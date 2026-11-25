package com.on.ebx.factory.landing;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** JUnit-free: run the main, a failure throws. */
public final class KeyEvidenceTest
{
	public static void main(final String[] pArgs)
	{
		final List<String> columns = Arrays.asList("BusinessPartner", "FirstName", "LastName", "Sex", "Notes");
		final List<List<String>> rows = Arrays.asList(
			Arrays.asList("1000123", "Jean", "Martin", "M", ""),
			Arrays.asList("1000124", "Marie", "Martin", "F", "a very long remark that goes on and on and on past forty characters"),
			Arrays.asList("1000125", "Jean", "Dupont", "", ""),
			Arrays.asList("1000126", " Paul ", "Durand", "M"));

		final List<KeyEvidence.Column> evidence = KeyEvidence.analyse(columns, rows);
		KeyEvidenceTest.check("one column of evidence per sample column", evidence.size() == 5);
		final KeyEvidence.Column partner = KeyEvidence.find(evidence, "BusinessPartner");
		KeyEvidenceTest.check("the identifier is unique in the sample", partner.uniqueInSample() && partner.distinct == 4 && partner.empty == 0 && partner.rows == 4);
		KeyEvidenceTest.check("its rates feed SourceKeyRule as a clean profile", BigDecimal.ZERO.compareTo(partner.nullRate()) == 0 && BigDecimal.ONE.compareTo(partner.uniqueRate()) == 0);
		KeyEvidenceTest.check("only three values are shown", partner.values.equals(Arrays.asList("1000123", "1000124", "1000125")));

		final KeyEvidence.Column firstName = KeyEvidence.find(evidence, "firstname");
		KeyEvidenceTest.check("lookup is case-insensitive as a fallback", firstName != null && firstName.name.equals("FirstName"));
		KeyEvidenceTest.check("duplicates and padding are counted on trimmed values", firstName.distinct == 3 && !firstName.uniqueInSample());
		KeyEvidenceTest.check("the shown values are trimmed", firstName.values.contains("Paul"));

		final KeyEvidence.Column sex = KeyEvidence.find(evidence, "Sex");
		KeyEvidenceTest.check("an empty cell counts as empty", sex.empty == 1 && sex.distinct == 2);
		KeyEvidenceTest.check("its null rate is measured", new BigDecimal("0.2500").compareTo(sex.nullRate()) == 0);

		final KeyEvidence.Column notes = KeyEvidence.find(evidence, "Notes");
		KeyEvidenceTest.check("a short row counts as empty past its end", notes.empty == 3 && notes.distinct == 1);
		KeyEvidenceTest.check("a long value is cut with an ellipsis", notes.values.get(0).length() == KeyEvidence.VALUE_MAX_LENGTH && notes.values.get(0).endsWith("…"));
		KeyEvidenceTest.check("unknown column: null, not an exception", KeyEvidence.find(evidence, "Missing") == null);

		// composite uniqueness on the very same rows
		KeyEvidenceTest.check("first + last name identify every sample row", Boolean.TRUE.equals(KeyEvidence.uniqueTogether(columns, rows, Arrays.asList("FirstName", "LastName"))));
		KeyEvidenceTest.check("last name alone does not", Boolean.FALSE.equals(KeyEvidence.uniqueTogether(columns, rows, Arrays.asList("LastName"))));
		KeyEvidenceTest.check("a component empty on one row refutes the key", Boolean.FALSE.equals(KeyEvidence.uniqueTogether(columns, rows, Arrays.asList("BusinessPartner", "Sex"))));
		KeyEvidenceTest.check("a key column the sample lacks cannot be checked", KeyEvidence.uniqueTogether(columns, rows, Arrays.asList("Id")) == null);
		KeyEvidenceTest.check("no sample row: cannot be checked", KeyEvidence.uniqueTogether(columns, Arrays.<List<String>> asList(), Arrays.asList("BusinessPartner")) == null);
		KeyEvidenceTest.check("the check is case-insensitive on names", Boolean.TRUE.equals(KeyEvidence.uniqueTogether(columns, rows, Arrays.asList("businesspartner"))));

		// the rule, profiled by the sample: the identifier of the table is confirmed with the sample's own rates
		final List<SourceKeyRule.Candidate> candidates = KeyEvidence.candidates(Arrays.asList("BusinessPartner", "FirstName", "Region"), evidence);
		KeyEvidenceTest.check("a table column the sample lacks keeps null rates", candidates.get(2).nullRate == null && candidates.get(2).uniqueRate == null);
		// the real Personnes_SAP_Sample case: BusinessPartner is not named like the identifier of the table, so the
		// rule proposes nothing however clean the sample - which is exactly why the language model is asked
		final SourceKeyRule.Proposal none = SourceKeyRule.propose("Personnes_SAP_Sample", candidates);
		KeyEvidenceTest.check("the naming rule proposes nothing for a column not named like the identifier", none.columns.isEmpty());
		final List<String> partnerColumns = Arrays.asList("PartnerId", "Name");
		final List<List<String>> partnerRows = Arrays.asList(Arrays.asList("P1", "Acme"), Arrays.asList("P2", "Acme"));
		final SourceKeyRule.Proposal proposal = SourceKeyRule.propose("PARTNER", KeyEvidence.candidates(partnerColumns, KeyEvidence.analyse(partnerColumns, partnerRows)));
		KeyEvidenceTest.check("the rule confirms an identifier-named column from the sample profile", proposal.columns.equals(Arrays.asList("PartnerId")) && proposal.confidence == 95);

		// an empty sample: nothing measured, nothing refuted
		final List<KeyEvidence.Column> unmeasured = KeyEvidence.analyse(columns, Arrays.<List<String>> asList());
		KeyEvidenceTest.check("an empty sample gives null rates", unmeasured.get(0).nullRate() == null && unmeasured.get(0).uniqueRate() == null && !unmeasured.get(0).uniqueInSample());

		// composite-key lot (2026-09-24): KeyEvidence.combination, on the very same rows
		final KeyEvidence.Combination firstLast = KeyEvidence.combination(columns, rows, Arrays.asList("FirstName", "LastName"));
		KeyEvidenceTest.check("first+last: unique together, no empty rows", firstLast.unique() && firstLast.rowsInSample == 4 && firstLast.distinctTogether == 4
			&& firstLast.rowsAllEmpty == 0 && firstLast.rowsPartlyEmpty == 0 && firstLast.rowsUniqueComplete == 4);

		final KeyEvidence.Combination partnerSex = KeyEvidence.combination(columns, rows, Arrays.asList("BusinessPartner", "Sex"));
		KeyEvidenceTest.check("BusinessPartner+Sex: one row has Sex empty - partly empty, not unique, the other 3 rows all count",
			!partnerSex.unique() && partnerSex.rowsPartlyEmpty == 1 && partnerSex.rowsAllEmpty == 0 && partnerSex.rowsUniqueComplete == 3);

		final KeyEvidence.Combination lastAlone = KeyEvidence.combination(columns, rows, Arrays.asList("LastName"));
		KeyEvidenceTest.check("last name alone: not unique (Martin repeats twice, excluded both times; Dupont+Durand count)",
			!lastAlone.unique() && lastAlone.distinctTogether < lastAlone.rowsInSample && lastAlone.rowsUniqueComplete == 2);

		final KeyEvidence.Combination missing = KeyEvidence.combination(columns, rows, Arrays.asList("Id"));
		KeyEvidenceTest.check("a column the sample lacks is treated as always empty, not an exception",
			!missing.unique() && missing.rowsAllEmpty == 4 && missing.rowsPartlyEmpty == 0 && missing.rowsUniqueComplete == 0);

		final KeyEvidence.Combination noColumns = KeyEvidence.combination(columns, rows, Arrays.<String> asList());
		KeyEvidenceTest.check("an empty column list: every row's combination is empty",
			noColumns.rowsAllEmpty == 4 && noColumns.rowsInSample == 4 && noColumns.rowsUniqueComplete == 0);

		// a set that is all-empty on SOME rows and filled on others (a mix of both symptoms)
		final List<String> mixColumns = Arrays.asList("K1", "K2");
		final List<List<String>> mixRows = Arrays.asList(Arrays.asList("a", "b"), Arrays.asList("", ""), Arrays.asList("c", ""), Arrays.asList("a", "b"));
		final KeyEvidence.Combination mix = KeyEvidence.combination(mixColumns, mixRows, Arrays.asList("K1", "K2"));
		KeyEvidenceTest.check("mixed sample: 1 fully empty, 1 partly empty, a duplicate COMPLETE combination (rows 0 and 3), none unique",
			!mix.unique() && mix.rowsAllEmpty == 1 && mix.rowsPartlyEmpty == 1 && mix.rowsInSample == 4 && mix.distinctTogether == 3 && mix.rowsUniqueComplete == 0);

		// coordinator follow-up round 3 (2026-09-24): the exact case that broke WizardPage's earlier
		// "distinctTogether minus one all-empty bucket minus rowsPartlyEmpty" approximation - SEVERAL
		// DIFFERENT partly-empty combinations (so distinctTogether keeps counting up) alongside a duplicate
		// among the COMPLETE rows (which distinctTogether, a plain Set, only ever counts once no matter how
		// many rows share it). Old approximation: distinctTogether(4) - allEmptyBucket(0) - partlyEmpty(2) = 2.
		// Correct answer: 1 (only row4's "c|5" - row0/row1 share "a|1" and are BOTH excluded, not credited once).
		final List<String> severalColumns = Arrays.asList("K1", "K2");
		final List<List<String>> severalRows = Arrays.asList(Arrays.asList("a", "1"), // complete, duplicate of the next row
			Arrays.asList("a", "1"), // complete, duplicate of the previous row
			Arrays.asList("", "3"), // partly empty
			Arrays.asList("", "4"), // partly empty, a DIFFERENT combination from the row above
			Arrays.asList("c", "5")); // complete, unique
		final KeyEvidence.Combination several = KeyEvidence.combination(severalColumns, severalRows, Arrays.asList("K1", "K2"));
		KeyEvidenceTest.check("several different partly-empty combinations + a duplicate complete pair: distinctTogether is 4, but only row4 is truly unique-and-complete",
			several.distinctTogether == 4 && several.rowsPartlyEmpty == 2 && several.rowsAllEmpty == 0 && several.rowsUniqueComplete == 1 && !several.unique());

		// sampleJson: a compact JSON array of rows, string values only, short rows simply missing trailing keys
		final String json = KeyEvidence.sampleJson(Arrays.asList("BusinessPartner", "FirstName"), rows);
		KeyEvidenceTest.check("sampleJson: one object per row, values only for the requested columns: " + json,
			json.equals("[{\"BusinessPartner\":\"1000123\",\"FirstName\":\"Jean\"},{\"BusinessPartner\":\"1000124\",\"FirstName\":\"Marie\"},"
				+ "{\"BusinessPartner\":\"1000125\",\"FirstName\":\"Jean\"},{\"BusinessPartner\":\"1000126\",\"FirstName\":\" Paul \"}]"));
		final String fullJson = KeyEvidence.sampleJson(columns, rows);
		KeyEvidenceTest.check("sampleJson: the short row (no 5th, Notes, value) simply omits the trailing key, not a null: " + fullJson,
			fullJson.contains("{\"BusinessPartner\":\"1000126\",\"FirstName\":\" Paul \",\"LastName\":\"Durand\",\"Sex\":\"M\"}"));

		System.out.println("KeyEvidenceTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
