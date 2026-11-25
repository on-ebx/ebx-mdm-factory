package com.on.ebx.factory.ebx.ui;

import java.util.Arrays;
import java.util.Locale;

import com.on.ebx.factory.ebx.EngineRefusal;
import com.orchestranetworks.service.OperationException;

/**
 * JUnit-free: run the main, a failure throws. Audit F-39: a coded refusal reads in the person's language and keeps its English
 * text for logs and REST; any other engine text is shown as is in English and under a localized lead sentence otherwise.
 */
public final class EngineNoticeTest
{
	private static final Locale FR = Locale.FRENCH;

	public static void main(final String[] pArgs)
	{
		EngineNoticeTest.codedRefusalIsLocalizedAndKeepsItsEnglishText();
		EngineNoticeTest.uncodedTextIsShownAsIsInEnglishAndFoldedOtherwise();
		EngineNoticeTest.codedRefusalIsFoundUnderAWrapper();
		EngineNoticeTest.jobKindsAreLabelled();
		EngineNoticeTest.everyCodedKeyHasBothLanguagesAndKeepsItsApostrophes();
		System.out.println("EngineNoticeTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static void codedRefusalIsLocalizedAndKeepsItsEnglishText()
	{
		final EngineRefusal refusal = EngineRefusal.projectNotFound("PersVol4");
		EngineNoticeTest.check("Project PersVol4 not found".equals(refusal.getMessage()), refusal.getMessage());
		EngineNoticeTest.check("engine.projectNotFound".equals(refusal.code()), refusal.code());
		final EngineNotice fr = EngineNotice.of(refusal, EngineNoticeTest.FR);
		EngineNoticeTest.check(fr.sentence.startsWith("Le projet PersVol4 est introuvable"), fr.sentence);
		EngineNoticeTest.check(fr.detail == null, "no fold for a plain coded refusal: " + fr.detail);
		EngineNoticeTest.check(refusal.getMessageForLocale(EngineNoticeTest.FR).equals(fr.sentence), "getMessageForLocale");
		final EngineNotice en = EngineNotice.of(refusal, Locale.ENGLISH);
		EngineNoticeTest.check(en.sentence.startsWith("Project PersVol4 was not found"), en.sentence);
	}

	private static void uncodedTextIsShownAsIsInEnglishAndFoldedOtherwise()
	{
		final OperationException raw = OperationException.createError("apply_data_model_patch could not apply op 3");
		final EngineNotice en = EngineNotice.of(raw, Locale.ENGLISH);
		EngineNoticeTest.check("apply_data_model_patch could not apply op 3".equals(en.sentence) && en.detail == null, en.sentence + " / " + en.detail);
		final EngineNotice fr = EngineNotice.of(raw, EngineNoticeTest.FR);
		EngineNoticeTest.check(fr.sentence.startsWith("L'action n'a pas abouti"), fr.sentence);
		EngineNoticeTest.check("apply_data_model_patch could not apply op 3".equals(fr.detail), String.valueOf(fr.detail));
		EngineNoticeTest.check("apply_data_model_patch could not apply op 3".equals(EngineNotice.inline(raw, EngineNoticeTest.FR)), "inline keeps the raw text");
	}

	private static void codedRefusalIsFoundUnderAWrapper()
	{
		final OperationException wrapped = OperationException.createError("Could not save the source: Another application of this project already uses the code ERP.",
			EngineRefusal.simple("appCode.taken", "Another application of this project already uses the code ERP.", "ERP"));
		final EngineNotice fr = EngineNotice.of(wrapped, EngineNoticeTest.FR);
		EngineNoticeTest.check("Une autre application de ce projet utilise déjà le code ERP.".equals(fr.sentence), fr.sentence);
		EngineNoticeTest.check(fr.detail != null && fr.detail.startsWith("Could not save the source"), String.valueOf(fr.detail));
		EngineNoticeTest.check(EngineNotice.inline(wrapped, EngineNoticeTest.FR).equals(fr.sentence), "inline uses the coded sentence");
	}

	private static void jobKindsAreLabelled()
	{
		final EngineRefusal refusal = EngineRefusal.jobsPending("2 job(s) of this project are waiting or running (MATCHING_RUN running, X_UNKNOWN queued).",
			Arrays.asList("MATCHING_RUN", "X_UNKNOWN"));
		final String fr = refusal.sentence(EngineNoticeTest.FR);
		EngineNoticeTest.check(fr.contains("(Rapprochement") || fr.contains("X_UNKNOWN"), fr);
		EngineNoticeTest.check(fr.contains("X_UNKNOWN") && fr.contains("qu'elles"), fr);
		final String en = refusal.sentence(Locale.ENGLISH);
		EngineNoticeTest.check(en.contains("Running the matching, X_UNKNOWN"), en);
	}

	private static void everyCodedKeyHasBothLanguagesAndKeepsItsApostrophes()
	{
		final String[][] keys = { { "engine.projectNotFound", "1" }, { "engine.busy", "3" }, { "engine.jobsPending", "1" }, { "engine.queueFailed", "1" },
			{ "engine.llm.noConnectors", "1" }, { "engine.llm.missing", "1" }, { "engine.llm.notLlm", "1" }, { "engine.llm.inactive", "1" },
			{ "engine.appCode.invalid", "1" }, { "engine.appCode.taken", "1" }, { "engine.justStarted", "0" }, { "engine.llm.notConfigured", "0" },
			{ "engine.appCode.blank", "0" }, { "engine.source.landingGenerated", "0" }, { "engine.source.landingPlanned", "0" }, { "engine.table.scopeFrozen", "0" },
			{ "engine.table.keyFrozen", "0" }, { "engine.generic", "0" }, { "engine.details", "0" } };
		for (final String[] key : keys)
		{
			final Object[] args = new Object[Integer.parseInt(key[1])];
			for (int i = 0; i < args.length; i++)
			{
				args[i] = "ARG" + i;
			}
			for (final Locale locale : new Locale[] { Locale.ENGLISH, EngineNoticeTest.FR })
			{
				final String text = Messages.get(EngineNotice.class, locale, key[0], args);
				EngineNoticeTest.check(!text.equals(key[0]), "missing " + key[0] + " in " + locale);
				EngineNoticeTest.check(!text.contains("''") && !text.contains("{"), key[0] + " in " + locale + ": " + text);
				for (int i = 0; i < args.length; i++)
				{
					EngineNoticeTest.check(text.contains("ARG" + i), key[0] + " in " + locale + " lost {" + i + "}: " + text);
				}
			}
		}
		// French text actually is French (a missing fr key would silently fall back to English)
		EngineNoticeTest.check(Messages.get(EngineNotice.class, EngineNoticeTest.FR, "engine.justStarted").startsWith("Une tâche"), "fr bundle");
	}
}
