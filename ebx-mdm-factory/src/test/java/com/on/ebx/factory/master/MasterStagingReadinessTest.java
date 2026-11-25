package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;

import com.on.ebx.factory.master.MasterStagingReadiness.Decision;
import com.on.ebx.factory.master.MasterStagingReadiness.EntityLoad;

/** JUnit-free: run the main, a failure throws. */
public final class MasterStagingReadinessTest
{
	public static void main(final String[] pArgs)
	{
		// the exact live incident: PipelineSteps.isDone wrongly answered false (a separate, now-fixed bug), but
		// the entities genuinely have load evidence - must still proceed
		Decision d = MasterStagingReadiness.decide(false, Arrays.asList(new EntityLoad("Company", 107L), new EntityLoad("Contact", 89L)));
		MasterStagingReadinessTest.check("step wrongly not-done, but entities have evidence: ready", d.ready);
		MasterStagingReadinessTest.check("no notLoaded reported when ready", d.notLoaded.isEmpty());

		// the step itself says DONE: ready regardless of what the entities show (e.g. a fresh project with rows
		// not yet re-read, or a genuinely empty but validly-run load)
		d = MasterStagingReadiness.decide(true, Collections.singletonList(new EntityLoad("Company", 0L)));
		MasterStagingReadinessTest.check("step DONE alone is enough: ready", d.ready);

		// at least one - not every - promoted entity needs evidence
		d = MasterStagingReadiness.decide(false, Arrays.asList(new EntityLoad("Company", 107L), new EntityLoad("Contact", 0L)));
		MasterStagingReadinessTest.check("one of two promoted entities loaded: still ready", d.ready);

		// neither signal holds for any promoted entity: refused, naming every one
		d = MasterStagingReadiness.decide(false, Arrays.asList(new EntityLoad("Company", 0L), new EntityLoad("Contact", 0L)));
		MasterStagingReadinessTest.check("no step, no evidence anywhere: not ready", !d.ready);
		MasterStagingReadinessTest.check("both promoted entities named as not loaded", d.notLoaded.equals(Arrays.asList("Company", "Contact")));

		// no promoted entity at all (should not normally reach this decision - MasterGenerator refuses earlier
		// for "nothing promoted" - but the pure function itself must still answer sensibly)
		d = MasterStagingReadiness.decide(false, Collections.<EntityLoad>emptyList());
		MasterStagingReadinessTest.check("no promoted entities and no step done: not ready", !d.ready);
		MasterStagingReadinessTest.check("nothing to name", d.notLoaded.isEmpty());

		System.out.println("MasterStagingReadinessTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
