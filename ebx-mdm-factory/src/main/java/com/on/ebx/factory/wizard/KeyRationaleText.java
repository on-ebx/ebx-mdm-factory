package com.on.ebx.factory.wizard;

import java.util.Locale;

import com.on.ebx.factory.landing.KeyTexts;

/**
 * Key rationale i18n (2026-10-17): the text a screen shows for a key rationale or a key-related table note. What the Factory itself wrote about a
 * key ({@link KeyTexts}) is worded in the viewer's language, whether it carries its code marker (written since this lot) or is the bare English
 * sentence of an older row; anything else (the language model's own rationale, a connector's error) is free text and comes back as it is, without
 * any marker. Pure, EBX-free.
 */
public final class KeyRationaleText
{
	private KeyRationaleText()
	{
	}

	/** The words of a language: the shared module bundle, null for a key it lacks. */
	private static KeyTexts.Words words(final Locale pLocale)
	{
		return new KeyTexts.Words()
		{
			@Override
			public String get(final String pKey, final Object... pArgs)
			{
				final String text = WizardMessages.get(pLocale, pKey, pArgs);
				return pKey.equals(text) ? null : text;
			}
		};
	}

	/** The stored text in {@code pLocale}; empty for null or empty. */
	public static String display(final String pStored, final Locale pLocale)
	{
		if (pStored == null || pStored.isEmpty())
		{
			return "";
		}
		final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(pStored);
		final String raw = decoded.raw;
		String key = null;
		Object[] args = null;
		String tail = null;
		if (decoded.coded() && decoded.key.startsWith(KeyTexts.P))
		{
			key = decoded.key;
			args = decoded.args;
			if (KeyTexts.PENDING.equals(key))
			{
				// the rationale that followed the coded head: what is left of the English once its own head is taken off
				final String english = KeyTexts.render(KeyTexts.ENGLISH_WORDS, key, args);
				tail = english != null && raw.startsWith(english) ? raw.substring(english.length()).trim() : "";
			}
		}
		else
		{
			final KeyTexts.Parsed parsed = KeyTexts.parse(raw);
			if (parsed != null)
			{
				key = parsed.key;
				args = parsed.args;
				tail = parsed.tail;
			}
		}
		if (key == null)
		{
			return raw;
		}
		final String text = KeyTexts.render(KeyRationaleText.words(pLocale), key, args);
		if (text == null)
		{
			return raw;
		}
		return tail == null || tail.isEmpty() ? text : text + " " + KeyRationaleText.display(tail, pLocale);
	}
}
