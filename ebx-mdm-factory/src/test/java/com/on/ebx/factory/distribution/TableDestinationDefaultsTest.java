package com.on.ebx.factory.distribution;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class TableDestinationDefaultsTest
{
	public static void main(final String[] pArgs)
	{
		TableDestinationDefaultsTest.connectorTopicPropertyIsUsedAsPrefix();
		TableDestinationDefaultsTest.fallsBackToProjectCodeWhenNoConnectorTopic();
		TableDestinationDefaultsTest.tableNameIsLowerCased();
		TableDestinationDefaultsTest.onlyTheLastPathSegmentIsUsed();
		TableDestinationDefaultsTest.blankConnectorTopicFallsBackToo();
		System.out.println("TableDestinationDefaultsTest OK");
	}

	/** Personnes live incident (task brief item C): a Kafka connector's own "topic" property ("mdm.personnes") becomes the shared prefix for every table's proposed destination. */
	private static void connectorTopicPropertyIsUsedAsPrefix()
	{
		TableDestinationDefaultsTest.check("connector topic property used as prefix",
			"mdm.personnes.personne".equals(TableDestinationDefaults.proposeDestination("mdm.personnes", "Personnes", "/root/Personne")));
		TableDestinationDefaultsTest.check("same prefix, different table", "mdm.personnes.adresse".equals(TableDestinationDefaults.proposeDestination("mdm.personnes", "Personnes", "/root/Adresse")));
	}

	private static void fallsBackToProjectCodeWhenNoConnectorTopic()
	{
		TableDestinationDefaultsTest.check("falls back to the lower-cased project code",
			"personnes.personne".equals(TableDestinationDefaults.proposeDestination(null, "Personnes", "/root/Personne")));
	}

	private static void tableNameIsLowerCased()
	{
		TableDestinationDefaultsTest.check("table name always lower-cased", "acme.company".equals(TableDestinationDefaults.proposeDestination("acme", "Acme", "/root/Company")));
	}

	private static void onlyTheLastPathSegmentIsUsed()
	{
		TableDestinationDefaultsTest.check("only the last path segment names the table",
			"mdm.demo.child".equals(TableDestinationDefaults.proposeDestination("mdm.demo", "Demo", "/root/Parent/Child")));
	}

	private static void blankConnectorTopicFallsBackToo()
	{
		TableDestinationDefaultsTest.check("a blank (not just null) connector topic property also falls back",
			"demo.company".equals(TableDestinationDefaults.proposeDestination("   ", "Demo", "/root/Company")));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
