package com.on.ebx.factory.ebx;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.distribution.DistributionBatches;
import com.orchestranetworks.service.OperationException;

/**
 * JUnit-free: run the main, a failure throws. Wave 13 (2026-10-12, "asynchronous distribution send"): one round of a distribution send
 * ({@link DistributionSendCalls}) against a scripted ebx-connectors - the followed asynchronous run (start, polls, terminal answer), a stop asked
 * of the job between polls, a Connectors that ignores {@code async} (the complete synchronous answer), a Connectors that refuses {@code async} for a
 * scope send (the synchronous fallback, with its own client), the absolute limit, and the question "is this run still running" the double-send guard
 * asks. No EBX repository, no sleep longer than a few milliseconds.
 */
public final class DistributionSendCallsTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Plays the Connectors MCP server: each tool has a queue of scripted answers (the last one repeats), every call is recorded with its arguments. */
	private static final class FakeConnectors implements McpClient.Transport
	{
		private final Map<String, Deque<String>> script = new HashMap<String, Deque<String>>();
		private final Map<String, String> last = new HashMap<String, String>();
		final List<String> calls = new ArrayList<String>();
		final List<JsonNode> arguments = new ArrayList<JsonNode>();
		private final String name;

		FakeConnectors(final String pName)
		{
			this.name = pName;
		}

		/** An answer: {@code structured} JSON; a text starting with "!" is an error result {@code {"error": text}}. */
		FakeConnectors then(final String pTool, final String pAnswer)
		{
			Deque<String> queue = this.script.get(pTool);
			if (queue == null)
			{
				queue = new ArrayDeque<String>();
				this.script.put(pTool, queue);
			}
			queue.add(pAnswer);
			return this;
		}

		@Override
		public String exchange(final String pRequest) throws McpException
		{
			try
			{
				final JsonNode message = DistributionSendCallsTest.MAPPER.readTree(pRequest);
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
				final String tool = message.path("params").path("name").asText();
				this.calls.add(tool);
				this.arguments.add(message.path("params").path("arguments"));
				final Deque<String> queue = this.script.get(tool);
				String answer = queue == null ? null : queue.poll();
				if (answer == null)
				{
					answer = this.last.get(tool);
				}
				if (answer == null)
				{
					throw new McpException("no scripted answer for " + tool + " on " + this.name, null);
				}
				this.last.put(tool, answer);
				final boolean error = answer.startsWith("!");
				final String structured = error ? "{\"error\":\"" + answer.substring(1) + "\"}" : answer;
				final ObjectNode result = DistributionSendCallsTest.MAPPER.createObjectNode();
				result.putArray("content").addObject().put("type", "text").put("text", structured);
				result.set("structuredContent", DistributionSendCallsTest.MAPPER.readTree(structured));
				result.put("isError", error);
				return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
			}
			catch (final java.io.IOException ex)
			{
				throw new McpException(ex.getMessage(), ex);
			}
		}

		@Override
		public String describe()
		{
			return this.name;
		}

		int count(final String pTool)
		{
			int n = 0;
			for (final String call : this.calls)
			{
				if (call.equals(pTool))
				{
					n++;
				}
			}
			return n;
		}

		JsonNode firstArguments(final String pTool)
		{
			return this.arguments.get(this.calls.indexOf(pTool));
		}
	}

	private static final String ACK = "{\"runId\":\"R20261012-100000-0001\",\"status\":\"RUNNING\",\"mapping\":412,\"async\":true}";
	private static final String RUNNING = "{\"runId\":\"R20261012-100000-0001\",\"execution\":{\"status\":\"RUNNING\",\"created\":20000,\"errorCount\":0}}";
	private static final String REFUSED = "!async only applies to an inbound feed with no key, predicate or scope";

	public static void main(final String[] pArgs) throws Exception
	{
		DistributionSendCallsTest.followedRun();
		DistributionSendCallsTest.cutRoundIsAskedAgainForChanged();
		DistributionSendCallsTest.failedRun();
		DistributionSendCallsTest.stopBetweenPolls();
		DistributionSendCallsTest.stopOfARunThatDoesNotEndInTime();
		DistributionSendCallsTest.unchangedRoundIsNotSent();
		DistributionSendCallsTest.olderConnectorsIgnoringAsync();
		DistributionSendCallsTest.connectorsRefusingAsync();
		DistributionSendCallsTest.anotherRefusalIsAFailure();
		DistributionSendCallsTest.absoluteLimit();
		DistributionSendCallsTest.runningQuestion();
		DistributionSendStopMessage.check();
		System.out.println("DistributionSendCallsTest OK");
	}

	private static ObjectNode args(final String pMapping, final String pScope)
	{
		final ObjectNode args = MAPPER.createObjectNode();
		args.put("mapping", pMapping);
		args.put("scope", pScope);
		args.put("dryRun", false);
		args.put("max", 100000);
		return args;
	}

	private static DistributionSendCalls calls(final McpClient pClient, final DistributionSendCalls.ClientSource pSync, final FactoryJobs.Handle pHandle)
	{
		return new DistributionSendCalls(pClient, pSync, pHandle, 2L, 60000L);
	}

	private static final DistributionSendCalls.ClientSource NO_SYNC = new DistributionSendCalls.ClientSource()
	{
		@Override
		public McpClient syncClient()
		{
			throw new AssertionError("the synchronous client must not be built for a followed run");
		}
	};

	private static void followedRun() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors("ordinary").then("connectors_sync", ACK).then("connectors_run_get", RUNNING)
			.then("connectors_run_get", "{\"runId\":\"R20261012-100000-0001\",\"execution\":{\"status\":\"DONE\",\"created\":88991,\"errorCount\":0,\"truncated\":false}}");
		final List<String> recorded = new ArrayList<String>();
		final ObjectNode tableDetail = MAPPER.createObjectNode();
		final DistributionSendCalls.Round round = DistributionSendCallsTest.calls(new McpClient(fake), NO_SYNC, null).round(DistributionSendCallsTest.args("412", "all"), tableDetail,
			new DistributionSendCalls.Recorder()
			{
				@Override
				public void runStarted(final String pRunId)
				{
					recorded.add(pRunId);
				}
			});
		check("the start asks for async, on the ordinary client", fake.firstArguments("connectors_sync").path("async").asBoolean(false)
			&& "412".equals(fake.firstArguments("connectors_sync").path("mapping").asText()) && "all".equals(fake.firstArguments("connectors_sync").path("scope").asText()));
		check("the run id is recorded before the wait", recorded.size() == 1 && "R20261012-100000-0001".equals(recorded.get(0)) && "R20261012-100000-0001".equals(tableDetail.path("connectorsRunId").asText()));
		check("two polls to the terminal state, the log not read", fake.count("connectors_run_get") == 2 && fake.arguments.get(2).path("callsMax").asInt(-1) == 0);
		check("the terminal answer is the outcome the send reads", round.async && !round.stoppedByHandle && round.outcome.ok && round.outcome.rows == 88991L && !round.outcome.truncated
			&& "R20261012-100000-0001".equals(round.outcome.runId));
		check("the detail says the run was followed", tableDetail.path("async").asBoolean(false) && "DONE".equals(tableDetail.path("runStatus").asText()));
	}

	private static void cutRoundIsAskedAgainForChanged() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors("ordinary").then("connectors_sync", ACK)
			.then("connectors_run_get", "{\"runId\":\"R20261012-100000-0001\",\"execution\":{\"status\":\"PARTIAL\",\"updated\":100000,\"errorCount\":0,\"truncated\":true,\"available\":130000}}");
		final DistributionSendCalls.Round round = DistributionSendCallsTest.calls(new McpClient(fake), NO_SYNC, null).round(DistributionSendCallsTest.args("412", "changed"),
			MAPPER.createObjectNode(), new NoRecord());
		check("a PARTIAL cut round is ok and truncated", round.outcome.ok && round.outcome.truncated && round.outcome.rows == 100000L);
		check("DistributionBatches.askAgain still re-asks a truncated changed round", DistributionBatches.askAgain("changed", round.outcome.truncated, round.outcome.rows, 1));
		check("and never a truncated all round", !DistributionBatches.askAgain("all", round.outcome.truncated, round.outcome.rows, 1));
	}

	private static void failedRun() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors("ordinary").then("connectors_sync", ACK).then("connectors_run_get",
			"{\"runId\":\"R20261012-100000-0001\",\"execution\":{\"status\":\"ERROR\",\"created\":10,\"errorCount\":7,\"firstErrors\":\"88: Kafka publish to mdm.x failed\"}}");
		final DistributionSendCalls.Round round = DistributionSendCallsTest.calls(new McpClient(fake), NO_SYNC, null).round(DistributionSendCallsTest.args("412", "all"),
			MAPPER.createObjectNode(), new NoRecord());
		check("an ERROR run is a failed outcome carrying the first error: " + round.outcome.message, !round.outcome.ok && round.outcome.message.contains("Kafka publish to mdm.x failed")
			&& !round.stoppedByHandle);
	}

	private static void stopBetweenPolls() throws Exception
	{
		// live, Vol100k 2026-10-12: the run read right after the stop request still showed no counters, its final STOPPED answer had updated 13000
		final FakeConnectors fake = new FakeConnectors("ordinary").then("connectors_sync", ACK).then("connectors_run_get", RUNNING)
			.then("connectors_run_stop", "{\"runId\":\"R20261012-100000-0001\",\"requested\":true}")
			.then("connectors_run_get", "{\"runId\":\"R20261012-100000-0001\",\"execution\":{\"status\":\"RUNNING\",\"errorCount\":0}}")
			.then("connectors_run_get", "{\"runId\":\"R20261012-100000-0001\",\"execution\":{\"status\":\"STOPPED\",\"updated\":13000,\"errorCount\":0,"
				+ "\"message\":\"Send stopped (connectors_run_stop) after 13000 record(s)\"}}");
		final FactoryJobs.Handle handle = new FactoryJobs.Handle("5", "DISTRIBUTION", "DISTRIBUTION_SEND_RUN", "admin", 0L);
		handle.cancel("stopped by admin");
		final ObjectNode tableDetail = MAPPER.createObjectNode();
		final DistributionSendCalls.Round round = DistributionSendCallsTest.calls(new McpClient(fake), NO_SYNC, handle).round(DistributionSendCallsTest.args("412", "all"), tableDetail,
			new NoRecord());
		check("a stop asked of the job stops the Connectors run", fake.count("connectors_run_stop") == 1
			&& "R20261012-100000-0001".equals(fake.firstArguments("connectors_run_stop").path("runId").asText()));
		check("the run is read until it is STOPPED (the stop only asks)", fake.count("connectors_run_get") == 3);
		check("the round ends STOPPED with the rows of the final counters: " + round.outcome.message, round.stoppedByHandle && !round.outcome.ok && round.outcome.rows == 13000L
			&& round.outcome.sent == 13000L && round.outcome.unchanged == 0L && "R20261012-100000-0001".equals(round.outcome.runId));
		check("the message names the 13000 rows, the reason and what a changed send does: " + round.outcome.message, round.outcome.message.contains("stopped by admin")
			&& round.outcome.message.contains("after 13000 row(s) sent") && round.outcome.message.contains("keep their outbound state") && round.outcome.message.contains("'changed' send resumes")
			&& !round.outcome.message.contains("may be short"));
		check("the detail says the stop was requested and the run ended STOPPED", tableDetail.path("stopRequested").asBoolean(false) && "STOPPED".equals(tableDetail.path("runStatus").asText())
			&& !tableDetail.has("stopPending"));
	}

	private static void stopOfARunThatDoesNotEndInTime() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors("ordinary").then("connectors_sync", ACK).then("connectors_run_get", RUNNING)
			.then("connectors_run_stop", "{\"runId\":\"R20261012-100000-0001\",\"requested\":true}");
		final FactoryJobs.Handle handle = new FactoryJobs.Handle("5", "DISTRIBUTION", "DISTRIBUTION_SEND_RUN", "admin", 0L);
		handle.cancel("stopped by admin");
		final ObjectNode tableDetail = MAPPER.createObjectNode();
		final DistributionSendCalls.Round round = DistributionSendCallsTest.calls(new McpClient(fake), NO_SYNC, handle).round(DistributionSendCallsTest.args("412", "all"), tableDetail,
			new NoRecord());
		check("the wait is bounded: the last counters are used and the message says they may be short: " + round.outcome.message, round.stoppedByHandle && round.outcome.rows == 20000L
			&& round.outcome.message.contains("after 20000 row(s) sent") && round.outcome.message.contains("may be short") && tableDetail.path("stopPending").asBoolean(false)
			&& fake.count("connectors_run_get") == 1 + DistributionSendCalls.STOP_WAIT_POLLS);
	}

	private static void unchangedRoundIsNotSent() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors("ordinary").then("connectors_sync", ACK).then("connectors_run_get",
			"{\"runId\":\"R20261012-100000-0001\",\"execution\":{\"status\":\"DONE\",\"noChange\":146559,\"errorCount\":0,\"truncated\":false}}");
		final DistributionSendCalls.Round round = DistributionSendCallsTest.calls(new McpClient(fake), NO_SYNC, null).round(DistributionSendCallsTest.args("412", "changed"),
			MAPPER.createObjectNode(), new NoRecord());
		check("a DONE run of noChange only covers the rows but sent none", round.outcome.ok && round.outcome.rows == 146559L && round.outcome.sent == 0L && round.outcome.unchanged == 146559L);
	}

	private static void olderConnectorsIgnoringAsync() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors("ordinary").then("connectors_sync",
			"{\"status\":\"OK\",\"count\":57568,\"errors\":0,\"runId\":\"R20261012-090000-0007\",\"truncated\":false}");
		final ObjectNode tableDetail = MAPPER.createObjectNode();
		final DistributionSendCalls.Round round = DistributionSendCallsTest.calls(new McpClient(fake), NO_SYNC, null).round(DistributionSendCallsTest.args("411", "all"), tableDetail,
			new DistributionSendCalls.Recorder()
			{
				@Override
				public void runStarted(final String pRunId)
				{
					throw new AssertionError("no run was followed");
				}
			});
		check("the synchronous answer is read directly", !round.async && round.outcome.ok && round.outcome.rows == 57568L && "R20261012-090000-0007".equals(round.outcome.runId));
		check("without results[] the whole count reads as sent (approximate)", round.outcome.sent == 57568L && round.outcome.unchanged == 0L);
		check("no poll was made", fake.count("connectors_run_get") == 0 && fake.count("connectors_sync") == 1);
		check("the detail says it was not followed", !tableDetail.path("async").asBoolean(true));
	}

	private static void connectorsRefusingAsync() throws Exception
	{
		final FakeConnectors ordinary = new FakeConnectors("ordinary").then("connectors_sync", REFUSED);
		final FakeConnectors longRead = new FakeConnectors("long-read").then("connectors_sync",
			"{\"status\":\"OK\",\"count\":88991,\"errors\":0,\"runId\":\"R20261012-100000-0002\",\"truncated\":false}");
		final int[] built = new int[1];
		final DistributionSendCalls sender = DistributionSendCallsTest.calls(new McpClient(ordinary), new DistributionSendCalls.ClientSource()
		{
			@Override
			public McpClient syncClient() throws OperationException
			{
				built[0]++;
				return new McpClient(longRead);
			}
		}, null);
		final ObjectNode tableDetail = MAPPER.createObjectNode();
		final DistributionSendCalls.Round first = sender.round(DistributionSendCallsTest.args("412", "all"), tableDetail, new NoRecord());
		check("the refusal is not a failure: the round is sent synchronously", !first.async && first.outcome.ok && first.outcome.rows == 88991L);
		check("async was asked once on the ordinary client, then the call went to the long-read client without it", ordinary.count("connectors_sync") == 1
			&& longRead.count("connectors_sync") == 1 && !longRead.firstArguments("connectors_sync").has("async") && built[0] == 1);
		check("the detail says async was refused", tableDetail.path("asyncRefused").asBoolean(false) && !sender.asyncSupported());
		final DistributionSendCalls.Round second = sender.round(DistributionSendCallsTest.args("413", "all"), MAPPER.createObjectNode(), new NoRecord());
		check("the next round does not ask again: straight to the synchronous client, built once", second.outcome.ok && ordinary.count("connectors_sync") == 1
			&& longRead.count("connectors_sync") == 2 && built[0] == 1);
	}

	private static void anotherRefusalIsAFailure() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors("ordinary").then("connectors_sync", "!No mapping '999'");
		final DistributionSendCalls sender = DistributionSendCallsTest.calls(new McpClient(fake), NO_SYNC, null);
		final DistributionSendCalls.Round round = sender.round(DistributionSendCallsTest.args("999", "all"), MAPPER.createObjectNode(), new NoRecord());
		check("any other refusal is a failed outcome, not a fallback: " + round.outcome.message, !round.outcome.ok && round.outcome.message.contains("No mapping '999'")
			&& sender.asyncSupported());
	}

	private static void absoluteLimit() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors("ordinary").then("connectors_sync", ACK).then("connectors_run_get", RUNNING)
			.then("connectors_run_stop", "{\"runId\":\"R20261012-100000-0001\",\"requested\":true}");
		final DistributionSendCalls sender = new DistributionSendCalls(new McpClient(fake), NO_SYNC, null, 20L, 30L);
		try
		{
			sender.round(DistributionSendCallsTest.args("412", "all"), MAPPER.createObjectNode(), new NoRecord());
			throw new AssertionError("a run that never ends must fail the round");
		}
		catch (final EngineRefusal ex)
		{
			check("the limit refusal is coded and names the run: " + ex.getMessage(), "engine.external.sendRunTimeout".equals(ex.code())
				&& ex.getMessage().contains("R20261012-100000-0001") && fake.count("connectors_run_stop") == 1);
		}
	}

	private static void runningQuestion() throws Exception
	{
		final FakeConnectors fake = new FakeConnectors("ordinary").then("connectors_run_get", RUNNING);
		check("the guard's question: a RUNNING run is running", DistributionRunner.runIsRunning(new McpClient(fake), "R20261012-100000-0001"));
		check("it asks without the log", fake.arguments.get(fake.arguments.size() - 1).path("callsMax").asInt(-1) == 0);
		final FakeConnectors done = new FakeConnectors("ordinary").then("connectors_run_get", "{\"runId\":\"R1\",\"execution\":{\"status\":\"DONE\"}}");
		check("a finished run is not", !DistributionRunner.runIsRunning(new McpClient(done), "R1"));
		final FakeConnectors gone = new FakeConnectors("ordinary").then("connectors_run_get", "!No run 'R0'");
		check("a run Connectors does not know does not block a send", !DistributionRunner.runIsRunning(new McpClient(gone), "R0"));
		final FakeConnectors outbound = new FakeConnectors("ordinary").then("connectors_run_get", "{\"runId\":\"R5\",\"count\":3,\"calls\":[]}");
		check("a run without state (an outbound run of the Connectors of 2026-10-12) does not block either", !DistributionRunner.runIsRunning(new McpClient(outbound), "R5"));
		final FakeConnectors broken = new FakeConnectors("ordinary");
		check("an unreachable Connectors does not block", !DistributionRunner.runIsRunning(new McpClient(broken), "R5"));
	}

	private static final class NoRecord implements DistributionSendCalls.Recorder
	{
		@Override
		public void runStarted(final String pRunId)
		{
			// nothing to record in a test
		}
	}

	/** The stop route's sentence (pure). */
	private static final class DistributionSendStopMessage
	{
		static void check()
		{
			final List<String> none = new ArrayList<String>();
			DistributionSendCallsTest.check("nothing running", DistributionSendStop.message("3", none, none).equals("No send of target 3 is running."));
			final List<String> jobs = new ArrayList<String>();
			jobs.add("41");
			final List<String> runs = new ArrayList<String>();
			runs.add("R1");
			final String both = DistributionSendStop.message("3", jobs, runs);
			DistributionSendCallsTest.check("job and run named: " + both, both.contains("job(s) 41") && both.contains("Connectors run(s) R1") && both.startsWith("Asked to stop the send of target 3"));
			DistributionSendCallsTest.check("a run alone", DistributionSendStop.message("3", none, runs).contains("Connectors run(s) R1") && !DistributionSendStop.message("3", none, runs).contains("job(s)"));
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
