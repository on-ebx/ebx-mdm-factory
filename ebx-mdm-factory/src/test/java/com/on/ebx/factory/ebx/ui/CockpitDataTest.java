package com.on.ebx.factory.ebx.ui;

import java.util.Date;
import java.util.List;

import com.on.ebx.factory.review.ReviewEntityFormat;

/** JUnit-free: run the main, a failure throws. */
public final class CockpitDataTest
{
	/** A fixed instant, never {@code new Date()}: every "stuck?"/"how long ago" computation must be reproducible. */
	private static final Date NOW = new Date(1790000000000L);

	public static void main(final String[] pArgs)
	{
		CockpitDataTest.emptyProjectIsHonestlyEmpty();
		CockpitDataTest.statsAndVolumesFromTheCompaniesDemoShape();
		CockpitDataTest.pipelineStepsAreGroupedBySourceAndOrdered();
		CockpitDataTest.driftedAndFailedTablesRaiseAnAlertOnlyThen();
		CockpitDataTest.reRunningAStepKeepsOneLineWithARunCount();
		CockpitDataTest.aFailedRerunOverridesAnEarlierSuccess();
		CockpitDataTest.orderIsByPipelineStageNotByWhichRanMostRecently();
		CockpitDataTest.stuckDetectionUsesTheInjectedClockNotTheSystemOne();
		CockpitDataTest.projectLevelStepsGroupAboveTheSourcesAndStayDistinctByOrigin();

		CockpitDataTest.noAcceptedEntityIsTheHonestEmptyState();
		CockpitDataTest.loadedEntityShowsRealCounters();
		CockpitDataTest.generatedButNeverLoadedEntitySaysSoDifferentlyFromNoDatasetAtAll();
		CockpitDataTest.pendingAndRejectedEntitiesAreNeverShownAsVolumes();
		CockpitDataTest.stagingRowsKpiSumsEveryEntitysWrittenCount();
		CockpitDataTest.canonicalEntityMembersAreFormattedByReviewEntityFormat();

		CockpitDataTest.rejectsKpiCountsOnlyUnresolved();
		CockpitDataTest.rejectSummaryGroupsByEntityAndStage();
		CockpitDataTest.latestRejectsAreCappedAtTenMostRecentFirst();
		CockpitDataTest.allResolvedRejectsStillShowUpAsHistory();

		CockpitDataTest.relationEvidenceCarriesTheCheckOrNone();

		CockpitDataTest.lastAgentRunPassesThrough();
		CockpitDataTest.noAgentRunIsNull();

		CockpitDataTest.qualityRulesGroupedByEntityAcceptedAndPendingCounted();
		CockpitDataTest.qualityFailingSumsOnlyAcceptedLikeRules();
		CockpitDataTest.noQualityRuleIsTheHonestEmptyState();

		CockpitDataTest.matchingListsOnePerEntityWithAPlan();
		CockpitDataTest.matchingCarriesTheCommentOnlyForRejected();
		CockpitDataTest.noMatchPlanIsTheHonestEmptyState();

		CockpitDataTest.sameInstanceIsNotDelivered();
		CockpitDataTest.differentInstanceIsDelivered();
		CockpitDataTest.unstampedProjectIsNeverDeliveredByAccident();
		CockpitDataTest.instanceRolePassesThroughRegardlessOfDelivery();

		System.out.println("CockpitDataTest OK");
	}

	private static void emptyProjectIsHonestlyEmpty()
	{
		final CockpitData.Snapshot s = CockpitData.compute(CockpitDataTest.inputs());
		CockpitDataTest.check("no source", s.sourceCount == 0);
		CockpitDataTest.check("no landing table", s.landingTableCount == 0);
		CockpitDataTest.check("no rows loaded", s.rowsLoaded == 0);
		CockpitDataTest.check("no staging rows written", s.stagingRowsWritten == 0);
		CockpitDataTest.check("no relations", s.relationsTotal == 0 && s.relationsAccepted == 0);
		CockpitDataTest.check("rejects always zero when none recorded", s.rejects == 0);
		CockpitDataTest.check("no staging entity", s.stagingEntityCount == 0);
		CockpitDataTest.check("master zone never exists in this lot", !s.masterZoneExists);
		CockpitDataTest.check("no pipeline block", s.pipeline.isEmpty());
		CockpitDataTest.check("no agent run", s.lastAgentRun == null);
		// the staging and master empty states are still present (one row each), not silently dropped
		CockpitDataTest.check("a staging empty-state row exists", CockpitDataTest.count(s.volumes, "staging") == 1);
		CockpitDataTest.check("a master empty-state row exists", CockpitDataTest.count(s.volumes, "master") == 1);
	}

