package com.on.ebx.factory.ebx;

import java.net.URL;
import java.net.URLClassLoader;

import com.orchestranetworks.service.OperationException;

/** JUnit-free (audit F2-02): the engine's loader is the context loader during the call and the previous one is back afterwards, also on an exception. */
public final class WorkflowLaunchesTest
{
	private WorkflowLaunchesTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final ClassLoader original = Thread.currentThread().getContextClassLoader();
		final ClassLoader module = new URLClassLoader(new URL[0], null);
		final ClassLoader engine = new URLClassLoader(new URL[0], null);
		Thread.currentThread().setContextClassLoader(module);
		try
		{
			final ClassLoader seen = WorkflowLaunches.withLoader(engine, new WorkflowLaunches.EngineCall<ClassLoader>()
			{
				@Override
				public ClassLoader call()
				{
					return Thread.currentThread().getContextClassLoader();
				}
			});
			check(seen == engine, "the engine loader must be set during the call");
			check(Thread.currentThread().getContextClassLoader() == module, "the previous loader must be restored");

			try
			{
				WorkflowLaunches.withLoader(engine, new WorkflowLaunches.EngineCall<Object>()
				{
					@Override
					public Object call() throws OperationException
					{
						throw new IllegalStateException("boom");
					}
				});
				check(false, "the exception must propagate");
			}
			catch (final IllegalStateException expected)
			{
				check("boom".equals(expected.getMessage()), "the original exception is rethrown");
			}
			check(Thread.currentThread().getContextClassLoader() == module, "the previous loader must be restored after an exception");

			final ClassLoader viaEngine = WorkflowLaunches.asEngine(new WorkflowLaunches.EngineCall<ClassLoader>()
			{
				@Override
				public ClassLoader call()
				{
					return Thread.currentThread().getContextClassLoader();
				}
			});
			check(viaEngine == com.orchestranetworks.workflow.WorkflowEngine.class.getClassLoader(), "asEngine uses WorkflowEngine's own loader");
			check(Thread.currentThread().getContextClassLoader() == module, "restored after asEngine");
		}
		finally
		{
			Thread.currentThread().setContextClassLoader(original);
		}
		System.out.println("WorkflowLaunchesTest OK");
	}

	private static void check(final boolean pOk, final String pMessage)
	{
		if (!pOk)
		{
			throw new AssertionError(pMessage);
		}
	}
}
