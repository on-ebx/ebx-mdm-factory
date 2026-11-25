package com.on.ebx.factory.transfer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link StoredValueEquality} (lot V3-M, 2026-10-04). */
public final class StoredValueEqualityTest
{
	public static void main(final String[] pArgs)
	{
		StoredValueEqualityTest.nullAndEmptyAreNoValue();
		StoredValueEqualityTest.numbersCompareNumerically();
		StoredValueEqualityTest.datesCompareByTime();
		StoredValueEqualityTest.listsCompareElementWise();
		StoredValueEqualityTest.everythingElseByEquals();
		StoredValueEqualityTest.aDoubtIsADifference();
		System.out.println("StoredValueEqualityTest OK");
	}

	private static void nullAndEmptyAreNoValue()
	{
		StoredValueEqualityTest.check("null equals null", StoredValueEquality.sameValue(null, null));
		StoredValueEqualityTest.check("empty string equals null (EBX stores an empty string as null)", StoredValueEquality.sameValue("", null));
		StoredValueEqualityTest.check("null equals empty string", StoredValueEquality.sameValue(null, ""));
		StoredValueEqualityTest.check("empty equals empty", StoredValueEquality.sameValue("", ""));
		StoredValueEqualityTest.check("a value is not null", !StoredValueEquality.sameValue("a", null));
		StoredValueEqualityTest.check("null is not a value", !StoredValueEquality.sameValue(null, "a"));
		StoredValueEqualityTest.check("a blank string is a value, not the absence of one", !StoredValueEquality.sameValue(" ", null));
		StoredValueEqualityTest.check("zero is not null", !StoredValueEquality.sameValue(BigDecimal.ZERO, null));
		StoredValueEqualityTest.check("false is not null", !StoredValueEquality.sameValue(Boolean.FALSE, null));
		StoredValueEqualityTest.check("null list equals empty list", StoredValueEquality.sameValue(null, new ArrayList<Object>()));
		StoredValueEqualityTest.check("empty list equals null list", StoredValueEquality.sameValue(Collections.emptyList(), null));
	}

	private static void numbersCompareNumerically()
	{
		StoredValueEqualityTest.check("BigDecimal 10 equals 10.0 (compareTo, not equals)", StoredValueEquality.sameValue(new BigDecimal("10"), new BigDecimal("10.0")));
		StoredValueEqualityTest.check("Integer equals the same BigDecimal", StoredValueEquality.sameValue(Integer.valueOf(7), new BigDecimal("7")));
		StoredValueEqualityTest.check("BigDecimal equals the same Integer", StoredValueEquality.sameValue(new BigDecimal("7.00"), Integer.valueOf(7)));
		StoredValueEqualityTest.check("Long equals Integer", StoredValueEquality.sameValue(Long.valueOf(42), Integer.valueOf(42)));
		StoredValueEqualityTest.check("BigInteger equals Long", StoredValueEquality.sameValue(BigInteger.valueOf(5), Long.valueOf(5)));
		StoredValueEqualityTest.check("Double 1.5 equals BigDecimal 1.50", StoredValueEquality.sameValue(Double.valueOf(1.5), new BigDecimal("1.50")));
		StoredValueEqualityTest.check("different numbers differ", !StoredValueEquality.sameValue(Integer.valueOf(7), new BigDecimal("7.01")));
		StoredValueEqualityTest.check("zero differs from one", !StoredValueEquality.sameValue(Integer.valueOf(0), Integer.valueOf(1)));
		StoredValueEqualityTest.check("a negative differs from its absolute value", !StoredValueEquality.sameValue(Integer.valueOf(-3), Integer.valueOf(3)));
	}

