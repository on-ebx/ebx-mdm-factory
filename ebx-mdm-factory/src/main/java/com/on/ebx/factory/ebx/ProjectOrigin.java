package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.delivery.OriginRule;
import com.on.ebx.factory.delivery.TargetRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;

/**
 * The EBX side of {@link OriginRule} and {@link TargetRule}: reads {@code FactorySettings.instanceId}/
 * {@code role} (the DEFAULT row) and a project's {@code originRepository}, and refuses a delivered project
 * or, on a {@code TARGET} instance, a project creation. Called by every {@code suggest*} entry point right
 * after the project lookup, so the rule holds for the REST routes and for the wizard alike (delivery lot 1,
 * {@code docs/DELIVERY.md} sec 5; the {@code role} gate is lot 2, sec 6.2).
 */
public final class ProjectOrigin
{
	private ProjectOrigin()
	{
	}

	/** This instance's identifier, or null on a settings row written before the field existed and not yet stamped. */
	public static String instanceId(final Adaptation pFactory)
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		return settings == null ? null : settings.getString(FactoryPaths.Settings.INSTANCE_ID);
	}

	/** {@code DEVELOPMENT} (default) or {@code TARGET}; null on a settings row written before the field existed - {@link TargetRule#projectCreationAllowed} treats that the same as DEVELOPMENT. */
	public static String role(final Adaptation pFactory)
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		return settings == null ? null : settings.getString(FactoryPaths.Settings.ROLE);
	}

	public static boolean isDelivered(final Adaptation pFactory, final Adaptation pProject)
	{
		return OriginRule.isDelivered(pProject.getString(FactoryPaths.Project.ORIGIN_REPOSITORY), ProjectOrigin.instanceId(pFactory));
	}

	public static void refuseIfDelivered(final Adaptation pFactory, final Adaptation pProject) throws OperationException
	{
		if (ProjectOrigin.isDelivered(pFactory, pProject))
		{
			throw EngineRefusal.coded("conflict.refused", OriginRule.refusal(pProject.getString(FactoryPaths.Project.CODE), pProject.getString(FactoryPaths.Project.ORIGIN_REPOSITORY)));
		}
	}

	/** Called by {@code WizardData.saveProject} right before creating a new project row - never on an update of an existing one. */
	public static void refuseIfTarget(final Adaptation pFactory) throws OperationException
	{
		if (!TargetRule.projectCreationAllowed(ProjectOrigin.role(pFactory)))
		{
			throw EngineRefusal.coded("conflict.refused", TargetRule.refusal());
		}
	}
}
