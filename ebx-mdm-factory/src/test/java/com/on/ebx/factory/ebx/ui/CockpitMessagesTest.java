package com.on.ebx.factory.ebx.ui;

import java.util.Locale;

/**
 * JUnit-free, like {@code com.on.ebx.factory.review.ReviewMessagesTest} on the {@code review.*} keys of the
 * same shared bundle ({@code com.on.ebx.factory.ebx.ui.Messages}): guards every {@code connection.*} and
 * {@code cockpit.*} key that is formatted with {@code {n}} arguments against the exact defect that test
 * class documents — {@code java.text.MessageFormat} silently eats a single, un-doubled apostrophe in a
 * pattern that also has a placeholder (the apostrophe, and everything up to the next one, including the
 * placeholder itself if it falls inside the accidental quoted span, simply vanishes; no exception). French
 * is the language actually at risk (English avoids apostrophes in this bundle by construction), so every key
 * is formatted in both locales with distinguishable dummy arguments and checked for two things: every
 * {@code {n}} placeholder was substituted, and the result is not the bare key (the bundle must resolve it).
 */
public final class CockpitMessagesTest
{
	/** Every {@code connection.*}/{@code cockpit.*} key formatted with one or more {@code {n}} arguments, and how many. */
	private static final Object[][] ARG_KEYS = { { "connection.results.ok", Integer.valueOf(1) }, { "connection.results.okInJvm", Integer.valueOf(1) }, { "connection.results.okSession", Integer.valueOf(1) }, { "connection.results.llm.ok", Integer.valueOf(4) }, { "connection.results.llm.bad", Integer.valueOf(2) },
		{ "connection.endpoint", Integer.valueOf(2) }, { "connection.endpoint.none", Integer.valueOf(1) }, { "connection.namespace", Integer.valueOf(1) },
		{ "connection.save.failed", Integer.valueOf(1) }, { "cockpit.title", Integer.valueOf(1) }, { "cockpit.alerts.line", Integer.valueOf(3) },
		{ "cockpit.pipeline.when.running", Integer.valueOf(1) }, { "cockpit.pipeline.runs.title", Integer.valueOf(1) }, { "cockpit.when.minutes", Integer.valueOf(1) },
		{ "cockpit.when.hours", Integer.valueOf(1) }, { "cockpit.when.days", Integer.valueOf(1) }, { "cockpit.volumes.fedBy", Integer.valueOf(1) },
		{ "cockpit.volumes.rejectedCount", Integer.valueOf(1) }, { "cockpit.relations.evidence", Integer.valueOf(2) }, { "cockpit.agent.line", Integer.valueOf(5) },
		// delivery lot 2: the French pattern has two apostrophes around the placeholder (l''instance ... s''exécutent)
		// - exactly the shape this test class exists to guard, see its own Javadoc
		{ "cockpit.delivered", Integer.valueOf(1) } };

	public static void main(final String[] pArgs)
	{
		CockpitMessagesTest.everyArgKeyFormatsCleanInFrench();
		CockpitMessagesTest.everyArgKeyFormatsCleanInEnglish();

		System.out.println("CockpitMessagesTest OK");
	}

	private static void everyArgKeyFormatsCleanInFrench()
	{
		CockpitMessagesTest.everyArgKeyFormatsClean(Locale.FRENCH);
	}

	private static void everyArgKeyFormatsCleanInEnglish()
	{
		CockpitMessagesTest.everyArgKeyFormatsClean(Locale.ENGLISH);
	}

	private static void everyArgKeyFormatsClean(final Locale pLocale)
	{
		for (final Object[] entry : CockpitMessagesTest.ARG_KEYS)
		{
			final String key = (String) entry[0];
			final int argCount = ((Integer) entry[1]).intValue();
			final Object[] args = new Object[argCount];
			for (int i = 0; i < argCount; i++)
			{
				args[i] = "ARG" + i;
			}
			final String formatted = Messages.get(CockpitPage.class, pLocale, key, args);
			CockpitMessagesTest.check(key + " (" + pLocale + "): not the raw key back (bundle must resolve it)", !key.equals(formatted));
			CockpitMessagesTest.check(key + " (" + pLocale + "): no unresolved {n} placeholder left: [" + formatted + "]", formatted.indexOf('{') < 0);
			for (int i = 0; i < argCount; i++)
			{
				CockpitMessagesTest.check(key + " (" + pLocale + "): argument " + i + " substituted: [" + formatted + "]", formatted.indexOf("ARG" + i) >= 0);
			}
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
