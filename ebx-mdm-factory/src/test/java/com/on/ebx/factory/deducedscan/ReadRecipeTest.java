package com.on.ebx.factory.deducedscan;

import java.util.LinkedHashMap;
import java.util.Map;

/** JUnit-free: run the main, a failure throws. */
public final class ReadRecipeTest
{
	public static void main(final String[] pArgs)
	{
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		inputs.put("after", "0");
		final ReadRecipe recipe = new ReadRecipe("HubSpot", "list_contacts", inputs, "results");
		final String json = recipe.toJson();
		ReadRecipeTest.check("connector in json", json.contains("\"connector\":\"HubSpot\""));
		ReadRecipeTest.check("operation in json", json.contains("\"operation\":\"list_contacts\""));
		ReadRecipeTest.check("itemsPath in json", json.contains("\"itemsPath\":\"results\""));
		ReadRecipeTest.check("inputs object in json", json.contains("\"after\":\"0\""));

		final ReadRecipe roundTrip = ReadRecipe.parse(json);
		ReadRecipeTest.check("round trip connector", "HubSpot".equals(roundTrip.connector));
		ReadRecipeTest.check("round trip operation", "list_contacts".equals(roundTrip.operation));
		ReadRecipeTest.check("round trip itemsPath", "results".equals(roundTrip.itemsPath));
		ReadRecipeTest.check("round trip inputs", "0".equals(roundTrip.inputs.get("after")));

		// empty itemsPath (the answer's own array IS the item list) round-trips as empty, not omitted-as-null
		final ReadRecipe noItemsPath = new ReadRecipe("Salesforce", "query", new LinkedHashMap<String, String>(), "");
		ReadRecipeTest.check("blank itemsPath round trip", "".equals(ReadRecipe.parse(noItemsPath.toJson()).itemsPath));

		ReadRecipeTest.check("null json never throws", ReadRecipe.parse(null).connector.isEmpty());
		ReadRecipeTest.check("blank json never throws", ReadRecipe.parse("   ").connector.isEmpty());
		ReadRecipeTest.check("garbage json never throws", ReadRecipe.parse("{not json").connector.isEmpty());

		// fix lot (2026-09-26, defect 9): equals/hashCode are what ebx.DeducedScanCatalog#findByRecipe matches
		// an existing Asset on - same connector/operation/itemsPath/inputs, whatever the proposed table name is.
		final Map<String, String> sameInputs = new LinkedHashMap<String, String>();
		sameInputs.put("after", "0");
		final ReadRecipe sameRecipe = new ReadRecipe("HubSpot", "list_contacts", sameInputs, "results");
		ReadRecipeTest.check("same connector/operation/inputs/itemsPath is equal", recipe.equals(sameRecipe));
		ReadRecipeTest.check("equal recipes hash the same", recipe.hashCode() == sameRecipe.hashCode());
		ReadRecipeTest.check("a different operation is not equal", !recipe.equals(new ReadRecipe("HubSpot", "list_companies", sameInputs, "results")));
		final Map<String, String> differentInputs = new LinkedHashMap<String, String>();
		differentInputs.put("after", "100");
		ReadRecipeTest.check("a different input value is not equal", !recipe.equals(new ReadRecipe("HubSpot", "list_contacts", differentInputs, "results")));
		ReadRecipeTest.check("never equal to null", !recipe.equals(null));

		System.out.println("ReadRecipeTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
