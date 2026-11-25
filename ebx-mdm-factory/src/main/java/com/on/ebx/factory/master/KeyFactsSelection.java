package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.matching.KeyFacts;

/**
 * Lot M3 (2026-10-17, {@code docs/MATCHING.md} &sect;56): which attributes of an entity are worth measuring as EXACT KEYS ({@link KeyFacts}) - decided from the
 * attribute's role, type, length and measured profile, never from its name.
 * <p>
 * A candidate key is an attribute that two records could agree on exactly and that is not a per-source record key, a reference to another entity, a free text, a flag
 * or a measure: role {@code BUSINESS}, {@code MATCH_KEY} or a derived child-keys attribute; type {@code STRING}, {@code DATE} or {@code INTEGER}; a maximum length of
 * at most {@link #MAX_KEY_LENGTH} (2000 for child keys); filled on at least {@link #MIN_FILL} of the rows when that is reliably measured; neither constant nor, among its
 * filled values, entirely unique (a value that never repeats cannot match anything); a source attribute that has its own matching key is represented by that key
 * and left out. Derived keys and child keys come first (they are the normalised form of what they stand for), then the best-filled ones; at most
 * {@link KeyFacts#MAX_KEYS}.
 */
public final class KeyFactsSelection
{
	/** A text longer than this is a free text, not a key. */
	public static final int MAX_KEY_LENGTH = 255;
	/** A child-keys attribute holds several opaque tokens. */
	public static final int MAX_CHILD_KEYS_LENGTH = 4000;
	/** An attribute filled on fewer rows than this (when reliably measured) is not worth a key. */
	public static final double MIN_FILL = 0.05;

	private KeyFactsSelection()
	{
	}

	/** The {@link KeyFacts.KeySpec}s of the candidate keys of {@code pEntity}, best first; empty when none. */
	public static List<KeyFacts.KeySpec> select(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		final List<MatchPlanLlmPass.AttributeInfo> chosen = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		for (final MatchPlanLlmPass.AttributeInfo attribute : pEntity.attributes)
		{
			if (KeyFactsSelection.eligible(pEntity, attribute))
			{
				chosen.add(attribute);
			}
		}
		Collections.sort(chosen, new Comparator<MatchPlanLlmPass.AttributeInfo>()
		{
			@Override
			public int compare(final MatchPlanLlmPass.AttributeInfo pA, final MatchPlanLlmPass.AttributeInfo pB)
			{
				final int rank = Integer.compare(KeyFactsSelection.rank(pA), KeyFactsSelection.rank(pB));
				if (rank != 0)
				{
					return rank;
				}
				final double fillA = pA.fillRate == null ? 0d : pA.fillRate.doubleValue();
				final double fillB = pB.fillRate == null ? 0d : pB.fillRate.doubleValue();
				return Double.compare(fillB, fillA);
			}
		});
		final List<KeyFacts.KeySpec> out = new ArrayList<KeyFacts.KeySpec>();
		for (final MatchPlanLlmPass.AttributeInfo attribute : chosen)
		{
			if (out.size() >= KeyFacts.MAX_KEYS)
			{
				break;
			}
			out.add(new KeyFacts.KeySpec(attribute.name, attribute.derivedFrom, attribute.isChildKeys()));
		}
		return out;
	}

	private static int rank(final MatchPlanLlmPass.AttributeInfo pAttribute)
	{
		return pAttribute.isChildKeys() || MatchPlanLlmPass.AttributeInfo.ROLE_MATCH_KEY.equals(pAttribute.role) ? 0 : 1;
	}

	/**
	 * Lot M17 (2026-10-18, {@code docs/MATCHING.md} &sect;69): the attributes that would be exact keys but are left out of {@link #select} ONLY because every one of their filled values is
	 * unique (measured, EXACT cardinality and fill): two records of the table can never share them, whatever the source systems. A table whose keys are all of this kind has no key
	 * to measure, yet it is measured that no two of its rows share one.
	 */
	public static List<String> uniqueKeys(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		final List<String> out = new ArrayList<String>();
		for (final MatchPlanLlmPass.AttributeInfo attribute : pEntity.attributes)
		{
			if (!attribute.isChildKeys() && !KeyFactsSelection.eligible(pEntity, attribute) && KeyFactsSelection.eligible(pEntity, attribute, true)
				&& Boolean.TRUE.equals(attribute.neverRepeats(pEntity.rowCount)))
			{
				out.add(attribute.name);
			}
		}
		return out;
	}

	static boolean eligible(final MatchPlanLlmPass.EntityInfo pEntity, final MatchPlanLlmPass.AttributeInfo pAttribute)
	{
		return KeyFactsSelection.eligible(pEntity, pAttribute, false);
	}

	private static boolean eligible(final MatchPlanLlmPass.EntityInfo pEntity, final MatchPlanLlmPass.AttributeInfo pAttribute, final boolean pAllowUnique)
	{
		final String role = pAttribute.role;
		if (!MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS.equals(role) && !MatchPlanLlmPass.AttributeInfo.ROLE_MATCH_KEY.equals(role))
		{
			return false;
		}
		if (pAttribute.referenceEntity != null)
		{
			return false;
		}
		final String type = pAttribute.dataType == null ? "STRING" : pAttribute.dataType.toUpperCase(Locale.ROOT);
		if (!"STRING".equals(type) && !"DATE".equals(type) && !"INTEGER".equals(type))
		{
			return false;
		}
		final int ceiling = pAttribute.isChildKeys() ? KeyFactsSelection.MAX_CHILD_KEYS_LENGTH : KeyFactsSelection.MAX_KEY_LENGTH;
		if (pAttribute.maxLength != null && pAttribute.maxLength.intValue() > ceiling)
		{
			return false;
		}
		if (pAttribute.fillRateReliable() && pAttribute.fillRate.doubleValue() < KeyFactsSelection.MIN_FILL)
		{
			return false;
		}
		// a source attribute that has its own matching key is represented by the key
		if (pAttribute.matchKey != null && pEntity.attribute(pAttribute.matchKey) != null)
		{
			return false;
		}
		if (!pAttribute.isChildKeys() && pAttribute.cardinalityReliable())
		{
			if (pAttribute.distinctCount.intValue() <= 1)
			{
				return false;
			}
			if (!pAllowUnique && Boolean.TRUE.equals(pAttribute.neverRepeats(pEntity.rowCount)))
			{
				return false;
			}
		}
		return true;
	}
}
