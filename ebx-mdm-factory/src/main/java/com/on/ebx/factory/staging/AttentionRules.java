package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Staging-proposal lot 0 (docs/STAGING-PROPOSAL.md): purely structural "worth a second look" flags on a
 * landing -&gt; staging mapping, computed from measured numbers alone - fill rate, distinct-value count, row
 * count, which landing column feeds which attribute. Pure: no EBX class, package fits alongside {@code
 * staging.StagingRules}/{@code staging.MappingFollowRule}/{@code staging.TechnicalKeyRule} (the other pure
 * rule engines the staging-proposal machinery already keeps, as opposed to {@code review} - a decision-
 * workflow package about {@code ReviewLine}s, not about the mapping itself).
 * <p>
 * <b>Domain-free by design</b> (a rule the project has followed since the normalisation lot, PLAN.md
 * 2026-09-20: "domain knowledge comes from the language model, Java only measures"): nothing here reads a
 * column or attribute NAME, or any business vocabulary. A column called {@code BusinessPartnerCategory} and
 * one called {@code sku} are judged by the exact same numbers. This is deliberate and load-bearing - see
 * {@code AttentionRulesTest} for worked examples from two unrelated domains (people, and retail articles)
 * producing the same flags for the same shapes of numbers.
 */
public final class AttentionRules
{
	/** The source column holds one single distinct value over at least {@link #MIN_FILLED_ROWS_FOR_CONSTANT} filled rows. */
	public static final String CONSTANT_COLUMN = "CONSTANT_COLUMN";
	/** The source column has zero filled rows. */
	public static final String EMPTY_COLUMN = "EMPTY_COLUMN";
	/** The attribute is fed only by landing column(s) that already feed another attribute of the same entity (see {@link #detect}). */
	public static final String DUPLICATE_FIELD = "DUPLICATE_FIELD";

	/**
	 * The mapping's own pipeline turns filled source values into empty output on at least half of the sampled
	 * values (PersVol3 finding 2026-09-25: a street regex copied from a value shape emptied 1641 of 1651
	 * addresses and the matrix still offered it for bulk accept). Decided by {@link #emptiedByRule}.
	 */
	public static final String EMPTIED_BY_RULE = "EMPTIED_BY_RULE";

	/**
	 * The mapping's pipeline splits a multi-token column whose ORDER cannot be inferred ({@link TokenCase#orderUnknown},
	 * Vol100kN 2026-10-12: {@code LOYALTY.MEMBER.FULL_NAME}, one casing layout, written in both orders with no marker; a
	 * casing regex took the last token as the first part on half the rows and "accept all" let it through). Decided by
	 * {@link #orderUnknownSplit}; the suggested fix is to keep the full value and compare it with an order-independent key.
	 */
	public static final String ORDER_UNKNOWN_SPLIT = "ORDER_UNKNOWN_SPLIT";

	/**
	 * Staging normalisation lot D (2026-10-17): the mapping's chain gives, on the sampled values, text the typed staging
	 * attribute cannot store (measured live: {@code isodate()} on {@code 10/06/1963} left it as it was, a DATE field refused
	 * every row of the source at the load, and the link was not among the flagged points). Decided by {@link
	 * #conversionFails} from {@code TransformVerifier#invalidOutputs} on the sample the matrix already runs.
	 */
	public static final String CONVERSION_FAILS = "CONVERSION_FAILS";

	/**
	 * Validation desk lot L3: an attribute of an entity fed by at least two non-rejected feeds where every feed's mapping is a {@code constant(...)}
	 * with the SAME value ("every source writes MAILING: the field no longer tells the sources apart"). Decided by {@link #detectSameConstant};
	 * every such mapping is flagged, and the measured values travel to the language model with the rule to apply ({@code ebx.AnomalyLlmSuggester}).
	 */
	public static final String SAME_CONSTANT = "SAME_CONSTANT";

	/** {@link #EMPTIED_BY_RULE} fires when {@code emptied * EMPTIED_RATIO_DENOMINATOR >= filled} - i.e. half or more. */
	public static final int EMPTIED_RATIO_DENOMINATOR = 2;

	/** {@link #CONSTANT_COLUMN} never fires below this many filled rows - a single-row sample being constant proves nothing. */
	public static final int MIN_FILLED_ROWS_FOR_CONSTANT = 10;

