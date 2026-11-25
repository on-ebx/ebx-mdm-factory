package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.on.ebx.factory.ebx.ui.Messages;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.instance.ValueContext;
import com.orchestranetworks.instance.ValueContextForValidation;
import com.orchestranetworks.schema.ConstraintContext;
import com.orchestranetworks.schema.ConstraintEnumeration;
import com.orchestranetworks.schema.InvalidSchemaException;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.directory.DirectoryHandler;

/**
 * Value list of {@code AccessRight/profile} (audit F-02): the roles of the directory (and "Everyone"), so that an
 * administrator maps the Factory rights onto roles that already exist instead of creating new ones. The value stored is
 * the formatted profile ({@code Profile.format()}, for instance {@code RDataStewards}). Same class as ebx-connectors'
 * {@code ConnectorsRoleEnumeration}.
 * <p>
 * The list comes from the directory the instance runs ({@code DirectoryHandler.getProfiles}). Its argument is obtained from
 * {@code ProfileListContextBridge}, a class of the directory package that the public Javadoc does not list: it is called by
 * reflection, and when it is missing the list falls back to "Everyone" and the three default role names, so that the model
 * always loads. What is accepted never depends on that list: the check below only uses the documented API
 * ({@code Profile.parse}, {@code DirectoryHandler.isSpecificRoleDefined}). Like any programmatic enumeration it does not
 * block a value written over Data Services (CLAUDE.md section 2): {@link FactoryAccessRightTable} re-checks every row it reads.
 * <p>
 * <b>Documented exception (audit F2-08, decision 6 of the audit README): the only use of {@code ProfileListContextBridge}, kept because the public
 * API offers no way to build the argument of {@code getProfiles}.</b> What breaks if the editor renames it: the dropdown offers "Everyone" and the
 * three default role names instead of the directory's roles, one WARN says so in {@code kernel.log}, and a role is still accepted when the
 * administrator writes it (Data Services, import): {@code checkOccurrence} validates it against {@code isSpecificRoleDefined}, so nothing that
 * worked is refused. No other private API is used by this class.
 */
public final class FactoryRoleEnumeration implements ConstraintEnumeration<String>
{
	@Override
	public void setup(final ConstraintContext pContext)
	{
		// nothing to check in the model
	}

	@Override
	public List<String> getValues(final ValueContext pContext) throws InvalidSchemaException
	{
		List<Profile> roles;
		try
		{
			roles = FactoryRoleEnumeration.listDirectoryProfiles(FactoryRoleEnumeration.directory(pContext), FactoryRoleEnumeration.BRIDGE_CLASS,
				FactoryRoleEnumeration.CONTEXT_CLASS);
		}
		catch (final Exception ex)
		{
			FactoryRoleEnumeration.warnOnce(ex);
			roles = new ArrayList<Profile>();
		}
		catch (final LinkageError ex)
		{
			FactoryRoleEnumeration.warnOnce(ex);
			roles = new ArrayList<Profile>();
		}
		return FactoryRoleEnumeration.offered(roles);
	}

	static final String BRIDGE_CLASS = "com.orchestranetworks.service.directory.ProfileListContextBridge";
	static final String CONTEXT_CLASS = "com.orchestranetworks.service.directory.ProfileListContext";
	private static final AtomicBoolean WARNED = new AtomicBoolean(false);

	/**
	 * The eligible profiles of the directory, listed through the unlisted bridge class. Throws (ClassNotFoundException, NoSuchMethodException...) when
	 * the class or a method is not there on this build: {@link #getValues} turns that into the fallback list and one WARN.
	 */
	static List<Profile> listDirectoryProfiles(final DirectoryHandler pDirectory, final String pBridgeClass, final String pContextClass) throws Exception
	{
		final Object listContext = Class.forName(pBridgeClass).getMethod("getForDefiningPermission").invoke(null);
		final Class<?> contextType = Class.forName(pContextClass);
		final Object profiles = DirectoryHandler.class.getMethod("getProfiles", contextType).invoke(pDirectory, listContext);
		final List<Profile> roles = new ArrayList<Profile>();
		for (final Object one : (List<?>) profiles)
		{
			if (one instanceof Profile && FactoryRoleEnumeration.eligible((Profile) one))
			{
				roles.add((Profile) one);
			}
		}
		return roles;
	}

