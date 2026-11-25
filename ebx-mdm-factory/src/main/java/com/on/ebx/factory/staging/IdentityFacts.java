package com.on.ebx.factory.staging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lot S10 (2026-10-18, staging proposal): the FACTS the staging language model is given about the identity columns of the landing tables - how many
 * distinct values such a column holds, on how many rows a value repeats, and how many values of a table are seen in no other table. Facts only: nothing
 * here decides that a table feeds an entity, and no name is read for its meaning (the columns are the ones the data catalog tagged).
 * <p>
 * <b>Where the vocabulary comes from</b>: the catalog's tag codes ({@code ebx-data-catalog}, {@code catalog-config.json}, family {@code PERSONAL_DATA} and the
 * company identifiers): {@code EMAIL}, {@code PHONE}, {@code PERSON_NAME}, {@code PERSON_IDENTIFIER}, {@code NATIONAL_ID}, {@code NIR},
 * {@code PASSPORT_NUMBER}, {@code DRIVING_LICENCE}, {@code PERSONAL_TAX_ID}, {@code VAT_NUMBER}, {@code LEI}, {@code COMPANY_REGISTRATION}. A column with none of
 * them is not an identity column here, whatever its name says.
 * <p>
 * <b>Counts only, digests in memory</b>: the pass reads a column's values in Java, folds each to a 128-bit digest of its normalised form ({@link Digest}) and
 * keeps the digests, never the values (CLAUDE.md sec 15: one streaming read per table, a 128-bit digest per value). Nothing but counts leaves this class, so
 * the figures are allowed for a sensitive column too (the LLM data policy: counts always go). Pure: no EBX class; {@code ebx.IdentityReader} does the reading.
 */
public final class IdentityFacts
{
	private IdentityFacts()
	{
	}

	/** What kind of identity a tagged column carries: the unit of the cross-table comparison. */
	public enum Kind
	{
		EMAIL("e-mail", "e-mails", true), PHONE("phone", "phones", true), OFFICIAL_ID("official identifier", "official identifiers", true),
		NAME("name", "names", false), PERSON_ID("source-internal identifier", "source-internal identifiers", false);

		private final String singular;
		private final String plural;
		private final boolean comparable;

		Kind(final String pSingular, final String pPlural, final boolean pComparable)
		{
			this.singular = pSingular;
			this.plural = pPlural;
			this.comparable = pComparable;
		}

		public String singular()
		{
			return this.singular;
		}

		public String plural()
		{
			return this.plural;
		}

		/**
		 * Whether a value of this kind written in two sources is the SAME identity: an e-mail, a phone, an official number are; a name is not enough on its own (it is
		 * not compared across tables), nor is a number internal to one source.
		 */
		public boolean comparable()
		{
			return this.comparable;
		}

		/** The kind of a column carrying these catalog tags; null when none of them is an identity tag. The first tag in {@code EMAIL, PHONE, official id, name, internal id} order wins. */
		public static Kind of(final List<String> pTags)
		{
			if (pTags == null || pTags.isEmpty())
			{
				return null;
			}
			Kind best = null;
			for (final String tag : pTags)
			{
				final Kind kind = Kind.ofTag(tag);
				if (kind != null && (best == null || kind.ordinal() < best.ordinal()))
				{
					best = kind;
				}
			}
			return best;
		}

		/** The kind of ONE tag code (case-insensitive); null when it is not an identity tag. */
		public static Kind ofTag(final String pTag)
		{
			if (pTag == null)
			{
				return null;
			}
			switch (pTag.trim().toUpperCase(Locale.ROOT))
			{
				case "EMAIL":
					return Kind.EMAIL;
				case "PHONE":
					return Kind.PHONE;
				case "NATIONAL_ID":
				case "NIR":
				case "PASSPORT_NUMBER":
				case "DRIVING_LICENCE":
				case "PERSONAL_TAX_ID":
				case "VAT_NUMBER":
				case "LEI":
				case "COMPANY_REGISTRATION":
					return Kind.OFFICIAL_ID;
				case "PERSON_NAME":
					return Kind.NAME;
				case "PERSON_IDENTIFIER":
					return Kind.PERSON_ID;
				default:
					return null;
			}
		}

