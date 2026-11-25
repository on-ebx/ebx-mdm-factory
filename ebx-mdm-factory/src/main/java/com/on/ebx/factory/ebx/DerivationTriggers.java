package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.capability.DerivationBodyBuilder;
import com.on.ebx.factory.landing.ErrorText;
import com.orchestranetworks.service.OperationException;

/**
 * The MCP half of lot T1 (2026-10-04): declaring, authoring, verifying and removing the two table triggers ({@code beforeCreate} and
 * {@code beforeModify}) that keep a stored script derivation up to date. Every method takes the MODEL NAME and the TABLE (path or entity name)
 * as parameters and knows nothing of the staging zone, so a later lot can reuse it for the master model unchanged. Used by {@link
 * DerivationApplier}; the bodies come from {@link DerivationBodyBuilder}.
 * <p>
 * The measured facts it relies on (live on 6.3.0-rc2, 2026-10-04, {@code Vol100k_Staging}, docs/SCRIPT-DERIVATION.md sec 10):
 * <ul>
 * <li>declare: data-model {@code execute_operation(set_script_trigger, {element_path: <TABLE path>, name, type: beforeCreate|beforeModify, scope: table})}
 * - {@code type} is case-sensitive, {@code scope} required; additive, the same name replaces that entry in place;</li>
 * <li>the body is a script at {@code data-model/<Model>/root/<Table>/@trigger/<Name>}; {@code get_script} gives its {@code revisionId}, {@code
 * update_script} with {@code confirmReplaceMetadata=true} authors it (trigger script types have {@code SCRIPT_LIBRARY_ACCESS});</li>
 * <li>{@code publish_data_model} alone publishes the triggers, the dataset built on the publication follows at once;</li>
 * <li>verify: data-model {@code execute_query(list_script_table_triggers)} answers {@code {"tableTriggers":[...]}};</li>
 * <li>remove: {@code unset_element_property(element_path=<table path>, property=triggers, item=<name>)} - WITHOUT {@code item} the whole trigger list
 * of the table is wiped, which is never done here.</li>
 * </ul>
 */
