package com.on.ebx.factory.wizard;

import java.util.List;

/**
 * Sources-landing live fix (2026-09-24, project A, source PERSONNES_SAP_SAMPLE): the Review screen's "no
 * table yet" line reads the same "has not run yet, or found none" wording whether a landing plan genuinely
 * never ran for this application, or ran once and was later invalidated by a step reset - measured live:
 * {@code ProjectSource} 9's four {@code LANDING_MODEL}/{@code LANDING_DATASET}/{@code LANDING_FLOWS}/
 * {@code LANDING_LOAD} {@code PipelineStep} rows all read {@code RESET} (a previous run's history, not
 * deleted - see {@code PipelineStep.status}'s own description: "a DONE step a later master/reset
 * invalidated"), with the {@code LandingTable} row it had produced gone - so {@code WizardSnapshot.SourceInfo
 * #tables} is empty exactly as it is for an application whose plan never ran at all. A person reading "has
 * not run yet" has no reason to suspect anything ran before, or that re-running the plan (not adding
 * anything new) is what is needed. This is a pure decision over the source's own latest-per-step statuses
 * ({@link WizardSnapshot.SourceInfo#pipelineSteps}, already read by {@code WizardData.readPipelineSteps}) -
 * no EBX access, so it lives here and not in ebx.WizardData.
 */
public final class LandingTableHint
{
	private LandingTableHint()
	{
	}

	/**
	 * True when at least one of the source's LANDING_* steps last ran and was RESET - the Review screen's
	 * "no table yet" line should then say the landing was reset, not that it never ran.
	 */
	public static boolean wasReset(final List<WizardSnapshot.PipelineStepInfo> pSteps)
	{
		if (pSteps == null)
		{
			return false;
		}
		for (final WizardSnapshot.PipelineStepInfo step : pSteps)
		{
			if (step != null && "RESET".equals(step.status))
			{
				return true;
			}
		}
		return false;
	}
}
