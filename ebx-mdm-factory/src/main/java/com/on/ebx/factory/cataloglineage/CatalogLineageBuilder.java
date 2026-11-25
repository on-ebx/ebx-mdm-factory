package com.on.ebx.factory.cataloglineage;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import com.on.ebx.factory.master.MasterFieldNameRule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.review.ProposalWording;
import com.on.ebx.factory.staging.TechnicalKeyRule;

/**
 * Turns one project's factory tables (already read into the plain shapes below) into the OpenLineage
 * {@code JobEvent} declarations the data catalog's {@code POST /ebx-data-catalog/rest/v1/openlineage}
 * accepts (`docs/CATALOG-LINEAGE.md`, PLAN.md "la factory declare au catalogue... le lineage de
 * STRUCTURE"). PURE - no EBX class anywhere in this file, unit-tested without a running instance, exactly
 * like {@code master.MasterModel}/{@code lineage.RecordLineage}.
 * <p>
 * <b>Three families of jobs, one per zone crossing</b> (`docs/CATALOG-LINEAGE.md` &sect;3):
 * <ul>
 * <li>{@code landing:<project>:<sourceSystem>:<landingTable>} - one job per landing table, column-to-column
 * 1:1 from the scanned source table ({@code LandingTable.sourceObject}, the catalog's own {@code
 * qualifiedName} for it - CLAUDE.md's "names must match" rule is satisfied by construction here, since this
 * class never invents a name, it only carries through what {@code ebx.CatalogLineageDeclarer} read off the
 * factory's own tables) to the landing field.</li>
 * <li>{@code staging:<project>:<entity>} - one job per {@code StagingEntity} (promoted or not: a member
 * entity such as {@code Supplier} still has its own staging table and deserves its own lineage), one
 * column-lineage entry per business attribute, one input field per ACCEPTED-or-{@code APPLIED} {@code
 * AttributeMapping} that feeds it (a canonical/LLM entity can have several, one per contributing source -
 * {@code AttributeMapping.landingColumn} always names a LANDING column directly, never another staging
 * table, confirmed by {@code ebx.RecordLineageReader.readFieldOrigins}, so there is no intermediate
 * "member's own staging table" node in this chain even for a merged entity).</li>
 * <li>{@code master:<project>:<entity>} - one job per PROMOTED entity, one column-lineage entry per business
 * attribute, always {@code IDENTITY} ({@code ebx.MasterLoader} copies the promoted entity's own staging row
 * as-is - no cleaning happens at this crossing).</li>
 * </ul>
 * <b>Only ACCEPTED-or-{@code APPLIED} rows are ever declared</b> ({@link TechnicalKeyRule#isAcceptedLike}):
 * a {@code PROPOSED} or {@code REJECTED} {@code AttributeMapping}/{@code MappingStep} never appears in a
 * column-lineage entry. Because a declaration is the job's WHOLE current lineage (the catalog's importer
 * retires whatever an earlier declaration listed and this one does not -
 * {@code OpenLineageImporter.retireEdges}, read in full, `docs/CATALOG-LINEAGE.md` &sect;2), a mapping that
 * moves from {@code ACCEPTED} to {@code REJECTED} between two declare calls is retired automatically the
 * next time this class runs over the same project - nothing here has to detect or special-case the
 * transition itself, which is also why {@code ebx.CatalogLineageDeclarer} always declares an entity's WHOLE
 * current mapping set, never a delta.
 * <p>
 * <b>Transformation text: pipeline BEFORE the mapping's own transformation</b> (task brief; confirmed the
 * loader's real order by {@code ebx.StagingLoader} lines ~723-730: {@code CleaningSteps.applyPipeline} runs
 * on the raw landing value, THEN {@code Transformations.apply} runs on the cleaned value) - {@link
 * #transformationWords} joins the cleaning-step words first, the mapping's own transformation word last.
 * Cleaning-step wording is {@link ProposalWording#mappingStepProposition(String, String, Integer)} (position
 * {@code null} so the "Step N:" prefix - meaningless outside the Proposal Review screen - is omitted): the
 * SAME wording a reviewer already reads, never a second copy of it (task brief: "reuse review.ProposalWording,
 * do not duplicate wording"). The mapping's OWN {@code transformation} (a different, smaller vocabulary - NONE/
 * TRIM/UPPER/LOWER/PARSE_DATE/PARSE_DECIMAL/PARSE_BOOLEAN/TRANSCODE/CONSTANT, see {@code
 * landing.Transformations#apply}) has no {@code ProposalWording} entry of its own (only {@code MappingStep}
 * kinds and {@code QualityRule} kinds do) - this class renders it the same plain way {@code
 * ProposalReviews.readMappings} already shows it inline (kind, then {@code " (" + argument + ")"} when an
 * argument is set), rather than inventing new copy for a vocabulary nobody asked to have wording for.
 * <p>
 * <b>Determinism</b> (task brief: "idempotent output, same input -&gt; byte-identical declarations, stable
 * ordering"): every list this class iterates is sorted by a stable key before being written (source system,
 * table name, attribute name, then contributing source system) regardless of the order the caller's own
 * table reads happened to produce, and {@link #build} takes the event time as a parameter rather than reading
 * the clock itself - two calls with the same {@link ProjectInput} and the same {@link Date} produce
 * byte-identical JSON, which is exactly what {@code CatalogLineageBuilderTest} checks.
 */
