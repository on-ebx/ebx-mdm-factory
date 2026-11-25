package com.on.ebx.factory.deducedscan;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JUnit-free: run the main, a failure throws. */
public final class TableProposalTest
{
	public static void main(final String[] pArgs)
	{
		final List<String> measuredColumns = Arrays.asList("id", "firstname", "lastname");

		final TableProposal good = new TableProposal("Contact", Arrays.asList("id"), 94, "id is filled and different on every row read.");
		TableProposalTest.check("a valid proposal has no violation", TableProposal.firstViolation(good, measuredColumns) == null);

		TableProposalTest.check("blank table name refused", TableProposal.firstViolation(new TableProposal("", Arrays.asList("id"), 90, "x"), measuredColumns) != null);
		TableProposalTest.check("null proposal refused", TableProposal.firstViolation(null, measuredColumns) != null);
		TableProposalTest.check("table name with a space refused", TableProposal.firstViolation(new TableProposal("My Table", Arrays.asList("id"), 90, "x"), measuredColumns) != null);
		TableProposalTest.check("no key column refused", TableProposal.firstViolation(new TableProposal("Contact", Collections.<String>emptyList(), 90, "x"), measuredColumns) != null);
		TableProposalTest.check("a key column not among the measured columns refused",
			TableProposal.firstViolation(new TableProposal("Contact", Arrays.asList("madeUp"), 90, "x"), measuredColumns) != null);
		TableProposalTest.check("blank rationale refused", TableProposal.firstViolation(new TableProposal("Contact", Arrays.asList("id"), 90, "  "), measuredColumns) != null);
		final StringBuilder tooLong = new StringBuilder();
		for (int i = 0; i < TableProposal.RATIONALE_MAX_LENGTH + 1; i++)
		{
			tooLong.append('x');
		}
		TableProposalTest.check("rationale over the max length refused",
			TableProposal.firstViolation(new TableProposal("Contact", Arrays.asList("id"), 90, tooLong.toString()), measuredColumns) != null);

		// composite key: every column must be measured, not just the first
		final TableProposal composite = new TableProposal("Adresse", Arrays.asList("id", "lastname"), 80, "composite key.");
		TableProposalTest.check("a composite key of measured columns is accepted", TableProposal.firstViolation(composite, measuredColumns) == null);

		TableProposalTest.check("clampConfidence clamps below 0", TableProposal.clampConfidence(-5) == 0);
		TableProposalTest.check("clampConfidence clamps above 100", TableProposal.clampConfidence(150) == 100);
		TableProposalTest.check("clampConfidence keeps an in-range value", TableProposal.clampConfidence(42) == 42);

		System.out.println("TableProposalTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
