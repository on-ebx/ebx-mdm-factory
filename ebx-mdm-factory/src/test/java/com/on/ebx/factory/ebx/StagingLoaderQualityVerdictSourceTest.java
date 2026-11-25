package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JUnit-free: run the main, a failure throws. Pins what a quality rule does with a row it could not examine (pattern invalid, too long,
 * or timed out): {@code StagingLoader} skips it BEFORE the severity test, so a rule in severity REJECT does not reject that row, it is
 * neither counted as checked nor failed, and {@code QualityRuleProblems} writes one WARN per rule. A source check: the loader needs EBX.
 * The source folder comes from {@code -Dmdmf.sourceDir} ({@code tools/test_local.sh} sets it).
 */
public final class StagingLoaderQualityVerdictSourceTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final String root = System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java");
		final String source = new String(Files.readAllBytes(Paths.get(root, "com/on/ebx/factory/ebx/StagingLoader.java")), StandardCharsets.UTF_8);
		final int verdict = source.indexOf("QualityRuleCheck.verdict(rule, xs, pLookups)");
		if (verdict < 0)
		{
			throw new AssertionError("the quality-rule call site is no longer where this test looks");
		}
		final int skip = source.indexOf("Verdict.NOT_VALIDATED", verdict);
		final int report = source.indexOf("QualityRuleProblems.reportOnce(rule)", verdict);
		final int cont = source.indexOf("continue;", report);
		final int record = source.indexOf("recordQualityCheck(pRuleCounters, rule, ok", verdict);
		final int reject = source.indexOf("rule.isRejectSeverity()", verdict);
		StagingLoaderQualityVerdictSourceTest.check("the not-validated branch exists", skip > verdict && report > skip && cont > report);
		StagingLoaderQualityVerdictSourceTest.check("it leaves the loop before the row is counted or rejected", cont < record && cont < reject);
		System.out.println("StagingLoaderQualityVerdictSourceTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
