package com.on.ebx.factory.index;

/**
 * Lot IDX (2026-10-12, docs/VOLUME.md "Lot IDX - compact indexes"): an open-addressing hash map from a 128-bit {@link KeyDigest} to a fixed number of {@code long}
 * values, with NO object per entry and no boxing: one flat {@code long[]} of {@code capacity} slots, each slot {@code [hi, lo, v0 .. v(width-1)]}. A slot whose
 * {@code hi} and {@code lo} are both zero is empty ({@link KeyDigest} never produces that pair).
 * <p>
 * <b>Why this and not a {@code HashMap<String, X>}.</b> Measured on Vol1mF (1,465,857 master rows, 2026-10-04) the three read indexes of a master load weighed about
 * 330 MB at the peak: a key string of 48-56 bytes, a 32-byte map node, a table slot and, for the master index, a 40-byte digest object, about 90 to 145 bytes a
 * row. Here a row is {@code 8 x (2 + width)} bytes divided by the load factor: 30 bytes for a key and one value, 40 for a key and two. The numbers are in the doc.
 * <p>
 * <b>Placement.</b> The digest is already uniform, so the slot is {@code (hi >>> 33) * capacity >>> 31} (a multiply-shift range reduction, no power-of-two
 * capacity is needed and the table can be sized to the row count, not to the next power of two) and collisions are resolved by linear probing, which is
 * sequential in a flat array. The table is sized from the expected number of entries at {@link #DEFAULT_LOAD_PERCENT} full, so a build that knows its row count never
 * rehashes; it still GROWS (by half) if more entries come, so a load that inserts more rows than it scanned stays correct.
 * <p>
 * <b>Exactness.</b> Two different keys with the same 128-bit digest would be the same entry. {@link #addUnique} is the build-time guard: a scan of a table whose key is
 * its primary key never presents the same key twice, so a digest that is already there IS a collision; it is counted in {@link #duplicates()} and the caller refuses
 * the index (the entity keeps its per-row reads). A key that is NOT in the table but collides with one that is can only be seen at query time, with probability
 * about n x q / 2^128 for n indexed keys and q queries (3e-25 for 10^7 of each).
 * <p>
 * Not thread safe for writes; reads ({@link #find}, {@link #get}) are safe once the builder has finished.
 */
public final class DigestMap
{
	/** How full the table may get before it grows. */
	public static final int DEFAULT_LOAD_PERCENT = 85;

	private static final int MIN_CAPACITY = 16;

	private final int width;
	private final int stride;
	private final int loadPercent;
	private final int maxCapacity;
	private long[] table;
	private int capacity;
	private int size;
	private int limit;
	private long duplicates;
	private boolean created;

	/** @param pWidth number of {@code long} values per entry (0 makes a set of digests) */
	public DigestMap(final int pWidth, final long pExpectedEntries)
	{
		this(pWidth, pExpectedEntries, DigestMap.DEFAULT_LOAD_PERCENT);
	}

	public DigestMap(final int pWidth, final long pExpectedEntries, final int pLoadPercent)
	{
		if (pWidth < 0 || pLoadPercent < 10 || pLoadPercent > 95)
		{
			throw new IllegalArgumentException("width " + pWidth + ", load " + pLoadPercent + "%");
		}
		this.width = pWidth;
		this.stride = 2 + pWidth;
		this.loadPercent = pLoadPercent;
		this.maxCapacity = (Integer.MAX_VALUE - 16) / this.stride;
		this.allocate(DigestMap.capacityFor(pExpectedEntries, pLoadPercent, this.maxCapacity));
	}

	private static int capacityFor(final long pEntries, final int pLoadPercent, final int pMaxCapacity)
	{
		final long wanted = Math.max(DigestMap.MIN_CAPACITY, Math.max(0L, pEntries) * 100L / pLoadPercent + 8L);
		return (int) Math.min(wanted, pMaxCapacity);
	}

	private void allocate(final int pCapacity)
	{
		this.capacity = pCapacity;
		this.table = new long[pCapacity * this.stride];
		this.limit = (int) Math.min((long) pCapacity * this.loadPercent / 100L, pCapacity - 1L);
	}

	// ============================================================================================== reads

	/** The slot of this digest, or -1 when it is not in the map. */
	public int find(final long pHi, final long pLo)
	{
		if (pHi == 0L && pLo == 0L)
		{
			return -1;
		}
		final long[] t = this.table;
		final int cap = this.capacity;
		final int st = this.stride;
		int slot = this.slotOf(pHi);
		while (true)
		{
			final int base = slot * st;
			final long hi = t[base];
			final long lo = t[base + 1];
			if (hi == pHi && lo == pLo)
			{
				return slot;
			}
			if (hi == 0L && lo == 0L)
			{
				return -1;
			}
			if (++slot == cap)
			{
				slot = 0;
			}
		}
	}

	public int find(final KeyDigest pKey)
	{
		return this.find(pKey.hi(), pKey.lo());
	}

