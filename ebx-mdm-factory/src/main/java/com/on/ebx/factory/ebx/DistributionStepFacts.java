package com.on.ebx.factory.ebx;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Audit F-2-101 (lot B3): what step 7 of the wizard knows about the instance's Connectors connections, kept per person and per project so that a DISPLAY never
 * calls Connectors. The display used to pay {@code connectors_list}, one {@code connectors_describe} per connection, then a second {@code connectors_list}
 * and a {@code select 1} on the customer's warehouse (which wakes a suspended warehouse, and costs credits) at every render of the step.
 * <p>
 * The facts come from {@link Reader}: a read of the connection list (what {@link DistributionConnections#listCandidateConnections} answers) and, ONLY on an explicit
 * action ("Test the connection"), a probe of the warehouse. {@link #lookup} never calls anything. A missing or expired entry is asked for again on a background thread
 * ({@link #refreshInBackground}) and the page says "reading the connections" until it comes. The probe is never started by a display.
 * <p>
 * Age limit: {@link #maxAgeMillis()}, JVM property {@value #AGE_PROPERTY} (seconds, default 300). Nothing here is shared between people: a read is the answer of one
 * session (what that person may see), so the key holds the user id. Pure but for the executor (no EBX type besides the session handed through): tested offline with an
 * injected clock and reader.
 */
public final class DistributionStepFacts
{
	/** JVM property: how long, in seconds, the connections read for a person stay fresh before a display asks for them again in the background. Default 300. */
	public static final String AGE_PROPERTY = "mdmf.distribution.connections.cacheSeconds";
	static final long DEFAULT_AGE_SECONDS = 300L;
	static final int MAX_ENTRIES = 200;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The two reads: both may call Connectors, so neither is ever called from a display. */
	public interface Reader
	{
		/** {@code {connections: [...]}} as {@link DistributionConnections#listCandidateConnections}. */
		ObjectNode readConnections() throws OperationException;

		/** The warehouse probe of connection {@code pConnectorId}: {@code {reachable, elapsedMs, detail}}. Called only by an explicit action. */
		ObjectNode probeWarehouse(String pConnectorId);
	}

	interface Clock
	{
		long now();
	}

	/** What is known for one (person, project). {@code connections} is null until a read succeeded; {@code readError} is the last refusal (the previous connections, if any, stay). */
	public static final class Entry
	{
		public final ObjectNode connections;
		public final String readError;
		public final long readAt;
		/** Null = never tested by this person; the probe result otherwise. */
		public final Boolean warehouseReachable;
		public final String warehouseDetail;
		public final String warehouseConnectorId;

		Entry(final ObjectNode pConnections, final String pReadError, final long pReadAt, final Boolean pReachable, final String pDetail, final String pWarehouseConnector)
		{
			this.connections = pConnections;
			this.readError = pReadError;
			this.readAt = pReadAt;
			this.warehouseReachable = pReachable;
			this.warehouseDetail = pDetail;
			this.warehouseConnectorId = pWarehouseConnector;
		}
	}

	/** What {@link #lookup} found: the entry (null = nothing read yet) and whether it is within the age a display accepts without asking again. */
	public static final class Found
	{
		public final Entry entry;
		public final boolean fresh;

		Found(final Entry pEntry, final boolean pFresh)
		{
			this.entry = pEntry;
			this.fresh = pFresh;
		}
	}

	private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
	private final Set<String> reading = Collections.synchronizedSet(new HashSet<String>());
	private final Clock clock;
	private final ThreadPoolExecutor refresher;

	DistributionStepFacts(final Clock pClock)
	{
		this.clock = pClock;
		this.refresher = DistributionStepFacts.newRefresher();
	}

	private static final DistributionStepFacts INSTANCE = new DistributionStepFacts(new Clock()
	{
		@Override
		public long now()
		{
			return System.currentTimeMillis();
		}
	});

	/** The module-wide cache the wizard uses. */
	public static DistributionStepFacts shared()
	{
		return DistributionStepFacts.INSTANCE;
	}

	/** The age limit in milliseconds: {@value #AGE_PROPERTY} seconds, 300 when absent or not a positive number. */
	public static long maxAgeMillis()
	{
		long seconds = DistributionStepFacts.DEFAULT_AGE_SECONDS;
		final String raw = System.getProperty(DistributionStepFacts.AGE_PROPERTY);
		if (raw != null && !raw.trim().isEmpty())
		{
			try
			{
				final long parsed = Long.parseLong(raw.trim());
				if (parsed > 0L)
				{
					seconds = parsed;
				}
			}
			catch (final NumberFormatException ex)
			{
				LoggingCategory.getKernel().warn("[mdm-factory] ignoring " + DistributionStepFacts.AGE_PROPERTY + "=" + raw + " (not a whole number of seconds); using "
					+ DistributionStepFacts.DEFAULT_AGE_SECONDS);
			}
		}
		return seconds * 1000L;
	}

	static String key(final String pViewerId, final String pProjectCode)
	{
		return (pViewerId == null ? "" : pViewerId) + "\u0000" + (pProjectCode == null ? "" : pProjectCode);
	}

	/** What this person last read for this project, with no call at all. */
	public synchronized Found lookup(final String pViewerId, final String pProjectCode, final long pMaxAgeMillis)
	{
		final Entry entry = this.entries.get(DistributionStepFacts.key(pViewerId, pProjectCode));
		if (entry == null)
		{
			return new Found(null, false);
		}
		return new Found(entry, Math.max(0L, this.clock.now() - entry.readAt) <= pMaxAgeMillis);
	}

	/** Whether a background read for this person and project is queued or running (the page then says "reading"). */
	public boolean isReading(final String pViewerId, final String pProjectCode)
	{
		return this.reading.contains(DistributionStepFacts.key(pViewerId, pProjectCode));
	}

	private synchronized void store(final String pKey, final Entry pEntry)
	{
		this.entries.remove(pKey);
		this.entries.put(pKey, pEntry);
		while (this.entries.size() > DistributionStepFacts.MAX_ENTRIES)
		{
			final Iterator<String> oldest = this.entries.keySet().iterator();
			oldest.next();
			oldest.remove();
		}
	}

	/** Reads the connections now, on the calling thread, and stores them. The background thread calls this; so does an explicit action that needs the facts at once. Never throws. */
	public Entry refreshNow(final String pViewerId, final String pProjectCode, final Reader pReader)
	{
		final String key = DistributionStepFacts.key(pViewerId, pProjectCode);
		final Entry previous;
		synchronized (this)
		{
			previous = this.entries.get(key);
		}
		Entry fresh;
		try
		{
			final ObjectNode connections = pReader.readConnections();
			// a new list invalidates a probe of a connection that is no longer the project's warehouse
			final String warehouse = DistributionStepFacts.snowflakeConnectorId(connections);
			final boolean keepProbe = previous != null && warehouse != null && warehouse.equals(previous.warehouseConnectorId);
			fresh = new Entry(connections, null, this.clock.now(), keepProbe ? previous.warehouseReachable : null, keepProbe ? previous.warehouseDetail : "", warehouse);
		}
		catch (final OperationException | RuntimeException ex)
		{
			fresh = new Entry(previous == null ? null : previous.connections, String.valueOf(ex.getMessage()), this.clock.now(),
				previous == null ? null : previous.warehouseReachable, previous == null ? "" : previous.warehouseDetail,
				previous == null ? null : previous.warehouseConnectorId);
		}
		this.store(key, fresh);
		return fresh;
	}

	/**
	 * Probes the warehouse (explicit action only: the probe wakes a suspended warehouse), on the calling thread, and stores the answer in the entry. Reads the
	 * connections first when nothing is cached. Never throws; without a warehouse connection the entry just says so.
	 */
	public Entry probeNow(final String pViewerId, final String pProjectCode, final Reader pReader)
	{
		Found found = this.lookup(pViewerId, pProjectCode, Long.MAX_VALUE);
		if (found.entry == null || found.entry.connections == null)
		{
			this.refreshNow(pViewerId, pProjectCode, pReader);
			found = this.lookup(pViewerId, pProjectCode, Long.MAX_VALUE);
		}
		final Entry entry = found.entry;
		final String warehouse = entry == null || entry.connections == null ? null : DistributionStepFacts.snowflakeConnectorId(entry.connections);
		if (warehouse == null)
		{
			return entry;
		}
		Boolean reachable;
		String detail;
		try
		{
			final ObjectNode probe = pReader.probeWarehouse(warehouse);
			reachable = Boolean.valueOf(probe.path("reachable").asBoolean(false));
			detail = probe.path("detail").asText("");
		}
		catch (final RuntimeException ex)
		{
			reachable = Boolean.FALSE;
			detail = String.valueOf(ex.getMessage());
		}
		final Entry probed = new Entry(entry.connections, entry.readError, entry.readAt, reachable, detail, warehouse);
		this.store(DistributionStepFacts.key(pViewerId, pProjectCode), probed);
		return probed;
	}

	/**
	 * Asks for a read on a background thread. At most one per (person, project) is queued or running; a full queue refuses (the display asks again at its next poll).
	 * Never throws, never waits: it is called from a display.
	 *
	 * @return true when a read was queued
	 */
	public boolean refreshInBackground(final String pViewerId, final String pProjectCode, final Reader pReader)
	{
		final String key = DistributionStepFacts.key(pViewerId, pProjectCode);
		if (!this.reading.add(key))
		{
			return false;
		}
		try
		{
			this.refresher.execute(new Runnable()
			{
				@Override
				public void run()
				{
					try
					{
						DistributionStepFacts.this.refreshNow(pViewerId, pProjectCode, pReader);
					}
					catch (final RuntimeException | LinkageError ex)
					{
						LoggingCategory.getKernel().warn("[mdm-factory] background read of the Connectors connections failed: " + ex);
					}
					finally
					{
						DistributionStepFacts.this.reading.remove(key);
					}
				}
			});
			return true;
		}
		catch (final RuntimeException ex)
		{
			this.reading.remove(key);
			return false;
		}
	}

	/** Test hook: waits until no read is queued or running. */
	boolean awaitIdle(final long pTimeoutMillis) throws InterruptedException
	{
		final long end = System.currentTimeMillis() + pTimeoutMillis;
		while (!this.reading.isEmpty())
		{
			if (System.currentTimeMillis() > end)
			{
				return false;
			}
			Thread.sleep(5L);
		}
		return true;
	}

	/** The id of the first active Snowflake connection of {@code pConnections} ({@code connectors_list} already leaves out inactive ones), or null. */
	public static String snowflakeConnectorId(final JsonNode pConnections)
	{
		if (pConnections == null)
		{
			return null;
		}
		for (final JsonNode connection : pConnections.path("connections"))
		{
			if ("snowflake".equalsIgnoreCase(connection.path("type").asText("")))
			{
				final String id = connection.path("connector").asText("");
				if (!id.isEmpty())
				{
					return id;
				}
			}
		}
		return null;
	}

	/**
	 * The {@code warehouses} object {@link DataProductPublication#detect(Session, com.onwbp.adaptation.Adaptation, String, ObjectNode)} takes, built from the cache and
	 * nothing else: {@code snowflake.tested} is false until a person ran the probe, and {@code reachable} then stays false.
	 */
	public static ObjectNode warehousesJson(final Entry pEntry)
	{
		final ObjectNode out = DistributionStepFacts.MAPPER.createObjectNode();
		final ObjectNode snowflake = out.putObject("snowflake");
		final String warehouse = pEntry == null || pEntry.connections == null ? null : DistributionStepFacts.snowflakeConnectorId(pEntry.connections);
		snowflake.put("present", warehouse != null);
		if (warehouse != null)
		{
			snowflake.put("connectorId", warehouse);
			snowflake.put("tested", pEntry.warehouseReachable != null);
			snowflake.put("reachable", Boolean.TRUE.equals(pEntry.warehouseReachable));
			snowflake.put("detail", pEntry.warehouseReachable == null ? "Not tested: use \"Test the connection\"." : pEntry.warehouseDetail);
		}
		else
		{
			snowflake.put("tested", false);
			snowflake.put("detail", pEntry == null || pEntry.connections == null ? "Connections not read yet." : "No active Snowflake connection is configured.");
		}
		final ObjectNode databricks = out.putObject("databricks");
		databricks.put("present", false);
		databricks.put("detail", "Not supported yet: ebx-connectors has no TablePublisher implementation for Databricks (checked 2026-09-24 - only SnowflakeConnector implements that interface).");
		return out;
	}

	/** One thread, a short queue, threads that die when idle and carry the web application's class loader (CLAUDE.md sec 1: a pool thread's loader is not the module's). */
	private static ThreadPoolExecutor newRefresher()
	{
		final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 20L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16), new ThreadFactory()
		{
			@Override
			public Thread newThread(final Runnable pTask)
			{
				final Thread thread = new Thread(pTask, "mdm-factory-distribution-connections");
				thread.setDaemon(true);
				thread.setContextClassLoader(DistributionStepFacts.class.getClassLoader());
				return thread;
			}
		}, new ThreadPoolExecutor.AbortPolicy());
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}
}
