package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.Arrays;
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

/**
 * JUnit-free: run the main, a failure throws. Lot M18 (2026-10-18): the Match and Merge business object is its own item of the deletion plan
 * ({@code MATCHING_BUSINESS_OBJECT}, option {@code matching}), planned before the members' matching configurations and before every dataspace and model - the order in
 * which {@code ProjectDeleter} executes the items, because the add-on can delete a member's configuration only once its table no longer belongs to a business object
 * and only while the master model still exists.
 */
public final class ProjectDeletionBusinessObjectTest
{
	private static final String CODE = "Tiers";

	public static void main(final String[] pArgs)
	{
		ProjectDeletionBusinessObjectTest.itemShownWithItsMembers();
		ProjectDeletionBusinessObjectTest.optionIsMatching();
		ProjectDeletionBusinessObjectTest.executedBeforeMembersAndBeforeDataspacesAndModels();
		ProjectDeletionBusinessObjectTest.noBusinessObjectNoItem();
		ProjectDeletionBusinessObjectTest.followsTheMasterZoneEligibility();
		ProjectDeletionBusinessObjectTest.dataspacesWithoutMatchingRefusedBecauseOfTheBusinessObject();
		ProjectDeletionBusinessObjectTest.noMasterDataspaceNoItem();
		ProjectDeletionBusinessObjectTest.mainTableConfigurationIsDeletedLast();
		ProjectDeletionBusinessObjectTest.distributionTriggersComeBeforeEveryMatchingItem();
		System.out.println("ProjectDeletionBusinessObjectTest OK");
	}

