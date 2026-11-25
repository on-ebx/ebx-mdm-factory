package com.on.ebx.factory.ebx;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orchestranetworks.service.OperationException;

/**
 * JUnit-free: run the main, a failure throws. Audit F-2-101 (lot B3): the display of step 7 never calls Connectors; a counting fake stands for the bridge and
 * the cache, the explicit refresh, the explicit probe, the per-person key and the expiry are checked, then the wizard's source is scanned to prove the display
 * path (buildDistribution / buildDistributionProduct) holds none of the former calls.
 */
public final class DistributionStepFactsTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		DistributionStepFactsTest.aDisplayNeverCallsTheBridge();
		DistributionStepFactsTest.oneCallSeriesPerExplicitRefresh();
		DistributionStepFactsTest.theProbeOnlyRunsOnAnExplicitTest();
		DistributionStepFactsTest.eachPersonHasHisOwnFacts();
		DistributionStepFactsTest.factsExpireButStillShow();
		DistributionStepFactsTest.aFailedReadKeepsTheOldConnections();
		DistributionStepFactsTest.theDisplayPathOfTheWizardHoldsNoConnectorsCall();
		System.out.println("DistributionStepFactsTest OK");
	}

	private static final class FakeClock implements DistributionStepFacts.Clock
	{
		long now = 1000000L;

		@Override
		public long now()
		{
			return this.now;
		}
	}

	private static final class CountingReader implements DistributionStepFacts.Reader
	{
		final AtomicInteger reads = new AtomicInteger();
		final AtomicInteger probes = new AtomicInteger();
		boolean fail;

		@Override
		public ObjectNode readConnections() throws OperationException
		{
			this.reads.incrementAndGet();
			if (this.fail)
			{
				throw new IllegalStateException("Connectors is down");
			}
			final ObjectNode out = DistributionStepFactsTest.MAPPER.createObjectNode();
			final ArrayNode connections = out.putArray("connections");
			connections.addObject().put("connector", "KAFKA1").put("type", "kafka");
			connections.addObject().put("connector", "SNOW1").put("type", "snowflake");
			return out;
		}

		@Override
		public ObjectNode probeWarehouse(final String pConnectorId)
		{
			this.probes.incrementAndGet();
			final ObjectNode out = DistributionStepFactsTest.MAPPER.createObjectNode();
			out.put("reachable", true);
			out.put("detail", "ok " + pConnectorId);
			return out;
		}
	}

	private static void aDisplayNeverCallsTheBridge()
	{
		final CountingReader reader = new CountingReader();
		final DistributionStepFacts facts = new DistributionStepFacts(new FakeClock());
		for (int i = 0; i < 5; i++)
		{
			final DistributionStepFacts.Found found = facts.lookup("alice", "P", 300000L);
			check("empty cache: nothing, not fresh", found.entry == null && !found.fresh);
		}
		check("no call at all from five displays", reader.reads.get() == 0 && reader.probes.get() == 0);
		final DistributionStepFacts.Entry nothing = null;
		check("warehouses of an empty cache say present=false, untested", !DistributionStepFacts.warehousesJson(nothing).path("snowflake").path("present").asBoolean(true)
			&& !DistributionStepFacts.warehousesJson(nothing).path("snowflake").path("tested").asBoolean(true));
	}

	private static void oneCallSeriesPerExplicitRefresh() throws Exception
	{
		final CountingReader reader = new CountingReader();
		final DistributionStepFacts facts = new DistributionStepFacts(new FakeClock());
		check("queued", facts.refreshInBackground("alice", "P", reader));
		check("idle", facts.awaitIdle(5000L));
		check("exactly one read for one refresh", reader.reads.get() == 1);
		for (int i = 0; i < 4; i++)
		{
			check("displays after the read call nothing", facts.lookup("alice", "P", 300000L).fresh);
		}
		check("still one read after four displays", reader.reads.get() == 1);
		check("no probe by a refresh", reader.probes.get() == 0);
		facts.refreshNow("alice", "P", reader);
		check("a second explicit refresh is a second read", reader.reads.get() == 2);
	}

	private static void theProbeOnlyRunsOnAnExplicitTest()
	{
		final CountingReader reader = new CountingReader();
		final DistributionStepFacts facts = new DistributionStepFacts(new FakeClock());
		facts.refreshNow("alice", "P", reader);
		final ObjectNode before = DistributionStepFacts.warehousesJson(facts.lookup("alice", "P", 1L).entry).path("snowflake").deepCopy();
		check("snowflake present, not tested, not reachable before the test", before.path("present").asBoolean() && !before.path("tested").asBoolean() && !before.path("reachable").asBoolean());
		check("still no probe", reader.probes.get() == 0);
		facts.probeNow("alice", "P", reader);
		check("one probe for one explicit test", reader.probes.get() == 1);
		final ObjectNode after = DistributionStepFacts.warehousesJson(facts.lookup("alice", "P", 1L).entry).path("snowflake").deepCopy();
		check("tested and reachable after it", after.path("tested").asBoolean() && after.path("reachable").asBoolean() && "SNOW1".equals(after.path("connectorId").asText()));
		facts.refreshNow("alice", "P", reader);
		check("a refresh of the list keeps the last test of the same warehouse", facts.lookup("alice", "P", 1L).entry.warehouseReachable != null && reader.probes.get() == 1);
	}

	private static void eachPersonHasHisOwnFacts()
	{
		final CountingReader reader = new CountingReader();
		final DistributionStepFacts facts = new DistributionStepFacts(new FakeClock());
		facts.refreshNow("alice", "P", reader);
		check("alice sees her read", facts.lookup("alice", "P", 300000L).entry != null);
		check("bob sees nothing of it", facts.lookup("bob", "P", 300000L).entry == null);
		check("alice sees nothing for another project", facts.lookup("alice", "Q", 300000L).entry == null);
	}

	private static void factsExpireButStillShow()
	{
		final FakeClock clock = new FakeClock();
		final CountingReader reader = new CountingReader();
		final DistributionStepFacts facts = new DistributionStepFacts(clock);
		facts.refreshNow("alice", "P", reader);
		clock.now += 300000L;
		check("at the limit still fresh", facts.lookup("alice", "P", 300000L).fresh);
		clock.now += 1L;
		final DistributionStepFacts.Found found = facts.lookup("alice", "P", 300000L);
		check("a millisecond past it: expired but still there to show", !found.fresh && found.entry != null);
		check("default age is five minutes", DistributionStepFacts.maxAgeMillis() == 300000L);
		System.setProperty(DistributionStepFacts.AGE_PROPERTY, "42");
		check("the JVM property sets it", DistributionStepFacts.maxAgeMillis() == 42000L);
		System.setProperty(DistributionStepFacts.AGE_PROPERTY, "nonsense");
		check("a bad value falls back", DistributionStepFacts.maxAgeMillis() == 300000L);
		System.clearProperty(DistributionStepFacts.AGE_PROPERTY);
	}

	private static void aFailedReadKeepsTheOldConnections()
	{
		final CountingReader reader = new CountingReader();
		final DistributionStepFacts facts = new DistributionStepFacts(new FakeClock());
		facts.refreshNow("alice", "P", reader);
		reader.fail = true;
		final DistributionStepFacts.Entry failed = facts.refreshNow("alice", "P", reader);
		check("the refusal is kept as text and the old connections stay", failed.readError != null && failed.readError.contains("down") && failed.connections != null);
		final CountingReader other = new CountingReader();
		other.fail = true;
		final DistributionStepFacts.Entry never = new DistributionStepFacts(new FakeClock()).refreshNow("a", "P", other);
		check("a first read that fails leaves an empty state with the reason", never.connections == null && never.readError != null);
	}

	private static void theDisplayPathOfTheWizardHoldsNoConnectorsCall() throws Exception
	{
		final String dir = System.getProperty("mdmf.sourceDir");
		check("mdmf.sourceDir is set", dir != null);
		final String source = new String(Files.readAllBytes(new File(dir, "com/on/ebx/factory/ebx/ui/ProjectWizardUserService.java").toPath()), StandardCharsets.UTF_8);
		for (final String method : new String[] { "private void buildDistribution(", "private void buildDistributionProduct(" })
		{
			final int start = source.indexOf(method);
			check(method + " found", start > 0);
			final int end = source.indexOf("\n\tprivate ", start + method.length());
			final String body = source.substring(start, end > 0 ? end : source.length());
			check(method + " calls no listCandidateConnections", !body.contains("listCandidateConnections"));
			check(method + " calls no probe", !body.contains("probeWarehouse") && !body.contains("testConnection"));
			check(method + " uses no live detect", !body.contains("DataProductPublication.detect(this.session, this.factory, pSnapshot.project.code)"));
			check(method + " never reads Connectors", !body.contains("McpServers") && !body.contains("callTool"));
		}
		final String publication = new String(Files.readAllBytes(new File(dir, "com/on/ebx/factory/ebx/DataProductPublication.java").toPath()), StandardCharsets.UTF_8);
		final int plan = publication.indexOf("public static ObjectNode plan(");
		final int create = publication.indexOf("public static ObjectNode create(");
		check("plan holds no Connectors call", !publication.substring(plan, create).contains("McpServers"));
		final int detect = publication.indexOf("public static ObjectNode detect(final Session pSession, final Adaptation pFactory, final String pProjectCode, final ObjectNode");
		final int warehouses = publication.indexOf("public static ObjectNode probeWarehouse(");
		check("the four-argument detect holds no Connectors call", detect > 0 && !publication.substring(detect, warehouses).contains("McpServers"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError("DistributionStepFactsTest: " + pWhat);
		}
	}
}
