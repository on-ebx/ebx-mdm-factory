package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The model answers measured on 2026-10-12 (docs/ACCELERATORS.md section 16, Company pack core + the supplier specification of the
 * mockup), reproduced as fixtures. Not a test itself: shared by {@code AdaptationLlmPassTest} and {@code DifferenceCheckTest}; its main checks that the answers still parse.
 */
public final class ProposalFixturesTest
{
	public static void main(final String[] pArgs)
	{
		if (AdaptationLlmPass.parse(1, PASS1).size() != 2 || AdaptationLlmPass.parse(2, PASS2).size() != 7 || AdaptationLlmPass.parse(4, PASS4).size() != 5)
		{
			throw new AssertionError("the measured answers no longer parse");
		}
		System.out.println("ProposalFixturesTest OK");
	}

	static final String SPECIFICATION = "We buy from suppliers. Each supplier has payment terms, an incoterm and a purchasing category. "
		+ "Bank accounts (IBAN and BIC) must be checked and every change of bank account must be re-approved. "
		+ "Compliance documents expire and are reviewed 30 days before. SIREN is mandatory for French suppliers.";

	/** Pass 1 as measured: two items only, a SupplierProfile table with `where` written as prose, and an attachments block adapted. Wrapped in a code fence with prose. */
	static final String PASS1 = "Here are the differences:\n```json\n[\n"
		+ "{\"nature\":\"ADD\",\"what\":\"SupplierProfile table\",\"where\":\"a new table SupplierProfile linked to Company\","
		+ "\"detail\":{\"kind\":\"table\",\"fields\":\"paymentTerms, incoterm, purchasingCategory\"},\"why\":\"purchasing data of a supplier\",\"pack\":\"company\"},\n"
		+ "{\"nature\":\"USE_BLOCK\",\"what\":\"Attachments with document type and expiry\",\"where\":\"/root/Company\",\"block\":\"attachments\","
		+ "\"detail\":\"{\\\"addFields\\\":[\\\"documentType\\\",\\\"expiryDate\\\"]}\",\"why\":\"compliance documents expire\",\"pack\":\"company\"}\n"
		+ "]\n```\nLet me know if you need more.";

	/** Pass 2 as measured: SIREN wrapped luhn, IBAN, a BIC draft rule, date.isValid on typed dates (useless, bare name), and two approval items that belong to pass 4. */
	static final String PASS2 = "["
		+ "{\"nature\":\"USE_LIBRARY\",\"what\":\"SIREN check digit\",\"where\":\"/root/Company/Legal/registrationNumber\","
		+ "\"function\":\"Fasttrack.lib.validator.checkdigit.luhn.isValid\",\"detail\":{\"wrap\":\"exactly 9 digits\"},\"why\":\"SIREN is a Luhn number\"},"
		+ "{\"nature\":\"USE_LIBRARY\",\"what\":\"IBAN check\",\"where\":\"/root/BankAccount/iban\",\"function\":\"Fasttrack.lib.validator.iban.isValid\","
		+ "\"detail\":{},\"why\":\"IBAN has a checksum\"},"
		+ "{\"nature\":\"ADD\",\"what\":\"BIC country must match IBAN country\",\"where\":\"/root/BankAccount/bic\","
		+ "\"detail\":{\"rule\":\"the first two letters after the bank code of the BIC equal the IBAN country\"},\"why\":\"no library covers it\"},"
		+ "{\"nature\":\"USE_LIBRARY\",\"what\":\"Incorporation date format\",\"where\":\"/root/Company/Legal/incorporationDate\",\"function\":\"date.isValid\","
		+ "\"detail\":{},\"why\":\"dates must be valid\"},"
		+ "{\"nature\":\"USE_LIBRARY\",\"what\":\"Phone check\",\"where\":\"/root/Contact/Contact/phone\",\"function\":\"Fasttrack.lib.phone.isValid\","
		+ "\"detail\":{},\"why\":\"contacts have phones\"},"
		+ "{\"nature\":\"USE_WORKFLOW\",\"what\":\"Approval of a new bank account\",\"where\":\"/root/BankAccount\",\"workflow\":\"changeRequest\","
		+ "\"detail\":{},\"why\":\"bank changes are approved\"},"
		+ "{\"nature\":\"USE_WORKFLOW\",\"what\":\"Approval by finance\",\"where\":\"/root/Company\",\"workflow\":\"creationWithReview\","
		+ "\"detail\":{},\"why\":\"finance reviews\"}"
		+ "]";

	/** Pass 4 as measured (the doc calls it pass 3 of its first run): three workflow types and two roles. */
	static final String PASS4 = "{\"differences\":["
		+ "{\"nature\":\"USE_WORKFLOW\",\"what\":\"Supplier creation with review\",\"where\":\"/root/Company\",\"workflow\":\"creationWithReview\","
		+ "\"detail\":{\"reviewers\":[\"Purchasing\",\"Finance\"],\"mode\":\"sequential\",\"acceptLabel\":\"Approve supplier\",\"rejectLabel\":\"Send back\"},\"why\":\"two reviewers in turn\"},"
		+ "{\"nature\":\"USE_WORKFLOW\",\"what\":\"Bank change re-approval\",\"where\":\"/root/Company\",\"workflow\":\"changeRequest\","
		+ "\"detail\":{\"sensitiveFields\":[\"IBAN\",\"BIC\"],\"reviewers\":[\"Finance\"]},\"why\":\"every bank change is re-approved\"},"
		+ "{\"nature\":\"USE_WORKFLOW\",\"what\":\"Compliance document review\",\"where\":\"/root/Company\",\"workflow\":\"periodicReview\","
		+ "\"detail\":{\"dueField\":\"expiryDate\",\"daysBefore\":30,\"reviewers\":[\"Purchasing\"]},\"why\":\"documents expire\"},"
		+ "{\"nature\":\"ADD\",\"what\":\"role Purchasing\",\"where\":\"Purchasing\",\"detail\":{\"role\":\"Purchasing\",\"does\":\"first review\"},\"why\":\"needed by the workflow\"},"
		+ "{\"nature\":\"ADD\",\"what\":\"role Finance\",\"where\":\"Finance\",\"detail\":{\"role\":\"Finance\",\"does\":\"second review\"},\"why\":\"needed by the workflow\"}"
		+ "]}";

	/** The differences pass 1 left standing in the pass 2 fixtures: a bank account table with iban and bic. */
	static List<Difference> decidedPass1()
	{
		final com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
		final List<Difference> out = new ArrayList<Difference>();
		out.add(new Difference(1, Difference.Nature.ADD, "BankAccount", "/root/BankAccount", Difference.PlaceState.PLACED, m.createObjectNode().put("kind", "table"), "", "company", "", "", ""));
		out.add(new Difference(1, Difference.Nature.ADD, "iban", "/root/BankAccount/iban", Difference.PlaceState.PLACED, m.createObjectNode().put("kind", "field").put("type", "string"), "", "company", "", "", ""));
		out.add(new Difference(1, Difference.Nature.ADD, "bic", "/root/BankAccount/bic", Difference.PlaceState.PLACED, m.createObjectNode().put("kind", "field").put("type", "string"), "", "company", "", "", ""));
		return out;
	}

	static AdaptationContext context(final PackCatalog pCatalogue, final List<Difference> pDecided)
	{
		return new AdaptationContext(pCatalogue, Arrays.asList(pCatalogue.pack("company")), Arrays.asList("bank-accounts"), SPECIFICATION, pDecided);
	}

	private ProposalFixturesTest()
	{
	}
}
