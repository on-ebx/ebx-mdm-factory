package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.project.MatchingConfigRows;
import com.on.ebx.factory.project.MatchingConfigRows.BusinessObject;
import com.on.ebx.factory.project.MatchingConfigRows.TableConfig;
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
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Lot M18 (2026-10-18): the EBX side of {@link MatchingConfigRows} - reads and writes the Match and Merge add-on's own configuration dataset
 * ({@code Bebx-addon-mame/ebx-addon-mame-configuration}: {@code /root/TableConfiguration}, {@code /root/BusinessObjectConfiguration}) with the Java API and the
 * caller's session, the same two writes that were done by hand through Data Services on 2026-10-18 (36 times {@code PUT FKBusinessObject = null}, 17 times {@code DELETE} of a
 * business-object row; both answered 204 / 200 as an administrator). It never deletes a {@code TableConfiguration} row: those go through {@code delete_matching_config}
 * (the add-on removes the hidden merge policy and the metadataset with it), or stay.
 */
final class MameConfigRows
{
	static final HomeKey HOME_KEY = HomeKey.forBranchName("ebx-addon-mame");
	static final String DATASET = "ebx-addon-mame-configuration";
	static final Path TABLE_CONFIGURATION = Path.parse("/root/TableConfiguration");
	static final Path BUSINESS_OBJECT_CONFIGURATION = Path.parse("/root/BusinessObjectConfiguration");
	private static final Path DATA_MODEL = Path.parse("./dataModel");
	private static final Path TABLE = Path.parse("./table");
	private static final Path MAIN_TABLE = Path.parse("./mainTable");
	private static final Path FK_BUSINESS_OBJECT = Path.parse("./FKBusinessObject");

	private MameConfigRows()
	{
	}