		/** The tag codes of this list that are identity tags, in order, once each (what a sentence quotes as "tags"). */
		public static List<String> identityTags(final List<String> pTags)
		{
			final List<String> out = new ArrayList<String>();
			if (pTags != null)
			{
				for (final String tag : pTags)
				{
					if (Kind.ofTag(tag) != null && !out.contains(tag.trim().toUpperCase(Locale.ROOT)))
					{
						out.add(tag.trim().toUpperCase(Locale.ROOT));
					}
				}
			}
			return out;
		}
	}

	/**
	 * The identity kind of a landing column (lot S10b, 2026-10-19): the kind of its APPLIED catalog tags, else of a catalogue suggestion not rejected - the trigger is the catalogue's own
	 * measurement (a regex on the sampled values, a name hint of its Tag table, its dictionary or model), pending a steward's review or not. Failing both (lot F2, 2026-10-19), the kind
	 * of the identity SHAPE measured on the column's values ({@link #shapeKindOf}) - a measure of how the values look, labelled as such wherever it is shown. Null when none.
	 */
	public static Kind kindOf(final StagingRules.LandingColumnInfo pColumn)
	{
		final Kind tagged = IdentityFacts.tagKindOf(pColumn);
		return tagged != null ? tagged : IdentityFacts.shapeKindOf(pColumn);
	}

	/** The identity kind the data catalogue gives a column (applied tags, else suggestions not rejected); null when it gives none. */
	public static Kind tagKindOf(final StagingRules.LandingColumnInfo pColumn)
	{
		Kind best = Kind.of(pColumn.tags);
		for (final StagingRules.TagSuggestionInfo suggestion : pColumn.suggestions)
		{
			final Kind kind = suggestion.rejected() ? null : Kind.ofTag(suggestion.tag);
			if (kind != null && (best == null || kind.ordinal() < best.ordinal()))
			{
				best = kind;
			}
		}
		return best;
	}

	/**
	 * Lot F2: the identity kind of the SHAPE measured on a column's values ({@link IdentityShapes}); null when no shape was measured or when a steward rejected a catalogue suggestion of that
	 * very kind for the column (a person said it is not that: the shape does not overrule them). It matters only for a column {@link #tagKindOf} gives nothing for.
	 */
	public static Kind shapeKindOf(final StagingRules.LandingColumnInfo pColumn)
	{
		if (pColumn.shapeIdentity == null)
		{
			return null;
		}
		for (final StagingRules.TagSuggestionInfo suggestion : pColumn.suggestions)
		{
			if (suggestion.rejected() && Kind.ofTag(suggestion.tag) == pColumn.shapeIdentity.kind)
			{
				return null;
			}
		}
		return pColumn.shapeIdentity.kind;
	}

	/** True when the column is identity-bearing ONLY by its measured value shape: the catalogue gave it no identity tag and no live suggestion. */
	public static boolean shapeOnly(final StagingRules.LandingColumnInfo pColumn)
	{
		return IdentityFacts.tagKindOf(pColumn) == null && IdentityFacts.shapeKindOf(pColumn) != null;
	}

	/** {@code e-mail-shaped in 98% of sampled values}: the measure of a {@link #shapeOnly} column; null for another column. */
	public static String shapeWords(final StagingRules.LandingColumnInfo pColumn)
	{
		return IdentityFacts.shapeOnly(pColumn) ? pColumn.shapeIdentity.words() : null;
	}

	/**
	 * How the identity kind of a column is known, one entry per identity tag, applied ones first: {@code EMAIL (regex matched 100% of sampled values, applied)}, {@code PHONE (suggested by the
	 * catalogue from its sampled values, pending review)}, {@code PERSON_NAME (applied)}. What the follow-up turn and the review say, so that the model and the steward know how firm the fact is.
	 */
	public static List<String> tagWords(final StagingRules.LandingColumnInfo pColumn)
	{
		final List<String> out = new ArrayList<String>();
		final List<String> seen = new ArrayList<String>();
		for (final String tag : pColumn.tags)
		{
			final String code = tag == null ? "" : tag.trim().toUpperCase(Locale.ROOT);
			if (Kind.ofTag(code) == null || seen.contains(code))
			{
				continue;
			}
			seen.add(code);
			StagingRules.TagSuggestionInfo found = null;
			for (final StagingRules.TagSuggestionInfo suggestion : pColumn.suggestions)
			{
				if (suggestion.tag.equals(code) && suggestion.applied())
				{
					found = suggestion;
				}
			}
			out.add(code + " (" + (found == null ? "applied" : found.words()) + ")");
		}
		for (final StagingRules.TagSuggestionInfo suggestion : pColumn.suggestions)
		{
			if (Kind.ofTag(suggestion.tag) == null || suggestion.rejected() || seen.contains(suggestion.tag))
			{
				continue;
			}
			seen.add(suggestion.tag);
			out.add(suggestion.tag + " (" + suggestion.basisWords() + ")");
		}
		return out;
	}

