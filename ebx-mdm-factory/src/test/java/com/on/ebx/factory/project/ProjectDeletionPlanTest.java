package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.project.ProjectDeletionPlan.Facts;
import com.on.ebx.factory.project.ProjectDeletionPlan.Item;
import com.on.ebx.factory.project.ProjectDeletionPlan.Kind;
import com.on.ebx.factory.project.ProjectDeletionPlan.MatchingFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.Plan;
import com.on.ebx.factory.project.ProjectDeletionPlan.SourceFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.TargetFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.TargetFlowFacts;

/** JUnit-free: run the main, a failure throws. */
public final class ProjectDeletionPlanTest
{
	private static final String CODE = "Companies";

	public static void main(final String[] pArgs)
	{
		ProjectDeletionPlanTest.deliveredProjectEveryItemIneligible();
		ProjectDeletionPlanTest.foreignDataspaceNamesIneligible();
		ProjectDeletionPlanTest.noStepEvidenceIneligible();
		ProjectDeletionPlanTest.targetExistsMasterIneligible();
		ProjectDeletionPlanTest.fullyEligibleGreenfieldProject();
		ProjectDeletionPlanTest.sharedLandingDataspaceEligibleWhenOnlyOneSourceCreatedIt();
		ProjectDeletionPlanTest.matchingDependsOnMasterDataspaceEligibility();
		ProjectDeletionPlanTest.matchingIneligibleWhenNotConfigured();
		ProjectDeletionPlanTest.blankStoredNamesAreAbsentNotIneligible();
		ProjectDeletionPlanTest.flowOfAnotherProjectIneligible();
		ProjectDeletionPlanTest.unreadableFlowIneligibleGoneFlowEligible();
		ProjectDeletionPlanTest.distributionTargetFlowsAreOfferedWithTheFlowsOption();
		ProjectDeletionPlanTest.distributionFlowOfADeliveredProjectIneligible();
		ProjectDeletionPlanTest.distributionFlowOfAnotherProjectOrTargetIneligible();
		ProjectDeletionPlanTest.distributionFlowUnreadableIneligibleAbsentOrUndatedEligible();
		ProjectDeletionPlanTest.validateUnknownOptionRefused();
		ProjectDeletionPlanTest.validateWrongConfirmRefused();
		ProjectDeletionPlanTest.validateMissingConfirmRefused();
		ProjectDeletionPlanTest.validateDataspacesWithoutMatchingRefusedWhenOrphaning();
		ProjectDeletionPlanTest.validateDataspacesWithoutMatchingAllowedWhenNothingToOrphan();
		ProjectDeletionPlanTest.validateEverythingAlignedAllowed();
		ProjectDeletionPlanTest.agentTablesAreDeletedChildrenFirst();

		System.out.println("ProjectDeletionPlanTest OK");
	}

	// -------------------------------------------------------------------------------------------------- eligibility

	private static void deliveredProjectEveryItemIneligible()
	{
		final Facts facts = ProjectDeletionPlanTest.fullyEligibleFacts();
		final Facts delivered = new Facts(facts.projectCode, facts.mode, true, facts.landingDataspaceStored, facts.landingDataspaceComputed, facts.stagingDataspaceStored,
			facts.stagingDatasetStored, facts.stagingDataspaceComputed, facts.stagingDatasetComputed, facts.stagingModelStepDone, facts.stagingDatasetCreatedByFactory,
			facts.masterDataspaceStored, facts.masterDatasetStored, facts.masterDataspaceComputed, facts.masterDatasetComputed, facts.masterModelStepDone,
			facts.masterDatasetCreatedByFactory, facts.sources, facts.matching, facts.masterModelName, facts.stagingModelName, facts.rowCounts);
		final Plan plan = ProjectDeletionPlan.build(delivered);
		ProjectDeletionPlanTest.check("at least one item", !plan.items.isEmpty());
		for (final Item item : plan.items)
		{
			ProjectDeletionPlanTest.check("every item ineligible on a delivered project: " + item.kind, !item.eligible);
			ProjectDeletionPlanTest.check("reason names delivery: " + item.kind, item.reason.toLowerCase(java.util.Locale.ENGLISH).contains("delivered"));
		}
	}

