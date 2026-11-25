package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.on.ebx.factory.quality.Shapes;

/**
 * B2B test (2026-10-23, project E2EB2B20261023081141, docs/TARGET-MAPPING.md sec 23): the facts about a landing column that only a ROW can show - how its value relates to
 * the other columns of the same row and to the keys of the other sources - measured, and given to the staging language model with the rule to apply. The live B2B run
 * proposed, for want of them: a SIREN attribute fed with a column that holds a SIREN for French partners and a VAT number for the others; a SIRET attribute fed with the
 * Salesforce column that holds SAP Business Partner numbers; the name fed with the second half of a name SAP cuts at 40 characters; a VAT number kept with its inner spaces
 * in one source and compact in another; and the preflight took the partner number repeated in a sub-record for a phone number.
 * <p>
 * <b>What is measured</b> (one streaming pass per landing table, {@code ebx.RowFactsReader}; this class holds no EBX type and is tested on the B2B values):
 * <ol>
 * <li><i>Shape mix</i> ({@link Column#mixedIdentifier}): an identifier-like column (letters and digits only, a digit in most values) whose values are digits only on some rows
 * and carry letters on others; and, when a short list of codes of the SAME row explains which rows are which ({@link Column#companion}), the shapes per code.</li>
 * <li><i>Keys of another source</i> ({@link Column#keysOfTable}): how many filled values of the column are, exactly after a trim, the key of a row of a landing table of
 * ANOTHER source system.</li>
 * <li><i>Continuation pair</i> ({@link Column#continues}): column B is filled only on rows where column A reaches its maximum length L (or L - 1, the space the source drops at
 * the cut), and on at least half of those rows, while at most half of A's rows reach it.</li>
 * <li><i>Own key</i> ({@link Column#equalsOwnKey}): the value equals the row's own key (a sub-record that repeats the record's number).</li>
 * <li><i>Separators in identifiers</i> ({@link Column#separated}): identifier-like values written with inner spaces or separators on some rows and compact on others, and
 * the compact shape other sources write it in.</li>
 * </ol>
 * <b>Never a decision</b>: no column name is read, no attribute is chosen, no step is added. A fact appears only when its measure fires, worded as a count with the rule it
 * calls for ({@link #columnText}); the model proposes, a person decides. Values never leave this class except the codes of a companion column, and only when the data policy
 * shows real values; shapes ({@link Shapes}) only for a column whose shapes may be shown.
 */
public final class RowFacts
{
	/** A fact never fires on fewer rows than this. */
	public static final int MIN_ROWS = 3;
	/** Rows kept per table for the row-level measures; past it the figures say they are measured on the first rows. */
	public static final int ROW_CAP = 20000;
	/** A companion column holds at most this many distinct codes. */
	public static final int COMPANION_MAX_CODES = 12;
	/** Share (percent) of the filled values a measure must cover to describe the column (identifier-like, own key, keys of another source). */
	public static final int SHARE_PERCENT = 80;
	/** Shapes shown per breakdown. */
	private static final int SHAPES_SHOWN = 4;
	/** A column shorter than this is never read as the first half of a value cut at a fixed width (codes, flags). */
	private static final int MIN_CUT_WIDTH = 10;

	/** Letters and digits with the separators an identifier is often written with; at least one digit. */
	private static final Pattern IDENTIFIER_CHARS = Pattern.compile("^[\\p{L}0-9][\\p{L}0-9 .\\-/]*$");
	/**
	 * The compact form of an identifier-like value: a short letter prefix then a digit, or a first digit other than 0 - a national phone number starts with 0 and an
	 * international one with + or 00, so neither is identifier-like here.
	 */
	private static final Pattern IDENTIFIER_COMPACT = Pattern.compile("^(?:\\p{L}{1,4}[0-9][\\p{L}0-9]{3,}|[1-9][\\p{L}0-9]{4,})$");

	private RowFacts()
	{
	}

	// ------------------------------------------------------------------------------------------------------------- results

	/** What was measured on one column. Counts, shapes and (for a companion) codes; never a value of the column itself. */
	public static final class Column
	{
		public final String field;
		public long filled;

