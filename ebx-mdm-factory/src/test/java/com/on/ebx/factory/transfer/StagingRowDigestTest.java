package com.on.ebx.factory.transfer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. {@link StagingRowDigest} (lot V3-S2, 2026-10-04) must never say
 * "same" when {@link StagingRowDiff#unchanged} says "different" (equal digests imply unchanged), and must say "same" for the ordinary unchanged row - pinned
 * on a table of cases and on random rows drawn from a pool of planned / stored values.
 */
public final class StagingRowDigestTest
{
	private static final StagingRowDiff.Field STRING = new Col(false, true)
	{
		@Override
		public Object parse(final String pXs)
		{
			return pXs;
		}
	};
	private static final StagingRowDiff.Field DECIMAL = new Col(false, true)
	{
		@Override
		public Object parse(final String pXs)
		{
			return new BigDecimal(pXs);
		}
	};
	private static final StagingRowDiff.Field DATE_TIME = new Col(false, true)
	{
		@Override
		public Object parse(final String pXs)
		{
			final String text = pXs.endsWith(".000") ? pXs.substring(0, pXs.length() - 4) : pXs;
			return new Date(Long.parseLong(text));
		}
	};
	private static final StagingRowDiff.Field REFERENCE = new Col(true, true)
	{
		@Override
		public Object parse(final String pXs)
		{
			throw new IllegalStateException("a foreign key is never parsed");
		}
	};
	private static final StagingRowDiff.Field MULTI = new Col(false, false)
	{
		@Override
		public Object parse(final String pXs)
		{
			return pXs;
		}
	};
	/** Parses to a value of a class nobody can encode, whatever the text. */
	private static final StagingRowDiff.Field QUIRKY = new Col(false, true)
	{
		@Override
		public Object parse(final String pXs)
		{
			return new Object();
		}
	};

	public static void main(final String[] pArgs)
	{
		StagingRowDigestTest.theOrdinaryUnchangedRowHasEqualDigests();
		StagingRowDigestTest.eachDifferenceBreaksTheDigest();
		StagingRowDigestTest.aDoubtIsUnknown();
		StagingRowDigestTest.theColumnSetMustBeTheLayout();
		StagingRowDigestTest.layoutPositionsAreIndependent();
		StagingRowDigestTest.randomRowsNeverContradictStagingRowDiff();
		System.out.println("StagingRowDigestTest OK");
	}

	private static void theOrdinaryUnchangedRowHasEqualDigests()
	{
		final Row row = new Row();
		row.put("name", STRING, "ACME", "ACME");
		row.put("amount", DECIMAL, "12.50", new BigDecimal("12.5"));
		row.put("at", DATE_TIME, "1700000000000.000", new Date(1700000000000L));
		row.put("parentRef", REFERENCE, "ERP|42", "ERP|42");
		row.put("note", STRING, null, "");
		row.put("other", STRING, "", null);
		StagingRowDigestTest.check("every value stored (decimal scale, date milliseconds, null and empty): digests equal",
			StagingRowDigestTest.sameDigest(row, "h1", true));
		StagingRowDigestTest.check("and StagingRowDiff agrees", StagingRowDiff.unchanged(row.planned, "h1", true, row));
		StagingRowDigestTest.check("an empty layout on a STAGED row", StagingRowDigest.same(StagingRowDigest.planned(new String[0], new LinkedHashMap<String, Object>(), "h1", true, row),
			StagingRowDigest.stored(new String[0], row, true), 0));
		final Row noHash = new Row();
		noHash.put("name", STRING, "ACME", "ACME");
		noHash.hash = "old";
		StagingRowDigestTest.check("a table without _sourceHash: the stored hash takes part in nothing", StagingRowDigestTest.sameDigest(noHash, "new", false));
	}

	private static void eachDifferenceBreaksTheDigest()
	{
		final Row value = new Row();
		value.put("name", STRING, "ACME", "ACME");
		value.put("city", STRING, "Paris", "Lyon");
		StagingRowDigestTest.check("a value differs", !StagingRowDigestTest.sameDigest(value, "h1", true));
		final Row cleared = new Row();
		cleared.put("a", STRING, null, "left over");
		StagingRowDigestTest.check("a forced null over a stale value", !StagingRowDigestTest.sameDigest(cleared, "h1", true));
		final Row key = new Row();
		key.put("parentRef", REFERENCE, "ERP|43", "ERP|42");
		StagingRowDigestTest.check("another parent", !StagingRowDigestTest.sameDigest(key, "h1", true));
		final Row status = new Row();
		status.put("name", STRING, "ACME", "ACME");
		status.status = "REJECTED";
		StagingRowDigestTest.check("another status", !StagingRowDigestTest.sameDigest(status, "h1", true));
		status.status = null;
		StagingRowDigestTest.check("no status", !StagingRowDigestTest.sameDigest(status, "h1", true));
		final Row hash = new Row();
		hash.put("name", STRING, "ACME", "ACME");
		hash.hash = "old";
		StagingRowDigestTest.check("the landing hash moved", !StagingRowDigestTest.sameDigest(hash, "new", true));
		hash.hash = null;
		StagingRowDigestTest.check("a row staged before the hash field existed", !StagingRowDigestTest.sameDigest(hash, "new", true));
		StagingRowDigestTest.check("no hash on either side", StagingRowDigestTest.sameDigest(hash, null, true));
	}

	private static void aDoubtIsUnknown()
	{
		final Row multi = new Row();
		multi.put("tags", MULTI, "a", "a");
		StagingRowDigestTest.check("a planned value on a column the comparison cannot reason about", StagingRowDigest.planned(new String[] { "tags" }, multi.planned, "h1", true, multi).length == 0);
		final Row multiNull = new Row();
		multiNull.put("tags", MULTI, null, Collections.emptyList());
		StagingRowDigestTest.check("a planned null over an empty list: no value on both sides", StagingRowDigestTest.sameDigest(multiNull, "h1", true));
		final Row ghost = new Row();
		ghost.planned.put("ghost", "x");
		StagingRowDigestTest.check("a planned field the table does not define", StagingRowDigest.planned(new String[] { "ghost" }, ghost.planned, "h1", true, ghost).length == 0);
		final Row bad = new Row();
		bad.put("amount", DECIMAL, "twelve", new BigDecimal("12"));
		StagingRowDigestTest.check("a text the node cannot parse", StagingRowDigest.planned(new String[] { "amount" }, bad.planned, "h1", true, bad).length == 0);
		final Row quirky = new Row();
		quirky.put("code", QUIRKY, "A-1", "A-1");
		StagingRowDigestTest.check("a parse no encoding can stand for", StagingRowDigest.planned(new String[] { "code" }, quirky.planned, "h1", true, quirky).length == 0);
		StagingRowDigestTest.check("StagingRowDiff still finds it unchanged (second test): the digest is unknown, so the row is READ - never wrongly written off",
			StagingRowDiff.unchanged(quirky.planned, "h1", true, quirky));
		final Row unreadable = new Row()
		{
			@Override
			public Object value(final String pName)
			{
				throw new IllegalStateException("cannot read");
			}
		};
		unreadable.put("name", STRING, "ACME", "ACME");
		StagingRowDigestTest.check("a stored row that cannot be read", StagingRowDigest.stored(new String[] { "name" }, unreadable, true).length == 0);
		StagingRowDigestTest.check("an unknown side never equals, not even another unknown", !StagingRowDigest.same(StagingRowDigest.UNKNOWN, StagingRowDigest.UNKNOWN, 0)
			&& !StagingRowDigest.same(new long[] { 1L, 2L }, StagingRowDigest.UNKNOWN, 0) && !StagingRowDigest.same(StagingRowDigest.UNKNOWN, new long[] { 1L, 2L }, 0));
		final Row ok = new Row();
		ok.put("name", STRING, "ACME", "ACME");
		final long[] planned = StagingRowDigest.planned(new String[] { "name" }, ok.planned, "h1", true, ok);
		StagingRowDigestTest.check("a layout position beyond the entry is never equal", !StagingRowDigest.same(planned, StagingRowDigest.stored(new String[] { "name" }, ok, true), 1));
	}

	private static void theColumnSetMustBeTheLayout()
	{
		final String[] layout = { "a", "b" };
		final Map<String, Object> exact = new LinkedHashMap<String, Object>();
		exact.put("b", "1");
		exact.put("a", null);
		StagingRowDigestTest.check("the same columns in another order, null values included", StagingRowDigest.sameColumns(layout, exact));
		final Map<String, Object> missing = new LinkedHashMap<String, Object>();
		missing.put("a", "1");
		StagingRowDigestTest.check("a column of the layout is missing", !StagingRowDigest.sameColumns(layout, missing));
		final Map<String, Object> extra = new LinkedHashMap<String, Object>(exact);
		extra.put("c", "1");
		StagingRowDigestTest.check("a column more than the layout", !StagingRowDigest.sameColumns(layout, extra));
		final Map<String, Object> other = new LinkedHashMap<String, Object>();
		other.put("a", "1");
		other.put("c", "1");
		StagingRowDigestTest.check("the same count, another column", !StagingRowDigest.sameColumns(layout, other));
	}

	private static void layoutPositionsAreIndependent()
	{
		final Row row = new Row();
		row.put("name", STRING, "ACME", "ACME");
		row.put("city", STRING, "Paris", "Paris");
		final String[] wide = { "city", "name" };
		final String[] narrow = { "name" };
		final long[] stored0 = StagingRowDigest.stored(wide, row, true);
		final long[] stored1 = StagingRowDigest.stored(narrow, row, true);
		final long[] entry = new long[4];
		System.arraycopy(stored0, 0, entry, 0, 2);
		System.arraycopy(stored1, 0, entry, 2, 2);
		final Map<String, Object> narrowPlan = new LinkedHashMap<String, Object>();
		narrowPlan.put("name", "ACME");
		StagingRowDigestTest.check("each layout is read at its own position", StagingRowDigest.same(StagingRowDigest.planned(wide, row.planned, "h1", true, row), entry, 0)
			&& StagingRowDigest.same(StagingRowDigest.planned(narrow, narrowPlan, "h1", true, row), entry, 1));
		StagingRowDigestTest.check("a layout is never read at another one's position", !StagingRowDigest.same(StagingRowDigest.planned(narrow, narrowPlan, "h1", true, row), entry, 0));
	}

	/** Equal digests must imply StagingRowDiff.unchanged, whatever the planned and stored values drawn. */
	private static void randomRowsNeverContradictStagingRowDiff()
	{
		final Random random = new Random(20261004L);
		final String[] plannedPool = { null, "", "a", "A", "10", "10.0", "7", "1700000000000", "ERP|1", " " };
		final Object[] storedPool = { null, "", "a", "A", "10", new BigDecimal("10"), new BigDecimal("10.00"), new BigDecimal("7"), Integer.valueOf(7), new Date(1700000000000L),
			"ERP|1", " ", Arrays.asList("a"), new ArrayList<Object>() };
		final StagingRowDiff.Field[] kinds = { STRING, DECIMAL, DATE_TIME, REFERENCE, MULTI, QUIRKY };
		final String[] hashes = { null, "", "h1", "h2" };
		final String[] statuses = { StagingRowDiff.STATUS_STAGED, "NEW", null };
		int equalDigests = 0;
		for (int round = 0; round < 100000; round++)
		{
			final Row row = new Row();
			final int columns = 1 + random.nextInt(3);
			for (int c = 0; c < columns; c++)
			{
				row.put("c" + c, kinds[random.nextInt(kinds.length)], plannedPool[random.nextInt(plannedPool.length)], storedPool[random.nextInt(storedPool.length)]);
			}
			row.status = statuses[random.nextInt(statuses.length)];
			row.hash = hashes[random.nextInt(hashes.length)];
			final String plannedHash = hashes[random.nextInt(hashes.length)];
			final boolean compareHash = random.nextBoolean();
			final boolean digestsEqual = StagingRowDigestTest.sameDigest(row, plannedHash, compareHash);
			if (digestsEqual)
			{
				equalDigests++;
				StagingRowDigestTest.check("equal digests but StagingRowDiff says changed: planned " + row.planned + " stored " + row.values + " status " + row.status + " hash "
					+ row.hash + "/" + plannedHash + " " + compareHash, StagingRowDiff.unchanged(row.planned, plannedHash, compareHash, row));
			}
		}
		StagingRowDigestTest.check("the random rows include equal ones (the test is not vacuous): " + equalDigests, equalDigests > 100);
	}

	private static boolean sameDigest(final Row pRow, final String pPlannedHash, final boolean pCompareHash)
	{
		final List<String> names = new ArrayList<String>(new java.util.TreeSet<String>(pRow.planned.keySet()));
		final String[] columns = names.toArray(new String[names.size()]);
		return StagingRowDigest.same(StagingRowDigest.planned(columns, pRow.planned, pPlannedHash, pCompareHash, pRow), StagingRowDigest.stored(columns, pRow, pCompareHash), 0);
	}

	// ------------------------------------------------------------------------------------------------ fakes

	private abstract static class Col implements StagingRowDiff.Field
	{
		private final boolean reference;
		private final boolean comparable;

		Col(final boolean pReference, final boolean pComparable)
		{
			this.reference = pReference;
			this.comparable = pComparable;
		}

		@Override
		public boolean comparable()
		{
			return this.comparable;
		}

		@Override
		public boolean reference()
		{
			return this.reference;
		}

		@Override
		public String format(final Object pStored)
		{
			return String.valueOf(pStored);
		}
	}

	private static class Row implements StagingRowDiff.Stored, StagingRowDigest.Columns
	{
		final Map<String, Object> planned = new LinkedHashMap<String, Object>();
		final Map<String, StagingRowDiff.Field> fields = new HashMap<String, StagingRowDiff.Field>();
		final Map<String, Object> values = new HashMap<String, Object>();
		String status = StagingRowDiff.STATUS_STAGED;
		String hash = "h1";

		void put(final String pName, final StagingRowDiff.Field pField, final Object pPlanned, final Object pStored)
		{
			this.planned.put(pName, pPlanned);
			this.fields.put(pName, pField);
			this.values.put(pName, pStored);
		}

		@Override
		public StagingRowDiff.Field field(final String pName)
		{
			return this.fields.get(pName);
		}

		@Override
		public Object value(final String pName)
		{
			return this.values.get(pName);
		}

		@Override
		public String status()
		{
			return this.status;
		}

		@Override
		public Object sourceHash()
		{
			return this.hash;
		}
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
