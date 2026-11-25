package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.lineage.SourceColorPalette;
import com.on.ebx.factory.master.MameMetadataset;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.matching.DecidingNode;
import com.on.ebx.factory.matching.MatchResultsView;
import com.on.ebx.factory.matching.MatchStats;
import com.on.ebx.factory.matching.PairRows;
import com.on.ebx.factory.matching.SuspectGroups;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

import com.on.ebx.factory.lineage.RecordTitleRule;

/**
 * Builds {@code wizard.WizardPage}'s "Matching results" block ({@code matching.MatchResultsView.Input}) for
 * ONE master entity - the thin EBX-facing reader behind {@link MatchStats}' pure computations, in the same
 * "thin reader, never a second decision" style as {@code ebx.RecordLineageReader}/{@code
 * ebx.GoldenKeyRefresher}. Every write this screen offers (merge/reject a suspect) is NOT done here - it goes
 * through the ALREADY-EXISTING {@link RecordLineageReader#decideSuspect}, called directly by {@code
 * ebx.ui.ProjectWizardUserService} (task brief: never re-derive a write path this codebase already has).
 * <p>
 * <b>Metadataset naming/lookup: {@link MameMetadataset}</b>, the SAME derivation {@code
 * ebx.RecordLineageReader}/{@code ebx.GoldenKeyRefresher} already use - one metadataset per master TABLE,
 * {@code <masterDatasetName>_<tableLastStep>_MDS} in the master dataspace (CLAUDE.md sec 8, measured live
 * 2026-09-22). A missing metadataset (matching never activated, or a naming mismatch) degrades this whole
 * class to empty results, never an exception - same discipline as every other class touching it.
 * <p>
 * <b>Bounded end to end (lot volume-4, 2026-09-28) - no full {@code RecordMetadata} scan anywhere in this
 * class any more.</b> The class' own earlier assumption ("a project's own master table is expected to hold
 * hundreds of records, not millions") stopped being safe once this screen is rendered against a real, large
 * master table (task brief: a 5-million-row table would exhaust the heap materializing one {@code
 * MatchStats.MetaRow} per record, or block a Tomcat thread). Every KPI/band number below is now a bounded SQL
 * aggregate ({@link #queryStateSourceCounts}/{@link #queryMergedGroupsCount}/{@link #queryBandSegments}/
 * {@link #querySuspectedCountSum}/{@link #queryFalsePositiveCount} - a handful of rows each, never one per
 * master record) run on the master dataset directly ({@code Adaptation.createQuery}, the same Java query API
 * {@code ebx.CrossSystemMeasures} already established for this codebase), and only the first {@link
 * #MAX_PENDING_PAIRS_SHOWN} candidate goldens/{@link #MAX_GROUPS_SHOWN} candidate merged groups are even
 * fetched (one small, capped SQL round trip each, ordered deterministically) before the add-on's own
 * {@code MameGroups} read runs - so the heavier add-on call happens at most {@link #MAX_PENDING_PAIRS_SHOWN}/
 * {@link #MAX_GROUPS_SHOWN} times, never once per golden-with-suspects/merged row in the whole table. Only the
 * first {@link #MAX_DETAIL_PAIRS} rendered pairs get a real add-on {@code executeDecisionTree} call ({@link
 * com.on.ebx.factory.ebx.MameMatchingApi#evaluatePair}) - the rest show the plain (cheap, add-on-free) field
 * comparison table only. {@link MameGroups#CAP} (50) already bounds how many suspect/matched records a single
 * group scan can return. Per-record source attribution now reads the master row's OWN {@code sourceSystem}
 * field directly (always populated, {@code master.MasterModel}'s own design) instead of a whole-project {@code
 * Xref} scan kept in a Java map - the one documented behavior difference is an AUTO-CREATED golden with no
 * {@code Xref} row of its own, which used to show as {@code UNKNOWN} and now shows whatever source the add-on
 * itself stamped on it (see {@code docs/VOLUME-SCREENS.md}).
 */
public final class MatchingResultsBuilder
{
	public static final int MAX_PENDING_PAIRS_SHOWN = 60;
	public static final int MAX_DETAIL_PAIRS = 60;
	private static final int MAX_COMPARE_FIELDS = 8;
	/** Child rows read per record for the card's summary (the first addresses of a person). */
	private static final int MAX_CHILD_ROWS = 2;
	private static final int MAX_GROUPS_SHOWN = MatchResultsView.MERGED_SHOWN_MAX;
	private static final int MAX_DISTINCT_SHOWN = MatchResultsView.DISTINCT_SHOWN_MAX;

	private MatchingResultsBuilder()
	{
	}

	private static List<String> capFields(final List<String> pFieldNames)
	{
		final List<String> out = new ArrayList<String>();
		if (pFieldNames != null)
		{
			for (final String name : pFieldNames)
			{
				if (out.size() >= MatchingResultsBuilder.MAX_COMPARE_FIELDS)
				{
					break;
				}
				if (name != null && !name.trim().isEmpty())
				{
					out.add(name.trim());
				}
			}
		}
		return out;
	}

	/** Everything the caller ({@code ebx.ui.ProjectWizardUserService}) already knows and this class does not need to re-read. */
	public static final class Context
	{
		public final String projectCode;
		public final String entityName;
		/** {@code MatchPlan.matchingPolicy} of this entity's plan, or null - passed to {@code executeDecisionTree}/{@code simulateMatch}. */
		public final String matchingPolicyCode;
		/** Field names to compare (the entity's own non-technical attributes, master-field names) - capped to {@link #MAX_COMPARE_FIELDS} here, in the caller's own order. */
		public final List<String> compareFieldNames;
		public final int bulkThresholdPercent;
		public final String selectedTab;
		public final String selectedPairId;
		public final boolean canDecide;
		public final String lastRunText;
		public final String lastDecisionError;
		/** Step 6 pair card (2026-10-18): what the card compares (the plan's fields, derived keys, labels) and summarises (child entities). Null: the plain field comparison of before. */
		public final PairRows.Spec spec;

		public Context(final String pProjectCode, final String pEntityName, final String pMatchingPolicyCode, final List<String> pCompareFieldNames,
			final int pBulkThresholdPercent, final String pSelectedTab, final String pSelectedPairId, final boolean pCanDecide, final String pLastRunText,
			final String pLastDecisionError)
		{
			this(pProjectCode, pEntityName, pMatchingPolicyCode, pCompareFieldNames, pBulkThresholdPercent, pSelectedTab, pSelectedPairId, pCanDecide, pLastRunText,
				pLastDecisionError, null);
		}

		public Context(final String pProjectCode, final String pEntityName, final String pMatchingPolicyCode, final List<String> pCompareFieldNames,
			final int pBulkThresholdPercent, final String pSelectedTab, final String pSelectedPairId, final boolean pCanDecide, final String pLastRunText,
			final String pLastDecisionError, final PairRows.Spec pSpec)
		{
			this.spec = pSpec;
			this.projectCode = pProjectCode;
			this.entityName = pEntityName;
			this.matchingPolicyCode = pMatchingPolicyCode;
			this.compareFieldNames = MatchingResultsBuilder.capFields(pCompareFieldNames);
			this.bulkThresholdPercent = pBulkThresholdPercent;
			this.selectedTab = pSelectedTab == null || pSelectedTab.isEmpty() ? MatchStats.PENDING : pSelectedTab;
			this.selectedPairId = pSelectedPairId == null ? "" : pSelectedPairId;
			this.canDecide = pCanDecide;
			this.lastRunText = pLastRunText;
			this.lastDecisionError = pLastDecisionError;
		}
	}

