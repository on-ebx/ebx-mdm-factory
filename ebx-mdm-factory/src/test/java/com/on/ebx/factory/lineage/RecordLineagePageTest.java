package com.on.ebx.factory.lineage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.lineage.RecordLineage.FieldContribution;
import com.on.ebx.factory.lineage.RecordLineage.FieldInput;
import com.on.ebx.factory.lineage.RecordLineage.FieldValue;
import com.on.ebx.factory.lineage.RecordLineage.GroupInfo;
import com.on.ebx.factory.lineage.RecordLineage.GroupMember;
import com.on.ebx.factory.lineage.RecordLineage.Input;
import com.on.ebx.factory.lineage.RecordLineage.MergeInfo;
import com.on.ebx.factory.lineage.RecordLineage.PlanFieldRef;
import com.on.ebx.factory.lineage.RecordLineage.SourceChain;
import com.on.ebx.factory.lineage.RecordLineage.SuspectFieldSet;
import com.on.ebx.factory.lineage.RecordLineagePage.EntityOption;
import com.on.ebx.factory.lineage.RecordLineagePage.ProjectOption;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Exercises {@link
 * RecordLineagePage} directly from {@code ObjectNode} fixtures built by {@link RecordLineage#assemble} -
 * the exact same shape {@code ebx.RecordLineageReader} hands the page in production - never from a
 * hand-written JSON string, so a fixture can never drift from what {@code RecordLineage} actually emits.
 */
public final class RecordLineagePageTest
{
	public static void main(final String[] pArgs)
	{
		RecordLineagePageTest.noProjectShowsSelector();
		RecordLineagePageTest.projectSelectedNoLookupYetShowsEmptyHint();
		RecordLineagePageTest.projectWithNoPromotedEntityShowsNotice();
		RecordLineagePageTest.singleSourceRecordRendersJourneyAndFields();
		RecordLineagePageTest.noscriptJourneyShowsLandingAndStagingIdChips();
		RecordLineagePageTest.twoSourcesDifferingFieldIsHighlighted();
		RecordLineagePageTest.agreeingSourcesShowNoHighlight();
		RecordLineagePageTest.cleaningStepsShownAsHumanWordsInOrder();
		RecordLineagePageTest.restrictedSourceShowsNoValueOnlyAMarker();
		RecordLineagePageTest.restrictedFieldContributionShowsUnknownDiffers();
		RecordLineagePageTest.mergeAbsentEachReasonShowsPlainWords();
		RecordLineagePageTest.mergeAbsentMetadataUnreadableShowsDetail();
		RecordLineagePageTest.goldenRecordWithTwoContributorsRendersMatchGroupAndGoldenNode();
		RecordLineagePageTest.mergedRecordPointsToADifferentGoldenNeverRendersAsIsolatedGolden();
		RecordLineagePageTest.autoCreatedGoldenShowsTheCreatedAutomaticallyNote();
		RecordLineagePageTest.restrictedMasterFieldShowsMarkerNeverAValue();
		RecordLineagePageTest.scriptInFieldValueIsEscapedNeverExecutable();
		RecordLineagePageTest.scriptInMasterLabelIsEscapedNeverExecutable();
		RecordLineagePageTest.compactHeaderShowsMasterLabelWhenPresent_keyOtherwise();
		RecordLineagePageTest.scriptInKeyTextIsEscapedNeverExecutable();
		RecordLineagePageTest.restrictedContributionDeliberatelyCarryingAValueNeverLeaksIt();
		RecordLineagePageTest.frenchAndEnglishBothRenderNoRawKey();
		RecordLineagePageTest.fieldLineageNoteShownWhenPresent();
		RecordLineagePageTest.graphicContainerRenderedInsteadOfFallbackWhenLineagePresent();
		RecordLineagePageTest.noGraphicContainerNorFallbackWithoutALookup();
		RecordLineagePageTest.graphicScriptNullWithoutALookup();
		RecordLineagePageTest.graphicScriptEmbedsDataAndTheDrawingCode();
		RecordLineagePageTest.graphicScriptScriptInFieldValueCannotBreakOutOfTheEmbeddedData();
		RecordLineagePageTest.graphicScriptRestrictedValueNeverLeaksIntoEmbeddedJson();
		RecordLineagePageTest.graphicScriptEmbedsTheNewTooltipLabels();
		RecordLineagePageTest.graphicScriptEmbedsIdChipsForEverySourceLandingStagingLane();
		RecordLineagePageTest.comparePanelAbsent_whenNoSuspectFieldSets();
		RecordLineagePageTest.comparePanelShown_oneSuspect_withDecisionButtons();
		RecordLineagePageTest.comparePanelShown_threeSuspects_allColumnsAndChips();
		RecordLineagePageTest.comparePanelRestrictedField_showsLockNoValue();
		RecordLineagePageTest.comparePanelNoPlan_fieldsStillShownUnderOtherFields();
		RecordLineagePageTest.comparePanelOtherFields_shareSameColgroupAsMainTable();
		RecordLineagePageTest.comparePanelReadOnly_noDecisionButtonsShown();
		RecordLineagePageTest.comparePanelPending_showsConfirmCancelInsteadOfInitialButtons();
		RecordLineagePageTest.pendingSuspectScrollScript_nullNormally_scrollsMdmfCompareWhenPending();
		RecordLineagePageTest.comparePanelScriptInSuspectValue_escapedNeverExecutable();
		RecordLineagePageTest.crossReferencesPanel_goldenAndTwoMergedMembersOneRestricted();
		RecordLineagePageTest.crossReferencesPanel_noSources_showsNoneNotice();
		RecordLineagePageTest.crossReferencesPanel_pendingSuspect_neverListed();
		RecordLineagePageTest.crossReferencesPanel_scriptInSourceSystemIsEscapedNeverExecutable();
		RecordLineagePageTest.recordBoundTrue_hidesLookupCard_hiddenInputsCarryResolvedValues_resultCardsStillRender();
		RecordLineagePageTest.recordBoundFalse_rendersByteForByteUnchanged();
		System.out.println("RecordLineagePageTest OK");
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	private static SourceChain chain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final String pSourceId, final String pStagingKey)
	{
		return RecordLineagePageTest.chain(pMasterKey, pGolden, pSourceSystem, false, pSourceId, pStagingKey);
	}

	private static SourceChain chain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final boolean pRestricted, final String pSourceId,
		final String pStagingKey)
	{
		return new SourceChain(pMasterKey, pGolden, pSourceSystem, pRestricted, pSourceId, "Companies_Landing", "SUPPLIER".equals(pSourceSystem) ? "SUPPLIER" : "ACCOUNT",
			pSourceId, "L20260921-1", "STAGED", new Date(0L), "Company", pStagingKey);
	}

	private static FieldContribution contribution(final String pMasterKey, final String pSourceSystem, final String pLandingColumn, final String pRawValue,
		final String pStagingValue, final List<String> pCleaningSteps)
	{
		return new FieldContribution(pMasterKey, pSourceSystem, false, pLandingColumn, pRawValue, pStagingValue, pCleaningSteps);
	}

	private static Map<String, FieldValue> recordFields(final String pField, final String pValue)
	{
		final Map<String, FieldValue> out = new LinkedHashMap<String, FieldValue>();
		out.put(pField, FieldValue.of(pValue));
		return out;
	}

	/** A minimal, otherwise-plain {@link RecordLineagePage.Input} carrying {@code pLineage} - one project, one entity, a key, no notice. */
	private static RecordLineagePage.Input input(final Locale pLocale, final ObjectNode pLineage, final String pNotice)
	{
		final List<ProjectOption> projects = Arrays.asList(new ProjectOption("COMPANIES360", "Companies 360"));
		final List<EntityOption> entities = Arrays.asList(new EntityOption("Company", "Company"));
		return new RecordLineagePage.Input(pLocale, projects, "COMPANIES360", "Companies 360", entities, "Company", "12", pNotice, pNotice == null ? "ok" : "warn",
			pLineage);
	}

	/** Same as {@link #input}, with a pending suspect decision ({@link RecordLineagePage.Input#pendingSuspectAction}/{@link RecordLineagePage.Input#pendingSuspectKey}). */
	private static RecordLineagePage.Input inputWithPending(final ObjectNode pLineage, final String pPendingAction, final String pPendingKey)
	{
		final List<ProjectOption> projects = Arrays.asList(new ProjectOption("COMPANIES360", "Companies 360"));
		final List<EntityOption> entities = Arrays.asList(new EntityOption("Company", "Company"));
		return new RecordLineagePage.Input(Locale.ENGLISH, projects, "COMPANIES360", "Companies 360", entities, "Company", "12", null, "ok", pLineage, pPendingAction,
			pPendingKey);
	}

	// ------------------------------------------------------------------------ "compare with suspects" fixtures

	private static GroupMember suspectMember(final String pMasterKey, final String pLabel, final String pSourceSystem)
	{
		return GroupMember.of(pMasterKey, pLabel, pSourceSystem, null);
	}

	/**
	 * A GOLDEN record with {@code pSuspectCount} pending suspects, each carrying field {@code "name"} (and
	 * {@code "country"} when {@code pWithSecondField}) so the comparison panel has something to compare -
	 * suspect N's own {@code "name"} value is {@code "Acme"} for N=0 (identical to the anchor), {@code "ACME"}
	 * for N=1 (case-fold equivalent), and {@code "Other " + N} otherwise (a genuine difference) - enough
	 * variety to exercise every chip in a single fixture family.
	 */
	private static ObjectNode lineageWithSuspects(final int pSuspectCount, final boolean pCanAcceptSuspects, final boolean pRestrictFieldOnFirstSuspect,
		final List<PlanFieldRef> pPlanFields)
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final List<GroupMember> suspectMembers = new ArrayList<GroupMember>();
		final List<SourceChain> suspectSources = new ArrayList<SourceChain>();
		final List<SuspectFieldSet> suspectFieldSets = new ArrayList<SuspectFieldSet>();
		for (int i = 0; i < pSuspectCount; i++)
		{
			final String key = "14" + i;
			suspectMembers.add(RecordLineagePageTest.suspectMember(key, "Suspect " + i, "CRM"));
			suspectSources.add(new SourceChain(key, false, "CRM", false, "ACC-" + i, "Companies_Landing", "ACCOUNT", "ACC-" + i, "L1", "STAGED", new Date(0L), "Company",
				"CRM|ACC-" + i, "/ebx-ui/ui/advanced/data/BCompanies_Landing/Landing/root/ACCOUNT?predicate=./id=" + i, null, null, true));
			final String nameValue = i == 0 ? "Acme" : i == 1 ? "ACME" : "Other " + i;
			final Map<String, FieldValue> suspectFields = new LinkedHashMap<String, FieldValue>();
			suspectFields.put("name", (i == 0 && pRestrictFieldOnFirstSuspect) ? FieldValue.restricted() : FieldValue.of(nameValue));
			suspectFields.put("country", FieldValue.of("FR"));
			suspectFieldSets.add(SuspectFieldSet.of(key, suspectFields));
		}
		final GroupInfo group = new GroupInfo("118", "Group 118", 1, Collections.<GroupMember>emptyList(), suspectMembers, false, pCanAcceptSuspects);
		final MergeInfo merge = MergeInfo.present("GOLDEN", "118", "1", false, "GOLDEN_WITH_SUSPECTS", group);
		final Map<String, FieldValue> anchorFields = new LinkedHashMap<String, FieldValue>();
		anchorFields.put("name", FieldValue.of("Acme"));
		anchorFields.put("country", FieldValue.of("FR"));
		final List<FieldInput> fields = Arrays.asList(new FieldInput("name", Collections.<FieldContribution>emptyList()),
			new FieldInput("country", Collections.<FieldContribution>emptyList()));
		// masterLabel deliberately left null: this fixture's own "Acme" is the anchor's FIELD value, checked
		// as an exact count in comparePanelRestrictedField_showsLockNoValue - a non-null masterLabel would add
		// a SECOND, unrelated ">Acme<" occurrence (the compact header's own <h2>) and make that count wrong.
		return RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", anchorFields, sources, fields, merge, null,
			"/ebx-ui/ui/advanced/data/BCompanies_Master/Master/root/Company?predicate=./id=1", null, suspectSources, pPlanFields, suspectFieldSets));
	}

	// ------------------------------------------------------------------------------------------------------ tests

	/** No project resolved yet: the project selector, never a table/journey with nothing behind it. */
	private static void noProjectShowsSelector()
	{
		final RecordLineagePage.Input input = new RecordLineagePage.Input(Locale.ENGLISH, Arrays.asList(new ProjectOption("COMPANIES360", "Companies 360")), null, null,
			Collections.<EntityOption>emptyList(), null, "", null, "ok", null);
		final String html = RecordLineagePage.render(input);
		RecordLineagePageTest.check("the project selector is shown", html.contains("mdmfProjectSelect"));
		RecordLineagePageTest.check("no journey without a project", !html.contains("mdmf-journey"));
	}

	/** A project (and entity) is picked but nothing has been looked up yet: the empty-state hint, including the deep-link URL form, not a blank screen. */
	private static void projectSelectedNoLookupYetShowsEmptyHint()
	{
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, null, null));
		RecordLineagePageTest.check("the empty-state hint is shown", html.contains("Nothing looked up yet"));
		RecordLineagePageTest.check("the deep-link URL form is documented", html.contains("service=RecordLineage&amp;project="));
		RecordLineagePageTest.check("no journey/field table without a lookup", !html.contains("mdmf-journey"));
	}

	/** A project with no promoted master entity yet: the entity-less warning, never an empty select silently offering nothing. */
	private static void projectWithNoPromotedEntityShowsNotice()
	{
		final RecordLineagePage.Input input = new RecordLineagePage.Input(Locale.ENGLISH, Arrays.asList(new ProjectOption("COMPANIES360", "Companies 360")),
			"COMPANIES360", "Companies 360", Collections.<EntityOption>emptyList(), null, "", null, "ok", null);
		final String html = RecordLineagePage.render(input);
		RecordLineagePageTest.check("the no-promoted-entity notice is shown", html.contains("no promoted master entity yet"));
	}

	/** A record fed by exactly one system: one journey row, no differing chip, the record's own fields listed. */
	private static void singleSourceRecordRendersJourneyAndFields()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("name",
			RecordLineage.listOf(RecordLineagePageTest.contribution("1", "ERP", "LEGAL_NAME", "ACME INDUSTRIES", "ACME INDUSTRIES", Collections.<String>emptyList()))));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineagePageTest.recordFields("name", "Acme Industries SAS"),
			sources, fields, MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the source system is shown", html.contains(">ERP<"));
		RecordLineagePageTest.check("the master record's own field value is shown", html.contains("Acme Industries SAS"));
		RecordLineagePageTest.check("the contribution's value is shown", html.contains("ACME INDUSTRIES"));
		RecordLineagePageTest.check("no differing row without a second source", !html.contains("mdmf-field-differs"));
		RecordLineagePageTest.check("merge absent shows the plain-words reason", html.contains("No matching has run yet for this entity."));
		RecordLineagePageTest.check("no fabricated matching group without a merge", !html.contains("Matching group"));
	}

	/**
	 * The landing and staging journey nodes each carry their OWN EBX id (task brief, 2026-09-22: "for a
	 * source-zone node whose key is a different table's key, show that table's key the same way") - as
	 * ".mdmf-id" chips, never fused into the bold title the way the pre-fix staging node used to bake its
	 * own key into ("{table} · {key}" in one run). {@link #chain} passes the same value for sourceId and
	 * landingKey (a fixture simplification, not a claim they are ever equal in production - see
	 * SourceChain#landingKey's own Javadoc: it is the LANDING table's own primary key, sourceId is the
	 * business identifier from the source system).
	 */
	private static void noscriptJourneyShowsLandingAndStagingIdChips()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the landing node carries its own id as a chip",
			html.contains("<span class=\"mdmf-id\" title=\"EBX record id\">· SUP-1</span>"));
		RecordLineagePageTest.check("the staging node carries its own id as a chip, never fused into the bold title any more",
			html.contains("<b>Company</b><span class=\"mdmf-id\" title=\"EBX record id\">· ERP|SUP-1</span>"));
	}

	/** Two systems disagreeing on a field: the row is highlighted and the "Differ" chip shown, both distinct values visible. */
	private static void twoSourcesDifferingFieldIsHighlighted()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"),
			RecordLineagePageTest.chain("2", false, "CRM", "ACC-1", "CRM|ACC-1"));
		final List<FieldContribution> countryContributions = Arrays.asList(
			RecordLineagePageTest.contribution("1", "ERP", "COUNTRY", "FR", "FR", Collections.<String>emptyList()),
			RecordLineagePageTest.contribution("2", "CRM", "BILLING_COUNTRY", "France", "FR-STAGED-DIFFERENT", Collections.<String>emptyList()));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("country", countryContributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources, fields,
			MergeInfo.present("MERGED", "42", "1", false), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the differing row is highlighted", html.contains("mdmf-field-differs"));
		RecordLineagePageTest.check("the 'Differ' chip is shown", html.contains(">Differ<"));
		RecordLineagePageTest.check("both contributing values are visible", html.contains("FR-STAGED-DIFFERENT") && html.contains("</span> FR</div>"));
	}

	/** Two systems agreeing: no highlight, the "Agree" chip instead. */
	private static void agreeingSourcesShowNoHighlight()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"),
			RecordLineagePageTest.chain("2", false, "CRM", "ACC-1", "CRM|ACC-1"));
		final List<FieldContribution> contributions = Arrays.asList(RecordLineagePageTest.contribution("1", "ERP", "COUNTRY", "FR", "FR", Collections.<String>emptyList()),
			RecordLineagePageTest.contribution("2", "CRM", "BILLING_COUNTRY", "France", "FR", Collections.<String>emptyList()));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("country", contributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources, fields,
			MergeInfo.present("MERGED", "42", "1", false), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("no highlight when every visible value agrees", !html.contains("mdmf-field-differs"));
		RecordLineagePageTest.check("the 'Agree' chip is shown", html.contains(">Agree<"));
	}

	/** A field's cleaning steps are shown as ordered, human words - never a raw enum name, never out of order. */
	private static void cleaningStepsShownAsHumanWordsInOrder()
	{
		final List<FieldContribution> contributions = RecordLineage
			.listOf(RecordLineagePageTest.contribution("1", "CRM", "BILLING_COUNTRY", "France", "FR", Arrays.asList("TRIM", "COUNTRY_TO_ISO2")));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("country", contributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "CRM", "ACC-1", "CRM|ACC-1")), fields, MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("no raw kind name leaks", !html.contains("TRIM→") && !html.contains(">TRIM<"));
		final int trimWords = html.indexOf("Trim leading/trailing spaces");
		final int isoWords = html.indexOf("Convert country name to ISO 3166-1 alpha-2 code");
		RecordLineagePageTest.check("both steps rendered in human words", trimWords >= 0 && isoWords >= 0);
		RecordLineagePageTest.check("TRIM's words appear before COUNTRY_TO_ISO2's, same order as the pipeline", trimWords < isoWords);

		// STRIP_ACCENTS (added 2026-09-20 per CLAUDE.md) also has its own words, not a fallback raw-kind rendering
		final List<FieldContribution> accentContribution = RecordLineage
			.listOf(RecordLineagePageTest.contribution("1", "CRM", "CITY", "Cote d'Azur", "Cote d'Azur", Collections.singletonList("STRIP_ACCENTS")));
		final ObjectNode accentJson = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "CRM", "ACC-1", "CRM|ACC-1")),
			RecordLineage.listOf(new FieldInput("city", accentContribution)), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String accentHtml = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, accentJson, null));
		RecordLineagePageTest.check("STRIP_ACCENTS has its own human words, not a raw fallback", accentHtml.contains("Remove accents"));
	}

	/** A restricted source (landing/staging hidden) shows a discreet marker only - never a source id, a landing/staging key, or a value, even in the per-source journey node. */
	private static void restrictedSourceShowsNoValueOnlyAMarker()
	{
		final SourceChain restricted = RecordLineagePageTest.chain("2", false, "CRM", true, "SHOULD-NOT-LEAK", "SHOULD-NOT-LEAK-EITHER");
		final List<SourceChain> sources = Arrays.asList(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"), restricted);
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.present("MERGED", "1", "1", false), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the restricted source's own id never leaks", !html.contains("SHOULD-NOT-LEAK"));
		RecordLineagePageTest.check("a discreet restricted marker is shown instead", html.contains("Restricted"));
	}

	/** A field hidden on one of two sources: the other's chip shows "restricted", never rawValue/stagingValue, and "differs" reads as Unknown, never a confident Agree/Differ. */
	private static void restrictedFieldContributionShowsUnknownDiffers()
	{
		final List<FieldContribution> contributions = Arrays.asList(RecordLineagePageTest.contribution("1", "ERP", "VAT_NO", "FR1", "FR1", Collections.<String>emptyList()),
			new FieldContribution("2", "CRM", true, "SECRET_COLUMN", "SHOULD-NOT-LEAK-RAW", "SHOULD-NOT-LEAK-STAGED", Collections.singletonList("TRIM")));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("vatNo", contributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), fields, MergeInfo.present("MERGED", "1", "1", false), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the restricted value never leaks", !html.contains("SHOULD-NOT-LEAK"));
		RecordLineagePageTest.check("a restricted marker is shown for that contribution", html.contains("Unknown (restricted)") || html.contains("restricted"));
		RecordLineagePageTest.check("differs reads Unknown, never a confident Agree/Differ", html.contains("Unknown (restricted)"));
	}

	/** Every documented "merge absent" reason renders in plain words - never the raw enum code, never an error page. */
	private static void mergeAbsentEachReasonShowsPlainWords()
	{
		final String[] reasons = { MergeInfo.NO_MATCHING_YET, MergeInfo.ADDON_NOT_AVAILABLE, MergeInfo.NO_METADATA };
		final String[] expectedWords = { "No matching has run yet", "not available on this server", "no matching metadata yet" };
		for (int i = 0; i < reasons.length; i++)
		{
			final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
				Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(), MergeInfo.absent(reasons[i]), null));
			final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));
			RecordLineagePageTest.check("plain words for " + reasons[i], html.contains(expectedWords[i]));
			RecordLineagePageTest.check("no raw reason code leaks for " + reasons[i], !html.contains(reasons[i]));
			RecordLineagePageTest.check("no fabricated golden/matching-group node for " + reasons[i], !html.contains("mdmf-jnode-golden"));
		}
	}

	/** METADATA_UNREADABLE additionally shows its non-sensitive diagnostic detail. */
	private static void mergeAbsentMetadataUnreadableShowsDetail()
	{
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(),
			MergeInfo.absent(MergeInfo.METADATA_UNREADABLE, "PathAccessException: no child named 'goldenId'"), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));
		RecordLineagePageTest.check("the reason is shown in plain words", html.contains("could not be read"));
		RecordLineagePageTest.check("the detail is shown", html.contains("PathAccessException"));
	}

	/** A golden record with two merged contributors: both journey rows shown, and the golden node highlighted with the entity's own table and the golden key. */
	private static void goldenRecordWithTwoContributorsRendersMatchGroupAndGoldenNode()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineagePageTest.chain("12", true, "ERP", "SUP-1", "ERP|SUP-1"),
			RecordLineagePageTest.chain("7", false, "CRM", "ACC-1", "CRM|ACC-1"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "12", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.present("GOLDEN", "42", "12", false), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("both source rows are rendered", html.contains(">ERP<") && html.contains(">CRM<"));
		RecordLineagePageTest.check("the matching group node is shown", html.contains("Matching group"));
		RecordLineagePageTest.check("the state is shown in plain words", html.contains("Golden record"));
		RecordLineagePageTest.check("the group id is shown", html.contains("Group 42"));
		// fix, 2026-09-22: the golden's own key is now a ".mdmf-id" chip after the table name, not baked into
		// the same bold run (task brief: "never as the main title") - RecordLineagePage#idChip's exact markup.
		RecordLineagePageTest.check("the golden node carries the anchor's own master key, as an id chip",
			html.contains("<b>/root/Company</b><span class=\"mdmf-id\" title=\"EBX record id\">· 12</span>"));
	}

	/**
	 * Coordinator's live measurement, 2026-09-22 (record 290, merged into a NEW golden 322 - {@code
	 * ebx.MameSuspectDecision}'s own Javadoc has the full story): viewing a MERGED record directly must say so
	 * in plain words and point at the golden's own key - never render as if this record were itself an isolated
	 * golden, and never crash for a golden key that differs from the record actually being viewed. {@code
	 * autoCreated} here describes the ANCHOR (290) itself, which was NOT auto-created (only the new golden 322
	 * was) - {@code ebx.RecordLineageReader#computeMerge} reads it off whichever record is being viewed, never
	 * off the golden.
	 */
	private static void mergedRecordPointsToADifferentGoldenNeverRendersAsIsolatedGolden()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("290", false, "ERP", "SUP-290", "ERP|SUP-290"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "290", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.present("MERGED", "1833036450509851", "322", false), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the state says MERGED, in plain words - never conflated with GOLDEN (RecordLineageTest#deriveState_nonGoldenState_neverDerived already covers the pure model)",
			html.contains("<b>Merged into another record</b>"));
		RecordLineagePageTest.check("the golden node points at the OTHER record's key, not the anchor's own, as an id chip",
			html.contains("<b>/root/Company</b><span class=\"mdmf-id\" title=\"EBX record id\">· 322</span>"));
		RecordLineagePageTest.check("the group id is shown", html.contains("Group 1833036450509851"));
		RecordLineagePageTest.check("no autoCreated note - the ANCHOR (290) was not itself auto-created", !html.contains("created automatically"));
	}

	/** The auto-created golden ITSELF (322, autoCreated true) - the "created automatically" note must show, and rendering a golden that has no landing/staging chain of its own (an auto-created record has none - see ebx.RecordLineageReader#resolveSource's own Javadoc) must never crash. */
	private static void autoCreatedGoldenShowsTheCreatedAutomaticallyNote()
	{
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "322", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(), MergeInfo.present("GOLDEN", "1833036450509851", "322", true), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("plain GOLDEN state shown", html.contains(">Golden record<"));
		RecordLineagePageTest.check("the autoCreated note is shown", html.contains("created automatically"));
		RecordLineagePageTest.check("the golden node carries its own key even with no source chain at all, as an id chip",
			html.contains("<b>/root/Company</b><span class=\"mdmf-id\" title=\"EBX record id\">· 322</span>"));
	}

	/** A field hidden on the master record itself: dropped from record.fields, shown as a restricted marker in the record panel - never a value. */
	private static void restrictedMasterFieldShowsMarkerNeverAValue()
	{
		final Map<String, FieldValue> recordFields = new LinkedHashMap<String, FieldValue>();
		recordFields.put("name", FieldValue.of("Acme Industries SAS"));
		recordFields.put("vatNo", FieldValue.restricted());
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", recordFields, Collections.<SourceChain>emptyList(),
			Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the visible field's value is shown", html.contains("Acme Industries SAS"));
		RecordLineagePageTest.check("the restricted field's name is shown", html.contains(">vatNo<"));
		RecordLineagePageTest.check("a restricted marker is shown for it", html.contains("restricted"));
	}

	/** A `"><script>` value inside a real field's rawValue/stagingValue is escaped, never rendered as executable markup. */
	private static void scriptInFieldValueIsEscapedNeverExecutable()
	{
		final String payload = "\"><script>alert(1)</script>";
		final List<FieldContribution> contributions = RecordLineage.listOf(RecordLineagePageTest.contribution("1", "ERP", "LEGAL_NAME", payload, payload, Collections.<String>emptyList()));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("name", contributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineagePageTest.recordFields("name", payload),
			RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1")), fields, MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("no raw <script> tag anywhere in the page", !html.contains("<script>alert(1)</script>"));
		RecordLineagePageTest.check("the escaped form is present instead (record panel and/or contribution)",
			html.contains("&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;"));
	}

	/** The master record's own NAME (coordinator review, 2026-09-22: shown as the compact header's title) is escaped exactly like every other dynamic string - a `"><script>` label can never break out of the page. */
	private static void scriptInMasterLabelIsEscapedNeverExecutable()
	{
		final String payload = "\"><script>alert(1)</script>";
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null, null, payload));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("no raw <script> tag from the master label", !html.contains("<script>alert(1)</script>"));
		RecordLineagePageTest.check("the escaped label is present instead", html.contains("&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;"));
	}

	/** The compact header's title is the master record's own NAME when the reader supplied one, and the bare key otherwise - never a blank title. */
	private static void compactHeaderShowsMasterLabelWhenPresent_keyOtherwise()
	{
		final ObjectNode withLabel = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null, null, "BPCE SA"));
		final String htmlWithLabel = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, withLabel, null));
		RecordLineagePageTest.check("the compact header shows the name", htmlWithLabel.contains("<h2>BPCE SA"));
		// fix, 2026-09-22 (task brief: "show the EBX id everywhere a record is named ... never as the main
		// title") - the id chip sits INSIDE the <h2>, right after the name, never fused into the title text.
		RecordLineagePageTest.check("the EBX id chip is shown next to the name, not fused into the title",
			htmlWithLabel.contains("<h2>BPCE SA<span class=\"mdmf-id\" title=\"EBX record id\">· 1</span></h2>"));

		final ObjectNode withoutLabel = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String htmlWithoutLabel = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, withoutLabel, null));
		RecordLineagePageTest.check("the compact header falls back to the bare key when there is no label", htmlWithoutLabel.contains("<h2>1</h2>"));
		// never "1 · 1" - masterTitle already IS the bare key here, so no separate id chip is added.
		RecordLineagePageTest.check("no id chip when the title is already the bare key", !htmlWithoutLabel.contains("mdmf-id"));
	}

	/** The very key the reviewer typed (echoed back into the key box's own value attribute) is escaped the same way. */
	private static void scriptInKeyTextIsEscapedNeverExecutable()
	{
		final String payload = "\"><script>alert(1)</script>";
		final List<ProjectOption> projects = Arrays.asList(new ProjectOption("COMPANIES360", "Companies 360"));
		final List<EntityOption> entities = Arrays.asList(new EntityOption("Company", "Company"));
		final RecordLineagePage.Input input = new RecordLineagePage.Input(Locale.ENGLISH, projects, "COMPANIES360", "Companies 360", entities, "Company", payload, null,
			"ok", null);
		final String html = RecordLineagePage.render(input);

		RecordLineagePageTest.check("no raw <script> tag from the typed key", !html.contains("<script>alert(1)</script>"));
		RecordLineagePageTest.check("the escaped key is present instead, in both the hidden field and the visible key box",
			html.contains("&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;"));
	}

	/**
	 * Defense in depth, same discipline as {@code RecordLineageTest#restrictedFieldOnOneOfTwoSources_
	 * valueOmittedDiffersNull}: a restricted contribution fixture that DELIBERATELY still carries real
	 * values must still be redacted by the time it reaches the page - proving the page never independently
	 * re-derives visibility from anything other than what {@code RecordLineage.assemble} already decided.
	 */
	private static void restrictedContributionDeliberatelyCarryingAValueNeverLeaksIt()
	{
		final FieldContribution stillCarryingAValue = new FieldContribution("2", "CRM", true, "SECRET_COLUMN", "leaked-raw-value", "leaked-staged-value",
			Collections.singletonList("TRIM"));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("vatNo", Collections.singletonList(stillCarryingAValue)));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), fields, MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the fixture's own still-carried raw value never reaches the page", !html.contains("leaked-raw-value"));
		RecordLineagePageTest.check("the fixture's own still-carried staged value never reaches the page", !html.contains("leaked-staged-value"));
	}

	/** French and English both render every label from the bundle - no raw message key ever shown to the reviewer. */
	private static void frenchAndEnglishBothRenderNoRawKey()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineagePageTest.recordFields("name", "Acme"), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.present("GOLDEN", "1", "1", false), null));

		final String english = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));
		RecordLineagePageTest.check("english label", english.contains("Record lineage"));
		RecordLineagePageTest.check("english journey label", english.contains("Source system"));
		RecordLineagePageTest.check("no raw lineage.* key leaks (english)", !english.contains("lineage."));

		final String french = RecordLineagePage.render(RecordLineagePageTest.input(Locale.FRENCH, json, null));
		// the apostrophe is HTML-escaped like any other dynamic string (RecordLineagePage.esc), so the raw
		// source text ("l'enregistrement") is never found verbatim in the rendered page - check the escaped form
		RecordLineagePageTest.check("french label", french.contains("Lineage de l&#39;enregistrement"));
		RecordLineagePageTest.check("french accented text renders as real UTF-8/HTML, not mojibake", french.contains("Système source"));
		RecordLineagePageTest.check("no raw lineage.* key leaks (french)", !french.contains("lineage."));
	}

	/** {@code fieldLineageNote} (the documented seam, more than one contributing source) is shown verbatim when present, absent otherwise. */
	private static void fieldLineageNoteShownWhenPresent()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"),
			RecordLineagePageTest.chain("2", false, "CRM", "ACC-1", "CRM|ACC-1"));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("name",
			RecordLineage.listOf(RecordLineagePageTest.contribution("1", "ERP", "LEGAL_NAME", "Acme", "Acme", Collections.<String>emptyList()))));
		final ObjectNode withNote = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources, fields,
			MergeInfo.present("MERGED", "1", "1", false), "Field-by-field merge provenance was not read: seam."));
		final String htmlWithNote = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, withNote, null));
		RecordLineagePageTest.check("the field lineage note is shown", htmlWithNote.contains("Field-by-field merge provenance was not read: seam."));

		final ObjectNode withoutNote = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1")), fields, MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String htmlWithoutNote = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, withoutNote, null));
		RecordLineagePageTest.check("no note fabricated when the reader did not send one", !htmlWithoutNote.contains("was not read"));
	}

	// -------------------------------------------------------------------------------------- graphic screen (2026-09-21)

	/** With a lineage answer, the graphic container is rendered and the OLD tables sit inside {@code <noscript>} (invisible to a JavaScript-enabled browser, the only fallback a browser without JavaScript would see). */
	private static void graphicContainerRenderedInsteadOfFallbackWhenLineagePresent()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineagePageTest.recordFields("name", "Acme"), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the graphic container is present", html.contains("mdmfLineageGraphicRoot"));
		RecordLineagePageTest.check("the old journey table is present, but only inside <noscript>",
			html.contains("<noscript>") && html.indexOf("mdmf-journey") > html.indexOf("<noscript>"));
		RecordLineagePageTest.check("the single-source note placeholder sits above the scrolling fields table, hidden until the script fills it in",
			html.contains("<p data-mdmf-fields-note class=\"mdmf-hint\" hidden=\"hidden\"></p>")
				&& html.indexOf("data-mdmf-fields-note") < html.indexOf("mdmf-fields-scroll"));
	}

	private static void noGraphicContainerNorFallbackWithoutALookup()
	{
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, null, null));
		RecordLineagePageTest.check("no graphic container without a lookup", !html.contains("mdmfLineageGraphicRoot"));
		RecordLineagePageTest.check("no noscript fallback without a lookup either", !html.contains("<noscript>"));
	}

	private static void graphicScriptNullWithoutALookup()
	{
		RecordLineagePageTest.check("graphicScript is null when there is nothing to draw", RecordLineagePage.graphicScript(null, Locale.ENGLISH) == null);
	}

	private static void graphicScriptEmbedsDataAndTheDrawingCode()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String script = RecordLineagePage.graphicScript(json, Locale.ENGLISH);

		RecordLineagePageTest.check("the script is not a hand-written <script> tag - a plain JS statement for addJS_cr",
			!script.trim().startsWith("<script"));
		RecordLineagePageTest.check("the master key is embedded", script.contains("\"masterKey\":\"1\""));
		RecordLineagePageTest.check("the drawing code (parcours) is included", script.contains("function drawParcours"));
		RecordLineagePageTest.check("it targets the container by id", script.contains("mdmfLineageGraphicRoot"));
	}

	/** A `"><script>`/`</script>` payload inside a field value cannot break out of the `var data = ...;` statement the script is embedded in - same discipline as the catalog module's own lineage view. */
	private static void graphicScriptScriptInFieldValueCannotBreakOutOfTheEmbeddedData()
	{
		final String payload = "\"};</script><script>alert(1)</script>";
		final List<FieldContribution> contributions = RecordLineage
			.listOf(RecordLineagePageTest.contribution("1", "ERP", "NAME", payload, payload, Collections.<String>emptyList()));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineagePageTest.recordFields("name", payload),
			RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1")), RecordLineage.listOf(new FieldInput("name", contributions)),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String script = RecordLineagePage.graphicScript(json, Locale.ENGLISH);

		RecordLineagePageTest.check("no raw </script> sequence anywhere in the emitted script", !script.contains("</script>"));
		RecordLineagePageTest.check("no raw <script> sequence anywhere in the emitted script", !script.contains("<script>"));
		RecordLineagePageTest.check("the payload is present in its escaped form instead", script.contains("\\u003c/script\\u003e"));
	}

	/** A restricted contribution's (deliberately still-carried, per the fixture) value never reaches the embedded JSON, even though the redacted JSON structure alone is not what this test is checking - it is the FULL embedded-script text. */
	private static void graphicScriptRestrictedValueNeverLeaksIntoEmbeddedJson()
	{
		final FieldContribution restricted = new FieldContribution("2", "CRM", true, "SECRET_COLUMN", "leaked-raw-value", "leaked-staged-value",
			Collections.singletonList("TRIM"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), RecordLineage.listOf(new FieldInput("vatNo", Collections.singletonList(restricted))),
			MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String script = RecordLineagePage.graphicScript(json, Locale.ENGLISH);

		RecordLineagePageTest.check("the restricted raw value never reaches the embedded script", !script.contains("leaked-raw-value"));
		RecordLineagePageTest.check("the restricted staged value never reaches the embedded script", !script.contains("leaked-staged-value"));
	}

	/** The new per-cell tooltip templates (this lot, 2026-09-22) reach the embedded `data.labels` object the client script reads as `L` - same "the L object carries every lineage.graphic.* label the JS needs" contract every other label already relies on. */
	private static void graphicScriptEmbedsTheNewTooltipLabels()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String script = RecordLineagePage.graphicScript(json, Locale.ENGLISH);

		RecordLineagePageTest.check("tooltipEq embedded", script.contains("\"tooltipEq\""));
		RecordLineagePageTest.check("tooltipEquivalent embedded", script.contains("\"tooltipEquivalent\""));
		RecordLineagePageTest.check("tooltipDiff embedded", script.contains("\"tooltipDiff\""));
		RecordLineagePageTest.check("tooltipTransformed embedded", script.contains("\"tooltipTransformed\""));
		RecordLineagePageTest.check("tooltipRetainedKnown embedded", script.contains("\"tooltipRetainedKnown\""));
		RecordLineagePageTest.check("tooltipRetainedDeduced embedded", script.contains("\"tooltipRetainedDeduced\""));
		RecordLineagePageTest.check("tooltipEmpty embedded", script.contains("\"tooltipEmpty\""));
		RecordLineagePageTest.check("tooltipRawWithSteps embedded", script.contains("\"tooltipRawWithSteps\""));
		RecordLineagePageTest.check("tooltipRawNoSteps embedded", script.contains("\"tooltipRawNoSteps\""));
		RecordLineagePageTest.check("transformed badge word embedded", script.contains("\"transformed\":\"transformed\""));
		RecordLineagePageTest.check("the client-side reading function is present", script.contains("function cellTooltip"));
	}

	/**
	 * The graphic's own id-chip wiring (task brief, 2026-09-22: "on each source/suspect lane of the
	 * graphic") - checked the same way {@link #graphicScriptEmbedsTheNewTooltipLabels} checks the tooltip
	 * templates above: {@link RecordLineagePage#graphicScript} embeds {@code lineage-graphic.js} VERBATIM
	 * (its own {@code graphicJs()}, a plain classpath resource read), so asserting on the returned script's
	 * text is asserting on the actual shipped JS source, not a description of it. This is the only kind of
	 * check available without Node on this machine (see this class's own "JUnit-free" Javadoc) - it proves
	 * the code is present and wired to the right JSON fields, not that a browser draws it correctly; that
	 * half was verified by reading, not by running (docs/RECORD-LINEAGE.md's 2026-09-22 entry says so).
	 */
	private static void graphicScriptEmbedsIdChipsForEverySourceLandingStagingLane()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String script = RecordLineagePage.graphicScript(json, Locale.ENGLISH);

		RecordLineagePageTest.check("the shared id tooltip label reaches the embedded data.labels object", script.contains("\"idTooltip\""));
		RecordLineagePageTest.check("the suspect lane reads its own masterKey for the source-system id chip", script.contains("s.kind==='suspect'&&s.masterKey"));
		RecordLineagePageTest.check("the landing card reads its own landingKey, not just sourceId", script.contains("s.landingKey"));
		RecordLineagePageTest.check("the staging card still reads stagingKey (now styled as a muted chip, not the bold title)", script.contains("s.stagingKey"));
		RecordLineagePageTest.check("the id chips use the shared monospace stack, same as .mdmf-id in lineage.css",
			script.contains("ui-monospace,SFMono-Regular,Menlo,monospace"));
		RecordLineagePageTest.check("an SVG <title> tooltip is attached to at least the staging id chip", script.contains("el('title',{},stageIdEl"));
	}

	// -------------------------------------------------------------------- "compare with suspects" (2026-09-22)

	/** No suspect at all: no comparison panel, no leftover "Compare with suspects" heading anywhere on the page. */
	private static void comparePanelAbsent_whenNoSuspectFieldSets()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));
		RecordLineagePageTest.check("no comparison panel without a suspect", !html.contains("id=\"mdmfCompare\""));
	}

	/** Anchor + 1 suspect, write access granted: the panel, the header (system/id/link), the chip for the compared field, and both decision buttons. */
	private static void comparePanelShown_oneSuspect_withDecisionButtons()
	{
		final ObjectNode json = RecordLineagePageTest.lineageWithSuspects(1, true, false,
			RecordLineage.listOf(new PlanFieldRef("name", "ebx-addon-mame@fuzzyFullText")));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the panel is rendered", html.contains("id=\"mdmfCompare\""));
		RecordLineagePageTest.check("the panel title carries the suspect count", html.contains("Compare with suspects (1)"));
		RecordLineagePageTest.check("the plan field's algorithm is shown in words", html.contains("fuzzy full-text match"));
		RecordLineagePageTest.check("the suspect's own source system is shown", html.contains(">CRM<"));
		RecordLineagePageTest.check("the suspect's own source id is shown", html.contains(">ACC-0<"));
		RecordLineagePageTest.check("an open-in-EBX link is offered for the suspect", html.contains("predicate=./id=0"));
		RecordLineagePageTest.check("an EQ chip for the identical name value", html.contains("mdmf-chip-cmp-eq"));
		RecordLineagePageTest.check("the accept button is offered", html.contains("data-mdmf-action=\"ACCEPT_SUSPECT\""));
		RecordLineagePageTest.check("the reject button is offered", html.contains("data-mdmf-action=\"REJECT_SUSPECT\""));
		RecordLineagePageTest.check("the suspect key is carried on the decision buttons", html.contains("data-mdmf-suspect-key=\"140\""));
		RecordLineagePageTest.check("the chip legend is shown", html.contains("Chip legend"));

		// task brief, 2026-09-22: the EBX id chip in the compare panel's own column headers - the anchor's
		// own key (masterKey "1") next to "This record", the suspect's own key ("140") next to its system.
		RecordLineagePageTest.check("the anchor column header carries the anchor's own id chip",
			html.contains("This record<span class=\"mdmf-id\" title=\"EBX record id\">· 1</span>"));
		RecordLineagePageTest.check("the suspect column header carries the suspect's own id chip",
			html.contains("<b>CRM</b><span class=\"mdmf-id\" title=\"EBX record id\">· 140</span>"));

		// the decision label ("Suspect 0" - lineageWithSuspects's own GroupMember label) now carries the
		// same id chip too (coordinator's live example: "HSBC BANK · 274 - Confirm this suspect is a
		// duplicate?").
		RecordLineagePageTest.check("the decision block's own label carries the suspect's id chip",
			html.contains("<b>Suspect 0</b><span class=\"mdmf-id\" title=\"EBX record id\">· 140</span>"));

		// fix, 2026-09-22 (the product owner's report: the confirm prompt rendered below "Other fields (N)", out of
		// the viewport) - the decision block (Accept/Reject here) must now render BEFORE the main comparison
		// table, right under the panel's own header, not after it.
		final int panelStart = html.indexOf("id=\"mdmfCompare\"");
		final int decisionAt = html.indexOf("data-mdmf-action=\"ACCEPT_SUSPECT\"", panelStart);
		final int tableAt = html.indexOf("<thead>", panelStart);
		RecordLineagePageTest.check("the decision buttons render before the main comparison table, not after it",
			panelStart >= 0 && decisionAt >= 0 && tableAt >= 0 && decisionAt < tableAt);
	}

	/** Anchor + 3 suspects (task brief's own fixture requirement): all three columns present, one of each chip kind on the "name" field. */
	private static void comparePanelShown_threeSuspects_allColumnsAndChips()
	{
		final ObjectNode json = RecordLineagePageTest.lineageWithSuspects(3, true, false,
			RecordLineage.listOf(new PlanFieldRef("name", "ebx-addon-mame@exact")));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the panel title carries the count of three", html.contains("Compare with suspects (3)"));
		RecordLineagePageTest.check("three suspect columns present", RecordLineagePageTest.count(html, "data-mdmf-compare-col=\"") == 3);
		RecordLineagePageTest.check("an EQ chip (suspect 0, identical)", html.contains("mdmf-chip-cmp-eq"));
		RecordLineagePageTest.check("an EQUIVALENT chip (suspect 1, case fold)", html.contains("mdmf-chip-cmp-equivalent"));
		RecordLineagePageTest.check("a DIFF chip (suspect 2, genuinely different)", html.contains("mdmf-chip-cmp-diff"));
		RecordLineagePageTest.check("three sets of decision buttons", RecordLineagePageTest.count(html, "data-mdmf-action=\"ACCEPT_SUSPECT\"") == 3);
	}

	/** A field hidden on one suspect's own record: a lock marker, never a value, never a confident chip - the "hidden field -> lock" rule the task brief asked for. */
	private static void comparePanelRestrictedField_showsLockNoValue()
	{
		final ObjectNode json = RecordLineagePageTest.lineageWithSuspects(1, true, true, Collections.<PlanFieldRef>emptyList());
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));
		RecordLineagePageTest.check("a locked-field marker is shown", html.contains("hidden for your profile"));
		// the ANCHOR's own "name" value ("Acme") is legitimately visible in the comparison panel's own anchor
		// column (never restricted in this fixture) - scope the count to the compare panel's own markup
		// (between its opening <details> and the <noscript> fallback that follows it, which ALSO shows every
		// record field unconditionally and would otherwise add an unrelated second occurrence of its own) and
		// assert exactly one - the suspect's own restricted field must never contribute a second one.
		final int compareStart = html.indexOf("id=\"mdmfCompare\"");
		final int noscriptStart = html.indexOf("<noscript>");
		RecordLineagePageTest.check("the compare panel is present before the noscript fallback", compareStart >= 0 && noscriptStart > compareStart);
		final String comparePanelHtml = html.substring(compareStart, noscriptStart);
		RecordLineagePageTest.check("the restricted suspect value never leaks a second, distinct 'Acme' occurrence inside the compare panel",
			RecordLineagePageTest.count(comparePanelHtml, ">Acme<") == 1);
	}

	/** No plan at all: every field still shows, collapsed under "Other fields (N)" - never dropped just because there is no plan to prioritise it. */
	private static void comparePanelNoPlan_fieldsStillShownUnderOtherFields()
	{
		final ObjectNode json = RecordLineagePageTest.lineageWithSuspects(1, true, false, Collections.<PlanFieldRef>emptyList());
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));
		RecordLineagePageTest.check("no plan-field algorithm text without a plan", !html.contains("fuzzy full-text match") && !html.contains("exact match"));
		RecordLineagePageTest.check("both fields still show, collapsed under Other fields", html.contains("Other fields (2)"));
	}

	/**
	 * Bug fixed 2026-09-22 (the product owner's screenshot: the "Other fields" table's value/suspect columns start
	 * well to the left of the main table's own, at x&asymp;363 vs x&asymp;398 and x&asymp;754 vs x&asymp;828).
	 * {@code RecordLineagePage#renderCompareTableOpen}/{@code #renderCompareColgroup} now write the EXACT
	 * SAME {@code <colgroup>} markup - byte for byte, not just "visually close" - into both the main
	 * comparison table and the "Other fields" table, computed once from the shared suspect count, so a
	 * browser genuinely cannot size the two tables' columns apart from each other (task brief's own wording
	 * for fix approach (b): "give both table-layout: fixed, identical colgroup widths ... so the browser
	 * cannot size them independently"). Reuses the "no plan" fixture ({@link
	 * #comparePanelNoPlan_fieldsStillShownUnderOtherFields}'s own) deliberately: it puts every field under
	 * "Other fields", leaving the main table's own {@code <tbody>} with zero data rows - the sharpest check
	 * that column width comes from the suspect count alone, never from a row of body content the main table
	 * happens (or does not happen) to have.
	 */
	private static void comparePanelOtherFields_shareSameColgroupAsMainTable()
	{
		final ObjectNode json = RecordLineagePageTest.lineageWithSuspects(1, true, false, Collections.<PlanFieldRef>emptyList());
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		// one suspect: field-name column 20%, "this record" column 20%, the sole suspect column gets the
		// remaining 60% - formatted with Locale.ROOT (RecordLineagePage#comparePct) so this never depends on
		// the JVM's own default locale.
		final String expectedColgroup = "<colgroup><col style=\"width:20.0000%\"/><col style=\"width:20.0000%\"/><col style=\"width:60.0000%\"/></colgroup>";
		RecordLineagePageTest.check("the main table and the Other-fields table carry the identical <colgroup> (one suspect: 20/20/60)",
			RecordLineagePageTest.count(html, expectedColgroup) == 2);

		// same guarantee for the table's own floor width (2 structural columns + 1 suspect column, 140px
		// each - RecordLineagePage#COMPARE_COL_MIN_PX): both <table> tags must carry the SAME value, never
		// one computed from a different row count.
		RecordLineagePageTest.check("both compare tables share the same fixed min-width",
			RecordLineagePageTest.count(html, "<table class=\"mdmf-compare-table\" style=\"min-width:420px\">") == 2);
	}

	/** {@code canAcceptSuspects: false} (no write access, or a stale render) - the read-only note, and neither decision button. */
	private static void comparePanelReadOnly_noDecisionButtonsShown()
	{
		final ObjectNode json = RecordLineagePageTest.lineageWithSuspects(1, false, false, Collections.<PlanFieldRef>emptyList());
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));
		RecordLineagePageTest.check("the read-only note is shown", html.contains("do not have write access"));
		RecordLineagePageTest.check("no accept button when read-only", !html.contains("data-mdmf-action=\"ACCEPT_SUSPECT\""));
		RecordLineagePageTest.check("no reject button when read-only", !html.contains("data-mdmf-action=\"REJECT_SUSPECT\""));
	}

	/** A pending ACCEPT decision on suspect "140": the "Confirm?" prompt with its own Confirm/Cancel pair - never the initial two buttons for THAT suspect (task brief: "a second click on Confirm?/Really?"). */
	private static void comparePanelPending_showsConfirmCancelInsteadOfInitialButtons()
	{
		final ObjectNode json = RecordLineagePageTest.lineageWithSuspects(1, true, false, Collections.<PlanFieldRef>emptyList());
		final String html = RecordLineagePage.render(RecordLineagePageTest.inputWithPending(json, "ACCEPT", "140"));
		RecordLineagePageTest.check("the confirm prompt is shown", html.contains("Confirm this suspect is a duplicate"));
		RecordLineagePageTest.check("a CONFIRM_SUSPECT_DECISION button is offered", html.contains("data-mdmf-action=\"CONFIRM_SUSPECT_DECISION\""));
		RecordLineagePageTest.check("a CANCEL_SUSPECT_DECISION button is offered", html.contains("data-mdmf-action=\"CANCEL_SUSPECT_DECISION\""));
		RecordLineagePageTest.check("the initial ACCEPT_SUSPECT button is no longer shown for this suspect",
			!html.contains("data-mdmf-action=\"ACCEPT_SUSPECT\" data-mdmf-suspect-key=\"140\""));

		// fix, 2026-09-22 (the product owner's report - see class Javadoc): the confirm prompt itself, not just the
		// initial buttons, must render BEFORE the main comparison table - it is what he clicked a suspect
		// lane to see and never saw, because it used to sit below "Other fields (N)".
		final int panelStart = html.indexOf("id=\"mdmfCompare\"");
		final int confirmAt = html.indexOf("Confirm this suspect is a duplicate", panelStart);
		final int tableAt = html.indexOf("<thead>", panelStart);
		RecordLineagePageTest.check("the confirm prompt renders before the main comparison table",
			panelStart >= 0 && confirmAt >= 0 && tableAt >= 0 && confirmAt < tableAt);
	}

	/**
	 * {@link RecordLineagePage#pendingSuspectScrollScript} - null whenever nothing is pending (every normal
	 * render), a guarded {@code scrollIntoView} on {@code #mdmfCompare} otherwise, {@code block:'start'}
	 * (see that method's own Javadoc for why this differs from {@code lineage-graphic.js}'s own
	 * {@code openCompare}, which uses {@code 'nearest'} - that one nudges an already-visible page, this one
	 * is the first thing the reviewer should see right after a postback).
	 */
	private static void pendingSuspectScrollScript_nullNormally_scrollsMdmfCompareWhenPending()
	{
		RecordLineagePageTest.check("null with nothing pending", RecordLineagePage.pendingSuspectScrollScript(null, null) == null);
		RecordLineagePageTest.check("null with only an action, no key", RecordLineagePage.pendingSuspectScrollScript("ACCEPT", null) == null);
		RecordLineagePageTest.check("null with only a key, no action", RecordLineagePage.pendingSuspectScrollScript(null, "140") == null);

		final String script = RecordLineagePage.pendingSuspectScrollScript("ACCEPT", "140");
		RecordLineagePageTest.check("a script is emitted once both are set", script != null);
		RecordLineagePageTest.check("targets the compare panel by id", script.contains("getElementById('mdmfCompare')"));
		RecordLineagePageTest.check("guarded before scrolling", script.contains("if(el&&el.scrollIntoView)"));
		RecordLineagePageTest.check("scrolls to the very top of the viewport, not merely the nearest edge", script.contains("block:'start'"));
	}

	/** A `"><script>` payload in a suspect's own field value cannot break out of the comparison table - the same escaping discipline as every other dynamic string on this page. */
	private static void comparePanelScriptInSuspectValue_escapedNeverExecutable()
	{
		final String payload = "\"><script>alert(1)</script>";
		final Map<String, FieldValue> suspectFields = new LinkedHashMap<String, FieldValue>();
		suspectFields.put("name", FieldValue.of(payload));
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final GroupInfo group = new GroupInfo("118", "Group 118", 1, Collections.<GroupMember>emptyList(),
			RecordLineage.listOf(RecordLineagePageTest.suspectMember("141", "Suspect", "CRM")), false, true);
		final MergeInfo merge = MergeInfo.present("GOLDEN", "118", "1", false, "GOLDEN_WITH_SUSPECTS", group);
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineagePageTest.recordFields("name", "Acme"), sources,
			RecordLineage.listOf(new FieldInput("name", Collections.<FieldContribution>emptyList())), merge, null, null, null,
			Collections.<SourceChain>emptyList(), Collections.<PlanFieldRef>emptyList(), RecordLineage.listOf(SuspectFieldSet.of("141", suspectFields))));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));
		RecordLineagePageTest.check("the raw payload never appears unescaped", !html.contains(payload));
		RecordLineagePageTest.check("the escaped form is present instead", html.contains("&quot;&gt;&lt;script&gt;"));
	}

	// ---------------------------------------------------------------------------------------- cross-references

	private static SourceChain chainWithUrls(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final String pSourceId,
		final String pStagingKey, final String pLandingUrl, final String pStagingUrl)
	{
		return new SourceChain(pMasterKey, pGolden, pSourceSystem, false, pSourceId, "Companies_Landing", "SUPPLIER".equals(pSourceSystem) ? "SUPPLIER" : "ACCOUNT",
			pSourceId, "L20260921-1", "STAGED", new Date(0L), "Company", pStagingKey, pLandingUrl, pStagingUrl);
	}

	private static SourceChain restrictedChain(final String pMasterKey, final String pSourceSystem)
	{
		return RecordLineagePageTest.chain(pMasterKey, false, pSourceSystem, true, "SHOULD-NOT-LEAK", "SHOULD-NOT-LEAK-EITHER");
	}

	/**
	 * The task brief's own scenario: a golden and two merged members, one of them restricted. Checks the
	 * table's title, every visible column, the golden mark on the golden row only, the "open in EBX" links
	 * under the staging/landing cells, and that the restricted row shows its system name and nothing else.
	 */
	private static void crossReferencesPanel_goldenAndTwoMergedMembersOneRestricted()
	{
		final List<SourceChain> sources = Arrays.asList(
			RecordLineagePageTest.chainWithUrls("12", true, "ERP", "SUP-00231", "ERP|SUP-00231", "https://ebx/landing/12", "https://ebx/staging/12"),
			RecordLineagePageTest.chain("7", false, "CRM", "ACC-1", "CRM|ACC-1"), RecordLineagePageTest.restrictedChain("9", "POS"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "12", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.present("GOLDEN", "42", "12", false), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the panel title is shown", html.contains("Cross-references"));
		RecordLineagePageTest.check("the golden row's own source system is shown", html.contains(">ERP<"));
		RecordLineagePageTest.check("the merged member's own source system is shown", html.contains(">CRM<"));
		RecordLineagePageTest.check("the restricted row's own source system is shown", html.contains(">POS<"));
		RecordLineagePageTest.check("the golden row is marked", html.contains("Golden record"));
		RecordLineagePageTest.check("exactly one row is marked golden (not the merged member, not the restricted one)",
			RecordLineagePageTest.count(html, "mdmf-chip mdmf-chip-accent\">Golden record") == 1);
		RecordLineagePageTest.check("the staging open link is rendered", html.contains("https://ebx/staging/12"));
		RecordLineagePageTest.check("the landing open link is rendered", html.contains("https://ebx/landing/12"));
		RecordLineagePageTest.check("the restricted row never leaks its own key", !html.contains("SHOULD-NOT-LEAK"));
		RecordLineagePageTest.check("the restricted row shows the same word every other restricted thing on this screen shows",
			html.contains("mdmf-chip mdmf-chip-target\">restricted"));
	}

	/** No contributing source at all: the panel shows its own "none" notice rather than an empty table. */
	private static void crossReferencesPanel_noSources_showsNoneNotice()
	{
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "322", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(), MergeInfo.present("GOLDEN", "1", "322", true), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the panel is still shown", html.contains("Cross-references"));
		RecordLineagePageTest.check("the none notice is shown", html.contains("No cross-reference for this record yet."));
		RecordLineagePageTest.check("no xref table is rendered", !html.contains("mdmf-xref-table"));
	}

	/** A pending suspect must never show up in the cross-references table - it is not yet a confirmed contributor. */
	private static void crossReferencesPanel_pendingSuspect_neverListed()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final List<SourceChain> suspectSources = RecordLineage.listOf(RecordLineagePageTest.chain("99", false, "CRM", "ACC-99", "CRM|ACC-99"));
		final GroupInfo group = new GroupInfo("118", "Group 118", 1, Collections.<GroupMember>emptyList(),
			RecordLineage.listOf(RecordLineagePageTest.suspectMember("99", "Suspect", "CRM")), false, true);
		final MergeInfo merge = MergeInfo.present("GOLDEN", "118", "1", false, "GOLDEN_WITH_SUSPECTS", group);
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), merge, null, null, null, suspectSources));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the confirmed ERP source is listed", html.contains(">ERP<"));
		// the suspect is also on the "Compare with suspects" panel elsewhere on the page (as "CRM"), so this
		// only proves the cross-references TABLE specifically never lists it, by checking its own id chip
		// text never appears at all - the suspect key "99" is otherwise absent from every fixture above.
		RecordLineagePageTest.check("the suspect's own key never appears anywhere on the page", !html.contains("· 99<"));
	}

	/** A field value carrying a script payload cannot break out of the cross-references table either - same {@code esc()} discipline as every other table on this screen. */
	private static void crossReferencesPanel_scriptInSourceSystemIsEscapedNeverExecutable()
	{
		final String payload = "\"><script>alert(1)</script>";
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, payload, "SUP-1", "ERP|SUP-1"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.present("GOLDEN", "1", "1", false), null));
		final String html = RecordLineagePage.render(RecordLineagePageTest.input(Locale.ENGLISH, json, null));

		RecordLineagePageTest.check("the raw payload never appears unescaped", !html.contains(payload));
		RecordLineagePageTest.check("the escaped form is present instead", html.contains("&quot;&gt;&lt;script&gt;"));
	}

	// --------------------------------------------------------------------------------------------- recordBound

	/** The exact fixture {@link #singleSourceRecordRendersJourneyAndFields} already uses - reused here so the two {@code recordBound} tests below compare like for like. */
	private static ObjectNode recordBoundFixtureLineage()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineagePageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("name",
			RecordLineage.listOf(RecordLineagePageTest.contribution("1", "ERP", "LEGAL_NAME", "ACME INDUSTRIES", "ACME INDUSTRIES", Collections.<String>emptyList()))));
		return RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineagePageTest.recordFields("name", "Acme Industries SAS"), sources,
			fields, MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
	}

	/**
	 * {@code recordBound = true} ({@code ebx.ui.RecordLineageOnRecordUserService}, coordinator review, 2026-09-23):
	 * the whole lookup card is gone (no title/lead, no project selector or "Change project" button, no entity
	 * select, no key input, no "Look up" button) but the hidden {@code mdmfProjectCode}/{@code mdmfEntity}/
	 * {@code mdmfKey} inputs still carry the record's own resolved values (what a suspect decide/accept/reject
	 * round trip posts back), and every result card below (compact header, graphic container) still renders
	 * exactly as it would with the card shown.
	 */
	private static void recordBoundTrue_hidesLookupCard_hiddenInputsCarryResolvedValues_resultCardsStillRender()
	{
		final ObjectNode json = RecordLineagePageTest.recordBoundFixtureLineage();
		final List<ProjectOption> projects = Arrays.asList(new ProjectOption("COMPANIES360", "Companies 360"));
		final List<EntityOption> entities = Arrays.asList(new EntityOption("Company", "Company"));
		final RecordLineagePage.Input input = new RecordLineagePage.Input(Locale.ENGLISH, projects, "COMPANIES360", "Companies 360", entities, "Company", "1", null, "ok",
			json, null, null, true);
		final String html = RecordLineagePage.render(input);

		RecordLineagePageTest.check("no page title/lead card", !html.contains("<h2>Record lineage</h2>"));
		RecordLineagePageTest.check("no 'Look up' button", !html.contains("data-mdmf-action=\"LOOKUP\""));
		RecordLineagePageTest.check("no project selector", !html.contains("mdmfProjectSelect"));
		RecordLineagePageTest.check("no 'Change project' button", !html.contains("data-mdmf-action=\"CHANGE_PROJECT\""));
		RecordLineagePageTest.check("no entity select", !html.contains("mdmfEntitySelect"));
		RecordLineagePageTest.check("no key input", !html.contains("mdmfKeyInput"));

		RecordLineagePageTest.check("the hidden project field still carries the resolved value",
			html.contains("<input type=\"hidden\" id=\"mdmfProjectCode\" name=\"mdmfProjectCode\" value=\"COMPANIES360\"/>"));
		RecordLineagePageTest.check("the hidden entity field still carries the resolved value",
			html.contains("<input type=\"hidden\" id=\"mdmfEntity\" name=\"mdmfEntity\" value=\"Company\"/>"));
		RecordLineagePageTest.check("the hidden key field still carries the resolved value",
			html.contains("<input type=\"hidden\" id=\"mdmfKey\" name=\"mdmfKey\" value=\"1\"/>"));

		RecordLineagePageTest.check("the compact record header still renders", html.contains("Company · 1 · 1 source(s)"));
		RecordLineagePageTest.check("the graphic container still renders", html.contains("mdmfLineageGraphicRoot"));
		RecordLineagePageTest.check("the merge-absent notice still renders", html.contains("No matching has run yet for this entity."));
	}

	/**
	 * {@code recordBound = false} (every entry point except {@code ebx.ui.RecordLineageOnRecordUserService}) renders
	 * BYTE-FOR-BYTE the same page as before this lot: the 12-argument {@code Input} constructor (every
	 * existing caller/fixture, including {@link #input}) still defaults {@link RecordLineagePage.Input#recordBound}
	 * to {@code false}, so its output must be identical to the same fixture rendered through the new
	 * 13-argument constructor with {@code recordBound} passed explicitly as {@code false} - and the lookup
	 * card must still be there.
	 */
	private static void recordBoundFalse_rendersByteForByteUnchanged()
	{
		final ObjectNode json = RecordLineagePageTest.recordBoundFixtureLineage();
		final RecordLineagePage.Input defaulted = RecordLineagePageTest.input(Locale.ENGLISH, json, null);
		final List<ProjectOption> projects = Arrays.asList(new ProjectOption("COMPANIES360", "Companies 360"));
		final List<EntityOption> entities = Arrays.asList(new EntityOption("Company", "Company"));
		final RecordLineagePage.Input explicit = new RecordLineagePage.Input(Locale.ENGLISH, projects, "COMPANIES360", "Companies 360", entities, "Company", "12", null,
			"ok", json, null, null, false);

		final String htmlDefaulted = RecordLineagePage.render(defaulted);
		final String htmlExplicit = RecordLineagePage.render(explicit);
		RecordLineagePageTest.check("recordBound=false renders byte-for-byte the same whether defaulted or passed explicitly",
			htmlDefaulted.equals(htmlExplicit));
		RecordLineagePageTest.check("the lookup card's title is still shown", htmlDefaulted.contains("<h2>Record lineage</h2>"));
		RecordLineagePageTest.check("the 'Look up' button is still shown", htmlDefaulted.contains("data-mdmf-action=\"LOOKUP\""));
		RecordLineagePageTest.check("the entity select is still shown", htmlDefaulted.contains("mdmfEntitySelect"));
		RecordLineagePageTest.check("the key input is still shown", htmlDefaulted.contains("mdmfKeyInput"));
	}

	private static int count(final String pHaystack, final String pNeedle)
	{
		int count = 0;
		int from = 0;
		while (true)
		{
			final int at = pHaystack.indexOf(pNeedle, from);
			if (at < 0)
			{
				return count;
			}
			count++;
			from = at + pNeedle.length();
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
