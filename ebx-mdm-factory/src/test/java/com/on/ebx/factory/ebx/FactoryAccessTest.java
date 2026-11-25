package com.on.ebx.factory.ebx;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.core.FactoryRight;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.Session;

/**
 * JUnit-free: run the main, a failure throws. Audit F-02: how {@link FactoryAccess} resolves the right of a session (sessions
 * are proxies of the {@code Session} interface holding a set of formatted profiles; the "Access rights" table is replaced by
 * {@link FactoryAccess#use}), and what the table-level access rule decides ({@link FactoryRightAccessRule#decide}).
 */
public final class FactoryAccessTest
{
	public static void main(final String[] pArgs)
	{
		try
		{
			FactoryAccessTest.denyByDefault();
			FactoryAccessTest.administratorAndSystem();
			FactoryAccessTest.defaultRoles();
			FactoryAccessTest.renamedRoles();
			FactoryAccessTest.tableRows();
			FactoryAccessTest.refusals();
			FactoryAccessTest.accessRule();
			FactoryAccessTest.tableRowRoles();
		}
		finally
		{
			FactoryAccess.use(new FactoryAccessRightTable());
		}
		System.out.println("FactoryAccessTest OK");
	}

	private static void denyByDefault()
	{
		FactoryAccess.use(FactoryAccessTest.grants(Collections.<Role, FactoryRight> emptyMap()));
		final Session plain = FactoryAccessTest.session(false);
		FactoryAccessTest.check("a plain user (the test account 'user') has no right", FactoryAccess.granted(plain) == null);
		FactoryAccessTest.check("not even VIEW", !FactoryAccess.has(plain, FactoryRight.VIEW));
		FactoryAccessTest.check("AUTHENTICATED passes", FactoryAccess.has(plain, FactoryRight.AUTHENTICATED));
		FactoryAccessTest.check("no session, nothing", !FactoryAccess.has(null, FactoryRight.AUTHENTICATED) && FactoryAccess.granted(null) == null);
	}

	private static void administratorAndSystem()
	{
		FactoryAccess.use(FactoryAccessTest.grants(Collections.<Role, FactoryRight> emptyMap()));
		final Session admin = FactoryAccessTest.session(false, Profile.ADMINISTRATOR.format());
		FactoryAccessTest.check("the EBX administrator holds ADMINISTRATOR", FactoryAccess.granted(admin) == FactoryRight.ADMINISTRATOR);
		final Session system = FactoryAccessTest.session(true);
		FactoryAccessTest.check("the system user holds ADMINISTRATOR", FactoryAccess.granted(system) == FactoryRight.ADMINISTRATOR);
		FactoryAccessTest.check("but in person the system user is nobody (no language model from an intake chain)",
			FactoryAccess.grantedInPerson(system) == null && !FactoryAccess.hasInPerson(system, FactoryRight.DESIGN));
		FactoryAccessTest.check("the system user may operate the pipeline", FactoryAccess.mayOperatePipeline(system, null));
	}

	private static void defaultRoles()
	{
		FactoryAccess.use(FactoryAccessTest.grants(Collections.<Role, FactoryRight> emptyMap()));
		FactoryAccessTest.check("FactoryViewer -> VIEW", FactoryAccess.granted(FactoryAccessTest.session(false, "R" + FactoryAccess.DEFAULT_VIEWER)) == FactoryRight.VIEW);
		FactoryAccessTest.check("FactorySteward -> STEWARD", FactoryAccess.granted(FactoryAccessTest.session(false, "R" + FactoryAccess.DEFAULT_STEWARD)) == FactoryRight.STEWARD);
		FactoryAccessTest.check("FactoryDesigner -> DESIGN", FactoryAccess.granted(FactoryAccessTest.session(false, "R" + FactoryAccess.DEFAULT_DESIGNER)) == FactoryRight.DESIGN);
		FactoryAccessTest.check("the widest role wins",
			FactoryAccess.granted(FactoryAccessTest.session(false, "R" + FactoryAccess.DEFAULT_VIEWER, "R" + FactoryAccess.DEFAULT_DESIGNER)) == FactoryRight.DESIGN);
		final Session designer = FactoryAccessTest.session(false, "R" + FactoryAccess.DEFAULT_DESIGNER);
		FactoryAccessTest.check("a designer never holds ADMINISTRATOR", !FactoryAccess.has(designer, FactoryRight.ADMINISTRATOR));
		FactoryAccessTest.check("a designer operates the pipeline without the launcher role", FactoryAccess.mayOperatePipeline(designer, null));
		FactoryAccessTest.check("a viewer does not operate the pipeline when no launcher role is set",
			!FactoryAccess.mayOperatePipeline(FactoryAccessTest.session(false, "R" + FactoryAccess.DEFAULT_VIEWER), null));
		FactoryAccessTest.check("allows() of a pipeline-operator entry with no launcher role = DESIGN only",
			!FactoryAccess.allows(FactoryAccessTest.session(false, "R" + FactoryAccess.DEFAULT_STEWARD), null, "INTAKE_SAVE", FactoryRight.DESIGN)
				&& FactoryAccess.allows(designer, null, "INTAKE_SAVE", FactoryRight.DESIGN));
	}

