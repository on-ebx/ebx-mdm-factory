package com.on.ebx.factory.lineage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.on.ebx.factory.lineage.RecordTitleRule.Field;

/** JUnit-free, like the sibling tests: run the main, a failure throws. */
public final class RecordTitleRuleTest
{
	public static void main(final String[] pArgs)
	{
		RecordTitleRuleTest.pickTitle_realLabelWins();
		RecordTitleRuleTest.pickTitle_blankLabelFallsBackToFields();
		RecordTitleRuleTest.pickTitle_labelEqualToKey_nameFieldWins();
		RecordTitleRuleTest.pickTitle_labelEqualToKey_onlyAccountNameWins();
		RecordTitleRuleTest.pickTitle_labelEqualToKey_labelAndTitleFieldsAlsoMatchExactTier();
		RecordTitleRuleTest.pickTitle_exactTierBeforeSuffixTier();
		RecordTitleRuleTest.pickTitle_suffixTierBeforeFallbackTier();
		RecordTitleRuleTest.pickTitle_allFieldsRestrictedOrBlank_fallsBackToKey();
		RecordTitleRuleTest.pickTitle_noFieldsAtAll_fallsBackToKey();
		RecordTitleRuleTest.pickTitle_neverPicksSourceSystemOrSourceId();
		RecordTitleRuleTest.pickTitle_neverPicksTechnicalUnderscoreField();
		RecordTitleRuleTest.pickTitle_caseInsensitiveNameMatch();
		RecordTitleRuleTest.pickTitle_blankFieldValueSkipped_nextCandidateUsed();
		RecordTitleRuleTest.pickTitle_bareNameNotDoubleMatchedBySuffixTier();
		RecordTitleRuleTest.pickDisplayFieldName_exactNameWins();
		RecordTitleRuleTest.pickDisplayFieldName_suffixWinsWhenNoExactMatch();
		RecordTitleRuleTest.pickDisplayFieldName_fallbackWhenNoNameLikeField();
		RecordTitleRuleTest.pickDisplayFieldName_nullWhenOnlyTechnicalFields();
		RecordTitleRuleTest.pickDisplayFieldName_nullFieldList();
		System.out.println("RecordTitleRuleTest OK");
	}

	// --------------------------------------------------------------------------------------------------- pickTitle

	private static void pickTitle_realLabelWins()
	{
		final String title = RecordTitleRule.pickTitle("BPCE", "183", RecordTitleRuleTest.fields("name", "Ignored"));
		RecordTitleRuleTest.check("a real, non-key label wins over every field", "BPCE".equals(title));
	}

	private static void pickTitle_blankLabelFallsBackToFields()
	{
		final String title = RecordTitleRule.pickTitle("   ", "183", RecordTitleRuleTest.fields("name", "BPCE"));
		RecordTitleRuleTest.check("a blank label is treated as absent, same as null", "BPCE".equals(title));
	}

	/** The task's own acceptance example: label blank/echoes the key, a "name" field is present. */
	private static void pickTitle_labelEqualToKey_nameFieldWins()
	{
		final List<Field> fields = RecordTitleRuleTest.fields("id", "183", "name", "BPCE", "accountName", "Should not be used");
		final String title = RecordTitleRule.pickTitle("183", "183", fields);
		RecordTitleRuleTest.check("label == key -> falls back to the 'name' field, before any suffix match", "BPCE".equals(title));
	}

	/** The task's own second acceptance example: label blank, only an accountName-shaped field available. */
	private static void pickTitle_labelEqualToKey_onlyAccountNameWins()
	{
		final List<Field> fields = RecordTitleRuleTest.fields("sourceSystem", "ERP", "accountName", "Banque Populaire");
		final String title = RecordTitleRule.pickTitle(null, "183", fields);
		RecordTitleRuleTest.check("no label, no bare name/label/title field -> falls back to the '...Name' suffix tier", "Banque Populaire".equals(title));
	}

	private static void pickTitle_labelEqualToKey_labelAndTitleFieldsAlsoMatchExactTier()
	{
		RecordTitleRuleTest.check("a field literally named 'label' matches the exact tier",
			"A Label Value".equals(RecordTitleRule.pickTitle(null, "K", RecordTitleRuleTest.fields("label", "A Label Value"))));
		RecordTitleRuleTest.check("a field literally named 'title' matches the exact tier",
			"A Title Value".equals(RecordTitleRule.pickTitle(null, "K", RecordTitleRuleTest.fields("title", "A Title Value"))));
	}

	private static void pickTitle_exactTierBeforeSuffixTier()
	{
		final List<Field> fields = RecordTitleRuleTest.fields("legalName", "Should lose", "name", "Should win");
		RecordTitleRuleTest.check("the exact-name tier is tried before the suffix tier, regardless of field order",
			"Should win".equals(RecordTitleRule.pickTitle(null, "K", fields)));
	}

	private static void pickTitle_suffixTierBeforeFallbackTier()
	{
		final List<Field> fields = RecordTitleRuleTest.fields("city", "Should lose", "legalName", "Should win");
		RecordTitleRuleTest.check("the suffix tier is tried before the plain fallback tier, regardless of field order",
			"Should win".equals(RecordTitleRule.pickTitle(null, "K", fields)));
	}

