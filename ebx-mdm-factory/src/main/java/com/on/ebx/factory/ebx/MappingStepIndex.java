package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;

/**
 * Audit F2-100 (2026-10-24): every {@code MappingStep} row of the repository read ONCE, grouped by mapping, for the whole read of the Mapping matrix. The
 * matrix reader used to open {@code ./mapping='N'} on this shared table (no index outside the primary key, CLAUDE.md section 5) twice per mapping of the
 * project - once for the cleaning steps of the sample, once for the code lists - i.e. 600 scans for a 300-mapping project, on each render of the wizard's
 * step 4, of the Validation desk and on each click of the matrix. One streaming read joined in memory is the loaders' own pattern (CLAUDE.md section 15).
 * <p>
 * Pure apart from {@link #scan(Adaptation)}: {@link #of(Source)} takes any {@link Source}, so the "one sweep" rule and the equivalence with the per-mapping
 * query are tested without a repository ({@code MappingStepIndexTest}).
 */
final class MappingStepIndex
{
	/** One row of {@code MappingStep}, the fields the matrix reads; {@link #row} is the record itself (null in tests). */
	static final class StepRow
	{
		final String mapping;
		final String status;
		final String kind;
		final String argument;
		final Integer position;
		final Adaptation row;

		StepRow(final String pMapping, final String pStatus, final String pKind, final String pArgument, final Integer pPosition, final Adaptation pRow)
		{
			this.mapping = pMapping;
			this.status = pStatus;
			this.kind = pKind;
			this.argument = pArgument;
			this.position = pPosition;
			this.row = pRow;
		}
	}

	/** Where the rows come from: ONE call of {@link #scan} is ONE sweep of the table. */
	interface Source
	{
		List<StepRow> scan();
	}

	/** Sweeps taken by {@link #scan(Adaptation)} since the JVM started - the counter the tests of the matrix read. */
	static final AtomicInteger SWEEPS = new AtomicInteger();

	private final Map<String, List<StepRow>> byMapping = new LinkedHashMap<String, List<StepRow>>();
	private int stepCount;

	private MappingStepIndex()
	{
	}

	/** Takes ONE sweep of {@code pSource} and groups what it returns by mapping, in the order the rows came. */
	static MappingStepIndex of(final Source pSource)
	{
		final MappingStepIndex index = new MappingStepIndex();
		for (final StepRow row : pSource.scan())
		{
			List<StepRow> list = index.byMapping.get(row.mapping);
			if (list == null)
			{
				list = new ArrayList<StepRow>();
				index.byMapping.put(row.mapping, list);
			}
			list.add(row);
			index.stepCount++;
		}
		return index;
	}

	/** The EBX-bound source: the whole {@code MappingStep} table, streamed once, as the engine (no permission filter - the readers that need the user's rows check each record). */
	static MappingStepIndex scan(final Adaptation pFactory)
	{
		return MappingStepIndex.of(new Source()
		{
			@Override
			public List<StepRow> scan()
			{
				MappingStepIndex.SWEEPS.incrementAndGet();
				final List<StepRow> out = new ArrayList<StepRow>();
				final RequestResult rows = pFactory.getTable(FactoryPaths.MAPPING_STEP).createRequestResult(null);
				try
				{
					for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
					{
						final Object position = row.get(FactoryPaths.MappingStep.POSITION);
						out.add(new StepRow(row.getString(FactoryPaths.MappingStep.MAPPING), row.getString(FactoryPaths.MappingStep.STATUS),
							row.getString(FactoryPaths.MappingStep.KIND), row.getString(FactoryPaths.MappingStep.ARGUMENT),
							position instanceof Number ? Integer.valueOf(((Number) position).intValue()) : null, row));
					}
				}
				finally
				{
					rows.close();
				}
				return out;
			}
		});
	}

	/** The rows of one mapping, in table order; empty when it has none. */
	List<StepRow> rowsOf(final String pMappingId)
	{
		final List<StepRow> rows = pMappingId == null ? null : this.byMapping.get(pMappingId);
		return rows == null ? Collections.<StepRow>emptyList() : rows;
	}

	int mappingCount()
	{
		return this.byMapping.size();
	}

	int stepCount()
	{
		return this.stepCount;
	}

	/**
	 * The ACCEPTED-or-APPLIED steps of one mapping in {@code position} order (a step with no position last, ties in table order) - exactly what {@code
	 * StagingLoader#readCleaningSteps} returns for the same rows, from the rows already in memory.
	 */
	static List<CleaningSteps.Step> cleaningStepsOf(final List<StepRow> pRows)
	{
		final List<StepRow> rows = new ArrayList<StepRow>();
		for (final StepRow row : pRows)
		{
			if (TechnicalKeyRule.isAcceptedLike(row.status))
			{
				rows.add(row);
			}
		}
		Collections.sort(rows, new Comparator<StepRow>()
		{
			@Override
			public int compare(final StepRow pLeft, final StepRow pRight)
			{
				return Integer.compare(pLeft.position == null ? Integer.MAX_VALUE : pLeft.position.intValue(),
					pRight.position == null ? Integer.MAX_VALUE : pRight.position.intValue());
			}
		});
		final List<CleaningSteps.Step> out = new ArrayList<CleaningSteps.Step>();
		for (final StepRow row : rows)
		{
			out.add(new CleaningSteps.Step(row.kind, row.argument));
		}
		return out;
	}
}
