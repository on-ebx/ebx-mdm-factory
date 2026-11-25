package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Validation desk lot L3b (2026-10-22): what the language model ANSWERED when the desk asked it to review the {@link AttentionRules#SAME_CONSTANT} mappings of a business object
 * ({@code STAGING_ANOMALY_REVIEW}), per mapping: it proposed another constant, or it kept the one the mapping writes (it called no tool for that source). Without this the callout
 * kept offering "Ask for a correction" for a source the model had judged right, and a person would ask again for nothing.
 * <p>
 * The record lives in the job row's own {@code params} JSON (no XSD change): the job writes {@code "answers":[{"m":"<mapping id>","a":"proposed"|"kept","n":"<note>"}]} into it when
 * it ends, and the desk reads it back from the latest DONE job of that kind for that object. A mapping id that is not in the list was never submitted (or the mapping is a newer one):
 * it still needs asking. Pure: JSON text in, plain values out.
 */
public final class ModelAnswers
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String KEY = "answers";
	public static final String PROPOSED = "proposed";
	public static final String KEPT = "kept";
	/** The job's params column holds at most this many characters ({@code FactoryJob.params}). */
	static final int PARAMS_MAX = 4000;
	static final int NOTE_MAX = 240;

	private ModelAnswers()
	{
	}

	/** One submitted mapping and what the model did with it. */
	public static final class Answer
	{
		public final String mappingId;
		public final boolean kept;
		/** What the model said, bounded; "" when nothing. */
		public final String note;

		public Answer(final String pMappingId, final boolean pKept, final String pNote)
		{
			this.mappingId = pMappingId;
			this.kept = pKept;
			this.note = pNote == null ? "" : pNote;
		}
	}

	/**
	 * The answers of one run: every submitted mapping is {@code proposed} when the model created an alternative for it, else {@code kept}. {@code pNote} (the model's own closing
	 * words, or null) is attached to the kept ones only.
	 */
	public static List<Answer> answers(final Collection<String> pSubmittedMappingIds, final Collection<String> pProposedMappingIds, final String pNote)
	{
		final List<Answer> out = new ArrayList<Answer>();
		final String note = ModelAnswers.compact(pNote);
		for (final String id : pSubmittedMappingIds)
		{
			final boolean proposed = pProposedMappingIds.contains(id);
			out.add(new Answer(id, !proposed, proposed ? "" : note));
		}
		return out;
	}

	/** One line, blanks collapsed, cut at {@link #NOTE_MAX}; "" for null. */
	static String compact(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		final String one = pText.replaceAll("\\s+", " ").trim();
		return one.length() <= ModelAnswers.NOTE_MAX ? one : one.substring(0, ModelAnswers.NOTE_MAX - 1).trim() + "…";
	}

	/** {@code pParamsJson} (the job's start parameters) with the answers added; the notes are dropped, then the list cut, if the result would not fit the column. */
	public static String withAnswers(final String pParamsJson, final List<Answer> pAnswers)
	{
		try
		{
			final JsonNode parsed = pParamsJson == null || pParamsJson.isEmpty() ? null : ModelAnswers.MAPPER.readTree(pParamsJson);
			final ObjectNode base = parsed instanceof ObjectNode ? (ObjectNode) parsed : ModelAnswers.MAPPER.createObjectNode();
			for (final boolean withNotes : new boolean[] { true, false })
			{
				final ObjectNode copy = base.deepCopy();
				final ArrayNode list = copy.putArray(ModelAnswers.KEY);
				for (final Answer answer : pAnswers)
				{
					final ObjectNode item = list.addObject();
					item.put("m", answer.mappingId);
					item.put("a", answer.kept ? ModelAnswers.KEPT : ModelAnswers.PROPOSED);
					if (withNotes && !answer.note.isEmpty())
					{
						item.put("n", answer.note);
					}
				}
				final String text = ModelAnswers.MAPPER.writeValueAsString(copy);
				if (text.length() <= ModelAnswers.PARAMS_MAX)
				{
					return text;
				}
			}
			return ModelAnswers.MAPPER.writeValueAsString(base); // too many to store: the desk then asks again, which is safe
		}
		catch (final java.io.IOException ex)
		{
			return pParamsJson;
		}
	}

	/** The answers stored in a job's params, by mapping id; empty when none (or not JSON). */
	public static Map<String, Answer> parse(final String pParamsJson)
	{
		final Map<String, Answer> out = new LinkedHashMap<String, Answer>();
		if (pParamsJson == null || pParamsJson.isEmpty())
		{
			return out;
		}
		try
		{
			for (final JsonNode item : ModelAnswers.MAPPER.readTree(pParamsJson).path(ModelAnswers.KEY))
			{
				final String id = item.path("m").asText("");
				if (!id.isEmpty())
				{
					out.put(id, new Answer(id, ModelAnswers.KEPT.equals(item.path("a").asText("")), item.path("n").asText("")));
				}
			}
		}
		catch (final java.io.IOException ex)
		{
			// not ours: no answers
		}
		return out;
	}

	/** True when at least one of the flagged mappings was never submitted to the model (it is not in {@code pAnswers}), so asking still makes sense. */
	public static boolean needsAsk(final Collection<String> pFlaggedMappingIds, final Map<String, Answer> pAnswers)
	{
		for (final String id : pFlaggedMappingIds)
		{
			if (pAnswers == null || !pAnswers.containsKey(id))
			{
				return true;
			}
		}
		return false;
	}
}
