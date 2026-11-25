package com.on.ebx.factory.ebx;

/**
 * JUnit-free, like the sibling tests: run the main, a failure throws. Only {@link MameSuspectDecision.Result}
 * itself is pure (a plain DTO, no EBX type) - {@link AddonMameSuspectDecision} is EBX-bound end to end
 * (Session, AdaptationTable, the add-on's own Group/MergeOperations) and has NO unit test, the same
 * discipline {@link MameGroups}/{@link MameRecordState} already carry (never run live this session -
 * correctness there rests on the {@code javap}-verified signatures quoted in its own Javadoc, not on a
 * running test).
 */
public final class MameSuspectDecisionTest
{
	public static void main(final String[] pArgs)
	{
		MameSuspectDecisionTest.addonAbsent_carriesTheRightOutcomeAndNoDetail();
		MameSuspectDecisionTest.groupNotFound_carriesNoDetail();
		MameSuspectDecisionTest.acceptFailed_carriesDetailNeverAGoldenKey();
		MameSuspectDecisionTest.acceptedAndMerged_carriesTheGoldenKey();
		MameSuspectDecisionTest.acceptedMergeFailed_stillCountsAsAcceptedWithADetail();
		MameSuspectDecisionTest.rejected_carriesNoDetailNoGoldenKey();
		MameSuspectDecisionTest.mergeFailure_messageNamingManualMerge_isReportedSpecifically();
		MameSuspectDecisionTest.mergeFailure_ordinaryMessage_staysGeneric();
		MameSuspectDecisionTest.mergeFailure_caseInsensitiveDetection();
		MameSuspectDecisionTest.mergeFailure_nullMessage_staysGenericNeverThrows();
		MameSuspectDecisionTest.postAcceptAction_suspectAlreadyMerged_skipsMergeAndCarriesGoldenKey();
		MameSuspectDecisionTest.postAcceptAction_mergedButNoGoldenKey_stillCallsMerge();
		MameSuspectDecisionTest.postAcceptAction_notMergedAndGroupHasTwoOrMoreRemaining_callsMerge();
		MameSuspectDecisionTest.postAcceptAction_notMergedAndFewerThanTwoRemaining_skipsMergeNoGoldenKey();
		MameSuspectDecisionTest.postAcceptAction_notMergedAndZeroRemaining_skipsMerge();
		MameSuspectDecisionTest.postAcceptAction_remainingUnknown_fallsBackToCallMerge();
		MameSuspectDecisionTest.postAcceptAction_nullSuspectState_fallsBackOnRemainingCount();
		System.out.println("MameSuspectDecisionTest OK");
	}

	// ------------------------------------------------------------------------ PostAcceptAction (2026-09-22 follow-up)

	/**
	 * The coordinator's own live measurement (interface Javadoc, "The class Javadoc above ... is FALSE on this
	 * build"): the suspect's own state can already be MERGED, with a resolvable golden, right after {@code
	 * acceptSuspectsAndMergeGroups} - {@code mergeGroup} must never even be attempted then.
	 */
	private static void postAcceptAction_suspectAlreadyMerged_skipsMergeAndCarriesGoldenKey()
	{
		final PostAcceptAction action = PostAcceptAction.of("MERGED", "322", 0);
		MameSuspectDecisionTest.check("action is ALREADY_MERGED", PostAcceptAction.ALREADY_MERGED.equals(action.action));
		MameSuspectDecisionTest.check("golden key carried through", "322".equals(action.goldenKey));
	}

	/** A MERGED state with no resolvable golden key (e.g. {@code getTargetRecord} itself failed) is not good enough - never guess a golden key, fall through to the remaining-count rule instead. */
	private static void postAcceptAction_mergedButNoGoldenKey_stillCallsMerge()
	{
		final PostAcceptAction action = PostAcceptAction.of("MERGED", null, 5);
		MameSuspectDecisionTest.check("no usable golden key -> falls through to the remaining-count rule", PostAcceptAction.CALL_MERGE.equals(action.action));
	}

	/** The suspect itself was not auto-merged, but the group still has two or more records eligible for a manual merge - mergeGroup must still be attempted, exactly as before this fix. */
	private static void postAcceptAction_notMergedAndGroupHasTwoOrMoreRemaining_callsMerge()
	{
		final PostAcceptAction action = PostAcceptAction.of("UNSET", null, 2);
		MameSuspectDecisionTest.check("action is CALL_MERGE", PostAcceptAction.CALL_MERGE.equals(action.action));
		MameSuspectDecisionTest.check("no golden key on this outcome", action.goldenKey == null);
	}

