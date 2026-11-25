package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.index.DigestMap;
import com.on.ebx.factory.index.KeyDigest;
import com.on.ebx.factory.index.KeyValueIndex;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.MasterTransferPlan;
import com.on.ebx.factory.master.ScanBudget;
import com.on.ebx.factory.staging.StagingLoadListener;
import com.on.ebx.factory.transfer.RowDigest;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;

/**
 * Master-load lot V3-M2 (2026-10-04, docs/VOLUME.md "Lot V3-M2"): what {@code ebx.MasterLoader} reads about an entity BEFORE it plans its first row,
 * so that planning a staging row needs no random read at all.
 * <p>
 * <b>The problem.</b> After lot V3-M a reload with nothing changed wrote 0 row out of 1,465,857 and still took 199-263 s (project Vol1m): thread
 * dumps showed the time in THREE RANDOM READS PER STAGING ROW, each ending in block reads and LZ4 decompression - the {@code Xref} row (to know the
 * master key), the master record (to compare its values) and the add-on's state of the record (to know whether it is merged away) - plus one more
 * {@code Xref} lookup per foreign key of a child row. Lot M10 ({@code ChildKeysPass}) had shown the cure on the same data: one streaming read per
 * table and a join in memory took a 574 s computation to 11 s.
 * <p>
 * <b>The three indexes</b> (SCAN mode, each built by ONE streaming request, in table order, closed before the first write of the entity):
 * <ol>
 * <li><b>Xref</b>: {@code stagingKey -> masterKey} of this project and this entity's staging table ({@code Xref}'s key is {@code (project, stagingTable,
 * stagingKey)}; the request is restricted on the first two). A row with no master key yet is simply absent. It is also what {@code ParentKeys} and
 * pass 2 read for a parent, so the same map answers a child entity's foreign keys.</li>
 * <li><b>Master digests</b>: {@code masterKey -> RowDigest} of EXACTLY the values the loader compares ({@code differsFromStored} reads the same
 * fields through the same paths), never the record. {@link RowDigest} has the equivalence of {@code transfer.StoredValueEquality} and is unit-tested
 * against it, so "digest of the planned values equals the digest of the stored ones" is "no value differs".</li>
 * <li><b>Merged-away keys</b> (only when matching is activated for the entity): the {@code functionalId} of every record whose add-on state is
 * {@code MERGED}, read from the metadataset's {@code RecordMetadata} table ({@link MameMetadatasetReads#readMergedFunctionalIds}) - the state the
 * per-row {@code MetadataHelper.getState} used to answer. If that table cannot be read the per-row check stays.</li>
 * </ol>
 * An unchanged row then costs a hash lookup in each map and the digest of its planned values: <b>zero random reads</b>. A row whose digest differs (or is
 * unknown, or whose master key is not in the index) takes the path of lot V3-M unchanged: it reads the master record by key, compares with
 * {@code StoredValueEquality}, and writes.
 * <p>
 * <b>Memory</b> (JDK 17+ with compressed pointers; strings are the key of the staging row, {@code "SAP|123456"}, and the master key, a decimal id):
 * Xref index about 145 bytes a row (staging key string 56 + master key string 48 + map node 32 + about 8 of table), master index about 130 (key 48 +
 * node 32 + table 8 + a {@link RowDigest} of 40, plus 8 per deferred foreign key), merged keys 90 a MERGED record. For the 889,165 addresses of Vol1m:
 * about 129 MB + 116 MB; for the 576,692 persons 84 MB + 75 MB. The two maps are sized once from the table sizes (no rehash, so no transient doubling).
 * The master index and the merged keys are dropped when the entity ends; the Xref index of an entity is kept only while a later entity (or pass 2) reads
 * its keys as a parent. At the bound {@link ScanBudget#DEFAULT_MAX_INDEXED_ROWS} (5,000,000 rows) one entity's two indexes weigh about 1.4 GB, against
 * the 8 GB of the FastTrack server; above it, or on any failure of a scan, the entity uses the lookups of lot V3-M ({@code LOOKUP} mode) and says why.
 * <p>
 * <b>Staleness.</b> The maps are built before the entity's first write and read afterwards. A staging row is read once per load and a master record is
 * written at most once, so a row is never compared with a state its own write changed; an insert has a key the maps do not know (fine: it is an insert),
 * and every written row's key is put in the Xref map after its {@code Xref} write ({@link #recordWritten}), which is what a child entity needs. One
 * thing could still move under the maps: the add-on may MERGE a record as a side effect of a write of this very load (automatic matching on create or
 * update). Two defences keep the data exactly as the per-row path would have it: a row about to be WRITTEN has its record read anyway, and
 * {@code MasterLoader} then asks the add-on live whether it is merged away; and a parent's foreign key is answered from the merged keys only while NO row
 * was written to the parent's table since those keys were last read ({@link #knownNotMerged}), otherwise by the live golden-record chain as before.
 * Only the counters can differ in that edge (a row merged during the load, unchanged, counts {@code unchanged} instead of {@code skippedMerged}).
 * <p>
 * <b>The merged keys are read AGAIN once the entity's own pass is over</b> ({@link #refreshMergedKeys}, lot V3-M2 fix, 2026-10-04). Without it, ONE row
 * written to a parent's table sent every foreign key of every child row to the live add-on check for the rest of the load (measured on Vol100k: a load
 * where one Personne changed took 28.0 s on Adresse instead of 1.9-2.4 s, with 0 Adresse row written). The re-read is one streaming request (1-30 ms
 * measured) and sees every merge the entity's writes caused, so after it the pre-built answer is as fresh as the live one; the counter {@link
 * #writesSinceMergedRead} restarts there ({@link #writes} keeps counting for the step detail). A later write to the same table (pass 2 patches of a
 * self-referencing entity) counts again. If the re-read fails, the live check stays, as before.
 * <p>
 * <b>The golden record of a merged parent is read in the same request</b> (lot V3-M3, 2026-10-04): the merged read also returns each MERGED record's {@code
 * goldenId} ({@link #goldenOfMerged}), under the same staleness rule. Before, a child entity whose parents were merged asked the add-on LIVE for the golden of
 * every distinct merged parent - Vol1mF's Adresse, 417,841 merged parents, 0 row written: 370 s, 725 s for the first load after the merges. The map costs about 90
 * bytes per MERGED record (the key set it replaces cost the same) plus about 50 per distinct golden key (golden strings are shared inside a group).
 * <p>
 * <b>Lot IDX (2026-10-12, docs/VOLUME.md "Lot IDX - compact indexes"): the three maps are compact.</b> The sizes quoted above (145 / 130 / 90 bytes a row) were the cost of
 * {@code HashMap<String, String>} and {@code HashMap<String, RowDigest>}. They are now flat {@code long[]} tables keyed by the 128-bit {@link KeyDigest} of the key text
 * ({@code index.DigestMap}, {@code index.KeyValueIndex}): the {@code Xref} index holds a digest and one {@code long} (the master key as a number when it is a
 * canonical decimal, which every auto-increment master key is), the master index a digest and the {@link RowDigest}'s words, the merged index a digest and the golden's
 * code - about 30, 40 and 30 bytes a row. What consumers need as TEXT is decoded on the way out: the master key of an {@code Xref} row (it is written into child
 * foreign keys and read by key), the golden of a merged record (the next hop of a chain, then a foreign key); nothing else is ever held as text. Exactness: a digest shared by
 * two keys of one scan makes the scan's index UNUSABLE ({@code DigestMap#duplicates}): the entity runs in {@code LOOKUP} mode, or the merged check stays per row - never a wrong answer.
 */
