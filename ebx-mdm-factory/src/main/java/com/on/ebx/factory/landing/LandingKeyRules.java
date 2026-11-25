package com.on.ebx.factory.landing;

import java.util.Collection;

/**
 * Small pure rules about a landing table's key and the paused run (2026-10-17, fix/landing-validated-key). No EBX class.
 */
public final class LandingKeyRules
{
	private LandingKeyRules()
	{
	}

	/**
	 * Whether {@code ebx.LandingRun#reconcileWithActualTableState} may settle the KEY cell of a table that is built ({@code GENERATED}) from the mere existence of key
	 * columns. It may NOT when the cell is {@link LandingRunProgress#WAITING}: the run put the table there because nobody had confirmed its key, and key columns on a
	 * built table are often only the proposal a planning job stored (origin RULE, 60 %). Settling it "confirmed" hid the key chooser while FLOW and LOAD, still
	 * waiting for the key, were never resumed - the run closed with tables not loaded (project TiersB2CRetestStaging, 2026-10-17). A waiting key is settled by a person
	 * through the chooser ({@code applyKeyAndResume}), which also resumes the run; resuming from a status poll would apply an unconfirmed key and write from a read path,
	 * so reconciliation leaves the cell alone.
	 *
	 * @param pTableHasKey at least one {@code LandingColumn} of the table is flagged as key
	 * @param pKeyCellState the run's KEY cell state for the table
	 */
	public static boolean reconcileMayConfirmKey(final boolean pTableHasKey, final String pKeyCellState)
	{
		return pTableHasKey && !LandingRunProgress.WAITING.equals(pKeyCellState);
	}

	/**
	 * Whether {@code applyKeyAndResume} may write the chosen key as a FIRST choice (bypassing the "table already generated" refusal of an ordinary key change): the table
	 * was built while it waited for its key (the TABLE stage is per source) and never loaded, so no landing row carries a {@code _sourceId} computed from another key.
	 * Whether the table already holds a proposed key (origin RULE, written by a planning job) no longer matters: that is exactly the waiting table of the live case.
	 */
	public static boolean firstChoiceAllowed(final String pTableStatus, final String pLastLoadId)
	{
		return "GENERATED".equals(pTableStatus) && pLastLoadId == null;
	}

	/** Whether the walkthrough page of an application offers the key chooser for this table: the last run left it waiting for its key. */
	public static boolean waitsInRun(final Collection<String> pWaitingTableIds, final String pTableId)
	{
		return pWaitingTableIds != null && pTableId != null && pWaitingTableIds.contains(pTableId);
	}
}
