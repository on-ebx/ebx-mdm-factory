package com.on.ebx.factory.staging;

/** JUnit-free, like the sibling tests: run the main, a failure throws. */
public final class RunGridCellStateTest
{
	public static void main(final String[] pArgs)
	{
		RunGridCellStateTest.applicationCellWaitsWhenTableMissing();
		RunGridCellStateTest.applicationCellNeedsPersonWhenEverythingHeldBack();
		RunGridCellStateTest.applicationCellDoneWhenSomeRowsLanded();
		RunGridCellStateTest.applicationCellDoneWhenNothingHeldBackEitherWay();
		RunGridCellStateTest.stepCellFollowsTheStepFlag();
		System.out.println("RunGridCellStateTest OK");
	}

	private static void applicationCellWaitsWhenTableMissing()
	{
		RunGridCellStateTest.check("no table -> WAITING regardless of counts", RunGridCellState.WAITING.equals(RunGridCellState.forApplicationCell(false, 0, 0)));
		RunGridCellStateTest.check("no table -> WAITING even with rows", RunGridCellState.WAITING.equals(RunGridCellState.forApplicationCell(false, 50, 3)));
	}

	private static void applicationCellNeedsPersonWhenEverythingHeldBack()
	{
		RunGridCellStateTest.check("0 rows, some held back -> NEEDS_PERSON",
			RunGridCellState.NEEDS_PERSON.equals(RunGridCellState.forApplicationCell(true, 0, 3)));
		RunGridCellStateTest.check("unknown row count (-1), some held back -> NEEDS_PERSON",
			RunGridCellState.NEEDS_PERSON.equals(RunGridCellState.forApplicationCell(true, -1, 1)));
	}

	private static void applicationCellDoneWhenSomeRowsLanded()
	{
		RunGridCellStateTest.check("rows landed, none held back -> DONE", RunGridCellState.DONE.equals(RunGridCellState.forApplicationCell(true, 165, 0)));
		RunGridCellStateTest.check("rows landed, some ALSO held back -> DONE (partial success is still progress)",
			RunGridCellState.DONE.equals(RunGridCellState.forApplicationCell(true, 165, 3)));
	}

	private static void applicationCellDoneWhenNothingHeldBackEitherWay()
	{
		RunGridCellStateTest.check("0 rows, nothing held back -> DONE (a genuinely empty application, not a problem)",
			RunGridCellState.DONE.equals(RunGridCellState.forApplicationCell(true, 0, 0)));
	}

	private static void stepCellFollowsTheStepFlag()
	{
		RunGridCellStateTest.check("step done -> DONE", RunGridCellState.DONE.equals(RunGridCellState.forStepCell(true)));
		RunGridCellStateTest.check("step not done -> WAITING", RunGridCellState.WAITING.equals(RunGridCellState.forStepCell(false)));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
