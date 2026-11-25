package com.on.ebx.factory.capability;

import java.util.LinkedHashMap;
import java.util.Map;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class CapabilityNatureRouteTest
{
	public static void main(final String[] pArgs)
	{
		CapabilityNatureRouteTest.routesByNature();
		CapabilityNatureRouteTest.refusalNamesTheNatureAndWhatCanBeApplied();
		CapabilityNatureRouteTest.partitionKeepsOrderAndSeparatesNatures();
		System.out.println("CapabilityNatureRouteTest OK");
	}

	private static void routesByNature()
	{
		CapabilityNatureRouteTest.check("constraint", CapabilityNatureRoute.route("SCRIPT_CONSTRAINT") == CapabilityNatureRoute.Route.CONSTRAINT);
		CapabilityNatureRouteTest.check("derivation", CapabilityNatureRoute.route("SCRIPT_DERIVATION") == CapabilityNatureRoute.Route.DERIVATION);
		CapabilityNatureRouteTest.check("external service is refused", CapabilityNatureRoute.route("EXTERNAL_SERVICE") == CapabilityNatureRoute.Route.REFUSED);
		CapabilityNatureRouteTest.check("blank is refused", CapabilityNatureRoute.route("") == CapabilityNatureRoute.Route.REFUSED);
		CapabilityNatureRouteTest.check("null is refused", CapabilityNatureRoute.route(null) == CapabilityNatureRoute.Route.REFUSED);
		CapabilityNatureRouteTest.check("a lower-case spelling is not guessed", CapabilityNatureRoute.route("script_derivation") == CapabilityNatureRoute.Route.REFUSED);
	}

	private static void refusalNamesTheNatureAndWhatCanBeApplied()
	{
		final String service = CapabilityNatureRoute.refusalMessage("12", "EXTERNAL_SERVICE");
		CapabilityNatureRouteTest.check("names the id and the nature: " + service, service.contains("'12'") && service.contains("EXTERNAL_SERVICE"));
		CapabilityNatureRouteTest.check("says what can be applied", service.contains("SCRIPT_CONSTRAINT") && service.contains("SCRIPT_DERIVATION"));
		CapabilityNatureRouteTest.check("an unknown nature is worded too", CapabilityNatureRoute.refusalMessage("3", null).contains("unknown nature"));
	}

	private static void partitionKeepsOrderAndSeparatesNatures()
	{
		final Map<String, String> natures = new LinkedHashMap<String, String>();
		natures.put("107", "SCRIPT_DERIVATION");
		natures.put("11", "SCRIPT_CONSTRAINT");
		natures.put("12", "EXTERNAL_SERVICE");
		natures.put("108", "SCRIPT_DERIVATION");
		natures.put("99", null);
		CapabilityNatureRouteTest.check("derivations in order", CapabilityNatureRoute.idsOfRoute(natures, CapabilityNatureRoute.Route.DERIVATION).toString().equals("[107, 108]"));
		CapabilityNatureRouteTest.check("constraints", CapabilityNatureRoute.idsOfRoute(natures, CapabilityNatureRoute.Route.CONSTRAINT).toString().equals("[11]"));
		CapabilityNatureRouteTest.check("refused (service and unknown id)", CapabilityNatureRoute.idsOfRoute(natures, CapabilityNatureRoute.Route.REFUSED).toString().equals("[12, 99]"));
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
