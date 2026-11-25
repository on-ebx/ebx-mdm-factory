package com.on.ebx.factory.ebx.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.core.FactoryRights;

/**
 * JUnit-free, reads the sources for what needs a live EBX. Lot B8: F2-111 (the Feed designer builds its trees in memory from ONE read of the feed table), F2-108 (no English
 * text naming a repository path in the sources of the screens), F2-109 (the project explorer panes are guarded, so they write the theme tokens), F2-115 (every service has an
 * anchor in both user guides).
 */
public final class FinishingB8UiTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		FinishingB8UiTest.treeOfDepthThree();
		FinishingB8UiTest.otherRootsFollowSortedByNumericId();
		FinishingB8UiTest.aCycleNeverLoops();
		FinishingB8UiTest.unknownRequestedFeed();
		final Path java = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		final String root = "com/on/ebx/factory/";
		final String designer = FinishingB8UiTest.read(java.resolve(root + "ebx/ui/FeedDesignerUserService.java"));
		FinishingB8UiTest.check("the whole-table walk is gone", !designer.contains("collectChildFeeds") && !designer.contains("private List<Adaptation> feedTree("));
		FinishingB8UiTest.check("ONE select on TransferFeed in the Feed designer (" + FinishingB8UiTest.count(designer, "getTable(FactoryPaths.TRANSFER_FEED)") + " table accesses)",
			FinishingB8UiTest.count(designer, "UserView.select(this.factory.getTable(FactoryPaths.TRANSFER_FEED)") == 1 && designer.contains("feedTableReads++"));
		FinishingB8UiTest.check("the read is restricted to the business object", designer.contains("\"./entity=\""));

		final String explorer = FinishingB8UiTest.read(java.resolve(root + "ebx/ui/ProjectExplorerUserService.java"));
		FinishingB8UiTest.check("both explorer panes (refusal and page) are guarded, hence themed",
			explorer.contains("ScreenFailure.guard(\"project explorer refusal\"") && explorer.contains("ScreenFailure.guard(\"project explorer\""));
		FinishingB8UiTest.check("the guard writes the tokens first", FinishingB8UiTest.read(java.resolve(root + "ebx/ui/ScreenFailure.java")).contains("ThemeTokens.styleTagFor(pPaneContext)"));

		// F2-108: no repository path, no workflow model name in a literal of the files that put text on a screen
		for (final String file : new String[] { "ebx/Intake.java", "intake/IntakeStatus.java", "wizard/IntakeScreens.java", "ebx/ui/FeedDesignerUserService.java",
			"ebx/ui/ProjectExplorerUserService.java", "ebx/NormalisationSuggester.java", "ebx/StagingProposalRun.java", "mappingmatrix/MatrixHtml.java" })
		{
			final String source = FinishingB8UiTest.read(java.resolve(root + file));
			for (final String line : source.split("\n"))
			{
				final String code = line.trim();
				if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*"))
				{
					continue;
				}
				for (final String banned : new String[] { "tools/", "MdmFactoryPipeline", "docs/" })
				{
					FinishingB8UiTest.check(file + " carries a literal with [" + banned + "]: " + code, !FinishingB8UiTest.literalContains(code, banned));
				}
			}
		}

		// F2-115: every service has an anchor in both guides
		final Path guides = java.getParent().resolve("webapp/www/common/html/factory");
		for (final String guide : new String[] { "user-guide-en.html", "user-guide-fr.html" })
		{
			final String html = FinishingB8UiTest.read(guides.resolve(guide));
			for (final String service : FactoryRights.services().keySet())
			{
				FinishingB8UiTest.check(guide + " has no id=\"" + service + "\" anchor", html.contains("id=\"" + service + "\""));
			}
		}
		System.out.println("FinishingB8UiTest OK");
	}

	private static void treeOfDepthThree()
	{
		final Map<String, String> parent = new LinkedHashMap<String, String>();
		parent.put("10", "");
		parent.put("12", "11");
		parent.put("11", "10");
		parent.put("13", "12");
		parent.put("14", "10");
		FinishingB8UiTest.check("depth 3, depth first, siblings in table order: " + FeedDesignerUserService.pageOrder("10", parent),
			FeedDesignerUserService.pageOrder("10", parent).equals(Arrays.asList("10", "11", "12", "13", "14")));
	}

	private static void otherRootsFollowSortedByNumericId()
	{
		final Map<String, String> parent = new LinkedHashMap<String, String>();
		parent.put("30", "");
		parent.put("5", "");
		parent.put("31", "30");
		parent.put("6", "5");
		parent.put("9", "");
		parent.put("20", "");
		final List<String> order = FeedDesignerUserService.pageOrder("9", parent);
		FinishingB8UiTest.check("requested tree first, then the others by numeric id (not text order): " + order, order.equals(Arrays.asList("9", "5", "6", "20", "30", "31")));
	}

	private static void aCycleNeverLoops()
	{
		final Map<String, String> parent = new LinkedHashMap<String, String>();
		parent.put("1", "");
		parent.put("2", "3");
		parent.put("3", "2");
		FinishingB8UiTest.check("an orphan cycle is not listed and does not loop", FeedDesignerUserService.pageOrder("1", parent).equals(Arrays.asList("1")));
	}

	private static void unknownRequestedFeed()
	{
		final Map<String, String> parent = new LinkedHashMap<String, String>();
		parent.put("1", "");
		FinishingB8UiTest.check("an unknown feed gives an empty page", FeedDesignerUserService.pageOrder("99", parent).isEmpty());
	}

	/** Whether a string literal of {@code pCode} contains {@code pWhat}: the text between two unescaped double quotes. */
	private static boolean literalContains(final String pCode, final String pWhat)
	{
		boolean in = false;
		final StringBuilder literal = new StringBuilder();
		for (int i = 0; i < pCode.length(); i++)
		{
			final char c = pCode.charAt(i);
			if (c == '\\' && in)
			{
				i++;
				continue;
			}
			if (c == '"')
			{
				if (in && literal.indexOf(pWhat) >= 0)
				{
					return true;
				}
				in = !in;
				literal.setLength(0);
			}
			else if (in)
			{
				literal.append(c);
			}
		}
		return false;
	}

	private static int count(final String pText, final String pWhat)
	{
		int n = 0;
		for (int at = pText.indexOf(pWhat); at >= 0; at = pText.indexOf(pWhat, at + pWhat.length()))
		{
			n++;
		}
		return n;
	}

	private static String read(final Path pPath) throws IOException
	{
		return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
