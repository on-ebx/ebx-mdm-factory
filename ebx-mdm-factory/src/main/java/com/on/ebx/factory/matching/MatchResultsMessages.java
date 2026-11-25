package com.on.ebx.factory.matching;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Labels of {@link MatchResultsView} ({@code results.*} keys), read from the SAME shared bundle every other
 * wizard/review screen uses ({@code com.on.ebx.factory.ebx.ui.Messages_en/fr.properties}) - identical
 * pattern to {@code wizard.WizardMessages}/{@code review.ReviewMessages}, a separate small class only
 * because this package must stay EBX-free and independent of {@code wizard} (no cross-dependency between
 * step 6's new "results" block and the rest of the wizard page beyond what {@code WizardPage} itself wires
 * together). The keys themselves live in one contiguous block of the shared properties files, prefixed
 * {@code wizard.step6.results.*} (not merely {@code results.*} - see {@link #get}) so they sort next to the
 * rest of step 6's keys and never collide with a key the step 7 (Summary) lot adds elsewhere in the same
 * file - task brief: "keep your message keys in the step-6 section of the bundles to limit merge conflicts".
 */
final class MatchResultsMessages
{
	private static final String BUNDLE_NAME = "com.on.ebx.factory.ebx.ui.Messages";
	private static final String PREFIX = "wizard.step6.";

	private MatchResultsMessages()
	{
	}

	static String get(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		final String fullKey = MatchResultsMessages.PREFIX + pKey;
		String pattern;
		try
		{
			pattern = ResourceBundle.getBundle(MatchResultsMessages.BUNDLE_NAME, pLocale == null ? Locale.ENGLISH : pLocale, MatchResultsMessages.class.getClassLoader())
				.getString(fullKey);
		}
		catch (final MissingResourceException ex)
		{
			try
			{
				pattern = ResourceBundle.getBundle(MatchResultsMessages.BUNDLE_NAME, Locale.ENGLISH, MatchResultsMessages.class.getClassLoader()).getString(fullKey);
			}
			catch (final MissingResourceException ex2)
			{
				return fullKey;
			}
		}
		if (pArgs == null || pArgs.length == 0)
		{
			return pattern;
		}
		try
		{
			return new MessageFormat(pattern, pLocale == null ? Locale.ENGLISH : pLocale).format(pArgs);
		}
		catch (final IllegalArgumentException ex)
		{
			return pattern;
		}
	}
}
