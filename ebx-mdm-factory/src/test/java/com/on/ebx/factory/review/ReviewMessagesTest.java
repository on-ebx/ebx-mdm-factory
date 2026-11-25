package com.on.ebx.factory.review;

import java.util.Locale;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Guards the exact
 * defect the coordinator flagged in {@code com.on.ebx.factory.ebx.ui.Messages_fr.properties}
 * ({@code review.cascade.none}, 2026-09-20): {@code java.text.MessageFormat} silently eats a single,
 * un-doubled apostrophe in any key that is formatted with arguments - the apostrophe (and everything up
 * to the next one) simply vanishes from the output, no exception, no warning. Every {@code review.*}
 * key called with arguments (see {@code ReviewPage}/{@code ProposalReviews}) is formatted here in French
 * - the language whose messages actually contain apostrophes - with distinguishable dummy arguments, and
 * the result is checked for two things: every {@code {n}} placeholder was substituted (no stray
 * {@code {} left), and every apostrophe the source text has is still there, exactly once each, not eaten
 * and not doubled into the output.
 */
public final class ReviewMessagesTest
{
	public static void main(final String[] pArgs)
	{
		ReviewMessagesTest.everyArgKeyFormatsCleanInFrench();
		ReviewMessagesTest.everyArgKeyFormatsCleanInEnglish();
		ReviewMessagesTest.cascadeNoneApostrophesSurvive();
		ReviewMessagesTest.cascadeMessageDoubledApostropheStaysASingleQuoteInOutput();
		System.out.println("ReviewMessagesTest OK");
	}

	/** Every {@code review.*} key formatted with one or more {@code {n}} arguments, and how many. */
	private static final Object[][] ARG_KEYS = { { "review.title", Integer.valueOf(1) }, { "review.counters", Integer.valueOf(5) },
		{ "review.project.current", Integer.valueOf(1) }, { "review.notice.refused", Integer.valueOf(1) }, { "review.cascade.message", Integer.valueOf(2) },
		{ "review.cascade.confirm", Integer.valueOf(1) }, { "review.cascade.none", Integer.valueOf(1) }, { "review.cascade.done", Integer.valueOf(1) },
		{ "review.bulk.confirm.message", Integer.valueOf(2) }, { "review.bulk.done", Integer.valueOf(2) }, { "review.prop.relation", Integer.valueOf(2) },
		{ "review.prop.entity.target", Integer.valueOf(1) }, { "review.prop.entity.unifies", Integer.valueOf(1) }, { "review.prop.attribute.plain", Integer.valueOf(1) },
		{ "review.prop.attribute.ref", Integer.valueOf(2) }, { "review.prop.match.requirements", Integer.valueOf(1) },
		{ "review.match.simulate.activation.refused", Integer.valueOf(1) } };

	private static void everyArgKeyFormatsCleanInFrench()
	{
		ReviewMessagesTest.everyArgKeyFormatsClean(Locale.FRENCH);
	}

	private static void everyArgKeyFormatsCleanInEnglish()
	{
		ReviewMessagesTest.everyArgKeyFormatsClean(Locale.ENGLISH);
	}

	private static void everyArgKeyFormatsClean(final Locale pLocale)
	{
		for (final Object[] entry : ReviewMessagesTest.ARG_KEYS)
		{
			final String key = (String) entry[0];
			final int argCount = ((Integer) entry[1]).intValue();
			final Object[] args = new Object[argCount];
			for (int i = 0; i < argCount; i++)
			{
				args[i] = "ARG" + i;
			}
			final String formatted = ReviewMessages.get(pLocale, key, args);
			ReviewMessagesTest.check(key + " (" + pLocale + "): not the raw key back (bundle must resolve it)", !key.equals(formatted));
			ReviewMessagesTest.check(key + " (" + pLocale + "): no unresolved {n} placeholder left: [" + formatted + "]", !formatted.contains("{"));
			for (int i = 0; i < argCount; i++)
			{
				ReviewMessagesTest.check(key + " (" + pLocale + "): argument " + i + " substituted: [" + formatted + "]", formatted.contains("ARG" + i));
			}
		}
	}

	/**
	 * The exact bug: {@code review.cascade.none} (French) reads "...Rien d'autre n'était ouvert..." - two apostrophes, neither adjacent to
	 * a placeholder, both doubled in the source ({@code d''autre}/{@code n''était}) per the MessageFormat escaping rule. Before the fix
	 * they were single, and MessageFormat silently turned "d'autre n'était" into "dautre nétait" (the quoted span between the two quotes
	 * loses its own delimiters as well as everything looking special inside it - here nothing else was inside, so only the quotes vanished).
	 */
	private static void cascadeNoneApostrophesSurvive()
	{
		final String formatted = ReviewMessages.get(Locale.FRENCH, "review.cascade.none", "Supplier");
		ReviewMessagesTest.check("no stray brace", !formatted.contains("{"));
		ReviewMessagesTest.check("entity name substituted", formatted.contains("Supplier"));
		ReviewMessagesTest.check("\"d'autre\" survives intact: [" + formatted + "]", formatted.contains("d'autre"));
		ReviewMessagesTest.check("\"n'était\" survives intact: [" + formatted + "]", formatted.contains("n'était"));
		ReviewMessagesTest.check("apostrophes did not get eaten into \"dautre\"", !formatted.contains("dautre"));
		ReviewMessagesTest.check("apostrophes did not get eaten into \"nétait\"", !formatted.contains(" nétait"));
	}

	/** {@code review.cascade.message} (both languages) uses the deliberate {@code ''{0}''} idiom to quote the entity name: the OUTPUT must show a single quote, not two. */
	private static void cascadeMessageDoubledApostropheStaysASingleQuoteInOutput()
	{
		final String formatted = ReviewMessages.get(Locale.ENGLISH, "review.cascade.message", "Supplier", Integer.valueOf(3));
		ReviewMessagesTest.check("single-quoted entity name in the output: [" + formatted + "]", formatted.contains("'Supplier'"));
		ReviewMessagesTest.check("never a doubled quote in the output", !formatted.contains("''"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
