package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.staging.ParentLinkRule;
import com.on.ebx.factory.quality.ChildKeysDerivation;
import com.on.ebx.factory.core.FeedDesignerLink;
import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.mappingmatrix.MatrixMessages;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.AttentionRules;
import com.on.ebx.factory.transfer.PipelineCompat;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

/**
 * Thin EBX-bound reader behind the Mapping matrix screen (docs/MAPPING-MATRIX.md): the only class of
 * this feature that touches EBX, turning {@code TransferFeed}/{@code AttributeMapping}/{@code
 * StagingEntity}/{@code StagingAttribute}/{@code LandingTable}/{@code LandingColumn}/{@code
 * ProjectSource} rows of one project into a plain {@link MatrixModel.Project} - everything else
 * ({@code mappingmatrix.MatrixCompute}/{@code MatrixHtml}) is pure and unit-tested without a live
 * repository, the same split {@code review.ReviewLine}/{@code ProposalReviews} already keep.
 * <p>
 * <b>Simplifications against the fully general model (docs/MAPPING-MATRIX.md "Simplifications" - none of
 * this is silently narrowed, every one is named here and in that doc):</b>
 * <ul>
 * <li>Only {@code ACCEPTED}/{@code PROPOSED}/{@code APPLIED} {@code StagingEntity}/{@code TransferFeed}
 * rows are shown (a {@code REJECTED} or {@code SUPERSEDED} entity/feed is dead - same "hide what nobody
 * acts on any more" rule the wizard's staging-focus lot already applies to {@code SUPERSEDED} entities,
 * docs/TARGET-MAPPING.md sec 4.3).</li>
 * <li>A {@code StagingAttribute} is shown unless it is the technical {@code sourceSystem} primary key
 * (every OTHER primary-key attribute, in practice only {@code sourceId}, IS shown as the "source key" row
 * the task brief asked for - {@link #SOURCE_ID_ATTRIBUTE_NAME}) or its own status is {@code REJECTED}.</li>
 * <li>A "key-linked" feed (the mockup's violet dashed curve: an ordinary foreign-key attribute of a ROOT
 * feed, resolved by matching a landing column against an already-loaded parent's key, rather than a real
 * {@code TransferFeed.parentFeed} child) is detected from the data itself: a root feed whose entity has a
 * {@code referenceEntity} attribute with a real {@code AttributeMapping} row IN THIS FEED. Per
 * docs/TARGET-MAPPING.md sec 4.2 ("a child feed naming a DIFFERENT landing table than its parent feed" is
 * refused), a genuine child feed always reads the SAME landing table as its parent - so this is the only
 * shape a cross-table link can take, never confused with a real child feed.</li>
 * <li>The sample "before -&gt; after" ({@link #sample}) is computed only for the feeds of the entity the
 * caller asks for (the currently open one), reading at most {@value #SAMPLE_ROW_LIMIT} landing rows per
 * feed - never for every entity/feed of the project on every render.</li>
 * </ul>
 */
public final class MappingMatrixReader
{
	/** Same technical-attribute naming convention {@code staging.StagingRules.technicalAttributeProposals} writes - the one primary-key attribute this screen still shows (class Javadoc). */
	private static final String SOURCE_ID_ATTRIBUTE_NAME = "sourceId";
	private static final int SAMPLE_ROW_LIMIT = 20;

	/**
	 * The module's own copy of {@code lineage.SourceColorPalette}'s 12-hue list (that class is
	 * package-private to {@code com.on.ebx.factory.lineage}, and widening it was out of scope for this lot -
	 * docs/MAPPING-MATRIX.md "Simplifications"): same values, same order, same "stable positional
	 * assignment" rule, so a source keeps the same colour here as on the Record lineage screen in practice
	 * (both assign by the project's own source order), without touching that class' visibility.
	 */
	private static final String[] PALETTE = { "#2f6fd0", "#0e9384", "#c77700", "#b4236c", "#6b7d12", "#0b84a5", "#8a5a2b", "#475467", "#4338ca", "#c2410c",
		"#a21caf", "#15803d" };

	private MappingMatrixReader()
	{
	}

	/**
	 * Reads the whole project. {@code pSampleForEntityId}, when non-null, also computes {@link
	 * MatrixModel.Cell#sample} for every data cell of that one entity's feeds (class Javadoc) - pass null to
	 * skip sample computation entirely (e.g. while only recomputing counters for a bulk-accept action).
	 */
	public static MatrixModel.Project read(final Adaptation pFactory, final Session pSession, final String pProjectId, final String pSampleForEntityId,
		final Locale pLocale)
	{
		// audit F2-100: the wizard's step 4, the Validation desk and the matrix read the same project several times per click; under the same state of
		// the tables (their modification dates) the model is the same, so it is read once. The key is null when a date cannot be read: then nothing is cached.
		final long started = System.currentTimeMillis();
		final String cacheKey = MappingMatrixReader.cacheKey(pFactory, pSession, pProjectId, pSampleForEntityId, pLocale);
		final Function<String, String> tokenOf = MappingMatrixReader.externalTokens(pFactory);
		if (cacheKey != null)
		{
			final MatrixModel.Project cached = MappingMatrixReader.CACHE.get(cacheKey, tokenOf, started);
			if (cached != null)
			{
				if (LoggingCategory.getKernel().isDebug())
				{
					LoggingCategory.getKernel().debug("[mdm-factory] mapping matrix for " + pProjectId + " served from the cache in " + (System.currentTimeMillis() - started) + " ms");
				}
				return cached;
			}
		}
		final ReadRun run = new ReadRun();
		final ReadRun before = MappingMatrixReader.RUN.get();
		MappingMatrixReader.RUN.set(run);
		final MatrixModel.Project model;
		try
		{
			model = MappingMatrixReader.readUncached(pFactory, pSession, pProjectId, pSampleForEntityId, pLocale);
		}
		finally
		{
			if (before == null)
			{
				MappingMatrixReader.RUN.remove();
			}
			else
			{
				MappingMatrixReader.RUN.set(before);
			}
		}
		if (cacheKey != null && !run.degraded)
		{
			MappingMatrixReader.CACHE.put(cacheKey, model, run.externals, tokenOf, System.currentTimeMillis());
		}
		if (LoggingCategory.getKernel().isDebug())
		{
			LoggingCategory.getKernel().debug("[mdm-factory] mapping matrix for " + pProjectId + " read in " + (System.currentTimeMillis() - started) + " ms, " + run.mappings
				+ " mappings, " + (run.steps == null ? 0 : run.steps.stepCount()) + " steps");
		}
		return model;
	}

	/** Audit F2-100: the bound on what no key can see (a write whose table date is not read here) - the matrix is read again after this long anyway. */
	static final long CACHE_MAX_AGE_MS = 5L * 60L * 1000L;
	/** Audit F2-100: a project per user and locale, with and without an open entity - the entries one wizard session needs. */
	static final int CACHE_MAX_ENTRIES = 24;
	static final MatrixReadCache<MatrixModel.Project> CACHE = new MatrixReadCache<MatrixModel.Project>(MappingMatrixReader.CACHE_MAX_ENTRIES, MappingMatrixReader.CACHE_MAX_AGE_MS);
	/** Audit F2-100: one measured sample per (mapping, pipeline, cleaning steps, landing table state, user): the sample is computed again only when one of them changes. */
	static final ReadCache<MatrixModel.Sample> SAMPLE_CACHE = new ReadCache<MatrixModel.Sample>(1024, MappingMatrixReader.CACHE_MAX_AGE_MS);
	/** Test hook: samples actually measured (not served from the cache) since the JVM started. */
	static final AtomicInteger SAMPLES_MEASURED = new AtomicInteger();

	/** What one uncached read collects: the one sweep of {@code MappingStep}, the tables outside the Factory it read, the counts for the debug line. */
	private static final class ReadRun
	{
		MappingStepIndex steps;
		final Set<String> externals = new LinkedHashSet<String>();
		int mappings;
		boolean degraded;
	}

