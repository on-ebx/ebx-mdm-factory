package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Landing test fixes (2026-10-17, defect 5): how likely a column is to be the identity of a row, for the key chooser.
 * Seen live on a store-sales file: {@code NUM_TICKET}, {@code DATE_ACHAT} and {@code MONTANT_TTC} were all unique on 25 rows
 * and the chooser opened on the date. A sample cannot tell an identifier from a timestamp that happens not to repeat, the
 * shape of the name and of the values can: identifier-like names (id, num, number, code, key, ref, ticket, card...) on
 * text-like columns first, dates, timestamps and amounts last and never preselected. Pure: no EBX class.
 */
public final class KeyCandidateRank
{
	/** Name looks like an identifier and the values do not look like a date or an amount. */
	public static final int IDENTIFIER = 0;
	/** Anything else. */
	public static final int NEUTRAL = 1;
	/** A date, a timestamp, an amount or a decimal: listed last, never preselected. */
	public static final int NEVER = 2;

	private static final Set<String> ID_TOKENS = new HashSet<String>(Arrays.asList("id", "uuid", "guid", "num", "no", "nr", "number", "numero", "code", "key", "ref", "reference", "ticket", "card", "sku"));
	private static final Set<String> DATE_TOKENS = new HashSet<String>(Arrays.asList("date", "datetime", "timestamp", "time", "heure", "dt", "ts", "created", "updated", "modified"));
	private static final Set<String> AMOUNT_TOKENS = new HashSet<String>(Arrays.asList("amount", "montant", "price", "prix", "total", "ttc", "ht", "tva", "vat", "tax", "cost", "cout", "balance", "solde", "qty", "quantity", "quantite"));
	private static final Pattern DATE_VALUE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}([T ].*)?|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}([T ].*)?|\\d{4}/\\d{2}/\\d{2}.*");
	private static final Pattern DECIMAL_VALUE = Pattern.compile("[-+]?\\d+[.,]\\d+");

	private KeyCandidateRank()
	{
	}

	/**
	 * @param pDataType the column's source data type ({@code VARCHAR}, {@code DECIMAL(10,2)}, {@code TIMESTAMP}...), may be blank
	 * @param pValues the first distinct sample values, may be empty
	 */
	public static int rank(final String pName, final String pDataType, final List<String> pValues)
	{
		final List<String> tokens = KeyCandidateRank.tokens(pName);
		final String type = pDataType == null ? "" : pDataType.toUpperCase(Locale.ROOT);
		if (type.contains("DATE") || type.contains("TIME") || type.contains("DECIMAL") || type.contains("NUMERIC") || type.contains("FLOAT") || type.contains("DOUBLE") || type.contains("REAL")
			|| type.contains("MONEY") || type.matches(".*NUMBER\\s*\\(\\s*\\d+\\s*,\\s*[1-9]\\d*\\s*\\).*"))
		{
			return KeyCandidateRank.NEVER;
		}
		for (final String token : tokens)
		{
			if (KeyCandidateRank.DATE_TOKENS.contains(token) || KeyCandidateRank.AMOUNT_TOKENS.contains(token))
			{
				return KeyCandidateRank.NEVER;
			}
		}
		if (pValues != null && !pValues.isEmpty())
		{
			boolean every = true;
			for (final String value : pValues)
			{
				final String v = value == null ? "" : value.trim();
				if (!KeyCandidateRank.DATE_VALUE.matcher(v).matches() && !KeyCandidateRank.DECIMAL_VALUE.matcher(v).matches())
				{
					every = false;
					break;
				}
			}
			if (every)
			{
				return KeyCandidateRank.NEVER;
			}
		}
		if (!tokens.isEmpty() && (KeyCandidateRank.ID_TOKENS.contains(tokens.get(0)) || KeyCandidateRank.ID_TOKENS.contains(tokens.get(tokens.size() - 1))))
		{
			return KeyCandidateRank.IDENTIFIER;
		}
		return KeyCandidateRank.NEUTRAL;
	}

	/** {@code NUM_TICKET} -> [num, ticket]; {@code customerId} -> [customer, id]; {@code cardNumber} -> [card, number]. */
	static List<String> tokens(final String pName)
	{
		final List<String> out = new ArrayList<String>();
		if (pName == null)
		{
			return out;
		}
		final String spaced = pName.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[^A-Za-z0-9]+", " ").trim();
		if (spaced.isEmpty())
		{
			return out;
		}
		for (final String part : spaced.split(" "))
		{
			out.add(part.toLowerCase(Locale.ROOT));
		}
		return out;
	}

	/** The columns unique on the sample, best rank first (table order within a rank), without the ones in {@code pExcluded}, at most {@code pMax}. */
	public static List<String> candidates(final KeyStudyReport pReport, final Collection<String> pExcluded, final int pMax)
	{
		final List<KeyStudyReport.ColumnRow> unique = new ArrayList<KeyStudyReport.ColumnRow>();
		for (final KeyStudyReport.ColumnRow column : pReport.columns)
		{
			if (column.evidence != null && column.evidence.uniqueInSample() && !pExcluded.contains(column.name))
			{
				unique.add(column);
			}
		}
		final List<Integer> ranks = new ArrayList<Integer>();
		for (final KeyStudyReport.ColumnRow column : unique)
		{
			ranks.add(Integer.valueOf(KeyCandidateRank.rank(column.name, column.dataType, column.evidence.values)));
		}
		final List<String> out = new ArrayList<String>();
		for (int r = KeyCandidateRank.IDENTIFIER; r <= KeyCandidateRank.NEVER && out.size() < pMax; r++)
		{
			for (int i = 0; i < unique.size() && out.size() < pMax; i++)
			{
				if (ranks.get(i).intValue() == r)
				{
					out.add(unique.get(i).name);
				}
			}
		}
		return out;
	}

	/** Rank of one column of the report (by name); {@link #NEUTRAL} when unknown. */
	public static int rankOf(final KeyStudyReport pReport, final String pColumn)
	{
		for (final KeyStudyReport.ColumnRow column : pReport.columns)
		{
			if (column.name.equalsIgnoreCase(pColumn))
			{
				return KeyCandidateRank.rank(column.name, column.dataType, column.evidence == null ? Collections.<String> emptyList() : column.evidence.values);
			}
		}
		return KeyCandidateRank.NEUTRAL;
	}

	/**
	 * The columns the chooser opens ticked: the proposal's, unless it is a single date/timestamp/amount column (never preselected);
	 * else the table's current key (a person's or the source's, kept as it is); else the best unique identifier-like candidate, if any. Empty when nothing qualifies.
	 */
	public static List<String> preselected(final KeyStudyReport pReport)
	{
		if (pReport.proposal != null && !pReport.proposal.columns.isEmpty()
			&& !(pReport.proposal.columns.size() == 1 && KeyCandidateRank.rankOf(pReport, pReport.proposal.columns.get(0)) == KeyCandidateRank.NEVER))
		{
			return new ArrayList<String>(pReport.proposal.columns);
		}
		final List<String> current = new ArrayList<String>();
		for (final KeyStudyReport.ColumnRow column : pReport.columns)
		{
			if (column.inCurrentKey)
			{
				current.add(column.name);
			}
		}
		if (!current.isEmpty())
		{
			return current;
		}
		final List<String> best = KeyCandidateRank.candidates(pReport, Collections.<String> emptySet(), 1);
		if (!best.isEmpty() && KeyCandidateRank.rankOf(pReport, best.get(0)) == KeyCandidateRank.IDENTIFIER)
		{
			return best;
		}
		return new ArrayList<String>();
	}
}