	private static void foreignDataspaceNamesIneligible()
	{
		final Facts facts = ProjectDeletionPlanTest.fullyEligibleFacts();
		final Facts foreign = new Facts(facts.projectCode, facts.mode, false, facts.landingDataspaceStored, facts.landingDataspaceComputed, "TestStaging",
			"TestStagingDs", facts.stagingDataspaceComputed, facts.stagingDatasetComputed, facts.stagingModelStepDone, facts.stagingDatasetCreatedByFactory,
			facts.masterDataspaceStored, facts.masterDatasetStored, facts.masterDataspaceComputed, facts.masterDatasetComputed, facts.masterModelStepDone,
			facts.masterDatasetCreatedByFactory, facts.sources, facts.matching, facts.masterModelName, facts.stagingModelName, facts.rowCounts);
		final Plan plan = ProjectDeletionPlan.build(foreign);
		final Item staging = ProjectDeletionPlanTest.itemOf(plan, Kind.STAGING_DATASPACE);
		ProjectDeletionPlanTest.check("foreign staging dataspace ineligible", !staging.eligible);
		ProjectDeletionPlanTest.check("reason names the hand-typed value and the computed one",
			staging.reason.contains("TestStaging") && staging.reason.contains(facts.stagingDataspaceComputed));
	}

	private static void noStepEvidenceIneligible()
	{
		final Facts facts = ProjectDeletionPlanTest.fullyEligibleFacts();
		final Facts noEvidence = new Facts(facts.projectCode, facts.mode, false, facts.landingDataspaceStored, facts.landingDataspaceComputed,
			facts.stagingDataspaceStored, facts.stagingDatasetStored, facts.stagingDataspaceComputed, facts.stagingDatasetComputed, facts.stagingModelStepDone, false,
			facts.masterDataspaceStored, facts.masterDatasetStored, facts.masterDataspaceComputed, facts.masterDatasetComputed, facts.masterModelStepDone,
			facts.masterDatasetCreatedByFactory, facts.sources, facts.matching, facts.masterModelName, facts.stagingModelName, facts.rowCounts);
		final Plan plan = ProjectDeletionPlan.build(noEvidence);
		final Item staging = ProjectDeletionPlanTest.itemOf(plan, Kind.STAGING_DATASPACE);
		ProjectDeletionPlanTest.check("no creation evidence: ineligible", !staging.eligible);
		ProjectDeletionPlanTest.check("reason names the missing evidence", staging.reason.toLowerCase(java.util.Locale.ENGLISH).contains("pipelinestep")
			|| staging.reason.toLowerCase(java.util.Locale.ENGLISH).contains("evidence"));
	}

	private static void targetExistsMasterIneligible()
	{
		final Facts facts = ProjectDeletionPlanTest.fullyEligibleFacts();
		final Facts targetExists = new Facts(facts.projectCode, "TARGET_EXISTS", false, facts.landingDataspaceStored, facts.landingDataspaceComputed,
			facts.stagingDataspaceStored, facts.stagingDatasetStored, facts.stagingDataspaceComputed, facts.stagingDatasetComputed, facts.stagingModelStepDone,
			facts.stagingDatasetCreatedByFactory, facts.masterDataspaceStored, facts.masterDatasetStored, facts.masterDataspaceComputed, facts.masterDatasetComputed,
			facts.masterModelStepDone, facts.masterDatasetCreatedByFactory, facts.sources, facts.matching, facts.masterModelName, facts.stagingModelName, facts.rowCounts);
		final Plan plan = ProjectDeletionPlan.build(targetExists);
		final Item masterDs = ProjectDeletionPlanTest.itemOf(plan, Kind.MASTER_DATASPACE);
		final Item masterModel = ProjectDeletionPlanTest.itemOf(plan, Kind.MASTER_MODEL);
		ProjectDeletionPlanTest.check("TARGET_EXISTS master dataspace ineligible", !masterDs.eligible);
		ProjectDeletionPlanTest.check("TARGET_EXISTS master model ineligible", !masterModel.eligible);
		ProjectDeletionPlanTest.check("reason names TARGET_EXISTS", masterDs.reason.contains("TARGET_EXISTS"));
		// staging/landing are untouched by the TARGET_EXISTS gate (only the master zone is not the factory's)
		final Item staging = ProjectDeletionPlanTest.itemOf(plan, Kind.STAGING_DATASPACE);
		ProjectDeletionPlanTest.check("staging dataspace still eligible under TARGET_EXISTS", staging.eligible);
	}

