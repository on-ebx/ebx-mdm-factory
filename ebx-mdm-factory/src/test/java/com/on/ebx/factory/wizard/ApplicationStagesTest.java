package com.on.ebx.factory.wizard;

/**
 * JUnit-free, like {@code SourceOnboardingTest}: run the main, a failure throws. Exercises {@link
 * ApplicationStages#from} on every {@link SourceOnboarding.Diagnosis} shape {@code SourceOnboardingTest}
 * already covers (task brief SOURCES ss2: "test it on every diagnosis shape") plus the all-DONE/ready shape,
 * so every {@link ApplicationStages.StageState} and every grouping rule (BLOCKED beats WAITING beats TODO
 * beats DONE, first-found text for BLOCKED/WAITING/TODO, last-found text once every sub-step is DONE) is hit
 * at least once.
 */
public final class ApplicationStagesTest
{
	public static void main(final String[] pArgs)
	{
		ApplicationStagesTest.unknownConnectionConnectionNeedsPersonRestWaiting();
		ApplicationStagesTest.inactiveConnectionConnectionNeedsPerson();
		ApplicationStagesTest.typeWithoutCatalogOperationCatalogNeedsPerson();
		ApplicationStagesTest.typeWithoutCatalogOperationButReadOnlyStillNeedsPerson();
		ApplicationStagesTest.createNoSourceNoMappingCatalogRunningWithTodoText();
		ApplicationStagesTest.linkedButMappingMissingCatalogStillRunning();
		ApplicationStagesTest.manualBlocksCatalogNeedsPersonWithHostHint();
		ApplicationStagesTest.scanNeverRunScanRunning();
		ApplicationStagesTest.scanErrorScanRunning();
		ApplicationStagesTest.sourceInactiveReadyNeedsPerson();
		ApplicationStagesTest.everyStepDoneEverythingDoneAndReady();
		ApplicationStagesTest.catalogGroupTextIsLastSubStepOnceAllDone();
		ApplicationStagesTest.jsonRoundTripNotApplicableHereButPipelineStageLookupWorks();

		System.out.println("ApplicationStagesTest OK");
	}

	// ------------------------------------------------------------------------------------------------------- cases

