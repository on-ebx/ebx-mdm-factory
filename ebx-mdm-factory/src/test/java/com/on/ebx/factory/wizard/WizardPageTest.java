package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.landing.KeyEvidence;
import com.on.ebx.factory.landing.KeyStudyReport;
import com.on.ebx.factory.landing.LandingRunProgress;

import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.staging.ProposalProgress;
import com.on.ebx.factory.staging.RunProgress;

/** JUnit-free: run the main, a failure throws. Exercises {@link WizardPage} without any EBX type. */
public final class WizardPageTest
{
	public static void main(final String[] pArgs)
	{
		WizardPageTest.blankModelShowsOnlyStep1();
		WizardPageTest.jobsBannerFollowsTheProject();
		WizardPageTest.currentStepPanelIsTheOnlyOneVisible();
		WizardPageTest.blockedStepButtonIsDisabledWithReason();
		WizardPageTest.fieldValuesAreEscaped();
		WizardPageTest.fieldErrorIsShownNextToItsField();
		WizardPageTest.onlyConsolidationRadioIsEnabled();
		WizardPageTest.everyStyleCardShowsItsHeadlineAndTwoLabelledFacts();
		WizardPageTest.onlyConsolidationCardShowsTheMostCommonChip();
		WizardPageTest.unavailableChipIsNeverInsideASentence();
		WizardPageTest.selectedStyleCardMatchesTheDraft();
		WizardPageTest.stagingModeCardsUseTheSameCardPattern();
		WizardPageTest.existingProjectCodeIsReadOnly();
		// wizard-llm-value-sharing lot (2026-09-28)
		WizardPageTest.llmSharingControlDefaultsToShapesOnlyWithTheSampleFieldHidden();
		WizardPageTest.llmSharingSampleValuesShowsTheSizeAndNamesTheConnector();
		WizardPageTest.llmSharingControlOnAnExistingProjectShowsTheStoredChoice();
		WizardPageTest.llmSharingCapDisablesHigherCardsAndSaysSoWhenTheChoiceIsAbove();
		WizardPageTest.llmSharingFieldErrorsShowNextToTheirField();
		WizardPageTest.sourceCardsAndAddFormRender();
		WizardPageTest.deducedScanCardsAndConfirmCardsRender();
		WizardPageTest.deducedScanFixLot20260926Renders();
		WizardPageTest.deducedScanButtonFollowsTheRunsOwnRule();
		WizardPageTest.decidedDeducedTablesAreListedNotScannedAgain();
		WizardPageTest.sourceTablesAreKeptInOneAction();
		WizardPageTest.singleAddApplicationListRenders();
		WizardPageTest.addFormProposesACodeAndNoLongerAsksATrustRank();
		WizardPageTest.liveSystemAlreadyAnApplicationOffersItsTablesNotTheAddForm();
		WizardPageTest.catalogSourcePickerIsASearchableCombobox();
		WizardPageTest.railTicksCompletedStepsWhateverStepIsOpen();
		WizardPageTest.landingTableRowRendersKeyEditAndTrack();
		WizardPageTest.keyStudyPanelRendersProposalEvidenceAndApply();
		WizardPageTest.disabledGeneratedButtonShowsGateReason();
		WizardPageTest.stagingEntityRendersAcceptRejectButtons();
		WizardPageTest.decisionCellOffersExactlyTheActionsForEachStatus();
		WizardPageTest.noRowEverShowsTwoStatusChips();
		WizardPageTest.primaryKeyAttributeShowsFollowsEntityChipNotButtons();
		WizardPageTest.entityWaitingHintShownWhenGenerationWouldSkipIt();
		WizardPageTest.canonicalEntityCardShowsItsMembers();
		WizardPageTest.supersededEntityCardIsNotRendered();
		WizardPageTest.step5OffersMasterGenerateAndLoad();
		WizardPageTest.step6HidesStalePipelineTrackWhenNoMatchPlanExists();
		WizardPageTest.step6OffersMatchingSuggestAndListsPlans();
		WizardPageTest.matchingLivePanelFollowsTheActionUnderTheButtons();
		WizardPageTest.matchingTriggerChoiceIsOfferedInStep6();
		WizardPageTest.matchPlanNoMatchingAndReproposePanelsRenderInline();
		WizardPageTest.matchPlanCardShowsTheDecisionTreeNotRawJson();
		WizardPageTest.attributeMappingWarningIsShown();
		WizardPageTest.waitingForReviewLineShowsCounts();
		WizardPageTest.waitingForReviewLineOffersAcceptCleaningStepsButton();
		WizardPageTest.proposalDoneSummaryUsesDecidedCountsNotProposedCounts();
		WizardPageTest.llmDisclosureTextFollowsTheProjectsDataPolicyLevel();
		WizardPageTest.reopenMappingsButtonOnlyWhenSomethingToReopen();
		WizardPageTest.step7ReflectsMasterAndMatchingReadiness();
		WizardPageTest.summaryScopesItsCardsLikeTheMeasuredProject();
		WizardPageTest.jobsPollTheProposalPassesInPlace();
		WizardPageTest.summaryDistributeCardSaysTheCatalogHidesTheProduct();
		// automatic-intake wizard lot (2026-10-05)
		WizardPageTest.intakeSummaryCardShowsItsStateInEveryCase();
		WizardPageTest.intakeSetupScreenRendersItsSettingsFlowAndGate();
		WizardPageTest.intakeRunningScreenRendersEveryStateAndTheChains();
		WizardPageTest.intakeHelpersMatchTheScriptsFormulas();
		WizardPageTest.step7DistributionEmptyStateThenTargetsAndProduct();
		WizardPageTest.step7DistributionArmingChecklistTicksOnlyOnRealFirstFullSend();
		WizardPageTest.step7DistributionProductPanelOffersNestModeAndAddAssets();
		WizardPageTest.step7DistributionSortsGroupsConnectionsAndWarnsOnKafkaKeyField();
		WizardPageTest.step7DistributionPerTableDestinationsOnlyForKafkaConnectorType();
		WizardPageTest.step7DistributionRetireSettingsRenderRadioAndUndoneCheckbox();
		WizardPageTest.step7DistributionRetireSettingsHiddenForNonRealtimeMode();
		WizardPageTest.step7DistributionHistoryRendersRetireAndReinstatedWording();
		WizardPageTest.proposalLinesRenderInlineUnderTheirEntity();
		WizardPageTest.qualityLineShowsSeverityControlPreselectedAndEvidence();
		WizardPageTest.appliedScriptConstraintShowsExplanationAndRemoveMeasure();
		WizardPageTest.runningProjectStepShowsElapsedMinutes();
		WizardPageTest.runningProposalStageShowsElapsedMinutesAndArmsThePoll();
		WizardPageTest.stableScrollAnchorIdsAreRendered();
		WizardPageTest.entityBulkButtonsShowTheEntityIdWhenDecidable();
		WizardPageTest.entityBulkButtonsAbsentWhenNothingDecidable();
		WizardPageTest.qualityOnlyBulkButtonsAndIncludeBelowThresholdCheckboxRender();
		WizardPageTest.acceptedScriptConstraintShowsApplyMessageAndBlockedApplyReasonWhenZoneMissing();
		WizardPageTest.appliedScriptConstraintShowsAppliedModelAndDate();
		WizardPageTest.frenchRendersAccentedLabels();
		WizardPageTest.sourceOnboardingPanelClosedShowsOnlyTheOpenButton();
		WizardPageTest.sourceOnboardingPanelRendersChecklistAndNextAction();
		WizardPageTest.sourceOnboardingOpenConnectionUsesThePredicateRoute();
		WizardPageTest.sourceOnboardingWriteActionsDisabledForNonAdministrator();
		WizardPageTest.sampleFilesPanelClosedByDefaultShowsOnlyTheToggle();
		WizardPageTest.sampleFilesPanelWithReportRendersOpenWithBothOutcomes();
		WizardPageTest.feedTreeRendersRootAndIndentedChildFeed();
		WizardPageTest.loadStagingGateNamesTheMissingFeedCoverage();
		WizardPageTest.stepResetButtonPresentForAdminOnSteps3to6();
		WizardPageTest.stepResetButtonAbsentForNonAdministrator();
		WizardPageTest.stepResetConfirmPanelRendersPlanOnItsOwnStepOnly();
		WizardPageTest.stepResetRefusalPanelHasNoConfirmField();
		WizardPageTest.stepResetDonePanelShowsResultNoticeOnly();
		WizardPageTest.stagingLoadRunPrimaryButtonAndAdvancedBothRender();
		WizardPageTest.stagingLoadRunRunningShowsTheLivePanel();
		WizardPageTest.stagingLoadGridRendersRowsAndHeldBackBanner();
		WizardPageTest.masterBuildBeforeRunShowsExplainCardsAndPrimaryButton();
		WizardPageTest.masterBuildRunningShowsStageListAndArmsThePoll();
		WizardPageTest.masterBuildDoneShowsKpisGridAndOpenTableLink();
		WizardPageTest.landingRunDoneWhenATableIsAlreadyLoadedEvenWithNoLandingRunRecord();
		WizardPageTest.landingTableRowOffersTheChangeColumnSelector();
		WizardPageTest.landingRunAgainOffersFullRefreshOnlyOnARerun();
		WizardPageTest.landingRunNotDoneWhileAnyTickedTableStillNotLoaded();
		WizardPageTest.step4SideColumnReadsProposedWhenNothingIsAcceptedYet();
		WizardPageTest.step4SideColumnReadsAcceptedCountsOnceAnEntityIsAccepted();
		WizardPageTest.step5SideColumnShowsAHintWhenNothingIsLoadedInStagingYet();
		WizardPageTest.stepHeaderLineNumberAndNameAreTheirOwnSpans();
		WizardPageTest.continueToLandingCountsOnlySelectedTables();
		WizardPageTest.continueToLandingCtaHiddenWithoutATickedTable();
		WizardPageTest.continueToLandingCtaShownOnceATableIsTicked();
		WizardPageTest.continueToStagingCtaHiddenUntilEveryTickedTableIsLoaded();
		WizardPageTest.continueToMasterCtaHiddenUntilStagingIsLoaded();
		WizardPageTest.masterDoneViewJudgedOnMasterLoadNotGridRowCount();
		WizardPageTest.nextButtonDisabledWithReasonWhenNextStepBlocked();
		WizardPageTest.nextButtonEnabledWithNoTitleWhenNextStepReachable();

		// staging-still-to-decide lot (2026-09-27, project ret)
		WizardPageTest.stillToDecideCardAbsentWhenNothingIsLeft();
		WizardPageTest.stillToDecideCardGathersTheRetLeftovers();
		WizardPageTest.stillToDecideCardRendersInFrench();
		// lot P1 (2026-10-04): a derivation's parameters settled by a person in step 4's "To apply" list
		WizardPageTest.toApplyDerivationWithAToBeProvidedParameterShowsAnInputAndBlocksApply();
		WizardPageTest.toApplyDerivationWithALiteralShowsItPrefilledAndApplyIsAvailable();
		WizardPageTest.aDerivationWaitingForAParameterIsAskedInThePrimaryBlock();
		WizardPageTest.aProposedDerivationSaysItNeedsAParameterBeforeTheAccept();
		WizardPageTest.toApplyListsAnAppliedDerivationWhoseParametersChangedWithTheReapplyLabel();
		System.out.println("WizardPageTest OK");
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug seen on the deployed wizard: "3 applications, 19 tables"
	 * while only 4 tables were ticked): the step 2 "Continue to the landing" block must count/sum only
	 * {@code TableInfo#selected} tables - an unselected one is still listed (a person can re-tick it later)
	 * but is not actually going to the landing, so it must not inflate the table count or the row total.
	 */
	private static void continueToLandingCountsOnlySelectedTables()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_SOURCES;
		final WizardSnapshot.SourceInfo source = model.snapshot.sources.get(0);
		// demoSnapshot's own single table ("SUPPLIER") stays selected/known...
		source.tables.get(0).sourceRowCountKnown = true;
		source.tables.get(0).sourceRowCount = 100;
		// ...a second table is added, UNSELECTED - must not count towards either the table count or the row sum.
		final WizardSnapshot.TableInfo unselected = new WizardSnapshot.TableInfo();
		unselected.id = "11";
		unselected.tableName = "INVOICE";
		unselected.selected = false;
		unselected.sourceRowCountKnown = true;
		unselected.sourceRowCount = 900;
		source.tables.add(unselected);
		final String html = WizardPage.render(model);
		WizardPageTest.check("counts exactly 1 table (the selected one), never 2",
			html.contains(WizardMessages.countWords(Locale.ENGLISH, 1, "wizard.unit.table")));
		WizardPageTest.check("never counts the unselected table", !html.contains(WizardMessages.countWords(Locale.ENGLISH, 2, "wizard.unit.table")));
		WizardPageTest.check("sums only the selected table's own row count (100), never 1000",
			html.contains(WizardMessages.countWords(Locale.ENGLISH, 100, "wizard.unit.row")));
	}

	// --------------------------------------------------------------------------------------- step-gating lot

