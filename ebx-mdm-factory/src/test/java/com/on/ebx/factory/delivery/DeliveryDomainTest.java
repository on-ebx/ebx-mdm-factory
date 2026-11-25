package com.on.ebx.factory.delivery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws. Fixture = the real Companies project of 2026-09-22 (two
 * Snowflake sources ERP and CRM, three zones generated, one applied matching plan on Company whose
 * add-on configuration row is id 9 - read live), plus the refusals a half-generated project must name.
 */
public final class DeliveryDomainTest
{
	public static void main(final String[] pArgs)
	{
		final DeliveryDomain.Locations locations = new DeliveryDomain.Locations("MdmFactory", "MdmFactory", "Connectors", "Connectors");
		final List<DeliveryDomain.Source> sources = Arrays.asList(
			new DeliveryDomain.Source("ERP", "Companies_Landing_ERP", "Companies_Landing_ERP", "ERP", "SNOW_DEMO", true, true),
			new DeliveryDomain.Source("CRM", "Companies_Landing_CRM", "Companies_Landing_CRM", "CRM", "SNOW_DEMO", true, true));
		final DeliveryDomain.Project project = new DeliveryDomain.Project("Companies", "Companies_Landing", "Companies_Staging", "Staging",
			"Companies_Staging", true, true, "Companies_Master", "Master", "Companies_Master", true, true);
		final List<DeliveryDomain.Matching> matching = Arrays.asList(new DeliveryDomain.Matching("Company", "/root/Company", true, "9"),
			new DeliveryDomain.Matching("Contact", "/root/Contact", false, null));

		final DeliveryDomain.Result full = DeliveryDomain.compose(project, sources, matching, locations);
		DeliveryDomainTest.check("domain name", "mdm.factory.Companies".equals(full.domainName));
		DeliveryDomainTest.check("nothing refused on a fully generated project", full.refused.isEmpty());
		final List<String> order = new ArrayList<String>();
		for (final DeliveryDomain.Component component : full.components)
		{
			order.add(component.typeName + " " + component.id);
		}
		// dependencies first: dataspace, model, publication, dataset - per zone, landing then staging then master
		DeliveryDomainTest.check("landing dataspace first, with EBX's B prefix", order.get(0).equals("Dataspace BCompanies_Landing"));
		DeliveryDomainTest.check("ERP model before its publication before its dataset", order.indexOf("Datamodel Companies_Landing_ERP") < order.indexOf("DatamodelPublication Companies_Landing_ERP")
			&& order.indexOf("DatamodelPublication Companies_Landing_ERP") < order.indexOf("Dataset ERP"));
		DeliveryDomainTest.check("one landing dataspace for two sources", order.stream().filter("Dataspace BCompanies_Landing"::equals).count() == 1);
		DeliveryDomainTest.check("staging after landing", order.indexOf("Dataspace BCompanies_Staging") > order.indexOf("Dataset CRM"));
		DeliveryDomainTest.check("master after staging", order.indexOf("Dataspace BCompanies_Master") > order.indexOf("Dataset Staging"));
		DeliveryDomainTest.check("every Factory decision table, whole", order.contains("DataTable /root/Project") && order.contains("DataTable /root/MatchPlan")
			&& order.contains("DataTable /root/ReferenceBinding"));
		DeliveryDomainTest.check("never the account, the LLM trace, the runs or the cross-references", !order.contains("DataTable /root/FactorySettings")
			&& !order.contains("DataTable /root/AgentRun") && !order.contains("DataTable /root/Xref") && !order.contains("DataTable /root/PipelineStep"));
		DeliveryDomainTest.check("Connectors' flow tables, never Connector itself", order.contains("DataTable /root/Mapping") && order.contains("DataTable /root/MappingRule")
			&& !order.contains("DataTable /root/Connector"));
		DeliveryDomainTest.check("the applied matching configuration by its add-on row id", order.contains("ebx-addon-mame-MatchingConfiguration 9"));
		DeliveryDomainTest.check("no configuration for an entity without an applied plan", !order.stream().anyMatch(o -> o.startsWith("ebx-addon-mame") && !o.endsWith(" 9")));
		final DeliveryDomain.Component factoryTable = full.components.stream().filter(c -> "/root/Project".equals(c.id)).findFirst().get();
		DeliveryDomainTest.check("a table's context names its dataspace (B prefix) and dataset",
			"BMdmFactory".equals(factoryTable.context.get("Dataspace")) && "MdmFactory".equals(factoryTable.context.get("Dataset")));
		final DeliveryDomain.Component dataset = full.components.stream().filter(c -> "Dataset".equals(c.typeName) && "Staging".equals(c.id)).findFirst().get();
		DeliveryDomainTest.check("a dataset's context names its dataspace only", dataset.context.size() == 1 && "BCompanies_Staging".equals(dataset.context.get("Dataspace")));
		DeliveryDomainTest.check("component names are folder-safe", full.components.stream().allMatch(c -> c.name.matches("[A-Za-z0-9_]+")));
		DeliveryDomainTest.check("component names are unique", full.components.stream().map(c -> c.name).distinct().count() == full.components.size());

		final ObjectNode json = DeliveryDomain.toJson(full);
		DeliveryDomainTest.check("json: each component is a ready request body", json.get("components").get(0).get("element").get("typeName").asText().equals("Dataspace")
			&& json.get("components").get(0).has("name"));
		DeliveryDomainTest.check("json: notes explain the whole-table choice", json.get("notes").size() >= 2);

		// a project whose staging model is generated but never published: staging refused by name, landing still travels
		final DeliveryDomain.Project halfDone = new DeliveryDomain.Project("Companies", "Companies_Landing", "Companies_Staging", "Staging",
			"Companies_Staging", true, false, "Companies_Master", "Master", "Companies_Master", false, false);
		final DeliveryDomain.Result partial = DeliveryDomain.compose(halfDone, sources, matching, locations);
		DeliveryDomainTest.check("staging refused, naming the missing step", partial.refused.contains("staging: STAGING_DATASET is not done"));
		DeliveryDomainTest.check("master not started: nothing said about it", partial.refused.stream().noneMatch(r -> r.startsWith("master")));
		DeliveryDomainTest.check("matching refused when the master zone does not travel", partial.refused.stream().anyMatch(r -> r.startsWith("matching Company")));
		DeliveryDomainTest.check("landing still travels", partial.components.stream().anyMatch(c -> "Companies_Landing_ERP".equals(c.id)));
		DeliveryDomainTest.check("no staging component", partial.components.stream().noneMatch(c -> "Companies_Staging".equals(c.id)));

		// a source whose landing model is not done: that source refused, the other delivered
		final List<DeliveryDomain.Source> oneMissing = Arrays.asList(sources.get(0),
			new DeliveryDomain.Source("CRM", "Companies_Landing_CRM", "Companies_Landing_CRM", "CRM", "SNOW_DEMO", false, false));
		final DeliveryDomain.Result oneSource = DeliveryDomain.compose(project, oneMissing, matching, locations);
		DeliveryDomainTest.check("CRM refused by its step", oneSource.refused.contains("landing CRM: LANDING_MODEL is not done"));
		DeliveryDomainTest.check("ERP delivered", oneSource.components.stream().anyMatch(c -> "ERP".equals(c.id)));

		// an applied plan whose add-on row was not found: refused, never dropped in silence
		final DeliveryDomain.Result noRow = DeliveryDomain.compose(project, sources,
			Arrays.asList(new DeliveryDomain.Matching("Company", "/root/Company", true, null)), locations);
		DeliveryDomainTest.check("missing configuration row refused", noRow.refused.contains("matching Company: no Match and Merge configuration row found for /root/Company"));

		// Connectors absent: flows not delivered, said in the notes
		final DeliveryDomain.Result noConnectors = DeliveryDomain.compose(project, sources, matching, new DeliveryDomain.Locations("MdmFactory", "MdmFactory", null, null));
		DeliveryDomainTest.check("no Connectors table without Connectors", noConnectors.components.stream().noneMatch(c -> "/root/Mapping".equals(c.id)));
		DeliveryDomainTest.check("and a note says so", noConnectors.notes.stream().anyMatch(n -> n.contains("not installed")));

		DeliveryDomainTest.check("safe name replaces anything outside letters, digits and underscore", "SNOW_DEMO_v2".equals(DeliveryDomain.safeName("SNOW-DEMO v2")));

		// measured 2026-09-22 on the target: the staging model's constraints import Fasttrack.lib.phone (which imports
		// Fasttrack.lib.validator.regex) and Fasttrack.lib.iban; without their sources the publication import fails
		final DeliveryDomain.Result withLibraries = DeliveryDomain.compose(project, sources, matching, locations,
			Arrays.asList("Fasttrack.lib.phone", "Fasttrack.lib.iban", "Fasttrack.lib.validator.regex"));
		DeliveryDomainTest.check("libraries first, as ScriptSource elements with the measured id shape",
			"ScriptSource".equals(withLibraries.components.get(0).typeName) && "definition.library.Fasttrack.lib.phone".equals(withLibraries.components.get(0).id)
				&& "ScriptSource".equals(withLibraries.components.get(2).typeName) && "Dataspace".equals(withLibraries.components.get(3).typeName));
		DeliveryDomainTest.check("library component names are folder-safe and unique", withLibraries.components.stream().map(c -> c.name).distinct().count() == withLibraries.components.size()
			&& "library_Fasttrack_lib_phone".equals(withLibraries.components.get(0).name));
		DeliveryDomainTest.check("a note says the target must publish them", withLibraries.notes.stream().anyMatch(n -> n.contains("publish them")));
		DeliveryDomainTest.check("no library, no note about them", full.notes.stream().noneMatch(n -> n.contains("Script libraries")));
		System.out.println("DeliveryDomainTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
