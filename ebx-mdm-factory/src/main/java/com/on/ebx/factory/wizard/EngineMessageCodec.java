package com.on.ebx.factory.wizard;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wave 10 (audit F-39): a failure the engine stored as TEXT (a {@code FactoryJob} row's message) keeps the code of its message
 * and the arguments of its sentence, so the screen that reads it later can word it in the reader's language. The stored text is
 * the engine's own English sentence followed by one marker, {@code " [mdmf:<bundle key>|<arg>|<arg>]"}; every argument is a
 * type letter, a colon and the URL-encoded value ({@code s} text, {@code n} number, {@code j} a job kind, {@code k} a comma
 * separated list of job kinds). A reader that knows nothing of the marker shows the English text with a short tail; a text without
 * the marker (older rows, uncoded failures) decodes to itself. Pure, EBX-free, tested offline.
 */
public final class EngineMessageCodec
{
	private static final String OPEN = " [mdmf:";
	private static final String CLOSE = "]";

	private EngineMessageCodec()
	{
	}

	/** A stored text taken apart: the English text without its marker, and (when coded) the bundle key and arguments. */
	public static final class Decoded
	{
		public final String raw;
		public final String key;
		public final Object[] args;

		Decoded(final String pRaw, final String pKey, final Object[] pArgs)
		{
			this.raw = pRaw;
			this.key = pKey;
			this.args = pArgs;
		}

		public boolean coded()
		{
			return this.key != null;
		}

		/** The sentence in {@code pLocale}, null when the text carries no code or the bundle has no such key. */
		public String sentence(final Locale pLocale)
		{
			if (this.key == null)
			{
				return null;
			}
			if (this.key.startsWith(com.on.ebx.factory.landing.RunTexts.P))
			{
				// run panel i18n (2026-10-17): the landing run's own sentences keep their nested texts and plain numbers (RunTexts is the one place that words them)
				return com.on.ebx.factory.landing.RunTexts.render(RunPanelText.patterns(pLocale), this.key, this.args);
			}
			if (this.key.startsWith(com.on.ebx.factory.rejects.RejectReason.P))
			{
				// staging load rejects (2026-10-17): a transfer reject's coded reason names its field and column in a phrase of its own (rejects.RejectReason)
				final String[] strings = new String[this.args.length];
				for (int i = 0; i < strings.length; i++)
				{
					strings[i] = String.valueOf(this.args[i]);
				}
				return new com.on.ebx.factory.rejects.RejectReason(null, this.key.substring(com.on.ebx.factory.rejects.RejectReason.P.length()), strings, "").sentence(pLocale);
			}
			final Object[] rendered = new Object[this.args.length];
			for (int i = 0; i < this.args.length; i++)
			{
				final Object arg = this.args[i];
				rendered[i] = arg instanceof JobKindArg ? ((JobKindArg) arg).label(pLocale) : arg;
			}
			final String sentence = WizardMessages.get(pLocale, this.key, rendered);
			return this.key.equals(sentence) ? null : sentence;
		}
	}

	/** An argument that is a job kind or a list of them, labelled in the reader's language ({@code wizard.jobs.kind.<KIND>}). */
	public static final class JobKindArg
	{
		private final String[] kinds;
		private final boolean list;

		JobKindArg(final String[] pKinds, final boolean pList)
		{
			this.kinds = pKinds;
			this.list = pList;
		}

		String label(final Locale pLocale)
		{
			final java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<String>();
			for (final String kind : this.kinds)
			{
				final String key = "wizard.jobs.kind." + kind;
				final String label = WizardMessages.get(pLocale, key);
				labels.add(key.equals(label) ? kind : label);
			}
			return String.join(", ", labels);
		}
	}

	/** The English text followed by the marker; {@code pRaw} unchanged when there is no code. Arguments: {@code String}, {@code Number}, or a {@link JobKindArg}. */
	public static String encode(final String pRaw, final String pKey, final Object[] pArgs, final int pMaxLength)
	{
		final String raw = pRaw == null ? "" : pRaw;
		if (pKey == null || pKey.isEmpty())
		{
			return raw;
		}
		final StringBuilder marker = new StringBuilder(EngineMessageCodec.OPEN).append(pKey);
		if (pArgs != null)
		{
			for (final Object arg : pArgs)
			{
				marker.append('|').append(EngineMessageCodec.token(arg));
			}
		}
		marker.append(EngineMessageCodec.CLOSE);
		// the marker is never cut: the text gives way to it
		final int room = Math.max(0, pMaxLength - marker.length());
		return (raw.length() > room ? raw.substring(0, room) : raw) + marker;
	}

	/**
	 * Key rationale i18n (2026-10-17): a stored text cut to {@code pMax} characters without cutting its marker (the text gives way to it); a text
	 * without a marker is simply cut at the end.
	 */
	public static String fit(final String pStored, final int pMax)
	{
		if (pStored == null)
		{
			return "";
		}
		if (pStored.length() <= pMax)
		{
			return pStored;
		}
		final int open = pStored.lastIndexOf(EngineMessageCodec.OPEN);
		if (open < 0 || !pStored.endsWith(EngineMessageCodec.CLOSE))
		{
			return pStored.substring(0, pMax);
		}
		final String marker = pStored.substring(open);
		return pStored.substring(0, Math.max(0, Math.min(open, pMax - marker.length()))) + marker;
	}

	private static final java.util.regex.Pattern MARKER = java.util.regex.Pattern.compile(" \\[mdmf:[A-Za-z0-9._|:%+*\\-]*\\]");

	/**
	 * Run panel i18n (2026-10-17): the text (or a JSON document holding such texts) without any code marker - what REST and MCP readers have always been given. A marker only
	 * holds URL-encoded values, so the pattern cannot reach into the text around it.
	 */
	public static String stripMarkers(final String pText)
	{
		return pText == null || pText.indexOf(EngineMessageCodec.OPEN) < 0 ? pText : EngineMessageCodec.MARKER.matcher(pText).replaceAll("");
	}

	/** A job kind as an argument. */
	public static JobKindArg jobKind(final String pKind)
	{
		return new JobKindArg(new String[] { pKind == null ? "" : pKind }, false);
	}

	/** A list of job kinds as an argument. */
	public static JobKindArg jobKinds(final List<String> pKinds)
	{
		return new JobKindArg(pKinds.toArray(new String[0]), true);
	}

	private static String token(final Object pArg)
	{
		if (pArg instanceof JobKindArg)
		{
			final JobKindArg kinds = (JobKindArg) pArg;
			return (kinds.list ? "k:" : "j:") + EngineMessageCodec.escape(String.join(",", kinds.kinds));
		}
		if (pArg instanceof Number)
		{
			return "n:" + pArg;
		}
		return "s:" + EngineMessageCodec.escape(pArg == null ? "" : String.valueOf(pArg));
	}

	/** Takes a stored text apart; a text with no (or a damaged) marker decodes to itself, uncoded. */
	public static Decoded decode(final String pStored)
	{
		if (pStored == null)
		{
			return new Decoded("", null, new Object[0]);
		}
		final int open = pStored.lastIndexOf(EngineMessageCodec.OPEN);
		if (open < 0 || !pStored.endsWith(EngineMessageCodec.CLOSE))
		{
			return new Decoded(pStored, null, new Object[0]);
		}
		final String inner = pStored.substring(open + EngineMessageCodec.OPEN.length(), pStored.length() - EngineMessageCodec.CLOSE.length());
		final String[] parts = inner.split("\\|", -1);
		if (parts.length == 0 || parts[0].isEmpty())
		{
			return new Decoded(pStored, null, new Object[0]);
		}
		final List<Object> args = new ArrayList<Object>();
		for (int i = 1; i < parts.length; i++)
		{
			final String part = parts[i];
			if (part.length() < 2 || part.charAt(1) != ':')
			{
				return new Decoded(pStored, null, new Object[0]);
			}
			final String value = EngineMessageCodec.unescape(part.substring(2));
			switch (part.charAt(0))
			{
			case 'n':
				try
				{
					args.add(value.indexOf('.') >= 0 ? (Object) Double.valueOf(value) : (Object) Long.valueOf(value));
				}
				catch (final NumberFormatException ex)
				{
					args.add(value);
				}
				break;
			case 'j':
				args.add(new JobKindArg(new String[] { value }, false));
				break;
			case 'k':
				args.add(new JobKindArg(value.isEmpty() ? new String[0] : value.split(","), true));
				break;
			default:
				args.add(value);
			}
		}
		return new Decoded(pStored.substring(0, open), parts[0], args.toArray());
	}

	private static String escape(final String pValue)
	{
		try
		{
			return URLEncoder.encode(pValue == null ? "" : pValue, "UTF-8");
		}
		catch (final UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	private static String unescape(final String pValue)
	{
		try
		{
			return URLDecoder.decode(pValue, "UTF-8");
		}
		catch (final UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
		catch (final IllegalArgumentException ex)
		{
			return pValue;
		}
	}
}