	/** Null when the master table/metadataset is not reachable at all (matching never activated for this entity) - the caller then skips the whole block, same "no matching yet" convention as {@code lineage.RecordLineage.MergeInfo}. */
	public static MatchResultsView.Input build(final Session pSession, final Adaptation pFactory, final Context pContext)
	{
		final Adaptation project = MatchingResultsBuilder.findProject(pFactory, pContext.projectCode);
		if (project == null)
		{
			return null;
		}
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (MatchingResultsBuilder.blank(masterDataspaceKey) || MatchingResultsBuilder.blank(masterDatasetName))
		{
			return null;
		}
		final String masterTablePath = MasterModel.tablePath(pContext.entityName);
		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		final Adaptation masterDataset = masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(masterDatasetName));
		final AdaptationTable masterTable;
		try
		{
			masterTable = masterDataset == null ? null : masterDataset.getTable(Path.parse(masterTablePath));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
		if (masterTable == null || masterTable.getTableOccurrenceRootNode() == null)
		{
			return null;
		}
		final Adaptation metadataset = MatchingResultsBuilder.metadataset(repository, masterDataspaceKey, masterDatasetName, masterTablePath);
		final AdaptationTable recordMetadataTable = metadataset == null ? null : MatchingResultsBuilder.safeGetTable(metadataset, "RecordMetadata");
		if (recordMetadataTable == null)
		{
			return null;
		}

		// ------------------------------------------------------------------------------------------------------ KPIs
		// Wave 2 (2026-09-30): this whole method now runs ONLY from a snapshot computation (a matching run, a refresh
		// job) - never while a page is written (MatchingResultsStore/MatchResultsSnapshot). Still worth trimming: the
		// (state, source) aggregate is the one whole-table statement always needed; when it shows no MERGED row the
		// distinct-golden count, the band (two-level GROUP BY) and the merged-tab reads are answered from it without
		// touching the master table again.
		final List<MatchStats.StateSourceCount> stateSourceCounts = MatchingResultsBuilder.queryStateSourceCounts(pSession, masterDataset, pContext.entityName);
		final boolean anyMerged = MatchStats.hasMerged(stateSourceCounts);
		final int mergedGroupsCount = anyMerged ? MatchingResultsBuilder.queryMergedGroupsCount(pSession, masterDataset, pContext.entityName) : 0;
		// the pending figure is the number of DISTINCT PENDING PAIRS - the rows of the metadataset's Suspected table - never
		// SUM(RecordMetadata.suspectedCount) (each pair is counted from both of its records: 66 748 for 33 374 pairs on
		// Vol100k) nor the number of goldens carrying a flag (46 663 there, what a run note used to call "pairs")
		final int pendingPairTotal = MatchingResultsBuilder.pendingPairTotal(pSession, metadataset);
		final int distinctPairsCount = MatchingResultsBuilder.queryFalsePositiveCount(pSession, metadataset);
		final long goldensWithSuspects = MatchingResultsBuilder.queryGoldensWithSuspects(pSession, metadataset);
		final MatchStats.Kpis kpis = MatchStats.fromStateSourceCounts(stateSourceCounts, mergedGroupsCount, pendingPairTotal, distinctPairsCount);

		// -------------------------------------------------------------------------------------------- band ("where golden records come from")
		final List<MatchStats.BandSegment> band = anyMerged ? MatchingResultsBuilder.queryBandSegments(pSession, masterDataset, pContext.entityName)
			: MatchStats.bandFromGoldenCounts(stateSourceCounts);

		// ----------------------------------------------------------------------------------------- pending pairs
		final Set<String> sourcesSeen = new LinkedHashSet<String>();
		final Map<String, MatchResultsView.TreeDetail> treeDetail = new LinkedHashMap<String, MatchResultsView.TreeDetail>();
		// step 6 queue per group (2026-10-18): the suspect pairs are read whole and grouped (connected components); only when they cannot be read does the older pair list stand in
		final GroupsRead groupsRead = MatchingResultsBuilder.readGroups(pSession, masterDataset, metadataset, masterTable, pContext, sourcesSeen);
		final List<MatchStats.SuspectPair> sortedPending = new ArrayList<MatchStats.SuspectPair>();
		if (groupsRead == null)
		{
			final List<MatchStats.SuspectPair> pendingPairs = MatchingResultsBuilder.readPendingPairs(pSession, masterDataset, metadataset, masterTable, pContext,
				MatchingResultsBuilder.MAX_PENDING_PAIRS_SHOWN, true, treeDetail, sourcesSeen);
			sortedPending.addAll(MatchStats.sortByStrengthDesc(pendingPairs));
		}

		// -------------------------------------------------------------------------------------------- merged tab
		// Only the first MAX_GROUPS_SHOWN merged-golden ids are fetched, then only THEIR own members - never a
		// scan of every MERGED row of the whole table (task brief item 1). Nothing to read when no row is MERGED.
		final List<String> mergedGoldenIds = anyMerged
			? MatchingResultsBuilder.queryMergedGoldenIds(pSession, masterDataset, pContext.entityName, MatchingResultsBuilder.MAX_GROUPS_SHOWN)
			: new ArrayList<String>();
		final Map<String, List<String[]>> membersByGoldenKey = MatchingResultsBuilder.queryMergedMembers(pSession, masterDataset, pContext.entityName, mergedGoldenIds);
		final List<MatchResultsView.GroupSummary> groups = new ArrayList<MatchResultsView.GroupSummary>();
		for (final String goldenKey : mergedGoldenIds)
		{
			final List<String[]> members = membersByGoldenKey.get(goldenKey);
			if (members == null || members.isEmpty())
			{
				continue;
			}
			final Adaptation goldenRecord = masterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(goldenKey));
			final String goldenSource = goldenRecord == null ? null : MatchingResultsBuilder.safeString(goldenRecord, "sourceSystem");
			sourcesSeen.add(goldenSource);
			final String goldenLabel = goldenRecord == null ? goldenKey : MatchingResultsBuilder.titleOf(pSession, goldenRecord, pContext.compareFieldNames, goldenKey);
			final List<String> memberSources = new ArrayList<String>();
			final List<String> memberKeys = new ArrayList<String>();
			for (final String[] member : members)
			{
				memberKeys.add(member[0]);
				memberSources.add(MatchingResultsBuilder.orUnknown(member[1]));
				sourcesSeen.add(member[1]);
			}
			groups.add(new MatchResultsView.GroupSummary(goldenKey, goldenLabel, MatchingResultsBuilder.orUnknown(goldenSource), memberSources, memberKeys));
		}

		// ---------------------------------------------------------------------------------------- distinct tab
		final List<MatchResultsView.DistinctPair> distinctPairs = MatchingResultsBuilder.readFalsePositives(repository, masterDataspaceKey, masterDatasetName, masterTablePath);

		final Map<String, String> colorBySource = MatchingResultsBuilder.colorMap(band, sourcesSeen);

