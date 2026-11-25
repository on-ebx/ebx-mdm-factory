package com.on.ebx.factory.wizard;

import java.util.Locale;

import com.on.ebx.factory.staging.CheckNote;
import com.on.ebx.factory.staging.NoteTexts;

/**
 * Staging last points, item 1 (2026-10-17): the text a screen shows for the rationale of a mapping or of a mapping step. What the staging proposal passes wrote
 * about a link or a step ({@link NoteTexts}: the "Value check" notes, the normalisation and code table rationales) is worded in the viewer's language, whether it
 * carries its marker (written since this change) or is the bare English of an older row; the proposer's own text (the language model's words) is shown as it is.
 * A rationale with a "Value check" note comes back as the proposer's text, then " || ", then the localized "Value check: ..." label (the {@code [was N]} stays
 * internal). Pure, EBX-free.
 */
public final class RationaleText
{
	private RationaleText()
	{
	}

	/** The words of a language: the shared module bundle, null for a key it lacks. */
	static NoteTexts.Words words(final Locale pLocale)
	{
		return new NoteTexts.Words()
		{
			@Override
			public String get(final String pKey, final Object... pArgs)
			{
				final String text = WizardMessages.get(pLocale == null ? Locale.ENGLISH : pLocale, pKey, pArgs);
				return pKey.equals(text) ? null : text;
			}
		};
	}

	/** A text that may be one note (and a free tail): the note worded in {@code pLocale}, or the text without any marker. */
	public static String one(final String pStored, final Locale pLocale)
	{
		if (pStored == null || pStored.isEmpty())
		{
			return "";
		}
		final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(pStored);
		if (!decoded.coded())
		{
			return pStored;
		}
		final String worded = NoteTexts.display(decoded, RationaleText.words(pLocale));
		return worded != null ? worded : decoded.raw;
	}

	/** The "Value check" note of a rationale worded in {@code pLocale} (no label, no {@code [was N]}), or empty when it has none. */
	public static String valueCheck(final String pRationale, final Locale pLocale)
	{
		return RationaleText.one(CheckNote.noteOf(pRationale), pLocale);
	}

	/** The rationale worded in {@code pLocale}; empty for null or empty. */
	public static String display(final String pStored, final Locale pLocale)
	{
		if (pStored == null || pStored.isEmpty())
		{
			return "";
		}
		if (!CheckNote.has(pStored))
		{
			return RationaleText.one(pStored, pLocale);
		}
		final String base = RationaleText.one(CheckNote.base(pStored), pLocale).trim();
		final String note = RationaleText.valueCheck(pStored, pLocale);
		if (note.isEmpty())
		{
			return base;
		}
		final String label = WizardMessages.get(pLocale == null ? Locale.ENGLISH : pLocale, "mappingMatrix.check.valueCheck", note);
		return base.isEmpty() ? label : base + " || " + label;
	}
}
