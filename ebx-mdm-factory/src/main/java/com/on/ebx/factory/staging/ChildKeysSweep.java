package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import com.on.ebx.factory.quality.ChildKeysDerivation;

/**
 * The pure half of the {@code CHILD_KEYS} post-pass of {@code ebx.StagingLoader} (lot M3, 2026-10-03, {@link ChildKeysDerivation}
 * has the why; rewritten for speed by lot M10, 2026-10-04): given a sequential scan of the child table and a sequential scan of the
 * parent table, computes every parent's value and writes the ones that differ, in batches, at bounded memory. No EBX class: the two
 * tables are seen through {@link Children} and {@link Parents}, so the grouping, the batching and the "removed child" cases are
 * tested against an in-memory fake.
 * <p>
 * <b>Why a full sweep, never "only the children this run touched".</b> The child table is indexed on its primary key only
 * (CLAUDE.md &sect;5, {@code <index>} is ignored on 6.3), so the children of one parent cannot be fetched without scanning the table;
 * a parent's value is a function of ALL its remaining children, so a child added, changed, moved to another parent or removed means
 * recomputing from every other child of that parent (and of the old one) - which needs the scan anyway. The caller decides WHEN a
 * sweep is worth it (a run that changed nothing in either entity skips it); this class decides how to do one cheaply.
 * <p>
 * <b>Two scans, no lookup (lot M10).</b> Measured on project Vol1m (2026-10-04) the sweep took 728 s, then 574 s for a recompute that
 * rewrote every parent, for 576,692 parents and 889,165 child rows. The structure that cost it - read from the code, no instance was
 * available - was one child scan per partition (3), the key of EVERY child row folded in every one of them, a primary-key lookup of
 * each parent to read its value and another one to write it, a predicate scan of the parents per partition to find the ones to clear,
 * and one transaction of 500 writes at a time. The new shape:
 * <ol>
 * <li><b>One scan of the child table.</b> Each child row becomes ONE pair of {@code long}s: a 64-bit hash of its parent's key and the
 * code of its own key ({@link ChildKeysDerivation.KeyEncoder}: with the default {@code hash} encoding the 61 bits of the opaque token).
 * Each distinct raw value of a part is folded once (bounded cache). The pairs sit in two parallel {@code long[]} (16 bytes per child
 * row, 14 MB for Vol1m), are sorted in place by (parent hash, code) and de-duplicated - so the distinct keys of a parent are a
 * contiguous run, found by binary search. No map, no per-parent object.</li>
 * <li><b>One scan of the parent table</b>, in table order. For each parent: look its run up by the hash of its key, build the value
 * (the same {@link ChildKeysDerivation#value} as ever), compare with the stored value that is already in hand, and queue a write ONLY
 * when they differ. A parent with no run and a stored value is cleared in this same pass: there is no separate "parents that hold a
 * value" scan. A sweep over unchanged data therefore costs the two scans and writes nothing.</li>
 * <li><b>Writes</b> go out in batches ({@code batchSize}), by the record the scan handed over ({@link Update#handle}), never by a
 * second lookup of the key. They happen while the parent scan is still open: an EBX request result is frozen when it is executed
 * (&laquo;Query snapshot isolation&raquo;), which the platform documents as the way to modify a table while browsing it.</li>
 * </ol>
 * <b>The parent key is hashed, never stored.</b> 576,692 parent keys as strings would cost ~75 MB in a map; the hash costs 8 bytes
 * inside the pair. Two different parents sharing a 64-bit hash would silently share their children, so the parent pass checks that
 * each run is claimed by ONE parent: a second claim is a collision (probability ~ n&sup2;/2<sup>65</sup>, 9e-9 for Vol1m), and the sweep
 * then starts again with another seed ({@link Result#hashRestarts}, at most {@link #MAX_HASH_RESTARTS}; the values already written are
 * recomputed, so the restart also repairs them). A child that refers to a parent that does not exist and whose key collides with a
 * real parent's cannot be detected: it needs a dangling reference and the same 2<sup>-64</sup> chance, per pair.
 * <p>
 * <b>Bounded memory</b> ({@link #partitionCount}): the pairs of the whole child table are held when they fit
 * {@code maxPairsInMemory} (the caller's budget, 8,000,000 = 128 MB for the {@code hash} encoding); above it the parents are split
 * into {@code K} hash partitions and the whole sequence is repeated per partition, keeping only that partition's pairs, so the cost
 * becomes {@code K} scans of each table instead of one - and a child key is folded in the partition of its parent only, never twice.
 * A hash split is even, not exact; a single parent holding most of the children cannot be split (and is capped to the value's
 * length anyway). Besides the pairs: a bit per pair (the runs a parent claimed), the fold caches (at most
 * {@link ChildKeysDerivation.KeyEncoder#CACHE_ENTRIES} per part) and one write batch of records.
 * <p>
 * {@code cancelled} is read after each batch and every {@value #CANCEL_CHECK_EVERY} parents.
 */