		final MatchResultsView.Input in = new MatchResultsView.Input();
		in.entityId = pContext.entityName;
		in.entityName = pContext.entityName;
		in.kpis = kpis;
		in.band = band;
		in.colorBySource = colorBySource;
		in.lastRunText = pContext.lastRunText;
		in.pendingPairs = sortedPending;
		if (groupsRead != null)
		{
			in.pendingGroups = groupsRead.groups;
			in.pendingGroupsTotal = groupsRead.total;
		}
		in.pendingPairsTotal = pendingPairTotal;
		in.goldensWithSuspects = goldensWithSuspects;
		in.maxDetailPairs = MatchingResultsBuilder.MAX_DETAIL_PAIRS;
		in.treeDetail = treeDetail;
		in.fieldLabels = pContext.spec == null || pContext.spec.labels.isEmpty() ? null : pContext.spec.labels;
		in.mergedGroups = groups;
		in.distinctPairs = distinctPairs;
		in.selectedTab = pContext.selectedTab;
		in.selectedPairId = pContext.selectedPairId;
		in.bulkThresholdPercent = pContext.bulkThresholdPercent;
		in.canDecide = pContext.canDecide;
		in.lastDecisionError = pContext.lastDecisionError;
		return in;
	}

	// ----------------------------------------------------------------------------------------------------- reads

	/**
	 * {@code RecordMetadata.state} is an {@code xs:int} with a STATIC enumeration, not a string
	 * ({@code data-access describe_table}, verified live 2026-09-24 against {@code Companies_Master}/
	 * {@code Master_Company_MDS} - {@code preview_records} shows {@code "state":{"key":1}}, an ordinal, not
	 * {@code "GOLDEN"}). {@code Adaptation.get(path)} on this field answers the raw {@code Integer} ordinal,
	 * never the add-on's own {@code State} enum name - only {@code MetadataHelper.getState(Adaptation)} (a
	 * BUSINESS-record read, not a metadataset-table read - see {@code ebx.MameRecordState}) resolves that.
	 * Mapped by ordinal per CLAUDE.md sec 8 ("State keys seen so far") and confirmed the same day by reading
	 * {@code AbstractBaseModel}'s own {@code State} enum declaration order (UNSET, GOLDEN, MERGED,
	 * PENDING_APPROVAL, DELETED) - {@code javap} on {@code com.orchestranetworks.addon.mame.model.State}.
	 */
	private static final String[] STATE_NAMES = { "UNSET", "GOLDEN", "MERGED", "PENDING_APPROVAL", "DELETED" };

	private static String stateName(final Object pRawOrdinal)
	{
		if (!(pRawOrdinal instanceof Number))
		{
			return null;
		}
		final int ordinal = ((Number) pRawOrdinal).intValue();
		return ordinal >= 0 && ordinal < MatchingResultsBuilder.STATE_NAMES.length ? MatchingResultsBuilder.STATE_NAMES[ordinal] : null;
	}

	/**
	 * The add-on's metadataset itself (the dataset, not one of its tables) - lot volume-4 (2026-09-28), factored
	 * out of {@link #metadatasetTable} so a caller that needs to run a SQL aggregate against it ({@link
	 * #querySuspectedCountSum}/{@link #queryFalsePositiveCount}, {@code Adaptation.createQuery} runs against a
	 * DATASET, never a table) is not forced to also name a specific table it does not need. Null (never an
	 * exception) whenever the dataspace, the metadataset, or the naming inputs are unavailable - same discipline
	 * as every other reader of it ({@code ebx.MameMetadatasetReads}).
	 */
	private static Adaptation metadataset(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final String pMasterTablePath)
	{
		try
		{
			final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pMasterDataspaceKey));
			final String metadatasetName = MameMetadataset.name(pMasterDatasetName, pMasterTablePath);
			return home == null || metadatasetName == null ? null : home.findAdaptationOrNull(AdaptationName.forName(metadatasetName));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** A named table of an already-resolved metadataset {@link Adaptation} - never throws. */
	private static AdaptationTable safeGetTable(final Adaptation pDataset, final String pTableName)
	{
		try
		{
			// audit F-27: the one door to the add-on's metadataset tables (MameMetadataGuard) - a changed shape is reported, not read as "empty"
			return MameMetadataGuard.table(pDataset, pTableName);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Package-visible (lot R6, 2026-09-27; still used by {@link #readFalsePositives} and by {@code ebx.MatchingConfigurator}'s own bounded metadataset lookups) - resolves the metadataset and one of its tables in one call. */
	static AdaptationTable metadatasetTable(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pMasterTablePath, final String pTableName)
	{
		try
		{
			final Adaptation metadataset = MatchingResultsBuilder.metadataset(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath);
			return MatchingResultsBuilder.safeGetTable(metadataset, pTableName);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** {@code FalsePositiveDecision} rows of this table's own metadataset (CLAUDE.md sec 8 table list) - {@code id}/{@code sourceId}/{@code targetId}/{@code user}/{@code decisionDate}, see {@code javap} on {@code FalsePositiveDecision.class} referenced from {@code docs/WIZARD-MATCHING-RESULTS.md}. Never throws - a missing table (an older add-on build, or matching never run) simply answers an empty list. */
	private static List<MatchResultsView.DistinctPair> readFalsePositives(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pMasterTablePath)
	{
		final List<MatchResultsView.DistinctPair> out = new ArrayList<MatchResultsView.DistinctPair>();
		final AdaptationTable table = MatchingResultsBuilder.metadatasetTable(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath, "FalsePositiveDecision");
		if (table == null)
		{
			return out;
		}
		final RequestResult rows = table.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null && out.size() < MatchingResultsBuilder.MAX_DISTINCT_SHOWN; row = rows.nextAdaptation())
			{
				final String sourceId = MatchingResultsBuilder.safeString(row, "sourceId");
				final String targetId = MatchingResultsBuilder.safeString(row, "targetId");
				final String user = MatchingResultsBuilder.safeString(row, "user");
				// decisionDate is xs:dateTime - safeString formats a java.util.Date via toInstant(), never a raw Date.toString()
				final String decisionDate = MatchingResultsBuilder.safeString(row, "decisionDate");
				out.add(new MatchResultsView.DistinctPair(sourceId == null ? "" : sourceId, targetId == null ? "" : targetId, user, decisionDate));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	// --------------------------------------------------------------------------------------------------- fields

	private static List<MatchStats.FieldComparison> compareRecords(final Adaptation pLeft, final Adaptation pRight, final List<String> pFieldNames)
	{
		final List<String> labels = new ArrayList<String>();
		final List<String> leftValues = new ArrayList<String>();
		final List<String> rightValues = new ArrayList<String>();
		for (final String fieldName : pFieldNames)
		{
			labels.add(fieldName);
			leftValues.add(MatchingResultsBuilder.safeString(pLeft, fieldName));
			rightValues.add(MatchingResultsBuilder.safeString(pRight, fieldName));
		}
		return MatchStats.compareFields(labels, leftValues, rightValues);
	}

	private static String nullToEmpty(final String pValue)
	{
		return pValue == null ? "" : pValue;
	}

	/** The rows of the plan's comparison ({@link PairRows}): the keys the plan derived stand for their raw fields, labelled with the attribute's label. */
	private static List<MatchStats.FieldComparison> comparePlanned(final Adaptation pLeft, final Adaptation pRight, final PairRows.Spec pSpec)
	{
		return PairRows.build(pSpec.attrs, new PairRows.Values()
		{
			@Override
			public String left(final String pField)
			{
				return MatchingResultsBuilder.nullToEmpty(MatchingResultsBuilder.safeString(pLeft, pField));
			}

			@Override
			public String right(final String pField)
			{
				return MatchingResultsBuilder.nullToEmpty(MatchingResultsBuilder.safeString(pRight, pField));
			}
		});
	}

	/** A record named in full: the values of the plan's name fields ("Julie Lefevre"); the record's own title when the plan names none or they are empty. */
	private static String nameOf(final Session pSession, final Adaptation pRecord, final Context pContext, final String pKey)
	{
		if (pContext.spec != null)
		{
			final StringBuilder name = new StringBuilder();
			for (final String field : pContext.spec.nameFields)
			{
				final String value = MatchingResultsBuilder.safeString(pRecord, field);
				if (value != null && !value.trim().isEmpty())
				{
					name.append(name.length() > 0 ? " " : "").append(value.trim());
				}
			}
			if (name.length() > 0)
			{
				return name.toString();
			}
		}
		return MatchingResultsBuilder.titleOf(pSession, pRecord, pContext.compareFieldNames, pKey);
	}

	/** Parent key to "street, postal code, city" (the first {@link #MAX_CHILD_ROWS} child rows joined by " | "), for the parents that have child rows. Empty when the child table cannot be read. */
	private static Map<String, String> childSummaries(final Adaptation pMasterDataset, final PairRows.Child pChild, final Set<String> pParentKeys)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		final AdaptationTable table;
		try
		{
			table = pMasterDataset.getTable(Path.parse(MasterModel.tablePath(pChild.entity)));
		}
		catch (final RuntimeException ex)
		{
			return out;
		}
		if (table == null || pChild.fkField == null || pChild.fkField.isEmpty() || pChild.fields.isEmpty())
		{
			return out;
		}
		// the staging attribute "client" is the source's key (a plain string, null in the master); the foreign key to the parent is the master field "clientRef" (MasterModel)
		final String fkField = MatchingResultsBuilder.masterForeignKeyField(table, pChild.fkField);
		if (fkField == null)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] the master table of " + pChild.entity + " has no foreign key field for '" + pChild.fkField + "': no child summary on the pair cards");
			return out;
		}
		final StringBuilder predicate = new StringBuilder();
		for (final String key : pParentKeys)
		{
			predicate.append(predicate.length() > 0 ? " or " : "").append("./").append(fkField).append('=').append(CatalogReader.quote(key));
		}
		RequestResult rows = null;
		try
		{
			rows = table.createRequestResult(predicate.toString());
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String parent = MatchingResultsBuilder.safeString(row, fkField);
				if (parent == null)
				{
					continue;
				}
				final int seen = counts.containsKey(parent) ? counts.get(parent).intValue() : 0;
				if (seen >= MatchingResultsBuilder.MAX_CHILD_ROWS)
				{
					continue;
				}
				final StringBuilder line = new StringBuilder();
				for (final String field : pChild.fields)
				{
					final String value = MatchingResultsBuilder.safeString(row, field);
					if (value != null && !value.trim().isEmpty())
					{
						line.append(line.length() > 0 ? ", " : "").append(value.trim());
					}
				}
				if (line.length() == 0)
				{
					continue;
				}
				counts.put(parent, Integer.valueOf(seen + 1));
				out.put(parent, out.containsKey(parent) ? out.get(parent) + " | " + line : line.toString());
			}
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] the child rows of " + pChild.entity + " could not be read for the pair cards: " + ex);
		}
		finally
		{
			if (rows != null)
			{
				rows.close();
			}
		}
		return out;
	}

	/**
	 * The field of a master child table that holds the foreign key built from the staging attribute {@code pAttribute}: {@code <attribute>Ref} (or the {@code _2}, {@code _3}
	 * that {@code MasterModel} gives a name already taken), the first that exists and is a foreign key. Null when none is (the attribute stayed plain data).
	 */
	static String masterForeignKeyField(final AdaptationTable pTable, final String pAttribute)
	{
		for (final String name : PairRows.foreignKeyFieldCandidates(pAttribute))
		{
			try
			{
				final com.orchestranetworks.schema.SchemaNode node = pTable.getTableOccurrenceRootNode().getNode(Path.parse("./" + name));
				if (node != null && node.getFacetOnTableReference() != null)
				{
					return name;
				}
			}
			catch (final RuntimeException ex)
			{
				// no such field: try the next name
			}
		}
		return null;
	}

	private static String titleOf(final Session pSession, final Adaptation pRecord, final List<String> pFieldNames, final String pKey)
	{
		final List<RecordTitleRule.Field> fields = new ArrayList<RecordTitleRule.Field>();
		for (final String fieldName : pFieldNames)
		{
			fields.add(new RecordTitleRule.Field(fieldName, MatchingResultsBuilder.safeString(pRecord, fieldName)));
		}
		String label = null;
		try
		{
			label = pRecord.getLabel(pSession);
		}
		catch (final RuntimeException ex)
		{
			label = null;
		}
		return RecordTitleRule.pickTitle(label, pKey, fields);
	}

	/** {@code MameMatchingApi.evaluatePair} references add-on types in its own signature - a server without {@code ebx-addons.jar} throws {@link LinkageError} resolving the call itself, caught HERE (see that class' own Javadoc, "the LinkageError guard lives in the CALLER"). */
	private static MatchResultsView.TreeDetail evaluate(final Session pSession, final Adaptation pGolden, final Adaptation pSuspect, final String pPolicyCodeOrNull)
	{
		try
		{
			final MameMatchingApi.TreeEvaluation evaluation = MameMatchingApi.evaluatePair(pSession, pGolden, pSuspect, pPolicyCodeOrNull);
			if (evaluation.failed || evaluation == MameMatchingApi.TreeEvaluation.UNAVAILABLE)
			{
				return new MatchResultsView.TreeDetail(false, evaluation.diagnostic, null, null, null);
			}
			final List<MatchResultsView.EngineFieldScore> scores = new ArrayList<MatchResultsView.EngineFieldScore>();
			for (final MameMatchingApi.FieldScore score : evaluation.fieldScores)
			{
				scores.add(new MatchResultsView.EngineFieldScore(score.field, score.score));
			}
			return new MatchResultsView.TreeDetail(true, null, evaluation.nodeNames, evaluation.lastNodeName, scores);
		}
		catch (final LinkageError ex)
		{
			return new MatchResultsView.TreeDetail(false, "ebx-addons.jar not on classpath", null, null, null);
		}
		catch (final RuntimeException ex)
		{
			return new MatchResultsView.TreeDetail(false, ex.getClass().getName() + ": " + ex.getMessage(), null, null, null);
		}
	}

	// ---------------------------------------------------------------------------------------------------- colors

	/**
	 * One colour per source label in {@code pBand}'s own order (the project's own declared trust order is not
	 * read here - band order, by count, is close enough for a KPI band and avoids a second EBX read) -
	 * {@link MatchStats#MULTIPLE} always gets the reserved golden/accent colour, never a palette slot. {@code
	 * pExtraSourcesInOrder} (lot volume-4, 2026-09-28) is every source label actually seen while building the
	 * RENDERED pending pairs/merged groups (bounded, at most a couple hundred distinct values even when the
	 * table itself holds millions of rows) - the replacement for the old "every source known anywhere in the
	 * whole project's Xref" fallback, which this class no longer reads at all.
	 */
	private static Map<String, String> colorMap(final List<MatchStats.BandSegment> pBand, final Set<String> pExtraSourcesInOrder)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		int ordinal = 0;
		for (final MatchStats.BandSegment segment : pBand)
		{
			if (MatchStats.MULTIPLE.equals(segment.label))
			{
				out.put(segment.label, SourceColorPalette.GOLDEN);
			}
			else
			{
				out.put(segment.label, SourceColorPalette.colorForOrdinal(ordinal++));
			}
		}
		if (pExtraSourcesInOrder != null)
		{
			for (final String source : pExtraSourcesInOrder)
			{
				if (source != null && !source.trim().isEmpty() && !out.containsKey(source))
				{
					out.put(source, SourceColorPalette.colorForOrdinal(ordinal++));
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------- misc

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + MatchingResultsBuilder.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	// ---------------------------------------------------------------------------------- pending pairs (wave 2)

	/**
	 * The pending pairs of the queue, at most {@code pLimit}, for the wizard's "To decide" tab and its bulk merge - the
	 * ONLY place a pair is built (wave 2, 2026-09-30). The pairs are read straight off the metadataset's {@code Suspected}
	 * table (one row per pending pair - CLAUDE.md sec 8, measured 33 374 rows = 33 374 distinct pairs on Vol100k) and the
	 * two master records of each row are read by primary key: no add-on group read at all ({@code MameGroups} ->
	 * {@code findRecordsInGroup} was in the thread dumps of the 32-46 s renders), and a pair is no longer listed twice when
	 * both of its records are among the candidates. The golden/suspect orientation is the row's own ({@code sourceId} is the
	 * anchor {@code RecordLineageReader.decideSuspect} takes - both sides carry the pair in their group, {@code
	 * SUM(suspectedCount)} = 2 x the row count). When the table cannot be read (an older add-on build), the former path -
	 * the first {@code pLimit} goldens flagged {@code hasSuspects}, then their group's suspects through the add-on - is used.
	 */
	private static List<MatchStats.SuspectPair> readPendingPairs(final Session pSession, final Adaptation pMasterDataset, final Adaptation pMetadataset,
		final AdaptationTable pMasterTable, final Context pContext, final int pLimit, final boolean pWithTreeDetail,
		final Map<String, MatchResultsView.TreeDetail> pTreeDetailOut, final Set<String> pSourcesSeen)
	{
		final List<MatchStats.SuspectPair> pairs = new ArrayList<MatchStats.SuspectPair>();
		final List<String[]> keys = MatchingResultsBuilder.queryPendingPairKeys(pSession, pMetadataset, pLimit);
		if (keys != null)
		{
			final Set<String> seen = new LinkedHashSet<String>();
			for (final String[] key : keys)
			{
				if (pairs.size() >= pLimit)
				{
					break;
				}
				if (!seen.add(MatchingResultsBuilder.unorderedKey(key[0], key[1])))
				{
					continue;
				}
				final Adaptation golden = MatchingResultsBuilder.lookup(pMasterTable, key[0]);
				final Adaptation suspect = MatchingResultsBuilder.lookup(pMasterTable, key[1]);
				if (golden == null || suspect == null)
				{
					continue;
				}
				pairs.add(MatchingResultsBuilder.makePair(pSession, golden, suspect, key[0], key[1], pContext, pWithTreeDetail, pTreeDetailOut, pSourcesSeen));
			}
			return pairs;
		}
		// fallback: goldens flagged hasSuspects -> the add-on's group read
		final List<GoldenCandidate> candidates = MatchingResultsBuilder.queryPendingGoldenCandidates(pSession, pMasterDataset, pContext.entityName, pLimit);
		outer: for (final GoldenCandidate candidate : candidates)
		{
			if (candidate.groupId == null)
			{
				continue;
			}
			final MameGroups.Result group = MameGroups.get().read(pSession, pMasterTable, candidate.groupId);
			if (!group.addonPresent || !group.found)
			{
				continue;
			}
			final Adaptation goldenRecord = MatchingResultsBuilder.lookup(pMasterTable, candidate.functionalId);
			if (goldenRecord == null)
			{
				continue;
			}
			for (final Adaptation suspect : group.suspectRecords)
			{
				if (pairs.size() >= pLimit)
				{
					break outer;
				}
				pairs.add(MatchingResultsBuilder.makePair(pSession, goldenRecord, suspect, candidate.functionalId, suspect.getOccurrencePrimaryKey().format(), pContext,
					pWithTreeDetail, pTreeDetailOut, pSourcesSeen));
			}
		}
		return pairs;
	}

	/**
	 * The pending pairs alone (no KPI, no band, no merged groups, no decision-tree evaluation) - what a bulk merge needs to
	 * decide which pairs to send, read fresh at the click. Empty when the master table or its metadataset is unreachable.
	 * Not for a page render: it reads up to {@code pLimit} x 2 master records.
	 */
	public static List<MatchStats.SuspectPair> buildPendingPairs(final Session pSession, final Adaptation pFactory, final Context pContext, final int pLimit)
	{
		final Adaptation project = MatchingResultsBuilder.findProject(pFactory, pContext.projectCode);
		if (project == null)
		{
			return new ArrayList<MatchStats.SuspectPair>();
		}
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (MatchingResultsBuilder.blank(masterDataspaceKey) || MatchingResultsBuilder.blank(masterDatasetName))
		{
			return new ArrayList<MatchStats.SuspectPair>();
		}
		final String masterTablePath = MasterModel.tablePath(pContext.entityName);
		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		final Adaptation masterDataset = masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(masterDatasetName));
		final AdaptationTable masterTable;
		try
		{
			masterTable = masterDataset == null ? null : masterDataset.getTable(Path.parse(masterTablePath));
		}
		catch (final RuntimeException ex)
		{
			return new ArrayList<MatchStats.SuspectPair>();
		}
		final Adaptation metadataset = MatchingResultsBuilder.metadataset(repository, masterDataspaceKey, masterDatasetName, masterTablePath);
		if (masterTable == null || masterTable.getTableOccurrenceRootNode() == null || metadataset == null)
		{
			return new ArrayList<MatchStats.SuspectPair>();
		}
		return MatchingResultsBuilder.readPendingPairs(pSession, masterDataset, metadataset, masterTable, pContext, pLimit, false,
			new LinkedHashMap<String, MatchResultsView.TreeDetail>(), new LinkedHashSet<String>());
	}

	/** The groups of the queue: the shown ones and the real number of groups. */
	static final class GroupsRead
	{
		final List<MatchStats.SuspectGroup> groups;
		final int total;

		GroupsRead(final List<MatchStats.SuspectGroup> pGroups, final int pTotal)
		{
			this.groups = pGroups;
			this.total = pTotal;
		}
	}

	/** Groups shown (and stored) at most; the rest is only counted. */
	static final int MAX_SUSPECT_GROUPS_SHOWN = 50;
	/** Suspect pairs read to build the groups: past it the groups are those of the first pairs. */
	static final int MAX_PAIRS_GROUPED = 200000;
	/** Decision-tree evaluations (one per pair of a shown group) per computation. */
	static final int MAX_GROUP_EVALUATIONS = 400;

	/**
	 * The queue as groups: every suspect pair is read ({@code Suspected}, two keys a row), the connected components are the groups, the biggest first (then by name), and for
	 * the first {@link #MAX_SUSPECT_GROUPS_SHOWN} the records are read once: one column each (source system, source id, name, golden flag; a golden record some of them are merged into
	 * joins as a column), the rows the plan compares, the child rows (an address) and the node of the plan that produced each pair. Null when the pairs cannot be read (the caller
	 * then lists pairs).
	 */
	private static GroupsRead readGroups(final Session pSession, final Adaptation pMasterDataset, final Adaptation pMetadataset, final AdaptationTable pMasterTable,
		final Context pContext, final Set<String> pSourcesSeen)
	{
		final List<String[]> pairs = MatchingResultsBuilder.queryPendingPairKeys(pSession, pMetadataset, MatchingResultsBuilder.MAX_PAIRS_GROUPED);
		if (pairs == null)
		{
			return null;
		}
		final List<SuspectGroups.Group> all = SuspectGroups.components(pairs, null);
		// the biggest first, then by id; the names of the candidates are read below and order the groups of the same size
		final List<SuspectGroups.Group> candidates = SuspectGroups.ordered(all, null);
		final List<SuspectGroups.Group> shown = new ArrayList<SuspectGroups.Group>(
			candidates.subList(0, Math.min(MatchingResultsBuilder.MAX_SUSPECT_GROUPS_SHOWN, candidates.size())));
		final PairRows.Spec spec = pContext.spec;
		final List<MatchStats.SuspectGroup> out = new ArrayList<MatchStats.SuspectGroup>();
		final Map<String, String> names = new LinkedHashMap<String, String>();
		final Map<String, Adaptation> records = new LinkedHashMap<String, Adaptation>();
		final List<SuspectGroups.Group> withGoldens = new ArrayList<SuspectGroups.Group>();
		// golden records some members are merged into join their group as a column
		for (final SuspectGroups.Group group : shown)
		{
			final Map<String, String> goldenOf = new LinkedHashMap<String, String>();
			for (final String key : group.members)
			{
				final Adaptation record = MatchingResultsBuilder.lookup(pMasterTable, key);
				if (record == null)
				{
					continue;
				}
				records.put(key, record);
				final MameRecordState.Result state = MameRecordState.get().read(record);
				if (state.addonPresent && state.goldenKey != null && !state.goldenKey.isEmpty() && !state.goldenKey.equals(key))
				{
					goldenOf.put(key, state.goldenKey);
				}
			}
			final List<SuspectGroups.Group> one = SuspectGroups.components(group.pairs, goldenOf);
			withGoldens.add(one.isEmpty() ? group : one.get(0));
		}
		for (final SuspectGroups.Group group : withGoldens)
		{
			for (final String key : group.members)
			{
				if (!records.containsKey(key))
				{
					final Adaptation record = MatchingResultsBuilder.lookup(pMasterTable, key);
					if (record != null)
					{
						records.put(key, record);
					}
				}
				if (records.get(key) != null)
				{
					names.put(key, MatchingResultsBuilder.nameOf(pSession, records.get(key), pContext, key));
				}
			}
		}
		final List<SuspectGroups.Group> ordered = SuspectGroups.ordered(withGoldens, names);
		// the child rows of every shown record, one request per child table
		final Set<String> memberKeys = new LinkedHashSet<String>();
		for (final SuspectGroups.Group group : ordered)
		{
			memberKeys.addAll(group.members);
		}
		final List<Map<String, String>> childSummaries = new ArrayList<Map<String, String>>();
		if (spec != null)
		{
			for (final PairRows.Child child : spec.children)
			{
				childSummaries.add(MatchingResultsBuilder.childSummaries(pMasterDataset, child, memberKeys));
			}
		}
		int evaluations = 0;
		for (final SuspectGroups.Group group : ordered)
		{
			final List<MatchStats.GroupMember> members = new ArrayList<MatchStats.GroupMember>();
			final List<Adaptation> memberRecords = new ArrayList<Adaptation>();
			final List<String> keys = new ArrayList<String>();
			for (final String key : group.members)
			{
				final Adaptation record = records.get(key);
				if (record == null)
				{
					continue; // a record the engine cannot read any more (deleted since): no column
				}
				final String source = MatchingResultsBuilder.orUnknown(MatchingResultsBuilder.safeString(record, "sourceSystem"));
				pSourcesSeen.add(source);
				final MameRecordState.Result state = MameRecordState.get().read(record);
				members.add(new MatchStats.GroupMember(key, source, MatchingResultsBuilder.nullToEmpty(MatchingResultsBuilder.safeString(record, "sourceId")),
					names.get(key) == null ? key : names.get(key), group.goldens.contains(key) || state.addonPresent && state.autoCreated));
				memberRecords.add(record);
				keys.add(key);
			}
			if (members.size() < 2)
			{
				continue;
			}
			final List<MatchStats.GroupRow> rows = new ArrayList<MatchStats.GroupRow>();
			if (spec != null && !spec.attrs.isEmpty())
			{
				rows.addAll(PairRows.buildGroup(spec.attrs, members.size(), new PairRows.MemberValues()
				{
					@Override
					public String value(final int pMember, final String pField)
					{
						return MatchingResultsBuilder.nullToEmpty(MatchingResultsBuilder.safeString(memberRecords.get(pMember), pField));
					}
				}));
			}
			else
			{
				for (final String field : pContext.compareFieldNames)
				{
					final List<String> values = new ArrayList<String>();
					for (final Adaptation record : memberRecords)
					{
						values.add(MatchingResultsBuilder.nullToEmpty(MatchingResultsBuilder.safeString(record, field)));
					}
					rows.add(new MatchStats.GroupRow(field, field, "", true, values, new ArrayList<String>()));
				}
			}
			if (spec != null)
			{
				for (int i = 0; i < spec.children.size(); i++)
				{
					final PairRows.Child child = spec.children.get(i);
					boolean any = false;
					final List<String> values = new ArrayList<String>();
					for (final String key : keys)
					{
						final String value = childSummaries.get(i).get(key);
						any |= value != null;
						values.add(value == null ? "" : value);
					}
					if (any)
					{
						rows.add(new MatchStats.GroupRow(child.label, MatchStats.CHILD_PREFIX + child.entity, "", true, values, new ArrayList<String>()));
					}
				}
			}
			// the rule that linked the records: the deciding node of each pair, in the order met, each once
			final Set<String> nodes = new LinkedHashSet<String>();
			for (final String[] pair : group.pairs)
			{
				if (evaluations >= MatchingResultsBuilder.MAX_GROUP_EVALUATIONS)
				{
					break;
				}
				final Adaptation a = records.get(pair[0]);
				final Adaptation b = records.get(pair[1]);
				if (a == null || b == null)
				{
					continue;
				}
				evaluations++;
				final MatchResultsView.TreeDetail detail = MatchingResultsBuilder.evaluate(pSession, a, b, pContext.matchingPolicyCode);
				if (detail.available)
				{
					// the rule is the last node walked before the verdict's leaf, never the leaf itself ("Suspect")
					final String deciding = DecidingNode.pick(detail.nodeNames, detail.lastNodeName, spec == null ? null : spec.planNodeNames);
					if (deciding != null)
					{
						nodes.add(deciding);
					}
				}
			}
			out.add(new MatchStats.SuspectGroup(group.id(), members, rows, group.pairs, new ArrayList<String>(nodes)));
		}
		return new GroupsRead(out, all.size());
	}

	/**
	 * The suspect pairs the add-on holds among {@code pKeys} ({@code [sourceId, targetId]} as stored, each once), read at the moment of a decision - never what a card showed. Empty
	 * when the master table or its metadataset is unreachable.
	 */
	public static List<String[]> suspectPairsAmong(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName,
		final java.util.Collection<String> pKeys)
	{
		final List<String[]> out = new ArrayList<String[]>();
		final Adaptation project = MatchingResultsBuilder.findProject(pFactory, pProjectCode);
		if (project == null || pKeys == null || pKeys.isEmpty())
		{
			return out;
		}
		final String dataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String datasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (MatchingResultsBuilder.blank(dataspaceKey) || MatchingResultsBuilder.blank(datasetName))
		{
			return out;
		}
		final AdaptationTable suspected = MatchingResultsBuilder.metadatasetTable(pFactory.getHome().getRepository(), dataspaceKey, datasetName, MasterModel.tablePath(pEntityName),
			"Suspected");
		if (suspected == null)
		{
			return out;
		}
		final StringBuilder predicate = new StringBuilder();
		for (final String key : pKeys)
		{
			predicate.append(predicate.length() > 0 ? " or " : "").append("./sourceId=").append(CatalogReader.quote(key));
		}
		final Set<String> seen = new LinkedHashSet<String>();
		RequestResult rows = null;
		try
		{
			rows = suspected.createRequestResult(predicate.toString());
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String source = MatchingResultsBuilder.safeString(row, "sourceId");
				final String target = MatchingResultsBuilder.safeString(row, "targetId");
				if (source != null && target != null && pKeys.contains(source) && pKeys.contains(target) && seen.add(MatchingResultsBuilder.unorderedKey(source, target)))
				{
					out.add(new String[] { source, target });
				}
			}
		}
		finally
		{
			if (rows != null)
			{
				rows.close();
			}
		}
		return out;
	}

	private static MatchStats.SuspectPair makePair(final Session pSession, final Adaptation pGolden, final Adaptation pSuspect, final String pGoldenKey, final String pSuspectKey,
		final Context pContext, final boolean pWithTreeDetail, final Map<String, MatchResultsView.TreeDetail> pTreeDetailOut, final Set<String> pSourcesSeen)
	{
		final String goldenSource = MatchingResultsBuilder.safeString(pGolden, "sourceSystem");
		final String suspectSource = MatchingResultsBuilder.safeString(pSuspect, "sourceSystem");
		pSourcesSeen.add(goldenSource);
		pSourcesSeen.add(suspectSource);
		final boolean planned = pContext.spec != null && !pContext.spec.attrs.isEmpty();
		final List<MatchStats.FieldComparison> fields = planned ? MatchingResultsBuilder.comparePlanned(pGolden, pSuspect, pContext.spec)
			: MatchingResultsBuilder.compareRecords(pGolden, pSuspect, pContext.compareFieldNames);
		final String goldenLabel = MatchingResultsBuilder.nameOf(pSession, pGolden, pContext, pGoldenKey);
		final String suspectLabel = MatchingResultsBuilder.nameOf(pSession, pSuspect, pContext, pSuspectKey);
		final MatchStats.SuspectPair pair = new MatchStats.SuspectPair(pGoldenKey + "|" + pSuspectKey, pGoldenKey, pSuspectKey, goldenLabel, suspectLabel,
			MatchingResultsBuilder.orUnknown(goldenSource), MatchingResultsBuilder.orUnknown(suspectSource), MatchingResultsBuilder.nullToEmpty(MatchingResultsBuilder.safeString(pGolden, "sourceId")),
			MatchingResultsBuilder.nullToEmpty(MatchingResultsBuilder.safeString(pSuspect, "sourceId")), fields, MatchStats.PENDING);
		if (pWithTreeDetail && pTreeDetailOut.size() < MatchingResultsBuilder.MAX_DETAIL_PAIRS)
		{
			pTreeDetailOut.put(pair.id, MatchingResultsBuilder.evaluate(pSession, pGolden, pSuspect, pContext.matchingPolicyCode));
		}
		return pair;
	}

	private static Adaptation lookup(final AdaptationTable pTable, final String pKeyText)
	{
		try
		{
			return pKeyText == null || pKeyText.trim().isEmpty() ? null : pTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pKeyText.trim()));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Package-visible, pure: the same key for a pair whichever side is named first. */
	static String unorderedKey(final String pA, final String pB)
	{
		final String a = pA == null ? "" : pA;
		final String b = pB == null ? "" : pB;
		return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
	}

	/**
	 * {@code Suspected.sourceId}/{@code targetId} of the first {@code pLimit} pending pairs (ordered by the table's own id,
	 * deterministic), or {@code null} when the statement failed - the caller then uses the former group path. Measured
	 * 2026-09-30 on {@code Master_Personne_MDS}: rows such as {@code 10000~45396 | 10000 | 45396} (ids of the master table's
	 * own primary key).
	 */
	private static List<String[]> queryPendingPairKeys(final Session pSession, final Adaptation pMetadataset, final int pLimit)
	{
		if (pMetadataset == null || pLimit <= 0)
		{
			return null;
		}
		final String sql = "SELECT s.\"sourceId\" AS SRC, s.\"targetId\" AS TGT FROM \"Suspected\" s ORDER BY s.\"id\" FETCH FIRST " + pLimit + " ROWS ONLY";
		final List<String[]> out = new ArrayList<String[]>();
		try
		{
			final Query<Tuple> query = pMetadataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Tuple tuple = it.next();
					final Object source = tuple.get("SRC");
					final Object target = tuple.get("TGT");
					if (source != null && target != null)
					{
						out.add(new String[] { String.valueOf(source), String.valueOf(target) });
					}
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
		return out;
	}

	/**
	 * The number of pending suspect PAIRS: the row count of the metadataset's {@code Suspected} table, one row per pair
	 * (33 374 on Vol100k/{@code Personne}, checked against the ground truth). Not {@code SUM(RecordMetadata.suspectedCount)}
	 * - each pair sits on both records, so that is twice the pairs (66 748 there) - and not the goldens flagged {@code
	 * hasSuspects} (46 663). When {@code Suspected} cannot be read, half of the {@code suspectedCount} sum (rounded up)
	 * stands in.
	 */
	static int pendingPairTotal(final Session pSession, final Adaptation pMetadataset)
	{
		if (pMetadataset == null)
		{
			return 0;
		}
		final int count = MatchingResultsBuilder.runScalarCount(pSession, pMetadataset, "SELECT COUNT(*) AS N FROM \"Suspected\" s", "N");
		if (count > 0)
		{
			return count;
		}
		// zero is either "nothing pending" or "statement refused": the fallback sum answers 0 too in the first case
		return (MatchingResultsBuilder.querySuspectedCountSum(pSession, pMetadataset) + 1) / 2;
	}

	/** Goldens still carrying a suspect flag - a record count, reported as such (never as pairs). */
	private static long queryGoldensWithSuspects(final Session pSession, final Adaptation pMetadataset)
	{
		return pMetadataset == null ? 0
			: MatchingResultsBuilder.runScalarCount(pSession, pMetadataset,
				"SELECT COUNT(*) AS N FROM \"RecordMetadata\" r WHERE r.\"state\" = 1 AND r.\"hasSuspects\" = true", "N");
	}

	// ------------------------------------------------------------------------------------------- SQL (lot volume-4)

	/** One candidate golden record with a suspect flag - {@link #queryPendingGoldenCandidates}'s own row shape. */
	private static final class GoldenCandidate
	{
		final String functionalId;
		/** Null when the SQL row carried no group id at all (defensive - {@code hasSuspects=true} should always pair with one). */
		final BigDecimal groupId;

		GoldenCandidate(final String pFunctionalId, final BigDecimal pGroupId)
		{
			this.functionalId = pFunctionalId;
			this.groupId = pGroupId;
		}
	}

	/**
	 * One {@code GROUP BY "Linked_Field_State", "sourceSystem"} pass over the master table (DELETED rows come back as their own
	 * group and are dropped by the caller; a NULL state is "not matched yet" - lot M4) - the KPI source, {@link MatchStats#fromStateSourceCounts}'s own input. Bounded by the number of
	 * distinct (state, source) pairs actually present, never by row count. {@code Linked_Field_State} is a
	 * computed/value-function field, normally SELECT-only per the EBX SQL dialect reference - measured live
	 * 2026-09-28 against {@code Companies_Master}/{@code Master}/{@code Company} that it IS usable in both
	 * {@code WHERE} and {@code GROUP BY} here (same live measurement backs every other query in this section;
	 * see {@code docs/VOLUME-SCREENS.md}). Returns an empty list on any failure (an unquotable entity name, a
	 * statement the engine refuses, a transport error) - the caller then shows KPIs of all zero, never throws.
	 */
	private static List<MatchStats.StateSourceCount> queryStateSourceCounts(final Session pSession, final Adaptation pMasterDataset, final String pEntityName)
	{
		final List<MatchStats.StateSourceCount> out = new ArrayList<MatchStats.StateSourceCount>();
		if (pMasterDataset == null || !CrossSystemMeasures.isQuotable(pEntityName))
		{
			return out;
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		// lot M4 (2026-10-03, defect 3): NO "WHERE state <> 4" any more. A record the add-on never touched has a NULL state, and
		// "NULL <> 4" is not true: after an incremental run (pivots = the last loaded batch only) every older record vanished
		// from the KPIs (total 338 257 for a 576 692-row table on Vol1m, one source in the band). DELETED (state 4) is a group
		// of its own here and MatchStats.fromStateSourceCounts drops it - still ONE GROUP BY, bounded by (state, source) pairs.
		final String sql = "SELECT c.\"Linked_Field_State\" AS ST, c.\"sourceSystem\" AS SYS, COUNT(*) AS N FROM " + table
			+ " c GROUP BY c.\"Linked_Field_State\", c.\"sourceSystem\"";
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Tuple tuple = it.next();
					final Object count = tuple.get("N");
					if (count instanceof Number)
					{
						final Object sys = tuple.get("SYS");
						final Object rawState = tuple.get("ST");
						// a NULL state = the add-on never touched this record = "not matched yet", like state 0 (UNSET)
						out.add(new MatchStats.StateSourceCount(rawState == null ? "UNSET" : MatchingResultsBuilder.stateName(rawState), sys == null ? null : String.valueOf(sys),
							((Number) count).longValue()));
					}
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return new ArrayList<MatchStats.StateSourceCount>();
		}
		return out;
	}

	/**
	 * Distinct {@code goldenId} values among {@code MERGED} rows, in ONE scalar query - the direct SQL
	 * replacement for counting distinct {@code goldenId}s off a full {@code MetaRow} list. {@code
	 * Linked_Field_GoldenId} is a foreign key (a struct in EBX SQL) - dereferenced to its target's own {@code id}
	 * field, the only way to compare/aggregate it (EBX SQL dialect reference, "Foreign keys & JOINs"). Measured
	 * live 2026-09-28 against {@code Companies_Master}/{@code Master}/{@code Company} (answered {@code 4},
	 * matching an independent {@code COUNT(DISTINCT goldenId)} run directly against the metadataset).
	 */
	private static int queryMergedGroupsCount(final Session pSession, final Adaptation pMasterDataset, final String pEntityName)
	{
		if (pMasterDataset == null || !CrossSystemMeasures.isQuotable(pEntityName))
		{
			return 0;
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String sql = "SELECT COUNT(DISTINCT c.\"Linked_Field_GoldenId\".\"id\") AS N FROM " + table + " c WHERE c.\"Linked_Field_State\" = 2";
		return MatchingResultsBuilder.runScalarCount(pSession, pMasterDataset, sql, "N");
	}

	/**
	 * The "where golden records come from" band, computed ENTIRELY server-side in one two-level aggregate (lot
	 * volume-4, 2026-09-28) - the scalable replacement for {@link MatchStats#computeBand}'s {@code
	 * Map<String, Set<String>>} built by hand from one metadataset row per record. The inner query groups every
	 * non-DELETED record (state 1 GOLDEN counts as its own one-row group, state 2 MERGED groups under its
	 * dereferenced {@code Linked_Field_GoldenId}) and reduces each group to its own distinct-source-system count
	 * plus (when exactly one) which source; the outer query buckets those groups into one row per single source
	 * label plus one {@code MULTIPLE} row. Output is bounded by (distinct source count + 1), never by row count.
	 * Measured live 2026-09-28 against {@code Companies_Master}/{@code Master}/{@code Company}: {@code {ERP: 53,
	 * MULTIPLE: 2, CRM: 50}}, matching the KPI's own {@code goldenRecords} total (105) and the coordinator's
	 * independently-verified merged-group members. {@link MatchStats#bandFromLabelCounts} does the final
	 * sort/{@code MULTIPLE}-last assembly. Empty list on any failure.
	 */
	private static List<MatchStats.BandSegment> queryBandSegments(final Session pSession, final Adaptation pMasterDataset, final String pEntityName)
	{
		if (pMasterDataset == null || !CrossSystemMeasures.isQuotable(pEntityName))
		{
			return new ArrayList<MatchStats.BandSegment>();
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String sql = "WITH G AS (SELECT CASE WHEN c.\"Linked_Field_State\" = 1 THEN c.\"id\" ELSE c.\"Linked_Field_GoldenId\".\"id\" END AS GKEY, "
			+ "COUNT(DISTINCT c.\"sourceSystem\") AS NSRC, MIN(c.\"sourceSystem\") AS SOLE_SRC FROM " + table + " c WHERE c.\"Linked_Field_State\" IN (1, 2) "
			+ "GROUP BY CASE WHEN c.\"Linked_Field_State\" = 1 THEN c.\"id\" ELSE c.\"Linked_Field_GoldenId\".\"id\" END) "
			+ "SELECT CASE WHEN G.NSRC > 1 THEN 'MULTIPLE' ELSE G.SOLE_SRC END AS LABEL, COUNT(*) AS N FROM G "
			+ "GROUP BY CASE WHEN G.NSRC > 1 THEN 'MULTIPLE' ELSE G.SOLE_SRC END";
		final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Tuple tuple = it.next();
					final Object label = tuple.get("LABEL");
					final Object n = tuple.get("N");
					if (label != null && n instanceof Number)
					{
						counts.put(String.valueOf(label), Integer.valueOf(((Number) n).intValue()));
					}
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return new ArrayList<MatchStats.BandSegment>();
		}
		return MatchStats.bandFromLabelCounts(counts);
	}

	/** Shared single-row {@code SELECT ... AS <pAlias>} execution - same iterate/close shape {@code ebx.CrossSystemMeasures#runCountQuery} already established. Zero (never an exception) on any failure, including a {@code SUM} over zero rows (SQL {@code NULL}, not zero - deliberately still reported as 0 here, "nothing pending" reads the same either way). */
	private static int runScalarCount(final Session pSession, final Adaptation pDataset, final String pSql, final String pAlias)
	{
		if (pDataset == null)
		{
			return 0;
		}
		try
		{
			final Query<Tuple> query = pDataset.createQuery(pSql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				if (!it.hasNext())
				{
					return 0;
				}
				final Object value = it.next().get(pAlias);
				return value instanceof Number ? ((Number) value).intValue() : 0;
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return 0;
		}
	}

	/**
	 * <b>Wave 2 (2026-09-30): no longer the pending-pairs total - only the fallback of {@link #pendingPairTotal}, and twice the pair
	 * count (each pair is on both of its records: 66 748 for 33 374 pairs on Vol100k).</b> Former text: the real pending-suspect-pairs total (task brief item 1: "just to count pendingTotal" - the add-on group
	 * read used to run for EVERY golden with {@code hasSuspects} only to size this number). {@code
	 * RecordMetadata.suspectedCount} is the add-on's OWN maintained per-record suspect count ({@code
	 * data-access describe_table}, confirmed live 2026-09-28 holding real, non-zero values on {@code
	 * Companies_Master}/{@code Master_Company_MDS}) - summing it over GOLDEN+{@code hasSuspects} rows in one SQL
	 * round trip answered 292 live, without a single add-on call. Not independently re-verified against a live
	 * {@code MameGroups}/{@code Group.getSuspectRecords()} count beyond that one sample - see {@code
	 * docs/VOLUME-SCREENS.md}.
	 */
	private static int querySuspectedCountSum(final Session pSession, final Adaptation pMetadataset)
	{
		return pMetadataset == null ? 0
			: MatchingResultsBuilder.runScalarCount(pSession, pMetadataset,
				"SELECT SUM(r.\"suspectedCount\") AS N FROM \"RecordMetadata\" r WHERE r.\"state\" = 1 AND r.\"hasSuspects\" = true", "N");
	}

	/** {@code FalsePositiveDecision} row count for this table's own metadataset - the SQL replacement for a full iterate-and-count scan. Measured live 2026-09-28 (answered 0 on a project with no recorded false positives yet). */
	private static int queryFalsePositiveCount(final Session pSession, final Adaptation pMetadataset)
	{
		return pMetadataset == null ? 0 : MatchingResultsBuilder.runScalarCount(pSession, pMetadataset, "SELECT COUNT(*) AS N FROM \"FalsePositiveDecision\" f", "N");
	}

	/**
	 * The first {@code pLimit} GOLDEN records flagged {@code hasSuspects} (deterministically ordered by {@code
	 * id}) - task brief item 1: bounds the add-on's own group read to at most {@code pLimit} calls, never one per
	 * flagged record in the whole table. {@code "$pk"} is the record's own formatted primary key (a plain
	 * VARCHAR system column, EBX SQL dialect reference) - the same string {@code
	 * Adaptation.getOccurrencePrimaryKey().format()} would give, so {@code PrimaryKey.parseString} on it round-
	 * trips correctly even for a composite key. Measured live 2026-09-28 against {@code Companies_Master}/{@code
	 * Master}/{@code Company}. Empty list on any failure.
	 */
	private static List<GoldenCandidate> queryPendingGoldenCandidates(final Session pSession, final Adaptation pMasterDataset, final String pEntityName, final int pLimit)
	{
		final List<GoldenCandidate> out = new ArrayList<GoldenCandidate>();
		if (pMasterDataset == null || !CrossSystemMeasures.isQuotable(pEntityName) || pLimit <= 0)
		{
			return out;
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String sql = "SELECT c.\"$pk\" AS FID, c.\"Linked_Field_GroupId\" AS GRP FROM " + table
			+ " c WHERE c.\"Linked_Field_State\" = 1 AND c.\"Linked_Field_HasSuspects\" = true ORDER BY c.\"id\" FETCH FIRST " + pLimit + " ROWS ONLY";
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Tuple tuple = it.next();
					final Object fid = tuple.get("FID");
					if (fid == null)
					{
						continue;
					}
					out.add(new GoldenCandidate(String.valueOf(fid), MatchingResultsBuilder.asBigDecimal(tuple.get("GRP"))));
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return new ArrayList<GoldenCandidate>();
		}
		return out;
	}

	/**
	 * The first {@code pLimit} distinct {@code goldenId} values among {@code MERGED} rows, ordered - task brief
	 * item 1: bounds the "merged" tab to at most {@code pLimit} groups, never a scan of every MERGED row.
	 * Measured live 2026-09-28: {@code [323, 324]} against a table with exactly two merged groups.
	 */
	private static List<String> queryMergedGoldenIds(final Session pSession, final Adaptation pMasterDataset, final String pEntityName, final int pLimit)
	{
		final List<String> out = new ArrayList<String>();
		if (pMasterDataset == null || !CrossSystemMeasures.isQuotable(pEntityName) || pLimit <= 0)
		{
			return out;
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String sql = "SELECT DISTINCT c.\"Linked_Field_GoldenId\".\"id\" AS GID FROM " + table
			+ " c WHERE c.\"Linked_Field_State\" = 2 ORDER BY c.\"Linked_Field_GoldenId\".\"id\" FETCH FIRST " + pLimit + " ROWS ONLY";
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final String rendered = MatchingResultsBuilder.renderMetadataText(it.next().get("GID"));
					if (rendered != null)
					{
						out.add(rendered);
					}
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return new ArrayList<String>();
		}
		return out;
	}

	/**
	 * Every MERGED member of the given (already-capped) golden id list, in ONE query - task brief item 1: never
	 * one query per group. {@code pGoldenIds} is always this class' own prior {@link #queryMergedGoldenIds}
	 * result (numeric strings straight off a SQL tuple), but every value is still re-checked to look like a
	 * plain integer before being concatenated into the {@code IN (...)} list - defence in depth, same discipline
	 * {@code ebx.CrossSystemMeasures#isQuotable} applies to an identifier. Measured live 2026-09-28: the two
	 * groups' four members, correct {@code sourceSystem} per row.
	 */
	private static Map<String, List<String[]>> queryMergedMembers(final Session pSession, final Adaptation pMasterDataset, final String pEntityName,
		final List<String> pGoldenIds)
	{
		final Map<String, List<String[]>> out = new LinkedHashMap<String, List<String[]>>();
		if (pMasterDataset == null || !CrossSystemMeasures.isQuotable(pEntityName) || pGoldenIds == null || pGoldenIds.isEmpty())
		{
			return out;
		}
		final StringBuilder idList = new StringBuilder();
		for (final String id : pGoldenIds)
		{
			if (id == null || !id.matches("-?\\d+"))
			{
				continue;
			}
			if (idList.length() > 0)
			{
				idList.append(',');
			}
			idList.append(id);
		}
		if (idList.length() == 0)
		{
			return out;
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String sql = "SELECT c.\"$pk\" AS FID, c.\"sourceSystem\" AS SYS, c.\"Linked_Field_GoldenId\".\"id\" AS GID FROM " + table
			+ " c WHERE c.\"Linked_Field_State\" = 2 AND c.\"Linked_Field_GoldenId\".\"id\" IN (" + idList + ")";
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Tuple tuple = it.next();
					final Object fid = tuple.get("FID");
					final Object gid = tuple.get("GID");
					if (fid == null || gid == null)
					{
						continue;
					}
					final String goldenKey = MatchingResultsBuilder.renderMetadataText(gid);
					if (goldenKey == null)
					{
						continue;
					}
					List<String[]> members = out.get(goldenKey);
					if (members == null)
					{
						members = new ArrayList<String[]>();
						out.put(goldenKey, members);
					}
					final Object sys = tuple.get("SYS");
					members.add(new String[] { String.valueOf(fid), sys == null ? null : String.valueOf(sys) });
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return new LinkedHashMap<String, List<String[]>>();
		}
		return out;
	}

	/** A SQL tuple value (typically {@code xs:decimal} -> {@link BigDecimal}, occasionally a plain {@link Number} or its string form) coerced to a {@link BigDecimal} - null when it is none of those. */
	private static BigDecimal asBigDecimal(final Object pValue)
	{
		if (pValue instanceof BigDecimal)
		{
			return (BigDecimal) pValue;
		}
		if (pValue instanceof Number)
		{
			return BigDecimal.valueOf(((Number) pValue).longValue());
		}
		if (pValue instanceof String)
		{
			try
			{
				return new BigDecimal((String) pValue);
			}
			catch (final RuntimeException ex)
			{
				return null;
			}
		}
		return null;
	}

	private static String quote(final String pValue)
	{
		return "'" + (pValue == null ? "" : pValue.replace("'", "''")) + "'";
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	private static String orUnknown(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() ? "?" : pValue.trim();
	}

	/** {@code Adaptation.get} throws on a path the table does not define (CLAUDE.md sec 9) - guarded, same idiom as {@code ebx.RecordLineageReader#safeGet}. */
	private static Object safeGet(final Adaptation pRow, final String pFieldName)
	{
		if (pRow == null || pFieldName == null)
		{
			return null;
		}
		final Path path = Path.parse("./" + pFieldName);
		return pRow.getSchemaNode().getNode(path) == null ? null : pRow.get(path);
	}

	/**
	 * Renders a raw metadataset/SQL value as text (lot R11, 2026-09-27, live PersVol7 crash, {@code kernel.log}
	 * 21:58:03,943: {@code ClassCastException: BigDecimal cannot be cast to String} at {@code
	 * CfContent.getString}, thrown by an earlier version of this class that called {@code
	 * Adaptation.getString(Path)} directly on a numeric {@code RecordMetadata.groupId}/{@code goldenId} field -
	 * {@code Adaptation.getString(Path)} assumes the underlying value is already a String and throws instead of
	 * formatting a number). A {@link BigDecimal} renders through {@link BigDecimal#toPlainString()} (never
	 * {@code toString()}, which can switch to scientific notation - CLAUDE.md sec 2's own {@code db.field<T>}
	 * note about a SQL decimal column applies the same way here), any other value through {@code
	 * String.valueOf}, and a blank result (empty or all-whitespace) folds back to {@code null} ("blank means
	 * absent"). Package-visible: unit-tested directly in {@code MatchingResultsBuilderTest}; also used (lot
	 * volume-4, 2026-09-28) to render a SQL query's own {@code BigDecimal}/{@code Integer} tuple values (a
	 * golden id from {@link #queryMergedGoldenIds}) into the same plain key strings.
	 */
	static String renderMetadataText(final Object pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		final String text = pValue instanceof BigDecimal ? ((BigDecimal) pValue).stripTrailingZeros().toPlainString() : String.valueOf(pValue);
		return text.trim().isEmpty() ? null : text;
	}

	private static String safeString(final Adaptation pRow, final String pFieldName)
	{
		final Object value = MatchingResultsBuilder.safeGet(pRow, pFieldName);
		if (value instanceof java.util.Date)
		{
			// a date field (a birth date) is a Date at midnight: shown as the day, not as an instant; a date-time keeps its full form
			final java.time.ZonedDateTime local = ((java.util.Date) value).toInstant().atZone(java.time.ZoneId.systemDefault());
			if (local.toLocalTime().equals(java.time.LocalTime.MIDNIGHT))
			{
				return local.toLocalDate().toString();
			}
			return ((java.util.Date) value).toInstant().toString();
		}
		return value == null ? null : String.valueOf(value);
	}
}
