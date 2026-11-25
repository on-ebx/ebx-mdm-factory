package com.on.ebx.factory.stagingdesk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.on.ebx.factory.capability.MissingInputs;

/**
 * Validation desk lot L5 (docs/STAGING-DESK.md): the data behind the "an input no field holds" cards of the "Cannot run as proposed" group. Plain data, read by {@code
 * ebx.MissingInputReader}: the waiting needs ({@link MissingInputs.Need}, measured on the suggestions' bindings) and the computed fields the language model proposed for
 * them ({@link Field}, found by the marker its suggestion's rationale carries). {@link #cards} puts the two together. Pure, unit-tested ({@code DeskInputTest}).
 */
public final class DeskInput
{
	/** The action the "Propose a field for it" button posts - handled by the desk service, which queues {@code ebx.MissingInputRun}. */
	public static final String ACTION_PROPOSE = "PROPOSE_INPUT_FIELD";

	/** Every need of the project's suggestions that are not decided: the reader filters nothing by the desk (the desk keeps those it lists as cannot-run). */
	public final List<MissingInputs.Need> needs = new ArrayList<MissingInputs.Need>();
	/** The computed fields proposed for some of them. */
	public final List<Field> fields = new ArrayList<Field>();
	/** A job that proposes such a field is queued or running for the project: the button waits. */
	public boolean jobRunning;

	/** A computed field proposed for waiting suggestions (a staging attribute with derivation SCRIPT and its SCRIPT_DERIVATION suggestion). */
	public static final class Field
	{
		public final String attributeId;
		public final String name;
		public final String label;
		public final String dataType;
		/** The attribute's status: {@code PROPOSED} (decidable here), {@code ACCEPTED}/{@code APPLIED}. A rejected field is not read. */
		public final String status;
		public final String suggestionId;
		public final String entityName;
		/** {@code targetLibrary} and {@code targetFunction} of the suggestion. */
		public final String library;
		public final String function;
		public final String binding;
		/** What it computes, in the model's words, without the marker. */
		public final String rationale;
		public final Integer confidence;
		public final List<MissingInputs.Supply> supplies;

		public Field(final String pAttributeId, final String pName, final String pLabel, final String pDataType, final String pStatus, final String pSuggestionId,
			final String pEntityName, final String pLibrary, final String pFunction, final String pBinding, final String pRationale, final Integer pConfidence,
			final List<MissingInputs.Supply> pSupplies)
		{
			this.attributeId = pAttributeId == null ? "" : pAttributeId;
			this.name = pName == null ? "" : pName;
			this.label = pLabel == null ? "" : pLabel;
			this.dataType = pDataType == null ? "" : pDataType;
			this.status = pStatus == null ? "" : pStatus;
			this.suggestionId = pSuggestionId == null ? "" : pSuggestionId;
			this.entityName = pEntityName == null ? "" : pEntityName;
			this.library = pLibrary == null ? "" : pLibrary;
			this.function = pFunction == null ? "" : pFunction;
			this.binding = pBinding == null ? "" : pBinding;
			this.rationale = pRationale == null ? "" : pRationale;
			this.confidence = pConfidence;
			this.supplies = pSupplies == null ? new ArrayList<MissingInputs.Supply>() : pSupplies;
		}

		/** True when the field supplies at least one of these needs. */
		boolean suppliesAny(final List<MissingInputs.Need> pNeeds)
		{
			return this.suppliedAmong(pNeeds) > 0;
		}

		/** How many distinct suggestions among these needs the field supplies (what "accepting binds the N waiting suggestions" counts). */
		public int suppliedAmong(final List<MissingInputs.Need> pNeeds)
		{
			final Set<String> ids = new LinkedHashSet<String>();
			for (final MissingInputs.Need need : pNeeds)
			{
				for (final MissingInputs.Supply supply : this.supplies)
				{
					if (supply.matches(need))
					{
						ids.add(need.suggestionId);
					}
				}
			}
			return ids.size();
		}

		public boolean decidable()
		{
			return "PROPOSED".equals(this.status);
		}
	}

	/** One card of the desk: the waiting suggestions that need the same thing, and the fields proposed for it. */
	public static final class Card
	{
		public final String key;
		/** The model's sentence of the first need of the card. */
		public final String sentence;
		public final List<MissingInputs.Need> needs = new ArrayList<MissingInputs.Need>();
		public final List<Field> fields = new ArrayList<Field>();