	/**
	 * Step-gating lot (2026-09-24): a project with a source that has no ticked table yet must not show the
	 * "Continue to the landing" call-to-action at all - one line names what remains instead, the exact same
	 * {@code wizard.blocked.needTickedTable} reason the rail's own step-3 tooltip shows.
	 */
	private static void continueToLandingCtaHiddenWithoutATickedTable()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.sources.get(0).tables.get(0).selected = false; // the only table of the only source, unticked
		model.snapshot.sources.get(1).tables.clear();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_SOURCES;
		final String html = WizardPage.render(model);
		WizardPageTest.check("no 'Continue to the landing' button", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.review.continue")));
		WizardPageTest.check("blocked reason shown instead", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.blocked.needTickedTable")));
	}

	/** Symmetric case: once one table is ticked, the real button renders instead of the blocked line. */
	private static void continueToLandingCtaShownOnceATableIsTicked()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot); // demoSnapshot's SUPPLIER table is selected by default
		model.currentStep = WizardRules.STEP_SOURCES;
		final String html = WizardPage.render(model);
		WizardPageTest.check("'Continue to the landing' button rendered", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.review.continue")));
		WizardPageTest.check("no blocked reason", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.blocked.needTickedTable")));
	}

	/**
	 * Step-gating lot: the step 3 "Continue to the staging" call-to-action used to show as soon as ANY
	 * selected table was loaded - now it needs EVERY ticked table loaded, and shows the blocked reason
	 * otherwise, even though the "done" view (at least one table loaded) is already showing.
	 */
	private static void continueToStagingCtaHiddenUntilEveryTickedTableIsLoaded()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.SourceInfo erp = model.snapshot.sources.get(0);
		erp.tables.get(0).status = "LOADED"; // one ticked table loaded: the "done" view already renders
		final WizardSnapshot.TableInfo secondTicked = new WizardSnapshot.TableInfo();
		secondTicked.id = "13";
		secondTicked.tableName = "PLANT";
		secondTicked.selected = true;
		secondTicked.status = "GENERATED"; // ticked, but not loaded yet
		erp.tables.add(secondTicked);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_LANDING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("no 'Continue to the staging' button while a ticked table is still not loaded",
			!html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.run.done.continue")));
		WizardPageTest.check("blocked reason shown instead", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.blocked.needLandingLoaded")));

		secondTicked.status = "LOADED";
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String loadedHtml = WizardPage.render(model);
		WizardPageTest.check("'Continue to the staging' button rendered once every ticked table is loaded",
			loadedHtml.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.run.done.continue")));
	}

	/**
	 * Step-gating lot: step 4's "Continue to the master" used to be a plain, always-shown hint sentence with
	 * no real button behind it - it is now a real GOTO_STEP button, shown only once the staging is loaded
	 * ({@code STAGING_LOAD DONE}), and a blocked-reason line otherwise.
	 */
	private static void continueToMasterCtaHiddenUntilStagingIsLoaded()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.stagingEntities.get(0).status = "ACCEPTED";
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		model.stagingLoadRunExists = true;
		final WizardSnapshot.RunGridInfo grid = new WizardSnapshot.RunGridInfo();
		grid.columnLabels.add("In staging");
		final WizardSnapshot.RunGridRow row = new WizardSnapshot.RunGridRow();
		row.label = "Supplier";
		row.cells.add(new WizardSnapshot.RunGridCell("DONE", "10 rows", ""));
		grid.rows.add(row);
		model.stagingLoadGrid = grid;
		final String html = WizardPage.render(model);
		WizardPageTest.check("no 'Continue to the master' button before the staging is loaded",
			!html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.build.continue")));
		WizardPageTest.check("UX lot U4: nothing greyed and no excuse line either - the block is simply not there", !WizardPageTest.section(html, 4).contains(WizardMessages.get(Locale.ENGLISH, "wizard.blocked.needStagingLoad")));

		model.snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String loadedHtml = WizardPage.render(model);
		WizardPageTest.check("'Continue to the master' button rendered once the staging is loaded",
			loadedHtml.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.build.continue")));
	}

	/**
	 * Step-gating lot, revised 2026-09-25 (task brief item 6, live incident on project DistribTest): step 5
	 * used to switch to the "built" (done) view as soon as {@code masterBuildGrid} was non-empty - but {@code
	 * MasterBuildGridReader.build} plans one row per ACCEPTED/APPLIED staging entity BEFORE any build even
	 * runs (every cell a dash), so a project with an accepted entity and no build yet ALREADY had a non-empty
	 * grid: the "Build and load" button never showed at all, the panel jumped straight to a done view with
	 * nothing built. Fixed: "built" is now judged on the MASTER_LOAD {@code PipelineStep} (the same evidence
	 * {@code stepStates[STEP_TRANSFERS].completed} already uses), never the grid's row count.
	 */
	private static void masterDoneViewJudgedOnMasterLoadNotGridRowCount()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_TRANSFERS;
		// the grid already has a planned row for the ACCEPTED "Account" entity - MasterBuildGridReader's own
		// real behaviour before any build has run (dashes in every cell), reproduced here rather than a
		// hand-picked "DONE" cell, so this test fails the way the live incident actually did if the fix regresses.
		final WizardSnapshot.RunGridInfo grid = new WizardSnapshot.RunGridInfo();
		grid.columnLabels.add("Tables");
		grid.columnLabels.add("Copy");
		grid.columnLabels.add("Matching ready");
		final WizardSnapshot.RunGridRow row = new WizardSnapshot.RunGridRow();
		row.label = "Account";
		row.cells.add(new WizardSnapshot.RunGridCell("PLANNED", "", ""));
		row.cells.add(new WizardSnapshot.RunGridCell("PLANNED", "", ""));
		row.cells.add(new WizardSnapshot.RunGridCell("PLANNED", "", ""));
		grid.rows.add(row);
		model.masterBuildGrid = grid;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the 'Build and load' button shows before MASTER_LOAD has run, even with a non-empty (planned) grid",
			html.contains("data-mdmf-action=\"MASTER_BUILD_RUN\""));
		WizardPageTest.check("no done-view title before the master is loaded", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.build.done.title")));
		WizardPageTest.check("no 'Continue to matching' button either", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.build.done.continue")));

		// once STAGING_LOAD and MASTER_LOAD are both genuinely DONE, the done view (grid + KPIs) renders, and
		// "Continue to matching" with it - Matching becomes unconditionally reachable once the master is loaded
		// (WizardRules.stepStates' own STEP_MATCHING branch), so the two are never independently blocked.
		model.snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		model.snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MASTER_LOAD", "DONE", ""));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String loadedHtml = WizardPage.render(model);
		WizardPageTest.check("done-view title rendered once the master is loaded",
			loadedHtml.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.build.done.title")));
		WizardPageTest.check("'Continue to matching' button rendered once the master is loaded",
			loadedHtml.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.build.done.continue")));
	}

	/** Step-gating lot: the bottom "Next" button carries the SAME reachable/blocked-reason pair as the rail and every CTA - never a separate computation. */
	private static void nextButtonDisabledWithReasonWhenNextStepBlocked()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.projectOnlySnapshot(); // a project, no source yet: step 3 (Landing) stays blocked
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_SOURCES;
		final String html = WizardPage.render(model);
		WizardPageTest.check("Next disabled", html.contains("id=\"mdmfWizNext\" disabled=\"disabled\""));
		WizardPageTest.check("Next carries the blocked reason as its title", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.blocked.needSource")));
	}

	private static void nextButtonEnabledWithNoTitleWhenNextStepReachable()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot(); // sources already ticked: step 3 (Landing) is reachable
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_SOURCES;
		final String html = WizardPage.render(model);
		WizardPageTest.check("Next enabled", html.contains("id=\"mdmfWizNext\">"));
		WizardPageTest.check("Next not disabled", !html.contains("id=\"mdmfWizNext\" disabled=\"disabled\""));
	}

	/** A saved project, no source yet - same shape as {@code WizardRulesTest#projectOnly}, local to this test class. */
	private static WizardSnapshot projectOnlySnapshot()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.code = "Companies";
		project.label = "Companies 360";
		snapshot.project = project;
		return snapshot;
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 6, measured live on project Personnes): a landing
	 * loaded before the one-click "Prepare the landing" run existed (or reloaded some other way) has no
	 * {@code LandingRun} record, but its tables are genuinely {@code LOADED} - step 3 must show the done view,
	 * not the plain intro again.
	 */
	private static void landingRunDoneWhenATableIsAlreadyLoadedEvenWithNoLandingRunRecord()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_LANDING;
		model.landingRunExists = false;
		model.landingRunRunning = false;
		model.snapshot.sources.get(0).tables.get(0).selected = true;
		model.snapshot.sources.get(0).tables.get(0).status = "LOADED";
		model.snapshot.sources.get(0).tables.get(0).lastLoadRows = 42;
		final String html = WizardPage.render(model);
		WizardPageTest.check("shows the done view's own title", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.run.done.title")));
		WizardPageTest.check("does NOT show the plain intro's four numbered cards", !html.contains("mdmf-landing-cards"));
	}

	/**
	 * Volume wave 2, lot V2-E (2026-10-03): a landing table with known columns offers its CHANGE column - a select of its own columns
	 * (the chosen one pre-selected, "none - reload in full" first), the kind with its own pre-selection, and a Save button naming the
	 * table; each option carries the kind its type reads as, which wizard.js uses to pre-select the kind when the column changes. A
	 * table with no known column offers none (nothing to choose from).
	 */
	private static void landingTableRowOffersTheChangeColumnSelector()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String without = WizardPage.render(model);
		WizardPageTest.check("no known column: no selector", !without.contains("mdmf-change-column"));
		final WizardSnapshot.TableInfo supplier = model.snapshot.sources.get(0).tables.get(0);
		supplier.columnNames.addAll(Arrays.asList("SUPPLIER_ID", "NAME1", "LAST_MODIFIED", "ROW_VERSION"));
		supplier.columnTypes.put("SUPPLIER_ID", "STRING");
		supplier.columnTypes.put("NAME1", "STRING");
		supplier.columnTypes.put("LAST_MODIFIED", "TIMESTAMP");
		supplier.columnTypes.put("ROW_VERSION", "INTEGER");
		final String none = WizardPage.render(model);
		WizardPageTest.check("the selector is there", none.contains("class=\"mdmf-change-column\"") && none.contains("class=\"mdmf-change-kind\""));
		WizardPageTest.check("'none' is offered first", none.contains("<select class=\"mdmf-change-column\"><option value=\"\">" + WizardMessages.get(Locale.ENGLISH, "wizard.step3.changeColumn.none")));
		WizardPageTest.check("nothing chosen: nothing selected, and the hint says every load reads the whole table",
			!none.contains("selected=\"selected\">LAST_MODIFIED") && none.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.changeColumn.hint.none")));
		WizardPageTest.check("each option carries the kind its type reads as",
			none.contains("<option value=\"LAST_MODIFIED\" data-kind=\"TIMESTAMP\">") && none.contains("<option value=\"ROW_VERSION\" data-kind=\"MONOTONIC_ID\">")
				&& none.contains("<option value=\"NAME1\" data-kind=\"ORDERED_STRING\">"));
		WizardPageTest.check("the Save button names its table and its inputs",
			none.contains("data-mdmf-action=\"TABLE_SET_CHANGE_COLUMN\"") && none.contains("data-mdmf-table-id=\"10\" data-mdmf-change-input=\"1\""));
		supplier.changeColumn = "LAST_MODIFIED";
		supplier.changeColumnKind = "TIMESTAMP";
		final String chosen = WizardPage.render(model);
		WizardPageTest.check("the chosen column and kind are pre-selected",
			chosen.contains("<option value=\"LAST_MODIFIED\" data-kind=\"TIMESTAMP\" selected=\"selected\">LAST_MODIFIED</option>")
				&& chosen.contains("<option value=\"TIMESTAMP\" selected=\"selected\">"));
		WizardPageTest.check("the hint says only changed rows are read", chosen.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.changeColumn.hint.set")));
		WizardPageTest.check("the selector is the same on a LOADED table (a column may change at any time)", WizardPageTest.withStatus(model, supplier, "LOADED").contains("class=\"mdmf-change-column\""));
		WizardPageTest.check("French labels exist for every new key", !WizardMessages.get(Locale.FRENCH, "wizard.step3.changeColumn").equals("wizard.step3.changeColumn")
			&& !WizardMessages.get(Locale.FRENCH, "wizard.step3.run.checkbox.fullRefresh").equals("wizard.step3.run.checkbox.fullRefresh")
			&& !WizardMessages.get(Locale.FRENCH, "wizard.step3.changeColumn.kind.ORDERED_STRING").equals("wizard.step3.changeColumn.kind.ORDERED_STRING"));
	}

	private static String withStatus(final WizardPage.Model pModel, final WizardSnapshot.TableInfo pTable, final String pStatus)
	{
		pTable.status = pStatus;
		return WizardPage.render(pModel);
	}

	/** The "Full refresh" checkbox only belongs to a RE-run (a first run reads everything anyway); it is a plain checkbox that wizard.js copies into mdmfLandingRunFullRefresh. */
	private static void landingRunAgainOffersFullRefreshOnlyOnARerun()
	{
		final WizardPage.Model first = WizardPageTest.model();
		first.snapshot = WizardPageTest.demoSnapshot();
		first.stepStates = WizardRules.stepStates(first.snapshot);
		first.currentStep = WizardRules.STEP_LANDING;
		final String intro = WizardPage.render(first);
		WizardPageTest.check("the first run has no full refresh checkbox", !intro.contains("mdmfLandingRunFullRefreshCheckbox"));
		WizardPageTest.check("the hidden field wizard.js fills is always there, false by default", intro.contains("id=\"mdmfLandingRunFullRefresh\"") && intro.contains("name=\"mdmfLandingRunFullRefresh\""));

		final WizardPage.Model again = WizardPageTest.model();
		again.snapshot = WizardPageTest.demoSnapshot();
		again.stepStates = WizardRules.stepStates(again.snapshot);
		again.currentStep = WizardRules.STEP_LANDING;
		again.snapshot.sources.get(0).tables.get(0).selected = true;
		again.snapshot.sources.get(0).tables.get(0).status = "LOADED";
		again.snapshot.sources.get(0).tables.get(0).lastLoadRows = 42;
		final String rerun = WizardPage.render(again);
		WizardPageTest.check("a re-run offers it, unchecked", rerun.contains("<input type=\"checkbox\" id=\"mdmfLandingRunFullRefreshCheckbox\"/>")
			&& rerun.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.run.checkbox.fullRefresh")));
	}

	/**
	 * fix/wizard-live-wave3 (2026-09-24, live bug item 3, measured live on project Vague1): a landing run that
	 * loaded SOME ticked tables but not others ("6 tables landed, 345 rows loaded in total" while 4 of the 6
	 * had never loaded) must NOT show the done view - or its row/table totals - until EVERY ticked table is
	 * LOADED; with a run to show progress from, it falls back to the running grid instead of the plain intro.
	 */
	private static void landingRunNotDoneWhileAnyTickedTableStillNotLoaded()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_LANDING;
		model.landingRunExists = true;
		model.landingRunRunning = false;
		model.snapshot.sources.get(0).tables.get(0).selected = true;
		model.snapshot.sources.get(0).tables.get(0).status = "LOADED";
		model.snapshot.sources.get(0).tables.get(0).lastLoadRows = 345;
		// a second, still-not-loaded ticked table on the other source
		final WizardSnapshot.TableInfo stillWaiting = new WizardSnapshot.TableInfo();
		stillWaiting.id = "20";
		stillWaiting.tableName = "CONTACT";
		stillWaiting.selected = true;
		stillWaiting.status = "PROPOSED";
		stillWaiting.lastLoadRows = 0;
		model.snapshot.sources.get(1).tables.add(stillWaiting);
		final List<LandingRunProgress.TableRef> refs = new ArrayList<LandingRunProgress.TableRef>();
		refs.add(new LandingRunProgress.TableRef("10", "SUPPLIER", "ERP"));
		refs.add(new LandingRunProgress.TableRef("20", "CONTACT", "CRM"));
		model.landingRunProgress = LandingRunProgress.newRun(refs);
		final String html = WizardPage.render(model);
		WizardPageTest.check("does NOT show the done view's title", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.run.done.title")));
		WizardPageTest.check("falls back to the running grid (a run exists) instead of the plain intro",
			!html.contains("mdmf-landing-cards") && html.contains("id=\"mdmfLandingLive\"")
				&& html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.run.live.titleEnded")));
		WizardPageTest.check("a run that is over offers no Stop", !html.contains("data-mdmf-action=\"LANDING_RUN_STOP\""));
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 8, measured live on project Personnes): with every
	 * entity still PROPOSED (nothing accepted), the side card must read "N business object(s) proposed,
	 * waiting for review", never claim anything is "accepted" when nothing is.
	 */
	private static void step4SideColumnReadsProposedWhenNothingIsAcceptedYet()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		// demoSnapshot's "Account" entity is ACCEPTED by default (a different fixture's own need) - forced back
		// to PROPOSED here so this test genuinely covers "nothing accepted yet".
		for (final WizardSnapshot.StagingEntityInfo entity : model.snapshot.stagingEntities)
		{
			entity.status = "PROPOSED";
		}
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		// UX lot U3 (2026-10-13): the card is one row per nature from Step4Counts - "Business objects 2: 2 waiting for review"
		WizardPageTest.check("reads 'Business objects 2: 2 waiting for review'",
			html.contains("<b>" + WizardMessages.get(Locale.ENGLISH, "wizard.step4.counts.businessObjects") + "</b> 2: ")
				&& html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.counts.waiting", Integer.valueOf(2))));
		// nothing accepted: no "accepted" part on that row at all, not just "with a 0 count"
		WizardPageTest.check("never shows an 'accepted' part for the business objects when nothing is accepted",
			!html.contains("</b> 2: " + WizardMessages.get(Locale.ENGLISH, "wizard.step4.counts.accepted", Integer.valueOf(0)))
				&& !html.contains("</b> 2: " + WizardMessages.get(Locale.ENGLISH, "wizard.step4.counts.accepted", Integer.valueOf(2))));
	}

	/** Same fixture: {@code demoSnapshot}'s "Account" entity is ACCEPTED by default - the side card must show the real "N · N · N · N accepted" summary, not the "proposed" wording. */
	private static void step4SideColumnReadsAcceptedCountsOnceAnEntityIsAccepted()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		// only the ACCEPTED "Account" entity counts as accepted - the PROPOSED "Supplier" entity waits, that is this test's whole point.
		// UX lot U3 (2026-10-13): the business-object row of Step4Counts says both ("Business objects 2: 1 accepted, 1 waiting for review")
		WizardPageTest.check("the business-object row counts the accepted entity apart from the waiting one",
			html.contains("<b>" + WizardMessages.get(Locale.ENGLISH, "wizard.step4.counts.businessObjects") + "</b> 2: "
				+ WizardMessages.get(Locale.ENGLISH, "wizard.step4.counts.accepted", Integer.valueOf(1)) + ", "));
		WizardPageTest.check("no second, disagreeing field count in the headline", !html.contains("2 fields \u00B7"));
	}

	/** fix/wizard-live-wave1 (2026-09-24, live bug item 9): the side card must never render an eyebrow with an empty body. */
	private static void step5SideColumnShowsAHintWhenNothingIsLoadedInStagingYet()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_TRANSFERS;
		// model.stagingLoadGrid/masterBuildGrid both default to an empty RunGridInfo() - "before a build, nothing
		// loaded in staging yet" is exactly the state this fixture leaves untouched.
		final String html = WizardPage.render(model);
		WizardPageTest.check("the side card's own eyebrow is present", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.side.fromStaging.title")));
		WizardPageTest.check("shows the one-line hint instead of an empty body",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.side.fromStaging.empty")));
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 3): wizard.js's client-side GOTO_STEP switch updates the
	 * topbar's "Step n of 7 - name" line by replacing just these two spans (never re-parsing/rebuilding the
	 * localized sentence) - they must exist, with the current step's own number and name, in every render.
	 */
	private static void stepHeaderLineNumberAndNameAreTheirOwnSpans()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the step number is its own span", html.contains("<span class=\"mdmf-wiz-stepline-n\">4</span>"));
		WizardPageTest.check("the step name is its own span", html.contains("<span class=\"mdmf-wiz-stepline-name\">"
			+ WizardMessages.get(Locale.ENGLISH, "wizard.step.staging") + "</span>"));
	}

	private static void blankModelShowsOnlyStep1()
	{
		final WizardPage.Model model = WizardPageTest.model();
		final String html = WizardPage.render(model);
		WizardPageTest.check("step 1 panel is present and visible", html.indexOf("data-step=\"1\">") >= 0 || html.contains("data-step=\"1\"><h2>"));
		WizardPageTest.check("step 2 panel is hidden", html.contains("data-step=\"2\" hidden=\"hidden\">"));
		WizardPageTest.check("step 7 panel is hidden", html.contains("data-step=\"7\" hidden=\"hidden\">"));
	}

	private static void currentStepPanelIsTheOnlyOneVisible()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("step 4 not hidden", !html.contains("data-step=\"4\" hidden=\"hidden\">"));
		WizardPageTest.check("step 1 hidden", html.contains("data-step=\"1\" hidden=\"hidden\">"));
		WizardPageTest.check("step 3 hidden", html.contains("data-step=\"3\" hidden=\"hidden\">"));
		WizardPageTest.check("hidden mdmfStep field carries the current step", html.contains("id=\"mdmfStep\" name=\"mdmfStep\" value=\"4\""));
	}

	private static void blockedStepButtonIsDisabledWithReason()
	{
		final WizardPage.Model model = WizardPageTest.model();
		final String html = WizardPage.render(model);
		WizardPageTest.check("sources step button is disabled on a blank project", html.contains("data-mdmf-goto-step=\"2\" disabled=\"disabled\""));
		WizardPageTest.check("the blocked reason is in the title attribute",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.blocked.needProject")));
	}

	private static void fieldValuesAreEscaped()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.projectDraft.code = "X";
		model.projectDraft.label = "a&b<script>alert(1)</script>";
		final String html = WizardPage.render(model);
		WizardPageTest.check("no raw script tag leaked", html.indexOf("<script>alert(1)</script>") < 0);
		WizardPageTest.check("the label is present, escaped", html.contains("a&amp;b&lt;script&gt;"));
	}

	private static void fieldErrorIsShownNextToItsField()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.problems.add(new WizardRules.Problem(WizardRules.STEP_PROJECT, "label", "wizard.error.labelRequired"));
		final String html = WizardPage.render(model);
		WizardPageTest.check("the label error message is rendered", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.error.labelRequired")));
	}

	private static void onlyConsolidationRadioIsEnabled()
	{
		final String html = WizardPage.render(WizardPageTest.model());
		final int consolidationIndex = html.indexOf(WizardMessages.get(Locale.ENGLISH, "wizard.style.consolidation.label"));
		WizardPageTest.check("consolidation label is rendered", consolidationIndex >= 0);
		WizardPageTest.check("Consolidation's radio posts (name + value)", html.contains("name=\"mdmfStyleRadio\" value=\"CONSOLIDATION\" checked=\"checked\""));
		WizardPageTest.check("Registry's radio is disabled", html.contains("name=\"mdmfStyleRadio\" value=\"REGISTRY\" disabled=\"disabled\""));
		WizardPageTest.check("Centralisation's radio is disabled (coming soon since 2026-11-10)", html.contains("name=\"mdmfStyleRadio\" value=\"CENTRALISATION\" disabled=\"disabled\""));
		WizardPageTest.check("Coexistence's radio is disabled", html.contains("name=\"mdmfStyleRadio\" value=\"COEXISTENCE\" disabled=\"disabled\""));
		WizardPageTest.check("exactly four style radios share the same name (one native radiogroup)", WizardPageTest.count(html, "mdmfStyleRadio\" value=\"") == 4);
	}

	/**
	 * The old "Authoring:" row became the bold plain-words headline ("Donnée créée dans : ...") - the first
	 * fact a reader sees, not a labelled row among others (product owner, 2026-09-21). Every card still
	 * shows the same two remaining labelled facts (golden record, flows) plus its own headline and diagram.
	 */
	private static void everyStyleCardShowsItsHeadlineAndTwoLabelledFacts()
	{
		final String html = WizardPage.render(WizardPageTest.model());
		WizardPageTest.check("golden-record row label appears 4 times", WizardPageTest.count(html, WizardMessages.get(Locale.ENGLISH, "wizard.style.row.golden")) == 4);
		WizardPageTest.check("flows row label appears 4 times", WizardPageTest.count(html, WizardMessages.get(Locale.ENGLISH, "wizard.style.row.flows")) == 4);
		WizardPageTest.check("when-to-choose-it label appears 4 times", WizardPageTest.count(html, WizardMessages.get(Locale.ENGLISH, "wizard.style.when.label")) == 4);
		WizardPageTest.check("every card gets its own diagram (4 inline svg diagrams)", WizardPageTest.count(html, "class=\"mdmf-style-diagram\"") == 4);
		for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			WizardPageTest.check(style.code + "'s headline is rendered",
				html.contains("<p class=\"mdmf-style-card-headline\">" + WizardPage.esc(WizardMessages.get(Locale.ENGLISH, style.headlineKey)) + "</p>"));
		}
	}

	/** Product owner correction (2026-09-21): keep the axis, but flag Consolidation as the default/most common - and only Consolidation. */
	private static void onlyConsolidationCardShowsTheMostCommonChip()
	{
		final String html = WizardPage.render(WizardPageTest.model());
		final String chip = "<span class=\"mdmf-chip mdmf-chip-accent mdmf-style-card-common\">" + WizardMessages.get(Locale.ENGLISH, "wizard.style.mostCommon") + "</span>";
		WizardPageTest.check("the 'most common' chip appears exactly once (Consolidation only)", WizardPageTest.count(html, chip) == 1);
	}

	/** The chip marking a card as not-yet-available sits in its own element, never woven into a sentence. */
	private static void unavailableChipIsNeverInsideASentence()
	{
		final String html = WizardPage.render(WizardPageTest.model());
		final String chip = "<span class=\"mdmf-choice-card-chip mdmf-chip mdmf-chip-target\">" + WizardMessages.get(Locale.ENGLISH, "wizard.notYet") + "</span>";
		// audit F-40: the target-exists mode card is gone (not built), the three planned style cards keep theirs
		WizardPageTest.check("the chip markup appears once per style card not built (2: Coexistence, Registry; Centralisation is built since lot C1-B)", WizardPageTest.count(html, chip) == 2);
		final String soon = "<span class=\"mdmf-choice-card-chip mdmf-chip mdmf-chip-target\">" + WizardMessages.get(Locale.ENGLISH, "wizard.comingSoon") + "</span>";
		WizardPageTest.check("the 'coming soon' chip appears exactly once (Centralisation, 2026-11-10)", WizardPageTest.count(html, soon) == 1);
		WizardPageTest.check("the coming-soon card sits in the main row, before the fold", html.indexOf(soon) < html.indexOf("mdmf-style-later"));
	}

	/** The selected style card is the project's own (from the snapshot/draft), not always Consolidation by accident of rendering order. */
	private static void selectedStyleCardMatchesTheDraft()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.projectDraft.style = "CENTRALISATION"; // a stale/forged value the snapshot could carry; still rendered honestly
		final String html = WizardPage.render(model);
		// 2026-11-10: the card is also disabled (coming soon), but a project that carries the style still sees it selected
		WizardPageTest.check("Centralisation's card is marked selected", html.contains("mdmf-choice-card mdmf-style-card selected mdmf-choice-card-disabled\"><input type=\"radio\" name=\"mdmfStyleRadio\" value=\"CENTRALISATION\" checked=\"checked\" disabled=\"disabled\"/>"));
		WizardPageTest.check("Consolidation's radio is no longer checked", !html.contains("value=\"CONSOLIDATION\" checked=\"checked\""));
		// the consequence sentence follows the selection too, not hard-coded to Consolidation
		WizardPageTest.check("the consequence sentence shown is Centralisation's own",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.style.centralisation.consequence")));
	}

	/** Staging mode reuses the same card pattern (GREENFIELD available, TARGET_EXISTS disabled with the chip), no diagram. */
	private static void stagingModeCardsUseTheSameCardPattern()
	{
		final String html = WizardPage.render(WizardPageTest.model());
		WizardPageTest.check("GREENFIELD card posts and is checked", html.contains("name=\"mdmfModeRadio\" value=\"GREENFIELD\" checked=\"checked\""));
		// audit F-40: the mode that is not built is no longer offered at all
		WizardPageTest.check("no TARGET_EXISTS card", !html.contains("value=\"TARGET_EXISTS\""));
	}

	/** Lot C2: the jobs banner exists only for a saved project, carries its encoded REST route, and flags a render "in progress". */
	private static void jobsBannerFollowsTheProject()
	{
		final WizardPage.Model blank = WizardPageTest.model();
		WizardPageTest.check("no project, no jobs banner", !WizardPage.render(blank).contains("id=\"mdmfJobs\""));
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot.project = new WizardSnapshot.ProjectInfo();
		model.snapshot.project.code = "A B&C";
		model.snapshot.project.label = "A";
		String html = WizardPage.render(model);
		WizardPageTest.check("jobs banner route is URL-encoded then HTML-escaped",
			html.contains("data-mdmf-jobs-url=\"/ebx-mdm-factory/rest/v1/projects/A%20B%26C/jobs?lang=en\"")); // run panel i18n (2026-10-17): the poll asks for the page's language
		WizardPageTest.check("idle project: banner not flagged active", !html.contains("data-mdmf-jobs-active"));
		model.masterBuildRunRunning = true;
		html = WizardPage.render(model);
		WizardPageTest.check("running master build: banner flagged active", html.contains("data-mdmf-jobs-active=\"true\""));
	}

	private static void existingProjectCodeIsReadOnly()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot.project = new WizardSnapshot.ProjectInfo();
		model.snapshot.project.code = "Companies";
		model.snapshot.project.label = "Companies 360";
		model.projectDraft.code = "Companies";
		model.projectDraft.label = "Companies 360";
		final String html = WizardPage.render(model);
		WizardPageTest.check("the code field is readonly once the project exists", html.contains("id=\"mdmfCode\" value=\"Companies\" readonly=\"readonly\"/>"));
		WizardPageTest.check("the save button reads Save, not Create", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step1.save.update")));
	}

	// ------------------------------------------------------------------------------- language-model value sharing (step 1)

	private static void llmSharingControlDefaultsToShapesOnlyWithTheSampleFieldHidden()
	{
		final String html = WizardPage.render(WizardPageTest.model());
		WizardPageTest.check("the control's label is rendered in step 1", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing")));
		WizardPageTest.check("NONE card posts and is enabled", html.contains("name=\"mdmfLlmSharingRadio\" value=\"NONE\"/>"));
		WizardPageTest.check("SHAPES_ONLY is checked by default", html.contains("name=\"mdmfLlmSharingRadio\" value=\"SHAPES_ONLY\" checked=\"checked\"/>"));
		WizardPageTest.check("SAMPLE_VALUES is disabled under the snapshot's default SHAPES_ONLY cap",
			html.contains("name=\"mdmfLlmSharingRadio\" value=\"SAMPLE_VALUES\" disabled=\"disabled\"/>"));
		WizardPageTest.check("the sample-size field is rendered hidden", html.contains("id=\"mdmfLlmSampleSizeField\" hidden=\"hidden\">"));
		WizardPageTest.check("the sample-size input carries the default 20",
			html.contains("id=\"mdmfLlmSampleSize\" name=\"mdmfLlmSampleSize\" min=\"1\" max=\"50\" step=\"1\" value=\"20\""));
		WizardPageTest.check("the consequence line reads the SHAPES_ONLY sentence",
			html.contains(">" + WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing.consequence.SHAPES_ONLY")) + "</p>"));
		WizardPageTest.check("every level's sentence is carried for the live swap", html.contains("data-mdmf-llm-none=\"") && html.contains("data-mdmf-llm-shapes_only=\"")
			&& html.contains("data-mdmf-llm-sample_values=\""));
		WizardPageTest.check("no cap notice when the choice is within the cap", !html.contains("mdmf-llm-capped\">"));
	}

	private static void llmSharingSampleValuesShowsTheSizeAndNamesTheConnector()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot.llmValueSharingMax = "SAMPLE_VALUES";
		model.snapshot.llmConfigured = true;
		model.snapshot.llmConnectorLabel = "Azure & co";
		model.projectDraft.llmValueSharing = "SAMPLE_VALUES";
		model.projectDraft.llmSampleSize = "7";
		final String html = WizardPage.render(model);
		WizardPageTest.check("SAMPLE_VALUES is checked and enabled", html.contains("name=\"mdmfLlmSharingRadio\" value=\"SAMPLE_VALUES\" checked=\"checked\"/>"));
		WizardPageTest.check("the sample-size field is shown", html.contains("id=\"mdmfLlmSampleSizeField\">"));
		WizardPageTest.check("the typed size is re-shown", html.contains("value=\"7\" autocomplete=\"off\"/>"));
		final String expected = WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing.consequence.SAMPLE_VALUES", "7", "Azure & co");
		WizardPageTest.check("the consequence names N and the connector, escaped", html.contains(">" + WizardPage.esc(expected) + "</p>"));
		WizardPageTest.check("the live template keeps {0} for the size", html.contains("data-mdmf-llm-sample_values=\"" + WizardPage.esc(
			WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing.consequence.SAMPLE_VALUES", "{0}", "Azure & co")) + "\""));
		WizardPageTest.check("no cap chip on any card", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing.cappedChip")));

		model.snapshot.llmConfigured = false;
		model.snapshot.llmConnectorLabel = "";
		WizardPageTest.check("no connector yet: the sentence says so instead of a blank",
			WizardPage.render(model).contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing.noConnector"))));
	}

	/** Edit of an existing project: the STORED choice (ProjectInfo.llmValueSharing) is what the service copies into the draft - mirrored here the way ProjectWizardUserService#model does it. */
	private static void llmSharingControlOnAnExistingProjectShowsTheStoredChoice()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot.llmValueSharingMax = "SAMPLE_VALUES";
		model.snapshot.project = new WizardSnapshot.ProjectInfo();
		model.snapshot.project.code = "Companies";
		model.snapshot.project.label = "Companies 360";
		model.snapshot.project.llmValueSharing = "NONE";
		model.snapshot.project.llmSampleSize = 12;
		model.projectDraft.code = "Companies";
		model.projectDraft.label = "Companies 360";
		model.projectDraft.llmValueSharing = model.snapshot.project.llmValueSharing;
		model.projectDraft.llmSampleSize = String.valueOf(model.snapshot.project.llmSampleSize);
		final String html = WizardPage.render(model);
		WizardPageTest.check("NONE is checked on the existing project", html.contains("name=\"mdmfLlmSharingRadio\" value=\"NONE\" checked=\"checked\"/>"));
		WizardPageTest.check("SHAPES_ONLY is no longer checked", !html.contains("value=\"SHAPES_ONLY\" checked=\"checked\""));
		WizardPageTest.check("the stored size is carried (hidden, since NONE)",
			html.contains("id=\"mdmfLlmSampleSizeField\" hidden=\"hidden\">") && html.contains("value=\"12\" autocomplete"));
		WizardPageTest.check("the consequence reads the NONE sentence",
			html.contains(">" + WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing.consequence.NONE")) + "</p>"));
		WizardPageTest.check("the control sits on the same step as the Save button of an existing project",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step1.save.update")));
	}

	/** The cap rule: cards above FactorySettings.llmValueSharingMax are disabled with a chip; a stored/posted choice above it is said out loud and the EFFECTIVE level is what is checked. */
	private static void llmSharingCapDisablesHigherCardsAndSaysSoWhenTheChoiceIsAbove()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot.llmValueSharingMax = "SHAPES_ONLY";
		model.projectDraft.llmValueSharing = "SAMPLE_VALUES";
		String html = WizardPage.render(model);
		WizardPageTest.check("SAMPLE_VALUES is disabled under a SHAPES_ONLY cap", html.contains("name=\"mdmfLlmSharingRadio\" value=\"SAMPLE_VALUES\" disabled=\"disabled\"/>"));
		WizardPageTest.check("the disabled card carries the capped chip", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing.cappedChip")));
		WizardPageTest.check("the checked card is the effective level, SHAPES_ONLY", html.contains("name=\"mdmfLlmSharingRadio\" value=\"SHAPES_ONLY\" checked=\"checked\"/>"));
		WizardPageTest.check("SAMPLE_VALUES is never checked while disabled", !html.contains("value=\"SAMPLE_VALUES\" checked=\"checked\""));
		final String note = WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing.capped", WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing.SHAPES_ONLY"),
			WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing.SAMPLE_VALUES"));
		WizardPageTest.check("a visible note names the cap level and what was asked", html.contains("mdmf-llm-capped\">" + WizardPage.esc(note) + "</div>"));
		WizardPageTest.check("the sample-size field stays hidden (nothing is sampled)", html.contains("id=\"mdmfLlmSampleSizeField\" hidden=\"hidden\">"));
		WizardPageTest.check("the consequence line reads the effective SHAPES_ONLY sentence",
			html.contains(">" + WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.field.llmSharing.consequence.SHAPES_ONLY")) + "</p>"));

		model.snapshot.llmValueSharingMax = "NONE";
		model.projectDraft.llmValueSharing = "SHAPES_ONLY";
		html = WizardPage.render(model);
		WizardPageTest.check("NONE cap: SHAPES_ONLY disabled too", html.contains("value=\"SHAPES_ONLY\" disabled=\"disabled\"/>"));
		WizardPageTest.check("NONE cap: SAMPLE_VALUES disabled", html.contains("value=\"SAMPLE_VALUES\" disabled=\"disabled\"/>"));
		WizardPageTest.check("NONE cap: NONE checked", html.contains("value=\"NONE\" checked=\"checked\"/>"));
		WizardPageTest.check("NONE cap: the note is shown for the SHAPES_ONLY ask", html.contains("mdmf-llm-capped\">"));

		model.projectDraft.llmValueSharing = "NONE";
		WizardPageTest.check("a choice within the cap shows no note", !WizardPage.render(model).contains("mdmf-llm-capped\">"));

		// French: the note's key carries apostrophes next to placeholders (MessageFormat doubling) - it must
		// format with both labels substituted and no doubled apostrophe leaking into the page
		model.locale = Locale.FRENCH;
		model.projectDraft.llmValueSharing = "SHAPES_ONLY";
		final String fr = WizardPage.render(model);
		final String frNote = WizardMessages.get(Locale.FRENCH, "wizard.field.llmSharing.capped", WizardMessages.get(Locale.FRENCH, "wizard.field.llmSharing.NONE"),
			WizardMessages.get(Locale.FRENCH, "wizard.field.llmSharing.SHAPES_ONLY"));
		WizardPageTest.check("French note is rendered formatted", fr.contains("mdmf-llm-capped\">" + WizardPage.esc(frNote) + "</div>"));
		WizardPageTest.check("French note: no doubled apostrophe leaks", !frNote.contains("''") && frNote.indexOf('{') < 0);
	}

	private static void llmSharingFieldErrorsShowNextToTheirField()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot.llmValueSharingMax = "SAMPLE_VALUES";
		model.projectDraft.llmValueSharing = "SAMPLE_VALUES";
		model.projectDraft.llmSampleSize = "0";
		model.problems.add(new WizardRules.Problem(WizardRules.STEP_PROJECT, "llmSampleSize", "wizard.error.llmSampleSizeInvalid"));
		model.problems.add(new WizardRules.Problem(WizardRules.STEP_PROJECT, "llmValueSharing", "wizard.error.llmSharingCapped"));
		final String html = WizardPage.render(model);
		WizardPageTest.check("the sample-size error is rendered", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.error.llmSampleSizeInvalid")));
		WizardPageTest.check("the level error is rendered", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.error.llmSharingCapped")));
		WizardPageTest.check("the bad size is re-shown, not reset", html.contains("value=\"0\" autocomplete=\"off\"/>"));
	}

	private static void sourceCardsAndAddFormRender()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.snapshot.catalogSources.add(new WizardSnapshot.CatalogSourceInfo("SNOW_DEMO", "Snowflake demo", "Snowflake", "EbxSnowflake"));
		final String html = WizardPage.render(model);
		// sources-landing lot SOURCES, visual pass (2026-09-24): the per-source name moved from an <h3> into
		// the review card's own header <b> (WizardPage.renderReviewCard), and the free per-card trust-rank
		// input moved into the sidebar's own ordered up/down list (SOURCE_TRUST_UP/DOWN,
		// WizardPage.renderTrustOrderAndGlossary) - "wizard.step2.applications" (the old counter heading) is
		// no longer rendered at all, superseded by the mockup-matching layout.
		// live fix (2026-09-24, project A): the header <b> is now the readable catalog label
		// (WizardPage.displayName), not the raw ProjectSource.sourceSystem code - see
		// docs/SOURCES-LANDING-SOURCES.md "Live fixes" for the live PERSONNES_SALESFORCE/PERSONNES_SAP_SAMPLE
		// measurement this fixes.
		WizardPageTest.check("both applications are listed", html.contains(">Snowflake demo (ERP)</b>") && html.contains(">Snowflake demo (CRM)</b>"));
		WizardPageTest.check("trust order sidebar offers up/down for both sources",
			html.contains("data-mdmf-action=\"SOURCE_TRUST_UP\" data-mdmf-source-id=\"") && html.contains("data-mdmf-action=\"SOURCE_TRUST_DOWN\" data-mdmf-source-id=\""));
		WizardPageTest.check("the add-application form offers the catalog source", html.contains("Snowflake demo"));
		WizardPageTest.check("Remove shows on every card, generated source included",
			html.contains("data-mdmf-action=\"SOURCE_REMOVE\" data-mdmf-source-id=\"1\"") && html.contains("data-mdmf-action=\"SOURCE_REMOVE\" data-mdmf-source-id=\"2\""));
		WizardPageTest.check("Remove carries a confirmation message naming the application", html.contains("data-mdmf-confirm=\"Remove Snowflake demo (ERP) from this project?"));
	}

	/**
	 * Fix (2026-09-27, project A, Personnes_SAP_Sample): the deduced-scan button is offered on a tableless card only when DeducedScanRun's LIST
	 * stage would accept the connection (SourceInfo#deducedScan). UX lot U2 (2026-10-13): a connection with a catalog scan gets "Scan again" and,
	 * when the catalog holds tables with columns, "Find its tables" - with the reason of every object that has no column, in plain words.
	 */
	/**
	 * UX lot C4 (2026-10-14), project UxCrmSf3: DemoCRM's 2 tables were CONFIRMED earlier and nothing planned them. The card offered "Find its tables" (a
	 * deduced scan, again, three times) and the finished run stacked a second card of the same application. Now: "List its tables" is the call to action,
	 * "Read its data again" a link, no sentence points at a button that is not there, and the finished run is a fold of the application's card.
	 */
	private static void decidedDeducedTablesAreListedNotScannedAgain()
	{
		final String start = "data-mdmf-action=\"DEDUCED_SCAN_START\" data-mdmf-source-id=\"2\"";
		final String find = "mdmf-btn-primary\" data-mdmf-action=\"SOURCE_FIND_TABLES\" data-mdmf-source-id=\"2\"";
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final WizardSnapshot.SourceInfo crm = model.snapshot.sources.get(1);
		crm.connector = "DemoCRM";
		crm.deducedScan = DeducedScanEligibility.OFFERED;

		// still to confirm: the old behaviour (a scan, and the sentence that points at its button)
		crm.unplannedAssets.add(new UnplannedAsset("Company", true, "CONFIRMED"));
		crm.unplannedAssets.add(new UnplannedAsset("Contact", true, "TO_CONFIRM"));
		String html = WizardPage.render(model);
		WizardPageTest.check("a table still to confirm: no 'List its tables'", !html.contains(find) && !html.contains("List its tables"));
		WizardPageTest.check("a table still to confirm: the scan button stays", html.contains("class=\"mdmf-btn mdmf-btn-sm\" " + start));

		crm.unplannedAssets.clear();
		crm.unplannedAssets.add(new UnplannedAsset("Company", true, "CONFIRMED"));
		crm.unplannedAssets.add(new UnplannedAsset("Contact", true, "CONFIRMED"));
		html = WizardPage.render(model);
		WizardPageTest.check("every table decided: 'List its tables' is the primary button (the plan)", html.contains(find) && html.contains("List its tables"));
		WizardPageTest.check("the scan moves to a secondary link, 'Read its data again'", html.contains("class=\"mdmf-linkbtn\" " + start) && html.contains("Read its data again"));
		WizardPageTest.check("no scan BUTTON left in the header", !html.contains("class=\"mdmf-btn mdmf-btn-sm\" " + start));
		WizardPageTest.check("never 'Press Find its tables above' when the tables exist", !html.contains("Press &quot;Find its tables&quot; above") && !html.contains("Press \"Find its tables\" above"));
		WizardPageTest.check("the sentence says what to do next", html.contains("Its tables were found from the data and you have decided them."));

		// the finished runs: one fold in the application's card, no second card
		model.snapshot.sources.get(1).tables.clear();
		final DeducedScanProgress progress = DeducedScanProgress.newRun(java.util.Arrays.asList("2"));
		progress.start("2", 1L);
		progress.stage("2", DeducedScanProgress.LIST, DeducedScanProgress.DONE, "the model chose listCompanies, listContacts");
		progress.finish("2", DeducedScanProgress.DONE, "2 table(s) up to date, decisions kept.", 2L, new java.util.ArrayList<String>());
		model.deducedScanCards.add(new WizardPage.Model.DeducedScanCard("2", "DEMOCRM", progress.entry("2"), 2, 0, 0));
		html = WizardPage.render(model);
		WizardPageTest.check("a finished run is no card of its own", !html.contains("<div class=\"mdmf-panel mdmf-src2-app-card\" id=\"mdmf-deduced-2\">"));
		WizardPageTest.check("its stages are folded in the application's card", html.contains("How the tables were found") && html.contains("mdmf-src2-pipe"));
		WizardPageTest.check("the fold sits inside the card of source 2", html.indexOf("id=\"mdmf-app-card-2\"") < html.indexOf("How the tables were found"));

		model.deducedScanCards.clear();
		final DeducedScanProgress failed = DeducedScanProgress.newRun(java.util.Arrays.asList("2"));
		failed.start("2", 1L);
		failed.finish("2", DeducedScanProgress.FAILED, "No table could be deduced.", 2L, new java.util.ArrayList<String>());
		model.deducedScanCards.add(new WizardPage.Model.DeducedScanCard("2", "DEMOCRM", failed.entry("2"), 0, 0, 0));
		html = WizardPage.render(model);
		WizardPageTest.check("a failed run keeps its own card (the reason)", html.contains("<div class=\"mdmf-panel mdmf-src2-app-card\" id=\"mdmf-deduced-2\">") && html.contains("No table could be deduced."));
		WizardPageTest.check("and no fold", !html.contains("How the tables were found"));
	}

	private static void deducedScanButtonFollowsTheRunsOwnRule()
	{
		final String start = "data-mdmf-action=\"DEDUCED_SCAN_START\" data-mdmf-source-id=\"2\"";
		final String find = "data-mdmf-action=\"SOURCE_FIND_TABLES\" data-mdmf-source-id=\"2\"";
		final String rescan = "data-mdmf-action=\"SOURCE_RESCAN\" data-mdmf-source-id=\"2\"";

		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final WizardSnapshot.SourceInfo crm = model.snapshot.sources.get(1);
		crm.connector = "SnowConn";
		WizardPageTest.check("fixture: the CRM application has no table", crm.tables.isEmpty());

		String html = WizardPage.render(model);
		WizardPageTest.check("not checked: no deduced-scan button", !html.contains(start));
		WizardPageTest.check("not checked: the scan can be asked again", html.contains(rescan));

		crm.deducedScan = DeducedScanEligibility.OFFICIAL_SCAN;
		crm.unplannedAssets.add(new UnplannedAsset("Account", false));
		html = WizardPage.render(model);
		WizardPageTest.check("catalog scan: no deduced-scan button", !html.contains(start));
		WizardPageTest.check("catalog scan: the object without a column is named with its reason", html.contains("<li><b>Account</b> - No column scanned yet.</li>"));
		WizardPageTest.check("catalog scan: scanning again is the call to action (primary)", html.contains("mdmf-btn-primary\" data-mdmf-action=\"SOURCE_RESCAN\" data-mdmf-source-id=\"2\""));
		WizardPageTest.check("catalog scan: no 'find its tables' while no object has a column", !html.contains(find));
		for (final String word : new String[] { "official scan", "deduced scan", "landing plan" })
		{
			WizardPageTest.check("no internal word in the card: " + word, !html.toLowerCase(Locale.ENGLISH).contains(word));
		}

		crm.unplannedAssets.add(new UnplannedAsset("Contact", true));
		html = WizardPage.render(model);
		WizardPageTest.check("a table with columns: 'Find its tables' is the call to action", html.contains("mdmf-btn-primary\" data-mdmf-action=\"SOURCE_FIND_TABLES\" data-mdmf-source-id=\"2\""));
		WizardPageTest.check("the other object still says why", html.contains("<li><b>Account</b> - No column scanned yet.</li>") && !html.contains("<b>Contact</b> - "));

		crm.deducedScan = DeducedScanEligibility.UNREACHABLE;
		crm.deducedScanError = "Unknown connector <X>";
		html = WizardPage.render(model);
		WizardPageTest.check("unreachable: no deduced-scan button", !html.contains(start));
		WizardPageTest.check("unreachable: the escaped describe error is shown", html.contains("(Unknown connector &lt;X&gt;)"));

		crm.deducedScan = DeducedScanEligibility.OFFERED;
		html = WizardPage.render(model);
		WizardPageTest.check("offered: the deduced-scan button is back, worded 'Find its tables'", html.contains(start) && html.contains("Find its tables"));
		WizardPageTest.check("offered: no catalog-scan button", !html.contains(rescan));
		WizardPageTest.check("the eligibility rule: describe error first, then the catalog operation",
			DeducedScanEligibility.of("boom", true) == DeducedScanEligibility.UNREACHABLE && DeducedScanEligibility.of(null, true) == DeducedScanEligibility.OFFICIAL_SCAN
				&& DeducedScanEligibility.of(null, false) == DeducedScanEligibility.OFFERED);

		final WizardSnapshot.SourceInfo erp = model.snapshot.sources.get(0);
		erp.deducedScan = DeducedScanEligibility.OFFERED;
		WizardPageTest.check("an application with tables never gets the button", !WizardPage.render(model).contains("data-mdmf-action=\"DEDUCED_SCAN_START\" data-mdmf-source-id=\"1\""));
	}

	/**
	 * UX lot U2 (2026-10-13), the live report: 17 Salesforce tables all ticked, alphabetical, each tick a full postback, a banner asking to tick
	 * when there was nothing to tick. Now: most rows first, the ticks are plain boxes (no action of their own) kept by ONE button per card and by
	 * "Continue", and the banner shows only when tables exist and none is ticked.
	 */
	private static void sourceTablesAreKeptInOneAction()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.SourceInfo erp = model.snapshot.sources.get(0);
		erp.tables.clear();
		for (final Object[] spec : new Object[][] { { "11", "Asset", 0L }, { "12", "Account", 52L }, { "13", "Contact", 51L }, { "14", "Lead", 41L } })
		{
			final WizardSnapshot.TableInfo table = new WizardSnapshot.TableInfo();
			table.id = (String) spec[0];
			table.tableName = (String) spec[1];
			table.sourceRowCountKnown = true;
			table.sourceRowCount = ((Long) spec[2]).longValue();
			table.selected = false;
			table.status = "PROPOSED";
			erp.tables.add(table);
		}
		model.snapshot.sources.remove(1);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_SOURCES;
		String html = WizardPage.render(model);
		final String tickBanner = WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.src2.tickBanner"));
		WizardPageTest.check("tables, none ticked: the banner shows", html.contains(tickBanner));
		final int step2Start = html.indexOf("data-step=\"2\""), step3Start = html.indexOf("data-step=\"3\"");
		WizardPageTest.check("the old action of a tick is gone from step 2: no postback per box", step2Start > 0 && step3Start > step2Start
			&& !html.substring(step2Start, step3Start).contains("TABLE_TOGGLE"));
		WizardPageTest.check("the boxes are plain, read by script", html.contains("class=\"mdmf-tick\" id=\"mdmfTableSel-12\"") && html.contains("data-mdmf-tick-id=\"12\""));
		WizardPageTest.check("no box is ticked", !html.contains("mdmfTableSel-12\" checked"));
		final int account = html.indexOf("mdmfTableSel-12"), contact = html.indexOf("mdmfTableSel-13"), lead = html.indexOf("mdmfTableSel-14"), asset = html.indexOf("mdmfTableSel-11");
		WizardPageTest.check("most rows first, empty tables last", account > 0 && account < contact && contact < lead && lead < asset);
		WizardPageTest.check("one button keeps the card's ticks", html.contains("data-mdmf-action=\"TABLES_KEEP\" data-mdmf-ticks-scope=\"mdmf-app-card-1\">Keep these tables</button>"));
		WizardPageTest.check("select all / none", html.contains("data-mdmf-tick-all=\"true\">Select all</button>") && html.contains("data-mdmf-tick-all=\"false\">Select none</button>"));
		WizardPageTest.check("the counter", html.contains(">0 of 4 selected</span>"));

		// a table already ticked stays ticked and removes the banner; Continue carries every card's ticks and skips the round trip when none changed
		erp.tables.get(0).selected = true;
		model.stepStates = WizardRules.stepStates(model.snapshot);
		html = WizardPage.render(model);
		WizardPageTest.check("a ticked table: no banner", !html.contains(tickBanner));
		WizardPageTest.check("Continue keeps the ticks of every card, once", html.contains("data-mdmf-action=\"TABLES_KEEP\" data-mdmf-ticks-scope=\"all\" data-mdmf-skip-if-clean=\"true\" data-mdmf-goto-step=\"3\">Continue to the landing"));

		// a table whose landing was generated has its scope fixed: its box is disabled
		erp.tables.get(1).status = "LOADED";
		WizardPageTest.check("a generated table's box is disabled", WizardPage.render(model).contains("disabled=\"disabled\" title=\"Already prepared in the landing"));

		// no table at all: no banner (nothing to tick), the card says what to do
		erp.tables.clear();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		WizardPageTest.check("no table at all: no banner", !WizardPage.render(model).contains(tickBanner));

		// the key of a table found from the data: who confirmed it and when, never "by a person"
		final WizardSnapshot.TableInfo deduced = new WizardSnapshot.TableInfo();
		deduced.id = "20";
		deduced.tableName = "Company";
		deduced.scanOrigin = "DEDUCED";
		deduced.declaredKeyColumns.add("id");
		erp.tables.add(deduced);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		WizardPageTest.check("no date: confirmed earlier", WizardPage.render(model).contains("confirmed earlier:"));
		deduced.scanConfirmedBy = "philippe";
		deduced.scanConfirmedAt = 1760000000000L;
		final String confirmed = WizardPage.render(model);
		WizardPageTest.check("who and when", confirmed.contains("confirmed by philippe on ") && !confirmed.contains("confirmed by a person"));
	}

	/** UX lot U2 (2026-10-13): one "Add an application" list; the three older ways are folded under "More ways to add an application". */
	private static void singleAddApplicationListRenders()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = new WizardSnapshot();
		model.snapshot.project = new WizardSnapshot.ProjectInfo();
		model.snapshot.project.code = "UxCrmSf";
		model.snapshot.catalogAvailable = true;
		model.snapshot.catalogSources.add(new WizardSnapshot.CatalogSourceInfo("SFDC", "salesforce://login.salesforce.com", "OTHER", "Salesforce"));
		model.snapshot.catalogSources.add(new WizardSnapshot.CatalogSourceInfo("OL", "Salesforce CRM (EU production)", "OTHER", ""));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_SOURCES;
		String html = WizardPage.render(model);
		WizardPageTest.check("closed: the list is an input-styled button that loads the connections", html.contains("data-mdmf-action=\"SOURCE_APP_OPEN\""));
		WizardPageTest.check("the ways in are folded under one summary", html.contains("<summary>More ways to add an application</summary>"));
		WizardPageTest.check("the sample files are no longer the big first panel", !html.contains("class=\"mdmf-panel mdmf-src2-dropzone\""));

		model.addAppOpen = true;
		final SourceOnboarding.ConnectionOption salesforce = new SourceOnboarding.ConnectionOption("Salesforce", "Salesforce", "salesforce", true, true, "SFDC");
		salesforce.scannedTables = 17;
		model.onboardConnections.add(salesforce);
		final SourceOnboarding.ConnectionOption lookup = new SourceOnboarding.ConnectionOption("Lookup", "Lookup", "rest", true, false, "");
		lookup.unreadableReason = AddApplicationOptions.REASON_NO_READ_OPERATION;
		model.onboardConnections.add(lookup);
		// UX lot C1: a rest connection with no catalog operation - one whose source already holds tables found from the data, one that can be read
		final SourceOnboarding.ConnectionOption demo = new SourceOnboarding.ConnectionOption("DemoCRM", "Demo CRM", "rest", true, false, "DEMOCRM");
		demo.scannedTables = 2;
		model.onboardConnections.add(demo);
		final SourceOnboarding.ConnectionOption generic = new SourceOnboarding.ConnectionOption("GenericREST", "GenericREST", "rest", true, false, "");
		generic.readable = true;
		model.onboardConnections.add(generic);
		html = WizardPage.render(model);
		WizardPageTest.check("the list is a combobox", html.contains("id=\"mdmfAddAppCombo\"") && html.contains("id=\"mdmfAddAppList\""));
		WizardPageTest.check("picking posts the one add action", html.contains("id=\"mdmfAddAppPick\" data-mdmf-action=\"SOURCE_ADD_APP\""));
		WizardPageTest.check("a scanned connection says how many tables", html.contains(">scanned, 17 tables</span>"));
		final String list = html.substring(html.indexOf("id=\"mdmfAddAppList\""), html.indexOf("id=\"mdmfAddAppPick\""));
		WizardPageTest.check("the source reached through its connection is not listed a second time", !list.contains("data-mdmf-id=\"SFDC\""));
		WizardPageTest.check("a source with no connection is greyed with its reason", html.contains("mdmf-combo-option mdmf-combo-option-off\" data-mdmf-id=\"OL\"")
			&& html.contains(">no connection to read it</span>") && html.contains("aria-disabled=\"true\""));
		WizardPageTest.check("a connection that cannot be read is greyed with the reason", html.contains("mdmf-combo-option mdmf-combo-option-off\" data-mdmf-id=\"Lookup\"")
			&& html.contains(">cannot be read: it has no operation that lists records</span>") && !html.contains("see More ways below"));
		WizardPageTest.check("a connection with no catalog scan but tables found from the data is pickable", html.contains("<li role=\"option\" class=\"mdmf-combo-option\" data-mdmf-id=\"DemoCRM\"")
			&& html.contains(">found from the data, 2 tables</span>"));
		WizardPageTest.check("a readable connection with no scan says it will be read from its data", html.contains("<li role=\"option\" class=\"mdmf-combo-option\" data-mdmf-id=\"GenericREST\"")
			&& html.contains(">will be read from its data</span>"));

		// a connection that is already an application of the project
		final WizardSnapshot.SourceInfo existing = new WizardSnapshot.SourceInfo();
		existing.id = "1";
		existing.sourceSystem = "SALESFORCE";
		existing.connector = "Salesforce";
		existing.catalogSourceId = "SFDC";
		model.snapshot.sources.add(existing);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		html = WizardPage.render(model);
		WizardPageTest.check("already in this project: greyed", html.contains(">already in this project</span>"));
	}

	/** UX lot U2: the code is proposed from the names the person knows, the trust rank is not asked, and a source with no schema says nothing alarming. */
	private static void addFormProposesACodeAndNoLongerAsksATrustRank()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.snapshot.catalogSources.add(new WizardSnapshot.CatalogSourceInfo("SF", "salesforce://login.salesforce.com", "OTHER", "Salesforce"));
		model.pickedCatalogSourceId = "SF";
		model.sourceDraft.sourceSystem = "SALESFORCE";
		model.sourceDraft.connector = "Salesforce";
		final String html = WizardPage.render(model);
		WizardPageTest.check("the code is pre-filled", html.contains("name=\"mdmfSourceSystem\" value=\"SALESFORCE\""));
		WizardPageTest.check("no trust-rank number in the form", !html.contains("name=\"mdmfTrustRank\""));
		WizardPageTest.check("a source with no schema: no 'No schema scanned yet'", !html.contains("No schema scanned yet"));
		WizardPageTest.check("the technical connection id sits in a fold", html.contains("<summary>Advanced</summary>") && html.contains("name=\"mdmfConnector\""));
		WizardPageTest.check("the label reads 'Application code'", html.contains("Application code</label><input type=\"text\" id=\"mdmfSourceSystem\""));
	}

	/** UX lot U2: "Use this source" on a system that is already an application of the project showed the add form again (a duplicate could be created). */
	private static void liveSystemAlreadyAnApplicationOffersItsTablesNotTheAddForm()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.onboardOpen = true;
		model.onboardConnectorId = "Salesforce";
		model.onboardConnectorDisplay = "Salesforce";
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "Salesforce";
		facts.connectorFound = true;
		facts.connectorActive = true;
		facts.catalogOperation = true;
		facts.catalogState = "VERIFIED";
		facts.planAction = "LINKED";
		facts.planSourceId = "SF";
		facts.scanMappingId = Integer.valueOf(1);
		facts.sourceExists = true;
		facts.sourceActive = true;
		facts.lastScanStatus = "OK";
		facts.assetsScanned = true;
		facts.assetsWithColumns = true;
		model.onboardDiagnosis = SourceOnboarding.diagnose(facts);
		String html = WizardPage.render(model);
		WizardPageTest.check("not yet an application: 'Use this source'", html.contains("data-mdmf-action=\"SOURCE_ONBOARD_USE\""));
		model.onboardExistingSourceId = "2";
		model.onboardExistingCode = "CRM";
		html = WizardPage.render(model);
		WizardPageTest.check("already an application: no 'Use this source'", !html.contains("data-mdmf-action=\"SOURCE_ONBOARD_USE\""));
		WizardPageTest.check("it says which application", html.contains("This system is already the application CRM of this project."));
		WizardPageTest.check("it offers its tables", html.contains("data-mdmf-action=\"SOURCE_FIND_TABLES\" data-mdmf-source-id=\"2\">Find its tables</button>"));
	}

	/**
	 * Deduced-scan lot (2026-09-24, boards 2d/2e): renders a HubSpot-like deduced-scan pipeline card (board
	 * 2d, mid-run) and a Contact confirm card (board 2e, TO_CONFIRM) - same "build a Model by hand, render,
	 * assert on the HTML" pattern as {@link #sourceCardsAndAddFormRender}. Verified as static HTML only (no
	 * browser/Tomcat) - see docs/DEDUCED-SCAN.md "What was verified, and how".
	 */
	private static void deducedScanCardsAndConfirmCardsRender()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.deducedScanRunning = true;

		final DeducedScanProgress progress = DeducedScanProgress.newRun(java.util.Arrays.asList("src-hub"));
		progress.start("src-hub", 1000L);
		progress.stage("src-hub", DeducedScanProgress.LIST, DeducedScanProgress.DONE, "the model chose list_contacts, list_companies");
		progress.stage("src-hub", DeducedScanProgress.SAMPLE, DeducedScanProgress.DONE, "25 rows each, read only");
		progress.stage("src-hub", DeducedScanProgress.MEASURE, DeducedScanProgress.DONE, "types, filling, uniqueness");
		progress.stage("src-hub", DeducedScanProgress.NAME, DeducedScanProgress.RUNNING, "");
		model.deducedScanCards.add(new WizardPage.Model.DeducedScanCard("src-hub", "HubSpot", progress.entry("src-hub")));

		final com.on.ebx.factory.ebx.DeducedScanCatalog.PendingAsset asset = new com.on.ebx.factory.ebx.DeducedScanCatalog.PendingAsset();
		asset.key = "HUBSPOT:Contact"; // audit D-07: the natural key source:qualifiedName
		asset.tableName = "Contact";
		asset.scanConfidence = 94;
		asset.scanRationale = "id is filled and different on every row read.";
		asset.readRecipe = com.on.ebx.factory.deducedscan.ReadRecipe.parse("{\"connector\":\"HubSpot\",\"operation\":\"list_contacts\",\"itemsPath\":\"results\"}");
		// fix lot (2026-09-26, defect 4): sampleSize is no longer a hardcoded 25 default (a live bug: a 10-row
		// source showed "10/25" instead of "10/10") - it is the real number of rows the sample read, propagated
		// from Asset.rowCount down to each column (DeducedScanCatalog#columns). Set explicitly here, same figure
		// as this fixture's own "25 rows each, read only" SAMPLE stage summary above.
		asset.sampleSize = 25;
		final com.on.ebx.factory.ebx.DeducedScanCatalog.PendingColumn idColumn = new com.on.ebx.factory.ebx.DeducedScanCatalog.PendingColumn();
		idColumn.name = "id";
		idColumn.dataType = "text";
		idColumn.primaryKey = true;
		idColumn.filled = 25;
		idColumn.distinct = 25;
		idColumn.example = "51";
		idColumn.sampleSize = 25;
		asset.columns.add(idColumn);
		final com.on.ebx.factory.ebx.DeducedScanCatalog.PendingColumn nameColumn = new com.on.ebx.factory.ebx.DeducedScanCatalog.PendingColumn();
		nameColumn.name = "properties.firstname";
		nameColumn.dataType = "text";
		nameColumn.filled = 25;
		nameColumn.distinct = 23;
		nameColumn.example = "Maria";
		nameColumn.sampleSize = 25;
		asset.columns.add(nameColumn);
		model.deducedScanPending
			.add(new WizardPage.Model.DeducedScanPendingGroup("src-hub", "HubSpot", java.util.Collections.singletonList(asset)));

		final String html = WizardPage.render(model);
		WizardPageTest.check("the pipeline card shows the application name", html.contains("id=\"mdmf-deduced-src-hub\""));
		WizardPageTest.check("the LIST stage summary is shown", html.contains("the model chose list_contacts, list_companies"));
		WizardPageTest.check("a RUNNING stage renders without a checkmark/warning glyph",
			html.contains("mdmf-src2-pipe-circle-running\">") || html.contains("mdmf-src2-pipe-circle-running\"></span>"));
		WizardPageTest.check("the deduced chip is shown", html.contains(">found from the data</span>"));
		WizardPageTest.check("the confirm card shows the proposed table name in the rename input", html.contains("value=\"Contact\""));
		WizardPageTest.check("the key chip shows the proposed key column", html.contains(">id</span>"));
		WizardPageTest.check("the confidence is shown", html.contains("94 %"));
		WizardPageTest.check("the measured columns table shows filled/distinct/example", html.contains("25/25") && html.contains("Maria"));
		WizardPageTest.check("how it is read names the operation", html.contains(">list_contacts</span>"));
		WizardPageTest.check("how it is read names the itemsPath", html.contains("rows in results"));
		WizardPageTest.check("the rationale is quoted", html.contains("id is filled and different on every row read."));
		WizardPageTest.check("Confirm/Choose another key/Leave it out all render",
			html.contains("data-mdmf-action=\"DEDUCED_SCAN_CONFIRM\" data-mdmf-id=\"HUBSPOT:Contact\"") && html.contains("Choose another key") && html.contains("data-mdmf-action=\"DEDUCED_SCAN_LEAVE_OUT\" data-mdmf-id=\"HUBSPOT:Contact\""));
		// fix/continue-landing-warning (2026-09-26): "Continue to the landing" stays enabled while this one
		// TO_CONFIRM table exists (the server fails that table's flow on its own) - it only carries a warning.
		WizardPageTest.check("Continue to the landing stays enabled while a deduced table is TO_CONFIRM",
			html.contains("data-mdmf-goto-step=\"3\">Continue to the landing"));
		WizardPageTest.check("the warning names the pending count", html.contains("mdmf-hint-warn\"><b>1 table(s) still to confirm: the landing will not load them"));
		WizardPageTest.check("no disabled Continue button any more", !html.contains("disabled=\"disabled\" style=\"opacity:.45\">Continue to the landing"));

		// once every pending table is gone, the warning goes away
		final WizardPage.Model doneModel = WizardPageTest.model();
		doneModel.snapshot = WizardPageTest.demoSnapshot();
		doneModel.stepStates = WizardRules.stepStates(doneModel.snapshot);
		final String doneHtml = WizardPage.render(doneModel);
		WizardPageTest.check("Continue to the landing is enabled with no pending deduced table",
			doneHtml.contains("data-mdmf-goto-step=\"3\">Continue to the landing"));
		WizardPageTest.check("no pending warning with no pending deduced table", !doneHtml.contains("still to confirm: the landing will not load them"));

		WizardPageTest.deducedScanKeysRoundTrip();
	}

	/**
	 * Audit D-07: a deduced table is named by its natural key ({@code source:qualifiedName}), which may hold quotes, a
	 * pipe, a backslash, accents, spaces, colons, slashes and angle brackets. The key must come back EXACTLY from the
	 * {@code data-mdmf-id} attribute (what wizard.js copies into the posted mdmfId field: the browser decodes the
	 * entities), never break the markup, and never be used as an element id - the name input's id is a token, and the
	 * Confirm button names that same token.
	 */
	private static void deducedScanKeysRoundTrip()
	{
		final String[] keys = {
			"SRC:O'Brien \"quoted\"",
			"SRC:a|b\\c",
			"SRC:Soci\u00e9t\u00e9 G\u00e9n\u00e9rale <Clients> & co",
			"SRC:ns:obj:with:colons",
			"SRC:path/to/file name.csv ",
		};
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final java.util.List<com.on.ebx.factory.ebx.DeducedScanCatalog.PendingAsset> assets = new java.util.ArrayList<com.on.ebx.factory.ebx.DeducedScanCatalog.PendingAsset>();
		for (final String key : keys)
		{
			final com.on.ebx.factory.ebx.DeducedScanCatalog.PendingAsset asset = new com.on.ebx.factory.ebx.DeducedScanCatalog.PendingAsset();
			asset.key = key;
			asset.tableName = key.substring(4);
			assets.add(asset);
		}
		model.deducedScanPending.add(new WizardPage.Model.DeducedScanPendingGroup("src-hub", "HubSpot", assets));
		final String html = WizardPage.render(model);
		final java.util.List<String> confirmIds = WizardPageTest.attributeValues(html, "data-mdmf-action=\"DEDUCED_SCAN_CONFIRM\" data-mdmf-id=\"");
		final java.util.List<String> leaveOutIds = WizardPageTest.attributeValues(html, "data-mdmf-action=\"DEDUCED_SCAN_LEAVE_OUT\" data-mdmf-id=\"");
		WizardPageTest.check("every key comes back exactly from Confirm's data-mdmf-id: " + confirmIds, java.util.Arrays.asList(keys).equals(confirmIds));
		WizardPageTest.check("every key comes back exactly from Leave it out's data-mdmf-id: " + leaveOutIds, java.util.Arrays.asList(keys).equals(leaveOutIds));
		final java.util.List<String> nameInputs = WizardPageTest.attributeValues(html, "data-mdmf-name-input=\"");
		WizardPageTest.check("one name input per key", nameInputs.size() == keys.length);
		for (int i = 0; i < keys.length; i++)
		{
			final String inputId = nameInputs.get(i);
			WizardPageTest.check("the name input id is a token, not the key: " + inputId, inputId.matches("mdmfDeducedName-k[0-9a-f]{20}"));
			WizardPageTest.check("the name input with that id exists", html.contains("id=\"" + inputId + "\""));
			WizardPageTest.check("the token is the key's own", inputId.equals("mdmfDeducedName-" + com.on.ebx.factory.core.CatalogKeys.domToken(keys[i])));
		}
		WizardPageTest.check("no raw quote or angle bracket of a key leaks into the markup", !html.contains("O'Brien \"quoted\"\"") && !html.contains("<Clients>"));
	}

	/** Every value of an attribute whose opening text is {@code pPrefix} (ends with the opening quote), HTML entities decoded the way a browser's getAttribute does. */
	private static java.util.List<String> attributeValues(final String pHtml, final String pPrefix)
	{
		final java.util.List<String> out = new java.util.ArrayList<String>();
		int from = 0;
		while (true)
		{
			final int start = pHtml.indexOf(pPrefix, from);
			if (start < 0)
			{
				return out;
			}
			final int valueStart = start + pPrefix.length();
			final int end = pHtml.indexOf('"', valueStart);
			out.add(pHtml.substring(valueStart, end).replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"));
			from = end;
		}
	}

	/**
	 * Fix lot (2026-09-26, second full live run, project DeducedTest2): the four rendering defects found live -
	 * defect 10 (the review card's key label must say "proposed by the language model" for a deduced table, not
	 * "declared by the source"), defect 12 ("Add more applications" stays open across a postback when the
	 * onboarding panel inside it is mid-flow), defect 13a (a SKIPPED deduced-scan card's remaining stages read
	 * "skipped", not "waiting", and its Remove button still renders), and defect 14 (a DONE card's end line
	 * follows the CURRENT decisions of its assets, not the run's own frozen summary).
	 */
	private static void deducedScanFixLot20260926Renders()
	{
		// defect 10: a table whose declared key came from a DEDUCED asset reads "proposed by the language
		// model", never "declared by the source" - a genuine OFFICIAL/blank-origin one keeps the old wording.
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final WizardSnapshot.TableInfo deducedTable = new WizardSnapshot.TableInfo();
		deducedTable.id = "20";
		deducedTable.tableName = "Company";
		deducedTable.sourceObject = "listCompanies";
		deducedTable.status = "PROPOSED";
		deducedTable.declaredKeyColumns.add("id");
		deducedTable.scanOrigin = "DEDUCED";
		model.snapshot.sources.get(0).tables.add(deducedTable);
		final WizardSnapshot.TableInfo officialTable = new WizardSnapshot.TableInfo();
		officialTable.id = "21";
		officialTable.tableName = "ACCOUNT";
		officialTable.sourceObject = "SALESFORCE.ACCOUNT";
		officialTable.status = "PROPOSED";
		officialTable.declaredKeyColumns.add("Id");
		// scanOrigin left blank: a genuine catalog-declared key (OFFICIAL scan, or a legacy row with no scan* fields at all)
		model.snapshot.sources.get(1).tables.add(officialTable);
		final String html = WizardPage.render(model);
		WizardPageTest.check("a deduced table's key label is the language-model wording",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.review.key.deduced")));
		WizardPageTest.check("the plain 'declared by the source' wording still shows for the OFFICIAL table",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.review.key.declared")));

		// defect 12: closed by default, open once onboardOpen (or another mid-flow signal) is set
		final WizardPage.Model closedModel = WizardPageTest.model();
		closedModel.snapshot = WizardPageTest.demoSnapshot();
		closedModel.stepStates = WizardRules.stepStates(closedModel.snapshot);
		final String closedHtml = WizardPage.render(closedModel);
		WizardPageTest.check("Add more applications is closed with nothing mid-flow", closedHtml.contains("<details class=\"mdmf-disclosure\"><summary>")
			&& closedHtml.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step2.addMore")));
		final WizardPage.Model openModel = WizardPageTest.model();
		openModel.snapshot = WizardPageTest.demoSnapshot();
		openModel.stepStates = WizardRules.stepStates(openModel.snapshot);
		openModel.onboardOpen = true;
		final String openHtml = WizardPage.render(openModel);
		WizardPageTest.check("Add more applications stays open when the person opened it",
			openHtml.contains("<details class=\"mdmf-disclosure\" open=\"open\"><summary>"));

		// defect 13a/13b: a SKIPPED entry - remaining stages read "skipped" (not "waiting"), Remove still renders
		final WizardPage.Model skippedModel = WizardPageTest.model();
		skippedModel.snapshot = WizardPageTest.demoSnapshot();
		skippedModel.stepStates = WizardRules.stepStates(skippedModel.snapshot);
		final DeducedScanProgress skippedProgress = DeducedScanProgress.newRun(java.util.Arrays.asList("src-fda"));
		skippedProgress.start("src-fda", 1000L);
		skippedProgress.stage("src-fda", DeducedScanProgress.LIST, DeducedScanProgress.SKIPPED, "an enrichment, not a source");
		skippedProgress.finish("src-fda", DeducedScanProgress.SKIPPED, "an enrichment, not a source", 2000L, java.util.Collections.<String>emptyList());
		skippedModel.deducedScanCards.add(new WizardPage.Model.DeducedScanCard("src-fda", "OpenFDA", skippedProgress.entry("src-fda")));
		final String skippedHtml = WizardPage.render(skippedModel);
		WizardPageTest.check("the LIST stage shows the warning glyph", skippedHtml.contains("mdmf-src2-pipe-circle-needs-person\">&#9888;"));
		WizardPageTest.check("SAMPLE/MEASURE/NAME read 'skipped', not 'waiting'",
			!skippedHtml.contains("mdmf-src2-pipe-label-waiting") && skippedHtml.contains(WizardMessages.get(Locale.ENGLISH, "wizard.deduced.stageShort.sample.skipped")));
		WizardPageTest.check("Remove it renders on a SKIPPED deduced-scan card",
			skippedHtml.contains("data-mdmf-action=\"SOURCE_REMOVE\" data-mdmf-source-id=\"src-fda\""));

		// defect 14: a DONE entry's end line follows the current decisions, not the frozen run summary
		final WizardPage.Model doneCountsModel = WizardPageTest.model();
		doneCountsModel.snapshot = WizardPageTest.demoSnapshot();
		doneCountsModel.stepStates = WizardRules.stepStates(doneCountsModel.snapshot);
		final DeducedScanProgress doneProgress = DeducedScanProgress.newRun(java.util.Arrays.asList("src-crm"));
		doneProgress.start("src-crm", 1000L);
		doneProgress.finish("src-crm", DeducedScanProgress.DONE, "2 table(s) deduced, waiting for confirmation.", 2000L,
			java.util.Arrays.asList("Company", "Contact"));
		doneCountsModel.deducedScanCards.add(new WizardPage.Model.DeducedScanCard("src-crm", "DemoCRM", doneProgress.entry("src-crm"), 2, 0, 0));
		final String doneCountsHtml = WizardPage.render(doneCountsModel);
		WizardPageTest.check("the end line follows the current decisions (2 confirmed), not the frozen run summary",
			doneCountsHtml.contains("2 tables confirmed.") && !doneCountsHtml.contains("2 table(s) deduced, waiting for confirmation."));

		// ... and a DONE entry with no decision data at all (an older catalog) still falls back to the old text
		final WizardPage.Model fallbackModel = WizardPageTest.model();
		fallbackModel.snapshot = WizardPageTest.demoSnapshot();
		fallbackModel.stepStates = WizardRules.stepStates(fallbackModel.snapshot);
		final DeducedScanProgress fallbackProgress = DeducedScanProgress.newRun(java.util.Arrays.asList("src-old"));
		fallbackProgress.start("src-old", 1000L);
		fallbackProgress.finish("src-old", DeducedScanProgress.DONE, "2 table(s) deduced, waiting for confirmation.", 2000L,
			java.util.Arrays.asList("Company", "Contact"));
		fallbackModel.deducedScanCards.add(new WizardPage.Model.DeducedScanCard("src-old", "Legacy", fallbackProgress.entry("src-old")));
		final String fallbackHtml = WizardPage.render(fallbackModel);
		WizardPageTest.check("no decision data at all falls back to the run's own frozen summary",
			fallbackHtml.contains("2 table(s) deduced, waiting for confirmation."));
	}

	/** Source-combobox lot (2026-09-23): a searchable combobox, no native select; picking goes through the one hidden SOURCE_PICK_CATALOG button. */
	private static void catalogSourcePickerIsASearchableCombobox()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.snapshot.catalogSources.add(new WizardSnapshot.CatalogSourceInfo("SNOW_DEMO", "Snowflake demo", "SNOWFLAKE", "EbxSnowflake"));
		model.snapshot.catalogSources.add(new WizardSnapshot.CatalogSourceInfo("PIM", "PIM (Akeneo) <prod>", "OTHER", null));
		model.pickedCatalogSourceId = "PIM";
		final String html = WizardPage.render(model);
		WizardPageTest.check("no native select any more", !html.contains("<select id=\"mdmfCatalogSourceSelect\""));
		WizardPageTest.check("a combobox input", html.contains("id=\"mdmfCatalogSourceSearch\" role=\"combobox\""));
		WizardPageTest.check("the picked label is shown in the input, escaped", html.contains("value=\"PIM (Akeneo) &lt;prod&gt;\" data-mdmf-picked-label=\"PIM (Akeneo) &lt;prod&gt;\""));
		WizardPageTest.check("one option per catalog source with a lower-cased search text",
			html.contains("data-mdmf-id=\"SNOW_DEMO\" data-mdmf-label=\"Snowflake demo\" data-search=\"snowflake demo snowflake ebxsnowflake snow_demo\" aria-selected=\"false\""));
		WizardPageTest.check("the picked option is marked", html.contains("class=\"mdmf-combo-option picked\" data-mdmf-id=\"PIM\""));
		WizardPageTest.check("a null connector renders no connector span", !html.contains("mdmf-combo-connector\"></span>"));
		WizardPageTest.check("the platform chip", html.contains("<span class=\"mdmf-chip mdmf-chip-muted\">SNOWFLAKE</span>"));
		WizardPageTest.check("the hidden pick button is the one action path", html.contains("id=\"mdmfCatalogSourcePick\" data-mdmf-action=\"SOURCE_PICK_CATALOG\" hidden=\"hidden\""));
		WizardPageTest.check("the no-match text travels as a data attribute", html.contains("data-mdmf-nomatch=\"" + WizardMessages.get(Locale.ENGLISH, "wizard.field.catalogSource.noMatch") + "\""));
		WizardPageTest.check("the hint counts the sources", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.field.catalogSource.hint", Integer.valueOf(2))));
	}

	/**
	 * Coordinator review (2026-09-23): {@code onboardOpen=false} (the model's own default) must render ONLY
	 * the title button, as a real action ({@code SOURCE_ONBOARD_OPEN}) - no combobox, no checklist, not even
	 * a hidden body: {@code connections()} is a live MCP round trip and must never fire on a plain render.
	 */
	private static void sourceOnboardingPanelClosedShowsOnlyTheOpenButton()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.catalogAvailable = true;
		model.stepStates = WizardRules.stepStates(model.snapshot);
		// left populated on purpose (as if a previous open had happened): must never leak into the closed render
		model.onboardConnections.add(new SourceOnboarding.ConnectionOption("SourceBidon", "SourceBidon", "sample-file", true, true, ""));

		final String html = WizardPage.render(model);
		// visual pass follow-up (2026-09-24): the closed state now shows the card's OWN title/lead (mockup
		// copy, "Connect a live system") plus an input-styled stand-in button that carries the real
		// SOURCE_ONBOARD_OPEN action and the combobox's own search placeholder text - not the button carrying
		// wizard.onboard.title text directly any more (WizardPage.renderSourceOnboardingPanel).
		WizardPageTest.check("the card shows its own mockup title", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.onboard.title")));
		WizardPageTest.check("the input-styled stand-in is a real SOURCE_ONBOARD_OPEN action button",
			html.contains("data-mdmf-action=\"SOURCE_ONBOARD_OPEN\">" + WizardMessages.get(Locale.ENGLISH, "wizard.onboard.combo.search")));
		WizardPageTest.check("no body element at all when closed - absent, not merely hidden", !html.contains("id=\"mdmf-onboard-body\""));
		WizardPageTest.check("no combobox when closed", !html.contains("mdmfOnboardConnectorCombo"));
		WizardPageTest.check("no close button when closed", !html.contains("data-mdmf-action=\"SOURCE_ONBOARD_CLOSE\""));
		WizardPageTest.check("a stale connections list never leaks into the closed render", !html.contains("data-mdmf-id=\"SourceBidon\""));
	}

	/**
	 * Source-onboarding lot (2026-09-23; fixture revised after coordinator review the same day: the panel must
	 * be explicitly open to show anything past its title): a connection is picked in the onboarding combobox
	 * and its diagnosis (CATALOG_APPLY, as measured live on SourceBidon - task brief) is set on the model -
	 * the checklist must show all six steps with the right state class/icon, the todo message, and the ONE
	 * next-action button (CATALOG_APPLY), never SCAN_RUN or PICK.
	 */
	private static void sourceOnboardingPanelRendersChecklistAndNextAction()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.catalogAvailable = true;
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_SOURCES;
		model.onboardOpen = true;
		model.onboardConnections.add(new SourceOnboarding.ConnectionOption("SourceBidon", "SourceBidon", "sample-file", true, true, ""));
		model.onboardConnections.add(new SourceOnboarding.ConnectionOption("PersonsSapSample", "PersonsSapSample", "sample-file", true, true, "PERSONSSAPSAMPLE"));
		model.onboardConnectorId = "SourceBidon";
		model.onboardConnectorDisplay = "SourceBidon";
		model.onboardDiagnosis = SourceOnboarding.diagnose(WizardPageTest.sourceBidonFacts());
		// coordinator review (2026-09-23, static-render CSS fix): the scan row's own outcome hint used to sit
		// to the right of the message as a separate flex sibling - it must now be a child of mdmf-onboard-text,
		// stacking below the message inside the one flex-grow column.
		model.onboardScanMessage = "Loaded 12 of 12 row(s).";

		final String html = WizardPage.render(model);
		// visual pass follow-up (2026-09-24): the Close button/toggle-header pair is gone - the card always
		// shows its own mockup title/lead (WizardPage.renderSourceOnboardingPanel), open or closed.
		WizardPageTest.check("the title is shown", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.onboard.title")));
		WizardPageTest.check("no more SOURCE_ONBOARD_OPEN button once open", !html.contains("data-mdmf-action=\"SOURCE_ONBOARD_OPEN\""));
		WizardPageTest.check("the onboarding combobox lists both connections", html.contains("data-mdmf-id=\"SourceBidon\"") && html.contains("data-mdmf-id=\"PersonsSapSample\""));
		WizardPageTest.check("the picked connection's chip says not yet in the catalog",
			html.contains("data-mdmf-id=\"SourceBidon\"") && html.indexOf(WizardMessages.get(Locale.ENGLISH, "wizard.onboard.chip.notInCatalog")) > 0);
		WizardPageTest.check("the already-linked connection's chip says in the catalog",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.onboard.chip.inCatalog")));
		WizardPageTest.check("the new-connection link points at the Connectors table with the creation service",
			html.contains("/ebx-ui/ui/advanced/data/BConnectors/Connectors/root/Connector?service=@creation"));
		// leave-guard lot (2026-09-23): carries the class ProjectWizardUserService's own voluntaryLeaveScript
		// call arms - a plain Connectors link, not routed through originCaptureScript, must still never trigger
		// EBX's own "Quitter le site Web ?" prompt.
		WizardPageTest.check("the new-connection link carries the voluntary-leave marker class",
			html.contains("<a class=\"mdmf-leave-link\" href=\"/ebx-ui/ui/advanced/data/BConnectors/Connectors/root/Connector?service=@creation\" target=\"_top\">"));
		// coordinator review: OPEN_CONNECTION now uses the documented ?predicate= filtered-table route, not a
		// bare .../root/Connector/<id> path - see WizardPage.connectorRecordUrl
		WizardPageTest.check("the checklist header names the picked connection", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.onboard.selected", "SourceBidon")));
		WizardPageTest.check("connection step is done", html.contains("mdmf-onboard-step-done"));
		WizardPageTest.check("catalogSource step is todo, with the plan's own message", html.contains("mdmf-onboard-step-todo")
			&& html.contains("No Source for connector SourceBidon"));
		WizardPageTest.check("scanFlows/scan/ready are waiting", WizardPageTest.count(html, "mdmf-onboard-step-waiting") == 3);
		// coordinator review (2026-09-23): three WAITING rows in a row used to all read the same "Waiting on a
		// previous step" with nothing saying which step - every row now leads with its own <b>name</b>, WAITING
		// included, so the three waiting rows stay distinguishable from one another and from the earlier steps.
		// coordinator review (2026-09-23, static-render CSS fix): the name carries its own class
		// (mdmf-onboard-name, kept from squeezing/wrapping in the flex row) and the message is now wrapped in
		// its own flex-grow <span class="mdmf-onboard-text"> column - the "<b>name - </b><span>message</span>"
		// text ORDER is unchanged, only the markup around it.
		// fix lot (2026-09-25, bug 2 addendum): the " - " separator moved INSIDE the <b> (WizardPage) so the
		// row is exactly two flex items, never a stray unstyled text-node item at a very narrow card width -
		// every "</b> - " expectation below is now "- </b>".
		final String nameOpenTag = "<b class=\"mdmf-onboard-name\">";
		final String waitingRow = " - </b><span class=\"mdmf-onboard-text\"><span>" + WizardMessages.get(Locale.ENGLISH, "wizard.onboard.step.waiting") + "</span>";
		WizardPageTest.check("connection row leads with its own name",
			html.contains(nameOpenTag + WizardMessages.get(Locale.ENGLISH, "wizard.onboard.step.connection.name") + " - </b>"));
		WizardPageTest.check("catalogSource row leads with its own name",
			html.contains(nameOpenTag + WizardMessages.get(Locale.ENGLISH, "wizard.onboard.step.catalogSource.name") + " - </b>"));
		WizardPageTest.check("scanFlows waiting row is distinguishable by its own name",
			html.contains(nameOpenTag + WizardMessages.get(Locale.ENGLISH, "wizard.onboard.step.scanFlows.name") + waitingRow));
		WizardPageTest.check("scan waiting row is distinguishable by its own name",
			html.contains(nameOpenTag + WizardMessages.get(Locale.ENGLISH, "wizard.onboard.step.scan.name") + waitingRow));
		WizardPageTest.check("ready waiting row is distinguishable by its own name",
			html.contains(nameOpenTag + WizardMessages.get(Locale.ENGLISH, "wizard.onboard.step.ready.name") + waitingRow));
		WizardPageTest.check("the scan hint is nested inside the flex-grow text column, right after the message, not a separate flex sibling",
			html.contains(nameOpenTag + WizardMessages.get(Locale.ENGLISH, "wizard.onboard.step.scan.name") + waitingRow + "<p class=\"mdmf-hint\">"
				+ WizardMessages.get(Locale.ENGLISH, "wizard.onboard.scanMessage", "Loaded 12 of 12 row(s).") + "</p></span>"));
		WizardPageTest.check("the next action is the catalog-write button, not scan or pick",
			html.contains("data-mdmf-action=\"SOURCE_ONBOARD_CATALOG\"") && !html.contains("data-mdmf-action=\"SOURCE_ONBOARD_SCAN\"")
				&& !html.contains("data-mdmf-action=\"SOURCE_ONBOARD_USE\""));
		WizardPageTest.check("the refresh button is always offered once a connection is picked", html.contains("data-mdmf-action=\"SOURCE_ONBOARD_REFRESH\""));
		WizardPageTest.check("the write button is enabled for an administrator", !html.contains("data-mdmf-action=\"SOURCE_ONBOARD_CATALOG\" disabled=\"disabled\""));
	}

	/**
	 * Coordinator review (2026-09-23): the {@code OPEN_CONNECTION} action's link must be the documented
	 * filtered-table route ({@code RecordLineageReader#openUrl}'s own pattern), a percent-encoded
	 * {@code ?predicate=./id='...'} - never a bare {@code .../root/Connector/<id>} path.
	 */
	private static void sourceOnboardingOpenConnectionUsesThePredicateRoute()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.catalogAvailable = true;
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.onboardOpen = true;
		model.onboardConnectorId = "NoSuchConnector";
		model.onboardConnectorDisplay = "NoSuchConnector";
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "NoSuchConnector";
		facts.connectorFound = false;
		facts.connectorError = "Connector not found: NoSuchConnector";
		model.onboardDiagnosis = SourceOnboarding.diagnose(facts);

		final String html = WizardPage.render(model);
		WizardPageTest.check("no bare .../root/Connector/<id> path", !html.contains("/root/Connector/NoSuchConnector"));
		WizardPageTest.check("the documented ?predicate= filtered-table route, id quoted then percent-encoded",
			html.contains("/ebx-ui/ui/advanced/data/BConnectors/Connectors/root/Connector?predicate=.%2Fid%3D%27NoSuchConnector%27"));
		// leave-guard lot (2026-09-23): carries the class ProjectWizardUserService's own voluntaryLeaveScript
		// call arms - a plain Connectors record link, not routed through originCaptureScript, must still never
		// trigger EBX's own "Quitter le site Web ?" prompt.
		WizardPageTest.check("the open-connection link keeps its own classes alongside the new one",
			html.contains("class=\"mdmf-btn mdmf-btn-primary mdmf-leave-link\""));
	}

	/** Same fixture, non-administrator: the CATALOG_APPLY button must render disabled with the admin-only reason - the diagnosis text itself stays visible (task brief). */
	private static void sourceOnboardingWriteActionsDisabledForNonAdministrator()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.catalogAvailable = true;
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.administrator = false; model.designer = false; // audit F-02: the design actions follow DESIGN
		model.onboardOpen = true;
		model.onboardConnectorId = "SourceBidon";
		model.onboardConnectorDisplay = "SourceBidon";
		model.onboardDiagnosis = SourceOnboarding.diagnose(WizardPageTest.sourceBidonFacts());

		final String html = WizardPage.render(model);
		WizardPageTest.check("the write button is disabled for a non-administrator",
			html.contains("data-mdmf-action=\"SOURCE_ONBOARD_CATALOG\" disabled=\"disabled\""));
		WizardPageTest.check("the admin-only reason is shown next to it", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.gate.adminOnly")));
		WizardPageTest.check("the checklist itself is still shown to a non-administrator", html.contains("mdmf-onboard-step-done"));
	}

	/**
	 * sample-files lot (2026-09-23): {@code sampleFilesReport} empty (the model's own default) must render
	 * ONLY the title toggle button, collapsed - a plain {@code data-mdmf-toggle} (no server round trip needed
	 * to open it, unlike the guided onboarding panel next to it).
	 */
	private static void sampleFilesPanelClosedByDefaultShowsOnlyTheToggle()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.catalogAvailable = true;
		model.stepStates = WizardRules.stepStates(model.snapshot);

		final String html = WizardPage.render(model);
		WizardPageTest.check("the title is a plain toggle button",
			html.contains("data-mdmf-toggle=\"mdmf-sample-files-body\">" + WizardMessages.get(Locale.ENGLISH, "wizard.step2.sampleFiles.title")));
		WizardPageTest.check("the body is collapsed (hidden attribute present) when there is no report yet", html.contains("id=\"mdmf-sample-files-body\" hidden=\"hidden\""));
		WizardPageTest.check("no report block when there is nothing to show", !html.contains("mdmf-sample-report-row"));
	}

	/**
	 * sample-files lot (2026-09-23): a report of two files - one that went all the way to {@code ready} and
	 * added the application, one refused at the raw-byte cap before any onboarding read was even attempted
	 * (no diagnosis at all). The panel must render OPEN (no {@code hidden} attribute on the body) as soon as
	 * a report exists, with one block per file reusing the exact checklist markup of the guided panel.
	 */
	private static void sampleFilesPanelWithReportRendersOpenWithBothOutcomes()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.catalogAvailable = true;
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.sampleFilesReport.add(new SampleFileRules.ReportRow("Personnes_SAP_Sample.json", "PersonnesSapSample", "PERSONSSAPSAMPLE",
			SourceOnboarding.diagnose(WizardPageTest.readySourceFacts()), true, "", null));
		model.sampleFilesReport.add(new SampleFileRules.ReportRow("huge_export.csv", "HugeExport", "", null, false, "",
			"File huge_export.csv is about 1400 KB, over the 1367 KB cap for one sample."));

		final String html = WizardPage.render(model);
		WizardPageTest.check("the body is OPEN (no hidden attribute) once a report exists", !html.contains("id=\"mdmf-sample-files-body\" hidden=\"hidden\""));
		WizardPageTest.check("body element is present", html.contains("id=\"mdmf-sample-files-body\">"));
		WizardPageTest.check("two report rows rendered", WizardPageTest.count(html, "mdmf-sample-report-row") == 2);
		WizardPageTest.check("first file's name is shown", html.contains("Personnes_SAP_Sample.json"));
		WizardPageTest.check("first file's checklist shows the ready step done", html.contains("mdmf-onboard-step-done"));
		WizardPageTest.check("first file's application-added line names the source id",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step2.sampleFiles.report.applicationAdded", "PERSONSSAPSAMPLE")));
		WizardPageTest.check("second file's name is shown", html.contains("huge_export.csv"));
		WizardPageTest.check("second file's error is shown, no checklist for it (diagnosis was null)",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step2.sampleFiles.report.error", "File huge_export.csv is about 1400 KB, over the 1367 KB cap for one sample.")));
		WizardPageTest.check("no application-added line for the failed file",
			WizardPageTest.count(html, WizardMessages.get(Locale.ENGLISH, "wizard.step2.sampleFiles.report.applicationAdded", "PERSONSSAPSAMPLE")) == 1);
	}

	/** {@code PersonsSapSample} as measured, 2026-09-23 (task brief): LINKED, mapping found, last scan OK with assets - the fully onboarded, ready-to-pick case. Same fixture as {@code SourceOnboardingTest.personsSapSampleAsMeasuredReadyToPick}. */
	private static SourceOnboarding.Facts readySourceFacts()
	{
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "PersonsSapSample";
		facts.connectorFound = true;
		facts.connectorActive = true;
		facts.connectorType = "sample-file";
		facts.catalogOperation = true;
		facts.catalogState = "VERIFIED";
		facts.planAction = "LINKED";
		facts.planSourceId = "PERSONSSAPSAMPLE";
		facts.planMessage = "Source PERSONSSAPSAMPLE already names connector PersonsSapSample with namespace 'sample-file://persons-sap.sample-file.local'.";
		facts.sourceExists = true;
		facts.sourceActive = true;
		facts.scanMappingId = Integer.valueOf(236);
		facts.lastScanStatus = "OK";
		facts.assetsScanned = true;
		facts.assetsWithColumns = true;
		return facts;
	}

	/** SourceBidon as measured, 2026-09-23 (task brief): CREATE, no source, no mapping - same fixture as {@code SourceOnboardingTest.sourceBidonAsMeasuredCreateNoSourceNoMapping}. */
	private static SourceOnboarding.Facts sourceBidonFacts()
	{
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = "SourceBidon";
		facts.connectorFound = true;
		facts.connectorActive = true;
		facts.connectorType = "sample-file";
		facts.catalogOperation = true;
		facts.catalogState = "VERIFIED";
		facts.planAction = "CREATE";
		facts.planSourceId = "SOURCEBIDON";
		facts.planMessage = "No Source for connector SourceBidon: create Source SOURCEBIDON with namespace 'sample-file://localhost:8123'; the catalog then creates its flows.";
		return facts;
	}

	/** Completed-rail lot (2026-09-23): the rail ticks every completed step, even the ones after the open one; the open step keeps "current". */
	private static void railTicksCompletedStepsWhateverStepIsOpen()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_PROJECT;
		final String html = WizardPage.render(model);
		// this fixture has no LOADED table: project and sources done, landing open but not done, staging blocked
		WizardPageTest.check("step 1 is current and done", html.contains("<li data-step=\"1\" class=\"current done\">"));
		WizardPageTest.check("step 2 is done although after the current step", html.contains("<li data-step=\"2\" class=\"done\">"));
		WizardPageTest.check("step 3 is reachable but not done", html.contains("<li data-step=\"3\">"));
		WizardPageTest.check("step 4 is blocked", html.contains("<li data-step=\"4\" class=\"blocked\" title=\""));
		// distribution-wizard lot (2026-09-24): step 7 is now "Distribution" (blocked until the master is
		// loaded, same prerequisite as Matching - this fixture has no master load yet); step 8 "Summary" keeps
		// the old "always reachable, never done" assertion this line used to make about step 7.
		WizardPageTest.check("step 7 (distribution) blocked: the master is not loaded yet", html.contains("<li data-step=\"7\" class=\"blocked\" title=\""));
		WizardPageTest.check("step 8 (summary) is never done", html.contains("<li data-step=\"8\">"));

		model.snapshot.sources.get(0).tables.get(0).status = "LOADED";
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String loadedHtml = WizardPage.render(model);
		WizardPageTest.check("step 3 done once a table is loaded", loadedHtml.contains("<li data-step=\"3\" class=\"done\">"));
		WizardPageTest.check("step 4 done: the fixture holds a staging entity", loadedHtml.contains("<li data-step=\"4\" class=\"done\">"));
		// step-gating lot (2026-09-24): every ticked table LOADED is no longer enough on its own for step 5 to
		// be reachable - the staging itself must be loaded (STAGING_LOAD DONE, WizardRules.stepStates' own
		// Transfers rule); without it step 5 is now blocked, not merely "not done".
		WizardPageTest.check("step 5 blocked: staging not loaded yet", loadedHtml.contains("<li data-step=\"5\" class=\"blocked\" title=\""));

		model.snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String stagingLoadedHtml = WizardPage.render(model);
		WizardPageTest.check("step 5 reachable but not done once the staging is loaded", stagingLoadedHtml.contains("<li data-step=\"5\">")
			&& !stagingLoadedHtml.contains("<li data-step=\"5\" class=\"blocked\" title=\"") && !stagingLoadedHtml.contains("<li data-step=\"5\" class=\"done\">"));

		final WizardPage.Model blank = WizardPageTest.model();
		blank.currentStep = WizardRules.STEP_SUMMARY;
		final String blankHtml = WizardPage.render(blank);
		WizardPageTest.check("a blank project ticks nothing, even before the open step", !blankHtml.contains("class=\"done\"") && blankHtml.contains("<li data-step=\"1\">"));
		WizardPageTest.check("a blocked step is marked blocked", blankHtml.contains("<li data-step=\"2\" class=\"blocked\" title=\""));
	}

	private static void landingTableRowRendersKeyEditAndTrack()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);
		WizardPageTest.check("a PROPOSED table gets an editable key input", html.contains("class=\"mdmf-key-input\""));
		WizardPageTest.check("the pipeline track shows the model step", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.pipelineStep.LANDING_MODEL")));
	}

	/**
	 * Landing key-study lot (2026-09-23): the panel under the SUPPLIER row shows the language model's proposal
	 * with its origin and confidence, the "Apply this key" button targeting that table, the per-column
	 * evidence (sample values as code, the column absent from the sample said so) and the sample rows; a
	 * table whose landing was generated keeps the panel but loses the Apply button.
	 */
	private static void keyStudyPanelRendersProposalEvidenceAndApply()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final WizardSnapshot.TableInfo supplier = model.snapshot.sources.get(0).tables.get(0);
		supplier.keyOrigin = "RULE";
		supplier.keyRationale = "No key declared at the source; SUPPLIER_ID is named like the identifier of the table.";

		final KeyStudyReport report = new KeyStudyReport();
		report.tableId = "10";
		report.tableName = "SUPPLIER";
		report.sourceObject = "SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER";
		report.editable = true;
		report.sampleColumns.addAll(java.util.Arrays.asList("SUPPLIER_ID", "NAME1"));
		report.sampleRows.add(java.util.Arrays.asList("S-001", "Acme <b>"));
		report.sampleRows.add(java.util.Arrays.asList("S-002", "Acme <b>"));
		final java.util.List<KeyEvidence.Column> evidence = KeyEvidence.analyse(report.sampleColumns, report.sampleRows);
		report.columns.add(new KeyStudyReport.ColumnRow("SUPPLIER_ID", "VARCHAR", true, KeyEvidence.find(evidence, "SUPPLIER_ID")));
		report.columns.add(new KeyStudyReport.ColumnRow("NAME1", "VARCHAR", false, KeyEvidence.find(evidence, "NAME1")));
		report.columns.add(new KeyStudyReport.ColumnRow("REGION", "VARCHAR", false, null));
		report.proposal = new KeyStudyReport.Proposal(java.util.Arrays.asList("SUPPLIER_ID"), 90, "The supplier number is the system's own identifier.", KeyStudyReport.ORIGIN_LLM);
		report.proposalUniqueInSample = Boolean.TRUE;
		report.llmRan = true;
		report.llmSteps = 2;
		report.llmInputTokens = 1200;
		report.llmOutputTokens = 80;
		model.keyStudy = report;
		model.keyStudyTableId = "10";

		String html = WizardPage.render(model);
		WizardPageTest.check("the row offers the study button for its table", html.contains("data-mdmf-action=\"TABLE_KEY_STUDY\" data-mdmf-source-id=\"1\" data-mdmf-table-id=\"10\""));
		WizardPageTest.check("the row says who proposed the current key and folds its rationale",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyOrigin.RULE")) && html.contains("SUPPLIER_ID is named like the identifier"));
		WizardPageTest.check("the panel is rendered for that table", html.contains("id=\"mdmfKeyStudy10\""));
		WizardPageTest.check("the proposal names its columns and its origin",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyStudy.proposal", "SUPPLIER_ID")) && html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyOrigin.LLM")));
		WizardPageTest.check("the confidence bar shows 90 %", html.contains("width:90%"));
		WizardPageTest.check("the apply button targets the table and is enabled",
			html.contains("data-mdmf-action=\"TABLE_KEY_APPLY\" data-mdmf-source-id=\"1\" data-mdmf-table-id=\"10\">"));
		WizardPageTest.check("uniqueness on the sample is stated with the row count",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyStudy.uniqueYes.other", Integer.valueOf(2))));
		WizardPageTest.check("the language-model line discloses that the sample rows were sent",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyStudy.llm.ran", WizardMessages.countWords(Locale.ENGLISH, 2, "wizard.unit.step"),
				WizardMessages.countWords(Locale.ENGLISH, 1200, "wizard.unit.token"), WizardMessages.countWords(Locale.ENGLISH, 80, "wizard.unit.token"),
				WizardMessages.countWords(Locale.ENGLISH, 2, "wizard.unit.row"))));
		WizardPageTest.check("sample values are shown as code, escaped", html.contains("<code>S-001</code>") && html.contains("<code>Acme &lt;b&gt;</code>"));
		WizardPageTest.check("distinct / empty counts are shown", html.contains("<td class=\"num\">2 / 0</td>") && html.contains("<td class=\"num\">1 / 0</td>"));
		WizardPageTest.check("a column absent from the sample says so", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyStudy.notInSample")));
		WizardPageTest.check("the proposed column's row is highlighted", html.contains("<tr class=\"mdmf-key-proposed\"><td><b>SUPPLIER_ID</b>"));
		WizardPageTest.check("the current-key chip marks SUPPLIER_ID", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyStudy.current")));
		WizardPageTest.check("the sample rows are listed", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyStudy.sample", Integer.valueOf(2))));

		report.editable = false;
		html = WizardPage.render(model);
		WizardPageTest.check("a generated table's apply button is disabled with the reason",
			html.contains("data-mdmf-action=\"TABLE_KEY_APPLY\" data-mdmf-source-id=\"1\" data-mdmf-table-id=\"10\" disabled=\"disabled\"")
				&& html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyStudy.notEditable"))));

		report.proposal = null;
		report.llmRan = false;
		report.llmSkippedReasonKey = "wizard.gate.noLlm";
		html = WizardPage.render(model);
		WizardPageTest.check("no proposal: the hint replaces the block and no apply button renders",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyStudy.noProposal")) && !html.contains("TABLE_KEY_APPLY"));
		WizardPageTest.check("the skipped language model is explained with its gate reason",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyStudy.llm.skipped", WizardMessages.get(Locale.ENGLISH, "wizard.gate.noLlm"))));

		model.keyStudyTableId = "";
		html = WizardPage.render(model);
		WizardPageTest.check("no panel without a studied table", !html.contains("mdmfKeyStudy10"));
	}

	private static void disabledGeneratedButtonShowsGateReason()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.administrator = false; model.designer = false; // audit F-02: the design actions follow DESIGN
		final String html = WizardPage.render(model);
		WizardPageTest.check("generate is disabled for a non-administrator", html.contains("data-mdmf-action=\"GENERATE\" data-mdmf-source-id=\"1\" disabled=\"disabled\""));
		WizardPageTest.check("the reason is shown as a plain sentence", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.gate.adminOnly")));
	}

	private static void stagingEntityRendersAcceptRejectButtons()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);
		WizardPageTest.check("an entity accept button targets the right nature/id",
			html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ENTITY\" data-mdmf-id=\"Supplier\""));
		WizardPageTest.check("an attribute accept button targets the right nature/id",
			html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a1\""));
	}

	/**
	 * The exact defect from the product-owner screenshot ("the Accepted tag seems to be set on attributes, but
	 * I can still Accept them") proven per status, for both an ENTITY row (Account, ACCEPTED) and ATTRIBUTE
	 * rows (a5 REJECTED, a6 APPLIED, a7 ACCEPTED) - {@link DecisionActions#forStatus} drives both.
	 */
	private static void decisionCellOffersExactlyTheActionsForEachStatus()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);

		// ENTITY, ACCEPTED (Account): only Reject
		WizardPageTest.check("an ACCEPTED entity offers no Accept button",
			!html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ENTITY\" data-mdmf-id=\"Account\""));
		WizardPageTest.check("an ACCEPTED entity still offers Reject",
			html.contains("data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"ENTITY\" data-mdmf-id=\"Account\""));

		// ATTRIBUTE, ACCEPTED (a7): only Reject
		WizardPageTest.check("an ACCEPTED attribute offers no Accept button",
			!html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a7\""));
		WizardPageTest.check("an ACCEPTED attribute still offers Reject",
			html.contains("data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a7\""));

		// ATTRIBUTE, REJECTED (a5): only Accept
		WizardPageTest.check("a REJECTED attribute offers no Reject button",
			!html.contains("data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a5\""));
		WizardPageTest.check("a REJECTED attribute still offers Accept",
			html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a5\""));

		// ATTRIBUTE, APPLIED (a6): neither - chip only
		WizardPageTest.check("an APPLIED attribute offers no Accept button",
			!html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a6\""));
		WizardPageTest.check("an APPLIED attribute offers no Reject button either",
			!html.contains("data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a6\""));

		// PROPOSED (Supplier entity, a1): both, still (the one case where both were always correct)
		WizardPageTest.check("a PROPOSED entity offers both Accept and Reject",
			html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ENTITY\" data-mdmf-id=\"Supplier\"")
				&& html.contains("data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"ENTITY\" data-mdmf-id=\"Supplier\""));
	}

	/**
	 * No row - entity or attribute, whatever its status - ever shows its status twice (the entity header used to
	 * draw its own status chip, then {@code renderInlineDecide} drew a second one right after it).
	 */
	private static void noRowEverShowsTwoStatusChips()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);
		// exactly one ACCEPTED-status chip rendered for the Account entity row and one for the a7 attribute row -
		// never two side by side for the same row, which is what the duplicated-chip bug looked like
		WizardPageTest.check("the word 'Accepted' appears exactly twice as chip text (Account entity + a7 attribute), never doubled on one row",
			WizardPageTest.count(html, ">" + WizardMessages.get(Locale.ENGLISH, "wizard.status.ACCEPTED") + "</span>") == 2);
		WizardPageTest.check("the word 'Rejected' appears exactly once as chip text (a5)",
			WizardPageTest.count(html, ">" + WizardMessages.get(Locale.ENGLISH, "wizard.status.REJECTED") + "</span>") == 1);
		WizardPageTest.check("the word 'Applied' appears exactly once as chip text (a6)",
			WizardPageTest.count(html, ">" + WizardMessages.get(Locale.ENGLISH, "wizard.status.APPLIED") + "</span>") == 1);
	}

	/** A technical-key attribute (primaryKey) has no decision of its own: a "follows the entity" chip, never Accept/Reject buttons (product owner, 2026-09-21). */
	private static void primaryKeyAttributeShowsFollowsEntityChipNotButtons()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);
		WizardPageTest.check("no accept button targets the primaryKey attribute (a2)",
			!html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a2\""));
		WizardPageTest.check("no reject button targets the primaryKey attribute (a2)",
			!html.contains("data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a2\""));
		WizardPageTest.check("the follows-the-entity chip is rendered", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.key.followsEntity")));
		// a regular (non-key) attribute still gets its buttons
		WizardPageTest.check("a1 (business key, not primary key) still gets Accept/Reject",
			html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a1\"")
				&& html.contains("data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"a1\""));
	}

	/** The Account card (accepted, only its technical key accepted) shows the "waiting for an attribute" hint; the Supplier card (nothing accepted) does not. */
	private static void entityWaitingHintShownWhenGenerationWouldSkipIt()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);
		WizardPageTest.check("the waiting hint for Account is shown",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.entity.waitingHint.one", "Account", Integer.valueOf(1))));
	}

	/** A canonical entity's card shows which entities it unifies (StagingEntity.memberEntities, via ReviewEntityFormat). */
	private static void canonicalEntityCardShowsItsMembers()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);
		WizardPageTest.check("the unifies sentence is shown for Account",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.entity.unifies", "Supplier (ERP), Account (CRM)")));
	}

	/**
	 * Staging-focus lot (2026-09-23, the product owner on project A: "il faut simplifier l'ecran ... les entites
	 * superseded ... ne doivent pas apparaitre"): a rule entity the language model absorbed into a canonical
	 * one is SUPERSEDED - out of the choice, and now out of the page entirely: no card at all, grayed or
	 * otherwise, and its own proposals (still PROPOSED underneath, e.g. its attributes) are not offered for
	 * decision because there is no card to offer them on. It is untouched in the snapshot (WizardData keeps
	 * reading it - other screens, e.g. Proposal review, still show it with its full row).
	 */
	private static void supersededEntityCardIsNotRendered()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.StagingEntityInfo supplier = new WizardSnapshot.StagingEntityInfo();
		supplier.id = "SupplierErp";
		supplier.name = "SupplierErp";
		supplier.origin = "RULE";
		supplier.status = "SUPERSEDED";
		supplier.supersededBy = "Account";
		final WizardSnapshot.AttributeInfo attribute = new WizardSnapshot.AttributeInfo();
		attribute.id = "a9";
		attribute.name = "supplierName";
		attribute.status = "PROPOSED";
		supplier.attributes.add(attribute);
		model.snapshot.stagingEntities.add(supplier);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);
		WizardPageTest.check("no anchor for the superseded entity's own card", !html.contains("id=\"mdmf-ENTITY-SupplierErp\""));
		WizardPageTest.check("the muted class is gone, not just unused on a hidden card", !html.contains("mdmf-entity-superseded"));
		WizardPageTest.check("no Accept/Reject on the superseded entity itself", !html.contains("data-mdmf-nature=\"ENTITY\" data-mdmf-id=\"SupplierErp\""));
		WizardPageTest.check("its attributes are not offered for decision either", !html.contains("data-mdmf-id=\"a9\""));
		// the other demo entities (Supplier, Account - see demoSnapshot) still render normally
		WizardPageTest.check("an ordinary entity's card is still there", html.contains("id=\"mdmf-ENTITY-Supplier\""));

		// a member a person already decided on (here REJECTED) keeps its ordinary, VISIBLE card and buttons,
		// but says which canonical it belongs to and that it never reaches master (project Personnes,
		// 2026-09-23) - only SUPERSEDED itself is hidden, not every member of a canonical
		final WizardSnapshot.StagingEntityInfo rejectedMember = new WizardSnapshot.StagingEntityInfo();
		rejectedMember.id = "SapErp";
		rejectedMember.name = "SapErp";
		rejectedMember.origin = "RULE";
		rejectedMember.status = "REJECTED";
		rejectedMember.supersededBy = "Account";
		model.snapshot.stagingEntities.add(rejectedMember);
		final String html2 = WizardPage.render(model);
		WizardPageTest.check("a decided member's card is rendered", html2.contains("id=\"mdmf-ENTITY-SapErp\""));
		WizardPageTest.check("a decided member names its canonical and the staging-only consequence",
			html2.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.entity.memberOf", "Account")));
		WizardPageTest.check("a REJECTED member still offers Accept (a decision stays reversible)",
			html2.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ENTITY\" data-mdmf-id=\"SapErp\""));
		WizardPageTest.check("the memberOf sentence is not drawn on an entity that is nobody's member",
			WizardPageTest.count(html2, WizardMessages.get(Locale.ENGLISH, "wizard.step4.entity.memberOf", "Account")) == 1);
	}

	/**
	 * Target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): an entity's feeds render as a tree - the root feed
	 * first, a child feed right after it, indented ({@code mdmf-feed-child}) and labelled "(child of ...)", with
	 * its own mapping table underneath.
	 */
	private static void feedTreeRendersRootAndIndentedChildFeed()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.StagingEntityInfo entity = model.snapshot.stagingEntities.get(0); // "Supplier"

		final WizardSnapshot.FeedInfo rootFeed = new WizardSnapshot.FeedInfo();
		rootFeed.id = "f1";
		rootFeed.landingTableName = "SUPPLIER";
		rootFeed.sourceSystem = "ERP";
		rootFeed.origin = "RULE";
		rootFeed.status = "PROPOSED";
		// feed-designer lot (2026-09-23): ProjectWizardUserService.model() sets this only on a root feed
		// (core.FeedDesignerLink#url) - WizardPage itself never builds the URL, it only renders it when present.
		rootFeed.designerUrl = "/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project?service=FeedDesigner&serviceMode=TABLE_VIEW&serviceParams=eyJmZWVkIjoiZjEifQ%3D%3D";
		final WizardSnapshot.FeedMappingInfo rootMapping = new WizardSnapshot.FeedMappingInfo();
		rootMapping.id = "m1";
		rootMapping.landingColumn = "SUPPLIER_NAME";
		rootMapping.attributeName = "supplierName";
		rootMapping.transformationDisplay = "TRIM";
		rootMapping.status = "PROPOSED";
		rootFeed.mappings.add(rootMapping);
		entity.feeds.add(rootFeed);

		final WizardSnapshot.FeedInfo childFeed = new WizardSnapshot.FeedInfo();
		childFeed.id = "f2";
		childFeed.landingTableName = "SUPPLIER";
		childFeed.sourceSystem = "ERP";
		childFeed.parentFeedId = "f1";
		childFeed.parentEntityName = "Supplier";
		childFeed.parentLinkField = "supplierRef";
		childFeed.sourceIdPipeline = "concat(ID)|suffix(#ADDR)";
		childFeed.origin = "RULE";
		childFeed.status = "PROPOSED";
		entity.feeds.add(childFeed);

		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);

		WizardPageTest.check("both feeds render with a stable scroll-anchor id", html.contains("id=\"mdmf-FEED-f1\"") && html.contains("id=\"mdmf-FEED-f2\""));
		WizardPageTest.check("the child feed carries the indentation class", html.contains("mdmf-feed mdmf-feed-child"));
		WizardPageTest.check("the root feed does not", html.contains("mdmf-feed\" id=\"mdmf-FEED-f1\""));
		WizardPageTest.check("the child feed names its parent entity",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.feed.childOf", "Supplier")));
		WizardPageTest.check("the child feed shows its parent link field",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.feed.parentLink", "supplierRef")));
		WizardPageTest.check("the root feed renders before the child feed", html.indexOf("mdmf-FEED-f1") < html.indexOf("mdmf-FEED-f2"));
		WizardPageTest.check("the root feed's mapping row is rendered", html.contains("mdmf-MAPPING-m1"));
		WizardPageTest.check("a PROPOSED mapping's pipeline is editable in place", html.contains("mdmf-mapping-pipeline-input"));
		WizardPageTest.check("the child feed's sourceIdPipeline is editable in place and shows the current value",
			html.contains("mdmf-feed-sourceIdPipeline-input") && html.contains("concat(ID)|suffix(#ADDR)"));
		// feed-designer lot (2026-09-23): the root feed's "Designer" link opens in the TOP window (target="_top",
		// never a modal iframe - CLAUDE.md sec 9), and the class it renders with is exactly the selector
		// FactoryHtml#originCaptureScript arms in ProjectWizardUserService's own write() (".mdmf-feed-designer-link").
		WizardPageTest.check("the root feed's card carries a Designer link, opened in the top window",
			html.contains("mdmf-feed-designer-link") && html.contains("serviceParams=eyJmZWVkIjoiZjEifQ") && html.contains("target=\"_top\""));
		WizardPageTest.check("the child feed's own designerUrl is empty, so its card carries no Designer link",
			WizardPageTest.count(html, "mdmf-feed-designer-link") == 1);
	}

	/**
	 * Target-mapping lot 3: when a child feed is accepted but its own parent feed is not, "Load the staging" is
	 * disabled and the hint names the entity - the plain-English detail {@link WizardRules.Gate#detail} carries,
	 * appended after the generic localized sentence.
	 */
	private static void loadStagingGateNamesTheMissingFeedCoverage()
	{
		final WizardPage.Model model = WizardPageTest.model();
		final WizardSnapshot.StagingEntityInfo parent = new WizardSnapshot.StagingEntityInfo();
		parent.id = "e1";
		parent.name = "Personne";
		parent.status = "ACCEPTED";
		final WizardSnapshot.FeedInfo parentRootFeed = new WizardSnapshot.FeedInfo();
		parentRootFeed.id = "f1";
		parentRootFeed.status = "PROPOSED"; // never accepted - the child below still names it as its parent
		parent.feeds.add(parentRootFeed);
		model.snapshot.stagingEntities.add(parent);

		final WizardSnapshot.StagingEntityInfo child = new WizardSnapshot.StagingEntityInfo();
		child.id = "e2";
		child.name = "Adresse";
		child.status = "ACCEPTED";
		final WizardSnapshot.FeedInfo childFeed = new WizardSnapshot.FeedInfo();
		childFeed.id = "f2";
		childFeed.parentFeedId = "f1";
		childFeed.landingTableName = "LANDING_ADDRESS";
		childFeed.status = "ACCEPTED";
		child.feeds.add(childFeed);
		model.snapshot.stagingEntities.add(child);

		model.snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_DATASET", "DONE", ""));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);

		WizardPageTest.check("the Load the staging button is disabled", html.contains("data-mdmf-action=\"STAGING_LOAD\" disabled=\"disabled\""));
		WizardPageTest.check("the generic feed-coverage reason is shown", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.gate.needFeeds")));
		WizardPageTest.check("the plain-English detail names Personne's missing accepted feed", html.contains("Personne"));
	}

	// ------------------------------------------------------------------------------------------ step-reset (2026-09-23)

	/** The "Clean this step and the following ones" button, administrators only, on steps 3 (Landing)/4 (Staging)/5 (Master)/6 (Matching), each with its own {@code data-mdmf-reset-stage}. */
	private static void stepResetButtonPresentForAdminOnSteps3to6()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);
		WizardPageTest.check("LANDING reset button", html.contains("data-mdmf-action=\"STEP_RESET_PLAN\" data-mdmf-reset-stage=\"LANDING\""));
		WizardPageTest.check("STAGING reset button", html.contains("data-mdmf-action=\"STEP_RESET_PLAN\" data-mdmf-reset-stage=\"STAGING\""));
		WizardPageTest.check("MASTER reset button", html.contains("data-mdmf-action=\"STEP_RESET_PLAN\" data-mdmf-reset-stage=\"MASTER\""));
		WizardPageTest.check("MATCHING reset button", html.contains("data-mdmf-action=\"STEP_RESET_PLAN\" data-mdmf-reset-stage=\"MATCHING\""));
	}

	private static void stepResetButtonAbsentForNonAdministrator()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.administrator = false;
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String html = WizardPage.render(model);
		WizardPageTest.check("no reset button of any kind for a non-administrator", !html.contains("STEP_RESET_PLAN"));
	}

	/** The confirmation panel opens on exactly the one step {@link WizardPage.Model#stepReset} names - never on another step's own button. */
	private static void stepResetConfirmPanelRendersPlanOnItsOwnStepOnly()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		model.stepReset = new WizardPage.StepResetView();
		model.stepReset.stage = "STAGING";
		final WizardPage.StepResetItem item = new WizardPage.StepResetItem();
		item.kind = "STAGING_DATASPACE";
		item.name = "Companies_Staging";
		item.detail = "Dataset Staging and its own tables go with it.";
		item.disposition = "DELETE";
		model.stepReset.items.add(item);
		model.stepReset.rowCounts.put("StagingEntity", Integer.valueOf(3));
		model.stepReset.stepNames.add("STAGING_LOAD");
		final String html = WizardPage.render(model);
		WizardPageTest.check("the item is listed", html.contains("Companies_Staging") && html.contains("Dataset Staging and its own tables go with it."));
		WizardPageTest.check("the row count table is shown", html.contains("StagingEntity") && html.contains(">3<"));
		WizardPageTest.check("the step name is named", html.contains("STAGING_LOAD"));
		WizardPageTest.check("the confirm field is present", html.contains("data-mdmf-action=\"STEP_RESET_CONFIRM\" data-mdmf-reset-stage=\"STAGING\""));
		WizardPageTest.check("a cancel button is present", html.contains("data-mdmf-action=\"STEP_RESET_CANCEL\""));
		// the panel is inside the STAGING step's own section - MASTER's own "Clean this step..." button still
		// shows its plain open button, not a second confirmation panel
		final int stagingSection = html.indexOf("mdmf-wiz-panel\" data-step=\"4\"");
		final int masterSection = html.indexOf("mdmf-wiz-panel\" data-step=\"5\"");
		WizardPageTest.check("the confirm panel sits inside the STAGING section, before the MASTER one",
			html.indexOf("data-mdmf-action=\"STEP_RESET_CONFIRM\"") > stagingSection && html.indexOf("data-mdmf-action=\"STEP_RESET_CONFIRM\"") < masterSection);
	}

	/** A refused plan (delivered project, or an ineligible generated object) never offers a confirm field - only a close button. */
	private static void stepResetRefusalPanelHasNoConfirmField()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		model.stepReset = new WizardPage.StepResetView();
		model.stepReset.stage = "STAGING";
		model.stepReset.refusal = "this project was delivered here by EBX Staging: remove it on the origin instance, or by hand.";
		final String html = WizardPage.render(model);
		WizardPageTest.check("the refusal text is shown", html.contains("delivered here by EBX Staging"));
		WizardPageTest.check("no confirm field on a refused plan", !html.contains("data-mdmf-action=\"STEP_RESET_CONFIRM\""));
		WizardPageTest.check("a close button is offered instead", html.contains("data-mdmf-action=\"STEP_RESET_CANCEL\""));
	}

	/** Once {@code STEP_RESET_CONFIRM} has actually run, the panel shows only the result notice - not the plan/confirm field again. */
	private static void stepResetDonePanelShowsResultNoticeOnly()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		model.stepReset = new WizardPage.StepResetView();
		model.stepReset.stage = "STAGING";
		model.stepReset.done = true;
		model.stepReset.resultOk = true;
		model.stepReset.resultMessage = "4 pipeline steps reset, 12 rows deleted.";
		final String html = WizardPage.render(model);
		WizardPageTest.check("the result message is shown", html.contains("4 pipeline steps reset, 12 rows deleted."));
		WizardPageTest.check("no confirm field once done", !html.contains("data-mdmf-action=\"STEP_RESET_CONFIRM\""));
	}

	/** Pipeline-buttons lot (2026-09-23): step 5 is "Master" now, with real Generate/Load buttons, not a placeholder. */
	private static void step5OffersMasterGenerateAndLoad()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_TRANSFERS;
		final String html = WizardPage.render(model);
		WizardPageTest.check("Generate the master button exists", html.contains("data-mdmf-action=\"MASTER_GENERATE\""));
		WizardPageTest.check("Load the master button exists", html.contains("data-mdmf-action=\"MASTER_LOAD\""));
		WizardPageTest.check("no leftover not-yet placeholder", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.notYet") + "</span></h2>"));
		// nothing generated yet: both gated on a PipelineStep this fixture never wrote
		WizardPageTest.check("generate master disabled: staging never loaded", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.gate.needStagingLoad")));
	}

	// ---- wizard-visual-pass lot (2026-09-24): step 4e "Fill the shared structure" / step 5 "Build the master"

	private static void stagingLoadRunPrimaryButtonAndAdvancedBothRender()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.stagingEntities.get(0).status = "ACCEPTED";
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("primary one-click button exists", html.contains("data-mdmf-action=\"STAGING_LOAD_RUN\""));
		WizardPageTest.check("UX lot U4: the 'Fill the shared structure' title panel is gone, the button lives in the primary block", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.build.run.title")));
		WizardPageTest.check("old Generate button still reachable under Advanced", html.contains("data-mdmf-action=\"STAGING_GENERATE\""));
		WizardPageTest.check("old Load button still reachable under Advanced", html.contains("data-mdmf-action=\"STAGING_LOAD\""));
		WizardPageTest.check("no grid yet: run never happened", !html.contains("mdmf-run-grid"));
	}

	private static void stagingLoadRunRunningShowsTheLivePanel()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		model.stagingLoadRunRunning = true;
		final RunProgress progress = RunProgress.newRun(Arrays.asList("MODEL", "LOAD"));
		progress.start("MODEL", 1000L);
		progress.finish("MODEL", true, "Staging model at BCompanies_Staging/Staging.", 23000L);
		progress.start("LOAD", 24000L);
		progress.unitPlanned("Personne", "SAP");
		progress.unitPlanned("Personne", "SF");
		progress.unitStart("Personne", "SAP", 165L);
		progress.unitProgress("Personne", "SAP", 96L, 95L, 1L);
		progress.activity("LOAD", "Personne \u00B7 SAP: 96 of 165 rows read, 95 written, 1 held back.");
		model.stagingLoadRunProgress = progress;
		model.stagingLoadRunJobId = "77";
		final String html = WizardPage.render(model);
		WizardPageTest.check("poll attribute armed on the wizard root", html.contains("data-mdmf-staging-load-running=\"true\""));
		WizardPageTest.check("staging-live: the live panel", html.contains("id=\"mdmfStagingLive\"") && html.contains("data-mdmf-job-id=\"77\""));
		WizardPageTest.check("staging-live: what runs now", html.contains("Personne \u00B7 SAP: 96 of 165 rows read, 95 written, 1 held back."));
		WizardPageTest.check("staging-live: both stages as chips, MODEL with its duration",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.live.stage.MODEL") + " \u00B7 22 s") && html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.live.stage.LOAD")));
		WizardPageTest.check("staging-live: the bar counts rows", html.contains("96 of 165 rows"));
		WizardPageTest.check("staging-live: one column per application", html.contains("<th>SAP</th>") && html.contains("<th>SF</th>"));
		WizardPageTest.check("staging-live: the running cell counts", html.contains("95 written \u00B7 1 held back"));
		WizardPageTest.check("staging-live: a planned cell says Next", html.contains(">" + WizardMessages.get(Locale.ENGLISH, "wizard.step4.live.cell.next") + "<"));
		WizardPageTest.check("staging-live: one Stop", html.contains("id=\"mdmfSlStop\""));
		WizardPageTest.check("staging-live: the journal", html.contains("id=\"mdmfSlJournal\""));
		WizardPageTest.check("no primary button while running", !html.contains("data-mdmf-action=\"STAGING_LOAD_RUN\""));
	}

	private static void stagingLoadGridRendersRowsAndHeldBackBanner()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		model.stagingLoadRunExists = true;
		final WizardSnapshot.RunGridInfo grid = new WizardSnapshot.RunGridInfo();
		grid.columnLabels.add("SAP");
		grid.columnLabels.add(WizardMessages.get(Locale.ENGLISH, "wizard.step4.grid.inStaging"));
		final WizardSnapshot.RunGridRow row = new WizardSnapshot.RunGridRow();
		row.label = "Person";
		row.sublabel = "9 fields";
		final WizardSnapshot.RunGridCell heldCell = new WizardSnapshot.RunGridCell("NEEDS_PERSON", "165 rows", "3 held back");
		heldCell.heldBackValue = Long.valueOf(3);
		row.cells.add(heldCell);
		row.cells.add(new WizardSnapshot.RunGridCell("DONE", "165 rows", ""));
		grid.rows.add(row);
		model.stagingLoadGrid = grid;
		model.stagingHeldBackCount = 3;
		// no "&" on purpose: WizardPage.esc HTML-escapes href values, this assertion compares the raw URL as-is
		model.stagingRejectsUrl = "/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project";
		final String html = WizardPage.render(model);
		WizardPageTest.check("grid table rendered", html.contains("mdmf-run-grid"));
		WizardPageTest.check("row label rendered", html.contains(">Person<"));
		WizardPageTest.check("primary cell text rendered", html.contains("165 rows"));
		WizardPageTest.check("held-back secondary text rendered", html.contains("3 held back"));
		// staging-screens lot (2026-10-17): the banner is the FIRST thing of step 4 (above the primary block), names the object, and does not blame a rule
		final String banner = "3 rows of Person were not loaded: refused at load time.";
		final String step4 = html.substring(html.indexOf("<section class=\"mdmf-wiz-panel\" data-step=\"4\""));
		WizardPageTest.check("held-back banner rendered at the top of step 4, naming the object, neutral about the cause",
			step4.contains(banner) && step4.indexOf(banner) < step4.indexOf("mdmf-primary-block") && !html.contains("a rule set to stop the row"));
		WizardPageTest.check("the old banner far below the grid is gone", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.build.heldBack.banner.other", Long.valueOf(3)))
			&& step4.indexOf(banner) < step4.indexOf("mdmf-run-grid"));
		WizardPageTest.check("review link points at the Cockpit URL", html.contains(model.stagingRejectsUrl));
		WizardPageTest.check("a build already ran but points are left: the early build sits under Advanced (UX lot U4; 'Build again' is the primary block's once nothing is left)",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.primary.buildEarly")) && html.contains("name=\"mdmfStagingScopeAll\""));
	}

	private static void masterBuildBeforeRunShowsExplainCardsAndPrimaryButton()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_TRANSFERS;
		final String html = WizardPage.render(model);
		WizardPageTest.check("primary Build the master button exists", html.contains("data-mdmf-action=\"MASTER_BUILD_RUN\""));
		WizardPageTest.check("three explain cards render", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.build.explain.tables.title"))
			&& html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.build.explain.copy.title"))
			&& html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.build.explain.matching.title")));
		WizardPageTest.check("old Generate/Load buttons still reachable under Advanced",
			html.contains("data-mdmf-action=\"MASTER_GENERATE\"") && html.contains("data-mdmf-action=\"MASTER_LOAD\""));
		WizardPageTest.check("no done grid yet", !html.contains("mdmf-run-grid"));
	}

	private static void masterBuildRunningShowsStageListAndArmsThePoll()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_TRANSFERS;
		model.masterBuildRunRunning = true;
		final RunProgress progress = RunProgress.newRun(Arrays.asList("TABLES", "COPY", "MATCHING_READY"));
		progress.start("TABLES", 0L);
		progress.finish("TABLES", true, "2 business objects, dataset BCompanies_Master/Master.", 112000L);
		progress.start("COPY", 113000L);
		progress.unitPlanned("Personne", "COPY");
		progress.unitPlanned("Adresse", "COPY");
		progress.unitStart("Personne", "COPY", 345L);
		progress.unitProgress("Personne", "COPY", 180L, 180L, 0L);
		progress.activity("COPY", "Personne: 180 of 345 rows read, 180 copied.");
		model.masterBuildRunProgress = progress;
		model.masterBuildRunJobId = "91";
		final String html = WizardPage.render(model);
		WizardPageTest.check("poll attribute armed on the wizard root", html.contains("data-mdmf-master-build-running=\"true\""));
		// master-live lot (2026-09-27): the live panel, grid = business object x the stages
		WizardPageTest.check("master-live: the live panel", html.contains("id=\"mdmfMasterLive\" data-mdmf-lr-prefix=\"mdmfMb\" data-mdmf-lr-stage-grid=\"true\""));
		WizardPageTest.check("master-live: what runs now", html.contains("Personne: 180 of 345 rows read, 180 copied."));
		WizardPageTest.check("master-live: Tables chip with its duration",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.live.stage.TABLES") + " \u00B7 1 min 52"));
		WizardPageTest.check("master-live: a column per stage", html.contains("<th>2 \u00B7 " + WizardMessages.get(Locale.ENGLISH, "wizard.step5.live.stage.COPY") + "</th>")
			&& html.contains("<th>3 \u00B7 " + WizardMessages.get(Locale.ENGLISH, "wizard.step5.live.stage.MATCHING_READY") + "</th>"));
		WizardPageTest.check("master-live: the copy counts real rows", html.contains("180 of 345 rows") && html.contains("180 written"));
		WizardPageTest.check("master-live: Adresse waits", html.contains(">" + WizardMessages.get(Locale.ENGLISH, "wizard.step4.live.cell.next") + "<"));
		WizardPageTest.check("master-live: the bar counts rows", html.contains("id=\"mdmfMbCount\">180 of 345 rows"));
		WizardPageTest.check("master-live: one Stop", html.contains("id=\"mdmfMbStop\"") && html.contains("data-mdmf-job-id=\"91\""));
		WizardPageTest.check("no primary button while running", !html.contains("data-mdmf-action=\"MASTER_BUILD_RUN\""));
	}

	private static void masterBuildDoneShowsKpisGridAndOpenTableLink()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		// live bug fix (2026-09-25, task brief item 6): the done view now renders on MASTER_LOAD DONE, not on
		// the grid's own row count (WizardPageTest#masterDoneViewJudgedOnMasterLoadNotGridRowCount covers the
		// bug itself) - this fixture needs both PipelineStep rows for the done view to render at all.
		model.snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		model.snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MASTER_LOAD", "DONE", ""));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_TRANSFERS;
		final WizardSnapshot.RunGridInfo grid = new WizardSnapshot.RunGridInfo();
		grid.columnLabels.add(WizardMessages.get(Locale.ENGLISH, "wizard.step5.grid.tables"));
		grid.columnLabels.add(WizardMessages.get(Locale.ENGLISH, "wizard.step5.grid.copy"));
		grid.columnLabels.add(WizardMessages.get(Locale.ENGLISH, "wizard.step5.grid.matchingReady"));
		final WizardSnapshot.RunGridRow row = new WizardSnapshot.RunGridRow();
		row.label = "Supplier";
		row.sublabel = "2 fields";
		row.cells.add(new WizardSnapshot.RunGridCell("DONE", "Created", ""));
		final WizardSnapshot.RunGridCell copyCell = new WizardSnapshot.RunGridCell("DONE", "342 rows", "");
		copyCell.primaryValue = Long.valueOf(342L);
		row.cells.add(copyCell);
		row.cells.add(new WizardSnapshot.RunGridCell("DONE", "Ready", ""));
		grid.rows.add(row);
		model.masterBuildGrid = grid;
		model.masterKpiTiles.add(new String[] { "342", "Supplier" });
		model.masterHeldBackInStagingCount = 3;
		model.masterOpenTableUrls.put("Supplier", "/ebx-ui/ui/advanced/data/BCompanies_Master/Master/root/Supplier");
		final String html = WizardPage.render(model);
		WizardPageTest.check("KPI tile with the real count renders", html.contains(">342<"));
		WizardPageTest.check("held-back KPI tile renders", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.build.done.heldBack", Long.valueOf(3))));
		WizardPageTest.check("grid table renders", html.contains("mdmf-run-grid"));
		WizardPageTest.check("row label rendered", html.contains(">Supplier<"));
		WizardPageTest.check("open-table link rendered", html.contains(model.masterOpenTableUrls.get("Supplier")));
		WizardPageTest.check("master-live: no trace for a build recorded before the lot", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.live.lastBuild")));
		// master-live lot (2026-09-27): the last build's stages and journal stay after the end
		final RunProgress last = RunProgress.newRun(Arrays.asList("TABLES", "COPY", "MATCHING_READY"));
		last.start("TABLES", 0L);
		last.finish("TABLES", true, "ok", 112000L);
		last.start("COPY", 113000L);
		last.finish("COPY", true, "ok", 130000L);
		last.note("Build finished: 342 rows copied, 3 held back.");
		model.masterBuildRunProgress = last;
		final String traced = WizardPage.render(model);
		WizardPageTest.check("master-live: the last build's journal stays", traced.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.live.lastBuild"))
			&& traced.contains("Build finished: 342 rows copied, 3 held back."));
		WizardPageTest.check("master-live: the stages keep their durations", traced.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.live.stage.COPY") + " \u00B7 17 s"));
		WizardPageTest.check("continue-to-matching button exists", html.contains("data-mdmf-goto-step=\"6\""));
		WizardPageTest.check("done title rendered", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step5.build.done.title")));
	}

	/**
	 * fix/wizard-live-wave3 (2026-09-24, live bug item 7b, measured live on project Vague1): a
	 * {@code MATCHING_SUGGEST_LLM} {@code PipelineStep} track ("agent run 46" in the live case) must never be
	 * shown once the project has no matching plan at all any more (a project reset deletes every {@code
	 * MatchPlan} but leaves the pipeline history alone) - the track would otherwise keep summarising plans
	 * that no longer exist.
	 */
	private static void step6HidesStalePipelineTrackWhenNoMatchPlanExists()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		// no matchPlans added: exactly the post-reset state
		model.snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MATCHING_SUGGEST_LLM", "DONE", "2 matching plan(s) written, 0 refreshed, 0 unchanged (agent run 46, DONE)."));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_MATCHING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the stale agent-run summary is never shown once no plan exists", !html.contains("agent run 46"));
	}

	private static void step6OffersMatchingSuggestAndListsPlans()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.MatchPlanInfo plan = new WizardSnapshot.MatchPlanInfo();
		plan.id = "mp1";
		plan.entityName = "Supplier";
		plan.origin = "RULE";
		plan.status = "PROPOSED";
		plan.requirements = "Same supplierId is the same supplier.";
		model.snapshot.matchPlans.add(plan);
		// same MATCHING_SUGGEST_LLM track as the sibling test above, but this time a plan genuinely exists - the
		// track must still render (the fix must not hide it unconditionally).
		model.snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MATCHING_SUGGEST_LLM", "DONE", "1 matching plan(s) written, 0 refreshed, 0 unchanged (agent run 47, DONE)."));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_MATCHING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("propose matching button exists", html.contains("data-mdmf-action=\"MATCHING_SUGGEST\""));
		WizardPageTest.check("the track still renders when a plan exists", html.contains("agent run 47"));
		WizardPageTest.check("configure button exists", html.contains("data-mdmf-action=\"MATCHING_CONFIGURE\""));
		WizardPageTest.check("run incremental button exists", html.contains("data-mdmf-action=\"MATCHING_RUN\" data-mdmf-scope=\"incremental\""));
		WizardPageTest.check("run all button exists", html.contains("data-mdmf-action=\"MATCHING_RUN\" data-mdmf-scope=\"all\""));
		WizardPageTest.check("the plan's entity is shown", html.contains(">Supplier<"));
		WizardPageTest.check("the plan's requirements are shown", html.contains("Same supplierId is the same supplier."));
		WizardPageTest.check("Accept is offered on a PROPOSED plan", html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"mp1\""));
	}

	/**
	 * Wizard-match-tree lot (2026-09-23): with the plan's MATCH review line on the model (what the service
	 * builds from ebx.ProposalReviews), the card shows the decision tree itself - review.MatchPlanView's own
	 * markup - never MatchPlan.requirements as raw JSON (the product owner: "Il faut afficher l'arbre ici pour pouvoir
	 * prendre une decision"). A plan awaiting a decision keeps the tree always visible and folds evidence/
	 * simulation/survivorship; a decided plan folds everything, tree included; the plan the postback just acted
	 * on has its fold open.
	 */
	private static void matchPlanCardShowsTheDecisionTreeNotRawJson()
	{
		final String requirements = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]},\"clusteringFields\":[\"vatNo\"],"
			+ "\"mergePolicy\":{\"mode\":\"ONLY_DUPLICATES\",\"recordSelection\":\"mostCompleted\",\"defaultFieldMerge\":\"longest\"}}";
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.MatchPlanInfo proposed = new WizardSnapshot.MatchPlanInfo();
		proposed.id = "mp3";
		proposed.entityName = "Company";
		proposed.status = "PROPOSED";
		proposed.requirements = requirements;
		model.snapshot.matchPlans.add(proposed);
		final WizardSnapshot.MatchPlanInfo applied = new WizardSnapshot.MatchPlanInfo();
		applied.id = "mp4";
		applied.entityName = "Contact";
		applied.status = "APPLIED";
		applied.requirements = requirements;
		model.snapshot.matchPlans.add(applied);
		model.matchLines.put("mp3", new ReviewLine(ReviewLine.MATCH, "mp3", "Company", "1 tier - suspect only", "LLM", Integer.valueOf(85), "why",
			ReviewLine.PROPOSED, false, false, null, null, false, null, requirements, null, false, null, null));
		model.matchLines.put("mp4", new ReviewLine(ReviewLine.MATCH, "mp4", "Contact", "1 tier - suspect only", "LLM", Integer.valueOf(85), "why",
			ReviewLine.APPLIED, false, false, null, null, false, null, requirements, null, false, null, null));
		model.matchCanSimulate.put("mp3", Boolean.TRUE);
		model.matchCanSimulate.put("mp4", Boolean.TRUE);
		model.matchMaxSimulationPairs = 20;
		model.expandedMatchRowIds.add("mp4");
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_MATCHING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the raw requirements JSON is never printed", !html.contains("{&quot;decisionTree&quot;") && !html.contains("{\"decisionTree\""));
		WizardPageTest.check("the decision tree diagram is rendered (twice: one per plan)", WizardPageTest.count(html, "mdmf-tree-diagram") >= 2);
		WizardPageTest.check("the one-line summary replaces the JSON", WizardPageTest.count(html, "1 tier - suspect only") == 2);
		WizardPageTest.check("the PROPOSED plan's tree sits outside the fold, always visible", html.contains("<div class=\"mdmf-match-plan-tree\">"));
		WizardPageTest.check("the APPLIED plan folds its tree too (only one always-visible tree)", WizardPageTest.count(html, "mdmf-match-plan-tree\"") == 1);
		WizardPageTest.check("the PROPOSED plan's fold names evidence/simulation/survivorship only",
			html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step6.plan.details"))));
		WizardPageTest.check("the APPLIED plan's fold names the tree as well",
			html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step6.plan.detailsWithTree"))));
		WizardPageTest.check("Simulate is offered per plan with the plan's own row id",
			html.contains("data-mdmf-action=\"SIMULATE\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"mp3\"")
				&& html.contains("data-mdmf-action=\"SIMULATE\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"mp4\""));
		WizardPageTest.check("exactly the plan just acted on has its fold open", WizardPageTest.count(html, "<details open=\"open\"><summary>") == 1
			&& html.indexOf("<details open=\"open\"><summary>") > html.indexOf(">Contact<"));
		WizardPageTest.check("Accept is still offered on the PROPOSED plan", html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"mp3\""));
		// a plan whose line the service could not build keeps the pre-lot plain text, never a blank card
		final WizardPage.Model bare = WizardPageTest.model();
		bare.snapshot = WizardPageTest.demoSnapshot();
		bare.snapshot.matchPlans.add(proposed);
		bare.stepStates = WizardRules.stepStates(bare.snapshot);
		bare.currentStep = WizardRules.STEP_MATCHING;
		WizardPageTest.check("without a review line the requirements text is still shown", WizardPage.render(bare).contains("{&quot;decisionTree&quot;"));
	}

	/**
	 * Inline-reviews lot (2026-09-23): a MATCH line never submits "No matching for this entity" directly (it
	 * would bypass the mandatory confirmation) - it opens its own inline panel instead, with a required
	 * checkbox and a comment box, ported from {@code ReviewPage}; "Propose another" opens its own comment
	 * panel the same way. Neither used to be built in the wizard at all (a text pointer to Proposal review
	 * stood in for both); both are now decidable without leaving the wizard.
	 */
	private static void matchPlanNoMatchingAndReproposePanelsRenderInline()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.MatchPlanInfo plan = new WizardSnapshot.MatchPlanInfo();
		plan.id = "mp2";
		plan.entityName = "Account";
		plan.status = "PROPOSED";
		model.snapshot.matchPlans.add(plan);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_MATCHING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("no plain (always-visible) Reject button for a MATCH line",
			!html.contains("mdmf-btn-quiet\" data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"MATCH\" data-mdmf-id=\"mp2\">"));
		WizardPageTest.check("a toggle opens the No-matching panel", html.contains("data-mdmf-toggle=\"mdmf-match-noMatch-mp2\""));
		WizardPageTest.check("the No-matching panel carries its confirmation checkbox", html.contains("id=\"mdmf-match-noMatch-mp2-confirm\""));
		WizardPageTest.check("its Send button requires that checkbox", html.contains("data-mdmf-requires-checkbox=\"mdmf-match-noMatch-mp2-confirm\""));
		WizardPageTest.check("its Send button posts the comment textarea", html.contains("data-mdmf-comment-from=\"mdmf-match-noMatch-mp2-text\""));
		WizardPageTest.check("a toggle opens the Propose-another panel", html.contains("data-mdmf-toggle=\"mdmf-match-repropose-mp2\""));
		WizardPageTest.check("Propose another posts REPROPOSE with its own comment", html.contains("data-mdmf-action=\"REPROPOSE\"") && html.contains("data-mdmf-comment-from=\"mdmf-match-repropose-mp2-text\""));
	}

	private static void attributeMappingWarningIsShown()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.StagingEntityInfo entity = model.snapshot.stagingEntities.get(0);
		final WizardSnapshot.AttributeInfo attribute = entity.attributes.get(0);
		attribute.status = "ACCEPTED";
		attribute.mappingCount = 1;
		attribute.mappingFeedingCount = 0;
		attribute.mappingWarning = "Accepted, but no mapping feeds it.";
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the mapping-count hint is shown",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.attribute.mappings.one", Integer.valueOf(0), Integer.valueOf(1))));
		WizardPageTest.check("the warning chip is shown", html.contains("Accepted, but no mapping feeds it."));
	}

	/**
	 * UX lot U3: step 4's counts are computed from the rows themselves ({@link Step4Counts}), so a test states its situation by adding rows: {@code pCount}
	 * source links (mappings of the first attribute) or proposal lines of {@code pNature}, all in {@code pStatus}, under the first business object.
	 */
	private static void addRows(final WizardSnapshot pSnapshot, final String pNature, final String pStatus, final int pCount)
	{
		final WizardSnapshot.StagingEntityInfo entity = pSnapshot.stagingEntities.get(0);
		for (int i = 0; i < pCount; i++)
		{
			if (com.on.ebx.factory.review.ReviewLine.MAPPING.equals(pNature))
			{
				final WizardSnapshot.MappingInfo mapping = new WizardSnapshot.MappingInfo();
				mapping.id = "t" + entity.attributes.get(0).mappings.size();
				mapping.status = pStatus;
				entity.attributes.get(0).mappings.add(mapping);
			}
			else
			{
				entity.proposalLines.add(new WizardSnapshot.ProposalLineInfo(new com.on.ebx.factory.review.ReviewLine(pNature, "t" + entity.proposalLines.size(), "Supplier.x",
					"proposition", "RULE", Integer.valueOf(80), "", pStatus, false, false)));
			}
		}
	}

	private static void waitingForReviewLineShowsCounts()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		WizardPageTest.addRows(model.snapshot, com.on.ebx.factory.review.ReviewLine.MAPPING, "PROPOSED", 3);
		WizardPageTest.addRows(model.snapshot, com.on.ebx.factory.review.ReviewLine.QUALITY, "PROPOSED", 2);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		final int waiting = Step4Counts.of(model.snapshot).waiting();
		WizardPageTest.check("waiting title shows the total of every nature still waiting (the one Step4Counts number)",
			waiting >= 5 && html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.waiting.title.other", Integer.valueOf(waiting))));
		WizardPageTest.check("mapping count line is shown", html.contains(WizardMessages.countWords(Locale.ENGLISH, 3, "wizard.unit.sourceLink")));
		WizardPageTest.check("quality count line is shown", html.contains(WizardMessages.countWords(Locale.ENGLISH, 2, "wizard.unit.qualityRule")));
		WizardPageTest.check("a zero-count nature is not shown", !html.contains(WizardMessages.countWords(Locale.ENGLISH, 0, "wizard.unit.check")));
	}

	/**
	 * Live bug fix (2026-09-25, PersVol2 volume test): the waiting-for-review header names cleaning steps but
	 * used to offer no way to accept them from there - see {@code WizardPage#renderWaitingForReviewHeader}'s
	 * own Javadoc and {@code ProjectWizardUserService}'s {@code STAGING_BULK_ACCEPT_STEPS} action.
	 */
	private static void waitingForReviewLineOffersAcceptCleaningStepsButton()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		WizardPageTest.addRows(model.snapshot, com.on.ebx.factory.review.ReviewLine.MAPPING, "PROPOSED", 3);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String htmlWithoutSteps = WizardPage.render(model);
		WizardPageTest.check("no accept-cleaning-steps button when nothing is waiting on that nature",
			!htmlWithoutSteps.contains("data-mdmf-action=\"STAGING_BULK_ACCEPT_STEPS\""));

		WizardPageTest.addRows(model.snapshot, com.on.ebx.factory.review.ReviewLine.MAPPING_STEP, "PROPOSED", 7);
		final String htmlWithSteps = WizardPage.render(model);
		WizardPageTest.check("accept-cleaning-steps button shown once cleaning steps are waiting",
			htmlWithSteps.contains("data-mdmf-action=\"STAGING_BULK_ACCEPT_STEPS\""));
	}

	/**
	 * Live bug fix (2026-09-25, PersVol2 volume test): "Build and load" succeeded, every mapping/quality rule
	 * was accepted (none left PROPOSED), yet the "Proposal ready for review" summary kept reading
	 * {@link WizardSnapshot#proposedCounts} (PROPOSED-only) and showed "0 field links, 0 quality rules, 0
	 * checks" - see {@code WizardPage#renderProposalDone}'s own Javadoc. It must read {@link
	 * WizardSnapshot#decidedCounts} instead (every row not REJECTED), so an already-accepted-and-loaded
	 * project still shows the real totals.
	 */
	private static void proposalDoneSummaryUsesDecidedCountsNotProposedCounts()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.proposalRunExists = true;
		// everything already decided - the PersVol2 shape (39 mappings and 7 quality rules ACCEPTED, none waiting): the block still reads the real totals
		// (UX lot U3: from the rows themselves, the same Step4Counts as the waiting line and the "Your decisions" card)
		WizardPageTest.addRows(model.snapshot, com.on.ebx.factory.review.ReviewLine.MAPPING, "ACCEPTED", 39);
		WizardPageTest.addRows(model.snapshot, com.on.ebx.factory.review.ReviewLine.QUALITY, "ACCEPTED", 7);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("source-link count is the 39 accepted ones", html.contains(WizardMessages.countWords(Locale.ENGLISH, 39, "wizard.unit.sourceLink")));
		WizardPageTest.check("quality-rule count is the 7 accepted ones", html.contains(WizardMessages.countWords(Locale.ENGLISH, 7, "wizard.unit.qualityRule")));
		WizardPageTest.check("never renders a misleading all-zero figure for a nature that has none",
			!html.contains(WizardMessages.countWords(Locale.ENGLISH, 0, "wizard.unit.sourceLink")) && !html.contains(WizardMessages.countWords(Locale.ENGLISH, 0, "wizard.unit.check")));
	}

	/**
	 * Live bug fix (2026-09-25, PersVol2 volume test): steps 4 and 6 used to say the fixed "Only metadata
	 * leaves this instance ... never row values" sentence whatever the project's own {@code
	 * ebx.LlmDataPolicy} level - stale since the staging LLM pass started sending value shapes (2026-09-24).
	 * The disclosure text must follow {@link WizardSnapshot.ProjectInfo#llmDataPolicyLevel}.
	 */
	private static void llmDisclosureTextFollowsTheProjectsDataPolicyLevel()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;

		model.snapshot.project.llmDataPolicyLevel = "NONE";
		final String htmlNone = WizardPage.render(model);
		WizardPageTest.check("NONE: says never row values, not even shapes",
			htmlNone.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.llm.disclosure.NONE")));
		WizardPageTest.check("NONE: never claims shapes leave the instance",
			!htmlNone.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.llm.disclosure.SHAPES_ONLY")));

		model.snapshot.project.llmDataPolicyLevel = "SHAPES_ONLY";
		final String htmlShapes = WizardPage.render(model);
		WizardPageTest.check("SHAPES_ONLY: says value shapes leave the instance",
			htmlShapes.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.llm.disclosure.SHAPES_ONLY")));

		model.snapshot.project.llmDataPolicyLevel = "SAMPLE_VALUES";
		final String htmlSamples = WizardPage.render(model);
		WizardPageTest.check("SAMPLE_VALUES: says real values may leave the instance",
			htmlSamples.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.llm.disclosure.SAMPLE_VALUES")));
	}

	private static void reopenMappingsButtonOnlyWhenSomethingToReopen()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String htmlBefore = WizardPage.render(model);
		WizardPageTest.check("no reopen button when nothing is orphaned", !htmlBefore.contains("data-mdmf-action=\"STAGING_REOPEN_MAPPINGS\""));

		final WizardSnapshot.AttributeInfo attribute = model.snapshot.stagingEntities.get(0).attributes.get(0);
		attribute.status = "ACCEPTED";
		attribute.mappingWarning = "Accepted, but no mapping feeds it.";
		final String htmlAfter = WizardPage.render(model);
		WizardPageTest.check("reopen button shown once an attribute is orphaned", htmlAfter.contains("data-mdmf-action=\"STAGING_REOPEN_MAPPINGS\""));
	}

	/**
	 * Wizard-summary lot (2026-09-24): {@link WizardPage} renders step 7 purely from {@link
	 * WizardPage.Model#summary}/{@code summaryInput} (built server-side by {@code
	 * ProjectWizardUserService.buildSummary}, exercised by {@code WizardSummaryDataTest} instead) - with no
	 * summary set (a fixture that never computed one, or a project with no data yet) the panel renders the
	 * empty-state notice, never a stale table. This test builds a small {@link WizardSummaryData.Input} by
	 * hand, the same way the service would, and checks the rendered HTML reflects it.
	 */
	private static void step7ReflectsMasterAndMatchingReadiness()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_SUMMARY;
		final String htmlEmpty = WizardPage.render(model);
		WizardPageTest.check("no summary yet - empty state shown", htmlEmpty.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7.empty")));

		final WizardSummaryData.Input in = new WizardSummaryData.Input();
		in.projectLabel = "Demo";
		final WizardSummaryData.SourceInput source = new WizardSummaryData.SourceInput();
		source.name = "ERP";
		source.receivedRows = 100;
		in.sources.add(source);
		in.landingTableCount = 1;
		in.landingLoaded = true;
		final WizardSummaryData.ZoneEntityInput stagingEntity = new WizardSummaryData.ZoneEntityInput();
		stagingEntity.name = "Supplier";
		stagingEntity.rows = Long.valueOf(100);
		in.stagingEntities.add(stagingEntity);
		final WizardSummaryData.ZoneEntityInput masterEntity = new WizardSummaryData.ZoneEntityInput();
		masterEntity.name = "Supplier";
		masterEntity.rows = Long.valueOf(100);
		in.masterEntities.add(masterEntity);
		in.matchPlansApplied = 1;
		in.matchPlansTotal = 1;
		// coordinator review (2026-09-24, board SummaryDistribution.dc.html): the "Distribute" card and its
		// "<target> not armed" open point.
		final WizardSummaryData.DistributionSummaryTarget realtimeTarget = new WizardSummaryData.DistributionSummaryTarget();
		realtimeTarget.label = "Salesforce CRM";
		realtimeTarget.mode = "REALTIME";
		realtimeTarget.state = "WAITING";
		in.distributionTargets.add(realtimeTarget);
		final WizardSummaryData.DistributionSummaryTarget scheduledTarget = new WizardSummaryData.DistributionSummaryTarget();
		scheduledTarget.label = "Kafka";
		scheduledTarget.mode = "SCHEDULED";
		scheduledTarget.state = "ARMED";
		in.distributionTargets.add(scheduledTarget);
		in.distributionProductExists = true;
		in.distributionProductLabel = "Person 360";
		in.distributionProductRefreshMode = "DAILY";
		in.openPoints.add(new WizardSummaryData.OpenPointInput("WARN", "Salesforce CRM not armed", "Real time starts after the first full send.", "Run it",
			WizardRules.STEP_DISTRIBUTION, "", "42"));
		model.summaryInput = in;
		model.summary = WizardSummaryData.compute(in);
		final String htmlAfter = WizardPage.render(model);
		WizardPageTest.check("matching zone reports one applied plan", htmlAfter.contains("1 matching plan applied, out of 1"));
		WizardPageTest.check("master zone shows the loaded row count", htmlAfter.contains("100 records"));
		WizardPageTest.check("the Distribute card lists the REALTIME target with its mode/state",
			htmlAfter.contains("Salesforce CRM") && htmlAfter.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.mode.REALTIME")));
		WizardPageTest.check("the Distribute card lists the scheduled target too", htmlAfter.contains("Kafka"));
		WizardPageTest.check("the Distribute card names the existing data product and its refresh",
			htmlAfter.contains("Person 360") && htmlAfter.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.product.refresh.DAILY")));
		WizardPageTest.check("the Distribute card has a Change link to step 7",
			htmlAfter.contains("data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"7\">" + WizardMessages.get(Locale.ENGLISH, "wizard.step7.prod.distribute.change")));
		WizardPageTest.check("the not-armed open point's button both opens the target AND switches to step 7 - one combined button, not GOTO_STEP alone",
			htmlAfter.contains("data-mdmf-action=\"DISTRIBUTION_TARGET_OPEN\" data-mdmf-id=\"42\" data-mdmf-goto-step=\"7\">Run it</button>"));
		WizardPageTest.check("Deliver mentions the distribution", htmlAfter.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7.prod.deliver.text"))
			&& WizardMessages.get(Locale.ENGLISH, "wizard.step7.prod.deliver.text").toLowerCase(Locale.ENGLISH).contains("distribution"));
		WizardPageTest.check("Follow mentions every send", WizardMessages.get(Locale.ENGLISH, "wizard.step7.prod.follow.text").toLowerCase(Locale.ENGLISH).contains("every send"));
		// step 6/7 visual round (2026-09-24): every zone fact names its nouns in singular/plural, never "(s)".
		// distribution-wizard lot (2026-09-24): Summary is now step 8 (renderStep8) - the substring below still
		// targets its section (its own message keys stayed under the "wizard.step7.*" prefix - see renderStep8's
		// own Javadoc for why).
		final String step7Html = htmlAfter.substring(htmlAfter.indexOf("<section class=\"mdmf-wiz-panel\" data-step=\"8\""));
		WizardPageTest.check("no (s) plural in step 8 (summary): " + step7Html, !step7Html.contains("(s)"));
		WizardPageTest.check("operational verdict shown", htmlAfter.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7.verdict.OPERATIONAL", "Companies 360")));
		WizardPageTest.check("no inline <script> tag - JS only ever goes through addJS_cr", !htmlAfter.toLowerCase(Locale.ENGLISH).contains("<script"));
	}

	/**
	 * UX lot U3 (2026-10-13), project UxCrmSf: the Summary said "Landing 19 tables" (4 kept), "Master 30 records" (194 rows), "2 matching plans applied,
	 * out of 2" (a failed run, a third object without plan) and "Organisation name 0 %" (62 named). Each card now says what it measured.
	 */
	private static void summaryScopesItsCardsLikeTheMeasuredProject()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_SUMMARY;
		final WizardSummaryData.Input in = new WizardSummaryData.Input();
		in.projectLabel = "UxCrmSf";
		in.landingLoaded = true;
		in.landingTableCount = 4;
		in.landingOtherTableCount = 15;
		for (final String name : new String[] { "Person", "Address", "Organisation" })
		{
			final WizardSummaryData.ZoneEntityInput master = new WizardSummaryData.ZoneEntityInput();
			master.name = name;
			master.rows = Long.valueOf("Person".equals(name) ? 120 : "Address".equals(name) ? 12 : 62);
			if ("Person".equals(name))
			{
				master.golden = Long.valueOf(30);
				master.merged = Long.valueOf(90);
			}
			in.masterEntities.add(master);
		}
		in.masterMainEntity = "Person";
		in.matchingAvailable = true;
		in.goldenCount = 30;
		in.matchObjects.add(new WizardSummaryData.MatchObjectInput("Person", "APPLIED", "DONE"));
		in.matchObjects.add(new WizardSummaryData.MatchObjectInput("Address", "APPLIED", "FAILED"));
		in.matchObjects.add(new WizardSummaryData.MatchObjectInput("Organisation", "", ""));
		for (final String[] fill : new String[][] { { "Person", "name", "30" }, { "Organisation", "name", "0" } })
		{
			final WizardSummaryData.CompletenessInput row = new WizardSummaryData.CompletenessInput();
			row.entityName = fill[0];
			row.attribute = fill[1];
			row.attributeLabel = fill[0] + " - " + fill[1];
			row.filled = Integer.parseInt(fill[2]);
			row.total = Integer.parseInt(fill[2]);
			in.completeness.add(row);
		}
		model.summaryInput = in;
		model.summary = WizardSummaryData.compute(in);
		final String html = WizardPage.render(model);
		WizardPageTest.check("landing: the 4 kept tables, the 15 others as a note", html.contains("4 tables, image of the sources") && html.contains("Not kept: 15 tables"));
		// step 7 screen counts (2026-10-18): one line per table - the rows of the tables are never added into one figure, the golden records belong to the main object
		WizardPageTest.check("master: one line per table, the golden records on the main object only",
			html.contains("Person: 120 records, of which 30 golden records (90 merged)") && html.contains("Address: 12 records<") && html.contains("Organisation: 62 records<")
				&& !html.contains("194 records"));
		WizardPageTest.check("matching: 2 plans of 3 objects", html.contains("2 matching plans applied, out of 3 business objects"));
		WizardPageTest.check("matching: a line per object with its plan and last run",
			html.contains("Person: plan applied, last run done") && html.contains("Address: plan applied, last run failed") && html.contains("Organisation: no plan, not run yet")
				&& html.contains("mdmf-zone-detail-bad"));
		WizardPageTest.check("matching: the card says the run failed", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7.zoneStatus.failed")));
		WizardPageTest.check("completeness: Person measured on its 30 golden records, with its bar",
			html.contains("<b>Person</b> - measured on the 30 golden records")
				&& html.contains("<span>name</span><span class=\"mdmf-completeness-bar\"><i style=\"width:100%\"></i></span><b>100 %</b>"));
		WizardPageTest.check("completeness: Organisation not matched yet, no bar (never 'name 0 %')",
			html.contains("<b>Organisation</b> - not matched yet") && !html.contains("<b>0 %</b>"));
	}

	/** UX lot U3: the proposal's six-pass list is the one wizard.js repaints from the jobs poll - the script must know the ids and attributes the page writes. */
	private static void jobsPollTheProposalPassesInPlace()
	{
		final String js;
		try
		{
			js = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"))
				.resolve("../webapp/www/common/jscripts/factory/wizard.js")), java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (final java.io.IOException ex)
		{
			throw new AssertionError("wizard.js unreadable: " + ex);
		}
		WizardPageTest.check("wizard.js has the proposal live block", js.contains("var proposalLive = (function () {") && js.contains("byId(\"mdmfProposalLive\")"));
		WizardPageTest.check("it is fed by the jobs poll with the server's clock", js.contains("proposalLive.update(job, data.serverTime)") && js.contains("kind === \"STAGING_PROPOSAL\""));
		for (final String attribute : new String[] { "data-mdmf-pl-", "mdmfProposalNow", "mdmfProposalCount", "mdmf-proposal-stage-time", "mdmf-proposal-stage-summary", "mdmf-proposal-bar-fill" })
		{
			WizardPageTest.check("script and page agree on " + attribute, js.contains(attribute));
		}
		WizardPageTest.check("a client-side step change removes the notice of an earlier action", js.contains("byId(\"mdmfNotice\")") && js.contains("removeChild(oldNotice)"));
	}

	/** Audit F-34: a product hidden by the person's catalog right is never reported as "no data product yet" on the Summary's Distribute card. */
	private static void summaryDistributeCardSaysTheCatalogHidesTheProduct()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_SUMMARY;
		final WizardSummaryData.Input in = new WizardSummaryData.Input();
		in.projectLabel = "Demo";
		in.distributionProductRestricted = true;
		model.summaryInput = in;
		model.summary = WizardSummaryData.compute(in);
		final String html = WizardPage.render(model);
		WizardPageTest.check("restricted: not 'no data product yet'", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7.prod.distribute.none")));
		WizardPageTest.check("restricted: says no target, and why the product may not show",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7.prod.distribute.noTarget")) && html.contains("data-mdmf-catalog-restricted=\"true\""));
		in.distributionProductRestricted = false;
		WizardPageTest.check("not restricted: the usual 'none yet'", WizardPage.render(model).contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7.prod.distribute.none")));
	}

	/**
	 * Distribution-wizard lot (2026-09-24): step 7 ("Distribution", boards {@code Main.dc.html}/
	 * {@code Modes.dc.html}/{@code DataProduct.dc.html}) - same discipline as {@code step7ReflectsMasterAndMatchingReadiness}
	 * above: a hand-built {@code WizardPage.DistributionModel} fixture (never a live EBX read, this class is
	 * unit-tested without one), checking the empty state, the target list, a target's setup panel (7b, with the
	 * arming checklist) and the data product panel (7c) all render without a live server.
	 */
	private static void step7DistributionEmptyStateThenTargetsAndProduct()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_DISTRIBUTION;
		final String htmlEmpty = WizardPage.render(model);
		WizardPageTest.check("no distribution data yet - empty state shown", htmlEmpty.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.empty")));
		WizardPageTest.check("not deferred - no deferred marker", !htmlEmpty.contains("data-mdmf-deferred-step"));

		// perf/wizard-distribution-lazy (2026-09-27): step 7 left unbuilt on purpose - the placeholder carries the
		// marker wizard.js loads on, and a STEP_VIEW fallback button, never the "no data yet" notice
		model.distributionDeferred = true;
		final String htmlDeferred = WizardPage.render(model);
		WizardPageTest.check("deferred step 7 renders the marker wizard.js loads on", htmlDeferred.contains("data-mdmf-deferred-step=\"7\""));
		WizardPageTest.check("deferred step 7 offers a STEP_VIEW fallback button",
			htmlDeferred.contains(">" + WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.deferred.load")) + "</button>")
				&& htmlDeferred.contains("data-mdmf-action=\"STEP_VIEW\">"));
		WizardPageTest.check("deferred step 7 is not the empty state", !htmlDeferred.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.empty")));
		model.distributionDeferred = false;

		final WizardPage.DistributionModel d = new WizardPage.DistributionModel();
		final WizardPage.DistributionTargetRow armed = new WizardPage.DistributionTargetRow();
		armed.id = "1";
		armed.connector = "Salesforce";
		armed.connectorLabel = "Salesforce CRM";
		armed.operation = "Contact.upsert";
		armed.mode = "REALTIME";
		armed.state = "ARMED";
		d.targets.add(armed);
		final WizardPage.DistributionConnectionRow connection = new WizardPage.DistributionConnectionRow();
		connection.connector = "Salesforce";
		connection.label = "Salesforce CRM";
		connection.hasWriteOperation = true;
		d.connections.add(connection);
		d.product.catalogInstalled = true;
		d.product.snowflakePresent = true;
		d.product.snowflakeTested = true;
		d.product.snowflakeReachable = true;
		d.connectionsRead = true;
		model.distribution = d;
		final String htmlList = WizardPage.render(model);
		WizardPageTest.check("the armed target's connector label is shown", htmlList.contains("Salesforce CRM"));
		WizardPageTest.check("the armed target's chip reads Armed", htmlList.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.state.ARMED")));
		WizardPageTest.check("the skip-to-summary link always renders (distribution never blocks the summary)",
			htmlList.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.skip")));
		WizardPageTest.check("the data product's reachable-Snowflake note is shown before the panel is opened",
			htmlList.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.product.snowflakeReachable")));

		// audit F-2-101: untested = said so, with the explicit test button; empty cache = clear empty state, not a spinner
		d.product.snowflakeTested = false;
		final String htmlUntested = WizardPage.render(model);
		WizardPageTest.check("an untested warehouse is said untested, with its test button", htmlUntested.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.product.snowflakeUntested"))
			&& htmlUntested.contains("DISTRIBUTION_WAREHOUSE_TEST") && !htmlUntested.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.product.snowflakeReachable")));
		d.connectionsRead = false;
		final String htmlNotRead = WizardPage.render(model);
		WizardPageTest.check("a cache never filled says the connections are not read, with Refresh", htmlNotRead.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.connections.notRead"))
			&& htmlNotRead.contains("DISTRIBUTION_CONNECTIONS_REFRESH"));
		d.connectionsReading = true;
		WizardPageTest.check("a read in progress says so", WizardPage.render(model).contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.connections.reading")));
		d.connectionsReading = false;
		d.product.snowflakeTested = true;
		d.connectionsRead = true;

		// 7b: opening a target's setup panel shows the arming checklist instead of the list
		final WizardPage.DistributionModel setup = new WizardPage.DistributionModel();
		setup.targets.add(armed);
		setup.connections.add(connection);
		setup.openTargetId = "1";
		setup.openTarget = armed;
		setup.readiness.landingLoadedFromEverySource = true;
		setup.readiness.masterBuilt = true;
		setup.readiness.firstMatchingRunFinished = false;
		model.distribution = setup;
		final String htmlSetup = WizardPage.render(model);
		WizardPageTest.check("the setup panel shows the target's own title", htmlSetup.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.setup.title", "Salesforce CRM")));
		WizardPageTest.check("the arming checklist names the master step", htmlSetup.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.arming.master")));
		WizardPageTest.check("the first-full-send button is disabled: matching has not finished",
			htmlSetup.contains("data-mdmf-action=\"DISTRIBUTION_TARGET_FIRST_FULL_SEND\" data-mdmf-id=\"1\" disabled=\"disabled\">"));
		WizardPageTest.check("the target list is not shown while its setup panel is open", !htmlSetup.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.targets.add")));

		// 7c: an ON_DEMAND target's setup panel offers Send now (changed/all), never the arming checklist
		final WizardPage.DistributionTargetRow onDemand = new WizardPage.DistributionTargetRow();
		onDemand.id = "2";
		onDemand.connector = "Kafka";
		onDemand.connectorLabel = "Kafka";
		onDemand.mode = "ON_DEMAND";
		onDemand.state = "WAITING";
		final WizardPage.DistributionModel setupOnDemand = new WizardPage.DistributionModel();
		setupOnDemand.openTargetId = "2";
		setupOnDemand.openTarget = onDemand;
		model.distribution = setupOnDemand;
		final String htmlOnDemand = WizardPage.render(model);
		WizardPageTest.check("an ON_DEMAND target offers Send now", htmlOnDemand.contains("DISTRIBUTION_TARGET_SEND_NOW"));
		WizardPageTest.check("an ON_DEMAND target does not show the real-time arming checklist",
			!htmlOnDemand.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.arming.firstFullSend")));

		WizardPageTest.check("no inline <script> tag on step 7 either", !htmlList.toLowerCase(Locale.ENGLISH).contains("<script"));
	}

	/**
	 * Round 5 (2026-09-26, task brief item 1, DistribTest live incident): a target armed through the admin
	 * override ({@code armedViaOverride=true}, {@code firstFullSendRunId} blank) never ran a first full send -
	 * the 7b checklist's "First full send done" line must not tick for it, and must show a distinct
	 * "Armed without a first full send" state instead of the plain grey "waiting" one. A target that DID run a
	 * real first full send ({@code firstFullSendRunId} set) still ticks, override flag or not.
	 */
	private static void step7DistributionArmingChecklistTicksOnlyOnRealFirstFullSend()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_DISTRIBUTION;

		// Armed via override: no real first full send ever ran.
		final WizardPage.DistributionTargetRow overridden = new WizardPage.DistributionTargetRow();
		overridden.id = "1";
		overridden.connector = "Kafka";
		overridden.connectorLabel = "KafkaMdmDistribTest";
		overridden.mode = "REALTIME";
		overridden.state = "ARMED";
		overridden.armedViaOverride = true;
		final WizardPage.DistributionModel setupOverridden = new WizardPage.DistributionModel();
		setupOverridden.openTargetId = "1";
		setupOverridden.openTarget = overridden;
		setupOverridden.readiness.landingLoadedFromEverySource = true;
		setupOverridden.readiness.masterBuilt = true;
		setupOverridden.readiness.firstMatchingRunFinished = true;
		model.distribution = setupOverridden;
		final String htmlOverridden = WizardPage.render(model);
		WizardPageTest.check("an override-armed target shows the distinct override checklist line",
			htmlOverridden.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.arming.firstFullSend.override")));
		WizardPageTest.check("an override-armed target does not show the plain first-full-send-done line",
			!htmlOverridden.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.arming.firstFullSend")));
		WizardPageTest.check("the override checklist line uses the amber needsPerson style",
			htmlOverridden.contains("mdmf-sc-needsPerson"));

		// Armed via a real first full send: the plain line ticks, even though armedViaOverride happens to be unset.
		final WizardPage.DistributionTargetRow realSend = new WizardPage.DistributionTargetRow();
		realSend.id = "2";
		realSend.connector = "Kafka";
		realSend.connectorLabel = "KafkaMdmDistribTest";
		realSend.mode = "REALTIME";
		realSend.state = "ARMED";
		realSend.firstFullSendRunId = "R20260926-133426-0004";
		final WizardPage.DistributionModel setupRealSend = new WizardPage.DistributionModel();
		setupRealSend.openTargetId = "2";
		setupRealSend.openTarget = realSend;
		setupRealSend.readiness.landingLoadedFromEverySource = true;
		setupRealSend.readiness.masterBuilt = true;
		setupRealSend.readiness.firstMatchingRunFinished = true;
		model.distribution = setupRealSend;
		final String htmlRealSend = WizardPage.render(model);
		WizardPageTest.check("a target armed by a real first full send shows the plain done line",
			htmlRealSend.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.arming.firstFullSend")));
		WizardPageTest.check("a target armed by a real first full send does not show the override line",
			!htmlRealSend.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.arming.firstFullSend.override")));
		WizardPageTest.check("a target armed by a real first full send ticks with the green done style",
			htmlRealSend.contains("mdmf-sc-done"));

		// WAITING, no override: plain grey waiting line, neither done nor the override state.
		final WizardPage.DistributionTargetRow waiting = new WizardPage.DistributionTargetRow();
		waiting.id = "3";
		waiting.connector = "Kafka";
		waiting.connectorLabel = "KafkaMdmDistribTest";
		waiting.mode = "REALTIME";
		waiting.state = "WAITING";
		final WizardPage.DistributionModel setupWaiting = new WizardPage.DistributionModel();
		setupWaiting.openTargetId = "3";
		setupWaiting.openTarget = waiting;
		model.distribution = setupWaiting;
		final String htmlWaiting = WizardPage.render(model);
		WizardPageTest.check("a never-armed target shows the plain waiting line, not the override one",
			htmlWaiting.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.arming.firstFullSend"))
				&& !htmlWaiting.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.arming.firstFullSend.override")));
	}

	/**
	 * Live bug fix (2026-09-25, DistribTest, task brief items 2/3/4): 7a puts write-capable connections first
	 * (an existing target's own connection first among those), folds every connection without a write
	 * operation under one closed {@code <details>} disclosure instead of a long alphabetical list; 7b's mode
	 * picker renders as option cards (title + one-line explanation), and a Kafka-style connector (its own
	 * {@code topic}/{@code key.field} properties, never chosen through this mapping) shows a read-only "Sends
	 * to" line plus a warning when {@code key.field} does not name a real master field.
	 */
	private static void step7DistributionSortsGroupsConnectionsAndWarnsOnKafkaKeyField()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_DISTRIBUTION;

		final WizardPage.DistributionModel d = new WizardPage.DistributionModel();
		// alphabetically first, but no write operation - must NOT lead the list
		final WizardPage.DistributionConnectionRow aReadOnly = new WizardPage.DistributionConnectionRow();
		aReadOnly.connector = "AReadOnly";
		aReadOnly.label = "A Read-Only System";
		aReadOnly.hasWriteOperation = false;
		d.connections.add(aReadOnly);
		// alphabetically last, write-capable, no target yet
		final WizardPage.DistributionConnectionRow zNoTarget = new WizardPage.DistributionConnectionRow();
		zNoTarget.connector = "ZNoTarget";
		zNoTarget.label = "Z Write Capable";
		zNoTarget.hasWriteOperation = true;
		d.connections.add(zNoTarget);
		// alphabetically middle, write-capable, WITH an existing target - must lead the list despite its position
		final WizardPage.DistributionConnectionRow kafka = new WizardPage.DistributionConnectionRow();
		kafka.connector = "Kafka";
		kafka.label = "Kafka";
		kafka.hasWriteOperation = true;
		kafka.writeOperations.add("Person.publish");
		kafka.topic = "ebx.companies";
		kafka.keyField = "lei";
		d.connections.add(kafka);
		final WizardPage.DistributionTargetRow kafkaTarget = new WizardPage.DistributionTargetRow();
		kafkaTarget.id = "9";
		kafkaTarget.connector = "Kafka";
		kafkaTarget.connectorLabel = "Kafka";
		kafkaTarget.operation = "Person.publish";
		kafkaTarget.mode = "REALTIME";
		kafkaTarget.state = "ARMED";
		d.targets.add(kafkaTarget);
		model.distribution = d;
		final String htmlList = WizardPage.render(model);
		final int kafkaIdx = htmlList.indexOf("Kafka");
		final int zIdx = htmlList.indexOf("Z Write Capable");
		// the plain class name "<details class=\"mdmf-disclosure\">" also appears earlier on the page, in a
		// different (hidden) step's own disclosure panel - every step renders in the same document - so the
		// no-write group is instead located by its own unique summary text.
		final int detailsIdx = htmlList.indexOf(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.connections.noWriteGroup.one", "1"));
		WizardPageTest.check("connection with an existing target (Kafka) leads the list", kafkaIdx >= 0 && kafkaIdx < zIdx);
		WizardPageTest.check("write-capable-without-target (Z) comes before the no-write disclosure", zIdx >= 0 && zIdx < detailsIdx);
		WizardPageTest.check("the no-write connection is folded under a <details> disclosure, not in the plain list",
			detailsIdx >= 0 && detailsIdx < htmlList.indexOf("A Read-Only System"));
		WizardPageTest.check("the disclosure names the count",
			htmlList.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.connections.noWriteGroup.one", "1")));
		final int detailsTagIdx = htmlList.lastIndexOf("<details", detailsIdx);
		WizardPageTest.check("the disclosure has no 'open' attribute - closed by default",
			detailsTagIdx >= 0 && !htmlList.substring(detailsTagIdx, detailsIdx).contains("open"));

		// 7b: the mode picker is option cards (title + explanation), not a bare radio+label
		final WizardPage.DistributionModel setup = new WizardPage.DistributionModel();
		setup.connections.add(kafka);
		setup.openTargetId = "9";
		setup.openTarget = kafkaTarget;
		model.distribution = setup;
		final String htmlSetup = WizardPage.render(model);
		WizardPageTest.check("mode picker uses the option-card pattern", htmlSetup.contains("mdmf-choice-card mdmf-mode-card selected"));
		WizardPageTest.check("REALTIME's one-line explanation is shown",
			htmlSetup.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.mode.REALTIME.explain")));
		WizardPageTest.check("each checkbox sits in its own block (one per line), not run together",
			WizardPageTest.count(htmlSetup, "<div class=\"mdmf-field\"><label class=\"mdmf-hint\"><input type=\"checkbox\"") >= 2);

		// Kafka's own topic/key.field: read-only "Sends to" line, and a warning since 'lei' is not among the
		// demo fixture's master fields (masterFieldNames is only ever populated by a live buildDistribution read,
		// left empty here on purpose to prove the warning is skipped rather than false-positiving on an empty
		// set). WizardPage.esc(...) (package-private, same package as this test) matches the HTML-escaping the
		// production render applies to the message's own apostrophes (rendered as "&#39;", not a raw "'").
		final String keyFieldWarningEscaped = WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.setup.keyFieldWarning", "lei"));
		WizardPageTest.check("no key-field warning while masterFieldNames is unknown (empty)", !htmlSetup.contains(keyFieldWarningEscaped));
		WizardPageTest.check("the 'Sends to' hint is shown for a Kafka-style connector",
			htmlSetup.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.setup.sendsTo", "ebx.companies", "lei")));
		setup.masterFieldNames.add("id");
		setup.masterFieldNames.add("name");
		final String htmlSetupKnownFields = WizardPage.render(model);
		WizardPageTest.check("the warning shows once the master's real fields are known and 'lei' is not one of them",
			htmlSetupKnownFields.contains(keyFieldWarningEscaped));
		setup.masterFieldNames.add("lei");
		final String htmlSetupHasField = WizardPage.render(model);
		WizardPageTest.check("no warning once 'lei' is a real master field", !htmlSetupHasField.contains(keyFieldWarningEscaped));

		// an ordinary (non-Kafka) connector shows no "Sends to" line at all
		final WizardPage.DistributionTargetRow ordinary = new WizardPage.DistributionTargetRow();
		ordinary.id = "10";
		ordinary.connector = "ZNoTarget";
		ordinary.connectorLabel = "Z Write Capable";
		ordinary.mode = "ON_DEMAND";
		final WizardPage.DistributionModel setupOrdinary = new WizardPage.DistributionModel();
		setupOrdinary.connections.add(zNoTarget);
		setupOrdinary.openTargetId = "10";
		setupOrdinary.openTarget = ordinary;
		model.distribution = setupOrdinary;
		final String htmlOrdinary = WizardPage.render(model);
		WizardPageTest.check("no 'Sends to' line for an ordinary connector",
			!htmlOrdinary.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.setup.sendsTo.noKey", "")));
	}

	/**
	 * Round 6 (2026-09-26, task brief item C, Personnes live incident: {@code /root/Personne} and
	 * {@code /root/Adresse} both landed on the same Kafka topic). A connector whose reported {@code type} is
	 * {@code kafka} gets one editable "Table -&gt; topic" row PER master table instead of the single
	 * connector-level "Sends to" line; a stored value in {@code DistributionTargetRow#tableDestinations} wins
	 * over the default {@link com.on.ebx.factory.distribution.TableDestinationDefaults#proposeDestination}
	 * proposes, and a connector of any OTHER type (including one with a {@code topic} connector property, like
	 * the plain "Kafka" fixture above whose {@code type} field is left blank) never renders these rows at all.
	 */
	private static void step7DistributionPerTableDestinationsOnlyForKafkaConnectorType()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.project.code = "Personnes";
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_DISTRIBUTION;

		final WizardPage.DistributionConnectionRow kafka = new WizardPage.DistributionConnectionRow();
		kafka.connector = "KafkaMdmPersonnes";
		kafka.label = "Kafka (Personnes)";
		kafka.type = "kafka";
		kafka.hasWriteOperation = true;
		kafka.writeOperations.add("publish");
		kafka.topic = "mdm.personnes";
		kafka.keyField = "id";

		final WizardPage.MasterTableRow personneTable = new WizardPage.MasterTableRow();
		personneTable.tablePath = "/root/Personne";
		personneTable.label = "Personne";
		final WizardPage.MasterTableRow adresseTable = new WizardPage.MasterTableRow();
		adresseTable.tablePath = "/root/Adresse";
		adresseTable.label = "Adresse";

		final WizardPage.DistributionTargetRow target = new WizardPage.DistributionTargetRow();
		target.id = "2";
		target.connector = "KafkaMdmPersonnes";
		target.connectorLabel = "Kafka (Personnes)";
		target.operation = "publish";
		target.mode = "REALTIME";
		target.state = "ARMED";
		// Personne already has a stored destination (typed by hand, or a previous Configure); Adresse has none
		// yet, so it must fall back to TableDestinationDefaults' own proposal.
		target.tableDestinations = "/root/Personne=mdm.personnes.personne.custom";

		final WizardPage.DistributionModel setup = new WizardPage.DistributionModel();
		setup.connections.add(kafka);
		setup.masterTables.add(personneTable);
		setup.masterTables.add(adresseTable);
		setup.openTargetId = "2";
		setup.openTarget = target;
		model.distribution = setup;
		final String html = WizardPage.render(model);

		WizardPageTest.check("no single connector-level 'Sends to' line for a kafka-type connector (replaced by the per-table rows)",
			!html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.setup.sendsTo", "mdm.personnes", "id")));
		WizardPageTest.check("the per-table destinations title is shown", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.setup.tableDestinations.title")));
		WizardPageTest.check("the topic-creation note is shown", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.setup.tableDestinations.note")));
		WizardPageTest.check("one hidden table-path field per master table",
			WizardPageTest.count(html, "<input type=\"hidden\" name=\"mdmfDistTopicTable\"") == 2);
		WizardPageTest.check("Personne's STORED destination wins over the default proposal", html.contains("value=\"" + WizardPage.esc("mdm.personnes.personne.custom") + "\""));
		WizardPageTest.check("Adresse falls back to TableDestinationDefaults' own proposal (no stored value yet)",
			html.contains("value=\"" + WizardPage.esc(com.on.ebx.factory.distribution.TableDestinationDefaults.proposeDestination("mdm.personnes", "Personnes", "/root/Adresse")) + "\""));

		// same connector, but its type is unknown/blank (e.g. an older read, or a genuinely non-Kafka connector
		// that happens to also expose a connector-level "topic" property) - no per-table rows, old single-line
		// hint instead, exactly like the pre-round-6 behaviour.
		final WizardPage.DistributionConnectionRow untyped = new WizardPage.DistributionConnectionRow();
		untyped.connector = "KafkaMdmPersonnes";
		untyped.label = "Kafka (Personnes)";
		untyped.hasWriteOperation = true;
		untyped.writeOperations.add("publish");
		untyped.topic = "mdm.personnes";
		untyped.keyField = "id";
		final WizardPage.DistributionModel setupUntyped = new WizardPage.DistributionModel();
		setupUntyped.connections.add(untyped);
		setupUntyped.masterTables.add(personneTable);
		setupUntyped.masterTables.add(adresseTable);
		setupUntyped.openTargetId = "2";
		setupUntyped.openTarget = target;
		model.distribution = setupUntyped;
		final String htmlUntyped = WizardPage.render(model);
		WizardPageTest.check("no per-table rows when the connector's type is not reported as kafka",
			!htmlUntyped.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.setup.tableDestinations.title")));
		WizardPageTest.check("the old single-line 'Sends to' hint is shown instead",
			htmlUntyped.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.setup.sendsTo", "mdm.personnes", "id")));
	}

	/**
	 * "Retire notices from the merge log" (SPEC-retire.md, UI lot, board {@code Retire.dc.html}): a REALTIME
	 * target's 7b panel shows the dashed "When two records are merged" block - the three-way {@code
	 * retireMode} radio (one option per mode, TOMBSTONE selectable but carrying its own "Kafka only" hint) and
	 * the {@code retireUndoneMerges} checkbox, both posted under the field names {@link
	 * ProjectWizardUserService.Posted#distRetireMode}/{@code #distRetireUndoneMerges} mirror.
	 */
	private static void step7DistributionRetireSettingsRenderRadioAndUndoneCheckbox()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_DISTRIBUTION;

		final WizardPage.DistributionTargetRow target = new WizardPage.DistributionTargetRow();
		target.id = "1";
		target.connector = "Kafka";
		target.connectorLabel = "KafkaMdmPersonnes";
		target.mode = "REALTIME";
		target.state = "ARMED";
		target.retireMode = "TOMBSTONE";
		target.retireUndoneMerges = false;
		final WizardPage.DistributionModel setup = new WizardPage.DistributionModel();
		setup.openTargetId = "1";
		setup.openTarget = target;
		model.distribution = setup;
		final String html = WizardPage.render(model);

		WizardPageTest.check("the block's title is shown", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.retire.title")));
		WizardPageTest.check("the static explanation is shown", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.retire.explain")));
		WizardPageTest.check("the example list shows all three change types",
			html.contains(">CREATE<") && html.contains(">RETIRE<") && html.contains(">MODIFY<"));
		WizardPageTest.check("exactly one CREATE badge and two RETIRE badges in the static example",
			WizardPageTest.count(html, ">RETIRE<") == 2);
		WizardPageTest.check("three retireMode radio options are posted under mdmfDistRetireMode",
			WizardPageTest.count(html, "name=\"mdmfDistRetireMode\"") == 3);
		WizardPageTest.check("TOMBSTONE is the checked option (stored on the target)",
			html.contains("name=\"mdmfDistRetireMode\" value=\"TOMBSTONE\" checked=\"checked\""));
		WizardPageTest.check("NOTICE is not checked", !html.contains("name=\"mdmfDistRetireMode\" value=\"NOTICE\" checked=\"checked\""));
		WizardPageTest.check("the tombstone option's own hint names Kafka and the refusal",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.retire.mode.TOMBSTONE.explain")));
		WizardPageTest.check("the undone-merges checkbox reflects the stored false value (unchecked)",
			html.contains("name=\"mdmfDistRetireUndoneMerges\" value=\"true\"/>") && !html.contains("name=\"mdmfDistRetireUndoneMerges\" value=\"true\" checked=\"checked\""));

		// a brand-new, unsaved target defaults to NOTICE checked and the checkbox checked (DistributionTargetRow's
		// own field defaults, matching the engine's XSD defaults - see this class' own field Javadoc).
		final WizardPage.DistributionTargetRow fresh = new WizardPage.DistributionTargetRow();
		fresh.mode = "REALTIME";
		final WizardPage.DistributionModel setupFresh = new WizardPage.DistributionModel();
		setupFresh.openTargetId = "NEW";
		setupFresh.openTarget = fresh;
		model.distribution = setupFresh;
		final String htmlFresh = WizardPage.render(model);
		WizardPageTest.check("a brand-new target defaults retireMode to NOTICE, checked",
			htmlFresh.contains("name=\"mdmfDistRetireMode\" value=\"NOTICE\" checked=\"checked\""));
		WizardPageTest.check("a brand-new target defaults retireUndoneMerges to checked",
			htmlFresh.contains("name=\"mdmfDistRetireUndoneMerges\" value=\"true\" checked=\"checked\""));
	}

	/** Same lot: the block only matters for a REALTIME target - a merge-log watch only ever drains a REALTIME target's outbox (SPEC-retire.md). ON_DEMAND/SCHEDULED targets show none of it. */
	private static void step7DistributionRetireSettingsHiddenForNonRealtimeMode()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_DISTRIBUTION;

		final WizardPage.DistributionTargetRow target = new WizardPage.DistributionTargetRow();
		target.id = "1";
		target.connector = "Kafka";
		target.mode = "ON_DEMAND";
		target.state = "DRAFT";
		final WizardPage.DistributionModel setup = new WizardPage.DistributionModel();
		setup.openTargetId = "1";
		setup.openTarget = target;
		model.distribution = setup;
		final String html = WizardPage.render(model);

		WizardPageTest.check("no retire block title for an ON_DEMAND target", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.retire.title")));
		WizardPageTest.check("no retireMode radio posted for an ON_DEMAND target", !html.contains("name=\"mdmfDistRetireMode\""));
		WizardPageTest.check("no retireUndoneMerges checkbox posted for an ON_DEMAND target", !html.contains("name=\"mdmfDistRetireUndoneMerges\""));
	}

	/**
	 * "Retire notices from the merge log" (SPEC-retire.md, UI lot, board {@code RetireHistory.dc.html}): a
	 * target's recent-sends table renders the three special wordings - a RETIRE with a survivor, a RETIRE
	 * whose golden was deleted by an undone merge (no survivor), and a CREATE reinstating a former member -
	 * plus a plain fallback (its own stored message) for every other row.
	 */
	private static void step7DistributionHistoryRendersRetireAndReinstatedWording()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_DISTRIBUTION;

		final WizardPage.DistributionTargetRow target = new WizardPage.DistributionTargetRow();
		target.id = "2";
		target.connector = "Kafka";
		target.mode = "REALTIME";
		target.state = "ARMED";

		final WizardPage.EventRow golden = new WizardPage.EventRow();
		golden.tablePath = "/root/Personne";
		golden.recordKey = "692";
		golden.change = "CREATE";
		golden.status = "SENT";
		golden.message = "golden · Pierre Lefevre";
		target.events.add(golden);

		final WizardPage.EventRow retireMerged = new WizardPage.EventRow();
		retireMerged.tablePath = "/root/Personne";
		retireMerged.recordKey = "347";
		retireMerged.change = "RETIRE";
		retireMerged.status = "SENT";
		retireMerged.reason = "MERGE";
		retireMerged.mergeId = "1";
		retireMerged.survivorKey = "692";
		target.events.add(retireMerged);

		final WizardPage.EventRow retireUnmergedGolden = new WizardPage.EventRow();
		retireUnmergedGolden.tablePath = "/root/Personne";
		retireUnmergedGolden.recordKey = "692";
		retireUnmergedGolden.change = "RETIRE";
		retireUnmergedGolden.status = "SENT";
		retireUnmergedGolden.reason = "UNMERGE";
		target.events.add(retireUnmergedGolden);

		final WizardPage.EventRow reinstated = new WizardPage.EventRow();
		reinstated.tablePath = "/root/Personne";
		reinstated.recordKey = "347";
		reinstated.change = "CREATE";
		reinstated.status = "SENT";
		reinstated.reason = "UNMERGE";
		target.events.add(reinstated);

		final WizardPage.DistributionModel setup = new WizardPage.DistributionModel();
		setup.openTargetId = "2";
		setup.openTarget = target;
		model.distribution = setup;
		final String html = WizardPage.render(model);

		WizardPageTest.check("the history title is shown", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.history.title")));
		WizardPageTest.check("a plain trigger-written row keeps its own stored message", html.contains(WizardPage.esc("golden · Pierre Lefevre")));
		WizardPageTest.check("a RETIRE with a survivor reads 'merged into <survivor> - merge <id>'",
			html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.history.retire.merged", "692", "1"))));
		WizardPageTest.check("a RETIRE of an unmerged golden with no survivor reads the dedicated wording",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.history.retire.unmergedGolden")));
		WizardPageTest.check("a reinstated member's CREATE reads 'live again after an undone merge'",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.history.create.liveAgain")));

		// a target with no events at all renders nothing (the panel is a no-op, not an empty table).
		final WizardPage.DistributionTargetRow empty = new WizardPage.DistributionTargetRow();
		empty.id = "3";
		empty.connector = "Kafka";
		empty.mode = "REALTIME";
		empty.state = "DRAFT";
		final WizardPage.DistributionModel setupEmpty = new WizardPage.DistributionModel();
		setupEmpty.openTargetId = "3";
		setupEmpty.openTarget = empty;
		model.distribution = setupEmpty;
		final String htmlEmpty = WizardPage.render(model);
		WizardPageTest.check("no history title when the target has no events yet",
			!htmlEmpty.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.history.title")));
	}

	/**
	 * Coordinator review (2026-09-24, board {@code DistributionDataProduct.dc.html}): the data product panel's
	 * own per-child "Own table / Nested in &lt;PARENT&gt;" select, the "added" tag on a catalogue asset already
	 * added, and the "+ Add assets from the catalogue" searchable combo listing the ones not yet added.
	 */
	private static void step7DistributionProductPanelOffersNestModeAndAddAssets()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_DISTRIBUTION;

		final WizardPage.DistributionModel d = new WizardPage.DistributionModel();
		d.productPanelOpen = true;
		d.product.catalogInstalled = true;
		d.product.snowflakePresent = true;
		d.product.snowflakeReachable = true;

		final WizardPage.DataProductTableRow person = new WizardPage.DataProductTableRow();
		person.key = "Person";
		person.label = "Person";
		person.from = "Project master";
		person.rowCount = 301;
		person.included = true;
		d.product.tables.add(person);

		final WizardPage.DataProductTableRow address = new WizardPage.DataProductTableRow();
		address.key = "Address";
		address.label = "Address";
		address.from = "Project master";
		address.rowCount = 412;
		address.included = true;
		address.child = true;
		address.parentLabel = "Person";
		address.nestMode = "OWN";
		d.product.tables.add(address);

		final WizardPage.DataProductTableRow country = new WizardPage.DataProductTableRow();
		country.key = "Country";
		country.label = "Country";
		country.from = "Catalogue - Reference";
		country.rowCount = 249;
		country.included = true;
		country.added = true;
		d.product.tables.add(country);

		final WizardPage.DataProductTableRow addableRegion = new WizardPage.DataProductTableRow();
		addableRegion.key = "Region";
		addableRegion.label = "Region";
		addableRegion.from = "Catalogue - Reference";
		addableRegion.rowCount = 12;
		d.product.addableAssets.add(addableRegion);

		model.distribution = d;
		final String html = WizardPage.render(model);
		WizardPageTest.check("a non-child row (Person) has no nest select", !html.contains("mdmfProductNest__Person"));
		WizardPageTest.check("a child row (Address) offers the nest select named after its key",
			html.contains("<select name=\"mdmfProductNest__Address\">"));
		WizardPageTest.check("the nest select names the real parent", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.product.nest.nested", "Person")));
		WizardPageTest.check("an already-added catalogue asset (Country) shows the 'added' tag",
			html.contains("Country") && html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7dist.product.added")));
		WizardPageTest.check("Country (added) is a plain checkbox row, not in the addable combo",
			!html.substring(html.indexOf("mdmfProductAssetList"), html.indexOf("mdmfProductAddAssetId")).contains("Country"));
		WizardPageTest.check("the not-yet-added asset (Region) is offered in the add-assets combo",
			html.contains("data-mdmf-id=\"Region\"") && html.contains("data-mdmf-label=\"Region\""));
		WizardPageTest.check("the add-assets combo posts through its own hidden pick button",
			html.contains("data-mdmf-action=\"DISTRIBUTION_PRODUCT_ADD_ASSET\""));
	}

	/**
	 * Inline-reviews lot (2026-09-23): a MAPPING_STEP/QUALITY/SCRIPT_CONSTRAINT proposal of the "Supplier"
	 * entity (fixture from {@link #demoSnapshot}) renders decidable in place, under that entity's own card -
	 * never a pointer to Proposal review.
	 */
	private static void proposalLinesRenderInlineUnderTheirEntity()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.StagingEntityInfo supplier = model.snapshot.stagingEntities.get(0);
		final ReviewLine qualityLine = new ReviewLine(ReviewLine.QUALITY, "q1", "Supplier.supplierId", "Pattern check", "RULE", Integer.valueOf(80),
			"Matches ^[A-Z0-9]+$ on 100 value(s).", ReviewLine.PROPOSED, false, false);
		supplier.proposalLines.add(new WizardSnapshot.ProposalLineInfo(qualityLine));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the QUALITY nature label is shown",
			html.contains(com.on.ebx.factory.review.ReviewMessages.get(Locale.ENGLISH, "review.nature.QUALITY")));
		WizardPageTest.check("the element names the attribute", html.contains("Supplier.supplierId"));
		WizardPageTest.check("the proposition is shown", html.contains("Pattern check"));
		WizardPageTest.check("Accept is offered inline", html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"QUALITY\" data-mdmf-id=\"q1\""));
		WizardPageTest.check("no pointer to Proposal review is shown any more", !html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step7.cockpitHint.review")));
	}

	private static void qualityLineShowsSeverityControlPreselectedAndEvidence()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.StagingEntityInfo supplier = model.snapshot.stagingEntities.get(0);
		final ReviewLine qualityLine = new ReviewLine(ReviewLine.QUALITY, "q2", "Supplier.postalCode", "Pattern check", "RULE", Integer.valueOf(70), "",
			ReviewLine.PROPOSED, false, false);
		final WizardSnapshot.ProposalLineInfo info = new WizardSnapshot.ProposalLineInfo(qualityLine);
		info.severity = "REJECT";
		info.qualityEvidence = "335 seen, 22 failing";
		supplier.proposalLines.add(info);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the severity select is shown", html.contains("class=\"mdmf-severity-select\""));
		WizardPageTest.check("REJECT is pre-selected", html.contains("<option value=\"REJECT\" selected=\"selected\">"));
		WizardPageTest.check("the evidence figures are shown next to it", html.contains("335 seen, 22 failing"));
		WizardPageTest.check("a Save button posts QUALITY_SET_SEVERITY for this row",
			html.contains("data-mdmf-action=\"QUALITY_SET_SEVERITY\" data-mdmf-id=\"q2\" data-mdmf-severity-select=\"1\""));
	}

	/** An APPLIED SCRIPT_CONSTRAINT line shows its explanation/measured effect and offers Remove/Measure again, never plain Accept - {@code DecisionActions} already tests the rule; this checks the wizard actually renders it. */
	private static void appliedScriptConstraintShowsExplanationAndRemoveMeasure()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.StagingEntityInfo supplier = model.snapshot.stagingEntities.get(0);
		final ReviewLine scriptLine = new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "sc1", "Supplier.supplierId", "Fasttrack.lib.phone.isValid", "LLM",
			Integer.valueOf(90), "The model found an existing validator.", ReviewLine.APPLIED, false, false, "Checks the phone number is E.164-valid.",
			"303 record(s) checked, 4 reported");
		supplier.proposalLines.add(new WizardSnapshot.ProposalLineInfo(scriptLine));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the explanation paragraph is shown", html.contains("Checks the phone number is E.164-valid."));
		WizardPageTest.check("the measured effect paragraph is shown", html.contains("303 record(s) checked, 4 reported"));
		WizardPageTest.check("Remove is offered", html.contains("data-mdmf-action=\"REMOVE\" data-mdmf-nature=\"SCRIPT_CONSTRAINT\" data-mdmf-id=\"sc1\""));
		WizardPageTest.check("Measure again is offered", html.contains("data-mdmf-action=\"MEASURE\" data-mdmf-nature=\"SCRIPT_CONSTRAINT\" data-mdmf-id=\"sc1\""));
		WizardPageTest.check("no plain Accept for an APPLIED line", !html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"SCRIPT_CONSTRAINT\" data-mdmf-id=\"sc1\""));
	}

	/**
	 * Long-passes-visible lot (2026-09-23, coordinator finding on project Personnes): a RUNNING project step
	 * names how long it has been running, so a person coming back to the page sees a genuinely-in-progress
	 * pass for what it is instead of reading a bare "Running" as "probably did nothing".
	 */
	private static void runningProjectStepShowsElapsedMinutes()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final long fiveMinutesAgo = System.currentTimeMillis() - (5 * 60000L) - 500L;
		model.snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("CAPABILITY_SUGGEST_LLM", "RUNNING", "", fiveMinutesAgo));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the running-since text is shown with the elapsed minutes",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.track.runningSince", Long.valueOf(5))));
	}

	/**
	 * Staging-proposal live-run bug fix (2026-09-24): measured live on project Personnes, a RUNNING stage of
	 * the "Propose the staging" one-click run showed no elapsed time at all (only {@code DONE}/{@code FAILED}
	 * stages, which carry both {@code startedAt} and {@code endedAt}, used to render a duration) - the exact
	 * same "did it actually do anything" confusion {@link #runningProjectStepShowsElapsedMinutes} already
	 * guards for a project-level pipeline step. Also covers the self-poll attribute {@code wizard.js} reads
	 * ({@code data-mdmf-proposal-running}) is actually emitted while a run is in progress.
	 */
	private static void runningProposalStageShowsElapsedMinutesAndArmsThePoll()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.currentStep = WizardRules.STEP_STAGING;
		model.proposalRunExists = true;
		model.proposalRunRunning = true;
		final long threeMinutesAgo = System.currentTimeMillis() - (3 * 60000L) - 500L;
		final ProposalProgress progress = ProposalProgress.newRun();
		progress.start(ProposalProgress.STRUCTURE_RULE, threeMinutesAgo);
		model.proposalRunProgress = progress;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the wizard root arms wizard.js's self-poll while a run is RUNNING",
			html.contains("data-mdmf-proposal-running=\"true\""));
		// UX lot U3 (2026-10-13): the elapsed time reads "running for 3 min 0 s" (seconds included, the old text said "0 min" for the first minute)
		WizardPageTest.check("the RUNNING stage shows its own elapsed time (not silence, not a DONE-only duration)",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.run.runningFor", "3 min ")));
		WizardPageTest.check("the list is the live one: wizard.js repaints it from the jobs poll (ids, words as attributes)",
			html.contains("id=\"mdmfProposalLive\"") && html.contains("data-mdmf-pl-now=\"Now: {0}\"") && html.contains("data-key=\"STRUCTURE_RULE\"")
				&& html.contains("class=\"mdmf-hint mdmf-proposal-stage-time\""));
		WizardPageTest.check("a 'Now' line names the running pass and the counter says how many are done",
			html.contains("<b id=\"mdmfProposalNow\">Now: " + WizardMessages.get(Locale.ENGLISH, "wizard.step4.run.stage.STRUCTURE_RULE") + "</b>")
				&& html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.run.count", Integer.valueOf(0), Integer.valueOf(6))));
	}

	/**
	 * Scroll-anchor lot (2026-09-23): the server-side half of the fix - wizard.js can only scroll back to an
	 * id that is actually rendered. Every decidable block (entity card, attribute row, landing table row, and
	 * an inline proposal line grouped under an entity) carries a stable "mdmf-<NATURE>-<id>"/"mdmf-table-<id>"
	 * id. The JS side (walking up from the clicked button, restoring via scrollIntoView or a scrollable
	 * ancestor's scrollTop) is not exercised here - only that the anchors it needs exist.
	 */
	private static void stableScrollAnchorIdsAreRendered()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.StagingEntityInfo supplier = model.snapshot.stagingEntities.get(0);
		final ReviewLine qualityLine = new ReviewLine(ReviewLine.QUALITY, "q9", "Supplier.supplierId", "Pattern check", "RULE", Integer.valueOf(80), "",
			ReviewLine.PROPOSED, false, false);
		supplier.proposalLines.add(new WizardSnapshot.ProposalLineInfo(qualityLine));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		final String htmlStaging = WizardPage.render(model);
		WizardPageTest.check("the entity card carries a stable id", htmlStaging.contains("id=\"mdmf-ENTITY-Supplier\""));
		WizardPageTest.check("the attribute row carries a stable id", htmlStaging.contains("id=\"mdmf-ATTRIBUTE-a1\""));
		WizardPageTest.check("the inline proposal line carries a stable id", htmlStaging.contains("id=\"mdmf-QUALITY-q9\""));

		model.currentStep = WizardRules.STEP_LANDING;
		final String htmlLanding = WizardPage.render(model);
		WizardPageTest.check("the landing table row carries a stable id", htmlLanding.contains("id=\"mdmf-table-10\""));
	}

	private static void entityBulkButtonsShowTheEntityIdWhenDecidable()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("Accept all of this entity carries the Supplier entity id",
			html.contains("data-mdmf-action=\"STAGING_BULK_ACCEPT_ENTITY\" data-mdmf-id=\"Supplier\""));
		WizardPageTest.check("Reject all shown of this entity carries the Supplier entity id",
			html.contains("data-mdmf-action=\"STAGING_BULK_REJECT_ENTITY\" data-mdmf-id=\"Supplier\""));
		WizardPageTest.check("the Account entity (still has a PROPOSED attribute) gets its own buttons too",
			html.contains("data-mdmf-action=\"STAGING_BULK_ACCEPT_ENTITY\" data-mdmf-id=\"Account\""));
	}

	/** Block-accept lot: {@link WizardRules#hasDecidableProposals} is false for an entity with nothing left PROPOSED anywhere - no dead button. */
	private static void entityBulkButtonsAbsentWhenNothingDecidable()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.code = "Done";
		project.label = "Done";
		project.mode = "GREENFIELD";
		model.snapshot.project = project;
		final WizardSnapshot.StagingEntityInfo doneEntity = new WizardSnapshot.StagingEntityInfo();
		doneEntity.id = "DoneEntity";
		doneEntity.name = "DoneEntity";
		doneEntity.status = "APPLIED";
		final WizardSnapshot.AttributeInfo appliedAttribute = new WizardSnapshot.AttributeInfo();
		appliedAttribute.id = "da1";
		appliedAttribute.name = "field";
		appliedAttribute.status = "APPLIED";
		doneEntity.attributes.add(appliedAttribute);
		model.snapshot.stagingEntities.add(doneEntity);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("no bulk-accept button on an entity with nothing left to decide", !html.contains("data-mdmf-id=\"DoneEntity\""));
	}

	/**
	 * Quality-block-bulk lot (2026-09-23, the product owner: "j'aurais aime un accept/reject all au niveau des revues
	 * liees a la qualite"): the entity's own "Accept/Reject all quality proposals of this entity" buttons -
	 * shown only when the entity has a decidable MAPPING_STEP/QUALITY line, sharing the one "include below
	 * threshold" checkbox with the entity-wide buttons, and showing the live threshold value.
	 */
	private static void qualityOnlyBulkButtonsAndIncludeBelowThresholdCheckboxRender()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.StagingEntityInfo supplier = model.snapshot.stagingEntities.get(0);
		final ReviewLine mappingStepLine = new ReviewLine(ReviewLine.MAPPING_STEP, "ms22", "Supplier.supplierId", "Trim", "RULE", Integer.valueOf(70), "",
			ReviewLine.PROPOSED, false, false);
		supplier.proposalLines.add(new WizardSnapshot.ProposalLineInfo(mappingStepLine));
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		model.stagingBulkThreshold = 80;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the shared include-below-threshold checkbox is rendered for Supplier",
			html.contains("id=\"mdmf-include-below-Supplier\""));
		WizardPageTest.check("the checkbox label is shown", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.bulk.includeBelowThreshold")));
		WizardPageTest.check("the quality-only accept button carries the entity id and the checkbox reference",
			html.contains("data-mdmf-action=\"STAGING_BULK_ACCEPT_QUALITY\" data-mdmf-id=\"Supplier\" data-mdmf-include-below-input=\"mdmf-include-below-Supplier\""));
		WizardPageTest.check("the quality-only reject button carries the entity id and the checkbox reference",
			html.contains("data-mdmf-action=\"STAGING_BULK_REJECT_QUALITY\" data-mdmf-id=\"Supplier\" data-mdmf-include-below-input=\"mdmf-include-below-Supplier\""));
		WizardPageTest.check("the live threshold value is shown next to the quality buttons too",
			html.contains("<span class=\"mdmf-bulk-threshold-value\">80</span>"));
	}

	/**
	 * Apply-visibility lot (2026-09-23, coordinator finding on project Personnes): "Pourquoi le bouton Apply
	 * des script constraints ne s'enleve jamais ?" - an ACCEPTED, not-yet-APPLIED SCRIPT_CONSTRAINT whose zone
	 * (MASTER) has no generated model yet shows its own {@code applyMessage} AND a disabled-looking hint
	 * naming why, instead of a live Apply button that would only fail the exact same way every time.
	 */
	private static void acceptedScriptConstraintShowsApplyMessageAndBlockedApplyReasonWhenZoneMissing()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		final WizardSnapshot.StagingEntityInfo supplier = model.snapshot.stagingEntities.get(0);
		final ReviewLine scriptLine = new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "sc2", "Supplier.supplierId", "Fasttrack.lib.phone.isValid", "LLM",
			Integer.valueOf(90), "", ReviewLine.ACCEPTED, false, false);
		final WizardSnapshot.ProposalLineInfo info = new WizardSnapshot.ProposalLineInfo(scriptLine);
		info.zone = "MASTER";
		info.applyMessage = "model Personnes_Master does not exist yet: generate the master zone first (master/generate)";
		supplier.proposalLines.add(info);
		// no MASTER_DATASET PipelineStepInfo added to model.snapshot.projectSteps: the master zone was never
		// generated, exactly the reported state (ids 10-14, zone MASTER, stuck ACCEPTED)
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the row's own applyMessage is always shown", html.contains(info.applyMessage));
		WizardPageTest.check("no live Apply button for this row", !html.contains("data-mdmf-action=\"APPLY\" data-mdmf-nature=\"SCRIPT_CONSTRAINT\" data-mdmf-id=\"sc2\""));
		WizardPageTest.check("the blocked-apply reason is shown instead",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.gate.needMasterModelForApply")));
	}

	/** The sibling of {@link #appliedScriptConstraintShowsExplanationAndRemoveMeasure}: an APPLIED line also names which model it reached and when. */
	private static void appliedScriptConstraintShowsAppliedModelAndDate()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_DATASET", "DONE", ""));
		final WizardSnapshot.StagingEntityInfo supplier = model.snapshot.stagingEntities.get(0);
		final ReviewLine scriptLine = new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "sc3", "Supplier.supplierId", "Fasttrack.lib.phone.isValid", "LLM",
			Integer.valueOf(90), "", ReviewLine.APPLIED, false, false);
		final WizardSnapshot.ProposalLineInfo info = new WizardSnapshot.ProposalLineInfo(scriptLine);
		info.zone = "STAGING";
		info.appliedModel = "Companies_Staging";
		info.appliedAt = "2026-09-20 10:00";
		supplier.proposalLines.add(info);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		final String html = WizardPage.render(model);
		WizardPageTest.check("the applied model/date line is shown",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.capability.appliedInfo", "Companies_Staging", "2026-09-20 10:00")));
	}

	private static void frenchRendersAccentedLabels()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.locale = Locale.FRENCH;
		final String html = WizardPage.render(model);
		WizardPageTest.check("French title is rendered", html.contains(WizardMessages.get(Locale.FRENCH, "wizard.step1.title")));
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static WizardPage.Model stillModel(final WizardSnapshot pSnapshot)
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = pSnapshot;
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_STAGING;
		model.proposalDoneDesignerUrl = "/designer?entity=Personne";
		return model;
	}

	private static void stillToDecideCardAbsentWhenNothingIsLeft()
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		snapshot.stagingEntities.get(0).attributes.get(2).status = "ACCEPTED";
		snapshot.stagingEntities.get(1).attributes.get(1).status = "ACCEPTED";
		snapshot.stagingEntities.get(0).proposalLines.clear();
		snapshot.stagingEntities.get(0).capabilities.clear();
		final String html = WizardPage.render(WizardPageTest.stillModel(snapshot));
		WizardPageTest.check("no card when nothing is left", !html.contains("id=\"mdmf-still-to-decide\""));
		WizardPageTest.check("no build note when nothing is left", !html.contains("mdmf-still-build-note"));
		// UX lot U3 (2026-10-13): the rail rows come from Step4Counts; the waiting part links to the card only when the card exists
		WizardPageTest.check("no link to the card in the rail", !html.contains("href=\"#mdmf-still-to-decide\""));
		final Step4Counts counts = Step4Counts.of(snapshot);
		WizardPageTest.check("the rail still tallies what is accepted",
			counts.fields.accepted == 3 && html.contains("<b>Fields</b> " + counts.fields.total() + ": "
				+ WizardMessages.get(Locale.ENGLISH, "wizard.step4.counts.accepted", Integer.valueOf(3))));
	}

	private static void stillToDecideCardGathersTheRetLeftovers()
	{
		final String html = WizardPage.render(WizardPageTest.stillModel(WizardRulesTest.retSnapshot()));
		// the card alone (the entity cards under "Advanced" still render the same rule with its own buttons)
		final String card = html.substring(html.indexOf("id=\"mdmf-still-to-decide\""), html.indexOf("id=\"mdmf-step4-passes\""));
		WizardPageTest.check("the card renders once", WizardPageTest.count(html, "id=\"mdmf-still-to-decide\"") == 1);
		// staging-screens lot (2026-10-17): the card still LISTS the 7 items, its title counts the decisions that block (the connector look-ups are optional)
		final StillToDecide stillOfRet = StillToDecide.of(WizardRulesTest.retSnapshot());
		WizardPageTest.check("the card lists 7 items", stillOfRet.total() == 7 && stillOfRet.optionalTotal() > 0);
		WizardPageTest.check("title says the blocking decisions left, not the optional look-ups too",
			html.contains(">" + stillOfRet.blockingTotal() + " decision" + (stillOfRet.blockingTotal() == 1 ? "" : "s") + " left</h3>"));
		// polish lot (2026-09-27, measured live on ret)
		WizardPageTest.check("no raw severity prefix in the card", !card.contains("MODEL_CONSTRAINT -"));
		WizardPageTest.check("the severity shows as a readable chip",
			card.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.quality.severity.MODEL_CONSTRAINT"))));
		WizardPageTest.check("no raw JSON detail in the card", !card.contains("{&quot;error&quot;") && !card.contains("{\"error\""));
		WizardPageTest.check("the detail sentence is shown", card.contains("An authentication secret is required"));
		WizardPageTest.check("the lead sentence is shown", html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step4.still.lead"))));
		WizardPageTest.check("Open in the Designer reuses the step's designer URL", html.contains("href=\"/designer?entity=Personne\" target=\"_top\">Open in the Designer</a>"));
		WizardPageTest.check("the card sits under the primary block and above the Advanced passes (UX lot U4)",
			html.indexOf("id=\"mdmf-still-to-decide\"") >= 0 && html.indexOf("id=\"mdmf-still-to-decide\"") < html.indexOf("id=\"mdmf-step4-passes\"")
			&& html.indexOf("mdmf-primary-block") < html.indexOf("id=\"mdmf-still-to-decide\""));
		// groups and counts
		WizardPageTest.check("fields group with its count", html.contains("data-mdmf-still-group=\"fields\"") && html.contains("Fields <span class=\"mdmf-chip mdmf-chip-target\">2</span>"));
		WizardPageTest.check("rules group with its count", html.contains("Quality rules <span class=\"mdmf-chip mdmf-chip-target\">1</span>"));
		WizardPageTest.check("checks group with its count", html.contains("Checks and services <span class=\"mdmf-chip mdmf-chip-target\">4</span>"));
		WizardPageTest.check("no links group when none is left", !html.contains("data-mdmf-still-group=\"links\""));
		// fields: label + technical name, source chip, confidence, links note, existing DECIDE actions
		WizardPageTest.check("field label and technical name", html.contains("<b>Correspondence language</b> <code>correspondenceLanguage</code>"));
		WizardPageTest.check("fed-by chip", html.contains(">Fed by SAP</span>"));
		WizardPageTest.check("confidence chip", html.contains(">Confidence 82 %</span>"));
		WizardPageTest.check("links-accepted note on both fields",
			WizardPageTest.count(html, WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step4.still.linksAccepted"))) == 2);
		WizardPageTest.check("accepting correspondenceLanguage carries its rule",
			html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"402\" data-mdmf-carry-rules=\"true\""));
		WizardPageTest.check("addressType's Accept carries nothing",
			html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"411\">"));
		WizardPageTest.check("Reject reuses DECIDE_REJECT", html.contains("data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"ATTRIBUTE\" data-mdmf-id=\"402\""));
		WizardPageTest.check("the carried-rule note is shown", html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step4.still.carriesRules.one", Long.valueOf(1)))));
		// rule: kind + argument, measured chips, decided with the field, no buttons of its own
		WizardPageTest.check("the rule's kind and argument, severity lifted out", html.contains("<b>Must match pattern: ^(?:[0-9]|[A-Z])$</b>"));
		WizardPageTest.check("values measured chip", html.contains(">495 values measured</span>"));
		WizardPageTest.check("failing chip, good colour at 0", html.contains("mdmf-chip-real\">0 failing</span>"));
		WizardPageTest.check("decided with the field", html.contains(">Decided with the field correspondenceLanguage</span>"));
		WizardPageTest.check("the rule has no button of its own on the card", !card.contains("data-mdmf-nature=\"QUALITY\" data-mdmf-id=\"q9\""));
		// services: readiness chips, set aside, configure, set all
		WizardPageTest.check("3 secret-missing chips", WizardPageTest.count(html, ">Secret missing</span>") == 3);
		WizardPageTest.check("1 query-to-write chip", WizardPageTest.count(html, ">Query to write</span>") == 1);
		WizardPageTest.check("readiness detail shown as its sentence", html.contains("An authentication secret is required"));
		WizardPageTest.check("pricing chip", html.contains(">Paid</span>"));
		WizardPageTest.check("one Set aside per service", WizardPageTest.count(html, ">Set aside</button>") == 4 && html.contains("data-mdmf-action=\"CAPABILITY_SET_ASIDE\" data-mdmf-id=\"c4\" title="));
		WizardPageTest.check("Set all 4 aside names every service that cannot run",
			html.contains("data-mdmf-action=\"CAPABILITY_SET_ASIDE\" data-mdmf-id=\"c1,c2,c3,c4\">Set all 4 aside</button>"));
		WizardPageTest.check("Configure links to the connector in Connectors",
			html.contains("class=\"mdmf-btn mdmf-btn-sm mdmf-leave-link\" href=\"/ebx-ui/ui/advanced/data/BConnectors/Connectors/root/Connector?predicate=.%2Fid%3D%27MelissaEmail%27\""));
		WizardPageTest.check("a service is never offered Accept on the card", !card.contains("data-mdmf-nature=\"EXTERNAL_SERVICE\""));
		// build note (H), rail (3), hidden field
		WizardPageTest.check("build note next to the button", html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step4.still.buildNote"))));
		final Step4Counts retCounts = Step4Counts.of(WizardRulesTest.retSnapshot());
		WizardPageTest.check("rail: fields accepted + 2 waiting, the waiting part links to the card",
			retCounts.fields.accepted == 1 && retCounts.fields.waiting == 2 && html.contains("<b>Fields</b> " + retCounts.fields.total() + ": 1 accepted, <a class=\"mdmf-chip mdmf-chip-target\" href=\"#mdmf-still-to-decide\">2 waiting for review</a>"));
		// staging-screens lot (2026-10-17): the 4 connector look-ups still open are optional - the rail says so and shows no "waiting for review" chip for them
		WizardPageTest.check("rail: the 4 services are optional suggestions, not waiting",
			retCounts.checks.waiting == 0 && retCounts.checks.optionalWaiting == 4 && html.contains("<b>Checks and services</b> 4: 4 optional suggestions")
				&& !html.contains("href=\"#mdmf-still-to-decide\">4 waiting for review</a>"));
		WizardPageTest.check("the carry flag has its hidden field", html.contains("id=\"mdmfCarryRules\""));

		// a single service that cannot run gets no "Set all" button
		final WizardSnapshot oneService = WizardRulesTest.retSnapshot();
		for (int i = 1; i < 4; i++)
		{
			oneService.stagingEntities.get(0).capabilities.get(i).status = "SET_ASIDE";
		}
		final String oneHtml = WizardPage.render(WizardPageTest.stillModel(oneService));
		WizardPageTest.check("no Set all with one service left", !oneHtml.contains(">Set all "));
		WizardPageTest.check("the rail shows what was set aside", oneHtml.contains("3 set aside"));
	}

	private static void stillToDecideCardRendersInFrench()
	{
		final WizardPage.Model model = WizardPageTest.stillModel(WizardRulesTest.retSnapshot());
		model.locale = Locale.FRENCH;
		final String html = WizardPage.render(model);
		WizardPageTest.check("French title", html.contains(">" + StillToDecide.of(WizardRulesTest.retSnapshot()).blockingTotal() + " d\u00E9cisions restantes</h3>"));
		WizardPageTest.check("French set aside", html.contains(">Mettre de c\u00F4t\u00E9</button>"));
		WizardPageTest.check("French build note keeps its apostrophes", html.contains("Ce qui n\u2019est pas encore d\u00E9cid\u00E9"));
	}

	/** Lot P1: a {@code SCRIPT_DERIVATION} line of the {@code ret} fixture (entity Personne), with the given status, binding and applyMessage. */
	private static WizardSnapshot derivationSnapshot(final String pStatus, final String pBinding, final String pApplyMessage)
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		final com.on.ebx.factory.review.ReviewLine line = new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.SCRIPT_DERIVATION, "107",
			"Personne.phoneE164", "phoneE164 = toE164(phone, country)", "LLM", Integer.valueOf(90), "", pStatus, false, false);
		final WizardSnapshot.ProposalLineInfo info = new WizardSnapshot.ProposalLineInfo(line);
		info.binding = pBinding;
		info.applyMessage = pApplyMessage;
		snapshot.stagingEntities.get(0).proposalLines.add(info);
		// UX lot U4: the Apply buttons exist only once the staging model does
		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_DATASET", "DONE", ""));
		return snapshot;
	}

	/** The "To apply" group alone, cut out of the whole page. */
	private static String toApplyGroup(final String pHtml)
	{
		final int start = pHtml.indexOf("data-mdmf-still-group=\"toApply\"");
		WizardPageTest.check("the To apply group is rendered", start >= 0);
		return pHtml.substring(start, pHtml.indexOf("id=\"mdmf-step4-passes\"", start));
	}

	private static void toApplyDerivationWithAToBeProvidedParameterShowsAnInputAndBlocksApply()
	{
		final String binding = "{\"value\":\"phone\",\"country\":{\"toBeProvided\":\"the default country <b>of the national numbers</b>\"}}";
		final String html = WizardPage.render(WizardPageTest.stillModel(WizardPageTest.derivationSnapshot("ACCEPTED", binding, "")));
		final String group = WizardPageTest.toApplyGroup(html);
		WizardPageTest.check("the attribute-bound parameter is not offered", !group.contains("data-param=\"value\""));
		WizardPageTest.check("the to-be-provided parameter has its input, empty, with its name",
			group.contains("class=\"mdmf-param-input\" data-param=\"country\" data-initial=\"\" value=\"\"") && group.contains("<code>country</code>"));
		WizardPageTest.check("the model's note is shown, escaped", group.contains("the default country &lt;b&gt;of the national numbers&lt;/b&gt;") && !group.contains("<b>of the national numbers</b>"));
		WizardPageTest.check("a 'To provide' chip", group.contains(">To provide</span>"));
		WizardPageTest.check("ONE Save parameters button, for this derivation, naming its box",
			WizardPageTest.count(group, "data-mdmf-action=\"DERIVATION_SET_PARAMS\" data-mdmf-id=\"107\" data-mdmf-param-box=\"mdmf-params-107\">Save parameters</button>") == 1
			&& group.contains("id=\"mdmf-params-107\""));
		WizardPageTest.check("Apply is disabled, with the reason naming the parameter", !group.contains("data-mdmf-action=\"APPLY\"") && group.contains("disabled=\"disabled\" data-mdmf-apply-blocked=\"toBeProvided\"")
			&& group.contains("Apply is blocked: provide country first"));
		WizardPageTest.check("the not-applied chip, not the re-apply one", group.contains(">Not applied</span>") && !group.contains("apply again</span>"));
		WizardPageTest.check("the hidden field the script fills exists", html.contains("id=\"mdmfParams\" name=\"mdmfParams\""));
		final WizardPage.Model fr = WizardPageTest.stillModel(WizardPageTest.derivationSnapshot("ACCEPTED", binding, ""));
		fr.locale = Locale.FRENCH;
		final String frGroup = WizardPageTest.toApplyGroup(WizardPage.render(fr));
		WizardPageTest.check("French: save button and blocked reason", frGroup.contains(">Enregistrer les param\u00E8tres</button>") && frGroup.contains("Application impossible : renseignez d\u2019abord country"));
	}

	/** Fix/derivation-apply-backfill (2026-10-17, lot G1, item 3): live, capability 226 waited for defaultCountry and the only input was in the bottom list. */
	private static void aDerivationWaitingForAParameterIsAskedInThePrimaryBlock()
	{
		final String binding = "{\"value\":\"phone\",\"country\":{\"toBeProvided\":\"the default country\"}}";
		final String html = WizardPage.render(WizardPageTest.stillModel(WizardPageTest.derivationSnapshot("ACCEPTED", binding, "")));
		final int block = html.indexOf("class=\"mdmf-primary-params\"");
		WizardPageTest.check("the block is rendered in the primary card", block >= 0 && html.lastIndexOf("mdmf-primary-block", block) >= 0);
		final int toApply = html.indexOf("data-mdmf-still-group=\"toApply\"");
		WizardPageTest.check("and above the bottom list", toApply > block);
		final String top = html.substring(block, html.indexOf("class=\"mdmf-primary-extra\"", block));
		WizardPageTest.check("an input for the parameter, with its own box id", top.contains("data-param=\"country\"") && top.contains("id=\"mdmf-params-top-107\""));
		WizardPageTest.check("ONE Save parameters button, naming that box", WizardPageTest.count(top, "data-mdmf-action=\"DERIVATION_SET_PARAMS\" data-mdmf-id=\"107\" data-mdmf-param-box=\"mdmf-params-top-107\">Save parameters</button>") == 1);
		WizardPageTest.check("the build is not held back, and the block says what stays empty", top.contains("stays empty until the derivation is applied"));
		WizardPageTest.check("the bottom list keeps its own box", html.substring(toApply).contains("id=\"mdmf-params-107\""));
		WizardPageTest.check("a line under the card tells the build can go on", html.contains("waits for the parameter country"));
		// a literal-only derivation asks for nothing at the top
		final String literal = WizardPage.render(WizardPageTest.stillModel(WizardPageTest.derivationSnapshot("ACCEPTED", "{\"value\":\"phone\",\"country\":{\"literal\":\"FR\"}}", "")));
		WizardPageTest.check("nothing to ask: no block", !literal.contains("class=\"mdmf-primary-params\""));
		// French
		final WizardPage.Model fr = WizardPageTest.stillModel(WizardPageTest.derivationSnapshot("ACCEPTED", binding, ""));
		fr.locale = Locale.FRENCH;
		final String frHtml = WizardPage.render(fr);
		WizardPageTest.check("French title", frHtml.contains("Une d\u00E9rivation attend une valeur que vous seul pouvez donner"));
	}

	/** A derivation still to decide says, before the accept, that it needs a value only the person can give. */
	private static void aProposedDerivationSaysItNeedsAParameterBeforeTheAccept()
	{
		final String binding = "{\"value\":\"phone\",\"country\":{\"toBeProvided\":\"the default country\"}}";
		final String html = WizardPage.render(WizardPageTest.stillModel(WizardPageTest.derivationSnapshot("PROPOSED", binding, "")));
		final int checks = html.indexOf("data-mdmf-still-group=\"checks\"");
		WizardPageTest.check("the checks group is rendered", checks >= 0);
		final String group = html.substring(checks, html.indexOf("id=\"mdmf-step4-passes\"", checks));
		WizardPageTest.check("a chip says a parameter is needed", group.contains(">Needs a parameter</span>"));
		WizardPageTest.check("and the sentence names it and where it is entered", group.contains("needs a value only you can give (country)") && group.contains("block at the top of this step"));
		WizardPageTest.check("nothing is asked at the top yet (it is not accepted)", !html.contains("class=\"mdmf-primary-params\""));
	}

	private static void toApplyDerivationWithALiteralShowsItPrefilledAndApplyIsAvailable()
	{
		final String binding = "{\"value\":\"phone\",\"country\":{\"literal\":\"F\\\"R<x>\"},\"mode\":{\"literal\":\"\"}}";
		final String group = WizardPageTest.toApplyGroup(WizardPage.render(WizardPageTest.stillModel(WizardPageTest.derivationSnapshot("ACCEPTED", binding, ""))));
		WizardPageTest.check("the literal is pre-filled, escaped, and is its own initial value: " + group,
			group.contains("data-param=\"country\" data-initial=\"F&quot;R&lt;x&gt;\" value=\"F&quot;R&lt;x&gt;\""));
		WizardPageTest.check("a blank literal is an input too (clearing it is allowed)", group.contains("data-param=\"mode\" data-initial=\"\" value=\"\""));
		WizardPageTest.check("no 'To provide' chip when nothing is to be provided", !group.contains(">To provide</span>"));
		WizardPageTest.check("Apply is live", group.contains("data-mdmf-action=\"APPLY\" data-mdmf-nature=\"SCRIPT_DERIVATION\" data-mdmf-id=\"107\">Apply</button>")
			&& !group.contains("data-mdmf-apply-blocked"));
		final String attributeOnly = WizardPageTest.toApplyGroup(WizardPage.render(WizardPageTest.stillModel(WizardPageTest.derivationSnapshot("ACCEPTED", "{\"value\":\"phone\"}", ""))));
		WizardPageTest.check("a binding with nothing to settle shows no parameters block and the live Apply", !attributeOnly.contains("mdmf-param-box")
			&& attributeOnly.contains("data-mdmf-action=\"APPLY\""));
	}

	private static void toApplyListsAnAppliedDerivationWhoseParametersChangedWithTheReapplyLabel()
	{
		final String binding = "{\"value\":\"phone\",\"country\":{\"literal\":\"FR\"}}";
		final WizardSnapshot stale = WizardPageTest.derivationSnapshot("APPLIED", binding, com.on.ebx.factory.capability.DerivationParameters.REAPPLY_MARKER);
		final String html = WizardPage.render(WizardPageTest.stillModel(stale));
		final String group = WizardPageTest.toApplyGroup(html);
		WizardPageTest.check("listed with the re-apply label", group.contains(">Parameters changed - apply again</span>") && !group.contains(">Not applied</span>"));
		WizardPageTest.check("the button reads Re-apply and runs the same APPLY action", group.contains("data-mdmf-action=\"APPLY\" data-mdmf-nature=\"SCRIPT_DERIVATION\" data-mdmf-id=\"107\">Re-apply</button>"));
		WizardPageTest.check("its parameter stays editable", group.contains("data-param=\"country\" data-initial=\"FR\""));
		final String plain = WizardPage.render(WizardPageTest.stillModel(WizardPageTest.derivationSnapshot("APPLIED", binding, "Backfill failed: x")));
		WizardPageTest.check("an applied derivation with any other message is not listed", !plain.contains("data-mdmf-still-group=\"toApply\""));
		final WizardPage.Model fr = WizardPageTest.stillModel(stale);
		fr.locale = Locale.FRENCH;
		WizardPageTest.check("French label", WizardPageTest.toApplyGroup(WizardPage.render(fr)).contains(">Param\u00E8tres modifi\u00E9s - r\u00E9appliquer</span>"));
	}

	private static WizardPage.Model model()
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.snapshot = new WizardSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.administrator = true; model.designer = true; // audit F-02
		return model;
	}

	private static WizardSnapshot demoSnapshot()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.code = "Companies";
		project.label = "Companies 360";
		project.mode = "GREENFIELD";
		snapshot.project = project;

		final WizardSnapshot.SourceInfo erp = new WizardSnapshot.SourceInfo();
		erp.id = "1";
		erp.sourceSystem = "ERP";
		erp.status = "GENERATED";
		// live fix (2026-09-24): WizardData only ever sets catalogSourceLabel alongside a non-blank
		// catalogSourceId (WizardPage.displayName reads the label only then) - the fixture now mirrors that,
		// distinctly per source, so the Review card's title is the readable catalog label, not the raw code.
		erp.catalogSourceId = "SNOW_DEMO";
		erp.catalogSourceLabel = "Snowflake demo (ERP)";
		final WizardSnapshot.TableInfo supplier = new WizardSnapshot.TableInfo();
		supplier.id = "10";
		supplier.tableName = "SUPPLIER";
		supplier.sourceObject = "SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER";
		supplier.status = "PROPOSED";
		supplier.keyColumns = "SUPPLIER_ID";
		supplier.keyConfidence = 70;
		erp.tables.add(supplier);
		erp.pipelineSteps.add(new WizardSnapshot.PipelineStepInfo("LANDING_MODEL", "DONE", "Companies_Landing_ERP published"));
		snapshot.sources.add(erp);

		final WizardSnapshot.SourceInfo crm = new WizardSnapshot.SourceInfo();
		crm.id = "2";
		crm.sourceSystem = "CRM";
		crm.status = "SELECTED";
		crm.catalogSourceId = "SNOW_DEMO";
		crm.catalogSourceLabel = "Snowflake demo (CRM)";
		snapshot.sources.add(crm);

		final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
		entity.id = "Supplier";
		entity.name = "Supplier";
		entity.origin = "RULE";
		entity.status = "PROPOSED";
		final WizardSnapshot.AttributeInfo attribute = new WizardSnapshot.AttributeInfo();
		attribute.id = "a1";
		attribute.name = "supplierId";
		attribute.dataType = "STRING";
		attribute.businessKey = true;
		attribute.origin = "RULE";
		attribute.status = "PROPOSED";
		entity.attributes.add(attribute);
		final WizardSnapshot.AttributeInfo technicalKey = new WizardSnapshot.AttributeInfo();
		technicalKey.id = "a2";
		technicalKey.name = "sourceSystem";
		technicalKey.dataType = "STRING";
		technicalKey.primaryKey = true;
		technicalKey.origin = "RULE";
		technicalKey.status = "PROPOSED";
		entity.attributes.add(technicalKey);
		// a few more attributes, one per remaining status, used only to prove the decision cell shows exactly the
		// actions DecisionActions allows for that status - kept off the Account entity below so the entityHint
		// fixture (which depends on Account having zero accepted-like business attributes) stays untouched
		final WizardSnapshot.AttributeInfo rejectedAttribute = new WizardSnapshot.AttributeInfo();
		rejectedAttribute.id = "a5";
		rejectedAttribute.name = "legacyCode";
		rejectedAttribute.status = "REJECTED";
		entity.attributes.add(rejectedAttribute);
		final WizardSnapshot.AttributeInfo appliedAttribute = new WizardSnapshot.AttributeInfo();
		appliedAttribute.id = "a6";
		appliedAttribute.name = "region";
		appliedAttribute.status = "APPLIED";
		entity.attributes.add(appliedAttribute);
		final WizardSnapshot.AttributeInfo acceptedAttribute = new WizardSnapshot.AttributeInfo();
		acceptedAttribute.id = "a7";
		acceptedAttribute.name = "notes";
		acceptedAttribute.status = "ACCEPTED";
		entity.attributes.add(acceptedAttribute);
		snapshot.stagingEntities.add(entity);

		// Account: accepted, only its technical key accepted (the product owner's exact case) - triggers the waiting hint
		final WizardSnapshot.StagingEntityInfo account = new WizardSnapshot.StagingEntityInfo();
		account.id = "Account";
		account.name = "Account";
		account.origin = "LLM";
		account.status = "ACCEPTED";
		account.memberEntitiesText = "Supplier (ERP), Account (CRM)";
		final WizardSnapshot.AttributeInfo accountKey = new WizardSnapshot.AttributeInfo();
		accountKey.id = "a3";
		accountKey.name = "sourceId";
		accountKey.primaryKey = true;
		accountKey.status = "ACCEPTED";
		account.attributes.add(accountKey);
		final WizardSnapshot.AttributeInfo accountWaiting = new WizardSnapshot.AttributeInfo();
		accountWaiting.id = "a4";
		accountWaiting.name = "accountName";
		accountWaiting.status = "PROPOSED";
		account.attributes.add(accountWaiting);
		snapshot.stagingEntities.add(account);

		// landing-walkthrough lot (2026-10-16): the fixture's applications are validated, so step 3 opens on the launch page (the former screen with its Advanced section)
		for (final WizardSnapshot.SourceInfo validated : snapshot.sources)
		{
			validated.landingValidated = true;
		}
		return snapshot;
	}

	private static int count(final String pHaystack, final String pNeedle)
	{
		int count = 0;
		int index = 0;
		while ((index = pHaystack.indexOf(pNeedle, index)) >= 0)
		{
			count++;
			index += pNeedle.length();
		}
		return count;
	}

	/** Lot MT (2026-10-04): step 6 offers the two matching trigger modes, the stored one checked, saved by MATCHING_SET_TRIGGER; empty/unknown reads MANUAL. */
	private static void matchingTriggerChoiceIsOfferedInStep6()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.snapshot.project.id = "1";
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_MATCHING;
		String html = WizardPage.render(model);
		WizardPageTest.check("two radios, MANUAL checked by default",
			html.contains("name=\"mdmfMatchingTriggerRadio\" value=\"MANUAL\" checked=\"checked\"") && html.contains("name=\"mdmfMatchingTriggerRadio\" value=\"REALTIME\"/>"));
		WizardPageTest.check("the save action is there", html.contains("data-mdmf-action=\"MATCHING_SET_TRIGGER\""));
		WizardPageTest.check("one sentence per mode",
			html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step6.trigger.MANUAL.text")))
				&& html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step6.trigger.REALTIME.text"))));
		WizardPageTest.check("the French bundle has them too (not the key itself)",
			!WizardMessages.get(Locale.FRENCH, "wizard.step6.trigger.REALTIME.text").startsWith("wizard.step6."));
		model.snapshot.project.matchingTrigger = "REALTIME";
		html = WizardPage.render(model);
		WizardPageTest.check("REALTIME stored: it is the checked one", html.contains("value=\"REALTIME\" checked=\"checked\"") && !html.contains("value=\"MANUAL\" checked=\"checked\""));
		model.snapshot.project.id = "";
		WizardPageTest.check("no project yet: no choice offered", !WizardPage.render(model).contains("mdmfMatchingTriggerRadio"));
	}

	/** Matching-live lot (2026-09-27, asked on project ret): the last matching action under step 6's buttons, queued -> running -> per-entity results. */
	private static void matchingLivePanelFollowsTheActionUnderTheButtons()
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		for (final String[] p : new String[][] { { "mp1", "Personne", "e1" }, { "mp2", "Adresse", "e2" } })
		{
			final WizardSnapshot.MatchPlanInfo plan = new WizardSnapshot.MatchPlanInfo();
			plan.id = p[0];
			plan.entityName = p[1];
			plan.status = "APPLIED";
			model.snapshot.matchPlans.add(plan);
		}
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_MATCHING;
		WizardPageTest.check("no action yet: no panel", !WizardPage.render(model).contains("id=\"mdmfMatchingLive\""));

		// queued: the panel says so and every accepted plan waits in line
		model.matchingActivity = new com.on.ebx.factory.matching.MatchingActivity();
		model.matchingActivity.status = com.on.ebx.factory.matching.MatchingActivity.QUEUED;
		String html = WizardPage.render(model);
		WizardPageTest.check("queued: the panel is live", html.contains("id=\"mdmfMatchingLive\" data-mdmf-ma-live=\"true\""));
		WizardPageTest.check("queued: its title", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step6.live.title.RUN.QUEUED")));
		WizardPageTest.check("queued: both entities in line", WizardPageTest.count(html, ">" + WizardMessages.get(Locale.ENGLISH, "wizard.step6.live.inLine") + "<") == 2);
		WizardPageTest.check("the panel sits under the Run matching button",
			html.indexOf("id=\"mdmfMatchingLive\"") > html.indexOf("data-mdmf-action=\"MATCHING_RUN\" data-mdmf-scope=\"incremental\""));
		WizardPageTest.check("a live matching action arms the jobs banner re-render", html.contains("data-mdmf-jobs-active=\"true\""));

		// running: Personne runs, Adresse still in line
		model.matchingActivity.status = com.on.ebx.factory.matching.MatchingActivity.RUNNING;
		model.matchingActivity.startedAt = 1000L;
		final com.on.ebx.factory.matching.MatchingActivity.Entity personne = new com.on.ebx.factory.matching.MatchingActivity.Entity();
		personne.name = "Personne";
		personne.startedAt = 1000L;
		model.matchingActivity.entities.add(personne);
		model.nowMs = 5000L;
		html = WizardPage.render(model);
		WizardPageTest.check("running: Personne runs with its timer", html.contains("running 4 s"));
		WizardPageTest.check("running: Adresse still in line", WizardPageTest.count(html, ">" + WizardMessages.get(Locale.ENGLISH, "wizard.step6.live.inLine") + "<") == 1);

		// done: per-entity results, a failure's own message, links to the results blocks
		model.matchingActivity.status = com.on.ebx.factory.matching.MatchingActivity.DONE;
		model.matchingActivity.endedAt = 5000L;
		model.matchingActivity.message = "2 entities run, 0 not applied, 1 refused.";
		personne.status = com.on.ebx.factory.matching.MatchingActivity.DONE;
		personne.endedAt = 3000L;
		personne.message = "Matching run on Personne finished (DONE); Xref.goldenKey refreshed (3 set, 0 cleared, 177 unchanged).";
		final com.on.ebx.factory.matching.MatchingActivity.Entity adresse = new com.on.ebx.factory.matching.MatchingActivity.Entity();
		adresse.name = "Adresse";
		adresse.status = com.on.ebx.factory.matching.MatchingActivity.FAILED;
		adresse.startedAt = 3000L;
		adresse.endedAt = 4000L;
		adresse.message = "java.lang.ClassCastException: BigDecimal cannot be cast to String";
		model.matchingActivity.entities.add(adresse);
		final com.on.ebx.factory.matching.MatchResultsView.Input results = new com.on.ebx.factory.matching.MatchResultsView.Input();
		results.entityId = "e1";
		results.entityName = "Personne";
		results.kpis = new com.on.ebx.factory.matching.MatchStats.Kpis(345, new java.util.HashMap<String, Integer>(), 340, 5, 2, 1);
		model.matchResults.put("mp1", results);
		html = WizardPage.render(model);
		WizardPageTest.check("done: no longer live", html.contains("data-mdmf-ma-live=\"false\""));
		WizardPageTest.check("done: the run's own sentence", html.contains("2 entities run, 0 not applied, 1 refused."));
		WizardPageTest.check("done: Personne's pairs to decide, merged groups, kept apart",
			html.contains("<td>2</td><td>5</td><td>1</td>"));
		WizardPageTest.check("done: a link to Personne's results block", html.contains("href=\"#mdmf-match-results-e1\""));
		WizardPageTest.check("done: Adresse's failure message", html.contains("mdmf-ma-error") && html.contains("ClassCastException"));
		WizardPageTest.check("done: no counts for a failed entity", html.contains("<td>\u2013</td><td>\u2013</td><td>\u2013</td>"));
	}

	// ------------------------------------------------------------------------------------------ automatic intake (2026-10-05)

	private static final java.time.Instant INTAKE_NOW = java.time.Instant.parse("2026-10-05T14:40:30Z");

	private static com.on.ebx.factory.intake.IntakeStatus.Source intakeSource(final String pCode, final String pKind, final String pState)
	{
		final com.on.ebx.factory.intake.IntakeStatus.Source source = new com.on.ebx.factory.intake.IntakeStatus.Source();
		source.sourceSystem = pCode;
		source.label = pCode;
		source.kind = pKind;
		source.quietSeconds = 60;
		source.maxWaitMinutes = 10;
		source.minRows = 1;
		source.state = pState;
		source.batchCompletePath = "/ebx-mdm-factory/rest/v1/projects/Companies/intake/" + pCode + ":complete";
		return source;
	}

	private static com.on.ebx.factory.intake.IntakeStatus.Chain intakeChain(final String pId, final String pStatus, final String pStarted, final String pEnded)
	{
		final com.on.ebx.factory.intake.IntakeStatus.Chain chain = new com.on.ebx.factory.intake.IntakeStatus.Chain();
		chain.id = pId;
		chain.status = pStatus;
		chain.startedAt = java.time.Instant.parse(pStarted);
		chain.endedAt = pEnded == null ? null : java.time.Instant.parse(pEnded);
		chain.sources.add("Salesforce");
		chain.rowsWaiting = Long.valueOf(300);
		return chain;
	}

	/** A status with two applications (one pushed, one read by a Connectors flow); the caller adjusts it. */
	private static com.on.ebx.factory.intake.IntakeStatus intakeStatus(final String pState)
	{
		final com.on.ebx.factory.intake.IntakeStatus status = new com.on.ebx.factory.intake.IntakeStatus();
		status.project = "Companies";
		status.state = pState;
		status.engineRunning = true;
		status.mayOperate = true;
		status.serverTime = WizardPageTest.INTAKE_NOW;
		status.sources.add(WizardPageTest.intakeSource("Salesforce", "PUSHED", "IDLE"));
		status.sources.add(WizardPageTest.intakeSource("SAP", "CONNECTORS", "IDLE"));
		status.pipeline.published = true;
		status.pipeline.declaresIntake = true;
		if ("OFF".equals(pState))
		{
			final com.on.ebx.factory.intake.IntakeReadiness.Result readiness = com.on.ebx.factory.intake.IntakeReadiness.evaluate(true, 2, 0, true, true, 3);
			status.ready = readiness.ready;
			status.readiness.addAll(readiness.lines);
		}
		else
		{
			status.ready = true;
		}
		return status;
	}

	/** Step 8 (summary) rendered with the given intake state; the summary data is the small hand-built one the other step 8 tests use. */
	private static WizardPage.Model intakeModel(final com.on.ebx.factory.intake.IntakeStatus pStatus, final boolean pOpen)
	{
		final WizardPage.Model model = WizardPageTest.model();
		model.snapshot = WizardPageTest.demoSnapshot();
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_SUMMARY;
		final WizardSummaryData.Input in = new WizardSummaryData.Input();
		in.projectLabel = "Demo";
		in.landingLoaded = true;
		in.cockpitUrl = "/cockpit?project=Companies&x=1";
		in.intakeState = pStatus == null ? "" : pStatus.state;
		in.noSchedulePoint = new WizardSummaryData.OpenPointInput("INFO", WizardPageTest.en("wizard.step7.open.schedule.title"), WizardPageTest.en("wizard.step7.open.schedule.detail"),
			WizardPageTest.en("wizard.step7.prod.chain.schedule"), 0, "");
		final WizardSummaryData.DistributionSummaryTarget target = new WizardSummaryData.DistributionSummaryTarget();
		target.label = "Kafka";
		target.mode = "SCHEDULED";
		target.state = "ARMED";
		in.distributionTargets.add(target);
		model.summaryInput = in;
		model.summary = WizardSummaryData.compute(in);
		model.intake = new IntakeScreens.Input();
		model.intake.status = pStatus;
		model.intake.open = pOpen;
		model.intake.zoneOffsetMs = 0L;
		return model;
	}

	private static String step8(final String pHtml)
	{
		return pHtml.substring(pHtml.indexOf("<section class=\"mdmf-wiz-panel\" data-step=\"8\""));
	}

	private static String en(final String pKey, final Object... pArgs)
	{
		return WizardMessages.get(Locale.ENGLISH, pKey, pArgs);
	}

	/** The block of the Summary's first Go-to-production card: state OFF / ON / PAUSED, a reader's read-only entry, an unread status, and an intake never read at all. */
	private static void intakeSummaryCardShowsItsStateInEveryCase()
	{
		final String off = WizardPageTest.step8(WizardPage.render(WizardPageTest.intakeModel(WizardPageTest.intakeStatus("OFF"), false)));
		WizardPageTest.check("the block sits in the card, with its one line", off.contains("id=\"mdmf-intake-card\"") && off.contains(WizardPageTest.en("wizard.intake.card.text")));
		WizardPageTest.check("OFF: Set up opens the screen and anchors on it", off.contains("data-mdmf-action=\"INTAKE_OPEN\" data-mdmf-anchor=\"mdmfIntakeScreen\" data-mdmf-anchor-block=\"start\">Set up</button>"));
		WizardPageTest.check("OFF: no On / Paused chip", !off.contains(">On</span>") && !off.contains(">Paused</span>"));
		WizardPageTest.check("OFF: the no-schedule open point is still there (nothing automates the chain)", off.contains(WizardPageTest.en("wizard.step7.open.schedule.title")));

		final String on = WizardPageTest.step8(WizardPage.render(WizardPageTest.intakeModel(WizardPageTest.intakeStatus("ON"), false)));
		WizardPageTest.check("ON: the On chip and Open", on.contains("<span class=\"mdmf-chip mdmf-chip-real\">On</span>") && on.contains("data-mdmf-anchor=\"mdmfIntakeScreen\" data-mdmf-anchor-block=\"start\">Open</button>"));
		WizardPageTest.check("ON: no Set up button", !on.contains(">Set up</button>"));
		WizardPageTest.check("ON: the no-schedule open point is gone (the intake IS the automation)", !on.contains(WizardPageTest.en("wizard.step7.open.schedule.title")));

		final com.on.ebx.factory.intake.IntakeStatus pausedStatus = WizardPageTest.intakeStatus("PAUSED");
		pausedStatus.pauseReason = "stopped: 6 % of the rows held back";
		final String paused = WizardPageTest.step8(WizardPage.render(WizardPageTest.intakeModel(pausedStatus, false)));
		WizardPageTest.check("PAUSED: the warning chip, the reason and Open", paused.contains("<span class=\"mdmf-chip mdmf-chip-warn\">Paused</span>")
			&& paused.contains("Paused: stopped: 6 % of the rows held back") && paused.contains(">Open</button>"));

		final com.on.ebx.factory.intake.IntakeStatus reader = WizardPageTest.intakeStatus("OFF");
		reader.mayOperate = false;
		final String readerHtml = WizardPageTest.step8(WizardPage.render(WizardPageTest.intakeModel(reader, false)));
		WizardPageTest.check("a reader sees the state (Off) and can open the read-only view, not Set up",
			readerHtml.contains("<span class=\"mdmf-chip mdmf-chip-muted\">Off</span>") && readerHtml.contains("data-mdmf-anchor=\"mdmfIntakeScreen\" data-mdmf-anchor-block=\"start\">Open</button>") && !readerHtml.contains(">Set up</button>"));

		final WizardPage.Model failed = WizardPageTest.intakeModel(null, false);
		failed.intake.error = "<b>boom</b>";
		final String failedHtml = WizardPageTest.step8(WizardPage.render(failed));
		WizardPageTest.check("an unreadable status still renders the Summary, the block shows the (escaped) sentence, no button",
			failedHtml.contains("mdmf-intake-error") && failedHtml.contains("&lt;b&gt;boom&lt;/b&gt;") && !failedHtml.contains("<b>boom</b>")
				&& !failedHtml.contains("data-mdmf-action=\"INTAKE_OPEN\"") && failedHtml.contains("data-mdmf-action=\"PIPELINE_LAUNCH\""));

		final WizardPage.Model unread = WizardPageTest.intakeModel(null, false);
		unread.intake = null;
		WizardPageTest.check("no intake input (a step other than 8, an older fixture): no block at all", !WizardPage.render(unread).contains("mdmf-intake-card"));
		WizardPageTest.check("no inline script in the summary with the block", !WizardPage.render(WizardPageTest.intakeModel(WizardPageTest.intakeStatus("ON"), false)).toLowerCase(Locale.ENGLISH).contains("<script"));

		final com.on.ebx.factory.intake.IntakeStatus pausedForPoints = WizardPageTest.intakeStatus("PAUSED");
		pausedForPoints.pauseReason = "failed: x";
		final WizardPage.Model withPoint = WizardPageTest.intakeModel(pausedForPoints, false);
		withPoint.summaryInput.intakePausedPoint = new WizardSummaryData.OpenPointInput("WARN", WizardPageTest.en("wizard.intake.openPoint.paused.title"),
			WizardPageTest.en("wizard.intake.openPoint.paused.detail", "failed: x"), "", 0, "");
		withPoint.summary = WizardSummaryData.compute(withPoint.summaryInput);
		final String pointHtml = WizardPageTest.step8(WizardPage.render(withPoint));
		WizardPageTest.check("PAUSED: the open point carries the reason", pointHtml.contains("Automatic intake is paused") && pointHtml.contains("Reason: failed: x."));
	}

	/** The set-up screen: pre-filled controls (a stored out-of-list value kept), the two wordings by kind, the exact call, the flow, the options, the readiness and the footer. */
	private static void intakeSetupScreenRendersItsSettingsFlowAndGate()
	{
		final com.on.ebx.factory.intake.IntakeStatus status = WizardPageTest.intakeStatus("OFF");
		status.sources.get(0).quietSeconds = 90; // not one of 10/30/60/120/300/900
		status.sources.get(0).maxWaitMinutes = 45; // not one of 2/5/10/30/60
		status.sources.get(0).minRows = 25;
		status.sources.get(0).batchSignal = true;
		status.sources.get(0).label = "Salesforce <b>CRM</b>";
		status.stopRejectPercent = Integer.valueOf(5);
		final WizardPage.Model model = WizardPageTest.intakeModel(status, true);
		model.snapshot.project.matchingTrigger = "REALTIME";
		final String html = WizardPageTest.step8(WizardPage.render(model));
		WizardPageTest.check("title and lead", html.contains("Process new data as it arrives") && html.contains("Only new and changed rows are processed."));
		WizardPageTest.check("the screen replaces the summary's content, in the same step", html.contains("id=\"mdmfIntakeScreen\"") && !html.contains("mdmf-zone-grid mdmf-intake-flow") == false && !html.contains("data-mdmf-action=\"PIPELINE_LAUNCH\""));
		WizardPageTest.check("the source name is escaped", html.contains("Salesforce &lt;b&gt;CRM&lt;/b&gt;") && !html.contains("<b>CRM</b>"));
		WizardPageTest.check("every control has a real label", html.contains("<label for=\"mdmfIntakeQuiet0\">Start after a quiet period of</label><select id=\"mdmfIntakeQuiet0\"")
			&& html.contains("<label for=\"mdmfIntakeMaxWait0\">But never wait longer than</label><select id=\"mdmfIntakeMaxWait0\"")
			&& html.contains("<label for=\"mdmfIntakeMinRows0\">Only when at least</label><input type=\"number\" min=\"1\" step=\"1\" id=\"mdmfIntakeMinRows0\""));
		WizardPageTest.check("the quiet choices of the mockup, 60 s selected on the other source", html.contains("<option value=\"10\">10 seconds</option>") && html.contains("<option value=\"120\">2 minutes</option>")
			&& html.contains("<option value=\"900\">15 minutes</option>") && html.contains("<option value=\"60\" selected=\"selected\">60 seconds</option>"));
		WizardPageTest.check("a stored 90 s is kept as an option and selected, never replaced", html.contains("<option value=\"90\" selected=\"selected\">90 seconds</option>"));
		WizardPageTest.check("a stored 45 min wait is kept and selected", html.contains("<option value=\"45\" selected=\"selected\">45 minutes</option>") && html.contains("<option value=\"60\">60 minutes</option>"));
		WizardPageTest.check("the minimum rows is pre-filled", html.contains("id=\"mdmfIntakeMinRows0\" name=\"mdmfIntakeMinRows0\" value=\"25\""));
		WizardPageTest.check("the batch checkbox is ticked for the first source and shows the exact call",
			html.contains("id=\"mdmfIntakeBatch0\" name=\"mdmfIntakeBatch0\" value=\"true\" checked=\"checked\"")
				&& html.contains("POST /ebx-mdm-factory/rest/v1/projects/Companies/intake/Salesforce:complete")
				&& html.contains("the quiet period still applies if the call never comes."));
		WizardPageTest.check("the source count and codes travel in hidden fields", html.contains("name=\"mdmfIntakeCount\" value=\"2\"") && html.contains("name=\"mdmfIntakeSrc1\" value=\"SAP\""));
		WizardPageTest.check("PUSHED wording", html.contains("Pushed by the application") && html.contains(WizardPageTest.en("wizard.intake.kind.PUSHED.text")));
		WizardPageTest.check("CONNECTORS wording is the honest one", html.contains("Fed by EBX") && html.contains(WizardPageTest.en("wizard.intake.kind.CONNECTORS.text")));
		WizardPageTest.check("no invented read schedule", !html.contains("01:00") && !html.toLowerCase(Locale.ENGLISH).contains("every day") && !html.toLowerCase(Locale.ENGLISH).contains("next read"));
		WizardPageTest.check("the four flow boxes, matching read from the project", html.contains("new rows only") && html.contains("changed records only") && html.contains("as set for the project: on each save")
			&& html.contains("Kafka \\u00B7 on a schedule".replace("\\u00B7", "·").replace("on a schedule", WizardPageTest.en("wizard.step7dist.mode.SCHEDULED"))));
		model.snapshot.project.matchingTrigger = "MANUAL";
		WizardPageTest.check("matching on demand is worded as such", WizardPageTest.step8(WizardPage.render(model)).contains("as set for the project: when launched"));
		WizardPageTest.check("the review option is ticked, the stop threshold ticked with 5",
			html.contains("name=\"mdmfIntakeReview\" value=\"true\" checked=\"checked\"") && html.contains("name=\"mdmfIntakeStopOn\" value=\"true\" checked=\"checked\"")
				&& html.contains("name=\"mdmfIntakeStopPct\" aria-label=\"Percentage of held back rows that stops the chain\" value=\"5\""));
		WizardPageTest.check("the checklist, why and one-at-a-time cards", html.contains("Switched on only after the first load") && html.contains("Landing loaded from every application")
			&& html.contains("Staging and master loaded once in full") && html.contains("Rows held back reviewed · 3 left") && html.contains("Why wait for a quiet moment") && html.contains("One chain at a time"));
		WizardPageTest.check("ready: Switch on enabled, with the confirmation that says it republishes the landing models",
			html.contains("data-mdmf-action=\"INTAKE_SWITCH_ON\" data-mdmf-confirm=\"") && html.contains("republishes the landing models") && !html.contains("INTAKE_SWITCH_ON\" data-mdmf-confirm=\"" + "x")
				&& !html.contains("data-mdmf-action=\"INTAKE_SWITCH_ON\" data-mdmf-confirm=\"Save these settings and switch the automatic intake on? This installs the triggers on the landing tables and republishes the landing models, which can take a while.\" disabled"));
		WizardPageTest.check("Save settings, Back (anchored on the card) and the reworded footer", html.contains("data-mdmf-action=\"INTAKE_SAVE\">Save settings</button>")
			&& html.contains("data-mdmf-action=\"INTAKE_CLOSE\" data-mdmf-anchor=\"mdmf-intake-card\">Back</button>") && html.contains("Can be paused or switched off at any time from this screen.")
			&& !html.contains("from the Cockpit."));
		WizardPageTest.check("no script literal on the set-up screen", !html.toLowerCase(Locale.ENGLISH).contains("<script"));

		// not ready: Switch on disabled, the first blocking line is the reason, the pipeline problem is an error notice
		final com.on.ebx.factory.intake.IntakeStatus blocked = WizardPageTest.intakeStatus("OFF");
		final com.on.ebx.factory.intake.IntakeReadiness.Result readiness = com.on.ebx.factory.intake.IntakeReadiness.evaluate(false, 2, 1, false, false, 3);
		blocked.ready = readiness.ready;
		blocked.readiness.clear();
		blocked.readiness.addAll(readiness.lines);
		blocked.pipeline.declaresIntake = false;
		blocked.pipeline.problemCode = com.on.ebx.factory.intake.IntakeStatus.Pipeline.NOT_PUBLISHED; // audit F2-108: a code, worded by the screen
		final String blockedHtml = WizardPageTest.step8(WizardPage.render(WizardPageTest.intakeModel(blocked, true)));
		final String reason = WizardPageTest.en("wizard.intake.ready.PIPELINE_MODEL.ko");
		WizardPageTest.check("Switch on is disabled with the first blocking line as its reason",
			blockedHtml.contains("disabled=\"disabled\" title=\"" + reason + "\">Switch on</button>") && blockedHtml.contains("mdmf-intake-blocker\">" + reason));
		WizardPageTest.check("the not-ready chip and the blocking lines are marked", blockedHtml.contains(">Not ready</span>") && blockedHtml.contains("mdmf-intake-ready-blocking")
			&& blockedHtml.contains("1 application not loaded into the landing yet") && blockedHtml.contains("mdmf-intake-ready-info"));
		WizardPageTest.check("the pipeline problem is an error notice, worded from its code in the person's language, with no repository path",
			blockedHtml.contains("mdmf-notice mdmf-notice-bad\" role=\"alert\">" + WizardPage.esc(WizardPageTest.en("wizard.intake.pipeline.problem.NOT_PUBLISHED"))) && !blockedHtml.contains("tools/"));

		// a reader sees everything, disabled
		final com.on.ebx.factory.intake.IntakeStatus reader = WizardPageTest.intakeStatus("OFF");
		reader.mayOperate = false;
		final String readerHtml = WizardPageTest.step8(WizardPage.render(WizardPageTest.intakeModel(reader, true)));
		WizardPageTest.check("a reader: controls disabled, Switch on and Save disabled with the reason", readerHtml.contains("<select id=\"mdmfIntakeQuiet0\" name=\"mdmfIntakeQuiet0\" disabled=\"disabled\">")
			&& readerHtml.contains("data-mdmf-action=\"INTAKE_SAVE\" disabled=\"disabled\">") && readerHtml.contains(WizardPageTest.en("wizard.intake.notOperator")));

		// what was typed after a refused save is shown again
		final WizardPage.Model refused = WizardPageTest.intakeModel(WizardPageTest.intakeStatus("OFF"), true);
		refused.intake.draft = new IntakeScreens.Draft();
		refused.intake.draft.reviewRejects = false;
		refused.intake.draft.stopOn = true;
		refused.intake.draft.stopPercent = "abc";
		final IntakeScreens.SourceDraft typed = new IntakeScreens.SourceDraft();
		typed.sourceSystem = "Salesforce";
		typed.quietSeconds = "300";
		typed.maxWaitMinutes = "30";
		typed.minRows = "zero";
		refused.intake.draft.sources.add(typed);
		final String refusedHtml = WizardPageTest.step8(WizardPage.render(refused));
		WizardPageTest.check("the typed values are shown again", refusedHtml.contains("<option value=\"300\" selected=\"selected\">5 minutes</option>") && refusedHtml.contains("name=\"mdmfIntakeMinRows0\" value=\"zero\"")
			&& refusedHtml.contains("name=\"mdmfIntakeStopPct\" aria-label=\"Percentage of held back rows that stops the chain\" value=\"abc\"") && !refusedHtml.contains("name=\"mdmfIntakeReview\" value=\"true\" checked"));

		// French renders the same screen
		final WizardPage.Model french = WizardPageTest.intakeModel(WizardPageTest.intakeStatus("OFF"), true);
		french.locale = Locale.FRENCH;
		WizardPageTest.check("French set-up", WizardPageTest.step8(WizardPage.render(french)).contains("Traiter les nouvelles données d’elles-mêmes".substring(0, 0) + "Traiter les nouvelles données dès qu’elles arrivent"));
	}

	/** The running screen: each application state, "rows arriving" without a number, the chains table (stopped / failed included), the paused notice, the engine notice, the live hooks. */
	private static void intakeRunningScreenRendersEveryStateAndTheChains()
	{
		final com.on.ebx.factory.intake.IntakeStatus status = WizardPageTest.intakeStatus("ON");
		final com.on.ebx.factory.intake.IntakeStatus.Source receiving = status.sources.get(0);
		receiving.state = "RECEIVING";
		receiving.rowsWaiting = Long.valueOf(312);
		receiving.lastRowAt = java.time.Instant.parse("2026-10-05T14:40:12Z");
		receiving.pendingSince = java.time.Instant.parse("2026-10-05T14:40:00Z");
		receiving.dueAt = java.time.Instant.parse("2026-10-05T14:41:12Z");
		receiving.latestAt = java.time.Instant.parse("2026-10-05T14:50:00Z");
		receiving.reason = "QUIET";
		receiving.label = "Salesforce <b>CRM</b>";
		final com.on.ebx.factory.intake.IntakeStatus.Source idle = status.sources.get(1);
		idle.rowsWaiting = Long.valueOf(0);
		final com.on.ebx.factory.intake.IntakeStatus.Chain done = WizardPageTest.intakeChain("c1", "DONE", "2026-09-24T14:32:00Z", "2026-09-24T14:32:38Z");
		done.written = Long.valueOf(18);
		done.unchanged = Long.valueOf(4);
		done.rejected = Long.valueOf(0);
		final com.on.ebx.factory.intake.IntakeStatus.Chain rejects = WizardPageTest.intakeChain("c2", "DONE_WITH_REJECTS", "2026-09-24T11:05:00Z", "2026-09-24T11:05:21Z");
		rejects.rejected = Long.valueOf(1);
		final com.on.ebx.factory.intake.IntakeStatus.Chain stopped = WizardPageTest.intakeChain("c3", "STOPPED", "2026-09-23T16:47:00Z", "2026-09-23T16:48:12Z");
		stopped.message = "6 % of the rows held back <b>x</b>";
		final com.on.ebx.factory.intake.IntakeStatus.Chain failed = WizardPageTest.intakeChain("c4", "FAILED", "2026-09-23T10:00:00Z", "2026-09-23T10:00:05Z");
		failed.message = "workflow <img src=x onerror=alert(1)> failed";
		failed.rowsWaiting = null;
		final com.on.ebx.factory.intake.IntakeStatus.Chain runningChain = WizardPageTest.intakeChain("c5", "RUNNING", "2026-10-05T14:40:00Z", null);
		status.chains.add(runningChain);
		status.chains.add(done);
		status.chains.add(rejects);
		status.chains.add(stopped);
		status.chains.add(failed);
		status.currentChain = runningChain;
		status.rejectReview.heldBack = Long.valueOf(3);
		status.rejectReview.warning = "The MdmFactoryRejectReview publication does not exist.";
		final WizardPage.Model model = WizardPageTest.intakeModel(status, true);
		final String html = WizardPageTest.step8(WizardPage.render(model));
		WizardPageTest.check("header: title and the On chip, no set-up", html.contains("class=\"mdmf-intake-live\" id=\"mdmfIntakeLive\"") && html.contains("Automatic intake <span class=\"mdmf-chip mdmf-chip-real\">On</span>")
			&& !html.contains("INTAKE_SWITCH_ON"));
		WizardPageTest.check("Pause, Settings and Switch off (confirmed) for an operator", html.contains("data-mdmf-action=\"INTAKE_PAUSE\">Pause</button>") && html.contains("data-mdmf-action=\"INTAKE_SETTINGS_OPEN\" data-mdmf-anchor=\"mdmfIntakeSettings\" data-mdmf-anchor-block=\"start\">Settings</button>")
			&& html.contains("data-mdmf-action=\"INTAKE_SWITCH_OFF\" data-mdmf-confirm=\"") && html.contains(">Switch off</button>"));
		WizardPageTest.check("Process now is honest about being project-wide", html.contains("data-mdmf-action=\"INTAKE_PROCESS_NOW\">Process now</button>") && html.contains("Processes the waiting rows of every application"));
		WizardPageTest.check("Read now: only on the CONNECTORS card (SAP), posting its own source field; never on the pushed one",
			html.contains("data-mdmf-action=\"INTAKE_READ_NOW\" data-mdmf-intake-source=\"SAP\">Read now</button>") && !html.contains("data-mdmf-intake-source=\"Salesforce\"")
				&& WizardPageTest.count(html, "INTAKE_READ_NOW") == 1);
		WizardPageTest.check("the posted source has its own hidden field (not mdmfSourceId)", WizardPage.render(model).contains("<input type=\"hidden\" id=\"mdmfIntakeSource\" name=\"mdmfIntakeSource\" value=\"\"/>"));
		WizardPageTest.check("receiving card: chip, the big number and its caption", html.contains("data-in-source=\"Salesforce\"") && html.contains("<span class=\"mdmf-chip mdmf-chip-accent\" data-in-chip>Receiving</span>")
			&& html.contains("<b class=\"mdmf-intake-n\">312</b> <span>new rows waiting</span>"));
		WizardPageTest.check("receiving card: 18 s against the SERVER time, the quiet period, the clock and the 30 % bar", html.contains("Last row 18 s ago · starts after 60 s of quiet") && html.contains("at the latest 14:50")
			&& html.contains("<div class=\"mdmf-intake-bar\"><i style=\"width:30%\"></i></div>") && html.contains("Why: the quiet period has elapsed"));
		WizardPageTest.check("the card carries what the script ticks on", html.contains("data-in-last=\"" + java.time.Instant.parse("2026-10-05T14:40:12Z").toEpochMilli() + "\" data-in-quiet=\"60\" data-in-line-state=\"RECEIVING\""));
		WizardPageTest.check("idle card: a counted zero is shown as 0, no bar", html.contains("<span class=\"mdmf-chip mdmf-chip-muted\" data-in-chip>Idle</span>") && html.contains("<b class=\"mdmf-intake-n\">0</b>"));
		WizardPageTest.check("the application name is escaped", html.contains("Salesforce &lt;b&gt;CRM&lt;/b&gt;") && !html.contains("<b>CRM</b>"));
		WizardPageTest.check("the Connectors application says processing follows a Factory read", html.contains(WizardPageTest.en("wizard.intake.src.connectors")));
		WizardPageTest.check("the chains: 5 rows, newest first, dates in the page language", html.contains("<tr data-in-chain=\"c1\"><td>24 Sep 14:32</td>") && html.indexOf("data-in-chain=\"c5\"") < html.indexOf("data-in-chain=\"c1\""));
		WizardPageTest.check("a done chain: rows new · written · unchanged, held back, took, chip", html.contains("<td>300 new · 18 written · 4 unchanged</td><td class=\"num\">0</td><td>38 s</td><td><span class=\"mdmf-chip mdmf-chip-real\">Done</span>"));
		WizardPageTest.check("done with rejects", html.contains("<span class=\"mdmf-chip mdmf-chip-warn\">Done · 1 to review</span>"));
		WizardPageTest.check("running chain: no end, no duration", html.contains("<td>—</td><td><span class=\"mdmf-chip mdmf-chip-accent\">Running</span>"));
		WizardPageTest.check("a stopped chain with its (escaped) reason, took 1 min 12 s", html.contains("<td>1 min 12 s</td><td><span class=\"mdmf-chip mdmf-chip-bad\">Stopped: 6 % of the rows held back &lt;b&gt;x&lt;/b&gt;</span>"));
		WizardPageTest.check("a failed chain with its escaped message and no rows count at launch", html.contains("Failed: workflow &lt;img src=x onerror=alert(1)&gt; failed") && !html.contains("<img src=x"));
		WizardPageTest.check("held back rows, a link to the Cockpit and the reject-review warning", html.contains("3 rows held back to review.") && html.contains("href=\"/cockpit?project=Companies&amp;x=1\" target=\"_top\">Review them</a>")
			&& html.contains("Open in the Cockpit") && html.contains("The MdmFactoryRejectReview publication does not exist."));
		WizardPageTest.check("the empty-state sentence is hidden while chains exist", html.contains("data-in-chains-empty hidden=\"hidden\""));
		WizardPageTest.check("live hooks: url, server clock, zone, interval, state, labels", html.contains("data-mdmf-in-url=\"/ebx-mdm-factory/rest/v1/projects/Companies/intake\"")
			&& html.contains("data-mdmf-in-server-now=\"" + WizardPageTest.INTAKE_NOW.toEpochMilli() + "\"") && html.contains("data-mdmf-in-tz=\"0\"") && html.contains("data-mdmf-in-interval=\"5\"")
			&& html.contains("data-mdmf-in-state=\"ON\"") && html.contains("data-mdmf-in-running=\"c5\"") && html.contains("data-mdmf-in-labels=\"") && html.contains("data-in-hint hidden=\"hidden\""));
		WizardPageTest.check("no script literal on the running screen", !html.toLowerCase(Locale.ENGLISH).contains("<script"));

		// a number of rows never counted is "rows arriving", never 0
		receiving.rowsWaiting = null;
		final String arriving = WizardPageTest.step8(WizardPage.render(model));
		WizardPageTest.check("rowsWaiting null: 'rows arriving', no number", arriving.contains("<span class=\"mdmf-intake-arriving\">rows arriving</span>") && !arriving.contains("<b class=\"mdmf-intake-n\">312</b>")
			&& arriving.indexOf("data-in-source=\"Salesforce\"") < arriving.indexOf("mdmf-intake-arriving\">rows arriving") && arriving.indexOf("mdmf-intake-arriving\">rows arriving") < arriving.indexOf("data-in-source=\"SAP\""));

		// the other states
		receiving.state = "DUE";
		WizardPageTest.check("a due application: chip, full bar", WizardPageTest.step8(WizardPage.render(model)).contains("mdmf-chip-warn\" data-in-chip>Due</span>")
			&& WizardPageTest.step8(WizardPage.render(model)).contains("<i style=\"width:100%\"></i>"));
		receiving.state = "RUNNING";
		final String runningHtml = WizardPageTest.step8(WizardPage.render(model));
		WizardPageTest.check("a running application: Processing chip and its line", runningHtml.contains("mdmf-chip-real\" data-in-chip>Processing</span>") && runningHtml.contains("Processed by the chain running now."));

		// no chain at all: the empty state shows
		status.chains.clear();
		status.currentChain = null;
		WizardPageTest.check("no chain yet: the sentence shows", WizardPageTest.step8(WizardPage.render(model)).contains("<p class=\"mdmf-hint mdmf-intake-empty\" data-in-chains-empty>No chain has run yet.</p>"));

		// PAUSED: the chip, the reason and what to do, Resume instead of Pause, Process now disabled
		final com.on.ebx.factory.intake.IntakeStatus pausedStatus = WizardPageTest.intakeStatus("PAUSED");
		pausedStatus.pauseReason = "stopped: 6 % of the rows held back";
		final String paused = WizardPageTest.step8(WizardPage.render(WizardPageTest.intakeModel(pausedStatus, true)));
		WizardPageTest.check("paused: chip, reason notice with what to do", paused.contains("mdmf-chip-warn\">Paused</span>") && paused.contains("<b>Paused: stopped: 6 % of the rows held back</b> Look at what happened")
			&& paused.contains("Nothing starts by itself while it is paused."));
		WizardPageTest.check("paused: Resume, no Pause, Process now disabled", paused.contains("data-mdmf-action=\"INTAKE_RESUME\">Resume</button>") && !paused.contains("INTAKE_PAUSE")
			&& paused.contains("data-mdmf-action=\"INTAKE_PROCESS_NOW\" disabled=\"disabled\">"));

		// the engine is not running
		final com.on.ebx.factory.intake.IntakeStatus down = WizardPageTest.intakeStatus("ON");
		down.engineRunning = false;
		WizardPageTest.check("engine not running: an error notice", WizardPageTest.step8(WizardPage.render(WizardPageTest.intakeModel(down, true)))
			.contains("mdmf-notice mdmf-notice-bad\" role=\"alert\">The intake engine is not running on this server; the administrator can find why in the server log."));

		// the settings panel, in place
		final WizardPage.Model withSettings = WizardPageTest.intakeModel(WizardPageTest.intakeStatus("ON"), true);
		withSettings.intake.settingsOpen = true;
		final String settingsHtml = WizardPageTest.step8(WizardPage.render(withSettings));
		WizardPageTest.check("settings open: the same form, saved without leaving, and a Hide button",
			settingsHtml.contains("id=\"mdmfIntakeSettings\"") && settingsHtml.contains("name=\"mdmfIntakeQuiet0\"") && settingsHtml.contains("data-mdmf-action=\"INTAKE_SAVE\" data-mdmf-anchor=\"mdmfIntakeSettings\">Save settings</button>")
				&& settingsHtml.contains("data-mdmf-action=\"INTAKE_SETTINGS_CLOSE\">Hide settings</button>") && settingsHtml.contains("data-mdmf-in-settings-open=\"true\""));
		WizardPageTest.check("settings closed: no form on the running screen", !WizardPageTest.step8(WizardPage.render(WizardPageTest.intakeModel(WizardPageTest.intakeStatus("ON"), true))).contains("mdmfIntakeQuiet0"));

		// a reader may look, not act
		final com.on.ebx.factory.intake.IntakeStatus reader = WizardPageTest.intakeStatus("ON");
		reader.mayOperate = false;
		final String readerHtml = WizardPageTest.step8(WizardPage.render(WizardPageTest.intakeModel(reader, true)));
		WizardPageTest.check("a reader: no Read now either", !readerHtml.contains("INTAKE_READ_NOW") && !readerHtml.contains("Read now"));
		WizardPageTest.check("a reader: no Pause / Settings / Switch off, Process now disabled, Back still there", !readerHtml.contains("INTAKE_PAUSE") && !readerHtml.contains("INTAKE_SETTINGS_OPEN")
			&& !readerHtml.contains("INTAKE_SWITCH_OFF") && readerHtml.contains("data-mdmf-action=\"INTAKE_PROCESS_NOW\" disabled=\"disabled\">") && readerHtml.contains("data-mdmf-action=\"INTAKE_CLOSE\""));

		// a status that could not be read, with the screen open: the error and a way back
		final WizardPage.Model broken = WizardPageTest.intakeModel(null, true);
		broken.intake.error = "factory dataset missing";
		final String brokenHtml = WizardPageTest.step8(WizardPage.render(broken));
		WizardPageTest.check("open on an unreadable status: the sentence and Back", brokenHtml.contains("factory dataset missing") && brokenHtml.contains("data-mdmf-action=\"INTAKE_CLOSE\""));
	}

	/** The small pure helpers the two renderings (server and script) share the formulas of. */
	private static void intakeHelpersMatchTheScriptsFormulas()
	{
		WizardPageTest.check("duration: seconds, minutes, hours", "12 s".equals(IntakeScreens.duration(12)) && "1 min 12 s".equals(IntakeScreens.duration(72)) && "3 min".equals(IntakeScreens.duration(180))
			&& "1 h 05 min".equals(IntakeScreens.duration(3900)) && "0 s".equals(IntakeScreens.duration(-4)));
		WizardPageTest.check("clock in the server zone (UTC+2)", "16:40".equals(IntakeScreens.clock(java.time.Instant.parse("2026-10-05T14:40:30Z").toEpochMilli(), 7200000L))
			&& "01:05".equals(IntakeScreens.clock(java.time.Instant.parse("2026-10-05T23:05:00Z").toEpochMilli(), 7200000L)));
		WizardPageTest.check("the quiet bar: elapsed over the quiet period, capped, 0 when unknown", IntakeScreens.quietPercent(0L, 30000L, 60) == 50 && IntakeScreens.quietPercent(0L, 90000L, 60) == 100
			&& IntakeScreens.quietPercent(-1L, 90000L, 60) == 0 && IntakeScreens.quietPercent(0L, 90000L, 0) == 0);
		WizardPageTest.check("choices keep an out-of-list value, sorted", IntakeScreens.withCurrent(IntakeScreens.QUIET_CHOICES, 90).toString().equals("[10, 30, 60, 90, 120, 300, 900]")
			&& IntakeScreens.withCurrent(IntakeScreens.QUIET_CHOICES, 60).size() == 6 && IntakeScreens.withCurrent(IntakeScreens.QUIET_CHOICES, -1).size() == 6);
		WizardPageTest.check("labels: seconds below two minutes, minutes above", "60 seconds".equals(IntakeScreens.quietLabel(Locale.ENGLISH, 60)) && "2 minutes".equals(IntakeScreens.quietLabel(Locale.ENGLISH, 120))
			&& "125 seconds".equals(IntakeScreens.quietLabel(Locale.ENGLISH, 125)) && "1 minute".equals(IntakeScreens.minutesLabel(Locale.ENGLISH, 1)));
		WizardPageTest.check("the script's labels carry every state and result", IntakeScreens.labels(Locale.ENGLISH).path("state").path("DUE").asText().equals("Due")
			&& IntakeScreens.labels(Locale.FRENCH).path("result").path("FAILED.plain").asText().length() > 0 && IntakeScreens.labels(Locale.ENGLISH).path("months").size() == 12);
		WizardPageTest.check("a date in the page language", "24 Sep 14:32".equals(IntakeScreens.dateTime(java.time.Instant.parse("2026-09-24T14:32:00Z").toEpochMilli(), 0L, Locale.ENGLISH)));
	}

	/** The HTML of one step's panel (from its opening section tag to the next one, or the end). */
	static String section(final String pHtml, final int pStep)
	{
		final java.util.regex.Matcher start = java.util.regex.Pattern.compile("<section[^>]*data-step=\"" + pStep + "\"").matcher(pHtml);
		if (!start.find())
		{
			return "";
		}
		final int next = pHtml.indexOf("<section", start.end());
		return pHtml.substring(start.start(), next < 0 ? pHtml.length() : next);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
