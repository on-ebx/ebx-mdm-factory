package com.on.ebx.factory.capability;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.PromptData;

/**
 * Lot M10c (2026-10-18): the one closed question behind {@link NameKeyRule} - "which two attributes of this entity are the first name and the last name of a person,
 * if any". The Factory stores no such role on its attributes, and a role cannot be read from a value shape (a city, a first name and a civility look alike), nor from a
 * name matched in Java (the module is language-neutral by design). The model is the only reader of meaning the Factory has; what changes here is the SHAPE of the
 * question: a single tool, {@value #TOOL}, whose answer can only be attributes of the entity, asked once per entity the rule can serve, instead of one proposal among a
 * score of others inside the open-ended capability run (the variance that lot M10c exists to remove). Everything that follows the answer - whether a key exists, what it
 * is called, how it is bound, how confident it is - is {@link NameKeyRule}, deterministic.
 * <p>
 * Pure (prompts, schema, a {@link Session} that validates and records the answer): {@code ebx.CapabilityLlmSuggester} runs it through {@code AgentRuntime}. The shapes
 * the prompt shows follow the data policy exactly as in the capability pass (they come from the same {@link CapabilityLlmPass.AttributeInfo}); no real value is ever sent.
 */
public final class NamePartsPass
{
	public static final String TOOL = "declare_name_parts";

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int REFUSAL_STOP_THRESHOLD = 3;
	private static final int REASON_MAX_LENGTH = 300;

	private NamePartsPass()
	{
	}

	private static JsonNode schema(final Set<String> pCandidateNames)
	{
		final ObjectNode schema = NamePartsPass.MAPPER.createObjectNode();
		schema.put("type", "object");
		final ObjectNode properties = schema.putObject("properties");
		properties.putObject("hasNamePair").put("type", "boolean").put("description",
			"true when the entity has one attribute holding a person's first (given) name AND another holding the same person's last (family) name, as separate values; "
				+ "false otherwise (a single full-name attribute, a company name, no person name at all).");
		final ObjectNode first = properties.putObject("firstName");
		first.put("type", "string").put("description", "The attribute holding the first (given) name. Omit when hasNamePair is false.");
		final ObjectNode last = properties.putObject("lastName");
		last.put("type", "string").put("description", "The attribute holding the last (family) name. Omit when hasNamePair is false.");
		final ArrayNode names = NamePartsPass.MAPPER.createArrayNode();
		for (final String name : pCandidateNames)
		{
			names.add(name);
		}
		first.set("enum", names.deepCopy());
		last.set("enum", names);
		properties.putObject("reason").put("type", "string").put("description", "One short sentence: what in the attributes shows it.");
		schema.putArray("required").add("hasNamePair");
		return schema;
	}

	/** Names no domain and no language: the attributes are described to the model as DATA, the answer is a pick among them. */
	public static String systemPrompt()
	{
		return "You are the attribute-role classifier of the ON EBX MDM Factory. For ONE staging entity you decide whether it describes people and, if it does, which of its "
			+ "attributes hold a person's FIRST (given) name and LAST (family) name as two separate values. The Factory builds an order-independent name key from that pair "
			+ "so that records written by different systems can be compared; it cannot read meaning itself, only you can.\n\n"
			+ "Decide from the attribute names, their labels, types, lengths, profiles and value shapes - whatever language they are written in. Rules: the two attributes "
			+ "must be among the candidates listed; they must be two different attributes; never choose an identifier, a title or civility, a nickname, a company or "
			+ "organisation name, a place or a computed key. When the entity has a single full-name attribute, a company name, or no person name at all, answer "
			+ "hasNamePair=false: a wrong pair is worse than none, because the key would be built from it.\n\n"
			+ "Answer with exactly ONE call of the tool " + NamePartsPass.TOOL + ". A plain text answer records nothing. If a call is refused, correct it on the next call; "
			+ "do not repeat the same call." + PromptData.SYSTEM_NOTICE;
	}

	/**
	 * @param pCandidates the attributes the pair may be chosen among ({@link NameKeyRule#candidates}).
	 * @param pLabels the human label of each attribute by name (data, may be empty).
	 */
	public static String userPrompt(final CapabilityLlmPass.EntityInfo pEntity, final List<CapabilityLlmPass.AttributeInfo> pCandidates, final Map<String, String> pLabels)
	{
		final StringBuilder body = new StringBuilder();
		for (final CapabilityLlmPass.AttributeInfo attribute : pCandidates)
		{
			body.append("- ").append(attribute.name);
			final String label = pLabels == null ? null : pLabels.get(attribute.name);
			if (label != null && !label.trim().isEmpty() && !label.trim().equals(attribute.name))
			{
				body.append(" (label: ").append(label.trim()).append(')');
			}
			body.append(" [").append(attribute.dataType == null ? "STRING" : attribute.dataType);
			if (attribute.maxLength != null)
			{
				body.append(", maxLength ").append(attribute.maxLength);
			}
			body.append("]\n");
			if (attribute.rows != null)
			{
				body.append("  Profile: ").append(attribute.rows).append(" row(s), fill rate ").append(attribute.fillRatePercent != null ? attribute.fillRatePercent + "%" : "unknown")
					.append(", distinct values ").append(attribute.distinctCount != null ? String.valueOf(attribute.distinctCount) : "unknown").append('\n');
				if (!attribute.shapes.isEmpty())
				{
					body.append("  Shapes (most frequent first): ");
					for (int i = 0; i < attribute.shapes.size(); i++)
					{
						if (i > 0)
						{
							body.append(", ");
						}
						body.append(attribute.shapes.get(i).shape).append(" (").append(attribute.shapes.get(i).count).append(')');
					}
					body.append('\n');
				}
			}
		}
		final StringBuilder out = new StringBuilder();
		out.append("Entity: ").append(pEntity.name).append(pEntity.label != null && !pEntity.label.isEmpty() ? " (" + pEntity.label + ")" : "").append('\n');
		out.append("\nCandidate attributes (names, labels and shapes come from the sources):\n");
		out.append(PromptData.fence("CANDIDATE ATTRIBUTES", body.toString()));
		out.append("\nCall ").append(NamePartsPass.TOOL).append(" once: the first-name attribute and the last-name attribute, or hasNamePair=false.\n");
		return out.toString();
	}

