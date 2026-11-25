package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LlmChat;
import com.on.ebx.agent.LlmClient;

/**
 * JUnit-free: run the main, a failure throws. Lot M5 (2026-10-18, {@code docs/MATCHING.md} &sect;60): the conversation the agent loop resends at every step is trimmed
 * of the arguments of its older REFUSED calls, nothing else.
 */
public final class ConversationCompactorTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		ConversationCompactorTest.olderRefusedCallsLoseTheirArgumentsAndKeepTheirRefusal();
		ConversationCompactorTest.theLastRoundsAcceptedAnswersAndThePromptAreUntouched();
		ConversationCompactorTest.theInputListIsNeverModifiedAndTheChatPassesTheAnswerThrough();
		ConversationCompactorTest.theGrowthIsLinearInTheRefusalsNotTheirArguments();
		System.out.println("ConversationCompactorTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static String big(final int pChars)
	{
		final StringBuilder out = new StringBuilder();
		while (out.length() < pChars)
		{
			out.append("[field=mobileE164 algorithm=exact],");
		}
		return out.toString();
	}

	/** One round: the model calls propose_match_plan with a large tree, the tool answers {@code pAnswer} (error or not). */
	private static void round(final List<LlmClient.Message> pConversation, final int pIndex, final String pAnswer, final boolean pError) throws Exception
	{
		final String id = "call" + pIndex;
		pConversation.add(LlmClient.Message.assistant("attempt " + pIndex,
			Collections.singletonList(new LlmClient.ToolCall(id, "propose_match_plan", ConversationCompactorTest.MAPPER.readTree("{\"entity\":\"Personne\",\"nodes\":\"" + ConversationCompactorTest.big(3000) + "\"}")))));
		pConversation.add(LlmClient.Message.toolResults(Collections.singletonList(new LlmClient.ToolResult(id, pAnswer, pError))));
	}

	private static List<LlmClient.Message> conversation(final int pRounds, final boolean pLastAccepted) throws Exception
	{
		final List<LlmClient.Message> messages = new ArrayList<LlmClient.Message>();
		messages.add(LlmClient.Message.user("the prompt"));
		for (int i = 1; i <= pRounds; i++)
		{
			final boolean accepted = pLastAccepted && i == pRounds;
			ConversationCompactorTest.round(messages, i, accepted ? "recorded" : "refusal " + i + ": 'mobileE164' is not near-unique enough", !accepted);
		}
		return messages;
	}

	private static int size(final List<LlmClient.Message> pMessages)
	{
		int total = 0;
		for (final LlmClient.Message message : pMessages)
		{
			total += message.getText() == null ? 0 : message.getText().length();
			if (message.getToolCalls() != null)
			{
				for (final LlmClient.ToolCall call : message.getToolCalls())
				{
					total += call.arguments.toString().length();
				}
			}
			if (message.getResults() != null)
			{
				for (final LlmClient.ToolResult result : message.getResults())
				{
					total += result.text.length();
				}
			}
		}
		return total;
	}

	private static void olderRefusedCallsLoseTheirArgumentsAndKeepTheirRefusal() throws Exception
	{
		final List<LlmClient.Message> messages = ConversationCompactorTest.conversation(8, false);
		final List<LlmClient.Message> compact = ConversationCompactor.compact(messages);
		check(compact.size() == messages.size(), "same number of messages");
		// rounds 1..5 are older than the last three
		for (int round = 1; round <= 5; round++)
		{
			final LlmClient.ToolCall call = compact.get(round * 2 - 1).getToolCalls().get(0);
			check(call.arguments.size() == 1 && call.arguments.has("omitted") && call.id.equals("call" + round) && "propose_match_plan".equals(call.name), "round " + round + ": arguments dropped, id and name kept");
			final LlmClient.ToolResult result = compact.get(round * 2).getResults().get(0);
			check(result.isError && result.toolCallId.equals("call" + round) && result.text.startsWith("refusal " + round), "round " + round + ": the refusal is kept, paired with its call");
			check(compact.get(round * 2 - 1).getText().equals("attempt " + round), "round " + round + ": the model's own words are kept");
		}
		// the last three rounds are exactly as they were
		for (int index = 11; index <= 16; index++)
		{
			check(compact.get(index) == messages.get(index), "message " + index + " untouched");
		}
		check(compact.get(0) == messages.get(0), "the prompt is untouched");
	}

	private static void theLastRoundsAcceptedAnswersAndThePromptAreUntouched() throws Exception
	{
		final List<LlmClient.Message> messages = new ArrayList<LlmClient.Message>();
		messages.add(LlmClient.Message.user("the prompt"));
		ConversationCompactorTest.round(messages, 1, "recorded", false);
		for (int i = 2; i <= 6; i++)
		{
			ConversationCompactorTest.round(messages, i, "refusal " + i, true);
		}
		final List<LlmClient.Message> compact = ConversationCompactor.compact(messages);
		check(compact.get(1) == messages.get(1) && compact.get(2) == messages.get(2), "an accepted call keeps its arguments, however old");
		check(compact.get(3).getToolCalls().get(0).arguments.has("omitted"), "an older refused one does not");
		final String longRefusal = ConversationCompactorTest.big(5000);
		final List<LlmClient.Message> longer = ConversationCompactorTest.conversation(5, false);
		longer.set(2, LlmClient.Message.toolResults(Collections.singletonList(new LlmClient.ToolResult("call1", longRefusal, true))));
		final String cut = ConversationCompactor.compact(longer).get(2).getResults().get(0).text;
		check(cut.length() <= ConversationCompactor.MAX_RESULT_CHARS + 10 && cut.endsWith("[...]"), "a very long refusal is cut with an ellipsis: " + cut.length());
		final List<LlmClient.Message> three = ConversationCompactorTest.conversation(3, false);
		final List<LlmClient.Message> same = ConversationCompactor.compact(three);
		for (int i = 0; i < three.size(); i++)
		{
			check(same.get(i) == three.get(i), "three rounds or fewer: message " + i + " is the same object");
		}
	}

	private static void theInputListIsNeverModifiedAndTheChatPassesTheAnswerThrough() throws Exception
	{
		final List<LlmClient.Message> messages = ConversationCompactorTest.conversation(7, false);
		final List<LlmClient.Message> before = new ArrayList<LlmClient.Message>(messages);
		final LlmClient.Turn expected = new LlmClient.Turn("ok", Collections.<LlmClient.ToolCall>emptyList(), LlmClient.StopReason.END, 123, 45);
		final List<List<LlmClient.Message>> seen = new ArrayList<List<LlmClient.Message>>();
		final LlmChat inner = new LlmChat()
		{
			@Override
			public LlmClient.Turn send(final String pSystem, final List<LlmClient.ToolSpec> pTools, final List<LlmClient.Message> pMessages)
			{
				seen.add(pMessages);
				return expected;
			}
		};
		final LlmClient.Turn turn = new ConversationCompactor.Chat(inner).send("system", Collections.<LlmClient.ToolSpec>emptyList(), messages);
		check(turn == expected && turn.inputTokens == 123, "the inner chat's answer and its token counts pass through");
		check(messages.equals(before) && messages.get(1).getToolCalls().get(0).arguments.size() == 2, "the caller's list and messages are not modified");
		check(seen.size() == 1 && seen.get(0).get(1).getToolCalls().get(0).arguments.has("omitted"), "the inner chat got the compacted conversation");
	}

	private static void theGrowthIsLinearInTheRefusalsNotTheirArguments() throws Exception
	{
		// what the loop pays: the sum, over the steps, of the size of the conversation sent at that step
		long raw = 0;
		long compacted = 0;
		for (int steps = 1; steps <= 12; steps++)
		{
			final List<LlmClient.Message> conversation = ConversationCompactorTest.conversation(steps, false);
			raw += ConversationCompactorTest.size(conversation);
			compacted += ConversationCompactorTest.size(ConversationCompactor.compact(conversation));
		}
		check(compacted * 2 < raw, "twelve refused rounds cost less than half once the older arguments are dropped: " + compacted + " vs " + raw);
	}
}
