package com.on.ebx.factory.capability;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws.
 * <p>
 * fix/matrix-computed-field: {@link FunctionDoc#parse} on the real doc comments of {@code Fasttrack.lib.phone}
 * (copied from the library source read through the scripting server, 2026-09-30) and on the edge cases the Mapping
 * matrix meets: first sentence, {@code @param} per name (continued on the next lines), {@code @return}, a function
 * without a comment, and a function whose name is the prefix of another's.
 */
public final class FunctionDocTest
{
	/** Verbatim from the library: the two functions a prefix match would confuse, and the one with a multi-line {@code @param}. */
	private static final String SOURCE = String.join("\n",
		"/**",
		" * Fasttrack.lib.phone - header comment of the whole library, not a function's.",
		" */",
		"",
		"uses core.string as str;",
		"",
		"/**",
		" * The main entry point for a number that carries its own country: E.164 syntax, plus the",
		" * national plan when the calling code is one this library knows. A number from a country with",
		" * no plan here passes on syntax alone.",
		" * @param value the number in international form, may be null",
		" * @return true when the number is plausible",
		" */",
		"export function isValid(value: string): boolean",
		"begin",
		"  return true;",
		"end",
		"",
		"/**",
		" * Is this a valid number in the given country, written either nationally or internationally?",
		" * An international number whose calling code is not the country's own is rejected.",
		" * @param value the number, in either form, may be null",
		" * @param country an ISO 3166-1 alpha-2 code, case-insensitive",
		" * @return true when the number fits that country's numbering plan",
		" */",
		"export function isValidForCountry(value: string, country: string): boolean",
		"begin",
		"  return true;",
		"end",
		"",
		"/**",
		" * The canonical form to store: '+' then the calling code then the national significant number,",
		" * no separators. This is the value a phone column should hold.",
		" * @param value the number, in either form, may be null",
		" * @param country an ISO 3166-1 alpha-2 code; leave null or blank to read the country off an",
		" *        international value, which then only has to satisfy isValid()",
		" * @return the E.164 string, or null when the number is not valid",
		" */",
		"export function toE164(value: string, country: string): string",
		"begin",
		"  return null;",
		"end",
		"",
		"export function undocumented(x: string): string",
		"begin",
		"  return x;",
		"end",
		"",
		"/** Separated from its function by a blank line: not its comment. */",
		"",
		"export function orphan(x: string): string",
		"begin",
		"  return x;",
		"end",
		"",
		"// Line comments work as well.",
		"// Second line of it.",
		"export function lineComment(x: string): string",
		"begin",
		"  return x;",
		"end",
		"",
		"/** @return only a return tag, on one line */",
		"export function tagOnly(x: string): string",
		"begin",
		"  return x;",
		"end");

	public static void main(final String[] pArgs)
	{
		FunctionDocTest.firstSentenceOfTheDescription();
		FunctionDocTest.paramTextsPerNameWithContinuationLines();
		FunctionDocTest.returnTextIsWholeEvenAtTheEndOfALongComment();
		FunctionDocTest.aNamePrefixingAnotherFindsItsOwnComment();
		FunctionDocTest.missingOrDetachedCommentIsNull();
		FunctionDocTest.lineCommentsAndSingleLineBlocks();
		FunctionDocTest.blankAndUnknownInputs();
		FunctionDocTest.firstSentenceCutsOnlyAtASentenceEnd();
		System.out.println("FunctionDocTest OK");
	}

	private static void firstSentenceOfTheDescription()
	{
		final FunctionDoc doc = FunctionDoc.parse(FunctionDocTest.SOURCE, "toE164");
		FunctionDocTest.check("toE164 has a doc", doc != null);
		FunctionDocTest.check("the description is the FIRST sentence only (the dot inside the quoted text is not a sentence end)",
			"The canonical form to store: '+' then the calling code then the national significant number, no separators.".equals(doc.description));
	}

	private static void paramTextsPerNameWithContinuationLines()
	{
		final FunctionDoc doc = FunctionDoc.parse(FunctionDocTest.SOURCE, "toE164");
		FunctionDocTest.check("two params, in order", "[value, country]".equals(doc.params.keySet().toString()));
		FunctionDocTest.check("value text", "the number, in either form, may be null".equals(doc.paramText("value")));
		FunctionDocTest.check("a @param continued on the next line is joined whole",
			"an ISO 3166-1 alpha-2 code; leave null or blank to read the country off an international value, which then only has to satisfy isValid()"
				.equals(doc.paramText("country")));
		FunctionDocTest.check("an undocumented argument reads empty", doc.paramText("nope").isEmpty());
	}

	private static void returnTextIsWholeEvenAtTheEndOfALongComment()
	{
		final FunctionDoc doc = FunctionDoc.parse(FunctionDocTest.SOURCE, "toE164");
		FunctionDocTest.check("the @return text", "the E.164 string, or null when the number is not valid".equals(doc.returns));
	}

	private static void aNamePrefixingAnotherFindsItsOwnComment()
	{
		final FunctionDoc isValid = FunctionDoc.parse(FunctionDocTest.SOURCE, "isValid");
		final FunctionDoc forCountry = FunctionDoc.parse(FunctionDocTest.SOURCE, "isValidForCountry");
		FunctionDocTest.check("isValid gets the comment of isValid, one parameter", isValid != null && isValid.params.size() == 1
			&& isValid.description.startsWith("The main entry point"));
		FunctionDocTest.check("isValidForCountry gets its own, two parameters", forCountry != null && forCountry.params.size() == 2
			&& forCountry.description.startsWith("Is this a valid number in the given country"));
		FunctionDocTest.check("isValid's @return is its own", "true when the number is plausible".equals(isValid.returns));
		FunctionDocTest.check("a prefix of a real name that is no function at all finds nothing", FunctionDoc.parse(FunctionDocTest.SOURCE, "isVal") == null);
	}

	private static void missingOrDetachedCommentIsNull()
	{
		FunctionDocTest.check("a function with no comment above it: null", FunctionDoc.parse(FunctionDocTest.SOURCE, "undocumented") == null);
		FunctionDocTest.check("a comment separated by a blank line is not its comment: null", FunctionDoc.parse(FunctionDocTest.SOURCE, "orphan") == null);
		FunctionDocTest.check("a function the source does not export: null", FunctionDoc.parse(FunctionDocTest.SOURCE, "notThere") == null);
	}

	private static void lineCommentsAndSingleLineBlocks()
	{
		final FunctionDoc line = FunctionDoc.parse(FunctionDocTest.SOURCE, "lineComment");
		FunctionDocTest.check("a run of // lines is one comment, first sentence kept", line != null && "Line comments work as well.".equals(line.description));
		final FunctionDoc tag = FunctionDoc.parse(FunctionDocTest.SOURCE, "tagOnly");
		FunctionDocTest.check("a one-line block holding only @return: no description, the return text", tag != null && tag.description.isEmpty()
			&& "only a return tag, on one line".equals(tag.returns));
	}

	private static void blankAndUnknownInputs()
	{
		FunctionDocTest.check("null source", FunctionDoc.parse(null, "x") == null);
		FunctionDocTest.check("blank source", FunctionDoc.parse("   ", "x") == null);
		FunctionDocTest.check("blank name", FunctionDoc.parse(FunctionDocTest.SOURCE, "") == null);
	}

	private static void firstSentenceCutsOnlyAtASentenceEnd()
	{
		FunctionDocTest.check("no sentence end: the whole text", "no end here".equals(FunctionDoc.firstSentence("no end here")));
		FunctionDocTest.check("a dot not followed by a space is not an end", "value 1.5 is kept.".equals(FunctionDoc.firstSentence("value 1.5 is kept. Next")));
		FunctionDocTest.check("two sentences: the first", "One. ".trim().equals(FunctionDoc.firstSentence("One. Two.")));
		FunctionDocTest.check("a question mark ends one too", "Is it? ".trim().equals(FunctionDoc.firstSentence("Is it? Yes.")));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
