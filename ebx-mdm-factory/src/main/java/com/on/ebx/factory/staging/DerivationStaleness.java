package com.on.ebx.factory.staging;

import java.util.Collection;
import java.util.Date;
import java.util.regex.Pattern;

/**
 * Fix/derivation-apply-backfill (2026-10-17, lot G1, item 2): which rows of a staging table hold a STALE value in an applied script derivation, i.e. were written
 * before the derivation was applied (or while its backfill could not run) and will never be recomputed by a trigger on their own.
 * <p>
 * <b>Measured live</b> (TiersB2CRetestFinal, capability 226, {@code Client.mobileE164}, 534 rows): the derivation was applied at 18:42:50 with its backfill SKIPPED (the
 * apply job's own STAGING claim, {@code DerivationBackfillPass}); the next "Build and load" read every landing row as unchanged ({@code _sourceHash} equal, compare-before-write)
 * and wrote nothing, so no {@code beforeModify} trigger ran and the column stayed empty on all rows - with no way out in the UI. The loader never writes an applied derivation, so
 * a row's staleness cannot be seen by comparing planned and stored values; it has to be asked of the row itself.
 * <p>
 * <b>The rule.</b> A derivation applied at {@code appliedAt} computes a row's value only when that row is WRITTEN after the apply (the triggers) or backfilled. So a row is stale
 * when its last write is not after {@code appliedAt} and it still has something to compute ({@link #rowStale}). That is exact per row, but reading the write time of every row costs
 * a scan; two cheaper questions cover the cases that matter:
 * <ul>
 * <li>the TABLE's last modification date is not after {@code appliedAt}: nothing was written since the apply, so every row is stale ({@link Need#ALL_ROWS});</li>
 * <li>otherwise the rows with an EMPTY derived column and a filled input ({@link #emptyDerivedPredicate}, an index-served XPath predicate) are the candidates, and only those
 * written before the apply are stale ({@link Need#EMPTY_DERIVED}) - a row the library legitimately answers nothing for (an invalid phone) is written once by the repair and is then
 * newer than {@code appliedAt}, so it is never rewritten again.</li>
 * </ul>
 * A filled-but-outdated value on a table that was partly rewritten since the apply is not found by these two questions (it would need the write time of every row); the re-apply
 * route ({@code POST .../apply}, which ends with a full backfill) remains the way to recompute everything. Pure: no EBX class.
 */
public final class DerivationStaleness
{
	/** What a load has to do about one applied derivation. */
	public enum Need
	{
		/** Nothing: no rows, parameters changed (the triggers are stale themselves: re-apply first), or the apply time is unknown. */
		NONE,
		/** Recompute the rows with an empty derived value and a filled input that were written before the apply. */
		EMPTY_DERIVED,
		/** Recompute every row with an input or a value: nothing was written to the table since the apply. */
		ALL_ROWS
	}

	private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private DerivationStaleness()
	{
	}

	/**
	 * @param pRows the rows of the staging table
	 * @param pAppliedAt {@code CapabilitySuggestion.appliedAt}, {@code null} when unknown
	 * @param pTableLastModification {@code AdaptationTable.getLastModificationDate()}, {@code null} when unknown (read as "not after the apply": the safe, wider direction)
	 * @param pReapplyNeeded the parameters changed since the apply: the live triggers carry the old ones, recomputing now would store old-parameter values
	 */
	public static Need decide(final long pRows, final Date pAppliedAt, final Date pTableLastModification, final boolean pReapplyNeeded)
	{
		if (pRows <= 0L || pAppliedAt == null || pReapplyNeeded)
		{
			return Need.NONE;
		}
		if (pTableLastModification == null || !pTableLastModification.after(pAppliedAt))
		{
			return Need.ALL_ROWS;
		}
		return Need.EMPTY_DERIVED;
	}

	/**
	 * Whether one row is stale: it has an input or a value to recompute and its last write is not after the apply. A row with no input and no value has nothing to compute; a
	 * row whose write time is unknown is not touched (the repair never guesses).
	 */
	public static boolean rowStale(final boolean pAnySourceFilled, final boolean pDerivedFilled, final Date pRowLastWrite, final Date pAppliedAt)
	{
		if (pRowLastWrite == null || pAppliedAt == null || pRowLastWrite.after(pAppliedAt))
		{
			return false;
		}
		return pAnySourceFilled || pDerivedFilled;
	}

	/** Whether a row with an empty derived value is a candidate of {@link Need#EMPTY_DERIVED}: an input is filled, the derived value is not. */
	public static boolean emptyDerivedCandidate(final boolean pAnySourceFilled, final boolean pDerivedFilled)
	{
		return pAnySourceFilled && !pDerivedFilled;
	}

	/**
	 * The XPath predicate that selects the candidates of {@link Need#EMPTY_DERIVED} (derived value empty, at least one input filled), or {@code null} when it cannot be written
	 * safely: no input, or a name that is not a plain identifier (names come from the factory's own attributes, never from a person's text, but a predicate is never built from an
	 * unchecked name).
	 */
	public static String emptyDerivedPredicate(final String pDerivedField, final Collection<String> pSourceFields)
	{
		if (pDerivedField == null || !SAFE_NAME.matcher(pDerivedField).matches() || pSourceFields == null || pSourceFields.isEmpty())
		{
			return null;
		}
		final StringBuilder sources = new StringBuilder();
		for (final String source : pSourceFields)
		{
			if (source == null || !SAFE_NAME.matcher(source).matches())
			{
				return null;
			}
			if (sources.length() > 0)
			{
				sources.append(" or ");
			}
			sources.append("osd:is-not-null(./").append(source).append(')');
		}
		return "osd:is-null(./" + pDerivedField + ") and (" + sources + ")";
	}

	/** The sentence the load keeps for a repair that recomputed rows ({@code entity.attribute}, how many, why). */
	public static String repairedNote(final String pEntity, final String pAttribute, final long pRecomputed, final Need pNeed)
	{
		return pEntity + "." + pAttribute + ": " + pRecomputed + " existing row(s) recomputed - "
			+ (pNeed == Need.ALL_ROWS ? "the derivation was applied after they were written and nothing had been written to the table since"
				: "their derived value was empty although their input is filled, and they were written before the derivation was applied");
	}
}
