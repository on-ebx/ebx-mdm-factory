package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import com.on.ebx.factory.quality.QualityDetectors;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.StagingModelDiff;
import com.on.ebx.factory.staging.StagingModelState;
import com.on.ebx.factory.staging.StagingNames;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.on.ebx.factory.landing.RunTexts;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
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
 * Turns the ACCEPTED (or already {@code APPLIED}) {@code StagingEntity}/{@code StagingAttribute} rows of a
 * project into a real staging zone in EBX: like {@link LandingGenerator}, but for the whole project at once
 * rather than per source system - one data model {@code <ProjectCode>_Staging} with one table per accepted
 * entity, one dataspace {@code <ProjectCode><stagingSuffix>}, one dataset {@code Staging}
 * ({@link StagingNames}). The tree and the patch operations come from {@link StagingModel#build}, the pure
 * function that decides what is generated and how; this class only drives the MCP calls and the EBX writes
 * around it, and is not itself unit-tested (see {@code StagingModelTest} for the generation logic).
 * <p>
 * Two steps, each recorded in its own {@code PipelineStep} (empty scope: the whole project, like {@code
 * StagingSuggester}'s own {@code STAGING_SUGGEST}) through {@link PipelineSteps}, so a run interrupted or
 * failed partway resumes instead of restarting:
 * <ul>
 * <li>{@code STAGING_MODEL} - state found on the server decides what is left to do
 * ({@link StagingModelState}, the same resumable pattern as {@code LandingGenerator}'s own {@code
 * LANDING_MODEL}: nothing / model only / model and publication): the one build ({@link ModelBuilder}, no rehearsal since wave 2 lot V2-C), the patch
 * operations {@link StagingModel} computed (maxLength, mandatory+minOccurs; foreign keys are already in the
 * tree - see {@link StagingModel}'s own Javadoc for why), the embedded-publication-mode check, the
 * publish. A staging model that already exists with tables that do NOT match the currently accepted
 * proposals is refused non-destructively - see {@link StagingModelState}'s Javadoc for why this differs
 * from the landing model's harsher "rename or delete" refusal.
 * <p>
 * <b>The patch runs whenever the model is not yet published, not only right after a build</b> (a defect a
 * coordinator review caught, 2026-09-20: build succeeds, the patch call fails or the process dies before
 * it runs, the step is {@code FAILED}; the next run used to find {@code MODEL_ONLY}, skip BOTH the build
 * and the patch since the patch call lived inside {@code if (decision.build)}, and publish a staging model
 * with no length limits and no mandatory field - silently wrong, and every accepted line would then be
 * marked {@code APPLIED} over it). {@link StagingModelState.Decision#patch} is now its own field, true in
 * exactly the states {@link StagingModelState.Decision#publish} is (never publish a model whose facets
 * were not (re-)applied first) - see {@link StagingModelState}'s Javadoc for the three-state table and its
 * tests. Re-applying the patch on an already-patched model is safe because none of the three tokens this
 * class ever emits is additive: `data_model_schema`'s {@code settableFacets} documents {@code mandatory}
 * as <i>"true/false. NOTE: true sets osd:checkNullInput only"</i>, {@code maxLength} as <i>"an integer as
 * a string - the exact length"</i> and {@code minOccurs} as <i>"an integer as a string, e.g. \"1\"..."</i>
 * - none carries the "ADDITIVE" marker the same listing gives {@code validationRules}/{@code
 * uniqueConstraints}/{@code javaConstraints} (the only three tokens it flags that way), and
 * {@code apply_data_model_patch}'s own tool description confirms the general rule: <i>"Idempotent MODIFY
 * for single-slot tokens; a few (validationRules, uniqueConstraints, javaConstraints) are ADDITIVE - they
 * append rather than replace, as their valueShape says"</i>.
 * <p>
 * <b>No separate validate/compile call guards the publish either</b> - {@code publish_data_model}'s own
 * description IS the gate the {@code data_model_guide} playbook prescribes: <i>"the ONLY tool that runs
 * the full validate -&gt; compile -&gt; publish chain, publishing only if both gates pass (no partial
 * write)"</i>, and <i>"a gate block or not-found returns a structured failure (published/found:false +
 * code), never a throw"</i> - so this class checks the response's own {@code published} field ({@link
 * PublishDecision}, shared with {@code LandingGenerator} so the exact same check is not re-derived twice),
 * not merely the absence of a transport-level error, and refuses with an explicit message when it is
 * {@code false}. The playbook itself argues against an extra {@code validate_data_model}/{@code compile_data_model} call
 * here: <i>"DO NOT VALIDATE OR COMPILE UNASKED EITHER... AND NEVER RIGHT AFTER A PATCH: build_data_model
 * and apply_data_model_patch already END with one validate + compile and report both in their result.
 * Calling either tool again immediately re-runs a gate that just ran, over a model nothing has changed
 * since - it cannot return anything new"</i> (`data_model_guide(goal="build")`, read live 2026-09-20).</li>
 * <li>{@code STAGING_DATASET} - the dataspace, created in Java exactly like {@code LandingGenerator}'s own
 * {@code LANDING_DATASET} ({@code Repository.createHome}, no REST), then the dataset via {@code
 * data-model.execute_operation(create_dataset)}.</li>
 * </ul>
 * On success: {@code Project.stagingDataspace}/{@code stagingDataset} are written, every accepted entity a
 * table was actually built for ({@link StagingModel.Result#builtEntities}) - and its own accepted attributes
 * - move to {@code APPLIED}; the project's own {@code status} becomes {@code STAGING}. A project with no
 * accepted entity that clears {@link StagingModel}'s own bar (a primary key AND at least one business
 * attribute, both accepted) refuses outright: nothing to generate.
 * <p>
 * EBX writes ({@code PipelineStep}, {@code Project}, {@code StagingEntity}, {@code StagingAttribute}) act
 * with the rights of the session given to {@link #generate}. The MCP calls that build and publish the model,
 * and that create the dataset, act as the factory's one agent account ({@link McpServers}) instead - exactly
 * the same division of responsibility as {@code LandingGenerator}.
 * <p>
 * <b>Regeneration (round 4, 2026-09-20):</b> once the staging model is published, calling {@link #generate}
 * again is no longer refused just because the accepted proposals moved on ({@code Company} accepted after
 * {@code Supplier}/{@code Account}/{@code Contact} were already live, PLAN.md - the exact case this was
 * built for). {@link StagingModelState.Found#MODEL_AND_PUBLICATION} now always routes through {@link
 * #runRegeneration}: a {@link StagingModelDiff} of the live model (read fresh, {@code deep=true}) against
 * what the accepted proposals currently describe, ADDITIVE changes applied through one {@code
 * apply_data_model_patch} call and republished, NON-ADDITIVE ones only ever reported, never applied - see
 * {@link StagingModelDiff}'s own Javadoc for the exact classification rules and op shapes, and {@link
 * #runRegeneration}'s for what is, and is not, confirmed about a dataset picking up a republished structure.
 * {@link #runModelStep} no longer short-circuits on a DONE {@code STAGING_MODEL} step from a previous run -
 * every call re-evaluates live state, so a later regeneration is never blocked by step history alone - but
 * still records its own {@code STAGING_MODEL} {@code PipelineStep} row per run, {@code detail.mode} set to
 * what happened ({@code CREATE}/{@code REGENERATE}/{@code UP_TO_DATE}, echoed in {@link #generate}'s own
 * answer together with {@code applied[]}/{@code refused[]}). {@link #writeBackSuccess} only marks {@code
 * APPLIED} what {@link StagingModelDiff#blockedEntityNames} does not exclude - see its own Javadoc for the
 * entity-level (not field-level) conservatism this implies.
 */
public final class StagingGenerator
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String STEP_STAGING_MODEL = "STAGING_MODEL";
	private static final String STEP_STAGING_DATASET = "STAGING_DATASET";
	/** Both {@code PipelineStep} steps of this generator run at project scope, like {@code StagingSuggester}'s own {@code STAGING_SUGGEST}. */
	private static final String SCOPE = "";

	private StagingGenerator()
	{
	}

	/**
	 * Staging-live lot (2026-09-27): the phase notes of the run that is generating on THIS thread - a thread-local
	 * rather than one more parameter through every step method; always reset in {@link #generate(Session,
	 * Adaptation, String, com.on.ebx.factory.landing.ProgressNote)}'s finally.
	 */
	private static final ThreadLocal<com.on.ebx.factory.landing.ProgressNote> NOTE = new ThreadLocal<com.on.ebx.factory.landing.ProgressNote>();

	private static void note(final String pText)
	{
		final com.on.ebx.factory.landing.ProgressNote note = StagingGenerator.NOTE.get();
		if (note != null)
		{
			note.note(pText);
		}
	}

	/** Same as {@link #generate(Session, Adaptation, String)}, saying each phase to {@code pNote} (the running screen of step 4). */
	public static ObjectNode generate(final Session pSession, final Adaptation pFactory, final String pProjectCode, final com.on.ebx.factory.landing.ProgressNote pNote)
		throws OperationException
	{
		StagingGenerator.NOTE.set(pNote);
		try
		{
			return StagingGenerator.generate(pSession, pFactory, pProjectCode);
		}
		finally
		{
			StagingGenerator.NOTE.remove();
		}
	}

	public static ObjectNode generate(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		// audit F-15: the staging model and dataset are the STAGING resource's; the queued job already holds it (StagingLoadRun), a REST call does not - see EngineClaim
		final EngineClaim claim = EngineClaim.enter(pFactory, pSession, pProjectCode, "STAGING_GENERATE_DIRECT", FactoryJobs.STAGING);
		try
		{
			return StagingGenerator.generateClaimed(pSession, pFactory, pProjectCode);
		}
		finally
		{
			claim.release();
		}
	}

	private static ObjectNode generateClaimed(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = StagingGenerator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();

		// same reasoning as StagingSuggester's own call: memberEntities must never again depend on someone having
		// re-run the language-model pass since it was added (docs/MASTER-ZONE.md, 2026-09-21 incident) - staging
		// generation does not itself use membership, but master generation reads entities this step produces, so
		// keeping it current at every entry point (not just master's own) is what "never again" actually means
		MemberEntitiesBackfill.run(pSession, pFactory, projectId);

		final StagingModel.Result modelResult = StagingModel.build(StagingGenerator.readEntities(pFactory, projectId));
		if (modelResult.tree.path("tables").size() == 0)
		{
			throw EngineRefusal.coded("missing.noKeyedEntity", "Project " + pProjectCode
				+ " has no accepted staging entity with both an accepted technical key and an accepted business attribute: nothing to generate. "
				+ (modelResult.skippedEntities.isEmpty() ? "Accept entities and attributes first (POST /projects/" + pProjectCode + "/staging/suggest, then review)."
					: "Skipped: " + modelResult.skippedEntities), pProjectCode);
		}

		final String modelName = StagingNames.modelName(pProjectCode);
		final String dataspaceKey = StagingNames.dataspaceKey(pProjectCode, StagingGenerator.stagingSuffix(pFactory));
		final String datasetName = StagingNames.DATASET;

		final ObjectNode out = StagingGenerator.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("stagingModel", modelName);
		out.put("stagingDataspace", dataspaceKey);
		out.put("stagingDataset", datasetName);
		final ArrayNode entitiesNode = out.putArray("entitiesGenerated");
		for (final String name : modelResult.builtEntities)
		{
			entitiesNode.add(name);
		}
		final ArrayNode skippedNode = out.putArray("entitiesSkipped");
		for (final String skip : modelResult.skippedEntities)
		{
			skippedNode.add(skip);
		}
		final ArrayNode notesNode = out.putArray("notes");
		for (final String note : modelResult.notes)
		{
			notesNode.add(note);
		}
		// task C: an accepted MODEL_CONSTRAINT QualityRule is never executed at transfer and never lowered into
		// the model by this class either (that is a later lot - see quality.QualityRuleCheck's own Javadoc) -
		// surfaced here so a person sees it was accepted but nothing has actually enforced it yet, rather than
		// assuming acceptance alone made it real
		final ArrayNode modelConstraintsNode = out.putArray("acceptedModelConstraintsNotLowered");
		for (final String note : StagingGenerator.readAcceptedModelConstraintNotes(pFactory, projectId))
		{
			modelConstraintsNode.add(note);
		}
		final ArrayNode fkNode = out.putArray("foreignKeys");
		for (final StagingModel.ForeignKeyInfo fk : modelResult.foreignKeys)
		{
			final ObjectNode line = fkNode.addObject();
			line.put("entity", fk.entityName);
			line.put("attribute", fk.attributeName);
			line.put("field", fk.fieldName);
			line.put("targetEntity", fk.targetEntityName);
		}

		StagingGenerator.note(RunTexts.preparingModel(modelName).stored());
		final ModelStepResult modelStep = StagingGenerator.runModelStep(pSession, pFactory, projectId, pProjectCode, modelName, modelResult);
		out.put("stagingModelStep", modelStep.alreadyDone ? "SKIPPED_ALREADY_DONE" : "DONE");
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
		out.put("stagingDatasetStep",
			StagingGenerator.runDatasetStepNoted(pSession, pFactory, projectId, modelName, dataspaceKey, datasetName) ? "SKIPPED_ALREADY_DONE" : "DONE");

		StagingGenerator.writeBackSuccess(pSession, pFactory, projectId, dataspaceKey, datasetName, modelResult, modelStep.blockedEntityNames);
		out.put("status", "GENERATED");
		return out;
	}

	/** What {@link #runModelStep} actually did, for {@link #generate}'s own JSON answer and for {@link #writeBackSuccess}'s scoping. */
	private static final class ModelStepResult
	{
		/** Backward-compatible with the pre-round-4 "stagingModelStep" field: true when nothing needed to run at all. */
		final boolean alreadyDone;
		/** {@code CREATE} (model built for the first time, or a MODEL_ONLY resume finishing publish), {@code REGENERATE} (an already-published model had additive and/or refused changes), {@code UP_TO_DATE} (published, and the field-level diff found nothing to change). */
		final String mode;
		/** Change descriptions actually applied this run - empty outside REGENERATE. */
		final List<String> applied;
		/** Change descriptions refused (non-additive) - empty outside REGENERATE. */
		final List<String> refused;
		/** Names of entities {@link #writeBackSuccess} must NOT move to {@code APPLIED} this run: at least one of their changes was refused, so the live model does not (yet) fully reflect what was accepted - conservative at entity granularity, see {@link #writeBackSuccess}'s own Javadoc. */
		final Set<String> blockedEntityNames;

		ModelStepResult(final boolean pAlreadyDone, final String pMode, final List<String> pApplied, final List<String> pRefused, final Set<String> pBlockedEntityNames)
		{
			this.alreadyDone = pAlreadyDone;
			this.mode = pMode;
			this.applied = pApplied;
			this.refused = pRefused;
			this.blockedEntityNames = pBlockedEntityNames;
		}
	}

	// ------------------------------------------------------------------------------------------------- STAGING_MODEL

	/**
	 * Always re-evaluates from live state, never short-circuited by a prior DONE run: round 4 (2026-09-20)
	 * explicitly requires a DONE {@code STAGING_MODEL} to not block a later regeneration once more entities/
	 * attributes are accepted - the decision must come from what the server actually has (via {@link
	 * StagingModelState}/{@link StagingModelDiff}), not from step history. Every call still records its own
	 * {@code STAGING_MODEL} {@code PipelineStep} row (the steps table already keeps one row per run), with
	 * {@code detail.mode} set to what actually happened.
	 */
	private static ModelStepResult runModelStep(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode, final String pModelName,
		final StagingModel.Result pModelResult) throws OperationException
	{
		final String stepId = PipelineSteps.begin(pSession, pFactory, pProjectId, StagingGenerator.STEP_STAGING_MODEL, StagingGenerator.SCOPE);
		final ObjectNode detail = StagingGenerator.MAPPER.createObjectNode();
		try
		{
			final McpClient client = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);

			// resumability: build_data_model refuses a name that already exists, so a run resumed after a partial
			// failure must not blindly build again - find out first, read-only, what the server already has.
			// deep=true unconditionally: a regeneration (below) needs the full field/constraint shape anyway, and
			// asking for it up front avoids a second live read when the model turns out to already be published.
			final ObjectNode getModelArgs = StagingGenerator.MAPPER.createObjectNode();
			getModelArgs.put("data_model_name", pModelName);
			getModelArgs.put("deep", true);
			final McpClient.ToolResult modelLookup = client.callTool("get_data_model", getModelArgs);
			detail.set("existingModel", StagingGenerator.toolDetail(getModelArgs, modelLookup));
			final boolean modelExists = modelLookup.json != null && modelLookup.json.path("found").asBoolean(false);

			final List<String> existingTablePaths = new ArrayList<String>();
			if (modelExists && modelLookup.json.path("tables").isArray())
			{
				for (final JsonNode t : modelLookup.json.path("tables"))
				{
					existingTablePaths.add(t.path("path").asText());
				}
			}

			StagingModelState.Found found = StagingModelState.Found.NOTHING;
			if (modelExists)
			{
				final ObjectNode getPubArgs = StagingGenerator.MAPPER.createObjectNode();
				getPubArgs.put("publication_name", pModelName);
				final McpClient.ToolResult pubLookup = client.callTool("get_publication", getPubArgs);
				detail.set("existingPublication", StagingGenerator.toolDetail(getPubArgs, pubLookup));
				final boolean published = pubLookup.json != null && pubLookup.json.path("found").asBoolean(false) && pubLookup.json.hasNonNull("version");
				found = published ? StagingModelState.Found.MODEL_AND_PUBLICATION : StagingModelState.Found.MODEL_ONLY;
			}

			final List<String> plannedTablePaths = new ArrayList<String>();
			for (final JsonNode table : pModelResult.tree.path("tables"))
			{
				plannedTablePaths.add(table.path("path").asText());
			}

			final StagingModelState.Decision decision = StagingModelState.decide(found, pModelName, pProjectCode, plannedTablePaths, existingTablePaths);
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
				final ModelStepResult regenerationResult = StagingGenerator.runRegeneration(client, pModelName, pModelResult, modelLookup.json.path("tables"), detail);
				final String regenerationMessage = "REGENERATE".equals(regenerationResult.mode)
					? regenerationResult.applied.size() + " additive change(s) applied and republished" + (regenerationResult.refused.isEmpty() ? "."
						: ", " + regenerationResult.refused.size() + " change(s) refused (never applied, see refused[]).")
					: "Staging model already published; the accepted proposals already match it exactly, nothing to change.";
				PipelineSteps.end(pSession, pFactory, stepId, true, regenerationMessage, detail.toString());
				return regenerationResult;
			}

			if (decision.build)
			{
				final String treeJson = pModelResult.tree.toString();
				StagingGenerator.note(RunTexts.creatingStagingModel(pModelName, pModelResult.builtEntities.size()).stored());
				// one build_data_model call, its own answer is the gate (wave 2 lot V2-C, ModelBuilder): no rehearsal first
				ModelBuilder.build(pSession, pFactory, client, "staging", pProjectCode, pModelName, treeJson, detail);
			}

			// decision.patch is true whenever the model is not yet published (NOTHING after the build above, or a
			// MODEL_ONLY resume where an earlier run built it but never published - see StagingModelState's own
			// Javadoc for the defect this guards against, and this class's own Javadoc for the tool-contract lines
			// that make re-applying these three facets on every such run safe rather than merely convenient
			if (decision.patch && pModelResult.operations.size() > 0)
			{
				final ObjectNode patchArgs = StagingGenerator.MAPPER.createObjectNode();
				patchArgs.put("data_model_name", pModelName);
				patchArgs.put("operations_json", pModelResult.operations.toString());
				// no terminal validate+compile of its own (~2 s): publish_data_model, which always follows in this run, is the gate (wave 2 lot V2-C);
				// the per-op failure count below is still checked
				patchArgs.put("gate", false);
				StagingGenerator.note(RunTexts.updatingModel(pModelName).stored());
				final McpClient.ToolResult patch = client.callTool("apply_data_model_patch", patchArgs);
				detail.set("patch", StagingGenerator.toolDetail(patchArgs, patch));
				if (patch.error)
				{
					throw EngineRefusal.coded("external.patchFacets", "apply_data_model_patch could not apply the facets of " + pModelName + ": " + StagingGenerator.shortText(patch.text), pModelName);
				}
				final int failed = patch.json == null ? 0 : patch.json.path("failed").asInt(0);
				final boolean patchCompiled = patch.json == null || patch.json.path("compiled").asBoolean(true);
				final boolean patchValid = patch.json == null || patch.json.path("valid").asBoolean(true);
				if (failed > 0 || !patchCompiled || !patchValid)
				{
					throw EngineRefusal.coded("external.patchIncomplete", "apply_data_model_patch left " + pModelName + " with " + failed
						+ " failed operation(s) or an invalid/uncompiled model: " + StagingGenerator.shortText(patch.text), pModelName);
				}
			}

			if (decision.publish)
			{
				StagingGenerator.ensureEmbedded(client, pModelName, detail);

				// no separate validate_data_model/compile_data_model call here: publish_data_model IS the gate
				// (see this class's own Javadoc for the exact contract lines) - it refuses to publish, with no
				// partial write, unless the model both validates and compiles, so a caller only has to read its
				// structured answer correctly rather than trust a transport-level "no error" as success
				final ObjectNode publishArgs = StagingGenerator.MAPPER.createObjectNode();
				publishArgs.put("data_model_name", pModelName);
				StagingGenerator.note(RunTexts.publishingStagingModel(pModelName).stored());
				final McpClient.ToolResult publish = client.callTool("publish_data_model", publishArgs);
				detail.set("publish", StagingGenerator.toolDetail(publishArgs, publish));
				if (publish.error)
				{
					throw EngineRefusal.coded("external.publishModel", "publish_data_model could not publish " + pModelName + ": " + StagingGenerator.shortText(publish.text), pModelName);
				}
				if (!PublishDecision.published(publish.json))
				{
					throw EngineRefusal.coded("invalid.modelNotValid", "publish_data_model refused to publish " + pModelName + " (it does not validate/compile): " + StagingGenerator.shortText(publish.text), pModelName);
				}
			}

			final String message = decision.build ? "Staging model built, patched and published."
				: "Staging model already existed with the accepted tables (MODEL_ONLY resume); re-patched and published.";
			detail.put("mode", "CREATE");
			PipelineSteps.end(pSession, pFactory, stepId, true, message, detail.toString());
			return new ModelStepResult(false, "CREATE", Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<String>emptySet());
		}
		catch (final McpException ex)
		{
			detail.put("transportError", ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw EngineRefusal.codedWithCause("external.mcpBuildModel", "MCP call failed while building the staging model of " + pProjectCode + ": " + ex.getMessage(), ex, pProjectCode);
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
	 * The staging model already exists AND is published ({@link StagingModelState.Found#MODEL_AND_PUBLICATION}):
	 * compares what {@code get_data_model(deep=true)} just reported against what the currently accepted
	 * proposals describe ({@link StagingModelDiff}), applies every ADDITIVE change through a single {@code
	 * apply_data_model_patch} call (new tables/fields, longer maxLength, no-longer-mandatory - see {@link
	 * StagingModelDiff}'s own Javadoc for the exact op shapes and their ordering) and republishes, and never
	 * even attempts a NON-ADDITIVE one (a removed table/field, a type change, a shorter maxLength, optional
	 * becoming mandatory, anything touching a primary key or an existing field's foreign key) - those are only
	 * ever reported back in {@link ModelStepResult#refused}, for a person to act on.
	 * <p>
	 * <b>Whether the dataset built on this publication picks up the new structure automatically, or needs an
	 * operation of its own, is NOT confirmed by a live regeneration run</b> (never executed live this round -
	 * this whole class only ever calls read-only MCP tools during development; {@code apply_data_model_patch}
	 * and {@code publish_data_model} are called for real only when this code actually runs on the server).
	 * Read live 2026-09-20: {@code list_operations(category="lifecycle")} lists {@code create_dataset}
	 * ("Instantiate a dataset FROM a published data model") but nothing to refresh an EXISTING dataset's
	 * structure, and {@code get_publication}/{@code list_data_model_publications} describe a publication as
	 * carrying its own datasets, updated in place by republishing under the SAME name (the same assumption
	 * {@code StagingGenerator}'s ordinary build/patch/publish path already relies on for a {@code MODEL_ONLY}
	 * resume - re-publishing there does not recreate the dataset either). No tool in this server's surface
	 * offers anything else to call, so republishing is the only lever available regardless - but the inference
	 * that it is SUFFICIENT (rather than merely necessary) is not independently verified live; flagged in the
	 * final report as the one unverified assumption of this path.
	 */
	private static ModelStepResult runRegeneration(final McpClient pClient, final String pModelName, final StagingModel.Result pModelResult,
		final JsonNode pExistingTablesJson, final ObjectNode pDetail) throws McpException, OperationException
	{
		final List<StagingModelDiff.ExistingTable> existingTables = StagingModelDiff.parseExisting(pExistingTablesJson);
		final StagingModelDiff.Result diff = StagingModelDiff.diff(existingTables, pModelResult);

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
		final Set<String> blockedEntityNames = new HashSet<String>(diff.blockedEntityNames());

		if (diff.isUpToDate())
		{
			pDetail.put("mode", "UP_TO_DATE");
			return new ModelStepResult(true, "UP_TO_DATE", applied, refused, blockedEntityNames);
		}
		pDetail.put("mode", "REGENERATE");

		if (diff.operations.size() > 0)
		{
			final ObjectNode patchArgs = StagingGenerator.MAPPER.createObjectNode();
			patchArgs.put("data_model_name", pModelName);
			patchArgs.put("operations_json", diff.operations.toString());
			// no terminal validate+compile of its own (~2 s): publish_data_model, which always follows in this run, is the gate (wave 2 lot V2-C);
			// the per-op failure count below is still checked
			patchArgs.put("gate", false);
			final McpClient.ToolResult patch = pClient.callTool("apply_data_model_patch", patchArgs);
			pDetail.set("regeneratePatch", StagingGenerator.toolDetail(patchArgs, patch));
			if (patch.error)
			{
				throw EngineRefusal.coded("external.patchRegenerate", "apply_data_model_patch could not apply the regeneration of " + pModelName + ": " + StagingGenerator.shortText(patch.text), pModelName);
			}
			final int failed = patch.json == null ? 0 : patch.json.path("failed").asInt(0);
			final boolean patchCompiled = patch.json == null || patch.json.path("compiled").asBoolean(true);
			final boolean patchValid = patch.json == null || patch.json.path("valid").asBoolean(true);
			if (failed > 0 || !patchCompiled || !patchValid)
			{
				throw EngineRefusal.coded("external.patchIncomplete", "apply_data_model_patch left " + pModelName + " with " + failed
					+ " failed operation(s) or an invalid/uncompiled model while regenerating: " + StagingGenerator.shortText(patch.text), pModelName);
			}

			// republish under the SAME name: see this method's own Javadoc for what is, and is not, confirmed about a
			// dataset picking up the new structure from this alone
			final ObjectNode publishArgs = StagingGenerator.MAPPER.createObjectNode();
			publishArgs.put("data_model_name", pModelName);
			final McpClient.ToolResult publish = pClient.callTool("publish_data_model", publishArgs);
			pDetail.set("regeneratePublish", StagingGenerator.toolDetail(publishArgs, publish));
			if (publish.error)
			{
				throw EngineRefusal.coded("external.publishModel", "publish_data_model could not republish " + pModelName + " after regeneration: " + StagingGenerator.shortText(publish.text), pModelName);
			}
			if (!PublishDecision.published(publish.json))
			{
				throw EngineRefusal.coded("invalid.modelNotValid", "publish_data_model refused to republish " + pModelName + " after regeneration (it does not validate/compile): " + StagingGenerator.shortText(publish.text), pModelName);
			}
		}
		return new ModelStepResult(false, "REGENERATE", applied, refused, blockedEntityNames);
	}

	/**
	 * A model {@code build_data_model} creates is not packaged inside any module (no {@code pathInModule}),
	 * so it should already read {@code publicationMode: EMBEDDED}; checked and fixed anyway, defensively -
	 * same reasoning, and the same code shape, as {@code LandingGenerator.ensureEmbedded}.
	 */
	private static void ensureEmbedded(final McpClient pClient, final String pModelName, final ObjectNode pDetail) throws McpException, OperationException
	{
		final ObjectNode getArgs = StagingGenerator.MAPPER.createObjectNode();
		getArgs.put("data_model_name", pModelName);
		getArgs.put("operation", "get_global_properties");
		final McpClient.ToolResult props = pClient.callTool("execute_query", getArgs);
		pDetail.set("globalProperties", StagingGenerator.toolDetail(getArgs, props));
		final String mode = props.json == null ? null : props.json.path("configuration").path("publicationMode").asText(null);
		if ("EMBEDDED".equals(mode))
		{
			return;
		}
		final ObjectNode setArgs = StagingGenerator.MAPPER.createObjectNode();
		setArgs.put("data_model_name", pModelName);
		setArgs.put("operation", "set_global_properties");
		final ObjectNode moduleProperties = StagingGenerator.MAPPER.createObjectNode();
		moduleProperties.put("publicationMode", "EMBEDDED");
		final ObjectNode operationArgs = StagingGenerator.MAPPER.createObjectNode();
		operationArgs.set("module_properties", moduleProperties);
		setArgs.put("arguments_json", operationArgs.toString());
		final McpClient.ToolResult set = pClient.callTool("execute_operation", setArgs);
		pDetail.set("publicationMode", StagingGenerator.toolDetail(setArgs, set));
		if (set.error)
		{
			throw EngineRefusal.coded("external.publicationMode", "Could not set the publication mode of " + pModelName + " to EMBEDDED: " + StagingGenerator.shortText(set.text), pModelName);
		}
	}

	// ----------------------------------------------------------------------------------------------- STAGING_DATASET

	/** @return true when the step was already DONE (nothing run again) */
	private static boolean runDatasetStepNoted(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pModelName,
		final String pDataspaceKey, final String pDatasetName) throws OperationException
	{
		StagingGenerator.note(RunTexts.creatingStagingDataset(pDatasetName, pDataspaceKey).stored());
		return StagingGenerator.runDatasetStep(pSession, pFactory, pProjectId, pModelName, pDataspaceKey, pDatasetName);
	}

	private static boolean runDatasetStep(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pModelName,
		final String pDataspaceKey, final String pDatasetName) throws OperationException
	{
		if (PipelineSteps.isDone(pFactory, pProjectId, StagingGenerator.STEP_STAGING_DATASET, StagingGenerator.SCOPE))
		{
			return true;
		}
		final String stepId = PipelineSteps.begin(pSession, pFactory, pProjectId, StagingGenerator.STEP_STAGING_DATASET, StagingGenerator.SCOPE);
		final ObjectNode detail = StagingGenerator.MAPPER.createObjectNode();
		// 1.4.2 (X3-11): true once THIS step created the zone, so that an empty refusal of create_dataset can remove it again (ZoneDatasetDenial)
		boolean zoneCreatedHere = false;
		try
		{
			final Repository repository = pFactory.getHome().getRepository();
			AdaptationHome stagingHome = repository.lookupHome(HomeKey.forBranchName(pDataspaceKey));
			final ObjectNode dataspaceDetail = detail.putObject("dataspace");
			dataspaceDetail.put("key", pDataspaceKey);
			if (stagingHome == null)
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
				spec.setDescription(UserMessage.createInfo("Staging zone of project " + pProjectId + ": typed, constrained entities fed from the landing."));
				stagingHome = repository.createHome(spec, pSession);
				zoneCreatedHere = true;
				dataspaceDetail.put("created", true);
			}
			else
			{
				dataspaceDetail.put("created", false);
			}

			final Adaptation existingDataset = stagingHome.findAdaptationOrNull(AdaptationName.forName(pDatasetName));
			final ObjectNode datasetDetail = detail.putObject("dataset");
			datasetDetail.put("name", pDatasetName);
			if (existingDataset == null)
			{
				final McpClient client = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
				final ObjectNode createArgs = StagingGenerator.MAPPER.createObjectNode();
				createArgs.put("data_model_name", pModelName);
				createArgs.put("operation", "create_dataset");
				final ObjectNode operationArgs = StagingGenerator.MAPPER.createObjectNode();
				operationArgs.put("publication_name", pModelName);
				operationArgs.put("dataset_name", pDatasetName);
				operationArgs.put("dataspace", pDataspaceKey);
				createArgs.put("arguments_json", operationArgs.toString());
				final McpClient.ToolResult create = client.callTool("execute_operation", createArgs);
				datasetDetail.set("call", StagingGenerator.toolDetail(createArgs, create));
				if (create.error)
				{
					if (ZoneDatasetDenial.emptyToolFailure(create.text))
					{
						throw ZoneDatasetDenial.translate(pSession, pFactory, pDataspaceKey, zoneCreatedHere);
					}
					throw EngineRefusal.coded("external.createDataset", "create_dataset could not create dataset " + pDatasetName + " in " + pDataspaceKey + ": " + StagingGenerator.shortText(create.text), pDatasetName, pDataspaceKey);
				}
				datasetDetail.put("skipped", false);
			}
			else
			{
				datasetDetail.put("skipped", true);
			}

			PipelineSteps.end(pSession, pFactory, stepId, true, "Staging dataspace and dataset ready.", detail.toString());
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
			throw EngineRefusal.codedWithCause("external.mcpCreateDataset", "MCP call failed while creating the staging dataset: " + ex.getMessage(), ex);
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

	// ------------------------------------------------------------------------------------------------------- write-back

	/**
	 * Every step succeeded (or was already done): {@code Project.stagingDataspace}/{@code stagingDataset} and
	 * {@code status=STAGING} are written, and every entity a table was actually built for
	 * ({@link StagingModel.Result#builtEntities}) moves to {@code APPLIED} together with its own accepted
	 * attributes - a skipped entity's rows, or an attribute this run left {@code PROPOSED}/{@code REJECTED},
	 * are never touched here. An attribute just marked {@code APPLIED} carries its {@code ACCEPTED} {@code
	 * AttributeMapping} rows to {@code APPLIED} too ({@code MappingFollowRule}'s posture applied one more
	 * time: they are what the generated field is actually fed by, per {@code TransferPlan}/{@code
	 * StagingLoader}) - a mapping still {@code PROPOSED} at this point (never accepted, by a person or by
	 * {@code StagingSuggester}'s backfill) is left alone, same as any other undecided line.
	 * <p>
	 * <b>Round 4 (2026-09-20) scoping:</b> in {@code REGENERATE} mode, {@code pModelResult.builtEntities}
	 * names every currently accepted entity, not only the ones this specific run actually changed on the
	 * live model - most of them were already {@code APPLIED} long ago and simply stay that way ({@code
	 * ACCEPTED} is the only status this method ever moves). {@code pBlockedEntityNames}
	 * ({@link ModelStepResult#blockedEntityNames}, from {@link StagingModelDiff#blockedEntityNames}) is what
	 * actually matters here: an entity with ANY non-additive (refused) change this run is skipped entirely -
	 * neither the entity row NOR any of its attribute rows move to {@code APPLIED} - even if some OTHER field
	 * of that same table WAS additively applied. This is conservative at entity, not field, granularity: a
	 * field that genuinely did reach the live model this run may still show {@code ACCEPTED} instead of
	 * {@code APPLIED} until a later run with nothing left refused on that entity. Deliberately so - "line
	 * blocked by a non-additive refusal must stay in its prior status" is the hard requirement; never
	 * mismarking something {@code APPLIED} that the live model does not fully reflect matters more here than
	 * catching every safe case at maximum precision.
	 */
	private static void writeBackSuccess(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pDataspaceKey,
		final String pDatasetName, final StagingModel.Result pModelResult, final Set<String> pBlockedEntityNames) throws OperationException
	{
		final AdaptationTable projects = pFactory.getTable(FactoryPaths.PROJECT);
		final AdaptationTable entitiesTable = pFactory.getTable(FactoryPaths.STAGING_ENTITY);
		final AdaptationTable attributesTable = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE);
		final AdaptationTable mappingsTable = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING);
		final Set<String> builtEntityNames = new HashSet<String>(pModelResult.builtEntities);
		builtEntityNames.removeAll(pBlockedEntityNames);

		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation project = projects.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
				if (project != null)
				{
					final ValueContextForUpdate value = pContext.getContext(project.getAdaptationName());
					value.setValue(pDataspaceKey, FactoryPaths.Project.STAGING_DATASPACE);
					value.setValue(pDatasetName, FactoryPaths.Project.STAGING_DATASET);
					value.setValue("STAGING", FactoryPaths.Project.STATUS);
					pContext.doModifyContent(project, value);
				}

				final RequestResult entityRows = entitiesTable.createRequestResult("./project=" + CatalogReader.quote(pProjectId));
				try
				{
					for (Adaptation entityRow = entityRows.nextAdaptation(); entityRow != null; entityRow = entityRows.nextAdaptation())
					{
						final String entityName = entityRow.getString(FactoryPaths.StagingEntity.NAME);
						if (!builtEntityNames.contains(entityName))
						{
							continue;
						}
						if ("ACCEPTED".equals(entityRow.getString(FactoryPaths.StagingEntity.STATUS)))
						{
							final ValueContextForUpdate entityValue = pContext.getContext(entityRow.getAdaptationName());
							entityValue.setValue("APPLIED", FactoryPaths.StagingEntity.STATUS);
							pContext.doModifyContent(entityRow, entityValue);
						}
						final String entityId = entityRow.getOccurrencePrimaryKey().format();
						final RequestResult attributeRows = attributesTable.createRequestResult("./entity=" + CatalogReader.quote(entityId));
						try
						{
							for (Adaptation attributeRow = attributeRows.nextAdaptation(); attributeRow != null; attributeRow = attributeRows.nextAdaptation())
							{
								if (!"ACCEPTED".equals(attributeRow.getString(FactoryPaths.StagingAttribute.STATUS)))
								{
									continue;
								}
								final ValueContextForUpdate attributeValue = pContext.getContext(attributeRow.getAdaptationName());
								attributeValue.setValue("APPLIED", FactoryPaths.StagingAttribute.STATUS);
								pContext.doModifyContent(attributeRow, attributeValue);

								// mappings follow: an attribute just marked APPLIED is fed by whatever mapping(s) are ACCEPTED
								// (MappingFollowRule already got them there, via ProposalReviews' live cascade or StagingSuggester's
								// backfill) - those become APPLIED too, since they are what the generated field is actually fed by
								final String attributeId = attributeRow.getOccurrencePrimaryKey().format();
								final RequestResult mappingRowsOfAttribute = mappingsTable.createRequestResult("./attribute=" + CatalogReader.quote(attributeId));
								try
								{
									for (Adaptation mappingRow = mappingRowsOfAttribute.nextAdaptation(); mappingRow != null; mappingRow = mappingRowsOfAttribute
										.nextAdaptation())
									{
										if (!"ACCEPTED".equals(mappingRow.getString(FactoryPaths.AttributeMapping.STATUS)))
										{
											continue;
										}
										final ValueContextForUpdate mappingValue = pContext.getContext(mappingRow.getAdaptationName());
										mappingValue.setValue("APPLIED", FactoryPaths.AttributeMapping.STATUS);
										pContext.doModifyContent(mappingRow, mappingValue);
									}
								}
								finally
								{
									mappingRowsOfAttribute.close();
								}
							}
						}
						finally
						{
							attributeRows.close();
						}
					}
				}
				finally
				{
					entityRows.close();
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.writeBackStaging", "The staging model was generated, but writing it back failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	// ------------------------------------------------------------------------------------------------------------- read

	/**
	 * Every {@code StagingEntity} of the project - whatever its status, {@link StagingModel#build} itself
	 * decides what is generated - with its {@code StagingAttribute}s, {@code referenceEntity} already
	 * resolved to the target entity's NAME (a two-pass read: every entity name is known before any attribute
	 * is read, so a forward reference - an entity proposed after the one it is referenced from - still
	 * resolves), and {@code memberEntities} resolved the same way, from ids to names.
	 */
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
				row.getString(FactoryPaths.StagingEntity.STATUS),
				StagingGenerator.readAttributes(pFactory, entityId, row.getString(FactoryPaths.StagingEntity.STATUS), entityIdToName), memberNames));
		}
		return out;
	}

	private static List<StagingModel.AttributeInfo> readAttributes(final Adaptation pFactory, final String pEntityId, final String pEntityStatus,
		final Map<String, String> pEntityIdToName)
	{
		final List<StagingModel.AttributeInfo> out = new ArrayList<StagingModel.AttributeInfo>();
		// fix/parent-link-follows-entity (2026-10-21): the parent link has no decision of its own (ParentLinkRule) - it is generated
		// from its entity's status, so a row left PROPOSED (decided before this rule, or never shown) never drops the parent foreign key
		final java.util.Set<String> parentLinkIds = ParentLinkReader.attributeIdsOf(pFactory, pEntityId);
		final String followedStatus = TechnicalKeyRule.cascadedStatus(pEntityStatus);
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
				final String ownStatus = row.getString(FactoryPaths.StagingAttribute.STATUS);
				final String status = followedStatus != null && !"APPLIED".equals(ownStatus) && parentLinkIds.contains(row.getOccurrencePrimaryKey().format())
					? followedStatus : ownStatus;
				out.add(new StagingModel.AttributeInfo(row.getString(FactoryPaths.StagingAttribute.NAME), row.getString(FactoryPaths.StagingAttribute.LABEL),
					row.getString(FactoryPaths.StagingAttribute.DATA_TYPE), maxLength, mandatory, primaryKey, referenceEntityName, status));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Every ACCEPTED-or-APPLIED {@code QualityRule} of kind {@code MODEL_CONSTRAINT} in this project, as one
	 * human-readable line each - "accepted, but nothing has lowered it into the model yet" (task C). A person
	 * accepting a {@code REFERENCE}-kind rule at {@code MODEL_CONSTRAINT} severity (docs/NORMALISATION.md sec
	 * 2.1: "a closed list WITH a real reference table... prefer a foreign key over a copy-pasted enumeration")
	 * sees it here every time they generate/regenerate the staging model, until a later lot actually lowers it
	 * into a foreign key/facet - this class deliberately does not attempt that lowering itself.
	 */
	private static List<String> readAcceptedModelConstraintNotes(final Adaptation pFactory, final String pProjectId)
	{
		final Map<String, String> entityNameById = new HashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				entityNameById.put(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.StagingEntity.NAME));
			}
		}
		finally
		{
			entityRows.close();
		}
		if (entityNameById.isEmpty())
		{
			return Collections.emptyList();
		}

		final List<String> out = new ArrayList<String>();
		final RequestResult ruleRows = pFactory.getTable(FactoryPaths.QUALITY_RULE)
			.createRequestResult("./kind='" + QualityDetectors.MODEL_CONSTRAINT + "'");
		try
		{
			for (Adaptation row = ruleRows.nextAdaptation(); row != null; row = ruleRows.nextAdaptation())
			{
				final String entityName = entityNameById.get(row.getString(FactoryPaths.QualityRule.ENTITY));
				if (entityName == null || !TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.QualityRule.STATUS)))
				{
					continue; // another project's rule, or not accepted
				}
				final String attributeId = row.getString(FactoryPaths.QualityRule.ATTRIBUTE);
				String label = entityName;
				if (attributeId != null && !attributeId.trim().isEmpty())
				{
					final Adaptation attributeRow = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(attributeId));
					final String attributeName = attributeRow == null ? null : attributeRow.getString(FactoryPaths.StagingAttribute.NAME);
					if (attributeName != null)
					{
						label = entityName + "." + attributeName;
					}
				}
				final String kind = row.getString(FactoryPaths.QualityRule.KIND);
				final String argument = row.getString(FactoryPaths.QualityRule.ARGUMENT);
				out.add(label + ": accepted constraint (" + kind + (argument == null || argument.trim().isEmpty() ? "" : " " + argument)
					+ ") not yet lowered into the model - lowering a reference constraint into a foreign key is a later lot.");
			}
		}
		finally
		{
			ruleRows.close();
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

	/** Package-visible (not {@code private}) since the project-deletion lot: {@code ebx.ProjectDeleter} needs the exact same computed dataspace key to recognise a factory-generated staging zone. */
	static String stagingSuffix(final Adaptation pFactory) throws OperationException
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (settings == null)
		{
			throw EngineRefusal.coded("missing.settings", "MDM Factory settings: the DEFAULT row is missing");
		}
		return settings.getString(FactoryPaths.Settings.STAGING_SUFFIX);
	}

	// ------------------------------------------------------------------------------------------------------------- detail

	private static ObjectNode toolDetail(final ObjectNode pArguments, final McpClient.ToolResult pResult)
	{
		final ObjectNode node = StagingGenerator.MAPPER.createObjectNode();
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
