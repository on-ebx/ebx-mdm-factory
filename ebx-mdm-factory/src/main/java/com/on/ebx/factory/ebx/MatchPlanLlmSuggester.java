package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.AgentLoop;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.master.ChildEntityMatching;
import com.on.ebx.factory.master.DecisionTreePlan;
import com.on.ebx.factory.master.MameDataModelResolution;
import com.on.ebx.factory.master.MasterFieldNameRule;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.master.MatchPlanLlmPass;
import com.on.ebx.factory.master.MatchPlanProfileReader;
import com.on.ebx.factory.master.StoredJsonFit;
import com.on.ebx.factory.master.TrustedSourceRanking;
import com.on.ebx.factory.quality.ChildKeysDerivation;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.QualityRuleCheck;
import com.on.ebx.factory.quality.ValidityAggregator;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Runs {@link MatchPlanLlmPass} - the language-model matching pass (`docs/MATCHING.md`, 2026-09-21 rework,
 * revised same day after coordinator review; reversed 2026-09-22, "child entities matched too") - over a
 * project's PROMOTED staging entities, child entities included. Route {@code POST /projects/{code}/matching/suggest-llm}.
 * <p>
 * <b>Coordinator review, same day: this class no longer profiles data itself.</b> An earlier revision
 * scanned the project's STAGING table directly (a bounded, capped Java loop) - removed entirely, for three
 * reasons the coordinator gave: (1) matching runs on the MASTER table, not staging - profiling the wrong
 * zone; (2) a hard-coded scan cap makes a genuinely unique identifier on a large table read as barely
 * distinct (volume is never marginal in this project - see `PLAN.md`); (3) it duplicated a brick the
 * `match-merge` MCP server already maintains. This class now calls that server's own
 * {@code profile_table_for_matching} (numbers, tagged with how reliably each was measured),
 * {@code matching_guide} (the editor's own algorithm-selection/threshold-tuning/merge-strategy text, fed
 * to the model instead of this codebase inventing its own recipe) and {@code matching_config_schema} (the
 * allowed algorithm/null-handling/merge-function vocabulary, and per-field pre-clustering capability) -
 * exactly the same {@code McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE)}/{@code McpClient.callTool}
 * pattern {@code MatchingConfigurator} already uses for its own MCP calls.
 * <p>
 * <b>{@code matching_config_schema} is called TWICE, for two different reasons, after the coordinator
 * measured its real shape live (2026-09-21, second revision - the first revision's guessed flat
 * {@code algorithms}/{@code nullHandlings} keys never existed; the real enums sit deep inside a JSON Schema
 * of {@code MameConfig}, at the exact dotted paths {@code master.MatchPlanProfileReader} now reads - see
 * its own Javadoc).</b> Once, WITHOUT a {@code table} argument, for the allowed algorithm/null-handling/
 * record-selection vocabulary - measured to always work, so a failure here aborts the whole route (the
 * guards depend on it; no {@code table} means no {@code dataModel} either, since there is nothing to
 * disambiguate). Once PER ENTITY, WITH {@code table} - {@code dataModel} sent UNCONDITIONALLY via {@code
 * ebx.MameCalls#call} since 2026-09-23 (`docs/MATCHING.md` &sect;28), never a preliminary probe read any more
 * (an earlier revision resolved it from a first, advisory {@code get_matching_config} call the same way {@code
 * ebx.MatchingConfigurator} used to - the exact "resolve once from a read" discipline a live measurement showed
 * unsound) - for {@code fieldCapabilities} only - measured to soft-miss ({@code TABLE_NOT_FOUND}) on this
 * instance today (the master table is not registered with the add-on yet), so a failure here NEVER aborts
 * the route or skips the entity: the pre-clustering guard is simply not enforced for that entity, and the
 * run's own {@code notes} say so. Genuinely unverified either way: {@code fieldCapabilities} has never been
 * observed to return real data - listed under "not verified" in `docs/MATCHING.md`.
 * <p>
 * <b>Never a real value, unless the project opted in - and the project's OWN decision, never a caller's.</b>
 * {@code profile_table_for_matching}'s own {@code mostFrequentValue}/{@code mostFrequentToken} are real data
 * values - {@link #toLlmEntity} only ever forwards them into {@link MatchPlanLlmPass.AttributeInfo} when
 * value-sharing is on; the COUNTS ({@code mostFrequentValueCount}/{@code mostFrequentTokenDocFrequency}) are
 * numbers, always forwarded regardless. Whether it is on is read straight off {@code
 * Project.llmValueSharing} - {@code "SAMPLE_VALUES".equals(...)}, defaulting to {@code false} ({@code
 * SHAPES_ONLY}) when null/absent - exactly the read {@code QualityLlmSuggester} already does for its own
 * quality pass (coordinator review, 2026-09-21, after {@code feature/quality-llm} merged into {@code main}
 * and added that field): a URL query parameter is never allowed to override this, since it is the project
 * owner's own data-sharing decision, not a per-call choice.
 * <p>
 * <b>"Promoted"</b> is computed exactly the way {@code MatchPlanSuggester}/{@code ebx.MasterGenerator}
 * compute it - {@code master.MasterModel.buildFromStaging(...).builtEntities} (already parent-before-child,
 * {@code MasterModel}'s own 2026-09-22 ordering guarantee). <b>2026-09-22, reversed (`docs/MATCHING.md`):
 * an entity carrying an accepted attribute that RESOLVES (via {@link MasterModel.Result#resolveReference}) to
 * a DIFFERENT promoted entity - a "child" - is now proposed exactly like any other entity, never excluded.</b>
 * {@link #childLink} (renamed from the old {@code childOf}, which returned only the parent's name and whose
 * result used to gate eligibility) is kept purely for the informational annotation {@link #toLlmEntity}
 * attaches to each child entity's own {@code EntityInfo} and for the response's own {@code childEntities} note
 * list - never to exclude an entity from {@code eligible} any more. <b>Coordinator review, 2026-09-21, real
 * defect found on this project's first live run (still relevant to how the resolution itself works):</b> this
 * method used to check only whether the raw {@code referenceEntity} was ITSELF directly in {@code
 * builtEntities} - a reference to a MEMBER entity (this project's own {@code Contact.accountId -> Account},
 * where {@code Account} is a member of the canonical {@code Company}, never independently promoted) resolved
 * to nothing, so {@code Contact} was wrongly read as NOT a child. Fixed by resolving through {@link
 * MasterModel.Result#resolveReference} instead, the exact rule {@link MasterModel} itself uses to decide
 * whether to lower a real foreign key.
 * <p>
 * <b>Never touches a decided {@code MatchPlan}, and never a {@code USER}-origin one</b> - the same
 * idempotence rule {@code MatchPlanSuggester} follows for its own {@code RULE}-origin plans, checked again
 * here defensively at write time even though {@link MatchPlanLlmPass.Session} already refuses the tool call
 * for exactly this case.
 * <p>
 * <b>A real {@code PipelineStep} is recorded for this route</b> (coordinator review, 2026-09-21, same
 * merge): earlier revisions of this class recorded none at all - no {@code PipelineStep.step} enum value
 * fit a language-model MATCHING suggestion, and adding one meant editing {@code tools/build_xsd.py}/the
 * generated {@code MdmFactory.xsd}, both off limits before {@code feature/quality-llm} (which needed the
 * exact same thing for {@code QUALITY_SUGGEST_LLM}) merged into {@code main} first. {@code
 * MATCHING_SUGGEST_LLM} now exists next to {@code MATCHING_CONFIG}/{@code MATCHING_RUN}, and {@link
 * #suggest} is wrapped in {@code PipelineSteps.begin}/{@code PipelineSteps.end} exactly the way {@code
 * QualityLlmSuggester.suggest} already does for its own step - empty {@code ""} scope (never {@code null}:
 * {@code PipelineSteps.isDone}'s own "empty-scope trap" reads an empty string as "the whole project", but
 * an actual EBX {@code null} would never match it - see {@code PipelineSteps.scopeEquals}'s own Javadoc).
 */
public final class MatchPlanLlmSuggester
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	// job-engine lot C3 (2026-09-24): package-visible (not private) so MatchingSuggestLlmRun can look up the
	// PipelineStep row this class already opens/closes for its own STEP.
	static final String STEP = "MATCHING_SUGGEST_LLM";
	/** Lot M5: the longest last refusal kept per entity in the step's detail. */
	static final int LAST_REFUSAL_MAX_LENGTH = 1500;
	private static final String PHASE = "MATCHING_SUGGEST_LLM";
	// requirements / previousRequirements / evidence limits: master.StoredJsonFit - a plan that does not fit is REFUSED in write(), never cut
	private static final int RATIONALE_MAX_LENGTH = 2000;

	/** {@code matching_guide} topics (`docs/MATCHING.md` &sect;2.1, CLAUDE.md &sect;8) - read once per run, not per entity: static editor content. */
	private static final String[] GUIDE_TOPICS = { "algorithm-selection", "threshold-tuning", "merge-strategy" };
	/** The only record-selection functions {@code master.MatchingPolicyTranslator} actually translates to a real i18n key - a downstream-compatibility restriction, not a guess of this class' own (see its own {@code RECORD_SELECTION_FUNCTIONS}). A schema-provided list is intersected with this before being handed to the model, so the model is never offered a choice the translator would silently mistranslate. */
	private static final List<String> TRANSLATABLE_RECORD_SELECTIONS = Arrays.asList("mostCompleted", "lastUpdate", "mostTrustedSource");
	/** Same literal {@link MatchPlanLlmPass} itself hardcodes (package-private there) - duplicated here, deliberately, so the cross-check against the live vocabulary does not depend on reaching into that class' internals. */
	private static final String EXACT_ALGORITHM_LITERAL = "ebx-addon-mame@exact";
	/** Technical fields {@code profile_table_for_matching} reports on a master table alongside real business attributes - never shown to the model even if somehow also "accepted" (they never are: {@code readAttributes} only ever reads business attributes from {@code StagingAttribute}). Defensive belt-and-braces, not load-bearing. */
	private static final Set<String> TECHNICAL_FIELDS = new LinkedHashSet<String>(Arrays.asList("id", "sourceSystem", "sourceId", "_loadId", "_receivedAt", "_status"));
	/**
	 * Survivorship lot (2026-09-22, `docs/MATCHING.md` &sect;15): caps how many BUSINESS/REFERENCE attributes of
	 * ONE entity ever get an eager {@code CrossSystemMeasures.perSystemFilledCounts} call in {@link #toLlmEntity}
	 * - "cap the SQL work like the existing measures do" (task brief). INFERRED, not read from a product document:
	 * a per-field {@code GROUP BY} is linear in row count (never a self-join), so this bound only ever matters for
	 * an entity with an unusually large number of accepted business attributes; re-tune if a real run shows it
	 * too low or too high. Never gates {@link #MAX_SURVIVORSHIP_MEASURED_ATTRIBUTES}-and-beyond attributes from
	 * the prompt entirely - they are still listed, just without a per-system fill rate line.
	 */
	private static final int MAX_SURVIVORSHIP_MEASURED_ATTRIBUTES = 30;

	private MatchPlanLlmSuggester()
	{
	}

	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		return MatchPlanLlmSuggester.suggest(pSession, pFactory, pProjectCode, null);
	}

	/**
	 * Reject/repropose lot (2026-09-22): the same route, scoped to ONE entity when {@code pEntity} is
	 * non-blank - {@code POST /projects/{code}/matching/suggest-llm?entity=Company}, and the "Propose
	 * another" button on a single {@code MatchPlan} line ({@code ebx.ProposalReviews.startMatchRepropose}
	 * marks the row {@code SUPERSEDED} first, then calls this). {@code pEntity} is null/blank for the
	 * ordinary sweep, whose own behaviour is UNCHANGED (every eligible entity, and a {@code SUPERSEDED} row
	 * is refreshed exactly like a {@code PROPOSED} one - see {@code MatchPlanLlmPass.isRewritable} - so the
	 * demo's own accepted plan is never touched by accident, same guarantee as before this lot).
	 */
	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntity) throws OperationException
	{
		return MatchPlanLlmSuggester.suggest(pSession, pFactory, pProjectCode, pEntity, null);
	}

	/**
	 * Same, with an explicit locale for the run's own summary message - job-engine lot C3 (2026-09-24): {@link
	 * com.on.ebx.factory.ebx.MatchingSuggestLlmRun} calls this from a background job running with the SYSTEM
	 * session, whose own {@code getLocale()} would no longer be the launcher's; it passes the launcher's locale
	 * (captured at enqueue time) here instead - same pattern as {@link AnomalyLlmSuggester}'s own locale
	 * overload. Null falls back to {@code pSession}'s own locale, same as before.
	 */
	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntity, final Locale pLocale)
		throws OperationException
	{
		// audit F-15: the pass writes the project's DESIGN proposals; the queued job already holds that resource, a REST call or a wizard action does not - see EngineClaim
		final EngineClaim claim = EngineClaim.enter(pFactory, pSession, pProjectCode, "MATCHING_SUGGEST_LLM_DIRECT", FactoryJobs.DESIGN);
		try
		{
			return MatchPlanLlmSuggester.suggestClaimed(pSession, pFactory, pProjectCode, pEntity, pLocale);
		}
		finally
		{
			claim.release();
		}
	}

	private static ObjectNode suggestClaimed(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntity, final Locale pLocale)
		throws OperationException
	{
		final Adaptation project = MatchPlanLlmSuggester.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		// delivery lot 1: a project delivered here by EBX Staging is designed elsewhere - no suggestion on a target
		ProjectOrigin.refuseIfDelivered(pFactory, project);
		final String projectId = project.getOccurrencePrimaryKey().format();
		// the project's own decision, never a caller-supplied override - same field, same read, as ebx.QualityLlmSuggester's own mode
		// lot B (2026-09-24): capped by the instance's LlmDataPolicy
		final boolean shareValues = LlmDataPolicy.of(pFactory).levelFor(project) == LlmDataPolicy.Level.SAMPLE_VALUES;
		// 2026-09-25, "matching-plan-child-and-identity" lot: shapes are a coarser signal than a real value - gated
		// at SHAPES_ONLY, not SAMPLE_VALUES (the same threshold ebx.StagingLlmSuggester's own shareShapes already
		// uses for the staging pass, docs/MATCHING.md sec 14).
		final boolean shareShapes = LlmDataPolicy.of(pFactory).levelFor(project) != LlmDataPolicy.Level.NONE;
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (MatchPlanLlmSuggester.blank(masterDataspaceKey) || MatchPlanLlmSuggester.blank(masterDatasetName))
		{
			throw EngineRefusal.coded("missing.noMasterZone", "Project " + pProjectCode + " has no master zone yet: run POST /projects/" + pProjectCode
				+ "/master/generate then POST /projects/" + pProjectCode + "/master/load first - this pass profiles the MASTER table, not staging.", pProjectCode);
		}
		// opened once, read-only, only for the cross-system-sharing SQL measurement (toLlmEntity) - profiling itself
		// still goes through the match-merge MCP server, never this Adaptation directly (see class Javadoc)
		final Adaptation masterDataset = MatchPlanLlmSuggester.openMasterDataset(pFactory.getHome().getRepository(), masterDataspaceKey, masterDatasetName);
		// Lot 1 (2026-09-23, quality-informed survivorship, docs/MATCHING.md sec 23): the SAME reference lookups
		// ebx.StagingLoader/ebx.QualitySuggester already build once per run, needed here so a REFERENCE-kind
		// validity rule's own CrossSystemMeasures#perSystemValidityCounts call can resolve a value exactly like a
		// real transfer would - built once for the whole suggest() call, never per entity/attribute. Notes are
		// discarded (this route already has several other note lists surfaced to the caller; a reference-table
		// warning here is no more actionable to a matching-plan reviewer than the fill/validity numbers it feeds).
		final CleaningSteps.Lookups lookups = QualitySuggester.buildLookups(pFactory, new ArrayList<String>());

		MemberEntitiesBackfill.run(pSession, pFactory, projectId);

		final Map<String, String> entityIdByName = new HashMap<String, String>();
		final List<StagingModel.EntityInfo> stagingEntities = MatchPlanLlmSuggester.readEntities(pFactory, projectId, entityIdByName);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(stagingEntities);
		final Map<String, StagingModel.EntityInfo> stagingByName = new HashMap<String, StagingModel.EntityInfo>();
		for (final StagingModel.EntityInfo entity : stagingEntities)
		{
			stagingByName.put(entity.name, entity);
		}

		// 2026-09-22, "child entities matched too" (docs/MATCHING.md): every promoted entity is eligible now,
		// child or not - masterModel.builtEntities is already parent-before-child (MasterModel's own new ordering
		// guarantee, MasterModel.java). childLinkByName is purely informational from here on: it feeds each
		// EntityInfo's own childOfParent/childOfAttribute annotation (userPrompt) and the response's own
		// "childEntities" note list - it is never used to exclude an entity any more.
		final List<String> promoted = new ArrayList<String>();
		final Map<String, MatchPlanLlmSuggester.ChildLink> childLinkByName = new LinkedHashMap<String, MatchPlanLlmSuggester.ChildLink>();
		final List<String> childNotes = new ArrayList<String>();
		for (final String entityName : masterModel.builtEntities)
		{
			final StagingModel.EntityInfo stagingEntity = stagingByName.get(entityName);
			if (stagingEntity == null)
			{
				continue;
			}
			final MatchPlanLlmSuggester.ChildLink childLink = MatchPlanLlmSuggester.childLink(stagingEntity, masterModel);
			if (childLink != null)
			{
				childLinkByName.put(entityName, childLink);
				childNotes.add(entityName + " (child of " + childLink.parent + "): parent reference offered as supporting field.");
			}
			promoted.add(entityName);
		}
		// reject/repropose lot (2026-09-22): scope to one entity when asked. Simplified 2026-09-22: every promoted
		// entity (child or not) is now eligible, so the only possible refusal is "not a promoted entity of this
		// project" - never a separate "it's a child" refusal any more. Pure decision
		// (MatchPlanLlmSuggesterFilterTest), this method only applies it and turns a refusal into the
		// OperationException the route answers with.
		// lot M2 (2026-10-17, docs/MATCHING.md sect;56): a child entity follows its parent - the sweep proposes no plan for it. A request naming the child itself
		// (pEntity, "Propose another", POST matching/suggest-llm?entity=) is an explicit ask and keeps it in; its plan can then only hold SUSPECTED leaves (checked at accept and configure).
		final Map<String, List<String>> parentsByChild = ChildEntityMatching.parentsByChild(masterModel);
		final List<String> followsParent = new ArrayList<String>();
		if (MatchPlanLlmSuggester.blank(pEntity))
		{
			for (final Iterator<String> it = promoted.iterator(); it.hasNext();)
			{
				final String name = it.next();
				final List<String> parents = parentsByChild.get(name);
				if (parents != null)
				{
					followsParent.add(ChildEntityMatching.followsNote(name, parents));
					it.remove();
				}
			}
		}
		final MatchPlanLlmSuggester.EntityFilterResult filterResult = MatchPlanLlmSuggester.filterToEntity(promoted, pEntity);
		if (filterResult.refusalReason != null)
		{
			throw EngineRefusal.coded("conflict.refused", filterResult.refusalReason.replace("{project}", pProjectCode));
		}
		promoted.clear();
		promoted.addAll(filterResult.entities);
		if (promoted.isEmpty())
		{
			throw EngineRefusal.coded("missing.noPromotedEntityMatch", "Project " + pProjectCode
				+ " has no promoted entity to propose a matching plan for: run master/generate (or check matching/suggest's own skip reasons) first.", pProjectCode);
		}

		final McpClient client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);

		final MatchPlanLlmPass.GuideTexts guideTexts;
		try
		{
			guideTexts = MatchPlanLlmSuggester.readGuideTexts(client);
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.agentTools", "matching_guide call failed: " + ex.getMessage(), ex);
		}

		// the vocabulary is global (not per-table): read it ONCE, without a table argument - measured live,
		// 2026-09-21, to always work, unlike the table-scoped call below (see readFieldCapabilities' own Javadoc)
		MatchPlanLlmPass.AllowedVocabulary vocabulary;
		try
		{
			final ObjectNode vocabularyArgs = MatchPlanLlmSuggester.MAPPER.createObjectNode();
			final McpClient.ToolResult vocabularyResult = client.callTool("matching_config_schema", vocabularyArgs);
			if (vocabularyResult.error || vocabularyResult.json == null)
			{
				throw EngineRefusal.coded("external.matchingSchema", "matching_config_schema call failed: " + MatchPlanProfileReader.shortText(vocabularyResult.text));
			}
			if (!vocabularyResult.json.path("success").asBoolean(false))
			{
				throw EngineRefusal.coded("external.matchingSchema", "matching_config_schema answered " + MatchPlanProfileReader.describeSoftMiss(vocabularyResult.json));
			}
			final MatchPlanProfileReader.VocabularyResult parsed = MatchPlanProfileReader.readVocabulary(MatchPlanProfileReader.resolveSchemaData(vocabularyResult.json));
			if (parsed.vocabulary == null)
			{
				throw EngineRefusal.coded("external.matchingSchema", "matching_config_schema: " + parsed.error);
			}
			vocabulary = parsed.vocabulary;
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.matchingSchema", "matching_config_schema MCP call failed: " + ex.getMessage(), ex);
		}
		if (!vocabulary.algorithms.contains(MatchPlanLlmSuggester.EXACT_ALGORITHM_LITERAL))
		{
			throw EngineRefusal.coded("conflict.matchingExactMissing", "matching_config_schema's own allowed-algorithm list does not include '" + MatchPlanLlmSuggester.EXACT_ALGORITHM_LITERAL
				+ "', which the MATCH-terminal guard depends on by name: refusing to run rather than silently disable that guard. Reported algorithms: "
				+ MatchPlanLlmSuggester.join(vocabulary.algorithms));
		}
		final List<String> translatableSelections = new ArrayList<String>();
		for (final String selection : vocabulary.recordSelections)
		{
			if (MatchPlanLlmSuggester.TRANSLATABLE_RECORD_SELECTIONS.contains(selection))
			{
				translatableSelections.add(selection);
			}
		}
		vocabulary = vocabulary.withRecordSelections(translatableSelections.isEmpty() ? Collections.singletonList("mostCompleted") : translatableSelections);

		final String modelName = MasterNames.modelName(pProjectCode);
		final Map<String, MatchPlanLlmPass.EntityInfo> eligible = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		final List<String> profilingSkipped = new ArrayList<String>();
		final List<String> capabilityNotes = new ArrayList<String>();
		// relation-evidence-required lot R3 (2026-09-26, docs/MATCHING.md sec 34): one report per entity that had at
		// least one candidate child - see buildRelationChildren's own Javadoc for why this exists (buildRelationChildren
		// used to have five silent `continue`s with no trace at all of what was offered or why a child was left out).
		final List<MatchPlanLlmSuggester.RelationEvidenceReport> relationEvidenceReports = new ArrayList<MatchPlanLlmSuggester.RelationEvidenceReport>();
		for (final String entityName : promoted)
		{
			final String tablePath = MasterModel.tablePath(entityName);
			final JsonNode profileData;
			try
			{
				final ObjectNode profileArgs = MatchPlanLlmSuggester.MAPPER.createObjectNode();
				profileArgs.put("table", tablePath);
				profileArgs.put("dataspace", masterDataspaceKey);
				profileArgs.put("dataset", masterDatasetName);
				final McpClient.ToolResult profileResult = client.callTool("profile_table_for_matching", profileArgs);
				if (profileResult.error || profileResult.json == null)
				{
					profilingSkipped.add(entityName + ": profile_table_for_matching call failed: " + MatchPlanProfileReader.shortText(profileResult.text));
					continue;
				}
				if (!profileResult.json.path("success").asBoolean(false))
				{
					profilingSkipped.add(entityName + ": profile_table_for_matching answered " + MatchPlanProfileReader.describeSoftMiss(profileResult.json));
					continue;
				}
				profileData = profileResult.json.path("data");
			}
			catch (final McpException ex)
			{
				profilingSkipped.add(entityName + ": profile_table_for_matching MCP call failed: " + ex.getMessage());
				continue;
			}

			// fieldCapabilities is table-scoped and, measured live 2026-09-21, soft-misses on this instance
			// (the master table is not registered with the add-on yet) - never lets a soft miss here skip the
			// entity or the whole route: the pre-clustering guard is simply not enforced, and it is said so.
			// docs/MATCHING.md sec 28 (2026-09-23 14:20): dataModel is now sent UNCONDITIONALLY via MameCalls.call,
			// from modelName - no more preliminary, advisory-only get_matching_config probe to decide whether it
			// is even needed (that "resolve once from a read" discipline is exactly what a live measurement showed
			// unsound). Purely advisory here either way: any failure (transport, or an AMBIGUOUS_TABLE MameCalls
			// itself could not settle) just leaves the guard "not enforced" for this entity, same as any other
			// soft miss on this path.
			Map<String, Boolean> preClusteringByField = Collections.emptyMap();
			try
			{
				final ObjectNode capabilityArgs = MatchPlanLlmSuggester.MAPPER.createObjectNode();
				capabilityArgs.put("table", tablePath);
				final McpClient.ToolResult capabilityResult = MameCalls.call(client, "matching_config_schema", capabilityArgs, modelName);
				if (!capabilityResult.error && capabilityResult.json != null && capabilityResult.json.path("success").asBoolean(false))
				{
					preClusteringByField = MatchPlanProfileReader.readFieldCapabilities(MatchPlanProfileReader.resolveSchemaData(capabilityResult.json));
					if (preClusteringByField.isEmpty())
					{
						capabilityNotes.add(entityName + ": matching_config_schema(table=...) returned no fieldCapabilities - the pre-clustering guard is not enforced for it.");
					}
				}
				else
				{
					final String detail = capabilityResult.json != null ? MatchPlanProfileReader.describeSoftMiss(capabilityResult.json)
						: MatchPlanProfileReader.shortText(capabilityResult.text);
					capabilityNotes.add(entityName + ": matching_config_schema(table=" + tablePath + ") answered " + detail
						+ " - the pre-clustering guard is not enforced for it.");
				}
			}
			catch (final McpException ex)
			{
				capabilityNotes.add(entityName + ": matching_config_schema(table=" + tablePath + ") MCP call failed: " + ex.getMessage()
					+ " - the pre-clustering guard is not enforced for it.");
			}
			catch (final OperationException ex)
			{
				capabilityNotes.add(entityName + ": matching_config_schema(table=" + tablePath + ") could not resolve a data model: " + ex.getMessage()
					+ " - the pre-clustering guard is not enforced for it.");
			}

			final MatchPlanLlmSuggester.RelationChildrenResult relationResult = MatchPlanLlmSuggester.buildRelationChildren(pFactory, pSession, client, modelName,
				masterDataspaceKey, masterDatasetName, masterDataset, masterModel, stagingByName, entityName, shareValues);
			if (!relationResult.report.offered.isEmpty() || !relationResult.report.skipped.isEmpty())
			{
				relationEvidenceReports.add(relationResult.report);
				LoggingCategory.getKernel().info("[mdm-factory] " + MatchPlanLlmSuggester.relationEvidenceLogLine(relationResult.report));
			}
			eligible.put(entityName, MatchPlanLlmSuggester.toLlmEntity(pFactory, entityIdByName.get(entityName), stagingByName.get(entityName), profileData,
				preClusteringByField, shareValues, shareShapes, childLinkByName.get(entityName), pSession, masterDataset, lookups, relationResult.children));
		}

		if (eligible.isEmpty())
		{
			throw EngineRefusal.coded("unexpected.profilingFailed", "Profiling failed for every eligible entity of project " + pProjectCode + ": " + MatchPlanLlmSuggester.join(profilingSkipped), pProjectCode);
		}

		// lot M3 (2026-10-17, docs/MATCHING.md sec 58): the measured facts about each entity's exact keys - per source system fill, shared values, how often two records sharing
		// one key also share another, and which records fill none of them. ONE bounded read-only SQL statement per entity (ebx.KeyFactsReader); null = not measured, and
		// the pass then behaves as it did before the lot.
		if (masterDataset != null)
		{
			for (final MatchPlanLlmPass.EntityInfo entity : eligible.values())
			{
				entity.keyFacts = KeyFactsReader.read(pSession, masterDataset, entity);
			}
		}

		final List<MatchPlanLlmPass.ExistingPlan> priorPlans = new ArrayList<MatchPlanLlmPass.ExistingPlan>();
		final Map<String, String> planEntityIdByName = new HashMap<String, String>();
		for (final String entityName : eligible.keySet())
		{
			final String entityId = entityIdByName.get(entityName);
			planEntityIdByName.put(entityName, entityId);
			final Adaptation existing = MatchPlanLlmSuggester.findMatchPlan(pFactory, entityId);
			if (existing != null)
			{
				// reject/repropose lot (2026-09-22): requirements/reviewComment travel along too, so
				// MatchPlanLlmPass.userPrompt can tell the model about a REJECTED/SUPERSEDED plan with a comment -
				// see MatchPlanLlmPass.rejectionNote. Harmless for every other status: rejectionNote ignores them.
				priorPlans.add(new MatchPlanLlmPass.ExistingPlan(entityName, existing.getString(FactoryPaths.MatchPlan.STATUS), existing.getString(FactoryPaths.MatchPlan.ORIGIN),
					existing.getString(FactoryPaths.MatchPlan.REQUIREMENTS), existing.getString(FactoryPaths.MatchPlan.REVIEW_COMMENT)));
			}
		}

		// Survivorship lot (2026-09-22, docs/MATCHING.md sec 15): the project's own trusted-source ranking, read
		// once, shown to the model so it can propose a sensible per-field trustedSourceList override and reason
		// about which source a plain "most trusted source" chain entry resolves to - same read
		// ebx.MatchingConfigurator#configureOne uses to build the set_trusted_sources call, never a second query.
		final List<TrustedSourceRanking.Source> orderedTrustedSources = TrustedSourceRanking.order(MatchingConfigurator.readTrustedSources(pFactory, projectId));
		final List<MatchPlanLlmPass.TrustedSourceInfo> trustedSourceInfos = new ArrayList<MatchPlanLlmPass.TrustedSourceInfo>();
		for (int i = 0; i < orderedTrustedSources.size(); i++)
		{
			final TrustedSourceRanking.Source source = orderedTrustedSources.get(i);
			trustedSourceInfos.add(new MatchPlanLlmPass.TrustedSourceInfo(source.code, i + 1, source.description));
		}
		final MatchPlanLlmPass.ProjectInfo projectInfo = new MatchPlanLlmPass.ProjectInfo(pProjectCode, project.getString(FactoryPaths.Project.LABEL),
			new ArrayList<MatchPlanLlmPass.EntityInfo>(eligible.values()), childNotes, guideTexts, shareValues, priorPlans, trustedSourceInfos);
		projectInfo.vocabularyNote = vocabulary.promptNote();
		// the two live-SQL seams Session needs (CrossSystemUsageLookup/PerSystemFillLookup's own Javadoc) - close over
		// pSession/masterDataset, called lazily by Session.proposeMatchPlan, never up front (coordinator review,
		// 2026-09-21, blocking change: measuring this for every candidate attribute does not scale to this project's
		// real table sizes). Both statements live in ebx.CrossSystemMeasures, shared with ebx.MatchPlanSuggester's
		// own deterministic route (coordinator review, 2026-09-21, second pass, "do not create a third private copy
		// of the SQL") - this class no longer keeps its own copy of either.
		final MatchPlanLlmPass.CrossSystemUsageLookup crossSystemLookup = masterDataset == null ? null : new MatchPlanLlmPass.CrossSystemUsageLookup()
		{
			@Override
			public Integer sharedCount(final String pEntityName, final String pFieldName)
			{
				return CrossSystemMeasures.sharedCount(pSession, masterDataset, pEntityName, pFieldName);
			}
		};
		final MatchPlanLlmPass.PerSystemFillLookup perSystemFillLookup = masterDataset == null ? null : new MatchPlanLlmPass.PerSystemFillLookup()
		{
			@Override
			public Map<String, Long> filledCountBySystem(final String pEntityName, final String pFieldName)
			{
				return CrossSystemMeasures.perSystemFilledCounts(pSession, masterDataset, pEntityName, pFieldName);
			}
		};
		// 2026-09-22, "child entities matched too": the within-system mirror of the two lookups above.
		final MatchPlanLlmPass.WithinSystemDuplicateLookup withinSystemDuplicateLookup = masterDataset == null ? null
			: new MatchPlanLlmPass.WithinSystemDuplicateLookup()
			{
				@Override
				public Integer duplicateCount(final String pEntityName, final String pFieldName)
				{
					return CrossSystemMeasures.withinSystemDuplicateCount(pSession, masterDataset, pEntityName, pFieldName);
				}
			};
		// childEntities handed to Session is now purely informational (parent name only, no attribute) - see
		// MatchPlanLlmPass.Session#childEntities' own updated Javadoc.
		final Map<String, String> childParentByName = new LinkedHashMap<String, String>();
		for (final Map.Entry<String, MatchPlanLlmSuggester.ChildLink> entry : childLinkByName.entrySet())
		{
			childParentByName.put(entry.getKey(), entry.getValue().parent);
		}
		// trustedSourceInfos reused verbatim (run-30 fix, 2026-09-22, docs/MATCHING.md sec 15): the same list built
		// above for ProjectInfo#trustedSources, so Session#buildSurvivorship can validate a trustedSourceList
		// override against the project's REAL codes at proposal time, never a second read.
		final MatchPlanLlmPass.Session llmSession = new MatchPlanLlmPass.Session(eligible, childParentByName, priorPlans, vocabulary, crossSystemLookup, perSystemFillLookup,
			withinSystemDuplicateLookup, trustedSourceInfos).requireFilteredCandidateSearch();

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

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, MatchPlanLlmSuggester.STEP, "");
		final ObjectNode out = MatchPlanLlmSuggester.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("valueSharing", shareValues ? "SAMPLE_VALUES" : "SHAPES_ONLY");
		out.put("eligibleEntities", eligible.size());
		MatchPlanLlmSuggester.putStrings(out, "profilingSkipped", profilingSkipped);
		// 2026-09-22, "child entities matched too": informational only now, never an exclusion list - a child
		// entity is proposed/written/refreshed below exactly like any other entity (requirement 4, `docs/MATCHING.md`).
		MatchPlanLlmSuggester.putStrings(out, "childEntities", childNotes);
		MatchPlanLlmSuggester.putStrings(out, "followsParent", followsParent);
		MatchPlanLlmSuggester.putStrings(out, "preClusteringCapabilityNotVerified", capabilityNotes);
		// relation-evidence-required lot R3 (2026-09-26, docs/MATCHING.md sec 34): keyed by parent entity, one entry
		// per entity that had at least one candidate child - see buildRelationChildren's own Javadoc.
		MatchPlanLlmSuggester.writeRelationEvidence(out, relationEvidenceReports);
		try
		{
			final String systemPrompt = MatchPlanLlmPass.systemPrompt();
			// lot M13 (2026-10-18, docs/MATCHING.md sec 66): "rules first, the model refines" - the Factory's own plan for each entity whose complete facts allow one, already judged
			// by every check of the pass on a scratch session, is given to the model in the prompt as a valid starting point (and accepted as it is if resubmitted unchanged)
			final ObjectNode startingPlansNode = out.putObject("startingPlans");
			for (final MatchPlanLlmPass.StartingPlan startingPlan : llmSession.prepareStartingPlans())
			{
				startingPlansNode.put(startingPlan.entity, startingPlan.summary);
			}
			final ObjectNode noStartingPlanNode = out.putObject("noStartingPlan");
			for (final Map.Entry<String, String> reason : llmSession.startingPlanReasons().entrySet())
			{
				noStartingPlanNode.put(reason.getKey(), TextLimits.truncate(reason.getValue(), MatchPlanLlmSuggester.LAST_REFUSAL_MAX_LENGTH));
			}
			// lot M17 (2026-10-18, docs/MATCHING.md sec 69): the entities the facts mark as needing no plan (one source system, no key shared by two of its rows), kept with the result so the
			// wizard words such an entity as a measured fact ("no plan - one source only, no shared key"), never as a gap or as silence
			final ObjectNode noPlanNode = out.putObject("noPlanNeeded");
			for (final Map.Entry<String, String> noPlan : llmSession.noPlanNeeded().entrySet())
			{
				noPlanNode.put(noPlan.getKey(), noPlan.getValue());
			}
			final String userPrompt = MatchPlanLlmPass.userPrompt(projectInfo);
			final AgentRuntime.Outcome firstOutcome = AgentRuntime.run(pSession, pFactory, projectId, MatchPlanLlmSuggester.PHASE,
				"Propose matching field roles (fuzzy anchor, supporting, blocking/clustering) for the promoted entities of project " + pProjectCode + ".",
				systemPrompt, userPrompt, tools);

			// round 3 (coordinator, 2026-09-21): a DONE run whose own text correctly described plans, but never
			// called a tool, recorded nothing - one follow-up on the SAME Session/tools (refusal counters carry
			// over), quoting the model's own text and telling it plainly to use the tools. "Nothing recorded" here
			// is no plan AND no note - skip_entity also writes to notes, so a deliberate skip is never mistaken
			// for "nothing" (MatchPlanLlmPass.Session#skipEntity).
			final ProseAnswerRecovery.RecordedNothing recordedNothing = new ProseAnswerRecovery.RecordedNothing()
			{
				@Override
				public boolean test()
				{
					return llmSession.createdPlans().isEmpty() && llmSession.notes().isEmpty();
				}
			};
			final ProseAnswerRecovery.FollowUpRunner followUpRunner = new ProseAnswerRecovery.FollowUpRunner()
			{
				@Override
				public AgentRuntime.Outcome run(final String pFollowUpTask) throws OperationException
				{
					return AgentRuntime.run(pSession, pFactory, projectId, MatchPlanLlmSuggester.PHASE, "Propose matching field roles for the promoted entities "
						+ "of project " + pProjectCode + " (follow-up: the previous answer was prose, not a tool call).", systemPrompt, pFollowUpTask, tools);
				}
			};
			final ProseAnswerRecovery.Result recovery = ProseAnswerRecovery.recover(firstOutcome, recordedNothing, followUpRunner);
			final AgentRuntime.Outcome outcome = recovery.latest();

			final ObjectNode agentRun = out.putObject("agentRun");
			agentRun.put("id", outcome.runId);
			agentRun.put("status", AgentRuntime.statusOf(outcome.status));
			agentRun.put("steps", recovery.combinedSteps());
			agentRun.put("inputTokens", recovery.combinedInputTokens());
			agentRun.put("outputTokens", recovery.combinedOutputTokens());
			agentRun.put("refusedToolCalls", recovery.combinedRefusedToolCalls());
			if (outcome.detail != null)
			{
				agentRun.put("detail", outcome.detail);
			}
			out.put("proseAnswerRecovered", recovery.proseAnswerRecovered);
			out.put("earlyStop", llmSession.stoppedEarly());
			if (llmSession.stoppedEarly())
			{
				out.put("earlyStopReason", llmSession.stopReason());
			}

			// lot M9 (2026-10-18, docs/MATCHING.md sec 63): an entity the model proposed for and left WITHOUT an accepted plan gets the Factory's own fallback plan, built from
			// its measured key facts and run through the same checks as a model proposal (origin RULE, confidence 40, PROPOSED). Only in the whole-project pass: "Propose
			// another" on one entity is an explicit ask for the MODEL's plan, and its previous plan is kept when the model fails (the caller's revert path).
			final ObjectNode fallbackNode = out.putObject("fallback");
			if (MatchPlanLlmSuggester.blank(pEntity))
			{
				for (final MatchPlanLlmPass.Session.FallbackOutcome fallback : llmSession.proposeFallbacks())
				{
					final ObjectNode one = fallbackNode.putObject(fallback.entity);
					one.put("proposed", fallback.proposed);
					one.put("reason", TextLimits.truncate(fallback.reason, MatchPlanLlmSuggester.LAST_REFUSAL_MAX_LENGTH));
					one.put("attempts", fallback.attempts);
					one.put("lastRefusal", TextLimits.truncate(fallback.lastRefusal, MatchPlanLlmSuggester.LAST_REFUSAL_MAX_LENGTH));
					one.put("endedBy", fallback.endedBy);
				}
			}

			MatchPlanLlmSuggester.write(pSession, pFactory, llmSession, planEntityIdByName, out);

			// lot F1 (2026-10-19, docs/MATCHING.md sec 70): the plans are stored; each plan the model proposed is simulated on the real engine, the model is told in counts what its plan does to
			// the pairs, and answers with a revised plan or keeps it with a rationale. Nothing is decided here: only the whole-project sweep runs it (a single-entity "Propose another" has the
			// Simulate button and the statements it stores for the next proposal), and a failure of the phase never fails the proposal.
			if (MatchPlanLlmSuggester.blank(pEntity))
			{
				final MatchPlanFeedbackPass.Context feedbackContext = new MatchPlanFeedbackPass.Context();
				feedbackContext.session = pSession;
				feedbackContext.factory = pFactory;
				feedbackContext.projectId = projectId;
				feedbackContext.projectCode = pProjectCode;
				feedbackContext.project = projectInfo;
				feedbackContext.llmSession = llmSession;
				feedbackContext.entityIdByName = planEntityIdByName;
				feedbackContext.systemPrompt = systemPrompt;
				feedbackContext.locale = pLocale != null ? pLocale : pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale();
				MatchPlanFeedbackPass.run(feedbackContext, out);
			}
			else
			{
				out.putObject("feedback").put("enabled", false).put("skippedAll", "a single-entity proposal: the plan card's Simulate button gives the same figures");
			}

			final ArrayNode notesNode = out.putArray("notes");
			for (final String note : llmSession.notes())
			{
				notesNode.add(note);
			}

			// lot M5 (2026-10-18, docs/MATCHING.md sec 60): per entity left WITHOUT a plan after being proposed for: how many proposals, the last refusal as the model read it, and
			// who ended it (the pass, or empty when the model stopped or the run's budget did). The wizard words the gap from this (MatchingPlanCoverage), never "no reason".
			final ObjectNode attemptsNode = out.putObject("attempts");
			for (final MatchPlanLlmPass.Session.Attempt attempt : llmSession.unplannedAttempts())
			{
				final ObjectNode one = attemptsNode.putObject(attempt.entity);
				one.put("count", attempt.attempts);
				one.put("lastRefusal", TextLimits.truncate(attempt.lastRefusal, MatchPlanLlmSuggester.LAST_REFUSAL_MAX_LENGTH));
				one.put("endedBy", attempt.endedBy);
				if (!attempt.fallbackRefusal.isEmpty())
				{
					// lot M9: the Factory's fallback was tried for this entity too, and refused - the wizard says so next to the model's last refusal
					one.put("fallback", TextLimits.truncate(attempt.fallbackRefusal, MatchPlanLlmSuggester.LAST_REFUSAL_MAX_LENGTH));
				}
			}

			// item 3 of the previous round (ok must reflect the real outcome) plus round 3 (a DONE run that still
			// recorded nothing after its one follow-up is NOT ok either - status NO_OUTPUT, step not DONE, the
			// model's own text returned so a person can read it)
			// a plan refused for not fitting its field leaves that entity without a new plan: the pass did not succeed for it, and says why
			final String refusedText = MatchPlanLlmSuggester.refusedText(out.path("refused"));
			final boolean succeeded = !recovery.noOutput && outcome.status == AgentLoop.Status.DONE && refusedText.isEmpty();
			out.put("status", recovery.noOutput ? "NO_OUTPUT" : AgentRuntime.statusOf(outcome.status));
			out.put("ok", succeeded);
			if (recovery.noOutput)
			{
				out.put("modelText", recovery.finalText);
			}

			final Locale msgLocale = pLocale != null ? pLocale : pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale();
			final String message = recovery.noOutput
				? "the model's final answer was prose, never a tool call, even after one follow-up: nothing was recorded for project " + pProjectCode + "."
				: com.on.ebx.factory.wizard.WizardMessages.get(msgLocale, "wizard.step6.run.summary",
					com.on.ebx.factory.wizard.WizardMessages.plural(msgLocale, out.path("written").size(), "wizard.step6.run.written.one", "wizard.step6.run.written.many"),
					com.on.ebx.factory.wizard.WizardMessages.plural(msgLocale, out.path("refreshed").size(), "wizard.step6.run.refreshed.one", "wizard.step6.run.refreshed.many"),
					com.on.ebx.factory.wizard.WizardMessages.plural(msgLocale, out.path("repeated").size(), "wizard.step6.run.unchanged.one", "wizard.step6.run.unchanged.many"),
					outcome.runId, AgentRuntime.statusOf(outcome.status), llmSession.stoppedEarly() ? ", stopped early" : "");
			PipelineSteps.end(pSession, pFactory, stepId, succeeded, refusedText.isEmpty() ? message : message + " Not stored: " + refusedText, out.toString());
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), out.toString());
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
			StepGuard.endUnexpected(pSession, pFactory, stepId, ex, out.toString());
			throw ex;
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------- writing

	private static void write(final Session pSession, final Adaptation pFactory, final MatchPlanLlmPass.Session pLlmSession, final Map<String, String> pEntityIdByName,
		final ObjectNode pOut) throws OperationException
	{
		MatchPlanLlmSuggester.write(pSession, pFactory, pLlmSession.createdPlans(), pEntityIdByName, pOut);
	}

	/**
	 * Lot F1 (2026-10-19, {@code docs/MATCHING.md} &sect;70): only the rationale of a plan row moves - the plan the model kept after the simulation feedback gets the line that says so. The tree,
	 * the clustering, the weights, the status and the message are not touched; a row decided by a person since, or gone, is left alone (false).
	 */
	static boolean updateRationale(final Session pSession, final Adaptation pFactory, final Adaptation pRow, final String pRationale)
	{
		if (pRow == null || !MatchPlanLlmPass.isRewritable(pRow.getString(FactoryPaths.MatchPlan.STATUS)) || "USER".equals(pRow.getString(FactoryPaths.MatchPlan.ORIGIN)))
		{
			return false;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(pRow.getAdaptationName());
				value.setValue(TextLimits.truncate(pRationale, MatchPlanLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.MatchPlan.RATIONALE);
				pContext.doModifyContent(pRow, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().warn("[mdm-factory] Matching plan feedback: the rationale could not be stored - " + result.getExceptionFullMessage(Locale.ENGLISH));
			return false;
		}
		return true;
	}

	/** The plans {@code pPlans} written (created or refreshed) into the {@code MatchPlan} table, with the outcome in {@code pOut} ({@code written}, {@code refreshed}, {@code alreadyDecided}, {@code repeated}, {@code refused}). */
	static void write(final Session pSession, final Adaptation pFactory, final List<MatchPlanLlmPass.CreatedPlan> pPlans, final Map<String, String> pEntityIdByName,
		final ObjectNode pOut) throws OperationException
	{
		final List<MatchPlanLlmPass.CreatedPlan> plans = pPlans;
		final AdaptationTable matchPlans = pFactory.getTable(FactoryPaths.MATCH_PLAN);
		final List<String> written = new ArrayList<String>();
		final List<String> refreshed = new ArrayList<String>();
		final List<String> alreadyDecided = new ArrayList<String>();
		// reject/repropose lot (2026-09-22): outcome (b) of "Propose another" - the model proposed the exact same
		// tier/field structure again (MatchPlanLlmPass.sameStructure) on top of an existing row - the row's own
		// requirements/rationale/confidence are left untouched, only status/message move.
		final List<String> repeated = new ArrayList<String>();
		// fix/match-plan-requirements-length (2026-10-12): a plan whose JSON does not fit its field is REFUSED for that entity (never cut into
		// unparseable JSON, never a hole: the row keeps its previous plan and the caller's revert path puts it back to PROPOSED). Entity -> reason.
		final Map<String, String> refused = new LinkedHashMap<String, String>();

		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final MatchPlanLlmPass.CreatedPlan plan : plans)
				{
					final String entityId = pEntityIdByName.get(plan.entityName);
					final Adaptation existing = MatchPlanLlmSuggester.findMatchPlan(pFactory, entityId);
					// defensive re-check: MatchPlanLlmPass.Session already refuses this at proposal time, but a write
					// path must never trust a upstream guard alone to protect a person's own decision. SUPERSEDED is
					// rewritable exactly like PROPOSED (reject/repropose lot, 2026-09-22 - MatchPlanLlmPass.isRewritable).
					final String existingStatus = existing == null ? null : existing.getString(FactoryPaths.MatchPlan.STATUS);
					if (existing != null && (!MatchPlanLlmPass.isRewritable(existingStatus) || "USER".equals(existing.getString(FactoryPaths.MatchPlan.ORIGIN))))
					{
						alreadyDecided.add(plan.entityName + " (" + existingStatus + ")");
						continue;
					}
					final String existingRequirements = existing == null ? null : existing.getString(FactoryPaths.MatchPlan.REQUIREMENTS);
					final String doesNotFit = StoredJsonFit.planRefusal(plan.requirementsJson, plan.evidenceJson);
					if (doesNotFit != null)
					{
						refused.put(plan.entityName, doesNotFit);
						continue;
					}
					final boolean samePlan = existing != null && MatchPlanLlmPass.sameStructure(existingRequirements, plan.requirementsJson);
					final ValueContextForUpdate value = existing == null ? pContext.getContextForNewOccurrence(matchPlans) : pContext.getContext(existing.getAdaptationName());
					if (samePlan)
					{
						// (b): the plan itself is untouched (requirements/origin/confidence/rationale/reviewComment all
						// stay exactly as they were) - only status returns to PROPOSED and a note explains why nothing
						// else changed, so a reviewer is never left staring at SUPERSEDED with no path forward.
						value.setValue("PROPOSED", FactoryPaths.MatchPlan.STATUS);
						value.setValue(TextLimits.truncate("The model proposed the same plan again - accept it, or comment differently.", TextLimits.MESSAGE_MAX_LENGTH),
							FactoryPaths.MatchPlan.MESSAGE);
						pContext.doModifyContent(existing, value);
						repeated.add(plan.entityName);
						continue;
					}
					value.setValue(entityId, FactoryPaths.MatchPlan.ENTITY);
					if (existing != null && existingRequirements != null && !existingRequirements.isEmpty())
					{
						// (a): a genuinely different plan replaces the row's requirements - the plan it replaces is kept,
						// never silently lost, so a reviewer can still see what used to be there (coordinator instruction,
						// 2026-09-22: "keep the old requirements JSON ... so nothing is lost").
						// read from the field itself, so it fits (previousRequirements has the same limit); never cut
						value.setValue(existingRequirements, FactoryPaths.MatchPlan.PREVIOUS_REQUIREMENTS);
					}
					// parsed back by the configurator and the review screen: stored whole (checked above), never cut
					value.setValue(plan.requirementsJson, FactoryPaths.MatchPlan.REQUIREMENTS);
					// lot M9: LLM for the model's plan, RULE for the Factory's fallback plan (requirements.fallback says which)
					value.setValue(plan.origin, FactoryPaths.MatchPlan.ORIGIN);
					value.setValue(Integer.valueOf(plan.confidence), FactoryPaths.MatchPlan.CONFIDENCE);
					value.setValue(TextLimits.truncate(plan.rationale, MatchPlanLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.MatchPlan.RATIONALE);
					// review-match-plan lot (2026-09-22): the structured sibling of the prose already folded into
					// rationale above - see master.MatchPlanEvidence's own Javadoc for the shape.
					value.setValue(plan.evidenceJson, FactoryPaths.MatchPlan.EVIDENCE);
					value.setValue("PROPOSED", FactoryPaths.MatchPlan.STATUS);
					// a fresh, genuinely new plan makes any earlier "same plan again"/failure note stale - clear it,
					// never leave a reviewer reading last round's note next to this round's real plan.
					value.setValue("", FactoryPaths.MatchPlan.MESSAGE);
					if (existing == null)
					{
						value.setValue(Boolean.valueOf(MatchPlanLlmSuggester.autoMergeForWrite(true, false)), FactoryPaths.MatchPlan.AUTO_MERGE);
						pContext.doCreateOccurrence(value, matchPlans);
						written.add(plan.entityName);
					}
					else
					{
						// automerge-toggle lot (2026-09-23), task 2: a person may have turned this ON from the review
						// screen's own control (ebx.ProposalReviews.setMatchPlanAutoMerge) since this plan was last
						// written - read it back explicitly and re-write it, rather than relying on the ValueContext's
						// own partial-update default (which already keeps it, but silently - see this method's own
						// Javadoc and MatchPlanLlmSuggester#autoMergeForWrite for why this is written explicitly).
						value.setValue(Boolean.valueOf(MatchPlanLlmSuggester.autoMergeForWrite(false, Boolean.TRUE.equals(existing.get(FactoryPaths.MatchPlan.AUTO_MERGE)))),
							FactoryPaths.MatchPlan.AUTO_MERGE);
						pContext.doModifyContent(existing, value);
						refreshed.add(plan.entityName);
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveMatchProposals", "Writing the language-model matching proposals failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		MatchPlanLlmSuggester.putStrings(pOut, "written", written);
		MatchPlanLlmSuggester.putStrings(pOut, "refreshed", refreshed);
		MatchPlanLlmSuggester.putStrings(pOut, "alreadyDecided", alreadyDecided);
		MatchPlanLlmSuggester.putStrings(pOut, "repeated", repeated);
		final ObjectNode refusedNode = pOut.putObject("refused");
		for (final Map.Entry<String, String> entry : refused.entrySet())
		{
			refusedNode.put(entry.getKey(), entry.getValue());
		}
	}

	/** "Entity: reason; Entity: reason" for the {@code refused} object {@link #write} puts in the outcome; empty when no plan was refused. Pure. */
	static String refusedText(final JsonNode pRefused)
	{
		final StringBuilder out = new StringBuilder();
		if (pRefused != null && pRefused.isObject())
		{
			final java.util.Iterator<Map.Entry<String, JsonNode>> fields = pRefused.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				out.append(out.length() == 0 ? "" : "; ").append(field.getKey()).append(": ").append(field.getValue().asText());
			}
		}
		return out.toString();
	}

	// -------------------------------------------------------------------------------------------------------- guide

	private static MatchPlanLlmPass.GuideTexts readGuideTexts(final McpClient pClient) throws McpException, OperationException
	{
		final String[] texts = new String[MatchPlanLlmSuggester.GUIDE_TOPICS.length];
		for (int i = 0; i < MatchPlanLlmSuggester.GUIDE_TOPICS.length; i++)
		{
			final ObjectNode args = MatchPlanLlmSuggester.MAPPER.createObjectNode();
			args.put("topic", MatchPlanLlmSuggester.GUIDE_TOPICS[i]);
			final McpClient.ToolResult result = pClient.callTool("matching_guide", args);
			if (result.error || result.json == null)
			{
				throw EngineRefusal.coded("external.matchingGuide", "matching_guide(topic=" + MatchPlanLlmSuggester.GUIDE_TOPICS[i] + ") call failed: "
					+ MatchPlanProfileReader.shortText(result.text));
			}
			if (!result.json.path("success").asBoolean(true))
			{
				throw EngineRefusal.coded("external.matchingGuide", "matching_guide(topic=" + MatchPlanLlmSuggester.GUIDE_TOPICS[i] + ") answered " + MatchPlanProfileReader.describeSoftMiss(result.json));
			}
			texts[i] = result.json.path("data").asText("");
		}
		return new MatchPlanLlmPass.GuideTexts(texts[0], texts[1], texts[2]);
	}

	// ---------------------------------------------------------------------------------------------------------- read

	/**
	 * Builds one entity's {@link MatchPlanLlmPass.EntityInfo}: structural metadata from the factory's own
	 * tables, profiling numbers from {@code pProfileData} via {@link MatchPlanProfileReader} (pure JSON
	 * parsing - see that class' own Javadoc). This is the ONE place {@code pShareValues} is actually applied:
	 * {@link MatchPlanProfileReader.FieldStats#mostFrequentValue}/{@code #mostFrequentToken} are read
	 * unconditionally by the reader, and only ever copied into the {@link MatchPlanLlmPass.AttributeInfo}
	 * handed to the prompt when {@code pShareValues} is true - the counts are copied regardless, since they
	 * are numbers, not values (see {@link MatchPlanLlmPass} class Javadoc).
	 * <p>
	 * <b>Coordinator review, 2026-09-21, second finding from the same live run</b>: this is also where each
	 * attribute's {@link MatchPlanLlmPass.AttributeInfo#role role} is derived ({@link MatchPlanLlmPass#deriveRole},
	 * pure, given the EBX-read booleans from {@link #mappingsAreOnSourceKeyColumns}). <b>Cross-system sharing is
	 * deliberately NOT computed here any more</b> (coordinator review, 2026-09-21, blocking change before merge):
	 * an earlier revision measured it for every non-{@code SOURCE_KEY} attribute right here, up front - a query
	 * per candidate attribute regardless of cardinality, which does not scale to this project's real table sizes.
	 * It is now measured lazily, only for a field the model actually proposes in an accepted {@code MATCH} tier,
	 * through {@link MatchPlanLlmPass.CrossSystemUsageLookup} - see {@link #suggest} (where the lookup is built)
	 * and {@code ebx.CrossSystemMeasures#sharedCount} (the query itself, shared with {@code
	 * ebx.MatchPlanSuggester}'s own deterministic route since 2026-09-21).
	 * <p>
	 * <b>Survivorship lot (2026-09-22, `docs/MATCHING.md` &sect;15)</b>: {@code pSession}/{@code pMasterDataset}
	 * are the same live master-table handle {@link MatchPlanLlmPass.CrossSystemUsageLookup}/{@link
	 * MatchPlanLlmPass.PerSystemFillLookup} already use, given here too so this method can measure {@link
	 * MatchPlanLlmPass.EntityInfo#rowCountBySystem}/{@link MatchPlanLlmPass.AttributeInfo#fillCountBySystem}/
	 * {@link MatchPlanLlmPass.AttributeInfo#sharedFoldGroupCount}/{@link
	 * MatchPlanLlmPass.AttributeInfo#cosmeticVariantGroupsCount}/{@link
	 * MatchPlanLlmPass.AttributeInfo#divergingGroupsCount}/{@link MatchPlanLlmPass.EntityInfo#identityKeyField}/
	 * {@link MatchPlanLlmPass.EntityInfo#sharedIdentityGroupCount} EAGERLY (unlike those two lazy lookups) - see
	 * those fields' own Javadoc for why the survivorship prompt needs them up front rather than only after a
	 * tier has already been decided. {@code null} (a project profiled with no master table opened) degrades
	 * every one of these to null, never a crash.
	 * <p>
	 * <b>Coordinator fix, 2026-09-22 18:20 (measured live)</b>: the divergence-after-fold statement must be
	 * grouped by the ENTITY's own identity key ({@link #entityIdentityKeyField}), never by the attribute's own
	 * fold key - see {@code ebx.CrossSystemMeasures#cosmeticVariantGroupsCount}/{@code #divergingGroupsCount}'s
	 * own Javadoc for the exact live numbers (city: 32/33 cosmetic, 0/33 genuine) that exposed the bug.
	 * <p>
	 * <b>Coordinator fix, 2026-09-22 21:36 (agent run 29, project Companies) - the 18:20 fix above was itself
	 * wrong about WHICH field is the entity's identity key.</b> It read {@code
	 * MatchPlan.requirements.clusteringFields[0]} - on {@code Company} that is {@code country}, a BLOCKING/
	 * clustering field, not an identity signal. Grouped by {@code country}, {@code divergingGroupsCount}
	 * answered a huge number for {@code name} (many {@code nameKey} values per country - of course they differ,
	 * a country is not remotely a unique-identity bucket) and exactly 0 for {@code country} itself (a field is
	 * never distinct from its own grouping key) - the model then wrote, verbatim, that {@code name} needed
	 * "most frequent" survivorship "due to the high genuine divergence after folding" and {@code country} needed
	 * "most trusted source" because it showed "no divergence": both rationales were artefacts of grouping by the
	 * wrong field, not real findings. {@link #entityIdentityKeyField} no longer reads {@code clusteringFields} at
	 * all - see its own Javadoc for the corrected rule (the entity's fuzzy ANCHOR's own derived key, falling back
	 * to the first attribute with a derived key, falling back to a prior EXACT single-field identifier, NEVER a
	 * clustering field). Re-measured the same day, live SQL, grouping by {@code nameKey} (the entity's real
	 * matching key, 33 groups shared across ERP/CRM): {@code cityKey} diverges in 0 of 33 groups (genuinely no
	 * disagreement, once folded) while the raw {@code city} spelling differs in 32 of 33 (cosmetic only) - the
	 * same live numbers the 18:20 fix already reported, now reached with the correct grouping key by construction
	 * rather than by coincidence.
	 */
	private static MatchPlanLlmPass.EntityInfo toLlmEntity(final Adaptation pFactory, final String pEntityId, final StagingModel.EntityInfo pStagingEntity,
		final JsonNode pProfileData, final Map<String, Boolean> pPreClusteringByField, final boolean pShareValues, final boolean pShareShapes,
		final MatchPlanLlmSuggester.ChildLink pChildLink, final Session pSession, final Adaptation pMasterDataset, final CleaningSteps.Lookups pLookups,
		final List<MatchPlanLlmPass.RelationChildInfo> pRelationChildren)
	{
		final LlmDataPolicy dataPolicy = LlmDataPolicy.of(pFactory);
		final List<StagingModel.AttributeInfo> accepted = new ArrayList<StagingModel.AttributeInfo>();
		for (final StagingModel.AttributeInfo attribute : pStagingEntity.attributes)
		{
			if (!attribute.primaryKey && TechnicalKeyRule.isAcceptedLike(attribute.status))
			{
				accepted.add(attribute);
			}
		}
		final Map<String, JsonNode> profileByField = MatchPlanProfileReader.fieldsByName(pProfileData, MatchPlanLlmSuggester.TECHNICAL_FIELDS);
		final long rowCount = MatchPlanProfileReader.rowCount(pProfileData);

		// Matching-key contract (2026-09-22, PLAN.md/docs/MATCHING.md): a derived attribute (StagingAttribute.derivation
		// FOLD_KEY) points at its source attribute of the same entity via derivedFrom - name -> source name here, then
		// inverted so a SOURCE attribute (e.g. "city") can expose the name of its own key (e.g. "cityKey") to the
		// guards/prompt. Guarded per CLAUDE.md sec 9 - see readDerivedFromByName's own Javadoc.
		final Map<String, String> derivedFromByName = pEntityId == null ? Collections.<String, String>emptyMap()
			: MatchPlanLlmSuggester.readDerivedFromByName(pFactory, pEntityId);
		// lot M8a (2026-10-04, docs/MATCHING.md sect;51): the child entity of every ACCEPTED CHILD_KEYS attribute, by attribute name - read once per entity
		final Map<String, String> childKeysChildByName = pEntityId == null ? Collections.<String, String>emptyMap()
			: MatchPlanLlmSuggester.readChildKeysChildByName(pFactory, pEntityId);
		final Map<String, String> matchKeyByName = new HashMap<String, String>();
		for (final Map.Entry<String, String> entry : derivedFromByName.entrySet())
		{
			matchKeyByName.put(entry.getValue(), entry.getKey());
		}

		// Survivorship lot (2026-09-22, docs/MATCHING.md sec 15): per-system row counts, measured ONCE per entity -
		// the denominator for AttributeInfo#fillCountBySystem below, and the "which systems exist" population every
		// per-attribute measurement is gated on (a single-source entity has no survivorship question to ask, so
		// nothing further is measured for it - same bounded-by-a-real-fact discipline CrossSystemUsageLookup's own
		// Javadoc established for the cross-system-sharing count).
		final Map<String, Long> rowCountBySystem = pMasterDataset == null ? null : CrossSystemMeasures.rowCountsBySystem(pSession, pMasterDataset, pStagingEntity.name);
		final boolean multiSource = rowCountBySystem != null && rowCountBySystem.size() >= 2;
		int fillMeasuredCount = 0;

		// Coordinator fix, 2026-09-22 21:36 (run 29): the entity's own identity key (e.g. nameKey) - the entity's
		// MATCHING key, never a blocking/clustering field (the 18:20 fix's own clusteringFields[0] read was the
		// bug: see entityIdentityKeyField's own Javadoc for the corrected rule). Never re-derived per attribute:
		// one identity key serves every attribute's own divergingGroupsCount below.
		final String identityKeyField = multiSource
			? MatchPlanLlmSuggester.entityIdentityKeyField(pFactory, pEntityId, accepted, derivedFromByName, matchKeyByName) : null;
		final Integer sharedIdentityGroupCount = identityKeyField == null ? null
			: CrossSystemMeasures.sharedCount(pSession, pMasterDataset, pStagingEntity.name, identityKeyField);

		// The name the model sees, proposes fields by, and every master-table read below uses is the MASTER field
		// name (master.MasterFieldNameRule, 2026-09-23, docs/MASTER-ZONE.md §13: an attribute named like the
		// table's own id/sourceSystem/sourceId/_loadId/_receivedAt is promoted as "<name>_src") - the add-on's
		// profile, fieldCapabilities and the SQL measures all name master columns, and a plan field names a master
		// column too. The StagingAttribute lookups (attributeId, derivedFrom, matchKeyByName) keep the attribute's
		// own name: those are factory rows, not master columns.
		final Map<String, String> masterFieldByAttribute = MasterFieldNameRule.masterFieldNames(pStagingEntity.attributes);
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		for (final StagingModel.AttributeInfo attribute : accepted)
		{
			final String masterField = masterFieldByAttribute.get(attribute.name);
			final JsonNode fieldProfile = profileByField.get(masterField);
			final String attributeId = MatchPlanLlmSuggester.attributeId(pFactory, pEntityId, attribute.name);
			final MatchPlanProfileReader.FieldStats stats = fieldProfile == null ? null : MatchPlanProfileReader.readFieldStats(fieldProfile);
			final Boolean preClustering = pPreClusteringByField.get(masterField);
			final String derivedFrom = derivedFromByName.get(attribute.name);
			final String role = MatchPlanLlmPass.deriveRole(derivedFrom, attribute.referenceEntity != null,
				attributeId == null ? Collections.<Boolean>emptyList() : MatchPlanLlmSuggester.mappingsAreOnSourceKeyColumns(pFactory, attributeId));

			// Survivorship lot (2026-09-22, corrected 18:20 the same day): fill-by-system/fold measures, EAGER but
			// capped (MAX_SURVIVORSHIP_MEASURED_ATTRIBUTES) - only for a BUSINESS/REFERENCE field of a multi-source
			// entity, never for a SOURCE_KEY (useless across systems by construction) or MATCH_KEY (its source
			// attribute is measured instead) field. cosmeticVariantGroupsCount groups by THIS attribute's own fold
			// key (matches CrossSystemMeasures#cosmeticVariantGroupsCount's own contract); divergingGroupsCount
			// groups by the ENTITY's identity key instead (CrossSystemMeasures#divergingGroupsCount) - never
			// computed at all when the entity has none (no fallback to a degenerate always-zero query: userPrompt
			// says explicitly "not measured - no identity key yet" instead of showing a fabricated number).
			Map<String, Long> fillCountBySystem = null;
			Integer sharedFoldGroupCount = null;
			Integer cosmeticVariantGroupsCount = null;
			Integer divergingGroupsCount = null;
			final boolean measurableRole = MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS.equals(role) || MatchPlanLlmPass.AttributeInfo.ROLE_REFERENCE.equals(role);
			if (multiSource && measurableRole && fillMeasuredCount < MatchPlanLlmSuggester.MAX_SURVIVORSHIP_MEASURED_ATTRIBUTES)
			{
				fillCountBySystem = CrossSystemMeasures.perSystemFilledCounts(pSession, pMasterDataset, pStagingEntity.name, masterField);
				fillMeasuredCount++;
				final String ownMatchKey = matchKeyByName.get(attribute.name);
				if (ownMatchKey != null)
				{
					sharedFoldGroupCount = CrossSystemMeasures.sharedCount(pSession, pMasterDataset, pStagingEntity.name, ownMatchKey);
					cosmeticVariantGroupsCount = CrossSystemMeasures.cosmeticVariantGroupsCount(pSession, pMasterDataset, pStagingEntity.name, ownMatchKey, masterField);
					if (identityKeyField != null)
					{
						divergingGroupsCount = CrossSystemMeasures.divergingGroupsCount(pSession, pMasterDataset, pStagingEntity.name, identityKeyField, ownMatchKey);
					}
				}
			}

			// Lot 1 (2026-09-23, quality-informed survivorship, docs/MATCHING.md sec 23): gated on fillCountBySystem
			// != null - i.e. this attribute was ALREADY within the fill-measurement cap just above (never a second,
			// independent budget: fillMeasuredCount was already incremented for it in that case) - AND carries at
			// least one accepted validity rule (StagingLoader#readQualityRules's own REFERENCE/CHECK_DIGIT/PATTERN
			// filter): most attributes have none, so this is a no-op for them, never an extra query. attributeId is
			// already resolved above for cleaningSteps/qualityRules - reused, never a second lookup.
			final List<QualityRuleCheck.Rule> validityRules = fillCountBySystem != null && attributeId != null
				? StagingLoader.readQualityRules(pFactory, attributeId) : Collections.<QualityRuleCheck.Rule>emptyList();
			final boolean needsValidity = fillCountBySystem != null && !validityRules.isEmpty();

			// lot B: never the frequent value/token of a sensitive attribute, whatever the level
			final boolean shareThis = pShareValues && !dataPolicy.isSensitive(masterField) && !dataPolicy.isSensitive(attribute.name)
				&& !dataPolicy.isSensitive(attribute.label);
			// 2026-09-25, "matching-plan-child-and-identity" lot (docs/MATCHING.md sec 14, PersVol3): per-system value
			// SHAPES for a TEXT attribute - gated on fillCountBySystem != null, the SAME already-within-budget test
			// validCountBySystem uses just above (never a second, independent measurement budget), a STRING data type
			// (a shape on a DATE/decimal is meaningless - dates are compared as dates, decimals as numbers), the
			// instance's LlmDataPolicy (pShareShapes; SHAPES_ONLY or above, never sensitive - same dataPolicy.isSensitive
			// gate shareThis already uses), and never on a SOURCE_KEY (per-source, never usable across systems anyway,
			// same reasoning ROLE_SOURCE_KEY already carries everywhere else).
			final boolean needsShapes = pShareShapes && fillCountBySystem != null && "STRING".equals(attribute.dataType)
				&& !MatchPlanLlmPass.AttributeInfo.ROLE_SOURCE_KEY.equals(role) && !dataPolicy.isSensitive(masterField) && !dataPolicy.isSensitive(attribute.name)
				&& !dataPolicy.isSensitive(attribute.label);

			// lot volume-4 (2026-09-28, task brief item 6): needsValidity and needsShapes can BOTH be true for the
			// same STRING attribute (one that also carries a validity rule) - the pre-lot code called
			// CrossSystemMeasures#perSystemValidityCounts and #perSystemValueShapes independently, each running
			// the identical "GROUP BY sourceSystem, field" SQL statement a second time. Fetch the raw distinct-
			// value rows ONCE here when either derivation is needed, then feed the SAME rows to both pure
			// derivations (ValidityAggregator#aggregate, CrossSystemMeasures#shapesFromDistinctValues) - never a
			// second query for the same field within this one suggestion run.
			final List<ValidityAggregator.DistinctValue> distinctValues = needsValidity || needsShapes
				? CrossSystemMeasures.perSystemDistinctValues(pSession, pMasterDataset, pStagingEntity.name, masterField) : null;

			Map<String, Long> validCountBySystem = null;
			Map<String, Long> checkedCountBySystem = null;
			if (needsValidity && distinctValues != null)
			{
				final Map<String, ValidityAggregator.SystemValidity> validity = ValidityAggregator.aggregate(distinctValues, validityRules, pLookups);
				if (validity != null && !validity.isEmpty())
				{
					validCountBySystem = new LinkedHashMap<String, Long>();
					checkedCountBySystem = new LinkedHashMap<String, Long>();
					for (final Map.Entry<String, ValidityAggregator.SystemValidity> entry : validity.entrySet())
					{
						checkedCountBySystem.put(entry.getKey(), Long.valueOf(entry.getValue().checked));
						validCountBySystem.put(entry.getKey(), Long.valueOf(entry.getValue().valid));
					}
				}
			}

			Map<String, List<String>> shapesBySystem = null;
			if (needsShapes && distinctValues != null)
			{
				shapesBySystem = CrossSystemMeasures.shapesFromDistinctValues(distinctValues);
			}
			attributes.add(new MatchPlanLlmPass.AttributeInfo(masterField, attribute.label, attribute.dataType, attribute.maxLength, attribute.referenceEntity,
				stats == null ? null : stats.fillRate, stats == null ? null : stats.fillRateProvenance, stats == null ? null : stats.distinctCount,
				stats == null ? null : stats.cardinalityProvenance, shareThis && stats != null ? stats.mostFrequentValue : null,
				stats == null ? null : stats.mostFrequentValueCount, shareThis && stats != null ? stats.mostFrequentToken : null,
				stats == null ? null : stats.mostFrequentTokenDocFrequency, preClustering == null || preClustering.booleanValue(), role,
				attributeId == null ? Collections.<String>emptyList() : MatchPlanLlmSuggester.cleaningSteps(pFactory, attributeId),
				attributeId == null ? Collections.<String>emptyList() : MatchPlanLlmSuggester.qualityRules(pFactory, attributeId), derivedFrom,
				matchKeyByName.get(attribute.name), fillCountBySystem, sharedFoldGroupCount, cosmeticVariantGroupsCount, divergingGroupsCount, validCountBySystem,
				checkedCountBySystem, shapesBySystem, ChildKeysDerivation.DERIVATION_CHILD_KEYS.equals(attribute.derivation) ? MatchPlanLlmPass.AttributeInfo.DERIVATION_CHILD_KEYS : null,
				childKeysChildByName.get(attribute.name)));
		}
		// 2026-09-26, PersVol4 (docs/MATCHING.md sec 31): a child's parent reference is its LINK FIELD (the master
		// foreign key, e.g. personneRef, filled for every feed), never the plain link attribute (personne, filled only
		// by a key-linked root feed) nor any other attribute pointing at the parent. Always measured per system (the
		// skip follow-up and the prompt both read it), outside the survivorship cap.
		List<MatchPlanLlmPass.AttributeInfo> offered = attributes;
		String childOfAttribute = pChildLink == null ? null : pChildLink.attributeName;
		if (pChildLink != null && pChildLink.linkField != null)
		{
			final StagingModel.AttributeInfo linkAttribute = MatchPlanLlmSuggester.attributeByName(accepted, pChildLink.attributeName);
			final JsonNode linkProfile = profileByField.get(pChildLink.linkField);
			final MatchPlanProfileReader.FieldStats linkStats = linkProfile == null ? null : MatchPlanProfileReader.readFieldStats(linkProfile);
			final Boolean linkPreClustering = pPreClusteringByField.get(pChildLink.linkField);
			final Map<String, Long> linkFill = multiSource
				? CrossSystemMeasures.perSystemFilledCounts(pSession, pMasterDataset, pStagingEntity.name, pChildLink.linkField) : null;
			final MatchPlanLlmPass.AttributeInfo linkField = new MatchPlanLlmPass.AttributeInfo(pChildLink.linkField,
				linkAttribute == null ? pChildLink.linkField : linkAttribute.label, "STRING", null,
				linkAttribute == null ? pChildLink.parent : linkAttribute.referenceEntity, linkStats == null ? null : linkStats.fillRate,
				linkStats == null ? null : linkStats.fillRateProvenance, linkStats == null ? null : linkStats.distinctCount,
				linkStats == null ? null : linkStats.cardinalityProvenance, null, null, null, null, linkPreClustering == null || linkPreClustering.booleanValue(),
				MatchPlanLlmPass.AttributeInfo.ROLE_REFERENCE, Collections.<String>emptyList(), Collections.<String>emptyList(), null, null, linkFill, null, null, null,
				null, null, null);
			offered = MatchPlanLlmPass.withParentLinkField(attributes, masterFieldByAttribute.get(pChildLink.attributeName), linkField);
			childOfAttribute = pChildLink.linkField;
		}
		return new MatchPlanLlmPass.EntityInfo(pStagingEntity.name, pStagingEntity.label, rowCount, offered,
			pChildLink == null ? null : pChildLink.parent, childOfAttribute, rowCountBySystem, identityKeyField, sharedIdentityGroupCount,
			pRelationChildren);
	}

	/** {@link #buildRelationChildren}'s own result: the children handed to the prompt, and the report of what was offered/left out - see {@link RelationEvidenceReport}. */
	static final class RelationChildrenResult
	{
		final List<MatchPlanLlmPass.RelationChildInfo> children;
		final MatchPlanLlmSuggester.RelationEvidenceReport report;

		RelationChildrenResult(final List<MatchPlanLlmPass.RelationChildInfo> pChildren, final MatchPlanLlmSuggester.RelationEvidenceReport pReport)
		{
			this.children = pChildren;
			this.report = pReport;
		}
	}

	/**
	 * Relation-evidence-required lot R3 (2026-09-26, `docs/MATCHING.md` &sect;34): what {@link #buildRelationChildren}
	 * offered to, or left out of, ONE parent entity's own relation-evidence prompt section - built purely from what
	 * {@link #buildRelationChildren} already computes, so it never needs a second pass over the same data. Exists
	 * because {@link #buildRelationChildren} used to have five silent {@code continue} statements with nothing
	 * recorded anywhere: a live run whose model never used a relation field could not be told "the child was never
	 * even offered" from "the model saw it and ignored it" (PersVol/Personnes run 112, 2026-09-26 21:42 - see the
	 * task brief this lot answers). {@code entityName} is the PARENT entity; {@link #offered}/{@link #skipped} are
	 * both empty exactly when the parent has no candidate child at all ({@code childEntityNames} empty in {@link
	 * #buildRelationChildren}) - {@link #suggest} never stores or logs a report in that case, since there is
	 * nothing to say.
	 */
	static final class RelationEvidenceReport
	{
		final String entityName;
		final List<MatchPlanLlmSuggester.RelationEvidenceReport.Offered> offered;
		final List<MatchPlanLlmSuggester.RelationEvidenceReport.Skipped> skipped;

		RelationEvidenceReport(final String pEntityName, final List<MatchPlanLlmSuggester.RelationEvidenceReport.Offered> pOffered,
			final List<MatchPlanLlmSuggester.RelationEvidenceReport.Skipped> pSkipped)
		{
			this.entityName = pEntityName;
			this.offered = pOffered;
			this.skipped = pSkipped;
		}

		/** One child entity actually offered as relation evidence for the parent - see {@link RelationChildInfo}. */
		static final class Offered
		{
			final String childEntity;
			final String fkField;
			final int attributeCount;
			final boolean rowStatsMeasured;

			Offered(final String pChildEntity, final String pFkField, final int pAttributeCount, final boolean pRowStatsMeasured)
			{
				this.childEntity = pChildEntity;
				this.fkField = pFkField;
				this.attributeCount = pAttributeCount;
				this.rowStatsMeasured = pRowStatsMeasured;
			}
		}

		/** One candidate child entity {@link #buildRelationChildren} left out, and why - never a name alone with no reason. */
		static final class Skipped
		{
			final String childEntity;
			final String reason;

			Skipped(final String pChildEntity, final String pReason)
			{
				this.childEntity = pChildEntity;
				this.reason = pReason;
			}
		}
	}

	/**
	 * Relation-fields lot (2026-09-26, `docs/MATCHING.md` dated entry): every promoted CHILD of {@code
	 * pEntityName} (a promoted entity whose own foreign key targets it - {@code MasterModel.Result#foreignKeys},
	 * the reverse of {@link #childLink}), each with its own measured, ACCEPTED, non-technical, non-reference
	 * attributes ({@code profile_table_for_matching} on the child's OWN master table - same profiling call, same
	 * {@code shareValues}/sensitive-field discipline as the parent's own attributes) and per-parent row-count
	 * stats ({@code ebx.RelationRowCountMeasures}). Best-effort throughout: a child whose own profiling call
	 * fails is simply skipped (never fails the whole matching-plan-suggestion route over one child) - the entity
	 * it belongs to is not itself a plan target here, only supporting evidence for its parent's own plan.
	 * <p>
	 * <b>Relation-evidence-required lot R3 (2026-09-26):</b> every one of the five points below where a candidate
	 * child used to be dropped with a bare {@code continue} and no trace now records a {@link
	 * RelationEvidenceReport.Skipped} entry with its own reason, and a successfully offered child records a {@link
	 * RelationEvidenceReport.Offered} entry - see {@link #suggest} for where the report is logged/stored.
	 */
	private static MatchPlanLlmSuggester.RelationChildrenResult buildRelationChildren(final Adaptation pFactory, final Session pSession, final McpClient pClient,
		final String pModelName, final String pMasterDataspaceKey, final String pMasterDatasetName, final Adaptation pMasterDataset,
		final MasterModel.Result pMasterModel, final Map<String, StagingModel.EntityInfo> pStagingByName, final String pEntityName, final boolean pShareValues)
	{
		final List<MatchPlanLlmPass.RelationChildInfo> out = new ArrayList<MatchPlanLlmPass.RelationChildInfo>();
		final List<MatchPlanLlmSuggester.RelationEvidenceReport.Offered> offered = new ArrayList<MatchPlanLlmSuggester.RelationEvidenceReport.Offered>();
		final List<MatchPlanLlmSuggester.RelationEvidenceReport.Skipped> skipped = new ArrayList<MatchPlanLlmSuggester.RelationEvidenceReport.Skipped>();
		final LlmDataPolicy dataPolicy = LlmDataPolicy.of(pFactory);
		final Set<String> childEntityNames = new LinkedHashSet<String>();
		for (final MasterModel.ForeignKeyInfo foreignKey : pMasterModel.foreignKeys)
		{
			if (pEntityName.equals(foreignKey.targetEntityName) && !pEntityName.equals(foreignKey.entityName))
			{
				childEntityNames.add(foreignKey.entityName);
			}
		}
		for (final String childEntityName : childEntityNames)
		{
			final StagingModel.EntityInfo childStagingEntity = pStagingByName.get(childEntityName);
			if (childStagingEntity == null)
			{
				skipped.add(new MatchPlanLlmSuggester.RelationEvidenceReport.Skipped(childEntityName, "no staging entity found for this child"));
				continue;
			}
			// the child's own FK field targeting pEntityName - MasterModel.ForeignKeyInfo#fieldName, re-scanned per
			// child (a child could, in principle, hold more than one FK to different targets; the first one that
			// targets pEntityName is used - the same "first match wins" discipline this codebase's own resolveTarget
			// helpers already apply elsewhere).
			String fkFieldName = null;
			for (final MasterModel.ForeignKeyInfo foreignKey : pMasterModel.foreignKeys)
			{
				if (childEntityName.equals(foreignKey.entityName) && pEntityName.equals(foreignKey.targetEntityName))
				{
					fkFieldName = foreignKey.fieldName;
					break;
				}
			}
			if (fkFieldName == null)
			{
				skipped.add(new MatchPlanLlmSuggester.RelationEvidenceReport.Skipped(childEntityName,
					"no foreign key field on the child's own master table targets this parent"));
				continue;
			}
			final String childTablePath = MasterModel.tablePath(childEntityName);
			final JsonNode childProfileData;
			try
			{
				final ObjectNode profileArgs = MatchPlanLlmSuggester.MAPPER.createObjectNode();
				profileArgs.put("table", childTablePath);
				profileArgs.put("dataspace", pMasterDataspaceKey);
				profileArgs.put("dataset", pMasterDatasetName);
				final McpClient.ToolResult profileResult = pClient.callTool("profile_table_for_matching", profileArgs);
				if (profileResult.error || profileResult.json == null)
				{
					skipped.add(new MatchPlanLlmSuggester.RelationEvidenceReport.Skipped(childEntityName,
						"profile_table_for_matching call failed: " + MatchPlanProfileReader.shortText(profileResult.text)));
					continue;
				}
				if (!profileResult.json.path("success").asBoolean(false))
				{
					skipped.add(new MatchPlanLlmSuggester.RelationEvidenceReport.Skipped(childEntityName,
						"profile_table_for_matching answered " + MatchPlanProfileReader.describeSoftMiss(profileResult.json)));
					continue;
				}
				childProfileData = profileResult.json.path("data");
			}
			catch (final McpException ex)
			{
				skipped.add(new MatchPlanLlmSuggester.RelationEvidenceReport.Skipped(childEntityName,
					"profile_table_for_matching MCP call failed: " + ex.getMessage()));
				continue;
			}
			final Map<String, JsonNode> profileByField = MatchPlanProfileReader.fieldsByName(childProfileData, MatchPlanLlmSuggester.TECHNICAL_FIELDS);
			final List<MatchPlanLlmPass.AttributeInfo> childAttributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
			for (final StagingModel.AttributeInfo attribute : childStagingEntity.attributes)
			{
				if (attribute.primaryKey || !TechnicalKeyRule.isAcceptedLike(attribute.status) || attribute.referenceEntity != null)
				{
					continue; // a relation field is never itself a REFERENCE (§3 guard); never the FK back to the parent either
				}
				final JsonNode fieldProfile = profileByField.get(attribute.name);
				final MatchPlanProfileReader.FieldStats stats = fieldProfile == null ? null : MatchPlanProfileReader.readFieldStats(fieldProfile);
				final String role = MatchPlanLlmPass.deriveRole(null, false, Collections.<Boolean>emptyList());
				if (MatchPlanLlmPass.AttributeInfo.ROLE_SOURCE_KEY.equals(role))
				{
					continue;
				}
				final boolean shareThis = pShareValues && !dataPolicy.isSensitive(attribute.name) && !dataPolicy.isSensitive(attribute.label);
				childAttributes.add(new MatchPlanLlmPass.AttributeInfo(attribute.name, attribute.label, attribute.dataType, attribute.maxLength, null,
					stats == null ? null : stats.fillRate, stats == null ? null : stats.fillRateProvenance, stats == null ? null : stats.distinctCount,
					stats == null ? null : stats.cardinalityProvenance, shareThis && stats != null ? stats.mostFrequentValue : null,
					stats == null ? null : stats.mostFrequentValueCount, shareThis && stats != null ? stats.mostFrequentToken : null,
					stats == null ? null : stats.mostFrequentTokenDocFrequency, true, role, Collections.<String>emptyList(), Collections.<String>emptyList()));
			}
			if (childAttributes.isEmpty())
			{
				skipped.add(new MatchPlanLlmSuggester.RelationEvidenceReport.Skipped(childEntityName,
					"no usable (accepted, non-technical, non-reference) attribute on the child"));
				continue;
			}
			final RelationRowCountMeasures.Stats rowStats = pMasterDataset == null ? RelationRowCountMeasures.Stats.notMeasured()
				: RelationRowCountMeasures.childRowCountStats(pSession, pMasterDataset, childEntityName, fkFieldName, pEntityName);
			out.add(new MatchPlanLlmPass.RelationChildInfo(childEntityName, childAttributes, rowStats.measured ? Integer.valueOf(rowStats.minPerParent) : null,
				rowStats.measured ? Integer.valueOf(rowStats.maxPerParent) : null, rowStats.measured ? Double.valueOf(rowStats.avgPerParent) : null,
				rowStats.measured ? Double.valueOf(rowStats.shareOfParentsWithNone) : null));
			offered.add(new MatchPlanLlmSuggester.RelationEvidenceReport.Offered(childEntityName, fkFieldName, childAttributes.size(), rowStats.measured));
		}
		return new MatchPlanLlmSuggester.RelationChildrenResult(out, new MatchPlanLlmSuggester.RelationEvidenceReport(pEntityName, offered, skipped));
	}

	/**
	 * One human-readable line (no {@code [mdm-factory]} prefix - {@link #suggest} adds that) summarising {@code
	 * pReport} - what {@link #suggest} logs to {@code kernel.log} once per parent entity that had at least one
	 * candidate child. Pure (no EBX/MCP access), unit-tested directly (relation-evidence-required lot R3,
	 * 2026-09-26, `docs/MATCHING.md` &sect;34).
	 */
	static String relationEvidenceLogLine(final MatchPlanLlmSuggester.RelationEvidenceReport pReport)
	{
		final StringBuilder out = new StringBuilder("relation evidence for '").append(pReport.entityName).append("': ");
		if (pReport.offered.isEmpty())
		{
			out.append("no child offered");
		}
		else
		{
			out.append("offered ");
			for (int i = 0; i < pReport.offered.size(); i++)
			{
				final MatchPlanLlmSuggester.RelationEvidenceReport.Offered child = pReport.offered.get(i);
				out.append(i == 0 ? "" : ", ").append(child.childEntity).append(" (fkField=").append(child.fkField).append(", attributes=")
					.append(child.attributeCount).append(", rowStats=").append(child.rowStatsMeasured ? "measured" : "not measured").append(')');
			}
		}
		if (!pReport.skipped.isEmpty())
		{
			out.append("; skipped ");
			for (int i = 0; i < pReport.skipped.size(); i++)
			{
				final MatchPlanLlmSuggester.RelationEvidenceReport.Skipped child = pReport.skipped.get(i);
				out.append(i == 0 ? "" : ", ").append(child.childEntity).append(" (").append(child.reason).append(')');
			}
		}
		return out.toString();
	}

	/**
	 * Writes {@code pReports} into {@code pOut} under {@code "relationEvidence"}, keyed by parent entity name -
	 * omitted entirely when {@code pReports} is empty (same "never an empty key" discipline as {@link
	 * #putStrings}). Pure JSON assembly, unit-tested directly (relation-evidence-required lot R3, 2026-09-26,
	 * `docs/MATCHING.md` &sect;34).
	 */
	static void writeRelationEvidence(final ObjectNode pOut, final List<MatchPlanLlmSuggester.RelationEvidenceReport> pReports)
	{
		if (pReports.isEmpty())
		{
			return;
		}
		final ObjectNode byEntity = pOut.putObject("relationEvidence");
		for (final MatchPlanLlmSuggester.RelationEvidenceReport report : pReports)
		{
			final ObjectNode entityNode = byEntity.putObject(report.entityName);
			final ArrayNode offeredNode = entityNode.putArray("offered");
			for (final MatchPlanLlmSuggester.RelationEvidenceReport.Offered child : report.offered)
			{
				final ObjectNode childNode = offeredNode.addObject();
				childNode.put("childEntity", child.childEntity);
				childNode.put("fkField", child.fkField);
				childNode.put("attributeCount", child.attributeCount);
				childNode.put("rowStatsMeasured", child.rowStatsMeasured);
			}
			final ArrayNode skippedNode = entityNode.putArray("skipped");
			for (final MatchPlanLlmSuggester.RelationEvidenceReport.Skipped child : report.skipped)
			{
				final ObjectNode childNode = skippedNode.addObject();
				childNode.put("childEntity", child.childEntity);
				childNode.put("reason", child.reason);
			}
		}
	}

	/**
	 * <b>Coordinator fix, 2026-09-22 21:36 (agent run 29, project Companies) - replaces the 18:20 fix, which
	 * read the wrong field.</b> The field this ENTITY uses to recognise "same real-world thing" across systems is
	 * its own MATCHING key - the derived {@code <attr>Key} (FOLD_KEY) field of the entity's fuzzy ANCHOR
	 * attribute in the plan (e.g. {@code nameKey} for {@code Company}, the derived key of the {@code name} field
	 * the plan actually compares with a fuzzy algorithm) - <b>never {@code
	 * MatchPlan.requirements.clusteringFields[0]}</b>, which the 18:20 fix wrongly used: {@code clusteringFields}
	 * is a BLOCKING key (candidate pairs are only ever compared when they share a bucket on it), not an identity
	 * signal - on {@code Company} it is {@code country}, and grouping by it made {@code name} read as hugely
	 * divergent (many {@code nameKey} values share one country, of course) and {@code country} read as having
	 * zero divergence (a field never disagrees with its own grouping key) - both artefacts of the wrong key, not
	 * real findings (run 29's own LLM rationale quoted both, verbatim, as if they were genuine measurements).
	 * <p>
	 * The precedence, in order, NEVER falling back to a clustering field at any step:
	 * <ol>
	 * <li>the derived key ({@link #readDerivedFromByName}'s inverse, {@code pMatchKeyByName}) of the field the
	 * prior plan's decision tree compares with a non-{@link #EXACT_ALGORITHM_LITERAL} (fuzzy) algorithm - the
	 * plan's own fuzzy anchor - skipping a candidate field that is itself a derived key ({@code
	 * MatchPlanLlmPass.deriveRole}'s {@code ROLE_MATCH_KEY}: comparing a key against itself proves nothing) or a
	 * {@code REFERENCE} attribute (a foreign key is never this entity's own identity, {@code deriveRole}'s
	 * {@code ROLE_REFERENCE});</li>
	 * <li>else the first of the entity's own accepted attributes (in staging order) that has a derived key at
	 * all, whether or not the prior plan even proposed a fuzzy tier for it yet;</li>
	 * <li>else, if the prior plan's {@code root} node is a single-field {@code ALL_FIELDS} node using the exact
	 * algorithm (a real, validated single-field identifier - e.g. a registration number, never a folded/derived
	 * value), that field itself;</li>
	 * <li>else {@code null} - "this entity has no identity key yet". {@code toLlmEntity} then never computes
	 * {@code divergingGroupsCount} for any attribute, and {@code userPrompt} says so explicitly instead of
	 * showing a fabricated number (an entity with a raw, un-folded identity such as {@code Contact}'s
	 * {@code firstName}/{@code lastName} - single source, so never even reached - is the ordinary case this
	 * covers).</li>
	 * </ol>
	 * Re-measured live, same day, with the corrected key ({@code nameKey}, 33 shared groups): {@code cityKey}
	 * diverges in 0 of them (genuinely no disagreement, once folded), while the raw {@code city} spelling
	 * differs in 32 of 33 (cosmetic only, {@code ebx.CrossSystemMeasures#cosmeticVariantGroupsCount}) - the same
	 * live numbers the 18:20 fix already reported, this time reached by construction rather than by the
	 * coincidence that {@code nameKey} used to also be {@code clusteringFields[0]} on some other prior plan
	 * shape.
	 * <p>
	 * Split into a thin EBX-reading wrapper (this method) and a pure decision ({@link #chooseIdentityKeyField})
	 * so the precedence itself is unit-testable without an {@code Adaptation} - same discipline {@link
	 * #filterToEntity} already established for the {@code ?entity=} filter.
	 */
	private static String entityIdentityKeyField(final Adaptation pFactory, final String pEntityId, final List<StagingModel.AttributeInfo> pAcceptedAttributes,
		final Map<String, String> pDerivedFromByName, final Map<String, String> pMatchKeyByName)
	{
		final Adaptation plan = MatchPlanLlmSuggester.findMatchPlan(pFactory, pEntityId);
		final String requirementsJson = plan == null ? null : plan.getString(FactoryPaths.MatchPlan.REQUIREMENTS);
		return MatchPlanLlmSuggester.chooseIdentityKeyField(requirementsJson, pAcceptedAttributes, pDerivedFromByName, pMatchKeyByName);
	}

	/**
	 * The pure precedence behind {@link #entityIdentityKeyField} - see that method's own Javadoc for the full
	 * rule and the 2026-09-22 21:36 (run 29) history of why it must never fall back to a clustering field.
	 * {@code pRequirementsJson} is the entity's prior {@code MatchPlan.requirements}, or null/blank for a
	 * brand-new entity with no prior plan. {@code pAcceptedAttributes} must already be filtered to this entity's
	 * own accepted (non-primary-key) attributes, in staging order - the same {@code accepted} list {@code
	 * toLlmEntity} builds before calling this. {@code pDerivedFromByName} (derived-attribute-name -&gt;
	 * source-attribute-name) and {@code pMatchKeyByName} (source-attribute-name -&gt; derived-attribute-name) are
	 * {@link #readDerivedFromByName}'s own map and its exact inverse, as {@code toLlmEntity} already builds them.
	 */
	static String chooseIdentityKeyField(final String pRequirementsJson, final List<StagingModel.AttributeInfo> pAcceptedAttributes,
		final Map<String, String> pDerivedFromByName, final Map<String, String> pMatchKeyByName)
	{
		final JsonNode requirements = MatchPlanLlmSuggester.parseRequirementsQuietly(pRequirementsJson);

		// (1) the plan's own fuzzy anchor's derived key, when the anchor field exists AND has one
		if (requirements != null)
		{
			final String anchorField = MatchPlanLlmSuggester.firstFuzzyAnchorField(requirements, pDerivedFromByName, pAcceptedAttributes);
			if (anchorField != null)
			{
				final String anchorKey = pMatchKeyByName.get(anchorField);
				if (anchorKey != null)
				{
					return anchorKey;
				}
			}
		}

		// (2) else the first accepted attribute (staging order) that has a derived key at all - independent of
		// whether the prior plan even proposed a fuzzy tier for it yet. Never a REFERENCE attribute (a foreign
		// key is never this entity's own identity), same guard as the anchor lookup above.
		for (final StagingModel.AttributeInfo attribute : pAcceptedAttributes)
		{
			if (attribute.referenceEntity == null && pMatchKeyByName.containsKey(attribute.name))
			{
				return pMatchKeyByName.get(attribute.name);
			}
		}

		// (3) else the prior plan's own EXACT, single-field identifier (an ALL_FIELDS root comparing exactly one
		// field with the exact algorithm) - a real validated identifier, used as-is, never a derived key of it.
		if (requirements != null)
		{
			final String exactIdentifier = MatchPlanLlmSuggester.exactIdentifierRootField(requirements);
			if (exactIdentifier != null)
			{
				return exactIdentifier;
			}
		}

		// (4) "this entity has no identity key yet" - NEVER a fallback to clusteringFields (a blocking key
		// answers nothing about identity - see this method's own Javadoc).
		return null;
	}

	/** {@code pRequirementsJson} parsed to a {@link JsonNode}, or null on a blank/unparsable document - the same "not this method's problem to fix" degrade-to-null discipline every reader in this class follows. Deliberately broad catch: Jackson's own checked {@code IOException} plus any {@code RuntimeException} a malformed document could still trigger. */
	private static JsonNode parseRequirementsQuietly(final String pRequirementsJson)
	{
		if (pRequirementsJson == null || pRequirementsJson.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return MatchPlanLlmSuggester.MAPPER.readTree(pRequirementsJson);
		}
		catch (final Exception ex)
		{
			return null;
		}
	}

	/**
	 * The name of the first field, among every node's {@code fields[]} of {@code pRequirements.nodes[]} (array
	 * order - a plan normally has exactly one fuzzy field, so any deterministic order finds it), compared with a
	 * non-{@link #EXACT_ALGORITHM_LITERAL} algorithm - the plan's fuzzy ANCHOR. Skips a field that is itself a
	 * derived key ({@code pDerivedFromByName.containsKey(name)} - {@code MatchPlanLlmPass.deriveRole}'s {@code
	 * ROLE_MATCH_KEY}: a key compared against itself proves nothing about identity) or a {@code REFERENCE}
	 * attribute ({@code deriveRole}'s {@code ROLE_REFERENCE}, found via {@code pAcceptedAttributes}: a foreign
	 * key is never this entity's own identity). Returns null when {@code nodes} is missing/not an array, or no
	 * field qualifies.
	 */
	private static String firstFuzzyAnchorField(final JsonNode pRequirements, final Map<String, String> pDerivedFromByName,
		final List<StagingModel.AttributeInfo> pAcceptedAttributes)
	{
		final JsonNode nodes = pRequirements.path("nodes");
		if (!nodes.isArray())
		{
			return null;
		}
		for (final JsonNode node : nodes)
		{
			final JsonNode fields = node.path("fields");
			if (!fields.isArray())
			{
				continue;
			}
			for (final JsonNode field : fields)
			{
				final String fieldName = field.path("field").asText(null);
				final String algorithm = field.path("algorithm").asText(null);
				if (fieldName == null || algorithm == null || MatchPlanLlmSuggester.EXACT_ALGORITHM_LITERAL.equals(algorithm))
				{
					continue;
				}
				if (pDerivedFromByName.containsKey(fieldName))
				{
					continue;
				}
				final StagingModel.AttributeInfo attribute = MatchPlanLlmSuggester.attributeByName(pAcceptedAttributes, fieldName);
				if (attribute != null && attribute.referenceEntity != null)
				{
					continue;
				}
				return fieldName;
			}
		}
		return null;
	}

	/**
	 * The single field name of {@code pRequirements}'s own {@code root} node, when that node is an {@link
	 * DecisionTreePlan#FUNCTION_ALL_FIELDS} node comparing EXACTLY ONE field with {@link #EXACT_ALGORITHM_LITERAL}
	 * - a real, validated single-field identifier the prior plan already trusted enough to terminate a MATCH
	 * node on ({@code MatchPlanLlmPass.Session#proposeMatchPlan}'s own MATCH-terminal guard already required
	 * fill/uniqueness checks on it). Returns null when {@code root}/{@code nodes} cannot be resolved, the root
	 * node is not {@code ALL_FIELDS}, it has other than exactly one field, or that field is not exact - this
	 * fallback is deliberately narrow (a multi-field or fuzzy root is not "one clean identifier").
	 */
	private static String exactIdentifierRootField(final JsonNode pRequirements)
	{
		final String rootId = pRequirements.path("root").asText(null);
		final JsonNode nodes = pRequirements.path("nodes");
		if (rootId == null || rootId.trim().isEmpty() || !nodes.isArray())
		{
			return null;
		}
		for (final JsonNode node : nodes)
		{
			if (!rootId.equals(node.path("id").asText(null)))
			{
				continue;
			}
			if (!DecisionTreePlan.FUNCTION_ALL_FIELDS.equals(node.path("function").asText(null)))
			{
				return null;
			}
			final JsonNode fields = node.path("fields");
			if (!fields.isArray() || fields.size() != 1)
			{
				return null;
			}
			final JsonNode field = fields.get(0);
			if (!MatchPlanLlmSuggester.EXACT_ALGORITHM_LITERAL.equals(field.path("algorithm").asText(null)))
			{
				return null;
			}
			final String fieldName = field.path("field").asText(null);
			return fieldName == null || fieldName.trim().isEmpty() ? null : fieldName;
		}
		return null;
	}

	/** First attribute of {@code pAttributes} named {@code pName}, or null - a plain linear lookup, this list is never large enough to warrant an index. */
	private static StagingModel.AttributeInfo attributeByName(final List<StagingModel.AttributeInfo> pAttributes, final String pName)
	{
		for (final StagingModel.AttributeInfo attribute : pAttributes)
		{
			if (attribute.name.equals(pName))
			{
				return attribute;
			}
		}
		return null;
	}

	/**
	 * Attribute name -> the NAME of the source attribute it was derived from ({@code StagingAttribute.derivation}
	 * = {@code FOLD_KEY} only) - the matching-key contract, 2026-09-22, implemented on the model side by a
	 * separate agent (`feature/match-key-attribute`). {@code derivedFrom}/{@code derivation} are read defensively
	 * via this class' own {@link #safeString}, which answers null on a schema that does not
	 * carry these fields yet rather than throwing ({@code Adaptation.get} throws on an undefined path, CLAUDE.md
	 * sec 9) - so this method degrades to an empty map, every attribute reading as "not derived", on a model built
	 * before that other branch merges. A second, separate query over {@code StagingAttribute} (rather than folding
	 * this into {@code readAttributes}' own single pass): this class already reads each entity's attributes more
	 * than once for other reasons (e.g. {@link #attributeId}), and keeping this lookup self-contained avoids
	 * threading a third output parameter through an already-widely-called private reader.
	 * <p>
	 * <b>Coordinator review, 2026-09-22: only an ACCEPTED-or-APPLIED derived (key) row counts as an existing
	 * key.</b> The key attribute row is itself a staging proposal like any other, with its own {@code
	 * StagingAttribute.status} ({@link FactoryPaths.StagingAttribute#STATUS}, read through {@link
	 * TechnicalKeyRule#isAcceptedLike}, the same "ACCEPTED or APPLIED" test every other caller in this codebase
	 * shares). A still-{@code PROPOSED} key is not in the master table yet - substituting it in (or even naming
	 * it in a {@code CrossSystemFieldGuard} refusal hint) would point a reviewer at something that does not
	 * exist there. A {@code REJECTED} key must obviously never be used either. Both are simply excluded here, the
	 * same way {@code toLlmEntity}'s own {@code accepted} filter already excludes a non-accepted SOURCE
	 * attribute from the prompt entirely - so an entity with only a {@code PROPOSED}/{@code REJECTED} key for a
	 * field behaves exactly as if that field had no key at all (the existing zero-shared-values/entirely-empty
	 * refusal stands, unqualified, rather than a misleading "use it instead").
	 */
	static Map<String, String> readDerivedFromByName(final Adaptation pFactory, final String pEntityId)
	{
		final List<Adaptation> rows = new ArrayList<Adaptation>();
		final Map<String, String> nameById = new HashMap<String, String>();
		final RequestResult requestResult = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = requestResult.nextAdaptation(); row != null; row = requestResult.nextAdaptation())
			{
				rows.add(row);
				nameById.put(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.StagingAttribute.NAME));
			}
		}
		finally
		{
			requestResult.close();
		}
		final Map<String, String> out = new HashMap<String, String>();
		for (final Adaptation row : rows)
		{
			if (!"FOLD_KEY".equals(MatchPlanLlmSuggester.safeString(row, "./derivation"))
				|| !TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.StagingAttribute.STATUS)))
			{
				continue;
			}
			final String derivedFromId = MatchPlanLlmSuggester.safeString(row, "./derivedFrom");
			final String sourceName = derivedFromId == null ? null : nameById.get(derivedFromId);
			if (sourceName != null)
			{
				out.put(row.getString(FactoryPaths.StagingAttribute.NAME), sourceName);
			}
		}
		return out;
	}

	/**
	 * Lot M8a: attribute name -> the child entity of its {@code CHILD_KEYS} derivation ({@code child} of {@code StagingAttribute.derivationArgument}), for the ACCEPTED-or-APPLIED
	 * rows of the entity only. An argument that does not parse is left out (the attribute is still offered, without the child's name) - never a failure of the proposal.
	 */
	static Map<String, String> readChildKeysChildByName(final Adaptation pFactory, final String pEntityId)
	{
		final Map<String, String> out = new HashMap<String, String>();
		final RequestResult requestResult = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = requestResult.nextAdaptation(); row != null; row = requestResult.nextAdaptation())
			{
				if (!ChildKeysDerivation.DERIVATION_CHILD_KEYS.equals(MatchPlanLlmSuggester.safeString(row, "./derivation"))
					|| !TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.StagingAttribute.STATUS)))
				{
					continue;
				}
				try
				{
					out.put(row.getString(FactoryPaths.StagingAttribute.NAME),
						ChildKeysDerivation.parse(MatchPlanLlmSuggester.safeString(row, "./derivationArgument")).childEntity);
				}
				catch (final IllegalArgumentException ex)
				{
					// an unparsable definition: offered without its child's name
				}
			}
		}
		finally
		{
			requestResult.close();
		}
		return out;
	}

	/**
	 * {@code Adaptation.get}/{@code getString} throws on a path the table does not define (CLAUDE.md sec 9) - this
	 * class' own guarded read for {@code derivedFrom}/{@code derivation}, which may not exist yet on this branch's
	 * model (the field is added by a separate agent on `feature/match-key-attribute`, merged after this branch).
	 * Same pattern {@code ebx.CatalogReader}'s own (private, not reachable from here) {@code text} helper uses.
	 */
	private static String safeString(final Adaptation pRow, final String pPath)
	{
		final Path path = Path.parse(pPath);
		return pRow.getSchemaNode().getNode(path) == null ? null : pRow.getString(path);
	}

	/** One boolean per ACCEPTED-or-APPLIED {@code AttributeMapping} of this attribute: whether its own {@code LandingColumn.sourceKey} is set - what {@link MatchPlanLlmPass#deriveRole} needs to tell a {@code SOURCE_KEY} attribute apart from an ordinary business one. */
	private static List<Boolean> mappingsAreOnSourceKeyColumns(final Adaptation pFactory, final String pAttributeId)
	{
		final List<Boolean> out = new ArrayList<Boolean>();
		final RequestResult mappingRows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).createRequestResult("./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation mappingRow = mappingRows.nextAdaptation(); mappingRow != null; mappingRow = mappingRows.nextAdaptation())
			{
				if (!TechnicalKeyRule.isAcceptedLike(mappingRow.getString(FactoryPaths.AttributeMapping.STATUS)))
				{
					continue;
				}
				final String landingColumnId = mappingRow.getString(FactoryPaths.AttributeMapping.LANDING_COLUMN);
				final Adaptation landingColumn = landingColumnId == null ? null
					: pFactory.getTable(FactoryPaths.LANDING_COLUMN).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(landingColumnId));
				out.add(Boolean.valueOf(landingColumn != null && Boolean.TRUE.equals(landingColumn.get(FactoryPaths.LandingColumn.SOURCE_KEY))));
			}
		}
		finally
		{
			mappingRows.close();
		}
		return out;
	}

	/** The (different, promoted) parent entity a child points to through an accepted reference attribute, and which attribute names it - see {@link #childLink}. */
	static final class ChildLink
	{
		final String parent;
		final String attributeName;
		/**
		 * 2026-09-26 (docs/MATCHING.md sec 31): the master foreign key field generated for {@link #attributeName}
		 * ({@code MasterModel.ForeignKeyInfo#fieldName}, e.g. {@code personneRef}) - the child's link field, filled
		 * for every feed; null when no foreign key was generated for it.
		 */
		final String linkField;

		ChildLink(final String pParent, final String pAttributeName, final String pLinkField)
		{
			this.parent = pParent;
			this.attributeName = pAttributeName;
			this.linkField = pLinkField;
		}
	}

	/**
	 * @return the parent entity (and the attribute naming it) a child points to through an accepted reference
	 * attribute, or null when this entity is not a child. Resolves every reference through {@link
	 * MasterModel.Result#resolveReference} - the SAME resolution {@link MasterModel#buildTable} itself
	 * applies when it decides whether to lower a foreign key - rather than checking {@code pMasterModel
	 * .builtEntities.contains(...)} directly: a reference to a MEMBER entity (this project's own {@code
	 * Contact.accountId -> Account}, where {@code Account} is a member of the canonical {@code Company}, not
	 * independently promoted) must resolve to its canonical entity, or it silently resolves to nothing and
	 * {@code Contact} is wrongly treated as a plain, non-child entity - a real defect, found on this
	 * project's first live run of {@code matching/suggest-llm} (coordinator review, 2026-09-21): the earlier
	 * revision of this method checked promotion directly and had exactly that hole. Self-reference is
	 * checked here, not inside {@code resolveReference} itself - see that method's own Javadoc for why.
	 * <p>
	 * <b>2026-09-22, "child entities matched too":</b> renamed from {@code childOf} (which returned only the
	 * parent's name) - being a child no longer excludes this entity from {@link #suggest}'s own eligible set
	 * (see class Javadoc), so the only remaining uses of this result are informational: {@link #toLlmEntity}'s
	 * own {@code EntityInfo.childOfParent}/{@code childOfAttribute} annotation, and the response's own {@code
	 * childEntities} note list.
	 */
	private static ChildLink childLink(final StagingModel.EntityInfo pEntity, final MasterModel.Result pMasterModel)
	{
		for (final StagingModel.AttributeInfo attribute : pEntity.attributes)
		{
			if (!TechnicalKeyRule.isAcceptedLike(attribute.status) || attribute.referenceEntity == null)
			{
				continue;
			}
			final String resolved = pMasterModel.resolveReference(attribute.referenceEntity);
			if (resolved != null && !resolved.equals(pEntity.name))
			{
				String linkField = null;
				for (final MasterModel.ForeignKeyInfo fk : pMasterModel.foreignKeys)
				{
					if (fk.entityName.equals(pEntity.name) && fk.attributeName.equals(attribute.name))
					{
						linkField = fk.fieldName;
					}
				}
				return new ChildLink(resolved, attribute.name, linkField);
			}
		}
		return null;
	}

	private static String attributeId(final Adaptation pFactory, final String pEntityId, final String pAttributeName)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE)
			.createRequestResult("./entity=" + CatalogReader.quote(pEntityId) + " and ./name=" + CatalogReader.quote(pAttributeName));
		try
		{
			final Adaptation row = rows.nextAdaptation();
			return row == null ? null : row.getOccurrencePrimaryKey().format();
		}
		finally
		{
			rows.close();
		}
	}

	/** {@code kind} (+ {@code argument} when present) of every ACCEPTED-or-APPLIED {@code MappingStep} of every {@code AttributeMapping} of this attribute - metadata only, never a value. */
	private static List<String> cleaningSteps(final Adaptation pFactory, final String pAttributeId)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult mappingRows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).createRequestResult("./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation mappingRow = mappingRows.nextAdaptation(); mappingRow != null; mappingRow = mappingRows.nextAdaptation())
			{
				final String mappingId = mappingRow.getOccurrencePrimaryKey().format();
				final RequestResult stepRows = pFactory.getTable(FactoryPaths.MAPPING_STEP).createRequestResult("./mapping=" + CatalogReader.quote(mappingId));
				try
				{
					for (Adaptation stepRow = stepRows.nextAdaptation(); stepRow != null; stepRow = stepRows.nextAdaptation())
					{
						if (TechnicalKeyRule.isAcceptedLike(stepRow.getString(FactoryPaths.MappingStep.STATUS)))
						{
							MatchPlanLlmSuggester.addEvidence(out, stepRow.getString(FactoryPaths.MappingStep.KIND), stepRow.getString(FactoryPaths.MappingStep.ARGUMENT));
						}
					}
				}
				finally
				{
					stepRows.close();
				}
			}
		}
		finally
		{
			mappingRows.close();
		}
		return out;
	}

	/** {@code kind} (+ {@code argument} when present) of every ACCEPTED-or-APPLIED {@code QualityRule} of this attribute - metadata only, never a value. */
	private static List<String> qualityRules(final Adaptation pFactory, final String pAttributeId)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.QUALITY_RULE).createRequestResult("./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.QualityRule.STATUS)))
				{
					MatchPlanLlmSuggester.addEvidence(out, row.getString(FactoryPaths.QualityRule.KIND), row.getString(FactoryPaths.QualityRule.ARGUMENT));
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static void addEvidence(final List<String> pOut, final String pKind, final String pArgument)
	{
		if (pKind == null || pKind.trim().isEmpty())
		{
			return;
		}
		final String entry = pArgument == null || pArgument.trim().isEmpty() ? pKind : pKind + " (" + pArgument + ")";
		if (!pOut.contains(entry))
		{
			pOut.add(entry);
		}
	}

	/**
	 * Opens the project's master dataset for the {@code ebx.CrossSystemMeasures} SQL measurements only -
	 * profiling itself never touches this {@link Adaptation} directly, only the {@code match-merge} MCP
	 * server (class Javadoc). Same {@code Repository.lookupHome}/{@code AdaptationHome.findAdaptationOrNull}
	 * shape {@code ebx.MasterLoader#load} already uses to open the very same dataset - returns null (never
	 * throws) when either the home or the dataset cannot be found, since a missing master dataset is not this
	 * route's own error to raise: {@link #suggest} already fails earlier, with a clearer message, when the
	 * project has no master zone at all - this is only reached once that check has already passed, so null
	 * here would mean the dataset disappeared between that check and this call, and the honest response is
	 * simply "not measured" for every entity (this method's own null feeds {@link #toLlmEntity}, which treats
	 * a null master dataset exactly like any other measurement failure), not a thrown error.
	 */
	static Adaptation openMasterDataset(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName)
	{
		final AdaptationHome masterHome = pRepository.lookupHome(HomeKey.forBranchName(pMasterDataspaceKey));
		return masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(pMasterDatasetName));
	}

	static Adaptation findMatchPlan(final Adaptation pFactory, final String pEntityId)
	{
		if (pEntityId == null)
		{
			return null;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.MATCH_PLAN).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	/** Same shape {@code MatchPlanSuggester.readEntities} already reads - a separate copy per this codebase's own convention (each caller keeps its own private reader). */
	private static List<StagingModel.EntityInfo> readEntities(final Adaptation pFactory, final String pProjectId, final Map<String, String> pEntityIdByName)
	{
		final List<Adaptation> entityRowsList = new ArrayList<Adaptation>();
		final Map<String, String> entityIdToName = new HashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				entityRowsList.add(row);
				final String id = row.getOccurrencePrimaryKey().format();
				final String name = row.getString(FactoryPaths.StagingEntity.NAME);
				entityIdToName.put(id, name);
				pEntityIdByName.put(name, id);
			}
		}
		finally
		{
			entityRows.close();
		}

		final List<StagingModel.EntityInfo> out = new ArrayList<StagingModel.EntityInfo>();
		for (final Adaptation row : entityRowsList)
		{
			final String entityId = row.getOccurrencePrimaryKey().format();
			final List<String> memberNames = new ArrayList<String>();
			final List<?> rawMemberIds = row.getList(FactoryPaths.StagingEntity.MEMBER_ENTITIES);
			if (rawMemberIds != null)
			{
				for (final Object rawMemberId : rawMemberIds)
				{
					final String memberName = entityIdToName.get(String.valueOf(rawMemberId));
					if (memberName != null)
					{
						memberNames.add(memberName);
					}
				}
			}
			out.add(new StagingModel.EntityInfo(row.getString(FactoryPaths.StagingEntity.NAME), row.getString(FactoryPaths.StagingEntity.LABEL),
				row.getString(FactoryPaths.StagingEntity.STATUS), MatchPlanLlmSuggester.readAttributes(pFactory, entityId, entityIdToName), memberNames));
		}
		return out;
	}

	private static List<StagingModel.AttributeInfo> readAttributes(final Adaptation pFactory, final String pEntityId, final Map<String, String> pEntityIdToName)
	{
		final List<StagingModel.AttributeInfo> out = new ArrayList<StagingModel.AttributeInfo>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object rawMaxLength = row.get(FactoryPaths.StagingAttribute.MAX_LENGTH);
				final Integer maxLength = rawMaxLength instanceof Number ? Integer.valueOf(((Number) rawMaxLength).intValue()) : null;
				final boolean mandatory = Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.MANDATORY));
				final boolean primaryKey = Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY));
				final String referenceEntityId = row.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
				final String referenceEntityName = referenceEntityId == null ? null : pEntityIdToName.get(referenceEntityId);
				out.add(new StagingModel.AttributeInfo(row.getString(FactoryPaths.StagingAttribute.NAME), row.getString(FactoryPaths.StagingAttribute.LABEL),
					row.getString(FactoryPaths.StagingAttribute.DATA_TYPE), maxLength, mandatory, primaryKey, referenceEntityName,
					row.getString(FactoryPaths.StagingAttribute.STATUS), MatchPlanLlmSuggester.safeString(row, "./derivation")));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

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

	private static void putStrings(final ObjectNode pOut, final String pKey, final List<String> pValues)
	{
		final ArrayNode array = pOut.putArray(pKey);
		for (final String value : pValues)
		{
			array.add(value);
		}
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	/** Reject/repropose lot (2026-09-22): the {@code ?entity=} filter's own answer - either the (possibly narrowed) list of promoted entity names to run over, or a clear refusal reason (never both). */
	static final class EntityFilterResult
	{
		final List<String> entities;
		/** {@code "{project}"} is a placeholder the caller substitutes with the real project code - kept out of this pure method so it stays free of any project-specific string. Null when {@link #entities} is the real answer. */
		final String refusalReason;

		private EntityFilterResult(final List<String> pEntities, final String pRefusalReason)
		{
			this.entities = pEntities;
			this.refusalReason = pRefusalReason;
		}
	}

	/**
	 * Reject/repropose lot (2026-09-22): pure decision behind the {@code ?entity=} filter, extracted out of
	 * {@link #suggest} so it can be unit-tested without EBX (see {@code MatchPlanLlmSuggesterFilterTest}). A
	 * null/blank {@code pEntityFilter} is the ordinary sweep - every promoted entity, unchanged.
	 * Otherwise: the ONE requested entity if it is promoted, or a refusal naming it as not eligible.
	 * <p>
	 * <b>2026-09-22, simplified ("child entities matched too"):</b> a child entity is now a normal promoted
	 * entity - the earlier "it's a child entity" refusal (naming its parent) is gone, since that is no longer a
	 * reason to refuse the filter at all; the only remaining refusal is "not a promoted entity of this project"
	 * (unknown name, or promoted-but-a-member-of-a-canonical-entity, which never reaches {@code pPromoted}).
	 */
	static EntityFilterResult filterToEntity(final List<String> pPromoted, final String pEntityFilter)
	{
		if (MatchPlanLlmSuggester.blank(pEntityFilter))
		{
			return new EntityFilterResult(new ArrayList<String>(pPromoted), null);
		}
		final String trimmed = pEntityFilter.trim();
		if (!pPromoted.contains(trimmed))
		{
			return new EntityFilterResult(null, "Entity '" + trimmed + "' is not a promoted entity of project {project} eligible for a matching plan.");
		}
		return new EntityFilterResult(Collections.singletonList(trimmed), null);
	}

	/**
	 * Automerge-toggle lot (2026-09-23), task 2: the pure rule behind {@link #write}'s own {@code AUTO_MERGE}
	 * write - extracted so it is unit-tested without EBX (see {@code MatchPlanLlmSuggesterAutoMergeTest}), same
	 * discipline as {@link #filterToEntity} above. A brand-new row NEVER inherits {@code true} (it is a human
	 * decision, never proposed by a model or a rule - {@code docs/MATCHING.md} &sect;3.4's own Javadoc quote,
	 * "autoMerge and the MATCH outcome"): {@code pIsNewRow} always wins. An EXISTING row keeps whatever a person
	 * may have set from the review screen's own toggle (task 1, {@code ebx.ProposalReviews
	 * #setMatchPlanAutoMerge}) - this pass must never silently turn it back off just because it is rewriting the
	 * plan's requirements/rationale/evidence.
	 */
	static boolean autoMergeForWrite(final boolean pIsNewRow, final boolean pExistingAutoMerge)
	{
		return !pIsNewRow && pExistingAutoMerge;
	}

	private static String join(final List<String> pValues)
	{
		if (pValues.isEmpty())
		{
			return "(none)";
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pValues.size(); i++)
		{
			if (i > 0)
			{
				out.append("; ");
			}
			out.append(pValues.get(i));
		}
		return out.toString();
	}

}