public final class ChildKeysSweep
{
	/** The most partitions a sweep will use, whatever the table size: past it the bound is exceeded rather than the run made unbounded in time. */
	public static final int MAX_PARTITIONS = 64;

	/** Times the sweep starts again with another hash seed after a parent-key collision, before giving up. */
	public static final int MAX_HASH_RESTARTS = 3;

	/** How often the parent pass looks at the cancellation flag, in parents. */
	public static final int CANCEL_CHECK_EVERY = 50000;

	private static final int INITIAL_CAPACITY = 1024;

	private ChildKeysSweep()
	{
	}

	/** Receives the children of one scan, in table order. */
	public interface ChildVisitor
	{
		/**
		 * @param pParentKey the formatted staging key of the parent the child refers to, or {@code null} when the child refers to none
		 * @param pRawParts the child's values for the definition's fields, in order ({@code null} = empty); the list is REUSED by the caller after this returns
		 */
		void child(String pParentKey, List<String> pRawParts);
	}

	/** The child table seen as a sequential scan. */
	public interface Children
	{
		/** Visits every row of the child table once. Called once per partition (once in the usual case). */
		void scan(ChildVisitor pVisitor) throws Exception;
	}

	/** Receives the parents of one scan, in table order. */
	public interface ParentVisitor
	{
		/**
		 * @param pKey the formatted key of the parent
		 * @param pStoredValue the value the parent holds ({@code null} when empty)
		 * @param pHandle what {@link Parents#write} needs to modify this very row (the record itself); opaque here
		 * @return {@code false} to stop the scan
		 */
		boolean parent(String pKey, String pStoredValue, Object pHandle) throws Exception;
	}

	/** One row to modify. */
	public static final class Update
	{
		/** What the parent scan gave for this row. */
		public final Object handle;
		/** The parent's formatted key (for messages and tests; the write goes through {@link #handle}). */
		public final String key;
		/** The new value, {@code null} to clear. */
		public final String value;

		Update(final Object pHandle, final String pKey, final String pValue)
		{
			this.handle = pHandle;
			this.key = pKey;
			this.value = pValue;
		}
	}

	/** The parent table: one streaming scan of every row, and a batched write by the rows' own handles. */
	public interface Parents
	{
		/**
		 * Visits every parent once, in table order. The scan stays open while {@link #write} is called from inside the visitor (the result is frozen when the scan
		 * starts), and is closed before this returns, also when the visitor throws.
		 */
		void scan(ParentVisitor pVisitor) throws Exception;

		/** Writes the batch in ONE transaction; each update modifies the row its {@link Update#handle} stands for. Must throw when the write fails. */
		void write(List<Update> pBatch) throws Exception;
	}

	/** Polled between batches. */
	public interface Cancellation
	{
		boolean cancelled();
	}

