package com.on.ebx.factory.master;

/**
 * Whether a {@code MASTER_LOAD} run counts as a genuine failure, not merely a partial one to report and move
 * on from. `docs/MASTER-ZONE.md` &sect;10's own incident, MEASURED LIVE 2026-09-22 12:39: {@code master/load}
 * rejected all 196 of 196 rows read (Company's own {@code id} field had no auto-increment facet on the live
 * model - the very defect {@link MasterFacetRepair} and the surviving-publication fix elsewhere in this same
 * incident both close), yet {@code ebx.MasterLoader} ended the {@code MASTER_LOAD} step {@code DONE} with
 * "Transferred 0 of 196" - a Cockpit reading "load complete" for a load that wrote NOTHING is misleading, and
 * worse, indistinguishable from a genuinely empty, fully-successful run of the same shape.
 * <p>
 * <b>Deliberately not {@code written == 0} alone.</b> A run where every read row was legitimately
 * SKIP_MERGED (already merged into a golden record elsewhere - {@code master.MasterUpsertDecision}'s own
 * SKIP_MERGED outcome, see its Javadoc) also writes nothing, and that is a genuine success, not a failure -
 * {@code written == 0} alone would misclassify it as a total failure too. The rule instead asks the one
 * question that actually distinguishes the two: did every row that WAS READ end up REJECTED (a real problem,
 * recorded in {@code TransferReject}) rather than skipped for a legitimate reason. This also matches the
 * incident's own numbers exactly: 196 read, 196 rejected, 0 written - not merely 0 written on its own.
 * <p>
 * The staging load applies the same rule to its own totals ({@code ebx.StagingLoader}): a step, a job stage and a workflow task must not read "done" for a load
 * that wrote nothing because every row was refused.
 * <p>
 * Pure: no EBX class in this file, the same convention as every other {@code *Decision}/{@code *Rule} class
 * in this package - unit-tested without an EBX runtime.
 */
public final class MasterLoadOutcome
{
	private MasterLoadOutcome()
	{
	}

	/**
	 * @param pRead total rows read across every promoted entity this run
	 * @param pRejected total rows rejected (written to {@code TransferReject}) across every promoted entity this run
	 * @return true when the run is a genuine failure: at least one row was read, and every single one of them
	 *         was rejected. Never true for an empty run ({@code pRead == 0}: nothing to load is not a
	 *         failure), never true for a run where some or all unwritten rows were legitimately skipped as
	 *         already-merged rather than rejected, and never true for a partial rejection (some written, some
	 *         rejected) - that stays a reportable, non-failing DONE, exactly as before this class existed.
	 */
	public static boolean isTotalFailure(final long pRead, final long pRejected)
	{
		return pRead > 0 && pRejected == pRead;
	}
}