final class MasterLoadReads
{
	/** Rows between two looks at the stop request while an index is being built. */
	private static final int TICK_EVERY = 20000;

	final String entityName;
	/** True: the indexes below exist. False: LOOKUP mode, {@link #lookupReason} says why. */
	final boolean scan;
	final String lookupReason;

	/** {@code stagingKey -> masterKey}, null in LOOKUP mode (and once released). Kept up to date by {@link #recordWritten}. Lot IDX: digest keys, the master key as a number when it is one. */
	KeyValueIndex xrefMasterKeys;
	/**
	 * Lot IDX: {@code masterKey digest -> RowDigest words} ({@link RowDigest#storedWords}: the 128 bits, then one hash per deferred field; all zeros = "the record exists,
	 * its digest is not known"), null in LOOKUP mode and after the entity ended.
	 */
	DigestMap storedDigests;
	/** Lot IDX: deferred (optional) fields of the layout, i.e. how many extra words a stored digest has. */
	int optionalCount;
	/**
	 * The master keys whose add-on state is MERGED, each with the golden record it was merged into (lot V3-M3: read in the same request) - empty value for a record whose
	 * {@code goldenId} the add-on did not give, or for a keys-only re-read ({@link #applyRefreshedMergedKeys}). Null when matching is not activated or the metadataset could not
	 * be read (per-row check). Lot IDX: a compact index, see the class Javadoc. Valid under the staleness rule of {@link #knownNotMerged}.
	 */
	KeyValueIndex merged;
	/** Which fields are compared always and which only when planned; null in LOOKUP mode. */
	RowDigest.Layout layout;