	/** What one sweep did. */
	public static final class Result
	{
		/** Hash partitions used: 1 unless the child table exceeds the memory budget. */
		public int partitions;
		/** Scans started, both tables together: {@code 2 * partitions} when nothing restarted. */
		public int scans;
		/** Child rows read (counted once, not once per partition). */
		public long childrenRead;
		/** Child rows that refer to no parent. */
		public long childrenWithoutParent;
		/** Child rows with a required part empty: they contribute no key. */
		public long childrenWithoutKey;
		/** Distinct child keys whose parent does not exist in the parent table (a dangling reference), counted per parent. */
		public long childrenOfMissingParent;
		/** Parents read by the parent scan(s). */
		public long parentsRead;
		/** Parents that have at least one key after the cap. */
		public long parentsWithKeys;
		/** Parents whose value was written (empty or different before, a value now). */
		public long parentsUpdated;
		/** Parents that held a value and now have none (their children were removed or moved). */
		public long parentsCleared;
		/** Parents that have a value, equal to the one they hold. */
		public long parentsUnchanged;
		/** Parents read whose computed value equals the stored one, with or without a value: the writes that were NOT done. */
		public long writesSkippedUnchanged;
		/** Distinct keys the cap dropped, over all parents. */
		public long tokensDropped;
		/** Parents that lost at least one key to the cap. */
		public long parentsCapped;
		/** Transactions of writes. */
		public long writeBatches;
		/** Distinct (parent, key) pairs held at the same time, at most: the memory unit (16 bytes each). */
		public long peakPairs;
		/** Starts again after a parent-key hash collision (almost always 0). */
		public int hashRestarts;
		/** Parts folded, and parts served by the cache. */
		public long partFolds;
		public long partCacheHits;
		/** Milliseconds: the child scan (read and encode), the sort of the pairs, the parent scan WITHOUT its writes, the writes. */
		public long childScanMs;
		public long sortMs;
		public long parentPassMs;
		public long writeMs;
		public boolean cancelled;

		/** One line for the step message. */
		public String summary()
		{
			return this.childrenRead + " child row(s) read, " + this.parentsRead + " parent(s) read in " + this.scans + " scan(s): " + this.parentsWithKeys
				+ " with keys, " + this.parentsUpdated + " updated, " + this.parentsCleared + " cleared, " + this.writesSkippedUnchanged + " left as they were; "
				+ this.tokensDropped + " key(s) dropped by the cap on " + this.parentsCapped + " parent(s); " + this.childrenWithoutParent
				+ " child row(s) without parent, " + this.childrenWithoutKey + " without key, " + this.childrenOfMissingParent + " of a missing parent; child scan "
				+ this.childScanMs + " ms, sort " + this.sortMs + " ms, parents " + this.parentPassMs + " ms, writes " + this.writeMs + " ms in " + this.writeBatches
				+ " batch(es)." + (this.cancelled ? " Stopped before the end." : "");
		}
	}

	/** {@code ceil(pChildRows / pMaxPairsInMemory)}, at least 1, at most {@link #MAX_PARTITIONS}. */
	public static int partitionCount(final long pChildRows, final int pMaxPairsInMemory)
	{
		final long bound = Math.max(1, pMaxPairsInMemory);
		final long partitions = (Math.max(0, pChildRows) + bound - 1) / bound;
		return (int) Math.max(1, Math.min(MAX_PARTITIONS, partitions));
	}

	/**
	 * A 64-bit hash of a parent key: FNV-1a over its characters, started from a seed, then the finalisation of MurmurHash3 (every input bit reaches every output bit).
	 * Specified by this code only, so it is stable across runs and JVMs.
	 */
	public static long keyHash(final String pKey, final int pSeed)
	{
		long h = 0xcbf29ce484222325L ^ (pSeed * 0x9e3779b97f4a7c15L);
		for (int i = 0; i < pKey.length(); i++)
		{
			h ^= pKey.charAt(i);
			h *= 0x100000001b3L;
		}
		h ^= h >>> 33;
		h *= 0xff51afd7ed558ccdL;
		h ^= h >>> 33;
		h *= 0xc4ceb9fe1a85ec53L;
		h ^= h >>> 33;
		return h;
	}

