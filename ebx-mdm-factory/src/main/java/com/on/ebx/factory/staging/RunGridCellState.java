package com.on.ebx.factory.staging;

/**
 * Wizard-visual-pass lot (2026-09-24): the pure decision behind one cell's state in the step 4e/step 5
 * "business object x column" grid ({@code ebx.StagingLoadGridReader}/{@code ebx.MasterBuildGridReader}
 * both call it) - extracted so the decision itself is unit-tested without a live repository, per the task
 * brief's "tests for every pure piece (progress model, grid/state decisions)".
 */
public final class RunGridCellState
{
	public static final String DONE = "DONE";
	public static final String WAITING = "WAITING";
	public static final String NEEDS_PERSON = "NEEDS_PERSON";

	private RunGridCellState()
	{
	}

	/**
	 * The staging-application cell state ({@code ebx.StagingLoadGridReader}): {@link #WAITING} when the
	 * staging table itself does not exist yet (model/load never ran); {@link #NEEDS_PERSON} when every row
	 * from this application was held back (rows loaded is zero or unknown, but at least one was rejected -
	 * nothing landed, someone must look); {@link #DONE} otherwise (some or all rows loaded, whether or not
	 * a few were also held back).
	 */
	public static String forApplicationCell(final boolean pTableExists, final long pRowsLoaded, final long pRowsHeldBack)
	{
		if (!pTableExists)
		{
			return RunGridCellState.WAITING;
		}
		return pRowsHeldBack > 0 && pRowsLoaded <= 0 ? RunGridCellState.NEEDS_PERSON : RunGridCellState.DONE;
	}

	/** A cell that only ever reports {@link #DONE} once its underlying step finished, {@link #WAITING} otherwise - e.g. the master grid's "Tables"/"Matching ready" columns, and the staging grid's "In staging" column. */
	public static String forStepCell(final boolean pStepDone)
	{
		return pStepDone ? RunGridCellState.DONE : RunGridCellState.WAITING;
	}
}
