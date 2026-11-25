package com.on.ebx.factory.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the answer of the scripting IDE's validate-only route ({@code POST /ebx-ide/rest/v1/script/validate/<idePath>}, body
 * {@code {"source":...,"parameters":[],"openApi":""}}; saves nothing) into one readable sentence - lot S3 (2026-10-18).
 * <p>
 * Why it exists: {@code update_script} answers {@code INVALID_DATA_MODEL "Revision history ... could not be loaded"} for ANY runtime exception
 * of the compiler (the ScriptingMcp classifier's wording), which hid that every typed {@code sql} literal crashes the compiler of a data-model
 * trigger script on 6.3.0-rc3. The validate route answers {@code {"isSuccess":true,"messages":[]}} when the body compiles, a list of messages
 * (with a line and a column) when it does not, and a bare HTTP 500 when the compiler itself crashes - which is itself the useful signal.
 * Pure (JSON text in, text out), so unit-tested without an EBX class.
 */
public final class ScriptValidateReport
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ScriptValidateReport()
	{
	}

	/** One sentence for the install result: the HTTP code, then the report, then (when a line is named) the failing statement of {@code pSource}. */
	public static String describe(final int pHttpCode, final String pBody, final String pSource)
	{
		final StringBuilder sb = new StringBuilder("validate-only IDE route: HTTP " + pHttpCode);
		final JsonNode report = ScriptValidateReport.parse(pBody);
		if (report == null)
		{
			final String text = pBody == null ? "" : pBody.trim();
			if (pHttpCode >= 500)
			{
				sb.append(" with no readable report (the compiler crashed on this body - bisect it statement by statement through this route; every typed sql literal does)");
			}
			else if (!text.isEmpty())
			{
				sb.append(", answer: ").append(text.length() > 400 ? text.substring(0, 400) + "..." : text);
			}
			return sb.toString();
		}
		sb.append(", isSuccess=").append(report.path("isSuccess").asBoolean(false));
		final String messages = ScriptValidateReport.messages(report);
		if (!messages.isEmpty())
		{
			sb.append(", ").append(messages);
		}
		final int line = ScriptValidateReport.firstLine(report);
		final String statement = line > 0 ? ScriptValidateReport.statementAt(pSource, line) : "";
		if (!statement.isEmpty())
		{
			sb.append(" - first failing statement (line ").append(line).append("): ").append(statement);
		}
		return sb.toString();
	}

	/** {@code messages[]} joined as {@code line:col: text}; an element may be an object (message/startLine/startColumn or line/column) or a bare string. */
	static String messages(final JsonNode pReport)
	{
		final StringBuilder out = new StringBuilder();
		for (final JsonNode message : pReport.path("messages"))
		{
			if (out.length() > 0)
			{
				out.append("; ");
			}
			if (message.isTextual())
			{
				out.append(message.asText());
				continue;
			}
			final int line = ScriptValidateReport.lineOf(message);
			if (line > 0)
			{
				out.append(line);
				final int column = ScriptValidateReport.columnOf(message);
				if (column > 0)
				{
					out.append(':').append(column);
				}
				out.append(": ");
			}
			out.append(message.path("message").asText(message.toString()));
		}
		return out.toString();
	}

	/** The line of the first message that names one, 0 when none does. */
	static int firstLine(final JsonNode pReport)
	{
		for (final JsonNode message : pReport.path("messages"))
		{
			final int line = ScriptValidateReport.lineOf(message);
			if (line > 0)
			{
				return line;
			}
		}
		return 0;
	}

	/** The trimmed text of the 1-based line {@code pLine} of {@code pSource}, empty when out of range. */
	static String statementAt(final String pSource, final int pLine)
	{
		if (pSource == null || pLine < 1)
		{
			return "";
		}
		final String[] lines = pSource.split("\n", -1);
		return pLine > lines.length ? "" : lines[pLine - 1].trim();
	}

	private static int lineOf(final JsonNode pMessage)
	{
		if (!pMessage.isObject())
		{
			return 0;
		}
		return pMessage.hasNonNull("startLine") ? pMessage.path("startLine").asInt(0) : pMessage.path("line").asInt(0);
	}

	private static int columnOf(final JsonNode pMessage)
	{
		return pMessage.hasNonNull("startColumn") ? pMessage.path("startColumn").asInt(0) : pMessage.path("column").asInt(0);
	}

	private static JsonNode parse(final String pBody)
	{
		if (pBody == null || pBody.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final JsonNode node = ScriptValidateReport.MAPPER.readTree(pBody);
			return node != null && node.isObject() && node.has("isSuccess") ? node : null;
		}
		catch (final java.io.IOException ex)
		{
			return null;
		}
	}
}
