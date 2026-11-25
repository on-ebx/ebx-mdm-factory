package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Landing test fixes (2026-10-17, defect 7): the person's last answer to "may the language model look at 25 rows of my data", read from what the
 * last landing run already stored in its job parameters ({@code useLlm}, and {@code llmSourceIds} when the walkthrough asked application by
 * application). The re-run form's checkbox opens on it; nothing was ever answered = unchecked. No new field, no XSD change. Pure: no EBX class.
 */
public final class LlmConsent
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private LlmConsent()
	{
	}

	/**
	 * Whether the last run let the language model in at all: {@code useLlm} true and, when the run carries the list of applications that said yes, at least one.
	 * Blank, unreadable or absent parameters (no run yet) answer false.
	 */
	public static boolean lastChoice(final String pJobParams)
	{
		final JsonNode params = LlmConsent.parse(pJobParams);
		if (params == null || !params.path("useLlm").asBoolean(false))
		{
			return false;
		}
		return !params.has("llmSourceIds") || params.path("llmSourceIds").size() > 0;
	}

	/** The applications (ProjectSource ids) that said yes in the last run; empty when none, or when the run did not ask application by application. */
	public static List<String> lastSources(final String pJobParams)
	{
		final List<String> out = new ArrayList<String>();
		final JsonNode params = LlmConsent.parse(pJobParams);
		if (params != null && params.path("useLlm").asBoolean(false))
		{
			for (final JsonNode id : params.path("llmSourceIds"))
			{
				if (!id.asText("").isEmpty())
				{
					out.add(id.asText(""));
				}
			}
		}
		return out;
	}

	private static JsonNode parse(final String pJobParams)
	{
		if (pJobParams == null || pJobParams.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return LlmConsent.MAPPER.readTree(pJobParams);
		}
		catch (final java.io.IOException ex)
		{
			return null;
		}
	}
}
