package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.index.DigestMap;
import com.on.ebx.factory.index.KeyDigest;
import com.on.ebx.factory.master.ScanBudget;
import com.on.ebx.factory.staging.StagingLoadListener;
import com.on.ebx.factory.transfer.StagingRowDigest;
import com.on.ebx.factory.transfer.StagingRowDiff;
import com.on.ebx.factory.transfer.TransferPlan;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;

/**
 * Staging-load lot V3-S2 (2026-10-04, docs/VOLUME.md "Lot V3-S2"): what {@code ebx.StagingLoader} reads about a staging table BEFORE it plans the first row of
 * a scope {@code all} load, so that classifying a landing row as "unchanged" needs no random read at all.
 * <p>
 * <b>The problem.</b> Lot V3-S made a full reload write only the rows whose result differs: 0 written / 1,465,857 unchanged on Vol1m in 94.5-124 s on a quiet
 * machine, but 169 s and 315 s on a loaded one, while the master load - which since lot V3-M2 reads each table once - stayed at 32-44 s under the same load.
 * What was left in the staging loader is a RANDOM read per landing row: the existing staging row (to compare), for a child feed the parent row (is the parent
 * there), and for every empty child the row at its key (142,003 on Vol1m) - each ending in block reads and LZ4 decompression.
 * <p>
 * <b>The index</b> (SCAN mode; one per staging TABLE, shared by every feed that writes it, built by ONE streaming request in table order, before the first
 * row of the first feed that needs it): {@code stagingKey -> digest}. The key is {@code PrimaryKey.format()}; the digest is a {@code long[]} holding, for
 * each LAYOUT of the entity, the 128-bit {@link StagingRowDigest} of exactly what {@code StagingRowDiff} compares (every column the feeds plan, {@code
 * _sourceHash} when the table has it, "is STAGED"). The three questions the loader used to put to the table are answered from it:
 * <ol>
 * <li><b>Is this row unchanged?</b> The planned digest of the row, under the layout of its feed, equals the stored one: unchanged, NO read. A different or
 * unknown digest sends the row down the lot V3-S path (read the row by key once, compare with {@code StagingRowDiff}, write when it differs); a key the
 * index does not hold is a new row, nothing to read or compare.</li>
 * <li><b>Does the empty child already exist?</b> (stale-row count) The key is in the index of the child's table.</li>
 * <li><b>Is the parent row there?</b> (foreign-key check of a child feed) The key is in the index of the parent entity, kept while the entities that point to
 * it are processed.</li>
 * </ol>
 * <b>Layouts.</b> Several feeds can write one table, each planning its own columns, and which feed will plan a stored row is not known when the table is
 * scanned: the scan computes one digest per DISTINCT layout of the entity (the sorted column set a feed plans, {@link #addLayout}); nearly always one, because
 * every feed plans every accepted attribute (supplied or forced to null). More than {@link #MAX_LAYOUTS} layouts: no digest is kept (the key set still
 * serves the two presence checks and every row takes the read path, {@code digestsDisabled} in the detail). A row is compared only when the columns of its
 * planned map are exactly its layout's ({@link StagingRowDigest#sameColumns}); otherwise it takes the read path and {@code layoutMismatches} counts it.
 * <p>
 * <b>Memory</b> (JDK 17+ compressed pointers): the key string ({@code "SAP|123456#MAILING"}) about 48-56 bytes, the {@code HashMap} node 32, the table slot
 * about 11, the digest {@code long[2]} 32 (16 per extra layout): about 125-135 bytes a row - 889,165 addresses about 115 MB, 576,692 persons about 75 MB. A
 * table's index is built when the first root feed whose tree touches it starts, and dropped after the last root feed that reads it as its own table or as a
 * foreign-key target ({@link #releaseAfter}); the whole set is dropped when the entity loop ends. At the bound {@link ScanBudget#DEFAULT_MAX_INDEXED_ROWS} one
 * table weighs about 0.7 GB, against the 8 GB of the FastTrack server. Above it ({@code mdm.factory.stagingLoad.maxIndexedRows}, 0 disables), or on any failure
 * of the scan, the table keeps the lot V3-S per-row path ({@code LOOKUP} mode) and says why.
 * <p>
 * <b>Staleness.</b> The index is built before the table's first write of the load and read afterwards. A row WRITTEN by this load has its entry replaced by
 * "unknown" ({@link Entity#recordWritten}, called by {@code flushOne} for the rows that were really committed): a later landing row with the same key (two
 * landing rows computing one staging key, in two batches) is then read and compared with what the first one wrote, exactly as before this lot, and a freshly
 * inserted key is present for the parent and empty-child checks of the batches that follow. A row planned twice in ONE batch is still written, never compared
 * (lot V3-S's {@code plannedKeys} rule, applied before the index is consulted). Pass 2 (deferred references) patches columns outside every layout and runs
 * after the index is dropped.
 * <p>
 * <b>Lot IDX (2026-10-12, docs/VOLUME.md "Lot IDX - compact indexes"): the index is a flat table, not a {@code HashMap<String, long[]>}.</b> The memory figures above (125-135
 * bytes a row) are those of the map; the index is now an {@code index.DigestMap}: the key's 128-bit digest and the 2 x layouts words of its digests side by side in one
 * {@code long[]} (32 bytes a row for the usual single layout, +16 per extra one, divided by the load factor), no key string, no node, no array per row. "No digest known for
 * this row" is all-zero words (a digest that happens to be zero is treated alike: a read, never a lost update). Two keys of one scan sharing a digest make the index
 * unusable ({@code DigestMap#duplicates}): that table runs in {@code LOOKUP} mode. The presence checks (empty child, parent row) ask the digest of the key.
 */
