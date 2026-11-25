package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.deducedscan.Flatten;
import com.on.ebx.factory.deducedscan.OperationCatalog;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Deduced-scan lot (2026-09-24, read-recipe-contract.md sec "Why"): "Read a sample" stage - {@code
 * connectors_call} with the operation/inputs {@link com.on.ebx.factory.deducedscan.ListOperationPass} chose,
 * capped at {@link #MAX_ITEMS} (contract: "Sample = 25 items max"), the answer's items flattened
 * ({@link Flatten}) - same read-only mechanism {@code ebx.LandingPreview} already uses
 * ({@link McpServers#connectors}, never {@code allowWrite}), reused here rather than re-derived. The items
 * array location in the answer ({@code itemsPath}, contract sec 1: "rows"/"results"/"messages[*].value"/empty
 * when the answer itself is the array) is discovered heuristically from the SAME plausible-key list {@code
 * LandingFlows}/{@code DistributionConnections} already try elsewhere in this module, and reported back so it
 * can be written verbatim into {@code Asset.readRecipe.itemsPath}.
 */
public final class DeducedScanSample
{
	public static final int MAX_ITEMS = 25;
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** Checked in order - the first one present and an array wins. {@code "messages"} covers a Kafka-shaped answer whose items sit one level under {@code [*].value} (contract's own example); this class does not descend into {@code .value} itself, only reports the item objects as found - a connector whose useful data sits nested one level further is a rarer shape not modelled by this first cut. */
	private static final String[] ITEMS_PATH_CANDIDATES = { "records", "rows", "results", "items", "data", "documents" };

	private DeducedScanSample()
	{
	}

	public static final class Result
	{
		public final List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
		/** What {@code Asset.readRecipe.itemsPath} should carry - empty when the answer's own array IS the item list. */
		public String itemsPath = "";
		/** Non-null on a failure to read anything at all. */
		public String error;
	}

	/** Builds a fresh {@link McpClient} for {@code pFactory}/{@code pSession} and reads one sample - a convenience for a single, one-off call. {@code ebx.DeducedScanRun} prefers the (McpClient, connectorId, choice) overload below to reuse one client across LIST/SAMPLE. */
	public static Result sample(final Session pSession, final Adaptation pFactory, final String pConnectorId, final OperationCatalog.ListChoice pChoice)
	{
		final Result out = new Result();
		try
		{
			return DeducedScanSample.sample(McpServers.connectors(pSession, pFactory), pConnectorId, pChoice);
		}
		catch (final OperationException ex)
		{
			out.error = ex.getMessage();
			return out;
		}
	}

	/** Same as {@link #sample(Session, Adaptation, String, OperationCatalog.ListChoice)} but with an already-built client - the shape {@code ebx.DeducedScanRun} actually calls (one client per connector, reused across LIST/SAMPLE). */
	public static Result sample(final McpClient pClient, final String pConnectorId, final OperationCatalog.ListChoice pChoice)
	{
		final Result out = new Result();
		// audit F-17: the last look before the external system is called - the values were checked when the operation was chosen and again when the
		// callable ones were selected, but this method is public and the model's JSON is never trusted twice removed. Here the operation is no longer at hand,
		// so no declared type is known: length, control characters and the URL/host rules still apply.
		final String badValue = DeducedScanSample.valueViolation(pChoice);
		if (badValue != null)
		{
			out.error = "Not called: " + badValue;
			return out;
		}
		try
		{
			final ObjectNode args = DeducedScanSample.MAPPER.createObjectNode();
			args.put("connector", pConnectorId);
			args.put("operation", pChoice.operation);
			final ObjectNode inputs = args.putObject("inputs");
			for (final Map.Entry<String, String> entry : pChoice.inputs.entrySet())
			{
				inputs.put(entry.getKey(), entry.getValue());
			}
			inputs.put("max", DeducedScanSample.MAX_ITEMS);
			final McpClient.ToolResult result = pClient.callTool("connectors_call", args);
			if (result.json != null && result.json.hasNonNull("error"))
			{
				out.error = result.json.path("error").asText();
				return out;
			}
			if (result.error)
			{
				out.error = result.text == null || result.text.trim().isEmpty() ? "connectors_call refused the request." : result.text.trim();
				return out;
			}
			DeducedScanSample.fill(out, result.json == null ? null : result.json.path("document"));
		}
		catch (final McpException ex)
		{
			out.error = ex.getMessage();
		}
		return out;
	}

	/** Pure: the first input value of the choice that {@link com.on.ebx.factory.deducedscan.InputValueRules} refuses without knowing the declared types, or null. */
	static String valueViolation(final OperationCatalog.ListChoice pChoice)
	{
		return OperationCatalog.valueViolation(new OperationCatalog.OperationInfo(pChoice.operation, true, new ArrayList<String>(pChoice.inputs.keySet())), pChoice);
	}

	private static void fill(final Result pResult, final JsonNode pDocument)
	{
		if (pDocument == null || pDocument.isMissingNode() || pDocument.isNull())
		{
			return;
		}
		JsonNode itemsNode = null;
		for (final String candidate : DeducedScanSample.ITEMS_PATH_CANDIDATES)
		{
			if (pDocument.has(candidate) && pDocument.path(candidate).isArray())
			{
				itemsNode = pDocument.path(candidate);
				pResult.itemsPath = candidate;
				break;
			}
		}
		if (itemsNode == null && pDocument.isArray())
		{
			itemsNode = pDocument;
			pResult.itemsPath = "";
		}
		if (itemsNode == null)
		{
			return;
		}
		int count = 0;
		for (final JsonNode item : itemsNode)
		{
			if (count >= DeducedScanSample.MAX_ITEMS)
			{
				break;
			}
			pResult.rows.add(Flatten.row(item));
			count++;
		}
	}
}
