package com.on.ebx.factory.core;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * A text of the module's one message bundle ({@code com.on.ebx.factory.ebx.ui.Messages_en/fr.properties}) for code that must not
 * import an EBX type (the pure guards of wave 10: {@link WriterGuard}, {@link MameSchemaWatch}). Same convention and same fallback as
 * {@code lineage.LineageMessages} and {@code review.ReviewMessages}: the requested locale, then English, then the raw key.
 */
public final class ModuleMessages
{
	private static final String BUNDLE_NAME = "com.on.ebx.factory.ebx.ui.Messages";

	private ModuleMessages()
	{
	}

	public static String get(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		String pattern;
		try
		{
			pattern = ResourceBundle.getBundle(ModuleMessages.BUNDLE_NAME, pLocale == null ? Locale.ENGLISH : pLocale, ModuleMessages.class.getClassLoader()).getString(pKey);
		}
		catch (final MissingResourceException ex)
		{
			try
			{
				pattern = ResourceBundle.getBundle(ModuleMessages.BUNDLE_NAME, Locale.ENGLISH, ModuleMessages.class.getClassLoader()).getString(pKey);
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
