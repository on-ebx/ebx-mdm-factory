package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * fix/body-compile-loop (2026-09-21), item 2: bounds the scripting server's own {@code get_syntax_guide}
 * answer (plain markdown text, {@code sections: "all"}) to a character budget before it goes into a system
 * prompt - WITHOUT ever cutting a section or a sentence in half. Pure: no EBX/MCP type, no network - the
 * caller ({@code ebx.CapabilityApplier}) fetches the raw text and hands it here.
 * <p>
 * <b>The bound</b> ({@link #DEFAULT_CHAR_BUDGET}, 20 000 characters, roughly 5 000 tokens): a judgment call,
 * not a measured limit - no live instance was available to this lot to see the real guide's actual size.
 * Chosen to be generous enough that a real constraintOnNode/constraintOnTable section (the part that matters
 * most) is very unlikely to need trimming on its own, while still keeping the system prompt from growing
 * unboundedly if "sections: all" turns out to be large. Documented here specifically so a future session with
 * a live instance can revisit it once the real guide's size is known.
 * <p>
 * <b>Algorithm</b>: the text is split into sections at every markdown heading line ({@code ^#{1,6}\s+}, any
 * level) - text before the first heading (the "preamble") is its own section and is ALWAYS kept, on the
 * assumption that a guide's opening framing is short and important. Any OTHER section whose heading line
 * contains the given script type as a case-insensitive substring is a "type-specific" section and is ALSO
 * ALWAYS kept, WHOLE, regardless of the budget (per the coordinator's own instruction: "if the general part
 * is too long keep the type-specific part whole and trim the general part by sections, never mid-sentence") -
 * even if the type-specific section alone already exceeds the budget, it is never itself trimmed. Every
 * remaining ("general") section is then added, WHOLE, in its original order, for as long as the running
 * total still fits the budget; the first section that would not fit, and everything after it, is dropped
 * entirely - never a partial section, never a cut sentence.
 */
public final class SyntaxGuideBudget
{
	public static final int DEFAULT_CHAR_BUDGET = 20000;

	private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$", Pattern.MULTILINE);

	private SyntaxGuideBudget()
	{
	}

	/** The result of {@link #bound}. */
	public static final class Result
	{
		public final String text;
		/** The length of the untrimmed input text (before any bounding). */
		public final int charsAvailable;
		/** The length of {@link #text} - what was actually sent. */
		public final int charsSent;
		/** True when at least one general section was dropped to fit the budget. */
		public final boolean trimmed;

		/** Public - {@code ebx.CapabilityApplier} also builds one directly for its own "nothing usable was fetched" cases (an unreachable scripting server, an empty answer), never only through {@link #bound}. */
		public Result(final String pText, final int pCharsAvailable, final int pCharsSent, final boolean pTrimmed)
		{
			this.text = pText;
			this.charsAvailable = pCharsAvailable;
			this.charsSent = pCharsSent;
			this.trimmed = pTrimmed;
		}
	}

	public static Result bound(final String pGuideText, final String pScriptType)
	{
		return SyntaxGuideBudget.bound(pGuideText, pScriptType, SyntaxGuideBudget.DEFAULT_CHAR_BUDGET);
	}

	public static Result bound(final String pGuideText, final String pScriptType, final int pCharBudget)
	{
		if (pGuideText == null || pGuideText.isEmpty())
		{
			return new Result(pGuideText == null ? "" : pGuideText, pGuideText == null ? 0 : pGuideText.length(), pGuideText == null ? 0 : pGuideText.length(), false);
		}
		final int available = pGuideText.length();
		if (available <= pCharBudget)
		{
			return new Result(pGuideText, available, available, false);
		}
		final List<String> sections = SyntaxGuideBudget.splitIntoSections(pGuideText);
		final String typeNeedle = pScriptType == null ? "" : pScriptType.toLowerCase(java.util.Locale.ROOT);
		final StringBuilder kept = new StringBuilder();
		final List<String> generalSections = new ArrayList<String>();
		// preamble (sections.get(0) when the text does not start with a heading) and every type-specific
		// section are ALWAYS kept, whole, never counted against the budget - see the class Javadoc
		for (int i = 0; i < sections.size(); i++)
		{
			final String section = sections.get(i);
			final boolean isPreamble = i == 0 && !SyntaxGuideBudget.HEADING.matcher(section).lookingAt();
			final boolean isTypeSpecific = !typeNeedle.isEmpty() && SyntaxGuideBudget.headingLine(section).toLowerCase(java.util.Locale.ROOT).contains(typeNeedle);
			if (isPreamble || isTypeSpecific)
			{
				kept.append(section);
			}
			else
			{
				generalSections.add(section);
			}
		}
		boolean trimmed = false;
		for (final String section : generalSections)
		{
			if (kept.length() + section.length() <= pCharBudget)
			{
				kept.append(section);
			}
			else
			{
				// stop at the first general section that does not fit - a later, shorter section might still
				// fit on its own, but keeping it would put it out of order relative to the guide's own
				// structure; dropping everything from here on keeps the sent document internally coherent
				trimmed = true;
				break;
			}
		}
		final String text = kept.toString();
		return new Result(text, available, text.length(), trimmed);
	}

	/**
	 * The whole document split at every heading line - a leading section that does NOT itself start with a
	 * heading (found via {@link #headingLine}/the caller's own {@code lookingAt()} check, never relied on as
	 * an index convention here) is the preamble; every other section starts with exactly one heading line
	 * and runs to the next heading or the end of the text.
	 */
	private static List<String> splitIntoSections(final String pText)
	{
		final List<Integer> headingStarts = new ArrayList<Integer>();
		final Matcher matcher = SyntaxGuideBudget.HEADING.matcher(pText);
		while (matcher.find())
		{
			headingStarts.add(Integer.valueOf(matcher.start()));
		}
		final List<String> sections = new ArrayList<String>();
		if (headingStarts.isEmpty())
		{
			sections.add(pText);
			return sections;
		}
		if (headingStarts.get(0).intValue() > 0)
		{
			sections.add(pText.substring(0, headingStarts.get(0).intValue()));
		}
		for (int i = 0; i < headingStarts.size(); i++)
		{
			final int start = headingStarts.get(i).intValue();
			final int end = i + 1 < headingStarts.size() ? headingStarts.get(i + 1).intValue() : pText.length();
			sections.add(pText.substring(start, end));
		}
		return sections;
	}

	/** The first line of a section - the heading itself for every section but the (headingless) preamble. */
	private static String headingLine(final String pSection)
	{
		final int newline = pSection.indexOf('\n');
		return newline < 0 ? pSection : pSection.substring(0, newline);
	}
}
