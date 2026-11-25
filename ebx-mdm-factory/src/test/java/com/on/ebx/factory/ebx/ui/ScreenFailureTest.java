package com.on.ebx.factory.ebx.ui;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** JUnit-free: run the main, a failure throws. Audit F-41: the reference, the localized sentence, and what is never swallowed. */
public final class ScreenFailureTest
{
	public static void main(final String[] pArgs)
	{
		ScreenFailureTest.referenceIsShortAndUnambiguous();
		ScreenFailureTest.noticeIsLocalizedAndCarriesTheReference();
		ScreenFailureTest.noticeHtmlIsEscapedAndNeverAStack();
		ScreenFailureTest.fatalErrorsAreRethrown();
		ScreenFailureTest.logLineNamesScreenAndPlace();
		System.out.println("ScreenFailureTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static void referenceIsShortAndUnambiguous()
	{
		final Set<String> seen = new HashSet<String>();
		for (int i = 0; i < 2000; i++)
		{
			final String reference = ScreenFailure.newReference();
			ScreenFailureTest.check(reference.matches("MF-[2-9A-HJ-NP-Z]{6}"), "unexpected reference " + reference);
			seen.add(reference);
		}
		ScreenFailureTest.check(seen.size() > 1990, "references collide too often: " + seen.size());
	}

	private static void noticeIsLocalizedAndCarriesTheReference()
	{
		final String en = ScreenFailure.notice(Locale.ENGLISH, "MF-ABC234");
		ScreenFailureTest.check(en.equals("Something went wrong (reference MF-ABC234). The administrator can find the details in the log."), en);
		final String fr = ScreenFailure.notice(Locale.FRENCH, "MF-ABC234");
		ScreenFailureTest.check(fr.contains("MF-ABC234") && fr.contains("L'administrateur") && fr.startsWith("Un problème"), fr);
	}

	private static void noticeHtmlIsEscapedAndNeverAStack()
	{
		final String html = ScreenFailure.noticeHtml(Locale.ENGLISH, "MF-<b>");
		ScreenFailureTest.check(!html.contains("<b>") && html.contains("role=\"alert\"") && html.contains("mdmf-notice-warn"), html);
	}

	private static void fatalErrorsAreRethrown()
	{
		ScreenFailure.rethrowIfFatal(new IllegalStateException("plain"));
		ScreenFailure.rethrowIfFatal(new NoClassDefFoundError("after an upgrade"));
		try
		{
			ScreenFailure.rethrowIfFatal(new OutOfMemoryError("heap"));
			throw new AssertionError("an OutOfMemoryError must not be swallowed");
		}
		catch (final OutOfMemoryError expected)
		{
			// rethrown
		}
	}

	private static void logLineNamesScreenAndPlace()
	{
		final String line = ScreenFailure.logLine("MF-ABC234", "wizard", "action LANDING_RUN", new IllegalStateException("boom"));
		ScreenFailureTest.check(line.equals("[mdm-factory] screen wizard failed in action LANDING_RUN (reference MF-ABC234): java.lang.IllegalStateException: boom"), line);
	}
}
