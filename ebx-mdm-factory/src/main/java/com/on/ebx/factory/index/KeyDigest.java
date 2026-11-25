package com.on.ebx.factory.index;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Lot IDX (2026-10-12, docs/VOLUME.md "Lot IDX - compact indexes"): the 128-bit digest of a key text (a staging key {@code VOL1MCRM|C000123}, a master key, a golden
 * key, a {@code RecordMetadata.functionalId}) that the compact indexes ({@link DigestMap}, {@link KeyValueIndex}) hold INSTEAD of the {@code String}: 16 bytes
 * in a flat array against 48-56 for the string alone plus a map node.
 * <p>
 * <b>What it is.</b> SHA-256 of the key's UTF-16 code units (never {@code getBytes("UTF-8")}, which turns an unpaired surrogate into {@code '?'} and would make two
 * different keys collide - the same rule as {@code transfer.RowDigest}), the first 16 bytes as two {@code long}s. SHA-256 and not a fast non-cryptographic hash
 * on purpose: the keys come from source systems, and a hash anyone can invert (Murmur, FNV, a plain {@code String.hashCode}) would let a crafted source key
 * collide with another record's and redirect an update. With a cryptographic hash, a collision needs about 2^64 work. About 0.25 microsecond a key.
 * <p>
 * <b>The reserved value.</b> {@code (0, 0)} is the empty-slot marker of {@link DigestMap}, so a key whose digest is exactly that is stored as {@code (0, 1)} - it
 * then collides with the key whose digest is {@code (0, 1)}, a pair that the build-time duplicate detection reports like any other collision (probability 2^-127).
 * <p>
 * Immutable and thread safe; the {@code MessageDigest} is per thread.
 */
public final class KeyDigest
{
	/** One per thread: the SHA-256 engine, the bytes of the key being hashed (keys are short: a staging key is 20-60 characters) and the 32 bytes it answers. */
	private static final class State
	{
		final MessageDigest digest;
		byte[] bytes = new byte[256];
		final byte[] hash = new byte[32];

		State()
		{
			try
			{
				this.digest = MessageDigest.getInstance("SHA-256");
			}
			catch (final NoSuchAlgorithmException ex)
			{
				throw new IllegalStateException("SHA-256 is part of every Java platform", ex);
			}
		}
	}

	private static final ThreadLocal<State> STATES = new ThreadLocal<State>()
	{
		@Override
		protected State initialValue()
		{
			return new State();
		}
	};

	private final long hi;
	private final long lo;

	private KeyDigest(final long pHi, final long pLo)
	{
		this.hi = pHi;
		this.lo = pLo;
	}

	/** The digest of {@code pKey}; {@code pKey} must not be null. */
	public static KeyDigest of(final String pKey)
	{
		final int length = pKey.length();
		final State state = KeyDigest.STATES.get();
		if (state.bytes.length < 2 * length)
		{
			state.bytes = new byte[Math.max(2 * length, 2 * state.bytes.length)];
		}
		final byte[] bytes = state.bytes;
		for (int i = 0; i < length; i++)
		{
			final char c = pKey.charAt(i);
			bytes[2 * i] = (byte) (c >>> 8);
			bytes[2 * i + 1] = (byte) c;
		}
		try
		{
			state.digest.update(bytes, 0, 2 * length);
			state.digest.digest(state.hash, 0, 32); // also resets the engine
		}
		catch (final DigestException ex)
		{
			throw new IllegalStateException(ex);
		}
		return KeyDigest.of(KeyDigest.long8(state.hash, 0), KeyDigest.long8(state.hash, 8));
	}

	/** A digest from its two words (the reserved {@code (0, 0)} is stored as {@code (0, 1)}, see the class Javadoc). */
	public static KeyDigest of(final long pHi, final long pLo)
	{
		return pHi == 0L && pLo == 0L ? new KeyDigest(0L, 1L) : new KeyDigest(pHi, pLo);
	}

	public long hi()
	{
		return this.hi;
	}

	public long lo()
	{
		return this.lo;
	}

	@Override
	public boolean equals(final Object pOther)
	{
		return pOther instanceof KeyDigest && ((KeyDigest) pOther).hi == this.hi && ((KeyDigest) pOther).lo == this.lo;
	}

	@Override
	public int hashCode()
	{
		return (int) (this.hi ^ (this.hi >>> 32));
	}

	@Override
	public String toString()
	{
		return String.format("%016x%016x", Long.valueOf(this.hi), Long.valueOf(this.lo));
	}

	private static long long8(final byte[] pHash, final int pOffset)
	{
		long out = 0L;
		for (int i = 0; i < 8; i++)
		{
			out = (out << 8) | (pHash[pOffset + i] & 0xFFL);
		}
		return out;
	}
}
