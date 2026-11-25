package com.on.ebx.factory.ebx;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;

/**
 * The rights an administrator gave in the table {@code AccessRight} of the MdmFactory dataset (audit F-02), as
 * {@link FactoryAccess} reads them: role of the directory -> right. The table is small and is asked on every click,
 * every menu and every REST call, so its content is kept for {@link #TTL_MILLIS}: a change applies within that delay.
 * Same class as ebx-connectors' {@code AccessRightTable}.
 * <p>
 * Anything that prevents the read (dataset not installed yet, a model loaded before the table existed, a row that cannot
 * be understood, a right outside VIEW / STEWARD / DESIGN) gives no right: the administrator and the default roles still
 * pass, nobody else does.
 */
final class FactoryAccessRightTable implements FactoryAccess.Grants
{
	static final long TTL_MILLIS = 5000L;
	private volatile Map<Role, FactoryRight> cached = Collections.<Role, FactoryRight> emptyMap();
	private volatile long readAt;

	@Override
	public Map<Role, FactoryRight> byRole()
	{
		final long now = System.currentTimeMillis();
		if (this.readAt != 0L && now - this.readAt < FactoryAccessRightTable.TTL_MILLIS)
		{
			return this.cached;
		}
		Map<Role, FactoryRight> read;
		try
		{
			read = FactoryAccessRightTable.read(Repository.getDefault());
		}
		catch (final RuntimeException ex)
		{
			read = Collections.<Role, FactoryRight> emptyMap();
		}
		catch (final LinkageError ex)
		{
			read = Collections.<Role, FactoryRight> emptyMap();
		}
		this.cached = read;
		this.readAt = now;
		return read;
	}

	private static Map<Role, FactoryRight> read(final Repository pRepository)
	{
		final Adaptation dataset = pRepository == null ? null : FactoryInstaller.dataset(pRepository);
		if (dataset == null)
		{
			return Collections.<Role, FactoryRight> emptyMap();
		}
		// a model loaded before the table existed throws here: byRole() reads that as "no row, no right"
		final AdaptationTable table = dataset.getTable(FactoryPaths.ACCESS_RIGHT);
		final Map<Role, FactoryRight> rights = new LinkedHashMap<Role, FactoryRight>();
		final RequestResult rows = table.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Role role = FactoryAccessRightTable.role(row.getString(FactoryPaths.AccessRight.PROFILE));
				final FactoryRight right = FactoryRight.grantableNamed(row.getString(FactoryPaths.AccessRight.RIGHT));
				if (role != null && right != null)
				{
					rights.put(role, right);
				}
			}
		}
		finally
		{
			rows.close();
		}
		return Collections.unmodifiableMap(rights);
	}

	/** The role a row names when it may receive a right (a specific role or "Everyone"), null otherwise. */
	static Role role(final String pFormatted)
	{
		if (pFormatted == null || pFormatted.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final Profile profile = Profile.parse(pFormatted.trim());
			return FactoryRoleEnumeration.eligible(profile) ? (Role) profile : null;
		}
		catch (final IllegalArgumentException ex)
		{
			return null;
		}
		catch (final ClassCastException ex)
		{
			return null;
		}
	}
}