public final class CatalogLineageBuilder
{
	/**
	 * The namespace of this EBX instance when nothing else is configured: the default of {@code ebx-connectors}'
	 * own {@code openlineage} connector property {@code ebx.namespace}, so both modules name EBX the same way.
	 */
	public static final String DEFAULT_EBX_NAMESPACE = "ebx://localhost:8080";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Same convention as {@code ebx-connectors}' {@code OpenLineageEmitter} (CLAUDE.md &sect;7/8 style: a producer URI naming the repository), this module's own GitHub repository (memory: {@code mdm-factory-github.md}). */
	public static final String PRODUCER = "https://github.com/on-ebx/ebx-mdm-factory";
	static final String JOB_EVENT_SCHEMA_URL = "https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/JobEvent";
	/** Every job this class writes shares one namespace, distinct from {@code ebx-connectors}' own {@code job.namespace} ({@code ebx-connectors} by default) so the two modules' declared jobs never collide or retire each other's edges. */
	public static final String JOB_NAMESPACE = "mdm-factory";

	private CatalogLineageBuilder()
	{
	}

	// ============================================================================================= pure input shapes

	/** One 1:1 source-column -&gt; landing-field pair (source table -&gt; landing table crossing is always a plain copy: no cleaning happens before landing). */
	public static final class SourceColumn
	{
		public final String sourceColumn;
		public final String landingField;

		public SourceColumn(final String pSourceColumn, final String pLandingField)
		{
			this.sourceColumn = pSourceColumn;
			this.landingField = pLandingField;
		}
	}

	/** One landing table of one project source, with the scanned source table it was planned from. */
	public static final class LandingTableInput
	{
		public final String sourceSystem;
		/** The catalog's own {@code qualifiedName} for the scanned source table ({@code LandingTable.sourceObject}) - never rebuilt here, only carried through. */
		public final String sourceObjectQualifiedName;
		/** {@code LandingTable.tableName}: the EBX table name within the source's own landing dataset. */
		public final String landingTableName;
		public final List<SourceColumn> columns;

		public LandingTableInput(final String pSourceSystem, final String pSourceObjectQualifiedName, final String pLandingTableName, final List<SourceColumn> pColumns)
		{
			this.sourceSystem = pSourceSystem;
			this.sourceObjectQualifiedName = pSourceObjectQualifiedName;
			this.landingTableName = pLandingTableName;
			this.columns = pColumns == null ? new ArrayList<SourceColumn>() : pColumns;
		}
	}

	/** One project source's own landing dataset, holding one or more landing tables. */
	public static final class SourceZone
	{
		public final String sourceSystem;
		/** {@code ProjectSource.landingDataset}: the dataset name within the project's shared landing dataspace. */
		public final String landingDatasetName;
		public final List<LandingTableInput> tables;

