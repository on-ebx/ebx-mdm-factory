package com.on.ebx.factory.landing;

/**
 * Landing key validated in the walkthrough (2026-10-17). JUnit-free: run the main, a failure throws. Pins {@link ValidatedKey}, the decision the landing run makes for a
 * table whose application the person validated, and {@link LandingKeyRules}.
 */
public final class ValidatedKeyTest
{
	public static void main(final String[] pArgs)
	{
		ValidatedKeyTest.whatTheValidationCovers();
		ValidatedKeyTest.aValidatedDeducedKeyIsAccepted();
		ValidatedKeyTest.aKeyProvedNotUniqueFailsWithTheReason();
		ValidatedKeyTest.anApplicationNotValidatedFollowsTheOrdinaryRules();
		ValidatedKeyTest.theSourceIsAskedOnlyWhenTheSampleDidNotProveTheOpposite();
		ValidatedKeyTest.reconciliationNeverConfirmsAWaitingKey();
		ValidatedKeyTest.aBuiltNeverLoadedTableTakesAFirstChoiceWhateverItsProposal();
		ValidatedKeyTest.theWalkthroughOffersTheChooserForWaitingTablesOnly();
		System.out.println("ValidatedKeyTest OK");
	}

	/** The keys the walkthrough shows as "deduced - N % - checked at the load": a rule key, a model key at or above the bar. Not: a person's, the source's, a model key under the bar, no key. */
	private static void whatTheValidationCovers()
	{
		ValidatedKeyTest.check("a RULE key at 60 % is covered (the live case)", ValidatedKey.covers("RULE", 60, true));
		ValidatedKeyTest.check("a model key at the bar is covered", ValidatedKey.covers("LLM", KeyCertainty.LLM_CONFIDENCE_MIN, true));
		ValidatedKeyTest.check("a model key under the bar is the walkthrough's 'no key': not covered", !ValidatedKey.covers("LLM", KeyCertainty.LLM_CONFIDENCE_MIN - 1, true));
		ValidatedKeyTest.check("USER is already settled: not covered", !ValidatedKey.covers("USER", 100, true));
		ValidatedKeyTest.check("SOURCE is already settled: not covered", !ValidatedKey.covers("SOURCE", 100, true));
		ValidatedKeyTest.check("DEDUCED (confirmed by a person) is already settled: not covered", !ValidatedKey.covers("DEDUCED", 100, true));
		ValidatedKeyTest.check("no key columns: not covered", !ValidatedKey.covers("RULE", 60, false));
	}

	private static void aValidatedDeducedKeyIsAccepted()
	{
		final ValidatedKey.Verdict measured = ValidatedKey.decide(true, "RULE", 60, true, Boolean.TRUE, null);
		ValidatedKeyTest.check("unique in the sample, source not verifiable (null): accepted, measured", measured.accepted() && measured.measured && !measured.failed());
		final ValidatedKey.Verdict both = ValidatedKey.decide(true, "RULE", 60, true, Boolean.TRUE, Boolean.TRUE);
		ValidatedKeyTest.check("unique in the sample and across the source: accepted", both.accepted() && both.measured);
		final ValidatedKey.Verdict unread = ValidatedKey.decide(true, "RULE", 60, true, null, null);
		ValidatedKeyTest.check("a sample that could not be read does not block a key the person validated: accepted, NOT measured", unread.accepted() && !unread.measured);
		ValidatedKeyTest.check("the very case of the live defect never waits: no verdict is a wait", !"WAIT".equals(unread.kind) && !ValidatedKey.NOT_APPLICABLE.equals(unread.kind));
	}

	private static void aKeyProvedNotUniqueFailsWithTheReason()
	{
		final ValidatedKey.Verdict sample = ValidatedKey.decide(true, "RULE", 60, true, Boolean.FALSE, null);
		ValidatedKeyTest.check("a repeated or empty value in the sample: fails, reason = sample", sample.failed() && ValidatedKey.NOT_UNIQUE_SAMPLE.equals(sample.kind));
		final ValidatedKey.Verdict source = ValidatedKey.decide(true, "RULE", 60, true, Boolean.TRUE, Boolean.FALSE);
		ValidatedKeyTest.check("a collision found across the source: fails, reason = source", source.failed() && ValidatedKey.NOT_UNIQUE_SOURCE.equals(source.kind));
		final ValidatedKey.Verdict both = ValidatedKey.decide(true, "RULE", 60, true, Boolean.FALSE, Boolean.FALSE);
		ValidatedKeyTest.check("both prove it: the sample's reason comes first", ValidatedKey.NOT_UNIQUE_SAMPLE.equals(both.kind));
		// the words the cell carries, in both languages, with the key named
		final String english = RunTexts.keyValidatedNotUnique("customerId", false).english;
		ValidatedKeyTest.check("the failure names the key and says what to do", english.contains("customerId") && english.contains("not unique in the sample") && english.contains("Choose another key"));
		ValidatedKeyTest.check("the source failure says so", RunTexts.keyValidatedNotUnique("a,b", true).english.contains("a+b") && RunTexts.keyValidatedNotUnique("a,b", true).english.contains("whole source"));
		ValidatedKeyTest.check("the accepted cell says measured or not", RunTexts.keyValidated("customerId", true).english.equals("customerId (validated by a person, unique in the sample)")
			&& RunTexts.keyValidated("customerId", false).english.contains("not measured"));
	}

