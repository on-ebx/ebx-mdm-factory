package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.List;

/**
 * The XPath predicate a scoped matching run is built from, for the {@code MatchingOperations.matchRecords(
 * AdaptationTable, Request, String)} Java API call (`docs/MATCHING.md` &sect;4). Pure: no EBX class, so
 * every branch is unit-tested without a running instance - the EBX reads that feed it
 * ({@link #incremental}'s most-recent {@code _loadId}, {@link #all}'s {@code Xref.masterKey} list) live in
 * {@code ebx.MatchingConfigurator}.
 * <p>
 * <b>Never an unscoped run</b> (`matching_guide`'s own warning, quoted in `docs/MATCHING.md` &sect;2.1: an
 * empty/omitted {@code recordPks}-equivalent scope discards every existing match/suspect verdict of the
 * WHOLE table, across every policy) - both modes below refuse outright, naming why, rather than fall back
 * to a predicate that would match everything.
 * <p>
 * <b>{@link #incremental} - the default, and why it reads {@code _loadId} off the MASTER table itself,
 * not a dedicated "master load run id."</b> No such id exists: {@code ebx.MasterLoader} carries
 * {@code _loadId}/{@code _receivedAt} through UNCHANGED from the staging row (its own Javadoc says so
 * explicitly - "never a freshly minted 'master load id'"), so there is nothing stamped at MASTER_LOAD time
 * to key an incremental scope on. The closest available proxy - and, for the one-batch-per-run shape this
 * factory's demo projects actually have, a correct one - is the MAXIMUM {@code _loadId} value already
 * present on the master table's own rows: whichever staging/landing batch was loaded most recently. If a
 * later lot ever mints a true per-run master-load id, this should switch to that instead.
 * <p>
 * <b>{@link #all} - every record with an {@code Xref} row of this project</b>, via an explicit
 * {@code ./id = 'k1' or ./id = 'k2' or ...} predicate over the exact master-key list the caller already
 * collected from {@code Xref} (the master primary key is always the plain auto-increment {@code id} -
 * {@code master.MasterModel}'s own design). Deliberately not "no predicate at all": a GREENFIELD master
 * table is 1:1 with one project today, but the predicate still names the exact records this run answers
 * for, so the intent survives even if that ever changes.
 * <p>
 * <b>Lot R9 (2026-09-27, `docs/MATCHING.md` &sect;40, live PersVol7 step 596) - two bugs in the enumerated
 * form above.</b> (1) <i>Wrong literal type.</i> Every key was rendered as a quoted XPath STRING literal
 * (` ./id='1' `) regardless of what {@code /root/&lt;Entity&gt;/id} actually is - a plain auto-increment
 * NUMBER by design (this class' own Javadoc above, `master.MasterModel`) - so the add-on's own XPath engine
 * threw {@code ClassCastException: class java.math.BigDecimal cannot be cast to class java.lang.String}
 * comparing a numeric field against a string literal. {@link #all} now takes a {@link KeyType} and renders
 * a {@link KeyType#NUMERIC} key UNQUOTED (validated to actually look like a number first - see
 * {@link #requireNumericLiteral}, fail loudly rather than build a predicate the add-on would choke on
 * differently) and a {@link KeyType#STRING} key quoted/escaped exactly as {@link #incremental} already
 * does, over the table's OWN primary-key field path ({@code pPrimaryKeyField}, read live by the caller -
 * never hardcoded to {@code id} here, even though that is what this factory's own master tables always use
 * - see &sect;40's own coordinator review). (2) <i>Unbounded OR list.</i> Enumerating one
 * {@code ./&lt;pk&gt;=...} clause per key does not scale and is not even necessary for this factory's own
 * architecture: a project's master dataset/dataspace ({@code Project.masterDataspace}/{@code masterDataset})
 * is created FOR that project alone - no other project's records can ever share it - so "every record with
 * an Xref row of this project" and "every record of this table" are the SAME set whenever
 * {@code pWholeTableBelongsToProject} is true (the only value any caller in this codebase passes today). In
 * that case {@link #all} answers {@code osd:is-not-null(./&lt;pk&gt;)} instead of enumerating - a real,
 * deliberate, non-empty predicate (not "no predicate at all", so `matching_guide`'s own "never an unscoped
 * run" warning is not violated: the add-on is TOLD, explicitly, to match every record with a real key,
 * rather than defaulting to that behavior because the caller forgot to scope anything). <b>Coordinator
 * review, same day: a bare {@code true()} was tried first and measured LIVE to be rejected</b> -
 * {@code count_records_by_xpath} against `PersVol7_Master`/`Master`/`/root/Adresse` answered
 * {@code "Invalid XPath predicate: 'true()'"} for {@code true()}, while {@code osd:is-not-null(./id)}
 * answered 4667 (every row) - EBX's own XPath dialect does not accept a bare boolean-function predicate with
 * no path step at all, unlike a predicate that actually names a field. {@code osd:is-not-null(./&lt;pk&gt;)}
 * is the fix: it names the table's own primary key (never null on a real row, so it selects every record)
 * and is immune to the type bug above by construction, since it never compares the key against a literal.
 * The enumerated form is kept (fixed, unit-tested) for a future table that does NOT satisfy the
 * one-project-per-table property, or for an add-on API that genuinely needs an explicit key set - a caller
 * passes {@code pWholeTableBelongsToProject=false} for that. A composite key is handled by using its FIRST
 * field only (never null on a real row either, and enough to select "every record" or, in the enumerated
 * form, is the caller's own choice of which field the collected key values are drawn from) - this factory's
 * own master tables never have one (this class' own Javadoc above: the primary key is always the single
 * {@code id} field), so this is defensive generality, not a measured case.
 */
