package com.on.ebx.factory.core;

import java.util.Locale;

/** Wave 10: every key of the guard and watch messages exists in the English AND the French bundle, formats its arguments, and the French one is not the English one. JUnit-free: run the main. */
public final class ModuleMessagesTest
{
	private static final String[] KEYS = { "writerguard.refused", "writerguard.disabled", "writerguard.problem.missing", "writerguard.problem.type", "writerguard.problem.more",
		"mame.unavailable.summary" };

	public static void main(final String[] pArgs)
	{
		for (final String key : ModuleMessagesTest.KEYS)
		{
			final String english = ModuleMessages.get(Locale.ENGLISH, key, "a", "b", "c", "d", "e");
			final String french = ModuleMessages.get(Locale.FRENCH, key, "a", "b", "c", "d", "e");
			check(key + " exists in English (not the raw key)", !english.equals(key));
			check(key + " exists in French (not the raw key)", !french.equals(key));
			check(key + " is translated", !english.equals(french));
			check(key + " formats its arguments (no {n} left)", !english.matches("(?s).*\\{\\d\\}.*") && !french.matches("(?s).*\\{\\d\\}.*"));
			check(key + " carries no ON EBX product naming slip", !english.contains("TIBCO") && !french.contains("TIBCO"));
		}
		check("an unknown key answers itself", ModuleMessages.get(Locale.ENGLISH, "no.such.key").equals("no.such.key"));
		check("a null locale is English", ModuleMessages.get(null, "writerguard.disabled", "x", "y", "z").contains("turned off"));
		System.out.println("ModuleMessagesTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
