package com.on.ebx.factory.stagingdesk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.quality.FailureBreakdown;
import com.on.ebx.factory.quality.QualityLlmPass;

/**
 * Validation desk lot L4 (docs/STAGING-DESK.md): the check view. The DemoBala postal-code check: {@code ^[0-9]{5}$}, 287 of 335 pass, the 48 others are the postal codes of seven
 * other countries; the model proposes the condition {@code countryCode = FR}. JUnit-free: run the main, a failure throws.
 */
public final class DeskCheckTest
{
	private static final String PATTERN = "^[0-9]{5}$";

	public static void main(final String[] pArgs)
	{
		DeskCheckTest.figuresAndTheConditionFollowTheBreakdown();
		DeskCheckTest.theThreeChoicesPostTheExistingDecisions();
		DeskCheckTest.aRuleWithoutConditionOffersAcceptAndReject();
		DeskCheckTest.aRuleNeverMeasuredOffersTheMeasureButton();
		DeskCheckTest.aMeasuredRuleWithNoBreakdownSaysSo();
		DeskCheckTest.aRuleThatPassesEverythingIsAlreadySafe();
		DeskCheckTest.theTitleIsTheCheckInWords();
		DeskCheckTest.everythingIsEscapedAndFrenchExists();
		System.out.println("DeskCheckTest OK");
	}

	private static String breakdownJson()
	{
		final List<Detectors.ValueCount> pairs = new ArrayList<Detectors.ValueCount>();
		pairs.add(new Detectors.ValueCount("75001", "FR", 247));
		pairs.add(new Detectors.ValueCount("10115", "DE", 40));
		pairs.add(new Detectors.ValueCount("1201", "CH", 13));
		pairs.add(new Detectors.ValueCount("1000", "BE", 9));
		pairs.add(new Detectors.ValueCount("111 20", "SE", 8));
		pairs.add(new Detectors.ValueCount("1000-001", "PT", 6));
		pairs.add(new Detectors.ValueCount("00-001", "PL", 5));
		pairs.add(new Detectors.ValueCount("1011AB", "NL", 4));
		pairs.add(new Detectors.ValueCount("D02", "IE", 3));
		return FailureBreakdown.toJson(FailureBreakdown.best(Arrays.asList(new FailureBreakdown.Joint("countryCode", pairs)),
			QualityLlmPass.judge("PATTERN", DeskCheckTest.PATTERN, null), QualityLlmPass.shower(QualityLlmPass.Mode.SAMPLE_VALUES)));
	}

	private static DeskCheck conditioned()
	{
		return new DeskCheck("77", "Adresse", "postalCode", "PATTERN", DeskCheckTest.PATTERN, "WARN", "five digits in France", Long.valueOf(335), Long.valueOf(48),
			DeskCheckTest.breakdownJson(), "countryCode", Arrays.asList("FR"));
	}

	private static String html(final DeskCheck pCheck, final boolean pReadOnly, final Locale pLocale)
	{
		final StringBuilder out = new StringBuilder();
		DeskCheckHtml.render(out, pCheck, 2, 2, pReadOnly, pLocale);
		return out.toString();
	}

	private static void figuresAndTheConditionFollowTheBreakdown()
	{
		final DeskCheck check = DeskCheckTest.conditioned();
		check("measured, 287 pass, 48 fail", check.measured() && check.passingCount() == 287 && check.failingCount() == 48 && check.flaggedAsProposed() == 48);
		check("the proposed condition is complete", check.hasCondition());
		final long[] within = check.withinCondition();
		check("France: 247 values, 0 flagged today", within != null && within[0] == 247 && within[1] == 0);
		check("seven groups fail", check.failingGroups() == 7);
		check("the groups that pass", check.cleanGroups().contains("FR") && check.cleanGroups().contains("DE"));
		final DeskCheck otherField = new DeskCheck("78", "Adresse", "postalCode", "PATTERN", DeskCheckTest.PATTERN, "WARN", "", Long.valueOf(335), Long.valueOf(48),
			DeskCheckTest.breakdownJson(), "addressType", Arrays.asList("MAILING"));
		check("a breakdown by another field says nothing of the condition", otherField.withinCondition() == null);
	}

