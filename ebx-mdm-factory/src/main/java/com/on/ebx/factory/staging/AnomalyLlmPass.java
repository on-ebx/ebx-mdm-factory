package com.on.ebx.factory.staging;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.PromptData;

/**
 * Staging-proposal-run lot (2026-09-24, docs/STAGING-PROPOSAL.md "run"): the anomaly pass - a {@link
 * AttentionRules#CONSTANT_COLUMN}/{@link AttentionRules#EMPTY_COLUMN} flag is a purely structural, Java-
 * measured signal (fill rate/distinct count); whether the landing table has a BETTER column for the flagged
 * attribute is a domain question, so it goes to the language model, one call per flagged business object,
 * exactly like {@link StagingLlmPass}/{@code QualityLlmPass}/{@code CapabilityLlmPass} already do. The model
 * sees the flagged attribute (name, label, current column and its measured shape) and every OTHER, still-
 * unused column of the SAME landing table with its own measured shape (fill rate, distinct count, max
 * length - never a row's actual value, unless the caller explicitly turned {@code
 * ebx.AnomalyLlmSuggester}'s own sample-sharing option on, see that class's Javadoc for why this pass keeps
 * that option itself instead of reading {@code Project.llmValueSharing}, deliberately). It never sees a
 * column NAME judged by Java - the flag exists purely from the numbers (see {@link AttentionRules}'s own
 * "domain-free" Javadoc) - the model is the one thing here allowed to read names.
 * <p>
 * ONE tool, {@code propose_replacement}: the model calls it when it found a better column, giving a
 * per-call rationale and confidence (same posture as the per-field-rationale lot elsewhere in this
 * codebase - never a page-level default). It simply does not call it - the flag stays a flag, nothing is
 * written - when it agrees the field should be dropped, or that nothing else fits either; "propose removing
 * the field" is not a tool of its own (task brief: "do not write anything, the UI offers reject").
 */
