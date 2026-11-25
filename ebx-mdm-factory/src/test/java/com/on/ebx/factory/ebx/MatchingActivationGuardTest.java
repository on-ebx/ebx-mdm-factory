package com.on.ebx.factory.ebx;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.orchestranetworks.service.OperationException;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. Covers the pure parts of
 * {@link MatchingActivationGuard} - the status/decision transitions, the {@link
 * MatchingActivationGuard#activateIfNeeded} retry core against a hand-written {@link
 * MatchingActivationGuard.StatusPort} fake that records the EXACT call sequence (coordinator follow-up,
 * 2026-09-23: "status" only when already {@link MatchingActivationGuard#isReady ready} - {@code state ==
 * ENABLED AND metadatasetExists}, not {@code state == ENABLED} alone since the 15:50 fix, `docs/MATCHING.md`
 * &sect;29; "status, activate, status" when a repair is needed - the RE-READ is a real, separate call, never a
 * reuse of {@code activate_matching}'s own returned status, see that class' own Javadoc for why), the
 * exception-translation message pattern, and the marker {@code ebx.ui.ProposalReviewUserService} relies on.
 * Never the live MCP orchestration itself ({@code ensureActivatedBeforeSimulate}/{@code
 * ensureActivatedAfterPreviewEnsure}), which need a real {@code McpClient}/{@code Session}/{@code Adaptation}
 * this test suite has none of - same "never construct a real McpClient in a test" discipline {@code
 * MatchingPreviewPolicyTest}'s own class Javadoc states, and the exact reason {@link
 * MatchingActivationGuard.StatusPort} exists as a separate, fakeable seam at all (that class' own class
 * Javadoc).
 */