		public SourceZone(final String pSourceSystem, final String pLandingDatasetName, final List<LandingTableInput> pTables)
		{
			this.sourceSystem = pSourceSystem;
			this.landingDatasetName = pLandingDatasetName;
			this.tables = pTables == null ? new ArrayList<LandingTableInput>() : pTables;
		}
	}

	/** One ACCEPTED-or-{@code APPLIED} {@code MappingStep}, kind + argument only (a reviewer-facing kind wording, never the raw JSON argument shape a kind like {@code TRANSCODE_REFERENCE} carries). */
	public static final class CleaningStep
	{
		public final String kind;
		public final String argument;

		public CleaningStep(final String pKind, final String pArgument)
		{
			this.kind = pKind;
			this.argument = pArgument;
		}
	}

	/** One ACCEPTED-or-{@code APPLIED} {@code AttributeMapping}: which landing column feeds this attribute, through which cleaning pipeline and transformation. */
	public static final class AttributeMappingInput
	{
		public final String sourceSystem;
		public final String landingTableName;
		public final String landingField;
		/** {@code AttributeMapping.transformation} ({@code NONE}/{@code TRIM}/{@code UPPER}/{@code LOWER}/{@code PARSE_DATE}/{@code PARSE_DECIMAL}/{@code PARSE_BOOLEAN}/{@code TRANSCODE}/{@code CONSTANT}). */
		public final String transformationKind;
		public final String transformationArgument;
		/** In {@code position} order - the caller is expected to have sorted/filtered these already (accepted-like only). */
		public final List<CleaningStep> steps;

		public AttributeMappingInput(final String pSourceSystem, final String pLandingTableName, final String pLandingField, final String pTransformationKind,
			final String pTransformationArgument, final List<CleaningStep> pSteps)
		{
			this.sourceSystem = pSourceSystem;
			this.landingTableName = pLandingTableName;
			this.landingField = pLandingField;
			this.transformationKind = pTransformationKind;
			this.transformationArgument = pTransformationArgument;
			this.steps = pSteps == null ? new ArrayList<CleaningStep>() : pSteps;
		}
	}

	/** One staging/master business attribute (excluding the technical primary key), and every ACCEPTED-or-{@code APPLIED} mapping that feeds it. */
	public static final class AttributeInput
	{
		public final String name;
		public final List<AttributeMappingInput> mappings;

		public AttributeInput(final String pName, final List<AttributeMappingInput> pMappings)
		{
			this.name = pName;
			this.mappings = pMappings == null ? new ArrayList<AttributeMappingInput>() : pMappings;
		}
	}

	/** One ACCEPTED-or-{@code APPLIED} {@code StagingEntity}. */
	public static final class EntityInput
	{
		public final String name;
		/** True when this entity has its own table in the master dataset ({@code master.MasterModel}: promoted unless it is a member of a canonical entity). */
		public final boolean promoted;
		public final List<AttributeInput> attributes;

		public EntityInput(final String pName, final boolean pPromoted, final List<AttributeInput> pAttributes)
		{
			this.name = pName;
			this.promoted = pPromoted;
			this.attributes = pAttributes == null ? new ArrayList<AttributeInput>() : pAttributes;
		}
	}

	/** Everything one project's declaration needs, already read out of the factory's own tables as plain values. */
	public static final class ProjectInput
	{
		public final String projectCode;
		/** This EBX instance's OpenLineage namespace ({@code ebx://host:port}) - see {@code ebx.CatalogLineageDeclarer} for how it is derived; a mismatch with the catalog's own {@code Source.lineageNamespace} degrades gracefully (`docs/CATALOG-LINEAGE.md` &sect;2.3), it is not required to be exact for the edges to attach. */
		public final String ebxNamespace;
		public final String landingDataspace;
		public final List<SourceZone> sources;
		public final String stagingDataspace;
		public final String stagingDataset;
		public final List<EntityInput> entities;
		/** Null/blank when the project has no master zone yet ({@code Project.masterDataspace}/{@code masterDataset} unset): {@link #build} then produces no {@code master:*} job. */
		public final String masterDataspace;
		public final String masterDataset;

