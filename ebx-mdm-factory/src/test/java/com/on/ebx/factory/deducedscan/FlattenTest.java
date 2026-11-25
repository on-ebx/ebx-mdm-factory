package com.on.ebx.factory.deducedscan;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free: run the main, a failure throws. */
public final class FlattenTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final ObjectMapper mapper = new ObjectMapper();

		final Map<String, String> flat1 = Flatten.row(mapper.readTree("{\"id\":\"51\",\"properties\":{\"firstname\":\"Maria\",\"email\":null},\"archived\":false}"));
		FlattenTest.check("nested object -> dotted path", "Maria".equals(flat1.get("properties.firstname")));
		FlattenTest.check("top-level scalar", "51".equals(flat1.get("id")));
		FlattenTest.check("null leaf is empty, not absent", "".equals(flat1.get("properties.email")));
		FlattenTest.check("boolean leaf as text", "false".equals(flat1.get("archived")));
		FlattenTest.check("field order preserved", "[id, properties.firstname, properties.email, archived]".equals(flat1.keySet().toString()));

		final Map<String, String> flat2 = Flatten.row(mapper.readTree("{\"tags\":[\"a\",\"b\"],\"empty\":[],\"num\":3.5}"));
		FlattenTest.check("array joined into one text value", "a; b".equals(flat2.get("tags")));
		FlattenTest.check("empty array is empty text", "".equals(flat2.get("empty")));
		FlattenTest.check("number leaf as text", "3.5".equals(flat2.get("num")));

		final Map<String, String> flat3 = Flatten.row(mapper.readTree("{\"a\":{\"b\":{\"c\":\"x\"}}}"));
		FlattenTest.check("deep nesting stays dotted", "x".equals(flat3.get("a.b.c")));

		final Map<String, String> flat4 = Flatten.row(mapper.readTree("{\"empty\":{}}"));
		FlattenTest.check("empty object leaf is empty text", "".equals(flat4.get("empty")));

		System.out.println("FlattenTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
