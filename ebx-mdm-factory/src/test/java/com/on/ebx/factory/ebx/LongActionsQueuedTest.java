package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws. Audit F-34: the wizard's accept-then-apply, Remove and "Propose another" are queued
 * jobs, not work done inside the request; the job keeps the "nothing new" handling the wizard had.
 */
public final class LongActionsQueuedTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws IOException
	{
		LongActionsQueuedTest.removeKindFollowsTheZone();
		LongActionsQueuedTest.reproposeMissReasonIsUnchanged();
		LongActionsQueuedTest.wizardNoLongerRunsThemInline();
		System.out.println("LongActionsQueuedTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static void removeKindFollowsTheZone()
	{
		LongActionsQueuedTest.check(CapabilityApplyRun.REMOVE_STAGING.equals(CapabilityApplyRun.removeKindFor("STAGING")), "staging");
		LongActionsQueuedTest.check(CapabilityApplyRun.REMOVE_MASTER.equals(CapabilityApplyRun.removeKindFor("MASTER")), "master");
		LongActionsQueuedTest.check(CapabilityApplyRun.removeKindFor("LANDING") == null && CapabilityApplyRun.removeKindFor(null) == null, "no other zone");
	}

	private static void reproposeMissReasonIsUnchanged()
	{
		final ObjectNode written = LongActionsQueuedTest.MAPPER.createObjectNode();
		written.putArray("written").add("Personne");
		LongActionsQueuedTest.check(MatchingSuggestLlmRun.contains(written.path("written"), "Personne"), "written names the entity");
		LongActionsQueuedTest.check(!MatchingSuggestLlmRun.contains(written.path("refreshed"), "Personne"), "a missing array names nothing");
		final ObjectNode noted = LongActionsQueuedTest.MAPPER.createObjectNode();
		noted.putArray("notes").add("Societe: skipped").add("Personne: same plan as before");
		LongActionsQueuedTest.check("Personne: same plan as before".equals(MatchingSuggestLlmRun.reproposeMissReason(noted, "Personne")), "the entity's note");
		final ObjectNode silent = LongActionsQueuedTest.MAPPER.createObjectNode();
		silent.put("status", "NO_OUTPUT");
		LongActionsQueuedTest.check(MatchingSuggestLlmRun.reproposeMissReason(silent, "Personne").startsWith("the model's answer never proposed"), "no output");
		final ObjectNode other = LongActionsQueuedTest.MAPPER.createObjectNode();
		other.put("status", "DONE");
		LongActionsQueuedTest.check(MatchingSuggestLlmRun.reproposeMissReason(other, "Personne").contains("(run status: DONE)"), "the run status");
	}

	private static void wizardNoLongerRunsThemInline() throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		final String wizard = new String(Files.readAllBytes(root.resolve("com/on/ebx/factory/ebx/ui/ProjectWizardUserService.java")), StandardCharsets.UTF_8);
		LongActionsQueuedTest.check(!wizard.contains("CapabilityDispatcher.apply(") && !wizard.contains("CapabilityDispatcher.remove("), "no apply/remove inside the request");
		LongActionsQueuedTest.check(!wizard.contains("MatchPlanLlmSuggester.suggest("), "no language-model call inside the request for Propose another");
		LongActionsQueuedTest.check(wizard.contains("CapabilityApplyRun.removeKindFor(") && wizard.contains("MatchingSuggestLlmRun.startRepropose("), "queued instead");
		final String kinds = new String(Files.readAllBytes(root.resolve("com/on/ebx/factory/ebx/FactoryJobKinds.java")), StandardCharsets.UTF_8);
		LongActionsQueuedTest.check(kinds.contains("CapabilityApplyRun.REMOVE_STAGING, FactoryJobs.STAGING") && kinds.contains("CapabilityApplyRun.REMOVE_MASTER, FactoryJobs.MASTER"),
			"the remove kinds hold their zone's resource");
	}
}
