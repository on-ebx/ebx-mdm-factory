package com.on.ebx.factory.ebx;

import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationReference;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.SchemaLocation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Automatic intake lot (docs/AUTOMATIC-INTAKE.md): creates the {@code MdmFactoryIntakeSignal} dataset (model {@link FactoryPaths#INTAKE_SIGNAL_MODEL_PATH}) as a
 * second ROOT dataset of a project's LANDING dataspace, next to its landing datasets - idempotent, the exact {@code doCreateRoot} pattern of
 * {@link DistributionOutboxInstaller} (a landing-table trigger can only write through its own ambient transaction, scoped to its own dataspace) - and pre-creates
 * one {@code Signal} row per source system, so the generated trigger's create branch stays a fallback and two concurrent first writers never race on it.
 * Never touches an existing dataset or row. <b>Not run live yet</b>: the model is new and needs the module restart that packages it.
 */
final class IntakeSignalInstaller
{
	private IntakeSignalInstaller()
	{
	}

	/** @return the signal dataset (existing or newly created) */
	static Adaptation ensure(final Session pSession, final AdaptationHome pLandingHome, final String pModuleName) throws OperationException
	{
		final Adaptation existing = pLandingHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.INTAKE_SIGNAL_DATASET));
		if (existing != null)
		{
			return existing;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pLandingHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final Adaptation created = pContext.doCreateRoot(SchemaLocation.forPathInModule(FactoryPaths.INTAKE_SIGNAL_MODEL_PATH, pModuleName),
					AdaptationReference.forPersistentName(FactoryPaths.INTAKE_SIGNAL_DATASET), Profile.ADMINISTRATOR);
				pContext.setInstanceLabel(created, UserMessage.createInfo("Automatic intake signal"));
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("external.signalDataset", "Could not create the " + FactoryPaths.INTAKE_SIGNAL_DATASET + " dataset in " + pLandingHome.getKey().getName() + ": "
				+ result.getExceptionFullMessage(Locale.ENGLISH), pLandingHome.getKey().getName());
		}
		final Adaptation created = pLandingHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.INTAKE_SIGNAL_DATASET));
		if (created == null)
		{
			throw EngineRefusal.coded("external.signalDataset", FactoryPaths.INTAKE_SIGNAL_DATASET + " dataset creation reported success but it cannot be found in " + pLandingHome.getKey().getName(), pLandingHome.getKey().getName());
		}
		return created;
	}

	/** Creates the {@code Signal} rows of the given source systems that are missing (no {@code lastRowAt}: nothing is waiting because of them). */
	static void ensureRows(final Session pSession, final Adaptation pSignalDataset, final List<String> pSourceSystems) throws OperationException
	{
		final AdaptationTable table = pSignalDataset.getTable(FactoryPaths.IntakeSignal.SIGNAL);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pSignalDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final String sourceSystem : pSourceSystems)
				{
					if (table.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { sourceSystem })) != null)
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
					value.setValue(sourceSystem, FactoryPaths.IntakeSignal.SOURCE_SYSTEM);
					value.setValue("intake installer", FactoryPaths.IntakeSignal.UPDATED_BY);
					pContext.doCreateOccurrence(value, table);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.signalRows", "Could not create the intake signal rows: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	/**
	 * The signal's {@code lastRowAt} of one source, or null (no dataset, no row, no value). A primary-key lookup: cheap, called by the engine every tick for every
	 * source of an ON project. Never throws.
	 */
	static Date lastRowAt(final Adaptation pSignalDataset, final String pSourceSystem)
	{
		try
		{
			final AdaptationTable table = pSignalDataset.getTable(FactoryPaths.IntakeSignal.SIGNAL);
			final Adaptation row = table.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { pSourceSystem }));
			if (row == null)
			{
				return null;
			}
			final Object value = row.get(FactoryPaths.IntakeSignal.LAST_ROW_AT);
			return value instanceof Date ? (Date) value : null;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}
}
