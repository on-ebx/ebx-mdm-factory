package com.on.ebx.factory.ebx;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.orchestranetworks.service.Session;

/**
 * The ebx-connectors tools through the module's in-JVM bridge (the platform MBean
 * {@code com.on.ebx.connectors:type=InJvmMcp}, registered by ebx-connectors at repository startup) instead of HTTP
 * with the factory's agent account: every call runs with the CALLER's own session - the person in the wizard or
 * behind a REST route, the workflow's session in {@code LandingLoadTaskBean} - so it needs no password and works
 * the same under SSO. The bridge takes the {@link Session} object itself (the EBX classes are shared by Tomcat's
 * common class loader) and answers the same JSON-RPC messages as {@code /ebx-connectors/rest/mcp}, so the
 * returned {@link McpClient} behaves exactly like the HTTP one.
 * <p>
 * Absent when ebx-connectors is older than the bridge, or not started: {@link McpServers#connectors} then falls
 * back to HTTP with the agent account.
 * <p>
 * Every exchange runs on a small daemon pool under the same read timeout the HTTP transport gets: an MBean call
 * has no timeout of its own, and a Connectors call that never returns would otherwise hold its caller for good - the
 * single thread of the distribution dispatcher included, which would stop all real-time distribution.
 */
public final class ConnectorsBridge
{
	public static final String OBJECT_NAME = "com.on.ebx.connectors:type=InJvmMcp";
	private static final String[] SIGNATURE = { "java.lang.Object", "java.lang.String" };
	/** At most this many exchanges at once; a call that outlives its timeout keeps its thread until Connectors lets go of it, so the bound also bounds what a hung Connectors can pin. */
	static final int MAX_THREADS = 8;
	private static ExecutorService pool;
	/** The exchanges holding a bridge thread right now (F2-12): the count and the age of the oldest are shown on /status. */
	private static final List<Running> RUNNING = new CopyOnWriteArrayList<Running>();

	/** One exchange that holds a thread. {@code abandoned} = its caller gave up (timeout) and the thread is still inside Connectors. */
	private static final class Running
	{
		final long since = System.currentTimeMillis();
		volatile boolean abandoned;
	}

	private ConnectorsBridge()
	{
	}

	/** Whether ebx-connectors has registered its bridge in this JVM. */
	public static boolean available()
	{
		try
		{
			return ManagementFactory.getPlatformMBeanServer().isRegistered(new ObjectName(ConnectorsBridge.OBJECT_NAME));
		}
		catch (final Exception ex)
		{
			return false;
		}
	}

	/** A client of the ebx-connectors tools running as {@code pSession}, under the read timeout of the HTTP transport ({@link McpServers#readTimeoutMs()}). */
	public static McpClient client(final Session pSession)
	{
		return ConnectorsBridge.client(pSession, McpServers.readTimeoutMs());
	}

	/** {@link #client(Session)} with its own read timeout - for a call made while a screen renders, which must never wait minutes. */
	public static McpClient client(final Session pSession, final int pReadTimeoutMs)
	{
		return new McpClient(new McpClient.Transport()
		{
			@Override
			public String exchange(final String pMessage) throws McpException
			{
				return ConnectorsBridge.exchange(pSession, pMessage, pReadTimeoutMs);
			}

			@Override
			public String describe()
			{
				return "the in-JVM ebx-connectors bridge";
			}
		});
	}

	/** Module stop: the pool goes away with the class loader (a started thread would pin it, CLAUDE.md sec 10). */
	public static synchronized void stop()
	{
		if (ConnectorsBridge.pool != null)
		{
			ConnectorsBridge.pool.shutdownNow();
			ConnectorsBridge.pool = null;
		}
	}

	private static synchronized ExecutorService pool()
	{
		if (ConnectorsBridge.pool == null)
		{
			final AtomicInteger count = new AtomicInteger();
			final ThreadPoolExecutor created = new ThreadPoolExecutor(0, ConnectorsBridge.MAX_THREADS, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
				new ThreadFactory()
				{
					@Override
					public Thread newThread(final Runnable pRunnable)
					{
						final Thread thread = new Thread(pRunnable, "ebx-mdm-factory-connectors-bridge-" + count.incrementAndGet());
						thread.setDaemon(true);
						return thread;
					}
				});
			ConnectorsBridge.pool = created;
		}
		return ConnectorsBridge.pool;
	}

	static String exchange(final Session pSession, final String pMessage, final int pReadTimeoutMs) throws McpException
	{
		return ConnectorsBridge.exchange(null, ConnectorsBridge.OBJECT_NAME, pSession, pMessage, pReadTimeoutMs);
	}

