package com.on.ebx.factory.wizard;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UX lot C2 (2026-10-14): how a finished job is SHOWN, whatever its stored status says. Measured on UxCrmSf2 (RAPPORT, "Done" in green for a landing run that
 * ended {@code LANDING_RUN} FAILED): the job queue only knows DONE / FAILED / CANCELLED, so a run that ended with some failures (landing: one source
 * failed, the others loaded; matching configuration: 2 entities refused) was a green "Done" and its end-of-task toast said "finished".
 * <p>
 * Two layers, both pure:
 * <ul>
 * <li>the queue stores FAILED when the work failed as a whole ({@code LandingRun}: every table failed; the step-backed kinds of {@link #stepGuarded}:
 * the step ended FAILED), see {@code FactoryJobQueue#runHandler};</li>
 * <li>UX lot D (2026-10-14): a job that did some of its work and failed on the rest is stored {@link PartialFailures#STATUS} with the failed items in its
 * detail, and {@link #display} shows it {@link #PARTIAL} ("Finished with failures", amber). Every reader goes through {@link #display} (or reads the status
 * as is: {@code jobnotice.js}, the jobs route). The sentence is NOT parsed any more for a new job; {@link #legacyReportsFailures} survives only to read the
 * DONE rows written before the lot, which cannot be corrected in the data.</li>
 * </ul>
 * No EBX class.
 */
public final class JobOutcome
{
	/** Some of the work failed, the rest was done: amber "Finished with failures". Display only, never stored. */
	public static final String PARTIAL = "PARTIAL";
	/** A DONE job that changed nothing: amber "Nothing to do" (convention of the UX lots). */
	public static final String NOTHING = "NOTHING";

	/** The sentence {@code LandingChainOutcome#finalMessage} writes when some applications failed (read for the rows stored before lot D only). */
	static final String LANDING_FAILURES = "finished with failures";

	private static final Pattern REFUSED = Pattern.compile("([0-9]+) refused");

	/**
	 * The kinds whose job must end FAILED when their {@code PipelineStep} ended FAILED, whatever the handler returned: the step is the truth the
	 * wizard's own screens read, the job's status must not contradict it. The landing is left out on purpose (a run where only some sources failed is
	 * {@link #PARTIAL}, its handler decides).
	 */
	static final Set<String> STEP_GUARDED = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("STAGING_LOAD_RUN", "MASTER_BUILD_RUN", "MATCHING_RUN",
		"MATCHING_CONFIGURE")));

	private JobOutcome()
	{
	}

	/** Whether a job of this kind must end FAILED when its step did. */
	public static boolean stepGuarded(final String pKind)
	{
		return pKind != null && JobOutcome.STEP_GUARDED.contains(pKind);
	}

	/** Whether the job did its work: ended DONE, or DONE with failures on some items (it did not fail as a whole). */
	public static boolean completed(final String pStatus)
	{
		return "DONE".equals(pStatus) || PartialFailures.STATUS.equals(pStatus);
	}

	/**
	 * HISTORY ONLY: whether the message of a DONE job written BEFORE lot D reports failures (so it is shown {@link #PARTIAL}, not a plain success). The
	 * jobs of lot D and after carry the status {@link PartialFailures#STATUS} and are never read this way.
	 */
	public static boolean legacyReportsFailures(final String pKind, final String pMessage)
	{
		final String message = pMessage == null ? "" : pMessage.trim();
		if (message.toLowerCase(java.util.Locale.ROOT).contains(JobOutcome.LANDING_FAILURES))
		{
			return true;
		}
		// "1 entity configured, 2 refused, 0 not accepted." / "1 entity run, 2 not applied, 0 refused."
		if ("MATCHING_CONFIGURE".equals(pKind) || "MATCHING_RUN".equals(pKind))
		{
			final Matcher refused = JobOutcome.REFUSED.matcher(message);
			while (refused.find())
			{
				if (Long.parseLong(refused.group(1)) > 0L)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The status a task is shown with: the stored one, except a DONE task that did nothing ({@link #NOTHING}), a task stored with failures ({@link
	 * PartialFailures#STATUS}) and a DONE task of before lot D whose sentence reports failures: both {@link #PARTIAL}. Keys of the bundle: {@code
	 * wizard.jobs.status.<result>}.
	 */
	public static String display(final String pKind, final String pStatus, final String pMessage)
	{
		final String status = pStatus == null ? "" : pStatus;
		if (PartialFailures.STATUS.equals(status))
		{
			return JobOutcome.PARTIAL;
		}
		if (!"DONE".equals(status))
		{
			return status;
		}
		final String message = pMessage == null ? "" : pMessage.trim();
		if (message.startsWith(RecentTasks.NOTHING_TO_DO))
		{
			return JobOutcome.NOTHING;
		}
		return JobOutcome.legacyReportsFailures(pKind, message) ? JobOutcome.PARTIAL : status;
	}
}
