package com.on.ebx.factory.scheduler;

/**
 * JUnit-free: run the main, a failure throws (same convention as {@code
 * com.on.ebx.factory.workflow.MatchingRunTaskBeanTest}). Covers ONLY {@link
 * GoldenKeyRefreshScheduledTask}'s pure static helpers, {@link GoldenKeyRefreshScheduledTask#hasRequiredProjectCode}
 * and {@link GoldenKeyRefreshScheduledTask#summary} - {@link GoldenKeyRefreshScheduledTask#execute} itself
 * needs a live EBX {@code ScheduledExecutionContext} and is out of reach for {@code tools/test_local.sh},
 * same discipline already used for every other EBX-dependent class in this module.
 */
public final class GoldenKeyRefreshScheduledTaskTest
{
	public static void main(final String[] pArgs)
	{
		GoldenKeyRefreshScheduledTaskTest.hasRequiredProjectCode_null_false();
		GoldenKeyRefreshScheduledTaskTest.hasRequiredProjectCode_blank_false();
		GoldenKeyRefreshScheduledTaskTest.hasRequiredProjectCode_valid_true();
		GoldenKeyRefreshScheduledTaskTest.summary_noEntity_everyPromotedEntity();
		GoldenKeyRefreshScheduledTaskTest.summary_withEntity_namesIt();
		GoldenKeyRefreshScheduledTaskTest.summary_blankEntity_treatedAsNoEntity();
		GoldenKeyRefreshScheduledTaskTest.summary_singularPlural();
		System.out.println("GoldenKeyRefreshScheduledTaskTest OK");
	}

	private static void hasRequiredProjectCode_null_false()
	{
		GoldenKeyRefreshScheduledTaskTest.check("null projectCode is not required-satisfied", !GoldenKeyRefreshScheduledTask.hasRequiredProjectCode(null));
	}

	private static void hasRequiredProjectCode_blank_false()
	{
		GoldenKeyRefreshScheduledTaskTest.check("blank projectCode is not required-satisfied", !GoldenKeyRefreshScheduledTask.hasRequiredProjectCode("   "));
	}

	private static void hasRequiredProjectCode_valid_true()
	{
		GoldenKeyRefreshScheduledTaskTest.check("a real projectCode satisfies the requirement", GoldenKeyRefreshScheduledTask.hasRequiredProjectCode("Companies"));
	}

	private static void summary_noEntity_everyPromotedEntity()
	{
		final String summary = GoldenKeyRefreshScheduledTask.summary("Companies", null, 2, 0);
		GoldenKeyRefreshScheduledTaskTest.check("names every promoted entity when entity is null", summary.contains("every promoted entity"));
		GoldenKeyRefreshScheduledTaskTest.check("names the project", summary.contains("Companies"));
		GoldenKeyRefreshScheduledTaskTest.check("carries the counts", summary.contains("2 entities refreshed, 0 refused."));
	}

	private static void summary_withEntity_namesIt()
	{
		final String summary = GoldenKeyRefreshScheduledTask.summary("Companies", "Company", 1, 0);
		GoldenKeyRefreshScheduledTaskTest.check("names the specific entity", summary.contains("entity Company"));
		GoldenKeyRefreshScheduledTaskTest.check("does not also say 'every promoted entity'", !summary.contains("every promoted entity"));
	}

	private static void summary_blankEntity_treatedAsNoEntity()
	{
		final String summary = GoldenKeyRefreshScheduledTask.summary("Companies", "   ", 0, 1);
		GoldenKeyRefreshScheduledTaskTest.check("blank entity is treated as null (scope = every promoted entity)", summary.contains("every promoted entity"));
	}

	private static void summary_singularPlural()
	{
		GoldenKeyRefreshScheduledTaskTest.check("singular 'entity' for one refreshed",
			GoldenKeyRefreshScheduledTask.summary("Companies", null, 1, 0).contains("1 entity refreshed"));
		GoldenKeyRefreshScheduledTaskTest.check("plural 'entities' for zero refreshed",
			GoldenKeyRefreshScheduledTask.summary("Companies", null, 0, 3).contains("0 entities refreshed"));
		GoldenKeyRefreshScheduledTaskTest.check("plural 'entities' for several refreshed",
			GoldenKeyRefreshScheduledTask.summary("Companies", null, 4, 0).contains("4 entities refreshed"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
