package com.on.ebx.factory.wizard;

import java.util.Arrays;

/**
 * JUnit-free, like {@code ApplicationStagesTest}. Covers the JSON round trip the task brief asks for
 * ("Pure progress model in wizard.* or landing.* with a JSON round-trip test") plus the ordering/defensive
 * rules {@link SourcesProgress} documents.
 */
public final class SourcesProgressTest
{
	public static void main(final String[] pArgs)
	{
		SourcesProgressTest.newRunSeedsEveryEntryWaitingAndIsNotOver();
		SourcesProgressTest.emptyRunIsTriviallyOver();
		SourcesProgressTest.duplicateConnectorIdsAreCollapsed();
		SourcesProgressTest.startThenFinishTransitionsAndCompletesTheRun();
		SourcesProgressTest.jsonRoundTripPreservesStateAndTimestampsAndSummary();
		SourcesProgressTest.parseOfNullOrBlankIsAnEmptyRun();
		SourcesProgressTest.parseOfGarbageIsAnEmptyRunNotAnException();
		SourcesProgressTest.startOrFinishOnUnknownIdIsANoOp();
		SourcesProgressTest.enqueueAddsOneWaitingEntryWithHintsAndIsANoOpIfAlreadyPresent();
		SourcesProgressTest.hintsSurviveStartFinishAndAJsonRoundTrip();
		SourcesProgressTest.removeDropsTheEntryAndCanCompleteTheRun();

		System.out.println("SourcesProgressTest OK");
	}

	private static void newRunSeedsEveryEntryWaitingAndIsNotOver()
	{
		final SourcesProgress progress = SourcesProgress.newRun(Arrays.asList("A", "B"));
		SourcesProgressTest.check("A waiting", SourcesProgress.WAITING.equals(progress.entry("A").state));
		SourcesProgressTest.check("B waiting", SourcesProgress.WAITING.equals(progress.entry("B").state));
		SourcesProgressTest.check("two entries in order", progress.entries().size() == 2 && "A".equals(progress.entries().get(0).connectorId));
		SourcesProgressTest.check("not over", !progress.isOver());
	}

	private static void emptyRunIsTriviallyOver()
	{
		final SourcesProgress progress = SourcesProgress.newRun(Arrays.<String>asList());
		SourcesProgressTest.check("empty run is over", progress.isOver());
		SourcesProgressTest.check("no entries", progress.entries().isEmpty());
	}

	private static void duplicateConnectorIdsAreCollapsed()
	{
		final SourcesProgress progress = SourcesProgress.newRun(Arrays.asList("A", "A", "B"));
		SourcesProgressTest.check("duplicates collapsed to two entries", progress.entries().size() == 2);
	}

	private static void startThenFinishTransitionsAndCompletesTheRun()
	{
		final SourcesProgress progress = SourcesProgress.newRun(Arrays.asList("A", "B"));
		progress.start("A", 1000L, "Source Bidon");
		SourcesProgressTest.check("A running", SourcesProgress.RUNNING.equals(progress.entry("A").state));
		SourcesProgressTest.check("still not over (B waiting)", !progress.isOver());
		progress.finish("A", true, "Ready.", 2000L);
		SourcesProgressTest.check("A done", SourcesProgress.DONE.equals(progress.entry("A").state));
		SourcesProgressTest.check("A startedAt kept from start()", progress.entry("A").startedAt.longValue() == 1000L);
		SourcesProgressTest.check("A endedAt set by finish()", progress.entry("A").endedAt.longValue() == 2000L);
		SourcesProgressTest.check("still not over (B waiting)", !progress.isOver());
		progress.start("B", 3000L, null);
		progress.finish("B", false, "connectors_catalog_source refused.", 4000L);
		SourcesProgressTest.check("B failed", SourcesProgress.FAILED.equals(progress.entry("B").state));
		SourcesProgressTest.check("run over once every entry is DONE/FAILED", progress.isOver());
	}

	private static void jsonRoundTripPreservesStateAndTimestampsAndSummary()
	{
		final SourcesProgress progress = SourcesProgress.newRun(Arrays.asList("SourceBidon", "PersonsSap"));
		progress.start("SourceBidon", 1000L, "Source Bidon");
		progress.finish("SourceBidon", true, "Ready: source SOURCEBIDON.", 5000L);
		// PersonsSap left WAITING on purpose, so the round trip also covers an entry with no started/ended/summary

		final String json = progress.toJson();
		final SourcesProgress back = SourcesProgress.parse(json);

		SourcesProgressTest.check("same entry count", back.entries().size() == 2);
		SourcesProgressTest.check("same order", "SourceBidon".equals(back.entries().get(0).connectorId) && "PersonsSap".equals(back.entries().get(1).connectorId));
		SourcesProgressTest.check("state round-trips", SourcesProgress.DONE.equals(back.entry("SourceBidon").state));
		SourcesProgressTest.check("startedAt round-trips", back.entry("SourceBidon").startedAt.longValue() == 1000L);
		SourcesProgressTest.check("endedAt round-trips", back.entry("SourceBidon").endedAt.longValue() == 5000L);
		SourcesProgressTest.check("summary round-trips", "Ready: source SOURCEBIDON.".equals(back.entry("SourceBidon").summary));
		SourcesProgressTest.check("waiting entry keeps null timestamps/summary", back.entry("PersonsSap").startedAt == null && back.entry("PersonsSap").summary == null);
		SourcesProgressTest.check("waiting entry state round-trips", SourcesProgress.WAITING.equals(back.entry("PersonsSap").state));
		SourcesProgressTest.check("run over after round trip only if the source run is (it is not - PersonsSap still waiting)", !back.isOver());
	}