		public ProjectInput(final String pProjectCode, final String pEbxNamespace, final String pLandingDataspace, final List<SourceZone> pSources,
			final String pStagingDataspace, final String pStagingDataset, final List<EntityInput> pEntities, final String pMasterDataspace, final String pMasterDataset)
		{
			this.projectCode = pProjectCode;
			this.ebxNamespace = pEbxNamespace;
			this.landingDataspace = pLandingDataspace;
			this.sources = pSources == null ? new ArrayList<SourceZone>() : pSources;
			this.stagingDataspace = pStagingDataspace;
			this.stagingDataset = pStagingDataset;
			this.entities = pEntities == null ? new ArrayList<EntityInput>() : pEntities;
			this.masterDataspace = pMasterDataspace;
			this.masterDataset = pMasterDataset;
		}
	}

	// =================================================================================================== output

	/** Per-family counts, for the {@code PipelineStep.detail} the EBX-side declarer writes. */
	public static final class Counts
	{
		public int landingJobs;
		public int stagingJobs;
		public int masterJobs;
		public int columnLinks;
		public int skipped;
	}

	public static final class Result
	{
		/** One OpenLineage {@code JobEvent} per job, ready to POST (as a JSON array, one request) to {@code /ebx-data-catalog/rest/v1/openlineage}. */
		public final List<ObjectNode> events;
		public final Counts counts;
		/** {@code "<what>: <why>"} - a mapping/table this class chose not to declare, and why (never silent - task brief). */
		public final List<String> notes;

		Result(final List<ObjectNode> pEvents, final Counts pCounts, final List<String> pNotes)
		{
			this.events = pEvents;
			this.counts = pCounts;
			this.notes = pNotes;
		}
	}

	// ==================================================================================================== build

