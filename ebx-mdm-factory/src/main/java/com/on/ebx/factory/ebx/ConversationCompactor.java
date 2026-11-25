package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LlmChat;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.agent.LlmException;

/**
 * Lot M5 (2026-10-18, {@code docs/MATCHING.md} &sect;60): what the agent loop resends at every step, trimmed.
 * <p>
 * {@code AgentLoop} keeps the whole conversation and sends it again at each model round trip, and its token budget adds up the input tokens the provider reports for EACH
 * round trip ({@code totalIn += turn.inputTokens}, javap on {@code on-ebx-agent.jar}): a run costs roughly the sum of the conversation's length at every step, so it grows with the
 * square of the number of steps. Live (run 433, matching proposals): 23 steps, 329,919 tokens, where the first prompt alone was about 8,000. What dominates the growth is the arguments
 * of the refused calls: every {@code propose_match_plan} that failed carries the whole tree the model sent, and the refusal that explains the failure is a few hundred characters.
 * <p>
 * The compaction keeps the refusal and drops the refused call's arguments, for every round older than the last {@link #KEEP_RECENT_ROUNDS}: the model reads what was wrong in the
 * refusal and has just written (and still sees) its latest attempts. Nothing else is touched: the prompt, the model's own words, the answers that were accepted, and the pairing of
 * call ids and results (a provider refuses a result without its call). A refusal longer than {@link #MAX_RESULT_CHARS} is cut with an ellipsis.
 * <p>
 * Pure (no EBX class); {@link Chat} applies it in front of any {@link LlmChat}.
 */
public final class ConversationCompactor
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The most recent tool rounds (one model turn and its tool results) kept exactly as they happened. */
	public static final int KEEP_RECENT_ROUNDS = 3;
	/** Longest refusal text kept for a compacted round. */
	public static final int MAX_RESULT_CHARS = 1200;
	/** What replaces the arguments of a refused call. */
	static final String OMITTED = "arguments of an earlier refused call, dropped from the conversation to save tokens; the refusal below says what was wrong";

	private ConversationCompactor()
	{
	}

	/** A new list: the messages as they are, except the refused calls of the rounds older than the last {@link #KEEP_RECENT_ROUNDS}. The argument list is never modified. */
	public static List<LlmClient.Message> compact(final List<LlmClient.Message> pMessages)
	{
		return ConversationCompactor.compact(pMessages, ConversationCompactor.KEEP_RECENT_ROUNDS);
	}

	static List<LlmClient.Message> compact(final List<LlmClient.Message> pMessages, final int pKeepRecentRounds)
	{
		final int size = pMessages.size();
		// the index of every assistant message that called tools = one round
		final List<Integer> rounds = new ArrayList<Integer>();
		for (int i = 0; i < size; i++)
		{
			final List<LlmClient.ToolCall> calls = pMessages.get(i).getToolCalls();
			if (calls != null && !calls.isEmpty())
			{
				rounds.add(Integer.valueOf(i));
			}
		}
		final int compactable = rounds.size() - Math.max(0, pKeepRecentRounds);
		if (compactable <= 0)
		{
			return new ArrayList<LlmClient.Message>(pMessages);
		}
		final List<LlmClient.Message> out = new ArrayList<LlmClient.Message>(pMessages);
		for (int r = 0; r < compactable; r++)
		{
			final int callIndex = rounds.get(r).intValue();
			final int resultIndex = callIndex + 1;
			if (resultIndex >= size)
			{
				continue;
			}
			final List<LlmClient.ToolResult> results = pMessages.get(resultIndex).getResults();
			if (results == null || results.isEmpty())
			{
				continue;
			}
			final List<String> refusedIds = new ArrayList<String>();
			final List<LlmClient.ToolResult> newResults = new ArrayList<LlmClient.ToolResult>();
			boolean changed = false;
			for (final LlmClient.ToolResult result : results)
			{
				if (result.isError)
				{
					refusedIds.add(result.toolCallId);
					final String text = ConversationCompactor.cut(result.text);
					if (!text.equals(result.text))
					{
						changed = true;
					}
					newResults.add(new LlmClient.ToolResult(result.toolCallId, text, true));
				}
				else
				{
					newResults.add(result);
				}
			}
			if (refusedIds.isEmpty())
			{
				continue;
			}
			final LlmClient.Message call = pMessages.get(callIndex);
			final List<LlmClient.ToolCall> newCalls = new ArrayList<LlmClient.ToolCall>();
			for (final LlmClient.ToolCall toolCall : call.getToolCalls())
			{
				if (refusedIds.contains(toolCall.id) && !ConversationCompactor.isOmitted(toolCall.arguments))
				{
					final ObjectNode marker = ConversationCompactor.MAPPER.createObjectNode();
					marker.put("omitted", ConversationCompactor.OMITTED);
					newCalls.add(new LlmClient.ToolCall(toolCall.id, toolCall.name, marker));
					changed = true;
				}
				else
				{
					newCalls.add(toolCall);
				}
			}
			if (changed)
			{
				out.set(callIndex, LlmClient.Message.assistant(call.getText(), newCalls));
				out.set(resultIndex, LlmClient.Message.toolResults(newResults));
			}
		}
		return out;
	}

	private static boolean isOmitted(final JsonNode pArguments)
	{
		return pArguments != null && pArguments.isObject() && pArguments.size() == 1 && pArguments.has("omitted");
	}

	private static String cut(final String pText)
	{
		if (pText == null || pText.length() <= ConversationCompactor.MAX_RESULT_CHARS)
		{
			return pText == null ? "" : pText;
		}
		return pText.substring(0, ConversationCompactor.MAX_RESULT_CHARS) + " [...]";
	}

	/** An {@link LlmChat} that compacts the conversation before each send; the answer (and its token counts) is the inner chat's own. */
	public static final class Chat implements LlmChat
	{
		private final LlmChat inner;

		public Chat(final LlmChat pInner)
		{
			this.inner = pInner;
		}

		@Override
		public LlmClient.Turn send(final String pSystem, final List<LlmClient.ToolSpec> pTools, final List<LlmClient.Message> pMessages) throws LlmException
		{
			return this.inner.send(pSystem, pTools, ConversationCompactor.compact(pMessages));
		}
	}
}
