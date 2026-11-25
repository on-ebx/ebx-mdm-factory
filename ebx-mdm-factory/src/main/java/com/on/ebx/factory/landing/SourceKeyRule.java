package com.on.ebx.factory.landing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Proposes the source key of a landing table: the columns that build {@code _sourceId}, the identity of
 * the row in its system. The key declared at the source wins. Many platforms declare none (Snowflake
 * does not enforce one, a file has none): then a column named like the identifier of the table, which the
 * profile must confirm when there is one (no null, all values distinct). Nothing else is guessed: without a
 * proposal the user picks the columns, and with none at all {@code _sourceId} is the hash of the row.
 * Pure: no EBX class.
 */
public final class SourceKeyRule
{
	private SourceKeyRule()
	{
	}

	/** What the catalog knows of a column. Rates are 0-1, null when the column was never profiled. */
	public static final class Candidate
	{
		public final String name;
		public final boolean declaredKey;
		public final BigDecimal nullRate;
		public final BigDecimal uniqueRate;

		public Candidate(final String pName, final boolean pDeclaredKey, final BigDecimal pNullRate, final BigDecimal pUniqueRate)
		{
			this.name = pName;
			this.declaredKey = pDeclaredKey;
			this.nullRate = pNullRate;
			this.uniqueRate = pUniqueRate;
		}
	}

	public static final class Proposal
	{
		/** Names of the key columns, in column order; empty when nothing could be proposed. */
		public final List<String> columns;
		/** 0-100. */
		public final int confidence;
		/** The English sentence (as before this lot); {@link #stored()} is what is written, with its code. */
		public final String rationale;
		/** Key rationale i18n (2026-10-17): the same sentence as a code and arguments. */
		public final KeyTexts.Text text;

		Proposal(final List<String> pColumns, final int pConfidence, final KeyTexts.Text pText)
		{
			this.columns = pColumns;
			this.confidence = pConfidence;
			this.text = pText;
			this.rationale = pText.english;
		}

		/** The English rationale followed by its code marker: what the Factory stores, and a wizard screen words in the viewer's language. */
		public String stored()
		{
			return this.text.stored();
		}
	}

	/** @param pTableName last part of the source object name ({@code SUPPLIER}) */
	public static Proposal propose(final String pTableName, final List<Candidate> pColumns)
	{
		return SourceKeyRule.propose(pTableName, pColumns, false);
	}

	/**
	 * Key rationale i18n (2026-10-17): {@code pFromSample} says where the null and distinct figures of the candidates were measured - on the sample rows
	 * of the key study (true) or in the catalog's profile (false) - so the sentence does not claim a profile when the evidence is the sample.
	 *
	 * @param pTableName last part of the source object name ({@code SUPPLIER})
	 */
	public static Proposal propose(final String pTableName, final List<Candidate> pColumns, final boolean pFromSample)
	{
		final List<String> declared = new ArrayList<String>();
		for (final Candidate column : pColumns)
		{
			if (column.declaredKey)
			{
				declared.add(column.name);
			}
		}
		if (!declared.isEmpty())
		{
			return new Proposal(declared, 100, KeyTexts.declared());
		}
		Candidate best = null;
		int bestRank = Integer.MAX_VALUE;
		for (final Candidate column : pColumns)
		{
			final int rank = SourceKeyRule.rank(pTableName, column.name);
			if (rank < bestRank && !SourceKeyRule.refutedByProfile(column))
			{
				best = column;
				bestRank = rank;
			}
		}
		if (best == null)
		{
			// landing test fixes (2026-10-17): nothing is named like the table, so a single column named <x>Id / <x>_id / <x>Number whose
			// profile shows no empty value and only distinct values is proposed, with a moderate confidence (a person still confirms it)
			final Proposal generic = SourceKeyRule.genericIdentifier(pColumns, pFromSample);
			if (generic != null)
			{
				return generic;
			}
			return new Proposal(new ArrayList<String>(), 0, KeyTexts.none());
		}
		final List<String> columns = new ArrayList<String>();
		columns.add(best.name);
		if (best.uniqueRate != null)
		{
			return new Proposal(columns, bestRank == 0 ? 95 : 85, KeyTexts.named(best.name, pFromSample));
		}
		return new Proposal(columns, bestRank == 0 ? 70 : 50, KeyTexts.noProfile(best.name));
	}

	/** Moderate confidence of a generic identifier column (landing test fixes, 2026-10-17): below {@code KeyCertainty.LLM_CONFIDENCE_MIN}. */
	public static final int GENERIC_CONFIDENCE = 60;

	/**
	 * Landing test fixes (2026-10-17): the only column of the table whose name ends like an identifier ({@code customerId},
	 * {@code customer_id}, {@code order_number}, {@code OrderNumber}) and which the profile confirms (no empty value, all distinct);
	 * {@code null} when there is none or more than one (then nothing says which one is the row's own identity).
	 */
	static Proposal genericIdentifier(final List<Candidate> pColumns, final boolean pFromSample)
	{
		Candidate found = null;
		for (final Candidate column : pColumns)
		{
			if (!SourceKeyRule.isGenericIdentifierName(column.name) || column.nullRate == null || column.uniqueRate == null || SourceKeyRule.refutedByProfile(column))
			{
				continue;
			}
			if (found != null)
			{
				return null;
			}
			found = column;
		}
		if (found == null)
		{
			return null;
		}
		final List<String> columns = new ArrayList<String>();
		columns.add(found.name);
		return new Proposal(columns, SourceKeyRule.GENERIC_CONFIDENCE, KeyTexts.genericId(found.name, pFromSample));
	}

	/** {@code id}, {@code <x>Id}, {@code <x>_id}, {@code <x>Number}, {@code <x>_number}, {@code <x>Num}-style names; {@code Paid} or {@code valid} are not. */
	static boolean isGenericIdentifierName(final String pName)
	{
		if (pName == null)
		{
			return false;
		}
		final String name = pName.trim();
		return name.equalsIgnoreCase("id") || name.matches(".*[a-z0-9]Id") || name.matches("(?i).+[_ .-]id") || name.matches(".*[a-z0-9]Number") || name.matches("(?i).+[_ .-]number");
	}

	/** 0: {@code <TABLE>_ID} or {@code <TABLE>ID} (table singular or plural, either way); 1: {@code ID}; anything else is not an identifier name. */
	private static int rank(final String pTableName, final String pColumn)
	{
		final String column = SourceKeyRule.letters(pColumn);
		final String table = SourceKeyRule.letters(pTableName);
		if (!table.isEmpty() && (column.equals(table + "id") || column.equals(table + "sid") || table.endsWith("s") && column.equals(table.substring(0, table.length() - 1) + "id")))
		{
			return 0;
		}
		return column.equals("id") ? 1 : Integer.MAX_VALUE;
	}

	private static boolean refutedByProfile(final Candidate pColumn)
	{
		final boolean hasNulls = pColumn.nullRate != null && pColumn.nullRate.signum() > 0;
		final boolean hasDuplicates = pColumn.uniqueRate != null && pColumn.uniqueRate.compareTo(BigDecimal.ONE) < 0;
		return hasNulls || hasDuplicates;
	}

	private static String letters(final String pName)
	{
		return pName == null ? "" : pName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
}
