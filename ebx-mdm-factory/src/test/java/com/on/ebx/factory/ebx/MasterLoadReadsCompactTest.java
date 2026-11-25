package com.on.ebx.factory.ebx;

import com.on.ebx.factory.index.DigestMap;
import com.on.ebx.factory.index.KeyDigest;
import com.on.ebx.factory.index.KeyValueIndex;
import com.on.ebx.factory.transfer.RowDigest;

/**
 * JUnit-free: run the main, a failure throws. Lot IDX (2026-10-12): the compact indexes of {@link MasterLoadReads} answer what the {@code HashMap}s did - the Xref index
 * gives the master key back as text, the master index says "listed" / "same values" / "not listed" / "listed with no digest", the merged index says merged or not and
 * names the golden - without a live instance (the indexes are filled by hand, the scans themselves need EBX).
 */
public final class MasterLoadReadsCompactTest
{
	public static void main(final String[] pArgs)
	{
		MasterLoadReadsCompactTest.xrefIndexReturnsTheMasterKeyAsText();
		MasterLoadReadsCompactTest.masterIndexSaysListedSameOrDifferent();
		MasterLoadReadsCompactTest.deferredFieldsAreComparedOnlyWhenPlanned();
		MasterLoadReadsCompactTest.mergedIndexAnswersMergedAndGolden();
		MasterLoadReadsCompactTest.lookupModeKnowsNothing();
		System.out.println("MasterLoadReadsCompactTest OK");
	}

	private static void install(final DigestMap pMap, final String pMasterKey, final RowDigest pStored, final int pOptional)
	{
		final int slot = pMap.addUnique(KeyDigest.of(pMasterKey));
		final long[] words = new long[RowDigest.storedWordCount(pOptional)];
		pStored.storedWords(words, pOptional);
		for (int i = 0; i < words.length; i++)
		{
			pMap.set(slot, i, words[i]);
		}
	}

	private static void xrefIndexReturnsTheMasterKeyAsText()
	{
		final MasterLoadReads reads = MasterLoadReads.lookup("Personne", "test");
		reads.xrefMasterKeys = new KeyValueIndex(10L);
		reads.xrefMasterKeys.add("CRM|C000123", "1000042");
		reads.xrefMasterKeys.add("CRM|C000124", "A|7"); // a composite master key
		MasterLoadReadsCompactTest.check("numeric master key", "1000042".equals(reads.xrefMasterKeys.get("CRM|C000123")));
		MasterLoadReadsCompactTest.check("composite master key", "A|7".equals(reads.xrefMasterKeys.get("CRM|C000124")));
		MasterLoadReadsCompactTest.check("a staging row with no master key yet", reads.xrefMasterKeys.get("CRM|C000125") == null);
		reads.recordWritten("CRM|C000125", "1000043");
		MasterLoadReadsCompactTest.check("a written row's key is there for the child entity", "1000043".equals(reads.xrefMasterKeys.get("CRM|C000125")) && reads.writes == 1L);
		reads.recordWritten("CRM|C000123", "1000099");
		MasterLoadReadsCompactTest.check("a re-pointed key is overwritten, not duplicated", "1000099".equals(reads.xrefMasterKeys.get("CRM|C000123")) && reads.xrefMasterKeys.size() == 3);
	}

