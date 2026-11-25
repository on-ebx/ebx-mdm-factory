package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.distribution.ScriptValidationErrors;
import com.orchestranetworks.service.OperationException;

/**
 * The generic steps of declaring, authoring and removing an EBX-Script TABLE trigger through the {@code data-model} and {@code scripting} MCP servers, for the
 * automatic-intake installer. The mechanism is the one {@link DistributionTriggerInstaller} proved live (its class Javadoc records every trap): declare with
 * {@code execute_operation set_script_trigger}; a script that was never published has no revision history, so the model is published ONCE after declaring
 * when something was newly declared, before {@code update_script}; the script lives at {@code data-model/<Model>/root/<Table>/@trigger/<declared name>}
 * (looked up by {@code scriptTypeId} when the guess fails); a final {@code publish_data_model} makes the bodies live. Removal is {@code unset_element_property(property="triggers",
 * item=<name>)} - NEVER without {@code item}, which would drop the Java, derivation and distribution triggers of the table too.
 * <p>
 * These are the same calls as {@link DistributionTriggerInstaller}'s private methods, generalised on the script name; that class was deliberately left untouched (no
 * live run is possible in this lot to prove a refactor of it behaviour-neutral). Nothing here was run live yet.
 */
final class ScriptTriggerOps
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ScriptTriggerOps()
	{
	}

	/** {@code scriptTypeId} {@code list_scripts} reports for a table trigger of the given type (measured live in the distribution lot: {@code triggerAfterCreate}). */
	static String scriptTypeId(final String pType)
	{
		return "trigger" + Character.toUpperCase(pType.charAt(0)) + pType.substring(1);
	}

	static String idePath(final String pModelName, final String pTableName, final String pScriptName)
	{
		return "data-model/" + pModelName + "/root/" + pTableName + "/@trigger/" + pScriptName;
	}

	/** The field names of a table as the data-model server describes them ({@code describe_table}; {@code data.children[].name}, {@code data.fields[].name} also read). Empty when the call fails: the caller falls back. */
	static List<String> fieldNames(final McpClient pDataModelClient, final String pModelName, final String pTablePath)
	{
		final List<String> names = new ArrayList<String>();
		try
		{
			final ObjectNode args = ScriptTriggerOps.MAPPER.createObjectNode();
			args.put("data_model_name", pModelName);
			args.put("table_path", pTablePath);
			final McpClient.ToolResult result = pDataModelClient.callTool("describe_table", args);
			if (!result.error && result.json != null)
			{
				for (final String key : new String[] { "children", "fields" })
				{
					for (final JsonNode child : result.json.path("data").path(key))
					{
						final String name = child.path("name").asText("");
						if (!name.isEmpty() && !names.contains(name))
						{
							names.add(name);
						}
					}
				}
			}
		}
		catch (final McpException ex)
		{
			return new ArrayList<String>();
		}
		return names;
	}

	/** True when {@code get_script} answers for the path. Never throws: any failure reads as "not there". */
	static boolean scriptExists(final McpClient pScriptingClient, final String pScriptIdePath)
	{
		try
		{
			final ObjectNode args = ScriptTriggerOps.MAPPER.createObjectNode();
			args.put("scriptIdePath", pScriptIdePath);
			final McpClient.ToolResult r = pScriptingClient.callTool("get_script", args);
			if (r.error || r.json == null)
				return false;
			// the scripting server answers a missing script as a SUCCESS carrying a code (SCRIPT_NOT_FOUND), not as an error:
			// read the code, and require the skeleton's revisionId (first real run of lot C1, 2026-10-12)
			if (r.json.has("code") && !r.json.path("code").isNull() && !r.json.path("code").asText().isEmpty())
				return false;
			if (r.json.has("success") && !r.json.path("success").asBoolean(true))
				return false;
			return r.json.path("data").hasNonNull("revisionId");
		}
		catch (final McpException ex)
		{
			return false;
		}
	}

	/** Declares the trigger ({@code set_script_trigger}, scope table); returns true when it did not exist before (so a middle publish is needed). */
	static boolean declare(final McpClient pDataModelClient, final McpClient pScriptingClient, final String pModelName, final String pTablePath, final String pTableName,
		final String pType, final String pScriptName, final ObjectNode pDetail) throws McpException, OperationException
	{
		final String guessed = ScriptTriggerOps.idePath(pModelName, pTableName, pScriptName);
		final boolean existedBefore = ScriptTriggerOps.scriptExists(pScriptingClient, guessed);
		final ObjectNode operationArgs = ScriptTriggerOps.MAPPER.createObjectNode();
		operationArgs.put("element_path", pTablePath);
		operationArgs.put("name", pScriptName);
		operationArgs.put("type", pType);
		operationArgs.put("scope", "table");
		final ObjectNode args = ScriptTriggerOps.MAPPER.createObjectNode();
		args.put("data_model_name", pModelName);
		args.put("operation", "set_script_trigger");
		args.put("arguments_json", operationArgs.toString());
		final McpClient.ToolResult result = pDataModelClient.callTool("execute_operation", args);
		pDetail.put("declared", !result.error);
		if (result.error)
		{
			throw EngineRefusal.coded("external.triggerDeclare", "set_script_trigger refused " + pType + " on " + pTablePath + ": " + DistributionConfigurator.errorText(result), pType, pTablePath);
		}
		if (result.json != null && !result.json.path("publishSafe").asBoolean(true))
		{
			throw EngineRefusal.coded("conflict.noScriptComponent", pModelName + " lacks EBX's built-in script component: " + pType + " on " + pTablePath + " would be dropped at publish", pModelName);
		}
		pDetail.put("scriptName", pScriptName);
		pDetail.put("newlyDeclared", !existedBefore);
		return !existedBefore;
	}

	static void publish(final McpClient pDataModelClient, final String pModelName, final String pWhy) throws McpException, OperationException
	{
		final ObjectNode args = ScriptTriggerOps.MAPPER.createObjectNode();
		args.put("data_model_name", pModelName);
		final McpClient.ToolResult result = pDataModelClient.callTool("publish_data_model", args);
		if (result.error)
		{
			throw EngineRefusal.coded("external.publishModel", "publish_data_model refused " + pWhy + " on " + pModelName + ": " + DistributionConfigurator.errorText(result), pModelName);
		}
	}

	/** Reads the declared script, writes {@code pSource} over it with {@code update_script} and checks the embedded validation. */
	static void author(final McpClient pScriptingClient, final String pModelName, final String pTablePath, final String pTableName, final String pType,
		final String pScriptName, final String pSource, final ObjectNode pDetail) throws McpException, OperationException
	{
		final String scriptIdePath = ScriptTriggerOps.resolveIdePath(pScriptingClient, pModelName, pTableName, pType, pScriptName, pDetail);
		final ObjectNode getArgs = ScriptTriggerOps.MAPPER.createObjectNode();
		getArgs.put("scriptIdePath", scriptIdePath);
		final McpClient.ToolResult skeleton = pScriptingClient.callTool("get_script", getArgs);
		if (skeleton.error)
		{
			throw EngineRefusal.coded("external.triggerRead", "could not read the declared trigger script at " + scriptIdePath + ": " + DistributionConfigurator.errorText(skeleton), scriptIdePath);
		}
		final String revisionId = skeleton.json == null ? null : skeleton.json.path("data").path("revisionId").asText(null);
		final ObjectNode updateArgs = ScriptTriggerOps.MAPPER.createObjectNode();
		updateArgs.put("scriptIdePath", scriptIdePath);
		updateArgs.put("source", pSource);
		if (revisionId != null)
		{
			updateArgs.put("revisionId", revisionId);
		}
		updateArgs.put("confirmReplaceMetadata", true);
		final McpClient.ToolResult update = pScriptingClient.callTool("update_script", updateArgs);
		final boolean updateSuccess = update.json != null && update.json.path("success").asBoolean(false);
		final boolean validationSuccess = update.json != null && update.json.path("data").path("validation").path("isSuccess").asBoolean(false);
		pDetail.put("authored", updateSuccess && validationSuccess);
		if (!updateSuccess || !validationSuccess)
		{
			final String validationText = ScriptValidationErrors.format(update.json);
			throw EngineRefusal.coded("invalid.triggerBody", "update_script refused the body of " + pType + " on " + pTablePath + " (" + scriptIdePath + "): "
				+ (validationText.isEmpty() ? DistributionConfigurator.errorText(update) : validationText), pType, pTablePath);
		}
	}

	private static String resolveIdePath(final McpClient pScriptingClient, final String pModelName, final String pTableName, final String pType, final String pScriptName,
		final ObjectNode pDetail)
	{
		final String guessed = ScriptTriggerOps.idePath(pModelName, pTableName, pScriptName);
		try
		{
			if (ScriptTriggerOps.scriptExists(pScriptingClient, guessed))
			{
				pDetail.put("scriptIdePath", guessed);
				return guessed;
			}
			final ObjectNode listArgs = ScriptTriggerOps.MAPPER.createObjectNode();
			listArgs.put("folderIdePath", "data-model/" + pModelName + "/root/" + pTableName + "/@trigger");
			listArgs.put("limit", 50);
			final McpClient.ToolResult list = pScriptingClient.callTool("list_scripts", listArgs);
			if (!list.error && list.json != null)
			{
				final String typeId = ScriptTriggerOps.scriptTypeId(pType);
				for (final JsonNode item : list.json.path("data").path("items"))
				{
					final String idePath = item.path("idePath").asText(null);
					if (idePath != null && (typeId.equals(item.path("scriptTypeId").asText("")) || pScriptName.equals(item.path("name").asText(""))))
					{
						pDetail.put("scriptIdePath", idePath);
						pDetail.put("scriptIdePathResolvedByListing", true);
						return idePath;
					}
				}
			}
		}
		catch (final McpException ex)
		{
			pDetail.put("scriptIdePathResolutionError", ex.getMessage());
		}
		pDetail.put("scriptIdePath", guessed);
		return guessed;
	}

	/** Removes ONE named trigger of a table ({@code unset_element_property}, {@code item} always given). Returns null when removed, else the reason it was not (already gone, model gone): the caller reports it, never fails on it. */
	static String remove(final McpClient pDataModelClient, final String pModelName, final String pTablePath, final String pScriptName)
	{
		try
		{
			final ObjectNode args = ScriptTriggerOps.MAPPER.createObjectNode();
			args.put("data_model_name", pModelName);
			args.put("element_path", pTablePath);
			args.put("property", "triggers");
			args.put("item", pScriptName);
			final McpClient.ToolResult result = pDataModelClient.callTool("unset_element_property", args);
			return result.error ? DistributionConfigurator.errorText(result) : null;
		}
		catch (final McpException ex)
		{
			return ex.getMessage();
		}
	}
}
