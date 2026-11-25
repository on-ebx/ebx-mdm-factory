package com.on.ebx.factory.delivery;

import java.util.Arrays;
import java.util.Collections;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free: run the main, a failure throws. */
public final class DeliveryPreCheckTest
{
	public static void main(final String[] pArgs)
	{
		final DeliveryPreCheck.Connector snowflake = new DeliveryPreCheck.Connector("SNOW_DEMO", Arrays.asList("ERP", "CRM"), true, true, false, true);
		final DeliveryPreCheck.Connector missing = new DeliveryPreCheck.Connector("OLD_ERP", Collections.singletonList("ERP"), false, false, false, false);
		final DeliveryPreCheck.Connector inactive = new DeliveryPreCheck.Connector("KAFKA", Collections.singletonList("CRM"), true, false, true, false);
		final DeliveryPreCheck.Reference countries = new DeliveryPreCheck.Reference("COUNTRY", "Geographies", "Geographies", true);
		final DeliveryPreCheck.Reference legalForms = new DeliveryPreCheck.Reference("LEGAL_FORM", "Companies", "Companies", false);

		final DeliveryPreCheck.Result ok = DeliveryPreCheck.check(Collections.singletonList(snowflake), Collections.singletonList(countries), true, true, true, true);
		DeliveryPreCheckTest.check("all good: ok", ok.ok);
		DeliveryPreCheckTest.check("the connector to create is listed with its secret to set", ok.items.stream().anyMatch(i -> "CONNECTOR_TO_CREATE".equals(i.code)
			&& i.message.contains("SNOW_DEMO") && i.message.contains("ERP, CRM") && i.message.contains("password") && !i.message.contains("apiKey")));
		DeliveryPreCheckTest.check("the reference dataset is listed as needed", ok.items.stream().anyMatch(i -> "REFERENCE_NEEDED".equals(i.code) && i.message.contains("Geographies/Geographies")));
		DeliveryPreCheckTest.check("the add-on is listed as needed", ok.items.stream().anyMatch(i -> "ADDON_NEEDED".equals(i.code)));

		final DeliveryPreCheck.Result bad = DeliveryPreCheck.check(Arrays.asList(snowflake, missing, inactive), Arrays.asList(countries, legalForms), true, false, true, true);
		DeliveryPreCheckTest.check("a missing connector or add-on is an error", !bad.ok);
		DeliveryPreCheckTest.check("missing connector named", bad.items.stream().anyMatch(i -> "CONNECTOR_MISSING".equals(i.code) && i.message.contains("OLD_ERP")));
		DeliveryPreCheckTest.check("inactive connector warned, still to create", bad.items.stream().anyMatch(i -> "CONNECTOR_INACTIVE".equals(i.code) && i.message.contains("KAFKA"))
			&& bad.items.stream().anyMatch(i -> "CONNECTOR_TO_CREATE".equals(i.code) && i.message.contains("KAFKA") && i.message.contains("apiKey")));
		DeliveryPreCheckTest.check("missing reference dataset warned", bad.items.stream().anyMatch(i -> "REFERENCE_MISSING".equals(i.code) && i.message.contains("Companies/Companies")));
		DeliveryPreCheckTest.check("add-on missing is an error", bad.items.stream().anyMatch(i -> "ADDON_MISSING".equals(i.code) && DeliveryPreCheck.ERROR.equals(i.level)));

		final DeliveryPreCheck.Result noConnectors = DeliveryPreCheck.check(Collections.<DeliveryPreCheck.Connector> emptyList(),
			Collections.<DeliveryPreCheck.Reference> emptyList(), false, false, false, true);
		DeliveryPreCheckTest.check("Connectors absent with a landing to deliver: a warning, not an error", noConnectors.ok
			&& noConnectors.items.stream().anyMatch(i -> "CONNECTORS_ABSENT".equals(i.code)));
		DeliveryPreCheckTest.check("no matching delivered: nothing about the add-on", noConnectors.items.stream().noneMatch(i -> i.code.startsWith("ADDON")));

		// measured 2026-09-22: a toolbar button of Companies_Master passed xpath=/root/Project[./code='Companies'] and EBX
		// Staging's import answered HTTP 500 ("Invalid step [Project[.]: there is no closing bracket") before any component
		final DeliveryPreCheck.Result toolbar = DeliveryPreCheck.check(Collections.<DeliveryPreCheck.Connector> emptyList(),
			Collections.<DeliveryPreCheck.Reference> emptyList(), false, false, true, true, Arrays.asList(
				new DeliveryPreCheck.ToolbarParameter("Companies_Master", "ebx-mdm-factory@/WEB-INF/ebx/schemas/MdmFactory.xsd@FactoryCockpit", "xpath", "/root/Project[./code='Companies']"),
				new DeliveryPreCheck.ToolbarParameter("Companies_Master", "ebx-mdm-factory@RecordLineageOnRecord", "branch", "MdmFactory")));
		DeliveryPreCheckTest.check("an xpath with a predicate in a toolbar parameter is an error naming model, button and value",
			!toolbar.ok && toolbar.items.stream().anyMatch(i -> "TOOLBAR_PREDICATE".equals(i.code) && i.message.contains("Companies_Master")
				&& i.message.contains("FactoryCockpit") && i.message.contains("xpath=/root/Project[./code='Companies']")));
		DeliveryPreCheckTest.check("a plain parameter value is not flagged", toolbar.items.stream().filter(i -> "TOOLBAR_PREDICATE".equals(i.code)).count() == 1);
		DeliveryPreCheckTest.check("blocksStaging: bracket only", DeliveryPreCheck.blocksStaging("/root/T[./a='1']") && !DeliveryPreCheck.blocksStaging("/root/T") && !DeliveryPreCheck.blocksStaging(null));

		final DeliveryPreCheck.Result libraries = DeliveryPreCheck.check(Collections.<DeliveryPreCheck.Connector> emptyList(),
			Collections.<DeliveryPreCheck.Reference> emptyList(), false, false, true, true, Collections.<DeliveryPreCheck.ToolbarParameter> emptyList(),
			Arrays.asList("Fasttrack.lib.phone", "Fasttrack.lib.validator.regex"), Collections.singletonList("Nowhere.lib"));
		DeliveryPreCheckTest.check("a missing library is an error naming it", !libraries.ok && libraries.items.stream().anyMatch(i -> "LIBRARY_MISSING".equals(i.code) && i.message.contains("Nowhere.lib")));
		DeliveryPreCheckTest.check("delivered libraries are listed to publish, in order", libraries.items.stream().anyMatch(i -> "LIBRARIES_TO_PUBLISH".equals(i.code)
			&& i.message.contains("Fasttrack.lib.phone, Fasttrack.lib.validator.regex") && DeliveryPreCheck.INFO.equals(i.level)));

		final ObjectNode json = DeliveryPreCheck.toJson(bad, new ObjectMapper().createObjectNode());
		DeliveryPreCheckTest.check("json carries ok and items with level/code/message", !json.get("ok").asBoolean() && json.get("items").get(0).has("level")
			&& json.get("items").get(0).has("code") && json.get("items").get(0).has("message"));
		System.out.println("DeliveryPreCheckTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
