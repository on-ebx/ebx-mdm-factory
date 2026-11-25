package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The doc comment of ONE exported function of a published EBX Script library, split into what the Mapping
 * matrix shows about a computed field (fix/matrix-computed-field, docs/MAPPING-MATRIX.md sec 14): the
 * description (first sentence), one text per {@code @param}, and the {@code @return} text. Pure - no EBX type, no
 * MCP: {@link #parse} works on the library source text the scripting server's {@code get_script(includeSource)}
 * answers, exactly the text {@link ScriptInventory} already reads for the capability pass.
 * <p>
 * Why not reuse {@link ScriptInventory#parseExportedFunctions}'s {@code documentation}: that string is the header
 * comment JOINED on one line and cut at {@link ScriptInventory#MAX_DOC_LENGTH} characters (it is prompt material
 * for the language model, bounded on purpose), so a long {@code @param} list loses its {@code @return}. This
 * class keeps the comment's line structure, so a {@code @param} continued on the next lines and a {@code
 * @return} at the very end are both recovered whole, and it looks the function up by its exact name ({@code
 * export function <name>(}, never a prefix match: {@code isValid} is not {@code isValidForCountry}).
 * <p>
 * Everything shown comes from the library's own text: nothing here knows what a phone number is. A sentence is cut
 * at the first {@code .}, {@code !} or {@code ?} followed by a space or the end of the text; an abbreviation with a
 * dot ("e.g. x") inside the first sentence would end it early - accepted, the full text stays one click away in the
 * library itself.
 */
public final class FunctionDoc
{
	/** The bound applied to every text kept from the library (an untrusted source text, shown in a screen). */
	static final int MAX_TEXT_LENGTH = 400;

	/** First sentence of the function's description, {@code ""} when the comment has none (it may start directly with a tag). */
	public final String description;
	/** {@code @param} name -&gt; its text (continuation lines joined), in declaration order. */
	public final Map<String, String> params;
	/** The {@code @return}/{@code @returns} text, {@code ""} when the comment has none. */
	public final String returns;

	FunctionDoc(final String pDescription, final Map<String, String> pParams, final String pReturns)
	{
		this.description = pDescription == null ? "" : pDescription;
		this.params = pParams == null ? new LinkedHashMap<String, String>() : pParams;
		this.returns = pReturns == null ? "" : pReturns;
	}

	/** The text of one {@code @param}, {@code ""} when the comment does not document that argument. */
	public String paramText(final String pName)
	{
		final String text = this.params.get(pName);
		return text == null ? "" : text;
	}

	/** True when there is something to show: a description, a parameter text or a return text. */
	public boolean hasAnyText()
	{
		return !this.description.isEmpty() || !this.params.isEmpty() || !this.returns.isEmpty();
	}

	/**
	 * The doc comment right above {@code export function <pFunctionName>(} in {@code pSource}, or {@code null} when
	 * the source is blank, the function is not exported there, or no comment sits directly above it (a blank line
	 * between the comment and the function, like {@link ScriptInventory#headerComment}, means it is not its comment).
	 */
	public static FunctionDoc parse(final String pSource, final String pFunctionName)
	{
		if (pSource == null || pSource.trim().isEmpty() || pFunctionName == null || pFunctionName.isEmpty())
		{
			return null;
		}
		final Pattern declaration = Pattern.compile("export\\s+function\\s+" + Pattern.quote(pFunctionName) + "\\s*\\(");
		final String[] lines = pSource.split("\r?\n", -1);
		for (int index = 0; index < lines.length; index++)
		{
			final Matcher matcher = declaration.matcher(lines[index]);
			if (matcher.find())
			{
				final List<String> comment = FunctionDoc.commentAbove(lines, index);
				return comment == null ? null : FunctionDoc.fromCommentLines(comment);
			}
		}
		return null;
	}

	/** The comment lines (markers stripped, structure kept) directly above line {@code pFunctionLine}, or null. */
	private static List<String> commentAbove(final String[] pLines, final int pFunctionLine)
	{
		// the comment must sit directly above the declaration: a blank line in between means it belongs to something else
		final int index = pFunctionLine - 1;
		if (index < 0)
		{
			return null;
		}
		final String immediate = pLines[index].trim();
		if (immediate.endsWith("*/"))
		{
			final List<String> out = new ArrayList<String>();
			String closing = immediate.substring(0, immediate.length() - 2).trim();
			if (closing.startsWith("*"))
			{
				closing = closing.substring(1).trim();
			}
			if (!closing.isEmpty() && !closing.startsWith("/*"))
			{
				out.add(0, closing);
			}
			else if (closing.startsWith("/**") || closing.startsWith("/*"))
			{
				final String only = closing.startsWith("/**") ? closing.substring(3).trim() : closing.substring(2).trim();
				if (!only.isEmpty())
				{
					out.add(0, only);
				}
				return out;
			}
			for (int blockIndex = index - 1; blockIndex >= 0; blockIndex--)
			{
				final String line = pLines[blockIndex].trim();
				if (line.startsWith("/*"))
				{
					final String opening = (line.startsWith("/**") ? line.substring(3) : line.substring(2)).trim();
					if (!opening.isEmpty())
					{
						out.add(0, opening);
					}
					return out;
				}
				String middle = line;
				if (middle.startsWith("*"))
				{
					middle = middle.substring(1).trim();
				}
				out.add(0, middle);
			}
			return null; // no opening marker: not a well-formed block
		}
		if (immediate.startsWith("//"))
		{
			final List<String> out = new ArrayList<String>();
			for (int lineIndex = index; lineIndex >= 0 && pLines[lineIndex].trim().startsWith("//"); lineIndex--)
			{
				out.add(0, pLines[lineIndex].trim().substring(2).trim());
			}
			return out;
		}
		return null;
	}

	/** Splits the comment lines into the description block and the tag blocks ({@code @param x ...}, {@code @return ...}); any other tag is ignored. */
	private static FunctionDoc fromCommentLines(final List<String> pLines)
	{
		final StringBuilder description = new StringBuilder();
		final Map<String, String> params = new LinkedHashMap<String, String>();
		final StringBuilder returns = new StringBuilder();
		String currentTag = null; // "param", "return" or "other"
		String currentParam = null;
		final StringBuilder current = new StringBuilder();
		for (final String raw : pLines)
		{
			final String line = raw.trim();
			if (line.startsWith("@"))
			{
				FunctionDoc.flush(currentTag, currentParam, current, params, returns);
				current.setLength(0);
				final String[] parts = line.split("\\s+", 3);
				final String tag = parts[0];
				if ("@param".equals(tag) && parts.length >= 2)
				{
					currentTag = "param";
					currentParam = parts[1];
					if (parts.length == 3)
					{
						current.append(parts[2]);
					}
				}
				else if ("@return".equals(tag) || "@returns".equals(tag))
				{
					currentTag = "return";
					currentParam = null;
					if (parts.length >= 2)
					{
						current.append(line.substring(tag.length()).trim());
					}
				}
				else
				{
					currentTag = "other";
					currentParam = null;
				}
				continue;
			}
			if (currentTag == null)
			{
				if (!line.isEmpty())
				{
					description.append(description.length() == 0 ? "" : " ").append(line);
				}
			}
			else if (!line.isEmpty())
			{
				current.append(current.length() == 0 ? "" : " ").append(line);
			}
		}
		FunctionDoc.flush(currentTag, currentParam, current, params, returns);
		return new FunctionDoc(FunctionDoc.bound(FunctionDoc.firstSentence(description.toString())), params, FunctionDoc.bound(returns.toString()));
	}

	private static void flush(final String pTag, final String pParam, final StringBuilder pText, final Map<String, String> pParams, final StringBuilder pReturns)
	{
		if ("param".equals(pTag) && pParam != null)
		{
			pParams.put(pParam, FunctionDoc.bound(pText.toString().trim()));
		}
		else if ("return".equals(pTag))
		{
			pReturns.setLength(0);
			pReturns.append(pText.toString().trim());
		}
	}

	private static final Pattern SENTENCE_END = Pattern.compile("[.!?](\\s|$)");

	/** Up to and including the first sentence end; the whole text when it has none. */
	static String firstSentence(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		final Matcher matcher = FunctionDoc.SENTENCE_END.matcher(text);
		return matcher.find() ? text.substring(0, matcher.start() + 1) : text;
	}

	private static String bound(final String pText)
	{
		return pText.length() <= FunctionDoc.MAX_TEXT_LENGTH ? pText : pText.substring(0, FunctionDoc.MAX_TEXT_LENGTH) + "...";
	}
}