	private static void parseOfNullOrBlankIsAnEmptyRun()
	{
		SourcesProgressTest.check("null is empty", SourcesProgress.parse(null).entries().isEmpty());
		SourcesProgressTest.check("blank is empty", SourcesProgress.parse("   ").entries().isEmpty());
		SourcesProgressTest.check("empty parse is trivially over", SourcesProgress.parse(null).isOver());
	}

	private static void parseOfGarbageIsAnEmptyRunNotAnException()
	{
		final SourcesProgress progress = SourcesProgress.parse("{not json");
		SourcesProgressTest.check("garbage parses to an empty run, no exception", progress.entries().isEmpty());
	}

	private static void startOrFinishOnUnknownIdIsANoOp()
	{
		final SourcesProgress progress = SourcesProgress.newRun(Arrays.asList("A"));
		progress.start("Z", 1000L, "should be ignored");
		progress.finish("Z", true, "should be ignored", 2000L);
		SourcesProgressTest.check("no entry was created for the unknown id", progress.entry("Z") == null);
		SourcesProgressTest.check("known entry untouched", SourcesProgress.WAITING.equals(progress.entry("A").state));
	}

	private static void enqueueAddsOneWaitingEntryWithHintsAndIsANoOpIfAlreadyPresent()
	{
		final SourcesProgress progress = SourcesProgress.newRun(Arrays.asList("A"));
		progress.enqueue("B", "CRM_SAMPLE", 40);
		SourcesProgressTest.check("two entries now", progress.entries().size() == 2);
		SourcesProgressTest.check("B waiting", SourcesProgress.WAITING.equals(progress.entry("B").state));
		SourcesProgressTest.check("B carries its code hint", "CRM_SAMPLE".equals(progress.entry("B").codeHint));
		SourcesProgressTest.check("B carries its trust-rank hint", progress.entry("B").trustRankHint == 40);
		SourcesProgressTest.check("A has no hint (plain newRun)", progress.entry("A").codeHint.isEmpty() && progress.entry("A").trustRankHint == 100);
		progress.start("B", 1000L, null);
		progress.enqueue("B", "IGNORED", 5);
		SourcesProgressTest.check("enqueue on an already-present id is a no-op, whatever state it is in", SourcesProgress.RUNNING.equals(progress.entry("B").state));
		SourcesProgressTest.check("hint from the no-op enqueue never overwrites the original one", "CRM_SAMPLE".equals(progress.entry("B").codeHint));
	}

	private static void hintsSurviveStartFinishAndAJsonRoundTrip()
	{
		final SourcesProgress run = SourcesProgress.newRun(Arrays.<String>asList());
		run.enqueue("SourceBidon", "CRM_SAMPLE", 40);
		run.start("SourceBidon", 1000L, null);
		run.finish("SourceBidon", true, "Ready.", 2000L);
		SourcesProgressTest.check("hint kept through start/finish", "CRM_SAMPLE".equals(run.entry("SourceBidon").codeHint) && run.entry("SourceBidon").trustRankHint == 40);

		final SourcesProgress back = SourcesProgress.parse(run.toJson());
		SourcesProgressTest.check("codeHint round-trips", "CRM_SAMPLE".equals(back.entry("SourceBidon").codeHint));
		SourcesProgressTest.check("trustRankHint round-trips", back.entry("SourceBidon").trustRankHint == 40);

		run.enqueue("NoHint", "", 100);
		final SourcesProgress backNoHint = SourcesProgress.parse(run.toJson());
		SourcesProgressTest.check("default hints (empty/100) round-trip too, just omitted from the JSON", backNoHint.entry("NoHint").codeHint.isEmpty()
			&& backNoHint.entry("NoHint").trustRankHint == 100);
	}

	private static void removeDropsTheEntryAndCanCompleteTheRun()
	{
		final SourcesProgress progress = SourcesProgress.newRun(Arrays.asList("A", "B"));
		SourcesProgressTest.check("still not over", !progress.isOver());
		progress.remove("A");
		SourcesProgressTest.check("A gone", progress.entry("A") == null);
		SourcesProgressTest.check("one entry left", progress.entries().size() == 1 && "B".equals(progress.entries().get(0).connectorId));
		// removing a RUNNING entry: its later start()/finish() must be silent no-ops (start()/finish() already
		// guard on byId.containsKey - see their own Javadoc), not resurrect it
		progress.remove("B"); // pretend B was never queued in the first place from here on
		progress.start("B", 1000L, null);
		SourcesProgressTest.check("start() on a removed id is a no-op", progress.entry("B") == null);
		progress.finish("B", true, "ignored", 2000L);
		SourcesProgressTest.check("finish() on a removed id is a no-op too", progress.entry("B") == null);
		SourcesProgressTest.check("run is over once nothing is left to wait for", progress.isOver());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