		// 1. shape mix
		/** Shape ({@link Shapes#of}) to rows, for an identifier-like column; empty otherwise. */
		public final Map<String, Long> shapes = new LinkedHashMap<String, Long>();
		public long digitsOnlyRows;
		public long withLettersRows;
		/** The code column of the same row whose codes separate the digits-only rows from the others; null when none does. */
		public String companion;
		/** Companion code to (shape to rows), in the order the codes were met. */
		public final Map<String, Map<String, Long>> shapesByCode = new LinkedHashMap<String, Map<String, Long>>();

		// 2. keys of another source
		/** {@code <sourceSystem>.<table>} whose keys the values are; null when none. */
		public String keysOfTable;
		public String keysOfKeyField;
		public long keyRows;
		public boolean keysOfSampled;

		// 3. continuation pair (on the SECOND column of the pair)
		/** The column this one continues (its first half); null when it continues none. */
		public String continues;
		public int cutWidth;
		/** Rows where the first half reaches its maximum length (or one less). */
		public long cutRows;
		/** On the FIRST column of a pair: the column that continues it; null otherwise. */
		public String continuedBy;

		// 4. own key
		public String ownKeyField;
		public long equalsOwnKey;

		// 5. separators
		public long identifierRows;
		public long separated;
		public long compact;
		/** Shapes of the values written with separators, to rows. */
		public final Map<String, Long> separatedShapes = new LinkedHashMap<String, Long>();
		/** Shapes of the compact form of every identifier-like value (separators removed, upper case), to rows. */
		public final Map<String, Long> compactShapes = new LinkedHashMap<String, Long>();
		/** {@code <sourceSystem>.<table>.<column>} of another source that writes the same compact shape with no separator; null when none. */
		public String compactElsewhere;

		/** Value to rows, kept until the cross-table pass (null past {@link #ROW_CAP} distinct values). */
		Map<String, Long> values = new LinkedHashMap<String, Long>();

		Column(final String pField)
		{
			this.field = pField;
		}

		/** The column holds identifier-like values written digits-only on some rows and with letters on others, each on enough rows to matter. */
		public boolean mixedIdentifier()
		{
			return this.digitsOnlyRows >= MIN_ROWS && this.withLettersRows >= MIN_ROWS && this.identifierRows * 100L >= this.filled * SHARE_PERCENT
				&& Math.min(this.digitsOnlyRows, this.withLettersRows) * 20L >= this.filled;
		}

		/** Most filled values are keys of a table of another source. */
		public boolean holdsKeysOfAnotherSource()
		{
			return this.keysOfTable != null && this.keyRows >= MIN_ROWS && this.keyRows * 100L >= this.filled * SHARE_PERCENT;
		}

		/** Most filled values repeat the row's own key: an identifier of the row. */
		public boolean repeatsOwnKey()
		{
			return this.ownKeyField != null && this.equalsOwnKey >= MIN_ROWS && this.equalsOwnKey * 100L >= this.filled * SHARE_PERCENT;
		}

		/** Identifier-like values written with separators on enough rows (compared before {@link RowFacts#crossTable} sets {@link #compactElsewhere}). */
		boolean writesSeparators()
		{
			return this.separated >= MIN_ROWS && this.identifierRows * 100L >= this.filled * SHARE_PERCENT;
		}

		/**
		 * The same identifiers written two ways: with separators on enough rows, and compact on enough rows of this column or in another source. A column written with
		 * separators everywhere and nowhere else compact (an identifier format of its own, a UUID) says nothing.
		 */
		public boolean separatorsInIdentifiers()
		{
			return this.writesSeparators() && (this.compact >= MIN_ROWS || this.compactElsewhere != null);
		}

		/** True for a column a business attribute should never be fed from as if it were business data: it repeats the row's key or holds another source's keys. */
		public boolean isIdentifierOfARow()
		{
			return this.repeatsOwnKey() || this.holdsKeysOfAnotherSource();
		}
	}

	/** One landing table measured. */
	public static final class Table
	{
		public final String sourceSystem;
		public final String name;
		/** The key field when the table has exactly one; null otherwise (no own-key or cross-source measure). */
		public final String keyField;
		public final Map<String, Column> columns = new LinkedHashMap<String, Column>();
		public long rowsRead;
		/** Only the first rows were read (the volume guard, or {@link #ROW_CAP}). */
		public boolean sampled;
		/** The key values of every row read, trimmed; empty without a single key. */
		final Set<String> keys = new HashSet<String>();

		Table(final String pSourceSystem, final String pName, final String pKeyField)
		{
			this.sourceSystem = pSourceSystem;
			this.name = pName;
			this.keyField = pKeyField;
		}

