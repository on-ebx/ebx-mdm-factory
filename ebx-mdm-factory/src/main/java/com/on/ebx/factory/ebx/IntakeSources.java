package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.intake.IntakeSettings;
import com.on.ebx.factory.landing.LandingNames;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.service.OperationException;

/**
 * Automatic intake lot: what the intake classes read about a project's applications from the factory's own tables - one place, plain values. Reads only; a
 * stored empty value is the default ({@link IntakeSettings#of}).
 */
final class IntakeSources
{
	private IntakeSources()
	{
	}

	/** One application of a project. */
	static final class Info
	{
		final String id;
		final String sourceSystem;
		/** The landing dataset name (= the source system; {@code ProjectSource.landingDataset} when stored). */
		final String landingDataset;
		/** The landing data model's name in the data-model server (stored {@code landingModel}, else {@link LandingNames#modelName}). */
		final String modelName;
		/** True when a Connectors flow reads it (a ticked landing table has an inbound mapping). */
		final boolean connectors;
		/** The ticked landing tables' names ({@code /root/<name>}). */
		final List<String> tableNames;
		final IntakeSettings settings;

		Info(final String pId, final String pSourceSystem, final String pLandingDataset, final String pModelName, final boolean pConnectors, final List<String> pTableNames,
			final IntakeSettings pSettings)
		{
			this.id = pId;
			this.sourceSystem = pSourceSystem;
			this.landingDataset = pLandingDataset;
			this.modelName = pModelName;
			this.connectors = pConnectors;
			this.tableNames = pTableNames;
			this.settings = pSettings;
		}
	}

	static List<Info> read(final Adaptation pFactory, final Adaptation pProject)
	{
		final String projectCode = pProject.getString(FactoryPaths.Project.CODE);
		final String projectId = pProject.getOccurrencePrimaryKey().format();
		final List<Info> out = new ArrayList<Info>();
		final RequestResult sources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult("./project=" + CatalogReader.quote(projectId));
		try
		{
			for (Adaptation source = sources.nextAdaptation(); source != null; source = sources.nextAdaptation())
			{
				final String sourceSystem = source.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
				if (sourceSystem == null || sourceSystem.trim().isEmpty())
				{
					continue;
				}
				final String id = source.getOccurrencePrimaryKey().format();
				final String storedDataset = source.getString(FactoryPaths.ProjectSource.LANDING_DATASET);
				final String storedModel = source.getString(FactoryPaths.ProjectSource.LANDING_MODEL);
				final List<String> tables = new ArrayList<String>();
				boolean connectors = false;
				final RequestResult tableRows = pFactory.getTable(FactoryPaths.LANDING_TABLE).createRequestResult("./projectSource=" + CatalogReader.quote(id));
				try
				{
					for (Adaptation table = tableRows.nextAdaptation(); table != null; table = tableRows.nextAdaptation())
					{
						if (Boolean.FALSE.equals(table.get(FactoryPaths.LandingTable.SELECTED)))
						{
							continue;
						}
						final String name = table.getString(FactoryPaths.LandingTable.TABLE_NAME);
						if (name != null && !name.trim().isEmpty())
						{
							tables.add(name.trim());
						}
						final String mapping = table.getString(FactoryPaths.LandingTable.INBOUND_MAPPING);
						connectors = connectors || mapping != null && !mapping.trim().isEmpty();
					}
				}
				finally
				{
					tableRows.close();
				}
				out.add(new Info(id, sourceSystem.trim(), storedDataset == null || storedDataset.trim().isEmpty() ? sourceSystem.trim() : storedDataset.trim(),
					storedModel == null || storedModel.trim().isEmpty() ? LandingNames.modelName(projectCode, sourceSystem.trim()) : storedModel.trim(), connectors, tables,
					IntakeSources.settings(source)));
			}
		}
		finally
		{
			sources.close();
		}
		return out;
	}

	static IntakeSettings settings(final Adaptation pSource)
	{
		return IntakeSettings.of(IntakeSources.intOrNull(pSource, FactoryPaths.ProjectSource.INTAKE_QUIET_SECONDS),
			IntakeSources.intOrNull(pSource, FactoryPaths.ProjectSource.INTAKE_MAX_WAIT_MINUTES), IntakeSources.intOrNull(pSource, FactoryPaths.ProjectSource.INTAKE_MIN_ROWS),
			(Boolean) IntakeSources.valueOrNull(pSource, FactoryPaths.ProjectSource.INTAKE_BATCH_SIGNAL));
	}

	/**
	 * A guarded read: a model written before this lot has no such field and {@code Adaptation.get} throws on an undeclared path (CLAUDE.md sec 9) - which reads as "empty" here.
	 */
	static Object valueOrNull(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		if (pRow.getSchemaNode().getNode(pPath) == null)
		{
			return null;
		}
		return pRow.get(pPath);
	}

	static Integer intOrNull(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final Object value = IntakeSources.valueOrNull(pRow, pPath);
		return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
	}

	/** The project's landing dataspace key: the stored one, else the computed {@link LandingNames#dataspaceKey}. */
	static String landingDataspaceKey(final Adaptation pFactory, final Adaptation pProject) throws OperationException
	{
		final String stored = pProject.getString(FactoryPaths.Project.LANDING_DATASPACE);
		if (stored != null && !stored.trim().isEmpty())
		{
			return stored.trim();
		}
		return LandingNames.dataspaceKey(pProject.getString(FactoryPaths.Project.CODE), LandingGenerator.landingSuffix(pFactory));
	}

	/** The landing dataspace, or null when it does not exist. */
	static AdaptationHome landingHome(final Adaptation pFactory, final Adaptation pProject) throws OperationException
	{
		return pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(IntakeSources.landingDataspaceKey(pFactory, pProject)));
	}

	/** One application's landing dataset, or null. */
	static Adaptation landingDataset(final AdaptationHome pLandingHome, final Info pSource)
	{
		return pLandingHome == null ? null : pLandingHome.findAdaptationOrNull(AdaptationName.forName(pSource.landingDataset));
	}
}
