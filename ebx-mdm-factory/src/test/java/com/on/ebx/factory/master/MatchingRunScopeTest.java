package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MatchingRunScopeTest
{
	public static void main(final String[] pArgs)
	{
		MatchingRunScopeTest.describe_statesTheScopePlainly();
		MatchingRunScopeTest.incremental_noLoadId_refused();
		MatchingRunScopeTest.incremental_blankLoadId_refused();
		MatchingRunScopeTest.incremental_withLoadId_scopedOnLoadId();
		MatchingRunScopeTest.incremental_loadIdWithQuote_escaped();
		MatchingRunScopeTest.all_noKeys_refused();
		MatchingRunScopeTest.all_blankKeysOnly_refused();
		// lot R9 (2026-09-27, docs/MATCHING.md sect;40): pWholeTableBelongsToProject=true is the non-enumerating,
		// bug-immune path every real caller uses today.
		MatchingRunScopeTest.all_wholeTableBelongsToProject_isNotNullPredicate();
		MatchingRunScopeTest.all_wholeTableBelongsToProject_stillRefusedWhenNoKeys();
		MatchingRunScopeTest.all_noPrimaryKeyField_refused();
		// The enumerated form, fixed: numeric keys unquoted, string keys quoted/escaped, a bad "numeric" value
		// refused loudly rather than silently embedded wrong.
		MatchingRunScopeTest.all_enumerated_numericKeysUnquoted();
		MatchingRunScopeTest.all_enumerated_stringKeysQuotedAndEscaped();
		MatchingRunScopeTest.all_enumerated_numericKeyThatDoesNotLookNumeric_refused();
		MatchingRunScopeTest.all_enumerated_nestedPrimaryKeyField();
		MatchingRunScopeTest.all_enumerated_catchUpChunkStaysShort();
		System.out.println("MatchingRunScopeTest OK");
	}

	/** Lot M4 (2026-10-03, defect 3): the sentence of the entity's step message. */
	private static void describe_statesTheScopePlainly()
	{
		final String incremental = MatchingRunScope.describe(false, 338257, 576692);
		MatchingRunScopeTest.check("incremental names pivots out of the table: " + incremental,
			incremental.contains("incremental") && incremental.contains("338,257 of 576,692 records were pivots") && incremental.contains("the other 238,435 were not matched in this run"));
		MatchingRunScopeTest.check("all: the whole table", MatchingRunScope.describe(true, 576692, 576692).contains("all records - the 576,692 records"));
		MatchingRunScopeTest.check("counts not available, incremental: still says incremental", MatchingRunScope.describe(false, -1, 576692).contains("incremental")
			&& MatchingRunScope.describe(false, -1, 576692).contains("not available"));
		MatchingRunScopeTest.check("counts not available, all", MatchingRunScope.describe(true, -1, -1).contains("not available"));
		MatchingRunScopeTest.check("pivots above the total never print a negative rest", MatchingRunScope.describe(false, 10, 5).contains("the other 0 "));
	}

	private static void incremental_noLoadId_refused()
	{
		final MatchingRunScope.Decision decision = MatchingRunScope.incremental(null);
		MatchingRunScopeTest.check("not scoped", !decision.scoped);
		MatchingRunScopeTest.check("no predicate", decision.xpathPredicate == null);
		MatchingRunScopeTest.check("refusal names the reason", decision.refusal.contains("nothing has reached master"));
	}

	private static void incremental_blankLoadId_refused()
	{
		final MatchingRunScope.Decision decision = MatchingRunScope.incremental("   ");
		MatchingRunScopeTest.check("not scoped", !decision.scoped);
	}

	private static void incremental_withLoadId_scopedOnLoadId()
	{
		final MatchingRunScope.Decision decision = MatchingRunScope.incremental("L20260920-133440-1");
		MatchingRunScopeTest.check("scoped", decision.scoped);
		MatchingRunScopeTest.check("no refusal", decision.refusal == null);
		MatchingRunScopeTest.check("predicate on _loadId", "./_loadId='L20260920-133440-1'".equals(decision.xpathPredicate));
	}

	private static void incremental_loadIdWithQuote_escaped()
	{
		final MatchingRunScope.Decision decision = MatchingRunScope.incremental("it's-odd");
		MatchingRunScopeTest.check("apostrophe doubled", "./_loadId='it''s-odd'".equals(decision.xpathPredicate));
	}

	private static void all_noKeys_refused()
	{
		final MatchingRunScope.Decision decision = MatchingRunScope.all(Collections.<String>emptyList(), true, "id", MatchingRunScope.KeyType.NUMERIC);
		MatchingRunScopeTest.check("not scoped", !decision.scoped);
		MatchingRunScopeTest.check("refusal names the reason", decision.refusal.contains("nothing to scope"));
	}

	private static void all_blankKeysOnly_refused()
	{
		final MatchingRunScope.Decision decision = MatchingRunScope.all(Arrays.asList("", "   ", null), true, "id", MatchingRunScope.KeyType.NUMERIC);
		MatchingRunScopeTest.check("not scoped (blanks filtered out)", !decision.scoped);
	}

	/**
	 * Lot R9 (2026-09-27, coordinator review): {@code pWholeTableBelongsToProject=true} - this factory's own real
	 * callers, since a project's master dataset/dataspace is exclusive to that project - never enumerates, and
	 * answers {@code osd:is-not-null(./&lt;pk&gt;)} rather than a bare {@code true()}: measured LIVE on the RC
	 * (`count_records_by_xpath` against `PersVol7_Master`/`Master`/`/root/Adresse`) that {@code true()} itself
	 * answers "Invalid XPath predicate: 'true()'", while {@code osd:is-not-null(./id)} answers 4667 (every row) -
	 * see {@code MatchingRunScope}'s own class Javadoc. Immune to the numeric/string type bug regardless of
	 * {@link MatchingRunScope.KeyType} (unused on this path), since it never compares the key against a literal.
	 */
	private static void all_wholeTableBelongsToProject_isNotNullPredicate()
	{
		final MatchingRunScope.Decision decision = MatchingRunScope.all(Arrays.asList("1", "2", "3"), true, "id", MatchingRunScope.KeyType.NUMERIC);
		MatchingRunScopeTest.check("scoped", decision.scoped);
		MatchingRunScopeTest.check("no refusal", decision.refusal == null);
		MatchingRunScopeTest.check("osd:is-not-null(./id) predicate, never an enumerated OR list and never a bare true()",
			"osd:is-not-null(./id)".equals(decision.xpathPredicate));
	}

	/** Even on the non-enumerating path, an empty key list still means "nothing has reached master yet" - never a silent all-records predicate over zero records. */
	private static void all_wholeTableBelongsToProject_stillRefusedWhenNoKeys()
	{
		final MatchingRunScope.Decision decision = MatchingRunScope.all(Collections.<String>emptyList(), true, "id", MatchingRunScope.KeyType.NUMERIC);
		MatchingRunScopeTest.check("not scoped", !decision.scoped);
	}

	/** A blank/missing primary key field is refused loudly (IllegalStateException) - never a predicate built on nothing. */
	private static void all_noPrimaryKeyField_refused()
	{
		try
		{
			MatchingRunScope.all(Arrays.asList("1"), true, "   ", MatchingRunScope.KeyType.NUMERIC);
			throw new AssertionError("expected IllegalStateException for a blank primary key field");
		}
		catch (final IllegalStateException expected)
		{
			MatchingRunScopeTest.check("names the missing field: " + expected.getMessage(), expected.getMessage().contains("no primary key field"));
		}
	}

	/**
	 * The enumerated form ({@code pWholeTableBelongsToProject=false}), fixed: a {@link MatchingRunScope.KeyType#NUMERIC}
	 * key is rendered UNQUOTED - the live PersVol7 bug (`ClassCastException: BigDecimal cannot be cast to String`)
	 * came from quoting a numeric {@code id} field's value as an XPath string literal.
	 */
	private static void all_enumerated_numericKeysUnquoted()
	{
		final MatchingRunScope.Decision decision = MatchingRunScope.all(Arrays.asList("1", "2", "", "3"), false, "id", MatchingRunScope.KeyType.NUMERIC);
		MatchingRunScopeTest.check("scoped", decision.scoped);
		MatchingRunScopeTest.check("or-predicate over the three real keys, unquoted", "./id=1 or ./id=2 or ./id=3".equals(decision.xpathPredicate));
	}

	/** A {@link MatchingRunScope.KeyType#STRING} key is quoted, apostrophes doubled - the same convention {@link MatchingRunScope#incremental} already uses. */
	private static void all_enumerated_stringKeysQuotedAndEscaped()
	{
		final MatchingRunScope.Decision decision = MatchingRunScope.all(Arrays.asList("AB-01", "it's-odd"), false, "id", MatchingRunScope.KeyType.STRING);
		MatchingRunScopeTest.check("scoped", decision.scoped);
		MatchingRunScopeTest.check("string keys quoted and escaped", "./id='AB-01' or ./id='it''s-odd'".equals(decision.xpathPredicate));
	}

	/** A key declared NUMERIC that does not actually look like a number is refused loudly (IllegalStateException), never silently embedded unquoted. */
	private static void all_enumerated_numericKeyThatDoesNotLookNumeric_refused()
	{
		try
		{
			MatchingRunScope.all(Arrays.asList("1", "not-a-number"), false, "id", MatchingRunScope.KeyType.NUMERIC);
			throw new AssertionError("expected IllegalStateException for a non-numeric NUMERIC key");
		}
		catch (final IllegalStateException expected)
		{
			MatchingRunScopeTest.check("names the offending value: " + expected.getMessage(), expected.getMessage().contains("not-a-number"));
		}
	}

	/** A nested/composite-looking primary key field path (e.g. a group) is rendered as given, no assumption of a bare top-level name. */
	private static void all_enumerated_nestedPrimaryKeyField()
	{
		final MatchingRunScope.Decision decision = MatchingRunScope.all(Arrays.asList("1"), false, "group/field", MatchingRunScope.KeyType.NUMERIC);
		MatchingRunScopeTest.check("nested field path used as-is: " + decision.xpathPredicate, "./group/field=1".equals(decision.xpathPredicate));
	}

	/** Lot MP (2026-10-04): the catch-up after a matching pause renders chunks of {@code CATCHUP_CHUNK_SIZE} numeric keys through the enumerated form. */
	private static void all_enumerated_catchUpChunkStaysShort()
	{
		final java.util.List<String> keys = new java.util.ArrayList<String>();
		for (int i = 0; i < MatchingLoadPauseRule.CATCHUP_CHUNK_SIZE; i++)
		{
			keys.add(String.valueOf(1000000 + i));
		}
		final MatchingRunScope.Decision decision = MatchingRunScope.all(keys, false, "id", MatchingRunScope.KeyType.NUMERIC);
		MatchingRunScopeTest.check("scoped", decision.scoped);
		MatchingRunScopeTest.check("one unquoted clause per key", decision.xpathPredicate.split(" or ").length == MatchingLoadPauseRule.CATCHUP_CHUNK_SIZE
			&& decision.xpathPredicate.startsWith("./id=1000000 or ./id=1000001") && !decision.xpathPredicate.contains("'"));
		MatchingRunScopeTest.check("a few kilobytes at most: " + decision.xpathPredicate.length(), decision.xpathPredicate.length() < 4096);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