	/**
	 * One exchange with the MBean {@code pObjectName} of ebx-connectors on {@code pServer} (null = the platform MBean server), under the
	 * same pool and read timeout as the MCP bridge: the contract client ({@link ConnectorsContractClient}) shares them, so a Connectors that never
	 * answers can pin at most {@link #MAX_THREADS} threads between the two.
	 */
	static String exchange(final MBeanServer pServer, final String pObjectName, final Session pSession, final String pMessage, final int pReadTimeoutMs) throws McpException
	{
		final Future<String> answer;
		final Running running = new Running();
		try
		{
			answer = ConnectorsBridge.pool().submit(new Callable<String>()
			{
				@Override
				public String call() throws McpException
				{
					ConnectorsBridge.RUNNING.add(running);
					try
					{
						return ConnectorsBridge.invoke(pServer, pObjectName, pSession, pMessage);
					}
					finally
					{
						ConnectorsBridge.RUNNING.remove(running);
					}
				}
			});
		}
		catch (final RejectedExecutionException ex)
		{
			// F2-12: all threads taken - refused at once (never queued, never an endless wait), with a code a caller can map to 503
			throw new McpException(503, ConnectorsBridge.busyMessage());
		}
		try
		{
			return answer.get(pReadTimeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (final TimeoutException ex)
		{
			// cancel(true) interrupts the thread; a Connectors call that does not read the interrupt keeps it until it ends: it is then counted as abandoned on /status
			running.abandoned = true;
			answer.cancel(true);
			throw new McpException("The in-JVM ebx-connectors bridge did not answer within " + pReadTimeoutMs / 1000 + " s (read timeout).", ex);
		}
		catch (final InterruptedException ex)
		{
			answer.cancel(true);
			Thread.currentThread().interrupt();
			throw new McpException("The in-JVM ebx-connectors bridge call was interrupted.", ex);
		}
		catch (final ExecutionException ex)
		{
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof McpException)
			{
				throw (McpException) cause;
			}
			throw new McpException("The in-JVM ebx-connectors bridge failed: " + cause, ex);
		}
	}

	/** The refusal sentence when every thread is taken (code {@code engine.external.bridgeBusy} in the docs): what holds them and what to do. */
	static String busyMessage()
	{
		final int busy = ConnectorsBridge.busyThreads();
		final long oldest = ConnectorsBridge.oldestBusyMs();
		return "The in-JVM ebx-connectors bridge is busy (external.bridgeBusy): " + busy + " of " + ConnectorsBridge.MAX_THREADS + " calls are still running"
			+ (oldest > 0L ? ", the oldest for " + oldest / 1000L + " s" : "") + ", " + ConnectorsBridge.abandonedThreads()
			+ " of them already given up by their caller but still running in Connectors. Wait for them to end, or look at the Connectors runs (connectors_run_get); /status shows connectorsBridge.";
	}

	/** Bridge threads inside a Connectors call right now. */
	public static int busyThreads()
	{
		return ConnectorsBridge.RUNNING.size();
	}

	/** Of those, calls whose caller already timed out: the thread stays taken until Connectors lets go. */
	public static int abandonedThreads()
	{
		int count = 0;
		for (final Running running : ConnectorsBridge.RUNNING)
		{
			if (running.abandoned)
			{
				count++;
			}
		}
		return count;
	}

	/** Age in milliseconds of the oldest call holding a thread; 0 when none. */
	public static long oldestBusyMs()
	{
		long oldest = 0L;
		final long now = System.currentTimeMillis();
		for (final Running running : new ArrayList<Running>(ConnectorsBridge.RUNNING))
		{
			oldest = Math.max(oldest, now - running.since);
		}
		return oldest;
	}

	/** {@code connectorsBridge} of {@code /status}: threads busy, abandoned after a timeout, the age of the oldest. */
	public static ObjectNode statusJson()
	{
		final ObjectNode out = new ObjectMapper().createObjectNode();
		out.put("maxThreads", ConnectorsBridge.MAX_THREADS);
		out.put("busyThreads", ConnectorsBridge.busyThreads());
		out.put("abandonedThreads", ConnectorsBridge.abandonedThreads());
		out.put("oldestBusySeconds", ConnectorsBridge.oldestBusyMs() / 1000L);
		out.put("full", ConnectorsBridge.busyThreads() >= ConnectorsBridge.MAX_THREADS);
		return out;
	}

	private static String invoke(final MBeanServer pServer, final String pObjectName, final Session pSession, final String pMessage) throws McpException
	{
		try
		{
			final MBeanServer server = pServer == null ? ManagementFactory.getPlatformMBeanServer() : pServer;
			final Object answer = server.invoke(new ObjectName(pObjectName), "handle", new Object[] { pSession, pMessage }, ConnectorsBridge.SIGNATURE);
			return answer == null ? null : answer.toString();
		}
		catch (final InstanceNotFoundException ex)
		{
			throw new McpException(0, "The in-JVM ebx-connectors bridge is not registered (ebx-connectors not started, or older than the bridge).");
		}
		catch (final Exception ex)
		{
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			throw new McpException("The in-JVM ebx-connectors bridge failed: " + cause, ex);
		}
	}
}
