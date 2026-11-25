package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.LandingKeyChangeRules;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * "Change the key..." - item 4 of the composite-key lot (2026-09-24): a table whose landing is already
 * GENERATED/LOADED cannot have its key changed through {@code WizardData#setTableKey} (that refuses once
 * the table is out of {@code PROPOSED} - see its own Javadoc), because the table's landing rows already
 * carry {@code _sourceId} values computed from the OLD key, and the mapping's generated flow would keep
 * computing them from the old key too. This class is the one legitimate way around that: it writes the new
 * key, throws away the now-meaningless landing rows (they are a raw copy of the source, not authored data -
 * a reload recreates them), and regenerates the table's flow so the NEXT load computes {@code _sourceId}
 * from the new key. It does <b>not</b> reload itself - the caller (lot LANDING's "Change the key..." button)
 * decides when, so a person can review the new key's effect before spending a real load run on it.
 * <p>
 * Sequence, all as the calling session's own rights except the flow regeneration (which goes through the
 * factory's agent account, like every {@code ebx-connectors} write - see {@link LandingFlows}):
 * <ol>
 * <li>validate the requested columns ({@link LandingKeyChangeRules}) against the table's real columns;</li>
 * <li>write the new key ({@code WizardData#writeTableKey}: origin {@code USER}, confidence 100, rationale
 * "changed after load");</li>
 * <li>delete every record of the landing table itself (found in the project's landing dataspace/dataset,
 * the same way {@code QualitySuggester#landingTableRef}/{@code StagingLoader} already resolve one);</li>
 * <li>regenerate the table's flow ({@link LandingFlows#generateOne}).</li>
 * </ol>
 * Refuses (a {@link Result} with {@code ok=false} and a {@link Result#message}, no write at all) when: the
 * table is not found, the table does not belong to {@code pProjectCode}, the table's status is neither
 * {@code GENERATED} nor {@code LOADED} (a {@code PROPOSED} table should go through the ordinary key picker
 * instead, which is exactly what it is for; any other status - {@code FAILED}, {@code DRIFTED} - is not
 * supported here, deliberately: those need attention of their own before a key change makes sense), or the
 * column list is empty or names a column the table does not have.
 */
public final class LandingKeyChange
{
	/** The rationale stamped on the table's key when this class writes it - see {@code LandingTable.keyRationale}. */
	private static final String RATIONALE = com.on.ebx.factory.landing.KeyTexts.changedAfterLoad().stored();

	private LandingKeyChange()
	{
	}

	/** {@code ok} true with {@link #rowsDeleted} and no {@link #message}; {@code ok} false with a {@link #message} naming exactly what was refused, and {@link #rowsDeleted} always 0 (a refusal after the key was written - a failed batch of the row deletion - says so in its message). */
	public static final class Result
	{
		public final boolean ok;
		public final String message;
		public final int rowsDeleted;

		private Result(final boolean pOk, final String pMessage, final int pRowsDeleted)
		{
			this.ok = pOk;
			this.message = pMessage;
			this.rowsDeleted = pRowsDeleted;
		}

		private static Result refuse(final String pMessage)
		{
			return new Result(false, pMessage, 0);
		}

		private static Result ok(final int pRowsDeleted)
		{
			return new Result(true, null, pRowsDeleted);
		}
	}

	/** What {@link #check} found: everything {@link #change} needs once the request is accepted, or a refusal sentence. */
	private static final class Checked
	{
		String refusal;
		Adaptation table;
		Adaptation projectSource;
		Adaptation project;
		String projectSourceId;
		LandingKeyChangeRules.Validation validation;

		static Checked refused(final String pMessage)
		{
			final Checked out = new Checked();
			out.refusal = pMessage;
			return out;
		}
	}

	/**
	 * Audit F-35 (wave 5): the refusals of {@link #change} without writing anything - read by the wizard BEFORE it queues {@link LandingKeyChangeRun}, so a
	 * request the rules refuse is shown at once instead of as a failed job.
	 *
	 * @return null when {@link #change} would accept the request, else the sentence it would refuse with
	 */
	public static String refusal(final Adaptation pFactory, final String pProjectCode, final String pLandingTableId, final List<String> pColumns)
	{
		return LandingKeyChange.check(pFactory, pProjectCode, pLandingTableId, pColumns).refusal;
	}

	private static Checked check(final Adaptation pFactory, final String pProjectCode, final String pLandingTableId, final List<String> pColumns)
	{
		final Adaptation table = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingTableId));
		if (table == null)
		{
			return Checked.refused("Landing table " + pLandingTableId + " not found.");
		}
		final String projectSourceId = table.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
		final Adaptation projectSource = projectSourceId == null || projectSourceId.trim().isEmpty() ? null
			: pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectSourceId));
		if (projectSource == null)
		{
			return Checked.refused("Landing table " + pLandingTableId + " names no project source.");
		}
		final Adaptation project = ProposalReviews.findProjectByCode(pFactory, pProjectCode);
		if (project == null)
		{
			return Checked.refused("Project " + pProjectCode + " not found.");
		}
		if (!project.getOccurrencePrimaryKey().format().equals(projectSource.getString(FactoryPaths.ProjectSource.PROJECT)))
		{
			return Checked.refused("Landing table " + pLandingTableId + " does not belong to project " + pProjectCode + ".");
		}
		final String status = table.getString(FactoryPaths.LandingTable.STATUS);
		if (!"GENERATED".equals(status) && !"LOADED".equals(status))
		{
			return Checked.refused("This table's landing is " + status + ": use the key picker (Update key) instead of Change the key here.");
		}

		final List<String> realColumns = new ArrayList<String>();
		final RequestResult columnRows = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(pLandingTableId));
		try
		{
			for (Adaptation column = columnRows.nextAdaptation(); column != null; column = columnRows.nextAdaptation())
			{
				realColumns.add(column.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN));
			}
		}
		finally
		{
			columnRows.close();
		}
		final LandingKeyChangeRules.Validation validation = LandingKeyChangeRules.validate(realColumns, pColumns);
		if (!validation.ok)
		{
			return Checked.refused(validation.message);
		}
		final Checked out = new Checked();
		out.table = table;
		out.projectSource = projectSource;
		out.project = project;
		out.projectSourceId = projectSourceId;
		out.validation = validation;
		return out;
	}

	public static Result change(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pLandingTableId, final List<String> pColumns)
		throws OperationException
	{
		return LandingKeyChange.change(pSession, pFactory, pProjectCode, pLandingTableId, pColumns, null);
	}

	/** Same; {@code pStop} (null = never) is asked before each batch of the row deletion - a queued job passes its handle's {@code cancelled()}, so a Stop ends the drain (the new key is saved, the table keeps its status, the same button retries). */
	public static Result change(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pLandingTableId, final List<String> pColumns,
		final java.util.function.BooleanSupplier pStop) throws OperationException
	{
		final Checked checked = LandingKeyChange.check(pFactory, pProjectCode, pLandingTableId, pColumns);
		if (checked.refusal != null)
		{
			return Result.refuse(checked.refusal);
		}
		final Adaptation project = checked.project;
		final Adaptation projectSource = checked.projectSource;
		final Adaptation table = checked.table;
		final String projectSourceId = checked.projectSourceId;
		final LandingKeyChangeRules.Validation validation = checked.validation;

		// 1. the new key
		final StringBuilder columnsCsv = new StringBuilder();
		for (final String column : validation.columns)
		{
			if (columnsCsv.length() > 0)
			{
				columnsCsv.append(',');
			}
			columnsCsv.append(column);
		}
		WizardData.writeTableKey(pSession, pFactory, pLandingTableId, columnsCsv.toString(), "USER", 100, LandingKeyChange.RATIONALE);

		// 2. the now-stale landing rows
		final String landingDataspace = project.getString(FactoryPaths.Project.LANDING_DATASPACE);
		final String landingDatasetName = projectSource.getString(FactoryPaths.ProjectSource.LANDING_DATASET);
		final String tableName = table.getString(FactoryPaths.LandingTable.TABLE_NAME);
		final int rowsDeleted;
		try
		{
			rowsDeleted = LandingKeyChange.deleteLandingRows(pSession, pFactory, landingDataspace, landingDatasetName, tableName, pStop);
		}
		catch (final OperationException ex)
		{
			// shown as a hint like every other refusal; the flow is NOT regenerated, the table keeps its status, so the same button retries
			return Result.refuse(ex.getMessage() + " The new key is saved; some rows are still in the landing table. Retry Change the key to finish deleting them.");
		}

		// 3. the flow, so the NEXT load computes _sourceId from the new key
		LandingFlows.generateOne(pSession, pFactory, projectSourceId, pLandingTableId);

		return Result.ok(rowsDeleted);
	}

	/** @return how many rows were deleted, in batches (audit F-35); 0 (not an error) when the landing dataset or table cannot be resolved - see the class Javadoc for why a stale row is not itself a blocker. */
	private static int deleteLandingRows(final Session pSession, final Adaptation pFactory, final String pLandingDataspace, final String pLandingDatasetName,
		final String pTableName, final java.util.function.BooleanSupplier pStop) throws OperationException
	{
		if (pLandingDataspace == null || pLandingDataspace.trim().isEmpty() || pLandingDatasetName == null || pLandingDatasetName.trim().isEmpty())
		{
			return 0;
		}
		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(pLandingDataspace));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pLandingDatasetName));
		if (dataset == null)
		{
			return 0;
		}
		final AdaptationTable landingTable;
		try
		{
			landingTable = dataset.getTable(Path.parse("/root/" + pTableName));
		}
		catch (final RuntimeException ex)
		{
			return 0;
		}
		// audit F-35: batches of VolumeBatchOps.BATCH_SIZE, keys streamed - never every row in a list and one transaction. A failed batch is
		// thrown (with the count already deleted) instead of the old silent 0: the key is already written, so the rows left behind would
		// be read with the new key's _sourceId rule; the table is still GENERATED/LOADED, so "Change the key..." can simply be retried.
		return VolumeBatchOps.deleteAllInBatches(pSession, dataset.getHome(), landingTable, "Deleting the rows of landing table " + pTableName, pStop);
	}
}
