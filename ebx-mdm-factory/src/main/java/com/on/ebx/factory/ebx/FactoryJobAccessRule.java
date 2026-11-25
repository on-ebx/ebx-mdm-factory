package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRule;
import com.orchestranetworks.service.AccessRuleForCreate;
import com.orchestranetworks.service.AccessRuleForCreateContext;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * The job queue ({@code FactoryJob}) is the factory's own bookkeeping, and the dispatcher runs every QUEUED row under
 * the system session FOR the user the row names: a row forged by hand would run work as any user. So the rows are read
 * only, in every screen, table view and Data Services call, to everybody but an administrator; the factory itself writes
 * them from a procedure that disables permission checks ({@code FactoryJobQueue}), or from a system session
 * ({@code FactoryRetention}'s purge), and is not stopped by this rule. The rows stay READABLE: the jobs banner and the
 * status screens show them to every user who may open the factory.
 * <p>
 * Creation is closed field by field ({@link #CREATE_LOCKED_FIELDS}): a creation rule on the table node itself puts the
 * whole model in error (measured on the Material audit trail).
 */
public final class FactoryJobAccessRule implements AccessRule, AccessRuleForCreate
{
	static final UserMessage REASON = UserMessage.createInfo("Background jobs are queued by the factory itself: read only.");

	/** Every field of the table (a test compares this list with {@link FactoryPaths.FactoryJob}). */
	static final Path[] CREATE_LOCKED_FIELDS = { FactoryPaths.FactoryJob.ID, FactoryPaths.FactoryJob.PROJECT, FactoryPaths.FactoryJob.RESOURCE, FactoryPaths.FactoryJob.KIND,
		FactoryPaths.FactoryJob.PARAMS, FactoryPaths.FactoryJob.STATUS, FactoryPaths.FactoryJob.REQUESTED_BY, FactoryPaths.FactoryJob.VIA,
		FactoryPaths.FactoryJob.REQUESTED_AT, FactoryPaths.FactoryJob.STARTED_AT, FactoryPaths.FactoryJob.ENDED_AT, FactoryPaths.FactoryJob.STEP_ID,
		FactoryPaths.FactoryJob.MESSAGE, FactoryPaths.FactoryJob.DETAIL };

	/** Pure: who may write a job row through a permission-checked path. */
	static boolean writable(final boolean pAdministrator, final boolean pSystemUser)
	{
		return pAdministrator || pSystemUser;
	}

	private static boolean writable(final Session pSession)
	{
		// no session = the platform itself (a trigger context, a startup procedure)
		return pSession == null || FactoryJobAccessRule.writable(pSession.isUserInRole(Profile.ADMINISTRATOR), pSession.isSystemUser());
	}

	@Override
	public AccessPermission getPermission(final Adaptation pRecord, final Session pSession, final SchemaNode pNode)
	{
		return FactoryJobAccessRule.writable(pSession) ? AccessPermission.getReadWrite() : AccessPermission.getReadOnly(FactoryJobAccessRule.REASON);
	}

	@Override
	public AccessPermission getPermission(final AccessRuleForCreateContext pContext)
	{
		return FactoryJobAccessRule.writable(pContext.getSession()) ? AccessPermission.getReadWrite() : AccessPermission.getReadOnly(FactoryJobAccessRule.REASON);
	}
}
