package com.on.ebx.factory.wizard;

import java.util.Locale;

/** JUnit-free: run the main, a failure throws. F3-114 (1.4.2): the card of the style a saved project already has carries no "Coming soon" chip. */
public final class WizardStyleCardTest
{
	public static void main(final String[] pArgs)
	{
		final WizardRules.ImplementationStyle centralisation = WizardRules.styleByCode("CENTRALISATION");
		final String soon = WizardMessages.get(Locale.ENGLISH, "wizard.comingSoon");
		WizardStyleCardTest.check("the bundle says 'Coming soon'", soon != null && !soon.isEmpty() && !"wizard.comingSoon".equals(soon));
		final StringBuilder fresh = new StringBuilder();
		WizardPage.renderStyleCard(fresh, Locale.ENGLISH, centralisation, false, false);
		WizardStyleCardTest.check("a new project's Centralisation card says Coming soon", fresh.indexOf(soon) >= 0);
		final StringBuilder otherSaved = new StringBuilder();
		WizardPage.renderStyleCard(otherSaved, Locale.ENGLISH, centralisation, false, true);
		WizardStyleCardTest.check("the Centralisation card of a saved Consolidation project still says Coming soon", otherSaved.indexOf(soon) >= 0);
		final StringBuilder own = new StringBuilder();
		WizardPage.renderStyleCard(own, Locale.ENGLISH, centralisation, true, true);
		WizardStyleCardTest.check("the card of a saved Centralisation project says nothing of Coming soon", own.indexOf(soon) < 0);
		WizardStyleCardTest.check("... and it stays selected and disabled", own.indexOf("checked=\"checked\"") >= 0 && own.indexOf("disabled=\"disabled\"") >= 0);
		System.out.println("WizardStyleCardTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