	public static Result build(final ProjectInput pInput, final Date pWhen)
	{
		final List<ObjectNode> events = new ArrayList<ObjectNode>();
		final Counts counts = new Counts();
		final List<String> notes = new ArrayList<String>();
		final String when = CatalogLineageBuilder.iso(pWhen);
		final String ebxNamespace = CatalogLineageBuilder.blank(pInput.ebxNamespace) ? CatalogLineageBuilder.DEFAULT_EBX_NAMESPACE : pInput.ebxNamespace.trim();

		// ------------------------------------------------------------------------------------------ landing jobs
		final List<SourceZone> sources = new ArrayList<SourceZone>(pInput.sources);
		Collections.sort(sources, new Comparator<SourceZone>()
		{
			@Override
			public int compare(final SourceZone pLeft, final SourceZone pRight)
			{
				return CatalogLineageBuilder.orEmpty(pLeft.sourceSystem).compareTo(CatalogLineageBuilder.orEmpty(pRight.sourceSystem));
			}
		});
		for (final SourceZone source : sources)
		{
			final String sourceNamespace = "mdm-factory-source://" + CatalogLineageBuilder.orEmpty(source.sourceSystem);
			final List<LandingTableInput> tables = new ArrayList<LandingTableInput>(source.tables);
			Collections.sort(tables, new Comparator<LandingTableInput>()
			{
				@Override
				public int compare(final LandingTableInput pLeft, final LandingTableInput pRight)
				{
					return CatalogLineageBuilder.orEmpty(pLeft.landingTableName).compareTo(CatalogLineageBuilder.orEmpty(pRight.landingTableName));
				}
			});
			for (final LandingTableInput table : tables)
			{
				if (CatalogLineageBuilder.blank(table.sourceObjectQualifiedName) || CatalogLineageBuilder.blank(table.landingTableName))
				{
					notes.add("landing table " + CatalogLineageBuilder.orEmpty(table.landingTableName) + " (" + source.sourceSystem
						+ "): no source object or no EBX table name yet - not declared");
					counts.skipped++;
					continue;
				}
				final String outputName = CatalogLineageBuilder.ebxName(pInput.landingDataspace, source.landingDatasetName, table.landingTableName);
				final ObjectNode fields = CatalogLineageBuilder.MAPPER.createObjectNode();
				int links = 0;
				final List<SourceColumn> columns = new ArrayList<SourceColumn>(table.columns);
				Collections.sort(columns, new Comparator<SourceColumn>()
				{
					@Override
					public int compare(final SourceColumn pLeft, final SourceColumn pRight)
					{
						return CatalogLineageBuilder.orEmpty(pLeft.landingField).compareTo(CatalogLineageBuilder.orEmpty(pRight.landingField));
					}
				});
				for (final SourceColumn column : columns)
				{
					if (CatalogLineageBuilder.blank(column.sourceColumn) || CatalogLineageBuilder.blank(column.landingField))
					{
						continue;
					}
					final ObjectNode spec = fields.putObject(column.landingField);
					final ArrayNode inputFields = spec.putArray("inputFields");
					final ObjectNode inField = inputFields.addObject();
					inField.put("namespace", sourceNamespace);
					inField.put("name", table.sourceObjectQualifiedName);
					inField.put("field", column.sourceColumn);
					final ArrayNode transformations = inField.putArray("transformations");
					final ObjectNode t = transformations.addObject();
					t.put("type", "DIRECT");
					t.put("subtype", "IDENTITY");
					t.put("masking", false);
					links++;
				}
				if (links == 0)
				{
					notes.add("landing table " + table.landingTableName + " (" + source.sourceSystem + "): no usable column pair - not declared");
					counts.skipped++;
					continue;
				}
				final String description = "MDM Factory: landing table " + table.landingTableName + " loaded from " + table.sourceObjectQualifiedName + " (" + source.sourceSystem
					+ "), " + links + " column(s), 1:1.";
				events.add(CatalogLineageBuilder.jobEvent(when, CatalogLineageBuilder.JOB_NAMESPACE,
					"landing:" + pInput.projectCode + ":" + source.sourceSystem + ":" + table.landingTableName, description, sourceNamespace,
					table.sourceObjectQualifiedName, ebxNamespace, outputName, fields));
				counts.landingJobs++;
				counts.columnLinks += links;
			}
		}

		// ------------------------------------------------------------------------------------------ staging jobs
		final List<EntityInput> entities = new ArrayList<EntityInput>(pInput.entities);
		Collections.sort(entities, new Comparator<EntityInput>()
		{
			@Override
			public int compare(final EntityInput pLeft, final EntityInput pRight)
			{
				return CatalogLineageBuilder.orEmpty(pLeft.name).compareTo(CatalogLineageBuilder.orEmpty(pRight.name));
			}
		});
		for (final EntityInput entity : entities)
		{
			if (CatalogLineageBuilder.blank(pInput.stagingDataspace) || CatalogLineageBuilder.blank(pInput.stagingDataset))
			{
				notes.add("entity " + entity.name + ": project has no staging zone yet - not declared");
				counts.skipped++;
				continue;
			}
			final String outputName = CatalogLineageBuilder.ebxName(pInput.stagingDataspace, pInput.stagingDataset, entity.name);
			final ObjectNode fields = CatalogLineageBuilder.MAPPER.createObjectNode();
			int links = 0;
			final List<AttributeInput> attributes = new ArrayList<AttributeInput>(entity.attributes);
			Collections.sort(attributes, new Comparator<AttributeInput>()
			{
				@Override
				public int compare(final AttributeInput pLeft, final AttributeInput pRight)
				{
					return CatalogLineageBuilder.orEmpty(pLeft.name).compareTo(CatalogLineageBuilder.orEmpty(pRight.name));
				}
			});
			for (final AttributeInput attribute : attributes)
			{
				final List<AttributeMappingInput> mappings = new ArrayList<AttributeMappingInput>(attribute.mappings);
				Collections.sort(mappings, new Comparator<AttributeMappingInput>()
				{
					@Override
					public int compare(final AttributeMappingInput pLeft, final AttributeMappingInput pRight)
					{
						return CatalogLineageBuilder.orEmpty(pLeft.sourceSystem).compareTo(CatalogLineageBuilder.orEmpty(pRight.sourceSystem));
					}
				});
				ObjectNode spec = null;
				for (final AttributeMappingInput mapping : mappings)
				{
					if (CatalogLineageBuilder.blank(mapping.landingTableName) || CatalogLineageBuilder.blank(mapping.landingField))
					{
						notes.add("entity " + entity.name + ", attribute " + attribute.name + " (" + mapping.sourceSystem
							+ "): no landing column resolved - not declared");
						counts.skipped++;
						continue;
					}
					if (spec == null)
					{
						spec = fields.putObject(attribute.name);
						spec.putArray("inputFields");
					}
					final String landingAssetName = CatalogLineageBuilder.ebxName(pInput.landingDataspace, CatalogLineageBuilder.landingDatasetOf(pInput, mapping.sourceSystem),
						mapping.landingTableName);
					final ObjectNode inField = ((ArrayNode) spec.get("inputFields")).addObject();
					inField.put("namespace", ebxNamespace);
					inField.put("name", landingAssetName);
					inField.put("field", mapping.landingField);
					final String words = CatalogLineageBuilder.transformationWords(mapping);
					final ArrayNode transformations = inField.putArray("transformations");
					final ObjectNode t = transformations.addObject();
					t.put("type", "DIRECT");
					t.put("subtype", words == null ? "IDENTITY" : "TRANSFORMATION");
					if (words != null)
					{
						t.put("description", words);
					}
					t.put("masking", false);
					links++;
				}
			}
			if (links == 0)
			{
				notes.add("entity " + entity.name + ": no usable ACCEPTED-or-APPLIED mapping - not declared");
				counts.skipped++;
				continue;
			}
			final String description = "MDM Factory: staging entity " + entity.name + " built from " + links + " column mapping(s).";
			events.add(CatalogLineageBuilder.jobEvent(when, CatalogLineageBuilder.JOB_NAMESPACE, "staging:" + pInput.projectCode + ":" + entity.name, description, null, null,
				ebxNamespace, outputName, fields));
			counts.stagingJobs++;
			counts.columnLinks += links;
		}

		// ------------------------------------------------------------------------------------------- master jobs
		for (final EntityInput entity : entities)
		{
			if (!entity.promoted)
			{
				continue;
			}
			if (CatalogLineageBuilder.blank(pInput.masterDataspace) || CatalogLineageBuilder.blank(pInput.masterDataset)
				|| CatalogLineageBuilder.blank(pInput.stagingDataspace) || CatalogLineageBuilder.blank(pInput.stagingDataset))
			{
				notes.add("entity " + entity.name + ": project has no master zone yet - not declared");
				counts.skipped++;
				continue;
			}
			final String inputName = CatalogLineageBuilder.ebxName(pInput.stagingDataspace, pInput.stagingDataset, entity.name);
			final String outputName = CatalogLineageBuilder.ebxName(pInput.masterDataspace, pInput.masterDataset, entity.name);
			final ObjectNode fields = CatalogLineageBuilder.MAPPER.createObjectNode();
			int links = 0;
			final List<AttributeInput> attributes = new ArrayList<AttributeInput>(entity.attributes);
			Collections.sort(attributes, new Comparator<AttributeInput>()
			{
				@Override
				public int compare(final AttributeInput pLeft, final AttributeInput pRight)
				{
					return CatalogLineageBuilder.orEmpty(pLeft.name).compareTo(CatalogLineageBuilder.orEmpty(pRight.name));
				}
			});
			// the OUTPUT field is the MASTER field name (master.MasterFieldNameRule, 2026-09-23: a business attribute
			// named like the table's own id/sourceSystem/sourceId/_loadId/_receivedAt is promoted as "<name>_src"),
			// the INPUT field stays the staging attribute's own name - the value is still copied unchanged
			final List<String> attributeNames = new ArrayList<String>();
			for (final AttributeInput attribute : attributes)
			{
				attributeNames.add(attribute.name);
			}
			for (final AttributeInput attribute : attributes)
			{
				final String masterField = MasterFieldNameRule.masterFieldName(attribute.name, attributeNames);
				final ObjectNode spec = fields.putObject(masterField);
				final ArrayNode inputFields = spec.putArray("inputFields");
				final ObjectNode inField = inputFields.addObject();
				inField.put("namespace", ebxNamespace);
				inField.put("name", inputName);
				inField.put("field", attribute.name);
				final ArrayNode transformations = inField.putArray("transformations");
				final ObjectNode t = transformations.addObject();
				t.put("type", "DIRECT");
				t.put("subtype", "IDENTITY");
				if (!masterField.equals(attribute.name))
				{
					t.put("description", "Promoted as " + masterField + ": the attribute name collides with a field every master table carries.");
				}
				t.put("masking", false);
				links++;
			}
			if (links == 0)
			{
				notes.add("entity " + entity.name + ": no business attribute to promote - not declared");
				counts.skipped++;
				continue;
			}
			final String description = "MDM Factory: master table " + entity.name + " loaded from its own staging entity, " + links + " field(s), identity copy.";
			events.add(CatalogLineageBuilder.jobEvent(when, CatalogLineageBuilder.JOB_NAMESPACE, "master:" + pInput.projectCode + ":" + entity.name, description, ebxNamespace,
				inputName, ebxNamespace, outputName, fields));
			counts.masterJobs++;
			counts.columnLinks += links;
		}

		return new Result(events, counts, notes);
	}

