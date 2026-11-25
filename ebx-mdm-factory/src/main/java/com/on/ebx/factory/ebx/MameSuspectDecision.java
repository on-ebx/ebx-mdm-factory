package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.Collections;

import com.on.ebx.factory.core.ForeignWording;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.addon.mame.common.InvalidParameterException;
import com.orchestranetworks.addon.mame.common.InvalidStateException;
import com.orchestranetworks.addon.mame.common.NotFoundException;
import com.orchestranetworks.addon.mame.common.util.iterator.CloseableIterator;
import com.orchestranetworks.addon.mame.model.State;
import com.orchestranetworks.addon.mame.service.OperationsFactory;
import com.orchestranetworks.addon.mame.service.group.Group;
import com.orchestranetworks.addon.mame.service.group.GroupOperations;
import com.orchestranetworks.addon.mame.service.merge.MergeOperations;
import com.orchestranetworks.addon.mame.service.merge.MergeProcess;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Confirming or rejecting ONE pending suspect of ONE group, through the add-on's public Java API - the call
 * sequence {@code docs/RECORD-LINEAGE.md} "Confirming a suspect" already documented (quoted from the add-on's
 * local Javadoc, {@code webapps/ebx-addon-mame/www/common/html/onHelp/advanced/Java_API/com/orchestranetworks/
 * addon/mame/service/group/Group.html}), now actually called. <b>Never run live this session</b> (task
 * constraint) - every signature below is {@code javap}-verified against {@code ebx-addons.jar}
 * (2026-09-22), the same jar {@link MameGroups}/{@link MameRecordState} already compile against; no build
 * file change was needed.
 * <p>
 * <b>Which call moves the suspect, and why {@link #accept} uses {@code acceptSuspectsAndMergeGroups}, not
 * plain {@code acceptSuspects}.</b> Both are read from the SAME Javadoc page. {@code acceptSuspects}: <i>"For
 * each suspect record passed in: If the record's state is GOLDEN or UNSET, its entire source group (golden +
 * all merged records) is moved into this group. If the record's state is MERGED, only that single record is
 * moved; the rest of its source group (golden + sibling merged records) is left behind."</i> {@code
 * acceptSuspectsAndMergeGroups} (since 6.2.4): <i>"...merges each suspect's entire source group into this
 * group. For each suspect record passed in, every record belonging to that suspect's source group (the
 * golden record and all records merged into it) is moved into this group. The source group is dissolved.
 * This method always merges full groups, regardless of whether the suspect record itself is GOLDEN, UNSET,
 * or MERGED... addresses the limitation of acceptSuspects(Set) where accepting a MERGED suspect moves only
 * that single record, leaving the rest of its source group orphaned (defect MAME-10041 / EBXRFA-2659)."</i>
 * A suspect examined through this screen could, in principle, already be a non-golden member of its own
 * source group (this lot never checks the suspect's own {@code MameRecordState} before deciding) - using the
 * narrower {@code acceptSuspects} risks silently orphaning sibling records the steward never saw. {@code
 * acceptSuspectsAndMergeGroups} is the one call that behaves the same way regardless, so it is the only one
 * used here.
 * <p>
 * <b>The class Javadoc above ("neither {@code accept*} method performs the actual MERGE by itself") is FALSE
 * on this build, per a real, live measurement - coordinator follow-up, 2026-09-22.</b> On the lineage screen
 * of record 290 (dataspace {@code Companies_Master}, dataset {@code Master}, table {@code /root/Company},
 * add-on 6.3.0-dev), confirming suspect 248 via exactly the sequence below (accept, then {@code mergeGroup})
 * had {@code mergeGroup} throw {@code InvalidParameterException}: <i>"Select two or more records that are not
 * 'Merged' or 'Deleted'"</i> - a real, documented precondition, not a bug in {@code mergeGroup}. But reading
 * {@code Master_Company_MDS/root/RecordMetadata} right after showed the merge had ALREADY happened: a NEW
 * golden record 322 existed in {@code /root/Company} ({@code autoCreated true}, every field chosen by the
 * project's own "longest" merge function), and 248/290 both read back {@code state MERGED, goldenId "322",
 * mergingProcessId 1}. So {@code acceptSuspectsAndMergeGroups} ITSELF can already perform the actual merge -
 * the same automatic-merge-of-a-fully-MATCHED-group behaviour CLAUDE.md &sect;8 already documents for a
 * MATCHING run ("MATCHED groups are merged automatically even with merge mode DISABLED"), now confirmed for
 * the manual accept-suspect path too. The subsequent {@code mergeGroup} call then correctly refused, because
 * the group's only non-{@code MERGED}, non-{@code DELETED} record left was the golden itself - not because
 * anything was misconfigured. Telling the steward "the automatic merge could not run" in that situation is
 * simply WRONG: a golden record plainly already exists.
 * <p>
 * <b>So {@link #accept} now RE-READS the suspect's own state right after {@code acceptSuspectsAndMergeGroups},
 * before ever calling {@code mergeGroup}</b> (through {@link MameRecordState}, the same public-API read this
 * module already uses read-only elsewhere - {@code MetadataHelper.getState}/{@code getTargetRecord} on the
 * SUSPECT itself, not the group's own {@code getMatchedRecords()}, which by {@code ebx.MameGroups}' own
 * established reading of that API never includes the group's golden, so it could never answer this question).
 * If the suspect is already {@code MERGED} with a resolvable golden, {@link #accept} answers {@code
 * ACCEPTED_AND_MERGED} directly and skips {@code mergeGroup} entirely (see {@link PostAcceptAction}, a pure,
 * directly-tested decision). Otherwise it re-fetches the group and counts members still eligible for a manual
 * merge (neither {@code MERGED} nor {@code DELETED} - {@code mergeGroup}'s own precondition, quoted above): if
 * fewer than two remain, there is nothing left to merge and {@code mergeGroup} is skipped too (plain {@code
 * ACCEPTED} - no golden key is available from this path alone, since the group scan cannot see the golden by
 * design). Only when at least two members remain eligible does {@link #accept} still call {@code mergeGroup},
 * exactly as before. <b>"The pivot"</b> the task brief asked to re-read has no {@code Adaptation} reference in
 * this method's own signature (only {@code pGroupId}, a {@code BigDecimal}) - {@code
 * ebx.RecordLineageReader#decideSuspect} never passes the anchor record down to this class, and extending the
 * signature only for this was judged unnecessary: the suspect's own post-accept state already answers the
 * real question ("did a merge already happen", proven true in the live measurement above) without it, and the
 * group-member count covers the "is there still something to manually merge" question the pivot's own
 * identity does not add anything to.
 * <p>
 * <b>Belt and braces: if {@code mergeGroup} throws ANYWAY</b>, {@link #accept} re-reads the suspect's own
 * state ONE more time before falling back to {@link #mergeFailure} - a golden may have been produced by the
 * add-on's own internal handling of the call even though it surfaced an exception (never observed this
 * session, purely defensive) - so a steward is never told "merge failed" once a golden demonstrably exists
 * for the suspect they just confirmed.
 * <p>
 * Every write here runs OUTSIDE this class' own transaction management - both add-on APIs are documented to
 * manage their own (`MergeOperations.html`'s own code sample calls {@code mergeGroup} directly from a
 * workflow script task, no {@code Procedure} wrapper) - {@code ebx.RecordLineageReader#decideSuspect} calls
 * this class directly with the caller's session, exactly like {@code ebx.MameGroups}/{@code
 * ebx.MameRecordState} already do for their own (read-only) calls.
 */
interface MameSuspectDecision
{
	String ADDON_NOT_AVAILABLE = "ADDON_NOT_AVAILABLE";
	String GROUP_NOT_FOUND = "GROUP_NOT_FOUND";
	String ACCEPT_FAILED = "ACCEPT_FAILED";
	String REJECT_FAILED = "REJECT_FAILED";
	String ACCEPTED = "ACCEPTED";
	String ACCEPTED_AND_MERGED = "ACCEPTED_AND_MERGED";
	String ACCEPTED_MERGE_FAILED = "ACCEPTED_MERGE_FAILED";
	/** Coordinator follow-up, 2026-09-22: the ONE {@link #ACCEPTED_MERGE_FAILED} cause common enough to name specifically - see {@link AddonMameSuspectDecision#mergeFailure}'s own Javadoc. */
	String ACCEPTED_MERGE_POLICY_NOT_MANUAL = "ACCEPTED_MERGE_POLICY_NOT_MANUAL";
	String REJECTED = "REJECTED";

	final class Result
	{
		/** False only when the add-on itself is absent from the classpath - see {@link MameRecordState}/{@link MameGroups} for the same seam; this class never throws {@link LinkageError} on its own since it references only {@code com.orchestranetworks.addon.mame.service.{group,merge}.*} types already on this module's compile classpath (verified by {@code javap} against {@code ebx-addons.jar}, class Javadoc) - kept for symmetry/future-proofing and because {@link Holder#resolve} still guards construction the same way. */
		final boolean addonPresent;
		/** One of the outcome constants above. */
		final String outcome;
		/** Non-null only on a failure outcome - the exception's class and message, never a record value. */
		final String detail;
		/** Non-null only for {@link #ACCEPTED_AND_MERGED} - the golden record's own formatted primary key after the merge. */
		final String goldenKey;

		private Result(final boolean pAddonPresent, final String pOutcome, final String pDetail, final String pGoldenKey)
		{
			this.addonPresent = pAddonPresent;
			this.outcome = pOutcome;
			this.detail = pDetail;
			this.goldenKey = pGoldenKey;
		}

		static Result addonAbsent()
		{
			return new Result(false, MameSuspectDecision.ADDON_NOT_AVAILABLE, null, null);
		}

		static Result groupNotFound()
		{
			return new Result(true, MameSuspectDecision.GROUP_NOT_FOUND, null, null);
		}

		static Result acceptFailed(final String pDetail)
		{
			return new Result(true, MameSuspectDecision.ACCEPT_FAILED, pDetail, null);
		}

		static Result rejectFailed(final String pDetail)
		{
			return new Result(true, MameSuspectDecision.REJECT_FAILED, pDetail, null);
		}

		static Result accepted()
		{
			return new Result(true, MameSuspectDecision.ACCEPTED, null, null);
		}

		static Result acceptedAndMerged(final String pGoldenKey)
		{
			return new Result(true, MameSuspectDecision.ACCEPTED_AND_MERGED, null, pGoldenKey);
		}

		static Result acceptedMergeFailed(final String pDetail)
		{
			return new Result(true, MameSuspectDecision.ACCEPTED_MERGE_FAILED, pDetail, null);
		}

		/** Same shape as {@link #acceptedMergeFailed(String)}, a more specific outcome code so the page can show the exact plain-word reason instead of a generic failure - see {@link AddonMameSuspectDecision#mergeFailure}'s own Javadoc for when this is chosen. */
		static Result acceptedMergePolicyNotManual(final String pDetail)
		{
			return new Result(true, MameSuspectDecision.ACCEPTED_MERGE_POLICY_NOT_MANUAL, pDetail, null);
		}

		static Result rejected()
		{
			return new Result(true, MameSuspectDecision.REJECTED, null, null);
		}
	}

	/** Confirms {@code pSuspect} is a duplicate of {@code pGroupId}'s own group, then attempts a real merge (see interface Javadoc). Never throws. */
	Result accept(Session pSession, AdaptationTable pTable, BigDecimal pGroupId, Adaptation pSuspect);

	/** Records {@code pSuspect} as NOT a duplicate ({@code Group.rejectSuspects} - a pairwise false-positive decision, never a global exclusion, per that method's own Javadoc). Never throws. */
	Result reject(Session pSession, AdaptationTable pTable, BigDecimal pGroupId, Adaptation pSuspect);

	static MameSuspectDecision get()
	{
		return Holder.INSTANCE;
	}

	/** Resolved once (initialization-on-demand holder), like {@link MameGroups.Holder}/{@link MameRecordState.Holder}. */
	final class Holder
	{
		static final MameSuspectDecision INSTANCE = Holder.resolve();

		private Holder()
		{
		}

		private static MameSuspectDecision resolve()
		{
			try
			{
				return new AddonMameSuspectDecision();
			}
			catch (final LinkageError ex)
			{
				return new MameSuspectDecision()
				{
					@Override
					public Result accept(final Session pSession, final AdaptationTable pTable, final BigDecimal pGroupId, final Adaptation pSuspect)
					{
						return Result.addonAbsent();
					}

					@Override
					public Result reject(final Session pSession, final AdaptationTable pTable, final BigDecimal pGroupId, final Adaptation pSuspect)
					{
						return Result.addonAbsent();
					}
				};
			}
		}
	}
}

/**
 * Pure decision, no EBX type anywhere - "what should {@link AddonMameSuspectDecision#accept} do about the
 * automatic-merge step, given what was re-read right after {@code acceptSuspectsAndMergeGroups}" (see {@link
 * MameSuspectDecision}'s own Javadoc, "The class Javadoc above ... is FALSE on this build", for the live
 * measurement that made this check necessary). Directly unit-tested ({@code MameSuspectDecisionTest}), the
 * same "no EBX type, so test it directly" discipline this file already applies to {@link
 * AddonMameSuspectDecision#mergeFailure}.
 */
final class PostAcceptAction
{
	/** The suspect's own post-accept state already carries a resolvable golden - skip {@code mergeGroup}, answer {@code ACCEPTED_AND_MERGED} with {@link #goldenKey}. */
	static final String ALREADY_MERGED = "ALREADY_MERGED";
	/** Fewer than two group members are still eligible for a manual merge - skip {@code mergeGroup} (it would only refuse), answer plain {@code ACCEPTED} ({@link #goldenKey} is always null here: the group scan this action is built from cannot see the golden by construction). */
	static final String NOTHING_TO_MERGE = "NOTHING_TO_MERGE";
	/** Neither of the above - call {@code mergeGroup} exactly as before this fix. */
	static final String CALL_MERGE = "CALL_MERGE";

	final String action;
	/** Non-null only for {@link #ALREADY_MERGED}. */
	final String goldenKey;

	private PostAcceptAction(final String pAction, final String pGoldenKey)
	{
		this.action = pAction;
		this.goldenKey = pGoldenKey;
	}

	/**
	 * @param pSuspectState the suspect's own {@link MameRecordState.Result#state} read right after {@code
	 *            acceptSuspectsAndMergeGroups} ({@code UNSET}/{@code GOLDEN}/{@code MERGED}/{@code
	 *            PENDING_APPROVAL}/{@code DELETED}, matching the add-on's own {@code State.name()} - or null
	 *            when the add-on answered nothing usable). Compared against the literal {@code "MERGED"} - this
	 *            class stays free of any add-on import on purpose; the caller passes {@code State.MERGED.name()}
	 *            or an equal string.
	 * @param pSuspectGoldenKey {@link MameRecordState.Result#goldenKey} from that SAME read - only meaningful
	 *            (and only ever non-null) when {@code pSuspectState} is {@code MERGED}.
	 * @param pRemainingEligibleForManualMerge how many of the group's own confirmed members (per a fresh
	 *            {@code Group.getMatchedRecords()} scan) are neither {@code MERGED} nor {@code DELETED} - or
	 *            {@code -1} when this could not be determined (the group re-fetch or scan itself failed). A
	 *            {@code -1} always falls through to {@link #CALL_MERGE}, the behaviour this method had before
	 *            this fix, rather than guessing "nothing left to merge".
	 */
	static PostAcceptAction of(final String pSuspectState, final String pSuspectGoldenKey, final int pRemainingEligibleForManualMerge)
	{
		if ("MERGED".equals(pSuspectState) && pSuspectGoldenKey != null)
		{
			return new PostAcceptAction(PostAcceptAction.ALREADY_MERGED, pSuspectGoldenKey);
		}
		if (pRemainingEligibleForManualMerge >= 0 && pRemainingEligibleForManualMerge < 2)
		{
			return new PostAcceptAction(PostAcceptAction.NOTHING_TO_MERGE, null);
		}
		return new PostAcceptAction(PostAcceptAction.CALL_MERGE, null);
	}
}

/** The only class of this module referencing {@code com.orchestranetworks.addon.mame.service.{group,merge}.*} types for a WRITE - see {@link MameSuspectDecision}'s own Javadoc for why. */
final class AddonMameSuspectDecision implements MameSuspectDecision
{
	AddonMameSuspectDecision()
	{
		// forces classloading right here (constructor time, from Holder.resolve) - same trick as AddonMameGroups/AddonMameRecordState
		OperationsFactory.class.getName();
	}

	@Override
	public Result accept(final Session pSession, final AdaptationTable pTable, final BigDecimal pGroupId, final Adaptation pSuspect)
	{
		if (pTable == null || pGroupId == null || pSuspect == null)
		{
			return Result.groupNotFound();
		}
		final Group group;
		try
		{
			group = OperationsFactory.getGroupOperations(pSession).getGroup(pTable, pGroupId);
		}
		catch (final NotFoundException ex)
		{
			return Result.groupNotFound();
		}
		catch (final RuntimeException ex)
		{
			return Result.acceptFailed(ex.getClass().getName() + ": " + ex.getMessage());
		}
		if (group == null)
		{
			return Result.groupNotFound();
		}
		try
		{
			// acceptSuspectsAndMergeGroups, not the narrower acceptSuspects - see interface Javadoc for why
			group.acceptSuspectsAndMergeGroups(Collections.singleton(pSuspect));
		}
		catch (final RuntimeException ex) // IllegalStateException/IllegalArgumentException per Group.html - no checked exception declared
		{
			return Result.acceptFailed(ex.getClass().getName() + ": " + ex.getMessage());
		}
		// the accept itself succeeded - coordinator's live measurement, 2026-09-22 (interface Javadoc, "The
		// class Javadoc above ... is FALSE on this build"): acceptSuspectsAndMergeGroups may ALREADY have
		// performed the real merge. Re-read the suspect's own state before ever touching MergeOperations.
		final MameRecordState.Result suspectState = MameRecordState.get().read(pSuspect);
		final int remaining = AddonMameSuspectDecision.countRemainingMergeable(pSession, pTable, pGroupId);
		final PostAcceptAction action = PostAcceptAction.of(suspectState.addonPresent ? suspectState.state : null, suspectState.goldenKey, remaining);
		if (PostAcceptAction.ALREADY_MERGED.equals(action.action))
		{
			return Result.acceptedAndMerged(action.goldenKey);
		}
		if (PostAcceptAction.NOTHING_TO_MERGE.equals(action.action))
		{
			// nothing left the group scan (which never sees the golden - see interface Javadoc) can call a
			// golden key from; the suspect's own state (checked above) already answered that question when it
			// could. Plain success, no golden to report - see PostAcceptAction#NOTHING_TO_MERGE's own Javadoc.
			return Result.accepted();
		}
		// a merge-policy failure past this point is reported separately and never undoes the accept itself
		// (task brief: "guard every call"; see interface Javadoc)
		try
		{
			final MergeOperations mergeOperations = OperationsFactory.getMergeOperations(pSession);
			final MergeProcess process = mergeOperations.mergeGroup(pTable, pGroupId);
			final Adaptation golden = process == null ? null : process.getGoldenRecord();
			final String goldenKey = golden == null ? null : golden.getOccurrencePrimaryKey().format();
			return Result.acceptedAndMerged(goldenKey);
		}
		catch (final InvalidStateException ex)
		{
			return AddonMameSuspectDecision.mergeFailureOrGolden(pSuspect, ex, AddonMameSuspectDecision.localizedMessage(ex, pSession));
		}
		catch (final InvalidParameterException ex)
		{
			return AddonMameSuspectDecision.mergeFailureOrGolden(pSuspect, ex, AddonMameSuspectDecision.localizedMessage(ex, pSession));
		}
		catch (final OperationException ex)
		{
			return AddonMameSuspectDecision.mergeFailureOrGolden(pSuspect, ex, ex.getMessage());
		}
		catch (final RuntimeException ex)
		{
			return AddonMameSuspectDecision.mergeFailureOrGolden(pSuspect, ex, ex.getMessage());
		}
	}

	/**
	 * Re-fetches {@code pGroupId}'s own group (fresh - {@code acceptSuspectsAndMergeGroups} may just have
	 * changed its membership) and counts how many of its CONFIRMED members ({@code Group.getMatchedRecords()} -
	 * per {@code ebx.MameGroups}'s own established reading of this API, the group's own GOLDEN is never among
	 * them, {@code RecordLineage.GroupInfo#matched}'s own Javadoc: "{@code matched.size() + 1}, the +1 being
	 * this golden itself") are neither {@code MERGED} nor {@code DELETED} (state per {@link MameRecordState}) -
	 * {@code mergeGroup}'s own documented precondition, "Select two or more records that are not 'Merged' or
	 * 'Deleted'", quoted verbatim in the very refusal the coordinator saw live, 2026-09-22. Returns {@code -1}
	 * (unknown) on ANY failure - {@link PostAcceptAction#of} then falls back to attempting {@code mergeGroup}
	 * exactly as {@link #accept} did before this fix, rather than guessing "nothing left to merge". Never
	 * exercised against a running server this session (task constraint) - {@code Group.getMatchedRecords()}
	 * returning a genuinely LIVE view (not one cached at the earlier {@code getGroup} call this method makes
	 * its own, fresh one) is assumed from the interface's own name and was not otherwise confirmed.
	 */
	private static int countRemainingMergeable(final Session pSession, final AdaptationTable pTable, final BigDecimal pGroupId)
	{
		try
		{
			final Group group = OperationsFactory.getGroupOperations(pSession).getGroup(pTable, pGroupId);
			if (group == null)
			{
				return -1;
			}
			int remaining = 0;
			final CloseableIterator<Adaptation> matched = group.getMatchedRecords();
			try
			{
				while (matched.hasNext())
				{
					final MameRecordState.Result state = MameRecordState.get().read(matched.next());
					if (!state.addonPresent)
					{
						return -1;
					}
					if (!State.MERGED.name().equals(state.state) && !State.DELETED.name().equals(state.state))
					{
						remaining++;
					}
				}
			}
			finally
			{
				matched.close();
			}
			return remaining;
		}
		catch (final NotFoundException ex)
		{
			return -1;
		}
		catch (final RuntimeException ex)
		{
			return -1;
		}
	}

	/**
	 * Wraps {@link #mergeFailure} with one more chance to answer {@code ACCEPTED_AND_MERGED} instead - task
	 * brief, 2026-09-22: "If {@code mergeGroup} nevertheless throws while a golden exists for the pivot,
	 * resolve to {@code ACCEPTED_AND_MERGED} ... rather than {@code ACCEPTED_MERGE_FAILED}." Re-reads {@code
	 * pSuspect}'s own state ONE more time (never trusts the earlier, pre-{@code mergeGroup} read - the add-on
	 * could in principle have produced a golden as part of handling the very call that then threw) and only
	 * falls through to the generic/specific failure outcome when that recheck still shows no resolvable golden.
	 * Never observed live this session (the {@code mergeGroup} refusal the coordinator DID see live was already
	 * caught upstream by {@link PostAcceptAction}, so this path is purely defensive).
	 */
	private static MameSuspectDecision.Result mergeFailureOrGolden(final Adaptation pSuspect, final Exception pException, final String pMessage)
	{
		final MameRecordState.Result recheck = MameRecordState.get().read(pSuspect);
		if (recheck.addonPresent && State.MERGED.name().equals(recheck.state) && recheck.goldenKey != null)
		{
			return MameSuspectDecision.Result.acceptedAndMerged(recheck.goldenKey);
		}
		return AddonMameSuspectDecision.mergeFailure(pException, pMessage);
	}

	/**
	 * Coordinator follow-up, 2026-09-22: a policy this factory writes was measured live carrying
	 * {@code useForManualMerge: false} on {@code /root/Company} - fixed at the source in {@code
	 * master.MatchingPolicyTranslator#buildMergePolicy} (now always {@code true}), but an
	 * ALREADY-DEPLOYED policy on some other instance/table could still carry the old value, and {@code
	 * mergeGroup}'s own documented precondition ("a merge policy should have the property `Used for manual
	 * merge` set to true", {@code MergeOperations.html}) means this is the ONE {@code
	 * ACCEPTED_MERGE_FAILED} cause worth naming specifically rather than showing a generic exception
	 * message. Detected by substring match on the exception's own (localized) message for the phrase
	 * "manual merge" (case-insensitive) - a plain-word heuristic, not a documented error code (the add-on's
	 * Javadoc names no such code), so a message that happens not to use those exact words still falls back
	 * to the generic {@link MameSuspectDecision.Result#acceptedMergeFailed(String)} rather than a
	 * mis-detected specific reason. Never verified against a REAL refusal message this session (task
	 * constraint) - the coordinator's own live measurement only confirmed the FLAG's value, not the exact
	 * wording {@code mergeGroup} throws for it.
	 */
	// package-private, not private: takes/returns only plain java.lang types (Exception/String/Result, the
	// latter itself EBX-free) - the substring-detection logic is pure and worth unit-testing directly
	// (MameSuspectDecisionTest), the same "no EBX type, so test it directly" discipline this codebase applies
	// wherever a decision can be isolated this cheaply.
	static MameSuspectDecision.Result mergeFailure(final Exception pException, final String pMessage)
	{
		final String detail = pException.getClass().getName() + ": " + pMessage;
		if (ForeignWording.isManualMergePolicyRefusal(pMessage))
		{
			return MameSuspectDecision.Result.acceptedMergePolicyNotManual(detail);
		}
		return MameSuspectDecision.Result.acceptedMergeFailed(detail);
	}

	@Override
	public Result reject(final Session pSession, final AdaptationTable pTable, final BigDecimal pGroupId, final Adaptation pSuspect)
	{
		if (pTable == null || pGroupId == null || pSuspect == null)
		{
			return Result.groupNotFound();
		}
		final Group group;
		try
		{
			group = OperationsFactory.getGroupOperations(pSession).getGroup(pTable, pGroupId);
		}
		catch (final NotFoundException ex)
		{
			return Result.groupNotFound();
		}
		catch (final RuntimeException ex)
		{
			return Result.rejectFailed(ex.getClass().getName() + ": " + ex.getMessage());
		}
		if (group == null)
		{
			return Result.groupNotFound();
		}
		try
		{
			group.rejectSuspects(Collections.singleton(pSuspect));
			return Result.rejected();
		}
		catch (final RuntimeException ex)
		{
			return Result.rejectFailed(ex.getClass().getName() + ": " + ex.getMessage());
		}
	}

	/** {@code InvalidStateException}/{@code InvalidParameterException} carry a localizable {@code UserMessage} (`getErrorMessage()`) and their own {@code getMessage(Locale)} to resolve it (both javap-verified against {@code ebx-addons.jar}) - falls back to {@code toString()} if that itself fails (never lets a diagnostic-message failure hide the real one). */
	private static String localizedMessage(final Exception pException, final Session pSession)
	{
		try
		{
			final String message = pException instanceof InvalidStateException ? ((InvalidStateException) pException).getMessage(pSession == null ? null : pSession.getLocale())
				: pException instanceof InvalidParameterException ? ((InvalidParameterException) pException).getMessage(pSession == null ? null : pSession.getLocale())
					: pException.getMessage();
			return message == null ? pException.toString() : message;
		}
		catch (final RuntimeException ex)
		{
			return pException.toString();
		}
	}
}