	private AttentionRules()
	{
	}

	/**
	 * One landing-column-to-attribute mapping, measured - the only shape every rule below reads, whatever
	 * table it really came from ({@code AttributeMapping} joined to its {@code LandingColumn}). No EBX type.
	 */
	public static final class MappingInput
	{
		public final String attributeId;
		public final String landingColumnId;
		public final String entityId;
		/** {@code PROPOSED}/{@code ACCEPTED}/{@code APPLIED}/{@code REJECTED} - a {@code REJECTED} mapping feeds nothing and is ignored by every rule below. */
		public final String status;
		/** 0-100, or {@code null} when the column was never profiled ({@code ebx.LandingColumnProfiler}) - a rule needing it then simply never fires for this row. */
		public final Double fillRatePercent;
		/** {@code null} when never profiled. */
		public final Long distinctCount;
		/** Total rows of the landing table this column belongs to; {@code null} when unknown. */
		public final Long rowCount;
		/** Lot L3: the feed this mapping belongs to - "" when the caller does not know it (the {@link #SAME_CONSTANT} rule then never counts the row). */
		public final String feedId;
		/** Lot L3: the mapping's resolved pipeline, "" when unknown. */
		public final String pipeline;
		/** Lot L3: true for a PROPOSED mapping written as the alternative of another one ({@code AttributeMapping.alternativeTo}): never part of {@link #SAME_CONSTANT}. */
		public final boolean alternative;

		public MappingInput(final String pAttributeId, final String pLandingColumnId, final String pEntityId, final String pStatus,
			final Double pFillRatePercent, final Long pDistinctCount, final Long pRowCount)
		{
			this(pAttributeId, pLandingColumnId, pEntityId, pStatus, pFillRatePercent, pDistinctCount, pRowCount, "", "", false);
		}

		public MappingInput(final String pAttributeId, final String pLandingColumnId, final String pEntityId, final String pStatus,
			final Double pFillRatePercent, final Long pDistinctCount, final Long pRowCount, final String pFeedId, final String pPipeline, final boolean pAlternative)
		{
			this.feedId = pFeedId == null ? "" : pFeedId;
			this.pipeline = pPipeline == null ? "" : pPipeline;
			this.alternative = pAlternative;
			this.attributeId = pAttributeId;
			this.landingColumnId = pLandingColumnId;
			this.entityId = pEntityId;
			this.status = pStatus;
			this.fillRatePercent = pFillRatePercent;
			this.distinctCount = pDistinctCount;
			this.rowCount = pRowCount;
		}

		boolean isRejected()
		{
			return "REJECTED".equals(this.status);
		}

		/** {@code round(fillRatePercent / 100 * rowCount)|null} when either input is missing. */
		Long filledRows()
		{
			if (this.fillRatePercent == null || this.rowCount == null)
			{
				return null;
			}
			return Long.valueOf(Math.round(this.fillRatePercent.doubleValue() / 100.0 * this.rowCount.longValue()));
		}
	}

	/** One flag on one attribute, with the plain numbers behind it - never a formatted sentence (the caller renders it, per the class Javadoc: no vocabulary lives here). */
	public static final class Flag
	{
		/** {@link #CONSTANT_COLUMN}/{@link #EMPTY_COLUMN}/{@link #DUPLICATE_FIELD}. */
		public final String kind;
		public final String entityId;
		public final String attributeId;
		public final String landingColumnId;
		/** {@link #CONSTANT_COLUMN}/{@link #EMPTY_COLUMN} only - the filled/total rows and distinct-value count behind the flag; null for {@link #DUPLICATE_FIELD}. */
		public final Long filledRows;
		public final Long totalRows;
		public final Long distinctCount;
		/** {@link #DUPLICATE_FIELD} only - every OTHER attribute (of the same entity) this flag's {@link #landingColumnId} also feeds; empty otherwise. */
		public final List<String> sharedWithAttributeIds;
		/** {@link #SAME_CONSTANT} only - the value every source writes, and how many feeds write it; "" and 0 otherwise. */
		public final String constantValue;
		public final int feedCount;

