package com.on.ebx.factory.index;

/**
 * Lot IDX (2026-10-12, docs/VOLUME.md "Lot IDX - compact indexes"): a {@code Map<String, String>} for the indexes whose key AND value are key texts - the {@code
 * Xref} index ({@code stagingKey -> masterKey}), the merged records ({@code masterKey -> golden key}), the golden-key refresh's {@code RecordMetadata} index
 * ({@code functionalId -> encoded state or golden}) - held as a {@link DigestMap} of one {@code long}: the key as a 128-bit {@link KeyDigest}, the value as a
 * {@link KeyCodec} code. About 30 bytes an entry when the values are numeric (every auto-increment master key), instead of 90 to 145.
 * <p>
 * <b>What it cannot do.</b> It cannot list its keys (only their digests are kept), so a consumer that needs the key text must have it from elsewhere - which none of
 * the three does: each is asked about a key it already holds. A value comes back as a new {@code String} for a numeric code (a short-lived allocation); a text
 * value is the one instance the codec keeps.
 * <p>
 * <b>Exactness.</b> {@link #add} is for a build from a scan of unique keys: a digest already present is counted in {@link #duplicates()} and the caller must refuse
 * the index. {@link #put} overwrites, for the one place an index follows the writes of the load (a written row's {@code Xref} key). See {@link DigestMap}.
 */
public final class KeyValueIndex
{
	private final DigestMap map;
	private KeyCodec codec = new KeyCodec();

	public KeyValueIndex(final long pExpectedEntries)
	{
		this.map = new DigestMap(1, pExpectedEntries);
	}

	/** Inserts a key that is expected to be new; false (and a collision counted) when its digest is already present - the existing entry is kept. A null key is ignored (true). */
	public boolean add(final String pKey, final String pValue)
	{
		if (pKey == null)
		{
			return true;
		}
		final int slot = this.map.addUnique(KeyDigest.of(pKey));
		if (slot < 0)
		{
			return false;
		}
		this.map.set(slot, 0, this.codec.encode(pValue));
		return true;
	}

	/** Inserts or overwrites (a null key is ignored). */
	public void put(final String pKey, final String pValue)
	{
		if (pKey == null)
		{
			return;
		}
		final int slot = this.map.slotFor(KeyDigest.of(pKey));
		this.map.set(slot, 0, this.codec.encode(pValue));
	}

	/** The value of this key; null when the key is absent AND when it is present with no value (use {@link #contains} to tell). */
	public String get(final String pKey)
	{
		return pKey == null ? null : this.get(KeyDigest.of(pKey));
	}

	public String get(final KeyDigest pKey)
	{
		final int slot = this.map.find(pKey);
		return slot < 0 ? null : this.codec.decode(this.map.get(slot, 0));
	}

	public boolean contains(final String pKey)
	{
		return pKey != null && this.map.find(KeyDigest.of(pKey)) >= 0;
	}

	public boolean contains(final KeyDigest pKey)
	{
		return this.map.find(pKey) >= 0;
	}

	public int size()
	{
		return this.map.size();
	}

	/** Collisions seen by {@link #add}: above 0 the index must not be used. */
	public long duplicates()
	{
		return this.map.duplicates();
	}

	/** Room for {@code pEntries} entries in all (one rehash; free on an empty index). */
	public void ensureCapacity(final long pEntries)
	{
		this.map.ensureCapacity(pEntries);
	}

	/** Forgets everything (keeps the room). */
	public void clear()
	{
		this.map.clear();
		this.codec = new KeyCodec();
	}

	/** Estimated heap held: the flat table plus the codec's table of non-numeric texts. */
	public long estimatedBytes()
	{
		return this.map.bytes() + this.codec.estimatedBytes();
	}
}