	private static void theThreeChoicesPostTheExistingDecisions()
	{
		final String html = DeskCheckTest.html(DeskCheckTest.conditioned(), false, Locale.ENGLISH);
		check("the table by country, with the 13 of Switzerland: " + html.length(), html.contains("<strong>CH</strong>") && html.contains("<code>1201</code>") && html.contains("The 48 values it would flag, by countryCode"));
		check("accept with the condition posts ACCEPT_GUARD on the rule", html.contains("mdskDo('ACCEPT_GUARD','77','QUALITY',false)") && html.contains("Accept for FR only"));
		check("it says how many values it would flag today", html.contains("Applies when countryCode is FR: 247 values, 0 flagged today"));
		check("accept as proposed drops the condition first, and says how many it flags", html.contains("mdskDo('ACCEPT_GUARD_AS_PROPOSED','77','QUALITY',false)") && html.contains("48 values would carry a warning"));
		check("reject posts REJECT_GUARD", html.contains("mdskDo('REJECT_GUARD','77','QUALITY',false)") && html.contains("No check on postalCode"));
		check("back to the list", html.contains("mdskOpenCheck('')") && html.contains("Validation desk") && html.contains("Check 2 of 2"));
		check("not a read-only page", !html.contains("disabled=\"disabled\""));
		check("read only disables the buttons", DeskCheckTest.html(DeskCheckTest.conditioned(), true, Locale.ENGLISH).contains("disabled=\"disabled\""));
	}

	private static void aRuleWithoutConditionOffersAcceptAndReject()
	{
		final DeskCheck check = new DeskCheck("77", "Adresse", "postalCode", "PATTERN", DeskCheckTest.PATTERN, "WARN", "", Long.valueOf(335), Long.valueOf(48), DeskCheckTest.breakdownJson(), "",
			Collections.<String> emptyList());
		final String html = DeskCheckTest.html(check, false, Locale.ENGLISH);
		check("no condition card", !html.contains("Accept for") && !html.contains("ACCEPT_GUARD_AS_PROPOSED"));
		check("plain accept and reject, with the figure", html.contains("mdskDo('ACCEPT_GUARD','77','QUALITY',false)") && html.contains("48 values would carry a warning") && html.contains("mdskDo('REJECT_GUARD'"));
	}

	private static void aRuleNeverMeasuredOffersTheMeasureButton()
	{
		final DeskCheck check = new DeskCheck("77", "Adresse", "postalCode", "PATTERN", DeskCheckTest.PATTERN, "WARN", "", Long.valueOf(335), Long.valueOf(48), "", "", Collections.<String> emptyList());
		check("can be measured", check.canMeasure() && !check.breakdownMeasured());
		final String html = DeskCheckTest.html(check, false, Locale.ENGLISH);
		check("it says it was not measured and offers the button", html.contains("was not measured") && html.contains("mdskDo('MEASURE_CHECK','77','',false)"));
		check.measuring = true;
		final String running = DeskCheckTest.html(check, false, Locale.ENGLISH);
		check("while the job runs: no button, a chip", !running.contains("MEASURE_CHECK") && running.contains("Measuring and asking the model..."));
		// lot L4b: the breakdown is stored, the model is still being asked about a condition
		final DeskCheck asking = new DeskCheck("77", "Adresse", "postalCode", "PATTERN", DeskCheckTest.PATTERN, "WARN", "", Long.valueOf(335), Long.valueOf(48), DeskCheckTest.breakdownJson(), "",
			Collections.<String> emptyList());
		check("measured and idle: no chip", !DeskCheckTest.html(asking, false, Locale.ENGLISH).contains("asking the model"));
		asking.measuring = true;
		check("measured while the model is asked: the chip says so", DeskCheckTest.html(asking, false, Locale.ENGLISH).contains("Measuring and asking the model..."));
		final DeskCheck unmeasured = new DeskCheck("78", "Adresse", "postalCode", "UNIQUE", "", "WARN", "", null, null, "", "", Collections.<String> emptyList());
		check("a kind that is not broken down has no button and says why", !unmeasured.canMeasure() && DeskCheckTest.html(unmeasured, false, Locale.ENGLISH).contains("not broken down"));
		check("never measured: says so plainly", DeskCheckTest.html(unmeasured, false, Locale.ENGLISH).contains("Not measured yet"));
	}

