package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.on.ebx.factory.project.ProjectDeletionPlan.MatchingFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.SourceFacts;
import com.on.ebx.factory.project.StepResetPlan.Disposition;
import com.on.ebx.factory.project.StepResetPlan.Item;
import com.on.ebx.factory.project.StepResetPlan.Kind;
import com.on.ebx.factory.project.StepResetPlan.Plan;
import com.on.ebx.factory.project.StepResetPlan.Stage;

/** JUnit-free: run the main, a failure throws. Fixture reused from {@code ProjectDeletionPlanTest}'s own shape (same Facts/SourceFacts/MatchingFacts). */
public final class StepResetPlanTest
{
	private static final String CODE = "Companies";

	public static void main(final String[] pArgs)
	{
		StepResetPlanTest.matchingOnlyResetsMatching();
		StepResetPlanTest.masterResetIncludesMatchingAndClearsTargetTable();
		StepResetPlanTest.stagingResetIncludesMasterAndMatching();
		StepResetPlanTest.landingResetIncludesEverything();
		StepResetPlanTest.executionOrderMatchingBeforeMasterBeforeStaging();
		StepResetPlanTest.deliveredProjectRefused();
		StepResetPlanTest.foreignDataspaceNameRefused();
		StepResetPlanTest.noStepEvidenceRefused();
		StepResetPlanTest.targetExistsMasterExcludedNotRefused();
		StepResetPlanTest.targetExistsMatchingStillDeleted();
		StepResetPlanTest.blankStoredNamesAreAbsent();
		StepResetPlanTest.stepNamesPureFunctionOfStage();
		StepResetPlanTest.validateWrongConfirmRefused();
		StepResetPlanTest.validateRefusalWinsOverConfirm();
		StepResetPlanTest.validateAlignedAllowed();
		StepResetPlanTest.landingResetTargetsTheSourceDrifts();

		System.out.println("StepResetPlanTest OK");
	}

	// -------------------------------------------------------------------------------------------------------- scope

	private static void matchingOnlyResetsMatching()
	{
		final Plan plan = StepResetPlan.build(Stage.MATCHING, StepResetPlanTest.fullyEligibleFacts());
		StepResetPlanTest.check("only MATCHING_CONFIG items", StepResetPlanTest.onlyKinds(plan, Kind.MATCHING_CONFIG));
		StepResetPlanTest.check("no refusal", plan.refusal == null);
		StepResetPlanTest.check("step names: only the MATCHING vocabulary", plan.stepNames.contains("MATCHING_CONFIG") && !plan.stepNames.contains("MASTER_MODEL")
			&& !plan.stepNames.contains("STAGING_MODEL") && !plan.stepNames.contains("LANDING_MODEL"));
	}

	private static void masterResetIncludesMatchingAndClearsTargetTable()
	{
		final Plan plan = StepResetPlan.build(Stage.MASTER, StepResetPlanTest.fullyEligibleFacts());
		StepResetPlanTest.check("has matching + master items", StepResetPlanTest.itemOf(plan, Kind.MATCHING_CONFIG) != null
			&& StepResetPlanTest.itemOf(plan, Kind.MASTER_DATASPACE) != null && StepResetPlanTest.itemOf(plan, Kind.MASTER_MODEL) != null);
		StepResetPlanTest.check("no staging/landing items", StepResetPlanTest.itemOf(plan, Kind.STAGING_DATASPACE) == null
			&& StepResetPlanTest.itemOf(plan, Kind.LANDING_DATASPACE) == null);
		StepResetPlanTest.check("step names include MASTER_MODEL/DATASET/LOAD", plan.stepNames.contains("MASTER_MODEL") && plan.stepNames.contains("MASTER_DATASET")
			&& plan.stepNames.contains("MASTER_LOAD"));
		StepResetPlanTest.check("step names include CAPABILITY_APPLY/REMOVE (zone MASTER resolved by StepResetter)",
			plan.stepNames.contains("CAPABILITY_APPLY") && plan.stepNames.contains("CAPABILITY_REMOVE"));
		StepResetPlanTest.check("step names do NOT include STAGING_* (StagingEntity rows survive a MASTER-only reset)", !plan.stepNames.contains("STAGING_LOAD"));
	}

