package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * JUnit-free: run the main, a failure throws. Lot F1b (2026-10-19): the EBX SQL literal of a key value, from the Java type of the field. Live (scenario run 7, 2026-10-19 21:08) the coverage read wrote
 * {@code c."birthDate" IN ('Tue Jan 20 00:00:00 CET 2026')} for a date key and Calcite's generated code failed with "Invalid DATE value". The value given is the field's XS string.
 */
public final class SqlLiteralsTest
{
	public static void main(final String[] pArgs)
	{
		SqlLiteralsTest.aDateIsADateLiteral();
		SqlLiteralsTest.aDateTimeIsATimestampLiteral();
		SqlLiteralsTest.aTimeIsATimeLiteral();
		SqlLiteralsTest.numbersAreBare();
		SqlLiteralsTest.aBooleanIsTrueOrFalse();
		SqlLiteralsTest.aStringIsQuotedAndAQuoteIsDoubled();
		SqlLiteralsTest.aValueThatDoesNotReadAsItsTypeIsQuotedNotInjected();
		SqlLiteralsTest.nullAndUnknownTypes();
		SqlLiteralsTest.theDateToStringOfTheLiveFailureIsNeverAnXsString();
		SqlLiteralsTest.theValueOfARowIsItsXsStringWhenTheNodeIsUnknownItIsToString();
		System.out.println("SqlLiteralsTest OK");
	}

	private static void eq(final String pMessage, final String pExpected, final String pActual)
	{
		if (!pExpected.equals(pActual))
		{
			throw new AssertionError(pMessage + ": expected " + pExpected + " but got " + pActual);
		}
	}

	private static void aDateIsADateLiteral()
	{
		SqlLiteralsTest.eq("date", "DATE '2026-01-20'", SqlLiterals.literal(java.util.Date.class, "2026-01-20"));
		SqlLiteralsTest.eq("date with a zone", "DATE '2026-01-20'", SqlLiterals.literal(java.util.Date.class, "2026-01-20+01:00"));
		SqlLiteralsTest.eq("date with Z", "DATE '1997-06-23'", SqlLiterals.literal(java.util.Date.class, "1997-06-23Z"));
		SqlLiteralsTest.eq("a sql date class is a date too", "DATE '2026-01-20'", SqlLiterals.literal(java.sql.Date.class, "2026-01-20"));
	}

	private static void aDateTimeIsATimestampLiteral()
	{
		SqlLiteralsTest.eq("dateTime", "TIMESTAMP '2026-01-20 10:30:05'", SqlLiterals.literal(java.util.Date.class, "2026-01-20T10:30:05"));
		SqlLiteralsTest.eq("with a fraction", "TIMESTAMP '2026-01-20 10:30:05'", SqlLiterals.literal(java.util.Date.class, "2026-01-20T10:30:05.250"));
		SqlLiteralsTest.eq("with a zone", "TIMESTAMP '2026-01-20 10:30:05'", SqlLiterals.literal(java.util.Date.class, "2026-01-20T10:30:05.000+01:00"));
		SqlLiteralsTest.eq("a timestamp class", "TIMESTAMP '2026-01-20 00:00:00'", SqlLiterals.literal(java.sql.Timestamp.class, "2026-01-20T00:00:00"));
	}

	private static void aTimeIsATimeLiteral()
	{
		SqlLiteralsTest.eq("time", "TIME '10:30:05'", SqlLiterals.literal(java.util.Date.class, "10:30:05"));
		SqlLiteralsTest.eq("time with a fraction", "TIME '10:30:05'", SqlLiterals.literal(java.util.Date.class, "10:30:05.5"));
	}

