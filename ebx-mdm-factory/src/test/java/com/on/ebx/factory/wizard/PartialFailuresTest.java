package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.landing.LandingRunProgress;

/**
 * JUnit-free: run the main, a failure throws. UX lot D (2026-10-14): partial failures as a real outcome - the failed items read from a landing progress and from
 * the matching entries, the stored detail (and its size cap), what is STILL missing, the gate of the staging step, the sentences, the blocks of steps 3 and 6, the
 * Summary's open points. The scenario is the measured one (UxCrmSf2: Salesforce and Demo CRM, 4 tables), with 2 of the 4 tables failing.
 */
public final class PartialFailuresTest
{
	public static void main(final String[] pArgs)
	{
		PartialFailuresTest.landingItemsComeFromTheGrid();
		PartialFailuresTest.partialIsNeitherAllNorNone();
		PartialFailuresTest.matchingEntriesAreSplitIntoNameAndReason();
		PartialFailuresTest.theDetailRoundTripsAndNeverExceedsTheColumn();
		PartialFailuresTest.anUnreadableDetailIsNoItem();
		PartialFailuresTest.onlyWhatIsStillMissingCounts();
		PartialFailuresTest.theStagingGateOpensOnlyForAKnownGap();
		PartialFailuresTest.theSentencesInBothLanguages();
		PartialFailuresTest.step3SaysWhatIsMissingAndOffersTheRetry();
		PartialFailuresTest.step3WarnsUnderContinueAndNeverBlocks();
		PartialFailuresTest.step3SaysNothingWhenTheLastRunSucceeded();
		PartialFailuresTest.step6OffersToConfigureTheRefusedObjectsAgain();
		PartialFailuresTest.theSummaryListsAnUnresolvedFailureUntilALaterRunSucceeds();
		PartialFailuresTest.theRecentTasksRowReadsAmberFromTheStatus();
		PartialFailuresTest.everyKeyResolvesInBothLanguages();
		System.out.println("PartialFailuresTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static List<LandingRunProgress.TableRef> fourTables()
	{
		return Arrays.asList(new LandingRunProgress.TableRef("1", "Account", "SALESFORCE"), new LandingRunProgress.TableRef("2", "Contact", "SALESFORCE"),
			new LandingRunProgress.TableRef("3", "Customer", "DEMOCRM"), new LandingRunProgress.TableRef("4", "Address", "DEMOCRM"));
	}

	private static void settle(final LandingRunProgress pProgress, final String pTableId, final boolean pOk, final String pReason)
	{
		for (final String stage : LandingRunProgress.STAGES)
		{
			pProgress.finish(pTableId, stage, pOk, pOk ? "ok" : pReason);
		}
	}

	/** Account and Contact fail, Customer and Address load. */
	private static LandingRunProgress twoOfFourFailed()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(PartialFailuresTest.fourTables());
		PartialFailuresTest.settle(progress, "1", false, "No scanned column for this table: rescan the catalog.");
		PartialFailuresTest.settle(progress, "2", false, "Unexpected error on this application - see ebxLog/kernel.log.");
		PartialFailuresTest.settle(progress, "3", true, null);
		PartialFailuresTest.settle(progress, "4", true, null);
		return progress;
	}

	private static WizardSnapshot.TableInfo table(final String pId, final String pName, final String pStatus)
	{
		final WizardSnapshot.TableInfo table = new WizardSnapshot.TableInfo();
		table.id = pId;
		table.tableName = pName;
		table.selected = true;
		table.status = pStatus;
		table.lastLoadRows = "LOADED".equals(pStatus) ? 40 : 0;
		return table;
	}

	/** The snapshot of the scenario: Account and Contact not loaded, Customer and Address loaded. */
	private static WizardSnapshot snapshot()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.id = "1";
		project.code = "UxCrmSf";
		project.label = "UxCrmSf";
		snapshot.project = project;
		final WizardSnapshot.SourceInfo salesforce = new WizardSnapshot.SourceInfo();
		salesforce.id = "s1";
		salesforce.sourceSystem = "SALESFORCE";
		salesforce.tables.add(PartialFailuresTest.table("1", "Account", "FAILED"));
		salesforce.tables.add(PartialFailuresTest.table("2", "Contact", "GENERATED"));
		final WizardSnapshot.SourceInfo crm = new WizardSnapshot.SourceInfo();
		crm.id = "s2";
		crm.sourceSystem = "DEMOCRM";
		crm.tables.add(PartialFailuresTest.table("3", "Customer", "LOADED"));
		crm.tables.add(PartialFailuresTest.table("4", "Address", "LOADED"));
		snapshot.sources.add(salesforce);
		snapshot.sources.add(crm);
		return snapshot;
	}