		public String key()
		{
			return this.sourceSystem + "." + this.name;
		}

		public Column column(final String pField)
		{
			return this.columns.get(pField);
		}
	}

	// ---------------------------------------------------------------------------------------------------------- measure

	/** Collects the rows of ONE landing table, then measures them ({@link #finish}). */
	public static final class Accumulator
	{
		private final Table table;
		private final List<String> fields;
		private final int keyIndex;
		private final List<String[]> rows = new ArrayList<String[]>();

		/**
		 * @param pFields the text columns read, in the order {@link #row} receives their values (the key field among them when there is one)
		 * @param pKeyField the table's single key field, or null
		 * @param pSampled the reader reads only the first rows of the table (the volume guard)
		 */
		public Accumulator(final String pSourceSystem, final String pTableName, final List<String> pFields, final String pKeyField, final boolean pSampled)
		{
			this.table = new Table(pSourceSystem, pTableName, pKeyField != null && pFields.contains(pKeyField) ? pKeyField : null);
			this.table.sampled = pSampled;
			this.fields = new ArrayList<String>(pFields);
			this.keyIndex = this.table.keyField == null ? -1 : this.fields.indexOf(this.table.keyField);
		}

		/** One row: the raw value of each field (null or blank = not filled). */
		public void row(final String[] pValues)
		{
			this.table.rowsRead++;
			if (this.keyIndex >= 0)
			{
				final String key = RowFacts.trimmed(pValues[this.keyIndex]);
				if (key != null)
				{
					this.table.keys.add(key);
				}
			}
			if (this.rows.size() < ROW_CAP)
			{
				final String[] copy = new String[this.fields.size()];
				for (int i = 0; i < copy.length; i++)
				{
					copy[i] = RowFacts.trimmed(i < pValues.length ? pValues[i] : null);
				}
				this.rows.add(copy);
			}
			else
			{
				this.table.sampled = true;
			}
		}

		/** Measures the rows kept; the cross-source measures wait for {@link RowFacts#crossTable}. */
		public Table finish()
		{
			final int width = this.fields.size();
			final int[] maxLength = new int[width];
			for (int c = 0; c < width; c++)
			{
				final String field = this.fields.get(c);
				final Column column = new Column(field);
				this.table.columns.put(field, column);
				if (c == this.keyIndex)
				{
					column.values = null; // the key itself is the table's identity, not a fact about a column
				}
				for (final String[] row : this.rows)
				{
					final String value = row[c];
					if (value == null)
					{
						continue;
					}
					column.filled++;
					maxLength[c] = Math.max(maxLength[c], value.length());
					if (column.values != null)
					{
						RowFacts.add(column.values, value, 1L);
						if (column.values.size() > ROW_CAP)
						{
							column.values = null;
						}
					}
					if (this.keyIndex >= 0 && c != this.keyIndex && value.equals(row[this.keyIndex]))
					{
						column.equalsOwnKey++;
					}
					RowFacts.addIdentifierShape(column, value);
				}
				if (this.keyIndex >= 0 && c != this.keyIndex)
				{
					column.ownKeyField = this.table.keyField;
				}
			}
			for (int c = 0; c < width; c++)
			{
				final Column column = this.table.columns.get(this.fields.get(c));
				if (column.mixedIdentifier())
				{
					this.companion(c, column);
				}
			}
			this.continuations(maxLength);
			this.rows.clear();
			return this.table;
		}