	/** The add-on's configuration dataset; null when the add-on (or its technical dataspace) is not on the instance. */
	static Adaptation dataset(final Repository pRepository)
	{
		final AdaptationHome home = pRepository.lookupHome(MameConfigRows.HOME_KEY);
		return home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(MameConfigRows.DATASET));
	}

	private static AdaptationTable tableOrNull(final Adaptation pDataset, final Path pPath)
	{
		try
		{
			return pDataset.getTable(pPath);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static String text(final Adaptation pRow, final Path pPath)
	{
		final Object value = pRow.get(pPath);
		return value == null ? "" : String.valueOf(value);
	}

	/** Every {@code BusinessObjectConfiguration} row; empty when the table is not there. */
	static List<BusinessObject> businessObjects(final Adaptation pDataset)
	{
		final List<BusinessObject> out = new ArrayList<BusinessObject>();
		final AdaptationTable table = MameConfigRows.tableOrNull(pDataset, MameConfigRows.BUSINESS_OBJECT_CONFIGURATION);
		if (table == null)
		{
			return out;
		}
		final RequestResult rows = table.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(new BusinessObject(row.getOccurrencePrimaryKey().format(), MameConfigRows.text(row, MameConfigRows.DATA_MODEL), MameConfigRows.text(row, MameConfigRows.MAIN_TABLE)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Every {@code TableConfiguration} row; empty when the table is not there. */
	static List<TableConfig> tableConfigs(final Adaptation pDataset)
	{
		final List<TableConfig> out = new ArrayList<TableConfig>();
		final AdaptationTable table = MameConfigRows.tableOrNull(pDataset, MameConfigRows.TABLE_CONFIGURATION);
		if (table == null)
		{
			return out;
		}
		final RequestResult rows = table.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(new TableConfig(row.getOccurrencePrimaryKey().format(), MameConfigRows.text(row, MameConfigRows.DATA_MODEL), MameConfigRows.text(row, MameConfigRows.TABLE),
					MameConfigRows.text(row, MameConfigRows.FK_BUSINESS_OBJECT)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** The business objects of {@code pPublication} with their linked table configurations; empty when the add-on has no dataset. */
	static List<MatchingConfigRows.BusinessObjectFacts> businessObjectsOf(final Repository pRepository, final String pPublication)
	{
		final Adaptation dataset = MameConfigRows.dataset(pRepository);
		if (dataset == null || pPublication == null || pPublication.isEmpty())
		{
			return new ArrayList<MatchingConfigRows.BusinessObjectFacts>();
		}
		return MatchingConfigRows.businessObjectsOf(pPublication, MameConfigRows.businessObjects(dataset), MameConfigRows.tableConfigs(dataset));
	}

	/** What the add-on's dataset still holds for {@code pPublication}, one sentence per row (empty = nothing, or no add-on dataset). */
	static List<String> remaining(final Repository pRepository, final String pPublication)
	{
		final Adaptation dataset = MameConfigRows.dataset(pRepository);
		if (dataset == null)
		{
			return new ArrayList<String>();
		}
		return MatchingConfigRows.remaining(pPublication, MameConfigRows.businessObjects(dataset), MameConfigRows.tableConfigs(dataset));
	}

	/** {business-object rows, table-configuration rows} the add-on's dataset still holds for {@code pPublication}; {0, 0} when nothing (or no add-on dataset). */
	static int[] remainingCounts(final Repository pRepository, final String pPublication)
	{
		final Adaptation dataset = MameConfigRows.dataset(pRepository);
		if (dataset == null)
		{
			return new int[] { 0, 0 };
		}
		final String urn = MatchingConfigRows.urnOf(pPublication);
		int businessObjects = 0;
		int tableConfigs = 0;
		for (final BusinessObject row : MameConfigRows.businessObjects(dataset))
		{
			businessObjects += urn.equals(row.dataModel) ? 1 : 0;
		}
		for (final TableConfig row : MameConfigRows.tableConfigs(dataset))
		{
			tableConfigs += urn.equals(row.dataModel) ? 1 : 0;
		}
		return new int[] { businessObjects, tableConfigs };
	}

	/**
	 * The documented unlink-then-delete of ONE business-object row: every {@code TableConfiguration} whose {@code FKBusinessObject} is {@code pBusinessObjectId} gets
	 * it set to null (a plain modify, the table configuration itself and its merge policy stay), then the business-object row is deleted, then both are read back.
	 * Each of the two writes is its own transaction, in that order (deleting the row first would be refused or leave dangling links).
	 *
	 * @return how many table configurations were unlinked; -1 when the business-object row did not exist (already gone)
	 * @throws OperationException a write failed or the read-back still finds a link or the row
	 */
	static int unlinkAndDelete(final Session pSession, final Adaptation pDataset, final String pBusinessObjectId) throws OperationException
	{
		final AdaptationTable boTable = MameConfigRows.tableOrNull(pDataset, MameConfigRows.BUSINESS_OBJECT_CONFIGURATION);
		final AdaptationTable tcTable = MameConfigRows.tableOrNull(pDataset, MameConfigRows.TABLE_CONFIGURATION);
		if (boTable == null)
		{
			return -1;
		}
		final PrimaryKey boKey = PrimaryKey.parseString(pBusinessObjectId);
		final Adaptation boRow = boTable.lookupAdaptationByPrimaryKey(boKey);
		if (boRow == null)
		{
			return -1;
		}
		final AdaptationHome home = pDataset.getHome();
		final String context = "Matching configuration of business object " + pBusinessObjectId + " (add-on dataset)";
		int unlinked = 0;
		if (tcTable != null)
		{
			unlinked = VolumeBatchOps.forEachBatched(pSession, home, tcTable, "./FKBusinessObject=" + CatalogReader.quote(pBusinessObjectId), context + ": unlinking a table configuration failed",
				new VolumeBatchOps.RowOp()
				{
					@Override
					public void apply(final ProcedureContext pContext, final Adaptation pRow) throws Exception
					{
						final ValueContextForUpdate value = pContext.getContext(pRow.getAdaptationName());
						value.setValue(null, MameConfigRows.FK_BUSINESS_OBJECT);
						pContext.doModifyContent(pRow, value);
					}
				});
			if (MameConfigRows.countEq(tcTable, "./FKBusinessObject=" + CatalogReader.quote(pBusinessObjectId)) > 0)
			{
				throw EngineRefusal.coded("unexpected.batchWrite", context + ": table configurations are still linked to it after the unlink; the business-object row was NOT deleted.");
			}
		}
		final ProcedureResult deletion = ProgrammaticService.createForSession(pSession, home).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = boTable.lookupAdaptationByPrimaryKey(boKey);
				if (row != null)
				{
					pContext.doDelete(row.getAdaptationName(), false);
				}
			}
		});
		if (deletion.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.batchWrite", context + ": deleting the business-object row failed: " + deletion.getExceptionFullMessage(Locale.ENGLISH));
		}
		if (boTable.lookupAdaptationByPrimaryKey(boKey) != null)
		{
			throw EngineRefusal.coded("unexpected.batchWrite", context + ": the business-object row is still there after its deletion.");
		}
		LoggingCategory.getKernel().info("[mdm-factory] add-on dataset: business object configuration " + pBusinessObjectId + " deleted after unlinking " + unlinked + " table configuration(s)");
		return unlinked;
	}

	private static int countEq(final AdaptationTable pTable, final String pPredicate)
	{
		final RequestResult rows = pTable.createRequestResult(pPredicate);
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}
}
