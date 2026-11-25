package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.PublishDecision;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.master.MameAddonDecision;
import com.on.ebx.factory.master.MasterFacetRepair;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.MasterModelState;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.master.MasterStagingReadiness;
import com.on.ebx.factory.master.MasterTargetDecision;
import com.on.ebx.factory.master.MasterToolbars;
import com.on.ebx.factory.master.MembershipConsistencyCheck;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.StagingModelDiff;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.instance.HomeCreationSpec;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Turns the PROMOTED staging entities of a project (`master.MasterModel`'s own promotion rule: an ACCEPTED-or-
 * {@code APPLIED} staging entity unless it is a member of another one) into a real GREENFIELD master zone in
 * EBX: one data model {@code <ProjectCode>_Master} with one table per promoted entity, one dataspace
 * {@code <ProjectCode><masterSuffix>} ({@code FactorySettings.masterSuffix}, default {@code _Master} -
 * {@link MasterNames}), one dataset {@code Master}. Mirrors {@code ebx.StagingGenerator}'s proven shape
 * exactly (build -&gt; patch -&gt; embed -&gt; publish -&gt; create dataspace -&gt; create
 * dataset, each state-driven and resumable through its own {@code PipelineStep}), with two structural
 * differences documented on {@link MasterModel} itself (auto-increment {@code id} primary key, no proposal
 * write-back to {@code StagingEntity}/{@code StagingAttribute} - this generator does not "accept" anything,
 * it only reads what staging already accepted) and one behavioural difference specific to this class: the
 * {@code Project.masterDataspace}/{@code masterDataset} guard below.
 * <p>
 * <b>Refuses when the project has no loaded staging entity</b> - decided by {@link MasterStagingReadiness}
 * (pure, unit-tested), never from {@code PipelineSteps.isDone(..., "STAGING_LOAD", "")} alone: a coordinator
 * review, 2026-09-20, of a live incident found that check ALWAYS answered {@code false} for a project-scope
 * step (a separate bug in the {@code ./scope=''} predicate, fixed in {@code PipelineSteps.scopeEquals} - see
 * its own Javadoc), and that step HISTORY should never be the only signal anyway (a row can be missing or
 * cleaned up). {@link MasterStagingReadiness#decide} accepts either the step's own {@code DONE} status or
 * direct evidence on the promoted entities themselves ({@code StagingEntity.lastLoadWritten > 0} for at least
 * one), and the refusal names exactly which promoted entities have neither.
 * <p>
 * <b>Never overwrites a {@code Project.masterDataspace}/{@code masterDataset} that is not this generator's own
 * value, silently.</b> The demo project on this instance carries hand-typed test values
 * ({@code TestMasterDataspace}/{@code TestMasterDataset}, `docs/MASTER-ZONE.md`) that must never be mistaken
 * for a dataspace this class actually built. {@link MasterTargetDecision#decide} (pure, unit-tested) compares
 * what this class WOULD generate ({@link MasterNames#dataspaceKey}/{@link MasterNames#DATASET}) against
 * whatever is already stored:
 * <ul>
 * <li>blank, or already exactly the computed pair (a prior run of this same generator) -&gt; proceeds normally,
 * generation always happens under the COMPUTED names (this class never reads, writes, or generates INTO a
 * foreign dataspace - only {@code Project}'s own two pointer fields are ever at stake here).</li>
 * <li>set to anything else -&gt; refused by default (409), the message naming what was found, what would be
 * generated, and that {@code ?overwriteTarget=true} replaces those two FIELDS on {@code Project} - nothing is
 * ever deleted, on this dataspace or the found one. Passing it makes this class replace the two fields with
 * the computed names and proceed - a deliberately narrow, explicit-confirmation-only override, not a general
 * "adopt this dataspace as the master zone" mechanism.</li>
 * </ul>
 * <p>
 * <b>Regeneration</b> (a later-accepted entity promoted after the master model was already published, the
 * mirror of {@code StagingGenerator}'s own round-4 regeneration): drives {@code staging.StagingModelDiff}'s
 * generalised {@code diff(existing, tree, operations)} overload directly - see that class's own Javadoc for why
 * it no longer depends on {@code StagingModel} for this. One thing the generic diff engine cannot know about a
 * brand-new master table, keyed by the bare {@code tablePath} rather than a field path, so the engine's own
 * per-FIELD facet association never sees it: the {@code id} field's auto-increment facets (no {@code
 * maxLength}/{@code mandatory} token the engine understands). {@link #runRegeneration} appends {@link
 * MasterModel.Result#autoIncrementOps} for every entity the diff found as a genuinely NEW table (one not already
 * present in the live model), after the diff's own additive operations, so a regenerated table's key is never
 * left without its auto-increment step - never re-applied to an already-existing table (see
 * {@link #runRegeneration}'s own comment on that skip).
 * <p>
 * <b>The table's own {@code osd:table/defaultLabel}</b> ({@link MasterModel.Result#defaultLabelByTable}, when
 * {@link MasterModel} found an eligible display attribute) is applied the SAME WAY, one {@code
 * execute_operation(set_default_label)} call per genuinely new table, right after a successful patch and
 * before publishing ({@link #applyDefaultLabels}) - NEVER as a {@code set_element_property} op folded into the
 * patch: MEASURED LIVE, 2026-09-22, {@code apply_data_model_patch} refuses {@code "defaultLabel"} as a
 * {@code set_element_property} property outright and aborts the WHOLE patch mid-way, leaving every later op
 * (including the very autoIncrement facets described above) unapplied - `docs/MASTER-ZONE.md` &sect;10. Best
 * effort by design: a failure to set a label is cosmetic and must never fail the generation itself.
 * <p>
 * <b>A table that already exists but never actually got those two facets - the same &sect;10 incident, closed
 * two ways.</b> First, {@link #runModelStep} no longer trusts {@code get_publication} alone to mean "the
 * factory published this": a DMA publication object survives {@code delete_data_model} and re-attaches itself
 * to a same-named model rebuilt after a {@code master/reset} (see {@code ebx.MasterReset}'s own Javadoc), so a
 * publication the SERVER reports {@code found} is only trusted when the factory's OWN {@code PipelineStep}
 * bookkeeping also shows a {@code MASTER_MODEL} step {@code DONE} - {@code MasterReset} moves every earlier
 * one to {@code RESET}, so a {@code DONE} row can only mean the factory itself finished publishing since the
 * project's last reset. Without it, the model is treated as {@code MODEL_ONLY} even though a publication
 * exists, which routes back through the FULL patch/publish path (every facet, every entity) rather than the
 * diff-based {@link #runRegeneration} - the exact case at fault in the incident, where the diff-only path's
 * "genuinely new table" gate never revisited an already-existing, half-built table. Second, as a genuine
 * defense in depth for the {@link #runRegeneration} path itself (a table can end up half-built between two
 * resets for other reasons too, not only this one): {@link MasterFacetRepair} independently checks every
 * PROMOTED table that ALREADY EXISTS on the live model for a missing auto-increment facet or defaultLabel,
 * regardless of whether the diff considers it "new" this run - see that class' own Javadoc.
 * <p>
 * <b>Table and record toolbars, for direct access to the factory's own features (the product owner, 2026-09-22).</b>
 * {@link #applyToolbars}, called right after {@link #applyDefaultLabels} and before publishing, in both
 * {@link #runModelStep}'s CREATE path and both branches of {@link #runRegeneration}: writes {@link
 * MasterToolbars}'s three toolbar definitions into {@code ebx-dma/<model>/root/toolbarNode} ({@link
 * DmaToolbarWriter}, the internal Java API - see that class' own Javadoc) and binds all three to EVERY
 * promoted table with {@code execute_operation(set_toolbars)}, so a steward reaches "Record lineage" (plus the
 * project's Cockpit and Proposal review) directly from a table's toolbar or a record's own, not only through
 * the generic Actions menu.
 * <p>
 * <b>A toolbar is part of the model DEFINITION - a dataset only sees it through a publication</b>, the same
 * rule as every other model change {@link #runModelStep}/{@link #runRegeneration} already follow (coordinator
 * review, 2026-09-22, "fact 2"). The CREATE path and {@link #runRegeneration}'s REGENERATE branch already
 * publish/republish unconditionally right after {@link #applyToolbars} runs, so nothing extra is needed there;
 * {@link #runRegeneration}'s {@code UP_TO_DATE} branch is the one place that would otherwise skip publishing
 * entirely - it now checks {@link ToolbarStepResult#needsRepublish} (true when a toolbar row was newly created
 * or at least one {@code set_toolbars} binding call answered OK) and, if set, calls the shared {@link
 * #republish} helper itself (recording {@code detail.toolbarsPublished=true} and a note), while the step's own
 * {@code mode} stays {@code UP_TO_DATE} - nothing else about that decision changes, only whether a republish
 * happens alongside it.
 * <p>
 * Best effort otherwise, same contract as {@link #applyDefaultLabels}: a toolbar write/binding failure is
 * recorded in {@code detail}/{@code notes}, never thrown - see {@link MasterToolbars}'s own Javadoc for exactly
 * which facts here were read live versus assumed, and `docs/MASTER-ZONE.md`'s toolbars section for the full
 * account.
 * <p>
 * <b>Deployed once and seen writing, 2026-09-22, 15:19-15:22</b> (by the product owner, on {@code Companies_Master}):
 * {@link #applyToolbars} did create the three toolbar roots, wrote the rows back exactly as designed, bound
 * {@code /root/Company}/{@code /root/Contact} at all three locations, and republished - and "Record lineage"
 * rendered and WORKED from every location. Cockpit/Proposal review did NOT (a cross-model service key a
 * master-model toolbar cannot resolve, "No service with key ..." in {@code kernel.log}) - fixed by a later,
 * module-level-services lot the same day, see {@link MasterToolbars}'s own Javadoc for the incident and the
 * fix. That later lot was itself constrained to read-only MCP tools again and has NOT been redeployed or
 * seen running - `docs/MASTER-ZONE.md` &sect;12 has the full, dated account either way.
 * <p>
 * EBX writes ({@code PipelineStep}, {@code Project}) act with the rights of the session given to
 * {@link #generate}. The MCP calls that build/publish the model and create the dataset act as the factory's one
 * agent account ({@code McpServers}), exactly like {@code StagingGenerator}.
 */
public final class MasterGenerator
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String STEP_MASTER_MODEL = "MASTER_MODEL";
	private static final String STEP_MASTER_DATASET = "MASTER_DATASET";
	private static final String STEP_STAGING_LOAD = "STAGING_LOAD";
	/** Both steps of this generator run at project scope, like {@code StagingGenerator}'s own staging steps. */
	private static final String SCOPE = "";

	private MasterGenerator()
	{
	}

	/** Master-live lot (2026-09-27): the phase notes of the run generating on THIS thread (same device as StagingGenerator's), always reset in the overload's finally. */
	private static final ThreadLocal<com.on.ebx.factory.landing.ProgressNote> NOTE = new ThreadLocal<com.on.ebx.factory.landing.ProgressNote>();

	private static void note(final String pText)
	{
		final com.on.ebx.factory.landing.ProgressNote note = MasterGenerator.NOTE.get();
		if (note != null)
		{
			note.note(pText);
		}
	}

	/** Same as {@link #generate(Session, Adaptation, String, boolean)}, saying each phase to {@code pNote} (step 5's running screen). */
	public static ObjectNode generate(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pOverwriteTarget,
		final com.on.ebx.factory.landing.ProgressNote pNote) throws OperationException
	{
		MasterGenerator.NOTE.set(pNote);
		try
		{
			return MasterGenerator.generate(pSession, pFactory, pProjectCode, pOverwriteTarget);
		}
		finally
		{
			MasterGenerator.NOTE.remove();
		}
	}

	public static ObjectNode generate(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pOverwriteTarget)
		throws OperationException
	{
		// audit F-15: the master model and dataset are the MASTER resource's; the queued job already holds it (MasterBuildRun), a REST call does not - see EngineClaim
		final EngineClaim claim = EngineClaim.enter(pFactory, pSession, pProjectCode, "MASTER_GENERATE_DIRECT", FactoryJobs.MASTER);
		try
		{
			return MasterGenerator.generateClaimed(pSession, pFactory, pProjectCode, pOverwriteTarget);
		}
		finally
		{
			claim.release();
		}
	}

	private static ObjectNode generateClaimed(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pOverwriteTarget)
		throws OperationException
	{
		final Adaptation project = MasterGenerator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();

		// docs/MASTER-ZONE.md, 2026-09-21 incident: Company (origin LLM, accepted) had memberEntities empty even
		// though its rationale still read "Unifies Supplier, Account: ..." - the one-time backfill had only ever
		// run inside StagingLlmSuggester, and nobody had reason to call it again on an already-accepted project.
		// Run it here, always, before reading anything this generator's own decisions depend on.
		MemberEntitiesBackfill.run(pSession, pFactory, projectId);

		// structural evidence beats text: even after the backfill above, an LLM-origin accepted entity with no
		// recorded members but whose attributes are mapped from the same landing table(s) as another accepted
		// entity looks exactly like an unresolved canonical unification - refuse rather than silently promote
		// it (and the entities it should have absorbed) to master. See MembershipConsistencyCheck's own Javadoc.
		final MembershipConsistencyCheck.Result consistency = MembershipConsistencyCheck.check(MasterGenerator.readMembershipCandidates(pFactory, projectId));
		if (!consistency.consistent)
		{
			throw EngineRefusal.coded("conflict.stagingMembership", "Project " + pProjectCode + " has an inconsistent staging membership, refusing to generate the master model: "
				+ consistency.refusals, pProjectCode);
		}

		final MasterModel.Result modelResult = MasterModel.buildFromStaging(MasterGenerator.readEntities(pFactory, projectId));
		if (modelResult.builtEntities.isEmpty())
		{
			throw EngineRefusal.coded("missing.noPromotedEntity", "Project " + pProjectCode + " has no staging entity promoted to the master zone: every accepted entity is "
				+ "either not yet staged or a member of another accepted (canonical) entity. " + modelResult.keptInStagingOnly, pProjectCode);
		}

		// "staging is loaded" is never trusted from PipelineStep history alone (a row can be missing or cleaned
		// up) - MasterStagingReadiness accepts either the step's own DONE status or direct evidence on the
		// promoted entities themselves (StagingEntity.lastLoadWritten), and names exactly which ones lack it
		final boolean stagingLoadStepDone = PipelineSteps.isDone(pFactory, projectId, MasterGenerator.STEP_STAGING_LOAD, MasterGenerator.SCOPE);
		final Map<String, Long> lastLoadWrittenByEntity = MasterGenerator.readLastLoadWritten(pFactory, projectId);
		final List<MasterStagingReadiness.EntityLoad> loadEvidence = new ArrayList<MasterStagingReadiness.EntityLoad>();
		for (final String entityName : modelResult.builtEntities)
		{
			final Long written = lastLoadWrittenByEntity.get(entityName);
			loadEvidence.add(new MasterStagingReadiness.EntityLoad(entityName, written == null ? 0L : written.longValue()));
		}
		final MasterStagingReadiness.Decision readiness = MasterStagingReadiness.decide(stagingLoadStepDone, loadEvidence);
		if (!readiness.ready)
		{
			throw EngineRefusal.coded("missing.stagingNotLoaded", "Project " + pProjectCode + " has no loaded staging entity yet (not loaded: " + readiness.notLoaded
				+ "): run POST /projects/" + pProjectCode + "/staging/load first.", pProjectCode);
		}

		final String modelName = MasterNames.modelName(pProjectCode);
		final String masterSuffix = MasterGenerator.masterSuffix(pFactory);
		final String computedDataspaceKey = MasterNames.dataspaceKey(pProjectCode, masterSuffix);
		final String computedDatasetName = MasterNames.DATASET;

		final String existingDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String existingDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		final MasterTargetDecision.Decision targetDecision = MasterTargetDecision.decide(pProjectCode, existingDataspaceKey, existingDatasetName,
			computedDataspaceKey, computedDatasetName, pOverwriteTarget);
		if (!targetDecision.proceed)
		{
			throw EngineRefusal.coded("conflict.regenerateRefused", targetDecision.refusal);
		}

		final ObjectNode out = MasterGenerator.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("masterModel", modelName);
		out.put("masterDataspace", computedDataspaceKey);
		out.put("masterDataset", computedDatasetName);
		final ArrayNode entitiesNode = out.putArray("entitiesPromoted");
		for (final String name : modelResult.builtEntities)
		{
			entitiesNode.add(name);
		}
		final ArrayNode keptNode = out.putArray("keptInStagingOnly");
		for (final String kept : modelResult.keptInStagingOnly)
		{
			keptNode.add(kept);
		}
		final ArrayNode notesNode = out.putArray("notes");
		for (final String note : modelResult.notes)
		{
			notesNode.add(note);
		}
		final ArrayNode fkNode = out.putArray("foreignKeys");
		for (final MasterModel.ForeignKeyInfo fk : modelResult.foreignKeys)
		{
			final ObjectNode line = fkNode.addObject();
			line.put("entity", fk.entityName);
			line.put("attribute", fk.attributeName);
			line.put("field", fk.fieldName);
			line.put("targetEntity", fk.targetEntityName);
		}

		final ModelStepResult modelStep = MasterGenerator.runModelStep(pSession, pFactory, projectId, pProjectCode, modelName, modelResult);
		out.put("mode", modelStep.mode);
		final ArrayNode appliedNode = out.putArray("applied");
		for (final String applied : modelStep.applied)
		{
			appliedNode.add(applied);
		}
		final ArrayNode refusedNode = out.putArray("refused");
		for (final String refused : modelStep.refused)
		{
			refusedNode.add(refused);
		}
		// notesNode was already populated above from modelResult.notes, BEFORE runModelStep ran - appending here
		// (ArrayNode stays mutable after out.putArray returned it) rather than rebuilding it from scratch.
		for (final String defaultLabelNote : modelStep.defaultLabelNotes)
		{
			notesNode.add(defaultLabelNote);
		}
		for (final String toolbarNote : modelStep.toolbarNotes)
		{
			notesNode.add(toolbarNote);
		}
		for (final String derivationNote : modelStep.derivationNotes)
		{
			notesNode.add(derivationNote);
		}
		out.put("masterDatasetStep",
			MasterGenerator.runDatasetStepNoted(pSession, pFactory, projectId, modelName, computedDataspaceKey, computedDatasetName) ? "SKIPPED_ALREADY_DONE" : "DONE");

		MasterGenerator.writeBackProjectPointers(pSession, pFactory, projectId, computedDataspaceKey, computedDatasetName);
		out.put("status", "GENERATED");
		// deliberately NOT written: Project.status stays whatever it already is - matching/merge does not exist yet
		// for this project (a later lot), so LIVE would be misleading (see class Javadoc / the coordinator's brief)
		// the "Record lineage" record-level action's own activation cache (coordinator review, 2026-09-22): a
		// newly generated master zone's own tables must be offered without waiting out MasterZoneIndex's TTL.
		MasterZoneIndex.invalidate();
		return out;
	}

	private static final class ModelStepResult
	{
		final String mode;
		final List<String> applied;
		final List<String> refused;
		/** One line per table {@link #applyDefaultLabels} touched (success or failure) - surfaced in the route's own "notes". */
		final List<String> defaultLabelNotes;
		/** One line per toolbar/table {@link #applyToolbars} touched (success or failure) - surfaced the same way. */
		final List<String> toolbarNotes;
		/** Lot T2 (2026-10-04): one line per applied script derivation the master triggers were (or were not) declared for - surfaced the same way. */
		final List<String> derivationNotes;

		ModelStepResult(final String pMode, final List<String> pApplied, final List<String> pRefused, final List<String> pDefaultLabelNotes,
			final List<String> pToolbarNotes, final List<String> pDerivationNotes)
		{
			this.mode = pMode;
			this.applied = pApplied;
			this.refused = pRefused;
			this.defaultLabelNotes = pDefaultLabelNotes;
			this.toolbarNotes = pToolbarNotes;
			this.derivationNotes = pDerivationNotes;
		}
	}

	// -------------------------------------------------------------------------------------------------- MASTER_MODEL

	private static ModelStepResult runModelStep(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode,
		final String pModelName, final MasterModel.Result pModelResult) throws OperationException
	{
		final String stepId = PipelineSteps.begin(pSession, pFactory, pProjectId, MasterGenerator.STEP_MASTER_MODEL, MasterGenerator.SCOPE);
		final ObjectNode detail = MasterGenerator.MAPPER.createObjectNode();
		try
		{
			final McpClient client = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);

			final ObjectNode getModelArgs = MasterGenerator.MAPPER.createObjectNode();
			getModelArgs.put("data_model_name", pModelName);
			getModelArgs.put("deep", true);
			final McpClient.ToolResult modelLookup = client.callTool("get_data_model", getModelArgs);
			detail.set("existingModel", MasterGenerator.toolDetail(getModelArgs, modelLookup));
			final boolean modelExists = modelLookup.json != null && modelLookup.json.path("found").asBoolean(false);

			final List<String> existingTablePaths = new ArrayList<String>();
			if (modelExists && modelLookup.json.path("tables").isArray())
			{
				for (final JsonNode t : modelLookup.json.path("tables"))
				{
					existingTablePaths.add(t.path("path").asText());
				}
			}

			MasterModelState.Found found = MasterModelState.Found.NOTHING;
			if (modelExists)
			{
				final ObjectNode getPubArgs = MasterGenerator.MAPPER.createObjectNode();
				getPubArgs.put("publication_name", pModelName);
				final McpClient.ToolResult pubLookup = client.callTool("get_publication", getPubArgs);
				detail.set("existingPublication", MasterGenerator.toolDetail(getPubArgs, pubLookup));
				final boolean publicationReportedByServer = pubLookup.json != null && pubLookup.json.path("found").asBoolean(false) && pubLookup.json.hasNonNull("version");
				// docs/MASTER-ZONE.md sec 10, MEASURED LIVE 2026-09-22 12:38: a DMA publication object survives
				// delete_data_model (the data-model MCP server's own operation catalogue has no delete-publication
				// operation at all - list_data_model_publications/set_publication_documentation/create_dataset/
				// versions/export/delete_data_model is the whole list, read live the same day) and re-attaches
				// itself to a same-named model this generator rebuilds from NOTHING after a master/reset. So a
				// publication the SERVER reports is never, on its own, proof that THIS project's factory-driven
				// generation is the one that published it - it can just as easily be the leftover of a PRIOR
				// project incarnation the reset never actually cleared server-side. Trust the factory's OWN
				// bookkeeping instead, the same evidence every other resume decision in this class already relies
				// on (MasterStagingReadiness, MasterResetEligibility): ebx.MasterReset moves every earlier DONE
				// MASTER_MODEL step to RESET (see its own Javadoc) - so a DONE MASTER_MODEL step existing right
				// now can only mean the factory itself finished publishing this exact model since the project's
				// last reset (or was never reset at all). Without that step, treat the model as MODEL_ONLY even
				// though a publication was found: the caller then re-runs the FULL patch (every facet, every
				// entity - see MasterModelState's own MODEL_ONLY branch), which incidentally also repairs a
				// half-built table left over from an earlier failed run - MasterFacetRepair (runRegeneration's own
				// concern) exists for the REGENERATE path specifically because this check alone cannot cover it.
				final boolean factoryRecordsPublication = PipelineSteps.isDone(pFactory, pProjectId, MasterGenerator.STEP_MASTER_MODEL, MasterGenerator.SCOPE);
				detail.put("publicationReportedByServer", publicationReportedByServer);
				detail.put("factoryRecordsPublicationSinceLastReset", factoryRecordsPublication);
				final boolean published = publicationReportedByServer && factoryRecordsPublication;
				found = published ? MasterModelState.Found.MODEL_AND_PUBLICATION : MasterModelState.Found.MODEL_ONLY;
			}

			final List<String> plannedTablePaths = new ArrayList<String>();
			for (final JsonNode table : pModelResult.tree.path("tables"))
			{
				plannedTablePaths.add(table.path("path").asText());
			}

			final MasterModelState.Decision decision = MasterModelState.decide(found, pModelName, pProjectCode, plannedTablePaths, existingTablePaths);
			final ObjectNode stateDetail = detail.putObject("state");
			stateDetail.put("found", found.name());
			stateDetail.put("build", decision.build);
			stateDetail.put("patch", decision.patch);
			stateDetail.put("publish", decision.publish);
			stateDetail.put("regenerate", decision.regenerate);
			if (!decision.ok())
			{
				throw EngineRefusal.coded("conflict.regenerateRefused", decision.refusal);
			}

			if (decision.regenerate)
			{
				final ModelStepResult regenerationResult = MasterGenerator.runRegeneration(pSession, pFactory, pProjectId, client, pModelName, pProjectCode, pModelResult,
					modelLookup.json.path("tables"), detail);
				final String regenerationMessage = "REGENERATE".equals(regenerationResult.mode)
					? regenerationResult.applied.size() + " additive change(s) applied and republished" + (regenerationResult.refused.isEmpty() ? "."
						: ", " + regenerationResult.refused.size() + " change(s) refused (never applied, see refused[]).")
					: "Master model already published; the promoted entities already match it exactly, nothing to change.";
				PipelineSteps.end(pSession, pFactory, stepId, true, regenerationMessage, detail.toString());
				return regenerationResult;
			}

			if (decision.build)
			{
				final String treeJson = pModelResult.tree.toString();
				MasterGenerator.note("Creating the master model " + pModelName + ": about one second per field.");
				// one build_data_model call, its own answer is the gate (wave 2 lot V2-C, ModelBuilder): no rehearsal first
				ModelBuilder.build(pSession, pFactory, client, "master", pProjectCode, pModelName, treeJson, detail);
			}

			if (decision.patch && pModelResult.operations.size() > 0)
			{
				final ObjectNode patchArgs = MasterGenerator.MAPPER.createObjectNode();
				patchArgs.put("data_model_name", pModelName);
				patchArgs.put("operations_json", pModelResult.operations.toString());
				// no terminal validate+compile of its own (~2 s): publish_data_model, which always follows in this run, is the gate (wave 2 lot V2-C);
				// the per-op failure count below is still checked
				patchArgs.put("gate", false);
				MasterGenerator.note("Updating the master model " + pModelName + " with the new decisions.");
				final McpClient.ToolResult patch = client.callTool("apply_data_model_patch", patchArgs);
				detail.set("patch", MasterGenerator.toolDetail(patchArgs, patch));
				if (patch.error)
				{
					throw EngineRefusal.coded("external.patchFacets", "apply_data_model_patch could not apply the facets of " + pModelName + ": " + MasterGenerator.shortText(patch.text), pModelName);
				}
				final int failed = patch.json == null ? 0 : patch.json.path("failed").asInt(0);
				final boolean patchCompiled = patch.json == null || patch.json.path("compiled").asBoolean(true);
				final boolean patchValid = patch.json == null || patch.json.path("valid").asBoolean(true);
				if (failed > 0 || !patchCompiled || !patchValid)
				{
					throw EngineRefusal.coded("external.patchIncomplete", "apply_data_model_patch left " + pModelName + " with " + failed
						+ " failed operation(s) or an invalid/uncompiled model: " + MasterGenerator.shortText(patch.text), pModelName);
				}
			}

			// osd:table/defaultLabel, AFTER a successful patch and BEFORE publish (class Javadoc): the fresh-build
			// and MODEL_ONLY-resume paths both reach here with decision.patch true, and "new table this run" is
			// simply "every table this generate() call promoted" - unlike runRegeneration, there is no live model
			// yet to diff against, so every entry of defaultLabelByTable is eligible.
			final List<String> defaultLabelNotes = MasterGenerator.applyDefaultLabels(client, pModelName, pModelResult.defaultLabelByTable,
				pModelResult.defaultLabelByTable.keySet(), detail);

			// toolbars (this lot, 2026-09-22): direct dataset write into ebx-dma/<model> (no publish needed for the
			// rows themselves) plus one set_toolbars execute_operation per promoted table (a model-definition change,
			// applied here - same "before publish" placement as applyDefaultLabels - and covered by the publish
			// below like every other execute_operation this method already performs before publishing).
			final ToolbarStepResult toolbarResult = MasterGenerator.applyToolbars(pSession, client, pModelName, pModelResult.builtEntities, detail);

			// lot T2 (2026-10-04): the script-derivation triggers of the applied derivations go on the master tables HERE, on the draft, so that the ONE
			// publish_data_model below carries them - never a second publish. A problem is a note, never a failed generation.
			final List<String> derivationNotes = new ArrayList<String>();
			if (decision.publish)
			{
				// lot W13 (2026-10-12, docs/MATCHING.md sect;33): the Match and Merge business objects of the parent tables, on the draft too, carried by the same publish;
				// their notes travel with these ("derivationNotes" is the list the route surfaces)
				derivationNotes.addAll(MasterBusinessObjects.ensure(client, pModelName, pModelResult, detail).notes);
				derivationNotes.addAll(DerivationMasterTriggers.ensureForProject(pSession, pFactory, pProjectId, pProjectCode, pModelResult, client, detail).notes);
			}

			if (decision.publish)
			{
				MasterGenerator.ensureEmbedded(client, pModelName, detail);
				MasterGenerator.note("Declaring the master tables to Match and Merge, then publishing " + pModelName + ".");
				MasterGenerator.ensureMameAddon(client, pModelName, detail);
				final ObjectNode publishArgs = MasterGenerator.MAPPER.createObjectNode();
				publishArgs.put("data_model_name", pModelName);
				final McpClient.ToolResult publish = client.callTool("publish_data_model", publishArgs);
				detail.set("publish", MasterGenerator.toolDetail(publishArgs, publish));
				if (publish.error)
				{
					throw EngineRefusal.coded("external.publishModel", "publish_data_model could not publish " + pModelName + ": " + MasterGenerator.shortText(publish.text), pModelName);
				}
				if (!PublishDecision.published(publish.json))
				{
					throw EngineRefusal.coded("invalid.modelNotValid", "publish_data_model refused to publish " + pModelName + " (it does not validate/compile): " + MasterGenerator.shortText(publish.text), pModelName);
				}
			}

			final String message = decision.build ? "Master model built, patched and published."
				: "Master model already existed with the promoted tables (MODEL_ONLY resume); re-patched and published.";
			detail.put("mode", "CREATE");
			PipelineSteps.end(pSession, pFactory, stepId, true, message, detail.toString());
			return new ModelStepResult("CREATE", java.util.Collections.<String>emptyList(), java.util.Collections.<String>emptyList(), defaultLabelNotes,
				toolbarResult.notes, derivationNotes);
		}
		catch (final McpException ex)
		{
			detail.put("transportError", ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw EngineRefusal.codedWithCause("external.mcpBuildModel", "MCP call failed while building the master model of " + pProjectCode + ": " + ex.getMessage(), ex, pProjectCode);
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
			StepGuard.endUnexpected(pSession, pFactory, stepId, ex, detail.toString());
			throw ex;
		}
	}

	// -------------------------------------------------------------------------------------------------- regeneration

	/**
	 * See the class Javadoc's "Regeneration" section for why {@link MasterModel.Result#autoIncrementOps} must be
	 * appended by hand, and {@link MasterModel.Result#defaultLabelByTable} applied by hand via
	 * {@link #applyDefaultLabels}, for every genuinely NEW table this run - and {@link MasterFacetRepair} for
	 * every table that already existed but turns out to be missing one of those same two facets on the live
	 * model (`docs/MASTER-ZONE.md` &sect;10's own incident, 2026-09-22: a patch that failed partway through left
	 * a later table published with neither facet, and the plain "is this table new this run" gate below would
	 * otherwise never revisit it again).
	 */
	private static ModelStepResult runRegeneration(final Session pSession, final Adaptation pFactory, final String pProjectId, final McpClient pClient, final String pModelName,
		final String pProjectCode, final MasterModel.Result pModelResult, final JsonNode pExistingTablesJson, final ObjectNode pDetail) throws McpException, OperationException
	{
		final List<StagingModelDiff.ExistingTable> existingTables = StagingModelDiff.parseExisting(pExistingTablesJson);
		final Set<String> existingNames = new HashSet<String>();
		for (final StagingModelDiff.ExistingTable table : existingTables)
		{
			existingNames.add(table.name);
		}
		final StagingModelDiff.Result diff = StagingModelDiff.diff(existingTables, pModelResult.tree, pModelResult.operations);
		// docs/MASTER-ZONE.md sec 10: a promoted table that ALREADY EXISTS but is missing its own id field's
		// auto-increment facet or its own defaultLabel (an earlier patch aborted before reaching it) is invisible
		// to the diff above (StagingModelDiff's generic per-field engine only ever compares maxLength/mandatory -
		// see its own Javadoc) - computed unconditionally, alongside the diff, for the same reason the add-on
		// declaration check right below is: it must not be skipped by an UP_TO_DATE fast path either.
		final MasterFacetRepair.Result repair = MasterFacetRepair.decide(existingTables, pModelResult);

		final ObjectNode diffDetail = pDetail.putObject("diff");
		final List<String> applied = new ArrayList<String>();
		final ArrayNode additiveNode = diffDetail.putArray("additive");
		for (final StagingModelDiff.Change change : diff.additive)
		{
			additiveNode.add(change.description);
			applied.add(change.description);
		}
		final List<String> refused = new ArrayList<String>();
		final ArrayNode nonAdditiveNode = diffDetail.putArray("nonAdditive");
		for (final StagingModelDiff.Change change : diff.nonAdditive)
		{
			nonAdditiveNode.add(change.description);
			refused.add(change.description);
		}
		if (!repair.notes.isEmpty())
		{
			final ArrayNode repairNode = pDetail.putArray("facetRepair");
			for (final String note : repair.notes)
			{
				repairNode.add(note);
				applied.add(note);
			}
		}

		// Coordinator review of 1cdb8bd, 2026-09-21 (docs/MATCHING.md sec 6): a missing MAME add-on declaration is
		// treated as an ADDITIVE difference on the same footing as a table/field diff - the real, already-published
		// Companies_Master is exactly this case (generated before this check existed): diff.isUpToDate() alone must
		// never short-circuit past it, or that model would never get the add-on it needs for matching to work at all.
		// repair.isEmpty() joins the same gate for the identical reason (docs/MASTER-ZONE.md sec 10).
		final boolean addonAlreadyDeclared = MasterGenerator.ensureMameAddon(pClient, pModelName, pDetail);
		if (diff.isUpToDate() && addonAlreadyDeclared && repair.isEmpty())
		{
			pDetail.put("mode", "UP_TO_DATE");
			// still always (re)bind the toolbars, class Javadoc's own "Idempotence" item - a table/field diff found
			// nothing to change is not evidence the toolbars were ever bound on THIS project (this lot is new). A
			// toolbar is part of the model DEFINITION - a dataset only sees it through a publication, the same rule
			// as every other model change here (add_addon's own explicit "INERT until republished" contract; the
			// same reasoning MasterFacetRepair/the add-on check already apply to this exact gate) - so when
			// applyToolbars reports it actually created a toolbar row or bound at least one table, this branch
			// republishes too, the same call runRegeneration's own REGENERATE branch makes below, even though the
			// diff/repair/add-on checks all found nothing else to do.
			final ToolbarStepResult toolbarResult = MasterGenerator.applyToolbars(pSession, pClient, pModelName, pModelResult.builtEntities, pDetail);
			// lot T2 (2026-10-04): the script-derivation triggers are (re)declared on the draft; when any was, the model is republished here too - whether
			// the draft's copy is the published one is not known from this side, and a no-op republish is cheaper than a derivation silently not live
			final DerivationMasterTriggers.ProjectResult derivationTriggers = DerivationMasterTriggers.ensureForProject(pSession, pFactory, pProjectId, pProjectCode, pModelResult,
				pClient, pDetail);
			// lot W13: the business objects of the parent tables (a master generated before this lot has none) - a write makes this branch republish too
			final MasterBusinessObjects.Result businessObjects = MasterBusinessObjects.ensure(pClient, pModelName, pModelResult, pDetail);
			derivationTriggers.notes.addAll(businessObjects.notes);
			if (toolbarResult.needsRepublish || derivationTriggers.declared || businessObjects.changed)
			{
				MasterGenerator.republish(pClient, pModelName, pDetail, toolbarResult.needsRepublish ? "toolbarsPublish" : derivationTriggers.declared ? "derivationTriggersPublish" : "businessObjectsPublish");
				if (toolbarResult.needsRepublish)
				{
					pDetail.put("toolbarsPublished", true);
					toolbarResult.notes.add("Toolbars written/bound: model republished.");
				}
				if (derivationTriggers.declared)
				{
					pDetail.put("derivationTriggersPublished", true);
				}
			}
			return new ModelStepResult("UP_TO_DATE", applied, refused, java.util.Collections.<String>emptyList(), toolbarResult.notes, derivationTriggers.notes);
		}
		pDetail.put("mode", "REGENERATE");
		if (!addonAlreadyDeclared)
		{
			applied.add("MAME add-on declared on " + pModelName + " (Task 1 of the Match and Merge admin guide) - republished.");
		}

		final ArrayNode operations = MasterGenerator.MAPPER.createArrayNode();
		operations.addAll(diff.operations);
		// the bare tablePath of every entity the diff found as a genuinely NEW table this run (never one already
		// present in the live model) - the same gate autoIncrementOps uses below, reused here for
		// MasterModel.Result#defaultLabelByTable (see that field's own Javadoc for why it is never folded into
		// "operations": apply_data_model_patch refuses "defaultLabel" as a set_element_property property outright).
		final Set<String> newTablePaths = new LinkedHashSet<String>();
		for (final String entityName : pModelResult.builtEntities)
		{
			if (existingNames.contains(entityName))
			{
				// not a new table this run - MasterFacetRepair (above) already decided, independently of this
				// gate, whether its auto-increment facet or defaultLabel is missing and needs repair; nothing
				// left to do for it here.
				continue;
			}
			final ArrayNode autoIncrementOps = pModelResult.autoIncrementOps.get(entityName);
			if (autoIncrementOps != null)
			{
				operations.addAll(autoIncrementOps);
			}
			newTablePaths.add(MasterModel.tablePath(entityName));
		}
		// MasterFacetRepair's own ops (autoIncrement only - its labelsToApply are applied the same way as a new
		// table's, via applyDefaultLabels below) - additive/idempotent either way, see that class' own Javadoc.
		operations.addAll(repair.operations);

		if (operations.size() > 0)
		{
			final ObjectNode patchArgs = MasterGenerator.MAPPER.createObjectNode();
			patchArgs.put("data_model_name", pModelName);
			patchArgs.put("operations_json", operations.toString());
			// no terminal validate+compile of its own (~2 s): publish_data_model, which always follows in this run, is the gate (wave 2 lot V2-C);
			// the per-op failure count below is still checked
			patchArgs.put("gate", false);
			final McpClient.ToolResult patch = pClient.callTool("apply_data_model_patch", patchArgs);
			pDetail.set("regeneratePatch", MasterGenerator.toolDetail(patchArgs, patch));
			if (patch.error)
			{
				throw EngineRefusal.coded("external.patchRegenerate", "apply_data_model_patch could not apply the regeneration of " + pModelName + ": " + MasterGenerator.shortText(patch.text), pModelName);
			}
			final int failed = patch.json == null ? 0 : patch.json.path("failed").asInt(0);
			final boolean patchCompiled = patch.json == null || patch.json.path("compiled").asBoolean(true);
			final boolean patchValid = patch.json == null || patch.json.path("valid").asBoolean(true);
			if (failed > 0 || !patchCompiled || !patchValid)
			{
				throw EngineRefusal.coded("external.patchIncomplete", "apply_data_model_patch left " + pModelName + " with " + failed
					+ " failed operation(s) or an invalid/uncompiled model while regenerating: " + MasterGenerator.shortText(patch.text), pModelName);
			}
		}

		// osd:table/defaultLabel, AFTER the patch above and BEFORE the republish below (class Javadoc) - eligible
		// tables are the ones this diff run actually created (newTablePaths) PLUS the ones MasterFacetRepair found
		// missing a label on an already-existing table - both cases are "apply it, nothing there yet".
		final Set<String> labelEligibleTablePaths = new LinkedHashSet<String>(newTablePaths);
		labelEligibleTablePaths.addAll(repair.labelsToApply.keySet());
		final List<String> defaultLabelNotes = MasterGenerator.applyDefaultLabels(pClient, pModelName, pModelResult.defaultLabelByTable, labelEligibleTablePaths, pDetail);

		// toolbars (this lot, 2026-09-22): direct dataset write into ebx-dma/<model> plus one
		// set_toolbars execute_operation per PROMOTED table (all of them, not just newTablePaths - class Javadoc's
		// own "Idempotence" item: always re-run set_toolbars on every promoted table, it is RMW/retryable) - placed
		// here, AFTER the patch and BEFORE the republish below, the same reasoning as applyDefaultLabels.
		final ToolbarStepResult toolbarResult = MasterGenerator.applyToolbars(pSession, pClient, pModelName, pModelResult.builtEntities, pDetail);

		// republish whenever we did not take the UP_TO_DATE fast path above - a table/field diff needs it, and so
		// does a just-declared add-on: add_addon's own contract says it is "INERT until the model is republished"
		// (a toolbar write/binding needs the same thing - see the UP_TO_DATE branch above - but this branch
		// republishes unconditionally regardless, so toolbarResult.needsRepublish is not even consulted here).
		// lot T2 (2026-10-04): the script-derivation triggers, on the draft, carried by the republish right below (one publish)
		final DerivationMasterTriggers.ProjectResult derivationTriggers = DerivationMasterTriggers.ensureForProject(pSession, pFactory, pProjectId, pProjectCode, pModelResult,
			pClient, pDetail);
		// lot W13: the business objects of the parent tables, on the draft, carried by the republish right below
		derivationTriggers.notes.addAll(MasterBusinessObjects.ensure(pClient, pModelName, pModelResult, pDetail).notes);
		MasterGenerator.republish(pClient, pModelName, pDetail, "regeneratePublish");
		return new ModelStepResult("REGENERATE", applied, refused, defaultLabelNotes, toolbarResult.notes, derivationTriggers.notes);
	}

	// ------------------------------------------------------------------------------------------------- defaultLabel

	/**
	 * Applies {@link MasterModel.Result#defaultLabelByTable}, one {@code execute_operation(set_default_label)}
	 * call per table path present in BOTH {@code pDefaultLabelByTable} and {@code pEligibleTablePaths} (the
	 * caller's own "new table this run" gate - see the class Javadoc and {@link #runModelStep}/{@link
	 * #runRegeneration}'s own call sites) - real operation contract, read live, 2026-09-22,
	 * {@code list_operations(name="set_default_label")}: category {@code element}, behavior
	 * {@code ADDITIVE_RETRYABLE}, args {@code element_path} (the table's {@code /root/<Table>} path, NOT a field)
	 * + {@code label} (the template) - runs through {@code execute_operation}, never {@code
	 * apply_data_model_patch} (see {@link MasterModel.Result#defaultLabelByTable}'s own Javadoc for why:
	 * {@code apply_data_model_patch} refuses {@code "defaultLabel"} as a {@code set_element_property} property
	 * outright and aborts the WHOLE patch mid-way - the exact incident `docs/MASTER-ZONE.md` &sect;10 documents).
	 * <p>
	 * <b>Best-effort, by design</b>: a label is cosmetic (EBX falls back to the formatted primary key, and
	 * {@code lineage.RecordTitleRule}'s own read-time fallback covers every table regardless of whether this
	 * ever ran) - a transport error or a refusal on ONE table is caught here, recorded in {@code pDetail}'s
	 * {@code "defaultLabels"} array (one entry per attempted table: {@code table}, {@code label}, {@code status}
	 * = {@code OK}/{@code FAILED}, {@code error} when failed) and in the returned notes, but never thrown -
	 * {@code set_default_label} being {@code ADDITIVE_RETRYABLE} also means a later resume (a MODEL_ONLY retry,
	 * or a future regeneration) can safely call it again on the same table without checking whether a previous
	 * attempt already succeeded.
	 *
	 * @return one human-readable line per table actually attempted (success or failure) - empty when
	 *         {@code pDefaultLabelByTable} has no entry in {@code pEligibleTablePaths}, never null
	 */
	private static List<String> applyDefaultLabels(final McpClient pClient, final String pModelName, final Map<String, String> pDefaultLabelByTable,
		final Set<String> pEligibleTablePaths, final ObjectNode pDetail)
	{
		final List<String> notes = new ArrayList<String>();
		final List<Map.Entry<String, String>> eligible = new ArrayList<Map.Entry<String, String>>();
		for (final Map.Entry<String, String> entry : pDefaultLabelByTable.entrySet())
		{
			if (pEligibleTablePaths.contains(entry.getKey()))
			{
				eligible.add(entry);
			}
		}
		if (eligible.isEmpty())
		{
			return notes;
		}
		final ArrayNode defaultLabelsNode = pDetail.putArray("defaultLabels");
		for (final Map.Entry<String, String> entry : eligible)
		{
			final String tablePath = entry.getKey();
			final String label = entry.getValue();
			final ObjectNode entryNode = defaultLabelsNode.addObject();
			entryNode.put("table", tablePath);
			entryNode.put("label", label);

			final ObjectNode setArgs = MasterGenerator.MAPPER.createObjectNode();
			setArgs.put("data_model_name", pModelName);
			setArgs.put("operation", "set_default_label");
			final ObjectNode operationArgs = MasterGenerator.MAPPER.createObjectNode();
			operationArgs.put("element_path", tablePath);
			operationArgs.put("label", label);
			setArgs.put("arguments_json", operationArgs.toString());
			try
			{
				final McpClient.ToolResult result = pClient.callTool("execute_operation", setArgs);
				entryNode.set("call", MasterGenerator.toolDetail(setArgs, result));
				if (result.error)
				{
					entryNode.put("status", "FAILED");
					entryNode.put("error", MasterGenerator.shortText(result.text));
					notes.add("Could not set the default label of " + tablePath + " to \"" + label + "\" (cosmetic, generation continues): "
						+ MasterGenerator.shortText(result.text));
				}
				else
				{
					entryNode.put("status", "OK");
					notes.add("Default label of " + tablePath + " set to \"" + label + "\".");
				}
			}
			catch (final McpException ex)
			{
				entryNode.put("status", "FAILED");
				entryNode.put("error", ex.getMessage());
				notes.add(
					"Could not set the default label of " + tablePath + " to \"" + label + "\" (cosmetic, generation continues): " + ex.getMessage());
			}
		}
		return notes;
	}

	// ------------------------------------------------------------------------------------------------------ toolbars

	/** {@link #applyToolbars}'s own outcome - notes for the route's "notes" array, and whether a republish is needed for the write/binding to actually take effect (coordinator review, 2026-09-22, "fact 2": a toolbar is part of the model DEFINITION, a dataset only sees it through a publication, the same rule as every other model change - never assume otherwise). */
	private static final class ToolbarStepResult
	{
		final List<String> notes;
		/** True when at least one toolbar root was newly created OR REPLACED ({@code DmaToolbarWriter.Result#anyChanged} - renamed 2026-09-22, toolbar module-level services lot, now that an existing root by name can also be content-replaced) OR at least one {@code set_toolbars} binding call answered OK - either is evidence the model definition may have just changed. Deliberately simple (an OK binding does not itself prove the BOUND NAMES changed, only that the call succeeded) rather than trying to diff the previous binding first - see class Javadoc. */
		final boolean needsRepublish;

		ToolbarStepResult(final List<String> pNotes, final boolean pNeedsRepublish)
		{
			this.notes = pNotes;
			this.needsRepublish = pNeedsRepublish;
		}
	}

	/**
	 * Writes {@link MasterToolbars#build}'s three toolbar definitions into {@code ebx-dma/<model>/root/toolbarNode}
	 * ({@link DmaToolbarWriter#write} - a root already existing from a prior call is compared field-by-field
	 * against the definition and either left alone or replaced, see that class's own Javadoc for the 2026-09-22
	 * migration that made this content-aware rather than name-only) and binds all three to EVERY promoted table via
	 * {@code execute_operation(set_toolbars)} - always,
	 * not only for a table new this run (class Javadoc's own precedent, {@code applyDefaultLabels}, gates on
	 * "new this run"; {@code set_toolbars} does not, because it is documented {@code ADDITIVE_RETRYABLE} and its own
	 * contract warns a wrong name/location fails SILENTLY, so re-asserting the binding on every call is the only way
	 * to notice a name was mistyped by re-reading {@code list_toolbars} afterward, which this method also does).
	 * <p>
	 * <b>Never republishes itself</b> - the returned {@link ToolbarStepResult#needsRepublish} is a REPORT for the
	 * caller to act on ({@link #runModelStep}'s CREATE path and {@link #runRegeneration}'s REGENERATE branch
	 * already republish unconditionally right after this call; {@link #runRegeneration}'s {@code UP_TO_DATE}
	 * branch is the one place that needs to check the flag and call {@link #republish} itself - coordinator review,
	 * 2026-09-22, "fact 2": a toolbar is part of the model DEFINITION, a dataset only sees it through a
	 * publication, the same rule as every other model change here).
	 * <p>
	 * <b>Best-effort, by design</b> - the exact same contract as {@link #applyDefaultLabels}: this feature is purely
	 * additive convenience (a steward can always still reach every one of these three screens through the record's
	 * generic Actions menu, the factory's own perspective, or a deep link - CLAUDE.md's own "never a single point of
	 * failure" spirit already followed by {@code RecordLineageOnRecordServiceDeclaration}'s five ways in), so a
	 * transport error or a refusal on ANY one call here is caught, recorded, and never thrown - a toolbar failing to
	 * write or bind must never fail {@code master/generate} itself.
	 *
	 * @param pBuiltTableEntities {@code MasterModel.Result#builtEntities} - every PROMOTED entity this generate()
	 *        call is working with, converted to table paths ({@link MasterModel#tablePath}) for the {@code
	 *        set_toolbars} calls; toolbar row CREATION itself needs no table at all (it is a plain write into the
	 *        model's own {@code ebx-dma} dataset)
	 */
	private static ToolbarStepResult applyToolbars(final Session pSession, final McpClient pClient, final String pModelName,
		final List<String> pBuiltTableEntities, final ObjectNode pDetail)
	{
		final List<String> notes = new ArrayList<String>();
		final ObjectNode toolbarsNode = pDetail.putObject("toolbars");

		final List<MasterToolbars.Node> definitions = MasterToolbars.build(FactoryPaths.MODULE_NAME);
		final ArrayNode rootsNode = toolbarsNode.putArray("roots");
		boolean anyChanged = false;
		try
		{
			final DmaToolbarWriter.Result writeResult = DmaToolbarWriter.write(pSession, pModelName, definitions);
			anyChanged = writeResult.anyChanged;
			for (final DmaToolbarWriter.ToolbarOutcome outcome : writeResult.outcomes)
			{
				final ObjectNode outcomeNode = rootsNode.addObject();
				outcomeNode.put("name", outcome.name);
				outcomeNode.put("status", outcome.status);
				if (outcome.error != null)
				{
					outcomeNode.put("error", outcome.error);
					notes.add("Could not write the \"" + outcome.name + "\" toolbar of " + pModelName + " (cosmetic, generation continues): " + outcome.error);
				}
				else if ("CREATED".equals(outcome.status))
				{
					notes.add("Toolbar \"" + outcome.name + "\" created on " + pModelName + ".");
				}
				else if ("REPLACED".equals(outcome.status))
				{
					// content-comparison migration (2026-09-22, toolbar module-level services lot,
					// ebx.DmaToolbarWriter's own Javadoc): a root existed under this name but no longer matched
					// the definition - deleted and recreated. outcome.detail carries ToolbarSubtreeDiff's own
					// difference lines, surfaced both in detail and in the note so a human reviewing this run
					// sees exactly what changed, never just "something changed".
					if (outcome.detail != null)
					{
						outcomeNode.put("detail", outcome.detail);
					}
					notes.add("Toolbar \"" + outcome.name + "\" on " + pModelName + " no longer matched its definition and was replaced: "
						+ (outcome.detail == null ? "(no detail)" : outcome.detail));
				}
			}
		}
		catch (final OperationException ex)
		{
			toolbarsNode.put("writeError", ex.getMessage());
			notes.add("Could not write the MDM Factory toolbars of " + pModelName + " (cosmetic, generation continues): " + ex.getMessage());
			return new ToolbarStepResult(notes, false); // no rows to bind to a table if the write itself failed
		}

		boolean anyBindingOk = false;
		// 2026-10-12: the data-model server takes ONE toolbar per set_toolbars call (element_path + locations + toolbar);
		// the old shape (tabular_view_top / tabular_view_row / record_top in one call) is answered "does not take ...".
		// New shape first; on that very refusal, the old shape once per table and for the rest of the run.
		boolean legacyShape = false;
		final ArrayNode bindingsNode = toolbarsNode.putArray("bindings");
		for (final String entityName : pBuiltTableEntities)
		{
			final String tablePath = MasterModel.tablePath(entityName);
			final ObjectNode bindingNode = bindingsNode.addObject();
			bindingNode.put("table", tablePath);
			final ArrayNode callsNode = bindingNode.putArray("calls");
			boolean tableFailed = false;

			if (!legacyShape)
			{
				for (final MasterToolbars.Binding binding : MasterToolbars.bindings())
				{
					final String failure = MasterGenerator.callSetToolbars(pClient, pModelName, MasterToolbars.setToolbarsArguments(tablePath, binding), callsNode);
					if (failure == null)
					{
						anyBindingOk = true;
						continue;
					}
					if (MasterToolbars.isArgumentShapeRefusal(failure))
					{
						legacyShape = true;
						notes.add("set_toolbars refused the per-location arguments (" + MasterGenerator.shortText(failure) + "): retrying with the older single-call shape.");
						break;
					}
					tableFailed = true;
					notes.add("Could not bind " + binding.toolbar + " (" + binding.locations + ") to " + tablePath + " (cosmetic, generation continues): " + MasterGenerator.shortText(failure));
				}
			}
			if (legacyShape)
			{
				bindingNode.put("contract", "legacy");
				tableFailed = false; // the legacy call below decides for this table
				final String failure = MasterGenerator.callSetToolbars(pClient, pModelName, MasterToolbars.legacySetToolbarsArguments(tablePath), callsNode);
				if (failure == null)
				{
					anyBindingOk = true;
				}
				else
				{
					tableFailed = true;
					notes.add("Could not bind the MDM Factory toolbars to " + tablePath + " (cosmetic, generation continues): " + MasterGenerator.shortText(failure));
				}
			}
			else
			{
				bindingNode.put("contract", "locations");
			}
			bindingNode.put("status", tableFailed ? "FAILED" : "OK");

			// read the bindings back instead of trusting the call's silent success (set_toolbars fails silently on a wrong name)
			try
			{
				final ObjectNode describeArgs = MasterGenerator.MAPPER.createObjectNode();
				describeArgs.put("data_model_name", pModelName);
				describeArgs.put("table_path", tablePath);
				final McpClient.ToolResult described = pClient.callTool("describe_table", describeArgs);
				if (described.error || described.json == null)
				{
					bindingNode.put("readBackError", MasterGenerator.shortText(described.text));
					notes.add("Toolbars bound on " + tablePath + ": could not be read back (describe_table: " + MasterGenerator.shortText(described.text) + ").");
				}
				else
				{
					final java.util.Map<String, String> bound = MasterToolbars.boundToolbars(described.json);
					final ObjectNode boundNode = bindingNode.putObject("readBack");
					for (final java.util.Map.Entry<String, String> entry : bound.entrySet())
					{
						boundNode.put(entry.getKey(), entry.getValue());
					}
					notes.add(MasterToolbars.boundNote(tablePath, bound));
				}
			}
			catch (final McpException ex)
			{
				bindingNode.put("readBackError", ex.getMessage());
				notes.add("Toolbars bound on " + tablePath + ": could not be read back (" + ex.getMessage() + ").");
			}
		}

		// list_toolbars re-read, class Javadoc: the one way to notice a name/location mismatch that set_toolbars
		// itself would otherwise fail on SILENTLY - recorded in detail only, never asserted against (this lot does
		// not attempt to parse the roots back out and compare, only to leave the evidence for a human to check).
		try
		{
			final ObjectNode listArgs = MasterGenerator.MAPPER.createObjectNode();
			listArgs.put("data_model_name", pModelName);
			listArgs.put("operation", "list_toolbars");
			final McpClient.ToolResult listResult = pClient.callTool("execute_query", listArgs);
			toolbarsNode.set("listToolbars", MasterGenerator.toolDetail(listArgs, listResult));
		}
		catch (final McpException ex)
		{
			toolbarsNode.put("listToolbarsError", ex.getMessage());
		}

		return new ToolbarStepResult(notes, anyChanged || anyBindingOk);
	}

	/** One {@code execute_operation(set_toolbars)} call, its detail appended to {@code pCalls}; null when it succeeded, the refusal/transport text otherwise (never thrown - a toolbar is cosmetic). */
	private static String callSetToolbars(final McpClient pClient, final String pModelName, final ObjectNode pOperationArguments, final ArrayNode pCalls)
	{
		final ObjectNode setArgs = MasterGenerator.MAPPER.createObjectNode();
		setArgs.put("data_model_name", pModelName);
		setArgs.put("operation", "set_toolbars");
		setArgs.put("arguments_json", pOperationArguments.toString());
		try
		{
			final McpClient.ToolResult result = pClient.callTool("execute_operation", setArgs);
			pCalls.add(MasterGenerator.toolDetail(setArgs, result));
			return result.error ? (result.text == null ? "set_toolbars failed" : result.text) : null;
		}
		catch (final McpException ex)
		{
			final ObjectNode failed = pCalls.addObject();
			failed.set("arguments", setArgs);
			failed.put("error", ex.getMessage());
			return ex.getMessage() == null ? "set_toolbars failed" : ex.getMessage();
		}
	}

	/**
	 * {@code publish_data_model}, factored out so both {@link #runRegeneration}'s REGENERATE branch and its
	 * {@code UP_TO_DATE} branch (when {@link #applyToolbars} reports {@link ToolbarStepResult#needsRepublish})
	 * call the exact same republish logic - coordinator review, 2026-09-22, "fact 2".
	 */
	private static void republish(final McpClient pClient, final String pModelName, final ObjectNode pDetail, final String pDetailKey)
		throws McpException, OperationException
	{
		final ObjectNode publishArgs = MasterGenerator.MAPPER.createObjectNode();
		publishArgs.put("data_model_name", pModelName);
		final McpClient.ToolResult publish = pClient.callTool("publish_data_model", publishArgs);
		pDetail.set(pDetailKey, MasterGenerator.toolDetail(publishArgs, publish));
		if (publish.error)
		{
			throw EngineRefusal.coded("external.publishModel", "publish_data_model could not republish " + pModelName + ": " + MasterGenerator.shortText(publish.text), pModelName);
		}
		if (!PublishDecision.published(publish.json))
		{
			throw EngineRefusal.coded("invalid.modelNotValid", "publish_data_model refused to republish " + pModelName + " (it does not validate/compile): " + MasterGenerator.shortText(publish.text), pModelName);
		}
	}

	static void ensureEmbedded(final McpClient pClient, final String pModelName, final ObjectNode pDetail) throws McpException, OperationException
	{
		final ObjectNode getArgs = MasterGenerator.MAPPER.createObjectNode();
		getArgs.put("data_model_name", pModelName);
		getArgs.put("operation", "get_global_properties");
		final McpClient.ToolResult props = pClient.callTool("execute_query", getArgs);
		pDetail.set("globalProperties", MasterGenerator.toolDetail(getArgs, props));
		final String mode = props.json == null ? null : props.json.path("configuration").path("publicationMode").asText(null);
		if ("EMBEDDED".equals(mode))
		{
			return;
		}
		final ObjectNode setArgs = MasterGenerator.MAPPER.createObjectNode();
		setArgs.put("data_model_name", pModelName);
		setArgs.put("operation", "set_global_properties");
		final ObjectNode moduleProperties = MasterGenerator.MAPPER.createObjectNode();
		moduleProperties.put("publicationMode", "EMBEDDED");
		final ObjectNode operationArgs = MasterGenerator.MAPPER.createObjectNode();
		operationArgs.set("module_properties", moduleProperties);
		setArgs.put("arguments_json", operationArgs.toString());
		final McpClient.ToolResult set = pClient.callTool("execute_operation", setArgs);
		pDetail.set("publicationMode", MasterGenerator.toolDetail(setArgs, set));
		if (set.error)
		{
			throw EngineRefusal.coded("external.publicationMode", "Could not set the publication mode of " + pModelName + " to EMBEDDED: " + MasterGenerator.shortText(set.text), pModelName);
		}
	}

	// -------------------------------------------------------------------------------------------------------- add-on

	/** The read-only half of {@link #ensureMameAddon}'s work, paired with the raw {@code list_addons} text a caller may still want to quote. */
	static final class MameAddonCheck
	{
		final MameAddonDecision.Decision decision;
		final String rawText;

		private MameAddonCheck(final MameAddonDecision.Decision pDecision, final String pRawText)
		{
			this.decision = pDecision;
			this.rawText = pRawText;
		}
	}

	/**
	 * Read-only: {@code list_addons} + {@link MameAddonDecision#decide}, with no write ever attempted - the exact
	 * parsing {@link #ensureMameAddon} already did inline, extracted (coordinator review, 2026-09-21,
	 * `docs/MATCHING.md` &sect;2.2 "dataModel" entry) so {@code ebx.MatchingConfigurator} can give a truthful
	 * {@code TABLE_NOT_FOUND} refusal - "the add-on is declared but match-merge still doesn't resolve the table"
	 * versus "the add-on genuinely isn't declared yet" - without duplicating this {@code list_addons} parsing.
	 */
	static MameAddonCheck checkMameAddonDeclared(final McpClient pClient, final String pModelName, final ObjectNode pDetail) throws McpException
	{
		final ObjectNode listArgs = MasterGenerator.MAPPER.createObjectNode();
		listArgs.put("data_model_name", pModelName);
		listArgs.put("operation", "list_addons");
		final McpClient.ToolResult listResult = pClient.callTool("execute_query", listArgs);
		pDetail.set("listAddons", MasterGenerator.toolDetail(listArgs, listResult));
		return new MameAddonCheck(MameAddonDecision.decide(listResult.json), listResult.text);
	}

	/**
	 * Declares the MAME (Match and Merge) add-on on the master model - master only, never landing/staging - so
	 * {@code activate_matching} stops refusing every table of it with {@code TABLE_NOT_FOUND} (`docs/MATCHING.md`
	 * &sect;2.2/&sect;5, coordinator review of 1cdb8bd). Found live, read-only, 2026-09-21:
	 * {@code list_operations(category="modelsettings")} lists {@code add_addon}/{@code list_addons} on the
	 * {@code data-model} MCP server, and {@code list_operations(name="add_addon")} quotes its full contract:
	 * <i>"Reference an add-on already installed and activated at the repository level (DMA \"Configuration &gt;
	 * Add-ons\") ... Unknown/inactive name -&gt; soft-miss (code ADDON_NOT_AVAILABLE) carrying availableAddons;
	 * throws on blank name or duplicate ... Cross-endpoint: a declaration only reaches the compiled schema at the
	 * next publication."</i> - the second half of that sentence is exactly why every caller of this method must
	 * still publish afterward, unconditionally, even when {@link #ensureEmbedded} and the table diff both found
	 * nothing else to do (see {@link #runRegeneration}'s own change the same review asked for). The on-disk admin
	 * guide (`ebx-addon-mame/www/.../admin_guide/updating_data_model.html`, read the same day) calls this exact
	 * step "Task 1: Updating the model" - "Expand Configuration and select Add-ons. Select '+' ... choose Match
	 * and Merge. After saving, publish the data model." - confirming this is the model-level declaration, distinct
	 * from "Task 2: Registering tables" (a {@code TableConfiguration}, i.e. {@link
	 * com.on.ebx.factory.ebx.MatchingConfigurator#configureOne configureOne}'s own {@code generate_matching_policy}
	 * call, unrelated to this method).
	 * <p>
	 * <b>{@code list_addons}'s real response shape, captured live by the coordinator on {@code Companies_Master},
	 * 2026-09-21 (`docs/MATCHING.md` &sect;5.1) - replaces an earlier, more speculative version of this method that
	 * guessed at the envelope and the add-on's name</b>: {@code {"found":true,"addons":[],"availableAddons":
	 * [{"label":"Digital Asset Manager","name":"ebx-addon-dama"},{"label":"Match and Merge",
	 * "name":"ebx-addon-mame"}], ...}}. {@code addons} = declared ON THIS MODEL (empty here - the gap this method
	 * closes); {@code availableAddons} = installed and activated at the REPOSITORY level; the add-on's real name is
	 * confirmed {@code ebx-addon-mame} ({@link MameAddonDecision#MAME_NAME}). Every actual decision (already
	 * declared / declare / not available / unreadable) is {@link MameAddonDecision#decide}, pure and
	 * unit-tested against this exact JSON; this method only makes the two MCP calls and acts on the decision.
	 * <b>{@code list_addons}/{@code add_addon} were still not called live from THIS class this session</b> (only the
	 * coordinator's own separate, supervised call did) - the JSON shape is real, but the end-to-end call sequence
	 * through {@code execute_query}/{@code execute_operation} remains to be watched on the first real
	 * {@code master/generate}.
	 *
	 * @return true when the add-on was ALREADY declared (and, where stated, activated) - nothing done; false when
	 *         this call just declared it - the caller must still publish, unconditionally, for the declaration to
	 *         take effect ("INERT until the model is republished").
	 */
	private static boolean ensureMameAddon(final McpClient pClient, final String pModelName, final ObjectNode pDetail) throws McpException, OperationException
	{
		final MasterGenerator.MameAddonCheck check = MasterGenerator.checkMameAddonDeclared(pClient, pModelName, pDetail);
		final MameAddonDecision.Decision decision = check.decision;
		pDetail.put("addonDecision", decision.action.name());
		switch (decision.action)
		{
			case ALREADY_DECLARED:
				return true;
			case NOT_AVAILABLE:
				throw EngineRefusal.coded("external.mameAddon", "Could not declare the MAME add-on on " + pModelName + ": " + decision.message, pModelName);
			case UNREADABLE:
				throw EngineRefusal.coded("external.mameAddon", "Could not declare the MAME add-on on " + pModelName + ": " + decision.message + " (raw: " + MasterGenerator.shortText(check.rawText) + ")", pModelName);
			case DECLARE:
			default:
				break;
		}

		// never reached when ALREADY_DECLARED - add_addon's own contract: "throws on blank name or duplicate"
		final ObjectNode addArgs = MasterGenerator.MAPPER.createObjectNode();
		addArgs.put("data_model_name", pModelName);
		addArgs.put("operation", "add_addon");
		final ObjectNode operationArgs = MasterGenerator.MAPPER.createObjectNode();
		operationArgs.put("name", decision.name);
		addArgs.put("arguments_json", operationArgs.toString());
		final McpClient.ToolResult addResult = pClient.callTool("execute_operation", addArgs);
		pDetail.set("addAddon", MasterGenerator.toolDetail(addArgs, addResult));
		if (addResult.error)
		{
			throw EngineRefusal.coded("external.addonDeclare", "add_addon could not declare " + decision.name + " on " + pModelName + ": " + MasterGenerator.shortText(addResult.text), decision.name, pModelName);
		}
		final boolean notAvailable = addResult.json != null && "ADDON_NOT_AVAILABLE".equals(addResult.json.path("code").asText(null));
		if (notAvailable)
		{
			throw EngineRefusal.coded("conflict.addonUnavailable", "add_addon refused " + decision.name + " on " + pModelName
				+ " as ADDON_NOT_AVAILABLE even though list_addons listed it as available - ask an administrator to check Administration > Add-ons: "
				+ MasterGenerator.shortText(addResult.text), decision.name, pModelName);
		}
		return false;
	}

	// ----------------------------------------------------------------------------------------------- MASTER_DATASET

	/** @return true when the step was already DONE (nothing run again) */
	private static boolean runDatasetStepNoted(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pModelName,
		final String pDataspaceKey, final String pDatasetName) throws OperationException
	{
		MasterGenerator.note("Creating the master dataset " + pDatasetName + " in " + pDataspaceKey + ".");
		return MasterGenerator.runDatasetStep(pSession, pFactory, pProjectId, pModelName, pDataspaceKey, pDatasetName);
	}

	private static boolean runDatasetStep(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pModelName,
		final String pDataspaceKey, final String pDatasetName) throws OperationException
	{
		if (PipelineSteps.isDone(pFactory, pProjectId, MasterGenerator.STEP_MASTER_DATASET, MasterGenerator.SCOPE))
		{
			return true;
		}
		final String stepId = PipelineSteps.begin(pSession, pFactory, pProjectId, MasterGenerator.STEP_MASTER_DATASET, MasterGenerator.SCOPE);
		final ObjectNode detail = MasterGenerator.MAPPER.createObjectNode();
		// 1.4.2 (X3-11): true once THIS step created the zone, so that an empty refusal of create_dataset can remove it again (ZoneDatasetDenial)
		boolean zoneCreatedHere = false;
		try
		{
			final Repository repository = pFactory.getHome().getRepository();
			AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(pDataspaceKey));
			final ObjectNode dataspaceDetail = detail.putObject("dataspace");
			dataspaceDetail.put("key", pDataspaceKey);
			if (masterHome == null)
			{
				final HomeCreationSpec spec = new HomeCreationSpec();
				spec.setKey(HomeKey.forBranchName(pDataspaceKey));
				final AdaptationHome parentHome = repository.getReferenceBranch();
				spec.setParent(parentHome);
				spec.setOwner(Profile.ADMINISTRATOR);
				// 1.4.3 (X3-11): the permissions of the parent dataspace by default, or of the dataspace named by -Debx.factory.zonePermissionsFrom, or none (ZonePermissions)
				final String permissionsFrom = FactoryAccess.copyZonePermissions(spec, repository, parentHome);
				dataspaceDetail.put("permissionsCopiedFrom", permissionsFrom == null ? "none" : permissionsFrom);
				spec.setLabel(UserMessage.createInfo(pDataspaceKey));
				spec.setDescription(UserMessage.createInfo("Master (golden) zone of project " + pProjectId + ": source and golden records side by side."));
				masterHome = repository.createHome(spec, pSession);
				zoneCreatedHere = true;
				dataspaceDetail.put("created", true);
			}
			else
			{
				dataspaceDetail.put("created", false);
			}

			final Adaptation existingDataset = masterHome.findAdaptationOrNull(AdaptationName.forName(pDatasetName));
			final ObjectNode datasetDetail = detail.putObject("dataset");
			datasetDetail.put("name", pDatasetName);
			if (existingDataset == null)
			{
				final McpClient client = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
				final ObjectNode createArgs = MasterGenerator.MAPPER.createObjectNode();
				createArgs.put("data_model_name", pModelName);
				createArgs.put("operation", "create_dataset");
				final ObjectNode operationArgs = MasterGenerator.MAPPER.createObjectNode();
				operationArgs.put("publication_name", pModelName);
				operationArgs.put("dataset_name", pDatasetName);
				operationArgs.put("dataspace", pDataspaceKey);
				createArgs.put("arguments_json", operationArgs.toString());
				final McpClient.ToolResult create = client.callTool("execute_operation", createArgs);
				datasetDetail.set("call", MasterGenerator.toolDetail(createArgs, create));
				if (create.error)
				{
					if (ZoneDatasetDenial.emptyToolFailure(create.text))
					{
						throw ZoneDatasetDenial.translate(pSession, pFactory, pDataspaceKey, zoneCreatedHere);
					}
					throw EngineRefusal.coded("external.createDataset", "create_dataset could not create dataset " + pDatasetName + " in " + pDataspaceKey + ": " + MasterGenerator.shortText(create.text), pDatasetName, pDataspaceKey);
				}
				datasetDetail.put("skipped", false);
			}
			else
			{
				datasetDetail.put("skipped", true);
			}

			PipelineSteps.end(pSession, pFactory, stepId, true, "Master dataspace and dataset ready.", detail.toString());
			return false;
		}
		catch (final McpException ex)
		{
			if (ZoneDatasetDenial.emptyToolFailure(ex.getMessage()))
			{
				final OperationException denied = ZoneDatasetDenial.translate(pSession, pFactory, pDataspaceKey, zoneCreatedHere);
				detail.put("transportError", ex.getMessage());
				PipelineSteps.end(pSession, pFactory, stepId, false, denied.getMessage(), detail.toString());
				throw denied;
			}
			detail.put("transportError", ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw EngineRefusal.codedWithCause("external.mcpCreateDataset", "MCP call failed while creating the master dataset: " + ex.getMessage(), ex);
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
			StepGuard.endUnexpected(pSession, pFactory, stepId, ex, detail.toString());
			throw ex;
		}
	}

	// ----------------------------------------------------------------------------------------------------- write-back

	private static void writeBackProjectPointers(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pDataspaceKey,
		final String pDatasetName) throws OperationException
	{
		final com.onwbp.adaptation.AdaptationTable projects = pFactory.getTable(FactoryPaths.PROJECT);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation project = projects.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
				if (project == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(project.getAdaptationName());
				value.setValue(pDataspaceKey, FactoryPaths.Project.MASTER_DATASPACE);
				value.setValue(pDatasetName, FactoryPaths.Project.MASTER_DATASET);
				pContext.doModifyContent(project, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.writeBackMaster", "The master model was generated, but writing back Project.masterDataspace/masterDataset failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
	}

	// --------------------------------------------------------------------------------------------------------- read

	/** Every {@code StagingEntity} of the project with its {@code StagingAttribute}s - the exact same shape {@code StagingGenerator.readEntities}/{@code StagingLoader.readEntities} already read, a separate copy per the codebase's own convention (each caller keeps its own private reader rather than sharing one across files). */
	private static List<StagingModel.EntityInfo> readEntities(final Adaptation pFactory, final String pProjectId)
	{
		final List<Adaptation> entityRowsList = new ArrayList<Adaptation>();
		final Map<String, String> entityIdToName = new HashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				entityRowsList.add(row);
				entityIdToName.put(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.StagingEntity.NAME));
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
				row.getString(FactoryPaths.StagingEntity.STATUS), MasterGenerator.readAttributes(pFactory, entityId, entityIdToName), memberNames));
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

	/**
	 * {@code StagingEntity.lastLoadWritten + lastLoadUnchanged} of every entity of the project, by name - the direct evidence {@link MasterStagingReadiness}
	 * weighs alongside the {@code STAGING_LOAD} step's own (fixable, but not solely trustworthy) history. Lot V3-S (2026-10-04): a load that found every row
	 * up to date writes none, yet the rows are in staging - "unchanged" counts as loaded.
	 */
	private static Map<String, Long> readLastLoadWritten(final Adaptation pFactory, final String pProjectId)
	{
		final Map<String, Long> out = new HashMap<String, Long>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object raw = row.get(FactoryPaths.StagingEntity.LAST_LOAD_WRITTEN);
				final Object rawUnchanged = row.get(FactoryPaths.StagingEntity.LAST_LOAD_UNCHANGED);
				final long written = (raw instanceof Number ? ((Number) raw).longValue() : 0L)
					+ (rawUnchanged instanceof Number ? ((Number) rawUnchanged).longValue() : 0L);
				out.put(row.getString(FactoryPaths.StagingEntity.NAME), Long.valueOf(written));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Every accepted-like {@code StagingEntity} of the project as a {@code MembershipConsistencyCheck.EntityInfo}:
	 * name, origin, whether {@code memberEntities} is non-empty, and the set of landing table NAMES its own
	 * accepted-like, non-technical-key attributes are mapped from ({@link #readLandingTableNames}). A row not
	 * accepted-like ({@code PROPOSED}/{@code REJECTED}) is not a real promotion candidate and would only add
	 * noise to the overlap comparison, so it is left out here.
	 */
	private static List<MembershipConsistencyCheck.EntityInfo> readMembershipCandidates(final Adaptation pFactory, final String pProjectId)
	{
		final List<MembershipConsistencyCheck.EntityInfo> out = new ArrayList<MembershipConsistencyCheck.EntityInfo>();
		final Map<String, String> landingTableNameCache = new HashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				final String status = row.getString(FactoryPaths.StagingEntity.STATUS);
				if (!TechnicalKeyRule.isAcceptedLike(status))
				{
					continue;
				}
				final String entityId = row.getOccurrencePrimaryKey().format();
				final String name = row.getString(FactoryPaths.StagingEntity.NAME);
				final String origin = row.getString(FactoryPaths.StagingEntity.ORIGIN);
				final List<?> rawMembers = row.getList(FactoryPaths.StagingEntity.MEMBER_ENTITIES);
				final boolean hasMembers = rawMembers != null && !rawMembers.isEmpty();
				out.add(new MembershipConsistencyCheck.EntityInfo(name, origin, hasMembers,
					MasterGenerator.readLandingTableNames(pFactory, entityId, landingTableNameCache)));
			}
		}
		finally
		{
			entityRows.close();
		}
		return out;
	}

	/** The landing table NAMES fed to one entity's own accepted-like, non-technical-key attributes, through their accepted-like {@code AttributeMapping} rows. */
	private static Set<String> readLandingTableNames(final Adaptation pFactory, final String pEntityId, final Map<String, String> pLandingTableNameCache)
	{
		final Set<String> out = new LinkedHashSet<String>();
		final RequestResult attributeRows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation attributeRow = attributeRows.nextAdaptation(); attributeRow != null; attributeRow = attributeRows.nextAdaptation())
			{
				if (Boolean.TRUE.equals(attributeRow.get(FactoryPaths.StagingAttribute.PRIMARY_KEY))
					|| !TechnicalKeyRule.isAcceptedLike(attributeRow.getString(FactoryPaths.StagingAttribute.STATUS)))
				{
					continue;
				}
				final String attributeId = attributeRow.getOccurrencePrimaryKey().format();
				final RequestResult mappingRows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).createRequestResult("./attribute=" + CatalogReader.quote(attributeId));
				try
				{
					for (Adaptation mappingRow = mappingRows.nextAdaptation(); mappingRow != null; mappingRow = mappingRows.nextAdaptation())
					{
						if (!TechnicalKeyRule.isAcceptedLike(mappingRow.getString(FactoryPaths.AttributeMapping.STATUS)))
						{
							continue;
						}
						final String tableName = MasterGenerator.landingTableName(pFactory, mappingRow.getString(FactoryPaths.AttributeMapping.LANDING_COLUMN),
							pLandingTableNameCache);
						if (tableName != null)
						{
							out.add(tableName);
						}
					}
				}
				finally
				{
					mappingRows.close();
				}
			}
		}
		finally
		{
			attributeRows.close();
		}
		return out;
	}

	private static String landingTableName(final Adaptation pFactory, final String pLandingColumnId, final Map<String, String> pCache)
	{
		if (pLandingColumnId == null)
		{
			return null;
		}
		if (pCache.containsKey(pLandingColumnId))
		{
			return pCache.get(pLandingColumnId);
		}
		String name = null;
		final Adaptation column = pFactory.getTable(FactoryPaths.LANDING_COLUMN).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingColumnId));
		if (column != null)
		{
			final String landingTableId = column.getString(FactoryPaths.LandingColumn.LANDING_TABLE);
			if (landingTableId != null)
			{
				final Adaptation table = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(landingTableId));
				name = table == null ? null : table.getString(FactoryPaths.LandingTable.TABLE_NAME);
			}
		}
		pCache.put(pLandingColumnId, name);
		return name;
	}

	/** Package-visible (not {@code private}): {@code ebx.MasterReset} needs the exact same computed dataspace key to recognise a factory-generated master zone. */
	static String masterSuffix(final Adaptation pFactory) throws OperationException
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (settings == null)
		{
			throw EngineRefusal.coded("missing.settings", "MDM Factory settings: the DEFAULT row is missing");
		}
		return settings.getString(FactoryPaths.Settings.MASTER_SUFFIX);
	}

	// ------------------------------------------------------------------------------------------------------------- detail

	private static ObjectNode toolDetail(final ObjectNode pArguments, final McpClient.ToolResult pResult)
	{
		final ObjectNode node = MasterGenerator.MAPPER.createObjectNode();
		node.set("arguments", pArguments);
		node.put("error", pResult.error);
		if (pResult.json != null)
		{
			node.set("result", pResult.json);
		}
		else
		{
			node.put("resultText", pResult.text);
		}
		return node;
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
