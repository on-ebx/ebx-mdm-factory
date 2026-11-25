package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. Live bug fix (coordinator, 2026-09-24): pins {@link
 * StagingLoader#attributeEqualsPredicate}, the one place an attribute id becomes an XPath equality
 * predicate - the exact spot a null id (a cross-project entity-name collision resolving
 * {@code StagingLoader.attributeId} to the wrong project's entity, see that method's own Javadoc) used to
 * reach an unguarded {@code "./attribute=" + CatalogReader.quote(attributeId)} and produce the malformed
 * XPath {@code MalformedXPathPredicateException} seen live on project Vague1
 * ({@code StagingLoadRun} step 265, 2026-09-24).
 */
public final class StagingLoaderTest
{
	public static void main(final String[] pArgs)
	{
		StagingLoaderTest.nullIdBuildsNoPredicate();
		StagingLoaderTest.blankIdBuildsNoPredicate();
		StagingLoaderTest.aRealIdBuildsAQuotedEqualityPredicate();
		StagingLoaderTest.aValueCarryingAnApostropheIsEscaped();
		StagingLoaderTest.sqlLiteralsFollowTheColumnKind();
		StagingLoaderTest.aFailingExampleQueryDegradesInsteadOfThrowing();
		System.out.println("StagingLoaderTest OK");
	}

	private static void nullIdBuildsNoPredicate()
	{
		StagingLoaderTest.check("a null id builds no predicate at all - never './attribute=' + literal null",
			StagingLoader.attributeEqualsPredicate(null) == null);
	}

	private static void blankIdBuildsNoPredicate()
	{
		StagingLoaderTest.check("an empty id builds no predicate", StagingLoader.attributeEqualsPredicate("") == null);
		StagingLoaderTest.check("a whitespace-only id builds no predicate", StagingLoader.attributeEqualsPredicate("   ") == null);
	}

	private static void aRealIdBuildsAQuotedEqualityPredicate()
	{
		final String predicate = StagingLoader.attributeEqualsPredicate("42");
		StagingLoaderTest.check("predicate: " + predicate, "./attribute='42'".equals(predicate));
	}

	private static void aValueCarryingAnApostropheIsEscaped()
	{
		// not a realistic attribute id (always a numeric primary key), but proves the predicate builder never
		// hands back a string that would itself break the XPath it is embedded in
		final String predicate = StagingLoader.attributeEqualsPredicate("O'Brien");
		StagingLoaderTest.check("apostrophe doubled: " + predicate, "./attribute='O''Brien'".equals(predicate));
	}

	/** Lot M11 (job 642): a DATE key's value must never reach the query as the java.util.Date text. */
	private static void sqlLiteralsFollowTheColumnKind()
	{
		final java.util.Calendar cal = java.util.Calendar.getInstance();
		cal.clear();
		cal.set(2018, java.util.Calendar.APRIL, 1);
		final java.util.Date april1 = cal.getTime();
		StagingLoaderTest.check("date: " + StagingLoader.sqlLiteralFor(april1, "date"), "DATE '2018-04-01'".equals(StagingLoader.sqlLiteralFor(april1, "date")));
		StagingLoaderTest.check("date, no kind known: " + StagingLoader.sqlLiteralFor(april1, null), "DATE '2018-04-01'".equals(StagingLoader.sqlLiteralFor(april1, null)));
		cal.set(2018, java.util.Calendar.APRIL, 1, 13, 5, 9);
		StagingLoaderTest.check("dateTime: " + StagingLoader.sqlLiteralFor(cal.getTime(), "dateTime"),
			"TIMESTAMP '2018-04-01 13:05:09'".equals(StagingLoader.sqlLiteralFor(cal.getTime(), "dateTime")));
		StagingLoaderTest.check("dateTime, no kind known", "TIMESTAMP '2018-04-01 13:05:09'".equals(StagingLoader.sqlLiteralFor(cal.getTime(), null)));
		cal.set(java.util.Calendar.MILLISECOND, 250);
		StagingLoaderTest.check("dateTime with millis: " + StagingLoader.sqlLiteralFor(cal.getTime(), "dateTime"),
			"TIMESTAMP '2018-04-01 13:05:09.250'".equals(StagingLoader.sqlLiteralFor(cal.getTime(), "dateTime")));
		cal.clear();
		cal.set(1970, java.util.Calendar.JANUARY, 1, 8, 30, 0);
		StagingLoaderTest.check("time: " + StagingLoader.sqlLiteralFor(cal.getTime(), "time"), "TIME '08:30:00'".equals(StagingLoader.sqlLiteralFor(cal.getTime(), "time")));
		StagingLoaderTest.check("time, no kind known", "TIME '08:30:00'".equals(StagingLoader.sqlLiteralFor(cal.getTime(), null)));
		StagingLoaderTest.check("a java.sql.Timestamp is a date too", StagingLoader.sqlLiteralFor(new java.sql.Timestamp(april1.getTime()), "date").startsWith("DATE '2018-04-01"));
		StagingLoaderTest.check("local date", "DATE '2018-04-01'".equals(StagingLoader.sqlLiteralFor(java.time.LocalDate.of(2018, 4, 1), null)));
		StagingLoaderTest.check("decimal is a bare number", "12.50".equals(StagingLoader.sqlLiteralFor(new java.math.BigDecimal("12.50"), "decimal")));
		StagingLoaderTest.check("a big decimal has no exponent", "100000000".equals(StagingLoader.sqlLiteralFor(new java.math.BigDecimal("1E+8"), "decimal")));
		StagingLoaderTest.check("int is a bare number", "42".equals(StagingLoader.sqlLiteralFor(Integer.valueOf(42), "int")));
		StagingLoaderTest.check("long is a bare number", "42".equals(StagingLoader.sqlLiteralFor(Long.valueOf(42L), null)));
		StagingLoaderTest.check("boolean true", "TRUE".equals(StagingLoader.sqlLiteralFor(Boolean.TRUE, "boolean")));
		StagingLoaderTest.check("boolean false", "FALSE".equals(StagingLoader.sqlLiteralFor(Boolean.FALSE, "boolean")));
		StagingLoaderTest.check("a string stays quoted, apostrophe doubled", "'O''Brien'".equals(StagingLoader.sqlLiteralFor("O'Brien", null)));
		StagingLoaderTest.check("null", "NULL".equals(StagingLoader.sqlLiteralFor(null, "date")));
	}

	/** Lot M11: the example query dies with an Error (job 642's ExceptionInInitializerError): the gathering keeps what it has, says why, never throws. */
	private static void aFailingExampleQueryDegradesInsteadOfThrowing()
	{
		final java.util.List<Object> values = new java.util.ArrayList<Object>();
		values.add("a");
		values.add("b");
		values.add("c");
		final StagingLoader.UniqueExamples ok = StagingLoader.gatherUniqueExamples(new StagingLoader.UniqueExampleQueries()
		{
			public java.util.List<Object> duplicatedValues()
			{
				return values;
			}

			public java.util.List<String> keysFor(final Object pValue, final int pLimit)
			{
				return java.util.Arrays.asList(pValue + "1", pValue + "2");
			}
		});
		StagingLoaderTest.check("all keys gathered: " + ok.keys, ok.keys.size() == 6 && ok.unavailableReason == null);

		final StagingLoader.UniqueExamples broken = StagingLoader.gatherUniqueExamples(new StagingLoader.UniqueExampleQueries()
		{
			public java.util.List<Object> duplicatedValues()
			{
				return values;
			}

			public java.util.List<String> keysFor(final Object pValue, final int pLimit)
			{
				if ("b".equals(pValue))
				{
					throw new ExceptionInInitializerError(new IllegalArgumentException("Invalid DATE value, 'Sun Apr 01 00:00:00 CEST 2018'"));
				}
				return java.util.Arrays.asList(pValue + "1");
			}
		});
		StagingLoaderTest.check("an Error from one value does not escape: " + broken.keys, broken.keys.size() == 2);
		StagingLoaderTest.check("the reason names the root cause: " + broken.unavailableReason,
			broken.unavailableReason != null && broken.unavailableReason.startsWith("IllegalArgumentException: Invalid DATE value"));

		final StagingLoader.UniqueExamples noGroups = StagingLoader.gatherUniqueExamples(new StagingLoader.UniqueExampleQueries()
		{
			public java.util.List<Object> duplicatedValues()
			{
				throw new ExceptionInInitializerError(new IllegalStateException("boom\nsecond line"));
			}

			public java.util.List<String> keysFor(final Object pValue, final int pLimit)
			{
				throw new AssertionError("not reached");
			}
		});
		StagingLoaderTest.check("a failing groups query: no keys, a one-line reason: " + noGroups.unavailableReason,
			noGroups.keys.isEmpty() && "IllegalStateException: boom second line".equals(noGroups.unavailableReason));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
