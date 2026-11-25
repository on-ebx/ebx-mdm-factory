package com.on.ebx.factory.review;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Labels of the review screen keys ({@code review.*}), read from the ONE shared bundle of the module's UI
 * screens: {@code com.on.ebx.factory.ebx.ui.Messages_en/fr.properties} (Connexion's {@code connection.*},
 * Cockpit's {@code cockpit.*}, this screen's {@code review.*} - one bundle pair, not one per screen, so a
 * key is never translated twice). {@code com.on.ebx.factory.ebx.ui.Messages} is the general-purpose,
 * EBX-bound wrapper used by the service declarations (it takes a {@code Class<?>} and derives the bundle
 * from ITS caller's package, which is also {@code ebx.ui}); this class hardcodes that same bundle name
 * instead, because it must stay EBX-free ({@link ReviewPage} depends on it and is unit-tested without EBX,
 * so nothing here may import {@code com.onwbp.base.text.UserMessage} or any other EBX type). Falls back to
 * English, then to the raw key, the same two-step fallback as {@code ebx.ui.Messages.get} and the sibling
 * data-catalog module's {@code Messages.get}.
 */
public final class ReviewMessages
{
	/** Same bundle {@code com.on.ebx.factory.ebx.ui.Messages} reads for every other screen - see the class Javadoc for why this is hardcoded rather than derived from this class's own package. */
	private static final String BUNDLE_NAME = "com.on.ebx.factory.ebx.ui.Messages";

	private ReviewMessages()
	{
	}

	public static String get(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		final String bundleName = ReviewMessages.BUNDLE_NAME;
		String pattern;
		try
		{
			pattern = ResourceBundle.getBundle(bundleName, pLocale == null ? Locale.ENGLISH : pLocale, ReviewMessages.class.getClassLoader()).getString(pKey);
		}
		catch (final MissingResourceException ex)
		{
			try
			{
				pattern = ResourceBundle.getBundle(bundleName, Locale.ENGLISH, ReviewMessages.class.getClassLoader()).getString(pKey);
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
