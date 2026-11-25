package com.on.ebx.factory.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link AgentDecisions} (agentic framework lot, 2026-10-30). */
public final class AgentDecisionsTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		AgentDecisionsTest.checkPlainObject();
		AgentDecisionsTest.checkFencedAndArray();
		AgentDecisionsTest.checkBadAnswers();
		AgentDecisionsTest.checkConfidence();
		System.out.println("AgentDecisionsTest OK");
	}

	private static void checkPlainObject()
	{
		final AgentDecisions.Parsed parsed = AgentDecisions.parse("{\"decisions\": [{\"target\": \"I1\", \"action\": \"merge\", \"confidence\": 92, \"rationale\": \"same VAT\"},"
			+ " {\"target\": \"I2\", \"action\": \"CORRECT\", \"arguments\": {\"value\": \"FR\"}, \"confidence\": 0.7}, {\"action\": \"MERGE\"}, {\"target\": \"I3\", \"action\": \"abstain\"}]}");
		AgentDecisionsTest.check("no problem", parsed.problem == null);
		AgentDecisionsTest.check("three kept, one dropped", parsed.decisions.size() == 3 && parsed.dropped == 1);
		AgentDecisionsTest.check("action upper-cased", "MERGE".equals(parsed.decisions.get(0).action));
		AgentDecisionsTest.check("rationale kept", "same VAT".equals(parsed.decisions.get(0).rationale));
		AgentDecisionsTest.check("arguments kept", "FR".equals(parsed.decisions.get(1).arguments.path("value").asText()));
		AgentDecisionsTest.check("fraction read as percent", parsed.decisions.get(1).confidence == 70);
		AgentDecisionsTest.check("abstain recognised", parsed.decisions.get(2).abstains() && !parsed.decisions.get(0).abstains());
		AgentDecisionsTest.check("lowest confidence", parsed.lowestConfidence() != null);
		AgentDecisionsTest.check("toJson carries the target", "I1".equals(parsed.decisions.get(0).toJson().path("target").asText()));
	}

	private static void checkFencedAndArray()
	{
		final AgentDecisions.Parsed fenced = AgentDecisions.parse("Here you go:\n```json\n{\"decisions\": [{\"target\": \"I1\", \"action\": \"DISTINCT\", \"confidence\": \"85%\"}]}\n```");
		AgentDecisionsTest.check("fenced answer read", fenced.problem == null && fenced.decisions.size() == 1);
		AgentDecisionsTest.check("percent text read", fenced.decisions.get(0).confidence == 85);
		final AgentDecisions.Parsed array = AgentDecisions.parse("[{\"target\": \"I9\", \"action\": \"MERGE\", \"confidence\": 100}]");
		AgentDecisionsTest.check("bare array read", array.decisions.size() == 1 && "I9".equals(array.decisions.get(0).target));
	}

	private static void checkBadAnswers()
	{
		AgentDecisionsTest.check("empty answer", AgentDecisions.parse("  ").problem != null);
		AgentDecisionsTest.check("null answer", AgentDecisions.parse(null).problem != null);
		AgentDecisionsTest.check("prose answer", AgentDecisions.parse("I cannot decide.").problem != null);
		AgentDecisionsTest.check("no decisions list", AgentDecisions.parse("{\"answer\": 1}").problem != null);
		AgentDecisionsTest.check("bad answers give no decision", AgentDecisions.parse("I cannot decide.").decisions.isEmpty());
	}

	private static void checkConfidence() throws Exception
	{
		AgentDecisionsTest.check("null is 0", AgentDecisions.confidence(null) == 0);
		AgentDecisionsTest.check("number", AgentDecisions.confidence(AgentDecisionsTest.MAPPER.readTree("77")) == 77);
		AgentDecisionsTest.check("over 100 clamped", AgentDecisions.confidence(AgentDecisionsTest.MAPPER.readTree("140")) == 100);
		AgentDecisionsTest.check("negative clamped", AgentDecisions.confidence(AgentDecisionsTest.MAPPER.readTree("-5")) == 0);
		AgentDecisionsTest.check("junk text is 0", AgentDecisions.confidence(AgentDecisionsTest.MAPPER.readTree("\"high\"")) == 0);
		AgentDecisionsTest.check("1 stays 1", AgentDecisions.confidence(AgentDecisionsTest.MAPPER.readTree("1")) == 1);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
