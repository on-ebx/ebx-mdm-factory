package com.on.ebx.factory.core;

import java.io.IOException;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * X3-08 (1.4.1): the read bounds of the module's JSON, written down. Since Jackson 2.15 every parser is bounded by {@code StreamReadConstraints} (nesting depth 1000, string
 * 20,000,000 characters, number 1000 characters, property name 50,000 characters); the module embeds Jackson 2.18.12 and <b>keeps these defaults</b>: it does not call
 * {@code StreamReadConstraints.overrideDefaultStreamReadConstraints} anywhere, so no property of the module moves them (docs/OPERATIONS.md, "JSON read bounds").
 * Why the defaults are enough, from the code: the JSON the module reads is its REST bodies (bounded by their own shape: at most 200 decisions, a sample-file panel capped at
 * 1,400,000 raw bytes, i.e. about 1.9 million characters of base64), the answers of the ON EBX and Connectors MCP servers and of the language model (a table or an answer, never
 * a document in one string) and its own JSON columns read back from its tables (every such field is declared with a {@code maxLength} of at most 100,000 characters,
 * {@code tools/build_xsd.py}); the project backup is read as a stream, one row at a time. None of these has a legitimate string of 20 million characters or 1000 levels of nesting.
 * <p>
 * What this class does: says the bounds in force ({@link #statusJson}, published under {@code readBounds} in {@code GET /rest/v1/status}), and tells a refusal by a bound
 * ({@code StreamConstraintsException}, a {@code JsonProcessingException}) from a malformed document, so that a caller refuses it with a code ({@link #BOUND_CODE},
 * {@link #MALFORMED_CODE}) instead of taking it for "empty" or "absent". The exception is recognised by its class NAME along the cause chain, so this class (and every caller)
 * still loads on a Jackson older than 2.15, where the bounds do not exist: {@link #statusJson} then says so.
 */
public final class JsonLimits
{
	/** Code of a refusal because the document goes beyond a read bound (depth, string, number, name length). */
	public static final String BOUND_CODE = "MDMF-JSON-1";

	/** Code of a refusal because the document is not JSON. */
	public static final String MALFORMED_CODE = "MDMF-JSON-2";

	private static final String CONSTRAINTS_EXCEPTION = "com.fasterxml.jackson.core.exc.StreamConstraintsException";

	private static final int DETAIL_MAX = 300;

	private JsonLimits()
	{
	}

	/** True when the failure, or one of its causes, is Jackson's refusal because a read bound was crossed. */
	public static boolean isBoundRefusal(final Throwable pFailure)
	{
		Throwable current = pFailure;
		for (int depth = 0; current != null && depth < 10; depth++)
		{
			if (JsonLimits.CONSTRAINTS_EXCEPTION.equals(current.getClass().getName()))
			{
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/**
	 * The coded sentence for a document that could not be read. {@code pWhat} says which document ("the request body", "the answer of the data-model server").
	 * A bound refusal says what to do (send less, or less nested) and where the bounds are written.
	 */
	public static String refusal(final IOException pFailure, final String pWhat)
	{
		final String detail = JsonLimits.detail(pFailure);
		if (JsonLimits.isBoundRefusal(pFailure))
		{
			return JsonLimits.BOUND_CODE + ": " + pWhat + " goes beyond a JSON read bound of this module (" + detail + "). Send a smaller or less nested document; the bounds in force are under readBounds in GET /rest/v1/status.";
		}
		return JsonLimits.MALFORMED_CODE + ": " + pWhat + " is not valid JSON: " + detail;
	}

	private static String detail(final Throwable pFailure)
	{
		final String message = pFailure.getMessage();
		final String text = message == null ? pFailure.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
		return text.length() <= JsonLimits.DETAIL_MAX ? text : text.substring(0, JsonLimits.DETAIL_MAX) + "...";
	}

	/**
	 * The bounds in force, for {@code /status}: {@code applied} false (Jackson older than 2.15, no bound at all) or the five limits of {@code StreamReadConstraints.defaults()}
	 * ({@code -1} = unlimited), where they come from, and the Jackson version. Read from Jackson itself, not from this class's own idea of the defaults.
	 */
	public static ObjectNode statusJson()
	{
		final ObjectNode out = JsonNodeFactory.instance.objectNode();
		out.put("jacksonVersion", com.fasterxml.jackson.core.json.PackageVersion.VERSION.toString());
		try
		{
			final com.fasterxml.jackson.core.StreamReadConstraints constraints = com.fasterxml.jackson.core.StreamReadConstraints.defaults();
			out.put("applied", true);
			out.put("source", "Jackson defaults (StreamReadConstraints); the module sets no override");
			out.put("maxNestingDepth", constraints.getMaxNestingDepth());
			out.put("maxStringLength", constraints.getMaxStringLength());
			out.put("maxNumberLength", constraints.getMaxNumberLength());
			out.put("maxNameLength", constraints.getMaxNameLength());
			out.put("maxDocumentLength", constraints.getMaxDocumentLength());
			out.put("refusalCode", JsonLimits.BOUND_CODE);
		}
		catch (final LinkageError ex)
		{
			out.put("applied", false);
			out.put("source", "Jackson older than 2.15: no read bound exists (the module is built for 2.18.12; see docs/UPGRADE.md section 13)");
		}
		return out;
	}
}
