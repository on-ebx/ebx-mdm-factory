package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.core.FactoryRights;
import com.on.ebx.factory.project.ProjectDeletionPlan.ConnectionFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.MappingRef;

/**
 * JUnit-free: run the main, a failure throws. Orphan-connections lot (2026-10-17): the selection rules of the cleanup of sample-file connections (and
 * catalogue sources) no project uses - what counts as referenced by a live project, what is eligible (the project deletion's own rules, fed by
 * {@code OrphanConnections.claim}), the name filter, the body and the confirm guard, and the rights of the two routes.
 */
public final class OrphanConnectionsTest
{
	private static final String CONNECTION = "EcomClientsE2e1017183516";
	private static final String SOURCE = "ECOMCLIENTSE2E1017183516";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		OrphanConnectionsTest.projectSourceReferencesItsConnectionAndItsCatalogueSource();
		OrphanConnectionsTest.stepsOfLiveProjectsReferenceAndUnreadableOnesStillDo();
		OrphanConnectionsTest.nothingReferencedMeansNothingFound();
		OrphanConnectionsTest.anOrphanWithItsOwnFlowsIsEligible();
		OrphanConnectionsTest.anOrphanWithoutSourceIsEligible();
		OrphanConnectionsTest.theSeedConnectionIsNeverEligible();
		OrphanConnectionsTest.onlySampleFileIsEverEligible();
		OrphanConnectionsTest.aMappingOfSomebodyElseKeepsIt();
		OrphanConnectionsTest.connectionBlockersKeepIt();
		OrphanConnectionsTest.catalogueBlockersKeepItAndTheSource();
		OrphanConnectionsTest.severalCatalogueSourcesKeepTheConnection();
		OrphanConnectionsTest.aSourceRepointedByHandIsKept();
		OrphanConnectionsTest.aMissingConnectionIsNotEligible();
		OrphanConnectionsTest.nameFilterIsAPlainSubstring();
		OrphanConnectionsTest.bodyAndIds();
		OrphanConnectionsTest.confirmGuard();
		OrphanConnectionsTest.bothRoutesAreAdministratorOnly();
		System.out.println("OrphanConnectionsTest OK");
	}

	// -------------------------------------------------------------------------------------------------- references

	private static void projectSourceReferencesItsConnectionAndItsCatalogueSource()
	{
		final Map<String, String> sourceConnector = new HashMap<String, String>();
		sourceConnector.put("DEMOCRM", "DemoCRM");
		final Set<String> ref = OrphanConnections.referencedIds(Arrays.asList("EcomClients"), Arrays.asList("DEMOCRM", "UNKNOWN", null), sourceConnector,
			Collections.<ConnectionProvenance.StepRow> emptyList());
		check("a project source's connector is referenced", ref.contains("EcomClients"));
		check("a project source's catalogue source references the connection that source names", ref.contains("DemoCRM"));
		check("nothing else", ref.size() == 2);
	}

	private static void stepsOfLiveProjectsReferenceAndUnreadableOnesStillDo()
	{
		final List<ConnectionProvenance.StepRow> steps = new ArrayList<ConnectionProvenance.StepRow>();
		steps.add(new ConnectionProvenance.StepRow(ConnectionProvenance.STEP_CONNECTION, "ConnA", ConnectionProvenance.connectionDetail("ConnA", "sample-file", true)));
		steps.add(new ConnectionProvenance.StepRow(ConnectionProvenance.STEP_SOURCES_RUN, "ConnB", ConnectionProvenance.sourcesRunDetail("ConnB", "SRCB", true)));
		steps.add(new ConnectionProvenance.StepRow(ConnectionProvenance.STEP_SOURCES_RUN, "ConnC", "not json"));
		steps.add(new ConnectionProvenance.StepRow(ConnectionProvenance.STEP_CONNECTION, "ConnD", ""));
		steps.add(new ConnectionProvenance.StepRow("LANDING_RUN", "ConnE", "{}"));
		final Map<String, String> sourceConnector = new HashMap<String, String>();
		sourceConnector.put("SRCB", "ConnB");
		final Set<String> ref = OrphanConnections.referencedIds(Collections.<String> emptyList(), Collections.<String> emptyList(), sourceConnector, steps);
		check("a SOURCE_CONNECTION step references", ref.contains("ConnA"));
		check("a SOURCES_RUN step references", ref.contains("ConnB"));
		check("a step with an unreadable detail still references by its scope (safe side)", ref.contains("ConnC") && ref.contains("ConnD"));
		check("a step of another kind references nothing", !ref.contains("ConnE"));
	}

	private static void nothingReferencedMeansNothingFound()
	{
		check("no reference: empty", OrphanConnections.referencedIds(Collections.<String> emptyList(), Collections.<String> emptyList(), new HashMap<String, String>(),
			Collections.<ConnectionProvenance.StepRow> emptyList()).isEmpty());
	}

	// ----------------------------------------------------------------------------------------------------- rules

	/** The facts as ConnectionCleanup would gather them for an orphan sample-file connection with one catalogue source and two catalogue flows. */
	private static ConnectionFacts orphan()
	{
		final ConnectionFacts facts = new ConnectionFacts(CONNECTION);
		OrphanConnections.claim(facts, Arrays.asList(SOURCE));
		facts.connectionExists = true;
		facts.connectionType = "sample-file";
		facts.catalogSourceExists = true;
		facts.catalogSourceConnector = CONNECTION;
		facts.assets = 1;
		facts.columns = 13;
		facts.catalogFlows.add(new MappingRef("550", "Catalog scan: " + SOURCE));
		facts.catalogFlows.add(new MappingRef("551", "Catalog scan columns: " + SOURCE));
		return facts;
	}

	private static void anOrphanWithItsOwnFlowsIsEligible()
	{
		final ConnectionFacts facts = orphan();
		check("claim: the single catalogue source is its source", SOURCE.equals(facts.catalogSourceId) && facts.catalogSourceCreatedByFactory);
		final OrphanConnections.Verdict verdict = OrphanConnections.decide(facts);
		check("an orphan whose only mappings are the catalogue's own flows is eligible: " + verdict.reason, verdict.eligible && verdict.reason == null);
		check("no blocker listed", verdict.blockers.isEmpty());
	}

	private static void anOrphanWithoutSourceIsEligible()
	{
		final ConnectionFacts facts = new ConnectionFacts("SourceBidon");
		OrphanConnections.claim(facts, Collections.<String> emptyList());
		facts.connectionExists = true;
		facts.connectionType = "sample-file";
		check("no source claimed", facts.catalogSourceId.isEmpty() && !facts.catalogSourceCreatedByFactory);
		check("a connection with no catalogue source and no mapping is eligible", OrphanConnections.decide(facts).eligible);
	}

	private static void theSeedConnectionIsNeverEligible()
	{
		final ConnectionFacts facts = new ConnectionFacts(OrphanConnections.SEED_CONNECTION);
		OrphanConnections.claim(facts, Collections.<String> emptyList());
		facts.connectionExists = true;
		facts.connectionType = "sample-file";
		final OrphanConnections.Verdict verdict = OrphanConnections.decide(facts);
		check("the seed connection is kept", !verdict.eligible && verdict.reason.contains("seeds itself"));
	}

	private static void onlySampleFileIsEverEligible()
	{
		final ConnectionFacts facts = orphan();
		facts.connectionType = "kafka";
		final OrphanConnections.Verdict verdict = OrphanConnections.decide(facts);
		check("a kafka connection is never eligible", !verdict.eligible && verdict.reason.contains("kafka"));
		facts.connectionType = "";
		check("an unreadable type is never eligible", !OrphanConnections.decide(facts).eligible);
	}

	private static void aMappingOfSomebodyElseKeepsIt()
	{
		final ConnectionFacts facts = orphan();
		facts.otherMappings.add(new MappingRef("700", "Landing: Ecom"));
		final OrphanConnections.Verdict verdict = OrphanConnections.decide(facts);
		check("a mapping that is not the source's own flow keeps the connection", !verdict.eligible && verdict.reason.contains("mapping 700"));
		check("and is listed as a blocker", verdict.blockers.size() == 1 && verdict.blockers.get(0).contains("700") && verdict.blockers.get(0).contains("Landing: Ecom"));
	}

	private static void connectionBlockersKeepIt()
	{
		final ConnectionFacts facts = orphan();
		facts.connectionBlockers.add("2 publication(s)");
		final OrphanConnections.Verdict verdict = OrphanConnections.decide(facts);
		check("a publication keeps the connection", !verdict.eligible && verdict.reason.contains("2 publication(s)"));
		check("listed", verdict.blockers.contains("2 publication(s)"));
	}

	private static void catalogueBlockersKeepItAndTheSource()
	{
		final ConnectionFacts facts = orphan();
		facts.catalogBlockers.add("1 data product(s) including its assets");
		final OrphanConnections.Verdict verdict = OrphanConnections.decide(facts);
		check("a data product on the assets keeps the lot", !verdict.eligible && verdict.reason.contains("data product"));
		check("listed", verdict.blockers.contains("1 data product(s) including its assets"));
	}

	private static void severalCatalogueSourcesKeepTheConnection()
	{
		final ConnectionFacts facts = new ConnectionFacts(CONNECTION);
		OrphanConnections.claim(facts, Arrays.asList("A", "B"));
		facts.connectionExists = true;
		facts.connectionType = "sample-file";
		check("with two sources none is claimed", facts.catalogSourceId.isEmpty());
		facts.otherSourceIds.addAll(Arrays.asList("A", "B")); // what ConnectionCleanup.readCatalog adds
		final OrphanConnections.Verdict verdict = OrphanConnections.decide(facts);
		check("two catalogue sources naming the connection keep it", !verdict.eligible && verdict.reason.contains("A, B"));
		check("and are listed", verdict.blockers.size() == 1 && verdict.blockers.get(0).contains("A, B"));
	}

	private static void aSourceRepointedByHandIsKept()
	{
		final ConnectionFacts facts = orphan();
		facts.catalogSourceConnector = "SomethingElse";
		check("a source re-pointed elsewhere is not deleted", !OrphanConnections.decide(facts).eligible);
	}

	private static void aMissingConnectionIsNotEligible()
	{
		final ConnectionFacts facts = orphan();
		facts.connectionExists = false;
		check("a connection that no longer exists is not eligible", !OrphanConnections.decide(facts).eligible);
	}

	// ---------------------------------------------------------------------------------------- filter, body, guard

	private static void nameFilterIsAPlainSubstring()
	{
		check("blank matches all", OrphanConnections.nameMatches("", "x") && OrphanConnections.nameMatches(null, "x") && OrphanConnections.nameMatches("  "));
		check("case-insensitive substring of the id", OrphanConnections.nameMatches("e2e", CONNECTION, "ECOM_Clients_e2e1017183516"));
		check("on any name, label or source", OrphanConnections.nameMatches("E2E1017", null, "lbl", SOURCE));
		check("no match", !OrphanConnections.nameMatches("zzz", CONNECTION, SOURCE));
		check("a regex is NOT a regex", !OrphanConnections.nameMatches("E2e.*", CONNECTION, SOURCE) && !OrphanConnections.nameMatches("^Ecom", CONNECTION));
		check("a dot is a dot", OrphanConnections.nameMatches("a.b", "xa.by") && !OrphanConnections.nameMatches("a.b", "xaby"));
		check("null names are skipped", !OrphanConnections.nameMatches("x", (String) null));
	}

	private static void bodyAndIds() throws Exception
	{
		check("ok body", OrphanConnections.bodyProblem(json("{\"ids\":[\"A\",\"B\"],\"confirm\":\"2\"}")) == null);
		check("ids deduplicated, in order, trimmed", OrphanConnections.idsOf(json("{\"ids\":[\"A\",\" B \",\"A\"],\"confirm\":\"2\"}")).equals(Arrays.asList("A", "B")));
		check("not an object", OrphanConnections.bodyProblem(json("[1]")) != null && OrphanConnections.bodyProblem(null) != null);
		check("no ids", OrphanConnections.bodyProblem(json("{\"confirm\":\"0\"}")) != null && OrphanConnections.bodyProblem(json("{\"ids\":[]}")) != null);
		check("ids not an array", OrphanConnections.bodyProblem(json("{\"ids\":\"A\"}")) != null);
		check("a blank or non-text id", OrphanConnections.bodyProblem(json("{\"ids\":[\"A\",\"\"]}")) != null && OrphanConnections.bodyProblem(json("{\"ids\":[7]}")) != null);
		final StringBuilder many = new StringBuilder("{\"ids\":[");
		for (int i = 0; i <= OrphanConnections.MAX_IDS; i++)
		{
			many.append(i == 0 ? "" : ",").append("\"c").append(i).append("\"");
		}
		many.append("]}");
		check("too many ids", OrphanConnections.bodyProblem(json(many.toString())) != null);
		check("idsOf null on a bad body", OrphanConnections.idsOf(json("{}")) == null);
	}

	private static void confirmGuard()
	{
		check("exact count passes", OrphanConnections.confirmRefusal("3", 3) == null && OrphanConnections.confirmRefusal(" 3 ", 3) == null);
		check("zero eligible, confirm 0 passes", OrphanConnections.confirmRefusal("0", 0) == null);
		check("a different count is refused, saying both numbers", OrphanConnections.confirmRefusal("4", 3).contains("4") && OrphanConnections.confirmRefusal("4", 3).contains("3"));
		check("missing confirm is refused", OrphanConnections.confirmRefusal(null, 3) != null && OrphanConnections.confirmRefusal("", 3) != null);
		check("non-numeric is refused", OrphanConnections.confirmRefusal("all", 3) != null && OrphanConnections.confirmRefusal("+3", 3) != null
			&& OrphanConnections.confirmRefusal("3.0", 3) != null && OrphanConnections.confirmRefusal("-1", 0) != null);
		check("nothing was deleted is said", OrphanConnections.confirmRefusal("9", 3).contains("nothing was deleted"));
	}

	private static void bothRoutesAreAdministratorOnly()
	{
		check("list is administrator", FactoryRights.route("GET /connections/orphans") == FactoryRight.ADMINISTRATOR);
		check("delete is administrator", FactoryRights.route("POST /connections/orphans/delete") == FactoryRight.ADMINISTRATOR);
		check("both are decided, not defaulted", FactoryRights.routes().containsKey("GET /connections/orphans") && FactoryRights.routes().containsKey("POST /connections/orphans/delete"));
	}

	private static JsonNode json(final String pText) throws Exception
	{
		return OrphanConnectionsTest.MAPPER.readTree(pText);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
