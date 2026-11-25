package com.on.ebx.factory.rejects;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.factory.wizard.WizardMessages;

/**
 * Staging load and rejects lot (2026-10-17), item 3: WHY rows were set aside, counted by {@link RejectCategory}, and the sentence that says so. The wizard said "N rows set aside -
 * a configured rule stopped them" for rows no rule had touched (EBX refused a date); the sentence is now built from the real causes, in the viewer's language.
 * <p>
 * <b>For the wizard (lot C).</b> The counts come from two places that agree: the load result ({@code entities[].rejectedBy}, {@link #of(JsonNode)}) and the stored rejects
 * ({@code ebx.RejectCauseReader}, the unresolved STAGING rejects of a project, which is what the "held back" number counts). {@link #sentence} is the whole sentence
 * ("140 rows set aside: 140 refused by EBX (a value does not fit the type of its field)."); the key {@code wizard.step4.build.heldBack.banner.*} (which blames a rule) is the
 * one to stop using. Pure, EBX-free.
 */
public final class RejectCauses
{
	private final Map<String, Long> byCategory = new LinkedHashMap<String, Long>();

	public RejectCauses()
	{
	}

	/** One count added to a category (an unknown or empty category counts as {@link RejectCategory#OTHER}). */
	public RejectCauses add(final String pCategory, final long pCount)
	{
		if (pCount > 0)
		{
			final String category = RejectCategory.of(pCategory);
			final Long soFar = this.byCategory.get(category);
			this.byCategory.put(category, Long.valueOf(soFar == null ? pCount : soFar.longValue() + pCount));
		}
		return this;
	}

	/** Everything {@code pOther} counted, added. */
	public RejectCauses addAll(final RejectCauses pOther)
	{
		for (final Map.Entry<String, Long> entry : pOther.byCategory.entrySet())
		{
			this.add(entry.getKey(), entry.getValue().longValue());
		}
		return this;
	}

	/** The counts of the {@code rejectedBy} object of a load result's entity ({@code {"EBX_REFUSAL":140}}); null or anything else reads as no cause. */
	public static RejectCauses of(final JsonNode pRejectedBy)
	{
		final RejectCauses out = new RejectCauses();
		if (pRejectedBy != null && pRejectedBy.isObject())
		{
			final java.util.Iterator<Map.Entry<String, JsonNode>> fields = pRejectedBy.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				out.add(field.getKey(), field.getValue().asLong(0L));
			}
		}
		return out;
	}

	public long total()
	{
		long total = 0L;
		for (final Long count : this.byCategory.values())
		{
			total += count.longValue();
		}
		return total;
	}

	public long count(final String pCategory)
	{
		final Long count = this.byCategory.get(RejectCategory.of(pCategory));
		return count == null ? 0L : count.longValue();
	}

	/** The categories that have rows, in the order of {@link RejectCategory#ALL}, with their counts. */
	public Map<String, Long> counts()
	{
		final Map<String, Long> out = new LinkedHashMap<String, Long>();
		for (final String category : RejectCategory.ALL)
		{
			final Long count = this.byCategory.get(category);
			if (count != null && count.longValue() > 0L)
			{
				out.put(category, count);
			}
		}
		return out;
	}

	/** True when at least one row was set aside by a QUALITY RULE (only then may a screen say "a rule stopped them"). */
	public boolean blamesARule()
	{
		return this.count(RejectCategory.QUALITY_RULE) > 0L;
	}

	/** "140 rows set aside: 140 refused by EBX (...)." in {@code pLocale}; "" when nothing was set aside. */
	public String sentence(final Locale pLocale)
	{
		return RejectCauses.sentence(pLocale, this.counts());
	}

	/**
	 * Staging integration (2026-10-17): the cause(s) alone, without the "N rows set aside" lead - "refus d'EBX, une valeur ne convient pas au type de son champ" - for a
	 * sentence that already says how many rows (the step 4 banner). "" when nothing was set aside.
	 */
	public String causePhrase(final Locale pLocale)
	{
		return RejectCauses.phrase(pLocale, this.counts());
	}

	/** {@link #sentence(Locale)} from plain counts by category. */
	public static String sentence(final Locale pLocale, final Map<String, Long> pCounts)
	{
		long total = 0L;
		int kinds = 0;
		for (final String category : RejectCategory.ALL)
		{
			final Long count = pCounts == null ? null : pCounts.get(category);
			if (count != null && count.longValue() > 0L)
			{
				total += count.longValue();
				kinds++;
			}
		}
		if (total <= 0L)
		{
			return "";
		}
		final StringBuilder parts = new StringBuilder();
		for (final String category : RejectCategory.ALL)
		{
			final Long count = pCounts.get(category);
			if (count == null || count.longValue() <= 0L)
			{
				continue;
			}
			if (parts.length() > 0)
			{
				parts.append("; ");
			}
			// one cause: its description says it all; several: each is followed by its count
			final String description = WizardMessages.get(pLocale, "rejects.cause." + category);
			parts.append(kinds == 1 ? description : WizardMessages.get(pLocale, "rejects.cause.part", description, count));
		}
		return WizardMessages.plural(pLocale, total, "rejects.cause.lead.one", "rejects.cause.lead.other", parts.toString());
	}

	/** The causes joined as in {@link #sentence(Locale, Map)}, without the lead. */
	static String phrase(final Locale pLocale, final Map<String, Long> pCounts)
	{
		int kinds = 0;
		for (final String category : RejectCategory.ALL)
		{
			final Long count = pCounts == null ? null : pCounts.get(category);
			if (count != null && count.longValue() > 0L)
			{
				kinds++;
			}
		}
		final StringBuilder parts = new StringBuilder();
		for (final String category : RejectCategory.ALL)
		{
			final Long count = pCounts == null ? null : pCounts.get(category);
			if (count == null || count.longValue() <= 0L)
			{
				continue;
			}
			if (parts.length() > 0)
			{
				parts.append("; ");
			}
			final String description = WizardMessages.get(pLocale, "rejects.cause." + category);
			parts.append(kinds == 1 ? description : WizardMessages.get(pLocale, "rejects.cause.part", description, count));
		}
		return parts.toString();
	}
}
