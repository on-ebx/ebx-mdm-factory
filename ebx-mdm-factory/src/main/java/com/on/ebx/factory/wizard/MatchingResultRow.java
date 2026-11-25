package com.on.ebx.factory.wizard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.matching.MatchStats;

/**
 * UX lot U1 (2026-10-13, RAPPORT 2.11): one row of step 6's "last matching run" table in the person's words. The row used to carry the entity step's own
 * log text - "[MatchingManager] Got exception in matching operation | [MatchingManager] Process Exception" in red, or a 400-word paragraph that repeated
 * the candidate-search warning (measured "9 times slower at 1 million rows" on a project of 81 rows) - and "0 pairs to decide" without the scope
 * ("30 of 81 records were pivots") that sat in the prose.
 * <p>
 * Now: one short sentence, the scope in its own column ({@link #compared}), the raw text kept for a per-row "Details" fold ({@link #details}) and the warning
 * the run attached separated ({@link #warning}) so the page decides whether it is worth showing inline (see {@link MatchingVolume}).
 * <p>
 * The scope is read from the step message the engine writes ({@code master.MatchingRunScope#describe}: "Scope: incremental - 30 of 81 records were pivots ..."
 * or "Scope: all records - the 81 records of the table were pivots"); when that sentence is missing the stored results give it (records in the master
 * minus those that never went through matching). Pure: no EBX class.
 */
public final class MatchingResultRow
{
	private static final Pattern INCREMENTAL = Pattern.compile("Scope: incremental - ([0-9][0-9,]*) of ([0-9][0-9,]*) records");
	private static final Pattern ALL = Pattern.compile("Scope: all records - the ([0-9][0-9,]*) records");
	private static final String WARNING_MARK = " WARNING: ";

	/** The one-sentence outcome. */
	public final String sentence;
	/** "30 / 81", "81 / 81", or "-" when the scope is unknown. */
	public final String compared;
	public final String pending;
	public final String merged;
	public final String distinct;
	/** The engine's own text, for the "Details" fold; empty when there is nothing beyond the sentence. */
	public final String details;
	/** The candidate-search / automatic-merge warning the run attached, or empty. */
	public final String warning;

	private MatchingResultRow(final String pSentence, final String pCompared, final String pPending, final String pMerged, final String pDistinct, final String pDetails,
		final String pWarning)
	{
		this.sentence = pSentence;
		this.compared = pCompared;
		this.pending = pPending;
		this.merged = pMerged;
		this.distinct = pDistinct;
		this.details = pDetails;
		this.warning = pWarning;
	}

	/**
	 * @param pStatus the entity step's state ({@code DONE}/{@code FAILED}/{@code STOPPED}/...)
	 * @param pMessage the step's own message (may be empty)
	 * @param pKpis the stored results of the entity, or null when none exist (a failed run has none)
	 */
	public static MatchingResultRow of(final Locale pLocale, final String pStatus, final String pMessage, final MatchStats.Kpis pKpis)
	{
		// run texts i18n (2026-10-18): the stored message is the engine's English plus the code of its sentences; the scope and the warning are read from the English, the fold shows the
		// message in the person's language
		final String message = pMessage == null ? "" : EngineMessageCodec.decode(pMessage.trim()).raw.trim();
		final String shown = pMessage == null ? "" : RunPanelText.display(pMessage.trim(), pLocale).trim();
		final long[] scope = MatchingResultRow.scope(message, pKpis);
		final String compared = scope[1] < 0 ? "–" : scope[0] + " / " + scope[1];
		final boolean done = "DONE".equals(pStatus);
		final boolean counts = done && pKpis != null;
		final String pending = counts ? String.valueOf(pKpis.pendingSuspectPairs) : "–";
		final String merged = counts ? String.valueOf(pKpis.mergedGroups) : "–";
		final String distinct = counts ? String.valueOf(pKpis.distinctPairs) : "–";
		final String sentence;
		if ("FAILED".equals(pStatus))
		{
			sentence = WizardMessages.get(pLocale, "wizard.step6.result.failed");
		}
		else if ("STOPPED".equals(pStatus))
		{
			sentence = WizardMessages.get(pLocale, "wizard.step6.result.stopped");
		}
		else if (!done)
		{
			sentence = "";
		}
		else if (!counts && scope[1] < 0)
		{
			sentence = WizardMessages.get(pLocale, "wizard.step6.result.doneNoCounts");
		}
		else
		{
			sentence = MatchingResultRow.doneSentence(pLocale, scope, counts ? pKpis : null);
		}
		final int warningAt = message.indexOf(MatchingResultRow.WARNING_MARK);
		final String warning = warningAt < 0 ? "" : message.substring(warningAt + MatchingResultRow.WARNING_MARK.length()).trim();
		return new MatchingResultRow(sentence, compared, pending, merged, distinct, shown, warning);
	}

	private static String doneSentence(final Locale pLocale, final long[] pScope, final MatchStats.Kpis pKpis)
	{
		final StringBuilder out = new StringBuilder();
		if (pScope[1] >= 0)
		{
			out.append(pScope[0] == pScope[1] ? WizardMessages.get(pLocale, "wizard.step6.result.compared.all", Long.valueOf(pScope[1]))
				: WizardMessages.get(pLocale, "wizard.step6.result.compared.part", Long.valueOf(pScope[0]), Long.valueOf(pScope[1])));
		}
		if (pKpis != null)
		{
			out.append(out.length() == 0 ? "" : ", ");
			out.append(pKpis.pendingSuspectPairs == 0 ? WizardMessages.get(pLocale, "wizard.step6.result.pairs.none")
				: WizardMessages.plural(pLocale, pKpis.pendingSuspectPairs, "wizard.step6.result.pairs.one", "wizard.step6.result.pairs.other"));
			out.append(", ");
			out.append(pKpis.mergedGroups == 0 ? WizardMessages.get(pLocale, "wizard.step6.result.merged.none")
				: WizardMessages.plural(pLocale, pKpis.mergedGroups, "wizard.step6.result.merged.one", "wizard.step6.result.merged.other"));
		}
		final String text = out.toString();
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	/** {pivots, total}; total is -1 when unknown. The step's own sentence wins; the stored results are the fallback (records of the master minus the ones never matched). */
	static long[] scope(final String pMessage, final MatchStats.Kpis pKpis)
	{
		final Matcher incremental = MatchingResultRow.INCREMENTAL.matcher(pMessage);
		if (incremental.find())
		{
			return new long[] { MatchingResultRow.number(incremental.group(1)), MatchingResultRow.number(incremental.group(2)) };
		}
		final Matcher all = MatchingResultRow.ALL.matcher(pMessage);
		if (all.find())
		{
			final long total = MatchingResultRow.number(all.group(1));
			return new long[] { total, total };
		}
		if (pKpis != null && pKpis.totalRecords > 0)
		{
			return new long[] { Math.max(0, pKpis.totalRecords - pKpis.unmatchedRecords), pKpis.totalRecords };
		}
		return new long[] { 0L, -1L };
	}

	private static long number(final String pText)
	{
		return Long.parseLong(pText.replace(",", ""));
	}
}
