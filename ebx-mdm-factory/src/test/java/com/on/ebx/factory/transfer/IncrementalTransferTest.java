package com.on.ebx.factory.transfer;

import java.util.LinkedHashMap;
import java.util.Map;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class IncrementalTransferTest
{
	public static void main(final String[] pArgs)
	{
		IncrementalTransferTest.isAllScope_all_isTrue();
		IncrementalTransferTest.isAllScope_caseAndWhitespaceInsensitive();
		IncrementalTransferTest.isAllScope_newIsFalse();
		IncrementalTransferTest.isAllScope_blankOrNullIsFalse();
		IncrementalTransferTest.isAllScope_garbageDefaultsToFalse();
		IncrementalTransferTest.landingPredicate_all_isNoFilter();
		IncrementalTransferTest.landingPredicate_new_isTheNewOrNullStatusPredicate();
		IncrementalTransferTest.shouldWrite_allScope_alwaysWrites();
		IncrementalTransferTest.shouldWrite_fieldMissing_alwaysWrites();
		IncrementalTransferTest.shouldWrite_noExistingRow_writes();
		IncrementalTransferTest.shouldWrite_sameHash_skips();
		IncrementalTransferTest.shouldWrite_differentHash_writes();
		IncrementalTransferTest.mergeLandingStatus_firstDecision_isKept();
		IncrementalTransferTest.mergeLandingStatus_rejectedWinsOverStaged();
		IncrementalTransferTest.mergeLandingStatus_rejectedWinsRegardlessOfOrder();
		IncrementalTransferTest.mergeLandingStatus_bothStaged_staysStaged();
		IncrementalTransferTest.mergeLandingStatus_bothRejected_staysRejected();
		IncrementalTransferTest.mergeLandingStatusMaps_theLiveBugScenario();
		IncrementalTransferTest.landingStatusNeedsWrite_scopeNewAlwaysWrites();
		IncrementalTransferTest.landingStatusNeedsWrite_scopeAllSkipsOnlyWhatIsUpToDate();
		System.out.println("IncrementalTransferTest OK");
	}

	private static void isAllScope_all_isTrue()
	{
		IncrementalTransferTest.check("'all' is the all-scope", IncrementalTransfer.isAllScope("all"));
	}

	private static void isAllScope_caseAndWhitespaceInsensitive()
	{
		IncrementalTransferTest.check("'ALL' is the all-scope", IncrementalTransfer.isAllScope("ALL"));
		IncrementalTransferTest.check("'  All  ' is the all-scope", IncrementalTransfer.isAllScope("  All  "));
	}

	private static void isAllScope_newIsFalse()
	{
		IncrementalTransferTest.check("'new' is not the all-scope", !IncrementalTransfer.isAllScope("new"));
		IncrementalTransferTest.check("'NEW' is not the all-scope", !IncrementalTransfer.isAllScope("NEW"));
	}

	private static void isAllScope_blankOrNullIsFalse()
	{
		IncrementalTransferTest.check("null defaults to new", !IncrementalTransfer.isAllScope(null));
		IncrementalTransferTest.check("blank defaults to new", !IncrementalTransfer.isAllScope("   "));
		IncrementalTransferTest.check("empty defaults to new", !IncrementalTransfer.isAllScope(""));
	}

	private static void isAllScope_garbageDefaultsToFalse()
	{
		IncrementalTransferTest.check("a typo defaults to new, never silently promoted to all", !IncrementalTransfer.isAllScope("alll"));
		IncrementalTransferTest.check("an unrelated word defaults to new", !IncrementalTransfer.isAllScope("everything"));
	}

	private static void landingPredicate_all_isNoFilter()
	{
		IncrementalTransferTest.check("scope=all reads with no filter (null)", IncrementalTransfer.landingPredicate(true) == null);
	}

	private static void landingPredicate_new_isTheNewOrNullStatusPredicate()
	{
		IncrementalTransferTest.check("scope=new reads the NEW-or-null-status predicate",
			"./_status='NEW' or osd:is-null(./_status)".equals(IncrementalTransfer.landingPredicate(false)));
	}

	private static void shouldWrite_allScope_alwaysWrites()
	{
		IncrementalTransferTest.check("scope=all writes even when the hash matches", IncrementalTransfer.shouldWrite("h1", "h1", true, true));
	}

	private static void shouldWrite_fieldMissing_alwaysWrites()
	{
		IncrementalTransferTest.check("no _sourceHash field yet: always write, even when the hashes 'match'",
			IncrementalTransfer.shouldWrite("h1", "h1", false, false));
	}

	private static void shouldWrite_noExistingRow_writes()
	{
		IncrementalTransferTest.check("a CREATE (no existing hash) always writes", IncrementalTransfer.shouldWrite(null, "h1", false, true));
	}

	private static void shouldWrite_sameHash_skips()
	{
		IncrementalTransferTest.check("scope=new, field exists, same hash: skip the write", !IncrementalTransfer.shouldWrite("h1", "h1", false, true));
	}

	private static void shouldWrite_differentHash_writes()
	{
		IncrementalTransferTest.check("scope=new, field exists, different hash: write", IncrementalTransfer.shouldWrite("h1", "h2", false, true));
	}

	private static void mergeLandingStatus_firstDecision_isKept()
	{
		IncrementalTransferTest.check("no prior decision: the incoming one is kept, STAGED",
			"STAGED".equals(IncrementalTransfer.mergeLandingStatus(null, "STAGED")));
		IncrementalTransferTest.check("no prior decision: the incoming one is kept, REJECTED",
			"REJECTED".equals(IncrementalTransfer.mergeLandingStatus(null, "REJECTED")));
	}

	private static void mergeLandingStatus_rejectedWinsOverStaged()
	{
		IncrementalTransferTest.check("existing STAGED, incoming REJECTED: REJECTED wins",
			"REJECTED".equals(IncrementalTransfer.mergeLandingStatus("STAGED", "REJECTED")));
	}

	private static void mergeLandingStatus_rejectedWinsRegardlessOfOrder()
	{
		IncrementalTransferTest.check("existing REJECTED, incoming STAGED: REJECTED still wins",
			"REJECTED".equals(IncrementalTransfer.mergeLandingStatus("REJECTED", "STAGED")));
	}

	private static void mergeLandingStatus_bothStaged_staysStaged()
	{
		IncrementalTransferTest.check("both STAGED: stays STAGED", "STAGED".equals(IncrementalTransfer.mergeLandingStatus("STAGED", "STAGED")));
	}

	private static void mergeLandingStatus_bothRejected_staysRejected()
	{
		IncrementalTransferTest.check("both REJECTED: stays REJECTED", "REJECTED".equals(IncrementalTransfer.mergeLandingStatus("REJECTED", "REJECTED")));
	}

	/** The exact live defect this fix targets, at the pure-map level: two feeds (Supplier's, Company's) touching the SAME landing table, one row rejected by only one of them. */
	private static void mergeLandingStatusMaps_theLiveBugScenario()
	{
		final Map<String, String> accumulated = new LinkedHashMap<String, String>();
		accumulated.put("row1", "STAGED");
		accumulated.put("row2", "STAGED");
		final Map<String, String> secondFeed = new LinkedHashMap<String, String>();
		secondFeed.put("row1", "STAGED"); // Company's feed agrees: still STAGED
		secondFeed.put("row2", "REJECTED"); // Company's feed rejects it, even though Supplier's feed staged it
		secondFeed.put("row3", "STAGED"); // a row only Company's feed ever saw (e.g. a key column Supplier does not map)
		final Map<String, String> merged = IncrementalTransfer.mergeLandingStatusMaps(accumulated, secondFeed);
		IncrementalTransferTest.check("merged map returned is the same (mutated) instance", merged == accumulated);
		IncrementalTransferTest.check("row1 both agree STAGED: stays STAGED", "STAGED".equals(merged.get("row1")));
		IncrementalTransferTest.check("row2 one feed rejects: REJECTED wins", "REJECTED".equals(merged.get("row2")));
		IncrementalTransferTest.check("row3 new to the accumulator: added as STAGED", "STAGED".equals(merged.get("row3")));
		IncrementalTransferTest.check("exactly three rows total, nothing lost or duplicated", merged.size() == 3);
	}

	/** Lot V3-S (2026-10-04). */
	private static void landingStatusNeedsWrite_scopeNewAlwaysWrites()
	{
		IncrementalTransferTest.check("scope new: a NEW row is written", IncrementalTransfer.landingStatusNeedsWrite("NEW", "STAGED", false));
		IncrementalTransferTest.check("scope new: even a row already at the decision is written (the path is not touched)", IncrementalTransfer.landingStatusNeedsWrite("STAGED", "STAGED", false));
	}

	/** Lot V3-S (2026-10-04): a landing row still NEW must become STAGED; one already STAGED is not written again. */
	private static void landingStatusNeedsWrite_scopeAllSkipsOnlyWhatIsUpToDate()
	{
		IncrementalTransferTest.check("scope all: STAGED stays STAGED, no write", !IncrementalTransfer.landingStatusNeedsWrite("STAGED", "STAGED", true));
		IncrementalTransferTest.check("scope all: REJECTED stays REJECTED, no write", !IncrementalTransfer.landingStatusNeedsWrite("REJECTED", "REJECTED", true));
		IncrementalTransferTest.check("scope all: a row still NEW becomes STAGED", IncrementalTransfer.landingStatusNeedsWrite("NEW", "STAGED", true));
		IncrementalTransferTest.check("scope all: a row without a status becomes STAGED", IncrementalTransfer.landingStatusNeedsWrite(null, "STAGED", true));
		IncrementalTransferTest.check("scope all: a previously rejected row now staged is written", IncrementalTransfer.landingStatusNeedsWrite("REJECTED", "STAGED", true));
		IncrementalTransferTest.check("scope all: a previously staged row now rejected is written", IncrementalTransfer.landingStatusNeedsWrite("STAGED", "REJECTED", true));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