	private static void pickTitle_allFieldsRestrictedOrBlank_fallsBackToKey()
	{
		// "restricted" fields never even reach this pure class (the EBX-bound caller omits them) - modelled
		// here as an empty list, exactly like every field being blank would look from this class's own view.
		final List<Field> fields = RecordTitleRuleTest.fields("city", "   ", "country", "");
		RecordTitleRuleTest.check("every candidate blank (or none offered at all) -> the key itself, never null",
			"183".equals(RecordTitleRule.pickTitle(null, "183", fields)));
	}

	private static void pickTitle_noFieldsAtAll_fallsBackToKey()
	{
		RecordTitleRuleTest.check("null field list -> the key", "183".equals(RecordTitleRule.pickTitle(null, "183", null)));
		RecordTitleRuleTest.check("empty field list -> the key", "183".equals(RecordTitleRule.pickTitle(null, "183", new ArrayList<Field>())));
	}

	private static void pickTitle_neverPicksSourceSystemOrSourceId()
	{
		final List<Field> fields = RecordTitleRuleTest.fields("sourceSystem", "ERP", "sourceId", "42", "SOURCEID", "dup-case");
		RecordTitleRuleTest.check("sourceSystem/sourceId (any case) are never eligible fallback fields, whatever the key",
			"183".equals(RecordTitleRule.pickTitle(null, "183", fields)));
	}

	private static void pickTitle_neverPicksTechnicalUnderscoreField()
	{
		final List<Field> fields = RecordTitleRuleTest.fields("_matchKey", "ZURICH");
		RecordTitleRuleTest.check("a '_'-prefixed technical field is never an eligible fallback",
			"183".equals(RecordTitleRule.pickTitle(null, "183", fields)));
	}

	private static void pickTitle_caseInsensitiveNameMatch()
	{
		RecordTitleRuleTest.check("the exact-name tier matches regardless of case",
			"BPCE".equals(RecordTitleRule.pickTitle(null, "183", RecordTitleRuleTest.fields("NAME", "BPCE"))));
	}

	private static void pickTitle_blankFieldValueSkipped_nextCandidateUsed()
	{
		final List<Field> fields = RecordTitleRuleTest.fields("name", "   ", "legalName", "Banque Populaire");
		RecordTitleRuleTest.check("a blank value at a higher tier is skipped, never returned, never blocking a lower tier",
			"Banque Populaire".equals(RecordTitleRule.pickTitle(null, "183", fields)));
	}

	/** Guards the Javadoc's own claim: a bare "name" is not re-matched by the suffix tier once it fails the exact tier for being blank. */
	private static void pickTitle_bareNameNotDoubleMatchedBySuffixTier()
	{
		final List<Field> fields = RecordTitleRuleTest.fields("name", "   ", "city", "Paris");
		RecordTitleRuleTest.check("a blank 'name' does not satisfy the suffix tier either (it is not 'accountName'-shaped) - the plain fallback tier wins",
			"Paris".equals(RecordTitleRule.pickTitle(null, "183", fields)));
	}

	// ------------------------------------------------------------------------------------------- pickDisplayFieldName

	private static void pickDisplayFieldName_exactNameWins()
	{
		RecordTitleRuleTest.check("'name' wins over a suffix-shaped field",
			"name".equals(RecordTitleRule.pickDisplayFieldName(Arrays.asList("legalName", "name", "city"))));
	}

	private static void pickDisplayFieldName_suffixWinsWhenNoExactMatch()
	{
		RecordTitleRuleTest.check("'accountName' wins when no bare name/label/title field exists",
			"accountName".equals(RecordTitleRule.pickDisplayFieldName(Arrays.asList("sourceSystem", "accountName", "city"))));
	}

	private static void pickDisplayFieldName_fallbackWhenNoNameLikeField()
	{
		RecordTitleRuleTest.check("the first eligible field wins when nothing looks name-shaped",
			"city".equals(RecordTitleRule.pickDisplayFieldName(Arrays.asList("sourceSystem", "sourceId", "city", "country"))));
	}

	private static void pickDisplayFieldName_nullWhenOnlyTechnicalFields()
	{
		RecordTitleRuleTest.check("only sourceSystem/sourceId/'_'-prefixed fields -> null, never a technical field chosen",
			RecordTitleRule.pickDisplayFieldName(Arrays.asList("sourceSystem", "sourceId", "_matchKey")) == null);
	}

	private static void pickDisplayFieldName_nullFieldList()
	{
		RecordTitleRuleTest.check("a null field list answers null, never throws", RecordTitleRule.pickDisplayFieldName(null) == null);
	}

	// --------------------------------------------------------------------------------------------------------- misc

	private static List<Field> fields(final String... pNameValuePairs)
	{
		final List<Field> out = new ArrayList<Field>();
		for (int i = 0; i < pNameValuePairs.length; i += 2)
		{
			out.add(new Field(pNameValuePairs[i], pNameValuePairs[i + 1]));
		}
		return out;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
