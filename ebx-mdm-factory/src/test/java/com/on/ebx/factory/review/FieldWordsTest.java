package com.on.ebx.factory.review;

import java.util.Arrays;

/** JUnit-free: run the main, a failure throws. tree-plain-words lot (2026-09-23). */
public final class FieldWordsTest
{
	public static void main(final String[] pArgs)
	{
		FieldWordsTest.check("camelCase", "First name".equals(FieldWords.humanize("firstName")));
		FieldWordsTest.check("acronym run then word", "VAT number".equals(FieldWords.humanize("VATNumber")));
		FieldWordsTest.check("trailing id", "Account id".equals(FieldWords.humanize("accountId")));
		FieldWordsTest.check("snake_case", "Vat no".equals(FieldWords.humanize("vat_no")));
		FieldWordsTest.check("single word", "Email".equals(FieldWords.humanize("email")));
		FieldWordsTest.check("already words", "Legal form".equals(FieldWords.humanize("Legal form")));
		FieldWordsTest.check("blank", "?".equals(FieldWords.humanize("  ")) && "?".equals(FieldWords.humanize(null)));
		FieldWordsTest.check("digits keep the word", "Address line2".equals(FieldWords.humanize("addressLine2")));
		FieldWordsTest.check("all: joined", "First name and Last name".equals(FieldWords.humanizeAll(Arrays.asList("firstName", "lastName"), " and ")));
		System.out.println("FieldWordsTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
