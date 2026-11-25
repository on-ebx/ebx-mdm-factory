package com.on.ebx.factory.stagingdesk;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Labels of the Validation desk ({@code desk.*}), read from this package's own bundle ({@code DeskMessages_en/fr.properties}, ASCII only, accents as {@code \}{@code uXXXX}).
 * Pure: no EBX class, so {@link DeskHtml} stays unit-testable. The English file answers for a locale that has no file of its own, and a missing key answers the key itself.
 * Texts the desk shares with the Mapping matrix (the kind of an item, why it is still to decide) are read from the matrix' own keys through {@code MatrixMessages}, not copied here.
 */
public final class DeskMessages
{
	private static final String BUNDLE_NAME = "com.on.ebx.factory.stagingdesk.DeskMessages";

	private DeskMessages()
	{
	}

	public static String get(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		String pattern;
		try
		{
			pattern = ResourceBundle.getBundle(DeskMessages.BUNDLE_NAME, pLocale == null ? Locale.ENGLISH : pLocale, DeskMessages.class.getClassLoader()).getString(pKey);
		}
		catch (final MissingResourceException ex)
		{
			try
			{
				pattern = ResourceBundle.getBundle(DeskMessages.BUNDLE_NAME, Locale.ENGLISH, DeskMessages.class.getClassLoader()).getString(pKey);
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

	/** {@code pOneKey} for exactly one, {@code pOtherKey} for every other count (0 included); {@code {0}} is the count, {@code pExtraArgs} follow. */
	public static String plural(final Locale pLocale, final long pCount, final String pOneKey, final String pOtherKey, final Object... pExtraArgs)
	{
		final Object[] args = new Object[1 + (pExtraArgs == null ? 0 : pExtraArgs.length)];
		args[0] = Long.valueOf(pCount);
		for (int i = 0; pExtraArgs != null && i < pExtraArgs.length; i++)
		{
			args[i + 1] = pExtraArgs[i];
		}
		return DeskMessages.get(pLocale, pCount == 1L ? pOneKey : pOtherKey, args);
	}

	/** "N noun" with the noun's own singular or plural form: {@code desk.unit.<unit>.one} / {@code .other} hold the noun only. */
	public static String count(final Locale pLocale, final long pCount, final String pUnit)
	{
		return pCount + " " + DeskMessages.get(pLocale, "desk.unit." + pUnit + (pCount == 1L ? ".one" : ".other"));
	}
}