	/** The partition of a key hash. */
	public static int partitionOfHash(final long pHash, final int pPartitions)
	{
		return pPartitions <= 1 ? 0 : (int) ((pHash >>> 1) % pPartitions);
	}

	/** The partition of a parent key (for the caller's reports and the tests). */
	public static int partitionOf(final String pParentKey, final int pPartitions)
	{
		return ChildKeysSweep.partitionOfHash(ChildKeysSweep.keyHash(pParentKey, 0), pPartitions);
	}

	/**
	 * Runs the sweep with arrays that start small (tests); see {@link #run(Children, Parents, ChildKeysDerivation.Definition, int, int, long, int, Cancellation)}.
	 */
	public static Result run(final Children pChildren, final Parents pParents, final ChildKeysDerivation.Definition pDefinition, final int pMaxLength,
		final int pPartitions, final int pBatchSize, final Cancellation pCancellation) throws Exception
	{
		return ChildKeysSweep.run(pChildren, pParents, pDefinition, pMaxLength, pPartitions, 0, pBatchSize, pCancellation);
	}

	/**
	 * Runs the sweep.
	 *
	 * @param pMaxLength the text length cap of the attribute ({@link ChildKeysDerivation#value})
	 * @param pPartitions {@link #partitionCount} of the child table's size
	 * @param pChildRowsHint the number of child rows, to allocate the pair arrays at the right size at once (0 = unknown: they grow by half)
	 * @param pBatchSize writes per transaction (at least 1)
	 */
	public static Result run(final Children pChildren, final Parents pParents, final ChildKeysDerivation.Definition pDefinition, final int pMaxLength,
		final int pPartitions, final long pChildRowsHint, final int pBatchSize, final Cancellation pCancellation) throws Exception
	{
		return ChildKeysSweep.run(pChildren, pParents, pDefinition, pMaxLength, pPartitions, pChildRowsHint, pBatchSize, pCancellation, ChildKeysSweep.DEFAULT_HASHER);
	}

	static Result run(final Children pChildren, final Parents pParents, final ChildKeysDerivation.Definition pDefinition, final int pMaxLength,
		final int pPartitions, final long pChildRowsHint, final int pBatchSize, final Cancellation pCancellation, final Hasher pHasher) throws Exception
	{
		for (int seed = 0;; seed++)
		{
			final Result result = new Result();
			result.hashRestarts = seed;
			try
			{
				ChildKeysSweep.runWithSeed(pChildren, pParents, pDefinition, pMaxLength, Math.max(1, pPartitions), pChildRowsHint, Math.max(1, pBatchSize), pCancellation, seed,
					pHasher, result);
				return result;
			}
			catch (final HashCollision ex)
			{
				if (seed >= MAX_HASH_RESTARTS)
				{
					throw new IllegalStateException("two parents share a 64-bit key hash in " + (MAX_HASH_RESTARTS + 1) + " successive seeds: this cannot be chance");
				}
			}
		}
	}

	/** The hash of a parent key; the seam that lets a test force a collision. */
	interface Hasher
	{
		long hash(String pKey, int pSeed);
	}

	private static final Hasher DEFAULT_HASHER = new Hasher()
	{
		@Override
		public long hash(final String pKey, final int pSeed)
		{
			return ChildKeysSweep.keyHash(pKey, pSeed);
		}
	};

	/** Two different parents hashed to the same value: the run starts again with another seed. */
	private static final class HashCollision extends RuntimeException
	{
		private static final long serialVersionUID = 1L;

		HashCollision()
		{
			super("parent key hash collision", null, false, false);
		}
	}

