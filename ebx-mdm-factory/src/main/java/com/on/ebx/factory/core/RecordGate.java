package com.on.ebx.factory.core;

/**
 * Pure decisions behind every screen and route that opens a project (or another record) a page or a URL names (2026-10-14, second permissions pass,
 * {@code docs/PERMISSIONS.md}): the Factory right of the entry point ({@link FactoryRight}) is necessary, never sufficient - EBX's own permissions on the
 * record decide too. No EBX dependency: {@code ebx.UserView} reads the facts, this class decides, {@code RecordGateTest} pins the table.
 * <ul>
 * <li>a record the user cannot see is answered exactly like a record that does not exist ({@link Verdict#UNKNOWN}): no 403 that would confirm it is there;</li>
 * <li>an action that only looks (Factory right {@link FactoryRight#VIEW} or {@link FactoryRight#AUTHENTICATED}) needs the record visible;</li>
 * <li>any other action writes or queues something about the record: it needs EBX's read-write access on it ({@link Verdict#READ_ONLY} otherwise);</li>
 * <li>creating a new project needs EBX's creation right on the {@code Project} table ({@link Verdict#NO_CREATE} otherwise).</li>
 * </ul>
 */
public final class RecordGate
{
	/** What an entry point does with the record it was asked about. */
	public enum Verdict
	{
		/** Go on. */
		ALLOW,
		/** Absent or hidden from the user: answered as "not found". */
		UNKNOWN,
		/** Visible, but the action writes and the user may only read it. */
		READ_ONLY,
		/** No record yet and the user may not create one. */
		NO_CREATE
	}

	private RecordGate()
	{
	}

	/** True when an entry point asking {@code pNeeded} only reads (looks, navigates, downloads what is shown). */
	public static boolean readsOnly(final FactoryRight pNeeded)
	{
		return pNeeded == FactoryRight.VIEW || pNeeded == FactoryRight.AUTHENTICATED;
	}

	/**
	 * The verdict for an existing record named by the caller.
	 *
	 * @param pExists the key resolves to a record
	 * @param pVisible the user's session may see it (dataspace, dataset, table and record)
	 * @param pWritable the user's session may modify it
	 */
	public static Verdict onRecord(final FactoryRight pNeeded, final boolean pExists, final boolean pVisible, final boolean pWritable)
	{
		if (!pExists || !pVisible)
		{
			return Verdict.UNKNOWN;
		}
		if (RecordGate.readsOnly(pNeeded) || pWritable)
		{
			return Verdict.ALLOW;
		}
		return Verdict.READ_ONLY;
	}

	/**
	 * The verdict for a wizard action: {@code pProjectKey} empty means a project not created yet, where only reading actions pass, and the action
	 * that creates the project needs {@code pCanCreate}.
	 */
	public static Verdict onWizardAction(final FactoryRight pNeeded, final boolean pCreatesProject, final String pProjectKey, final boolean pExists,
		final boolean pVisible, final boolean pWritable, final boolean pCanCreate)
	{
		if (pProjectKey == null || pProjectKey.trim().isEmpty())
		{
			if (pCreatesProject)
			{
				return pCanCreate ? Verdict.ALLOW : Verdict.NO_CREATE;
			}
			return Verdict.ALLOW; // nothing to look at or write about yet: the action itself has its own preconditions
		}
		return RecordGate.onRecord(pNeeded, pExists, pVisible, pWritable);
	}

	/** The HTTP status a REST route answers for a verdict other than {@link Verdict#ALLOW}: 404 for an unknown or hidden record, 403 otherwise. */
	public static int httpStatus(final Verdict pVerdict)
	{
		switch (pVerdict)
		{
			case ALLOW:
				return 200;
			case UNKNOWN:
				return 404;
			default:
				return 403;
		}
	}
}
