package com.on.ebx.factory.staging;

import java.util.Arrays;
import java.util.Date;

/** JUnit-free: run the main, a failure throws. Fix/derivation-apply-backfill (2026-10-17, lot G1, item 2). */
public final class DerivationStalenessTest
{
	private static final Date APPLIED = new Date(1_000_000L);
	private static final Date BEFORE = new Date(900_000L);
	private static final Date AFTER = new Date(1_100_000L);

	public static void main(final String[] pArgs)
	{
		DerivationStalenessTest.decidesFromTheTableDate();
		DerivationStalenessTest.judgesOneRow();
		DerivationStalenessTest.buildsThePredicate();
		DerivationStalenessTest.wordsTheRepair();
		System.out.println("DerivationStalenessTest OK");
	}

	private static void decidesFromTheTableDate()
	{
		// the live case: applied at 18:42, nothing written to the table since
		DerivationStalenessTest.check("nothing written since the apply", DerivationStaleness.decide(534L, APPLIED, BEFORE, false) == DerivationStaleness.Need.ALL_ROWS);
		DerivationStalenessTest.check("written at the very instant of the apply", DerivationStaleness.decide(534L, APPLIED, APPLIED, false) == DerivationStaleness.Need.ALL_ROWS);
		DerivationStalenessTest.check("unknown table date is read as not written", DerivationStaleness.decide(534L, APPLIED, null, false) == DerivationStaleness.Need.ALL_ROWS);
		DerivationStalenessTest.check("written since: only the empty ones are candidates", DerivationStaleness.decide(534L, APPLIED, AFTER, false) == DerivationStaleness.Need.EMPTY_DERIVED);
		DerivationStalenessTest.check("empty table", DerivationStaleness.decide(0L, APPLIED, BEFORE, false) == DerivationStaleness.Need.NONE);
		DerivationStalenessTest.check("unknown apply time", DerivationStaleness.decide(10L, null, BEFORE, false) == DerivationStaleness.Need.NONE);
		DerivationStalenessTest.check("parameters changed: re-apply first", DerivationStaleness.decide(10L, APPLIED, BEFORE, true) == DerivationStaleness.Need.NONE);
	}

	private static void judgesOneRow()
	{
		DerivationStalenessTest.check("empty derived, input filled, written before", DerivationStaleness.rowStale(true, false, BEFORE, APPLIED));
		DerivationStalenessTest.check("filled derived, written before", DerivationStaleness.rowStale(false, true, BEFORE, APPLIED));
		DerivationStalenessTest.check("written after the apply: the trigger ran", !DerivationStaleness.rowStale(true, false, AFTER, APPLIED));
		DerivationStalenessTest.check("written at the instant of the apply is stale", DerivationStaleness.rowStale(true, false, APPLIED, APPLIED));
		DerivationStalenessTest.check("nothing to compute", !DerivationStaleness.rowStale(false, false, BEFORE, APPLIED));
		DerivationStalenessTest.check("unknown write time is never touched", !DerivationStaleness.rowStale(true, false, null, APPLIED));
		DerivationStalenessTest.check("candidate", DerivationStaleness.emptyDerivedCandidate(true, false));
		DerivationStalenessTest.check("derived filled is no candidate", !DerivationStaleness.emptyDerivedCandidate(true, true));
		DerivationStalenessTest.check("no input is no candidate", !DerivationStaleness.emptyDerivedCandidate(false, false));
	}

	private static void buildsThePredicate()
	{
		final String one = DerivationStaleness.emptyDerivedPredicate("mobileE164", Arrays.asList("mobile"));
		DerivationStalenessTest.check(one, "osd:is-null(./mobileE164) and (osd:is-not-null(./mobile))".equals(one));
		final String two = DerivationStaleness.emptyDerivedPredicate("nameKey", Arrays.asList("firstName", "lastName"));
		DerivationStalenessTest.check(two, "osd:is-null(./nameKey) and (osd:is-not-null(./firstName) or osd:is-not-null(./lastName))".equals(two));
		DerivationStalenessTest.check("no input", DerivationStaleness.emptyDerivedPredicate("a", Arrays.<String>asList()) == null);
		DerivationStalenessTest.check("unsafe derived name", DerivationStaleness.emptyDerivedPredicate("a'] or [1=1", Arrays.asList("b")) == null);
		DerivationStalenessTest.check("unsafe source name", DerivationStaleness.emptyDerivedPredicate("a", Arrays.asList("b c")) == null);
	}

	private static void wordsTheRepair()
	{
		final String all = DerivationStaleness.repairedNote("Client", "mobileE164", 534L, DerivationStaleness.Need.ALL_ROWS);
		DerivationStalenessTest.check(all, all.startsWith("Client.mobileE164: 534 existing row(s) recomputed") && all.contains("nothing had been written"));
		final String empty = DerivationStaleness.repairedNote("Client", "mobileE164", 3L, DerivationStaleness.Need.EMPTY_DERIVED);
		DerivationStalenessTest.check(empty, empty.contains("empty although their input is filled"));
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
