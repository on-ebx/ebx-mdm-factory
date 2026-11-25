package com.on.ebx.factory.accel;

import java.util.List;

import com.on.ebx.factory.accel.Difference.Nature;
import com.on.ebx.factory.accel.DifferenceCheck.Batch;
import com.on.ebx.factory.accel.DifferenceCheck.Kind;
import com.on.ebx.factory.accel.DifferenceCheck.Verdict;

/** JUnit-free: run the main, a failure throws. The inputs are the answers measured on 2026-10-12 (ProposalFixturesTest). */
public final class DifferenceCheckTest
{
	public static void main(final String[] pArgs)
	{
		final PackCatalog catalogue = PackCatalog.load();
		DifferenceCheckTest.measuredPass1(catalogue);
		DifferenceCheckTest.measuredPass2(catalogue);
		DifferenceCheckTest.dateIsValidBareIsRefusedQualifiedIsKnown(catalogue);
		DifferenceCheckTest.approvalsInPass2AreDropped(catalogue);
		DifferenceCheckTest.removeMainTableRefused(catalogue);
		DifferenceCheckTest.addOfAnExistingPathBecomesChange(catalogue);
		DifferenceCheckTest.newFieldUnderAnAddOfTheSameAnswerIsPlaced(catalogue);
		DifferenceCheckTest.workflowAndBlockRules(catalogue);
		DifferenceCheckTest.pass4RolesAndPass3Layouts(catalogue);
		DifferenceCheckTest.looksLikeExisting(catalogue);
		DifferenceCheckTest.refusalTextForTheReask(catalogue);
		System.out.println("DifferenceCheckTest OK");
	}

	static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static Difference one(final int pPass, final String pJson)
	{
		return AdaptationLlmPass.parse(pPass, "[" + pJson + "]").get(0);
	}

	static void measuredPass1(final PackCatalog pCatalogue)
	{
		final DifferenceCheck.CheckContext context = ProposalFixturesTest.context(pCatalogue, null).checkContext();
		final List<Difference> answer = AdaptationLlmPass.parse(1, ProposalFixturesTest.PASS1);
		final Batch batch = DifferenceCheck.checkAll(1, answer, context);
		check("SupplierProfile where as prose is kept to place", batch.kept.size() == 1 && batch.kept.get(0).toPlace());
		check("to place line is marked TO_PLACE", batch.kept.get(0).difference().placeState() == Difference.PlaceState.TO_PLACE);
		check("the attachments block is not in the catalogue: refused", batch.refused.size() == 1 && batch.refused.get(0).reasonKey().equals("accel.pass.refusal.unknownBlock"));
		check("nothing dropped", batch.dropped == 0);
	}

	static void measuredPass2(final PackCatalog pCatalogue)
	{
		final DifferenceCheck.CheckContext context = ProposalFixturesTest.context(pCatalogue, ProposalFixturesTest.decidedPass1()).checkContext();
		final Batch batch = DifferenceCheck.checkAll(2, AdaptationLlmPass.parse(2, ProposalFixturesTest.PASS2), context);
		// luhn, iban, bic draft, phone = kept; bare date.isValid = refused; two approvals = dropped
		check("four kept", batch.kept.size() == 4);
		check("all four placed", allOk(batch.kept));
		check("the bare date.isValid is refused as not in the catalogue", batch.refused.size() == 1
			&& batch.refused.get(0).reasonKey().equals("accel.pass.refusal.unknownLibrary") && batch.refused.get(0).reason().contains("not in the catalogue"));
		check("the two approvals belong to pass 4: dropped", batch.dropped == 2);
	}

	private static boolean allOk(final List<Verdict> pVerdicts)
	{
		for (final Verdict v : pVerdicts)
		{
			if (!v.ok())
			{
				return false;
			}
		}
		return true;
	}

	static void dateIsValidBareIsRefusedQualifiedIsKnown(final PackCatalog pCatalogue)
	{
		final DifferenceCheck.CheckContext context = ProposalFixturesTest.context(pCatalogue, null).checkContext();
		final String body = ",\"where\":\"/root/Company/Legal/incorporationDate\",\"what\":\"d\",\"detail\":{}}";
		final Verdict bare = DifferenceCheck.check(2, one(2, "{\"nature\":\"USE_LIBRARY\",\"function\":\"date.isValid\"" + body), context);
		check("bare date.isValid refused", bare.refused());
		final Verdict qualified = DifferenceCheck.check(2, one(2, "{\"nature\":\"USE_LIBRARY\",\"function\":\"Fasttrack.lib.validator.date.isValid\"" + body), context);
		check("fully qualified date.isValid is in the catalogue", qualified.ok());
		final Verdict withSignature = DifferenceCheck.check(2,
			one(2, "{\"nature\":\"USE_LIBRARY\",\"function\":\"Fasttrack.lib.phone.isValid(phone: string): boolean\",\"where\":\"/root/Contact/Contact/phone\",\"what\":\"p\"}"), context);
		check("a signature after the name is tolerated", withSignature.ok());
		final Verdict wrongField = DifferenceCheck.check(2,
			one(2, "{\"nature\":\"USE_LIBRARY\",\"function\":\"Fasttrack.lib.phone.isValid\",\"where\":\"the phone number of contacts\",\"what\":\"p\"}"), context);
		check("a library on a prose location is kept to place", wrongField.toPlace());
	}

