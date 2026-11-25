package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot C1 integration: what sub-lot C parses from the measured answers (ProposalFixturesTest) is what sub-lot D's {@link DomainPlan} reads. JUnit-free:
 * run the main, a failure throws.
 */
public final class ProposalToPlanTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final PackCatalog catalog = PackCatalog.load();
		ProposalToPlanTest.measuredAnswers(catalog);
		ProposalToPlanTest.shapes();
		System.out.println("ProposalToPlanTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		PackCatalogTest.check(pWhat, pCondition);
	}

	private static DomainPlan.Decision decision(final int pId, final Difference pDifference) throws Exception
	{
		final DomainPlan.Decision d = new DomainPlan.Decision();
		d.id = String.valueOf(pId);
		d.pass = pDifference.pass();
		d.nature = pDifference.nature().name();
		d.what = pDifference.what();
		d.where = pDifference.where();
		d.packKey = pDifference.packKey();
		d.blockKey = pDifference.blockKey();
		d.libraryFunction = pDifference.libraryFunction();
		d.workflowType = pDifference.workflowType();
		// the round trip through the Difference.detail column, as DifferenceWrites then DomainInputs do
		d.detail = pDifference.detailJson().isEmpty() ? null : Res.MAPPER.readTree(pDifference.detailJson());
		return d;
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

	private static void measuredAnswers(final PackCatalog catalog) throws Exception
	{
		final List<Difference> decided = ProposalFixturesTest.decidedPass1();
		final AdaptationContext context = ProposalFixturesTest.context(catalog, decided);
		final List<DomainPlan.Decision> decisions = new ArrayList<DomainPlan.Decision>();
		int id = 1;
		for (final Difference d : decided)
		{
			decisions.add(decision(id++, d));
		}
		final DifferenceCheck.Batch pass2 = DifferenceCheck.checkAll(2, AdaptationLlmPass.parse(2, ProposalFixturesTest.PASS2), context.checkContext());
		for (final DifferenceCheck.Verdict v : pass2.kept)
		{
			decisions.add(decision(id++, v.difference()));
		}
		final DifferenceCheck.Batch pass4 = DifferenceCheck.checkAll(4, AdaptationLlmPass.parse(4, ProposalFixturesTest.PASS4), context.checkContext());
		for (final DifferenceCheck.Verdict v : pass4.kept)
		{
			decisions.add(decision(id++, v.difference()));
		}
		final List<DomainPlan.PackChoice> choices = new ArrayList<DomainPlan.PackChoice>();
		choices.add(new DomainPlan.PackChoice("company", new ArrayList<String>()));
		final DomainPlan plan = DomainPlan.build(catalog, "Fournisseurs", "Suppliers", choices, decisions);

		check("pass 1 ADD table (decided) creates the table", hasOp(plan.modelOps, "create_table", "table_path", "/root/BankAccount"));
		check("pass 1 ADD fields create the fields", hasOp(plan.modelOps, "create_field", "field_path", "/root/BankAccount/iban")
			&& hasOp(plan.modelOps, "create_field", "field_path", "/root/BankAccount/bic"));
		boolean luhn = false;
		boolean bicDraft = false;
		for (final DomainPlan.ScriptItem s : plan.scripts)
		{
			luhn |= s.source.startsWith("library:Fasttrack.lib.validator.checkdigit.luhn") && !s.draft && s.body.contains("lib.isValid");
			bicDraft |= s.draft && s.source.startsWith("rule:") && s.elementPath.endsWith("/bic");
		}
		check("pass 2 USE_LIBRARY (SIREN luhn) becomes a published constraint script", luhn);
		check("pass 2 ADD rule (BIC) becomes a held-back draft script", bicDraft);
		check("pass 4 USE_WORKFLOW becomes a creationWithReview workflow", plan.workflows.size() == 1 && plan.workflows.get(0).params.reviewers.size() == 2
			&& "RPurchasing".equals(plan.workflows.get(0).params.reviewers.get(0).profile));
		boolean purchasing = false;
		boolean finance = false;
		for (final DomainPlan.Role r : plan.roles)
		{
			purchasing |= "Purchasing".equals(r.name);
			finance |= "Finance".equals(r.name);
		}
		check("pass 4 ADD roles become roles", purchasing && finance);
	}

	private static void shapes() throws Exception
	{
		// pass 1 as measured: a prose "where" is never turned into an operation, the table fields given as a string are split
		final List<Difference> pass1 = AdaptationLlmPass.parse(1, ProposalFixturesTest.PASS1);
		final Difference table = pass1.get(0);
		check("a comma list of fields becomes an array", table.detail().path("fields").isArray() && table.detail().path("fields").size() == 3);
		check("kind kept", "table".equals(table.detail().path("kind").asText()));
		final PackCatalog catalog = PackCatalog.load();
		final List<DomainPlan.PackChoice> choices = new ArrayList<DomainPlan.PackChoice>();
		choices.add(new DomainPlan.PackChoice("company", new ArrayList<String>()));
		final List<DomainPlan.Decision> one = new ArrayList<DomainPlan.Decision>();
		one.add(decision(1, table));
		final DomainPlan prose = DomainPlan.build(catalog, "P", "P", choices, one);
		check("a prose where is skipped with a note", !hasOp(prose.modelOps, "create_table", "table_path", table.where()) && !prose.notes.isEmpty());

		// a field with type/target and a group
		final List<Difference> fix = AdaptationLlmPass.parse(1,
			"[{\"nature\":\"ADD\",\"what\":\"owner\",\"where\":\"/root/Company/Audit/owner\",\"detail\":{\"type\":\"string\",\"maxLength\":40,\"target\":\"/root/Company\"},\"why\":\"x\"},"
				+ "{\"nature\":\"ADD\",\"what\":\"grp\",\"where\":\"/root/Company/Extra\",\"detail\":{\"kind\":\"group\"},\"why\":\"x\"}]");
		check("type is renamed fieldType, kind inferred", "fieldType".equals("fieldType") && "string".equals(fix.get(0).detail().path("fieldType").asText())
			&& !fix.get(0).detail().has("type") && "field".equals(fix.get(0).detail().path("kind").asText()));
		final List<DomainPlan.Decision> two = new ArrayList<DomainPlan.Decision>();
		two.add(decision(1, fix.get(0)));
		two.add(decision(2, fix.get(1)));
		final DomainPlan fields = DomainPlan.build(catalog, "P", "P", choices, two);
		check("field with a foreign key", hasOp(fields.modelOps, "create_field", "field_path", "/root/Company/Audit/owner")
			&& hasOp(fields.modelOps, "add_foreign_key", "target_table_path", "/root/Company"));
		check("group", hasOp(fields.modelOps, "create_group", "group_name", "Extra") && hasOp(fields.modelOps, "create_group", "table_path", "/root/Company"));

		// pass 3: the layout is wrapped as {"layout": ...} and D finds it
		final List<Difference> forms = AdaptationLlmPass.parse(3, "[{\"table\":\"/root/Company\",\"layout\":{\"tabs\":[{\"title\":\"Main\",\"rows\":[]}]},\"why\":\"x\"}]");
		final JsonNode detail = forms.get(0).detail();
		check("layout wrapped", detail.has("layout") && detail.get("layout").has("tabs"));
		final List<DomainPlan.Decision> three = new ArrayList<DomainPlan.Decision>();
		three.add(decision(1, forms.get(0)));
		final DomainPlan layout = DomainPlan.build(catalog, "P", "P", choices, three);
		boolean found = false;
		for (final DomainPlan.LayoutItem l : layout.layouts)
		{
			found |= "/root/Company".equals(l.tablePath) && l.layout.path("tabs").size() >= 1 && "Main".equals(l.layout.path("tabs").get(0).path("title").asText());
		}
		check("the layout reaches the plan", found);
	}

	private ProposalToPlanTest()
	{
	}
}
