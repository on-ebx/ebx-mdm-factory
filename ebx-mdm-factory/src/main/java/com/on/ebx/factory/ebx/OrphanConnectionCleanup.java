package com.on.ebx.factory.ebx;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.project.ConnectionProvenance;
import com.on.ebx.factory.project.OrphanConnections;
import com.on.ebx.factory.project.ProjectDeletionPlan.ConnectionFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.MappingRef;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Orphan-connections lot (2026-10-17): a guarded way to remove the sample-file connections and catalogue sources that NO project uses and that carry no creation
 * trace (left by projects deleted before the {@code connections} option of the project deletion, which relies on {@code PipelineStep} evidence the older runs
 * never wrote). The EBX-facing side; the selection rules are {@link OrphanConnections} (pure) and, through it, {@code ProjectDeletionPlan}'s own - and the
 * gathering, the blockers and the two deletions are {@link ConnectionCleanup}'s, the very code the project deletion runs: there is no second implementation.
 * <p>
 * Two entry points, behind {@code GET /connections/orphans} and {@code POST /connections/orphans/delete} (administrator): {@link #list} and {@link #delete}.
 * The deletion re-gathers everything live, deletes only the ids that are still eligible, refuses outright (nothing deleted) when the {@code confirm} count does
 * not match the eligible ids sent, and answers one line per id: {@code DELETED}, {@code KEPT} (with the reason), {@code ABSENT}, or {@code FAILED} (an error
 * while deleting; what was deleted before it stays deleted, the answer says which step failed).
 */
public final class OrphanConnectionCleanup
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private OrphanConnectionCleanup()
	{
	}

	// ======================================================================================================== evidence

	/** What one connection the listing considers looks like now. */
	private static final class Entry
	{
		final String id;
		final String label;
		final Adaptation row;
		final ConnectionFacts facts;
		final OrphanConnections.Verdict verdict;

		Entry(final String pId, final String pLabel, final Adaptation pRow, final ConnectionFacts pFacts, final OrphanConnections.Verdict pVerdict)
		{
			this.id = pId;
			this.label = pLabel;
			this.row = pRow;
			this.facts = pFacts;
			this.verdict = pVerdict;
		}
	}

	/** Every connection id that a live project, a project source or a distribution target still references. Read once per call. */
	static Set<String> referenced(final Adaptation pFactory, final Adaptation pCatalog)
	{
		final List<String> connectorRefs = new ArrayList<String>();
		final List<String> sourceRefs = new ArrayList<String>();
		final AdaptationTable projects = pFactory.getTable(FactoryPaths.PROJECT);
		final Map<String, Boolean> projectExists = new HashMap<String, Boolean>();
		final RequestResult sources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult(null);
		try
		{
			for (Adaptation row = sources.nextAdaptation(); row != null; row = sources.nextAdaptation())
			{
				if (!OrphanConnectionCleanup.projectExists(projects, projectExists, row.getString(FactoryPaths.ProjectSource.PROJECT)))
				{
					continue;
				}
				OrphanConnectionCleanup.addIfSet(connectorRefs, row.getString(FactoryPaths.ProjectSource.CONNECTOR));
				OrphanConnectionCleanup.addIfSet(sourceRefs, row.getString(FactoryPaths.ProjectSource.CATALOG_SOURCE));
			}
		}
		finally
		{
			sources.close();
		}
		final RequestResult targets = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).createRequestResult(null);
		try
		{
			for (Adaptation row = targets.nextAdaptation(); row != null; row = targets.nextAdaptation())
			{
				OrphanConnectionCleanup.addIfSet(connectorRefs, row.getString(FactoryPaths.DistributionTarget.CONNECTOR));
			}
		}
		finally
		{
			targets.close();
		}
		final List<ConnectionProvenance.StepRow> steps = new ArrayList<ConnectionProvenance.StepRow>();
		final RequestResult stepRows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult("./step=" + CatalogReader.quote(ConnectionProvenance.STEP_CONNECTION)
			+ " or ./step=" + CatalogReader.quote(ConnectionProvenance.STEP_SOURCES_RUN));
		try
		{
			for (Adaptation row = stepRows.nextAdaptation(); row != null; row = stepRows.nextAdaptation())
			{
				if (!OrphanConnectionCleanup.projectExists(projects, projectExists, row.getString(FactoryPaths.PipelineStep.PROJECT)))
				{
					continue; // the step of a project that no longer exists references nothing
				}
				steps.add(new ConnectionProvenance.StepRow(row.getString(FactoryPaths.PipelineStep.STEP), row.getString(FactoryPaths.PipelineStep.SCOPE),
					row.getString(FactoryPaths.PipelineStep.DETAIL)));
			}
		}
		finally
		{
			stepRows.close();
		}
		// the catalogue source ids that must be mapped to their connection: the project sources' and the steps' (the pure rule reads both)
		final Map<String, String> sourceConnector = new LinkedHashMap<String, String>();
		final Set<String> sourceIds = new LinkedHashSet<String>(sourceRefs);
		for (final ConnectionProvenance.Evidence evidence : ConnectionProvenance.read(steps).values())
		{
			OrphanConnectionCleanup.addIfSet(sourceIds, evidence.catalogSourceId);
		}
		if (pCatalog != null)
		{
			final AdaptationTable sourceTable = pCatalog.getTable(ConnectionCleanup.SOURCE);
			for (final String id : sourceIds)
			{
				final Adaptation source = sourceTable.lookupAdaptationByPrimaryKey(sourceTable.computePrimaryKey(new Object[] { id }));
				final String connector = source == null ? null : source.getString(ConnectionCleanup.S_CONNECTOR);
				if (connector != null)
				{
					sourceConnector.put(id, connector);
				}
			}
		}
		return OrphanConnections.referencedIds(connectorRefs, sourceRefs, sourceConnector, steps);
	}

	private static boolean projectExists(final AdaptationTable pProjects, final Map<String, Boolean> pCache, final String pKey)
	{
		if (pKey == null || pKey.isEmpty())
		{
			return false;
		}
		Boolean known = pCache.get(pKey);
		if (known == null)
		{
			known = Boolean.valueOf(pProjects.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pKey)) != null);
			pCache.put(pKey, known);
		}
		return known.booleanValue();
	}

	private static void addIfSet(final Collection<String> pOut, final String pValue)
	{
		if (pValue != null && !pValue.trim().isEmpty())
		{
			pOut.add(pValue.trim());
		}
	}

	/** Gathers and judges one connection row: ConnectionCleanup's own reading, the plan's own rules. */
	private static Entry entryOf(final Adaptation pConnectors, final Adaptation pCatalog, final CatalogReader.Reader pReader, final Adaptation pRow)
	{
		final String id = pRow.getOccurrencePrimaryKey().format();
		final String label = pRow.getString(ConnectionCleanup.C_LABEL);
		final ConnectionFacts facts = new ConnectionFacts(id);
		OrphanConnections.claim(facts, ConnectionCleanup.sourceIdsNaming(pReader, pCatalog, id));
		ConnectionCleanup.readConnection(pConnectors, facts);
		ConnectionCleanup.readCatalog(pReader, pCatalog, facts);
		return new Entry(id, label, pRow, facts, OrphanConnections.decide(facts));
	}

	// ============================================================================================================ list

	/**
	 * The sample-file connections no project references, each with its catalogue source, what keeps it, and whether it can be removed now. Read-only.
	 *
	 * @param pNamePattern optional plain substring (case-insensitive, never a regex) tested on the connection id, its label and its catalogue source ids
	 */
	public static ObjectNode list(final Adaptation pFactory, final String pNamePattern)
	{
		final ObjectNode out = OrphanConnectionCleanup.MAPPER.createObjectNode();
		final Adaptation connectors = ConnectionCleanup.connectorsDataset(pFactory.getHome().getRepository());
		final ArrayNode orphans = out.putArray("orphans");
		int eligible = 0;
		int sampleFile = 0;
		int referenced = 0;
		if (connectors == null)
		{
			out.put("error", "The Connectors dataset is not available.");
		}
		else
		{
			final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
			final CatalogReader.Reader reader = ConnectionCleanup.reader();
			final Set<String> used = OrphanConnectionCleanup.referenced(pFactory, catalog);
			final RequestResult rows = connectors.getTable(ConnectionCleanup.CONNECTOR).createRequestResult("./type=" + CatalogReader.quote(OrphanConnections.SAMPLE_FILE));
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					sampleFile++;
					if (used.contains(row.getOccurrencePrimaryKey().format()))
					{
						referenced++;
						continue;
					}
					final Entry entry = OrphanConnectionCleanup.entryOf(connectors, catalog, reader, row);
					if (!OrphanConnections.nameMatches(pNamePattern, entry.id, entry.label, entry.facts.catalogSourceId, String.join(" ", entry.facts.otherSourceIds)))
					{
						continue;
					}
					if (entry.verdict.eligible)
					{
						eligible++;
					}
					orphans.add(OrphanConnectionCleanup.toJson(entry));
				}
			}
			finally
			{
				rows.close();
			}
		}
		out.put("count", orphans.size());
		out.put("eligibleCount", eligible);
		out.put("sampleFileConnections", sampleFile);
		out.put("referencedByProjects", referenced);
		out.put("namePattern", pNamePattern == null ? "" : pNamePattern.trim());
		out.put("confirmWith", String.valueOf(eligible));
		return out;
	}

	private static ObjectNode toJson(final Entry pEntry)
	{
		final ObjectNode node = OrphanConnectionCleanup.MAPPER.createObjectNode();
		final ConnectionFacts facts = pEntry.facts;
		node.put("id", pEntry.id);
		node.put("label", pEntry.label == null ? "" : pEntry.label);
		node.put("type", facts.connectionType);
		node.put("created", OrphanConnectionCleanup.iso(pEntry.row.getTimeOfCreation()));
		node.put("lastModified", OrphanConnectionCleanup.iso(pEntry.row.getTimeOfLastModification()));
		if (facts.catalogSourceId.isEmpty())
		{
			node.putNull("catalogSource");
		}
		else
		{
			node.put("catalogSource", facts.catalogSourceId);
		}
		final ArrayNode others = node.putArray("otherCatalogSources");
		for (final String id : facts.otherSourceIds)
		{
			others.add(id);
		}
		node.put("assets", facts.assets);
		node.put("columns", facts.columns);
		final ArrayNode flows = node.putArray("catalogFlows");
		for (final MappingRef mapping : facts.catalogFlows)
		{
			flows.add(mapping.id);
		}
		final ArrayNode blockers = node.putArray("blockers");
		for (final String blocker : pEntry.verdict.blockers)
		{
			blockers.add(blocker);
		}
		node.put("eligible", pEntry.verdict.eligible);
		if (pEntry.verdict.eligible)
		{
			node.putNull("reason");
		}
		else
		{
			node.put("reason", pEntry.verdict.reason);
		}
		return node;
	}

	private static String iso(final java.util.Date pDate)
	{
		return pDate == null ? "" : Instant.ofEpochMilli(pDate.getTime()).toString();
	}

	// ========================================================================================================== delete

	/** The answer of {@link #delete}: either a refusal (nothing deleted) or the per-id lines. */
	public static final class DeleteResult
	{
		/** Non-null: nothing was deleted, this is why (HTTP 409). */
		public final String refusal;
		public final ObjectNode json;

		DeleteResult(final String pRefusal, final ObjectNode pJson)
		{
			this.refusal = pRefusal;
			this.json = pJson;
		}
	}

	/**
	 * Re-gathers live, then deletes the eligible ids among {@code pIds} - catalogue flows ({@code connectors_mapping_delete}), catalogue source rows, connection
	 * rows, with {@link ConnectionCleanup}'s code. Refuses (nothing deleted) when {@code pConfirm} is not exactly the number of eligible ids sent.
	 */
	public static DeleteResult delete(final Session pSession, final Adaptation pFactory, final List<String> pIds, final String pConfirm)
	{
		final Adaptation connectors = ConnectionCleanup.connectorsDataset(pFactory.getHome().getRepository());
		if (connectors == null)
		{
			return new DeleteResult("The Connectors dataset is not available; nothing was deleted.", null);
		}
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		final CatalogReader.Reader reader = ConnectionCleanup.reader();
		final Set<String> used = OrphanConnectionCleanup.referenced(pFactory, catalog);
		final AdaptationTable table = connectors.getTable(ConnectionCleanup.CONNECTOR);
		final Map<String, Entry> toDelete = new LinkedHashMap<String, Entry>();
		final Map<String, String[]> settled = new LinkedHashMap<String, String[]>(); // id -> {result, message}
		for (final String id : pIds)
		{
			final Adaptation row = table.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { id }));
			if (row == null)
			{
				settled.put(id, new String[] { "ABSENT", "no connection with this id." });
				continue;
			}
			final String type = row.getString(ConnectionCleanup.C_TYPE);
			if (!OrphanConnections.SAMPLE_FILE.equals(type))
			{
				settled.put(id, new String[] { "KEPT", "a " + (type == null ? "" : type) + " connection: only sample-file connections are ever deleted here." });
				continue;
			}
			if (used.contains(id))
			{
				settled.put(id, new String[] { "KEPT", "a live project still references this connection (a source, a landing flow step, a distribution target): not an orphan." });
				continue;
			}
			final Entry entry = OrphanConnectionCleanup.entryOf(connectors, catalog, reader, row);
			if (entry.verdict.eligible)
			{
				toDelete.put(id, entry);
			}
			else
			{
				settled.put(id, new String[] { "KEPT", entry.verdict.reason });
			}
		}
		final String refusal = OrphanConnections.confirmRefusal(pConfirm, toDelete.size());
		if (refusal != null)
		{
			return new DeleteResult(refusal, null);
		}
		final String user = pSession.getUserReference().getUserId();
		for (final Map.Entry<String, Entry> one : toDelete.entrySet())
		{
			settled.put(one.getKey(), OrphanConnectionCleanup.deleteOne(pSession, pFactory, one.getValue(), user));
		}
		final ObjectNode out = OrphanConnectionCleanup.MAPPER.createObjectNode();
		final ArrayNode results = out.putArray("results");
		int deleted = 0;
		int kept = 0;
		int absent = 0;
		int failed = 0;
		for (final String id : pIds)
		{
			final String[] line = settled.get(id);
			final ObjectNode node = results.addObject();
			node.put("id", id);
			node.put("result", line[0]);
			if (line[1] != null && !line[1].isEmpty())
			{
				node.put("KEPT".equals(line[0]) ? "reason" : "message", line[1]);
			}
			if ("DELETED".equals(line[0]))
			{
				deleted++;
			}
			else if ("KEPT".equals(line[0]))
			{
				kept++;
			}
			else if ("ABSENT".equals(line[0]))
			{
				absent++;
			}
			else
			{
				failed++;
			}
		}
		out.put("deleted", deleted);
		out.put("kept", kept);
		out.put("absent", absent);
		out.put("failed", failed);
		return new DeleteResult(null, out);
	}

	/** The catalogue source (flows, rows) first, then the connection - ConnectionCleanup's two deletions; each re-reads live and may keep what it finds in use. @return {result, message} */
	private static String[] deleteOne(final Session pSession, final Adaptation pFactory, final Entry pEntry, final String pUser)
	{
		final ConnectionFacts facts = pEntry.facts;
		String sourceNote = "";
		try
		{
			if (!facts.catalogSourceId.isEmpty())
			{
				final GeneratedObjectDeleter.Outcome source = ConnectionCleanup.deleteCatalogSource(pSession, pFactory, facts);
				if (GeneratedObjectDeleter.RESULT_KEPT_IN_USE.equals(source.result))
				{
					return OrphanConnectionCleanup.logged(pUser, pEntry, new String[] { "KEPT", source.message });
				}
				if ("FAILED".equals(source.result))
				{
					return OrphanConnectionCleanup.logged(pUser, pEntry, new String[] { "FAILED", "catalogue source " + facts.catalogSourceId + ": " + source.message });
				}
				sourceNote = "catalogue source " + facts.catalogSourceId + ": " + (source.message == null ? "already gone" : source.message) + "; ";
			}
			final GeneratedObjectDeleter.Outcome connection = ConnectionCleanup.deleteConnection(pSession, pFactory, facts);
			if ("DELETED".equals(connection.result))
			{
				return OrphanConnectionCleanup.logged(pUser, pEntry, new String[] { "DELETED", sourceNote + "connection: " + connection.message });
			}
			if ("SKIPPED_ABSENT".equals(connection.result))
			{
				return OrphanConnectionCleanup.logged(pUser, pEntry, new String[] { "ABSENT", sourceNote + "the connection was already gone." });
			}
			if (GeneratedObjectDeleter.RESULT_KEPT_IN_USE.equals(connection.result))
			{
				return OrphanConnectionCleanup.logged(pUser, pEntry, new String[] { "KEPT", sourceNote + connection.message });
			}
			return OrphanConnectionCleanup.logged(pUser, pEntry, new String[] { "FAILED", sourceNote + "connection: " + connection.message });
		}
		catch (final OperationException ex)
		{
			return OrphanConnectionCleanup.logged(pUser, pEntry, new String[] { "FAILED", sourceNote + ex.getMessage() });
		}
		catch (final McpException ex)
		{
			return OrphanConnectionCleanup.logged(pUser, pEntry, new String[] { "FAILED", sourceNote + ex.getMessage() });
		}
		catch (final RuntimeException ex)
		{
			return OrphanConnectionCleanup.logged(pUser, pEntry, new String[] { "FAILED", sourceNote + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()) });
		}
	}

	/** One kernel.log line per attempted deletion: who, which connection, which catalogue source, what came of it. */
	private static String[] logged(final String pUser, final Entry pEntry, final String[] pLine)
	{
		LoggingCategory.getKernel().info("[mdm-factory] orphan connection cleanup by " + pUser + ": connection " + pEntry.id
			+ (pEntry.facts.catalogSourceId.isEmpty() ? "" : " (catalogue source " + pEntry.facts.catalogSourceId + ")") + " -> " + pLine[0]
			+ (pLine[1] == null || pLine[1].isEmpty() ? "" : ": " + pLine[1]));
		return pLine;
	}
}
