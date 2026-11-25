package com.on.ebx.factory.wizard;

/**
 * JUnit-free, like {@code WizardRulesTest}: run the main, a failure throws. Fixtures below reproduce the
 * measured {@code ebx-connectors} tool answers quoted in {@code scratchpad/source-onboarding-brief.md}
 * (SourceBidon, PersonsSapSample, 2026-09-23) plus every other case the brief's "Tests" paragraph lists,
 * and a handful of defensive fallbacks {@link SourceOnboarding} covers but the brief did not name
 * (unknown plan action, catalog not verified, tool error on the plan call, a Source row missing despite
 * everything above reporting DONE).
 */
public final class SourceOnboardingTest
{
	public static void main(final String[] pArgs)
	{
		SourceOnboardingTest.sourceBidonAsMeasuredCreateNoSourceNoMapping();
		SourceOnboardingTest.personsSapSampleAsMeasuredReadyToPick();
		SourceOnboardingTest.unknownConnectionBlockedOpenConnectionEverythingElseWaiting();
		SourceOnboardingTest.inactiveConnectionBlockedOpenConnection();
		SourceOnboardingTest.typeWithoutCatalogOperationBlockedAtCatalogOperation();
		SourceOnboardingTest.typeWithoutCatalogOperationButReadOnlyOffersDeducedScan();
		SourceOnboardingTest.manualOnSampleFileBlockedOpenConnectionWithHostHint();
		SourceOnboardingTest.manualOnNonSampleFileBlockedOpenConnectionNoHostHint();
		SourceOnboardingTest.catalogNotVerifiedBlocksCatalogSource();
		SourceOnboardingTest.planToolErrorBlocksCatalogSource();
		SourceOnboardingTest.unknownPlanActionIsADefensiveFallback();
		SourceOnboardingTest.linkedButMappingMissingIsTodoCatalogApply();
		SourceOnboardingTest.scanNeverRunIsTodoScanRun();
		SourceOnboardingTest.scanErrorIsTodoScanRun();
		SourceOnboardingTest.scanOkWithoutAssetsIsTodoScanRunWithEmptyResultKey();
		SourceOnboardingTest.scanPartialWithAssetsCountsAsDoneTooBySymmetry();
		SourceOnboardingTest.scanOkWithObjectsButNoColumnIsNotReady();
		SourceOnboardingTest.sourceInactiveBlocksReady();
		SourceOnboardingTest.sourceMissingIsADefensiveFallbackAtReady();
		SourceOnboardingTest.scanMessageIsCarriedThroughButNeverReadByDiagnose();
		SourceOnboardingTest.connectionOptionLabelDefaultsToId();
		SourceOnboardingTest.catalogRestrictedBlocksTheScanStepWithItsOwnKey();

		System.out.println("SourceOnboardingTest OK");
	}

	// --------------------------------------------------------------------------------------------- measured fixtures

