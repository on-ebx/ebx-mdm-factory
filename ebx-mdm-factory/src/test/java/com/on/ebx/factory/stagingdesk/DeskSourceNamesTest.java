package com.on.ebx.factory.stagingdesk;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.review.ReviewLine;

/** JUnit-free: run the main, a failure throws. How the desk names a source (DemoBala: landing dataset names versus SAP / Salesforce). */
public final class DeskSourceNamesTest
{
	public static void main(final String[] pArgs)
	{
		final MatrixModel.Project demo = DeskSourceNamesTest.project("PERSONNESSAPSAMPLE", "Personnes_SAP_Sample", "PERSONNESSALESFORCESAMPLE", "Personnes_Salesforce_Sample");
		final Map<String, String> names = DeskSourceNames.of(demo);
		check("SAP: " + names, "SAP".equals(names.get("s1")) && "Salesforce".equals(names.get("s2")));
		check("a source named by a person is kept", "SAP".equals(DeskSourceNames.of(DeskSourceNamesTest.project("SAP", "Personnes_SAP_Sample", "Salesforce", "Personnes_Salesforce_Sample")).get("s1")));
		check("one source: nothing to tell apart, as it is",
			"PERSONNESSAPSAMPLE".equals(DeskSourceNames.of(new MatrixModel.Project("p", Arrays.asList(new MatrixModel.Source("s1", "PERSONNESSAPSAMPLE", "#000")),
				Arrays.asList(entity("Personnes_SAP_Sample", "s1")))).get("s1")));
		check("a feed gives its source's name", "Salesforce".equals(DeskSourceNames.name(demo, demo.entities.get(0).feeds.get(1))));
		System.out.println("DeskSourceNamesTest OK");
	}

	private static MatrixModel.Entity entity(final String pTable, final String pSource)
	{
		return new MatrixModel.Entity("e", "E", Arrays.<MatrixModel.Attr>asList(), Arrays.asList(feed("f" + pSource, pSource, pTable)), ReviewLine.PROPOSED, Integer.valueOf(90));
	}

	private static MatrixModel.Feed feed(final String pId, final String pSource, final String pTable)
	{
		return new MatrixModel.Feed(pId, pSource, "t" + pId, pTable, ReviewLine.ACCEPTED, null, "", false, null, new LinkedHashMap<String, MatrixModel.Cell>(), Integer.valueOf(90), "", "");
	}

	private static MatrixModel.Project project(final String pSystem1, final String pTable1, final String pSystem2, final String pTable2)
	{
		final MatrixModel.Entity entity = new MatrixModel.Entity("e", "Adresse", Arrays.<MatrixModel.Attr>asList(),
			Arrays.asList(feed("f1", "s1", pTable1), feed("f2", "s2", pTable2)), ReviewLine.PROPOSED, Integer.valueOf(90));
		return new MatrixModel.Project("DemoBala", Arrays.asList(new MatrixModel.Source("s1", pSystem1, "#000"), new MatrixModel.Source("s2", pSystem2, "#111")), Arrays.asList(entity));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
