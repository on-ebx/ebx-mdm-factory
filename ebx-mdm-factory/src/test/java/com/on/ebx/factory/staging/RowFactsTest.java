package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit-free, like the other tests of the module: run the main, a failure throws.
 * <p>
 * {@link RowFacts} on the landing tables of the B2B test of 2026-10-23 (project E2EB2B20261023081141, files of {@code tools/e2e/gen_b2b.py}): the SAP Business Partner
 * table (150 rows: key {@code BusinessPartner} {@code 0001690977}..., names cut at 40 characters, a tax sub-record holding a SIREN for French partners and a VAT number for
 * the others) and the Salesforce accounts (120 rows: {@code SAP_BP__c} = SAP numbers on 36 rows, VAT numbers with and without spaces). The rows are built here with the
 * generator's own shapes and the exact values quoted in the defect report, so the test needs no file.
 */
public final class RowFactsTest
{
	private static final List<String> SAP_FIELDS = Arrays.asList("BusinessPartner", "OrganizationBPName1", "OrganizationBPName2", "SearchTerm1",
		"to_BusinessPartnerAddress_results_0__Country", "to_BusinessPartnerTax_results_0__BusinessPartner", "to_BusinessPartnerTax_results_0__BPTaxType",
		"to_BusinessPartnerTax_results_0__BPTaxNumber");
	private static final List<String> SF_FIELDS = Arrays.asList("Id", "Name", "BillingStreet", "Phone", "VAT_Number__c", "SAP_BP__c");