	private static void anApplicationNotValidatedFollowsTheOrdinaryRules()
	{
		ValidatedKeyTest.check("not validated: the ordinary rules decide (a wait stays a wait)", ValidatedKey.NOT_APPLICABLE.equals(ValidatedKey.decide(false, "RULE", 60, true, Boolean.TRUE, null).kind));
		ValidatedKeyTest.check("not validated, proved not unique: still the ordinary rules", ValidatedKey.NOT_APPLICABLE.equals(ValidatedKey.decide(false, "RULE", 60, true, Boolean.FALSE, null).kind));
		ValidatedKeyTest.check("validated but a settled key: the ordinary short-circuit handles it", ValidatedKey.NOT_APPLICABLE.equals(ValidatedKey.decide(true, "USER", 100, true, Boolean.FALSE, null).kind));
		ValidatedKeyTest.check("validated, no key: the table still waits for a person", ValidatedKey.NOT_APPLICABLE.equals(ValidatedKey.decide(true, "", 0, false, null, null).kind));
		ValidatedKeyTest.check("validated, model key under the bar: still a proposal", ValidatedKey.NOT_APPLICABLE.equals(ValidatedKey.decide(true, "LLM", 85, true, Boolean.TRUE, Boolean.TRUE).kind));
	}

	private static void theSourceIsAskedOnlyWhenTheSampleDidNotProveTheOpposite()
	{
		ValidatedKeyTest.check("sample unique: ask the source", ValidatedKey.askSource(Boolean.TRUE));
		ValidatedKeyTest.check("sample unknown: ask the source", ValidatedKey.askSource(null));
		ValidatedKeyTest.check("sample proved not unique: no need to ask", !ValidatedKey.askSource(Boolean.FALSE));
	}

	/** The live defect: a built table with a proposed key had its WAITING key cell settled "confirmed" by the status poll, hiding the chooser while FLOW/LOAD waited for ever. */
	private static void reconciliationNeverConfirmsAWaitingKey()
	{
		ValidatedKeyTest.check("built, key columns, cell WAITING: the person has not confirmed it", !LandingKeyRules.reconcileMayConfirmKey(true, LandingRunProgress.WAITING));
		ValidatedKeyTest.check("built, key columns, cell still TODO: as before", LandingKeyRules.reconcileMayConfirmKey(true, LandingRunProgress.TODO));
		ValidatedKeyTest.check("built, key columns, cell RUNNING: as before", LandingKeyRules.reconcileMayConfirmKey(true, LandingRunProgress.RUNNING));
		ValidatedKeyTest.check("built, no key columns: never (project ret, 2026-09-26)", !LandingKeyRules.reconcileMayConfirmKey(false, LandingRunProgress.TODO));
		ValidatedKeyTest.check("built, no key columns, WAITING: never", !LandingKeyRules.reconcileMayConfirmKey(false, LandingRunProgress.WAITING));
		// end to end on the grid: a waiting table stays waiting, so the run is not over and FLOW/LOAD are not left behind a settled KEY
		final LandingRunProgress progress = LandingRunProgress.newRun(java.util.Collections.singletonList(new LandingRunProgress.TableRef("7", "ECOM_Clients", "ECOM")));
		progress.waiting("7", "customerId - unique in the sample but not across the whole source");
		progress.waitingStage("7", LandingRunProgress.FLOW, "Waits for the key.");
		progress.waitingStage("7", LandingRunProgress.LOAD, "Waits for the key.");
		if (LandingKeyRules.reconcileMayConfirmKey(true, progress.table("7").cell(LandingRunProgress.KEY).state))
		{
			progress.finish("7", LandingRunProgress.KEY, true, "Key confirmed");
		}
		ValidatedKeyTest.check("the grid still has its waiting key (chooser reachable, run kept open)", progress.hasWaiting() && progress.waitingTableIds().contains("7"));
	}

	private static void aBuiltNeverLoadedTableTakesAFirstChoiceWhateverItsProposal()
	{
		ValidatedKeyTest.check("GENERATED and never loaded: first choice allowed (proposal or not)", LandingKeyRules.firstChoiceAllowed("GENERATED", null));
		ValidatedKeyTest.check("GENERATED and already loaded: a key change needs the key-change job", !LandingKeyRules.firstChoiceAllowed("GENERATED", "42"));
		ValidatedKeyTest.check("LOADED: no", !LandingKeyRules.firstChoiceAllowed("LOADED", null));
		ValidatedKeyTest.check("PROPOSED: the ordinary write handles it", !LandingKeyRules.firstChoiceAllowed("PROPOSED", null));
	}

	private static void theWalkthroughOffersTheChooserForWaitingTablesOnly()
	{
		final java.util.List<String> waiting = java.util.Arrays.asList("7", "9");
		ValidatedKeyTest.check("a waiting table", LandingKeyRules.waitsInRun(waiting, "7"));
		ValidatedKeyTest.check("another table", !LandingKeyRules.waitsInRun(waiting, "8"));
		ValidatedKeyTest.check("no run", !LandingKeyRules.waitsInRun(null, "7") && !LandingKeyRules.waitsInRun(new java.util.ArrayList<String>(), "7"));
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
