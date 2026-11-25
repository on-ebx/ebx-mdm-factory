package com.on.ebx.factory.staging;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.factory.quality.QualityDetectors;
import com.on.ebx.factory.quality.QualityRuleCheck;
import com.on.ebx.factory.quality.ValidValueDerivation;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. {@link DerivedFieldRule}
 * is the pure seam pulled out of {@code ebx.StagingLoader.planRow} (2026-09-22, product-owner decision -
 * PLAN.md) so the "compute every derived field from a row's own already-planned fields" step can be tested
 * without a running EBX instance - see that class's own Javadoc. Extended 2026-09-23 (lot 1, quality-informed
 * survivorship) for the {@code VALID_VALUE} derivation, next to the pre-existing {@code FOLD_KEY} coverage.
 */
public final class DerivedFieldRuleTest
{
	public static void main(final String[] pArgs)
	{
		DerivedFieldRuleTest.foldsTheSourceFieldIntoTheKeyField();
		DerivedFieldRuleTest.missingSourceFieldYieldsANullKey();
		DerivedFieldRuleTest.sourceFieldExplicitlyNullYieldsANullKey();
		DerivedFieldRuleTest.severalDerivedFieldsOfOneRowAreAllComputed();
		DerivedFieldRuleTest.originalMapIsNeverMutated();
		DerivedFieldRuleTest.emptyOrNullDerivedFieldsIsAPureCopy();
		DerivedFieldRuleTest.aSecondDomainProductDesignation();
		DerivedFieldRuleTest.validValue_passingValueIsKeptUnchanged();
		DerivedFieldRuleTest.validValue_failingValueBecomesNull();
		DerivedFieldRuleTest.validValue_everyRuleMustPass();
		DerivedFieldRuleTest.validValue_noRulesYieldsNull();
		DerivedFieldRuleTest.validValue_missingSourceYieldsNull();
		DerivedFieldRuleTest.foldKeyAndValidValueOnTheSameRow();
		DerivedFieldRuleTest.scriptAndUnknownDerivationsAreNeverComputedHere();
		DerivedFieldRuleTest.producesKeyAgreesWithApply();
		System.out.println("DerivedFieldRuleTest OK");
	}

