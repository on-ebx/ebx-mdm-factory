package com.on.ebx.factory.ebx;

import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRule;
import com.orchestranetworks.service.Session;

/**
 * Production review of 2026-11-12 (F3-02): a field no person sees or writes, the administrator included, in any screen, table view, export or Data Services call:
 * the secret key of the agents' activations. The module reads it with {@code Adaptation.getString}, which is not session-checked (the same reading that
 * {@link AdminOnlyAccessRule} relies on), and writes it from a procedure with all privileges. A call with no session (the platform itself) is not hidden.
 */
public final class HiddenFieldRule implements AccessRule
{
	/** Pure: hidden for every session of a person or of the system; only the absence of a session (the platform) is let through. */
	static boolean hidden(final boolean pHasSession)
	{
		return pHasSession;
	}

	@Override
	public AccessPermission getPermission(final Adaptation pRecord, final Session pSession, final SchemaNode pNode)
	{
		return HiddenFieldRule.hidden(pSession != null) ? AccessPermission.getHidden() : AccessPermission.getReadWrite();
	}
}
