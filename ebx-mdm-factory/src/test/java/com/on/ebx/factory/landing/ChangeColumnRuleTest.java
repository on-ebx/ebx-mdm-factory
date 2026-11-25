package com.on.ebx.factory.landing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link ChangeColumnRule} (volume wave 2, lot V2-E, 2026-10-03). */
public final class ChangeColumnRuleTest
{
	public static void main(final String[] pArgs)
	{
		ChangeColumnRuleTest.checkTimestampMarkerNames();
		ChangeColumnRuleTest.checkNameNormalisation();
		ChangeColumnRuleTest.checkTypeMustMatch();
		ChangeColumnRuleTest.checkNothingQualifiesStaysFull();
		ChangeColumnRuleTest.checkBestNameWinsThenColumnOrder();
		ChangeColumnRuleTest.checkTimestampBeatsNumber();
		ChangeColumnRuleTest.checkNumberMarkers();
		ChangeColumnRuleTest.checkNullsRefused();
		ChangeColumnRuleTest.checkKindFor();
		ChangeColumnRuleTest.checkValidate();
		System.out.println("ChangeColumnRuleTest OK");
	}

	private static List<ChangeColumnRule.Candidate> columns(final String... pNameAndType)
	{
		final List<ChangeColumnRule.Candidate> out = new ArrayList<ChangeColumnRule.Candidate>();
		for (int i = 0; i < pNameAndType.length; i += 2)
		{
			out.add(new ChangeColumnRule.Candidate(pNameAndType[i], pNameAndType[i + 1], null));
		}
		return out;
	}

	/** Every marker the brief names (and the French/SAP ones) is found on a timestamp/date column and read as TIMESTAMP. */
	private static void checkTimestampMarkerNames()
	{
		for (final String name : new String[] { "LAST_MODIFIED", "MODIFIED", "UPDATED_AT", "UPDATE_DATE", "CHANGED_ON", "AEDAT", "LASTMODIFIEDDATE", "SystemModstamp", "_UPDATED",
			"MODIFIEDDATE", "DATE_MODIFICATION", "DT_MAJ", "LastModifiedDate" })
		{
			final ChangeColumnRule.Choice choice = ChangeColumnRule.choose(ChangeColumnRuleTest.columns("ID", "INTEGER", name, "TIMESTAMP", "NAME", "STRING"));
			ChangeColumnRuleTest.check(name + " is a change column", choice != null && choice.column.equals(name));
			ChangeColumnRuleTest.check(name + " reads as TIMESTAMP", choice != null && ChangeColumnRule.TIMESTAMP.equals(choice.kind));
		}
		final ChangeColumnRule.Choice date = ChangeColumnRule.choose(ChangeColumnRuleTest.columns("AEDAT", "DATE"));
		ChangeColumnRuleTest.check("a DATE column qualifies too", date != null && ChangeColumnRule.TIMESTAMP.equals(date.kind));
	}

	private static void checkNameNormalisation()
	{
		ChangeColumnRuleTest.check("separators and case do not matter", "LASTMODIFIED".equals(ChangeColumnRule.normalise("_Last-Modified ")));
		ChangeColumnRuleTest.check("null is empty", ChangeColumnRule.normalise(null).isEmpty());
		final ChangeColumnRule.Choice choice = ChangeColumnRule.choose(ChangeColumnRuleTest.columns("last modified", "TIMESTAMP"));
		ChangeColumnRuleTest.check("a name with a space still matches", choice != null);
	}

	/** A marker name on a text column is NOT chosen (never guessed from data), nor is a creation date, nor a bare ID. */
	private static void checkTypeMustMatch()
	{
		ChangeColumnRuleTest.check("LAST_MODIFIED as text is refused", ChangeColumnRule.choose(ChangeColumnRuleTest.columns("LAST_MODIFIED", "STRING")) == null);
		ChangeColumnRuleTest.check("a number named like a date marker is refused", ChangeColumnRule.choose(ChangeColumnRuleTest.columns("UPDATED_AT", "INTEGER")) == null);
		ChangeColumnRuleTest.check("a date named like a number marker is refused", ChangeColumnRule.choose(ChangeColumnRuleTest.columns("ROW_VERSION", "TIMESTAMP")) == null);
		ChangeColumnRuleTest.check("a type written in lower case still counts", ChangeColumnRule.choose(ChangeColumnRuleTest.columns("UPDATED_AT", "timestamp")) != null);
		ChangeColumnRuleTest.check("no type known is refused", ChangeColumnRule.choose(ChangeColumnRuleTest.columns("UPDATED_AT", null)) == null);
	}

