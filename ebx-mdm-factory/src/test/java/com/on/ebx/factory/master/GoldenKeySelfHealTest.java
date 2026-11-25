package com.on.ebx.factory.master;

import java.util.LinkedHashMap;
import java.util.Map;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class GoldenKeySelfHealTest
{
	public static void main(final String[] pArgs)
	{
		GoldenKeySelfHealTest.blankTrueGoldenKey_emptyMap();
		GoldenKeySelfHealTest.nullCurrentMap_emptyMap();
		GoldenKeySelfHealTest.allAgree_emptyMap();
		GoldenKeySelfHealTest.oneDisagrees_onlyThatOneWritten();
		GoldenKeySelfHealTest.nullOrBlankCurrentValue_alwaysMismatch();
		GoldenKeySelfHealTest.trueGoldenKeyTrimmed();
		GoldenKeySelfHealTest.singleKeyStaleValue_oneRowWritten();
		GoldenKeySelfHealTest.singleKeyNoXrefRow_nothingWritten();
		System.out.println("GoldenKeySelfHealTest OK");
	}

	private static void blankTrueGoldenKey_emptyMap()
	{
		final Map<String, String> current = new LinkedHashMap<String, String>();
		current.put("290", "183");
		GoldenKeySelfHealTest.check("null truth -> nothing to write", GoldenKeySelfHeal.rowsToWrite(null, current).isEmpty());
		GoldenKeySelfHealTest.check("blank truth -> nothing to write", GoldenKeySelfHeal.rowsToWrite("   ", current).isEmpty());
	}

	private static void nullCurrentMap_emptyMap()
	{
		GoldenKeySelfHealTest.check("null current map -> nothing to write", GoldenKeySelfHeal.rowsToWrite("322", null).isEmpty());
	}

	private static void allAgree_emptyMap()
	{
		final Map<String, String> current = new LinkedHashMap<String, String>();
		current.put("290", "322");
		current.put("248", "322");
		current.put("322", "322");
		GoldenKeySelfHealTest.check("every row already agrees - nothing to write", GoldenKeySelfHeal.rowsToWrite("322", current).isEmpty());
	}

	private static void oneDisagrees_onlyThatOneWritten()
	{
		// the BPCE scenario the product owner must check on a real instance: 290/248/322 all correct except 290,
		// which stayed pointed at the OLD golden after an Unmerge nobody refreshed Xref for
		final Map<String, String> current = new LinkedHashMap<String, String>();
		current.put("290", "183");
		current.put("248", "322");
		current.put("322", "322");
		final Map<String, String> toWrite = GoldenKeySelfHeal.rowsToWrite("322", current);
		GoldenKeySelfHealTest.check("exactly one row needs correction", toWrite.size() == 1);
		GoldenKeySelfHealTest.check("the stale row is 290", toWrite.containsKey("290"));
		GoldenKeySelfHealTest.check("corrected to the true golden", "322".equals(toWrite.get("290")));
	}

	private static void nullOrBlankCurrentValue_alwaysMismatch()
	{
		final Map<String, String> current = new LinkedHashMap<String, String>();
		current.put("290", null);
		current.put("248", "");
		final Map<String, String> toWrite = GoldenKeySelfHeal.rowsToWrite("322", current);
		GoldenKeySelfHealTest.check("null current value is a mismatch", "322".equals(toWrite.get("290")));
		GoldenKeySelfHealTest.check("blank current value is a mismatch", "322".equals(toWrite.get("248")));
	}

	private static void trueGoldenKeyTrimmed()
	{
		final Map<String, String> current = new LinkedHashMap<String, String>();
		current.put("290", "322");
		GoldenKeySelfHealTest.check("surrounding whitespace in the truth is trimmed before comparing",
			GoldenKeySelfHeal.rowsToWrite(" 322 ", current).isEmpty());
	}

	private static void singleKeyStaleValue_oneRowWritten()
	{
		// the defect measured live 2026-09-23 (golden 322 / member 290, after an Unmerge): computeMerge
		// resolves contributingKeys == {"290"} for the now-isolated 290 (a single contributing key - itself),
		// but its Xref row still carries the stale golden it no longer belongs to. The caller
		// (ebx.RecordLineageReader#selfHealGoldenKeys) used to return early whenever there was only one
		// contributing key; this asserts the pure decision itself has always corrected a single-key
		// disagreement - it is the caller's early-return shortcut that was wrong, not this function.
		final Map<String, String> current = new LinkedHashMap<String, String>();
		current.put("290", "322");
		final Map<String, String> toWrite = GoldenKeySelfHeal.rowsToWrite("290", current);
		GoldenKeySelfHealTest.check("exactly one row needs correction", toWrite.size() == 1);
		GoldenKeySelfHealTest.check("corrected to the record's own key", "290".equals(toWrite.get("290")));
	}

	private static void singleKeyNoXrefRow_nothingWritten()
	{
		// the DELETED auto-created golden case (`docs/MATCHING.md` &sect;20): looked up directly, its own
		// contributingKeys collapses to just itself once nothing points at it any more, but an auto-created
		// golden never had an Xref row of its own - ebx.RecordLineageReader#selfHealGoldenKeys' scoped Xref
		// read then finds nothing, so pCurrentGoldenKeyByMasterKey arrives here empty. Nothing to compare
		// against means nothing to write - never invent a row.
		final Map<String, String> current = new LinkedHashMap<String, String>();
		GoldenKeySelfHealTest.check("no Xref row for the sole contributing key -> nothing to write", GoldenKeySelfHeal.rowsToWrite("322", current).isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