	/** Lot IDX: estimated heap of the three indexes at their largest (taken when the entity's pass starts, when they are all built); reported as {@code indexBytes}. */
	private long peakIndexBytes;
	/** Lot IDX: the last key digested, so that the several lookups a row makes for one master key hash it once. One immutable holder: a race at worst recomputes. */
	private volatile Memo memo;

	private static final class Memo
	{
		final String key;
		final KeyDigest digest;

		Memo(final String pKey, final KeyDigest pDigest)
		{
			this.key = pKey;
			this.digest = pDigest;
		}
	}

	/** How merged-away is answered: {@code NEVER} (matching not activated), {@code SCAN} (merged keys), {@code LOOKUP} (per-row add-on read). */
	String mergedMode = "NEVER";
	/** True once a stop request ended the build: the caller stops the load. */
	boolean cancelled;
	/** Rows written to this entity's master table in this load (inserted + updated): gates the use of {@link #merged} for a parent's foreign keys. */
	long writes;
	/** Rows written to this entity's master table since {@link #merged} were last read (build, or {@link #refreshMergedKeys}): what {@link #knownNotMerged} tests. */
	long writesSinceMergedRead;

	// statistics for the step detail
	long xrefRows;
	long xrefMs;
	long masterRows;
	long masterMs;
	long mergedRows;
	long mergedMs;
	/** Lot V3-M2 fix: the merged keys were read again after the entity's own pass; milliseconds and rows of that read (0 / 0 when it did not happen). */
	boolean mergedRefreshed;
	long mergedRefreshMs;
	long mergedRefreshRows;

	// where the merged keys come from, kept for the re-read (null in LOOKUP mode or when matching is not activated)
	private Repository repository;
	private String masterDataspaceKey;
	private String masterDatasetName;
	private String masterTablePath;

	private MasterLoadReads(final String pEntityName, final boolean pScan, final String pLookupReason)
	{
		this.entityName = pEntityName;
		this.scan = pScan;
		this.lookupReason = pLookupReason;
	}

	/** LOOKUP mode: no index, every read is the per-row one of lot V3-M. */
	static MasterLoadReads lookup(final String pEntityName, final String pReason)
	{
		return new MasterLoadReads(pEntityName, false, pReason);
	}