	private static void fullyEligibleGreenfieldProject()
	{
		final Facts facts = ProjectDeletionPlanTest.fullyEligibleFacts();
		final Plan plan = ProjectDeletionPlan.build(facts);
		for (final Item item : plan.items)
		{
			ProjectDeletionPlanTest.check("eligible: " + item.kind + " " + item.name, item.eligible);
			ProjectDeletionPlanTest.check("no reason on an eligible item", item.reason == null);
		}
		// execution order: matching, flows, dataspaces (master/staging/landing), models (master/staging/landing)
		final List<Kind> order = new ArrayList<Kind>();
		for (final Item item : plan.items)
		{
			order.add(item.kind);
		}
		ProjectDeletionPlanTest.check("matching before flows", order.indexOf(Kind.MATCHING_CONFIG) < order.indexOf(Kind.LANDING_FLOW));
		ProjectDeletionPlanTest.check("flows before master dataspace", order.indexOf(Kind.LANDING_FLOW) < order.indexOf(Kind.MASTER_DATASPACE));
		ProjectDeletionPlanTest.check("master dataspace before staging dataspace", order.indexOf(Kind.MASTER_DATASPACE) < order.indexOf(Kind.STAGING_DATASPACE));
		ProjectDeletionPlanTest.check("staging dataspace before landing dataspace", order.indexOf(Kind.STAGING_DATASPACE) < order.indexOf(Kind.LANDING_DATASPACE));
		ProjectDeletionPlanTest.check("landing dataspace before master model", order.indexOf(Kind.LANDING_DATASPACE) < order.indexOf(Kind.MASTER_MODEL));
		ProjectDeletionPlanTest.check("master model before staging model", order.indexOf(Kind.MASTER_MODEL) < order.indexOf(Kind.STAGING_MODEL));
		ProjectDeletionPlanTest.check("staging model before landing model", order.indexOf(Kind.STAGING_MODEL) < order.indexOf(Kind.LANDING_MODEL));
		ProjectDeletionPlanTest.check("row counts carried through unchanged", plan.rowCounts.get("Project").intValue() == 1);
	}

	private static void sharedLandingDataspaceEligibleWhenOnlyOneSourceCreatedIt()
	{
		final Facts facts = ProjectDeletionPlanTest.fullyEligibleFacts();
		final List<SourceFacts> twoSources = new ArrayList<SourceFacts>();
		twoSources.add(new SourceFacts("ERP", "Companies_Landing_ERP", "ERP", true, true, ProjectDeletionPlanTest.list("m1")));
		// the second source's own step recorded created:false (the dataspace already existed from the first source)
		twoSources.add(new SourceFacts("CRM", "Companies_Landing_CRM", "CRM", true, false, ProjectDeletionPlanTest.list("m2")));
		final Facts twoSourceFacts = new Facts(facts.projectCode, facts.mode, false, facts.landingDataspaceStored, facts.landingDataspaceComputed,
			facts.stagingDataspaceStored, facts.stagingDatasetStored, facts.stagingDataspaceComputed, facts.stagingDatasetComputed, facts.stagingModelStepDone,
			facts.stagingDatasetCreatedByFactory, facts.masterDataspaceStored, facts.masterDatasetStored, facts.masterDataspaceComputed, facts.masterDatasetComputed,
			facts.masterModelStepDone, facts.masterDatasetCreatedByFactory, twoSources, facts.matching, facts.masterModelName, facts.stagingModelName, facts.rowCounts);
		final Plan plan = ProjectDeletionPlan.build(twoSourceFacts);
		final Item landing = ProjectDeletionPlanTest.itemOf(plan, Kind.LANDING_DATASPACE);
		ProjectDeletionPlanTest.check("landing dataspace eligible: ANY source recorded created:true", landing.eligible);
		int landingModelCount = 0;
		for (final Item item : plan.items)
		{
			if (item.kind == Kind.LANDING_MODEL)
			{
				landingModelCount++;
			}
		}
		ProjectDeletionPlanTest.check("one LANDING_MODEL item per source", landingModelCount == 2);
		int flowCount = 0;
		for (final Item item : plan.items)
		{
			if (item.kind == Kind.LANDING_FLOW)
			{
				flowCount++;
			}
		}
		ProjectDeletionPlanTest.check("one flow item per mapping id across both sources", flowCount == 2);
	}

	private static void matchingDependsOnMasterDataspaceEligibility()
	{
		final Facts facts = ProjectDeletionPlanTest.fullyEligibleFacts();
		// foreign master dataspace name -> the master dataspace item is ineligible -> matching must follow
		final Facts foreignMaster = new Facts(facts.projectCode, facts.mode, false, facts.landingDataspaceStored, facts.landingDataspaceComputed,
			facts.stagingDataspaceStored, facts.stagingDatasetStored, facts.stagingDataspaceComputed, facts.stagingDatasetComputed, facts.stagingModelStepDone,
			facts.stagingDatasetCreatedByFactory, "TestMaster", "TestMasterDs", facts.masterDataspaceComputed, facts.masterDatasetComputed, facts.masterModelStepDone,
			facts.masterDatasetCreatedByFactory, facts.sources, facts.matching, facts.masterModelName, facts.stagingModelName, facts.rowCounts);
		final Plan plan = ProjectDeletionPlan.build(foreignMaster);
		final Item matching = ProjectDeletionPlanTest.itemOf(plan, Kind.MATCHING_CONFIG);
		ProjectDeletionPlanTest.check("matching ineligible when its master dataspace is ineligible", !matching.eligible);
		ProjectDeletionPlanTest.check("reason references the master zone", matching.reason.contains("master zone"));
	}

