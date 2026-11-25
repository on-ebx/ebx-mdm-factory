package com.on.ebx.factory.deducedscan;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.factory.landing.KeyLlmPass;

/** JUnit-free: run the main, a failure throws. Exercises {@link NameKeyPass.Session}'s tool handler directly - no real LLM call. */
public final class NameKeyPassTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final ObjectMapper mapper = new ObjectMapper();
		final List<ColumnMeasurer.Measured> measured = new ArrayList<ColumnMeasurer.Measured>();
		measured.add(new ColumnMeasurer.Measured("id", ColumnMeasurer.NUMBER, 25, 25, 25, "51"));
		measured.add(new ColumnMeasurer.Measured("properties.firstname", ColumnMeasurer.TEXT, 25, 25, 23, "Maria"));

		final NameKeyPass.Session session = new NameKeyPass.Session(measured);
		NameKeyPassTest.check("no proposal before any call", session.proposal() == null);

		final JsonNode goodArgs = mapper
			.readTree("{\"tableName\":\"Contact\",\"keyColumns\":[\"id\"],\"confidence\":94,\"rationale\":\"id is filled and different on every row.\"}");
		final LocalTool.Result goodResult = session.proposeTable(goodArgs);
		NameKeyPassTest.check("a valid proposal is accepted", !goodResult.isError);
		NameKeyPassTest.check("the proposal is recorded", session.proposal() != null);
		NameKeyPassTest.check("table name recorded", "Contact".equals(session.proposal().tableName));
		NameKeyPassTest.check("key columns recorded", session.proposal().keyColumns.contains("id"));
		NameKeyPassTest.check("confidence recorded", session.proposal().confidence == 94);

		final NameKeyPass.Session badSession = new NameKeyPass.Session(measured);
		final JsonNode badArgs = mapper.readTree("{\"tableName\":\"Contact\",\"keyColumns\":[\"madeUpColumn\"],\"confidence\":50,\"rationale\":\"x\"}");
		final LocalTool.Result badResult = badSession.proposeTable(badArgs);
		NameKeyPassTest.check("a key column not measured is refused", badResult.isError);
		NameKeyPassTest.check("a refused call leaves no proposal", badSession.proposal() == null);

		final NameKeyPass.Session clampSession = new NameKeyPass.Session(measured);
		final JsonNode outOfRangeArgs = mapper.readTree("{\"tableName\":\"Contact\",\"keyColumns\":[\"id\"],\"confidence\":250,\"rationale\":\"x\"}");
		clampSession.proposeTable(outOfRangeArgs);
		NameKeyPassTest.check("an out-of-range confidence is clamped, not refused", clampSession.proposal().confidence == 100);

		NameKeyPassTest.promptFollowsTheDataPolicy();
		System.out.println("NameKeyPassTest OK");
	}

	/** The example of a column is a real source value: absent under NONE, shaped under SHAPES, withheld for a sensitive column at any level. */
	private static void promptFollowsTheDataPolicy()
	{
		final String marker = "Zqxmarker7";
		final List<ColumnMeasurer.Measured> measured = new ArrayList<ColumnMeasurer.Measured>();
		measured.add(new ColumnMeasurer.Measured("id", ColumnMeasurer.NUMBER, 25, 25, 25, "51"));
		measured.add(new ColumnMeasurer.Measured("properties.firstname", ColumnMeasurer.TEXT, 25, 25, 23, marker));
		measured.add(new ColumnMeasurer.Measured("properties.iban", ColumnMeasurer.TEXT, 25, 25, 25, "FR7612345678" + marker));
		final java.util.Set<String> withheld = new java.util.HashSet<String>(java.util.Arrays.asList("Properties.IBAN"));
		final java.util.Set<String> none = java.util.Collections.<String>emptySet();

		final String underNone = NameKeyPass.userPrompt("listContacts", measured, KeyLlmPass.Sharing.NONE, none);
		NameKeyPassTest.check("NONE: no marker", !underNone.contains(marker) && !underNone.contains("\"51\""));
		NameKeyPassTest.check("NONE: names and counts still go", underNone.contains("properties.firstname: text, 25/25 filled, 23 distinct")
			&& underNone.contains("withheld by the data policy"));

		final String underShapes = NameKeyPass.userPrompt("listContacts", measured, KeyLlmPass.Sharing.SHAPES, withheld);
		NameKeyPassTest.check("SHAPES: no marker, the shape instead", !underShapes.contains(marker) && underShapes.contains("MASKED SHAPES")
			&& underShapes.contains("example \"" + com.on.ebx.factory.quality.Shapes.of(marker) + "\""));
		NameKeyPassTest.check("SHAPES: a numeric id becomes 99", underShapes.contains("example \"99\""));
		NameKeyPassTest.check("SHAPES: the sensitive column gives no shape either", !underShapes.contains("FR76") && !underShapes.contains("AA99"));

		final String underValues = NameKeyPass.userPrompt("listContacts", measured, KeyLlmPass.Sharing.VALUES, withheld);
		NameKeyPassTest.check("VALUES: the real example of a plain column goes", underValues.contains("example \"" + marker + "\""));
		NameKeyPassTest.check("VALUES: the sensitive column never gives its value", !underValues.contains("FR7612345678"));
		final String longOne = NameKeyPass.userPrompt("op", java.util.Arrays.asList(new ColumnMeasurer.Measured("note", ColumnMeasurer.TEXT, 1, 1, 1,
			"0123456789012345678901234567890123456789012345678901234567890123456789")), KeyLlmPass.Sharing.VALUES, none);
		NameKeyPassTest.check("VALUES: an example is cut", !longOne.contains("0123456789012345678901234567890123456789012"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