	// =================================================================================================== wording

	/**
	 * Pipeline (cleaning steps, {@link ProposalWording#mappingStepProposition}, position {@code null} - no
	 * "Step N:" prefix) BEFORE the mapping's own transformation (plain {@code kind (argument)}, {@code
	 * ProposalReviews.readMappings}'s own convention - see class Javadoc); {@code null} when there is nothing
	 * to say (no accepted step, transformation {@code NONE}/blank) - the caller then declares {@code IDENTITY}.
	 */
	static String transformationWords(final AttributeMappingInput pMapping)
	{
		final List<String> parts = new ArrayList<String>();
		for (final CleaningStep step : pMapping.steps)
		{
			parts.add(ProposalWording.mappingStepProposition(step.kind, step.argument, null));
		}
		final String kind = pMapping.transformationKind == null ? "" : pMapping.transformationKind.trim().toUpperCase(Locale.ROOT);
		if (!kind.isEmpty() && !"NONE".equals(kind))
		{
			final String argument = pMapping.transformationArgument == null ? "" : pMapping.transformationArgument.trim();
			parts.add(argument.isEmpty() ? kind : kind + " (" + argument + ")");
		}
		if (parts.isEmpty())
		{
			return null;
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < parts.size(); i++)
		{
			if (i > 0)
			{
				out.append("; ");
			}
			out.append(parts.get(i));
		}
		return out.toString();
	}

