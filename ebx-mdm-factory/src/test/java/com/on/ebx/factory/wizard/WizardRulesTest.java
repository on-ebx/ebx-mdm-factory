package com.on.ebx.factory.wizard;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JUnit-free, like the rest of the module's tests: run the main, a failure throws. Fixtures below are
 * built by hand to match the real demo state named in the task brief: project {@code Companies}, sources
 * {@code ERP} and {@code CRM} loaded, 2 relations proposed, 3 entities proposed.
 */
public final class WizardRulesTest
{
	public static void main(final String[] pArgs)
	{
		WizardRulesTest.blankSnapshotOnlyProjectReachable();
		WizardRulesTest.stillToDecideLiftsSeverityAndReadsTheDetail();
		WizardRulesTest.projectWithNoSourceUnlocksOnlySources();
		WizardRulesTest.sourceWithNoTickedTableBlocksLanding();
		WizardRulesTest.hasUnconfirmedDeducedTable();
		WizardRulesTest.addMoreApplicationsOpen();
		WizardRulesTest.demoStateReachabilityRequiresZoneLoads();
		WizardRulesTest.summaryAlwaysReachable();
		WizardRulesTest.completionFollowsTheSnapshotNotTheCurrentStep();
		WizardRulesTest.suggestedStepFollowsWhatIsMissing();
		WizardRulesTest.suggestSourceSystemCodeStripsSourceSuffix();
		WizardRulesTest.suggestSourceSystemCodeHandlesQualifiedAndEdgeNames();
		WizardRulesTest.codeFromConnectionIdUppercasesAndStrips();
		WizardRulesTest.proposeApplicationCodeFallsBackOnGenericSchemaName();
		WizardRulesTest.proposeApplicationCodeFallsBackOnTakenSchemaCode();
		WizardRulesTest.proposeApplicationCodeKeepsNonGenericUnusedSchemaCode();
		WizardRulesTest.proposeApplicationCodeDedupesEvenTheConnectionFallback();
		WizardRulesTest.nextTrustRankIsTheDefaultForAFirstSource();
		WizardRulesTest.nextTrustRankIsOneMoreThanTheHighestExisting();
		WizardRulesTest.projectProblemsRequireCodeAndLabel();
		WizardRulesTest.projectProblemsRejectDuplicateCodeUnlessOwnProject();
		WizardRulesTest.projectProblemsRejectTargetExistsForNow();
		WizardRulesTest.projectProblemsRejectNonConsolidationStyle();
		WizardRulesTest.sourceProblemsRequireCatalogSourceAndCode();
		WizardRulesTest.sourceProblemsRejectDuplicateSourceSystemWithinProject();
		WizardRulesTest.sameCatalogSourceTwiceWithDifferentCodesIsAllowed();
		WizardRulesTest.actionGatesReflectAdministratorAndPrerequisites();
		WizardRulesTest.suggestStagingLlmGateBlockedByPendingLlmProposals();
		WizardRulesTest.pipelineButtonGatesReflectPrerequisites();
		WizardRulesTest.onlyConsolidationStyleIsSelectable();
		WizardRulesTest.stepListsFollowTheStyle();
		WizardRulesTest.centralisationStepStatesAndResume();
		WizardRulesTest.centralisationBlankStart();
		WizardRulesTest.centralisationGates();
		WizardRulesTest.defaultStyleIsConsolidation();
		WizardRulesTest.entityHintNoneWhenNotAcceptedLike();
		WizardRulesTest.entityHintNoneWhenABusinessAttributeIsAccepted();
		WizardRulesTest.entityHintFiresWhenOnlyTheTechnicalKeyIsAccepted();
		WizardRulesTest.entityHintCountsOnlyProposedNonKeyAttributes();
		WizardRulesTest.hasDecidableProposalsReflectsEveryLevel();
		WizardRulesTest.hasDecidableQualityProposalsOnlyCountsMappingStepAndQuality();
		WizardRulesTest.bulkEligibilityClassifiesEveryRow();
		WizardRulesTest.capabilityApplyGateChecksTheRightZoneStep();
		WizardRulesTest.feedCoverageGatesLoadStaging();
		WizardRulesTest.feedCoverageRequiresParentFeedAcceptedToo();

		// staging-still-to-decide lot (2026-09-27, project ret)
		WizardRulesTest.stillToDecideIsEmptyWhenEverythingIsDecided();
		WizardRulesTest.stillToDecideGroupsTheRetLeftoversByNature();
		WizardRulesTest.stillToDecideRuleOfAProposedFieldIsDecidedWithTheField();
		WizardRulesTest.stillToDecideServiceReadinessChips();
		WizardRulesTest.stillToDecideSetAsideIsNeitherPendingNorAccepted();
		WizardRulesTest.stillToDecideListsAnAcceptedScriptDerivationAsToApply();
		WizardRulesTest.stillToDecideCarriesTheParametersAndTheReapplyMark();
		WizardRulesTest.stillToDecideOnlyAcceptedObjectsAndNoTechnicalKey();
		WizardRulesTest.onlyAProposedRuleFollowsItsFieldDecision();

		// wizard-llm-value-sharing lot (2026-09-28)
		WizardRulesTest.llmLevelsRankAndCapLikeTheDataPolicy();
		WizardRulesTest.projectProblemsRefuseALevelAboveTheInstanceCap();
		WizardRulesTest.projectProblemsCheckTheSampleSizeOnlyForSampleValues();
		System.out.println("WizardRulesTest OK");
	}

	// ------------------------------------------------------------------------------------------------- step states

