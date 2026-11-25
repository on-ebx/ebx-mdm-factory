package com.on.ebx.factory.landing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.PromptData;

/**
 * The language-model half of the landing key study (2026-09-23): given one source object's columns, what
 * the catalog knows of them, a small live sample and the {@link KeyEvidence} measured on it, the model names
 * the columns that identify a row in its own system - through one local tool, {@code propose_key}, never as
 * prose. Same shape as every other {@code *LlmPass} of the factory: this class is pure (prompts, the tool
 * schema, a {@link Session} that validates and records what the model called), {@code ebx.KeyStudy} runs it
 * through {@code AgentRuntime} and writes nothing until a person applies the proposal.
 * <p>
 * <b>Sample values DO leave the instance in this pass</b> - deliberately, and unlike the staging pass: telling
 * a business-partner number from a search term is exactly what the values show and the names hide. The
 * caller says so on the page ({@code wizard.step3.keyStudy.llm.ran}), the sample is bounded ({@code
 * ebx.KeyStudy.SAMPLE_MAX} rows, {@link KeyEvidence#VALUE_MAX_LENGTH} characters per value) and the pass runs
 * only for an administrator, like every other language-model pass.
 */
public final class KeyLlmPass
{
	public static final String TOOL_PROPOSE = "propose_key";
	public static final String TOOL_NOTE = "note";

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final JsonNode PROPOSE_SCHEMA = KeyLlmPass.schema("{\"type\":\"object\",\"properties\":{"
		+ "\"columns\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":1,\"description\":\"The source column names that together identify one row, in key order.\"},"
		+ "\"confidence\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100},"
		+ "\"rationale\":{\"type\":\"string\",\"description\":\"Two or three sentences a data steward can check: why these columns, what the sample showed, what could still refute it.\"}},"
		+ "\"required\":[\"columns\",\"confidence\",\"rationale\"]}");
	private static final JsonNode NOTE_SCHEMA = KeyLlmPass.schema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}");

	private KeyLlmPass()
	{
	}

	/** What the model is told about one column. */
	public static final class ColumnFacts
	{
		public final String name;
		public final String dataType;
		/** Null when the catalog does not say. */
		public final Boolean nullable;
		/** Whether the column is currently part of the proposed/chosen key ({@code LandingColumn.sourceKey}). */
		public final boolean inCurrentKey;
		/** Null when the sample has no column of this name. */
		public final KeyEvidence.Column evidence;

		public ColumnFacts(final String pName, final String pDataType, final Boolean pNullable, final boolean pInCurrentKey, final KeyEvidence.Column pEvidence)
		{
			this.name = pName;
			this.dataType = pDataType == null ? "" : pDataType;
			this.nullable = pNullable;
			this.inCurrentKey = pInCurrentKey;
			this.evidence = pEvidence;
		}
	}

	public static final class TableFacts
	{
		public final String sourceSystem;
		public final String sourceObject;
		public final String tableName;
		/** Rows at the source as profiled by the catalog, 0 when unknown. */
		public final long rowCount;
		/** {@code RULE}/{@code LLM}/{@code USER}, or empty when no key is set. */
		public final String currentKeyOrigin;
		public final List<ColumnFacts> columns;
		public final List<String> sampleColumns;
		public final List<List<String>> sampleRows;

		public TableFacts(final String pSourceSystem, final String pSourceObject, final String pTableName, final long pRowCount, final String pCurrentKeyOrigin,
			final List<ColumnFacts> pColumns, final List<String> pSampleColumns, final List<List<String>> pSampleRows)
		{
			this.sourceSystem = pSourceSystem == null ? "" : pSourceSystem;
			this.sourceObject = pSourceObject == null ? "" : pSourceObject;
			this.tableName = pTableName == null ? "" : pTableName;
			this.rowCount = pRowCount;
			this.currentKeyOrigin = pCurrentKeyOrigin == null ? "" : pCurrentKeyOrigin;
			this.columns = pColumns;
			this.sampleColumns = pSampleColumns;
			this.sampleRows = pSampleRows;
		}

		/** The column named {@code pName}, exact then case-insensitive; null when the table has none. */
		public ColumnFacts column(final String pName)
		{
			for (final ColumnFacts column : this.columns)
			{
				if (column.name.equals(pName))
				{
					return column;
				}
			}
			for (final ColumnFacts column : this.columns)
			{
				if (column.name.equalsIgnoreCase(pName))
				{
					return column;
				}
			}
			return null;
		}
	}

	public static final class Proposal
	{
		public final List<String> columns;
		public final int confidence;
		public final String rationale;

		Proposal(final List<String> pColumns, final int pConfidence, final String pRationale)
		{
			this.columns = pColumns;
			this.confidence = pConfidence;
			this.rationale = pRationale;
		}
	}

	public static String systemPrompt()
	{
		return "You are helping a data steward choose the SOURCE KEY of one table that is about to be landed into an MDM hub. "
			+ "The source key is the identity of a row in its own system: the column (or the smallest set of columns) whose values are "
			+ "present on every row, never change for a given row, and are never shared by two rows. It becomes _sourceId in the landing "
			+ "and is followed up to the golden record, so a wrong key duplicates or overwrites records for years.\n\n"
			+ "How to judge:\n"
			+ "- Prefer the system's own identifier (a business-partner number, a customer id, a Salesforce 18-character Id, a GUID) over a "
			+ "natural key (name + birth date, email): identifiers are stable and unique by construction, natural keys are not.\n"
			+ "- A key may be composite: a line/detail table is identified by its parent id plus its line number; a per-language or per-date "
			+ "table by the entity id plus the language or date.\n"
			+ "- Never propose a hash, a timestamp, a load date, a free-text field or a flag as a key, however unique it looks in the sample.\n"
			+ "- The sample is small: 'unique in the sample' supports a candidate, it does not prove it, and a column empty in the sample is "
			+ "disqualified. State in the rationale what the sample showed and what could still refute the choice.\n"
			+ "- If nothing is a genuine identifier, propose the natural composite that is unique in the sample with a LOW confidence "
			+ "(under 50) and say plainly that the source has no stable identifier.\n"
			+ "- The current proposal (if any) came from a naming rule or a person; you may confirm it or replace it, and must say which.\n\n"
			+ "Call propose_key EXACTLY ONCE with the column names spelled exactly as given. Anything you write as prose is NOT recorded; "
			+ "only the tool call is. Use note only for a remark worth keeping besides the proposal." + PromptData.SYSTEM_NOTICE;
	}

	/** The facts as JSON: the table, its columns with their evidence, then the sample rows themselves. */
	public static String userPrompt(final TableFacts pFacts)
	{
		return KeyLlmPass.userPrompt(pFacts, true);
	}

	/**
	 * With {@code pShareValues} false (the project's {@code llmValueSharing} is not {@code SAMPLE_VALUES}, the default),
	 * every sample value is sent as its shape ({@code quality.Shapes}: letters to a/A, digits to 9, separators kept),
	 * never as written - enough to judge uniqueness and format, the counts being computed on the real values
	 * (security review, 2026-09-24: the key study used to send real rows whatever the project setting).
	 */
	public static String userPrompt(final TableFacts pFacts, final boolean pShareValues)
	{
		return KeyLlmPass.userPrompt(pFacts, pShareValues ? Sharing.VALUES : Sharing.SHAPES, java.util.Collections.<String>emptySet());
	}

	/** What a sample value becomes in the prompt (lot B, 2026-09-24, mirrors {@code ebx.LlmDataPolicy.Level}). */
	public enum Sharing
	{
		/** Nothing: counts and uniqueness only. */
		NONE,
		/** The value's shape ({@code quality.Shapes}). */
		SHAPES,
		/** The value itself, cut to {@code KeyEvidence.VALUE_MAX_LENGTH}. */
		VALUES
	}

	/**
	 * Lot B: {@code pSharing} for every column, except the columns named in {@code pWithheld} (sensitive names of the
	 * instance policy, compared case-insensitively), whose values never appear - neither in the per-column evidence nor in
	 * the sample rows - only their counts.
	 */
	public static String userPrompt(final TableFacts pFacts, final Sharing pSharing, final java.util.Set<String> pWithheld)
	{
		final java.util.Set<String> withheld = new java.util.HashSet<String>();
		for (final String name : pWithheld)
		{
			withheld.add(name.toLowerCase(java.util.Locale.ROOT));
		}
		final ObjectNode root = KeyLlmPass.MAPPER.createObjectNode();
		root.put("sourceSystem", pFacts.sourceSystem);
		root.put("sourceObject", pFacts.sourceObject);
		root.put("landingTable", pFacts.tableName);
		if (pFacts.rowCount > 0)
		{
			root.put("rowsAtSource", pFacts.rowCount);
		}
		root.put("currentKeyOrigin", pFacts.currentKeyOrigin.isEmpty() ? "none" : pFacts.currentKeyOrigin);
		final String sampleValueNote = KeyLlmPass.sampleValueNote(pSharing);
		if (sampleValueNote != null)
		{
			root.put("sampleValueNote", sampleValueNote);
		}
		final ArrayNode currentKey = root.putArray("currentKey");
		final ArrayNode columns = root.putArray("columns");
		for (final ColumnFacts column : pFacts.columns)
		{
			if (column.inCurrentKey)
			{
				currentKey.add(column.name);
			}
			final ObjectNode node = columns.addObject();
			node.put("name", column.name);
			if (!column.dataType.isEmpty())
			{
				node.put("dataType", column.dataType);
			}
			if (column.nullable != null)
			{
				node.put("nullableAtSource", column.nullable.booleanValue());
			}
			if (column.evidence == null)
			{
				node.put("sample", "not in the sample");
				continue;
			}
			final ObjectNode sample = node.putObject("sample");
			sample.put("rows", column.evidence.rows);
			sample.put("empty", column.evidence.empty);
			sample.put("distinct", column.evidence.distinct);
			sample.put("uniqueInSample", column.evidence.uniqueInSample());
			final Sharing columnSharing = withheld.contains(column.name.toLowerCase(java.util.Locale.ROOT)) ? Sharing.NONE : pSharing;
			if (columnSharing == Sharing.NONE)
			{
				sample.put("values", "withheld by the data policy");
				continue;
			}
			final ArrayNode values = sample.putArray("values");
			for (final String value : column.evidence.values)
			{
				values.add(columnSharing == Sharing.VALUES ? value : com.on.ebx.factory.quality.Shapes.of(value));
			}
		}
		final ObjectNode sample = root.putObject("sampleRows");
		sample.put("count", pFacts.sampleRows.size());
		final ArrayNode header = sample.putArray("columns");
		for (final String column : pFacts.sampleColumns)
		{
			header.add(column);
		}
		final ArrayNode rows = sample.putArray("rows");
		for (final List<String> row : pFacts.sampleRows)
		{
			final ArrayNode line = rows.addArray();
			for (int i = 0; i < row.size(); i++)
			{
				final String value = row.get(i);
				final String column = i < pFacts.sampleColumns.size() ? pFacts.sampleColumns.get(i) : "";
				final Sharing columnSharing = withheld.contains(column.toLowerCase(java.util.Locale.ROOT)) ? Sharing.NONE : pSharing;
				if (value == null || value.isEmpty())
				{
					line.add("");
				}
				else if (columnSharing == Sharing.NONE)
				{
					line.add("*"); // present but withheld: the model still sees which cells are filled
				}
				else
				{
					line.add(columnSharing == Sharing.VALUES ? KeyEvidence.cut(value) : com.on.ebx.factory.quality.Shapes.of(KeyEvidence.cut(value)));
				}
			}
		}
		return "Choose the source key of this table, then call propose_key once. Everything in the facts below comes from the source system.\n\n"
			+ PromptData.fence("SOURCE TABLE FACTS", root.toString());
	}

	/**
	 * Bug fixed live 2026-09-27 (project DeducedTest3): with {@code pSharing == SHAPES} (the default, no project
	 * opt-in to real values), every sample value is replaced by its shape ({@code quality.Shapes}), and nothing
	 * in the prompt said so - so two DIFFERENT real values that happen to share a shape (e.g. two short
	 * alphanumeric ids) render as the SAME masked string in every row of the sample. A
	 * live run answered a table's key with confidence 55%/5%, its own rationale citing "every displayed sample
	 * row has the same value ... so its uniqueness is contradicted" - reasoning from the masked display, while
	 * the JSON's own {@code distinct}/{@code uniqueInSample} fields (computed on the REAL values, never masked -
	 * see {@link KeyEvidence}) already said otherwise. Rather than sending no sample rows at all (the other
	 * option the task considered: it would also remove the model's ability to judge FORMAT, which is exactly
	 * why shapes are sent instead of nothing in the first place - see this pass's own class-Javadoc and
	 * {@code docs/LANDING-KEY-STUDY.md}), this note tells the model plainly what it is looking at and which
	 * fields to trust instead. {@code null} for {@code VALUES} (real values, no caveat needed).
	 */
	static String sampleValueNote(final Sharing pSharing)
	{
		if (pSharing == Sharing.SHAPES)
		{
			return "Sample and column 'values' below are MASKED SHAPES (letters -> a/A, digits -> 9, separators kept), not the real "
				+ "values, per the instance's data-sharing policy. Two masked values that look identical (e.g. the same row shown "
				+ "several times as \"A99\") may still be DIFFERENT real values in the source: never conclude a column is not "
				+ "unique, or that two rows are duplicates, from masked values looking the same or different. The 'distinct' and "
				+ "'uniqueInSample' figures are computed on the real, unmasked data and are what you must rely on for uniqueness; "
				+ "use the masked shapes only to judge FORMAT (length, letter/digit/separator pattern).";
		}
		if (pSharing == Sharing.NONE)
		{
			return "Sample values below are withheld entirely by the instance's data-sharing policy (shown as \"*\" when present, "
				+ "blank when empty). Judge uniqueness only from the 'distinct' and 'uniqueInSample' figures, which are computed on "
				+ "the real, unmasked data; nothing about the values' format or content is available to you here.";
		}
		return null;
	}

	/** Records what the model calls; the same mutable object across the first run and the prose follow-up. */
	public static final class Session
	{
		private final TableFacts facts;
		private Proposal proposal;
		private final List<String> notes = new ArrayList<String>();

		public Session(final TableFacts pFacts)
		{
			this.facts = pFacts;
		}

		/** Null until {@code propose_key} was called with a valid payload. */
		public Proposal proposal()
		{
			return this.proposal;
		}

		public List<String> notes()
		{
			return this.notes;
		}

		/** {@code ProseAnswerRecovery}'s test: nothing proposed and nothing noted. */
		public boolean recordedNothing()
		{
			return this.proposal == null && this.notes.isEmpty();
		}

		public ToolSet.LocalGroup toolGroup()
		{
			final List<LocalTool> tools = new ArrayList<LocalTool>();
			tools.add(new LocalTool(KeyLlmPass.TOOL_PROPOSE, "Propose the source key of the table: the columns that identify one row, a 0-100 confidence and a rationale.",
				KeyLlmPass.PROPOSE_SCHEMA, new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.propose(pArguments);
					}
				}));
			tools.add(new LocalTool(KeyLlmPass.TOOL_NOTE, "Record a remark worth keeping besides the proposal.", KeyLlmPass.NOTE_SCHEMA, new LocalTool.Handler()
			{
				@Override
				public LocalTool.Result handle(final JsonNode pArguments)
				{
					final String text = pArguments == null ? "" : pArguments.path("text").asText("").trim();
					if (text.isEmpty())
					{
						return LocalTool.Result.error("text is required.");
					}
					Session.this.notes.add(text);
					return LocalTool.Result.ok("Noted.");
				}
			}));
			return new ToolSet.LocalGroup(tools);
		}

		LocalTool.Result propose(final JsonNode pArguments)
		{
			final String violation = LocalTool.firstViolation(KeyLlmPass.PROPOSE_SCHEMA, pArguments);
			if (violation != null)
			{
				return LocalTool.Result.error(violation);
			}
			final List<String> columns = new ArrayList<String>();
			for (final JsonNode item : pArguments.path("columns"))
			{
				final String name = item.asText("").trim();
				if (name.isEmpty())
				{
					continue;
				}
				final ColumnFacts known = this.facts.column(name);
				if (known == null)
				{
					return LocalTool.Result.error("Unknown column '" + name + "': the table's columns are " + this.columnNames() + ".");
				}
				if (!columns.contains(known.name))
				{
					columns.add(known.name);
				}
			}
			if (columns.isEmpty())
			{
				return LocalTool.Result.error("columns must name at least one column of the table.");
			}
			final int confidence = Math.max(0, Math.min(100, pArguments.path("confidence").asInt(0)));
			final String rationale = pArguments.path("rationale").asText("").trim();
			if (rationale.isEmpty())
			{
				return LocalTool.Result.error("rationale is required.");
			}
			this.proposal = new Proposal(columns, confidence, rationale);
			return LocalTool.Result.ok("Recorded: key " + columns + " (" + confidence + " %). You are done.");
		}

		private String columnNames()
		{
			final List<String> names = new ArrayList<String>();
			for (final ColumnFacts column : this.facts.columns)
			{
				names.add(column.name);
			}
			return names.toString();
		}
	}

	private static JsonNode schema(final String pJson)
	{
		try
		{
			return KeyLlmPass.MAPPER.readTree(pJson);
		}
		catch (final IOException ex)
		{
			// the built-in schemas of this class are fixed constants: a parse failure here is a bug in this file, not a runtime condition
			throw new IllegalStateException(ex);
		}
	}
}
