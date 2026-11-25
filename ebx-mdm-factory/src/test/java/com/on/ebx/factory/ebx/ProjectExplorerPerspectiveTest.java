package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JUnit-free: run the main, a failure throws. Guards the project explorer lot's perspective edit
 * (PLAN.md screen 6, 2026-09-23): the packaged {@code MdmFactory.perspective.json} parses, and its
 * "Projects" section starts with a "Project explorer" action, {@code default: true}, targeting the new
 * {@code ProjectExplorer} service - never a full re-verification of {@link PerspectiveInstaller}/
 * {@link PerspectiveCompletion} itself (their own test classes already cover that).
 * <p>
 * 2026-09-23, contextual-return lot: the plain "Projects" table item (no {@code serviceKey}, xpath
 * {@code /root/Project}) is now REMOVED from the packaged JSON entirely, at the coordinator's own request -
 * "Project explorer" replaces it rather than merely outranking it as the default. See {@code
 * docs/PROJECT-EXPLORER.md} for what this change does, and does not, do to an already-installed perspective
 * ({@code PerspectiveCompletion} only ever adds a missing item; it never removes one, so this edit has zero
 * live effect there - the old item stays exactly as it was until someone changes it by hand or with the
 * perspective tools).
 */
public final class ProjectExplorerPerspectiveTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		ProjectExplorerPerspectiveTest.packagedJsonParses();
		ProjectExplorerPerspectiveTest.projectExplorerIsTheFirstChildOfProjectsAndIsDefault();
		ProjectExplorerPerspectiveTest.plainProjectsTableItemIsGone();

		System.out.println("ProjectExplorerPerspectiveTest OK");
	}

	private static void packagedJsonParses() throws Exception
	{
		final JsonNode definition = PerspectiveInstaller.definition();
		ProjectExplorerPerspectiveTest.check("the definition has a top-level items array", definition.path("items").isArray());
	}

	private static void projectExplorerIsTheFirstChildOfProjectsAndIsDefault() throws Exception
	{
		final JsonNode projects = ProjectExplorerPerspectiveTest.projectsSection();
		final JsonNode children = projects.path("children");
		ProjectExplorerPerspectiveTest.check("the Projects section has at least one child", children.size() > 0);
		final JsonNode first = children.get(0);
		ProjectExplorerPerspectiveTest.check("the first child is \"Project explorer\"", "Project explorer".equals(first.path("labelEn").asText()));
		ProjectExplorerPerspectiveTest.check("its French label is set", "Explorateur de projets".equals(first.path("labelFr").asText()));
		ProjectExplorerPerspectiveTest.check("it is the perspective's default selection", first.path("default").asBoolean(false));
		ProjectExplorerPerspectiveTest.check("it targets the new ProjectExplorer service",
			"ebx-mdm-factory@/WEB-INF/ebx/schemas/MdmFactory.xsd@ProjectExplorer".equals(first.path("serviceKey").asText()));
		ProjectExplorerPerspectiveTest.check("its service parameter names ProjectExplorer", ProjectExplorerPerspectiveTest.hasParameter(first, "service", "ProjectExplorer"));
		ProjectExplorerPerspectiveTest.check("it targets the Project table", ProjectExplorerPerspectiveTest.hasParameter(first, "xpath", "/root/Project"));
	}

	/** The plain table-shorthand "Projects" item ({@code xpath: /root/Project}, no {@code serviceKey}) is gone - "Project explorer" replaces it. */
	private static void plainProjectsTableItemIsGone() throws Exception
	{
		final JsonNode projects = ProjectExplorerPerspectiveTest.projectsSection();
		final JsonNode children = projects.path("children");
		for (final JsonNode child : children)
		{
			ProjectExplorerPerspectiveTest.check("no remaining child is the plain table-shorthand \"Projects\" item",
				!("Projects".equals(child.path("labelEn").asText()) && !child.has("serviceKey")));
		}
	}

	private static JsonNode projectsSection() throws Exception
	{
		final JsonNode definition = PerspectiveInstaller.definition();
		for (final JsonNode item : definition.path("items"))
		{
			if ("Projects".equals(item.path("labelEn").asText()))
			{
				return item;
			}
		}
		throw new AssertionError("no top-level \"Projects\" section in the packaged perspective");
	}

	private static boolean hasParameter(final JsonNode pItem, final String pName, final String pValue)
	{
		for (final JsonNode parameter : pItem.path("parameters"))
		{
			if (pName.equals(parameter.path("name").asText()) && pValue.equals(parameter.path("value").asText()))
			{
				return true;
			}
		}
		return false;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