final class StagingLoadReads
{
	/** More distinct layouts than this: no digest is kept for the entity. */
	static final int MAX_LAYOUTS = 4;
	/** Rows between two looks at the stop request while an index is being built. */
	private static final int TICK_EVERY = 20000;

	private final long max;
	private final Map<String, Entity> entities = new LinkedHashMap<String, Entity>();
	/** Sequence number of the last root feed whose tree needs the entity (as its own table or as a foreign-key target). */
	private final Map<String, Integer> lastUse = new HashMap<String, Integer>();
	private final Map<TransferPlan.Feed, Integer> sequenceByRootFeed = new IdentityHashMap<TransferPlan.Feed, Integer>();
	private final Map<TransferPlan.Feed, Set<String>> needsByRootFeed = new IdentityHashMap<TransferPlan.Feed, Set<String>>();

	/** @param pMax the bound on the rows of a table to index, see {@link ScanBudget#currentStaging} */
	StagingLoadReads(final long pMax)
	{
		this.max = pMax;
	}

	// ============================================================================================== registration (before any row is read)

	/** The entity's statistics and index; created on first mention. */
	Entity entity(final String pName)
	{
		Entity entity = this.entities.get(pName);
		if (entity == null)
		{
			entity = new Entity(pName);
			this.entities.put(pName, entity);
		}
		return entity;
	}

	/**
	 * Registers a layout (the columns a feed plans) of an entity and answers its position; a layout already known gives the same position, so the throwaway
	 * registration pass and the real feed preparation agree.
	 */
	int addLayout(final String pEntityName, final Collection<String> pColumns)
	{
		final Entity entity = this.entity(pEntityName);
		final String[] columns = new TreeSet<String>(pColumns).toArray(new String[0]);
		final String key = String.join("\u0000", columns);
		final Integer known = entity.layoutByKey.get(key);
		if (known != null)
		{
			return known.intValue();
		}
		entity.layouts.add(columns);
		entity.layoutByKey.put(key, Integer.valueOf(entity.layouts.size() - 1));
		return entity.layouts.size() - 1;
	}

