package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Job-engine lot C3 (2026-09-24): the "name why" helper {@code ProjectWizardUserService#zeroResultReason} used
 * for {@code QUALITY_SUGGEST_LLM}/{@code CAPABILITY_SUGGEST_LLM} (long-passes-visible lot, 2026-09-23 -
 * coordinator finding: a zero-result notice used to say nothing about why) - extracted here, unchanged, so
 * {@link QualitySuggestLlmRun}/{@link CapabilitySuggestLlmRun} (which run in the {@code ebx.*} package, not
 * {@code ebx.ui}, and cannot see that method's {@code private}) share the exact same logic instead of each
 * carrying its own copy. Pure ({@link JsonNode} in, {@link String} out - no EBX type), so it is unit-tested
 * directly without a live repository ({@code LlmSuggestZeroResultTest}).
 */
final class LlmSuggestZeroResult
{
	private LlmSuggestZeroResult()
	{
	}

	/**
	 * The first useful reason a zero-result LLM pass carries in its own {@code entities} array ({@link
	 * QualityLlmSuggester}/{@link CapabilityLlmSuggester} both use the same shape) - the first entity's own
	 * {@code notes[0]}, or, failing that, its {@code error}; null when the pass considered no entity at all
	 * (nothing to explain). Never throws on an unexpected shape - a missing field is simply not found, the
	 * caller falls back to a plain zero notice.
	 */
	static String reason(final ObjectNode pResult)
	{
		final JsonNode entities = pResult.path("entities");
		if (!entities.isArray())
		{
			return null;
		}
		for (final JsonNode entity : entities)
		{
			final JsonNode notes = entity.path("notes");
			if (notes.isArray() && notes.size() > 0)
			{
				final String note = notes.get(0).asText("");
				if (!note.isEmpty())
				{
					return note;
				}
			}
			final String error = entity.path("error").asText("");
			if (!error.isEmpty())
			{
				return error;
			}
		}
		return null;
	}
}