public final class AnomalyLlmPass
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static final String TOOL_REPLACE = "propose_replacement";
	/** Validation desk lot L3: the correction of a {@link AttentionRules#SAME_CONSTANT} flag - the constant one source should write instead. */
	public static final String TOOL_CONSTANT = "propose_constant";
	/** A proposed constant stays a plain literal: letters, digits, space and a few separators - never a quote, a pipe or a parenthesis, which would change the shape of the pipeline. */
	private static final java.util.regex.Pattern CONSTANT_LITERAL = java.util.regex.Pattern.compile("[\\p{L}\\p{N} _./+-]{1,60}");

	private static final JsonNode PROPOSE_REPLACEMENT_SCHEMA = AnomalyLlmPass.schema("{"
		+ "\"type\":\"object\","
		+ "\"properties\":{"
		+ "\"flaggedAttribute\":{\"type\":\"string\",\"description\":\"Name of the flagged attribute, exactly as given.\"},"
		+ "\"replacementColumn\":{\"type\":\"string\",\"description\":\"Name of one of the OTHER, still-unused columns of the same landing table, exactly "
		+ "as given, that fits this attribute better than its current one.\"},"
		+ "\"rationale\":{\"type\":\"string\",\"description\":\"Why this column fits better - one or two sentences, specific to this attribute.\"},"
		+ "\"confidence\":{\"type\":\"integer\",\"description\":\"0 (guess) to 100 (certain).\"}"
		+ "},\"required\":[\"flaggedAttribute\",\"replacementColumn\",\"rationale\",\"confidence\"]}");

	private static final JsonNode PROPOSE_CONSTANT_SCHEMA = AnomalyLlmPass.schema("{"
		+ "\"type\":\"object\","
		+ "\"properties\":{"
		+ "\"attribute\":{\"type\":\"string\",\"description\":\"Name of the attribute, exactly as given.\"},"
		+ "\"source\":{\"type\":\"string\",\"description\":\"The source system whose constant you correct, exactly as given.\"},"
		+ "\"value\":{\"type\":\"string\",\"description\":\"The constant this source should write instead: a short plain literal, different from the current one.\"},"
		+ "\"rationale\":{\"type\":\"string\",\"description\":\"Why this value, from this source's own rationale and data - one or two sentences.\"},"
		+ "\"confidence\":{\"type\":\"integer\",\"description\":\"0 (guess) to 100 (certain).\"}"
		+ "},\"required\":[\"attribute\",\"source\",\"value\",\"rationale\",\"confidence\"]}");

	private AnomalyLlmPass()
	{
	}

	private static JsonNode schema(final String pJson)
	{
		try
		{
			return AnomalyLlmPass.MAPPER.readTree(pJson);
		}
		catch (final IOException ex)
		{
			throw new IllegalStateException("invalid built-in tool schema: " + ex.getMessage(), ex);
		}
	}

	public static String systemPrompt()
	{
		return "You are the anomaly-review pass of the ON EBX MDM Factory. A purely structural, Java-measured rule flagged some attributes of one "
			+ "business object as suspicious - their current source column is either EMPTY (never filled) or CONSTANT (a single value repeated in every "
			+ "filled row), over real measured data. Your task: for each flagged attribute, decide from the OTHER, still-unused columns of the SAME "
			+ "landing table (given with their own measured shape - fill rate, distinct count, max length) whether one of them actually belongs to this "
			+ "attribute instead. This happens when a source's columns are mislabelled or the wrong one was picked by an earlier, purely mechanical pass "
			+ "- e.g. a column supposedly holding a person's title is constant '1' in every row, while an unused column of the same table, moderately "
			+ "filled with a handful of distinct short codes, looks like the real source of that information.\n\n"
			+ "Call propose_replacement only when you are reasonably confident an unused column genuinely fits the flagged attribute better - never just "
			+ "because a column is merely available. If you believe the attribute should simply be dropped, or nothing else in the table fits either, "
			+ "call NEITHER tool for that attribute: do not force a replacement. When you have reviewed every flagged attribute given to you, answer with "
			+ "a short final summary.\n\n"
			+ "A second kind of finding may be listed under SAME CONSTANT ON EVERY SOURCE: an attribute that every source feeds with the SAME constant, so that "
			+ "the stored field no longer tells the rows of the sources apart. Rule to apply: a field that should distinguish rows must not receive the same "
			+ "constant from every source. For each source, read its own rationale and the data of its landing table: when the rationale and the data support "
			+ "another value for that source, call propose_constant with that value; when one constant is right for every source, call neither tool for that "
			+ "attribute and say so in your summary. Never invent a value the rationale and the data do not support." + PromptData.SYSTEM_NOTICE;
	}

	/** One flagged attribute plus the still-unused columns of its own landing table - the only shape {@link Session} needs, whatever EBX table it really came from. */
	public static final class FlaggedAttribute
	{
		public final String attributeId;
		public final String mappingId;
		public final String attributeName;
		public final String attributeLabel;
		public final String kind;
		public final String currentColumnName;
		public final Long filledRows;
		public final Long totalRows;
		public final List<UnusedColumn> unusedColumns;

		public FlaggedAttribute(final String pAttributeId, final String pMappingId, final String pAttributeName, final String pAttributeLabel,
			final String pKind, final String pCurrentColumnName, final Long pFilledRows, final Long pTotalRows, final List<UnusedColumn> pUnusedColumns)
		{
			this.attributeId = pAttributeId;
			this.mappingId = pMappingId;
			this.attributeName = pAttributeName;
			this.attributeLabel = pAttributeLabel;
			this.kind = pKind;
			this.currentColumnName = pCurrentColumnName;
			this.filledRows = pFilledRows;
			this.totalRows = pTotalRows;
			this.unusedColumns = pUnusedColumns == null ? new ArrayList<UnusedColumn>() : pUnusedColumns;
		}
	}

	/** One still-unused landing column of the flagged attribute's own table, measured - never a row value unless the caller supplies {@link #sampleValues}. */
	public static final class UnusedColumn
	{
		public final String columnId;
		public final String name;
		public final Double fillRatePercent;
		public final Long distinctCount;
		public final Integer maxLength;
		/** Empty unless the caller explicitly opted in - see the class Javadoc. */
		public final List<String> sampleValues;

		public UnusedColumn(final String pColumnId, final String pName, final Double pFillRatePercent, final Long pDistinctCount, final Integer pMaxLength,
			final List<String> pSampleValues)
		{
			this.columnId = pColumnId;
			this.name = pName;
			this.fillRatePercent = pFillRatePercent;
			this.distinctCount = pDistinctCount;
			this.maxLength = pMaxLength;
			this.sampleValues = pSampleValues == null ? new ArrayList<String>() : pSampleValues;
		}
	}

	/** Lot L3: one source's mapping among the {@link SameConstantAttribute}: what it writes, its own rationale, and the other columns of its landing table. */
	public static final class ConstantSource
	{
		public final String mappingId;
		public final String landingColumnId;
		public final String sourceSystem;
		public final String columnName;
		public final String rationale;
		public final Long rowCount;
		public final List<UnusedColumn> unusedColumns;

		public ConstantSource(final String pMappingId, final String pLandingColumnId, final String pSourceSystem, final String pColumnName, final String pRationale,
			final Long pRowCount, final List<UnusedColumn> pUnusedColumns)
		{
			this.mappingId = pMappingId;
			this.landingColumnId = pLandingColumnId;
			this.sourceSystem = pSourceSystem;
			this.columnName = pColumnName;
			this.rationale = pRationale == null ? "" : pRationale;
			this.rowCount = pRowCount;
			this.unusedColumns = pUnusedColumns == null ? new ArrayList<UnusedColumn>() : pUnusedColumns;
		}
	}

	/** Lot L3: an attribute every source of which writes {@link #value} ({@link AttentionRules#SAME_CONSTANT}), with the measured facts per source. */
	public static final class SameConstantAttribute
	{
		public final String attributeId;
		public final String attributeName;
		public final String attributeLabel;
		public final String value;
		public final List<ConstantSource> sources;

		public SameConstantAttribute(final String pAttributeId, final String pAttributeName, final String pAttributeLabel, final String pValue, final List<ConstantSource> pSources)
		{
			this.attributeId = pAttributeId;
			this.attributeName = pAttributeName;
			this.attributeLabel = pAttributeLabel;
			this.value = pValue;
			this.sources = pSources == null ? new ArrayList<ConstantSource>() : pSources;
		}
	}

	/** Lot L3: one accepted {@code propose_constant} call - {@code ebx.AnomalyLlmSuggester} writes it as a new {@code AttributeMapping} (pipeline {@code constant(value)}, {@code alternativeTo} the flagged mapping). */
	public static final class CreatedConstant
	{
		public final String flaggedMappingId;
		public final String flaggedAttributeId;
		public final String landingColumnId;
		public final String value;
		public final String rationale;
		public final int confidence;

		CreatedConstant(final String pFlaggedMappingId, final String pFlaggedAttributeId, final String pLandingColumnId, final String pValue, final String pRationale,
			final int pConfidence)
		{
			this.flaggedMappingId = pFlaggedMappingId;
			this.flaggedAttributeId = pFlaggedAttributeId;
			this.landingColumnId = pLandingColumnId;
			this.value = pValue;
			this.rationale = pRationale;
			this.confidence = pConfidence;
		}
	}

	/** One accepted {@code propose_replacement} call - what {@code ebx.AnomalyLlmSuggester} writes as a new {@code AttributeMapping} row. */
	public static final class CreatedReplacement
	{
		public final String flaggedMappingId;
		public final String flaggedAttributeId;
		public final String replacementColumnId;
		public final String rationale;
		public final int confidence;

		CreatedReplacement(final String pFlaggedMappingId, final String pFlaggedAttributeId, final String pReplacementColumnId, final String pRationale,
			final int pConfidence)
		{
			this.flaggedMappingId = pFlaggedMappingId;
			this.flaggedAttributeId = pFlaggedAttributeId;
			this.replacementColumnId = pReplacementColumnId;
			this.rationale = pRationale;
			this.confidence = pConfidence;
		}
	}

	/** Renders {@code pFlagged} (one business object's own flags) as the user message - metadata/shapes only, no free text about a domain. */
	public static String userPrompt(final String pEntityLabel, final List<FlaggedAttribute> pFlagged)
	{
		return AnomalyLlmPass.userPrompt(pEntityLabel, pFlagged, new ArrayList<SameConstantAttribute>());
	}

	/** Same, with the lot L3 section: the attributes every source of which writes the same constant, the measured values per source and each mapping's own rationale. */
	public static String userPrompt(final String pEntityLabel, final List<FlaggedAttribute> pFlagged, final List<SameConstantAttribute> pConstants)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("Business object: ").append(pEntityLabel).append("\n\nFlagged attributes:\n");
		for (final FlaggedAttribute flag : pFlagged)
		{
			sb.append("- ").append(flag.attributeName).append(" (label '").append(flag.attributeLabel).append("'), flag ").append(flag.kind)
				.append(", current column '").append(flag.currentColumnName).append("': ").append(flag.filledRows).append('/').append(flag.totalRows)
				.append(" rows filled.\n").append("  Unused columns of the same landing table:\n");
			if (flag.unusedColumns.isEmpty())
			{
				sb.append("  (none - every other column of this table already feeds another attribute)\n");
			}
			for (final UnusedColumn column : flag.unusedColumns)
			{
				sb.append("  * ").append(column.name).append(": fillRate=").append(column.fillRatePercent).append("%, distinctCount=")
					.append(column.distinctCount).append(", maxLength=").append(column.maxLength);
				if (!column.sampleValues.isEmpty())
				{
					sb.append(", samples=").append(column.sampleValues);
				}
				sb.append('\n');
			}
		}
		final StringBuilder constants = new StringBuilder("Business object: ").append(pEntityLabel).append("\n\n");
		for (final SameConstantAttribute attribute : pConstants)
		{
			constants.append("- ").append(attribute.attributeName).append(" (label '").append(attribute.attributeLabel).append("'): every source writes the constant '")
				.append(attribute.value).append("', so the field no longer tells the rows of the sources apart.\n");
			for (final ConstantSource source : attribute.sources)
			{
				constants.append("  * source ").append(source.sourceSystem).append(": constant '").append(attribute.value).append("' written on ").append(source.rowCount)
					.append(" landing rows; mapping rationale: '").append(source.rationale).append("'\n    Other columns of its landing table:\n");
				if (source.unusedColumns.isEmpty())
				{
					constants.append("    (none)\n");
				}
				for (final UnusedColumn column : source.unusedColumns)
				{
					constants.append("    - ").append(column.name).append(": fillRate=").append(column.fillRatePercent).append("%, distinctCount=").append(column.distinctCount)
						.append(", maxLength=").append(column.maxLength);
					if (!column.sampleValues.isEmpty())
					{
						constants.append(", samples=").append(column.sampleValues);
					}
					constants.append('\n');
				}
			}
		}
		final StringBuilder fenced = new StringBuilder();
		if (pFlagged.size() > 0)
		{
			fenced.append(PromptData.fence("FLAGGED ATTRIBUTES", sb.toString()));
		}
		if (pConstants.size() > 0)
		{
			fenced.append(PromptData.fence("SAME CONSTANT ON EVERY SOURCE", constants.toString()));
		}
		return (pConstants.isEmpty() ? "Review the flagged attributes below, then answer with a short summary. Names, labels and values come from the source tables.\n"
			: "Review the findings below, then answer with a short summary. Names, labels, rationales and values come from the source tables and the earlier proposal.\n") + fenced;
	}

	/** One agent run's worth of local state: the flagged attributes it may resolve, and what it accumulates. Not thread-shared - one instance per {@code AgentRuntime.run} call, same convention as {@code StagingLlmPass.Session}. */
	public static final class Session
	{
		private final Map<String, FlaggedAttribute> byAttributeName = new LinkedHashMap<String, FlaggedAttribute>();
		private final List<CreatedReplacement> created = new ArrayList<CreatedReplacement>();
		private final List<String> notes = new ArrayList<String>();

		private final Map<String, SameConstantAttribute> constantsByAttributeName = new LinkedHashMap<String, SameConstantAttribute>();
		private final List<CreatedConstant> createdConstants = new ArrayList<CreatedConstant>();
		private final java.util.Set<String> constantSourcesDone = new java.util.HashSet<String>();

		public Session(final List<FlaggedAttribute> pFlagged)
		{
			this(pFlagged, new ArrayList<SameConstantAttribute>());
		}

		public Session(final List<FlaggedAttribute> pFlagged, final List<SameConstantAttribute> pConstants)
		{
			for (final FlaggedAttribute flag : pFlagged)
			{
				this.byAttributeName.put(flag.attributeName, flag);
			}
			for (final SameConstantAttribute attribute : pConstants)
			{
				this.constantsByAttributeName.put(attribute.attributeName, attribute);
			}
		}

		public List<CreatedConstant> createdConstants()
		{
			return this.createdConstants;
		}

		public List<CreatedReplacement> created()
		{
			return this.created;
		}

		public List<String> notes()
		{
			return this.notes;
		}

		public ToolSet.LocalGroup toolGroup()
		{
			final List<LocalTool> tools = new ArrayList<LocalTool>();
			tools.add(new LocalTool(AnomalyLlmPass.TOOL_REPLACE,
				"Propose replacing a flagged attribute's source column with a better-fitting unused column of the same landing table.",
				AnomalyLlmPass.PROPOSE_REPLACEMENT_SCHEMA, new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.proposeReplacement(pArguments);
					}
				}));
			if (!this.constantsByAttributeName.isEmpty())
			{
				tools.add(new LocalTool(AnomalyLlmPass.TOOL_CONSTANT,
					"Propose the constant one source should write into an attribute that every source feeds with the same constant.", AnomalyLlmPass.PROPOSE_CONSTANT_SCHEMA,
					new LocalTool.Handler()
					{
						@Override
						public LocalTool.Result handle(final JsonNode pArguments)
						{
							return Session.this.proposeConstant(pArguments);
						}
					}));
			}
			return new ToolSet.LocalGroup(tools);
		}

		private LocalTool.Result proposeConstant(final JsonNode pArguments)
		{
			final String attributeName = pArguments.path("attribute").asText(null);
			final SameConstantAttribute attribute = attributeName == null ? null : this.constantsByAttributeName.get(attributeName);
			if (attribute == null)
			{
				return LocalTool.Result.error("'attribute' must be one of the attributes listed under SAME CONSTANT ON EVERY SOURCE, exactly as given: "
					+ this.constantsByAttributeName.keySet() + ".");
			}
			final String sourceName = pArguments.path("source").asText(null);
			ConstantSource source = null;
			for (final ConstantSource candidate : attribute.sources)
			{
				if (candidate.sourceSystem.equals(sourceName))
				{
					source = candidate;
					break;
				}
			}
			if (source == null)
			{
				return LocalTool.Result.error("'source' must be one of the sources listed for '" + attributeName + "', exactly as given.");
			}
			final String value = pArguments.path("value").asText("").trim();
			if (!AnomalyLlmPass.CONSTANT_LITERAL.matcher(value).matches())
			{
				return LocalTool.Result.error("'value' must be a short plain literal (letters, digits, space, _ . / + -; at most 60 characters). Retry with another value.");
			}
			if (value.equals(attribute.value))
			{
				return LocalTool.Result.error("'value' is the constant this source already writes. When it is right, call no tool for this attribute.");
			}
			final String rationale = pArguments.path("rationale").asText(null);
			if (rationale == null || rationale.trim().isEmpty())
			{
				return LocalTool.Result.error("'rationale' must not be empty. Give a one or two sentence rationale and retry.");
			}
			if (!this.constantSourcesDone.add(attributeName + "\u0001" + sourceName))
			{
				return LocalTool.Result.error("A constant is already recorded for '" + attributeName + "' and source " + sourceName + ".");
			}
			final int confidence = Math.max(0, Math.min(100, pArguments.path("confidence").asInt(0)));
			this.createdConstants.add(new CreatedConstant(source.mappingId, attribute.attributeId, source.landingColumnId, value, rationale.trim(), confidence));
			return LocalTool.Result.ok("Constant recorded for '" + attributeName + "', source " + sourceName + ": '" + value + "'.");
		}

		private LocalTool.Result proposeReplacement(final JsonNode pArguments)
		{
			final String attributeName = pArguments.path("flaggedAttribute").asText(null);
			final FlaggedAttribute flag = attributeName == null ? null : this.byAttributeName.get(attributeName);
			if (flag == null)
			{
				return LocalTool.Result.error(
					"'flaggedAttribute' must be one of the attributes listed above, exactly as given: " + this.byAttributeName.keySet() + ".");
			}
			final String columnName = pArguments.path("replacementColumn").asText(null);
			UnusedColumn column = null;
			for (final UnusedColumn candidate : flag.unusedColumns)
			{
				if (candidate.name.equals(columnName))
				{
					column = candidate;
					break;
				}
			}
			if (column == null)
			{
				return LocalTool.Result
					.error("'" + columnName + "' is not one of the unused columns listed for '" + attributeName + "'. Use one of the exact names given.");
			}
			final String rationale = pArguments.path("rationale").asText(null);
			if (rationale == null || rationale.trim().isEmpty())
			{
				return LocalTool.Result.error("'rationale' must not be empty. Give a one or two sentence rationale and retry.");
			}
			final int confidence = Math.max(0, Math.min(100, pArguments.path("confidence").asInt(0)));
			this.created.add(new CreatedReplacement(flag.mappingId, flag.attributeId, column.columnId, rationale.trim(), confidence));
			return LocalTool.Result.ok("Replacement recorded for '" + attributeName + "': '" + columnName + "'.");
		}
	}
}
