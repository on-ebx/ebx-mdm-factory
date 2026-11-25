package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.ForeignWording;
import com.on.ebx.factory.core.PublishDecision;
import com.on.ebx.factory.master.BusinessObjectPlan;
import com.on.ebx.factory.master.MameAnswer;
import com.on.ebx.factory.project.MatchingConfigRows;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Step-reset lot (2026-09-23): the four ways of deleting one generated EBX object, extracted out of {@code
 * ProjectDeleter} (unchanged behaviour - a straight move, not a rewrite) so {@code StepResetter} can reuse the
 * exact same mechanics without a second implementation drifting from the first. {@code ProjectDeleter} keeps
 * deciding WHAT is eligible ({@link com.on.ebx.factory.project.ProjectDeletionPlan}) and in what order; this
 * class only knows HOW to remove one already-decided item:
 * <ul>
 * <li>a matching configuration - {@code delete_matching_config} on the {@code match-merge} MCP server, {@code
 * dataModel} sent unconditionally via {@link MameCalls#call} (`docs/MATCHING.md` &sect;28);</li>
 * <li>a Connectors flow - {@code connectors_mapping_delete} on the {@code ebx-connectors} MCP server;</li>
 * <li>a dataspace - {@code Repository.closeHome}/{@code deleteHome}, called directly with the session outside
 * any {@code Procedure}, exactly as {@code ebx.MasterReset} first established;</li>
 * <li>a data model - {@code execute_operation(operation="delete_data_model", arguments_json={"confirm":
 * "true"}})} on the {@code data-model} MCP server, same call shape as {@code ebx.MasterReset}.</li>
 * </ul>
 * Every method is best-effort about telling "already gone" (a retry-safe {@link Outcome#absent()}) apart from
 * a genuine failure - see each method's own comment for the exact signal it reads.
 */
final class GeneratedObjectDeleter
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private GeneratedObjectDeleter()
	{
	}

	/** The {@link Outcome#result} of a configuration the Match and Merge tools cannot delete: neither a success nor a failure, the plan goes on. */
	static final String RESULT_KEPT_BUSINESS_OBJECT = "KEPT_BUSINESS_OBJECT";

	/** The {@link Outcome#result} of a connection or catalogue source that something else still uses (lot C, 2026-10-17): kept, said so, not a failure. */
	static final String RESULT_KEPT_IN_USE = "KEPT_IN_USE";

	/** Bundle key (EN/FR) of the reason a business-object member's matching configuration stays in place. */
	static final String KEPT_BUSINESS_OBJECT_CODE = "engine.external.matchingConfigKeptBusinessObject";

	static final class Outcome
	{
		final String result;
		final String message;
		/** Bundle key of the reason when {@link #result} is a coded one ({@link #RESULT_KEPT_BUSINESS_OBJECT}), else null. */
		final String code;
		/**
		 * Lot M18 (second round): what the add-on / the server itself answered, clipped (the raw tool text), so that a refusal reads itself in the job's message and the
		 * answer of the route; null when the outcome has no such answer.
		 */
		final String answer;

		private Outcome(final String pResult, final String pMessage)
		{
			this(pResult, pMessage, null);
		}

		private Outcome(final String pResult, final String pMessage, final String pCode)
		{
			this(pResult, pMessage, pCode, null);
		}

		private Outcome(final String pResult, final String pMessage, final String pCode, final String pAnswer)
		{
			this.result = pResult;
			this.message = pMessage;
			this.code = pCode;
			this.answer = pAnswer;
		}

		/** The same outcome, carrying the add-on's own answer (clipped). */
		Outcome withAnswer(final String pAnswer)
		{
			return new Outcome(this.result, this.message, this.code, GeneratedObjectDeleter.shortText(pAnswer));
		}

		/** The matching configuration of {@code pTablePath} stays: the add-on refuses to delete a business-object member. */
		static Outcome keptBusinessObject(final String pTablePath)
		{
			return new Outcome(RESULT_KEPT_BUSINESS_OBJECT, GeneratedObjectDeleter.keptBusinessObjectSentence(pTablePath), KEPT_BUSINESS_OBJECT_CODE);
		}

		/** A connection or catalogue source that is still used by something the deletion does not remove: kept, with the reason. */
		static Outcome keptInUse(final String pMessage)
		{
			return new Outcome(RESULT_KEPT_IN_USE, pMessage);
		}

		static Outcome deleted(final String pMessage)
		{
			return new Outcome("DELETED", pMessage);
		}

		static Outcome absent()
		{
			return new Outcome("SKIPPED_ABSENT", null);
		}

		static Outcome failed(final String pMessage)
		{
			return new Outcome("FAILED", pMessage);
		}
	}

	/**
	 * {@code delete_matching_config} - `docs/MATCHING.md` &sect;28 (2026-09-23 14:20): {@code dataModel} is
	 * sent unconditionally, from {@code pMasterModelName}, via {@link MameCalls#call}. {@code
	 * CONFIG_NOT_FOUND}/{@code TABLE_NOT_FOUND} both count as "already absent".
	 */
	static Outcome deleteMatchingConfig(final Session pSession, final Adaptation pFactory, final String pTablePath, final String pMasterModelName) throws OperationException, McpException
	{
		final McpClient client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);
		final ObjectNode args = GeneratedObjectDeleter.MAPPER.createObjectNode();
		args.put("table", pTablePath);
		final McpClient.ToolResult result;
		try
		{
			result = MameCalls.call(client, "delete_matching_config", args, pMasterModelName);
		}
		catch (final McpException ex)
		{
			if (GeneratedObjectDeleter.isBusinessObjectMemberRefusal(ex.getMessage()))
			{
				return Outcome.keptBusinessObject(pTablePath).withAnswer(ex.getMessage());
			}
			throw ex;
		}
		final boolean ok = MameAnswer.success(result.json, false);
		final String code = MameAnswer.code(result.json);
		if (ok)
		{
			final boolean metadatasetDeleted = MameAnswer.field(result.json, "metadatasetDeleted").asBoolean(false);
			final boolean metadatasetOrphaned = MameAnswer.field(result.json, "metadatasetOrphaned").asBoolean(false);
			final String message = metadatasetOrphaned ? "matching configuration deleted; its metadataset was left ORPHANED (see the tool's own contract)"
				: metadatasetDeleted ? "matching configuration and its metadataset deleted" : "matching configuration deleted";
			return Outcome.deleted(message).withAnswer(result.text);
		}
		// 2026-10-18 22:17 (RC3): the add-on deleted the configuration and only THEN could not resolve the table after its schema reset (the model failed to recompile): that is
		// DELETED, with the add-on's text as the reason - pinned to the message prefix, never to the bare TABLE_NOT_FOUND code (which also means "never configured / model gone")
		final String answerMessage = result.json != null ? result.json.path("message").asText("") : "";
		if ("TABLE_NOT_FOUND".equals(code) && GeneratedObjectDeleter.isConfigurationDeletedButTableUnresolved(answerMessage))
		{
			return Outcome.deleted("matching configuration deleted; the add-on reports: " + GeneratedObjectDeleter.shortText(answerMessage)).withAnswer(result.text);
		}
		if ("CONFIG_NOT_FOUND".equals(code) || "TABLE_NOT_FOUND".equals(code))
		{
			return Outcome.absent().withAnswer(result.text);
		}
		final String message = result.json != null ? result.json.path("message").asText(GeneratedObjectDeleter.shortText(result.text))
			: GeneratedObjectDeleter.shortText(result.text);
		// measured on W13BO (RC3, 2026-10-12): delete_matching_config on a business-object member answers INVALID_PARAMS "Table ... is part of a business object; use the
		// business-object tools instead", and there is no business-object delete tool on the match-merge server - not a failure that stops the plan
		if (GeneratedObjectDeleter.isBusinessObjectMemberRefusal(message) || GeneratedObjectDeleter.isBusinessObjectMemberRefusal(result.text))
		{
			return Outcome.keptBusinessObject(pTablePath).withAnswer(result.text);
		}
		return Outcome.failed(message).withAnswer(result.text);
	}

	/** The exact prefix of the add-on's TABLE_NOT_FOUND message that says the configuration WAS deleted (measured 2026-10-18 22:17, Match and Merge RC3). */
	static final String CONFIGURATION_DELETED_PREFIX = "The configuration was deleted but table ";

	/**
	 * Is this the add-on's answer {@code "The configuration was deleted but table /root/X no longer resolves after the schema reset."}? Exactly that message prefix (measured
	 * 2026-10-18 22:17: the master model failed to recompile once the main table's configuration was gone, because the distribution triggers read {@code Linked_Field_State});
	 * nothing broader - {@code "Table not found: /root/Adresse"} and every other text stay what they were.
	 */
	static boolean isConfigurationDeletedButTableUnresolved(final String pMessage)
	{
		return pMessage != null && pMessage.trim().startsWith(GeneratedObjectDeleter.CONFIGURATION_DELETED_PREFIX);
	}

	/** What the removal of the distribution triggers needs from the data-model and scripting servers - an interface so the sequence is testable without them. */
	interface TriggerOps
	{
		/** {@code unset_element_property(property=triggers, item=name)}: null when the server removed it, else the text of its refusal (an error RESULT); a JSON-RPC error is thrown. */
		String unset(String pTablePath, String pTriggerName) throws McpException;

		/** True only when the scripting server positively answers that the trigger script does not exist (never on a failure to ask). */
		boolean scriptGone(String pTablePath, String pTriggerName);

		void publish() throws McpException;
	}

	/** The arguments of one {@code unset_element_property} call: ONE named trigger, {@code item} always given (without it the table's whole trigger list, Java triggers included, would go). */
	static ObjectNode unsetTriggerArgs(final String pModelName, final String pTablePath, final String pTriggerName)
	{
		final ObjectNode args = GeneratedObjectDeleter.MAPPER.createObjectNode();
		args.put("data_model_name", pModelName);
		args.put("element_path", pTablePath);
		args.put("property", "triggers");
		args.put("item", pTriggerName);
		return args;
	}

	/**
	 * Lot S9 (2026-10-18, live): removes the distribution triggers from the master model BEFORE any matching configuration - they read {@code Linked_Field_State} on every
	 * table that carries it, so deleting one table's configuration while they exist breaks the model's compile and every later configuration answers "Table not found".
	 * The names come from {@link DistributionTriggerInstaller#scriptNames()} (the very names it declares), one {@code unset_element_property} per table and name, then ONE
	 * {@code publish_data_model}. A trigger that is not declared (the answer reads "already removed", or the scripting server says the script does not exist) and a model that
	 * is gone ({@link #DATA_MODEL_ABSENT_MARKER}) are absent, never a failure; DELETED when at least one trigger went.
	 */
	static Outcome deleteDistributionTriggers(final Session pSession, final Adaptation pFactory, final String pModelName, final List<String> pTablePaths) throws OperationException, McpException
	{
		final McpClient dataModel = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
		final McpClient scripting = McpServers.client(pSession, pFactory, McpServers.SCRIPTING);
		return GeneratedObjectDeleter.removeDistributionTriggers(pModelName, pTablePaths, DistributionTriggerInstaller.scriptNames(), new TriggerOps()
		{
			@Override
			public String unset(final String pTablePath, final String pTriggerName) throws McpException
			{
				final McpClient.ToolResult result = dataModel.callTool("unset_element_property", GeneratedObjectDeleter.unsetTriggerArgs(pModelName, pTablePath, pTriggerName));
				return result.error ? GeneratedObjectDeleter.errorText(result) : null;
			}

			@Override
			public boolean scriptGone(final String pTablePath, final String pTriggerName)
			{
				try
				{
					final ObjectNode args = GeneratedObjectDeleter.MAPPER.createObjectNode();
					args.put("scriptIdePath", ScriptTriggerOps.idePath(pModelName, pTablePath.substring(pTablePath.lastIndexOf('/') + 1), pTriggerName));
					final McpClient.ToolResult r = scripting.callTool("get_script", args);
					return !r.error && r.json != null && (r.json.hasNonNull("code") && !r.json.path("code").asText().isEmpty() || !r.json.path("data").hasNonNull("revisionId"));
				}
				catch (final McpException ex)
				{
					return false;
				}
			}

			@Override
			public void publish() throws McpException
			{
				final ObjectNode args = GeneratedObjectDeleter.MAPPER.createObjectNode();
				args.put("data_model_name", pModelName);
				final McpClient.ToolResult r = dataModel.callTool("publish_data_model", args);
				if (r.error || !PublishDecision.published(r.json))
				{
					throw new McpException("publish_data_model after removing the distribution triggers of " + pModelName + ": " + GeneratedObjectDeleter.shortText(r.text), null);
				}
			}
		});
	}

	/** The sequence of {@link #deleteDistributionTriggers}, free of the MCP clients. Pure given {@code pOps}. */
	static Outcome removeDistributionTriggers(final String pModelName, final List<String> pTablePaths, final List<String> pTriggerNames, final TriggerOps pOps)
	{
		int removed = 0;
		int absent = 0;
		final List<String> failures = new ArrayList<String>();
		for (final String table : pTablePaths)
		{
			for (final String name : pTriggerNames)
			{
				final String text;
				try
				{
					text = pOps.unset(table, name);
				}
				catch (final McpException ex)
				{
					if (GeneratedObjectDeleter.thrownMeansAbsent(ex.getMessage(), GeneratedObjectDeleter.DATA_MODEL_ABSENT_MARKER))
					{
						return Outcome.absent();
					}
					failures.add(table + " " + name + ": " + GeneratedObjectDeleter.shortText(ex.getMessage()));
					continue;
				}
				if (text == null)
				{
					removed++;
					continue;
				}
				if (ForeignWording.looksAlreadyRemoved(text) || ForeignWording.thrownMeansAbsent(text, ForeignWording.DATA_MODEL_NOT_FOUND) || pOps.scriptGone(table, name))
				{
					absent++;
				}
				else
				{
					failures.add(table + " " + name + ": " + text);
				}
			}
		}
		if (!failures.isEmpty())
		{
			return Outcome.failed("the distribution triggers of " + pModelName + " could not all be removed: " + GeneratedObjectDeleter.join(failures, "; ")
				+ " (the matching configurations are kept: deleting them with these triggers in place would break the model's compile)");
		}
		if (removed == 0)
		{
			return Outcome.absent();
		}
		try
		{
			pOps.publish();
		}
		catch (final McpException ex)
		{
			return Outcome.failed(ex.getMessage());
		}
		return Outcome.deleted(removed + " distribution trigger(s) removed from " + pTablePaths.size() + " table(s) of " + pModelName + (absent > 0 ? " (" + absent + " already absent)" : "")
			+ ", the model republished");
	}

	private static String join(final List<String> pParts, final String pSeparator)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String part : pParts)
		{
			sb.append(sb.length() == 0 ? "" : pSeparator).append(part);
		}
		return sb.toString();
	}

	/**
	 * Lot M18, second round (measured live 2026-10-18 15:56, projects E2EMASTER20261018134344 / ...134921): once the business-object ROW is gone, the members' configurations
	 * delete (rows 142, 144 deleted by {@code DeleteMatchingConfigProcessor}) but the MAIN table's does not - no processor line for it at all, the table configuration rows 143 and 146
	 * survived and the job ended DONE: the tool answered the business-object refusal, which {@link #deleteMatchingConfig} reads as {@link #RESULT_KEPT_BUSINESS_OBJECT}, not a
	 * failure. Cause, read by {@code javap -c} on {@code MameConfigWriter.rejectIfBusinessObjectTable} (6.3.0.5-RC3): the tool refuses a table when {@code SchemaNode.
	 * getBusinessObjectModel() != null} - the PUBLISHED DATA MODEL still declares a business object whose main table it is - OR when its stored configuration still names a business
	 * object. The first condition holds only for the MAIN table of the object and does not depend on the add-on's rows, so no delay, re-read or retry can help: the declaration
	 * must be removed from the model ({@code data-model} {@code delete_business_object}) and the model republished ({@code publish_data_model}) first. This does exactly that, ONCE,
	 * when - and only when - the first answer is that refusal, then deletes again. An answer that is not the refusal, or a table the model declares no object for, is returned as it came.
	 */
	static Outcome deleteMatchingConfigUndeclaringBusinessObject(final Session pSession, final Adaptation pFactory, final String pTablePath, final String pMasterModelName)
		throws OperationException, McpException
	{
		final Outcome first = GeneratedObjectDeleter.deleteMatchingConfig(pSession, pFactory, pTablePath, pMasterModelName);
		if (!GeneratedObjectDeleter.RESULT_KEPT_BUSINESS_OBJECT.equals(first.result))
		{
			return first;
		}
		final Outcome undeclared = GeneratedObjectDeleter.undeclareBusinessObject(pSession, pFactory, pMasterModelName, pTablePath);
		if (!"DELETED".equals(undeclared.result))
		{
			// "SKIPPED_ABSENT": the model declares no business object rooted on this table, so the refusal has another cause: the first answer stands
			return "FAILED".equals(undeclared.result) ? Outcome.failed("delete_matching_config on " + pTablePath + " answered the business-object refusal and the model's business object could not be "
				+ "removed: " + undeclared.message).withAnswer(first.answer) : first;
		}
		final Outcome second = GeneratedObjectDeleter.deleteMatchingConfig(pSession, pFactory, pTablePath, pMasterModelName);
		final String trail = " [first answer: " + first.answer + "; " + undeclared.message + "]";
		return new Outcome(second.result, (second.message == null ? "" : second.message) + trail, second.code, second.answer);
	}

	/**
	 * Removes from the published master model the business object whose main table is {@code pMainTablePath} and republishes the model: {@code execute_query get_data_model_business_objects}
	 * finds its name, {@code execute_operation delete_business_object}, {@code publish_data_model}. Already absent when the model declares none for that table (or the query could not say).
	 * A business object the model no longer declares is useless once the matching configurations are being deleted; a later master generation declares it again (idempotent).
	 */
	static Outcome undeclareBusinessObject(final Session pSession, final Adaptation pFactory, final String pModelName, final String pMainTablePath) throws OperationException, McpException
	{
		final McpClient client = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
		final ObjectNode queryArgs = GeneratedObjectDeleter.MAPPER.createObjectNode();
		queryArgs.put("data_model_name", pModelName);
		queryArgs.put("operation", "get_data_model_business_objects");
		final McpClient.ToolResult query = client.callTool("execute_query", queryArgs);
		final String name = query.error ? null : BusinessObjectPlan.readExisting(query.json, null, pMainTablePath).businessObjectName;
		if (name == null)
		{
			return Outcome.absent();
		}
		final ObjectNode operationArgs = GeneratedObjectDeleter.MAPPER.createObjectNode();
		operationArgs.put("name", name);
		final ObjectNode callArgs = GeneratedObjectDeleter.MAPPER.createObjectNode();
		callArgs.put("data_model_name", pModelName);
		callArgs.put("operation", "delete_business_object");
		callArgs.put("arguments_json", operationArgs.toString());
		final McpClient.ToolResult deletion = client.callTool("execute_operation", callArgs);
		final String refusal = deletion.error ? GeneratedObjectDeleter.shortText(deletion.text) : BusinessObjectPlan.failureOf(deletion.json);
		if (refusal != null)
		{
			return Outcome.failed("delete_business_object " + name + ": " + refusal);
		}
		final ObjectNode publishArgs = GeneratedObjectDeleter.MAPPER.createObjectNode();
		publishArgs.put("data_model_name", pModelName);
		final McpClient.ToolResult publish = client.callTool("publish_data_model", publishArgs);
		if (publish.error || !PublishDecision.published(publish.json))
		{
			return Outcome.failed("publish_data_model after delete_business_object " + name + ": " + GeneratedObjectDeleter.shortText(publish.text));
		}
		return Outcome.deleted("business object " + name + " removed from the model " + pModelName + ", which was republished");
	}

	/**
	 * Lot M18 (2026-10-18): the configuration of a Match and Merge business object of the master model, removed with the documented procedure in the add-on's own
	 * dataset ({@link MameConfigRows}): every {@code TableConfiguration} linked to it is unlinked ({@code FKBusinessObject} = null), then its
	 * {@code BusinessObjectConfiguration} row is deleted. The member tables are then plain tables, and the {@code MATCHING_CONFIG} items that follow in the plan
	 * ({@link #deleteMatchingConfig}) can delete their configuration, their hidden merge policy and their metadataset - which needs the master model, so this runs
	 * before any dataspace or model deletion. No MCP call: the rows are the add-on's, read and written with the Java API and the caller's session.
	 * Already absent when the add-on has no dataset or holds no business object for {@code pPublication} / {@code pMainTablePath}.
	 */
	static Outcome deleteBusinessObjectConfig(final Session pSession, final Adaptation pFactory, final String pPublication, final String pMainTablePath) throws OperationException
	{
		final Adaptation dataset = MameConfigRows.dataset(pFactory.getHome().getRepository());
		if (dataset == null)
		{
			return Outcome.absent();
		}
		final String urn = MatchingConfigRows.urnOf(pPublication);
		int deleted = 0;
		int unlinked = 0;
		final StringBuilder ids = new StringBuilder();
		for (final MatchingConfigRows.BusinessObject row : MameConfigRows.businessObjects(dataset))
		{
			if (!urn.equals(row.dataModel) || !pMainTablePath.equals(row.mainTable))
			{
				continue;
			}
			final int n = MameConfigRows.unlinkAndDelete(pSession, dataset, row.id);
			if (n >= 0)
			{
				deleted++;
				unlinked += n;
				ids.append(ids.length() == 0 ? "" : ", ").append(row.id);
			}
		}
		if (deleted == 0)
		{
			return Outcome.absent();
		}
		return Outcome.deleted("business object configuration " + ids + " deleted after unlinking " + unlinked + " table configuration(s) from it; its member tables are plain tables now");
	}

	/**
	 * Is this the add-on's refusal to delete the configuration of a table that belongs to a business object? Exactly the phrase "is part of a business object"
	 * (case-insensitive) - measured 2026-10-12: {@code "Table /root/Personne is part of a business object; use the business-object tools instead"}; nothing broader
	 * (an unrelated refusal that merely mentions business objects must stay a failure).
	 */
	static boolean isBusinessObjectMemberRefusal(final String pText)
	{
		return pText != null && pText.toLowerCase(java.util.Locale.ROOT).contains("is part of a business object");
	}

	/** English sentence of {@link #KEPT_BUSINESS_OBJECT_CODE} (the step detail; the bundle has the same in EN/FR). */
	static String keptBusinessObjectSentence(final String pTablePath)
	{
		return "The matching configuration of " + pTablePath + " stays in place: the Match and Merge tools offer no deletion for a business-object member; the metadatasets go with "
			+ "the master dataspace, the configuration rows must be removed in the add-on's administration screen.";
	}

	/**
	 * The plan's note when configurations were kept AND the master model or its dataspace is deleted anyway: the rows of the add-on's own dataset (Bebx-addon-mame /
	 * ebx-addon-mame-configuration, table /root/TableConfiguration, rows whose dataModel is {@code urn:ebx:publication:<model>}) stay orphaned. This lot only reports it - nothing
	 * is written into the add-on's dataset. Null when there is nothing to say.
	 */
	static String orphanedConfigurationNote(final int pKeptCount, final boolean pModelOrDataspaceDeleted, final String pMasterModelName)
	{
		if (pKeptCount <= 0 || !pModelOrDataspaceDeleted)
		{
			return null;
		}
		return pKeptCount + " matching configuration(s) of business-object members were kept and the master model " + pMasterModelName + " (or its dataspace) is deleted: their rows are left orphaned in the "
			+ "add-on's dataset Bebx-addon-mame/ebx-addon-mame-configuration, table /root/TableConfiguration (filter on dataModel = urn:ebx:publication:" + pMasterModelName
			+ "); remove them in the add-on's administration screen. Nothing was written into that dataset.";
	}

	/**
	 * {@code connectors_mapping_delete}. Already absent only on the Connectors server's own sentence for a mapping whose
	 * primary key it just failed to find - {@code {"error": "no mapping <id>"}} ({@code ConfigTransfer.deleteMapping},
	 * read in the sibling module's source), which is itself a positive answer (it is produced only by a primary-key lookup
	 * that returned nothing), thrown or returned. Every other error - "mapping id is required", a refused permission, a
	 * failed transaction, a text that merely contains "unknown" - is a failure: reading it as "gone" would let the
	 * deletion remove the project's rows while the flow still exists.
	 */
	static Outcome deleteMapping(final Session pSession, final Adaptation pFactory, final String pMappingId) throws OperationException, McpException
	{
		final McpClient client = McpServers.connectors(pSession, pFactory);
		final ObjectNode args = GeneratedObjectDeleter.MAPPER.createObjectNode();
		args.put("mapping", pMappingId);
		final McpClient.ToolResult result;
		try
		{
			result = client.callTool("connectors_mapping_delete", args);
		}
		catch (final McpException ex)
		{
			return GeneratedObjectDeleter.fromThrown(ex, GeneratedObjectDeleter.MAPPING_ABSENT_MARKER);
		}
		if (!result.error)
		{
			return Outcome.deleted(null);
		}
		final String text = GeneratedObjectDeleter.errorText(result);
		return GeneratedObjectDeleter.mappingAbsentAnswer(text) ? Outcome.absent() : Outcome.failed(text);
	}

	/** {@code Repository.closeHome}/{@code deleteHome}, called directly with the session, outside any {@code Procedure}. */
	static Outcome deleteDataspace(final Session pSession, final Adaptation pFactory, final String pDataspaceKey)
	{
		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(pDataspaceKey));
		if (home == null)
		{
			return Outcome.absent();
		}
		try
		{
			if (home.isOpen())
			{
				repository.closeHome(home, pSession);
			}
			repository.deleteHome(home, pSession);
			return Outcome.deleted(null);
		}
		catch (final OperationException ex)
		{
			return Outcome.failed(ex.getMessage());
		}
	}

	/**
	 * {@code execute_operation(operation="delete_data_model", ...)} on the {@code data-model} server. On
	 * 6.3.0-rc2 a model that no longer exists is NOT a tool result flagged in error: the server raises a JSON-RPC
	 * error, so the client THROWS ({@code "tools/call: Tool invocation failed: Data model not found:
	 * 'PersVol4_Master'. Call list_data_models ..."}, measured 2026-09-29 on project PersVol4, five models).
	 * That thrown text is classified here too - see {@link #fromThrown}.
	 */
	static Outcome deleteDataModel(final Session pSession, final Adaptation pFactory, final String pModelName) throws OperationException, McpException
	{
		final McpClient client = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
		final ObjectNode args = GeneratedObjectDeleter.MAPPER.createObjectNode();
		args.put("data_model_name", pModelName);
		args.put("operation", "delete_data_model");
		final ObjectNode operationArgs = GeneratedObjectDeleter.MAPPER.createObjectNode();
		operationArgs.put("confirm", "true");
		args.put("arguments_json", operationArgs.toString());
		final McpClient.ToolResult result;
		try
		{
			result = client.callTool("execute_operation", args);
		}
		catch (final McpException ex)
		{
			return GeneratedObjectDeleter.fromThrown(ex, GeneratedObjectDeleter.DATA_MODEL_ABSENT_MARKER);
		}
		if (!result.error)
		{
			return Outcome.deleted(null);
		}
		final String text = GeneratedObjectDeleter.errorText(result);
		return GeneratedObjectDeleter.thrownMeansAbsent(text, GeneratedObjectDeleter.DATA_MODEL_ABSENT_MARKER) ? Outcome.absent() : Outcome.failed(text);
	}

	/**
	 * Lot C1-D: {@code workflow_publication_unpublish} with {@code terminateActiveInstances=false} (the tool then REFUSES when instances still run, and nothing
	 * is unpublished: a failure the caller reports). "Already absent" is decided from the publication list, not from the wording of the refusal (the tool's
	 * wording for an unknown publication was never captured): a failed unpublish whose name is not in {@code workflow_publication_list} is absent.
	 */
	static Outcome deleteWorkflowPublication(final Session pSession, final Adaptation pFactory, final String pPublicationName) throws OperationException, McpException
	{
		final McpClient client = McpServers.client(pSession, pFactory, McpServers.WORKFLOW_MODELING);
		final ObjectNode args = GeneratedObjectDeleter.MAPPER.createObjectNode();
		args.put("publicationName", pPublicationName);
		args.put("terminateActiveInstances", false);
		McpClient.ToolResult result = null;
		McpException thrown = null;
		try
		{
			result = client.callTool("workflow_publication_unpublish", args);
		}
		catch (final McpException ex)
		{
			thrown = ex;
		}
		if (result != null && !WorkflowModelOps.failed(result))
		{
			return Outcome.deleted(null);
		}
		if (!WorkflowModelOps.publicationExists(client, pPublicationName))
		{
			return Outcome.absent();
		}
		return Outcome.failed(thrown != null ? thrown.getMessage() : GeneratedObjectDeleter.errorText(result));
	}

	/** Lot C1-D: {@code workflow_model_delete}; absent decided from {@code workflow_model_list}, as {@link #deleteWorkflowPublication}. */
	static Outcome deleteWorkflowModel(final Session pSession, final Adaptation pFactory, final String pModelName) throws OperationException, McpException
	{
		final McpClient client = McpServers.client(pSession, pFactory, McpServers.WORKFLOW_MODELING);
		final ObjectNode args = GeneratedObjectDeleter.MAPPER.createObjectNode();
		args.put("modelName", pModelName);
		McpClient.ToolResult result = null;
		McpException thrown = null;
		try
		{
			result = client.callTool("workflow_model_delete", args);
		}
		catch (final McpException ex)
		{
			thrown = ex;
		}
		if (result != null && !WorkflowModelOps.failed(result))
		{
			return Outcome.deleted(null);
		}
		if (!WorkflowModelOps.modelExists(client, pModelName))
		{
			return Outcome.absent();
		}
		return Outcome.failed(thrown != null ? thrown.getMessage() : GeneratedObjectDeleter.errorText(result));
	}

	/** Lot C1-D: the {@code FormLayout} rows of a publication in the Layout Designer's dataset. Absent when the Designer or its dataset is not there. */
	static Outcome deleteFormLayouts(final Session pSession, final Adaptation pFactory, final String pPublicationName) throws OperationException
	{
		final Adaptation designer = LayoutOps.designerDataset(pFactory.getHome().getRepository());
		if (designer == null)
		{
			return Outcome.absent();
		}
		final int n = LayoutOps.deleteFor(pSession, designer, pPublicationName);
		return n == 0 ? Outcome.absent() : Outcome.deleted(n + " FormLayout row(s) deleted");
	}

	/**
	 * Classifies an exception thrown by an MCP call: already absent only when its message carries {@code
	 * pAbsentMarker}, the EXACT wording measured for that tool (lower case). Deliberately narrow: a thrown error also covers "MCP server not found", "Unknown tool", a refused permission or
	 * a transport failure, and reading any of those as "the object is gone" would delete the project's own rows
	 * while its generated objects are still there.
	 */
	static Outcome fromThrown(final Exception pException, final String pAbsentMarker)
	{
		final String message = pException.getMessage() == null ? pException.getClass().getSimpleName() : pException.getMessage();
		return GeneratedObjectDeleter.thrownMeansAbsent(message, pAbsentMarker) ? Outcome.absent() : Outcome.failed(message);
	}

	/** Package-visible for its test only. */
	static boolean thrownMeansAbsent(final String pMessage, final String pAbsentMarker)
	{
		return ForeignWording.thrownMeansAbsent(pMessage, pAbsentMarker);
	}

	/**
	 * The Connectors server's sentence for a mapping it cannot find, lower case: {@code "no mapping <id>"} from {@code
	 * connectors_mapping_delete} ({@code ConfigTransfer.deleteMapping}) and {@code "No mapping '<id>'"} from its read
	 * tools ({@code ConnectorsTools}).
	 */
	static final String MAPPING_ABSENT_MARKER = ForeignWording.CONNECTORS_NO_MAPPING;
	/** The data-model server's sentence for a model it cannot find ({@code "Data model not found: '<name>'. Call list_data_models ..."}, measured 2026-09-29), lower case. */
	static final String DATA_MODEL_ABSENT_MARKER = ForeignWording.DATA_MODEL_NOT_FOUND;

	/**
	 * Whether the {@code error} text of a {@code connectors_mapping_delete} answer is the "no mapping" sentence: it must BEGIN
	 * with it (the field holds the whole message), not merely contain a similar wording. Package-visible for its test.
	 */
	static boolean mappingAbsentAnswer(final String pText)
	{
		return ForeignWording.startsWithNoMapping(pText);
	}

	private static String errorText(final McpClient.ToolResult pResult)
	{
		if (pResult.json != null && pResult.json.hasNonNull("error"))
		{
			return pResult.json.path("error").asText();
		}
		return GeneratedObjectDeleter.shortText(pResult.text);
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