	static void approvalsInPass2AreDropped(final PackCatalog pCatalogue)
	{
		final DifferenceCheck.CheckContext context = ProposalFixturesTest.context(pCatalogue, null).checkContext();
		final Verdict v = DifferenceCheck.check(2, one(2, "{\"nature\":\"USE_WORKFLOW\",\"what\":\"approval\",\"where\":\"/root/Company\",\"workflow\":\"creationWithReview\"}"), context);
		check("approval in pass 2 dropped", v.dropped() && v.reasonKey().equals("accel.pass.refusal.outOfPass"));
		check("REMOVE in pass 2 dropped", DifferenceCheck.check(2, one(2, "{\"nature\":\"REMOVE\",\"where\":\"/root/Contact\"}"), context).dropped());
		check("KEEP from the model dropped in pass 1", DifferenceCheck.check(1, one(1, "{\"nature\":\"KEEP\",\"where\":\"/root/Contact\"}"), context).dropped());
		check("a library in pass 1 dropped", DifferenceCheck.check(1, one(1, "{\"nature\":\"USE_LIBRARY\",\"function\":\"Fasttrack.lib.phone.isValid\",\"where\":\"/root/Contact\"}"), context).dropped());
	}

	static void removeMainTableRefused(final PackCatalog pCatalogue)
	{
		final DifferenceCheck.CheckContext context = ProposalFixturesTest.context(pCatalogue, null).checkContext();
		final Verdict main = DifferenceCheck.check(1, one(1, "{\"nature\":\"REMOVE\",\"what\":\"company\",\"where\":\"/root/Company\"}"), context);
		check("REMOVE of the main table refused", main.refused() && main.reasonKey().equals("accel.pass.refusal.removeMain"));
		final Verdict other = DifferenceCheck.check(1, one(1, "{\"nature\":\"REMOVE\",\"what\":\"contacts\",\"where\":\"/root/Contact\"}"), context);
		check("REMOVE of another table is fine", other.ok());
		final Verdict field = DifferenceCheck.check(1, one(1, "{\"nature\":\"REMOVE\",\"what\":\"size\",\"where\":\"/root/Company/Legal/sizeCategory\"}"), context);
		check("REMOVE of a pack field is fine", field.ok());
	}

	static void addOfAnExistingPathBecomesChange(final PackCatalog pCatalogue)
	{
		final DifferenceCheck.CheckContext context = ProposalFixturesTest.context(pCatalogue, null).checkContext();
		final Verdict v = DifferenceCheck.check(1, one(1, "{\"nature\":\"ADD\",\"what\":\"name\",\"where\":\"/root/Company/Identification/name\",\"detail\":{\"kind\":\"field\",\"maxLength\":200}}"), context);
		check("ADD of an existing field turned into CHANGE", v.ok() && v.difference().nature() == Nature.CHANGE);
		final Verdict table = DifferenceCheck.check(1, one(1, "{\"nature\":\"ADD\",\"what\":\"contact\",\"where\":\"/root/Contact\"}"), context);
		check("ADD of an existing table turned into CHANGE", table.difference().nature() == Nature.CHANGE);
	}

	static void newFieldUnderAnAddOfTheSameAnswerIsPlaced(final PackCatalog pCatalogue)
	{
		final DifferenceCheck.CheckContext context = ProposalFixturesTest.context(pCatalogue, null).checkContext();
		final String answer = "[{\"nature\":\"ADD\",\"what\":\"table\",\"where\":\"/root/ComplianceDocument\",\"detail\":{\"kind\":\"table\"}},"
			+ "{\"nature\":\"ADD\",\"what\":\"expiry\",\"where\":\"/root/ComplianceDocument/expiryDate\",\"detail\":{\"kind\":\"field\",\"type\":\"date\"}},"
			+ "{\"nature\":\"ADD\",\"what\":\"orphan\",\"where\":\"/root/Unknown/field\"},"
			+ "{\"nature\":\"ADD\",\"what\":\"purchasing\",\"where\":\"/root/Company/Purchasing/paymentTerms\"}]";
		final Batch batch = DifferenceCheck.checkAll(1, AdaptationLlmPass.parse(1, answer), context);
		check("four lines kept", batch.kept.size() == 4 && batch.refused.isEmpty());
		check("new table placed", batch.kept.get(0).ok());
		check("field of the new table placed", batch.kept.get(1).ok());
		check("field under an unknown table to place", batch.kept.get(2).toPlace());
		check("new group and field under a table of the pack placed", batch.kept.get(3).ok());
	}

