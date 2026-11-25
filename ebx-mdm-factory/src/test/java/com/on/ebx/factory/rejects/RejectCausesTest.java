package com.on.ebx.factory.rejects;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free: run the main, a failure throws. Staging load and rejects lot (2026-10-17), item 3: the true cause of the rows set aside, counted by category, worded in both
 * languages (never "a rule stopped them" for an EBX refusal), read from the load result's {@code rejectedBy} and from one aggregate query.
 */
public final class RejectCausesTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		RejectCausesTest.oneCauseIsNamedWithoutACount();
		RejectCausesTest.severalCausesAreCounted();
		RejectCausesTest.noRuleIsBlamedForAnEbxRefusal();
		RejectCausesTest.readsTheLoadResult();
		RejectCausesTest.unknownCategoriesCountAsOther();
		RejectCausesTest.theQuery();
		RejectCausesTest.everyCauseIsWordedInBothLocales();
		RejectCausesTest.thePhraseForTheBanner();
		System.out.println("RejectCausesTest OK");
	}

	private static void oneCauseIsNamedWithoutACount()
	{
		final RejectCauses causes = new RejectCauses().add(RejectCategory.EBX_REFUSAL, 140);
		final String fr = causes.sentence(Locale.FRENCH);
		RejectCausesTest.check("French: " + fr, fr.startsWith("140 lignes mises de côté (refus d’EBX") && !fr.contains(": 140") && !fr.contains("règle"));
		final String en = causes.sentence(Locale.ENGLISH);
		RejectCausesTest.check("English: " + en, en.startsWith("140 rows set aside (refused by EBX") && !en.contains("rule"));
		RejectCausesTest.check("one row: singular", new RejectCauses().add(RejectCategory.PARENT_REJECTED, 1).sentence(Locale.ENGLISH).startsWith("1 row set aside"));
		RejectCausesTest.check("nothing set aside, nothing said", new RejectCauses().sentence(Locale.FRENCH).isEmpty() && RejectCauses.sentence(Locale.FRENCH, null).isEmpty());
	}

	private static void severalCausesAreCounted()
	{
		final RejectCauses causes = new RejectCauses().add(RejectCategory.QUALITY_RULE, 3).add(RejectCategory.EBX_REFUSAL, 137).add(RejectCategory.PARENT_REJECTED, 140);
		final String fr = causes.sentence(Locale.FRENCH);
		RejectCausesTest.check("the total leads: " + fr, fr.startsWith("280 lignes mises de côté ("));
		RejectCausesTest.check("each cause has its count, in the order of the categories: " + fr, fr.indexOf("règle de qualité : 3") > 0
			&& fr.indexOf("règle de qualité : 3") < fr.indexOf("EBX, le modèle de données n’accepte pas la ligne : 137") && fr.contains("parente a été rejetée : 140"));
		RejectCausesTest.check("counts by category", causes.count(RejectCategory.QUALITY_RULE) == 3L && causes.total() == 280L && causes.counts().size() == 3);
	}

	/** Staging integration (2026-10-17): the step 4 banner already says how many rows; it takes the cause alone. */
	private static void thePhraseForTheBanner()
	{
		final String one = new RejectCauses().add(RejectCategory.EBX_REFUSAL, 140).causePhrase(Locale.FRENCH);
		RejectCausesTest.check("one cause, no lead, no count: " + one, one.startsWith("refus d’EBX") && !one.contains("140") && !one.contains("mises de côté"));
		final String two = new RejectCauses().add(RejectCategory.EBX_REFUSAL, 140).add(RejectCategory.PARENT_REJECTED, 140).causePhrase(Locale.FRENCH);
		RejectCausesTest.check("two causes, each counted: " + two, two.contains("; ") && two.contains(": 140") && !two.contains("mises de côté"));
		RejectCausesTest.check("nothing, nothing said", new RejectCauses().causePhrase(Locale.FRENCH).isEmpty());
	}

	private static void noRuleIsBlamedForAnEbxRefusal()
	{
		RejectCausesTest.check("an EBX refusal is not a rule", !new RejectCauses().add(RejectCategory.EBX_REFUSAL, 5).blamesARule());
		RejectCausesTest.check("a quality rule is", new RejectCauses().add(RejectCategory.QUALITY_RULE, 1).blamesARule());
	}

	private static void readsTheLoadResult() throws Exception
	{
		final RejectCauses causes = RejectCauses.of(new ObjectMapper().readTree("{\"EBX_REFUSAL\":140,\"PARENT_REJECTED\":0,\"DATA_CHECK\":2}"));
		RejectCausesTest.check("the rejectedBy of a load result", causes.count(RejectCategory.EBX_REFUSAL) == 140L && causes.count(RejectCategory.DATA_CHECK) == 2L
			&& causes.count(RejectCategory.PARENT_REJECTED) == 0L && causes.total() == 142L);
		RejectCausesTest.check("a missing or odd node is no cause", RejectCauses.of(null).total() == 0L && RejectCauses.of(new ObjectMapper().readTree("[1]")).total() == 0L);
		final RejectCauses sum = new RejectCauses().addAll(causes).addAll(new RejectCauses().add(RejectCategory.EBX_REFUSAL, 10));
		RejectCausesTest.check("several entities add up", sum.count(RejectCategory.EBX_REFUSAL) == 150L);
	}

	private static void unknownCategoriesCountAsOther()
	{
		final RejectCauses causes = new RejectCauses().add(null, 2).add("", 1).add("WHATEVER", 4);
		RejectCausesTest.check("a row written before the category existed counts as other", causes.count(RejectCategory.OTHER) == 7L && causes.counts().size() == 1);
		final Map<String, Long> counts = new LinkedHashMap<String, Long>();
		counts.put(RejectCategory.OTHER, Long.valueOf(2));
		RejectCausesTest.check("plain counts too: " + RejectCauses.sentence(Locale.ENGLISH, counts), RejectCauses.sentence(Locale.ENGLISH, counts).contains("another reason"));
	}

	private static void theQuery()
	{
		final String sql = RejectSql.causes("it's", "STAGING");
		RejectCausesTest.check("one aggregate by entity and cause: " + sql, sql.startsWith("SELECT t.\"entity\".\"id\" AS ENT, COALESCE(t.\"category\", 'OTHER') AS CAT, COUNT(*) AS N FROM \"TransferReject\" t WHERE ")
			&& sql.contains("GROUP BY t.\"entity\".\"id\", COALESCE(t.\"category\", 'OTHER')"));
		RejectCausesTest.check("the project's quote is doubled, the stage is quoted, resolved rows are out",
			sql.contains("t.\"project\".\"code\" = 'it''s'") && sql.contains("t.\"stage\" = 'STAGING'") && sql.contains("COALESCE(t.\"resolved\", false) = false"));
	}

	private static void everyCauseIsWordedInBothLocales()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			for (final String category : RejectCategory.ALL)
			{
				final String sentence = new RejectCauses().add(category, 2).add(RejectCategory.OTHER, 1).sentence(locale);
				RejectCausesTest.check(category + " (" + locale + "): " + sentence, !sentence.contains("rejects.") && sentence.indexOf('{') < 0 && sentence.indexOf("''") < 0 && sentence.startsWith("3 "));
			}
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
