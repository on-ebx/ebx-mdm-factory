package com.on.ebx.factory.wizard;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Labels of the wizard screen keys ({@code wizard.*}), read from the ONE shared bundle of the module's
 * UI screens ({@code com.on.ebx.factory.ebx.ui.Messages_en/fr.properties}) - same pattern, and the same
 * reason, as {@code com.on.ebx.factory.review.ReviewMessages}: this class must stay EBX-free
 * ({@link WizardPage} depends on it and is unit-tested without EBX), so the bundle name is hardcoded
 * rather than derived from this class's own package.
 */
public final class WizardMessages
{
	private static final String BUNDLE_NAME = "com.on.ebx.factory.ebx.ui.Messages";

	private WizardMessages()
	{
	}

	public static String get(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		String pattern;
		try
		{
			pattern = ResourceBundle.getBundle(WizardMessages.BUNDLE_NAME, pLocale == null ? Locale.ENGLISH : pLocale, WizardMessages.class.getClassLoader()).getString(pKey);
		}
		catch (final MissingResourceException ex)
		{
			try
			{
				pattern = ResourceBundle.getBundle(WizardMessages.BUNDLE_NAME, Locale.ENGLISH, WizardMessages.class.getClassLoader()).getString(pKey);
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

	/**
	 * wizard-visual-pass ROUND 3 (2026-09-24): "singular/plural via the bundle (one key per form)" - picks
	 * between an {@code .one} and an {@code .other} message key, English rule (singular only at exactly 1,
	 * plural everywhere else including 0). {@code {0}} is always the count; {@code pExtraArgs} fill
	 * {@code {1}}, {@code {2}}... Public (unlike the rest of this class's callers, which are all inside
	 * {@code wizard.*}) so a plain reader class in {@code ebx.*} (e.g. {@code StagingLoadGridReader}) can
	 * build a plural sentence without duplicating this logic - {@code WizardPage}'s own (private) {@code
	 * plural} delegates here.
	 */
	public static String plural(final Locale pLocale, final long pCount, final String pOneKey, final String pOtherKey, final Object... pExtraArgs)
	{
		final Object[] args = new Object[pExtraArgs.length + 1];
		args[0] = Long.valueOf(pCount);
		System.arraycopy(pExtraArgs, 0, args, 1, pExtraArgs.length);
		return WizardMessages.get(pLocale, pCount == 1 ? pOneKey : pOtherKey, args);
	}

	/**
	 * "N noun" with the noun's own singular/plural form ({@code pUnitKey + ".one"}/{@code ".many"}, the
	 * SAME two-suffix convention every existing {@code wizard.unit.*} key already uses - e.g. {@code
	 * wizard.unit.row.one}/{@code .many}, read by {@code ProjectWizardUserService}'s own {@code
	 * countWords}) - the building block for a sentence that names SEVERAL different counted nouns (a
	 * single {@link #plural} pair cannot cover more than one). Never a bare "(s)".
	 */
	public static String countWords(final Locale pLocale, final long pCount, final String pUnitKey)
	{
		return pCount + " " + WizardMessages.get(pLocale, pUnitKey + (pCount == 1 ? ".one" : ".many"));
	}
}