	private static void checkNothingQualifiesStaysFull()
	{
		ChangeColumnRuleTest.check("created date, auto-increment id, version counter and line number are not change markers",
			ChangeColumnRule.choose(ChangeColumnRuleTest.columns("CREATED_AT", "TIMESTAMP", "ID", "INTEGER", "VERSION", "INTEGER", "SEQ", "INTEGER", "REVISION", "INTEGER")) == null);
		ChangeColumnRuleTest.check("no column at all", ChangeColumnRule.choose(new ArrayList<ChangeColumnRule.Candidate>()) == null);
		ChangeColumnRuleTest.check("null list", ChangeColumnRule.choose(null) == null);
	}

	/** The marker list is ordered: LAST_MODIFIED beats UPDATED_AT whatever the column order; equal names fall back to the table's order. */
	private static void checkBestNameWinsThenColumnOrder()
	{
		final ChangeColumnRule.Choice choice = ChangeColumnRule.choose(ChangeColumnRuleTest.columns("UPDATED_AT", "TIMESTAMP", "LAST_MODIFIED", "TIMESTAMP"));
		ChangeColumnRuleTest.check("LAST_MODIFIED outranks UPDATED_AT", choice != null && "LAST_MODIFIED".equals(choice.column));
		final ChangeColumnRule.Choice same = ChangeColumnRule.choose(ChangeColumnRuleTest.columns("last_modified", "TIMESTAMP", "LAST-MODIFIED", "TIMESTAMP"));
		ChangeColumnRuleTest.check("two columns of the same normalised name: the first in table order", same != null && "last_modified".equals(same.column));
	}

	private static void checkTimestampBeatsNumber()
	{
		final ChangeColumnRule.Choice choice = ChangeColumnRule.choose(ChangeColumnRuleTest.columns("ROW_VERSION", "INTEGER", "UPDATED_AT", "TIMESTAMP"));
		ChangeColumnRuleTest.check("a timestamp marker wins over a number marker", choice != null && "UPDATED_AT".equals(choice.column) && ChangeColumnRule.TIMESTAMP.equals(choice.kind));
	}

	private static void checkNumberMarkers()
	{
		for (final String name : new String[] { "ROW_VERSION", "ROWVERSION", "CHANGE_SEQ", "LSN" })
		{
			final ChangeColumnRule.Choice choice = ChangeColumnRule.choose(ChangeColumnRuleTest.columns("ID", "INTEGER", "NAME", "STRING", name, "DECIMAL"));
			ChangeColumnRuleTest.check(name + " is a number change column", choice != null && choice.column.equals(name) && ChangeColumnRule.MONOTONIC_ID.equals(choice.kind));
		}
	}

	/** {@code >=} never returns a NULL: a column profiled with NULLs is refused, one with 0 or never profiled is allowed. */
	private static void checkNullsRefused()
	{
		final List<ChangeColumnRule.Candidate> withNulls = new ArrayList<ChangeColumnRule.Candidate>();
		withNulls.add(new ChangeColumnRule.Candidate("LAST_MODIFIED", "TIMESTAMP", new BigDecimal("0.02")));
		withNulls.add(new ChangeColumnRule.Candidate("UPDATED_AT", "TIMESTAMP", BigDecimal.ZERO));
		final ChangeColumnRule.Choice fallback = ChangeColumnRule.choose(withNulls);
		ChangeColumnRuleTest.check("the column with NULLs is skipped, the clean one is used", fallback != null && "UPDATED_AT".equals(fallback.column));
		withNulls.remove(1);
		ChangeColumnRuleTest.check("alone, a column with NULLs leaves the table in full", ChangeColumnRule.choose(withNulls) == null);
		final ChangeColumnRule.Choice unprofiled = ChangeColumnRule.choose(ChangeColumnRuleTest.columns("LAST_MODIFIED", "TIMESTAMP"));
		ChangeColumnRuleTest.check("never profiled is allowed and says so", unprofiled != null && unprofiled.reason.contains("not profiled"));
	}