	/**
	 * The firmness of the identity of a column: {@code APPLIED} when an identity tag is applied, else the {@link StagingRules.TagSuggestionInfo#basis} of the first identity suggestion, else
	 * {@code SHAPES} (lot F2: measured from the value shapes, no catalogue tag); null for none.
	 */
	public static String basisOf(final StagingRules.LandingColumnInfo pColumn)
	{
		if (Kind.of(pColumn.tags) != null)
		{
			return "APPLIED";
		}
		for (final StagingRules.TagSuggestionInfo suggestion : pColumn.suggestions)
		{
			if (Kind.ofTag(suggestion.tag) != null && !suggestion.rejected())
			{
				return suggestion.basis();
			}
		}
		return IdentityFacts.shapeOnly(pColumn) ? IdentityFacts.BASIS_SHAPES : null;
	}

	/** The {@link #basisOf} of a column identity-bearing only by its measured value shape (lot F2). */
	public static final String BASIS_SHAPES = "SHAPES";

	/** How many digits of a phone number are compared: the last nine bridge a national form (06 12 34 56 78) and an international one (+33 6 12 34 56 78). */
	static final int PHONE_COMPARED_DIGITS = 9;
	/** A phone with fewer digits than this is compared as written (it is not a number). */
	static final int PHONE_MIN_DIGITS = 6;

	/**
	 * The form a value is compared in, per kind: e-mail trimmed and lower-cased; phone reduced to its last {@value #PHONE_COMPARED_DIGITS} digits; official identifier
	 * without spaces, dots or dashes, upper-cased; anything else trimmed, lower-cased, inner spaces collapsed. Null for a blank value.
	 */
	public static String normalise(final Kind pKind, final String pRaw)
	{
		if (pRaw == null)
		{
			return null;
		}
		final String trimmed = pRaw.trim();
		if (trimmed.isEmpty())
		{
			return null;
		}
		switch (pKind)
		{
			case EMAIL:
				return trimmed.toLowerCase(Locale.ROOT);
			case PHONE:
			{
				final StringBuilder digits = new StringBuilder();
				for (int i = 0; i < trimmed.length(); i++)
				{
					if (Character.isDigit(trimmed.charAt(i)))
					{
						digits.append(trimmed.charAt(i));
					}
				}
				if (digits.length() < IdentityFacts.PHONE_MIN_DIGITS)
				{
					return trimmed.toLowerCase(Locale.ROOT);
				}
				return digits.length() > IdentityFacts.PHONE_COMPARED_DIGITS ? digits.substring(digits.length() - IdentityFacts.PHONE_COMPARED_DIGITS) : digits.toString();
			}
			case OFFICIAL_ID:
			{
				final StringBuilder kept = new StringBuilder();
				for (int i = 0; i < trimmed.length(); i++)
				{
					if (Character.isLetterOrDigit(trimmed.charAt(i)))
					{
						kept.append(trimmed.charAt(i));
					}
				}
				return kept.length() == 0 ? trimmed.toUpperCase(Locale.ROOT) : kept.toString().toUpperCase(Locale.ROOT);
			}
			default:
				return trimmed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		}
	}

	/** A 128-bit digest of a normalised value: all that is kept of it. */
	public static final class Digest
	{
		private final long high;
		private final long low;

		private Digest(final long pHigh, final long pLow)
		{
			this.high = pHigh;
			this.low = pLow;
		}

		/** MD5 of the UTF-8 bytes (a fingerprint, not a protection: the value is never recovered from it nor shown). */
		public static Digest of(final String pNormalised)
		{
			try
			{
				final byte[] bytes = MessageDigest.getInstance("MD5").digest(pNormalised.getBytes(StandardCharsets.UTF_8));
				long high = 0L;
				long low = 0L;
				for (int i = 0; i < 8; i++)
				{
					high = (high << 8) | (bytes[i] & 0xFFL);
					low = (low << 8) | (bytes[8 + i] & 0xFFL);
				}
				return new Digest(high, low);
			}
			catch (final NoSuchAlgorithmException ex)
			{
				throw new IllegalStateException("MD5 is a mandatory JDK algorithm", ex);
			}
		}

