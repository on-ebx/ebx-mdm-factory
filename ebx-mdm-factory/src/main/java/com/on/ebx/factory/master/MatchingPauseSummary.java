package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lot MP (2026-10-04): the two sentences said about a master load that paused the add-on's matching ({@code ebx.MatchingLoadPause}), from the {@code matchingPause}
 * node of the {@code MASTER_LOAD} step's detail - pure, so the wording and the "when is it a warning" rule are unit-tested. {@link #warning} is what must not be
 * missed (a table left paused, a pause that could not be made, a catch-up that failed) and goes FIRST in the step's message; {@link #sentence} is the plain recap.
 * <p>
 * The node: {@code {trigger, pauseStartedAt, skipped?, tables:[{entity, table, action (PAUSED / ALREADY_PAUSED / NOT_ENABLED / STATUS_UNREADABLE /
 * PAUSE_FAILED), pausedByLoad, note?, resumed?, resumeError?, catchUp?:{status, mode, selected, runCount, error?, note?}}]}}.
 */
public final class MatchingPauseSummary
{
	private MatchingPauseSummary()
	{
	}

	/** Empty when there is nothing to warn about. */
	public static String warning(final JsonNode pPause)
	{
		if (pPause == null || pPause.isMissingNode() || pPause.isNull())
		{
			return "";
		}
		final List<String> parts = new ArrayList<String>();
		final String skipped = pPause.path("skipped").asText("");
		if (!skipped.isEmpty())
		{
			parts.add("matching could not be paused during the load (" + skipped + "): the add-on may have matched inline while it wrote");
		}
		for (final JsonNode table : pPause.path("tables"))
		{
			final String entity = table.path("entity").asText("?");
			if ("PAUSE_FAILED".equals(table.path("action").asText("")))
			{
				parts.add("matching of " + entity + " could not be paused (" + table.path("note").asText("no reason given") + ")");
			}
			if (!table.path("resumeError").asText("").isEmpty() || table.path("pausedByLoad").asBoolean(false) && !table.path("resumed").asBoolean(false))
			{
				parts.add("matching of " + entity + " is still PAUSED (" + table.path("resumeError").asText("the resume did not happen") + "); resume it with set_table_settings {\"table\":\""
					+ table.path("table").asText("") + "\",\"settings\":{\"pauseMatching\":false}} on the match-merge server (the module also lifts it at its next start)");
			}
			final JsonNode catchUp = table.path("catchUp");
			if ("FAILED".equals(catchUp.path("status").asText("")))
			{
				parts.add("the matching catch-up of " + entity + " failed (" + catchUp.path("error").asText("no reason given")
					+ "): the records written during the pause are not matched, run the matching on the whole table");
			}
		}
		return parts.isEmpty() ? "" : "WARNING: " + MatchingPauseSummary.join(parts) + ".";
	}

	/** Empty when the load paused nothing. */
	public static String sentence(final JsonNode pPause)
	{
		if (pPause == null || pPause.isMissingNode() || pPause.isNull())
		{
			return "";
		}
		final List<String> paused = new ArrayList<String>();
		final List<String> catchUps = new ArrayList<String>();
		boolean allResumed = true;
		for (final JsonNode table : pPause.path("tables"))
		{
			if (!table.path("pausedByLoad").asBoolean(false))
			{
				continue;
			}
			final String entity = table.path("entity").asText("?");
			paused.add(entity);
			allResumed &= table.path("resumed").asBoolean(false);
			final JsonNode catchUp = table.path("catchUp");
			if (catchUp.isMissingNode())
			{
				continue;
			}
			final String status = catchUp.path("status").asText("");
			final long selected = catchUp.path("selected").asLong(0L);
			if ("NOTHING_TO_MATCH".equals(status))
			{
				catchUps.add(entity + ": no record to match afterwards");
			}
			else if ("DONE".equals(status))
			{
				catchUps.add(entity + ": " + MatchingPauseSummary.number(selected) + " record(s) matched afterwards ("
					+ ("WHOLE_TABLE".equals(catchUp.path("mode").asText("")) ? "one run on the whole table, too many records touched to enumerate"
						: catchUp.path("runCount").asInt(0) + " run(s) on explicit keys")
					+ ")");
			}
			else if ("SKIPPED".equals(status))
			{
				catchUps.add(entity + ": catch-up not run (" + catchUp.path("reason").asText("") + ")");
			}
			else if ("STOPPED".equals(status))
			{
				catchUps.add(entity + ": catch-up stopped");
			}
		}
		if (paused.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder("Matching was paused on " + MatchingPauseSummary.join(paused) + " while the master loaded"
			+ (allResumed ? " and resumed" : " (see the warning)") + ".");
		if (!catchUps.isEmpty())
		{
			out.append(" Catch-up: ").append(MatchingPauseSummary.join(catchUps)).append('.');
		}
		return out.toString();
	}

	private static String number(final long pValue)
	{
		return String.format(java.util.Locale.ROOT, "%,d", Long.valueOf(pValue));
	}

	private static String join(final List<String> pParts)
	{
		final StringBuilder out = new StringBuilder();
		for (final String part : pParts)
		{
			if (out.length() > 0)
			{
				out.append("; ");
			}
			out.append(part);
		}
		return out.toString();
	}
}
