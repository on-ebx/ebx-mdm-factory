package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryRight;

/**
 * Every background job kind the factory knows, with the resource it writes (lot C1, 2026-09-24). Registered once at
 * module start, before the queue resumes the jobs a restart left QUEUED - a kind must be known before its first job
 * is dispatched, which a lazy static initialiser in each {@code *Run} class could not guarantee.
 * <p>
 * Audit F-02: every kind also declares the Factory right that queues it ({@code FactoryJobQueue#enqueue} checks it, the job then
 * runs with the system session): STEWARD for the refresh of the matching results a steward decides from, ADMINISTRATOR for the
 * three whole-project resets and deletion, DESIGN for every other kind. {@code FactoryRightsJobKindsTest} reads this file.
 */
final class FactoryJobKinds
{
	private FactoryJobKinds()
	{
	}

	static void registerAll()
	{
		FactoryJobQueue.register(StagingLoadRun.STEP, FactoryJobs.STAGING, StagingLoadRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(MasterBuildRun.STEP, FactoryJobs.MASTER, MasterBuildRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(StagingProposalRun.STEP, FactoryJobs.DESIGN, StagingProposalRun.HANDLER, FactoryRight.DESIGN);
		// lot C1-C (2026-10-12): the Centralisation style's adapted proposal (passes 1 to 4 of the accelerator packs), resource DESIGN like the other proposal runs
		FactoryJobQueue.register(AdaptationProposalRun.STEP, FactoryJobs.DESIGN, AdaptationProposalRun.HANDLER, FactoryRight.DESIGN);
		// audit F-47 (wave 5): the wizard's Accept all / Reject all (decisions, then one batch apply of the accepted script constraints) - DESIGN, like the runs that write those proposals
		FactoryJobQueue.register(StagingBulkDecideRun.STEP, FactoryJobs.DESIGN, StagingBulkDecideRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(LandingRun.STEP, FactoryJobs.LANDING, LandingRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(LandingRun.RESUME_STEP, FactoryJobs.LANDING, LandingRun.RESUME_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(SourcesRun.STEP, FactoryJobs.SOURCES, SourcesRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(DeducedScanRun.STEP, FactoryJobs.SOURCES, DeducedScanRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(MatchingConfigureRun.STEP, FactoryJobs.MATCHING, MatchingConfigureRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(MatchingRunRun.STEP, FactoryJobs.MATCHING, MatchingRunRun.HANDLER, FactoryRight.DESIGN);
		// wave 2 (2026-09-30, lot V2-A): recomputes the stored matching results of wizard step 6 - same resource as the run, so never concurrently with it.
		FactoryJobQueue.register(MatchingResultsRun.STEP, FactoryJobs.MATCHING, MatchingResultsRun.HANDLER, FactoryRight.STEWARD);
		// audit F-49: a decision made while a refresh runs is not in that refresh - this request is raised by a change, so it queues behind a RUNNING one instead of folding into it
		FactoryJobQueue.doNotFoldIntoRunning(MatchingResultsRun.STEP);
		// lot C3 (2026-09-24): the wizard's stand-alone Apply/Re-apply and Measure-again on a SCRIPT_CONSTRAINT
		// capability suggestion - one kind per (action, zone) since a kind's resource is fixed at registration,
		// see ebx.CapabilityApplyRun's own Javadoc.
		FactoryJobQueue.register(CapabilityApplyRun.APPLY_STAGING, FactoryJobs.STAGING, CapabilityApplyRun.APPLY_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(CapabilityApplyRun.APPLY_MASTER, FactoryJobs.MASTER, CapabilityApplyRun.APPLY_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(CapabilityApplyRun.MEASURE_STAGING, FactoryJobs.STAGING, CapabilityApplyRun.MEASURE_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(CapabilityApplyRun.MEASURE_MASTER, FactoryJobs.MASTER, CapabilityApplyRun.MEASURE_HANDLER, FactoryRight.DESIGN);
		// audit F-34: the wizard's Remove (it republishes the model) - same resource as the apply of its zone
		FactoryJobQueue.register(CapabilityApplyRun.REMOVE_STAGING, FactoryJobs.STAGING, CapabilityApplyRun.REMOVE_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(CapabilityApplyRun.REMOVE_MASTER, FactoryJobs.MASTER, CapabilityApplyRun.REMOVE_HANDLER, FactoryRight.DESIGN);
		// audit F-34 (wave 10): the proposal review's batch apply (one publication per model) - same resource as the single apply of its zone
		FactoryJobQueue.register(CapabilityApplyRun.APPLY_ALL_STAGING, FactoryJobs.STAGING, CapabilityApplyRun.APPLY_ALL_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(CapabilityApplyRun.APPLY_ALL_MASTER, FactoryJobs.MASTER, CapabilityApplyRun.APPLY_ALL_HANDLER, FactoryRight.DESIGN);
		// job-engine lot C3 (2026-09-24): the four standalone language-model suggestion passes, all resource
		// DESIGN (never run over StagingProposalRun, nor over each other, for the same project).
		FactoryJobQueue.register(StagingSuggestLlmRun.STEP, FactoryJobs.DESIGN, StagingSuggestLlmRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(QualitySuggestLlmRun.STEP, FactoryJobs.DESIGN, QualitySuggestLlmRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(CapabilitySuggestLlmRun.STEP, FactoryJobs.DESIGN, CapabilitySuggestLlmRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(MatchingSuggestLlmRun.STEP, FactoryJobs.DESIGN, MatchingSuggestLlmRun.HANDLER, FactoryRight.DESIGN);
		// Validation desk lot L5 (2026-10-22): "Propose a field for it" - the capability pass narrowed to the computed field an input no field holds needs, resource DESIGN like the other language-model passes
		FactoryJobQueue.register(MissingInputRun.STEP, FactoryJobs.DESIGN, MissingInputRun.HANDLER, FactoryRight.DESIGN);
		// Validation desk lot L3 (2026-10-22): the desk's "Ask for a correction" - the anomaly pass for one business object, SAME_CONSTANT findings only
		FactoryJobQueue.register(AnomalyReviewRun.STEP, FactoryJobs.DESIGN, AnomalyReviewRun.HANDLER, FactoryRight.DESIGN);
		// Validation desk lot L4 (2026-10-22): "Measure" on a check that was never measured - where does the rule fail, by which other field
		FactoryJobQueue.register(RuleBreakdownRun.STEP, FactoryJobs.DESIGN, RuleBreakdownRun.HANDLER, FactoryRight.DESIGN);
		// audit F-34 (wave 10): the rule-based propose passes (staging with its chained language-model pass, quality, matching plans), resource DESIGN like the
		// language-model passes, and the distribution step's set-up actions
		FactoryJobQueue.register(SuggestRulesRun.STAGING_STEP, FactoryJobs.DESIGN, SuggestRulesRun.STAGING_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(SuggestRulesRun.QUALITY_STEP, FactoryJobs.DESIGN, SuggestRulesRun.QUALITY_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(SuggestRulesRun.MATCHING_STEP, FactoryJobs.DESIGN, SuggestRulesRun.MATCHING_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(DistributionSetupRun.CONFIGURE_STEP, FactoryJobs.DISTRIBUTION, DistributionSetupRun.CONFIGURE_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(DistributionSetupRun.TRIGGERS_STEP, FactoryJobs.MASTER, DistributionSetupRun.TRIGGERS_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(DistributionSetupRun.PRODUCT_STEP, FactoryJobs.DISTRIBUTION, DistributionSetupRun.PRODUCT_HANDLER, FactoryRight.DESIGN);
		// job-engine lot C3 (2026-09-24): the onboarding panel's single-MCP-call writes (resource SOURCES, same
		// as SourcesRun) and the step-3 Advanced disclosure's per-source Plan/Generate (resource LANDING, same
		// as LandingRun) - see each class's own Javadoc.
		FactoryJobQueue.register(OnboardStepRun.CATALOG_STEP, FactoryJobs.SOURCES, OnboardStepRun.CATALOG_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(OnboardStepRun.SCAN_STEP, FactoryJobs.SOURCES, OnboardStepRun.SCAN_HANDLER, FactoryRight.DESIGN);
		// UX lot U2 (2026-10-13): "Scan again" on any application card of step 2 - catalog scan then plan, one job (resource SOURCES, like the onboarding scan)
		FactoryJobQueue.register(SourceRescanRun.STEP, FactoryJobs.SOURCES, SourceRescanRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(LandingSourceRun.PLAN_STEP, FactoryJobs.LANDING, LandingSourceRun.PLAN_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(LandingSourceRun.GENERATE_STEP, FactoryJobs.LANDING, LandingSourceRun.GENERATE_HANDLER, FactoryRight.DESIGN);
		// agentic framework lot (2026-10-30, docs/AGENTS.md): a run of a declared agent - resource DESIGN like the other language-model passes; queued by a
		// steward (the run then acts for that person, whose own EBX permissions bound every action)
		FactoryJobQueue.register(AgentFramework.RUN_STEP, FactoryJobs.DESIGN, AgentRunJob.HANDLER, FactoryRight.STEWARD);
		// automatic-intake lot (2026-10-06): "Read now" of one application - a plain landing load, same resource
		FactoryJobQueue.register(LandingSourceRun.READ_STEP, FactoryJobs.LANDING, LandingSourceRun.READ_HANDLER, FactoryRight.DESIGN);
		// audit F-02: "Read now" is an intake operation - the pipeline launcher role queues it too (Intake.readNow checks the same)
		FactoryJobQueue.allowPipelineOperator(LandingSourceRun.READ_STEP);
		// audit F-34 (wave 10): "Study the key" when the language model takes part - the report is handed to the wizard through KeyStudyRun.take
		FactoryJobQueue.register(KeyStudyRun.STEP, FactoryJobs.LANDING, KeyStudyRun.HANDLER, FactoryRight.DESIGN);
		// audit F-35 (wave 5): "Change the key..." on a loaded landing table - batched deletion of its rows, then the flow, in the background
		FactoryJobQueue.register(LandingKeyChangeRun.STEP, FactoryJobs.LANDING, LandingKeyChangeRun.HANDLER, FactoryRight.DESIGN);
		// volume-operations lot 8a (2026-09-28, task 1): matching/refresh-golden-keys and the two distribution
		// send routes had no job wrapper at all before this lot - see each class' own Javadoc.
		FactoryJobQueue.register(GoldenKeyRefreshRun.STEP, FactoryJobs.MATCHING, GoldenKeyRefreshRun.HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(DistributionSendRun.FIRST_FULL_STEP, FactoryJobs.DISTRIBUTION, DistributionSendRun.FIRST_FULL_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(DistributionSendRun.SEND_STEP, FactoryJobs.DISTRIBUTION, DistributionSendRun.SEND_HANDLER, FactoryRight.DESIGN);
		// volume-operations lot 8a (2026-09-28, task 2): the three whole-project actions (a step reset, a
		// project deletion, a master-zone reset) - see each class' own Javadoc for why registerWholeProject
		// (FactoryJobs.claimAll), not the single-resource register above.
		FactoryJobQueue.registerWholeProject(StepResetRun.STEP, StepResetRun.HANDLER, FactoryRight.ADMINISTRATOR);
		FactoryJobQueue.registerWholeProject(ProjectDeleteRun.STEP, ProjectDeleteRun.HANDLER, FactoryRight.ADMINISTRATOR);
		FactoryJobQueue.registerWholeProject(MasterResetRun.STEP, MasterResetRun.HANDLER, FactoryRight.ADMINISTRATOR);
		// lot C1-D (2026-10-12): the generation of a Centralisation project's domain (nine PipelineSteps), resource MASTER like the golden zone it builds
		FactoryJobQueue.register(DomainGenerator.STEP, FactoryJobs.MASTER, DomainGenerator.HANDLER, FactoryRight.DESIGN);
		// F2-102 (robustness audit 2026-10-23): the actions that still ran inside the wizard's request. The intake switch republishes the landing models (resource LANDING) and,
		// like "Read now", may be queued by the pipeline launcher role; the check before the build reads the landing and writes the stored report (STAGING); the plan simulation and
		// the bulk merge work on the matching (MATCHING, the merge is the steward's); "Chain the loads" launches the pipeline workflow for its person (DESIGN or the launcher role)
		FactoryJobQueue.register(IntakeSwitchRun.ON_STEP, FactoryJobs.LANDING, IntakeSwitchRun.ON_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(IntakeSwitchRun.OFF_STEP, FactoryJobs.LANDING, IntakeSwitchRun.OFF_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.allowPipelineOperator(IntakeSwitchRun.ON_STEP);
		FactoryJobQueue.allowPipelineOperator(IntakeSwitchRun.OFF_STEP);
		FactoryJobQueue.register(WizardActionRuns.PREFLIGHT_STEP, FactoryJobs.STAGING, WizardActionRuns.PREFLIGHT_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(WizardActionRuns.SIMULATE_STEP, FactoryJobs.MATCHING, WizardActionRuns.SIMULATE_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.register(WizardActionRuns.BULK_MERGE_STEP, FactoryJobs.MATCHING, WizardActionRuns.BULK_MERGE_HANDLER, FactoryRight.STEWARD);
		FactoryJobQueue.register(WizardActionRuns.PIPELINE_STEP, FactoryJobs.DESIGN, WizardActionRuns.PIPELINE_HANDLER, FactoryRight.DESIGN);
		FactoryJobQueue.allowPipelineOperator(WizardActionRuns.PIPELINE_STEP);
		// F2-103: the transfer-reject review's export file (a read, but a job kind is always a person's right: the steward's) and its "acknowledge / reopen all" (the steward's), resource STAGING like the loads that write the rejects
		FactoryJobQueue.register(RejectBulkRun.EXPORT_STEP, FactoryJobs.STAGING, RejectBulkRun.EXPORT_HANDLER, FactoryRight.STEWARD);
		FactoryJobQueue.register(RejectBulkRun.BULK_STEP, FactoryJobs.STAGING, RejectBulkRun.BULK_HANDLER, FactoryRight.STEWARD);
		// audit F-37: the kinds that can saturate the processor or the database on their own - ebx.factory.maxHeavyJobs bounds how many run at
		// once across the instance (unlimited by default); a pool thread and the project's resource are still needed on top
		FactoryJobs.registerHeavy(MatchingRunRun.STEP);
		FactoryJobs.registerHeavy(StagingLoadRun.STEP);
		FactoryJobs.registerHeavy(MasterBuildRun.STEP);
		FactoryJobs.registerHeavy(LandingRun.STEP);
		FactoryJobs.registerHeavy(LandingRun.RESUME_STEP);
		FactoryJobs.registerHeavy(DistributionSendRun.FIRST_FULL_STEP);
		FactoryJobs.registerHeavy(LandingKeyChangeRun.STEP);
	}
}
