package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.accel.DomainPlan;
import com.on.ebx.factory.distribution.ScriptValidationErrors;
import com.orchestranetworks.service.OperationException;

/**
 * Declares and authors the scripts of a generated domain (lot C1-D, step ACCEL_SCRIPTS), the three-call recipe of {@code docs/ACCELERATORS.md}
 * section 13: {@code set_script_trigger} / {@code set_script_constraint} on the {@code data-model} server, {@code get_script} +
 * {@code update_script} (with {@code confirmReplaceMetadata}) on the {@code scripting} server, and ONE {@code publish_data_model} by the caller at
 * the end. A body that does not validate stops the step (the model is not published with it); a DRAFT script is authored too but a failure of its
 * stub is only a note. If the freshly declared script cannot be read yet, the model is published once and the read retried (the distribution lot
 * needed that on one build, {@link ScriptTriggerOps}).
 */
final class DomainScripts
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DomainScripts()
	{
	}

	/** Declares then authors one script; returns true when the authoring succeeded. */
	static boolean declareAndAuthor(final McpClient pDataModel, final McpClient pScripting, final String pModel, final DomainPlan.ScriptItem pItem, final ObjectNode pDetail)
		throws McpException, OperationException
	{
		if (ScriptTriggerOps.scriptExists(pScripting, pItem.idePath))
		{
			pDetail.put("declared", "already");
		}
		else
		{
			DomainScripts.declare(pDataModel, pModel, pItem, pDetail);
		}
		String idePath = DomainScripts.resolve(pScripting, pItem);
		if (idePath == null)
		{
			ScriptTriggerOps.publish(pDataModel, pModel, "the declaration of " + pItem.name);
			pDetail.put("middlePublish", true);
			idePath = DomainScripts.resolve(pScripting, pItem);
		}
		if (idePath == null)
		{
			throw EngineRefusal.coded("external.scriptNotFound", "The declared script " + pItem.name + " on " + pItem.elementPath + " cannot be found in the scripting IDE (looked for " + pItem.idePath + ").",
				pItem.name, pItem.elementPath);
		}
		pDetail.put("idePath", idePath);
		final ObjectNode getArgs = DomainScripts.MAPPER.createObjectNode();
		getArgs.put("scriptIdePath", idePath);
		final McpClient.ToolResult skeleton = pScripting.callTool("get_script", getArgs);
		final String revisionId = skeleton.json == null ? null : skeleton.json.path("data").path("revisionId").asText(null);
		final ObjectNode update = DomainScripts.MAPPER.createObjectNode();
		update.put("scriptIdePath", idePath);
		update.put("source", pItem.body);
		if (revisionId != null)
		{
			update.put("revisionId", revisionId);
		}
		update.put("confirmReplaceMetadata", true);
		final McpClient.ToolResult result = pScripting.callTool("update_script", update);
		final boolean ok = result.json != null && result.json.path("success").asBoolean(false) && result.json.path("data").path("validation").path("isSuccess").asBoolean(false);
		pDetail.put("authored", ok);
		if (!ok)
		{
			final String why = ScriptValidationErrorsBridge.text(result);
			if (pItem.draft)
			{
				pDetail.put("draftNote", "The draft stub did not validate: " + why);
				return false;
			}
			throw EngineRefusal.coded("invalid.scriptBody", "update_script refused the body of " + pItem.name + " on " + pItem.elementPath + " (" + idePath + "): " + why, pItem.name, pItem.elementPath);
		}
		return true;
	}

	private static void declare(final McpClient pDataModel, final String pModel, final DomainPlan.ScriptItem pItem, final ObjectNode pDetail) throws McpException, OperationException
	{
		final ObjectNode operationArgs = DomainScripts.MAPPER.createObjectNode();
		operationArgs.put("element_path", pItem.elementPath);
		operationArgs.put("name", pItem.name);
		operationArgs.put("type", pItem.type);
		operationArgs.put("scope", pItem.scope);
		final ObjectNode args = DomainScripts.MAPPER.createObjectNode();
		args.put("data_model_name", pModel);
		args.put("operation", "trigger".equals(pItem.kind) ? "set_script_trigger" : "set_script_constraint");
		args.put("arguments_json", operationArgs.toString());
		final McpClient.ToolResult result = pDataModel.callTool("execute_operation", args);
		pDetail.put("declared", !WorkflowModelOps.failed(result));
		if (WorkflowModelOps.failed(result))
		{
			throw EngineRefusal.coded("external.scriptDeclare", args.path("operation").asText() + " refused " + pItem.name + " on " + pItem.elementPath + ": "
				+ DistributionConfigurator.errorText(result), pItem.name, pItem.elementPath);
		}
		final JsonNode json = result.json;
		if (json != null && json.has("publishSafe") && !json.path("publishSafe").asBoolean(true))
		{
			throw EngineRefusal.coded("conflict.noScriptComponent", pModel + " lacks EBX's built-in script component: " + pItem.name + " would be dropped at publish", pModel);
		}
	}

	/** The IDE path of the declared script: the computed one when {@code get_script} answers, else a listing of its folder; null when neither finds it. */
	private static String resolve(final McpClient pScripting, final DomainPlan.ScriptItem pItem) throws McpException
	{
		if (ScriptTriggerOps.scriptExists(pScripting, pItem.idePath))
		{
			return pItem.idePath;
		}
		final String folder = pItem.idePath.substring(0, pItem.idePath.lastIndexOf('/'));
		final ObjectNode list = DomainScripts.MAPPER.createObjectNode();
		list.put("folderIdePath", folder);
		list.put("limit", 50);
		final McpClient.ToolResult listed = pScripting.callTool("list_scripts", list);
		if (!listed.error && listed.json != null)
		{
			for (final JsonNode item : listed.json.path("data").path("items"))
			{
				final String idePath = item.path("idePath").asText(null);
				if (idePath != null && pItem.name.equals(item.path("name").asText("")))
				{
					return idePath;
				}
			}
		}
		return null;
	}

	/** Shares {@link ScriptValidationErrors}' formatting without exposing its package-private surface twice. */
	static final class ScriptValidationErrorsBridge
	{
		private ScriptValidationErrorsBridge()
		{
		}

		static String text(final McpClient.ToolResult pResult)
		{
			final String formatted = ScriptValidationErrors.format(pResult.json);
			return formatted.isEmpty() ? DistributionConfigurator.errorText(pResult) : formatted;
		}
	}
}
