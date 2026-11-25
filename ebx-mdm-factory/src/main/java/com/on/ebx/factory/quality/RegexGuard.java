package com.on.ebx.factory.quality;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The one place a regular expression written by a language model (or typed by a person) is compiled and evaluated. {@code
 * java.util.regex} backtracks: a pattern such as {@code (a+)+$} can take exponential time on a value that nearly matches, and a
 * pattern is applied to every row of a load, so one such pattern may block a thread for good (no timeout exists in the JDK, and the
 * agent's time budget cannot interrupt it). Three defences, none of which needs a regex library:
 * <ul>
 * <li>a length cap ({@link #MAX_PATTERN_LENGTH}): a pattern is a format description, never a program. Applies everywhere;</li>
 * <li>a deadline on every evaluation ({@link #matches}, {@link #matcher}): the text is wrapped in a {@link CharSequence} whose {@code
 * charAt} checks the clock every {@link #CHECK_EVERY} reads, so a runaway match is abandoned with a {@link MatchTimeout}. Costs one
 * {@code System.nanoTime()} and one small object per evaluation, nothing per character in the normal case. Applies everywhere, and is
 * the only protection for a rule a person already accepted;</li>
 * <li>a shape refusal ({@link #compile}, {@link #refusal}), for a pattern that is only PROPOSED (by the model; a pipeline being
 * measured). It is a HEURISTIC on the text of the pattern, not a proof: it refuses a group that is repeated without a fixed count
 * ({@code *}, {@code +}, {@code {n,}}, {@code {n,m}} with {@code m > n}) when the group's body is an alternation, or can END on a
 * repetition. Refused: {@code (a+)+}, {@code (.*)*}, {@code (\w+\s?)+} (the optional {@code \s?} may be skipped, so the body can end on
 * {@code \w+}), {@code (a|aa)+}, {@code (a+b+)*}. Accepted: a repeated group whose body ends with a mandatory element that is not
 * repeated - {@code ([a-z]+\.)+}, {@code ([\w-]+\.)+}, {@code (\d{2}-)+}, a full e-mail pattern {@code ^[\w.+-]+@([\w-]+\.)+[\w-]{2,}$} -
 * because the mandatory delimiter ties each iteration to one place in the text. It does not see every catastrophic pattern (the
 * deadline is there for those) and it refuses a few harmless ones, such as {@code (?:FR|DE)+}.
 * {@link #compileStored} never applies it: a stored rule keeps being enforced exactly as before unless it really times out.</li>
 * </ul>
 * Pure: no EBX class.
 */
public final class RegexGuard
{
	/** Longest pattern accepted. A real format (an IBAN, a VAT number, a phone number) is far shorter. */
	public static final int MAX_PATTERN_LENGTH = 300;

	/**
	 * Longest pattern accepted for a rule a person already accepted. Much wider than {@link #MAX_PATTERN_LENGTH}: a long list of
	 * alternatives written by hand is legitimate, and a rule that is enforced today must not stop being enforced because of its length.
	 */
	public static final int MAX_STORED_PATTERN_LENGTH = 4000;

	/** Time one evaluation may take: a legitimate pattern on a business value answers in microseconds. */
	public static final long DEFAULT_BUDGET_MILLIS = 50L;

	/** The clock is read once per this many characters examined by the matcher (a power of two minus one is used as a mask). */
	static final int CHECK_EVERY = 1024;

	private RegexGuard()
	{
	}

	/** A pattern the guard refuses, as a {@link PatternSyntaxException} so that every caller that already catches an invalid pattern catches this too; its message is the reason alone. */
	public static final class Refused extends PatternSyntaxException
	{
		private static final long serialVersionUID = 1L;
		private final String reason;

		Refused(final String pReason, final String pPattern)
		{
			super(pReason, pPattern, -1);
			this.reason = pReason;
		}

		@Override
		public String getMessage()
		{
			return this.reason;
		}
	}

	/** An evaluation abandoned after its deadline. */
	public static final class MatchTimeout extends RuntimeException
	{
		private static final long serialVersionUID = 1L;

		MatchTimeout(final long pBudgetMillis)
		{
			super("the regular expression did not finish within " + pBudgetMillis + " ms");
		}
	}

	/**
	 * Compiles a PROPOSED pattern: the length cap and the shape refusal apply. Throws a {@link PatternSyntaxException} - a {@link Refused}
	 * when the guard refuses it, the JDK's own when the syntax is wrong.
	 */
	public static Pattern compile(final String pPattern) throws PatternSyntaxException
	{
		final String reason = RegexGuard.refusal(pPattern);
		if (reason != null)
		{
			throw new Refused(reason, pPattern);
		}
		return Pattern.compile(pPattern);
	}

	/**
	 * Compiles a pattern a person already accepted (a stored quality rule): only the wide cap {@link #MAX_STORED_PATTERN_LENGTH} applies,
	 * never the shape refusal, so a rule that is enforced today keeps being enforced; the deadline of {@link #matches} is its protection.
	 */
	public static Pattern compileStored(final String pPattern) throws PatternSyntaxException
	{
		final String reason = RegexGuard.lengthRefusal(pPattern, RegexGuard.MAX_STORED_PATTERN_LENGTH);
		if (reason != null)
		{
			throw new Refused(reason, pPattern);
		}
		return Pattern.compile(pPattern);
	}

	/** The reason a PROPOSED pattern is refused (length, shape), or {@code null} when the guard accepts it (it may still be an invalid pattern). */
	public static String refusal(final String pPattern)
	{
		final String tooLong = RegexGuard.lengthRefusal(pPattern, RegexGuard.MAX_PATTERN_LENGTH);
		if (tooLong != null)
		{
			return tooLong;
		}
		if (pPattern != null && RegexGuard.hasRepeatedGroupThatCanEndOnARepetition(pPattern))
		{
			return "the pattern repeats a group whose body can end on a repetition or is an alternation, such as (a+)+, (\\w+\\s?)+ or (a|b)*: it can take exponential time. "
				+ "Make the body end with a mandatory delimiter, ([a-z]+\\.)+ , or use a character class and one repetition, [a-z.]+";
		}
		return null;
	}

	private static String lengthRefusal(final String pPattern, final int pMaxLength)
	{
		if (pPattern != null && pPattern.length() > pMaxLength)
		{
			return "the pattern is " + pPattern.length() + " characters long, more than the " + pMaxLength + " allowed: write a shorter one";
		}
		return null;
	}

	private static final int MANDATORY = 0;
	private static final int OPTIONAL = 1;
	private static final int REPEATED = 2;

	/**
	 * Whether some group of the pattern is repeated without a fixed count while its body is an alternation or can end on a repetition (see
	 * the class comment). Every group is examined on its own, whatever its depth.
	 */
	static boolean hasRepeatedGroupThatCanEndOnARepetition(final String pPattern)
	{
		final int length = pPattern.length();
		for (int i = 0; i < length; i++)
		{
			final char c = pPattern.charAt(i);
			if (c == '\\')
			{
				i++;
			}
			else if (c == '[')
			{
				i = RegexGuard.endOfClass(pPattern, i);
			}
			else if (c == '(')
			{
				final int close = RegexGuard.endOfGroup(pPattern, i);
				if (close < 0)
				{
					return false; // unbalanced: the JDK reports it
				}
				if (RegexGuard.quantifierAt(pPattern, close + 1) == RegexGuard.REPEATED && RegexGuard.bodyIsRisky(pPattern, RegexGuard.bodyStart(pPattern, i), close))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Index of the {@code )} closing the group opened at {@code pOpen}, or {@code -1}. */
	private static int endOfGroup(final String pPattern, final int pOpen)
	{
		int depth = 0;
		for (int i = pOpen; i < pPattern.length(); i++)
		{
			final char c = pPattern.charAt(i);
			if (c == '\\')
			{
				i++;
			}
			else if (c == '[')
			{
				i = RegexGuard.endOfClass(pPattern, i);
			}
			else if (c == '(')
			{
				depth++;
			}
			else if (c == ')')
			{
				depth--;
				if (depth == 0)
				{
					return i;
				}
			}
		}
		return -1;
	}

	/** Index of the first character of the body of the group opened at {@code pOpen}: after {@code (?:}, {@code (?>}, {@code (?<name>}, a flag group's colon, a lookaround's marker. */
	private static int bodyStart(final String pPattern, final int pOpen)
	{
		if (pOpen + 1 >= pPattern.length() || pPattern.charAt(pOpen + 1) != '?')
		{
			return pOpen + 1;
		}
		final int n = pPattern.length();
		final char kind = pOpen + 2 < n ? pPattern.charAt(pOpen + 2) : ')';
		if (kind == ':' || kind == '>' || kind == '=' || kind == '!')
		{
			return pOpen + 3;
		}
		if (kind == '<')
		{
			final char next = pOpen + 3 < n ? pPattern.charAt(pOpen + 3) : ')';
			if (next == '=' || next == '!')
			{
				return pOpen + 4;
			}
			final int gt = pPattern.indexOf('>', pOpen);
			return gt < 0 ? n : gt + 1;
		}
		int i = pOpen + 2; // flags: (?i) has no body, (?i:...) has
		while (i < n && pPattern.charAt(i) != ':' && pPattern.charAt(i) != ')')
		{
			i++;
		}
		return i < n && pPattern.charAt(i) == ':' ? i + 1 : i;
	}

	/** Whether the group is a lookaround ({@code (?=}, {@code (?!}, {@code (?<=}, {@code (?<!}): it consumes nothing. */
	private static boolean isLookaround(final String pPattern, final int pOpen)
	{
		return pPattern.startsWith("(?=", pOpen) || pPattern.startsWith("(?!", pOpen) || pPattern.startsWith("(?<=", pOpen) || pPattern.startsWith("(?<!", pOpen);
	}

	/** A body is risky when it has a top-level {@code |} or can end on a repetition. */
	private static boolean bodyIsRisky(final String pPattern, final int pStart, final int pEnd)
	{
		if (pStart >= pEnd)
		{
			return false;
		}
		// elements of the body, as [start, end, quantifier kind, is group (open index) or -1]
		final java.util.List<int[]> elements = new java.util.ArrayList<int[]>();
		int i = pStart;
		while (i < pEnd)
		{
			final char c = pPattern.charAt(i);
			if (c == '|')
			{
				return true;
			}
			final int start = i;
			int groupOpen = -1;
			if (c == '\\')
			{
				i += 2;
				if (i - 1 < pEnd && (pPattern.charAt(i - 1) == 'p' || pPattern.charAt(i - 1) == 'P') && i < pEnd && pPattern.charAt(i) == '{')
				{
					final int close = pPattern.indexOf('}', i);
					i = close < 0 ? pEnd : close + 1;
				}
			}
			else if (c == '[')
			{
				i = RegexGuard.endOfClass(pPattern, i) + 1;
			}
			else if (c == '(')
			{
				final int close = RegexGuard.endOfGroup(pPattern, i);
				if (close < 0 || close >= pEnd)
				{
					return false;
				}
				groupOpen = i;
				i = close + 1;
			}
			else
			{
				i++;
			}
			i = Math.min(i, pEnd);
			final boolean zeroWidth = c == '^' || c == '$' || (c == '\\' && start + 1 < pEnd && "bBAzZG".indexOf(pPattern.charAt(start + 1)) >= 0)
				|| (groupOpen >= 0 && RegexGuard.isLookaround(pPattern, groupOpen));
			int kind = RegexGuard.quantifierAt(pPattern, i);
			final int quantEnd = RegexGuard.quantifierEnd(pPattern, i);
			i = quantEnd;
			if (zeroWidth)
			{
				kind = RegexGuard.OPTIONAL; // consumes nothing: never the element that ties the end of the body
			}
			elements.add(new int[] { start, i, kind, groupOpen });
		}
		for (int e = elements.size() - 1; e >= 0; e--)
		{
			final int[] element = elements.get(e);
			if (element[2] == RegexGuard.REPEATED)
			{
				return true;
			}
			if (element[2] == RegexGuard.OPTIONAL)
			{
				continue; // may be skipped: the body can end on the element before it
			}
			if (element[3] >= 0)
			{
				final int close = RegexGuard.endOfGroup(pPattern, element[3]);
				return RegexGuard.bodyIsRisky(pPattern, RegexGuard.bodyStart(pPattern, element[3]), close);
			}
			return false; // a mandatory, non-repeated element ends the body
		}
		return false;
	}

	/** {@link #REPEATED} for {@code *}, {@code +}, {@code {n,}}, {@code {n,m}} with {@code m > n} and {@code m > 1}; {@link #OPTIONAL} for {@code ?}, {@code {0}}, {@code {0,1}}; otherwise {@link #MANDATORY}. */
	private static int quantifierAt(final String pPattern, final int pIndex)
	{
		if (pIndex >= pPattern.length())
		{
			return RegexGuard.MANDATORY;
		}
		final char c = pPattern.charAt(pIndex);
		if (c == '*' || c == '+')
		{
			return RegexGuard.REPEATED;
		}
		if (c == '?')
		{
			return RegexGuard.OPTIONAL;
		}
		if (c == '{')
		{
			final int end = RegexGuard.endOfCount(pPattern, pIndex);
			if (end < 0)
			{
				return RegexGuard.MANDATORY;
			}
			final String inside = pPattern.substring(pIndex + 1, end);
			final int comma = inside.indexOf(',');
			if (comma < 0)
			{
				return RegexGuard.isZero(inside) ? RegexGuard.OPTIONAL : RegexGuard.MANDATORY;
			}
			final String high = inside.substring(comma + 1);
			if (high.isEmpty() || RegexGuard.isVariableCount(inside))
			{
				return !high.isEmpty() && RegexGuard.isZeroOrOne(high) ? RegexGuard.OPTIONAL : RegexGuard.REPEATED;
			}
			return RegexGuard.isZero(inside.substring(0, comma)) ? RegexGuard.OPTIONAL : RegexGuard.MANDATORY;
		}
		return RegexGuard.MANDATORY;
	}

	/** Index just after the quantifier at {@code pIndex} (and its lazy or possessive {@code ?}/{@code +} suffix), or {@code pIndex} when there is none. */
	private static int quantifierEnd(final String pPattern, final int pIndex)
	{
		if (pIndex >= pPattern.length())
		{
			return pIndex;
		}
		final char c = pPattern.charAt(pIndex);
		int end = pIndex;
		if (c == '*' || c == '+' || c == '?')
		{
			end = pIndex + 1;
		}
		else if (c == '{')
		{
			final int close = RegexGuard.endOfCount(pPattern, pIndex);
			if (close < 0)
			{
				return pIndex;
			}
			end = close + 1;
		}
		else
		{
			return pIndex;
		}
		if (end < pPattern.length() && (pPattern.charAt(end) == '?' || pPattern.charAt(end) == '+'))
		{
			end++;
		}
		return end;
	}

	private static boolean isZero(final String pDigits)
	{
		return !pDigits.isEmpty() && pDigits.replace("0", "").isEmpty();
	}

	private static boolean isZeroOrOne(final String pDigits)
	{
		try
		{
			return Long.parseLong(pDigits) <= 1L;
		}
		catch (final NumberFormatException ex)
		{
			return false;
		}
	}

	/** Index of the {@code ]} that closes the class opened at {@code pStart} (a leading {@code ^} or {@code ]} and escapes handled), or the last index when it never closes. */
	private static int endOfClass(final String pPattern, final int pStart)
	{
		int i = pStart + 1;
		if (i < pPattern.length() && pPattern.charAt(i) == '^')
		{
			i++;
		}
		if (i < pPattern.length() && pPattern.charAt(i) == ']')
		{
			i++;
		}
		int nested = 0;
		for (; i < pPattern.length(); i++)
		{
			final char c = pPattern.charAt(i);
			if (c == '\\')
			{
				i++;
			}
			else if (c == '[')
			{
				nested++;
			}
			else if (c == ']')
			{
				if (nested == 0)
				{
					return i;
				}
				nested--;
			}
		}
		return pPattern.length() - 1;
	}

	/** Index of the {@code }} of a counted repetition opening at {@code pStart}, or {@code -1} when the brace is a literal (not {@code {digits[,[digits]]}}). */
	private static int endOfCount(final String pPattern, final int pStart)
	{
		int i = pStart + 1;
		final int digitsStart = i;
		while (i < pPattern.length() && Character.isDigit(pPattern.charAt(i)))
		{
			i++;
		}
		if (i == digitsStart)
		{
			return -1;
		}
		if (i < pPattern.length() && pPattern.charAt(i) == ',')
		{
			i++;
			while (i < pPattern.length() && Character.isDigit(pPattern.charAt(i)))
			{
				i++;
			}
		}
		return i < pPattern.length() && pPattern.charAt(i) == '}' ? i : -1;
	}

	/** {@code n,} or {@code n,m} with {@code m > n}: a count that is not fixed. */
	private static boolean isVariableCount(final String pInside)
	{
		final int comma = pInside.indexOf(',');
		if (comma < 0)
		{
			return false;
		}
		final String low = pInside.substring(0, comma);
		final String high = pInside.substring(comma + 1);
		if (high.isEmpty())
		{
			return true;
		}
		try
		{
			return Long.parseLong(high) > Long.parseLong(low);
		}
		catch (final NumberFormatException ex)
		{
			return true; // a count too big for a long is a repetition
		}
	}

	/** {@code matcher.matches()} on {@code pValue} within {@code pBudgetMillis}, or a {@link MatchTimeout}. */
	public static boolean matches(final Pattern pPattern, final String pValue, final long pBudgetMillis)
	{
		return pPattern.matcher(new Deadline(pValue, pBudgetMillis)).matches();
	}

	/** {@link #matches(Pattern, String, long)} with {@link #DEFAULT_BUDGET_MILLIS}. */
	public static boolean matches(final Pattern pPattern, final String pValue)
	{
		return RegexGuard.matches(pPattern, pValue, RegexGuard.DEFAULT_BUDGET_MILLIS);
	}

	/**
	 * A matcher over {@code pValue} whose {@code find()}, {@code matches()} and group reads abandon with a {@link MatchTimeout} once {@code
	 * pBudgetMillis} have passed since this call. Groups are read from the underlying text, so {@code matcher.group(i)} costs no clock read.
	 */
	public static Matcher matcher(final Pattern pPattern, final String pValue, final long pBudgetMillis)
	{
		return pPattern.matcher(new Deadline(pValue, pBudgetMillis));
	}

	/** {@link #matcher(Pattern, String, long)} with {@link #DEFAULT_BUDGET_MILLIS}. */
	public static Matcher matcher(final Pattern pPattern, final String pValue)
	{
		return RegexGuard.matcher(pPattern, pValue, RegexGuard.DEFAULT_BUDGET_MILLIS);
	}

	/** The value, with a clock check every {@link #CHECK_EVERY} reads. */
	static final class Deadline implements CharSequence
	{
		private final CharSequence text;
		private final long budgetMillis;
		private final long deadlineNanos;
		private int reads;

		Deadline(final CharSequence pText, final long pBudgetMillis)
		{
			this.text = pText;
			this.budgetMillis = pBudgetMillis;
			this.deadlineNanos = System.nanoTime() + pBudgetMillis * 1000000L;
		}

		@Override
		public char charAt(final int pIndex)
		{
			if ((++this.reads & (RegexGuard.CHECK_EVERY - 1)) == 0 && System.nanoTime() - this.deadlineNanos > 0)
			{
				throw new MatchTimeout(this.budgetMillis);
			}
			return this.text.charAt(pIndex);
		}

		@Override
		public int length()
		{
			return this.text.length();
		}

		@Override
		public CharSequence subSequence(final int pStart, final int pEnd)
		{
			return this.text.subSequence(pStart, pEnd);
		}

		@Override
		public String toString()
		{
			return this.text.toString();
		}
	}
}
