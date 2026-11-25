package com.on.ebx.factory.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Whether {@code data-model.publish_data_model} actually published the model - read from the tool's own
 * response, never merely inferred from the absence of a transport-level error. The tool's own contract
 * (read live, 2026-09-20): <i>"the ONLY tool that runs the full validate -&gt; compile -&gt; publish chain,
 * publishing only if both gates pass (no partial write)"</i>; a gate block <i>"returns a structured failure
 * (published/found:false + code), never a throw"</i>. A caller that only checks the MCP transport/JSON-RPC
 * error flag can therefore read a refused publish as a success, because the refusal comes back as an
 * ordinary, non-error response body with {@code published:false}.
 * <p>
 * {@code StagingGenerator} got this right first (2026-09-20 coordinator review, the resume-before-publish
 * defect); {@code LandingGenerator} used the same absence-of-error shortcut and needed the same fix in a
 * follow-up review the same day - both now call this ONE helper instead of each re-deriving the same
 * one-line check. Pure: no EBX class, unit-tested as is.
 */
public final class PublishDecision
{
	private PublishDecision()
	{
	}

	/**
	 * @param pResponse the {@code json} field of {@code publish_data_model}'s {@code ToolResult}; null when
	 * the call itself failed at the transport/protocol level (a caller checks that separately, first - a
	 * null response here reads as not published, never as an assumed success).
	 */
	public static boolean published(final JsonNode pResponse)
	{
		return pResponse != null && pResponse.path("published").asBoolean(false);
	}
}
