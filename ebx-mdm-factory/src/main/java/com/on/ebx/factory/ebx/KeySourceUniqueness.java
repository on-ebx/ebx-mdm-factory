package com.on.ebx.factory.ebx;

import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.KeyEvidence;
import com.on.ebx.factory.landing.LandingFlow;
import com.on.ebx.factory.landing.ReadRecipe;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

/**
 * Volume-guards lot (2026-09-28, task item 4, "landing key applied only if unique at the source"): verifies a
 * {@code RULE}/{@code LLM}-proposed key against the WHOLE source before {@code ebx.LandingRun} lets {@link
 * com.on.ebx.factory.landing.KeyCertainty#certain} treat it as safe to auto-apply with nobody looking - a key
 * unique in {@code KeyStudy.SAMPLE_MAX} (25) sample rows can still collide once the source has millions.
 * Read-only, and deliberately degrades to {@code null} ("not verifiable") on ANY failure ({@link #verify}
 * never throws): this is a guard-rail on top of the pre-existing sample check, never a reason to fail the
 * whole "Prepare the landing" run over a transport hiccup.
 * <p>
 * Two paths, chosen the same way {@code ebx.LandingPreview}/{@code ebx.LandingFlows} already choose a table's
 * read/query shape:
 * <ul>
 * <li><b>A SQL-capable connector</b> ({@code LandingFlows.ConnectorInfo#fileFeed} false, {@link
 * LandingFlow#dialectFor} recognises its type): runs {@link LandingFlow#uniquenessCheckSql} through the
 * connector's own read-only {@code query} operation ({@code connectors_call}) - a single {@code COUNT(*)}/
 * {@code COUNT(DISTINCT ...)} row, compared in Java. Cheap regardless of table size: the counting happens at
 * the source, never a bulk read into EBX.</li>
 * <li><b>A file/recipe source</b> (no SQL dialect at all): read IN FULL only when BOUNDED - {@code
 * LandingTable.sourceRowCount} is known ({@code &gt; 0}) and no larger than {@link #FULL_READ_BOUND} - through
 * {@code ebx.LandingPreview#sample} (which already resolves the catalog's read recipe vs. the connector's own
 * {@code read} operation exactly like a table's real load would), then {@link KeyEvidence#uniqueTogether}
 * decides on the FULL row set instead of a small sample. An unknown (never profiled) or too-large row count is
 * treated as NOT VERIFIABLE - never assumed small, the task brief's own "not verifiable" case - rather than
 * risking a bulk read of a genuinely huge file source just to check a key.</li>
 * </ul>
 */
final class KeySourceUniqueness
{
	/**
	 * Above this many rows, a file/recipe source (no SQL dialect, so no server-side {@code COUNT DISTINCT}) is
	 * not read in full to verify a key - too large to check this way without turning "prepare the landing"
	 * into a bulk read of its own. A genuinely huge file source is exactly the case {@code null} ("not
	 * verifiable") was written for: the chooser is shown instead of guessing.
	 */
	static final int FULL_READ_BOUND = 5000;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private KeySourceUniqueness()
	{
	}

