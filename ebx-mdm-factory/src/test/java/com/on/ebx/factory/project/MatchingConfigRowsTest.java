package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.project.MatchingConfigRows.Action;
import com.on.ebx.factory.project.MatchingConfigRows.BusinessObject;
import com.on.ebx.factory.project.MatchingConfigRows.Finding;
import com.on.ebx.factory.project.MatchingConfigRows.ModelState;
import com.on.ebx.factory.project.MatchingConfigRows.TableConfig;

/**
 * JUnit-free: run the main, a failure throws. Lot M18 (2026-10-18): the classifier of the rows the Match and Merge add-on keeps for a publication, on a fixture
 * shaped like the live dataset read the same day (module configurations, a vanished E2E publication with and without a business object, a publication that still exists, a
 * Factory project's own master model, the 36-of-38 linked table configurations).
 */
public final class MatchingConfigRowsTest
{
	private static final String GONE = "E2EMASTER20261018115006_Master";
	private static final String GONE_BO = "E2EMASTER20261018090711_Master";
	private static final String LIVE = "Vol1mR_Master";
	private static final String OWNED = "Tiers_Master";
	private static final String UNKNOWN = "Mystery_Master";

	public static void main(final String[] pArgs) throws Exception
	{
		MatchingConfigRowsTest.names();
		MatchingConfigRowsTest.businessObjectFacts();
		MatchingConfigRowsTest.goneModelWithBusinessObjectIsSweptAndKeepsTableConfigs();
		MatchingConfigRowsTest.goneModelWithOnlyTableConfigsIsListedNotActedOn();
		MatchingConfigRowsTest.existingModelIsListedOnlyWhenNamed();
		MatchingConfigRowsTest.namedExistingModelIsSweptUnlessALiveProjectOwnsIt();
		MatchingConfigRowsTest.unknownModelIsNeverActedOn();
		MatchingConfigRowsTest.moduleConfigurationsAreNeverTouched();
		MatchingConfigRowsTest.remainingIsWhatThe2eCleanupChecks();
		MatchingConfigRowsTest.requestBody();
		System.out.println("MatchingConfigRowsTest OK");
	}

	// ------------------------------------------------------------------------------------------------------------ fixture

	private static List<BusinessObject> businessObjects()
	{
		return Arrays.asList(
			new BusinessObject("2", "urn:ebx:publication:" + LIVE, "/root/Personne"),
			new BusinessObject("9", "urn:ebx:publication:" + GONE_BO, "/root/Client"),
			new BusinessObject("10", "urn:ebx:publication:" + OWNED, "/root/Client"));
	}

	private static List<TableConfig> tableConfigs()
	{
		return Arrays.asList(
			new TableConfig("1", "urn:ebx:module:EBX FastTrack - TIBCO EBX Presales environment:/WEB-INF/ebx/schemas/HumanResources.xsd", "/root/Person", null),
			new TableConfig("65", "urn:ebx:publication:" + LIVE, "/root/Personne", null),
			new TableConfig("66", "urn:ebx:publication:" + LIVE, "/root/Adresse", "2"),
			new TableConfig("115", "urn:ebx:publication:" + GONE, "/root/Client", null),
			new TableConfig("116", "urn:ebx:publication:" + GONE, "/root/Adresse", null),
			new TableConfig("122", "urn:ebx:publication:" + GONE_BO, "/root/Client", "9"),
			new TableConfig("123", "urn:ebx:publication:" + GONE_BO, "/root/Adresse", "9"),
			new TableConfig("140", "urn:ebx:publication:" + OWNED, "/root/Client", "10"),
			new TableConfig("141", "urn:ebx:publication:" + OWNED, "/root/Adresse", "10"),
			new TableConfig("150", "urn:ebx:publication:" + UNKNOWN, "/root/Client", null));
	}