	private static void renamedRoles()
	{
		FactoryAccess.use(FactoryAccessTest.grants(Collections.<Role, FactoryRight> emptyMap()));
		System.setProperty(FactoryAccess.PROPERTY_STEWARD, "DataStewards");
		try
		{
			FactoryAccessTest.check("a renamed steward role is read", FactoryAccess.granted(FactoryAccessTest.session(false, "RDataStewards")) == FactoryRight.STEWARD);
			FactoryAccessTest.check("the default name no longer gives the right",
				FactoryAccess.granted(FactoryAccessTest.session(false, "R" + FactoryAccess.DEFAULT_STEWARD)) == null);
			FactoryAccessTest.check("the refusal names the renamed role", FactoryAccess.refusal("x", FactoryRight.STEWARD).contains("'DataStewards'"));
		}
		finally
		{
			System.clearProperty(FactoryAccess.PROPERTY_STEWARD);
		}
	}

	private static void tableRows()
	{
		final Map<Role, FactoryRight> rows = new HashMap<Role, FactoryRight>();
		rows.put(Profile.forSpecificRole("Sales"), FactoryRight.VIEW);
		rows.put(Profile.forSpecificRole("MdmTeam"), FactoryRight.DESIGN);
		FactoryAccess.use(FactoryAccessTest.grants(rows));
		FactoryAccessTest.check("a row gives its right", FactoryAccess.granted(FactoryAccessTest.session(false, "RSales")) == FactoryRight.VIEW);
		FactoryAccessTest.check("the widest row wins", FactoryAccess.granted(FactoryAccessTest.session(false, "RSales", "RMdmTeam")) == FactoryRight.DESIGN);
		FactoryAccessTest.check("a row widens a default role", FactoryAccess.granted(FactoryAccessTest.session(false, "R" + FactoryAccess.DEFAULT_VIEWER, "RMdmTeam")) == FactoryRight.DESIGN);
		FactoryAccessTest.check("a role without a row has nothing", FactoryAccess.granted(FactoryAccessTest.session(false, "ROther")) == null);
		rows.clear();
		rows.put(Profile.EVERYONE, FactoryRight.VIEW);
		FactoryAccess.use(FactoryAccessTest.grants(rows));
		FactoryAccessTest.check("Everyone -> VIEW gives every user VIEW", FactoryAccess.granted(FactoryAccessTest.session(false)) == FactoryRight.VIEW);
		rows.clear();
		rows.put(Profile.forSpecificRole("Rogue"), FactoryRight.ADMINISTRATOR);
		FactoryAccess.use(FactoryAccessTest.grants(rows));
		FactoryAccessTest.check("a row can never give ADMINISTRATOR", FactoryAccess.granted(FactoryAccessTest.session(false, "RRogue")) == null);
	}

	private static void refusals()
	{
		final String steward = FactoryAccess.refusal("deciding a pair", FactoryRight.STEWARD);
		FactoryAccessTest.check("the refusal names the right and the narrowest default role",
			steward.contains("STEWARD") && steward.contains("'" + FactoryAccess.DEFAULT_STEWARD + "'") && steward.contains("Access rights"));
		FactoryAccessTest.check("an administrator-only refusal says so", FactoryAccess.refusal("deleting", FactoryRight.ADMINISTRATOR).contains("administrators only"));
		FactoryAccessTest.check("roleFor DESIGN", FactoryAccess.DEFAULT_DESIGNER.equals(FactoryAccess.roleFor(FactoryRight.DESIGN)));
	}

