package com.on.ebx.factory.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free: run the main, a failure throws. The explicit max of a distribution send and what a cut send does next (audit C-35, wave 10). */
public final class DistributionBatchesTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		DistributionBatchesTest.check("absent property = default", DistributionBatches.maxRows(null) == DistributionBatches.DEFAULT_MAX_ROWS);
		DistributionBatchesTest.check("explicit value read", DistributionBatches.maxRows(" 5000 ") == 5000);
		DistributionBatchesTest.check("0 is the default, never no limit", DistributionBatches.maxRows("0") == DistributionBatches.DEFAULT_MAX_ROWS);
		DistributionBatchesTest.check("typo is the default", DistributionBatches.maxRows("10k") == DistributionBatches.DEFAULT_MAX_ROWS);
		DistributionBatchesTest.check("changed + cut + rows moved -> ask again", DistributionBatches.askAgain("changed", true, 100, 1));
		DistributionBatchesTest.check("all + cut -> never asked again (would restart from the first record)", !DistributionBatches.askAgain("all", true, 100, 1));
		DistributionBatchesTest.check("not cut -> done", !DistributionBatches.askAgain("changed", false, 100, 1));
		DistributionBatchesTest.check("cut with nothing moved -> stop (backlog does not drain)", !DistributionBatches.askAgain("changed", true, 0, 1));
		DistributionBatchesTest.check("rounds are bounded", !DistributionBatches.askAgain("changed", true, 100, DistributionBatches.MAX_ROUNDS));
		// wave 13: the read timeout of a send grows with the records it may take
		DistributionBatchesTest.check("0 rows = the 300 s base", DistributionBatches.sendTimeoutMs(0, 20L, 7200000L) == 300000);
		DistributionBatchesTest.check("100,000 rows at 20 ms = 2,300 s", DistributionBatches.sendTimeoutMs(100000, 20L, 7200000L) == 2300000);
		DistributionBatchesTest.check("the ceiling is applied", DistributionBatches.sendTimeoutMs(100000, 20L, 1000000L) == 1000000);
		DistributionBatchesTest.check("a ceiling under the base still leaves the base", DistributionBatches.sendTimeoutMs(100000, 20L, 1000L) == 300000);
		DistributionBatchesTest.check("a huge row limit does not overflow", DistributionBatches.sendTimeoutMs(Integer.MAX_VALUE, 20000L, DistributionBatches.DEFAULT_TIMEOUT_MAX_MS) == 7200000);
		DistributionBatchesTest.check("default per row is 20 ms", DistributionBatches.DEFAULT_PER_ROW_MS == 20);
		DistributionBatchesTest.check("default ceiling is 2 hours", DistributionBatches.DEFAULT_TIMEOUT_MAX_MS == 7200000L);
		DistributionBatchesTest.check("property value read", DistributionBatches.positive(" 35 ", 20L) == 35L);
		DistributionBatchesTest.check("absent property = default", DistributionBatches.positive(null, 20L) == 20L);
		DistributionBatchesTest.check("garbage property = default", DistributionBatches.positive("twenty", 20L) == 20L);
		DistributionBatchesTest.check("0 and negative = default", DistributionBatches.positive("0", 20L) == 20L && DistributionBatches.positive("-5", 20L) == 20L);
		System.clearProperty(DistributionBatches.PER_ROW_PROPERTY);
		System.clearProperty(DistributionBatches.TIMEOUT_MAX_PROPERTY);
		DistributionBatchesTest.check("send timeout with the defaults for the default limit", DistributionBatches.sendTimeoutMs(DistributionBatches.DEFAULT_MAX_ROWS) == 2300000);
		System.setProperty(DistributionBatches.PER_ROW_PROPERTY, "abc");
		System.setProperty(DistributionBatches.TIMEOUT_MAX_PROPERTY, "-1");
		DistributionBatchesTest.check("garbage system properties fall back to the defaults", DistributionBatches.sendTimeoutMs(100000) == 2300000);
		System.setProperty(DistributionBatches.PER_ROW_PROPERTY, "50");
		System.setProperty(DistributionBatches.TIMEOUT_MAX_PROPERTY, "3600000");
		DistributionBatchesTest.check("system properties are read at each call, ceiling applied", DistributionBatches.sendTimeoutMs(100000) == 3600000);
		System.clearProperty(DistributionBatches.PER_ROW_PROPERTY);
		System.clearProperty(DistributionBatches.TIMEOUT_MAX_PROPERTY);
		DistributionBatchesTest.check("the bridge's timeout text is a read timeout",
			DistributionBatches.isReadTimeout("The in-JVM ebx-connectors bridge did not answer within 300 s (read timeout)."));
		DistributionBatchesTest.check("an HTTP timeout is a read timeout", DistributionBatches.isReadTimeout("java.net.SocketTimeoutException: Read timed out"));
		DistributionBatchesTest.check("a refusal is not a timeout", !DistributionBatches.isReadTimeout("connectors_sync refused: no mapping 9") && !DistributionBatches.isReadTimeout(null));
		final String late = DistributionBatches.timeoutMessage("The in-JVM ebx-connectors bridge did not answer within 2300 s (read timeout).", "412");
		DistributionBatchesTest.check("the timeout sentence says the run may still be sending and names the mapping",
			late.equals("The in-JVM ebx-connectors bridge did not answer within 2300 s (read timeout): the Connectors run may still be sending; check the Connectors sync log of mapping 412 (connectors_run_get) before sending again."));
		final String cut = DistributionBatches.cutMessage("/root/Personne", "all", 100000, 100000);
		DistributionBatchesTest.check("cut sentence names the table and the property", cut.contains("/root/Personne") && cut.contains(DistributionBatches.PROPERTY));
		final ObjectMapper mapper = new ObjectMapper();
		final SyncOutcome.Result cutRun = SyncOutcome.read(false, null, mapper.readTree("{\"status\":\"PARTIAL\",\"count\":200,\"errors\":0,\"truncated\":true,\"runId\":\"R1\"}"));
		DistributionBatchesTest.check("a cut run with no error is ok but truncated", cutRun.ok && cutRun.truncated && cutRun.rows == 200L);
		final SyncOutcome.Result older = SyncOutcome.read(false, null, mapper.readTree("{\"status\":\"OK\",\"count\":12,\"errors\":0}"));
		DistributionBatchesTest.check("an older Connectors answer without truncated reads as not cut", older.ok && !older.truncated);
		final SyncOutcome.Result mixedSync = SyncOutcome.read(false, null, mapper.readTree("{\"count\":3,\"errors\":0,\"results\":[{\"status\":\"OK\"},{\"status\":\"NO_CHANGE\"},{\"status\":\"NO_CHANGE\"}]}"));
		DistributionBatchesTest.check("synchronous shape: NO_CHANGE items are the unchanged ones (approximate), rows stay the count",
			mixedSync.rows == 3L && mixedSync.sent == 1L && mixedSync.unchanged == 2L);
		// wave 13: the end messages keep sent and unchanged apart
		DistributionBatchesTest.check("unchanged only: Sent 0 row(s), 146559 unchanged",
			DistributionBatches.sentMessage(0L, 146559L, 2, "changed").equals("Sent 0 row(s), 146559 unchanged, across 2 mapping(s), scope=changed"));
		DistributionBatchesTest.check("nothing unchanged: the plain sentence",
			DistributionBatches.sentMessage(146559L, 0L, 2, "all").equals("Sent 146559 row(s) across 2 mapping(s), scope=all"));
		final String stop = DistributionBatches.stoppedMessage("stopped by admin", 13000L, 0L, false);
		DistributionBatchesTest.check("stop message: " + stop, stop.equals("Send stopped (stopped by admin) after 13000 row(s) sent; the rows sent keep their outbound state, a 'changed' send resumes after them."));
		DistributionBatchesTest.check("stop message with unchanged and a run still stopping", DistributionBatches.stoppedMessage("x", 5L, 7L, true).contains("after 5 row(s) sent and 7 found unchanged")
			&& DistributionBatches.stoppedMessage("x", 5L, 7L, true).contains("may be short"));
		System.out.println("DistributionBatchesTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
