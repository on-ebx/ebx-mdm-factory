package com.on.ebx.factory.deducedscan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.PromptData;
import com.on.ebx.factory.landing.KeyLlmPass;
import com.on.ebx.factory.quality.Shapes;

/**
 * Deduced-scan lot (2026-09-24): "Name and key" stage - one {@code AgentRuntime.run} per table, given the
 * columns {@link ColumnMeasurer} measured on the sample, proposes a table name, key column(s), a confidence
 * (0-100) and a one-sentence rationale through the single {@code propose_table} tool. Pure, same
 * prompts-plus-{@link Session} shape as {@link ListOperationPass}.
 */
public final class NameKeyPass
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private NameKeyPass()
	{
	}

	public static String systemPrompt()
	{
		// fix lot (2026-09-26): table names came back inconsistent across two tables of the same source (e.g. one
		// singular, one plural) - the model now must ask for one specific shape rather than "PascalCase or
		// camelCase" with no further guidance.
		return "You are naming ONE table deduced from a sample read from an application with no catalog of its own, and proposing its key. "
			+ "Call propose_table exactly once: a SINGULAR business name in PascalCase (one table = one kind of record - e.g. \"Company\", "
			+ "\"Contact\", never the plural of the listing operation it came from, e.g. not \"Companies\"/\"Contacts\"); "
			+ "the column(s) that together uniquely identify one row (a real business/technical key - prefer a single column that looks like "
			+ "a stable identifier, filled and different on every row, over a composite key, unless nothing single-column qualifies); a "
			+ "confidence from 0 (a guess) to 100 (certain, e.g. a column literally named id/ID that is fully filled and fully distinct); and "
			+ "one plain sentence explaining why - name what you observed in the measurements (filled/distinct counts), never invent a reason." + PromptData.SYSTEM_NOTICE;
	}

	/** Longest example shown or shaped; longer ones are cut with an ellipsis (same cap as the key study's sample values). */
	static final int EXAMPLE_MAX_LENGTH = 40;

	/**
	 * What each column's example becomes in the prompt, following the project's effective data-policy level
	 * ({@code ebx.LlmDataPolicy}): nothing under {@code NONE}, the value's shape ({@link Shapes}) under {@code SHAPES},
	 * the value itself (cut) only under {@code VALUES}. A column named in {@code pWithheld} (a sensitive column, matched
	 * case-insensitively) never gives an example whatever the level; its counts still go, they are what the model reasons on.
	 */
	public static String userPrompt(final String pOperationName, final List<ColumnMeasurer.Measured> pMeasured, final KeyLlmPass.Sharing pSharing,
		final Set<String> pWithheld)
	{
		final Set<String> withheld = new HashSet<String>();
		for (final String name : pWithheld)
		{
			withheld.add(name.toLowerCase(Locale.ROOT));
		}
		final StringBuilder out = new StringBuilder();
		out.append("Operation read: ").append(pOperationName).append("\nMeasured columns (name: type, filled/sample, distinct, example):\n");
		for (final ColumnMeasurer.Measured column : pMeasured)
		{
			out.append("- ").append(column.name).append(": ").append(column.type).append(", ").append(column.filled).append('/').append(column.sampleSize)
				.append(" filled, ").append(column.distinct).append(" distinct, example ");
			final KeyLlmPass.Sharing sharing = withheld.contains(column.name.toLowerCase(Locale.ROOT)) ? KeyLlmPass.Sharing.NONE : pSharing;
			if (sharing == KeyLlmPass.Sharing.NONE || column.example.isEmpty())
			{
				out.append(sharing == KeyLlmPass.Sharing.NONE ? "withheld by the data policy" : "none").append('\n');
				continue;
			}
			final String shown = NameKeyPass.cut(column.example);
			out.append('"').append(sharing == KeyLlmPass.Sharing.VALUES ? shown : Shapes.of(shown)).append("\"\n");
		}
		final StringBuilder prompt = new StringBuilder("Name the table read by the operation below and propose its key. Everything in the measurements comes from the source application.\n");
		prompt.append(PromptData.fence("MEASURED COLUMNS", out.toString()));
		if (pSharing == KeyLlmPass.Sharing.SHAPES)
		{
			prompt.append("Examples are MASKED SHAPES (letters -> a/A, digits -> 9, separators kept), not the real values, per the data-sharing policy.\n");
		}
		return prompt.toString();
	}

	private static String cut(final String pValue)
	{
		return pValue.length() <= NameKeyPass.EXAMPLE_MAX_LENGTH ? pValue : pValue.substring(0, NameKeyPass.EXAMPLE_MAX_LENGTH - 1) + "\u2026";
	}

	private static final ObjectNode PROPOSE_TABLE_SCHEMA;
	static
	{
		final ObjectNode schema = NameKeyPass.MAPPER.createObjectNode();
		schema.put("type", "object");
		final ObjectNode properties = schema.putObject("properties");
		properties.putObject("tableName").put("type", "string");
		final ObjectNode keyColumns = properties.putObject("keyColumns");
		keyColumns.put("type", "array");
		keyColumns.putObject("items").put("type", "string");
		properties.putObject("confidence").put("type", "integer");
		properties.putObject("rationale").put("type", "string");
		final ArrayNode required = schema.putArray("required");
		required.add("tableName");
		required.add("keyColumns");
		required.add("confidence");
		required.add("rationale");
		PROPOSE_TABLE_SCHEMA = schema;
	}

	public static final class Session
	{
		private final List<String> measuredColumnNames;
		private TableProposal proposal;

		public Session(final List<ColumnMeasurer.Measured> pMeasured)
		{
			this.measuredColumnNames = new ArrayList<String>();
			for (final ColumnMeasurer.Measured column : pMeasured)
			{
				this.measuredColumnNames.add(column.name);
			}
		}

		/** Null until a valid {@code propose_table} call was made. */
		public TableProposal proposal()
		{
			return this.proposal;
		}

		public ToolSet.LocalGroup toolGroup()
		{
			final List<LocalTool> tools = new ArrayList<LocalTool>();
			tools.add(new LocalTool("propose_table", "Propose this table's name, its key column(s), a confidence and a one-sentence rationale.",
				NameKeyPass.PROPOSE_TABLE_SCHEMA, new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.proposeTable(pArguments);
					}
				}));
			return new ToolSet.LocalGroup(tools);
		}

		LocalTool.Result proposeTable(final JsonNode pArguments)
		{
			final List<String> keyColumns = new ArrayList<String>();
			for (final JsonNode keyColumnNode : pArguments.path("keyColumns"))
			{
				keyColumns.add(keyColumnNode.asText(""));
			}
			final TableProposal candidate = new TableProposal(pArguments.path("tableName").asText(""), keyColumns,
				TableProposal.clampConfidence(pArguments.path("confidence").asInt(0)), pArguments.path("rationale").asText(""));
			final String violation = TableProposal.firstViolation(candidate, this.measuredColumnNames);
			if (violation != null)
			{
				return LocalTool.Result.error(violation);
			}
			this.proposal = candidate;
			return LocalTool.Result.ok("'" + candidate.tableName + "' recorded, key " + candidate.keyColumns + ", confidence " + candidate.confidence + ".");
		}
	}
}
