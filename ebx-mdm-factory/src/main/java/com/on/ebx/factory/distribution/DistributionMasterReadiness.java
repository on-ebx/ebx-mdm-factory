package com.on.ebx.factory.distribution;

/**
 * Whether {@code ebx.DistributionRunner#readiness}'s "master built" fact may be trusted from the
 * {@code MASTER_LOAD} {@code PipelineStep}'s own {@code DONE} status alone - the same "step history is not
 * the only signal that should ever gate this" precedent {@code master.MasterStagingReadiness} already
 * established for {@code STAGING_LOAD} ({@code ebx.MasterGenerator}'s own guard): a {@code PipelineStep} row
 * can be missing or cleaned up (a reset, an older run, a step never recorded) even though the master zone
 * genuinely holds data.
 * <p>
 * Live incident, DistribTest project, 2026-09-25: {@code DistributionRunner.readiness}'s arming checklist
 * showed "master not built" although the master was built and loaded through the wizard's one-click
 * {@code MASTER_BUILD_RUN} (180 records). {@link #ready} accepts EITHER the step's own {@code DONE} status
 * OR direct evidence that the project's master zone actually holds a promoted table with data
 * ({@code ebx.DistributionRunner#masterZoneHasData}, which reuses {@code ebx.ProjectMasterEntities} - the
 * same shared helper this pass introduced to fix the sibling {@code StagingEntity.targetTable} bug in
 * {@code ebx.DistributionConfigurator}/{@code ebx.DistributionTriggerInstaller}).
 * <p>
 * Pure: no EBX class, unit-tested directly.
 */
public final class DistributionMasterReadiness
{
	private DistributionMasterReadiness()
	{
	}

	public static boolean ready(final boolean pMasterLoadStepDone, final boolean pMasterZoneHasData)
	{
		return pMasterLoadStepDone || pMasterZoneHasData;
	}
}
