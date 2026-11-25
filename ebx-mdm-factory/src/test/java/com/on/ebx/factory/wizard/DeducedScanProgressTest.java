package com.on.ebx.factory.wizard;

import java.util.Arrays;
import java.util.Collections;

/** JUnit-free: run the main, a failure throws. Same discipline as {@code SourcesProgressTest}/{@code staging.ProposalProgressTest}. */
public final class DeducedScanProgressTest
{
	public static void main(final String[] pArgs)
	{
		final DeducedScanProgress fresh = DeducedScanProgress.newRun(Arrays.asList("src-1", "src-2"));
		DeducedScanProgressTest.check("two seeded entries", fresh.entries().size() == 2);
		DeducedScanProgressTest.check("seed order kept", "src-1".equals(fresh.entries().get(0).connectorId));
		DeducedScanProgressTest.check("every entry starts WAITING", DeducedScanProgress.WAITING.equals(fresh.entries().get(0).state));
		DeducedScanProgressTest.check("every stage starts WAITING", DeducedScanProgress.WAITING.equals(fresh.entry("src-1").stage(DeducedScanProgress.LIST).state));
		DeducedScanProgressTest.check("not over while WAITING entries remain", !fresh.isOver());

		fresh.enqueue("src-1"); // no-op, already present
		DeducedScanProgressTest.check("enqueue is a no-op for an existing id", fresh.entries().size() == 2);
		fresh.enqueue("src-3");
		DeducedScanProgressTest.check("enqueue appends a new id", fresh.entries().size() == 3);

		fresh.start("src-1", 1000L);
		DeducedScanProgressTest.check("start transitions to RUNNING", DeducedScanProgress.RUNNING.equals(fresh.entry("src-1").state));
		DeducedScanProgressTest.check("startedAt recorded", Long.valueOf(1000L).equals(fresh.entry("src-1").startedAt));

		fresh.stage("src-1", DeducedScanProgress.LIST, DeducedScanProgress.DONE, "the model chose list_contacts");
		DeducedScanProgressTest.check("stage transition recorded", DeducedScanProgress.DONE.equals(fresh.entry("src-1").stage(DeducedScanProgress.LIST).state));
		DeducedScanProgressTest.check("stage summary recorded", "the model chose list_contacts".equals(fresh.entry("src-1").stage(DeducedScanProgress.LIST).summary));
		DeducedScanProgressTest.check("other stages untouched by one stage transition",
			DeducedScanProgress.WAITING.equals(fresh.entry("src-1").stage(DeducedScanProgress.SAMPLE).state));

		fresh.finish("src-1", DeducedScanProgress.DONE, "1 table deduced, waiting for confirmation.", 2000L, Arrays.asList("Contact"));
		DeducedScanProgressTest.check("finish transitions to the given state", DeducedScanProgress.DONE.equals(fresh.entry("src-1").state));
		DeducedScanProgressTest.check("tablesWritten recorded", fresh.entry("src-1").tablesWritten.contains("Contact"));
		DeducedScanProgressTest.check("stage history survives finish", DeducedScanProgress.DONE.equals(fresh.entry("src-1").stage(DeducedScanProgress.LIST).state));

		// SKIPPED (lookup-only connection) also ends the entry, still not over while others remain WAITING
		fresh.start("src-2", 1500L);
		fresh.stage("src-2", DeducedScanProgress.LIST, DeducedScanProgress.SKIPPED, "no listing operation found");
		fresh.finish("src-2", DeducedScanProgress.SKIPPED, "no listing operation found", 1600L, Collections.<String>emptyList());
		DeducedScanProgressTest.check("SKIPPED still ends the entry", !fresh.isOver()); // src-3 is still WAITING

		fresh.start("src-3", 1700L);
		fresh.finish("src-3", DeducedScanProgress.FAILED, "connectors_describe refused the request.", 1800L, Collections.<String>emptyList());
		DeducedScanProgressTest.check("over once every entry finished one way or another", fresh.isOver());

		// JSON round trip - full fidelity of state, stages and tablesWritten
		final DeducedScanProgress roundTrip = DeducedScanProgress.parse(fresh.toJson());
		DeducedScanProgressTest.check("round trip entry count", roundTrip.entries().size() == 3);
		DeducedScanProgressTest.check("round trip order", "src-1".equals(roundTrip.entries().get(0).connectorId));
		DeducedScanProgressTest.check("round trip entry state", DeducedScanProgress.DONE.equals(roundTrip.entry("src-1").state));
		DeducedScanProgressTest.check("round trip stage state", DeducedScanProgress.DONE.equals(roundTrip.entry("src-1").stage(DeducedScanProgress.LIST).state));
		DeducedScanProgressTest.check("round trip stage summary", "the model chose list_contacts".equals(roundTrip.entry("src-1").stage(DeducedScanProgress.LIST).summary));
		DeducedScanProgressTest.check("round trip tablesWritten", roundTrip.entry("src-1").tablesWritten.contains("Contact"));
		DeducedScanProgressTest.check("round trip SKIPPED entry", DeducedScanProgress.SKIPPED.equals(roundTrip.entry("src-2").state));
		DeducedScanProgressTest.check("round trip isOver", roundTrip.isOver());

		// never throws on garbage/null/blank
		DeducedScanProgressTest.check("null json gives an empty, vacuously-over run", DeducedScanProgress.parse(null).isOver());
		DeducedScanProgressTest.check("blank json gives an empty, vacuously-over run", DeducedScanProgress.parse("   ").isOver());
		DeducedScanProgressTest.check("garbage json gives an empty, vacuously-over run", DeducedScanProgress.parse("{not json").isOver());

		// start/stage/finish on an unknown id are silent no-ops (defensive - see class Javadoc)
		final DeducedScanProgress defensive = DeducedScanProgress.newRun(Collections.<String>emptyList());
		defensive.start("ghost", 1L);
		defensive.stage("ghost", DeducedScanProgress.LIST, DeducedScanProgress.DONE, "x");
		defensive.finish("ghost", DeducedScanProgress.DONE, "x", 2L, Collections.<String>emptyList());
		DeducedScanProgressTest.check("unknown id never throws and never appears", defensive.entries().isEmpty());

		System.out.println("DeducedScanProgressTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
