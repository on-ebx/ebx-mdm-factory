package com.on.ebx.factory.rejects;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.wizard.EngineMessageCodec;

/**
 * Staging load and rejects lot (2026-10-17): the reasons of one reject as a list, stored in one text and read back in the viewer's language. A stored text is the reasons' English
 * sentences, each followed by its marker ({@link RejectReason#stored}), joined with {@code "; "}; {@link #read} takes it apart again, whatever its age: a text with markers gives
 * its coded reasons, a text from before this lot is recognised when it is an EBX conversion failure and is otherwise shown as it is (cleaned of the double escaping). Pure.
 */
public final class RejectReasons
{
	private static final Pattern MARKER = Pattern.compile(" \\[mdmf:[A-Za-z0-9._|:%+*\\-]*\\]");

	private RejectReasons()
	{
	}

	/** One reason ready to show: its sentence and what to do about it in the viewer's language, the wording for a group, the grouping signature and the raw text. */
	public static final class Shown
	{
		public final String sentence;
		public final String fix;
		public final String group;
		/** "" for a reason that carries no code (then rows are not grouped by it). */
		public final String signature;
		public final String category;
		/** The raw text for a folded detail, "" when there is none. */
		public final String raw;

		Shown(final String pSentence, final String pFix, final String pGroup, final String pSignature, final String pCategory, final String pRaw)
		{
			this.sentence = pSentence;
			this.fix = pFix;
			this.group = pGroup;
			this.signature = pSignature;
			this.category = pCategory;
			this.raw = pRaw;
		}
	}

	/** The reasons joined into the stored text; as many as fit in {@code pMax} characters (the first always does), none cut in the middle of its marker. */
	public static String store(final List<RejectReason> pReasons, final int pMax)
	{
		final StringBuilder sb = new StringBuilder();
		for (final RejectReason reason : pReasons)
		{
			final String one = reason.stored(pMax);
			if (sb.length() > 0)
			{
				if (sb.length() + 2 + one.length() > pMax)
				{
					break;
				}
				sb.append("; ");
			}
			sb.append(one);
		}
		return sb.toString();
	}

	/** The English text of a stored one, without its markers - what REST, MCP and the CSV read. */
	public static String english(final String pStored)
	{
		return pStored == null ? "" : EngineMessageCodec.stripMarkers(pStored);
	}

	/** A coded reason read back from its marker, null when the key is not one of this class. */
	static RejectReason decode(final String pOneStored)
	{
		final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(pOneStored);
		if (!decoded.coded() || !decoded.key.startsWith(RejectReason.P))
		{
			return null;
		}
		final String[] args = new String[decoded.args.length];
		for (int i = 0; i < args.length; i++)
		{
			args[i] = String.valueOf(decoded.args[i]);
		}
		return new RejectReason(null, decoded.key.substring(RejectReason.P.length()), args, "");
	}

	/** The reasons of a stored text, worded in {@code pLocale}; {@code pDetail} is the reject's own detail field (EBX's raw text, may be empty). */
	public static List<Shown> read(final String pStored, final String pCategory, final String pDetail, final Locale pLocale)
	{
		final List<Shown> out = new ArrayList<Shown>();
		final String stored = pStored == null ? "" : pStored;
		final Matcher matcher = RejectReasons.MARKER.matcher(stored);
		int from = 0;
		boolean first = true;
		while (matcher.find())
		{
			String piece = stored.substring(from, matcher.end());
			from = matcher.end();
			if (piece.startsWith("; "))
			{
				piece = piece.substring(2);
			}
			final RejectReason reason = RejectReasons.decode(piece);
			if (reason == null)
			{
				final EngineMessageCodec.Decoded other = EngineMessageCodec.decode(piece);
				final String sentence = other.coded() ? other.sentence(pLocale) : null;
				out.add(new Shown(sentence == null ? RejectText.oneLine(other.raw) : sentence, "", "", "", RejectCategory.of(pCategory), ""));
			}
			else
			{
				out.add(new Shown(reason.sentence(pLocale), reason.fix(pLocale), reason.group(pLocale), reason.signature(), RejectCategory.of(pCategory),
					first ? RejectText.detail(pDetail) : ""));
			}
			first = false;
		}
		if (out.isEmpty())
		{
			final RejectReason legacy = RejectClassifier.legacy(stored);
			if (legacy != null)
			{
				out.add(new Shown(legacy.sentence(pLocale), legacy.fix(pLocale), legacy.group(pLocale), legacy.signature(), RejectCategory.EBX_REFUSAL,
					RejectText.detail(pDetail).isEmpty() ? legacy.detail : RejectText.detail(pDetail)));
			}
			else if (!stored.trim().isEmpty())
			{
				out.add(new Shown(RejectText.oneLine(stored), "", "", "", RejectCategory.of(pCategory), RejectText.detail(pDetail)));
			}
		}
		return out;
	}
}
