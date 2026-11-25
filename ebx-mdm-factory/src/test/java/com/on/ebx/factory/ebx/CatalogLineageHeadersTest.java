package com.on.ebx.factory.ebx;

import java.util.Map;

/** JUnit-free: run the main, a failure throws. Every declaration request carries the catalog's CSRF marker, cookie path and Authorization path alike (audit D2-15). */
public final class CatalogLineageHeadersTest
{
	public static void main(final String[] pArgs)
	{
		final Map<String, String> viaCookie = CatalogLineageDeclarer.requestHeaders("EBXUID=x", null);
		CatalogLineageHeadersTest.check("cookie path carries X-Requested-With", "ebx-catalog-client".equals(viaCookie.get("X-Requested-With")));
		CatalogLineageHeadersTest.check("cookie path sends the cookie, no Authorization", "EBXUID=x".equals(viaCookie.get("Cookie")) && !viaCookie.containsKey("Authorization"));
		final Map<String, String> viaAuth = CatalogLineageDeclarer.requestHeaders(null, "Basic abc");
		CatalogLineageHeadersTest.check("Authorization path carries X-Requested-With", "ebx-catalog-client".equals(viaAuth.get("X-Requested-With")));
		CatalogLineageHeadersTest.check("Authorization path sends it, no Cookie", "Basic abc".equals(viaAuth.get("Authorization")) && !viaAuth.containsKey("Cookie"));
		CatalogLineageHeadersTest.check("constant is the agreed value", "ebx-catalog-client".equals(CatalogLineageDeclarer.CATALOG_CLIENT_HEADER_VALUE));
		System.out.println("CatalogLineageHeadersTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