	/** Declares that the tree of {@code pRootFeed} (the {@code pSequence}-th root feed of the load) reads these entities' tables. */
	void need(final TransferPlan.Feed pRootFeed, final int pSequence, final Set<String> pEntities)
	{
		this.sequenceByRootFeed.put(pRootFeed, Integer.valueOf(pSequence));
		this.needsByRootFeed.put(pRootFeed, new LinkedHashSet<String>(pEntities));
		for (final String name : pEntities)
		{
			final Integer last = this.lastUse.get(name);
			if (last == null || last.intValue() < pSequence)
			{
				this.lastUse.put(name, Integer.valueOf(pSequence));
			}
		}
	}

	// ============================================================================================== lifecycle

	/**
	 * Builds the index of every table the tree of {@code pRootFeed} needs and that is not built yet - before the feed reads its first landing row. Never
	 * throws for a scan problem (that table falls back to the lookups and says why).
	 *
	 * @return false when a stop request interrupted a build: the caller stops the load
	 */
	boolean prepare(final TransferPlan.Feed pRootFeed, final Map<String, AdaptationTable> pStagingTables, final StagingLoadListener pListener, final FactoryJobs.Handle pHandle)
	{
		final Set<String> needs = this.needsByRootFeed.get(pRootFeed);
		if (needs == null)
		{
			return true;
		}
		for (final String name : needs)
		{
			final AdaptationTable table = pStagingTables.get(name);
			if (table != null && !this.ensure(name, table, pListener, pHandle))
			{
				return false;
			}
		}
		return true;
	}

	private boolean ensure(final String pName, final AdaptationTable pTable, final StagingLoadListener pListener, final FactoryJobs.Handle pHandle)
	{
		final Entity entity = this.entity(pName);
		if (entity.built)
		{
			return true;
		}
		entity.built = true;
		final long rows = this.max <= 0L ? 0L : StagingLoadReads.size(pTable);
		final String refusal = ScanBudget.refusal(ScanBudget.STAGING_PROPERTY, this.max, rows);
		if (refusal != null)
		{
			entity.lookupReason = refusal;
			return true;
		}
		try
		{
			pListener.note(pName + ": reading the staging index (one scan).");
			final long start = System.currentTimeMillis();
			final boolean finished = entity.scan(pTable, rows, pHandle);
			entity.indexMs = System.currentTimeMillis() - start;
			if (!finished)
			{
				entity.drop();
				entity.lookupReason = "stopped while the index was being built";
				return false;
			}
			if (entity.entries.duplicates() > 0L)
			{
				// two staging keys with one 128-bit digest (or a key twice): this index cannot tell them apart, the table keeps the per-row path of lot V3-S
				final long duplicates = entity.entries.duplicates();
				LoggingCategory.getKernel().warn("[mdm-factory] staging load of " + pName + ": " + duplicates
					+ " key(s) share a 128-bit digest with another one, using per-row lookups instead");
				entity.drop();
				entity.lookupReason = duplicates + " key(s) share a 128-bit digest with another one";
				return true;
			}
			entity.scanMode = true;
			entity.indexRows = entity.entries.size();
			entity.indexBytes = entity.entries.bytes();
			return true;
		}
		catch (final RuntimeException ex)
		{
			// a failed scan costs speed, never the load: the table keeps the per-row path of lot V3-S
			LoggingCategory.getKernel().warn("[mdm-factory] staging load of " + pName + ": the read index could not be built, using per-row lookups instead: " + ex);
			entity.drop();
			entity.lookupReason = "index build failed: " + ex;
			return true;
		}
	}

	/** The entity's index when it is in use (SCAN mode, built, not released yet), otherwise null: the caller then reads the table as lot V3-S did. */
	Entity scanning(final String pName)
	{
		final Entity entity = this.entities.get(pName);
		return entity != null && entity.scanMode && entity.entries != null ? entity : null;
	}

