package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.on.ebx.factory.project.ProjectDeletionPlan;

/**
 * JUnit-free: run the main, a failure throws. Fix of 2026-10-12 (the targets of deleted projects and their Connectors mappings were left behind): a structural check
 * of the wiring the offline suite cannot run (it needs an EBX repository) - the deleter gathers the project's targets, executes the new kind like a landing flow, deletes
 * the target and pause rows, and the ownership marker of the plan is the label {@code DistributionConfigurator} writes.
 */
public final class ProjectDeleterDistributionSourceTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java")).resolve("com/on/ebx/factory");
		final String deleter = ProjectDeleterDistributionSourceTest.read(root.resolve("ebx/ProjectDeleter.java"));
		ProjectDeleterDistributionSourceTest.check("the targets of the project are gathered", deleter.contains("queryEq(pFactory, FactoryPaths.DISTRIBUTION_TARGET, \"./project\", projectId)"));
		ProjectDeleterDistributionSourceTest.check("their mapping ids are parsed with TargetMappingList",
			deleter.contains("TargetMappingList.parse(row.getString(FactoryPaths.DistributionTarget.CONNECTORS_MAPPING_IDS))"));
		ProjectDeleterDistributionSourceTest.check("the new kind is deleted like a landing flow", deleter.contains("case LANDING_FLOW:\n\t\t\t\tcase DISTRIBUTION_FLOW:\n\t\t\t\t\treturn GeneratedObjectDeleter.deleteMapping("));
		ProjectDeleterDistributionSourceTest.check("the target rows are deleted with the project's own rows", deleter.contains("rowsByTable.put(\"DistributionTarget\", distributionTargets)"));
		ProjectDeleterDistributionSourceTest.check("the pauses of the targets are deleted with them", deleter.contains("FactoryPaths.LoadPause.KIND_DISTRIBUTION_TARGET")
			&& deleter.contains("rowsByTable.put(\"LoadPause\", loadPauses)"));
		ProjectDeleterDistributionSourceTest.check("the targets are counted in the plan's row counts (rowsByTable is)", deleter.contains("for (final Map.Entry<String, List<Adaptation>> entry : rowsByTable.entrySet())"));
		ProjectDeleterDistributionSourceTest.check("a scheduler schedule or launcher left behind is reported", deleter.contains("schedulerLeftBehind"));
		final String configurator = ProjectDeleterDistributionSourceTest.read(root.resolve("ebx/DistributionConfigurator.java"));
		ProjectDeleterDistributionSourceTest.check("the ownership marker is the label DistributionConfigurator writes",
			configurator.contains("return \"Distribution: target \" + pTargetId + \" \" + pTablePath;") && "Distribution: target 7 ".equals(ProjectDeletionPlan.distributionLabelPrefix("7")));
		System.out.println("ProjectDeleterDistributionSourceTest OK");
	}

	private static String read(final Path pPath) throws IOException
	{
		return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
