package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.LandingFlow;
import com.on.ebx.factory.landing.ReadRecipe;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * A small live sample of one landing table's source object, fetched on demand for the wizard's step 3
 * "Preview" button (task brief): for a file-based connector (sample-file/SFTP/S3/GCS/Azure Blob), the
 * catalog only ever holds a schema scan ({@code LandingColumn} rows from {@link LandingPlanner}) - those
 * connector types declare {@code catalog} but never {@code profile}/{@code sample}, so no
 * {@code ColumnSample}/{@code ProfileSnapshot} row exists anywhere to read real values from. The only way
 * to show a human real data before they pick a key or rename anything is to call the connector's own
 * {@code read} operation live, through the same MCP mechanism {@link LandingFlows} already uses
 * ({@link McpServers#client}, {@code connectors_call} instead of {@code connectors_mapping_upsert}).
 * Read-only by construction: {@code allowWrite} is never passed, so a connector whose only operation named
 * {@code read} happened to be a write operation would be refused by {@code ebx-connectors} itself, not
 * silently attempted here.
 * <p>
 * The read is chosen exactly as {@link LandingFlows} chooses the table's flow: the catalog's read recipe when
 * the asset carries one (its own operation and inputs, plus {@code max}), otherwise {@code read}/{@code path}
 * for a file connector and {@code query} with a {@code select *} in the connector's SQL dialect for a SQL one.
 * Until 2026-09-25 it always called {@code read}, so every SQL source (project PersVol, H2 connector
 * PersVolCRM) answered "Connector PersVolCRM has no operation 'read'" and the key study got no sample.
 * The row count is capped here as well, in case an operation ignores {@code max}.
 * <p>
 * Rows can be arbitrary JSON (a file-based connector may read CSV, JSON, NDJSON or XML): each row is
 * flattened to dotted/bracketed keys ({@code address.city}, {@code tags[0]}) rather than assumed to be a
 * flat record, and the columns shown are the ordered union of every row's own keys - a row missing a key
 * some other row has just shows an empty cell. Simpler for a person to scan than a raw JSON dump per row,
 * and safe for a shape this class does not otherwise know.
 */
public final class LandingPreview
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Small on purpose (task brief: "3-5 real rows, enough to eyeball") - never a bulk read. */
	public static final int DEFAULT_MAX = 5;

	private LandingPreview()
	{
	}

	/** One row per flattened source row, columns aligned across every row - see the class Javadoc. */
	public static final class Result
	{
		public final List<String> columns = new ArrayList<String>();
		public final List<List<String>> rows = new ArrayList<List<String>>();
		/** Null on success (rows may still legitimately be empty, e.g. an empty source file - that is not an error). */
		public String error;
	}

	public static Result sample(final Session pSession, final Adaptation pFactory, final String pLandingTableId, final int pMax)
	{
		final Result result = new Result();
		try
		{
			final Adaptation table = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingTableId));
			if (table == null)
			{
				result.error = "Landing table " + pLandingTableId + " not found.";
				return result;
			}
			final String sourceObject = table.getString(FactoryPaths.LandingTable.SOURCE_OBJECT);
			if (sourceObject == null || sourceObject.trim().isEmpty())
			{
				result.error = "This table names no source object to read.";
				return result;
			}
			final String projectSourceId = table.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
			final Adaptation projectSource = projectSourceId == null || projectSourceId.trim().isEmpty() ? null
				: pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectSourceId));
			final String connectorId = projectSource == null ? null : projectSource.getString(FactoryPaths.ProjectSource.CONNECTOR);
			if (connectorId == null || connectorId.trim().isEmpty())
			{
				result.error = "This source names no connector: a preview cannot be read.";
				return result;
			}

			final int max = pMax <= 0 ? LandingPreview.DEFAULT_MAX : pMax;
			final McpClient client = McpServers.connectors(pSession, pFactory);
			final ObjectNode args = LandingPreview.MAPPER.createObjectNode();
			final ObjectNode inputs = LandingPreview.MAPPER.createObjectNode();
			String itemsPath = "rows";
			// audit D-07: the asset KEY, resolved from catalogAsset or, for a table planned before, from its source and name
			final ReadRecipe.Eligibility eligibility = LandingFlows.recipeEligibility(pFactory, table);
			if (eligibility.kind == ReadRecipe.Eligibility.Kind.RECIPE)
			{
				args.put("connector", eligibility.recipe.connector);
				args.put("operation", eligibility.recipe.operation);
				for (final Map.Entry<String, String> input : eligibility.recipe.inputs.entrySet())
				{
					inputs.put(input.getKey(), input.getValue());
				}
				itemsPath = eligibility.recipe.itemsPath;
			}
			else
			{
				final LandingFlows.ConnectorInfo connector = LandingFlows.describeConnector(client, connectorId, LandingPreview.MAPPER.createObjectNode());
				args.put("connector", connectorId);
				if (connector.fileFeed)
				{
					args.put("operation", "read");
					inputs.put("path", sourceObject);
				}
				else
				{
					// throws IllegalArgumentException for a type with no SQL dialect: caught below, shown as is
					args.put("operation", "query");
					inputs.put("sql", LandingFlow.selectAll(connector.type, sourceObject));
				}
			}
			inputs.put("max", String.valueOf(max));
			args.set("inputs", inputs);
			final McpClient.ToolResult toolResult = client.callTool("connectors_call", args);
			// connectors_call itself never throws for a refused/failed call (ConnectorsTools' own contract:
			// "errors come back as {"error": "..."} rather than exceptions") - check the JSON payload first,
			// then fall back to the protocol-level isError flag, exactly the two checks LandingFlows.errorText
			// already makes for the tools that DO set isError.
			if (toolResult.json != null && toolResult.json.hasNonNull("error"))
			{
				result.error = toolResult.json.path("error").asText();
				return result;
			}
			if (toolResult.error)
			{
				result.error = toolResult.text == null || toolResult.text.trim().isEmpty() ? "connectors_call refused the request." : toolResult.text.trim();
				return result;
			}
			LandingPreview.fill(result, LandingPreview.items(toolResult.json == null ? null : toolResult.json.path("document"), itemsPath), max);
		}
		catch (final OperationException ex)
		{
			result.error = ex.getMessage();
		}
		catch (final McpException ex)
		{
			result.error = ex.getMessage();
		}
		catch (final RuntimeException ex)
		{
			result.error = ErrorText.of(ex);
		}
		return result;
	}

	/** The array at {@code pItemsPath} (dot-separated, {@code ""} = the document itself), or null. */
	private static JsonNode items(final JsonNode pDocument, final String pItemsPath)
	{
		JsonNode node = pDocument;
		if (pItemsPath != null && !pItemsPath.trim().isEmpty())
		{
			for (final String step : pItemsPath.trim().split("[.]"))
			{
				node = node == null ? null : node.path(step);
			}
		}
		return node;
	}

	private static void fill(final Result pResult, final JsonNode pRows, final int pMax)
	{
		// a null document is not an error: AbstractFileConnector's "read" answers it for an empty file, the
		// JDBC "query" for an empty table - just nothing to show (this is a preview, not a validity check)
		if (pRows == null || !pRows.isArray() || pRows.size() == 0)
		{
			return;
		}
		final List<LinkedHashMap<String, String>> flattened = new ArrayList<LinkedHashMap<String, String>>();
		final Set<String> columns = new LinkedHashSet<String>();
		for (final JsonNode row : pRows)
		{
			if (flattened.size() >= pMax)
			{
				break;
			}
			final LinkedHashMap<String, String> flat = new LinkedHashMap<String, String>();
			LandingPreview.flatten("", row, flat);
			flattened.add(flat);
			columns.addAll(flat.keySet());
		}
		pResult.columns.addAll(columns);
		for (final LinkedHashMap<String, String> flat : flattened)
		{
			final List<String> line = new ArrayList<String>(pResult.columns.size());
			for (final String column : pResult.columns)
			{
				final String value = flat.get(column);
				line.add(value == null ? "" : value);
			}
			pResult.rows.add(line);
		}
	}

	/** Dotted/bracketed flattening of one arbitrary JSON row - see the class Javadoc. */
	private static void flatten(final String pPrefix, final JsonNode pNode, final Map<String, String> pOut)
	{
		if (pNode == null || pNode.isNull() || pNode.isMissingNode())
		{
			pOut.put(pPrefix, "");
			return;
		}
		if (pNode.isObject())
		{
			if (pNode.size() == 0)
			{
				pOut.put(pPrefix, "{}");
				return;
			}
			for (final Iterator<Map.Entry<String, JsonNode>> it = pNode.fields(); it.hasNext();)
			{
				final Map.Entry<String, JsonNode> entry = it.next();
				LandingPreview.flatten(pPrefix.isEmpty() ? entry.getKey() : pPrefix + "." + entry.getKey(), entry.getValue(), pOut);
			}
			return;
		}
		if (pNode.isArray())
		{
			if (pNode.size() == 0)
			{
				pOut.put(pPrefix, "[]");
				return;
			}
			for (int i = 0; i < pNode.size(); i++)
			{
				LandingPreview.flatten(pPrefix + "[" + i + "]", pNode.get(i), pOut);
			}
			return;
		}
		pOut.put(pPrefix, pNode.asText());
	}
}