	private static void runWithSeed(final Children pChildren, final Parents pParents, final ChildKeysDerivation.Definition pDefinition, final int pMaxLength,
		final int pPartitions, final long pChildRowsHint, final int pBatchSize, final Cancellation pCancellation, final int pSeed, final Hasher pHasher,
		final Result pResult) throws Exception
	{
		pResult.partitions = pPartitions;
		final ChildKeysDerivation.KeyEncoder encoder = new ChildKeysDerivation.KeyEncoder(pDefinition, ChildKeysDerivation.KeyEncoder.CACHE_ENTRIES);
		try
		{
			for (int partition = 0; partition < pPartitions; partition++)
			{
				if (ChildKeysSweep.runPartition(pChildren, pParents, pDefinition, pMaxLength, pPartitions, partition, pChildRowsHint, pBatchSize, pCancellation, pSeed, pHasher,
					encoder, pResult))
				{
					pResult.cancelled = true;
					return;
				}
			}
		}
		finally
		{
			pResult.partFolds = encoder.folds;
			pResult.partCacheHits = encoder.cacheHits;
		}
	}

	/** @return {@code true} when cancelled */
	private static boolean runPartition(final Children pChildren, final Parents pParents, final ChildKeysDerivation.Definition pDefinition, final int pMaxLength,
		final int pPartitions, final int pPartition, final long pChildRowsHint, final int pBatchSize, final Cancellation pCancellation, final int pSeed,
		final Hasher pHasher, final ChildKeysDerivation.KeyEncoder pEncoder, final Result pResult) throws Exception
	{
		final boolean firstPartition = pPartition == 0;
		final Pairs pairs = new Pairs(pChildRowsHint <= 0 ? INITIAL_CAPACITY : (int) Math.min(Integer.MAX_VALUE - 16, pChildRowsHint / pPartitions + pChildRowsHint / pPartitions / 16 + 16));
		pEncoder.reset();

		// 1. one scan of the child table: one (parent hash, key code) pair per child row of this partition
		long started = System.nanoTime();
		pResult.scans++;
		pChildren.scan(new ChildVisitor()
		{
			@Override
			public void child(final String pParentKey, final List<String> pRawParts)
			{
				if (firstPartition)
				{
					pResult.childrenRead++;
					if (pParentKey == null)
					{
						pResult.childrenWithoutParent++;
					}
				}
				if (pParentKey == null)
				{
					return;
				}
				final long hash = pHasher.hash(pParentKey, pSeed);
				if (ChildKeysSweep.partitionOfHash(hash, pPartitions) != pPartition)
				{
					return;
				}
				final long code = pEncoder.encode(pRawParts);
				if (code == ChildKeysDerivation.KeyEncoder.NO_KEY)
				{
					pResult.childrenWithoutKey++;
					return;
				}
				pairs.add(hash, code);
			}
		});
		pResult.childScanMs += (System.nanoTime() - started) / 1000000;

		// 2. sort by (parent hash, code) and drop the duplicates: the distinct keys of a parent are now one contiguous run
		started = System.nanoTime();
		pairs.sortAndDedupe();
		pResult.sortMs += (System.nanoTime() - started) / 1000000;
		pResult.peakPairs = Math.max(pResult.peakPairs, pairs.rawSize);

		// 3. one scan of the parent table: compute, compare with what is stored, write only the differences
		final BitSet claimed = new BitSet(pairs.size);
		final List<Update> batch = new ArrayList<Update>();
		final List<String> tokens = new ArrayList<String>();
		final boolean[] stopped = new boolean[1];
		final long[] writeNanos = new long[1];
		final long[] visited = new long[1];
		started = System.nanoTime();
		pResult.scans++;
		pParents.scan(new ParentVisitor()
		{
			@Override
			public boolean parent(final String pKey, final String pStoredValue, final Object pHandle) throws Exception
			{
				final long hash = pHasher.hash(pKey, pSeed);
				if (ChildKeysSweep.partitionOfHash(hash, pPartitions) != pPartition)
				{
					return true;
				}
				pResult.parentsRead++;
				String value = null;
				final int first = pairs.firstOf(hash);
				if (first >= 0)
				{
					if (claimed.get(first))
					{
						throw new HashCollision();
					}
					claimed.set(first);
					tokens.clear();
					for (int i = first; i < pairs.size() && pairs.hashes[i] == hash; i++)
					{
						tokens.add(pEncoder.render(pairs.codes[i]));
					}
					final ChildKeysDerivation.Value computed = ChildKeysDerivation.value(tokens, pMaxLength, pDefinition.maxTokens);
					if (computed.dropped > 0)
					{
						pResult.tokensDropped += computed.dropped;
						pResult.parentsCapped++;
					}
					value = computed.text;
				}
				if (value != null)
				{
					pResult.parentsWithKeys++;
				}
				if (ChildKeysSweep.same(pStoredValue, value))
				{
					pResult.writesSkippedUnchanged++;
					if (value != null)
					{
						pResult.parentsUnchanged++;
					}
				}
				else
				{
					batch.add(new Update(pHandle, pKey, value));
					if (value == null)
					{
						pResult.parentsCleared++;
					}
					else
					{
						pResult.parentsUpdated++;
					}
					if (batch.size() >= pBatchSize)
					{
						ChildKeysSweep.flush(pParents, batch, pResult, writeNanos);
						if (pCancellation != null && pCancellation.cancelled())
						{
							stopped[0] = true;
							return false;
						}
					}
				}
				if (++visited[0] % CANCEL_CHECK_EVERY == 0 && pCancellation != null && pCancellation.cancelled())
				{
					stopped[0] = true;
					return false;
				}
				return true;
			}
		});
		if (!stopped[0] && !batch.isEmpty())
		{
			ChildKeysSweep.flush(pParents, batch, pResult, writeNanos);
		}
		pResult.writeMs += writeNanos[0] / 1000000;
		pResult.parentPassMs += (System.nanoTime() - started - writeNanos[0]) / 1000000;
		if (stopped[0])
		{
			return true;
		}
		// the runs no parent claimed belong to parents that do not exist
		for (int from = 0; from < pairs.size();)
		{
			int to = from + 1;
			while (to < pairs.size() && pairs.hashes[to] == pairs.hashes[from])
			{
				to++;
			}
			if (!claimed.get(from))
			{
				pResult.childrenOfMissingParent += to - from;
			}
			from = to;
		}
		return false;
	}

