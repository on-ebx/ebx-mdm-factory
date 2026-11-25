package com.on.ebx.factory.capability;

/** JUnit-free: run the main, a failure throws. */
public final class ConstraintMessageSubmissionTest
{
	public static void main(final String[] pArgs)
	{
		ConstraintMessageSubmissionTest.nonEmptyMessageIsAccepted();
		ConstraintMessageSubmissionTest.emptyMessageIsRefused();
		ConstraintMessageSubmissionTest.blankMessageIsRefused();
		ConstraintMessageSubmissionTest.nullMessageIsRefused();
		ConstraintMessageSubmissionTest.messageIsSanitizedTrimmedAndWhitespaceCollapsed();
		ConstraintMessageSubmissionTest.tooLongMessageIsTruncatedNeverRefused();

		System.out.println("ConstraintMessageSubmissionTest OK");
	}

	private static void nonEmptyMessageIsAccepted()
	{
		final ConstraintMessageSubmission submission = new ConstraintMessageSubmission();
		final ConstraintMessageSubmission.Outcome outcome = submission.submit("The phone number is not valid.");
		ConstraintMessageSubmissionTest.check("accepted", outcome.accepted);
		ConstraintMessageSubmissionTest.check("message captured", "The phone number is not valid.".equals(submission.message()));
	}

	private static void emptyMessageIsRefused()
	{
		final ConstraintMessageSubmission submission = new ConstraintMessageSubmission();
		final ConstraintMessageSubmission.Outcome outcome = submission.submit("");
		ConstraintMessageSubmissionTest.check("refused", !outcome.accepted);
		ConstraintMessageSubmissionTest.check("message not captured", submission.message() == null);
	}

	private static void blankMessageIsRefused()
	{
		final ConstraintMessageSubmission submission = new ConstraintMessageSubmission();
		final ConstraintMessageSubmission.Outcome outcome = submission.submit("   ");
		ConstraintMessageSubmissionTest.check("refused", !outcome.accepted);
	}

	private static void nullMessageIsRefused()
	{
		final ConstraintMessageSubmission submission = new ConstraintMessageSubmission();
		final ConstraintMessageSubmission.Outcome outcome = submission.submit(null);
		ConstraintMessageSubmissionTest.check("refused, never throws", !outcome.accepted);
	}

	/** Same sanitisation as {@code suggest_script_constraint}'s own {@code message} argument ({@link CapabilityLlmPass#sanitizeUserMessage}). */
	private static void messageIsSanitizedTrimmedAndWhitespaceCollapsed()
	{
		final ConstraintMessageSubmission submission = new ConstraintMessageSubmission();
		submission.submit("  The   phone\nnumber is not valid.  ");
		ConstraintMessageSubmissionTest.check("trimmed and collapsed", "The phone number is not valid.".equals(submission.message()));
	}

	/** Never refused merely for being too long - truncated instead ({@link CapabilityLlmPass#USER_MESSAGE_MAX_LENGTH}). */
	private static void tooLongMessageIsTruncatedNeverRefused()
	{
		final StringBuilder raw = new StringBuilder();
		while (raw.length() < 300)
		{
			raw.append("a");
		}
		final ConstraintMessageSubmission submission = new ConstraintMessageSubmission();
		final ConstraintMessageSubmission.Outcome outcome = submission.submit(raw.toString());
		ConstraintMessageSubmissionTest.check("accepted despite the length", outcome.accepted);
		ConstraintMessageSubmissionTest.check("truncated to the bound", submission.message().length() == CapabilityLlmPass.USER_MESSAGE_MAX_LENGTH);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
