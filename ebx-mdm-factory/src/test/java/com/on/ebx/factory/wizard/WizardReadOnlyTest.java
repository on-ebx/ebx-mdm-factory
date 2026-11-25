package com.on.ebx.factory.wizard;

import java.util.Locale;

import com.on.ebx.factory.core.FactoryRight;

/**
 * JUnit-free: run the main, a failure throws. Audit F-02: the read-only wizard - {@link WizardPage#disableDenied} disables what
 * the session's right does not cover, keeps navigation, and says the page is read only below DESIGN.
 */
public final class WizardReadOnlyTest
{
	private static final String PAGE = "<div class=\"mdmf mdmf-wizard\"><input type=\"hidden\" name=\"mdmfAction\" value=\"\"><div class=\"mdmf-wiz\" id=\"mdmfWizRoot\">"
		+ "<button type=\"button\" data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"2\">2</button>"
		+ "<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"SAVE_PROJECT\">Save</button>"
		+ "<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"MATCH_PAIR_MERGE\" data-mdmf-id=\"7\" title=\"Merge\">Merge</button>"
		+ "<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STEP_RESET_PLAN\" disabled=\"disabled\">Reset</button>"
		+ "<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"INTAKE_SAVE\">Save intake</button>"
		+ "<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"JOBS_POLL\">Poll</button></div></div>";

	public static void main(final String[] pArgs)
	{
		final String view = WizardPage.disableDenied(WizardReadOnlyTest.PAGE, FactoryRight.VIEW, false, Locale.ENGLISH);
		WizardReadOnlyTest.check("navigation stays enabled", view.contains("<button type=\"button\" data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"2\">"));
		WizardReadOnlyTest.check("a poll stays enabled", view.contains("data-mdmf-action=\"JOBS_POLL\">"));
		WizardReadOnlyTest.check("VIEW: save is disabled and marked", WizardReadOnlyTest.denied(view, "SAVE_PROJECT"));
		WizardReadOnlyTest.check("VIEW: merge is disabled", WizardReadOnlyTest.denied(view, "MATCH_PAIR_MERGE"));
		WizardReadOnlyTest.check("the title names the right", view.contains("title=\"Needs the MDM Factory right STEWARD\""));
		WizardReadOnlyTest.check("an own title is replaced, not doubled", !view.contains("title=\"Merge\""));
		final String resetTag = WizardReadOnlyTest.tag(view, "STEP_RESET_PLAN");
		WizardReadOnlyTest.check("an own disabled is not doubled", resetTag.indexOf("disabled=") == resetTag.lastIndexOf("disabled="));
		WizardReadOnlyTest.check("VIEW: the read-only notice is shown inside the page", view.contains("data-mdmf-readonly=\"true\"")
			&& view.indexOf("data-mdmf-readonly") < view.indexOf("id=\"mdmfWizRoot\"") && view.contains("Read only: your MDM Factory right is VIEW."));
		WizardReadOnlyTest.check("VIEW without the launcher role: intake save disabled", WizardReadOnlyTest.denied(view, "INTAKE_SAVE"));
		final String operator = WizardPage.disableDenied(WizardReadOnlyTest.PAGE, FactoryRight.VIEW, true, Locale.ENGLISH);
		WizardReadOnlyTest.check("the pipeline launcher role keeps intake save", !WizardReadOnlyTest.denied(operator, "INTAKE_SAVE"));
		final String steward = WizardPage.disableDenied(WizardReadOnlyTest.PAGE, FactoryRight.STEWARD, false, Locale.ENGLISH);
		WizardReadOnlyTest.check("STEWARD: merge enabled", !WizardReadOnlyTest.denied(steward, "MATCH_PAIR_MERGE"));
		WizardReadOnlyTest.check("STEWARD: save still disabled", WizardReadOnlyTest.denied(steward, "SAVE_PROJECT"));
		final String design = WizardPage.disableDenied(WizardReadOnlyTest.PAGE, FactoryRight.DESIGN, false, Locale.ENGLISH);
		WizardReadOnlyTest.check("DESIGN: save and merge enabled", !WizardReadOnlyTest.denied(design, "SAVE_PROJECT") && !WizardReadOnlyTest.denied(design, "MATCH_PAIR_MERGE"));
		WizardReadOnlyTest.check("DESIGN: the reset stays the administrator's", WizardReadOnlyTest.denied(design, "STEP_RESET_PLAN"));
		WizardReadOnlyTest.check("DESIGN: no read-only notice", !design.contains("data-mdmf-readonly"));
		final String admin = WizardPage.disableDenied(WizardReadOnlyTest.PAGE, FactoryRight.ADMINISTRATOR, false, Locale.ENGLISH);
		WizardReadOnlyTest.check("ADMINISTRATOR: nothing changes", admin.equals(WizardReadOnlyTest.PAGE));
		final String french = WizardPage.disableDenied(WizardReadOnlyTest.PAGE, FactoryRight.VIEW, false, Locale.FRENCH);
		WizardReadOnlyTest.check("French notice, apostrophe rendered once (escaped)", french.contains("qu&#39;il") && !french.contains("qu&#39;&#39;il"));

		// a whole rendered page: a model with the VIEW right shows no enabled SAVE_PROJECT
		final WizardPage.Model model = new WizardPage.Model();
		model.right = FactoryRight.VIEW;
		final String html = WizardPage.render(model);
		WizardReadOnlyTest.check("a real render disables SAVE_PROJECT for VIEW", WizardReadOnlyTest.denied(html, "SAVE_PROJECT"));
		final WizardPage.Model unset = new WizardPage.Model();
		WizardReadOnlyTest.check("a model without a right renders as before", !WizardPage.render(unset).contains("data-mdmf-denied"));
		System.out.println("WizardReadOnlyTest OK");
	}

	/** The element of that action carries the denial marker and is disabled. */
	private static boolean denied(final String pHtml, final String pAction)
	{
		final String tag = WizardReadOnlyTest.tag(pHtml, pAction);
		return tag.contains("data-mdmf-denied=\"true\"") && tag.contains("disabled=\"disabled\"");
	}

	private static String tag(final String pHtml, final String pAction)
	{
		final int at = pHtml.indexOf("data-mdmf-action=\"" + pAction + "\"");
		if (at < 0)
		{
			throw new AssertionError("no element for " + pAction);
		}
		return pHtml.substring(pHtml.lastIndexOf('<', at), pHtml.indexOf('>', at));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
