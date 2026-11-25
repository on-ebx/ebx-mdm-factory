package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.on.ebx.factory.master.MasterModelState.Decision;
import com.on.ebx.factory.master.MasterModelState.Found;

/** JUnit-free: run the main, a failure throws. Mirrors {@code staging.StagingModelStateTest} for the master zone's own three-state decision. */
public final class MasterModelStateTest
{
	public static void main(final String[] pArgs)
	{
		final List<String> planned = Arrays.asList("/root/Company", "/root/Contact");

		Decision d = MasterModelState.decide(Found.NOTHING, "COMPANIES360_Master", "COMPANIES360", planned, Collections.<String>emptyList());
		MasterModelStateTest.check("nothing exists: build, patch and publish, never regenerate", d.ok() && d.build && d.patch && d.publish && !d.regenerate);

		d = MasterModelState.decide(Found.MODEL_ONLY, "COMPANIES360_Master", "COMPANIES360", planned, planned);
		MasterModelStateTest.check("model only, tables match: skip the build, but still patch and publish", d.ok() && !d.build && d.patch && d.publish && !d.regenerate);

		d = MasterModelState.decide(Found.MODEL_AND_PUBLICATION, "COMPANIES360_Master", "COMPANIES360", planned, planned);
		MasterModelStateTest.check("model and publication, tables match: still routed to the diff (regenerate), never a refusal",
			d.ok() && !d.build && !d.patch && !d.publish && d.regenerate);

		// the exact live case this generator exists for: Contact promoted after Company alone was already published
		d = MasterModelState.decide(Found.MODEL_AND_PUBLICATION, "COMPANIES360_Master", "COMPANIES360", planned, Arrays.asList("/root/Company"));
		MasterModelStateTest.check("a model missing one planned table regenerates instead of refusing, now that it is published", d.ok() && d.regenerate);

		d = MasterModelState.decide(Found.MODEL_ONLY, "COMPANIES360_Master", "COMPANIES360",
			Arrays.asList("/root/Company", "/root/Contact", "/root/Supplier"), planned);
		MasterModelStateTest.check("MODEL_ONLY drift still refuses (never published, so nothing was ever at risk of losing data)", !d.ok() && !d.regenerate);
		MasterModelStateTest.check("the refusal names the project and refuses to adopt it",
			d.refusal.contains("COMPANIES360") && d.refusal.toLowerCase().contains("refusing to adopt"));

		d = MasterModelState.decide(Found.MODEL_ONLY, "COMPANIES360_Master", "COMPANIES360", planned, null);
		MasterModelStateTest.check("MODEL_ONLY: no existing tables reported at all still refuses", !d.ok() && !d.regenerate);

		System.out.println("MasterModelStateTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