	/** The root feed {@code pRootFeed} is done: every index nobody reads later (as own table or foreign-key target) is dropped. */
	void releaseAfter(final TransferPlan.Feed pRootFeed)
	{
		final Integer sequence = this.sequenceByRootFeed.get(pRootFeed);
		if (sequence == null)
		{
			return;
		}
		for (final Map.Entry<String, Integer> use : this.lastUse.entrySet())
		{
			if (use.getValue().intValue() <= sequence.intValue())
			{
				final Entity entity = this.entities.get(use.getKey());
				if (entity != null)
				{
					entity.release();
				}
			}
		}
	}

	/** Every index goes (the entity loop is over, or the load ended). */
	void releaseAll()
	{
		for (final Entity entity : this.entities.values())
		{
			entity.release();
		}
	}

	/** {@code readMode}, the index size and time, and what the load did with it, added to the entity's step detail. */
	void describe(final String pEntityName, final ObjectNode pDetail)
	{
		this.entity(pEntityName).describe(pDetail);
	}

	private static long size(final AdaptationTable pTable)
	{
		final RequestResult rows = pTable.createRequestResult(null);
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}

	/** Initial capacity for {@code pRows} entries with no rehash (HashMap's load factor is 0.75). */
	private static int capacity(final long pRows)
	{
		final long wanted = pRows * 4L / 3L + 16L;
		return (int) Math.min(wanted, 1L << 29);
	}

	// ============================================================================================== one table

	/** One staging table: its layouts, its index while it lives, and the counters of what the load did with it (kept after the index is dropped). */
	static final class Entity
	{
		final String name;
		final List<String[]> layouts = new ArrayList<String[]>();
		final Map<String, Integer> layoutByKey = new HashMap<String, Integer>();

		boolean built;
		/** True once the index was built successfully: the mode of the entity, kept after release for the detail. */
		boolean scanMode;
		String lookupReason = "not reached by this load";
		/**
		 * Lot IDX: {@code digest of the staging key -> the digests of every layout}, 2 words per layout side by side (all zeros = unknown); width 0 when no digest is kept. Null in
		 * LOOKUP mode, before the build and after release.
		 */
		private DigestMap entries;
		private Map<String, StagingLoader.StagingColumn> columns;
		private boolean compareSourceHash;
		private boolean digests;
		/** The columns of this table as {@link StagingRowDigest} reads them: one object per index, not one per row. */
		private final StagingRowDigest.Columns resolver = new StagingRowDigest.Columns()
		{
			@Override
			public StagingRowDiff.Field field(final String pName)
			{
				final Map<String, StagingLoader.StagingColumn> table = Entity.this.columns;
				return table == null ? null : table.get(pName);
			}
		};

		// statistics for the step detail
		long indexRows;
		long indexMs;
		long unchangedByIndex;
		long newByIndex;
		long stagingRowReads;
		long layoutMismatches;
		long parentChecksByIndex;
		long parentChecksByLookup;
		long emptyChildChecksByIndex;
		long emptyChildChecksByLookup;
		long writtenKeys;
		/** Lot IDX: estimated heap of the index at its largest (the flat table), reported as {@code indexBytes}. */
		long indexBytes;

		Entity(final String pName)
		{
			this.name = pName;
		}

		/**
		 * The digests of the stored row at this key, 2 words per layout; {@link StagingRowDigest#UNKNOWN} (length 0) when the row exists but no digest is kept for it (the
		 * caller reads it); null when the key is not in the table (as the index knew it).
		 */
		long[] entry(final String pKey)
		{
			if (this.entries == null)
			{
				return null;
			}
			final int slot = this.entries.find(KeyDigest.of(pKey));
			if (slot < 0)
			{
				return null;
			}
			final int width = this.entries.width();
			if (width == 0 || this.entries.allZero(slot))
			{
				return StagingRowDigest.UNKNOWN;
			}
			final long[] words = new long[width];
			for (int i = 0; i < width; i++)
			{
				words[i] = this.entries.get(slot, i);
			}
			return words;
		}

		boolean contains(final String pKey)
		{
			return this.entries != null && this.entries.find(KeyDigest.of(pKey)) >= 0;
		}

