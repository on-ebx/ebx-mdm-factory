package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free: run the main, a failure throws. Wave 13 (2026-10-12, "asynchronous distribution send"): the pure decisions of {@link SendRuns} - the
 * verdict of a terminal {@code connectors_run_get} answer fed into the {@link SyncOutcome.Result} the send already reads, the synchronous shape
 * of a Connectors that ignores {@code async}, the refusal of a Connectors that does not run a scope send asynchronously, and the double-send guard.
 */
public final class SendRunsTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		SendRunsTest.terminalAnswers();
		SendRunsTest.startAnswers();
		SendRunsTest.asyncRefusal();
		SendRunsTest.guardDecision();
		SendRunsTest.fieldValues();
		System.out.println("SendRunsTest OK");
	}

	private static JsonNode run(final String pExecutionJson) throws Exception
	{
		return MAPPER.readTree("{\"runId\":\"R20261012-100000-0001\",\"count\":0,\"errors\":0,\"execution\":" + pExecutionJson + "}");
	}

	private static void terminalAnswers() throws Exception
	{
		final String id = "R20261012-100000-0001";
		// DONE, cut at max: a success that says it was cut - the askAgain loop and the cut messages read truncated
		final SyncOutcome.Result done = SendRuns.terminal(SendRunsTest.run(
			"{\"status\":\"DONE\",\"created\":0,\"updated\":99990,\"noChange\":10,\"errorCount\":0,\"truncated\":true,\"available\":150000}"), id);
		SendRunsTest.check("DONE with a cut: ok, truncated, rows = created+updated+noChange", done.ok && done.truncated && done.rows == 100000L && id.equals(done.runId));
		SendRunsTest.check("a cut changed round is asked again", DistributionBatches.askAgain("changed", done.truncated, done.rows, 1));
		SendRunsTest.check("a cut all round is never asked again", !DistributionBatches.askAgain("all", done.truncated, done.rows, 1));
		final SyncOutcome.Result whole = SendRuns.terminal(SendRunsTest.run("{\"status\":\"DONE\",\"created\":88991,\"errorCount\":0,\"truncated\":false}"), id);
		SendRunsTest.check("DONE not cut: ok, not truncated, 88,991 rows", whole.ok && !whole.truncated && whole.rows == 88991L && whole.message.isEmpty());
		SendRunsTest.check("a run state without truncated reads as not cut", !SendRuns.terminal(SendRunsTest.run("{\"status\":\"DONE\",\"created\":3}"), id).truncated);

		// PARTIAL: the status Connectors gives a run cut at max - ok without errors, a failure with them
		final SyncOutcome.Result partialCut = SendRuns.terminal(SendRunsTest.run("{\"status\":\"PARTIAL\",\"updated\":200,\"errorCount\":0,\"truncated\":true}"), id);
		SendRunsTest.check("PARTIAL cut at max without errors is ok and truncated", partialCut.ok && partialCut.truncated && partialCut.rows == 200L);
		final SyncOutcome.Result partialErrors = SendRuns.terminal(SendRunsTest.run(
			"{\"status\":\"PARTIAL\",\"created\":50,\"errorCount\":3,\"firstErrors\":\"42: Kafka publish failed\\n43: Kafka publish failed\"}"), id);
		SendRunsTest.check("PARTIAL with errors fails and names the first one: " + partialErrors.message,
			!partialErrors.ok && partialErrors.rows == 50L && partialErrors.message.contains("3 record(s) in error") && partialErrors.message.contains("42: Kafka publish failed")
				&& !partialErrors.message.contains("43:"));

		// ERROR / STOPPED / INTERRUPTED never succeed
		final SyncOutcome.Result error = SendRuns.terminal(SendRunsTest.run("{\"status\":\"ERROR\",\"created\":10,\"errorCount\":5,\"message\":\"broker down\"}"), id);
		SendRunsTest.check("ERROR fails with its message: " + error.message, !error.ok && error.rows == 10L && error.message.contains("ended in ERROR") && error.message.contains("broker down"));
		final SyncOutcome.Result stopped = SendRuns.terminal(SendRunsTest.run("{\"status\":\"STOPPED\",\"updated\":4000,\"nextIndex\":4000,\"errorCount\":0}"), id);
		SendRunsTest.check("STOPPED fails, says where it stopped: " + stopped.message,
			!stopped.ok && stopped.rows == 4000L && stopped.message.contains("was stopped after 4000") && stopped.message.contains("resume point: item 4000"));
		final SyncOutcome.Result stopped13 = SendRuns.terminal(SendRunsTest.run("{\"status\":\"STOPPED\",\"updated\":13000,\"errorCount\":0}"), id);
		SendRunsTest.check("STOPPED 13000: sent 13000, unchanged 0, keeps outbound state: " + stopped13.message, stopped13.rows == 13000L && stopped13.sent == 13000L && stopped13.unchanged == 0L
			&& stopped13.message.contains("stopped after 13000 record(s) sent") && stopped13.message.contains("keep their outbound state"));
		final SyncOutcome.Result noChangeOnly = SendRuns.terminal(SendRunsTest.run("{\"status\":\"DONE\",\"noChange\":146559,\"errorCount\":0}"), id);
		SendRunsTest.check("DONE with noChange only: rows 146559, sent 0, unchanged 146559", noChangeOnly.ok && noChangeOnly.rows == 146559L && noChangeOnly.sent == 0L && noChangeOnly.unchanged == 146559L);
		final SyncOutcome.Result mixed = SendRuns.terminal(SendRunsTest.run("{\"status\":\"DONE\",\"created\":5,\"updated\":10,\"noChange\":20,\"errorCount\":0}"), id);
		SendRunsTest.check("sent = created + updated, rows keep all three", mixed.rows == 35L && mixed.sent == 15L && mixed.unchanged == 20L);
		final SyncOutcome.Result interrupted = SendRuns.terminal(SendRunsTest.run("{\"status\":\"INTERRUPTED\",\"updated\":120,\"errorCount\":0}"), id);
		SendRunsTest.check("INTERRUPTED fails and says why: " + interrupted.message, !interrupted.ok && interrupted.message.contains("restarted") && interrupted.rows == 120L);
		final SyncOutcome.Result none = SendRuns.terminal(null, id);
		SendRunsTest.check("no run state at all is not a success", !none.ok || none.rows == 0L);

		SendRunsTest.check("RUNNING is running", SendRuns.isRunning(SendRunsTest.run("{\"status\":\"RUNNING\"}")));
		SendRunsTest.check("DONE is not running", !SendRuns.isRunning(SendRunsTest.run("{\"status\":\"DONE\"}")));
		SendRunsTest.check("an answer without execution (an outbound run Connectors keeps no state for) is not running",
			!SendRuns.isRunning(MAPPER.readTree("{\"runId\":\"R1\",\"calls\":[]}")) && !SendRuns.isRunning(null));
	}

	private static void startAnswers() throws Exception
	{
		final JsonNode ack = MAPPER.readTree("{\"runId\":\"R20261012-100000-0001\",\"status\":\"RUNNING\",\"mapping\":412,\"async\":true,\"nextStep\":\"Poll connectors_run_get\"}");
		SendRunsTest.check("the acknowledgement is an async start", SendRuns.isAsyncStart(ack));
		// a Connectors that ignores async answers the complete synchronous shape: read directly, exactly as before
		final JsonNode sync = MAPPER.readTree("{\"status\":\"OK\",\"count\":57568,\"errors\":0,\"runId\":\"R20261012-090000-0007\",\"truncated\":false}");
		SendRunsTest.check("a synchronous answer is not an async start", !SendRuns.isAsyncStart(sync));
		final SyncOutcome.Result read = SyncOutcome.read(false, null, sync);
		SendRunsTest.check("and reads as it always did", read.ok && read.rows == 57568L && !read.truncated && "R20261012-090000-0007".equals(read.runId));
		final JsonNode syncCut = MAPPER.readTree("{\"status\":\"PARTIAL\",\"count\":100000,\"errors\":0,\"runId\":\"R2\",\"truncated\":true}");
		final SyncOutcome.Result cut = SyncOutcome.read(false, null, syncCut);
		SendRunsTest.check("a synchronous cut round is still asked again for changed", cut.truncated && DistributionBatches.askAgain("changed", cut.truncated, cut.rows, 1));
		SendRunsTest.check("a RUNNING status with a count is the synchronous shape", !SendRuns.isAsyncStart(MAPPER.readTree("{\"runId\":\"R3\",\"status\":\"RUNNING\",\"count\":5}")));
		SendRunsTest.check("no answer is not an async start", !SendRuns.isAsyncStart(null));
	}

	private static void asyncRefusal() throws Exception
	{
		SendRunsTest.check("the Connectors of 2026-10-12 refuses async for a scope send",
			SendRuns.asyncRefused("async only applies to an inbound feed with no key, predicate or scope"));
		SendRunsTest.check("whatever its capitals", SendRuns.asyncRefused("Async only applies to an Inbound Feed with no key, predicate or scope"));
		SendRunsTest.check("another refusal is not that one", !SendRuns.asyncRefused("No mapping '412'") && !SendRuns.asyncRefused("async cannot be combined with dryRun")
			&& !SendRuns.asyncRefused("Too many asynchronous runs: 4 may run and 16 may wait") && !SendRuns.asyncRefused(null));
	}

	private static void guardDecision() throws Exception
	{
		final String detail = "{\"transport\":\"in-jvm\",\"tables\":[{\"tablePath\":\"/root/Personne\",\"mapping\":\"411\",\"connectorsRunId\":\"R1\",\"runIds\":[\"R1\"]},"
			+ "{\"tablePath\":\"/root/Adresse\",\"mapping\":\"412\",\"connectorsRunId\":\"R2\"},{\"tablePath\":\"/root/X\",\"mapping\":\"413\"}]}";
		final List<String> candidates = SendRuns.candidateRunIds(detail, "R2, R0");
		SendRunsTest.check("candidates: the last run of each table, then the target's own ids, distinct: " + candidates, candidates.equals(Arrays.asList("R1", "R2", "R0")));
		SendRunsTest.check("nothing recorded, nothing to check", SendRuns.candidateRunIds(null, null).isEmpty() && SendRuns.candidateRunIds("", " ").isEmpty());
		SendRunsTest.check("a detail cut at its size limit is not read, the target's ids still count", SendRuns.candidateRunIds("{\"tables\":[{\"connectorsRu", "R9").equals(Arrays.asList("R9")));

		final Set<String> running = new HashSet<String>(Arrays.asList("R2"));
		final List<String> asked = new ArrayList<String>();
		final String blocked = SendRuns.firstRunning(candidates, new SendRuns.RunLookup()
		{
			@Override
			public boolean isRunning(final String pRunId)
			{
				asked.add(pRunId);
				return running.contains(pRunId);
			}
		});
		SendRunsTest.check("a send whose run is still running is refused, naming the run", "R2".equals(blocked));
		SendRunsTest.check("the guard stops asking at the first running run", asked.equals(Arrays.asList("R1", "R2")));
		running.clear();
		SendRunsTest.check("every run finished: the send may start", SendRuns.firstRunning(candidates, new SendRuns.RunLookup()
		{
			@Override
			public boolean isRunning(final String pRunId)
			{
				return running.contains(pRunId);
			}
		}) == null);
		SendRunsTest.check("no candidate, no question", SendRuns.firstRunning(new ArrayList<String>(), new SendRuns.RunLookup()
		{
			@Override
			public boolean isRunning(final String pRunId)
			{
				throw new AssertionError("must not be asked");
			}
		}) == null);
	}

	private static void fieldValues() throws Exception
	{
		SendRunsTest.check("two runs fit the 80-character field", SendRuns.fieldValue(Arrays.asList("R20261012-100000-0001", "R20261012-100500-0002"), "17")
			.equals("R20261012-100000-0001,R20261012-100500-0002"));
		final String four = SendRuns.fieldValue(Arrays.asList("R20261012-100000-0001", "R20261012-100500-0002", "R20261012-101000-0003", "R20261012-101500-0004"), "17");
		SendRunsTest.check("whole ids only, never past the field's length: " + four, four.length() <= SendRuns.FIELD_MAX_LENGTH && four.endsWith("0003") && !four.contains("0004"));
		SendRunsTest.check("no Connectors run id: the Factory step id, so the wizard still ticks", SendRuns.fieldValue(new ArrayList<String>(), "17").equals("17"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