	private static void flush(final Parents pParents, final List<Update> pBatch, final Result pResult, final long[] pWriteNanos) throws Exception
	{
		final long started = System.nanoTime();
		pParents.write(new ArrayList<Update>(pBatch));
		pWriteNanos[0] += System.nanoTime() - started;
		pResult.writeBatches++;
		pBatch.clear();
	}

	private static boolean same(final String pA, final String pB)
	{
		return pA == null ? pB == null : pA.equals(pB);
	}

	// =============================================================================================== the pairs

	/** Two parallel {@code long} arrays, {@code (hashes[i], codes[i])}: 16 bytes per child row. */
	static final class Pairs
	{
		long[] hashes;
		long[] codes;
		/** Pairs added. */
		int rawSize;
		/** Pairs after {@link #sortAndDedupe}. */
		private int size;

		Pairs(final int pCapacity)
		{
			this.hashes = new long[Math.max(16, pCapacity)];
			this.codes = new long[this.hashes.length];
		}

		void add(final long pHash, final long pCode)
		{
			if (this.rawSize == this.hashes.length)
			{
				final int grown = (int) Math.min(Integer.MAX_VALUE - 16L, this.hashes.length + (this.hashes.length >> 1) + 16L);
				this.hashes = Arrays.copyOf(this.hashes, grown);
				this.codes = Arrays.copyOf(this.codes, grown);
			}
			this.hashes[this.rawSize] = pHash;
			this.codes[this.rawSize] = pCode;
			this.rawSize++;
			this.size = this.rawSize;
		}

		int size()
		{
			return this.size;
		}

		void sortAndDedupe()
		{
			ChildKeysSweep.sortPairs(this.hashes, this.codes, 0, this.rawSize);
			int out = 0;
			for (int i = 0; i < this.rawSize; i++)
			{
				if (out > 0 && this.hashes[i] == this.hashes[out - 1] && this.codes[i] == this.codes[out - 1])
				{
					continue;
				}
				this.hashes[out] = this.hashes[i];
				this.codes[out] = this.codes[i];
				out++;
			}
			this.size = out;
		}