	private static void itemShownWithItsMembers()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionBusinessObjectTest.facts("GREENFIELD", true, true));
		final Item item = ProjectDeletionBusinessObjectTest.only(plan, Kind.MATCHING_BUSINESS_OBJECT);
		ProjectDeletionBusinessObjectTest.check("named after the main table", "/root/Client".equals(item.name));
		ProjectDeletionBusinessObjectTest.check("eligible, no reason", item.eligible && item.reason == null);
		ProjectDeletionBusinessObjectTest.check("the detail names the business object and its members: " + item.detail, item.detail.startsWith("Business object of Client: Client, Adresse"));
	}

	private static void optionIsMatching()
	{
		ProjectDeletionBusinessObjectTest.check("the kind belongs to the matching option", ProjectDeletionPlan.OPTION_MATCHING.equals(ProjectDeletionPlan.optionOf(Kind.MATCHING_BUSINESS_OBJECT)));
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionBusinessObjectTest.facts("GREENFIELD", true, true));
		ProjectDeletionBusinessObjectTest.check("allOf(matching) holds the business object and the member",
			plan.allOf(ProjectDeletionPlan.OPTION_MATCHING).size() == 2);
	}

	private static void executedBeforeMembersAndBeforeDataspacesAndModels()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionBusinessObjectTest.facts("GREENFIELD", true, true));
		final List<Kind> order = new ArrayList<Kind>();
		for (final Item item : plan.items)
		{
			order.add(item.kind);
		}
		final int businessObject = order.indexOf(Kind.MATCHING_BUSINESS_OBJECT);
		ProjectDeletionBusinessObjectTest.check("the business object is the FIRST item of the plan", businessObject == 0);
		for (int i = 0; i < order.size(); i++)
		{
			if (order.get(i) == Kind.MATCHING_CONFIG)
			{
				ProjectDeletionBusinessObjectTest.check("the business object before the member configuration at " + i, businessObject < i);
			}
		}
		ProjectDeletionBusinessObjectTest.check("members before the master dataspace", order.lastIndexOf(Kind.MATCHING_CONFIG) < order.indexOf(Kind.MASTER_DATASPACE));
		ProjectDeletionBusinessObjectTest.check("members before the master model", order.lastIndexOf(Kind.MATCHING_CONFIG) < order.indexOf(Kind.MASTER_MODEL));
		ProjectDeletionBusinessObjectTest.check("the business object before the master dataspace and model", businessObject < order.indexOf(Kind.MASTER_DATASPACE) && businessObject < order.indexOf(Kind.MASTER_MODEL));
	}

	private static void noBusinessObjectNoItem()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionBusinessObjectTest.facts("GREENFIELD", false, true));
		ProjectDeletionBusinessObjectTest.check("a master with no business object lists none", plan.items.size() > 0 && !ProjectDeletionBusinessObjectTest.has(plan, Kind.MATCHING_BUSINESS_OBJECT));
	}

	private static void followsTheMasterZoneEligibility()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionBusinessObjectTest.facts("TARGET_EXISTS", true, true));
		final Item item = ProjectDeletionBusinessObjectTest.only(plan, Kind.MATCHING_BUSINESS_OBJECT);
		ProjectDeletionBusinessObjectTest.check("a TARGET_EXISTS master zone is not the factory's: ineligible", !item.eligible);
		ProjectDeletionBusinessObjectTest.check("the reason names the master zone: " + item.reason, item.reason.contains("TARGET_EXISTS"));
		final Plan delivered = ProjectDeletionPlan.build(ProjectDeletionBusinessObjectTest.facts("GREENFIELD", true, false));
		ProjectDeletionBusinessObjectTest.check("no proof the factory created the master dataset: ineligible", !ProjectDeletionBusinessObjectTest.only(delivered, Kind.MATCHING_BUSINESS_OBJECT).eligible);
	}

	private static void dataspacesWithoutMatchingRefusedBecauseOfTheBusinessObject()
	{
		// the member configuration is not configured (nothing to delete): only the business object would be orphaned by deleting the dataspace
		final Facts facts = ProjectDeletionBusinessObjectTest.facts("GREENFIELD", true, true);
		facts.matching.clear();
		final Plan plan = ProjectDeletionPlan.build(facts);
		final String refusal = ProjectDeletionPlan.validate(plan, ProjectDeletionBusinessObjectTest.set("dataspaces"), CODE);
		ProjectDeletionBusinessObjectTest.check("dataspaces without matching is refused: " + refusal, refusal != null && refusal.contains("/root/Client") && refusal.contains("matching"));
		ProjectDeletionBusinessObjectTest.check("with matching it passes", ProjectDeletionPlan.validate(plan, ProjectDeletionBusinessObjectTest.set("dataspaces", "matching"), CODE) == null);
	}

	private static void noMasterDataspaceNoItem()
	{
		final Facts facts = ProjectDeletionBusinessObjectTest.facts("GREENFIELD", true, true);
		final Facts noMaster = new Facts(facts.projectCode, facts.mode, false, facts.landingDataspaceStored, facts.landingDataspaceComputed, "", "", facts.stagingDataspaceComputed,
			facts.stagingDatasetComputed, false, false, "", "", facts.masterDataspaceComputed, facts.masterDatasetComputed, false, false, facts.sources, new ArrayList<MatchingFacts>(),
			facts.masterModelName, facts.stagingModelName, facts.rowCounts);
		noMaster.businessObjects = facts.businessObjects;
		ProjectDeletionBusinessObjectTest.check("a project that never reached the master zone lists no business object", !ProjectDeletionBusinessObjectTest.has(ProjectDeletionPlan.build(noMaster), Kind.MATCHING_BUSINESS_OBJECT));
	}

	/**
	 * Lot S9 (2026-10-18 22:17, live): the main table's configuration is the one whose deletion breaks the master model's compile (the distribution triggers read its
	 * Linked_Field_State), so every other configuration runs BEFORE it, after the business object; the order of the others is the path order, whatever the facts' order.
	 */
	private static void mainTableConfigurationIsDeletedLast()
	{
		final Facts facts = ProjectDeletionBusinessObjectTest.facts("GREENFIELD", true, true);
		final List<MatchingFacts> matching = new ArrayList<MatchingFacts>();
		matching.add(new MatchingFacts("Personne", "/root/Personne", true));
		matching.add(new MatchingFacts("Adresse", "/root/Adresse", true));
		matching.add(new MatchingFacts("AchatMagasin", "/root/AchatMagasin", true));
		final Facts live = new Facts(facts.projectCode, facts.mode, false, facts.landingDataspaceStored, facts.landingDataspaceComputed, facts.stagingDataspaceStored,
			facts.stagingDatasetStored, facts.stagingDataspaceComputed, facts.stagingDatasetComputed, true, true, facts.masterDataspaceStored, facts.masterDatasetStored,
			facts.masterDataspaceComputed, facts.masterDatasetComputed, true, true, facts.sources, matching, facts.masterModelName, facts.stagingModelName, facts.rowCounts);
		live.businessObjects = MatchingConfigRows.businessObjectsOf("Tiers_Master",
			Arrays.asList(new MatchingConfigRows.BusinessObject("33", "urn:ebx:publication:Tiers_Master", "/root/Personne")),
			Arrays.asList(new MatchingConfigRows.TableConfig("160", "urn:ebx:publication:Tiers_Master", "/root/Personne", "33"),
				new MatchingConfigRows.TableConfig("161", "urn:ebx:publication:Tiers_Master", "/root/Adresse", "33")));
		final List<String> names = new ArrayList<String>();
		for (final Item item : ProjectDeletionPlan.build(live).items)
		{
			if (item.kind == Kind.MATCHING_BUSINESS_OBJECT || item.kind == Kind.MATCHING_CONFIG)
			{
				names.add(item.kind + " " + item.name);
			}
		}
		ProjectDeletionBusinessObjectTest.check("business object, the other tables in path order, the main table last: " + names,
			names.equals(Arrays.asList("MATCHING_BUSINESS_OBJECT /root/Personne", "MATCHING_CONFIG /root/AchatMagasin", "MATCHING_CONFIG /root/Adresse", "MATCHING_CONFIG /root/Personne")));
		// without a business object there is no main table: the order is the path order, and the facts' list is left alone
		live.businessObjects = new ArrayList<MatchingConfigRows.BusinessObjectFacts>();
		final List<MatchingFacts> original = new ArrayList<MatchingFacts>(matching);
		final List<MatchingFacts> ordered = ProjectDeletionPlan.matchingInDeletionOrder(matching, live.businessObjects);
		ProjectDeletionBusinessObjectTest.check("path order without a business object", ordered.get(0).tablePath.equals("/root/AchatMagasin") && ordered.get(2).tablePath.equals("/root/Personne"));
		ProjectDeletionBusinessObjectTest.check("the input list is not modified", matching.equals(original));
	}

	/**
	 * Lot S9 (extension): the distribution triggers read Linked_Field_State on every table that carries it, members included, so ONE DISTRIBUTION_TRIGGERS item (option matching,
	 * named after the master model) runs before the business object and before every matching configuration; no recorded install, no item.
	 */
	private static void distributionTriggersComeBeforeEveryMatchingItem()
	{
		final Facts facts = ProjectDeletionBusinessObjectTest.facts("GREENFIELD", true, true);
		ProjectDeletionBusinessObjectTest.check("no recorded install, no item", !ProjectDeletionBusinessObjectTest.has(ProjectDeletionPlan.build(facts), Kind.DISTRIBUTION_TRIGGERS));
		facts.distributionTriggerTables = new ArrayList<String>(Arrays.asList("/root/Client", "/root/Adresse"));
		final Plan plan = ProjectDeletionPlan.build(facts);
		final Item item = ProjectDeletionBusinessObjectTest.only(plan, Kind.DISTRIBUTION_TRIGGERS);
		ProjectDeletionBusinessObjectTest.check("named after the master model", "Tiers_Master".equals(item.name));
		ProjectDeletionBusinessObjectTest.check("eligible, option matching", item.eligible && ProjectDeletionPlan.OPTION_MATCHING.equals(item.option));
		ProjectDeletionBusinessObjectTest.check("the detail names the tables: " + item.detail, item.detail.contains("/root/Client, /root/Adresse"));
		final List<Kind> order = new ArrayList<Kind>();
		for (final Item each : plan.items)
		{
			order.add(each.kind);
		}
		ProjectDeletionBusinessObjectTest.check("first of the plan: " + order, order.get(0) == Kind.DISTRIBUTION_TRIGGERS);
		ProjectDeletionBusinessObjectTest.check("before the business object and every matching configuration",
			order.indexOf(Kind.DISTRIBUTION_TRIGGERS) < order.indexOf(Kind.MATCHING_BUSINESS_OBJECT) && order.indexOf(Kind.DISTRIBUTION_TRIGGERS) < order.indexOf(Kind.MATCHING_CONFIG));
		ProjectDeletionBusinessObjectTest.check("allOf(matching) now holds three items", plan.allOf(ProjectDeletionPlan.OPTION_MATCHING).size() == 3);
		// a master zone the factory does not own: the item is shown but not eligible, like the other matching items
		final Facts foreign = ProjectDeletionBusinessObjectTest.facts("TARGET_EXISTS", true, false);
		foreign.distributionTriggerTables = new ArrayList<String>(Arrays.asList("/root/Client"));
		final Item kept = ProjectDeletionBusinessObjectTest.only(ProjectDeletionPlan.build(foreign), Kind.DISTRIBUTION_TRIGGERS);
		ProjectDeletionBusinessObjectTest.check("not eligible with the master zone: " + kept.reason, !kept.eligible && kept.reason != null);
	}

	// ------------------------------------------------------------------------------------------------------------ fixtures

	/** The live shape of 2026-10-18: a business object {@code 8} on {@code /root/Client}, linked to the table configurations of Client and Adresse. */
	private static Facts facts(final String pMode, final boolean pWithBusinessObject, final boolean pMasterCreatedByFactory)
	{
		final List<SourceFacts> sources = new ArrayList<SourceFacts>();
		sources.add(new SourceFacts("ERP", "Tiers_Landing_ERP", "ERP", true, true, Arrays.asList("m1")));
		final List<MatchingFacts> matching = new ArrayList<MatchingFacts>();
		matching.add(new MatchingFacts("Client", "/root/Client", true));
		final Map<String, Integer> rowCounts = new LinkedHashMap<String, Integer>();
		rowCounts.put("Project", Integer.valueOf(1));
		final Facts facts = new Facts(CODE, pMode, false, "Tiers_Landing", "Tiers_Landing", "Tiers_Staging", "Staging", "Tiers_Staging", "Staging", true, true, "Tiers_Master", "Master",
			"Tiers_Master", "Master", pMasterCreatedByFactory, pMasterCreatedByFactory, sources, matching, "Tiers_Master", "Tiers_Staging", rowCounts);
		if (pWithBusinessObject)
		{
			facts.businessObjects = MatchingConfigRows.businessObjectsOf("Tiers_Master",
				Arrays.asList(new MatchingConfigRows.BusinessObject("8", "urn:ebx:publication:Tiers_Master", "/root/Client")),
				Arrays.asList(new MatchingConfigRows.TableConfig("83", "urn:ebx:publication:Tiers_Master", "/root/Client", "8"),
					new MatchingConfigRows.TableConfig("84", "urn:ebx:publication:Tiers_Master", "/root/Adresse", "8")));
		}
		return facts;
	}

	private static boolean has(final Plan pPlan, final Kind pKind)
	{
		for (final Item item : pPlan.items)
		{
			if (item.kind == pKind)
			{
				return true;
			}
		}
		return false;
	}

	private static Item only(final Plan pPlan, final Kind pKind)
	{
		Item found = null;
		for (final Item item : pPlan.items)
		{
			if (item.kind == pKind)
			{
				ProjectDeletionBusinessObjectTest.check("exactly one " + pKind, found == null);
				found = item;
			}
		}
		ProjectDeletionBusinessObjectTest.check("an item of kind " + pKind, found != null);
		return found;
	}

	private static Set<String> set(final String... pValues)
	{
		return new LinkedHashSet<String>(Arrays.asList(pValues));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
