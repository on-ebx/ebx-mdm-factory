package com.on.ebx.factory.capability;

/** JUnit-free: run the main, a failure throws. */
public final class MeasurementMatchTest
{
	public static void main(final String[] pArgs)
	{
		MeasurementMatchTest.onExpectedPathTrueWhenSeverityAndPathBothMatch();
		MeasurementMatchTest.onExpectedPathFalseWhenSeverityDiffers();
		MeasurementMatchTest.onExpectedPathFalseWhenPathDiffers();
		MeasurementMatchTest.onExpectedPathTrueWhenSubjectPathHasNoLeadingDot();
		MeasurementMatchTest.onExpectedPathTrueWhenBothSidesHaveTheLeadingDot();
		MeasurementMatchTest.onExpectedPathFalseWhenSubjectPathIsANestedField();
		MeasurementMatchTest.onExpectedPathFalseWhenSubjectPathIsTableQualified();
		MeasurementMatchTest.onExpectedPathSelfFormsMatchEachOtherButNeverAField();
		MeasurementMatchTest.onExpectedPathFalseWhenEitherPathIsNull();
		MeasurementMatchTest.onExpectedPathFalseWhenPathsMatchButSeverityDoesNot();
		MeasurementMatchTest.messageMatchesExactRequiresEquality();
		MeasurementMatchTest.messageMatchesPrefixAcceptsAConcatenationSuffix();
		MeasurementMatchTest.messageMatchesPrefixRejectsAnUnrelatedMessage();
		MeasurementMatchTest.messageMatchesFalseWhenAttributionIsNull();
		MeasurementMatchTest.messageMatchesFalseWhenRenderedMessageIsNull();
		MeasurementMatchTest.shouldSampleTrueUnderCap();
		MeasurementMatchTest.shouldSampleFalseAtOrOverCap();
		MeasurementMatchTest.truncateLeavesShortTextUnchanged();
		MeasurementMatchTest.truncateAppendsEllipsisWhenLonger();
		MeasurementMatchTest.truncateNullInNullOut();
		MeasurementMatchTest.truncateExactlyAtBoundaryIsUnchanged();
		MeasurementMatchTest.keyAgreesWithOnExpectedPathOnMatchingPairs();
		MeasurementMatchTest.keyDisagreesWhenSeverityOrPathDiffer();
		MeasurementMatchTest.keyNormalizesPathTheSameWayOnExpectedPathDoes();

		System.out.println("MeasurementMatchTest OK");
	}

	// ------------------------------------------------------------------------------------------------- onExpectedPath

	private static void onExpectedPathTrueWhenSeverityAndPathBothMatch()
	{
		MeasurementMatchTest.check("both match", MeasurementMatch.onExpectedPath("WARNING", "WARNING", "./iban", "./iban"));
	}

	/** The real captured shape: an item on the right path but the WRONG severity (e.g. an unrelated ERROR-severity constraint on the same field) must never be counted. */
	private static void onExpectedPathFalseWhenSeverityDiffers()
	{
		MeasurementMatchTest.check("severity mismatch: never a match", !MeasurementMatch.onExpectedPath("ERROR", "WARNING", "./iban", "./iban"));
	}

	/** The real captured shape: an item of the right severity but on a DIFFERENT field's path (e.g. ./country) must never be counted for this constraint. */
	private static void onExpectedPathFalseWhenPathDiffers()
	{
		MeasurementMatchTest.check("path mismatch: never a match", !MeasurementMatch.onExpectedPath("WARNING", "WARNING", "./country", "./iban"));
	}

	/**
	 * fix/measure-path-form (2026-09-21): the actual bug, measured live against {@code recordsChecked: 55,
	 * recordsReported: 0} - a RECORD subject's path comes back from EBX as {@code /iban} (no leading dot), but
	 * {@code ebx.CapabilityApplier} builds its own {@code expectedPath} as {@code ./iban}. Must match.
	 */
	private static void onExpectedPathTrueWhenSubjectPathHasNoLeadingDot()
	{
		MeasurementMatchTest.check("EBX's own /iban must match the constraint's own ./iban", MeasurementMatch.onExpectedPath("WARNING", "WARNING", "/iban", "./iban"));
	}

	/** The pre-fix shape must keep working: both sides already carrying the leading {@code ./} still match. */
	private static void onExpectedPathTrueWhenBothSidesHaveTheLeadingDot()
	{
		MeasurementMatchTest.check("./iban vs ./iban still matches", MeasurementMatch.onExpectedPath("WARNING", "WARNING", "./iban", "./iban"));
	}