		/** @return the index of the first pair of this parent hash in the sorted pairs, or {@code -1} */
		int firstOf(final long pHash)
		{
			int low = 0;
			int high = this.size - 1;
			int found = -1;
			while (low <= high)
			{
				final int mid = (low + high) >>> 1;
				final long value = this.hashes[mid];
				if (value < pHash)
				{
					low = mid + 1;
				}
				else if (value > pHash)
				{
					high = mid - 1;
				}
				else
				{
					found = mid;
					high = mid - 1;
				}
			}
			return found;
		}
	}

	/**
	 * Sorts {@code [pFrom, pTo)} of two parallel arrays by (a, b), ascending (signed). In place; a three-way quicksort with a median-of-three pivot, recursing on the smaller
	 * side so the depth stays logarithmic, and an insertion sort below 16 entries: runs of equal pairs (one key repeated by thousands of children) cost one pass, not a
	 * quadratic one.
	 */
	static void sortPairs(final long[] pA, final long[] pB, final int pFrom, final int pTo)
	{
		int from = pFrom;
		int to = pTo;
		while (to - from > 16)
		{
			final int mid = (from + to) >>> 1;
			final int last = to - 1;
			final int pivotIndex = ChildKeysSweep.median(pA, pB, from, mid, last);
			final long pa = pA[pivotIndex];
			final long pb = pB[pivotIndex];
			int less = from;
			int i = from;
			int greater = last;
			while (i <= greater)
			{
				final int c = ChildKeysSweep.compare(pA[i], pB[i], pa, pb);
				if (c < 0)
				{
					ChildKeysSweep.swap(pA, pB, less++, i++);
				}
				else if (c > 0)
				{
					ChildKeysSweep.swap(pA, pB, i, greater--);
				}
				else
				{
					i++;
				}
			}
			// [from, less) < pivot, [less, greater] == pivot, (greater, to) > pivot
			if (less - from < to - (greater + 1))
			{
				ChildKeysSweep.sortPairs(pA, pB, from, less);
				from = greater + 1;
			}
			else
			{
				ChildKeysSweep.sortPairs(pA, pB, greater + 1, to);
				to = less;
			}
		}
		for (int i = from + 1; i < to; i++)
		{
			final long a = pA[i];
			final long b = pB[i];
			int j = i - 1;
			while (j >= from && ChildKeysSweep.compare(pA[j], pB[j], a, b) > 0)
			{
				pA[j + 1] = pA[j];
				pB[j + 1] = pB[j];
				j--;
			}
			pA[j + 1] = a;
			pB[j + 1] = b;
		}
	}

	private static int median(final long[] pA, final long[] pB, final int pX, final int pY, final int pZ)
	{
		final int xy = ChildKeysSweep.compare(pA[pX], pB[pX], pA[pY], pB[pY]);
		final int yz = ChildKeysSweep.compare(pA[pY], pB[pY], pA[pZ], pB[pZ]);
		final int xz = ChildKeysSweep.compare(pA[pX], pB[pX], pA[pZ], pB[pZ]);
		if (xy < 0)
		{
			return yz < 0 ? pY : xz < 0 ? pZ : pX;
		}
		return yz > 0 ? pY : xz > 0 ? pZ : pX;
	}

	private static int compare(final long pA1, final long pB1, final long pA2, final long pB2)
	{
		if (pA1 != pA2)
		{
			return pA1 < pA2 ? -1 : 1;
		}
		return pB1 == pB2 ? 0 : pB1 < pB2 ? -1 : 1;
	}

	private static void swap(final long[] pA, final long[] pB, final int pI, final int pJ)
	{
		final long a = pA[pI];
		pA[pI] = pA[pJ];
		pA[pJ] = a;
		final long b = pB[pI];
		pB[pI] = pB[pJ];
		pB[pJ] = b;
	}
}
