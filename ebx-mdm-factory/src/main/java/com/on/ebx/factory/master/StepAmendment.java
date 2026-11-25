package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot MP (2026-10-04): what changes in an ALREADY ENDED {@code PipelineStep} row when something worth saying happens after its work - the matching resumed and
 * caught up once the master load had ended its {@code MASTER_LOAD} step. Pure: the JSON merge and the message composition, so that {@code
 * ebx.PipelineSteps.amendEnded} only writes the result.
 */
public final class StepAmendment
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private StepAmendment()
	{
	}

	/**
	 * {@code pDetailJson} with {@code pKey} set to {@code pValue}. A detail that is empty, not JSON (a truncated one) or not an object is replaced by an object
	 * holding only the key: the amendment must reach the person even when the earlier detail cannot be kept.
	 */
	public static String mergeDetail(final String pDetailJson, final String pKey, final JsonNode pValue)
	{
		ObjectNode root = null;
		if (pDetailJson != null && !pDetailJson.trim().isEmpty())
		{
			try
			{
				final JsonNode parsed = StepAmendment.MAPPER.readTree(pDetailJson);
				if (parsed != null && parsed.isObject())
				{
					root = (ObjectNode) parsed;
				}
			}
			catch (final java.io.IOException ex)
			{
				root = null;
			}
		}
		if (root == null)
		{
			root = StepAmendment.MAPPER.createObjectNode();
		}
		root.set(pKey, pValue);
		return root.toString();
	}

	/**
	 * {@code pPrefix} (a warning, first so it is the first thing read), the message the step already had, then {@code pSuffix}, within {@code pMax} characters:
	 * when it does not fit, the existing message is the part shortened - the prefix and the suffix are what this amendment exists to say.
	 */
	public static String composeMessage(final String pPrefix, final String pExisting, final String pSuffix, final int pMax)
	{
		final String prefix = pPrefix == null ? "" : pPrefix.trim();
		final String existing = pExisting == null ? "" : pExisting.trim();
		final String suffix = pSuffix == null ? "" : pSuffix.trim();
		final String full = StepAmendment.join(prefix, existing, suffix);
		if (full.length() <= pMax)
		{
			return full;
		}
		final int fixed = (prefix.isEmpty() ? 0 : prefix.length() + 1) + (suffix.isEmpty() ? 0 : suffix.length() + 1);
		final int room = pMax - fixed - 3;
		if (room >= 0 && !existing.isEmpty())
		{
			return StepAmendment.join(prefix, existing.substring(0, Math.min(existing.length(), room)) + "...", suffix);
		}
		final String fixedOnly = StepAmendment.join(prefix, "", suffix);
		return fixedOnly.substring(0, Math.max(0, Math.min(pMax, fixedOnly.length())));
	}

	/** The non-empty parts, separated by one space. */
	private static String join(final String... pParts)
	{
		final StringBuilder out = new StringBuilder();
		for (final String part : pParts)
		{
			if (part != null && !part.isEmpty())
			{
				if (out.length() > 0)
				{
					out.append(' ');
				}
				out.append(part);
			}
		}
		return out.toString();
	}
}
