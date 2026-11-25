package com.on.ebx.factory.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.on.ebx.factory.agent.AgentPrompts.Sharing;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link AgentPrompts} (agentic framework lot, 2026-10-30). */
public final class AgentPromptsTest
{
	public static void main(final String[] pArgs)
	{
		AgentPromptsTest.checkShare();
		AgentPromptsTest.checkSystems();
		AgentPromptsTest.checkTask();
		System.out.println("AgentPromptsTest OK");
	}

	private static void checkShare()
	{
		AgentPromptsTest.check("empty stays empty", "".equals(AgentPrompts.share(Sharing.VALUES, true, "")) && "".equals(AgentPrompts.share(Sharing.VALUES, false, null)));
		AgentPromptsTest.check("sensitive is a star", "*".equals(AgentPrompts.share(Sharing.VALUES, true, "FR7630001007941234567890185")));
		AgentPromptsTest.check("NONE is a star", "*".equals(AgentPrompts.share(Sharing.NONE, false, "Paris")));
		final String shape = AgentPrompts.share(Sharing.SHAPES, false, "Paris 75001");
		AgentPromptsTest.check("SHAPES hides the value", !shape.isEmpty() && !shape.contains("Paris") && !shape.contains("75001"));
		AgentPromptsTest.check("VALUES shows the value", "Paris".equals(AgentPrompts.share(Sharing.VALUES, false, "Paris")));
		final StringBuilder longValue = new StringBuilder();
		for (int i = 0; i < 300; i++)
		{
			longValue.append('x');
		}
		AgentPromptsTest.check("VALUES cut to 200", AgentPrompts.share(Sharing.VALUES, false, longValue.toString()).length() == 200);
	}

	private static void checkSystems()
	{
		final String steward = AgentPrompts.stewardSystem("Merge duplicate suppliers", "Never merge across countries", Sharing.SHAPES);
		AgentPromptsTest.check("steward names its actions", steward.contains("MERGE") && steward.contains("DISTINCT") && steward.contains("ABSTAIN"));
		AgentPromptsTest.check("steward carries the purpose", steward.contains("Merge duplicate suppliers") && steward.contains("Never merge across countries"));
		AgentPromptsTest.check("steward says shapes", steward.contains("SHAPE"));
		AgentPromptsTest.check("answer format present", steward.contains(AgentPrompts.ANSWER_FORMAT));
		final String quality = AgentPrompts.qualitySystem(null, null, Sharing.NONE);
		AgentPromptsTest.check("quality names CORRECT", quality.contains("CORRECT") && quality.contains("ABSTAIN"));
		AgentPromptsTest.check("quality without values says so", quality.contains("without the value"));
		AgentPromptsTest.check("no purpose line when none", !quality.contains("Purpose of this agent"));
	}

	private static void checkTask()
	{
		final ArrayNode items = new ObjectMapper().createArrayNode();
		items.addObject().put("id", "I1");
		final String task = AgentPrompts.task("suspect pairs", items);
		AgentPromptsTest.check("task carries the items", task.contains("\"I1\""));
		AgentPromptsTest.check("null items are an empty list", AgentPrompts.task("x", null).contains("[]"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