	/**
	 * @param pKeyColumns the proposal's own key columns (source column names) - null/empty answers {@code null}
	 *            at once (nothing to check).
	 * @return {@code TRUE} (confirmed unique across the whole source), {@code FALSE} (a real collision found),
	 *         or {@code null} (could not be verified at all: an unsupported connector type, a file/recipe
	 *         source of unknown or too-large size, a transport/tool failure). Never throws - see class Javadoc.
	 */
	static Boolean verify(final Session pSession, final Adaptation pFactory, final String pLandingTableId, final List<String> pKeyColumns)
	{
		if (pKeyColumns == null || pKeyColumns.isEmpty())
		{
			return null;
		}
		try
		{
			final Adaptation table = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingTableId));
			if (table == null)
			{
				return null;
			}
			final String sourceObject = table.getString(FactoryPaths.LandingTable.SOURCE_OBJECT);
			if (sourceObject == null || sourceObject.trim().isEmpty())
			{
				return null;
			}
			final String projectSourceId = table.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
			final Adaptation projectSource = projectSourceId == null || projectSourceId.trim().isEmpty() ? null
				: pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectSourceId));
			final String connectorId = projectSource == null ? null : projectSource.getString(FactoryPaths.ProjectSource.CONNECTOR);
			if (connectorId == null || connectorId.trim().isEmpty())
			{
				return null;
			}
			final Object rawRowCount = table.get(FactoryPaths.LandingTable.SOURCE_ROW_COUNT);
			final long sourceRowCount = rawRowCount instanceof Number ? ((Number) rawRowCount).longValue() : 0L;

			final McpClient client = McpServers.connectors(pSession, pFactory);
			// audit D-07: the asset KEY, resolved from catalogAsset or, for a table planned before, from its source and name
			final ReadRecipe.Eligibility eligibility = LandingFlows.recipeEligibility(pFactory, table);
			if (eligibility.kind != ReadRecipe.Eligibility.Kind.RECIPE)
			{
				final LandingFlows.ConnectorInfo connector = LandingFlows.describeConnector(client, connectorId, KeySourceUniqueness.MAPPER.createObjectNode());
				if (!connector.fileFeed)
				{
					return KeySourceUniqueness.verifyBySql(client, connectorId, connector.type, sourceObject, pKeyColumns);
				}
			}
			// file/recipe: bounded full read only - see class Javadoc.
			if (sourceRowCount <= 0L || sourceRowCount > KeySourceUniqueness.FULL_READ_BOUND)
			{
				return null;
			}
			final LandingPreview.Result full = LandingPreview.sample(pSession, pFactory, pLandingTableId, (int) sourceRowCount);
			if (full.error != null)
			{
				return null;
			}
			// uniqueTogether is already nullable (null when it could not be told) - exactly this method's own
			// "not verifiable" contract, so its result is returned as-is, never unboxed.
			return KeyEvidence.uniqueTogether(full.columns, full.rows, pKeyColumns);
		}
		catch (final Exception ex) // deliberately broad: a guard-rail on top of the sample check, never a reason to fail the run
		{
			LoggingCategory.getKernel().info("[mdm-factory] key source-uniqueness check could not run for landing table " + pLandingTableId + ": " + ex);
			return null;
		}
	}

	/** The SQL-capable path: one round trip, counted at the source, never a bulk read. */
	private static Boolean verifyBySql(final McpClient pClient, final String pConnectorId, final String pConnectorType, final String pSourceObject,
		final List<String> pKeyColumns)
	{
		final String sql;
		try
		{
			sql = LandingFlow.uniquenessCheckSql(pConnectorType, pSourceObject, pKeyColumns);
		}
		catch (final IllegalArgumentException ex)
		{
			// no SQL dialect for this connector type (LandingFlow.dialectFor's own refusal) - defensive: every
			// connector this factory targets is either SQL-capable or fileFeed, so this path is not expected live.
			return null;
		}
		final ObjectNode args = KeySourceUniqueness.MAPPER.createObjectNode();
		args.put("connector", pConnectorId);
		args.put("operation", "query");
		final ObjectNode inputs = args.putObject("inputs");
		inputs.put("sql", sql);
		inputs.put("max", "2");
		final McpClient.ToolResult result;
		try
		{
			result = pClient.callTool("connectors_call", args);
		}
		catch (final McpException ex)
		{
			return null;
		}
		if (result.error || result.json == null || result.json.hasNonNull("error"))
		{
			return null;
		}
		final JsonNode rows = result.json.path("document").path("rows");
		if (!rows.isArray() || rows.size() == 0 || !rows.get(0).isObject())
		{
			return null;
		}
		// A SQL alias's casing varies by database engine (H2/Snowflake may uppercase an unquoted alias,
		// PostgreSQL lowercases it) - read the two numeric fields POSITIONALLY (Jackson's ObjectNode preserves
		// field insertion order, and uniquenessCheckSql always emits totalCount then distinctCount) rather than
		// by name, so this never depends on getting the alias's engine-specific casing right.
		final Iterator<JsonNode> values = rows.get(0).elements();
		if (!values.hasNext())
		{
			return null;
		}
		final long total = KeySourceUniqueness.asLong(values.next());
		if (total < 0L || !values.hasNext())
		{
			return null;
		}
		final long distinct = KeySourceUniqueness.asLong(values.next());
		if (distinct < 0L)
		{
			return null;
		}
		return Boolean.valueOf(distinct == total);
	}

	/** A JSON value read as a non-negative count, or -1 if it is not one (never guessed). */
	private static long asLong(final JsonNode pValue)
	{
		if (pValue == null || pValue.isNull() || pValue.isMissingNode())
		{
			return -1L;
		}
		if (pValue.isNumber())
		{
			return pValue.asLong(-1L);
		}
		try
		{
			return Long.parseLong(pValue.asText().trim());
		}
		catch (final NumberFormatException ex)
		{
			return -1L;
		}
	}
}