	// ===================================================================================================== json

	/**
	 * One {@code JobEvent} (no {@code run}, no {@code eventType} - a declaration, per {@code
	 * OpenLineageEmitter.declaration}'s own shape in {@code ebx-connectors}, followed here rather than
	 * reinvented). {@code pExtraInputNamespace}/{@code pExtraInputName} is an additional plain (non-column-level)
	 * input dataset to list ({@code inputs[]}) alongside the output - purely informative, since the catalog's
	 * importer resolves every edge from {@code columnLineage.fields[].inputFields[]} directly, never from
	 * {@code inputs[]} when column-level data is present ({@code OpenLineageImporter.importOne}, read in full:
	 * the plain {@code inputs} fallback only fires when NO column lineage was given at all) - both null when
	 * the job's only meaningful input is already named field-by-field (the staging job, which can read from
	 * several different landing tables at once).
	 */
	private static ObjectNode jobEvent(final String pWhen, final String pJobNamespace, final String pJobName, final String pDescription,
		final String pExtraInputNamespace, final String pExtraInputName, final String pOutputNamespace, final String pOutputName,
		final ObjectNode pColumnLineageFields)
	{
		final ObjectNode event = CatalogLineageBuilder.MAPPER.createObjectNode();
		event.put("eventTime", pWhen);
		event.put("producer", CatalogLineageBuilder.PRODUCER);
		event.put("schemaURL", CatalogLineageBuilder.JOB_EVENT_SCHEMA_URL);

		final ObjectNode job = event.putObject("job");
		job.put("namespace", pJobNamespace);
		job.put("name", pJobName);
		final ObjectNode jobFacets = job.putObject("facets");
		final ObjectNode doc = CatalogLineageBuilder.facet(jobFacets, "documentation");
		doc.put("description", pDescription);
		final ObjectNode jobType = CatalogLineageBuilder.facet(jobFacets, "jobType");
		jobType.put("processingType", "BATCH");
		jobType.put("integration", "MDM_FACTORY");
		jobType.put("jobType", "TRANSFORMATION");

		final ArrayNode inputs = event.putArray("inputs");
		if (pExtraInputNamespace != null && pExtraInputName != null)
		{
			final ObjectNode in = inputs.addObject();
			in.put("namespace", pExtraInputNamespace);
			in.put("name", pExtraInputName);
		}
		final ArrayNode outputs = event.putArray("outputs");
		final ObjectNode out = outputs.addObject();
		out.put("namespace", pOutputNamespace);
		out.put("name", pOutputName);
		if (pColumnLineageFields != null && pColumnLineageFields.size() > 0)
		{
			final ObjectNode facets = out.putObject("facets");
			final ObjectNode columnLineage = CatalogLineageBuilder.facet(facets, "columnLineage");
			columnLineage.set("fields", pColumnLineageFields);
		}
		return event;
	}