	private static void stagingResetIncludesMasterAndMatching()
	{
		final Plan plan = StepResetPlan.build(Stage.STAGING, StepResetPlanTest.fullyEligibleFacts());
		StepResetPlanTest.check("has matching + master + staging items",
			StepResetPlanTest.itemOf(plan, Kind.MATCHING_CONFIG) != null && StepResetPlanTest.itemOf(plan, Kind.MASTER_DATASPACE) != null
				&& StepResetPlanTest.itemOf(plan, Kind.STAGING_DATASPACE) != null && StepResetPlanTest.itemOf(plan, Kind.STAGING_MODEL) != null);
		StepResetPlanTest.check("no landing items", StepResetPlanTest.itemOf(plan, Kind.LANDING_DATASPACE) == null
			&& StepResetPlanTest.itemOf(plan, Kind.LANDING_FLOW) == null);
		StepResetPlanTest.check("step names include STAGING_SUGGEST/MODEL/DATASET/LOAD",
			plan.stepNames.contains("STAGING_SUGGEST") && plan.stepNames.contains("STAGING_MODEL") && plan.stepNames.contains("STAGING_LOAD"));
	}

	private static void landingResetIncludesEverything()
	{
		final Plan plan = StepResetPlan.build(Stage.LANDING, StepResetPlanTest.fullyEligibleFacts());
		StepResetPlanTest.check("has every kind", StepResetPlanTest.itemOf(plan, Kind.MATCHING_CONFIG) != null && StepResetPlanTest.itemOf(plan, Kind.MASTER_DATASPACE) != null
			&& StepResetPlanTest.itemOf(plan, Kind.STAGING_DATASPACE) != null && StepResetPlanTest.itemOf(plan, Kind.LANDING_DATASPACE) != null
			&& StepResetPlanTest.itemOf(plan, Kind.LANDING_MODEL) != null && StepResetPlanTest.itemOf(plan, Kind.LANDING_FLOW) != null);
		StepResetPlanTest.check("step names include LANDING_MODEL/DATASET/FLOWS/LOAD", plan.stepNames.contains("LANDING_MODEL") && plan.stepNames.contains("LANDING_DATASET")
			&& plan.stepNames.contains("LANDING_FLOWS") && plan.stepNames.contains("LANDING_LOAD"));
	}

	private static void executionOrderMatchingBeforeMasterBeforeStaging()
	{
		final Plan plan = StepResetPlan.build(Stage.LANDING, StepResetPlanTest.fullyEligibleFacts());
		final List<Kind> order = new ArrayList<Kind>();
		for (final Item item : plan.items)
		{
			order.add(item.kind);
		}
		StepResetPlanTest.check("matching before master dataspace", order.indexOf(Kind.MATCHING_CONFIG) < order.indexOf(Kind.MASTER_DATASPACE));
		StepResetPlanTest.check("master dataspace before master model", order.indexOf(Kind.MASTER_DATASPACE) < order.indexOf(Kind.MASTER_MODEL));
		StepResetPlanTest.check("master model before staging dataspace", order.indexOf(Kind.MASTER_MODEL) < order.indexOf(Kind.STAGING_DATASPACE));
		StepResetPlanTest.check("staging dataspace before staging model", order.indexOf(Kind.STAGING_DATASPACE) < order.indexOf(Kind.STAGING_MODEL));
		StepResetPlanTest.check("staging model before landing flows", order.indexOf(Kind.STAGING_MODEL) < order.indexOf(Kind.LANDING_FLOW));
		StepResetPlanTest.check("landing flows before landing dataspace", order.indexOf(Kind.LANDING_FLOW) < order.indexOf(Kind.LANDING_DATASPACE));
		StepResetPlanTest.check("landing dataspace before landing model", order.indexOf(Kind.LANDING_DATASPACE) < order.indexOf(Kind.LANDING_MODEL));
	}

	// -------------------------------------------------------------------------------------------------------- refusal

	private static void deliveredProjectRefused()
	{
		final ProjectDeletionPlan.Facts base = StepResetPlanTest.fullyEligibleFacts().base;
		final ProjectDeletionPlan.Facts delivered = new ProjectDeletionPlan.Facts(base.projectCode, base.mode, true, base.landingDataspaceStored,
			base.landingDataspaceComputed, base.stagingDataspaceStored, base.stagingDatasetStored, base.stagingDataspaceComputed, base.stagingDatasetComputed,
			base.stagingModelStepDone, base.stagingDatasetCreatedByFactory, base.masterDataspaceStored, base.masterDatasetStored, base.masterDataspaceComputed,
			base.masterDatasetComputed, base.masterModelStepDone, base.masterDatasetCreatedByFactory, base.sources, base.matching, base.masterModelName,
			base.stagingModelName, base.rowCounts);
		final Plan plan = StepResetPlan.build(Stage.STAGING, new StepResetPlan.Facts(delivered, new LinkedHashMap<String, Integer>()));
		StepResetPlanTest.check("delivered: whole plan refused", plan.refusal != null);
		StepResetPlanTest.check("refusal names delivery", plan.refusal.toLowerCase(java.util.Locale.ENGLISH).contains("delivered"));
	}

