package com.on.ebx.factory.capability;

/** JUnit-free: run the main, a failure throws. */
public final class SyntaxGuideBudgetTest
{
	public static void main(final String[] pArgs)
	{
		SyntaxGuideBudgetTest.underBudgetTextIsReturnedUnchanged();
		SyntaxGuideBudgetTest.nullOrEmptyTextIsHandledWithoutThrowing();
		SyntaxGuideBudgetTest.typeSpecificSectionIsAlwaysKeptEvenIfAloneOverBudget();
		SyntaxGuideBudgetTest.generalSectionsAreDroppedFromTheFirstThatDoesNotFitOnward();
		SyntaxGuideBudgetTest.preambleIsAlwaysKept();
		SyntaxGuideBudgetTest.scriptTypeMatchIsCaseInsensitive();
		SyntaxGuideBudgetTest.headlessDocumentOverBudgetIsKeptWholeAsPreamble();
		SyntaxGuideBudgetTest.charsAvailableIsAlwaysTheOriginalLength();
		SyntaxGuideBudgetTest.neverCutsMidSection();

		System.out.println("SyntaxGuideBudgetTest OK");
	}

	private static String heading(final String pTitle, final int pBodyChars)
	{
		final StringBuilder body = new StringBuilder();
		for (int i = 0; i < pBodyChars; i++)
		{
			body.append('x');
		}
		return "## " + pTitle + "\n" + body + "\n";
	}

	private static void underBudgetTextIsReturnedUnchanged()
	{
		final String text = "a short guide, well under any reasonable budget";
		final SyntaxGuideBudget.Result result = SyntaxGuideBudget.bound(text, "constraintOnNode", 1000);
		SyntaxGuideBudgetTest.check("unchanged text", text.equals(result.text));
		SyntaxGuideBudgetTest.check("not trimmed", !result.trimmed);
		SyntaxGuideBudgetTest.check("charsAvailable == charsSent when nothing was trimmed", result.charsAvailable == result.charsSent);
	}

	private static void nullOrEmptyTextIsHandledWithoutThrowing()
	{
		final SyntaxGuideBudget.Result nullResult = SyntaxGuideBudget.bound(null, "constraintOnNode", 1000);
		SyntaxGuideBudgetTest.check("null text -> empty text, never throws", "".equals(nullResult.text));
		final SyntaxGuideBudget.Result emptyResult = SyntaxGuideBudget.bound("", "constraintOnNode", 1000);
		SyntaxGuideBudgetTest.check("empty text -> empty text", "".equals(emptyResult.text));
	}

	/**
	 * fix/body-compile-loop (2026-09-21), item 2: "if the general part is too long keep the type-specific
	 * part whole and trim the general part by sections" (the coordinator's own instruction) - the
	 * constraintOnNode section here is, on its own, already bigger than the whole budget, and must still
	 * come back entirely intact.
	 */
	private static void typeSpecificSectionIsAlwaysKeptEvenIfAloneOverBudget()
	{
		final String typeSpecific = SyntaxGuideBudgetTest.heading("constraintOnNode reference", 500);
		final String general = SyntaxGuideBudgetTest.heading("general syntax", 200);
		final String text = general + typeSpecific;
		final SyntaxGuideBudget.Result result = SyntaxGuideBudget.bound(text, "constraintOnNode", 100);
		SyntaxGuideBudgetTest.check("the type-specific section survives whole", result.text.contains(typeSpecific));
		SyntaxGuideBudgetTest.check("trimmed is true (the general section did not fit)", result.trimmed);
	}

	/** General sections are kept, in order, for as long as they fit; the first one that does not fit - and everything after it - is dropped whole, never partially. */
	private static void generalSectionsAreDroppedFromTheFirstThatDoesNotFitOnward()
	{
		final String typeSpecific = SyntaxGuideBudgetTest.heading("constraintOnTable reference", 10);
		final String generalA = SyntaxGuideBudgetTest.heading("general A", 50);
		final String generalB = SyntaxGuideBudgetTest.heading("general B", 50);
		final String generalC = SyntaxGuideBudgetTest.heading("general C", 50);
		final String text = typeSpecific + generalA + generalB + generalC;
		// budget fits the type-specific section plus exactly general A, not general B
		final int budget = typeSpecific.length() + generalA.length() + 5;
		final SyntaxGuideBudget.Result result = SyntaxGuideBudget.bound(text, "constraintOnTable", budget);
		SyntaxGuideBudgetTest.check("type-specific kept", result.text.contains("constraintOnTable reference"));
		SyntaxGuideBudgetTest.check("general A kept (fits)", result.text.contains("general A"));
		SyntaxGuideBudgetTest.check("general B dropped (does not fit)", !result.text.contains("general B"));
		SyntaxGuideBudgetTest.check("general C dropped too (comes after the first that did not fit)", !result.text.contains("general C"));
		SyntaxGuideBudgetTest.check("trimmed", result.trimmed);
		SyntaxGuideBudgetTest.check("never exceeds the budget", result.charsSent <= budget);
	}

