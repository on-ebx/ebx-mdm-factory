package com.on.ebx.factory.wizard;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.landing.KeyTexts;
import com.on.ebx.factory.landing.RunTexts;

/**
 * Run panel i18n (2026-10-17): the text a screen shows for what the landing RUN wrote (a cell's summary or activity, a journal line, the job's message, the reason of a
 * failed table). What the Factory itself built ({@link RunTexts}) is worded in the viewer's language, whether it carries its code marker (written since this lot) or is
 * the bare English of an older run; a refusal it raised with a message code is worded from that code; anything else (a connector's error, an EBX exception, the language
 * model's words) is free text and comes back as it is, without any marker. Pure, EBX-free. The wizard's script never words anything itself: the jobs route
 * hands it the progress already worded ({@link #localizeProgressJson}).
 */
public final class RunPanelText
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private RunPanelText()
	{
	}

	/** The patterns of a language: the shared module bundle, null for a key it lacks. */
	static RunTexts.Patterns patterns(final Locale pLocale)
	{
		return new RunTexts.Patterns()
		{
			@Override
			public String pattern(final String pKey)
			{
				final String text = WizardMessages.get(pLocale, pKey);
				return pKey.equals(text) ? null : text;
			}

			@Override
			public String foreign(final String pStored)
			{
				final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(pStored);
				if (decoded.key != null && decoded.key.startsWith(KeyTexts.P))
				{
					return KeyRationaleText.display(pStored, pLocale);
				}
				return decoded.sentence(pLocale);
			}
		};
	}

	/** A cell's summary or activity, a job's message, a failure's reason, in {@code pLocale}; empty for null or empty. */
	public static String display(final String pStored, final Locale pLocale)
	{
		return RunTexts.display(pStored, RunPanelText.patterns(pLocale == null ? Locale.ENGLISH : pLocale));
	}

	/** One line of the run's journal in {@code pLocale}. */
	public static String event(final String pStored, final Locale pLocale)
	{
		return RunTexts.displayEvent(pStored, RunPanelText.patterns(pLocale == null ? Locale.ENGLISH : pLocale));
	}

	/**
	 * The progress grid of a landing run ({@link com.on.ebx.factory.landing.LandingRunProgress#toJson}), or the progress of a staging proposal / build ({@code stages} at the top
	 * level), with every text the run wrote worded in {@code pLocale}: the summary and the activity of each cell or stage, the lines of the journal. What the wizard's script repaints the live panel from, so it shows the words of the page it was rendered with. A text that
	 * is not valid JSON, or not a grid, comes back with its code markers removed.
	 */
	public static String localizeProgressJson(final String pJson, final Locale pLocale)
	{
		if (pJson == null || pJson.isEmpty())
		{
			return pJson;
		}
		try
		{
			final JsonNode root = RunPanelText.MAPPER.readTree(pJson);
			if (!root.isObject())
			{
				return EngineMessageCodec.stripMarkers(pJson);
			}
			for (final JsonNode event : root.path("events"))
			{
				if (event.isObject() && event.hasNonNull("text"))
				{
					((ObjectNode) event).put("text", RunPanelText.event(event.path("text").asText(), pLocale));
				}
			}
			// staging screens (2026-10-17): the staging proposal / build (and master build) progress has stages at the top level - summary and activity worded the same way
			for (final JsonNode stage : root.path("stages"))
			{
				if (!stage.isObject())
				{
					continue;
				}
				for (final String field : new String[] { "summary", "activity" })
				{
					if (stage.hasNonNull(field))
					{
						((ObjectNode) stage).put(field, RunPanelText.display(stage.path(field).asText(), pLocale));
					}
				}
			}
			for (final JsonNode table : root.path("tables"))
			{
				for (final JsonNode cell : table.path("cells"))
				{
					if (!cell.isObject())
					{
						continue;
					}
					for (final String field : new String[] { "summary", "activity" })
					{
						if (cell.hasNonNull(field))
						{
							((ObjectNode) cell).put(field, RunPanelText.display(cell.path(field).asText(), pLocale));
						}
					}
				}
			}
			return root.toString();
		}
		catch (final java.io.IOException ex)
		{
			return EngineMessageCodec.stripMarkers(pJson);
		}
	}
}
