package com.on.ebx.factory.lineage;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.lineage.RecordLineage.FieldInput;
import com.on.ebx.factory.lineage.RecordLineage.FieldValue;
import com.on.ebx.factory.lineage.RecordLineage.Input;
import com.on.ebx.factory.lineage.RecordLineage.MergeInfo;
import com.on.ebx.factory.lineage.RecordLineage.SourceChain;

/**
 * JUnit-free, like every other pure-model test of this module: run the main, a failure throws. Every fixture
 * goes through {@link RecordLineage#assemble} first (never a hand-written JSON string), so a fixture can
 * never drift from what {@code ebx.RecordLineageReader} actually hands {@link CrossReferences#project} in
 * production - the same discipline {@code RecordLineageTest}/{@code RecordLineagePageTest} already follow.
 */
public final class CrossReferencesTest
{
	public static void main(final String[] pArgs)
	{
		CrossReferencesTest.goldenWithTwoMergedMembersOneRestricted_allFieldsProjected();
		CrossReferencesTest.restrictedRow_onlyMasterKeyGoldenSourceSystemRestricted();
		CrossReferencesTest.suspectSources_neverProjected();
		CrossReferencesTest.mergeAbsent_stateAndGoldenKeyNull_xrefsStillProjected();
		CrossReferencesTest.noUrls_fieldsSimplyAbsent();
		CrossReferencesTest.noSources_emptyXrefsArray();
		CrossReferencesTest.orderPreserved_neverSorted();
		System.out.println("CrossReferencesTest OK");
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	private static SourceChain chain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final String pSourceId, final String pStagingKey)
	{
		return CrossReferencesTest.chain(pMasterKey, pGolden, pSourceSystem, false, pSourceId, pStagingKey, null, null);
	}

	private static SourceChain chainWithUrls(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final String pSourceId,
		final String pStagingKey, final String pLandingUrl, final String pStagingUrl)
	{
		return CrossReferencesTest.chain(pMasterKey, pGolden, pSourceSystem, false, pSourceId, pStagingKey, pLandingUrl, pStagingUrl);
	}

	private static SourceChain restrictedChain(final String pMasterKey, final String pSourceSystem)
	{
		// deliberately still carries a sourceId/staging key, same defense-in-depth discipline as
		// RecordLineageTest#restrictedSource_onlySystemNameEmitted - proves CrossReferences#project redacts
		// because RecordLineage#assemble already redacted upstream, not because this fixture happens to be clean.
		return CrossReferencesTest.chain(pMasterKey, false, pSourceSystem, true, "SHOULD-NOT-LEAK", "SHOULD-NOT-LEAK-EITHER", null, null);
	}

