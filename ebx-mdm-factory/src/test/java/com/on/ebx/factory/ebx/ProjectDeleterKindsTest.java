package com.on.ebx.factory.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.on.ebx.factory.project.ProjectDeletionPlan;

/**
 * Staging integration (2026-10-17, live: "Unknown item kind CATALOG_SOURCE"): every kind the deletion PLAN can list must be EXECUTED by
 * {@code ProjectDeleter#execute} - lot C added CATALOG_SOURCE and CONNECTION to the plan and the screen, never to the switch, and every unit test passed.
 * Read from the source like {@link MasterRejectsSourceTest}: the switch needs an EBX session to run.
 */
public final class ProjectDeleterKindsTest
{
	private ProjectDeleterKindsTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String root = System.getProperty("mdmf.sourceDir", "src/main/java") + "/com/on/ebx/factory/ebx/";
		final String deleter = new String(Files.readAllBytes(Paths.get(root + "ProjectDeleter.java")), StandardCharsets.UTF_8);
		final int start = deleter.indexOf("private static GeneratedObjectDeleter.Outcome execute(");
		final int end = deleter.indexOf("Unknown item kind", start);
		if (start < 0 || end < 0)
		{
			throw new AssertionError("ProjectDeleter#execute and its default branch were not found");
		}
		final String body = deleter.substring(start, end);
		for (final ProjectDeletionPlan.Kind kind : ProjectDeletionPlan.Kind.values())
		{
			if (!body.contains("case " + kind.name() + ":"))
			{
				throw new AssertionError("ProjectDeleter#execute has no case for the planned kind " + kind + ": the deletion would answer 'Unknown item kind'");
			}
		}
		// lot M18 (2026-10-18): the business object is removed by the add-on-dataset procedure, in the plan's own order, and a failure keeps the master model and dataspace
		final int businessObjectCase = body.indexOf("case MATCHING_BUSINESS_OBJECT:");
		final int memberCase = body.indexOf("case MATCHING_CONFIG:");
		if (businessObjectCase < 0 || memberCase < 0 || businessObjectCase > memberCase
			|| !body.substring(businessObjectCase, memberCase).contains("GeneratedObjectDeleter.deleteBusinessObjectConfig("))
		{
			throw new AssertionError("MATCHING_BUSINESS_OBJECT must be executed by deleteBusinessObjectConfig");
		}
		if (!deleter.contains("for (final Item item : plan.items)") || deleter.contains("plan.items.sort") || deleter.contains("Collections.sort(plan.items"))
		{
			throw new AssertionError("the deletion must execute the items in the plan's own order (business objects, then member configurations, then dataspaces, then models)");
		}
		final int loop = deleter.indexOf("for (final Item item : plan.items)");
		final int guard = deleter.indexOf("businessObjectFailed && (item.kind == ProjectDeletionPlan.Kind.MASTER_MODEL", loop);
		final int firstExecute = deleter.indexOf("ProjectDeleter.execute(pSession, pFactory, gathered.facts, item)", loop);
		if (guard < 0 || firstExecute < 0 || guard > firstExecute)
		{
			throw new AssertionError("a failed business-object removal must keep the master model and dataspace BEFORE any item is executed");
		}
		if (!deleter.contains("MameConfigRows.remainingCounts(") || !deleter.contains("matchingConfigRowsLeft"))
		{
			throw new AssertionError("the deletion must read the add-on's dataset back after the matching items");
		}
		System.out.println("ProjectDeleterKindsTest OK");
	}
}
