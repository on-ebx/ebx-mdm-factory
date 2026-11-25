package com.on.ebx.factory.ebx;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.ForeignWording;
import com.on.ebx.factory.master.MameAnswer;
import com.on.ebx.factory.master.MameDataModelResolution;
import com.on.ebx.factory.master.MasterNames;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Self-heals the "MatchingTableTrigger not attached after a bad boot" defect, measured LIVE TWICE
 * (`docs/MATCHING.md` &sect;13.7): 2026-09-22 15:11 and again 23:26, both right after a Tomcat restart, both
 * on Proposal review &rarr; "Simulate on 20 pivots" on {@code Company}. The add-on itself threw, INSIDE its
 * own canopy/Lucene candidate-search layer: {@code java.lang.IllegalArgumentException: field
 * [Linked_Field_State] not found; input fields are: [id, sourceSystem, ..., ebx-metadata, $docid, ...]} -
 * {@code CnyFilterBuilder.addTableParameters} &larr; {@code CnyCanopyProducer.getSimilar} &larr;
 * {@code SimilarFinder.getSimilar} &larr; {@code SimulateMatchingService.simulate} &larr;
 * {@code MatchingOperationsImpl.simulateMatch} &larr; this module's own {@link MameMatchingApi#simulate} - and
 * the user service page showed EBX's generic "A fatal error occurred" instead of anything a reviewer could
 * act on.
 * <p>
 * <b>Root cause (CLAUDE.md &sect;8, "huge Calcite stack trace at boot")</b>: on some boots the add-on's own
 * {@code MameDataModelModifier} fails with {@code IllegalStateException: Recursive load of:
 * LogicalProject(...)} and the {@code MatchingTableTrigger} never gets attached to the business table.
 * Without that trigger, the table's own Lucene index never gains the metadataset-linked fields
 * ({@code Linked_Field_State}/{@code GroupId}/{@code GoldenId}/...) the canopy filter unconditionally
 * expects. {@code get_matching_activation_status(table)} answers exactly this situation with
 * {@code state != ENABLED}; {@code activate_matching(table)} repairs it with no restart needed (measured live
 * 2026-09-22 12:54: it resets the schema, recompiles it on an HTTP thread and re-attaches the trigger).
 * <p>
 * <b>2026-09-23 07:45, second live incident, the call ORDER itself was wrong.</b> On {@code Contact}
 * (a plan still {@code PROPOSED}, table NEVER configured before), {@code get_matching_activation_status}
 * answered a genuine {@code CONFIG_NOT_FOUND} - not the boot-race shape at all - even though
 * {@code ebx.MatchPlanSimulationRunner#simulate} calls {@code ebx.MatchingPreviewPolicy#ensure} (which WOULD
 * have just created that very configuration) textually BEFORE this class. The real bug was narrower than
 * "wrong order": (1) this class used to REQUIRE {@code published && metadatasetExists} before even
 * attempting {@code activate_matching} - a config {@code ensure} just generated via
 * {@code generate_matching_policy}/{@code generate_merge_policy} is neither published nor metadataset-backed
 * yet, so that gate refused outright instead of self-healing a table that was simply never activated for the
 * first time; (2) {@code ensure} and this class each independently resolved {@code AMBIGUOUS_TABLE} from a
 * DIFFERENT tool's own candidate list ({@code get_matching_config} vs. {@code get_matching_activation_status})
 * - nothing guaranteed the two resolutions agreed on the same {@code dataModel}, so a config written under one
 * data model could read back {@code CONFIG_NOT_FOUND} under the other. Both are fixed here: {@link
 * MatchingPreviewPolicy.Result#dataModel} now carries the EXACT value {@code ensure} resolved and used, reused
 * verbatim by {@link #ensureActivatedAfterPreviewEnsure} (no second, independent resolution); and {@link
 * #decide} no longer looks at {@code published}/{@code metadatasetExists} at all - ANY found-but-not-{@code
 * ENABLED} config gets ONE {@code activate_matching} attempt, unconditionally, then a fresh RE-READ (never
 * trusting {@code activate_matching}'s own returned status as final - {@code
 * ebx.MatchPlanSimulationRunnerTest}'s own coordinator review, 2026-09-23) before refusing.
 * <b>A never-before-activated table's metadataset is therefore now created as a side effect of a "Simulate"
 * click</b> - a one-way but harmless write (the metadataset is exactly what the table needs to be simulated
 * or matched at all; nothing about it is undone by leaving the plan unaccepted).
 * <p>
 * <b>Two independent jobs, kept in one file because they share the same "never let the add-on's own exception
 * reach the user service unwrapped" goal</b>:
 * <ol>
 * <li>{@link #ensureActivatedBeforeSimulate} (config already exists - an {@code APPLIED} plan, or
 * {@code ebx.MatchingConfigurator#simulate}'s own REST route, which requires {@code APPLIED} outright) and
 * {@link #ensureActivatedAfterPreviewEnsure} (config was just written/confirmed by {@code
 * ebx.MatchingPreviewPolicy#ensure} - a {@code PROPOSED}/{@code ACCEPTED} plan) - both called ONCE per
 * "Simulate" click, before the FIRST {@code simulateMatch}/{@code executeDecisionTree} call, both funnel into
 * the SAME core ({@link #activateIfNeeded}) once a {@code dataModel} is known.</li>
 * <li>{@link #isAddonIndexFailure}/{@link #translateAddonIndexFailure} - a SECOND, independent guard inside
 * {@link MameMatchingApi#simulate} itself, for the (expected to be rare, now that job 1 runs first) case
 * where the add-on still throws this way regardless. Defense in depth, never load-bearing on its own.</li>
 * </ol>
 * <p>
 * <b>Testability seam ({@link StatusPort}) and its own limit.</b> {@link #activateIfNeeded} - the actual
 * decision/retry core - depends only on the small {@link StatusPort} interface, never directly on
 * {@code McpClient} (which is {@code final} with a package-private {@code ToolResult} constructor - this
 * codebase's established discipline is to never construct a real one in a test, {@code
 * ebx.MatchingPreviewPolicyTest}'s own class Javadoc), so {@code MatchingActivationGuardTest} exercises the
 * EXACT "status &rarr; activate &rarr; status" / "status only" call sequences with a hand-written fake -
 * genuine behavior coverage, not a placeholder. What that seam does NOT reach: the OUTER order
 * "{@code ensure} (preview only) &rarr; this class &rarr; {@code simulateMatch}" inside {@code
 * MatchPlanSimulationRunner#simulate} itself is a plain sequence of statements where each step's own thrown
 * {@link OperationException} skips every statement after it - verified by reading that method (three
 * un-catch-wrapped calls in a row), not by a fake-client integration test: {@code ensure} needs a live
 * {@code Session}/{@code Adaptation} (org-wide convention: never faked, no fake {@code Adaptation}
 * implementation exists anywhere in this codebase) and {@link MameMatchingApi#simulate} calls the add-on's
 * Java API directly, no {@code McpClient} involved at all, so it could not join the SAME recorded sequence as
 * the two MCP calls even with a faked transport.
 * <p>
 * <b>2026-09-23 14:20 - the "each independently resolved AMBIGUOUS_TABLE" fix above still left one path doing
 * its own probe-then-resolve read (`docs/MATCHING.md` &sect;28).</b> {@link #ensureActivatedBeforeSimulate}
 * used to call {@code get_matching_activation_status} unscoped FIRST, purely to decide whether {@code
 * dataModel} was needed at all - the exact "resolve once from a read" shape measured live to be unsound (a
 * table's own read can succeed with no {@code dataModel} while a write on it still needs one). Both orchestration
 * methods now compute {@code dataModel} directly from the project's master model name, no probe call, and {@link
 * McpStatusPort} (through {@link MameCalls#call}) retries once on a genuine {@code AMBIGUOUS_TABLE} as the
 * remaining safety net - never exercised live this session (task constraint), verified only by {@code
 * MameCallsTest} against the exact live payload.
 * <p>
 * <b>2026-09-23 15:50 - third live incident: {@code state == ENABLED} does not mean ready
 * (`docs/MATCHING.md` &sect;29).</b> On {@code Contact} - a PREVIEW plan, the config written correctly this
 * time (matching + merge policy linked, default cleared, §27/§28's own fixes both already in) -
 * {@code get_matching_activation_status} answered {@code {"metadatasetExists":false,"paused":false,
 * "published":true,"state":"ENABLED"}}. {@link #decide} (as it stood right after the 2026-09-23 07:45 fix
 * above) reads only {@code state}, saw {@code ENABLED}, and returned {@link Verdict#PROCEED} without ever
 * calling {@code activate_matching} - {@code simulateMatch} then failed with the exact {@code Linked_Field_State}
 * "not found" error this whole class exists to self-heal (class Javadoc, top). So {@code state: ENABLED} means
 * only "the matching trigger is attached", NOT "the add-on's metadataset for this table exists" - the two are
 * usually the same moment (the boot-race defect at the top of this Javadoc leaves BOTH unset together) but are
 * NOT always the same fact, as this incident proves. Fixed: {@link #decide} and the post-{@code
 * activate_matching} recheck in {@link #activateIfNeeded} both now go through one shared readiness test, {@link
 * #isReady(StatusSnapshot)} - {@code state == ENABLED AND metadatasetExists} - never {@code state} alone.
 * {@link #refusalMessage} now tells the two ways a table can still not be ready apart: "the add-on never enabled
 * it" (the pre-2026-09-23 wording) vs. "it reports ENABLED but never created a metadataset" (new) - the second is
 * exactly this incident, replayed after {@code activate_matching} itself failed to fix it a second time. {@link
 * #reactivatedNote} (replacing the constant {@code REACTIVATED_NOTE}) reads the PRE-activation snapshot to say
 * which of the two was actually missing, instead of a single fixed phrase that assumed it was always the
 * trigger.
 */
public final class MatchingActivationGuard
{
	/**
	 * Prefixes an {@link OperationException} message this class itself produces (a refusal from {@link
	 * #activateIfNeeded}) - {@code ebx.ui.ProposalReviewUserService#simulateOne} strips it (see
	 * {@link #isActivationRefusal}/{@link #stripMarker}, both {@code public}: the only two members of this
	 * class a DIFFERENT package needs) to pick the dedicated {@code review.match.simulate.activation.refused}
	 * bundle key instead of the generic {@code review.match.simulate.failed} every other failure of that
	 * method already uses - a plain internal tag, never sent to the add-on, never shown to a reviewer with the
	 * marker still attached.
	 */
	static final String REFUSAL_MARKER = "MATCHING_NOT_ACTIVATED: ";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MatchingActivationGuard()
	{
	}

	// ------------------------------------------------------------------------------------------ pure status/decision

	/**
	 * {@code ActivationStatus} as {@code get_matching_activation_status}/{@code activate_matching} both answer
	 * it. {@code state} and {@code metadatasetExists} are BOTH decision inputs again since 2026-09-23 15:50
	 * (class Javadoc, `docs/MATCHING.md` &sect;29: {@code state == ENABLED} alone does not mean the table is
	 * ready - measured live on a table with {@code state:"ENABLED", metadatasetExists:false}) - see {@link
	 * #isReady}, the one place both are read together. {@code published} remains diagnostic-only, kept here for
	 * {@link #refusalMessage}'s own detail text, never itself a decision input.
	 */
	static final class StatusSnapshot
	{
		final String state;
		final boolean published;
		final boolean metadatasetExists;

		StatusSnapshot(final String pState, final boolean pPublished, final boolean pMetadatasetExists)
		{
			this.state = pState;
			this.published = pPublished;
			this.metadatasetExists = pMetadatasetExists;
		}

		boolean enabled()
		{
			return "ENABLED".equals(this.state);
		}
	}

	/**
	 * {@code true} only when the table is genuinely usable: {@code state == ENABLED} AND {@code
	 * metadatasetExists} (2026-09-23 15:50, class Javadoc &sect;29 - {@code state} alone was measured live to
	 * read {@code ENABLED} on a table whose metadataset the add-on never created, and {@code simulateMatch} then
	 * fails on the missing {@code Linked_Field_State} field). The single place both fields are read together -
	 * {@link #decide} and {@link #activateIfNeeded}'s post-{@code activate_matching} recheck both call this
	 * instead of {@link StatusSnapshot#enabled} directly.
	 */
	static boolean isReady(final StatusSnapshot pStatus)
	{
		return pStatus.enabled() && pStatus.metadatasetExists;
	}

	enum Verdict
	{
		/** {@link #isReady} already {@code true}: call the add-on right away, nothing to repair. */
		PROCEED,
		/**
		 * {@code !isReady(status)} - UNCONDITIONALLY, since 2026-09-23 (class Javadoc: the {@code published}/
		 * {@code metadatasetExists} gate used to refuse a freshly-generated, never-yet-activated configuration
		 * outright, which is exactly the state {@code ebx.MatchingPreviewPolicy#ensure} always leaves a
		 * brand-new table in). {@code activate_matching}'s own contract only requires a configuration to
		 * already exist - which {@link #activateIfNeeded}'s caller has, by construction, already confirmed via
		 * a successful {@link StatusPort#readStatus} before {@link #decide} is ever called. Reached both when
		 * the trigger itself is missing ({@code state != ENABLED}) and when it is attached but the metadataset
		 * is not ({@code state == ENABLED, metadatasetExists == false} - &sect;29's own incident).
		 */
		ACTIVATE_THEN_RECHECK
	}

	/** Pure: {@link #isReady} proceeds, anything else gets one {@code activate_matching} attempt. See {@link Verdict}'s own Javadoc. */
	static Verdict decide(final StatusSnapshot pStatus)
	{
		return MatchingActivationGuard.isReady(pStatus) ? Verdict.PROCEED : Verdict.ACTIVATE_THEN_RECHECK;
	}

	/** Same {@code data}-wrapped-with-root-fallback envelope every match-merge answer already reads ({@link MameAnswer#field}) - never independently confirmed for THIS tool's answer shape. */
	static StatusSnapshot parseStatus(final JsonNode pAnswer)
	{
		final String state = MameAnswer.field(pAnswer, "state").asText(null);
		final boolean published = MameAnswer.field(pAnswer, "published").asBoolean(false);
		final boolean metadatasetExists = MameAnswer.field(pAnswer, "metadatasetExists").asBoolean(false);
		return new StatusSnapshot(state, published, metadatasetExists);
	}

	/**
	 * Names the state and the remedy - reached ONLY after {@link #activateIfNeeded} already tried
	 * {@code activate_matching} once and re-read, and the table is STILL not {@link #isReady}. Always {@link
	 * #REFUSAL_MARKER}-prefixed.
	 * <p>
	 * 2026-09-23 15:50 (class Javadoc &sect;29): branches on WHICH half of {@link #isReady} is still false, since
	 * they are two different failure shapes a person needs to act on differently - {@code state != ENABLED} (the
	 * pre-existing wording: the add-on itself refused to enable the table) vs. {@code state == ENABLED but
	 * metadatasetExists == false} (new: the trigger is attached but the add-on never created the metadataset,
	 * the exact incident this section documents - {@code activate_matching} reporting success does not by
	 * itself mean the metadataset exists, so this can happen even right after a "successful" repair).
	 */
	static String refusalMessage(final String pTablePath, final StatusSnapshot pStatus)
	{
		final String stateText = pStatus.state == null ? "UNKNOWN" : pStatus.state;
		if (pStatus.enabled() && !pStatus.metadatasetExists)
		{
			return MatchingActivationGuard.REFUSAL_MARKER + "matching for " + pTablePath + " reports state=ENABLED (the matching trigger is attached) "
				+ "but metadatasetExists=false even after calling activate_matching to repair it - the add-on did not create this table's metadataset, "
				+ "so a simulate/match call still fails on a missing Linked_Field_* field (measured live 2026-09-23 15:50, `docs/MATCHING.md` sec 29); "
				+ "this is a different failure than 'not enabled' - try activate_matching again from the MAME administration screen, or restart the "
				+ "EBX server.";
		}
		return MatchingActivationGuard.REFUSAL_MARKER + "matching for " + pTablePath + " is still not activated (state=" + stateText + ", published="
			+ pStatus.published + ", metadatasetExists=" + pStatus.metadatasetExists + ") even after calling activate_matching to repair it - the add-on "
			+ "refused to enable it; check the MAME configuration for this table, or restart the EBX server if it was already configured and activated "
			+ "before the last boot.";
	}

	/**
	 * {@link MatchPlanView.SimulationResult#activationNote}'s value when {@link #activateIfNeeded} actually
	 * repaired the table - built from {@code pBefore}, the PRE-activation snapshot (2026-09-23 15:50, class
	 * Javadoc &sect;29: replaces the old fixed {@code REACTIVATED_NOTE} constant, which always said "matching
	 * trigger was not attached", even in the case measured live where the trigger WAS already attached and only
	 * the metadataset was missing - misleading about what was actually repaired).
	 */
	static String reactivatedNote(final StatusSnapshot pBefore)
	{
		final boolean triggerMissing = !pBefore.enabled();
		final boolean metadatasetMissing = !pBefore.metadatasetExists;
		final String what;
		if (triggerMissing && metadatasetMissing)
		{
			what = "the matching trigger was not attached and its metadataset did not exist";
		}
		else if (triggerMissing)
		{
			what = "the matching trigger was not attached";
		}
		else
		{
			// triggerMissing == false here, so metadatasetMissing must be true (isReady would have been true
			// otherwise and activateIfNeeded would never have reached ACTIVATE_THEN_RECHECK at all).
			what = "the matching trigger was attached (state=ENABLED) but its metadataset did not exist yet";
		}
		return what + " after the last boot - re-activated";
	}

	/**
	 * The FIRST {@link StatusPort#readStatus} of a click answered "no configuration found" at all -
	 * {@code pJustEnsured} distinguishes the two situations {@link #activateIfNeeded} can reach this from
	 * (2026-09-23 fix, class Javadoc): {@code true} when {@code ebx.MatchingPreviewPolicy#ensure} was just
	 * called and is assumed to have succeeded (it throws otherwise) - a not-found answer right after THAT
	 * means the write itself did not stick, an alarming, different situation from {@code false} (an
	 * {@code APPLIED} plan / {@code ebx.MatchingConfigurator#simulate}'s own route, which never calls
	 * {@code ensure} and genuinely expects a configuration to already exist).
	 */
	static String notConfiguredRefusalMessage(final String pTablePath, final String pCode, final boolean pJustEnsured)
	{
		final String codeText = pCode == null ? "an unexpected soft miss" : pCode;
		if (pJustEnsured)
		{
			return MatchingActivationGuard.REFUSAL_MARKER + "a preview matching policy was just written for " + pTablePath
				+ " but get_matching_activation_status still answers " + codeText + " right after - the preview write may have failed silently; "
				+ "retry, or check the MAME configuration for this table directly.";
		}
		return MatchingActivationGuard.REFUSAL_MARKER + "matching for " + pTablePath + " is not configured (get_matching_activation_status answered "
			+ codeText + ") - run matching/configure for this entity first.";
	}

	/** The RE-READ after a successful {@code activate_matching} answered "no configuration found" - always alarming (by this point a configuration was already confirmed to exist once), never the "not configured yet" wording. */
	static String activateSucceededButRereadMissingMessage(final String pTablePath, final String pCode)
	{
		return MatchingActivationGuard.REFUSAL_MARKER + "activate_matching for " + pTablePath + " reported success, but a fresh "
			+ "get_matching_activation_status read right after answered " + (pCode == null ? "an unexpected soft miss" : pCode)
			+ " - matching is not reliably activated; retry, or check the MAME configuration for this table directly.";
	}

	/**
	 * {@code true} only for a refusal this class itself produced ({@link #REFUSAL_MARKER}) - never for a
	 * transport error or an unrelated {@link OperationException} from elsewhere in the same call chain.
	 * {@code public}: called from {@code ebx.ui.ProposalReviewUserService#simulateOne}, a different package
	 * (class Javadoc).
	 */
	public static boolean isActivationRefusal(final String pMessage)
	{
		return pMessage != null && pMessage.startsWith(MatchingActivationGuard.REFUSAL_MARKER);
	}

	/** The message with {@link #REFUSAL_MARKER} removed - safe to call on a message that never carried it (returned unchanged). {@code public} - see {@link #isActivationRefusal}'s own Javadoc. */
	public static String stripMarker(final String pMessage)
	{
		if (pMessage != null && pMessage.startsWith(MatchingActivationGuard.REFUSAL_MARKER))
		{
			return pMessage.substring(MatchingActivationGuard.REFUSAL_MARKER.length());
		}
		return pMessage;
	}

	// -------------------------------------------------------------------------- add-on canopy/Lucene exception guard

	/**
	 * {@code true} for the EXACT defect this class exists for (a {@code Linked_Field_*} field missing from the
	 * business table's own Lucene index - class Javadoc) OR any other {@code RuntimeException} raised from
	 * inside the add-on's own canopy/clustering/candidate-search layer ({@code com.orchestranetworks.cce.*} -
	 * the package the measured stack trace's every frame from {@code CnyFilterBuilder} down belongs to), walking
	 * the full cause chain (the add-on may wrap its own internal exception before it reaches this module's
	 * caller). Never matches a plain {@code InvalidParameterException}/{@code InvalidStateException} - those
	 * are the add-on's OWN typed refusals, already handled by {@link MameMatchingApi#simulate}'s existing
	 * catches, both raised from {@code com.orchestranetworks.addon.mame.common}, a DIFFERENT package.
	 */
	static boolean isAddonIndexFailure(final Throwable pEx)
	{
		return ForeignWording.isAddonIndexFailure(pEx);
	}

	/** The exact wording the original task brief quotes, {@code pTablePath} and the raw exception message substituted in. */
	static OperationException translateAddonIndexFailure(final String pTablePath, final RuntimeException pEx)
	{
		return EngineRefusal.codedWithCause("conflict.matchingTriggerMissing", "The add-on's index of " + pTablePath + " has no metadataset-linked fields (Linked_Field_State): the "
			+ "matching trigger is not attached — re-activate matching (activate_matching) or restart; raw: " + pEx.getMessage(), pEx, pTablePath);
	}

	// -------------------------------------------------------------------------------------- testability seam (port)

	/**
	 * The only two MCP operations {@link #activateIfNeeded} needs, abstracted away from {@code McpClient}
	 * itself (class Javadoc: {@code McpClient}/{@code ToolResult} cannot be faked from this package - final
	 * class, package-private constructor). {@code pDataModelOrNull} is ALREADY resolved by the caller (never
	 * re-resolved here - {@code AMBIGUOUS_TABLE} handling lives in {@link McpStatusPort}, the one production
	 * implementation, entirely OUTSIDE this interface's contract).
	 */
	interface StatusPort
	{
		/** @throws ConfigNotFoundException a soft-miss answer (see that class' own Javadoc) - {@code RuntimeException}, never declared. */
		StatusSnapshot readStatus(String pTablePath, String pDataModelOrNull) throws OperationException;

		/** Same failure shape as {@link #readStatus}, for {@code activate_matching}; its own returned status is intentionally NOT read by {@link #activateIfNeeded} - the RE-READ ({@link #readStatus} again) is what decides (class Javadoc, 2026-09-23). */
		void activate(String pTablePath, String pDataModelOrNull) throws OperationException;
	}

	/** Unchecked on purpose - thrown by a {@link StatusPort} to mean "no configuration found for this table at all" (a soft miss), letting {@link #activateIfNeeded} tell it apart from a transport failure ({@link OperationException}) without a second checked-exception type cluttering {@link StatusPort}'s own signature. */
	static final class ConfigNotFoundException extends RuntimeException
	{
		private static final long serialVersionUID = 1L;
		/** The soft-miss {@code code} (e.g. {@code CONFIG_NOT_FOUND}), or {@code null} if the answer carried none. */
		final String code;

		ConfigNotFoundException(final String pCode)
		{
			super(pCode);
			this.code = pCode;
		}
	}

	/**
	 * The core decision/retry loop, {@code McpClient}-free (class Javadoc) - unit-tested directly
	 * ({@code MatchingActivationGuardTest}) against a hand-written {@link StatusPort} fake that records the
	 * exact call sequence: {@code status} only when already {@code ENABLED}; {@code status, activate, status}
	 * when a repair is needed.
	 *
	 * @param pJustEnsured see {@link #notConfiguredRefusalMessage}'s own Javadoc
	 * @return {@link #reactivatedNote(StatusSnapshot)} of the PRE-activation snapshot when a repair actually
	 *         happened, {@code null} when matching was already {@link #isReady}
	 * @throws OperationException the table could not be made runnable - always {@link #REFUSAL_MARKER}-prefixed
	 */
	static String activateIfNeeded(final StatusPort pPort, final String pTablePath, final String pDataModelOrNull, final boolean pJustEnsured)
		throws OperationException
	{
		final StatusSnapshot status;
		try
		{
			status = pPort.readStatus(pTablePath, pDataModelOrNull);
		}
		catch (final ConfigNotFoundException ex)
		{
			throw EngineRefusal.coded("conflict.matchingNotReady", MatchingActivationGuard.notConfiguredRefusalMessage(pTablePath, ex.code, pJustEnsured), pTablePath);
		}
		if (MatchingActivationGuard.decide(status) == Verdict.PROCEED)
		{
			return null;
		}

		// ACTIVATE_THEN_RECHECK - the one repair this class ever attempts, exactly once.
		try
		{
			pPort.activate(pTablePath, pDataModelOrNull);
		}
		catch (final ConfigNotFoundException ex)
		{
			throw EngineRefusal.coded("conflict.matchingNotReady", MatchingActivationGuard.notConfiguredRefusalMessage(pTablePath, ex.code, pJustEnsured), pTablePath);
		}

		// RE-READ - never trust activate_matching's own returned status as final (2026-09-23, class Javadoc).
		final StatusSnapshot recheck;
		try
		{
			recheck = pPort.readStatus(pTablePath, pDataModelOrNull);
		}
		catch (final ConfigNotFoundException ex)
		{
			throw EngineRefusal.coded("conflict.matchingNotReady", MatchingActivationGuard.activateSucceededButRereadMissingMessage(pTablePath, ex.code), pTablePath);
		}
		if (MatchingActivationGuard.isReady(recheck))
		{
			return MatchingActivationGuard.reactivatedNote(status);
		}
		throw EngineRefusal.coded("conflict.matchingNotReady", MatchingActivationGuard.refusalMessage(pTablePath, recheck), pTablePath);
	}

	// --------------------------------------------------------------------------------------------- orchestration

	/**
	 * Config already exists (an {@code APPLIED} plan; {@code ebx.MatchingConfigurator#simulate}'s own route,
	 * which requires {@code APPLIED} outright and never calls {@code ebx.MatchingPreviewPolicy#ensure}).
	 * <p>
	 * <b>2026-09-23 14:20 correction (`docs/MATCHING.md` &sect;28) - no more "self-resolve {@code AMBIGUOUS_TABLE}
	 * once from a read".</b> This used to call {@code get_matching_activation_status} unscoped first, purely to
	 * decide whether the table even needed a resolved {@code dataModel} - exactly the "resolve once from a read,
	 * reuse (or omit) for every later call" discipline measured live to be unsound (a table can answer a READ
	 * successfully with no {@code dataModel} while a WRITE on the SAME table still needs one). {@code dataModel}
	 * is now always {@link MameDataModelResolution#publicationUrn(String)} of the project's own master model,
	 * computed directly with no probe call at all; {@link McpStatusPort} (via {@link MameCalls#call}) still
	 * retries once on a genuine {@code AMBIGUOUS_TABLE} as a safety net.
	 */
	static String ensureActivatedBeforeSimulate(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTablePath)
		throws OperationException
	{
		final McpClient client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);
		final String publicationName = MasterNames.modelName(pProjectCode);
		final String dataModel = MameDataModelResolution.publicationUrn(publicationName);
		return MatchingActivationGuard.activateIfNeeded(new McpStatusPort(client, publicationName, MatchingActivationGuard.businessObjectMains(pFactory, client, pProjectCode, publicationName, pTablePath)), pTablePath, dataModel, false);
	}

	/**
	 * lot R12 (2026-09-27, docs/MATCHING.md &sect;41, live evidence A): the SAME self-heal as {@link
	 * #ensureActivatedBeforeSimulate} (a config already exists - here, an {@code APPLIED} plan, the precondition
	 * {@code ebx.MatchingConfigurator#configureOne} already enforces for a plan to have reached {@code APPLIED} at
	 * all), given its own name so a reader of {@code MatchingConfigurator#runInternal}/{@code #configureOne} does
	 * not have to wonder why a "simulate" method is called before a matching RUN. Measured live, right after a
	 * Tomcat restart hit the boot race CLAUDE.md &sect;8/this class' own class Javadoc describe:
	 * {@code get_matching_activation_status} answered {@code state: DISABLED} on tables whose {@code MatchPlan}
	 * was already {@code APPLIED} (a previously-successful {@code configure}), and {@code runInternal} launched
	 * {@code matchRecords} straight into it anyway - refused ("Please check the Table activation and settings
	 * configuration") on every entity, every time a boot happened to hit the race. Called once per entity right
	 * before {@link MameMatchingApi#launchScoped} in {@code runInternal}, and a SECOND time at the very end of
	 * {@code configureOne} (the same "independent recheck at the very end, after every write this method makes"
	 * discipline lot R10 already applies to the relation-field relink) - a final guard against the same race
	 * having hit AFTER {@code configureOne}'s own {@code activate_matching} call already ran.
	 *
	 * @return {@link #reactivatedNote(StatusSnapshot)} when a repair actually ran, {@code null} when the table was
	 *         already ready - the caller records {@code activationRepaired: true} in its own step detail only in
	 *         the first case (this class never touches an {@code ObjectNode} step detail itself).
	 */
	static String ensureActivatedBeforeRun(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTablePath)
		throws OperationException
	{
		final McpClient client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);
		final String publicationName = MasterNames.modelName(pProjectCode);
		final String dataModel = MameDataModelResolution.publicationUrn(publicationName);
		return MatchingActivationGuard.activateIfNeeded(new McpStatusPort(client, publicationName, MatchingActivationGuard.businessObjectMains(pFactory, client, pProjectCode, publicationName, pTablePath)), pTablePath, dataModel, false);
	}

	/**
	 * Config was just written or confirmed by {@code ebx.MatchingPreviewPolicy#ensure} (a {@code PROPOSED}/
	 * {@code ACCEPTED} plan) - {@code pResolvedDataModel} is the EXACT value {@code ensure} itself used ({@link
	 * MatchingPreviewPolicy.Result#dataModel}, always {@link MameDataModelResolution#publicationUrn(String)} of
	 * the same project's master model since 2026-09-23, `docs/MATCHING.md` &sect;28), reused VERBATIM as {@link
	 * #activateIfNeeded}'s own {@code pDataModelOrNull} argument - the SAME value {@link McpStatusPort} would
	 * have computed on its own from {@code pProjectCode} anyway, so this is not a second independent resolution.
	 */
	static String ensureActivatedAfterPreviewEnsure(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTablePath,
		final String pResolvedDataModel) throws OperationException
	{
		final McpClient client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);
		return MatchingActivationGuard.activateIfNeeded(new McpStatusPort(client, MasterNames.modelName(pProjectCode),
			MatchingActivationGuard.businessObjectMains(pFactory, client, pProjectCode, MasterNames.modelName(pProjectCode), pTablePath)), pTablePath, pResolvedDataModel, true);
	}

	/**
	 * Wraps a real {@code McpClient} as a {@link StatusPort} - the ONLY class in this file that touches {@code
	 * McpClient}/{@code ToolResult} directly. {@code pDataModelOrNull} - {@link #activateIfNeeded}'s own,
	 * already-resolved value, reused verbatim by every {@link StatusPort} implementation's contract - is not
	 * itself read here any more (2026-09-23, `docs/MATCHING.md` &sect;28): {@link #callTool} routes through
	 * {@link MameCalls#call}, which computes and sends the SAME {@code dataModel} value from {@link
	 * #publicationName} on every call, unconditionally, and retries once on a genuine {@code AMBIGUOUS_TABLE} -
	 * a safety net {@code pDataModelOrNull} alone (a plain string the caller already trusts) cannot provide.
	 */
	private static final class McpStatusPort implements StatusPort
	{
		private final McpClient client;
		private final String publicationName;
		/** The declared business objects of the table, resolved only when an activation is actually needed (never on the ready path); null = none to look for. */
		private final java.util.function.Supplier<List<String>> businessObjectMains;

		McpStatusPort(final McpClient pClient, final String pPublicationName, final java.util.function.Supplier<List<String>> pBusinessObjectMains)
		{
			this.client = pClient;
			this.publicationName = pPublicationName;
			this.businessObjectMains = pBusinessObjectMains;
		}

		@Override
		public StatusSnapshot readStatus(final String pTablePath, final String pDataModelOrNull) throws OperationException
		{
			final ObjectNode args = MatchingActivationGuard.MAPPER.createObjectNode();
			args.put("table", pTablePath);
			final McpClient.ToolResult result = MatchingActivationGuard.callTool(this.client, "get_matching_activation_status", args, pTablePath, this.publicationName);
			if (!MameAnswer.success(result.json, false))
			{
				throw new ConfigNotFoundException(MameAnswer.code(result.json));
			}
			return MatchingActivationGuard.parseStatus(result.json);
		}

		@Override
		public void activate(final String pTablePath, final String pDataModelOrNull) throws OperationException
		{
			// a table that is part of a business object is activated through it (activate_matching is refused on a member, measured on W13BO 2026-10-12)
			final List<String> mains = this.businessObjectMains == null ? null : this.businessObjectMains.get();
			if (mains != null && !mains.isEmpty())
			{
				for (final String main : mains)
				{
					final ObjectNode boArgs = MatchingActivationGuard.MAPPER.createObjectNode();
					boArgs.put("businessObject", main);
					final McpClient.ToolResult boResult = MatchingActivationGuard.callTool(this.client, "activate_business_object_matching", boArgs, pTablePath, this.publicationName);
					if (!MameAnswer.success(boResult.json, false))
					{
						throw new ConfigNotFoundException(MameAnswer.code(boResult.json));
					}
				}
				return;
			}
			final ObjectNode args = MatchingActivationGuard.MAPPER.createObjectNode();
			args.put("table", pTablePath);
			final McpClient.ToolResult result = MatchingActivationGuard.callTool(this.client, "activate_matching", args, pTablePath, this.publicationName);
			// same success-shape discipline ebx.MatchingConfigurator's own activate_matching step already applies
			// (default false, not true: this tool's answer is only trusted with an EXPLICIT success:true) - never
			// the generic requireSuccess default, which is specific to a DIFFERENT family of write tools.
			if (!MameAnswer.success(result.json, false))
			{
				throw new ConfigNotFoundException(MameAnswer.code(result.json));
			}
		}
	}

	/** Lazily resolves the declared business objects of {@code pTablePath} (see {@link MameBusinessObjects#declaredMainsOf}) - evaluated only when an activation is needed. */
	private static java.util.function.Supplier<List<String>> businessObjectMains(final Adaptation pFactory, final McpClient pClient, final String pProjectCode,
		final String pModelName, final String pTablePath)
	{
		return () -> MameBusinessObjects.declaredMainsOf(pFactory, pClient, pProjectCode, pModelName, pTablePath);
	}

	/** {@code pPublicationName} is the project's bare master model name (never a resolved {@code dataModel} value any more) - see {@link MameCalls#call}'s own Javadoc for the two-layer discipline this delegates to. */
	private static McpClient.ToolResult callTool(final McpClient pClient, final String pToolName, final ObjectNode pArgs, final String pTablePath,
		final String pPublicationName) throws OperationException
	{
		final McpClient.ToolResult result;
		try
		{
			result = MameCalls.call(pClient, pToolName, pArgs, pPublicationName);
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.matchingCall", MatchingActivationGuard.REFUSAL_MARKER + pToolName + " failed for " + pTablePath + ": " + ex.getMessage(), ex, pToolName, pTablePath);
		}
		catch (final OperationException ex)
		{
			// MameCalls.call's own AMBIGUOUS_TABLE-could-not-resolve refusal - re-wrapped so isActivationRefusal
			// still recognises it (class Javadoc: every OperationException this class produces carries the marker).
			throw EngineRefusal.codedWithCause("external.matchingCall", MatchingActivationGuard.REFUSAL_MARKER + pToolName + " could not resolve which data model owns " + pTablePath + ": "
				+ ex.getMessage(), ex, pToolName, pTablePath);
		}
		if (result.error)
		{
			throw EngineRefusal.coded("external.matchingCall", MatchingActivationGuard.REFUSAL_MARKER + pToolName + " failed for " + pTablePath + ": " + MatchingActivationGuard.shortText(result.text), pToolName, pTablePath);
		}
		return result;
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 300 ? text.substring(0, 300) + "..." : text;
	}
}
