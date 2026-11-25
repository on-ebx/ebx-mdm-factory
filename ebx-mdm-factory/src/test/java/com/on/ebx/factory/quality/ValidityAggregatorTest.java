package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. {@link
 * ValidityAggregator} is the pure scoring shared by {@code ebx.CrossSystemMeasures#perSystemValidityCounts}
 * (SQL-grouped rows off the live master table) and {@link ValidValueDerivation} (sampled landing-data rows) -
 * lot 1, 2026-09-23, quality-informed survivorship, `docs/MATCHING.md` &sect;23.
 */
public final class ValidityAggregatorTest
{
	public static void main(final String[] pArgs)
	{
		ValidityAggregatorTest.everyValuePassing_checkedEqualsValid();
		ValidityAggregatorTest.mixedPassAndFail_weightedByCount();
		ValidityAggregatorTest.everyRuleMustPass();
		ValidityAggregatorTest.blankOrNullValuesNeverCounted();
		ValidityAggregatorTest.nonPositiveCountNeverCounted();
		ValidityAggregatorTest.noRulesYieldsEmptyMap();
		ValidityAggregatorTest.nullValuesListYieldsEmptyMap();
		ValidityAggregatorTest.severalSystemsScoredIndependently();
		ValidityAggregatorTest.insertionOrderOfFirstAppearancePreserved();
		System.out.println("ValidityAggregatorTest OK");
	}

	private static QualityRuleCheck.Rule frVatRule()
	{
		return new QualityRuleCheck.Rule("r1", QualityDetectors.PATTERN, "FR\\d{11}", QualityDetectors.WARN);
	}

	private static void everyValuePassing_checkedEqualsValid()
	{
		final List<ValidityAggregator.DistinctValue> values = Arrays.asList(new ValidityAggregator.DistinctValue("ERP", "FR12345678901", 10),
			new ValidityAggregator.DistinctValue("ERP", "FR98765432109", 5));
		final Map<String, ValidityAggregator.SystemValidity> out = ValidityAggregator.aggregate(values, Arrays.asList(ValidityAggregatorTest.frVatRule()), null);
		final ValidityAggregator.SystemValidity erp = out.get("ERP");
		ValidityAggregatorTest.check("checked = 15", erp.checked == 15);
		ValidityAggregatorTest.check("valid = 15 (every value passes)", erp.valid == 15);
	}

	private static void mixedPassAndFail_weightedByCount()
	{
		final List<ValidityAggregator.DistinctValue> values = Arrays.asList(new ValidityAggregator.DistinctValue("ERP", "FR12345678901", 48),
			new ValidityAggregator.DistinctValue("ERP", "not-a-vat-number", 4));
		final Map<String, ValidityAggregator.SystemValidity> out = ValidityAggregator.aggregate(values, Arrays.asList(ValidityAggregatorTest.frVatRule()), null);
		final ValidityAggregator.SystemValidity erp = out.get("ERP");
		ValidityAggregatorTest.check("checked = 52", erp.checked == 52);
		ValidityAggregatorTest.check("valid = 48 (only the passing distinct value's own count)", erp.valid == 48);
	}

	/** A value must pass EVERY rule of the list, not merely one of them. */
	private static void everyRuleMustPass()
	{
		final QualityRuleCheck.Rule startsWithZ = new QualityRuleCheck.Rule("r2", QualityDetectors.PATTERN, "Z.*", QualityDetectors.WARN);
		final List<ValidityAggregator.DistinctValue> values = Arrays
			.asList(new ValidityAggregator.DistinctValue("ERP", "FR12345678901", 1), new ValidityAggregator.DistinctValue("ERP", "ZR12345678901", 1));
		final Map<String, ValidityAggregator.SystemValidity> out = ValidityAggregator.aggregate(values,
			Arrays.asList(ValidityAggregatorTest.frVatRule(), startsWithZ), null);
		final ValidityAggregator.SystemValidity erp = out.get("ERP");
		ValidityAggregatorTest.check("both checked", erp.checked == 2);
		ValidityAggregatorTest.check("neither passes both rules at once: valid = 0", erp.valid == 0);
	}

	private static void blankOrNullValuesNeverCounted()
	{
		final List<ValidityAggregator.DistinctValue> values = Arrays.asList(new ValidityAggregator.DistinctValue("ERP", null, 5),
			new ValidityAggregator.DistinctValue("ERP", "   ", 3), new ValidityAggregator.DistinctValue("ERP", "FR12345678901", 2));
		final Map<String, ValidityAggregator.SystemValidity> out = ValidityAggregator.aggregate(values, Arrays.asList(ValidityAggregatorTest.frVatRule()), null);
		final ValidityAggregator.SystemValidity erp = out.get("ERP");
		ValidityAggregatorTest.check("only the real value counted: checked = 2", erp.checked == 2);
		ValidityAggregatorTest.check("valid = 2", erp.valid == 2);
	}

	private static void nonPositiveCountNeverCounted()
	{
		final List<ValidityAggregator.DistinctValue> values = Arrays.asList(new ValidityAggregator.DistinctValue("ERP", "FR12345678901", 0),
			new ValidityAggregator.DistinctValue("ERP", "FR98765432109", -3));
		final Map<String, ValidityAggregator.SystemValidity> out = ValidityAggregator.aggregate(values, Arrays.asList(ValidityAggregatorTest.frVatRule()), null);
		ValidityAggregatorTest.check("no system contributed anything real: empty map", out.isEmpty());
	}

	/** No rules at all: nothing to score - an empty map, never "everything passes". */
	private static void noRulesYieldsEmptyMap()
	{
		final List<ValidityAggregator.DistinctValue> values = Arrays.asList(new ValidityAggregator.DistinctValue("ERP", "FR12345678901", 10));
		ValidityAggregatorTest.check("empty rule list: empty map", ValidityAggregator.aggregate(values, new ArrayList<QualityRuleCheck.Rule>(), null).isEmpty());
		ValidityAggregatorTest.check("null rule list: empty map, never throws", ValidityAggregator.aggregate(values, null, null).isEmpty());
	}

	private static void nullValuesListYieldsEmptyMap()
	{
		ValidityAggregatorTest.check("null values list: empty map, never throws",
			ValidityAggregator.aggregate(null, Arrays.asList(ValidityAggregatorTest.frVatRule()), null).isEmpty());
	}

	/** ERP and CRM are scored entirely independently - a poor CRM rate never drags ERP's own numbers down. */
	private static void severalSystemsScoredIndependently()
	{
		final List<ValidityAggregator.DistinctValue> values = Arrays.asList(new ValidityAggregator.DistinctValue("ERP", "FR12345678901", 48),
			new ValidityAggregator.DistinctValue("ERP", "bad-value", 4), new ValidityAggregator.DistinctValue("CRM", "bad-value-crm", 10));
		final Map<String, ValidityAggregator.SystemValidity> out = ValidityAggregator.aggregate(values, Arrays.asList(ValidityAggregatorTest.frVatRule()), null);
		final ValidityAggregator.SystemValidity erp = out.get("ERP");
		final ValidityAggregator.SystemValidity crm = out.get("CRM");
		ValidityAggregatorTest.check("ERP: 48/52 valid", erp.checked == 52 && erp.valid == 48);
		ValidityAggregatorTest.check("CRM: 0/10 valid", crm.checked == 10 && crm.valid == 0);
	}

	private static void insertionOrderOfFirstAppearancePreserved()
	{
		final List<ValidityAggregator.DistinctValue> values = Arrays.asList(new ValidityAggregator.DistinctValue("CRM", "FR12345678901", 1),
			new ValidityAggregator.DistinctValue("ERP", "FR12345678901", 1));
		final Map<String, ValidityAggregator.SystemValidity> out = ValidityAggregator.aggregate(values, Arrays.asList(ValidityAggregatorTest.frVatRule()), null);
		final List<String> keys = new ArrayList<String>(out.keySet());
		ValidityAggregatorTest.check("CRM first, then ERP - order of first appearance", keys.equals(Arrays.asList("CRM", "ERP")));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
