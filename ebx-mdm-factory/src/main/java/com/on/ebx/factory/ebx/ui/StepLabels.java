package com.on.ebx.factory.ebx.ui;

import java.util.Locale;

/**
 * The label of a pipeline step kind ({@code PipelineStep.step}) or of a background task kind, for the Cockpit and the project explorer.
 * <p>
 * 2026-10-07 (non-administrator test of the audit): 21 step kinds had no {@code step.*} label, and the Cockpit and the explorer showed the raw
 * key ({@code step.PROJECT_BACKUP}). Order: the step's own label, else the task kind's label ({@code wizard.jobs.kind.*}, the kinds that are
 * recorded as steps too), else a readable form of the name ("Intake trigger install") - never a raw key.
 */
public final class StepLabels
{
	private StepLabels()
	{
	}

	public static String of(final Locale pLocale, final String pStep)
	{
		if (pStep == null || pStep.trim().isEmpty())
		{
			return "";
		}
		final String step = pStep.trim();
		final String stepKey = "step." + step;
		final String stepLabel = Messages.get(StepLabels.class, pLocale, stepKey);
		if (!stepKey.equals(stepLabel))
		{
			return stepLabel;
		}
		final String kindKey = "wizard.jobs.kind." + step;
		final String kindLabel = Messages.get(StepLabels.class, pLocale, kindKey);
		if (!kindKey.equals(kindLabel))
		{
			return kindLabel;
		}
		return StepLabels.readable(step);
	}

	/** {@code INTAKE_TRIGGER_INSTALL} -> {@code Intake trigger install}. Pure. */
	static String readable(final String pName)
	{
		final String words = pName.replace('_', ' ').trim().toLowerCase(Locale.ENGLISH);
		return words.isEmpty() ? pName : Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}
}
