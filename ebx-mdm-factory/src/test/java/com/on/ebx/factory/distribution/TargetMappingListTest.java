package com.on.ebx.factory.distribution;

import java.util.LinkedHashMap;
import java.util.Map;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class TargetMappingListTest
{
	public static void main(final String[] pArgs)
	{
		TargetMappingListTest.roundTrips();
		TargetMappingListTest.emptyParsesEmpty();
		TargetMappingListTest.nullParsesEmpty();
		TargetMappingListTest.skipsMalformedEntries();
		System.out.println("TargetMappingListTest OK");
	}

	private static void roundTrips()
	{
		final Map<String, String> map = new LinkedHashMap<String, String>();
		map.put("/root/Company", "41");
		map.put("/root/Contact", "42");
		final String formatted = TargetMappingList.format(map);
		TargetMappingListTest.check("format joins with commas", "/root/Company=41,/root/Contact=42".equals(formatted));
		final Map<String, String> parsed = TargetMappingList.parse(formatted);
		TargetMappingListTest.check("round-trips", map.equals(parsed));
	}

	private static void emptyParsesEmpty()
	{
		TargetMappingListTest.check("blank parses to an empty map", TargetMappingList.parse("").isEmpty());
		TargetMappingListTest.check("whitespace-only parses to an empty map", TargetMappingList.parse("   ").isEmpty());
	}

	private static void nullParsesEmpty()
	{
		TargetMappingListTest.check("null parses to an empty map", TargetMappingList.parse(null).isEmpty());
	}

	private static void skipsMalformedEntries()
	{
		final Map<String, String> parsed = TargetMappingList.parse("/root/Company=41,garbage,=42,/root/Contact=");
		TargetMappingListTest.check("only the well-formed entry survives", parsed.size() == 1 && "41".equals(parsed.get("/root/Company")));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
