package com.on.ebx.factory.wizard;

import java.text.DateFormatSymbols;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

import com.on.ebx.factory.landing.RunTexts;

/**
 * Step 7 screen counts (2026-10-18): the words of the distribution screens that are computed rather than looked up - when a target last sent and what it sent, the
 * retire-notice facts, the golden records of the master. Pure: no EBX class, the page hands in what it already holds ({@code DistributionTextsTest}).
 */
public final class DistributionTexts
{
	private DistributionTexts()
	{
	}

	/**
	 * A stored instant (ISO-8601, "2026-10-18T18:23:11Z") as the screen says it: "18/10 20:23" in French, "18 Oct 20:23" in English, in {@code pZone}. Blank for a blank or
	 * unreadable text - the caller then leaves the date out.
	 */
	public static String when(final String pIso, final Locale pLocale, final ZoneId pZone)
	{
		if (pIso == null || pIso.trim().isEmpty())
		{
			return "";
		}
		final ZonedDateTime moment = DistributionTexts.parse(pIso.trim(), pZone);
		if (moment == null)
		{
			return "";
		}
		final String clock = DistributionTexts.two(moment.getHour()) + ":" + DistributionTexts.two(moment.getMinute());
		if (pLocale != null && "fr".equals(pLocale.getLanguage()))
		{
			return DistributionTexts.two(moment.getDayOfMonth()) + "/" + DistributionTexts.two(moment.getMonthValue()) + " " + clock;
		}
		final String[] months = DateFormatSymbols.getInstance(pLocale == null ? Locale.ENGLISH : pLocale).getShortMonths();
		return moment.getDayOfMonth() + " " + months[moment.getMonthValue() - 1] + " " + clock;
	}

