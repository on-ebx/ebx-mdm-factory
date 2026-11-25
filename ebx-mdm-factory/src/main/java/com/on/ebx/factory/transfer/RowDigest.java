package com.on.ebx.factory.transfer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Master-load lot V3-M2 (2026-10-04, docs/VOLUME.md "Lot V3-M2"): a fixed-size DIGEST of the values a master row
 * holds (or a load would write), with exactly the equivalence of {@link StoredValueEquality#sameValue} applied
 * field by field - so that "is this row unchanged" can be answered from ONE streaming read of the master table
 * (the digest of every record, kept in memory) instead of one random read of the record per staging row. Pure,
 * no EBX class - {@code RowDigestTest} pins the equivalence against {@code StoredValueEquality} on a table of
 * cases and on the whole cross product of a pool of values.
 * <p>
 * <b>The equivalence, reproduced.</b> Every value is first normalised the way {@code sameValue} does (an empty
 * string and an empty list are "no value", at every nesting level), then encoded with a TYPE TAG and a
 * self-delimiting body, so two values give the same bytes exactly when {@code sameValue} calls them equal:
 * <ul>
 * <li>no value: tag 0 - null, {@code ""} and an empty list are one and the same;</li>
 * <li>a number: tag 2 and the canonical decimal ({@code stripTrailingZeros}: unscaled value and scale, never a
 * {@code toPlainString}, which would allocate a gigabyte for {@code 1E+1000000000}) - {@code 10}, {@code 10.0},
 * {@code Integer 10} and {@code Long 10} are one value, as {@code sameNumber} (a {@code compareTo} of the
 * {@code BigDecimal}s) says; a number with no finite decimal form (NaN, infinity) is tag 3 and its class and
 * text, i.e. "only the identical boxed value", as {@code sameNumber} says;</li>
 * <li>a date: tag 4 and its time in milliseconds, whatever the class ({@code java.sql.Timestamp}, {@code Date});</li>
 * <li>a list: tag 6, its size, and the elements one after the other with the same rule;</li>
 * <li>a string: tag 1 and its length and characters, so the string {@code "10"} and the number 10 differ;</li>
 * <li>a boolean: tag 5.</li>
 * </ul>
 * <b>The safe direction.</b> Any other class of value ({@code sameValue} would compare it with {@code equals},
 * which no encoding can reproduce) makes the digest UNKNOWN: it never equals anything, not even itself, so the
 * caller falls back to reading the record and comparing with {@code StoredValueEquality} - a doubt costs a read,
 * never a lost update. Two different value lists that collide are astronomically unlikely (SHA-256 truncated
 * to 128 bits); the only way to a wrong "unchanged" is such a collision.
 * <p>
 * <b>Why a 128-bit digest of the always-present fields PLUS a 64-bit hash per DEFERRED foreign key, not one
 * hash per field.</b> Since lot V3-M a deferred (self-reference / cycle) foreign key of an update row is left
 * out of the plan when staging holds a reference (pass 2 resolves and patches it), so the set of compared
 * fields differs from row to row, and only for those fields. An entity without deferred fields (nearly all)
 * therefore costs one 128-bit digest, 40 bytes with its object header, per master record; one hash per field
 * would cost 16 + 8 x F bytes (F = fields of the plan, 20 to 40 for a person). An entity WITH deferred fields
 * adds 8 bytes per deferred field. A field left out of a planned digest ({@link #OMITTED}) is simply not
 * compared; the stored side always has every field.
 */
public final class RowDigest
{
	/** Stands in the optional values of {@link #of} for a deferred field the plan leaves out: that field is not compared. */
	public static final Object OMITTED = new Object();

	/** The digest of a row holding a value no encoding can reproduce: equals nothing. */
	public static final RowDigest UNKNOWN = new RowDigest(0L, 0L, null, 0, true);

	/** More deferred fields than an {@code int} mask holds: the caller must not use digests for that entity. */
	public static final int MAX_OPTIONAL_FIELDS = 31;

	private static final byte TAG_NULL = 0;
	private static final byte TAG_STRING = 1;
	private static final byte TAG_NUMBER = 2;
	private static final byte TAG_NUMBER_OTHER = 3;
	private static final byte TAG_DATE = 4;
	private static final byte TAG_BOOLEAN = 5;
	private static final byte TAG_LIST = 6;

	private static final ThreadLocal<MessageDigest> DIGESTS = new ThreadLocal<MessageDigest>()
	{
		@Override
		protected MessageDigest initialValue()
		{
			try
			{
				return MessageDigest.getInstance("SHA-256");
			}
			catch (final NoSuchAlgorithmException ex)
			{
				throw new IllegalStateException("SHA-256 is part of every Java platform", ex);
			}
		}
	};

	private final long hi;
	private final long lo;
	/** One 64-bit hash per optional (deferred) field, in layout order; null when the layout has none. */
	private final long[] optionalHashes;
	/** Bit i set: optional field i was left out of this (planned) digest. Always 0 on the stored side. */
	private final int omittedMask;
	private final boolean unknown;

	private RowDigest(final long pHi, final long pLo, final long[] pOptionalHashes, final int pOmittedMask, final boolean pUnknown)
	{
		this.hi = pHi;
		this.lo = pLo;
		this.optionalHashes = pOptionalHashes;
		this.omittedMask = pOmittedMask;
		this.unknown = pUnknown;
	}

	/**
	 * The digest of one row.
	 *
	 * @param pFixed the values of the fields that are always compared, in the fixed order of the plan
	 * @param pOptional the values of the optional (deferred) fields, in the fixed order of the plan, each possibly {@link #OMITTED}; null or empty when there are none
	 * @return the digest, or {@link #UNKNOWN} when a value is of a class {@code sameValue} compares with {@code equals}
	 */
	public static RowDigest of(final Object[] pFixed, final Object[] pOptional)
	{
		final MessageDigest digest = RowDigest.DIGESTS.get();
		digest.reset();
		final Encoder encoder = new Encoder(digest);
		encoder.putInt(pFixed.length);
		for (final Object value : pFixed)
		{
			if (!encoder.value(value))
			{
				return RowDigest.UNKNOWN;
			}
		}
		final byte[] hash = digest.digest();
		long[] optionalHashes = null;
		int omitted = 0;
		if (pOptional != null && pOptional.length > 0)
		{
			if (pOptional.length > RowDigest.MAX_OPTIONAL_FIELDS)
			{
				return RowDigest.UNKNOWN;
			}
			optionalHashes = new long[pOptional.length];
			for (int i = 0; i < pOptional.length; i++)
			{
				if (pOptional[i] == RowDigest.OMITTED)
				{
					omitted |= 1 << i;
					continue;
				}
				digest.reset();
				if (!new Encoder(digest).value(pOptional[i]))
				{
					return RowDigest.UNKNOWN;
				}
				optionalHashes[i] = RowDigest.leading(digest.digest());
			}
		}
		return new RowDigest(RowDigest.leading(hash), RowDigest.long8(hash, 8), optionalHashes, omitted, false);
	}

	/**
	 * Whether a row the load would write with these (planned) values changes nothing on a record holding {@code pStored}:
	 * the fixed fields all equal, and every optional field that is not left out equal. False whenever either side is
	 * {@link #UNKNOWN} - the caller reads the record and decides with {@link StoredValueEquality}.
	 */
	public boolean sameAs(final RowDigest pStored)
	{
		if (this.unknown || pStored == null || pStored.unknown)
		{
			return false;
		}
		if (this.hi != pStored.hi || this.lo != pStored.lo)
		{
			return false;
		}
		if (this.optionalHashes == null)
		{
			return true;
		}
		if (pStored.optionalHashes == null || pStored.optionalHashes.length != this.optionalHashes.length)
		{
			return false;
		}
		for (int i = 0; i < this.optionalHashes.length; i++)
		{
			if ((this.omittedMask & (1 << i)) == 0 && this.optionalHashes[i] != pStored.optionalHashes[i])
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Lot IDX (2026-10-12): the number of {@code long}s {@link #storedWords} writes for a layout with {@code pOptionalCount} deferred fields: the 128 bits, then one
	 * 64-bit hash per deferred field. The compact master index ({@code index.DigestMap}) keeps a stored digest as these words instead of as an object.
	 */
	public static int storedWordCount(final int pOptionalCount)
	{
		return 2 + pOptionalCount;
	}

	/**
	 * Lot IDX: the words of a STORED digest (one built by {@link Layout#stored}), {@code pDst[0..storedWordCount)}. {@link #UNKNOWN}, a digest that lacks the
	 * hashes of {@code pOptionalCount} deferred fields, and a digest whose 128 bits happen to be zero all write ZEROS, which {@link #sameAsWords} reads as "no
	 * digest known" - the safe direction: the row is then read and compared with {@link StoredValueEquality}.
	 */
	public void storedWords(final long[] pDst, final int pOptionalCount)
	{
		java.util.Arrays.fill(pDst, 0, RowDigest.storedWordCount(pOptionalCount), 0L);
		if (this.unknown || (pOptionalCount > 0 && (this.optionalHashes == null || this.optionalHashes.length != pOptionalCount)))
		{
			return;
		}
		if (this.hi == 0L && this.lo == 0L)
		{
			return;
		}
		pDst[0] = this.hi;
		pDst[1] = this.lo;
		for (int i = 0; i < pOptionalCount; i++)
		{
			pDst[2 + i] = this.optionalHashes[i];
		}
	}

	/**
	 * Lot IDX: {@link #sameAs} against a stored digest given as the words {@link #storedWords} wrote. False when this digest is {@link #UNKNOWN} or the stored
	 * words are all-zero ("no digest known").
	 */
	public boolean sameAsWords(final long[] pStoredWords)
	{
		if (this.unknown || pStoredWords == null || pStoredWords.length < 2 || (pStoredWords[0] == 0L && pStoredWords[1] == 0L))
		{
			return false;
		}
		if (this.hi != pStoredWords[0] || this.lo != pStoredWords[1])
		{
			return false;
		}
		if (this.optionalHashes == null)
		{
			return true;
		}
		if (pStoredWords.length < 2 + this.optionalHashes.length)
		{
			return false;
		}
		for (int i = 0; i < this.optionalHashes.length; i++)
		{
			if ((this.omittedMask & (1 << i)) == 0 && this.optionalHashes[i] != pStoredWords[2 + i])
			{
				return false;
			}
		}
		return true;
	}

	/** True for {@link #UNKNOWN} (a value of a class the digest cannot stand for). */
	public boolean isUnknown()
	{
		return this.unknown;
	}

	/**
	 * Lot V3-S2 (2026-10-04): the 128 bits of a digest built with no optional field, as two words {@code {hi, lo}} - what {@code transfer.StagingRowDigest}
	 * keeps per layout in a flat {@code long[]} (32 bytes with the array header, against 40 for this object). Null for {@link #UNKNOWN} and for a digest
	 * that carries optional-field hashes (those words alone would not stand for it).
	 */
	public long[] words()
	{
		if (this.unknown || this.optionalHashes != null)
		{
			return null;
		}
		return new long[] { this.hi, this.lo };
	}

	/**
	 * Which of a plan's fields are always compared and which are optional (deferred foreign keys), in the order of the
	 * plan, and the two ways to build a digest from them: the planned values of a row ({@link #planned}) and the values
	 * a master record holds ({@link #stored}).
	 */
	public static final class Layout
	{
		private final String[] fixed;
		private final String[] optional;

		public Layout(final List<String> pFixedFields, final List<String> pOptionalFields)
		{
			this.fixed = pFixedFields.toArray(new String[pFixedFields.size()]);
			this.optional = pOptionalFields.toArray(new String[pOptionalFields.size()]);
		}

		/** False when the plan has more optional fields than a digest can mark: no digest may be used for that entity. */
		public boolean usable()
		{
			return this.optional.length <= RowDigest.MAX_OPTIONAL_FIELDS;
		}

		public int fixedCount()
		{
			return this.fixed.length;
		}

		public int optionalCount()
		{
			return this.optional.length;
		}

		/** A fixed field absent from {@code pFields} counts as null (a plan always carries them; defensive); an optional field absent is left out of the comparison. */
		public RowDigest planned(final Map<String, Object> pFields)
		{
			final Object[] fixedValues = new Object[this.fixed.length];
			for (int i = 0; i < this.fixed.length; i++)
			{
				fixedValues[i] = pFields.get(this.fixed[i]);
			}
			Object[] optionalValues = null;
			if (this.optional.length > 0)
			{
				optionalValues = new Object[this.optional.length];
				for (int i = 0; i < this.optional.length; i++)
				{
					optionalValues[i] = pFields.containsKey(this.optional[i]) ? pFields.get(this.optional[i]) : RowDigest.OMITTED;
				}
			}
			return RowDigest.of(fixedValues, optionalValues);
		}

		/** The digest of a stored record: {@code pReader} answers the value of a field of the plan by its name. */
		public RowDigest stored(final FieldReader pReader)
		{
			final Object[] fixedValues = new Object[this.fixed.length];
			for (int i = 0; i < this.fixed.length; i++)
			{
				fixedValues[i] = pReader.read(this.fixed[i]);
			}
			Object[] optionalValues = null;
			if (this.optional.length > 0)
			{
				optionalValues = new Object[this.optional.length];
				for (int i = 0; i < this.optional.length; i++)
				{
					optionalValues[i] = pReader.read(this.optional[i]);
				}
			}
			return RowDigest.of(fixedValues, optionalValues);
		}
	}

	/** Reads the stored value of a field of the plan ({@code Adaptation#get} in the loader). */
	public interface FieldReader
	{
		Object read(String pFieldName);
	}

	// ============================================================================================ encoding

	private static long leading(final byte[] pHash)
	{
		return RowDigest.long8(pHash, 0);
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

	/** Same normalisation as {@code StoredValueEquality}: an empty string and an empty list are no value. */
	private static Object normalise(final Object pValue)
	{
		if (pValue instanceof String && ((String) pValue).isEmpty())
		{
			return null;
		}
		if (pValue instanceof List && ((List<?>) pValue).isEmpty())
		{
			return null;
		}
		return pValue;
	}

	/** Same decimal reading as {@code StoredValueEquality#toDecimal}: a BigDecimal as it is, any other Number through its text; null when it has no finite decimal form. */
	private static BigDecimal toDecimal(final Number pValue)
	{
		if (pValue instanceof BigDecimal)
		{
			return (BigDecimal) pValue;
		}
		try
		{
			return new BigDecimal(pValue.toString());
		}
		catch (final NumberFormatException ex)
		{
			return null;
		}
	}

	private static final class Encoder
	{
		private final MessageDigest digest;
		private final byte[] buffer = new byte[8];

		Encoder(final MessageDigest pDigest)
		{
			this.digest = pDigest;
		}

		void putByte(final byte pValue)
		{
			this.digest.update(pValue);
		}

		void putInt(final int pValue)
		{
			for (int i = 0; i < 4; i++)
			{
				this.buffer[i] = (byte) (pValue >>> (24 - 8 * i));
			}
			this.digest.update(this.buffer, 0, 4);
		}

		void putLong(final long pValue)
		{
			for (int i = 0; i < 8; i++)
			{
				this.buffer[i] = (byte) (pValue >>> (56 - 8 * i));
			}
			this.digest.update(this.buffer, 0, 8);
		}

		void putString(final String pValue)
		{
			// the UTF-16 code units themselves, never getBytes("UTF-8"): that replaces an unpaired surrogate with '?', so two different strings could collide
			final int length = pValue.length();
			this.putInt(length);
			final byte[] bytes = new byte[length * 2];
			for (int i = 0; i < length; i++)
			{
				final char c = pValue.charAt(i);
				bytes[2 * i] = (byte) (c >>> 8);
				bytes[2 * i + 1] = (byte) c;
			}
			this.digest.update(bytes);
		}

		/** @return false when the value is of a class the digest cannot stand for */
		boolean value(final Object pRaw)
		{
			final Object value = RowDigest.normalise(pRaw);
			if (value == null)
			{
				this.putByte(RowDigest.TAG_NULL);
				return true;
			}
			if (value instanceof String)
			{
				this.putByte(RowDigest.TAG_STRING);
				this.putString((String) value);
				return true;
			}
			if (value instanceof Number)
			{
				final BigDecimal decimal = RowDigest.toDecimal((Number) value);
				if (decimal == null)
				{
					this.putByte(RowDigest.TAG_NUMBER_OTHER);
					this.putString(value.getClass().getName());
					this.putString(value.toString());
					return true;
				}
				final BigDecimal canonical = decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
				final BigInteger unscaled = canonical.unscaledValue();
				final byte[] bytes = unscaled.toByteArray();
				this.putByte(RowDigest.TAG_NUMBER);
				this.putInt(canonical.scale());
				this.putInt(bytes.length);
				this.digest.update(bytes);
				return true;
			}
			if (value instanceof Date)
			{
				this.putByte(RowDigest.TAG_DATE);
				this.putLong(((Date) value).getTime());
				return true;
			}
			if (value instanceof Boolean)
			{
				this.putByte(RowDigest.TAG_BOOLEAN);
				this.putByte(((Boolean) value).booleanValue() ? (byte) 1 : (byte) 0);
				return true;
			}
			if (value instanceof List)
			{
				final List<?> list = (List<?>) value;
				this.putByte(RowDigest.TAG_LIST);
				this.putInt(list.size());
				for (final Object element : list)
				{
					if (!this.value(element))
					{
						return false;
					}
				}
				return true;
			}
			return false;
		}
	}
}
