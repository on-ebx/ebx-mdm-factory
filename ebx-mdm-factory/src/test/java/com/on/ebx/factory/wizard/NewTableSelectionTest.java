package com.on.ebx.factory.wizard;

/** JUnit-free: run the main, a failure throws. Exercises {@link NewTableSelection} (UX lot C1, 2026-10-13): the one rule for the ticks of a new application's tables. */
public final class NewTableSelectionTest
{
	public static void main(final String[] pArgs)
	{
		// the types of the measured instance: Salesforce (rest-like live system), a JDBC database, a source with no connection, Kafka
		for (final String type : new String[] { "salesforce", "jdbc", "rest", "kafka", "h2", "", null })
		{
			NewTableSelectionTest.check("a live system's tables start unticked: " + type, !NewTableSelection.selectNew(type));
		}
		NewTableSelectionTest.check("a sample file IS the tables the person chose: they stay ticked", NewTableSelection.selectNew("sample-file"));
		System.out.println("NewTableSelectionTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
