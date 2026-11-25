package com.on.ebx.factory.quality;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Domain-heuristics
 * removal (2026-09-21, product owner review): every country/currency/legal-form/identifier-tag test that used
 * to live here moved to the language model - see {@code QualityLlmPassTest} for the equivalent coverage of
 * those families now that they are measured, not name-hinted. What remains here is exactly what {@code
 * Detectors} still decides on its own: {@code TRIM}/{@code COLLAPSE_SPACES}/{@code EMPTY_TO_NULL}, all
 * value-only, no column name or catalog tag involved.
 */
public final class DetectorsTest
{
	public static void main(final String[] pArgs)
	{
		DetectorsTest.noProposalsOnAnEmptyOrNonStringColumn();
		DetectorsTest.trimAndCollapseProposeNothingOnAlreadyCleanData();
		DetectorsTest.trimProposesOnRealDefects();
		DetectorsTest.emptyToNullProposesAndFlagsTheCaution();
		DetectorsTest.columnNameAndTagsNeverInfluenceWhatIsProposed();
		DetectorsTest.pipelineIsChainedNotIndependent();
		DetectorsTest.sampledEvidenceIsHonestAndConfidenceCapped();
		DetectorsTest.notSampledEvidenceCarriesNoCaveatAndFullConfidence();
		System.out.println("DetectorsTest OK");
	}

	private static void noProposalsOnAnEmptyOrNonStringColumn()
	{
		final Detectors.ColumnContext intColumn = new Detectors.ColumnContext("AMOUNT", "amount", "DECIMAL", Collections.<String>emptyList());
		final List<Detectors.StepProposal> proposals = Detectors.propose(Arrays.asList(new Detectors.ValueCount(" 1 ", 5)), intColumn);
		DetectorsTest.check("a non-STRING column gets no proposal at all", proposals.isEmpty());

		final Detectors.ColumnContext stringColumn = DetectorsTest.column("X", Collections.<String>emptyList());
		DetectorsTest.check("no values: no proposals", Detectors.propose(Collections.<Detectors.ValueCount>emptyList(), stringColumn).isEmpty());
	}

	/** SUPPLIER.NAME1/CITY, ACCOUNT.ACCOUNT_NAME: 0/55, 0/55, 0/52 whitespace defects measured live (docs/NORMALISATION.md sec 1.1) - a real negative finding, reproduced here as a fixture. */
	private static void trimAndCollapseProposeNothingOnAlreadyCleanData()
	{
		final List<Detectors.ValueCount> clean = Arrays.asList(new Detectors.ValueCount("ACME CORP", 20), new Detectors.ValueCount("BNP PARIBAS", 35));
		final List<Detectors.StepProposal> proposals = Detectors.propose(clean, DetectorsTest.column("NAME1", Collections.<String>emptyList()));
		DetectorsTest.check("clean data: no TRIM/COLLAPSE_SPACES proposal - a rule that fires for nothing is noise", DetectorsTest.kindsOf(proposals).isEmpty());
	}

	private static void trimProposesOnRealDefects()
	{
		final List<Detectors.ValueCount> withDefects = Arrays.asList(new Detectors.ValueCount("ACME CORP", 20), new Detectors.ValueCount("  BNP PARIBAS", 3),
			new Detectors.ValueCount("STE  GENERALE", 2));
		final List<Detectors.StepProposal> proposals = Detectors.propose(withDefects, DetectorsTest.column("NAME1", Collections.<String>emptyList()));
		final Map<String, Detectors.StepProposal> byKind = DetectorsTest.byKind(proposals);
		DetectorsTest.check("TRIM proposed, exactly the 3 defective rows counted", byKind.containsKey(CleaningSteps.TRIM) && byKind.get(CleaningSteps.TRIM).valuesChanged == 3);
		DetectorsTest.check("COLLAPSE_SPACES proposed, exactly the 2 defective rows counted",
			byKind.containsKey(CleaningSteps.COLLAPSE_SPACES) && byKind.get(CleaningSteps.COLLAPSE_SPACES).valuesChanged == 2);
		DetectorsTest.check("TRIM's own example is a shape, never the real value: " + byKind.get(CleaningSteps.TRIM).examples,
			!DetectorsTest.anyExampleContains(byKind.get(CleaningSteps.TRIM).examples, "BNP"));
	}