	/**
	 * {@code mergeGroup}'s own documented precondition ("Select two or more records that are not 'Merged' or
	 * 'Deleted'", quoted verbatim in the refusal the coordinator saw live) - exactly one record remaining means
	 * calling {@code mergeGroup} could only ever refuse, so it is skipped instead of exercised for nothing.
	 */
	private static void postAcceptAction_notMergedAndFewerThanTwoRemaining_skipsMergeNoGoldenKey()
	{
		final PostAcceptAction action = PostAcceptAction.of("UNSET", null, 1);
		MameSuspectDecisionTest.check("action is NOTHING_TO_MERGE", PostAcceptAction.NOTHING_TO_MERGE.equals(action.action));
		MameSuspectDecisionTest.check("no golden key - the group scan this is built from never sees the golden", action.goldenKey == null);
	}

	/** Zero remaining (the whole group already dissolved into MERGED/DELETED members) is the same NOTHING_TO_MERGE outcome as one. */
	private static void postAcceptAction_notMergedAndZeroRemaining_skipsMerge()
	{
		final PostAcceptAction action = PostAcceptAction.of("GOLDEN", null, 0);
		MameSuspectDecisionTest.check("action is NOTHING_TO_MERGE", PostAcceptAction.NOTHING_TO_MERGE.equals(action.action));
	}

	/** A {@code -1} (the group re-fetch/scan itself failed) must never be read as "nothing to merge" - it falls back to the pre-fix behaviour of just attempting {@code mergeGroup}. */
	private static void postAcceptAction_remainingUnknown_fallsBackToCallMerge()
	{
		final PostAcceptAction action = PostAcceptAction.of("UNSET", null, -1);
		MameSuspectDecisionTest.check("unknown remaining count falls back to CALL_MERGE, never a guess", PostAcceptAction.CALL_MERGE.equals(action.action));
	}

	/** A null suspect state (add-on answered nothing usable for the read) must not crash the decision and must fall through to the remaining-count rule, same as any other non-MERGED state. */
	private static void postAcceptAction_nullSuspectState_fallsBackOnRemainingCount()
	{
		final PostAcceptAction skips = PostAcceptAction.of(null, null, 1);
		MameSuspectDecisionTest.check("null state, 1 remaining -> NOTHING_TO_MERGE", PostAcceptAction.NOTHING_TO_MERGE.equals(skips.action));
		final PostAcceptAction calls = PostAcceptAction.of(null, null, 3);
		MameSuspectDecisionTest.check("null state, 3 remaining -> CALL_MERGE", PostAcceptAction.CALL_MERGE.equals(calls.action));
	}

	// ------------------------------------------------------------------------ mergeFailure (2026-09-22 follow-up)

	/**
	 * Coordinator follow-up, 2026-09-22: {@code mergeGroup}'s own documented precondition names "Used for
	 * manual merge" - a message quoting that setting must resolve to the SPECIFIC outcome, not the generic
	 * {@code ACCEPTED_MERGE_FAILED}, so the page can show "the merge policy does not allow manual merge -
	 * re-run matching/configure" instead of an opaque exception message.
	 */
	private static void mergeFailure_messageNamingManualMerge_isReportedSpecifically()
	{
		final MameSuspectDecision.Result result = AddonMameSuspectDecision.mergeFailure(new IllegalStateException("boom"),
			"No merge policy configured for manual merge on this table.");
		MameSuspectDecisionTest.check("outcome is the specific ACCEPTED_MERGE_POLICY_NOT_MANUAL",
			MameSuspectDecision.ACCEPTED_MERGE_POLICY_NOT_MANUAL.equals(result.outcome));
		MameSuspectDecisionTest.check("the real message is still carried in the detail, for diagnostics",
			result.detail.contains("No merge policy configured for manual merge"));
		MameSuspectDecisionTest.check("no golden key", result.goldenKey == null);
	}

	/** An ordinary failure message that never mentions manual merge stays the generic outcome - never a false positive. */
	private static void mergeFailure_ordinaryMessage_staysGeneric()
	{
		final MameSuspectDecision.Result result = AddonMameSuspectDecision.mergeFailure(new IllegalStateException("boom"), "The business table is not configured for matching.");
		MameSuspectDecisionTest.check("outcome stays the generic ACCEPTED_MERGE_FAILED",
			MameSuspectDecision.ACCEPTED_MERGE_FAILED.equals(result.outcome));
	}