		/** The code column of the same row that separates the digits-only rows of {@code pColumn} from the others, if one does (fewest codes wins). */
		private void companion(final int pIndex, final Column pColumn)
		{
			String best = null;
			int bestDistance = Integer.MAX_VALUE;
			Map<String, Map<String, Long>> bestShapes = null;
			for (int c = 0; c < this.fields.size(); c++)
			{
				if (c == pIndex || c == this.keyIndex)
				{
					continue;
				}
				final Map<String, Map<String, Long>> byCode = new LinkedHashMap<String, Map<String, Long>>();
				final Map<String, long[]> kinds = new LinkedHashMap<String, long[]>();
				boolean usable = true;
				for (final String[] row : this.rows)
				{
					final String value = row[pIndex];
					if (value == null || !RowFacts.identifierLike(value))
					{
						continue;
					}
					final String code = row[c];
					if (code == null)
					{
						usable = false;
						break;
					}
					Map<String, Long> shapes = byCode.get(code);
					if (shapes == null)
					{
						if (byCode.size() >= COMPANION_MAX_CODES)
						{
							usable = false;
							break;
						}
						shapes = new LinkedHashMap<String, Long>();
						byCode.put(code, shapes);
						kinds.put(code, new long[2]);
					}
					RowFacts.add(shapes, Shapes.of(value), 1L);
					kinds.get(code)[RowFacts.digitsOnly(value) ? 0 : 1]++;
				}
				if (!usable || byCode.size() < 2)
				{
					continue;
				}
				// the codes explain the shapes when every code holds one kind only (digits only, or with letters)
				boolean pure = true;
				for (final long[] kind : kinds.values())
				{
					if (kind[0] > 0L && kind[1] > 0L)
					{
						pure = false;
						break;
					}
				}
				// fewest codes first; between two as short, the nearer column (a flattened sub-record keeps its fields side by side)
				final int distance = Math.abs(c - pIndex);
				if (pure && (bestShapes == null || byCode.size() < bestShapes.size() || byCode.size() == bestShapes.size() && distance < bestDistance))
				{
					best = this.fields.get(c);
					bestShapes = byCode;
					bestDistance = distance;
				}
			}
			if (best != null)
			{
				pColumn.companion = best;
				pColumn.shapesByCode.putAll(bestShapes);
			}
		}

		/** Pairs (A, B) where B is filled only on rows where A reaches its maximum length L or L - 1, and on at least half of them. */
		private void continuations(final int[] pMaxLength)
		{
			final int width = this.fields.size();
			for (int a = 0; a < width; a++)
			{
				final int cut = pMaxLength[a];
				if (a == this.keyIndex || cut < MIN_CUT_WIDTH)
				{
					continue;
				}
				for (int b = 0; b < width; b++)
				{
					if (b == a || b == this.keyIndex)
					{
						continue;
					}
					long atCut = 0L;
					long both = 0L;
					boolean outside = false;
					for (final String[] row : this.rows)
					{
						final boolean full = row[a] != null && row[a].length() >= cut - 1;
						if (full)
						{
							atCut++;
						}
						if (row[b] != null)
						{
							if (!full)
							{
								outside = true;
								break;
							}
							both++;
						}
					}
					// the cut is a property of SOME values: at most half of the first column's rows reach it (a column of one length everywhere is no cut value)
					final long firstFilled = this.table.columns.get(this.fields.get(a)).filled;
					if (!outside && both >= MIN_ROWS && both * 2L >= atCut && atCut * 2L <= firstFilled)
					{
						final Column second = this.table.columns.get(this.fields.get(b));
						final Column first = this.table.columns.get(this.fields.get(a));
						if (second.continues == null && first.continues == null)
						{
							second.continues = first.field;
							second.cutWidth = cut;
							second.cutRows = atCut;
							first.continuedBy = second.field;
							first.cutWidth = cut;
							first.cutRows = atCut;
						}
					}
				}
			}
		}
	}

	/**
	 * The measures between tables: for every column of every table, whether its values are the keys of a table of ANOTHER source system (the one holding most of them), and,
	 * for a column whose identifier-like values carry separators, a column of another source that writes the same compact shape with none. Frees the values kept.
	 */
	public static void crossTable(final Map<String, Table> pTables)
	{
		for (final Table table : pTables.values())
		{
			for (final Column column : table.columns.values())
			{
				if (column.values != null && column.filled >= MIN_ROWS)
				{
					for (final Table other : pTables.values())
					{
						if (other == table || other.sourceSystem == null || other.sourceSystem.equals(table.sourceSystem) || other.keys.isEmpty())
						{
							continue;
						}
						long rows = 0L;
						for (final Map.Entry<String, Long> value : column.values.entrySet())
						{
							if (other.keys.contains(value.getKey()))
							{
								rows += value.getValue().longValue();
							}
						}
						if (rows > column.keyRows)
						{
							column.keyRows = rows;
							column.keysOfTable = other.key();
							column.keysOfKeyField = other.keyField;
							column.keysOfSampled = other.sampled;
						}
					}
				}
				if (column.writesSeparators())
				{
					column.compactElsewhere = RowFacts.compactElsewhere(pTables, table, column);
				}
			}
		}
		for (final Table table : pTables.values())
		{
			for (final Column column : table.columns.values())
			{
				column.values = null;
			}
		}
	}

