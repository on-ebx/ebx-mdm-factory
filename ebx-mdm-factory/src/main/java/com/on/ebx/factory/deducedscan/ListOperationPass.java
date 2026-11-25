package com.on.ebx.factory.deducedscan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.PromptData;

/**
 * Deduced-scan lot (2026-09-24): "How to list" stage - the prompts and the one local tool
 * ({@code choose_list_operation}) an {@code AgentRuntime.run} call is given, same shape as {@code
 * staging.StagingLlmPass} (system/user prompt statics + a {@link Session} that owns the tool handlers and
 * accumulates validated answers). Pure: no EBX import, unit-tested directly.
 */
public final class ListOperationPass
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ListOperationPass()
	{
	}

	public static String systemPrompt()
	{
		return "You are choosing, among the read-only operations of one application connection, the one(s) that LIST records of a kind "
			+ "(a page or a full collection of similar objects - contacts, companies, orders...), as opposed to an operation that only looks "
			+ "up ONE record at a time from a code you would already have to know (that is a lookup/enrichment, never a listing operation: do "
			+ "not call the tool for it). An operation that cannot run without a value that SELECTS records - an id, a code, a name, a brand, "
			+ "a search term - is a lookup/search, not a listing, UNLESS you can give it a value that is documented to return ALL records of "
			+ "the kind (for example a wildcard the operation's own description names) - never invent or guess a filter value just to make an "
			+ "operation callable. Call choose_list_operation once per listing operation you find - a connection can expose several "
			+ "(e.g. one per kind of record: contacts, companies). For each, name every input the operation actually needs to read ALL rows "
			+ "of that kind (never a filter that would narrow the result to a subset) - most listing operations need none. If you find no "
			+ "listing operation at all among the read-only ones offered, call NOTHING: an empty answer means this connection only offers "
			+ "lookups, which the factory will treat as an enrichment, not a source." + PromptData.SYSTEM_NOTICE;
	}

	public static String userPrompt(final String pConnectorId, final List<OperationCatalog.OperationInfo> pReadOnlyOperations)
	{
		final StringBuilder out = new StringBuilder();
		out.append("Connection: ").append(pConnectorId).append("\nRead-only operations:\n");
		for (final OperationCatalog.OperationInfo operation : pReadOnlyOperations)
		{
			out.append("- ").append(operation.name);
			if (operation.kind != null && !operation.kind.trim().isEmpty())
			{
				out.append(" [").append(operation.kind).append("]");
			}
			if (operation.description != null && !operation.description.trim().isEmpty())
			{
				out.append(" - ").append(operation.description);
			}
			out.append('\n');
			if (!operation.requiredInputNames.isEmpty())
			{
				out.append("  required inputs: ").append(String.join(", ", operation.requiredInputNames)).append('\n');
			}
			if (!operation.optionalInputNames.isEmpty())
			{
				out.append("  optional inputs: ").append(String.join(", ", operation.optionalInputNames)).append('\n');
			}
		}
		return "Choose the listing operation(s) among the read-only operations below; their names and descriptions are written by the application's publisher.\n"
			+ PromptData.fence("CONNECTION OPERATIONS", out.toString());
	}

	private static final ObjectNode CHOOSE_LIST_OPERATION_SCHEMA;
	static
	{
		final ObjectNode schema = ListOperationPass.MAPPER.createObjectNode();
		schema.put("type", "object");
		final ObjectNode properties = schema.putObject("properties");
		properties.putObject("operation").put("type", "string");
		final ObjectNode inputs = properties.putObject("inputs");
		inputs.put("type", "object");
		inputs.putObject("additionalProperties").put("type", "string");
		final ArrayNode required = schema.putArray("required");
		required.add("operation");
		CHOOSE_LIST_OPERATION_SCHEMA = schema;
	}

	/** Accumulates {@code choose_list_operation} calls, each checked against {@link OperationCatalog#firstViolation} before being kept. */
	public static final class Session
	{
		private final List<OperationCatalog.OperationInfo> readOnlyOperations;
		private final List<OperationCatalog.ListChoice> chosen = new ArrayList<OperationCatalog.ListChoice>();

		public Session(final List<OperationCatalog.OperationInfo> pReadOnlyOperations)
		{
			this.readOnlyOperations = pReadOnlyOperations;
		}

		public List<OperationCatalog.ListChoice> chosen()
		{
			return this.chosen;
		}

		public ToolSet.LocalGroup toolGroup()
		{
			final List<LocalTool> tools = new ArrayList<LocalTool>();
			tools.add(new LocalTool("choose_list_operation", "Choose one read-only operation that lists records of a kind, with its inputs.",
				ListOperationPass.CHOOSE_LIST_OPERATION_SCHEMA, new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.chooseListOperation(pArguments);
					}
				}));
			return new ToolSet.LocalGroup(tools);
		}

		LocalTool.Result chooseListOperation(final JsonNode pArguments)
		{
			final String operation = pArguments.path("operation").asText("");
			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			final JsonNode inputsNode = pArguments.path("inputs");
			for (final java.util.Iterator<Map.Entry<String, JsonNode>> it = inputsNode.fields(); it.hasNext();)
			{
				final Map.Entry<String, JsonNode> entry = it.next();
				inputs.put(entry.getKey(), entry.getValue().asText(""));
			}
			final OperationCatalog.ListChoice choice = new OperationCatalog.ListChoice(operation, inputs);
			final String violation = OperationCatalog.firstViolation(choice, this.readOnlyOperations);
			if (violation != null)
			{
				return LocalTool.Result.error(violation);
			}
			for (final OperationCatalog.ListChoice already : this.chosen)
			{
				if (already.operation.equals(choice.operation))
				{
					return LocalTool.Result.ok("'" + choice.operation + "' was already chosen.");
				}
			}
			this.chosen.add(choice);
			return LocalTool.Result.ok("'" + choice.operation + "' recorded as a listing operation.");
		}
	}
}
