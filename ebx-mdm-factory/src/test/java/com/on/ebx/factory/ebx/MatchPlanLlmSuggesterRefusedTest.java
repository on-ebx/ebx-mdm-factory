package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws. fix/match-plan-requirements-length (2026-10-12): a plan refused for not fitting its field is reported by
 * the pass, and "Propose another" puts the previous plan back (docs/MATCHING.md &sect;10.2 case c) with that reason.
 */
public final class MatchPlanLlmSuggesterRefusedTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		final ObjectNode outcome = MatchPlanLlmSuggesterRefusedTest.MAPPER.createObjectNode();
		outcome.put("status", "DONE");
		outcome.putArray("written");
		outcome.putArray("refreshed");
		outcome.putArray("repeated");
		outcome.putArray("notes");

		// nothing refused: empty text, the ordinary miss reason is unchanged
		outcome.putObject("refused");
		MatchPlanLlmSuggesterRefusedTest.check("no refusal reads empty", MatchPlanLlmSuggester.refusedText(outcome.path("refused")).isEmpty());
		MatchPlanLlmSuggesterRefusedTest.check("a missing refused node reads empty", MatchPlanLlmSuggester.refusedText(outcome.path("absent")).isEmpty());
		MatchPlanLlmSuggesterRefusedTest.check("null reads empty", MatchPlanLlmSuggester.refusedText(null).isEmpty());
		MatchPlanLlmSuggesterRefusedTest.check("ordinary miss reason", MatchingSuggestLlmRun.reproposeMissReason(outcome, "Person")
			.startsWith("the language model made no proposal for this entity this round"));

		// one entity refused
		final String reason = "plan of 120000 characters exceeds the field limit of 100000; the model proposed 14 nodes";
		((ObjectNode) outcome.path("refused")).put("Person", reason);
		MatchPlanLlmSuggesterRefusedTest.check("refused text names entity and reason", MatchPlanLlmSuggester.refusedText(outcome.path("refused")).equals("Person: " + reason));
		final String miss = MatchingSuggestLlmRun.reproposeMissReason(outcome, "Person");
		MatchPlanLlmSuggesterRefusedTest.check("the revert reason carries the refusal and says the previous plan is kept: " + miss,
			miss.contains(reason) && miss.contains("not stored") && miss.endsWith("The previous plan is kept."));
		MatchPlanLlmSuggesterRefusedTest.check("another entity's miss is unaffected", MatchingSuggestLlmRun.reproposeMissReason(outcome, "Address")
			.startsWith("the language model made no proposal"));

		// two entities
		((ObjectNode) outcome.path("refused")).put("Address", "plan of 100001 characters exceeds the field limit of 100000; the model proposed 3 nodes");
		final String both = MatchPlanLlmSuggester.refusedText(outcome.path("refused"));
		MatchPlanLlmSuggesterRefusedTest.check("two refusals joined: " + both, both.startsWith("Person: ") && both.contains("; Address: plan of 100001"));

		System.out.println("MatchPlanLlmSuggesterRefusedTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
