package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;

/** JUnit-free. F2-05: a scan started async is followed with connectors_run_get, not waited for under the fixed read timeout. */
public final class ConnectorsRunFollowerTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** A Connectors that starts a run, answers RUNNING {@code pRunning} times, then {@code pFinal}. */
	static McpClient fake(final List<String> pCalls, final int pRunning, final String pFinalExecution, final boolean pIgnoresAsync)
	{
		final int[] polls = new int[1];
		return new McpClient(new McpClient.Transport()
		{
			@Override
			public String exchange(final String pMessage) throws McpException
			{
				try
				{
					final JsonNode message = MAPPER.readTree(pMessage);
					final String method = message.path("method").asText();
					final int id = message.path("id").asInt();
					if (method.startsWith("notifications/"))
					{
						return "";
					}
					if ("initialize".equals(method))
					{
						return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}";
					}
					final String tool = message.path("params").path("name").asText();
					pCalls.add(tool + " " + message.path("params").path("arguments"));
					String structured;
					if ("connectors_sync".equals(tool))
					{
						structured = pIgnoresAsync ? "{\"status\":\"OK\",\"count\":5,\"ok\":5,\"errors\":0}" : "{\"runId\":\"r1\",\"status\":\"RUNNING\",\"async\":true}";
					}
					else if ("connectors_run_get".equals(tool))
					{
						polls[0]++;
						structured = polls[0] <= pRunning ? "{\"execution\":{\"status\":\"RUNNING\",\"created\":1}}" : "{\"execution\":" + pFinalExecution + "}";
					}
					else
					{
						structured = "{\"stopped\":true}";
					}
					return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}],\"structuredContent\":" + structured + ",\"isError\":false}}";
				}
				catch (final java.io.IOException ex)
				{
					throw new McpException("bad message", ex);
				}
			}

			@Override
			public String describe()
			{
				return "fake";
			}
		});
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final ObjectNode args = MAPPER.createObjectNode().put("mapping", 7).put("max", 10000);

		final List<String> calls = new ArrayList<String>();
		final ConnectorsRunFollower.Answer done = ConnectorsRunFollower.sync(fake(calls, 2, "{\"status\":\"DONE\",\"created\":40,\"updated\":2,\"noChange\":0,\"errorCount\":1,\"truncated\":false}", false),
			args, null, 1L, 60000L);
		ConnectorsRunFollowerTest.check("started with async: " + calls.get(0), calls.get(0).contains("\"async\":true"));
		ConnectorsRunFollowerTest.check("followed with connectors_run_get until terminal: " + calls, calls.size() == 4 && calls.get(3).startsWith("connectors_run_get"));
		ConnectorsRunFollowerTest.check("answer in the synchronous shape: " + done.json, !done.error && "OK".equals(done.json.path("status").asText()) && done.json.path("count").asInt() == 42
			&& done.json.path("errors").asInt() == 1 && done.json.path("ok").asInt() == 41 && "r1".equals(done.runId));
		ConnectorsRunFollowerTest.check("the caller's arguments are left alone", !args.has("async"));

		final ConnectorsRunFollower.Answer older = ConnectorsRunFollower.sync(fake(new ArrayList<String>(), 0, "{}", true), args, null, 1L, 60000L);
		ConnectorsRunFollowerTest.check("a Connectors that ignores async answers directly: " + older.json, older.json.path("count").asInt() == 5 && older.runId == null);

		try
		{
			ConnectorsRunFollower.sync(fake(new ArrayList<String>(), 0, "{\"status\":\"STOPPED\",\"created\":3}", false), args, null, 1L, 60000L);
			ConnectorsRunFollowerTest.check("a stopped run must not read as a scan", false);
		}
		catch (final McpException ex)
		{
			ConnectorsRunFollowerTest.check("names the run: " + ex.getMessage(), ex.getMessage().contains("r1") && ex.getMessage().contains("STOPPED"));
		}

		final List<String> slow = new ArrayList<String>();
		try
		{
			ConnectorsRunFollower.sync(fake(slow, 1000000, "{}", false), args, null, 5L, 60L);
			ConnectorsRunFollowerTest.check("a run that never ends must hit the deadline", false);
		}
		catch (final McpException ex)
		{
			ConnectorsRunFollowerTest.check("deadline names the run and asks Connectors to stop: " + ex.getMessage(), ex.getMessage().contains("r1") && ex.getMessage().contains("asked to stop"));
			ConnectorsRunFollowerTest.check("connectors_run_stop was sent", slow.get(slow.size() - 1).startsWith("connectors_run_stop"));
		}
		System.out.println("ConnectorsRunFollowerTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