	/** Backs the one tool for one agent run over ONE entity. Not thread-safe. */
	public static final class Session
	{
		private final Set<String> candidateNames;
		private final Map<String, Integer> refusals = new LinkedHashMap<String, Integer>();
		private boolean answered;
		private boolean stopped;
		private NameKeyRule.NameParts parts;
		private String reason = "";
		private String refusal = "";

		public Session(final List<CapabilityLlmPass.AttributeInfo> pCandidates)
		{
			this.candidateNames = new LinkedHashSet<String>();
			for (final CapabilityLlmPass.AttributeInfo candidate : pCandidates)
			{
				this.candidateNames.add(candidate.name);
			}
		}

		public ToolSet.LocalGroup toolGroup()
		{
			final List<LocalTool> tools = new ArrayList<LocalTool>();
			tools.add(new LocalTool(NamePartsPass.TOOL, "Declare which attributes of the entity hold a person's first name and last name, or that there is no such pair.",
				NamePartsPass.schema(this.candidateNames), new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.declare(pArguments);
					}
				}));
			return new ToolSet.LocalGroup(tools);
		}

		LocalTool.Result declare(final JsonNode pArguments)
		{
			if (this.stopped)
			{
				return LocalTool.Result.error("The run is over: no more tool calls will be executed.");
			}
			if (this.answered)
			{
				return LocalTool.Result.ok("Already recorded. Nothing more to do: end the run.");
			}
			final JsonNode flag = pArguments.path("hasNamePair");
			if (!flag.isBoolean())
			{
				return this.refuse("hasNamePair", "'hasNamePair' must be true or false.");
			}
			final String reasonText = NamePartsPass.text(pArguments, "reason");
			this.reason = reasonText == null ? "" : reasonText.length() > NamePartsPass.REASON_MAX_LENGTH ? reasonText.substring(0, NamePartsPass.REASON_MAX_LENGTH) : reasonText;
			if (!flag.asBoolean())
			{
				this.answered = true;
				return LocalTool.Result.ok("Recorded: no first-name/last-name pair on this entity. End the run.");
			}
			final String first = NamePartsPass.text(pArguments, "firstName");
			final String last = NamePartsPass.text(pArguments, "lastName");
			final String key = first + "|" + last;
			if (first == null || last == null || first.trim().isEmpty() || last.trim().isEmpty())
			{
				return this.refuse(key, "hasNamePair is true: give both 'firstName' and 'lastName', each an attribute of the candidate list - or hasNamePair=false.");
			}
			if (!this.candidateNames.contains(first.trim()))
			{
				return this.refuse(key, "'" + first + "' is not one of the candidate attributes: " + this.candidateList() + ".");
			}
			if (!this.candidateNames.contains(last.trim()))
			{
				return this.refuse(key, "'" + last + "' is not one of the candidate attributes: " + this.candidateList() + ".");
			}
			if (first.trim().equals(last.trim()))
			{
				return this.refuse(key, "the first name and the last name must be two different attributes; if one attribute holds the whole name, answer hasNamePair=false.");
			}
			this.parts = new NameKeyRule.NameParts(first.trim(), last.trim());
			this.answered = true;
			return LocalTool.Result.ok("Recorded: first name = " + first.trim() + ", last name = " + last.trim() + ". End the run.");
		}

		private LocalTool.Result refuse(final String pKey, final String pMessage)
		{
			final Integer previous = this.refusals.get(pKey);
			final int count = (previous == null ? 0 : previous.intValue()) + 1;
			this.refusals.put(pKey, Integer.valueOf(count));
			this.refusal = pMessage;
			if (count >= NamePartsPass.REFUSAL_STOP_THRESHOLD)
			{
				this.stopped = true;
				return LocalTool.Result.error(pMessage + " This was refused " + count + " times in a row: the run ends now.");
			}
			return LocalTool.Result.error(pMessage);
		}

		private String candidateList()
		{
			return String.join(", ", this.candidateNames);
		}

		/** The declared pair; null when the model answered "no pair", never answered, or was stopped. */
		public NameKeyRule.NameParts parts()
		{
			return this.parts;
		}

		/** The model answered (a pair or "no pair"). */
		public boolean answered()
		{
			return this.answered;
		}

		/** The model's one-sentence reason (data, for the run's outcome only). */
		public String reason()
		{
			return this.reason;
		}

		/** The last refusal text, empty when the call was never refused. */
		public String lastRefusal()
		{
			return this.refusal;
		}

		public boolean stopped()
		{
			return this.stopped;
		}

		public Set<String> candidateNames()
		{
			return Collections.unmodifiableSet(this.candidateNames);
		}
	}

	private static String text(final JsonNode pArguments, final String pField)
	{
		final JsonNode value = pArguments.path(pField);
		return value.isTextual() ? value.asText() : null;
	}

	/** Parses a JSON object for tests and callers that hold the model's arguments as text. */
	static JsonNode parse(final String pJson) throws IOException
	{
		return NamePartsPass.MAPPER.readTree(pJson);
	}
}
