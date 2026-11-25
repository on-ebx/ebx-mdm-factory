package com.on.ebx.factory.ebx;

import java.util.Locale;

import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.master.MatchPlanLlmPass;
import com.on.ebx.factory.master.SimulationFeedback;
import com.on.ebx.factory.wizard.WizardMessages;

/**
 * Lot F1 (2026-10-19, {@code docs/MATCHING.md} &sect;70): the ONE line the simulation feedback adds at the top of a plan's rationale (shown on the plan card at step 6), in the language of the
 * person who launched the proposal - {@code Simulation : 52 paires identiques sur emailKey laissées en suspect (87 paires évaluées, 20 pivots) - plan conservé par le modèle.} - and the
 * words the model gave when it kept its plan. Pure (the bundle only): counts and field names, no value.
 */
final class MatchPlanFeedbackText
{
	/** The most of the model's own reason for keeping its plan that goes into the rationale. */
	static final int KEPT_REASON_MAX_LENGTH = 400;

	private MatchPlanFeedbackText()
	{
	}

	/**
	 * @param pFeedback the figures of the simulation of the plan the feedback was about
	 * @param pOutcomeKind a {@link MatchPlanLlmPass.Session.FeedbackOutcome} kind
	 */
	static String line(final Locale pLocale, final SimulationFeedback.Result pFeedback, final String pOutcomeKind)
	{
		final String outcome = WizardMessages.get(pLocale, MatchPlanLlmPass.Session.FeedbackOutcome.REVISED.equals(pOutcomeKind) ? "matchfb.outcome.revised"
			: MatchPlanLlmPass.Session.FeedbackOutcome.KEPT.equals(pOutcomeKind) ? "matchfb.outcome.kept" : "matchfb.outcome.noAnswer");
		final SimulationFeedback.SetCount headline = pFeedback.headlineSet();
		if (headline == null)
		{
			return WizardMessages.get(pLocale, "matchfb.rationale.clean", Integer.valueOf(pFeedback.engine.pairs), Integer.valueOf(pFeedback.pivots), outcome);
		}
		return WizardMessages.plural(pLocale, headline.engine.suspected, "matchfb.rationale.suspects.one", "matchfb.rationale.suspects.many", headline.set.fieldsText(),
			Integer.valueOf(pFeedback.engine.pairs), Integer.valueOf(pFeedback.pivots), outcome);
	}

	/** The rationale of a plan after the feedback: the line, the model's reason when it kept the plan, then the plan's own rationale. */
	static String rationale(final Locale pLocale, final SimulationFeedback.Result pFeedback, final String pOutcomeKind, final String pKeptReason, final String pPlanRationale)
	{
		final StringBuilder out = new StringBuilder(MatchPlanFeedbackText.line(pLocale, pFeedback, pOutcomeKind));
		if (pKeptReason != null && !pKeptReason.trim().isEmpty())
		{
			out.append(' ').append(WizardMessages.get(pLocale, "matchfb.rationale.keptReason", TextLimits.truncate(pKeptReason.trim().replaceAll("\\s+", " "), KEPT_REASON_MAX_LENGTH)));
		}
		if (pPlanRationale != null && !pPlanRationale.trim().isEmpty())
		{
			out.append(' ').append(pPlanRationale.trim());
		}
		return out.toString();
	}
}
