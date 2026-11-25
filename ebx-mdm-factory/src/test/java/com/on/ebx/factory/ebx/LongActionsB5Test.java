package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryRight;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * JUnit-free: run the main, a failure throws. F2-102 and F2-107 (robustness audit 2026-10-23): the intake switch, the check before the build, the plan simulation, the bulk merge and the
 * pipeline launch are queued jobs - one {@code FactoryJob} row each, the request handed back at once (a fake launcher stands for the queue); the intake switch keeps a readable state and
 * resumes idempotently; the catalog scans of the deduced applications are read once per application, not once per render.
 */
public final class LongActionsB5Test
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		LongActionsB5Test.everyActionIsOneQueuedRowAndReturnsAtOnce();
		LongActionsB5Test.kindsAreRegisteredWithTheirRightAndLabelled();
		LongActionsB5Test.wizardNoLongerRunsThemInline();
		LongActionsB5Test.switchStateIsReadableAndResumeIdempotent();
		LongActionsB5Test.catalogScansAreCachedPerSource();
		System.out.println("LongActionsB5Test OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static final class FakeLauncher implements RunLauncher.Launcher
	{
		final List<String> kinds = new ArrayList<String>();
		final List<ObjectNode> params = new ArrayList<ObjectNode>();

		@Override
		public ObjectNode launch(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pKind, final ObjectNode pParams)
		{
			this.kinds.add(pKind);
			this.params.add(pParams);
			final ObjectNode out = LongActionsB5Test.MAPPER.createObjectNode();
			out.put("started", true);
			out.put("jobId", String.valueOf(this.kinds.size()));
			return out;
		}
	}

	private static void everyActionIsOneQueuedRowAndReturnsAtOnce() throws OperationException
	{
		final FakeLauncher fake = new FakeLauncher();
		RunLauncher.use(fake);
		try
		{
			final List<String[]> pairs = new ArrayList<String[]>();
			pairs.add(new String[] { "1", "2" });
			final long start = System.nanoTime();
			final ObjectNode[] answers = new ObjectNode[] { IntakeSwitchRun.queueOn(null, null, "P"), IntakeSwitchRun.queueOff(null, null, "P"),
				WizardActionRuns.startPreflight(null, null, "P", false, ""), WizardActionRuns.startSimulation(null, null, "P", "7", "Person", "{}"),
				WizardActionRuns.startBulkMerge(null, null, "P", "Person", pairs), WizardActionRuns.startPipeline(null, null, "P") };
			final long millis = (System.nanoTime() - start) / 1000000L;
			LongActionsB5Test.check(millis < 100, "the six actions hand the request back in under 100 ms (took " + millis + " ms)");
			LongActionsB5Test.check(fake.kinds.equals(Arrays.asList(IntakeSwitchRun.ON_STEP, IntakeSwitchRun.OFF_STEP, WizardActionRuns.PREFLIGHT_STEP, WizardActionRuns.SIMULATE_STEP,
				WizardActionRuns.BULK_MERGE_STEP, WizardActionRuns.PIPELINE_STEP)), "one job row, of its own kind, per action: " + fake.kinds);
			for (final ObjectNode answer : answers)
			{
				LongActionsB5Test.check(answer.path("started").asBoolean(false), "the answer says it started");
			}
			LongActionsB5Test.check(fake.params.get(0).path("installed").isArray() && fake.params.get(0).path("projectCode").asText("").equals("P"), "the switch-on starts with no installed application");
			LongActionsB5Test.check(fake.params.get(4).path("pairs").size() == 1 && "2".equals(fake.params.get(4).path("pairs").path(0).path(1).asText()), "the bulk merge carries its pairs");
			LongActionsB5Test.check("7".equals(fake.params.get(3).path("rowId").asText()), "the simulation names its plan");
		}
		finally
		{
			RunLauncher.reset();
		}
	}

	private static void kindsAreRegisteredWithTheirRightAndLabelled()
	{
		FactoryJobKinds.registerAll();
		final Object[][] kinds = { { IntakeSwitchRun.ON_STEP, FactoryJobs.LANDING, FactoryRight.DESIGN }, { IntakeSwitchRun.OFF_STEP, FactoryJobs.LANDING, FactoryRight.DESIGN },
			{ WizardActionRuns.PREFLIGHT_STEP, FactoryJobs.STAGING, FactoryRight.DESIGN }, { WizardActionRuns.SIMULATE_STEP, FactoryJobs.MATCHING, FactoryRight.DESIGN },
			{ WizardActionRuns.BULK_MERGE_STEP, FactoryJobs.MATCHING, FactoryRight.STEWARD }, { WizardActionRuns.PIPELINE_STEP, FactoryJobs.DESIGN, FactoryRight.DESIGN } };
		for (final Object[] kind : kinds)
		{
			final String name = (String) kind[0];
			LongActionsB5Test.check(kind[1].equals(FactoryJobQueue.resourceOf(name)), name + " holds " + kind[1]);
			LongActionsB5Test.check(FactoryJobQueue.rightOf(name) == kind[2], name + " is queued by " + kind[2]);
			for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
			{
				final ResourceBundle bundle = ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", locale);
				LongActionsB5Test.check(bundle.containsKey("wizard.jobs.kind." + name) && !bundle.getString("wizard.jobs.kind." + name).isEmpty(), name + " has a label in " + locale);
			}
		}
		for (final String key : new String[] { "wizard.intake.notice.switchOnQueued", "wizard.intake.notice.switchOffQueued", "wizard.intake.job.installing", "wizard.intake.job.interrupted",
			"wizard.intake.job.switchOnFailed", "wizard.intake.job.switchOffFailed", "wizard.intake.job.none", "wizard.step6.simulate.noResult" })
		{
			for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
			{
				LongActionsB5Test.check(ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", locale).containsKey(key), key + " exists in " + locale);
			}
		}
	}

	private static String read(final String pRelative) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		return new String(Files.readAllBytes(root.resolve("com/on/ebx/factory/" + pRelative)), StandardCharsets.UTF_8);
	}

	private static void wizardNoLongerRunsThemInline() throws IOException
	{
		final String wizard = LongActionsB5Test.read("ebx/ui/ProjectWizardUserService.java");
		for (final String inline : new String[] { "Intake.switchOn(", "Intake.switchOff(", "StagingPreflight.runAndStore(", "StagingPreflight.applyFix(", "MatchPlanSimulationRunner.simulate(",
			"PipelineWorkflowLauncher.launch(" })
		{
			LongActionsB5Test.check(!wizard.contains(inline), "no " + inline + " inside the wizard's request");
		}
		for (final String queued : new String[] { "IntakeSwitchRun.startOn(", "IntakeSwitchRun.startOff(", "WizardActionRuns.startPreflight(", "WizardActionRuns.startSimulation(",
			"WizardActionRuns.startBulkMerge(", "WizardActionRuns.startPipeline(" })
		{
			LongActionsB5Test.check(wizard.contains(queued), "the wizard queues with " + queued);
		}
		final int bulk = wizard.indexOf("private void bulkMergeMatchPairs(");
		LongActionsB5Test.check(!wizard.substring(bulk, wizard.indexOf("private static long masterLoadStartedAt", bulk)).contains("RecordLineageReader.decideSuspect("), "the bulk merge no longer decides pair by pair in the request");
		// F2-107: a display takes the action's snapshot instead of reading a second one, and the full read goes through one reader
		LongActionsB5Test.check(wizard.contains("this.takeRequestSnapshot()") && wizard.split("WizardData\\.readSnapshot\\(ProjectWizardUserService\\.this\\.view\\(\\)").length == 2,
			"model() takes the request's snapshot; the full reader is declared once");
		final String actions = wizard.substring(wizard.indexOf("private void handleAction("));
		LongActionsB5Test.check(actions.contains("this.requestSnapshots.clear()"), "an action starts from no kept snapshot");
		final String kinds = LongActionsB5Test.read("ebx/FactoryJobKinds.java");
		for (final String kind : new String[] { "IntakeSwitchRun.ON_STEP", "IntakeSwitchRun.OFF_STEP", "WizardActionRuns.PREFLIGHT_STEP", "WizardActionRuns.SIMULATE_STEP",
			"WizardActionRuns.BULK_MERGE_STEP", "WizardActionRuns.PIPELINE_STEP" })
		{
			LongActionsB5Test.check(kinds.contains("FactoryJobQueue.register(" + kind + ","), kind + " is registered");
		}
	}

	private static void switchStateIsReadableAndResumeIdempotent() throws IOException
	{
		LongActionsB5Test.check(IntakeSwitchRun.missing(Arrays.asList("SAP", "CRM", "WEB"), Arrays.asList("SAP")).equals(Arrays.asList("CRM", "WEB")), "what is not installed yet, in order");
		LongActionsB5Test.check(IntakeSwitchRun.missing(Arrays.asList("SAP"), Arrays.asList("SAP")).isEmpty(), "nothing missing once every application is done");
		final com.fasterxml.jackson.databind.node.ArrayNode installed = LongActionsB5Test.MAPPER.createArrayNode().add("SAP").add("CRM");
		LongActionsB5Test.check(IntakeSwitchRun.installedNames(installed).equals(Arrays.asList("SAP", "CRM")) && IntakeSwitchRun.installedNames(null).isEmpty(), "the installed list is read back");
		// the long half re-checks the state first (already ON answers, PAUSED refuses), and the state becomes ON only AFTER the installation: a cut leaves it OFF and a second run goes on
		final String intake = LongActionsB5Test.read("ebx/Intake.java");
		final int run = intake.indexOf("static IntakeStatus runSwitchOn(");
		final String body = intake.substring(run, intake.indexOf("ON -&gt; PAUSED with the reason", run));
		final int check = body.indexOf("FactoryPaths.Project.INTAKE_ON.equals(state)");
		final int install = body.indexOf("IntakeTriggerInstaller.install(");
		final int setOn = body.indexOf("FactoryPaths.Project.INTAKE_STATE, FactoryPaths.Project.INTAKE_ON");
		LongActionsB5Test.check(check > 0 && check < install && install < setOn, "runSwitchOn: state check, then the installation, then ON - in that order");
		LongActionsB5Test.check(body.contains("pEnabledBy") && !body.contains("pSession.getUserReference().getUserId()"), "ON is recorded for the person who asked, never the job's session");
		final String installer = LongActionsB5Test.read("ebx/IntakeTriggerInstaller.java");
		LongActionsB5Test.check(installer.contains("pProgress.sourceInstalled(source.sourceSystem, installed)"), "the installer reports each application once it is published");
	}

	private static void catalogScansAreCachedPerSource()
	{
		DeducedScanCatalog.clearCache();
		final CatalogReader.Reader reader = CatalogReader.Reader.system("test");
		final int[] loads = new int[1];
		final DeducedScanCatalog.Loader<String> loader = new DeducedScanCatalog.Loader<String>()
		{
			@Override
			public String load()
			{
				loads[0]++;
				return "answer" + loads[0];
			}
		};
		final long t0 = 1000000L;
		final String first = DeducedScanCatalog.cached("counts|u|S1", "100/200", t0, reader, loader);
		LongActionsB5Test.check("answer1".equals(first) && loads[0] == 1, "the first render scans");
		LongActionsB5Test.check(first == DeducedScanCatalog.cached("counts|u|S1", "100/200", t0 + 3000, reader, loader) && loads[0] == 1, "the next render, 3 s later, scans nothing");
		LongActionsB5Test.check("answer2".equals(DeducedScanCatalog.cached("counts|u|S2", "100/200", t0 + 3000, reader, loader)) && loads[0] == 2, "another application is its own entry");
		LongActionsB5Test.check("answer3".equals(DeducedScanCatalog.cached("counts|u|S1", "101/200", t0 + 3000, reader, loader)) && loads[0] == 3, "a write to the Asset table (new date) reads again");
		LongActionsB5Test.check("answer4".equals(DeducedScanCatalog.cached("counts|u|S1", "101/200", t0 + 3000 + DeducedScanCatalog.CACHE_MILLIS + 1, reader, loader)) && loads[0] == 4,
			"an entry older than the limit reads again");
		LongActionsB5Test.check("answer5".equals(DeducedScanCatalog.cached("counts|v|S1", "101/200", t0 + 3000 + DeducedScanCatalog.CACHE_MILLIS + 2, reader, loader)) && loads[0] == 5,
			"another person never gets someone else's entry");
		final int before = loads[0];
		DeducedScanCatalog.cached("counts|u|S3", null, t0, reader, loader);
		DeducedScanCatalog.cached("counts|u|S3", null, t0, reader, loader);
		LongActionsB5Test.check(loads[0] == before + 2, "no table date, no cache");
		// a read that found rows hidden from the person marks the reader restricted: the hit replays it
		final CatalogReader.Reader person = CatalogReader.Reader.system("restricted");
		final DeducedScanCatalog.Loader<String> hiding = new DeducedScanCatalog.Loader<String>()
		{
			@Override
			public String load()
			{
				person.markRestricted();
				return "none";
			}
		};
		DeducedScanCatalog.cached("pending|u|S9", "1/1", t0, person, hiding);
		final CatalogReader.Reader next = CatalogReader.Reader.system("next render");
		DeducedScanCatalog.cached("pending|u|S9", "1/1", t0 + 1, next, hiding);
		LongActionsB5Test.check(next.restricted(), "a cache hit still tells the reader that rows were hidden");
		DeducedScanCatalog.clearCache();
	}
}
