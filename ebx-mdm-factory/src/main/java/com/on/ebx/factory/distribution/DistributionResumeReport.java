package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What {@code ebx.DistributionPause#resume} answers, read for failures. A load's own outcome must not be masked by a distribution hiccup, but the hiccup must
 * not be silent either: a target that was not set back to ARMED stays PAUSED (it sends nothing until somebody re-arms it), and a catch-up send that failed
 * leaves the records written during the load unsent. Pure, so the wording and the "what is a failure" rule are unit-tested.
 * <p>
 * The node: {@code {targets:[{target, error?, catchUp?:{status: DONE / FAILED, message?}}]}}.
 */
public final class DistributionResumeReport
{
	private DistributionResumeReport()
	{
	}

	/** One sentence per target that did not come back cleanly; empty when every target was re-armed and caught up. */
	public static List<String> failures(final JsonNode pResume)
	{
		final List<String> out = new ArrayList<String>();
		if (pResume == null)
		{
			return out;
		}
		for (final JsonNode target : pResume.path("targets"))
		{
			final String id = target.path("target").asText("?");
			final String error = target.path("error").asText("");
			if (!error.isEmpty())
			{
				out.add("distribution target " + id + " could not be re-armed and may still be PAUSED (" + error + ")");
				continue;
			}
			final JsonNode catchUp = target.path("catchUp");
			if (!catchUp.isMissingNode() && !"DONE".equals(catchUp.path("status").asText("DONE")))
			{
				out.add("distribution target " + id + " was re-armed but its catch-up send failed (" + catchUp.path("message").asText("no reason given")
					+ "): the records written during the load are not sent yet");
			}
		}
		return out;
	}

	/** The warning put first in the step's message, empty when there is nothing to say. */
	public static String warning(final JsonNode pResume)
	{
		final List<String> failures = DistributionResumeReport.failures(pResume);
		if (failures.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder("WARNING: ");
		for (int i = 0; i < failures.size(); i++)
		{
			out.append(i == 0 ? "" : "; ").append(failures.get(i));
		}
		return out.append('.').toString();
	}
}