	private static void checkKindFor()
	{
		ChangeColumnRuleTest.check("DATE", ChangeColumnRule.TIMESTAMP.equals(ChangeColumnRule.kindFor("DATE")));
		ChangeColumnRuleTest.check("TIMESTAMP", ChangeColumnRule.TIMESTAMP.equals(ChangeColumnRule.kindFor("TIMESTAMP")));
		ChangeColumnRuleTest.check("INTEGER", ChangeColumnRule.MONOTONIC_ID.equals(ChangeColumnRule.kindFor("integer")));
		ChangeColumnRuleTest.check("DECIMAL", ChangeColumnRule.MONOTONIC_ID.equals(ChangeColumnRule.kindFor("DECIMAL")));
		ChangeColumnRuleTest.check("STRING", ChangeColumnRule.ORDERED_STRING.equals(ChangeColumnRule.kindFor("STRING")));
		ChangeColumnRuleTest.check("null", ChangeColumnRule.ORDERED_STRING.equals(ChangeColumnRule.kindFor(null)));
		ChangeColumnRuleTest.check("isKind", ChangeColumnRule.isKind("TIMESTAMP") && ChangeColumnRule.isKind("MONOTONIC_ID") && ChangeColumnRule.isKind("ORDERED_STRING")
			&& !ChangeColumnRule.isKind("timestamp") && !ChangeColumnRule.isKind("") && !ChangeColumnRule.isKind(null));
	}

	private static void checkValidate()
	{
		final Map<String, String> types = new LinkedHashMap<String, String>();
		types.put("ID", "INTEGER");
		types.put("CHANGED", "DATE");
		types.put("VER", "DECIMAL");
		types.put("STAMP", "STRING");
		final ChangeColumnRule.Validation none = ChangeColumnRule.validate(types, "  ", "TIMESTAMP");
		ChangeColumnRuleTest.check("blank column is 'none'", none.ok && none.column.isEmpty() && none.kind.isEmpty());
		final ChangeColumnRule.Validation noneNull = ChangeColumnRule.validate(types, null, null);
		ChangeColumnRuleTest.check("null column is 'none'", noneNull.ok && noneNull.column.isEmpty());
		final ChangeColumnRule.Validation derived = ChangeColumnRule.validate(types, " CHANGED ", "");
		ChangeColumnRuleTest.check("blank kind is derived from the type", derived.ok && "CHANGED".equals(derived.column) && ChangeColumnRule.TIMESTAMP.equals(derived.kind));
		ChangeColumnRuleTest.check("a number column reads MONOTONIC_ID", ChangeColumnRule.MONOTONIC_ID.equals(ChangeColumnRule.validate(types, "VER", null).kind));
		ChangeColumnRuleTest.check("a text column reads ORDERED_STRING", ChangeColumnRule.ORDERED_STRING.equals(ChangeColumnRule.validate(types, "STAMP", null).kind));
		final ChangeColumnRule.Validation explicit = ChangeColumnRule.validate(types, "STAMP", "TIMESTAMP");
		ChangeColumnRuleTest.check("an explicit kind is kept", explicit.ok && ChangeColumnRule.TIMESTAMP.equals(explicit.kind));
		final ChangeColumnRule.Validation unknown = ChangeColumnRule.validate(types, "NOPE", "TIMESTAMP");
		ChangeColumnRuleTest.check("an unknown column is refused with a message naming it", !unknown.ok && unknown.message.contains("NOPE"));
		final ChangeColumnRule.Validation badKind = ChangeColumnRule.validate(types, "CHANGED", "EPOCH");
		ChangeColumnRuleTest.check("an unknown kind is refused", !badKind.ok && badKind.message.contains("EPOCH"));
		ChangeColumnRuleTest.check("case matters for a column name, like the key", !ChangeColumnRule.validate(types, "changed", null).ok);
		ChangeColumnRuleTest.check("no column list at all refuses any column", !ChangeColumnRule.validate(null, "CHANGED", null).ok);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
