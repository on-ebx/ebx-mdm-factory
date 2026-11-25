package com.on.ebx.factory.ebx;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.staging.RunGridCellState;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.wizard.WizardMessages;
import com.on.ebx.factory.wizard.WizardSnapshot;
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
import com.orchestranetworks.service.Session;

/**
 * Wizard-visual-pass lot (2026-09-24, docs/mockups/wizard-flow/StagingLoad.dc.html): builds the grid
 * step 4e shows under "Fill the shared structure" - rows are the project's accepted business objects,
 * columns are one per application plus "In staging". Every number is read live, never invented:
 * <ul>
 * <li>a cell's "rows" count is a live {@code UserView.select(AdaptationTable, "./sourceSystem=...").
 * getSize()} on the entity's own staging table - the field every staging row carries ({@code StagingLoader}'s
 * own {@code ./sourceSystem}, see its class Javadoc, "the field every staging row carries the source
 * system on").</li>
 * <li>a cell's "held back" count is a live count of {@code TransferReject} rows for that entity, that
 * source, stage {@code STAGING}, not yet {@code resolved} - the same table {@code FactoryCockpitUserService}
 * already reads to LIST rejects (this class only counts, filtered).</li>
 * <li>"In staging" is the entity's own staging table's total row count (every application combined).</li>
 * </ul>
 * A table that cannot be found (staging model/dataset not generated yet) or a project with no accepted
 * business object at all yields an EMPTY grid ({@link WizardSnapshot.RunGridInfo#rows} empty) - the caller
 * (step 4's {@code ProjectWizardUserService#model}) only calls this once {@code StagingLoadRun} is not
 * currently RUNNING, so a genuinely mid-run state is never misread as "0 rows everywhere".
 * <p>
 * <b>Live bug fix (2026-09-25, PersVol2 volume test):</b> the table path used to be read off {@code
 * StagingEntity.targetTable} - a field {@code StagingLoader} does not write any more (it computes the
 * staging table path directly from the entity's own name, {@code StagingModel.tablePath(entity.name)}, the
 * same as this class's own Javadoc for {@code MasterBuildGridReader} already notes for the master zone).
 * Nothing else in this codebase writes {@code targetTable} either (it is only ever nulled by {@code
 * StepResetter}), so every cell of every row read as {@code WAITING} (dashes) even right after a successful
 * load with real rows written - the exact same root cause {@code DistributionConfigurator}/{@code
 * DistributionTriggerInstaller} hit and fixed the same way in commit 44b13da ({@code
 * ProjectMasterEntities.promotedMasterTablePaths}, the master-zone equivalent). Fixed here by computing the
 * table path the same way {@code StagingLoader} itself does, never reading {@code targetTable} again.
 */
public final class StagingLoadGridReader
{
	private StagingLoadGridReader()
	{
	}

	/** @deprecated lot volume-4 (2026-09-28): kept only so an out-of-tree caller does not break at compile time - the in-tree caller now passes a {@link Session} (see {@link #build(Session, Adaptation, String, WizardSnapshot, Locale)}), needed for the SQL {@code GROUP BY} queries that replace what used to be one {@code getSize()} predicate-count call per grid cell. */
	@Deprecated
	public static WizardSnapshot.RunGridInfo build(final Adaptation pFactory, final String pProjectId, final WizardSnapshot pSnapshot, final Locale pLocale)
	{
		return StagingLoadGridReader.build(null, pFactory, pProjectId, pSnapshot, pLocale);
	}

