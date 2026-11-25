package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lot S10b (2026-10-19): the wording of the "sampling for the catalogue's classification" step that follows a successful catalogue scan of a source
 * ({@link SourceOnboarder#sampleForClassification}). The catalogue classifies a column (adds the tag {@code EMAIL}, suggests {@code PHONE} or {@code PERSON_NAME}) only when its
 * {@code Catalog sample: <SOURCE>} flow has written the sampled values of the column (one {@code ColumnSample} row per text column; {@code ColumnSampleTrigger} classifies them and
 * blanks them): the Factory's onboarding scan never ran it, so a source scanned by the Factory had no tag at all. The sampling is a courtesy that must never fail the scan - this class
 * only phrases what happened, as one sentence for the step's message. Pure.
 */
final class SampleClassification
{
	/** The sentence every message of this step starts with. */
	static final String PREFIX = "sampling for the catalogue's classification: ";
	/** Columns one sampling run may write ({@code connectors_sync}'s {@code max}: one item per text column; the API default of 200 would cut a large source). */
	static final int MAX_COLUMNS = 20000;

	private SampleClassification()
	{
	}

	/** {@code sampling for the catalogue's classification: skipped: <reason>}. */
	static String skipped(final String pReason)
	{
		final String reason = pReason == null || pReason.trim().isEmpty() ? "no reason given" : pReason.trim();
		return SampleClassification.PREFIX + "skipped: " + (reason.length() > 300 ? reason.substring(0, 300) + "..." : reason);
	}

	/**
	 * What a {@code connectors_sync} answer of the sample flow says: {@code sampling for the catalogue's classification: 9 columns sampled}, with {@code , N with errors} and
	 * {@code , cut at N columns} when the answer says so; {@code skipped: ...} when the run itself failed (status ERROR with nothing read).
	 */
	static String outcome(final JsonNode pSyncResult)
	{
		if (pSyncResult == null || pSyncResult.isMissingNode())
		{
			return SampleClassification.skipped("Connectors gave no answer");
		}
		final String status = pSyncResult.path("status").asText("");
		final long columns = pSyncResult.path("ok").asLong(pSyncResult.path("count").asLong(0));
		final long errors = pSyncResult.path("errors").asLong(0);
		if ("ERROR".equals(status) && columns == 0)
		{
			return SampleClassification.skipped("the sample flow failed" + (errors > 0 ? " (" + errors + " error(s))" : ""));
		}
		final StringBuilder out = new StringBuilder(SampleClassification.PREFIX).append(columns).append(columns == 1 ? " column" : " columns").append(" sampled");
		if (errors > 0)
		{
			out.append(", ").append(errors).append(" with errors");
		}
		if (pSyncResult.path("truncated").asBoolean(false))
		{
			out.append(", cut at ").append(SampleClassification.MAX_COLUMNS).append(" columns");
		}
		return out.toString();
	}

	/** A mapping of {@code connectors_mappings_list} that is the sample flow: operation {@code sample}, inbound, a feed, not a child, writing {@code /root/ColumnSample}. */
	static boolean isSampleFlow(final JsonNode pMapping)
	{
		return "sample".equals(pMapping.path("operation").asText("")) && "INBOUND".equals(pMapping.path("direction").asText("")) && pMapping.path("feed").asBoolean(false)
			&& !pMapping.path("child").asBoolean(true) && "/root/ColumnSample".equals(pMapping.path("table").asText(""));
	}
}
