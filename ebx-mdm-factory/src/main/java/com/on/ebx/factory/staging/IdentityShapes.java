package com.on.ebx.factory.staging;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import com.on.ebx.factory.quality.Detectors;

/**
 * Lot F2 (2026-10-19, staging proposal): MEASURED SHAPE FACTS for a text column the data catalogue has not tagged (no applied tag, no suggestion). The catalogue tags a column only
 * from a regex on values it sampled ({@code EMAIL}, {@code PHONE} with two separators), a name hint, a dictionary or a model: a person's name has no regex, a phone written
 * {@code 0612345678} matches none, and a source scanned before the catalogue sampled it has nothing. The staging model then received no identity measure for such a column.
 * <p>
 * This class counts, over the distinct values the profiler already reads for the column's shapes ({@code StagingLlmSuggester#fillShapes}: no second read), how many rows hold a value
 * that is <b>written like</b> an e-mail, a phone number or two capitalised words. It is a <b>measure of how the values look, never a tag</b>: it does not say what the column is, it
 * does not touch the column's tags, and the text built from it says "e-mail-shaped in 98% of sampled values; no catalogue tag". The Factory decides nothing from it; the model and
 * the steward read the share and weigh it.
 * <p>
 * <b>The shapes</b> (universal, no word list, no column name):
 * <ul>
 * <li><i>e-mail-shaped</i>: no white space, exactly one {@code @} with something before it, and a dot after it with something between the {@code @} and the dot and something after the dot;</li>
 * <li><i>phone-shaped</i>: an optional leading {@code +}, then digits and the separators space, {@code .}, {@code -}, parentheses; {@value #PHONE_MIN_DIGITS} to {@value #PHONE_MAX_DIGITS}
 * digits in all; a value of digits only (no {@code +}, no separator) counts when it starts with {@code 0}, as the profiler's own phone measure does (a bare run of digits is far
 * more often an identifier); a value the profiler reads as a date ({@link NormalisationProfiler}) is never phone-shaped;</li>
 * <li><i>two-capitalised-words-shaped</i>: exactly two words, each made of letters, apostrophes and hyphens, each starting with a capital ({@code Camille Martin}, {@code MARTIN Camille},
 * {@code Jean-Pierre O'Brien}).</li>
 * </ul>
 * <b>The thresholds are measurement thresholds, stated in the text</b>: a kind is reported when its shape holds in at least {@value #EMAIL_MIN_PERCENT}% (e-mail),
 * {@value #PHONE_MIN_PERCENT}% (phone) or {@value #NAME_MIN_PERCENT}% (two capitalised words) of the filled rows among the values read - the catalogue's own regex thresholds for the
 * first two. Pure.
 */
public final class IdentityShapes
{
	/** Share of the values read that must be e-mail-shaped for the column to be reported (the catalogue's {@code EMAIL} regex threshold). */
	public static final int EMAIL_MIN_PERCENT = 80;
	/** Same for phone-shaped (the catalogue's {@code PHONE} regex threshold). */
	public static final int PHONE_MIN_PERCENT = 90;
	/** Same for two-capitalised-words-shaped. */
	public static final int NAME_MIN_PERCENT = 90;
	/** Digits of a phone-shaped value, at least. */
	public static final int PHONE_MIN_DIGITS = 8;
	/** Digits of a phone-shaped value, at most. */
	public static final int PHONE_MAX_DIGITS = 15;

	private static final Pattern PHONE_BODY = Pattern.compile("[0-9 .\\-()\\u00a0\\u202f]+");
	private static final Pattern NAME_WORD = Pattern.compile("\\p{Lu}[\\p{L}'\\u2019\\-]+");

	private IdentityShapes()
	{
	}

	/** What was measured on a column: the kind whose shape holds, in which share of the filled rows read. */
	public static final class Detected
	{
		public final IdentityFacts.Kind kind;
		/** Whole percent (rounded down) of the filled rows read whose value has the shape. */
		public final int percent;
		/** Filled rows among the values read (the base of the percentage). */
		public final long rowsRead;

		public Detected(final IdentityFacts.Kind pKind, final int pPercent, final long pRowsRead)
		{
			this.kind = pKind;
			this.percent = pPercent;
			this.rowsRead = pRowsRead;
		}

		/** {@code e-mail-shaped in 98% of sampled values}. */
		public String words()
		{
			return IdentityShapes.shapeWord(this.kind) + " in " + this.percent + "% of sampled values";
		}
	}

	/** The adjective of a kind's shape: {@code e-mail-shaped}, {@code phone-shaped}, {@code two-capitalised-words-shaped}. Null for a kind that has no shape here. */
	public static String shapeWord(final IdentityFacts.Kind pKind)
	{
		switch (pKind)
		{
			case EMAIL:
				return "e-mail-shaped";
			case PHONE:
				return "phone-shaped";
			case NAME:
				return "two-capitalised-words-shaped";
			default:
				return null;
		}
	}

	/** The minimum share, in percent, at which the shape of this kind is reported; 0 for a kind with no shape here. */
	public static int thresholdOf(final IdentityFacts.Kind pKind)
	{
		switch (pKind)
		{
			case EMAIL:
				return IdentityShapes.EMAIL_MIN_PERCENT;
			case PHONE:
				return IdentityShapes.PHONE_MIN_PERCENT;
			case NAME:
				return IdentityShapes.NAME_MIN_PERCENT;
			default:
				return 0;
		}
	}

