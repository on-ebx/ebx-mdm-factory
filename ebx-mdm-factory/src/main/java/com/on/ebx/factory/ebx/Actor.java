package com.on.ebx.factory.ebx;

import com.orchestranetworks.service.Session;

/**
 * Who a piece of factory work is done FOR, when that is not the EBX session's own user (pipeline-audit lot,
 * 2026-09-24). A workflow script task runs with a system-user session (EBX Javadoc,
 * {@code ProcessExecutionInfoContext.getSession}), so every step it records would read "system": the workflow beans
 * set the instance's real launcher here for the duration of their work, and {@code PipelineSteps.begin} records it.
 * Outside a bean nothing is set and the session's user is the actor. Thread-bound, always cleared in a finally.
 */
public final class Actor
{
	private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<Actor>();

	public final String userId;
	public final String via;

	private Actor(final String pUserId, final String pVia)
	{
		this.userId = pUserId;
		this.via = pVia;
	}

	/** Work that may throw, run with {@code pUserId}/{@code pVia} as the actor. */
	public interface Work<T, E extends Exception>
	{
		T run() throws E;
	}

	public static <T, E extends Exception> T as(final String pUserId, final String pVia, final Work<T, E> pWork) throws E
	{
		final Actor previous = Actor.CURRENT.get();
		Actor.CURRENT.set(new Actor(pUserId, pVia));
		try
		{
			return pWork.run();
		}
		finally
		{
			if (previous == null)
			{
				Actor.CURRENT.remove();
			}
			else
			{
				Actor.CURRENT.set(previous);
			}
		}
	}

	/** The actor set by {@link #as} on this thread, or null (then the session's own user acts). */
	public static Actor explicit()
	{
		return Actor.CURRENT.get();
	}

	/** The actor set by {@link #as}, else the session's own user with via {@code session}. */
	public static Actor current(final Session pSession)
	{
		final Actor set = Actor.CURRENT.get();
		if (set != null)
		{
			return set;
		}
		final String user = pSession == null || pSession.getUserReference() == null ? null : pSession.getUserReference().getUserId();
		return new Actor(user, "session");
	}
}