	private static void numbersAreBare()
	{
		SqlLiteralsTest.eq("integer", "42", SqlLiterals.literal(Integer.class, "42"));
		SqlLiteralsTest.eq("negative integer", "-7", SqlLiterals.literal(Integer.class, "-7"));
		SqlLiteralsTest.eq("long", "9876543210", SqlLiterals.literal(Long.class, "9876543210"));
		SqlLiteralsTest.eq("decimal", "12.50", SqlLiterals.literal(BigDecimal.class, "12.50"));
		SqlLiteralsTest.eq("big integer", "123456789012345678901234567890", SqlLiterals.literal(BigInteger.class, "123456789012345678901234567890"));
		SqlLiteralsTest.eq("a primitive class", "5", SqlLiterals.literal(int.class, "5"));
		SqlLiteralsTest.eq("a space around the value is not part of it", "3.14", SqlLiterals.literal(Double.class, " 3.14 "));
	}

	private static void aBooleanIsTrueOrFalse()
	{
		SqlLiteralsTest.eq("true", "TRUE", SqlLiterals.literal(Boolean.class, "true"));
		SqlLiteralsTest.eq("false", "FALSE", SqlLiterals.literal(Boolean.class, "false"));
		SqlLiteralsTest.eq("case", "TRUE", SqlLiterals.literal(Boolean.class, "TRUE"));
		SqlLiteralsTest.eq("primitive", "FALSE", SqlLiterals.literal(boolean.class, "false"));
	}

	private static void aStringIsQuotedAndAQuoteIsDoubled()
	{
		SqlLiteralsTest.eq("string", "'JULIE.RICHARD62@FREE.FR'", SqlLiterals.literal(String.class, "JULIE.RICHARD62@FREE.FR"));
		SqlLiteralsTest.eq("a quote", "'l''avenue d''Italie'", SqlLiterals.literal(String.class, "l'avenue d'Italie"));
		SqlLiteralsTest.eq("an unknown class is a string", "'x'", SqlLiterals.literal(null, "x"));
		SqlLiteralsTest.eq("the spaces of a string are kept", "' x '", SqlLiterals.literal(String.class, " x "));
		SqlLiteralsTest.eq("digits in a string field stay a string", "'0033612345678'", SqlLiterals.literal(String.class, "0033612345678"));
	}

	/** A value that is not what its field's type says is never written as code: it is a quoted string (the engine then answers no row, or refuses, both caught by the caller). */
	private static void aValueThatDoesNotReadAsItsTypeIsQuotedNotInjected()
	{
		SqlLiteralsTest.eq("date field, text value", "'x'' OR 1=1'", SqlLiterals.literal(java.util.Date.class, "x' OR 1=1"));
		SqlLiteralsTest.eq("number field, text value", "'1; DROP'", SqlLiterals.literal(Integer.class, "1; DROP"));
		SqlLiteralsTest.eq("number field, a decimal comma", "'12,5'", SqlLiterals.literal(BigDecimal.class, "12,5"));
		SqlLiteralsTest.eq("boolean field, text value", "'yes'", SqlLiterals.literal(Boolean.class, "yes"));
	}

	private static void nullAndUnknownTypes()
	{
		SqlLiteralsTest.eq("null", "NULL", SqlLiterals.literal(String.class, null));
		SqlLiteralsTest.eq("an object class", "'k'", SqlLiterals.literal(Object.class, "k"));
	}

	/** The text the live run sent: not a date literal, whatever the field. */
	private static void theDateToStringOfTheLiveFailureIsNeverAnXsString()
	{
		SqlLiteralsTest.eq("kept as a string, never DATE", "'Tue Jan 20 00:00:00 CET 2026'", SqlLiterals.literal(java.util.Date.class, "Tue Jan 20 00:00:00 CET 2026"));
	}

	/** Without a node to format with, a text stays itself, null stays null and anything else is {@code String.valueOf} (the node-backed path, {@code formatToXsString}, needs a repository). */
	private static void theValueOfARowIsItsXsStringWhenTheNodeIsUnknownItIsToString()
	{
		SqlLiteralsTest.eq("text", "abc", MatchPlanSimulationRunner.xsValue(null, "abc"));
		SqlLiteralsTest.eq("number", "5", MatchPlanSimulationRunner.xsValue(null, Integer.valueOf(5)));
		if (MatchPlanSimulationRunner.xsValue(null, null) != null)
		{
			throw new AssertionError("null stays null");
		}
	}
}