	private static String compactElsewhere(final Map<String, Table> pTables, final Table pTable, final Column pColumn)
	{
		final String shape = RowFacts.top(pColumn.compactShapes);
		if (shape == null)
		{
			return null;
		}
		for (final Table other : pTables.values())
		{
			if (other == pTable || other.sourceSystem == null || other.sourceSystem.equals(pTable.sourceSystem))
			{
				continue;
			}
			for (final Column column : other.columns.values())
			{
				final Long rows = column.compactShapes.get(shape);
				if (rows != null && rows.longValue() >= MIN_ROWS && column.separated == 0L && column.identifierRows * 100L >= column.filled * SHARE_PERCENT)
				{
					return other.key() + "." + column.field;
				}
			}
		}
		return null;
	}

	// ----------------------------------------------------------------------------------------------------------- render

	/**
	 * What the model reads next to the column ({@code ", row facts: ..."}), empty when no measure fires. Each fact is a count followed by the rule it calls for.
	 *
	 * @param pShowShapes the value shapes of this column may be shown (not for a sensitive column, not under data policy NONE)
	 * @param pShowValues real values may be shown (data policy SAMPLE_VALUES): only the codes of a companion column are concerned
	 */
	public static String columnText(final Table pTable, final String pField, final boolean pShowShapes, final boolean pShowValues)
	{
		final Column column = pTable == null ? null : pTable.column(pField);
		if (column == null || column.filled == 0L || pField.equals(pTable.keyField))
		{
			return ""; // the key is the table's identity, already given as such
		}
		final List<String> facts = new ArrayList<String>();
		final String on = pTable.sampled ? " (measured on the first " + Math.min(pTable.rowsRead, ROW_CAP) + " rows)" : "";
		if (column.repeatsOwnKey())
		{
			facts.add("equals the row's own key " + column.ownKeyField + " on " + column.equalsOwnKey + " of " + column.filled + " filled rows" + on
				+ ": an identifier of the row repeated in a sub-record, not a business value - never map it to a business attribute (not a phone, not a number of the business)");
		}
		if (column.holdsKeysOfAnotherSource())
		{
			facts.add((column.keysOfSampled ? "at least " : "") + column.keyRows + " of " + column.filled + " filled values are keys of " + column.keysOfTable
				+ (column.keysOfKeyField == null ? "" : " (its key " + column.keysOfKeyField + ")") + on
				+ ": a column whose values are another source's keys is a cross-reference to that source - keep it as its own attribute (a reference to that source, named after it) "
				+ "or leave it out with a note; never map it to a business attribute");
		}
		if (column.continues != null)
		{
			facts.add("filled on " + column.filled + " rows, every one a row where " + column.continues + " reaches its maximum length " + column.cutWidth + " (or "
				+ (column.cutWidth - 1) + ", the space dropped at the cut; " + column.cutRows + " such rows), empty on all the others" + on
				+ ": the continuation of a value its source cut at " + column.cutWidth + " characters - join the pair into ONE attribute with joinwidth(" + column.cutWidth + ","
				+ column.continues + "," + column.field + "), never map one half alone");
		}
		else if (column.continuedBy != null)
		{
			facts.add("reaches its maximum length " + column.cutWidth + " (or " + (column.cutWidth - 1) + ") on " + column.cutRows + " rows, continued by " + column.continuedBy
				+ " on those rows: a value cut at " + column.cutWidth + " characters - feed the attribute with joinwidth(" + column.cutWidth + "," + column.field + ","
				+ column.continuedBy + "), never this half alone");
		}
		if (column.mixedIdentifier())
		{
			final StringBuilder text = new StringBuilder("holds several identifier shapes: digits only on ").append(column.digitsOnlyRows).append(" rows, with letters on ")
				.append(column.withLettersRows).append(" rows");
			if (pShowShapes)
			{
				text.append(" (").append(RowFacts.shapeList(column.shapes)).append(')');
			}
			if (column.companion != null)
			{
				text.append("; the shape follows ").append(column.companion).append(" of the same row");
				if (pShowValues && pShowShapes)
				{
					text.append(" (");
					int shown = 0;
					for (final Map.Entry<String, Map<String, Long>> code : column.shapesByCode.entrySet())
					{
						if (shown++ > 0)
						{
							text.append("; ");
						}
						text.append(code.getKey()).append(" -> ").append(RowFacts.shapeList(code.getValue()));
					}
					text.append(')');
				}
				else
				{
					text.append(" (").append(column.shapesByCode.size()).append(" codes, each holding one kind)");
				}
			}
			text.append(on).append(": a column holding several shapes must not be mapped whole to an attribute of one shape, and must not be left out either (its values are "
				+ "then lost) - map it TWICE, one rule per kind, each with a filtering step: the digits-only values to the attribute of that shape");
			final String digitsShape = RowFacts.singleDigitsLength(column.shapes);
			text.append(digitsShape == null ? " (a regex that keeps the digits-only values and gives null on the others)" : " with regex('^[0-9]{" + digitsShape + "}$')");
			text.append(", the values with letters to the attribute of that kind with ONE regex that keeps every lettered shape listed, not only the most frequent one (e.g. ")
				.append(RowFacts.letteredFilter(column.shapes)).append("); a filter that empties exactly the other kind is accepted");
			facts.add(text.toString());
		}
		final String explained = RowFacts.companionOf(pTable, pField);
		if (explained != null)
		{
			// live B2B run 2026-10-23: the tax-type codes (FR1, BE0...) were mapped to Organisation.businessPartnerCategory and AdresseOrganisation.addressType
			facts.add("its codes tell which kind of identifier " + explained + " holds on the same row (each code holds one shape of it)" + on
				+ ": a qualifier of " + explained + ", not a business attribute of its own - leave it out with a note, or keep it only as the type of that identifier; "
				+ "never map it to an unrelated attribute (a category, an address type, a status)");
		}
		if (column.separatorsInIdentifiers())
		{
			final StringBuilder text = new StringBuilder("identifier-like values written with inner spaces or separators on ").append(column.separated).append(" rows");
			if (pShowShapes)
			{
				text.append(" (").append(RowFacts.shapeList(column.separatedShapes)).append(')');
			}
			text.append(", compact on ").append(column.compact).append(" rows");
			if (column.compactElsewhere != null)
			{
				text.append("; ").append(column.compactElsewhere).append(" writes the same compact shape").append(pShowShapes ? " " + RowFacts.top(column.compactShapes) : "")
					.append(" with no separator");
			}
			text.append(on).append(": identifiers are compared without separators - remove them in the pipeline (replace(' ','') for spaces, or a regex that keeps letters and digits), "
				+ "a case change alone leaves the same identifier written two ways");
			facts.add(text.toString());
		}
		if (facts.isEmpty())
		{
			return "";
		}
		return ", row facts: " + String.join("; ", facts);
	}