	/**
	 * Lot volume-4 (2026-09-28, task brief item 8): {@code pSession} is new - needed to run the two SQL {@code
	 * GROUP BY} queries below in place of the old per-cell {@code RequestResult.getSize()} calls ({@link
	 * #applicationCell}/{@link #inStagingCell} used to run up to two count queries EACH, for every (entity,
	 * source) cell of the grid - a project with 20 entities and 5 applications meant up to ~200 separate EBX
	 * round trips just to render this one grid). {@code heldBackByEntityAndSource} is ONE query for the WHOLE
	 * grid (every entity's held-back counts, grouped); {@code rowsBySource} is one query PER ENTITY (its own
	 * staging table's row counts, grouped by {@code sourceSystem} - the same numbers also sum into the "In
	 * staging" total, so no separate total query either). Either map is {@code null} when its own query failed
	 * (an unquotable identifier, a transport error) - {@link #applicationCell}/{@link #inStagingCell} then fall
	 * back to the original per-cell read for that one entity/cell, never silently showing zero. The in-tree
	 * caller ({@code ebx.ui.ProjectWizardUserService}) already has a {@code Session} in scope.
	 */
	public static WizardSnapshot.RunGridInfo build(final Session pSession, final Adaptation pFactory, final String pProjectId, final WizardSnapshot pSnapshot,
		final Locale pLocale)
	{
		final Adaptation project = UserView.byKey(pFactory.getTable(FactoryPaths.PROJECT), PrimaryKey.parseString(pProjectId));
		final String stagingDataspaceKey = project == null ? null : UserView.string(project, FactoryPaths.Project.STAGING_DATASPACE);
		final String stagingDatasetName = project == null ? null : UserView.string(project, FactoryPaths.Project.STAGING_DATASET);
		final Adaptation stagingDataset = project == null ? null : StagingLoadGridReader.findDataset(pFactory, stagingDataspaceKey, stagingDatasetName);

		// audit F-33: the grid is whole-table GROUP BYs, redrawn on every render of every step. It is cached under a key holding everything it
		// depends on - the snapshot's own inputs, the user (the queries run as the session) and the last modification date of each staging table
		// and of TransferReject, which moves on any write - so a load, a rejected row or a resolved one builds a new key and the grid is measured
		// again; the same data produces the same grid, from the cache or not.
		final Map<String, AdaptationTable> stagingTables = new LinkedHashMap<String, AdaptationTable>();
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if ("ACCEPTED".equals(entity.status) || "APPLIED".equals(entity.status))
			{
				stagingTables.put(entity.id, stagingDataset == null ? null : StagingLoadGridReader.findTable(stagingDataset, StagingModel.tablePath(entity.name)));
			}
		}
		final String cacheKey = StagingLoadGridReader.cacheKey(pSession, pFactory, pProjectId, pSnapshot, pLocale, stagingDataspaceKey, stagingDatasetName, stagingTables);
		if (cacheKey != null)
		{
			final WizardSnapshot.RunGridInfo cached = StagingLoadGridReader.CACHE.get(cacheKey, System.currentTimeMillis());
			if (cached != null)
			{
				return cached;
			}
		}
		final boolean[] degraded = new boolean[1];

