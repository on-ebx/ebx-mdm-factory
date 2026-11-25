package com.on.ebx.factory.core;

/**
 * What an entry point of the MDM Factory asks of the person using it (audit F-02), declared from the narrowest to the
 * widest: each right includes every right above it in this list. Pure, no EBX dependency: the decision tables of
 * {@link FactoryRights} and the wizard page use it offline; {@code ebx.FactoryAccess} resolves the right of a session.
 * <ul>
 * <li>{@link #AUTHENTICATED} - any authenticated user, no Factory right needed (liveness, the application's own
 * "batch complete" call, which is gated by its write access to the landing dataset instead);</li>
 * <li>{@link #VIEW} - the project explorer, the cockpit, the wizard and the proposal review read only, record lineage
 * and cross-references (filtered by the user's own EBX read permissions), the jobs banner;</li>
 * <li>{@link #STEWARD} - also the decisions of a data steward: suspect pairs of the matching results (merge / not
 * the same), the proposal review's accept / reject (one line, a selection, the confidence threshold, a cascade);</li>
 * <li>{@link #DESIGN} - also every design and run action: the wizard's actions, the mapping matrix, the feed designer,
 * staging and master generation and loads, matching configuration and runs, capabilities, distribution set-up and
 * sends, the language-model passes (bounded by the administrator's settings);</li>
 * <li>{@link #ADMINISTRATOR} - the EBX administrator only, never given by a role: the factory settings (language-model
 * connector and limits, the agent account and its password, the launcher role, retention), the connection screen and
 * its test, project deletion, step reset and master reset, the arming override of a distribution target, the
 * delivery domain, diagnostics and maintenance routes.</li>
 * </ul>
 * The EBX administrator and the system user (a workflow script task, a scheduled task, the job dispatcher) always hold
 * every right.
 */
public enum FactoryRight
{
	AUTHENTICATED, VIEW, STEWARD, DESIGN, ADMINISTRATOR;

	/** True when holding this right is enough for an entry point that asks {@code pNeeded}; nothing covers a null need. */
	public boolean covers(final FactoryRight pNeeded)
	{
		return pNeeded != null && this.ordinal() >= pNeeded.ordinal();
	}

	/** True for the rights an administrator may give to a role in the table "Access rights": VIEW, STEWARD, DESIGN. */
	public boolean grantable()
	{
		return this == VIEW || this == STEWARD || this == DESIGN;
	}

	/** The grantable right of this name as the table stores it, null for anything else (ADMINISTRATOR included). */
	public static FactoryRight grantableNamed(final String pName)
	{
		for (final FactoryRight right : FactoryRight.values())
		{
			if (right.grantable() && right.name().equals(pName == null ? null : pName.trim()))
			{
				return right;
			}
		}
		return null;
	}

	/** {@code pGranted} covers {@code pNeeded}; a session with no right (null) passes only {@link #AUTHENTICATED}. */
	public static boolean allows(final FactoryRight pGranted, final FactoryRight pNeeded)
	{
		if (pNeeded == FactoryRight.AUTHENTICATED)
		{
			return true;
		}
		return pGranted != null && pGranted.covers(pNeeded);
	}
}
