package com.on.ebx.factory.ebx;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.on.ebx.factory.quality.QualityRuleCheck;
import com.orchestranetworks.service.LoggingCategory;

/**
 * Reports, once per rule and JVM, that a {@code PATTERN} quality rule cannot validate anything - its pattern is refused by {@code
 * quality.RegexGuard} (too long, a catastrophic shape), invalid, or kept running past its time limit - so the rows it was meant to check
 * are NOT validated by it. Such a row is neither a pass nor a failure and is not counted as checked; the kernel log is where it shows.
 */
final class QualityRuleProblems
{
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	private QualityRuleProblems()
	{
	}

	/** Logs the rule's problem the first time this rule id is reported. Never throws. */
	static void reportOnce(final QualityRuleCheck.Rule pRule)
	{
		if (!QualityRuleProblems.REPORTED.add(pRule.id))
		{
			return;
		}
		try
		{
			LoggingCategory.getKernel().warn("[mdm-factory] quality rule " + pRule.id + " (" + pRule.kind + ") validates nothing: " + pRule.problem()
				+ ". Its rows are not validated by this rule (not counted as checked, neither passed nor failed): propose a simpler pattern.");
		}
		catch (final RuntimeException ex)
		{
			// logging must never break a load
		}
	}
}
