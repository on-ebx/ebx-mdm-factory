package com.on.ebx.factory.designer;

import com.on.ebx.factory.review.ReviewLine;

/** JUnit-free, like the sibling tests of this module: run the main, a failure throws. */
public final class DesignerModelTest
{
	public static void main(final String[] pArgs)
	{
		DesignerModelTest.qualitySeverities();
		DesignerModelTest.scriptConstraintSeverities();
		DesignerModelTest.otherNaturesHaveNoSeverity();
		DesignerModelTest.severityOptionsNeverAliasTheInternalArray();
		DesignerModelTest.qualityIsARule();
		DesignerModelTest.scriptNaturesAreChecks();
		DesignerModelTest.unknownNatureIsACheck();
		System.out.println("DesignerModelTest OK");
	}

	private static void qualitySeverities()
	{
		DesignerModelTest.checkArray("QUALITY offers WARN/REJECT/MODEL_CONSTRAINT", new String[] { "WARN", "REJECT", "MODEL_CONSTRAINT" },
			DesignerModel.severityOptions(ReviewLine.QUALITY));
	}

	private static void scriptConstraintSeverities()
	{
		DesignerModelTest.checkArray("SCRIPT_CONSTRAINT offers blank/WARN/REJECT, blank first", new String[] { "", "WARN", "REJECT" },
			DesignerModel.severityOptions(ReviewLine.SCRIPT_CONSTRAINT));
	}

	/** {@link com.on.ebx.factory.guard.Guard}'s own Javadoc: {@code EXTERNAL_SERVICE}/{@code SCRIPT_DERIVATION} carry no severity concept at all. */
	private static void otherNaturesHaveNoSeverity()
	{
		DesignerModelTest.checkArray("EXTERNAL_SERVICE has no severity choice", new String[0], DesignerModel.severityOptions(ReviewLine.EXTERNAL_SERVICE));
		DesignerModelTest.checkArray("SCRIPT_DERIVATION has no severity choice", new String[0], DesignerModel.severityOptions(ReviewLine.SCRIPT_DERIVATION));
		DesignerModelTest.checkArray("an unknown nature has no severity choice", new String[0], DesignerModel.severityOptions("SOMETHING_ELSE"));
	}

	/** A caller mutating the returned array must never corrupt the next call's answer. */
	private static void severityOptionsNeverAliasTheInternalArray()
	{
		final String[] first = DesignerModel.severityOptions(ReviewLine.QUALITY);
		first[0] = "TAMPERED";
		final String[] second = DesignerModel.severityOptions(ReviewLine.QUALITY);
		DesignerModelTest.check("severityOptions returns a fresh copy each time", "WARN".equals(second[0]));
	}

	private static void qualityIsARule()
	{
		DesignerModelTest.check("QUALITY is a rule, not a check", !DesignerModel.isCheckNature(ReviewLine.QUALITY));
	}

	private static void scriptNaturesAreChecks()
	{
		DesignerModelTest.check("SCRIPT_CONSTRAINT is a check", DesignerModel.isCheckNature(ReviewLine.SCRIPT_CONSTRAINT));
		DesignerModelTest.check("EXTERNAL_SERVICE is a check", DesignerModel.isCheckNature(ReviewLine.EXTERNAL_SERVICE));
		DesignerModelTest.check("SCRIPT_DERIVATION is a check", DesignerModel.isCheckNature(ReviewLine.SCRIPT_DERIVATION));
	}

	private static void unknownNatureIsACheck()
	{
		DesignerModelTest.check("an unrecognised nature fails open to 'check'", DesignerModel.isCheckNature("SOMETHING_ELSE"));
	}

	private static void checkArray(final String pWhat, final String[] pExpected, final String[] pActual)
	{
		final boolean same = pExpected.length == pActual.length && java.util.Arrays.equals(pExpected, pActual);
		if (!same)
		{
			throw new AssertionError(pWhat + " - expected " + java.util.Arrays.toString(pExpected) + " but got " + java.util.Arrays.toString(pActual));
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
