package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JUnit-free, like {@code ApplicationStagesTest}: run the main, a failure throws. Exercises {@link LandingTableHint#wasReset}. */
public final class LandingTableHintTest
{
	public static void main(final String[] pArgs)
	{
		LandingTableHintTest.nullListIsNotAReset();
		LandingTableHintTest.emptyListIsNotAReset();
		LandingTableHintTest.everyStepDoneIsNotAReset();
		LandingTableHintTest.oneStepResetIsAReset();
		LandingTableHintTest.everyStepResetIsAReset();

		System.out.println("LandingTableHintTest OK");
	}

	// never run at all: no PipelineStep row exists yet, pipelineSteps is empty - "no table yet" (never ran),
	// not "was reset".
	private static void nullListIsNotAReset()
	{
		LandingTableHintTest.check("null", !LandingTableHint.wasReset(null));
	}

	private static void emptyListIsNotAReset()
	{
		LandingTableHintTest.check("empty", !LandingTableHint.wasReset(new ArrayList<WizardSnapshot.PipelineStepInfo>()));
	}

	private static void everyStepDoneIsNotAReset()
	{
		final List<WizardSnapshot.PipelineStepInfo> steps = Arrays.asList(new WizardSnapshot.PipelineStepInfo("LANDING_MODEL", "DONE", "ok", 0L),
			new WizardSnapshot.PipelineStepInfo("LANDING_LOAD", "DONE", "ok", 0L));
		LandingTableHintTest.check("all done", !LandingTableHint.wasReset(steps));
	}

	// the live bug itself, reproduced: project A / ProjectSource 9 - all four LANDING_* steps read RESET,
	// with no LandingTable row left.
	private static void everyStepResetIsAReset()
	{
		final List<WizardSnapshot.PipelineStepInfo> steps = Arrays.asList(new WizardSnapshot.PipelineStepInfo("LANDING_MODEL", "RESET", "Landing model built and published.", 0L),
			new WizardSnapshot.PipelineStepInfo("LANDING_DATASET", "RESET", "Landing dataspace and dataset ready.", 0L),
			new WizardSnapshot.PipelineStepInfo("LANDING_FLOWS", "RESET", "Landing flow generated for 1 table(s).", 0L),
			new WizardSnapshot.PipelineStepInfo("LANDING_LOAD", "RESET", "Loaded 1 landing table(s).", 0L));
		LandingTableHintTest.check("all reset", LandingTableHint.wasReset(steps));
	}

	// a partial reset (e.g. a later step reset that did not touch every step's own latest row) is still a
	// reset - one RESET step is enough to say so rather than "never ran".
	private static void oneStepResetIsAReset()
	{
		final List<WizardSnapshot.PipelineStepInfo> steps = Arrays.asList(new WizardSnapshot.PipelineStepInfo("LANDING_MODEL", "DONE", "ok", 0L),
			new WizardSnapshot.PipelineStepInfo("LANDING_LOAD", "RESET", "was reset", 0L));
		LandingTableHintTest.check("one reset is enough", LandingTableHint.wasReset(steps));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