	private static Map<String, ModelState> states()
	{
		final Map<String, ModelState> out = new LinkedHashMap<String, ModelState>();
		out.put(GONE, ModelState.GONE);
		out.put(GONE_BO, ModelState.GONE);
		out.put(LIVE, ModelState.EXISTS);
		out.put(OWNED, ModelState.EXISTS);
		out.put(UNKNOWN, ModelState.UNKNOWN);
		return out;
	}

	private static List<Finding> classify(final String... pNamed)
	{
		return MatchingConfigRows.classify(MatchingConfigRowsTest.businessObjects(), MatchingConfigRowsTest.tableConfigs(), MatchingConfigRowsTest.states(),
			MatchingConfigRowsTest.set(OWNED), MatchingConfigRowsTest.set(pNamed));
	}

	private static Finding of(final List<Finding> pFindings, final String pPublication)
	{
		for (final Finding finding : pFindings)
		{
			if (finding.publication.equals(pPublication))
			{
				return finding;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------------------ cases

	private static void names()
	{
		MatchingConfigRowsTest.check("a publication urn gives its name", GONE.equals(MatchingConfigRows.publicationOf("urn:ebx:publication:" + GONE)));
		MatchingConfigRowsTest.check("a module urn is not a publication", MatchingConfigRows.publicationOf("urn:ebx:module:EBX FastTrack:/WEB-INF/ebx/schemas/Companies.xsd") == null);
		MatchingConfigRowsTest.check("blank and null are not publications", MatchingConfigRows.publicationOf("") == null && MatchingConfigRows.publicationOf(null) == null
			&& MatchingConfigRows.publicationOf("urn:ebx:publication:") == null);
		MatchingConfigRowsTest.check("urn round trip", ("urn:ebx:publication:" + GONE).equals(MatchingConfigRows.urnOf(GONE)));
		MatchingConfigRowsTest.check("entity of a table path", "Client".equals(MatchingConfigRows.entityOf("/root/Client")));
	}

	private static void businessObjectFacts()
	{
		final List<MatchingConfigRows.BusinessObjectFacts> facts = MatchingConfigRows.businessObjectsOf(GONE_BO, MatchingConfigRowsTest.businessObjects(), MatchingConfigRowsTest.tableConfigs());
		MatchingConfigRowsTest.check("one business object for the publication", facts.size() == 1);
		MatchingConfigRowsTest.check("its id and main table", "9".equals(facts.get(0).id) && "/root/Client".equals(facts.get(0).mainTablePath));
		MatchingConfigRowsTest.check("its linked table configurations, nothing of another publication", facts.get(0).memberConfigIds.equals(Arrays.asList("122", "123")));
		MatchingConfigRowsTest.check("members text, main table first and once", "Client, Adresse".equals(facts.get(0).membersText()));
		MatchingConfigRowsTest.check("plan sentence", MatchingConfigRows.planDetail(facts.get(0)).startsWith("Business object of Client: Client, Adresse."));
		MatchingConfigRowsTest.check("a publication with no business object gives none", MatchingConfigRows.businessObjectsOf(GONE, MatchingConfigRowsTest.businessObjects(),
			MatchingConfigRowsTest.tableConfigs()).isEmpty());
		// a business object of ANOTHER publication that happens to share the id must not pick this publication's rows
		final List<MatchingConfigRows.BusinessObjectFacts> mixed = MatchingConfigRows.businessObjectsOf(LIVE, MatchingConfigRowsTest.businessObjects(), MatchingConfigRowsTest.tableConfigs());
		MatchingConfigRowsTest.check("LIVE's business object 2 holds only the Adresse configuration (the main table has none)", mixed.size() == 1 && mixed.get(0).memberConfigIds.equals(Arrays.asList("66")));
		MatchingConfigRowsTest.check("members text still starts with the main table", "Personne, Adresse".equals(mixed.get(0).membersText()));
	}

	private static void goneModelWithBusinessObjectIsSweptAndKeepsTableConfigs()
	{
		final Finding finding = MatchingConfigRowsTest.of(MatchingConfigRowsTest.classify(), GONE_BO);
		MatchingConfigRowsTest.check("listed without being named", finding != null);
		MatchingConfigRowsTest.check("eligible: unlink and delete the business object", finding.eligible && finding.action == Action.UNLINK_AND_DELETE_BUSINESS_OBJECTS);
		MatchingConfigRowsTest.check("the business object row and the two table configurations", finding.businessObjects.size() == 1 && finding.tableConfigs.size() == 2);
		MatchingConfigRowsTest.check("the table configurations of a vanished model stay", finding.tableConfigsKept() == 2 && finding.reason.contains("stay"));
	}

	private static void goneModelWithOnlyTableConfigsIsListedNotActedOn()
	{
		final Finding finding = MatchingConfigRowsTest.of(MatchingConfigRowsTest.classify(), GONE);
		MatchingConfigRowsTest.check("listed", finding != null);
		MatchingConfigRowsTest.check("not eligible: nothing to unlink or delete", !finding.eligible && finding.action == Action.NOTHING);
		MatchingConfigRowsTest.check("the reason says why they stay", finding.reason.contains("2 table configuration(s)") && finding.reason.contains("next start"));
	}

	private static void existingModelIsListedOnlyWhenNamed()
	{
		final List<Finding> unnamed = MatchingConfigRowsTest.classify();
		MatchingConfigRowsTest.check("a publication whose model exists is not listed by default", MatchingConfigRowsTest.of(unnamed, LIVE) == null && MatchingConfigRowsTest.of(unnamed, OWNED) == null);
		MatchingConfigRowsTest.check("nor is the module or the unknown one swept", MatchingConfigRowsTest.of(unnamed, UNKNOWN) == null);
		MatchingConfigRowsTest.check("only the two vanished publications", unnamed.size() == 2);
	}

	private static void namedExistingModelIsSweptUnlessALiveProjectOwnsIt()
	{
		final Finding live = MatchingConfigRowsTest.of(MatchingConfigRowsTest.classify(LIVE), LIVE);
		MatchingConfigRowsTest.check("named and existing: eligible with the table configurations removed by delete_matching_config", live.eligible && live.action == Action.UNLINK_DELETE_AND_REMOVE_TABLE_CONFIGS);
		MatchingConfigRowsTest.check("a named existing model keeps none", live.tableConfigsKept() == 0);
		final Finding owned = MatchingConfigRowsTest.of(MatchingConfigRowsTest.classify(OWNED), OWNED);
		MatchingConfigRowsTest.check("the master model of a live project is never swept", !owned.eligible && owned.ownedByLiveProject && owned.reason.contains("live Factory project"));
	}

	private static void unknownModelIsNeverActedOn()
	{
		final Finding unknown = MatchingConfigRowsTest.of(MatchingConfigRowsTest.classify(UNKNOWN), UNKNOWN);
		MatchingConfigRowsTest.check("named but unknown: listed, not eligible", unknown != null && !unknown.eligible && unknown.action == Action.NOTHING);
		final Map<String, ModelState> noAnswer = new LinkedHashMap<String, ModelState>();
		final List<Finding> none = MatchingConfigRows.classify(MatchingConfigRowsTest.businessObjects(), MatchingConfigRowsTest.tableConfigs(), noAnswer, MatchingConfigRowsTest.set(), MatchingConfigRowsTest.set());
		MatchingConfigRowsTest.check("no model state at all: nothing is listed, nothing is swept", none.isEmpty());
	}

	private static void moduleConfigurationsAreNeverTouched()
	{
		for (final Finding finding : MatchingConfigRowsTest.classify(LIVE, OWNED, UNKNOWN, GONE, GONE_BO))
		{
			for (final TableConfig row : finding.tableConfigs)
			{
				MatchingConfigRowsTest.check("only publication rows in a finding", row.dataModel.startsWith(MatchingConfigRows.PUBLICATION_PREFIX));
			}
		}
	}

	private static void remainingIsWhatThe2eCleanupChecks()
	{
		final List<String> left = MatchingConfigRows.remaining(GONE_BO, MatchingConfigRowsTest.businessObjects(), MatchingConfigRowsTest.tableConfigs());
		MatchingConfigRowsTest.check("one business object and two table configurations: " + left, left.size() == 3 && left.get(0).startsWith("business object configuration 9"));
		MatchingConfigRowsTest.check("the linked ones say so", left.get(1).contains("linked to business object 9"));
		MatchingConfigRowsTest.check("nothing for a publication the add-on does not know", MatchingConfigRows.remaining("Nope_Master", MatchingConfigRowsTest.businessObjects(), MatchingConfigRowsTest.tableConfigs()).isEmpty());
		MatchingConfigRowsTest.check("a bare table configuration counts too", MatchingConfigRows.remaining(GONE, MatchingConfigRowsTest.businessObjects(), MatchingConfigRowsTest.tableConfigs()).size() == 2);
		final String sentence = MatchingConfigRows.leftSentence(GONE_BO, 1, 2, true);
		MatchingConfigRowsTest.check("the deletion's warning names the publication and the sweep route", sentence.contains("urn:ebx:publication:" + GONE_BO) && sentence.contains("1 business-object")
			&& sentence.contains("GET /matching/orphans?publication=" + GONE_BO) && sentence.contains("whose model or dataspace was deleted"));
		MatchingConfigRowsTest.check("with table configurations left, the warning names the likely cause (the distribution triggers reading Linked_Field_State)",
			sentence.contains("Table not found") && sentence.contains("Linked_Field_State") && sentence.contains("distribution triggers"));
		MatchingConfigRowsTest.check("with only a business object left it does not", !MatchingConfigRows.leftSentence(GONE_BO, 1, 0, true).contains("Linked_Field_State"));
	}

	private static void requestBody() throws Exception
	{
		final ObjectMapper mapper = new ObjectMapper();
		MatchingConfigRowsTest.check("a good body", MatchingConfigRows.bodyProblem(mapper.readTree("{\"publications\":[\"" + GONE + "\"],\"confirm\":\"1\"}")) == null);
		MatchingConfigRowsTest.check("no body", MatchingConfigRows.bodyProblem(null) != null);
		MatchingConfigRowsTest.check("no names", MatchingConfigRows.bodyProblem(mapper.readTree("{\"publications\":[],\"confirm\":\"0\"}")) != null);
		MatchingConfigRowsTest.check("a urn is refused (a bare name only)", MatchingConfigRows.bodyProblem(mapper.readTree("{\"publications\":[\"urn:ebx:publication:X\"]}")) != null);
		MatchingConfigRowsTest.check("a number is refused", MatchingConfigRows.bodyProblem(mapper.readTree("{\"publications\":[1]}")) != null);
		MatchingConfigRowsTest.check("names are deduplicated in order", MatchingConfigRows.namesOf(mapper.readTree("{\"publications\":[\"B_Master\",\"A_Master\",\"B_Master\"]}")).equals(Arrays.asList("B_Master", "A_Master")));
		MatchingConfigRowsTest.check("unusable body gives no names", MatchingConfigRows.namesOf(mapper.readTree("{}")) == null);
		MatchingConfigRowsTest.check("confirm must be the eligible count", MatchingConfigRows.confirmRefusal("2", 2) == null && MatchingConfigRows.confirmRefusal("1", 2) != null
			&& MatchingConfigRows.confirmRefusal("", 2) != null && MatchingConfigRows.confirmRefusal("two", 2) != null && MatchingConfigRows.confirmRefusal(" 3 ", 3) == null);
		final List<String> many = new ArrayList<String>();
		for (int i = 0; i <= MatchingConfigRows.MAX_PUBLICATIONS; i++)
		{
			many.add("\"P" + i + "\"");
		}
		MatchingConfigRowsTest.check("too many names", MatchingConfigRows.bodyProblem(mapper.readTree("{\"publications\":[" + String.join(",", many) + "]}")) != null);
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
