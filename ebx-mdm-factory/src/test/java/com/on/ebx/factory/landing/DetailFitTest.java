package com.on.ebx.factory.landing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. {@link DetailFit} (audit F-29): a step detail longer
 * than the column cap must still be valid JSON that gives the ownership readers ({@code ProjectDeleter}, {@code MasterReset},
 * {@code StepResetter}: {@code detail.dataspace.created}) the keys they look for.
 */
public final class DetailFitTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int CAP = TextLimits.DETAIL_MAX_LENGTH;

	public static void main(final String[] pArgs) throws Exception
	{
		DetailFitTest.check("null stays null", DetailFit.fit(null, CAP) == null);
		final String small = "{\"a\":1}";
		DetailFitTest.check("a detail under the cap is returned as is", small == DetailFit.fit(small, CAP));

		// the shape MasterGenerator/LandingGenerator write, with a bulky sibling after it
		final ObjectNode big = MAPPER.createObjectNode();
		final ObjectNode dataspace = big.putObject("dataspace");
		dataspace.put("key", "Master_PROJ");
		dataspace.put("created", true);
		big.putObject("dataset").put("created", false);
		final ArrayNode tables = big.putArray("tables");
		for (int i = 0; i < 4000; i++)
		{
			tables.addObject().put("name", "TABLE_" + i).put("result", "x1234567890123456789012345678901234567890");
		}
		big.put("transport", "session");
		final String original = big.toString();
		DetailFitTest.check("the fixture is over the cap", original.length() > CAP);

		final String fitted = DetailFit.fit(original, CAP);
		DetailFitTest.check("it fits the cap", fitted.length() <= CAP);
		final JsonNode parsed = MAPPER.readTree(fitted); // the old plain cut threw here, and the readers then saw "no evidence"
		DetailFitTest.check("the readers' key survives: dataspace.created", parsed.path("dataspace").path("created").asBoolean(false));
		DetailFitTest.check("and dataset.created is not turned into true", !parsed.path("dataset").path("created").asBoolean(true));
		DetailFitTest.check("the document says it was cut", parsed.path("truncated").asBoolean(false) && parsed.path("originalLength").asInt() == original.length());
		DetailFitTest.check("the bulky part is named as omitted, not silently gone", parsed.path("omitted").toString().contains("tables"));
		DetailFitTest.check("a small sibling after the bulky one is still kept", "session".equals(parsed.path("transport").asText()));

		// a bulky ownership object is reduced to its scalars, created included
		final ObjectNode bulkyOwner = MAPPER.createObjectNode();
		final ObjectNode owner = bulkyOwner.putObject("dataspace");
		owner.put("created", true);
		owner.put("key", "Master_X");
		final ArrayNode answer = owner.putArray("mcpAnswer");
		for (int i = 0; i < 6000; i++)
		{
			answer.add("line " + i + " of an exchange that goes on and on");
		}
		final String fittedOwner = DetailFit.fit(bulkyOwner.toString(), CAP);
		DetailFitTest.check("a bulky dataspace object still fits", fittedOwner.length() <= CAP);
		final JsonNode parsedOwner = MAPPER.readTree(fittedOwner);
		DetailFitTest.check("and keeps created and key", parsedOwner.path("dataspace").path("created").asBoolean(false) && "Master_X".equals(parsedOwner.path("dataspace").path("key").asText()));
		DetailFitTest.check("the array of the exchange is the part dropped", !parsedOwner.path("dataspace").has("mcpAnswer"));

		// the top-level created flag (FactoryRetention) and an invalid or non-object document
		final ObjectNode flag = MAPPER.createObjectNode();
		flag.put("created", true);
		flag.put("blob", DetailFitTest.repeat('z', CAP + 10));
		final JsonNode parsedFlag = MAPPER.readTree(DetailFit.fit(flag.toString(), CAP));
		DetailFitTest.check("a top-level created flag survives a huge text sibling", parsedFlag.path("created").asBoolean(false) && !parsedFlag.has("blob"));
		DetailFitTest.check("an invalid document falls back to the plain cut", DetailFit.fit(DetailFitTest.repeat('q', CAP + 5), CAP).length() == CAP);
		DetailFitTest.check("an array document falls back to the plain cut", DetailFit.fit("[" + DetailFitTest.repeat('1', CAP) + "]", CAP).length() == CAP);

		// an ownership scalar that is itself huge: still valid, still within the cap
		final ObjectNode hugeScalar = MAPPER.createObjectNode();
		hugeScalar.putObject("dataspace").put("created", true).put("note", DetailFitTest.repeat('n', CAP * 2));
		final String fittedScalar = DetailFit.fit(hugeScalar.toString(), CAP);
		final JsonNode parsedScalar = MAPPER.readTree(fittedScalar);
		DetailFitTest.check("a huge text inside the ownership object is cut short, created kept",
			fittedScalar.length() <= CAP && parsedScalar.path("dataspace").path("created").asBoolean(false));
		System.out.println("DetailFitTest OK");
	}

	private static String repeat(final char pChar, final int pCount)
	{
		final StringBuilder text = new StringBuilder(pCount);
		for (int i = 0; i < pCount; i++)
		{
			text.append(pChar);
		}
		return text.toString();
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
