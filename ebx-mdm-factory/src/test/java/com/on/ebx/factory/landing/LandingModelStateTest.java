package com.on.ebx.factory.landing;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.on.ebx.factory.landing.LandingModelState.Decision;
import com.on.ebx.factory.landing.LandingModelState.Found;

/** JUnit-free: run the main, a failure throws. */
public final class LandingModelStateTest
{
	public static void main(final String[] pArgs)
	{
		final List<String> planned = Arrays.asList("/root/SUPPLIER", "/root/CRM_SOURCE_SUPPLIER");

		Decision d = LandingModelState.decide(Found.NOTHING, "ACME_Landing_ERP", "ERP", planned, Collections.<String>emptyList());
		LandingModelStateTest.check("nothing exists: build and publish", d.ok() && d.build && d.publish);

		d = LandingModelState.decide(Found.MODEL_ONLY, "ACME_Landing_ERP", "ERP", planned, planned);
		LandingModelStateTest.check("model only, tables match: skip the build, still publish", d.ok() && !d.build && d.publish);

		d = LandingModelState.decide(Found.MODEL_AND_PUBLICATION, "ACME_Landing_ERP", "ERP", planned, planned);
		LandingModelStateTest.check("model and publication, tables match: nothing left to do", d.ok() && !d.build && !d.publish);

		// the server does not promise an order
		d = LandingModelState.decide(Found.MODEL_AND_PUBLICATION, "ACME_Landing_ERP", "ERP", planned, Arrays.asList("/root/CRM_SOURCE_SUPPLIER", "/root/SUPPLIER"));
		LandingModelStateTest.check("table order does not matter", d.ok() && !d.build && !d.publish);

		d = LandingModelState.decide(Found.MODEL_ONLY, "ACME_Landing_ERP", "ERP", planned, Arrays.asList("/root/SomethingElse"));
		LandingModelStateTest.check("a model with different tables refuses", !d.ok() && d.refusal.contains("ACME_Landing_ERP") && d.refusal.contains("ERP"));

		d = LandingModelState.decide(Found.MODEL_AND_PUBLICATION, "ACME_Landing_ERP", "ERP", planned, Arrays.asList("/root/SUPPLIER"));
		LandingModelStateTest.check("a model missing one planned table refuses even if already published", !d.ok());

		d = LandingModelState.decide(Found.MODEL_ONLY, "ACME_Landing_ERP", "ERP", planned, Arrays.asList("/root/SUPPLIER", "/root/CRM_SOURCE_SUPPLIER", "/root/Extra"));
		LandingModelStateTest.check("a model with an extra table refuses too", !d.ok());

		d = LandingModelState.decide(Found.MODEL_ONLY, "ACME_Landing_ERP", "ERP", planned, null);
		LandingModelStateTest.check("no existing tables reported at all refuses", !d.ok());

		System.out.println("LandingModelStateTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
