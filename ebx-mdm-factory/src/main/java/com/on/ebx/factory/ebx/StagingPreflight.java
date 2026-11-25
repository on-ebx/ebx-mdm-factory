package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.TransformationContext;
import com.on.ebx.connect.core.Transformations;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.staging.CheckNote;
import com.on.ebx.factory.staging.NoteTexts;
import com.on.ebx.factory.staging.NormalisationProfile;
import com.on.ebx.factory.staging.NormalisationProfiler;
import com.on.ebx.factory.staging.PreflightCheck;
import com.on.ebx.factory.staging.PreflightFinding;
import com.on.ebx.factory.staging.PreflightLink;
import com.on.ebx.factory.staging.PreflightReport;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.on.ebx.factory.staging.TransformVerifier;
import com.on.ebx.factory.transfer.TransferPlan;
import com.on.ebx.factory.wizard.DerivationParamGate;
import com.on.ebx.factory.wizard.StillToDecide;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Staging preflight lot H1 (2026-10-17, docs/STAGING-PREFLIGHT.md): the EBX side of the check before "Build and load the staging". The verifications of the proposal (lots D and E,
 * {@link NormalisationSuggester}) run when a proposal is made; a link a person accepted or edited afterwards, or landing data that changed since, was never checked again - 140 rows of
 * the loyalty source were refused once because of ONE accepted {@code isodate()} link.
 * <p>
 * One check = for every ACCEPTED mapping the load would run (read by {@link StagingLoader#acceptedMappings}, so the very same set, steps and chains), the distinct values of its
 * landing column with their rows (bounded to {@link QualitySuggester#DEFAULT_MAX_DISTINCT_VALUES}, one read per column, no connector call, nothing leaves the instance), replayed by
 * {@link PreflightCheck} with the engine the load runs ({@code Transformations.apply}); plus the accepted derivations still waiting for a parameter (the wizard's own reading). The
 * report ({@link PreflightReport}) is stored as the detail of a {@code PipelineStep} named {@link PreflightReport#STEP} - no data-model change - and served by {@code GET
 * /projects/{code}/staging/preflight}.
 * <p>
 * <b>What it writes</b>: only that step row, and, on a person's request ({@link #applyFix}), {@code AttributeMapping.pipeline} + a "Value check" note of the link the report named -
 * the status stays ACCEPTED. <b>What it never does</b>: change a decision, write a staging row, call the language model or a connector.
 */
public final class StagingPreflight
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int RATIONALE_MAX_LENGTH = 2000;
	/** A cheap bound: the same sample every verification of the proposal reads. */
	private static final int MAX_DISTINCT_VALUES = QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES;

	private StagingPreflight()
	{
	}

	/** Never cancelled. */
	public static final BooleanSupplier NEVER = () -> false;

	// ------------------------------------------------------------------------------------------------------ the check

	/**
	 * Runs the check and returns the report, writing nothing.
	 *
	 * @param pNote told what is being read (the build's journal); may be null
	 * @param pCancelled asked between two links; true stops the check early (the report then says it is partial)
	 */
	public static PreflightReport run(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Consumer<String> pNote,
		final BooleanSupplier pCancelled) throws OperationException
	{
		final Adaptation project = StagingPreflight.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final List<String> notes = new ArrayList<String>();
		final CleaningSteps.Lookups lookups = QualitySuggester.buildLookups(pFactory, notes);
		final List<TransferPlan.MappingInfo> mappings = StagingLoader.acceptedMappings(pFactory, pProjectCode);
		final Map<String, String> singleBusinessKeys = StagingPreflight.singleBusinessKeys(pFactory, projectId);
		final TransformationContext context = new TransformationContext(null, null, pSession);
		final Repository repository = pFactory.getHome().getRepository();

		final Map<String, ColumnData> columns = new HashMap<String, ColumnData>();
		final List<PreflightFinding> all = new ArrayList<PreflightFinding>();
		final List<PreflightLink> links = new ArrayList<PreflightLink>();
		boolean stopped = false;
		for (final TransferPlan.MappingInfo mapping : mappings)
		{
			if (pCancelled != null && pCancelled.getAsBoolean())
			{
				stopped = true;
				break;
			}
			final boolean collection = mapping.feed != null && mapping.feed.collectionPath != null && !mapping.feed.collectionPath.trim().isEmpty();
			ColumnData data = null;
			if (!collection)
			{
				final String key = mapping.landingDataspace + "|" + mapping.landingDataset + "|" + mapping.landingTableName + "|" + mapping.landingField;
				data = columns.get(key);
				if (data == null && !columns.containsKey(key))
				{
					data = StagingPreflight.readColumn(pSession, repository, mapping, notes, pNote);
					columns.put(key, data);
				}
				if (data == null)
				{
					continue; // the landing column could not be read: said in the notes, never guessed
				}
			}
			final boolean single = singleBusinessKeys.containsKey(mapping.entityName) && singleBusinessKeys.get(mapping.entityName).equals(mapping.stagingField);
			final PreflightLink link = new PreflightLink(mapping.mappingId, mapping.entityName, mapping.stagingField, mapping.dataType, mapping.maxLength, mapping.mandatory,
				mapping.sourceSystem, mapping.landingTableName, mapping.landingField, mapping.cleaningSteps, mapping.pipeline, data == null ? null : data.values,
				data != null && data.profile.sampled, data == null ? -1L : data.rows, data == null ? -1L : data.filled, single, collection, data == null ? null : data.profile);
			final String fieldName = mapping.landingField;
			final TransformVerifier.Evaluator evaluator = new TransformVerifier.Evaluator()
			{
				@Override
				public String apply(final String pPipeline, final String pValue) throws Exception
				{
					final ObjectNode document = StagingPreflight.MAPPER.createObjectNode();
					document.put(fieldName, pValue);
					context.setDocumentRoot(document);
					return Transformations.text(Transformations.apply(pPipeline, pValue, context));
				}
			};
			links.add(link);
			all.add(PreflightCheck.check(link, evaluator, lookups));
		}
		if (stopped)
		{
			notes.add("the check was stopped before every link was looked at");
		}
		else
		{
			// lot M7 (2026-10-18): an ACCEPTED entity no accepted mapping feeds - a warning, the build goes on, the load will say "no feed" on the row
			final java.util.Set<String> fed = new java.util.HashSet<String>();
			for (final TransferPlan.MappingInfo mapping : mappings)
			{
				fed.add(mapping.entityName);
			}
			for (final String entityName : StagingPreflight.acceptedEntityNames(pFactory, projectId))
			{
				if (!fed.contains(entityName))
				{
					all.add(PreflightCheck.noFeed(entityName));
				}
			}
			// lot M15 (2026-10-18): an attribute the other sources feed that this source leaves unfed although one of its filled columns holds the same kind of values
			all.addAll(PreflightCheck.notFedBySource(links, StagingPreflight.unmappedColumns(pSession, pFactory, repository, mappings, links, notes)));
			// B2B test (2026-10-23): values a link gives an attribute that do not have the identifier shape its other links give it
			all.addAll(PreflightCheck.shapeOutliers(new ArrayList<PreflightFinding>(all)));
		}
		return PreflightReport.of(pProjectCode, System.currentTimeMillis(), all, StagingPreflight.derivationsWaiting(pSession, pFactory, projectId), notes);
	}

	/** A landing column is never offered to the unfed-by-source check when it is longer than this (a phone number is short). */
	private static final int UNMAPPED_MAX_LENGTH = 40;

	/**
	 * Lot M15 (2026-10-18): the filled landing columns that NO accepted mapping reads, of the tables a source reads into an entity that has an attribute the other sources
	 * feed with phone-like values ({@link PreflightCheck#sourcesToLookAt}) - read only for those (entity, source) pairs, text columns of at most {@link #UNMAPPED_MAX_LENGTH}
	 * characters and a fill rate above 0, their values profiled like a mapped column. Nothing is read when no attribute qualifies. A column that cannot be read is said in the
	 * notes and left out.
	 */
	private static List<PreflightCheck.UnmappedColumn> unmappedColumns(final Session pSession, final Adaptation pFactory, final Repository pRepository,
		final List<TransferPlan.MappingInfo> pMappings, final List<PreflightLink> pLinks, final List<String> pNotes)
	{
		final List<PreflightCheck.UnmappedColumn> out = new ArrayList<PreflightCheck.UnmappedColumn>();
		final Map<String, java.util.Set<String>> toLookAt = PreflightCheck.sourcesToLookAt(pLinks);
		if (toLookAt.isEmpty())
		{
			return out;
		}
		final java.util.Set<String> mapped = new java.util.HashSet<String>();
		for (final TransferPlan.MappingInfo mapping : pMappings)
		{
			mapped.add(mapping.landingDataspace + "|" + mapping.landingDataset + "|" + mapping.landingTableName + "|" + mapping.landingField);
		}
		final java.util.Set<String> seen = new java.util.HashSet<String>();
		for (final TransferPlan.MappingInfo mapping : pMappings)
		{
			final java.util.Set<String> sources = toLookAt.get(mapping.entityName);
			if (sources == null || !sources.contains(mapping.sourceSystem) || mapping.mappingId.isEmpty()
				|| !seen.add(mapping.entityName + "|" + mapping.sourceSystem + "|" + mapping.landingTableName))
			{
				continue;
			}
			final Adaptation mappingRow = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(mapping.mappingId));
			final String landingColumnId = mappingRow == null ? null : mappingRow.getString(FactoryPaths.AttributeMapping.LANDING_COLUMN);
			final Adaptation landingColumn = landingColumnId == null ? null
				: pFactory.getTable(FactoryPaths.LANDING_COLUMN).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(landingColumnId));
			final String landingTableId = landingColumn == null ? null : landingColumn.getString(FactoryPaths.LandingColumn.LANDING_TABLE);
			final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(mapping.landingDataspace));
			final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(mapping.landingDataset));
			if (landingTableId == null || dataset == null)
			{
				continue;
			}
			final QualitySuggester.LandingTableRef ref;
			try
			{
				ref = new QualitySuggester.LandingTableRef(dataset, dataset.getTable(Path.parse("/root/" + mapping.landingTableName)));
			}
			catch (final RuntimeException ex)
			{
				pNotes.add("the landing table " + mapping.landingTableName + " could not be read: its unmapped columns were not looked at");
				continue;
			}
			final List<Adaptation> columnRows = new ArrayList<Adaptation>();
			final RequestResult columns = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(landingTableId));
			try
			{
				for (Adaptation column = columns.nextAdaptation(); column != null; column = columns.nextAdaptation())
				{
					columnRows.add(column);
				}
			}
			finally
			{
				columns.close();
			}
			// B2B test (2026-10-23): the key values of the table, read once, so that a column repeating them (a sub-record's copy of the row's key) is not taken for a phone
			java.util.Set<String> keyValues = null;
			try
			{
				keyValues = StagingPreflight.keyValues(pSession, ref, columnRows);
			}
			catch (final RuntimeException ex)
			{
				keyValues = null; // best effort: without the keys, a column is judged on its values alone, as before
			}
			{
				for (final Adaptation column : columnRows)
				{
					final String field = column.getString(FactoryPaths.LandingColumn.FIELD_NAME);
					final Object fillRate = column.get(FactoryPaths.LandingColumn.FILL_RATE);
					final Object maxLength = column.get(FactoryPaths.LandingColumn.MAX_LENGTH);
					if (field == null || field.startsWith("_") || Boolean.TRUE.equals(column.get(FactoryPaths.LandingColumn.SOURCE_KEY))
						|| !"STRING".equalsIgnoreCase(column.getString(FactoryPaths.LandingColumn.INFERRED_TYPE))
						|| !(fillRate instanceof Number) || ((Number) fillRate).doubleValue() <= 0.0
						|| (maxLength instanceof Number && ((Number) maxLength).intValue() > StagingPreflight.UNMAPPED_MAX_LENGTH)
						|| mapped.contains(mapping.landingDataspace + "|" + mapping.landingDataset + "|" + mapping.landingTableName + "|" + field))
					{
						continue;
					}
					try
					{
						final QualitySuggester.ColumnQuery query = QualitySuggester.distinctValues(pSession, ref, field, null, StagingPreflight.MAX_DISTINCT_VALUES);
						final QualitySuggester.ExactAggregates aggregates = QualitySuggester.exactAggregates(pSession, ref, field);
						out.add(new PreflightCheck.UnmappedColumn(mapping.entityName, mapping.sourceSystem, mapping.landingTableName, field, aggregates.rows, aggregates.filled,
							NormalisationProfiler.profile(query.values, aggregates.distinctCount), PreflightCheck.holdsTableKeys(query.values, keyValues)));
					}
					catch (final RuntimeException ex)
					{
						LoggingCategory.getKernel().warn("[mdm-factory] preflight: could not read " + mapping.landingTableName + "." + field + ": " + ex);
						pNotes.add("the landing column " + mapping.landingTableName + "." + field + " could not be read: it was not looked at for an attribute the other sources feed");
					}
				}
			}
		}
		return out;
	}

	/** The distinct values of the table's single key column (at most {@link #MAX_DISTINCT_VALUES}), trimmed; null when the table has no single key. */
	private static java.util.Set<String> keyValues(final Session pSession, final QualitySuggester.LandingTableRef pRef, final List<Adaptation> pColumns)
	{
		String keyField = null;
		for (final Adaptation column : pColumns)
		{
			if (Boolean.TRUE.equals(column.get(FactoryPaths.LandingColumn.SOURCE_KEY)))
			{
				if (keyField != null)
				{
					return null; // a composite key: no single value to compare with
				}
				keyField = column.getString(FactoryPaths.LandingColumn.FIELD_NAME);
			}
		}
		if (keyField == null)
		{
			return null;
		}
		final java.util.Set<String> out = new java.util.HashSet<String>();
		for (final com.on.ebx.factory.quality.Detectors.ValueCount value : QualitySuggester.distinctValues(pSession, pRef, keyField, null, StagingPreflight.MAX_DISTINCT_VALUES).values)
		{
			if (value.value != null && !value.value.trim().isEmpty())
			{
				out.add(value.value.trim());
			}
		}
		return out;
	}

	/** The names of the project's ACCEPTED / APPLIED entities, in table order (lot M7). */
	private static List<String> acceptedEntityNames(final Adaptation pFactory, final String pProjectId)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult entities = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation entity = entities.nextAdaptation(); entity != null; entity = entities.nextAdaptation())
			{
				if (TechnicalKeyRule.isAcceptedLike(entity.getString(FactoryPaths.StagingEntity.STATUS)))
				{
					out.add(entity.getString(FactoryPaths.StagingEntity.NAME));
				}
			}
		}
		finally
		{
			entities.close();
		}
		return out;
	}

	/** One landing column's values and measures, read once per check. */
	private static final class ColumnData
	{
		final List<com.on.ebx.factory.quality.Detectors.ValueCount> values;
		final long rows;
		final long filled;
		final NormalisationProfile profile;

		ColumnData(final List<com.on.ebx.factory.quality.Detectors.ValueCount> pValues, final long pRows, final long pFilled, final NormalisationProfile pProfile)
		{
			this.values = pValues;
			this.rows = pRows;
			this.filled = pFilled;
			this.profile = pProfile;
		}
	}

	private static ColumnData readColumn(final Session pSession, final Repository pRepository, final TransferPlan.MappingInfo pMapping, final List<String> pNotes,
		final Consumer<String> pNote)
	{
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pMapping.landingDataspace));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pMapping.landingDataset));
		if (dataset == null)
		{
			pNotes.add("the landing dataset " + pMapping.landingDataspace + "/" + pMapping.landingDataset + " was not found: " + pMapping.entityName + "." + pMapping.stagingField
				+ " was not checked");
			return null;
		}
		try
		{
			final QualitySuggester.LandingTableRef ref = new QualitySuggester.LandingTableRef(dataset, dataset.getTable(Path.parse("/root/" + pMapping.landingTableName)));
			final QualitySuggester.ColumnQuery query = QualitySuggester.distinctValues(pSession, ref, pMapping.landingField, null, StagingPreflight.MAX_DISTINCT_VALUES);
			final QualitySuggester.ExactAggregates aggregates = QualitySuggester.exactAggregates(pSession, ref, pMapping.landingField);
			return new ColumnData(query.values, aggregates.rows, aggregates.filled, NormalisationProfiler.profile(query.values, aggregates.distinctCount));
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] preflight: could not read " + pMapping.landingTableName + "." + pMapping.landingField + ": " + ex);
			pNotes.add("the landing column " + pMapping.landingTableName + "." + pMapping.landingField + " could not be read: " + pMapping.entityName + "." + pMapping.stagingField
				+ " was not checked");
			return null;
		}
	}

	/** Entity name to the name of its ONLY business-key attribute (ACCEPTED / APPLIED); an entity with none or several is absent: a composite key cannot be judged column by column. */
	private static Map<String, String> singleBusinessKeys(final Adaptation pFactory, final String pProjectId)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		final RequestResult entities = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation entity = entities.nextAdaptation(); entity != null; entity = entities.nextAdaptation())
			{
				String only = null;
				int count = 0;
				final RequestResult attributes = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE)
					.createRequestResult("./entity=" + CatalogReader.quote(entity.getOccurrencePrimaryKey().format()));
				try
				{
					for (Adaptation attribute = attributes.nextAdaptation(); attribute != null; attribute = attributes.nextAdaptation())
					{
						if (Boolean.TRUE.equals(attribute.get(FactoryPaths.StagingAttribute.BUSINESS_KEY))
							&& TechnicalKeyRule.isAcceptedLike(attribute.getString(FactoryPaths.StagingAttribute.STATUS)))
						{
							count++;
							only = attribute.getString(FactoryPaths.StagingAttribute.NAME);
						}
					}
				}
				finally
				{
					attributes.close();
				}
				if (count == 1 && only != null)
				{
					out.put(entity.getString(FactoryPaths.StagingEntity.NAME), only);
				}
			}
		}
		finally
		{
			entities.close();
		}
		return out;
	}

	/** The accepted derivations the wizard shows as waiting for a parameter ({@link DerivationParamGate}); empty when the snapshot cannot be read (the check is never lost for it). */
	private static List<PreflightReport.Waiting> derivationsWaiting(final Session pSession, final Adaptation pFactory, final String pProjectId)
	{
		final List<PreflightReport.Waiting> out = new ArrayList<PreflightReport.Waiting>();
		try
		{
			final WizardSnapshot snapshot = WizardData.readSnapshot(pFactory, pProjectId, pSession);
			for (final StillToDecide.CheckItem item : DerivationParamGate.waiting(StillToDecide.of(snapshot).toApply))
			{
				out.add(new PreflightReport.Waiting(item.entityName, item.element, item.toBeProvidedRemaining()));
			}
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] preflight: could not read the derivations waiting for a parameter: " + ex);
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------- the stored report

	/** Runs the check and stores the report (a {@link PreflightReport#STEP} row). */
	public static PreflightReport runAndStore(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Consumer<String> pNote,
		final BooleanSupplier pCancelled) throws OperationException
	{
		final PreflightReport report = StagingPreflight.run(pSession, pFactory, pProjectCode, pNote, pCancelled);
		StagingPreflight.store(pSession, pFactory, pProjectCode, report);
		return report;
	}

	/** Stores {@code pReport} as the project's last report. Best effort: a bookkeeping failure is logged, never thrown (the report is still returned to the caller). */
	static void store(final Session pSession, final Adaptation pFactory, final String pProjectCode, final PreflightReport pReport)
	{
		final Adaptation project = StagingPreflight.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			return;
		}
		try
		{
			final String projectId = project.getOccurrencePrimaryKey().format();
			final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, PreflightReport.STEP, "");
			PipelineSteps.end(pSession, pFactory, stepId, true, StagingPreflight.summary(pReport), pReport.toJson().toString());
		}
		catch (final OperationException | RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] preflight: could not store the report of " + pProjectCode + ": " + ex);
		}
	}

	/** The English one-liner stored as the step's message. */
	static String summary(final PreflightReport pReport)
	{
		if (pReport.clean())
		{
			return "Check before the build: nothing found on " + pReport.linksChecked + " link(s).";
		}
		final StringBuilder text = new StringBuilder("Check before the build: ");
		if (pReport.blocking())
		{
			text.append(pReport.blockingRows()).append(" row(s) would be refused on ").append(pReport.blockingLinks()).append(" link(s)");
		}
		else
		{
			text.append("nothing would be refused");
		}
		return text.append(", ").append(pReport.warnings()).append(" warning(s).").toString();
	}

	/** The last stored report of the project, or null when none was stored (or the stored text is not a report). */
	public static PreflightReport last(final Adaptation pFactory, final String pProjectCode)
	{
		final Adaptation project = StagingPreflight.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			return null;
		}
		final String stepId = PipelineSteps.latestStepId(pFactory, project.getOccurrencePrimaryKey().format(), PreflightReport.STEP);
		if (stepId == null)
		{
			return null;
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.PIPELINE_STEP).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(stepId));
		return row == null ? null : PreflightReport.fromJson(row.getString(FactoryPaths.PipelineStep.DETAIL));
	}

	/**
	 * Whether a build of the project started after {@code pReport} was made: the report is then history, not what stands in the way - the page stops showing it as the thing to
	 * act on (a "Build anyway" or a fixed link followed by a build).
	 */
	public static boolean builtSince(final Adaptation pFactory, final String pProjectCode, final PreflightReport pReport)
	{
		final Adaptation project = StagingPreflight.findProject(pFactory, pProjectCode);
		if (project == null || pReport == null)
		{
			return false;
		}
		final String stepId = PipelineSteps.latestStepId(pFactory, project.getOccurrencePrimaryKey().format(), StagingLoadRun.STEP);
		if (stepId == null)
		{
			return false;
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.PIPELINE_STEP).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(stepId));
		if (row == null || !"DONE".equals(row.getString(FactoryPaths.PipelineStep.STATUS)))
		{
			return false;
		}
		final Object started = row.get(FactoryPaths.PipelineStep.STARTED_AT);
		return started instanceof Date && ((Date) started).getTime() > pReport.at;
	}

	// ------------------------------------------------------------------------------------------------------------ the fix

	/** What {@link #applyFix} did. */
	public static final class FixOutcome
	{
		public int applied;
		/** Links whose fix was no longer valid (the mapping changed since the report): re-check. */
		public int stale;
		/** Links asked for that carry no fix. */
		public int missing;
		public final List<String> notes = new ArrayList<String>();
	}

	/**
	 * Writes the fix of the last report: {@code AttributeMapping.pipeline} := the pipeline that converts every sampled value, the status untouched (ACCEPTED / APPLIED), a "Value
	 * check" note saying what changed. One link when {@code pMappingId} is given, every fixable blocking link otherwise. A link whose pipeline is no longer the one the report
	 * judged is left alone ({@link FixOutcome#stale}): the person decided something else since.
	 */
	public static FixOutcome applyFix(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pMappingId) throws OperationException
	{
		final FixOutcome outcome = new FixOutcome();
		final PreflightReport report = StagingPreflight.last(pFactory, pProjectCode);
		if (report == null)
		{
			outcome.missing++;
			outcome.notes.add("no check was made yet");
			return outcome;
		}
		final List<PreflightFinding> targets = new ArrayList<PreflightFinding>();
		if (pMappingId == null || pMappingId.trim().isEmpty() || "*".equals(pMappingId.trim()))
		{
			targets.addAll(report.fixable());
		}
		else
		{
			final PreflightFinding finding = report.byMapping(pMappingId.trim());
			if (finding == null || !finding.fixable())
			{
				outcome.missing++;
				return outcome;
			}
			targets.add(finding);
		}
		for (final PreflightFinding finding : targets)
		{
			final Adaptation row = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(finding.mappingId));
			if (row == null || !TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.AttributeMapping.STATUS)))
			{
				outcome.stale++;
				continue;
			}
			final String stored = row.getString(FactoryPaths.AttributeMapping.PIPELINE);
			final String effective = NormalisationSuggester.effectivePipeline(row, finding.dataType);
			if (effective == null || !effective.equals(finding.pipeline))
			{
				outcome.stale++; // the link was edited since the report: its fix was made for another chain
				continue;
			}
			final NoteTexts.Note note = NoteTexts.Note.of(stored == null || stored.trim().isEmpty() ? NoteTexts.N_PREFLIGHT_FIX_REPLACES : NoteTexts.N_PREFLIGHT_FIX,
				finding.pipeline.isEmpty() ? NoteTexts.frag(NoteTexts.F_NO_TRANSFORMATION) : "'" + finding.pipeline + "'", Long.valueOf(finding.blockingRows()), finding.fixPipeline);
			final String rationale = CheckNote.with(row.getString(FactoryPaths.AttributeMapping.RATIONALE), note, null, StagingPreflight.RATIONALE_MAX_LENGTH);
			final String newPipeline = finding.fixPipeline;
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(newPipeline, FactoryPaths.AttributeMapping.PIPELINE);
					value.setValue(rationale, FactoryPaths.AttributeMapping.RATIONALE);
					pContext.doModifyContent(row, value);
				}
			});
			if (result.hasFailed())
			{
				outcome.notes.add("could not write the fix of " + finding.entityName + "." + finding.attributeName + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
				continue;
			}
			outcome.applied++;
		}
		return outcome;
	}

	// ------------------------------------------------------------------------------------------------------------ helpers

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}
}
