package com.on.ebx.factory.ebx;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.capability.ConstraintBodyBuilder;
import com.on.ebx.factory.capability.DerivationApplyFailure;
import com.on.ebx.factory.capability.DerivationBodyBuilder;
import com.on.ebx.factory.capability.DerivationParameters;
import com.on.ebx.factory.capability.ScriptGuard;
import com.on.ebx.factory.capability.ScriptInventory;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.PublishDecision;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.staging.StagingModelDiff;
import com.on.ebx.factory.staging.StagingNames;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Applies (or removes) an accepted {@code CapabilitySuggestion} row of nature {@code SCRIPT_DERIVATION} - the sibling of
 * {@code ebx.CapabilityApplier} (which stays SCRIPT_CONSTRAINT-only, unchanged - the coordinator's
 * own "SCRIPT_CONSTRAINT/EXTERNAL_SERVICE only" note on that class is still literally true, this lot adds the
 * third nature as its OWN small class rather than growing an already very large one). See {@code
 * quality.ScriptDerivation}'s own Javadoc for the design and {@code docs/SCRIPT-DERIVATION.md} for the measured facts this class relies on.
 * <p>
 * <b>Lot T1 (2026-10-04): the derived attribute is an ordinary STORED field, kept up to date by two EBX-Script table triggers</b> ({@code
 * beforeCreate}, {@code beforeModify}) that call the library function - no longer a computed function field, which an INDEXED field cannot
 * make call a library (measured: {@code SCRIPT_LIBRARY_ACCESS} not available) and matching needs indexed. {@link #apply} declares the two
 * triggers, authors their bodies ({@link DerivationBodyBuilder#buildCreateTriggerBody}/{@link DerivationBodyBuilder#buildModifyTriggerBody}),
 * publishes the model once, verifies, records the application, and - when the staging table already holds rows - backfills them ({@link
 * DerivationBackfillPass}: triggers only fire on a write). A field still declared as a function by an older apply is un-declared first.
 * The MCP half ({@link DerivationTriggers}) takes the model name and the table as parameters.
 * <p>
 * <b>Lot T2 (2026-10-04): the same two triggers go on the MASTER table</b> ({@link DerivationMasterTriggers}, decided by {@code master.DerivationMasterTarget}) at the end
 * of {@link #apply}, once the staging part succeeded and was recorded - a failure there is reported in the answer's {@code master} node, in the step detail and in {@code
 * applyMessage}, and never undoes or fails the staging apply; {@link #remove} takes them off the master table too. {@code ebx.MasterGenerator} declares them when it builds
 * the master model. Two defects found during lot P1 are fixed here: a failed RE-apply no longer takes the published triggers off the draft nor flips the row to ACCEPTED
 * ({@link DerivationApplyFailure}), and a refusal of {@link #prepare} is stored in {@code applyMessage}.
 * <p>
 * <b>Considerably simpler than {@code CapabilityApplier}, by construction, not by omission</b>: a derivation body is ALWAYS the deterministic
 * template - one call to the target library function, no message, no severity - so there is no LLM-authored body path, no compile-retry loop,
 * no measured-effect pass (a derivation never flags a record, there is nothing in the validation report to count) and no cross-suggestion
 * publish batching (one row is accepted at a time from the wizard, unlike a bulk constraint accept - {@code docs/CAPABILITY-APPLY.md} sec
 * 2a's own batching exists for a problem this class does not have).
 */
public final class DerivationApplier
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String NATURE_SCRIPT_DERIVATION = "SCRIPT_DERIVATION";
	private static final String STATUS_ACCEPTED = "ACCEPTED";
	private static final String STATUS_APPLIED = "APPLIED";
	/** Reuses the SAME {@code PipelineStep.step} enum values {@code ebx.CapabilityApplier} already writes (`tools/build_xsd.py`) - a derivation apply/remove is conceptually the exact same action ("accept a capability suggestion") on a different nature; a THIRD enum value would say nothing a reviewer could not already tell from the step's own {@code scope} (the suggestion id) and {@code detail}. */
	static final String STEP_APPLY = "CAPABILITY_APPLY";
	private static final String STEP_REMOVE = "CAPABILITY_REMOVE";

	private DerivationApplier()
	{
	}

	// ------------------------------------------------------------------------------------------------------ apply

	/**
	 * Declares, authors and publishes the two triggers of one {@code ACCEPTED} (or already {@code APPLIED} - re-applying is not skipped, same
	 * "same name replaces in place" idempotence {@code CapabilityApplier} relies on) {@code SCRIPT_DERIVATION} suggestion, then backfills the rows the
	 * staging table already holds. Throws on any failure up to and including the verification - the caller (the review/wizard accept action) is
	 * expected to surface it directly, the same contract {@code CapabilityApplier.apply} follows for a single id. A problem in the BACKFILL never
	 * fails the apply (the triggers are live and recorded): it is reported in the answer's {@code backfill} node and noted in {@code applyMessage}.
	 */
	public static ObjectNode apply(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pSuggestionId) throws OperationException
	{
		final Prepared p;
		try
		{
			p = DerivationApplier.prepare(pFactory, pProjectCode, pSuggestionId);
		}
		catch (final OperationException refusal)
		{
			// lot T2 (2026-10-04, defect 3b of lot P1): a refusal before the step begins (a parameter still to be provided, a vanished attribute) used to be
			// thrown and forgotten - the wizard could not say why. Stored now, with no PipelineStep (nothing was attempted).
			DerivationApplier.recordRefusal(pSession, pFactory, pProjectCode, pSuggestionId, refusal.getMessage());
			throw refusal;
		}
		final String stepId = PipelineSteps.begin(pSession, pFactory, p.projectId, DerivationApplier.STEP_APPLY, pSuggestionId);
		final ObjectNode detail = DerivationApplier.MAPPER.createObjectNode();
		// lot T2 (defect 3a of lot P1): what a failure must undo depends on whether the triggers were already applied before this round
		final Previous previous = DerivationApplier.readPrevious(pFactory, pSuggestionId, p.newAttributeName);
		final String[] rollbackNote = new String[1];
		boolean recorded = false;
		try
		{
			final McpClient dataModelClient = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);

			final ObjectNode getModelArgs = DerivationApplier.MAPPER.createObjectNode();
			getModelArgs.put("data_model_name", p.modelName);
			getModelArgs.put("deep", true);
			final McpClient.ToolResult modelLookup = dataModelClient.callTool("get_data_model", getModelArgs);
			detail.set("getModel", DerivationApplier.toolDetail(getModelArgs, modelLookup));
			final boolean modelExists = modelLookup.json != null && modelLookup.json.path("found").asBoolean(false);
			if (!modelExists)
			{
				throw EngineRefusal.coded("missing.stagingModel", "model '" + p.modelName + "' does not exist yet: generate the staging zone first (staging/generate)", p.modelName);
			}
			final List<StagingModelDiff.ExistingTable> tables = StagingModelDiff.parseExisting(modelLookup.json.path("tables"));
			final StagingModelDiff.ExistingTable table = DerivationApplier.findTable(tables, p.entityName);
			if (table == null)
			{
				throw EngineRefusal.coded("missing.derivationTarget", "table '/root/" + p.entityName + "' does not exist in " + p.modelName + ": run staging/generate first", p.entityName, p.modelName);
			}
			if (table.field(p.newAttributeName) == null)
			{
				throw EngineRefusal.coded("missing.derivationTarget", "field '" + p.newAttributeName + "' does not exist in /root/" + p.entityName + " of " + p.modelName
					+ ": run staging/generate first", p.entityName, p.modelName);
			}
			for (final String boundAttribute : p.boundAttributes)
			{
				if (table.field(boundAttribute) == null)
				{
					throw EngineRefusal.coded("missing.derivationTarget", "field '" + boundAttribute + "' does not exist in /root/" + p.entityName + " of " + p.modelName
						+ ": run staging/generate first", p.entityName, p.modelName);
				}
			}

			final McpClient scriptingClient = McpServers.client(pSession, pFactory, McpServers.SCRIPTING);

			// the library function, resolved BEFORE anything is declared: a stale suggestion changes nothing
			final Bodies bodies = DerivationApplier.resolveBodies(scriptingClient, p, detail);
			final String createSource;
			final String modifySource;
			try
			{
				createSource = bodies.create(p, p.attributeMandatory);
				modifySource = bodies.modify(p, p.attributeMandatory);
			}
			catch (final IllegalArgumentException ex)
			{
				throw EngineRefusal.coded("invalid.templateScript", "the generated script of '" + pSuggestionId + "' was refused before it was compiled: " + ex.getMessage(), pSuggestionId, ex.getMessage());
			}
			catch (final IllegalStateException ex)
			{
				throw EngineRefusal.coded("unexpected.applyFailed", "cannot apply '" + pSuggestionId + "': " + ex.getMessage(), pSuggestionId);
			}

			// migration: a field still declared as a function by an older apply cannot also be a stored field
			final McpClient.ToolResult functions = DerivationTriggers.listFunctions(dataModelClient, p.modelName);
			final ObjectNode listFunctionsArgs = DerivationApplier.MAPPER.createObjectNode();
			listFunctionsArgs.put("data_model_name", p.modelName);
			listFunctionsArgs.put("operation", "list_script_functions");
			detail.set("listFunctions", DerivationApplier.toolDetail(listFunctionsArgs, functions));
			if (!functions.error && DerivationTriggers.isFunctionDeclared(functions.json, p.elementPath))
			{
				DerivationApplier.undeclareFunction(dataModelClient, p.modelName, p.elementPath, detail, "undeclareFunction");
				detail.put("undeclaredFunction", true);
			}

			final String tablePath = DerivationBodyBuilder.tableElementPath(p.entityName);
			final String createName = DerivationBodyBuilder.triggerName(p.newAttributeName, true);
			final String modifyName = DerivationBodyBuilder.triggerName(p.newAttributeName, false);
			final String createIdePath = DerivationBodyBuilder.triggerIdePath(p.modelName, p.entityName, createName);
			final String modifyIdePath = DerivationBodyBuilder.triggerIdePath(p.modelName, p.entityName, modifyName);
			boolean publishedThisRound = false;

			try
			{
				DerivationTriggers.declare(dataModelClient, p.modelName, tablePath, createName, DerivationTriggers.TYPE_CREATE, detail, "declareCreate");
				DerivationTriggers.declare(dataModelClient, p.modelName, tablePath, modifyName, DerivationTriggers.TYPE_MODIFY, detail, "declareModify");
				DerivationTriggers.author(scriptingClient, createIdePath, createSource, detail, "authorCreate");
				DerivationTriggers.author(scriptingClient, modifyIdePath, modifySource, detail, "authorModify");

				final ObjectNode publishArgs = DerivationApplier.MAPPER.createObjectNode();
				publishArgs.put("data_model_name", p.modelName);
				final McpClient.ToolResult publish = dataModelClient.callTool("publish_data_model", publishArgs);
				detail.set("publish", DerivationApplier.toolDetail(publishArgs, publish));
				if (!PublishDecision.published(publish.json))
				{
					throw EngineRefusal.coded("external.publishModel", "publish_data_model failed for " + p.modelName + ": " + DerivationApplier.shortText(publish.text), p.modelName);
				}
				publishedThisRound = true;

				DerivationTriggers.verify(dataModelClient, p.modelName, DerivationTriggers.namesOf(p.newAttributeName), detail);
			}
			catch (final Exception ex)
			{
				rollbackNote[0] = DerivationApplier.rollback(dataModelClient, scriptingClient, p, tablePath, previous, createIdePath, modifyIdePath, publishedThisRound, detail);
				throw ex;
			}

			final String explanation = DerivationBodyBuilder.buildExplanation(bodies.importName, p.targetFunction, p.attributeLabels.get(p.sourceAttribute), p.newAttributeLabel);
			DerivationApplier.writeApplied(pSession, pFactory, pSuggestionId, p.modelName, p.elementPath, createIdePath,
				DerivationBodyBuilder.joinStoredBodies(createName, createIdePath, createSource, modifyName, modifyIdePath, modifySource),
				explanation + " Triggers: " + createName + ", " + modifyName + ".");
			recorded = true;

			// lot T2: the same two triggers on the master table, when the master zone exists and carries the attributes. NEVER fails the apply: the
			// staging part is done, recorded and live; a problem here is in the answer, the step detail and applyMessage.
			final ObjectNode master = DerivationMasterTriggers.applyAfterStaging(dataModelClient, scriptingClient, pProjectCode, p, bodies, detail);
			detail.set("master", master);
			final String masterNote = DerivationMasterTriggers.noteFor(master);

			// rows that already exist hold an old copy or nothing, and a trigger only fires on a write
			// lot G1 (2026-10-17): the suggestion id, not the step id this call passed until now (the answer and the "run POST .../backfill" hint of applyMessage named step 1696 for capability 226)
			final ObjectNode backfill = DerivationBackfillPass.runAfterApply(pSession, pFactory, p, pSuggestionId);
			detail.set("backfill", backfill);
			final String backfillNote = DerivationBackfillPass.noteFor(backfill);
			final String note = DerivationApplier.joinNotes(masterNote, backfillNote);
			if (note != null)
			{
				DerivationApplier.writeApplyMessageOnly(pSession, pFactory, pSuggestionId, note);
			}
			PipelineSteps.end(pSession, pFactory, stepId, true, "applied '" + p.newAttributeName + "' on " + p.entityName + " (source " + p.sourceAttribute + "; backfill "
				+ backfill.path("status").asText("?") + "; master " + master.path("status").asText("?") + ")", detail.toString());
			final ObjectNode out = DerivationApplier.MAPPER.createObjectNode();
			out.put("suggestion", pSuggestionId);
			out.put("ok", true);
			out.put("appliedModel", p.modelName);
			out.put("appliedElementPath", p.elementPath);
			out.put("appliedScriptIdePath", createIdePath);
			out.put("appliedModifyScriptIdePath", modifyIdePath);
			out.set("master", master);
			out.set("backfill", backfill);
			return out;
		}
		catch (final OperationException ex)
		{
			DerivationApplier.recordFailure(pSession, pFactory, pSuggestionId, ex.getMessage(), previous.applied, recorded, rollbackNote[0]);
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final Exception ex)
		{
			final String message = "applying '" + pSuggestionId + "' failed: " + ex.getMessage();
			DerivationApplier.recordFailure(pSession, pFactory, pSuggestionId, message, previous.applied, recorded, rollbackNote[0]);
			PipelineSteps.end(pSession, pFactory, stepId, false, message, detail.toString());
			throw EngineRefusal.codedWithCause("unexpected.applyFailed", message, ex, pSuggestionId);
		}
	}

	/** Two optional sentences joined by a space; {@code null} when both are. */
	static String joinNotes(final String pFirst, final String pSecond)
	{
		if (pFirst == null)
		{
			return pSecond;
		}
		return pSecond == null ? pFirst : pFirst + " " + pSecond;
	}

	// ------------------------------------------------------------------------------------------- library and bodies

	/**
	 * The target library function read through the scripting server and what the two bodies are built from (lot T2: factored out of {@link #apply} so that
	 * the master part, which builds the same bodies with its own mandatory flags, and {@code ebx.MasterGenerator} reuse one read). The library read
	 * and the function's return type are checked here, BEFORE anything is declared anywhere.
	 */
	static final class Bodies
	{
		final String importName;
		final String alias;
		final String functionName;
		final List<DerivationBodyBuilder.Param> parameters;

		Bodies(final String pImportName, final String pAlias, final String pFunctionName, final List<DerivationBodyBuilder.Param> pParameters)
		{
			this.importName = pImportName;
			this.alias = pAlias;
			this.functionName = pFunctionName;
			this.parameters = pParameters;
		}

		/** The {@code beforeCreate} body; {@code pAttributeMandatory} decides which bound attributes get a null guard. Throws {@link IllegalStateException} as {@link DerivationBodyBuilder} does. */
		String create(final Prepared pPrepared, final Map<String, Boolean> pAttributeMandatory)
		{
			return DerivationBodyBuilder.buildCreateTriggerBody(this.importName, this.alias, this.functionName, this.parameters, pPrepared.binding, pAttributeMandatory,
				pPrepared.newAttributeName);
		}

		/** The {@code beforeModify} body, same convention as {@link #create}. */
		String modify(final Prepared pPrepared, final Map<String, Boolean> pAttributeMandatory)
		{
			return DerivationBodyBuilder.buildModifyTriggerBody(this.importName, this.alias, this.functionName, this.parameters, pPrepared.binding, pAttributeMandatory,
				pPrepared.newAttributeName, pPrepared.attributeScriptTypes, pPrepared.derivedScriptType);
		}
	}

	static Bodies resolveBodies(final McpClient pScriptingClient, final Prepared p, final ObjectNode pDetail) throws McpException, OperationException
	{
		final ObjectNode libraryArgs = DerivationApplier.MAPPER.createObjectNode();
		libraryArgs.put("scriptIdePath", p.targetLibrary);
		libraryArgs.put("includeSource", true);
		final McpClient.ToolResult libraryResult = pScriptingClient.callTool("get_script", libraryArgs);
		pDetail.set("library", DerivationApplier.toolDetail(libraryArgs, libraryResult));
		if (libraryResult.error || libraryResult.json == null)
		{
			throw EngineRefusal.coded("external.readLibrary", "could not read library '" + p.targetLibrary + "': " + DerivationApplier.shortText(libraryResult.text), p.targetLibrary);
		}
		final ScriptInventory.ScriptDetails libraryDetails = ScriptInventory.parseScript(libraryResult.json);
		if (libraryDetails.source == null)
		{
			throw EngineRefusal.coded("conflict.libraryUnusable", "library '" + p.targetLibrary + "': " + libraryDetails.sourceNote, p.targetLibrary);
		}
		ScriptInventory.FunctionInfo function = null;
		for (final ScriptInventory.FunctionInfo candidate : ScriptInventory.parseExportedFunctions(libraryDetails.source))
		{
			if (candidate.name.equals(p.targetFunction))
			{
				function = candidate;
				break;
			}
		}
		if (function == null)
		{
			throw EngineRefusal.coded("conflict.functionGone", "function '" + p.targetFunction + "' is no longer exported by library '" + p.targetLibrary + "'", p.targetFunction, p.targetLibrary);
		}
		final String returnDataType = DerivationBodyBuilder.dataTypeForReturnType(function.returnType);
		if (returnDataType == null)
		{
			throw EngineRefusal.coded("conflict.functionTypeChanged", "function '" + p.targetFunction + "' now returns '" + function.returnType
				+ "', which has no matching attribute type - the suggestion is stale, reject and re-propose it", p.targetFunction, function.returnType);
		}
		final String importName = p.targetImportNameOnRow != null && !p.targetImportNameOnRow.trim().isEmpty() ? p.targetImportNameOnRow
			: libraryDetails.importName != null ? libraryDetails.importName : p.targetLibrary;
		// audit F2-01: the library read above and the name the body imports must be the same library, and neither the import name nor a bound attribute
		// may be anything but a name before it is written into a trigger body that is compiled and published
		if (libraryDetails.importName != null && !libraryDetails.importName.equals(importName))
		{
			throw EngineRefusal.coded("conflict.importLibraryMismatch", "the import name '" + importName + "' of '" + (p.entityName + "." + p.newAttributeName) + "' is not the library '" + p.targetLibrary
				+ "' the function was read from (its import name is '" + libraryDetails.importName + "'): reject and re-propose the suggestion", (p.entityName + "." + p.newAttributeName), importName, libraryDetails.importName);
		}
		DerivationApplier.refuseIfNotAName((p.entityName + "." + p.newAttributeName), ScriptGuard.importNameViolation(importName));
		for (final String boundAttribute : p.boundAttributes)
		{
			DerivationApplier.refuseIfNotAName((p.entityName + "." + p.newAttributeName), ScriptGuard.attributeNameViolation(boundAttribute));
		}
		return new Bodies(importName, DerivationApplier.aliasOf(importName), p.targetFunction, DerivationBodyBuilder.paramsOf(function.parameters));
	}

	/** Audit F2-01: a coded refusal for a name that is not a name; a null violation means fine. */
	private static void refuseIfNotAName(final String pSuggestionId, final String pViolation) throws OperationException
	{
		if (pViolation != null)
		{
			throw EngineRefusal.coded("invalid.templateScript", "the generated script of '" + pSuggestionId + "' was refused before it was compiled: " + pViolation, pSuggestionId, pViolation);
		}
	}

	// ----------------------------------------------------------------------------------------------------- remove

	/**
	 * Takes both triggers off the table (each by its exact name, never the whole trigger list), un-declares a function field left by an older apply,
	 * and republishes - then, lot T2, does the same for the master table when it carries them ({@link DerivationMasterTriggers#removeFromMaster}; tolerant of a
	 * master zone that does not exist, never fails the removal) - the attribute stays an ordinary stored field. <b>Does NOT change the {@code StagingAttribute} row</b> (its own {@code
	 * derivation=SCRIPT} stays set) and does not clear the values already stored, but the suggestion goes back to {@code ACCEPTED} with {@code
	 * appliedModel} cleared, i.e. "not applied": from the next staging load on, {@code ebx.StagingLoader} holds the field EMPTY (an explicit null,
	 * {@code transfer.NullFillRule}) and says so in the step message ({@code staging.ScriptDerivationGate}, lot M5, 2026-10-04) - it is never
	 * written with a value, and never a copy of its source. Rejecting the {@code StagingAttribute} itself is the real "undo everything", a
	 * person's own separate decision.
	 */
	public static ObjectNode remove(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pSuggestionId) throws OperationException
	{
		final Prepared p = DerivationApplier.prepare(pFactory, pProjectCode, pSuggestionId);
		final String stepId = PipelineSteps.begin(pSession, pFactory, p.projectId, DerivationApplier.STEP_REMOVE, pSuggestionId);
		final ObjectNode detail = DerivationApplier.MAPPER.createObjectNode();
		try
		{
			final McpClient dataModelClient = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
			final String tablePath = DerivationBodyBuilder.tableElementPath(p.entityName);

			// a function field declared by an older apply
			final McpClient.ToolResult functions = DerivationTriggers.listFunctions(dataModelClient, p.modelName);
			if (!functions.error && DerivationTriggers.isFunctionDeclared(functions.json, p.elementPath))
			{
				DerivationApplier.undeclareFunction(dataModelClient, p.modelName, p.elementPath, detail, "removeFunctionDeclaration");
				detail.put("undeclaredFunction", true);
			}

			// the two triggers, by name: unset only those the model reports (an absent one is already gone); when the listing itself fails, try both
			final McpClient.ToolResult listed = DerivationTriggers.listTriggers(dataModelClient, p.modelName);
			final boolean listingUsable = !listed.error && listed.json != null;
			for (final String name : DerivationTriggers.namesOf(p.newAttributeName))
			{
				if (listingUsable && DerivationTriggers.stateOf(listed.json, name) == DerivationTriggers.State.MISSING)
				{
					detail.put("alreadyAbsent_" + name, true);
					continue;
				}
				DerivationTriggers.unset(dataModelClient, p.modelName, tablePath, name, detail, "remove_" + name);
			}
			final ObjectNode publishArgs = DerivationApplier.MAPPER.createObjectNode();
			publishArgs.put("data_model_name", p.modelName);
			final McpClient.ToolResult publish = dataModelClient.callTool("publish_data_model", publishArgs);
			detail.set("publish", DerivationApplier.toolDetail(publishArgs, publish));
			if (!PublishDecision.published(publish.json))
			{
				throw EngineRefusal.coded("external.publishModel", "publish_data_model could not republish " + p.modelName + " after removal: " + DerivationApplier.shortText(publish.text), p.modelName);
			}
			// lot T2: the two triggers of the master table too, by name, tolerant of a master zone that does not exist or never had them; a problem there
			// never undoes the removal on staging (it is done and published) - it is in the answer and in applyMessage
			final ObjectNode master = DerivationMasterTriggers.removeFromMaster(dataModelClient, pProjectCode, p, detail);
			detail.set("master", master);
			DerivationApplier.writeRemoved(pSession, pFactory, pSuggestionId);
			final String masterNote = DerivationMasterTriggers.removeNoteFor(master);
			if (masterNote != null)
			{
				DerivationApplier.writeApplyMessageOnly(pSession, pFactory, pSuggestionId, masterNote);
			}
			PipelineSteps.end(pSession, pFactory, stepId, true, "removed the derivation of '" + p.newAttributeName + "' from " + p.entityName + " (master " + master.path("status").asText("?") + ")",
				detail.toString());
			final ObjectNode out = DerivationApplier.MAPPER.createObjectNode();
			out.put("suggestion", pSuggestionId);
			out.put("ok", true);
			out.set("master", master);
			return out;
		}
		catch (final OperationException ex)
		{
			DerivationApplier.writeApplyMessageOnly(pSession, pFactory, pSuggestionId, ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final Exception ex)
		{
			final String message = "removing '" + pSuggestionId + "' failed: " + ex.getMessage();
			DerivationApplier.writeApplyMessageOnly(pSession, pFactory, pSuggestionId, message);
			PipelineSteps.end(pSession, pFactory, stepId, false, message, detail.toString());
			throw EngineRefusal.codedWithCause("unexpected.removeFailed", message, ex, pSuggestionId);
		}
	}

	/** {@code unset_element_property(property=scriptedFunction)} on the derived field: the function-field declaration an older apply left. Throws when EBX refuses. */
	private static void undeclareFunction(final McpClient pDataModelClient, final String pModelName, final String pElementPath, final ObjectNode pDetail, final String pDetailKey)
		throws McpException, OperationException
	{
		final ObjectNode args = DerivationApplier.MAPPER.createObjectNode();
		args.put("data_model_name", pModelName);
		args.put("element_path", pElementPath);
		args.put("property", "scriptedFunction");
		final McpClient.ToolResult result = pDataModelClient.callTool("unset_element_property", args);
		pDetail.set(pDetailKey, DerivationApplier.toolDetail(args, result));
		if (result.error)
		{
			throw EngineRefusal.coded("external.undeclareFunction", "could not un-declare the function field " + pModelName + pElementPath + ": " + DerivationApplier.shortText(result.text), pModelName + pElementPath);
		}
	}

	// --------------------------------------------------------------------------------------------------- prepare

	/** What {@link #prepare} resolved for one suggestion; package-private so that {@link DerivationBackfillPass} reuses it. */
	static final class Prepared
	{
		final String projectId;
		final String entityName;
		final String modelName;
		final String newAttributeName;
		final String newAttributeLabel;
		final String elementPath;
		final String dataType;
		final String sourceAttribute;
		final String targetLibrary;
		final String targetFunction;
		final String targetImportNameOnRow;
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding;
		final Map<String, Boolean> attributeMandatory;
		final Map<String, String> attributeLabels;
		/** The distinct source attributes the binding reads, in parameter order (lot T1). */
		final List<String> boundAttributes;
		/** The EBX-Script type of every accepted attribute of the entity that has one the staging model builds (lot T1: the modify trigger compares old and new values by type). */
		final Map<String, String> attributeScriptTypes;
		/** The EBX-Script type of the derived attribute. */
		final String derivedScriptType;
		/** {@code StagingAttribute.maxLength} of the derived attribute, 0 when unset (the backfill marker must fit it). */
		final int derivedMaxLength;

		Prepared(final String pProjectId, final String pEntityName, final String pModelName, final String pNewAttributeName, final String pNewAttributeLabel,
			final String pElementPath, final String pDataType, final String pSourceAttribute, final String pTargetLibrary, final String pTargetFunction,
			final String pTargetImportNameOnRow, final Map<String, ConstraintBodyBuilder.BindingEntry> pBinding, final Map<String, Boolean> pAttributeMandatory,
			final Map<String, String> pAttributeLabels,
			final List<String> pBoundAttributes, final Map<String, String> pAttributeScriptTypes, final String pDerivedScriptType, final int pDerivedMaxLength)
		{
			this.projectId = pProjectId;
			this.entityName = pEntityName;
			this.modelName = pModelName;
			this.newAttributeName = pNewAttributeName;
			this.newAttributeLabel = pNewAttributeLabel;
			this.elementPath = pElementPath;
			this.dataType = pDataType;
			this.sourceAttribute = pSourceAttribute;
			this.targetLibrary = pTargetLibrary;
			this.targetFunction = pTargetFunction;
			this.targetImportNameOnRow = pTargetImportNameOnRow;
			this.binding = pBinding;
			this.attributeMandatory = pAttributeMandatory;
			this.attributeLabels = pAttributeLabels;
			this.boundAttributes = pBoundAttributes;
			this.attributeScriptTypes = pAttributeScriptTypes;
			this.derivedScriptType = pDerivedScriptType;
			this.derivedMaxLength = pDerivedMaxLength;
		}
	}

	static Prepared prepare(final Adaptation pFactory, final String pProjectCode, final String pSuggestionId) throws OperationException
	{
		final Adaptation project = ProposalReviews.findProjectByCode(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final AdaptationTable suggestionTable = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION);
		final Adaptation row = ProposalReviews.byId(suggestionTable, pSuggestionId);
		if (row == null)
		{
			throw EngineRefusal.coded("missing.suggestion", "capability suggestion '" + pSuggestionId + "' not found", pSuggestionId);
		}
		if (!projectId.equals(DerivationApplier.text(row, FactoryPaths.CapabilitySuggestion.PROJECT)))
		{
			throw EngineRefusal.coded("conflict.suggestionOtherProject", "capability suggestion '" + pSuggestionId + "' does not belong to project " + pProjectCode, pSuggestionId, pProjectCode);
		}
		if (!DerivationApplier.NATURE_SCRIPT_DERIVATION.equals(row.getString(FactoryPaths.CapabilitySuggestion.NATURE)))
		{
			throw EngineRefusal.coded("conflict.suggestionKindDerivation", "only SCRIPT_DERIVATION suggestions can be applied through DerivationApplier - '" + pSuggestionId + "' is "
				+ row.getString(FactoryPaths.CapabilitySuggestion.NATURE), pSuggestionId);
		}
		final String currentStatus = DerivationApplier.text(row, FactoryPaths.CapabilitySuggestion.STATUS);
		if (!DerivationApplier.STATUS_ACCEPTED.equals(currentStatus) && !DerivationApplier.STATUS_APPLIED.equals(currentStatus))
		{
			throw EngineRefusal.coded("conflict.mustBeAccepted", "suggestion '" + pSuggestionId + "' must be ACCEPTED first (current status: " + currentStatus + ")", pSuggestionId, currentStatus);
		}

		final String entityId = row.getString(FactoryPaths.CapabilitySuggestion.ENTITY);
		final Adaptation entity = ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ENTITY, entityId);
		if (entity == null)
		{
			throw EngineRefusal.coded("conflict.entityGone", "the entity of suggestion '" + pSuggestionId + "' no longer exists", pSuggestionId);
		}
		final String entityName = entity.getString(FactoryPaths.StagingEntity.NAME);
		final String modelName = StagingNames.modelName(pProjectCode);

		final String newAttributeId = DerivationApplier.text(row, FactoryPaths.CapabilitySuggestion.ATTRIBUTE);
		final Adaptation attributeRow = newAttributeId.isEmpty() ? null : ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ATTRIBUTE, newAttributeId);
		if (attributeRow == null)
		{
			throw EngineRefusal.coded("conflict.attributeGone", "the derived attribute of suggestion '" + pSuggestionId + "' no longer exists", pSuggestionId);
		}
		final String newAttributeName = attributeRow.getString(FactoryPaths.StagingAttribute.NAME);
		final String rawLabel = attributeRow.getString(FactoryPaths.StagingAttribute.LABEL);
		final String newAttributeLabel = rawLabel == null || rawLabel.trim().isEmpty() ? newAttributeName : rawLabel.trim();
		final String dataType = attributeRow.getString(FactoryPaths.StagingAttribute.DATA_TYPE);
		final String sourceAttributeId = attributeRow.getString(FactoryPaths.StagingAttribute.DERIVED_FROM);
		final Adaptation sourceAttributeRow = sourceAttributeId == null ? null : ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ATTRIBUTE, sourceAttributeId);
		final String sourceAttribute = sourceAttributeRow == null ? null : sourceAttributeRow.getString(FactoryPaths.StagingAttribute.NAME);
		if (sourceAttribute == null)
		{
			throw EngineRefusal.coded("conflict.attributeGone", "the source attribute of suggestion '" + pSuggestionId + "' no longer exists", pSuggestionId);
		}

		final Map<String, Boolean> attributeMandatory = DerivationApplier.readAttributeMandatory(pFactory, entityId);
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = ConstraintBodyBuilder.parseBinding(row.getString(FactoryPaths.CapabilitySuggestion.BINDING));
		final List<String> toBeProvided = ConstraintBodyBuilder.toBeProvidedParameters(binding);
		if (!toBeProvided.isEmpty())
		{
			// lot P1 (2026-10-04): the block stays here (accepting the attribute and applying its computation are two moments), but the
			// message names the parameter(s) and says where a person supplies them
			throw EngineRefusal.coded("conflict.paramsToProvide", DerivationParameters.toBeProvidedMessage(pSuggestionId, toBeProvided, pProjectCode), pSuggestionId);
		}
		for (final String attributeName : ConstraintBodyBuilder.attributeBoundNames(binding))
		{
			if (!attributeMandatory.containsKey(attributeName))
			{
				throw EngineRefusal.coded("conflict.bindingAttribute", "the binding of '" + pSuggestionId + "' names attribute '" + attributeName
					+ "', which is not (or no longer) an accepted attribute of entity '" + entityName + "'", pSuggestionId, attributeName, entityName);
			}
		}
		final Map<String, String> attributeLabels = DerivationApplier.readAttributeLabels(pFactory, entityId);
		final String elementPath = DerivationBodyBuilder.elementPath(entityName, newAttributeName);

		// lot T1: the modify trigger compares old and new values with one typed helper per type - a type with none is refused, never guessed
		final Map<String, String> dataTypes = DerivationApplier.readAttributeDataTypes(pFactory, entityId);
		final Map<String, String> scriptTypes = new java.util.LinkedHashMap<String, String>();
		final List<String> boundAttributes = ConstraintBodyBuilder.attributeBoundNames(binding);
		for (final String attributeName : boundAttributes)
		{
			final String scriptType = DerivationBodyBuilder.scriptTypeOrNull(dataTypes.get(attributeName));
			if (scriptType == null)
			{
				throw EngineRefusal.coded("invalid.changeTriggerType", "cannot apply '" + pSuggestionId + "': attribute '" + attributeName + "' has data type '" + dataTypes.get(attributeName)
					+ "', which the change trigger cannot compare (supported: STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP)", pSuggestionId, attributeName);
			}
			scriptTypes.put(attributeName, scriptType);
		}
		final String derivedScriptType = DerivationBodyBuilder.scriptTypeOrNull(dataType);
		if (derivedScriptType == null)
		{
			throw EngineRefusal.coded("invalid.changeTriggerType", "cannot apply '" + pSuggestionId + "': the derived attribute '" + newAttributeName + "' has data type '" + dataType
				+ "', which the change trigger cannot compare (supported: STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP)", pSuggestionId, newAttributeName);
		}

		final Object rawMaxLength = attributeRow.get(FactoryPaths.StagingAttribute.MAX_LENGTH);
		final int derivedMaxLength = rawMaxLength instanceof Number ? ((Number) rawMaxLength).intValue() : 0;

		return new Prepared(projectId, entityName, modelName, newAttributeName, newAttributeLabel, elementPath, dataType, sourceAttribute,
			row.getString(FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY), row.getString(FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION),
			row.getString(FactoryPaths.CapabilitySuggestion.TARGET_IMPORT_NAME), binding, attributeMandatory, attributeLabels, boundAttributes, scriptTypes,
			derivedScriptType, derivedMaxLength);
	}

	// --------------------------------------------------------------------------------------------------- helpers

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

	/** What was applied before this round: whether the row was APPLIED (its triggers declared and published) and the two bodies its stored {@code scriptBody} holds, {@code null} when unreadable. */
	static final class Previous
	{
		final boolean applied;
		final DerivationBodyBuilder.StoredBodies bodies;

		Previous(final boolean pApplied, final DerivationBodyBuilder.StoredBodies pBodies)
		{
			this.applied = pApplied;
			this.bodies = pBodies;
		}
	}

	private static Previous readPrevious(final Adaptation pFactory, final String pSuggestionId, final String pAttributeName)
	{
		final Adaptation row = ProposalReviews.byId(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION), pSuggestionId);
		if (row == null || !DerivationApplier.STATUS_APPLIED.equals(DerivationApplier.text(row, FactoryPaths.CapabilitySuggestion.STATUS)))
		{
			return new Previous(false, null);
		}
		return new Previous(true, DerivationBodyBuilder.splitStoredBodies(row.getString(FactoryPaths.CapabilitySuggestion.SCRIPT_BODY),
			DerivationBodyBuilder.triggerName(pAttributeName, true), DerivationBodyBuilder.triggerName(pAttributeName, false)));
	}

	/**
	 * Best-effort (CapabilityApplier's own {@code rollback}, same discipline): never lets a rollback problem hide the ORIGINAL failure from the
	 * caller - {@link #apply} always re-throws right after calling this, whatever happens here. What it does is decided by {@link
	 * DerivationApplyFailure#decide} (lot T2, 2026-10-04 - the defect found during lot P1):
	 * <ul>
	 * <li><b>a first apply</b>: takes each of the two triggers off the DRAFT by its exact name (never the whole trigger list of the table; an entry that
	 * was never declared just answers an error, recorded and ignored); republishes only when a publish had already genuinely happened this round - a
	 * failure before that point never made the triggers part of a published model, and republishing anyway would risk publishing some OTHER, unrelated
	 * pending draft change on the same model prematurely;</li>
	 * <li><b>a RE-apply</b> (the row was APPLIED, its triggers published): leaves the declarations ALONE - taking them off the draft would make the next
	 * {@code publish_data_model} of the model, for any reason, silently drop the published triggers - and writes the previous bodies (the two the stored
	 * {@code scriptBody} holds) back to the scripting draft, republishing only when this round had published. Without readable previous bodies nothing is
	 * restored and the returned sentence says so.</li>
	 * </ul>
	 * A function-field declaration un-declared by the migration is not put back: it could never have been authored (an indexed function field refuses a library import).
	 *
	 * @return a sentence to add to the failure text when the previous state could not be put back completely, else {@code null}
	 */
	private static String rollback(final McpClient pDataModelClient, final McpClient pScriptingClient, final Prepared p, final String pTablePath, final Previous pPrevious,
		final String pCreateIdePath, final String pModifyIdePath, final boolean pPublishedThisRound, final ObjectNode pDetail)
	{
		if (pDataModelClient == null)
		{
			return null;
		}
		final DerivationApplyFailure.Plan plan = DerivationApplyFailure.decide(pPrevious.applied, pPublishedThisRound, pPrevious.bodies != null);
		pDetail.put("rollbackPlan", "removeDeclarations=" + plan.removeDeclarations + " restoreBodies=" + plan.restoreBodies + " republish=" + plan.republish
			+ " statusStaysApplied=" + plan.statusStaysApplied);
		String note = null;
		try
		{
			if (plan.removeDeclarations)
			{
				for (final String name : DerivationTriggers.namesOf(p.newAttributeName))
				{
					DerivationTriggers.unsetRaw(pDataModelClient, p.modelName, pTablePath, name, pDetail, "rollbackRemove_" + name);
				}
			}
			boolean restored = false;
			if (plan.restoreBodies && pScriptingClient != null)
			{
				final boolean createBack = DerivationTriggers.restore(pScriptingClient, pCreateIdePath, pPrevious.bodies.createSource, pDetail, "rollbackRestoreCreate");
				final boolean modifyBack = DerivationTriggers.restore(pScriptingClient, pModifyIdePath, pPrevious.bodies.modifySource, pDetail, "rollbackRestoreModify");
				restored = createBack && modifyBack;
				if (!restored)
				{
					note = "The previous trigger bodies could not all be written back to the scripting draft (see the step detail).";
				}
			}
			else if (pPrevious.applied)
			{
				note = "The previous trigger bodies could not be read from the stored script body, so none was written back.";
			}
			if (plan.republish && (!plan.restoreBodies || restored))
			{
				final ObjectNode publishArgs = DerivationApplier.MAPPER.createObjectNode();
				publishArgs.put("data_model_name", p.modelName);
				final McpClient.ToolResult republish = pDataModelClient.callTool("publish_data_model", publishArgs);
				pDetail.set("rollbackRepublish", DerivationApplier.toolDetail(publishArgs, republish));
			}
		}
		catch (final Exception ex)
		{
			pDetail.put("rollbackError", ex.getMessage());
			note = "The rollback itself failed: " + ex.getMessage();
		}
		return note;
	}

	private static String aliasOf(final String pImportName)
	{
		final String name = pImportName == null ? "" : pImportName.trim();
		final int lastDot = name.lastIndexOf('.');
		final String tail = lastDot >= 0 && lastDot + 1 < name.length() ? name.substring(lastDot + 1) : name;
		return tail.isEmpty() ? "lib" : tail;
	}

	private static Map<String, Boolean> readAttributeMandatory(final Adaptation pFactory, final String pEntityId)
	{
		final Map<String, Boolean> out = new java.util.LinkedHashMap<String, Boolean>();
		final com.onwbp.adaptation.RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE)
			.createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String status = row.getString(FactoryPaths.StagingAttribute.STATUS);
				if (!"ACCEPTED".equals(status) && !"APPLIED".equals(status))
				{
					continue;
				}
				out.put(row.getString(FactoryPaths.StagingAttribute.NAME), Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.MANDATORY)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** {@code StagingAttribute.dataType} per accepted (or applied) attribute name of the entity - null entries are kept out; a blank type reads as absent (the caller maps it to string). */
	private static Map<String, String> readAttributeDataTypes(final Adaptation pFactory, final String pEntityId)
	{
		final Map<String, String> out = new java.util.LinkedHashMap<String, String>();
		final com.onwbp.adaptation.RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE)
			.createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String dataType = row.getString(FactoryPaths.StagingAttribute.DATA_TYPE);
				out.put(row.getString(FactoryPaths.StagingAttribute.NAME), dataType == null ? "" : dataType);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static Map<String, String> readAttributeLabels(final Adaptation pFactory, final String pEntityId)
	{
		final Map<String, String> out = new java.util.LinkedHashMap<String, String>();
		final com.onwbp.adaptation.RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE)
			.createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String label = row.getString(FactoryPaths.StagingAttribute.LABEL);
				if (label != null && !label.trim().isEmpty())
				{
					out.put(row.getString(FactoryPaths.StagingAttribute.NAME), label.trim());
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static String text(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final String value = pRow.getString(pPath);
		return value == null ? "" : value;
	}

	static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}

	static ObjectNode toolDetail(final ObjectNode pArguments, final McpClient.ToolResult pResult)
	{
		final ObjectNode node = DerivationApplier.MAPPER.createObjectNode();
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

	// -------------------------------------------------------------------------------------------------- writing

	/** @throws OperationException the row could not be written: the triggers are live on the model, but the suggestion must not read as applied (the caller's catch ends the step FAILED) */
	private static void writeApplied(final Session pSession, final Adaptation pFactory, final String pSuggestionId, final String pModelName,
		final String pElementPath, final String pScriptIdePath, final String pScriptBody, final String pExplanation) throws OperationException
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION);
		final Date now = new Date();
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pSuggestionId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(DerivationApplier.STATUS_APPLIED, FactoryPaths.CapabilitySuggestion.STATUS);
				value.setValue(pModelName, FactoryPaths.CapabilitySuggestion.APPLIED_MODEL);
				value.setValue(pElementPath, FactoryPaths.CapabilitySuggestion.APPLIED_ELEMENT_PATH);
				value.setValue(pScriptIdePath, FactoryPaths.CapabilitySuggestion.APPLIED_SCRIPT_IDE_PATH);
				value.setValue(pScriptBody, FactoryPaths.CapabilitySuggestion.SCRIPT_BODY);
				value.setValue(pExplanation, FactoryPaths.CapabilitySuggestion.EXPLANATION);
				value.setValue(now, FactoryPaths.CapabilitySuggestion.APPLIED_AT);
				value.setValue(null, FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			final String why = result.getExceptionFullMessage(Locale.ENGLISH);
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] could not record the applied derivation of suggestion " + pSuggestionId + ": " + why);
			throw EngineRefusal.coded("unexpected.recordTriggers", "the triggers of suggestion '" + pSuggestionId + "' are live on " + pModelName + " but recording them on the suggestion failed ("
				+ why + "): the suggestion does not read APPLIED. Check the model, then apply it again.", pSuggestionId);
		}
	}

	/**
	 * Records a failed apply on the row (lot T2, defect 3a): a row that was APPLIED before this round stays APPLIED and keeps "apply again" visible
	 * ({@link DerivationApplyFailure#message}); a row that was not goes back to ACCEPTED as it always did; a row whose application was already
	 * recorded this round (a failure after {@code writeApplied}) only gets its message.
	 */
	private static void recordFailure(final Session pSession, final Adaptation pFactory, final String pSuggestionId, final String pFailure, final boolean pPreviouslyApplied,
		final boolean pAlreadyRecorded, final String pRollbackNote)
	{
		final String failure = pRollbackNote == null ? pFailure : pFailure + " " + pRollbackNote;
		if (pAlreadyRecorded)
		{
			DerivationApplier.writeApplyMessageOnly(pSession, pFactory, pSuggestionId, failure);
			return;
		}
		DerivationApplier.writeApplyFailure(pSession, pFactory, pSuggestionId, DerivationApplyFailure.message(pPreviouslyApplied, failure), pPreviouslyApplied);
	}

	/**
	 * Stores a refusal {@link #prepare} threw before the step began (lot T2, defect 3b) in {@code applyMessage}, so that the wizard can show why - only for
	 * an ACCEPTED or APPLIED derivation of this very project (a refusal about another project, another nature or a status that cannot be applied is
	 * not this row's message), and never lets a problem here hide the refusal itself.
	 */
	private static void recordRefusal(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pSuggestionId, final String pRefusal)
	{
		try
		{
			final Adaptation project = ProposalReviews.findProjectByCode(pFactory, pProjectCode);
			final Adaptation row = project == null ? null : ProposalReviews.byId(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION), pSuggestionId);
			if (row == null || !project.getOccurrencePrimaryKey().format().equals(DerivationApplier.text(row, FactoryPaths.CapabilitySuggestion.PROJECT))
				|| !DerivationApplier.NATURE_SCRIPT_DERIVATION.equals(row.getString(FactoryPaths.CapabilitySuggestion.NATURE)))
			{
				return;
			}
			final String message = DerivationApplyFailure.refusalMessage(DerivationApplier.text(row, FactoryPaths.CapabilitySuggestion.STATUS), pRefusal);
			if (message != null)
			{
				DerivationApplier.writeApplyMessageOnly(pSession, pFactory, pSuggestionId, message);
			}
		}
		catch (final RuntimeException ex)
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] could not record the refusal of suggestion " + pSuggestionId + ": " + ex.getMessage());
		}
	}

	private static void writeApplyFailure(final Session pSession, final Adaptation pFactory, final String pSuggestionId, final String pMessage, final boolean pKeepApplied)
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pSuggestionId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				if (!pKeepApplied)
				{
					value.setValue(DerivationApplier.STATUS_ACCEPTED, FactoryPaths.CapabilitySuggestion.STATUS);
				}
				value.setValue(pMessage == null ? null : (pMessage.length() > DerivationParameters.APPLY_MESSAGE_MAX ? pMessage.substring(0, DerivationParameters.APPLY_MESSAGE_MAX) : pMessage),
					FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			com.orchestranetworks.service.LoggingCategory.getKernel()
				.error("[mdm-factory] could not record the apply failure of suggestion " + pSuggestionId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	private static void writeApplyMessageOnly(final Session pSession, final Adaptation pFactory, final String pSuggestionId, final String pMessage)
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pSuggestionId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pMessage == null ? null : (pMessage.length() > 2000 ? pMessage.substring(0, 2000) : pMessage), FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			com.orchestranetworks.service.LoggingCategory.getKernel()
				.error("[mdm-factory] could not record the remove failure of suggestion " + pSuggestionId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	private static void writeRemoved(final Session pSession, final Adaptation pFactory, final String pSuggestionId) throws OperationException
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pSuggestionId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(DerivationApplier.STATUS_ACCEPTED, FactoryPaths.CapabilitySuggestion.STATUS);
				value.setValue(null, FactoryPaths.CapabilitySuggestion.APPLIED_MODEL);
				value.setValue(null, FactoryPaths.CapabilitySuggestion.APPLIED_ELEMENT_PATH);
				value.setValue(null, FactoryPaths.CapabilitySuggestion.APPLIED_SCRIPT_IDE_PATH);
				value.setValue(null, FactoryPaths.CapabilitySuggestion.SCRIPT_BODY);
				value.setValue(null, FactoryPaths.CapabilitySuggestion.EXPLANATION);
				value.setValue(null, FactoryPaths.CapabilitySuggestion.APPLIED_AT);
				value.setValue(null, FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.recordRemoval", "could not record the removal of suggestion " + pSuggestionId + ": " + result.getExceptionFullMessage(Locale.ENGLISH), pSuggestionId);
		}
	}
}
