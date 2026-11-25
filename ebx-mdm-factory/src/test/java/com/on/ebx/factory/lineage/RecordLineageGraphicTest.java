package com.on.ebx.factory.lineage;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.lineage.RecordLineage.FieldContribution;
import com.on.ebx.factory.lineage.RecordLineage.FieldInput;
import com.on.ebx.factory.lineage.RecordLineage.FieldValue;
import com.on.ebx.factory.lineage.RecordLineage.Input;
import com.on.ebx.factory.lineage.RecordLineage.MergeInfo;
import com.on.ebx.factory.lineage.RecordLineage.SourceChain;

/**
 * JUnit-free, like the sibling tests: run the main, a failure throws. Exercises {@link RecordLineageGraphic}
 * from the SAME {@code RecordLineage.assemble} output the real read side produces - never a hand-written
 * JSON fixture (same discipline as {@code RecordLineagePageTest}, so a fixture can never drift from what
 * {@code RecordLineage} actually emits).
 */
public final class RecordLineageGraphicTest
{
	public static void main(final String[] pArgs)
	{
		RecordLineageGraphicTest.oneSource_buildsCleanly();
		RecordLineageGraphicTest.oneSource_singleSourceFlagAndNote();
		RecordLineageGraphicTest.oneRestrictedSource_singleSourceFlagButNoNote();
		RecordLineageGraphicTest.twoSources_noSingleSourceFlagOrNote();
		RecordLineageGraphicTest.fieldLabel_passesThroughWhenPresent_absentOtherwise();
		RecordLineageGraphicTest.twoSources_bothCarried();
		RecordLineageGraphicTest.fourSources_matchesMockupCount();
		RecordLineageGraphicTest.nineSources_visibleCappedAtEight();
		RecordLineageGraphicTest.restrictedSourceNeverLeaksBeyondSystemName();
		RecordLineageGraphicTest.restrictedContributionRendersLockState();
		RecordLineageGraphicTest.compositionFallsBackToNeutralSummaryWithoutAKnownAnswer();
		RecordLineageGraphicTest.compositionUsesKnownAnswersWhenPresent();
		RecordLineageGraphicTest.timelineNeverInventsADateBeyondReceived();
		RecordLineageGraphicTest.timelinePendingWhenMergeAbsent();
		RecordLineageGraphicTest.labelsResolveInBothLocalesNeverARawKey();
		RecordLineageGraphicTest.goldenRecordLookedUpDirectly_neverShowsMergedWording();
		RecordLineageGraphicTest.colorAssignment_usesProjectSourceOrderWhenPresent();
		RecordLineageGraphicTest.colorAssignment_fallsBackAlphabeticallyWithoutOrder();
		RecordLineageGraphicTest.colorAssignment_sameAcrossSourcesAndFieldContributions();
		RecordLineageGraphicTest.stepShortWords_knownKindShort_unknownFallsBackToKind();
		RecordLineageGraphicTest.survivorshipRuleOnlyShownWhenKnown();
		RecordLineageGraphicTest.masterLabelPassesThroughWhenPresent_absentOtherwise();
		RecordLineageGraphicTest.formatInstant_fixedLocaleAndZone_englishAndFrench();
		RecordLineageGraphicTest.formatInstant_unparsableFallsBackToRawText();
		RecordLineageGraphicTest.sourceCarriesFormattedReceivedAtDisplay();
		RecordLineageGraphicTest.styleIsDashedPastThePaletteSize();
		RecordLineageGraphicTest.suspectLane_presentWithKindSuspectAndDashedStyle();
		RecordLineageGraphicTest.suspectLane_scriptPayloadInSourceSystem_neverExecutedJustCarried();
		RecordLineageGraphicTest.suspectLane_cleaningStepKindsFromSourceSystem_notFromContributions();
		RecordLineageGraphicTest.contributionState_equivalentForCaseOnlyDifference();
		RecordLineageGraphicTest.contributionState_diffStaysStrictForAGenuineDifference();
		RecordLineageGraphicTest.contributionState_transformedForSingleSourceRegardlessOfHowItDiffers();
		RecordLineageGraphicTest.composition_singleSource_differentCountAlwaysZero();
		RecordLineageGraphicTest.tooltipLabels_resolveInBothLocales();
		System.out.println("RecordLineageGraphicTest OK");
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	private static SourceChain chain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final Date pReceivedAt)
	{
		return new SourceChain(pMasterKey, pGolden, pSourceSystem, false, "SRC-" + pMasterKey, "Companies_Landing", "SUPPLIER", "SRC-" + pMasterKey, "L1", "STAGED",
			pReceivedAt, "Company", pSourceSystem + "|SRC-" + pMasterKey);
	}

	private static SourceChain suspectLane(final String pMasterKey, final String pSourceSystem)
	{
		return new SourceChain(pMasterKey, false, pSourceSystem, false, "SRC-" + pMasterKey, "Companies_Landing", "ACCOUNT", "SRC-" + pMasterKey, "L1", "STAGED",
			new Date(0L), "Company", pSourceSystem + "|SRC-" + pMasterKey, null, null, null, true);
	}

