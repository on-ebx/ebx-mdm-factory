package com.on.ebx.factory.capability;

/**
 * JUnit-free: run the main, a failure throws. Lot T2 (2026-10-04): what a failed apply undoes - the defect found during lot P1 (a failed RE-apply used to
 * take the declarations off the draft and flip the row to ACCEPTED).
 */
public final class DerivationApplyFailureTest
{
	public static void main(final String[] pArgs)
	{
		DerivationApplyFailureTest.aFirstApplyIsRolledBackByName();
		DerivationApplyFailureTest.aFailedReapplyLeavesTheDeclarationsAndRestoresTheBodies();
		DerivationApplyFailureTest.theMessageKeepsTheReapplyMarkerOnlyForAnAppliedRow();
		DerivationApplyFailureTest.aRefusalBeforeTheStepIsStoredForAnAcceptedOrAppliedRowOnly();
		System.out.println("DerivationApplyFailureTest OK");
	}

	private static void aFirstApplyIsRolledBackByName()
	{
		final DerivationApplyFailure.Plan beforePublish = DerivationApplyFailure.decide(false, false, false);
		DerivationApplyFailureTest.check("removes the declarations, no republish, back to ACCEPTED",
			beforePublish.removeDeclarations && !beforePublish.republish && !beforePublish.restoreBodies && !beforePublish.statusStaysApplied);
		final DerivationApplyFailure.Plan afterPublish = DerivationApplyFailure.decide(false, true, false);
		DerivationApplyFailureTest.check("republishes only when this round published", afterPublish.removeDeclarations && afterPublish.republish);
		DerivationApplyFailureTest.check("known previous bodies change nothing for a first apply", DerivationApplyFailure.decide(false, true, true).removeDeclarations
			&& !DerivationApplyFailure.decide(false, true, true).restoreBodies);
	}

	private static void aFailedReapplyLeavesTheDeclarationsAndRestoresTheBodies()
	{
		final DerivationApplyFailure.Plan beforePublish = DerivationApplyFailure.decide(true, false, true);
		DerivationApplyFailureTest.check("never removes the declarations of a published derivation", !beforePublish.removeDeclarations);
		DerivationApplyFailureTest.check("restores the previous bodies, nothing to republish, stays APPLIED", beforePublish.restoreBodies && !beforePublish.republish && beforePublish.statusStaysApplied);
		final DerivationApplyFailure.Plan afterPublish = DerivationApplyFailure.decide(true, true, true);
		DerivationApplyFailureTest.check("republishes the restored bodies when this round had published", !afterPublish.removeDeclarations && afterPublish.restoreBodies && afterPublish.republish
			&& afterPublish.statusStaysApplied);
		final DerivationApplyFailure.Plan unreadable = DerivationApplyFailure.decide(true, true, false);
		DerivationApplyFailureTest.check("unreadable previous bodies: nothing restored, nothing republished (it would change nothing), declarations kept, still APPLIED",
			!unreadable.removeDeclarations && !unreadable.restoreBodies && !unreadable.republish && unreadable.statusStaysApplied);
	}

	private static void theMessageKeepsTheReapplyMarkerOnlyForAnAppliedRow()
	{
		final String applied = DerivationApplyFailure.message(true, "boom");
		DerivationApplyFailureTest.check("failure text and marker: " + applied, applied.startsWith("boom ") && DerivationParameters.isReapplyMarker(applied));
		final String first = DerivationApplyFailure.message(false, "boom");
		DerivationApplyFailureTest.check("a first apply says only what failed", "boom".equals(first) && !DerivationParameters.isReapplyMarker(first));
		final StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 2500; i++)
		{
			longText.append('y');
		}
		DerivationApplyFailureTest.check("always within the field", DerivationApplyFailure.message(false, longText.toString()).length() == DerivationParameters.APPLY_MESSAGE_MAX
			&& DerivationApplyFailure.message(true, longText.toString()).length() <= DerivationParameters.APPLY_MESSAGE_MAX);
		DerivationApplyFailureTest.check("null failure", "".equals(DerivationApplyFailure.message(false, null)));
	}

	private static void aRefusalBeforeTheStepIsStoredForAnAcceptedOrAppliedRowOnly()
	{
		final String accepted = DerivationApplyFailure.refusalMessage("ACCEPTED", "cannot apply '7': parameter(s) [country] are still 'to be provided'");
		DerivationApplyFailureTest.check("an accepted row gets the refusal as it is", "cannot apply '7': parameter(s) [country] are still 'to be provided'".equals(accepted));
		final String applied = DerivationApplyFailure.refusalMessage("APPLIED", "the source attribute of suggestion '7' no longer exists");
		DerivationApplyFailureTest.check("an applied row keeps its 'apply again' next to the refusal", applied != null && applied.startsWith("the source attribute") && DerivationParameters.isReapplyMarker(applied));
		DerivationApplyFailureTest.check("a proposed row is not touched", DerivationApplyFailure.refusalMessage("PROPOSED", "must be ACCEPTED first") == null);
		DerivationApplyFailureTest.check("a rejected row is not touched", DerivationApplyFailure.refusalMessage("REJECTED", "x") == null && DerivationApplyFailure.refusalMessage(null, "x") == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
