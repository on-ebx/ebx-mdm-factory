package com.on.ebx.factory.wizard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JUnit-free: run the main, a failure throws. The key chooser's sample values (real source values) sit in a {@code <script
 * type="application/json">} block of {@link WizardPage}; the block must go through the shared {@code FactoryHtml.jsonForScript}
 * (every {@code <}, {@code >}, {@code &} and U+2028/U+2029 escaped), not a local {@code </} replacement. A source check: the render is
 * private and needs a whole step-3 model. The source folder comes from {@code -Dmdmf.sourceDir} ({@code tools/test_local.sh} sets it).
 */
public final class WizardPageSourceTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final String root = System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java");
		final String source = new String(Files.readAllBytes(Paths.get(root, "com/on/ebx/factory/wizard/WizardPage.java")), StandardCharsets.UTF_8);
		final int block = source.indexOf("class=\\\"mdmf-key-sample-json\\\"");
		if (block < 0)
		{
			throw new AssertionError("the sample block is no longer where this test looks");
		}
		final String statement = source.substring(block, source.indexOf(';', block));
		WizardPageSourceTest.check("the sample JSON goes through FactoryHtml.jsonForScript", statement.contains("FactoryHtml.jsonForScript(LandingKeyChooserJson.sample("));
		WizardPageSourceTest.check("no local </ replacement is left", !statement.contains(".replace(\"</\""));
		System.out.println("WizardPageSourceTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