	private static void matchingIneligibleWhenNotConfigured()
	{
		final Facts facts = ProjectDeletionPlanTest.fullyEligibleFacts();
		final List<MatchingFacts> notConfigured = new ArrayList<MatchingFacts>();
		notConfigured.add(new MatchingFacts("Company", "/root/Company", false));
		final Facts f = new Facts(facts.projectCode, facts.mode, false, facts.landingDataspaceStored, facts.landingDataspaceComputed, facts.stagingDataspaceStored,
			facts.stagingDatasetStored, facts.stagingDataspaceComputed, facts.stagingDatasetComputed, facts.stagingModelStepDone, facts.stagingDatasetCreatedByFactory,
			facts.masterDataspaceStored, facts.masterDatasetStored, facts.masterDataspaceComputed, facts.masterDatasetComputed, facts.masterModelStepDone,
			facts.masterDatasetCreatedByFactory, facts.sources, notConfigured, facts.masterModelName, facts.stagingModelName, facts.rowCounts);
		final Plan plan = ProjectDeletionPlan.build(f);
		final Item matching = ProjectDeletionPlanTest.itemOf(plan, Kind.MATCHING_CONFIG);
		ProjectDeletionPlanTest.check("not configured: ineligible", !matching.eligible);
		ProjectDeletionPlanTest.check("reason says nothing to delete", matching.reason.contains("nothing to delete"));
	}

	private static void blankStoredNamesAreAbsentNotIneligible()
	{
		final Facts f = new Facts(ProjectDeletionPlanTest.CODE, "GREENFIELD", false, "", "Companies_Landing", "", "", "Companies_Staging", "Staging", false, false, "",
			"", "Companies_Master", "Master", false, false, new ArrayList<SourceFacts>(), new ArrayList<MatchingFacts>(), "Companies_Master", "Companies_Staging",
			new LinkedHashMap<String, Integer>());
		final Plan plan = ProjectDeletionPlan.build(f);
		ProjectDeletionPlanTest.check("no items at all when nothing was ever generated", plan.items.isEmpty());
	}

	// ------------------------------------------------------------------------------------------------------ validate

