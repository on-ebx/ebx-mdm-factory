package com.on.ebx.factory.ebx;

import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.workflow.ProcessInstanceKey;
import com.orchestranetworks.workflow.WorkflowEngine;

/**
 * The single passage through which this module calls the workflow engine's {@code launchProcess} (audit F2-02, CLAUDE.md &sect;10, measured 2026-10-22 on ebx-privacy).
 * The engine's token pools create their threads on demand FROM THE SUBMITTING THREAD and those threads keep its context class loader. A thread of this module (intake launch pool,
 * REST or wizard HTTP thread) carries the Factory web application's loader: once that context is reloaded, every token such a thread picks dies with
 * {@code NoClassDefFoundError ... SimpleLoggerContext} (Tomcat console only) and every workflow of the instance stays without step, work item or error flag until Tomcat restarts.
 * So the loader of the engine's own class ({@code WorkflowEngine}, in the server's shared libraries) is set around the call and the previous one restored in a {@code finally}.
 * Public API only.
 */
public final class WorkflowLaunches
{
	/** One call to the workflow engine. */
	public interface EngineCall<T>
	{
		T call() throws OperationException;
	}

	private WorkflowLaunches()
	{
	}

	/** Runs the call with the workflow engine's class loader as the thread's context class loader. */
	public static <T> T asEngine(final EngineCall<T> pCall) throws OperationException
	{
		return WorkflowLaunches.withLoader(WorkflowEngine.class.getClassLoader(), pCall);
	}

	/** Launches through {@code launchProcess} and returns its result; every launch of the module goes through here. */
	public static ProcessInstanceKey launch(final com.orchestranetworks.workflow.ProcessLauncher pLauncher) throws OperationException
	{
		return WorkflowLaunches.asEngine(new EngineCall<ProcessInstanceKey>()
		{
			@Override
			public ProcessInstanceKey call() throws OperationException
			{
				return pLauncher.launchProcess();
			}
		});
	}

	/** Same with an explicit loader (pure, testable): sets it, calls, restores the previous one whatever happens. */
	static <T> T withLoader(final ClassLoader pLoader, final EngineCall<T> pCall) throws OperationException
	{
		final Thread thread = Thread.currentThread();
		final ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(pLoader);
		try
		{
			return pCall.call();
		}
		finally
		{
			thread.setContextClassLoader(previous);
		}
	}
}