final class DerivationTriggers
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** {@code set_script_trigger}'s {@code type} for the create trigger (case-sensitive). */
	static final String TYPE_CREATE = "beforeCreate";
	/** {@code set_script_trigger}'s {@code type} for the modify trigger (case-sensitive). */
	static final String TYPE_MODIFY = "beforeModify";

	private DerivationTriggers()
	{
	}

	/** What a {@code list_script_table_triggers} answer says about one trigger. */
	enum State
	{
		/** The trigger is not in the answer. */
		MISSING,
		/** Listed, {@code authored:true}. */
		AUTHORED,
		/** Listed, {@code authored:false}. */
		NOT_AUTHORED,
		/** Listed, without an {@code authored} flag: not failed on. */
		UNKNOWN
	}

	// ------------------------------------------------------------------------------------------------------- declare

	/** Declares one trigger on the TABLE ({@code pTablePath}, e.g. {@code /root/Personne}); throws when EBX refuses. */
	static void declare(final McpClient pDataModelClient, final String pModelName, final String pTablePath, final String pTriggerName, final String pType,
		final ObjectNode pDetail, final String pDetailKey) throws McpException, OperationException
	{
		final ObjectNode operationArgs = DerivationTriggers.MAPPER.createObjectNode();
		operationArgs.put("element_path", pTablePath);
		operationArgs.put("name", pTriggerName);
		operationArgs.put("type", pType);
		operationArgs.put("scope", "table");
		final ObjectNode args = DerivationTriggers.MAPPER.createObjectNode();
		args.put("data_model_name", pModelName);
		args.put("operation", "set_script_trigger");
		args.put("arguments_json", operationArgs.toString());
		final McpClient.ToolResult result = pDataModelClient.callTool("execute_operation", args);
		pDetail.set(pDetailKey, DerivationApplier.toolDetail(args, result));
		if (result.error || (result.json != null && !result.json.path("success").asBoolean(true)))
		{
			throw EngineRefusal.coded("external.triggerDeclare", "set_script_trigger refused '" + pTriggerName + "' on " + pModelName + pTablePath + ": " + DerivationApplier.shortText(result.text), pTriggerName, pModelName + pTablePath);
		}
	}

	// -------------------------------------------------------------------------------------------------------- author

	/**
	 * Authors the body of one declared trigger: {@code get_script} for the revision, {@code update_script}, and a check of {@code success} and {@code
	 * validation.isSuccess}. {@code update_script} saves the draft even when validation fails (CLAUDE.md sec 3), so a failure is thrown here and the
	 * caller rolls back.
	 */
	static void author(final McpClient pScriptingClient, final String pIdePath, final String pSource, final ObjectNode pDetail, final String pDetailKey)
		throws McpException, OperationException
	{
		final ObjectNode getArgs = DerivationTriggers.MAPPER.createObjectNode();
		getArgs.put("scriptIdePath", pIdePath);
		final McpClient.ToolResult skeleton = pScriptingClient.callTool("get_script", getArgs);
		pDetail.set(pDetailKey + "Skeleton", DerivationApplier.toolDetail(getArgs, skeleton));
		final String revisionId = skeleton.json == null ? null : skeleton.json.path("data").path("revisionId").asText(null);
		if (revisionId == null)
		{
			throw EngineRefusal.coded("external.triggerRead", "could not read the declared trigger's revisionId at " + pIdePath + ": " + DerivationApplier.shortText(skeleton.text), pIdePath);
		}
		final ObjectNode updateArgs = DerivationTriggers.MAPPER.createObjectNode();
		updateArgs.put("scriptIdePath", pIdePath);
		updateArgs.put("source", pSource);
		updateArgs.put("revisionId", revisionId);
		updateArgs.put("confirmReplaceMetadata", true);
		final McpClient.ToolResult update = pScriptingClient.callTool("update_script", updateArgs);
		pDetail.set(pDetailKey, DerivationApplier.toolDetail(updateArgs, update));
		if (!DerivationTriggers.updateSucceeded(update.json))
		{
			throw EngineRefusal.coded("invalid.triggerScript", "the trigger body at " + pIdePath + " did not validate: " + DerivationApplier.shortText(update.text), pIdePath);
		}
	}

	/**
	 * The source of the script at {@code pIdePath} as the scripting server holds it now ({@code get_script(includeSource=true)}, {@code data.source}), or
	 * {@code null} when it cannot be read - kept for a re-apply that fails after having rewritten a body it must put back (lot T2, 2026-10-04). Never throws for a refusal.
	 */
	static String readSource(final McpClient pScriptingClient, final String pIdePath, final ObjectNode pDetail, final String pDetailKey) throws McpException
	{
		final ObjectNode args = DerivationTriggers.MAPPER.createObjectNode();
		args.put("scriptIdePath", pIdePath);
		args.put("includeSource", true);
		final McpClient.ToolResult result = pScriptingClient.callTool("get_script", args);
		pDetail.set(pDetailKey, DerivationApplier.toolDetail(args, result));
		if (result.error || result.json == null)
		{
			return null;
		}
		final String source = result.json.path("data").path("source").asText(null);
		return source == null || source.trim().isEmpty() ? null : source;
	}

	/**
	 * Best-effort write-back of a previous body ({@link #author} without the throw): {@code true} when it was authored and validated. A failure is
	 * recorded in the detail and returned as {@code false}, never thrown - it runs while another failure is being handled.
	 */
	static boolean restore(final McpClient pScriptingClient, final String pIdePath, final String pSource, final ObjectNode pDetail, final String pDetailKey)
	{
		try
		{
			DerivationTriggers.author(pScriptingClient, pIdePath, pSource, pDetail, pDetailKey);
			return true;
		}
		catch (final Exception ex)
		{
			pDetail.put(pDetailKey + "Error", ErrorText.of(ex));
			return false;
		}
	}

	/** {@code true} when an {@code update_script} answer says {@code success} and {@code data.validation.isSuccess}. */
	static boolean updateSucceeded(final JsonNode pUpdateJson)
	{
		return pUpdateJson != null && pUpdateJson.path("success").asBoolean(false) && pUpdateJson.path("data").path("validation").path("isSuccess").asBoolean(false);
	}

	// --------------------------------------------------------------------------------------------------------- verify

	/** Reads {@code list_script_table_triggers} of the model; the raw tool result (the caller records it and reads the states). */
	static McpClient.ToolResult listTriggers(final McpClient pDataModelClient, final String pModelName) throws McpException
	{
		final ObjectNode args = DerivationTriggers.MAPPER.createObjectNode();
		args.put("data_model_name", pModelName);
		args.put("operation", "list_script_table_triggers");
		return pDataModelClient.callTool("execute_query", args);
	}

	/** Reads {@code list_script_functions} of the model (a function-field declaration of an older apply). */
	static McpClient.ToolResult listFunctions(final McpClient pDataModelClient, final String pModelName) throws McpException
	{
		final ObjectNode args = DerivationTriggers.MAPPER.createObjectNode();
		args.put("data_model_name", pModelName);
		args.put("operation", "list_script_functions");
		return pDataModelClient.callTool("execute_query", args);
	}

	/**
	 * What a {@code list_script_table_triggers} answer says about the trigger {@code pTriggerName}. The exact shape was measured only as {@code
	 * {"tableTriggers":[...]}}, so the read is defensive: the entries are the array under a {@code tableTriggers} key found anywhere in the answer
	 * (else {@code data}, else the root when it is an array); an entry is the one whose NAME is a text value anywhere inside it; its {@code authored}
	 * flag, when there is one, decides, and a missing flag is {@link State#UNKNOWN} (never a failure).
	 */
	static State stateOf(final JsonNode pAnswer, final String pTriggerName)
	{
		return DerivationTriggers.stateOf(pAnswer, pTriggerName, null);
	}

	/**
	 * Same as {@link #stateOf(JsonNode, String)}, restricted to the TABLE {@code pTablePath} (lot T2, 2026-10-04): a model with two tables that each
	 * derive an attribute of the same name (the master model holds every promoted entity) carries two triggers of the same name, and the one of
	 * another table must not be taken for this one. An entry that names a table ({@code tablePath}, measured; {@code table}) must name this one; an
	 * entry that names none is accepted, as before. A {@code null} path asks for no restriction.
	 */
	static State stateOf(final JsonNode pAnswer, final String pTriggerName, final String pTablePath)
	{
		final JsonNode entries = DerivationTriggers.entries(pAnswer);
		if (entries == null)
		{
			return State.MISSING;
		}
		for (final JsonNode entry : entries)
		{
			if (!DerivationTriggers.holdsText(entry, pTriggerName) || !DerivationTriggers.onTable(entry, pTablePath))
			{
				continue;
			}
			final JsonNode authored = entry.path("authored");
			if (authored.isBoolean())
			{
				return authored.asBoolean() ? State.AUTHORED : State.NOT_AUTHORED;
			}
			return State.UNKNOWN;
		}
		return State.MISSING;
	}

	private static boolean onTable(final JsonNode pEntry, final String pTablePath)
	{
		if (pTablePath == null)
		{
			return true;
		}
		for (final String key : new String[] { "tablePath", "table" })
		{
			final JsonNode named = pEntry.path(key);
			if (named.isTextual())
			{
				return pTablePath.equals(named.asText());
			}
		}
		return true;
	}

	private static JsonNode entries(final JsonNode pAnswer)
	{
		if (pAnswer == null)
		{
			return null;
		}
		final JsonNode named = DerivationTriggers.findArray(pAnswer, "tableTriggers");
		if (named != null)
		{
			return named;
		}
		if (pAnswer.path("data").isArray())
		{
			return pAnswer.path("data");
		}
		return pAnswer.isArray() ? pAnswer : null;
	}

	private static JsonNode findArray(final JsonNode pNode, final String pKey)
	{
		if (pNode == null)
		{
			return null;
		}
		if (pNode.isObject())
		{
			final JsonNode direct = pNode.get(pKey);
			if (direct != null && direct.isArray())
			{
				return direct;
			}
			final java.util.Iterator<JsonNode> children = pNode.elements();
			while (children.hasNext())
			{
				final JsonNode found = DerivationTriggers.findArray(children.next(), pKey);
				if (found != null)
				{
					return found;
				}
			}
		}
		else if (pNode.isArray())
		{
			for (final JsonNode child : pNode)
			{
				final JsonNode found = DerivationTriggers.findArray(child, pKey);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static boolean holdsText(final JsonNode pNode, final String pText)
	{
		if (pNode == null)
		{
			return false;
		}
		if (pNode.isTextual())
		{
			return pText.equals(pNode.asText());
		}
		final java.util.Iterator<JsonNode> children = pNode.elements();
		while (children.hasNext())
		{
			if (DerivationTriggers.holdsText(children.next(), pText))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether {@code list_script_functions}' answer lists {@code pElementPath} as a declared function field, authored or not: the migration of an
	 * older apply (a computed function field) must un-declare it before the field can be a stored one. Read defensively like {@link
	 * DerivationApplier#isAuthored} - {@code data.functions[]} (or {@code data[]}), an entry's {@code fieldPath} / {@code elementPath}.
	 */
	static boolean isFunctionDeclared(final JsonNode pAnswer, final String pElementPath)
	{
		if (pAnswer == null)
		{
			return false;
		}
		JsonNode list = pAnswer.path("data").path("functions");
		if (!list.isArray())
		{
			list = pAnswer.path("data");
		}
		if (!list.isArray())
		{
			list = pAnswer.path("functions");
		}
		if (!list.isArray())
		{
			return false;
		}
		for (final JsonNode item : list)
		{
			final String fieldPath = item.path("fieldPath").asText(item.path("elementPath").asText(null));
			if (pElementPath.equals(fieldPath))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks that both triggers are reported after publishing: {@link State#MISSING} or {@link State#NOT_AUTHORED} fail, {@link State#UNKNOWN} does not
	 * (the flag was not independently re-measured in the answer's shape).
	 */
	static void verify(final McpClient pDataModelClient, final String pModelName, final List<String> pTriggerNames, final ObjectNode pDetail) throws McpException,
		OperationException
	{
		DerivationTriggers.verify(pDataModelClient, pModelName, null, pTriggerNames, pDetail, "verify");
	}

	/** Same as {@link #verify(McpClient, String, List, ObjectNode)}, for the triggers of one TABLE only, the answer recorded under {@code pDetailKey}. */
	static void verify(final McpClient pDataModelClient, final String pModelName, final String pTablePath, final List<String> pTriggerNames, final ObjectNode pDetail,
		final String pDetailKey) throws McpException, OperationException
	{
		final McpClient.ToolResult listed = DerivationTriggers.listTriggers(pDataModelClient, pModelName);
		final ObjectNode args = DerivationTriggers.MAPPER.createObjectNode();
		args.put("data_model_name", pModelName);
		args.put("operation", "list_script_table_triggers");
		pDetail.set(pDetailKey, DerivationApplier.toolDetail(args, listed));
		for (final String name : pTriggerNames)
		{
			final State state = DerivationTriggers.stateOf(listed.json, name, pTablePath);
			if (state == State.MISSING || state == State.NOT_AUTHORED)
			{
				throw OperationException.createError("trigger '" + name + "' is " + (state == State.MISSING ? "not reported" : "reported authored:false") + " after publishing " + pModelName
					+ " - see the '" + pDetailKey + "' entry of this step's detail");
			}
		}
	}

	// --------------------------------------------------------------------------------------------------------- remove

	/**
	 * Takes ONE trigger off the table by its exact name ({@code unset_element_property(property=triggers, item=<name>)}) - never without {@code item}, which
	 * would wipe every trigger of the table. Throws when EBX refuses.
	 */
	static void unset(final McpClient pDataModelClient, final String pModelName, final String pTablePath, final String pTriggerName, final ObjectNode pDetail,
		final String pDetailKey) throws McpException, OperationException
	{
		final McpClient.ToolResult result = DerivationTriggers.unsetRaw(pDataModelClient, pModelName, pTablePath, pTriggerName, pDetail, pDetailKey);
		if (result.error)
		{
			throw EngineRefusal.coded("external.removeTrigger", "could not remove trigger '" + pTriggerName + "' from " + pModelName + pTablePath + ": " + DerivationApplier.shortText(result.text), pTriggerName, pModelName + pTablePath);
		}
	}

	/** Same call as {@link #unset}, never throwing for an EBX refusal: the result is recorded in the detail and returned (rollback is best-effort). */
	static McpClient.ToolResult unsetRaw(final McpClient pDataModelClient, final String pModelName, final String pTablePath, final String pTriggerName, final ObjectNode pDetail,
		final String pDetailKey) throws McpException
	{
		final ObjectNode args = DerivationTriggers.MAPPER.createObjectNode();
		args.put("data_model_name", pModelName);
		args.put("element_path", pTablePath);
		args.put("property", "triggers");
		args.put("item", pTriggerName);
		final McpClient.ToolResult result = pDataModelClient.callTool("unset_element_property", args);
		pDetail.set(pDetailKey, DerivationApplier.toolDetail(args, result));
		return result;
	}

	/** The two trigger names of a derived attribute, create first. */
	static List<String> namesOf(final String pAttributeName)
	{
		final List<String> out = new ArrayList<String>();
		out.add(DerivationBodyBuilder.triggerName(pAttributeName, true));
		out.add(DerivationBodyBuilder.triggerName(pAttributeName, false));
		return out;
	}
}
