package com.on.ebx.factory.ebx;

import com.on.ebx.factory.wizard.DeducedScanProgress;

/**
 * JUnit-free, like the rest of this package's tests. Exercises the job-engine lot C-deduced (2026-09-24)
 * addition of {@link DeducedScanRun} that does not need a live repository: {@link DeducedScanRun#entryState}
 * (the {@code FactoryJob} status -&gt; {@link DeducedScanProgress} entry-state mapping used by {@link
 * DeducedScanRun#status} for a job that has not reached its own {@code PipelineStep} yet - see that method's
 * own Javadoc). Same convention as {@link SourcesRunJobMappingTest} for {@link SourcesRun#entryState}; {@link
 * SourcesRun#localize}, which {@link DeducedScanRun}'s HANDLER reuses as-is, is already covered there.
 */
public final class DeducedScanRunJobMappingTest
{
	public static void main(final String[] pArgs)
	{
		DeducedScanRunJobMappingTest.everyJobStatusMapsToAnEntryState();

		System.out.println("DeducedScanRunJobMappingTest OK");
	}

	private static void everyJobStatusMapsToAnEntryState()
	{
		DeducedScanRunJobMappingTest.check("QUEUED -> WAITING", DeducedScanProgress.WAITING.equals(DeducedScanRun.entryState(FactoryJobQueue.QUEUED)));
		DeducedScanRunJobMappingTest.check("RUNNING -> RUNNING", DeducedScanProgress.RUNNING.equals(DeducedScanRun.entryState(FactoryJobQueue.RUNNING)));
		DeducedScanRunJobMappingTest.check("DONE -> DONE", DeducedScanProgress.DONE.equals(DeducedScanRun.entryState(FactoryJobQueue.DONE)));
		DeducedScanRunJobMappingTest.check("FAILED -> FAILED", DeducedScanProgress.FAILED.equals(DeducedScanRun.entryState(FactoryJobQueue.FAILED)));
		// defensive: an unrecognised status (should never happen - batchJobs only selects these four) still
		// answers a value the wizard's message bundle actually has a key for, never null/blank. Note this
		// mapping never produces DeducedScanProgress.SKIPPED - a job without a step yet cannot have reached
		// that (business-level) outcome, see entryNode's own Javadoc.
		DeducedScanRunJobMappingTest.check("unknown status falls back to FAILED, never null",
			DeducedScanProgress.FAILED.equals(DeducedScanRun.entryState("SOMETHING_ELSE")));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
