package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.capability.DerivationApplyFailure;
import com.on.ebx.factory.capability.DerivationBodyBuilder;
import com.on.ebx.factory.capability.DerivationParameters;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.PublishDecision;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.master.DerivationMasterTarget;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.staging.StagingModelDiff;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lot T2 (2026-10-04): the two script triggers of a derivation on the MASTER table too. The decision (is the master eligible, which model, table and names)
 * is {@link DerivationMasterTarget} (pure, tested); the MCP calls are {@link DerivationTriggers}'; this class strings them together for the three
 * moments a master table has to carry them:
 * <ul>
 * <li>{@link #applyAfterStaging}: at the end of {@code DerivationApplier.apply}, once the staging part succeeded and was recorded - declares, authors,
 * publishes the master model and verifies; a failure here never undoes or fails the staging apply;</li>
 * <li>{@link #ensureForProject}: inside {@code MasterGenerator}, for every APPLIED derivation of the promoted entities, declared and authored on the
 * model DRAFT so that the generator's OWN publish carries them (one publish, never a second one);</li>
 * <li>{@link #removeFromMaster}: {@code DerivationApplier.remove} takes the two triggers off the master table by name and republishes it.</li>
 * </ul>
 * <b>The master bodies are built with NO mandatory flag.</b> The staging body leaves a bound attribute unguarded when it is mandatory in staging (it can
 * never be null there); a master business attribute is never mandatory ({@code MasterModel}: a golden record the add-on creates may leave it empty), so
 * the same unguarded call would hand a null to the library function. Everything else - function, parameters, literals, names - is the staging body.
 * <p>
 * <b>Never removes a trigger it did not just declare when something fails.</b> {@link DerivationApplyFailure} decides, exactly as on staging: triggers
 * that were already on the master model are left declared (taking them off the draft would make the next publish of the master model drop them
 * silently) and their previous sources are written back; only triggers this round added are unset, by name. {@code unset_element_property} is never
 * called without {@code item}: the master table carries the Match and Merge add-on's own trigger at runtime and a whole-list wipe would be fatal.
 */
final class DerivationMasterTriggers
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The two triggers are declared and authored (and, for an apply, published and verified). */
	static final String DECLARED = "DECLARED";
	/** Nothing was done and nothing failed: see {@code reason}. */
	static final String SKIPPED = "SKIPPED";
	/** The master part failed (the staging part is unaffected): see {@code reason}. */
	static final String FAILED = "FAILED";
	/** The triggers were taken off the master table and the model republished. */
	static final String REMOVED = "REMOVED";
	/** Removal: the master model, the table or the triggers were not there. */
	static final String ABSENT = "ABSENT";

	private DerivationMasterTriggers()
	{
	}

	// ---------------------------------------------------------------------------------------------------------- target

	/** What the master zone offers a derivation: whether the model exists and the exact field names of the entity's table ({@code null} when it has none). */
	static final class Target
	{
		final boolean modelExists;
		final Set<String> tableFields;

		Target(final boolean pModelExists, final Set<String> pTableFields)
		{
			this.modelExists = pModelExists;
			this.tableFields = pTableFields;
		}
	}

	/** The master model as it exists now ({@code get_data_model}, deep): used by an apply, which runs on a master that was generated earlier. */
	static Target liveTarget(final McpClient pDataModelClient, final String pProjectCode, final String pEntityName, final ObjectNode pDetail) throws McpException
	{
		final ObjectNode args = DerivationMasterTriggers.MAPPER.createObjectNode();
		args.put("data_model_name", MasterNames.modelName(pProjectCode));
		args.put("deep", true);
		final McpClient.ToolResult lookup = pDataModelClient.callTool("get_data_model", args);
		pDetail.set("masterGetModel", DerivationApplier.toolDetail(args, lookup));
		if (lookup.error || lookup.json == null || !lookup.json.path("found").asBoolean(false))
		{
			return new Target(false, null);
		}
		final StagingModelDiff.ExistingTable table = DerivationMasterTriggers.findTable(StagingModelDiff.parseExisting(lookup.json.path("tables")), pEntityName);
		if (table == null)
		{
			return new Target(true, null);
		}
		final Set<String> fields = new LinkedHashSet<String>();
		for (final StagingModelDiff.ExistingField field : table.fields)
		{
			fields.add(field.name);
		}
		return new Target(true, fields);
	}

	/** The master model the generator is about to publish: its planned fields ({@link MasterModel.Result#fields}), the model being on the draft. */
	static Target plannedTarget(final MasterModel.Result pModelResult, final String pEntityName)
	{
		if (!pModelResult.builtEntities.contains(pEntityName))
		{
			return new Target(true, null);
		}
		final Set<String> fields = new LinkedHashSet<String>();
		for (final MasterModel.FieldInfo field : pModelResult.fields)
		{
			if (pEntityName.equals(field.entityName))
			{
				fields.add(field.masterField);
			}
		}
		return new Target(true, fields);
	}

	private static StagingModelDiff.ExistingTable findTable(final List<StagingModelDiff.ExistingTable> pTables, final String pEntityName)
	{
		for (final StagingModelDiff.ExistingTable table : pTables)
		{
			if (table.name.equals(pEntityName))
			{
				return table;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------ apply (staging done)

	/**
	 * The master part of {@code DerivationApplier.apply}. Never throws: the answer's {@code status} is {@link #DECLARED}, {@link #SKIPPED} (with a
	 * {@code reason}, and {@code noteworthy} when it deserves a line on the row) or {@link #FAILED}.
	 */
	static ObjectNode applyAfterStaging(final McpClient pDataModelClient, final McpClient pScriptingClient, final String pProjectCode, final DerivationApplier.Prepared p,
		final DerivationApplier.Bodies pBodies, final ObjectNode pDetail)
	{
		try
		{
			final Target target = DerivationMasterTriggers.liveTarget(pDataModelClient, pProjectCode, p.entityName, pDetail);
			final DerivationMasterTarget.Decision decision = DerivationMasterTarget.decide(pProjectCode, p.entityName, p.newAttributeName, p.boundAttributes, target.modelExists,
				target.tableFields, false);
			if (!decision.eligible())
			{
				return DerivationMasterTriggers.skipped(decision);
			}
			return DerivationMasterTriggers.ensure(pDataModelClient, pScriptingClient, decision, p, pBodies, true, pDetail, "master");
		}
		catch (final Exception ex)
		{
			final ObjectNode node = DerivationMasterTriggers.MAPPER.createObjectNode();
			node.put("status", DerivationMasterTriggers.FAILED);
			node.put("reason", ErrorText.of(ex));
			return node;
		}
	}

	/** The sentence for {@code applyMessage} after an apply whose master part failed or was skipped for a reason worth telling; {@code null} when there is nothing to say. */
	static String noteFor(final ObjectNode pMaster)
	{
		final String status = pMaster.path("status").asText("");
		if (DerivationMasterTriggers.FAILED.equals(status))
		{
			return "Applied on staging, but the triggers could not be put on the master table (" + pMaster.path("reason").asText("") + "): apply again once this is fixed.";
		}
		if (DerivationMasterTriggers.SKIPPED.equals(status) && pMaster.path("noteworthy").asBoolean(false))
		{
			return "Applied on staging; the master table does not get the triggers: " + pMaster.path("reason").asText("") + ".";
		}
		return null;
	}

	private static ObjectNode skipped(final DerivationMasterTarget.Decision pDecision)
	{
		final ObjectNode node = DerivationMasterTriggers.MAPPER.createObjectNode();
		node.put("status", DerivationMasterTriggers.SKIPPED);
		node.put("kind", pDecision.kind.name());
		node.put("reason", pDecision.reason);
		node.put("noteworthy", pDecision.noteworthy());
		node.put("model", pDecision.modelName);
		node.put("table", pDecision.tablePath);
		return node;
	}

	// --------------------------------------------------------------------------------------------------------- ensure

	/**
	 * Declares both triggers on the master table, authors their bodies and, when {@code pPublish}, publishes the master model and verifies. Idempotent
	 * (the same name replaces in place). Never throws: a failure is rolled back as {@link DerivationApplyFailure} says and answered as {@link #FAILED}.
	 *
	 * @param pPublish true for an apply (this call publishes); false inside the generator, whose own publish follows
	 */
	private static ObjectNode ensure(final McpClient pDataModelClient, final McpClient pScriptingClient, final DerivationMasterTarget.Decision pDecision,
		final DerivationApplier.Prepared p, final DerivationApplier.Bodies pBodies, final boolean pPublish, final ObjectNode pDetail, final String pKey)
	{
		final ObjectNode node = DerivationMasterTriggers.MAPPER.createObjectNode();
		node.put("model", pDecision.modelName);
		node.put("table", pDecision.tablePath);
		final ArrayNode names = node.putArray("triggers");
		names.add(pDecision.createName);
		names.add(pDecision.modifyName);
		final ObjectNode detail = pDetail.putObject(pKey + "Triggers");
		final String createSource;
		final String modifySource;
		try
		{
			createSource = pBodies.create(p, DerivationBodyBuilder.noMandatoryFlags());
			modifySource = pBodies.modify(p, DerivationBodyBuilder.noMandatoryFlags());
		}
		catch (final IllegalStateException ex)
		{
			node.put("status", DerivationMasterTriggers.FAILED);
			node.put("reason", "cannot build the master trigger bodies: " + ex.getMessage());
			return node;
		}
		final String createIdePath = DerivationBodyBuilder.triggerIdePath(pDecision.modelName, p.entityName, pDecision.createName);
		final String modifyIdePath = DerivationBodyBuilder.triggerIdePath(pDecision.modelName, p.entityName, pDecision.modifyName);

		boolean previously = false;
		String previousCreate = null;
		String previousModify = null;
		boolean published = false;
		try
		{
			final McpClient.ToolResult listed = DerivationTriggers.listTriggers(pDataModelClient, pDecision.modelName);
			detail.set("listBefore", DerivationApplier.toolDetail(DerivationMasterTriggers.listArguments(pDecision.modelName), listed));
			previously = !listed.error && listed.json != null
				&& DerivationTriggers.stateOf(listed.json, pDecision.createName, pDecision.tablePath) != DerivationTriggers.State.MISSING
				&& DerivationTriggers.stateOf(listed.json, pDecision.modifyName, pDecision.tablePath) != DerivationTriggers.State.MISSING;
			if (previously)
			{
				previousCreate = DerivationTriggers.readSource(pScriptingClient, createIdePath, detail, "previousCreate");
				previousModify = DerivationTriggers.readSource(pScriptingClient, modifyIdePath, detail, "previousModify");
			}
			node.put("previouslyDeclared", previously);

			DerivationTriggers.declare(pDataModelClient, pDecision.modelName, pDecision.tablePath, pDecision.createName, DerivationTriggers.TYPE_CREATE, detail, "declareCreate");
			DerivationTriggers.declare(pDataModelClient, pDecision.modelName, pDecision.tablePath, pDecision.modifyName, DerivationTriggers.TYPE_MODIFY, detail, "declareModify");
			DerivationTriggers.author(pScriptingClient, createIdePath, createSource, detail, "authorCreate");
			DerivationTriggers.author(pScriptingClient, modifyIdePath, modifySource, detail, "authorModify");
			if (pPublish)
			{
				final ObjectNode publishArgs = DerivationMasterTriggers.MAPPER.createObjectNode();
				publishArgs.put("data_model_name", pDecision.modelName);
				final McpClient.ToolResult publish = pDataModelClient.callTool("publish_data_model", publishArgs);
				detail.set("publish", DerivationApplier.toolDetail(publishArgs, publish));
				if (!PublishDecision.published(publish.json))
				{
					throw EngineRefusal.coded("external.publishModel", "publish_data_model failed for " + pDecision.modelName + ": " + DerivationApplier.shortText(publish.text), pDecision.modelName);
				}
				published = true;
				DerivationTriggers.verify(pDataModelClient, pDecision.modelName, pDecision.tablePath, java.util.Arrays.asList(pDecision.createName, pDecision.modifyName), detail,
					"verify");
			}
			node.put("status", DerivationMasterTriggers.DECLARED);
			node.put("published", published);
			return node;
		}
		catch (final Exception ex)
		{
			final String note = DerivationMasterTriggers.rollback(pDataModelClient, pScriptingClient, pDecision, previously, previousCreate, previousModify, createIdePath,
				modifyIdePath, published, detail);
			node.put("status", DerivationMasterTriggers.FAILED);
			node.put("published", published);
			node.put("reason", ErrorText.of(ex) + (note == null ? "" : " " + note));
			return node;
		}
	}

	private static ObjectNode listArguments(final String pModelName)
	{
		final ObjectNode args = DerivationMasterTriggers.MAPPER.createObjectNode();
		args.put("data_model_name", pModelName);
		args.put("operation", "list_script_table_triggers");
		return args;
	}

	/** {@link DerivationApplyFailure#decide} acted on for the master table; the returned sentence, when not null, says what could not be put back. */
	private static String rollback(final McpClient pDataModelClient, final McpClient pScriptingClient, final DerivationMasterTarget.Decision pDecision, final boolean pPreviously,
		final String pPreviousCreate, final String pPreviousModify, final String pCreateIdePath, final String pModifyIdePath, final boolean pPublished, final ObjectNode pDetail)
	{
		final boolean known = pPreviousCreate != null && pPreviousModify != null;
		final DerivationApplyFailure.Plan plan = DerivationApplyFailure.decide(pPreviously, pPublished, known);
		pDetail.put("rollbackPlan", "removeDeclarations=" + plan.removeDeclarations + " restoreBodies=" + plan.restoreBodies + " republish=" + plan.republish);
		String note = null;
		try
		{
			if (plan.removeDeclarations)
			{
				DerivationTriggers.unsetRaw(pDataModelClient, pDecision.modelName, pDecision.tablePath, pDecision.createName, pDetail, "rollbackRemove_" + pDecision.createName);
				DerivationTriggers.unsetRaw(pDataModelClient, pDecision.modelName, pDecision.tablePath, pDecision.modifyName, pDetail, "rollbackRemove_" + pDecision.modifyName);
			}
			boolean restored = false;
			if (plan.restoreBodies)
			{
				restored = DerivationTriggers.restore(pScriptingClient, pCreateIdePath, pPreviousCreate, pDetail, "rollbackRestoreCreate")
					& DerivationTriggers.restore(pScriptingClient, pModifyIdePath, pPreviousModify, pDetail, "rollbackRestoreModify");
				if (!restored)
				{
					note = "The previous master trigger bodies could not all be written back (see the step detail).";
				}
			}
			else if (pPreviously)
			{
				note = "The previous master trigger bodies could not be read, so none was written back.";
			}
			if (plan.republish && restored)
			{
				final ObjectNode publishArgs = DerivationMasterTriggers.MAPPER.createObjectNode();
				publishArgs.put("data_model_name", pDecision.modelName);
				pDetail.set("rollbackRepublish", DerivationApplier.toolDetail(publishArgs, pDataModelClient.callTool("publish_data_model", publishArgs)));
			}
		}
		catch (final Exception ex)
		{
			pDetail.put("rollbackError", ErrorText.of(ex));
			note = "The master rollback itself failed: " + ex.getMessage();
		}
		return note;
	}

	// ----------------------------------------------------------------------------------------------------------- remove

	/**
	 * Takes the two triggers of the derived attribute off the master table, by name, and republishes the master model. Tolerant: no master model, no
	 * table or no trigger answers {@link #ABSENT}. Never throws; a failure is {@link #FAILED}.
	 */
	static ObjectNode removeFromMaster(final McpClient pDataModelClient, final String pProjectCode, final DerivationApplier.Prepared p, final ObjectNode pDetail)
	{
		final ObjectNode node = DerivationMasterTriggers.MAPPER.createObjectNode();
		final String modelName = MasterNames.modelName(pProjectCode);
		final String tablePath = DerivationBodyBuilder.tableElementPath(p.entityName);
		node.put("model", modelName);
		node.put("table", tablePath);
		final ObjectNode detail = pDetail.putObject("masterRemoval");
		try
		{
			final ObjectNode getArgs = DerivationMasterTriggers.MAPPER.createObjectNode();
			getArgs.put("data_model_name", modelName);
			final McpClient.ToolResult lookup = pDataModelClient.callTool("get_data_model", getArgs);
			detail.set("getModel", DerivationApplier.toolDetail(getArgs, lookup));
			if (lookup.error || lookup.json == null || !lookup.json.path("found").asBoolean(false))
			{
				node.put("status", DerivationMasterTriggers.ABSENT);
				node.put("reason", "no master model " + modelName);
				return node;
			}
			final McpClient.ToolResult listed = DerivationTriggers.listTriggers(pDataModelClient, modelName);
			detail.set("list", DerivationApplier.toolDetail(DerivationMasterTriggers.listArguments(modelName), listed));
			if (listed.error || listed.json == null)
			{
				throw EngineRefusal.coded("external.readTriggers", "could not read the triggers of " + modelName + ": " + DerivationApplier.shortText(listed.text), modelName);
			}
			boolean removedAny = false;
			for (final String name : DerivationTriggers.namesOf(p.newAttributeName))
			{
				if (DerivationTriggers.stateOf(listed.json, name, tablePath) == DerivationTriggers.State.MISSING)
				{
					detail.put("alreadyAbsent_" + name, true);
					continue;
				}
				DerivationTriggers.unset(pDataModelClient, modelName, tablePath, name, detail, "remove_" + name);
				removedAny = true;
			}
			if (!removedAny)
			{
				node.put("status", DerivationMasterTriggers.ABSENT);
				node.put("reason", "the master table had no such trigger");
				return node;
			}
			final ObjectNode publishArgs = DerivationMasterTriggers.MAPPER.createObjectNode();
			publishArgs.put("data_model_name", modelName);
			final McpClient.ToolResult publish = pDataModelClient.callTool("publish_data_model", publishArgs);
			detail.set("publish", DerivationApplier.toolDetail(publishArgs, publish));
			if (!PublishDecision.published(publish.json))
			{
				throw EngineRefusal.coded("external.publishModel", "publish_data_model could not republish " + modelName + " after removal: " + DerivationApplier.shortText(publish.text), modelName);
			}
			node.put("status", DerivationMasterTriggers.REMOVED);
			return node;
		}
		catch (final Exception ex)
		{
			node.put("status", DerivationMasterTriggers.FAILED);
			node.put("reason", ErrorText.of(ex));
			return node;
		}
	}

	/** The sentence for {@code applyMessage} when the master removal failed; {@code null} otherwise. */
	static String removeNoteFor(final ObjectNode pMaster)
	{
		if (DerivationMasterTriggers.FAILED.equals(pMaster.path("status").asText("")))
		{
			return "Removed from staging, but the triggers could not be taken off the master table (" + pMaster.path("reason").asText("")
				+ "): take them off " + pMaster.path("model").asText("") + pMaster.path("table").asText("") + " by name.";
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------------------- generator

	/** What {@link #ensureForProject} did, for the generator: the lines for its notes and whether anything was declared (an UP_TO_DATE model then needs its republish). */
	static final class ProjectResult
	{
		final List<String> notes = new ArrayList<String>();
		boolean declared;
	}

	/**
	 * For {@code MasterGenerator}, BEFORE its publish: declares and authors the two triggers on the model DRAFT for every APPLIED derivation of the project
	 * whose entity is promoted and whose attributes the master table carries (the planned fields, {@code pModelResult}). The generator's own {@code
	 * publish_data_model} then publishes them - no publish of its own here. Idempotent: the same names replace in place, so a re-generation simply
	 * rewrites them (and the generator republishes even an UP_TO_DATE model when {@link ProjectResult#declared}, because whether the draft's copy is the
	 * published one is not known from here). Never throws and never fails the generation: a derivation that cannot be handled is a line in the notes and
	 * in {@code pDetail.masterDerivationTriggers}.
	 */
	static ProjectResult ensureForProject(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode,
		final MasterModel.Result pModelResult, final McpClient pDataModelClient, final ObjectNode pDetail)
	{
		final ProjectResult out = new ProjectResult();
		final List<String> ids = new ArrayList<String>();
		final List<Boolean> reapply = new ArrayList<Boolean>();
		try
		{
			final RequestResult rows = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION).createRequestResult("./project=" + CatalogReader.quote(pProjectId)
				+ " and ./nature='SCRIPT_DERIVATION' and ./status='APPLIED'");
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					ids.add(row.getOccurrencePrimaryKey().format());
					reapply.add(Boolean.valueOf(DerivationParameters.isReapplyMarker(row.getString(FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE))));
				}
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			out.notes.add("Script derivations: the applied derivations of the project could not be listed, so the master triggers were not (re)declared: " + ex.getMessage());
			return out;
		}
		if (ids.isEmpty())
		{
			return out;
		}
		final ArrayNode results = pDetail.putArray("masterDerivationTriggers");
		McpClient scriptingClient = null;
		for (int i = 0; i < ids.size(); i++)
		{
			final String id = ids.get(i);
			final ObjectNode line = results.addObject();
			line.put("suggestion", id);
			try
			{
				final DerivationApplier.Prepared p = DerivationApplier.prepare(pFactory, pProjectCode, id);
				line.put("attribute", p.newAttributeName);
				line.put("entity", p.entityName);
				final Target target = DerivationMasterTriggers.plannedTarget(pModelResult, p.entityName);
				final DerivationMasterTarget.Decision decision = DerivationMasterTarget.decide(pProjectCode, p.entityName, p.newAttributeName, p.boundAttributes, target.modelExists,
					target.tableFields, reapply.get(i).booleanValue());
				if (!decision.eligible())
				{
					line.put("status", DerivationMasterTriggers.SKIPPED);
					line.put("reason", decision.reason);
					if (decision.kind == DerivationMasterTarget.Kind.ATTRIBUTE_NOT_ON_MASTER || decision.kind == DerivationMasterTarget.Kind.PARAMETERS_CHANGED)
					{
						out.notes.add("Script derivation " + p.entityName + "." + p.newAttributeName + ": " + decision.reason + ".");
					}
					continue;
				}
				if (scriptingClient == null)
				{
					scriptingClient = McpServers.client(pSession, pFactory, McpServers.SCRIPTING);
				}
				final DerivationApplier.Bodies bodies = DerivationApplier.resolveBodies(scriptingClient, p, line.putObject("detail"));
				final ObjectNode result = DerivationMasterTriggers.ensure(pDataModelClient, scriptingClient, decision, p, bodies, false, line, "ensure");
				line.put("status", result.path("status").asText(""));
				line.put("reason", result.path("reason").asText(""));
				if (DerivationMasterTriggers.DECLARED.equals(result.path("status").asText("")))
				{
					out.declared = true;
					out.notes.add("Script derivation " + p.entityName + "." + p.newAttributeName + ": the two triggers are declared on the master table and published with the model.");
				}
				else
				{
					out.notes.add("Script derivation " + p.entityName + "." + p.newAttributeName + ": the master triggers were not declared (" + result.path("reason").asText("") + ").");
				}
			}
			catch (final Exception ex)
			{
				line.put("status", DerivationMasterTriggers.FAILED);
				line.put("reason", ErrorText.of(ex));
				out.notes.add("Script derivation " + id + ": the master triggers were not declared (" + ex.getMessage() + ").");
			}
		}
		return out;
	}
}
