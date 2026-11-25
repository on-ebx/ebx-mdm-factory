package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Lot L2 (2026-10-12): the final status of a chain names the sources that failed, and the grid survives several threads reporting into it. */
public final class LandingChainOutcomeTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		LandingChainOutcomeTest.nothingFailedIsTheOldMessage();
		LandingChainOutcomeTest.failedSourcesAreNamedOnceInOrder();
		LandingChainOutcomeTest.oneFailureNeverTouchesTheOtherSources();
		LandingChainOutcomeTest.theJobFailsOnlyWhenEveryApplicationFailed();
		LandingChainOutcomeTest.distinctKeepsOrder();
		LandingChainOutcomeTest.severalThreadsReportIntoOneGrid();
		System.out.println("LandingChainOutcomeTest OK");
	}

	private static List<LandingRunProgress.TableRef> threeSources()
	{
		return Arrays.asList(new LandingRunProgress.TableRef("1", "BusinessPartner", "SAP"), new LandingRunProgress.TableRef("2", "Address", "SAP"),
			new LandingRunProgress.TableRef("3", "Contact", "CRM"), new LandingRunProgress.TableRef("4", "Customer", "WEB"));
	}

	private static void settleAll(final LandingRunProgress pProgress, final String pTableId, final boolean pOk)
	{
		for (final String stage : LandingRunProgress.STAGES)
		{
			pProgress.finish(pTableId, stage, pOk, pOk ? "ok" : "no");
		}
	}

	private static void nothingFailedIsTheOldMessage()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingChainOutcomeTest.threeSources());
		for (final String id : Arrays.asList("1", "2", "3", "4"))
		{
			LandingChainOutcomeTest.settleAll(progress, id, true);
		}
		LandingChainOutcomeTest.check("no failed source", LandingChainOutcome.failedSources(progress).isEmpty());
		LandingChainOutcomeTest.check("old wording kept", "Landing run finished: 16 of 16 stages done.".equals(LandingChainOutcome.finalMessage(progress)));
	}

	private static void failedSourcesAreNamedOnceInOrder()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingChainOutcomeTest.threeSources());
		LandingChainOutcomeTest.settleAll(progress, "1", true);
		progress.finish("2", LandingRunProgress.KEY, true, "ok");
		progress.finish("2", LandingRunProgress.TABLE, true, "ok");
		progress.finish("2", LandingRunProgress.FLOW, true, "ok");
		progress.finish("2", LandingRunProgress.LOAD, false, "1 of 2 tables failed");
		LandingChainOutcomeTest.settleAll(progress, "3", true);
		LandingChainOutcomeTest.settleAll(progress, "4", false);
		LandingChainOutcomeTest.check("SAP (once, though 1 is fine and 2 failed) and WEB", LandingChainOutcome.failedSources(progress).equals(Arrays.asList("SAP", "WEB")));
		LandingChainOutcomeTest.check("message names them", "Landing run finished with failures on: SAP, WEB.".equals(LandingChainOutcome.finalMessage(progress)));
	}

	/** UX lot C2: measured on UxCrmSf2, "failures on: SALESFORCE, DEMOCRM" (both applications) was a green Done. */
	private static void theJobFailsOnlyWhenEveryApplicationFailed()
	{
		final LandingRunProgress both = LandingRunProgress.newRun(Arrays.asList(new LandingRunProgress.TableRef("1", "Account", "SALESFORCE"),
			new LandingRunProgress.TableRef("2", "Contact", "SALESFORCE"), new LandingRunProgress.TableRef("3", "Customer", "DEMOCRM")));
		LandingChainOutcomeTest.settleAll(both, "1", false);
		both.finish("2", LandingRunProgress.KEY, true, "ok");
		both.finish("2", LandingRunProgress.TABLE, true, "ok");
		both.finish("2", LandingRunProgress.FLOW, true, "ok");
		both.finish("2", LandingRunProgress.LOAD, true, "ok");
		LandingChainOutcomeTest.settleAll(both, "3", false);
		LandingChainOutcomeTest.check("sources of the grid", LandingChainOutcome.sources(both).equals(Arrays.asList("SALESFORCE", "DEMOCRM")));
		LandingChainOutcomeTest.check("the measured sentence", "Landing run finished with failures on: SALESFORCE, DEMOCRM.".equals(LandingChainOutcome.finalMessage(both)));
		LandingChainOutcomeTest.check("every application has a failed cell: the job fails", LandingChainOutcome.allSourcesFailed(both));
		final LandingRunProgress some = LandingRunProgress.newRun(LandingChainOutcomeTest.threeSources());
		LandingChainOutcomeTest.settleAll(some, "1", true);
		LandingChainOutcomeTest.settleAll(some, "2", true);
		LandingChainOutcomeTest.settleAll(some, "3", false);
		LandingChainOutcomeTest.settleAll(some, "4", true);
		LandingChainOutcomeTest.check("one application of three failed: not a failed job (amber 'finished with failures')", !LandingChainOutcome.allSourcesFailed(some));
		final LandingRunProgress none = LandingRunProgress.newRun(LandingChainOutcomeTest.threeSources());
		for (final String id : Arrays.asList("1", "2", "3", "4"))
		{
			LandingChainOutcomeTest.settleAll(none, id, true);
		}
		LandingChainOutcomeTest.check("nothing failed: not failed", !LandingChainOutcome.allSourcesFailed(none));
	}

	private static void oneFailureNeverTouchesTheOtherSources()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingChainOutcomeTest.threeSources());
		LandingChainOutcomeTest.settleAll(progress, "4", false);
		LandingChainOutcomeTest.check("CRM's cells still TODO", LandingRunProgress.TODO.equals(progress.table("3").cell(LandingRunProgress.LOAD).state));
		LandingChainOutcomeTest.check("only WEB so far", LandingChainOutcome.failedSources(progress).equals(Arrays.asList("WEB")));
	}

	private static void distinctKeepsOrder()
	{
		LandingChainOutcomeTest.check("distinct, first occurrence order", LandingChainOutcome.distinct(Arrays.asList("b", "a", "b", "c", "a")).equals(Arrays.asList("b", "a", "c")));
	}

	/** Four threads, each settling its own source's cells and journal lines, with a listener that serialises the writes: no lost cell, no torn JSON. */
	private static void severalThreadsReportIntoOneGrid() throws Exception
	{
		final List<LandingRunProgress.TableRef> refs = new ArrayList<LandingRunProgress.TableRef>();
		for (int i = 0; i < 4; i++)
		{
			for (int t = 0; t < 25; t++)
			{
				refs.add(new LandingRunProgress.TableRef(i + "-" + t, "T" + i + "_" + t, "S" + i));
			}
		}
		final LandingRunProgress progress = LandingRunProgress.newRun(refs);
		final java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
		final java.util.concurrent.atomic.AtomicInteger inListener = new java.util.concurrent.atomic.AtomicInteger();
		final java.util.concurrent.atomic.AtomicInteger overlap = new java.util.concurrent.atomic.AtomicInteger();
		progress.onChange(() -> {
			if (inListener.incrementAndGet() > 1)
			{
				overlap.incrementAndGet();
			}
			progress.toJson();
			writes.incrementAndGet();
			inListener.decrementAndGet();
		});
		final Thread[] threads = new Thread[4];
		final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
		for (int i = 0; i < 4; i++)
		{
			final int source = i;
			threads[i] = new Thread(() -> {
				try
				{
					final List<String> ids = new ArrayList<String>();
					for (int t = 0; t < 25; t++)
					{
						ids.add(source + "-" + t);
					}
					progress.startAll(ids, LandingRunProgress.LOAD, "Loading the data.");
					for (int t = 0; t < 25; t++)
					{
						progress.activityAll(ids, LandingRunProgress.LOAD, "table " + t);
						progress.finish(source + "-" + t, LandingRunProgress.LOAD, source != 2, source == 2 ? "failed" : "ok");
					}
				}
				catch (final Throwable ex)
				{
					failure.set(ex);
				}
			});
		}
		for (final Thread thread : threads)
		{
			thread.start();
		}
		for (final Thread thread : threads)
		{
			thread.join();
		}
		LandingChainOutcomeTest.check("no thread failed: " + failure.get(), failure.get() == null);
		LandingChainOutcomeTest.check("the listener never ran twice at once (" + overlap.get() + ")", overlap.get() == 0);
		LandingChainOutcomeTest.check("every change was reported", writes.get() > 100);
		int done = 0;
		int failed = 0;
		for (final LandingRunProgress.TableProgress table : progress.tables())
		{
			final String state = table.cell(LandingRunProgress.LOAD).state;
			done += LandingRunProgress.DONE.equals(state) ? 1 : 0;
			failed += LandingRunProgress.FAILED.equals(state) ? 1 : 0;
		}
		LandingChainOutcomeTest.check("75 loads done, 25 failed (no lost update): " + done + "/" + failed, done == 75 && failed == 25);
		LandingChainOutcomeTest.check("the journal keeps its cap", progress.events().size() <= LandingRunProgress.MAX_EVENTS);
		LandingChainOutcomeTest.check("the JSON still parses back to the same grid", LandingRunProgress.parse(progress.toJson()).tables().size() == 100);
		LandingChainOutcomeTest.check("only the failing source is named", LandingChainOutcome.failedSources(progress).equals(Arrays.asList("S2")));
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