		private Flag(final String pKind, final String pEntityId, final String pAttributeId, final String pLandingColumnId, final Long pFilledRows,
			final Long pTotalRows, final Long pDistinctCount, final List<String> pSharedWithAttributeIds)
		{
			this(pKind, pEntityId, pAttributeId, pLandingColumnId, pFilledRows, pTotalRows, pDistinctCount, pSharedWithAttributeIds, "", 0);
		}

		private Flag(final String pKind, final String pEntityId, final String pAttributeId, final String pLandingColumnId, final Long pFilledRows,
			final Long pTotalRows, final Long pDistinctCount, final List<String> pSharedWithAttributeIds, final String pConstantValue, final int pFeedCount)
		{
			this.constantValue = pConstantValue;
			this.feedCount = pFeedCount;
			this.kind = pKind;
			this.entityId = pEntityId;
			this.attributeId = pAttributeId;
			this.landingColumnId = pLandingColumnId;
			this.filledRows = pFilledRows;
			this.totalRows = pTotalRows;
			this.distinctCount = pDistinctCount;
			this.sharedWithAttributeIds = pSharedWithAttributeIds;
		}

		static Flag column(final String pKind, final MappingInput pRow, final Long pFilledRows)
		{
			return new Flag(pKind, pRow.entityId, pRow.attributeId, pRow.landingColumnId, pFilledRows, pRow.rowCount, pRow.distinctCount,
				java.util.Collections.<String> emptyList());
		}

		static Flag sameConstant(final MappingInput pRow, final String pValue, final int pFeedCount)
		{
			return new Flag(AttentionRules.SAME_CONSTANT, pRow.entityId, pRow.attributeId, pRow.landingColumnId, null, pRow.rowCount, null,
				java.util.Collections.<String> emptyList(), pValue, pFeedCount);
		}

		static Flag duplicate(final String pEntityId, final String pAttributeId, final String pLandingColumnId, final List<String> pSharedWith)
		{
			return new Flag(AttentionRules.DUPLICATE_FIELD, pEntityId, pAttributeId, pLandingColumnId, null, null, null, pSharedWith);
		}
	}

	/**
	 * {@link #EMPTIED_BY_RULE}: true when {@code pPipeline} is a real transformation (non-blank, not a
	 * {@code constant(...)} - a constant ignores its input on purpose), at least one sampled input value was
	 * filled, and half or more of those filled inputs came out empty. Pure numbers, no value is read here.
	 */
	public static boolean emptiedByRule(final String pPipeline, final int pFilledInputs, final int pEmptiedOutputs)
	{
		if (pPipeline == null || pPipeline.trim().isEmpty())
		{
			return false;
		}
		if (pPipeline.trim().toLowerCase(java.util.Locale.ROOT).startsWith("constant("))
		{
			return false;
		}
		return pFilledInputs >= 1 && pEmptiedOutputs * AttentionRules.EMPTIED_RATIO_DENOMINATOR >= pFilledInputs;
	}

	/** {@link #CONVERSION_FAILS}: at least one sampled output is not a form the typed attribute accepts. Pure numbers. */
	public static boolean conversionFails(final int pInvalidOutputs)
	{
		return pInvalidOutputs > 0;
	}

	/**
	 * {@link #ORDER_UNKNOWN_SPLIT}: true when {@code pPipeline} holds a {@code regex(...)} step ({@link
	 * PartOrder#hasRegexStep}), the sampled column is {@link TokenCase#orderUnknown} and the rule's output is a strict
	 * part of the input in at least {@link PartOrder#SPLIT_MIN_PERCENT} percent of the {@code pMultiTokenInputs} sampled
	 * multi-token values ({@code pParts} of them). Pure numbers: no value is read here, no name either.
	 */
	public static boolean orderUnknownSplit(final String pPipeline, final boolean pOrderUnknown, final int pParts, final int pMultiTokenInputs)
	{
		return pOrderUnknown && PartOrder.hasRegexStep(pPipeline) && PartOrder.isSplit(pParts, pMultiTokenInputs);
	}

