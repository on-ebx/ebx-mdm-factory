package com.on.ebx.factory.capability;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. fix/match-key-confidence
 * (2026-09-26): the pure decision behind a cross-system key derivation's stored confidence. Domain-neutral
 * fixture (a "code" attribute fed by systems A, B, C), shaped after the PersVol4 capture: the model said 50%,
 * the source was filled on all three systems.
 */
public final class DerivationEvidenceTest
{
	public static void main(final String[] pArgs)
	{
		DerivationEvidenceTest.notACrossSystemKeyKeepsModelNumberWithoutNote();
		DerivationEvidenceTest.filledOnEverySystemRaisesToMeasuredLevel();
		DerivationEvidenceTest.neverLowersAHigherModelNumber();
		DerivationEvidenceTest.oneSystemWithoutValueKeepsModelNumber();
		DerivationEvidenceTest.oneSystemNotMeasuredAtAllKeepsModelNumber();
		DerivationEvidenceTest.singleSystemEntityKeepsModelNumber();
		DerivationEvidenceTest.everyBoundAttributeMustBeCovered();
		DerivationEvidenceTest.severalMappingsOfOneSystemAreSummed();
		System.out.println("DerivationEvidenceTest OK");
	}

	private static Set<String> systems(final String... pSystems)
	{
		return new LinkedHashSet<String>(Arrays.asList(pSystems));
	}

	private static List<DerivationEvidence.SourceFill> fullCoverage()
	{
		return Arrays.asList(new DerivationEvidence.SourceFill("code", "A", 1194, 1054), new DerivationEvidence.SourceFill("code", "B", 1003, 1003),
			new DerivationEvidence.SourceFill("code", "C", 799, 513));
	}

	private static void notACrossSystemKeyKeepsModelNumberWithoutNote()
	{
		final DerivationEvidence.Decision d = DerivationEvidence.decide(50, false, Arrays.asList("code"), DerivationEvidenceTest.systems("A", "B", "C"),
			DerivationEvidenceTest.fullCoverage());
		DerivationEvidenceTest.check("model number kept", d.confidence == 50 && !d.raised);
		DerivationEvidenceTest.check("no note", d.note == null);
	}

	private static void filledOnEverySystemRaisesToMeasuredLevel()
	{
		final DerivationEvidence.Decision d = DerivationEvidence.decide(50, true, Arrays.asList("code"), DerivationEvidenceTest.systems("A", "B", "C"),
			DerivationEvidenceTest.fullCoverage());
		DerivationEvidenceTest.check("raised to the measured level: " + d.confidence,
			d.raised && d.confidence == DerivationEvidence.MEASURED_CROSS_SYSTEM_KEY_CONFIDENCE);
		DerivationEvidenceTest.check("at or above the default bulk threshold (60)", d.confidence >= 60);
		DerivationEvidenceTest.check("note names the systems and both numbers: " + d.note,
			d.note.contains("A, B, C") && d.note.contains("proposed 50%") && d.note.contains("85%"));
	}

	private static void neverLowersAHigherModelNumber()
	{
		final DerivationEvidence.Decision d = DerivationEvidence.decide(95, true, Arrays.asList("code"), DerivationEvidenceTest.systems("A", "B", "C"),
			DerivationEvidenceTest.fullCoverage());
		DerivationEvidenceTest.check("kept at 95", d.confidence == 95 && !d.raised);
	}

	private static void oneSystemWithoutValueKeepsModelNumber()
	{
		final List<DerivationEvidence.SourceFill> fills = Arrays.asList(new DerivationEvidence.SourceFill("code", "A", 10, 10),
			new DerivationEvidence.SourceFill("code", "B", 10, 0), new DerivationEvidence.SourceFill("code", "C", 10, 4));
		final DerivationEvidence.Decision d = DerivationEvidence.decide(50, true, Arrays.asList("code"), DerivationEvidenceTest.systems("A", "B", "C"), fills);
		DerivationEvidenceTest.check("kept at 50", d.confidence == 50 && !d.raised);
		DerivationEvidenceTest.check("note names the gap: " + d.note, d.note.contains("code on B"));
	}

	private static void oneSystemNotMeasuredAtAllKeepsModelNumber()
	{
		final List<DerivationEvidence.SourceFill> fills = Arrays.asList(new DerivationEvidence.SourceFill("code", "A", 10, 10),
			new DerivationEvidence.SourceFill("code", "B", 10, 10));
		final DerivationEvidence.Decision d = DerivationEvidence.decide(50, true, Arrays.asList("code"), DerivationEvidenceTest.systems("A", "B", "C"), fills);
		DerivationEvidenceTest.check("a system with no mapping on the source is a gap", d.confidence == 50 && d.note.contains("code on C"));
	}

	private static void singleSystemEntityKeepsModelNumber()
	{
		final DerivationEvidence.Decision d = DerivationEvidence.decide(50, true, Arrays.asList("code"), DerivationEvidenceTest.systems("A"),
			Collections.singletonList(new DerivationEvidence.SourceFill("code", "A", 10, 10)));
		DerivationEvidenceTest.check("nothing cross-system to measure", d.confidence == 50 && !d.raised && d.note.contains("only 1 system"));
	}

	private static void everyBoundAttributeMustBeCovered()
	{
		final List<DerivationEvidence.SourceFill> fills = Arrays.asList(new DerivationEvidence.SourceFill("code", "A", 10, 10),
			new DerivationEvidence.SourceFill("code", "B", 10, 10), new DerivationEvidence.SourceFill("region", "A", 10, 10));
		final DerivationEvidence.Decision d = DerivationEvidence.decide(40, true, Arrays.asList("code", "region"), DerivationEvidenceTest.systems("A", "B"), fills);
		DerivationEvidenceTest.check("second bound attribute missing on B keeps 40: " + d.note, d.confidence == 40 && d.note.contains("region on B"));
	}

	private static void severalMappingsOfOneSystemAreSummed()
	{
		final List<DerivationEvidence.SourceFill> fills = Arrays.asList(new DerivationEvidence.SourceFill("code", "A", 10, 0),
			new DerivationEvidence.SourceFill("code", "A", 10, 3), new DerivationEvidence.SourceFill("code", "B", 10, 10));
		final DerivationEvidence.Decision d = DerivationEvidence.decide(50, true, Arrays.asList("code"), DerivationEvidenceTest.systems("A", "B"), fills);
		DerivationEvidenceTest.check("an empty column plus a filled one of the same system is covered", d.raised && d.confidence == 85);
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
