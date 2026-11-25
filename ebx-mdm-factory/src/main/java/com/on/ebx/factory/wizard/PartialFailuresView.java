package com.on.ebx.factory.wizard;

import java.util.List;
import java.util.Locale;

/**
 * UX lot D (2026-10-14): the blocks that say WHAT is missing after a partial failure and offer to redo only that - step 3 (the landing tables that were not
 * loaded, "Retry the 2 failed tables"), step 6 (the objects refused when configuring, "Configure these 2 objects again"; the objects the last run failed) -
 * and the warnings under the buttons that go on ("The staging will be built without ..."). The words and the items come from {@link PartialFailures}; this
 * class only draws them, in the visual vocabulary of the wizard (notice, list, actions). Pure, no EBX class.
 */
final class PartialFailuresView
{
	private PartialFailuresView()
	{
	}

	/**
	 * Step 3, under the grid of a landing run that is over: the tables that were not loaded, each with the reason of its failed cell, and the button that runs
	 * only those. Empty when the last landing job did not end with failures or every table has been loaded since.
	 */
	static String landingBlock(final WizardPage.Model pModel, final Locale pLocale)
	{
		final PartialFailures.Last last = pModel.lastOutcomes.get(PartialFailures.LANDING);
		final List<PartialFailures.Item> missing = PartialFailures.stillMissing(last, PartialFailures.unloadedTickedTables(pModel.snapshot));
		if (missing.isEmpty())
		{
			return "";
		}
		final WizardRules.ActionGates gates = WizardRules.actionGates(pModel.snapshot, pModel.designer);
		final StringBuilder out = new StringBuilder();
		out.append("<div class=\"mdmf-notice mdmf-notice-warn mdmf-partial\" id=\"mdmfLandingMissing\"><b class=\"mdmf-partial-title\">")
			.append(WizardPage.esc(PartialFailures.landingTitle(missing, last.failures.total, pLocale))).append("</b>");
		PartialFailuresView.list(out, missing, pLocale);
		out.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"LANDING_RETRY_FAILED\"")
			.append(gates.generateLanding.enabled ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.plural(pLocale, missing.size(), "wizard.partial.landing.retry.one", "wizard.partial.landing.retry.other"))).append("</button>");
		out.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(gates.generateLanding.enabled ? WizardMessages.get(pLocale, "wizard.partial.landing.retryHint")
			: WizardMessages.get(pLocale, gates.generateLanding.reasonKey))).append("</span></div></div>");
		return out.toString();
	}

	/**
	 * Step 3, under "Continue to the staging": what the staging will leave out. Empty when no table is missing. Never blocks: the button above it is the
	 * wizard's own (the gate of the staging step lets a gap that a failed run explains through, {@code WizardRules#stepStates}).
	 */
	static String landingWarning(final WizardPage.Model pModel, final Locale pLocale)
	{
		final List<PartialFailures.Item> missing = PartialFailures.stillMissing(pModel.lastOutcomes.get(PartialFailures.LANDING),
			PartialFailures.unloadedTickedTables(pModel.snapshot));
		if (missing.isEmpty())
		{
			return "";
		}
		return "<p class=\"mdmf-hint mdmf-partial-warning\" id=\"mdmfLandingWarning\">" + WizardPage.esc(PartialFailures.stagingWarning(missing, pLocale)) + "</p>";
	}

	/**
	 * Step 6, above Run: the objects the last configuration refused (and that are still waiting to be applied), with the button that configures again. The
	 * button is the wizard's own Configure: it skips the objects already up to date, so it only redoes the refused ones.
	 */
	static String configureBlock(final WizardPage.Model pModel, final Locale pLocale, final boolean pConfigureEnabled)
	{
		final List<PartialFailures.Item> refused = PartialFailuresView.refusedAtConfigure(pModel);
		if (refused.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder();
		out.append("<div class=\"mdmf-notice mdmf-notice-warn mdmf-partial\" id=\"mdmfConfigureRefused\"><b class=\"mdmf-partial-title\">")
			.append(WizardPage.esc(PartialFailures.configureTitle(refused, pLocale))).append("</b>");
		PartialFailuresView.list(out, refused, pLocale);
		out.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"MATCHING_CONFIGURE\"")
			.append(pConfigureEnabled ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.plural(pLocale, refused.size(), "wizard.partial.configure.retry.one", "wizard.partial.configure.retry.other")))
			.append("</button></div></div>");
		return out.toString();
	}

	/** Step 6, under the Run text: what Run will leave out because it was refused at configuration. Empty when nothing was refused. */
	static String runWarning(final WizardPage.Model pModel, final Locale pLocale)
	{
		final List<PartialFailures.Item> refused = PartialFailuresView.refusedAtConfigure(pModel);
		if (refused.isEmpty())
		{
			return "";
		}
		return "<span class=\"mdmf-hint mdmf-partial-warning\" id=\"mdmfRunWarning\">" + WizardPage.esc(PartialFailures.runWarning(refused, pLocale)) + "</span>";
	}

	/** Step 6, below the live table: the objects the last matching run failed, with their reasons (Run above runs them all again). Empty when none failed. */
	static String runBlock(final WizardPage.Model pModel, final Locale pLocale)
	{
		final List<PartialFailures.Item> failed = PartialFailures.stillMissing(pModel.lastOutcomes.get(PartialFailures.RUN), null);
		if (failed.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder();
		out.append("<div class=\"mdmf-notice mdmf-notice-warn mdmf-partial\" id=\"mdmfRunFailed\"><b class=\"mdmf-partial-title\">")
			.append(WizardPage.esc(PartialFailures.runTitle(failed, pLocale))).append("</b>");
		PartialFailuresView.list(out, failed, pLocale);
		out.append("</div>");
		return out.toString();
	}

	private static List<PartialFailures.Item> refusedAtConfigure(final WizardPage.Model pModel)
	{
		return PartialFailures.stillMissing(pModel.lastOutcomes.get(PartialFailures.CONFIGURE), PartialFailures.unappliedPlans(pModel.snapshot));
	}

	private static void list(final StringBuilder pOut, final List<PartialFailures.Item> pItems, final Locale pLocale)
	{
		pOut.append("<ul class=\"mdmf-partial-list\">");
		for (final PartialFailures.Item item : pItems)
		{
			pOut.append("<li><b>").append(WizardPage.esc(item.label)).append("</b>");
			if (!item.reason.isEmpty())
			{
				pOut.append(" <span class=\"mdmf-hint\">").append(WizardPage.esc(RunPanelText.display(item.reason, pLocale))).append("</span>");
			}
			pOut.append("</li>");
		}
		pOut.append("</ul>");
	}
}
