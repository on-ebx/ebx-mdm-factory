package com.on.ebx.factory.mappingmatrix;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Labels of the Mapping matrix screen keys ({@code mappingMatrix.*}), read from the ONE shared bundle of
 * the module's UI screens ({@code com.on.ebx.factory.ebx.ui.Messages_en/fr.properties}) - same pattern,
 * and the same reason, as {@code wizard.WizardMessages}/{@code review.ReviewMessages}: this class must
 * stay EBX-free ({@link MatrixHtml} depends on it and is unit-tested without EBX), so the bundle name is
 * hardcoded rather than derived from this class's own package.
 */
public final class MatrixMessages
{
	private static final String BUNDLE_NAME = "com.on.ebx.factory.ebx.ui.Messages";

	private MatrixMessages()
	{
	}

	/**
	 * The raw text of {@code pKey} for the locale (the English file when the locale's own has none), or {@code null} when no file has the key - for the texts
	 * that are optional by design (lot G2: the localized documentation of a library function, which falls back to the library's own text).
	 */
	public static String find(final Locale pLocale, final String pKey)
	{
		final String text = MatrixMessages.get(pLocale, pKey);
		return pKey.equals(text) ? null : text;
	}

	public static String get(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		String pattern;
		try
		{
			pattern = ResourceBundle.getBundle(MatrixMessages.BUNDLE_NAME, pLocale == null ? Locale.ENGLISH : pLocale, MatrixMessages.class.getClassLoader())
				.getString(pKey);
		}
		catch (final MissingResourceException ex)
		{
			try
			{
				pattern = ResourceBundle.getBundle(MatrixMessages.BUNDLE_NAME, Locale.ENGLISH, MatrixMessages.class.getClassLoader()).getString(pKey);
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