		Card(final String pKey, final String pSentence)
		{
			this.key = pKey;
			this.sentence = pSentence;
		}

		/** The distinct suggestions waiting on this card. */
		public int suggestions()
		{
			final Set<String> ids = new LinkedHashSet<String>();
			for (final MissingInputs.Need need : this.needs)
			{
				ids.add(need.suggestionId);
			}
			return ids.size();
		}

		/** True when the waiting suggestions do not all carry the same sentence: the card then lists each with its own words. */
		public boolean mixed()
		{
			final Set<String> sentences = new LinkedHashSet<String>();
			for (final MissingInputs.Need need : this.needs)
			{
				sentences.add(need.sentence.trim().toLowerCase(java.util.Locale.ROOT));
			}
			return sentences.size() > 1;
		}

		/** The distinct names to show for the waiting suggestions, in order. */
		public List<String> labels()
		{
			final Set<String> out = new LinkedHashSet<String>();
			for (final MissingInputs.Need need : this.needs)
			{
				out.add(need.label);
			}
			return new ArrayList<String>(out);
		}
	}

	/**
	 * The cards of the waiting suggestions {@code pWaitingIds} (the ones the "Cannot run" group lists as needing an input). Rule (live fix, round 2): the MODEL says which
	 * needs are the same, no Java test of similarity. Before any field is proposed for a business object, ALL its waiting needs are ONE card (each suggestion keeps its own
	 * sentence). Once fields exist, cards follow the fields: the needs a field supplies, and the needs that share a field, are one card; the needs no field covers stay in ONE
	 * remaining card of their object. A field attaches to the card holding the needs it supplies. Order: first appearance of the objects, then of the needs.
	 */
	public List<Card> cards(final Set<String> pWaitingIds)
	{
		final java.util.Map<String, List<MissingInputs.Need>> byObject = new java.util.LinkedHashMap<String, List<MissingInputs.Need>>();
		for (final MissingInputs.Need need : this.needs)
		{
			if (pWaitingIds.contains(need.suggestionId))
			{
				List<MissingInputs.Need> list = byObject.get(need.entityName);
				if (list == null)
				{
					list = new ArrayList<MissingInputs.Need>();
					byObject.put(need.entityName, list);
				}
				list.add(need);
			}
		}
		final List<Card> out = new ArrayList<Card>();
		for (final java.util.Map.Entry<String, List<MissingInputs.Need>> entry : byObject.entrySet())
		{
			final List<MissingInputs.Need> needs = entry.getValue();
			// union of the needs a field spans
			final int[] parent = new int[needs.size()];
			final boolean[] covered = new boolean[needs.size()];
			for (int i = 0; i < parent.length; i++)
			{
				parent[i] = i;
			}
			final List<Field> objectFields = new ArrayList<Field>();
			for (final Field field : this.fields)
			{
				int first = -1;
				for (int i = 0; i < needs.size(); i++)
				{
					if (!field.suppliesAny(java.util.Collections.singletonList(needs.get(i))))
					{
						continue;
					}
					covered[i] = true;
					if (first < 0)
					{
						first = i;
						objectFields.add(field);
					}
					else
					{
						parent[DeskInput.root(parent, i)] = DeskInput.root(parent, first);
					}
				}
			}
			final java.util.Map<Integer, Card> byRoot = new java.util.LinkedHashMap<Integer, Card>();
			Card rest = null;
			for (int i = 0; i < needs.size(); i++)
			{
				final MissingInputs.Need need = needs.get(i);
				Card card;
				if (covered[i])
				{
					final Integer root = Integer.valueOf(DeskInput.root(parent, i));
					card = byRoot.get(root);
					if (card == null)
					{
						card = new Card("obj:" + entry.getKey() + ":" + need.pair(), need.sentence);
						byRoot.put(root, card);
						out.add(card);
					}
				}
				else
				{
					if (rest == null)
					{
						rest = new Card("obj:" + entry.getKey(), need.sentence);
						out.add(rest);
					}
					card = rest;
				}
				card.needs.add(need);
			}
			for (final Field field : objectFields)
			{
				for (final Card card : out)
				{
					if (card.key.startsWith("obj:" + entry.getKey()) && field.suppliesAny(card.needs))
					{
						card.fields.add(field);
						break;
					}
				}
			}
		}
		return out;
	}

	private static int root(final int[] pParent, final int pIndex)
	{
		int node = pIndex;
		while (pParent[node] != node)
		{
			node = pParent[node];
		}
		return node;
	}
}