	/** The thresholds as a sentence for the model: {@code e-mail 80%, phone 90%, two capitalised words 90%}. */
	public static String thresholdsText()
	{
		return "e-mail " + IdentityShapes.EMAIL_MIN_PERCENT + "%, phone " + IdentityShapes.PHONE_MIN_PERCENT + "%, two capitalised words " + IdentityShapes.NAME_MIN_PERCENT + "%";
	}

	// --------------------------------------------------------------------------------------------------------- shapes

	/** Whether the value is written like an e-mail address (see the class comment). */
	public static boolean emailShaped(final String pValue)
	{
		if (pValue == null)
		{
			return false;
		}
		final String value = pValue.trim();
		final int at = value.indexOf('@');
		if (at < 1 || at != value.lastIndexOf('@'))
		{
			return false;
		}
		for (int i = 0; i < value.length(); i++)
		{
			if (Character.isWhitespace(value.charAt(i)))
			{
				return false;
			}
		}
		final int dot = value.lastIndexOf('.');
		return dot > at + 1 && dot < value.length() - 1;
	}

	/** Whether the value is written like a phone number (see the class comment). */
	public static boolean phoneShaped(final String pValue)
	{
		if (pValue == null)
		{
			return false;
		}
		final String value = pValue.trim();
		final boolean plus = value.startsWith("+");
		final String body = plus ? value.substring(1).trim() : value;
		if (body.isEmpty() || !IdentityShapes.PHONE_BODY.matcher(body).matches())
		{
			return false;
		}
		int digits = 0;
		for (int i = 0; i < body.length(); i++)
		{
			if (Character.isDigit(body.charAt(i)))
			{
				digits++;
			}
		}
		if (digits < IdentityShapes.PHONE_MIN_DIGITS || digits > IdentityShapes.PHONE_MAX_DIGITS)
		{
			return false;
		}
		if (!plus && digits == body.length() && body.charAt(0) != '0')
		{
			return false; // a bare run of digits not starting with 0: an identifier far more often than a phone (the profiler's own guard)
		}
		if (NormalisationProfiler.zeroAfterInternationalPrefix(pValue))
		{
			return false; // 2026-10-23: 00 then 0 is no international number (no country code starts with 0): a zero-padded identifier
		}
		return !IdentityShapes.readAsDate(value);
	}

	/** Whether the value is written as two capitalised words (see the class comment). */
	public static boolean nameShaped(final String pValue)
	{
		if (pValue == null)
		{
			return false;
		}
		final String[] words = pValue.trim().split("\\s+");
		if (words.length != 2)
		{
			return false;
		}
		for (final String word : words)
		{
			if (!IdentityShapes.NAME_WORD.matcher(word).matches())
			{
				return false;
			}
		}
		return true;
	}

	/** True when the profiler reads this value as a calendar date (ISO, day/month/year, compact yyyyMMdd...): 1980-05-12 has eight digits and separators, it is not a phone. */
	private static boolean readAsDate(final String pValue)
	{
		final NormalisationProfile probe = NormalisationProfiler.profile(Collections.singletonList(new Detectors.ValueCount(pValue, 1L)), -1L);
		return !probe.dateFamilies.isEmpty();
	}

	// ------------------------------------------------------------------------------------------------------- measure

	/**
	 * Which identity shape the values of a column have. Counts the rows of the filled values ({@code pValues}: distinct values with their row counts, as the profiler reads them); the kind
	 * whose shape holds in the highest share, when that share reaches its threshold ({@link #thresholdOf}); null when none does or nothing is filled.
	 */
	public static Detected detect(final List<Detectors.ValueCount> pValues)
	{
		if (pValues == null)
		{
			return null;
		}
		long total = 0L;
		long email = 0L;
		long phone = 0L;
		long name = 0L;
		for (final Detectors.ValueCount entry : pValues)
		{
			if (entry.value == null || entry.value.trim().isEmpty() || entry.count <= 0L)
			{
				continue;
			}
			total += entry.count;
			if (IdentityShapes.emailShaped(entry.value))
			{
				email += entry.count;
			}
			else if (IdentityShapes.phoneShaped(entry.value))
			{
				phone += entry.count;
			}
			else if (IdentityShapes.nameShaped(entry.value))
			{
				name += entry.count;
			}
		}
		if (total == 0L)
		{
			return null;
		}
		Detected best = null;
		best = IdentityShapes.better(best, IdentityFacts.Kind.EMAIL, email, total);
		best = IdentityShapes.better(best, IdentityFacts.Kind.PHONE, phone, total);
		best = IdentityShapes.better(best, IdentityFacts.Kind.NAME, name, total);
		return best;
	}

	private static Detected better(final Detected pBest, final IdentityFacts.Kind pKind, final long pCount, final long pTotal)
	{
		if (pCount * 100L < pTotal * IdentityShapes.thresholdOf(pKind))
		{
			return pBest;
		}
		final int percent = (int) (pCount * 100L / pTotal);
		return pBest == null || percent > pBest.percent ? new Detected(pKind, percent, pTotal) : pBest;
	}
}