		/**
		 * Whether a row planned with these values, under the layout of its feed, is unchanged against the stored entry: true only when every compared value
		 * is shown equal. False for an unknown entry, an unknown planned value, a planned map that is not the layout's columns (counted) - the caller reads
		 * the row and compares with {@code StagingRowDiff}.
		 */
		boolean sameAsPlanned(final long[] pStoredEntry, final int pLayoutIndex, final Map<String, Object> pPlannedFields, final String pPlannedSourceHash)
		{
			if (pStoredEntry == null || pStoredEntry.length == 0 || pLayoutIndex < 0 || pLayoutIndex >= this.layouts.size())
			{
				return false;
			}
			final String[] layout = this.layouts.get(pLayoutIndex);
			if (!StagingRowDigest.sameColumns(layout, pPlannedFields))
			{
				this.layoutMismatches++;
				return false;
			}
			final long[] planned = StagingRowDigest.planned(layout, pPlannedFields, pPlannedSourceHash, this.compareSourceHash, this.resolver);
			return StagingRowDigest.same(planned, pStoredEntry, pLayoutIndex);
		}

		/** A row of this table was really written (inserted or modified) by this load: its key is present, and what the index held for it is stale. */
		void recordWritten(final String pKey)
		{
			if (this.entries != null && pKey != null)
			{
				final int slot = this.entries.slotFor(KeyDigest.of(pKey));
				for (int i = 0; i < this.entries.width(); i++)
				{
					this.entries.set(slot, i, 0L); // unknown: a later landing row with this key is read and compared
				}
				this.writtenKeys++;
			}
		}

		/** For {@code StagingLoadReadsCompactTest}: an empty index of {@code pRows} rows holding {@code pDigestLayouts} layouts of digests, as {@link #scan} would leave it. */
		void installForTest(final int pDigestLayouts, final long pRows)
		{
			this.entries = new DigestMap(2 * pDigestLayouts, pRows);
			this.scanMode = true;
		}

		/** For {@code StagingLoadReadsCompactTest}: a row as {@link #scan} stores it (the words of every layout, empty for none); false when the key was already there. */
		boolean putForTest(final String pKey, final long... pWords)
		{
			final int slot = this.entries.addUnique(KeyDigest.of(pKey));
			if (slot < 0)
			{
				return false;
			}
			for (int i = 0; i < pWords.length; i++)
			{
				this.entries.set(slot, i, pWords[i]);
			}
			return true;
		}

		void release()
		{
			if (this.entries != null)
			{
				this.indexBytes = Math.max(this.indexBytes, this.entries.bytes()); // the table may have grown with the keys this load inserted
			}
			this.entries = null;
			this.columns = null;
		}

		private void drop()
		{
			this.entries = null;
			this.columns = null;
			this.scanMode = false;
		}

		/** @return false when a stop request interrupted the scan */
		private boolean scan(final AdaptationTable pTable, final long pRows, final FactoryJobs.Handle pHandle)
		{
			// the columns of every layout, resolved once; _sourceHash only when the live table carries it (as StagingRowDiff's caller does)
			this.compareSourceHash = pTable.getTableOccurrenceRootNode().getNode(StagingLoader.STAGING_SOURCE_HASH) != null;
			this.columns = new HashMap<String, StagingLoader.StagingColumn>();
			for (final String[] layout : this.layouts)
			{
				for (final String column : layout)
				{
					if (!this.columns.containsKey(column))
					{
						final Path path = Path.parse("./" + column);
						this.columns.put(column, new StagingLoader.StagingColumn(path, pTable.getTableOccurrenceRootNode().getNode(path)));
					}
				}
			}
			this.digests = !this.layouts.isEmpty() && this.layouts.size() <= StagingLoadReads.MAX_LAYOUTS;
			this.entries = new DigestMap(this.digests ? 2 * this.layouts.size() : 0, pRows);
			final StoredRecord record = new StoredRecord(this.columns);
			final RequestResult rows = pTable.createRequestResult(null);
			try
			{
				long count = 0;
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					final int slot = this.entries.addUnique(KeyDigest.of(row.getOccurrencePrimaryKey().format()));
					if (slot >= 0 && this.digests)
					{
						final long[] entry = this.entryOf(record.at(row));
						for (int i = 0; i < entry.length; i++) // UNKNOWN (length 0) leaves the zeros: no digest known
						{
							this.entries.set(slot, i, entry[i]);
						}
					}
					if (++count % StagingLoadReads.TICK_EVERY == 0 && pHandle != null && pHandle.cancelled())
					{
						return false;
					}
				}
				return true;
			}
			finally
			{
				rows.close();
			}
		}

