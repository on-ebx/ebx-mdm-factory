package com.on.ebx.factory.quality;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class QualityRuleCheckTest
{
	public static void main(final String[] pArgs)
	{
		QualityRuleCheckTest.blankOrNonStringAlwaysPasses();
		QualityRuleCheckTest.referenceChecksAgainstTheSameLookupsCleaningUses();
		QualityRuleCheckTest.checkDigitReusesQualityDetectorsAlgorithm();
		QualityRuleCheckTest.patternMatchesBicShape();
		QualityRuleCheckTest.guardedPatterns();
		QualityRuleCheckTest.uniqueAndMandatoryAreNotEvaluatedByPasses();
		QualityRuleCheckTest.uniqueSeverityIsNeverRejectEvenIfStoredThatWay();
		QualityRuleCheckTest.rejectSeverityHonouredForOtherKinds();
		QualityRuleCheckTest.duplicatesAmongGroupsByNormalisedValue();
		QualityRuleCheckTest.duplicatesAmongIgnoresBlankAndCapsExamples();
		System.out.println("QualityRuleCheckTest OK");
	}

	private static void blankOrNonStringAlwaysPasses()
	{
		final QualityRuleCheck.Rule rule = new QualityRuleCheck.Rule("1", QualityDetectors.PATTERN, "[A-Z]+", QualityDetectors.WARN);
		QualityRuleCheckTest.check("null value passes", QualityRuleCheck.passes(rule, null, null));
		QualityRuleCheckTest.check("blank string passes", QualityRuleCheck.passes(rule, "   ", null));
		QualityRuleCheckTest.check("a non-String typed value (e.g. a parsed Integer) always passes - not this class's concern",
			QualityRuleCheck.passes(rule, java.math.BigDecimal.TEN, null));
	}

	private static void referenceChecksAgainstTheSameLookupsCleaningUses()
	{
		final Map<String, String> flat = new LinkedHashMap<String, String>();
		flat.put(CleaningSteps.normalizeKey("FR"), "FR");
		final CleaningSteps.Lookups lookups = new CleaningSteps.Lookups(Collections.singletonMap("COUNTRY", flat), null);
		final QualityRuleCheck.Rule rule = new QualityRuleCheck.Rule("1", QualityDetectors.REFERENCE, "COUNTRY", QualityDetectors.MODEL_CONSTRAINT);
		QualityRuleCheckTest.check("FR resolves", QualityRuleCheck.passes(rule, "FR", lookups));
		QualityRuleCheckTest.check("XX does not resolve", !QualityRuleCheck.passes(rule, "XX", lookups));
		QualityRuleCheckTest.check("no lookups at all: never resolves, never crashes", !QualityRuleCheck.passes(rule, "FR", null));
	}

	/** A real SIREN (552120222, read live off Companies_Landing per CheckDigits' own Javadoc) and a broken one. */
	private static void checkDigitReusesQualityDetectorsAlgorithm()
	{
		final QualityRuleCheck.Rule rule = new QualityRuleCheck.Rule("1", QualityDetectors.CHECK_DIGIT, "SIREN", QualityDetectors.WARN);
		QualityRuleCheckTest.check("a real SIREN passes", QualityRuleCheck.passes(rule, "552120222", null));
		QualityRuleCheckTest.check("a real SIREN with separators still passes (normalised first)", QualityRuleCheck.passes(rule, "552 120 222", null));
		QualityRuleCheckTest.check("a broken checksum fails", !QualityRuleCheck.passes(rule, "552120221", null));
		QualityRuleCheckTest.check("directly matches QualityDetectors.isCheckDigitValid",
			QualityRuleCheck.passes(rule, "552120222", null) == QualityDetectors.isCheckDigitValid("SIREN", "552120222"));
	}

	private static void patternMatchesBicShape()
	{
		final QualityRuleCheck.Rule rule = new QualityRuleCheck.Rule("1", QualityDetectors.PATTERN, "[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?", QualityDetectors.WARN);
		QualityRuleCheckTest.check("8-char BIC passes", QualityRuleCheck.passes(rule, "BNPAFRPP", null));
		QualityRuleCheckTest.check("lower case still passes (upper-cased first)", QualityRuleCheck.passes(rule, "bnpafrpp", null));
		QualityRuleCheckTest.check("11-char BIC with branch code passes", QualityRuleCheck.passes(rule, "BNPAFRPPXXX", null));
		QualityRuleCheckTest.check("wrong shape fails", !QualityRuleCheck.passes(rule, "TOO-SHORT", null));
		final QualityRuleCheck.Rule malformed = new QualityRuleCheck.Rule("1", QualityDetectors.PATTERN, "[", QualityDetectors.WARN);
		QualityRuleCheckTest.check("a malformed stored pattern degrades to pass, never crashes the transfer", QualityRuleCheck.passes(malformed, "ANYTHING", null));
		QualityRuleCheckTest.check("...but is reported as NOT VALIDATED, never as a pass", QualityRuleCheck.verdict(malformed, "ANYTHING", null) == QualityRuleCheck.Verdict.NOT_VALIDATED
			&& malformed.problem() != null);
		QualityRuleCheckTest.check("a valid pattern answers PASS and FAIL", QualityRuleCheck.verdict(rule, "BNPAFRPP", null) == QualityRuleCheck.Verdict.PASS
			&& QualityRuleCheck.verdict(rule, "TOO-SHORT", null) == QualityRuleCheck.Verdict.FAIL && rule.problem() == null);
	}

	/**
	 * A stored rule is enforced exactly as before whatever its shape (only the length cap and the deadline apply), so a REJECT rule on an
	 * e-mail or domain pattern keeps rejecting; one that keeps timing out stops evaluating and is reported; a blank pattern is "nothing to check".
	 */
	private static void guardedPatterns()
	{
		final QualityRuleCheck.Rule domain = new QualityRuleCheck.Rule("d", QualityDetectors.PATTERN, "^[\\w.+-]+@([\\w-]+\\.)+[\\w-]{2,}$", QualityDetectors.REJECT);
		QualityRuleCheckTest.check("an e-mail rule passes a good value", QualityRuleCheck.verdict(domain, "a.b@mail.example.com", null) == QualityRuleCheck.Verdict.PASS);
		QualityRuleCheckTest.check("and FAILS a bad one: a REJECT rule keeps rejecting", QualityRuleCheck.verdict(domain, "not-an-email", null) == QualityRuleCheck.Verdict.FAIL
			&& domain.isRejectSeverity() && domain.problem() == null);
		// a shape the proposal-time heuristic refuses is still evaluated when it is stored (the deadline protects it)
		final QualityRuleCheck.Rule nested = new QualityRuleCheck.Rule("n", QualityDetectors.PATTERN, "^(A+)+$", QualityDetectors.REJECT);
		QualityRuleCheckTest.check("a stored (A+)+ rule is evaluated, not refused", nested.problem() == null && QualityRuleCheck.verdict(nested, "AAAA", null) == QualityRuleCheck.Verdict.PASS
			&& QualityRuleCheck.verdict(nested, "AAAB", null) == QualityRuleCheck.Verdict.FAIL);
		final StringBuilder tooLong = new StringBuilder();
		for (int i = 0; i <= RegexGuard.MAX_STORED_PATTERN_LENGTH; i++)
		{
			tooLong.append('A');
		}
		final StringBuilder longButStored = new StringBuilder("^");
		for (int i = 0; i < 400; i++)
		{
			longButStored.append('A');
		}
		final QualityRuleCheck.Rule storedLong = new QualityRuleCheck.Rule("s", QualityDetectors.PATTERN, longButStored.append('$').toString(), QualityDetectors.REJECT);
		QualityRuleCheckTest.check("a stored pattern longer than a proposed one may be is still enforced", storedLong.problem() == null
			&& QualityRuleCheck.verdict(storedLong, "B", null) == QualityRuleCheck.Verdict.FAIL);
		final QualityRuleCheck.Rule longRule = new QualityRuleCheck.Rule("l", QualityDetectors.PATTERN, tooLong.toString(), QualityDetectors.WARN);
		QualityRuleCheckTest.check("a too-long stored pattern is NOT_VALIDATED", QualityRuleCheck.verdict(longRule, "A", null) == QualityRuleCheck.Verdict.NOT_VALIDATED
			&& longRule.problem().contains("characters long"));
		final QualityRuleCheck.Rule blank = new QualityRuleCheck.Rule("b", QualityDetectors.PATTERN, "  ", QualityDetectors.WARN);
		QualityRuleCheckTest.check("a blank pattern passes and is no problem", QualityRuleCheck.verdict(blank, "X", null) == QualityRuleCheck.Verdict.PASS && blank.problem() == null);

		// a pattern the shape test does not see, on a value that makes it run away: abandoned, then the rule stops evaluating
		final QualityRuleCheck.Rule evasive = new QualityRuleCheck.Rule("e", QualityDetectors.PATTERN, "^(.*A){28}$", QualityDetectors.REJECT);
		QualityRuleCheckTest.check("no problem is known before it runs", evasive.problem() == null);
		final String nearMatch = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA!";
		for (int i = 0; i < QualityRuleCheck.MAX_TIMEOUTS; i++)
		{
			QualityRuleCheckTest.check("evaluation " + (i + 1) + " is abandoned, not passed", QualityRuleCheck.verdict(evasive, nearMatch, null) == QualityRuleCheck.Verdict.NOT_VALIDATED);
		}
		// pinned: a REJECT rule whose evaluation timed out answers NOT_VALIDATED, never FAIL - StagingLoader rejects a row only on FAIL (it skips
		// NOT_VALIDATED before the severity test, see StagingLoaderQualityVerdictSourceTest) and a WARN names the rule once (QualityRuleProblems)
		QualityRuleCheckTest.check("a timed-out REJECT rule does not reject", evasive.isRejectSeverity() && QualityRuleCheck.verdict(evasive, nearMatch, null) != QualityRuleCheck.Verdict.FAIL);
		QualityRuleCheckTest.check("after " + QualityRuleCheck.MAX_TIMEOUTS + " time-outs the rule is reported and stops evaluating", evasive.timeoutCount() == QualityRuleCheck.MAX_TIMEOUTS
			&& evasive.problem() != null && evasive.problem().contains("time limit"));
		final long before = System.nanoTime();
		QualityRuleCheckTest.check("a disabled rule answers at once", QualityRuleCheck.verdict(evasive, nearMatch, null) == QualityRuleCheck.Verdict.NOT_VALIDATED
			&& (System.nanoTime() - before) / 1000000L < 20L);
	}

	private static void uniqueAndMandatoryAreNotEvaluatedByPasses()
	{
		final QualityRuleCheck.Rule unique = new QualityRuleCheck.Rule("1", QualityDetectors.UNIQUE, null, QualityDetectors.WARN);
		QualityRuleCheckTest.check("UNIQUE is not a per-value check: passes() never fails it", QualityRuleCheck.passes(unique, "ANYTHING", null));
		final QualityRuleCheck.Rule mandatory = new QualityRuleCheck.Rule("1", QualityDetectors.MANDATORY, null, QualityDetectors.WARN);
		QualityRuleCheckTest.check("MANDATORY is not executed here either (the attribute facet already covers it)",
			QualityRuleCheck.passes(mandatory, "ANYTHING", null));
	}

	/** The task's own explicit instruction: UNIQUE is WARN-only, never REJECT, whatever is stored. */
	private static void uniqueSeverityIsNeverRejectEvenIfStoredThatWay()
	{
		final QualityRuleCheck.Rule uniqueRejectStored = new QualityRuleCheck.Rule("1", QualityDetectors.UNIQUE, null, QualityDetectors.REJECT);
		QualityRuleCheckTest.check("UNIQUE never rejects, even if severity is stored as REJECT", !uniqueRejectStored.isRejectSeverity());
	}

	private static void rejectSeverityHonouredForOtherKinds()
	{
		final QualityRuleCheck.Rule checkDigitReject = new QualityRuleCheck.Rule("1", QualityDetectors.CHECK_DIGIT, "IBAN", QualityDetectors.REJECT);
		QualityRuleCheckTest.check("a REJECT-severity CHECK_DIGIT rule is honoured", checkDigitReject.isRejectSeverity());
		final QualityRuleCheck.Rule checkDigitWarn = new QualityRuleCheck.Rule("1", QualityDetectors.CHECK_DIGIT, "IBAN", QualityDetectors.WARN);
		QualityRuleCheckTest.check("a WARN-severity rule is never a reject", !checkDigitWarn.isRejectSeverity());
	}

	private static void duplicatesAmongGroupsByNormalisedValue()
	{
		final List<QualityRuleCheck.RowValue> rows = Arrays.asList(new QualityRuleCheck.RowValue("ERP|V100150", "FR76662042449"),
			new QualityRuleCheck.RowValue("ERP|V109010", "fr76 662042449"), // same VAT, different spelling/case - normalizeKey folds both
			new QualityRuleCheck.RowValue("ERP|V100160", "FR00000000000")); // a distinct value, alone
		final QualityRuleCheck.UniqueOutcome outcome = QualityRuleCheck.duplicatesAmong(rows, 10);
		QualityRuleCheckTest.check("3 non-blank values checked", outcome.checked == 3);
		QualityRuleCheckTest.check("exactly the 2 duplicated rows counted, not the lone one", outcome.failing == 2);
		QualityRuleCheckTest.check("both duplicated rows' keys are in the examples", outcome.exampleKeys.contains("ERP|V100150") && outcome.exampleKeys.contains("ERP|V109010"));
		QualityRuleCheckTest.check("the lone row's key is not", !outcome.exampleKeys.contains("ERP|V100160"));
	}

	private static void duplicatesAmongIgnoresBlankAndCapsExamples()
	{
		final List<QualityRuleCheck.RowValue> withBlanks = Arrays.asList(new QualityRuleCheck.RowValue("A", null), new QualityRuleCheck.RowValue("B", "  "),
			new QualityRuleCheck.RowValue("C", "X"), new QualityRuleCheck.RowValue("D", "X"), new QualityRuleCheck.RowValue("E", "X"));
		final QualityRuleCheck.UniqueOutcome outcome = QualityRuleCheck.duplicatesAmong(withBlanks, 2);
		QualityRuleCheckTest.check("blanks never counted as checked: " + outcome.checked, outcome.checked == 3);
		QualityRuleCheckTest.check("all 3 X rows counted failing", outcome.failing == 3);
		QualityRuleCheckTest.check("examples capped at 2: " + outcome.exampleKeys.size(), outcome.exampleKeys.size() == 2);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
