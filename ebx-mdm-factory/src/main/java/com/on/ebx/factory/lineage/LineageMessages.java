package com.on.ebx.factory.lineage;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Labels of the record-lineage screen keys ({@code lineage.*}), read from the ONE shared bundle of the
 * module's UI screens: {@code com.on.ebx.factory.ebx.ui.Messages_en/fr.properties} - same convention, and
 * same reason, as {@code review.ReviewMessages}: {@link RecordLineagePage} depends on this class and is
 * unit-tested without EBX (see {@code RecordLineagePageTest}), so nothing here may import
 * {@code com.onwbp.base.text.UserMessage} or any other EBX type. The bundle name is hardcoded rather than
 * derived from this class's own package (which is {@code lineage}, not {@code ebx.ui}) - the module keeps
 * ONE bundle pair for every screen's labels, never one per screen, so a key is never translated twice.
 * Falls back to English, then to the raw key, the same two-step fallback {@code ebx.ui.Messages.get} and
 * {@code review.ReviewMessages.get} already use.
 */
final class LineageMessages
{
	/** Same bundle {@code com.on.ebx.factory.ebx.ui.Messages} reads for every other screen - see the class Javadoc for why this is hardcoded rather than derived from this class's own package. */
	private static final String BUNDLE_NAME = "com.on.ebx.factory.ebx.ui.Messages";

	private LineageMessages()
	{
	}

	static String get(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		final String bundleName = LineageMessages.BUNDLE_NAME;
		String pattern;
		try
		{
			pattern = ResourceBundle.getBundle(bundleName, pLocale == null ? Locale.ENGLISH : pLocale, LineageMessages.class.getClassLoader()).getString(pKey);
		}
		catch (final MissingResourceException ex)
		{
			try
			{
				pattern = ResourceBundle.getBundle(bundleName, Locale.ENGLISH, LineageMessages.class.getClassLoader()).getString(pKey);
			}
			catch (final MissingResourceException ex2)
			{
				return pKey;
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
