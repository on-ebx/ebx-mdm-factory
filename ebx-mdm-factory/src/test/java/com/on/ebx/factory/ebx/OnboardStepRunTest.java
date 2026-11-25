package com.on.ebx.factory.ebx;

import com.on.ebx.factory.wizard.SourceOnboarding;

/**
 * JUnit-free, like the rest of this package's tests. Job-engine lot C3 (2026-09-24): exercises {@link
 * OnboardStepRun#firstUnfinished}, the pure helper {@link OnboardStepRun#CATALOG_HANDLER}/{@link
 * OnboardStepRun#SCAN_HANDLER} use to summarise a not-yet-{@code ready} diagnosis into the job's own result
 * message - package-visible for exactly this reason, same convention as {@code SourcesRun#uniqueCode}.
 */
public final class OnboardStepRunTest
{
	public static void main(final String[] pArgs)
	{
		OnboardStepRunTest.namesTheFirstStepThatIsNotDone();
		OnboardStepRunTest.everyStepDoneFallsBackToPlainMessage();

		System.out.println("OnboardStepRunTest OK");
	}

	private static void namesTheFirstStepThatIsNotDone()
	{
		// unknown connection: connectorFound stays false - the "connection" step is BLOCKED, every step after
		// it (never reached) is WAITING, exactly the shape SourceOnboardingTest's own
		// "unknownConnectionBlockedOpenConnectionEverythingElseWaiting" fixture documents.
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "NoSuchConnector";
		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		OnboardStepRunTest.check("diagnosis is not ready", !diagnosis.ready);
		final String summary = OnboardStepRun.firstUnfinished(diagnosis);
		OnboardStepRunTest.check("names the first unfinished step (connection)", summary.contains("connection"));
		OnboardStepRunTest.check("says not finished automatically", summary.contains("not finished automatically"));
	}

	private static void everyStepDoneFallsBackToPlainMessage()
	{
		// defensive-only path: diagnose() never returns ready=false with every step DONE in practice (the last
		// step, "ready", is what turns the whole diagnosis ready), but firstUnfinished must still answer
		// something sane rather than throw if it were ever called on one.
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "SourceBidon";
		facts.connectorFound = true;
		facts.connectorActive = true;
		facts.connectorType = "sample-file";
		facts.catalogOperation = true;
		facts.catalogState = "VERIFIED";
		facts.planAction = "LINKED";
		facts.planSourceId = "SOURCEBIDON";
		facts.scanMappingId = Integer.valueOf(240);
		facts.sourceExists = true;
		facts.sourceActive = true;
		facts.lastScanStatus = "OK";
		facts.assetsScanned = true;
		facts.assetsWithColumns = true;
		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		OnboardStepRunTest.check("this fixture is actually ready (sanity check on the fixture itself)", diagnosis.ready);
		OnboardStepRunTest.check("firstUnfinished never throws even when every step is DONE",
			"not finished automatically.".equals(OnboardStepRun.firstUnfinished(diagnosis)));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
