package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import com.on.ebx.factory.quality.ChildKeysDerivation;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. {@link ChildKeysSweep}
 * (lots M3 and M10) is exercised against an in-memory child table and parent table: parents with no, one and
 * several children, a child removed, a child moved to another parent, a sweep over unchanged data that writes
 * nothing, the batching, the single pass (one scan of each table, no lookup, writes through the record in hand),
 * the partitions above the memory budget giving the same answer, the parent-key hash collision, the sort of the
 * pairs, the fold cache, the cap and the cancellation.
 */
public final class ChildKeysSweepTest
{
	public static void main(final String[] pArgs)
	{
		ChildKeysSweepTest.parentsWithNoOneAndSeveralChildren();
		ChildKeysSweepTest.aRemovedChildIsRecomputedFromTheRemainingOnes();
		ChildKeysSweepTest.aParentWhoseLastChildWasRemovedIsClearedInTheSamePass();
		ChildKeysSweepTest.aChildMovedToAnotherParentUpdatesBoth();
		ChildKeysSweepTest.aSecondSweepOverUnchangedDataWritesNothing();
		ChildKeysSweepTest.writesGoOutInBatches();
		ChildKeysSweepTest.oneScanOfEachTableAndWritesThroughTheRecordInHand();
		ChildKeysSweepTest.partitionedScansGiveTheSameAnswerAsOneScan();
		ChildKeysSweepTest.aPartitionedSweepFoldsEachChildOnceAndScansEachTablePerPartition();
		ChildKeysSweepTest.partitionsFollowTheTableSize();
		ChildKeysSweepTest.childrenWithoutParentWithoutKeyOrOfAMissingParentAreCounted();
		ChildKeysSweepTest.theCapIsCountedPerParent();
		ChildKeysSweepTest.theHashEncodingGivesTheOpaqueTokensSorted();
		ChildKeysSweepTest.theFoldCacheFoldsEachDistinctValueOnce();
		ChildKeysSweepTest.aParentKeyHashCollisionRestartsWithAnotherSeed();
		ChildKeysSweepTest.sortPairsAgreesWithAReferenceSort();
		ChildKeysSweepTest.pairsAreDedupedAndFoundByTheirHash();
		ChildKeysSweepTest.theSingleParentWithManyChildrenIsNotQuadratic();
		ChildKeysSweepTest.aCancellationStopsAfterTheCurrentBatchAndSaysSo();
		ChildKeysSweepTest.aFailedWriteIsNeverHidden();
		ChildKeysSweepTest.theKeyHashIsStableAndSpread();
		System.out.println("ChildKeysSweepTest OK");
	}

	/** One part, readable keys: the value of a parent is the folded (upper case, no space or underscore) parts, sorted. */
	private static final ChildKeysDerivation.Definition PLAIN = ChildKeysDerivation.parse("{\"child\":\"Adresse\",\"fields\":[\"code\"],\"encoding\":\"plain\"}");
	private static final ChildKeysDerivation.Definition HASH = ChildKeysDerivation.parse("{\"child\":\"Adresse\",\"fields\":[\"code\"]}");

	/** A parent row: the handle the scan gives and the write modifies. */
	private static final class Row
	{
		final String key;
		String value;

		Row(final String pKey, final String pValue)
		{
			this.key = pKey;
			this.value = pValue;
		}
	}

	/** The parent table: no lookup of any kind, a scan over a frozen list of rows, and writes by row. */
	private static final class FakeParents implements ChildKeysSweep.Parents
	{
		final Map<String, Row> rows = new LinkedHashMap<String, Row>();
		final List<List<ChildKeysSweep.Update>> batches = new ArrayList<List<ChildKeysSweep.Update>>();
		int scans;
		boolean failWrites;
		boolean handlesAreTheScannedRows = true;

		@Override
		public void scan(final ChildKeysSweep.ParentVisitor pVisitor) throws Exception
		{
			this.scans++;
			for (final Row row : new ArrayList<Row>(this.rows.values()))
			{
				if (!pVisitor.parent(row.key, row.value, row))
				{
					return;
				}
			}
		}

