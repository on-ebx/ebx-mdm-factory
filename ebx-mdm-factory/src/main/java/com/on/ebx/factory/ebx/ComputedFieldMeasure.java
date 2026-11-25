package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.staging.StagingModel;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.schema.Path;

/**
 * What a computed field ({@code StagingAttribute.derivation = SCRIPT}) really did on the staging table, per source
 * (fix/matrix-computed-field, docs/MAPPING-MATRIX.md sec 14): how many of the values the function reads were
 * converted and how many came out empty, with one real value before -&gt; after of each kind. There is no way to run
 * EBX Script from a render, so the numbers are READ. Since lot T1 (2026-10-04) the computed field is a STORED field of the staging
 * table, written by two table triggers when a row is created or its source changes, so what is read is what the triggers stored
 * (before that it was a value-function field that EBX computed on {@code Adaptation#get}). A row loaded before the derivation was
 * applied and not yet backfilled ({@code ebx.DerivationBackfillPass}) reads as empty here.
 * <p>
 * <b>Cost, and why it is bounded.</b> The bound below was sized for a value-function field, which cannot be filtered or indexed (EBX
 * SQL: "SELECT-only"; a predicate on it is refused) and runs the script once per row read; it is kept as is for the stored field. So per source this makes two counts on the
 * INDEXED fields (the key prefix {@code sourceSystem}, and {@code osd:is-not-null} on the field the function reads)
 * and evaluates the function on at most {@link #SAMPLE_CAP} rows of the filled ones, in key order - never a scan of the
 * table per source. The result says "on the first N of M filled values" when it is a sample, never a full count it
 * did not make. Results are cached ({@link #CACHE_MILLIS}) under the entity's last staging load and the suggestion's
 * status, so the clicks of a person who is reviewing never re-run it.
 * <p>
 * Nothing is measured when it would measure nothing: before the first load of the entity ({@code lastLoadAt} empty:
 * "measured after the first staging load"), or while the function is not applied to the staging model (the suggestion is
 * not {@code APPLIED}: the field is a plain empty field there, its emptiness says nothing about the function).
 */
public final class ComputedFieldMeasure
{
	/** Rows evaluated per source. The function runs once per row: 3 sources cost 1 500 evaluations at most. */
	static final int SAMPLE_CAP = 500;
	private static final long CACHE_MILLIS = 300000L;
	private static final int CACHE_MAX = 64;

	private ComputedFieldMeasure()
	{
	}

	private static final class Cached
	{
		final MatrixModel.Measurement value;
		final long at;

		Cached(final MatrixModel.Measurement pValue, final long pAt)
		{
			this.value = pValue;
			this.at = pAt;
		}
	}

	private static final Map<String, Cached> CACHE = new LinkedHashMap<String, Cached>();

	/**
	 * @param pStagingDataspace {@code Project.stagingDataspace} (empty when not generated)
	 * @param pStagingDataset {@code Project.stagingDataset}
	 * @param pEntityName the staging entity's name: its table is {@code /root/<name>}
	 * @param pLastLoadAt {@code StagingEntity.lastLoadAt}, null before the first staging load
	 * @param pSuggestionStatus status of the {@code SCRIPT_DERIVATION} suggestion
	 * @param pSourceField the attribute the function reads ({@code StagingAttribute.derivedFrom}'s name)
	 * @param pComputedField the computed attribute's name
	 * @param pSourceSystems the project's source systems, in display order
	 */
	public static MatrixModel.Measurement measure(final Adaptation pFactory, final String pStagingDataspace, final String pStagingDataset, final String pEntityName,
		final Date pLastLoadAt, final String pSuggestionStatus, final String pSourceField, final String pComputedField, final List<String> pSourceSystems)
	{
		if (pLastLoadAt == null)
		{
			return MatrixModel.Measurement.notLoaded();
		}
		if (!ReviewLine.APPLIED.equals(pSuggestionStatus))
		{
			return MatrixModel.Measurement.notApplied();
		}
		if (pStagingDataspace == null || pStagingDataspace.isEmpty() || pStagingDataset == null || pStagingDataset.isEmpty() || pSourceField == null
			|| pSourceField.isEmpty())
		{
			return MatrixModel.Measurement.unavailable("the staging dataset is not known");
		}
		final String key = pStagingDataspace + "|" + pStagingDataset + "|" + pEntityName + "|" + pComputedField + "|" + pLastLoadAt.getTime() + "|" + pSuggestionStatus;
		final long now = System.currentTimeMillis();
		synchronized (ComputedFieldMeasure.CACHE)
		{
			final Cached cached = ComputedFieldMeasure.CACHE.get(key);
			if (cached != null && now - cached.at < ComputedFieldMeasure.CACHE_MILLIS)
			{
				return cached.value;
			}
		}
		MatrixModel.Measurement result;
		try
		{
			result = ComputedFieldMeasure.read(pFactory, pStagingDataspace, pStagingDataset, pEntityName, pSourceField, pComputedField, pSourceSystems);
		}
		catch (final RuntimeException ex)
		{
			result = MatrixModel.Measurement.unavailable(ex.getMessage() == null ? ex.toString() : ex.getMessage());
		}
		synchronized (ComputedFieldMeasure.CACHE)
		{
			if (ComputedFieldMeasure.CACHE.size() >= ComputedFieldMeasure.CACHE_MAX)
			{
				ComputedFieldMeasure.CACHE.remove(ComputedFieldMeasure.CACHE.keySet().iterator().next());
			}
			ComputedFieldMeasure.CACHE.put(key, new Cached(result, now));
		}
		return result;
	}

