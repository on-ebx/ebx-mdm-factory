package com.on.ebx.factory.deducedscan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JUnit-free: run the main, a failure throws. */
public final class ColumnMeasurerTest
{
	public static void main(final String[] pArgs)
	{
		final List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
		rows.add(ColumnMeasurerTest.row("id", "51", "firstname", "Maria", "archived", "false", "created", "2026-09-24T09:12:03Z"));
		rows.add(ColumnMeasurerTest.row("id", "52", "firstname", "Ella", "archived", "false", "created", "2026-09-20T09:12:03Z"));
		rows.add(ColumnMeasurerTest.row("id", "53", "firstname", "", "archived", "true", "created", "2026-09-11T09:12:03Z"));

		final List<ColumnMeasurer.Measured> measured = ColumnMeasurer.measure(rows);
		ColumnMeasurerTest.check("column order preserved", "[id, firstname, archived, created]".equals(namesOf(measured).toString()));

		final ColumnMeasurer.Measured id = measured.get(0);
		ColumnMeasurerTest.check("id measured as number", ColumnMeasurer.NUMBER.equals(id.type));
		ColumnMeasurerTest.check("id fully filled", id.filled == 3);
		ColumnMeasurerTest.check("id fully distinct", id.distinct == 3);
		ColumnMeasurerTest.check("id example is the first value", "51".equals(id.example));

		final ColumnMeasurer.Measured firstname = measured.get(1);
		ColumnMeasurerTest.check("firstname measured as text", ColumnMeasurer.TEXT.equals(firstname.type));
		ColumnMeasurerTest.check("firstname filled excludes the blank row", firstname.filled == 2);

		final ColumnMeasurer.Measured archived = measured.get(2);
		ColumnMeasurerTest.check("archived measured as yes/no", ColumnMeasurer.YES_NO.equals(archived.type));
		ColumnMeasurerTest.check("archived distinct count is 2 (true/false)", archived.distinct == 2);

		final ColumnMeasurer.Measured created = measured.get(3);
		ColumnMeasurerTest.check("created measured as date and time", ColumnMeasurer.DATE_TIME.equals(created.type));

		// a column entirely absent/empty still measures as TEXT with 0 filled, never throws
		final List<Map<String, String>> emptyRows = new ArrayList<Map<String, String>>();
		emptyRows.add(new LinkedHashMap<String, String>());
		ColumnMeasurerTest.check("no columns from an all-empty row", ColumnMeasurer.measure(emptyRows).isEmpty());

		// a mixed column (some numbers, some text) falls back to TEXT
		final List<Map<String, String>> mixedRows = new ArrayList<Map<String, String>>();
		mixedRows.add(ColumnMeasurerTest.row("code", "12"));
		mixedRows.add(ColumnMeasurerTest.row("code", "ABC"));
		final ColumnMeasurer.Measured code = ColumnMeasurer.measure(mixedRows).get(0);
		ColumnMeasurerTest.check("mixed values fall back to text", ColumnMeasurer.TEXT.equals(code.type));

		// a column missing from some rows is still "filled" only for the rows that have it
		final List<Map<String, String>> unionRows = new ArrayList<Map<String, String>>();
		unionRows.add(ColumnMeasurerTest.row("a", "1"));
		unionRows.add(ColumnMeasurerTest.row("b", "2"));
		final List<ColumnMeasurer.Measured> unionMeasured = ColumnMeasurer.measure(unionRows);
		ColumnMeasurerTest.check("union of columns across rows", "[a, b]".equals(namesOf(unionMeasured).toString()));
		ColumnMeasurerTest.check("sampleSize is the row count, not the filled count", unionMeasured.get(0).sampleSize == 2);
		ColumnMeasurerTest.check("a filled once out of two rows", unionMeasured.get(0).filled == 1);

		System.out.println("ColumnMeasurerTest OK");
	}

	private static List<String> namesOf(final List<ColumnMeasurer.Measured> pMeasured)
	{
		final List<String> out = new ArrayList<String>();
		for (final ColumnMeasurer.Measured column : pMeasured)
		{
			out.add(column.name);
		}
		return out;
	}

	private static Map<String, String> row(final String... pPairs)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (int i = 0; i < pPairs.length; i += 2)
		{
			out.put(pPairs[i], pPairs[i + 1]);
		}
		return out;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
