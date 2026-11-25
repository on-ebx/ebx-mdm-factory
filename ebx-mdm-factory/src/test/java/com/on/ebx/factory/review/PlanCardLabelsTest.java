package com.on.ebx.factory.review;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Step 6 plan card (2026-10-18): the fields of a plan read as the entity's attribute labels ("Date d'achat", "Code magasin") and the comparison words are the reader's language
 * ("identique"), not the English words made of the identifiers ("Purchase date", "Store code", "identical"). JUnit-free: run the main, a failure throws.
 */
public final class PlanCardLabelsTest
{
	private static final String REQUIREMENTS = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":["
		+ "{\"field\":\"purchaseDate\",\"algorithm\":\"ebx-addon-mame@exact\"},{\"field\":\"storeCode\",\"algorithm\":\"ebx-addon-mame@exact\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}";

	public static void main(final String[] pArgs)
	{
		PlanCardLabelsTest.humanizeUsesLabelsOnlyWhileTheyAreSet();
		PlanCardLabelsTest.algorithmWordsInTheReadersLanguage();
		PlanCardLabelsTest.theCardInFrench();
		System.out.println("PlanCardLabelsTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static Map<String, String> labels()
	{
		final Map<String, String> labels = new LinkedHashMap<String, String>();
		labels.put("purchaseDate", "Date d'achat");
		labels.put("storeCode", "Code magasin");
		return labels;
	}

	private static void humanizeUsesLabelsOnlyWhileTheyAreSet()
	{
		check("without labels the identifier is humanised, as before", FieldWords.humanize("purchaseDate").equals("Purchase date"));
		final String[] inside = new String[2];
		FieldWords.withLabels(PlanCardLabelsTest.labels(), () ->
		{
			inside[0] = FieldWords.humanize("purchaseDate");
			inside[1] = FieldWords.humanize("unlabelled");
		});
		check("a labelled field reads as its label", inside[0].equals("Date d'achat"));
		check("a field with no label is humanised", inside[1].equals("Unlabelled"));
		check("the labels are gone afterwards", FieldWords.humanize("purchaseDate").equals("Purchase date"));
		try
		{
			FieldWords.withLabels(PlanCardLabelsTest.labels(), () ->
			{
				throw new IllegalStateException("boom");
			});
		}
		catch (final IllegalStateException expected)
		{
			// the labels must not leak out of a failed render
		}
		check("and gone after a failure too", FieldWords.humanize("storeCode").equals("Store code"));
	}

	private static void algorithmWordsInTheReadersLanguage()
	{
		check("French: identical", ProposalWording.matchAlgorithmShortWords("ebx-addon-mame@exact", Locale.FRENCH).equals("identique"));
		check("French: close spelling, short and long", ProposalWording.matchAlgorithmShortWords("ebx-addon-mame@jaroWinkler", Locale.FRENCH).equals("orthographe proche")
			&& ProposalWording.matchAlgorithmWords("ebx-addon-mame@jaroWinkler", Locale.FRENCH).equals("orthographe proche (Jaro-Winkler)"));
		check("French: beider-morse is read on its bare suffix", ProposalWording.matchAlgorithmWords("ebx-addon-mame@beider-morse", Locale.FRENCH).equals("sonne pareil"));
		check("English is the English of before", ProposalWording.matchAlgorithmWords("ebx-addon-mame@fuzzyFullText", Locale.ENGLISH).equals("resembles (fuzzy, full text)")
			&& ProposalWording.matchAlgorithmShortWords("ebx-addon-mame@exact", Locale.ENGLISH).equals("identical"));
		check("an unknown code is shown as it is", ProposalWording.matchAlgorithmWords("ebx-addon-mame@somethingNew", Locale.FRENCH).equals("ebx-addon-mame@somethingNew"));
		check("a blank code is a placeholder", ProposalWording.matchAlgorithmWords(null, Locale.FRENCH).equals("?"));
	}

	private static void theCardInFrench()
	{
		final MatchPlanView.Input input = new MatchPlanView.Input("Achat", PlanCardLabelsTest.REQUIREMENTS, null, false, false, null, null, 30, "mp1", Locale.FRENCH);
		final String[] html = new String[2];
		FieldWords.withLabels(PlanCardLabelsTest.labels(), () ->
		{
			html[0] = MatchPlanView.renderTree(input);
			html[1] = MatchPlanView.renderDetails(input);
		});
		final String tree = html[0] + html[1];
		check("the fields read as the entity's labels: " + tree.substring(0, Math.min(300, tree.length())), tree.contains("Date d&#39;achat") && tree.contains("Code magasin"));
		check("no English words made of the identifiers", !tree.contains("Purchase date") && !tree.contains("Store code"));
		check("the comparison is in French", tree.contains(">identique<") && !tree.contains(">identical<"));
		final String plain = MatchPlanView.renderTree(input);
		check("without labels the card is what it was", plain.contains("Purchase date") && plain.contains("Store code"));
	}
}
