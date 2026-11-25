package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.on.ebx.factory.project.ProjectDeletionPlan.ConnectionFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.ConnectionReasons;
import com.on.ebx.factory.project.ProjectDeletionPlan.Facts;
import com.on.ebx.factory.project.ProjectDeletionPlan.Item;
import com.on.ebx.factory.project.ProjectDeletionPlan.Kind;
import com.on.ebx.factory.project.ProjectDeletionPlan.MappingRef;
import com.on.ebx.factory.project.ProjectDeletionPlan.Plan;
import com.on.ebx.factory.project.ProjectDeletionPlan.SourceFacts;

/**
 * JUnit-free: run the main, a failure throws. Lot C (2026-10-17): the {@code connections} option of the project deletion - the SELECTION rules (a connection
 * or a catalogue source the factory created vs one the person picked from the existing catalogue, one shared with another project, one something still
 * uses) and the wording of the plan. The evidence the rules read ({@link ConnectionFacts}) is built by hand here; where it comes from is
 * {@code ConnectionProvenanceTest}.
 */
public final class ProjectDeletionConnectionsTest
{
	private static final String CODE = "Ecom";
	private static final String CONNECTION = "EcomClientsE2e1017183516";
	private static final String SOURCE = "ECOMCLIENTSE2E1017183516";

	public static void main(final String[] pArgs)
	{
		ProjectDeletionConnectionsTest.optionIsKnownAndOwnsBothKinds();
		ProjectDeletionConnectionsTest.noEvidenceNoItem();
		ProjectDeletionConnectionsTest.createdConnectionAndSourceAreOfferedSourceFirst();
		ProjectDeletionConnectionsTest.pickedConnectionKeepsTheSourceCreatedOnIt();
		ProjectDeletionConnectionsTest.foundConnectionIsKeptAndSoIsItsSource();
		ProjectDeletionConnectionsTest.sharedWithAnotherProjectIsKept();
		ProjectDeletionConnectionsTest.sharedWithTwoProjectsIsPlural();
		ProjectDeletionConnectionsTest.sourceRepointedByHandIsKept();
		ProjectDeletionConnectionsTest.sourceStillUsedKeepsTheConnection();
		ProjectDeletionConnectionsTest.otherCatalogueSourcesKeepTheConnection();
		ProjectDeletionConnectionsTest.onlySampleFileConnectionsAreEverDeleted();
		ProjectDeletionConnectionsTest.connectionReferencedByAPublicationIsKept();
		ProjectDeletionConnectionsTest.catalogFlowsGoWithTheSourceAndBlockNothing();
		ProjectDeletionConnectionsTest.landingFlowsOfTheProjectNeedTheFlowsOption();
		ProjectDeletionConnectionsTest.aMappingNoDeletionRemovesKeepsTheConnection();
		ProjectDeletionConnectionsTest.anIneligibleFlowKeepsTheConnection();
		ProjectDeletionConnectionsTest.deliveredProjectKeepsEverything();
		ProjectDeletionConnectionsTest.objectsAlreadyGoneAreStillOffered();
		ProjectDeletionConnectionsTest.detailSaysWhatGoesWithTheSource();

		System.out.println("ProjectDeletionConnectionsTest OK");
	}

	// ----------------------------------------------------------------------------------------------------- rules