	private static void datesCompareByTime()
	{
		StoredValueEqualityTest.check("same instant, two Date objects", StoredValueEquality.sameValue(new Date(1700000000000L), new Date(1700000000000L)));
		StoredValueEqualityTest.check("a java.sql.Timestamp equals the same instant as a Date",
			StoredValueEquality.sameValue(new Date(1700000000000L), new java.sql.Timestamp(1700000000000L)));
		StoredValueEqualityTest.check("the same instant the other way round", StoredValueEquality.sameValue(new java.sql.Timestamp(1700000000000L), new Date(1700000000000L)));
		StoredValueEqualityTest.check("one millisecond apart differ", !StoredValueEquality.sameValue(new Date(1700000000000L), new Date(1700000000001L)));
	}

	private static void listsCompareElementWise()
	{
		StoredValueEqualityTest.check("same elements", StoredValueEquality.sameValue(Arrays.asList("a", "b"), Arrays.asList("a", "b")));
		StoredValueEqualityTest.check("order matters", !StoredValueEquality.sameValue(Arrays.asList("a", "b"), Arrays.asList("b", "a")));
		StoredValueEqualityTest.check("size matters", !StoredValueEquality.sameValue(Arrays.asList("a"), Arrays.asList("a", "b")));
		StoredValueEqualityTest.check("a list against no list differs", !StoredValueEquality.sameValue(Arrays.asList("a"), null));
		StoredValueEqualityTest.check("elements use the same rule (numbers)",
			StoredValueEquality.sameValue(Arrays.<Object> asList(Integer.valueOf(1), Integer.valueOf(2)), Arrays.<Object> asList(new BigDecimal("1.0"), new BigDecimal("2"))));
		StoredValueEqualityTest.check("elements use the same rule (empty is null)", StoredValueEquality.sameValue(Arrays.<Object> asList("a", ""), Arrays.<Object> asList("a", null)));
		StoredValueEqualityTest.check("a different element differs", !StoredValueEquality.sameValue(Arrays.asList("a", "b"), Arrays.asList("a", "c")));
	}

	private static void everythingElseByEquals()
	{
		StoredValueEqualityTest.check("same string", StoredValueEquality.sameValue("CRM|001", "CRM|001"));
		StoredValueEqualityTest.check("a string is case sensitive", !StoredValueEquality.sameValue("Paris", "paris"));
		StoredValueEqualityTest.check("a string is not trimmed", !StoredValueEquality.sameValue("Paris ", "Paris"));
		StoredValueEqualityTest.check("same boolean", StoredValueEquality.sameValue(Boolean.TRUE, Boolean.TRUE));
		StoredValueEqualityTest.check("different boolean", !StoredValueEquality.sameValue(Boolean.TRUE, Boolean.FALSE));
		StoredValueEqualityTest.check("a formatted foreign key is a plain string on both sides", StoredValueEquality.sameValue("12345", "12345"));
		StoredValueEqualityTest.check("a different foreign key differs", !StoredValueEquality.sameValue("12345", "12346"));
	}

	/** Never through toString across types: whatever cannot be shown to be equal is reported different (the row is then written, as before the lot). */
	private static void aDoubtIsADifference()
	{
		StoredValueEqualityTest.check("the string '7' is not the number 7", !StoredValueEquality.sameValue("7", Integer.valueOf(7)));
		StoredValueEqualityTest.check("the number 7 is not the string '7'", !StoredValueEquality.sameValue(Integer.valueOf(7), "7"));
		StoredValueEqualityTest.check("a boolean is not its text", !StoredValueEquality.sameValue(Boolean.TRUE, "true"));
		StoredValueEqualityTest.check("a date is not its text", !StoredValueEquality.sameValue(new Date(0L), "1970-01-01"));
		StoredValueEqualityTest.check("a date is not a number", !StoredValueEquality.sameValue(new Date(5L), Long.valueOf(5L)));
		StoredValueEqualityTest.check("a list is not an element", !StoredValueEquality.sameValue(Arrays.asList("a"), "a"));
		StoredValueEqualityTest.check("NaN is only the same as NaN", StoredValueEquality.sameValue(Double.valueOf(Double.NaN), Double.valueOf(Double.NaN)));
		StoredValueEqualityTest.check("NaN is not a number", !StoredValueEquality.sameValue(Double.valueOf(Double.NaN), Integer.valueOf(0)));
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