	/** SUPPLIER.LEGAL_FORM's own "NA" trap (docs/NORMALISATION.md sec 1.3): still proposed (2 real rows match), but the rationale must carry the caution, and confidence is capped, precisely so a reviewer catches it before accepting - unchanged by the domain-heuristics removal, since EMPTY_TO_NULL never looked at the column's name to begin with. */
	private static void emptyToNullProposesAndFlagsTheCaution()
	{
		final List<Detectors.ValueCount> values = Arrays.asList(new Detectors.ValueCount("SA", 23), new Detectors.ValueCount("NA", 2), new Detectors.ValueCount("PLC", 5));
		final List<Detectors.StepProposal> proposals = Detectors.propose(values, DetectorsTest.column("LEGAL_FORM", Collections.<String>emptyList()));
		final Detectors.StepProposal proposal = DetectorsTest.byKind(proposals).get(CleaningSteps.EMPTY_TO_NULL);
		DetectorsTest.check("EMPTY_TO_NULL proposed", proposal != null);
		DetectorsTest.check("exactly the 2 NA rows counted", proposal.valuesChanged == 2);
		DetectorsTest.check("confidence capped at 70 for this collision-prone family: " + proposal.confidence, proposal.confidence <= 70);
		DetectorsTest.check("rationale carries the caution: " + proposal.rationale, proposal.rationale.toLowerCase(java.util.Locale.ROOT).contains("review"));
		DetectorsTest.check("EMPTY_TO_NULL's own example is the real matched sentinel token (a small, fixed, published list) - not a shape",
			DetectorsTest.anyExampleContains(proposal.examples, "NA"));
	}

	/**
	 * The core regression for the 2026-09-21 removal: a column literally named/tagged like an identifier
	 * (SIREN, VAT, IBAN...) or a legal form must get EXACTLY the same proposals as an identically-shaped
	 * column with a meaningless name and no tags - {@code Detectors} must not special-case any of them any
	 * more (that judgement moved to the language model, {@code QualityLlmPass}).
	 */
	private static void columnNameAndTagsNeverInfluenceWhatIsProposed()
	{
		final List<Detectors.ValueCount> values = Arrays.asList(new Detectors.ValueCount("552120222", 1), new Detectors.ValueCount("CHE-106.831.974", 1));
		final Detectors.ColumnContext namedLikeAnIdentifier = new Detectors.ColumnContext("SIREN", "siren", "STRING", Arrays.asList("COMPANY_REGISTRATION"));
		final Detectors.ColumnContext meaninglessName = new Detectors.ColumnContext("X123", "x123", "STRING", Collections.<String>emptyList());
		final List<String> kindsA = DetectorsTest.kindsOf(Detectors.propose(values, namedLikeAnIdentifier));
		final List<String> kindsB = DetectorsTest.kindsOf(Detectors.propose(values, meaninglessName));
		DetectorsTest.check("identical proposals whatever the column is called or tagged: " + kindsA + " vs " + kindsB, kindsA.equals(kindsB));
		DetectorsTest.check("neither ever proposes NORMALISE_IDENTIFIER/COUNTRY_TO_ISO2/CURRENCY_TO_ISO/TRANSCODE_REFERENCE/CHECK_DIGIT-shaped kinds any more (Detectors only ever proposes TRIM/COLLAPSE_SPACES/EMPTY_TO_NULL)",
			DetectorsTest.onlyTrimFamilyKinds(kindsA) && DetectorsTest.onlyTrimFamilyKinds(kindsB));
	}

