package com.on.ebx.factory.lineage;

import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free: run the main, a failure throws. F2-08 / F2-112: the lineage screen says "unavailable on this version" and "N of M contributors" instead of staying quiet. */
public final class LineageStatusRenderTest
{
	public static void main(final String[] pArgs)
	{
		final ObjectNode lineage = new ObjectMapper().createObjectNode();
		lineage.put("fieldLineageNote", RecordLineage.MERGE_LINEAGE_UNREADABLE_PREFIX + "a read of the merge lineage failed");
		final String en = render(lineage, Locale.ENGLISH);
		check("EN unavailable text", en.contains("Merge lineage is unavailable on this version") && en.contains("a read of the merge lineage failed"));
		final String fr = render(lineage, Locale.FRENCH);
		check("FR unavailable text", fr.contains("Le lineage de fusion est indisponible sur cette version"));

		final ObjectNode paged = new ObjectMapper().createObjectNode();
		final ObjectNode contributors = paged.putObject("contributors");
		contributors.put("total", 200);
		contributors.put("shown", 50);
		contributors.put("nextOffset", 49);
		final String page = render(paged, Locale.ENGLISH);
		check("N of M", page.contains("50 of 200 contributors shown") && page.contains("NEXT_CONTRIBUTORS"));
		check("FR N de M", render(paged, Locale.FRENCH).contains("50 de 200 contributeurs"));
		contributors.remove("nextOffset");
		check("no next button on the last page", !render(paged, Locale.ENGLISH).contains("NEXT_CONTRIBUTORS"));

		final ObjectNode all = new ObjectMapper().createObjectNode();
		all.putObject("contributors").put("total", 3).put("shown", 3);
		check("nothing to say when all are shown", render(all, Locale.ENGLISH).isEmpty());
		System.out.println("LineageStatusRenderTest OK");
	}

	private static String render(final ObjectNode pLineage, final Locale pLocale)
	{
		final StringBuilder out = new StringBuilder();
		RecordLineagePage.renderLineageStatus(out, pLineage, pLocale);
		return out.toString();
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
