package com.on.ebx.factory.distribution;

import java.util.Arrays;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class PrimaryKeyFormatTest
{
	public static void main(final String[] pArgs)
	{
		PrimaryKeyFormatTest.singleFieldNeedsNoEscaping();
		PrimaryKeyFormatTest.escapesAPipeInsideAValue();
		PrimaryKeyFormatTest.escapesABackslashInsideAValue();
		PrimaryKeyFormatTest.escapesBackslashBeforePipeInOrder();
		PrimaryKeyFormatTest.joinsTwoPlainFields();
		PrimaryKeyFormatTest.nullBecomesEmpty();
		System.out.println("PrimaryKeyFormatTest OK");
	}

	private static void singleFieldNeedsNoEscaping()
	{
		PrimaryKeyFormatTest.check("a plain value formats unchanged", "42".equals(PrimaryKeyFormat.format(Arrays.asList("42"))));
	}

	private static void escapesAPipeInsideAValue()
	{
		PrimaryKeyFormatTest.check("a pipe inside a value is escaped", "A\\|B".equals(PrimaryKeyFormat.escape("A|B")));
		PrimaryKeyFormatTest.check("format of a single field with a pipe", "A\\|B".equals(PrimaryKeyFormat.format(Arrays.asList("A|B"))));
	}

	private static void escapesABackslashInsideAValue()
	{
		PrimaryKeyFormatTest.check("a backslash inside a value is doubled", "A\\\\B".equals(PrimaryKeyFormat.escape("A\\B")));
	}

	private static void escapesBackslashBeforePipeInOrder()
	{
		// "A\|B" (a literal backslash directly followed by a literal pipe) must escape to "A\\\|B":
		// the backslash doubles first, THEN the pipe is escaped - escaping the pipe first would wrongly
		// treat the already-inserted escape backslash as part of the original value.
		PrimaryKeyFormatTest.check("backslash-then-pipe escapes in the right order", "A\\\\\\|B".equals(PrimaryKeyFormat.escape("A\\|B")));
	}

	private static void joinsTwoPlainFields()
	{
		PrimaryKeyFormatTest.check("two fields join with an unescaped pipe", "ERP|00042".equals(PrimaryKeyFormat.format(Arrays.asList("ERP", "00042"))));
	}

	private static void nullBecomesEmpty()
	{
		PrimaryKeyFormatTest.check("null escapes to empty", "".equals(PrimaryKeyFormat.escape(null)));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
