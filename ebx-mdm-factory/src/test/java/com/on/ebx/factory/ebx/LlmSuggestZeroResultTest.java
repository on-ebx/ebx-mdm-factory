package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like the rest of this package's tests. Exercises {@link LlmSuggestZeroResult#reason} - the
 * "name why" logic extracted from {@code ProjectWizardUserService#zeroResultReason} for the job-engine lot C3
 * (2026-09-24) migration of {@code QUALITY_SUGGEST_LLM}/{@code CAPABILITY_SUGGEST_LLM}. Pure ({@link
 * com.fasterxml.jackson.databind.JsonNode} in, {@link String} out - no EBX type), so it needs no live
 * repository.
 */
public final class LlmSuggestZeroResultTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		LlmSuggestZeroResultTest.noEntitiesArrayReturnsNull();
		LlmSuggestZeroResultTest.emptyEntitiesArrayReturnsNull();
		LlmSuggestZeroResultTest.firstNonEmptyNoteWins();
		LlmSuggestZeroResultTest.emptyNotesFallsBackToError();
		LlmSuggestZeroResultTest.entityWithNeitherIsSkippedInFavourOfTheNextOne();
		LlmSuggestZeroResultTest.everyEntityBlankReturnsNull();

		System.out.println("LlmSuggestZeroResultTest OK");
	}

	private static void noEntitiesArrayReturnsNull()
	{
		final ObjectNode result = LlmSuggestZeroResultTest.MAPPER.createObjectNode();
		LlmSuggestZeroResultTest.check("no entities field at all -> null", LlmSuggestZeroResult.reason(result) == null);
	}

	private static void emptyEntitiesArrayReturnsNull()
	{
		final ObjectNode result = LlmSuggestZeroResultTest.MAPPER.createObjectNode();
		result.putArray("entities");
		LlmSuggestZeroResultTest.check("empty entities array -> null (nothing to explain)", LlmSuggestZeroResult.reason(result) == null);
	}

	private static void firstNonEmptyNoteWins()
	{
		final ObjectNode result = LlmSuggestZeroResultTest.MAPPER.createObjectNode();
		final ArrayNode entities = result.putArray("entities");
		final ObjectNode entity = entities.addObject();
		entity.putArray("notes").add("no eligible field on this entity");
		entity.put("error", "should never be read - notes[0] already answered");
		LlmSuggestZeroResultTest.check("notes[0] wins over error", "no eligible field on this entity".equals(LlmSuggestZeroResult.reason(result)));
	}

	private static void emptyNotesFallsBackToError()
	{
		final ObjectNode result = LlmSuggestZeroResultTest.MAPPER.createObjectNode();
		final ArrayNode entities = result.putArray("entities");
		final ObjectNode entity = entities.addObject();
		entity.putArray("notes"); // present but empty
		entity.put("error", "the run refused every tool call");
		LlmSuggestZeroResultTest.check("empty notes array falls back to error", "the run refused every tool call".equals(LlmSuggestZeroResult.reason(result)));
	}

	private static void entityWithNeitherIsSkippedInFavourOfTheNextOne()
	{
		final ObjectNode result = LlmSuggestZeroResultTest.MAPPER.createObjectNode();
		final ArrayNode entities = result.putArray("entities");
		entities.addObject(); // no notes, no error - contributes nothing
		final ObjectNode second = entities.addObject();
		second.put("error", "the second entity's own reason");
		LlmSuggestZeroResultTest.check("a blank first entity is skipped, not treated as the answer",
			"the second entity's own reason".equals(LlmSuggestZeroResult.reason(result)));
	}

	private static void everyEntityBlankReturnsNull()
	{
		final ObjectNode result = LlmSuggestZeroResultTest.MAPPER.createObjectNode();
		final ArrayNode entities = result.putArray("entities");
		entities.addObject();
		entities.addObject();
		LlmSuggestZeroResultTest.check("every entity blank -> null, never an empty string", LlmSuggestZeroResult.reason(result) == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
