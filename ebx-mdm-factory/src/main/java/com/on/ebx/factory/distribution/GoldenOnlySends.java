package com.on.ebx.factory.distribution;

/**
 * Lot S2 (2026-10-18): the XPath predicate that keeps Match and Merge's MERGED and DELETED records out of a distribution send. Measured by reading
 * Connectors (main): the outbound scopes ({@code connectors_sync} with a scope, which is how the first full send and every scheduled send run) walk
 * the whole table; the {@code mergedRecords=exclude} input the Factory puts on each mapping is read by the data-product publication only. So a first
 * full send used to send every merged record, and every unmerge-deleted golden, as if they were live. The send now passes this predicate as the
 * {@code predicate} of {@code connectors_sync}, which narrows the table for a scope. The same rule as the publication's: MERGED (2) and DELETED (4) are
 * left out; GOLDEN, PENDING_APPROVAL and a record without metadata are sent. Pure, unit-tested.
 */
public final class GoldenOnlySends
{
	public static final int STATE_MERGED = 2;
	public static final int STATE_DELETED = 4;

	private GoldenOnlySends()
	{
	}

	/** The predicate for a table that carries {@code Linked_Field_State}; null for a table that does not (no Match and Merge on it: every record is live). */
	public static String predicate(final boolean pTableHasStateField)
	{
		if (!pTableHasStateField)
		{
			return null;
		}
		return "not(./Linked_Field_State = " + GoldenOnlySends.STATE_MERGED + " or ./Linked_Field_State = " + GoldenOnlySends.STATE_DELETED + ")";
	}

	/** Whether a record in this state ({@link RetireRules} vocabulary) may be sent as a live record. */
	public static boolean isLive(final String pState)
	{
		return !RetireRules.STATE_MERGED.equals(pState) && !RetireRules.STATE_DELETED.equals(pState);
	}
}