	private static void unknownConnectionConnectionNeedsPersonRestWaiting()
	{
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "Nope";
		facts.connectorFound = false;
		facts.connectorError = "not found";
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));

		ApplicationStagesTest.check("connection needs a person", pipeline.stage("connection").state == ApplicationStages.StageState.NEEDS_PERSON);
		ApplicationStagesTest.check("connection message is notFound", "wizard.onboard.step.connection.notFound".equals(pipeline.stage("connection").messageKey));
		ApplicationStagesTest.check("catalog waiting", pipeline.stage("catalog").state == ApplicationStages.StageState.WAITING);
		ApplicationStagesTest.check("scan waiting", pipeline.stage("scan").state == ApplicationStages.StageState.WAITING);
		ApplicationStagesTest.check("ready waiting", pipeline.stage("ready").state == ApplicationStages.StageState.WAITING);
		ApplicationStagesTest.check("pipeline not ready", !pipeline.ready);
		ApplicationStagesTest.check("pipeline needs person", pipeline.needsPerson);
	}

	private static void inactiveConnectionConnectionNeedsPerson()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.baseline("Bidon", null);
		facts.connectorActive = false;
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		ApplicationStagesTest.check("connection needs a person", pipeline.stage("connection").state == ApplicationStages.StageState.NEEDS_PERSON);
		ApplicationStagesTest.check("catalog waiting (never reached)", pipeline.stage("catalog").state == ApplicationStages.StageState.WAITING);
	}

	private static void typeWithoutCatalogOperationCatalogNeedsPerson()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.baseline("Bidon", null);
		facts.catalogOperation = false;
		// fix lot (2026-09-25, bug 1): no readOnly operation either -> the true "enrichment, not a source"
		// dead end (see typeWithoutCatalogOperationButReadOnlyStillNeedsPerson below for the DEDUCED_SCAN case)
		facts.hasReadOnlyOperation = false;
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		ApplicationStagesTest.check("connection done", pipeline.stage("connection").state == ApplicationStages.StageState.DONE);
		ApplicationStagesTest.check("catalog needs a person (catalogOperation enrichmentOnly)", pipeline.stage("catalog").state == ApplicationStages.StageState.NEEDS_PERSON);
		ApplicationStagesTest.check("catalog message is the enrichmentOnly key", "wizard.onboard.step.catalogOperation.enrichmentOnly".equals(pipeline.stage("catalog").messageKey));
		ApplicationStagesTest.check("scan waiting", pipeline.stage("scan").state == ApplicationStages.StageState.WAITING);
	}

	/**
	 * Fix lot (2026-09-25, bug 1): with a read-only operation, the same "catalog" grouped stage still reads
	 * NEEDS_PERSON (the group() rule maps BLOCKED to NEEDS_PERSON regardless of which action the step carries -
	 * DEDUCED_SCAN, like OPEN_CONNECTION, is a person-actionable step, never something SourcesRun's own
	 * background pass runs by itself), but with the deduced-scan message/action instead of the dead end.
	 */
	private static void typeWithoutCatalogOperationButReadOnlyStillNeedsPerson()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.baseline("Bidon", null);
		facts.catalogOperation = false;
		facts.hasReadOnlyOperation = true;
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		final ApplicationStages.Stage catalog = pipeline.stage("catalog");
		ApplicationStagesTest.check("catalog needs a person (a click is required, not automatic)", catalog.state == ApplicationStages.StageState.NEEDS_PERSON);
		ApplicationStagesTest.check("catalog message is the deduced key", "wizard.onboard.step.catalogOperation.deduced".equals(catalog.messageKey));
		ApplicationStagesTest.check("catalog action is DEDUCED_SCAN", catalog.action == SourceOnboarding.Action.DEDUCED_SCAN);
	}

	private static void createNoSourceNoMappingCatalogRunningWithTodoText()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.baseline("SourceBidon", null);
		facts.catalogState = "VERIFIED";
		facts.planAction = "CREATE";
		facts.planSourceId = "SOURCEBIDON";
		facts.planMessage = "No Source for connector SourceBidon: create it.";
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		final ApplicationStages.Stage catalog = pipeline.stage("catalog");
		ApplicationStagesTest.check("catalog running (automatic CATALOG_APPLY pending)", catalog.state == ApplicationStages.StageState.RUNNING);
		ApplicationStagesTest.check("catalog message is catalogSource.todo", "wizard.onboard.step.catalogSource.todo".equals(catalog.messageKey));
		ApplicationStagesTest.check("catalog action is CATALOG_APPLY", catalog.action == SourceOnboarding.Action.CATALOG_APPLY);
		ApplicationStagesTest.check("catalog not a person's job", !pipeline.needsPerson);
		ApplicationStagesTest.check("scan waiting (catalog not done yet)", pipeline.stage("scan").state == ApplicationStages.StageState.WAITING);
	}

	private static void linkedButMappingMissingCatalogStillRunning()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.baseline("SourceBidon", null);
		facts.catalogState = "VERIFIED";
		facts.planAction = "LINKED";
		facts.planSourceId = "SOURCEBIDON";
		facts.scanMappingId = null; // catalogSource DONE, scanFlows TODO
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		final ApplicationStages.Stage catalog = pipeline.stage("catalog");
		ApplicationStagesTest.check("catalog running (scanFlows todo, catalogSource already done)", catalog.state == ApplicationStages.StageState.RUNNING);
		ApplicationStagesTest.check("catalog message is scanFlows.missing, the first TODO sub-step", "wizard.onboard.step.scanFlows.missing".equals(catalog.messageKey));
	}

	private static void manualBlocksCatalogNeedsPersonWithHostHint()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.baseline("SourceBidon", null);
		facts.connectorType = "sample-file";
		facts.catalogState = "VERIFIED";
		facts.planAction = "MANUAL";
		facts.planMessage = "Namespace collision.";
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		final ApplicationStages.Stage catalog = pipeline.stage("catalog");
		ApplicationStagesTest.check("catalog needs a person", catalog.state == ApplicationStages.StageState.NEEDS_PERSON);
		ApplicationStagesTest.check("host hint key used for sample-file", "wizard.onboard.step.catalogSource.manualSampleFileHint".equals(catalog.messageKey));
		ApplicationStagesTest.check("action is OPEN_CONNECTION", catalog.action == SourceOnboarding.Action.OPEN_CONNECTION);
	}

	private static void scanNeverRunScanRunning()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.linkedWithMapping();
		facts.lastScanStatus = null;
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		ApplicationStagesTest.check("catalog done", pipeline.stage("catalog").state == ApplicationStages.StageState.DONE);
		ApplicationStagesTest.check("scan running (todoNever)", pipeline.stage("scan").state == ApplicationStages.StageState.RUNNING);
		ApplicationStagesTest.check("scan message is todoNever", "wizard.onboard.step.scan.todoNever".equals(pipeline.stage("scan").messageKey));
	}

	private static void scanErrorScanRunning()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.linkedWithMapping();
		facts.lastScanStatus = "ERROR";
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		ApplicationStagesTest.check("scan running, never a hard failure state in this 4-stage model", pipeline.stage("scan").state == ApplicationStages.StageState.RUNNING);
		ApplicationStagesTest.check("scan message is scan.error", "wizard.onboard.step.scan.error".equals(pipeline.stage("scan").messageKey));
	}

	private static void sourceInactiveReadyNeedsPerson()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.linkedWithMapping();
		facts.lastScanStatus = "OK";
		facts.assetsScanned = true;
		facts.assetsWithColumns = true;
		facts.sourceExists = true;
		facts.sourceActive = false;
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		ApplicationStagesTest.check("scan done", pipeline.stage("scan").state == ApplicationStages.StageState.DONE);
		ApplicationStagesTest.check("ready needs a person", pipeline.stage("ready").state == ApplicationStages.StageState.NEEDS_PERSON);
		ApplicationStagesTest.check("ready message is sourceInactive", "wizard.onboard.step.ready.sourceInactive".equals(pipeline.stage("ready").messageKey));
		ApplicationStagesTest.check("pipeline not ready", !pipeline.ready);
		ApplicationStagesTest.check("pipeline needs person", pipeline.needsPerson);
	}

	private static void everyStepDoneEverythingDoneAndReady()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.linkedWithMapping();
		facts.lastScanStatus = "OK";
		facts.assetsScanned = true;
		facts.assetsWithColumns = true;
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		for (final ApplicationStages.Stage stage : pipeline.stages)
		{
			ApplicationStagesTest.check("stage " + stage.key + " done", stage.state == ApplicationStages.StageState.DONE);
		}
		ApplicationStagesTest.check("pipeline ready", pipeline.ready);
		ApplicationStagesTest.check("pipeline does not need a person", !pipeline.needsPerson);
		ApplicationStagesTest.check("ready message carries the source id", "SOURCEBIDON".equals(pipeline.stage("ready").args[0]));
	}

	private static void catalogGroupTextIsLastSubStepOnceAllDone()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.linkedWithMapping();
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		// catalogOperation/catalogSource/scanFlows are all DONE here (scan itself is not, but that's stage
		// "scan", a different group) - the catalog stage's text must be scanFlows.done (the LAST sub-step),
		// not catalogOperation.done (the first)
		ApplicationStagesTest.check("catalog text is the last sub-step's (scanFlows.done)", "wizard.onboard.step.scanFlows.done".equals(pipeline.stage("catalog").messageKey));
	}

	/** {@link ApplicationStages.Pipeline#stage} returns null for an unknown key rather than throwing - a defensive contract worth pinning down since {@code WizardPage} will call it with literal strings. */
	private static void jsonRoundTripNotApplicableHereButPipelineStageLookupWorks()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.baseline("Bidon", null);
		final ApplicationStages.Pipeline pipeline = ApplicationStages.from(SourceOnboarding.diagnose(facts));
		ApplicationStagesTest.check("unknown stage key returns null, not an exception", pipeline.stage("nope") == null);
		ApplicationStagesTest.check("exactly four stages", pipeline.stages.size() == 4);
	}

	// ---------------------------------------------------------------------------------------------------- fixtures

	private static SourceOnboarding.Facts baseline(final String pId, final String pLabel)
	{
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = pId;
		facts.connectorFound = true;
		facts.connectorActive = true;
		facts.connectorType = "sample-file";
		facts.connectorLabel = pLabel;
		facts.catalogOperation = true;
		return facts;
	}

	private static SourceOnboarding.Facts linkedWithMapping()
	{
		final SourceOnboarding.Facts facts = ApplicationStagesTest.baseline("SourceBidon", null);
		facts.catalogState = "VERIFIED";
		facts.planAction = "LINKED";
		facts.planSourceId = "SOURCEBIDON";
		facts.scanMappingId = Integer.valueOf(240);
		facts.sourceExists = true;
		facts.sourceActive = true;
		return facts;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
