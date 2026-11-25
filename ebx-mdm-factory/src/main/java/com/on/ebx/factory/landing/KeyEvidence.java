package com.on.ebx.factory.landing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What a small live sample of a source object says about each column as a key candidate (landing key-study
 * lot, 2026-09-23 - "help me make a choice": the key picker of the wizard's step 3 used to offer the column
 * names alone). Per column: how many sample rows are empty, how many distinct non-empty values there are,
 * and the first few distinct values themselves, so a person can see at a glance that {@code BusinessPartner}
 * holds {@code 1000123}-like identifiers while {@code SearchTerm1} holds free text. The same counts feed
 * {@link SourceKeyRule} as {@code nullRate}/{@code uniqueRate} (the profile the rule asks for, measured on
 * the sample rather than by the catalog), and {@link #uniqueTogether} checks a proposed composite key
 * against the very same rows.
 * <p>
 * A sample is evidence, never proof: a column unique in 25 rows may still duplicate in the full table. Every
 * figure here carries the number of rows it was measured on so the page never shows a rate alone. Pure: no
 * EBX class.
 */
public final class KeyEvidence
{
	/** How many distinct values are kept per column for display. */
	public static final int VALUES_SHOWN = 3;
	/** Longest value shown or sent as-is; longer ones are cut with an ellipsis. */
	public static final int VALUE_MAX_LENGTH = 40;

	private static final char COMBINED_SEPARATOR = '';
	private static final String ELLIPSIS = "…";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private KeyEvidence()
	{
	}

	/** One column of the sample. */
	public static final class Column
	{
		public final String name;
		/** Rows in the sample (the same for every column of one sample). */
		public final int rows;
		/** Rows where the value is null or blank. */
		public final int empty;
		/** Distinct non-empty values. */
		public final int distinct;
		/** The first {@link #VALUES_SHOWN} distinct non-empty values, in row order, each cut to {@link #VALUE_MAX_LENGTH}. */
		public final List<String> values;

		Column(final String pName, final int pRows, final int pEmpty, final int pDistinct, final List<String> pValues)
		{
			this.name = pName;
			this.rows = pRows;
			this.empty = pEmpty;
			this.distinct = pDistinct;
			this.values = pValues;
		}

		/** Every sample row has a value and no two rows share one - what a key column looks like on a sample. */
		public boolean uniqueInSample()
		{
			return this.rows > 0 && this.empty == 0 && this.distinct == this.rows;
		}

		/** {@code empty / rows}, 4 decimals; null on an empty sample (nothing measured). */
		public BigDecimal nullRate()
		{
			return this.rows == 0 ? null : BigDecimal.valueOf(this.empty).divide(BigDecimal.valueOf(this.rows), 4, RoundingMode.HALF_UP);
		}

		/** {@code distinct / non-empty rows}, 4 decimals; null when no row has a value. */
		public BigDecimal uniqueRate()
		{
			final int filled = this.rows - this.empty;
			return filled <= 0 ? null : BigDecimal.valueOf(this.distinct).divide(BigDecimal.valueOf(filled), 4, RoundingMode.HALF_UP);
		}
	}

	/**
	 * @param pColumns the sample's column names, in order
	 * @param pRows one list per row, aligned on {@code pColumns} (a short row counts as empty past its end)
	 */
	public static List<Column> analyse(final List<String> pColumns, final List<List<String>> pRows)
	{
		final List<Column> out = new ArrayList<Column>();
		for (int c = 0; c < pColumns.size(); c++)
		{
			int empty = 0;
			final Set<String> distinct = new LinkedHashSet<String>();
			for (final List<String> row : pRows)
			{
				final String value = c < row.size() ? row.get(c) : null;
				if (value == null || value.trim().isEmpty())
				{
					empty++;
				}
				else
				{
					distinct.add(value.trim());
				}
			}
			final List<String> shown = new ArrayList<String>();
			for (final String value : distinct)
			{
				if (shown.size() >= KeyEvidence.VALUES_SHOWN)
				{
					break;
				}
				shown.add(KeyEvidence.cut(value));
			}
			out.add(new Column(pColumns.get(c), pRows.size(), empty, distinct.size(), shown));
		}
		return out;
	}

	/** The column named {@code pName} (exact, then case-insensitive), or null when the sample has none. */
	public static Column find(final List<Column> pEvidence, final String pName)
	{
		for (final Column column : pEvidence)
		{
			if (column.name.equals(pName))
			{
				return column;
			}
		}
		for (final Column column : pEvidence)
		{
			if (column.name.equalsIgnoreCase(pName))
			{
				return column;
			}
		}
		return null;
	}

	/**
	 * Whether {@code pKeyColumns} together identify every sample row: no row has an empty component and no two
	 * rows share the same combination. Null when it cannot be told - no sample row, or a key column the sample
	 * does not have.
	 */
	public static Boolean uniqueTogether(final List<String> pSampleColumns, final List<List<String>> pRows, final List<String> pKeyColumns)
	{
		if (pRows.isEmpty() || pKeyColumns.isEmpty())
		{
			return null;
		}
		final List<Integer> indexes = new ArrayList<Integer>();
		for (final String key : pKeyColumns)
		{
			final int index = KeyEvidence.indexOf(pSampleColumns, key);
			if (index < 0)
			{
				return null;
			}
			indexes.add(Integer.valueOf(index));
		}
		final Set<String> seen = new HashSet<String>();
		for (final List<String> row : pRows)
		{
			final StringBuilder combined = new StringBuilder();
			for (final Integer index : indexes)
			{
				final String value = index.intValue() < row.size() ? row.get(index.intValue()) : null;
				if (value == null || value.trim().isEmpty())
				{
					return Boolean.FALSE;
				}
				// a separator no value contains in practice; a value carrying it could only make two distinct
				// combinations collide, i.e. a false "not unique", never a false "unique"
				combined.append(value.trim()).append(KeyEvidence.COMBINED_SEPARATOR);
			}
			if (!seen.add(combined.toString()))
			{
				return Boolean.FALSE;
			}
		}
		return Boolean.TRUE;
	}

	/**
	 * {@link SourceKeyRule} candidates for the table's columns, profiled by the sample: a column the sample
	 * does not have keeps null rates (never profiled), exactly as the catalog would report it. No column is
	 * flagged as declared at the source here - the rule then judges on names and the sample alone.
	 */
	public static List<SourceKeyRule.Candidate> candidates(final List<String> pTableColumns, final List<Column> pEvidence)
	{
		final List<SourceKeyRule.Candidate> out = new ArrayList<SourceKeyRule.Candidate>();
		for (final String name : pTableColumns)
		{
			final Column column = KeyEvidence.find(pEvidence, name);
			out.add(new SourceKeyRule.Candidate(name, false, column == null ? null : column.nullRate(), column == null ? null : column.uniqueRate()));
		}
		return out;
	}

	static int indexOf(final List<String> pColumns, final String pName)
	{
		final int index = pColumns.indexOf(pName);
		if (index >= 0)
		{
			return index;
		}
		for (int i = 0; i < pColumns.size(); i++)
		{
			if (pColumns.get(i).equalsIgnoreCase(pName))
			{
				return i;
			}
		}
		return -1;
	}

	static String cut(final String pValue)
	{
		return pValue.length() <= KeyEvidence.VALUE_MAX_LENGTH ? pValue : pValue.substring(0, KeyEvidence.VALUE_MAX_LENGTH - 1) + KeyEvidence.ELLIPSIS;
	}

	/**
	 * What the sample says about ONE candidate SET of columns as a composite key - the measurement lot
	 * LANDING's composite-key builder calls live "as you add or remove a column" (composite-key lot,
	 * 2026-09-24). Unlike {@link #uniqueTogether} (a plain yes/no/unknown), this always returns a result,
	 * with enough figures to explain a "not unique" verdict rather than just state it: how many sample rows
	 * have every part of the combination empty (a {@code '#'}-prefixed, non-key {@code _sourceId} fallback
	 * waiting to happen - {@code LandingFlow.mapping}), and how many have SOME but not all parts empty (a
	 * partially-empty composite key, still a real identity - see the same class).
	 */
	public static final class Combination
	{
		/** The requested columns, in the order they were asked for (not necessarily the table's column order). */
		public final List<String> columns;
		/** Rows in the sample. */
		public final int rowsInSample;
		/** Distinct combined values among every sample row (a fully-empty combination counts as one). */
		public final int distinctTogether;
		/** Rows where every one of {@link #columns} is empty (or not in the sample at all). */
		public final int rowsAllEmpty;
		/** Rows where at least one, but not every, part of the combination is empty. */
		public final int rowsPartlyEmpty;
		/**
		 * Coordinator follow-up round 3 (2026-09-24): rows whose key has NO empty part AND whose joined key
		 * value occurs EXACTLY ONCE among the complete (no-empty-part) rows - the exact, tested replacement
		 * for {@code wizard.WizardPage}'s earlier {@code displayDistinct} approximation (which only subtracted
		 * at most one "all-empty" bucket and one per partly-empty row from {@link #distinctTogether}, wrong as
		 * soon as two DIFFERENT partly-empty rows shared a combination). This is what every "unique in N of M"
		 * chip (candidate cards, the composite fold, {@code wizard.js}'s live builder) now shows as N -
		 * mirrored EXACTLY in {@code wizard.js}, never re-derived from {@link #distinctTogether} there either.
		 */
		public final int rowsUniqueComplete;

		Combination(final List<String> pColumns, final int pRowsInSample, final int pDistinctTogether, final int pRowsAllEmpty, final int pRowsPartlyEmpty,
			final int pRowsUniqueComplete)
		{
			this.columns = pColumns;
			this.rowsInSample = pRowsInSample;
			this.distinctTogether = pDistinctTogether;
			this.rowsAllEmpty = pRowsAllEmpty;
			this.rowsPartlyEmpty = pRowsPartlyEmpty;
			this.rowsUniqueComplete = pRowsUniqueComplete;
		}

		/** Every sample row is complete (no empty part) and distinct from every other - exactly {@link #rowsUniqueComplete} == {@link #rowsInSample}, which can only hold when {@link #rowsAllEmpty}/{@link #rowsPartlyEmpty} are both zero. */
		public boolean unique()
		{
			return this.rowsInSample > 0 && this.rowsUniqueComplete == this.rowsInSample;
		}
	}

	/**
	 * Measures {@code pColumns} (any set, in any order - not necessarily a proposed or current key) against
	 * {@code pRows}. A column {@code pColumns} names that the sample does not have (see {@link #indexOf}) is
	 * treated as always empty for every row, exactly like {@link #candidates} treats an unprofiled column -
	 * never an exception, so a caller can measure a column the sample happens to lack without checking first.
	 * An empty {@code pColumns} measures the degenerate "no key at all" case: every row's combination is
	 * empty, {@link Combination#rowsAllEmpty} equals {@link Combination#rowsInSample}.
	 */
	public static Combination combination(final List<String> pSampleColumns, final List<List<String>> pRows, final List<String> pColumns)
	{
		final List<Integer> indexes = new ArrayList<Integer>();
		for (final String name : pColumns)
		{
			indexes.add(Integer.valueOf(KeyEvidence.indexOf(pSampleColumns, name)));
		}
		int allEmpty = 0;
		int partlyEmpty = 0;
		final Set<String> distinct = new LinkedHashSet<String>();
		// coordinator follow-up round 3 (2026-09-24): a second pass, over COMPLETE (no-empty-part) rows only,
		// counts how many times each one's combined value occurs among them - rowsUniqueComplete is then the
		// count of complete rows whose combined value occurs exactly once. Kept as a plain list (row order
		// preserved) rather than folded into the single loop above so the "occurs exactly once" check can
		// look at every complete row's own count after all of them are known, not just the ones seen so far.
		final List<String> completeCombined = new ArrayList<String>();
		for (final List<String> row : pRows)
		{
			int emptyParts = 0;
			final StringBuilder combined = new StringBuilder();
			for (final Integer index : indexes)
			{
				final int i = index.intValue();
				final String raw = i >= 0 && i < row.size() ? row.get(i) : null;
				final String value = raw == null ? "" : raw.trim();
				if (value.isEmpty())
				{
					emptyParts++;
				}
				combined.append(value).append(KeyEvidence.COMBINED_SEPARATOR);
			}
			if (emptyParts == indexes.size())
			{
				allEmpty++;
			}
			else if (emptyParts > 0)
			{
				partlyEmpty++;
			}
			else
			{
				completeCombined.add(combined.toString());
			}
			distinct.add(combined.toString());
		}
		final Map<String, Integer> completeCounts = new HashMap<String, Integer>();
		for (final String combined : completeCombined)
		{
			final Integer previous = completeCounts.get(combined);
			completeCounts.put(combined, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
		}
		int uniqueComplete = 0;
		for (final String combined : completeCombined)
		{
			if (completeCounts.get(combined).intValue() == 1)
			{
				uniqueComplete++;
			}
		}
		return new Combination(new ArrayList<String>(pColumns), pRows.size(), distinct.size(), allEmpty, partlyEmpty, uniqueComplete);
	}

	/**
	 * The sample as a compact JSON array of rows, restricted to string values - {@code [{"col1":"v1",...},
	 * ...]}, one object per row. {@code pColumns} must align POSITIONALLY with {@code pRows} exactly the way
	 * {@link #analyse}/{@link #combination} read them (typically the sample's own full column list, in the
	 * same order it was read in) - it is not a filter naming which of the row's own columns to keep, the way
	 * {@link #combination}'s own {@code pColumns} argument is. A short row (fewer values than {@code pColumns})
	 * simply omits the trailing keys rather than writing a null. Lets a client-side script (lot LANDING's
	 * composite-key builder) recompute {@link #combination} for a set of columns the person is still trying
	 * out, without a further round trip to the server for every checkbox they click.
	 */
	public static String sampleJson(final List<String> pColumns, final List<List<String>> pRows)
	{
		final ArrayNode array = KeyEvidence.MAPPER.createArrayNode();
		for (final List<String> row : pRows)
		{
			final ObjectNode object = array.addObject();
			for (int c = 0; c < pColumns.size() && c < row.size(); c++)
			{
				final String value = row.get(c);
				object.put(pColumns.get(c), value == null ? "" : value);
			}
		}
		return array.toString();
	}
}