	/** Normalising the leading marker away must not turn this into a suffix match: a nested field is a different field. */
	private static void onExpectedPathFalseWhenSubjectPathIsANestedField()
	{
		MeasurementMatchTest.check("/address/iban is not ./iban", !MeasurementMatch.onExpectedPath("WARNING", "WARNING", "/address/iban", "./iban"));
	}

	/** Nor must it turn into a contains match against an absolute, table-qualified path - that stays unmatched (it shows up in {@code unmatchedSample} instead). */
	private static void onExpectedPathFalseWhenSubjectPathIsTableQualified()
	{
		MeasurementMatchTest.check("/root/Supplier/iban is not ./iban", !MeasurementMatch.onExpectedPath("WARNING", "WARNING", "/root/Supplier/iban", "./iban"));
	}

	/**
	 * "The record itself" has three spellings in this codebase - {@code Path.SELF.format()} ({@code "."},
	 * measured 2026-09-21), and the empty/root-only paths ({@code ""}, {@code "/"}) {@code describeSubject}
	 * can also produce - and all of them must match each other, but none of them may match a real field path.
	 */
	private static void onExpectedPathSelfFormsMatchEachOtherButNeverAField()
	{
		MeasurementMatchTest.check("'.' matches '/' (both mean the record itself)", MeasurementMatch.onExpectedPath("WARNING", "WARNING", "/", "."));
		MeasurementMatchTest.check("'.' matches '' (both mean the record itself)", MeasurementMatch.onExpectedPath("WARNING", "WARNING", "", "."));
		MeasurementMatchTest.check("the record itself is never a field", !MeasurementMatch.onExpectedPath("WARNING", "WARNING", ".", "./iban"));
	}

	/** Neither path argument may ever throw - a missing subject path (or a missing expected path) is simply not a match. */
	private static void onExpectedPathFalseWhenEitherPathIsNull()
	{
		MeasurementMatchTest.check("null subject path: no match, no exception", !MeasurementMatch.onExpectedPath("WARNING", "WARNING", null, "./iban"));
		MeasurementMatchTest.check("null expected path: no match, no exception", !MeasurementMatch.onExpectedPath("WARNING", "WARNING", "./iban", null));
	}

	/** The path-normalisation fix must never loosen the severity gate: a matching path with the wrong severity is still not a match. */
	private static void onExpectedPathFalseWhenPathsMatchButSeverityDoesNot()
	{
		MeasurementMatchTest.check("matching path, wrong severity: never a match", !MeasurementMatch.onExpectedPath("ERROR", "WARNING", "/iban", "./iban"));
	}

	// ------------------------------------------------------------------------------------------------- messageMatches

	private static void messageMatchesExactRequiresEquality()
	{
		final ConstraintBodyBuilder.MessageAttribution exact = new ConstraintBodyBuilder.MessageAttribution("IBAN failed validation.", true);
		MeasurementMatchTest.check("exact match", MeasurementMatch.messageMatches(exact, "IBAN failed validation."));
		MeasurementMatchTest.check("exact mode rejects a mere prefix", !MeasurementMatch.messageMatches(exact, "IBAN failed validation. Extra text."));
	}

	/**
	 * The real captured shape (fix/node-scope-multi-attribute, item 5): the model wrote {@code message | ' ('
	 * | status | ')'} - the stored literal is only the leading prefix, and the item's own RENDERED message
	 * carries the runtime status appended (e.g. {@code "The IBAN is not valid (CHECKSUM_FAILED)."}).
	 */
	private static void messageMatchesPrefixAcceptsAConcatenationSuffix()
	{
		final ConstraintBodyBuilder.MessageAttribution prefix = new ConstraintBodyBuilder.MessageAttribution("The IBAN is not valid (", false);
		MeasurementMatchTest.check("prefix match against the real rendered shape", MeasurementMatch.messageMatches(prefix, "The IBAN is not valid (CHECKSUM_FAILED)."));
	}

	private static void messageMatchesPrefixRejectsAnUnrelatedMessage()
	{
		final ConstraintBodyBuilder.MessageAttribution prefix = new ConstraintBodyBuilder.MessageAttribution("The IBAN is not valid (", false);
		MeasurementMatchTest.check("an unrelated message never matches", !MeasurementMatch.messageMatches(prefix, "Postal code is not recognised."));
	}

	/** {@code pAttribution == null} (the approximate case) is never this method's job to handle - callers treat {@link MeasurementMatch#onExpectedPath} alone as a match instead; called anyway, it returns false rather than throwing. */
	private static void messageMatchesFalseWhenAttributionIsNull()
	{
		MeasurementMatchTest.check("null attribution: false, never throws", !MeasurementMatch.messageMatches(null, "anything"));
	}