	private static PartialFailures.Last landingPartial()
	{
		return new PartialFailures.Last(PartialFailures.LANDING, PartialFailures.STATUS, PartialFailures.fromLanding(PartialFailuresTest.twoOfFourFailed()));
	}

	private static WizardPage.Model landingModel(final boolean pPartial)
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.administrator = true;
		model.designer = true;
		model.snapshot = PartialFailuresTest.snapshot();
		if (pPartial)
		{
			model.lastOutcomes.put(PartialFailures.LANDING, PartialFailuresTest.landingPartial());
			model.snapshot.landingGapIsKnown = PartialFailures.landingGapIsKnown(model.snapshot, model.lastOutcomes.get(PartialFailures.LANDING));
		}
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_LANDING;
		model.landingRunExists = true;
		model.landingRunRunning = false;
		model.landingRunProgress = PartialFailuresTest.twoOfFourFailed();
		return model;
	}

	private static String en(final String pKey, final Object... pArgs)
	{
		return WizardPage.esc(WizardMessages.get(Locale.ENGLISH, pKey, pArgs));
	}

	// ----------------------------------------------------------------------------------------------------- the rules

	private static void landingItemsComeFromTheGrid()
	{
		final PartialFailures.Failures failures = PartialFailures.fromLanding(PartialFailuresTest.twoOfFourFailed());
		PartialFailuresTest.check("4 tables in the run, 2 failed", failures.total == 4 && failures.items.size() == 2);
		PartialFailuresTest.check("labelled SOURCE.Table in the grid's order", "SALESFORCE.Account".equals(failures.items.get(0).label) && "SALESFORCE.Contact".equals(failures.items.get(1).label));
		PartialFailuresTest.check("the id is the landing table's", "1".equals(failures.items.get(0).id) && "2".equals(failures.items.get(1).id));
		PartialFailuresTest.check("the reason is the cell's own", "No scanned column for this table: rescan the catalog.".equals(failures.items.get(0).reason));
		PartialFailuresTest.check("names, comma separated", "SALESFORCE.Account, SALESFORCE.Contact".equals(PartialFailures.names(failures.items)));
		final LandingRunProgress waiting = LandingRunProgress.newRun(PartialFailuresTest.fourTables());
		waiting.waiting("1", "needs a key");
		PartialFailuresTest.check("a table waiting for a key is not a failure", PartialFailures.fromLanding(waiting).items.isEmpty());
		PartialFailuresTest.check("a message the job stores", "Landing run finished with failures: 2 of 4 tables were not loaded (SALESFORCE.Account, SALESFORCE.Contact)."
			.equals(PartialFailures.landingMessage(failures)));
	}

	private static void partialIsNeitherAllNorNone()
	{
		final PartialFailures.Failures some = PartialFailures.fromLanding(PartialFailuresTest.twoOfFourFailed());
		PartialFailuresTest.check("2 of 4: partial, not all", PartialFailures.isPartial(some) && !PartialFailures.allFailed(some));
		final LandingRunProgress every = LandingRunProgress.newRun(PartialFailuresTest.fourTables());
		for (final String id : Arrays.asList("1", "2", "3", "4"))
		{
			PartialFailuresTest.settle(every, id, false, "no");
		}
		final PartialFailures.Failures all = PartialFailures.fromLanding(every);
		PartialFailuresTest.check("4 of 4: all failed, so the job fails as a whole", PartialFailures.allFailed(all) && !PartialFailures.isPartial(all));
		final LandingRunProgress none = LandingRunProgress.newRun(PartialFailuresTest.fourTables());
		for (final String id : Arrays.asList("1", "2", "3", "4"))
		{
			PartialFailuresTest.settle(none, id, true, null);
		}
		final PartialFailures.Failures nothing = PartialFailures.fromLanding(none);
		PartialFailuresTest.check("0 of 4: neither", !PartialFailures.isPartial(nothing) && !PartialFailures.allFailed(nothing));
		// two applications, one of them with a table that loaded: the old per-application verdict called this a total failure
		final LandingRunProgress oneApplication = LandingRunProgress.newRun(PartialFailuresTest.fourTables());
		PartialFailuresTest.settle(oneApplication, "1", false, "no");
		PartialFailuresTest.settle(oneApplication, "2", true, null);
		PartialFailuresTest.settle(oneApplication, "3", true, null);
		PartialFailuresTest.settle(oneApplication, "4", true, null);
		PartialFailuresTest.check("1 table of an application failed: partial", PartialFailures.isPartial(PartialFailures.fromLanding(oneApplication)));
	}

	private static void matchingEntriesAreSplitIntoNameAndReason()
	{
		final PartialFailures.Failures configure = PartialFailures.fromEntries(Arrays.asList("Organisation: refusing to overwrite a configuration this generator did not write",
			"OrganisationAddress: MCP call failed: Access denied", "  ", "Organisation: a second line for the same object"), 5);
		PartialFailuresTest.check("2 objects, once each", configure.items.size() == 2 && configure.total == 5);
		PartialFailuresTest.check("name before the first colon", "Organisation".equals(configure.items.get(0).id) && "OrganisationAddress".equals(configure.items.get(1).id));
		PartialFailuresTest.check("the reason is the rest, colons kept", "MCP call failed: Access denied".equals(configure.items.get(1).reason));
		final PartialFailures.Failures run = PartialFailures.fromEntries(Arrays.asList("Personne - the add-on's process failed at 75.0 %"), 2);
		PartialFailuresTest.check("a run failure: 'Entity - reason'", "Personne".equals(run.items.get(0).id) && "the add-on's process failed at 75.0 %".equals(run.items.get(0).reason));
		final PartialFailures.Failures bare = PartialFailures.fromEntries(Arrays.asList("Adresse"), 0);
		PartialFailuresTest.check("an entry with no reason keeps its name", "Adresse".equals(bare.items.get(0).label) && bare.items.get(0).reason.isEmpty());
		PartialFailuresTest.check("null is no entry", PartialFailures.fromEntries(null, 0).items.isEmpty());
	}

	private static void theDetailRoundTripsAndNeverExceedsTheColumn()
	{
		final PartialFailures.Failures failures = PartialFailures.fromLanding(PartialFailuresTest.twoOfFourFailed());
		final PartialFailures.Failures back = PartialFailures.parse(PartialFailures.toJson(failures));
		PartialFailuresTest.check("round trip", back.total == 4 && back.items.size() == 2 && "SALESFORCE.Contact".equals(back.items.get(1).label)
			&& "No scanned column for this table: rescan the catalog.".equals(back.items.get(0).reason));
		// 60 tables with 300-character reasons do not fit 4000 characters: reasons shrink first, then items go, the JSON stays valid
		final List<PartialFailures.Item> many = new ArrayList<PartialFailures.Item>();
		final StringBuilder reason = new StringBuilder();
		for (int i = 0; i < 300; i++)
		{
			reason.append('x');
		}
		for (int i = 0; i < 60; i++)
		{
			many.add(new PartialFailures.Item("id" + i, "SRC.Table" + i, reason.toString()));
		}
		final String json = PartialFailures.toJson(new PartialFailures.Failures(80, many));
		PartialFailuresTest.check("never longer than the column (" + json.length() + ")", json.length() <= PartialFailures.DETAIL_MAX);
		final PartialFailures.Failures capped = PartialFailures.parse(json);
		PartialFailuresTest.check("still valid JSON, the total kept, at least some items", capped.total == 80 && !capped.items.isEmpty() && "SRC.Table0".equals(capped.items.get(0).label));
		PartialFailuresTest.check("a short reason is kept whole", PartialFailures.toJson(new PartialFailures.Failures(2,
			Arrays.asList(new PartialFailures.Item("a", "A", "short")))).contains("\"reason\":\"short\""));
	}

	private static void anUnreadableDetailIsNoItem()
	{
		for (final String bad : new String[] { null, "", "   ", "not json", "{\"items\":42}", "[1,2" })
		{
			PartialFailuresTest.check("'" + bad + "' reads as no item", PartialFailures.parse(bad).items.isEmpty());
		}
		PartialFailuresTest.check("a bare array of items is read too", PartialFailures.parse("[{\"id\":\"1\",\"label\":\"A.B\",\"reason\":\"r\"}]").items.size() == 1);
	}

	private static void onlyWhatIsStillMissingCounts()
	{
		final WizardSnapshot snapshot = PartialFailuresTest.snapshot();
		final PartialFailures.Last partial = PartialFailuresTest.landingPartial();
		PartialFailuresTest.check("both tables are still not loaded", PartialFailures.stillMissing(partial, PartialFailures.unloadedTickedTables(snapshot)).size() == 2);
		snapshot.sources.get(0).tables.get(0).status = "LOADED"; // Account loaded since, by another route
		final List<PartialFailures.Item> missing = PartialFailures.stillMissing(partial, PartialFailures.unloadedTickedTables(snapshot));
		PartialFailuresTest.check("a table loaded since no longer counts", missing.size() == 1 && "SALESFORCE.Contact".equals(missing.get(0).label));
		snapshot.sources.get(0).tables.get(1).selected = false; // Contact unticked
		PartialFailuresTest.check("a table unticked since no longer counts", PartialFailures.stillMissing(partial, PartialFailures.unloadedTickedTables(snapshot)).isEmpty());
		final PartialFailures.Last later = new PartialFailures.Last(PartialFailures.LANDING, "DONE", null);
		PartialFailuresTest.check("a later run that succeeded resolves it", PartialFailures.stillMissing(later, null).isEmpty());
		final PartialFailures.Last failedAll = new PartialFailures.Last(PartialFailures.LANDING, "FAILED", null);
		PartialFailuresTest.check("a later run that failed entirely carries no item (the plain Run again is the way)", PartialFailures.stillMissing(failedAll, null).isEmpty());
		PartialFailuresTest.check("no job at all", PartialFailures.stillMissing(null, null).isEmpty());
		PartialFailuresTest.check("groups of kinds", PartialFailures.LANDING.equals(PartialFailures.groupOf("LANDING_RUN")) && PartialFailures.LANDING.equals(PartialFailures.groupOf("LANDING_RUN_RESUME"))
			&& PartialFailures.CONFIGURE.equals(PartialFailures.groupOf("MATCHING_CONFIGURE")) && PartialFailures.RUN.equals(PartialFailures.groupOf("MATCHING_RUN"))
			&& PartialFailures.groupOf("STAGING_LOAD_RUN") == null && PartialFailures.groupOf(null) == null);
		final WizardSnapshot plans = new WizardSnapshot();
		for (final String[] plan : new String[][] { { "Organisation", "ACCEPTED" }, { "OrganisationAddress", "ACCEPTED" }, { "Personne", "APPLIED" }, { "Adresse", "REJECTED" } })
		{
			final WizardSnapshot.MatchPlanInfo info = new WizardSnapshot.MatchPlanInfo();
			info.entityName = plan[0];
			info.status = plan[1];
			plans.matchPlans.add(info);
		}
		PartialFailuresTest.check("objects still waiting to be applied: the accepted plans only", PartialFailures.unappliedPlans(plans).equals(
			new java.util.LinkedHashSet<String>(Arrays.asList("Organisation", "OrganisationAddress"))));
	}

	private static void theStagingGateOpensOnlyForAKnownGap()
	{
		final WizardSnapshot snapshot = PartialFailuresTest.snapshot();
		PartialFailuresTest.check("without a failed run the staging step is blocked while tables are unloaded", !WizardRules.stepStates(snapshot)[WizardRules.STEP_STAGING].reachable);
		PartialFailuresTest.check("the gap is explained by the failed run", PartialFailures.landingGapIsKnown(snapshot, PartialFailuresTest.landingPartial()));
		snapshot.landingGapIsKnown = true;
		PartialFailuresTest.check("then the step is open (a warning is shown, nothing blocks)", WizardRules.stepStates(snapshot)[WizardRules.STEP_STAGING].reachable);
		// a table that never ran is NOT explained by the failed run: the gate stays closed
		final WizardSnapshot extra = PartialFailuresTest.snapshot();
		extra.sources.get(1).tables.add(PartialFailuresTest.table("5", "Order", "PROPOSED"));
		PartialFailuresTest.check("an unloaded table the run did not report keeps the step closed", !PartialFailures.landingGapIsKnown(extra, PartialFailuresTest.landingPartial()));
		// nothing loaded at all: not a partial landing
		final WizardSnapshot none = PartialFailuresTest.snapshot();
		none.sources.get(1).tables.get(0).status = "FAILED";
		none.sources.get(1).tables.get(1).status = "FAILED";
		PartialFailuresTest.check("no table loaded: the gate stays closed", !PartialFailures.landingGapIsKnown(none, PartialFailuresTest.landingPartial()));
		PartialFailuresTest.check("a job that did not end with failures explains nothing", !PartialFailures.landingGapIsKnown(PartialFailuresTest.snapshot(),
			new PartialFailures.Last(PartialFailures.LANDING, "DONE", null)));
		// after the person has gone on and proposed the staging, the wizard stops suggesting the landing
		final WizardSnapshot gone = PartialFailuresTest.snapshot();
		gone.landingGapIsKnown = true;
		PartialFailuresTest.check("before the staging exists the landing is still the suggested step", WizardRules.suggestedStep(gone) == WizardRules.STEP_LANDING);
		final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
		entity.name = "Organisation";
		gone.stagingEntities.add(entity);
		PartialFailuresTest.check("once the staging exists the gap no longer pulls back to the landing", WizardRules.suggestedStep(gone) != WizardRules.STEP_LANDING);
	}

	private static void theSentencesInBothLanguages()
	{
		final List<PartialFailures.Item> two = PartialFailures.fromLanding(PartialFailuresTest.twoOfFourFailed()).items;
		PartialFailuresTest.check("EN title", "2 of 4 tables were not loaded: SALESFORCE.Account, SALESFORCE.Contact".equals(PartialFailures.landingTitle(two, 4, Locale.ENGLISH)));
		PartialFailuresTest.check("EN title, one table", "1 of 4 tables was not loaded: SALESFORCE.Account".equals(PartialFailures.landingTitle(two.subList(0, 1), 4, Locale.ENGLISH)));
		PartialFailuresTest.check("EN title without a recorded total", "2 tables were not loaded: SALESFORCE.Account, SALESFORCE.Contact".equals(PartialFailures.landingTitle(two, 0, Locale.ENGLISH)));
		PartialFailuresTest.check("EN warning", "The staging will be built without SALESFORCE.Account and SALESFORCE.Contact. Load them later and build the staging again to add them."
			.equals(PartialFailures.stagingWarning(two, Locale.ENGLISH)));
		PartialFailuresTest.check("FR warning names them", PartialFailures.stagingWarning(two, Locale.FRENCH).startsWith("Le staging sera construit sans SALESFORCE.Account et SALESFORCE.Contact."));
		PartialFailuresTest.check("FR title", PartialFailures.landingTitle(two, 4, Locale.FRENCH).startsWith("2 tables sur 4 n\u2019ont pas \u00e9t\u00e9 charg\u00e9es"));
		final List<PartialFailures.Item> five = new ArrayList<PartialFailures.Item>();
		for (int i = 1; i <= 7; i++)
		{
			five.add(new PartialFailures.Item("t" + i, "S.T" + i, ""));
		}
		PartialFailuresTest.check("one name alone", "S.T1".equals(PartialFailures.namesInSentence(five.subList(0, 1), Locale.ENGLISH)));
		PartialFailuresTest.check("three names", "S.T1, S.T2 and S.T3".equals(PartialFailures.namesInSentence(five.subList(0, 3), Locale.ENGLISH)));
		PartialFailuresTest.check("seven names: the first four and the number left", "S.T1, S.T2, S.T3, S.T4 and 3 more".equals(PartialFailures.namesInSentence(five, Locale.ENGLISH)));
		PartialFailuresTest.check("no name", PartialFailures.namesInSentence(new ArrayList<PartialFailures.Item>(), Locale.ENGLISH).isEmpty());
		final List<PartialFailures.Item> objects = PartialFailures.fromEntries(Arrays.asList("Organisation: refused", "OrganisationAddress: refused"), 3).items;
		PartialFailuresTest.check("EN configure title", "2 objects were refused when configuring: Organisation, OrganisationAddress".equals(PartialFailures.configureTitle(objects, Locale.ENGLISH)));
		PartialFailuresTest.check("EN configure title, one", "1 object was refused when configuring: Organisation".equals(PartialFailures.configureTitle(objects.subList(0, 1), Locale.ENGLISH)));
		PartialFailuresTest.check("EN run warning", "Run matching will leave out Organisation and OrganisationAddress: refused when configuring. Configure them again first."
			.equals(PartialFailures.runWarning(objects, Locale.ENGLISH)));
		PartialFailuresTest.check("EN run title", "2 objects failed in the last run: Organisation, OrganisationAddress".equals(PartialFailures.runTitle(objects, Locale.ENGLISH)));
	}

	// -------------------------------------------------------------------------------------------------------- step 3

	private static void step3SaysWhatIsMissingAndOffersTheRetry()
	{
		final String html = WizardPage.render(PartialFailuresTest.landingModel(true));
		PartialFailuresTest.check("the block is there", html.contains("id=\"mdmfLandingMissing\""));
		PartialFailuresTest.check("it says '2 of 4 tables were not loaded: ...'", html.contains(PartialFailuresTest.en("wizard.partial.landing.title.other", Integer.valueOf(2), Integer.valueOf(4),
			"SALESFORCE.Account, SALESFORCE.Contact")));
		PartialFailuresTest.check("each table with its reason", html.contains("<li><b>SALESFORCE.Account</b>") && html.contains("No scanned column for this table: rescan the catalog.")
			&& html.contains("<li><b>SALESFORCE.Contact</b>"));
		PartialFailuresTest.check("the retry names the number", html.contains("data-mdmf-action=\"LANDING_RETRY_FAILED\"")
			&& html.contains(PartialFailuresTest.en("wizard.partial.landing.retry.other", Integer.valueOf(2))));
		PartialFailuresTest.check("the plain 'run again' is still there", html.contains("data-mdmf-landing-run-checkboxes=\"1\""));
		PartialFailuresTest.check("no internal name leaks", !html.contains("DONE_WITH_FAILURES") && !html.contains("PartialOutcome"));
		final WizardPage.Model one = PartialFailuresTest.landingModel(true);
		one.snapshot.sources.get(0).tables.get(1).status = "LOADED"; // Contact loaded since
		final String oneHtml = WizardPage.render(one);
		PartialFailuresTest.check("loaded since: only the table still missing is listed", oneHtml.contains(PartialFailuresTest.en("wizard.partial.landing.title.one", Integer.valueOf(1), Integer.valueOf(4),
			"SALESFORCE.Account")) && oneHtml.contains(PartialFailuresTest.en("wizard.partial.landing.retry.one")));
		final WizardPage.Model fr = PartialFailuresTest.landingModel(true);
		fr.locale = Locale.FRENCH;
		final String frHtml = WizardPage.render(fr);
		PartialFailuresTest.check("French", frHtml.contains(WizardPage.esc(PartialFailures.landingTitle(PartialFailures.fromLanding(PartialFailuresTest.twoOfFourFailed()).items, 4, Locale.FRENCH)))
			&& frHtml.contains(WizardPage.esc(WizardMessages.get(Locale.FRENCH, "wizard.partial.landing.retry.other", Integer.valueOf(2)))));
		final WizardPage.Model readOnly = PartialFailuresTest.landingModel(true);
		readOnly.designer = false;
		PartialFailuresTest.check("without the right to run a landing the retry is disabled", WizardPage.render(readOnly).matches("(?s).*data-mdmf-action=\"LANDING_RETRY_FAILED\"[^>]*disabled.*"));
	}

	private static void step3WarnsUnderContinueAndNeverBlocks()
	{
		final String html = WizardPage.render(PartialFailuresTest.landingModel(true));
		final int cta = html.indexOf(PartialFailuresTest.en("wizard.step3.run.done.continue"));
		PartialFailuresTest.check("'Continue to the staging' is offered", cta > 0);
		final int warning = html.indexOf("id=\"mdmfLandingWarning\"");
		PartialFailuresTest.check("with the warning right under it", warning > cta && html.indexOf("The staging will be built without SALESFORCE.Account and SALESFORCE.Contact.") > warning);
		final WizardPage.Model blocked = PartialFailuresTest.landingModel(false);
		final String blockedHtml = WizardPage.render(blocked);
		PartialFailuresTest.check("without a failed run the old gate is unchanged: no Continue, no warning, no block", !blockedHtml.contains(PartialFailuresTest.en("wizard.step3.run.done.continue"))
			&& !blockedHtml.contains("mdmfLandingWarning") && !blockedHtml.contains("mdmfLandingMissing"));
	}

	private static void step3SaysNothingWhenTheLastRunSucceeded()
	{
		final WizardPage.Model model = PartialFailuresTest.landingModel(true);
		model.lastOutcomes.put(PartialFailures.LANDING, new PartialFailures.Last(PartialFailures.LANDING, "DONE", null));
		model.snapshot.landingGapIsKnown = false;
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);
		PartialFailuresTest.check("a later run that succeeded: no block, no warning", !html.contains("mdmfLandingMissing") && !html.contains("mdmfLandingWarning"));
	}

	// -------------------------------------------------------------------------------------------------------- step 6

	private static WizardPage.Model matchingModel()
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.administrator = true;
		model.designer = true;
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.id = "1";
		project.code = "UxCrmSf";
		project.label = "UxCrmSf";
		snapshot.project = project;
		for (final String name : new String[] { "Organisation", "OrganisationAddress", "Personne" })
		{
			final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
			entity.id = name;
			entity.name = name;
			entity.status = "ACCEPTED";
			snapshot.stagingEntities.add(entity);
		}
		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MASTER_LOAD", "DONE", "loaded"));
		final WizardSnapshot.SourceInfo source = new WizardSnapshot.SourceInfo();
		source.tables.add(PartialFailuresTest.table("1", "Account", "LOADED"));
		snapshot.sources.add(source);
		int n = 0;
		for (final String[] plan : new String[][] { { "Organisation", "ACCEPTED" }, { "OrganisationAddress", "ACCEPTED" }, { "Personne", "APPLIED" } })
		{
			final WizardSnapshot.MatchPlanInfo info = new WizardSnapshot.MatchPlanInfo();
			info.id = "mp" + (++n);
			info.entityName = plan[0];
			info.status = plan[1];
			info.origin = "LLM";
			info.requirements = "Same name.";
			snapshot.matchPlans.add(info);
		}
		model.snapshot = snapshot;
		model.currentStep = WizardRules.STEP_MATCHING;
		model.stepStates = WizardRules.stepStates(snapshot);
		return model;
	}

	private static void step6OffersToConfigureTheRefusedObjectsAgain()
	{
		final WizardPage.Model model = PartialFailuresTest.matchingModel();
		model.lastOutcomes.put(PartialFailures.CONFIGURE, new PartialFailures.Last(PartialFailures.CONFIGURE, PartialFailures.STATUS, PartialFailures.fromEntries(
			Arrays.asList("Organisation: the add-on refused the policy", "OrganisationAddress: MCP call failed"), 3)));
		final String html = WizardPage.render(model);
		PartialFailuresTest.check("the block names the refused objects", html.contains("id=\"mdmfConfigureRefused\"")
			&& html.contains(PartialFailuresTest.en("wizard.partial.configure.title.other", Integer.valueOf(2), "Organisation, OrganisationAddress")));
		PartialFailuresTest.check("with their reasons", html.contains("the add-on refused the policy") && html.contains("MCP call failed"));
		PartialFailuresTest.check("and the button that configures them again", html.contains(PartialFailuresTest.en("wizard.partial.configure.retry.other", Integer.valueOf(2))));
		PartialFailuresTest.check("Run says what it will leave out", html.contains("id=\"mdmfRunWarning\"") && html.contains("Run matching will leave out Organisation and OrganisationAddress"));
		PartialFailuresTest.check("Run itself is not disabled by the warning", !html.matches("(?s).*data-mdmf-action=\"MATCHING_RUN\" data-mdmf-scope=\"all\"[^>]*disabled.*"));
		model.snapshot.matchPlans.get(0).status = "APPLIED"; // Organisation configured since
		model.snapshot.matchPlans.get(1).status = "APPLIED";
		final String done = WizardPage.render(model);
		PartialFailuresTest.check("configured since: the block and the warning are gone", !done.contains("mdmfConfigureRefused") && !done.contains("mdmfRunWarning"));
		final WizardPage.Model run = PartialFailuresTest.matchingModel();
		run.lastOutcomes.put(PartialFailures.RUN, new PartialFailures.Last(PartialFailures.RUN, PartialFailures.STATUS, PartialFailures.fromEntries(
			Arrays.asList("Personne - the add-on's process failed"), 3)));
		final String runHtml = WizardPage.render(run);
		PartialFailuresTest.check("a failed run lists its objects", runHtml.contains("id=\"mdmfRunFailed\"") && runHtml.contains(PartialFailuresTest.en("wizard.partial.run.title.one", Integer.valueOf(1), "Personne")));
		PartialFailuresTest.check("nothing without a partial job", !WizardPage.render(PartialFailuresTest.matchingModel()).contains("mdmf-partial"));
	}

	// ------------------------------------------------------------------------------------------------------ Summary

	private static void theSummaryListsAnUnresolvedFailureUntilALaterRunSucceeds()
	{
		final WizardSnapshot snapshot = PartialFailuresTest.snapshot();
		final WizardPage.Model plans = PartialFailuresTest.matchingModel();
		snapshot.matchPlans.addAll(plans.snapshot.matchPlans);
		final Map<String, PartialFailures.Last> last = new LinkedHashMap<String, PartialFailures.Last>();
		last.put(PartialFailures.LANDING, PartialFailuresTest.landingPartial());
		last.put(PartialFailures.CONFIGURE, new PartialFailures.Last(PartialFailures.CONFIGURE, PartialFailures.STATUS, PartialFailures.fromEntries(
			Arrays.asList("Organisation: refused", "OrganisationAddress: refused"), 3)));
		last.put(PartialFailures.RUN, new PartialFailures.Last(PartialFailures.RUN, PartialFailures.STATUS, PartialFailures.fromEntries(Arrays.asList("Personne - failed"), 3)));
		List<WizardSummaryData.OpenPointInput> points = PartialFailures.openPoints(last, snapshot, new ArrayList<String>(), Locale.ENGLISH);
		PartialFailuresTest.check("one red point per unresolved failure", points.size() == 3);
		for (final WizardSummaryData.OpenPointInput point : points)
		{
			PartialFailuresTest.check("each one is an error", "ERROR".equals(point.severity));
		}
		PartialFailuresTest.check("landing: the tables, linking to the landing step", "2 landing tables are not loaded: SALESFORCE.Account, SALESFORCE.Contact".equals(points.get(0).title)
			&& points.get(0).gotoStep == WizardRules.STEP_LANDING && !points.get(0).detail.isEmpty());
		PartialFailuresTest.check("configure: the objects, linking to the matching step", "2 objects were refused when configuring: Organisation, OrganisationAddress".equals(points.get(1).title)
			&& points.get(1).gotoStep == WizardRules.STEP_MATCHING);
		PartialFailuresTest.check("run: the object", "1 object failed in the last matching run: Personne".equals(points.get(2).title) && points.get(2).gotoStep == WizardRules.STEP_MATCHING);
		points = PartialFailures.openPoints(last, snapshot, Arrays.asList("Personne"), Locale.ENGLISH);
		PartialFailuresTest.check("an object the Summary already flags as a failed run is not repeated", points.size() == 2);
		// a later run of the same kind that succeeded is the latest job: the point is gone
		last.put(PartialFailures.LANDING, new PartialFailures.Last(PartialFailures.LANDING, "DONE", null));
		last.put(PartialFailures.CONFIGURE, new PartialFailures.Last(PartialFailures.CONFIGURE, "DONE", null));
		last.put(PartialFailures.RUN, new PartialFailures.Last(PartialFailures.RUN, "DONE", null));
		PartialFailuresTest.check("resolved by a later run", PartialFailures.openPoints(last, snapshot, new ArrayList<String>(), Locale.ENGLISH).isEmpty());
		PartialFailuresTest.check("no job, no point", PartialFailures.openPoints(new LinkedHashMap<String, PartialFailures.Last>(), snapshot, null, Locale.ENGLISH).isEmpty()
			&& PartialFailures.openPoints(null, snapshot, null, Locale.ENGLISH).isEmpty());
		final List<WizardSummaryData.OpenPointInput> french = PartialFailures.openPoints(Collections.singletonMap(PartialFailures.LANDING, PartialFailuresTest.landingPartial()), snapshot,
			null, Locale.FRENCH);
		PartialFailuresTest.check("French", french.size() == 1 && french.get(0).title.startsWith("2 tables de landing ne sont pas charg\u00e9es"));
	}

	// -------------------------------------------------------------------------------------------------- Recent tasks

	private static void theRecentTasksRowReadsAmberFromTheStatus()
	{
		final RecentTasks.Task task = new RecentTasks.Task();
		task.jobId = "9";
		task.kind = "LANDING_RUN";
		task.status = "DONE_WITH_FAILURES";
		task.message = "Landing run finished with failures: 2 of 4 tables were not loaded (SALESFORCE.Account, SALESFORCE.Contact).";
		task.requestedBy = "alice";
		task.mayRerun = true;
		final String html = RecentTasks.render(Arrays.asList(task), Locale.ENGLISH, new java.util.Date(), null);
		PartialFailuresTest.check("amber row, the label of a partial", html.contains("mdmf-recent-partial") && html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.jobs.status.PARTIAL")));
		PartialFailuresTest.check("its sentence is shown", html.contains("2 of 4 tables were not loaded (SALESFORCE.Account, SALESFORCE.Contact)."));
		PartialFailuresTest.check("the raw status is not shown", !html.contains(">DONE_WITH_FAILURES<"));
	}

	private static void everyKeyResolvesInBothLanguages()
	{
		for (final String key : new String[] { "wizard.partial.and", "wizard.partial.more", "wizard.partial.landing.title.one", "wizard.partial.landing.title.other",
			"wizard.partial.landing.titleNoTotal.one", "wizard.partial.landing.titleNoTotal.other", "wizard.partial.landing.retry.one", "wizard.partial.landing.retry.other",
			"wizard.partial.landing.retryHint", "wizard.partial.landing.nothingToRetry", "wizard.partial.landing.warning", "wizard.partial.configure.title.one",
			"wizard.partial.configure.title.other", "wizard.partial.configure.retry.one", "wizard.partial.configure.retry.other", "wizard.partial.run.title.one",
			"wizard.partial.run.title.other", "wizard.partial.run.warning", "wizard.partial.open.landing.title.one", "wizard.partial.open.landing.title.other",
			"wizard.partial.open.landing.detail", "wizard.partial.open.landing.action", "wizard.partial.open.configure.title.one", "wizard.partial.open.configure.title.other",
			"wizard.partial.open.configure.detail", "wizard.partial.open.run.title.one", "wizard.partial.open.run.title.other", "wizard.partial.open.run.detail",
			"wizard.jobs.status.PARTIAL", "wizard.jobnotice.partial" })
		{
			for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
			{
				final String text = WizardMessages.get(locale, key, "A", "B", "C");
				PartialFailuresTest.check(key + " (" + locale + ") resolves", !key.equals(text) && !text.isEmpty());
			}
			PartialFailuresTest.check(key + ": French differs from English (translated)", !WizardMessages.get(Locale.ENGLISH, key, "A", "B", "C").equals(WizardMessages.get(Locale.FRENCH, key, "A", "B", "C"))
				|| "wizard.partial.open.landing.action".equals(key));
		}
	}
}
