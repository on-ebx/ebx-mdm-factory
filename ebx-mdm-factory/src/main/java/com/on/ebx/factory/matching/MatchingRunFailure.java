package com.on.ebx.factory.matching;

import java.util.List;
import java.util.Locale;

/**
 * Lot MT (2026-10-04): what a matching run says when the add-on's process for an entity ENDS in failure - pure wording, no EBX class.
 * <p>
 * Seen live (6.3.0-rc2, project Vol1mF): the process ended {@code FAILED} at completion rate 50.0, the per-entity step said "Matching run
 * on Personne still FAILED after 315360000000ms - Xref.goldenKey NOT refreshed" (a terminal status is not a timeout: the wait is
 * unbounded for a job) and the job ended {@code DONE} with "2 entities run, 0 not applied, 0 refused". A terminal {@code FAILED} (or a
 * {@code CANCELLED} nobody asked for) is now a failure of the entity's step with the add-on's own reason ({@link AddonLogWindow}), and the
 * JOB fails with a message that counts and names the failed entities, while the entities that completed keep their results.
 */
public final class MatchingRunFailure
{
	private MatchingRunFailure()
	{
	}

	/**
	 * {@code true} for a terminal status that is a failure: {@code FAILED}, or {@code CANCELLED} when the Factory did not ask for the stop
	 * ({@code pStoppedByUser} false - somebody or something else cancelled the add-on's process). {@code DONE} is not one; neither is a
	 * non-terminal status (that one is a timeout for a synchronous caller, handled elsewhere) nor a {@code CANCELLED} the user asked for.
	 */
	public static boolean isFailure(final String pStatus, final boolean pStoppedByUser)
	{
		if ("FAILED".equals(pStatus))
		{
			return true;
		}
		return "CANCELLED".equals(pStatus) && !pStoppedByUser;
	}

	/**
	 * The per-entity step message. {@code pCompletionRate} is the add-on's percentage ({@code 50.0}), negative when unknown;
	 * {@code pReason} the text {@link AddonLogWindow#reasonText} built, {@code null} when the log could not be read or holds no ERROR for the
	 * run (the message then points at the log file); {@code pLogHint} names where to look ("ebx-addon-mame.log", or its full path).
	 */
	public static String stepMessage(final String pEntityName, final String pStatus, final double pCompletionRate, final String pReason, final String pLogHint,
		final String pScopeSentence)
	{
		final StringBuilder out = new StringBuilder();
		out.append("Matching run on ").append(pEntityName).append(" ").append(pStatus).append(": the add-on's process ");
		out.append("FAILED".equals(pStatus) ? "failed" : "was cancelled");
		out.append(pCompletionRate < 0 ? " (completion rate unknown)" : " at " + String.format(Locale.ROOT, "%.1f", Double.valueOf(pCompletionRate)) + " %");
		if (pReason != null && !pReason.trim().isEmpty())
		{
			out.append(" - reason from ebx-addon-mame.log: ").append(pReason.trim());
			out.append(pReason.trim().endsWith(".") ? " " : ". ");
		}
		else
		{
			out.append(" - see ").append(pLogHint == null || pLogHint.isEmpty() ? AddonLogWindow.LOG_FILE_NAME : pLogHint).append(" for the reason. ");
		}
		out.append("Xref.goldenKey NOT refreshed.");
		if (pScopeSentence != null && !pScopeSentence.trim().isEmpty())
		{
			out.append(" ").append(pScopeSentence.trim());
		}
		return out.toString();
	}

	/** One entry of the failed list the run returns and the job message quotes: {@code Personne - <short reason>}. */
	public static String entry(final String pEntityName, final String pReasonOrNull, final String pStatus, final double pCompletionRate)
	{
		final String what = pReasonOrNull != null && !pReasonOrNull.trim().isEmpty() ? pReasonOrNull.trim()
			: ("FAILED".equals(pStatus) ? "failed" : "cancelled") + (pCompletionRate < 0 ? "" : " at " + String.format(Locale.ROOT, "%.1f", Double.valueOf(pCompletionRate)) + " %")
				+ ", see ebx-addon-mame.log";
		return pEntityName + " - " + what;
	}

	/**
	 * The JOB's result sentence when at least one entity failed: {@code "1 of 2 entities failed: Personne - <reason>; ..."} - the
	 * denominator is the entities whose add-on process was launched (completed plus failed) - then the usual counts, so the entities that
	 * completed are still reported.
	 */
	public static String jobMessage(final List<String> pFailedEntries, final int pRan, final int pNotApplied, final int pRefused)
	{
		final int failed = pFailedEntries.size();
		final int launched = pRan + failed;
		final StringBuilder out = new StringBuilder();
		out.append(failed).append(" of ").append(launched).append(" entit").append(launched == 1 ? "y" : "ies").append(" failed: ").append(String.join("; ", pFailedEntries))
			.append(". ").append(pRan).append(" entit").append(pRan == 1 ? "y" : "ies").append(" run, ").append(pNotApplied).append(" not applied, ").append(pRefused)
			.append(" refused.");
		return out.toString();
	}
}
