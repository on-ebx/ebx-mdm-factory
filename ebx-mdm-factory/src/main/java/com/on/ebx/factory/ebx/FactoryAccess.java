package com.on.ebx.factory.ebx;

import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.core.FactoryRights;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.UserReference;
import com.orchestranetworks.service.directory.DirectoryHandler;
import com.orchestranetworks.ui.selection.EntitySelection;
import com.orchestranetworks.userservice.permission.ServicePermissionRule;
import com.orchestranetworks.userservice.permission.ServicePermissionRuleContext;
import com.orchestranetworks.userservice.permission.UserServicePermission;

/**
 * Who may do what in the MDM Factory (audit F-02): the one place every entry point asks - the user services' permission
 * rules, each wizard and review action, each REST route, each background job when it is queued, the workflow gate and the
 * automatic intake. The decisions themselves (which entry point asks which right) are in {@link FactoryRights}; this
 * class resolves the right a session or a user holds. Same pattern as ebx-connectors' {@code ConnectorsAccess}.
 * <p>
 * Nothing is granted by default: a person who is neither an EBX administrator nor a member of a role with a Factory right
 * is refused everything (deny by default). An administrator gives rights in the table "Access rights" of the MdmFactory
 * dataset ({@link FactoryAccessRightTable}): one row maps a role that already exists in the directory (or "Everyone") to
 * VIEW, STEWARD or DESIGN. Without any row, three role names work out of the box: {@value #DEFAULT_VIEWER},
 * {@value #DEFAULT_STEWARD} and {@value #DEFAULT_DESIGNER} ({@code -Debx.factory.role.viewer|steward|designer=<role>}
 * renames them). A session receives the widest right any of its roles brings; {@link FactoryRight#ADMINISTRATOR} is never
 * given by a role.
 * <p>
 * The EBX administrator and the system user (a workflow script task, the job dispatcher, the intake engine, the module's
 * installers) hold every right: what the system user runs was queued or launched by somebody whose right was checked then.
 */
public final class FactoryAccess
{
	public static final String DEFAULT_VIEWER = "FactoryViewer";
	public static final String DEFAULT_STEWARD = "FactorySteward";
	public static final String DEFAULT_DESIGNER = "FactoryDesigner";
	public static final String PROPERTY_VIEWER = "ebx.factory.role.viewer";
	public static final String PROPERTY_STEWARD = "ebx.factory.role.steward";
	public static final String PROPERTY_DESIGNER = "ebx.factory.role.designer";

	/** Where the rights given by an administrator come from: role of the directory (or "Everyone") -> right. */
	public interface Grants
	{
		Map<Role, FactoryRight> byRole();
	}

	private static volatile Grants grants = new FactoryAccessRightTable();

	private FactoryAccess()
	{
	}

	/** Replaces the source of the administrator's rights (the table of the MdmFactory dataset by default); for tests. */
	static void use(final Grants pGrants)
	{
		FactoryAccess.grants = pGrants;
	}

	// ------------------------------------------------------------------------------------------------ a session

