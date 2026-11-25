package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JUnit-free: run the main, a failure throws. The two classes that open an HTTP connection with EBX's own credentials
 * (the caller's session token, or the agent account) send them only to this node ({@code CredentialPolicy.requireOwnNode},
 * not the any-HTTPS-host {@code requireSafeDestination}) and never follow a redirect. A source check: both need a running
 * EBX to be exercised. The source folder comes from {@code -Dmdmf.sourceDir} ({@code tools/test_local.sh} sets it).
 */
public final class CredentialDestinationsSourceTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final String root = System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java");
		for (final String name : new String[] { "DistributionScheduler", "CatalogLineageDeclarer" })
		{
			final String source = new String(Files.readAllBytes(Paths.get(root, "com/on/ebx/factory/ebx", name + ".java")), StandardCharsets.UTF_8);
			CredentialDestinationsSourceTest.check(name + " restricts the destination to this node", source.contains("CredentialPolicy.requireOwnNode(")
				&& !source.contains("CredentialPolicy.requireSafeDestination("));
			CredentialDestinationsSourceTest.check(name + " does not follow redirects", source.contains("setInstanceFollowRedirects(false)"));
		}
		System.out.println("CredentialDestinationsSourceTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
