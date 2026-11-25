package com.on.ebx.factory.staging;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.Detectors;

/**
 * JUnit-free: run the main, a failure throws. Lot E (2026-10-17, coordinator addition (a)): the whole chain of cleaning steps, on the raw values.
 */
public final class StepChainCheckTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		StepChainCheckTest.aLaterStepThatStripsThePlusIsFound();
		StepChainCheckTest.theRepairedStepChangesNothingAfterTheRuleStep();
		StepChainCheckTest.onlyCharacterStrippingKindsAreRepaired();
		StepChainCheckTest.aRedundantStepChangesNothing();
		StepChainCheckTest.theLiveWebMobileNumbers();
		System.out.println("StepChainCheckTest OK");
	}

	private static List<CleaningSteps.Step> chain(final String... pKindAndArgument)
	{
		final List<CleaningSteps.Step> out = new ArrayList<CleaningSteps.Step>();
		for (int i = 0; i < pKindAndArgument.length; i += 2)
		{
			out.add(new CleaningSteps.Step(pKindAndArgument[i], pKindAndArgument[i + 1]));
		}
		return out;
	}

	/** The rule pass keeps the +, a model's NORMALISE_IDENTIFIER with " .+-" strips it: seen on the raw values, which the cleaned ones hid. */
	private static void aLaterStepThatStripsThePlusIsFound()
	{
		final List<Detectors.ValueCount> raw = NormalisationProfilerTest.counts("+33 6 01 31 09 18", 4, "+33 7 55 12 34 56", 2);
		final List<StepChainCheck.StepReport> reports = StepChainCheck.analyse(StepChainCheckTest.chain(CleaningSteps.STRIP_CHARS, " .-()/",
			CleaningSteps.NORMALISE_IDENTIFIER, " .+-"), raw, CleaningSteps.Lookups.empty());
		check("two reports", reports.size() == 2);
		check("the rule step changes every row and loses no +", reports.get(0).changedRows == 6L && reports.get(0).plusLostRows == 0L);
		check("the model's step drops the + of all 6 rows", reports.get(1).plusLostRows == 6L && reports.get(1).seenRows == 6L);
	}

	private static void theRepairedStepChangesNothingAfterTheRuleStep()
	{
		final String repaired = StepChainCheck.withoutPlus(CleaningSteps.NORMALISE_IDENTIFIER, " .+-");
		check("the + comes out of the argument, nothing else", " .-".equals(repaired));
		final List<Detectors.ValueCount> raw = NormalisationProfilerTest.counts("+33 6 01 31 09 18", 4, "+33 7 55 12 34 56", 2);
		final List<StepChainCheck.StepReport> reports = StepChainCheck.analyse(StepChainCheckTest.chain(CleaningSteps.STRIP_CHARS, " .-()/",
			CleaningSteps.NORMALISE_IDENTIFIER, repaired), raw, CleaningSteps.Lookups.empty());
		check("no + lost any more", reports.get(0).plusLostRows == 0L && reports.get(1).plusLostRows == 0L);
		check("the repaired step is redundant once the rule step has run", reports.get(1).redundant());
		check("a step that has something to do is not", !reports.get(0).redundant());
	}

	private static void onlyCharacterStrippingKindsAreRepaired()
	{
		check("STRIP_CHARS", " .-".equals(StepChainCheck.withoutPlus("STRIP_CHARS", "+ .-")));
		check("no + in the argument: nothing to repair", StepChainCheck.withoutPlus("STRIP_CHARS", " .-") == null && StepChainCheck.withoutPlus("NORMALISE_IDENTIFIER", null) == null);
		check("another kind is not rewritten", StepChainCheck.withoutPlus("EMPTY_TO_NULL", "+") == null && !StepChainCheck.stripsCharacters("UPPER"));
		check("a lone + leaves an empty argument (the redundancy check withdraws it)", "".equals(StepChainCheck.withoutPlus("STRIP_CHARS", "+")));
	}

	private static void aRedundantStepChangesNothing()
	{
		final List<Detectors.ValueCount> raw = NormalisationProfilerTest.counts("Dupont", 3, "Martin", 2);
		final List<StepChainCheck.StepReport> reports = StepChainCheck.analyse(StepChainCheckTest.chain(CleaningSteps.UPPER, null, CleaningSteps.UPPER, null,
			CleaningSteps.TRIM, null), raw, CleaningSteps.Lookups.empty());
		check("the first UPPER works", !reports.get(0).redundant() && reports.get(0).changedRows == 5L);
		check("the second changes none of the 5 rows", reports.get(1).redundant() && reports.get(1).seenRows == 5L);
		check("a TRIM on clean values is redundant too", reports.get(2).redundant());
		check("an empty chain or no value gives nothing", StepChainCheck.analyse(new ArrayList<CleaningSteps.Step>(), raw, CleaningSteps.Lookups.empty()).isEmpty()
			&& StepChainCheck.analyse(StepChainCheckTest.chain(CleaningSteps.UPPER, null), null, CleaningSteps.Lookups.empty()).get(0).seenRows == 0L);
		check("an emptied value (EMPTY_TO_NULL) counts as a change and stops downstream", StepChainCheck.analyse(StepChainCheckTest.chain(CleaningSteps.EMPTY_TO_NULL, null,
			CleaningSteps.UPPER, null), NormalisationProfilerTest.counts("N/A", 2, "abc", 1), CleaningSteps.Lookups.empty()).get(0).changedRows == 2L);
	}

	/** The real web mobile numbers of the B2C files: the live chain 110/112 is found, and its repair leaves a redundant step. */
	private static void theLiveWebMobileNumbers() throws Exception
	{
		final File dir = new File(System.getProperty("mdmf.b2cDir", "test-landing-b2c-2026-10-17/fichiers"));
		final File web = new File(dir, "ECOM_Clients.json");
		if (!web.isFile())
		{
			System.out.println("  (B2C files not found: real-file check skipped)");
			return;
		}
		final List<Detectors.ValueCount> mobiles = NormalisationPassTest.jsonStrings(web, "mobile");
		final NormalisationProfile profile = NormalisationProfiler.profile(mobiles, -1L);
		check("the real mobiles are phone-like with a leading +", profile.isPhoneLike() && profile.leadingPlusRows() == 160L);
		final List<StepChainCheck.StepReport> live = StepChainCheck.analyse(StepChainCheckTest.chain(CleaningSteps.STRIP_CHARS, " ", CleaningSteps.NORMALISE_IDENTIFIER, " .+-"), mobiles,
			CleaningSteps.Lookups.empty());
		check("live chain: the second step drops the + of all 160 rows", live.get(1).plusLostRows == 160L);
		final List<StepChainCheck.StepReport> fixed = StepChainCheck.analyse(StepChainCheckTest.chain(CleaningSteps.STRIP_CHARS, " ", CleaningSteps.NORMALISE_IDENTIFIER,
			StepChainCheck.withoutPlus(CleaningSteps.NORMALISE_IDENTIFIER, " .+-")), mobiles, CleaningSteps.Lookups.empty());
		check("repaired: no + lost, the step is redundant", fixed.get(1).plusLostRows == 0L && fixed.get(1).redundant());
		final List<String> kinds = Arrays.asList(fixed.get(0).kind, fixed.get(1).kind);
		check("kinds are reported upper-case", kinds.equals(Arrays.asList("STRIP_CHARS", "NORMALISE_IDENTIFIER")));
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("StepChainCheckTest: " + pLabel);
		}
	}
}
