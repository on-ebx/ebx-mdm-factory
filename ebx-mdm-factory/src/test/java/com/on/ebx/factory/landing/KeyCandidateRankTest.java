package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JUnit-free: run the main, a failure throws. Landing test fixes (2026-10-17, defect 5). */
public final class KeyCandidateRankTest
{
	public static void main(final String[] pArgs)
	{
		final List<String> columns = Arrays.asList("NUM_TICKET", "MAGASIN", "DATE_ACHAT", "NOM", "MONTANT_TTC");
		final List<List<String>> rows = new ArrayList<List<String>>();
		for (int i = 0; i < 25; i++)
		{
			rows.add(Arrays.asList("T" + (57802999 + i * 7), "MRS-PRADO", "2026-08-" + String.format("%02d", i + 1) + "T10:00", "DUPONT", (10 + i) + "." + (i % 10) + "5"));
		}
		final KeyStudyReport report = KeyCandidateRankTest.report(columns, rows);
		// as a store file reports it: every column text, the date and the amount unique by chance
		KeyCandidateRankTest.check("identifier first, then neutral, dates and amounts last",
			KeyCandidateRank.candidates(report, Collections.<String> emptySet(), 4).equals(Arrays.asList("NUM_TICKET", "DATE_ACHAT", "MONTANT_TTC")));
		KeyCandidateRankTest.check("the date and the amount never preselected, the ticket is", KeyCandidateRank.preselected(report).equals(Arrays.asList("NUM_TICKET")));

		// a proposal on the date is dropped from the preselection
		report.proposal = new KeyStudyReport.Proposal(Arrays.asList("DATE_ACHAT"), 80, "x", KeyStudyReport.ORIGIN_LLM);
		KeyCandidateRankTest.check("a proposed date is not preselected", KeyCandidateRank.preselected(report).equals(Arrays.asList("NUM_TICKET")));
		report.proposal = new KeyStudyReport.Proposal(Arrays.asList("NUM_TICKET"), 80, "x", KeyStudyReport.ORIGIN_RULE);
		KeyCandidateRankTest.check("a proposed identifier is kept", KeyCandidateRank.preselected(report).equals(Arrays.asList("NUM_TICKET")));
		report.proposal = new KeyStudyReport.Proposal(Arrays.asList("MAGASIN", "NOM"), 80, "x", KeyStudyReport.ORIGIN_LLM);
		KeyCandidateRankTest.check("a composite proposal is kept", KeyCandidateRank.preselected(report).equals(Arrays.asList("MAGASIN", "NOM")));

		// only a date is unique: nothing is preselected
		final KeyStudyReport only = KeyCandidateRankTest.report(Arrays.asList("DATE_ACHAT", "NOM"), KeyCandidateRankTest.rows(Arrays.asList("2026-01-0", "DUPONT")));
		KeyCandidateRankTest.check("a lone unique date is listed but not preselected", KeyCandidateRank.candidates(only, Collections.<String> emptySet(), 4).equals(Arrays.asList("DATE_ACHAT"))
			&& KeyCandidateRank.preselected(only).isEmpty());

		KeyCandidateRankTest.check("names", KeyCandidateRank.rank("customerId", "VARCHAR", Arrays.asList("a1")) == KeyCandidateRank.IDENTIFIER
			&& KeyCandidateRank.rank("card_number", "VARCHAR", Arrays.asList("4970")) == KeyCandidateRank.IDENTIFIER
			&& KeyCandidateRank.rank("ref", "TEXT", Collections.<String> emptyList()) == KeyCandidateRank.IDENTIFIER
			&& KeyCandidateRank.rank("NOM", "VARCHAR", Arrays.asList("DUPONT")) == KeyCandidateRank.NEUTRAL
			&& KeyCandidateRank.rank("createdAt", "VARCHAR", Arrays.asList("x")) == KeyCandidateRank.NEVER);
		KeyCandidateRankTest.check("types and values", KeyCandidateRank.rank("code", "TIMESTAMP_NTZ", null) == KeyCandidateRank.NEVER && KeyCandidateRank.rank("code", "DECIMAL(10,2)", null) == KeyCandidateRank.NEVER
			&& KeyCandidateRank.rank("x_id", "NUMBER(38,0)", null) == KeyCandidateRank.IDENTIFIER
			&& KeyCandidateRank.rank("code", "VARCHAR", Arrays.asList("12.50", "3,20")) == KeyCandidateRank.NEVER
			&& KeyCandidateRank.rank("code", "VARCHAR", Arrays.asList("18/10/2026", "01/01/2025")) == KeyCandidateRank.NEVER
			&& KeyCandidateRank.rank("code", "VARCHAR", Arrays.asList("00123", "00124")) == KeyCandidateRank.IDENTIFIER);
		System.out.println("KeyCandidateRankTest OK");
	}

	private static List<List<String>> rows(final List<String> pFirst)
	{
		final List<List<String>> rows = new ArrayList<List<String>>();
		for (int i = 0; i < 5; i++)
		{
			final List<String> row = new ArrayList<String>();
			row.add(pFirst.get(0) + i);
			row.add(pFirst.get(1));
			rows.add(row);
		}
		return rows;
	}

	private static KeyStudyReport report(final List<String> pColumns, final List<List<String>> pRows)
	{
		final KeyStudyReport report = new KeyStudyReport();
		final List<KeyEvidence.Column> evidence = KeyEvidence.analyse(pColumns, pRows);
		for (final String name : pColumns)
		{
			report.columns.add(new KeyStudyReport.ColumnRow(name, "VARCHAR", false, KeyEvidence.find(evidence, name)));
		}
		return report;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
