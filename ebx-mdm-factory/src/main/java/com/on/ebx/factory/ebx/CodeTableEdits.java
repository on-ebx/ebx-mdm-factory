package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.CodeMap;
import com.on.ebx.factory.staging.NoteTexts;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Codes transcoding lot H3 (2026-10-17): the "Edit" of the Mapping matrix's code table. A person changes the target of one or several observed codes; the edit is
 * written back to the {@code MappingStep.argument} of the proposal - the factory's own table, never a reference table of the instance - and only while the step is still
 * {@code PROPOSED} (a decided step is not rewritten behind the decision). The step then belongs to a person: origin {@code USER}, the rationale says so, and no later
 * proposal run replaces it ({@code CodeTranscodingSuggester} leaves an attribute that already has a code table alone). The evidence counters and the confidence are
 * recomputed from the rows recorded in the table: a table still lacking a target for some code carries a confidence under the bulk-accept bar.
 */
public final class CodeTableEdits
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int MAX_EDITS = 200;
	private static final int MAX_TARGET_LENGTH = 100;
	private static final int RATIONALE_MAX_LENGTH = 2000;
	/** Appended to the rationale of an edited step. */
	static final String EDITED = " Edited by a person.";

	private CodeTableEdits()
	{
	}

	/** What an edit did. */
	public static final class Result
	{
		public boolean ok;
		public String reason;
		/** Codes left without a target after the edit. */
		public long unresolvedRows;
	}

	/**
	 * @param pStepId the {@code MappingStep} row
	 * @param pEditsJson a JSON object {@code {"<observed code>":"<target>"}}; a code that is not in the table is ignored, an empty target empties it
	 */
	public static Result save(final Session pSession, final Adaptation pFactory, final String pStepId, final String pEditsJson)
	{
		final Result out = new Result();
		final Adaptation step = pStepId == null || pStepId.trim().isEmpty() ? null
			: pFactory.getTable(FactoryPaths.MAPPING_STEP).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pStepId));
		if (step == null)
		{
			return CodeTableEdits.refuse(out, "This code table no longer exists: reload the page.");
		}
		if (!"PROPOSED".equals(step.getString(FactoryPaths.MappingStep.STATUS)))
		{
			return CodeTableEdits.refuse(out, "This code table was already decided: it is not edited any more.");
		}
		final CodeMap map = CleaningSteps.TRANSCODE_REFERENCE.equals(step.getString(FactoryPaths.MappingStep.KIND))
			? CodeMap.parse(step.getString(FactoryPaths.MappingStep.ARGUMENT)) : null;
		if (map == null)
		{
			return CodeTableEdits.refuse(out, "This step does not carry a code table.");
		}
		final Map<String, String> edits = new LinkedHashMap<String, String>();
		try
		{
			final JsonNode root = CodeTableEdits.MAPPER.readTree(pEditsJson == null ? "" : pEditsJson);
			if (root == null || !root.isObject())
			{
				return CodeTableEdits.refuse(out, "The edit is not readable.");
			}
			final Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				final String target = field.getValue().asText("").trim();
				if (target.length() > CodeTableEdits.MAX_TARGET_LENGTH)
				{
					return CodeTableEdits.refuse(out, "A target code is longer than " + CodeTableEdits.MAX_TARGET_LENGTH + " characters.");
				}
				edits.put(field.getKey(), target);
				if (edits.size() > CodeTableEdits.MAX_EDITS)
				{
					return CodeTableEdits.refuse(out, "Too many codes in one edit.");
				}
			}
		}
		catch (final java.io.IOException ex)
		{
			return CodeTableEdits.refuse(out, "The edit is not readable.");
		}
		final CodeMap edited = map.withTargets(edits);
		if (!edited.conflicts().isEmpty())
		{
			return CodeTableEdits.refuse(out, "Two codes that read the same once case and punctuation are ignored cannot go to different targets.");
		}
		final String json = edited.toJson();
		if (json.length() > CodeMap.MAX_JSON_LENGTH)
		{
			return CodeTableEdits.refuse(out, "The table would be too long to be stored: shorten the target codes.");
		}
		final CodeMap.Coverage coverage = edited.coverageFromCounts();
		final Object rawConfidence = step.get(FactoryPaths.MappingStep.CONFIDENCE);
		final int previous = rawConfidence instanceof Number ? ((Number) rawConfidence).intValue() : 80;
		final int confidence = coverage.complete() ? Math.min(Math.max(previous, 60), 85) : Math.min(coverage.percent(), NormalisationSuggester.FLAG_CONFIDENCE_CAP);
		final String rationale = step.getString(FactoryPaths.MappingStep.RATIONALE);
		final String base = rationale == null ? "" : rationale;
		// staging last points (2026-10-17): the note of a code table step is coded - the sentence is added to it, before its marker, so a screen still words it
		final String coded = NoteTexts.withSegment(base, NoteTexts.C_EDITED, CodeTableEdits.RATIONALE_MAX_LENGTH);
		final String newRationale = coded != null ? coded
			: TextLimits.truncate(base.endsWith(CodeTableEdits.EDITED) ? base : base + CodeTableEdits.EDITED, CodeTableEdits.RATIONALE_MAX_LENGTH);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(step.getAdaptationName());
				value.setValue(json, FactoryPaths.MappingStep.ARGUMENT);
				value.setValue("USER", FactoryPaths.MappingStep.ORIGIN);
				value.setValue(Integer.valueOf(confidence), FactoryPaths.MappingStep.CONFIDENCE);
				value.setValue(newRationale, FactoryPaths.MappingStep.RATIONALE);
				value.setValue(BigDecimal.valueOf(coverage.rows), FactoryPaths.MappingStep.VALUES_SEEN);
				value.setValue(BigDecimal.valueOf(coverage.changed), FactoryPaths.MappingStep.VALUES_CHANGED);
				value.setValue(BigDecimal.valueOf(coverage.unresolved), FactoryPaths.MappingStep.VALUES_UNRESOLVED);
				value.setValue(new Date(), FactoryPaths.MappingStep.CHECKED_AT);
				pContext.doModifyContent(step, value);
			}
		});
		if (result.hasFailed())
		{
			return CodeTableEdits.refuse(out, result.getExceptionFullMessage(Locale.ENGLISH));
		}
		out.ok = true;
		out.unresolvedRows = coverage.unresolved;
		return out;
	}

	private static Result refuse(final Result pOut, final String pReason)
	{
		pOut.ok = false;
		pOut.reason = pReason;
		return pOut;
	}
}
