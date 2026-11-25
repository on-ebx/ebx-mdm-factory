package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot C (2026-10-17, "deleting a project must also remove the sample-file connections and catalogue sources it created"): how the factory KNOWS which
 * Connectors connection and which catalogue source it created for a project, as opposed to one the person picked from the existing catalogue or one
 * that was already there. No new table and no XSD field: the evidence is the project's own {@code PipelineStep} rows, in the same shape every other
 * "created by the factory" proof already has ({@code dataspace.created == true} in the {@code detail} JSON of a DONE step):
 * <ul>
 * <li>step {@link #STEP_CONNECTION} ({@code SOURCE_CONNECTION}), scope = the connection id, written by {@code ebx.SampleSourceImporter} right after it
 * writes the Connectors row for an uploaded sample file: {@code {"connection":{"id","type","created"}}} - {@code created} is true when the row did
 * not exist (the importer created it), false when it found one (a file of the same name was uploaded before, by this project or another);</li>
 * <li>step {@link #STEP_SOURCES_RUN} ({@code SOURCES_RUN}), scope = the connection id, the job that makes the connection a ready catalogue source:
 * {@code {"connection":{"id"},"catalogSource":{"id","created"}}} - {@code created} is true only when {@code connectors_catalog_source(apply=true)}
 * answered {@code action: CREATE} (no Source existed, it wrote one); a Source that was linked, or re-pointed, is recorded {@code created: false}.</li>
 * </ul>
 * A step of any status counts (a job that created the source and then failed to scan it must still be cleaned up); a step with no readable detail
 * proves nothing (an older step, or a crash before the detail was written): the object is then kept, never guessed to be the factory's. A picked
 * connection has no {@code SOURCE_CONNECTION} step at all. Pure (no EBX type), unit-tested ({@code ConnectionProvenanceTest}).
 */
public final class ConnectionProvenance
{
	public static final String STEP_CONNECTION = "SOURCE_CONNECTION";
	public static final String STEP_SOURCES_RUN = "SOURCES_RUN";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ConnectionProvenance()
	{
	}

	// ------------------------------------------------------------------------------------------------------ writing

	/** The {@code detail} of a {@code SOURCE_CONNECTION} step. */
	public static String connectionDetail(final String pConnectionId, final String pType, final boolean pCreated)
	{
		final ObjectNode root = ConnectionProvenance.MAPPER.createObjectNode();
		final ObjectNode connection = root.putObject("connection");
		connection.put("id", pConnectionId == null ? "" : pConnectionId);
		connection.put("type", pType == null ? "" : pType);
		connection.put("created", pCreated);
		return root.toString();
	}

	/** The {@code detail} of a {@code SOURCES_RUN} step; the {@code catalogSource} member is left out when the run never learned a source id. */
	public static String sourcesRunDetail(final String pConnectionId, final String pCatalogSourceId, final boolean pCatalogSourceCreated)
	{
		final ObjectNode root = ConnectionProvenance.MAPPER.createObjectNode();
		root.putObject("connection").put("id", pConnectionId == null ? "" : pConnectionId);
		if (pCatalogSourceId != null && !pCatalogSourceId.trim().isEmpty())
		{
			final ObjectNode source = root.putObject("catalogSource");
			source.put("id", pCatalogSourceId.trim());
			source.put("created", pCatalogSourceCreated);
		}
		return root.toString();
	}

	// ------------------------------------------------------------------------------------------------------ reading

	/** One {@code PipelineStep} row of the project, as far as this class reads it. */
	public static final class StepRow
	{
		public final String step;
		public final String scope;
		public final String detail;

		public StepRow(final String pStep, final String pScope, final String pDetail)
		{
			this.step = pStep == null ? "" : pStep;
			this.scope = pScope == null ? "" : pScope;
			this.detail = pDetail == null ? "" : pDetail;
		}
	}

	/** What the steps prove about one connection id. */
	public static final class Evidence
	{
		public final String connectionId;
		/** A {@code SOURCE_CONNECTION} step names the connection. */
		public boolean connectionSeen;
		/** One of them recorded {@code created: true}. */
		public boolean connectionCreated;
		public String connectionType = "";
		/** The catalogue source a {@code SOURCES_RUN} step recorded, {@code ""} when none; the one recorded CREATED wins over a linked one. */
		public String catalogSourceId = "";
		public boolean catalogSourceCreated;

		Evidence(final String pConnectionId)
		{
			this.connectionId = pConnectionId;
		}

		/** Whether the plan has anything to say about this connection: the factory created it, found it through an import, or created a catalogue source on it. */
		public boolean isCandidate()
		{
			return this.connectionSeen || this.catalogSourceCreated;
		}
	}

	/** The evidence per connection id, in the order the steps are given. Never throws: an unreadable detail proves nothing. */
	public static Map<String, Evidence> read(final List<StepRow> pSteps)
	{
		final Map<String, Evidence> out = new LinkedHashMap<String, Evidence>();
		for (final StepRow row : pSteps)
		{
			final boolean connectionStep = ConnectionProvenance.STEP_CONNECTION.equals(row.step);
			final boolean runStep = ConnectionProvenance.STEP_SOURCES_RUN.equals(row.step);
			if (!connectionStep && !runStep)
			{
				continue;
			}
			final JsonNode detail = ConnectionProvenance.parse(row.detail);
			if (detail == null)
			{
				continue; // no readable detail: proves nothing
			}
			String id = detail.path("connection").path("id").asText("").trim();
			if (id.isEmpty())
			{
				id = row.scope.trim();
			}
			if (id.isEmpty())
			{
				continue;
			}
			Evidence evidence = out.get(id);
			if (evidence == null)
			{
				evidence = new Evidence(id);
				out.put(id, evidence);
			}
			if (connectionStep)
			{
				evidence.connectionSeen = true;
				if (detail.path("connection").path("created").asBoolean(false))
				{
					evidence.connectionCreated = true;
				}
				final String type = detail.path("connection").path("type").asText("").trim();
				if (!type.isEmpty())
				{
					evidence.connectionType = type;
				}
			}
			else
			{
				final String sourceId = detail.path("catalogSource").path("id").asText("").trim();
				if (sourceId.isEmpty())
				{
					continue;
				}
				final boolean created = detail.path("catalogSource").path("created").asBoolean(false);
				if (created)
				{
					evidence.catalogSourceId = sourceId;
					evidence.catalogSourceCreated = true;
				}
				else if (!evidence.catalogSourceCreated && evidence.catalogSourceId.isEmpty())
				{
					evidence.catalogSourceId = sourceId;
				}
			}
		}
		return out;
	}

	private static JsonNode parse(final String pDetail)
	{
		if (pDetail == null || pDetail.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final JsonNode node = ConnectionProvenance.MAPPER.readTree(pDetail);
			return node != null && node.isObject() ? node : null;
		}
		catch (final java.io.IOException ex)
		{
			return null;
		}
	}

	// ------------------------------------------------------------------------------------------------ the catalogue's flows

	/**
	 * Is {@code pLabel} the label of one of the catalogue's own flows of source {@code pSourceId}? The catalogue creates them from the Source row
	 * ({@code CatalogFlows}): {@code Catalog scan: ID}, {@code Catalog scan columns: ID}, {@code Catalog profile: ID}, {@code Catalog sample: ID},
	 * {@code Catalog glossary: ID}, {@code Catalog usage: ID}, {@code Catalog lineage: ID}, {@code Catalog jobs: ID}. The test is the shape
	 * {@code "Catalog " + <lower-case words> + ": " + id}, the id compared exactly - never a prefix test, so source {@code X} does not claim the flows of {@code X_2}.
	 */
	public static boolean isCatalogFlowLabel(final String pLabel, final String pSourceId)
	{
		if (pLabel == null || pSourceId == null || pSourceId.trim().isEmpty())
		{
			return false;
		}
		final String suffix = ": " + pSourceId.trim();
		if (!pLabel.startsWith("Catalog ") || !pLabel.endsWith(suffix))
		{
			return false;
		}
		final String kind = pLabel.substring("Catalog ".length(), pLabel.length() - suffix.length());
		return !kind.isEmpty() && kind.matches("[a-z]+( [a-z]+)*");
	}

	// ------------------------------------------------------------------------------------------------ predicates

	/**
	 * {@code ./field='a' or ./field='b' ...} in chunks of at most {@code pSize} values (EBX xpath has no IN operator; a very long OR is slow to parse), one
	 * predicate per chunk. A quote inside a value is doubled. No values: no chunk.
	 */
	public static List<String> orChunks(final String pFieldPath, final List<String> pValues, final int pSize)
	{
		final List<String> out = new ArrayList<String>();
		final int size = Math.max(1, pSize);
		for (int start = 0; start < pValues.size(); start += size)
		{
			final StringBuilder sb = new StringBuilder();
			for (int i = start; i < Math.min(pValues.size(), start + size); i++)
			{
				sb.append(sb.length() == 0 ? "" : " or ").append(pFieldPath).append("='").append(pValues.get(i).replace("'", "''")).append("'");
			}
			out.add(sb.toString());
		}
		return out;
	}
}