	static void workflowAndBlockRules(final PackCatalog pCatalogue)
	{
		final DifferenceCheck.CheckContext context = ProposalFixturesTest.context(pCatalogue, null).checkContext();
		check("unknown workflow refused", DifferenceCheck.check(4, one(4, "{\"nature\":\"USE_WORKFLOW\",\"where\":\"/root/Company\",\"workflow\":\"megaApproval\"}"), context).refused());
		check("known workflow ok", DifferenceCheck.check(4, one(4, "{\"nature\":\"USE_WORKFLOW\",\"where\":\"/root/Company\",\"workflow\":\"creationWithReview\"}"), context).ok());
		check("unknown block refused", DifferenceCheck.check(1, one(1, "{\"nature\":\"USE_BLOCK\",\"where\":\"/root/Company\",\"block\":\"nope\"}"), context).refused());
		check("known block ok", DifferenceCheck.check(1, one(1, "{\"nature\":\"USE_BLOCK\",\"where\":\"/root/CompanyIdentifier\",\"block\":\"contacts\"}"), context).ok());
		check("known block on a table in prose to place", DifferenceCheck.check(1, one(1, "{\"nature\":\"USE_BLOCK\",\"where\":\"the supplier table\",\"block\":\"audit\"}"), context).toPlace());
	}

	static void pass4RolesAndPass3Layouts(final PackCatalog pCatalogue)
	{
		final DifferenceCheck.CheckContext context = ProposalFixturesTest.context(pCatalogue, null).checkContext();
		final Batch roles = DifferenceCheck.checkAll(4, AdaptationLlmPass.parse(4, ProposalFixturesTest.PASS4), context);
		check("pass 4 measured answer: five kept", roles.kept.size() == 5 && roles.refused.isEmpty() && roles.dropped == 0);
		check("a role has no path to resolve", roles.kept.get(3).ok());
		final String layouts = "[{\"table\":\"/root/Company\",\"layout\":{\"format\":\"x\"}},{\"table\":\"Supplier\",\"layout\":{\"format\":\"x\"}}]";
		final Batch forms = DifferenceCheck.checkAll(3, AdaptationLlmPass.parse(3, layouts), context);
		check("a layout of a table of the model is placed, of an unknown one to place", forms.kept.get(0).ok() && forms.kept.get(1).toPlace());
	}

	static void looksLikeExisting(final PackCatalog pCatalogue)
	{
		final Difference createdOn = one(1, "{\"nature\":\"ADD\",\"what\":\"creation date\",\"where\":\"/root/Company/createdOn\"}");
		final DifferenceCheck.Existing block = DifferenceCheck.looksLikeExisting(createdOn, pCatalogue);
		check("createdOn is the audit block", block != null && block.kind.equals("block") && block.key.equals("audit"));
		final Difference audit = one(1, "{\"nature\":\"ADD\",\"what\":\"audit trail columns\",\"where\":\"/root/Company/History/log\"}");
		check("a name that says audit", DifferenceCheck.looksLikeExisting(audit, pCatalogue) != null);
		final Difference iban = one(2, "{\"nature\":\"ADD\",\"what\":\"IBAN checksum rule\",\"where\":\"/root/BankAccount/iban\",\"detail\":{\"rule\":\"validate the IBAN checksum\"}}");
		final DifferenceCheck.Existing library = DifferenceCheck.looksLikeExisting(iban, pCatalogue);
		check("an IBAN rule looks like the iban library", library != null && library.kind.equals("library") && library.key.equals("Fasttrack.lib.validator.iban"));
		final Difference bic = one(2, "{\"nature\":\"ADD\",\"what\":\"BIC country must match IBAN country\",\"where\":\"/root/BankAccount/bic\"}");
		check("the measured BIC rule mentions iban: flagged for a look, never refused", DifferenceCheck.looksLikeExisting(bic, pCatalogue) != null);
		final Difference plain = one(1, "{\"nature\":\"ADD\",\"what\":\"purchasing category\",\"where\":\"/root/Company/Purchasing/category\"}");
		check("an ordinary ADD is not flagged", DifferenceCheck.looksLikeExisting(plain, pCatalogue) == null);
		check("only ADD is flagged", DifferenceCheck.looksLikeExisting(one(1, "{\"nature\":\"CHANGE\",\"where\":\"/root/Company/createdOn\"}"), pCatalogue) == null);
	}

	static void refusalTextForTheReask(final PackCatalog pCatalogue)
	{
		final DifferenceCheck.CheckContext context = ProposalFixturesTest.context(pCatalogue, null).checkContext();
		final Batch batch = DifferenceCheck.checkAll(1, AdaptationLlmPass.parse(1, ProposalFixturesTest.PASS1), context);
		final String text = batch.refusalText();
		check("the re-ask names the item and the reason", text.contains("Attachments with document type and expiry") && text.contains("block 'attachments' is not in the catalogue"));
		check("the re-ask prompt appends it", AdaptationLlmPass.reaskPrompt("P\n", text).contains("previous answer had items that were refused"));
		check("kind enum reachable", Kind.valueOf("TO_PLACE") == Kind.TO_PLACE);
	}
}
