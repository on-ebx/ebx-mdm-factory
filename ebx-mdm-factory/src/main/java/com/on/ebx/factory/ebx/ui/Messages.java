package com.on.ebx.factory.ebx.ui;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import com.onwbp.base.text.Severity;
import com.onwbp.base.text.UserMessage;

/**
 * Labels of the module's UI screens, read from the {@code Messages_xx.properties} bundle next to the
 * calling class ({@code com.on.ebx.factory.ebx.ui.Messages_en/fr.properties}), following the same pattern
 * as {@code com.on.ebx.catalog.ebx.ui.Messages} in ebx-data-catalog. The bundle files are plain
 * {@code .properties}, so accented characters are written as {@code \\uXXXX} escapes (ISO-8859-1 rule) and
 * copied to {@code target/classes} by {@code build.sh}, not compiled.
 */
public final class Messages
{
	private Messages()
	{
	}

	public static String get(final Class<?> pClass, final Locale pLocale, final String pKey, final Object... pArgs)
	{
		final String bundleName = pClass.getPackage().getName() + ".Messages";
		String pattern;
		try
		{
			pattern = ResourceBundle.getBundle(bundleName, pLocale == null ? Locale.ENGLISH : pLocale, pClass.getClassLoader()).getString(pKey);
		}
		catch (final MissingResourceException ex)
		{
			try
			{
				pattern = ResourceBundle.getBundle(bundleName, Locale.ENGLISH, pClass.getClassLoader()).getString(pKey);
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

	public static UserMessage getInfo(final Class<?> pClass, final String pKey, final Object... pArgs)
	{
		return new Localized(pClass, pKey, pArgs, Severity.INFO);
	}

	/** Audit F-02: an error resolved in the locale of the user who sees it (the role enumeration of the table "Access rights"). */
	public static UserMessage getError(final Class<?> pClass, final String pKey, final Object... pArgs)
	{
		return new Localized(pClass, pKey, pArgs, Severity.ERROR);
	}

	/** A message resolved in the locale of the user who sees it, for a service declaration's label/description. */
	static final class Localized extends UserMessage
	{
		private static final long serialVersionUID = 1L;
		private final Class<?> owner;
		private final String key;
		private final Object[] args;

		Localized(final Class<?> pOwner, final String pKey, final Object[] pArgs, final Severity pSeverity)
		{
			super(pSeverity, pArgs == null ? new Object[0] : pArgs);
			this.owner = pOwner;
			this.key = pKey;
			this.args = pArgs == null ? new Object[0] : pArgs;
		}

		@Override
		protected String lookupRawString(final Locale pLocale)
		{
			return Messages.get(this.owner, pLocale, this.key, this.args);
		}

		@Override
		protected String getRawStringOrNull(final Locale pLocale)
		{
			return Messages.get(this.owner, pLocale, this.key, this.args);
		}

		@Override
		public String getInternalId()
		{
			return this.owner.getName() + "#" + this.key;
		}

		@Override
		protected UserMessage copy(final Object[] pArgs)
		{
			return new Localized(this.owner, this.key, pArgs, this.getSeverity());
		}
	}
}
