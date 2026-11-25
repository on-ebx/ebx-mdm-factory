package com.on.ebx.factory.deducedscan;

/** JUnit-free: run the main, a failure throws. */
public final class DeducedScanRunSummaryTest
{
	public static void main(final String[] pArgs)
	{
		// first-ever scan of a connection: everything created, nothing kept or reopened - reads exactly like
		// the pre-fix wording (no regression for the common, single-outcome case).
		DeducedScanRunSummaryTest.check("all created",
			DeducedScanRunSummary.build(DeducedScanRunSummaryTest.counts(2, 0, 0, 0), ""),
			"2 table(s) deduced, waiting for confirmation.");

		// a rescan that reconciled every asset back to its earlier decision: nothing needs a person's attention.
		DeducedScanRunSummaryTest.check("all kept, decisions preserved",
			DeducedScanRunSummary.build(DeducedScanRunSummaryTest.counts(0, 2, 0, 0), ""),
			"2 table(s) up to date, decisions kept.");

		// a rescan where every table's columns changed: every one reopened, nothing new, nothing kept.
		DeducedScanRunSummaryTest.check("all reopened",
			DeducedScanRunSummary.build(DeducedScanRunSummaryTest.counts(0, 0, 0, 1), ""),
			"1 table(s) changed: to confirm again (columns changed).");

		// a mix: one genuinely new table, one whose earlier decision survived untouched.
		DeducedScanRunSummaryTest.check("mixed: new + kept",
			DeducedScanRunSummary.build(DeducedScanRunSummaryTest.counts(1, 1, 0, 0), ""),
			"1 new table(s) to confirm, 1 kept.");

		// a mix of all four outcomes at once, in the fixed part order (created, waiting, reopened, kept).
		DeducedScanRunSummaryTest.check("mixed: every outcome at once",
			DeducedScanRunSummary.build(DeducedScanRunSummaryTest.counts(1, 1, 1, 1), ""),
			"1 new table(s) to confirm, 1 still waiting for confirmation, 1 changed: to confirm again (columns changed), 1 kept.");

		// still-unconfirmed-and-unchanged alone (no new table, nothing reopened, nothing decided) - counted the
		// same as a fresh scan by the single-outcome branch (toConfirm = created + keptToConfirm).
		DeducedScanRunSummaryTest.check("only unchanged-still-to-confirm",
			DeducedScanRunSummary.build(DeducedScanRunSummaryTest.counts(0, 0, 3, 0), ""),
			"3 table(s) deduced, waiting for confirmation.");

		// problems are appended verbatim, exactly like the pre-fix summary did.
		DeducedScanRunSummaryTest.check("problems appended verbatim",
			DeducedScanRunSummary.build(DeducedScanRunSummaryTest.counts(1, 0, 0, 0), "listOrders: no row read; "),
			"1 table(s) deduced, waiting for confirmation. listOrders: no row read; ");

		// a null problems string is treated the same as an empty one - never a NullPointerException.
		DeducedScanRunSummaryTest.check("null problems does not crash",
			DeducedScanRunSummary.build(DeducedScanRunSummaryTest.counts(1, 0, 0, 0), null),
			"1 table(s) deduced, waiting for confirmation.");

		final DeducedScanRunSummary.Counts total = DeducedScanRunSummaryTest.counts(1, 2, 3, 4);
		DeducedScanRunSummaryTest.checkTrue("Counts.total sums every bucket", total.total() == 10);

		System.out.println("DeducedScanRunSummaryTest OK");
	}

	private static DeducedScanRunSummary.Counts counts(final int pCreated, final int pKeptDecided, final int pKeptToConfirm, final int pReopened)
	{
		final DeducedScanRunSummary.Counts out = new DeducedScanRunSummary.Counts();
		out.created = pCreated;
		out.keptDecided = pKeptDecided;
		out.keptToConfirm = pKeptToConfirm;
		out.reopened = pReopened;
		return out;
	}

	private static void check(final String pWhat, final String pActual, final String pExpected)
	{
		if (!pExpected.equals(pActual))
		{
			throw new AssertionError(pWhat + ": expected [" + pExpected + "] but got [" + pActual + "]");
		}
	}

	private static void checkTrue(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
