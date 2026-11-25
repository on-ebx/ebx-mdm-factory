package com.on.ebx.factory.transfer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. {@link RowDigest} (lot V3-M2, 2026-10-04) must say "same"
 * exactly when {@link StoredValueEquality#sameValue} says so, field by field: pinned on a table of cases, on every ordered pair of a pool of
 * values, and on lists of values drawn from the pool.
 */
public final class RowDigestTest
{
	public static void main(final String[] pArgs)
	{
		RowDigestTest.tableOfCases();
		RowDigestTest.everyPairOfThePoolAgreesWithStoredValueEquality();
		RowDigestTest.randomRowsAgreeWithStoredValueEquality();
		RowDigestTest.fieldBoundariesAreUnambiguous();
		RowDigestTest.optionalFieldsAreComparedOnlyWhenPlanned();
		RowDigestTest.aValueNoEncodingCanStandForIsUnknown();
		RowDigestTest.layoutBuildsPlannedAndStoredDigests();
		RowDigestTest.wordsAreTheTwoHalves();
		System.out.println("RowDigestTest OK");
	}

	private static RowDigest one(final Object pValue)
	{
		return RowDigest.of(new Object[] { pValue }, null);
	}

	private static boolean sameDigest(final Object pPlanned, final Object pStored)
	{
		return RowDigestTest.one(pPlanned).sameAs(RowDigestTest.one(pStored));
	}

	private static void tableOfCases()
	{
		final Object[][] cases = {
			{ null, null }, { null, "" }, { "", null }, { "", "" }, { null, new ArrayList<Object>() }, { Collections.emptyList(), null }, { "", Collections.emptyList() },
			{ "a", null }, { null, "a" }, { " ", null }, { BigDecimal.ZERO, null }, { Boolean.FALSE, null },
			{ new BigDecimal("10"), new BigDecimal("10.0") }, { Integer.valueOf(10), new BigDecimal("10") }, { new BigDecimal("10.00"), Integer.valueOf(10) },
			{ Long.valueOf(10), Integer.valueOf(10) }, { BigInteger.TEN, Long.valueOf(10) }, { Double.valueOf(1.5), new BigDecimal("1.50") },
			{ new BigDecimal("0.00"), Integer.valueOf(0) }, { new BigDecimal("0"), new BigDecimal("0.000") }, { new BigDecimal("1E+3"), Integer.valueOf(1000) },
			{ new BigDecimal("100"), new BigDecimal("1E+2") }, { new BigDecimal("-3"), Integer.valueOf(3) }, { new BigDecimal("7"), new BigDecimal("7.01") },
			{ new Date(1700000000000L), new Date(1700000000000L) }, { new Date(1700000000000L), new java.sql.Timestamp(1700000000000L) },
			{ new java.sql.Timestamp(1700000000000L), new Date(1700000000000L) }, { new Date(1700000000000L), new Date(1700000000001L) },
			{ Arrays.asList("a", "b"), Arrays.asList("a", "b") }, { Arrays.asList("a", "b"), Arrays.asList("b", "a") }, { Arrays.asList("a"), Arrays.asList("a", "b") },
			{ Arrays.<Object> asList(Integer.valueOf(1), Integer.valueOf(2)), Arrays.<Object> asList(new BigDecimal("1.0"), new BigDecimal("2")) },
			{ Arrays.<Object> asList("a", ""), Arrays.<Object> asList("a", null) },
			{ Arrays.<Object> asList(Arrays.asList("x", "y"), "z"), Arrays.<Object> asList(Arrays.asList("x", "y"), "z") },
			{ Arrays.<Object> asList(Arrays.asList("x", "y"), "z"), Arrays.<Object> asList(Arrays.asList("x", "yz")) },
			{ "10", Integer.valueOf(10) }, { Integer.valueOf(10), "10" }, { "10", new BigDecimal("10.0") }, { "1", Integer.valueOf(1) },
			{ "true", Boolean.TRUE }, { Boolean.TRUE, Boolean.TRUE }, { Boolean.TRUE, Boolean.FALSE }, { "1970-01-01", new Date(0L) }, { new Date(5L), Long.valueOf(5L) },
			{ Arrays.asList("a"), "a" }, { Double.valueOf(Double.NaN), Double.valueOf(Double.NaN) }, { Double.valueOf(Double.NaN), Float.valueOf(Float.NaN) },
			{ Double.valueOf(Double.NaN), Integer.valueOf(0) }, { Double.valueOf(Double.POSITIVE_INFINITY), Double.valueOf(Double.POSITIVE_INFINITY) },
			{ Double.valueOf(Double.POSITIVE_INFINITY), Double.valueOf(Double.NEGATIVE_INFINITY) }, { "Paris", "paris" }, { "Paris ", "Paris" }, { "CRM|001", "CRM|001" },
			{ "12345", "12346" }, { "\uD800", "\uD801" }, { "\uD800", "?" },
		};
		for (final Object[] pair : cases)
		{
			RowDigestTest.agree(pair[0], pair[1]);
		}
		RowDigestTest.check("the string 10 and the number 10 differ", !RowDigestTest.sameDigest("10", Integer.valueOf(10)));
		RowDigestTest.check("10 and 10.0 and Integer 10 are one value", RowDigestTest.sameDigest(new BigDecimal("10"), new BigDecimal("10.0"))
			&& RowDigestTest.sameDigest(Integer.valueOf(10), new BigDecimal("10.00")));
		RowDigestTest.check("a huge exponent does not allocate a plain string", RowDigestTest.sameDigest(new BigDecimal("1E+1000000000"), new BigDecimal("1E+1000000000")));
	}

	private static List<Object> pool()
	{
		final List<Object> pool = new ArrayList<Object>();
		pool.addAll(Arrays.<Object> asList(null, "", "a", "A", "10", "10.0", "true", " ", "CRM|1", "\uD800", Integer.valueOf(0), Integer.valueOf(10), Long.valueOf(10), Short.valueOf((short) 10),
			new BigDecimal("0"), new BigDecimal("0.0"), new BigDecimal("10"), new BigDecimal("10.0"), new BigDecimal("1E+1"), new BigDecimal("10.0001"), new BigDecimal("-10"),
			BigInteger.TEN, Double.valueOf(10.0), Double.valueOf(0.1), new BigDecimal("0.1"), Float.valueOf(0.1f), Double.valueOf(Double.NaN), Float.valueOf(Float.NaN),
			Double.valueOf(Double.POSITIVE_INFINITY), Boolean.TRUE, Boolean.FALSE, new Date(0L), new Date(1700000000000L), new java.sql.Timestamp(1700000000000L),
			new java.sql.Timestamp(0L), new java.sql.Date(0L)));
		pool.add(new ArrayList<Object>());
		pool.add(Arrays.<Object> asList("a"));
		pool.add(Arrays.<Object> asList("a", "b"));
		pool.add(Arrays.<Object> asList("b", "a"));
		pool.add(Arrays.<Object> asList("a", ""));
		pool.add(Arrays.<Object> asList("a", null));
		pool.add(Arrays.<Object> asList(Integer.valueOf(1), Integer.valueOf(2)));
		pool.add(Arrays.<Object> asList(new BigDecimal("1.0"), new BigDecimal("2")));
		pool.add(Arrays.<Object> asList(Arrays.asList("a"), "b"));
		pool.add(Arrays.<Object> asList(Arrays.asList("a", "b")));
		pool.add(Arrays.<Object> asList(Collections.emptyList()));
		pool.add(Arrays.<Object> asList((Object) null));
		pool.add(Arrays.<Object> asList(""));
		return pool;
	}

	private static void everyPairOfThePoolAgreesWithStoredValueEquality()
	{
		final List<Object> pool = RowDigestTest.pool();
		for (final Object planned : pool)
		{
			for (final Object stored : pool)
			{
				RowDigestTest.agree(planned, stored);
			}
		}
	}

	private static void randomRowsAgreeWithStoredValueEquality()
	{
		final List<Object> pool = RowDigestTest.pool();
		final Random random = new Random(20261004L);
		for (int n = 0; n < 20000; n++)
		{
			final int size = 1 + random.nextInt(4);
			final Object[] planned = new Object[size];
			final Object[] stored = new Object[size];
			boolean expected = true;
			for (int i = 0; i < size; i++)
			{
				planned[i] = pool.get(random.nextInt(pool.size()));
				// half of the time the stored value is the planned one itself or an equivalent, so equal rows are well represented
				stored[i] = random.nextBoolean() ? planned[i] : pool.get(random.nextInt(pool.size()));
				expected &= StoredValueEquality.sameValue(planned[i], stored[i]);
			}
			final boolean digest = RowDigest.of(planned, null).sameAs(RowDigest.of(stored, null));
			RowDigestTest.check("row " + Arrays.toString(planned) + " vs " + Arrays.toString(stored) + ": sameValue says " + expected + ", digest says " + digest, expected == digest);
		}
	}

	/** The values are length-prefixed: moving a character from one field to the next changes the digest. */
	private static void fieldBoundariesAreUnambiguous()
	{
		RowDigestTest.check("ab|c differs from a|bc", !RowDigest.of(new Object[] { "ab", "c" }, null).sameAs(RowDigest.of(new Object[] { "a", "bc" }, null)));
		RowDigestTest.check("a null then a value differs from a value then a null", !RowDigest.of(new Object[] { null, "x" }, null).sameAs(RowDigest.of(new Object[] { "x", null }, null)));
		RowDigestTest.check("a list of two differs from two fields", !RowDigest.of(new Object[] { Arrays.asList("a", "b") }, null).sameAs(RowDigest.of(new Object[] { "a", "b" }, null)));
		RowDigestTest.check("a list holding a list differs from the flat list",
			!RowDigest.of(new Object[] { Arrays.<Object> asList(Arrays.asList("a", "b")) }, null).sameAs(RowDigest.of(new Object[] { Arrays.asList("a", "b") }, null)));
		RowDigestTest.check("the field order is part of the digest", !RowDigest.of(new Object[] { "a", "b" }, null).sameAs(RowDigest.of(new Object[] { "b", "a" }, null)));
		RowDigestTest.check("same fields, same digest", RowDigest.of(new Object[] { "a", Integer.valueOf(1), null }, null).sameAs(RowDigest.of(new Object[] { "a", new BigDecimal("1.0"), "" }, null)));
	}

	/** A deferred foreign key left out of the plan is left out of BOTH sides of the comparison; one that is planned is compared. */
	private static void optionalFieldsAreComparedOnlyWhenPlanned()
	{
		final Object[] fixed = { "a", Integer.valueOf(1) };
		final RowDigest stored = RowDigest.of(fixed, new Object[] { "12", "34" });
		RowDigestTest.check("every optional field planned and equal", RowDigest.of(fixed, new Object[] { "12", "34" }).sameAs(stored));
		RowDigestTest.check("one optional field differs", !RowDigest.of(fixed, new Object[] { "12", "35" }).sameAs(stored));
		RowDigestTest.check("a planned null clears a stored reference: different", !RowDigest.of(fixed, new Object[] { "12", null }).sameAs(stored));
		RowDigestTest.check("a field left out is not compared (the stored one differs)", RowDigest.of(fixed, new Object[] { "12", RowDigest.OMITTED }).sameAs(stored));
		RowDigestTest.check("all left out: only the fixed fields count", RowDigest.of(fixed, new Object[] { RowDigest.OMITTED, RowDigest.OMITTED }).sameAs(stored));
		RowDigestTest.check("left out does not hide a fixed difference", !RowDigest.of(new Object[] { "b", Integer.valueOf(1) }, new Object[] { RowDigest.OMITTED, RowDigest.OMITTED }).sameAs(stored));
		RowDigestTest.check("a stored null equals a planned null reference",
			RowDigest.of(fixed, new Object[] { null, null }).sameAs(RowDigest.of(fixed, new Object[] { "", null })));
		RowDigestTest.check("a layout with no optional field against one with two never matches", !RowDigest.of(fixed, new Object[] { "12", "34" }).sameAs(RowDigest.of(fixed, null)));
	}

	private static void aValueNoEncodingCanStandForIsUnknown()
	{
		final Object odd = Locale.FRANCE;
		RowDigestTest.check("an unsupported class gives an unknown digest", RowDigest.of(new Object[] { odd }, null).isUnknown());
		RowDigestTest.check("an unsupported class nested in a list too", RowDigest.of(new Object[] { Arrays.<Object> asList("a", odd) }, null).isUnknown());
		RowDigestTest.check("an unsupported optional value too", RowDigest.of(new Object[] { "a" }, new Object[] { odd }).isUnknown());
		RowDigestTest.check("unknown never equals anything, itself included", !RowDigest.UNKNOWN.sameAs(RowDigest.UNKNOWN));
		RowDigestTest.check("unknown against a real digest", !RowDigest.of(new Object[] { odd }, null).sameAs(RowDigestTest.one("a")) && !RowDigestTest.one("a").sameAs(RowDigest.UNKNOWN));
		final Object[] tooMany = new Object[RowDigest.MAX_OPTIONAL_FIELDS + 1];
		Arrays.fill(tooMany, "x");
		RowDigestTest.check("more optional fields than the mask holds is unknown", RowDigest.of(new Object[0], tooMany).isUnknown());
	}

	private static void layoutBuildsPlannedAndStoredDigests()
	{
		final RowDigest.Layout layout = new RowDigest.Layout(Arrays.asList("name", "age", "city"), Arrays.asList("parent"));
		RowDigestTest.check("layout is usable", layout.usable() && layout.fixedCount() == 3 && layout.optionalCount() == 1);
		final Map<String, Object> planned = new LinkedHashMap<String, Object>();
		planned.put("age", Integer.valueOf(7)); // insertion order is not the layout order
		planned.put("name", "Ada");
		planned.put("city", "");
		final Map<String, Object> storedValues = new LinkedHashMap<String, Object>();
		storedValues.put("name", "Ada");
		storedValues.put("age", new BigDecimal("7.0"));
		storedValues.put("city", null);
		storedValues.put("parent", "123");
		final RowDigest stored = layout.stored(new RowDigest.FieldReader()
		{
			@Override
			public Object read(final String pFieldName)
			{
				return storedValues.get(pFieldName);
			}
		});
		RowDigestTest.check("a parent left out of the plan is not compared", layout.planned(planned).sameAs(stored));
		planned.put("parent", "123");
		RowDigestTest.check("a parent planned and equal", layout.planned(planned).sameAs(stored));
		planned.put("parent", null);
		RowDigestTest.check("a parent planned null against a stored reference", !layout.planned(planned).sameAs(stored));
		planned.put("parent", "123");
		planned.put("age", Integer.valueOf(8));
		RowDigestTest.check("a fixed field that differs", !layout.planned(planned).sameAs(stored));
	}

	/** Lot V3-S2: {@code words()} is {hi, lo}, equal exactly when {@code sameAs} is, and absent for UNKNOWN and for a digest with optional hashes. */
	private static void wordsAreTheTwoHalves()
	{
		final long[] a = RowDigestTest.one(new BigDecimal("10")).words();
		final long[] b = RowDigestTest.one(Integer.valueOf(10)).words();
		final long[] c = RowDigestTest.one("10").words();
		RowDigestTest.check("two words", a != null && a.length == 2);
		RowDigestTest.check("the same value gives the same words", Arrays.equals(a, b));
		RowDigestTest.check("another value gives other words", !Arrays.equals(a, c));
		RowDigestTest.check("UNKNOWN has no words", RowDigest.UNKNOWN.words() == null && RowDigestTest.one(new Object()).words() == null);
		RowDigestTest.check("a digest with optional hashes has no words", RowDigest.of(new Object[] { "a" }, new Object[] { "b" }).words() == null);
	}

	private static void agree(final Object pPlanned, final Object pStored)
	{
		final boolean expected = StoredValueEquality.sameValue(pPlanned, pStored);
		final boolean digest = RowDigestTest.sameDigest(pPlanned, pStored);
		RowDigestTest.check("planned " + RowDigestTest.show(pPlanned) + " vs stored " + RowDigestTest.show(pStored) + ": sameValue says " + expected + ", digest says " + digest,
			expected == digest);
	}

	private static String show(final Object pValue)
	{
		return pValue == null ? "null" : pValue.getClass().getSimpleName() + "(" + pValue + ")";
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
