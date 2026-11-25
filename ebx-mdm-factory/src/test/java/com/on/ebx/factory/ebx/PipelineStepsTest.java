package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. Same package as {@link PipelineSteps} on purpose, to reach its
 * package-private {@link PipelineSteps#scopeEquals} directly - the exact predicate fragment the live incident
 * of 2026-09-20 ("Project Companies has no loaded staging entity yet" although {@code STAGING_LOAD} was
 * {@code DONE}) turned on.
 */
public final class PipelineStepsTest
{
	public static void main(final String[] pArgs)
	{
		PipelineStepsTest.check("empty scope: osd:is-null, never ./scope=''",
			"osd:is-null(./scope)".equals(PipelineSteps.scopeEquals("./scope", "")));
		PipelineStepsTest.check("null scope: same as empty", "osd:is-null(./scope)".equals(PipelineSteps.scopeEquals("./scope", null)));
		PipelineStepsTest.check("plain non-empty scope: ordinary quoted equality",
			"./scope='ERP'".equals(PipelineSteps.scopeEquals("./scope", "ERP")));
		PipelineStepsTest.check("a scope containing a quote is escaped by doubling, like CatalogReader.quote itself",
			"./scope='O''Brien'".equals(PipelineSteps.scopeEquals("./scope", "O'Brien")));
		System.out.println("PipelineStepsTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
