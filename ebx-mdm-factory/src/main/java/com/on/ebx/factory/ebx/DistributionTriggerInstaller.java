package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.DistributionTriggerBody;
import com.on.ebx.factory.landing.ErrorText;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Distribution lot 1 (engine, SPEC-distribution.md "Real time"): declares an EBX-Script table trigger
 * ({@code afterCreate}/{@code afterModify}/{@code afterDelete}) on each master table of a project, authors
 * their body, and republishes the master model - exactly the declare -&gt; author -&gt; publish sequence
 * {@code CapabilityApplier} already follows for a script constraint (CLAUDE.md sec 5's last bullet), moved
 * to {@code set_script_trigger} (data-model server, category {@code scripts}) instead of
 * {@code set_script_constraint}.
 * <p>
 * The trigger body never calls the target: it only writes one {@code DistributionEvent} row when a REALTIME
 * target of the project is {@code ARMED} and not {@code PAUSED} ({@code distribution.DistributionArmingGate
 * #mayQueueRealtimeEvent}) - the module's own {@link DistributionDispatcher} drains it later. This keeps the
 * trigger cheap and side-effect-free from EBX's point of view (no outbound HTTP inside a save transaction).
 * <b>Coordinator review fix, 2026-09-24, item 2</b>: the event's own transaction is on a DIFFERENT dataspace
 * than the record's save, so it is NOT part of that save's transaction - a rolled-back save can still leave
 * an event, and this class no longer claims otherwise (see {@code DistributionTriggerBody}'s own Javadoc and
 * {@code docs/DISTRIBUTION.md}); {@code DistributionDispatcher} is what makes the overall pipeline truthful,
 * by re-checking the record's current state at send time.
 * <p>
 * <b>Coordinator review fix, item 1</b>: before generating a table's three trigger bodies, {@link #describeTable}
 * reads the table's real primary-key field names via {@code describe_table} - never
 * assumes a single {@code id} field. See that method's own Javadoc for the defensive parsing (the exact JSON
 * shape of a PK/field flag was not confirmed live).
 * <p>
 * <b>Round 3 (2026-09-26)</b>: before authoring any trigger, {@link #install} now ensures
 * ({@code DistributionOutboxInstaller.ensure}) the {@code DistributionOutbox} dataset exists in the
 * project's OWN master dataspace, next to its {@code Master} dataset - PipelineStep 395 proved live that a
 * table trigger cannot open {@code execute transaction} at all, so it can only write through its ambient
 * {@code _ebx.transaction}, which never leaves {@code _ebx.dataspace}; the outbox therefore has to live
 * there, not in MdmFactory. See {@code DistributionTriggerBody}'s own Javadoc for the compile errors this
 * replaces and the exact live probe performed.
 * <p>
 * <b>Live bug fix (2026-09-25, DistribTest, PipelineStep 364)</b>: the guessed idePath used to be
 * {@code data-model/<Model>/root/<Table>/@trigger/<type>} (the trigger TYPE, e.g. {@code afterCreate}) - the
 * declaration itself worked, but {@code update_script} then answered {@code SCRIPT_NOT_FOUND}. Measured live
 * with {@code list_scripts}: the script actually lives at {@code @trigger/<the NAME passed to
 * set_script_trigger>} (here, {@code DistributionAfterCreate}), not the type - {@code @trigger/<type>} was
 * never a real path, only a plausible-looking guess. Fixed: the guessed path now uses {@link #installOne}'s
 * own {@code scriptName} (the declared name), and {@link #resolveScriptIdePath}'s fallback now lists the
 * table's {@code @trigger} folder specifically and matches by {@code scriptTypeId} (e.g. {@code
 * triggerAfterCreate}, also read live the same day) rather than the whole table folder matched by a substring
 * of the name - a wrong guess still degrades to one extra read call, never a hard failure, exactly as before.
 * Re-running {@link #install} against the SAME model now finds the already-declared script at its real path
 * (declare-then-update on an existing path is idempotent: {@code set_script_trigger}'s declaration re-run is a
 * no-op, {@code update_script} on the existing script just replaces its body - never a second script).
 * <p>
 * <b>Round 6 fix item A (2026-09-26, task brief, Personnes/PipelineStep 509)</b>: on a project's FIRST ever
 * trigger install, {@code update_script}/{@code validate_saved} on a JUST-declared trigger answered
 * {@code INVALID_DATA_MODEL "Revision history for the data-model script could not be loaded: the data-model
 * script record is unavailable"} - even though {@code list_scripts} already listed it and {@code get_script}
 * returned its metadata (a real {@code revisionId}). On DistribTest this had never surfaced because an EARLIER
 * failed run had already declared AND published the triggers before this class' current single-publish-at-
 * the-end design was reached - so by the time authoring ran, the model had already been published once with
 * the triggers present. On Personnes, the very first install ever hit the true order requirement: a
 * data-model script's revision history is only backed by a published model, so a script declared and never
 * published has no revision history for {@code update_script}'s embedded {@code validate_saved} to load, no
 * matter that {@code list_scripts}/{@code get_script} already see the (unpublished) declaration. {@code
 * compile_data_model} on {@code Personnes_Master} was also checked as an alternative explanation: it answers
 * {@code compiled:true} with one pre-existing, tolerated error
 * ({@code interInstanceLink.schema.container.notExist} on {@code /root/Personne/Association_Reverse} - the
 * add-on's own metadataset association, unrelated to this module) - not the cause.
 * <p>
 * Fixed: {@link #install} now runs in two passes - declare EVERY trigger of every table first
 * ({@link #declareOne}), then {@code publish_data_model} ONCE if (and only if) at least one of them did not
 * already resolve via {@code get_script} before it was declared (a fresh table/model - {@link #scriptAlreadyExists}),
 * then author (get_script/update_script) EVERY trigger ({@link #authorOne}), then the pre-existing final
 * {@code publish_data_model} that makes the authored bodies live. Skipping the middle publish when every
 * trigger already existed (a plain re-run against an already-installed project) avoids two publishes doing the
 * work of one on the common path; the middle publish still always runs on the very first install, or when a
 * NEW table/trigger is added to an already-installed project, which is exactly when it is needed.
 * <p>
 * <b>Lot F3 (2026-10-19): independent of Match and Merge, and repairs old bodies.</b> The generated bodies no longer read any
 * Match and Merge linked field ({@code DistributionTriggerBody}), so the installer no longer inspects the dataset's schema for them and
 * the three triggers compile whatever happens to the table's matching configuration. Every trigger is now compared with what the scripting
 * server holds ({@code get_script(includeSource=true)}, {@code data.source}, line endings and trailing blank lines ignored): an identical
 * body is NOT rewritten ({@code update_script} is skipped, the trigger detail reads {@code unchanged: true}) and the final
 * {@code publish_data_model} always runs once at least one trigger is declared, unchanged bodies included, so a run whose publish failed is repaired by the next one ({@code publishSkipped: true} only when no trigger is declared at all). A
 * project installed by the old generator (bodies with the {@code Linked_Field_State} comparison) therefore differs on its {@code afterModify}
 * body, is rewritten and republished by "Install triggers"; the {@code afterCreate}/{@code afterDelete} bodies of such a project were
 * already identical to the new text when the table had no linked field at the time, and are left alone.
 */
public final class DistributionTriggerInstaller
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	static final String STEP = "DISTRIBUTION_TRIGGER_INSTALL";
	/** {@code set_script_trigger}'s own vocabulary for {@code scope="table"} - CASE-SENSITIVE, read live via {@code list_operations(name="set_script_trigger")}, 2026-09-24. */
	private static final String[] TABLE_TRIGGER_TYPES = { "afterCreate", "afterModify", "afterDelete" };

	private DistributionTriggerInstaller()
	{
	}

	/** The name the trigger of {@code pType} is declared under ({@code DistributionAfterCreate}...): the one {@code set_script_trigger} gets and the removal must give back as {@code item}. */
	static String scriptName(final String pType)
	{
		return "Distribution" + Character.toUpperCase(pType.charAt(0)) + pType.substring(1);
	}

	/** The names of the three triggers {@link #install} declares on every master table, in declaration order. */
	static List<String> scriptNames()
	{
		final List<String> names = new ArrayList<String>();
		for (final String type : DistributionTriggerInstaller.TABLE_TRIGGER_TYPES)
		{
			names.add(DistributionTriggerInstaller.scriptName(type));
		}
		return names;
	}

	/** Declares, authors and publishes the three triggers on every master table of the project. Idempotent: {@code set_script_trigger}'s own contract is additive/replace-in-place (CLAUDE.md sec 5/list_operations), so re-running this after a model change is safe. */
	public static ObjectNode install(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pModelName) throws OperationException
	{
		final Adaptation project = DistributionConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.of("engine.projectNotFound", "Project " + pProjectCode + " does not exist", pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Set<String> masterTables = DistributionTriggerInstaller.masterTables(pFactory, projectId);
		if (masterTables.isEmpty())
		{
			throw EngineRefusal.coded("missing.noMasterTable", "Project " + pProjectCode + " has no promoted master table yet", pProjectCode);
		}
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		if (masterDataspaceKey == null || masterDataspaceKey.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.noMasterDataspace", "Project " + pProjectCode + " has no master dataspace yet", pProjectCode);
		}
		final AdaptationHome masterHome = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		if (masterHome == null)
		{
			throw EngineRefusal.coded("missing.masterDataspace", "Master dataspace " + masterDataspaceKey + " of project " + pProjectCode + " could not be found", masterDataspaceKey, pProjectCode);
		}
		// lot F3: the dataset is only checked to exist (as before); its schema is no longer read - the bodies never reference a Match and Merge field.
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		final Adaptation masterDataset = masterDatasetName == null || masterDatasetName.trim().isEmpty() ? null
			: masterHome.findAdaptationOrNull(com.onwbp.adaptation.AdaptationName.forName(masterDatasetName));
		if (masterDataset == null)
		{
			throw EngineRefusal.coded("missing.masterDataset", "Master dataset " + masterDatasetName + " of project " + pProjectCode + " could not be found in dataspace " + masterDataspaceKey, masterDatasetName, pProjectCode, masterDataspaceKey);
		}
		// Round 3: the outbox dataset must exist in the MASTER dataspace (not MdmFactory - see
		// DistributionTriggerBody's own Javadoc) before any trigger referencing it is authored below.
		DistributionOutboxInstaller.ensure(pSession, masterHome, FactoryPaths.MODULE_NAME);
		// Volume-operations lot 8b (2026-09-28, task 1): the Control row the new short-circuit trigger reads
		// must exist and be correct from this project's very first install onward - in particular when a target
		// was already ARMED before triggers (and therefore this row) ever existed for this project. Unconditional:
		// cheap (one recompute per install, never per record) and correct whether this is the first install or a
		// reinstall picking up the short-circuit for the first time.
		DistributionControlRow.recomputeOne(pSession, pFactory, projectId);

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, DistributionTriggerInstaller.STEP, pModelName);
		final ObjectNode detail = DistributionTriggerInstaller.MAPPER.createObjectNode();
		final ArrayNode tableDetails = detail.putArray("tables");
		try
		{
			final McpClient dataModelClient = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
			final McpClient scriptingClient = McpServers.client(pSession, pFactory, McpServers.SCRIPTING);
			// lot F3: the old "matching not active yet" hint is gone with the retire branch it announced; the answer keeps the key (false) for older callers.
			// round 6 fix item A: pass 1 - declare every trigger of every table BEFORE authoring any of them
			// (class Javadoc: a script declared but never published has no revision history yet for
			// update_script's embedded validate_saved to load). anyNewlyDeclared tracks whether at least one of
			// them did NOT already resolve via get_script before being declared - a fresh table/model, or a table
			// newly added to an already-installed project - which is exactly when the middle publish below is
			// actually needed; a plain re-run of an already-installed project skips it.
			final List<PendingTrigger> pending = new ArrayList<PendingTrigger>();
			boolean anyNewlyDeclared = false;
			for (final String tablePath : masterTables)
			{
				final ObjectNode tableDetail = tableDetails.addObject();
				tableDetail.put("tablePath", tablePath);
				final String tableName = tablePath.substring(tablePath.lastIndexOf('/') + 1);
				final TableDescription description = DistributionTriggerInstaller.describeTable(dataModelClient, pModelName, tablePath);
				tableDetail.put("primaryKeyFields", String.join(",", description.primaryKeyFields));
				for (final String type : DistributionTriggerInstaller.TABLE_TRIGGER_TYPES)
				{
					final ObjectNode triggerDetail = tableDetail.putObject(type);
					final boolean newlyDeclared = DistributionTriggerInstaller.declareOne(dataModelClient, scriptingClient, pModelName, tablePath, tableName, type, triggerDetail);
					anyNewlyDeclared = anyNewlyDeclared || newlyDeclared;
					pending.add(new PendingTrigger(tablePath, tableName, type, description, triggerDetail));
				}
			}
			if (anyNewlyDeclared)
			{
				final ObjectNode declarePublishArgs = DistributionTriggerInstaller.MAPPER.createObjectNode();
				declarePublishArgs.put("data_model_name", pModelName);
				final McpClient.ToolResult declarePublish = dataModelClient.callTool("publish_data_model", declarePublishArgs);
				detail.put("declarePublishOk", !declarePublish.error);
				detail.put("declarePublishSkipped", false);
				if (declarePublish.error)
				{
					throw EngineRefusal.coded("external.triggersPublish", "publish_data_model refused after DECLARING distribution triggers on " + pModelName + " (needed before authoring a first-ever trigger): "
							+ DistributionConfigurator.errorText(declarePublish), pModelName);
				}
			}
			else
			{
				detail.put("declarePublishSkipped", true);
			}
			// pass 2: author (get_script/update_script) every declared trigger - now backed by a published
			// revision history whenever any of them was genuinely new.
			// lot F3: a trigger whose stored body already equals the generated one is left alone; the final publish still always runs (see below).
			int written = 0;
			int unchanged = 0;
			for (final PendingTrigger pendingTrigger : pending)
			{
				if (DistributionTriggerInstaller.authorOne(pSession, pFactory, scriptingClient, pModelName, projectId, pendingTrigger.tablePath, pendingTrigger.tableName, pendingTrigger.type,
					pendingTrigger.description, pendingTrigger.triggerDetail))
				{
					written++;
				}
				else
				{
					unchanged++;
				}
			}
			detail.put("triggersWritten", written);
			detail.put("triggersUnchanged", unchanged);
			// the publish is unconditional once at least one trigger is declared (unchanged bodies included): a run whose publish failed after its update_script calls
			// would otherwise find every body "unchanged" next time and leave the model unpublished; republishing an unchanged model is cheap.
			final boolean publishNeeded = !pending.isEmpty();
			detail.put("publishSkipped", !publishNeeded);
			if (publishNeeded)
			{
				final ObjectNode publishArgs = DistributionTriggerInstaller.MAPPER.createObjectNode();
				publishArgs.put("data_model_name", pModelName);
				final McpClient.ToolResult publish = dataModelClient.callTool("publish_data_model", publishArgs);
				detail.put("publishOk", !publish.error);
				if (publish.error)
				{
					throw EngineRefusal.coded("external.triggersPublish", "publish_data_model refused after installing distribution triggers on " + pModelName + ": " + DistributionConfigurator.errorText(publish), pModelName);
				}
			}
			PipelineSteps.end(pSession, pFactory, stepId, true, "Installed distribution triggers on " + masterTables.size() + " table(s) of " + pModelName + " (" + written + " written, "
				+ unchanged + " already up to date)", detail.toString());
			final ObjectNode out = DistributionTriggerInstaller.MAPPER.createObjectNode();
			out.put("status", "DONE");
			out.put("matchingNotActiveHint", false);
			out.set("detail", detail);
			return out;
		}
		catch (final McpException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw EngineRefusal.coded("external.triggersInstall", "Could not install distribution triggers for project " + pProjectCode + ": " + ex.getMessage(), pProjectCode);
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

	/** {@code describe_table}'s answer, reduced to what {@code DistributionTriggerBody} needs: the primary-key field names (lot F3: nothing about Match and Merge - the body never reads a linked field). */
	private static final class TableDescription
	{
		final List<String> primaryKeyFields;

		TableDescription(final List<String> pPrimaryKeyFields)
		{
			this.primaryKeyFields = pPrimaryKeyFields;
		}
	}

	/** One declared-but-not-yet-authored trigger, carried from {@link #install}'s pass 1 (declare) to its pass 2 (author) - round 6 fix item A. */
	private static final class PendingTrigger
	{
		final String tablePath;
		final String tableName;
		final String type;
		final TableDescription description;
		final ObjectNode triggerDetail;

		PendingTrigger(final String pTablePath, final String pTableName, final String pType, final TableDescription pDescription, final ObjectNode pTriggerDetail)
		{
			this.tablePath = pTablePath;
			this.tableName = pTableName;
			this.type = pType;
			this.description = pDescription;
			this.triggerDetail = pTriggerDetail;
		}
	}

	/**
	 * Reads a table's real primary-key field names via {@code describe_table} (the DMA model - unchanged). Lot F3: it no longer asks whether the
	 * table carries Match and Merge's {@code Linked_Field_State}/{@code GoldenId} - the generated bodies never read them.
	 * <p>
	 * <b>Not verified live</b>: the exact JSON key describe_table uses to flag a child as the primary key was
	 * not confirmed against a running instance - this method checks several plausible keys
	 * ({@code primaryKey}, {@code isPrimaryKey}, {@code pk}, all case-sensitive as written) on each child, and
	 * falls back to a bare {@code ["id"]} (every table this module generates always has at least that field -
	 * {@code master.MasterModel}'s own table-building code) when describe_table fails outright or no child is
	 * ever flagged. A wrong/missing flag key therefore degrades to the historical single-field assumption,
	 * never to a broken trigger.
	 */
	private static TableDescription describeTable(final McpClient pDataModelClient, final String pModelName, final String pTablePath)
	{
		final List<String> primaryKeyFields = new ArrayList<String>();
		try
		{
			final ObjectNode args = DistributionTriggerInstaller.MAPPER.createObjectNode();
			args.put("data_model_name", pModelName);
			args.put("table_path", pTablePath);
			final McpClient.ToolResult result = pDataModelClient.callTool("describe_table", args);
			if (!result.error && result.json != null)
			{
				for (final JsonNode child : result.json.path("data").path("children"))
				{
					final String name = child.path("name").asText("");
					if (child.path("primaryKey").asBoolean(false) || child.path("isPrimaryKey").asBoolean(false) || child.path("pk").asBoolean(false))
					{
						primaryKeyFields.add(name);
					}
				}
			}
		}
		catch (final McpException ex)
		{
			// best-effort: falls back to ["id"] below, exactly as if describe_table had found nothing
		}
		if (primaryKeyFields.isEmpty())
		{
			primaryKeyFields.add("id");
		}
		return new TableDescription(primaryKeyFields);
	}

	/**
	 * Round 6 fix item A: pass-1 half of the former {@code installOne} - declares the trigger only (never reads
	 * or authors its body), and reports whether it is genuinely NEW: probed via {@link #scriptAlreadyExists}
	 * BEFORE declaring, since a first-ever declaration has no revision history until the model is published
	 * (class Javadoc). The guessed script path and name are stashed on {@code pTriggerDetail} for {@link
	 * #authorOne} to pick back up after the caller's own middle-publish decision.
	 */
	private static boolean declareOne(final McpClient pDataModelClient, final McpClient pScriptingClient, final String pModelName, final String pTablePath,
		final String pTableName, final String pType, final ObjectNode pTriggerDetail) throws McpException, OperationException
	{
		final String scriptName = DistributionTriggerInstaller.scriptName(pType);
		final String guessedPath = "data-model/" + pModelName + "/root/" + pTableName + "/@trigger/" + scriptName;
		final boolean existedBefore = DistributionTriggerInstaller.scriptAlreadyExists(pScriptingClient, guessedPath);

		final ObjectNode declareOperationArgs = DistributionTriggerInstaller.MAPPER.createObjectNode();
		declareOperationArgs.put("element_path", pTablePath);
		declareOperationArgs.put("name", scriptName);
		declareOperationArgs.put("type", pType);
		declareOperationArgs.put("scope", "table");
		final ObjectNode declareArgs = DistributionTriggerInstaller.MAPPER.createObjectNode();
		declareArgs.put("data_model_name", pModelName);
		declareArgs.put("operation", "set_script_trigger");
		declareArgs.put("arguments_json", declareOperationArgs.toString());
		final McpClient.ToolResult declareResult = pDataModelClient.callTool("execute_operation", declareArgs);
		pTriggerDetail.put("declared", !declareResult.error);
		if (declareResult.error)
		{
			throw EngineRefusal.coded("external.triggerDeclare", "set_script_trigger refused " + pType + " on " + pTablePath + ": " + DistributionConfigurator.errorText(declareResult), pType, pTablePath);
		}
		if (declareResult.json != null && !declareResult.json.path("publishSafe").asBoolean(true))
		{
			throw EngineRefusal.coded("conflict.noScriptComponent", pModelName + " lacks EBX's built-in script component: " + pType + " on " + pTablePath + " would be dropped at publish", pModelName);
		}
		pTriggerDetail.put("scriptName", scriptName);
		pTriggerDetail.put("guessedScriptIdePath", guessedPath);
		pTriggerDetail.put("newlyDeclared", !existedBefore);
		return !existedBefore;
	}

	/** A cheap existence probe (a single {@code get_script} call, the same one {@link #resolveScriptIdePath} already made after declaring) run BEFORE declaring, so {@link #declareOne} can tell a first-ever declaration (no revision history until the model is published) from a plain re-run of an already-installed, already-published trigger. Never throws - any failure (including "does not exist yet") reads as "not existing", the safe default that keeps the middle publish. */
	private static boolean scriptAlreadyExists(final McpClient pScriptingClient, final String pScriptIdePath)
	{
		try
		{
			final ObjectNode probeArgs = DistributionTriggerInstaller.MAPPER.createObjectNode();
			probeArgs.put("scriptIdePath", pScriptIdePath);
			final McpClient.ToolResult probe = pScriptingClient.callTool("get_script", probeArgs);
			return !probe.error;
		}
		catch (final McpException ex)
		{
			return false;
		}
	}

	/**
	 * Round 6 fix item A: pass-2 half of the former {@code installOne} - resolves the real script path, reads
	 * the (now revision-history-backed, whenever it was genuinely new) script, authors its body, and validates
	 * it exactly as before. Only ever called after {@link #install}'s own middle-publish decision, so a
	 * first-ever trigger is never authored against an unpublished declaration (the Personnes/PipelineStep 509
	 * bug this pass fixes).
	 */
	private static boolean authorOne(final Session pSession, final Adaptation pFactory, final McpClient pScriptingClient, final String pModelName, final String pProjectId, final String pTablePath, final String pTableName,
		final String pType, final TableDescription pDescription, final ObjectNode pTriggerDetail) throws McpException, OperationException
	{
		final String scriptName = pTriggerDetail.path("scriptName").asText("Distribution" + Character.toUpperCase(pType.charAt(0)) + pType.substring(1));
		final String guessedPath = pTriggerDetail.path("guessedScriptIdePath").asText("data-model/" + pModelName + "/root/" + pTableName + "/@trigger/" + scriptName);
		final String scriptIdePath = DistributionTriggerInstaller.resolveScriptIdePath(pScriptingClient, guessedPath, pModelName, pTableName, pType, scriptName, pTriggerDetail);

		final ObjectNode getArgs = DistributionTriggerInstaller.MAPPER.createObjectNode();
		getArgs.put("scriptIdePath", scriptIdePath);
		getArgs.put("includeSource", true);
		final McpClient.ToolResult skeleton = pScriptingClient.callTool("get_script", getArgs);
		if (skeleton.error)
		{
			throw EngineRefusal.coded("external.triggerRead", "could not read the declared trigger script at " + scriptIdePath + ": " + DistributionConfigurator.errorText(skeleton), scriptIdePath);
		}
		final String revisionId = skeleton.json == null ? null : skeleton.json.path("data").path("revisionId").asText(null);

		final String source = DistributionTriggerBody.body(pType, pProjectId, pTablePath, pTableName, pModelName, pDescription.primaryKeyFields);
		// lot F3: idempotent reinstall - an identical stored body is not rewritten (no new draft revision, nothing to republish for it)
		final String storedSource = skeleton.json == null ? null : skeleton.json.path("data").path("source").asText(null);
		if (DistributionTriggerInstaller.sameSource(storedSource, source))
		{
			pTriggerDetail.put("authored", true);
			pTriggerDetail.put("unchanged", true);
			return false;
		}
		pTriggerDetail.put("unchanged", false);
		final ObjectNode updateArgs = DistributionTriggerInstaller.MAPPER.createObjectNode();
		updateArgs.put("scriptIdePath", scriptIdePath);
		updateArgs.put("source", source);
		if (revisionId != null)
		{
			updateArgs.put("revisionId", revisionId);
		}
		updateArgs.put("confirmReplaceMetadata", true);
		final McpClient.ToolResult update = pScriptingClient.callTool("update_script", updateArgs);
		// same isSuccess/data.validation.isSuccess check ebx.CapabilityApplier already runs after its own
		// update_script call for a script constraint - "run the Factory's own validate_saved logic" (task
		// brief) means exactly this: update_script's own embedded compile/validate result, no separate call.
		final boolean updateSuccess = update.json != null && update.json.path("success").asBoolean(false);
		final boolean validationSuccess = update.json != null && update.json.path("data").path("validation").path("isSuccess").asBoolean(false);
		pTriggerDetail.put("authored", updateSuccess && validationSuccess);
		if (!updateSuccess || !validationSuccess)
		{
			// live bug fix (2026-09-25): errorText(update) alone reads "(no message)" on a pure validation
			// failure (update_script's own top-level "error"/"text" fields are empty when the CALL succeeded but
			// the SOURCE failed to compile) - surface data.validation.errors[], same shape/fields
			// CapabilityApplier already reads (startLine/startColumn/message), so a compile error is actually
			// diagnosable from this exception's message instead of a bare "(no message)".
			final String validationText = com.on.ebx.factory.distribution.ScriptValidationErrors.format(update.json);
			// round 6 fix item A: authoring now always runs AFTER the model has been published at least once
			// with this trigger declared (install()'s own middle-publish step) - a clear hint in case it still
			// fails, rather than leaving INVALID_DATA_MODEL looking like the same unpublished-model problem again.
			String hint = validationText.isEmpty() ? DistributionConfigurator.errorText(update) : validationText;
			// lot S3 (2026-10-18): update_script classifies ANY runtime exception of the compiler as INVALID_DATA_MODEL "revision history ...
			// could not be loaded" - the first live distribution run hid a compiler crash (a typed sql literal) behind that wording. The IDE's
			// validate-only route (saves nothing) says what the compiler really answers: put ITS answer in the result and in kernel.log.
			if (DistributionTriggerInstaller.isInvalidDataModel(update))
			{
				final String routeAnswer = ScriptValidateRoute.describe(pSession, pFactory, scriptIdePath, source);
				pTriggerDetail.put("validateRoute", routeAnswer);
				hint = hint + " | " + routeAnswer;
				com.orchestranetworks.service.LoggingCategory.getKernel().warn("[mdm-factory] distribution trigger " + pType + " on " + pTablePath + " (" + scriptIdePath
					+ "): update_script answered INVALID_DATA_MODEL; " + routeAnswer);
			}
			throw EngineRefusal.coded("invalid.triggerBody", "update_script refused the body of " + pType + " on " + pTablePath + " (" + scriptIdePath
				+ ") even after publishing the model with the trigger declared - this is a genuine authoring failure, not the first-install ordering issue: " + hint, pType, pTablePath);
		}
		return true;
	}

	/**
	 * Lot F3: whether the stored script text is the generated one. Line endings are normalised and trailing blank lines/spaces ignored (the scripting
	 * server may hand the text back with its own final newline); everything else must be identical. A missing stored text is never "the same".
	 */
	static boolean sameSource(final String pStored, final String pGenerated)
	{
		if (pStored == null || pGenerated == null)
		{
			return false;
		}
		return DistributionTriggerInstaller.normalise(pStored).equals(DistributionTriggerInstaller.normalise(pGenerated));
	}

	private static String normalise(final String pSource)
	{
		return pSource.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[ \t]+\n", "\n").replaceAll("\\s+\\z", "");
	}

	/** True when {@code update_script}'s answer carries the {@code INVALID_DATA_MODEL} code (top-level {@code code}, {@code data.code} or inside the text). */
	static boolean isInvalidDataModel(final McpClient.ToolResult pUpdate)
	{
		return DistributionTriggerInstaller.isInvalidDataModel(pUpdate.json == null ? null : pUpdate.json.toString(), pUpdate.text);
	}

	/** Pure form of {@link #isInvalidDataModel(McpClient.ToolResult)}, for the unit test. */
	static boolean isInvalidDataModel(final String pJsonText, final String pText)
	{
		return pJsonText != null && pJsonText.contains("INVALID_DATA_MODEL") || pText != null && pText.contains("INVALID_DATA_MODEL");
	}

	/**
	 * Tries the deduced path first (@trigger/&lt;declared name&gt;, see class Javadoc); on a {@code get_script}
	 * refusal, lists the table's {@code @trigger} folder specifically and matches by {@code scriptTypeId}
	 * (e.g. {@code triggerAfterCreate} for {@code pType="afterCreate"} - measured live 2026-09-25) rather than
	 * a substring of the whole table folder's listing, since more than one script type can live under the same
	 * table. Falls back to a name match (exact, then substring) when an item carries no {@code scriptTypeId} at
	 * all - a defensive second line, never trusted alone. Returns the guessed path unchanged when nothing
	 * resolves - the caller's own {@code get_script} call then reports the real problem.
	 */
	private static String resolveScriptIdePath(final McpClient pScriptingClient, final String pGuessedPath, final String pModelName, final String pTableName,
		final String pType, final String pScriptName, final ObjectNode pTriggerDetail)
	{
		try
		{
			final ObjectNode probeArgs = DistributionTriggerInstaller.MAPPER.createObjectNode();
			probeArgs.put("scriptIdePath", pGuessedPath);
			final McpClient.ToolResult probe = pScriptingClient.callTool("get_script", probeArgs);
			if (!probe.error)
			{
				pTriggerDetail.put("scriptIdePath", pGuessedPath);
				return pGuessedPath;
			}
			final String scriptTypeId = DistributionTriggerInstaller.scriptTypeId(pType);
			final ObjectNode listArgs = DistributionTriggerInstaller.MAPPER.createObjectNode();
			listArgs.put("folderIdePath", "data-model/" + pModelName + "/root/" + pTableName + "/@trigger");
			listArgs.put("limit", 50);
			final McpClient.ToolResult list = pScriptingClient.callTool("list_scripts", listArgs);
			if (!list.error && list.json != null)
			{
				String byName = null;
				String byNameContains = null;
				for (final com.fasterxml.jackson.databind.JsonNode item : list.json.path("data").path("items"))
				{
					final String name = item.path("name").asText("");
					final String idePath = item.path("idePath").asText(null);
					if (idePath == null)
					{
						continue;
					}
					if (scriptTypeId.equals(item.path("scriptTypeId").asText("")))
					{
						pTriggerDetail.put("scriptIdePath", idePath);
						pTriggerDetail.put("scriptIdePathResolvedByListing", true);
						return idePath;
					}
					if (pScriptName.equals(name) && byName == null)
					{
						byName = idePath;
					}
					if (name.contains(pScriptName) && byNameContains == null)
					{
						byNameContains = idePath;
					}
				}
				final String fallback = byName != null ? byName : byNameContains;
				if (fallback != null)
				{
					pTriggerDetail.put("scriptIdePath", fallback);
					pTriggerDetail.put("scriptIdePathResolvedByListing", true);
					return fallback;
				}
			}
		}
		catch (final McpException ex)
		{
			pTriggerDetail.put("scriptIdePathResolutionError", ex.getMessage());
		}
		pTriggerDetail.put("scriptIdePath", pGuessedPath);
		return pGuessedPath;
	}

	/** {@code set_script_trigger}'s own {@code type} argument ({@link #TABLE_TRIGGER_TYPES}) prefixed "trigger" and capitalized - the {@code scriptTypeId} {@code list_scripts} reports for a table trigger (measured live 2026-09-25, DistribTest: {@code triggerAfterCreate} for {@code afterCreate}). */
	private static String scriptTypeId(final String pType)
	{
		return "trigger" + Character.toUpperCase(pType.charAt(0)) + pType.substring(1);
	}

	/** Every promoted master table path of the project - {@link ProjectMasterEntities#promotedMasterTablePaths}, same shared computation as {@link DistributionConfigurator} (see its own Javadoc on the live {@code StagingEntity.targetTable} bug this replaces). */
	private static Set<String> masterTables(final Adaptation pFactory, final String pProjectId)
	{
		return new LinkedHashSet<String>(ProjectMasterEntities.promotedMasterTablePaths(pFactory, pProjectId));
	}
}
