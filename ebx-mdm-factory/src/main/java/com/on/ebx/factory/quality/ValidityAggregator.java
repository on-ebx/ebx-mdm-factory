package com.on.ebx.factory.quality;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates a bag of (source system, distinct value, occurrence count) triples into a per-system
 * {@code checked}/{@code valid} count against a fixed list of {@link QualityRuleCheck.Rule}s - the shared
 * arithmetic behind lot 1's "quality-informed survivorship" ({@code docs/MATCHING.md} &sect;23): both {@code
 * ebx.CrossSystemMeasures#perSystemValidityCounts} (grouped {@code SELECT ... GROUP BY sourceSystem, field}
 * rows off the live MASTER table) and {@link ValidValueDerivation} (sampled, capped distinct values off a
 * mapping's own landing data, the same shape {@code quality.CrossSystemFold} already consumes) feed this ONE
 * method rather than each re-implementing "run every rule on every distinct value, weighted by how many rows
 * carry it" - the two callers disagree only on WHERE the triples come from, never on how they are scored.
 * <p>
 * Pure: no EBX class, {@link QualityRuleCheck#passes} does the actual per-value evaluation (it is itself pure
 * - see its own Javadoc). A value is "valid" only when it passes EVERY rule given, not merely one of them - the
 * same "every declared rule must agree" reading {@code ebx.StagingLoader.planRow} already applies when it
 * records a rule's own {@code lastLoadFailing} counter per rule (this class just does it for several rules of
 * the SAME field at once, since a {@code *Valid} derived field and the prompt's own per-system validity line
 * both ask "is this value clean", not "which one rule did it fail").
 * <p>
 * A blank/null value, or a non-positive count, is never counted at all (same "empty is not a reject" rule
 * {@link QualityRuleCheck#passes} and {@code Transformations} already apply one layer down) - a value that was
 * never really there contributes to neither {@code checked} nor {@code valid}.
 */
public final class ValidityAggregator
{
	private ValidityAggregator()
	{
	}

	/** One distinct value of one source system, with how many rows carry it - the common shape both live callers (SQL rows, sampled landing values) are reduced to before reaching {@link #aggregate}. */
	public static final class DistinctValue
	{
		public final String sourceSystem;
		public final String value;
		public final long count;

		public DistinctValue(final String pSourceSystem, final String pValue, final long pCount)
		{
			this.sourceSystem = pSourceSystem;
			this.value = pValue;
			this.count = pCount;
		}
	}

	/** One source system's own {@code checked}/{@code valid} totals - {@code valid <= checked} always; a system with {@code checked == 0} was present in the input but never contributed a non-blank value to score. */
	public static final class SystemValidity
	{
		public final long checked;
		public final long valid;

		public SystemValidity(final long pChecked, final long pValid)
		{
			this.checked = pChecked;
			this.valid = pValid;
		}
	}

	/**
	 * @param pValues one entry per DISTINCT (source system, value) pair, with its own occurrence count - never
	 *            pre-aggregated by the caller; this method itself sums by source system. Null/empty: every
	 *            system reads as "not measured" (an empty map, never a fabricated zero).
	 * @param pRules the field's own accepted validity rule(s) ({@code REFERENCE}/{@code CHECK_DIGIT}/{@code
	 *            PATTERN} - {@code QualityRuleCheck}'s own Javadoc on which kinds {@link QualityRuleCheck#passes}
	 *            actually evaluates); null/empty: nothing to score, returns an empty map (there is no "always
	 *            valid" default here - a caller with no rules must not call this method expecting a meaningful
	 *            answer, and none of this lot's two callers ever does).
	 * @return one entry per source system that contributed at least one non-blank value - insertion order of
	 *         first appearance in {@code pValues}, never re-sorted.
	 */
	public static Map<String, SystemValidity> aggregate(final List<DistinctValue> pValues, final List<QualityRuleCheck.Rule> pRules,
		final CleaningSteps.Lookups pLookups)
	{
		final Map<String, long[]> counters = new LinkedHashMap<String, long[]>();
		if (pValues != null && pRules != null && !pRules.isEmpty())
		{
			for (final DistinctValue distinctValue : pValues)
			{
				if (distinctValue == null || distinctValue.sourceSystem == null || distinctValue.value == null || distinctValue.value.trim().isEmpty()
					|| distinctValue.count <= 0)
				{
					continue;
				}
				boolean ok = true;
				for (final QualityRuleCheck.Rule rule : pRules)
				{
					if (!QualityRuleCheck.passes(rule, distinctValue.value, pLookups))
					{
						ok = false;
						break;
					}
				}
				long[] pair = counters.get(distinctValue.sourceSystem);
				if (pair == null)
				{
					pair = new long[2]; // [checked, valid]
					counters.put(distinctValue.sourceSystem, pair);
				}
				pair[0] += distinctValue.count;
				if (ok)
				{
					pair[1] += distinctValue.count;
				}
			}
		}
		final Map<String, SystemValidity> out = new LinkedHashMap<String, SystemValidity>();
		for (final Map.Entry<String, long[]> entry : counters.entrySet())
		{
			out.put(entry.getKey(), new SystemValidity(entry.getValue()[0], entry.getValue()[1]));
		}
		return out;
	}
}