	private static void foreignDataspaceNameRefused()
	{
		final ProjectDeletionPlan.Facts base = StepResetPlanTest.fullyEligibleFacts().base;
		final ProjectDeletionPlan.Facts foreign = new ProjectDeletionPlan.Facts(base.projectCode, base.mode, false, base.landingDataspaceStored,
			base.landingDataspaceComputed, "TestStaging", "TestStagingDs", base.stagingDataspaceComputed, base.stagingDatasetComputed, base.stagingModelStepDone,
			base.stagingDatasetCreatedByFactory, base.masterDataspaceStored, base.masterDatasetStored, base.masterDataspaceComputed, base.masterDatasetComputed,
			base.masterModelStepDone, base.masterDatasetCreatedByFactory, base.sources, base.matching, base.masterModelName, base.stagingModelName, base.rowCounts);
		final Plan plan = StepResetPlan.build(Stage.STAGING, new StepResetPlan.Facts(foreign, new LinkedHashMap<String, Integer>()));
		StepResetPlanTest.check("foreign staging dataspace name: whole plan refused", plan.refusal != null);
		final Item staging = StepResetPlanTest.itemOf(plan, Kind.STAGING_DATASPACE);
		StepResetPlanTest.check("item itself is REFUSED, not just the plan", staging.disposition == Disposition.REFUSED);
		StepResetPlanTest.check("reason names both values", staging.reason.contains("TestStaging") && staging.reason.contains(base.stagingDataspaceComputed));
	}

	private static void noStepEvidenceRefused()
	{
		final ProjectDeletionPlan.Facts base = StepResetPlanTest.fullyEligibleFacts().base;
		final ProjectDeletionPlan.Facts noEvidence = new ProjectDeletionPlan.Facts(base.projectCode, base.mode, false, base.landingDataspaceStored,
			base.landingDataspaceComputed, base.stagingDataspaceStored, base.stagingDatasetStored, base.stagingDataspaceComputed, base.stagingDatasetComputed,
			base.stagingModelStepDone, false, base.masterDataspaceStored, base.masterDatasetStored, base.masterDataspaceComputed, base.masterDatasetComputed,
			base.masterModelStepDone, base.masterDatasetCreatedByFactory, base.sources, base.matching, base.masterModelName, base.stagingModelName, base.rowCounts);
		final Plan plan = StepResetPlan.build(Stage.STAGING, new StepResetPlan.Facts(noEvidence, new LinkedHashMap<String, Integer>()));
		StepResetPlanTest.check("no creation evidence: whole plan refused", plan.refusal != null);
	}

	private static void targetExistsMasterExcludedNotRefused()
	{
		final ProjectDeletionPlan.Facts base = StepResetPlanTest.fullyEligibleFacts().base;
		final ProjectDeletionPlan.Facts targetExists = new ProjectDeletionPlan.Facts(base.projectCode, "TARGET_EXISTS", false, base.landingDataspaceStored,
			base.landingDataspaceComputed, base.stagingDataspaceStored, base.stagingDatasetStored, base.stagingDataspaceComputed, base.stagingDatasetComputed,
			base.stagingModelStepDone, base.stagingDatasetCreatedByFactory, base.masterDataspaceStored, base.masterDatasetStored, base.masterDataspaceComputed,
			base.masterDatasetComputed, base.masterModelStepDone, base.masterDatasetCreatedByFactory, base.sources, base.matching, base.masterModelName,
			base.stagingModelName, base.rowCounts);
		final Plan plan = StepResetPlan.build(Stage.MASTER, new StepResetPlan.Facts(targetExists, new LinkedHashMap<String, Integer>()));
		StepResetPlanTest.check("TARGET_EXISTS master zone: no refusal (excluded, not refused)", plan.refusal == null);
		final Item masterDs = StepResetPlanTest.itemOf(plan, Kind.MASTER_DATASPACE);
		final Item masterModel = StepResetPlanTest.itemOf(plan, Kind.MASTER_MODEL);
		StepResetPlanTest.check("master dataspace EXCLUDED", masterDs.disposition == Disposition.EXCLUDED);
		StepResetPlanTest.check("master model EXCLUDED", masterModel.disposition == Disposition.EXCLUDED);
		StepResetPlanTest.check("reason names TARGET_EXISTS", masterDs.reason.contains("TARGET_EXISTS"));
	}

