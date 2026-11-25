package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationReference;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.SchemaLocation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;

/**
 * Distribution round 3: creates the {@code DistributionOutbox} dataset (model
 * {@link FactoryPaths#DISTRIBUTION_OUTBOX_MODEL_PATH}) as a second ROOT dataset of a project's master
 * dataspace, next to its {@code Master} dataset - idempotent, following exactly the same
 * {@code ProgrammaticService}/{@code doCreateRoot} pattern {@link FactoryInstaller#install} already uses to
 * create the {@code MdmFactory} dataset itself (CLAUDE.md sec 12: {@code doCreateRoot} creates a new ROOT
 * adaptation directly in an existing home - a dataspace can hold several such root adaptations, i.e.
 * several datasets, which is exactly what this needs: {@code Master} and {@code DistributionOutbox} side by
 * side in the SAME dataspace, reachable by a master-table trigger's own ambient {@code _ebx.transaction} -
 * see {@code DistributionTriggerBody}'s own Javadoc for why the outbox could not stay in MdmFactory).
 * <p>
 * Called by {@link DistributionTriggerInstaller#install} before generating any trigger body, so the dataset
 * always exists by the time a trigger that references it is authored. Never touches an existing dataset.
 */
final class DistributionOutboxInstaller
{
	private DistributionOutboxInstaller()
	{
	}

	/** @return the outbox dataset (existing or newly created) */
	static Adaptation ensure(final Session pSession, final AdaptationHome pMasterHome, final String pModuleName) throws OperationException
	{
		final Adaptation existing = pMasterHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.DISTRIBUTION_OUTBOX_DATASET));
		if (existing != null)
		{
			return existing;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pMasterHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final Adaptation created = pContext.doCreateRoot(SchemaLocation.forPathInModule(FactoryPaths.DISTRIBUTION_OUTBOX_MODEL_PATH, pModuleName),
					AdaptationReference.forPersistentName(FactoryPaths.DISTRIBUTION_OUTBOX_DATASET), Profile.ADMINISTRATOR);
				pContext.setInstanceLabel(created, UserMessage.createInfo("Distribution outbox"));
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("external.outboxDataset", "Could not create the DistributionOutbox dataset in " + pMasterHome.getKey().getName() + ": "
				+ result.getExceptionFullMessage(java.util.Locale.ENGLISH), pMasterHome.getKey().getName());
		}
		final Adaptation created = pMasterHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.DISTRIBUTION_OUTBOX_DATASET));
		if (created == null)
		{
			throw EngineRefusal.coded("external.outboxDataset", "DistributionOutbox dataset creation reported success but the dataset cannot be found in " + pMasterHome.getKey().getName(), pMasterHome.getKey().getName());
		}
		return created;
	}
}
