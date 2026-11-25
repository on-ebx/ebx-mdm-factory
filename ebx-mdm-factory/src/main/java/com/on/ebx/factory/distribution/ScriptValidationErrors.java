package com.on.ebx.factory.distribution;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Formats an {@code update_script} answer's {@code data.validation.errors[]} array into one readable line per
 * entry ({@code line:col: message}, semicolon-joined) - the same fields {@code ebx.CapabilityApplier} already
 * reads for a script constraint (startLine/startColumn/message).
 * <p>
 * Live bug fix (2026-09-25, DistribTest, PipelineStep 364): {@code DistributionConfigurator#errorText} alone
 * reads "(no message)" on a pure validation failure - {@code update_script}'s own top-level {@code error}/
 * {@code text} fields are empty when the CALL succeeded but the SOURCE failed to compile, so the real reason
 * was silently dropped from every thrown exception. Pure (a {@link JsonNode} in, a {@link String} out), so
 * unit-tested directly without an EBX class or a live {@code McpClient.ToolResult} (whose constructor is
 * package-private to {@code com.on.ebx.agent}, unreachable from a test in this module).
 */
public final class ScriptValidationErrors
{
	private ScriptValidationErrors()
	{
	}

	/** {@code pUpdateJson}: the {@code update_script} answer's own {@code .json} (may be {@code null}). Empty string when there is no {@code data.validation.errors[]} entry at all (a transport/tool-level failure, not a compile failure) - the caller falls back to its own generic error text in that case. */
	public static String format(final JsonNode pUpdateJson)
	{
		if (pUpdateJson == null)
		{
			return "";
		}
		final StringBuilder out = new StringBuilder();
		for (final JsonNode errorNode : pUpdateJson.path("data").path("validation").path("errors"))
		{
			if (out.length() > 0)
			{
				out.append("; ");
			}
			if (errorNode.hasNonNull("startLine"))
			{
				out.append(errorNode.path("startLine").asInt());
				if (errorNode.hasNonNull("startColumn"))
				{
					out.append(':').append(errorNode.path("startColumn").asInt());
				}
				out.append(": ");
			}
			out.append(errorNode.path("message").asText("(no message)"));
		}
		return out.toString();
	}
}