	private static MatrixModel.Measurement read(final Adaptation pFactory, final String pStagingDataspace, final String pStagingDataset, final String pEntityName,
		final String pSourceField, final String pComputedField, final List<String> pSourceSystems)
	{
		final AdaptationHome home = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(pStagingDataspace));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pStagingDataset));
		if (dataset == null)
		{
			return MatrixModel.Measurement.unavailable("staging dataset " + pStagingDataspace + "/" + pStagingDataset + " not found");
		}
		final AdaptationTable table = dataset.getTable(Path.parse(StagingModel.tablePath(pEntityName)));
		final Path sourcePath = Path.parse("./" + pSourceField);
		final Path computedPath = Path.parse("./" + pComputedField);
		if (table.getTableOccurrenceRootNode().getNode(sourcePath) == null || table.getTableOccurrenceRootNode().getNode(computedPath) == null)
		{
			return MatrixModel.Measurement.unavailable("the staging table has no field " + pSourceField + " / " + pComputedField + " (regenerate the staging)");
		}
		final List<MatrixModel.SourceCount> counts = new ArrayList<MatrixModel.SourceCount>();
		for (final String source : pSourceSystems)
		{
			final String bySource = "./sourceSystem=" + CatalogReader.quote(source);
			final String filledPredicate = bySource + " and osd:is-not-null(./" + pSourceField + ")";
			final long total = ComputedFieldMeasure.count(table, bySource);
			final long filled = total == 0 ? 0 : ComputedFieldMeasure.count(table, filledPredicate);
			final Tally tally = new Tally();
			if (filled > 0)
			{
				final RequestResult rows = table.createRequestResult(filledPredicate);
				try
				{
					for (Adaptation row = rows.nextAdaptation(); row != null && tally.sampled < ComputedFieldMeasure.SAMPLE_CAP; row = rows.nextAdaptation())
					{
						final Object before = row.get(sourcePath);
						final Object after = row.get(computedPath);
						tally.add(before == null ? "" : String.valueOf(before), after == null ? "" : String.valueOf(after));
					}
				}
				finally
				{
					rows.close();
				}
			}
			counts.add(tally.toCount(source, total, filled));
		}
		return MatrixModel.Measurement.measured(ComputedFieldMeasure.SAMPLE_CAP, counts);
	}

	private static long count(final AdaptationTable pTable, final String pPredicate)
	{
		final RequestResult rows = pTable.createRequestResult(pPredicate);
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}

	/** Counts what the function did to the rows read, keeping the first value of each kind as the example - pure, unit-tested. */
	static final class Tally
	{
		int sampled;
		int converted;
		int empty;
		String convertedBefore;
		String convertedAfter;
		String emptyBefore;

		void add(final String pBefore, final String pAfter)
		{
			this.sampled++;
			if (pAfter == null || pAfter.trim().isEmpty())
			{
				this.empty++;
				if (this.emptyBefore == null)
				{
					this.emptyBefore = pBefore;
				}
			}
			else
			{
				this.converted++;
				if (this.convertedBefore == null)
				{
					this.convertedBefore = pBefore;
					this.convertedAfter = pAfter;
				}
			}
		}

		MatrixModel.SourceCount toCount(final String pSource, final long pTotal, final long pFilled)
		{
			return new MatrixModel.SourceCount(pSource, pTotal, pFilled, this.sampled, this.converted, this.empty, this.convertedBefore, this.convertedAfter,
				this.emptyBefore);
		}
	}
}