public final class MatchingActivationGuardTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		MatchingActivationGuardTest.decide_enabled_proceeds();
		MatchingActivationGuardTest.decide_disabled_activateThenRecheck();
		MatchingActivationGuardTest.decide_unknownState_activateThenRecheck();
		MatchingActivationGuardTest.decide_enabledButNoMetadataset_activateThenRecheck();
		MatchingActivationGuardTest.activateIfNeeded_alreadyEnabled_statusOnly_noActivateCall();
		MatchingActivationGuardTest.activateIfNeeded_freshlyGeneratedConfig_neverPublishedNorMetadataset_selfHealsAnyway();
		MatchingActivationGuardTest.activateIfNeeded_stillNotEnabledAfterActivate_refusesWithRestartRemedy();
		MatchingActivationGuardTest.activateIfNeeded_enabledButMetadatasetMissing_selfHeals_noteNamesMetadatasetNotTrigger();
		MatchingActivationGuardTest.activateIfNeeded_enabledButMetadatasetStillMissingAfterActivate_refusesNamingMetadataset();
		MatchingActivationGuardTest.activateIfNeeded_firstStatusNotFound_justEnsuredTrue_saysPreviewWriteMayHaveFailed();
		MatchingActivationGuardTest.activateIfNeeded_firstStatusNotFound_justEnsuredFalse_saysRunConfigureFirst();
		MatchingActivationGuardTest.activateIfNeeded_activateItselfNotFound_refusesWithSameNotConfiguredWording();
		MatchingActivationGuardTest.activateIfNeeded_rereadNotFoundAfterSuccessfulActivate_alarmingWording();
		MatchingActivationGuardTest.markerRoundTrip_detectedAndStripped();
		MatchingActivationGuardTest.markerRoundTrip_untaggedMessageUnaffected();
		MatchingActivationGuardTest.addonIndexFailure_linkedFieldMessageDetected();
		MatchingActivationGuardTest.addonIndexFailure_detectedThroughCauseChain();
		MatchingActivationGuardTest.addonIndexFailure_unrelatedExceptionNotDetected();
		MatchingActivationGuardTest.translateAddonIndexFailure_exactWording();
		System.out.println("MatchingActivationGuardTest OK");
	}

	// ------------------------------------------------------------------------------------------------- decide()

	private static void decide_enabled_proceeds()
	{
		final MatchingActivationGuard.StatusSnapshot status = new MatchingActivationGuard.StatusSnapshot("ENABLED", true, true);
		MatchingActivationGuardTest.check("ENABLED proceeds", MatchingActivationGuard.decide(status) == MatchingActivationGuard.Verdict.PROCEED);
	}

	private static void decide_disabled_activateThenRecheck()
	{
		final MatchingActivationGuard.StatusSnapshot status = new MatchingActivationGuard.StatusSnapshot("DISABLED", true, true);
		MatchingActivationGuardTest.check("DISABLED (published+metadataset) self-heals",
			MatchingActivationGuard.decide(status) == MatchingActivationGuard.Verdict.ACTIVATE_THEN_RECHECK);
	}

	/**
	 * 2026-09-23 fix: a config {@code ebx.MatchingPreviewPolicy#ensureFresh} just generated is neither published
	 * nor metadataset-backed yet - {@link MatchingActivationGuard#decide} must STILL attempt the repair rather
	 * than refuse (the exact live incident on {@code Contact}, `docs/MATCHING.md` &sect;13.8).
	 */
	private static void decide_unknownState_activateThenRecheck()
	{
		final MatchingActivationGuard.StatusSnapshot freshlyGenerated = new MatchingActivationGuard.StatusSnapshot("DISABLED", false, false);
		MatchingActivationGuardTest.check("a freshly generated, never-published, no-metadataset config still self-heals",
			MatchingActivationGuard.decide(freshlyGenerated) == MatchingActivationGuard.Verdict.ACTIVATE_THEN_RECHECK);
		final MatchingActivationGuard.StatusSnapshot unknown = new MatchingActivationGuard.StatusSnapshot(null, false, false);
		MatchingActivationGuardTest.check("an unknown/unparsed state is never mistaken for ENABLED",
			MatchingActivationGuard.decide(unknown) == MatchingActivationGuard.Verdict.ACTIVATE_THEN_RECHECK);
	}

	/**
	 * The THIRD live incident, 2026-09-23 15:50 (class Javadoc &sect;29, `docs/MATCHING.md` &sect;29): a preview
	 * policy written correctly on {@code Contact} still answered {@code state:"ENABLED", metadatasetExists:false}
	 * - {@code decide} used to read {@code state} alone and returned {@code PROCEED}, so {@code simulateMatch}
	 * went straight to the add-on and failed on the missing {@code Linked_Field_State} field. {@code state ==
	 * ENABLED} must not be enough on its own any more.
	 */
	private static void decide_enabledButNoMetadataset_activateThenRecheck()
	{
		final MatchingActivationGuard.StatusSnapshot enabledNoMetadataset = new MatchingActivationGuard.StatusSnapshot("ENABLED", true, false);
		MatchingActivationGuardTest.check("ENABLED with no metadataset still self-heals, never PROCEED",
			MatchingActivationGuard.decide(enabledNoMetadataset) == MatchingActivationGuard.Verdict.ACTIVATE_THEN_RECHECK);
		MatchingActivationGuardTest.check("isReady itself agrees", !MatchingActivationGuard.isReady(enabledNoMetadataset));
	}

	// ------------------------------------------------------------------------------------------ activateIfNeeded()

	private static void activateIfNeeded_alreadyEnabled_statusOnly_noActivateCall() throws OperationException
	{
		final FakeStatusPort port = new FakeStatusPort();
		port.statusScript.add(new MatchingActivationGuard.StatusSnapshot("ENABLED", true, true));
		final String note = MatchingActivationGuard.activateIfNeeded(port, "/root/Contact", "urn:ebx:publication:Companies_Master", true);
		MatchingActivationGuardTest.check("no note when already enabled", note == null);
		MatchingActivationGuardTest.check("exactly one call: status", port.calls.equals(java.util.Collections.singletonList("status")));
	}

	/**
	 * The EXACT live incident, 2026-09-23 07:45 (class Javadoc, `docs/MATCHING.md` &sect;13.8): {@code Contact},
	 * plan {@code PROPOSED}, table never configured before this click - {@code ebx.MatchingPreviewPolicy#ensure}
	 * just generated a config that is DISABLED, unpublished, with no metadataset yet. This must self-heal, not
	 * refuse - the exact call sequence the task brief asks for: status, activate, status.
	 */
	private static void activateIfNeeded_freshlyGeneratedConfig_neverPublishedNorMetadataset_selfHealsAnyway() throws OperationException
	{
		final FakeStatusPort port = new FakeStatusPort();
		port.statusScript.add(new MatchingActivationGuard.StatusSnapshot("DISABLED", false, false));
		port.statusScript.add(new MatchingActivationGuard.StatusSnapshot("ENABLED", true, true));
		final String note = MatchingActivationGuard.activateIfNeeded(port, "/root/Contact", "urn:ebx:publication:Companies_Master", true);
		MatchingActivationGuardTest.check("a note is returned", note != null);
		MatchingActivationGuardTest.check("the note names both the trigger and the metadataset (both were missing)",
			note.contains("trigger") && note.contains("metadataset"));
		MatchingActivationGuardTest.check("exact sequence status, activate, status",
			port.calls.equals(java.util.Arrays.asList("status", "activate", "status")));
		MatchingActivationGuardTest.check("the same dataModel value was reused on every call, never re-resolved",
			port.dataModelsSeen.equals(java.util.Arrays.asList("urn:ebx:publication:Companies_Master", "urn:ebx:publication:Companies_Master",
				"urn:ebx:publication:Companies_Master")));
	}

	private static void activateIfNeeded_stillNotEnabledAfterActivate_refusesWithRestartRemedy()
	{
		final FakeStatusPort port = new FakeStatusPort();
		port.statusScript.add(new MatchingActivationGuard.StatusSnapshot("DISABLED", true, true));
		port.statusScript.add(new MatchingActivationGuard.StatusSnapshot("DISABLED", true, true));
		try
		{
			MatchingActivationGuard.activateIfNeeded(port, "/root/Company", null, false);
			throw new AssertionError("expected a refusal");
		}
		catch (final OperationException ex)
		{
			MatchingActivationGuardTest.check("carries the marker", MatchingActivationGuard.isActivationRefusal(ex.getMessage()));
			MatchingActivationGuardTest.check("names restart as the remedy", ex.getMessage().contains("restart"));
			MatchingActivationGuardTest.check("names activate_matching as already tried", ex.getMessage().contains("activate_matching"));
			MatchingActivationGuardTest.check("sequence is status, activate, status", port.calls.equals(java.util.Arrays.asList("status", "activate", "status")));
		}
	}

	/**
	 * The exact live incident, 2026-09-23 15:50 (class Javadoc &sect;29): {@code state:"ENABLED"} but {@code
	 * metadatasetExists:false} - self-heals like any other not-ready snapshot, and the "reactivated" note must
	 * say the METADATASET was the problem, not the trigger (the trigger was never missing here).
	 */
	private static void activateIfNeeded_enabledButMetadatasetMissing_selfHeals_noteNamesMetadatasetNotTrigger() throws OperationException
	{
		final FakeStatusPort port = new FakeStatusPort();
		port.statusScript.add(new MatchingActivationGuard.StatusSnapshot("ENABLED", true, false));
		port.statusScript.add(new MatchingActivationGuard.StatusSnapshot("ENABLED", true, true));
		final String note = MatchingActivationGuard.activateIfNeeded(port, "/root/Contact", "urn:ebx:publication:Companies_Master", true);
		MatchingActivationGuardTest.check("a note is returned", note != null);
		MatchingActivationGuardTest.check("the note names the metadataset", note.contains("metadataset"));
		MatchingActivationGuardTest.check("the note does not blame the trigger (it was already attached)", !note.contains("trigger was not attached"));
		MatchingActivationGuardTest.check("exact sequence status, activate, status",
			port.calls.equals(java.util.Arrays.asList("status", "activate", "status")));
	}

	/**
	 * Same starting point as above, but {@code activate_matching} does not fix it: the re-read still answers
	 * {@code metadatasetExists:false} (state now irrelevant to the wording - this is the "different failure than
	 * not enabled" branch of {@link MatchingActivationGuard#refusalMessage}).
	 */
	private static void activateIfNeeded_enabledButMetadatasetStillMissingAfterActivate_refusesNamingMetadataset()
	{
		final FakeStatusPort port = new FakeStatusPort();
		port.statusScript.add(new MatchingActivationGuard.StatusSnapshot("ENABLED", true, false));
		port.statusScript.add(new MatchingActivationGuard.StatusSnapshot("ENABLED", true, false));
		try
		{
			MatchingActivationGuard.activateIfNeeded(port, "/root/Contact", "urn:ebx:publication:Companies_Master", true);
			throw new AssertionError("expected a refusal");
		}
		catch (final OperationException ex)
		{
			MatchingActivationGuardTest.check("carries the marker", MatchingActivationGuard.isActivationRefusal(ex.getMessage()));
			MatchingActivationGuardTest.check("names the metadataset, not just \"not activated\"", ex.getMessage().contains("metadatasetExists=false"));
			MatchingActivationGuardTest.check("names state=ENABLED so a reader sees the trigger IS attached", ex.getMessage().contains("state=ENABLED"));
			MatchingActivationGuardTest.check("names activate_matching as already tried", ex.getMessage().contains("activate_matching"));
			MatchingActivationGuardTest.check("sequence is status, activate, status", port.calls.equals(java.util.Arrays.asList("status", "activate", "status")));
		}
	}

	private static void activateIfNeeded_firstStatusNotFound_justEnsuredTrue_saysPreviewWriteMayHaveFailed()
	{
		final FakeStatusPort port = new FakeStatusPort();
		port.statusScript.add(new MatchingActivationGuard.ConfigNotFoundException("CONFIG_NOT_FOUND"));
		try
		{
			MatchingActivationGuard.activateIfNeeded(port, "/root/Contact", "urn:ebx:publication:Companies_Master", true);
			throw new AssertionError("expected a refusal");
		}
		catch (final OperationException ex)
		{
			MatchingActivationGuardTest.check("carries the marker", MatchingActivationGuard.isActivationRefusal(ex.getMessage()));
			MatchingActivationGuardTest.check("says a preview policy was just written", ex.getMessage().contains("preview matching policy was just written"));
			MatchingActivationGuardTest.check("says the write may have failed", ex.getMessage().contains("may have failed silently"));
			MatchingActivationGuardTest.check("never calls activate on a config that was never found", port.calls.equals(java.util.Collections.singletonList("status")));
		}
	}

	private static void activateIfNeeded_firstStatusNotFound_justEnsuredFalse_saysRunConfigureFirst()
	{
		final FakeStatusPort port = new FakeStatusPort();
		port.statusScript.add(new MatchingActivationGuard.ConfigNotFoundException("CONFIG_NOT_FOUND"));
		try
		{
			MatchingActivationGuard.activateIfNeeded(port, "/root/Company", null, false);
			throw new AssertionError("expected a refusal");
		}
		catch (final OperationException ex)
		{
			MatchingActivationGuardTest.check("carries the marker", MatchingActivationGuard.isActivationRefusal(ex.getMessage()));
			MatchingActivationGuardTest.check("never mentions a preview write for the applied path", !ex.getMessage().contains("preview matching policy"));
			MatchingActivationGuardTest.check("points at matching/configure", ex.getMessage().contains("matching/configure"));
		}
	}

	private static void activateIfNeeded_activateItselfNotFound_refusesWithSameNotConfiguredWording()
	{
		final FakeStatusPort port = new FakeStatusPort();
		port.statusScript.add(new MatchingActivationGuard.StatusSnapshot("DISABLED", true, true));
		port.activateScript.add(new MatchingActivationGuard.ConfigNotFoundException("CONFIG_NOT_FOUND"));
		try
		{
			MatchingActivationGuard.activateIfNeeded(port, "/root/Contact", "urn:ebx:publication:Companies_Master", true);
			throw new AssertionError("expected a refusal");
		}
		catch (final OperationException ex)
		{
			MatchingActivationGuardTest.check("carries the marker", MatchingActivationGuard.isActivationRefusal(ex.getMessage()));
			MatchingActivationGuardTest.check("sequence is status, activate (never a third call)", port.calls.equals(java.util.Arrays.asList("status", "activate")));
		}
	}

	private static void activateIfNeeded_rereadNotFoundAfterSuccessfulActivate_alarmingWording()
	{
		final FakeStatusPort port = new FakeStatusPort();
		port.statusScript.add(new MatchingActivationGuard.StatusSnapshot("DISABLED", true, true));
		port.statusScript.add(new MatchingActivationGuard.ConfigNotFoundException("CONFIG_NOT_FOUND"));
		try
		{
			MatchingActivationGuard.activateIfNeeded(port, "/root/Contact", null, false);
			throw new AssertionError("expected a refusal");
		}
		catch (final OperationException ex)
		{
			MatchingActivationGuardTest.check("carries the marker", MatchingActivationGuard.isActivationRefusal(ex.getMessage()));
			MatchingActivationGuardTest.check("says activate reported success", ex.getMessage().contains("reported success"));
			MatchingActivationGuardTest.check("says the re-read could not find it",
				ex.getMessage().contains("re-read") || ex.getMessage().contains("read right after"));
			MatchingActivationGuardTest.check("sequence is status, activate, status", port.calls.equals(java.util.Arrays.asList("status", "activate", "status")));
		}
	}

	// ------------------------------------------------------------------------------------------------- marker

	private static void markerRoundTrip_detectedAndStripped()
	{
		final String tagged = MatchingActivationGuard.REFUSAL_MARKER + "matching for /root/Company is not activated";
		MatchingActivationGuardTest.check("detected", MatchingActivationGuard.isActivationRefusal(tagged));
		final String stripped = MatchingActivationGuard.stripMarker(tagged);
		MatchingActivationGuardTest.check("stripped does not carry the marker", !stripped.startsWith(MatchingActivationGuard.REFUSAL_MARKER));
		MatchingActivationGuardTest.check("stripped keeps the real text", stripped.equals("matching for /root/Company is not activated"));
	}

	private static void markerRoundTrip_untaggedMessageUnaffected()
	{
		MatchingActivationGuardTest.check("an ordinary message is not mistaken for this guard's own refusal",
			!MatchingActivationGuard.isActivationRefusal("validate_matching_config reported errors"));
		MatchingActivationGuardTest.check("null is not mistaken for a refusal", !MatchingActivationGuard.isActivationRefusal(null));
		MatchingActivationGuardTest.check("stripMarker on an untagged message returns it unchanged",
			"validate_matching_config reported errors".equals(MatchingActivationGuard.stripMarker("validate_matching_config reported errors")));
		MatchingActivationGuardTest.check("stripMarker on null returns null", MatchingActivationGuard.stripMarker(null) == null);
	}

	// -------------------------------------------------------------------------------------- add-on exception guard

	/** The EXACT message measured live, both incidents (class Javadoc): {@code IllegalArgumentException: field [Linked_Field_State] not found; input fields are: [...]}. */
	private static void addonIndexFailure_linkedFieldMessageDetected()
	{
		final RuntimeException ex = new IllegalArgumentException("field [Linked_Field_State] not found; input fields are: [id, sourceSystem]");
		MatchingActivationGuardTest.check("detected by message content", MatchingActivationGuard.isAddonIndexFailure(ex));
	}

	/** The add-on may wrap its own internal failure before it reaches this module's caller - the walk must not stop at the outermost exception. */
	private static void addonIndexFailure_detectedThroughCauseChain()
	{
		final RuntimeException cause = new IllegalArgumentException("field [Linked_Field_State] not found");
		final RuntimeException wrapper = new IllegalStateException("matching/run failed", cause);
		MatchingActivationGuardTest.check("detected through a wrapping exception's cause", MatchingActivationGuard.isAddonIndexFailure(wrapper));
	}

	/** {@code InvalidParameterException}/{@code InvalidStateException} (the add-on's OWN typed refusals, already handled by {@code MameMatchingApi}'s existing catches) and any other unrelated failure must never be mistaken for this specific defect. */
	private static void addonIndexFailure_unrelatedExceptionNotDetected()
	{
		MatchingActivationGuardTest.check("an unrelated NullPointerException is not mistaken for the add-on index defect",
			!MatchingActivationGuard.isAddonIndexFailure(new NullPointerException("some other field is null")));
		MatchingActivationGuardTest.check("a message merely containing \"field\" without the exact marker is not mistaken for it",
			!MatchingActivationGuard.isAddonIndexFailure(new IllegalArgumentException("field sourceSystem is mandatory")));
	}

	/** The original task brief's own exact wording, {@code pTablePath}/the raw message substituted in. */
	private static void translateAddonIndexFailure_exactWording()
	{
		final RuntimeException ex = new IllegalArgumentException("field [Linked_Field_State] not found; input fields are: [id, sourceSystem]");
		final OperationException translated = MatchingActivationGuard.translateAddonIndexFailure("/root/Company", ex);
		final String message = translated.getMessage();
		MatchingActivationGuardTest.check("names the table", message.contains("/root/Company"));
		MatchingActivationGuardTest.check("names the missing field", message.contains("Linked_Field_State"));
		MatchingActivationGuardTest.check("names the remedy (activate_matching)", message.contains("activate_matching"));
		MatchingActivationGuardTest.check("names the remedy (restart)", message.contains("restart"));
		MatchingActivationGuardTest.check("carries the raw exception message", message.contains("field [Linked_Field_State] not found"));
		MatchingActivationGuardTest.check("never carries this guard's own refusal marker (a different failure family)",
			!MatchingActivationGuard.isActivationRefusal(message));
	}

	// ------------------------------------------------------------------------------------------------ fake port

	/**
	 * Hand-written {@link MatchingActivationGuard.StatusPort} - no {@code McpClient} involved at all (class
	 * Javadoc). Scripted responses are consumed in call order; every call (and the {@code dataModel} it was
	 * given) is recorded.
	 */
	private static final class FakeStatusPort implements MatchingActivationGuard.StatusPort
	{
		final List<String> calls = new ArrayList<String>();
		final List<String> dataModelsSeen = new ArrayList<String>();
		final Deque<Object> statusScript = new ArrayDeque<Object>();
		final Deque<Object> activateScript = new ArrayDeque<Object>();

		@Override
		public MatchingActivationGuard.StatusSnapshot readStatus(final String pTablePath, final String pDataModelOrNull) throws OperationException
		{
			this.calls.add("status");
			this.dataModelsSeen.add(pDataModelOrNull);
			final Object next = this.statusScript.poll();
			if (next instanceof MatchingActivationGuard.ConfigNotFoundException)
			{
				throw (MatchingActivationGuard.ConfigNotFoundException) next;
			}
			return (MatchingActivationGuard.StatusSnapshot) next;
		}

		@Override
		public void activate(final String pTablePath, final String pDataModelOrNull) throws OperationException
		{
			this.calls.add("activate");
			this.dataModelsSeen.add(pDataModelOrNull);
			final Object next = this.activateScript.poll();
			if (next instanceof MatchingActivationGuard.ConfigNotFoundException)
			{
				throw (MatchingActivationGuard.ConfigNotFoundException) next;
			}
			// else: scripted success, nothing further to do (activate's own returned status is never read by
			// activateIfNeeded - class Javadoc, "RE-READ" discipline).
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