	// ----------------------------------------------------------------------------------------------------------- shapes

	/**
	 * Letters and digits (with the usual separators), at least half of them digits, compact form not a phone number: an identifier as sources write one ({@code 552100554},
	 * {@code BE0801007390}, {@code FR 747 741 994 42}); a street with a house number ({@code 92 pl. du Marche}) or a word is not one.
	 */
	public static boolean identifierLike(final String pValue)
	{
		if (pValue == null || !RowFacts.IDENTIFIER_CHARS.matcher(pValue).matches())
		{
			return false;
		}
		final String compact = RowFacts.compactOf(pValue);
		if (!RowFacts.IDENTIFIER_COMPACT.matcher(compact).matches())
		{
			return false;
		}
		int digits = 0;
		for (int i = 0; i < compact.length(); i++)
		{
			if (Character.isDigit(compact.charAt(i)))
			{
				digits++;
			}
		}
		return digits * 2 >= compact.length();
	}

	/** The value without its spaces, dots, hyphens and slashes, in upper case. */
	public static String compactOf(final String pValue)
	{
		return pValue.replaceAll("[ .\\-/]", "").toUpperCase(Locale.ROOT);
	}

	/** Digits only once the separators are removed ({@code 552 100 554} is the same nine digits as {@code 552100554}). */
	static boolean digitsOnly(final String pValue)
	{
		return RowFacts.compactOf(pValue).matches("[0-9]+");
	}

	private static void addIdentifierShape(final Column pColumn, final String pValue)
	{
		if (!RowFacts.identifierLike(pValue))
		{
			return;
		}
		pColumn.identifierRows++;
		RowFacts.add(pColumn.shapes, Shapes.of(pValue), 1L);
		if (RowFacts.digitsOnly(pValue))
		{
			pColumn.digitsOnlyRows++;
		}
		else
		{
			pColumn.withLettersRows++;
		}
		final String compact = RowFacts.compactOf(pValue);
		RowFacts.add(pColumn.compactShapes, Shapes.of(compact), 1L);
		if (compact.length() == pValue.length())
		{
			pColumn.compact++;
		}
		else
		{
			pColumn.separated++;
			RowFacts.add(pColumn.separatedShapes, Shapes.of(pValue), 1L);
		}
	}