	private static void blankSnapshotOnlyProjectReachable()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardRules.StepState[] states = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("project always reachable", states[WizardRules.STEP_PROJECT].reachable);
		WizardRulesTest.check("sources blocked without a project", !states[WizardRules.STEP_SOURCES].reachable);
		WizardRulesTest.check("sources blocked reason names the project", "wizard.blocked.needProject".equals(states[WizardRules.STEP_SOURCES].blockedKey));
		WizardRulesTest.check("landing blocked", !states[WizardRules.STEP_LANDING].reachable);
		WizardRulesTest.check("staging blocked", !states[WizardRules.STEP_STAGING].reachable);
		WizardRulesTest.check("summary always reachable even blank", states[WizardRules.STEP_SUMMARY].reachable);
	}

	private static void projectWithNoSourceUnlocksOnlySources()
	{
		final WizardSnapshot snapshot = WizardRulesTest.projectOnly();
		final WizardRules.StepState[] states = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("sources reachable once the project exists", states[WizardRules.STEP_SOURCES].reachable);
		WizardRulesTest.check("landing still blocked, no source", !states[WizardRules.STEP_LANDING].reachable);
		WizardRulesTest.check("landing blocked reason names the source", "wizard.blocked.needSource".equals(states[WizardRules.STEP_LANDING].blockedKey));
	}

	/**
	 * Step-gating lot (2026-09-24): a source with no TICKED table yet does not unlock Landing - the previous
	 * rule only asked for a source to exist at all. Once a table is ticked, Landing opens but Staging stays
	 * blocked until that ticked table (and every other ticked table) is genuinely {@code LOADED} - {@code
	 * GENERATED} is not enough, matching {@code LandingTable.status}'s own pipeline order.
	 */
	private static void sourceWithNoTickedTableBlocksLanding()
	{
		final WizardSnapshot snapshot = WizardRulesTest.projectOnly();
		final WizardSnapshot.SourceInfo source = new WizardSnapshot.SourceInfo();
		source.id = "1";
		source.sourceSystem = "ERP";
		snapshot.sources.add(source);
		final WizardRules.StepState[] states = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("landing blocked: the source has no table at all yet", !states[WizardRules.STEP_LANDING].reachable);
		WizardRulesTest.check("landing blocked reason names the ticked table", "wizard.blocked.needTickedTable".equals(states[WizardRules.STEP_LANDING].blockedKey));
		WizardRulesTest.check("staging blocked: landing itself is not reachable yet", !states[WizardRules.STEP_STAGING].reachable);
		WizardRulesTest.check("staging blocked reason names landing", "wizard.blocked.needLanding".equals(states[WizardRules.STEP_STAGING].blockedKey));

		// a table exists but is not ticked (selected=false): still no ticked table, landing stays blocked
		final WizardSnapshot.TableInfo unticked = new WizardSnapshot.TableInfo();
		unticked.status = "LOADED";
		unticked.selected = false;
		source.tables.add(unticked);
		WizardRulesTest.check("an unticked table, even LOADED, does not unlock landing",
			!WizardRules.stepStates(snapshot)[WizardRules.STEP_LANDING].reachable);

		// deduced-scan lot (2026-09-24): see hasUnconfirmedDeducedTable() below for the "Continue
		// to the landing" warning itself; this fixture continues below unchanged.

		// a ticked table unlocks landing, but staging stays blocked until it is LOADED
		final WizardSnapshot.TableInfo ticked = new WizardSnapshot.TableInfo();
		ticked.status = "PROPOSED";
		ticked.selected = true;
		source.tables.add(ticked);
		WizardRules.StepState[] withTicked = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("landing reachable once one table is ticked", withTicked[WizardRules.STEP_LANDING].reachable);
		WizardRulesTest.check("staging blocked: the ticked table is not loaded yet", !withTicked[WizardRules.STEP_STAGING].reachable);
		WizardRulesTest.check("staging blocked reason names the landing load", "wizard.blocked.needLandingLoaded".equals(withTicked[WizardRules.STEP_STAGING].blockedKey));

		ticked.status = "GENERATED";
		WizardRulesTest.check("a GENERATED (not LOADED) ticked table still blocks staging",
			!WizardRules.stepStates(snapshot)[WizardRules.STEP_STAGING].reachable);

		ticked.status = "LOADED";
		withTicked = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("staging reachable once every ticked table is loaded (unticked one ignored)", withTicked[WizardRules.STEP_STAGING].reachable);
	}

	/**
	 * Step-gating lot (2026-09-24): the demo fixture (every ticked table LOADED, a staging entity PROPOSED,
	 * no zone loaded yet) unlocks steps 1-4 and the always-open summary, but Transfers/Matching stay blocked
	 * until the staging, then the master, are genuinely loaded - {@code STAGING_LOAD}/{@code MASTER_LOAD}
	 * {@code DONE}, never the mere existence of a staging entity (the previous, looser rule this lot fixes).
	 */
	private static void demoStateReachabilityRequiresZoneLoads()
	{
		final WizardSnapshot snapshot = WizardRulesTest.demoSnapshot();
		final WizardRules.StepState[] states = WizardRules.stepStates(snapshot);
		for (int step = WizardRules.STEP_PROJECT; step <= WizardRules.STEP_STAGING; step++)
		{
			WizardRulesTest.check("demo state step " + step + " reachable", states[step].reachable);
		}
		WizardRulesTest.check("transfers blocked: the staging is not loaded yet", !states[WizardRules.STEP_TRANSFERS].reachable);
		WizardRulesTest.check("transfers blocked reason names the staging load", "wizard.blocked.needStagingLoad".equals(states[WizardRules.STEP_TRANSFERS].blockedKey));
		WizardRulesTest.check("matching blocked: the master is not loaded yet", !states[WizardRules.STEP_MATCHING].reachable);
		WizardRulesTest.check("matching blocked reason names the master load", "wizard.blocked.needMasterLoad".equals(states[WizardRules.STEP_MATCHING].blockedKey));
		WizardRulesTest.check("summary always reachable", states[WizardRules.STEP_SUMMARY].reachable);

		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		final WizardRules.StepState[] stagingLoaded = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("transfers reachable once the staging is loaded", stagingLoaded[WizardRules.STEP_TRANSFERS].reachable);
		WizardRulesTest.check("matching still blocked: the master is not loaded yet", !stagingLoaded[WizardRules.STEP_MATCHING].reachable);

		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MASTER_LOAD", "DONE", ""));
		final WizardRules.StepState[] both = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("matching reachable once the master is loaded too", both[WizardRules.STEP_MATCHING].reachable);
	}

	/** Completed-rail lot (2026-09-23): {@code completed} is judged on the snapshot alone; the summary and the rail read the same flags. */
	private static void completionFollowsTheSnapshotNotTheCurrentStep()
	{
		final WizardRules.StepState[] blank = WizardRules.stepStates(new WizardSnapshot());
		for (int step = WizardRules.STEP_PROJECT; step <= WizardRules.STEP_SUMMARY; step++)
		{
			WizardRulesTest.check("blank snapshot: step " + step + " not completed", !blank[step].completed);
		}
		final WizardRules.StepState[] projectOnly = WizardRules.stepStates(WizardRulesTest.projectOnly());
		WizardRulesTest.check("project step completed once the project is saved", projectOnly[WizardRules.STEP_PROJECT].completed);
		WizardRulesTest.check("sources step reachable but not completed without a source", projectOnly[WizardRules.STEP_SOURCES].reachable && !projectOnly[WizardRules.STEP_SOURCES].completed);

		final WizardSnapshot demo = WizardRulesTest.demoSnapshot();
		final WizardRules.StepState[] states = WizardRules.stepStates(demo);
		WizardRulesTest.check("demo: project, sources, landing and staging completed", states[WizardRules.STEP_PROJECT].completed && states[WizardRules.STEP_SOURCES].completed
			&& states[WizardRules.STEP_LANDING].completed && states[WizardRules.STEP_STAGING].completed);
		WizardRulesTest.check("demo: transfers not completed before MASTER_LOAD is DONE", !states[WizardRules.STEP_TRANSFERS].completed);
		WizardRulesTest.check("demo: matching not completed before a plan is APPLIED", !states[WizardRules.STEP_MATCHING].completed);
		WizardRulesTest.check("summary is a view, never completed", !states[WizardRules.STEP_SUMMARY].completed);

		// step-gating lot (2026-09-24): Transfers is only REACHABLE (and so only capable of being "completed")
		// once the staging itself is loaded - without this, MASTER_LOAD DONE alone would leave the step
		// blocked, which forces completed=false by construction (StepState.blocked), not what this asserts.
		demo.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		demo.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MASTER_LOAD", "DONE", ""));
		final WizardSnapshot.MatchPlanInfo applied = new WizardSnapshot.MatchPlanInfo();
		applied.id = "mp1";
		applied.status = "APPLIED";
		demo.matchPlans.add(applied);
		final WizardSnapshot.MatchPlanInfo proposed = new WizardSnapshot.MatchPlanInfo();
		proposed.id = "mp2";
		proposed.status = "PROPOSED";
		demo.matchPlans.add(proposed);
		final WizardRules.StepState[] full = WizardRules.stepStates(demo);
		WizardRulesTest.check("transfers completed on MASTER_LOAD DONE", full[WizardRules.STEP_TRANSFERS].completed);
		WizardRulesTest.check("matching completed on one APPLIED plan", full[WizardRules.STEP_MATCHING].completed);
		WizardRulesTest.check("matchPlansApplied counts only APPLIED", WizardRules.matchPlansApplied(demo) == 1);
	}

	private static void summaryAlwaysReachable()
	{
		WizardRulesTest.check("summary reachable on a blank snapshot", WizardRules.stepStates(new WizardSnapshot())[WizardRules.STEP_SUMMARY].reachable);
		WizardRulesTest.check("summary reachable on the demo snapshot", WizardRules.stepStates(WizardRulesTest.demoSnapshot())[WizardRules.STEP_SUMMARY].reachable);
	}

	private static void suggestedStepFollowsWhatIsMissing()
	{
		WizardRulesTest.check("blank snapshot suggests step 1", WizardRules.suggestedStep(new WizardSnapshot()) == WizardRules.STEP_PROJECT);
		WizardRulesTest.check("project only suggests step 2", WizardRules.suggestedStep(WizardRulesTest.projectOnly()) == WizardRules.STEP_SOURCES);
		final WizardSnapshot demo = WizardRulesTest.demoSnapshot();
		// live bug fix (2026-09-25, DistribTest, task brief item 5): staging entities merely PROPOSED (this
		// fixture's own state, no STAGING_LOAD step recorded yet) must suggest Staging again, never jump past
		// it to Summary - it used to, as soon as any staging entity existed at all.
		WizardRulesTest.check("landing loaded, staging only proposed (not loaded) suggests step 4",
			WizardRules.suggestedStep(demo) == WizardRules.STEP_STAGING);
		demo.stagingEntities.clear();
		WizardRulesTest.check("landing loaded but no staging entity suggests step 4", WizardRules.suggestedStep(demo) == WizardRules.STEP_STAGING);

		// bug fixed live 2026-09-24 (project Vague1): SOME ticked tables loaded, others not (e.g. two SF/SAP
		// tables loaded, two SFDC/CRM tables still not) must still suggest Landing, even when staging was
		// already proposed from the tables that DID load - reopening must never skip past an incomplete Landing.
		final WizardSnapshot partiallyLoaded = WizardRulesTest.demoSnapshot();
		partiallyLoaded.sources.get(1).tables.get(1).status = "PROPOSED"; // CRM/CONTACT not loaded yet
		WizardRulesTest.check("one ticked table still not loaded suggests step 3, not step 7",
			WizardRules.suggestedStep(partiallyLoaded) == WizardRules.STEP_LANDING);

		// live bug fix (2026-09-25, DistribTest): staging loaded but the master zone was never built at all -
		// DistribTest's own live state before its MASTER_BUILD_RUN finished. Must suggest Transfers (step 5),
		// never Summary.
		final WizardSnapshot stagingLoadedOnly = WizardRulesTest.demoSnapshot();
		stagingLoadedOnly.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		WizardRulesTest.check("staging loaded, master never built suggests step 5 (transfers)",
			WizardRules.suggestedStep(stagingLoadedOnly) == WizardRules.STEP_TRANSFERS);

		// live bug fix (2026-09-25, DistribTest): the master is built and loaded (this task brief's own live
		// state: 180 records, no matching run yet) - must suggest Matching (step 6), never Summary.
		final WizardSnapshot masterLoadedOnly = WizardRulesTest.demoSnapshot();
		masterLoadedOnly.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		masterLoadedOnly.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MASTER_LOAD", "DONE", ""));
		WizardRulesTest.check("master loaded, no matching run yet suggests step 6 (matching)",
			WizardRules.suggestedStep(masterLoadedOnly) == WizardRules.STEP_MATCHING);

		// once staging, master and a first matching run are all genuinely done, Summary is the right resume
		// point - Distribution (step 7) is deliberately skipped even though it is also reachable at this point:
		// it is optional and never a resume stop (task brief: "distribution stays optional and never blocks").
		final WizardSnapshot fullyDone = WizardRulesTest.demoSnapshot();
		fullyDone.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		fullyDone.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MASTER_LOAD", "DONE", ""));
		final WizardSnapshot.MatchPlanInfo applied = new WizardSnapshot.MatchPlanInfo();
		applied.id = "mp1";
		applied.status = "APPLIED";
		fullyDone.matchPlans.add(applied);
		WizardRulesTest.check("staging, master and a first matching run all done suggests step 8 (summary), never step 7",
			WizardRules.suggestedStep(fullyDone) == WizardRules.STEP_SUMMARY);
	}

	// -------------------------------------------------------------------------------------------------- source code

	private static void suggestSourceSystemCodeStripsSourceSuffix()
	{
		WizardRulesTest.check("ERP_SOURCE -> ERP", "ERP".equals(WizardRules.suggestSourceSystemCode("ERP_SOURCE")));
		WizardRulesTest.check("CRM_SOURCE -> CRM", "CRM".equals(WizardRules.suggestSourceSystemCode("CRM_SOURCE")));
	}

	private static void suggestSourceSystemCodeHandlesQualifiedAndEdgeNames()
	{
		WizardRulesTest.check("DB.SCHEMA -> SCHEMA (last segment)", "SCHEMA".equals(WizardRules.suggestSourceSystemCode("SNOWFLAKE_LEARNING_DB.SCHEMA")));
		WizardRulesTest.check("null gives empty, not an exception", "".equals(WizardRules.suggestSourceSystemCode(null)));
		WizardRulesTest.check("blank/only punctuation falls back to SRC", "SRC".equals(WizardRules.suggestSourceSystemCode("___")));
		WizardRulesTest.check("a name starting with a digit gets prefixed", WizardRules.suggestSourceSystemCode("123SCHEMA").matches("[A-Za-z][A-Za-z0-9_]*"));
	}

	private static void codeFromConnectionIdUppercasesAndStrips()
	{
		WizardRulesTest.check("PersVolCRM -> PERSVOLCRM", "PERSVOLCRM".equals(WizardRules.codeFromConnectionId("PersVolCRM")));
		WizardRulesTest.check("punctuation is stripped, not turned into underscores", "ABC123".equals(WizardRules.codeFromConnectionId("abc-123!")));
		WizardRulesTest.check("null gives empty, not an exception", "".equals(WizardRules.codeFromConnectionId(null)));
	}

	/**
	 * Volume-test bug (project PersVol, three H2 sources CRM/ERP/ECOM, each database exposing one schema
	 * named {@code PUBLIC}): the schema-derived code must never be a bare generic schema name - it has to
	 * fall back to the connection id instead, so two sources of one project never collide on it.
	 */
	private static void proposeApplicationCodeFallsBackOnGenericSchemaName()
	{
		final Set<String> none = Collections.<String> emptySet();
		WizardRulesTest.check("PUBLIC schema falls back to the connection id",
			"PERSVOLCRM".equals(WizardRules.proposeApplicationCode("PUBLIC", "PersVolCRM", none)));
		WizardRulesTest.check("generic check is case-insensitive",
			"PERSVOLERP".equals(WizardRules.proposeApplicationCode("public", "PersVolErp", none)));
		WizardRulesTest.check("dbo/default/main/sys are generic too", "PERSVOLECOM".equals(WizardRules.proposeApplicationCode("dbo", "PersVolEcom", none)));
	}

	private static void proposeApplicationCodeFallsBackOnTakenSchemaCode()
	{
		final Set<String> taken = new HashSet<String>(Arrays.asList("CRM"));
		WizardRulesTest.check("a non-generic but already-used schema code also falls back to the connection id",
			"PERSVOLCRM2".equals(WizardRules.proposeApplicationCode("CRM", "PersVolCRM2", taken)));
	}

	private static void proposeApplicationCodeKeepsNonGenericUnusedSchemaCode()
	{
		final Set<String> none = Collections.<String> emptySet();
		WizardRulesTest.check("a real, unused schema name is kept as-is (unchanged behavior)",
			"CRM".equals(WizardRules.proposeApplicationCode("CRM_SOURCE", "PersVolCRM", none)));
	}

	private static void proposeApplicationCodeDedupesEvenTheConnectionFallback()
	{
		final Set<String> taken = new HashSet<String>(Arrays.asList("PUBLIC", "PERSVOLCRM"));
		WizardRulesTest.check("the connection-id fallback is itself de-duplicated against existing codes",
			"PERSVOLCRM_2".equals(WizardRules.proposeApplicationCode("PUBLIC", "PersVolCRM", taken)));
	}

	// -------------------------------------------------------------------------------------------------- trust rank

	private static void nextTrustRankIsTheDefaultForAFirstSource()
	{
		WizardRulesTest.check("a project with no source yet proposes the field's own default", WizardRules.nextTrustRank(Collections.<Integer> emptyList()) == 100);
	}

	/**
	 * Volume-test bug (project PersVol): three sources added one after another all kept {@code trustRank =
	 * 100} until someone clicked the reorder arrows - the second and third source must each be proposed a
	 * value strictly higher (less trusted) than every one already stored, so the "when two applications
	 * disagree" order is actually the order they were added in.
	 */
	private static void nextTrustRankIsOneMoreThanTheHighestExisting()
	{
		WizardRulesTest.check("one existing source at the default", WizardRules.nextTrustRank(Arrays.asList(Integer.valueOf(100))) == 101);
		WizardRulesTest.check("picks the HIGHEST existing rank, not the last one added",
			WizardRules.nextTrustRank(Arrays.asList(Integer.valueOf(100), Integer.valueOf(102), Integer.valueOf(101))) == 103);
		WizardRulesTest.check("a rank already moved by the reorder arrows (1..n scale) is still respected",
			WizardRules.nextTrustRank(Arrays.asList(Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3))) == 4);
	}

	// -------------------------------------------------------------------------------------------------- project step

	// ------------------------------------------------------------------------------- language-model value sharing

	/** Same rule as ebx.LlmDataPolicy#levelFor: the more restrictive of the project's choice and the instance cap; empty = SHAPES_ONLY, unknown = NONE. */
	private static void llmLevelsRankAndCapLikeTheDataPolicy()
	{
		WizardRulesTest.check("NONE < SHAPES_ONLY < SAMPLE_VALUES", WizardRules.llmLevelRank("NONE") < WizardRules.llmLevelRank("SHAPES_ONLY")
			&& WizardRules.llmLevelRank("SHAPES_ONLY") < WizardRules.llmLevelRank("SAMPLE_VALUES"));
		WizardRulesTest.check("empty ranks as the default SHAPES_ONLY", WizardRules.llmLevelRank("") == WizardRules.llmLevelRank("SHAPES_ONLY"));
		WizardRulesTest.check("unknown fails closed to NONE", WizardRules.llmLevelRank("WHATEVER") == 0);
		WizardRulesTest.check("cap SHAPES_ONLY lowers SAMPLE_VALUES", "SHAPES_ONLY".equals(WizardRules.effectiveLlmLevel("SAMPLE_VALUES", "SHAPES_ONLY")));
		WizardRulesTest.check("cap SAMPLE_VALUES leaves NONE alone", "NONE".equals(WizardRules.effectiveLlmLevel("NONE", "SAMPLE_VALUES")));
		WizardRulesTest.check("an unreadable cap means NONE", "NONE".equals(WizardRules.effectiveLlmLevel("SAMPLE_VALUES", "garbage")));
		WizardRulesTest.check("allowed at or below the cap", WizardRules.llmLevelAllowed("SHAPES_ONLY", "SHAPES_ONLY") && WizardRules.llmLevelAllowed("NONE", "SHAPES_ONLY"));
		WizardRulesTest.check("refused above the cap", !WizardRules.llmLevelAllowed("SAMPLE_VALUES", "SHAPES_ONLY"));
		WizardRulesTest.check("sample size: empty is the default 20", WizardRules.llmSampleSize("") == 20);
		WizardRulesTest.check("sample size: 1..50 accepted", WizardRules.llmSampleSize("1") == 1 && WizardRules.llmSampleSize(" 50 ") == 50);
		WizardRulesTest.check("sample size: 0, 51 and text refused", WizardRules.llmSampleSize("0") < 0 && WizardRules.llmSampleSize("51") < 0 && WizardRules.llmSampleSize("ten") < 0);
	}

	private static WizardRules.ProjectDraft validDraft()
	{
		final WizardRules.ProjectDraft draft = new WizardRules.ProjectDraft();
		draft.code = "Companies";
		draft.label = "Companies 360";
		return draft;
	}

	private static boolean llmProblem(final WizardRules.ProjectDraft pDraft, final String pCap)
	{
		return WizardRulesTest.hasField(WizardRules.projectProblems(pDraft, Collections.<String> emptySet(), null, pCap), "llmValueSharing");
	}

	private static void projectProblemsRefuseALevelAboveTheInstanceCap()
	{
		final WizardRules.ProjectDraft draft = WizardRulesTest.validDraft();
		draft.llmValueSharing = "SAMPLE_VALUES";
		WizardRulesTest.check("SAMPLE_VALUES under a SHAPES_ONLY cap is refused, never lowered", WizardRulesTest.llmProblem(draft, "SHAPES_ONLY"));
		WizardRulesTest.check("SAMPLE_VALUES under a SAMPLE_VALUES cap is fine", !WizardRulesTest.llmProblem(draft, "SAMPLE_VALUES"));
		WizardRulesTest.check("the 3-argument overload applies no cap (existing callers/tests)",
			!WizardRulesTest.hasField(WizardRules.projectProblems(draft, Collections.<String> emptySet(), null), "llmValueSharing"));
		draft.llmValueSharing = "";
		WizardRulesTest.check("nothing posted = the default SHAPES_ONLY, fine under the default cap", !WizardRulesTest.llmProblem(draft, "SHAPES_ONLY"));
		WizardRulesTest.check("nothing posted = the default SHAPES_ONLY, refused under a NONE cap (a forged/missing post never lands above it)",
			WizardRulesTest.llmProblem(draft, "NONE"));
		draft.llmValueSharing = "SHAPES_ONLY";
		WizardRulesTest.check("SHAPES_ONLY under a NONE cap is refused", WizardRulesTest.llmProblem(draft, "NONE"));
		draft.llmValueSharing = "SOMETHING_ELSE";
		WizardRulesTest.check("an unknown level is a problem on its own field", WizardRulesTest.llmProblem(draft, "SAMPLE_VALUES"));
	}

	private static void projectProblemsCheckTheSampleSizeOnlyForSampleValues()
	{
		final WizardRules.ProjectDraft draft = WizardRulesTest.validDraft();
		draft.llmValueSharing = "SAMPLE_VALUES";
		draft.llmSampleSize = "0";
		WizardRulesTest.check("0 is refused with SAMPLE_VALUES",
			WizardRulesTest.hasField(WizardRules.projectProblems(draft, Collections.<String> emptySet(), null, "SAMPLE_VALUES"), "llmSampleSize"));
		draft.llmSampleSize = "25";
		WizardRulesTest.check("25 is accepted", WizardRules.projectProblems(draft, Collections.<String> emptySet(), null, "SAMPLE_VALUES").isEmpty());
		draft.llmValueSharing = "SHAPES_ONLY";
		draft.llmSampleSize = "999";
		WizardRulesTest.check("the size is not checked when no sample is ever sent", WizardRules.projectProblems(draft, Collections.<String> emptySet(), null, "SAMPLE_VALUES").isEmpty());
	}

	private static void projectProblemsRequireCodeAndLabel()
	{
		final WizardRules.ProjectDraft draft = new WizardRules.ProjectDraft();
		final List<WizardRules.Problem> problems = WizardRules.projectProblems(draft, Collections.<String> emptySet(), null);
		WizardRulesTest.check("empty code is a problem", WizardRulesTest.hasField(problems, "code"));
		WizardRulesTest.check("empty label is a problem", WizardRulesTest.hasField(problems, "label"));
	}

	private static void projectProblemsRejectDuplicateCodeUnlessOwnProject()
	{
		final WizardRules.ProjectDraft draft = new WizardRules.ProjectDraft();
		draft.code = "Companies";
		draft.label = "Companies 360";
		final Set<String> existing = new HashSet<String>(Arrays.asList("Companies"));
		WizardRulesTest.check("duplicate code refused for a different project", WizardRulesTest.hasField(WizardRules.projectProblems(draft, existing, null), "code"));
		WizardRulesTest.check("same code allowed when it is this project's own", !WizardRulesTest.hasField(WizardRules.projectProblems(draft, existing, "Companies"), "code"));
	}

	private static void projectProblemsRejectTargetExistsForNow()
	{
		final WizardRules.ProjectDraft draft = new WizardRules.ProjectDraft();
		draft.code = "Companies";
		draft.label = "Companies 360";
		draft.mode = "TARGET_EXISTS";
		WizardRulesTest.check("TARGET_EXISTS is refused server-side even if posted", WizardRulesTest.hasField(WizardRules.projectProblems(draft, Collections.<String> emptySet(), null), "mode"));
		draft.mode = "GREENFIELD";
		WizardRulesTest.check("GREENFIELD is fine", !WizardRulesTest.hasField(WizardRules.projectProblems(draft, Collections.<String> emptySet(), null), "mode"));
	}

	private static void projectProblemsRejectNonConsolidationStyle()
	{
		final WizardRules.ProjectDraft draft = new WizardRules.ProjectDraft();
		draft.code = "Companies";
		draft.label = "Companies 360";
		for (final String style : new String[] { "COEXISTENCE", "REGISTRY" })
		{
			draft.style = style;
			WizardRulesTest.check(style + " is refused server-side even if posted (not built)",
				WizardRulesTest.hasField(WizardRules.projectProblems(draft, Collections.<String> emptySet(), null), "style"));
		}
		draft.style = "CONSOLIDATION";
		WizardRulesTest.check("CONSOLIDATION is fine", !WizardRulesTest.hasField(WizardRules.projectProblems(draft, Collections.<String> emptySet(), null), "style"));
		// lot C1-B built CENTRALISATION; since 2026-11-10 it is "coming soon": shown, refused for a new project
		draft.style = "CENTRALISATION";
		WizardRulesTest.check("CENTRALISATION is refused server-side (coming soon since 2026-11-10)",
			WizardRulesTest.hasField(WizardRules.projectProblems(draft, Collections.<String> emptySet(), null), "style"));
		WizardRulesTest.check("CENTRALISATION is the coming-soon style, not selectable", WizardRules.styleByCode("CENTRALISATION").comingSoon
			&& !WizardRules.styleByCode("CENTRALISATION").selectable && !WizardRules.styleSelectable("CENTRALISATION"));
		draft.style = "NOT_A_REAL_STYLE";
		WizardRulesTest.check("an unknown style code is refused too", WizardRulesTest.hasField(WizardRules.projectProblems(draft, Collections.<String> emptySet(), null), "style"));
	}

	// -------------------------------------------------------------------------------------------------- sources step

	private static void sourceProblemsRequireCatalogSourceAndCode()
	{
		final WizardRules.SourceDraft draft = new WizardRules.SourceDraft();
		final List<WizardRules.Problem> problems = WizardRules.sourceProblems(draft, Collections.<String> emptySet());
		WizardRulesTest.check("empty catalog source is a problem", WizardRulesTest.hasField(problems, "catalogSourceId"));
		WizardRulesTest.check("empty source system is a problem", WizardRulesTest.hasField(problems, "sourceSystem"));
	}

	private static void sourceProblemsRejectDuplicateSourceSystemWithinProject()
	{
		final WizardRules.SourceDraft draft = new WizardRules.SourceDraft();
		draft.catalogSourceId = "SNOW_DEMO";
		draft.sourceSystem = "ERP";
		final Set<String> existing = new HashSet<String>(Arrays.asList("ERP", "CRM"));
		WizardRulesTest.check("two sources of the SAME project can never share a system code",
			WizardRulesTest.hasField(WizardRules.sourceProblems(draft, existing), "sourceSystem"));
	}

	private static void sameCatalogSourceTwiceWithDifferentCodesIsAllowed()
	{
		// the demo case: SNOW_DEMO hosts both ERP_SOURCE and CRM_SOURCE - the same catalogSourceId is added
		// twice, with different sourceSystem codes; sourceProblems must never object to the catalog source
		// repeating, only to the code repeating (checked above)
		final WizardRules.SourceDraft erp = new WizardRules.SourceDraft();
		erp.catalogSourceId = "SNOW_DEMO";
		erp.sourceSystem = "ERP";
		final WizardRules.SourceDraft crm = new WizardRules.SourceDraft();
		crm.catalogSourceId = "SNOW_DEMO";
		crm.sourceSystem = "CRM";
		WizardRulesTest.check("ERP from SNOW_DEMO has no problem", WizardRules.sourceProblems(erp, Collections.<String> emptySet()).isEmpty());
		WizardRulesTest.check("CRM from the SAME SNOW_DEMO, once ERP already exists, has no problem",
			WizardRules.sourceProblems(crm, new HashSet<String>(Arrays.asList("ERP"))).isEmpty());
	}

	// -------------------------------------------------------------------------------------------------- action gates

	private static void actionGatesReflectAdministratorAndPrerequisites()
	{
		final WizardSnapshot snapshot = WizardRulesTest.projectOnly();
		WizardRulesTest.check("no source: plan/generate disabled", !WizardRules.actionGates(snapshot, true, null).planLanding.enabled);

		final WizardSnapshot.SourceInfo source = new WizardSnapshot.SourceInfo();
		source.id = "1";
		WizardRules.ActionGates nonAdmin = WizardRules.actionGates(snapshot, false, source);
		WizardRulesTest.check("plan is not an admin-only action", nonAdmin.planLanding.enabled);
		WizardRulesTest.check("generate landing needs an administrator", !nonAdmin.generateLanding.enabled);
		WizardRulesTest.check("generate landing reason names the administrator requirement", "wizard.gate.adminOnly".equals(nonAdmin.generateLanding.reasonKey));

		final WizardRules.ActionGates adminNoPlan = WizardRules.actionGates(snapshot, true, source);
		WizardRulesTest.check("administrator but nothing planned/selected: generate still disabled", !adminNoPlan.generateLanding.enabled);

		final WizardSnapshot.TableInfo table = new WizardSnapshot.TableInfo();
		table.selected = true;
		source.tables.add(table);
		final WizardRules.ActionGates adminWithPlan = WizardRules.actionGates(snapshot, true, source);
		WizardRulesTest.check("administrator with a selected planned table: generate enabled", adminWithPlan.generateLanding.enabled);

		// landing-walkthrough lot (2026-10-16): the first application with no ticked table must not block the run of the others
		final WizardSnapshot twoApps = WizardRulesTest.projectOnly();
		final WizardSnapshot.SourceInfo firstApp = new WizardSnapshot.SourceInfo();
		firstApp.id = "1";
		final WizardSnapshot.TableInfo unticked = new WizardSnapshot.TableInfo();
		unticked.selected = false;
		firstApp.tables.add(unticked);
		final WizardSnapshot.SourceInfo secondApp = new WizardSnapshot.SourceInfo();
		secondApp.id = "2";
		twoApps.sources.add(firstApp);
		twoApps.sources.add(secondApp);
		WizardRulesTest.check("no ticked table anywhere: generate disabled with the plan reason",
			!WizardRules.actionGates(twoApps, true).generateLanding.enabled && "wizard.gate.needPlan".equals(WizardRules.actionGates(twoApps, true).generateLanding.reasonKey));
		final WizardSnapshot.TableInfo ticked = new WizardSnapshot.TableInfo();
		ticked.selected = true;
		secondApp.tables.add(ticked);
		WizardRulesTest.check("first application without a ticked table, second with one: generate enabled", WizardRules.actionGates(twoApps, true).generateLanding.enabled);

		WizardRulesTest.check("suggest staging needs at least one source", !WizardRules.actionGates(WizardRulesTest.projectOnly(), true, null).suggestStaging.enabled);

		final WizardSnapshot demo = WizardRulesTest.demoSnapshot();
		final WizardRules.ActionGates demoGates = WizardRules.actionGates(demo, false, demo.sources.get(0));
		WizardRulesTest.check("suggest staging is not an admin-only action", demoGates.suggestStaging.enabled);
		WizardRulesTest.check("suggest staging LLM needs an administrator", !demoGates.suggestStagingLlm.enabled);
		final WizardRules.ActionGates demoAdminGates = WizardRules.actionGates(demo, true, demo.sources.get(0));
		WizardRulesTest.check("suggest staging LLM refused with no llm connector configured", !demoAdminGates.suggestStagingLlm.enabled);
		WizardRulesTest.check("reason names the missing llm connector", "wizard.gate.noLlm".equals(demoAdminGates.suggestStagingLlm.reasonKey));
		demo.llmConfigured = true;
		final WizardRules.ActionGates demoLlmReady = WizardRules.actionGates(demo, true, demo.sources.get(0));
		WizardRulesTest.check("with an llm connector and >=2 rule entities, suggest staging LLM is enabled", demoLlmReady.suggestStagingLlm.enabled);
	}

	/**
	 * Staging-focus lot (2026-09-23, the product owner on project A: a second click on "Affiner avec le modele de
	 * langage" 15 seconds after "Proposer le staging" had already chained it created a duplicate canonical
	 * entity): {@link WizardRules#actionGates}'s {@code suggestStagingLlm} gate refuses a re-run while a
	 * previous LLM-origin entity is still {@code PROPOSED}, and opens again once every one of them is
	 * decided. A {@code PROPOSED} RULE entity (the normal pre-first-run state) never triggers it - only
	 * origin {@code LLM} counts.
	 */
	private static void suggestStagingLlmGateBlockedByPendingLlmProposals()
	{
		final WizardSnapshot demo = WizardRulesTest.demoSnapshot();
		demo.llmConfigured = true;
		final WizardRules.ActionGates readyBeforeAnyLlmRun = WizardRules.actionGates(demo, true, demo.sources.get(0));
		WizardRulesTest.check("no LLM-origin entity yet: gate open (RULE-origin PROPOSED entities never block it)", readyBeforeAnyLlmRun.suggestStagingLlm.enabled);

		final WizardSnapshot.StagingEntityInfo canonical = new WizardSnapshot.StagingEntityInfo();
		canonical.id = "Account";
		canonical.name = "Account";
		canonical.origin = "LLM";
		canonical.status = "PROPOSED";
		demo.stagingEntities.add(canonical);
		final WizardRules.ActionGates blocked = WizardRules.actionGates(demo, true, demo.sources.get(0));
		WizardRulesTest.check("a PROPOSED LLM entity blocks a re-run", !blocked.suggestStagingLlm.enabled);
		WizardRulesTest.check("reason names the pending proposals", "wizard.gate.llmProposalsPending".equals(blocked.suggestStagingLlm.reasonKey));

		canonical.status = "ACCEPTED";
		WizardRulesTest.check("gate reopens once the LLM entity is decided", WizardRules.actionGates(demo, true, demo.sources.get(0)).suggestStagingLlm.enabled);

		canonical.status = "SUPERSEDED";
		WizardRulesTest.check("a SUPERSEDED LLM entity (should not really happen, but is not PROPOSED either) does not block it",
			WizardRules.actionGates(demo, true, demo.sources.get(0)).suggestStagingLlm.enabled);
	}

	/** Pipeline-buttons lot (2026-09-23): every gate added past staging suggestion. */
	private static void pipelineButtonGatesReflectPrerequisites()
	{
		final WizardSnapshot demo = WizardRulesTest.demoSnapshot();
		final WizardRules.ActionGates fresh = WizardRules.actionGates(demo, true, demo.sources.get(0));
		WizardRulesTest.check("quality suggest is not admin-only", WizardRules.actionGates(demo, false, demo.sources.get(0)).suggestQuality.enabled);
		WizardRulesTest.check("quality suggest enabled once entities are proposed", fresh.suggestQuality.enabled);
		WizardRulesTest.check("capability suggest-llm needs an accepted entity", !fresh.suggestCapabilitiesLlm.enabled);
		WizardRulesTest.check("generate staging needs an accepted entity", !fresh.generateStaging.enabled);
		WizardRulesTest.check("load staging needs the staging model generated first", !fresh.loadStaging.enabled);
		WizardRulesTest.check("generate master needs staging loaded first", !fresh.generateMaster.enabled);
		WizardRulesTest.check("load master needs the master model generated first", !fresh.loadMaster.enabled);
		WizardRulesTest.check("suggest matching needs the master loaded first", !fresh.suggestMatching.enabled);
		WizardRulesTest.check("configure matching needs an accepted match plan", !fresh.configureMatching.enabled);
		WizardRulesTest.check("run matching needs a configured (applied) match plan", !fresh.runMatching.enabled);
		WizardRulesTest.check("no orphaned mapping: nothing to reopen", !fresh.reopenMappings.enabled);

		demo.stagingEntities.get(0).status = "ACCEPTED";
		final WizardRules.ActionGates accepted = WizardRules.actionGates(demo, true, demo.sources.get(0));
		WizardRulesTest.check("generate staging enabled once an entity is accepted", accepted.generateStaging.enabled);
		WizardRulesTest.check("capability suggest-llm still needs an llm connector", !accepted.suggestCapabilitiesLlm.enabled);

		demo.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_DATASET", "DONE", ""));
		WizardRulesTest.check("load staging enabled once the staging model is generated",
			WizardRules.actionGates(demo, true, demo.sources.get(0)).loadStaging.enabled);

		final WizardSnapshot.AttributeInfo orphaned = new WizardSnapshot.AttributeInfo();
		orphaned.id = "aOrphan";
		orphaned.status = "ACCEPTED";
		orphaned.mappingWarning = "Accepted, but no mapping feeds it.";
		demo.stagingEntities.get(0).attributes.add(orphaned);
		final WizardRules.ActionGates orphanedGates = WizardRules.actionGates(demo, true, demo.sources.get(0));
		WizardRulesTest.check("load staging refused while an accepted attribute has nothing feeding it", !orphanedGates.loadStaging.enabled);
		WizardRulesTest.check("reason names the missing mapping", "wizard.gate.needMappings".equals(orphanedGates.loadStaging.reasonKey));
		WizardRulesTest.check("reopen mappings is now offered", orphanedGates.reopenMappings.enabled);

		demo.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		WizardRulesTest.check("generate master enabled once staging is loaded", WizardRules.actionGates(demo, true, demo.sources.get(0)).generateMaster.enabled);
		demo.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MASTER_DATASET", "DONE", ""));
		WizardRulesTest.check("load master enabled once the master model is generated", WizardRules.actionGates(demo, true, demo.sources.get(0)).loadMaster.enabled);
		demo.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MASTER_LOAD", "DONE", ""));
		WizardRulesTest.check("suggest matching enabled once the master is loaded", WizardRules.actionGates(demo, true, demo.sources.get(0)).suggestMatching.enabled);

		final WizardSnapshot.MatchPlanInfo acceptedPlan = new WizardSnapshot.MatchPlanInfo();
		acceptedPlan.id = "mp1";
		acceptedPlan.entityName = "Supplier";
		acceptedPlan.status = "ACCEPTED";
		demo.matchPlans.add(acceptedPlan);
		WizardRulesTest.check("configure matching enabled once a plan is accepted", WizardRules.actionGates(demo, true, demo.sources.get(0)).configureMatching.enabled);
		acceptedPlan.status = "APPLIED";
		WizardRulesTest.check("run matching enabled once a plan is applied", WizardRules.actionGates(demo, true, demo.sources.get(0)).runMatching.enabled);
	}

	// -------------------------------------------------------------------------------- target-mapping lot 3: feeds

	/**
	 * Target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): "Load the staging" refuses an accepted entity that
	 * has no accepted feed (or no feed at all), naming that entity, and accepts once one exists.
	 */
	private static void feedCoverageGatesLoadStaging()
	{
		final WizardSnapshot snapshot = WizardRulesTest.projectOnly();
		final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
		entity.id = "e1";
		entity.name = "Personne";
		entity.status = "ACCEPTED";
		snapshot.stagingEntities.add(entity);
		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_DATASET", "DONE", ""));

		final WizardRules.ActionGates noFeedAtAll = WizardRules.actionGates(snapshot, true, null);
		WizardRulesTest.check("no feed at all: load staging refused", !noFeedAtAll.loadStaging.enabled);
		WizardRulesTest.check("reason names the missing feed", "wizard.gate.needFeeds".equals(noFeedAtAll.loadStaging.reasonKey));
		WizardRulesTest.check("detail names the entity: " + noFeedAtAll.loadStaging.detail, noFeedAtAll.loadStaging.detail != null
			&& noFeedAtAll.loadStaging.detail.contains("Personne"));

		final WizardSnapshot.FeedInfo proposedFeed = new WizardSnapshot.FeedInfo();
		proposedFeed.id = "f1";
		proposedFeed.status = "PROPOSED";
		entity.feeds.add(proposedFeed);
		WizardRulesTest.check("a PROPOSED feed alone is still not enough", !WizardRules.actionGates(snapshot, true, null).loadStaging.enabled);

		proposedFeed.status = "ACCEPTED";
		WizardRulesTest.check("an accepted feed unblocks load staging", WizardRules.actionGates(snapshot, true, null).loadStaging.enabled);
	}

	/**
	 * Target-mapping lot 3: a child feed that is itself accepted still blocks "Load the staging" while its OWN
	 * parent feed (on a DIFFERENT entity's card) is not accepted - and unblocks once the parent feed is too.
	 */
	private static void feedCoverageRequiresParentFeedAcceptedToo()
	{
		final WizardSnapshot snapshot = WizardRulesTest.projectOnly();
		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_DATASET", "DONE", ""));

		final WizardSnapshot.StagingEntityInfo parent = new WizardSnapshot.StagingEntityInfo();
		parent.id = "e1";
		parent.name = "Personne";
		parent.status = "ACCEPTED";
		// an ACCEPTED feed of its own, so the entity-level "has an accepted feed" check (feedCoverageGatesLoadStaging)
		// is satisfied on its own - this test isolates the SECOND rule, "a child feed's own referenced parent feed
		// must be accepted", never a re-run of the first one against the same entity.
		final WizardSnapshot.FeedInfo otherFeed = new WizardSnapshot.FeedInfo();
		otherFeed.id = "f0";
		otherFeed.status = "ACCEPTED";
		parent.feeds.add(otherFeed);
		final WizardSnapshot.FeedInfo parentFeed = new WizardSnapshot.FeedInfo();
		parentFeed.id = "f1";
		parentFeed.status = "PROPOSED"; // not yet accepted - this is the one the child feed below names as its parent
		parent.feeds.add(parentFeed);
		snapshot.stagingEntities.add(parent);

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
		snapshot.stagingEntities.add(child);

		final WizardRules.ActionGates blocked = WizardRules.actionGates(snapshot, true, null);
		WizardRulesTest.check("child feed accepted but its parent feed is not: load staging refused", !blocked.loadStaging.enabled);
		WizardRulesTest.check("reason names the missing feed coverage", "wizard.gate.needFeeds".equals(blocked.loadStaging.reasonKey));
		WizardRulesTest.check("detail names the child entity and its parent-feed problem: " + blocked.loadStaging.detail,
			blocked.loadStaging.detail != null && blocked.loadStaging.detail.contains("Adresse"));

		parentFeed.status = "ACCEPTED";
		WizardRulesTest.check("once the parent feed is accepted too, load staging is enabled", WizardRules.actionGates(snapshot, true, null).loadStaging.enabled);
	}

	// ---------------------------------------------------------------------------------------- lot C1-B: step list per style

	private static void stepListsFollowTheStyle()
	{
		final int[] consolidation = { 1, 2, 3, 4, 5, 6, 7, 8 };
		WizardRulesTest.check("CONSOLIDATION keeps its 8 steps, unchanged", java.util.Arrays.equals(consolidation, WizardRules.steps("CONSOLIDATION")));
		WizardRulesTest.check("an empty, null or unknown style shows the 8 steps too", java.util.Arrays.equals(consolidation, WizardRules.steps((String) null))
			&& java.util.Arrays.equals(consolidation, WizardRules.steps("")) && java.util.Arrays.equals(consolidation, WizardRules.steps("COEXISTENCE")));
		final int[] centralisation = WizardRules.steps("CENTRALISATION");
		WizardRulesTest.check("CENTRALISATION has 6 steps", centralisation.length == 6);
		WizardRulesTest.check("PROJECT, PACK, SPECIFICATION, PROPOSAL, GENERATION, SUMMARY in that order",
			java.util.Arrays.equals(new int[] { WizardRules.STEP_PROJECT, WizardRules.STEP_PACK, WizardRules.STEP_SPECIFICATION, WizardRules.STEP_PROPOSAL, WizardRules.STEP_GENERATION,
				WizardRules.STEP_SUMMARY }, centralisation));
		WizardRulesTest.check("steps() hands out a copy", WizardRules.steps("CENTRALISATION") != centralisation);
		WizardRulesTest.check("positionOf is the 1-based place in the style's list", WizardRules.positionOf("CENTRALISATION", WizardRules.STEP_PROPOSAL) == 4
			&& WizardRules.positionOf("CENTRALISATION", WizardRules.STEP_SUMMARY) == 6 && WizardRules.positionOf("CONSOLIDATION", WizardRules.STEP_SUMMARY) == 8);
		WizardRulesTest.check("a step the style does not list has position 0", WizardRules.positionOf("CENTRALISATION", WizardRules.STEP_LANDING) == 0
			&& WizardRules.positionOf("CONSOLIDATION", WizardRules.STEP_PACK) == 0);
		final WizardSnapshot snapshot = WizardRulesTest.projectOnly();
		snapshot.project.style = "CENTRALISATION";
		WizardRulesTest.check("the snapshot's project style picks the list", WizardRules.steps(snapshot).length == 6 && WizardRules.steps(new WizardSnapshot()).length == 8);
	}

	private static WizardSnapshot centralisation()
	{
		final WizardSnapshot snapshot = WizardRulesTest.projectOnly();
		snapshot.project.style = "CENTRALISATION";
		return snapshot;
	}

	private static WizardSnapshot.DifferenceInfo difference(final String pNature, final String pStatus)
	{
		final WizardSnapshot.DifferenceInfo row = new WizardSnapshot.DifferenceInfo();
		row.id = String.valueOf(pNature.hashCode() % 1000 + pStatus.hashCode() % 1000);
		row.nature = pNature;
		row.status = pStatus;
		return row;
	}

	private static void centralisationStepStatesAndResume()
	{
		final WizardSnapshot snapshot = WizardRulesTest.centralisation();
		WizardRules.StepState[] states = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("the array covers every step id", states.length == WizardRules.STEP_MAX + 1);
		WizardRulesTest.check("pack is reachable once the project exists, not completed", states[WizardRules.STEP_PACK].reachable && !states[WizardRules.STEP_PACK].completed);
		WizardRulesTest.check("specification is blocked without a pack, naming it", !states[WizardRules.STEP_SPECIFICATION].reachable
			&& "wizard.blocked.needPack".equals(states[WizardRules.STEP_SPECIFICATION].blockedKey));
		WizardRulesTest.check("proposal is blocked until a proposal was started", !states[WizardRules.STEP_PROPOSAL].reachable && "wizard.blocked.needProposal".equals(states[WizardRules.STEP_PROPOSAL].blockedKey));
		WizardRulesTest.check("generation is blocked until every line is decided", !states[WizardRules.STEP_GENERATION].reachable && "wizard.blocked.needDecisions".equals(states[WizardRules.STEP_GENERATION].blockedKey));
		WizardRulesTest.check("summary is always reachable", states[WizardRules.STEP_SUMMARY].reachable);
		WizardRulesTest.check("resume on the Pack step without a pack", WizardRules.suggestedStep(snapshot) == WizardRules.STEP_PACK);

		snapshot.projectPacks.add(new WizardSnapshot.ProjectPackInfo());
		states = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("a pack chosen completes Pack and opens Specification", states[WizardRules.STEP_PACK].completed && states[WizardRules.STEP_SPECIFICATION].reachable);
		WizardRulesTest.check("resume on Specification once a pack is chosen", WizardRules.suggestedStep(snapshot) == WizardRules.STEP_SPECIFICATION);

		snapshot.accelJob = new WizardSnapshot.AccelJobInfo();
		states = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("a running proposal job opens Proposal", states[WizardRules.STEP_PROPOSAL].reachable && WizardRules.suggestedStep(snapshot) == WizardRules.STEP_PROPOSAL);
		snapshot.accelJob = null;

		snapshot.differences.add(WizardRulesTest.difference("ADD", "PROPOSED"));
		snapshot.differences.add(WizardRulesTest.difference("KEEP", "PROPOSED"));
		states = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("a difference on file opens Proposal, not completed while one is PROPOSED", states[WizardRules.STEP_PROPOSAL].reachable && !states[WizardRules.STEP_PROPOSAL].completed);
		WizardRulesTest.check("a KEEP line never counts as left to decide", WizardRules.differencesToDecide(snapshot) == 1);
		WizardRulesTest.check("generation still blocked", !states[WizardRules.STEP_GENERATION].reachable);
		WizardRulesTest.check("resume on Proposal while a line is open", WizardRules.suggestedStep(snapshot) == WizardRules.STEP_PROPOSAL);

		snapshot.differences.get(0).status = "ACCEPTED";
		states = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("every line decided completes Proposal and opens Generation", states[WizardRules.STEP_PROPOSAL].completed && states[WizardRules.STEP_GENERATION].reachable
			&& !states[WizardRules.STEP_GENERATION].completed);
		WizardRulesTest.check("resume on Generation", WizardRules.suggestedStep(snapshot) == WizardRules.STEP_GENERATION);

		snapshot.project.domainModel = "Suppliers_Master";
		states = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("a generated model completes Generation", states[WizardRules.STEP_GENERATION].completed);
		WizardRulesTest.check("resume on the Summary once generated", WizardRules.suggestedStep(snapshot) == WizardRules.STEP_SUMMARY);

		// the CONSOLIDATION states are unchanged by the new entries
		final WizardRules.StepState[] consolidation = WizardRules.stepStates(WizardRulesTest.projectOnly());
		WizardRulesTest.check("a consolidation project still resumes on Sources", WizardRules.suggestedStep(WizardRulesTest.projectOnly()) == WizardRules.STEP_SOURCES && consolidation[WizardRules.STEP_SOURCES].reachable);
	}

	/** "Skip the packs, start blank": no pack, the need alone opens Specification and the proposal (the model proposes everything). */
	private static void centralisationBlankStart()
	{
		final WizardSnapshot snapshot = WizardRulesTest.centralisation();
		snapshot.accelBlankStart = true;
		snapshot.llmConfigured = true;
		final WizardRules.StepState[] states = WizardRules.stepStates(snapshot);
		WizardRulesTest.check("a blank start completes Pack and opens Specification without any pack", states[WizardRules.STEP_PACK].completed
			&& states[WizardRules.STEP_SPECIFICATION].reachable);
		WizardRulesTest.check("a blank start resumes on Specification", WizardRules.suggestedStep(snapshot) == WizardRules.STEP_SPECIFICATION);
		WizardRulesTest.check("a blank start may run the proposal", WizardRules.actionGates(snapshot, true, null).accelRunProposal.enabled);
	}

	private static void centralisationGates()
	{
		final WizardSnapshot snapshot = WizardRulesTest.centralisation();
		WizardRules.ActionGates gates = WizardRules.actionGates(snapshot, true, null);
		WizardRulesTest.check("suggesting packs needs the project only", gates.accelSuggestPacks.enabled);
		WizardRulesTest.check("the proposal needs a pack", !gates.accelRunProposal.enabled && "wizard.blocked.needPack".equals(gates.accelRunProposal.reasonKey));
		WizardRulesTest.check("the generation needs a proposal", !gates.accelGenerate.enabled && "wizard.blocked.needProposal".equals(gates.accelGenerate.reasonKey));
		snapshot.projectPacks.add(new WizardSnapshot.ProjectPackInfo());
		gates = WizardRules.actionGates(snapshot, true, null);
		WizardRulesTest.check("the proposal needs a language model", !gates.accelRunProposal.enabled && "wizard.gate.noLlm".equals(gates.accelRunProposal.reasonKey));
		snapshot.llmConfigured = true;
		gates = WizardRules.actionGates(snapshot, true, null);
		WizardRulesTest.check("the proposal is enabled with a pack and a model", gates.accelRunProposal.enabled);
		WizardRulesTest.check("and refused to a non-designer", !WizardRules.actionGates(snapshot, false, null).accelRunProposal.enabled
			&& "wizard.gate.adminOnly".equals(WizardRules.actionGates(snapshot, false, null).accelRunProposal.reasonKey));
		snapshot.differences.add(WizardRulesTest.difference("ADD", "PROPOSED"));
		gates = WizardRules.actionGates(snapshot, true, null);
		WizardRulesTest.check("generation waits for the decisions", !gates.accelGenerate.enabled && "wizard.blocked.needDecisions".equals(gates.accelGenerate.reasonKey));
		snapshot.differences.get(0).status = "REJECTED";
		WizardRulesTest.check("generation opens once nothing is PROPOSED", WizardRules.actionGates(snapshot, true, null).accelGenerate.enabled);
		snapshot.accelJob = new WizardSnapshot.AccelJobInfo();
		WizardRulesTest.check("neither runs while a task of the path is on", !WizardRules.actionGates(snapshot, true, null).accelGenerate.enabled
			&& !WizardRules.actionGates(snapshot, true, null).accelRunProposal.enabled);
	}

	// ---------------------------------------------------------------------------------------- implementation style

	private static void onlyConsolidationStyleIsSelectable()
	{
		WizardRulesTest.check("exactly four styles", WizardRules.IMPLEMENTATION_STYLES.size() == 4);
		int selectable = 0;
		for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			if (style.selectable)
			{
				selectable++;
				WizardRulesTest.check("the selectable one is Consolidation (Centralisation, built by lot C1-B, is coming soon since 2026-11-10)", "CONSOLIDATION".equals(style.code));
			}
		}
		WizardRulesTest.check("one style is selectable", selectable == 1);
		WizardRulesTest.check("Centralisation is the one coming-soon style", WizardRules.styleByCode("CENTRALISATION").comingSoon
			&& !WizardRules.styleByCode("REGISTRY").comingSoon && !WizardRules.styleByCode("COEXISTENCE").comingSoon && !WizardRules.styleByCode("CONSOLIDATION").comingSoon);

		// axis order: sources-only (Registry) .. hub-only (Centralisation) - the redesign's whole point
		WizardRulesTest.check("axis order is Registry, Consolidation, Coexistence, Centralisation",
			"REGISTRY".equals(WizardRules.IMPLEMENTATION_STYLES.get(0).code) && "CONSOLIDATION".equals(WizardRules.IMPLEMENTATION_STYLES.get(1).code)
				&& "COEXISTENCE".equals(WizardRules.IMPLEMENTATION_STYLES.get(2).code) && "CENTRALISATION".equals(WizardRules.IMPLEMENTATION_STYLES.get(3).code));

		final WizardRules.ImplementationStyle registry = WizardRules.styleByCode("REGISTRY");
		WizardRulesTest.check("Registry authors in the sources", registry.authoring == WizardRules.Authoring.SOURCES);
		WizardRulesTest.check("Registry stores no golden attributes", registry.goldenRecord == WizardRules.GoldenRecord.NONE);
		final WizardRules.ImplementationStyle consolidation = WizardRules.styleByCode("CONSOLIDATION");
		WizardRulesTest.check("Consolidation authors in the sources", consolidation.authoring == WizardRules.Authoring.SOURCES);
		WizardRulesTest.check("Consolidation's golden record is read-only", consolidation.goldenRecord == WizardRules.GoldenRecord.READ_ONLY);
		WizardRulesTest.check("Consolidation flows one way in", consolidation.flow == WizardRules.Flow.IN_ONLY);
		final WizardRules.ImplementationStyle coexistence = WizardRules.styleByCode("COEXISTENCE");
		WizardRulesTest.check("Coexistence authors in both places", coexistence.authoring == WizardRules.Authoring.BOTH);
		WizardRulesTest.check("Coexistence flows both ways", coexistence.flow == WizardRules.Flow.BOTH);
		final WizardRules.ImplementationStyle centralisation = WizardRules.styleByCode("CENTRALISATION");
		WizardRulesTest.check("Centralisation authors only in the hub", centralisation.authoring == WizardRules.Authoring.HUB);
		WizardRulesTest.check("Centralisation flows out only (sources subscribe)", centralisation.flow == WizardRules.Flow.OUT_ONLY);

		WizardRulesTest.check("styleByCode falls back to Consolidation for an unknown code", "CONSOLIDATION".equals(WizardRules.styleByCode("NOT_A_REAL_STYLE").code));
		WizardRulesTest.check("styleByCode resolves a known code exactly", "REGISTRY".equals(WizardRules.styleByCode("REGISTRY").code));

		// every style carries a distinct, non-empty "when" and "consequence" key, in every case (product owner:
		// the consequence sentence is computed from whichever style is selected, not hard-coded to Consolidation)
		final java.util.Set<String> whenKeys = new java.util.HashSet<String>();
		final java.util.Set<String> headlineKeys = new java.util.HashSet<String>();
		for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			WizardRulesTest.check(style.code + " has a when key", style.whenKey != null && !style.whenKey.isEmpty());
			WizardRulesTest.check(style.code + " has a consequence key", style.consequenceKey != null && !style.consequenceKey.isEmpty());
			WizardRulesTest.check(style.code + " has a headline key", style.headlineKey != null && !style.headlineKey.isEmpty());
			whenKeys.add(style.whenKey);
			headlineKeys.add(style.headlineKey);
		}
		WizardRulesTest.check("every style has its own distinct 'when' key", whenKeys.size() == WizardRules.IMPLEMENTATION_STYLES.size());
		WizardRulesTest.check("every style has its own distinct headline key", headlineKeys.size() == WizardRules.IMPLEMENTATION_STYLES.size());
	}

	/** Product owner correction (2026-09-21): the axis stays as designed, but Consolidation - the only buildable style - must be pre-selected. */
	private static void defaultStyleIsConsolidation()
	{
		WizardRulesTest.check("the default style is Consolidation", "CONSOLIDATION".equals(WizardRules.defaultStyle().code));
		WizardRulesTest.check("the default style is also the selectable one", WizardRules.defaultStyle().selectable);
		int selectable = 0;
		for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			selectable += style.selectable ? 1 : 0;
			WizardRulesTest.check(style.code + " is never both selectable and coming soon", !(style.selectable && style.comingSoon));
		}
		WizardRulesTest.check("Consolidation is the only selectable style (2026-11-10)", selectable == 1);
	}

	// -------------------------------------------------------------------------------------------- entity generation hint

	private static void entityHintNoneWhenNotAcceptedLike()
	{
		final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
		entity.status = "PROPOSED";
		WizardRulesTest.check("a PROPOSED entity never gets the waiting hint (nothing to generate yet)", !WizardRules.entityHint(entity).waitingForAttribute);
	}

	private static void entityHintNoneWhenABusinessAttributeIsAccepted()
	{
		final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
		entity.status = "ACCEPTED";
		entity.attributes.add(WizardRulesTest.attribute(false, "ACCEPTED"));
		WizardRulesTest.check("an accepted entity with an accepted business attribute has no hint", !WizardRules.entityHint(entity).waitingForAttribute);
	}

	/** The exact case the product owner named: entities accepted, one attribute accepted, but the technical key alone never unblocks generation (StagingModel/TechnicalKeyRule). */
	private static void entityHintFiresWhenOnlyTheTechnicalKeyIsAccepted()
	{
		final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
		entity.name = "Account";
		entity.status = "ACCEPTED";
		entity.attributes.add(WizardRulesTest.attribute(true, "ACCEPTED")); // sourceSystem, cascaded
		entity.attributes.add(WizardRulesTest.attribute(true, "ACCEPTED")); // sourceId, cascaded
		entity.attributes.add(WizardRulesTest.attribute(false, "PROPOSED"));
		entity.attributes.add(WizardRulesTest.attribute(false, "REJECTED"));
		final WizardRules.EntityHint hint = WizardRules.entityHint(entity);
		WizardRulesTest.check("the technical key alone does not unblock generation: the hint fires", hint.waitingForAttribute);
		WizardRulesTest.check("only PROPOSED non-key attributes count as waiting (REJECTED does not)", hint.waitingCount == 1);
	}

	private static void entityHintCountsOnlyProposedNonKeyAttributes()
	{
		final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
		entity.status = "APPLIED";
		entity.attributes.add(WizardRulesTest.attribute(true, "APPLIED"));
		entity.attributes.add(WizardRulesTest.attribute(false, "PROPOSED"));
		entity.attributes.add(WizardRulesTest.attribute(false, "PROPOSED"));
		entity.attributes.add(WizardRulesTest.attribute(false, "PROPOSED"));
		final WizardRules.EntityHint hint = WizardRules.entityHint(entity);
		WizardRulesTest.check("an APPLIED entity (accepted-like) with only PROPOSED business attributes still gets the hint", hint.waitingForAttribute);
		WizardRulesTest.check("waiting count matches the number of PROPOSED non-key attributes", hint.waitingCount == 3);
	}

	private static WizardSnapshot.AttributeInfo attribute(final boolean pPrimaryKey, final String pStatus)
	{
		final WizardSnapshot.AttributeInfo attribute = new WizardSnapshot.AttributeInfo();
		attribute.primaryKey = pPrimaryKey;
		attribute.status = pStatus;
		return attribute;
	}

	/** Block-accept lot (2026-09-23): {@link WizardRules#hasDecidableProposals} checks the entity itself, its non-key attributes, their mappings, and the inline proposal lines grouped under it - false only when every one of those is done deciding. */
	private static void hasDecidableProposalsReflectsEveryLevel()
	{
		final WizardSnapshot.StagingEntityInfo doneEntity = new WizardSnapshot.StagingEntityInfo();
		doneEntity.status = "APPLIED";
		doneEntity.attributes.add(WizardRulesTest.attribute(true, "APPLIED"));
		doneEntity.attributes.add(WizardRulesTest.attribute(false, "ACCEPTED"));
		WizardRulesTest.check("nothing left to decide anywhere in the entity", !WizardRules.hasDecidableProposals(doneEntity));

		final WizardSnapshot.StagingEntityInfo proposedEntity = new WizardSnapshot.StagingEntityInfo();
		proposedEntity.status = "PROPOSED";
		WizardRulesTest.check("the entity itself still PROPOSED makes it decidable", WizardRules.hasDecidableProposals(proposedEntity));

		final WizardSnapshot.StagingEntityInfo mappingOnly = new WizardSnapshot.StagingEntityInfo();
		mappingOnly.status = "ACCEPTED";
		final WizardSnapshot.AttributeInfo acceptedAttribute = WizardRulesTest.attribute(false, "ACCEPTED");
		final WizardSnapshot.MappingInfo mapping = new WizardSnapshot.MappingInfo();
		mapping.status = "PROPOSED";
		acceptedAttribute.mappings.add(mapping);
		mappingOnly.attributes.add(acceptedAttribute);
		WizardRulesTest.check("a still-PROPOSED mapping alone makes the entity decidable", WizardRules.hasDecidableProposals(mappingOnly));

		final WizardSnapshot.StagingEntityInfo proposalLineOnly = new WizardSnapshot.StagingEntityInfo();
		proposalLineOnly.status = "ACCEPTED";
		final com.on.ebx.factory.review.ReviewLine qualityLine = new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.QUALITY, "q1",
			"Entity.field", "Pattern check", "RULE", Integer.valueOf(80), "", com.on.ebx.factory.review.ReviewLine.PROPOSED, false, false);
		proposalLineOnly.proposalLines.add(new WizardSnapshot.ProposalLineInfo(qualityLine));
		WizardRulesTest.check("a still-PROPOSED inline proposal line alone makes the entity decidable", WizardRules.hasDecidableProposals(proposalLineOnly));
	}

	/**
	 * Quality-block-bulk lot (2026-09-23): {@link WizardRules#hasDecidableQualityProposals} only looks at
	 * {@code MAPPING_STEP}/{@code QUALITY} proposal lines - a still-PROPOSED {@code SCRIPT_CONSTRAINT} line
	 * must NOT make it true (that one is only counted by the broader {@link WizardRules#hasDecidableProposals}).
	 */
	private static void hasDecidableQualityProposalsOnlyCountsMappingStepAndQuality()
	{
		final WizardSnapshot.StagingEntityInfo scriptConstraintOnly = new WizardSnapshot.StagingEntityInfo();
		scriptConstraintOnly.status = "ACCEPTED";
		final com.on.ebx.factory.review.ReviewLine scriptLine = new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.SCRIPT_CONSTRAINT, "c1",
			"Entity", "Check digit", "LLM", Integer.valueOf(90), "", com.on.ebx.factory.review.ReviewLine.PROPOSED, false, false);
		scriptConstraintOnly.proposalLines.add(new WizardSnapshot.ProposalLineInfo(scriptLine));
		WizardRulesTest.check("a lone PROPOSED SCRIPT_CONSTRAINT line is not a quality proposal",
			!WizardRules.hasDecidableQualityProposals(scriptConstraintOnly));
		WizardRulesTest.check("but it does count for the broader hasDecidableProposals", WizardRules.hasDecidableProposals(scriptConstraintOnly));

		final WizardSnapshot.StagingEntityInfo qualityLineEntity = new WizardSnapshot.StagingEntityInfo();
		qualityLineEntity.status = "ACCEPTED";
		final com.on.ebx.factory.review.ReviewLine qualityLine = new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.QUALITY, "q1",
			"Entity.field", "Pattern check", "RULE", Integer.valueOf(80), "", com.on.ebx.factory.review.ReviewLine.PROPOSED, false, false);
		qualityLineEntity.proposalLines.add(new WizardSnapshot.ProposalLineInfo(qualityLine));
		WizardRulesTest.check("a still-PROPOSED QUALITY line makes it a decidable quality block", WizardRules.hasDecidableQualityProposals(qualityLineEntity));

		final com.on.ebx.factory.review.ReviewLine mappingStepLine = new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.MAPPING_STEP, "m1",
			"Entity.field", "Trim", "RULE", Integer.valueOf(70), "", com.on.ebx.factory.review.ReviewLine.PROPOSED, false, false);
		final WizardSnapshot.StagingEntityInfo mappingStepEntity = new WizardSnapshot.StagingEntityInfo();
		mappingStepEntity.status = "ACCEPTED";
		mappingStepEntity.proposalLines.add(new WizardSnapshot.ProposalLineInfo(mappingStepLine));
		WizardRulesTest.check("a still-PROPOSED MAPPING_STEP line makes it a decidable quality block too",
			WizardRules.hasDecidableQualityProposals(mappingStepEntity));
	}

	/**
	 * Threshold-left-out lot (2026-09-23, coordinator finding on project Personnes): {@link
	 * WizardRules#bulkEligibility} is the pure counting helper behind the "N decided, M refused, K left out"
	 * notice - MappingStep id 22 (confidence 70, threshold 80) is exactly the reported case: PROPOSED, below
	 * the threshold, checkbox unticked -&gt; left out, not silently dropped and not eligible either.
	 */
	private static void bulkEligibilityClassifiesEveryRow()
	{
		final WizardRules.BulkEligibility notProposed = WizardRules.bulkEligibility("ACCEPTED", Integer.valueOf(90), 80, false);
		WizardRulesTest.check("an already-decided row is never eligible", !notProposed.eligible);
		WizardRulesTest.check("an already-decided row is never left out either - nothing to do with it", !notProposed.leftOutBelowThreshold);

		final WizardRules.BulkEligibility atThreshold = WizardRules.bulkEligibility("PROPOSED", Integer.valueOf(80), 80, false);
		WizardRulesTest.check("exactly at the threshold is eligible", atThreshold.eligible);
		WizardRulesTest.check("exactly at the threshold is not left out", !atThreshold.leftOutBelowThreshold);

		final WizardRules.BulkEligibility belowThreshold = WizardRules.bulkEligibility("PROPOSED", Integer.valueOf(70), 80, false);
		WizardRulesTest.check("MappingStep 22 at 70% against an 80% threshold, checkbox unticked, is not eligible", !belowThreshold.eligible);
		WizardRulesTest.check("...and is counted as left out", belowThreshold.leftOutBelowThreshold);

		final WizardRules.BulkEligibility belowThresholdIncluded = WizardRules.bulkEligibility("PROPOSED", Integer.valueOf(70), 80, true);
		WizardRulesTest.check("the same row with the checkbox ticked becomes eligible", belowThresholdIncluded.eligible);
		WizardRulesTest.check("...and is no longer counted as left out", !belowThresholdIncluded.leftOutBelowThreshold);

		final WizardRules.BulkEligibility nullConfidence = WizardRules.bulkEligibility("PROPOSED", null, 80, false);
		WizardRulesTest.check("a null confidence reads as 0 and is left out against any positive threshold", nullConfidence.leftOutBelowThreshold);
	}

	/**
	 * Apply-visibility lot (2026-09-23, coordinator finding on project Personnes): {@link
	 * WizardRules#capabilityApplyGate} checks {@code MASTER_DATASET} for zone {@code MASTER} and {@code
	 * STAGING_DATASET} for zone {@code STAGING} (or anything else, defensively) - ids 10-14, zone MASTER,
	 * stuck ACCEPTED because {@code MASTER_DATASET} never completed, is exactly this gate's reason to exist.
	 */
	private static void capabilityApplyGateChecksTheRightZoneStep()
	{
		final WizardSnapshot noStepsDone = new WizardSnapshot();
		WizardRulesTest.check("MASTER zone blocked when MASTER_DATASET never ran", !WizardRules.capabilityApplyGate(noStepsDone, "MASTER").enabled);
		WizardRulesTest.check("blocked MASTER names the right reason key",
			"wizard.gate.needMasterModelForApply".equals(WizardRules.capabilityApplyGate(noStepsDone, "MASTER").reasonKey));
		WizardRulesTest.check("STAGING zone blocked when STAGING_DATASET never ran", !WizardRules.capabilityApplyGate(noStepsDone, "STAGING").enabled);
		WizardRulesTest.check("blocked STAGING names the right reason key",
			"wizard.gate.needStagingModelForApply".equals(WizardRules.capabilityApplyGate(noStepsDone, "STAGING").reasonKey));

		final WizardSnapshot masterDone = new WizardSnapshot();
		masterDone.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MASTER_DATASET", "DONE", ""));
		WizardRulesTest.check("MASTER zone enabled once MASTER_DATASET is DONE", WizardRules.capabilityApplyGate(masterDone, "MASTER").enabled);
		WizardRulesTest.check("STAGING zone is NOT unlocked by MASTER_DATASET alone", !WizardRules.capabilityApplyGate(masterDone, "STAGING").enabled);

		final WizardSnapshot stagingDone = new WizardSnapshot();
		stagingDone.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_DATASET", "DONE", ""));
		WizardRulesTest.check("STAGING zone enabled once STAGING_DATASET is DONE", WizardRules.capabilityApplyGate(stagingDone, "STAGING").enabled);
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static WizardSnapshot projectOnly()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.id = "Companies";
		project.code = "Companies";
		project.label = "Companies 360";
		project.mode = "GREENFIELD";
		snapshot.project = project;
		return snapshot;
	}

	/** Project Companies, sources ERP and CRM loaded, 2 relations proposed, 3 entities proposed - the real demo state named in the task brief. */
	private static WizardSnapshot demoSnapshot()
	{
		final WizardSnapshot snapshot = WizardRulesTest.projectOnly();

		final WizardSnapshot.SourceInfo erp = new WizardSnapshot.SourceInfo();
		erp.id = "1";
		erp.sourceSystem = "ERP";
		erp.status = "LOADED";
		final WizardSnapshot.TableInfo supplier = new WizardSnapshot.TableInfo();
		supplier.id = "10";
		supplier.tableName = "SUPPLIER";
		supplier.status = "LOADED";
		erp.tables.add(supplier);
		snapshot.sources.add(erp);

		final WizardSnapshot.SourceInfo crm = new WizardSnapshot.SourceInfo();
		crm.id = "2";
		crm.sourceSystem = "CRM";
		crm.status = "LOADED";
		final WizardSnapshot.TableInfo account = new WizardSnapshot.TableInfo();
		account.id = "11";
		account.tableName = "ACCOUNT";
		account.status = "LOADED";
		final WizardSnapshot.TableInfo contact = new WizardSnapshot.TableInfo();
		contact.id = "12";
		contact.tableName = "CONTACT";
		contact.status = "LOADED";
		crm.tables.add(account);
		crm.tables.add(contact);
		final WizardSnapshot.RelationInfo r1 = new WizardSnapshot.RelationInfo();
		r1.id = "100";
		r1.fromTable = "CONTACT";
		r1.toTable = "ACCOUNT";
		r1.origin = "RULE";
		r1.confidence = Integer.valueOf(80);
		r1.status = "ACCEPTED";
		final WizardSnapshot.RelationInfo r2 = new WizardSnapshot.RelationInfo();
		r2.id = "101";
		r2.fromTable = "ACCOUNT";
		r2.toTable = "ACCOUNT";
		r2.selfReference = true;
		r2.origin = "RULE";
		r2.confidence = Integer.valueOf(65);
		r2.status = "ACCEPTED";
		crm.relations.add(r1);
		crm.relations.add(r2);
		snapshot.sources.add(crm);

		for (final String name : new String[] { "Supplier", "Account", "Contact" })
		{
			final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
			entity.id = name;
			entity.name = name;
			entity.origin = "RULE";
			entity.status = "PROPOSED";
			snapshot.stagingEntities.add(entity);
		}
		// target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): every entity gets one root, ACCEPTED-eligible
		// feed - "load staging enabled once the staging model is generated" (pipelineButtonGatesReflectPrerequisites)
		// accepts the "Supplier" entity but never touches its feed, so the feed itself must already read ACCEPTED
		// here for that gate to stay green; feedCoverageGatesLoadStaging below exercises the missing-feed and
		// unaccepted-parent-feed cases on their own, dedicated fixtures.
		for (final WizardSnapshot.StagingEntityInfo entity : snapshot.stagingEntities)
		{
			final WizardSnapshot.FeedInfo feed = new WizardSnapshot.FeedInfo();
			feed.id = "feed-" + entity.id;
			feed.landingTableName = entity.id.toUpperCase(java.util.Locale.ROOT);
			feed.sourceSystem = "ERP";
			feed.origin = "RULE";
			feed.status = "ACCEPTED";
			entity.feeds.add(feed);
		}
		return snapshot;
	}

	/** Deduced-scan lot (2026-09-24): {@link WizardRules#hasUnconfirmedDeducedTable}. */
	private static void hasUnconfirmedDeducedTable()
	{
		WizardRulesTest.check("no ticked table at all: none pending", !WizardRules.hasUnconfirmedDeducedTable(Collections.<String>emptyList()));
		WizardRulesTest.check("null list: none pending", !WizardRules.hasUnconfirmedDeducedTable(null));
		WizardRulesTest.check("every ticked table CONFIRMED: none pending",
			!WizardRules.hasUnconfirmedDeducedTable(Arrays.asList("CONFIRMED", "CONFIRMED")));
		WizardRulesTest.check("legacy/blank scanState (an OFFICIAL table): none pending", !WizardRules.hasUnconfirmedDeducedTable(Arrays.asList("")));
		WizardRulesTest.check("LEFT_OUT is not pending (it is no longer part of the landing)",
			!WizardRules.hasUnconfirmedDeducedTable(Arrays.asList("LEFT_OUT")));
		WizardRulesTest.check("one TO_CONFIRM among several confirmed ones is pending",
			WizardRules.hasUnconfirmedDeducedTable(Arrays.asList("CONFIRMED", "TO_CONFIRM")));
		WizardRulesTest.check("a single TO_CONFIRM table is pending", WizardRules.hasUnconfirmedDeducedTable(Arrays.asList("TO_CONFIRM")));
	}

	/** Fix lot (2026-09-26, defect 12): {@link WizardRules#addMoreApplicationsOpen}. */
	private static void addMoreApplicationsOpen()
	{
		WizardRulesTest.check("nothing at all: closed", !WizardRules.addMoreApplicationsOpen(false, "", false, "", false));
		WizardRulesTest.check("the person opened it: open", WizardRules.addMoreApplicationsOpen(true, "", false, "", false));
		WizardRulesTest.check("a connection is picked in the onboarding combobox: open", WizardRules.addMoreApplicationsOpen(false, "SF1", false, "", false));
		WizardRulesTest.check("a diagnosis already ran for the picked connection: open", WizardRules.addMoreApplicationsOpen(false, "", true, "", false));
		WizardRulesTest.check("a catalog source is picked: open", WizardRules.addMoreApplicationsOpen(false, "", false, "SNOW_DEMO", false));
		WizardRulesTest.check("a sample-files import report is showing: open", WizardRules.addMoreApplicationsOpen(false, "", false, "", true));
		WizardRulesTest.check("blank connector id (whitespace only) does not open it on its own",
			!WizardRules.addMoreApplicationsOpen(false, "   ", false, "", false));
		WizardRulesTest.check("null connector/catalog source ids never throw", !WizardRules.addMoreApplicationsOpen(false, null, false, null, false));
	}

	private static boolean hasField(final List<WizardRules.Problem> pProblems, final String pField)
	{
		for (final WizardRules.Problem problem : pProblems)
		{
			if (pField.equals(problem.field))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Staging-still-to-decide lot (2026-09-27): the real leftovers measured on project {@code ret} after the staging
	 * proposal - 2 fields whose source links are already accepted ({@code correspondenceLanguage} SAP only 82,
	 * {@code addressType} 88), 1 PATTERN rule on {@code correspondenceLanguage} (495 values, 0 failing), 4
	 * connector services: Melissa email/phone and OpenSanctions screen with a secret missing, and a Salesforce
	 * query whose binding still says {@code toBeProvided}. Everything else accepted. Package-private: {@code
	 * WizardPageTest} renders the same fixture.
	 */
	static WizardSnapshot retSnapshot()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.id = "77";
		project.code = "ret";
		project.label = "ret";
		project.mode = "GREENFIELD";
		snapshot.project = project;

		final WizardSnapshot.StagingEntityInfo personne = new WizardSnapshot.StagingEntityInfo();
		personne.id = "40";
		personne.name = "Personne";
		personne.status = "APPLIED";
		personne.attributes.add(WizardRulesTest.attribute("400", "sourceId", "ACCEPTED", true));
		personne.attributes.add(WizardRulesTest.attribute("401", "lastName", "ACCEPTED", false));
		final WizardSnapshot.AttributeInfo language = WizardRulesTest.attribute("402", "correspondenceLanguage", "PROPOSED", false);
		language.label = "Correspondence language";
		language.confidence = Integer.valueOf(82);
		language.rationale = "SAP carries a language code on every partner.";
		language.mappings.add(WizardRulesTest.mapping("m402", "ACCEPTED"));
		personne.attributes.add(language);
		final WizardSnapshot.FeedInfo sapFeed = new WizardSnapshot.FeedInfo();
		sapFeed.id = "f1";
		sapFeed.sourceSystem = "SAP";
		sapFeed.landingTableName = "BusinessPartner";
		sapFeed.status = "ACCEPTED";
		sapFeed.mappings.add(WizardRulesTest.feedMapping("m401", "NAME_LAST", "lastName", "ACCEPTED"));
		sapFeed.mappings.add(WizardRulesTest.feedMapping("m402", "CORRESPONDENCE_LANGUAGE", "correspondenceLanguage", "ACCEPTED"));
		personne.feeds.add(sapFeed);
		final WizardSnapshot.FeedInfo sfFeed = new WizardSnapshot.FeedInfo();
		sfFeed.id = "f2";
		sfFeed.sourceSystem = "SALESFORCE";
		sfFeed.landingTableName = "Contact";
		sfFeed.status = "ACCEPTED";
		sfFeed.mappings.add(WizardRulesTest.feedMapping("m403", "LastName", "lastName", "ACCEPTED"));
		personne.feeds.add(sfFeed);
		final com.on.ebx.factory.review.ReviewLine ruleLine = new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.QUALITY, "q9",
			"Personne.correspondenceLanguage", "MODEL_CONSTRAINT - Must match pattern: ^(?:[0-9]|[A-Z])$", "RULE", Integer.valueOf(90), "Every value is two capital letters.", "PROPOSED", false, false);
		final WizardSnapshot.ProposalLineInfo rule = new WizardSnapshot.ProposalLineInfo(ruleLine);
		rule.attributeId = "402";
		rule.valuesSeen = Long.valueOf(495);
		rule.valuesFailing = Long.valueOf(0);
		personne.proposalLines.add(rule);
		final com.on.ebx.factory.review.ReviewLine acceptedRuleLine = new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.QUALITY, "q8",
			"Personne.lastName", "MANDATORY", "RULE", Integer.valueOf(95), "", "ACCEPTED", false, false);
		final WizardSnapshot.ProposalLineInfo acceptedRule = new WizardSnapshot.ProposalLineInfo(acceptedRuleLine);
		acceptedRule.attributeId = "401";
		personne.proposalLines.add(acceptedRule);
		// stored as JSON, exactly as live on ret (polish lot, 2026-09-27)
		personne.capabilities.add(WizardRulesTest.service("c1", "MelissaEmail", "verifyEmail", false,
			"{\"error\":\"Connector 'Melissa' configuration is invalid: authentication: An authentication secret is required\"}", "{}"));
		personne.capabilities.add(WizardRulesTest.service("c2", "MelissaPhone", "verifyPhone", false, "connector secret missing: Melissa phone", "{}"));
		personne.capabilities.add(WizardRulesTest.service("c3", "OpenSanctions", "screen", false, "connector secret missing: OpenSanctions screen", "{}"));
		personne.capabilities.add(WizardRulesTest.service("c4", "Salesforce", "query", true, "", "{\"soql\":\"toBeProvided\"}"));
		snapshot.stagingEntities.add(personne);

		final WizardSnapshot.StagingEntityInfo adresse = new WizardSnapshot.StagingEntityInfo();
		adresse.id = "41";
		adresse.name = "Adresse";
		adresse.status = "ACCEPTED";
		adresse.attributes.add(WizardRulesTest.attribute("410", "sourceId", "ACCEPTED", true));
		final WizardSnapshot.AttributeInfo addressType = WizardRulesTest.attribute("411", "addressType", "PROPOSED", false);
		addressType.confidence = Integer.valueOf(88);
		addressType.mappings.add(WizardRulesTest.mapping("m411", "ACCEPTED"));
		adresse.attributes.add(addressType);
		snapshot.stagingEntities.add(adresse);
		return snapshot;
	}

	private static WizardSnapshot.AttributeInfo attribute(final String pId, final String pName, final String pStatus, final boolean pPrimaryKey)
	{
		final WizardSnapshot.AttributeInfo attribute = new WizardSnapshot.AttributeInfo();
		attribute.id = pId;
		attribute.name = pName;
		attribute.status = pStatus;
		attribute.primaryKey = pPrimaryKey;
		return attribute;
	}

	private static WizardSnapshot.MappingInfo mapping(final String pId, final String pStatus)
	{
		final WizardSnapshot.MappingInfo mapping = new WizardSnapshot.MappingInfo();
		mapping.id = pId;
		mapping.status = pStatus;
		return mapping;
	}

	private static WizardSnapshot.FeedMappingInfo feedMapping(final String pId, final String pColumn, final String pAttribute, final String pStatus)
	{
		final WizardSnapshot.FeedMappingInfo mapping = new WizardSnapshot.FeedMappingInfo();
		mapping.id = pId;
		mapping.landingColumn = pColumn;
		mapping.attributeName = pAttribute;
		mapping.status = pStatus;
		return mapping;
	}

	private static WizardSnapshot.CapabilityInfo service(final String pId, final String pConnector, final String pOperation, final boolean pReady, final String pDetail,
		final String pBinding)
	{
		final WizardSnapshot.CapabilityInfo info = new WizardSnapshot.CapabilityInfo();
		info.id = pId;
		info.attributeName = "";
		info.connectorId = pConnector;
		info.connectorOperation = pOperation;
		info.proposition = "Look into " + pOperation + " of " + pConnector;
		info.ready = pReady;
		info.readinessDetail = pDetail;
		info.binding = pBinding;
		info.pricing = "PAID";
		info.confidence = Integer.valueOf(70);
		info.status = "PROPOSED";
		return info;
	}

	private static void stillToDecideIsEmptyWhenEverythingIsDecided()
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		snapshot.stagingEntities.get(0).attributes.get(2).status = "ACCEPTED";
		snapshot.stagingEntities.get(1).attributes.get(1).status = "REJECTED";
		snapshot.stagingEntities.get(0).proposalLines.clear();
		for (final WizardSnapshot.CapabilityInfo capability : snapshot.stagingEntities.get(0).capabilities)
		{
			capability.status = "SET_ASIDE";
		}
		final StillToDecide still = StillToDecide.of(snapshot);
		WizardRulesTest.check("nothing left: total is 0", still.total() == 0);
		WizardRulesTest.check("nothing left: no service to set aside", still.servicesThatCannotRun() == 0 && still.idsOfServicesThatCannotRun().isEmpty());
		WizardRulesTest.check("an empty project has nothing left either", StillToDecide.of(new WizardSnapshot()).total() == 0);
	}

	private static void stillToDecideGroupsTheRetLeftoversByNature()
	{
		final StillToDecide still = StillToDecide.of(WizardRulesTest.retSnapshot());
		WizardRulesTest.check("ret: 7 decisions left", still.total() == 7);
		WizardRulesTest.check("ret: 2 fields", still.fields.size() == 2);
		WizardRulesTest.check("ret: 1 quality rule", still.rules.size() == 1);
		WizardRulesTest.check("ret: 4 checks and services", still.checks.size() == 4);
		WizardRulesTest.check("ret: no source link left (they are all accepted)", still.links.isEmpty());
		final StillToDecide.FieldItem language = still.fields.get(0);
		WizardRulesTest.check("field keeps its entity, name, label and confidence", "Personne".equals(language.entityName)
			&& "correspondenceLanguage".equals(language.name) && "Correspondence language".equals(language.label) && Integer.valueOf(82).equals(language.confidence));
		WizardRulesTest.check("field is fed by SAP only", language.sources.equals(Arrays.asList("SAP")));
		WizardRulesTest.check("its source links are already accepted", language.linksAccepted);
		WizardRulesTest.check("the second field belongs to Adresse", "Adresse".equals(still.fields.get(1).entityName) && "addressType".equals(still.fields.get(1).name));
		WizardRulesTest.check("tally: fields 1 accepted (lastName; technical keys excluded), 2 left", still.fieldTally.accepted == 1 && still.fieldTally.left == 2);
		WizardRulesTest.check("tally: rules 1 accepted, 1 left", still.ruleTally.accepted == 1 && still.ruleTally.left == 1);
		WizardRulesTest.check("tally: checks 0 accepted, 4 left", still.checkTally.accepted == 0 && still.checkTally.left == 4);
		WizardRulesTest.check("tally: links 3 accepted mappings + 2 accepted feeds, 0 left", still.linkTally.accepted == 5 && still.linkTally.left == 0);
	}

	private static void stillToDecideRuleOfAProposedFieldIsDecidedWithTheField()
	{
		final StillToDecide still = StillToDecide.of(WizardRulesTest.retSnapshot());
		final StillToDecide.RuleItem rule = still.rules.get(0);
		WizardRulesTest.check("the rule on a PROPOSED field is decided with it", "correspondenceLanguage".equals(rule.decidedWithField));
		WizardRulesTest.check("the rule carries its measured values", Long.valueOf(495).equals(rule.valuesSeen) && Long.valueOf(0).equals(rule.valuesFailing));
		WizardRulesTest.check("the field knows it carries one rule", still.fields.get(0).carriedRules == 1 && still.fields.get(1).carriedRules == 0);

		final WizardSnapshot accepted = WizardRulesTest.retSnapshot();
		accepted.stagingEntities.get(0).attributes.get(2).status = "ACCEPTED";
		final StillToDecide afterField = StillToDecide.of(accepted);
		WizardRulesTest.check("once the field is accepted elsewhere, the rule is decidable on its own", afterField.rules.size() == 1
			&& afterField.rules.get(0).decidedWithField.isEmpty());
	}

	private static void stillToDecideServiceReadinessChips()
	{
		final StillToDecide still = StillToDecide.of(WizardRulesTest.retSnapshot());
		int secretMissing = 0;
		int queryToWrite = 0;
		for (final StillToDecide.CheckItem item : still.checks)
		{
			WizardRulesTest.check("every ret check is a connector service", item.isService());
			secretMissing += item.secretMissing ? 1 : 0;
			queryToWrite += item.queryToWrite ? 1 : 0;
		}
		WizardRulesTest.check("3 services have a secret missing", secretMissing == 3);
		WizardRulesTest.check("1 service has a query to write", queryToWrite == 1);
		WizardRulesTest.check("all 4 cannot run as they stand", still.servicesThatCannotRun() == 4);
		WizardRulesTest.check("the set-all ids are the 4 rows", "c1,c2,c3,c4".equals(still.idsOfServicesThatCannotRun()));
		WizardRulesTest.check("toBeProvided detection", StillToDecide.queryToWrite("{\"q\":\"toBeProvided\"}") && !StillToDecide.queryToWrite("{\"q\":\"SELECT Id\"}")
			&& !StillToDecide.queryToWrite(null));
	}

	/** Lot M5 (2026-10-04): an ACCEPTED SCRIPT_DERIVATION is an open point to APPLY (its attribute stays empty until then); an APPLIED one is not, and a PROPOSED one is still a decision. */
	private static void stillToDecideListsAnAcceptedScriptDerivationAsToApply()
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		final int before = StillToDecide.of(snapshot).total();
		final com.on.ebx.factory.review.ReviewLine accepted = new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.SCRIPT_DERIVATION, "107",
			"Personne.phoneE164", "phoneE164 = toE164(phone)", "RULE", Integer.valueOf(90), "", "ACCEPTED", false, false);
		snapshot.stagingEntities.get(0).proposalLines.add(new WizardSnapshot.ProposalLineInfo(accepted));
		StillToDecide still = StillToDecide.of(snapshot);
		WizardRulesTest.check("an accepted, unapplied derivation is one more open point", still.total() == before + 1);
		WizardRulesTest.check("it is listed under toApply, with its id", still.toApply.size() == 1 && "107".equals(still.toApply.get(0).rowId)
			&& com.on.ebx.factory.review.ReviewLine.SCRIPT_DERIVATION.equals(still.toApply.get(0).nature));
		WizardRulesTest.check("it is not listed as a decision to take", still.checks.size() == 4);
		WizardRulesTest.check("it still counts as accepted in the tally", still.checkTally.accepted == 1);

		final WizardSnapshot applied = WizardRulesTest.retSnapshot();
		final com.on.ebx.factory.review.ReviewLine appliedLine = new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.SCRIPT_DERIVATION, "107",
			"Personne.phoneE164", "phoneE164 = toE164(phone)", "RULE", Integer.valueOf(90), "", "APPLIED", false, false);
		applied.stagingEntities.get(0).proposalLines.add(new WizardSnapshot.ProposalLineInfo(appliedLine));
		still = StillToDecide.of(applied);
		WizardRulesTest.check("an applied derivation is not an open point", still.toApply.isEmpty() && still.total() == before);

		final WizardSnapshot proposed = WizardRulesTest.retSnapshot();
		final com.on.ebx.factory.review.ReviewLine proposedLine = new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.SCRIPT_DERIVATION, "108",
			"Personne.phoneE164", "phoneE164 = toE164(phone)", "RULE", Integer.valueOf(90), "", "PROPOSED", false, false);
		proposed.stagingEntities.get(0).proposalLines.add(new WizardSnapshot.ProposalLineInfo(proposedLine));
		still = StillToDecide.of(proposed);
		WizardRulesTest.check("a proposed derivation is a decision, not an apply", still.toApply.isEmpty() && still.checks.size() == 5);
	}

	/** Lot P1 (2026-10-04): the parameters a person may settle ride on the toApply item; an APPLIED derivation counts only while the re-apply marker is on its applyMessage. */
	private static void stillToDecideCarriesTheParametersAndTheReapplyMark()
	{
		final String binding = "{\"value\":\"phone\",\"country\":{\"toBeProvided\":\"which country\"},\"mode\":{\"literal\":\"strict\"}}";
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		final int before = StillToDecide.of(snapshot).total();
		final WizardSnapshot.ProposalLineInfo accepted = new WizardSnapshot.ProposalLineInfo(new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.SCRIPT_DERIVATION, "107",
			"Personne.phoneE164", "phoneE164 = toE164(phone)", "LLM", Integer.valueOf(90), "", "ACCEPTED", false, false));
		accepted.binding = binding;
		snapshot.stagingEntities.get(0).proposalLines.add(accepted);
		final StillToDecide.CheckItem item = StillToDecide.of(snapshot).toApply.get(0);
		WizardRulesTest.check("two settable parameters in binding order", item.parameters.size() == 2 && "country".equals(item.parameters.get(0).name) && "mode".equals(item.parameters.get(1).name));
		WizardRulesTest.check("the to-be-provided one blocks apply: " + item.toBeProvidedRemaining(), item.toBeProvidedRemaining().equals(java.util.Collections.singletonList("country")));
		WizardRulesTest.check("an ACCEPTED derivation is not a re-apply", !item.reapplyNeeded);

		final WizardSnapshot stale = WizardRulesTest.retSnapshot();
		final WizardSnapshot.ProposalLineInfo applied = new WizardSnapshot.ProposalLineInfo(new com.on.ebx.factory.review.ReviewLine(com.on.ebx.factory.review.ReviewLine.SCRIPT_DERIVATION, "107",
			"Personne.phoneE164", "phoneE164 = toE164(phone)", "LLM", Integer.valueOf(90), "", "APPLIED", false, false));
		applied.binding = "{\"value\":\"phone\",\"country\":{\"literal\":\"FR\"}}";
		applied.applyMessage = com.on.ebx.factory.capability.DerivationParameters.REAPPLY_MARKER;
		stale.stagingEntities.get(0).proposalLines.add(applied);
		final StillToDecide still = StillToDecide.of(stale);
		WizardRulesTest.check("an APPLIED derivation carrying the marker is one more open point, a re-apply", still.total() == before + 1 && still.toApply.size() == 1
			&& still.toApply.get(0).reapplyNeeded && still.toApply.get(0).toBeProvidedRemaining().isEmpty());
		applied.applyMessage = "";
		WizardRulesTest.check("without the marker it is not", StillToDecide.of(stale).toApply.isEmpty() && StillToDecide.of(stale).total() == before);
	}

	private static void stillToDecideSetAsideIsNeitherPendingNorAccepted()
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		snapshot.stagingEntities.get(0).capabilities.get(0).status = "SET_ASIDE";
		snapshot.stagingEntities.get(0).capabilities.get(1).status = "SET_ASIDE";
		final StillToDecide still = StillToDecide.of(snapshot);
		WizardRulesTest.check("a set-aside service is no longer left", still.checks.size() == 2 && still.total() == 5);
		WizardRulesTest.check("nor counted as accepted, but tallied as set aside", still.checkTally.accepted == 0 && still.checkTally.setAside == 2);
		WizardRulesTest.check("set-all now names only the 2 still pending", "c3,c4".equals(still.idsOfServicesThatCannotRun()));
	}

	private static void stillToDecideOnlyAcceptedObjectsAndNoTechnicalKey()
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		snapshot.stagingEntities.get(1).status = "PROPOSED";
		final WizardSnapshot.AttributeInfo proposedKey = WizardRulesTest.attribute("499", "sourceSystem", "PROPOSED", true);
		snapshot.stagingEntities.get(0).attributes.add(proposedKey);
		// a PROPOSED mapping of the PROPOSED field follows it (MappingFollowRule) - never listed on its own
		snapshot.stagingEntities.get(0).feeds.get(1).mappings.add(WizardRulesTest.feedMapping("m404", "Language", "correspondenceLanguage", "PROPOSED"));
		// ... while a PROPOSED mapping of an ACCEPTED field is a decision of its own
		snapshot.stagingEntities.get(0).feeds.get(1).mappings.add(WizardRulesTest.feedMapping("m405", "FirstName", "lastName", "PROPOSED"));
		final StillToDecide still = StillToDecide.of(snapshot);
		WizardRulesTest.check("a PROPOSED business object is decided as a whole elsewhere", still.fields.size() == 1);
		WizardRulesTest.check("a technical key is never listed", !"sourceSystem".equals(still.fields.get(0).name));
		WizardRulesTest.check("only the mapping of the accepted field is listed", still.links.size() == 1 && "m405".equals(still.links.get(0).rowId)
			&& com.on.ebx.factory.review.ReviewLine.MAPPING.equals(still.links.get(0).nature));
		WizardRulesTest.check("the field fed by a second, still-proposed mapping lists both sources and is not 'links accepted' via feeds",
			still.fields.get(0).sources.equals(Arrays.asList("SALESFORCE", "SAP")));
	}

	/** The cascade's own predicate (ebx.ProposalReviews#acceptProposedRulesOfAttribute): a rule a person already decided never moves. */
	private static void onlyAProposedRuleFollowsItsFieldDecision()
	{
		WizardRulesTest.check("PROPOSED follows", StillToDecide.followsFieldDecision("PROPOSED"));
		for (final String status : new String[] { "ACCEPTED", "REJECTED", "APPLIED", "SET_ASIDE", null })
		{
			WizardRulesTest.check(status + " never follows", !StillToDecide.followsFieldDecision(status));
		}
	}

	/** Polish lot (2026-09-27, measured live on project ret): no raw severity prefix, no raw JSON in the card. */
	private static void stillToDecideLiftsSeverityAndReadsTheDetail()
	{
		final String[] split = StillToDecide.splitSeverity("MODEL_CONSTRAINT - Must match pattern: ^(?:[0-9]|[A-Z])$");
		WizardRulesTest.check("severity lifted", "MODEL_CONSTRAINT".equals(split[0]) && "Must match pattern: ^(?:[0-9]|[A-Z])$".equals(split[1]));
		WizardRulesTest.check("REJECT lifted", "REJECT".equals(StillToDecide.splitSeverity("REJECT - Position isValid of lib")[0]));
		WizardRulesTest.check("no prefix, unchanged", "".equals(StillToDecide.splitSeverity("Must not be empty")[0])
			&& "Must not be empty".equals(StillToDecide.splitSeverity("Must not be empty")[1]));
		WizardRulesTest.check("null is empty", "".equals(StillToDecide.splitSeverity(null)[1]));
		WizardRulesTest.check("JSON error -> its sentence",
			"Connector 'Melissa' configuration is invalid".equals(StillToDecide.readableDetail("{\"error\":\"Connector 'Melissa' configuration is invalid\"}")));
		WizardRulesTest.check("JSON message -> its sentence", "x".equals(StillToDecide.readableDetail("{\"message\":\"x\"}")));
		WizardRulesTest.check("plain text kept", "secret missing".equals(StillToDecide.readableDetail("secret missing")));
		WizardRulesTest.check("broken JSON shown as is", "{oops".equals(StillToDecide.readableDetail("{oops")));
		WizardRulesTest.check("null is empty", "".equals(StillToDecide.readableDetail(null)));
		final StillToDecide still = StillToDecide.of(WizardRulesTest.retSnapshot());
		WizardRulesTest.check("the RET rule carries its severity apart", "MODEL_CONSTRAINT".equals(still.rules.get(0).severity)
			&& still.rules.get(0).proposition.startsWith("Must match pattern"));
		boolean jsonLeft = false;
		for (final StillToDecide.CheckItem item : still.checks)
		{
			jsonLeft |= item.readinessDetail.startsWith("{");
		}
		WizardRulesTest.check("no service detail left as JSON", !jsonLeft);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
