package com.on.ebx.factory.intake;

/**
 * Automatic intake lot: how a finished chain is reported and what the engine does next. Pure.
 */
public final class IntakeChainOutcome
{
	public static final String DONE = "DONE";
	public static final String DONE_WITH_REJECTS = "DONE_WITH_REJECTS";
	public static final String STOPPED = "STOPPED";
	public static final String FAILED = "FAILED";

	private IntakeChainOutcome()
	{
	}

	/**
	 * The status of a chain that is over. A failure (the workflow instance in error, or gone without having produced anything usable) wins over a stop; a stop
	 * (the staging load decided the threshold was reached) wins over "done"; rejects without a stop make it {@code DONE_WITH_REJECTS}.
	 */
	public static String status(final boolean pFailed, final boolean pStopped, final long pRejected)
	{
		if (pFailed)
		{
			return IntakeChainOutcome.FAILED;
		}
		if (pStopped)
		{
			return IntakeChainOutcome.STOPPED;
		}
		return pRejected > 0 ? IntakeChainOutcome.DONE_WITH_REJECTS : IntakeChainOutcome.DONE;
	}

	/** The sentence the staging load writes on its {@code PipelineStep} when every row read was rejected ({@code ebx.StagingLoader}); the engine recognises a total reject by it when the workflow history is gone. */
	public static final String TOTAL_REJECT_MARKER = "Every source row read was rejected";

	/**
	 * Whether the chain counts as STOPPED (and pauses the intake) for what the staging load held back. Decided from the COUNTS and the project's own setting, never from the
	 * workflow's {@code stagingStopped} alone - in an intake chain that output is also {@code true} for a total reject with no threshold, which is NOT a stop: the rows go to
	 * the review and the intake carries on. No threshold: never. A threshold: {@code RejectThreshold#reached} on the loader's read/rejected ("at least": a total
	 * reject reaches every threshold; a threshold of 100 stops only on a total reject). Read 0 (counts unknown, the history gone): only what the workflow decided with the
	 * threshold it was launched with ({@code pStoppedByWorkflow}) - and still only when a threshold is set.
	 */
	public static boolean stoppedByThreshold(final Integer pStopRejectPercent, final long pRead, final long pRejected, final boolean pStoppedByWorkflow)
	{
		if (pStopRejectPercent == null)
		{
			return false;
		}
		return pRead > 0 ? RejectThreshold.reached(pRead, pRejected, pStopRejectPercent) : pStoppedByWorkflow;
	}

	/** {@link #status} with the stop decided by {@link #stoppedByThreshold}. */
	public static String statusOf(final boolean pFailed, final Integer pStopRejectPercent, final long pRead, final long pRejected, final boolean pStoppedByWorkflow)
	{
		return IntakeChainOutcome.status(pFailed, IntakeChainOutcome.stoppedByThreshold(pStopRejectPercent, pRead, pRejected, pStoppedByWorkflow), pRejected);
	}

	/**
	 * A FAILED {@code STAGING_LOAD} step is a total reject, not a failure of the chain: the history said every row read was rejected ({@code pCountsKnown}), or - history gone -
	 * the step's own message carries {@link #TOTAL_REJECT_MARKER}.
	 */
	public static boolean isTotalRejectStep(final boolean pCountsKnown, final long pRead, final long pRejected, final String pStepMessage)
	{
		if (pCountsKnown && pRead > 0 && pRejected == pRead)
		{
			return true;
		}
		return pStepMessage != null && pStepMessage.contains(IntakeChainOutcome.TOTAL_REJECT_MARKER);
	}

	/** True for the two outcomes after which the intake pauses itself: a chain that stopped or failed must not be followed by the next trickle of rows. */
	public static boolean pausesIntake(final String pStatus)
	{
		return IntakeChainOutcome.STOPPED.equals(pStatus) || IntakeChainOutcome.FAILED.equals(pStatus);
	}

	/** True when the review task of the rows held back should open: the chain ended with rejects (a stopped chain too: its rows are held back and the person must see them), the project asked for it. */
	public static boolean opensReview(final String pStatus, final long pRejected, final boolean pReviewRejects)
	{
		return pReviewRejects && pRejected > 0 && !IntakeChainOutcome.FAILED.equals(pStatus);
	}

	/** Pause reason codes (audit F2-108): stored as {@code CODE:argument}, worded by the screens from the bundle keys {@code wizard.intake.pauseReason.<CODE>} in the viewer's language. */
	public static final String REASON_STOPPED = "STOPPED";
	public static final String REASON_FAILED = "FAILED";
	public static final String REASON_LAUNCH_FAILED = "LAUNCH_FAILED";
	public static final String REASON_PAUSED_BY = "PAUSED_BY";

	/**
	 * The value stored in {@code Project.intakePauseReason}: a code and its argument, e.g. {@code STOPPED:6} (the rounded percentage of the rows held back) or
	 * {@code FAILED:<the failure message, cut to 300 characters; empty when unknown>}. A value stored before F2-108 is an English sentence and is shown as it is.
	 */
	public static String pauseReason(final String pStatus, final long pRead, final long pRejected, final String pFailureMessage)
	{
		if (IntakeChainOutcome.STOPPED.equals(pStatus))
		{
			return IntakeChainOutcome.REASON_STOPPED + ":" + RejectThreshold.roundedPercent(pRead, pRejected);
		}
		final String message = pFailureMessage == null ? "" : pFailureMessage.trim();
		return IntakeChainOutcome.REASON_FAILED + ":" + (message.length() > 300 ? message.substring(0, 300) : message);
	}

	/** The pause reason of a chain that could not even be launched ({@code LAUNCH_FAILED:<message>}). */
	public static String launchFailedReason(final String pMessage)
	{
		final String message = pMessage == null ? "" : pMessage.trim();
		return IntakeChainOutcome.REASON_LAUNCH_FAILED + ":" + (message.length() > 300 ? message.substring(0, 300) : message);
	}

	/** The pause reason of a person's pause ({@code PAUSED_BY:<user id>}). */
	public static String pausedByReason(final String pUserId)
	{
		return IntakeChainOutcome.REASON_PAUSED_BY + ":" + (pUserId == null ? "" : pUserId);
	}
}
