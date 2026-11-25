package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
import com.on.ebx.factory.capability.CapabilityLlmPass;
import com.on.ebx.factory.capability.DerivationEvidence;
import com.on.ebx.factory.capability.MissingInputs;
import com.on.ebx.factory.capability.NameKeyRule;
import com.on.ebx.factory.capability.NamePartsPass;
import com.on.ebx.factory.capability.ScriptInventory;
import com.on.ebx.factory.capability.ServiceInventory;
import com.on.ebx.factory.capability.ValueEvidence;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.StoredJsonFit;
import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.quality.ScriptDerivation;
import com.on.ebx.factory.quality.Shapes;
import com.on.ebx.factory.staging.MappingScope;
import com.on.ebx.factory.staging.NormalisationProfile;
import com.on.ebx.factory.staging.NormalisationPass;
import com.on.ebx.factory.staging.NormalisationProfiler;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Runs {@link CapabilityLlmPass} - the language-model proposer of script constraints and external-service
 * suggestions (product owner's request, task brief 2026-09-21) - over a project's ACCEPTED-or-APPLIED
 * staging entities: <b>one agent run PER entity</b>, because the first real run of a similarly-shaped pass
 * ({@code staging.StagingLlmPass}, `PLAN.md` 2026-09-20) showed a model stops proposing after the first
 * entity when handed the whole project at once. One entity's own failure (an {@code OperationException}
 * from {@code AgentRuntime.run}, or an inventory-build problem specific to that call) is caught and recorded
 * in that entity's own block of the response; it never aborts the other entities' runs. Route
 * {@code POST /projects/{code}/capabilities/suggest-llm[?entity=<name>][&maxPromptChars=<n>]}.
 * <p>
 * <b>Two COMPACT catalogues, built ONCE per route call (not per entity, coordinator, 2026-09-21)</b> and
 * shared by every entity's {@link CapabilityLlmPass.Session}: {@link #buildScriptInventory} (the {@code
 * scripting} MCP server - {@code list_scripts} walked recursively under the {@code library} category, then
 * {@code get_script(includeSource: true)} per script, exported functions parsed by {@link
 * ScriptInventory#parseExportedFunctions} - unchanged from before) grouped into {@link
 * CapabilityLlmPass.LibrarySummary} lines, and {@link #buildServiceCatalogue} (the {@code ebx-connectors} MCP
 * server - {@code connectors_list} ONLY, never {@code connectors_describe}: that call is now LAZY, made only
 * when the model itself asks {@link CapabilityLlmPass.Session#getServiceDetails} for one connector - see the
 * {@link CapabilityLlmPass.ConnectorDescriber} this class builds and hands to every {@link
 * CapabilityLlmPass.Session}). This is the fix for a REAL run (deployed, {@code gpt-4o}, 2026-09-21) that
 * produced nothing: 321 functions + 38 operations dumped into every prompt built a ~30k-token first request,
 * refused outright by the provider's own tokens-per-minute limit (HTTP 429) - and would have been paid again,
 * in full, on every turn. Both catalogues degrade to empty, with a note explaining why, rather than aborting
 * the whole route: the scripting server is OPTIONAL ({@code McpServers.OPTIONAL}) and the task brief is
 * explicit that "the factory must degrade cleanly if scripting or Connectors is missing".
 * <p>
 * <b>A connector whose configuration is invalid is not skipped or hidden - {@code connectors_describe}
 * refuses it, and that refusal becomes the suggestion's own readiness detail</b> (coordinator, 2026-09-21):
 * {@link #describerFor} wraps the live call so a "configuration is invalid" (or any other) failure becomes
 * {@link CapabilityLlmPass.DescribeOutcome#notConfigured}, never a hard error - the connector stays
 * suggestable. There is no more alphabetical cap on how many connectors get described (the earlier cap
 * constant spent itself on unconfigured connectors early in the alphabet and never reached real ones later in
 * it, e.g. VIES/OpenSanctions/Sirene/Nominatim) - describing is lazy and per-connector now, so there is
 * nothing left to cap upfront.
 * <p>
 * <b>A request too large for the provider is refused LOCALLY, before the call, with a message that survives
 * intact</b> (item 4, coordinator: the provider's own 429 body is shortened to 300 characters by the shared
 * {@code on-ebx-agent} library - out of this repository's scope to change - so relying on it for diagnosis
 * loses the useful part). {@link #suggest} estimates chars/4 as a token count before every entity's LLM call
 * and refuses with a full, untruncated message naming the real character/token counts when the ceiling
 * ({@link #DEFAULT_MAX_PROMPT_CHARS}, or the {@code maxPromptChars} query parameter - no schema change) is
 * exceeded.
 * <p>
 * <b>{@code ok} reflects the REAL outcome</b> (item 3, coordinator: a run that ended {@code FAILED}/{@code
 * BUDGET} was still reported {@code "ok":true} before this fix): an entity's block is {@code ok:true} only
 * when its agent run ended {@link AgentLoop.Status#DONE}; the route's own {@code PipelineStep} is {@code
 * DONE} only if at least one entity's run genuinely finished that way.
 * <p>
 * <b>"Promoted" is computed the same way {@code ebx.MatchPlanLlmSuggester} computes it</b> - {@code
 * master.MasterModel.buildFromStaging(...).builtEntities} - never re-derived here, so a
 * {@code CapabilitySuggestion.zone = MASTER} is only ever offered for an entity that genuinely reaches the
 * master zone.
 * <p>
 * <b>Never a real value, whatever the project's own setting</b>: attribute names/types/lengths, the two
 * catalogues, what is already proposed, and - when an attribute has an accepted landing mapping - its EXACT
 * profile numbers (row count, fill rate, distinct count) and value SHAPES, obtained by calling {@code
 * QualitySuggester}'s own package-visible helpers ({@link CapabilityLlmSuggester#toLlmAttribute}) the exact
 * same way {@code QualityLlmSuggester} does for its own pass. Unlike {@code QualityLlmSuggester}/{@code
 * MatchPlanLlmSuggester} this pass has no {@code SAMPLE_VALUES} opt-in of its own: it never shows a real value
 * under any circumstance, only shapes and counts. The shapes themselves follow the data policy ({@code
 * ebx.LlmDataPolicy}): none under {@code NONE}, none for a column of the sensitive list, whatever the level.
 */
public final class CapabilityLlmSuggester
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	// job-engine lot C3 (2026-09-24): package-visible (not private) so CapabilitySuggestLlmRun can look up the
	// PipelineStep row this class already opens/closes for its own STEP.
	static final String STEP = "CAPABILITY_SUGGEST_LLM";
	private static final String PHASE = "CAPABILITY_SUGGEST_LLM";
	/** Lot M10c: the {@code AgentRun} phase of the closed question behind {@link NameKeyRule} (which attributes are a first and a last name). */
	private static final String NAME_PARTS_PHASE = "CAPABILITY_NAME_PARTS";
	private static final int RATIONALE_MAX_LENGTH = 2000;
	/** {@code CapabilitySuggestion.binding} own {@code maxLen}: the apply step PARSES it, so a binding that does not fit is refused (row not written), never cut. */
	private static final int BINDING_MAX_LENGTH = StoredJsonFit.BINDING_MAX_LENGTH;
	private static final int READINESS_DETAIL_MAX_LENGTH = 255;
	/** Bounds a connector operation's own {@code description} before it reaches a prompt/tool result - untrusted text, same reasoning as {@code ScriptInventory.MAX_DOC_LENGTH} (coordinator, 2026-09-21: "bounded"). */
	private static final int SERVICE_DESCRIPTION_MAX_LENGTH = 400;

	// bounds on the scripting-server walk - documented, never silent (see the response's own "scriptInventoryNotes")
	private static final int MAX_FOLDER_DEPTH = 6;
	private static final int MAX_LIBRARY_ITEMS_LISTED = 500;
	private static final int MAX_LIBRARY_SCRIPTS_FETCHED = 150;
	private static final int LIST_SCRIPTS_PAGE_SIZE = 100;

	/**
	 * Item 4 (coordinator, 2026-09-21): estimate the first request's size (chars/4, a plain, dependency-free
	 * proxy for tokens - good enough to catch an oversized prompt before paying for a 429) and refuse LOCALLY,
	 * with a full message, rather than discovering it through the provider's own rate limit - whose error body
	 * is shortened to 300 characters by the shared {@code on-ebx-agent} library, out of this repository's
	 * scope to change. Default ceiling, or the {@code maxPromptChars} query parameter - no schema change.
	 * 60000 chars (~15000 estimated tokens) is generous now that the catalogue is compact (a handful of
	 * accepted attributes plus ~1-2 catalogue lines each): this is a safety net for an entity with many
	 * attributes/existing suggestions, not the normal case.
	 */
	static final int DEFAULT_MAX_PROMPT_CHARS = 60000;

	private CapabilityLlmSuggester()
	{
	}

	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pOnlyEntityName)
		throws OperationException
	{
		return CapabilityLlmSuggester.suggest(pSession, pFactory, pProjectCode, pOnlyEntityName, null);
	}

	/**
	 * {@link MappingScope#ACCEPTED_ONLY} - unchanged behaviour, the scope every pre-existing caller (including
	 * every REST route) keeps using.
	 *
	 * @param pMaxPromptChars item 4: the request-size ceiling in characters, or null for {@link #DEFAULT_MAX_PROMPT_CHARS}.
	 */
	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pOnlyEntityName,
		final Integer pMaxPromptChars) throws OperationException
	{
		return CapabilityLlmSuggester.suggest(pSession, pFactory, pProjectCode, pOnlyEntityName, pMaxPromptChars, MappingScope.ACCEPTED_ONLY);
	}

	/**
	 * Staging-proposal-run lot (2026-09-24): {@code pScope} widens which entities/attributes/mappings count as
	 * "in scope" - see {@link MappingScope}'s own Javadoc. The one-click proposal run calls this with {@link
	 * MappingScope#PROPOSAL} right after the structure pass, while everything is still {@code PROPOSED}; every
	 * row this pass writes stays {@code PROPOSED} regardless of scope, so nothing here short-circuits review.
	 */
	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pOnlyEntityName,
		final Integer pMaxPromptChars, final MappingScope pScope) throws OperationException
	{
		return CapabilityLlmSuggester.suggest(pSession, pFactory, pProjectCode, pOnlyEntityName, pMaxPromptChars, pScope, null);
	}

	/**
	 * Staging feed-conversion lot E (2026-10-17): {@code pLocale} is the language the person works in - the rationales, notes, labels and
	 * the messages shown to a business user are asked for in it (lot A did the same for the staging pass; the matrix showed an English
	 * "Order-independent key of a name held in TWO fields" on a French project). Null: the session's own locale, English if none.
	 */
	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pOnlyEntityName,
		final Integer pMaxPromptChars, final MappingScope pScope, final java.util.Locale pLocale) throws OperationException
	{
		// audit F-15: the pass writes the project's DESIGN proposals; the queued job already holds that resource, a REST call or a wizard action does not - see EngineClaim
		final EngineClaim claim = EngineClaim.enter(pFactory, pSession, pProjectCode, "CAPABILITY_SUGGEST_LLM_DIRECT", FactoryJobs.DESIGN);
		try
		{
			return CapabilityLlmSuggester.suggestClaimed(pSession, pFactory, pProjectCode, pOnlyEntityName, pMaxPromptChars, pScope,
				pLocale != null ? pLocale : pSession == null || pSession.getLocale() == null ? java.util.Locale.ENGLISH : pSession.getLocale(), null);
		}
		finally
		{
			claim.release();
		}
	}

	/**
	 * Validation desk lot L5 (docs/STAGING-DESK.md): the capability pass, narrowed to the one thing the desk's "Cannot run as proposed" group asks for - a computed
	 * field for an input no field holds. {@code pNeeds} is the measured fact ({@link MissingInputs#needsOf}); only the entities that have a need are visited, the
	 * prompt of each carries the needs and the rule ({@link MissingInputs#promptBlock}), the script-check and service tools refuse, and each proposed field must name the
	 * (suggestion, input) pairs it supplies. Scope {@link MappingScope#PROPOSAL}: the fields it is assembled from may still be {@code PROPOSED}. The rows are written by
	 * the ordinary {@link #write} (a PROPOSED attribute with derivation SCRIPT and its SCRIPT_DERIVATION suggestion); no Java builds the formula.
	 */
	public static ObjectNode suggestForMissingInputs(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<MissingInputs.Need> pNeeds,
		final java.util.Locale pLocale) throws OperationException
	{
		final Map<String, List<MissingInputs.Need>> focus = new java.util.LinkedHashMap<String, List<MissingInputs.Need>>();
		for (final MissingInputs.Need need : pNeeds)
		{
			List<MissingInputs.Need> ofEntity = focus.get(need.entityName);
			if (ofEntity == null)
			{
				ofEntity = new ArrayList<MissingInputs.Need>();
				focus.put(need.entityName, ofEntity);
			}
			ofEntity.add(need);
		}
		final EngineClaim claim = EngineClaim.enter(pFactory, pSession, pProjectCode, "CAPABILITY_MISSING_INPUT_DIRECT", FactoryJobs.DESIGN);
		try
		{
			return CapabilityLlmSuggester.suggestClaimed(pSession, pFactory, pProjectCode, null, null, MappingScope.PROPOSAL,
				pLocale != null ? pLocale : pSession == null || pSession.getLocale() == null ? java.util.Locale.ENGLISH : pSession.getLocale(), focus);
		}
		finally
		{
			claim.release();
		}
	}

	private static ObjectNode suggestClaimed(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pOnlyEntityName,
		final Integer pMaxPromptChars, final MappingScope pScope, final java.util.Locale pLocale, final Map<String, List<MissingInputs.Need>> pFocus) throws OperationException
	{
		final int maxPromptChars = pMaxPromptChars != null && pMaxPromptChars.intValue() > 0 ? pMaxPromptChars.intValue() : CapabilityLlmSuggester.DEFAULT_MAX_PROMPT_CHARS;
		final Adaptation project = CapabilityLlmSuggester.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		// delivery lot 1: a project delivered here by EBX Staging is designed elsewhere - no suggestion on a target
		ProjectOrigin.refuseIfDelivered(pFactory, project);
		final String projectId = project.getOccurrencePrimaryKey().format();

		final Map<String, String> entityIdByName = new HashMap<String, String>();
		final List<StagingModel.EntityInfo> stagingEntities = CapabilityLlmSuggester.readEntities(pFactory, projectId, entityIdByName);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(stagingEntities);
		final Set<String> promoted = new LinkedHashSet<String>(masterModel.builtEntities);
		// lot M11: the members that a canonical entity still in play absorbs - not a candidate for a name key (promotion itself is not asked: it is decided after this pass)
		final Set<String> absorbedByCanonical = new LinkedHashSet<String>();
		for (final StagingModel.EntityInfo canonical : stagingEntities)
		{
			if (NameKeyRule.eligible(canonical.status, false))
			{
				absorbedByCanonical.addAll(canonical.memberEntities);
			}
		}

		final List<StagingModel.EntityInfo> accepted = new ArrayList<StagingModel.EntityInfo>();
		for (final StagingModel.EntityInfo entity : stagingEntities)
		{
			if (!pScope.includes(entity.status))
			{
				continue;
			}
			if (pOnlyEntityName != null && !pOnlyEntityName.trim().isEmpty() && !pOnlyEntityName.trim().equals(entity.name))
			{
				continue;
			}
			if (pFocus != null && !pFocus.containsKey(entity.name))
			{
				continue; // lot L5: a "missing input" run visits only the entities that have a waiting suggestion
			}
			accepted.add(entity);
		}
		if (accepted.isEmpty())
		{
			throw EngineRefusal.coded("missing.acceptedEntities", pOnlyEntityName != null && !pOnlyEntityName.trim().isEmpty()
				? "Entity '" + pOnlyEntityName.trim() + "' of project " + pProjectCode + " is not an accepted-or-applied staging entity"
				: "Project " + pProjectCode + " has no accepted-or-applied staging entity yet: run staging/suggest and accept some entities first.", pProjectCode);
		}

		// COMPACT catalogues, built ONCE per route call (coordinator, 2026-09-21, item 1) - never per entity
		final List<String> scriptNotes = new ArrayList<String>();
		final List<CapabilityLlmPass.FunctionRef> scriptInventory = CapabilityLlmSuggester.buildScriptInventory(pSession, pFactory, scriptNotes);
		final Map<String, List<CapabilityLlmPass.FunctionRef>> functionsByLibrary = CapabilityLlmSuggester.groupByLibrary(scriptInventory);
		final List<CapabilityLlmPass.LibrarySummary> libraries = CapabilityLlmSuggester.librarySummaries(functionsByLibrary);

		final List<String> serviceNotes = new ArrayList<String>();
		final CapabilityLlmSuggester.ServiceCatalogue serviceCatalogue = CapabilityLlmSuggester.buildServiceCatalogue(pSession, pFactory, serviceNotes);

		// exact profile numbers and value shapes for each accepted attribute (task brief) - the SAME evidence
		// ebx.QualityLlmSuggester already reads for its own pass, reused here by calling QualitySuggester's own
		// package-visible helpers rather than duplicating the SQL-building/fallback logic; never a real value,
		// only shapes/counts (see CapabilityLlmPass class Javadoc - unlike QualityLlmSuggester this pass has no
		// Project.llmValueSharing = SAMPLE_VALUES mode of its own, since it never shows a real value at all)
		final String landingDataspace = project.getString(FactoryPaths.Project.LANDING_DATASPACE);
		// the value shapes of an attribute follow the project's effective data-policy level, and a sensitive column gives none
		final LlmDataPolicy dataPolicy = LlmDataPolicy.of(pFactory);
		final LlmDataPolicy.Level dataLevel = dataPolicy.levelFor(project);
		final List<QualitySuggester.MappingRow> mappings = QualitySuggester.readMappings(pFactory, projectId, pScope);
		final Map<String, Map<String, QualitySuggester.MappingRow>> mappingByEntityAttribute = new HashMap<String, Map<String, QualitySuggester.MappingRow>>();
		// fix/match-key-confidence (2026-09-26): EVERY mapping of an entity, one per (attribute, feed) - the map above
		// keeps one per attribute only, while the coverage of a cross-system key is measured on every system
		final Map<String, List<QualitySuggester.MappingRow>> mappingsByEntity = new HashMap<String, List<QualitySuggester.MappingRow>>();
		for (final QualitySuggester.MappingRow mapping : mappings)
		{
			List<QualitySuggester.MappingRow> ofEntity = mappingsByEntity.get(mapping.entityName);
			if (ofEntity == null)
			{
				ofEntity = new ArrayList<QualitySuggester.MappingRow>();
				mappingsByEntity.put(mapping.entityName, ofEntity);
			}
			ofEntity.add(mapping);
		}
		for (final QualitySuggester.MappingRow mapping : mappings)
		{
			Map<String, QualitySuggester.MappingRow> byAttribute = mappingByEntityAttribute.get(mapping.entityName);
			if (byAttribute == null)
			{
				byAttribute = new HashMap<String, QualitySuggester.MappingRow>();
				mappingByEntityAttribute.put(mapping.entityName, byAttribute);
			}
			// lot G2 (2026-10-17): an attribute is read from its most trusted mapping that reads a column - not from whichever came last (measured: a country
			// profiled through a 0 % alternative that read a timestamp column, so a timestamp pattern was proposed on it)
			final QualitySuggester.MappingRow current = byAttribute.get(mapping.attributeName);
			if (current == null || CapabilityLlmSuggester.preferredMapping(mapping, current))
			{
				byAttribute.put(mapping.attributeName, mapping);
			}
		}
		final Map<String, QualitySuggester.LandingTableRef> landingTableCache = new HashMap<String, QualitySuggester.LandingTableRef>();

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, CapabilityLlmSuggester.STEP, "");
		final ObjectNode out = CapabilityLlmSuggester.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("entitiesConsidered", accepted.size());
		out.put("librariesInventoried", libraries.size());
		out.put("libraryFunctionsInventoried", scriptInventory.size());
		out.put("connectorsInventoried", serviceCatalogue.connectors.size());
		out.put("maxPromptChars", maxPromptChars);
		CapabilityLlmSuggester.putStrings(out, "scriptInventoryNotes", scriptNotes);
		CapabilityLlmSuggester.putStrings(out, "serviceInventoryNotes", serviceNotes);

		final ArrayNode entitiesOut = out.putArray("entities");
		int totalScriptConstraints = 0;
		int totalServices = 0;
		int totalScriptDerivations = 0;
		boolean anySucceeded = false;
		for (final StagingModel.EntityInfo entity : accepted)
		{
			final String entityId = entityIdByName.get(entity.name);
			final ObjectNode entityOut = entitiesOut.addObject();
			entityOut.put("entity", entity.name);
			try
			{
				final CapabilityLlmPass.EntityInfo llmEntity = CapabilityLlmSuggester.toLlmEntity(pSession, pFactory, entityId, entity, promoted.contains(entity.name),
					landingDataspace, mappingByEntityAttribute.get(entity.name), mappingsByEntity.get(entity.name), landingTableCache, pScope, dataPolicy, dataLevel);
				final CapabilityLlmPass.Session llmSession = new CapabilityLlmPass.Session(llmEntity, functionsByLibrary, serviceCatalogue.connectors,
					serviceCatalogue.describer);

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

				final String systemPrompt = CapabilityLlmPass.systemPrompt(pLocale);
				final List<MissingInputs.Need> entityNeeds = pFocus == null ? null : pFocus.get(entity.name);
				if (entityNeeds != null)
				{
					llmSession.restrictToMissingInputs(entityNeeds);
				}
				final String userPrompt = CapabilityLlmPass.userPrompt(llmEntity, libraries, serviceCatalogue.connectors)
					+ (entityNeeds == null ? "" : MissingInputs.promptBlock(entity.name, entityNeeds));
				// item 4: refuse LOCALLY, before ever calling the provider, rather than discovering an oversized
				// request through its own 429 (whose body the shared on-ebx-agent library shortens to 300 chars)
				final int promptChars = systemPrompt.length() + userPrompt.length();
				if (promptChars > maxPromptChars)
				{
					entityOut.put("promptChars", promptChars);
					entityOut.put("ok", false);
					entityOut.put("error", "prompt too large for entity '" + entity.name + "': ~" + promptChars + " character(s) (~" + promptChars / 4
						+ " estimated token(s)) exceeds the configured ceiling of " + maxPromptChars + " character(s) (~" + maxPromptChars / 4
						+ " estimated token(s)) - no language-model call was attempted. Raise maxPromptChars, or this entity has too many accepted "
						+ "attributes/existing suggestions to fit even the compact catalogue.");
					continue;
				}

				final AgentRuntime.Outcome firstOutcome = AgentRuntime.run(pSession, pFactory, projectId, CapabilityLlmSuggester.PHASE,
					entityNeeds == null ? "Propose script constraints and external services for entity " + entity.name + " of project " + pProjectCode + "."
						: "Propose a computed field for the missing input(s) of entity " + entity.name + " of project " + pProjectCode + ".", systemPrompt, userPrompt, tools);

				// round 3 (coordinator, 2026-09-21): a DONE run whose own text correctly described suggestions, but
				// never called a tool, recorded nothing - one follow-up on the SAME Session/tools (fetched details,
				// refusal counters carry over), quoting the model's own text and telling it plainly to use the tools
				final ProseAnswerRecovery.RecordedNothing recordedNothing = new ProseAnswerRecovery.RecordedNothing()
				{
					@Override
					public boolean test()
					{
						return llmSession.proposedScriptConstraints().isEmpty() && llmSession.proposedScriptDerivations().isEmpty() && llmSession.proposedServices().isEmpty()
							&& llmSession.notes().isEmpty();
					}
				};
				final ProseAnswerRecovery.FollowUpRunner followUpRunner = new ProseAnswerRecovery.FollowUpRunner()
				{
					@Override
					public AgentRuntime.Outcome run(final String pFollowUpTask) throws OperationException
					{
						return AgentRuntime.run(pSession, pFactory, projectId, CapabilityLlmSuggester.PHASE, "Propose script constraints and external services for "
							+ "entity " + entity.name + " of project " + pProjectCode + " (follow-up: the previous answer was prose, not a tool call).", systemPrompt,
							pFollowUpTask, tools);
					}
				};
				final ProseAnswerRecovery.Result recovery = ProseAnswerRecovery.recover(firstOutcome, recordedNothing, followUpRunner);
				final AgentRuntime.Outcome outcome = recovery.latest();

				final ObjectNode agentRun = entityOut.putObject("agentRun");
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
				entityOut.put("proseAnswerRecovered", recovery.proseAnswerRecovered);
				entityOut.put("earlyStop", llmSession.stoppedEarly());
				if (llmSession.stoppedEarly())
				{
					entityOut.put("earlyStopReason", llmSession.stopReason());
				}

				// written unconditionally: even a run cut short by BUDGET/FAILED (or recovered by one follow-up)
				// keeps whatever it validly proposed - only "ok"/"status" change below
				// lot M10c (2026-10-18): the person name key never depends on the model remembering to propose it - the rules side proposes it when the
				// library is there, the entity has a first-name and a last-name attribute and no derivation of that library exists (NameKeyRule)
				final List<CapabilityLlmPass.ProposedScriptDerivation> allDerivations = new ArrayList<CapabilityLlmPass.ProposedScriptDerivation>(
					llmSession.proposedScriptDerivations());
				if (entityNeeds == null)
				{
					allDerivations.addAll(CapabilityLlmSuggester.nameKeyRule(pSession, pFactory, projectId, entityId, entity.name, llmEntity,
						NameKeyRule.eligible(entity.status, absorbedByCanonical.contains(entity.name)),
						functionsByLibrary, llmSession, pLocale, entityOut));
				}
				final Map<String, DerivationEvidence.Decision> evidence = CapabilityLlmSuggester.derivationEvidence(pSession, pFactory, landingDataspace,
					mappingsByEntity.get(entity.name), landingTableCache, allDerivations);
				final List<String> refusedBindings = new ArrayList<String>();
				final int[] counts = CapabilityLlmSuggester.write(pSession, pFactory, projectId, entityId, entity.name, llmSession, allDerivations, evidence, refusedBindings);
				entityOut.put("scriptConstraintsCreated", counts[0]);
				entityOut.put("servicesCreated", counts[1]);
				entityOut.put("scriptDerivationsCreated", counts[2]);
				totalScriptConstraints += counts[0];
				totalServices += counts[1];
				totalScriptDerivations += counts[2];

				final ArrayNode entityNotes = entityOut.putArray("notes");
				for (final String note : llmSession.notes())
				{
					entityNotes.add(note);
				}
				for (final String refusal : refusedBindings)
				{
					entityNotes.add("Suggestion not stored - " + refusal);
				}
				// coverage lot (coordinator, 2026-09-21, item 2c): informational only, same "columnsWithoutProposalOrNote"
				// idea ebx.QualityLlmSuggester already reports - never enforced, just makes a run that quietly stops
				// after one or two attribute groups visible instead of looking complete
				final ArrayNode gapNode = entityOut.putArray("attributesWithoutSuggestionOrNote");
				for (final String attributeName : llmSession.attributesWithoutSuggestionOrNote())
				{
					gapNode.add(attributeName);
				}

				// item 3 (coordinator round 2, BLOCKING): ok must reflect the REAL outcome - a FAILED/BUDGET run (e.g.
				// the provider's own 429) is never ok:true just because this line was reached without an exception.
				// Round 3: a DONE run that STILL recorded nothing after its one follow-up is NOT ok either, whatever
				// its own status says - reported as status NO_OUTPUT, with the model's own last text so a person can
				// read what it said.
				final boolean succeeded = !recovery.noOutput && outcome.status == AgentLoop.Status.DONE;
				entityOut.put("status", recovery.noOutput ? "NO_OUTPUT" : AgentRuntime.statusOf(outcome.status));
				entityOut.put("ok", succeeded);
				if (recovery.noOutput)
				{
					entityOut.put("error", "the model's final answer was prose, never a tool call, even after one follow-up: nothing was recorded for this entity.");
					entityOut.put("modelText", recovery.finalText);
				}
				else if (!succeeded)
				{
					entityOut.put("error", "agent run ended " + AgentRuntime.statusOf(outcome.status) + (outcome.detail == null ? "" : ": " + outcome.detail));
				}
				else
				{
					anySucceeded = true;
				}
			}
			catch (final OperationException ex)
			{
				entityOut.put("ok", false);
				entityOut.put("error", ex.getMessage());
			}
		}
		out.put("scriptConstraintsCreated", totalScriptConstraints);
		out.put("servicesCreated", totalServices);
		out.put("scriptDerivationsCreated", totalScriptDerivations);

		final String message = totalScriptConstraints + " script constraint suggestion(s), " + totalScriptDerivations + " script derivation suggestion(s) and "
			+ totalServices + " external service suggestion(s) proposed by the language model across " + accepted.size() + " entity/entities.";
		// item 3: the route-level step is DONE only if at least one entity's run genuinely finished DONE
		PipelineSteps.end(pSession, pFactory, stepId, anySucceeded, message, out.toString());
		return out;
	}

	// ------------------------------------------------------------------------------------------ lot M10c: the person name key

	/**
	 * Lot M10c (2026-10-18): {@link NameKeyRule} for one entity. The structural preconditions are checked FIRST (library published with the expected signature, entity
	 * promoted, no derivation of that library proposed in this run or stored in any status, two candidate text attributes), so an entity the rule can never serve costs
	 * no model call; then ONE closed question ({@link NamePartsPass}) names the first-name and the last-name attribute among the entity's own attributes, and the rule
	 * builds the derivation itself, origin {@code RULE}. Never throws and never fails the entity: whatever happens is the {@code nameKeyRule} node of the entity's block
	 * ({@code status}: {@code PROPOSED}, {@code SKIPPED} + {@code skipped}, {@code NO_PAIR}, {@code NOT_ANSWERED}, {@code NOT_PROPOSABLE} or {@code FAILED}).
	 */
	private static List<CapabilityLlmPass.ProposedScriptDerivation> nameKeyRule(final Session pSession, final Adaptation pFactory, final String pProjectId,
		final String pEntityId, final String pEntityName, final CapabilityLlmPass.EntityInfo pEntity, final boolean pEligible,
		final Map<String, List<CapabilityLlmPass.FunctionRef>> pFunctionsByLibrary, final CapabilityLlmPass.Session pLlmSession, final java.util.Locale pLocale,
		final ObjectNode pEntityOut)
	{
		final ObjectNode node = pEntityOut.putObject("nameKeyRule");
		final List<CapabilityLlmPass.ProposedScriptDerivation> out = new ArrayList<CapabilityLlmPass.ProposedScriptDerivation>();
		try
		{
			final CapabilityLlmPass.FunctionRef function = NameKeyRule.findFunction(pFunctionsByLibrary);
			final Set<String> excluded = new LinkedHashSet<String>();
			final Set<String> taken = new LinkedHashSet<String>();
			final Map<String, String> labels = new HashMap<String, String>();
			CapabilityLlmSuggester.readNameFacts(pFactory, pEntityId, excluded, taken, labels);
			final List<CapabilityLlmPass.ProposedScriptDerivation> byModel = pLlmSession.proposedScriptDerivations();
			for (final CapabilityLlmPass.ProposedScriptDerivation proposed : byModel)
			{
				taken.add(proposed.newAttributeName);
			}
			final List<CapabilityLlmPass.AttributeInfo> candidates = NameKeyRule.candidates(pEntity, excluded);
			final NameKeyRule.Skip skip = NameKeyRule.check(pEntity, pEligible, function, byModel, candidates);
			if (skip != NameKeyRule.Skip.NONE)
			{
				node.put("status", "SKIPPED");
				node.put("skipped", skip.name());
				return out;
			}
			final NamePartsPass.Session question = new NamePartsPass.Session(candidates);
			final ToolSet tools;
			try
			{
				tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), Collections.singletonMap("factory", question.toolGroup()));
			}
			catch (final McpException ex)
			{
				throw EngineRefusal.codedWithCause("external.agentTools", "Could not build the agent's tool set: " + ex.getMessage(), ex);
			}
			final AgentRuntime.Outcome outcome = AgentRuntime.run(pSession, pFactory, pProjectId, CapabilityLlmSuggester.NAME_PARTS_PHASE,
				"Name the first-name and last-name attributes of entity " + pEntityName + ".", NamePartsPass.systemPrompt(),
				NamePartsPass.userPrompt(pEntity, candidates, labels), tools);
			node.put("agentRunId", outcome.runId);
			node.put("agentStatus", AgentRuntime.statusOf(outcome.status));
			if (!question.answered())
			{
				node.put("status", "NOT_ANSWERED");
				if (!question.lastRefusal().isEmpty())
				{
					node.put("lastRefusal", question.lastRefusal());
				}
				return out;
			}
			if (question.parts() == null)
			{
				node.put("status", "NO_PAIR");
				node.put("reason", question.reason());
				return out;
			}
			final String label = WizardMessages.get(pLocale, "capability.nameKey.label");
			final String rationale = WizardMessages.get(pLocale, "capability.nameKey.rationale", question.parts().firstName, question.parts().lastName,
				NameKeyRule.LIBRARY_IMPORT_NAME + "." + NameKeyRule.FUNCTION_NAME);
			final CapabilityLlmPass.ProposedScriptDerivation proposal = NameKeyRule.propose(function, question.parts(), candidates, taken, label, rationale);
			if (proposal == null)
			{
				node.put("status", "NOT_PROPOSABLE");
				return out;
			}
			out.add(proposal);
			node.put("status", "PROPOSED");
			node.put("newAttribute", proposal.newAttributeName);
			node.put("firstName", question.parts().firstName);
			node.put("lastName", question.parts().lastName);
			node.put("reason", question.reason());
		}
		catch (final OperationException ex)
		{
			node.put("status", "FAILED");
			node.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
		}
		return out;
	}

	/**
	 * What the rule reads of an entity's stored attributes: every name (any status - a new attribute never takes a name that exists), those that cannot be a person's name
	 * part (computed from another attribute, or a reference to another entity) and the human labels (shown to the model as data).
	 */
	private static void readNameFacts(final Adaptation pFactory, final String pEntityId, final Set<String> pExcluded, final Set<String> pTaken, final Map<String, String> pLabels)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String name = row.getString(FactoryPaths.StagingAttribute.NAME);
				pTaken.add(name);
				final String label = row.getString(FactoryPaths.StagingAttribute.LABEL);
				if (label != null && !label.trim().isEmpty())
				{
					pLabels.put(name, label);
				}
				final String derivation = row.getString(FactoryPaths.StagingAttribute.DERIVATION);
				if ((derivation != null && !derivation.trim().isEmpty()) || row.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY) != null)
				{
					pExcluded.add(name);
				}
			}
		}
		finally
		{
			rows.close();
		}
	}

	/** {@code FunctionRef}s grouped by {@code libraryIdePath}, preserving first-seen order - the full detail behind {@link CapabilityLlmPass.Session#getLibraryFunctions}, built ONCE per route call. */
	private static Map<String, List<CapabilityLlmPass.FunctionRef>> groupByLibrary(final List<CapabilityLlmPass.FunctionRef> pFunctions)
	{
		final Map<String, List<CapabilityLlmPass.FunctionRef>> out = new LinkedHashMap<String, List<CapabilityLlmPass.FunctionRef>>();
		for (final CapabilityLlmPass.FunctionRef function : pFunctions)
		{
			List<CapabilityLlmPass.FunctionRef> forLibrary = out.get(function.libraryIdePath);
			if (forLibrary == null)
			{
				forLibrary = new ArrayList<CapabilityLlmPass.FunctionRef>();
				out.put(function.libraryIdePath, forLibrary);
			}
			forLibrary.add(function);
		}
		return out;
	}

	/** One {@link CapabilityLlmPass.LibrarySummary} per library - the compact catalogue line (coordinator, 2026-09-21, item 1). */
	private static List<CapabilityLlmPass.LibrarySummary> librarySummaries(final Map<String, List<CapabilityLlmPass.FunctionRef>> pFunctionsByLibrary)
	{
		final List<CapabilityLlmPass.LibrarySummary> out = new ArrayList<CapabilityLlmPass.LibrarySummary>();
		for (final Map.Entry<String, List<CapabilityLlmPass.FunctionRef>> entry : pFunctionsByLibrary.entrySet())
		{
			final CapabilityLlmPass.FunctionRef first = entry.getValue().get(0);
			out.add(new CapabilityLlmPass.LibrarySummary(entry.getKey(), first.libraryImportName, CapabilityLlmPass.firstSentence(first.libraryDescription),
				entry.getValue().size()));
		}
		return out;
	}

	// -------------------------------------------------------------------------------------------------- writing

	/** @return {script constraints created, services created, script derivations created} */
	private static int[] write(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityId, final String pEntityName,
		final CapabilityLlmPass.Session pLlmSession, final List<CapabilityLlmPass.ProposedScriptDerivation> pDerivations,
		final Map<String, DerivationEvidence.Decision> pEvidence, final List<String> pRefused) throws OperationException
	{
		final List<CapabilityLlmPass.ProposedScriptConstraint> scriptConstraints = pLlmSession.proposedScriptConstraints();
		final List<CapabilityLlmPass.ProposedService> services = pLlmSession.proposedServices();
		final List<CapabilityLlmPass.ProposedScriptDerivation> scriptDerivations = pDerivations;
		final AdaptationTable table = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION);
		final AdaptationTable attributesTable = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE);
		final Map<String, String> attributeIdByName = CapabilityLlmSuggester.attributeIdsByName(pFactory, pEntityId);
		final int[] counts = new int[3];

		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final CapabilityLlmPass.ProposedScriptConstraint constraint : scriptConstraints)
				{
					if (!StoredJsonFit.fits(constraint.bindingJson, CapabilityLlmSuggester.BINDING_MAX_LENGTH))
					{
						pRefused.add(constraint.functionName + " on " + constraint.attribute + ": " + StoredJsonFit.refusal("binding", constraint.bindingJson, CapabilityLlmSuggester.BINDING_MAX_LENGTH));
						continue;
					}
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
					value.setValue(pProjectId, FactoryPaths.CapabilitySuggestion.PROJECT);
					value.setValue(pEntityId, FactoryPaths.CapabilitySuggestion.ENTITY);
					if (constraint.attribute != null)
					{
						final String attributeId = attributeIdByName.get(constraint.attribute);
						if (attributeId != null)
						{
							value.setValue(attributeId, FactoryPaths.CapabilitySuggestion.ATTRIBUTE);
						}
					}
					value.setValue(CapabilityLlmPass.SCRIPT_CONSTRAINT, FactoryPaths.CapabilitySuggestion.NATURE);
					value.setValue(constraint.zone, FactoryPaths.CapabilitySuggestion.ZONE);
					value.setValue(constraint.libraryIdePath, FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY);
					value.setValue(constraint.functionName, FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION);
					if (constraint.libraryImportName != null)
					{
						value.setValue(constraint.libraryImportName, FactoryPaths.CapabilitySuggestion.TARGET_IMPORT_NAME);
					}
					value.setValue(constraint.bindingJson, FactoryPaths.CapabilitySuggestion.BINDING);
					// apply-ux fix (2026-09-21): already sanitised (single line, bounded) by CapabilityLlmPass.Session
					// .suggestScriptConstraint - stored as-is, the field's own maxLength (200) is a further safety margin
					value.setValue(constraint.userMessage, FactoryPaths.CapabilitySuggestion.USER_MESSAGE);
					value.setValue(constraint.severity, FactoryPaths.CapabilitySuggestion.SEVERITY);
					// quality-script-rule lot (2026-09-23): null/blank for MASTER zone or when the model had no
					// opinion (ProposedScriptConstraint's own constructor already clamps a non-STAGING zone to
					// null) - never REJECT from this pass, see CapabilityLlmPass.SUGGEST_SCRIPT_CONSTRAINT_SCHEMA.
					if (constraint.transferPolicy != null)
					{
						value.setValue(constraint.transferPolicy, FactoryPaths.CapabilitySuggestion.TRANSFER_POLICY);
					}
					value.setValue("LLM", FactoryPaths.CapabilitySuggestion.ORIGIN);
					value.setValue(Integer.valueOf(constraint.confidence), FactoryPaths.CapabilitySuggestion.CONFIDENCE);
					value.setValue(TextLimits.truncate(constraint.rationale, CapabilityLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.CapabilitySuggestion.RATIONALE);
					value.setValue("PROPOSED", FactoryPaths.CapabilitySuggestion.STATUS);
					pContext.doCreateOccurrence(value, table);
					counts[0]++;
				}
				for (final CapabilityLlmPass.ProposedService service : services)
				{
					if (!StoredJsonFit.fits(service.bindingJson, CapabilityLlmSuggester.BINDING_MAX_LENGTH))
					{
						pRefused.add(service.operation + " on " + service.attribute + ": " + StoredJsonFit.refusal("binding", service.bindingJson, CapabilityLlmSuggester.BINDING_MAX_LENGTH));
						continue;
					}
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
					value.setValue(pProjectId, FactoryPaths.CapabilitySuggestion.PROJECT);
					value.setValue(pEntityId, FactoryPaths.CapabilitySuggestion.ENTITY);
					if (service.attribute != null)
					{
						final String attributeId = attributeIdByName.get(service.attribute);
						if (attributeId != null)
						{
							value.setValue(attributeId, FactoryPaths.CapabilitySuggestion.ATTRIBUTE);
						}
					}
					value.setValue(CapabilityLlmPass.EXTERNAL_SERVICE, FactoryPaths.CapabilitySuggestion.NATURE);
					value.setValue(service.connectorId, FactoryPaths.CapabilitySuggestion.CONNECTOR_ID);
					value.setValue(service.operation, FactoryPaths.CapabilitySuggestion.CONNECTOR_OPERATION);
					value.setValue(service.bindingJson, FactoryPaths.CapabilitySuggestion.BINDING);
					value.setValue(Boolean.valueOf(service.ready), FactoryPaths.CapabilitySuggestion.READY);
					if (service.readinessDetail != null)
					{
						value.setValue(TextLimits.truncate(service.readinessDetail, CapabilityLlmSuggester.READINESS_DETAIL_MAX_LENGTH),
							FactoryPaths.CapabilitySuggestion.READINESS_DETAIL);
					}
					// service-choice lot: pricing/pricingNote relayed as data (never a Java judgment - see
					// CapabilityLlmPass.ProposedService's own Javadoc); severity is deliberately NEVER set here -
					// it means nothing for a suggestion that is never attached or run (coordinator, 2026-09-21)
					value.setValue(service.pricing, FactoryPaths.CapabilitySuggestion.PRICING);
					if (service.pricingNote != null)
					{
						value.setValue(TextLimits.truncate(service.pricingNote, CapabilityLlmSuggester.READINESS_DETAIL_MAX_LENGTH),
							FactoryPaths.CapabilitySuggestion.PRICING_NOTE);
					}
					if (service.alternativeTo != null)
					{
						value.setValue(TextLimits.truncate(service.alternativeTo, CapabilityLlmSuggester.READINESS_DETAIL_MAX_LENGTH),
							FactoryPaths.CapabilitySuggestion.ALTERNATIVE_TO);
					}
					value.setValue("LLM", FactoryPaths.CapabilitySuggestion.ORIGIN);
					value.setValue(Integer.valueOf(service.confidence), FactoryPaths.CapabilitySuggestion.CONFIDENCE);
					value.setValue(TextLimits.truncate(service.rationale, CapabilityLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.CapabilitySuggestion.RATIONALE);
					value.setValue("PROPOSED", FactoryPaths.CapabilitySuggestion.STATUS);
					pContext.doCreateOccurrence(value, table);
					counts[1]++;
				}
				// script-backed-derivation lot, 2026-09-23: TWO rows per proposal, in the SAME procedure - the new
				// StagingAttribute FIRST (its auto-increment id is readable right off the created occurrence,
				// CLAUDE.md sec 7), then the CapabilitySuggestion naming it as CapabilitySuggestion.attribute.
				// Skipped (with nothing written) when the source attribute's own id cannot be resolved - should
				// not happen (the source came straight from this entity's own attributeIdByName), defensive only.
				for (final CapabilityLlmPass.ProposedScriptDerivation derivation : scriptDerivations)
				{
					final String sourceAttributeId = attributeIdByName.get(derivation.sourceAttribute);
					if (sourceAttributeId == null)
					{
						continue;
					}
					if (!StoredJsonFit.fits(derivation.bindingJson, CapabilityLlmSuggester.BINDING_MAX_LENGTH))
					{
						pRefused.add(derivation.newAttributeName + ": " + StoredJsonFit.refusal("binding", derivation.bindingJson, CapabilityLlmSuggester.BINDING_MAX_LENGTH));
						continue;
					}
					// fix/match-key-confidence: the measured-evidence confidence (and its one-sentence reason) replaces
					// the model's own number on BOTH rows, so every bulk accept reads the same decided value
					final DerivationEvidence.Decision decision = pEvidence.get(derivation.newAttributeName);
					final int confidence = decision == null ? derivation.confidence : decision.confidence;
					final String rationale = decision == null || decision.note == null ? derivation.rationale : derivation.rationale + " " + decision.note;
					final ValueContextForUpdate attributeValue = pContext.getContextForNewOccurrence(attributesTable);
					attributeValue.setValue(pEntityId, FactoryPaths.StagingAttribute.ENTITY);
					attributeValue.setValue(derivation.newAttributeName, FactoryPaths.StagingAttribute.NAME);
					attributeValue.setValue(derivation.newAttributeLabel, FactoryPaths.StagingAttribute.LABEL);
					attributeValue.setValue(derivation.dataType, FactoryPaths.StagingAttribute.DATA_TYPE);
					attributeValue.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.MANDATORY);
					attributeValue.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.BUSINESS_KEY);
					attributeValue.setValue(sourceAttributeId, FactoryPaths.StagingAttribute.DERIVED_FROM);
					attributeValue.setValue(ScriptDerivation.DERIVATION_SCRIPT, FactoryPaths.StagingAttribute.DERIVATION);
					attributeValue.setValue(derivation.origin, FactoryPaths.StagingAttribute.ORIGIN);
					attributeValue.setValue(Integer.valueOf(confidence), FactoryPaths.StagingAttribute.CONFIDENCE);
					attributeValue.setValue(TextLimits.truncate(rationale, CapabilityLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.StagingAttribute.RATIONALE);
					attributeValue.setValue("PROPOSED", FactoryPaths.StagingAttribute.STATUS);
					final String newAttributeId = pContext.doCreateOccurrence(attributeValue, attributesTable).getOccurrencePrimaryKey().format();

					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
					value.setValue(pProjectId, FactoryPaths.CapabilitySuggestion.PROJECT);
					value.setValue(pEntityId, FactoryPaths.CapabilitySuggestion.ENTITY);
					value.setValue(newAttributeId, FactoryPaths.CapabilitySuggestion.ATTRIBUTE);
					value.setValue(CapabilityLlmPass.SCRIPT_DERIVATION, FactoryPaths.CapabilitySuggestion.NATURE);
					value.setValue(CapabilityLlmPass.STAGING, FactoryPaths.CapabilitySuggestion.ZONE);
					value.setValue(derivation.libraryIdePath, FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY);
					value.setValue(derivation.functionName, FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION);
					if (derivation.libraryImportName != null)
					{
						value.setValue(derivation.libraryImportName, FactoryPaths.CapabilitySuggestion.TARGET_IMPORT_NAME);
					}
					value.setValue(derivation.bindingJson, FactoryPaths.CapabilitySuggestion.BINDING);
					value.setValue(derivation.origin, FactoryPaths.CapabilitySuggestion.ORIGIN);
					value.setValue(Integer.valueOf(confidence), FactoryPaths.CapabilitySuggestion.CONFIDENCE);
					// lot L5: a field proposed for waiting suggestions says which ones, as a sentence at the end of ITS suggestion's rationale (the attribute's own text stays clean)
					value.setValue(MissingInputs.withMarker(rationale, derivation.supplies, CapabilityLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.CapabilitySuggestion.RATIONALE);
					value.setValue("PROPOSED", FactoryPaths.CapabilitySuggestion.STATUS);
					pContext.doCreateOccurrence(value, table);
					counts[2]++;
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveCapabilityProposals", "Writing the language-model capability proposals for entity " + pEntityName + " failed: "
				+ result.getExceptionFullMessage(Locale.ENGLISH), pEntityName);
		}
		return counts;
	}

	/**
	 * fix/match-key-confidence (2026-09-26): for every proposed derivation the model states is a cross-system key,
	 * measures the EXACT fill of each attribute its binding reads on each system feeding the entity (one {@code
	 * QualitySuggester.exactAggregates} per mapping - counts only, never a value) and hands the decision to
	 * {@link DerivationEvidence#decide}. Keyed by the new attribute's name; a derivation that is not a
	 * cross-system key is not measured at all (no entry, the model's number stands). A mapping whose landing
	 * table cannot be resolved simply contributes no fill - that system then reads "not measured", never filled.
	 */
	private static Map<String, DerivationEvidence.Decision> derivationEvidence(final Session pSession, final Adaptation pFactory, final String pLandingDataspace,
		final List<QualitySuggester.MappingRow> pEntityMappings, final Map<String, QualitySuggester.LandingTableRef> pLandingTableCache,
		final List<CapabilityLlmPass.ProposedScriptDerivation> pDerivations)
	{
		final Map<String, DerivationEvidence.Decision> out = new HashMap<String, DerivationEvidence.Decision>();
		final List<QualitySuggester.MappingRow> entityMappings = pEntityMappings == null ? Collections.<QualitySuggester.MappingRow>emptyList() : pEntityMappings;
		final Set<String> systems = new LinkedHashSet<String>();
		for (final QualitySuggester.MappingRow mapping : entityMappings)
		{
			if (mapping.sourceSystem != null)
			{
				systems.add(mapping.sourceSystem);
			}
		}
		final Map<String, DerivationEvidence.SourceFill> measured = new HashMap<String, DerivationEvidence.SourceFill>();
		for (final CapabilityLlmPass.ProposedScriptDerivation derivation : pDerivations)
		{
			if (!derivation.crossSystemKey)
			{
				continue;
			}
			final List<DerivationEvidence.SourceFill> fills = new ArrayList<DerivationEvidence.SourceFill>();
			for (final QualitySuggester.MappingRow mapping : entityMappings)
			{
				if (mapping.sourceSystem == null || !derivation.boundAttributes.contains(mapping.attributeName))
				{
					continue;
				}
				DerivationEvidence.SourceFill fill = measured.get(mapping.mappingId);
				if (fill == null && pLandingDataspace != null && !pLandingDataspace.trim().isEmpty())
				{
					final QualitySuggester.LandingTableRef landingRef = QualitySuggester.landingTableRef(pFactory, pLandingDataspace, mapping, pLandingTableCache);
					if (landingRef != null)
					{
						// staging polish (2026-10-17): bounded above mdm.factory.profile.maxRowsFullScan rows - a fill RATE is what the decision reads
						final QualitySuggester.ExactAggregates aggregates = QualitySuggester.boundedAggregates(pSession, landingRef, mapping.fieldName);
						fill = new DerivationEvidence.SourceFill(mapping.attributeName, mapping.sourceSystem, aggregates.rows, aggregates.filled);
						measured.put(mapping.mappingId, fill);
					}
				}
				if (fill != null)
				{
					fills.add(fill);
				}
			}
			out.put(derivation.newAttributeName, DerivationEvidence.decide(derivation.confidence, true, derivation.boundAttributes, systems, fills));
		}
		return out;
	}

	private static Map<String, String> attributeIdsByName(final Adaptation pFactory, final String pEntityId)
	{
		final Map<String, String> out = new HashMap<String, String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.put(row.getString(FactoryPaths.StagingAttribute.NAME), row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------- read

	/** How many distinct shapes are shown per attribute in the prompt - same reasoning as {@code ebx.QualityLlmSuggester.MAX_SHAPES_SHOWN}, capped lower since this pass shows several attributes' worth of context per entity, not one column at a time. */
	private static final int MAX_SHAPES_SHOWN = 5;

	private static CapabilityLlmPass.EntityInfo toLlmEntity(final Session pSession, final Adaptation pFactory, final String pEntityId,
		final StagingModel.EntityInfo pStagingEntity, final boolean pPromoted, final String pLandingDataspace,
		final Map<String, QualitySuggester.MappingRow> pMappingsByAttribute, final List<QualitySuggester.MappingRow> pAllMappings,
		final Map<String, QualitySuggester.LandingTableRef> pLandingTableCache, final MappingScope pScope, final LlmDataPolicy pPolicy, final LlmDataPolicy.Level pLevel)
	{
		final List<CapabilityLlmPass.AttributeInfo> attributes = new ArrayList<CapabilityLlmPass.AttributeInfo>();
		for (final StagingModel.AttributeInfo attribute : pStagingEntity.attributes)
		{
			if (attribute.primaryKey || !pScope.includes(attribute.status))
			{
				continue;
			}
			final QualitySuggester.MappingRow mapping = pMappingsByAttribute == null ? null : pMappingsByAttribute.get(attribute.name);
			CapabilityLlmPass.AttributeInfo info = CapabilityLlmSuggester.toLlmAttribute(pSession, pFactory, attribute, mapping, pLandingDataspace, pLandingTableCache, pScope,
				pPolicy, pLevel);
			// lot E (2026-10-17): how the values are written ACROSS ALL the sources feeding the attribute (the shapes above come from one mapping)
			final NormalisationProfiler.PhoneMix mix = CapabilityLlmSuggester.phoneMix(pSession, pFactory, attribute.name, pAllMappings, pLandingDataspace,
				pLandingTableCache, pLevel);
			if (mix != null && mix.isPhoneLike())
			{
				info = new CapabilityLlmPass.AttributeInfo(info.name, info.dataType, info.maxLength, info.rows, info.fillRatePercent, info.distinctCount, info.shapes,
					info.cleaningSteps, info.qualityRules, mix.sentence(), mix.needsDefaultCountry(), info.evidence);
			}
			// lot G2 (2026-10-17): an attribute fed only by constants holds those constants, whatever column the mappings nominally read
			final ValueEvidence constants = CapabilityLlmSuggester.constantEvidence(pAllMappings, attribute.name);
			if (constants != null)
			{
				info = new CapabilityLlmPass.AttributeInfo(info.name, info.dataType, info.maxLength, info.rows, info.fillRatePercent, info.distinctCount, info.shapes,
					info.cleaningSteps, info.qualityRules, info.facts, info.needsDefaultCountry, constants);
			}
			attributes.add(info);
		}
		final List<CapabilityLlmPass.ExistingSuggestion> existing = CapabilityLlmSuggester.readExisting(pFactory, pEntityId);
		return new CapabilityLlmPass.EntityInfo(pStagingEntity.name, pStagingEntity.label, pPromoted, attributes, existing);
	}

	/**
	 * One accepted business attribute's structural facts plus, WHEN it has an accepted landing mapping, its
	 * EXACT profile numbers and value SHAPES (never a real value) - the same evidence {@code
	 * ebx.QualityLlmSuggester} reads for its own pass, obtained by calling {@code QualitySuggester}'s own
	 * package-visible helpers directly ({@link QualitySuggester#landingTableRef}/{@link
	 * QualitySuggester#distinctValues}/{@link QualitySuggester#exactAggregates}) rather than duplicating the
	 * SQL-building/fallback logic - both classes live in this same {@code ebx} package. An attribute with no
	 * accepted mapping (or whose landing table cannot be resolved) simply gets no profile - never a hard
	 * failure, since a capability suggestion is still meaningful from structure alone (name, type, length).
	 */
	private static CapabilityLlmPass.AttributeInfo toLlmAttribute(final Session pSession, final Adaptation pFactory, final StagingModel.AttributeInfo pAttribute,
		final QualitySuggester.MappingRow pMapping, final String pLandingDataspace, final Map<String, QualitySuggester.LandingTableRef> pLandingTableCache,
		final MappingScope pScope, final LlmDataPolicy pPolicy, final LlmDataPolicy.Level pLevel)
	{
		final List<String> cleaningSteps = new ArrayList<String>();
		final List<String> qualityRules = new ArrayList<String>();
		if (pMapping != null)
		{
			CapabilityLlmSuggester.addCleaningSteps(pFactory, pMapping.mappingId, cleaningSteps);
			CapabilityLlmSuggester.addQualityRules(pFactory, pMapping.attributeId, pScope, qualityRules);
		}
		if (pMapping == null || pLandingDataspace == null || pLandingDataspace.trim().isEmpty())
		{
			return new CapabilityLlmPass.AttributeInfo(pAttribute.name, pAttribute.dataType, pAttribute.maxLength, null, null, null,
				Collections.<CapabilityLlmPass.ShapeCount>emptyList(), cleaningSteps, qualityRules);
		}
		final QualitySuggester.LandingTableRef landingRef = QualitySuggester.landingTableRef(pFactory, pLandingDataspace, pMapping, pLandingTableCache);
		if (landingRef == null)
		{
			return new CapabilityLlmPass.AttributeInfo(pAttribute.name, pAttribute.dataType, pAttribute.maxLength, null, null, null,
				Collections.<CapabilityLlmPass.ShapeCount>emptyList(), cleaningSteps, qualityRules);
		}
		final boolean sensitive = pPolicy.isSensitive(pAttribute.name) || pPolicy.isSensitive(pMapping.attributeName) || pPolicy.isSensitive(pMapping.sourceColumn)
			|| pPolicy.isSensitive(pMapping.fieldName);
		// lot G2 (2026-10-17): the values are read whatever the data policy says - what the policy withholds is what reaches the MODEL (shapesFor filters), the
		// deterministic guards of capability.ConstraintSanity run here, inside the instance (the same stance as the normalisation lot's value profile)
		final QualitySuggester.ColumnQuery read = QualitySuggester.distinctValues(pSession, landingRef, pMapping.fieldName, null,
			QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES);
		final List<Detectors.ValueCount> values = read.values;
		// staging polish (2026-10-17): above mdm.factory.profile.maxRowsFullScan rows the figures are those of the table's first rows (the fill RATE holds on
		// the sample, the row count is the counted total, the distinct count is unknown - a sample's would be a lower bound read as a fact)
		final QualitySuggester.ExactAggregates aggregates = QualitySuggester.boundedAggregates(pSession, landingRef, pMapping.fieldName);
		final Double fillRatePercent = aggregates.rows > 0 ? Double.valueOf(Math.round(aggregates.filled * 10000.0 / aggregates.rows) / 100.0) : null;
		return new CapabilityLlmPass.AttributeInfo(pAttribute.name, pAttribute.dataType, pAttribute.maxLength, Long.valueOf(aggregates.totalRows()), fillRatePercent,
			aggregates.sampled() ? null : Long.valueOf(aggregates.distinctCount), CapabilityLlmSuggester.shapesFor(pLevel, sensitive, values), cleaningSteps,
			qualityRules, null, false, CapabilityLlmSuggester.evidenceOf(values, read.sampledRows));
	}

	/** The sampled values of a column as the guards' evidence (the empty ones dropped, null when nothing was read). */
	static ValueEvidence evidenceOf(final List<Detectors.ValueCount> pValues)
	{
		return CapabilityLlmSuggester.evidenceOf(pValues, 0L);
	}

	/** Same, for values read on the first {@code pSampleRows} rows of a big table (0: the whole table). */
	static ValueEvidence evidenceOf(final List<Detectors.ValueCount> pValues, final long pSampleRows)
	{
		if (pValues == null || pValues.isEmpty())
		{
			return null;
		}
		final List<ValueEvidence.Weighted> out = new ArrayList<ValueEvidence.Weighted>();
		for (final Detectors.ValueCount value : pValues)
		{
			if (value.value != null && !value.value.isEmpty())
			{
				out.add(new ValueEvidence.Weighted(value.value, value.count));
			}
		}
		return out.isEmpty() ? null : ValueEvidence.sampled(out, pSampleRows);
	}

	/**
	 * Lot G2: the evidence of an attribute whose EVERY mapping is a constant pipeline (at least one mapping), or null. Structural: reads the pipelines, never a name.
	 */
	static ValueEvidence constantEvidence(final List<QualitySuggester.MappingRow> pAllMappings, final String pAttributeName)
	{
		if (pAllMappings == null)
		{
			return null;
		}
		final List<String> constants = new ArrayList<String>();
		for (final QualitySuggester.MappingRow mapping : pAllMappings)
		{
			if (!pAttributeName.equals(mapping.attributeName))
			{
				continue;
			}
			final String constant = ValueEvidence.constantOf(mapping.pipeline);
			if (constant == null)
			{
				return null;
			}
			constants.add(constant);
		}
		return constants.isEmpty() ? null : ValueEvidence.constants(constants);
	}

	/**
	 * Lot G2: whether {@code pCandidate} should replace {@code pCurrent} as the mapping an attribute is read from: one that reads its column beats one that
	 * does not ({@code constant(...)}), then the higher confidence wins, a tie goes to the candidate (the order the rows were read in).
	 */
	static boolean preferredMapping(final QualitySuggester.MappingRow pCandidate, final QualitySuggester.MappingRow pCurrent)
	{
		final boolean candidateReads = NormalisationPass.readsColumn(pCandidate.pipeline, pCandidate.sourceColumn);
		final boolean currentReads = NormalisationPass.readsColumn(pCurrent.pipeline, pCurrent.sourceColumn);
		if (candidateReads != currentReads)
		{
			return candidateReads;
		}
		final int candidate = pCandidate.confidence == null ? 50 : pCandidate.confidence.intValue();
		final int current = pCurrent.confidence == null ? 50 : pCurrent.confidence.intValue();
		return candidate >= current;
	}

	/**
	 * The phone mix of {@code pAttributeName} over EVERY mapping that feeds it (one per source), read from the landing columns' distinct values -
	 * formats and shares of rows, computed in Java. Null under {@code NONE} (nothing about the values goes to the model), when no mapping can be read
	 * or the attribute has fewer than one readable source. A sensitive column is measured too: only the format sentence goes, never a value.
	 */
	private static NormalisationProfiler.PhoneMix phoneMix(final Session pSession, final Adaptation pFactory, final String pAttributeName,
		final List<QualitySuggester.MappingRow> pAllMappings, final String pLandingDataspace, final Map<String, QualitySuggester.LandingTableRef> pLandingTableCache,
		final LlmDataPolicy.Level pLevel)
	{
		if (pLevel == LlmDataPolicy.Level.NONE || pAllMappings == null || pLandingDataspace == null || pLandingDataspace.trim().isEmpty())
		{
			return null;
		}
		final List<NormalisationProfile> profiles = new ArrayList<NormalisationProfile>();
		for (final QualitySuggester.MappingRow mapping : pAllMappings)
		{
			if (!pAttributeName.equals(mapping.attributeName))
			{
				continue;
			}
			try
			{
				final QualitySuggester.LandingTableRef ref = QualitySuggester.landingTableRef(pFactory, pLandingDataspace, mapping, pLandingTableCache);
				if (ref != null)
				{
					final QualitySuggester.ColumnQuery read = QualitySuggester.distinctValues(pSession, ref, mapping.fieldName, null,
						QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES);
					profiles.add(NormalisationProfiler.profile(read.values, -1L).markRowSample(read.sampledRows));
				}
			}
			catch (final RuntimeException ex)
			{
				// best effort: a column that cannot be read adds nothing
			}
		}
		return profiles.isEmpty() ? null : NormalisationProfiler.phoneMix(profiles);
	}

	/** Whether the value shapes of a column may reach the model: never under {@code NONE}, never for a column of the sensitive list. */
	static boolean mayShowShapes(final LlmDataPolicy.Level pLevel, final boolean pSensitive)
	{
		return pLevel != LlmDataPolicy.Level.NONE && !pSensitive;
	}

	/** The shapes of {@code pValues} for the prompt, or none when {@link #mayShowShapes} says they must not go. */
	static List<CapabilityLlmPass.ShapeCount> shapesFor(final LlmDataPolicy.Level pLevel, final boolean pSensitive, final List<Detectors.ValueCount> pValues)
	{
		return CapabilityLlmSuggester.mayShowShapes(pLevel, pSensitive) ? CapabilityLlmSuggester.shapesOf(pValues) : Collections.<CapabilityLlmPass.ShapeCount>emptyList();
	}

	/** Distinct shapes of {@code pValues}, counts summed across every raw value sharing the same shape, most frequent first, capped - same approach as {@code ebx.QualityLlmSuggester.shapesOf}, a separate copy per this codebase's own "each caller keeps its own private reader" convention. */
	private static List<CapabilityLlmPass.ShapeCount> shapesOf(final List<Detectors.ValueCount> pValues)
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
		Collections.sort(sorted, new Comparator<Map.Entry<String, Long>>()
		{
			@Override
			public int compare(final Map.Entry<String, Long> pLeft, final Map.Entry<String, Long> pRight)
			{
				return pRight.getValue().compareTo(pLeft.getValue());
			}
		});
		final List<CapabilityLlmPass.ShapeCount> out = new ArrayList<CapabilityLlmPass.ShapeCount>();
		for (final Map.Entry<String, Long> entry : sorted)
		{
			if (out.size() >= CapabilityLlmSuggester.MAX_SHAPES_SHOWN)
			{
				break;
			}
			out.add(new CapabilityLlmPass.ShapeCount(entry.getKey(), entry.getValue().longValue()));
		}
		return out;
	}

	/** {@code kind} (+ {@code argument} when present) of every ACCEPTED-or-APPLIED {@code MappingStep} of this mapping - metadata only, never a value. Same shape {@code ebx.MatchPlanLlmSuggester.cleaningSteps} already reads, a separate copy per this codebase's own convention. */
	private static void addCleaningSteps(final Adaptation pFactory, final String pMappingId, final List<String> pOut)
	{
		final RequestResult stepRows = pFactory.getTable(FactoryPaths.MAPPING_STEP).createRequestResult("./mapping=" + CatalogReader.quote(pMappingId));
		try
		{
			for (Adaptation stepRow = stepRows.nextAdaptation(); stepRow != null; stepRow = stepRows.nextAdaptation())
			{
				if (TechnicalKeyRule.isAcceptedLike(stepRow.getString(FactoryPaths.MappingStep.STATUS)))
				{
					CapabilityLlmSuggester.addEvidence(pOut, stepRow.getString(FactoryPaths.MappingStep.KIND), CapabilityLlmSuggester.text(stepRow, FactoryPaths.MappingStep.ARGUMENT));
				}
			}
		}
		finally
		{
			stepRows.close();
		}
	}

	/**
	 * {@code kind} (+ {@code argument} when present) of every {@code QualityRule} of this attribute already
	 * in {@code pScope} - metadata only, never a value. Same shape {@code ebx.MatchPlanLlmSuggester.qualityRules}
	 * already reads.
	 * <p>
	 * Live fix (bug 8, docs/STAGING-PROPOSAL.md "live fixes"): before this, only ACCEPTED-or-APPLIED rules
	 * were shown - correct for {@link MappingScope#ACCEPTED_ONLY} (unchanged here, task brief), but wrong for
	 * {@link MappingScope#PROPOSAL}: right after the one-click "Propose the staging" run, the QUALITY stage
	 * already wrote its own PROPOSED rules before CAPABILITY runs (see {@code ebx.StagingProposalRun}'s own
	 * stage order), and this pass could not see them - measured live on project Personnes: the quality pass
	 * proposed a PATTERN rule on {@code email}, then the capability pass, blind to it, proposed {@code
	 * Fasttrack.lib.validator.email.isValid} as a SCRIPT_CONSTRAINT on the SAME attribute - the same check
	 * twice (same for {@code phone}). {@code pScope.includes(status)} is exactly "is this row visible to a
	 * pass running with this scope" - the same test every mapping already goes through - so a PROPOSED rule
	 * is now shown under {@link MappingScope#PROPOSAL} too, and {@link MappingScope#ACCEPTED_ONLY} keeps
	 * seeing ACCEPTED/APPLIED only, exactly as before.
	 */
	private static void addQualityRules(final Adaptation pFactory, final String pAttributeId, final MappingScope pScope, final List<String> pOut)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.QUALITY_RULE).createRequestResult("./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (pScope.includes(row.getString(FactoryPaths.QualityRule.STATUS)))
				{
					CapabilityLlmSuggester.addEvidence(pOut, row.getString(FactoryPaths.QualityRule.KIND), CapabilityLlmSuggester.text(row, FactoryPaths.QualityRule.ARGUMENT));
				}
			}
		}
		finally
		{
			rows.close();
		}
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

	private static List<CapabilityLlmPass.ExistingSuggestion> readExisting(final Adaptation pFactory, final String pEntityId)
	{
		final List<CapabilityLlmPass.ExistingSuggestion> out = new ArrayList<CapabilityLlmPass.ExistingSuggestion>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(new CapabilityLlmPass.ExistingSuggestion(row.getString(FactoryPaths.CapabilitySuggestion.NATURE),
					CapabilityLlmSuggester.text(row, FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY), CapabilityLlmSuggester.text(row, FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION),
					CapabilityLlmSuggester.text(row, FactoryPaths.CapabilitySuggestion.CONNECTOR_ID), CapabilityLlmSuggester.text(row, FactoryPaths.CapabilitySuggestion.CONNECTOR_OPERATION),
					row.getString(FactoryPaths.CapabilitySuggestion.STATUS)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Same shape {@code MatchPlanLlmSuggester.readEntities}/{@code readAttributes} already read - a separate copy per this codebase's own "each caller keeps its own private reader" convention. */
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
				row.getString(FactoryPaths.StagingEntity.STATUS), CapabilityLlmSuggester.readAttributes(pFactory, entityId, entityIdToName), memberNames));
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
					row.getString(FactoryPaths.StagingAttribute.STATUS)));
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

	// ---------------------------------------------------------------------------------------------- script inventory

	/**
	 * Walks {@code list_scripts} straight from the {@code library} category (its idePath is verified
	 * literally {@code "library"} - coordinator capture, 2026-09-21: no root probe needed any more),
	 * recurses under every {@code script_package}/{@code isPackaged} folder (today {@code library/Fasttrack}
	 * -&gt; {@code lib}/{@code validator} -&gt; scripts), fetches each real script with {@code
	 * get_script(scriptIdePath: ..., includeSource: true)} - the real parameter name, coordinator capture -
	 * and parses its exported functions ({@link ScriptInventory#parseExportedFunctions}). Bounded ({@link
	 * #MAX_FOLDER_DEPTH}/{@link #MAX_LIBRARY_ITEMS_LISTED}/{@link #MAX_LIBRARY_SCRIPTS_FETCHED}) - every cap
	 * hit, and every call failure, is appended to {@code pNotes} rather than aborting the route (the
	 * scripting server is OPTIONAL, {@code McpServers.OPTIONAL}).
	 */
	private static List<CapabilityLlmPass.FunctionRef> buildScriptInventory(final Session pSession, final Adaptation pFactory, final List<String> pNotes)
	{
		final List<CapabilityLlmPass.FunctionRef> out = new ArrayList<CapabilityLlmPass.FunctionRef>();
		final McpClient client;
		try
		{
			client = McpServers.client(pSession, pFactory, McpServers.SCRIPTING);
		}
		catch (final OperationException ex)
		{
			pNotes.add("scripting server not usable: " + ex.getMessage() + " - no SCRIPT_CONSTRAINT will ever be proposed this run.");
			return out;
		}

		final List<ScriptInventory.ScriptRef> scripts = new ArrayList<ScriptInventory.ScriptRef>();
		final int[] itemsListed = { 0 };
		CapabilityLlmSuggester.collectScripts(client, "library", 0, scripts, itemsListed, pNotes);
		if (itemsListed[0] >= CapabilityLlmSuggester.MAX_LIBRARY_ITEMS_LISTED)
		{
			pNotes.add("library listing capped at " + CapabilityLlmSuggester.MAX_LIBRARY_ITEMS_LISTED + " items: some scripts may not be inventoried.");
		}

		int fetched = 0;
		for (final ScriptInventory.ScriptRef script : scripts)
		{
			if (fetched >= CapabilityLlmSuggester.MAX_LIBRARY_SCRIPTS_FETCHED)
			{
				pNotes.add("library script fetch capped at " + CapabilityLlmSuggester.MAX_LIBRARY_SCRIPTS_FETCHED + ": " + (scripts.size() - fetched)
					+ " remaining script(s) not inventoried.");
				break;
			}
			fetched++;
			try
			{
				final ObjectNode args = CapabilityLlmSuggester.MAPPER.createObjectNode();
				args.put("scriptIdePath", script.idePath);
				args.put("includeSource", true);
				final McpClient.ToolResult result = client.callTool("get_script", args);
				if (result.error || result.json == null)
				{
					pNotes.add("get_script(" + script.idePath + ") failed: " + CapabilityLlmSuggester.shortText(result.text));
					continue;
				}
				final ScriptInventory.ScriptDetails details = ScriptInventory.parseScript(result.json);
				if (details.source == null)
				{
					pNotes.add(script.idePath + ": " + details.sourceNote);
					continue;
				}
				for (final ScriptInventory.FunctionInfo function : ScriptInventory.parseExportedFunctions(details.source))
				{
					final List<CapabilityLlmPass.ParamRef> parameters = new ArrayList<CapabilityLlmPass.ParamRef>();
					for (final ScriptInventory.Parameter parameter : function.parameters)
					{
						parameters.add(new CapabilityLlmPass.ParamRef(parameter.name, parameter.type));
					}
					out.add(new CapabilityLlmPass.FunctionRef(script.idePath, details.importName, details.label != null ? details.label : script.label,
						details.description, function.name, parameters, function.returnType, function.documentation));
				}
			}
			catch (final McpException ex)
			{
				pNotes.add("get_script(" + script.idePath + ") MCP call failed: " + ex.getMessage());
			}
		}
		return out;
	}

	private static void collectScripts(final McpClient pClient, final String pFolderIdePath, final int pDepth, final List<ScriptInventory.ScriptRef> pOut,
		final int[] pItemsListed, final List<String> pNotes)
	{
		if (pDepth >= CapabilityLlmSuggester.MAX_FOLDER_DEPTH || pItemsListed[0] >= CapabilityLlmSuggester.MAX_LIBRARY_ITEMS_LISTED)
		{
			return;
		}
		String cursor = null;
		do
		{
			final ScriptInventory.ListScriptsResult page;
			try
			{
				page = CapabilityLlmSuggester.listScripts(pClient, pFolderIdePath, cursor);
			}
			catch (final McpException ex)
			{
				pNotes.add("scripting list_scripts(" + pFolderIdePath + ") failed: " + ex.getMessage());
				return;
			}
			for (final ScriptInventory.ScriptRef item : page.items)
			{
				pItemsListed[0]++;
				if (pItemsListed[0] > CapabilityLlmSuggester.MAX_LIBRARY_ITEMS_LISTED)
				{
					return;
				}
				if (item.isFolder())
				{
					CapabilityLlmSuggester.collectScripts(pClient, item.idePath, pDepth + 1, pOut, pItemsListed, pNotes);
				}
				else
				{
					pOut.add(item);
				}
			}
			cursor = page.hasMore ? page.nextCursor : null;
		}
		while (cursor != null && !cursor.isEmpty());
	}

	private static ScriptInventory.ListScriptsResult listScripts(final McpClient pClient, final String pFolderIdePath, final String pCursor) throws McpException
	{
		final ObjectNode args = CapabilityLlmSuggester.MAPPER.createObjectNode();
		args.put("folderIdePath", pFolderIdePath);
		args.put("limit", CapabilityLlmSuggester.LIST_SCRIPTS_PAGE_SIZE);
		if (pCursor != null && !pCursor.isEmpty())
		{
			args.put("cursor", pCursor);
		}
		final McpClient.ToolResult result = pClient.callTool("list_scripts", args);
		if (result.error || result.json == null)
		{
			throw new McpException(0, "list_scripts(" + pFolderIdePath + ") answered an error: " + CapabilityLlmSuggester.shortText(result.text));
		}
		return ScriptInventory.parseListScripts(result.json);
	}

	// --------------------------------------------------------------------------------------------- service inventory

	/** {@code connectors_list}-derived connector catalogue plus the {@link CapabilityLlmPass.ConnectorDescriber} that lazily calls {@code connectors_describe} for one connector at a time - built ONCE per route call, shared by every entity's {@link CapabilityLlmPass.Session}. */
	private static final class ServiceCatalogue
	{
		final List<CapabilityLlmPass.ConnectorSummary> connectors;
		final CapabilityLlmPass.ConnectorDescriber describer;

		ServiceCatalogue(final List<CapabilityLlmPass.ConnectorSummary> pConnectors, final CapabilityLlmPass.ConnectorDescriber pDescriber)
		{
			this.connectors = pConnectors;
			this.describer = pDescriber;
		}
	}

	/**
	 * {@code connectors_list} ONCE to build the compact connector catalogue - {@code connectors_describe} is
	 * never called here (coordinator, 2026-09-21, item 2: it refuses a connector whose configuration is
	 * invalid, e.g. no secret set, so it cannot be the source of the inventory itself; and it would repeat
	 * the earlier bug of an alphabetical cap that spent itself on unconfigured connectors and never reached
	 * real ones later in the list). A connector is offered at all only when it is {@code active} and its
	 * TYPE has at least one read/search operation per {@code connectors_list} ({@link
	 * ServiceInventory#readSearchOperationNames}) - nothing worth suggesting otherwise. The per-connector
	 * {@code connectors_describe} call is made LAZILY, later, only for the one connector the model itself
	 * asks about via {@link CapabilityLlmPass.Session#getServiceDetails} - see {@link #describerFor}.
	 */
	private static ServiceCatalogue buildServiceCatalogue(final Session pSession, final Adaptation pFactory, final List<String> pNotes)
	{
		final McpClient client;
		try
		{
			client = McpServers.connectors(pSession, pFactory);
		}
		catch (final OperationException ex)
		{
			pNotes.add("ebx-connectors server not usable: " + ex.getMessage() + " - no EXTERNAL_SERVICE will ever be proposed this run.");
			return new ServiceCatalogue(Collections.<CapabilityLlmPass.ConnectorSummary>emptyList(),
				CapabilityLlmSuggester.unusableDescriber("ebx-connectors server not usable: " + ex.getMessage()));
		}

		final ServiceInventory.ListResult listResult;
		try
		{
			final McpClient.ToolResult result = client.callTool("connectors_list", CapabilityLlmSuggester.MAPPER.createObjectNode());
			if (result.error || result.json == null)
			{
				pNotes.add("connectors_list failed: " + CapabilityLlmSuggester.shortText(result.text) + " - no EXTERNAL_SERVICE will ever be proposed this run.");
				return new ServiceCatalogue(Collections.<CapabilityLlmPass.ConnectorSummary>emptyList(),
					CapabilityLlmSuggester.unusableDescriber("connectors_list failed: " + CapabilityLlmSuggester.shortText(result.text)));
			}
			listResult = ServiceInventory.parseConnectorsList(result.json);
		}
		catch (final McpException ex)
		{
			pNotes.add("connectors_list MCP call failed: " + ex.getMessage() + " - no EXTERNAL_SERVICE will ever be proposed this run.");
			return new ServiceCatalogue(Collections.<CapabilityLlmPass.ConnectorSummary>emptyList(),
				CapabilityLlmSuggester.unusableDescriber("connectors_list MCP call failed: " + ex.getMessage()));
		}

		// service-choice lot (coordinator, 2026-09-21, item 3): "an older Connectors build does not send them
		// [pricing/pricingNote] => UNKNOWN, and the response says pricing is not available from this Connectors
		// build" - parsed as OPTIONAL, never a hard failure either way (ServiceInventory.parseConnectorsList
		// already defaults every connector to UNKNOWN when absent; this note only explains WHY, once, for the
		// whole run, rather than leaving every connector's own UNKNOWN unexplained)
		if (!listResult.pricingReported)
		{
			pNotes.add("pricing is not available from this Connectors build (connectors_list reported no 'pricing' key on any connector) - every connector "
				+ "shows UNKNOWN; this never blocks a suggestion, UNKNOWN is neither preferred nor penalised.");
		}

		final List<CapabilityLlmPass.ConnectorSummary> connectors = new ArrayList<CapabilityLlmPass.ConnectorSummary>();
		for (final ServiceInventory.ConnectorRef connector : listResult.connectors)
		{
			if (!connector.active)
			{
				continue;
			}
			final List<String> operationNames = ServiceInventory.readSearchOperationNames(connector, listResult);
			if (operationNames.isEmpty())
			{
				continue;
			}
			connectors.add(new CapabilityLlmPass.ConnectorSummary(connector.id, connector.label, connector.type, connector.isReady(),
				connector.isReady() ? null : "secrets not set for authType " + connector.authType, operationNames, connector.pricing, connector.pricingNote));
		}
		return new ServiceCatalogue(connectors, CapabilityLlmSuggester.describerFor(client));
	}

	/**
	 * The one seam that ever calls {@code connectors_describe}, LAZILY, for a single connector the model asked
	 * {@link CapabilityLlmPass.Session#getServiceDetails} about. Never sends {@code operation}/{@code inputs}
	 * ({@link ServiceInventory#describeRequestArgs} builds {@code connector} only - passing {@code operation}
	 * would make Connectors CALL the third-party service, coordinator capture 2026-09-21). A refusal - most
	 * commonly "configuration is invalid: ..." for a connector with no secret/base URL set - is NOT an error:
	 * it becomes {@link CapabilityLlmPass.DescribeOutcome#notConfigured} so the connector stays suggestable
	 * with that exact reason as its readiness detail (coordinator, item 2: "Melissa could verify phones, but
	 * no API key is set" is what a person needs to see, not a dropped connector).
	 */
	private static CapabilityLlmPass.ConnectorDescriber describerFor(final McpClient pClient)
	{
		return new CapabilityLlmPass.ConnectorDescriber()
		{
			@Override
			public CapabilityLlmPass.DescribeOutcome describe(final String pConnectorId)
			{
				try
				{
					final McpClient.ToolResult result = pClient.callTool("connectors_describe", ServiceInventory.describeRequestArgs(pConnectorId));
					if (result.error || result.json == null)
					{
						return CapabilityLlmPass.DescribeOutcome.notConfigured(CapabilityLlmSuggester.shortText(result.text));
					}
					final ServiceInventory.ConnectorDescription description = ServiceInventory.parseDescribeConnector(result.json);
					final List<ServiceInventory.DescribedOperation> operations = new ArrayList<ServiceInventory.DescribedOperation>();
					for (final ServiceInventory.DescribedOperation operation : description.operations)
					{
						if (operation.isCandidateKind())
						{
							operations.add(new ServiceInventory.DescribedOperation(operation.name, operation.kind,
								TextLimits.truncate(operation.description, CapabilityLlmSuggester.SERVICE_DESCRIPTION_MAX_LENGTH), operation.requiredInputs,
								operation.optionalInputs));
						}
					}
					return CapabilityLlmPass.DescribeOutcome.configured(operations);
				}
				catch (final McpException ex)
				{
					return CapabilityLlmPass.DescribeOutcome.notConfigured(ex.getMessage());
				}
			}
		};
	}

	/** A {@link CapabilityLlmPass.ConnectorDescriber} that always answers "not configured" with {@code pReason} - used when the {@code ebx-connectors} server or {@code connectors_list} itself is unusable, so no live call is ever attempted. */
	private static CapabilityLlmPass.ConnectorDescriber unusableDescriber(final String pReason)
	{
		return new CapabilityLlmPass.ConnectorDescriber()
		{
			@Override
			public CapabilityLlmPass.DescribeOutcome describe(final String pConnectorId)
			{
				return CapabilityLlmPass.DescribeOutcome.notConfigured(pReason);
			}
		};
	}

	private static String join(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pValues.size(); i++)
		{
			if (i > 0)
			{
				out.append(", ");
			}
			out.append(pValues.get(i));
		}
		return out.toString();
	}

	// ---------------------------------------------------------------------------------------------------------- misc

	private static String text(final Adaptation pRow, final Path pPath)
	{
		final String value = pRow.getString(pPath);
		return value == null ? "" : value;
	}

	private static void putStrings(final ObjectNode pOut, final String pKey, final List<String> pValues)
	{
		final ArrayNode array = pOut.putArray(pKey);
		for (final String value : pValues)
		{
			array.add(value);
		}
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