public final class MatchingRunScope
{
	private MatchingRunScope()
	{
	}

	/** Whether an {@link #all} master-key value must be compared as a NUMBER (unquoted) or a STRING (quoted, apostrophes doubled) in the enumerated XPath predicate - see class Javadoc "Lot R9". */
	public enum KeyType
	{
		/** The primary key field is a number (e.g. the auto-increment {@code id} every master table uses by design) - compared unquoted. */
		NUMERIC,
		/** The primary key field is a string - quoted, with an embedded apostrophe doubled the same way {@link #incremental} already does. */
		STRING
	}

	public static final class Decision
	{
		public final boolean scoped;
		/** Non-null only when {@link #scoped} is true. */
		public final String xpathPredicate;
		/** Non-null only when {@link #scoped} is false. */
		public final String refusal;

		private Decision(final boolean pScoped, final String pXpathPredicate, final String pRefusal)
		{
			this.scoped = pScoped;
			this.xpathPredicate = pXpathPredicate;
			this.refusal = pRefusal;
		}
	}

	/**
	 * Lot M4 (2026-10-03, defect 3, Vol1m): one plain sentence saying what a run covered, for the entity's step message and detail - so
	 * an incremental run is never mistaken for a run on the whole table. {@code pPivots} = the records the run's predicate selects (the
	 * pivots the add-on matches), {@code pTotal} = the table's row count; either is {@code -1} when it could not be counted. For
	 * {@code pScopeAll} the predicate selects every record of the table, so only the total is meaningful. Pure, unit-tested.
	 */
	public static String describe(final boolean pScopeAll, final long pPivots, final long pTotal)
	{
		if (pScopeAll)
		{
			return pTotal >= 0 ? "Scope: all records - the " + MatchingRunScope.number(pTotal) + " records of the table were pivots."
				: "Scope: all records of the table (the record count was not available).";
		}
		if (pPivots < 0 || pTotal < 0)
		{
			return "Scope: incremental - only the most recently loaded batch was matched (the record counts were not available).";
		}
		final long others = Math.max(0, pTotal - pPivots);
		return "Scope: incremental - " + MatchingRunScope.number(pPivots) + " of " + MatchingRunScope.number(pTotal)
			+ " records were pivots (the last loaded batch); the other " + MatchingRunScope.number(others) + " were not matched in this run.";
	}

	private static String number(final long pValue)
	{
		return String.format(java.util.Locale.ROOT, "%,d", Long.valueOf(pValue));
	}

	/** @param pMostRecentLoadId the maximum {@code _loadId} value found on the master table's own rows (this project's records only), or null/blank if none. */
	public static Decision incremental(final String pMostRecentLoadId)
	{
		if (pMostRecentLoadId == null || pMostRecentLoadId.trim().isEmpty())
		{
			return new Decision(false, null,
				"No _loadId found on the master table yet: nothing has reached master through POST master/load, so there is nothing to scope an incremental run to.");
		}
		return new Decision(true, "./_loadId=" + MatchingRunScope.quote(pMostRecentLoadId.trim()), null);
	}

