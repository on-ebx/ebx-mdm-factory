package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.Detectors;

/**
 * Staging feed-conversion lot E (2026-10-17, coordinator addition (a)): runs the WHOLE chain of cleaning steps of one mapping,
 * in position order, on the RAW values of its landing column, and says what each step does AFTER the ones before it. Measured
 * live (TiersB2CRetestStaging): the rule pass kept the {@code +} of the phone numbers ({@code STRIP_CHARS} 110/111), then a
 * language-model {@code NORMALISE_IDENTIFIER} with argument {@code " .+-"} (112/113) stripped it anyway: the lot D check only
 * looked at {@code STRIP_CHARS}, and judged the pipeline on values the steps had already cleaned, so the {@code +} was gone
 * before it looked.
 * <p>
 * Two findings per step, both generic (a regular expression over the VALUES, never a column name):
 * <ul>
 * <li>{@link StepReport#plusLostRows}: rows whose value still started with {@code +} when the step got it and no longer does -
 * for a phone-like column that is the country of the number;</li>
 * <li>{@link StepReport#changedRows} == 0: the step changes nothing once the earlier steps have run - redundant on the sample.</li>
 * </ul>
 * Pure: the steps are applied with {@link CleaningSteps#applyStep}, the engine the loader runs. Counts only; no value leaves.
 */
public final class StepChainCheck
{
	private StepChainCheck()
	{
	}

	/** What one step did, in the chain, to the distinct values of the column (weighted by their rows). */
	public static final class StepReport
	{
		public final int index;
		public final String kind;
		/** Rows that reached the step non-empty. */
		public final long seenRows;
		/** Rows the step changed (a value turned empty counts). */
		public final long changedRows;
		/** Rows that started with {@code +} when the step got them and do not any more. */
		public final long plusLostRows;

		StepReport(final int pIndex, final String pKind, final long pSeen, final long pChanged, final long pPlusLost)
		{
			this.index = pIndex;
			this.kind = pKind;
			this.seenRows = pSeen;
			this.changedRows = pChanged;
			this.plusLostRows = pPlusLost;
		}

		/** True when the step changes none of the rows that reach it. */
		public boolean redundant()
		{
			return this.seenRows > 0L && this.changedRows == 0L;
		}
	}

	/** One report per step of {@code pSteps}, same order. Values are trimmed first, like the loader does; an empty value never reaches a step. */
	public static List<StepReport> analyse(final List<CleaningSteps.Step> pSteps, final List<Detectors.ValueCount> pRawValues, final CleaningSteps.Lookups pLookups)
	{
		final List<StepReport> out = new ArrayList<StepReport>();
		if (pSteps == null || pSteps.isEmpty())
		{
			return out;
		}
		// current value -> rows; null never kept
		Map<String, Long> current = new LinkedHashMap<String, Long>();
		for (final Detectors.ValueCount value : pRawValues == null ? new ArrayList<Detectors.ValueCount>() : pRawValues)
		{
			if (value.value == null || value.value.trim().isEmpty())
			{
				continue;
			}
			StepChainCheck.add(current, value.value, value.count);
		}
		for (int i = 0; i < pSteps.size(); i++)
		{
			final CleaningSteps.Step step = pSteps.get(i);
			long seen = 0L;
			long changed = 0L;
			long plusLost = 0L;
			final Map<String, Long> next = new LinkedHashMap<String, Long>();
			for (final Map.Entry<String, Long> entry : current.entrySet())
			{
				final String before = entry.getKey();
				final long rows = entry.getValue().longValue();
				seen += rows;
				final CleaningSteps.Result result = CleaningSteps.applyStep(step.kind, step.argument, before, null, pLookups);
				final String after = result.value;
				final boolean empty = after == null || after.trim().isEmpty();
				if (empty || !after.equals(before))
				{
					changed += rows;
				}
				if (before.startsWith("+") && (empty || !after.startsWith("+")))
				{
					plusLost += rows;
				}
				if (!empty)
				{
					StepChainCheck.add(next, after, rows);
				}
			}
			out.add(new StepReport(i, step.kind == null ? "" : step.kind.toUpperCase(Locale.ROOT), seen, changed, plusLost));
			current = next;
		}
		return out;
	}

	private static void add(final Map<String, Long> pMap, final String pValue, final long pRows)
	{
		final String value = pValue.trim();
		final Long seen = pMap.get(value);
		pMap.put(value, Long.valueOf((seen == null ? 0L : seen.longValue()) + pRows));
	}

	/**
	 * The argument of a character-stripping step ({@code STRIP_CHARS}, {@code NORMALISE_IDENTIFIER}) without the {@code +}, or null when
	 * the step is not of those kinds or its argument holds no {@code +}. Never invents a character: it only removes the plus. A blank
	 * result means "the step's own default" for {@code NORMALISE_IDENTIFIER} (space, dot, dash) and "nothing to strip" for {@code
	 * STRIP_CHARS} (the redundancy check then withdraws it).
	 */
	public static String withoutPlus(final String pKind, final String pArgument)
	{
		if (pArgument == null || pArgument.indexOf('+') < 0)
		{
			return null;
		}
		if (!CleaningSteps.STRIP_CHARS.equalsIgnoreCase(pKind) && !CleaningSteps.NORMALISE_IDENTIFIER.equalsIgnoreCase(pKind))
		{
			return null;
		}
		return pArgument.replace("+", "");
	}

	/** True when {@code pKind} is a step whose argument is a set of characters to remove. */
	public static boolean stripsCharacters(final String pKind)
	{
		return CleaningSteps.STRIP_CHARS.equalsIgnoreCase(pKind) || CleaningSteps.NORMALISE_IDENTIFIER.equalsIgnoreCase(pKind);
	}
}