	private static void validateUnknownOptionRefused()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionPlanTest.fullyEligibleFacts());
		final String refusal = ProjectDeletionPlan.validate(plan, ProjectDeletionPlanTest.set("bogus"), ProjectDeletionPlanTest.CODE);
		ProjectDeletionPlanTest.check("unknown option refused", refusal != null);
		ProjectDeletionPlanTest.check("names the bad option", refusal.contains("bogus"));
	}

	private static void validateWrongConfirmRefused()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionPlanTest.fullyEligibleFacts());
		final String refusal = ProjectDeletionPlan.validate(plan, new LinkedHashSet<String>(), "SomethingElse");
		ProjectDeletionPlanTest.check("wrong confirm refused", refusal != null);
	}

	private static void validateMissingConfirmRefused()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionPlanTest.fullyEligibleFacts());
		final String refusal = ProjectDeletionPlan.validate(plan, new LinkedHashSet<String>(), null);
		ProjectDeletionPlanTest.check("missing confirm refused", refusal != null);
		ProjectDeletionPlanTest.check("names the exact confirm value", refusal.contains("confirm=" + ProjectDeletionPlanTest.CODE));
	}

	private static void validateDataspacesWithoutMatchingRefusedWhenOrphaning()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionPlanTest.fullyEligibleFacts());
		final String refusal = ProjectDeletionPlan.validate(plan, ProjectDeletionPlanTest.set(ProjectDeletionPlan.OPTION_DATASPACES), ProjectDeletionPlanTest.CODE);
		ProjectDeletionPlanTest.check("dataspaces without matching refused when a live config would be orphaned", refusal != null);
		ProjectDeletionPlanTest.check("mentions matching", refusal.contains("matching"));
	}

	private static void validateDataspacesWithoutMatchingAllowedWhenNothingToOrphan()
	{
		final Facts facts = ProjectDeletionPlanTest.fullyEligibleFacts();
		final Facts noMatching = new Facts(facts.projectCode, facts.mode, false, facts.landingDataspaceStored, facts.landingDataspaceComputed,
			facts.stagingDataspaceStored, facts.stagingDatasetStored, facts.stagingDataspaceComputed, facts.stagingDatasetComputed, facts.stagingModelStepDone,
			facts.stagingDatasetCreatedByFactory, facts.masterDataspaceStored, facts.masterDatasetStored, facts.masterDataspaceComputed, facts.masterDatasetComputed,
			facts.masterModelStepDone, facts.masterDatasetCreatedByFactory, facts.sources, new ArrayList<MatchingFacts>(), facts.masterModelName, facts.stagingModelName, facts.rowCounts);
		final Plan plan = ProjectDeletionPlan.build(noMatching);
		final String refusal = ProjectDeletionPlan.validate(plan, ProjectDeletionPlanTest.set(ProjectDeletionPlan.OPTION_DATASPACES), ProjectDeletionPlanTest.CODE);
		ProjectDeletionPlanTest.check("dataspaces alone is fine when there is no matching config to orphan", refusal == null);
	}

	private static void validateEverythingAlignedAllowed()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionPlanTest.fullyEligibleFacts());
		final String refusal = ProjectDeletionPlan.validate(plan,
			ProjectDeletionPlanTest.set(ProjectDeletionPlan.OPTION_DATASPACES, ProjectDeletionPlan.OPTION_MATCHING, ProjectDeletionPlan.OPTION_FLOWS,
				ProjectDeletionPlan.OPTION_MODELS),
			ProjectDeletionPlanTest.CODE);
		ProjectDeletionPlanTest.check("every option together, matching included: allowed", refusal == null);
	}

	/**
	 * 2026-10-03 (Vol100k / Vol100kM): the id stored on a project's landing table can name the mapping of ANOTHER project - the
	 * mapping's own dataspace says whose it is. m1 writes into this project's landing dataspace (eligible), m2 into another's (not).
	 */
	private static void flowOfAnotherProjectIneligible()
	{
		final Facts facts = ProjectDeletionPlanTest.fullyEligibleFacts();
		final Map<String, String> dataspaces = new LinkedHashMap<String, String>();
		dataspaces.put("m1", "Companies_Landing");
		dataspaces.put("m2", "Other_Landing");
		final List<SourceFacts> sources = new ArrayList<SourceFacts>();
		sources.add(new SourceFacts("ERP", "Companies_Landing_ERP", "ERP", true, true, ProjectDeletionPlanTest.list("m1", "m2"), dataspaces));
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionPlanTest.withSources(facts, sources));
		final Item own = ProjectDeletionPlanTest.flow(plan, "m1");
		final Item foreign = ProjectDeletionPlanTest.flow(plan, "m2");
		ProjectDeletionPlanTest.check("a mapping writing into the project's landing dataspace is eligible", own.eligible && own.reason == null);
		ProjectDeletionPlanTest.check("a mapping writing into another dataspace is NOT eligible", !foreign.eligible);
		ProjectDeletionPlanTest.check("the reason says shared/foreign and names both dataspaces: " + foreign.reason,
			foreign.reason.contains("shared or foreign") && foreign.reason.contains("Other_Landing") && foreign.reason.contains("Companies_Landing"));
		ProjectDeletionPlanTest.check("only the project's own flow is offered under the flows option", plan.eligibleOf(ProjectDeletionPlan.OPTION_FLOWS).size() == 1
			&& "m1".equals(plan.eligibleOf(ProjectDeletionPlan.OPTION_FLOWS).get(0).name));
		ProjectDeletionPlanTest.check("a facts value built without the evidence (6-argument SourceFacts) still lists every flow eligible",
			ProjectDeletionPlan.build(ProjectDeletionPlanTest.fullyEligibleFacts()).eligibleOf(ProjectDeletionPlan.OPTION_FLOWS).size() == 2);
	}

	private static void unreadableFlowIneligibleGoneFlowEligible()
	{
		final Map<String, String> dataspaces = new LinkedHashMap<String, String>();
		dataspaces.put("m1", SourceFacts.MAPPING_UNREADABLE);
		dataspaces.put("m2", SourceFacts.MAPPING_GONE);
		final List<SourceFacts> sources = new ArrayList<SourceFacts>();
		sources.add(new SourceFacts("ERP", "Companies_Landing_ERP", "ERP", true, true, ProjectDeletionPlanTest.list("m1", "m2"), dataspaces));
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionPlanTest.withSources(ProjectDeletionPlanTest.fullyEligibleFacts(), sources));
		ProjectDeletionPlanTest.check("a mapping that could not be read is not offered", !ProjectDeletionPlanTest.flow(plan, "m1").eligible
			&& ProjectDeletionPlanTest.flow(plan, "m1").reason.contains("could not be read"));
		ProjectDeletionPlanTest.check("a mapping already gone stays eligible (the deletion reports it absent)", ProjectDeletionPlanTest.flow(plan, "m2").eligible);
	}

	// ------------------------------------------------------------------------------- distribution targets (fix of 2026-10-12)

	private static TargetFlowFacts read(final String pId, final String pTarget, final String pTable, final String pDataspace)
	{
		return new TargetFlowFacts(pId, pTable, TargetFlowFacts.STATE_READ, "Distribution: target " + pTarget + " " + pTable, pDataspace);
	}

	private static Facts withTargets(final Facts pFacts, final boolean pDelivered, final List<TargetFacts> pTargets)
	{
		return new Facts(pFacts.projectCode, pFacts.mode, pDelivered, pFacts.landingDataspaceStored, pFacts.landingDataspaceComputed, pFacts.stagingDataspaceStored,
			pFacts.stagingDatasetStored, pFacts.stagingDataspaceComputed, pFacts.stagingDatasetComputed, pFacts.stagingModelStepDone, pFacts.stagingDatasetCreatedByFactory,
			pFacts.masterDataspaceStored, pFacts.masterDatasetStored, pFacts.masterDataspaceComputed, pFacts.masterDatasetComputed, pFacts.masterModelStepDone,
			pFacts.masterDatasetCreatedByFactory, pFacts.sources, pFacts.matching, pTargets, pFacts.masterModelName, pFacts.stagingModelName, pFacts.rowCounts);
	}

	private static List<TargetFacts> oneTargetTwoMappings()
	{
		final List<TargetFlowFacts> flows = new ArrayList<TargetFlowFacts>();
		flows.add(ProjectDeletionPlanTest.read("314", "1", "/root/Personne", "Companies_Master"));
		flows.add(ProjectDeletionPlanTest.read("316", "1", "/root/Adresse", "Companies_Master"));
		final List<TargetFacts> targets = new ArrayList<TargetFacts>();
		targets.add(new TargetFacts("1", flows));
		return targets;
	}

	private static List<Item> distributionFlows(final Plan pPlan)
	{
		final List<Item> out = new ArrayList<Item>();
		for (final Item item : pPlan.items)
		{
			if (item.kind == Kind.DISTRIBUTION_FLOW)
			{
				out.add(item);
			}
		}
		return out;
	}

	/** A project with one target and two mappings: two outbound-flow items under the flows option, in the flows step (after the landing flows, before the dataspaces). */
	private static void distributionTargetFlowsAreOfferedWithTheFlowsOption()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionPlanTest.withTargets(ProjectDeletionPlanTest.fullyEligibleFacts(), false,
			ProjectDeletionPlanTest.oneTargetTwoMappings()));
		final List<Item> flows = ProjectDeletionPlanTest.distributionFlows(plan);
		ProjectDeletionPlanTest.check("one distribution-flow item per mapping of the target", flows.size() == 2);
		ProjectDeletionPlanTest.check("both eligible", flows.get(0).eligible && flows.get(1).eligible && flows.get(0).reason == null);
		ProjectDeletionPlanTest.check("named by mapping id", "314".equals(flows.get(0).name) && "316".equals(flows.get(1).name));
		ProjectDeletionPlanTest.check("detail names target and table: " + flows.get(0).detail,
			flows.get(0).detail.contains("target 1") && flows.get(0).detail.contains("/root/Personne"));
		ProjectDeletionPlanTest.check("under the flows option", ProjectDeletionPlan.OPTION_FLOWS.equals(flows.get(0).option));
		ProjectDeletionPlanTest.check("4 flows are offered in all (2 landing + 2 distribution)", plan.eligibleOf(ProjectDeletionPlan.OPTION_FLOWS).size() == 4);
		final List<Kind> order = new ArrayList<Kind>();
		for (final Item item : plan.items)
		{
			order.add(item.kind);
		}
		ProjectDeletionPlanTest.check("after the landing flows", order.lastIndexOf(Kind.LANDING_FLOW) < order.indexOf(Kind.DISTRIBUTION_FLOW));
		ProjectDeletionPlanTest.check("before the master dataspace", order.lastIndexOf(Kind.DISTRIBUTION_FLOW) < order.indexOf(Kind.MASTER_DATASPACE));
		ProjectDeletionPlanTest.check("the flows option alone is valid", ProjectDeletionPlan.validate(plan, ProjectDeletionPlanTest.set(ProjectDeletionPlan.OPTION_FLOWS),
			ProjectDeletionPlanTest.CODE) == null);
		ProjectDeletionPlanTest.check("a facts value built without targets (old constructor) has none",
			ProjectDeletionPlanTest.distributionFlows(ProjectDeletionPlan.build(ProjectDeletionPlanTest.fullyEligibleFacts())).isEmpty());
	}

	private static void distributionFlowOfADeliveredProjectIneligible()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionPlanTest.withTargets(ProjectDeletionPlanTest.fullyEligibleFacts(), true,
			ProjectDeletionPlanTest.oneTargetTwoMappings()));
		final List<Item> flows = ProjectDeletionPlanTest.distributionFlows(plan);
		ProjectDeletionPlanTest.check("still listed on a delivered project", flows.size() == 2);
		for (final Item item : flows)
		{
			ProjectDeletionPlanTest.check("ineligible with the delivered reason: " + item.reason, !item.eligible && item.reason.toLowerCase(java.util.Locale.ENGLISH).contains("delivered"));
		}
	}

	/** Ownership: the label must be THIS target's (target 1 is not target 10) and a named dataspace must be the project's master one. */
	private static void distributionFlowOfAnotherProjectOrTargetIneligible()
	{
		final List<TargetFlowFacts> flows = new ArrayList<TargetFlowFacts>();
		flows.add(ProjectDeletionPlanTest.read("1", "1", "/root/A", "Companies_Master"));
		flows.add(ProjectDeletionPlanTest.read("2", "1", "/root/B", "Other_Master"));
		flows.add(ProjectDeletionPlanTest.read("3", "10", "/root/C", "Companies_Master"));
		flows.add(new TargetFlowFacts("4", "/root/D", TargetFlowFacts.STATE_READ, "Catalog scan: X", "Companies_Master"));
		final List<TargetFacts> targets = new ArrayList<TargetFacts>();
		targets.add(new TargetFacts("1", flows));
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionPlanTest.withTargets(ProjectDeletionPlanTest.fullyEligibleFacts(), false, targets));
		final List<Item> items = ProjectDeletionPlanTest.distributionFlows(plan);
		ProjectDeletionPlanTest.check("the mapping of this target and master dataspace is eligible", items.get(0).eligible);
		ProjectDeletionPlanTest.check("a mapping on another dataspace is not: " + items.get(1).reason, !items.get(1).eligible
			&& items.get(1).reason.contains("shared or foreign") && items.get(1).reason.contains("Other_Master") && items.get(1).reason.contains("Companies_Master"));
		ProjectDeletionPlanTest.check("the label of target 10 does not pass for target 1: " + items.get(2).reason, !items.get(2).eligible && items.get(2).reason.contains("foreign"));
		ProjectDeletionPlanTest.check("a mapping that is not a distribution one is not: " + items.get(3).reason, !items.get(3).eligible);
		ProjectDeletionPlanTest.check("only the first is offered under the flows option (plus the 2 landing flows)", plan.eligibleOf(ProjectDeletionPlan.OPTION_FLOWS).size() == 3);
		ProjectDeletionPlanTest.check("a project with no master dataspace owns no mapping that names one",
			ProjectDeletionPlan.distributionFlowReason("", "1", flows.get(0)) != null);
	}

	private static void distributionFlowUnreadableIneligibleAbsentOrUndatedEligible()
	{
		final List<TargetFlowFacts> flows = new ArrayList<TargetFlowFacts>();
		flows.add(new TargetFlowFacts("1", "/root/A", TargetFlowFacts.STATE_UNREADABLE, null, null));
		flows.add(new TargetFlowFacts("2", "/root/B", TargetFlowFacts.STATE_GONE, null, null));
		// a mapping written before the dataspace was recorded: the label alone says whose it is
		flows.add(ProjectDeletionPlanTest.read("3", "1", "/root/C", null));
		final List<TargetFacts> targets = new ArrayList<TargetFacts>();
		targets.add(new TargetFacts("1", flows));
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionPlanTest.withTargets(ProjectDeletionPlanTest.fullyEligibleFacts(), false, targets));
		final List<Item> items = ProjectDeletionPlanTest.distributionFlows(plan);
		ProjectDeletionPlanTest.check("unreadable: not offered, says so: " + items.get(0).reason, !items.get(0).eligible && items.get(0).reason.contains("could not be read"));
		ProjectDeletionPlanTest.check("already gone: eligible, the deletion reports it absent", items.get(1).eligible);
		ProjectDeletionPlanTest.check("no dataspace named: eligible on the label", items.get(2).eligible);
	}

	private static Item flow(final Plan pPlan, final String pName)
	{
		for (final Item item : pPlan.items)
		{
			if (item.kind == ProjectDeletionPlan.Kind.LANDING_FLOW && pName.equals(item.name))
			{
				return item;
			}
		}
		throw new AssertionError("no LANDING_FLOW item " + pName);
	}

	private static Facts withSources(final Facts pFacts, final List<SourceFacts> pSources)
	{
		return new Facts(pFacts.projectCode, pFacts.mode, pFacts.delivered, pFacts.landingDataspaceStored, pFacts.landingDataspaceComputed, pFacts.stagingDataspaceStored,
			pFacts.stagingDatasetStored, pFacts.stagingDataspaceComputed, pFacts.stagingDatasetComputed, pFacts.stagingModelStepDone, pFacts.stagingDatasetCreatedByFactory,
			pFacts.masterDataspaceStored, pFacts.masterDatasetStored, pFacts.masterDataspaceComputed, pFacts.masterDatasetComputed, pFacts.masterModelStepDone,
			pFacts.masterDatasetCreatedByFactory, pSources, pFacts.matching, pFacts.masterModelName, pFacts.stagingModelName, pFacts.rowCounts);
	}

	// ------------------------------------------------------------------------------------------------------- fixtures

	/** A fully generated, fully eligible GREENFIELD project: one source, one promoted+configured entity, everything created by the factory. */
	private static Facts fullyEligibleFacts()
	{
		final List<SourceFacts> sources = new ArrayList<SourceFacts>();
		sources.add(new SourceFacts("ERP", "Companies_Landing_ERP", "ERP", true, true, ProjectDeletionPlanTest.list("m1", "m2")));
		final List<MatchingFacts> matching = new ArrayList<MatchingFacts>();
		matching.add(new MatchingFacts("Company", "/root/Company", true));
		final Map<String, Integer> rowCounts = new LinkedHashMap<String, Integer>();
		rowCounts.put("Project", Integer.valueOf(1));
		rowCounts.put("ProjectSource", Integer.valueOf(1));
		return new Facts(ProjectDeletionPlanTest.CODE, "GREENFIELD", false, "Companies_Landing", "Companies_Landing", "Companies_Staging", "Staging",
			"Companies_Staging", "Staging", true, true, "Companies_Master", "Master", "Companies_Master", "Master", true, true, sources, matching, "Companies_Master",
			"Companies_Staging", rowCounts);
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

	private static Set<String> set(final String... pValues)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final String value : pValues)
		{
			out.add(value);
		}
		return out;
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
		throw new AssertionError("no item of kind " + pKind);
	}

	/** Audit F3-10 / F3-103: the three tables of November are named in the deletion order, each BEFORE every table its foreign keys point to. */
	private static void agentTablesAreDeletedChildrenFirst()
	{
		final List<String> order = ProjectDeletionPlan.FACTORY_ROW_ORDER;
		ProjectDeletionPlanTest.check("AgentDefinition, AgentProposal and SourceDrift are in the order", order.contains("AgentDefinition") && order.contains("AgentProposal")
			&& order.contains("SourceDrift"));
		// child -> parent, read from MdmFactory.xsd (tableRef)
		final String[][] foreignKeys = { { "SourceDrift", "LandingTable" }, { "AgentProposal", "AgentRun" }, { "AgentProposal", "AgentDefinition" }, { "AgentRun", "AgentDefinition" },
			{ "AgentStep", "AgentRun" }, { "LandingColumn", "LandingTable" }, { "LandingTable", "ProjectSource" }, { "StagingAttribute", "StagingEntity" } };
		for (final String[] fk : foreignKeys)
		{
			ProjectDeletionPlanTest.check(fk[0] + " is deleted before " + fk[1], order.indexOf(fk[0]) >= 0 && order.indexOf(fk[0]) < order.indexOf(fk[1]));
		}
		// the gathering hands rows in any order and the plan puts them in the deletion order
		final Map<String, Integer> shuffled = new LinkedHashMap<String, Integer>();
		shuffled.put("AgentDefinition", Integer.valueOf(1));
		shuffled.put("LandingTable", Integer.valueOf(2));
		shuffled.put("AgentRun", Integer.valueOf(3));
		shuffled.put("SourceDrift", Integer.valueOf(4));
		shuffled.put("AgentProposal", Integer.valueOf(5));
		final List<String> ordered = new ArrayList<String>(ProjectDeletionPlan.inDeletionOrder(shuffled).keySet());
		ProjectDeletionPlanTest.check("ordered: " + ordered, ordered.equals(java.util.Arrays.asList("SourceDrift", "LandingTable", "AgentProposal", "AgentRun", "AgentDefinition")));
		boolean refused = false;
		try
		{
			final Map<String, Integer> unknown = new LinkedHashMap<String, Integer>();
			unknown.put("NoSuchTable", Integer.valueOf(1));
			ProjectDeletionPlan.inDeletionOrder(unknown);
		}
		catch (final IllegalArgumentException ex)
		{
			refused = true;
		}
		ProjectDeletionPlanTest.check("a table the order does not know is refused, not skipped", refused);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
