package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.TransformationContext;
import com.on.ebx.connect.core.Transformations;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.CodeMap;
import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.staging.CheckNote;
import com.on.ebx.factory.staging.NoteTexts;
import com.on.ebx.factory.staging.MappingScope;
import com.on.ebx.factory.staging.NormalisationPass;
import com.on.ebx.factory.staging.NormalisationProfile;
import com.on.ebx.factory.staging.NormalisationProfiler;
import com.on.ebx.factory.staging.StepChainCheck;
import com.on.ebx.factory.staging.TransformVerifier;
import com.on.ebx.factory.transfer.PipelineCompat;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Staging normalisation lot D (2026-10-17, docs/UX-STAGING-TEST-FIXES-D.md): the EBX side of the deterministic,
 * VERIFIED normalisation of the staging proposal. Measured live on project TiersB2CRetestLanding (report of
 * 2026-10-17): every cleaning step and every transform of the proposal came from the language model, none was checked
 * on the values - {@code date_naissance} written {@code 10/06/1963} was mapped with {@code isodate()} (which only cuts an
 * ISO text), the whole loyalty source was refused at the load, and a {@code STRIP_CHARS} / {@code digits()} dropped the
 * {@code +} of every phone number. The module already had a catalogue of verified cleaning steps ({@code MappingStep}),
 * but only {@code quality.Detectors} (trim, collapse, empty-to-null) proposed any, and only in the optional quality stage.
 * <p>
 * One run does, over the mappings in {@link MappingScope#PROPOSAL} of a project:
 * <ol>
 * <li><b>Profile</b> the landing column of every mapping ({@link NormalisationProfiler}) from the distinct values with row
 * counts that {@link QualitySuggester#distinctValues} already reads - bounded (2000 distinct values per column), no new
 * connector call, nothing leaves the instance.</li>
 * <li><b>Propose</b> the catalogue's steps from the profile ({@link NormalisationPass}) and write them as {@code
 * MappingStep} rows (origin {@code RULE}, status {@code PROPOSED}, evidence {@code valuesSeen}/{@code valuesChanged}/
 * {@code examples}, measured confidence). An own row ({@link NormalisationPass#MARKER}) still {@code PROPOSED} is
 * refreshed or withdrawn on a re-run; anything a person decided is never touched.</li>
 * <li><b>Repair</b> a {@code STRIP_CHARS} step (the language model's included) that strips the {@code +} of a phone-like
 * column.</li>
 * <li><b>Verify</b> the chain of every {@code PROPOSED} mapping ({@link TransformVerifier}): the steps as they will run,
 * then the mapping's pipeline - or the pipeline its type-driven transformation translates to - through {@code
 * Transformations.apply}, the engine the loader runs, on the sampled values; a typed target must receive its xs form. A
 * chain that fails is replaced by the pipeline the profile suggests only when that converts 100 % of the non-empty values;
 * otherwise the link is <b>flagged</b>: its confidence is set to the measured success rate and never above {@value
 * #FLAG_CONFIDENCE_CAP} (below the 60 % bar of "accept all", so the Mapping matrix queues it and the bulk accept leaves it
 * out), and a "Value check" note ({@link CheckNote}) with the reason is appended to its rationale. The codes of two sources
 * that do not line up are flagged the same way.</li>
 * </ol>
 * <b>What it never does</b>: touch an {@code ACCEPTED}/{@code APPLIED}/{@code REJECTED} row, invent a code mapping, call a
 * connector or the language model, or fail the proposal run (the hooks in {@link NormalisationHooks} catch everything).
 * <p>
 * <b>Data policy</b>: the language model never sees the profile of a sensitive column; this class reads those columns like
 * any other because it never sends anything anywhere - the values stay in the instance, and the notes and examples it
 * writes are counts and shapes.
 */
public final class NormalisationSuggester
{
	/** A flagged link's confidence is never above this: one under the bulk-accept bar. */
	public static final int FLAG_CONFIDENCE_CAP = MatrixCompute.DEFAULT_BULK_THRESHOLD - 1;
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int RATIONALE_MAX_LENGTH = 2000;
	private static final int EXAMPLES_MAX_LENGTH = 2000;
	/** The cache holds at most this many distinct values for a run; the columns read after that are re-read when asked again. */
	private static final int CACHE_MAX_VALUES = 400000;
	private static final long CACHE_MAX_AGE_MS = 30L * 60L * 1000L;

	private NormalisationSuggester()
	{
	}

	// ------------------------------------------------------------------------------------------------------- result

	/** What a run did. */
	public static final class Outcome
	{
		public int mappings;
		public int stepsCreated;
		public int stepsRefreshed;
		public int stepsWithdrawn;
		public int stepsRepaired;
		public int pipelinesFixed;
		public int linksFlagged;
		public int notVerifiable;
		public final List<String> notes = new ArrayList<String>();
		/** Codes transcoding lot H3 (2026-10-17): the code lists of several sources that do not line up (after the code tables already there), with their codes - what {@code CodeTranscodingSuggester} asks the language model about. */
		public final List<NormalisationPass.Alignment> alignments = new ArrayList<NormalisationPass.Alignment>();

		/** True when the run changed or flagged nothing. */
		public boolean isEmpty()
		{
			return this.stepsCreated + this.stepsRefreshed + this.stepsWithdrawn + this.stepsRepaired + this.pipelinesFixed + this.linksFlagged == 0;
		}
	}

	// ---------------------------------------------------------------------------------------------------- the cache

	/** One landing column's data and profile, read once per proposal run. */
	private static final class ColumnData
	{
		final List<Detectors.ValueCount> values;
		final QualitySuggester.ExactAggregates aggregates;
		final NormalisationProfile profile;

		ColumnData(final List<Detectors.ValueCount> pValues, final QualitySuggester.ExactAggregates pAggregates, final NormalisationProfile pProfile)
		{
			this.values = pValues;
			this.aggregates = pAggregates;
			this.profile = pProfile;
		}
	}

	/** Per project, for the few minutes of a proposal run: the three hooks of the run read the same landing columns. */
	static final class RunCache
	{
		final Map<String, ColumnData> columns = new HashMap<String, ColumnData>();
		final Map<String, QualitySuggester.LandingTableRef> landingTables = new HashMap<String, QualitySuggester.LandingTableRef>();
		CleaningSteps.Lookups lookups;
		final long startedAt;
		int cachedValues;

		RunCache()
		{
			this(System.currentTimeMillis());
		}

		RunCache(final long pStartedAt)
		{
			this.startedAt = pStartedAt;
		}
	}

	private static final Map<String, RunCache> CACHES = new HashMap<String, RunCache>();

	/** At most this many projects hold a cache at once (audit F2-110: a bound on the memory, on top of the per-run value cap); the oldest is evicted. */
	static final int CACHE_MAX_PROJECTS = 3;

	static synchronized RunCache cache(final String pProjectId, final boolean pFresh)
	{
		final long now = System.currentTimeMillis();
		// audit F2-110: a run that never ended (an interrupted proposal) must not hold its columns until the next run of the SAME project: every access
		// drops what is older than the age limit, whichever project it belongs to
		for (final java.util.Iterator<Map.Entry<String, RunCache>> it = NormalisationSuggester.CACHES.entrySet().iterator(); it.hasNext();)
		{
			if (now - it.next().getValue().startedAt > NormalisationSuggester.CACHE_MAX_AGE_MS)
			{
				it.remove();
			}
		}
		RunCache cache = NormalisationSuggester.CACHES.get(pProjectId);
		if (pFresh || cache == null)
		{
			cache = new RunCache();
			NormalisationSuggester.CACHES.remove(pProjectId);
			while (NormalisationSuggester.CACHES.size() >= NormalisationSuggester.CACHE_MAX_PROJECTS)
			{
				String oldest = null;
				long oldestAt = Long.MAX_VALUE;
				for (final Map.Entry<String, RunCache> entry : NormalisationSuggester.CACHES.entrySet())
				{
					if (entry.getValue().startedAt < oldestAt)
					{
						oldestAt = entry.getValue().startedAt;
						oldest = entry.getKey();
					}
				}
				NormalisationSuggester.CACHES.remove(oldest);
			}
			NormalisationSuggester.CACHES.put(pProjectId, cache);
		}
		return cache;
	}

	/** How many projects hold a cache right now. Package-visible for the tests (audit F2-110). */
	static synchronized int cachedProjects()
	{
		return NormalisationSuggester.CACHES.size();
	}

	/** Test hook (audit F2-110): puts a cache for {@code pProjectId} that started {@code pAgeMs} ago. */
	static synchronized void seedForTest(final String pProjectId, final long pAgeMs)
	{
		final RunCache cache = new RunCache(System.currentTimeMillis() - pAgeMs);
		NormalisationSuggester.CACHES.put(pProjectId, cache);
	}

	/** Drops the cache of a project (end of a proposal run). */
	static synchronized void release(final String pProjectId)
	{
		NormalisationSuggester.CACHES.remove(pProjectId);
	}

	// -------------------------------------------------------------------------------------------------- entry point

	/**
	 * @param pFreshRun true at the start of a proposal run (the landing columns are read again), false for the later hooks of the same run
	 */
	public static Outcome run(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pFreshRun) throws OperationException
	{
		final Outcome outcome = new Outcome();
		final RequestResult projectRows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		final Adaptation project;
		try
		{
			project = projectRows.nextAdaptation();
		}
		finally
		{
			projectRows.close();
		}
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		ProjectOrigin.refuseIfDelivered(pFactory, project);
		final String projectId = project.getOccurrencePrimaryKey().format();
		final String landingDataspace = project.getString(FactoryPaths.Project.LANDING_DATASPACE);
		if (landingDataspace == null || landingDataspace.trim().isEmpty())
		{
			outcome.notes.add("no landing zone yet");
			return outcome;
		}
		final RunCache cache = NormalisationSuggester.cache(projectId, pFreshRun);
		if (cache.lookups == null)
		{
			cache.lookups = QualitySuggester.buildLookups(pFactory, outcome.notes);
		}
		final List<QualitySuggester.MappingRow> mappings = QualitySuggester.readMappings(pFactory, projectId, MappingScope.PROPOSAL);
		outcome.mappings = mappings.size();

		// ---- 1. read: one entry per mapping that has a landing column to look at
		final List<Entry> entries = new ArrayList<Entry>();
		final Set<String> coveredMappingIds = new LinkedHashSet<String>();
		for (final QualitySuggester.MappingRow mapping : mappings)
		{
			final Adaptation mappingRow = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(mapping.mappingId));
			// lot G2 (2026-10-17): a mapping whose pipeline does not read its column (constant(HOME)) is neither profiled nor given a step - and, left out of
			// the covered set, an own step still PROPOSED on it is withdrawn by the sweep below
			if (mappingRow != null && !NormalisationPass.readsColumn(mappingRow.getString(FactoryPaths.AttributeMapping.PIPELINE), mapping.sourceColumn))
			{
				continue;
			}
			coveredMappingIds.add(mapping.mappingId);
			if (mappingRow == null || NormalisationSuggester.isCollectionFeed(pFactory, mappingRow.getString(FactoryPaths.AttributeMapping.FEED)))
			{
				continue; // a collection feed reads a sub-element of the row: the landing column's values are not its values
			}
			final ColumnData data = NormalisationSuggester.columnData(pSession, pFactory, landingDataspace, cache, mapping);
			if (data == null || data.profile.filled == 0L)
			{
				continue;
			}
			entries.add(new Entry(mapping, mappingRow, data, NormalisationSuggester.readSteps(pFactory, mapping.mappingId)));
		}

		// ---- 2. repair a language-model STRIP_CHARS that strips the +
		for (final Entry entry : entries)
		{
			NormalisationSuggester.repairStripChars(pSession, pFactory, entry, outcome);
		}

		// ---- 3. propose the deterministic steps and write them
		final List<NormalisationPass.Column> columns = new ArrayList<NormalisationPass.Column>();
		for (final Entry entry : entries)
		{
			columns.add(new NormalisationPass.Column(entry.mapping.mappingId, entry.mapping.attributeId, entry.mapping.attributeName, entry.mapping.sourceColumn,
				entry.mapping.sourceSystem, entry.mapping.dataType, NormalisationSuggester.effectiveDeclaredPipeline(entry), entry.data.profile, entry.data.values,
				entry.data.aggregates.distinctCount, entry.foreignKinds(), NormalisationSuggester.codeTableOf(entry.steps) == null ? null
				: CodeMap.parseCached(NormalisationSuggester.codeTableOf(entry.steps).argument), entry.mapping.entityName));
		}
		final NormalisationPass.Result proposed = NormalisationPass.propose(columns, cache.lookups);
		for (final Entry entry : entries)
		{
			NormalisationSuggester.writeSteps(pSession, pFactory, entry, proposed.stepsOf(entry.mapping.mappingId), outcome);
		}
		outcome.stepsWithdrawn += NormalisationSuggester.withdrawOrphans(pSession, pFactory, projectId, coveredMappingIds);

		// ---- 4. verify the chains, fix or flag
		final Map<String, Flag> flags = new LinkedHashMap<String, Flag>();
		// lot E (2026-10-17): the WHOLE chain of steps, on the raw values - a step that drops the + of a phone, or changes nothing after the earlier ones
		for (final Entry entry : entries)
		{
			NormalisationSuggester.reconcileChain(pSession, pFactory, entry, cache.lookups, flags, outcome);
		}
		outcome.alignments.addAll(proposed.alignments);
		for (final NormalisationPass.Alignment alignment : proposed.alignments)
		{
			for (final String mappingId : alignment.mappingIds)
			{
				NormalisationSuggester.flag(flags, mappingId).add(alignment.note, NormalisationSuggester.FLAG_CONFIDENCE_CAP);
			}
		}
		// codes transcoding lot H3 (2026-10-17): the code table of a mapping is verified on the values that reach it - a code with no target is a failure, the link is flagged
		for (final Entry entry : entries)
		{
			NormalisationSuggester.verifyCodeTable(entry, cache.lookups, flags);
		}
		final TransformationContext context = new TransformationContext(null, null, pSession);
		for (final Entry entry : entries)
		{
			NormalisationSuggester.verify(pFactory, context, cache, entry, flags, outcome);
		}
		for (final Entry entry : entries)
		{
			final Flag flag = flags.get(entry.mapping.mappingId);
			NormalisationSuggester.applyToMapping(pSession, pFactory, entry, flag, outcome);
		}
		LoggingCategory.getKernel().info("[mdm-factory] normalisation " + pProjectCode + ": " + outcome.mappings + " mapping(s), " + outcome.stepsCreated + " step(s) created, "
			+ outcome.stepsRefreshed + " refreshed, " + outcome.stepsWithdrawn + " withdrawn, " + outcome.stepsRepaired + " repaired, " + outcome.pipelinesFixed
			+ " pipeline(s) fixed, " + outcome.linksFlagged + " link(s) flagged, " + outcome.notVerifiable + " not verifiable.");
		return outcome;
	}

	// ----------------------------------------------------------------------------------------------------- the data

	/** One mapping in the run, with what is read about it. */
	private static final class Entry
	{
		final QualitySuggester.MappingRow mapping;
		final Adaptation mappingRow;
		final ColumnData data;
		/** The mapping's steps (every status but REJECTED), in position order. */
		final List<StepRow> steps;

		Entry(final QualitySuggester.MappingRow pMapping, final Adaptation pMappingRow, final ColumnData pData, final List<StepRow> pSteps)
		{
			this.mapping = pMapping;
			this.mappingRow = pMappingRow;
			this.data = pData;
			this.steps = pSteps;
		}

		String status()
		{
			return this.mappingRow.getString(FactoryPaths.AttributeMapping.STATUS);
		}

		boolean isProposed()
		{
			return "PROPOSED".equals(this.status());
		}

		/** Kinds of the steps the pass must treat as "already there": every non-rejected step except an own, still PROPOSED one (the pass recomputes those). */
		Set<String> foreignKinds()
		{
			final Set<String> kinds = new LinkedHashSet<String>();
			for (final StepRow step : this.steps)
			{
				if (!step.isOwnProposed())
				{
					kinds.add(step.kind);
				}
			}
			return kinds;
		}
	}

	private static final class StepRow
	{
		final Adaptation row;
		final String kind;
		final String argument;
		final String status;
		final String origin;
		final String rationale;
		final int position;

		StepRow(final Adaptation pRow)
		{
			this.row = pRow;
			this.kind = pRow.getString(FactoryPaths.MappingStep.KIND);
			this.argument = pRow.getString(FactoryPaths.MappingStep.ARGUMENT);
			this.status = pRow.getString(FactoryPaths.MappingStep.STATUS);
			this.origin = pRow.getString(FactoryPaths.MappingStep.ORIGIN);
			this.rationale = pRow.getString(FactoryPaths.MappingStep.RATIONALE);
			final Object rawPosition = pRow.get(FactoryPaths.MappingStep.POSITION);
			this.position = rawPosition instanceof Number ? ((Number) rawPosition).intValue() : Integer.MAX_VALUE;
		}

		boolean isOwnProposed()
		{
			return "PROPOSED".equals(this.status) && "RULE".equals(this.origin) && NormalisationPass.isOwnRationale(this.rationale);
		}
	}

	private static List<StepRow> readSteps(final Adaptation pFactory, final String pMappingId)
	{
		final List<StepRow> out = new ArrayList<StepRow>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.MAPPING_STEP).createRequestResult("./mapping=" + CatalogReader.quote(pMappingId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!"REJECTED".equals(row.getString(FactoryPaths.MappingStep.STATUS)))
				{
					out.add(new StepRow(row));
				}
			}
		}
		finally
		{
			rows.close();
		}
		Collections.sort(out, new Comparator<StepRow>()
		{
			@Override
			public int compare(final StepRow pLeft, final StepRow pRight)
			{
				return Integer.compare(pLeft.position, pRight.position);
			}
		});
		return out;
	}

	private static boolean isCollectionFeed(final Adaptation pFactory, final String pFeedId)
	{
		if (pFeedId == null || pFeedId.trim().isEmpty())
		{
			return false;
		}
		final Adaptation feed = pFactory.getTable(FactoryPaths.TRANSFER_FEED).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pFeedId));
		final String collectionPath = feed == null ? null : feed.getString(FactoryPaths.TransferFeed.COLLECTION_PATH);
		return collectionPath != null && !collectionPath.trim().isEmpty();
	}

	private static ColumnData columnData(final Session pSession, final Adaptation pFactory, final String pLandingDataspace, final RunCache pCache,
		final QualitySuggester.MappingRow pMapping)
	{
		final String key = pMapping.landingTableId + "|" + pMapping.fieldName;
		ColumnData data;
		synchronized (pCache)
		{
			data = pCache.columns.get(key);
		}
		if (data != null)
		{
			return data;
		}
		final QualitySuggester.LandingTableRef ref = QualitySuggester.landingTableRef(pFactory, pLandingDataspace, pMapping, pCache.landingTables);
		if (ref == null)
		{
			return null;
		}
		try
		{
			final QualitySuggester.ColumnQuery query = QualitySuggester.distinctValues(pSession, ref, pMapping.fieldName, null, QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES);
			// staging polish (2026-10-17): above mdm.factory.profile.maxRowsFullScan rows the column is read on its first rows, and the profile says so
			final QualitySuggester.ExactAggregates aggregates = QualitySuggester.boundedAggregates(pSession, ref, pMapping.fieldName);
			data = new ColumnData(query.values, aggregates, NormalisationProfiler.profile(query.values, aggregates.distinctCount).markRowSample(query.sampledRows));
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] normalisation: could not read " + pMapping.landingTableName + "." + pMapping.fieldName + ": " + ex);
			return null;
		}
		synchronized (pCache)
		{
			if (pCache.cachedValues + data.values.size() <= NormalisationSuggester.CACHE_MAX_VALUES)
			{
				pCache.cachedValues += data.values.size();
				pCache.columns.put(key, data);
			}
		}
		return data;
	}

	/** The pipeline the mapping declares, or empty. */
	private static String effectiveDeclaredPipeline(final Entry pEntry)
	{
		final String pipeline = pEntry.mappingRow.getString(FactoryPaths.AttributeMapping.PIPELINE);
		return pipeline == null ? "" : pipeline.trim();
	}

	/**
	 * The chain as the loader will run it: the mapping's pipeline, or what its type-driven transformation translates to
	 * ({@link PipelineCompat}); null when an old row cannot be translated (not verifiable).
	 */
	static String effectivePipeline(final Adaptation pMappingRow, final String pDataType)
	{
		final String pipeline = pMappingRow.getString(FactoryPaths.AttributeMapping.PIPELINE);
		if (pipeline != null && !pipeline.trim().isEmpty())
		{
			return pipeline.trim();
		}
		final String transformation = pMappingRow.getString(FactoryPaths.AttributeMapping.TRANSFORMATION);
		if (transformation == null || transformation.trim().isEmpty())
		{
			return "";
		}
		try
		{
			return PipelineCompat.translate(transformation, pMappingRow.getString(FactoryPaths.AttributeMapping.ARGUMENT), pDataType);
		}
		catch (final IllegalArgumentException ex)
		{
			return null;
		}
	}

	// ----------------------------------------------------------------------------------------------- repair a step

	private static void repairStripChars(final Session pSession, final Adaptation pFactory, final Entry pEntry, final Outcome pOutcome)
	{
		for (final StepRow step : pEntry.steps)
		{
			// lot E (2026-10-17): NORMALISE_IDENTIFIER strips characters too (a model's " .+-" removed the + after the rule pass had kept it)
			if (!StepChainCheck.stripsCharacters(step.kind) || !"PROPOSED".equals(step.status) || step.isOwnProposed())
			{
				continue;
			}
			final String repaired = TransformVerifier.repairStripChars(step.argument, pEntry.data.profile);
			if (repaired == null)
			{
				continue;
			}
			final String rationale = CheckNote.with(step.rationale, NoteTexts.Note.of(NoteTexts.N_PLUS_REPAIR), null, NormalisationSuggester.RATIONALE_MAX_LENGTH);
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					final ValueContextForUpdate value = pContext.getContext(step.row.getAdaptationName());
					value.setValue(repaired, FactoryPaths.MappingStep.ARGUMENT);
					value.setValue(rationale, FactoryPaths.MappingStep.RATIONALE);
					pContext.doModifyContent(step.row, value);
				}
			});
			if (result.hasFailed())
			{
				pOutcome.notes.add("could not repair a STRIP_CHARS step: " + result.getExceptionFullMessage(Locale.ENGLISH));
			}
			else
			{
				pOutcome.stepsRepaired++;
			}
		}
		// the repaired argument is what the next reads see
		final List<StepRow> reread = NormalisationSuggester.readSteps(pFactory, pEntry.mapping.mappingId);
		pEntry.steps.clear();
		pEntry.steps.addAll(reread);
	}

	// ------------------------------------------------------------------------------------------ the whole chain of steps

	/**
	 * Staging feed-conversion lot E (2026-10-17, coordinator addition (a)): {@link StepChainCheck} on every step of the mapping, in position
	 * order, on the raw values of its landing column. Of a step still {@code PROPOSED} that is not this pass's own: one that drops the leading
	 * {@code +} of a phone-like column (whatever its kind) is rejected with a note, and one that changes nothing after the steps before it
	 * is withdrawn the same way (rejected, with the note - never deleted: the row is the model's, a person can read why). A step a person
	 * already decided, or the pass's own, is never touched: if it drops the {@code +}, the MAPPING is flagged (confidence under the bulk
	 * bar, a "Value check" note). Redundancy is judged only when the whole column was read (a rare value past the sample might need the step).
	 */
	private static void reconcileChain(final Session pSession, final Adaptation pFactory, final Entry pEntry, final CleaningSteps.Lookups pLookups,
		final Map<String, Flag> pFlags, final Outcome pOutcome) throws OperationException
	{
		if (pEntry.steps.isEmpty())
		{
			return;
		}
		final List<CleaningSteps.Step> chain = new ArrayList<CleaningSteps.Step>();
		for (final StepRow step : pEntry.steps)
		{
			chain.add(new CleaningSteps.Step(step.kind, step.argument));
		}
		final List<StepChainCheck.StepReport> reports = StepChainCheck.analyse(chain, pEntry.data.values, pLookups);
		final NormalisationProfile profile = pEntry.data.profile;
		final boolean plusMatters = profile.isPhoneLike() && profile.leadingPlusRows() > 0L;
		final List<StepRow> toReject = new ArrayList<StepRow>();
		final List<NoteTexts.Note> reasons = new ArrayList<NoteTexts.Note>();
		for (int i = 0; i < pEntry.steps.size() && i < reports.size(); i++)
		{
			final StepRow step = pEntry.steps.get(i);
			final StepChainCheck.StepReport report = reports.get(i);
			final boolean undecidedForeign = "PROPOSED".equals(step.status) && !step.isOwnProposed();
			if (plusMatters && report.plusLostRows > 0L)
			{
				final NoteTexts.Note text = NoteTexts.Note.of(NoteTexts.N_STEP_DROPS_PLUS, step.kind, Long.valueOf(report.plusLostRows), Long.valueOf(report.seenRows));
				if (undecidedForeign)
				{
					toReject.add(step);
					reasons.add(NoteTexts.Note.of(NoteTexts.N_WITHDRAWN).addAll(text).add(NoteTexts.DOT));
				}
				else
				{
					NormalisationSuggester.flag(pFlags, pEntry.mapping.mappingId).add(text.add(NoteTexts.N_NOT_WITHDRAWABLE), NormalisationSuggester.FLAG_CONFIDENCE_CAP);
				}
			}
			// codes transcoding lot H3 (2026-10-17): a code table is never "redundant" in this sense - one still waiting for its targets (the codes were not shared with the
			// model) changes nothing yet, and a person is the one who fills it
			else if (undecidedForeign && report.redundant() && !profile.sampled && CodeMap.parseCached(step.argument) == null)
			{
				toReject.add(step);
				reasons.add(NoteTexts.Note.of(NoteTexts.N_WITHDRAWN).add(NoteTexts.N_REDUNDANT, step.kind, Long.valueOf(report.seenRows)));
			}
		}
		if (toReject.isEmpty())
		{
			return;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (int i = 0; i < toReject.size(); i++)
				{
					final StepRow step = toReject.get(i);
					final ValueContextForUpdate value = pContext.getContext(step.row.getAdaptationName());
					value.setValue("REJECTED", FactoryPaths.MappingStep.STATUS);
					value.setValue(CheckNote.with(step.rationale, reasons.get(i), null, NormalisationSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.MappingStep.RATIONALE);
					pContext.doModifyContent(step.row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			pOutcome.notes.add("could not withdraw a step of " + pEntry.mapping.entityName + "." + pEntry.mapping.attributeName + ": "
				+ result.getExceptionFullMessage(Locale.ENGLISH));
			return;
		}
		pOutcome.stepsWithdrawn += toReject.size();
		final List<StepRow> reread = NormalisationSuggester.readSteps(pFactory, pEntry.mapping.mappingId);
		pEntry.steps.clear();
		pEntry.steps.addAll(reread);
	}

	// ------------------------------------------------------------------------------------------------- write steps

	private static void writeSteps(final Session pSession, final Adaptation pFactory, final Entry pEntry, final List<NormalisationPass.Step> pSteps, final Outcome pOutcome)
		throws OperationException
	{
		final AdaptationTable stepsTable = pFactory.getTable(FactoryPaths.MAPPING_STEP);
		final Map<String, StepRow> ownByKind = new LinkedHashMap<String, StepRow>();
		int lastPosition = 0;
		for (final StepRow step : pEntry.steps)
		{
			lastPosition = Math.max(lastPosition, step.position == Integer.MAX_VALUE ? 0 : step.position);
			if (step.isOwnProposed())
			{
				ownByKind.put(step.kind, step);
			}
		}
		final List<NormalisationPass.Step> toCreate = new ArrayList<NormalisationPass.Step>();
		final List<Object[]> toRefresh = new ArrayList<Object[]>();
		final Set<String> proposedKinds = new LinkedHashSet<String>();
		for (final NormalisationPass.Step step : pSteps)
		{
			proposedKinds.add(step.kind);
			final StepRow own = ownByKind.get(step.kind);
			if (own != null)
			{
				toRefresh.add(new Object[] { own, step });
			}
			else
			{
				toCreate.add(step);
			}
		}
		final List<StepRow> toWithdraw = new ArrayList<StepRow>();
		for (final StepRow own : ownByKind.values())
		{
			if (!proposedKinds.contains(own.kind))
			{
				toWithdraw.add(own); // the data (or the other sources) no longer ask for it
			}
		}
		if (toCreate.isEmpty() && toRefresh.isEmpty() && toWithdraw.isEmpty())
		{
			return;
		}
		final int startPosition = lastPosition;
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Object[] pair : toRefresh)
				{
					final StepRow own = (StepRow) pair[0];
					final NormalisationPass.Step step = (NormalisationPass.Step) pair[1];
					final ValueContextForUpdate value = pContext.getContext(own.row.getAdaptationName());
					NormalisationSuggester.fill(value, step, false, 0);
					pContext.doModifyContent(own.row, value);
				}
				int position = startPosition;
				for (final NormalisationPass.Step step : toCreate)
				{
					position++;
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(stepsTable);
					value.setValue(pEntry.mapping.mappingId, FactoryPaths.MappingStep.MAPPING);
					NormalisationSuggester.fill(value, step, true, position);
					pContext.doCreateOccurrence(value, stepsTable);
				}
				for (final StepRow own : toWithdraw)
				{
					pContext.doDelete(own.row.getAdaptationName(), false);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveMappingSteps", "Writing the normalisation steps of " + pEntry.mapping.entityName + "." + pEntry.mapping.attributeName
				+ " failed: " + result.getExceptionFullMessage(Locale.ENGLISH), pEntry.mapping.entityName, pEntry.mapping.attributeName);
		}
		pOutcome.stepsCreated += toCreate.size();
		pOutcome.stepsRefreshed += toRefresh.size();
		pOutcome.stepsWithdrawn += toWithdraw.size();
		final List<StepRow> reread = NormalisationSuggester.readSteps(pFactory, pEntry.mapping.mappingId);
		pEntry.steps.clear();
		pEntry.steps.addAll(reread);
	}

	private static void fill(final ValueContextForUpdate pValue, final NormalisationPass.Step pStep, final boolean pCreate, final int pPosition)
	{
		if (pCreate)
		{
			pValue.setValue(Integer.valueOf(pPosition), FactoryPaths.MappingStep.POSITION);
			pValue.setValue(pStep.kind, FactoryPaths.MappingStep.KIND);
			pValue.setValue("RULE", FactoryPaths.MappingStep.ORIGIN);
			pValue.setValue("PROPOSED", FactoryPaths.MappingStep.STATUS);
		}
		pValue.setValue(pStep.argument, FactoryPaths.MappingStep.ARGUMENT);
		pValue.setValue(Integer.valueOf(pStep.confidence), FactoryPaths.MappingStep.CONFIDENCE);
		pValue.setValue(pStep.storedRationale(NormalisationSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.MappingStep.RATIONALE);
		pValue.setValue(BigDecimal.valueOf(pStep.valuesSeen), FactoryPaths.MappingStep.VALUES_SEEN);
		pValue.setValue(BigDecimal.valueOf(pStep.valuesChanged), FactoryPaths.MappingStep.VALUES_CHANGED);
		pValue.setValue(BigDecimal.valueOf(pStep.valuesUnresolved), FactoryPaths.MappingStep.VALUES_UNRESOLVED);
		pValue.setValue(NormalisationSuggester.examplesText(pStep.examples), FactoryPaths.MappingStep.EXAMPLES);
		pValue.setValue(new Date(), FactoryPaths.MappingStep.CHECKED_AT);
	}

	private static String examplesText(final List<String> pExamples)
	{
		if (pExamples == null || pExamples.isEmpty())
		{
			return null;
		}
		final StringBuilder out = new StringBuilder();
		for (final String example : pExamples)
		{
			if (out.length() > 0)
			{
				out.append("; ");
			}
			out.append(example);
		}
		return TextLimits.truncate(out.toString(), NormalisationSuggester.EXAMPLES_MAX_LENGTH);
	}

	/**
	 * Deletes the PROPOSED rows of this pass whose mapping is no longer in scope (typically the rule-pass mappings of an
	 * entity the language-model pass superseded). Only rows of THIS project, only rows of this pass.
	 */
	private static int withdrawOrphans(final Session pSession, final Adaptation pFactory, final String pProjectId, final Set<String> pCoveredMappingIds)
		throws OperationException
	{
		final List<Adaptation> orphans = new ArrayList<Adaptation>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.MAPPING_STEP).createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!"PROPOSED".equals(row.getString(FactoryPaths.MappingStep.STATUS)) || !"RULE".equals(row.getString(FactoryPaths.MappingStep.ORIGIN))
					|| !NormalisationPass.isOwnRationale(row.getString(FactoryPaths.MappingStep.RATIONALE)))
				{
					continue;
				}
				final String mappingId = row.getString(FactoryPaths.MappingStep.MAPPING);
				if (pCoveredMappingIds.contains(mappingId))
				{
					continue;
				}
				if (pProjectId.equals(NormalisationSuggester.projectOfMapping(pFactory, mappingId)))
				{
					orphans.add(row);
				}
			}
		}
		finally
		{
			rows.close();
		}
		if (orphans.isEmpty())
		{
			return 0;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Adaptation row : orphans)
				{
					pContext.doDelete(row.getAdaptationName(), false);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.withdrawProposals", "Withdrawing stale normalisation steps failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		return orphans.size();
	}

	private static String projectOfMapping(final Adaptation pFactory, final String pMappingId)
	{
		final Adaptation mapping = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pMappingId));
		final String attributeId = mapping == null ? null : mapping.getString(FactoryPaths.AttributeMapping.ATTRIBUTE);
		if (attributeId == null || attributeId.trim().isEmpty())
		{
			return null;
		}
		final Adaptation attribute = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(attributeId));
		final String entityId = attribute == null ? null : attribute.getString(FactoryPaths.StagingAttribute.ENTITY);
		if (entityId == null || entityId.trim().isEmpty())
		{
			return null;
		}
		final Adaptation entity = pFactory.getTable(FactoryPaths.STAGING_ENTITY).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(entityId));
		return entity == null ? null : entity.getString(FactoryPaths.StagingEntity.PROJECT);
	}

	// ---------------------------------------------------------------------------------------------------- verification

	/** The first non-rejected step of the list that carries a code table of its own ({@link CodeMap}), or null. */
	private static StepRow codeTableOf(final List<StepRow> pSteps)
	{
		for (final StepRow step : pSteps)
		{
			if (CleaningSteps.TRANSCODE_REFERENCE.equals(step.kind) && CodeMap.parseCached(step.argument) != null)
			{
				return step;
			}
		}
		return null;
	}

	/**
	 * Codes transcoding lot H3: the code table of the mapping run over the values that reach its step (the column's values after the steps before it, nulled ones gone).
	 * Every code with no target - a code that appeared since the table was proposed, a target a person emptied, the targets the model could not give without seeing the
	 * codes - flags the link (confidence under the bulk-accept bar, a "Value check" note with counts only); a table whose spellings collide is flagged too.
	 */
	private static void verifyCodeTable(final Entry pEntry, final CleaningSteps.Lookups pLookups, final Map<String, Flag> pFlags)
	{
		if (!pEntry.isProposed())
		{
			return; // a decided mapping is not touched
		}
		final StepRow tableStep = NormalisationSuggester.codeTableOf(pEntry.steps);
		if (tableStep == null)
		{
			return;
		}
		final CodeMap map = CodeMap.parseCached(tableStep.argument);
		final List<CleaningSteps.Step> before = new ArrayList<CleaningSteps.Step>();
		for (final StepRow step : pEntry.steps)
		{
			if (step == tableStep)
			{
				break;
			}
			before.add(new CleaningSteps.Step(step.kind, step.argument));
		}
		final Map<String, Long> merged = new LinkedHashMap<String, Long>();
		for (final Detectors.ValueCount value : pEntry.data.values)
		{
			String cleaned = value.value;
			if (!before.isEmpty() && value.value != null && !value.value.trim().isEmpty())
			{
				cleaned = CleaningSteps.applyPipeline(before, value.value, null, pLookups).value;
			}
			if (cleaned == null || cleaned.trim().isEmpty())
			{
				continue;
			}
			final Long seen = merged.get(cleaned);
			merged.put(cleaned, Long.valueOf((seen == null ? 0L : seen.longValue()) + value.count));
		}
		final List<Detectors.ValueCount> reaching = new ArrayList<Detectors.ValueCount>();
		for (final Map.Entry<String, Long> entry : merged.entrySet())
		{
			reaching.add(new Detectors.ValueCount(entry.getKey(), entry.getValue().longValue()));
		}
		final CodeMap.Coverage coverage = map.cover(reaching);
		if (!coverage.complete())
		{
			NormalisationSuggester.flag(pFlags, pEntry.mapping.mappingId).add(NoteTexts.Note.of(NoteTexts.N_CODE_GAP, Long.valueOf(coverage.unresolved), Long.valueOf(coverage.rows),
				Long.valueOf(coverage.unresolvedDistinct)), Math.min(coverage.percent(), NormalisationSuggester.FLAG_CONFIDENCE_CAP));
		}
		if (!map.conflicts().isEmpty())
		{
			NormalisationSuggester.flag(pFlags, pEntry.mapping.mappingId).add(NoteTexts.Note.of(NoteTexts.N_CODE_CONFLICT, Integer.valueOf(map.conflicts().size())),
				NormalisationSuggester.FLAG_CONFIDENCE_CAP);
		}
	}

	/** What the verification and the alignment found for one mapping. */
	private static final class Flag
	{
		final List<NoteTexts.Note> notes = new ArrayList<NoteTexts.Note>();
		int cap = 100;
		String fixedPipeline;

		void add(final NoteTexts.Note pNote, final int pCap)
		{
			this.notes.add(pNote);
			this.cap = Math.min(this.cap, pCap);
		}
	}

	private static Flag flag(final Map<String, Flag> pFlags, final String pMappingId)
	{
		Flag flag = pFlags.get(pMappingId);
		if (flag == null)
		{
			flag = new Flag();
			pFlags.put(pMappingId, flag);
		}
		return flag;
	}

	/** The distinct values of the column as the mapping's own cleaning steps leave them (the chain's real input), merged by value. */
	private static List<Detectors.ValueCount> cleanedInputs(final Entry pEntry, final CleaningSteps.Lookups pLookups)
	{
		final List<CleaningSteps.Step> steps = new ArrayList<CleaningSteps.Step>();
		for (final StepRow step : pEntry.steps)
		{
			steps.add(new CleaningSteps.Step(step.kind, step.argument));
		}
		final Map<String, Long> merged = new LinkedHashMap<String, Long>();
		for (final Detectors.ValueCount value : pEntry.data.values)
		{
			if (value.value == null || value.value.trim().isEmpty())
			{
				continue;
			}
			String cleaned = value.value;
			if (!steps.isEmpty())
			{
				final CleaningSteps.Result result = CleaningSteps.applyPipeline(steps, value.value, null, pLookups);
				if (!result.unresolved)
				{
					cleaned = result.value; // an unresolved lookup step leaves the value raw, like the Mapping matrix's sample
				}
			}
			if (cleaned == null || cleaned.trim().isEmpty())
			{
				continue; // nulled by EMPTY_TO_NULL: nothing reaches the chain
			}
			final Long seen = merged.get(cleaned);
			merged.put(cleaned, Long.valueOf((seen == null ? 0L : seen.longValue()) + value.count));
		}
		final List<Detectors.ValueCount> out = new ArrayList<Detectors.ValueCount>();
		for (final Map.Entry<String, Long> entry : merged.entrySet())
		{
			out.add(new Detectors.ValueCount(entry.getKey(), entry.getValue().longValue()));
		}
		return out;
	}

	private static void verify(final Adaptation pFactory, final TransformationContext pContext, final RunCache pCache, final Entry pEntry, final Map<String, Flag> pFlags,
		final Outcome pOutcome)
	{
		if (!pEntry.isProposed())
		{
			return; // a decided mapping is not touched
		}
		final String pipeline = NormalisationSuggester.effectivePipeline(pEntry.mappingRow, pEntry.mapping.dataType);
		if (pipeline == null)
		{
			pOutcome.notVerifiable++;
			return;
		}
		final String fieldName = pEntry.mapping.fieldName;
		final TransformVerifier.Evaluator evaluator = new TransformVerifier.Evaluator()
		{
			@Override
			public String apply(final String pPipeline, final String pValue) throws Exception
			{
				final ObjectNode document = NormalisationSuggester.MAPPER.createObjectNode();
				document.put(fieldName, pValue);
				pContext.setDocumentRoot(document);
				return Transformations.text(Transformations.apply(pPipeline, pValue, pContext));
			}
		};
		final TransformVerifier.Result result = TransformVerifier.verify(pEntry.mapping.dataType, pipeline, NormalisationSuggester.cleanedInputs(pEntry, pCache.lookups),
			pEntry.data.profile, evaluator, pEntry.data.profile.sampled, pEntry.data.profile.sampleRows);
		if (TransformVerifier.UNVERIFIABLE.equals(result.verdict))
		{
			pOutcome.notVerifiable++;
			return;
		}
		if (TransformVerifier.FIXED.equals(result.verdict))
		{
			final Flag flag = NormalisationSuggester.flag(pFlags, pEntry.mapping.mappingId);
			flag.fixedPipeline = result.replacementPipeline;
			flag.notes.add(NoteTexts.Note.of(NoteTexts.N_FIXED).addAll(result.note));
		}
		else if (result.needsAPerson())
		{
			NormalisationSuggester.flag(pFlags, pEntry.mapping.mappingId).add(result.note, Math.min(result.ratePercent, NormalisationSuggester.FLAG_CONFIDENCE_CAP));
		}
	}

	/** Writes the verdict on the mapping row: the replacement pipeline, the lowered confidence, the note - or restores what a clean re-verification no longer needs lowered. */
	private static void applyToMapping(final Session pSession, final Adaptation pFactory, final Entry pEntry, final Flag pFlag, final Outcome pOutcome)
	{
		if (!pEntry.isProposed())
		{
			return;
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pEntry.mapping.mappingId));
		if (row == null)
		{
			return;
		}
		final String rationale = row.getString(FactoryPaths.AttributeMapping.RATIONALE);
		final Object rawConfidence = row.get(FactoryPaths.AttributeMapping.CONFIDENCE);
		final Integer currentConfidence = rawConfidence instanceof Number ? Integer.valueOf(((Number) rawConfidence).intValue()) : null;
		final Integer was = CheckNote.originalConfidence(rationale);
		final String newRationale;
		final Integer newConfidence;
		final String newPipeline;
		boolean flagged = false;
		if (pFlag == null || pFlag.notes.isEmpty())
		{
			if (!CheckNote.has(rationale))
			{
				return; // nothing found, nothing written before: untouched
			}
			// verified clean now: the note goes, the confidence the check lowered comes back
			newRationale = CheckNote.base(rationale);
			newConfidence = was != null ? was : currentConfidence;
			newPipeline = null;
		}
		else
		{
			final Integer basis = was != null ? was : currentConfidence;
			final NoteTexts.Note note = new NoteTexts.Note();
			for (final NoteTexts.Note text : pFlag.notes)
			{
				note.addAll(text);
			}
			if (pFlag.cap < 100)
			{
				final int capped = Math.min(basis == null ? 100 : basis.intValue(), pFlag.cap);
				newConfidence = Integer.valueOf(capped);
				flagged = true;
				newRationale = CheckNote.with(rationale, note, basis, NormalisationSuggester.RATIONALE_MAX_LENGTH);
			}
			else
			{
				newConfidence = was != null ? was : currentConfidence; // a fix alone: the confidence is the proposer's
				newRationale = CheckNote.with(rationale, note, null, NormalisationSuggester.RATIONALE_MAX_LENGTH);
			}
			newPipeline = pFlag.fixedPipeline;
		}
		final boolean sameRationale = newRationale.equals(rationale == null ? "" : rationale);
		final boolean sameConfidence = newConfidence == null ? currentConfidence == null : newConfidence.equals(currentConfidence);
		if (sameRationale && sameConfidence && newPipeline == null)
		{
			if (flagged)
			{
				pOutcome.linksFlagged++;
			}
			return;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(newRationale, FactoryPaths.AttributeMapping.RATIONALE);
				if (newConfidence != null)
				{
					value.setValue(newConfidence, FactoryPaths.AttributeMapping.CONFIDENCE);
				}
				if (newPipeline != null)
				{
					value.setValue(newPipeline, FactoryPaths.AttributeMapping.PIPELINE);
				}
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			pOutcome.notes.add("could not write the value check of " + pEntry.mapping.entityName + "." + pEntry.mapping.attributeName + ": "
				+ result.getExceptionFullMessage(Locale.ENGLISH));
			return;
		}
		if (newPipeline != null)
		{
			pOutcome.pipelinesFixed++;
		}
		if (flagged)
		{
			pOutcome.linksFlagged++;
		}
	}
}
