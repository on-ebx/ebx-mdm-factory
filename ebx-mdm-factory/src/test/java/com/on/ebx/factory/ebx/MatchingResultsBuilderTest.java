package com.on.ebx.factory.ebx;

import java.math.BigDecimal;

/**
 * JUnit-free: run the main, a failure throws. Covers {@link MatchingResultsBuilder#renderMetadataText} (lot
 * R11, 2026-09-27) - the pure rendering half of {@link MatchingResultsBuilder#safeGetString}, split out
 * specifically so the fix for the live PersVol7 crash (kernel.log 21:58:03,943: {@code ClassCastException:
 * BigDecimal cannot be cast to String} from {@code Adaptation.getString(Path)} on a numeric {@code
 * RecordMetadata.groupId}/{@code goldenId} field) is testable without any EBX class - same "no lightweight
 * fake for a schema-node-backed class" reasoning {@code docs/RELATION_FIELDS_WORKAROUND.md}'s lot R10 note
 * already applies to {@code SchemaNode}/{@code Adaptation}. Same package as {@link MatchingResultsBuilder} on
 * purpose, to reach this package-private static directly.
 */
public final class MatchingResultsBuilderTest
{
	public static void main(final String[] pArgs)
	{
		MatchingResultsBuilderTest.renderMetadataText_null_null();
		MatchingResultsBuilderTest.renderMetadataText_bigDecimal_plainStringNeverScientificNotation();
		MatchingResultsBuilderTest.renderMetadataText_otherNumber_stringValueOf();
		MatchingResultsBuilderTest.renderMetadataText_plainString_unchanged();
		MatchingResultsBuilderTest.renderMetadataText_blankString_null();
		MatchingResultsBuilderTest.unorderedKey_sameWhicheverSideIsNamedFirst();
		System.out.println("MatchingResultsBuilderTest OK");
	}

	private static void renderMetadataText_null_null()
	{
		MatchingResultsBuilderTest.check("null value renders as null", MatchingResultsBuilder.renderMetadataText(null) == null);
	}

	/**
	 * The live PersVol7 crash: {@code RecordMetadata.groupId}/{@code goldenId} are {@link BigDecimal} at the
	 * EBX-Java boundary (CLAUDE.md sec 2's {@code db.field<T>}/decimal note). {@code toPlainString()}, never
	 * {@code toString()}, so a large id never renders in scientific notation.
	 */
	private static void renderMetadataText_bigDecimal_plainStringNeverScientificNotation()
	{
		MatchingResultsBuilderTest.check("small BigDecimal renders as a plain integer string",
			"324".equals(MatchingResultsBuilder.renderMetadataText(new BigDecimal("324"))));
		MatchingResultsBuilderTest.check("BigDecimal that would use scientific notation via toString() renders plain instead",
			"123000000".equals(MatchingResultsBuilder.renderMetadataText(new BigDecimal("1.23E+8"))));
	}

	private static void renderMetadataText_otherNumber_stringValueOf()
	{
		MatchingResultsBuilderTest.check("a plain Integer renders via String.valueOf", "42".equals(MatchingResultsBuilder.renderMetadataText(Integer.valueOf(42))));
		MatchingResultsBuilderTest.check("a Long renders via String.valueOf", "9000000000".equals(MatchingResultsBuilder.renderMetadataText(Long.valueOf(9000000000L))));
	}

	private static void renderMetadataText_plainString_unchanged()
	{
		MatchingResultsBuilderTest.check("a genuine String value is unchanged", "GOLDEN".equals(MatchingResultsBuilder.renderMetadataText("GOLDEN")));
	}

	private static void renderMetadataText_blankString_null()
	{
		MatchingResultsBuilderTest.check("an empty string folds back to null", MatchingResultsBuilder.renderMetadataText("") == null);
		MatchingResultsBuilderTest.check("an all-whitespace string folds back to null", MatchingResultsBuilder.renderMetadataText("   ") == null);
	}

	/** Wave 2: a pair read from {@code Suspected} is listed once even if both of its orientations were ever present. */
	private static void unorderedKey_sameWhicheverSideIsNamedFirst()
	{
		MatchingResultsBuilderTest.check("orientation does not matter", MatchingResultsBuilder.unorderedKey("10000", "45396").equals(MatchingResultsBuilder.unorderedKey("45396", "10000")));
		MatchingResultsBuilderTest.check("different pairs differ", !MatchingResultsBuilder.unorderedKey("1", "2").equals(MatchingResultsBuilder.unorderedKey("1", "3")));
		MatchingResultsBuilderTest.check("a record paired with itself is a key too", MatchingResultsBuilder.unorderedKey("7", "7").equals("7|7"));
		MatchingResultsBuilderTest.check("null side does not throw", MatchingResultsBuilder.unorderedKey(null, "7").equals(MatchingResultsBuilder.unorderedKey("7", null)));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