		@Override
		public boolean equals(final Object pOther)
		{
			return pOther instanceof Digest && ((Digest) pOther).high == this.high && ((Digest) pOther).low == this.low;
		}

		@Override
		public int hashCode()
		{
			return Long.hashCode(this.high * 31L + this.low);
		}
	}

	/** The figures of ONE identity column, measured on the rows the read covered. */
	public static final class Column
	{
		public final Kind kind;
		public final long filledRows;
		public final long distinctValues;
		/** Rows whose value is also the value of at least one other row of the same column. */
		public final long repeatedRows;
		/** 0 when the whole table was read; otherwise the rows read (a sample of the first rows in key order, {@code quality.ScanGuard}). */
		public final long sampledRows;

		public Column(final Kind pKind, final long pFilledRows, final long pDistinctValues, final long pRepeatedRows, final long pSampledRows)
		{
			this.kind = pKind;
			this.filledRows = pFilledRows;
			this.distinctValues = pDistinctValues;
			this.repeatedRows = pRepeatedRows;
			this.sampledRows = pSampledRows;
		}

		/** {@code 198 distinct values over 230 filled rows, repeated on 46 rows} (+ {@code , in a sample of 50,000 rows}); the words the inventory carries beside the column. */
		public String inventoryText()
		{
			if (this.filledRows == 0L)
			{
				return "no filled row" + this.sampleWords();
			}
			return this.distinctValues + " distinct value" + (this.distinctValues == 1L ? "" : "s") + " over " + this.filledRows + " filled row" + (this.filledRows == 1L ? "" : "s")
				+ ", repeated on " + this.repeatedRows + " row" + (this.repeatedRows == 1L ? "" : "s") + this.sampleWords();
		}

		private String sampleWords()
		{
			return this.sampledRows > 0L ? " (measured on a sample of " + String.format(Locale.ROOT, "%,d", Long.valueOf(this.sampledRows)) + " rows)" : "";
		}
	}

	/** The figures of one landing table: its identity columns, how many rows carry an identity value, and (after {@link #crossTable}) the values seen nowhere else. */
	public static final class Table
	{
		/** Rows read (the table's rows, or the sample's). */
		public final long rowsRead;
		/** Rows where at least one identity column is filled. */
		public final long rowsWithIdentity;
		/** True when only the first rows were read ({@code quality.ScanGuard}): no value digests are kept then. */
		public final boolean sampled;
		/** Identity column (landing field name) -> its figures, in table order. */
		public final Map<String, Column> columns;
		private final Map<Kind, Set<Digest>> valuesByKind;
		/** Kind -> distinct values of this table seen in no other table (only the {@link Kind#comparable} kinds some other table also carries; filled by {@link #crossTable}). */
		public final Map<Kind, Long> seenNowhereElse = new EnumMap<Kind, Long>(Kind.class);
		/** True when {@link #crossTable} could not count a kind because this table or another one carrying it was read on a sample. */
		public boolean crossSkipped;

		Table(final long pRowsRead, final long pRowsWithIdentity, final boolean pSampled, final Map<String, Column> pColumns, final Map<Kind, Set<Digest>> pValuesByKind)
		{
			this.rowsRead = pRowsRead;
			this.rowsWithIdentity = pRowsWithIdentity;
			this.sampled = pSampled;
			this.columns = pColumns;
			this.valuesByKind = pValuesByKind;
		}

		/** The kinds this table carries, in {@link Kind} order. */
		public List<Kind> kinds()
		{
			final List<Kind> out = new ArrayList<Kind>();
			for (final Kind kind : Kind.values())
			{
				for (final Column column : this.columns.values())
				{
					if (column.kind == kind)
					{
						out.add(kind);
						break;
					}
				}
			}
			return out;
		}

		/** Entries held in memory (the weight a cache charges for this table). */
		public long weight()
		{
			long weight = 1L;
			for (final Set<Digest> values : this.valuesByKind.values())
			{
				weight += values.size();
			}
			return weight;
		}
	}

	/** Collects the rows of ONE table in a single pass: {@link #row} per row, {@link #finish} at the end. */
	public static final class Accumulator
	{
		private final List<String> fields;
		private final List<Kind> kinds;
		private final boolean sampled;
		private final List<Map<Digest, Integer>> counts = new ArrayList<Map<Digest, Integer>>();
		private final long[] filled;
		private final Map<Kind, Set<Digest>> valuesByKind = new EnumMap<Kind, Set<Digest>>(Kind.class);
		private long rows;
		private long rowsWithIdentity;