	/** Mirrors the real ERP/CRM run in PLAN.md: 55 + 52 + 89 = 196 rows, two accepted relations. */
	private static void statsAndVolumesFromTheCompaniesDemoShape()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.sources.add(CockpitDataTest.source("ERP"));
		in.sources.add(CockpitDataTest.source("CRM"));
		in.tables.add(CockpitDataTest.table("ERP", "SUPPLIER", "LOADED", 55L, 55L));
		in.tables.add(CockpitDataTest.table("CRM", "ACCOUNT", "LOADED", 52L, 52L));
		in.tables.add(CockpitDataTest.table("CRM", "CONTACT", "LOADED", 89L, 89L));
		in.relations.add(CockpitDataTest.relation("CONTACT", "ACCOUNT", "ACCEPTED"));
		in.relations.add(CockpitDataTest.relation("ACCOUNT", "ACCOUNT", "ACCEPTED"));

		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("2 sources", s.sourceCount == 2);
		CockpitDataTest.check("3 landing tables", s.landingTableCount == 3);
		CockpitDataTest.check("196 rows loaded: " + s.rowsLoaded, s.rowsLoaded == 196L);
		CockpitDataTest.check("2/2 relations accepted", s.relationsTotal == 2 && s.relationsAccepted == 2);
		CockpitDataTest.check("3 landing volume rows", CockpitDataTest.count(s.volumes, "landing") == 3);
		// no staging entity was proposed: a single "not proposed" row, not one fabricated per landing table
		CockpitDataTest.check("a single staging empty-state row, not one per table", CockpitDataTest.count(s.volumes, "staging") == 1);
	}

	private static void pipelineStepsAreGroupedBySourceAndOrdered()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.sources.add(CockpitDataTest.source("ERP"));
		// deliberately out of order and interleaved with a step of another scope
		in.steps.add(CockpitDataTest.step("s2", "LANDING_DATASET", "ERP", "DONE", CockpitDataTest.minutesAgo(30), CockpitDataTest.minutesAgo(29)));
		in.steps.add(CockpitDataTest.step("s1", "LANDING_MODEL", "ERP", "DONE", CockpitDataTest.minutesAgo(40), CockpitDataTest.minutesAgo(39)));
		in.steps.add(CockpitDataTest.step("s0", "STAGING_SUGGEST", "", "DONE", CockpitDataTest.minutesAgo(50), CockpitDataTest.minutesAgo(49))); // whole-project scope: not under ERP
		in.steps.add(CockpitDataTest.step("s3", "LANDING_LOAD", "ERP", "FAILED", CockpitDataTest.minutesAgo(20), CockpitDataTest.minutesAgo(19)));

		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("a project block plus one source block: " + s.pipeline.size(), s.pipeline.size() == 2);
		CockpitDataTest.check("the project block comes first", s.pipeline.get(0).project);
		final CockpitData.SourceBlock block = s.pipeline.get(1);
		CockpitDataTest.check("the ERP block is not the project block", !block.project);
		CockpitDataTest.check("only the 3 ERP-scoped steps, the whole-project one excluded", block.steps.size() == 3);
		CockpitDataTest.check("sorted by the canonical step order: " + block.steps.get(0).step, "LANDING_MODEL".equals(block.steps.get(0).step));
		CockpitDataTest.check("then LANDING_DATASET", "LANDING_DATASET".equals(block.steps.get(1).step));
		CockpitDataTest.check("then LANDING_LOAD", "LANDING_LOAD".equals(block.steps.get(2).step));
		CockpitDataTest.check("the failed step keeps its status", "FAILED".equals(block.steps.get(2).status));
	}

	private static void driftedAndFailedTablesRaiseAnAlertOnlyThen()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.tables.add(CockpitDataTest.table("ERP", "SUPPLIER", "LOADED", 55L, 55L));
		final CockpitData.TableInfo drifted = CockpitDataTest.table("SUPPLIER", "SUPPLIER", "DRIFTED", 55L, 55L);
		drifted.message = "new source column DISCOUNT_CODE";
		in.tables.add(drifted);
		in.tables.add(CockpitDataTest.table("SUPPLIER", "SUPPLIER", "FAILED", null, null));

		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("only the drifted and failed tables raise an alert: " + s.alerts.size(), s.alerts.size() == 2);
		CockpitDataTest.check("the drift alert carries the table's own message", "new source column DISCOUNT_CODE".equals(s.alerts.get(0).message));
	}

	/** The coordinator's exact screenshot: flows regenerated with {@code ?force=true} runs LANDING_FLOWS twice. */
	private static void reRunningAStepKeepsOneLineWithARunCount()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.sources.add(CockpitDataTest.source("ERP"));
		in.steps.add(CockpitDataTest.step("1", "LANDING_FLOWS", "ERP", "DONE", CockpitDataTest.minutesAgo(120), CockpitDataTest.minutesAgo(119)));
		in.steps.add(CockpitDataTest.step("2", "LANDING_FLOWS", "ERP", "DONE", CockpitDataTest.minutesAgo(10), CockpitDataTest.minutesAgo(9)));
		in.steps.add(CockpitDataTest.step("3", "LANDING_LOAD", "ERP", "DONE", CockpitDataTest.minutesAgo(30), CockpitDataTest.minutesAgo(29)));
		in.steps.add(CockpitDataTest.step("4", "LANDING_LOAD", "ERP", "DONE", CockpitDataTest.minutesAgo(5), CockpitDataTest.minutesAgo(4)));
		in.steps.add(CockpitDataTest.step("5", "LANDING_LOAD", "ERP", "DONE", CockpitDataTest.minutesAgo(1), CockpitDataTest.minutesAgo(0)));

		final CockpitData.Snapshot s = CockpitData.compute(in);
		final CockpitData.SourceBlock block = s.pipeline.get(0);
		CockpitDataTest.check("one line per step type, not one per run: " + block.steps.size(), block.steps.size() == 2);
		CockpitDataTest.check("LANDING_FLOWS ran twice", CockpitDataTest.stepRow(block, "LANDING_FLOWS").runs == 2);
		CockpitDataTest.check("LANDING_LOAD ran three times", CockpitDataTest.stepRow(block, "LANDING_LOAD").runs == 3);
		CockpitDataTest.check("the line's end time is the LATEST run's, not the first",
			CockpitDataTest.stepRow(block, "LANDING_FLOWS").endedAt.equals(CockpitDataTest.minutesAgo(9)));
	}

	private static void aFailedRerunOverridesAnEarlierSuccess()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.sources.add(CockpitDataTest.source("ERP"));
		in.steps.add(CockpitDataTest.step("1", "LANDING_LOAD", "ERP", "DONE", CockpitDataTest.minutesAgo(60), CockpitDataTest.minutesAgo(59)));
		in.steps.add(CockpitDataTest.step("2", "LANDING_LOAD", "ERP", "FAILED", CockpitDataTest.minutesAgo(5), CockpitDataTest.minutesAgo(4)));

		final CockpitData.Snapshot s = CockpitData.compute(in);
		final CockpitData.StepRow row = CockpitDataTest.stepRow(s.pipeline.get(0), "LANDING_LOAD");
		CockpitDataTest.check("the latest (failed) run wins over the earlier success", "FAILED".equals(row.status));
		CockpitDataTest.check("both runs are still counted", row.runs == 2);
	}

	/** Two runs recorded in the very same instant: {@code id} breaks the tie, never insertion order by accident. */
	private static void orderIsByPipelineStageNotByWhichRanMostRecently()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.sources.add(CockpitDataTest.source("ERP"));
		final Date sameInstant = CockpitDataTest.minutesAgo(10);
		final CockpitData.StepInfo older = CockpitDataTest.step("1", "LANDING_LOAD", "ERP", "DONE", sameInstant, sameInstant);
		final CockpitData.StepInfo newer = CockpitDataTest.step("2", "LANDING_LOAD", "ERP", "FAILED", sameInstant, sameInstant);
		// deliberately inserted with the higher id first, to prove the winner is chosen by id, not by list order
		in.steps.add(newer);
		in.steps.add(older);
		in.steps.add(CockpitDataTest.step("3", "LANDING_MODEL", "ERP", "DONE", CockpitDataTest.minutesAgo(90), CockpitDataTest.minutesAgo(89)));

		final CockpitData.Snapshot s = CockpitData.compute(in);
		final CockpitData.SourceBlock block = s.pipeline.get(0);
		// LANDING_MODEL comes first in the row list (pipeline order) even though it started long before LANDING_LOAD
		CockpitDataTest.check("pipeline order, not recency, drives the row order", "LANDING_MODEL".equals(block.steps.get(0).step));
		CockpitDataTest.check("LANDING_LOAD second", "LANDING_LOAD".equals(block.steps.get(1).step));
		// same startedAt for both LANDING_LOAD runs: the higher id (run "2", FAILED) must win the tie-break
		CockpitDataTest.check("the higher id wins an exact-instant tie, not insertion order", "FAILED".equals(block.steps.get(1).status));
	}

	private static void stuckDetectionUsesTheInjectedClockNotTheSystemOne()
	{
		final CockpitData.Inputs notStuckIn = CockpitDataTest.inputs();
		notStuckIn.sources.add(CockpitDataTest.source("ERP"));
		notStuckIn.steps.add(CockpitDataTest.step("1", "LANDING_LOAD", "ERP", "RUNNING", CockpitDataTest.minutesAgo(10), null));
		final CockpitData.Snapshot notStuck = CockpitData.compute(notStuckIn);
		CockpitDataTest.check("running for 10 minutes is not stuck", !notStuck.pipeline.get(0).steps.get(0).stuck);

		final CockpitData.Inputs stuckIn = CockpitDataTest.inputs();
		stuckIn.sources.add(CockpitDataTest.source("ERP"));
		stuckIn.steps.add(CockpitDataTest.step("2", "LANDING_LOAD", "ERP", "RUNNING", CockpitDataTest.minutesAgo(45), null));
		final CockpitData.Snapshot stuck = CockpitData.compute(stuckIn);
		CockpitDataTest.check("running for 45 minutes is flagged possibly stuck", stuck.pipeline.get(0).steps.get(0).stuck);

		final CockpitData.Inputs doneIn = CockpitDataTest.inputs();
		doneIn.sources.add(CockpitDataTest.source("ERP"));
		doneIn.steps.add(CockpitDataTest.step("3", "LANDING_LOAD", "ERP", "DONE", CockpitDataTest.minutesAgo(45), CockpitDataTest.minutesAgo(44)));
		final CockpitData.Snapshot done = CockpitData.compute(doneIn);
		CockpitDataTest.check("a step that finished is never flagged stuck, however long it ran", !done.pipeline.get(0).steps.get(0).stuck);
	}

	/** {@code STAGING_SUGGEST} from the rule pass (scope "") and from the language model pass (scope "LLM") both
	 * belong to the "Project" group, but are two different processes: one's re-run count must not absorb the
	 * other's. */
	private static void projectLevelStepsGroupAboveTheSourcesAndStayDistinctByOrigin()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.sources.add(CockpitDataTest.source("ERP"));
		in.steps.add(CockpitDataTest.step("1", "STAGING_SUGGEST", "", "DONE", CockpitDataTest.minutesAgo(60), CockpitDataTest.minutesAgo(59)));
		in.steps.add(CockpitDataTest.step("2", "STAGING_SUGGEST", "", "DONE", CockpitDataTest.minutesAgo(30), CockpitDataTest.minutesAgo(29)));
		in.steps.add(CockpitDataTest.step("3", "STAGING_SUGGEST", "LLM", "DONE", CockpitDataTest.minutesAgo(20), CockpitDataTest.minutesAgo(19)));
		in.steps.add(CockpitDataTest.step("4", "LANDING_MODEL", "ERP", "DONE", CockpitDataTest.minutesAgo(90), CockpitDataTest.minutesAgo(89)));

		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("project block first, source block second", s.pipeline.size() == 2 && s.pipeline.get(0).project && !s.pipeline.get(1).project);
		final CockpitData.SourceBlock project = s.pipeline.get(0);
		CockpitDataTest.check("the rule pass and the LLM pass stay two distinct lines: " + project.steps.size(), project.steps.size() == 2);
		int ruleRuns = -1;
		int llmRuns = -1;
		for (final CockpitData.StepRow row : project.steps)
		{
			// both rows share the same step code (STAGING_SUGGEST): the run counts are what proves they were not merged
			if (row.endedAt.equals(CockpitDataTest.minutesAgo(29)))
			{
				ruleRuns = row.runs;
			}
			if (row.endedAt.equals(CockpitDataTest.minutesAgo(19)))
			{
				llmRuns = row.runs;
			}
		}
		CockpitDataTest.check("the rule pass ran twice, on its own", ruleRuns == 2);
		CockpitDataTest.check("the LLM pass ran once, on its own, not folded into the rule pass's count", llmRuns == 1);
	}

	// ------------------------------------------------------------------ staging volumes (real transfer counters)

	private static void noAcceptedEntityIsTheHonestEmptyState()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.entities.add(CockpitDataTest.entity("Supplier", "PROPOSED", null, null));
		in.stagingDatasetExists = true;
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("a PROPOSED (not yet accepted) entity is never shown as a volume", CockpitDataTest.count(s.volumes, "staging") == 1);
		CockpitDataTest.check("no staging entity in scope", s.stagingEntityCount == 0);
		final CockpitData.VolumeRow row = CockpitDataTest.firstOfZone(s.volumes, "staging");
		CockpitDataTest.check("the honest empty state, not a fabricated per-entity row", "NONE".equals(row.status) && row.table == null);
	}

	private static void loadedEntityShowsRealCounters()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		final CockpitData.EntityInfo supplier = CockpitDataTest.entity("Supplier", "ACCEPTED", 55L, CockpitDataTest.minutesAgo(5));
		supplier.lastLoadRejected = Long.valueOf(0L);
		in.entities.add(supplier);
		in.stagingDatasetExists = true;
		final CockpitData.Snapshot s = CockpitData.compute(in);
		final CockpitData.VolumeRow row = CockpitDataTest.firstOfZone(s.volumes, "staging");
		CockpitDataTest.check("the real written count is shown, not an em dash", Long.valueOf(55L).equals(row.rows));
		CockpitDataTest.check("status LOADED", "LOADED".equals(row.status));
		CockpitDataTest.check("the rejected count travels with the row", Long.valueOf(0L).equals(row.rejected));
		CockpitDataTest.check("the last load time travels with the row", row.lastAt != null);
	}

	private static void generatedButNeverLoadedEntitySaysSoDifferentlyFromNoDatasetAtAll()
	{
		final CockpitData.Inputs withDataset = CockpitDataTest.inputs();
		withDataset.entities.add(CockpitDataTest.entity("Account", "ACCEPTED", null, null));
		withDataset.stagingDatasetExists = true;
		final CockpitData.Snapshot withDatasetSnapshot = CockpitData.compute(withDataset);
		final CockpitData.VolumeRow generated = CockpitDataTest.firstOfZone(withDatasetSnapshot.volumes, "staging");
		CockpitDataTest.check("accepted, dataset exists, never loaded: GENERATED, not NOT_GENERATED", "GENERATED".equals(generated.status));
		CockpitDataTest.check("still no row count: never fabricate 0", generated.rows == null);

		final CockpitData.Inputs withoutDataset = CockpitDataTest.inputs();
		withoutDataset.entities.add(CockpitDataTest.entity("Account", "ACCEPTED", null, null));
		withoutDataset.stagingDatasetExists = false;
		final CockpitData.Snapshot withoutDatasetSnapshot = CockpitData.compute(withoutDataset);
		final CockpitData.VolumeRow notGenerated = CockpitDataTest.firstOfZone(withoutDatasetSnapshot.volumes, "staging");
		CockpitDataTest.check("accepted, no staging dataset at all: NOT_GENERATED, a different message than GENERATED", "NOT_GENERATED".equals(notGenerated.status));
	}

	private static void pendingAndRejectedEntitiesAreNeverShownAsVolumes()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.entities.add(CockpitDataTest.entity("Proposed", "PROPOSED", 10L, CockpitDataTest.minutesAgo(1)));
		in.entities.add(CockpitDataTest.entity("Rejected", "REJECTED", 10L, CockpitDataTest.minutesAgo(1)));
		final CockpitData.EntityInfo applied = CockpitDataTest.entity("Applied", "APPLIED", 10L, CockpitDataTest.minutesAgo(1));
		in.entities.add(applied);
		in.stagingDatasetExists = true;
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("only the APPLIED entity is in scope: PROPOSED/REJECTED never generate a volume row", CockpitDataTest.count(s.volumes, "staging") == 1);
		CockpitDataTest.check("it is the applied one", "Applied".equals(CockpitDataTest.firstOfZone(s.volumes, "staging").table));
	}

	private static void stagingRowsKpiSumsEveryEntitysWrittenCount()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.entities.add(CockpitDataTest.entity("Supplier", "ACCEPTED", 55L, CockpitDataTest.minutesAgo(1)));
		in.entities.add(CockpitDataTest.entity("Account", "ACCEPTED", 52L, CockpitDataTest.minutesAgo(1)));
		in.entities.add(CockpitDataTest.entity("Contact", "ACCEPTED", 89L, CockpitDataTest.minutesAgo(1)));
		in.stagingDatasetExists = true;
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("196 staging rows written: " + s.stagingRowsWritten, s.stagingRowsWritten == 196L);
	}

	private static void canonicalEntityMembersAreFormattedByReviewEntityFormat()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		final CockpitData.EntityInfo company = CockpitDataTest.entity("Company", "ACCEPTED", 107L, CockpitDataTest.minutesAgo(1));
		company.members.add(new ReviewEntityFormat.Member("Supplier", "ERP"));
		company.members.add(new ReviewEntityFormat.Member("Account", "CRM"));
		in.entities.add(company);
		in.stagingDatasetExists = true;
		final CockpitData.Snapshot s = CockpitData.compute(in);
		final CockpitData.VolumeRow row = CockpitDataTest.firstOfZone(s.volumes, "staging");
		CockpitDataTest.check("the member summary is exactly what ReviewEntityFormat.unifiesText produces",
			"Supplier (ERP), Account (CRM)".equals(row.memberSummary));

		final CockpitData.Inputs single = CockpitDataTest.inputs();
		single.entities.add(CockpitDataTest.entity("Supplier", "ACCEPTED", 55L, CockpitDataTest.minutesAgo(1)));
		single.stagingDatasetExists = true;
		final CockpitData.VolumeRow soloRow = CockpitDataTest.firstOfZone(CockpitData.compute(single).volumes, "staging");
		CockpitDataTest.check("an entity fed by one landing table (no members) carries no member summary", soloRow.memberSummary == null);
	}

	// ------------------------------------------------------------------ transfer rejects

	private static void rejectsKpiCountsOnlyUnresolved()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.rejects.add(CockpitDataTest.reject("Supplier", "STAGING", false, CockpitDataTest.minutesAgo(5)));
		in.rejects.add(CockpitDataTest.reject("Supplier", "STAGING", false, CockpitDataTest.minutesAgo(4)));
		in.rejects.add(CockpitDataTest.reject("Account", "STAGING", true, CockpitDataTest.minutesAgo(3)));
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("the KPI counts only unresolved rejects: " + s.rejects, s.rejects == 2);
	}

	private static void rejectSummaryGroupsByEntityAndStage()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.rejects.add(CockpitDataTest.reject("Supplier", "STAGING", false, CockpitDataTest.minutesAgo(5)));
		in.rejects.add(CockpitDataTest.reject("Supplier", "STAGING", false, CockpitDataTest.minutesAgo(4)));
		in.rejects.add(CockpitDataTest.reject("Supplier", "MASTER", false, CockpitDataTest.minutesAgo(3)));
		in.rejects.add(CockpitDataTest.reject("Account", "STAGING", false, CockpitDataTest.minutesAgo(2)));
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("three distinct (entity, stage) groups", s.rejectSummaries.size() == 3);
		int supplierStaging = -1;
		for (final CockpitData.RejectSummary summary : s.rejectSummaries)
		{
			if ("Supplier".equals(summary.entityName) && "STAGING".equals(summary.stage))
			{
				supplierStaging = summary.unresolvedCount;
			}
		}
		CockpitDataTest.check("Supplier/STAGING has 2 unresolved", supplierStaging == 2);
	}

	private static void latestRejectsAreCappedAtTenMostRecentFirst()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		for (int i = 0; i < 15; i++)
		{
			in.rejects.add(CockpitDataTest.reject("Supplier", "STAGING", false, CockpitDataTest.minutesAgo(i)));
		}
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("capped at 10: " + s.latestRejects.size(), s.latestRejects.size() == 10);
		CockpitDataTest.check("most recent first", s.latestRejects.get(0).rejectedAt.equals(CockpitDataTest.minutesAgo(0)));
		CockpitDataTest.check("then the next most recent", s.latestRejects.get(1).rejectedAt.equals(CockpitDataTest.minutesAgo(1)));
	}

	private static void allResolvedRejectsStillShowUpAsHistory()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.rejects.add(CockpitDataTest.reject("Supplier", "STAGING", true, CockpitDataTest.minutesAgo(5)));
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("the KPI is honestly zero: nothing unresolved", s.rejects == 0);
		CockpitDataTest.check("no summary row: nothing unresolved to group", s.rejectSummaries.isEmpty());
		CockpitDataTest.check("but the resolved reject still appears in the latest list: fixed history is not the same as never happened",
			s.latestRejects.size() == 1);
		CockpitDataTest.check("marked resolved", s.latestRejects.get(0).resolved);
	}

	// ------------------------------------------------------------------ relation evidence

	private static void relationEvidenceCarriesTheCheckOrNone()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		final CockpitData.RelationInfo checked = CockpitDataTest.relation("CONTACT", "ACCOUNT", "ACCEPTED");
		checked.checkedAt = CockpitDataTest.minutesAgo(10);
		checked.childValues = Long.valueOf(52L);
		checked.orphanValues = Long.valueOf(0L);
		in.relations.add(checked);
		in.relations.add(CockpitDataTest.relation("ACCOUNT", "ACCOUNT", "PROPOSED")); // never checked

		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("both relations reported, whatever their status", s.relations.size() == 2);
		CockpitDataTest.check("the checked one carries its evidence", Long.valueOf(52L).equals(s.relations.get(0).childValues));
		CockpitDataTest.check("the unchecked one carries no checkedAt", s.relations.get(1).checkedAt == null);
	}

	// ------------------------------------------------------------------ agent run

	private static void lastAgentRunPassesThrough()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		final CockpitData.AgentRunInfo run = new CockpitData.AgentRunInfo();
		run.phase = "STAGING_LLM";
		run.status = "DONE";
		run.steps = Integer.valueOf(12);
		run.inputTokens = Long.valueOf(45000L);
		run.outputTokens = Long.valueOf(3000L);
		in.lastAgentRun = run;
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("the last agent run passes through untouched", s.lastAgentRun == run);
	}

	private static void noAgentRunIsNull()
	{
		final CockpitData.Snapshot s = CockpitData.compute(CockpitDataTest.inputs());
		CockpitDataTest.check("no agent run recorded: null, not a fabricated placeholder", s.lastAgentRun == null);
	}

	// ------------------------------------------------------------------ quality (task C)

	private static void qualityRulesGroupedByEntityAcceptedAndPendingCounted()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.qualityRules.add(CockpitDataTest.qualityRule("Company", "ACCEPTED", null));
		in.qualityRules.add(CockpitDataTest.qualityRule("Company", "PROPOSED", null));
		in.qualityRules.add(CockpitDataTest.qualityRule("Company", "PROPOSED", null));
		in.qualityRules.add(CockpitDataTest.qualityRule("Contact", "APPLIED", null));
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("two entities reported", s.quality.size() == 2);
		final CockpitData.QualityEntitySummary company = CockpitDataTest.qualityOf(s, "Company");
		CockpitDataTest.check("Company: 1 accepted", company.accepted == 1);
		CockpitDataTest.check("Company: 2 pending", company.pending == 2);
		final CockpitData.QualityEntitySummary contact = CockpitDataTest.qualityOf(s, "Contact");
		CockpitDataTest.check("Contact: APPLIED counts as accepted-like", contact.accepted == 1 && contact.pending == 0);
		CockpitDataTest.check("project-wide accepted total: " + s.qualityRulesAccepted, s.qualityRulesAccepted == 2);
		CockpitDataTest.check("project-wide pending total: " + s.qualityRulesPending, s.qualityRulesPending == 2);
	}

	/** A REJECTED rule (a person decided against it) and a PROPOSED rule (never decided, and never actually run) must never contribute to the failing count - only ACCEPTED/APPLIED rules that have actually been checked at a load do. */
	private static void qualityFailingSumsOnlyAcceptedLikeRules()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.qualityRules.add(CockpitDataTest.qualityRule("Company", "ACCEPTED", Long.valueOf(3L)));
		in.qualityRules.add(CockpitDataTest.qualityRule("Company", "ACCEPTED", Long.valueOf(2L)));
		in.qualityRules.add(CockpitDataTest.qualityRule("Company", "REJECTED", Long.valueOf(99L))); // a person rejected it - never counted
		in.qualityRules.add(CockpitDataTest.qualityRule("Company", "PROPOSED", null)); // never run yet
		final CockpitData.Snapshot s = CockpitData.compute(in);
		final CockpitData.QualityEntitySummary company = CockpitDataTest.qualityOf(s, "Company");
		CockpitDataTest.check("only the two ACCEPTED rules' failing counts are summed (3+2=5), never the REJECTED one's 99: " + company.lastLoadFailing,
			company.lastLoadFailing == 5L);
	}

	private static void noQualityRuleIsTheHonestEmptyState()
	{
		final CockpitData.Snapshot s = CockpitData.compute(CockpitDataTest.inputs());
		CockpitDataTest.check("no quality rule anywhere: an empty list, not a fabricated row", s.quality.isEmpty());
		CockpitDataTest.check("no accepted", s.qualityRulesAccepted == 0);
		CockpitDataTest.check("no pending", s.qualityRulesPending == 0);
	}

	// ------------------------------------------------------------------------- reject/repropose lot (2026-09-22)

	private static void matchingListsOnePerEntityWithAPlan()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.matchPlans.add(CockpitDataTest.matchPlan("Company", "ACCEPTED", null));
		in.matchPlans.add(CockpitDataTest.matchPlan("Contact", "PROPOSED", null));
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("two entities reported", s.matching.size() == 2);
		CockpitDataTest.check("Company is ACCEPTED", "ACCEPTED".equals(CockpitDataTest.matchingOf(s, "Company").status));
		CockpitDataTest.check("Contact is PROPOSED", "PROPOSED".equals(CockpitDataTest.matchingOf(s, "Contact").status));
	}

	/** The comment only ever travels for a REJECTED plan ("Matching: none, by decision") - never for any other status, even if the row happens to carry one (e.g. a stale comment from a prior round). */
	private static void matchingCarriesTheCommentOnlyForRejected()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.matchPlans.add(CockpitDataTest.matchPlan("Company", "REJECTED", "do not cluster on legalForm"));
		in.matchPlans.add(CockpitDataTest.matchPlan("Contact", "SUPERSEDED", "a leftover comment"));
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("Company's comment is carried", "do not cluster on legalForm".equals(CockpitDataTest.matchingOf(s, "Company").comment));
		CockpitDataTest.check("Contact's comment is NOT carried (only REJECTED does)", CockpitDataTest.matchingOf(s, "Contact").comment.isEmpty());
	}

	private static void noMatchPlanIsTheHonestEmptyState()
	{
		final CockpitData.Snapshot s = CockpitData.compute(CockpitDataTest.inputs());
		CockpitDataTest.check("no MatchPlan anywhere: an empty list, not a fabricated row", s.matching.isEmpty());
	}

	// ------------------------------------------------------------------ delivery lot 2 (docs/DELIVERY.md sec 6.2)

	private static void sameInstanceIsNotDelivered()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.instanceId = "a1";
		in.project.originRepository = "a1";
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("same instance: not delivered", !s.delivered);
		CockpitDataTest.check("origin not shown when not delivered", s.originRepository.isEmpty());
	}

	private static void differentInstanceIsDelivered()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.instanceId = "a1";
		in.project.originRepository = "b2";
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("different instance: delivered", s.delivered);
		CockpitDataTest.check("the real origin is carried through, not a placeholder", "b2".equals(s.originRepository));
	}

	private static void unstampedProjectIsNeverDeliveredByAccident()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.instanceId = "a1";
		// project.originRepository defaults to "" (a row written before the field existed): never mistaken for delivered
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("an unstamped project (older row) is never reported as delivered", !s.delivered);
	}

	private static void instanceRolePassesThroughRegardlessOfDelivery()
	{
		final CockpitData.Inputs in = CockpitDataTest.inputs();
		in.instanceRole = "TARGET";
		final CockpitData.Snapshot s = CockpitData.compute(in);
		CockpitDataTest.check("the instance role is shown whether or not this particular project is delivered", "TARGET".equals(s.instanceRole));

		final CockpitData.Inputs noRole = CockpitDataTest.inputs();
		final CockpitData.Snapshot sNoRole = CockpitData.compute(noRole);
		CockpitDataTest.check("a null role (settings row written before the field existed) never NPEs, reads as empty", sNoRole.instanceRole.isEmpty());
	}

	private static CockpitData.MatchEntitySummary matchingOf(final CockpitData.Snapshot pSnapshot, final String pEntityName)
	{
		for (final CockpitData.MatchEntitySummary summary : pSnapshot.matching)
		{
			if (pEntityName.equals(summary.entityName))
			{
				return summary;
			}
		}
		throw new AssertionError("no matching summary for " + pEntityName);
	}

	private static CockpitData.MatchPlanInfo matchPlan(final String pEntityName, final String pStatus, final String pReviewComment)
	{
		final CockpitData.MatchPlanInfo plan = new CockpitData.MatchPlanInfo();
		plan.entityName = pEntityName;
		plan.status = pStatus;
		plan.reviewComment = pReviewComment == null ? "" : pReviewComment;
		return plan;
	}

	private static CockpitData.QualityEntitySummary qualityOf(final CockpitData.Snapshot pSnapshot, final String pEntityName)
	{
		for (final CockpitData.QualityEntitySummary summary : pSnapshot.quality)
		{
			if (pEntityName.equals(summary.entityName))
			{
				return summary;
			}
		}
		throw new AssertionError("no quality summary for " + pEntityName);
	}

	private static CockpitData.QualityRuleInfo qualityRule(final String pEntityName, final String pStatus, final Long pLastLoadFailing)
	{
		final CockpitData.QualityRuleInfo rule = new CockpitData.QualityRuleInfo();
		rule.entityName = pEntityName;
		rule.status = pStatus;
		rule.lastLoadFailing = pLastLoadFailing;
		return rule;
	}

	// ------------------------------------------------------------------ fixtures

	private static CockpitData.Inputs inputs()
	{
		final CockpitData.Inputs in = new CockpitData.Inputs();
		in.now = CockpitDataTest.NOW;
		return in;
	}

	private static Date minutesAgo(final int pMinutes)
	{
		return new Date(CockpitDataTest.NOW.getTime() - pMinutes * 60000L);
	}

	private static CockpitData.StepRow stepRow(final CockpitData.SourceBlock pBlock, final String pStep)
	{
		for (final CockpitData.StepRow row : pBlock.steps)
		{
			if (pStep.equals(row.step))
			{
				return row;
			}
		}
		throw new AssertionError("no row for step " + pStep);
	}

	private static CockpitData.VolumeRow firstOfZone(final List<CockpitData.VolumeRow> pRows, final String pZone)
	{
		for (final CockpitData.VolumeRow row : pRows)
		{
			if (pZone.equals(row.zone))
			{
				return row;
			}
		}
		throw new AssertionError("no volume row for zone " + pZone);
	}

	private static CockpitData.SourceInfo source(final String pSourceSystem)
	{
		final CockpitData.SourceInfo s = new CockpitData.SourceInfo();
		s.id = pSourceSystem + "-id";
		s.sourceSystem = pSourceSystem;
		s.status = "LOADED";
		return s;
	}

	private static CockpitData.TableInfo table(final String pSourceSystem, final String pName, final String pStatus, final Long pSourceRows, final Long pLoadRows)
	{
		final CockpitData.TableInfo t = new CockpitData.TableInfo();
		t.sourceSystem = pSourceSystem;
		t.tableName = pName;
		t.status = pStatus;
		t.sourceRowCount = pSourceRows;
		t.lastLoadRows = pLoadRows;
		if (pLoadRows != null)
		{
			t.lastLoadId = "L-TEST";
		}
		return t;
	}

	private static CockpitData.RelationInfo relation(final String pFrom, final String pTo, final String pStatus)
	{
		final CockpitData.RelationInfo r = new CockpitData.RelationInfo();
		r.fromTable = pFrom;
		r.toTable = pTo;
		r.status = pStatus;
		return r;
	}

	private static CockpitData.StepInfo step(final String pId, final String pStep, final String pScope, final String pStatus, final Date pStartedAt, final Date pEndedAt)
	{
		final CockpitData.StepInfo s = new CockpitData.StepInfo();
		s.id = pId;
		s.step = pStep;
		s.scope = pScope;
		s.status = pStatus;
		s.startedAt = pStartedAt;
		s.endedAt = pEndedAt;
		return s;
	}

	private static CockpitData.EntityInfo entity(final String pName, final String pStatus, final Long pLastLoadWritten, final Date pLastLoadAt)
	{
		final CockpitData.EntityInfo e = new CockpitData.EntityInfo();
		e.name = pName;
		e.status = pStatus;
		e.lastLoadWritten = pLastLoadWritten;
		e.lastLoadAt = pLastLoadAt;
		return e;
	}

	private static CockpitData.RejectInfo reject(final String pEntityName, final String pStage, final boolean pResolved, final Date pRejectedAt)
	{
		final CockpitData.RejectInfo r = new CockpitData.RejectInfo();
		r.entityName = pEntityName;
		r.stage = pStage;
		r.resolved = pResolved;
		r.rejectedAt = pRejectedAt;
		r.sourceSystem = "ERP";
		r.sourceId = "SUP-001";
		r.reasons = "mandatory field missing";
		return r;
	}

	private static int count(final List<CockpitData.VolumeRow> pRows, final String pZone)
	{
		int n = 0;
		for (final CockpitData.VolumeRow row : pRows)
		{
			if (pZone.equals(row.zone))
			{
				n++;
			}
		}
		return n;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
