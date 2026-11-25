package com.on.ebx.factory.ebx.ui;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

import com.on.ebx.factory.mappingmatrix.MatrixMessages;

/**
 * Staging-screens lot (2026-10-17): the Mapping matrix header printed "Latelier propose {0}, alimentes par {1}..." - the French pattern had ONE apostrophe next to
 * placeholders, and {@link MessageFormat} reads a lone apostrophe as the start of a quoted span: the apostrophe vanishes and every {n} after it stays as written. The
 * per-screen tests guarded the keys one list at a time; this one reads the WHOLE shared bundle, in both languages, so a new key with the same shape fails here
 * whatever its screen. Rule of the bundle: a message that carries a placeholder doubles each apostrophe ({@code ''}) or uses the typographic one; a message with no
 * placeholder is returned as written and keeps a single apostrophe.
 */
public final class BundleApostropheTest
{
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d");
	private static final Pattern LONE_APOSTROPHE = Pattern.compile("(?<!')'(?!')");

	public static void main(final String[] pArgs)
	{
		BundleApostropheTest.noLoneApostropheNextToAPlaceholder(Locale.ENGLISH);
		BundleApostropheTest.noLoneApostropheNextToAPlaceholder(Locale.FRENCH);
		BundleApostropheTest.theMatrixSummaryHeaderFormats();
		System.out.println("BundleApostropheTest OK");
	}

	private static void noLoneApostropheNextToAPlaceholder(final Locale pLocale)
	{
		final ResourceBundle bundle = ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", pLocale);
		final List<String> bad = new ArrayList<String>();
		final Enumeration<String> keys = bundle.getKeys();
		while (keys.hasMoreElements())
		{
			final String key = keys.nextElement();
			final String pattern = bundle.getString(key);
			if (BundleApostropheTest.PLACEHOLDER.matcher(pattern).find() && BundleApostropheTest.LONE_APOSTROPHE.matcher(pattern).find())
			{
				bad.add(key);
			}
		}
		if (!bad.isEmpty())
		{
			throw new AssertionError(pLocale + ": single apostrophe next to a placeholder (MessageFormat eats it): " + bad);
		}
	}

	/** The exact sentence of the live report: all five placeholders substituted, the apostrophe kept. */
	private static void theMatrixSummaryHeaderFormats()
	{
		final String fr = MatrixMessages.get(Locale.FRENCH, "mappingMatrix.summary", "3 objets métier", "3 applications", "40 liens", "15 règles", "11 contrôles");
		if (fr.indexOf('{') >= 0 || !fr.startsWith("L'atelier propose 3 objets métier, alimentés par 3 applications, 40 liens, 15 règles et 11 contrôles"))
		{
			throw new AssertionError(fr);
		}
		final String en = MatrixMessages.get(Locale.ENGLISH, "mappingMatrix.summary", "3 business objects", "3 applications", "40 links", "15 rules", "11 checks");
		if (en.indexOf('{') >= 0 || !en.contains("proposes 3 business objects")) 
		{
			throw new AssertionError(en);
		}
	}
}
