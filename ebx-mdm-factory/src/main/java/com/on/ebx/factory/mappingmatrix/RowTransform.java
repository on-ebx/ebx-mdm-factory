package com.on.ebx.factory.mappingmatrix;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.MappingEngine;
import com.on.ebx.connect.core.MappingRule;
import com.on.ebx.connect.core.TransformationContext;
import com.on.ebx.factory.quality.CleaningSteps;
import com.orchestranetworks.service.Session;

/**
 * What the loader makes of ONE landing row for ONE mapping - the single place the Mapping matrix' "before -&gt; after" sample ({@code ebx.MappingMatrixReader#sample}) and the
 * Validation desk's preview of real rows ({@code ebx.DeskPreviewReader} / {@code stagingdesk.DeskPreview}) get a value from, so the two cannot drift (docs/STAGING-DESK.md L2).
 * It is {@code StagingLoader#processRow} in miniature, with no second transformation engine: the mapping's ACCEPTED-or-APPLIED cleaning steps first
 * ({@link CleaningSteps#applyPipeline}, read by the caller with {@code StagingLoader#readCleaningSteps}), then the mapping's resolved pipeline through the Connectors engine
 * ({@link MappingEngine#compute}) on the same row document ({@code StagingLoader#buildRowDocument}). Pure: no EBX repository access - the caller supplies the document, the steps,
 * the scope value, the lookups (the sample passes {@link CleaningSteps.Lookups#empty()} and says so in its note, the desk passes the real ones) and the session.
 * <p>
 * Also the home of the wording a preview that cannot be computed gives ({@code REASON_*}): the matrix' {@code Sample.unavailable} texts, shared so the desk quotes the matrix.
 */
public final class RowTransform
{
	/** The matrix' own words for "no value can be shown for this feed" - see {@code MatrixModel.Sample#unavailable}. */
	public static final String REASON_COLLECTION = "no sample for collection feeds: the loader reads a sub-element of the row this method does not resolve";
	public static final String REASON_NO_LANDING = "no landing table resolved";
	public static final String REASON_RUNTIME_LOOKUP = "pipeline needs a live lookup (fk/lookup/golden/mdmstate)";
	public static final String REASON_DATASET_NOT_FOUND = "landing dataset not found";
	public static final String REASON_EMPTY_TABLE = "landing table is empty";
	/** Caveat on a value computed without the reference lookup a cleaning step needed. */
	public static final String NOTE_BEFORE_CLEANING = "sample before cleaning steps: a cleaning step needs a reference lookup this screen cannot provide";

	private RowTransform()
	{
	}

	/** One row's outcome: the produced value (null when the pipeline made nothing), a caveat, or the engine's error. */
	public static final class Result
	{
		/** The value as the loader would stage it, null when empty. */
		public final String after;
		/** "" or {@link #NOTE_BEFORE_CLEANING}. */
		public final String note;
		/** The engine's first error, or null. */
		public final String error;

		Result(final String pAfter, final String pNote, final String pError)
		{
			this.after = pAfter;
			this.note = pNote == null ? "" : pNote;
			this.error = pError;
		}

		public boolean failed()
		{
			return this.error != null;
		}
	}

	/** Whether the pipeline reads reference data only the load has ({@code fk}/{@code lookup}/{@code golden}/{@code mdmstate}): no value can be computed ahead of the load. */
	public static boolean needsRuntimeLookup(final String pPipeline)
	{
		if (pPipeline == null)
		{
			return false;
		}
		final String lower = pPipeline.toLowerCase(Locale.ROOT);
		return lower.contains("fk(") || lower.contains("lookup(") || lower.contains("golden(") || lower.contains("mdmstate(");
	}

	/**
	 * Runs one mapping on one row. {@code pDocument} is the row's own document and IS MODIFIED (the cleaning steps rewrite the mapping's field in it): pass a copy when the same
	 * document serves another mapping. {@code pScopeValue} is the value of the scope field a cleaning step names ({@link CleaningSteps#scopeFieldOf}), "" when none. Never throws:
	 * an engine failure is the {@link Result#error}.
	 */
	public static Result apply(final ObjectNode pDocument, final String pFieldName, final List<CleaningSteps.Step> pCleaningSteps, final String pScopeValue,
		final String pPipeline, final CleaningSteps.Lookups pLookups, final Session pSession)
	{
		try
		{
			final String raw = RowTransform.text(pDocument, pFieldName);
			String note = "";
			if (pCleaningSteps != null && !pCleaningSteps.isEmpty())
			{
				final CleaningSteps.Result cleaned = CleaningSteps.applyPipeline(pCleaningSteps, raw, pScopeValue, pLookups == null ? CleaningSteps.Lookups.empty() : pLookups);
				if (cleaned.unresolved)
				{
					// never lie: the pipeline below runs on the RAW value, not the (unreliable, lookup-less) "cleaned" one, and the caller says so
					note = RowTransform.NOTE_BEFORE_CLEANING;
				}
				else if (cleaned.value == null)
				{
					pDocument.putNull(pFieldName);
				}
				else
				{
					pDocument.put(pFieldName, cleaned.value);
				}
			}
			final MappingDefinition definition = new MappingDefinition(0, "mdm-factory", "mapping-matrix-sample", MappingDefinition.Direction.INBOUND, "sample", null, null, "/root/Sample",
				"", true);
			definition.addRule(new MappingRule(0, pFieldName, "./sample", pPipeline, MappingRule.Policy.ALWAYS, null));
			final TransformationContext ctx = new TransformationContext(null, null, pSession);
			final MappingEngine.Values values = MappingEngine.compute(definition, pDocument, ctx);
			if (!values.getErrors().isEmpty())
			{
				return new Result(null, note, values.getErrors().get(0));
			}
			final String after = values.get("./sample");
			return new Result(after, note, null);
		}
		catch (final Exception ex) // MappingEngine.compute's own checked Exception, plus any runtime error of a step - never breaks a page
		{
			return new Result(null, "", ex.getMessage() == null ? ex.toString() : ex.getMessage());
		}
	}

	/** The raw text of one field of a row document, "" when absent or null (what {@code MappingMatrixReader#text} answers for the row itself). */
	public static String text(final ObjectNode pDocument, final String pFieldName)
	{
		if (pDocument == null || pFieldName == null || pFieldName.isEmpty())
		{
			return "";
		}
		final com.fasterxml.jackson.databind.JsonNode node = pDocument.get(pFieldName);
		return node == null || node.isNull() ? "" : node.asText();
	}
}
