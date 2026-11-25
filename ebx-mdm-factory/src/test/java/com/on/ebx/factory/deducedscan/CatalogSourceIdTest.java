package com.on.ebx.factory.deducedscan;

import java.util.HashSet;
import java.util.Set;

/** JUnit-free: run the main, a failure throws. */
public final class CatalogSourceIdTest
{
	public static void main(final String[] pArgs)
	{
		// identical to CatalogDetection#sourceIdFor (ebx-connectors) - the live examples measured there
		CatalogSourceIdTest.check("uppercases and underscores", "SAP_ODATA_PROD".equals(CatalogSourceId.forConnector("sap-odata prod")));
		CatalogSourceIdTest.check("SourceBidon -> SOURCEBIDON (contract fixture)", "SOURCEBIDON".equals(CatalogSourceId.forConnector("SourceBidon")));
		CatalogSourceIdTest.check("OpenFDA -> OPENFDA (this bug's own live repro)", "OPENFDA".equals(CatalogSourceId.forConnector("OpenFDA")));
		CatalogSourceIdTest.check("strips leading/trailing separators", "A_B".equals(CatalogSourceId.forConnector("--a.b--")));
		CatalogSourceIdTest.check("blank falls back to SOURCE", "SOURCE".equals(CatalogSourceId.forConnector("")));
		CatalogSourceIdTest.check("null falls back to SOURCE", "SOURCE".equals(CatalogSourceId.forConnector(null)));
		final String longId = CatalogSourceId.forConnector("ThisConnectorIdIsDefinitelyLongerThanFortyCharactersTotal");
		CatalogSourceIdTest.check("truncated to 40 characters", longId.length() == 40);

		// unique(): no collision -> the base id itself
		final Set<String> none = new HashSet<String>();
		CatalogSourceIdTest.check("no collision keeps the base id", "OPENFDA".equals(CatalogSourceId.unique("OpenFDA", none)));

		// unique(): one collision -> _2
		final Set<String> one = new HashSet<String>();
		one.add("OPENFDA");
		CatalogSourceIdTest.check("first collision appends _2", "OPENFDA_2".equals(CatalogSourceId.unique("OpenFDA", one)));

		// unique(): _2 also taken -> _3
		final Set<String> two = new HashSet<String>();
		two.add("OPENFDA");
		two.add("OPENFDA_2");
		CatalogSourceIdTest.check("second collision appends _3", "OPENFDA_3".equals(CatalogSourceId.unique("OpenFDA", two)));

		// unique(): a long base still fits the suffix within 40 characters and stays unique
		final Set<String> longCollision = new HashSet<String>();
		longCollision.add(longId);
		final String uniqueLong = CatalogSourceId.unique("ThisConnectorIdIsDefinitelyLongerThanFortyCharactersTotal", longCollision);
		CatalogSourceIdTest.check("disambiguated long id still <= 40 chars", uniqueLong.length() <= 40);
		CatalogSourceIdTest.check("disambiguated long id differs from the collision", !uniqueLong.equals(longId));

		// unique() tolerates a null existing-id set (never seen live, but must not throw)
		CatalogSourceIdTest.check("unique() tolerates a null set", "OPENFDA".equals(CatalogSourceId.unique("OpenFDA", null)));

		System.out.println("CatalogSourceIdTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