	private static ObjectNode lineageOf(final List<SourceChain> pSources, final List<FieldInput> pFields, final MergeInfo pMerge, final Map<String, FieldValue> pRecordFields)
	{
		return RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", pSources.isEmpty() ? "1" : pSources.get(0).masterKey,
			pRecordFields == null ? new LinkedHashMap<String, FieldValue>() : pRecordFields, pSources, pFields, pMerge, null));
	}

	/** Same as {@link #lineageOf}, with {@code pSuspectSources} - the journey-only lanes {@code RecordLineageGraphic} draws in addition to {@code pSources}. */
	private static ObjectNode lineageOfWithSuspects(final List<SourceChain> pSources, final MergeInfo pMerge, final List<SourceChain> pSuspectSources)
	{
		return RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", pSources.isEmpty() ? "1" : pSources.get(0).masterKey,
			new LinkedHashMap<String, FieldValue>(), pSources, Collections.<FieldInput>emptyList(), pMerge, null, null, null, pSuspectSources));
	}

	// ------------------------------------------------------------------------------------------------------ tests

	private static void oneSource_buildsCleanly()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		RecordLineageGraphicTest.check("one source", graphic.get("sourceCount").asInt() == 1);
		RecordLineageGraphicTest.check("one source visible", graphic.get("sources").size() == 1);
		RecordLineageGraphicTest.check("visibleSourceCount is 1", graphic.get("visibleSourceCount").asInt() == 1);
		RecordLineageGraphicTest.check("merge present is false", !graphic.get("merge").get("present").asBoolean());
	}

	// ------------------------------------------------------------------------- single-source rule (2026-09-22)

	/** Exactly one contributing source: {@code singleSource} is true and {@code singleSourceNote} names the system, its staging table and key - coordinator review, 2026-09-22, live record Company 183 (BPCE, ERP only). */
	private static void oneSource_singleSourceFlagAndNote()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		RecordLineageGraphicTest.check("singleSource is true", graphic.get("singleSource").asBoolean());
		final String note = graphic.get("singleSourceNote").asText();
		RecordLineageGraphicTest.check("the note names the system", note.contains("ERP"));
		RecordLineageGraphicTest.check("the note names the staging table", note.contains("Company"));
		RecordLineageGraphicTest.check("the note names the staging key", note.contains("ERP|SRC-1"));
	}

	/** The one contributing source is itself restricted: {@code singleSource} still reports true, but no note is fabricated - there is nothing non-sensitive left to name (no staging table/key, CLAUDE.md's own redaction discipline). */
	private static void oneRestrictedSource_singleSourceFlagButNoNote()
	{
		final SourceChain restricted = new SourceChain("1", true, "CRM", true, "SHOULD-NOT-LEAK", "Companies_Landing", "ACCOUNT", "SHOULD-NOT-LEAK", "L1", "STAGED",
			new Date(0L), "Company", "SHOULD-NOT-LEAK-EITHER");
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(RecordLineage.listOf(restricted), Collections.<FieldInput>emptyList(),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		RecordLineageGraphicTest.check("singleSource is still true", graphic.get("singleSource").asBoolean());
		RecordLineageGraphicTest.check("no note fabricated for a restricted single source", graphic.get("singleSourceNote") == null);
	}

	/** Two contributing sources: neither the flag nor the note fires - the deduced badge/connector stay meaningful with a real choice among sources. */
	private static void twoSources_noSingleSourceFlagOrNote()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)),
			RecordLineageGraphicTest.chain("2", false, "CRM", new Date(1000L)));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.present("MERGED", "1", "1", false), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		RecordLineageGraphicTest.check("singleSource is false with two sources", !graphic.get("singleSource").asBoolean());
		RecordLineageGraphicTest.check("no note with two sources", graphic.get("singleSourceNote") == null);
	}

	/** {@code fieldLabel} passes through onto the graphic's own field node when {@code RecordLineage} carries one, and is absent (never the bare name duplicated) otherwise - the client script itself falls back to {@code field}. */
	private static void fieldLabel_passesThroughWhenPresent_absentOtherwise()
	{
		final FieldInput withLabel = new FieldInput("vatNo", Collections.<FieldContribution>emptyList(), null, null, "VAT number");
		final ObjectNode lineageWithLabel = RecordLineageGraphicTest.lineageOf(Collections.<SourceChain>emptyList(), RecordLineage.listOf(withLabel),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphicWithLabel = RecordLineageGraphic.build(lineageWithLabel, Locale.ENGLISH);
		RecordLineageGraphicTest.check("the label passes through", "VAT number".equals(graphicWithLabel.get("fields").get(0).get("fieldLabel").asText()));

		final FieldInput withoutLabel = new FieldInput("vatNo", Collections.<FieldContribution>emptyList());
		final ObjectNode lineageWithoutLabel = RecordLineageGraphicTest.lineageOf(Collections.<SourceChain>emptyList(), RecordLineage.listOf(withoutLabel),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphicWithoutLabel = RecordLineageGraphic.build(lineageWithoutLabel, Locale.ENGLISH);
		RecordLineageGraphicTest.check("no fabricated label when none was sent", graphicWithoutLabel.get("fields").get(0).get("fieldLabel") == null);
	}

	private static void twoSources_bothCarried()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)),
			RecordLineageGraphicTest.chain("2", false, "CRM", new Date(1000L)));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.present("MERGED", "42", "1", false), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		RecordLineageGraphicTest.check("two sources carried", graphic.get("sources").size() == 2);
		RecordLineageGraphicTest.check("sorted oldest first", "ERP".equals(graphic.get("sources").get(0).get("system").asText()));
		RecordLineageGraphicTest.check("each source has a colour", graphic.get("sources").get(0).get("color").asText().startsWith("#"));
	}

	private static void fourSources_matchesMockupCount()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)),
			RecordLineageGraphicTest.chain("2", false, "CRM", new Date(1000L)), RecordLineageGraphicTest.chain("3", false, "Salesforce", new Date(2000L)),
			RecordLineageGraphicTest.chain("4", false, "SAP", new Date(3000L)));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.present("GOLDEN", "118", "1", false), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		RecordLineageGraphicTest.check("four sources, the mockup's own case", graphic.get("sourceCount").asInt() == 4);
		RecordLineageGraphicTest.check("all four visible (below the cap of 8)", graphic.get("visibleSourceCount").asInt() == 4);
		RecordLineageGraphicTest.check("record count on the ring equals the source count", graphic.get("merge").get("recordCount").asInt() == 4);
	}

	/** Above 8 contributing sources: the first 8 (by reception date) are marked visible, the rest are still present in `sources` for the "N more" reveal to use (task brief: "many sources ... above 8 ... show the first 8 ... and a 'N more sources' control that reveals the rest - all are already in the bounded read model"). */
	private static void nineSources_visibleCappedAtEight()
	{
		final List<SourceChain> sources = new ArrayList<SourceChain>();
		for (int i = 1; i <= 9; i++)
		{
			sources.add(RecordLineageGraphicTest.chain(String.valueOf(i), i == 1, "SYS" + i, new Date(i * 1000L)));
		}
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.present("GOLDEN", "118", "1", false), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		RecordLineageGraphicTest.check("nine sources total", graphic.get("sourceCount").asInt() == 9);
		RecordLineageGraphicTest.check("all nine still present for the reveal control", graphic.get("sources").size() == 9);
		RecordLineageGraphicTest.check("visibleSourceCount capped at 8", graphic.get("visibleSourceCount").asInt() == 8);
	}

	/** A restricted source in the graphic JSON carries only masterKey/golden/system/color/restricted - no sourceId, no landing/staging, no cleaning steps - even though it is deliberately given real values below, proving RecordLineageGraphic redacts on its own rather than trusting the input shape. */
	private static void restrictedSourceNeverLeaksBeyondSystemName()
	{
		final SourceChain restricted = new SourceChain("2", false, "CRM", true, "SHOULD-NOT-LEAK", "Companies_Landing", "ACCOUNT", "SHOULD-NOT-LEAK", "L1", "STAGED",
			new Date(0L), "Company", "SHOULD-NOT-LEAK-EITHER");
		final List<SourceChain> sources = Arrays.asList(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)), restricted);
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.present("MERGED", "1", "1", false), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		JsonNode restrictedNode = null;
		for (final JsonNode node : graphic.get("sources"))
		{
			if (node.get("restricted").asBoolean())
			{
				restrictedNode = node;
			}
		}
		RecordLineageGraphicTest.check("a restricted source is present", restrictedNode != null);
		RecordLineageGraphicTest.check("only masterKey/golden/system/color/style/restricted - six keys", restrictedNode.size() == 6);
		RecordLineageGraphicTest.check("sourceId never leaks", restrictedNode.get("sourceId") == null);
		RecordLineageGraphicTest.check("no landingUrl/stagingUrl leak", restrictedNode.get("landingUrl") == null && restrictedNode.get("stagingUrl") == null);
		RecordLineageGraphicTest.check("no cleaningSteps leak", restrictedNode.get("cleaningSteps") == null);
	}

	private static void restrictedContributionRendersLockState()
	{
		final FieldContribution restricted = new FieldContribution("2", "CRM", true, "SECRET", "should-not-leak-raw", "should-not-leak-staged",
			Collections.singletonList("TRIM"));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(Collections.<SourceChain>emptyList(),
			RecordLineage.listOf(new FieldInput("vatNo", Collections.singletonList(restricted))), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		final JsonNode contribution = graphic.get("fields").get(0).get("contributions").get(0);
		RecordLineageGraphicTest.check("state is LOCK", "LOCK".equals(contribution.get("state").asText()));
		RecordLineageGraphicTest.check("no value leaks", contribution.get("value") == null);
		RecordLineageGraphicTest.check("no rawValue leaks", contribution.get("rawValue") == null);
	}

	private static void compositionFallsBackToNeutralSummaryWithoutAKnownAnswer()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)),
			RecordLineageGraphicTest.chain("2", false, "SAP", new Date(1000L)));
		final List<FieldContribution> nameContributions = Arrays.asList(
			RecordLineageGraphicTest.eqContribution("1", "ERP", "BPCE SA"), RecordLineageGraphicTest.eqContribution("2", "SAP", "BPCE"));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("name", nameContributions));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, fields, MergeInfo.present("GOLDEN", "118", "1", false),
			RecordLineageGraphicTest.recordFields("name", "BPCE SA"));
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		final JsonNode composition = graphic.get("composition");
		RecordLineageGraphicTest.check("no KNOWN answer today", !composition.get("known").asBoolean());
		RecordLineageGraphicTest.check("sourceCount is the neutral fallback's own field", composition.get("sourceCount").asInt() == 2);
		RecordLineageGraphicTest.check("no bySource breakdown without a known answer", composition.get("bySource") == null);
	}

	/** Once a real KNOWN retained answer exists (the FieldMergeLineageSource seam), the composition bar switches to the per-source breakdown - deliberately NEVER built from a merely DEDUCED answer (class Javadoc: deduction is a field-level inference, not strong enough evidence for a record-wide "who supplied what" claim). */
	private static void compositionUsesKnownAnswersWhenPresent()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)),
			RecordLineageGraphicTest.chain("2", false, "SAP", new Date(1000L)));
		final List<FieldContribution> nameContributions = Arrays.asList(
			RecordLineageGraphicTest.eqContribution("1", "ERP", "BPCE SA"), RecordLineageGraphicTest.eqContribution("2", "SAP", "BPCE SA"));
		// both agree with golden - the deduced rule alone would retain neither - but a KNOWN answer names "2"
		final FieldInput field = new FieldInput("name", nameContributions, "2");
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, RecordLineage.listOf(field), MergeInfo.present("GOLDEN", "118", "1", false),
			RecordLineageGraphicTest.recordFields("name", "BPCE SA"));
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		final JsonNode composition = graphic.get("composition");
		RecordLineageGraphicTest.check("a known answer is now present", composition.get("known").asBoolean());
		RecordLineageGraphicTest.check("exactly one row in the breakdown (SAP)", composition.get("bySource").size() == 1);
		RecordLineageGraphicTest.check("SAP is credited with the one known field", "SAP".equals(composition.get("bySource").get(0).get("system").asText())
			&& composition.get("bySource").get(0).get("count").asInt() == 1);
	}

	/** The timeline never carries a fabricated date beyond the real per-source `receivedAt`: a CLEANED milestone follows, dateless, and nothing about match/merge appears when there IS no merge yet. */
	private static void timelineNeverInventsADateBeyondReceived()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)),
			RecordLineageGraphicTest.chain("2", false, "CRM", new Date(1000L)));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		final JsonNode timeline = graphic.get("timeline");
		RecordLineageGraphicTest.check("two RECEIVED events + one CLEANED + one PENDING", timeline.size() == 4);
		RecordLineageGraphicTest.check("first two are RECEIVED with real dates", "RECEIVED".equals(timeline.get(0).get("kind").asText()) && timeline.get(0).hasNonNull("at"));
		RecordLineageGraphicTest.check("CLEANED carries no date", "CLEANED".equals(timeline.get(2).get("kind").asText()) && !timeline.get(2).get("dateKnown").asBoolean());
		RecordLineageGraphicTest.check("no MATCH event fabricated without a merge", timeline.get(3).get("kind").asText().equals("PENDING"));
	}

	private static void timelinePendingWhenMergeAbsent()
	{
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(),
			MergeInfo.absent(MergeInfo.NO_METADATA), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);
		final JsonNode last = graphic.get("timeline").get(graphic.get("timeline").size() - 1);
		RecordLineageGraphicTest.check("a dateless PENDING marker with the real reason", "PENDING".equals(last.get("kind").asText())
			&& last.get("reasonWords").asText().contains("matching metadata"));
	}

	private static void labelsResolveInBothLocalesNeverARawKey()
	{
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode english = RecordLineageGraphic.build(lineage, Locale.ENGLISH);
		final ObjectNode french = RecordLineageGraphic.build(lineage, Locale.FRENCH);

		RecordLineageGraphicTest.check("english open label", "Open in EBX".equals(english.get("labels").get("open").asText()));
		RecordLineageGraphicTest.check("french open label", "Ouvrir dans EBX".equals(french.get("labels").get("open").asText()));
		// the id chip's own tooltip (task brief, 2026-09-22) - a SHARED message key ("lineage.id.tooltip",
		// not under the "lineage.graphic." prefix every other label above uses), so worth its own explicit
		// check rather than only the generic "no raw key leaks" loop below.
		RecordLineageGraphicTest.check("english id tooltip label", "EBX record id".equals(english.get("labels").get("idTooltip").asText()));
		RecordLineageGraphicTest.check("french id tooltip label", "Identifiant d'enregistrement EBX".equals(french.get("labels").get("idTooltip").asText()));
		for (final JsonNode label : english.get("labels"))
		{
			RecordLineageGraphicTest.check("no raw lineage.* key leaks in a label", !label.asText().startsWith("lineage."));
		}
	}

	/** Looking up the golden record DIRECTLY (masterKey equals the golden entry's own key) must never show the "merged into another record" wording - a real fixture bug found in this task's own preview harness, coordinator review 2026-09-22: that combination (golden:true alongside state MERGED) is not something the real read side produces (state describes the record actually looked up, and MameRecordState.read of the golden itself reports GOLDEN, not MERGED), but is worth locking in as a test rather than trusting that invariant to hold by convention alone. */
	private static void goldenRecordLookedUpDirectly_neverShowsMergedWording()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.present("GOLDEN", "118", "1", false), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		RecordLineageGraphicTest.check("masterKey is the golden entry's own key", "1".equals(graphic.get("masterKey").asText())
			&& graphic.get("sources").get(0).get("golden").asBoolean() && "1".equals(graphic.get("sources").get(0).get("masterKey").asText()));
		final String stateWords = graphic.get("merge").get("stateWords").asText();
		RecordLineageGraphicTest.check("state words say Golden record", stateWords.toLowerCase(java.util.Locale.ROOT).contains("golden"));
		RecordLineageGraphicTest.check("state words never say merged when looking at the golden itself", !stateWords.toLowerCase(java.util.Locale.ROOT).contains("merged"));
	}

	private static SourceChain chainWithOrder(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final Integer pOrder)
	{
		return new SourceChain(pMasterKey, pGolden, pSourceSystem, false, "SRC-" + pMasterKey, "Companies_Landing", "SUPPLIER", "SRC-" + pMasterKey, "L1", "STAGED",
			new Date(0L), "Company", pSourceSystem + "|SRC-" + pMasterKey, null, null, pOrder);
	}

	/** The coordinator's own explicit first four colours, assigned in the project's declared order (ProjectSource.trustRank -> SourceChain#sourceOrder), regardless of what order the sources happen to appear in the JSON. */
	private static void colorAssignment_usesProjectSourceOrderWhenPresent()
	{
		// declared order SAP(0), ERP(1), CRM(2), Salesforce(3) - deliberately NOT alphabetical and NOT the
		// list order below, to prove the ordering comes from sourceOrder, not from either of those.
		final List<SourceChain> sources = Arrays.asList(RecordLineageGraphicTest.chainWithOrder("2", false, "ERP", Integer.valueOf(1)),
			RecordLineageGraphicTest.chainWithOrder("1", true, "SAP", Integer.valueOf(0)), RecordLineageGraphicTest.chainWithOrder("3", false, "CRM", Integer.valueOf(2)),
			RecordLineageGraphicTest.chainWithOrder("4", false, "Salesforce", Integer.valueOf(3)));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		final Map<String, String> colorBySystem = new LinkedHashMap<String, String>();
		for (final JsonNode s : graphic.get("sources"))
		{
			colorBySystem.put(s.get("system").asText(), s.get("color").asText());
		}
		RecordLineageGraphicTest.check("position 0 (SAP) is blue", "#2f6fd0".equals(colorBySystem.get("SAP")));
		RecordLineageGraphicTest.check("position 1 (ERP) is teal", "#0e9384".equals(colorBySystem.get("ERP")));
		RecordLineageGraphicTest.check("position 2 (CRM) is amber", "#c77700".equals(colorBySystem.get("CRM")));
		RecordLineageGraphicTest.check("position 3 (Salesforce) is rose", "#b4236c".equals(colorBySystem.get("Salesforce")));
	}

	/** No declared order at all: falls back to alphabetical by system name (task brief's own instruction), never a hash. */
	private static void colorAssignment_fallsBackAlphabeticallyWithoutOrder()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineageGraphicTest.chain("1", true, "SAP", new Date(0L)),
			RecordLineageGraphicTest.chain("2", false, "ERP", new Date(1000L)));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		final Map<String, String> colorBySystem = new LinkedHashMap<String, String>();
		for (final JsonNode s : graphic.get("sources"))
		{
			colorBySystem.put(s.get("system").asText(), s.get("color").asText());
		}
		// alphabetically, ERP sorts before SAP - so ERP gets position 0 (blue) even though it is listed second above
		RecordLineageGraphicTest.check("ERP (alphabetically first) is blue", "#2f6fd0".equals(colorBySystem.get("ERP")));
		RecordLineageGraphicTest.check("SAP (alphabetically second) is teal", "#0e9384".equals(colorBySystem.get("SAP")));
	}

	/** The SAME colour for a system in `sources[]` and in every `fields[].contributions[]` entry - one shared assignment, never two independent computations that could disagree. */
	private static void colorAssignment_sameAcrossSourcesAndFieldContributions()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineageGraphicTest.chainWithOrder("1", true, "SAP", Integer.valueOf(5)),
			RecordLineageGraphicTest.chainWithOrder("2", false, "ERP", Integer.valueOf(1)));
		final List<FieldContribution> contributions = Arrays.asList(RecordLineageGraphicTest.eqContribution("1", "SAP", "x"),
			RecordLineageGraphicTest.eqContribution("2", "ERP", "y"));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, RecordLineage.listOf(new FieldInput("f", contributions)),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		String sapSourceColor = null;
		for (final JsonNode s : graphic.get("sources"))
		{
			if ("SAP".equals(s.get("system").asText()))
			{
				sapSourceColor = s.get("color").asText();
			}
		}
		String sapContributionColor = null;
		for (final JsonNode c : graphic.get("fields").get(0).get("contributions"))
		{
			if ("SAP".equals(c.get("system").asText()))
			{
				sapContributionColor = c.get("color").asText();
			}
		}
		RecordLineageGraphicTest.check("SAP's colour agrees between sources[] and fields[].contributions[]", sapSourceColor != null && sapSourceColor.equals(sapContributionColor));
	}

	/** A known cleaning-step kind gets its short chip label; an unknown one falls back to the raw kind rather than a blank chip. */
	private static void stepShortWords_knownKindShort_unknownFallsBackToKind()
	{
		RecordLineageGraphicTest.check("TRIM has a short label", "trim".equals(RecordLineageGraphic.stepShortWords("TRIM", Locale.ENGLISH)));
		RecordLineageGraphicTest.check("COUNTRY_TO_ISO2 has a short label", RecordLineageGraphic.stepShortWords("COUNTRY_TO_ISO2", Locale.ENGLISH).toLowerCase(java.util.Locale.ROOT).contains("iso"));
		RecordLineageGraphicTest.check("an unknown kind falls back to the raw kind", "SOME_FUTURE_KIND".equals(RecordLineageGraphic.stepShortWords("SOME_FUTURE_KIND", Locale.ENGLISH)));
		RecordLineageGraphicTest.check("French has its own short label for TRIM", "espaces".equals(RecordLineageGraphic.stepShortWords("TRIM", Locale.FRENCH)));
	}

	/** The survivorship-rule sentence is only ever emitted alongside a KNOWN retained cell - never a merely DEDUCED one, and never fabricated when absent even for a KNOWN answer. */
	private static void survivorshipRuleOnlyShownWhenKnown()
	{
		final List<FieldContribution> contributions = Arrays.asList(RecordLineageGraphicTest.eqContribution("1", "ERP", "BPCE SA"),
			RecordLineageGraphicTest.eqContribution("2", "SAP", "BPCE SA"));
		final FieldInput knownWithRule = new FieldInput("name", contributions, "2", "most recent update");
		final ObjectNode withRule = RecordLineageGraphicTest.lineageOf(Collections.<SourceChain>emptyList(), RecordLineage.listOf(knownWithRule),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), RecordLineageGraphicTest.recordFields("name", "BPCE SA"));
		final ObjectNode graphicWithRule = RecordLineageGraphic.build(withRule, Locale.ENGLISH);
		RecordLineageGraphicTest.check("the rule is shown for a KNOWN retained field", "most recent update".equals(graphicWithRule.get("fields").get(0).get("survivorshipRule").asText()));

		// the SAME unique-match shape, but DEDUCED (no knownRetainedMasterKey) - never shows a rule, even
		// though nothing stops a caller from trying to smuggle one in via a field the deduced path ignores.
		final FieldInput deduced = new FieldInput("name",
			Arrays.asList(RecordLineageGraphicTest.eqContribution("1", "ERP", "BPCE SA"), RecordLineageGraphicTest.eqContribution("2", "SAP", "something else")));
		final ObjectNode withoutRule = RecordLineageGraphicTest.lineageOf(Collections.<SourceChain>emptyList(), RecordLineage.listOf(deduced),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), RecordLineageGraphicTest.recordFields("name", "BPCE SA"));
		final ObjectNode graphicWithoutRule = RecordLineageGraphic.build(withoutRule, Locale.ENGLISH);
		RecordLineageGraphicTest.check("no rule for a merely deduced retained cell", graphicWithoutRule.get("fields").get(0).get("survivorshipRule") == null);
	}

	/** The master record's own NAME (coordinator review, 2026-09-22) passes through verbatim when the reader supplied one, and is simply absent (the client falls back to the key) when it did not - never fabricated. */
	private static void masterLabelPassesThroughWhenPresent_absentOtherwise()
	{
		final ObjectNode withLabel = RecordLineage.assemble(new RecordLineage.Input("Companies", "Company", "/root/Company", "1",
			new LinkedHashMap<String, FieldValue>(), Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET),
			null, null, "BPCE SA"));
		final ObjectNode graphicWithLabel = RecordLineageGraphic.build(withLabel, Locale.ENGLISH);
		RecordLineageGraphicTest.check("the label passes through", "BPCE SA".equals(graphicWithLabel.get("masterLabel").asText()));

		final ObjectNode withoutLabel = RecordLineageGraphicTest.lineageOf(Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphicWithoutLabel = RecordLineageGraphic.build(withoutLabel, Locale.ENGLISH);
		RecordLineageGraphicTest.check("no label fabricated when the reader supplied none", graphicWithoutLabel.get("masterLabel") == null);
	}

	/** `formatInstant` is pure: fixed Locale/ZoneId in, the SAME formatted text out, every time - the exact combination the coordinator asked to test (2026-09-22). */
	private static void formatInstant_fixedLocaleAndZone_englishAndFrench()
	{
		final String iso = "2026-09-21T21:14:00Z";
		final ZoneId paris = ZoneId.of("Europe/Paris");
		final String english = RecordLineageGraphic.formatInstant(iso, Locale.ENGLISH, paris);
		final String french = RecordLineageGraphic.formatInstant(iso, Locale.FRANCE, paris);
		RecordLineageGraphicTest.check("the raw ISO instant never reaches the page as-is", !english.equals(iso) && !french.equals(iso));
		RecordLineageGraphicTest.check("english and french format the same instant differently", !english.equals(french));
		RecordLineageGraphicTest.check("repeated calls with the same locale/zone give the exact same text", english.equals(RecordLineageGraphic.formatInstant(iso, Locale.ENGLISH, paris)));
		// Europe/Paris is UTC+2 in September (CEST) - 21:14 UTC is 23:14 local, so "23" (or "11 PM") must appear
		RecordLineageGraphicTest.check("the zone conversion actually happened (21:14 UTC -> 23:14 in Europe/Paris)",
			english.contains("23") || english.toLowerCase(Locale.ROOT).contains("11:14"));
	}

	/** Never an exception, never a blank result - an unparsable instant string falls back to being shown as-is. */
	private static void formatInstant_unparsableFallsBackToRawText()
	{
		RecordLineageGraphicTest.check("null in, null out, never a throw", RecordLineageGraphic.formatInstant(null, Locale.ENGLISH, ZoneId.of("UTC")) == null);
		RecordLineageGraphicTest.check("garbage text falls back to itself rather than throwing",
			"not-a-date".equals(RecordLineageGraphic.formatInstant("not-a-date", Locale.ENGLISH, ZoneId.of("UTC"))));
	}

	/** The landing card's own third line (coordinator review, 2026-09-22: "reçue le 21/09 - 21 h 14", the mockup's own line) needs a formatted, not raw, display string alongside the raw ISO instant (kept for anything that still wants to sort/compare it). */
	private static void sourceCarriesFormattedReceivedAtDisplay()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageGraphicTest.chain("1", true, "ERP", new java.util.Date(0L)));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH, ZoneId.of("UTC"));
		final JsonNode source = graphic.get("sources").get(0);
		RecordLineageGraphicTest.check("the raw ISO instant is still carried", source.get("receivedAt").asText().startsWith("1970-01-01"));
		RecordLineageGraphicTest.check("a formatted display string is carried alongside it, and differs from the raw one",
			source.hasNonNull("receivedAtDisplay") && !source.get("receivedAtDisplay").asText().equals(source.get("receivedAt").asText()));
	}

	/** Past the palette's own 12 entries, a lane's assigned style switches to "dashed" (coordinator review, 2026-09-22) - checked here through the real assignment path (sourceOrder-driven), not just SourceColorPaletteTest's own direct palette test. */
	private static void styleIsDashedPastThePaletteSize()
	{
		final List<SourceChain> sources = new ArrayList<SourceChain>();
		for (int i = 0; i < 13; i++)
		{
			sources.add(RecordLineageGraphicTest.chainWithOrder(String.valueOf(i), i == 0, "SYS" + String.format("%02d", i), Integer.valueOf(i)));
		}
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);
		String firstStyle = null;
		String thirteenthStyle = null;
		String firstColor = null;
		String thirteenthColor = null;
		for (final JsonNode s : graphic.get("sources"))
		{
			if ("SYS00".equals(s.get("system").asText()))
			{
				firstStyle = s.get("style").asText();
				firstColor = s.get("color").asText();
			}
			if ("SYS12".equals(s.get("system").asText()))
			{
				thirteenthStyle = s.get("style").asText();
				thirteenthColor = s.get("color").asText();
			}
		}
		RecordLineageGraphicTest.check("the first (position 0) lane is solid", "solid".equals(firstStyle));
		RecordLineageGraphicTest.check("the 13th (position 12) lane is dashed", "dashed".equals(thirteenthStyle));
		RecordLineageGraphicTest.check("the 13th lane reuses position 0's colour", firstColor.equals(thirteenthColor));
	}

	/** A pending suspect (task brief, 2026-09-22: "the PARCOURS must draw a lane for each SUSPECT record too") lands in its OWN `suspectLanes` array, marked `kind:"suspect"`, never mixed into `sources[]`/`visibleSourceCount` (that budget is for confirmed contributors only). */
	private static void suspectLane_presentWithKindSuspectAndDashedStyle()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)));
		final List<SourceChain> suspects = RecordLineage.listOf(RecordLineageGraphicTest.suspectLane("141", "CRM"));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOfWithSuspects(sources, MergeInfo.present("GOLDEN", "118", "1", false), suspects);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		RecordLineageGraphicTest.check("one confirmed source, still just one", graphic.get("sources").size() == 1);
		RecordLineageGraphicTest.check("visibleSourceCount only counts confirmed sources", graphic.get("visibleSourceCount").asInt() == 1);
		RecordLineageGraphicTest.check("one suspect lane present", graphic.get("suspectLanes").size() == 1);
		final JsonNode lane = graphic.get("suspectLanes").get(0);
		RecordLineageGraphicTest.check("the suspect lane is marked kind:suspect", "suspect".equals(lane.get("kind").asText()));
		RecordLineageGraphicTest.check("the suspect lane carries its own source system", "CRM".equals(lane.get("system").asText()));
		RecordLineageGraphicTest.check("the suspect lane has a colour, like any other lane", lane.get("color").asText().startsWith("#"));
	}

	/** A `"><script>` payload in a suspect lane's own source system passes through the graphic JSON as an inert string - Jackson never lets a JSON string value execute as markup on its own; the actual HTML/JS-embedding escape is `RecordLineagePage`'s own job (untouched this lot, covered by its own tests) - this test only proves the pure JSON-building layer never unwraps, decodes or otherwise special-cases the payload. */
	private static void suspectLane_scriptPayloadInSourceSystem_neverExecutedJustCarried()
	{
		final String payload = "\"><script>alert(1)</script>";
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)));
		final List<SourceChain> suspects = RecordLineage.listOf(RecordLineageGraphicTest.suspectLane("141", payload));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOfWithSuspects(sources, MergeInfo.present("GOLDEN", "118", "1", false), suspects);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		final JsonNode lane = graphic.get("suspectLanes").get(0);
		RecordLineageGraphicTest.check("the payload is carried through byte-for-byte as a plain JSON string value", payload.equals(lane.get("system").asText()));
		RecordLineageGraphicTest.check("still marked kind:suspect regardless of the payload", "suspect".equals(lane.get("kind").asText()));
		RecordLineageGraphicTest.check("the confirmed source lane is unaffected", "ERP".equals(graphic.get("sources").get(0).get("system").asText()));
	}

	/**
	 * Fix, 2026-09-22 (task brief item D): a suspect lane's own {@code cleaningStepKinds}
	 * ({@code SourceChain#cleaningStepKinds} - the entity's accepted mapping steps for the suspect's OWN
	 * source system, computed by {@code ebx.RecordLineageReader} since a suspect has no field CONTRIBUTIONS
	 * yet to derive them from) is used verbatim by {@link RecordLineageGraphic#buildSource}, in PLACE of the
	 * usual per-contribution scan - a suspect with no field contributions at all (the realistic case: it is
	 * not yet a confirmed source) still shows its real cleaning steps, not a permanent "0 steps".
	 */
	private static void suspectLane_cleaningStepKindsFromSourceSystem_notFromContributions()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)));
		final SourceChain plain = RecordLineageGraphicTest.suspectLane("141", "CRM");
		final SourceChain withKinds = new SourceChain(plain.masterKey, plain.golden, plain.sourceSystem, plain.restricted, plain.sourceId, plain.landingDataset,
			plain.landingTable, plain.landingKey, plain.loadId, plain.landingStatus, plain.receivedAt, plain.stagingTable, plain.stagingKey, plain.landingUrl,
			plain.stagingUrl, plain.sourceOrder, plain.suspect, Arrays.asList("TRIM", "COUNTRY_TO_ISO2"));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOfWithSuspects(sources, MergeInfo.present("GOLDEN", "118", "1", false),
			RecordLineage.listOf(withKinds));
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		final JsonNode lane = graphic.get("suspectLanes").get(0);
		final JsonNode steps = lane.get("cleaningSteps");
		RecordLineageGraphicTest.check("the suspect lane shows the REAL step count from cleaningStepKinds, not 0", steps.size() == 2);
		RecordLineageGraphicTest.check("first step kind carried through", "TRIM".equals(steps.get(0).get("kind").asText()));
		RecordLineageGraphicTest.check("second step kind carried through", "COUNTRY_TO_ISO2".equals(steps.get(1).get("kind").asText()));
		RecordLineageGraphicTest.check("short words resolved for a known kind", "trim".equals(steps.get(0).get("shortWords").asText()));

		// a PLAIN contributing source (no cleaningStepKinds set) must keep the OLD, contribution-based
		// computation unchanged - this fix only ever changes behaviour for a suspect lane.
		final JsonNode confirmed = graphic.get("sources").get(0);
		RecordLineageGraphicTest.check("a plain contributing source is unaffected by this fix", confirmed.get("cleaningSteps").size() == 0);
	}

	// ------------------------------------------------------------------- EQUIVALENT/TRANSFORMED states (2026-09-22)

	/**
	 * A case-only difference from the golden value, with MORE than one contributing source: the disagreeing
	 * contribution is EQUIVALENT (a "&asymp;" badge, no strikethrough), never a false DIFF. THREE sources
	 * (not two) so the two EXACT matches are ambiguous for the deduced-retained rule (RecordLineage#assemble,
	 * &sect;2's own "two matches is ambiguous, never guessed") and stay plain EQ, rather than one of them
	 * incidentally winning the RETAINED badge and hiding the EQ/EQUIVALENT distinction this test is about.
	 */
	private static void contributionState_equivalentForCaseOnlyDifference()
	{
		final List<FieldContribution> contributions = Arrays.asList(RecordLineageGraphicTest.eqContribution("1", "ERP", "BANK OF CHINA"),
			RecordLineageGraphicTest.eqContribution("2", "SAP", "BANK OF CHINA"), RecordLineageGraphicTest.eqContribution("3", "CRM", "Bank of China"));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(
			Arrays.asList(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)), RecordLineageGraphicTest.chain("2", false, "SAP", new Date(500L)),
				RecordLineageGraphicTest.chain("3", false, "CRM", new Date(1000L))),
			RecordLineage.listOf(new FieldInput("name", contributions)), MergeInfo.present("GOLDEN", "118", "1", false),
			RecordLineageGraphicTest.recordFields("name", "BANK OF CHINA"));
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		final JsonNode contributionsNode = graphic.get("fields").get(0).get("contributions");
		RecordLineageGraphicTest.check("the first exact-matching contribution is plain EQ (two matches - ambiguous, never retained)",
			"EQ".equals(contributionsNode.get(0).get("state").asText()));
		RecordLineageGraphicTest.check("the second exact-matching contribution is plain EQ too", "EQ".equals(contributionsNode.get(1).get("state").asText()));
		RecordLineageGraphicTest.check("the case-only-differing contribution is EQUIVALENT, not DIFF", "EQUIVALENT".equals(contributionsNode.get(2).get("state").asText()));
	}

	/** A genuine (non-fold-equivalent) difference, with more than one source, still renders DIFF - the fold fix must not hide a real disagreement. */
	private static void contributionState_diffStaysStrictForAGenuineDifference()
	{
		final List<FieldContribution> contributions = Arrays.asList(RecordLineageGraphicTest.eqContribution("1", "ERP", "BPCE SA"),
			RecordLineageGraphicTest.eqContribution("2", "CRM", "Groupe BPCE"));
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(
			Arrays.asList(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)), RecordLineageGraphicTest.chain("2", false, "CRM", new Date(1000L))),
			RecordLineage.listOf(new FieldInput("name", contributions)), MergeInfo.present("GOLDEN", "118", "1", false), RecordLineageGraphicTest.recordFields("name", "BPCE SA"));
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		RecordLineageGraphicTest.check("a genuine difference still renders DIFF", "DIFF".equals(graphic.get("fields").get(0).get("contributions").get(1).get("state").asText()));
	}

	/**
	 * Single-source rule (task brief, 2026-09-22): a value differing from golden - whether by case only or by
	 * a genuine edit - is TRANSFORMED, never DIFF/EQUIVALENT, when there is exactly one contributing source
	 * (nothing else could possibly have supplied the golden value).
	 */
	private static void contributionState_transformedForSingleSourceRegardlessOfHowItDiffers()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)));

		final List<FieldContribution> caseOnly = RecordLineage.listOf(RecordLineageGraphicTest.eqContribution("1", "ERP", "Bank of China"));
		final ObjectNode caseOnlyLineage = RecordLineageGraphicTest.lineageOf(sources, RecordLineage.listOf(new FieldInput("name", caseOnly)),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), RecordLineageGraphicTest.recordFields("name", "BANK OF CHINA"));
		final ObjectNode caseOnlyGraphic = RecordLineageGraphic.build(caseOnlyLineage, Locale.ENGLISH);
		RecordLineageGraphicTest.check("single source, case-only difference from golden -> TRANSFORMED, not EQUIVALENT",
			"TRANSFORMED".equals(caseOnlyGraphic.get("fields").get(0).get("contributions").get(0).get("state").asText()));

		final List<FieldContribution> genuine = RecordLineage.listOf(RecordLineageGraphicTest.eqContribution("1", "ERP", "Bank of China"));
		final ObjectNode genuineLineage = RecordLineageGraphicTest.lineageOf(sources, RecordLineage.listOf(new FieldInput("name", genuine)),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), RecordLineageGraphicTest.recordFields("name", "Bank of China (edited by a steward)"));
		final ObjectNode genuineGraphic = RecordLineageGraphic.build(genuineLineage, Locale.ENGLISH);
		RecordLineageGraphicTest.check("single source, a genuinely different golden value -> TRANSFORMED too, never DIFF",
			"TRANSFORMED".equals(genuineGraphic.get("fields").get(0).get("contributions").get(0).get("state").asText()));

		final List<FieldContribution> exact = RecordLineage.listOf(RecordLineageGraphicTest.eqContribution("1", "ERP", "Bank of China"));
		final ObjectNode exactLineage = RecordLineageGraphicTest.lineageOf(sources, RecordLineage.listOf(new FieldInput("name", exact)),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), RecordLineageGraphicTest.recordFields("name", "Bank of China"));
		final ObjectNode exactGraphic = RecordLineageGraphic.build(exactLineage, Locale.ENGLISH);
		RecordLineageGraphicTest.check("single source, an exact match -> still plain EQ",
			"EQ".equals(exactGraphic.get("fields").get(0).get("contributions").get(0).get("state").asText()));
	}

	/** With at most one contributing source, `composition.differentCount` is always 0 - nothing can genuinely "differ" against a golden with a single supplier (task brief, 2026-09-22), even for a field whose one contribution differs from the golden text (TRANSFORMED, not a disagreement). */
	private static void composition_singleSource_differentCountAlwaysZero()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageGraphicTest.chain("1", true, "ERP", new Date(0L)));
		final List<FieldInput> fields = Arrays.asList(new FieldInput("name", RecordLineage.listOf(RecordLineageGraphicTest.eqContribution("1", "ERP", "Bank of China"))),
			new FieldInput("city", RecordLineage.listOf(RecordLineageGraphicTest.eqContribution("1", "ERP", "Beijing"))));
		final Map<String, FieldValue> recordFields = new LinkedHashMap<String, FieldValue>();
		recordFields.put("name", FieldValue.of("BANK OF CHINA")); // differs from the contribution only by case -> TRANSFORMED
		recordFields.put("city", FieldValue.of("Beijing")); // exact match -> EQ
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(sources, fields, MergeInfo.absent(MergeInfo.NO_MATCHING_YET), recordFields);
		final ObjectNode graphic = RecordLineageGraphic.build(lineage, Locale.ENGLISH);

		final JsonNode composition = graphic.get("composition");
		RecordLineageGraphicTest.check("never a KNOWN answer here", !composition.get("known").asBoolean());
		RecordLineageGraphicTest.check("differentCount is always 0 with a single source", composition.get("differentCount").asInt() == 0);
		RecordLineageGraphicTest.check("both fields count as concordant", composition.get("concordantCount").asInt() == 2);
	}

	/** The tooltip/badge label keys this lot adds resolve to real text in both locales - never a raw `lineage.graphic.*` key leaking through (same discipline as {@link #labelsResolveInBothLocalesNeverARawKey}). */
	private static void tooltipLabels_resolveInBothLocales()
	{
		final ObjectNode lineage = RecordLineageGraphicTest.lineageOf(Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null);
		final ObjectNode english = RecordLineageGraphic.build(lineage, Locale.ENGLISH);
		final ObjectNode french = RecordLineageGraphic.build(lineage, Locale.FRENCH);
		final String[] keys = { "transformed", "tooltipEq", "tooltipEquivalent", "tooltipDiff", "tooltipTransformed", "tooltipRetainedKnown", "tooltipRetainedDeduced",
			"tooltipEmpty", "tooltipRawWithSteps", "tooltipRawNoSteps" };
		for (final String key : keys)
		{
			final String en = english.get("labels").get(key).asText();
			final String fr = french.get("labels").get(key).asText();
			RecordLineageGraphicTest.check("english " + key + " is not a raw key", !en.startsWith("lineage."));
			RecordLineageGraphicTest.check("french " + key + " is not a raw key", !fr.startsWith("lineage."));
			RecordLineageGraphicTest.check("english and french differ for " + key, !en.equals(fr));
		}
	}

	private static FieldContribution eqContribution(final String pMasterKey, final String pSourceSystem, final String pValue)
	{
		return new FieldContribution(pMasterKey, pSourceSystem, false, "COL", pValue, pValue, Collections.<String>emptyList());
	}

	private static Map<String, FieldValue> recordFields(final String pField, final String pValue)
	{
		final Map<String, FieldValue> out = new LinkedHashMap<String, FieldValue>();
		out.put(pField, FieldValue.of(pValue));
		return out;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
