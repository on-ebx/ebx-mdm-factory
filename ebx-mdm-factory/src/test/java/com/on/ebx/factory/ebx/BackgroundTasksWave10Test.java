package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.landing.KeyStudyReport;

/**
 * JUnit-free: run the main, a failure throws. Wave 10 (audit F-34, F-49): the long actions that still ran in the wizard's request are queued jobs, a request
 * identical to a RUNNING job folds into it (except where it is raised by a change), and every kind has its label in both languages.
 */
public final class BackgroundTasksWave10Test
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** Every kind this wave adds, with the resource it holds. */
	private static final String[][] NEW_KINDS = {
		{ SuggestRulesRun.STAGING_STEP, FactoryJobs.DESIGN }, { SuggestRulesRun.QUALITY_STEP, FactoryJobs.DESIGN }, { SuggestRulesRun.MATCHING_STEP, FactoryJobs.DESIGN },
		{ DistributionSetupRun.CONFIGURE_STEP, FactoryJobs.DISTRIBUTION }, { DistributionSetupRun.TRIGGERS_STEP, FactoryJobs.MASTER },
		{ DistributionSetupRun.PRODUCT_STEP, FactoryJobs.DISTRIBUTION }, { KeyStudyRun.STEP, FactoryJobs.LANDING },
		{ CapabilityApplyRun.APPLY_ALL_STAGING, FactoryJobs.STAGING }, { CapabilityApplyRun.APPLY_ALL_MASTER, FactoryJobs.MASTER } };

	public static void main(final String[] pArgs) throws IOException
	{
		BackgroundTasksWave10Test.foldingIntoARunningJob();
		BackgroundTasksWave10Test.newKindsAreRegisteredAndLabelled();
		BackgroundTasksWave10Test.applyAllGroupsByZone();
		BackgroundTasksWave10Test.productVerdict();
		BackgroundTasksWave10Test.keyStudyHandOff();
		BackgroundTasksWave10Test.statusCarriesTheAskingPerson();
		BackgroundTasksWave10Test.actionsNoLongerRunInTheRequest();
		System.out.println("BackgroundTasksWave10Test OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static void foldingIntoARunningJob()
	{
		BackgroundTasksWave10Test.check(FactoryJobQueue.absorbs(FactoryJobQueue.QUEUED, false) && FactoryJobQueue.absorbs(FactoryJobQueue.QUEUED, true), "a QUEUED job always absorbs an identical request");
		BackgroundTasksWave10Test.check(FactoryJobQueue.absorbs(FactoryJobQueue.RUNNING, true), "a RUNNING job absorbs it too (audit F-49)");
		BackgroundTasksWave10Test.check(!FactoryJobQueue.absorbs(FactoryJobQueue.RUNNING, false), "unless the kind opts out");
		for (final String over : new String[] { FactoryJobQueue.DONE, FactoryJobQueue.FAILED, FactoryJobQueue.CANCELLED, "INTERRUPTED", null })
		{
			BackgroundTasksWave10Test.check(!FactoryJobQueue.absorbs(over, true) && !FactoryJobQueue.absorbs(over, false), "a finished job (" + over + ") never absorbs a request");
		}
		BackgroundTasksWave10Test.check(FactoryJobQueue.sameParams("{\"a\":1}", "{\"a\":1}") && !FactoryJobQueue.sameParams("{\"a\":1}", "{\"a\":2}"), "same parameters");
		BackgroundTasksWave10Test.check(FactoryJobQueue.sameParams(null, "{}") && FactoryJobQueue.sameParams("{}", "{}") && !FactoryJobQueue.sameParams(null, "{\"a\":1}"), "no stored parameters read as {}");
		FactoryJobKinds.registerAll();
		BackgroundTasksWave10Test.check(FactoryJobQueue.foldsIntoRunning(StagingLoadRun.STEP) && FactoryJobQueue.foldsIntoRunning(LandingRun.STEP)
			&& FactoryJobQueue.foldsIntoRunning(MatchingRunRun.STEP) && FactoryJobQueue.foldsIntoRunning(CapabilityApplyRun.APPLY_STAGING), "the usual kinds fold into a running job");
		BackgroundTasksWave10Test.check(!FactoryJobQueue.foldsIntoRunning(MatchingResultsRun.STEP),
			"the matching-results refresh is raised by decisions: one made while a refresh runs needs its own run");
		BackgroundTasksWave10Test.check(!FactoryJobQueue.foldsIntoRunning("NO_SUCH_KIND") && !FactoryJobQueue.foldsIntoRunning(null), "an unknown kind folds into nothing");
		// a request made after Stop is a new run, not a duplicate of the job on its way out
		final FactoryJobs.Handle handle = FactoryJobs.claim("W10P", FactoryJobs.STAGING, StagingLoadRun.STEP, "alice");
		BackgroundTasksWave10Test.check(handle != null, "claimed");
		try
		{
			BackgroundTasksWave10Test.check(!FactoryJobQueue.stopping("W10P", FactoryJobs.STAGING, StagingLoadRun.STEP), "running normally: not stopping");
			handle.cancel("stopped by alice");
			BackgroundTasksWave10Test.check(FactoryJobQueue.stopping("W10P", FactoryJobs.STAGING, StagingLoadRun.STEP), "asked to stop: a new request does not fold into it");
			BackgroundTasksWave10Test.check(!FactoryJobQueue.stopping("W10P", FactoryJobs.STAGING, MasterBuildRun.STEP) && !FactoryJobQueue.stopping("W10Q", FactoryJobs.STAGING, StagingLoadRun.STEP),
				"only that kind of that project");
		}
		finally
		{
			FactoryJobs.release(handle);
		}
		BackgroundTasksWave10Test.check(!FactoryJobQueue.stopping("W10P", FactoryJobs.STAGING, StagingLoadRun.STEP), "released: nothing live");
	}

	private static void newKindsAreRegisteredAndLabelled()
	{
		FactoryJobKinds.registerAll();
		for (final String[] kind : BackgroundTasksWave10Test.NEW_KINDS)
		{
			BackgroundTasksWave10Test.check(kind[1].equals(FactoryJobQueue.resourceOf(kind[0])), kind[0] + " holds " + kind[1] + ", not " + FactoryJobQueue.resourceOf(kind[0]));
			BackgroundTasksWave10Test.check(FactoryJobQueue.rightOf(kind[0]) == com.on.ebx.factory.core.FactoryRight.DESIGN, kind[0] + " is queued by the DESIGN right");
			for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
			{
				final ResourceBundle bundle = ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", locale);
				final String label = bundle.containsKey("wizard.jobs.kind." + kind[0]) ? bundle.getString("wizard.jobs.kind." + kind[0]) : "";
				BackgroundTasksWave10Test.check(!label.isEmpty(), kind[0] + " has a label in " + locale);
			}
			BackgroundTasksWave10Test.check(com.on.ebx.factory.wizard.RecentTasks.stepOf(kind[0]) != com.on.ebx.factory.wizard.WizardRules.STEP_SUMMARY, kind[0] + " belongs to a step");
		}
		BackgroundTasksWave10Test.check(com.on.ebx.factory.wizard.RecentTasks.stepOf(SuggestRulesRun.QUALITY_STEP) == com.on.ebx.factory.wizard.WizardRules.STEP_STAGING, "quality rules are step 4");
		BackgroundTasksWave10Test.check(com.on.ebx.factory.wizard.RecentTasks.stepOf(DistributionSetupRun.CONFIGURE_STEP) == com.on.ebx.factory.wizard.WizardRules.STEP_DISTRIBUTION, "distribution set-up is step 7");
		BackgroundTasksWave10Test.check(com.on.ebx.factory.wizard.RecentTasks.stepOf(KeyStudyRun.STEP) == com.on.ebx.factory.wizard.WizardRules.STEP_LANDING, "the key study is step 3");
		BackgroundTasksWave10Test.check(!com.on.ebx.factory.wizard.RecentTasks.rerunnable(SuggestRulesRun.STAGING_STEP) && !com.on.ebx.factory.wizard.RecentTasks.rerunnable(DistributionSetupRun.PRODUCT_STEP),
			"none of the new kinds is offered as Run again");
	}

	private static void applyAllGroupsByZone()
	{
		final Map<String, String> zones = new HashMap<String, String>();
		zones.put("a", "STAGING");
		zones.put("b", "MASTER");
		zones.put("c", "STAGING");
		zones.put("d", "");
		final Map<String, List<String>> groups = CapabilityApplyRun.groupByZone(Arrays.asList("a", "b", "c", "d", "e"), zones);
		BackgroundTasksWave10Test.check(groups.get("STAGING").equals(Arrays.asList("a", "c")), "staging ids keep their order");
		BackgroundTasksWave10Test.check(groups.get("MASTER").equals(Arrays.asList("b")), "master ids");
		BackgroundTasksWave10Test.check(groups.get("").equals(Arrays.asList("d", "e")), "an empty or unknown zone goes under the empty key");
		BackgroundTasksWave10Test.check(CapabilityApplyRun.APPLY_ALL_STAGING.equals(CapabilityApplyRun.applyAllKindFor("STAGING")) && CapabilityApplyRun.APPLY_ALL_MASTER.equals(CapabilityApplyRun.applyAllKindFor("MASTER"))
			&& CapabilityApplyRun.applyAllKindFor("") == null && CapabilityApplyRun.applyAllKindFor(null) == null, "apply-all kind per zone, none for an unknown one");
		BackgroundTasksWave10Test.check(CapabilityApplyRun.groupByZone(new ArrayList<String>(), zones).isEmpty(), "nothing selected, nothing grouped");
	}

	private static void productVerdict()
	{
		final ObjectNode written = BackgroundTasksWave10Test.MAPPER.createObjectNode();
		written.putObject("write").put("success", true);
		final ObjectNode refused = BackgroundTasksWave10Test.MAPPER.createObjectNode();
		refused.putObject("write").put("success", false).put("message", "The plan has 1 problem(s): nothing was written.");
		final ObjectNode flat = BackgroundTasksWave10Test.MAPPER.createObjectNode();
		flat.put("success", true);
		BackgroundTasksWave10Test.check(DistributionSetupRun.created(written) && !DistributionSetupRun.created(refused) && DistributionSetupRun.created(flat)
			&& !DistributionSetupRun.created(BackgroundTasksWave10Test.MAPPER.createObjectNode()), "the product is created only when the write says so");
	}

	private static void keyStudyHandOff()
	{
		final KeyStudyReport report = new KeyStudyReport();
		report.tableId = "7";
		final long t0 = 1000000L;
		KeyStudyRun.keep("P1", "alice", KeyStudyRun.SLOT_PANEL, "7", report, t0);
		BackgroundTasksWave10Test.check(KeyStudyRun.take("P1", "bob", KeyStudyRun.SLOT_PANEL, "7", t0 + 1) == null, "another person never receives it");
		BackgroundTasksWave10Test.check(KeyStudyRun.take("P1", "alice", KeyStudyRun.SLOT_CHOOSER, "7", t0 + 1) == null, "nor another screen");
		BackgroundTasksWave10Test.check(KeyStudyRun.take("P2", "alice", KeyStudyRun.SLOT_PANEL, "7", t0 + 1) == null, "nor another project");
		BackgroundTasksWave10Test.check(KeyStudyRun.take("P1", "alice", KeyStudyRun.SLOT_PANEL, "7", t0 + 1) == report, "the person who asked takes it");
		BackgroundTasksWave10Test.check(KeyStudyRun.take("P1", "alice", KeyStudyRun.SLOT_PANEL, "7", t0 + 2) == null, "once");
		KeyStudyRun.keep("P1", "alice", KeyStudyRun.SLOT_PANEL, "8", report, t0);
		BackgroundTasksWave10Test.check(KeyStudyRun.take("P1", "alice", KeyStudyRun.SLOT_PANEL, "8", t0 + KeyStudyRun.KEEP_MILLIS + 1) == null, "a report nobody took expires");
		for (int i = 0; i < KeyStudyRun.KEEP_MAX + 20; i++)
		{
			KeyStudyRun.keep("P1", "alice", KeyStudyRun.SLOT_PANEL, "t" + i, report, t0 + i);
		}
		BackgroundTasksWave10Test.check(KeyStudyRun.take("P1", "alice", KeyStudyRun.SLOT_PANEL, "t0", t0 + 1000) == null, "the oldest are dropped past the cap");
		BackgroundTasksWave10Test.check(KeyStudyRun.take("P1", "alice", KeyStudyRun.SLOT_PANEL, "t" + (KeyStudyRun.KEEP_MAX + 19), t0 + 1000) == report, "the newest is kept");
		BackgroundTasksWave10Test.check(!KeyStudyRun.key("P1", null, "panel", "7").equals(KeyStudyRun.key("P1", "x", "panel", "7")), "a missing user is its own key");
	}

	private static void statusCarriesTheAskingPerson() throws IOException
	{
		final String status = BackgroundTasksWave10Test.read("com/on/ebx/factory/ebx/FactoryJobStatus.java");
		BackgroundTasksWave10Test.check(status.contains("out.put(\"me\", me)"), "the jobs answer names the person asking, for the end-of-task notice");
		final String resource = BackgroundTasksWave10Test.read("com/on/ebx/factory/rest/FactoryResource.java");
		BackgroundTasksWave10Test.check(resource.contains("FactoryJobStatus.of(dataset, project.getOccurrencePrimaryKey().format(), this.sessionContext.getSession(), locale)"), "the route passes the session (and the language asked with ?lang)");
	}

	/** The actions of the audit's list no longer call their engine inside the request; the review screen's apply/remove/measure/propose-another are queued. */
	private static void actionsNoLongerRunInTheRequest() throws IOException
	{
		final String wizard = BackgroundTasksWave10Test.read("com/on/ebx/factory/ebx/ui/ProjectWizardUserService.java");
		for (final String call : new String[] { "StagingSuggester.suggest(", "QualitySuggester.suggest(", "MatchPlanSuggester.suggest(", "StagingLlmSuggester.suggest(",
			"DistributionConfigurator.configure(", "DistributionTriggerInstaller.install(", "DataProductPublication.create(" })
		{
			BackgroundTasksWave10Test.check(!wizard.contains(call), "the wizard no longer calls " + call + " inside the request");
		}
		for (final String queued : new String[] { "SuggestRulesRun.startStaging(", "SuggestRulesRun.startQuality(", "SuggestRulesRun.startMatching(", "DistributionSetupRun.startConfigure(",
			"DistributionSetupRun.startTriggers(", "DistributionSetupRun.startProduct(", "KeyStudyRun.start(", "KeyStudyRun.take(" })
		{
			BackgroundTasksWave10Test.check(wizard.contains(queued), "the wizard queues through " + queued);
		}
		final String review = BackgroundTasksWave10Test.read("com/on/ebx/factory/ebx/ui/ProposalReviewUserService.java");
		for (final String call : new String[] { "CapabilityDispatcher.apply(", "CapabilityDispatcher.remove(", "CapabilityDispatcher.applyAll(", "CapabilityApplier.measure(", "MatchPlanLlmSuggester.suggest(" })
		{
			BackgroundTasksWave10Test.check(!review.contains(call), "the review screen no longer calls " + call + " inside the request");
		}
		for (final String queued : new String[] { "CapabilityApplyRun.applyKindFor(", "CapabilityApplyRun.removeKindFor(", "CapabilityApplyRun.measureKindFor(", "CapabilityApplyRun.startApplyAll(",
			"MatchingSuggestLlmRun.startRepropose(" })
		{
			BackgroundTasksWave10Test.check(review.contains(queued), "the review screen queues through " + queued);
		}
		final String kinds = BackgroundTasksWave10Test.read("com/on/ebx/factory/ebx/FactoryJobKinds.java");
		BackgroundTasksWave10Test.check(kinds.contains("FactoryJobQueue.doNotFoldIntoRunning(MatchingResultsRun.STEP)"), "the results refresh opts out of folding into a running job");
	}

	private static String read(final String pRelative) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		return new String(Files.readAllBytes(root.resolve(pRelative)), StandardCharsets.UTF_8);
	}
}