	/**
	 * Lot M5 (2026-10-04): the defect itself. A {@code SCRIPT} (or any unknown) derivation reaching {@code apply} used to be read as a fold -
	 * for a phone number, upper-casing and trimming leave it as it was, so {@code phoneE164} became a copy of {@code phone} on every row.
	 */
	private static void scriptAndUnknownDerivationsAreNeverComputedHere()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("phone", "+31 10 12 64 418");
		final Map<String, Object> out = DerivedFieldRule.apply(fields,
			Arrays.asList(new DerivedFieldRule.Field("phone", "phoneE164", "SCRIPT", null), new DerivedFieldRule.Field("phone", "phoneOther", "SOMETHING_NEW", null)), null);
		DerivedFieldRuleTest.check("a SCRIPT derivation is not written by the Java rule", !out.containsKey("phoneE164"));
		DerivedFieldRuleTest.check("an unknown derivation is not read as a fold", !out.containsKey("phoneOther"));
		DerivedFieldRuleTest.check("the source is untouched", "+31 10 12 64 418".equals(out.get("phone")) && out.size() == 1);
		final Map<String, Object> withFold = DerivedFieldRule.apply(fields, Arrays.asList(new DerivedFieldRule.Field("phone", "phoneE164", "SCRIPT", null),
			new DerivedFieldRule.Field("phone", "phoneKey")), null);
		DerivedFieldRuleTest.check("a FOLD_KEY next to it is still computed", withFold.containsKey("phoneKey") && !withFold.containsKey("phoneE164"));
	}

	/** Lot V3-S2 (2026-10-04): {@code producesKey} says, before any row, exactly which key fields {@code apply} will put in a row's map. */
	private static void producesKeyAgreesWithApply()
	{
		final DerivedFieldRule.Field fold = new DerivedFieldRule.Field("name", "nameKey");
		final DerivedFieldRule.Field valid = new DerivedFieldRule.Field("iban", "ibanValid", ValidValueDerivation.DERIVATION_VALID_VALUE, Collections.<QualityRuleCheck.Rule> emptyList());
		final DerivedFieldRule.Field script = new DerivedFieldRule.Field("phone", "phoneE164", "SCRIPT", null);
		final DerivedFieldRule.Field childKeys = new DerivedFieldRule.Field("id", "childKeys", "CHILD_KEYS", null);
		final DerivedFieldRule.Field unknown = new DerivedFieldRule.Field("x", "xOther", "SOMETHING_NEW", null);
		for (final DerivedFieldRule.Field field : Arrays.asList(fold, valid, script, childKeys, unknown))
		{
			final Map<String, Object> out = DerivedFieldRule.apply(new LinkedHashMap<String, Object>(), Collections.singletonList(field), null);
			DerivedFieldRuleTest.check(field.keyFieldName + ": producesKey is what apply does", DerivedFieldRule.producesKey(field) == out.containsKey(field.keyFieldName));
		}
		DerivedFieldRuleTest.check("the two computed derivations produce a key", DerivedFieldRule.producesKey(fold) && DerivedFieldRule.producesKey(valid));
	}

	private static void foldsTheSourceFieldIntoTheKeyField()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("city", "Zürich");
		final Map<String, Object> out = DerivedFieldRule.apply(fields, Collections.singletonList(new DerivedFieldRule.Field("city", "cityKey")), null);
		DerivedFieldRuleTest.check("cityKey holds the folded value: " + out.get("cityKey"), "ZURICH".equals(out.get("cityKey")));
		DerivedFieldRuleTest.check("the source field itself is untouched, accents and case intact: " + out.get("city"), "Zürich".equals(out.get("city")));
	}

	/** "Missing" (this row's feed never supplied the source field, so it is simply absent from the map) reads as null - see the class Javadoc. */
	private static void missingSourceFieldYieldsANullKey()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("name", "Acme");
		final Map<String, Object> out = DerivedFieldRule.apply(fields, Collections.singletonList(new DerivedFieldRule.Field("city", "cityKey")), null);
		DerivedFieldRuleTest.check("no 'city' entry at all: cityKey is null", out.containsKey("cityKey") && out.get("cityKey") == null);
	}

	/** Same as above but the map DOES carry the key, explicitly null (e.g. NullFillRule's own forced-null default for a field this feed does not cover). */
	private static void sourceFieldExplicitlyNullYieldsANullKey()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("city", null);
		final Map<String, Object> out = DerivedFieldRule.apply(fields, Collections.singletonList(new DerivedFieldRule.Field("city", "cityKey")), null);
		DerivedFieldRuleTest.check("explicit null source: null key", out.get("cityKey") == null);
	}

	/** An entity can have more than one derived field on the same row (e.g. a matching key on both city and a product designation) - every one of them is computed. */
	private static void severalDerivedFieldsOfOneRowAreAllComputed()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("city", "São Paulo");
		fields.put("designation", "Écrou M8");
		final Map<String, Object> out = DerivedFieldRule.apply(fields,
			Arrays.asList(new DerivedFieldRule.Field("city", "cityKey"), new DerivedFieldRule.Field("designation", "designationKey")), null);
		DerivedFieldRuleTest.check("cityKey: " + out.get("cityKey"), "SAO PAULO".equals(out.get("cityKey")));
		DerivedFieldRuleTest.check("designationKey: " + out.get("designationKey"), "ECROU M8".equals(out.get("designationKey")));
	}

	/** {@link DerivedFieldRule#apply} never mutates its input - {@code ebx.StagingLoader.planRow} still needs the original {@code fields} map's own identity for its own bookkeeping. */
	private static void originalMapIsNeverMutated()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("city", "Zürich");
		final int sizeBefore = fields.size();
		DerivedFieldRule.apply(fields, Collections.singletonList(new DerivedFieldRule.Field("city", "cityKey")), null);
		DerivedFieldRuleTest.check("original map untouched (still just 'city'): " + fields.keySet(), fields.size() == sizeBefore && !fields.containsKey("cityKey"));
	}

	/** No derived fields (the common case: most entities have none) is a pure, harmless copy - never null, never throws. */
	private static void emptyOrNullDerivedFieldsIsAPureCopy()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("name", "Acme");
		DerivedFieldRuleTest.check("empty list: unchanged copy", DerivedFieldRule.apply(fields, Collections.<DerivedFieldRule.Field>emptyList(), null).equals(fields));
		DerivedFieldRuleTest.check("null list: unchanged copy, never throws", DerivedFieldRule.apply(fields, null, null).equals(fields));
	}

	/** A second domain (a product designation, never only city/company vocabulary), with an accented source value. */
	private static void aSecondDomainProductDesignation()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("designation", "Rondelle élastique");
		final Map<String, Object> out = DerivedFieldRule.apply(fields, Collections.singletonList(new DerivedFieldRule.Field("designation", "designationKey")), null);
		DerivedFieldRuleTest.check("designationKey: " + out.get("designationKey"), "RONDELLE ELASTIQUE".equals(out.get("designationKey")));
	}

	// -------------------------------------------------------------------------------------------------- VALID_VALUE

	private static QualityRuleCheck.Rule patternRule(final String pPattern)
	{
		return new QualityRuleCheck.Rule("r1", QualityDetectors.PATTERN, pPattern, QualityDetectors.WARN);
	}

	private static void validValue_passingValueIsKeptUnchanged()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("vatNo", "FR12345678901");
		final DerivedFieldRule.Field field = new DerivedFieldRule.Field("vatNo", "vatNoValid", ValidValueDerivation.DERIVATION_VALID_VALUE,
			Collections.singletonList(DerivedFieldRuleTest.patternRule("FR\\d{11}")));
		final Map<String, Object> out = DerivedFieldRule.apply(fields, Collections.singletonList(field), null);
		DerivedFieldRuleTest.check("passing value kept unchanged: " + out.get("vatNoValid"), "FR12345678901".equals(out.get("vatNoValid")));
		DerivedFieldRuleTest.check("source field itself untouched", "FR12345678901".equals(out.get("vatNo")));
	}

	private static void validValue_failingValueBecomesNull()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("vatNo", "not-a-vat-number");
		final DerivedFieldRule.Field field = new DerivedFieldRule.Field("vatNo", "vatNoValid", ValidValueDerivation.DERIVATION_VALID_VALUE,
			Collections.singletonList(DerivedFieldRuleTest.patternRule("FR\\d{11}")));
		final Map<String, Object> out = DerivedFieldRule.apply(fields, Collections.singletonList(field), null);
		DerivedFieldRuleTest.check("failing value becomes null", out.get("vatNoValid") == null);
		DerivedFieldRuleTest.check("source field itself untouched even though invalid", "not-a-vat-number".equals(out.get("vatNo")));
	}

	/** Every rule of the list must pass - failing only the second of two still nulls the derived field. */
	private static void validValue_everyRuleMustPass()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("code", "ABC123");
		final DerivedFieldRule.Field field = new DerivedFieldRule.Field("code", "codeValid", ValidValueDerivation.DERIVATION_VALID_VALUE,
			Arrays.asList(DerivedFieldRuleTest.patternRule("[A-Z]+\\d+"), DerivedFieldRuleTest.patternRule("Z.*")));
		final Map<String, Object> out = DerivedFieldRule.apply(fields, Collections.singletonList(field), null);
		DerivedFieldRuleTest.check("second rule fails: null", out.get("codeValid") == null);
	}

	/** A VALID_VALUE field with no rules at all (defensive - the proposer never creates one this way) never claims a value is valid. */
	private static void validValue_noRulesYieldsNull()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("vatNo", "FR12345678901");
		final DerivedFieldRule.Field field = new DerivedFieldRule.Field("vatNo", "vatNoValid", ValidValueDerivation.DERIVATION_VALID_VALUE,
			Collections.<QualityRuleCheck.Rule>emptyList());
		final Map<String, Object> out = DerivedFieldRule.apply(fields, Collections.singletonList(field), null);
		DerivedFieldRuleTest.check("no rules: null, never a guessed valid", out.get("vatNoValid") == null);
	}

	private static void validValue_missingSourceYieldsNull()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		final DerivedFieldRule.Field field = new DerivedFieldRule.Field("vatNo", "vatNoValid", ValidValueDerivation.DERIVATION_VALID_VALUE,
			Collections.singletonList(DerivedFieldRuleTest.patternRule("FR\\d{11}")));
		final Map<String, Object> out = DerivedFieldRule.apply(fields, Collections.singletonList(field), null);
		DerivedFieldRuleTest.check("missing source: null", out.get("vatNoValid") == null);
	}

	/** An entity can carry a FOLD_KEY field and a VALID_VALUE field on different attributes of the same row - both computed by the one {@link DerivedFieldRule#apply} call. */
	private static void foldKeyAndValidValueOnTheSameRow()
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("city", "Zürich");
		fields.put("vatNo", "not-a-vat-number");
		final DerivedFieldRule.Field foldField = new DerivedFieldRule.Field("city", "cityKey");
		final DerivedFieldRule.Field validField = new DerivedFieldRule.Field("vatNo", "vatNoValid", ValidValueDerivation.DERIVATION_VALID_VALUE,
			Collections.singletonList(DerivedFieldRuleTest.patternRule("FR\\d{11}")));
		final Map<String, Object> out = DerivedFieldRule.apply(fields, Arrays.asList(foldField, validField), null);
		DerivedFieldRuleTest.check("cityKey folded: " + out.get("cityKey"), "ZURICH".equals(out.get("cityKey")));
		DerivedFieldRuleTest.check("vatNoValid nulled: " + out.get("vatNoValid"), out.get("vatNoValid") == null);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