	/** The widest right of this session, null when it has none. The system user and the EBX administrator: ADMINISTRATOR. */
	public static FactoryRight granted(final Session pSession)
	{
		if (pSession == null)
		{
			return null;
		}
		try
		{
			if (pSession.isSystemUser())
			{
				return FactoryRight.ADMINISTRATOR;
			}
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
		return FactoryAccess.grantedInPerson(pSession);
	}

	/**
	 * The right of the PERSON behind a session, without the system-user shortcut: what the language-model flags read (a system
	 * session - the intake engine - never spent tokens before F-02, where it asked {@code isUserInRole(ADMINISTRATOR)}).
	 */
	public static FactoryRight grantedInPerson(final Session pSession)
	{
		if (pSession == null)
		{
			return null;
		}
		try
		{
			if (pSession.isUserInRole(Profile.ADMINISTRATOR))
			{
				return FactoryRight.ADMINISTRATOR;
			}
			return FactoryAccess.widest(new Holds()
			{
				@Override
				public boolean holds(final Role pRole)
				{
					return pSession.isUserInRole(pRole);
				}
			});
		}
		catch (final RuntimeException ex)
		{
			// a directory or a table that cannot answer grants nothing
			return null;
		}
	}

	/** True when this session may use an entry point that asks {@code pNeeded}; no session, no right (not even AUTHENTICATED). */
	public static boolean has(final Session pSession, final FactoryRight pNeeded)
	{
		return pSession != null && FactoryRight.allows(FactoryAccess.granted(pSession), pNeeded);
	}

	/** Same as {@link #has} without the system-user shortcut (see {@link #grantedInPerson}). */
	public static boolean hasInPerson(final Session pSession, final FactoryRight pNeeded)
	{
		return pSession != null && FactoryRight.allows(FactoryAccess.grantedInPerson(pSession), pNeeded);
	}

	/**
	 * True when this session may use the entry point {@code pEntry} (a wizard action or a route key of {@link FactoryRights})
	 * that asks {@code pNeeded}: the right itself, or - for the few entries {@link FactoryRights#pipelineOperatorMay} lists - the
	 * pipeline launcher role of the factory settings.
	 */
	public static boolean allows(final Session pSession, final Adaptation pFactory, final String pEntry, final FactoryRight pNeeded)
	{
		if (FactoryAccess.has(pSession, pNeeded))
		{
			return true;
		}
		return FactoryRights.pipelineOperatorMay(pEntry) && FactoryAccess.mayOperatePipeline(pSession, pFactory);
	}

	/** Throws the refusal when the session does not hold {@code pNeeded}. */
	public static void require(final Session pSession, final FactoryRight pNeeded, final String pWhat) throws OperationException
	{
		if (!FactoryAccess.has(pSession, pNeeded))
		{
			throw EngineRefusal.accessDenied(pWhat, pNeeded); // audit F-39: localized in the wizard, same English text elsewhere
		}
	}

	// ------------------------------------------------------------------------------------ a user, without a session

	/** The widest right of a user of the directory (the creator of a workflow instance, the person who switched the intake on); null when none. */
	public static FactoryRight grantedTo(final Repository pRepository, final UserReference pUser)
	{
		if (pUser == null || pRepository == null)
		{
			return null;
		}
		try
		{
			final DirectoryHandler directory = DirectoryHandler.getInstance(pRepository);
			if (directory.isUserInRole(pUser, Profile.ADMINISTRATOR))
			{
				return FactoryRight.ADMINISTRATOR;
			}
			return FactoryAccess.widest(new Holds()
			{
				@Override
				public boolean holds(final Role pRole)
				{
					return directory.isUserInRole(pUser, pRole);
				}
			});
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * F2-03: the one decision "does a launcher holding {@code pGranted} may run something that asks {@code pNeeded}", for a user who has no session (the
	 * requester recorded on a queued job). {@code pPipelineOperator} says the entry also admits the pipeline launcher role and {@code pOperatesPipeline}
	 * whether this user holds it. Pure: {@link FactoryJobQueue#mayQueue} (session) and {@link FactoryJobQueue#launcherMayRun} (recorded user) share it.
	 */
	static boolean mayRun(final FactoryRight pGranted, final FactoryRight pNeeded, final boolean pPipelineOperator, final boolean pOperatesPipeline)
	{
		return FactoryRight.allows(pGranted, pNeeded) || pPipelineOperator && pOperatesPipeline;
	}

	// ------------------------------------------------------------------------------ the pipeline launcher role

	/**
	 * The role of the factory setting {@code pipelineLauncherRole}, null when the setting is empty or not a valid role name.
	 * Before F-02 an empty setting meant "administrators only"; it now means "DESIGN holders only" (administrators included).
	 */
	public static Role pipelineLauncherRole(final Adaptation pFactory)
	{
		if (pFactory == null)
		{
			return null;
		}
		try
		{
			final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
			final String name = settings == null ? null : settings.getString(FactoryPaths.Settings.PIPELINE_LAUNCHER_ROLE);
			return name == null || name.trim().isEmpty() ? null : Profile.forSpecificRole(name.trim());
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** May launch the factory's workflows and operate the automatic intake: the system user, a DESIGN holder, or a member of the pipeline launcher role. */
	public static boolean mayOperatePipeline(final Session pSession, final Adaptation pFactory)
	{
		if (pSession == null)
		{
			return false;
		}
		if (FactoryAccess.has(pSession, FactoryRight.DESIGN))
		{
			return true;
		}
		final Role role = FactoryAccess.pipelineLauncherRole(pFactory);
		try
		{
			return role != null && pSession.isUserInRole(role);
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	/** {@link #mayOperatePipeline} for a user of the directory (a workflow's recorded creator, the person who switched the intake on). */
	public static boolean userMayOperatePipeline(final Repository pRepository, final UserReference pUser, final Adaptation pFactory)
	{
		if (FactoryRight.allows(FactoryAccess.grantedTo(pRepository, pUser), FactoryRight.DESIGN))
		{
			return true;
		}
		final Role role = FactoryAccess.pipelineLauncherRole(pFactory);
		try
		{
			return role != null && pUser != null && DirectoryHandler.getInstance(pRepository).isUserInRole(pUser, role);
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	// ------------------------------------------------------------------------------------------- generated zones

	/** Key of a dataspace whose permissions every new zone copies; unset = the parent's (since 1.4.3); {@code none} = no copy. See {@link ZonePermissions}. */
	public static final String PROPERTY_ZONE_PERMISSIONS = "ebx.factory.zonePermissionsFrom";

	/**
	 * The generated dataspaces are created owned by the administrator (never changed: the administrator is never locked out). Since 1.4.3 (X3-11) the new
	 * dataspace also copies the permissions of its PARENT dataspace {@code pParent} ({@code HomeCreationSpec.setHomeToCopyPermissionsFrom}, public API) when
	 * {@code -Debx.factory.zonePermissionsFrom} is unset; a dataspace key there copies that dataspace instead; {@code none} copies nothing (see {@link ZonePermissions}).
	 * {@code pParent} must be the dataspace the caller gives to {@code spec.setParent}. Returns the key copied from, null when nothing was copied (explicit {@code none},
	 * or a key naming no dataspace: one WARN line). Existing zones are not touched.
	 */
	public static String copyZonePermissions(final com.orchestranetworks.instance.HomeCreationSpec pSpec, final Repository pRepository, final com.onwbp.adaptation.AdaptationHome pParent)
	{
		if (pSpec == null || pRepository == null || pParent == null)
		{
			return null;
		}
		final ZonePermissions.Decision decision = ZonePermissions.decide(pRepository, pParent);
		switch (decision.mode)
		{
		case PARENT:
			pSpec.setHomeToCopyPermissionsFrom(pParent);
			return decision.from;
		case NAMED:
			pSpec.setHomeToCopyPermissionsFrom(pRepository.lookupHome(com.orchestranetworks.instance.HomeKey.forBranchName(decision.from)));
			return decision.from;
		case NAMED_MISSING:
			com.orchestranetworks.service.LoggingCategory.getKernel().warn("[mdm-factory] -D" + FactoryAccess.PROPERTY_ZONE_PERMISSIONS + "=" + decision.from
				+ " names no dataspace: the new zone is created without copied permissions.");
			return null;
		default:
			return null;
		}
	}

	// ------------------------------------------------------------------------------------------------ the roles

	private interface Holds
	{
		boolean holds(Role pRole);
	}

	/** The widest grantable right the default roles and the table's rows give to whoever {@code pHolds} says is in a role. */
	private static FactoryRight widest(final Holds pHolds)
	{
		FactoryRight widest = FactoryAccess.inRole(pHolds, FactoryAccess.designerRole()) ? FactoryRight.DESIGN
			: FactoryAccess.inRole(pHolds, FactoryAccess.stewardRole()) ? FactoryRight.STEWARD
			: FactoryAccess.inRole(pHolds, FactoryAccess.viewerRole()) ? FactoryRight.VIEW : null;
		if (widest == FactoryRight.DESIGN)
		{
			return widest; // nothing a row gives is wider
		}
		final Grants source = FactoryAccess.grants;
		final Map<Role, FactoryRight> given = source == null ? null : source.byRole();
		if (given != null)
		{
			for (final Map.Entry<Role, FactoryRight> one : given.entrySet())
			{
				final FactoryRight right = one.getValue();
				if (right != null && right.grantable() && (widest == null || !widest.covers(right)) && pHolds.holds(one.getKey()))
				{
					widest = right;
				}
			}
		}
		return widest;
	}

	private static boolean inRole(final Holds pHolds, final String pRoleName)
	{
		if (pRoleName == null || pRoleName.isEmpty())
		{
			return false;
		}
		try
		{
			return pHolds.holds(Profile.forSpecificRole(pRoleName));
		}
		catch (final RuntimeException ex)
		{
			// a role name the directory refuses is a role nobody holds
			return false;
		}
	}

	public static String viewerRole()
	{
		return FactoryAccess.roleName(FactoryAccess.PROPERTY_VIEWER, FactoryAccess.DEFAULT_VIEWER);
	}

	public static String stewardRole()
	{
		return FactoryAccess.roleName(FactoryAccess.PROPERTY_STEWARD, FactoryAccess.DEFAULT_STEWARD);
	}

	public static String designerRole()
	{
		return FactoryAccess.roleName(FactoryAccess.PROPERTY_DESIGNER, FactoryAccess.DEFAULT_DESIGNER);
	}

	static String roleName(final String pProperty, final String pDefault)
	{
		final String name = System.getProperty(pProperty);
		return name == null || name.trim().isEmpty() ? pDefault : name.trim();
	}

	/** The default role that grants exactly this right (a wider role grants it too); "the EBX administrator" for ADMINISTRATOR. */
	public static String roleFor(final FactoryRight pRight)
	{
		if (pRight == FactoryRight.DESIGN)
		{
			return FactoryAccess.designerRole();
		}
		if (pRight == FactoryRight.STEWARD)
		{
			return FactoryAccess.stewardRole();
		}
		if (pRight == FactoryRight.VIEW || pRight == FactoryRight.AUTHENTICATED)
		{
			return FactoryAccess.viewerRole();
		}
		return null;
	}

	/** What a refused caller reads (English, for REST answers and logs): the action, and the narrowest role that would have allowed it. */
	public static String refusal(final String pWhat, final FactoryRight pNeeded)
	{
		if (pNeeded == FactoryRight.ADMINISTRATOR || pNeeded == null)
		{
			return "Access denied: " + pWhat + " is for EBX administrators only.";
		}
		return "Access denied: " + pWhat + " requires the MDM Factory right " + pNeeded + ". An EBX administrator gives it to one of your roles in the table"
			+ " 'Access rights' of the MdmFactory dataset (the role '" + FactoryAccess.roleFor(pNeeded) + "' has it by default).";
	}

	/**
	 * The permission rule of a user service that asks {@code pNeeded}: the service is neither shown to nor executable by a
	 * session that does not hold the right (its handlers re-check each action: a rule only hides).
	 */
	public static <S extends EntitySelection> ServicePermissionRule<S> rule(final FactoryRight pNeeded)
	{
		return new ServicePermissionRule<S>()
		{
			@Override
			public UserServicePermission getPermission(final ServicePermissionRuleContext<S> pContext)
			{
				return FactoryAccess.has(pContext.getSession(), pNeeded) ? UserServicePermission.getEnabled() : UserServicePermission.getDisabled();
			}
		};
	}
}
