package com.on.ebx.factory.ebx;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LlmChat;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.agent.LlmException;

/**
 * An {@link LlmChat} that records the answers of the chat it wraps and replays them ({@link LlmRecordStore} says how, and which system properties switch it on - read at each
 * {@link #send}, so with both unset this class is a pass-through). It sits directly around the real chat, INSIDE the conversation compactor, so the request it digests is exactly
 * the one that would have been sent. Every turn of a multi-turn loop is its own request: replay goes turn by turn, and a turn the store does not hold goes to the model.
 * <p>
 * A replayed turn is reported to the trace: {@link AgentRuntime} calls {@link #takeReplayMarker} from the loop listener's {@code onTurn} and writes "replayed from &lt;file&gt;"
 * in the {@code arguments} of that {@code MODEL} {@code AgentStep} row.
 */
final class LlmReplayChat implements LlmChat
{
	private final LlmChat inner;
	private final String provider;
	private final String model;
	private final Map<String, Object> params;
	private volatile String replayMarker;
	private volatile String replayedModel;
	private volatile int replayed;
	private volatile int asked;
	private volatile int recorded;
	private volatile String replayDirUsed;

	/**
	 * @param pProvider the provider name (digested); @param pModel the model (digested); @param pParams the call's other settings that shape the answer (maxTokens,
	 * reasoningEffort...), digested too
	 */
	LlmReplayChat(final LlmChat pInner, final String pProvider, final String pModel, final Map<String, Object> pParams)
	{
		this.inner = pInner;
		this.provider = pProvider;
		this.model = pModel;
		this.params = pParams == null ? new LinkedHashMap<String, Object>() : pParams;
	}

	@Override
	public LlmClient.Turn send(final String pSystem, final List<LlmClient.ToolSpec> pTools, final List<LlmClient.Message> pMessages) throws LlmException
	{
		final File replay = LlmRecordStore.replayDir();
		final File record = LlmRecordStore.recordDir();
		if (replay == null && record == null)
		{
			this.asked++;
			return this.inner.send(pSystem, pTools, pMessages);
		}
		final ObjectNode request = LlmRecordStore.chatRequest(this.provider, this.model, this.params, pSystem, pTools, pMessages);
		final String digest = LlmRecordStore.digest(request);
		if (replay != null)
		{
			final LlmRecordStore.Hit hit = LlmRecordStore.lookup(replay, digest, LlmRecordStore.KIND_CHAT);
			if (hit != null)
			{
				this.replayMarker = hit.file;
				this.replayedModel = hit.model.isEmpty() ? null : hit.model;
				this.replayed++;
				this.replayDirUsed = replay.getPath();
				return LlmRecordStore.turnOf(hit.answer);
			}
		}
		this.asked++;
		final LlmClient.Turn turn = this.inner.send(pSystem, pTools, pMessages);
		if (record != null && LlmRecordStore.write(record, digest, LlmRecordStore.KIND_CHAT, this.provider, this.model, request, LlmRecordStore.turnJson(turn)) != null)
		{
			this.recorded++;
		}
		return turn;
	}

	/** The file the LAST turn was replayed from, once (then null until the next replayed turn); null when the last turn came from the model. Called after each turn by the loop listener. */
	String takeReplayMarker()
	{
		final String marker = this.replayMarker;
		this.replayMarker = null;
		return marker;
	}

	/** The model recorded with the last replayed answer; null when none was replayed (or the file did not name it). */
	String replayedModel()
	{
		return this.replayedModel;
	}

	int replayedTurns()
	{
		return this.replayed;
	}

	/** Turns that went to the model (recorded or not). */
	int askedTurns()
	{
		return this.asked;
	}

	int recordedTurns()
	{
		return this.recorded;
	}

	/** The sentence added to the run's outcome when turns were replayed or recorded; null when neither happened. */
	String summary()
	{
		if (this.replayed == 0 && this.recorded == 0)
		{
			return null;
		}
		final StringBuilder out = new StringBuilder("[LLM");
		if (this.replayed > 0)
		{
			out.append(" replay: ").append(this.replayed).append(" of ").append(this.replayed + this.asked).append(" turn(s) answered from ").append(this.replayDirUsed);
		}
		if (this.recorded > 0)
		{
			out.append(this.replayed > 0 ? ", " : " ").append("record: ").append(this.recorded).append(" turn(s) written");
		}
		return out.append(']').toString();
	}
}
