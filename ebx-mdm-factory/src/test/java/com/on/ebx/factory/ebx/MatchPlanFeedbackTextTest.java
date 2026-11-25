package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.master.MatchPlanLlmPass;
import com.on.ebx.factory.master.SimulationFeedback;

/**
 * JUnit-free: run the main, a failure throws. Lot F1 (2026-10-19, {@code docs/MATCHING.md} &sect;70): the ONE line the simulation feedback adds to the top of a plan's rationale (step 6), in English
 * and French, for each answer of the model, and the gate that decides whether the proposal job runs the turn at all.
 */
public final class MatchPlanFeedbackTextTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String KEPT = MatchPlanLlmPass.Session.FeedbackOutcome.KEPT;
	private static final String REVISED = MatchPlanLlmPass.Session.FeedbackOutcome.REVISED;
	private static final String NO_ANSWER = MatchPlanLlmPass.Session.FeedbackOutcome.NO_ANSWER;

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanFeedbackTextTest.theLineInEnglish();
		MatchPlanFeedbackTextTest.theLineInFrench();
		MatchPlanFeedbackTextTest.aSingularPairIsInTheSingular();
		MatchPlanFeedbackTextTest.whenNoSuspectedPairAgreesOnAKeySetTheLineSaysSo();
		MatchPlanFeedbackTextTest.theRationaleStartsWithTheLineThenTheModelsReasonThenThePlansOwn();
		MatchPlanFeedbackTextTest.theBundlesAreAlignedAndFreeOfRawNonAscii();
		System.out.println("MatchPlanFeedbackTextTest OK");
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	/** 703 pairs, 341 SUSPECTED that agree on e-mail + phone (the SAFE pair): the shape of the live run 6, as the unit test of the class under it measures it. */
	private static SimulationFeedback.Result feedback(final int pSuspectedOnTheSafePair, final int pPairs)
	{
		try
		{
			final ObjectNode plan = (ObjectNode) MatchPlanFeedbackTextTest.MAPPER.readTree("{\"decisionTree\":{\"root\":\"N1\",\"nodes\":["
				+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"emailKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"},"
				+ "{\"field\":\"mobileE164\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}");
			final List<SimulationFeedback.Pair> pairs = new ArrayList<SimulationFeedback.Pair>();
			final Map<String, String> same = new LinkedHashMap<String, String>();
			same.put("emailKey", "k");
			same.put("mobileE164", "p");
			final Map<String, String> other = new LinkedHashMap<String, String>();
			other.put("emailKey", "z");
			for (int i = 0; i < pPairs; i++)
			{
				final boolean agree = i < pSuspectedOnTheSafePair;
				pairs.add(new SimulationFeedback.Pair(SimulationFeedback.ORIGIN_ENGINE, agree ? "SUSPECTED" : "NOT_MATCHED", same, agree ? same : other, null));
			}
			return SimulationFeedback.measure(plan, pairs, 20);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static void theLineInEnglish()
	{
		final SimulationFeedback.Result result = MatchPlanFeedbackTextTest.feedback(341, 703);
		check("kept: " + MatchPlanFeedbackText.line(Locale.ENGLISH, result, KEPT),
			"Simulation: 341 pairs identical on emailKey + mobileE164 left as suspects (703 pairs evaluated, 20 pivots) — plan kept by the model.".equals(MatchPlanFeedbackText.line(Locale.ENGLISH, result, KEPT)));
		check("revised says the figures are those of the previous proposal", MatchPlanFeedbackText.line(Locale.ENGLISH, result, REVISED).endsWith("plan revised by the model (these figures are those of the previous proposal)."));
		check("no answer", MatchPlanFeedbackText.line(Locale.ENGLISH, result, NO_ANSWER).endsWith("plan kept (the model gave no answer)."));
	}

	private static void theLineInFrench()
	{
		final SimulationFeedback.Result result = MatchPlanFeedbackTextTest.feedback(341, 703);
		final String kept = MatchPlanFeedbackText.line(Locale.FRENCH, result, KEPT);
		check("kept: " + kept, "Simulation : 341 paires identiques sur emailKey + mobileE164 laissées en suspect (703 paires évaluées, 20 pivots) — plan conservé par le modèle.".equals(kept));
		check("revised: " + MatchPlanFeedbackText.line(Locale.FRENCH, result, REVISED), MatchPlanFeedbackText.line(Locale.FRENCH, result, REVISED).contains("plan revu par le modèle"));
		check("no answer", MatchPlanFeedbackText.line(Locale.FRENCH, result, NO_ANSWER).contains("pas de réponse du modèle"));
	}

	private static void aSingularPairIsInTheSingular()
	{
		final SimulationFeedback.Result result = MatchPlanFeedbackTextTest.feedback(1, 5);
		check("English: " + MatchPlanFeedbackText.line(Locale.ENGLISH, result, KEPT), MatchPlanFeedbackText.line(Locale.ENGLISH, result, KEPT).startsWith("Simulation: 1 pair identical on emailKey + mobileE164 left as suspect ("));
		check("French: " + MatchPlanFeedbackText.line(Locale.FRENCH, result, KEPT), MatchPlanFeedbackText.line(Locale.FRENCH, result, KEPT).startsWith("Simulation : 1 paire identique sur emailKey + mobileE164 laissée en suspect ("));
	}

	private static void whenNoSuspectedPairAgreesOnAKeySetTheLineSaysSo()
	{
		final SimulationFeedback.Result result = MatchPlanFeedbackTextTest.feedback(0, 12);
		check("English: " + MatchPlanFeedbackText.line(Locale.ENGLISH, result, KEPT),
			"Simulation: 12 pairs evaluated, 20 pivots, no pair left as suspect agrees on every field of a key set of the plan — plan kept by the model.".equals(MatchPlanFeedbackText.line(Locale.ENGLISH, result, KEPT)));
		check("French: " + MatchPlanFeedbackText.line(Locale.FRENCH, result, KEPT), MatchPlanFeedbackText.line(Locale.FRENCH, result, KEPT).contains("aucune paire laissée en suspect n'est identique sur tous les champs d'un jeu de clés du plan"));
	}

	private static void theRationaleStartsWithTheLineThenTheModelsReasonThenThePlansOwn()
	{
		final SimulationFeedback.Result result = MatchPlanFeedbackTextTest.feedback(341, 703);
		final String text = MatchPlanFeedbackText.rationale(Locale.ENGLISH, result, KEPT, "the pairs that share a phone are\n\n  family members", "Keys first, weaker paths after.");
		check("the line first: " + text, text.startsWith("Simulation: 341 pairs identical on emailKey + mobileE164 left as suspects (703 pairs evaluated, 20 pivots) — plan kept by the model. "));
		check("then the model's reason on one line", text.contains("Reason given by the model: the pairs that share a phone are family members "));
		check("and the plan's own rationale last: " + text, text.endsWith("Keys first, weaker paths after."));
		final String longReason = MatchPlanFeedbackTextTest.repeat("x", 900);
		check("the model's reason is cut", MatchPlanFeedbackText.rationale(Locale.ENGLISH, result, KEPT, longReason, "r").length() < 800);
		check("without a reason of the model, none is announced", !MatchPlanFeedbackText.rationale(Locale.ENGLISH, result, NO_ANSWER, null, "r").contains("Reason given"));
		check("the same in French", MatchPlanFeedbackText.rationale(Locale.FRENCH, result, KEPT, "pourquoi", "r").contains("Raison donnée par le modèle : pourquoi"));
	}

	private static String repeat(final String pText, final int pTimes)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pTimes; i++)
		{
			out.append(pText);
		}
		return out.toString();
	}

	/** Every key exists in both languages, and neither bundle holds a raw non-ASCII character (the module's bundles are plain ASCII with \\uXXXX). */
	private static void theBundlesAreAlignedAndFreeOfRawNonAscii()
	{
		final String[] keys = { "matchfb.rationale.suspects.one", "matchfb.rationale.suspects.many", "matchfb.rationale.clean", "matchfb.rationale.keptReason", "matchfb.outcome.kept",
			"matchfb.outcome.revised", "matchfb.outcome.noAnswer" };
		for (final String key : keys)
		{
			final java.util.ResourceBundle en = java.util.ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", Locale.ENGLISH, MatchPlanFeedbackText.class.getClassLoader());
			final java.util.ResourceBundle fr = java.util.ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", Locale.FRENCH, MatchPlanFeedbackText.class.getClassLoader());
			check(key + " in English", en.containsKey(key) && !en.getString(key).isEmpty());
			check(key + " in French", fr.containsKey(key) && !fr.getString(key).isEmpty() && !fr.getString(key).equals(en.getString(key)));
		}
	}
}
