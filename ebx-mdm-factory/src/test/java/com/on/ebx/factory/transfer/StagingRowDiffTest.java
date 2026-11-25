package com.on.ebx.factory.transfer;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link StagingRowDiff} (lot V3-S, 2026-10-04). */
public final class StagingRowDiffTest
{
	/** A string column. */
	private static final StagingRowDiff.Field STRING = new Col(false, true)
	{
		@Override
		public Object parse(final String pXs)
		{
			return pXs;
		}
	};
	/** A decimal column: "1.0" and "1" parse to numerically equal values. */
	private static final StagingRowDiff.Field DECIMAL = new Col(false, true)
	{
		@Override
		public Object parse(final String pXs)
		{
			return new BigDecimal(pXs);
		}
	};
	/** A date-time column: xs-strings of the same instant, with or without milliseconds, parse to the same Date. */
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
	/** A column whose parse yields a value of another class than the stored one, but whose stored value formats to the planned text. */
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
		StagingRowDiffTest.nothingDiffersMeansUnchanged();
		StagingRowDiffTest.oneDifferentFieldMeansWritten();
		StagingRowDiffTest.nullAndEmptyAreTheSame();
		StagingRowDiffTest.theTypedFormAbsorbsCanonicalTextDifferences();
		StagingRowDiffTest.foreignKeysAreComparedAsTheirKeyText();
		StagingRowDiffTest.statusMustAlreadyBeStaged();
		StagingRowDiffTest.sourceHashIsComparedOnlyWhenTheTableHasIt();
		StagingRowDiffTest.loadIdAndReceivedAtTakePartInNothing();
		StagingRowDiffTest.aDoubtIsADifference();
		StagingRowDiffTest.xrefTextsIgnoreWhenTheRowWasWritten();
		System.out.println("StagingRowDiffTest OK");
	}

	private static void nothingDiffersMeansUnchanged()
	{
		final Row row = new Row();
		row.put("name", STRING, "ACME", "ACME");
		row.put("amount", DECIMAL, "12.50", new BigDecimal("12.50"));
		StagingRowDiffTest.check("every planned value stored: unchanged", StagingRowDiff.unchanged(row.planned, "h1", true, row));
		StagingRowDiffTest.check("an empty planned map on a STAGED row is unchanged", StagingRowDiff.unchanged(new LinkedHashMap<String, Object>(), "h1", true, row));
	}

	private static void oneDifferentFieldMeansWritten()
	{
		final Row row = new Row();
		row.put("name", STRING, "ACME", "ACME");
		row.put("city", STRING, "Paris", "Lyon");
		StagingRowDiffTest.check("one stored value differs: written", !StagingRowDiff.unchanged(row.planned, "h1", true, row));
		final Row appended = new Row();
		appended.put("name", STRING, "ACME", "ACME");
		appended.put("city", STRING, "Paris", null);
		StagingRowDiffTest.check("a value planned where the row holds none: written", !StagingRowDiff.unchanged(appended.planned, "h1", true, appended));
	}

	private static void nullAndEmptyAreTheSame()
	{
		final Row row = new Row();
		row.put("a", STRING, null, null);
		row.put("b", STRING, null, "");
		row.put("c", STRING, "", null);
		row.put("d", DECIMAL, null, null);
		StagingRowDiffTest.check("null / empty planned against null / empty stored: unchanged", StagingRowDiff.unchanged(row.planned, "h1", true, row));
		final Row clear = new Row();
		clear.put("a", STRING, null, "left over");
		StagingRowDiffTest.check("a forced null over a stale value is a change", !StagingRowDiff.unchanged(clear.planned, "h1", true, clear));
	}

	private static void theTypedFormAbsorbsCanonicalTextDifferences()
	{
		final Row decimal = new Row();
		decimal.put("price", DECIMAL, "1.0", new BigDecimal("1"));
		decimal.put("qty", DECIMAL, "7", new BigDecimal("7.00"));
		StagingRowDiffTest.check("\"1.0\" planned, 1 stored (and 7 against 7.00): unchanged, the text is not what is compared", StagingRowDiff.unchanged(decimal.planned, "h1", true, decimal));
		final Row date = new Row();
		date.put("at", DATE_TIME, "1700000000000.000", new Date(1700000000000L));
		StagingRowDiffTest.check("a date-time with milliseconds planned, the same instant stored: unchanged", StagingRowDiff.unchanged(date.planned, "h1", true, date));
		final Row other = new Row();
		other.put("at", DATE_TIME, "1700000000001", new Date(1700000000000L));
		StagingRowDiffTest.check("one millisecond apart: written", !StagingRowDiff.unchanged(other.planned, "h1", true, other));
		final Row number = new Row();
		number.put("price", DECIMAL, "1.01", new BigDecimal("1"));
		StagingRowDiffTest.check("a different decimal: written", !StagingRowDiff.unchanged(number.planned, "h1", true, number));
	}

	private static void foreignKeysAreComparedAsTheirKeyText()
	{
		final Row row = new Row();
		row.put("parentRef", REFERENCE, "ERP|42", "ERP|42");
		StagingRowDiffTest.check("same key text: unchanged (never parsed)", StagingRowDiff.unchanged(row.planned, "h1", true, row));
		final Row moved = new Row();
		moved.put("parentRef", REFERENCE, "ERP|43", "ERP|42");
		StagingRowDiffTest.check("another parent: written", !StagingRowDiff.unchanged(moved.planned, "h1", true, moved));
		final Row cleared = new Row();
		cleared.put("parentRef", REFERENCE, null, "ERP|42");
		StagingRowDiffTest.check("a reference removed at the source: written", !StagingRowDiff.unchanged(cleared.planned, "h1", true, cleared));
	}

	private static void statusMustAlreadyBeStaged()
	{
		final Row row = new Row();
		row.put("name", STRING, "ACME", "ACME");
		row.status = "REJECTED";
		StagingRowDiffTest.check("a row in another status is written even when every value matches", !StagingRowDiff.unchanged(row.planned, "h1", true, row));
		row.status = null;
		StagingRowDiffTest.check("a row without a status is written", !StagingRowDiff.unchanged(row.planned, "h1", true, row));
		row.status = StagingRowDiff.STATUS_STAGED;
		StagingRowDiffTest.check("STAGED and equal: unchanged", StagingRowDiff.unchanged(row.planned, "h1", true, row));
	}

	private static void sourceHashIsComparedOnlyWhenTheTableHasIt()
	{
		final Row row = new Row();
		row.put("name", STRING, "ACME", "ACME");
		row.hash = "old";
		StagingRowDiffTest.check("the landing hash moved while no mapped value did: written, so scope new later sees the new hash", !StagingRowDiff.unchanged(row.planned, "new", true, row));
		StagingRowDiffTest.check("a table without _sourceHash: the hash takes part in nothing", StagingRowDiff.unchanged(row.planned, "new", false, row));
		row.hash = null;
		StagingRowDiffTest.check("a row staged before the field existed holds no hash: written once to receive it", !StagingRowDiff.unchanged(row.planned, "new", true, row));
		StagingRowDiffTest.check("no hash on either side: equal", StagingRowDiff.unchanged(row.planned, null, true, row));
	}

	private static void loadIdAndReceivedAtTakePartInNothing()
	{
		// the API does not even take them: a planned map holds business fields only, and the technical ones compared are status and hash
		final Row row = new Row();
		row.put("name", STRING, "ACME", "ACME");
		final Map<String, Object> planned = new HashMap<String, Object>(row.planned);
		StagingRowDiffTest.check("an unchanged row stays unchanged whatever load or reception date the landing row carries", StagingRowDiff.unchanged(planned, "h1", true, row));
	}

	private static void aDoubtIsADifference()
	{
		final Row multi = new Row();
		multi.put("tags", MULTI, "a", "a");
		StagingRowDiffTest.check("a column the comparison cannot reason about is written", !StagingRowDiff.unchanged(multi.planned, "h1", true, multi));
		final Row unknown = new Row();
		unknown.planned.put("ghost", "x");
		StagingRowDiffTest.check("a planned field the table does not define is written (never silently ignored)", !StagingRowDiff.unchanged(unknown.planned, "h1", true, unknown));
		final Row bad = new Row();
		bad.put("amount", DECIMAL, "twelve", new BigDecimal("12"));
		StagingRowDiffTest.check("a text the node cannot parse is written (the write fails the same way and rejects the row)", !StagingRowDiff.unchanged(bad.planned, "h1", true, bad));
		final Row quirky = new Row();
		quirky.put("code", QUIRKY, "A-1", "A-1");
		StagingRowDiffTest.check("a parse of another class, but the stored value formats to the planned text: unchanged", StagingRowDiff.unchanged(quirky.planned, "h1", true, quirky));
		final Row quirkyOther = new Row();
		quirkyOther.put("code", QUIRKY, "A-2", "A-1");
		StagingRowDiffTest.check("the same quirk with another text: written", !StagingRowDiff.unchanged(quirkyOther.planned, "h1", true, quirkyOther));
		final Row nonString = new Row();
		nonString.put("amount", DECIMAL, Integer.valueOf(5), new BigDecimal("5"));
		StagingRowDiffTest.check("a planned value that is not a String is compared as the text the write sends", StagingRowDiff.unchanged(nonString.planned, "h1", true, nonString));
	}

	private static void xrefTextsIgnoreWhenTheRowWasWritten()
	{
		final String[] planned = { "P", "Personne", "ERP|1", "ERP", "CUSTOMER", "9", "ERP", "1" };
		StagingRowDiffTest.check("identical texts", StagingRowDiff.sameTexts(planned, planned.clone()));
		final String[] other = planned.clone();
		other[5] = "10";
		StagingRowDiffTest.check("another landing key: the Xref must be rewritten", !StagingRowDiff.sameTexts(planned, other));
		final String[] blank = planned.clone();
		blank[3] = null;
		final String[] empty = planned.clone();
		empty[3] = "";
		StagingRowDiffTest.check("null equals empty", StagingRowDiff.sameTexts(blank, empty));
		StagingRowDiffTest.check("another length is never equal", !StagingRowDiff.sameTexts(planned, new String[] { "P" }));
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

	private static final class Row implements StagingRowDiff.Stored
	{
		final Map<String, Object> planned = new LinkedHashMap<String, Object>();
		private final Map<String, StagingRowDiff.Field> fields = new HashMap<String, StagingRowDiff.Field>();
		private final Map<String, Object> values = new HashMap<String, Object>();
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
