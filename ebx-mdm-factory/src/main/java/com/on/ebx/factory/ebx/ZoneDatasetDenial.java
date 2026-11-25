package com.on.ebx.factory.ebx;

import java.util.regex.Pattern;

import com.onwbp.adaptation.AdaptationHome;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.onwbp.adaptation.Adaptation;

/**
 * 1.4.2 (X3-11): on a standard ON EBX 6.3.0-gp instance, a zone (landing, staging, master) the factory has just created with {@code Repository.createHome} and NO copied
 * permissions refuses {@code create_dataset} of the {@code data-model} server, even to an administrator: the server answers {@code tools/call: Tool invocation failed: } with
 * an EMPTY reason (the console holds {@code UowErrorOperationNotAllowed}). Measured 2026-11-12 on a clean instance; the same operation succeeds in {@code Reference} and in a
 * dataspace created by Data Services; with {@code -Debx.factory.zonePermissionsFrom=<a dataspace created by an administrator>} the landing passes. On the FastTrack development
 * instance the same sequence passes (cause not established).
 * <p>
 * 1.4.3: the default changed (the parent's permissions are copied when no setting is given, {@link ZonePermissions}), so a refusal now means the parent's own permissions, a
 * named dataspace, {@code none}, or a zone created before 1.4.3. This class makes the refusal readable and the relaunch possible: an empty tool failure is translated to a coded message that says what happened and what to do; and when the zone was created by THIS step and holds
 * nothing, it is removed again, because the setting applies to NEW zones only and a relaunch would otherwise meet the same zone and fail the same way.
 */
final class ZoneDatasetDenial
{
	/** Code written in front of the sentences. */
	static final String CODE = "MDMF-ZONE-1";

	private static final Pattern EMPTY = Pattern.compile("(?is)^\\s*(tools/call\\s*:\\s*)?(mcp\\s*:\\s*)?tool invocation failed\\s*:?\\s*$");

	private ZoneDatasetDenial()
	{
	}

	/** Pure: true for an answer with no reason - nothing, or only "Tool invocation failed:" (optionally behind "tools/call:"). */
	static boolean emptyToolFailure(final String pText)
	{
		return pText == null || pText.trim().isEmpty() || ZoneDatasetDenial.EMPTY.matcher(pText).matches();
	}

	/** Pure: the bundle code (suffix of {@code engine.}) for the three situations. */
	static String codeFor(final boolean pCreatedHere, final boolean pRemoved)
	{
		return !pCreatedHere ? "external.zoneDatasetDeniedExisting" : pRemoved ? "external.zoneDatasetDeniedRemoved" : "external.zoneDatasetDeniedKept";
	}

	/** Pure: what to do, true to the three settings (parent by default, a named dataspace, none). */
	static String advice(final String pSetting)
	{
		return "Permissions copied at creation: " + pSetting + ". By default (no -D" + FactoryAccess.PROPERTY_ZONE_PERMISSIONS + ") the zone copies its parent dataspace, so the refusal then comes from the permissions of that parent itself:"
			+ " give the administrator and the roles what they need on it, or name another dataspace with -D" + FactoryAccess.PROPERTY_ZONE_PERMISSIONS + "=<key>. With a named dataspace, check that dataspace's permissions."
			+ " With -D" + FactoryAccess.PROPERTY_ZONE_PERMISSIONS + "=none the zone is closed to everyone but its owner administrator: remove the setting or name a dataspace";
	}

	/** Pure: the English sentence (the bundle holds the same in both languages). {@code pSetting} is {@link ZonePermissions#label}. */
	static String english(final String pCode, final String pZone, final String pRemovalProblem, final String pSetting)
	{
		final String head = ZoneDatasetDenial.CODE + ": ON EBX refused to create the dataset of the zone " + pZone + " (the data-model server answered \"Tool invocation failed\" with no reason). ";
		final String advice = ZoneDatasetDenial.advice(pSetting);
		if ("external.zoneDatasetDeniedRemoved".equals(pCode))
		{
			return head + "The zone had just been created by the factory and has been removed (it held nothing of its own). " + advice + ", restart the server if you changed a setting, and run the step again. The setting applies to NEW zones only.";
		}
		if ("external.zoneDatasetDeniedKept".equals(pCode))
		{
			return head + "The zone had just been created by the factory and could not be removed (" + pRemovalProblem + "). Delete the zone in the ON EBX dataspaces screen if nothing else uses it. " + advice
				+ ", restart the server if you changed a setting, and run the step again; or give the permissions on the zone itself. The setting applies to NEW zones only.";
		}
		return head + "The zone already existed (possibly created by a version before 1.4.3, which copied no permissions by default), so the setting is not applied to it. If it is empty, delete it in the ON EBX dataspaces screen and run the step again, or give the permissions on the zone itself. "
			+ advice + ".";
	}

	/**
	 * Builds the refusal for an empty tool failure while creating the dataset of {@code pZoneKey}. When {@code pCreatedHere} (the factory created the zone in the very step
	 * that fails) and the zone holds nothing, the zone is deleted so that a relaunch creates it again, with the setting.
	 */
	/**
	 * A child dataspace starts with every dataset of its parent (measured 2026-11-12 on a standard instance: a zone created a second earlier
	 * listed the datasets of Reference, so "no dataset" was never true and the zone was never removed). The zone holds something OF ITS OWN
	 * only when a dataset is not one its parent has, when a snapshot other than the initial one exists, or when it has a child dataspace.
	 */
	static boolean hasOwnContent(final AdaptationHome pZone)
	{
		if (!pZone.getBranchChildren().isEmpty())
		{
			return true;
		}
		for (final AdaptationHome version : pZone.getVersionChildren())
		{
			if (!version.isInitialVersion() || !version.getBranchChildren().isEmpty())
			{
				return true;
			}
		}
		final AdaptationHome parent = pZone.getParentBranch();
		for (final Adaptation root : pZone.findAllRoots())
		{
			if (parent == null || parent.findAdaptationOrNull(root.getAdaptationName()) == null)
			{
				return true;
			}
		}
		return false;
	}

	static OperationException translate(final Session pSession, final Adaptation pFactory, final String pZoneKey, final boolean pCreatedHere)
	{
		boolean removed = false;
		String problem = "";
		if (pCreatedHere)
		{
			try
			{
				final Repository repository = pFactory.getHome().getRepository();
				final AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(pZoneKey));
				if (home == null)
				{
					removed = true;
				}
				else if (ZoneDatasetDenial.hasOwnContent(home))
				{
					problem = "the zone holds a dataset, a snapshot or a child dataspace of its own";
				}
				else
				{
					if (home.isOpen())
					{
						repository.closeHome(home, pSession);
					}
					repository.deleteHome(home, pSession);
					removed = true;
				}
			}
			catch (final OperationException | RuntimeException ex)
			{
				problem = ex.getMessage() == null || ex.getMessage().trim().isEmpty() ? ex.getClass().getSimpleName() : ex.getMessage();
			}
		}
		final String code = ZoneDatasetDenial.codeFor(pCreatedHere, removed);
		String setting;
		try
		{
			final Repository repository = pFactory.getHome().getRepository();
			setting = ZonePermissions.label(ZonePermissions.decide(repository, repository.getReferenceBranch()));
		}
		catch (final RuntimeException ex)
		{
			setting = "unknown (" + ex.getClass().getSimpleName() + ")";
		}
		return EngineRefusal.coded(code, ZoneDatasetDenial.english(code, pZoneKey, problem, setting), pZoneKey, FactoryAccess.PROPERTY_ZONE_PERMISSIONS, problem, setting);
	}
}