	private static ObjectNode facet(final ObjectNode pHolder, final String pName)
	{
		final ObjectNode facet = pHolder.putObject(pName);
		facet.put("_producer", CatalogLineageBuilder.PRODUCER);
		facet.put("_schemaURL", "https://openlineage.io/spec/facets/1-0-0/" + CatalogLineageBuilder.facetSchemaName(pName) + ".json");
		return facet;
	}

	private static String facetSchemaName(final String pName)
	{
		if ("documentation".equals(pName))
		{
			return "DocumentationJobFacet";
		}
		if ("jobType".equals(pName))
		{
			return "JobTypeJobFacet";
		}
		if ("columnLineage".equals(pName))
		{
			return "ColumnLineageDatasetFacet";
		}
		return pName;
	}

	// ==================================================================================================== helpers

	/** {@code <dataspace>/<dataset><tablePath>} - the exact convention {@code ebx-connectors}' {@code
	 * OpenLineageEmitter.ebxName}/{@code EbxCatalog} use, so an edge attaches to the asset the catalog's own
	 * EBX scan already created rather than a lookalike (`docs/CATALOG-LINEAGE.md` &sect;2.4). */
	static String ebxName(final String pDataspace, final String pDataset, final String pTableName)
	{
		final String dataspace = pDataspace == null ? "" : pDataspace.trim();
		final String dataset = pDataset == null ? "" : pDataset.trim();
		final String table = pTableName == null ? "" : pTableName.trim();
		return (dataspace.isEmpty() ? dataset : dataspace + "/" + dataset) + "/root/" + table;
	}

	/** The landing dataset name of the source system a staging mapping's landing column belongs to - looked up from {@code ProjectInput.sources} rather than passed a second time on every {@link AttributeMappingInput}. */
	private static String landingDatasetOf(final ProjectInput pInput, final String pSourceSystem)
	{
		for (final SourceZone source : pInput.sources)
		{
			if (source.sourceSystem != null && source.sourceSystem.equals(pSourceSystem))
			{
				return source.landingDatasetName;
			}
		}
		return null;
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	private static String orEmpty(final String pValue)
	{
		return pValue == null ? "" : pValue;
	}

	static String iso(final Date pWhen)
	{
		final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.format(pWhen == null ? new Date(0L) : pWhen);
	}
}