	/** {@code 9(9) 125, AA9(9) 10, ...}: the most frequent shapes with their rows. */
	static String shapeList(final Map<String, Long> pShapes)
	{
		final List<Map.Entry<String, Long>> sorted = new ArrayList<Map.Entry<String, Long>>(pShapes.entrySet());
		Collections.sort(sorted, new java.util.Comparator<Map.Entry<String, Long>>()
		{
			@Override
			public int compare(final Map.Entry<String, Long> pLeft, final Map.Entry<String, Long> pRight)
			{
				return pRight.getValue().compareTo(pLeft.getValue());
			}
		});
		final StringBuilder out = new StringBuilder();
		int shown = 0;
		long rest = 0L;
		for (final Map.Entry<String, Long> entry : sorted)
		{
			if (shown >= SHAPES_SHOWN)
			{
				rest += entry.getValue().longValue();
				continue;
			}
			if (shown++ > 0)
			{
				out.append(", ");
			}
			out.append(entry.getKey()).append(' ').append(entry.getValue());
		}
		if (rest > 0L)
		{
			out.append(", other shapes ").append(rest);
		}
		return out.toString();
	}

	/** The field of {@code pTable} whose mixed identifier shapes {@code pField} explains (it is that column's companion), or null. */
	static String companionOf(final Table pTable, final String pField)
	{
		for (final Column other : pTable.columns.values())
		{
			if (pField.equals(other.companion) && other.mixedIdentifier())
			{
				return other.field;
			}
		}
		return null;
	}

	/**
	 * A regex keeping every shape with letters of {@code pShapes}: {@code regex('^\p{L}{2}[\p{L}0-9]+$')} when every lettered shape starts with two letters
	 * ({@code AA9(9)}, {@code AA9(11)}, {@code AAA9(8)}...), the looser {@code regex('^[\p{L}0-9]*\p{L}[\p{L}0-9]*$')} otherwise. Live B2B run 2026-10-23: the
	 * model kept {@code ^\p{Lu}{2}[0-9]{11}$} (the French VAT shape only) and would have emptied the other countries' numbers.
	 */
	static String letteredFilter(final Map<String, Long> pShapes)
	{
		boolean twoLetters = false;
		for (final String shape : pShapes.keySet())
		{
			if (!shape.matches(".*[Aa].*"))
			{
				continue;
			}
			if (!shape.startsWith("AA"))
			{
				return "regex('^[\\p{L}0-9]*\\p{L}[\\p{L}0-9]*$')";
			}
			twoLetters = true;
		}
		return twoLetters ? "regex('^\\p{L}{2}[\\p{L}0-9]+$')" : "regex('^[\\p{L}0-9]*\\p{L}[\\p{L}0-9]*$')";
	}

	/** The length n when every digits-only shape is {@code 9(n)} (or a run of up to 4 nines); null otherwise. */
	static String singleDigitsLength(final Map<String, Long> pShapes)
	{
		String length = null;
		for (final String shape : pShapes.keySet())
		{
			final String n;
			if (shape.matches("9\\(\\d+\\)"))
			{
				n = shape.substring(2, shape.length() - 1);
			}
			else if (shape.matches("9{1,4}"))
			{
				n = String.valueOf(shape.length());
			}
			else
			{
				continue;
			}
			if (length != null && !length.equals(n))
			{
				return null;
			}
			length = n;
		}
		return length;
	}

	private static String top(final Map<String, Long> pCounts)
	{
		String best = null;
		long rows = -1L;
		for (final Map.Entry<String, Long> entry : pCounts.entrySet())
		{
			if (entry.getValue().longValue() > rows)
			{
				best = entry.getKey();
				rows = entry.getValue().longValue();
			}
		}
		return best;
	}

	private static void add(final Map<String, Long> pMap, final String pKey, final long pRows)
	{
		final Long seen = pMap.get(pKey);
		pMap.put(pKey, Long.valueOf((seen == null ? 0L : seen.longValue()) + pRows));
	}

	private static String trimmed(final String pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		final String out = pValue.trim();
		return out.isEmpty() ? null : out;
	}
}