	public int find(final String pKey)
	{
		return this.find(KeyDigest.of(pKey));
	}

	/** Value {@code pIndex} of the entry in {@code pSlot} (a slot returned by {@link #find} or {@link #slotFor}). */
	public long get(final int pSlot, final int pIndex)
	{
		return this.table[pSlot * this.stride + 2 + pIndex];
	}

	/** True when every value of the entry is zero (how a caller marks "no digest known"). */
	public boolean allZero(final int pSlot)
	{
		final int base = pSlot * this.stride + 2;
		for (int i = 0; i < this.width; i++)
		{
			if (this.table[base + i] != 0L)
			{
				return false;
			}
		}
		return true;
	}

	public int size()
	{
		return this.size;
	}

	public int width()
	{
		return this.width;
	}

	public int capacity()
	{
		return this.capacity;
	}

	/** Digests presented to {@link #addUnique} that were already there: collisions between two keys of a table whose keys are unique. */
	public long duplicates()
	{
		return this.duplicates;
	}

	/** Bytes held by the table (the part that grows with the rows). */
	public long bytes()
	{
		return 16L + 8L * this.table.length;
	}

	// ============================================================================================== writes

	/**
	 * The slot of this digest, created (all values zero) when absent; {@link #lastCreated} says which. The way to INSERT OR OVERWRITE: set the values after it.
	 * Grows the table when it is full.
	 */
	public int slotFor(final long pHi, final long pLo)
	{
		if (pHi == 0L && pLo == 0L)
		{
			throw new IllegalArgumentException("the digest (0, 0) is the empty-slot marker; build keys with KeyDigest");
		}
		if (this.size >= this.limit)
		{
			this.grow();
		}
		final long[] t = this.table;
		final int cap = this.capacity;
		final int st = this.stride;
		int slot = this.slotOf(pHi);
		while (true)
		{
			final int base = slot * st;
			final long hi = t[base];
			final long lo = t[base + 1];
			if (hi == pHi && lo == pLo)
			{
				this.created = false;
				return slot;
			}
			if (hi == 0L && lo == 0L)
			{
				t[base] = pHi;
				t[base + 1] = pLo;
				this.size++;
				this.created = true;
				return slot;
			}
			if (++slot == cap)
			{
				slot = 0;
			}
		}
	}

	public int slotFor(final KeyDigest pKey)
	{
		return this.slotFor(pKey.hi(), pKey.lo());
	}

	/** Whether the last {@link #slotFor} / {@link #addUnique} created the entry. */
	public boolean lastCreated()
	{
		return this.created;
	}

	/**
	 * Build-time insert for a key that must be unique: the new slot, or -1 when the digest is already there (counted in {@link #duplicates()}; the existing entry
	 * is left as it was).
	 */
	public int addUnique(final long pHi, final long pLo)
	{
		final int slot = this.slotFor(pHi, pLo);
		if (!this.created)
		{
			this.duplicates++;
			return -1;
		}
		return slot;
	}

	public int addUnique(final KeyDigest pKey)
	{
		return this.addUnique(pKey.hi(), pKey.lo());
	}

	public void set(final int pSlot, final int pIndex, final long pValue)
	{
		this.table[pSlot * this.stride + 2 + pIndex] = pValue;
	}

	/** Makes room for {@code pEntries} entries in all (rehashes once; cheap on an empty map). Never shrinks. */
	public void ensureCapacity(final long pEntries)
	{
		final int wanted = DigestMap.capacityFor(pEntries, this.loadPercent, this.maxCapacity);
		if (wanted > this.capacity)
		{
			this.rehash(wanted);
		}
	}

	/** Empties the map (keeps the capacity). */
	public void clear()
	{
		java.util.Arrays.fill(this.table, 0L);
		this.size = 0;
		this.duplicates = 0L;
	}

	private void grow()
	{
		if (this.capacity >= this.maxCapacity)
		{
			throw new IllegalStateException("DigestMap is full at " + this.size + " entries");
		}
		final long next = Math.max((long) this.capacity + this.capacity / 2, (long) this.capacity + 16L);
		this.rehash((int) Math.min(next, this.maxCapacity));
	}

	private void rehash(final int pNewCapacity)
	{
		final long[] old = this.table;
		final int oldCapacity = this.capacity;
		final int st = this.stride;
		this.allocate(pNewCapacity);
		final long[] t = this.table;
		for (int i = 0; i < oldCapacity; i++)
		{
			final int base = i * st;
			final long hi = old[base];
			final long lo = old[base + 1];
			if (hi == 0L && lo == 0L)
			{
				continue;
			}
			int slot = this.slotOf(hi);
			while (t[slot * st] != 0L || t[slot * st + 1] != 0L)
			{
				if (++slot == this.capacity)
				{
					slot = 0;
				}
			}
			System.arraycopy(old, base, t, slot * st, st);
		}
	}

	private int slotOf(final long pHi)
	{
		return (int) (((pHi >>> 33) * (long) this.capacity) >>> 31);
	}
}
