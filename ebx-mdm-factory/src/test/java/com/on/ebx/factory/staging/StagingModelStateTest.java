package com.on.ebx.factory.staging;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.on.ebx.factory.staging.StagingModelState.Decision;
import com.on.ebx.factory.staging.StagingModelState.Found;

/** JUnit-free: run the main, a failure throws. */
public final class StagingModelStateTest
{
	public static void main(final String[] pArgs)
	{
		final List<String> planned = Arrays.asList("/root/Account", "/root/Contact");

		Decision d = StagingModelState.decide(Found.NOTHING, "COMPANIES360_Staging", "COMPANIES360", planned, Collections.<String>emptyList());
		StagingModelStateTest.check("nothing exists: build, patch and publish, never regenerate", d.ok() && d.build && d.patch && d.publish && !d.regenerate);

		// the exact defect the coordinator flagged: a run resumed at MODEL_ONLY (built, never published -
		// died, or the publish call itself failed) must still re-apply the facets before publishing, or a
		// staging model with no maxLength and no mandatory field gets published silently wrong
		d = StagingModelState.decide(Found.MODEL_ONLY, "COMPANIES360_Staging", "COMPANIES360", planned, planned);
		StagingModelStateTest.check("model only, tables match: skip the build, but still patch and publish", d.ok() && !d.build && d.patch && d.publish && !d.regenerate);

		// round 4 (2026-09-20): a published model is now ALWAYS routed through the field-level StagingModelDiff,
		// whether or not the table set itself still matches - a new optional field on an existing table would
		// otherwise be missed by a table-path-only comparison
		d = StagingModelState.decide(Found.MODEL_AND_PUBLICATION, "COMPANIES360_Staging", "COMPANIES360", planned, planned);
		StagingModelStateTest.check("model and publication, tables match: still routed to the diff (regenerate), never a refusal",
			d.ok() && !d.build && !d.patch && !d.publish && d.regenerate);

		// the server does not promise an order - still regenerate, same as above
		d = StagingModelState.decide(Found.MODEL_AND_PUBLICATION, "COMPANIES360_Staging", "COMPANIES360", planned, Arrays.asList("/root/Contact", "/root/Account"));
		StagingModelStateTest.check("table order does not matter, still regenerate", d.ok() && !d.build && !d.patch && !d.publish && d.regenerate);

		// the exact live case round 4 asked for: Company accepted after the model was already published - a
		// published model missing a planned table no longer refuses, it regenerates (StagingGenerator's diff
		// then classifies the missing table as an ADDITIVE new-table change and creates it)
		d = StagingModelState.decide(Found.MODEL_AND_PUBLICATION, "COMPANIES360_Staging", "COMPANIES360", planned, Arrays.asList("/root/Account"));
		StagingModelStateTest.check("a model missing one planned table regenerates instead of refusing, now that it is published",
			d.ok() && d.regenerate);

		// MODEL_ONLY keeps its original, narrower behaviour: out of scope for round 4 (never published, so
		// nothing has ever been at risk of losing data) - still a non-destructive refusal, not a regenerate
		d = StagingModelState.decide(Found.MODEL_ONLY, "COMPANIES360_Staging", "COMPANIES360", Arrays.asList("/root/Account", "/root/Contact", "/root/Supplier"), planned);
		StagingModelStateTest.check("MODEL_ONLY drift still refuses without asking to rename or delete anything",
			!d.ok() && !d.regenerate && !d.refusal.toLowerCase().contains("rename") && !d.refusal.toLowerCase().contains("delete"));
		StagingModelStateTest.check("the refusal names the project and says regeneration is not supported yet",
			d.refusal.contains("COMPANIES360") && d.refusal.toLowerCase().contains("regeneration"));

		d = StagingModelState.decide(Found.MODEL_ONLY, "COMPANIES360_Staging", "COMPANIES360", planned, null);
		StagingModelStateTest.check("MODEL_ONLY: no existing tables reported at all still refuses", !d.ok() && !d.regenerate);

		System.out.println("StagingModelStateTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