		/** One digest per layout, side by side; the shared "unknown" entry as soon as one cannot be computed (the row is then read and compared). */
		private long[] entryOf(final StoredRecord pRecord)
		{
			final long[] entry = new long[2 * this.layouts.size()];
			for (int i = 0; i < this.layouts.size(); i++)
			{
				final long[] words = StagingRowDigest.stored(this.layouts.get(i), pRecord, this.compareSourceHash);
				if (words.length != 2)
				{
					return StagingRowDigest.UNKNOWN;
				}
				entry[2 * i] = words[0];
				entry[2 * i + 1] = words[1];
			}
			return entry;
		}

		void describe(final ObjectNode pDetail)
		{
			pDetail.put("readMode", this.scanMode ? "SCAN" : "LOOKUP");
			if (!this.scanMode)
			{
				pDetail.put("lookupReason", this.lookupReason);
			}
			else
			{
				pDetail.put("stagingIndexRows", this.indexRows);
				pDetail.put("stagingIndexMs", this.indexMs);
				pDetail.put("indexBytes", this.indexBytes); // lot IDX
				pDetail.put("layouts", this.layouts.size());
				if (this.layouts.size() > StagingLoadReads.MAX_LAYOUTS || this.layouts.isEmpty())
				{
					pDetail.put("digestsDisabled", this.layouts.size() + " column layouts (at most " + StagingLoadReads.MAX_LAYOUTS + ")");
				}
				pDetail.put("newByIndex", this.newByIndex);
				pDetail.put("writtenKeysRecorded", this.writtenKeys);
				pDetail.put("layoutMismatches", this.layoutMismatches);
			}
			pDetail.put("unchangedByIndex", this.unchangedByIndex);
			pDetail.put("stagingRowReads", this.stagingRowReads);
			pDetail.put("parentChecksByIndex", this.parentChecksByIndex);
			pDetail.put("parentChecksByLookup", this.parentChecksByLookup);
			pDetail.put("emptyChildChecksByIndex", this.emptyChildChecksByIndex);
			pDetail.put("emptyChildChecksByLookup", this.emptyChildChecksByLookup);
		}
	}

	/** A stored staging row seen through {@link StagingRowDiff.Stored}: one mutable instance, pointed at each record of the scan. */
	private static final class StoredRecord implements StagingRowDiff.Stored
	{
		private final Map<String, StagingLoader.StagingColumn> columns;
		private Adaptation row;

		StoredRecord(final Map<String, StagingLoader.StagingColumn> pColumns)
		{
			this.columns = pColumns;
		}

		StoredRecord at(final Adaptation pRow)
		{
			this.row = pRow;
			return this;
		}

		@Override
		public StagingRowDiff.Field field(final String pName)
		{
			return this.columns.get(pName);
		}

		@Override
		public Object value(final String pName)
		{
			final StagingLoader.StagingColumn column = this.columns.get(pName);
			if (column == null)
			{
				throw new IllegalStateException("no column " + pName);
			}
			return this.row.get(column.path);
		}

		@Override
		public String status()
		{
			return this.row.getString(StagingLoader.STAGING_STATUS);
		}

		@Override
		public Object sourceHash()
		{
			return this.row.get(StagingLoader.STAGING_SOURCE_HASH);
		}
	}
}
