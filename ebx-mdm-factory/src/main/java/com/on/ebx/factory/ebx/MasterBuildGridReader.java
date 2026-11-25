package com.on.ebx.factory.ebx;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.staging.RunGridCellState;
import com.on.ebx.factory.staging.RunProgress;
import com.on.ebx.factory.wizard.WizardMessages;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;

/**
 * Wizard-visual-pass lot (2026-09-24, docs/mockups/wizard-flow/MasterDone.dc.html): builds the "built"
 * grid step 5 shows once {@link MasterBuildRun} is no longer running - rows are the project's promoted
 * business objects (same accepted-like set {@link StagingLoadGridReader} uses), three fixed columns
 * ({@code Tables}/{@code Copy}/{@code Matching ready}, {@link MasterBuildRun#TABLES}/{@link
 * MasterBuildRun#COPY}/{@link MasterBuildRun#MATCHING_READY}).
 * <p>
 * The {@code Copy} column's row count is read from the latest {@code MASTER_LOAD} {@code PipelineStep}'s
 * own {@code detail} JSON ({@code entities: [{entity, written, unchanged, ...}]}, written by {@code MasterLoader.load}
 * - see that class's own field names) rather than a fresh live count on the master table: {@code
 * MasterLoader} already measured it precisely during the transfer, and re-deriving it from a live query
 * would need to guess the master table's path, which this class has no reliable way to compute (unlike
 * {@link StagingLoadGridReader}, which reads it straight off {@code StagingEntity.targetTable}).
 */
