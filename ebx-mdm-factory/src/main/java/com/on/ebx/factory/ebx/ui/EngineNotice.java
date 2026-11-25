package com.on.ebx.factory.ebx.ui;

import java.util.Locale;

import com.on.ebx.factory.ebx.EngineRefusal;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.wizard.EngineMessageCodec;

/**
 * Audit F-39: how the wizard words a refusal or a failure of the engine. A coded refusal ({@link EngineRefusal}) becomes its
 * short sentence in the person's language. Any other message of the engine is English and often technical: in English it is
 * shown as it is (it is the person's language, and the most precise text there is); in any other language the person reads a
 * localized lead sentence and finds the English text in a "details" fold. Pure (bundle only).
 */
public final class EngineNotice
{
	/** The sentence to show, and the raw text for the "details" fold (null: no fold). */
	public final String sentence;
	public final String detail;

	private EngineNotice(final String pSentence, final String pDetail)
	{
		this.sentence = pSentence;
		this.detail = pDetail == null || pDetail.trim().isEmpty() || pDetail.equals(pSentence) ? null : pDetail;
	}

	public static EngineNotice of(final Throwable pFailure, final Locale pLocale)
	{
		final String raw = ErrorText.of(pFailure);
		final EngineRefusal coded = EngineRefusal.find(pFailure);
		if (coded != null)
		{
			// a coded message: its sentence; the engine's own text goes to the fold when it says more than the sentence (wave 10), and
			// so does what a caller that added context wrote around it
			final String detail = coded == pFailure ? (coded.rawInDetail() ? raw : EngineNotice.causeText(coded)) : raw;
			return new EngineNotice(coded.sentence(pLocale), detail);
		}
		if (EngineNotice.hasNoMessage(pFailure))
		{
			// wave 10 (F-23): an exception with no message (a NullPointerException) is never shown as "null" or as a bare class name
			return new EngineNotice(EngineNotice.noMessageSentence(pFailure, pLocale), raw);
		}
		return EngineNotice.ofText(raw, pLocale);
	}

	/**
	 * The same for a text the engine stored rather than threw (a job's message). A message stored with its code
	 * ({@link EngineMessageCodec}) reads as its sentence in {@code pLocale}, the engine's English text going to the fold.
	 */
	public static EngineNotice ofText(final String pRaw, final Locale pLocale)
	{
		final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(pRaw);
		final String sentence = decoded.sentence(pLocale);
		if (sentence != null)
		{
			return new EngineNotice(sentence, decoded.raw);
		}
		final String raw = decoded.raw.trim();
		if (EngineNotice.english(pLocale) && !raw.isEmpty())
		{
			return new EngineNotice(raw, null);
		}
		return new EngineNotice(Messages.get(EngineNotice.class, pLocale, "engine.generic"), raw);
	}

	/** A sentence to insert in a larger localized message ({@code "Could not apply: {0}"}): the coded sentence, or the raw text; never "null". */
	public static String inline(final Throwable pFailure, final Locale pLocale)
	{
		final EngineRefusal coded = EngineRefusal.find(pFailure);
		if (coded != null)
		{
			return coded.sentence(pLocale);
		}
		return EngineNotice.hasNoMessage(pFailure) ? EngineNotice.noMessageSentence(pFailure, pLocale) : ErrorText.of(pFailure);
	}

	private static boolean hasNoMessage(final Throwable pFailure)
	{
		return pFailure == null || pFailure.getMessage() == null || pFailure.getMessage().trim().isEmpty();
	}

	/** "The action failed unexpectedly (NullPointerException)..." - the exception class plus a generic sentence, in the person's language. */
	static String noMessageSentence(final Throwable pFailure, final Locale pLocale)
	{
		return Messages.get(EngineNotice.class, pLocale, "engine.noMessage", pFailure == null ? "?" : pFailure.getClass().getSimpleName());
	}

	private static boolean english(final Locale pLocale)
	{
		return pLocale == null || Locale.ENGLISH.getLanguage().equals(pLocale.getLanguage());
	}

	private static String causeText(final Throwable pFailure)
	{
		final Throwable cause = pFailure.getCause();
		return cause == null || cause == pFailure ? null : ErrorText.of(cause);
	}
}
