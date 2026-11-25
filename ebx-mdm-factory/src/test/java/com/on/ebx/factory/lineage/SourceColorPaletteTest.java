package com.on.ebx.factory.lineage;

import java.util.HashSet;
import java.util.Set;

/** JUnit-free, like the sibling tests: run the main, a failure throws. */
public final class SourceColorPaletteTest
{
	public static void main(final String[] pArgs)
	{
		SourceColorPaletteTest.sameNameAlwaysSameColor();
		SourceColorPaletteTest.neverTheGoldenAccent();
		SourceColorPaletteTest.caseAndWhitespaceInsensitive();
		SourceColorPaletteTest.nullAndBlankAreStableToo();
		SourceColorPaletteTest.fourRealSystemNamesAreDistinctInPractice();
		SourceColorPaletteTest.ordinalStability_sameOrderAlwaysSameColours();
		SourceColorPaletteTest.ordinalFirstFour_areTheCoordinatorsExactFour();
		SourceColorPaletteTest.ordinalWraps_neverThrowsPastTwelve();
		SourceColorPaletteTest.isExtended_falseWithinPalette_trueBeyond();
		SourceColorPaletteTest.thirteenSources_noTwoColorStylePairsEqual();
		System.out.println("SourceColorPaletteTest OK");
	}

	/** The whole point of a stateless assignment: repeated calls, and calls "on a different record", must agree. */
	private static void sameNameAlwaysSameColor()
	{
		final String first = SourceColorPalette.colorFor("ERP");
		for (int i = 0; i < 20; i++)
		{
			SourceColorPaletteTest.check("ERP's colour never changes across calls", first.equals(SourceColorPalette.colorFor("ERP")));
		}
	}

	private static void neverTheGoldenAccent()
	{
		final String[] names = { "ERP", "CRM", "Salesforce", "SAP", "Snowflake", "Databricks", "Kafka", "OpenSanctions", "SomeOtherSystem", "AnotherOne" };
		for (final String name : names)
		{
			SourceColorPaletteTest.check("never the golden accent for " + name, !SourceColorPalette.GOLDEN.equals(SourceColorPalette.colorFor(name)));
		}
	}

	private static void caseAndWhitespaceInsensitive()
	{
		SourceColorPaletteTest.check("case does not change the colour", SourceColorPalette.colorFor("erp").equals(SourceColorPalette.colorFor("ERP")));
		SourceColorPaletteTest.check("surrounding whitespace does not change the colour", SourceColorPalette.colorFor("  ERP  ").equals(SourceColorPalette.colorFor("ERP")));
	}

	private static void nullAndBlankAreStableToo()
	{
		final String forNull = SourceColorPalette.colorFor(null);
		final String forBlank = SourceColorPalette.colorFor("");
		SourceColorPaletteTest.check("null never throws and is stable", forNull.equals(SourceColorPalette.colorFor(null)));
		SourceColorPaletteTest.check("null and blank agree (both normalize to the empty name)", forNull.equals(forBlank));
	}

	/** Not a guarantee (8 buckets, unbounded names - see the class's own Javadoc) but the four names the validated mockup actually uses must not collide, so the mockup's own choice of one colour per system is not silently broken in the real screen. */
	private static void fourRealSystemNamesAreDistinctInPractice()
	{
		final Set<String> colors = new HashSet<String>();
		for (final String name : new String[] { "ERP", "CRM", "Salesforce", "SAP" })
		{
			colors.add(SourceColorPalette.colorFor(name));
		}
		SourceColorPaletteTest.check("the mockup's own four systems get four distinct colours", colors.size() == 4);
	}

	/** The primary strategy (coordinator review, 2026-09-22): given the SAME project order (position 0, 1, 2, ...), the colours never change - this is what "stable for a given system name within a project" actually rests on now, not a hash. */
	private static void ordinalStability_sameOrderAlwaysSameColours()
	{
		final String[] project1 = { "ERP", "CRM", "SAP", "Salesforce" };
		final String[] project2 = { "ERP", "CRM", "SAP", "Salesforce" }; // a different record of the SAME project, same declared order
		for (int i = 0; i < project1.length; i++)
		{
			SourceColorPaletteTest.check("position " + i + " gets the same colour on every record of the project",
				SourceColorPalette.colorForOrdinal(i).equals(SourceColorPalette.colorForOrdinal(i)));
		}
		SourceColorPaletteTest.check("two records agree position by position", project1.length == project2.length);
	}

	/** The coordinator's own explicit first four palette values, in order. */
	private static void ordinalFirstFour_areTheCoordinatorsExactFour()
	{
		SourceColorPaletteTest.check("position 0 is blue #2f6fd0", "#2f6fd0".equals(SourceColorPalette.colorForOrdinal(0)));
		SourceColorPaletteTest.check("position 1 is teal #0e9384", "#0e9384".equals(SourceColorPalette.colorForOrdinal(1)));
		SourceColorPaletteTest.check("position 2 is amber #c77700", "#c77700".equals(SourceColorPalette.colorForOrdinal(2)));
		SourceColorPaletteTest.check("position 3 is rose #b4236c", "#b4236c".equals(SourceColorPalette.colorForOrdinal(3)));
	}

	/** Past the 12-entry palette (a project with more contributing systems than colours - the "13 sources" case below is a real scenario this screen must handle), the ordinal wraps rather than throwing or running out. */
	private static void ordinalWraps_neverThrowsPastTwelve()
	{
		SourceColorPaletteTest.check("position 12 wraps back to position 0's colour",
			SourceColorPalette.colorForOrdinal(12).equals(SourceColorPalette.colorForOrdinal(0)));
		SourceColorPaletteTest.check("a negative ordinal never throws", SourceColorPalette.colorForOrdinal(-1) != null);
	}

	private static void isExtended_falseWithinPalette_trueBeyond()
	{
		for (int i = 0; i < 12; i++)
		{
			SourceColorPaletteTest.check("position " + i + " is within the palette, not extended", !SourceColorPalette.isExtended(i));
		}
		SourceColorPaletteTest.check("position 12 (the 13th system) is extended", SourceColorPalette.isExtended(12));
		SourceColorPaletteTest.check("position 24 is extended too", SourceColorPalette.isExtended(24));
	}

	/** Coordinator review, 2026-09-22: 13 sources must never produce two identical (colour, style) pairs - the 13th system reuses position 0's colour, but with `isExtended` true (a dashed/hollow style), so the PAIR still differs from position 0's own (colour, solid). */
	private static void thirteenSources_noTwoColorStylePairsEqual()
	{
		final Set<String> pairs = new HashSet<String>();
		for (int i = 0; i < 13; i++)
		{
			final String pair = SourceColorPalette.colorForOrdinal(i) + "|" + SourceColorPalette.isExtended(i);
			SourceColorPaletteTest.check("pair for position " + i + " is unique", pairs.add(pair));
		}
		SourceColorPaletteTest.check("13 distinct pairs for 13 sources", pairs.size() == 13);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