	/** The item's own message failed to render ({@code UserMessage.formatMessage} returned null, or the item carries no message at all) - never a match. */
	private static void messageMatchesFalseWhenRenderedMessageIsNull()
	{
		final ConstraintBodyBuilder.MessageAttribution exact = new ConstraintBodyBuilder.MessageAttribution("IBAN failed validation.", true);
		MeasurementMatchTest.check("null rendered message: false, never throws", !MeasurementMatch.messageMatches(exact, null));
	}

	// ------------------------------------------------------------------------------------------------- shouldSample

	private static void shouldSampleTrueUnderCap()
	{
		MeasurementMatchTest.check("0 of 8: still sampling", MeasurementMatch.shouldSample(0, 8));
		MeasurementMatchTest.check("7 of 8: still sampling", MeasurementMatch.shouldSample(7, 8));
	}

	private static void shouldSampleFalseAtOrOverCap()
	{
		MeasurementMatchTest.check("8 of 8: cap reached, stop sampling", !MeasurementMatch.shouldSample(8, 8));
		MeasurementMatchTest.check("9 of 8: past the cap either way", !MeasurementMatch.shouldSample(9, 8));
	}

	// ------------------------------------------------------------------------------------------------- truncate

	private static void truncateLeavesShortTextUnchanged()
	{
		MeasurementMatchTest.check("short text unchanged", "hello".equals(MeasurementMatch.truncate("hello", 100)));
	}

	private static void truncateAppendsEllipsisWhenLonger()
	{
		final String text = MeasurementMatchTest.repeat('x', 150);
		final String truncated = MeasurementMatch.truncate(text, 100);
		MeasurementMatchTest.check("truncated to 100 + ellipsis", truncated.length() == 103 && truncated.endsWith("..."));
		MeasurementMatchTest.check("the first 100 characters are preserved verbatim", truncated.startsWith(MeasurementMatchTest.repeat('x', 100)));
	}

	private static void truncateNullInNullOut()
	{
		MeasurementMatchTest.check("null in, null out", MeasurementMatch.truncate(null, 100) == null);
	}

	private static void truncateExactlyAtBoundaryIsUnchanged()
	{
		final String text = MeasurementMatchTest.repeat('x', 100);
		MeasurementMatchTest.check("exactly at the boundary: unchanged, no ellipsis", text.equals(MeasurementMatch.truncate(text, 100)));
	}

	// ------------------------------------------------------------------------------------------------------- key

	/** Volume lot 3 (2026-09-28, task 4): {@link MeasurementMatch#key} must agree with {@link MeasurementMatch#onExpectedPath} on every pair the latter calls a match. */
	private static void keyAgreesWithOnExpectedPathOnMatchingPairs()
	{
		MeasurementMatchTest.check("same key for a matching (severity, path) pair, plain field",
			MeasurementMatch.key("WARNING", "./iban").equals(MeasurementMatch.key("WARNING", "/iban")));
		MeasurementMatchTest.check("onExpectedPath itself agrees on this pair",
			MeasurementMatch.onExpectedPath("WARNING", "WARNING", "/iban", "./iban"));
	}

	private static void keyDisagreesWhenSeverityOrPathDiffer()
	{
		MeasurementMatchTest.check("different severity: different key", !MeasurementMatch.key("WARNING", "./iban").equals(MeasurementMatch.key("ERROR", "./iban")));
		MeasurementMatchTest.check("different path: different key", !MeasurementMatch.key("WARNING", "./iban").equals(MeasurementMatch.key("WARNING", "./phone")));
	}

	/** {@code key} must use the SAME normalization as {@code onExpectedPath} - self/table-scope forms (".", "/", "") all collapse to the same key. */
	private static void keyNormalizesPathTheSameWayOnExpectedPathDoes()
	{
		MeasurementMatchTest.check("self-form '.' and '/' collapse to the same key", MeasurementMatch.key("WARNING", ".").equals(MeasurementMatch.key("WARNING", "/")));
		MeasurementMatchTest.check("self-form and empty string also collapse", MeasurementMatch.key("WARNING", ".").equals(MeasurementMatch.key("WARNING", "")));
		MeasurementMatchTest.check("a self-form key never equals a real field's key",
			!MeasurementMatch.key("WARNING", ".").equals(MeasurementMatch.key("WARNING", "./iban")));
	}

	private static String repeat(final char pChar, final int pCount)
	{
		final StringBuilder out = new StringBuilder(pCount);
		for (int i = 0; i < pCount; i++)
		{
			out.append(pChar);
		}
		return out.toString();
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
