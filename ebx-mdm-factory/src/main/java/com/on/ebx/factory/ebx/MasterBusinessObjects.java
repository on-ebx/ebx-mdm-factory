package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.master.BusinessObjectPlan;
import com.on.ebx.factory.master.MasterModel;

/**
 * Declares, on the master DMA model and through the {@code data-model} MCP server, the Match and Merge business objects a relation-field plan needs
 * (lot W13, 2026-10-12, `docs/MATCHING.md` sect;33): for every promoted parent table that has promoted child tables, an inverse association per child
 * on the PARENT table (a string host field named after the child, plus {@code set_association tableRefInverse} over the child's foreign key), the
 * {@code businessObjects} extension, ONE business object {@code <Parent>BO} with the parent as main table, and one relationship per child association.
 * The caller publishes right after (the same {@code publish_data_model} that carries the rest of the model) - nothing is published here.
 * <p>
 * Idempotent: {@link BusinessObjectPlan#reconcile} decides, from what the model already holds, which of the writes are still to do; a second generation
 * writes nothing. A write the server refuses ends THAT declaration (a half-declared object would fail the publication) and is reported in the notes - the
 * master is still delivered, and the matching configurator then refuses a relation-field plan with the coded {@code missing.businessObject} instead of
 * guessing. Calls (names and argument shapes, measured on the scratch model W13BO):
 * <pre>
 * execute_query(data_model_name, operation=get_data_model_business_objects)                         read the objects
 * describe_table(data_model_name, table_path=/root/Parent)                                          read the parent's fields and associations
 * execute_operation(operation=enable_extension, arguments_json={"extension":"businessObjects"})     once, only when the model says it is not enabled
 * create_field(data_model_name, field_path=/root/Parent/children, field_type=string)                the host field
 * set_association(data_model_name, field_path=/root/Parent/children, mode=tableRefInverse, fk_field_path=/root/Child/parentRef)
 * execute_operation(operation=create_business_object, arguments_json={"name":"ParentBO","main_table":"/root/Parent"})
 * execute_operation(operation=add_business_object_relationship, arguments_json={"business_object_name":"ParentBO","relationship_path":"/root/Parent/children"})
 * </pre>
 */