		final WizardSnapshot.RunGridInfo grid = new WizardSnapshot.RunGridInfo();
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			final String label = !source.catalogSourceLabel.isEmpty() ? source.catalogSourceLabel
				: !source.connectorLabel.isEmpty() ? source.connectorLabel : source.sourceSystem;
			grid.columnLabels.add(label);
		}
		grid.columnLabels.add(WizardMessages.get(pLocale, "wizard.step4.grid.inStaging"));

		final Map<String, Long> heldBackByEntityAndSource = pSession == null ? null : StagingLoadGridReader.queryHeldBackCounts(pSession, pFactory, pProjectId);

		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (!"ACCEPTED".equals(entity.status) && !"APPLIED".equals(entity.status))
			{
				continue;
			}
			final WizardSnapshot.RunGridRow row = new WizardSnapshot.RunGridRow();
			row.label = entity.name;
			int rulesAndChecks = 0;
			for (final WizardSnapshot.ProposalLineInfo line : entity.proposalLines)
			{
				if (ReviewLine.QUALITY.equals(line.line.nature) || ReviewLine.SCRIPT_CONSTRAINT.equals(line.line.nature))
				{
					rulesAndChecks++;
				}
			}
			// wizard-visual-pass ROUND 3 (2026-09-24): two independent counted nouns ("field(s)" and "rule(s)
			// and check(s)") in one sublabel - each pluralized on its own with countWords.
			row.sublabel = WizardMessages.get(pLocale, "wizard.step4.grid.sublabel", WizardMessages.countWords(pLocale, entity.attributes.size(), "wizard.unit.field"),
				WizardMessages.countWords(pLocale, rulesAndChecks, "wizard.unit.ruleAndCheck"));

			final AdaptationTable stagingTable = stagingTables.get(entity.id);
			final Map<String, Long> rowsBySource = pSession == null || stagingTable == null ? null
				: StagingLoadGridReader.queryRowsBySource(pSession, stagingDataset, entity.name);

			for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
			{
				row.cells.add(StagingLoadGridReader.applicationCell(pFactory, pProjectId, entity.id, source.sourceSystem, stagingTable, rowsBySource,
					heldBackByEntityAndSource, pLocale, degraded));
			}
			row.cells.add(StagingLoadGridReader.inStagingCell(stagingTable, rowsBySource, pSnapshot.sources.size(), pLocale, degraded));
			grid.rows.add(row);
		}
		if (cacheKey != null && !degraded[0])
		{
			StagingLoadGridReader.CACHE.put(cacheKey, grid, System.currentTimeMillis());
		}
		return grid;
	}

	/** Audit F-33: the bound on what no cache key can see (a write the table's modification date does not cover). */
	static final long CACHE_MAX_AGE_MS = 5L * 60L * 1000L;
	static final int CACHE_MAX_ENTRIES = 32;
	static final ReadCache<WizardSnapshot.RunGridInfo> CACHE = new ReadCache<WizardSnapshot.RunGridInfo>(StagingLoadGridReader.CACHE_MAX_ENTRIES,
		StagingLoadGridReader.CACHE_MAX_AGE_MS);

	/**
	 * Pure: the part of the cache key that comes from the snapshot - every input of the grid's TEXT: the columns (application label and code) and,
	 * per accepted business object, its id, name, field count and "rules and checks" count (the two numbers of its sublabel).
	 */
	static String snapshotSignature(final WizardSnapshot pSnapshot)
	{
		final StringBuilder out = new StringBuilder();
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			final String label = !source.catalogSourceLabel.isEmpty() ? source.catalogSourceLabel
				: !source.connectorLabel.isEmpty() ? source.connectorLabel : source.sourceSystem;
			out.append("S\u0002").append(label).append('\u0002').append(source.sourceSystem).append('\u0001');
		}
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (!"ACCEPTED".equals(entity.status) && !"APPLIED".equals(entity.status))
			{
				continue;
			}
			int rulesAndChecks = 0;
			for (final WizardSnapshot.ProposalLineInfo line : entity.proposalLines)
			{
				if (ReviewLine.QUALITY.equals(line.line.nature) || ReviewLine.SCRIPT_CONSTRAINT.equals(line.line.nature))
				{
					rulesAndChecks++;
				}
			}
			out.append("E\u0002").append(entity.id).append('\u0002').append(entity.name).append('\u0002').append(entity.attributes.size()).append('\u0002')
				.append(rulesAndChecks).append('\u0001');
		}
		return out.toString();
	}

	/** The table's last modification time as text, or null when it cannot be read (then nothing is cached). A missing table is "-". */
	static String modificationToken(final AdaptationTable pTable)
	{
		if (pTable == null)
		{
			return "-";
		}
		try
		{
			final java.util.Date date = pTable.getLastModificationDate();
			return date == null ? "0" : String.valueOf(date.getTime());
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Null when a modification date could not be read: the grid is then measured, never cached. */
	private static String cacheKey(final Session pSession, final Adaptation pFactory, final String pProjectId, final WizardSnapshot pSnapshot, final Locale pLocale,
		final String pStagingDataspace, final String pStagingDataset, final Map<String, AdaptationTable> pStagingTables)
	{
		final StringBuilder key = new StringBuilder();
		key.append(pProjectId).append('\u0003').append(String.valueOf(pLocale)).append('\u0003').append(pSession == null ? "-" : String.valueOf(pSession.getUserReference()))
			.append('\u0003').append(pStagingDataspace).append('\u0003').append(pStagingDataset).append('\u0003').append(StagingLoadGridReader.snapshotSignature(pSnapshot))
			.append('\u0003');
		final String rejects = StagingLoadGridReader.modificationToken(pFactory.getTable(FactoryPaths.TRANSFER_REJECT));
		if (rejects == null)
		{
			return null;
		}
		key.append("R").append(rejects);
		for (final Map.Entry<String, AdaptationTable> entry : pStagingTables.entrySet())
		{
			final String token = StagingLoadGridReader.modificationToken(entry.getValue());
			if (token == null)
			{
				return null;
			}
			key.append('\u0001').append(entry.getKey()).append('=').append(token);
		}
		return key.toString();
	}

	/**
	 * {@code pRowsBySource}/{@code pHeldBackByEntityAndSource} are the grid-wide/per-entity SQL {@code GROUP BY}
	 * answers {@link #build} computed up front (lot volume-4, 2026-09-28, task brief item 8) - a present map is
	 * read directly (a missing key legitimately means zero, the {@code GROUP BY} simply has no row for it, never
	 * "not measured"); a {@code null} map means that ONE query failed, and only then does this cell fall back to
	 * the original per-cell {@link #count}/{@link #countRejects} read - never silently showing zero for a query
	 * that never ran.
	 */
	private static WizardSnapshot.RunGridCell applicationCell(final Adaptation pFactory, final String pProjectId, final String pEntityId,
		final String pSourceSystem, final AdaptationTable pStagingTable, final Map<String, Long> pRowsBySource, final Map<String, Long> pHeldBackByEntityAndSource,
		final Locale pLocale, final boolean[] pDegraded)
	{
		if (pStagingTable == null)
		{
			return new WizardSnapshot.RunGridCell("WAITING", "", "");
		}
		final long rows = pRowsBySource != null ? StagingLoadGridReader.orZero(pRowsBySource.get(pSourceSystem))
			: StagingLoadGridReader.count(pStagingTable, CatalogReader.equalsPredicate("./sourceSystem", pSourceSystem));
		final long heldBack = pHeldBackByEntityAndSource != null
			? StagingLoadGridReader.orZero(pHeldBackByEntityAndSource.get(StagingLoadGridReader.heldBackKey(pEntityId, pSourceSystem)))
			: StagingLoadGridReader.countRejects(pFactory, pProjectId, pEntityId, pSourceSystem);
		if (rows < 0 || heldBack < 0)
		{
			pDegraded[0] = true; // a count that could not be obtained: shown as before, never kept for the next render
		}
		final String primary = rows < 0 ? "" : WizardMessages.get(pLocale, "wizard.step4.grid.rows", Long.valueOf(Math.max(0L, rows)));
		final String secondary = heldBack <= 0 ? "" : WizardMessages.plural(pLocale, heldBack, "wizard.step4.grid.heldBack.one", "wizard.step4.grid.heldBack.other");
		final String state = RunGridCellState.forApplicationCell(true, rows, heldBack);
		final WizardSnapshot.RunGridCell cell = new WizardSnapshot.RunGridCell(state, primary, secondary);
		if (rows >= 0)
		{
			cell.primaryValue = Long.valueOf(rows);
		}
		if (heldBack > 0)
		{
			cell.heldBackValue = Long.valueOf(heldBack); // staging-screens lot (2026-10-17): step 4's top banner names the business object
		}
		return cell;
	}

	/** {@code pRowsBySource} non-null: the "In staging" total is the SUM of the per-entity {@code GROUP BY} query {@link #build} already ran for the application cells - no separate total query. Null: falls back to the original {@link #count} read. */
	private static WizardSnapshot.RunGridCell inStagingCell(final AdaptationTable pStagingTable, final Map<String, Long> pRowsBySource, final int pApplicationCount,
		final Locale pLocale, final boolean[] pDegraded)
	{
		if (pStagingTable == null)
		{
			return new WizardSnapshot.RunGridCell("WAITING", "", "");
		}
		long total;
		if (pRowsBySource != null)
		{
			total = 0L;
			for (final Long value : pRowsBySource.values())
			{
				total += value.longValue();
			}
		}
		else
		{
			total = StagingLoadGridReader.count(pStagingTable, null);
		}
		if (total < 0)
		{
			pDegraded[0] = true;
		}
		final String primary = total < 0 ? "" : WizardMessages.get(pLocale, "wizard.step4.grid.rows", Long.valueOf(Math.max(0L, total)));
		final String secondary = pApplicationCount <= 1 ? "" : WizardMessages.get(pLocale, "wizard.step4.grid.fromApplications", Integer.valueOf(pApplicationCount));
		final WizardSnapshot.RunGridCell cell = new WizardSnapshot.RunGridCell("DONE", primary, secondary);
		if (total >= 0)
		{
			cell.primaryValue = Long.valueOf(total);
		}
		return cell;
	}

	private static long orZero(final Long pValue)
	{
		return pValue == null ? 0L : pValue.longValue();
	}

	/**
	 * Robustness audit F2-105: adds {@code pCount} to what {@code pKey} already holds instead of overwriting it, so the total stays right even if a SQL engine split one group
	 * into several tuples (the "one group per row" defect of {@code GROUP BY} on a foreign key, product-bugs/2026-10-06). Pure, package-private for the test.
	 */
	static void accumulate(final Map<String, Long> pOut, final String pKey, final long pCount)
	{
		final Long soFar = pOut.get(pKey);
		pOut.put(pKey, Long.valueOf(soFar == null ? pCount : soFar.longValue() + pCount));
	}

	private static String heldBackKey(final String pEntityId, final String pSourceSystem)
	{
		return pEntityId + "\u0001" + pSourceSystem;
	}

	/**
	 * ONE SQL round trip for the WHOLE grid's held-back counts (task brief item 8) - {@code GROUP BY} the
	 * project's own {@code TransferReject} table by (entity, sourceSystem), {@code stage='STAGING'} and not
	 * resolved, replacing what used to be one {@code getSize()} predicate-count call per application cell.
	 * {@code entity}/{@code project} are foreign keys - dereferenced to their own primary key fields ({@code id}
	 * for {@code StagingEntity}, {@code code} for {@code Project} - {@code data-access describe_table}, measured
	 * live 2026-09-28: comparing {@code t."project"."id"} answered {@code INVALID}, {@code Project}'s real
	 * primary key is {@code code}), never compared as a struct (EBX SQL dialect reference, "Foreign keys &amp;
	 * JOINs"). {@code not(./resolved=true())}'s SQL mirror is {@code NOT (resolved = true)} - a null/unset
	 * {@code resolved} counts as unresolved either way. Returns {@code null} (never partial counts) on any
	 * failure - the caller then falls back to {@link #countRejects} per cell.
	 */
	private static Map<String, Long> queryHeldBackCounts(final Session pSession, final Adaptation pFactory, final String pProjectId)
	{
		final String sql = "SELECT t.\"entity\".\"id\" AS ENT, t.\"sourceSystem\" AS SYS, COUNT(*) AS N FROM \"TransferReject\" t WHERE t.\"project\".\"code\" = "
			+ MatchPlanSimulationRunner.sqlStringLiteral(pProjectId) + " AND t.\"stage\" = 'STAGING' AND NOT (t.\"resolved\" = true) GROUP BY t.\"entity\".\"id\", "
			+ "t.\"sourceSystem\"";
		final Map<String, Long> out = new LinkedHashMap<String, Long>();
		try
		{
			final Query<Tuple> query = pFactory.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Tuple tuple = it.next();
					final Object entityId = tuple.get("ENT");
					final Object sourceSystem = tuple.get("SYS");
					final Object count = tuple.get("N");
					if (entityId != null && sourceSystem != null && count instanceof Number)
					{
						StagingLoadGridReader.accumulate(out, StagingLoadGridReader.heldBackKey(String.valueOf(entityId), String.valueOf(sourceSystem)),
							((Number) count).longValue());
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
	 * ONE SQL round trip per entity (task brief item 8) - {@code GROUP BY sourceSystem} on the entity's own
	 * staging table, replacing one {@code getSize()} predicate-count call per application cell PLUS the separate
	 * "In staging" total read ({@link #inStagingCell} now sums this map instead). Returns {@code null} on any
	 * failure (an unquotable entity name, a transport error) - the caller then falls back to {@link #count} per
	 * cell/entity. Measured live 2026-09-28 against {@code Companies_Staging}/{@code Staging}/{@code Company}:
	 * {@code {ERP: 55, CRM: 52}}.
	 */
	private static Map<String, Long> queryRowsBySource(final Session pSession, final Adaptation pStagingDataset, final String pEntityName)
	{
		if (!CrossSystemMeasures.isQuotable(pEntityName))
		{
			return null;
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String sql = "SELECT c.\"sourceSystem\" AS SYS, COUNT(*) AS N FROM " + table + " c GROUP BY c.\"sourceSystem\"";
		final Map<String, Long> out = new LinkedHashMap<String, Long>();
		try
		{
			final Query<Tuple> query = pStagingDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Tuple tuple = it.next();
					final Object sourceSystem = tuple.get("SYS");
					final Object count = tuple.get("N");
					if (sourceSystem != null && count instanceof Number)
					{
						StagingLoadGridReader.accumulate(out, String.valueOf(sourceSystem), ((Number) count).longValue());
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

	private static long count(final AdaptationTable pTable, final String pPredicate)
	{
		final RequestResult rows = UserView.select(pTable, pPredicate);
		try
		{
			return rows.getSize();
		}
		catch (final RuntimeException ex) // never blocks the grid over a count that could not be obtained
		{
			return -1L;
		}
		finally
		{
			rows.close();
		}
	}

	private static long countRejects(final Adaptation pFactory, final String pProjectId, final String pEntityId, final String pSourceSystem)
	{
		final String predicate = "./project=" + CatalogReader.quote(pProjectId) + " and ./entity=" + CatalogReader.quote(pEntityId) + " and "
			+ CatalogReader.equalsPredicate("./sourceSystem", pSourceSystem) + " and ./stage=" + CatalogReader.quote("STAGING") + " and not(./resolved=" + CatalogReader.quote("true") + ")";
		try
		{
			return StagingLoadGridReader.count(pFactory.getTable(FactoryPaths.TRANSFER_REJECT), predicate);
		}
		catch (final RuntimeException ex)
		{
			return -1L;
		}
	}

	private static Adaptation findDataset(final Adaptation pFactory, final String pDataspaceKey, final String pDatasetName)
	{
		if (pDataspaceKey == null || pDataspaceKey.isEmpty() || pDatasetName == null || pDatasetName.isEmpty())
		{
			return null;
		}
		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(pDataspaceKey));
		return home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pDatasetName));
	}

	private static AdaptationTable findTable(final Adaptation pDataset, final String pTargetTable)
	{
		try
		{
			return pDataset.getTable(Path.parse(pTargetTable));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}
}