	private static final ThreadLocal<ReadRun> RUN = new ThreadLocal<ReadRun>();

	private static ReadRun run()
	{
		final ReadRun run = MappingMatrixReader.RUN.get();
		return run == null ? new ReadRun() : run; // a caller outside read() (a test) gets a throw-away run
	}

	private static MappingStepIndex steps(final Adaptation pFactory)
	{
		final ReadRun run = MappingMatrixReader.run();
		if (run.steps == null)
		{
			run.steps = MappingStepIndex.scan(pFactory);
		}
		return run.steps;
	}

	/** The Factory tables a matrix read depends on, whose modification dates make the cache key. */
	private static final Path[] KEY_TABLES = { FactoryPaths.PROJECT, FactoryPaths.PROJECT_SOURCE, FactoryPaths.LANDING_TABLE, FactoryPaths.LANDING_COLUMN,
		FactoryPaths.STAGING_ENTITY, FactoryPaths.STAGING_ATTRIBUTE, FactoryPaths.TRANSFER_FEED, FactoryPaths.ATTRIBUTE_MAPPING, FactoryPaths.MAPPING_STEP,
		FactoryPaths.QUALITY_RULE, FactoryPaths.CAPABILITY_SUGGESTION };

	private static String cacheKey(final Adaptation pFactory, final Session pSession, final String pProjectId, final String pSampleForEntityId, final Locale pLocale)
	{
		final StringBuilder key = new StringBuilder();
		key.append(pFactory.getHome().getKey().format()).append('\u0003').append(pFactory.getAdaptationName().getStringName()).append('\u0003').append(pProjectId)
			.append('\u0003').append(pSession == null ? "-" : String.valueOf(pSession.getUserReference())).append('\u0003').append(String.valueOf(pLocale)).append('\u0003')
			.append(pSampleForEntityId == null ? "-" : pSampleForEntityId);
		for (final Path path : MappingMatrixReader.KEY_TABLES)
		{
			final String token = StagingLoadGridReader.modificationToken(pFactory.getTable(path));
			if (token == null)
			{
				return null;
			}
			key.append('\u0001').append(token);
		}
		return key.toString();
	}