	public static void main(final String[] pArgs)
	{
		RowFactsTest.aSubRecordRepeatingTheRowKeyIsAnIdentifier();
		RowFactsTest.aColumnOfAnotherSourcesKeysIsACrossReference();
		RowFactsTest.aNameCutAtFortyCharactersIsAContinuationPair();
		RowFactsTest.aTaxColumnHoldingSirensAndVatNumbersIsSplitByItsTaxType();
		RowFactsTest.vatNumbersWrittenWithSpacesAreSaidAgainstTheCompactSource();
		RowFactsTest.ordinaryColumnsSayNothing();
		RowFactsTest.policyWithholdsShapesAndCodes();
		RowFactsTest.identifierLikeValues();
		RowFactsTest.aSirenTypedWithSpacesIsOneShapeAndAUuidSaysNothing();
		System.out.println("RowFactsTest OK");
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	private static final String[][] FOREIGN = { { "DE0", "DE", "DE123456789" }, { "ES0", "ES", "ESB1234567X" }, { "IT0", "IT", "IT12345678901" },
		{ "BE0", "BE", "BE0801007390" } };

	/** The SAP table: 125 French partners (FR1 + a 9-digit SIREN), 25 foreign ones (10 DE, 8 ES, 4 IT, 3 BE: their VAT number); 20 long names cut at 40. */
	static Map<String, RowFacts.Table> tables()
	{
		final RowFacts.Accumulator sap = new RowFacts.Accumulator("SAP", "SAP_BusinessPartner", RowFactsTest.SAP_FIELDS, "BusinessPartner", false);
		final List<String> sapKeys = new ArrayList<String>();
		for (int i = 0; i < 150; i++)
		{
			final String bp = String.format("%010d", 1690977 + i * 37);
			sapKeys.add(bp);
			final String type;
			final String country;
			final String number;
			if (i < 125)
			{
				type = "FR1";
				country = "FR";
				number = String.valueOf(552100554 + i * 1009);
			}
			else
			{
				final int f = i < 135 ? 0 : i < 143 ? 1 : i < 147 ? 2 : 3;
				type = RowFactsTest.FOREIGN[f][0];
				country = RowFactsTest.FOREIGN[f][1];
				number = RowFactsTest.FOREIGN[f][2];
			}
			String name1 = "Valmor Logistique SAS " + i;
			String name2 = "";
			if (i % 7 == 0 && i < 140)
			{
				// 20 long names: SAP keeps 40 characters (39 when the cut falls on a space, which it drops) and the rest in Name2
				if (i % 2 == 0)
				{
					name1 = "Compagnie Générale de Chimie Druarimar E";
					name2 = "urope SAS";
				}
				else
				{
					name1 = "Compagnie Générale de Médical Morelidov";
					name2 = "Europe SASU";
				}
			}
			sap.row(new String[] { bp, name1, name2, "VALMOR", country, bp, type, number });
		}
		final RowFacts.Accumulator sf = new RowFacts.Accumulator("SALESFORCE", "SF_Accounts", RowFactsTest.SF_FIELDS, "Id", false);
		for (int i = 0; i < 120; i++)
		{
			final String id = "001rU19DnOxsyC" + String.format("%04d", i);
			final String vat = i < 50 ? "FR50676952278" : i < 88 ? "FR 747 741 994 42" : "";
			final String sapRef = i < 36 ? sapKeys.get(i * 4) : "";
			sf.row(new String[] { id, "Fiozel Distribution SNC", "92 pl. du Marché", "02 16 51 27 89", vat, sapRef });
		}
		final Map<String, RowFacts.Table> out = new LinkedHashMap<String, RowFacts.Table>();
		final RowFacts.Table sapTable = sap.finish();
		final RowFacts.Table sfTable = sf.finish();
		out.put(sapTable.key(), sapTable);
		out.put(sfTable.key(), sfTable);
		RowFacts.crossTable(out);
		return out;
	}

	// ------------------------------------------------------------------------------------------------------------ cases

	/** Defect 5: to_BusinessPartnerTax_results_0__BusinessPartner holds 0001690977, the row's own key - the preflight took it for a phone. */
	private static void aSubRecordRepeatingTheRowKeyIsAnIdentifier()
	{
		final RowFacts.Table sap = RowFactsTest.tables().get("SAP.SAP_BusinessPartner");
		final RowFacts.Column copy = sap.column("to_BusinessPartnerTax_results_0__BusinessPartner");
		check("150 of 150 rows repeat the key", copy.equalsOwnKey == 150L && copy.repeatsOwnKey() && copy.isIdentifierOfARow());
		final String text = RowFacts.columnText(sap, copy.field, true, false);
		check("the model is told it is an identifier, not a business value: " + text, text.startsWith(", row facts: equals the row's own key BusinessPartner on 150 of 150 filled rows")
			&& text.contains("never map it to a business attribute"));
		check("the key itself carries no row fact", RowFacts.columnText(sap, "BusinessPartner", true, false).isEmpty());
		check("and the profiler no longer counts it as a phone (00 then 0: no country code starts with 0)",
			!NormalisationProfiler.profile(NormalisationProfilerTest.counts("0001690977", 1, "0001691014", 1, "0001691051", 1), -1L).isPhoneLike());
	}

	/** Defect 3: Salesforce SAP_BP__c holds SAP BusinessPartner keys on 36 rows; the model mapped it to siret. */
	private static void aColumnOfAnotherSourcesKeysIsACrossReference()
	{
		final Map<String, RowFacts.Table> tables = RowFactsTest.tables();
		final RowFacts.Column ref = tables.get("SALESFORCE.SF_Accounts").column("SAP_BP__c");
		check("36 of 36 filled values are SAP keys", ref.filled == 36L && ref.keyRows == 36L && "SAP.SAP_BusinessPartner".equals(ref.keysOfTable) && ref.holdsKeysOfAnotherSource());
		final String text = RowFacts.columnText(tables.get("SALESFORCE.SF_Accounts"), "SAP_BP__c", true, false);
		check("the fact and its rule: " + text, text.contains("36 of 36 filled values are keys of SAP.SAP_BusinessPartner (its key BusinessPartner)")
			&& text.contains("a cross-reference to that source - keep it as its own attribute") && text.contains("never map it to a business attribute"));
		check("the SAP copy of its own key is not a cross-reference (same source)",
			tables.get("SAP.SAP_BusinessPartner").column("to_BusinessPartnerTax_results_0__BusinessPartner").keysOfTable == null);
	}

	/** Defect 4: OrganizationBPName1 is cut at 40 and OrganizationBPName2 holds the rest exactly on those 20 rows; the model mapped Name2 alone to name. */
	private static void aNameCutAtFortyCharactersIsAContinuationPair()
	{
		final RowFacts.Table sap = RowFactsTest.tables().get("SAP.SAP_BusinessPartner");
		final RowFacts.Column second = sap.column("OrganizationBPName2");
		check("Name2 continues Name1 at 40: " + second.continues + " " + second.cutWidth + " " + second.filled, "OrganizationBPName1".equals(second.continues) && second.cutWidth == 40
			&& second.filled == 20L && second.cutRows == 20L);
		final String text = RowFacts.columnText(sap, "OrganizationBPName2", true, false);
		check("the rule names the step that joins them: " + text, text.contains("filled on 20 rows, every one a row where OrganizationBPName1 reaches its maximum length 40 (or 39")
			&& text.contains("join the pair into ONE attribute with joinwidth(40,OrganizationBPName1,OrganizationBPName2), never map one half alone"));
		check("Name1 says it is continued", RowFacts.columnText(sap, "OrganizationBPName1", true, false).contains("continued by OrganizationBPName2"));
		check("no column of the table is taken for the continuation of a full column", sap.column("SearchTerm1").continues == null
			&& sap.column("to_BusinessPartnerTax_results_0__BPTaxNumber").continues == null);
	}

	/** Defect 2: the tax number holds a SIREN (FR1) or a VAT number (DE0, ES0, IT0, BE0); 25 staging rows got a VAT number in siren. */
	private static void aTaxColumnHoldingSirensAndVatNumbersIsSplitByItsTaxType()
	{
		final RowFacts.Table sap = RowFactsTest.tables().get("SAP.SAP_BusinessPartner");
		final RowFacts.Column tax = sap.column("to_BusinessPartnerTax_results_0__BPTaxNumber");
		check("125 digits only, 25 with letters", tax.digitsOnlyRows == 125L && tax.withLettersRows == 25L && tax.mixedIdentifier());
		check("the tax type explains it (the country does too; the nearer column wins): " + tax.companion, "to_BusinessPartnerTax_results_0__BPTaxType".equals(tax.companion)
			&& tax.shapesByCode.size() == 5);
		final String withValues = RowFacts.columnText(sap, tax.field, true, true);
		check("the fact with the shapes, the codes and the filtering rule: " + withValues,
			withValues.contains("holds several identifier shapes: digits only on 125 rows, with letters on 25 rows (9(9) 125, AA9(9) 10")
				&& withValues.contains("the shape follows to_BusinessPartnerTax_results_0__BPTaxType of the same row (FR1 -> 9(9) 125; DE0 -> AA9(9) 10")
				&& withValues.contains("must not be mapped whole to an attribute of one shape, and must not be left out either")
				&& withValues.contains("map it TWICE, one rule per kind, each with a filtering step: the digits-only values to the attribute of that shape with regex('^[0-9]{9}$')")
				&& withValues.contains("ONE regex that keeps every lettered shape listed, not only the most frequent one (e.g. regex('^\\p{L}{2}[\\p{L}0-9]+$'))")
				&& withValues.contains("a filter that empties exactly the other kind is accepted"));
		final String noValues = RowFacts.columnText(sap, tax.field, true, false);
		check("without real values the codes are counted, not named: " + noValues, noValues.contains("(5 codes, each holding one kind)") && !noValues.contains("FR1"));
		// live B2B run 2026-10-23: the tax type went to businessPartnerCategory and addressType
		final String type = RowFacts.columnText(sap, "to_BusinessPartnerTax_results_0__BPTaxType", true, true);
		check("the tax type column is told it qualifies the tax number: " + type,
			type.contains("its codes tell which kind of identifier to_BusinessPartnerTax_results_0__BPTaxNumber holds on the same row")
				&& type.contains("never map it to an unrelated attribute (a category, an address type, a status)"));
		check("a lettered shape not starting with two letters gives the looser filter",
			RowFacts.letteredFilter(java.util.Collections.singletonMap("9AA99", Long.valueOf(3L))).equals("regex('^[\\p{L}0-9]*\\p{L}[\\p{L}0-9]*$')"));
	}

	/** Defect 6: VAT_Number__c holds FR50676952278 and FR 747 741 994 42; the model proposed only upper(). */
	private static void vatNumbersWrittenWithSpacesAreSaidAgainstTheCompactSource()
	{
		final Map<String, RowFacts.Table> tables = RowFactsTest.tables();
		final RowFacts.Column vat = tables.get("SALESFORCE.SF_Accounts").column("VAT_Number__c");
		check("38 with spaces, 50 compact", vat.separated == 38L && vat.compact == 50L && vat.separatorsInIdentifiers());
		check("SAP writes the same compact shape (an Italian VAT number, AA9(11)) with none: " + vat.compactElsewhere,
			"SAP.SAP_BusinessPartner.to_BusinessPartnerTax_results_0__BPTaxNumber".equals(vat.compactElsewhere));
		final String text = RowFacts.columnText(tables.get("SALESFORCE.SF_Accounts"), "VAT_Number__c", true, false);
		check("the fact and its rule: " + text, text.contains("identifier-like values written with inner spaces or separators on 38 rows (AA 999 999 999 99 38), compact on 50 rows")
			&& text.contains("writes the same compact shape AA9(11) with no separator")
			&& text.contains("identifiers are compared without separators - remove them in the pipeline (replace(' ','') for spaces"));
	}

	private static void ordinaryColumnsSayNothing()
	{
		final Map<String, RowFacts.Table> tables = RowFactsTest.tables();
		for (final String field : Arrays.asList("Name", "BillingStreet", "Phone"))
		{
			check(field + " has no row fact", RowFacts.columnText(tables.get("SALESFORCE.SF_Accounts"), field, true, true).isEmpty());
		}
		check("SearchTerm1 has no row fact", RowFacts.columnText(tables.get("SAP.SAP_BusinessPartner"), "SearchTerm1", true, true).isEmpty());
	}

	private static void policyWithholdsShapesAndCodes()
	{
		final Map<String, RowFacts.Table> tables = RowFactsTest.tables();
		final String tax = RowFacts.columnText(tables.get("SAP.SAP_BusinessPartner"), "to_BusinessPartnerTax_results_0__BPTaxNumber", false, true);
		check("no shape and no code when shapes are withheld (a sensitive column): " + tax, !tax.contains("9(9)") && !tax.contains("FR1") && tax.contains("digits only on 125 rows"));
		final String vat = RowFacts.columnText(tables.get("SALESFORCE.SF_Accounts"), "VAT_Number__c", false, false);
		check("the separator fact keeps its counts only: " + vat, vat.contains("on 38 rows, compact on 50 rows") && !vat.contains("AA"));
	}

	private static void identifierLikeValues()
	{
		for (final String value : Arrays.asList("552100554", "BE0801007390", "FR 747 741 994 42", "ESB1234567X", "552 100 554"))
		{
			check(value + " is identifier-like", RowFacts.identifierLike(value));
		}
		for (final String value : Arrays.asList("0001690977", "02 16 51 27 89", "+33 6 12 34 56 78", "92 pl. du Marché", "Valmor", "180", "garlum-energie.fr"))
		{
			check(value + " is not identifier-like", !RowFacts.identifierLike(value));
		}
	}

	/** The website's siren: 552100554 and 552 100 554 are the same nine digits (one kind, written two ways); a column of UUIDs, hyphens everywhere, says nothing. */
	private static void aSirenTypedWithSpacesIsOneShapeAndAUuidSaysNothing()
	{
		final RowFacts.Accumulator web = new RowFacts.Accumulator("WEB", "WEB_AccountRequests", Arrays.asList("submissionId", "siren", "requestRef"), "submissionId", false);
		for (int i = 0; i < 40; i++)
		{
			web.row(new String[] { "id-" + i, i < 25 ? "552100554" : "552 100 554", "4b2c19a7-" + (1000 + i) + "-41d2-9e3a-7f20b51c" + (1000 + i) });
		}
		final Map<String, RowFacts.Table> tables = new LinkedHashMap<String, RowFacts.Table>();
		final RowFacts.Table table = web.finish();
		tables.put(table.key(), table);
		RowFacts.crossTable(tables);
		final RowFacts.Column siren = table.column("siren");
		check("digits only on every row, no shape mix", siren.digitsOnlyRows == 40L && siren.withLettersRows == 0L && !siren.mixedIdentifier());
		final String text = RowFacts.columnText(table, "siren", true, false);
		check("but written two ways: " + text, text.contains("written with inner spaces or separators on 15 rows (999 999 999 15), compact on 25 rows"));
		check("a UUID column written with hyphens everywhere says nothing", RowFacts.columnText(table, "requestRef", true, false).isEmpty());
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new IllegalStateException("RowFactsTest: " + pLabel);
		}
	}
}
