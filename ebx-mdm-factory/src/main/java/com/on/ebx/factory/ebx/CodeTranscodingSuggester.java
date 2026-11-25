package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.agent.McpException;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.KeyLlmPass;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.CodeMap;
import com.on.ebx.factory.staging.CodeTranscodingPass;
import com.on.ebx.factory.staging.NormalisationPass;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Codes transcoding lot H3 (2026-10-17, docs/CODES-TRANSCODING.md): the EBX side of {@link CodeTranscodingPass}. Lot D's detector
 * ({@link NormalisationPass.Alignment}) finds the attributes that several sources feed with short code lists that do not line up; this class
 * asks the language model about each of them (one {@link AgentRuntime} call per business object) and writes what it answers as {@code MappingStep}
 * rows - kind {@code TRANSCODE_REFERENCE} with a {@link CodeMap} argument, origin {@code LLM}, status {@code PROPOSED} - one per source whose codes
 * change, that a person decides in the Mapping matrix (Accept, Edit, Reject). Nothing is accepted here and no mapping row is touched.
 * <p>
 * <b>Why a {@code MappingStep} and not a {@code map(...)} pipeline</b>: the loader already runs a mapping's accepted steps in order, before its pipeline
 * ({@code StagingLoader#readCleaningSteps}, {@code CleaningSteps#applyPipeline}) and the matrix, the proposal review and the bulk accepts already decide
 * {@code MappingStep} rows one by one. A {@code map(...)} would have to go into {@code AttributeMapping.pipeline}, the mapping's single pipeline, which
 * may already hold the model's own conversion, and its acceptance would be the whole mapping's. The step is a reviewable item of its own, can be
 * rejected without touching the link, and a person's edit stays in the factory's table.
 * <p>
 * <b>Asked at most once per attribute</b>: an attribute where any source already has a code table of its own - in any status, a rejected one included - is
 * left to its people ("a row a person decided on is never touched"); the detector's counts-only flag stays on the links. Without a language model
 * connector nothing here runs.
 */
public final class CodeTranscodingSuggester
{
	static final String STEP = "CODES_TRANSCODE_LLM";
	private static final String PHASE = "CODES_TRANSCODE_LLM";
	private static final int RATIONALE_MAX_LENGTH = 2000;
	private static final int EXAMPLES_MAX_LENGTH = 2000;

	private CodeTranscodingSuggester()
	{
	}

	/** What a run did. */
	public static final class Result
	{
		/** Attributes put to the model. */
		public int attributesAsked;
		/** Attributes the model answered with a valid proposal. */
		public int attributesProposed;
		public int stepsWritten;
		/** Attributes left alone because a source already has a code table. */
		public int attributesSkipped;
		/** Of the steps written, how many do not cover every code (a person completes them). */
		public int stepsIncomplete;
		public final List<String> notes = new ArrayList<String>();

		public boolean isEmpty()
		{
			return this.attributesAsked == 0 && this.stepsWritten == 0;
		}
	}

	/**
	 * @param pAlignments {@code NormalisationSuggester.Outcome#alignments} of the run just made
	 * @param pLocale unused for now (the steps' wording is English, like every RULE/LLM row)
	 */
	public static Result suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<NormalisationPass.Alignment> pAlignments,
		final Locale pLocale) throws OperationException
	{
		final Result result = new Result();
		if (pAlignments == null || pAlignments.isEmpty())
		{
			return result;
		}
		final Adaptation project = CodeTranscodingSuggester.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		ProjectOrigin.refuseIfDelivered(pFactory, project);
		final String projectId = project.getOccurrencePrimaryKey().format();
		final LlmDataPolicy policy = LlmDataPolicy.of(pFactory);
		final LlmDataPolicy.Level level = policy.levelFor(project);
		final CleaningSteps.Lookups lookups = QualitySuggester.buildLookups(pFactory, result.notes);
		final List<CodeTranscodingPass.Reference> references = new ArrayList<CodeTranscodingPass.Reference>();
		for (final String kind : lookups.flatKinds())
		{
			final java.util.Set<String> codes = lookups.codesOf(kind);
			if (!codes.isEmpty())
			{
				references.add(new CodeTranscodingPass.Reference(kind, codes));
			}
		}

		// group by business object, drop what is not to be asked
		final Map<String, List<CodeTranscodingPass.Request>> byEntity = new LinkedHashMap<String, List<CodeTranscodingPass.Request>>();
		for (final NormalisationPass.Alignment alignment : pAlignments)
		{
			if (alignment.hasCodeTable() || CodeTranscodingSuggester.anyMemberHasATable(pFactory, alignment))
			{
				result.attributesSkipped++;
				continue;
			}
			final List<CodeTranscodingPass.Source> sources = CodeTranscodingPass.sourcesOf(alignment);
			if (sources.size() < 2)
			{
				continue;
			}
			boolean sensitive = policy.isSensitive(alignment.attributeName);
			for (final CodeTranscodingPass.Source source : sources)
			{
				sensitive = sensitive || policy.isSensitive(source.sourceColumn);
			}
			final KeyLlmPass.Sharing sharing = sensitive ? KeyLlmPass.Sharing.NONE : LlmDataPolicy.sharingOf(level);
			final CodeTranscodingPass.Request request = new CodeTranscodingPass.Request(alignment.attributeId, alignment.entityName, alignment.attributeName,
				CodeTranscodingSuggester.labelOf(pFactory, alignment.attributeId), sharing, sources);
			List<CodeTranscodingPass.Request> list = byEntity.get(alignment.entityName);
			if (list == null)
			{
				list = new ArrayList<CodeTranscodingPass.Request>();
				byEntity.put(alignment.entityName, list);
			}
			list.add(request);
		}

		for (final Map.Entry<String, List<CodeTranscodingPass.Request>> entry : byEntity.entrySet())
		{
			final List<CodeTranscodingPass.Request> requests = entry.getValue();
			result.attributesAsked += requests.size();
			final CodeTranscodingPass.Session llm = new CodeTranscodingPass.Session(requests, references, lookups);
			final ToolSet tools;
			try
			{
				tools = ToolSet.build(Collections.<String, ToolSet.Server> emptyMap(), Collections.singletonMap("factory", llm.toolGroup()));
			}
			catch (final McpException ex)
			{
				result.notes.add("could not build the agent's tool set: " + ex.getMessage());
				continue;
			}
			final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, CodeTranscodingSuggester.STEP, entry.getKey().isEmpty() ? "project" : entry.getKey());
			try
			{
				final AgentRuntime.Outcome outcome = AgentRuntime.run(pSession, pFactory, projectId, CodeTranscodingSuggester.PHASE,
					"Align the code lists of " + requests.size() + " attribute(s) of " + entry.getKey() + " across their sources.", CodeTranscodingPass.systemPrompt(),
					CodeTranscodingPass.userPrompt(entry.getKey(), requests, references), tools);
				result.notes.addAll(llm.notes());
				int written = 0;
				int incomplete = 0;
				final List<CodeTranscodingPass.Proposal> proposals = llm.proposals();
				result.attributesProposed += proposals.size();
				for (final CodeTranscodingPass.Proposal proposal : proposals)
				{
					final List<CodeTranscodingPass.Draft> drafts = CodeTranscodingPass.drafts(proposal, NormalisationSuggester.FLAG_CONFIDENCE_CAP);
					written += CodeTranscodingSuggester.write(pSession, pFactory, drafts);
					for (final CodeTranscodingPass.Draft draft : drafts)
					{
						if (!draft.complete)
						{
							incomplete++;
						}
					}
				}
				result.stepsWritten += written;
				result.stepsIncomplete += incomplete;
				PipelineSteps.end(pSession, pFactory, stepId, true, written + " code table(s) proposed for " + proposals.size() + " of " + requests.size()
					+ " attribute(s) of " + entry.getKey() + " (agent run " + outcome.runId + ", " + AgentRuntime.statusOf(outcome.status) + ").", null);
			}
			catch (final OperationException ex)
			{
				PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), null);
				throw ex;
			}
			catch (final RuntimeException | Error ex)
			{
				StepGuard.endUnexpected(pSession, pFactory, stepId, ex, null);
				throw ex;
			}
		}
		LoggingCategory.getKernel().info("[mdm-factory] code lists " + pProjectCode + ": " + result.attributesAsked + " attribute(s) asked, " + result.attributesProposed
			+ " proposed, " + result.stepsWritten + " step(s) written (" + result.stepsIncomplete + " incomplete), " + result.attributesSkipped + " skipped.");
		return result;
	}

	// ------------------------------------------------------------------------------------------------------ reads

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	private static String labelOf(final Adaptation pFactory, final String pAttributeId)
	{
		final Adaptation attribute = pAttributeId == null || pAttributeId.trim().isEmpty() ? null
			: pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pAttributeId));
		final String label = attribute == null ? null : attribute.getString(FactoryPaths.StagingAttribute.LABEL);
		return label == null ? "" : label;
	}

	/** True when a member mapping has a step with a code table of its own, in ANY status (a rejected one is a person's decision). */
	private static boolean anyMemberHasATable(final Adaptation pFactory, final NormalisationPass.Alignment pAlignment)
	{
		for (final NormalisationPass.Member member : pAlignment.members)
		{
			final RequestResult rows = pFactory.getTable(FactoryPaths.MAPPING_STEP).createRequestResult("./mapping=" + CatalogReader.quote(member.mappingId));
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					if (CleaningSteps.TRANSCODE_REFERENCE.equals(row.getString(FactoryPaths.MappingStep.KIND))
						&& CodeMap.parseCached(row.getString(FactoryPaths.MappingStep.ARGUMENT)) != null)
					{
						return true;
					}
				}
			}
			finally
			{
				rows.close();
			}
		}
		return false;
	}

	private static int lastPosition(final Adaptation pFactory, final String pMappingId)
	{
		int last = 0;
		final RequestResult rows = pFactory.getTable(FactoryPaths.MAPPING_STEP).createRequestResult("./mapping=" + CatalogReader.quote(pMappingId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object position = row.get(FactoryPaths.MappingStep.POSITION);
				if (position instanceof Number)
				{
					last = Math.max(last, ((Number) position).intValue());
				}
			}
		}
		finally
		{
			rows.close();
		}
		return last;
	}

	// ----------------------------------------------------------------------------------------------------- writes

	private static int write(final Session pSession, final Adaptation pFactory, final List<CodeTranscodingPass.Draft> pDrafts) throws OperationException
	{
		if (pDrafts.isEmpty())
		{
			return 0;
		}
		final AdaptationTable steps = pFactory.getTable(FactoryPaths.MAPPING_STEP);
		final Map<String, Integer> positions = new LinkedHashMap<String, Integer>();
		for (final CodeTranscodingPass.Draft draft : pDrafts)
		{
			positions.put(draft.mappingId, Integer.valueOf(CodeTranscodingSuggester.lastPosition(pFactory, draft.mappingId) + 1));
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final CodeTranscodingPass.Draft draft : pDrafts)
				{
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(steps);
					value.setValue(draft.mappingId, FactoryPaths.MappingStep.MAPPING);
					value.setValue(positions.get(draft.mappingId), FactoryPaths.MappingStep.POSITION);
					value.setValue(CleaningSteps.TRANSCODE_REFERENCE, FactoryPaths.MappingStep.KIND);
					value.setValue(draft.argument, FactoryPaths.MappingStep.ARGUMENT);
					value.setValue("LLM", FactoryPaths.MappingStep.ORIGIN);
					value.setValue("PROPOSED", FactoryPaths.MappingStep.STATUS);
					value.setValue(Integer.valueOf(draft.confidence), FactoryPaths.MappingStep.CONFIDENCE);
					value.setValue(draft.storedRationale(CodeTranscodingSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.MappingStep.RATIONALE);
					value.setValue(BigDecimal.valueOf(draft.valuesSeen), FactoryPaths.MappingStep.VALUES_SEEN);
					value.setValue(BigDecimal.valueOf(draft.valuesChanged), FactoryPaths.MappingStep.VALUES_CHANGED);
					value.setValue(BigDecimal.valueOf(draft.valuesUnresolved), FactoryPaths.MappingStep.VALUES_UNRESOLVED);
					value.setValue(CodeTranscodingSuggester.examplesText(draft.examples), FactoryPaths.MappingStep.EXAMPLES);
					value.setValue(new Date(), FactoryPaths.MappingStep.CHECKED_AT);
					pContext.doCreateOccurrence(value, steps);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveMappingSteps", "Writing the code tables failed: " + result.getExceptionFullMessage(Locale.ENGLISH), "code lists", "");
		}
		return pDrafts.size();
	}

	private static String examplesText(final List<String> pExamples)
	{
		if (pExamples == null || pExamples.isEmpty())
		{
			return null;
		}
		final StringBuilder out = new StringBuilder();
		for (final String example : pExamples)
		{
			if (out.length() > 0)
			{
				out.append("; ");
			}
			out.append(example);
		}
		return TextLimits.truncate(out.toString(), CodeTranscodingSuggester.EXAMPLES_MAX_LENGTH);
	}
}