	private static SourceChain chain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final boolean pRestricted, final String pSourceId,
		final String pStagingKey, final String pLandingUrl, final String pStagingUrl)
	{
		return new SourceChain(pMasterKey, pGolden, pSourceSystem, pRestricted, pSourceId, "Companies_Landing",
			"SUPPLIER".equals(pSourceSystem) || "ERP".equals(pSourceSystem) ? "SUPPLIER" : "ACCOUNT", pSourceId, "L1", "STAGED", new Date(0L), "Company", pStagingKey,
			pLandingUrl, pStagingUrl);
	}

	private static ObjectNode lineage(final String pMasterKey, final List<SourceChain> pSources, final MergeInfo pMerge)
	{
		return RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", pMasterKey, new LinkedHashMap<String, FieldValue>(), pSources,
			Collections.<FieldInput>emptyList(), pMerge, null));
	}

	// ------------------------------------------------------------------------------------------------------ tests

	/**
	 * The scenario the task brief names explicitly: a golden and two merged members, one of them restricted.
	 * Every field the REST route documents is checked on the two visible rows, plus the top-level anchor.
	 */
	private static void goldenWithTwoMergedMembersOneRestricted_allFieldsProjected()
	{
		final List<SourceChain> sources = Arrays.asList(CrossReferencesTest.chainWithUrls("12", true, "ERP", "SUP-00231", "ERP|SUP-00231",
			"https://ebx/landing/12", "https://ebx/staging/12"), CrossReferencesTest.chain("7", false, "CRM", "ACC-1", "CRM|ACC-1"),
			CrossReferencesTest.restrictedChain("9", "POS"));
		final ObjectNode json = CrossReferencesTest.lineage("12", sources, MergeInfo.present("GOLDEN", "42", "12", false));

		final ObjectNode projected = CrossReferences.project(json);
		CrossReferencesTest.check("project passes through", "Companies".equals(projected.get("project").asText()));
		CrossReferencesTest.check("entity passes through", "Company".equals(projected.get("entity").asText()));
		CrossReferencesTest.check("masterTable passes through", "/root/Company".equals(projected.get("masterTable").asText()));
		CrossReferencesTest.check("masterKey passes through", "12".equals(projected.get("masterKey").asText()));
		CrossReferencesTest.check("present is true", projected.get("present").asBoolean());
		CrossReferencesTest.check("state is the anchor's own merge state", "GOLDEN".equals(projected.get("state").asText()));
		CrossReferencesTest.check("goldenKey is the anchor's own golden key", "12".equals(projected.get("goldenKey").asText()));

		final JsonNode xrefs = projected.get("xrefs");
		CrossReferencesTest.check("one row per source, including the restricted one", xrefs.size() == 3);

		final JsonNode golden = xrefs.get(0);
		CrossReferencesTest.check("golden row masterKey", "12".equals(golden.get("masterKey").asText()));
		CrossReferencesTest.check("golden row golden flag", golden.get("golden").asBoolean());
		CrossReferencesTest.check("golden row sourceSystem", "ERP".equals(golden.get("sourceSystem").asText()));
		CrossReferencesTest.check("golden row sourceId", "SUP-00231".equals(golden.get("sourceId").asText()));
		CrossReferencesTest.check("golden row stagingTable", "Company".equals(golden.get("stagingTable").asText()));
		CrossReferencesTest.check("golden row stagingKey", "ERP|SUP-00231".equals(golden.get("stagingKey").asText()));
		CrossReferencesTest.check("golden row landingDataset", "Companies_Landing".equals(golden.get("landingDataset").asText()));
		CrossReferencesTest.check("golden row landingTable", "SUPPLIER".equals(golden.get("landingTable").asText()));
		CrossReferencesTest.check("golden row landingKey", "SUP-00231".equals(golden.get("landingKey").asText()));
		CrossReferencesTest.check("golden row loadId", "L1".equals(golden.get("loadId").asText()));
		CrossReferencesTest.check("golden row receivedAt", golden.get("receivedAt").asText().startsWith("1970-01-01"));
		CrossReferencesTest.check("golden row restricted is false", !golden.get("restricted").asBoolean());
		CrossReferencesTest.check("golden row landingUrl carried through", "https://ebx/landing/12".equals(golden.get("landingUrl").asText()));
		CrossReferencesTest.check("golden row stagingUrl carried through", "https://ebx/staging/12".equals(golden.get("stagingUrl").asText()));

		final JsonNode member = xrefs.get(1);
		CrossReferencesTest.check("member row masterKey", "7".equals(member.get("masterKey").asText()));
		CrossReferencesTest.check("member row golden flag is false", !member.get("golden").asBoolean());
		CrossReferencesTest.check("member row sourceSystem", "CRM".equals(member.get("sourceSystem").asText()));
		CrossReferencesTest.check("member row restricted is false", !member.get("restricted").asBoolean());
	}

	/** Same all-or-nothing rule {@code RecordLineage#assemble} already enforces on {@code sources[]}: nothing beyond masterKey/golden/sourceSystem/restricted for a hidden row. */
	private static void restrictedRow_onlyMasterKeyGoldenSourceSystemRestricted()
	{
		final List<SourceChain> sources = Arrays.asList(CrossReferencesTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"),
			CrossReferencesTest.restrictedChain("2", "CRM"));
		final ObjectNode json = CrossReferencesTest.lineage("1", sources, MergeInfo.present("MERGED", "1", "1", false));

		JsonNode restricted = null;
		for (final JsonNode xref : CrossReferences.project(json).get("xrefs"))
		{
			if (xref.get("restricted").asBoolean())
			{
				restricted = xref;
			}
		}
		CrossReferencesTest.check("a restricted row is present", restricted != null);
		CrossReferencesTest.check("only masterKey/golden/sourceSystem/restricted are emitted", restricted.size() == 4);
		CrossReferencesTest.check("sourceSystem is kept (not sensitive)", "CRM".equals(restricted.get("sourceSystem").asText()));
		CrossReferencesTest.check("sourceId never leaks", restricted.get("sourceId") == null);
		CrossReferencesTest.check("stagingKey never leaks", restricted.get("stagingKey") == null);
		CrossReferencesTest.check("landingKey never leaks", restricted.get("landingKey") == null);
	}

	/**
	 * A pending suspect is not yet a confirmed contributor - {@link CrossReferences#project} must read only
	 * {@code sources[]}, never {@code suspectSources[]}, even when the lineage JSON carries both.
	 */
	private static void suspectSources_neverProjected()
	{
		final List<SourceChain> sources = Arrays.asList(CrossReferencesTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final List<FieldInput> noFields = Collections.<FieldInput>emptyList();
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources,
			noFields, MergeInfo.present("GOLDEN", "1", "1", false), null, null, null,
			Arrays.asList(CrossReferencesTest.chain("99", false, "CRM", "ACC-99", "CRM|ACC-99"))));

		CrossReferencesTest.check("the lineage JSON does carry a suspect source", json.get("suspectSources").size() == 1);
		final JsonNode xrefs = CrossReferences.project(json).get("xrefs");
		CrossReferencesTest.check("only the one confirmed source is projected", xrefs.size() == 1);
		CrossReferencesTest.check("the suspect's own key never appears among the xrefs", "1".equals(xrefs.get(0).get("masterKey").asText()));
	}

	/** {@code merge.present: false}: state/goldenKey are absent (null), but sources[] is still projected as usual - the two are independent. */
	private static void mergeAbsent_stateAndGoldenKeyNull_xrefsStillProjected()
	{
		final List<SourceChain> sources = Arrays.asList(CrossReferencesTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final ObjectNode json = CrossReferencesTest.lineage("1", sources, MergeInfo.absent(MergeInfo.NO_MATCHING_YET));

		final ObjectNode projected = CrossReferences.project(json);
		CrossReferencesTest.check("present is false", !projected.get("present").asBoolean());
		CrossReferencesTest.check("state is null", projected.get("state").isNull());
		CrossReferencesTest.check("goldenKey is null", projected.get("goldenKey").isNull());
		CrossReferencesTest.check("the one source is still projected", projected.get("xrefs").size() == 1);
	}

	/** A source with no "open in EBX" link (could not be built) simply omits landingUrl/stagingUrl - never a null placeholder. */
	private static void noUrls_fieldsSimplyAbsent()
	{
		final List<SourceChain> sources = Arrays.asList(CrossReferencesTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final ObjectNode json = CrossReferencesTest.lineage("1", sources, MergeInfo.present("GOLDEN", "1", "1", false));

		final JsonNode row = CrossReferences.project(json).get("xrefs").get(0);
		CrossReferencesTest.check("landingUrl is absent, not null", row.get("landingUrl") == null);
		CrossReferencesTest.check("stagingUrl is absent, not null", row.get("stagingUrl") == null);
	}

	/** No contributing source at all (e.g. an add-on-auto-created golden with no Xref row of its own) is an empty array, not an error. */
	private static void noSources_emptyXrefsArray()
	{
		final ObjectNode json = CrossReferencesTest.lineage("322", Collections.<SourceChain>emptyList(), MergeInfo.present("GOLDEN", "1", "322", true));
		final JsonNode xrefs = CrossReferences.project(json).get("xrefs");
		CrossReferencesTest.check("empty, not absent", xrefs != null && xrefs.size() == 0);
	}

	/** {@code sources[]} is never sorted, filtered or reordered by this projection - the reader already bounds and orders it. */
	private static void orderPreserved_neverSorted()
	{
		final List<SourceChain> sources = Arrays.asList(CrossReferencesTest.chain("30", false, "POS", "P-1", "POS|P-1"),
			CrossReferencesTest.chain("10", true, "ERP", "SUP-1", "ERP|SUP-1"), CrossReferencesTest.chain("20", false, "CRM", "ACC-1", "CRM|ACC-1"));
		final ObjectNode json = CrossReferencesTest.lineage("10", sources, MergeInfo.present("GOLDEN", "1", "10", false));

		final JsonNode xrefs = CrossReferences.project(json).get("xrefs");
		CrossReferencesTest.check("first is 30", "30".equals(xrefs.get(0).get("masterKey").asText()));
		CrossReferencesTest.check("second is 10", "10".equals(xrefs.get(1).get("masterKey").asText()));
		CrossReferencesTest.check("third is 20", "20".equals(xrefs.get(2).get("masterKey").asText()));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