	private static void masterIndexSaysListedSameOrDifferent()
	{
		final MasterLoadReads reads = MasterLoadReads.lookup("Personne", "test");
		reads.optionalCount = 0;
		reads.storedDigests = new DigestMap(RowDigest.storedWordCount(0), 10L);
		MasterLoadReadsCompactTest.install(reads.storedDigests, "11", RowDigest.of(new Object[] { "Dupont", "Paris" }, null), 0);
		MasterLoadReadsCompactTest.install(reads.storedDigests, "12", RowDigest.UNKNOWN, 0);
		MasterLoadReadsCompactTest.check("listed", reads.masterIndexed("11") && reads.masterIndexed("12"));
		MasterLoadReadsCompactTest.check("not listed", !reads.masterIndexed("13"));
		MasterLoadReadsCompactTest.check("same values", reads.sameAsStored("11", RowDigest.of(new Object[] { "Dupont", "Paris" }, null)));
		MasterLoadReadsCompactTest.check("different values", !reads.sameAsStored("11", RowDigest.of(new Object[] { "Dupont", "Lyon" }, null)));
		MasterLoadReadsCompactTest.check("listed with no digest known: never the same, so the record is read",
			!reads.sameAsStored("12", RowDigest.of(new Object[] { "Dupont", "Paris" }, null)));
		MasterLoadReadsCompactTest.check("not listed: never the same", !reads.sameAsStored("13", RowDigest.of(new Object[] { "Dupont", "Paris" }, null)));
		MasterLoadReadsCompactTest.check("an unknown planned digest is never the same", !reads.sameAsStored("11", RowDigest.UNKNOWN));
		reads.releaseEntity();
		MasterLoadReadsCompactTest.check("released: nothing listed", !reads.masterIndexed("11") && !reads.sameAsStored("11", RowDigest.of(new Object[] { "Dupont", "Paris" }, null)));
	}

	private static void deferredFieldsAreComparedOnlyWhenPlanned()
	{
		final MasterLoadReads reads = MasterLoadReads.lookup("Compte", "test");
		reads.optionalCount = 1;
		reads.storedDigests = new DigestMap(RowDigest.storedWordCount(1), 10L);
		MasterLoadReadsCompactTest.install(reads.storedDigests, "5", RowDigest.of(new Object[] { "x" }, new Object[] { "parent-9" }), 1);
		MasterLoadReadsCompactTest.check("deferred field equal", reads.sameAsStored("5", RowDigest.of(new Object[] { "x" }, new Object[] { "parent-9" })));
		MasterLoadReadsCompactTest.check("deferred field left out (an update row keeps its stored reference)",
			reads.sameAsStored("5", RowDigest.of(new Object[] { "x" }, new Object[] { RowDigest.OMITTED })));
		MasterLoadReadsCompactTest.check("deferred field planned and different", !reads.sameAsStored("5", RowDigest.of(new Object[] { "x" }, new Object[] { "parent-1" })));
	}

	private static void mergedIndexAnswersMergedAndGolden()
	{
		final MasterLoadReads reads = MasterLoadReads.lookup("Personne", "test");
		MasterLoadReadsCompactTest.check("not read yet", !reads.mergedRead() && !reads.isMerged("12") && !reads.knownNotMerged("12") && reads.goldenOfMerged("12") == null);
		final KeyValueIndex merged = new KeyValueIndex(10L);
		merged.add("12", "40");
		merged.add("13", "");
		merged.add("14", "G|9");
		reads.applyMerged(merged);
		MasterLoadReadsCompactTest.check("read", reads.mergedRead());
		MasterLoadReadsCompactTest.check("merged", reads.isMerged("12") && reads.isMerged("13") && reads.isMerged("14") && !reads.isMerged("40"));
		MasterLoadReadsCompactTest.check("golden as text", "40".equals(reads.goldenOfMerged("12")) && "G|9".equals(reads.goldenOfMerged("14")));
		MasterLoadReadsCompactTest.check("no golden given: ask the add-on", reads.goldenOfMerged("13") == null && !reads.knownNotMerged("13"));
		MasterLoadReadsCompactTest.check("a golden is known not merged", reads.knownNotMerged("40"));
		reads.recordWritten("S|1", "1");
		MasterLoadReadsCompactTest.check("after a write the index is not trusted for a parent's key", !reads.knownNotMerged("40") && reads.goldenOfMerged("12") == null);
		MasterLoadReadsCompactTest.check("but the merged read itself still lists it (the row's own check)", reads.isMerged("12"));
	}

	private static void lookupModeKnowsNothing()
	{
		final MasterLoadReads reads = MasterLoadReads.lookup("X", "reason");
		MasterLoadReadsCompactTest.check("no index, no answer", !reads.masterIndexed("1") && !reads.sameAsStored("1", RowDigest.of(new Object[] { "a" }, null)) && !reads.mergedRead());
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
