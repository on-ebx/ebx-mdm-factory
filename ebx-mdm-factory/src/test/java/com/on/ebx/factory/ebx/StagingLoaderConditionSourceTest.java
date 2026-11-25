package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * JUnit-free. Validation desk lot L4: pins how {@code StagingLoader} honours the condition of a quality rule - a rule with a condition is skipped (neither counted as checked nor
 * failed, so the last-load counters count only the rows it covers) BEFORE its verdict, reading the condition attribute's PRODUCED staging value; only the transfer's own reader
 * returns conditioned rules, the readers of validity (derivations, match plans, the study's measures) leave them out. A source check: the loader needs EBX.
 */
public final class StagingLoaderConditionSourceTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final String root = System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java");
		final String source = new String(Files.readAllBytes(Paths.get(root, "com/on/ebx/factory/ebx/StagingLoader.java")), StandardCharsets.UTF_8);
		final int verdict = source.indexOf("QualityRuleCheck.verdict(rule, xs, pLookups)");
		final int condition = source.indexOf("rule.hasCondition() && !rule.appliesTo(values.get(\"./\" + rule.conditionField))");
		check("the row loop tests the condition on the produced value", condition > 0 && verdict > 0);
		check("before the verdict", condition < verdict);
		final int skip = source.indexOf("continue;", condition);
		check("by leaving the iteration, before the row is counted", skip > condition && skip < verdict);
		check("the transfer reads the conditioned rules", source.contains("readQualityRules(pFactory, attributeId, true)"));
		check("the other readers do not", source.contains("return StagingLoader.readQualityRules(pFactory, pAttributeId, false);"));
		System.out.println("StagingLoaderConditionSourceTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
