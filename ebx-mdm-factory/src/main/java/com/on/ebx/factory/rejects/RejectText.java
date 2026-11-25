package com.on.ebx.factory.rejects;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Staging load and rejects lot (2026-10-17): the raw text of an engine failure, made readable. EBX hands its exception messages back already HTML-escaped ({@code &gt; Caused by:}),
 * and the review screen escaped them a second time (the literal "&amp;gt;" of the live test), so the text is unescaped ONCE here, where it is stored, and escaped once where it is
 * shown. Pure.
 */
public final class RejectText
{
	private static final Pattern ENTITY = Pattern.compile("&(?:(lt|gt|amp|quot|apos)|#(\\d{1,6})|#[xX]([0-9a-fA-F]{1,5}));");

	private RejectText()
	{
	}

	/** The HTML entities of {@code pText} replaced by their characters, once (a text that never held one is returned as it is). */
	public static String unescapeEntities(final String pText)
	{
		if (pText == null || pText.indexOf('&') < 0)
		{
			return pText == null ? "" : pText;
		}
		final Matcher matcher = RejectText.ENTITY.matcher(pText);
		final StringBuffer out = new StringBuffer(pText.length());
		while (matcher.find())
		{
			String replacement;
			if (matcher.group(1) != null)
			{
				final String name = matcher.group(1);
				replacement = "lt".equals(name) ? "<" : "gt".equals(name) ? ">" : "amp".equals(name) ? "&" : "quot".equals(name) ? "\"" : "'";
			}
			else
			{
				final int code = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : Integer.parseInt(matcher.group(3), 16);
				replacement = Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : "";
			}
			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/** Unescaped, line breaks (including EBX's {@code \n\r}) turned into single spaces, trimmed: the text on one line. */
	public static String oneLine(final String pText)
	{
		return RejectText.unescapeEntities(pText).replaceAll("[\\r\\n]+", " ").replaceAll("[ \\t]{2,}", " ").trim();
	}

	/** Unescaped with the line breaks normalised to {@code \n} and surrounding blanks removed: the form a folded detail is shown in. */
	public static String detail(final String pText)
	{
		return RejectText.unescapeEntities(pText).replaceAll("\\r\\n|\\n\\r|\\r", "\n").trim();
	}

	/** {@code pText} cut to {@code pMax} characters with an ellipsis. */
	public static String cut(final String pText, final int pMax)
	{
		final String text = pText == null ? "" : pText;
		return text.length() <= pMax ? text : text.substring(0, Math.max(0, pMax - 3)) + "...";
	}
}
