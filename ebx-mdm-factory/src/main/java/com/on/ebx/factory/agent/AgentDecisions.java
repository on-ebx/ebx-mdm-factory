package com.on.ebx.factory.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The answer of an agent, read (agentic framework lot, 2026-10-30). Pure. Every behaviour asks the model for the same JSON shape:
 *
 * <pre>
 * {"decisions": [{"target": "...", "action": "...", "arguments": {...}, "confidence": 0-100, "rationale": "..."}]}
 * </pre>
 *
 * A model that wraps the object in a Markdown fence or a sentence is tolerated (the first {@code {} to the last {@code }} is read); a bare array is read as
 * the decisions. A decision without a target or an action is dropped and counted ({@link Parsed#dropped}); a confidence outside 0..100 is clamped, a
 * missing or unreadable one reads as 0 (never acts alone). The action {@link #ABSTAIN} (or {@code UNSURE}) is an abstention.
 */
public final class AgentDecisions
{
	/** The action of an abstention. */
	public static final String ABSTAIN = "ABSTAIN";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One decision as the model returned it. */
	public static final class Decision
	{
		public final String target;
		public final String action;
		public final ObjectNode arguments;
		public final int confidence;
		public final String rationale;

		public Decision(final String pTarget, final String pAction, final ObjectNode pArguments, final int pConfidence, final String pRationale)
		{
			this.target = pTarget;
			this.action = pAction;
			this.arguments = pArguments == null ? AgentDecisions.MAPPER.createObjectNode() : pArguments;
			this.confidence = AgentPolicy.clamp(pConfidence);
			this.rationale = pRationale == null ? "" : pRationale;
		}

		/** Whether the agent abstained on this target. */
		public boolean abstains()
		{
			return AgentDecisions.ABSTAIN.equalsIgnoreCase(this.action) || "UNSURE".equalsIgnoreCase(this.action);
		}

		/** The decision as JSON (for {@code AgentRun.decision}). */
		public ObjectNode toJson()
		{
			final ObjectNode out = AgentDecisions.MAPPER.createObjectNode();
			out.put("target", this.target);
			out.put("action", this.action);
			out.set("arguments", this.arguments);
			out.put("confidence", this.confidence);
			out.put("rationale", this.rationale);
			return out;
		}
	}

	/** What {@link #parse} read. */
	public static final class Parsed
	{
		public final List<Decision> decisions;
		/** Entries left out: no target, no action, not an object. */
		public final int dropped;
		/** Null when the text held a readable answer; else why not. */
		public final String problem;

		Parsed(final List<Decision> pDecisions, final int pDropped, final String pProblem)
		{
			this.decisions = Collections.unmodifiableList(pDecisions);
			this.dropped = pDropped;
			this.problem = pProblem;
		}

		/** The lowest confidence among the decisions that are not abstentions, null when there is none. */
		public Integer lowestConfidence()
		{
			Integer lowest = null;
			for (final Decision decision : this.decisions)
			{
				if (!decision.abstains() && (lowest == null || decision.confidence < lowest.intValue()))
				{
					lowest = Integer.valueOf(decision.confidence);
				}
			}
			return lowest;
		}
	}

	private AgentDecisions()
	{
	}

	/** Reads the answer text of the model. Never throws: an unreadable answer gives no decision and a {@link Parsed#problem}. */
	public static Parsed parse(final String pText)
	{
		final List<Decision> out = new ArrayList<Decision>();
		if (pText == null || pText.trim().isEmpty())
		{
			return new Parsed(out, 0, "empty answer");
		}
		final JsonNode root = AgentDecisions.readJson(pText);
		if (root == null)
		{
			return new Parsed(out, 0, "the answer is not JSON");
		}
		final JsonNode list = root.isArray() ? root : root.path("decisions");
		if (!list.isArray())
		{
			return new Parsed(out, 0, "the answer has no \"decisions\" list");
		}
		int dropped = 0;
		for (final JsonNode item : list)
		{
			final String target = item.isObject() ? AgentDecisions.text(item.get("target")) : null;
			final String action = item.isObject() ? AgentDecisions.text(item.get("action")) : null;
			if (target == null || action == null)
			{
				dropped++;
				continue;
			}
			final JsonNode arguments = item.get("arguments");
			out.add(new Decision(target, action.toUpperCase(java.util.Locale.ROOT), arguments != null && arguments.isObject() ? (ObjectNode) arguments : null,
				AgentDecisions.confidence(item.get("confidence")), AgentDecisions.text(item.get("rationale"))));
		}
		return new Parsed(out, dropped, null);
	}

	/** Pure: the JSON object (or array) of a text, fence and prose around it tolerated; null when none can be read. */
	static JsonNode readJson(final String pText)
	{
		final String text = pText.trim();
		try
		{
			return AgentDecisions.MAPPER.readTree(text);
		}
		catch (final Exception ex)
		{
			// fall through: a fenced or wrapped answer
		}
		for (final char[] pair : new char[][] { { '{', '}' }, { '[', ']' } })
		{
			final int start = text.indexOf(pair[0]);
			final int end = text.lastIndexOf(pair[1]);
			if (start >= 0 && end > start)
			{
				try
				{
					return AgentDecisions.MAPPER.readTree(text.substring(start, end + 1));
				}
				catch (final Exception ex)
				{
					// try the other shape
				}
			}
		}
		return null;
	}

	/** A confidence: a number, or a numeric text, 0..1 read as a fraction; anything else 0. */
	static int confidence(final JsonNode pNode)
	{
		if (pNode == null || pNode.isNull())
		{
			return 0;
		}
		double value;
		if (pNode.isNumber())
		{
			value = pNode.asDouble();
		}
		else
		{
			try
			{
				value = Double.parseDouble(pNode.asText("").replace("%", "").trim());
			}
			catch (final NumberFormatException ex)
			{
				return 0;
			}
		}
		if (value > 0 && value < 1)
		{
			value = value * 100; // 0.85 from a model that answered a probability
		}
		return AgentPolicy.clamp((int) Math.round(value));
	}

	private static String text(final JsonNode pNode)
	{
		if (pNode == null || pNode.isNull() || pNode.isContainerNode())
		{
			return null;
		}
		final String value = pNode.asText("").trim();
		return value.isEmpty() ? null : value;
	}
}