		@Override
		public void write(final List<ChildKeysSweep.Update> pBatch)
		{
			if (this.failWrites)
			{
				throw new IllegalStateException("EBX refused the save");
			}
			this.batches.add(new ArrayList<ChildKeysSweep.Update>(pBatch));
			for (final ChildKeysSweep.Update update : pBatch)
			{
				if (!(update.handle instanceof Row) || this.rows.get(update.key) != update.handle)
				{
					this.handlesAreTheScannedRows = false;
				}
				((Row) update.handle).value = update.value;
			}
		}

		String value(final String pKey)
		{
			return this.rows.get(pKey).value;
		}

		int writtenRows()
		{
			int count = 0;
			for (final List<ChildKeysSweep.Update> batch : this.batches)
			{
				count += batch.size();
			}
			return count;
		}
	}

	/** The child table: (parent key, raw value of the one part) rows, scanned in order. */
	private static final class FakeChildren implements ChildKeysSweep.Children
	{
		final List<String[]> rows = new ArrayList<String[]>();
		int scans;

		FakeChildren child(final String pParent, final String pRaw)
		{
			this.rows.add(new String[] { pParent, pRaw });
			return this;
		}

		@Override
		public void scan(final ChildKeysSweep.ChildVisitor pVisitor)
		{
			this.scans++;
			final List<String> parts = new ArrayList<String>();
			for (final String[] row : this.rows)
			{
				parts.clear();
				parts.add(row[1]);
				pVisitor.child(row[0], parts);
			}
		}
	}

	private static FakeParents parents(final String... pKeys)
	{
		final FakeParents parents = new FakeParents();
		for (final String key : pKeys)
		{
			parents.rows.put(key, new Row(key, null));
		}
		return parents;
	}

	private static ChildKeysSweep.Result run(final FakeChildren pChildren, final FakeParents pParents, final int pMaxLength, final int pPartitions, final int pBatchSize)
	{
		return ChildKeysSweepTest.run(ChildKeysSweepTest.PLAIN, pChildren, pParents, pMaxLength, pPartitions, pBatchSize);
	}

	private static ChildKeysSweep.Result run(final ChildKeysDerivation.Definition pDefinition, final FakeChildren pChildren, final FakeParents pParents, final int pMaxLength,
		final int pPartitions, final int pBatchSize)
	{
		try
		{
			return ChildKeysSweep.run(pChildren, pParents, pDefinition, pMaxLength, pPartitions, pBatchSize, null);
		}
		catch (final Exception ex)
		{
			throw new AssertionError("the sweep threw: " + ex);
		}
	}

