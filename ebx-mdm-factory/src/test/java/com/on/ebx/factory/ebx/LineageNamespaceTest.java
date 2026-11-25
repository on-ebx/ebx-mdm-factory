package com.on.ebx.factory.ebx;

import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder;

/**
 * JUnit-free: run the main, a failure throws. {@link LineageNamespace} (local-endpoint lot, 2026-09-23): the
 * namespace no longer follows the address the factory calls itself on.
 */
public final class LineageNamespaceTest
{
	public static void main(final String[] pArgs)
	{
		LineageNamespaceTest.check("the setting wins", "ebx://mdm.acme.com".equals(LineageNamespace.resolve(" ebx://mdm.acme.com ", "ebx://prop", "https://other.acme.com")));
		LineageNamespaceTest.check("then the system property", "ebx://prop".equals(LineageNamespace.resolve("", "ebx://prop", "https://other.acme.com")));
		LineageNamespaceTest.check("then a public address left in the old field, as before",
			"ebx://mdm.acme.com:8443".equals(LineageNamespace.resolve(null, null, "https://mdm.acme.com:8443/")));
		LineageNamespaceTest.check("a loopback address never names the instance",
			CatalogLineageBuilder.DEFAULT_EBX_NAMESPACE.equals(LineageNamespace.resolve(null, null, "http://127.0.0.1:8081")));
		LineageNamespaceTest.check("localhost neither", CatalogLineageBuilder.DEFAULT_EBX_NAMESPACE.equals(LineageNamespace.resolve(null, null, "http://localhost:8080")));
		LineageNamespaceTest.check("nothing set: the ebx-connectors default", "ebx://localhost:8080".equals(LineageNamespace.resolve(null, null, null)));
		LineageNamespaceTest.check("a malformed address is ignored", CatalogLineageBuilder.DEFAULT_EBX_NAMESPACE.equals(LineageNamespace.resolve(null, null, "not a url")));
		System.out.println("LineageNamespaceTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
