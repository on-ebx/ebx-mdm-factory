package com.on.ebx.factory.capability;

/** JUnit-free: run the main, a failure throws. */
public final class ConstraintExplanationSubmissionTest
{
	public static void main(final String[] pArgs)
	{
		ConstraintExplanationSubmissionTest.nonEmptyTextIsAccepted();
		ConstraintExplanationSubmissionTest.emptyTextIsRefused();
		ConstraintExplanationSubmissionTest.blankTextIsRefused();
		ConstraintExplanationSubmissionTest.nullTextIsRefused();
		ConstraintExplanationSubmissionTest.textIsTrimmed();
		ConstraintExplanationSubmissionTest.doubleEscapedTextIsNormalizedBeforeAccepting();
		ConstraintExplanationSubmissionTest.wasNormalizedFalseForOrdinaryText();
		ConstraintExplanationSubmissionTest.apostropheInProseSurvivesNormalizationUnharmed();

		System.out.println("ConstraintExplanationSubmissionTest OK");
	}

	private static void nonEmptyTextIsAccepted()
	{
		final ConstraintExplanationSubmission submission = new ConstraintExplanationSubmission();
		final ConstraintExplanationSubmission.Outcome outcome = submission.submit("Warns when the phone number does not look valid.");
		ConstraintExplanationSubmissionTest.check("accepted", outcome.accepted);
		ConstraintExplanationSubmissionTest.check("text captured", "Warns when the phone number does not look valid.".equals(submission.text()));
	}

	private static void emptyTextIsRefused()
	{
		final ConstraintExplanationSubmission submission = new ConstraintExplanationSubmission();
		final ConstraintExplanationSubmission.Outcome outcome = submission.submit("");
		ConstraintExplanationSubmissionTest.check("refused", !outcome.accepted);
		ConstraintExplanationSubmissionTest.check("text not captured", submission.text() == null);
	}

	private static void blankTextIsRefused()
	{
		final ConstraintExplanationSubmission submission = new ConstraintExplanationSubmission();
		final ConstraintExplanationSubmission.Outcome outcome = submission.submit("   ");
		ConstraintExplanationSubmissionTest.check("refused", !outcome.accepted);
	}

	private static void nullTextIsRefused()
	{
		final ConstraintExplanationSubmission submission = new ConstraintExplanationSubmission();
		final ConstraintExplanationSubmission.Outcome outcome = submission.submit(null);
		ConstraintExplanationSubmissionTest.check("refused, never throws", !outcome.accepted);
	}

	private static void textIsTrimmed()
	{
		final ConstraintExplanationSubmission submission = new ConstraintExplanationSubmission();
		submission.submit("  spaced out  ");
		ConstraintExplanationSubmissionTest.check("trimmed", "spaced out".equals(submission.text()));
	}

	/**
	 * fix/review-assets (2026-09-21): the same double-JSON-escape symptom {@code submit_constraint_body} hit
	 * is possible here too - a multi-sentence explanation sent as one physical line with a literal
	 * backslash-n between sentences.
	 */
	private static void doubleEscapedTextIsNormalizedBeforeAccepting()
	{
		final ConstraintExplanationSubmission submission = new ConstraintExplanationSubmission();
		final ConstraintExplanationSubmission.Outcome outcome = submission.submit("Warns when the IBAN is not recognised.\\nChecks the checksum and the country's own format.");
		ConstraintExplanationSubmissionTest.check("accepted", outcome.accepted);
		ConstraintExplanationSubmissionTest.check("normalized to a real line break",
			"Warns when the IBAN is not recognised.\nChecks the checksum and the country's own format.".equals(submission.text()));
		ConstraintExplanationSubmissionTest.check("flagged as normalized", submission.wasNormalized());
	}

	private static void wasNormalizedFalseForOrdinaryText()
	{
		final ConstraintExplanationSubmission submission = new ConstraintExplanationSubmission();
		submission.submit("Warns when the phone number does not look valid.");
		ConstraintExplanationSubmissionTest.check("no normalisation needed", !submission.wasNormalized());
	}

	/** Prose mode (no string-literal protection): an apostrophe must never suppress the backslash-n -> real-line-break conversion that follows it. */
	private static void apostropheInProseSurvivesNormalizationUnharmed()
	{
		final ConstraintExplanationSubmission submission = new ConstraintExplanationSubmission();
		submission.submit("Warns when the record's iban does not look valid.\\nSee the field's own documentation for details.");
		ConstraintExplanationSubmissionTest.check("both sentences present, joined by a real line break, apostrophes untouched",
			"Warns when the record's iban does not look valid.\nSee the field's own documentation for details.".equals(submission.text()));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
