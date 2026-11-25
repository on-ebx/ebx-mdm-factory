package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.Session;

/**
 * JUnit-free: run the main, a failure throws. The throwable boundary of a job body ({@link FactoryJobQueue#runHandler}): whatever
 * a handler throws, even an Error, becomes a terminal outcome for the job row - never an exception that leaves it RUNNING.
 */
public final class FactoryJobQueueTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final JsonNode params = new ObjectMapper().readTree("{}");
		final FactoryJobs.Handle handle = FactoryJobs.claim("QP1", FactoryJobs.STAGING, "STAGING_LOAD_RUN", "alice");

		FactoryJobQueue.Outcome ok = FactoryJobQueueTest.run(handle, params, new FactoryJobQueue.Handler()
		{
			@Override
			public String run(final Session pS, final FactoryJobs.Handle pH, final Adaptation pF, final String pP, final JsonNode pPa)
			{
				return "loaded 3 rows";
			}
		});
		FactoryJobQueueTest.check("a normal run is DONE with the handler's sentence", FactoryJobQueue.DONE.equals(ok.status) && "loaded 3 rows".equals(ok.message));

		final FactoryJobQueue.Outcome expected = FactoryJobQueueTest.run(handle, params, new FactoryJobQueue.Handler()
		{
			@Override
			public String run(final Session pS, final FactoryJobs.Handle pH, final Adaptation pF, final String pP, final JsonNode pPa) throws Exception
			{
				throw new FactoryJobQueue.ExpectedFailure("a source needs a person");
			}
		});
		FactoryJobQueueTest.check("an expected failure is FAILED with its sentence", FactoryJobQueue.FAILED.equals(expected.status) && "a source needs a person".equals(expected.message));

		// UX lot D (2026-10-14): a handler that did some of its work signals it in a typed way - a real status with the failed items, not a DONE with a sentence
		final java.util.List<com.on.ebx.factory.wizard.PartialFailures.Item> failedItems = new java.util.ArrayList<com.on.ebx.factory.wizard.PartialFailures.Item>();
		failedItems.add(new com.on.ebx.factory.wizard.PartialFailures.Item("t1", "SALESFORCE.Account", "no scanned column"));
		final FactoryJobQueue.Outcome partial = FactoryJobQueueTest.run(handle, params, new FactoryJobQueue.Handler()
		{
			@Override
			public String run(final Session pS, final FactoryJobs.Handle pH, final Adaptation pF, final String pP, final JsonNode pPa) throws Exception
			{
				throw new FactoryJobQueue.PartialOutcome("1 of 4 tables was not loaded", new com.on.ebx.factory.wizard.PartialFailures.Failures(4, failedItems));
			}
		});
		FactoryJobQueueTest.check("a partial outcome is DONE_WITH_FAILURES with its sentence", FactoryJobQueue.DONE_WITH_FAILURES.equals(partial.status)
			&& "DONE_WITH_FAILURES".equals(partial.status) && "1 of 4 tables was not loaded".equals(partial.message));
		final com.on.ebx.factory.wizard.PartialFailures.Failures stored = com.on.ebx.factory.wizard.PartialFailures.parse(partial.detail);
		FactoryJobQueueTest.check("... and the failed items travel in the detail", stored.total == 4 && stored.items.size() == 1 && "SALESFORCE.Account".equals(stored.items.get(0).label)
			&& "no scanned column".equals(stored.items.get(0).reason));
		FactoryJobQueueTest.check("a normal run carries no detail", ok.detail == null && expected.detail == null);

		final FactoryJobQueue.Outcome exception = FactoryJobQueueTest.run(handle, params, new FactoryJobQueue.Handler()
		{
			@Override
			public String run(final Session pS, final FactoryJobs.Handle pH, final Adaptation pF, final String pP, final JsonNode pPa) throws Exception
			{
				throw new IllegalStateException("boom");
			}
		});
		FactoryJobQueueTest.check("an exception is FAILED with its message", FactoryJobQueue.FAILED.equals(exception.status) && "boom".equals(exception.message));

		final FactoryJobQueue.Outcome linkage = FactoryJobQueueTest.run(handle, params, new FactoryJobQueue.Handler()
		{
			@Override
			public String run(final Session pS, final FactoryJobs.Handle pH, final Adaptation pF, final String pP, final JsonNode pPa)
			{
				throw new VerifyError("Bad type on operand stack");
			}
		});
		FactoryJobQueueTest.check("a VerifyError is FAILED, not an exception that leaves the row RUNNING",
			FactoryJobQueue.FAILED.equals(linkage.status) && linkage.message.contains("VerifyError") && linkage.message.contains("Bad type") && linkage.threadDeath == null);

		final FactoryJobQueue.Outcome noMessage = FactoryJobQueueTest.run(handle, params, new FactoryJobQueue.Handler()
		{
			@Override
			public String run(final Session pS, final FactoryJobs.Handle pH, final Adaptation pF, final String pP, final JsonNode pPa)
			{
				throw new StackOverflowError();
			}
		});
		FactoryJobQueueTest.check("an Error without a message is named by its class", noMessage.message.equals("java.lang.StackOverflowError"));

		final FactoryJobQueue.Outcome death = FactoryJobQueueTest.run(handle, params, new FactoryJobQueue.Handler()
		{
			@Override
			public String run(final Session pS, final FactoryJobs.Handle pH, final Adaptation pF, final String pP, final JsonNode pPa)
			{
				throw new ThreadDeath();
			}
		});
		FactoryJobQueueTest.check("a ThreadDeath closes the row first and is handed back to be rethrown", FactoryJobQueue.FAILED.equals(death.status) && death.threadDeath != null);

		handle.cancel("stopped by bob");
		final FactoryJobQueue.Outcome cancelled = FactoryJobQueueTest.run(handle, params, new FactoryJobQueue.Handler()
		{
			@Override
			public String run(final Session pS, final FactoryJobs.Handle pH, final Adaptation pF, final String pP, final JsonNode pPa)
			{
				return null;
			}
		});
		FactoryJobQueueTest.check("a cancelled job without a sentence says why it stopped",
			FactoryJobQueue.CANCELLED.equals(cancelled.status)
				// run panel i18n (2026-10-17): the English sentence is unchanged, followed by its code (the screens word it in the viewer's language)
				&& "Stopped (stopped by bob).".equals(com.on.ebx.factory.wizard.EngineMessageCodec.decode(cancelled.message).raw)
				&& cancelled.message.endsWith("[mdmf:wizard.runtext.stopped|s:stopped+by+bob]"));
		System.out.println("FactoryJobQueueTest OK");
	}

	private static FactoryJobQueue.Outcome run(final FactoryJobs.Handle pHandle, final JsonNode pParams, final FactoryJobQueue.Handler pHandler)
	{
		return FactoryJobQueue.runHandler(pHandler, null, pHandle, null, "QP1", pParams, "1", "STAGING_LOAD_RUN");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