	private static void warnOnce(final Throwable pCause)
	{
		if (FactoryRoleEnumeration.WARNED.compareAndSet(false, true))
		{
			try
			{
				LoggingCategory.getKernel().warn("[mdm-factory] the roles of the directory cannot be listed on this EBX version (" + pCause
					+ "): the access-rights dropdown offers Everyone and the default role names only; any existing role can still be written and is checked with"
					+ " isSpecificRoleDefined (audit F2-08).");
			}
			catch (final Throwable ex)
			{
				// a logging failure never breaks the model load
			}
		}
	}

	/** Pure: what the dropdown shows for the profiles the directory listed (none = the listing failed or the directory is empty). Sorted, formatted. */
	static List<String> offered(final List<Profile> pListed)
	{
		final List<Profile> roles = new ArrayList<Profile>(pListed);
		if (roles.isEmpty())
		{
			// the directory could not be listed on this EBX version: the default names stay selectable
			roles.add(Profile.EVERYONE);
			for (final String name : new String[] { FactoryAccess.viewerRole(), FactoryAccess.stewardRole(), FactoryAccess.designerRole() })
			{
				try
				{
					roles.add(Profile.forSpecificRole(name));
				}
				catch (final IllegalArgumentException ex)
				{
					// a role name EBX refuses is simply not offered
				}
			}
		}
		else if (!roles.contains(Profile.EVERYONE))
		{
			roles.add(Profile.EVERYONE);
		}
		Collections.sort(roles, new Comparator<Profile>()
		{
			@Override
			public int compare(final Profile pFirst, final Profile pSecond)
			{
				return pFirst.format().compareToIgnoreCase(pSecond.format());
			}
		});
		final List<String> values = new ArrayList<String>(roles.size());
		for (final Profile role : roles)
		{
			values.add(role.format());
		}
		return values;
	}

	/** A specific role or "Everyone": the administrator always passes, and a right is never given to "owner", "read-only" or a single user. */
	static boolean eligible(final Profile pProfile)
	{
		return pProfile != null && (pProfile.isSpecificRole() || pProfile.isBuiltInEveryone());
	}

	private static DirectoryHandler directory(final ValueContext pContext)
	{
		final Repository repository = pContext != null && pContext.getHome() != null ? pContext.getHome().getRepository() : Repository.getDefault();
		return DirectoryHandler.getInstance(repository);
	}

	@Override
	public String displayOccurrence(final String pValue, final ValueContext pContext, final Locale pLocale) throws InvalidSchemaException
	{
		try
		{
			return FactoryRoleEnumeration.directory(pContext).displayProfile(Profile.parse(pValue), pLocale);
		}
		catch (final Exception ex)
		{
			return pValue;
		}
	}

	@Override
	public void checkOccurrence(final String pValue, final ValueContextForValidation pContext) throws InvalidSchemaException
	{
		if (pValue == null)
		{
			return;
		}
		final Profile profile;
		try
		{
			profile = Profile.parse(pValue);
		}
		catch (final IllegalArgumentException ex)
		{
			pContext.addMessage(Messages.getError(Messages.class, "access.role.notARole", pValue));
			return;
		}
		if (!FactoryRoleEnumeration.eligible(profile))
		{
			pContext.addMessage(Messages.getError(Messages.class, "access.role.notARole", pValue));
			return;
		}
		if (profile.isSpecificRole() && !FactoryRoleEnumeration.directory(pContext).isSpecificRoleDefined((Role) profile))
		{
			pContext.addMessage(Messages.getError(Messages.class, "access.role.unknown", profile.getLabel()));
		}
	}

	@Override
	public String toUserDocumentation(final Locale pLocale, final ValueContext pContext) throws InvalidSchemaException
	{
		return Messages.get(Messages.class, pLocale, "access.role.doc");
	}
}