	private static void optionIsKnownAndOwnsBothKinds()
	{
		ProjectDeletionConnectionsTest.check("connections is one of the options", Arrays.asList(ProjectDeletionPlan.OPTIONS).contains(ProjectDeletionPlan.OPTION_CONNECTIONS));
		ProjectDeletionConnectionsTest.check("a catalogue source belongs to it", ProjectDeletionPlan.OPTION_CONNECTIONS.equals(ProjectDeletionPlan.optionOf(Kind.CATALOG_SOURCE)));
		ProjectDeletionConnectionsTest.check("a connection belongs to it", ProjectDeletionPlan.OPTION_CONNECTIONS.equals(ProjectDeletionPlan.optionOf(Kind.CONNECTION)));
		ProjectDeletionConnectionsTest.check("the older options still own their kinds", ProjectDeletionPlan.OPTION_MODELS.equals(ProjectDeletionPlan.optionOf(Kind.LANDING_MODEL))
			&& ProjectDeletionPlan.OPTION_FLOWS.equals(ProjectDeletionPlan.optionOf(Kind.LANDING_FLOW)));
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionConnectionsTest.facts(new ArrayList<SourceFacts>(), new ArrayList<ConnectionFacts>(), false));
		ProjectDeletionConnectionsTest.check("a known option passes validate", ProjectDeletionPlan.validate(plan, ProjectDeletionConnectionsTest.set("connections"), CODE) == null);
	}

	private static void noEvidenceNoItem()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionConnectionsTest.facts(new ArrayList<SourceFacts>(), new ArrayList<ConnectionFacts>(), false));
		ProjectDeletionConnectionsTest.check("a project with no trace of a created connection lists none", plan.allOf(ProjectDeletionPlan.OPTION_CONNECTIONS).isEmpty());
	}

	private static void createdConnectionAndSourceAreOfferedSourceFirst()
	{
		final Plan plan = ProjectDeletionConnectionsTest.build(ProjectDeletionConnectionsTest.created());
		final List<Item> items = plan.allOf(ProjectDeletionPlan.OPTION_CONNECTIONS);
		ProjectDeletionConnectionsTest.check("two items", items.size() == 2);
		ProjectDeletionConnectionsTest.check("the catalogue source first (the connection cannot go while it names it)", items.get(0).kind == Kind.CATALOG_SOURCE
			&& SOURCE.equals(items.get(0).name));
		ProjectDeletionConnectionsTest.check("the connection second", items.get(1).kind == Kind.CONNECTION && CONNECTION.equals(items.get(1).name));
		ProjectDeletionConnectionsTest.check("both eligible, no reason", items.get(0).eligible && items.get(1).eligible && items.get(0).reason == null && items.get(1).reason == null);
		ProjectDeletionConnectionsTest.check("the connections come after every other kind: the flows they run on go first",
			plan.items.get(plan.items.size() - 1).kind == Kind.CONNECTION);
	}

	/** The brief: "never a connection the person picked from the existing catalogue". A picked connection has no SOURCE_CONNECTION step. */
	private static void pickedConnectionKeepsTheSourceCreatedOnIt()
	{
		final ConnectionFacts picked = ProjectDeletionConnectionsTest.created();
		picked.connectionStepSeen = false;
		picked.connectionCreatedByFactory = false;
		final Plan plan = ProjectDeletionConnectionsTest.build(picked);
		final List<Item> items = plan.allOf(ProjectDeletionPlan.OPTION_CONNECTIONS);
		ProjectDeletionConnectionsTest.check("only the catalogue source is listed: the connection is not the factory's to even mention", items.size() == 1
			&& items.get(0).kind == Kind.CATALOG_SOURCE);
		ProjectDeletionConnectionsTest.check("the source is kept", !items.get(0).eligible);
		ProjectDeletionConnectionsTest.check("with the exact wording: " + items.get(0).reason, ConnectionReasons.sourceOnPickedConnection(SOURCE, CONNECTION).equals(items.get(0).reason)
			&& items.get(0).reason.contains("picked from the catalogue") && items.get(0).reason.contains(CONNECTION));
		ProjectDeletionConnectionsTest.check("nothing eligible, so the screen disables the option", plan.eligibleOf(ProjectDeletionPlan.OPTION_CONNECTIONS).isEmpty());
	}

	/** The importer FOUND a connection of that name (a file of the same name was uploaded before): created false. */
	private static void foundConnectionIsKeptAndSoIsItsSource()
	{
		final ConnectionFacts found = ProjectDeletionConnectionsTest.created();
		found.connectionCreatedByFactory = false;
		final Plan plan = ProjectDeletionConnectionsTest.build(found);
		final Item connection = ProjectDeletionConnectionsTest.item(plan, Kind.CONNECTION);
		final Item source = ProjectDeletionConnectionsTest.item(plan, Kind.CATALOG_SOURCE);
		ProjectDeletionConnectionsTest.check("a connection the factory found is kept: " + connection.reason, !connection.eligible && ConnectionReasons.connectionFound().equals(connection.reason));
		ProjectDeletionConnectionsTest.check("and so is a source it created on it", !source.eligible && source.reason.contains("not created by this factory"));
	}

	private static void sharedWithAnotherProjectIsKept()
	{
		final ConnectionFacts shared = ProjectDeletionConnectionsTest.created();
		shared.otherProjects.add("Alpha");
		final Plan plan = ProjectDeletionConnectionsTest.build(shared);
		final Item connection = ProjectDeletionConnectionsTest.item(plan, Kind.CONNECTION);
		final Item source = ProjectDeletionConnectionsTest.item(plan, Kind.CATALOG_SOURCE);
		ProjectDeletionConnectionsTest.check("the source is kept: " + source.reason, !source.eligible && source.reason.equals("shared: project Alpha also uses this catalogue source: not deleted."));
		ProjectDeletionConnectionsTest.check("the connection is kept: " + connection.reason, !connection.eligible
			&& connection.reason.equals("shared: project Alpha also uses this connection: not deleted."));
	}

	private static void sharedWithTwoProjectsIsPlural()
	{
		final ConnectionFacts shared = ProjectDeletionConnectionsTest.created();
		shared.otherProjects.add("Alpha");
		shared.otherProjects.add("Beta");
		final Item connection = ProjectDeletionConnectionsTest.item(ProjectDeletionConnectionsTest.build(shared), Kind.CONNECTION);
		ProjectDeletionConnectionsTest.check("plural wording: " + connection.reason, connection.reason.equals("shared: projects Alpha, Beta also use this connection: not deleted."));
	}

	private static void sourceRepointedByHandIsKept()
	{
		final ConnectionFacts repointed = ProjectDeletionConnectionsTest.created();
		repointed.catalogSourceConnector = "Salesforce";
		final Plan plan = ProjectDeletionConnectionsTest.build(repointed);
		final Item source = ProjectDeletionConnectionsTest.item(plan, Kind.CATALOG_SOURCE);
		final Item connection = ProjectDeletionConnectionsTest.item(plan, Kind.CONNECTION);
		ProjectDeletionConnectionsTest.check("a source now naming another connection is kept: " + source.reason, !source.eligible && source.reason.contains("now names connection Salesforce"));
		ProjectDeletionConnectionsTest.check("the connection says its source is kept: " + connection.reason, !connection.eligible && connection.reason.contains("its catalogue source " + SOURCE + " is kept"));
	}

	private static void sourceStillUsedKeepsTheConnection()
	{
		final ConnectionFacts used = ProjectDeletionConnectionsTest.created();
		used.catalogBlockers.add("2 data product(s) including its assets");
		final Plan plan = ProjectDeletionConnectionsTest.build(used);
		final Item source = ProjectDeletionConnectionsTest.item(plan, Kind.CATALOG_SOURCE);
		final Item connection = ProjectDeletionConnectionsTest.item(plan, Kind.CONNECTION);
		ProjectDeletionConnectionsTest.check("the source says what uses it: " + source.reason, !source.eligible && source.reason.contains("2 data product(s) including its assets")
			&& source.reason.startsWith("still in use"));
		ProjectDeletionConnectionsTest.check("the connection stays with it: " + connection.reason, !connection.eligible && connection.reason.contains("is kept"));
	}

	private static void otherCatalogueSourcesKeepTheConnection()
	{
		final ConnectionFacts other = ProjectDeletionConnectionsTest.created();
		other.otherSourceIds.add("SOMEONES_SOURCE");
		final Item connection = ProjectDeletionConnectionsTest.item(ProjectDeletionConnectionsTest.build(other), Kind.CONNECTION);
		ProjectDeletionConnectionsTest.check("a source the factory did not create names the connection: " + connection.reason, !connection.eligible
			&& connection.reason.contains("SOMEONES_SOURCE") && connection.reason.contains("not created by this factory"));
	}

	private static void onlySampleFileConnectionsAreEverDeleted()
	{
		final ConnectionFacts other = ProjectDeletionConnectionsTest.created();
		other.connectionType = "snowflake";
		final Item connection = ProjectDeletionConnectionsTest.item(ProjectDeletionConnectionsTest.build(other), Kind.CONNECTION);
		ProjectDeletionConnectionsTest.check("a connection that is not a sample-file one is kept: " + connection.reason, !connection.eligible
			&& connection.reason.equals(ConnectionReasons.connectionNotSampleFile("snowflake")));
	}

	private static void connectionReferencedByAPublicationIsKept()
	{
		final ConnectionFacts used = ProjectDeletionConnectionsTest.created();
		used.connectionBlockers.add("1 publication(s)");
		final Item connection = ProjectDeletionConnectionsTest.item(ProjectDeletionConnectionsTest.build(used), Kind.CONNECTION);
		ProjectDeletionConnectionsTest.check("kept with the reference named: " + connection.reason, !connection.eligible && connection.reason.contains("1 publication(s)"));
	}

	private static void catalogFlowsGoWithTheSourceAndBlockNothing()
	{
		final ConnectionFacts withFlows = ProjectDeletionConnectionsTest.created();
		withFlows.catalogFlows.add(new MappingRef("550", "Catalog scan: " + SOURCE));
		withFlows.catalogFlows.add(new MappingRef("551", "Catalog scan columns: " + SOURCE));
		final Plan plan = ProjectDeletionConnectionsTest.build(withFlows);
		ProjectDeletionConnectionsTest.check("both items stay eligible", plan.eligibleOf(ProjectDeletionPlan.OPTION_CONNECTIONS).size() == 2);
		ProjectDeletionConnectionsTest.check("the source's detail counts its flows", ProjectDeletionConnectionsTest.item(plan, Kind.CATALOG_SOURCE).detail.contains("2 catalogue flow(s)"));
		ProjectDeletionConnectionsTest.check("the connection needs no flows option for them", ProjectDeletionConnectionsTest.item(plan, Kind.CONNECTION).needsFlows.isEmpty());
		ProjectDeletionConnectionsTest.check("connections alone validates", ProjectDeletionPlan.validate(plan, ProjectDeletionConnectionsTest.set("connections"), CODE) == null);
	}

	/** The landing flow 481 runs on the connection: it is the project's own, removed by the flows option - so connections needs flows. */
	private static void landingFlowsOfTheProjectNeedTheFlowsOption()
	{
		final ConnectionFacts runsOn = ProjectDeletionConnectionsTest.created();
		runsOn.otherMappings.add(new MappingRef("481", "Landing: Ecom ECOM_CLIENTS ECOM_Clients"));
		final Plan plan = ProjectDeletionConnectionsTest.build(ProjectDeletionConnectionsTest.sourcesWithFlow("481"), runsOn);
		final Item connection = ProjectDeletionConnectionsTest.item(plan, Kind.CONNECTION);
		ProjectDeletionConnectionsTest.check("the connection is eligible: the flow that runs on it is removed by this plan", connection.eligible);
		ProjectDeletionConnectionsTest.check("and says which flow it waits for", connection.needsFlows.size() == 1 && "481".equals(connection.needsFlows.get(0)));
		final String refusal = ProjectDeletionPlan.validate(plan, ProjectDeletionConnectionsTest.set("connections"), CODE);
		ProjectDeletionConnectionsTest.check("connections without flows is refused: " + refusal, refusal != null && refusal.contains("select 'flows' too") && refusal.contains("481")
			&& refusal.contains(CONNECTION));
		ProjectDeletionConnectionsTest.check("connections with flows is allowed", ProjectDeletionPlan.validate(plan, ProjectDeletionConnectionsTest.set("connections", "flows"), CODE) == null);
		ProjectDeletionConnectionsTest.check("flows alone is still allowed (the connection simply stays)", ProjectDeletionPlan.validate(plan, ProjectDeletionConnectionsTest.set("flows"), CODE) == null);
	}

	private static void aMappingNoDeletionRemovesKeepsTheConnection()
	{
		final ConnectionFacts someoneElses = ProjectDeletionConnectionsTest.created();
		someoneElses.otherMappings.add(new MappingRef("900", "Somebody's own flow"));
		final Item connection = ProjectDeletionConnectionsTest.item(ProjectDeletionConnectionsTest.build(someoneElses), Kind.CONNECTION);
		ProjectDeletionConnectionsTest.check("kept, the mapping named: " + connection.reason, !connection.eligible
			&& connection.reason.equals(ConnectionReasons.mappingStillUses(new MappingRef("900", "Somebody's own flow")))
			&& connection.reason.contains("mapping 900 (Somebody's own flow) still runs on this connection"));
	}

	/** The landing flow is the project's by id but writes into another dataspace: the flows option will not delete it, so the connection stays. */
	private static void anIneligibleFlowKeepsTheConnection()
	{
		final ConnectionFacts runsOn = ProjectDeletionConnectionsTest.created();
		runsOn.otherMappings.add(new MappingRef("481", "Landing: Other ECOM_CLIENTS"));
		final LinkedHashMap<String, String> dataspaces = new LinkedHashMap<String, String>();
		dataspaces.put("481", "Other_Landing");
		final List<SourceFacts> sources = new ArrayList<SourceFacts>();
		sources.add(new SourceFacts("ECOM", "Ecom_Landing_ECOM", "ECOM", true, true, Arrays.asList("481"), dataspaces));
		final Plan plan = ProjectDeletionConnectionsTest.build(sources, runsOn);
		final Item connection = ProjectDeletionConnectionsTest.item(plan, Kind.CONNECTION);
		ProjectDeletionConnectionsTest.check("the flow is not eligible", !plan.eligibleOf(ProjectDeletionPlan.OPTION_FLOWS).contains(ProjectDeletionConnectionsTest.item(plan, Kind.LANDING_FLOW)));
		ProjectDeletionConnectionsTest.check("so the connection is kept: " + connection.reason, !connection.eligible && connection.reason.contains("mapping 481"));
	}

	private static void deliveredProjectKeepsEverything()
	{
		final Plan plan = ProjectDeletionPlan.build(ProjectDeletionConnectionsTest.facts(new ArrayList<SourceFacts>(), ProjectDeletionConnectionsTest.list(ProjectDeletionConnectionsTest.created()), true));
		final List<Item> items = plan.allOf(ProjectDeletionPlan.OPTION_CONNECTIONS);
		ProjectDeletionConnectionsTest.check("two items", items.size() == 2);
		for (final Item item : items)
		{
			ProjectDeletionConnectionsTest.check("delivered: " + item.kind, !item.eligible && item.reason.contains("delivered"));
		}
	}

	/** Evidence of creation but the rows are gone (an earlier partial run): still offered, the deletion reports them absent. */
	private static void objectsAlreadyGoneAreStillOffered()
	{
		final ConnectionFacts gone = ProjectDeletionConnectionsTest.created();
		gone.connectionExists = false;
		gone.connectionType = "";
		gone.catalogSourceExists = false;
		gone.catalogSourceConnector = "";
		gone.assets = 0;
		gone.columns = 0;
		final Plan plan = ProjectDeletionConnectionsTest.build(gone);
		ProjectDeletionConnectionsTest.check("both still eligible", plan.eligibleOf(ProjectDeletionPlan.OPTION_CONNECTIONS).size() == 2);
	}

	private static void detailSaysWhatGoesWithTheSource()
	{
		final ConnectionFacts big = ProjectDeletionConnectionsTest.created();
		big.assets = 1;
		big.columns = 13;
		final Item source = ProjectDeletionConnectionsTest.item(ProjectDeletionConnectionsTest.build(big), Kind.CATALOG_SOURCE);
		ProjectDeletionConnectionsTest.check("detail: " + source.detail, source.detail.equals("Catalogue source of connection " + CONNECTION
			+ ": 1 asset(s), 13 column(s) and 0 catalogue flow(s) go with it."));
	}

	// -------------------------------------------------------------------------------------------------- fixtures

	/** A sample-file connection the factory created for the project, with the catalogue source it created on it: nothing else uses either. */
	private static ConnectionFacts created()
	{
		final ConnectionFacts facts = new ConnectionFacts(CONNECTION);
		facts.connectionStepSeen = true;
		facts.connectionCreatedByFactory = true;
		facts.connectionExists = true;
		facts.connectionType = "sample-file";
		facts.catalogSourceId = SOURCE;
		facts.catalogSourceCreatedByFactory = true;
		facts.catalogSourceExists = true;
		facts.catalogSourceConnector = CONNECTION;
		facts.assets = 1;
		facts.columns = 13;
		return facts;
	}

	private static List<ConnectionFacts> list(final ConnectionFacts pFacts)
	{
		final List<ConnectionFacts> out = new ArrayList<ConnectionFacts>();
		out.add(pFacts);
		return out;
	}

	private static Plan build(final ConnectionFacts pConnection)
	{
		return ProjectDeletionPlan.build(ProjectDeletionConnectionsTest.facts(new ArrayList<SourceFacts>(), ProjectDeletionConnectionsTest.list(pConnection), false));
	}

	private static Plan build(final List<SourceFacts> pSources, final ConnectionFacts pConnection)
	{
		return ProjectDeletionPlan.build(ProjectDeletionConnectionsTest.facts(pSources, ProjectDeletionConnectionsTest.list(pConnection), false));
	}

	private static List<SourceFacts> sourcesWithFlow(final String pMappingId)
	{
		final List<SourceFacts> sources = new ArrayList<SourceFacts>();
		sources.add(new SourceFacts("ECOM", "Ecom_Landing_ECOM", "ECOM", true, true, Arrays.asList(pMappingId)));
		return sources;
	}

	/** Landing dataspace stored and computed alike, created by the factory; no staging, no master. */
	private static Facts facts(final List<SourceFacts> pSources, final List<ConnectionFacts> pConnections, final boolean pDelivered)
	{
		return new Facts(CODE, "GREENFIELD", pDelivered, "Ecom_Landing", "Ecom_Landing", "", "", "Ecom_Staging", "Staging", false, false, "", "", "Ecom_Master", "Master", false,
			false, pSources, new ArrayList<ProjectDeletionPlan.MatchingFacts>(), new ArrayList<ProjectDeletionPlan.TargetFacts>(), pConnections, "Ecom_Master", "Ecom_Staging",
			new LinkedHashMap<String, Integer>());
	}

	private static Item item(final Plan pPlan, final Kind pKind)
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