final class MasterBusinessObjects
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MasterBusinessObjects()
	{
	}

	/** What {@link #ensure} did, for the step detail and the route's notes. */
	static final class Result
	{
		/** One line per business object declared or checked ({@code PersonneBO on /root/Personne: 1 relationship written}). */
		final List<String> notes = new ArrayList<String>();
		/** The business objects (main table) the model holds after this call. */
		final List<String> declaredMainTables = new ArrayList<String>();
		/** True when a write was made to the draft model: the caller must publish (the same publish that carries the rest of the model). */
		boolean changed;
	}

	/**
	 * Declares every business object of {@code pMasterModel} that the model does not hold yet; records the details under {@code businessObjects} in {@code
	 * pDetail}. A master with no child entity declares nothing and calls nothing.
	 */
	static Result ensure(final McpClient pClient, final String pModelName, final MasterModel.Result pMasterModel, final ObjectNode pDetail) throws McpException
	{
		final Result result = new Result();
		final List<BusinessObjectPlan.Declaration> declarations = BusinessObjectPlan.plan(pMasterModel);
		if (declarations.isEmpty())
		{
			return result;
		}
		final ArrayNode detailList = pDetail.putArray("businessObjects");
		boolean extensionEnabledByUs = false;
		for (final BusinessObjectPlan.Declaration declaration : declarations)
		{
			final ObjectNode entry = detailList.addObject();
			entry.put("mainTable", declaration.mainTablePath);
			entry.put("name", declaration.name);
			if (!declaration.skippedForeignKeys.isEmpty())
			{
				final ArrayNode skipped = entry.putArray("skippedForeignKeys");
				for (final String line : declaration.skippedForeignKeys)
				{
					skipped.add(line);
				}
			}
			final BusinessObjectPlan.ExistingState existing = MasterBusinessObjects.readExisting(pClient, pModelName, declaration, entry);
			final BusinessObjectPlan.Steps steps = BusinessObjectPlan.reconcile(declaration, new BusinessObjectPlan.ExistingState(existing.extensionEnabled || extensionEnabledByUs,
				existing.parentFieldNames, existing.associationFkByHostField, existing.businessObjectName, existing.relationshipPaths));
			entry.put("businessObjectName", steps.businessObjectName);
			if (steps.nothingToDo())
			{
				entry.put("alreadyDeclared", true);
				result.notes.add(steps.businessObjectName + " on " + declaration.mainTablePath + ": already declared");
				result.declaredMainTables.add(declaration.mainTablePath);
				continue;
			}
			result.changed = true;
			final String failure = MasterBusinessObjects.apply(pClient, pModelName, steps, entry);
			extensionEnabledByUs = extensionEnabledByUs || (steps.enableExtension && failure == null);
			if (failure != null)
			{
				entry.put("failure", failure);
				result.notes.add(steps.businessObjectName + " on " + declaration.mainTablePath + ": NOT declared - " + failure
					+ " (a plan comparing child fields of this table is refused until it is declared)");
				continue;
			}
			result.notes.add(steps.businessObjectName + " on " + declaration.mainTablePath + ": declared (" + steps.relationships.size() + " relationship(s))");
			result.declaredMainTables.add(declaration.mainTablePath);
		}
		return result;
	}

	private static BusinessObjectPlan.ExistingState readExisting(final McpClient pClient, final String pModelName, final BusinessObjectPlan.Declaration pDeclaration,
		final ObjectNode pEntry) throws McpException
	{
		final ObjectNode queryArgs = MasterBusinessObjects.MAPPER.createObjectNode();
		queryArgs.put("data_model_name", pModelName);
		queryArgs.put("operation", "get_data_model_business_objects");
		final McpClient.ToolResult query = pClient.callTool("execute_query", queryArgs);
		final ObjectNode describeArgs = MasterBusinessObjects.MAPPER.createObjectNode();
		describeArgs.put("data_model_name", pModelName);
		describeArgs.put("table_path", pDeclaration.mainTablePath);
		final McpClient.ToolResult describe = pClient.callTool("describe_table", describeArgs);
		final BusinessObjectPlan.ExistingState existing = BusinessObjectPlan.readExisting(query.error ? null : query.json, describe.error ? null : describe.json,
			pDeclaration.mainTablePath);
		pEntry.put("existingBusinessObject", existing.businessObjectName == null ? "none" : existing.businessObjectName);
		pEntry.put("extensionEnabled", existing.extensionEnabled);
		return existing;
	}

	/** Runs the writes {@code pSteps} still lists; returns null when all succeeded, else the first refusal (the declaration stops there). */
	private static String apply(final McpClient pClient, final String pModelName, final BusinessObjectPlan.Steps pSteps, final ObjectNode pEntry) throws McpException
	{
		final ArrayNode calls = pEntry.putArray("calls");
		if (pSteps.enableExtension)
		{
			final String failure = MasterBusinessObjects.operation(pClient, pModelName, "enable_extension", MasterBusinessObjects.args("extension", "businessObjects"), calls);
			if (failure != null)
			{
				return "enable_extension businessObjects: " + failure;
			}
		}
		final String parentPath = pSteps.declaration.mainTablePath;
		for (final BusinessObjectPlan.RelationshipStep step : pSteps.relationships)
		{
			final String hostPath = parentPath + "/" + step.hostFieldName;
			if (step.createHostField)
			{
				final ObjectNode createArgs = MasterBusinessObjects.MAPPER.createObjectNode();
				createArgs.put("data_model_name", pModelName);
				createArgs.put("field_path", hostPath);
				createArgs.put("field_type", "string");
				final McpClient.ToolResult created = pClient.callTool("create_field", createArgs);
				MasterBusinessObjects.record(calls, "create_field", createArgs, created);
				final String failure = created.error ? MasterBusinessObjects.shortText(created.text) : BusinessObjectPlan.failureOf(created.json);
				if (failure != null)
				{
					return "create_field " + hostPath + ": " + failure;
				}
			}
			if (step.setAssociation)
			{
				final ObjectNode associationArgs = MasterBusinessObjects.MAPPER.createObjectNode();
				associationArgs.put("data_model_name", pModelName);
				associationArgs.put("field_path", hostPath);
				associationArgs.put("mode", "tableRefInverse");
				associationArgs.put("fk_field_path", step.relationship.fkFieldPath());
				final McpClient.ToolResult associated = pClient.callTool("set_association", associationArgs);
				MasterBusinessObjects.record(calls, "set_association", associationArgs, associated);
				final String failure = associated.error ? MasterBusinessObjects.shortText(associated.text) : BusinessObjectPlan.failureOf(associated.json);
				if (failure != null)
				{
					return "set_association " + hostPath + " over " + step.relationship.fkFieldPath() + ": " + failure;
				}
			}
		}
		if (pSteps.createBusinessObject)
		{
			final ObjectNode createArgs = MasterBusinessObjects.args("name", pSteps.businessObjectName);
			createArgs.put("main_table", parentPath);
			final String failure = MasterBusinessObjects.operation(pClient, pModelName, "create_business_object", createArgs, calls);
			if (failure != null)
			{
				return "create_business_object " + pSteps.businessObjectName + ": " + failure;
			}
		}
		for (final BusinessObjectPlan.RelationshipStep step : pSteps.relationships)
		{
			if (!step.addRelationship)
			{
				continue;
			}
			final ObjectNode relationshipArgs = MasterBusinessObjects.args("business_object_name", pSteps.businessObjectName);
			relationshipArgs.put("relationship_path", step.relationshipPath);
			final String failure = MasterBusinessObjects.operation(pClient, pModelName, "add_business_object_relationship", relationshipArgs, calls);
			if (failure != null)
			{
				return "add_business_object_relationship " + step.relationshipPath + ": " + failure;
			}
		}
		return null;
	}

	private static ObjectNode args(final String pName, final String pValue)
	{
		final ObjectNode node = MasterBusinessObjects.MAPPER.createObjectNode();
		node.put(pName, pValue);
		return node;
	}

	/** One {@code execute_operation}; null = ok, else the refusal text. */
	private static String operation(final McpClient pClient, final String pModelName, final String pOperation, final ObjectNode pArguments, final ArrayNode pCalls)
		throws McpException
	{
		final ObjectNode callArgs = MasterBusinessObjects.MAPPER.createObjectNode();
		callArgs.put("data_model_name", pModelName);
		callArgs.put("operation", pOperation);
		callArgs.put("arguments_json", pArguments.toString());
		final McpClient.ToolResult result = pClient.callTool("execute_operation", callArgs);
		MasterBusinessObjects.record(pCalls, pOperation, callArgs, result);
		return result.error ? MasterBusinessObjects.shortText(result.text) : BusinessObjectPlan.failureOf(result.json);
	}

	private static void record(final ArrayNode pCalls, final String pTool, final ObjectNode pArguments, final McpClient.ToolResult pResult)
	{
		final ObjectNode call = pCalls.addObject();
		call.put("tool", pTool);
		call.set("arguments", pArguments);
		call.put("error", pResult.error);
		call.put("answer", MasterBusinessObjects.shortText(pResult.text));
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 300 ? text.substring(0, 300) + "..." : text;
	}
}
