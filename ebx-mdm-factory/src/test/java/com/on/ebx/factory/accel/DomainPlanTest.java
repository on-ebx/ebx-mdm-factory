package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free: run the main, a failure throws. */
public final class DomainPlanTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final PackCatalog catalog = PackCatalog.load();
		DomainPlanTest.plainCompany(catalog);
		DomainPlanTest.withDifferences(catalog);
		DomainPlanTest.matchingRepointed(catalog);
		DomainPlanTest.names();
		System.out.println("DomainPlanTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		PackCatalogTest.check(pWhat, pCondition);
	}

	private static DomainPlan.Decision decision(final String pNature, final String pWhere, final String pDetailJson) throws Exception
	{
		final DomainPlan.Decision d = new DomainPlan.Decision();
		d.id = "d" + pNature + pWhere;
		d.nature = pNature;
		d.what = pNature + " " + pWhere;
		d.where = pWhere;
		d.detail = pDetailJson == null ? null : Res.MAPPER.readTree(pDetailJson);
		return d;
	}

	private static List<DomainPlan.PackChoice> choices()
	{
		return Arrays.asList(new DomainPlan.PackChoice("company", new ArrayList<String>()));
	}

	private static boolean hasOp(final List<ObjectNode> pOps, final String pOp, final String pArg, final String pValue)
	{
		for (final ObjectNode op : pOps)
		{
			if (pOp.equals(op.path("op").asText()) && pValue.equals(op.path("arguments").path(pArg).asText()))
			{
				return true;
			}
		}
		return false;
	}

	private static void plainCompany(final PackCatalog c) throws Exception
	{
		final DomainPlan plan = DomainPlan.build(c, "Fournisseurs", "Suppliers", choices(), new ArrayList<DomainPlan.Decision>());
		check("company pulls reference-data first", plan.packKeys.size() == 2 && "reference-data".equals(plan.packKeys.get(0)) && "company".equals(plan.packKeys.get(1)));
		check("names", "Fournisseurs_Domain".equals(plan.names.model) && "Fournisseurs_Domain".equals(plan.names.dataspace) && "Domain".equals(plan.names.dataset));
		check("reference tables first", hasOp(plan.modelOps, "create_table", "table_path", "/root/ReferenceData/Country") && hasOp(plan.modelOps, "create_table", "table_path", "/root/Company"));
		check("audit group on Company", hasOp(plan.modelOps, "create_group", "group_name", "Audit"));
		check("contacts group on Company", hasOp(plan.modelOps, "create_field", "field_path", "/root/Company/Contact/phone"));
		check("default labels", "${./Identification/name}".equals(plan.defaultLabels.get("/root/Company")) && plan.defaultLabels.containsKey("/root/ReferenceData/Country"));
		check("documentation ops flat", !plan.documentationOps.isEmpty() && plan.documentationOps.get(0).path("arguments").has("label") && plan.documentationOps.get(0).path("arguments").has("locale")
				&& !plan.documentationOps.get(0).path("arguments").has("documentation"));
		// block scripts: 2 audit triggers x 4 tables, 3 contact constraints x 2 tables
		int triggers = 0;
		int constraints = 0;
		for (final DomainPlan.ScriptItem s : plan.scripts)
		{
			check("body resolved " + s.name, !s.body.contains("${"));
			check("idePath " + s.name, s.idePath.startsWith("data-model/Fournisseurs_Domain/root/"));
			if ("trigger".equals(s.kind))
			{
				triggers++;
			}
			else
			{
				constraints++;
			}
		}
		check("8 audit triggers", triggers == 8);
		check("6 contact constraints", constraints == 6);
		check("no draft", plan.drafts().isEmpty());
		check("one layout (Company) with the audit panel", plan.layouts.size() == 1 && "/root/Company".equals(plan.layouts.get(0).tablePath)
				&& plan.layouts.get(0).layout.toString().contains("audit-company")
				&& plan.layouts.get(0).layout.toString().contains("${publication}"));
		check("one workflow", plan.workflows.size() == 1 && "Fournisseurs_CompanyCreationRequest".equals(plan.workflows.get(0).modelName));
		final WorkflowAssembler.Params p = plan.workflows.get(0).params;
		check("workflow params", "RRequester".equals(p.requesterProfile) && p.reviewers.size() == 1 && "RReviewer".equals(p.reviewers.get(0).profile)
				&& "Fournisseurs_Domain".equals(p.domainDataspace) && "Approve company".equals(p.acceptLabel) && "Approuver la société".equals(p.acceptLabelFr));
		WorkflowAssembler.assemble(p);
		check("roles", plan.roles.size() == 3 && "RRequester".equals(plan.roles.get(0).profile) && "RDataSteward".equals(plan.roles.get(2).profile));
		check("reference tables to load", plan.referenceTables.size() == 9);
		check("matching on Company", plan.matching.size() == 1 && "Company_entry".equals(plan.matching.get(0).processCode)
				&& 3 == plan.matching.get(0).matchingPolicy.path("matchingOnCreation").asInt() && 3 == plan.matching.get(0).matchingPolicy.path("matchingOnUpdate").asInt());
		check("mame optional", plan.optionalModules.contains("ebx-addon-mame"));
	}

	private static void withDifferences(final PackCatalog c) throws Exception
	{
		final List<DomainPlan.Decision> ds = new ArrayList<DomainPlan.Decision>();
		ds.add(decision("REMOVE", "/root/Contact", null));
		ds.add(decision("CHANGE", "/root/Company/Identification/name", "{\"property\":\"maxLength\",\"value\":\"200\"}"));
		ds.add(decision("ADD", "/root/Company/Purchasing/paymentTerm", "{\"kind\":\"field\",\"fieldType\":\"string\",\"maxLength\":40}"));
		final DomainPlan.Decision lib = decision("USE_LIBRARY", "/root/Company/Legal/registrationNumber", "{\"exactLength\":9,\"message\":\"The SIREN check digit does not balance (Luhn).\"}");
		lib.libraryFunction = "Fasttrack.lib.validator.checkdigit.luhn.isValid";
		ds.add(lib);
		final DomainPlan.Decision bad = decision("USE_LIBRARY", "/root/Company/Legal/registrationNumber", null);
		bad.libraryFunction = "Fasttrack.lib.nope.isValid";
		ds.add(bad);
		ds.add(decision("ADD", "/root/Company/Contact/email", "{\"kind\":\"rule\",\"description\":\"BIC country must match the IBAN country\"}"));
		final DomainPlan.Decision wf = decision("USE_WORKFLOW", "/root/Company", "{\"reviewers\":[{\"role\":\"Purchasing\",\"label\":\"Purchasing\"},{\"role\":\"Finance\",\"label\":\"Finance\"}]}");
		wf.workflowType = "creationWithReview";
		ds.add(wf);
		final DomainPlan plan = DomainPlan.build(c, "Fournisseurs", "Suppliers", choices(), ds);
		check("Contact table removed", !hasOp(plan.modelOps, "create_table", "table_path", "/root/Contact") && !hasOp(plan.modelOps, "create_field", "field_path", "/root/Contact/firstName"));
		check("no block on the removed table", !plan.modelOps.toString().contains("/root/Contact/"));
		boolean changed = false;
		for (final ObjectNode op : plan.modelOps)
		{
			if ("set_element_property".equals(op.path("op").asText()) && "/root/Company/Identification/name".equals(op.path("arguments").path("element_path").asText())
					&& "maxLength".equals(op.path("arguments").path("property").asText()))
			{
				check("CHANGE rewrote in place", "200".equals(op.path("arguments").path("value").asText()));
				changed = true;
			}
		}
		check("maxLength found", changed);
		check("ADD field", hasOp(plan.modelOps, "create_field", "field_path", "/root/Company/Purchasing/paymentTerm"));
		DomainPlan.ScriptItem luhn = null;
		DomainPlan.ScriptItem draft = null;
		for (final DomainPlan.ScriptItem s : plan.scripts)
		{
			if (s.source.startsWith("library:"))
			{
				luhn = s;
			}
			if (s.draft)
			{
				draft = s;
			}
		}
		check("library rule is a node constraint", luhn != null && "ConstraintOnNode".equals(luhn.type) && "/root/Company/Legal/registrationNumber".equals(luhn.elementPath));
		check("wraps the project rule then the library", luhn.body.indexOf(".length <> 9") > 0 && luhn.body.indexOf(".length <> 9") < luhn.body.indexOf("lib.isValid")
				&& luhn.body.contains("import Fasttrack.lib.validator.checkdigit.luhn as lib;") && luhn.body.contains("record.Legal.registrationNumber"));
		check("unknown library refused with a note", plan.notes.toString().contains("Fasttrack.lib.nope.isValid"));
		check("draft rule authored but flagged", draft != null && draft.draft && draft.body.startsWith("// DRAFT") && draft.idePath.contains("@nodeConstraint")
				&& plan.drafts().size() == 1);
		check("workflow override has two reviewers", plan.workflows.get(0).params.reviewers.size() == 2 && "RFinance".equals(plan.workflows.get(0).params.reviewers.get(1).profile));
		boolean finance = false;
		for (final DomainPlan.Role r : plan.roles)
		{
			finance |= "RFinance".equals(r.profile);
		}
		check("roles follow the workflow", finance);
		check("Contact layout absent", plan.layouts.size() == 1);
		// the removed table's reference data still loads; Contact is not one of them
		check("reference tables unchanged", plan.referenceTables.size() == 9);
	}

	private static void matchingRepointed(final PackCatalog c) throws Exception
	{
		final List<DomainPlan.Decision> ds = new ArrayList<DomainPlan.Decision>();
		ds.add(decision("REMOVE", "/root/Company/Legal/registrationNumber", null));
		final DomainPlan plan = DomainPlan.build(c, "P", "P", choices(), ds);
		check("matching kept", plan.matching.size() == 1);
		final ObjectNode policy = plan.matching.get(0).matchingPolicy;
		check("registration field gone from candidates", !policy.path("fields").toString().contains("registrationNumber"));
		check("root test skipped, name/postcode test is the root", !policy.path("decisionTree").toString().contains("registrationNumber")
				&& "Close name, same postcode".equals(policy.path("decisionTree").path("name").asText()));
		final List<DomainPlan.Decision> all = new ArrayList<DomainPlan.Decision>();
		all.add(decision("REMOVE", "/root/Company", null));
		final DomainPlan none = DomainPlan.build(c, "P", "P", choices(), all);
		check("no table, no matching, no workflow", none.matching.isEmpty() && none.workflows.isEmpty() && none.layouts.isEmpty());
		final JsonNode unused = null;
		check("unused", unused == null);
	}

	private static void names()
	{
		check("identifier", "X1_a_b".equals(DomainPlan.identifier("1_a b")) && "Fo_urn".equals(DomainPlan.identifier("Fo urn")));
	}
}