	/** Resolves a reference {@code dataspace|dataset|table path} collected during a read to its table's modification token ("-" when it is not there, null when unreadable). */
	private static Function<String, String> externalTokens(final Adaptation pFactory)
	{
		return new Function<String, String>()
		{
			@Override
			public String apply(final String pRef)
			{
				final String[] parts = pRef.split("\u0001", -1);
				try
				{
					final AdaptationHome home = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(parts[0]));
					final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(parts[1]));
					if (dataset == null)
					{
						return "-";
					}
					AdaptationTable table = null;
					try
					{
						table = dataset.getTable(Path.parse(parts[2]));
					}
					catch (final RuntimeException absent)
					{
						table = null;
					}
					return StagingLoadGridReader.modificationToken(table);
				}
				catch (final RuntimeException ex)
				{
					return null;
				}
			}
		};
	}

	private static MatrixModel.Project readUncached(final Adaptation pFactory, final Session pSession, final String pProjectId, final String pSampleForEntityId,
		final Locale pLocale)
	{
		final Adaptation project = MappingMatrixReader.lookup(pFactory, FactoryPaths.PROJECT, pProjectId);
		final String projectLabel = project == null ? "?" : MappingMatrixReader.text(project, FactoryPaths.Project.LABEL);
		final String landingDataspace = project == null ? "" : MappingMatrixReader.text(project, FactoryPaths.Project.LANDING_DATASPACE);
		final String projectCode = project == null ? "" : MappingMatrixReader.text(project, FactoryPaths.Project.CODE);
		// staging-proposal matrix lot: every guard of the project, read ONCE (ebx.GuardReader mirrors
		// ebx.ProposalReviews's own field paths - see its class Javadoc), grouped by attribute id -
		// GuardReader.ENTITY_LEVEL ("") for a guard with no attribute of its own (delta 1).
		final Map<String, List<Guard>> guardsByAttribute = projectCode.isEmpty() ? java.util.Collections.<String, List<Guard>>emptyMap()
			: GuardReader.read(pFactory, projectCode);
		// fix/mapping-matrix-live (2026-09-24): a root feed's own "Designer" link, same technique
		// wizard.ProjectWizardUserService#model already uses for WizardSnapshot.FeedInfo.designerUrl.
		final String dataspaceKey = pFactory.getHome().getKey().format();
		final String datasetName = pFactory.getAdaptationName().getStringName();

		final List<MatrixModel.Source> sources = new ArrayList<MatrixModel.Source>();
		final Map<String, Adaptation> projectSourceRows = new LinkedHashMap<String, Adaptation>();
		final RequestResult sourceRows = UserView.select(pFactory.getTable(FactoryPaths.PROJECT_SOURCE), "./project=" + MappingMatrixReader.quote(pProjectId));
		try
		{
			int ordinal = 0;
			for (Adaptation row = sourceRows.nextAdaptation(); row != null; row = sourceRows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				projectSourceRows.put(id, row);
				final String color = MappingMatrixReader.PALETTE[((ordinal % MappingMatrixReader.PALETTE.length) + MappingMatrixReader.PALETTE.length)
					% MappingMatrixReader.PALETTE.length];
				sources.add(new MatrixModel.Source(id, MappingMatrixReader.text(row, FactoryPaths.ProjectSource.SOURCE_SYSTEM), color));
				ordinal++;
			}
		}
		finally
		{
			sourceRows.close();
		}

		final Map<String, String> landingTableSourceId = new LinkedHashMap<String, String>();
		final Map<String, String> landingTableName = new LinkedHashMap<String, String>();
		final Map<String, String> landingTableDataset = new LinkedHashMap<String, String>();
		final Map<String, Long> landingTableRowCount = new LinkedHashMap<String, Long>();
		for (final String sourceId : projectSourceRows.keySet())
		{
			final Adaptation projectSource = projectSourceRows.get(sourceId);
			final RequestResult tableRows = UserView.select(pFactory.getTable(FactoryPaths.LANDING_TABLE), "./projectSource=" + MappingMatrixReader.quote(sourceId));
			try
			{
				for (Adaptation row = tableRows.nextAdaptation(); row != null; row = tableRows.nextAdaptation())
				{
					final String tableId = row.getOccurrencePrimaryKey().format();
					landingTableSourceId.put(tableId, sourceId);
					landingTableName.put(tableId, MappingMatrixReader.text(row, FactoryPaths.LandingTable.TABLE_NAME));
					landingTableDataset.put(tableId, MappingMatrixReader.text(projectSource, FactoryPaths.ProjectSource.LANDING_DATASET));
					// staging-proposal matrix lot (delta 2), fixed live: AttentionRules.MappingInput#rowCount -
					// LandingRowCounts.rowCountOf (lastLoadRows, falling back to sourceRowCount - see its own
					// Javadoc), not lastLoadRows alone, so this reader agrees with ebx.AnomalyLlmSuggester and
					// ebx.ui.FeedDesignerUserService on the same number (LandingColumn fillRate/distinctCount
					// are PERCENT/count, this is the denominator to turn them back into "N filled / M rows").
					final Long rowCount = LandingRowCounts.rowCountOf(row);
					if (rowCount != null)
					{
						landingTableRowCount.put(tableId, rowCount);
					}
				}
			}
			finally
			{
				tableRows.close();
			}
		}

		final List<MatrixModel.Entity> entities = new ArrayList<MatrixModel.Entity>();
		final RequestResult entityRows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ENTITY), "./project=" + MappingMatrixReader.quote(pProjectId));
		try
		{
			for (Adaptation entityRow = entityRows.nextAdaptation(); entityRow != null; entityRow = entityRows.nextAdaptation())
			{
				final String status = MappingMatrixReader.text(entityRow, FactoryPaths.StagingEntity.STATUS);
				if (ReviewLine.REJECTED.equals(status) || "SUPERSEDED".equals(status))
				{
					continue; // class Javadoc: dead, never a matrix tab
				}
				final String entityId = entityRow.getOccurrencePrimaryKey().format();
				final String entityName = MappingMatrixReader.text(entityRow, FactoryPaths.StagingEntity.NAME);
				final boolean computeSamples = entityId.equals(pSampleForEntityId);
				entities.add(MappingMatrixReader.readEntity(pFactory, pSession, entityId, entityName, status, MappingMatrixReader.intOrNull(entityRow, FactoryPaths.StagingEntity.CONFIDENCE),
					landingDataspace, landingTableSourceId, landingTableName, landingTableDataset, landingTableRowCount, guardsByAttribute, computeSamples, pLocale,
					dataspaceKey, datasetName, project, entityRow, sourceSystemsOf(sources)));
			}
		}
		finally
		{
			entityRows.close();
		}
		return new MatrixModel.Project(projectLabel, sources, entities);
	}

	private static MatrixModel.Entity readEntity(final Adaptation pFactory, final Session pSession, final String pEntityId, final String pEntityName,
		final String pEntityStatus, final Integer pEntityConfidence, final String pLandingDataspace, final Map<String, String> pLandingTableSourceId,
		final Map<String, String> pLandingTableName, final Map<String, String> pLandingTableDataset, final Map<String, Long> pLandingTableRowCount,
		final Map<String, List<Guard>> pGuardsByAttribute, final boolean pComputeSamples, final Locale pLocale, final String pDataspaceKey,
		final String pDatasetName, final Adaptation pProject, final Adaptation pEntityRow, final List<String> pSourceSystems)
	{
		// pass 1: TransferFeed rows (the parent-link attributes are read by ParentLinkReader below)
		final List<Adaptation> feedRows = new ArrayList<Adaptation>();
		final RequestResult feedRequest = UserView.select(pFactory.getTable(FactoryPaths.TRANSFER_FEED), "./entity=" + MappingMatrixReader.quote(pEntityId));
		try
		{
			for (Adaptation row = feedRequest.nextAdaptation(); row != null; row = feedRequest.nextAdaptation())
			{
				final String status = MappingMatrixReader.text(row, FactoryPaths.TransferFeed.STATUS);
				if (ReviewLine.REJECTED.equals(status))
				{
					continue;
				}
				feedRows.add(row);
			}
		}
		finally
		{
			feedRequest.close();
		}

		// fix/parent-link-follows-entity (2026-10-21, demoBala): a feed stores the FIELD it fills (personneRef), not the attribute
		// name (personne) - matched through ParentLinkRule, the same derivation StagingModel uses to name that field
		final Set<String> parentLinkAttributeIds = ParentLinkReader.attributeIdsOf(pFactory, pEntityId);
		if (pComputeSamples)
		{
			// audit F2-100: a computed field is measured on the entity's staging table: a load there must show on the next read
			MappingMatrixReader.run().externals.add(MappingMatrixReader.text(pProject, FactoryPaths.Project.STAGING_DATASPACE) + "\u0001"
				+ MappingMatrixReader.text(pProject, FactoryPaths.Project.STAGING_DATASET) + "\u0001" + StagingModel.tablePath(pEntityName));
		}
		final List<MatrixModel.Attr> attrs = new ArrayList<MatrixModel.Attr>();
		final Map<String, String> attrNameById = new LinkedHashMap<String, String>();
		final RequestResult attributeRows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + MappingMatrixReader.quote(pEntityId));
		try
		{
			for (Adaptation row = attributeRows.nextAdaptation(); row != null; row = attributeRows.nextAdaptation())
			{
				final String status = MappingMatrixReader.text(row, FactoryPaths.StagingAttribute.STATUS);
				if (ReviewLine.REJECTED.equals(status))
				{
					continue;
				}
				final String name = MappingMatrixReader.text(row, FactoryPaths.StagingAttribute.NAME);
				final boolean primaryKey = Boolean.TRUE.equals(UserView.get(row, FactoryPaths.StagingAttribute.PRIMARY_KEY));
				final boolean sourceKey = primaryKey && MappingMatrixReader.SOURCE_ID_ATTRIBUTE_NAME.equalsIgnoreCase(name);
				if (primaryKey && !sourceKey)
				{
					continue; // sourceSystem (or any other technical PK) - class Javadoc
				}
				final String id = row.getOccurrencePrimaryKey().format();
				attrNameById.put(id, name);
				final List<Guard> guardsOfAttr = pGuardsByAttribute.get(id);
				// fix/matrix-derived-key-rows (2026-09-27): a derived attribute (FOLD_KEY matching key, VALID_VALUE,
				// SCRIPT) is computed at load from another attribute of the SAME entity and has no source link by
				// design - the matrix shows one "computed at load" cell for it instead of "not provided by this
				// source" per feed. The source attribute is read by id (it may be REJECTED, hence absent from
				// attrNameById, or simply come later in this loop); a missing row reads as "" - never a failure.
				final String derivation = MappingMatrixReader.text(row, FactoryPaths.StagingAttribute.DERIVATION);
				String derivedFromName = "";
				if (ChildKeysDerivation.DERIVATION_CHILD_KEYS.equals(derivation))
				{
					// lot M3: derivedFrom is empty by design - the matrix names the child entity the keys come from ("Keys of the Adresse records")
					derivedFromName = ChildKeysPass.childEntityLabel(row);
				}
				else if (!derivation.isEmpty())
				{
					final Adaptation derivedFromRow =
						MappingMatrixReader.lookup(pFactory, FactoryPaths.STAGING_ATTRIBUTE, MappingMatrixReader.text(row, FactoryPaths.StagingAttribute.DERIVED_FROM));
					derivedFromName = derivedFromRow == null ? "" : MappingMatrixReader.text(derivedFromRow, FactoryPaths.StagingAttribute.NAME);
				}
				// fix/matrix-computed-field: what a SCRIPT field computes - only for the entity on screen (the doc is a cached
				// library read, the measurement a bounded read of the staging table; no other caller needs either)
				final MatrixModel.Computation computation = pComputeSamples && "SCRIPT".equals(derivation)
					? MappingMatrixReader.readComputation(pFactory, pSession, pProject, pEntityRow, pEntityName, name, derivedFromName, guardsOfAttr, pSourceSystems)
					: null;
				// Validation desk lot L2: the parent link says WHICH business object it points to ("its Personne" in the preview)
				final String parentEntityName = parentLinkAttributeIds.contains(id)
					? MappingMatrixReader.text(MappingMatrixReader.lookup(pFactory, FactoryPaths.STAGING_ENTITY, MappingMatrixReader.text(row, FactoryPaths.StagingAttribute.REFERENCE_ENTITY)),
						FactoryPaths.StagingEntity.NAME)
					: "";
				attrs.add(new MatrixModel.Attr(id, name, MappingMatrixReader.text(row, FactoryPaths.StagingAttribute.DATA_TYPE),
					Boolean.TRUE.equals(UserView.get(row, FactoryPaths.StagingAttribute.MANDATORY)), parentLinkAttributeIds.contains(id), sourceKey, status,
					MappingMatrixReader.intOrNull(row, FactoryPaths.StagingAttribute.CONFIDENCE), guardsOfAttr, derivation, derivedFromName, computation,
					MappingMatrixReader.text(row, FactoryPaths.StagingAttribute.RATIONALE), parentEntityName));
			}
		}
		finally
		{
			attributeRows.close();
		}

		// staging-proposal matrix lot (delta 2): every AttentionRules flag of this entity, computed ONCE
		// across every feed's mappings (DUPLICATE_FIELD needs the whole entity's picture - staging.AttentionRules
		// class Javadoc), keyed by attributeId+"\u0001"+landingColumnId so readFeed can attach the right flag to
		// the right cell while building it (no post-hoc rebuild of the otherwise-immutable Feed/Cell tree).
		final Map<String, AttentionRules.Flag> attentionByKey =
			MappingMatrixReader.computeAttentionFlags(pFactory, feedRows, pEntityId, pLandingTableRowCount);

		final List<MatrixModel.Feed> feeds = new ArrayList<MatrixModel.Feed>();
		for (final Adaptation feedRow : feedRows)
		{
			feeds.add(MappingMatrixReader.readFeed(pFactory, pSession, feedRow, attrs, pLandingDataspace, pLandingTableSourceId, pLandingTableName,
				pLandingTableDataset, pLandingTableRowCount, attentionByKey, pComputeSamples, pLocale, pDataspaceKey, pDatasetName));
		}
		final List<Guard> entityGuards = new ArrayList<Guard>();
		for (final Guard guard : pGuardsByAttribute.containsKey(GuardReader.ENTITY_LEVEL) ? pGuardsByAttribute.get(GuardReader.ENTITY_LEVEL) : Collections.<Guard>emptyList())
		{
			if (pEntityId.equals(guard.entityId))
			{
				entityGuards.add(guard);
			}
		}
		return new MatrixModel.Entity(pEntityId, pEntityName, attrs, feeds, pEntityStatus, pEntityConfidence, entityGuards);
	}

	private static List<String> sourceSystemsOf(final List<MatrixModel.Source> pSources)
	{
		final List<String> out = new ArrayList<String>();
		for (final MatrixModel.Source source : pSources)
		{
			if (!out.contains(source.sourceSystem))
			{
				out.add(source.sourceSystem);
			}
		}
		return out;
	}

	/**
	 * fix/matrix-computed-field (docs/MAPPING-MATRIX.md sec 14): what a computed attribute computes, from its
	 * {@code SCRIPT_DERIVATION} suggestion (function, arguments, rationale - stored data), the library's own doc comment
	 * ({@link LibraryDocs}, cached) and the staging table ({@link ComputedFieldMeasure}, bounded, only once the entity
	 * was loaded and the function applied). Null when the attribute has no such suggestion on the matrix (rejected or
	 * set aside): the cell then keeps its generic text. Never throws: a doc or a measure that cannot be read is said so in
	 * the model, it never breaks the page.
	 */
	private static MatrixModel.Computation readComputation(final Adaptation pFactory, final Session pSession, final Adaptation pProject, final Adaptation pEntityRow,
		final String pEntityName, final String pAttributeName, final String pSourceFieldName, final List<Guard> pGuards, final List<String> pSourceSystems)
	{
		Guard guard = null;
		for (final Guard candidate : pGuards == null ? Collections.<Guard>emptyList() : pGuards)
		{
			if (ReviewLine.SCRIPT_DERIVATION.equals(candidate.nature))
			{
				guard = candidate;
				break;
			}
		}
		final Adaptation suggestion = guard == null ? null : MappingMatrixReader.lookup(pFactory, FactoryPaths.CAPABILITY_SUGGESTION, guard.id);
		if (suggestion == null)
		{
			return null;
		}
		final String library = MappingMatrixReader.text(suggestion, FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY);
		final String function = MappingMatrixReader.text(suggestion, FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION);
		final String status = MappingMatrixReader.text(suggestion, FactoryPaths.CapabilitySuggestion.STATUS);
		com.on.ebx.factory.capability.FunctionDoc doc = null;
		String docProblem = "";
		try
		{
			final LibraryDocs.Result result = LibraryDocs.of(pSession, pFactory, library, function);
			doc = result.doc;
			docProblem = result.problem;
		}
		catch (final RuntimeException ex)
		{
			docProblem = ex.getMessage() == null ? ex.toString() : ex.getMessage();
		}
		MatrixModel.Measurement measurement;
		try
		{
			measurement = ComputedFieldMeasure.measure(pFactory, MappingMatrixReader.text(pProject, FactoryPaths.Project.STAGING_DATASPACE),
				MappingMatrixReader.text(pProject, FactoryPaths.Project.STAGING_DATASET), pEntityName,
				(java.util.Date) UserView.get(pEntityRow, FactoryPaths.StagingEntity.LAST_LOAD_AT), status, pSourceFieldName, pAttributeName, pSourceSystems);
		}
		catch (final RuntimeException ex)
		{
			measurement = MatrixModel.Measurement.unavailable(ex.getMessage() == null ? ex.toString() : ex.getMessage());
		}
		return new MatrixModel.Computation(guard.id, status, library, function, MappingMatrixReader.text(suggestion, FactoryPaths.CapabilitySuggestion.BINDING),
			com.on.ebx.factory.capability.MissingInputs.stripMarker(MappingMatrixReader.text(suggestion, FactoryPaths.CapabilitySuggestion.RATIONALE)), doc, docProblem, measurement);
	}

	/**
	 * See {@link #readEntity}'s own Javadoc note above: one {@code staging.AttentionRules} pass over every
	 * non-rejected {@code AttributeMapping} of every feed of this entity, key = {@code attributeId + "\u0001"
	 * + landingColumnId} (unique enough: an attribute/column pair belongs to exactly one feed in practice -
	 * two feeds mapping the same attribute from the same landing column id would collide here, an edge case
	 * this lot does not special-case).
	 */
	private static Map<String, AttentionRules.Flag> computeAttentionFlags(final Adaptation pFactory, final List<Adaptation> pFeedRows, final String pEntityId,
		final Map<String, Long> pLandingTableRowCount)
	{
		final List<AttentionRules.MappingInput> inputs = new ArrayList<AttentionRules.MappingInput>();
		for (final Adaptation feedRow : pFeedRows)
		{
			final String landingTableId = MappingMatrixReader.text(feedRow, FactoryPaths.TransferFeed.LANDING_TABLE);
			final Long rowCount = pLandingTableRowCount.get(landingTableId);
			final String feedId = feedRow.getOccurrencePrimaryKey().format();
			final RequestResult mappingRows = UserView.select(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./feed=" + MappingMatrixReader.quote(feedId));
			try
			{
				for (Adaptation row = mappingRows.nextAdaptation(); row != null; row = mappingRows.nextAdaptation())
				{
					final String status = MappingMatrixReader.text(row, FactoryPaths.AttributeMapping.STATUS);
					final String attributeId = MappingMatrixReader.text(row, FactoryPaths.AttributeMapping.ATTRIBUTE);
					final String columnId = MappingMatrixReader.text(row, FactoryPaths.AttributeMapping.LANDING_COLUMN);
					if (attributeId.isEmpty() || columnId.isEmpty())
					{
						continue;
					}
					final Adaptation column = MappingMatrixReader.lookup(pFactory, FactoryPaths.LANDING_COLUMN, columnId);
					// lot L3: the feed, the pipeline and the alternative mark let SAME_CONSTANT see every source of the attribute (staging.AttentionRules)
					inputs.add(new AttentionRules.MappingInput(attributeId, columnId, pEntityId, status, MappingMatrixReader.doubleOrNull(column, FactoryPaths.LandingColumn.FILL_RATE),
						MappingMatrixReader.longOrNull(column, FactoryPaths.LandingColumn.DISTINCT_COUNT), rowCount, feedId, MappingMatrixReader.resolvedPipeline(row, ""),
						!MappingMatrixReader.text(row, FactoryPaths.AttributeMapping.ALTERNATIVE_TO).isEmpty()));
				}
			}
			finally
			{
				mappingRows.close();
			}
		}
		final Map<String, AttentionRules.Flag> out = new LinkedHashMap<String, AttentionRules.Flag>();
		for (final AttentionRules.Flag flag : AttentionRules.detect(inputs))
		{
			out.put(flag.attributeId + "\u0001" + flag.landingColumnId, flag);
		}
		return out;
	}

	private static MatrixModel.Feed readFeed(final Adaptation pFactory, final Session pSession, final Adaptation pFeedRow, final List<MatrixModel.Attr> pAttrs,
		final String pLandingDataspace, final Map<String, String> pLandingTableSourceId, final Map<String, String> pLandingTableName,
		final Map<String, String> pLandingTableDataset, final Map<String, Long> pLandingTableRowCount, final Map<String, AttentionRules.Flag> pAttentionByKey,
		final boolean pComputeSamples, final Locale pLocale, final String pDataspaceKey, final String pDatasetName)
	{
		final String feedId = pFeedRow.getOccurrencePrimaryKey().format();
		final String landingTableId = MappingMatrixReader.text(pFeedRow, FactoryPaths.TransferFeed.LANDING_TABLE);
		final String sourceId = pLandingTableSourceId.get(landingTableId);
		final String tableName = pLandingTableName.get(landingTableId);
		final String parentFeedId = MappingMatrixReader.text(pFeedRow, FactoryPaths.TransferFeed.PARENT_FEED);
		final String collectionPath = MappingMatrixReader.text(pFeedRow, FactoryPaths.TransferFeed.COLLECTION_PATH);
		final String parentLinkField = MappingMatrixReader.text(pFeedRow, FactoryPaths.TransferFeed.PARENT_LINK_FIELD);
		final boolean isChild = !parentFeedId.isEmpty();
		final String status = MappingMatrixReader.text(pFeedRow, FactoryPaths.TransferFeed.STATUS);

		final Map<String, Adaptation> columnsByFieldName = MappingMatrixReader.columnsByFieldName(pFactory, landingTableId);
		final Map<String, List<Adaptation>> mappingsByAttribute = new LinkedHashMap<String, List<Adaptation>>();
		final Set<String> usedColumnFieldNames = new LinkedHashSet<String>();
		final RequestResult mappingRows = UserView.select(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./feed=" + MappingMatrixReader.quote(feedId));
		try
		{
			for (Adaptation row = mappingRows.nextAdaptation(); row != null; row = mappingRows.nextAdaptation())
			{
				final String attributeId = MappingMatrixReader.text(row, FactoryPaths.AttributeMapping.ATTRIBUTE);
				List<Adaptation> list = mappingsByAttribute.get(attributeId);
				if (list == null)
				{
					list = new ArrayList<Adaptation>();
					mappingsByAttribute.put(attributeId, list);
				}
				list.add(row);
				final Adaptation column = MappingMatrixReader.lookup(pFactory, FactoryPaths.LANDING_COLUMN, MappingMatrixReader.text(row, FactoryPaths.AttributeMapping.LANDING_COLUMN));
				if (column != null)
				{
					usedColumnFieldNames.add(MappingMatrixReader.text(column, FactoryPaths.LandingColumn.FIELD_NAME));
				}
			}
		}
		finally
		{
			mappingRows.close();
		}

		boolean keyLinked = false;
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		for (final MatrixModel.Attr attr : pAttrs)
		{
			if (attr.sourceKey)
			{
				final String sourceIdPipeline = MappingMatrixReader.text(pFeedRow, FactoryPaths.TransferFeed.SOURCE_ID_PIPELINE);
				final String landingColumn = sourceIdPipeline.isEmpty() ? "(_sourceId)" : sourceIdPipeline;
				cells.put(attr.id, new MatrixModel.Cell(MatrixModel.KEY, null, landingColumn, null, null, null, null, null, 0, null, null));
				continue;
			}
			if (attr.parentLink && isChild && ParentLinkRule.isLinkFieldOf(attr.name, parentLinkField))
			{
				cells.put(attr.id, new MatrixModel.Cell(MatrixModel.ENGINE, null, null, null, null, null, null, null, 0, null, null));
				continue;
			}
			final List<Adaptation> mappingsOfAttribute = mappingsByAttribute.get(attr.id);
			if (mappingsOfAttribute == null || mappingsOfAttribute.isEmpty())
			{
				continue; // MISSING/NONE, computed by MatrixCompute from the attribute's own mandatory flag
			}
			final Adaptation mapping = mappingsOfAttribute.get(0);
			final String mappingId = mapping.getOccurrencePrimaryKey().format();
			final String mappingStatus = MappingMatrixReader.text(mapping, FactoryPaths.AttributeMapping.STATUS);
			final String columnId = MappingMatrixReader.text(mapping, FactoryPaths.AttributeMapping.LANDING_COLUMN);
			final Adaptation column = MappingMatrixReader.lookup(pFactory, FactoryPaths.LANDING_COLUMN, columnId);
			final String sourceColumnLabel = column == null ? "?" : MappingMatrixReader.sourceColumnLabel(column);
			final String pipeline = MappingMatrixReader.resolvedPipeline(mapping, attr.dataType);
			final List<String> steps = MappingMatrixReader.splitPipeline(pipeline);
			final Integer confidence = MappingMatrixReader.intOrNull(mapping, FactoryPaths.AttributeMapping.CONFIDENCE);
			final String origin = MappingMatrixReader.text(mapping, FactoryPaths.AttributeMapping.ORIGIN);
			final String rationale = MappingMatrixReader.text(mapping, FactoryPaths.AttributeMapping.RATIONALE);
			final String reviewComment = MappingMatrixReader.text(mapping, FactoryPaths.AttributeMapping.REVIEW_COMMENT);
			// E3: the sample is always evaluated (at most SAMPLE_ROW_LIMIT landing rows), because the
			// EMPTIED_BY_RULE flag it feeds must also exist for the bulk accepts, which read the project without
			// an active entity; it is only DISPLAYED for the active entity, as before.
			final MatrixModel.Sample measured = ReviewLine.REJECTED.equals(mappingStatus) ? null
				: MappingMatrixReader.sample(pFactory, pSession, pLandingDataspace, pLandingTableDataset.get(landingTableId), tableName, column, pipeline,
					StagingLoader.readCleaningSteps(MappingMatrixReader.steps(pFactory), mappingId), collectionPath);
			MappingMatrixReader.run().mappings++;
			final MatrixModel.Sample sample = pComputeSamples ? measured : null;
			if (!isChild && !attr.parentLink)
			{
				// class Javadoc "key-linked": a root feed carrying a real mapping onto a reference attribute
				final Path referenceEntityPath = FactoryPaths.StagingAttribute.REFERENCE_ENTITY;
				final Adaptation attributeRow = MappingMatrixReader.lookup(pFactory, FactoryPaths.STAGING_ATTRIBUTE, attr.id);
				if (attributeRow != null && !MappingMatrixReader.text(attributeRow, referenceEntityPath).isEmpty())
				{
					keyLinked = true;
				}
			}
			// staging-proposal matrix lot (delta 2): the flag computed once for the whole entity
			// (readEntity#computeAttentionFlags), attached here to the one cell it belongs to.
			final AttentionRules.Flag attentionFlag = pAttentionByKey.get(attr.id + "\u0001" + columnId);
			String attentionKind = attentionFlag == null ? "" : attentionFlag.kind;
			String attentionReason = attentionFlag == null ? "" : MappingMatrixReader.attentionReasonText(attentionFlag, pLocale);
			if (attentionFlag == null && measured != null && measured.isAvailable()
				&& AttentionRules.emptiedByRule(pipeline, measured.filled, measured.emptied))
			{
				attentionKind = AttentionRules.EMPTIED_BY_RULE;
				attentionReason = MatrixMessages.get(pLocale, "mappingMatrix.attention.emptiedByRule", String.valueOf(measured.emptied),
					String.valueOf(measured.filled));
			}
			// lot feat/name-order-unknown (Vol100kN, 2026-10-12): a regex that splits a multi-token column whose order cannot be inferred
			if (attentionFlag == null && attentionKind.isEmpty() && measured != null && measured.isAvailable()
				&& AttentionRules.orderUnknownSplit(pipeline, measured.orderUnknown, measured.splitParts, measured.multiTokenFilled))
			{
				attentionKind = AttentionRules.ORDER_UNKNOWN_SPLIT;
				attentionReason = MatrixMessages.get(pLocale, "mappingMatrix.attention.orderUnknown", String.valueOf(measured.splitParts),
					String.valueOf(measured.multiTokenFilled));
			}
			// staging normalisation lot D (2026-10-17): a chain whose output the typed attribute cannot store (isodate() on a text date) is never "fine to accept in bulk"
			if (attentionFlag == null && attentionKind.isEmpty() && measured != null && measured.isAvailable())
			{
				final int invalid = com.on.ebx.factory.staging.TransformVerifier.invalidOutputs(attr.dataType, measured.afterValues);
				if (AttentionRules.conversionFails(invalid))
				{
					attentionKind = AttentionRules.CONVERSION_FAILS;
					attentionReason = MatrixMessages.get(pLocale, "mappingMatrix.attention.conversionFails", String.valueOf(invalid), String.valueOf(measured.filled), attr.dataType);
				}
			}
			// delta 3: a PROPOSED mapping suggested as a replacement for this one (AttributeMapping.alternativeTo).
			final MatrixModel.Alternative alternative =
				MappingMatrixReader.readAlternative(pFactory, pSession, mappingId, attr.dataType, pLandingDataspace, pLandingTableDataset.get(landingTableId), tableName,
					collectionPath, pComputeSamples, pLocale);
			cells.put(attr.id, new MatrixModel.Cell(mappingStatus, mappingId, sourceColumnLabel, steps, confidence, origin, rationale, reviewComment,
				mappingsOfAttribute.size() - 1, null, sample, attentionKind, attentionReason, alternative, MappingMatrixReader.readCodeLists(MappingMatrixReader.steps(pFactory), mappingId),
				column == null ? "" : MappingMatrixReader.text(column, FactoryPaths.LandingColumn.FIELD_NAME), MappingMatrixReader.fillRateOf(column), pipeline));
		}

		final List<String> unused = new ArrayList<String>();
		for (final String fieldName : columnsByFieldName.keySet())
		{
			if (!usedColumnFieldNames.contains(fieldName))
			{
				unused.add(fieldName);
			}
		}

		final String arrivalMode;
		if (isChild)
		{
			arrivalMode = collectionPath.isEmpty() ? MatrixMessages.get(pLocale, "mappingMatrix.arrival.sameRow")
				: MatrixMessages.get(pLocale, "mappingMatrix.arrival.collection", collectionPath);
		}
		else if (keyLinked)
		{
			arrivalMode = MatrixMessages.get(pLocale, "mappingMatrix.arrival.key");
		}
		else
		{
			arrivalMode = "";
		}

		// fix/mapping-matrix-live (2026-09-24): FeedDesignerLink only ever accepts a ROOT feed id (its own
		// class Javadoc) - a child feed's tree opens from its root's own designer instead, same rule
		// wizard.WizardPage#renderFeed already follows for its own "Designer" button.
		final String designerUrl = isChild ? "" : FeedDesignerLink.url(pDataspaceKey, pDatasetName, feedId);
		return new MatrixModel.Feed(feedId, sourceId, landingTableId, tableName == null ? "?" : tableName, status, parentFeedId.isEmpty() ? null : parentFeedId,
			arrivalMode, keyLinked, unused, cells, MappingMatrixReader.intOrNull(pFeedRow, FactoryPaths.TransferFeed.CONFIDENCE),
			MappingMatrixReader.text(pFeedRow, FactoryPaths.TransferFeed.REVIEW_COMMENT), designerUrl, collectionPath, pLandingDataspace, pLandingTableDataset.get(landingTableId),
			pLandingTableRowCount.get(landingTableId));
	}

	/**
	 * Codes transcoding lot H3 (2026-10-17): the code tables of a mapping - its non-rejected {@code TRANSCODE_REFERENCE} steps whose argument carries a table of its own
	 * ({@code quality.CodeMap}). The rows and their counts come from the argument itself (what the model was shown when it proposed the table), so a card costs one read of
	 * the mapping's steps and nothing on the landing.
	 */
	static List<MatrixModel.CodeList> readCodeLists(final MappingStepIndex pSteps, final String pMappingId)
	{
		final List<MatrixModel.CodeList> out = new ArrayList<MatrixModel.CodeList>();
		final UserView view = UserView.current();
		for (final MappingStepIndex.StepRow stepRow : pSteps.rowsOf(pMappingId))
		{
			final Adaptation step = stepRow.row;
			// the sweep read every row as the engine: the user's own rows only, as the per-mapping query of the screen's scope used to give
			if (step == null || !view.recordVisible(step))
			{
				continue;
			}
			if (!com.on.ebx.factory.quality.CleaningSteps.TRANSCODE_REFERENCE.equals(MappingMatrixReader.text(step, FactoryPaths.MappingStep.KIND))
				|| ReviewLine.REJECTED.equals(MappingMatrixReader.text(step, FactoryPaths.MappingStep.STATUS)))
			{
				continue;
			}
			final com.on.ebx.factory.quality.CodeMap map = com.on.ebx.factory.quality.CodeMap.parseCached(MappingMatrixReader.text(step, FactoryPaths.MappingStep.ARGUMENT));
			if (map == null)
			{
				continue;
			}
			final com.on.ebx.factory.quality.CodeMap.Coverage coverage = map.coverageFromCounts();
			final List<MatrixModel.CodeRow> rows = new ArrayList<MatrixModel.CodeRow>();
			for (final Map.Entry<String, String> code : map.codes.entrySet())
			{
				final Long count = map.counts.get(code.getKey());
				rows.add(new MatrixModel.CodeRow(code.getKey(), code.getValue(), count == null ? 0L : count.longValue()));
			}
			out.add(new MatrixModel.CodeList(step.getOccurrencePrimaryKey().format(), MappingMatrixReader.text(step, FactoryPaths.MappingStep.STATUS),
				MappingMatrixReader.intOrNull(step, FactoryPaths.MappingStep.CONFIDENCE), map.basedOn, map.targets, rows, coverage.rows, coverage.unresolved, map.codesWithoutTarget()));
		}
		return out;
	}

	private static String resolvedPipeline(final Adaptation pMapping, final String pDataType)
	{
		final String pipeline = MappingMatrixReader.text(pMapping, FactoryPaths.AttributeMapping.PIPELINE);
		if (!pipeline.isEmpty())
		{
			return pipeline;
		}
		final String transformation = MappingMatrixReader.text(pMapping, FactoryPaths.AttributeMapping.TRANSFORMATION);
		if (transformation.isEmpty())
		{
			return "";
		}
		try
		{
			return PipelineCompat.translate(transformation, MappingMatrixReader.text(pMapping, FactoryPaths.AttributeMapping.ARGUMENT), pDataType);
		}
		catch (final IllegalArgumentException ex)
		{
			return transformation.toLowerCase(Locale.ROOT) + "()"; // an old row PipelineCompat cannot express - shown as-is rather than crashing the page
		}
	}

	private static List<String> splitPipeline(final String pPipeline)
	{
		final List<String> out = new ArrayList<String>();
		if (pPipeline == null || pPipeline.trim().isEmpty())
		{
			return out;
		}
		for (final String step : pPipeline.split("\\|"))
		{
			final String trimmed = step.trim();
			if (!trimmed.isEmpty())
			{
				out.add(trimmed.endsWith(")") || trimmed.indexOf('(') >= 0 ? trimmed : trimmed + "()");
			}
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------ sample

	/**
	 * One "before -&gt; after" sample for a data mapping - reads at most {@value #SAMPLE_ROW_LIMIT} rows of
	 * the feed's own landing table, picks the first whose column is non-empty, applies this mapping's own
	 * ACCEPTED-or-APPLIED cleaning steps ({@code MappingStep}, read with the SAME {@code
	 * StagingLoader#readCleaningSteps} the real loader uses) exactly like {@code StagingLoader#processRow}
	 * does (cleaning BEFORE the pipeline), then runs the SAME Connectors engine ({@code
	 * MappingEngine.compute}) with a one-rule {@code MappingDefinition} on the SAME row document ({@code
	 * StagingLoader#buildRowDocument}, reused rather than a second, drifting copy - coordinator review,
	 * 2026-09-23) - never writes anything, never touches the staging dataset (passed {@code null}: every
	 * pipeline this method attempts is already known not to need it, see below). Degrades to {@link
	 * MatrixModel.Sample#unavailable(String)} rather than ever failing the whole page: an empty/missing
	 * landing table, a pipeline naming a runtime-only step ({@code fk}/{@code lookup}/{@code golden}/{@code
	 * mdmstate} - docs/TARGET-MAPPING.md sec 8, "needs the reference table in the STAGING dataset"), a
	 * COLLECTION child feed (the loader reads the row's sub-element the mapping's column actually lives in,
	 * which this method never resolves - see docs/MAPPING-MATRIX.md, "never a guess at the first element"),
	 * or any exception the engine itself throws.
	 * <p>
	 * <b>Cleaning that needs a reference lookup this method cannot honestly provide</b> ({@code
	 * COUNTRY_TO_ISO2}/{@code CURRENCY_TO_ISO}/{@code TRANSCODE_REFERENCE}, run here with {@code
	 * CleaningSteps.Lookups#empty()}): {@link com.on.ebx.factory.quality.CleaningSteps.Result#unresolved}
	 * says so, and rather than presenting a value as "cleaned" when it was really left untouched for lack of
	 * a lookup table, the pipeline runs on the RAW value instead and {@link MatrixModel.Sample#note} says
	 * "sample before cleaning steps" - never a silently wrong "after".
	 */
	private static MatrixModel.Sample sample(final Adaptation pFactory, final Session pSession, final String pLandingDataspace, final String pLandingDataset,
		final String pLandingTableName, final Adaptation pColumn, final String pPipeline,
		final List<com.on.ebx.factory.quality.CleaningSteps.Step> pCleaningSteps, final String pCollectionPath)
	{
		if (pCollectionPath != null && !pCollectionPath.isEmpty())
		{
			return MatrixModel.Sample.unavailable(com.on.ebx.factory.mappingmatrix.RowTransform.REASON_COLLECTION);
		}
		if (pColumn == null || pLandingDataspace == null || pLandingDataspace.isEmpty() || pLandingDataset == null || pLandingDataset.isEmpty())
		{
			return MatrixModel.Sample.unavailable(com.on.ebx.factory.mappingmatrix.RowTransform.REASON_NO_LANDING);
		}
		if (MappingMatrixReader.needsRuntimeLookup(pPipeline))
		{
			return MatrixModel.Sample.unavailable(com.on.ebx.factory.mappingmatrix.RowTransform.REASON_RUNTIME_LOOKUP);
		}
		try
		{
			final Repository repository = pFactory.getHome().getRepository();
			final AdaptationHome landingHome = repository.lookupHome(HomeKey.forBranchName(pLandingDataspace));
			final Adaptation landingDataset = landingHome == null ? null : landingHome.findAdaptationOrNull(AdaptationName.forName(pLandingDataset));
			if (landingDataset == null)
			{
				return MatrixModel.Sample.unavailable(com.on.ebx.factory.mappingmatrix.RowTransform.REASON_DATASET_NOT_FOUND);
			}
			final AdaptationTable landingTable = landingDataset.getTable(Path.parse("/root/" + pLandingTableName));
			final String fieldName = MappingMatrixReader.text(pColumn, FactoryPaths.LandingColumn.FIELD_NAME);
			// audit F2-100: the sample depends on the landing table's content, this mapping's pipeline and cleaning steps, and who reads it - measured again only
			// when one of them changes (the cleaning steps come from the one sweep of MappingStep of this read)
			MappingMatrixReader.run().externals.add(pLandingDataspace + "\u0001" + pLandingDataset + "\u0001/root/" + pLandingTableName);
			final String landingToken = StagingLoadGridReader.modificationToken(landingTable);
			final String sampleKey = landingToken == null ? null
				: MappingMatrixReader.sampleKey(pSession, pLandingDataspace, pLandingDataset, pLandingTableName, fieldName, pPipeline, pCleaningSteps, landingToken);
			if (sampleKey != null)
			{
				final MatrixModel.Sample cachedSample = MappingMatrixReader.SAMPLE_CACHE.get(sampleKey, System.currentTimeMillis());
				if (cachedSample != null)
				{
					return cachedSample;
				}
			}
			MappingMatrixReader.SAMPLES_MEASURED.incrementAndGet();
			final MatrixModel.Sample measured = MappingMatrixReader.measureSample(pSession, landingTable, fieldName, pPipeline, pCleaningSteps);
			if (sampleKey != null)
			{
				MappingMatrixReader.SAMPLE_CACHE.put(sampleKey, measured, System.currentTimeMillis());
			}
			return measured;
		}
		catch (final Exception ex) // MappingEngine.compute's own checked Exception, plus any EBX runtime error - never breaks the page (class Javadoc)
		{
			MappingMatrixReader.run().degraded = true;
			return MatrixModel.Sample.unavailable(ex.getMessage() == null ? ex.toString() : ex.getMessage());
		}
	}

	private static String sampleKey(final Session pSession, final String pDataspace, final String pDataset, final String pTable, final String pField, final String pPipeline,
		final List<com.on.ebx.factory.quality.CleaningSteps.Step> pSteps, final String pLandingToken)
	{
		final StringBuilder key = new StringBuilder();
		key.append(pSession == null ? "-" : String.valueOf(pSession.getUserReference())).append('\u0003').append(pDataspace).append('\u0003').append(pDataset).append('\u0003')
			.append(pTable).append('\u0003').append(pField).append('\u0003').append(pPipeline).append('\u0003').append(pLandingToken);
		for (final com.on.ebx.factory.quality.CleaningSteps.Step step : pSteps)
		{
			key.append('\u0001').append(step.kind).append('\u0002').append(step.argument);
		}
		return key.toString();
	}

	/** The measurement itself (no cache); throws what the engine throws, {@link #sample} turns it into an unavailable sample. */
	private static MatrixModel.Sample measureSample(final Session pSession, final AdaptationTable landingTable, final String fieldName, final String pPipeline,
		final List<com.on.ebx.factory.quality.CleaningSteps.Step> cleaningSteps) throws Exception
	{
		{
			// task D order (StagingLoader#processRow): an ACCEPTED-or-APPLIED MappingStep pipeline runs BEFORE
			// this mapping's own transformation pipeline - reusing StagingLoader's own reader rather than a
			// second implementation.
			final String scopeFieldName = com.on.ebx.factory.quality.CleaningSteps.scopeFieldOf(cleaningSteps);
			final RequestResult rows = UserView.select(landingTable, null);
			try
			{
				int scanned = 0;
				int filled = 0;
				int emptied = 0;
				// lot feat/name-order-unknown: the sampled source values and what the pipeline made of each, for AttentionRules#orderUnknownSplit
				final com.on.ebx.factory.staging.TokenCase sourceCase = new com.on.ebx.factory.staging.TokenCase();
				final Map<String, String> afterByRaw = new LinkedHashMap<String, String>();
				String firstBefore = null;
				String firstAfter = null;
				String firstNote = "";
				for (Adaptation row = rows.nextAdaptation(); row != null && scanned < MappingMatrixReader.SAMPLE_ROW_LIMIT; row = rows.nextAdaptation())
				{
					scanned++;
					final String raw = MappingMatrixReader.text(row, Path.parse("./" + fieldName));
					if (raw.isEmpty())
					{
						continue;
					}
					// the SAME transformation the Validation desk's preview of real rows runs (mappingmatrix.RowTransform): cleaning steps first, then the pipeline
					final com.on.ebx.factory.mappingmatrix.RowTransform.Result transformed = com.on.ebx.factory.mappingmatrix.RowTransform.apply(StagingLoader.buildRowDocument(row),
						fieldName, cleaningSteps, scopeFieldName == null ? null : MappingMatrixReader.text(row, Path.parse("./" + scopeFieldName)), pPipeline,
						com.on.ebx.factory.quality.CleaningSteps.Lookups.empty(), pSession);
					if (transformed.failed())
					{
						return MatrixModel.Sample.unavailable(transformed.error);
					}
					final String note = transformed.note;
					final String after = transformed.after;
					// E3 (PersVol3, 2026-09-25): every filled value of the first rows is run, not only the first one,
					// so a rule that empties most of them (AttentionRules#EMPTIED_BY_RULE) is seen; the first pair
					// stays the one displayed.
					filled++;
					sourceCase.add(raw, 1L);
					afterByRaw.put(raw, after == null || after.trim().isEmpty() ? null : after);
					if (after == null || after.trim().isEmpty())
					{
						emptied++;
					}
					if (firstBefore == null)
					{
						firstBefore = raw;
						firstAfter = after == null ? "" : after;
						firstNote = note;
					}
				}
				if (filled > 0)
				{
					final int[] split = com.on.ebx.factory.staging.PartOrder.splitCounts(afterByRaw);
					return MatrixModel.Sample.counted(firstBefore, firstAfter, firstNote, filled, emptied, sourceCase.orderUnknown(), split[0], split[1],
						new java.util.ArrayList<String>(afterByRaw.values()));
				}
				return MatrixModel.Sample
					.unavailable(scanned == 0 ? com.on.ebx.factory.mappingmatrix.RowTransform.REASON_EMPTY_TABLE : "no non-empty value in the first " + MappingMatrixReader.SAMPLE_ROW_LIMIT + " rows");
			}
			finally
			{
				rows.close();
			}
		}
	}

	private static boolean needsRuntimeLookup(final String pPipeline)
	{
		return com.on.ebx.factory.mappingmatrix.RowTransform.needsRuntimeLookup(pPipeline);
	}

	// -------------------------------------------------------------------------------------------------- attention

	/**
	 * Short, already-localized second-line text for one {@code staging.AttentionRules.Flag} (delta 2's own
	 * worked examples: "always the same value", "empty in this source (0 / 165)", "duplicate field") - the
	 * plain numbers stay in {@code AttentionRules} (domain-free), the wording lives in the bundle here, same
	 * split every other explain-text field of this reader already keeps (e.g. {@code explain.missing}).
	 */
	private static String attentionReasonText(final AttentionRules.Flag pFlag, final Locale pLocale)
	{
		if (AttentionRules.CONSTANT_COLUMN.equals(pFlag.kind))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.attention.constant");
		}
		if (AttentionRules.EMPTY_COLUMN.equals(pFlag.kind))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.attention.empty", pFlag.filledRows == null ? "0" : String.valueOf(pFlag.filledRows),
				pFlag.totalRows == null ? "?" : String.valueOf(pFlag.totalRows));
		}
		if (AttentionRules.DUPLICATE_FIELD.equals(pFlag.kind))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.attention.duplicate");
		}
		if (AttentionRules.SAME_CONSTANT.equals(pFlag.kind))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.attention.sameConstant", pFlag.constantValue, String.valueOf(pFlag.feedCount));
		}
		return "";
	}

	/**
	 * Delta 3: the first PROPOSED {@code AttributeMapping} whose {@code alternativeTo} names {@code
	 * pOriginalMappingId} - "Suggested: use &lt;column&gt; instead". Never more than one is read (the task
	 * brief's own worked example is one suggestion per flagged cell); degrades to {@code null} on any lookup
	 * failure, same "never break the page for a decoration" rule {@link #sample} already follows.
	 */
	private static MatrixModel.Alternative readAlternative(final Adaptation pFactory, final Session pSession, final String pOriginalMappingId,
		final String pDataType, final String pLandingDataspace, final String pLandingDataset, final String pLandingTableName, final String pCollectionPath,
		final boolean pComputeSamples, final Locale pLocale)
	{
		try
		{
			final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./alternativeTo=" + MappingMatrixReader.quote(pOriginalMappingId) + " and ./status='" + ReviewLine.PROPOSED + "'");
			try
			{
				final Adaptation altMapping = rows.nextAdaptation();
				if (altMapping == null)
				{
					return null;
				}
				final String columnId = MappingMatrixReader.text(altMapping, FactoryPaths.AttributeMapping.LANDING_COLUMN);
				final Adaptation column = MappingMatrixReader.lookup(pFactory, FactoryPaths.LANDING_COLUMN, columnId);
				final String label = column == null ? "?" : MappingMatrixReader.sourceColumnLabel(column);
				final String pipeline = MappingMatrixReader.resolvedPipeline(altMapping, pDataType);
				final List<String> steps = MappingMatrixReader.splitPipeline(pipeline);
				final String rationale = MappingMatrixReader.text(altMapping, FactoryPaths.AttributeMapping.RATIONALE);
				MatrixModel.Sample sample = null;
				if (pComputeSamples)
				{
					sample = MappingMatrixReader.sample(pFactory, pSession, pLandingDataspace, pLandingDataset, pLandingTableName, column, pipeline,
						StagingLoader.readCleaningSteps(MappingMatrixReader.steps(pFactory), altMapping.getOccurrencePrimaryKey().format()), pCollectionPath);
				}
				return new MatrixModel.Alternative(altMapping.getOccurrencePrimaryKey().format(), label, steps, sample, rationale);
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex) // never let a "nice to have" suggestion break the whole cell
		{
			return null;
		}
	}

	// ---------------------------------------------------------------------------------------------------- helpers

	private static Map<String, Adaptation> columnsByFieldName(final Adaptation pFactory, final String pLandingTableId)
	{
		final Map<String, Adaptation> out = new LinkedHashMap<String, Adaptation>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.LANDING_COLUMN), "./landingTable=" + MappingMatrixReader.quote(pLandingTableId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.put(MappingMatrixReader.text(row, FactoryPaths.LandingColumn.FIELD_NAME), row);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static String sourceColumnLabel(final Adaptation pColumn)
	{
		final String sourceColumn = MappingMatrixReader.text(pColumn, FactoryPaths.LandingColumn.SOURCE_COLUMN);
		return sourceColumn.isEmpty() ? MappingMatrixReader.text(pColumn, FactoryPaths.LandingColumn.FIELD_NAME) : sourceColumn;
	}

	private static Adaptation lookup(final Adaptation pFactory, final Path pTablePath, final String pId)
	{
		if (pId == null || pId.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return UserView.byKey(pFactory.getTable(pTablePath), PrimaryKey.parseString(pId));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static String text(final Adaptation pRow, final Path pPath)
	{
		if (pRow == null)
		{
			return "";
		}
		final Object value = UserView.get(pRow, pPath);
		return value == null ? "" : String.valueOf(value);
	}

	/** {@code LandingColumn.fillRate} (a percentage), null when the column is unknown or was never profiled. */
	private static Double fillRateOf(final Adaptation pColumn)
	{
		if (pColumn == null)
		{
			return null;
		}
		final Object value = UserView.get(pColumn, FactoryPaths.LandingColumn.FILL_RATE);
		return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
	}

	private static Integer intOrNull(final Adaptation pRow, final Path pPath)
	{
		final Object value = UserView.get(pRow, pPath);
		return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
	}

	private static Long longOrNull(final Adaptation pRow, final Path pPath)
	{
		if (pRow == null)
		{
			return null;
		}
		final Object value = UserView.get(pRow, pPath);
		return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
	}

	private static Double doubleOrNull(final Adaptation pRow, final Path pPath)
	{
		if (pRow == null)
		{
			return null;
		}
		final Object value = UserView.get(pRow, pPath);
		return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
	}

	private static String quote(final String pValue)
	{
		return "'" + String.valueOf(pValue).replace("'", "''") + "'";
	}
}
