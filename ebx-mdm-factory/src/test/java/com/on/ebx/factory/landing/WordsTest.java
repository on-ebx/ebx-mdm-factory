package com.on.ebx.factory.landing;

import java.util.Arrays;
import java.util.List;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class WordsTest
{
	public static void main(final String[] pArgs)
	{
		WordsTest.check("underscores separate", Words.of("BILL_TO_ACCOUNT_ID"), "bill", "to", "account", "id");
		WordsTest.check("camelCase separates", Words.of("parentAccountId"), "parent", "account", "id");
		WordsTest.check("a plain snake_case key", Words.of("ACCOUNT_ID"), "account", "id");
		WordsTest.check("no separator at all: one word", Words.of("SUBACCOUNT_ID"), "subaccount", "id");
		WordsTest.check("no separator at all: one word (2)", Words.of("DISCOUNT_ID"), "discount", "id");
		WordsTest.check("an acronym run stays one word, the capitalised word after it starts a new one", Words.of("VATNumber"), "vat", "number");
		WordsTest.check("a digit stays attached to the word it follows", Words.of("LINE1"), "line1");
		WordsTest.check("a lone word", Words.of("Id"), "id");
		WordsTest.check("empty", Words.of(""), new String[0]);
		WordsTest.check("null is empty, not an error", Words.of(null), new String[0]);

		System.out.println("WordsTest OK");
	}

	private static void check(final String pWhat, final List<String> pActual, final String... pExpected)
	{
		if (!pActual.equals(Arrays.asList(pExpected)))
		{
			throw new AssertionError(pWhat + ": expected " + Arrays.asList(pExpected) + ", got " + pActual);
		}
	}
}
