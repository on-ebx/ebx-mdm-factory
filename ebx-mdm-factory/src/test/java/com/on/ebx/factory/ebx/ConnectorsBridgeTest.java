package com.on.ebx.factory.ebx;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;

/**
 * JUnit-free: run the main, a failure throws. {@link ConnectorsBridge} (connectors-in-jvm lot, 2026-09-24): absent
 * bridge = HTTP fallback and a clear error, present bridge = the whole MCP exchange through the platform MBean
 * server. A stand-in plays ebx-connectors' InJvmMcp (same ObjectName, same operation and signature); the real one
 * was checked in a plain JVM the same day.
 */
public final class ConnectorsBridgeTest
{
	/** Same operation as ebx-connectors' {@code InJvmMcpMBean}. */
	public interface Handler
	{
		String handle(Object pSession, String pMessage);
	}

	public static final class Fake implements Handler
	{
		final List<Object> sessions = new ArrayList<Object>();
		/** Milliseconds each call hangs before it answers - a Connectors call that does not return. */
		volatile long delayMs;

		@Override
		public String handle(final Object pSession, final String pMessage)
		{
			this.sessions.add(pSession);
			try
			{
				if (this.delayMs > 0)
				{
					Thread.sleep(this.delayMs);
				}
				final JsonNode message = new ObjectMapper().readTree(pMessage);
				final String method = message.path("method").asText();
				final int id = message.path("id").asInt();
				if (method.startsWith("notifications/"))
				{
					return "";
				}
				if ("initialize".equals(method))
				{
					return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"serverInfo\":{\"name\":\"ebx-connectors\"}}}";
				}
				return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}],"
					+ "\"structuredContent\":{\"status\":\"OK\",\"count\":7,\"ok\":7},\"isError\":false}}";
			}
			catch (final Exception ex)
			{
				return "";
			}
		}
	}

	/** F2-12: a Connectors call that blocks; {@code interruptible} says whether it reads the interrupt of a cancelled future. */
	public static final class Blocking implements Handler
	{
		final CountDownLatch release = new CountDownLatch(1);
		final boolean interruptible;

		Blocking(final boolean pInterruptible)
		{
			this.interruptible = pInterruptible;
		}

		@Override
		public String handle(final Object pSession, final String pMessage)
		{
			while (this.release.getCount() > 0)
			{
				try
				{
					this.release.await(50, TimeUnit.MILLISECONDS);
				}
				catch (final InterruptedException ex)
				{
					if (this.interruptible)
					{
						return "";
					}
				}
			}
			return "";
		}
	}

	private static void waitBusy(final int pExpected) throws Exception
	{
		final long until = System.currentTimeMillis() + 5000L;
		while (ConnectorsBridge.busyThreads() != pExpected && System.currentTimeMillis() < until)
		{
			Thread.sleep(20L);
		}
	}

	/** F2-12 on a private MBean server: threads freed after a timeout, counter, coded refusal when all 8 are taken. */
	static void threadAccounting() throws Exception
	{
		final MBeanServer server = MBeanServerFactory.newMBeanServer();
		final ObjectName name = new ObjectName("test:type=Blocking");
		final Blocking interruptible = new Blocking(true);
		server.registerMBean(new StandardMBean(interruptible, Handler.class), name);
		try
		{
			ConnectorsBridge.exchange(server, name.toString(), null, "{}", 150);
			ConnectorsBridgeTest.check("a hung call must time out", false);
		}
		catch (final McpException ex)
		{
			ConnectorsBridgeTest.check("timeout: " + ex.getMessage(), ex.getMessage().contains("did not answer within"));
		}
		ConnectorsBridgeTest.waitBusy(0);
		ConnectorsBridgeTest.check("the thread of a cancelled call that reads the interrupt is freed: " + ConnectorsBridge.busyThreads(), ConnectorsBridge.busyThreads() == 0);
		server.unregisterMBean(name);

		final Blocking stuck = new Blocking(false);
		server.registerMBean(new StandardMBean(stuck, Handler.class), name);
		for (int i = 0; i < ConnectorsBridge.MAX_THREADS; i++)
		{
			try
			{
				ConnectorsBridge.exchange(server, name.toString(), null, "{}", 60);
				ConnectorsBridgeTest.check("a stuck call must time out", false);
			}
			catch (final McpException ex)
			{
				ConnectorsBridgeTest.check("stuck call " + i + " times out: " + ex.getMessage(), ex.getMessage().contains("did not answer within"));
			}
		}
		ConnectorsBridgeTest.check("a call that ignores the interrupt keeps its thread, counted: " + ConnectorsBridge.busyThreads(), ConnectorsBridge.busyThreads() == ConnectorsBridge.MAX_THREADS);
		ConnectorsBridgeTest.check("all of them abandoned by their caller: " + ConnectorsBridge.abandonedThreads(), ConnectorsBridge.abandonedThreads() == ConnectorsBridge.MAX_THREADS);
		final long before = System.currentTimeMillis();
		try
		{
			ConnectorsBridge.exchange(server, name.toString(), null, "{}", 5000);
			ConnectorsBridgeTest.check("the ninth call must be refused", false);
		}
		catch (final McpException ex)
		{
			ConnectorsBridgeTest.check("refused at once, not after an endless wait: " + (System.currentTimeMillis() - before) + " ms", System.currentTimeMillis() - before < 1000L);
			ConnectorsBridgeTest.check("coded busy refusal: " + ex.getMessage(), ex.getMessage().contains("external.bridgeBusy") && ex.getMessage().contains("busy"));
			ConnectorsBridgeTest.check("with a 503 code", ex.getCode() == 503);
		}
		final ObjectNode status = ConnectorsBridge.statusJson();
		ConnectorsBridgeTest.check("/status counter: busy " + status, status.path("busyThreads").asInt() == ConnectorsBridge.MAX_THREADS && status.path("full").asBoolean()
			&& status.path("abandonedThreads").asInt() == ConnectorsBridge.MAX_THREADS && status.path("maxThreads").asInt() == ConnectorsBridge.MAX_THREADS);
		stuck.release.countDown();
		ConnectorsBridgeTest.waitBusy(0);
		ConnectorsBridgeTest.check("threads come back once Connectors lets go: " + ConnectorsBridge.busyThreads(), ConnectorsBridge.busyThreads() == 0);
		ConnectorsBridgeTest.check("/status says so", ConnectorsBridge.statusJson().path("busyThreads").asInt() == 0);
		server.unregisterMBean(name);
		ConnectorsBridge.stop();
	}

	public static void main(final String[] pArgs) throws Exception
	{
		ConnectorsBridgeTest.threadAccounting();
		ConnectorsBridgeTest.check("no bridge registered in a bare JVM", !ConnectorsBridge.available());
		ConnectorsBridgeTest.check("no session, no bridge: HTTP", "http".equals(McpServers.connectorsTransport(null)));
		try
		{
			ConnectorsBridge.client(null).callTool("connectors_list", null);
			ConnectorsBridgeTest.check("a call without a registered bridge must throw", false);
		}
		catch (final McpException ex)
		{
			ConnectorsBridgeTest.check("the error says the bridge is not registered: " + ex.getMessage(), ex.getMessage().indexOf("not registered") >= 0);
		}

		final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
		final ObjectName name = new ObjectName(ConnectorsBridge.OBJECT_NAME);
		final Fake fake = new Fake();
		server.registerMBean(new StandardMBean(fake, Handler.class), name);
		try
		{
			ConnectorsBridgeTest.check("the registered bridge is seen", ConnectorsBridge.available());
			ConnectorsBridgeTest.check("still HTTP without a session: nobody to run as", "http".equals(McpServers.connectorsTransport(null)));
			final McpClient.ToolResult result = ConnectorsBridge.client(null).callTool("connectors_sync", new ObjectMapper().createObjectNode().put("mapping", "231"));
			ConnectorsBridgeTest.check("the tool answer comes back structured", !result.error && result.json.path("count").asInt() == 7);
			ConnectorsBridgeTest.check("initialize, the notification and the call all went through the MBean: " + fake.sessions.size(), fake.sessions.size() == 3);

			// a call that never returns must give up at the read timeout, with the error shape of the HTTP transport (McpException, code 0)
			fake.delayMs = 5000L;
			final long before = System.currentTimeMillis();
			try
			{
				ConnectorsBridge.client(null, 300).callTool("connectors_sync", new ObjectMapper().createObjectNode().put("mapping", "231"));
				ConnectorsBridgeTest.check("a hung call must time out", false);
			}
			catch (final McpException ex)
			{
				final long waited = System.currentTimeMillis() - before;
				ConnectorsBridgeTest.check("the timeout names the bridge and the read timeout: " + ex.getMessage(), ex.getMessage().contains("did not answer within"));
				ConnectorsBridgeTest.check("it gave up at the timeout, not at the end of the call: " + waited + " ms", waited >= 250L && waited < 3000L);
				ConnectorsBridgeTest.check("a transport failure has code 0, like the HTTP transport's", ex.getCode() == 0);
			}
			fake.delayMs = 0L;
			ConnectorsBridgeTest.check("the bridge answers again once the call is quick", !ConnectorsBridge.client(null, 5000).callTool("connectors_list", null).error);
			ConnectorsBridgeTest.check("the default read timeout is the one of the HTTP transport", McpServers.readTimeoutMs() == 300000);

			// its threads are daemons, so a stopped module never pins the JVM, and stop() lets the pool go
			boolean daemon = false;
			for (final Thread thread : Thread.getAllStackTraces().keySet())
			{
				if (thread.getName().startsWith("ebx-mdm-factory-connectors-bridge-"))
				{
					daemon = thread.isDaemon();
				}
			}
			ConnectorsBridgeTest.check("the bridge threads are daemons", daemon);
			ConnectorsBridge.stop();
		}
		finally
		{
			server.unregisterMBean(name);
		}
		System.out.println("ConnectorsBridgeTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