		/**
		 * @param pFields the landing field names of the identity columns, in the order {@link #row} receives their values
		 * @param pKinds the kind of each of them
		 * @param pSampled whether only the first rows are read: the digests are then not kept for the cross-table comparison
		 */
		public Accumulator(final List<String> pFields, final List<Kind> pKinds, final boolean pSampled)
		{
			this.fields = pFields;
			this.kinds = pKinds;
			this.sampled = pSampled;
			this.filled = new long[pFields.size()];
			for (int i = 0; i < pFields.size(); i++)
			{
				this.counts.add(new HashMap<Digest, Integer>());
				if (!pSampled && !this.valuesByKind.containsKey(pKinds.get(i)))
				{
					this.valuesByKind.put(pKinds.get(i), new HashSet<Digest>());
				}
			}
		}

		/** One row: the raw value of each identity column (null or blank = not filled). */
		public void row(final String[] pValues)
		{
			this.rows++;
			boolean any = false;
			for (int i = 0; i < this.fields.size(); i++)
			{
				final String normalised = IdentityFacts.normalise(this.kinds.get(i), pValues[i]);
				if (normalised == null)
				{
					continue;
				}
				any = true;
				this.filled[i]++;
				final Digest digest = Digest.of(normalised);
				final Map<Digest, Integer> perColumn = this.counts.get(i);
				final Integer seen = perColumn.get(digest);
				perColumn.put(digest, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
				if (!this.sampled)
				{
					this.valuesByKind.get(this.kinds.get(i)).add(digest);
				}
			}
			if (any)
			{
				this.rowsWithIdentity++;
			}
		}

		public Table finish()
		{
			final Map<String, Column> columns = new LinkedHashMap<String, Column>();
			for (int i = 0; i < this.fields.size(); i++)
			{
				long repeated = 0L;
				for (final Integer count : this.counts.get(i).values())
				{
					if (count.intValue() > 1)
					{
						repeated += count.intValue();
					}
				}
				columns.put(this.fields.get(i), new Column(this.kinds.get(i), this.filled[i], this.counts.get(i).size(), repeated, this.sampled ? this.rows : 0L));
			}
			return new Table(this.rows, this.rowsWithIdentity, this.sampled, columns, this.valuesByKind);
		}
	}

	/**
	 * Fills {@link Table#seenNowhereElse} of every table: for each {@link Kind#comparable} kind a table carries AND at least one other table also carries, the
	 * distinct values of the table that no other table holds in a column of that kind. Not counted (and {@link Table#crossSkipped} set) when the table or any other
	 * table carrying the kind was read on a sample - a figure drawn from a sample would call a value "seen nowhere else" because the row that holds it was not read.
	 *
	 * @param pTables the measured tables by landing table key (a table without identity column has no entry)
	 */
	public static void crossTable(final Map<String, Table> pTables)
	{
		for (final Map.Entry<String, Table> entry : pTables.entrySet())
		{
			final Table table = entry.getValue();
			table.seenNowhereElse.clear();
			table.crossSkipped = false;
			for (final Kind kind : table.kinds())
			{
				if (!kind.comparable())
				{
					continue;
				}
				final List<Table> others = new ArrayList<Table>();
				boolean otherSampled = false;
				for (final Map.Entry<String, Table> other : pTables.entrySet())
				{
					if (other.getKey().equals(entry.getKey()) || !other.getValue().kinds().contains(kind))
					{
						continue;
					}
					others.add(other.getValue());
					otherSampled |= other.getValue().sampled;
				}
				if (others.isEmpty())
				{
					continue; // nothing to compare with: no figure
				}
				if (table.sampled || otherSampled)
				{
					table.crossSkipped = true;
					continue;
				}
				long only = 0L;
				for (final Digest value : table.valuesByKind.get(kind))
				{
					boolean elsewhere = false;
					for (final Table other : others)
					{
						if (other.valuesByKind.get(kind).contains(value))
						{
							elsewhere = true;
							break;
						}
					}
					if (!elsewhere)
					{
						only++;
					}
				}
				table.seenNowhereElse.put(kind, Long.valueOf(only));
			}
		}
	}
}