	private static ZonedDateTime parse(final String pIso, final ZoneId pZone)
	{
		try
		{
			return Instant.parse(pIso).atZone(pZone);
		}
		catch (final RuntimeException ex)
		{
			// not an instant: a local date-time ("2026-10-18T20:23:11") is taken as it is
		}
		try
		{
			return LocalDateTime.parse(pIso).atZone(pZone);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static String two(final int pValue)
	{
		return pValue < 10 ? "0" + pValue : String.valueOf(pValue);
	}

	/**
	 * "Last send: 18/10 20:23, 980 rows" - the date of the target's last send and the rows its message says it sent. Blank when the target never sent ({@code lastSendAt} blank),
	 * the count left out when the message is not a sent-N-rows sentence (a failure, a stop).
	 */
	public static String lastSend(final Locale pLocale, final WizardPage.DistributionTargetRow pTarget, final ZoneId pZone)
	{
		final String when = DistributionTexts.when(pTarget.lastSendAt, pLocale, pZone);
		if (when.isEmpty())
		{
			return "";
		}
		if ("FAILED".equals(pTarget.lastStatus))
		{
			return WizardMessages.get(pLocale, "wizard.step7dist.lastSend.failed", when);
		}
		final long rows = RunTexts.sentCount(pTarget.lastMessage);
		return rows < 0L ? WizardMessages.get(pLocale, "wizard.step7dist.lastSend.noCount", when)
			: WizardMessages.get(pLocale, "wizard.step7dist.lastSend", when, WizardPage.countText(pLocale, rows, "wizard.unit.row"));
	}

	/** Whether the target made its first full send (the run that arms a real-time target) - even if it has been disabled since. */
	public static boolean madeFirstFullSend(final String pFirstFullSendRunId)
	{
		return pFirstFullSendRunId != null && !pFirstFullSendRunId.trim().isEmpty();
	}

	/**
	 * Whether a target is a "not armed" open point of the summary: a real-time target that is not armed. A target the person DISABLED after its first full send is not - it did send,
	 * the Distribute card says so; "not armed" stays for a target that never sent.
	 */
	public static boolean notArmedIsOpenPoint(final String pMode, final String pState, final boolean pMadeFirstFullSend)
	{
		return "REALTIME".equals(pMode) && !"ARMED".equals(pState) && !("DISABLED".equals(pState) && pMadeFirstFullSend);
	}

	/**
	 * The state of a target in a sentence of the summary: its state word, except for a disabled target that did make its first full send, which says so with the send's date and
	 * rows ("Disabled after a first full send (980 rows on 18/10 20:23)").
	 */
	public static String summaryState(final Locale pLocale, final String pState, final boolean pMadeFirstFullSend, final String pLastSendAt, final String pLastMessage, final ZoneId pZone)
	{
		if (!"DISABLED".equals(pState) || !pMadeFirstFullSend)
		{
			return WizardMessages.get(pLocale, "wizard.step7dist.state." + pState);
		}
		final String when = DistributionTexts.when(pLastSendAt, pLocale, pZone);
		final long rows = RunTexts.sentCount(pLastMessage);
		if (when.isEmpty() && rows < 0L)
		{
			return WizardMessages.get(pLocale, "wizard.step7dist.state.DISABLED.afterSend");
		}
		if (when.isEmpty())
		{
			return WizardMessages.get(pLocale, "wizard.step7dist.state.DISABLED.afterSend.rows", WizardPage.countText(pLocale, rows, "wizard.unit.row"));
		}
		return rows < 0L ? WizardMessages.get(pLocale, "wizard.step7dist.state.DISABLED.afterSend.when", when)
			: WizardMessages.get(pLocale, "wizard.step7dist.state.DISABLED.afterSend.rowsWhen", WizardPage.countText(pLocale, rows, "wizard.unit.row"), when);
	}

	/** "No retire notice sent yet" / "1 retire notice sent" / "12 retire notices sent" / "10000+ retire notices sent" (the count stopped at its cap). */
	public static String retireSent(final Locale pLocale, final int pCount, final boolean pCapped)
	{
		if (!pCapped && pCount <= 0)
		{
			return WizardMessages.get(pLocale, "wizard.step7dist.retire.sent.none");
		}
		if (!pCapped && pCount == 1)
		{
			return WizardMessages.get(pLocale, "wizard.step7dist.retire.sent.one");
		}
		return WizardMessages.get(pLocale, "wizard.step7dist.retire.sent.other", WizardPage.retireCountText(pCount, pCapped));
	}

	/** The retire facts of a target on one line: how many notices went out and when the merge states were last compared. */
	public static String retireFacts(final Locale pLocale, final int pCount, final boolean pCapped, final String pLastSweepAt, final ZoneId pZone)
	{
		final String when = DistributionTexts.when(pLastSweepAt, pLocale, pZone);
		return DistributionTexts.retireSent(pLocale, pCount, pCapped) + " · "
			+ (when.isEmpty() ? WizardMessages.get(pLocale, "wizard.step7dist.retire.sweep.never") : WizardMessages.get(pLocale, "wizard.step7dist.retire.sweep.at", when));
	}

	/**
	 * What the master holds, for step 7's introduction: the golden records of the main object and the rows of all the tables, both named - never the one added to the other.
	 * {@code pMainGolden} is negative when Match and Merge has no state for the main object (then its rows are the figure).
	 */
	public static String masterHolds(final Locale pLocale, final String pMainEntity, final long pMainGolden, final long pMainRows, final long pTotalRows, final int pTables)
	{
		final String main = pMainGolden >= 0L ? WizardPage.countText(pLocale, pMainGolden, "wizard.unit.goldenRecord")
			: WizardPage.countText(pLocale, pMainRows, "wizard.unit.record");
		if (pTables <= 1)
		{
			return WizardMessages.get(pLocale, "wizard.step7dist.masterHolds.single", main, pMainEntity);
		}
		return WizardMessages.get(pLocale, "wizard.step7dist.masterHolds", main, pMainEntity, WizardPage.countText(pLocale, pTotalRows, "wizard.unit.row"), String.valueOf(pTables));
	}

	/** "534 compared" for one object, "Personne 534, Achat 234 compared" for several - the objects' figures are never added up; blank when no run said. */
	public static String comparedText(final java.util.Map<String, Long> pByObject, final String pWord)
	{
		if (pByObject.isEmpty())
		{
			return "";
		}
		if (pByObject.size() == 1)
		{
			return pByObject.values().iterator().next() + " " + pWord;
		}
		final StringBuilder out = new StringBuilder();
		for (final java.util.Map.Entry<String, Long> entry : pByObject.entrySet())
		{
			out.append(out.length() == 0 ? "" : ", ").append(entry.getKey()).append(' ').append(entry.getValue());
		}
		return out.append(' ').append(pWord).toString();
	}
}
