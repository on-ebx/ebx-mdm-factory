package com.on.ebx.factory.ebx;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Covers the
 * identifier-quoting guard ({@link CrossSystemMeasures#isQuotable}/{@link CrossSystemMeasures#quoteIdentifier})
 * - {@code sharedCount}/{@code perSystemFilledCounts}/{@code withinSystemDuplicateCount}/{@code
 * cosmeticVariantGroupsCount}/{@code divergingGroupsCount} themselves still need a real {@code Adaptation}/
 * {@code Session} and are exercised only against the live instance, never here (same reasoning every other
 * live-SQL test in this module already gives).
 * <p>
 * <b>Coordinator fix, 2026-09-22 18:20 (measured live on {@code Companies_Master/Master/"Company"})</b>: the
 * survivorship lot's own {@code divergingAfterFoldCount} measured the wrong thing under that name (32 of 33
 * "cosmetic" spelling variants reported as "divergence") - split into {@link
 * CrossSystemMeasures#cosmeticVariantGroupsSql}/{@link CrossSystemMeasures#divergingGroupsSql}, the exact SQL
 * TEXT each live-executing method sends, extracted purely so it CAN be asserted here without an {@code
 * Adaptation}/{@code Session} - the live numbers themselves (city: cosmetic 32/33, genuine divergence 0/33
 * when grouped by {@code nameKey}) are recorded in {@code docs/MATCHING.md} &sect;15, not re-derived here.
 * <p>
 * Coordinator review, 2026-09-21: an entity/field name reaches these methods from the factory's own tables
 * on the deterministic route, but from an entity/field name a language model itself proposed on the
 * `matching/suggest-llm` route (checked against the entity's real attributes by {@code
 * MatchPlanLlmPass.Session} before ever reaching a lookup - but that guard is Java string equality, not a
 * SQL-injection guard) - so a name carrying a double quote must never reach the generated SQL text
 * unescaped, whatever earlier layer looked at it.
 */
public final class CrossSystemMeasuresTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		CrossSystemMeasuresTest.ordinaryNamesAreQuotable();
		CrossSystemMeasuresTest.embeddedDoubleQuoteIsRefused();
		CrossSystemMeasuresTest.otherUnsafeCharactersAreRefused();
		CrossSystemMeasuresTest.nullAndBlankAreRefused();
		CrossSystemMeasuresTest.quoteIdentifierWrapsInDoubleQuotes();
		CrossSystemMeasuresTest.cosmeticVariantGroupsSql_groupsByTheAttributesOwnFoldedKey();
		CrossSystemMeasuresTest.divergingGroupsSql_groupsByTheEntitysIdentityKey();
		CrossSystemMeasuresTest.cosmeticAndDivergingSql_areNotAccidentallyIdentical();
		CrossSystemMeasuresTest.perSystemValidityCountsSql_groupsBySourceSystemAndField();
		System.out.println("CrossSystemMeasuresTest OK");
	}

	private static void ordinaryNamesAreQuotable()
	{
		CrossSystemMeasuresTest.check("a plain entity name", CrossSystemMeasures.isQuotable("Company"));
		CrossSystemMeasuresTest.check("a plain field name", CrossSystemMeasures.isQuotable("vatNo"));
		CrossSystemMeasuresTest.check("an underscore-led technical name", CrossSystemMeasures.isQuotable("_loadId"));
		CrossSystemMeasuresTest.check("digits after the first character", CrossSystemMeasures.isQuotable("field2"));
	}

	/**
	 * The one case this test exists for: a name carrying a double quote could otherwise close the
	 * generated SQL text's own quoting early and inject arbitrary SQL - refused outright (never escaped),
	 * degrading the measurement to "not measured" rather than building an unsafe statement.
	 */
	private static void embeddedDoubleQuoteIsRefused()
	{
		CrossSystemMeasuresTest.check("a trailing embedded double quote is refused", !CrossSystemMeasures.isQuotable("Company\""));
		CrossSystemMeasuresTest.check("a double quote followed by injected SQL is refused",
			!CrossSystemMeasures.isQuotable("Company\" a WHERE 1=1 --"));
		CrossSystemMeasuresTest.check("a leading embedded double quote is refused", !CrossSystemMeasures.isQuotable("\"Company"));
	}

	/** Every other character this factory's own entity/field names never carry either - a single quote, a space, a semicolon, a dot, a backslash. */
	private static void otherUnsafeCharactersAreRefused()
	{
		CrossSystemMeasuresTest.check("a single quote is refused", !CrossSystemMeasures.isQuotable("Company'"));
		CrossSystemMeasuresTest.check("a space is refused", !CrossSystemMeasures.isQuotable("Company Inc"));
		CrossSystemMeasuresTest.check("a semicolon is refused", !CrossSystemMeasures.isQuotable("Company;DROP"));
		CrossSystemMeasuresTest.check("a dot is refused", !CrossSystemMeasures.isQuotable("schema.Company"));
		CrossSystemMeasuresTest.check("a backslash is refused", !CrossSystemMeasures.isQuotable("Company\\"));
		CrossSystemMeasuresTest.check("a leading digit is refused (not a legal identifier start)", !CrossSystemMeasures.isQuotable("1Company"));
	}

	private static void nullAndBlankAreRefused()
	{
		CrossSystemMeasuresTest.check("null is refused", !CrossSystemMeasures.isQuotable(null));
		CrossSystemMeasuresTest.check("an empty string is refused", !CrossSystemMeasures.isQuotable(""));
	}

	private static void quoteIdentifierWrapsInDoubleQuotes()
	{
		CrossSystemMeasuresTest.check("wraps in double quotes", "\"Company\"".equals(CrossSystemMeasures.quoteIdentifier("Company")));
		CrossSystemMeasuresTest.check("wraps sourceSystem the same way", "\"sourceSystem\"".equals(CrossSystemMeasures.quoteIdentifier("sourceSystem")));
	}

	/**
	 * {@link CrossSystemMeasures#cosmeticVariantGroupsSql} groups by the ATTRIBUTE's own folded key ({@code
	 * cityKey}) and counts DISTINCT RAW values ({@code city}) - the exact shape the coordinator measured live
	 * as answering 32 of 33 (cosmetic spelling variation), never "divergence" on its own.
	 */
	private static void cosmeticVariantGroupsSql_groupsByTheAttributesOwnFoldedKey()
	{
		final String sql = CrossSystemMeasures.cosmeticVariantGroupsSql("Company", "cityKey", "city");
		CrossSystemMeasuresTest.check("selects from the entity table", sql.contains("FROM \"Company\" a"));
		CrossSystemMeasuresTest.check("groups by the attribute's OWN folded key", sql.contains("GROUP BY a.\"cityKey\""));
		CrossSystemMeasuresTest.check("filters the folded key non-null", sql.contains("WHERE a.\"cityKey\" IS NOT NULL"));
		CrossSystemMeasuresTest.check("shared-across-systems condition", sql.contains("COUNT(DISTINCT a.\"sourceSystem\") > 1"));
		CrossSystemMeasuresTest.check("counts distinct RAW values inside each folded-key group", sql.contains("COUNT(DISTINCT a.\"city\") > 1"));
		CrossSystemMeasuresTest.check("aliased COSMETIC, not DIVERGING", sql.contains("AS COSMETIC"));
	}

	/**
	 * {@link CrossSystemMeasures#divergingGroupsSql} groups by the ENTITY's own identity key ({@code nameKey})
	 * and counts DISTINCT FOLDED values of the attribute under test ({@code cityKey}) - the fix: the coordinator
	 * measured this shape as answering 0 of 33 on the same data the cosmetic statement calls 32/33 divergent.
	 */
	private static void divergingGroupsSql_groupsByTheEntitysIdentityKey()
	{
		final String sql = CrossSystemMeasures.divergingGroupsSql("Company", "nameKey", "cityKey");
		CrossSystemMeasuresTest.check("selects from the entity table", sql.contains("FROM \"Company\" a"));
		CrossSystemMeasuresTest.check("groups by the ENTITY's own identity key, not the attribute's", sql.contains("GROUP BY a.\"nameKey\""));
		CrossSystemMeasuresTest.check("filters the identity key non-null", sql.contains("WHERE a.\"nameKey\" IS NOT NULL"));
		CrossSystemMeasuresTest.check("shared-across-systems condition", sql.contains("COUNT(DISTINCT a.\"sourceSystem\") > 1"));
		CrossSystemMeasuresTest.check("counts distinct FOLDED values of the attribute inside each identity group",
			sql.contains("COUNT(DISTINCT a.\"cityKey\") > 1"));
		CrossSystemMeasuresTest.check("aliased DIVERGING, not COSMETIC", sql.contains("AS DIVERGING"));
	}

	/** The bug this fix corrects, made impossible to reintroduce silently: the two statements must never end up textually identical for the same (entity, attribute) pair. */
	private static void cosmeticAndDivergingSql_areNotAccidentallyIdentical()
	{
		final String cosmetic = CrossSystemMeasures.cosmeticVariantGroupsSql("Company", "cityKey", "city");
		final String diverging = CrossSystemMeasures.divergingGroupsSql("Company", "nameKey", "cityKey");
		CrossSystemMeasuresTest.check("the two statements are never textually identical", !cosmetic.equals(diverging));
		CrossSystemMeasuresTest.check("cosmetic never groups by the entity's identity key", !cosmetic.contains("GROUP BY a.\"nameKey\""));
		CrossSystemMeasuresTest.check("diverging never groups by the attribute's own folded key", !diverging.contains("GROUP BY a.\"cityKey\""));
	}

	/**
	 * Lot 1 (2026-09-23, quality-informed survivorship): {@link CrossSystemMeasures#perSystemValidityCountsSql}
	 * groups by BOTH {@code sourceSystem} and the field itself - one row per DISTINCT (system, value) pair,
	 * never pre-aggregated across systems (that would lose exactly the per-system split the measure exists
	 * for). {@code ValidityAggregatorTest}/{@code ValidValueDerivationTest} cover the in-Java scoring this SQL
	 * text feeds; the live query itself (like every other live-SQL statement in this class) is exercised only
	 * against the real instance, never here.
	 */
	private static void perSystemValidityCountsSql_groupsBySourceSystemAndField()
	{
		final String sql = CrossSystemMeasures.perSystemValidityCountsSql("Company", "vatNo");
		CrossSystemMeasuresTest.check("selects from the entity table", sql.contains("FROM \"Company\" a"));
		CrossSystemMeasuresTest.check("groups by sourceSystem AND the field - never the field alone", sql.contains("GROUP BY a.\"sourceSystem\", a.\"vatNo\""));
		CrossSystemMeasuresTest.check("filters the field non-null", sql.contains("WHERE a.\"vatNo\" IS NOT NULL"));
		CrossSystemMeasuresTest.check("selects the system, the value and a count", sql.contains("a.\"sourceSystem\" AS SYS") && sql.contains("a.\"vatNo\" AS V")
			&& sql.contains("COUNT(*) AS N"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