	/** Detection is case-insensitive - the add-on's own wording is never guaranteed to match one exact casing. */
	private static void mergeFailure_caseInsensitiveDetection()
	{
		final MameSuspectDecision.Result upper = AddonMameSuspectDecision.mergeFailure(new IllegalStateException("boom"), "MANUAL MERGE is not enabled.");
		MameSuspectDecisionTest.check("upper case still detected", MameSuspectDecision.ACCEPTED_MERGE_POLICY_NOT_MANUAL.equals(upper.outcome));
	}

	/** A null message (should not happen for a real exception, defensive only) never throws and never guesses the specific outcome. */
	private static void mergeFailure_nullMessage_staysGenericNeverThrows()
	{
		final MameSuspectDecision.Result result = AddonMameSuspectDecision.mergeFailure(new IllegalStateException(), null);
		MameSuspectDecisionTest.check("a null message never crashes the detection and stays generic",
			MameSuspectDecision.ACCEPTED_MERGE_FAILED.equals(result.outcome));
	}

	private static void addonAbsent_carriesTheRightOutcomeAndNoDetail()
	{
		final MameSuspectDecision.Result result = MameSuspectDecision.Result.addonAbsent();
		MameSuspectDecisionTest.check("addonPresent false", !result.addonPresent);
		MameSuspectDecisionTest.check("outcome is the ADDON_NOT_AVAILABLE constant", MameSuspectDecision.ADDON_NOT_AVAILABLE.equals(result.outcome));
		MameSuspectDecisionTest.check("no detail", result.detail == null);
		MameSuspectDecisionTest.check("no golden key", result.goldenKey == null);
	}

	private static void groupNotFound_carriesNoDetail()
	{
		final MameSuspectDecision.Result result = MameSuspectDecision.Result.groupNotFound();
		MameSuspectDecisionTest.check("addonPresent true (this is a real, if unsuccessful, add-on answer)", result.addonPresent);
		MameSuspectDecisionTest.check("outcome is GROUP_NOT_FOUND", MameSuspectDecision.GROUP_NOT_FOUND.equals(result.outcome));
		MameSuspectDecisionTest.check("no detail - a plain not-found, not an unexpected failure", result.detail == null);
	}

	private static void acceptFailed_carriesDetailNeverAGoldenKey()
	{
		final MameSuspectDecision.Result result = MameSuspectDecision.Result.acceptFailed("java.lang.IllegalArgumentException: not a suspect of this group");
		MameSuspectDecisionTest.check("outcome is ACCEPT_FAILED", MameSuspectDecision.ACCEPT_FAILED.equals(result.outcome));
		MameSuspectDecisionTest.check("detail carried through verbatim", result.detail.contains("IllegalArgumentException"));
		MameSuspectDecisionTest.check("never a golden key on a failure", result.goldenKey == null);
	}

	private static void acceptedAndMerged_carriesTheGoldenKey()
	{
		final MameSuspectDecision.Result result = MameSuspectDecision.Result.acceptedAndMerged("183");
		MameSuspectDecisionTest.check("outcome is ACCEPTED_AND_MERGED", MameSuspectDecision.ACCEPTED_AND_MERGED.equals(result.outcome));
		MameSuspectDecisionTest.check("golden key carried through", "183".equals(result.goldenKey));
		MameSuspectDecisionTest.check("no detail on a clean success", result.detail == null);
	}

	/** The suspect DID join the group (the accept itself succeeded) even though the subsequent merge attempt failed - see MameSuspectDecision's own Javadoc for why these are two independently-guarded steps. */
	private static void acceptedMergeFailed_stillCountsAsAcceptedWithADetail()
	{
		final MameSuspectDecision.Result result = MameSuspectDecision.Result
			.acceptedMergeFailed("com.orchestranetworks.addon.mame.common.InvalidStateException: no merging policy configured");
		MameSuspectDecisionTest.check("outcome is ACCEPTED_MERGE_FAILED", MameSuspectDecision.ACCEPTED_MERGE_FAILED.equals(result.outcome));
		MameSuspectDecisionTest.check("detail names the real failure", result.detail.contains("InvalidStateException"));
		MameSuspectDecisionTest.check("no golden key - the merge never actually produced one", result.goldenKey == null);
	}

	private static void rejected_carriesNoDetailNoGoldenKey()
	{
		final MameSuspectDecision.Result result = MameSuspectDecision.Result.rejected();
		MameSuspectDecisionTest.check("outcome is REJECTED", MameSuspectDecision.REJECTED.equals(result.outcome));
		MameSuspectDecisionTest.check("no detail", result.detail == null);
		MameSuspectDecisionTest.check("no golden key - rejecting never merges anything", result.goldenKey == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
