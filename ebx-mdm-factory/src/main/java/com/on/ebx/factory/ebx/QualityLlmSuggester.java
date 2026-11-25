package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpException;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.quality.FailureBreakdown;
import com.on.ebx.factory.quality.QualityLlmPass;
import com.on.ebx.factory.quality.RuleFocus;
import com.on.ebx.factory.quality.Shapes;
import com.on.ebx.factory.staging.MappingScope;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Runs {@link QualityLlmPass} - the language-model proposer of normalisation/quality rules - over a
 * project's ACCEPTED-or-APPLIED mapped columns (the exact same scope {@code QualitySuggester}'s own
 * deterministic RULE pass reads), through {@link AgentRuntime}.
 * <p>
 * <b>Coordinator review, 2026-09-21 (real first run, OpenAI, project Companies): ONE agent run over all 60
 * mapped columns of 4 entities covered only the FIRST entity - the model declared itself done after one
 * entity, with 0 refused tool calls and budget to spare.</b> {@link #suggest} now runs ONE AGENT RUN PER
 * ACCEPTED ENTITY (an entity with at least one mapped column), sequentially, each scoped to only that
 * entity's columns (a fresh {@link QualityLlmPass.Session}, so the {@code measure} budget and the refusal
 * circuit breaker apply PER ENTITY, not once for the whole project) - the factory drives the iteration, the
 * model is never asked to self-manage sixty columns' worth of attention in one turn sequence. One entity's
 * own failure (an {@code OperationException} from {@link AgentRuntime#run} - a connector error - or any other
 * exception) is caught and recorded, never aborting the remaining entities - see {@link #runOneEntity}'s own
 * call site in {@link #suggest}. The route's response gains a per-entity block ({@code entity}, {@code
 * agentRunId}, {@code status}, token/step counts, {@code stepsCreated}/{@code rulesCreated}, {@code
 * earlyStop}, {@code notes}, {@code columnsWithoutProposalOrNote}) plus project-wide totals. The single
 * project-level {@code PipelineStep} this route has always recorded is {@code DONE} only when every entity's
 * own run status is {@code DONE} (an entity that errored, or whose {@code AgentLoop} itself reports {@code
 * FAILED}/{@code BUDGET}, is named in the step's own message/detail) - idempotency and "never touch a decided
 * row" are otherwise unchanged: each entity's {@link QualityLlmPass.Session} still refuses a proposal for a
 * kind already present on a column (whatever its status/origin) before it ever reaches {@link #write}.
 * <p>
 * An optional {@code entity} query parameter (validated against the project's own accepted-mapped entities,
 * {@link #suggest}'s own {@code pEntityFilter}) restricts the whole call to a single entity - useful to
 * re-run just the one entity a previous call left in a non-{@code DONE} state, without re-spending tokens on
 * every other entity that already finished cleanly.
 * <p>
 * <b>Coordinator review round 3, 2026-09-21 (the per-entity fix ran for real - OpenAI, {@code SAMPLE_VALUES},
 * 4 entities): it worked, but cost roughly the SQUARE of the column count in input tokens</b> (~480 000 for a
 * 60-column demo), because the round-2 prompt's own per-column {@code note} instruction made the model call
 * one tool per turn, mostly {@code note}, and every turn re-sends the whole conversation - see {@code
 * quality.QualityLlmPass}'s own class Javadoc and {@code docs/QUALITY-LLM.md} section 1.2 for the prompt-level
 * fix ({@code AgentLoop}/{@code LlmClient} already supported batching several tool calls per turn; nothing in
 * this file needed to change for that). What DID change here: {@link #runOneEntity} now GROUPS an entity's
 * accepted mappings by {@code entity.attribute} before building any {@code ColumnInfo}/evidence - the same
 * real run found a genuine duplicate {@code QualityRule} on {@code Company.country}, fed by two mappings that
 * were previously shown (and, worse, silently mismeasured) as two separate columns; see {@link #runOneEntity}'s
 * own Javadoc and {@code docs/QUALITY-LLM.md} section 1.3 for the full mechanism and the product owner's own
 * live decision on the resulting duplicate pair. {@link #write} changed to match: a {@code QualityRule} is
 * still written ONCE per attribute, a {@code MappingStep} is now written ONCE PER MAPPING of that attribute.
 */
public final class QualityLlmSuggester
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	// job-engine lot C3 (2026-09-24): package-visible (not private) so QualitySuggestLlmRun can look up the
	// PipelineStep row this class already opens/closes for its own STEP.
	static final String STEP = "QUALITY_SUGGEST_LLM";
	private static final String PHASE = "QUALITY_SUGGEST_LLM";
	private static final int DEFAULT_SAMPLE_SIZE = 20;
	/** Upper bound of {@code Project.llmSampleSize}: the most distinct real values per column that may ever reach the language model. */
	static final int MAX_SAMPLE_SIZE = 50;
	/** How many distinct shapes are shown per column in the prompt - enough to see the dominant pattern(s) without flooding the context. */
	private static final int MAX_SHAPES_SHOWN = 8;
	private static final int RATIONALE_MAX_LENGTH = 2000;
	private static final int EXAMPLES_MAX_LENGTH = 2000;

	private QualityLlmSuggester()
	{
	}

	/**
	 * {@link MappingScope#ACCEPTED_ONLY} - unchanged behaviour, the scope every pre-existing caller (including
	 * the wizard's stand-alone "Quality rules" button and every REST route) keeps using.
	 */
	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityFilter)
		throws OperationException
	{
		return QualityLlmSuggester.suggest(pSession, pFactory, pProjectCode, pEntityFilter, MappingScope.ACCEPTED_ONLY);
	}

	/**
	 * Staging-proposal-run lot (2026-09-24): {@code pScope} widens what counts as "in scope" for {@link
	 * QualitySuggester#readMappings} - see {@link MappingScope}'s own Javadoc. The one-click proposal run
	 * calls this with {@link MappingScope#PROPOSAL} right after the structure pass, while everything is still
	 * {@code PROPOSED}; every row this pass writes stays {@code PROPOSED} regardless of scope, so nothing here
	 * short-circuits review.
	 *
	 * @param pEntityFilter optional; when set, only this one in-scope entity is run (validated against the
	 * project's own in-scope-mapped entities - an unknown name is refused, naming the known ones).
	 */
	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityFilter,
		final MappingScope pScope) throws OperationException
	{
		// audit F-15: the pass writes the project's DESIGN proposals; the queued job already holds that resource, a REST call or a wizard action does not - see EngineClaim
		final EngineClaim claim = EngineClaim.enter(pFactory, pSession, pProjectCode, "QUALITY_SUGGEST_LLM_DIRECT", FactoryJobs.DESIGN);
		try
		{
			return QualityLlmSuggester.suggestClaimed(pSession, pFactory, pProjectCode, pEntityFilter, pScope);
		}
		finally
		{
			claim.release();
		}
	}

	/**
	 * Desk lot L4b (2026-10-22): the same pass NARROWED to one existing rule, run by the desk's "Measure" job once the breakdown is stored. Its entity and column are the rule's, the
	 * session accepts nothing but that rule given a condition ({@link RuleFocus}), and the prompt carries the rule's breakdown like any other run. Never throws for a model that
	 * cannot be asked: the caller reads {@link RuleFocus#asked} / {@link RuleFocus#notAskedReason}.
	 */
	public static RuleFocus suggestForRule(final Session pSession, final Adaptation pFactory, final String pRuleId) throws OperationException
	{
		final Adaptation rule = pFactory.getTable(FactoryPaths.QUALITY_RULE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRuleId));
		final RuleFocus focus = new RuleFocus(rule == null ? null : rule.getString(FactoryPaths.QualityRule.ATTRIBUTE),
			rule == null ? null : rule.getString(FactoryPaths.QualityRule.KIND), rule == null ? null : rule.getString(FactoryPaths.QualityRule.ARGUMENT));
		final Adaptation entity = rule == null ? null
			: pFactory.getTable(FactoryPaths.STAGING_ENTITY).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(rule.getString(FactoryPaths.QualityRule.ENTITY)));
		final Adaptation project = entity == null ? null
			: pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(entity.getString(FactoryPaths.StagingEntity.PROJECT)));
		if (project == null)
		{
			focus.notAskedReason = "the check or its project does not exist any more";
			return focus;
		}
		final String projectCode = project.getString(FactoryPaths.Project.CODE);
		final EngineClaim claim = EngineClaim.enter(pFactory, pSession, projectCode, "QUALITY_SUGGEST_LLM_DIRECT", FactoryJobs.DESIGN);
		try
		{
			final ObjectNode out = QualityLlmSuggester.suggestClaimed(pSession, pFactory, projectCode, entity.getString(FactoryPaths.StagingEntity.NAME),
				MappingScope.PROPOSAL, focus);
			if (focus.notAskedReason == null && !focus.asked)
			{
				// the run did not get as far as the model: its entity block says why
				final com.fasterxml.jackson.databind.JsonNode block = out.path("entities").path(0);
				focus.notAskedReason = block.path("error").asText(block.path("status").asText("the model did not answer"));
			}
			return focus;
		}
		finally
		{
			claim.release();
		}
	}

	private static ObjectNode suggestClaimed(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityFilter,
		final MappingScope pScope) throws OperationException
	{
		return QualityLlmSuggester.suggestClaimed(pSession, pFactory, pProjectCode, pEntityFilter, pScope, null);
	}

	private static ObjectNode suggestClaimed(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityFilter,
		final MappingScope pScope, final RuleFocus pFocus) throws OperationException
	{
		final Adaptation project = QualityLlmSuggester.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		// delivery lot 1: a project delivered here by EBX Staging is designed elsewhere - no suggestion on a target
		ProjectOrigin.refuseIfDelivered(pFactory, project);
		final String projectId = project.getOccurrencePrimaryKey().format();
		final String projectLabel = project.getString(FactoryPaths.Project.LABEL);
		// lot B (2026-09-24): the instance's LlmDataPolicy caps the project's own choice
		final LlmDataPolicy policy = LlmDataPolicy.of(pFactory);
		final QualityLlmPass.Mode mode = QualityLlmPass.Mode.valueOf(policy.levelFor(project).name());
		final Object rawSampleSize = project.get(FactoryPaths.Project.LLM_SAMPLE_SIZE);
		// security review (2026-09-24): the field is a bare xs:int, so an operator could send thousands of real values
		// per column to the language model; capped at MAX_SAMPLE_SIZE whatever the project says
		final int sampleSize = Math.min(QualityLlmSuggester.MAX_SAMPLE_SIZE, rawSampleSize instanceof Number && ((Number) rawSampleSize).intValue() > 0
			? ((Number) rawSampleSize).intValue() : QualityLlmSuggester.DEFAULT_SAMPLE_SIZE);

		final List<QualitySuggester.MappingRow> allMappings = QualitySuggester.readMappings(pFactory, projectId, pScope);
		if (allMappings.isEmpty())
		{
			throw EngineRefusal.coded("missing.noAcceptedMapping", "Project " + pProjectCode
				+ " has no accepted-or-applied mapping yet: run POST /projects/" + pProjectCode + "/staging/suggest and accept some mappings first.", pProjectCode);
		}

		// grouped in first-encounter order, so a re-run without an entity filter always processes entities in
		// the same sequence - not load-bearing for correctness, just makes a trace easier to read
		final Map<String, List<QualitySuggester.MappingRow>> mappingsByEntity = new LinkedHashMap<String, List<QualitySuggester.MappingRow>>();
		for (final QualitySuggester.MappingRow mapping : allMappings)
		{
			List<QualitySuggester.MappingRow> forEntity = mappingsByEntity.get(mapping.entityName);
			if (forEntity == null)
			{
				forEntity = new ArrayList<QualitySuggester.MappingRow>();
				mappingsByEntity.put(mapping.entityName, forEntity);
			}
			forEntity.add(mapping);
		}

		final List<String> entityOrder;
		if (pEntityFilter != null && !pEntityFilter.trim().isEmpty())
		{
			if (!mappingsByEntity.containsKey(pEntityFilter))
			{
				throw EngineRefusal.coded("missing.noMappingForEntity", "Project " + pProjectCode + " has no accepted-or-applied mapping for entity '" + pEntityFilter
					+ "': entities with mapped columns are " + mappingsByEntity.keySet() + ".", pProjectCode, pEntityFilter);
			}
			entityOrder = Collections.singletonList(pEntityFilter);
		}
		else
		{
			entityOrder = new ArrayList<String>(mappingsByEntity.keySet());
		}

		final List<String> setupNotes = new ArrayList<String>();
		final CleaningSteps.Lookups lookups = QualitySuggester.buildLookups(pFactory, setupNotes);
		final List<QualityLlmPass.ReferenceBindingInfo> referenceBindings = QualityLlmSuggester.readReferenceBindings(pFactory);
		final Map<String, QualitySuggester.LandingTableRef> landingTableCache = new HashMap<String, QualitySuggester.LandingTableRef>();
		final String landingDataspace = project.getString(FactoryPaths.Project.LANDING_DATASPACE);

		// desk lot L4b: a run narrowed to one rule is not the project's quality step and leaves no trace of it
		final String stepId = pFocus != null ? null : PipelineSteps.begin(pSession, pFactory, projectId, QualityLlmSuggester.STEP, "");
		final ObjectNode out = QualityLlmSuggester.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("valueSharing", mode.name());
		out.put("entitiesTotal", entityOrder.size());
		final ArrayNode entitiesOut = out.putArray("entities");
		final ArrayNode setupNotesNode = out.putArray("notes");
		for (final String note : setupNotes)
		{
			setupNotesNode.add(note);
		}

		int totalStepsCreated = 0;
		int totalRulesCreated = 0;
		final List<String> notNormalEntities = new ArrayList<String>();

		for (final String entityName : entityOrder)
		{
			final ObjectNode entityOut = entitiesOut.addObject();
			entityOut.put("entity", entityName);
			try
			{
				final EntityRunOutcome outcome = QualityLlmSuggester.runOneEntity(pSession, pFactory, projectId, pProjectCode, projectLabel, entityName,
					mappingsByEntity.get(entityName), mode, sampleSize, lookups, referenceBindings, landingDataspace, landingTableCache, pFocus);
				entityOut.put("agentRunId", outcome.runId);
				entityOut.put("status", outcome.status);
				// turns (LLM round trips - what the step budget actually counts) reported SEPARATELY from
				// toolCalls (coordinator review round 3, 2026-09-21: "make the expensive path visible" - a run
				// making one tool call per turn costs roughly the SQUARE of its column count in input tokens,
				// since every turn re-sends the whole conversation; a run batching several calls per turn does
				// not, and this is how the difference becomes visible without reading raw AgentStep rows)
				entityOut.put("turns", outcome.turns);
				entityOut.put("toolCalls", outcome.toolCalls);
				entityOut.put("inputTokens", outcome.inputTokens);
				entityOut.put("outputTokens", outcome.outputTokens);
				entityOut.put("refusedToolCalls", outcome.refusedToolCalls);
				if (outcome.detail != null)
				{
					entityOut.put("detail", outcome.detail);
				}
				entityOut.put("stepsCreated", outcome.stepsCreated);
				entityOut.put("rulesCreated", outcome.rulesCreated);
				entityOut.put("proseAnswerRecovered", outcome.proseAnswerRecovered);
				if ("NO_OUTPUT".equals(outcome.status))
				{
					entityOut.put("modelText", outcome.modelText);
				}
				entityOut.put("earlyStop", outcome.earlyStop);
				if (outcome.earlyStop)
				{
					entityOut.put("earlyStopReason", outcome.earlyStopReason);
				}
				final ArrayNode entityNotesNode = entityOut.putArray("notes");
				for (final String note : outcome.notes)
				{
					entityNotesNode.add(note);
				}
				final ArrayNode gapNode = entityOut.putArray("columnsWithoutProposalOrNote");
				for (final String column : outcome.columnsWithoutProposalOrNote)
				{
					gapNode.add(column);
				}

				totalStepsCreated += outcome.stepsCreated;
				totalRulesCreated += outcome.rulesCreated;
				if (!"DONE".equals(outcome.status))
				{
					notNormalEntities.add(entityName + " (" + outcome.status + ")");
				}
			}
			catch (final Exception ex)
			{
				// one entity's failure must never abort the others - caught broadly (not just OperationException):
				// this loop's whole point is resilience, and an unexpected RuntimeException is exactly the case
				// that must not take the remaining entities down with it
				entityOut.put("status", "ERROR");
				entityOut.put("error", ex.getMessage());
				notNormalEntities.add(entityName + " (ERROR: " + ex.getMessage() + ")");
			}
		}

		out.put("stepsCreated", totalStepsCreated);
		out.put("rulesCreated", totalRulesCreated);
		out.put("entitiesOk", entityOrder.size() - notNormalEntities.size());
		out.put("entitiesNotNormal", notNormalEntities.size());

		final boolean allNormal = notNormalEntities.isEmpty();
		final String message = totalStepsCreated + " cleaning step(s) and " + totalRulesCreated + " quality rule(s) proposed by the language model over "
			+ entityOrder.size() + " entity run(s)" + (allNormal ? "." : "; NOT DONE: " + QualityLlmSuggester.join(notNormalEntities) + ".");
		if (stepId != null)
		{
			PipelineSteps.end(pSession, pFactory, stepId, allNormal, message, out.toString());
		}
		return out;
	}

	// -------------------------------------------------------------------------------------------------- per entity

	/** What one entity's own agent run produced - {@link #suggest} folds this into the route's per-entity response block. */
	private static final class EntityRunOutcome
	{
		final String runId;
		/** {@code AgentRuntime.statusOf}'s own vocabulary: {@code DONE}/{@code FAILED}/{@code BUDGET} - "ended normally" (the project-level {@code PipelineStep}'s own DONE gate) means exactly {@code DONE}. */
		final String status;
		/** LLM round trips - see {@code AgentRuntime.Outcome#steps}'s own Javadoc for why this is reported separately from {@link #toolCalls}. */
		final int turns;
		/** Every tool call attempted this run, successful or refused - see {@code AgentRuntime.Outcome#toolCalls}. */
		final int toolCalls;
		final int inputTokens;
		final int outputTokens;
		final int refusedToolCalls;
		final String detail;
		final boolean earlyStop;
		final String earlyStopReason;
		final int stepsCreated;
		final int rulesCreated;
		final List<String> notes;
		final List<String> columnsWithoutProposalOrNote;
		/** Round 3 (coordinator, 2026-09-21): whether a follow-up run was needed AND made the session record something. See {@code ProseAnswerRecovery}. */
		final boolean proseAnswerRecovered;
		/** The model's own last text, bounded - non-null only when {@link #status} is {@code NO_OUTPUT}. */
		final String modelText;

		EntityRunOutcome(final String pRunId, final String pStatus, final int pTurns, final int pToolCalls, final int pInputTokens, final int pOutputTokens,
			final int pRefusedToolCalls, final String pDetail, final boolean pEarlyStop, final String pEarlyStopReason, final int pStepsCreated,
			final int pRulesCreated, final List<String> pNotes, final List<String> pColumnsWithoutProposalOrNote, final boolean pProseAnswerRecovered,
			final String pModelText)
		{
			this.runId = pRunId;
			this.status = pStatus;
			this.turns = pTurns;
			this.toolCalls = pToolCalls;
			this.inputTokens = pInputTokens;
			this.outputTokens = pOutputTokens;
			this.refusedToolCalls = pRefusedToolCalls;
			this.detail = pDetail;
			this.earlyStop = pEarlyStop;
			this.earlyStopReason = pEarlyStopReason;
			this.stepsCreated = pStepsCreated;
			this.rulesCreated = pRulesCreated;
			this.notes = pNotes;
			this.columnsWithoutProposalOrNote = pColumnsWithoutProposalOrNote;
			this.proseAnswerRecovered = pProseAnswerRecovered;
			this.modelText = pModelText;
		}
	}

	/** Lot B: the attribute or any of its source columns matches a sensitive-name pattern of the instance policy. */
	static boolean anySensitive(final LlmDataPolicy pPolicy, final List<QualitySuggester.MappingRow> pMappings)
	{
		for (final QualitySuggester.MappingRow mapping : pMappings)
		{
			if (pPolicy.isSensitive(mapping.attributeName) || pPolicy.isSensitive(mapping.sourceColumn) || pPolicy.isSensitive(mapping.fieldName))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Builds ONE entity's columns/evidence (the exact same per-column reads {@link #suggest} always did, just
	 * scoped to {@code pEntityMappings} instead of the whole project), runs ONE {@link AgentRuntime} call
	 * against them through a FRESH {@link QualityLlmPass.Session} (so the {@code measure} budget and the
	 * refusal circuit breaker are per-entity, matching one agent run each), writes whatever it proposed, and
	 * reports the coverage gap ({@link QualityLlmPass.Session#columnsWithoutProposalOrNote()}).
	 * <p>
	 * <b>Coordinator review round 3, 2026-09-21 (real run, row ids 28/29): {@code Company.country} - fed by
	 * TWO accepted mappings, one from an ERP source, one from a CRM source - was measured and proposed TWICE,
	 * writing the identical {@code QualityRule} row twice.</b> {@code QualityRule} is keyed on the ATTRIBUTE,
	 * not the mapping, but the previous version of this method built one {@code ColumnInfo}/evidence entry PER
	 * MAPPING, both under the SAME {@code entity.attribute} map key - the second mapping's {@code Map.put}
	 * silently overwrote the first's evidence entirely, so BOTH of the model's two (apparently distinct)
	 * "columns" were, underneath, always measured against whichever mapping was inserted LAST (both rows 28/29
	 * showed the identical {@code valuesSeen = 52}, one mapping's own row count, never the other's 55 - the
	 * first mapping's own evidence was silently unreachable the whole run). Mappings are now grouped by
	 * {@code entity.attribute} FIRST: an attribute fed by N mappings is shown to the model ONCE, with every
	 * contributing source column named, and its {@link QualityLlmPass.ColumnEvidence} is the UNION of every
	 * mapping's own distinct values (concatenated) and EXACT aggregates (summed) - {@code exactDistinctCount}
	 * summed across mappings is a documented, deliberate over-count when the same value appears in more than
	 * one source (no cross-source dedup attempted this lot), the same one-directional-trade-off discipline
	 * {@code proposeUnique} already documents elsewhere in this file's sibling class. A {@code QualityRule}
	 * proposed against this union evidence is still written ONCE (one row per attribute, unchanged). A {@code
	 * MappingStep}, by contrast, STAYS PER MAPPING - cleaning is legitimately about one source column at a
	 * time - so {@link #write} now writes one {@code MappingStep} row per mapping of the attribute the model
	 * proposed a step for, all sharing the step's own kind/argument/rationale (measured against the union) but
	 * each attached to its own {@code mapping.mappingId} and its own position in that mapping's own chain.
	 */
	private static EntityRunOutcome runOneEntity(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode,
		final String pProjectLabel, final String pEntityName, final List<QualitySuggester.MappingRow> pEntityMappings, final QualityLlmPass.Mode pMode,
		final int pSampleSize, final CleaningSteps.Lookups pLookups, final List<QualityLlmPass.ReferenceBindingInfo> pReferenceBindings,
		final String pLandingDataspace, final Map<String, QualitySuggester.LandingTableRef> pLandingTableCache, final RuleFocus pFocus) throws OperationException
	{
		final LlmDataPolicy policy = LlmDataPolicy.of(pFactory);
		// group by attribute BEFORE building any ColumnInfo/evidence - see this method's own Javadoc
		final Map<String, List<QualitySuggester.MappingRow>> mappingsByAttribute = new LinkedHashMap<String, List<QualitySuggester.MappingRow>>();
		for (final QualitySuggester.MappingRow mapping : pEntityMappings)
		{
			final String key = mapping.entityName + "." + mapping.attributeName;
			List<QualitySuggester.MappingRow> forAttribute = mappingsByAttribute.get(key);
			if (forAttribute == null)
			{
				forAttribute = new ArrayList<QualitySuggester.MappingRow>();
				mappingsByAttribute.put(key, forAttribute);
			}
			forAttribute.add(mapping);
		}

		final List<QualityLlmPass.ColumnInfo> columns = new ArrayList<QualityLlmPass.ColumnInfo>();
		final Map<String, QualityLlmPass.ColumnEvidence> evidenceByKey = new LinkedHashMap<String, QualityLlmPass.ColumnEvidence>();

		for (final Map.Entry<String, List<QualitySuggester.MappingRow>> attributeEntry : mappingsByAttribute.entrySet())
		{
			final String key = attributeEntry.getKey();
			final List<QualitySuggester.MappingRow> mappings = attributeEntry.getValue();
			final QualitySuggester.MappingRow first = mappings.get(0);
			if (pFocus != null && !pFocus.attributeId.equals(first.attributeId))
			{
				continue; // desk lot L4b: the other columns only serve as grouping candidates (FailureBreakdowns#joints reads the whole map), they are not asked about
			}
			if (pFocus != null)
			{
				pFocus.notAskedReason = RuleFocus.whyNotAsked(pMode, QualityLlmSuggester.anySensitive(policy, mappings));
				if (pFocus.notAskedReason != null)
				{
					return new EntityRunOutcome(null, "SKIPPED", 0, 0, 0, 0, 0, pFocus.notAskedReason, false, null, 0, 0, Collections.<String>emptyList(),
						Collections.<String>emptyList(), false, null);
				}
			}
			if (QualityLlmSuggester.anySensitive(policy, mappings))
			{
				continue; // lot B (2026-09-24): a sensitive column never reaches the model - left to the deterministic rules
			}

			final List<Detectors.ValueCount> unionValues = new ArrayList<Detectors.ValueCount>();
			final List<QualityLlmPass.ExistingProposal> existingSteps = new ArrayList<QualityLlmPass.ExistingProposal>();
			final List<String> sourceLabels = new ArrayList<String>();
			// lot M8b (2026-10-04): the pipelines of this attribute's mappings, so a cleaning step is never proposed in front of one it would break
			final List<String> mappingPipelines = new ArrayList<String>();
			long totalRows = 0;
			long totalFilled = 0;
			long totalDistinct = 0;
			Integer maxLength = null;
			for (final QualitySuggester.MappingRow mapping : mappings)
			{
				sourceLabels.add((mapping.landingTableName == null ? mapping.entityName : mapping.landingTableName) + "." + mapping.sourceColumn);
				existingSteps.addAll(QualityLlmSuggester.existingSteps(pFactory, mapping.mappingId));
				if (mapping.pipeline != null && !mapping.pipeline.trim().isEmpty() && !mappingPipelines.contains(mapping.pipeline.trim()))
				{
					mappingPipelines.add(mapping.pipeline.trim());
				}
				if (mapping.observedMaxLength != null && (maxLength == null || mapping.observedMaxLength.intValue() > maxLength.intValue()))
				{
					maxLength = mapping.observedMaxLength;
				}
				if (pLandingDataspace == null || pLandingDataspace.trim().isEmpty())
				{
					continue;
				}
				final QualitySuggester.LandingTableRef landingRef = QualitySuggester.landingTableRef(pFactory, pLandingDataspace, mapping, pLandingTableCache);
				if (landingRef == null)
				{
					continue;
				}
				final QualitySuggester.ColumnQuery query = QualitySuggester.distinctValues(pSession, landingRef, mapping.fieldName, null,
					QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES);
				// EXACT, never derived from query.values (coordinator review, 2026-09-21) - the sole source of
				// truth for every context number shown to the model AND for the sampled/exact split the pure
				// Session applies; SUMMED across this attribute's mappings (coordinator review round 3)
				final QualitySuggester.ExactAggregates aggregates = QualitySuggester.exactAggregates(pSession, landingRef, mapping.fieldName);
				unionValues.addAll(query.values);
				totalRows += aggregates.rows;
				totalFilled += aggregates.filled;
				totalDistinct += aggregates.distinctCount;
			}
			if (unionValues.isEmpty())
			{
				continue; // no landing zone reachable for ANY of this attribute's mappings yet - nothing to measure
			}
			// a union of several already most-frequent-first lists is not itself globally sorted - re-sort so
			// shapesOf/topValues still show the truly dominant shapes/values first, across every source
			Collections.sort(unionValues, new java.util.Comparator<Detectors.ValueCount>()
			{
				@Override
				public int compare(final Detectors.ValueCount pLeft, final Detectors.ValueCount pRight)
				{
					return Long.compare(pRight.count, pLeft.count);
				}
			});

			final Double fillRatePercent = totalRows > 0 ? Double.valueOf(Math.round(totalFilled * 10000.0 / totalRows) / 100.0) : null;
			// desk lot L4: where a rule fails - the joint counts with the other attributes mapped from the same landing rows
			final List<FailureBreakdown.Joint> joints = FailureBreakdowns.joints(pSession, pFactory, key, mappingsByAttribute, policy, pLandingDataspace, pLandingTableCache);
			final QualityLlmPass.ColumnEvidence evidence = new QualityLlmPass.ColumnEvidence(unionValues, fillRatePercent, totalFilled, totalDistinct, joints);
			evidenceByKey.put(key, evidence);

			final List<QualityLlmPass.ShapeCount> shapes = pMode == QualityLlmPass.Mode.NONE ? Collections.<QualityLlmPass.ShapeCount>emptyList()
				: QualityLlmSuggester.shapesOf(unionValues);
			final List<String> topValues = pMode == QualityLlmPass.Mode.SAMPLE_VALUES ? QualityLlmSuggester.topValues(unionValues, pSampleSize)
				: Collections.<String>emptyList();
			// entity-level: identical for every mapping of this attribute (the same StagingAttribute), read once
			final List<QualityLlmPass.ExistingProposal> existingRules = QualityLlmSuggester.existingRules(pFactory, first.entityId, first.attributeId, evidence, pLookups, pMode);

			QualityLlmSuggester.refreshBreakdowns(pSession, pFactory, first.entityId, first.attributeId, evidence, pLookups, pMode);

			columns.add(new QualityLlmPass.ColumnInfo(first.entityName, first.attributeName, QualityLlmSuggester.join(sourceLabels), first.dataType, maxLength,
				Long.valueOf(totalRows), fillRatePercent, Long.valueOf(totalDistinct), shapes, topValues, existingSteps, existingRules, mappingPipelines,
				mappings.size() == 1));
		}
		if (columns.isEmpty())
		{
			throw EngineRefusal.coded("missing.noLandingForEntity", "entity " + pEntityName + " has no landing zone reachable for its accepted mappings: nothing to measure yet.", pEntityName);
		}

		final QualityLlmPass.ProjectInfo entityInfo = new QualityLlmPass.ProjectInfo(pProjectCode, pProjectLabel, pMode, pSampleSize, columns, pReferenceBindings);
		final QualityLlmPass.Session llmSession = new QualityLlmPass.Session(columns, evidenceByKey, pLookups, pReferenceBindings, pMode, pSampleSize);
		if (pFocus != null)
		{
			llmSession.restrictTo(pFocus);
		}

		final ToolSet tools;
		try
		{
			tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), Collections.singletonMap("factory", llmSession.toolGroup()));
		}
		catch (final McpException ex)
		{
			// unreachable in practice: no MCP server is ever given to this ToolSet, only the local "factory" group
			throw EngineRefusal.codedWithCause("external.agentTools", "Could not build the agent's tool set: " + ex.getMessage(), ex);
		}

		final String systemPrompt = QualityLlmPass.systemPrompt();
		final AgentRuntime.Outcome firstOutcome = AgentRuntime.run(pSession, pFactory, pProjectId, QualityLlmSuggester.PHASE,
			"Propose normalisation/quality rules for entity " + pEntityName + " of project " + pProjectCode + ".", systemPrompt, QualityLlmPass.userPrompt(entityInfo, pFocus),
			tools);

		// round 3 (coordinator, 2026-09-21): a DONE run whose own text correctly described proposals, but never
		// called a tool, recorded nothing - one follow-up on the SAME Session/tools (measure budget, refusal
		// counters carry over), quoting the model's own text and telling it plainly to use the tools
		final ProseAnswerRecovery.RecordedNothing recordedNothing = new ProseAnswerRecovery.RecordedNothing()
		{
			@Override
			public boolean test()
			{
				return llmSession.proposedSteps().isEmpty() && llmSession.proposedRules().isEmpty() && llmSession.notes().isEmpty();
			}
		};
		final ProseAnswerRecovery.FollowUpRunner followUpRunner = new ProseAnswerRecovery.FollowUpRunner()
		{
			@Override
			public AgentRuntime.Outcome run(final String pFollowUpTask) throws OperationException
			{
				return AgentRuntime.run(pSession, pFactory, pProjectId, QualityLlmSuggester.PHASE, "Propose normalisation/quality rules for entity " + pEntityName
					+ " of project " + pProjectCode + " (follow-up: the previous answer was prose, not a tool call).", systemPrompt, pFollowUpTask, tools);
			}
		};
		final ProseAnswerRecovery.Result recovery = ProseAnswerRecovery.recover(firstOutcome, recordedNothing, followUpRunner);
		final AgentRuntime.Outcome outcome = recovery.latest();

		final int[] counts = QualityLlmSuggester.write(pSession, pFactory, mappingsByAttribute, llmSession);
		if (pFocus != null)
		{
			pFocus.asked = "DONE".equals(AgentRuntime.statusOf(outcome.status));
			for (final QualityLlmPass.ProposedRule proposed : llmSession.proposedRules())
			{
				if (proposed.conditionAttribute != null)
				{
					pFocus.conditionAttribute = proposed.conditionAttribute;
					pFocus.conditionValues = proposed.conditionValues;
				}
			}
			pFocus.note = RuleFocus.reasonOf(llmSession.notes());
			if (!pFocus.asked)
			{
				pFocus.notAskedReason = "the model run ended as " + AgentRuntime.statusOf(outcome.status);
			}
		}

		final String status = recovery.noOutput ? "NO_OUTPUT" : AgentRuntime.statusOf(outcome.status);
		return new EntityRunOutcome(outcome.runId, status, recovery.combinedSteps(), recovery.combinedToolCalls(), recovery.combinedInputTokens(),
			recovery.combinedOutputTokens(), recovery.combinedRefusedToolCalls(), outcome.detail, llmSession.stoppedEarly(), llmSession.stopReason(), counts[0],
			counts[1], llmSession.notes(), llmSession.columnsWithoutProposalOrNote(), recovery.proseAnswerRecovered, recovery.finalText);
	}

	private static String join(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (final String value : pValues)
		{
			if (out.length() > 0)
			{
				out.append(", ");
			}
			out.append(value);
		}
		return out.toString();
	}

	// ------------------------------------------------------------------------------------------------------- reading

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

	/** Every {@code ReferenceBinding} row's own shape (kind/codeField/labelFields/scopeField) - never the reference table's own rows: that would need {@link QualitySuggester#buildLookups}, already computed once for the {@code measure} tool, not duplicated here. */
	private static List<QualityLlmPass.ReferenceBindingInfo> readReferenceBindings(final Adaptation pFactory)
	{
		final List<QualityLlmPass.ReferenceBindingInfo> out = new ArrayList<QualityLlmPass.ReferenceBindingInfo>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.REFERENCE_BINDING).createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final List<String> labelFields = new ArrayList<String>();
				final List<?> rawLabelFields = row.getList(FactoryPaths.ReferenceBinding.LABEL_FIELDS);
				if (rawLabelFields != null)
				{
					for (final Object field : rawLabelFields)
					{
						labelFields.add(String.valueOf(field));
					}
				}
				out.add(new QualityLlmPass.ReferenceBindingInfo(row.getString(FactoryPaths.ReferenceBinding.KIND), row.getString(FactoryPaths.ReferenceBinding.CODE_FIELD),
					labelFields, row.getString(FactoryPaths.ReferenceBinding.SCOPE_FIELD)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Every {@code MappingStep} (kind, status, argument) already present for {@code pMappingId} - what {@link QualityLlmPass.Session} refuses a repeat of. */
	private static List<QualityLlmPass.ExistingProposal> existingSteps(final Adaptation pFactory, final String pMappingId)
	{
		final List<QualityLlmPass.ExistingProposal> out = new ArrayList<QualityLlmPass.ExistingProposal>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.MAPPING_STEP).createRequestResult("./mapping=" + CatalogReader.quote(pMappingId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(new QualityLlmPass.ExistingProposal(row.getString(FactoryPaths.MappingStep.KIND), row.getString(FactoryPaths.MappingStep.STATUS),
					row.getString(FactoryPaths.MappingStep.ARGUMENT)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Every {@code QualityRule} (kind, status, argument) already present for {@code (pEntityId, pAttributeId)}.
	 * {@code argument} is read here (coordinator review round 3, 2026-09-21) because identity for "does this
	 * already exist" is {@code (kind, argument)}, not {@code kind} alone - see {@link
	 * QualityLlmPass.ExistingProposal}'s own Javadoc and {@link QualityLlmPass.Session#proposeQualityRule}'s
	 * matching loop.
	 */
	private static List<QualityLlmPass.ExistingProposal> existingRules(final Adaptation pFactory, final String pEntityId, final String pAttributeId,
		final QualityLlmPass.ColumnEvidence pEvidence, final CleaningSteps.Lookups pLookups, final QualityLlmPass.Mode pMode)
	{
		final List<QualityLlmPass.ExistingProposal> out = new ArrayList<QualityLlmPass.ExistingProposal>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.QUALITY_RULE)
			.createRequestResult("./entity=" + CatalogReader.quote(pEntityId) + " and ./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String kind = row.getString(FactoryPaths.QualityRule.KIND);
				final String status = row.getString(FactoryPaths.QualityRule.STATUS);
				final String argument = row.getString(FactoryPaths.QualityRule.ARGUMENT);
				final String condition = row.getString(FactoryPaths.QualityRule.CONDITION_ATTRIBUTE);
				// desk lot L4: an open rule that fails somewhere is shown with where, so the model may add a condition to it
				String breakdownText = null;
				if (!"REJECTED".equals(status) && ("PATTERN".equals(kind) || "REFERENCE".equals(kind) || "CHECK_DIGIT".equals(kind)))
				{
					final FailureBreakdown.Result result = QualityLlmPass.breakdown(kind, argument, pEvidence, pLookups, pMode);
					breakdownText = result == null ? null : FailureBreakdown.promptText(result, pMode == QualityLlmPass.Mode.SAMPLE_VALUES);
				}
				out.add(new QualityLlmPass.ExistingProposal(kind, status, argument, condition != null && !condition.trim().isEmpty(), breakdownText));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Desk lot L4: stores {@code failureBreakdown} on the open rules of an attribute that have none yet (the measured fact the desk shows), the marker when nothing separates the
	 * failures. A rule the person already decided is left alone only if it has a breakdown; evidence is evidence, so a decided one without it gets it too. Never fatal.
	 */
	private static void refreshBreakdowns(final Session pSession, final Adaptation pFactory, final String pEntityId, final String pAttributeId,
		final QualityLlmPass.ColumnEvidence pEvidence, final CleaningSteps.Lookups pLookups, final QualityLlmPass.Mode pMode)
	{
		if (pEvidence.joints.isEmpty())
		{
			return;
		}
		final List<String[]> todo = new ArrayList<String[]>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.QUALITY_RULE)
			.createRequestResult("./entity=" + CatalogReader.quote(pEntityId) + " and ./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String kind = row.getString(FactoryPaths.QualityRule.KIND);
				final String stored = row.getString(FactoryPaths.QualityRule.FAILURE_BREAKDOWN);
				if (!"REJECTED".equals(row.getString(FactoryPaths.QualityRule.STATUS)) && FailureBreakdown.measured(stored) == false
					&& ("PATTERN".equals(kind) || "REFERENCE".equals(kind) || "CHECK_DIGIT".equals(kind)))
				{
					todo.add(new String[] { row.getOccurrencePrimaryKey().format(), kind, row.getString(FactoryPaths.QualityRule.ARGUMENT) });
				}
			}
		}
		finally
		{
			rows.close();
		}
		for (final String[] rule : todo)
		{
			try
			{
				final FailureBreakdown.Result result = QualityLlmPass.breakdown(rule[1], rule[2], pEvidence, pLookups, pMode);
				FailureBreakdowns.store(pSession, pFactory, rule[0], result == null ? FailureBreakdown.NOT_FOUND_JSON : FailureBreakdown.toJson(result));
			}
			catch (final OperationException ex)
			{
				// advisory evidence: the run goes on, the desk offers its Measure button for this rule
			}
		}
	}

	/** Distinct shapes of {@code pValues}, counts summed across every raw value sharing the same shape, most frequent first, capped at {@link #MAX_SHAPES_SHOWN} - never a real value (see {@link QualityLlmPass}'s own {@code SHAPES_ONLY} guarantee: this is what the prompt shows regardless of mode). */
	private static List<QualityLlmPass.ShapeCount> shapesOf(final List<Detectors.ValueCount> pValues)
	{
		final Map<String, Long> byShape = new LinkedHashMap<String, Long>();
		for (final Detectors.ValueCount vc : pValues)
		{
			if (vc.value == null)
			{
				continue;
			}
			final String shape = Shapes.of(vc.value);
			final Long existing = byShape.get(shape);
			byShape.put(shape, Long.valueOf((existing == null ? 0L : existing.longValue()) + vc.count));
		}
		final List<Map.Entry<String, Long>> sorted = new ArrayList<Map.Entry<String, Long>>(byShape.entrySet());
		Collections.sort(sorted, new java.util.Comparator<Map.Entry<String, Long>>()
		{
			@Override
			public int compare(final Map.Entry<String, Long> pLeft, final Map.Entry<String, Long> pRight)
			{
				return pRight.getValue().compareTo(pLeft.getValue());
			}
		});
		final List<QualityLlmPass.ShapeCount> out = new ArrayList<QualityLlmPass.ShapeCount>();
		for (final Map.Entry<String, Long> entry : sorted)
		{
			if (out.size() >= QualityLlmSuggester.MAX_SHAPES_SHOWN)
			{
				break;
			}
			out.add(new QualityLlmPass.ShapeCount(entry.getKey(), entry.getValue().longValue()));
		}
		return out;
	}

	/** The top {@code pSampleSize} most frequent DISTINCT raw values - {@code pValues} is already ordered most-frequent-first ({@code QualitySuggester.buildGroupBySql}'s own {@code ORDER BY N DESC}). Only ever called in {@code SAMPLE_VALUES} mode - see the class Javadoc. */
	private static List<String> topValues(final List<Detectors.ValueCount> pValues, final int pSampleSize)
	{
		final List<String> out = new ArrayList<String>();
		for (final Detectors.ValueCount vc : pValues)
		{
			if (out.size() >= pSampleSize)
			{
				break;
			}
			if (vc.value != null)
			{
				out.add(vc.value);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------- writing

	/**
	 * @return {steps created, rules created}. {@code pMappingsByAttribute} maps {@code entity.attribute} to
	 * EVERY accepted mapping that feeds it (see {@link #runOneEntity}'s own Javadoc): a {@code ProposedRule} is
	 * still written ONCE, against any one of them (they share the same {@code entityId}/{@code attributeId} -
	 * the same {@code StagingAttribute}); a {@code ProposedStep} is written ONCE PER MAPPING, since cleaning is
	 * legitimately about one source column at a time - all sharing the same measured kind/argument/rationale
	 * (from the union evidence) but each attached to its own {@code mapping.mappingId} and its own position in
	 * that mapping's own chain.
	 */
	private static int[] write(final Session pSession, final Adaptation pFactory, final Map<String, List<QualitySuggester.MappingRow>> pMappingsByAttribute,
		final QualityLlmPass.Session pLlmSession) throws OperationException
	{
		final List<QualityLlmPass.ProposedStep> steps = pLlmSession.proposedSteps();
		final List<QualityLlmPass.ProposedRule> rules = pLlmSession.proposedRules();
		final AdaptationTable stepsTable = pFactory.getTable(FactoryPaths.MAPPING_STEP);
		final AdaptationTable rulesTable = pFactory.getTable(FactoryPaths.QUALITY_RULE);
		final int[] counts = new int[2];

		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final QualityLlmPass.ProposedStep step : steps)
				{
					final List<QualitySuggester.MappingRow> mappings = pMappingsByAttribute.get(step.entity + "." + step.attribute);
					if (mappings == null)
					{
						// every proposal came from a column Session was built with, from pMappingsByAttribute's own keys - unreachable defensively
						continue;
					}
					for (final QualitySuggester.MappingRow mapping : mappings)
					{
						final int position = QualityLlmSuggester.nextPosition(stepsTable, mapping.mappingId);
						final ValueContextForUpdate value = pContext.getContextForNewOccurrence(stepsTable);
						value.setValue(mapping.mappingId, FactoryPaths.MappingStep.MAPPING);
						value.setValue(Integer.valueOf(position), FactoryPaths.MappingStep.POSITION);
						value.setValue(step.kind, FactoryPaths.MappingStep.KIND);
						if (step.argument != null)
						{
							value.setValue(step.argument, FactoryPaths.MappingStep.ARGUMENT);
						}
						value.setValue("LLM", FactoryPaths.MappingStep.ORIGIN);
						value.setValue(Integer.valueOf(step.confidence), FactoryPaths.MappingStep.CONFIDENCE);
						value.setValue(TextLimits.truncate(step.rationale, QualityLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.MappingStep.RATIONALE);
						value.setValue("PROPOSED", FactoryPaths.MappingStep.STATUS);
						value.setValue(BigDecimal.valueOf(step.valuesSeen), FactoryPaths.MappingStep.VALUES_SEEN);
						value.setValue(BigDecimal.valueOf(step.valuesChanged), FactoryPaths.MappingStep.VALUES_CHANGED);
						value.setValue(BigDecimal.valueOf(step.valuesUnresolved), FactoryPaths.MappingStep.VALUES_UNRESOLVED);
						value.setValue(QualityLlmSuggester.examplesText(step.examples), FactoryPaths.MappingStep.EXAMPLES);
						value.setValue(new Date(), FactoryPaths.MappingStep.CHECKED_AT);
						pContext.doCreateOccurrence(value, stepsTable);
						counts[0]++;
					}
				}
				for (final QualityLlmPass.ProposedRule rule : rules)
				{
					final List<QualitySuggester.MappingRow> mappings = pMappingsByAttribute.get(rule.entity + "." + rule.attribute);
					if (mappings == null || mappings.isEmpty())
					{
						continue;
					}
					// entityId/attributeId are identical across every mapping of this attribute (the same
					// StagingAttribute) - any one of them names the right row; a QualityRule is written ONCE
					final QualitySuggester.MappingRow mapping = mappings.get(0);
					if (rule.updatesExisting)
					{
						// desk lot L4: the model added a condition to a rule that is already there (PROPOSED): that row gets the condition and the measured fact
						final Adaptation existing = QualityLlmSuggester.findOpenRule(rulesTable, mapping.entityId, mapping.attributeId, rule.kind, rule.argument);
						final String conditionAttributeId = QualityLlmSuggester.attributeIdOf(pMappingsByAttribute, rule.entity, rule.conditionAttribute);
						if (existing != null && conditionAttributeId != null)
						{
							final ValueContextForUpdate update = pContext.getContext(existing.getAdaptationName());
							update.setValue(conditionAttributeId, FactoryPaths.QualityRule.CONDITION_ATTRIBUTE);
							update.setValue(new ArrayList<String>(rule.conditionValues), FactoryPaths.QualityRule.CONDITION_VALUES);
							update.setValue(rule.failureBreakdown, FactoryPaths.QualityRule.FAILURE_BREAKDOWN);
							update.setValue(TextLimits.truncate(rule.rationale, QualityLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.QualityRule.RATIONALE);
							pContext.doModifyContent(existing, update);
							counts[1]++;
							continue;
						}
					}
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(rulesTable);
					value.setValue(mapping.entityId, FactoryPaths.QualityRule.ENTITY);
					value.setValue(mapping.attributeId, FactoryPaths.QualityRule.ATTRIBUTE);
					value.setValue(rule.kind, FactoryPaths.QualityRule.KIND);
					if (rule.argument != null)
					{
						value.setValue(rule.argument, FactoryPaths.QualityRule.ARGUMENT);
					}
					value.setValue(rule.severity, FactoryPaths.QualityRule.SEVERITY);
					value.setValue("LLM", FactoryPaths.QualityRule.ORIGIN);
					value.setValue(Integer.valueOf(rule.confidence), FactoryPaths.QualityRule.CONFIDENCE);
					value.setValue(TextLimits.truncate(rule.rationale, QualityLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.QualityRule.RATIONALE);
					value.setValue("PROPOSED", FactoryPaths.QualityRule.STATUS);
					value.setValue(BigDecimal.valueOf(rule.valuesSeen), FactoryPaths.QualityRule.VALUES_SEEN);
					value.setValue(BigDecimal.valueOf(rule.valuesFailing), FactoryPaths.QualityRule.VALUES_FAILING);
					value.setValue(QualityLlmSuggester.examplesText(rule.examples), FactoryPaths.QualityRule.EXAMPLES);
					value.setValue(new Date(), FactoryPaths.QualityRule.CHECKED_AT);
					value.setValue(rule.failureBreakdown, FactoryPaths.QualityRule.FAILURE_BREAKDOWN);
					if (rule.conditionAttribute != null)
					{
						final String conditionAttributeId = QualityLlmSuggester.attributeIdOf(pMappingsByAttribute, rule.entity, rule.conditionAttribute);
						if (conditionAttributeId != null)
						{
							value.setValue(conditionAttributeId, FactoryPaths.QualityRule.CONDITION_ATTRIBUTE);
							value.setValue(new ArrayList<String>(rule.conditionValues), FactoryPaths.QualityRule.CONDITION_VALUES);
						}
					}
					pContext.doCreateOccurrence(value, rulesTable);
					counts[1]++;
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveQualityProposals", "Writing the language-model quality proposals failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		return counts;
	}

	/** The non-rejected, still-PROPOSED rule of this attribute with the same kind and argument that has no condition yet, or {@code null}. */
	private static Adaptation findOpenRule(final AdaptationTable pRulesTable, final String pEntityId, final String pAttributeId, final String pKind, final String pArgument)
	{
		final RequestResult rows = pRulesTable.createRequestResult("./entity=" + CatalogReader.quote(pEntityId) + " and ./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String condition = row.getString(FactoryPaths.QualityRule.CONDITION_ATTRIBUTE);
				if ("PROPOSED".equals(row.getString(FactoryPaths.QualityRule.STATUS)) && pKind.equals(row.getString(FactoryPaths.QualityRule.KIND))
					&& java.util.Objects.equals(pArgument, row.getString(FactoryPaths.QualityRule.ARGUMENT)) && (condition == null || condition.trim().isEmpty()))
				{
					return row;
				}
			}
		}
		finally
		{
			rows.close();
		}
		return null;
	}

	/** The StagingAttribute id of {@code entity.attribute} among the mappings of the run, or {@code null}. */
	private static String attributeIdOf(final Map<String, List<QualitySuggester.MappingRow>> pMappingsByAttribute, final String pEntity, final String pAttribute)
	{
		final List<QualitySuggester.MappingRow> mappings = pMappingsByAttribute.get(pEntity + "." + pAttribute);
		return mappings == null || mappings.isEmpty() ? null : mappings.get(0).attributeId;
	}

	private static int nextPosition(final AdaptationTable pStepsTable, final String pMappingId)
	{
		int position = 0;
		final RequestResult rows = pStepsTable.createRequestResult("./mapping=" + CatalogReader.quote(pMappingId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object rawPosition = row.get(FactoryPaths.MappingStep.POSITION);
				final int existing = rawPosition instanceof Number ? ((Number) rawPosition).intValue() : 0;
				position = Math.max(position, existing);
			}
		}
		finally
		{
			rows.close();
		}
		return position + 1;
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
		return TextLimits.truncate(out.toString(), QualityLlmSuggester.EXAMPLES_MAX_LENGTH);
	}
}