	/** Text before the first heading (the preamble) is always kept, never counted against the budget or dropped. */
	private static void preambleIsAlwaysKept()
	{
		final String preamble = "Introduction: read this whole guide before writing a constraint.\n\n";
		final String general = SyntaxGuideBudgetTest.heading("a huge general section", 5000);
		final String text = preamble + general;
		final SyntaxGuideBudget.Result result = SyntaxGuideBudget.bound(text, "constraintOnNode", 50);
		SyntaxGuideBudgetTest.check("preamble survives even though the budget is tiny", result.text.startsWith(preamble));
	}

	private static void scriptTypeMatchIsCaseInsensitive()
	{
		final String typeSpecific = SyntaxGuideBudgetTest.heading("CONSTRAINTONNODE (upper-case heading)", 300);
		final String general = SyntaxGuideBudgetTest.heading("general", 300);
		final SyntaxGuideBudget.Result result = SyntaxGuideBudget.bound(general + typeSpecific, "constraintOnNode", 50);
		SyntaxGuideBudgetTest.check("matched case-insensitively, kept whole", result.text.contains("CONSTRAINTONNODE"));
	}

	/** A document with no markdown heading at all is treated as one single (preamble) section - always kept whole, however large, never cut mid-sentence. */
	private static void headlessDocumentOverBudgetIsKeptWholeAsPreamble()
	{
		final StringBuilder text = new StringBuilder("no heading anywhere in this document, just plain prose. ");
		for (int i = 0; i < 500; i++)
		{
			text.append("more words. ");
		}
		final SyntaxGuideBudget.Result result = SyntaxGuideBudget.bound(text.toString(), "constraintOnNode", 100);
		SyntaxGuideBudgetTest.check("kept whole, not trimmed at all (nothing to drop but the whole document)", text.toString().equals(result.text));
		SyntaxGuideBudgetTest.check("trimmed stays false: nothing was actually dropped", !result.trimmed);
	}

	private static void charsAvailableIsAlwaysTheOriginalLength()
	{
		final String typeSpecific = SyntaxGuideBudgetTest.heading("constraintOnNode", 50);
		final String general = SyntaxGuideBudgetTest.heading("general", 500);
		final String text = typeSpecific + general;
		final SyntaxGuideBudget.Result result = SyntaxGuideBudget.bound(text, "constraintOnNode", 60);
		SyntaxGuideBudgetTest.check("charsAvailable is the untrimmed length", result.charsAvailable == text.length());
		SyntaxGuideBudgetTest.check("charsSent is the trimmed length, strictly less here", result.charsSent < result.charsAvailable);
	}

	/**
	 * Every heading kept in the result is a COMPLETE section, starting at "## " - never a fragment. No
	 * scriptType matches either heading here on purpose, so both A and B are plain general sections and
	 * neither one's size is "free" - isolates the general-section boundary behaviour from the separate
	 * always-kept-whole rule {@link #typeSpecificSectionIsAlwaysKeptEvenIfAloneOverBudget} already covers.
	 */
	private static void neverCutsMidSection()
	{
		final String a = SyntaxGuideBudgetTest.heading("A", 40);
		final String b = SyntaxGuideBudgetTest.heading("B", 40);
		final SyntaxGuideBudget.Result result = SyntaxGuideBudget.bound(a + b, "constraintOnTable", a.length() + 5);
		// section A fits whole (its own heading AND its trailing newline), section B must be entirely absent
		SyntaxGuideBudgetTest.check("section A kept complete, including its own body", result.text.contains(a));
		SyntaxGuideBudgetTest.check("section B entirely absent, not a fragment of it", !result.text.contains("## B"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
