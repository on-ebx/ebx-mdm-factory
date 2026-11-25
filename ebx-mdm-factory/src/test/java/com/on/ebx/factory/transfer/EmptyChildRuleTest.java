package com.on.ebx.factory.transfer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JUnit-free, like the tests of the sibling classes: run the main, a failure throws. */
public final class EmptyChildRuleTest
{
	public static void main(final String[] pArgs)
	{
		EmptyChildRuleTest.isConstant_recognisesLeadingConstantCall();
		EmptyChildRuleTest.isConstant_otherPipelinesAreNot();
		EmptyChildRuleTest.isConstant_nullIsNot();
		EmptyChildRuleTest.isBlank_nullAndEmptyAndWhitespaceAreBlank();
		EmptyChildRuleTest.isBlank_nonBlankStringIsNot();
		EmptyChildRuleTest.isBlank_nonStringValueIsNeverBlank();
		EmptyChildRuleTest.isEmpty_liveBugScenario_onlyConstantAndTechnicalFieldsSet();
		EmptyChildRuleTest.isEmpty_oneRealFieldPresent_isNotEmpty();
		EmptyChildRuleTest.isEmpty_everyFieldIsConstant_isNeverEmpty();
		EmptyChildRuleTest.isEmpty_noFieldsAtAll_isNeverEmpty();
		EmptyChildRuleTest.isEmpty_blankStringCountsAsEmpty();
		System.out.println("EmptyChildRuleTest OK");
	}

	private static void isConstant_recognisesLeadingConstantCall()
	{
		EmptyChildRuleTest.check("bare constant(...)", EmptyChildRule.isConstant("constant(OTHER)"));
		EmptyChildRuleTest.check("leading/trailing whitespace tolerated", EmptyChildRule.isConstant("  constant(OTHER)  "));
	}

	private static void isConstant_otherPipelinesAreNot()
	{
		EmptyChildRuleTest.check("trim|upper is not a constant", !EmptyChildRule.isConstant("trim|upper"));
		EmptyChildRuleTest.check("empty pipeline is not a constant", !EmptyChildRule.isConstant(""));
		EmptyChildRuleTest.check("a pipeline merely CONTAINING constant( later is not recognised - only a LEADING one",
			!EmptyChildRule.isConstant("upper|constant(OTHER)"));
	}

	private static void isConstant_nullIsNot()
	{
		EmptyChildRuleTest.check("null pipeline is not a constant", !EmptyChildRule.isConstant(null));
	}

	private static void isBlank_nullAndEmptyAndWhitespaceAreBlank()
	{
		EmptyChildRuleTest.check("null is blank", EmptyChildRule.isBlank(null));
		EmptyChildRuleTest.check("empty string is blank", EmptyChildRule.isBlank(""));
		EmptyChildRuleTest.check("whitespace-only string is blank", EmptyChildRule.isBlank("   "));
	}

	private static void isBlank_nonBlankStringIsNot()
	{
		EmptyChildRuleTest.check("a real string value is not blank", !EmptyChildRule.isBlank("123 Main St"));
	}

	private static void isBlank_nonStringValueIsNeverBlank()
	{
		EmptyChildRuleTest.check("a non-string, non-null value is never blank", !EmptyChildRule.isBlank(Boolean.TRUE));
	}

	/** The exact live defect (docs/TARGET-MAPPING.md, 2026-09-25): an OTHER-address child feed on a contact with no OTHER address - only {@code addressType <- constant(OTHER)} ever produces a value. */
	private static void isEmpty_liveBugScenario_onlyConstantAndTechnicalFieldsSet()
	{
		final List<TransferPlan.FieldPlan> fields = new ArrayList<TransferPlan.FieldPlan>();
		fields.add(EmptyChildRuleTest.field("addressType", "constant(OTHER)"));
		fields.add(EmptyChildRuleTest.field("street", ""));
		fields.add(EmptyChildRuleTest.field("city", ""));
		fields.add(EmptyChildRuleTest.field("postalCode", ""));

		final Map<String, Object> values = new LinkedHashMap<String, Object>();
		values.put("addressType", "OTHER");
		values.put("street", null);
		values.put("city", null);
		values.put("postalCode", null);

		EmptyChildRuleTest.check("only the constant field is set: the child is empty", EmptyChildRule.isEmpty(fields, values));
	}

	private static void isEmpty_oneRealFieldPresent_isNotEmpty()
	{
		final List<TransferPlan.FieldPlan> fields = new ArrayList<TransferPlan.FieldPlan>();
		fields.add(EmptyChildRuleTest.field("addressType", "constant(OTHER)"));
		fields.add(EmptyChildRuleTest.field("street", ""));
		fields.add(EmptyChildRuleTest.field("city", ""));

		final Map<String, Object> values = new LinkedHashMap<String, Object>();
		values.put("addressType", "OTHER");
		values.put("street", "12 Rue de la Paix");
		values.put("city", null);

		EmptyChildRuleTest.check("one real field is present: the child is not empty", !EmptyChildRule.isEmpty(fields, values));
	}

	private static void isEmpty_everyFieldIsConstant_isNeverEmpty()
	{
		final List<TransferPlan.FieldPlan> fields = new ArrayList<TransferPlan.FieldPlan>();
		fields.add(EmptyChildRuleTest.field("addressType", "constant(OTHER)"));
		fields.add(EmptyChildRuleTest.field("countryHint", "constant(FR)"));

		final Map<String, Object> values = new LinkedHashMap<String, Object>();
		values.put("addressType", "OTHER");
		values.put("countryHint", "FR");

		EmptyChildRuleTest.check("nothing but constants: never flagged empty - there is no 'missing' source value to detect",
			!EmptyChildRule.isEmpty(fields, values));
	}

	private static void isEmpty_noFieldsAtAll_isNeverEmpty()
	{
		EmptyChildRuleTest.check("a feed with no fields at all is never flagged empty",
			!EmptyChildRule.isEmpty(new ArrayList<TransferPlan.FieldPlan>(), new LinkedHashMap<String, Object>()));
	}

	private static void isEmpty_blankStringCountsAsEmpty()
	{
		final List<TransferPlan.FieldPlan> fields = new ArrayList<TransferPlan.FieldPlan>();
		fields.add(EmptyChildRuleTest.field("street", ""));

		final Map<String, Object> values = new LinkedHashMap<String, Object>();
		values.put("street", "   ");

		EmptyChildRuleTest.check("a whitespace-only value is blank, same as null", EmptyChildRule.isEmpty(fields, values));
	}

	private static TransferPlan.FieldPlan field(final String pStagingField, final String pPipeline)
	{
		return new TransferPlan.FieldPlan(pStagingField, "STRING", null, false, pPipeline, pStagingField, null, null, false, false, null, null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