	private static boolean onlyTrimFamilyKinds(final List<String> pKinds)
	{
		for (final String kind : pKinds)
		{
			if (!CleaningSteps.TRIM.equals(kind) && !CleaningSteps.COLLAPSE_SPACES.equals(kind) && !CleaningSteps.EMPTY_TO_NULL.equals(kind))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The chaining regression kept from before the removal (task D, 2026-09-21 review-quality lot): a value
	 * matched by EMPTY_TO_NULL must not be examined a second time by a later family in the SAME pipeline. With
	 * only TRIM/COLLAPSE_SPACES/EMPTY_TO_NULL left, the only observable chaining left to check is TRIM ->
	 * COLLAPSE_SPACES -> EMPTY_TO_NULL itself: a value with both a leading space and an EMPTY_TO_NULL-sentinel
	 * shape, once trimmed, is still correctly recognised as the sentinel.
	 */
	private static void pipelineIsChainedNotIndependent()
	{
		final List<Detectors.ValueCount> values = Arrays.asList(new Detectors.ValueCount("France", 10), new Detectors.ValueCount("  N/A", 3));
		final List<Detectors.StepProposal> proposals = Detectors.propose(values, DetectorsTest.column("NOTES", Collections.<String>emptyList()));
		final Map<String, Detectors.StepProposal> byKind = DetectorsTest.byKind(proposals);
		DetectorsTest.check("TRIM fires on the leading-space row", byKind.containsKey(CleaningSteps.TRIM) && byKind.get(CleaningSteps.TRIM).valuesChanged == 3);
		DetectorsTest.check("EMPTY_TO_NULL recognises the sentinel only after TRIM ran on it first",
			byKind.containsKey(CleaningSteps.EMPTY_TO_NULL) && byKind.get(CleaningSteps.EMPTY_TO_NULL).valuesChanged == 3);
	}

	/**
	 * Coordinator review, 2026-09-21: "a fixture where the capped list and the exact aggregates disagree
	 * (sampled column), proving the gate/evidence follows the aggregates". Only 3 distinct values are given
	 * (as if a cap had truncated a much larger column), but {@code pExactDistinctCount} says the true total is
	 * 500 - {@code sampled = 500 > 3 = true}. Every proposal must then say so in its own rationale and have its
	 * confidence capped at {@link Detectors#cappedConfidence}'s own ceiling (80), never the mechanical 100/70 a
	 * caller that thought it had read the whole column would otherwise get.
	 */
	private static void sampledEvidenceIsHonestAndConfidenceCapped()
	{
		final List<Detectors.ValueCount> values = Arrays.asList(new Detectors.ValueCount("  Blue Widget", 3), new Detectors.ValueCount("Red Widget", 2),
			new Detectors.ValueCount("N/A", 1));
		final List<Detectors.StepProposal> proposals = Detectors.propose(values, DetectorsTest.column("PRODUCT_NAME", Collections.<String>emptyList()), 500L);
		final Map<String, Detectors.StepProposal> byKind = DetectorsTest.byKind(proposals);

		final Detectors.StepProposal trim = byKind.get(CleaningSteps.TRIM);
		DetectorsTest.check("TRIM proposed", trim != null);
		DetectorsTest.check("TRIM's rationale names the sample size (3) and the exact total (500): " + trim.rationale,
			trim.rationale.contains("3 most frequent distinct value(s) out of 500") && trim.rationale.contains("(sample)"));
		DetectorsTest.check("TRIM's confidence is capped at 80, not the mechanical 100: " + trim.confidence, trim.confidence == 80);

		final Detectors.StepProposal emptyToNull = byKind.get(CleaningSteps.EMPTY_TO_NULL);
		DetectorsTest.check("EMPTY_TO_NULL proposed", emptyToNull != null);
		DetectorsTest.check("EMPTY_TO_NULL's rationale also carries the sampling caveat: " + emptyToNull.rationale, emptyToNull.rationale.contains("(sample)"));
		DetectorsTest.check("EMPTY_TO_NULL's confidence stays at its own 70 (already below the 80 cap, unaffected): " + emptyToNull.confidence,
			emptyToNull.confidence == 70);
	}

	/** The 2-arg {@code propose} overload (or an explicit exact count equal to the sample size) never adds a sampling caveat and never caps confidence - the pre-existing, unchanged behaviour for a caller that genuinely read the whole column. */
	private static void notSampledEvidenceCarriesNoCaveatAndFullConfidence()
	{
		final List<Detectors.ValueCount> values = Arrays.asList(new Detectors.ValueCount("  Blue Widget", 3), new Detectors.ValueCount("Red Widget", 2));
		final Detectors.ColumnContext column = DetectorsTest.column("PRODUCT_NAME", Collections.<String>emptyList());

		final Detectors.StepProposal viaTwoArg = DetectorsTest.byKind(Detectors.propose(values, column)).get(CleaningSteps.TRIM);
		DetectorsTest.check("2-arg overload: no sampling caveat, full confidence 100: " + viaTwoArg.rationale + " / " + viaTwoArg.confidence,
			!viaTwoArg.rationale.contains("sample") && viaTwoArg.confidence == 100);

		final Detectors.StepProposal viaExactMatch = DetectorsTest.byKind(Detectors.propose(values, column, 2L)).get(CleaningSteps.TRIM);
		DetectorsTest.check("3-arg overload, exact count equal to the sample size (2): no sampling caveat, full confidence 100: " + viaExactMatch.rationale
			+ " / " + viaExactMatch.confidence, !viaExactMatch.rationale.contains("sample") && viaExactMatch.confidence == 100);
	}

	private static boolean anyExampleContains(final List<String> pExamples, final String pNeedle)
	{
		for (final String example : pExamples)
		{
			if (example.contains(pNeedle))
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static Detectors.ColumnContext column(final String pSourceColumnName, final List<String> pTags)
	{
		return new Detectors.ColumnContext(pSourceColumnName, DetectorsTest.camel(pSourceColumnName), "STRING", pTags);
	}

	private static String camel(final String pName)
	{
		return pName.toLowerCase(java.util.Locale.ROOT);
	}

	private static Map<String, Detectors.StepProposal> byKind(final List<Detectors.StepProposal> pProposals)
	{
		final Map<String, Detectors.StepProposal> out = new LinkedHashMap<String, Detectors.StepProposal>();
		for (final Detectors.StepProposal proposal : pProposals)
		{
			out.put(proposal.kind, proposal);
		}
		return out;
	}

	private static List<String> kindsOf(final List<Detectors.StepProposal> pProposals)
	{
		final List<String> out = new java.util.ArrayList<String>();
		for (final Detectors.StepProposal proposal : pProposals)
		{
			out.add(proposal.kind);
		}
		return out;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