	private static void targetExistsMatchingStillDeleted()
	{
		final ProjectDeletionPlan.Facts base = StepResetPlanTest.fullyEligibleFacts().base;
		final ProjectDeletionPlan.Facts targetExists = new ProjectDeletionPlan.Facts(base.projectCode, "TARGET_EXISTS", false, base.landingDataspaceStored,
			base.landingDataspaceComputed, base.stagingDataspaceStored, base.stagingDatasetStored, base.stagingDataspaceComputed, base.stagingDatasetComputed,
			base.stagingModelStepDone, base.stagingDatasetCreatedByFactory, base.masterDataspaceStored, base.masterDatasetStored, base.masterDataspaceComputed,
			base.masterDatasetComputed, base.masterModelStepDone, base.masterDatasetCreatedByFactory, base.sources, base.matching, base.masterModelName,
			base.stagingModelName, base.rowCounts);
		final Plan plan = StepResetPlan.build(Stage.MATCHING, new StepResetPlan.Facts(targetExists, new LinkedHashMap<String, Integer>()));
		final Item matching = StepResetPlanTest.itemOf(plan, Kind.MATCHING_CONFIG);
		StepResetPlanTest.check("matching config still DELETE under TARGET_EXISTS - the product owner: "
			+ "\"zone MDM jamais supprimee, mais les lignes de la factory et la config matching le sont\"", matching.disposition == Disposition.DELETE);
		StepResetPlanTest.check("no refusal", plan.refusal == null);
	}

	private static void blankStoredNamesAreAbsent()
	{
		final ProjectDeletionPlan.Facts f = new ProjectDeletionPlan.Facts(StepResetPlanTest.CODE, "GREENFIELD", false, "", "Companies_Landing", "", "",
			"Companies_Staging", "Staging", false, false, "", "", "Companies_Master", "Master", false, false, new ArrayList<SourceFacts>(),
			new ArrayList<MatchingFacts>(), "Companies_Master", "Companies_Staging", null);
		final Plan plan = StepResetPlan.build(Stage.LANDING, new StepResetPlan.Facts(f, new LinkedHashMap<String, Integer>()));
		StepResetPlanTest.check("no items at all when nothing was ever generated", plan.items.isEmpty());
		StepResetPlanTest.check("no refusal either", plan.refusal == null);
	}

	private static void stepNamesPureFunctionOfStage()
	{
		StepResetPlanTest.check("MATCHING step names", StepResetPlan.stepNames(Stage.MATCHING).size() == 6);
		StepResetPlanTest.check("MASTER step names superset of MATCHING's", StepResetPlan.stepNames(Stage.MASTER).containsAll(StepResetPlan.stepNames(Stage.MATCHING)));
		StepResetPlanTest.check("STAGING step names superset of MASTER's", StepResetPlan.stepNames(Stage.STAGING).containsAll(StepResetPlan.stepNames(Stage.MASTER)));
		StepResetPlanTest.check("LANDING step names superset of STAGING's", StepResetPlan.stepNames(Stage.LANDING).containsAll(StepResetPlan.stepNames(Stage.STAGING)));
	}

	// ------------------------------------------------------------------------------------------------------ validate

	private static void validateWrongConfirmRefused()
	{
		final Plan plan = StepResetPlan.build(Stage.STAGING, StepResetPlanTest.fullyEligibleFacts());
		final String refusal = StepResetPlan.validate(plan, "SomethingElse");
		StepResetPlanTest.check("wrong confirm refused", refusal != null);
		final String missing = StepResetPlan.validate(plan, null);
		StepResetPlanTest.check("missing confirm refused", missing != null);
	}