	/**
	 * @param pMasterKeys every non-blank {@code Xref.masterKey} of this project for the entity's master table -
	 *        only ever consulted for "is there anything to scope at all" when {@code pWholeTableBelongsToProject}
	 *        is true (see class Javadoc "Lot R9").
	 * @param pWholeTableBelongsToProject true when the table this scope is for lives in a dataset/dataspace
	 *        exclusive to this one project (this factory's own architecture, always, as of lot R9 - {@code
	 *        Project.masterDataspace}/{@code masterDataset} are created one per project) - the predicate then
	 *        becomes the non-enumerating {@code osd:is-not-null(./&lt;pk&gt;)} rather than one
	 *        {@code ./&lt;pk&gt;=...} clause per key.
	 * @param pPrimaryKeyField the table's own primary key field, relative to the record (no leading {@code ./} or
	 *        {@code /} - e.g. {@code "id"}, or {@code "group/field"} for a nested one) - read live by the caller
	 *        off the table's real schema (never hardcoded here, even though this factory's own master tables
	 *        always use the plain {@code id} field - class Javadoc "Lot R9" coordinator review). For a composite
	 *        key, the caller passes the FIRST field only (see class Javadoc).
	 * @param pKeyType how to render each key literal in the ENUMERATED form - ignored when
	 *        {@code pWholeTableBelongsToProject} is true.
	 */
	public static Decision all(final List<String> pMasterKeys, final boolean pWholeTableBelongsToProject, final String pPrimaryKeyField, final KeyType pKeyType)
	{
		if (pPrimaryKeyField == null || pPrimaryKeyField.trim().isEmpty())
		{
			throw new IllegalStateException("MatchingRunScope.all: no primary key field given - cannot build an all-records predicate without one.");
		}
		final List<String> keys = new ArrayList<String>();
		if (pMasterKeys != null)
		{
			for (final String key : pMasterKeys)
			{
				if (key != null && !key.trim().isEmpty())
				{
					keys.add(key.trim());
				}
			}
		}
		if (keys.isEmpty())
		{
			return new Decision(false, null, "No Xref row of this project has reached master yet: nothing to scope an all-records run to.");
		}
		if (pWholeTableBelongsToProject)
		{
			// lot R9 (coordinator review): equivalent to the enumerated set (see class Javadoc), never an
			// unbounded OR list, and never touches the key field's own VALUE type - true() itself was measured
			// live to be an invalid EBX XPath predicate, osd:is-not-null(./<pk>) is not.
			return new Decision(true, "osd:is-not-null(./" + pPrimaryKeyField + ")", null);
		}
		final StringBuilder predicate = new StringBuilder();
		for (final String key : keys)
		{
			if (predicate.length() > 0)
			{
				predicate.append(" or ");
			}
			predicate.append("./").append(pPrimaryKeyField).append('=')
				.append(pKeyType == KeyType.NUMERIC ? MatchingRunScope.requireNumericLiteral(key) : MatchingRunScope.quote(key));
		}
		return new Decision(true, predicate.toString(), null);
	}

	/**
	 * A {@link KeyType#NUMERIC} key rendered unquoted - but only once it is checked to actually look like a
	 * number (an optional leading {@code -}, digits, an optional {@code .} and more digits): embedding an
	 * unquoted, unchecked value in an XPath predicate would either be silently wrong (a bad comparison the add-on
	 * would not catch) or, worse, let an unexpected value change the predicate's own structure - lot R9's whole
	 * point is to fail loudly on a type mismatch, not to trade one silent wrong-type bug for another.
	 *
	 * @throws IllegalStateException naming the exact offending value if it does not look like a number.
	 */
	private static String requireNumericLiteral(final String pKey)
	{
		if (!pKey.matches("-?\\d+(\\.\\d+)?"))
		{
			throw new IllegalStateException("MatchingRunScope.all: '" + pKey + "' was declared a NUMERIC master key but does not look like a number.");
		}
		return pKey;
	}

	/** Same doubling convention {@code ebx.CatalogReader.quote} uses for an XPath string literal - duplicated here since this class must stay pure (no EBX package dependency). */
	private static String quote(final String pValue)
	{
		return "'" + pValue.replace("'", "''") + "'";
	}
}