	/**
	 * Decides the mode and, in SCAN mode, builds the indexes. Never throws for a scan problem: the entity falls back to LOOKUP mode with the
	 * reason (a failure here must cost speed, never the load). {@link #cancelled} is set when a stop request arrived during the build.
	 */
	static MasterLoadReads build(final Adaptation pFactory, final String pProjectId, final MasterTransferPlan.Entity pEntity, final AdaptationTable pMasterTable,
		final long pStagingRows, final boolean pMatchingActivated, final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final java.util.Set<String> pWritableMasterFields, final Map<String, Path> pFieldPaths, final FactoryJobs.Handle pHandle, final StagingLoadListener pListener)
	{
		final long max = ScanBudget.current();
		final long masterRowCount = max <= 0L ? 0L : MasterLoadReads.size(pMasterTable);
		final String refusal = ScanBudget.refusal(max, pStagingRows, masterRowCount);
		if (refusal != null)
		{
			return MasterLoadReads.lookup(pEntity.name, refusal);
		}
		// the layout: a deferred foreign key is compared only when the row plans it (lot V3-M leaves it out of an update row), every other field always
		final List<String> fixed = new ArrayList<String>();
		final List<String> optional = new ArrayList<String>();
		for (final MasterTransferPlan.FieldPlan field : pEntity.fields)
		{
			if (!pWritableMasterFields.contains(field.masterField))
			{
				continue;
			}
			(field.isForeignKey && field.deferred ? optional : fixed).add(field.masterField);
		}
		final RowDigest.Layout layout = new RowDigest.Layout(fixed, optional);
		if (!layout.usable())
		{
			return MasterLoadReads.lookup(pEntity.name, optional.size() + " deferred foreign keys: more than a digest can mark");
		}

		final MasterLoadReads reads = new MasterLoadReads(pEntity.name, true, null);
		reads.layout = layout;
		try
		{
			// 1. Xref
			pListener.note(pEntity.name + ": reading the Xref index (one scan).");
			long start = System.currentTimeMillis();
			reads.xrefMasterKeys = new KeyValueIndex(pStagingRows);
			if (!MasterLoadReads.scanXref(pFactory, pProjectId, pEntity.name, reads, pHandle))
			{
				reads.cancelled = true;
				return reads;
			}
			reads.xrefMs = System.currentTimeMillis() - start;
			reads.xrefRows = reads.xrefMasterKeys.size();
			if (reads.xrefMasterKeys.duplicates() > 0L)
			{
				// two staging keys of one entity share a 128-bit digest (or a key appears twice): the index cannot tell them apart, so it is not used
				return MasterLoadReads.collided(pEntity.name, "Xref", reads.xrefMasterKeys.duplicates());
			}

			// 2. master digests
			pListener.note(pEntity.name + ": reading the master table (one scan).");
			start = System.currentTimeMillis();
			reads.optionalCount = optional.size();
			reads.storedDigests = new DigestMap(RowDigest.storedWordCount(reads.optionalCount), masterRowCount);
			if (!MasterLoadReads.scanMaster(pMasterTable, reads, pFieldPaths, fixed, optional, pHandle))
			{
				reads.cancelled = true;
				return reads;
			}
			reads.masterMs = System.currentTimeMillis() - start;
			reads.masterRows = reads.storedDigests.size();
			if (reads.storedDigests.duplicates() > 0L)
			{
				return MasterLoadReads.collided(pEntity.name, "master", reads.storedDigests.duplicates());
			}

			// 3. merged-away keys
			if (pMatchingActivated)
			{
				pListener.note(pEntity.name + ": reading the merged records (one scan).");
				start = System.currentTimeMillis();
				final boolean[] stopped = new boolean[1];
				reads.repository = pRepository;
				reads.masterDataspaceKey = pMasterDataspaceKey;
				reads.masterDatasetName = pMasterDatasetName;
				reads.masterTablePath = MasterModel.tablePath(pEntity.name);
				reads.applyMerged(MameMetadatasetReads.readMergedIndex(pRepository, pMasterDataspaceKey, pMasterDatasetName, reads.masterTablePath,
					MasterLoadReads.tick(pHandle, stopped)));
				reads.mergedMs = System.currentTimeMillis() - start;
				if (stopped[0])
				{
					reads.cancelled = true;
					return reads;
				}
				reads.mergedMode = reads.merged == null ? "LOOKUP" : "SCAN";
				reads.mergedRows = reads.merged == null ? 0L : reads.merged.size();
			}
			reads.notePeak();
			return reads;
		}
		catch (final RuntimeException ex)
		{
			// a failed scan costs speed, never the load: the entity keeps the lookups of lot V3-M
			LoggingCategory.getKernel().warn("[mdm-factory] master load of " + pEntity.name + ": the read indexes could not be built, using per-row lookups instead: " + ex);
			return MasterLoadReads.lookup(pEntity.name, "index build failed: " + ex);
		}
	}