	/** {@code connectors_catalog_source(connector=SourceBidon)} as measured, 2026-09-23: plan action CREATE, no Source row yet, no scan mapping yet. */
	private static void sourceBidonAsMeasuredCreateNoSourceNoMapping()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.sampleFileConnection("SourceBidon", null);
		facts.catalogState = "VERIFIED";
		facts.planAction = "CREATE";
		facts.planSourceId = "SOURCEBIDON";
		facts.planNamespace = "sample-file://localhost:8123";
		facts.planMessage = "No Source for connector SourceBidon: create Source SOURCEBIDON with namespace 'sample-file://localhost:8123'; the catalog then creates its flows.";
		facts.sourceExists = false;
		facts.scanMappingId = null;
		facts.lastScanStatus = null;

		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		SourceOnboardingTest.check("connection done", SourceOnboardingTest.step(diagnosis, "connection").state == SourceOnboarding.State.DONE);
		SourceOnboardingTest.check("catalogOperation done", SourceOnboardingTest.step(diagnosis, "catalogOperation").state == SourceOnboarding.State.DONE);
		final SourceOnboarding.Step catalogSource = SourceOnboardingTest.step(diagnosis, "catalogSource");
		SourceOnboardingTest.check("catalogSource todo", catalogSource.state == SourceOnboarding.State.TODO);
		SourceOnboardingTest.check("catalogSource message key is the todo key", "wizard.onboard.step.catalogSource.todo".equals(catalogSource.messageKey));
		SourceOnboardingTest.check("catalogSource message carries the plan's own message", facts.planMessage.equals(catalogSource.args[0]));
		SourceOnboardingTest.check("catalogSource action is CATALOG_APPLY", catalogSource.action == SourceOnboarding.Action.CATALOG_APPLY);
		SourceOnboardingTest.check("scanFlows waiting (catalogSource not done)", SourceOnboardingTest.step(diagnosis, "scanFlows").state == SourceOnboarding.State.WAITING);
		SourceOnboardingTest.check("scan waiting", SourceOnboardingTest.step(diagnosis, "scan").state == SourceOnboarding.State.WAITING);
		SourceOnboardingTest.check("ready waiting", SourceOnboardingTest.step(diagnosis, "ready").state == SourceOnboarding.State.WAITING);
		SourceOnboardingTest.check("next action is CATALOG_APPLY", diagnosis.nextAction == SourceOnboarding.Action.CATALOG_APPLY);
		SourceOnboardingTest.check("not ready", !diagnosis.ready);
		SourceOnboardingTest.check("no pick source id while not ready", diagnosis.pickSourceId == null);
	}

	/** {@code connectors_catalog_source(connector=PersonsSapSample)} as measured, 2026-09-23: LINKED, mapping 236 (Catalog scan: PERSONSSAPSAMPLE), last scan OK with assets - the fully onboarded case. */
	private static void personsSapSampleAsMeasuredReadyToPick()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.sampleFileConnection("PersonsSapSample", null);
		facts.catalogState = "VERIFIED";
		facts.planAction = "LINKED";
		facts.planSourceId = "PERSONSSAPSAMPLE";
		facts.planNamespace = "sample-file://persons-sap.sample-file.local";
		facts.planMessage = "Source PERSONSSAPSAMPLE already names connector PersonsSapSample with namespace 'sample-file://persons-sap.sample-file.local'.";
		facts.sourceExists = true;
		facts.sourceActive = true;
		facts.scanMappingId = Integer.valueOf(236);
		facts.lastScanStatus = "OK";
		facts.assetsScanned = true;
		facts.assetsWithColumns = true;

		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		for (final SourceOnboarding.Step step : diagnosis.steps)
		{
			SourceOnboardingTest.check("every step is DONE: " + step.key, step.state == SourceOnboarding.State.DONE);
		}
		SourceOnboardingTest.check("ready", diagnosis.ready);
		SourceOnboardingTest.check("next action is PICK", diagnosis.nextAction == SourceOnboarding.Action.PICK);
		SourceOnboardingTest.check("pick source id is the plan's source id", "PERSONSSAPSAMPLE".equals(diagnosis.pickSourceId));
	}

	// ---------------------------------------------------------------------------------------------------- connection

	private static void unknownConnectionBlockedOpenConnectionEverythingElseWaiting()
	{
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "NoSuchConnector";
		facts.connectorFound = false;
		facts.connectorError = "Connector not found: NoSuchConnector";

		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		final SourceOnboarding.Step connection = SourceOnboardingTest.step(diagnosis, "connection");
		SourceOnboardingTest.check("connection blocked", connection.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("connection message key is notFound", "wizard.onboard.step.connection.notFound".equals(connection.messageKey));
		SourceOnboardingTest.check("connection action is OPEN_CONNECTION", connection.action == SourceOnboarding.Action.OPEN_CONNECTION);
		SourceOnboardingTest.check("notFound message carries the connector id", "NoSuchConnector".equals(connection.args[0]));
		SourceOnboardingTest.check("notFound message carries the tool's error text", facts.connectorError.equals(connection.args[1]));
		for (final String key : new String[] { "catalogOperation", "catalogSource", "scanFlows", "scan", "ready" })
		{
			SourceOnboardingTest.check(key + " waiting", SourceOnboardingTest.step(diagnosis, key).state == SourceOnboarding.State.WAITING);
			SourceOnboardingTest.check(key + " waiting message key", "wizard.onboard.step.waiting".equals(SourceOnboardingTest.step(diagnosis, key).messageKey));
		}
		SourceOnboardingTest.check("next action is OPEN_CONNECTION", diagnosis.nextAction == SourceOnboarding.Action.OPEN_CONNECTION);
		SourceOnboardingTest.check("not ready", !diagnosis.ready);
	}

	private static void inactiveConnectionBlockedOpenConnection()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.sampleFileConnection("SourceBidon", "Source Bidon");
		facts.connectorActive = false;

		final SourceOnboarding.Step connection = SourceOnboardingTest.step(SourceOnboarding.diagnose(facts), "connection");
		SourceOnboardingTest.check("inactive connection blocks", connection.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("inactive message key", "wizard.onboard.step.connection.inactive".equals(connection.messageKey));
		SourceOnboardingTest.check("inactive message prefers the label over the id", "Source Bidon".equals(connection.args[0]));
		SourceOnboardingTest.check("inactive action is OPEN_CONNECTION", connection.action == SourceOnboarding.Action.OPEN_CONNECTION);
	}

	// --------------------------------------------------------------------------------------------- catalog operation

	/**
	 * Fix lot (2026-09-25, bug 1): a connection with NO catalog operation and NO read-only operation at all
	 * (the OpenFDA-style lookup-only case is the fixture just below - this one is the true "nothing this
	 * connection can ever list a table from" dead end, e.g. a webhook-only or write-only connector type).
	 */
	private static void typeWithoutCatalogOperationBlockedAtCatalogOperation()
	{
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "SlackDemo";
		facts.connectorFound = true;
		facts.connectorActive = true;
		facts.connectorType = "slack";
		facts.catalogOperation = false; // the slack type has no "catalog" operation (connectors_describe.operations[])
		facts.hasReadOnlyOperation = false; // and no readOnly operation at all either

		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		SourceOnboardingTest.check("connection done", SourceOnboardingTest.step(diagnosis, "connection").state == SourceOnboarding.State.DONE);
		final SourceOnboarding.Step catalogOperation = SourceOnboardingTest.step(diagnosis, "catalogOperation");
		SourceOnboardingTest.check("catalogOperation blocked", catalogOperation.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("catalogOperation message key", "wizard.onboard.step.catalogOperation.enrichmentOnly".equals(catalogOperation.messageKey));
		SourceOnboardingTest.check("catalogOperation message carries the type", "slack".equals(catalogOperation.args[0]));
		SourceOnboardingTest.check("catalogOperation action is NONE: nothing to click, pick another connection", catalogOperation.action == SourceOnboarding.Action.NONE);
		SourceOnboardingTest.check("catalogSource waiting", SourceOnboardingTest.step(diagnosis, "catalogSource").state == SourceOnboarding.State.WAITING);
		SourceOnboardingTest.check("next action is NONE", diagnosis.nextAction == SourceOnboarding.Action.NONE);
	}

	/**
	 * Fix lot (2026-09-25, bug 1): a connection with NO catalog operation but AT LEAST ONE read-only operation
	 * (the OpenFDA case measured live, 2026-09-25: operations udi/deviceSearch/drug, all read-only, no
	 * catalog operation) - used to be the exact same dead end as the fixture above; now offers DEDUCED_SCAN.
	 */
	private static void typeWithoutCatalogOperationButReadOnlyOffersDeducedScan()
	{
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "OpenFDA";
		facts.connectorFound = true;
		facts.connectorActive = true;
		facts.connectorType = "openfda";
		facts.catalogOperation = false;
		facts.hasReadOnlyOperation = true; // udi/deviceSearch/drug are readOnly=true, none is named "catalog"

		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		final SourceOnboarding.Step catalogOperation = SourceOnboardingTest.step(diagnosis, "catalogOperation");
		SourceOnboardingTest.check("catalogOperation blocked (a person must click the deduced-scan button)",
			catalogOperation.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("catalogOperation message key is the deduced key", "wizard.onboard.step.catalogOperation.deduced".equals(catalogOperation.messageKey));
		SourceOnboardingTest.check("catalogOperation message carries the type", "openfda".equals(catalogOperation.args[0]));
		SourceOnboardingTest.check("catalogOperation action is DEDUCED_SCAN", catalogOperation.action == SourceOnboarding.Action.DEDUCED_SCAN);
		SourceOnboardingTest.check("catalogSource waiting (the official chain does not apply to a deduced entry)",
			SourceOnboardingTest.step(diagnosis, "catalogSource").state == SourceOnboarding.State.WAITING);
		SourceOnboardingTest.check("next action is DEDUCED_SCAN", diagnosis.nextAction == SourceOnboarding.Action.DEDUCED_SCAN);
		SourceOnboardingTest.check("not ready", !diagnosis.ready);
	}

	// -------------------------------------------------------------------------------------------------- catalog source

	/** MANUAL on a {@code sample-file} connection: the host-collision hint fires (CLAUDE.md ss14). */
	private static void manualOnSampleFileBlockedOpenConnectionWithHostHint()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.sampleFileConnection("SourceBidon", null);
		facts.catalogState = "VERIFIED";
		facts.planAction = "MANUAL";
		facts.planMessage = "namespace held by Source SOURCEBIDON2";

		final SourceOnboarding.Step catalogSource = SourceOnboardingTest.step(SourceOnboarding.diagnose(facts), "catalogSource");
		SourceOnboardingTest.check("MANUAL blocks", catalogSource.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("MANUAL message key carries the sample-file host hint", "wizard.onboard.step.catalogSource.manualSampleFileHint".equals(catalogSource.messageKey));
		SourceOnboardingTest.check("hint message carries the plan message", facts.planMessage.equals(catalogSource.args[0]));
		SourceOnboardingTest.check("hint message carries the connector id (for the suggested host)", "SourceBidon".equals(catalogSource.args[1]));
		SourceOnboardingTest.check("MANUAL action is OPEN_CONNECTION: a person must fix the connection", catalogSource.action == SourceOnboarding.Action.OPEN_CONNECTION);
	}

	/** MANUAL on any OTHER connector type: no host hint (the fix is not "give it its own host"). */
	private static void manualOnNonSampleFileBlockedOpenConnectionNoHostHint()
	{
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "SnowflakeDemo";
		facts.connectorFound = true;
		facts.connectorActive = true;
		facts.connectorType = "snowflake";
		facts.catalogOperation = true;
		facts.catalogState = "VERIFIED";
		facts.planAction = "MANUAL";
		facts.planMessage = "ambiguous namespace";

		final SourceOnboarding.Step catalogSource = SourceOnboardingTest.step(SourceOnboarding.diagnose(facts), "catalogSource");
		SourceOnboardingTest.check("MANUAL blocks", catalogSource.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("no sample-file hint for a non-sample-file type", "wizard.onboard.step.catalogSource.manual".equals(catalogSource.messageKey));
		SourceOnboardingTest.check("plain manual message carries the plan message", facts.planMessage.equals(catalogSource.args[0]));
		SourceOnboardingTest.check("plain manual message has exactly one argument", catalogSource.args.length == 1);
	}

	private static void catalogNotVerifiedBlocksCatalogSource()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.sampleFileConnection("SourceBidon", null);
		facts.catalogState = "NOT_DEPLOYED";
		facts.planAction = "CREATE"; // must be ignored: the catalog state problem takes priority

		final SourceOnboarding.Step catalogSource = SourceOnboardingTest.step(SourceOnboarding.diagnose(facts), "catalogSource");
		SourceOnboardingTest.check("catalog not verified blocks", catalogSource.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("message key names the catalog state problem", "wizard.onboard.step.catalogSource.catalogUnavailable".equals(catalogSource.messageKey));
		SourceOnboardingTest.check("message carries the catalog state", "NOT_DEPLOYED".equals(catalogSource.args[0]));
		SourceOnboardingTest.check("action is NONE: nothing the user can do from here", catalogSource.action == SourceOnboarding.Action.NONE);
	}

	private static void planToolErrorBlocksCatalogSource()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.sampleFileConnection("SourceBidon", null);
		facts.planError = "connectors_catalog_source refused: internal error";

		final SourceOnboarding.Step catalogSource = SourceOnboardingTest.step(SourceOnboarding.diagnose(facts), "catalogSource");
		SourceOnboardingTest.check("tool error blocks", catalogSource.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("message key names the tool error", "wizard.onboard.step.catalogSource.toolError".equals(catalogSource.messageKey));
		SourceOnboardingTest.check("message carries the tool's error text", facts.planError.equals(catalogSource.args[0]));
	}

	/** Defensive: a plan call that answered neither an error, a state problem, nor one of the five known actions must still produce a Step, never a NullPointerException. */
	private static void unknownPlanActionIsADefensiveFallback()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.sampleFileConnection("SourceBidon", null);
		facts.catalogState = "VERIFIED";
		facts.planAction = "SOME_FUTURE_ACTION";

		final SourceOnboarding.Step catalogSource = SourceOnboardingTest.step(SourceOnboarding.diagnose(facts), "catalogSource");
		SourceOnboardingTest.check("unrecognized plan action blocks defensively", catalogSource.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("message key is the unknown fallback", "wizard.onboard.step.catalogSource.unknown".equals(catalogSource.messageKey));
		SourceOnboardingTest.check("no args needed for the fallback", catalogSource.args.length == 0);
	}

	// ----------------------------------------------------------------------------------------------------- scan flows

	private static void linkedButMappingMissingIsTodoCatalogApply()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.sampleFileConnection("SourceBidon", null);
		facts.catalogState = "VERIFIED";
		facts.planAction = "LINKED";
		facts.planSourceId = "SOURCEBIDON";
		facts.scanMappingId = null; // the catalog scan mappings were deleted or never created despite the link

		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		SourceOnboardingTest.check("catalogSource done (linked)", SourceOnboardingTest.step(diagnosis, "catalogSource").state == SourceOnboarding.State.DONE);
		final SourceOnboarding.Step scanFlows = SourceOnboardingTest.step(diagnosis, "scanFlows");
		SourceOnboardingTest.check("scanFlows todo, never silently blocked", scanFlows.state == SourceOnboarding.State.TODO);
		SourceOnboardingTest.check("scanFlows message key is missing", "wizard.onboard.step.scanFlows.missing".equals(scanFlows.messageKey));
		SourceOnboardingTest.check("scanFlows action is CATALOG_APPLY: create/link the source again", scanFlows.action == SourceOnboarding.Action.CATALOG_APPLY);
		SourceOnboardingTest.check("scan still waiting", SourceOnboardingTest.step(diagnosis, "scan").state == SourceOnboarding.State.WAITING);
		SourceOnboardingTest.check("next action is CATALOG_APPLY", diagnosis.nextAction == SourceOnboarding.Action.CATALOG_APPLY);
	}

	// ----------------------------------------------------------------------------------------------------------- scan

	private static void scanNeverRunIsTodoScanRun()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.linkedWithMapping();
		facts.lastScanStatus = "NEVER";

		final SourceOnboarding.Step scan = SourceOnboardingTest.step(SourceOnboarding.diagnose(facts), "scan");
		SourceOnboardingTest.check("never-run scan is todo", scan.state == SourceOnboarding.State.TODO);
		SourceOnboardingTest.check("never-run message key", "wizard.onboard.step.scan.todoNever".equals(scan.messageKey));
		SourceOnboardingTest.check("never-run action is SCAN_RUN", scan.action == SourceOnboarding.Action.SCAN_RUN);
	}

	private static void scanErrorIsTodoScanRun()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.linkedWithMapping();
		facts.lastScanStatus = "ERROR";

		final SourceOnboarding.Step scan = SourceOnboardingTest.step(SourceOnboarding.diagnose(facts), "scan");
		SourceOnboardingTest.check("scan ERROR is todo, not blocked", scan.state == SourceOnboarding.State.TODO);
		SourceOnboardingTest.check("scan error message key", "wizard.onboard.step.scan.error".equals(scan.messageKey));
		SourceOnboardingTest.check("scan error still offers SCAN_RUN", scan.action == SourceOnboarding.Action.SCAN_RUN);
	}

	private static void scanOkWithoutAssetsIsTodoScanRunWithEmptyResultKey()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.linkedWithMapping();
		facts.lastScanStatus = "OK";
		facts.assetsScanned = false;

		final SourceOnboarding.Step scan = SourceOnboardingTest.step(SourceOnboarding.diagnose(facts), "scan");
		SourceOnboardingTest.check("OK without assets is still todo", scan.state == SourceOnboarding.State.TODO);
		SourceOnboardingTest.check("empty-result message key", "wizard.onboard.step.scan.emptyResult".equals(scan.messageKey));
		SourceOnboardingTest.check("empty-result message carries the status", "OK".equals(scan.args[0]));
		SourceOnboardingTest.check("empty-result still offers SCAN_RUN", scan.action == SourceOnboarding.Action.SCAN_RUN);
	}

	/** Symmetry: the brief only names "OK without assets", but a PARTIAL scan with assets found must count as DONE exactly like OK does - both are in the DONE set per the brief's own step description. */
	private static void scanPartialWithAssetsCountsAsDoneTooBySymmetry()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.linkedWithMapping();
		facts.lastScanStatus = "PARTIAL";
		facts.assetsScanned = true;
		facts.assetsWithColumns = true;

		final SourceOnboarding.Step scan = SourceOnboardingTest.step(SourceOnboarding.diagnose(facts), "scan");
		SourceOnboardingTest.check("PARTIAL with assets is done", scan.state == SourceOnboarding.State.DONE);
		SourceOnboardingTest.check("done message key", "wizard.onboard.step.scan.done".equals(scan.messageKey));
		SourceOnboardingTest.check("done message carries the status", "PARTIAL".equals(scan.args[0]));
	}

	/**
	 * UX lot U2 (2026-10-13), the defect measured live on "salesforce://login.salesforce.com": last scan OK, one asset, but that asset came from OpenLineage
	 * events and has no column. The diagnosis said "Ready" and the landing plan answered "0 table(s)". It must say the scan read nothing and offer the scan.
	 */
	private static void scanOkWithObjectsButNoColumnIsNotReady()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.linkedWithMapping();
		facts.lastScanStatus = "OK";
		facts.assetsScanned = true;
		facts.assetsWithColumns = false;
		facts.sourceExists = true;
		facts.sourceActive = true;

		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		final SourceOnboarding.Step scan = SourceOnboardingTest.step(diagnosis, "scan");
		SourceOnboardingTest.check("objects without a column: scan is todo", scan.state == SourceOnboarding.State.TODO);
		SourceOnboardingTest.check("the no-column message key", "wizard.onboard.step.scan.noColumns".equals(scan.messageKey));
		SourceOnboardingTest.check("the next action is the scan", diagnosis.nextAction == SourceOnboarding.Action.SCAN_RUN);
		SourceOnboardingTest.check("not ready", !diagnosis.ready && diagnosis.pickSourceId == null);
		SourceOnboardingTest.check("ready step waits", SourceOnboardingTest.step(diagnosis, "ready").state == SourceOnboarding.State.WAITING);

		facts.assetsWithColumns = true;
		SourceOnboardingTest.check("with a scanned column it is ready", SourceOnboarding.diagnose(facts).ready);
	}

	// --------------------------------------------------------------------------------------------------------- ready

	private static void sourceInactiveBlocksReady()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.linkedWithMapping();
		facts.lastScanStatus = "OK";
		facts.assetsScanned = true;
		facts.assetsWithColumns = true;
		facts.sourceExists = true;
		facts.sourceActive = false;

		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		SourceOnboardingTest.check("scan done", SourceOnboardingTest.step(diagnosis, "scan").state == SourceOnboarding.State.DONE);
		final SourceOnboarding.Step ready = SourceOnboardingTest.step(diagnosis, "ready");
		SourceOnboardingTest.check("ready blocked on an inactive source", ready.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("ready message key names the inactive source", "wizard.onboard.step.ready.sourceInactive".equals(ready.messageKey));
		SourceOnboardingTest.check("ready message carries the source id", "SOURCEBIDON".equals(ready.args[0]));
		SourceOnboardingTest.check("ready action is NONE", ready.action == SourceOnboarding.Action.NONE);
		SourceOnboardingTest.check("next action is NONE: nothing left to click", diagnosis.nextAction == SourceOnboarding.Action.NONE);
		SourceOnboardingTest.check("not ready", !diagnosis.ready);
		SourceOnboardingTest.check("no pick source id", diagnosis.pickSourceId == null);
	}

	/** Defensive: every earlier step DONE yet the Source row itself is missing - should not happen on a real instance, but must not crash and must not silently claim ready. */
	private static void sourceMissingIsADefensiveFallbackAtReady()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.linkedWithMapping();
		facts.lastScanStatus = "OK";
		facts.assetsScanned = true;
		facts.assetsWithColumns = true;
		facts.sourceExists = false;
		facts.sourceActive = false;

		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		final SourceOnboarding.Step ready = SourceOnboardingTest.step(diagnosis, "ready");
		SourceOnboardingTest.check("ready blocked when the source row is missing", ready.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("ready message key names the missing source", "wizard.onboard.step.ready.sourceMissing".equals(ready.messageKey));
		SourceOnboardingTest.check("not ready", !diagnosis.ready);
	}

	/** source-onboarding lot (2026-09-23) extension: {@link SourceOnboarding.Facts#scanMessage} is a pure carry-through field - {@link SourceOnboarding#diagnose} must never look at it (the scan step's DONE/TODO decision stays driven by lastScanStatus/assetsScanned alone), but it must still survive untouched on the Diagnosis's own input Facts. */
	private static void scanMessageIsCarriedThroughButNeverReadByDiagnose()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.linkedWithMapping();
		facts.lastScanStatus = "OK";
		facts.assetsScanned = true;
		facts.assetsWithColumns = true;
		facts.scanMessage = "Loaded 12 of 12 row(s).";

		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		SourceOnboardingTest.check("scan still done regardless of scanMessage content", SourceOnboardingTest.step(diagnosis, "scan").state == SourceOnboarding.State.DONE);
		SourceOnboardingTest.check("scanMessage itself is untouched by diagnose", "Loaded 12 of 12 row(s).".equals(facts.scanMessage));
	}

	/** source-onboarding lot (2026-09-23): {@link SourceOnboarding.ConnectionOption}'s label falls back to the id, never blank - same rule as Facts's own connectorDisplay. */
	private static void connectionOptionLabelDefaultsToId()
	{
		final SourceOnboarding.ConnectionOption withLabel = new SourceOnboarding.ConnectionOption("SourceBidon", "Source Bidon", "sample-file", true, true, "");
		SourceOnboardingTest.check("label kept when given", "Source Bidon".equals(withLabel.label));
		final SourceOnboarding.ConnectionOption noLabel = new SourceOnboarding.ConnectionOption("SourceBidon", null, "sample-file", true, true, null);
		SourceOnboardingTest.check("label falls back to id", "SourceBidon".equals(noLabel.label));
		SourceOnboardingTest.check("catalogSourceId falls back to empty, never null", "".equals(noLabel.catalogSourceId));
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	/**
	 * Audit F-02 (catalog reads): the Source row and its assets are hidden by the person's catalog right - the facts then read
	 * as "never scanned", and the scan step must say why instead of offering "Run the scan" (a dead end for that person).
	 */
	private static void catalogRestrictedBlocksTheScanStepWithItsOwnKey()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.linkedWithMapping();
		facts.sourceExists = false;
		facts.lastScanStatus = null;
		facts.catalogRestricted = true;
		final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
		final SourceOnboarding.Step scan = SourceOnboardingTest.step(diagnosis, "scan");
		SourceOnboardingTest.check("restricted: scan blocked", scan.state == SourceOnboarding.State.BLOCKED);
		SourceOnboardingTest.check("restricted: its own message key", "wizard.onboard.step.scan.catalogRestricted".equals(scan.messageKey));
		SourceOnboardingTest.check("restricted: names the catalog source", "SOURCEBIDON".equals(scan.args[0]));
		SourceOnboardingTest.check("restricted: no scan offered", scan.action == SourceOnboarding.Action.NONE && diagnosis.nextAction != SourceOnboarding.Action.SCAN_RUN);
		SourceOnboardingTest.check("restricted: not ready", !diagnosis.ready && diagnosis.pickSourceId == null);
		facts.catalogRestricted = false;
		SourceOnboardingTest.check("not restricted: the usual never-scanned step", "wizard.onboard.step.scan.todoNever".equals(SourceOnboardingTest.step(SourceOnboarding.diagnose(facts), "scan").messageKey));
	}

	/** A {@code sample-file} connection, found, active, with a catalog operation - the common baseline every test starts from and overrides. */
	private static SourceOnboarding.Facts sampleFileConnection(final String pId, final String pLabel)
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

	/** Baseline through {@code scanFlows} DONE (LINKED, mapping 236): every scan-step test starts here and only overrides {@code lastScanStatus}/{@code assetsScanned}. */
	private static SourceOnboarding.Facts linkedWithMapping()
	{
		final SourceOnboarding.Facts facts = SourceOnboardingTest.sampleFileConnection("SourceBidon", null);
		facts.catalogState = "VERIFIED";
		facts.planAction = "LINKED";
		facts.planSourceId = "SOURCEBIDON";
		facts.scanMappingId = Integer.valueOf(240);
		facts.sourceExists = true;
		facts.sourceActive = true;
		return facts;
	}

	private static SourceOnboarding.Step step(final SourceOnboarding.Diagnosis pDiagnosis, final String pKey)
	{
		for (final SourceOnboarding.Step step : pDiagnosis.steps)
		{
			if (step.key.equals(pKey))
			{
				return step;
			}
		}
		throw new AssertionError("no step with key " + pKey);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
