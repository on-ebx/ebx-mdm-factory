package com.on.ebx.factory.ebx;

import java.util.HashSet;
import java.util.Set;

import com.on.ebx.factory.wizard.WizardRules;

/**
 * JUnit-free, like the rest of this package's tests. Exercises {@link SourcesRun#uniqueCode} (package-visible
 * for exactly this reason) - the follow-up-review gap this lot's own documentation flagged as untested, closed
 * here. Pure string logic, no EBX call needed despite living in the {@code ebx.*} package.
 */
public final class SourcesRunUniqueCodeTest
{
	public static void main(final String[] pArgs)
	{
		SourcesRunUniqueCodeTest.freeCodeKeptAsIs();
		SourcesRunUniqueCodeTest.takenCodeGetsSuffix2();
		SourcesRunUniqueCodeTest.severalTakenCodesSkipToFirstFreeSuffix();
		SourcesRunUniqueCodeTest.blankBaseFallsBackToSrc();
		SourcesRunUniqueCodeTest.overlongBaseIsTruncatedToCodeMax();
		SourcesRunUniqueCodeTest.overlongBaseWithSuffixStaysWithinCodeMax();
		SourcesRunUniqueCodeTest.neverReturnsAnExistingCode();

		System.out.println("SourcesRunUniqueCodeTest OK");
	}

	private static void freeCodeKeptAsIs()
	{
		final String result = SourcesRun.uniqueCode("ERP", SourcesRunUniqueCodeTest.codes());
		SourcesRunUniqueCodeTest.check("free code kept as is", "ERP".equals(result));
	}

	private static void takenCodeGetsSuffix2()
	{
		final String result = SourcesRun.uniqueCode("ERP", SourcesRunUniqueCodeTest.codes("ERP"));
		SourcesRunUniqueCodeTest.check("first collision gets _2", "ERP_2".equals(result));
	}

	private static void severalTakenCodesSkipToFirstFreeSuffix()
	{
		final String result = SourcesRun.uniqueCode("ERP", SourcesRunUniqueCodeTest.codes("ERP", "ERP_2", "ERP_3"));
		SourcesRunUniqueCodeTest.check("skips to the first free suffix", "ERP_4".equals(result));
	}

	private static void blankBaseFallsBackToSrc()
	{
		SourcesRunUniqueCodeTest.check("null base falls back to SRC", "SRC".equals(SourcesRun.uniqueCode(null, SourcesRunUniqueCodeTest.codes())));
		SourcesRunUniqueCodeTest.check("blank base falls back to SRC", "SRC".equals(SourcesRun.uniqueCode("   ", SourcesRunUniqueCodeTest.codes())));
		SourcesRunUniqueCodeTest.check("SRC itself still gets suffixed if taken", "SRC_2".equals(SourcesRun.uniqueCode("", SourcesRunUniqueCodeTest.codes("SRC"))));
	}

	private static void overlongBaseIsTruncatedToCodeMax()
	{
		final String longBase = SourcesRunUniqueCodeTest.repeat("A", WizardRules.CODE_MAX + 10);
		final String result = SourcesRun.uniqueCode(longBase, SourcesRunUniqueCodeTest.codes());
		SourcesRunUniqueCodeTest.check("truncated to CODE_MAX", result.length() == WizardRules.CODE_MAX);
		SourcesRunUniqueCodeTest.check("truncated is a valid code", result.matches(WizardRules.CODE_PATTERN));
	}

	private static void overlongBaseWithSuffixStaysWithinCodeMax()
	{
		final String longBase = SourcesRunUniqueCodeTest.repeat("B", WizardRules.CODE_MAX);
		final Set<String> existing = SourcesRunUniqueCodeTest.codes(longBase);
		final String result = SourcesRun.uniqueCode(longBase, existing);
		SourcesRunUniqueCodeTest.check("suffixed result never exceeds CODE_MAX", result.length() <= WizardRules.CODE_MAX);
		SourcesRunUniqueCodeTest.check("suffixed result ends with the suffix", result.endsWith("_2"));
		SourcesRunUniqueCodeTest.check("suffixed result is not the taken one", !existing.contains(result));
	}

	private static void neverReturnsAnExistingCode()
	{
		final Set<String> existing = new HashSet<String>();
		existing.add("CRM");
		for (int i = 2; i <= 50; i++)
		{
			existing.add("CRM_" + i);
		}
		final String result = SourcesRun.uniqueCode("CRM", existing);
		SourcesRunUniqueCodeTest.check("skips every already-taken suffix", !existing.contains(result));
		SourcesRunUniqueCodeTest.check("picks the first free one (CRM_51)", "CRM_51".equals(result));
	}

	// ---------------------------------------------------------------------------------------------------- fixtures

	private static Set<String> codes(final String... pCodes)
	{
		final Set<String> out = new HashSet<String>();
		for (final String code : pCodes)
		{
			out.add(code);
		}
		return out;
	}

	private static String repeat(final String pChar, final int pCount)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pCount; i++)
		{
			out.append(pChar);
		}
		return out.toString();
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