	/** The entity cannot use its indexes because two keys of one scan share a digest: LOOKUP mode, said in the step detail, logged. */
	private static MasterLoadReads collided(final String pEntityName, final String pIndex, final long pDuplicates)
	{
		LoggingCategory.getKernel().warn("[mdm-factory] master load of " + pEntityName + ": " + pDuplicates + " key(s) of the " + pIndex
			+ " scan share a 128-bit digest with another one, using per-row lookups instead");
		return MasterLoadReads.lookup(pEntityName, pDuplicates + " key(s) of the " + pIndex + " scan share a 128-bit digest with another one");
	}

	// ============================================================================================== the scans

	/** @return false when a stop request interrupted the scan */
	private static boolean scanXref(final Adaptation pFactory, final String pProjectId, final String pEntityName, final MasterLoadReads pReads, final FactoryJobs.Handle pHandle)
	{
		final AdaptationTable xrefTable = pFactory.getTable(FactoryPaths.XREF);
		final RequestResult rows = xrefTable.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./stagingTable=" + CatalogReader.quote(pEntityName));
		try
		{
			long count = 0;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String masterKey = row.getString(FactoryPaths.Xref.MASTER_KEY);
				if (masterKey != null && !masterKey.trim().isEmpty())
				{
					pReads.xrefMasterKeys.add(row.getString(FactoryPaths.Xref.STAGING_KEY), masterKey);
				}
				if (++count % MasterLoadReads.TICK_EVERY == 0 && pHandle != null && pHandle.cancelled())
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

	/** @return false when a stop request interrupted the scan */
	private static boolean scanMaster(final AdaptationTable pMasterTable, final MasterLoadReads pReads, final Map<String, Path> pFieldPaths, final List<String> pFixed,
		final List<String> pOptional, final FactoryJobs.Handle pHandle)
	{
		// the same paths differsFromStored reads, resolved once
		final Map<String, Path> paths = new HashMap<String, Path>();
		for (final String name : pFixed)
		{
			paths.put(name, pFieldPaths.containsKey(name) ? pFieldPaths.get(name) : Path.parse("./" + name));
		}
		for (final String name : pOptional)
		{
			paths.put(name, pFieldPaths.containsKey(name) ? pFieldPaths.get(name) : Path.parse("./" + name));
		}
		final long[] words = new long[RowDigest.storedWordCount(pReads.optionalCount)];
		final RequestResult rows = pMasterTable.createRequestResult(null);
		try
		{
			long count = 0;
			for (Adaptation record = rows.nextAdaptation(); record != null; record = rows.nextAdaptation())
			{
				final Adaptation current = record;
				RowDigest digest;
				try
				{
					digest = pReads.layout.stored(new RowDigest.FieldReader()
					{
						@Override
						public Object read(final String pFieldName)
						{
							return current.get(paths.get(pFieldName));
						}
					});
				}
				catch (final RuntimeException ex)
				{
					digest = RowDigest.UNKNOWN; // a record that cannot be read here is compared by the per-row path
				}
				final int slot = pReads.storedDigests.addUnique(KeyDigest.of(record.getOccurrencePrimaryKey().format()));
				if (slot >= 0) // -1: the digest of this key was already there, counted as a collision by the caller
				{
					digest.storedWords(words, pReads.optionalCount);
					for (int i = 0; i < words.length; i++)
					{
						pReads.storedDigests.set(slot, i, words[i]);
					}
				}
				if (++count % MasterLoadReads.TICK_EVERY == 0 && pHandle != null && pHandle.cancelled())
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

	/** Rows of a table (a count, no row read); also used by {@code MasterLoader} to tell an empty master table (lot M-INC). */
	static long size(final AdaptationTable pTable)
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
	static int capacity(final long pRows)
	{
		final long wanted = pRows * 4L / 3L + 16L;
		return (int) Math.min(wanted, 1L << 29);
	}

	// ============================================================================================== reads

	/** Lot IDX: the digest of {@code pKey}, memoized for the last key (a row asks about one master key three or four times). */
	private KeyDigest digestOf(final String pKey)
	{
		final Memo known = this.memo;
		if (known != null && known.key.equals(pKey))
		{
			return known.digest;
		}
		final KeyDigest digest = KeyDigest.of(pKey);
		this.memo = new Memo(pKey, digest);
		return digest;
	}

	/** Whether the master index lists a record at this key (it existed when the index was built). False in LOOKUP mode, after the entity ended, and for a key the index does not know. */
	boolean masterIndexed(final String pMasterKey)
	{
		return this.storedDigests != null && this.storedDigests.find(this.digestOf(pMasterKey)) >= 0;
	}

	/**
	 * True only when the master index holds a digest for this key and it equals {@code pPlanned} - every planned value is already stored. False for a key the index does
	 * not know, a record whose digest was not known (a value the digest cannot stand for), and an unknown planned digest: the caller then reads the record.
	 */
	boolean sameAsStored(final String pMasterKey, final RowDigest pPlanned)
	{
		if (this.storedDigests == null)
		{
			return false;
		}
		final int slot = this.storedDigests.find(this.digestOf(pMasterKey));
		if (slot < 0)
		{
			return false;
		}
		final long[] words = new long[RowDigest.storedWordCount(this.optionalCount)];
		for (int i = 0; i < words.length; i++)
		{
			words[i] = this.storedDigests.get(slot, i);
		}
		return pPlanned.sameAsWords(words);
	}

	/** Whether the merged records were read (so {@link #isMerged} answers); false when matching is not activated, in LOOKUP mode, or when the read failed. */
	boolean mergedRead()
	{
		return this.merged != null;
	}

	/** Whether the merged read lists this key. Only meaningful while {@link #mergedRead()}; the caller applies its own staleness rule. */
	boolean isMerged(final String pMasterKey)
	{
		return this.merged != null && this.merged.contains(this.digestOf(pMasterKey));
	}

	/** A scan tick that stops on the job's stop request and says so in {@code pStopped[0]}. */
	private static MameMetadatasetReads.ScanTick tick(final FactoryJobs.Handle pHandle, final boolean[] pStopped)
	{
		return new MameMetadatasetReads.ScanTick()
		{
			@Override
			public boolean keepGoing(final long pRowsRead)
			{
				if (pHandle != null && pHandle.cancelled())
				{
					pStopped[0] = true;
					return false;
				}
				return true;
			}
		};
	}

	/**
	 * True only when the add-on state of this record is known NOT to be MERGED without asking it: the merged keys were read, and no row was written to this
	 * table since they were (a write could have made the add-on merge something - see the class Javadoc). Used for a parent's foreign key.
	 */
	boolean knownNotMerged(final String pMasterKey)
	{
		return this.merged != null && this.writesSinceMergedRead == 0L && !this.merged.contains(this.digestOf(pMasterKey));
	}

	/**
	 * Lot V3-M3 (2026-10-04): the golden record {@code pMasterKey} was merged into, answered from the merged read without asking the add-on, or null when the
	 * index cannot say - LOOKUP mode, the merged keys read without golden keys, a record not in them, a record whose add-on {@code goldenId} was empty, or a
	 * write to this table since the read (the same staleness rule as {@link #knownNotMerged}: the add-on may have merged or re-pointed something as a side
	 * effect of a write, so only the live answer is trusted until {@link #refreshMergedKeys} reads again). The caller asks the add-on in every null case.
	 */
	String goldenOfMerged(final String pMasterKey)
	{
		if (this.merged == null || this.writesSinceMergedRead != 0L)
		{
			return null;
		}
		final String golden = this.merged.get(this.digestOf(pMasterKey));
		return golden == null || golden.isEmpty() ? null : golden;
	}

	/** Takes a merged read (merged key to golden key): the keys and the goldens come from the one index. Null = not read. */
	void applyMerged(final KeyValueIndex pMerged)
	{
		this.merged = pMerged;
	}

	/**
	 * Lot V3-M2 fix (2026-10-04): called once the entity's own pass is over (every row planned and written), BEFORE a later entity or pass 2 reads it as a
	 * parent. When rows were written to the table and the merged keys were read by scan, reads them again - one streaming request, the same call as at
	 * build time - so that {@link #knownNotMerged} answers from them again instead of every child foreign key paying the live add-on check. A failed or
	 * stopped re-read keeps the live check (the counter is not reset). No-op when nothing was written or the merged keys are not in use.
	 */
	void refreshMergedKeys(final FactoryJobs.Handle pHandle)
	{
		if (this.merged == null || this.writesSinceMergedRead == 0L || this.repository == null)
		{
			return;
		}
		final long start = System.currentTimeMillis();
		KeyValueIndex fresh = null;
		try
		{
			fresh = MameMetadatasetReads.readMergedIndex(this.repository, this.masterDataspaceKey, this.masterDatasetName, this.masterTablePath,
				MasterLoadReads.tick(pHandle, new boolean[1]));
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] master load of " + this.entityName + ": the merged keys could not be read again, keeping the live merge check: " + ex);
		}
		if (fresh != null)
		{
			this.applyMerged(fresh);
			this.noteRefreshed(fresh.size(), System.currentTimeMillis() - start);
		}
	}

	/** The pure half of {@link #refreshMergedKeys}: null keeps the live check, a set replaces the merged keys and restarts the write counter. */
	void applyRefreshedMergedKeys(final Set<String> pFresh, final long pMs)
	{
		if (pFresh == null)
		{
			return;
		}
		final KeyValueIndex keys = new KeyValueIndex(pFresh.size());
		for (final String key : pFresh)
		{
			keys.add(key, ""); // keys only: no golden to answer from
		}
		this.merged = keys;
		this.noteRefreshed(pFresh.size(), pMs);
	}

	private void noteRefreshed(final int pRows, final long pMs)
	{
		this.writesSinceMergedRead = 0L;
		this.mergedRefreshed = true;
		this.mergedRefreshMs += pMs;
		this.mergedRefreshRows = pRows;
	}

	/** A row was inserted or updated and its {@code Xref} written: the index now holds its key (what a child entity resolves a foreign key through). */
	void recordWritten(final String pStagingKey, final String pMasterKey)
	{
		this.writes++;
		this.writesSinceMergedRead++;
		if (this.xrefMasterKeys != null && pStagingKey != null && pMasterKey != null)
		{
			this.xrefMasterKeys.put(pStagingKey, pMasterKey);
		}
	}

	/** Lot IDX: remembers the largest the three indexes were at (estimated heap), for the step detail. */
	private void notePeak()
	{
		final long now = (this.xrefMasterKeys == null ? 0L : this.xrefMasterKeys.estimatedBytes()) + (this.storedDigests == null ? 0L : this.storedDigests.bytes())
			+ (this.merged == null ? 0L : this.merged.estimatedBytes());
		this.peakIndexBytes = Math.max(this.peakIndexBytes, now);
	}

	/** The entity is done: what only its own rows needed goes (the Xref index stays while a later entity or pass 2 reads it as a parent). */
	void releaseEntity()
	{
		this.notePeak();
		this.storedDigests = null;
		this.layout = null;
	}

	/** Nobody reads this entity's keys any more. */
	void releaseAll()
	{
		this.releaseEntity();
		this.xrefMasterKeys = null;
		this.merged = null;
	}

	/** {@code readMode}, index sizes and the milliseconds spent building each index: added to the entity's step detail. */
	void describe(final ObjectNode pDetail)
	{
		pDetail.put("readMode", this.scan ? "SCAN" : "LOOKUP");
		if (!this.scan)
		{
			pDetail.put("lookupReason", this.lookupReason);
			return;
		}
		pDetail.put("xrefIndexRows", this.xrefRows);
		pDetail.put("xrefIndexMs", this.xrefMs);
		pDetail.put("masterIndexRows", this.masterRows);
		pDetail.put("masterIndexMs", this.masterMs);
		pDetail.put("mergedRead", this.mergedMode);
		pDetail.put("mergedIndexRows", this.mergedRows);
		pDetail.put("mergedIndexMs", this.mergedMs);
		this.notePeak();
		pDetail.put("indexBytes", this.peakIndexBytes); // lot IDX: estimated heap of the three indexes of this entity (the Xref index of a parent it reads is that entity's own)
		if (this.mergedRefreshed)
		{
			pDetail.put("mergedRefreshMs", this.mergedRefreshMs);
			pDetail.put("mergedRefreshRows", this.mergedRefreshRows);
		}
	}
}
