package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. Fix of 2026-10-03 (Vol100k / Vol100kM): the
 * mapping label carries the project, so two projects reading the same source system never share a mapping
 * ({@link LandingFlow#label}), and a table that already owns a mapping updates it by id ({@link LandingFlow.Table#forProject}).
 */
public final class LandingFlowLabelTest
{
	public static void main(final String[] pArgs)
	{
		LandingFlowLabelTest.labelCarriesTheProject();
		LandingFlowLabelTest.longLabelStaysWithinConnectorsLimitAndStaysUnique();
		LandingFlowLabelTest.blankProjectKeepsTheLegacyLabel();
		LandingFlowLabelTest.everyMappingShapeGetsTheProjectLabel();
		LandingFlowLabelTest.existingMappingIsUpdatedById();
		LandingFlowLabelTest.projectAndIdSurviveTheChangeColumnCopies();
		System.out.println("LandingFlowLabelTest OK");
	}

	private static void labelCarriesTheProject()
	{
		check("project code, source system, table in that order", "Landing: Vol100k VOL100KCRM CONTACT".equals(LandingFlow.label("Vol100k", "VOL100KCRM", "CONTACT")));
		check("two projects on the same source system get two labels",
			!LandingFlow.label("Vol100k", "VOL100KCRM", "CONTACT").equals(LandingFlow.label("Vol100kM", "VOL100KCRM", "CONTACT")));
		check("the label is deterministic (the upsert is idempotent on it)", LandingFlow.label("P", "S", "T").equals(LandingFlow.label("P", "S", "T")));
	}

	private static void longLabelStaysWithinConnectorsLimitAndStaysUnique()
	{
		final String project = "ProjectCodeOfFortyCharactersExactly_123456";
		final String sourceSystem = "A_VERY_LONG_SOURCE_SYSTEM_NAME_THAT_KEEPS_GOING_ON_AND_ON";
		final String a = LandingFlow.label(project, sourceSystem, "LANDING_TABLE_WITH_A_LONG_NAME_ONE");
		final String b = LandingFlow.label(project, sourceSystem, "LANDING_TABLE_WITH_A_LONG_NAME_TWO");
		check("cut to Mapping.label's maxLength: " + a.length(), a.length() <= LandingFlow.LABEL_MAX && b.length() <= LandingFlow.LABEL_MAX);
		check("two long labels differing only past the cut stay different", !a.equals(b));
		check("a cut label is stable", a.equals(LandingFlow.label(project, sourceSystem, "LANDING_TABLE_WITH_A_LONG_NAME_ONE")));
		check("a label that fits is never cut", "Landing: P S T".equals(LandingFlow.label("P", "S", "T")));
	}

	private static void blankProjectKeepsTheLegacyLabel()
	{
		check("no project: the old label", "Landing: S T".equals(LandingFlow.label(null, "S", "T")) && "Landing: S T".equals(LandingFlow.label(" ", "S", "T")));
		check("the legacy label is what an earlier generation wrote", "Landing: S T".equals(LandingFlow.legacyLabel("S", "T")));
	}

	private static List<LandingFlow.Column> columns()
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("CONTACT_ID", "CONTACT_ID", true));
		columns.add(new LandingFlow.Column("NAME", "NAME", false));
		return columns;
	}

	private static LandingFlow.Table table(final String pType, final boolean pFileFeed)
	{
		return new LandingFlow.Table("Conn", pType, "CRM", "PUBLIC.CONTACT", "Landing_CRM", "CRM", "CONTACT", LandingFlowLabelTest.columns(), pFileFeed);
	}

	/** The SQL, the file feed and the recipe builders all set the label the same way. */
	private static void everyMappingShapeGetsTheProjectLabel()
	{
		final ObjectNode sql = LandingFlow.mapping(LandingFlowLabelTest.table("h2", false).forProject("PrjA", null));
		check("SQL flow: project label", "Landing: PrjA CRM CONTACT".equals(sql.path("label").asText()));
		final ObjectNode feed = LandingFlow.mapping(LandingFlowLabelTest.table("sample-file", true).forProject("PrjA", null));
		check("file feed flow: project label", "Landing: PrjA CRM CONTACT".equals(feed.path("label").asText()));
		check("no id, no updateById without an existing mapping", !sql.has("id") && !sql.has("updateById") && !feed.has("id") && !feed.has("updateById"));
		final ObjectNode other = LandingFlow.mapping(LandingFlowLabelTest.table("h2", false).forProject("PrjB", null));
		check("same source system, another project: another label", !other.path("label").asText().equals(sql.path("label").asText()));
		check("the landing dataspace stays the project's own", "Landing_CRM".equals(sql.path("dataspace").asText()));
	}

	private static void existingMappingIsUpdatedById()
	{
		final ObjectNode sql = LandingFlow.mapping(LandingFlowLabelTest.table("h2", false).forProject("PrjA", " 346 "));
		check("id is sent, trimmed", "346".equals(sql.path("id").asText()));
		check("updateById is set so Connectors falls back to the id when the (new) label finds nothing", sql.path("updateById").asBoolean(false));
		check("and the label is the new one, so the update renames the mapping", "Landing: PrjA CRM CONTACT".equals(sql.path("label").asText()));
		final ObjectNode feed = LandingFlow.mapping(LandingFlowLabelTest.table("sample-file", true).forProject("PrjA", "12"));
		check("file feed: same", "12".equals(feed.path("id").asText()) && feed.path("updateById").asBoolean(false));
		final ObjectNode blank = LandingFlow.mapping(LandingFlowLabelTest.table("h2", false).forProject("PrjA", "  "));
		check("a blank existing id is no id", !blank.has("id") && !blank.has("updateById"));
	}

	private static void projectAndIdSurviveTheChangeColumnCopies()
	{
		final LandingFlow.Table base = LandingFlowLabelTest.table("h2", false).forProject("PrjA", "346");
		final LandingFlow.Table incremental = base.withChangeColumn("NAME", "ORDERED_STRING");
		check("withChangeColumn keeps the project and the id", "PrjA".equals(incremental.projectCode) && "346".equals(incremental.existingMappingId));
		final LandingFlow.Table full = incremental.withoutChangeColumn();
		check("withoutChangeColumn keeps them too", "PrjA".equals(full.projectCode) && "346".equals(full.existingMappingId));
		check("forProject keeps the change column", "NAME".equals(base.withChangeColumn("NAME", "ORDERED_STRING").forProject("PrjB", null).changeColumn));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
