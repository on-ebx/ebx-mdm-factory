package com.on.ebx.factory.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Master integration (2026-10-17, live TiersB2CRetestFinal): every merge policy the Simulate preview writes is NOT the table's manual-merge policy (the add-on
 * allows one per table and then refused the real policy), and a configure that finds only a preview removes it BEFORE generating. Read from the source: both
 * paths need the match-merge server.
 */
public final class PreviewManualMergeTest
{
	private PreviewManualMergeTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String root = System.getProperty("mdmf.sourceDir", "src/main/java") + "/com/on/ebx/factory/ebx/";
		final String preview = new String(Files.readAllBytes(Paths.get(root + "MatchingPreviewPolicy.java")), StandardCharsets.UTF_8);
		final int builds = preview.split("MatchingPolicyTranslator\\.buildMergePolicy\\(pPreviewCode", -1).length - 1;
		final int flags = preview.split("put\\(\"useForManualMerge\", false\\)", -1).length - 1;
		if (builds == 0 || flags != builds)
		{
			throw new AssertionError("every preview merge policy built (" + builds + ") must set useForManualMerge false (" + flags + ")");
		}
		final String configurator = new String(Files.readAllBytes(Paths.get(root + "MatchingConfigurator.java")), StandardCharsets.UTF_8);
		final int fresh = configurator.indexOf("case GENERATE_FRESH:");
		final int removal = configurator.indexOf("MatchingPreviewPolicy.remove(", fresh);
		final int generate = configurator.indexOf("writePoliciesThroughGenerate(", fresh);
		if (fresh < 0 || removal < 0 || generate < 0 || removal > generate)
		{
			throw new AssertionError("GENERATE_FRESH must remove a leftover preview before generating the real policies");
		}
		System.out.println("PreviewManualMergeTest OK");
	}
}