	private static void validateRefusalWinsOverConfirm()
	{
		final ProjectDeletionPlan.Facts base = StepResetPlanTest.fullyEligibleFacts().base;
		final ProjectDeletionPlan.Facts delivered = new ProjectDeletionPlan.Facts(base.projectCode, base.mode, true, base.landingDataspaceStored,
			base.landingDataspaceComputed, base.stagingDataspaceStored, base.stagingDatasetStored, base.stagingDataspaceComputed, base.stagingDatasetComputed,
			base.stagingModelStepDone, base.stagingDatasetCreatedByFactory, base.masterDataspaceStored, base.masterDatasetStored, base.masterDataspaceComputed,
			base.masterDatasetComputed, base.masterModelStepDone, base.masterDatasetCreatedByFactory, base.sources, base.matching, base.masterModelName,
			base.stagingModelName, base.rowCounts);
		final Plan plan = StepResetPlan.build(Stage.STAGING, new StepResetPlan.Facts(delivered, new LinkedHashMap<String, Integer>()));
		// even with the EXACT right confirm text, a delivered project's refusal still wins
		final String refusal = StepResetPlan.validate(plan, StepResetPlanTest.CODE);
		StepResetPlanTest.check("build-time refusal always wins over a correct confirm", refusal != null && refusal.toLowerCase(java.util.Locale.ENGLISH).contains("delivered"));
	}

	private static void validateAlignedAllowed()
	{
		final Plan plan = StepResetPlan.build(Stage.STAGING, StepResetPlanTest.fullyEligibleFacts());
		final String refusal = StepResetPlan.validate(plan, StepResetPlanTest.CODE);
		StepResetPlanTest.check("fully eligible facts + right confirm: allowed", refusal == null);
	}

	// ------------------------------------------------------------------------------------------------------- fixtures

	/** Same shape as {@code ProjectDeletionPlanTest#fullyEligibleFacts} - one source, one promoted+configured entity, everything created by the factory. */
	private static StepResetPlan.Facts fullyEligibleFacts()
	{
		final List<SourceFacts> sources = new ArrayList<SourceFacts>();
		sources.add(new SourceFacts("ERP", "Companies_Landing_ERP", "ERP", true, true, StepResetPlanTest.list("m1", "m2")));
		final List<MatchingFacts> matching = new ArrayList<MatchingFacts>();
		matching.add(new MatchingFacts("Company", "/root/Company", true));
		final ProjectDeletionPlan.Facts base = new ProjectDeletionPlan.Facts(StepResetPlanTest.CODE, "GREENFIELD", false, "Companies_Landing", "Companies_Landing",
			"Companies_Staging", "Staging", "Companies_Staging", "Staging", true, true, "Companies_Master", "Master", "Companies_Master", "Master", true, true, sources,
			matching, "Companies_Master", "Companies_Staging", null);
		return new StepResetPlan.Facts(base, new LinkedHashMap<String, Integer>());
	}

	private static List<String> list(final String... pValues)
	{
		final List<String> out = new ArrayList<String>();
		for (final String value : pValues)
		{
			out.add(value);
		}
		return out;
	}

	private static boolean onlyKinds(final Plan pPlan, final Kind pKind)
	{
		for (final Item item : pPlan.items)
		{
			if (item.kind != pKind)
			{
				return false;
			}
		}
		return !pPlan.items.isEmpty();
	}

	private static Item itemOf(final Plan pPlan, final Kind pKind)
	{
		for (final Item item : pPlan.items)
		{
			if (item.kind == pKind)
			{
				return item;
			}
		}
		return null;
	}

	/** Audit F3-10: a landing reset removes the drifts of its landing tables (before the tables); a reset that keeps the landing keeps them. */
	private static void landingResetTargetsTheSourceDrifts()
	{
		final List<String> landing = StepResetPlan.rowTables(Stage.LANDING);
		StepResetPlanTest.check("landing reset names SourceDrift: " + landing, landing.contains("SourceDrift"));
		StepResetPlanTest.check("SourceDrift before LandingTable", landing.indexOf("SourceDrift") < landing.indexOf("LandingTable"));
		StepResetPlanTest.check("LandingColumn before LandingTable", landing.indexOf("LandingColumn") < landing.indexOf("LandingTable"));
		StepResetPlanTest.check("a staging reset keeps the drifts", !StepResetPlan.rowTables(Stage.STAGING).contains("SourceDrift"));
		StepResetPlanTest.check("a master reset keeps the landing", !StepResetPlan.rowTables(Stage.MASTER).contains("LandingTable") && !StepResetPlan.rowTables(Stage.MASTER).contains("SourceDrift"));
		StepResetPlanTest.check("the agents' history is not reset", !landing.contains("AgentProposal") && !landing.contains("AgentRun") && !landing.contains("AgentDefinition"));
		StepResetPlanTest.check("a landing reset still holds the staging tables", landing.contains("StagingEntity") && landing.contains("MatchPlan") && landing.contains("TransferReject"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