	/**
	 * Every flag {@code pMappings} raises, in input order within each rule (a {@code REJECTED} row is
	 * ignored by every rule - see {@link MappingInput#status}). {@link #CONSTANT_COLUMN}/{@link
	 * #EMPTY_COLUMN} are evaluated per row independently; {@link #DUPLICATE_FIELD} is evaluated per {@link
	 * MappingInput#entityId} (two mappings of different entities that happen to share an id - never possible
	 * in practice, ids are unique across the whole project - are never compared to each other).
	 */
	public static List<Flag> detect(final List<MappingInput> pMappings)
	{
		final List<Flag> out = new ArrayList<Flag>();
		if (pMappings == null)
		{
			return out;
		}
		for (final MappingInput row : pMappings)
		{
			if (row.isRejected())
			{
				continue;
			}
			final Long filled = row.filledRows();
			if (filled == null)
			{
				continue;
			}
			if (filled.longValue() == 0L)
			{
				out.add(Flag.column(AttentionRules.EMPTY_COLUMN, row, filled));
			}
			else if (row.distinctCount != null && row.distinctCount.longValue() == 1L && filled.longValue() >= AttentionRules.MIN_FILLED_ROWS_FOR_CONSTANT)
			{
				out.add(Flag.column(AttentionRules.CONSTANT_COLUMN, row, filled));
			}
		}
		out.addAll(AttentionRules.detectDuplicateFields(pMappings));
		out.addAll(AttentionRules.detectSameConstant(pMappings));
		return out;
	}

	/**
	 * The value of a {@code constant(...)} pipeline made of that single step ({@code constant(MAILING)}, {@code constant('MAILING')}, {@code constant("MAILING")}),
	 * trimmed; {@code null} for anything else - a chain, another step, an empty constant. Reads the shape of the rule, never a word of its value.
	 */
	public static String constantValueOf(final String pPipeline)
	{
		if (pPipeline == null)
		{
			return null;
		}
		final String text = pPipeline.trim();
		if (!text.toLowerCase(java.util.Locale.ROOT).startsWith("constant(") || !text.endsWith(")"))
		{
			return null;
		}
		String inner = text.substring("constant(".length(), text.length() - 1).trim();
		if (inner.length() >= 2 && (inner.charAt(0) == '\'' || inner.charAt(0) == '"') && inner.charAt(inner.length() - 1) == inner.charAt(0))
		{
			final char quote = inner.charAt(0);
			inner = inner.substring(1, inner.length() - 1);
			if (inner.indexOf(quote) >= 0)
			{
				return null; // a quote inside: not a single plain literal
			}
		}
		else if (inner.indexOf('|') >= 0 || inner.indexOf('(') >= 0 || inner.indexOf(')') >= 0)
		{
			return null;
		}
		inner = inner.trim();
		return inner.isEmpty() ? null : inner;
	}

	/**
	 * {@link #SAME_CONSTANT}: per (entity, attribute), the non-rejected, non-alternative mappings; when they come from at least two distinct feeds and every one is a
	 * {@link #constantValueOf constant} with the same value, each of them is flagged (input order). Needs the whole entity's picture, like {@link #DUPLICATE_FIELD}.
	 */
	private static List<Flag> detectSameConstant(final List<MappingInput> pMappings)
	{
		final Map<String, List<MappingInput>> byAttribute = new LinkedHashMap<String, List<MappingInput>>();
		for (final MappingInput row : pMappings)
		{
			if (row.isRejected() || row.alternative || row.feedId.isEmpty())
			{
				continue;
			}
			AttentionRules.subList2(byAttribute, row.entityId + "\u0001" + row.attributeId).add(row);
		}
		final List<Flag> out = new ArrayList<Flag>();
		for (final List<MappingInput> rows : byAttribute.values())
		{
			final Set<String> feeds = new LinkedHashSet<String>();
			String common = null;
			boolean same = true;
			for (final MappingInput row : rows)
			{
				final String value = AttentionRules.constantValueOf(row.pipeline);
				if (value == null || (common != null && !common.equals(value)))
				{
					same = false;
					break;
				}
				common = value;
				feeds.add(row.feedId);
			}
			if (same && common != null && feeds.size() >= 2)
			{
				for (final MappingInput row : rows)
				{
					out.add(Flag.sameConstant(row, common, feeds.size()));
				}
			}
		}
		return out;
	}

	private static List<MappingInput> subList2(final Map<String, List<MappingInput>> pOuter, final String pKey)
	{
		List<MappingInput> inner = pOuter.get(pKey);
		if (inner == null)
		{
			inner = new ArrayList<MappingInput>();
			pOuter.put(pKey, inner);
		}
		return inner;
	}

