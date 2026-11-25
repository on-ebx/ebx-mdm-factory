package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. {@link
 * ValidValueDerivation} is the {@code VALID_VALUE} sibling of {@link CrossSystemFold} - lot 1, 2026-09-23,
 * quality-informed survivorship, `docs/MATCHING.md` &sect;23. Reuses {@link CrossSystemFold.SystemValues} as
 * input verbatim (same fixture shape {@code CrossSystemFoldTest} already builds), never a second input shape.
 * <p>
 * <b>NOT covered here, by design</b>: the idempotence/name-collision rules
 * ({@code ebx.QualitySuggester#writeDerivedValidValueAttribute}'s own Javadoc) live entirely on the EBX-bound
 * side, exactly like {@link CrossSystemFold}'s own equivalent - see {@code CrossSystemFoldTest}'s own class
 * Javadoc for why.
 */
public final class ValidValueDerivationTest
{
	public static void main(final String[] pArgs)
	{
		ValidValueDerivationTest.noRulesAtAll_noProposal();
		ValidValueDerivationTest.everySystem100PercentValid_noProposal();
		ValidValueDerivationTest.oneSystemBelow100Percent_proposesOne();
		ValidValueDerivationTest.singleSourceStillProposes();
		ValidValueDerivationTest.nonTextColumnNeverProposed();
		ValidValueDerivationTest.suggestedNameIsSourceAttributeNamePlusValid();
		ValidValueDerivationTest.rationaleNamesTheRuleKindAndThePerSystemNumbers();
		ValidValueDerivationTest.emptyByMappingList_noProposal();
		ValidValueDerivationTest.onlyBlankSampledValues_noProposal();
		System.out.println("ValidValueDerivationTest OK");
	}

	private static QualityRuleCheck.Rule frVatRule()
	{
		return new QualityRuleCheck.Rule("r1", QualityDetectors.PATTERN, "FR\\d{11}", QualityDetectors.WARN);
	}

	private static Detectors.ColumnContext column()
	{
		return new Detectors.ColumnContext("VAT_NO", "vatNo", "STRING", Collections.<String> emptyList());
	}

	private static void noRulesAtAll_noProposal()
	{
		final List<CrossSystemFold.SystemValues> byMapping = Collections
			.singletonList(new CrossSystemFold.SystemValues("ERP", Collections.singletonList(new Detectors.ValueCount("not-a-vat-number", 10)), false));
		final List<ValidValueDerivation.AttributeProposal> out = ValidValueDerivation.propose(byMapping, new ArrayList<QualityRuleCheck.Rule>(), null,
			ValidValueDerivationTest.column());
		ValidValueDerivationTest.check("no rules: nothing to test, no proposal", out.isEmpty());
	}

	private static void everySystem100PercentValid_noProposal()
	{
		final List<CrossSystemFold.SystemValues> byMapping = Arrays.asList(
			new CrossSystemFold.SystemValues("ERP", Collections.singletonList(new Detectors.ValueCount("FR12345678901", 50)), false),
			new CrossSystemFold.SystemValues("CRM", Collections.singletonList(new Detectors.ValueCount("FR98765432109", 40)), false));
		final List<ValidValueDerivation.AttributeProposal> out = ValidValueDerivation.propose(byMapping, Arrays.asList(ValidValueDerivationTest.frVatRule()), null,
			ValidValueDerivationTest.column());
		ValidValueDerivationTest.check("nothing to gain when every measured system is already 100% valid", out.isEmpty());
	}

	/** The real shape this class exists for: ERP mostly valid, CRM mostly not. */
	private static void oneSystemBelow100Percent_proposesOne()
	{
		final List<CrossSystemFold.SystemValues> byMapping = Arrays.asList(
			new CrossSystemFold.SystemValues("ERP", Collections.singletonList(new Detectors.ValueCount("FR12345678901", 48)), false),
			new CrossSystemFold.SystemValues("CRM", Arrays.asList(new Detectors.ValueCount("FR98765432109", 10), new Detectors.ValueCount("not-a-vat", 40)), false));
		final List<ValidValueDerivation.AttributeProposal> out = ValidValueDerivation.propose(byMapping, Arrays.asList(ValidValueDerivationTest.frVatRule()), null,
			ValidValueDerivationTest.column());
		ValidValueDerivationTest.check("one proposal", out.size() == 1);
		ValidValueDerivationTest.check("suggested name is vatNoValid", "vatNoValid".equals(out.get(0).suggestedName));
	}

	/** Unlike {@code CrossSystemFold}, a SINGLE source system whose own validity is below 100% still gets a proposal - no multi-source gate (see the class Javadoc). */
	private static void singleSourceStillProposes()
	{
		final List<CrossSystemFold.SystemValues> byMapping = Collections.singletonList(
			new CrossSystemFold.SystemValues("ERP", Arrays.asList(new Detectors.ValueCount("FR12345678901", 8), new Detectors.ValueCount("garbage", 2)), false));
		final List<ValidValueDerivation.AttributeProposal> out = ValidValueDerivation.propose(byMapping, Arrays.asList(ValidValueDerivationTest.frVatRule()), null,
			ValidValueDerivationTest.column());
		ValidValueDerivationTest.check("a single mapping is enough", out.size() == 1);
	}

	private static void nonTextColumnNeverProposed()
	{
		final Detectors.ColumnContext intColumn = new Detectors.ColumnContext("VAT_NO", "vatNo", "INTEGER", Collections.<String> emptyList());
		final List<CrossSystemFold.SystemValues> byMapping = Collections
			.singletonList(new CrossSystemFold.SystemValues("ERP", Collections.singletonList(new Detectors.ValueCount("garbage", 10)), false));
		final List<ValidValueDerivation.AttributeProposal> out = ValidValueDerivation.propose(byMapping, Arrays.asList(ValidValueDerivationTest.frVatRule()), null,
			intColumn);
		ValidValueDerivationTest.check("a validity rule is a shape/reference check over text - never proposed on a non-text column", out.isEmpty());
	}

	private static void suggestedNameIsSourceAttributeNamePlusValid()
	{
		final Detectors.ColumnContext designationColumn = new Detectors.ColumnContext("DESIGNATION", "designation", "STRING", Collections.<String> emptyList());
		final List<CrossSystemFold.SystemValues> byMapping = Collections.singletonList(new CrossSystemFold.SystemValues("ERP",
			Arrays.asList(new Detectors.ValueCount("VALID123", 5), new Detectors.ValueCount("bad", 5)), false));
		final QualityRuleCheck.Rule rule = new QualityRuleCheck.Rule("r2", QualityDetectors.PATTERN, "VALID.*", QualityDetectors.WARN);
		final List<ValidValueDerivation.AttributeProposal> out = ValidValueDerivation.propose(byMapping, Arrays.asList(rule), null, designationColumn);
		ValidValueDerivationTest.check("designationValid", "designationValid".equals(out.get(0).suggestedName));
	}

	private static void rationaleNamesTheRuleKindAndThePerSystemNumbers()
	{
		final List<CrossSystemFold.SystemValues> byMapping = Collections.singletonList(
			new CrossSystemFold.SystemValues("ERP", Arrays.asList(new Detectors.ValueCount("FR12345678901", 8), new Detectors.ValueCount("garbage", 2)), false));
		final List<ValidValueDerivation.AttributeProposal> out = ValidValueDerivation.propose(byMapping, Arrays.asList(ValidValueDerivationTest.frVatRule()), null,
			ValidValueDerivationTest.column());
		final String rationale = out.get(0).rationale;
		ValidValueDerivationTest.check("names the rule kind", rationale.contains(QualityDetectors.PATTERN));
		ValidValueDerivationTest.check("names the per-system numbers", rationale.contains("ERP: 8/10 valid"));
	}

	private static void emptyByMappingList_noProposal()
	{
		final List<ValidValueDerivation.AttributeProposal> out = ValidValueDerivation.propose(new ArrayList<CrossSystemFold.SystemValues>(),
			Arrays.asList(ValidValueDerivationTest.frVatRule()), null, ValidValueDerivationTest.column());
		ValidValueDerivationTest.check("empty input: no proposal, never throws", out.isEmpty());
		ValidValueDerivationTest.check("null input: no proposal, never throws",
			ValidValueDerivation.propose(null, Arrays.asList(ValidValueDerivationTest.frVatRule()), null, ValidValueDerivationTest.column()).isEmpty());
	}

	/** Every sampled value blank/null - nothing measured, nothing to propose (never a fabricated gain). */
	private static void onlyBlankSampledValues_noProposal()
	{
		final List<CrossSystemFold.SystemValues> byMapping = Collections
			.singletonList(new CrossSystemFold.SystemValues("ERP", Arrays.asList(new Detectors.ValueCount(null, 5), new Detectors.ValueCount("  ", 3)), false));
		final List<ValidValueDerivation.AttributeProposal> out = ValidValueDerivation.propose(byMapping, Arrays.asList(ValidValueDerivationTest.frVatRule()), null,
			ValidValueDerivationTest.column());
		ValidValueDerivationTest.check("no proposal", out.isEmpty());
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
