package com.on.ebx.factory.ebx;

import java.util.Locale;

import com.on.ebx.factory.wizard.WizardMessages;

/** JUnit-free: run the main, a failure throws. The stage sentence of the normalisation hooks, in both languages. */
public final class NormalisationHooksTest
{
	public static void main(final String[] pArgs)
	{
		final NormalisationSuggester.Outcome none = new NormalisationSuggester.Outcome();
		check("nothing done: the 'nothing to correct' sentence", NormalisationHooks.sentence(none, Locale.ENGLISH).contains("nothing to correct"));
		final NormalisationSuggester.Outcome some = new NormalisationSuggester.Outcome();
		some.stepsCreated = 5;
		some.stepsRepaired = 1;
		some.pipelinesFixed = 1;
		some.linksFlagged = 2;
		final String english = NormalisationHooks.sentence(some, Locale.ENGLISH);
		check("English counts and plurals: " + english, english.equals("Value checks: 6 cleaning steps, 1 conversion fixed, 2 links to check."));
		final String french = NormalisationHooks.sentence(some, Locale.FRENCH);
		check("French counts and plurals: " + french, french.equals("Vérifications des valeurs : 6 étapes de nettoyage, 1 conversion corrigée, 2 liens à vérifier."));
		some.linksFlagged = 1;
		check("singular", NormalisationHooks.sentence(some, Locale.ENGLISH).endsWith("1 link to check."));
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			for (final String key : new String[] { "wizard.step4.run.stage.normalise.failed", "mappingMatrix.attention.conversionFails" })
			{
				check("key " + key + " is in the " + locale + " bundle", !key.equals(WizardMessages.get(locale, key)));
			}
		}
		check("the matrix reason takes its three arguments", WizardMessages.get(Locale.ENGLISH, "mappingMatrix.attention.conversionFails", "3", "40", "DATE")
			.equals("this rule gives 3 of the 40 sampled values that the DATE field cannot store"));
		System.out.println("NormalisationHooksTest OK");
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("NormalisationHooksTest: " + pLabel);
		}
	}
}