	/**
	 * See the class Javadoc / {@link #DUPLICATE_FIELD}: within one entity, a landing column fed to two or
	 * more distinct attributes (through non-{@code REJECTED} mappings) is "shared". The FIRST attribute to
	 * claim a shared column, in {@code pMappings}' own input order, is that column's "owner" and is never
	 * flagged for it; every attribute that claims an already-owned column afterwards is flagged - but ONLY
	 * when every one of ITS OWN columns is likewise an already-owned, shared column (an attribute with at
	 * least one column nobody else uses is never flagged, whatever else it also shares). This is exactly
	 * "flag the attribute that is fed ONLY by columns that already feed another attribute" (task brief) and
	 * never flags both sides of a two-attribute share: of any pair sharing one column with no other source,
	 * the first-claiming attribute is spared, the second is flagged.
	 */
	private static List<Flag> detectDuplicateFields(final List<MappingInput> pMappings)
	{
		// pass 1: for each (entity, column), the attributes that claim it, in first-seen order.
		final Map<String, Map<String, List<String>>> claimsByEntityThenColumn = new LinkedHashMap<String, Map<String, List<String>>>();
		// pass 1 also collects, per (entity, attribute), the distinct columns it is fed by - in first-seen order.
		final Map<String, Map<String, List<String>>> columnsByEntityThenAttribute = new LinkedHashMap<String, Map<String, List<String>>>();
		for (final MappingInput row : pMappings)
		{
			if (row.isRejected())
			{
				continue;
			}
			final Map<String, List<String>> claimsByColumn = AttentionRules.subMap(claimsByEntityThenColumn, row.entityId);
			final List<String> claimants = AttentionRules.subList(claimsByColumn, row.landingColumnId);
			if (!claimants.contains(row.attributeId))
			{
				claimants.add(row.attributeId);
			}
			final Map<String, List<String>> columnsByAttribute = AttentionRules.subMap(columnsByEntityThenAttribute, row.entityId);
			final List<String> columns = AttentionRules.subList(columnsByAttribute, row.attributeId);
			if (!columns.contains(row.landingColumnId))
			{
				columns.add(row.landingColumnId);
			}
		}

		final List<Flag> out = new ArrayList<Flag>();
		for (final Map.Entry<String, Map<String, List<String>>> perEntity : columnsByEntityThenAttribute.entrySet())
		{
			final String entityId = perEntity.getKey();
			final Map<String, List<String>> claimsByColumn = claimsByEntityThenColumn.get(entityId);
			for (final Map.Entry<String, List<String>> perAttribute : perEntity.getValue().entrySet())
			{
				final String attributeId = perAttribute.getKey();
				final List<String> columns = perAttribute.getValue();
				boolean everyColumnIsAnAlreadyOwnedShare = !columns.isEmpty();
				final Set<String> sharedWith = new LinkedHashSet<String>();
				String lastSharedColumn = null;
				for (final String columnId : columns)
				{
					final List<String> claimants = claimsByColumn.get(columnId);
					final boolean shared = claimants != null && claimants.size() > 1;
					final boolean isOwner = shared && claimants.get(0).equals(attributeId);
					if (!shared || isOwner)
					{
						everyColumnIsAnAlreadyOwnedShare = false;
						continue;
					}
					lastSharedColumn = columnId;
					for (final String other : claimants)
					{
						if (!other.equals(attributeId))
						{
							sharedWith.add(other);
						}
					}
				}
				if (everyColumnIsAnAlreadyOwnedShare)
				{
					out.add(Flag.duplicate(entityId, attributeId, lastSharedColumn, new ArrayList<String>(sharedWith)));
				}
			}
		}
		return out;
	}

	private static <K, V> Map<K, V> subMap(final Map<K, Map<K, V>> pOuter, final K pKey)
	{
		Map<K, V> inner = pOuter.get(pKey);
		if (inner == null)
		{
			inner = new LinkedHashMap<K, V>();
			pOuter.put(pKey, inner);
		}
		return inner;
	}

	private static List<String> subList(final Map<String, List<String>> pOuter, final String pKey)
	{
		List<String> inner = pOuter.get(pKey);
		if (inner == null)
		{
			inner = new ArrayList<String>();
			pOuter.put(pKey, inner);
		}
		return inner;
	}
}