	private static void parentsWithNoOneAndSeveralChildren()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("P0", "P1", "P2");
		final FakeChildren children = new FakeChildren().child("P1", "75008 rue").child("P2", "69001 quai").child("P2", "13001 port").child("P2", "69001 quai");
		final ChildKeysSweep.Result result = ChildKeysSweepTest.run(children, parents, 2000, 1, 100);
		ChildKeysSweepTest.check("P0 has no child: stays empty, never written", parents.value("P0") == null);
		ChildKeysSweepTest.check("P1 has its one key: " + parents.value("P1"), "75008RUE".equals(parents.value("P1")));
		ChildKeysSweepTest.check("P2 has its distinct keys, sorted: " + parents.value("P2"), "13001PORT 69001QUAI".equals(parents.value("P2")));
		ChildKeysSweepTest.check("two parents updated", result.parentsUpdated == 2 && result.parentsWithKeys == 2 && result.parentsCleared == 0);
		ChildKeysSweepTest.check("four child rows read", result.childrenRead == 4);
		ChildKeysSweepTest.check("three parents read", result.parentsRead == 3);
		ChildKeysSweepTest.check("P0 never in a write", parents.writtenRows() == 2);
		ChildKeysSweepTest.check("P0 empty and still empty is a write not done", result.writesSkippedUnchanged == 1 && result.parentsUnchanged == 0);
	}

	private static void aRemovedChildIsRecomputedFromTheRemainingOnes()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("P1");
		ChildKeysSweepTest.run(new FakeChildren().child("P1", "a1").child("P1", "b2").child("P1", "c3"), parents, 2000, 1, 100);
		ChildKeysSweepTest.check("three keys", "A1 B2 C3".equals(parents.value("P1")));
		// b2's child row is gone
		final ChildKeysSweep.Result result = ChildKeysSweepTest.run(new FakeChildren().child("P1", "a1").child("P1", "c3"), parents, 2000, 1, 100);
		ChildKeysSweepTest.check("recomputed from ALL the remaining children: " + parents.value("P1"), "A1 C3".equals(parents.value("P1")));
		ChildKeysSweepTest.check("one parent updated", result.parentsUpdated == 1);
	}

	private static void aParentWhoseLastChildWasRemovedIsClearedInTheSamePass()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("P1", "P2");
		ChildKeysSweepTest.run(new FakeChildren().child("P1", "a1").child("P2", "b2"), parents, 2000, 1, 100);
		final int scansBefore = parents.scans;
		// P1's only child was deleted: no child row refers to P1 any more
		final ChildKeysSweep.Result result = ChildKeysSweepTest.run(new FakeChildren().child("P2", "b2"), parents, 2000, 1, 100);
		ChildKeysSweepTest.check("P1 no longer holds a key", parents.value("P1") == null);
		ChildKeysSweepTest.check("P2 untouched", "B2".equals(parents.value("P2")));
		ChildKeysSweepTest.check("one parent cleared, one unchanged", result.parentsCleared == 1 && result.parentsUnchanged == 1 && result.parentsUpdated == 0);
		ChildKeysSweepTest.check("the clearing needed no scan of its own: one scan of the parents", parents.scans - scansBefore == 1);
	}

	private static void aChildMovedToAnotherParentUpdatesBoth()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("P1", "P2");
		ChildKeysSweepTest.run(new FakeChildren().child("P1", "a1"), parents, 2000, 1, 100);
		// the child now refers to P2
		final ChildKeysSweep.Result result = ChildKeysSweepTest.run(new FakeChildren().child("P2", "a1"), parents, 2000, 1, 100);
		ChildKeysSweepTest.check("the old parent loses it", parents.value("P1") == null);
		ChildKeysSweepTest.check("the new parent gets it", "A1".equals(parents.value("P2")));
		ChildKeysSweepTest.check("one updated, one cleared", result.parentsUpdated == 1 && result.parentsCleared == 1);
	}

	private static void aSecondSweepOverUnchangedDataWritesNothing()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("P1", "P2", "P3");
		final FakeChildren children = new FakeChildren().child("P1", "a1").child("P2", "b2").child("P2", "c3");
		ChildKeysSweepTest.run(children, parents, 2000, 1, 100);
		final int writtenAfterFirst = parents.writtenRows();
		final int batchesAfterFirst = parents.batches.size();
		final ChildKeysSweep.Result second = ChildKeysSweepTest.run(children, parents, 2000, 1, 100);
		ChildKeysSweepTest.check("nothing rewritten", parents.writtenRows() == writtenAfterFirst && parents.batches.size() == batchesAfterFirst);
		ChildKeysSweepTest.check("no transaction at all", second.writeBatches == 0);
		ChildKeysSweepTest.check("both parents with keys counted unchanged", second.parentsUnchanged == 2 && second.parentsUpdated == 0 && second.parentsCleared == 0);
		ChildKeysSweepTest.check("every parent read was a write not done: " + second.writesSkippedUnchanged, second.parentsRead == 3 && second.writesSkippedUnchanged == 3);
		ChildKeysSweepTest.check("exactly two scans", second.scans == 2);
	}

	private static void writesGoOutInBatches()
	{
		final String[] keys = new String[7];
		final FakeChildren children = new FakeChildren();
		for (int i = 0; i < keys.length; i++)
		{
			keys[i] = "P" + i;
			children.child(keys[i], "k" + i);
		}
		final FakeParents parents = ChildKeysSweepTest.parents(keys);
		final ChildKeysSweep.Result result = ChildKeysSweepTest.run(children, parents, 2000, 1, 3);
		ChildKeysSweepTest.check("7 updates in batches of 3: 3 + 3 + 1, got " + parents.batches.size(), parents.batches.size() == 3);
		ChildKeysSweepTest.check("no batch over 3", parents.batches.get(0).size() == 3 && parents.batches.get(1).size() == 3 && parents.batches.get(2).size() == 1);
		ChildKeysSweepTest.check("every parent written once", parents.writtenRows() == 7);
		ChildKeysSweepTest.check("the transactions are counted", result.writeBatches == 3);
	}

	private static void oneScanOfEachTableAndWritesThroughTheRecordInHand()
	{
		final List<String> keys = new ArrayList<String>();
		final FakeChildren children = new FakeChildren();
		for (int i = 0; i < 1000; i++)
		{
			keys.add("SRC|" + i);
			children.child("SRC|" + i, "street " + (i % 50)).child("SRC|" + i, "zone " + i);
		}
		final FakeParents parents = ChildKeysSweepTest.parents(keys.toArray(new String[0]));
		final ChildKeysSweep.Result result = ChildKeysSweepTest.run(children, parents, 2000, 1, 100);
		ChildKeysSweepTest.check("one scan of the child table: " + children.scans, children.scans == 1);
		ChildKeysSweepTest.check("one scan of the parents: " + parents.scans, parents.scans == 1);
		ChildKeysSweepTest.check("two scans reported", result.scans == 2 && result.partitions == 1);
		ChildKeysSweepTest.check("every write went through the very row the scan handed over", parents.handlesAreTheScannedRows);
		ChildKeysSweepTest.check("1000 parents updated in 10 batches", result.parentsUpdated == 1000 && parents.batches.size() == 10);
		ChildKeysSweepTest.check("every parent holds its two keys", "STREET0 ZONE0".equals(parents.value("SRC|0")) && "STREET49 ZONE999".equals(parents.value("SRC|999")));
	}

	private static void partitionedScansGiveTheSameAnswerAsOneScan()
	{
		final List<String> keys = new ArrayList<String>();
		final FakeChildren children = new FakeChildren();
		for (int i = 0; i < 200; i++)
		{
			final String parent = "SRC|" + i;
			keys.add(parent);
			children.child(parent, "k" + (i % 7) + "a").child(parent, "k" + (i % 5) + "b");
		}
		children.child(null, "k9z").child("SRC|0", null);
		final FakeParents one = ChildKeysSweepTest.parents(keys.toArray(new String[0]));
		final FakeParents several = ChildKeysSweepTest.parents(keys.toArray(new String[0]));
		// a stale value that no child supports, and that must be cleared in whichever partition it falls
		one.rows.put("SRC|stale", new Row("SRC|stale", "OLD1"));
		several.rows.put("SRC|stale", new Row("SRC|stale", "OLD1"));
		final ChildKeysSweep.Result a = ChildKeysSweepTest.run(children, one, 2000, 1, 50);
		final ChildKeysSweep.Result b = ChildKeysSweepTest.run(children, several, 2000, 5, 50);
		final Map<String, String> valuesOne = new LinkedHashMap<String, String>();
		final Map<String, String> valuesSeveral = new LinkedHashMap<String, String>();
		for (final Row row : one.rows.values())
		{
			valuesOne.put(row.key, row.value);
		}
		for (final Row row : several.rows.values())
		{
			valuesSeveral.put(row.key, row.value);
		}
		ChildKeysSweepTest.check("the same values with 1 and 5 partitions", valuesOne.equals(valuesSeveral));
		ChildKeysSweepTest.check("the stale parent was cleared in both", one.value("SRC|stale") == null && several.value("SRC|stale") == null);
		ChildKeysSweepTest.check("the same counters except the scans",
			a.parentsUpdated == b.parentsUpdated && a.parentsCleared == b.parentsCleared && a.parentsWithKeys == b.parentsWithKeys && a.childrenRead == b.childrenRead
				&& a.childrenWithoutParent == b.childrenWithoutParent && a.childrenWithoutKey == b.childrenWithoutKey && a.parentsRead == b.parentsRead
				&& a.writesSkippedUnchanged == b.writesSkippedUnchanged);
		ChildKeysSweepTest.check("child rows are counted once, not once per partition: " + b.childrenRead, b.childrenRead == 402);
		ChildKeysSweepTest.check("parents are counted once, not once per partition: " + b.parentsRead, b.parentsRead == 201);
		ChildKeysSweepTest.check("every parent is in exactly one partition", ChildKeysSweep.partitionOf("SRC|3", 5) >= 0 && ChildKeysSweep.partitionOf("SRC|3", 5) < 5);
	}

	private static void aPartitionedSweepFoldsEachChildOnceAndScansEachTablePerPartition()
	{
		final FakeChildren children = new FakeChildren();
		final List<String> keys = new ArrayList<String>();
		for (int i = 0; i < 400; i++)
		{
			keys.add("P" + i);
			children.child("P" + i, "distinct value " + i); // every raw value different: no cache hit, so folds counts the rows folded
		}
		final FakeParents parents = ChildKeysSweepTest.parents(keys.toArray(new String[0]));
		final ChildKeysSweep.Result result = ChildKeysSweepTest.run(children, parents, 2000, 4, 100000);
		ChildKeysSweepTest.check("four scans of the child table: " + children.scans, children.scans == 4);
		ChildKeysSweepTest.check("four scans of the parents: " + parents.scans, parents.scans == 4);
		ChildKeysSweepTest.check("eight scans reported", result.scans == 8 && result.partitions == 4);
		ChildKeysSweepTest.check("each child key is folded once in total, in its parent's partition: " + result.partFolds, result.partFolds == 400 && result.partCacheHits == 0);
		ChildKeysSweepTest.check("every parent got its key", parents.writtenRows() == 400);
	}

	private static void partitionsFollowTheTableSize()
	{
		ChildKeysSweepTest.check("below the bound: one pass", ChildKeysSweep.partitionCount(100, 8000000) == 1);
		ChildKeysSweepTest.check("an empty table: one pass", ChildKeysSweep.partitionCount(0, 8000000) == 1);
		ChildKeysSweepTest.check("889,165 children, 8,000,000 in memory: one pass", ChildKeysSweep.partitionCount(889165, 8000000) == 1);
		ChildKeysSweepTest.check("889,165 children, 400,000 in memory (plain): 3 passes", ChildKeysSweep.partitionCount(889165, 400000) == 3);
		ChildKeysSweepTest.check("exactly the bound: one pass", ChildKeysSweep.partitionCount(400000, 400000) == 1);
		ChildKeysSweepTest.check("one over: two passes", ChildKeysSweep.partitionCount(400001, 400000) == 2);
		ChildKeysSweepTest.check("20 million children: 3 passes", ChildKeysSweep.partitionCount(20000000, 8000000) == 3);
		ChildKeysSweepTest.check("never more than the maximum", ChildKeysSweep.partitionCount(Long.MAX_VALUE / 2, 1) == ChildKeysSweep.MAX_PARTITIONS);
		ChildKeysSweepTest.check("a nonsense bound reads as 1", ChildKeysSweep.partitionCount(5, 0) == 5);
	}

	private static void childrenWithoutParentWithoutKeyOrOfAMissingParentAreCounted()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("P1");
		final FakeChildren children = new FakeChildren().child(null, "a1").child("P1", null).child("P1", "b2").child("GONE", "c3").child("GONE", "d4").child("GONE", "d4");
		final ChildKeysSweep.Result result = ChildKeysSweepTest.run(children, parents, 2000, 1, 10);
		ChildKeysSweepTest.check("one without parent", result.childrenWithoutParent == 1);
		ChildKeysSweepTest.check("one without key", result.childrenWithoutKey == 1);
		ChildKeysSweepTest.check("two distinct keys of a parent that does not exist: " + result.childrenOfMissingParent, result.childrenOfMissingParent == 2);
		ChildKeysSweepTest.check("a missing parent is never created", !parents.rows.containsKey("GONE"));
		ChildKeysSweepTest.check("the real parent still gets its key", "B2".equals(parents.value("P1")));
		ChildKeysSweepTest.check("six child rows read", result.childrenRead == 6);
	}

	private static void theCapIsCountedPerParent()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("P1", "P2");
		final FakeChildren children = new FakeChildren().child("P1", "aaaa").child("P1", "bbbb").child("P1", "cccc").child("P2", "aaaa");
		final ChildKeysSweep.Result result = ChildKeysSweepTest.run(children, parents, 9, 1, 10);
		ChildKeysSweepTest.check("P1 is capped to what fits: " + parents.value("P1"), "AAAA BBBB".equals(parents.value("P1")));
		ChildKeysSweepTest.check("one key dropped on one parent", result.tokensDropped == 1 && result.parentsCapped == 1);
		ChildKeysSweepTest.check("P2 untouched by the cap", "AAAA".equals(parents.value("P2")));
		ChildKeysSweepTest.check("the summary says it", result.summary().contains("1 key(s) dropped by the cap on 1 parent(s)"));
	}

	private static void theHashEncodingGivesTheOpaqueTokensSorted()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("P1", "P2");
		final FakeChildren children = new FakeChildren().child("P1", "75008 rue").child("P1", "13001 port").child("P1", "75008 Rue").child("P2", "x");
		ChildKeysSweepTest.run(ChildKeysSweepTest.HASH, children, parents, 2000, 1, 10);
		final TreeSet<String> expected = new TreeSet<String>(Arrays.asList(ChildKeysDerivation.opaque("75008RUE"), ChildKeysDerivation.opaque("13001PORT")));
		final StringBuilder text = new StringBuilder();
		for (final String token : expected)
		{
			text.append(text.length() == 0 ? "" : " ").append(token);
		}
		ChildKeysSweepTest.check("P1 holds the sorted opaque tokens of its distinct keys: " + parents.value("P1"), text.toString().equals(parents.value("P1")));
		ChildKeysSweepTest.check("P2 holds its single token: " + parents.value("P2"), ChildKeysDerivation.opaque("X").equals(parents.value("P2")));
	}

	private static void theFoldCacheFoldsEachDistinctValueOnce()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("P1", "P2", "P3");
		final FakeChildren children = new FakeChildren();
		for (int i = 0; i < 300; i++)
		{
			children.child("P" + (1 + i % 3), "Rue de la Paix");
		}
		children.child("P1", "Quai d'Orsay");
		final ChildKeysSweep.Result result = ChildKeysSweepTest.run(ChildKeysSweepTest.HASH, children, parents, 2000, 1, 10);
		ChildKeysSweepTest.check("two distinct raw values folded once each: " + result.partFolds, result.partFolds == 2);
		ChildKeysSweepTest.check("every other part came from the cache: " + result.partCacheHits, result.partCacheHits == 299);
		ChildKeysSweepTest.check("P2 holds the one token of the street", ChildKeysDerivation.opaque("RUEDELAPAIX").equals(parents.value("P2")));

		// the encoder itself: the same code as token(), an LRU that stays bounded, and a value that folds to nothing
		final ChildKeysDerivation.Definition address = ChildKeysDerivation.parse("{\"child\":\"A\",\"fields\":[\"pc\",\"no\",\"street\"],\"required\":[\"pc\"]}");
		final ChildKeysDerivation.KeyEncoder encoder = new ChildKeysDerivation.KeyEncoder(address, 4);
		final Random random = new Random(7);
		for (int i = 0; i < 2000; i++)
		{
			final List<String> parts = Arrays.asList(random.nextInt(10) == 0 ? "  " : "750" + random.nextInt(30), random.nextInt(3) == 0 ? null : String.valueOf(random.nextInt(20)),
				random.nextInt(3) == 0 ? "" : "Rue d'Été " + random.nextInt(40));
			final long code = encoder.encode(parts);
			final String token = ChildKeysDerivation.token(address, parts);
			ChildKeysSweepTest.check("encode/render equals token for " + parts, token == null ? code == ChildKeysDerivation.KeyEncoder.NO_KEY : token.equals(encoder.render(code)));
		}
		ChildKeysSweepTest.check("the 4-entry cache did serve some parts and fold others", encoder.cacheHits > 0 && encoder.folds > 0);
		final ChildKeysDerivation.Definition plain = ChildKeysDerivation.parse("{\"child\":\"A\",\"fields\":[\"pc\",\"street\"],\"encoding\":\"plain\"}");
		final ChildKeysDerivation.KeyEncoder plainEncoder = new ChildKeysDerivation.KeyEncoder(plain, 10);
		final long first = plainEncoder.encode(Arrays.asList("75008", "Rue X"));
		ChildKeysSweepTest.check("a plain key renders as the readable key", "75008_RUEX".equals(plainEncoder.render(first)));
		ChildKeysSweepTest.check("the same key keeps its code", first == plainEncoder.encode(Arrays.asList("75008 ", "rue x")));
	}

	private static void aParentKeyHashCollisionRestartsWithAnotherSeed()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("P1", "P2", "P3");
		final FakeChildren children = new FakeChildren().child("P1", "a1").child("P2", "b2").child("P3", "c3");
		final ChildKeysSweep.Result result;
		try
		{
			// seed 0: P1 and P2 collide; from seed 1 on the hash is the real one
			result = ChildKeysSweep.run(children, parents, ChildKeysSweepTest.PLAIN, 2000, 1, 3, 10, null, new ChildKeysSweep.Hasher()
			{
				@Override
				public long hash(final String pKey, final int pSeed)
				{
					return pSeed == 0 && (pKey.equals("P1") || pKey.equals("P2")) ? 42L : ChildKeysSweep.keyHash(pKey, pSeed);
				}
			});
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex.toString());
		}
		ChildKeysSweepTest.check("one restart", result.hashRestarts == 1);
		ChildKeysSweepTest.check("every parent ends with ITS OWN key, not the other's: " + parents.value("P1") + " " + parents.value("P2"),
			"A1".equals(parents.value("P1")) && "B2".equals(parents.value("P2")) && "C3".equals(parents.value("P3")));

		boolean refused = false;
		try
		{
			ChildKeysSweep.run(children, parents, ChildKeysSweepTest.PLAIN, 2000, 1, 3, 10, null, new ChildKeysSweep.Hasher()
			{
				@Override
				public long hash(final String pKey, final int pSeed)
				{
					return 1L; // every parent collides, whatever the seed
				}
			});
		}
		catch (final IllegalStateException ex)
		{
			refused = ex.getMessage().contains("cannot be chance");
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex.toString());
		}
		ChildKeysSweepTest.check("a hash that always collides ends the sweep with an error, never a wrong value", refused);
	}

	private static void sortPairsAgreesWithAReferenceSort()
	{
		final Random random = new Random(11);
		for (final int range : new int[] { 3, 50, 1000000 })
		{
			for (final int size : new int[] { 0, 1, 2, 15, 16, 17, 100, 20000 })
			{
				final long[] a = new long[size];
				final long[] b = new long[size];
				final List<long[]> reference = new ArrayList<long[]>();
				for (int i = 0; i < size; i++)
				{
					a[i] = random.nextInt(range) - range / 2;
					b[i] = random.nextInt(range) * (random.nextBoolean() ? 1L : 1000003L);
					reference.add(new long[] { a[i], b[i] });
				}
				Collections.sort(reference, new Comparator<long[]>()
				{
					@Override
					public int compare(final long[] pX, final long[] pY)
					{
						return pX[0] != pY[0] ? (pX[0] < pY[0] ? -1 : 1) : (pX[1] == pY[1] ? 0 : pX[1] < pY[1] ? -1 : 1);
					}
				});
				ChildKeysSweep.sortPairs(a, b, 0, size);
				for (int i = 0; i < size; i++)
				{
					ChildKeysSweepTest.check("pair " + i + " of " + size + " (range " + range + ")", a[i] == reference.get(i)[0] && b[i] == reference.get(i)[1]);
				}
			}
		}
		// a sub-range only: the rest is untouched
		final long[] a = { 9, 8, 7, 6, 5, 4, 3, 2, 1, 0 };
		final long[] b = new long[10];
		ChildKeysSweep.sortPairs(a, b, 2, 7);
		ChildKeysSweepTest.check("[2,7) sorted, the rest left alone", Arrays.equals(a, new long[] { 9, 8, 3, 4, 5, 6, 7, 2, 1, 0 }));
	}

	private static void pairsAreDedupedAndFoundByTheirHash()
	{
		final ChildKeysSweep.Pairs pairs = new ChildKeysSweep.Pairs(2);
		final long[][] input = { { 5, 2 }, { 3, 9 }, { 5, 1 }, { 5, 2 }, { 3, 9 }, { 8, 8 }, { -4, 0 } };
		for (final long[] pair : input)
		{
			pairs.add(pair[0], pair[1]);
		}
		pairs.sortAndDedupe();
		ChildKeysSweepTest.check("five distinct pairs: " + pairs.size(), pairs.size() == 5 && pairs.rawSize == 7);
		ChildKeysSweepTest.check("sorted", pairs.hashes[0] == -4 && pairs.hashes[1] == 3 && pairs.hashes[2] == 5 && pairs.codes[2] == 1 && pairs.codes[3] == 2 && pairs.hashes[4] == 8);
		ChildKeysSweepTest.check("the first pair of a hash is found", pairs.firstOf(5) == 2 && pairs.firstOf(3) == 1 && pairs.firstOf(-4) == 0 && pairs.firstOf(8) == 4);
		ChildKeysSweepTest.check("an absent hash is not found", pairs.firstOf(4) == -1 && pairs.firstOf(100) == -1 && pairs.firstOf(-100) == -1);
		ChildKeysSweepTest.check("an empty set finds nothing", new ChildKeysSweep.Pairs(16).firstOf(1) == -1);
	}

	private static void theSingleParentWithManyChildrenIsNotQuadratic()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("BIG");
		final FakeChildren children = new FakeChildren();
		for (int i = 0; i < 200000; i++)
		{
			children.child("BIG", "k" + (i % 1000));
		}
		final long started = System.nanoTime();
		final ChildKeysSweep.Result result = ChildKeysSweepTest.run(ChildKeysSweepTest.HASH, children, parents, 20000, 1, 100);
		final long millis = (System.nanoTime() - started) / 1000000;
		ChildKeysSweepTest.check("200,000 children of one parent, 1,000 distinct keys, in a blink (" + millis + " ms)", millis < 5000);
		ChildKeysSweepTest.check("the parent holds its keys: " + result.tokensDropped, result.parentsWithKeys == 1 && parents.value("BIG") != null);
		ChildKeysSweepTest.check("1,000 distinct keys of 13 letters need 13,999 characters: nothing dropped by a cap of 20,000", result.tokensDropped == 0);
	}

	private static void aCancellationStopsAfterTheCurrentBatchAndSaysSo()
	{
		final String[] keys = new String[10];
		final FakeChildren children = new FakeChildren();
		for (int i = 0; i < keys.length; i++)
		{
			keys[i] = "P" + i;
			children.child(keys[i], "k" + i);
		}
		final FakeParents parents = ChildKeysSweepTest.parents(keys);
		final ChildKeysSweep.Result result;
		try
		{
			result = ChildKeysSweep.run(children, parents, ChildKeysSweepTest.PLAIN, 2000, 1, 4, new ChildKeysSweep.Cancellation()
			{
				@Override
				public boolean cancelled()
				{
					return true;
				}
			});
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex.toString());
		}
		ChildKeysSweepTest.check("stopped", result.cancelled);
		ChildKeysSweepTest.check("one batch of 4 written, no more", parents.batches.size() == 1 && parents.writtenRows() == 4);
		ChildKeysSweepTest.check("the summary says it was stopped", result.summary().contains("Stopped"));
	}

	private static void aFailedWriteIsNeverHidden()
	{
		final FakeParents parents = ChildKeysSweepTest.parents("P1");
		parents.failWrites = true;
		boolean thrown = false;
		try
		{
			ChildKeysSweep.run(new FakeChildren().child("P1", "a1"), parents, ChildKeysSweepTest.PLAIN, 2000, 1, 10, null);
		}
		catch (final Exception ex)
		{
			thrown = ex.getMessage().contains("refused");
		}
		ChildKeysSweepTest.check("the failure reaches the caller, a sweep never reports a success it did not have", thrown);
	}

	private static void theKeyHashIsStableAndSpread()
	{
		ChildKeysSweepTest.check("the hash is a pure function of key and seed", ChildKeysSweep.keyHash("SRC|1", 0) == ChildKeysSweep.keyHash("SRC|1", 0));
		ChildKeysSweepTest.check("another seed gives another hash", ChildKeysSweep.keyHash("SRC|1", 0) != ChildKeysSweep.keyHash("SRC|1", 1));
		final Set<Long> seen = new HashSet<Long>();
		final int[] perPartition = new int[8];
		for (int i = 0; i < 100000; i++)
		{
			final long hash = ChildKeysSweep.keyHash("SRC|" + i, 0);
			seen.add(Long.valueOf(hash));
			perPartition[ChildKeysSweep.partitionOfHash(hash, 8)]++;
		}
		ChildKeysSweepTest.check("100,000 successive keys, no collision", seen.size() == 100000);
		for (final int count : perPartition)
		{
			ChildKeysSweepTest.check("8 partitions of 100,000 keys are even (" + count + ")", count > 11500 && count < 13500);
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