	private static void accessRule()
	{
		FactoryAccessTest.check("no right: hidden", "hidden".equals(FactoryRightAccessRule.decide(null, FactoryRight.STEWARD)));
		FactoryAccessTest.check("VIEW on a steward table: read only", "r".equals(FactoryRightAccessRule.decide(FactoryRight.VIEW, FactoryRight.STEWARD)));
		FactoryAccessTest.check("STEWARD on a steward table: read-write", "rw".equals(FactoryRightAccessRule.decide(FactoryRight.STEWARD, FactoryRight.STEWARD)));
		FactoryAccessTest.check("STEWARD on a design table: read only", "r".equals(FactoryRightAccessRule.decide(FactoryRight.STEWARD, FactoryRight.DESIGN)));
		FactoryAccessTest.check("DESIGN on a design table: read-write", "rw".equals(FactoryRightAccessRule.decide(FactoryRight.DESIGN, FactoryRight.DESIGN)));
		FactoryAccessTest.check("ADMINISTRATOR: read-write", "rw".equals(FactoryRightAccessRule.decide(FactoryRight.ADMINISTRATOR, FactoryRight.DESIGN)));
		final Set<String> steward = new HashSet<String>();
		for (final com.orchestranetworks.schema.Path one : FactoryRightAccessRule.STEWARD_TABLES)
		{
			steward.add(one.format());
		}
		final Set<String> design = new HashSet<String>();
		for (final com.orchestranetworks.schema.Path one : FactoryRightAccessRule.DESIGN_TABLES)
		{
			design.add(one.format());
		}
		FactoryAccessTest.check("no table in both lists", Collections.disjoint(steward, design));
		final Set<String> own = new HashSet<String>(Arrays.asList("/root/FactorySettings", "/root/AgentRun", "/root/AgentStep", "/root/AccessRight", "/root/Favorite",
			"/root/FactoryJob", "/root/AgentProposal"));
		for (final com.orchestranetworks.schema.Path table : com.on.ebx.factory.core.FactoryPaths.TABLES)
		{
			final String path = table.format();
			FactoryAccessTest.check("table " + path + " has exactly one access rule", (steward.contains(path) ? 1 : 0) + (design.contains(path) ? 1 : 0) + (own.contains(path) ? 1 : 0) == 1);
		}
		FactoryAccessTest.check("an agent proposal is written by the module only (F3-03)", !steward.contains("/root/AgentProposal") && !design.contains("/root/AgentProposal"));
		FactoryAccessTest.check("distribution targets are written by DESIGN only", design.contains("/root/DistributionTarget"));
		FactoryAccessTest.check("the stored matching results (a PipelineStep detail) are written by a steward's decision", steward.contains("/root/PipelineStep"));
	}

	private static void tableRowRoles()
	{
		FactoryAccessTest.check("a role row is read", Profile.forSpecificRole("Sales").equals(FactoryAccessRightTable.role("RSales")));
		FactoryAccessTest.check("Everyone is read", Profile.EVERYONE.equals(FactoryAccessRightTable.role(Profile.EVERYONE.format())));
		FactoryAccessTest.check("a user is not a role", FactoryAccessRightTable.role("Ualice") == null);
		FactoryAccessTest.check("the administrator profile is not a grantee", FactoryAccessRightTable.role(Profile.ADMINISTRATOR.format()) == null);
		FactoryAccessTest.check("garbage is nobody", FactoryAccessRightTable.role("") == null && FactoryAccessRightTable.role(null) == null);
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	private static FactoryAccess.Grants grants(final Map<Role, FactoryRight> pRows)
	{
		return new FactoryAccess.Grants()
		{
			@Override
			public Map<Role, FactoryRight> byRole()
			{
				return pRows;
			}
		};
	}

	/** A session of the {@code Session} interface: in the given profiles (formatted, e.g. {@code RFactoryViewer}, {@code Badministrator}), plus Everyone. */
	private static Session session(final boolean pSystem, final String... pProfiles)
	{
		final Set<String> profiles = new HashSet<String>(Arrays.asList(pProfiles));
		profiles.add(Profile.EVERYONE.format());
		return (Session) Proxy.newProxyInstance(FactoryAccessTest.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
			{
				if ("isUserInRole".equals(pMethod.getName()))
				{
					return Boolean.valueOf(pArgs[0] != null && profiles.contains(((Profile) pArgs[0]).format()));
				}
				if ("isSystemUser".equals(pMethod.getName()))
				{
					return Boolean.valueOf(pSystem);
				}
				if ("equals".equals(pMethod.getName()))
				{
					return Boolean.valueOf(pProxy == pArgs[0]);
				}
				if ("hashCode".equals(pMethod.getName()))
				{
					return Integer.valueOf(System.identityHashCode(pProxy));
				}
				final Class<?> type = pMethod.getReturnType();
				return type == boolean.class ? Boolean.FALSE : type == int.class ? Integer.valueOf(0) : null;
			}
		});
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