	private static void aMeasuredRuleWithNoBreakdownSaysSo()
	{
		final DeskCheck check = new DeskCheck("77", "Adresse", "postalCode", "PATTERN", DeskCheckTest.PATTERN, "WARN", "", Long.valueOf(335), Long.valueOf(48), FailureBreakdown.NOT_FOUND_JSON, "",
			Collections.<String> emptyList());
		check("measured, nothing to show", check.breakdownMeasured() && check.breakdown() == null && !check.canMeasure());
		final String html = DeskCheckTest.html(check, false, Locale.ENGLISH);
		check("it says no other field separates the failures, no measure button", html.contains("No other field of this object separates") && !html.contains("MEASURE_CHECK"));
	}

	private static void aRuleThatPassesEverythingIsAlreadySafe()
	{
		final DeskCheck check = new DeskCheck("5", "Adresse", "countryCode", "REFERENCE", "COUNTRY", "MODEL_CONSTRAINT", "", Long.valueOf(345), Long.valueOf(0), "", "", Collections.<String> emptyList());
		check("already safe", check.alreadySafe() && !check.canMeasure());
		final String html = DeskCheckTest.html(check, false, Locale.ENGLISH);
		check("the strip, with Accept and Reject: " + html, html.contains("Already safe") && html.contains("345 of 345 values pass") && html.contains("mdskDo('ACCEPT_GUARD','5','QUALITY',false)")
			&& html.contains("mdskDo('REJECT_GUARD','5'") && !html.contains("Your choice"));
	}

	private static void theTitleIsTheCheckInWords()
	{
		check("pattern", DeskCheckHtml.title(DeskCheckTest.conditioned(), Locale.ENGLISH).equals("postalCode matches ^[0-9]{5}$"));
		check("reference", DeskCheckHtml.title(new DeskCheck("1", "A", "country", "REFERENCE", "COUNTRY", "WARN", "", null, null, "", "", null), Locale.ENGLISH).equals("country is in the COUNTRY list"));
		check("check digit", DeskCheckHtml.title(new DeskCheck("1", "A", "iban", "CHECK_DIGIT", "IBAN", "WARN", "", null, null, "", "", null), Locale.ENGLISH).contains("valid IBAN check digit"));
	}

	private static void everythingIsEscapedAndFrenchExists()
	{
		final DeskCheck hostile = new DeskCheck("77\"><b>", "Adresse", "<i>x</i>", "PATTERN", "<script>alert(1)</script>", "WARN", "<b>why</b>", Long.valueOf(10), Long.valueOf(2),
			"{\"attribute\":\"<u>c</u>\",\"groups\":[{\"value\":\"<img src=x>\",\"seen\":5,\"failing\":2,\"examples\":[\"<s>e</s>\"]},{\"value\":\"ok\",\"seen\":5,\"failing\":0,\"examples\":[]}]}", "", null);
		final String html = DeskCheckTest.html(hostile, false, Locale.ENGLISH);
		check("no raw markup from the data: " + html, !html.contains("<script>") && !html.contains("<img") && !html.contains("<b>why") && !html.contains("<i>x") && !html.contains("<s>e") && !html.contains("<u>c"));
		final String french = DeskCheckTest.html(DeskCheckTest.conditioned(), false, Locale.FRENCH);
		check("the French page is not the English one", french.contains("Accepter pour FR seulement") && !french.contains("Accept for FR only"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