public final class MasterBuildGridReader
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MasterBuildGridReader()
	{
	}

	public static WizardSnapshot.RunGridInfo build(final Adaptation pFactory, final String pProjectId, final WizardSnapshot pSnapshot, final Locale pLocale)
	{
		final WizardSnapshot.RunGridInfo grid = new WizardSnapshot.RunGridInfo();
		grid.columnLabels.add(WizardMessages.get(pLocale, "wizard.step5.grid.tables"));
		grid.columnLabels.add(WizardMessages.get(pLocale, "wizard.step5.grid.copy"));
		grid.columnLabels.add(WizardMessages.get(pLocale, "wizard.step5.grid.matchingReady"));

		// audit F-104: the three PipelineStep questions below (two isDone, the latest MASTER_LOAD detail) and the matching-ready fallback read the project's shared
		// step index - one scan per table change, the same the wizard snapshot reads - instead of four predicate queries over the table of every project
		final PipelineStepIndex steps = PipelineStepIndex.forProject(pFactory, pProjectId);
		final boolean tablesDone = steps.isDone("MASTER_DATASET", "");
		final boolean copyStepDone = steps.isDone("MASTER_LOAD", "");
		final Map<String, Long> writtenByEntity = MasterBuildGridReader.readMasterLoadDetail(steps.projectWide("MASTER_LOAD"));
		final Adaptation project = UserView.byKey(pFactory.getTable(FactoryPaths.PROJECT), PrimaryKey.parseString(pProjectId));
		final boolean matchingReady = project != null && MasterBuildGridReader.matchingReadyDone(pFactory, project, steps);

		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (!"ACCEPTED".equals(entity.status) && !"APPLIED".equals(entity.status))
			{
				continue;
			}
			final WizardSnapshot.RunGridRow row = new WizardSnapshot.RunGridRow();
			row.label = entity.name;
			row.sublabel = WizardMessages.plural(pLocale, entity.attributes.size(), "wizard.step5.grid.sublabel.one", "wizard.step5.grid.sublabel.other");

			row.cells.add(new WizardSnapshot.RunGridCell(RunGridCellState.forStepCell(tablesDone),
				tablesDone ? WizardMessages.get(pLocale, "wizard.step5.grid.created") : "", ""));

			final Long written = writtenByEntity.get(entity.name);
			if (written != null)
			{
				final WizardSnapshot.RunGridCell copyCell =
					new WizardSnapshot.RunGridCell(RunGridCellState.DONE, WizardMessages.get(pLocale, "wizard.step4.grid.rows", written), "");
				copyCell.primaryValue = written;
				row.cells.add(copyCell);
			}
			else
			{
				row.cells.add(new WizardSnapshot.RunGridCell(RunGridCellState.forStepCell(copyStepDone), "", ""));
			}

			row.cells.add(new WizardSnapshot.RunGridCell(RunGridCellState.forStepCell(matchingReady),
				matchingReady ? WizardMessages.get(pLocale, "wizard.step5.grid.ready") : "", ""));
			grid.rows.add(row);
		}
		return grid;
	}

	/** {@code TransferReject} rows still held back in STAGING (unresolved) - the "N rows held back in staging" KPI tile, project-wide (never per entity: the board's own tile is a single number). */
	public static long heldBackInStagingCount(final Adaptation pFactory, final String pProjectId)
	{
		// audit F-33: the wizard asked twice per render (steps 4 and 5), a scan of TransferReject each time. The count only moves when that table
		// does, so it is kept under the table's last modification date (any reject written or resolved changes it); a failed count is never kept.
		final String token = StagingLoadGridReader.modificationToken(pFactory.getTable(FactoryPaths.TRANSFER_REJECT));
		// second permissions pass (2026-10-14): counted with the current view (the person's inside a screen's read scope): one entry per viewer
		final com.orchestranetworks.service.Session viewer = UserView.current().session();
		final String key = token == null ? null
			: MasterBuildGridReader.heldBackKey(pProjectId + "\u0002" + (viewer == null ? "-" : String.valueOf(viewer.getUserReference())), token);
		if (key != null)
		{
			final Long cached = MasterBuildGridReader.HELD_BACK.get(key, System.currentTimeMillis());
			if (cached != null)
			{
				return cached.longValue();
			}
		}
		final long count = MasterBuildGridReader.countHeldBack(pFactory, pProjectId);
		if (key != null && count >= 0L)
		{
			MasterBuildGridReader.HELD_BACK.put(key, Long.valueOf(count), System.currentTimeMillis());
		}
		return count;
	}

	static final ReadCache<Long> HELD_BACK = new ReadCache<Long>(64, StagingLoadGridReader.CACHE_MAX_AGE_MS);

	/** Pure: the cache key of the held-back count - the project and the modification token of the reject table. */
	static String heldBackKey(final String pProjectId, final String pRejectTableToken)
	{
		return pProjectId + "\u0001" + pRejectTableToken;
	}

	private static long countHeldBack(final Adaptation pFactory, final String pProjectId)
	{
		final String predicate = "./project=" + CatalogReader.quote(pProjectId) + " and ./stage=" + CatalogReader.quote("STAGING") + " and not(./resolved="
			+ CatalogReader.quote("true") + ")";
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.TRANSFER_REJECT), predicate);
		try
		{
			return rows.getSize();
		}
		catch (final RuntimeException ex)
		{
			return -1L;
		}
		finally
		{
			rows.close();
		}
	}

	/** {@link MasterBuildRun#MATCHING_READY} of the latest run, when one exists; falls back to {@code MASTER_DATASET} done (a project built with the older, separate "Advanced" buttons never has a {@link MasterBuildRun} row at all). */
	private static boolean matchingReadyDone(final Adaptation pFactory, final Adaptation pProject, final PipelineStepIndex pSteps)
	{
		final String projectCode = UserView.string(pProject, FactoryPaths.Project.CODE);
		final com.fasterxml.jackson.databind.node.ObjectNode status = MasterBuildRun.status(pFactory, projectCode);
		if (!status.path("exists").asBoolean(false))
		{
			return pSteps.isDone("MASTER_DATASET", "");
		}
		final RunProgress progress = RunProgress.parse(status.path("detailJson").asText(null), MasterBuildRun.STAGES);
		final RunProgress.Stage stage = progress.stage(MasterBuildRun.MATCHING_READY);
		return stage != null && RunProgress.DONE.equals(stage.state);
	}

	/** Latest {@code MASTER_LOAD} step's {@code detail} JSON, parsed into {@code entity name -> rows in master from the copy} ({@code written + unchanged}, lot V3-M) - empty map when none ran yet or it could not be parsed. */
	private static Map<String, Long> readMasterLoadDetail(final Adaptation pLatest)
	{
		final Map<String, Long> out = new HashMap<String, Long>();
		final Adaptation latest = pLatest;
		if (latest == null)
		{
			return out;
		}
		final String detail = UserView.string(latest, FactoryPaths.PipelineStep.DETAIL);
		if (detail == null || detail.trim().isEmpty())
		{
			return out;
		}
		try
		{
			final JsonNode root = MasterBuildGridReader.MAPPER.readTree(detail);
			for (final JsonNode entityNode : root.path("entities"))
			{
				final String name = entityNode.path("entity").asText(null);
				if (name != null)
				{
					// lot V3-M (2026-10-04): the Copy cell says how many rows the master holds from this copy - those the load
					// wrote AND those it found already up to date; a detail recorded before the lot has no "unchanged" (0)
					out.put(name, Long.valueOf(entityNode.path("written").asLong(0) + entityNode.path("unchanged").asLong(0)));
				}
			}
		}
		catch (final Exception ex) // best-effort: a corrupted/truncated detail must never break the grid
		{
			return new HashMap<String, Long>();
		}
		return out;
	}
}
