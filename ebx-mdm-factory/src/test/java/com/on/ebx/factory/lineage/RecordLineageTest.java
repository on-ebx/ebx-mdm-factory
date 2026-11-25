package com.on.ebx.factory.lineage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.lineage.RecordLineage.FieldContribution;
import com.on.ebx.factory.lineage.RecordLineage.FieldInput;
import com.on.ebx.factory.lineage.RecordLineage.FieldValue;
import com.on.ebx.factory.lineage.RecordLineage.Input;
import com.on.ebx.factory.lineage.RecordLineage.MergeInfo;
import com.on.ebx.factory.lineage.RecordLineage.PlanFieldRef;
import com.on.ebx.factory.lineage.RecordLineage.SourceChain;
import com.on.ebx.factory.lineage.RecordLineage.SuspectFieldSet;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class RecordLineageTest
{
	public static void main(final String[] pArgs)
	{
		RecordLineageTest.oneSystem_noMatchingYet_singleChainNoDiffer();
		RecordLineageTest.twoSystems_caseOnlyCityDifference_noLongerDiffers();
		RecordLineageTest.twoSystems_genuinelyDifferentCity_stillDiffers();
		RecordLineageTest.equivalentToGolden_caseAccentAndWhitespaceOnlyDifferences();
		RecordLineageTest.equivalentToGolden_noGoldenValue_isNull();
		RecordLineageTest.fieldWithCleaningSteps_stepsCarriedInOrder();
		RecordLineageTest.mergeAbsent_eachReason();
		RecordLineageTest.mergeAbsent_metadataUnreadable_carriesDetail();
		RecordLineageTest.goldenWithTwoMergedSources();
		RecordLineageTest.fieldMissingFromOneSource_noExceptionNoSpuriousDiffer();
		RecordLineageTest.compositeKeyContainingPipe_carriedThroughUnmodified();
		RecordLineageTest.restrictedSource_onlySystemNameEmitted();
		RecordLineageTest.restrictedFieldOnOneOfTwoSources_valueOmittedDiffersNull();
		RecordLineageTest.restrictedMasterField_droppedFromFieldsListedAsRestricted();
		RecordLineageTest.allVisible_unchangedShape();
		RecordLineageTest.deducedRetained_uniqueMatch_isDeduced();
		RecordLineageTest.deducedRetained_twoMatches_noneRetained();
		RecordLineageTest.deducedRetained_noMatch_noneRetained();
		RecordLineageTest.knownRetained_takesPrecedenceOverDeduction();
		RecordLineageTest.noGoldenValue_matchesGoldenIsNullNeverRetained();
		RecordLineageTest.restrictedContributionNeverRetainedEvenIfItWouldMatch();
		RecordLineageTest.openUrls_carriedThroughAndRedactedWhenRestricted();
		RecordLineageTest.singleContribution_neverDeducedRetained_evenThoughItTriviallyMatches();
		RecordLineageTest.fieldLabelPassesThroughWhenPresent_absentOtherwise();
		RecordLineageTest.deriveState_goldenIsolatedWithSuspects_isGoldenWithSuspects();
		RecordLineageTest.deriveState_notIsolated_staysPlainGolden();
		RecordLineageTest.deriveState_isolatedButNoSuspects_staysPlainGolden();
		RecordLineageTest.deriveState_nonGoldenState_neverDerived();
		RecordLineageTest.deriveState_unknownIsolation_neverDerived();
		RecordLineageTest.group_noSuspects_emptyArraysZeroTruncated();
		RecordLineageTest.group_oneSuspectAndOneMatch_memberCountAndFieldsCarried();
		RecordLineageTest.group_sixtySuspects_cappedAtFiftyMarkedTruncated();
		RecordLineageTest.group_hiddenSuspect_restrictedOnlyNoKeyLeaked();
		RecordLineageTest.group_actionsCanAcceptSuspects_carriedThrough();
		RecordLineageTest.suspectSources_separateArrayNeverMixedIntoSources();
		RecordLineageTest.comparison_absentWhenNoSuspectFieldSets();
		RecordLineageTest.comparison_oneSuspect_chipsForEachCase();
		RecordLineageTest.comparison_restrictedSuspect_onlyMasterKeyAndRestrictedFlag();
		RecordLineageTest.comparison_restrictedFieldOnSuspect_unknownChipNoValue();
		RecordLineageTest.comparison_restrictedAnchorField_unknownChipEvenIfSuspectVisible();
		RecordLineageTest.comparison_planFieldsCarriedInOrderWithAlgorithm();
		RecordLineageTest.comparison_threeSuspects_allPresent();
		RecordLineageTest.cleaningStepKinds_nullForPlainSource_carriedForSuspectWhenSet();
		System.out.println("RecordLineageTest OK");
	}

	// ---------------------------------------------------------------------------------------------- group fixtures

	private static RecordLineage.GroupMember member(final String pMasterKey, final String pLabel, final String pSourceSystem, final String pProcessId)
	{
		return RecordLineage.GroupMember.of(pMasterKey, pLabel, pSourceSystem, pProcessId);
	}

	private static List<RecordLineage.GroupMember> membersOf(final int pCount)
	{
		final List<RecordLineage.GroupMember> out = new ArrayList<RecordLineage.GroupMember>();
		for (int i = 0; i < pCount; i++)
		{
			out.add(RecordLineageTest.member("S" + i, "Suspect " + i, "CRM", null));
		}
		return out;
	}

	private static ObjectNode assembleWithMerge(final MergeInfo pMerge)
	{
		return RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(), pMerge, null));
	}

	// --------------------------------------------------------------------------------------------------- deriveState

	/** The task brief's own example combination: GOLDEN + isolated + at least one pending suspect -> GOLDEN_WITH_SUSPECTS. */
	private static void deriveState_goldenIsolatedWithSuspects_isGoldenWithSuspects()
	{
		final RecordLineage.GroupInfo group = new RecordLineage.GroupInfo("1", "Group 1", 1, Collections.<RecordLineage.GroupMember>emptyList(),
			RecordLineageTest.membersOf(1), false, false);
		RecordLineageTest.check("golden + isolated + a suspect -> GOLDEN_WITH_SUSPECTS",
			"GOLDEN_WITH_SUSPECTS".equals(RecordLineage.deriveState("GOLDEN", Boolean.TRUE, group)));
	}

	/** A golden with confirmed matches already (not isolated) never derives GOLDEN_WITH_SUSPECTS, whatever its suspects. */
	private static void deriveState_notIsolated_staysPlainGolden()
	{
		final RecordLineage.GroupInfo group = new RecordLineage.GroupInfo("1", "Group 1", 2, RecordLineageTest.membersOf(1), RecordLineageTest.membersOf(1), false, false);
		RecordLineageTest.check("not isolated -> plain GOLDEN", "GOLDEN".equals(RecordLineage.deriveState("GOLDEN", Boolean.FALSE, group)));
	}

	/** Isolated with zero pending suspects is just a plain, unremarkable golden. */
	private static void deriveState_isolatedButNoSuspects_staysPlainGolden()
	{
		final RecordLineage.GroupInfo group = new RecordLineage.GroupInfo("1", "Group 1", 1, Collections.<RecordLineage.GroupMember>emptyList(),
			Collections.<RecordLineage.GroupMember>emptyList(), false, false);
		RecordLineageTest.check("isolated, no suspects -> plain GOLDEN", "GOLDEN".equals(RecordLineage.deriveState("GOLDEN", Boolean.TRUE, group)));
		RecordLineageTest.check("isolated, null group -> plain GOLDEN", "GOLDEN".equals(RecordLineage.deriveState("GOLDEN", Boolean.TRUE, null)));
	}

	/** MERGED/UNSET/PENDING_APPROVAL/DELETED never derive GOLDEN_WITH_SUSPECTS, whatever isolated/suspects say - the derivation is GOLDEN-only. */
	private static void deriveState_nonGoldenState_neverDerived()
	{
		final RecordLineage.GroupInfo group = new RecordLineage.GroupInfo("1", "Group 1", 1, Collections.<RecordLineage.GroupMember>emptyList(),
			RecordLineageTest.membersOf(1), false, false);
		RecordLineageTest.check("MERGED never derives", "MERGED".equals(RecordLineage.deriveState("MERGED", Boolean.TRUE, group)));
		RecordLineageTest.check("UNSET never derives", "UNSET".equals(RecordLineage.deriveState("UNSET", Boolean.TRUE, group)));
	}

	/** An unreadable/unknown isolated flag (null) never derives GOLDEN_WITH_SUSPECTS - only a CONFIRMED true does (RecordLineageReader#readIsolatedFlag's own "never guess" rule). */
	private static void deriveState_unknownIsolation_neverDerived()
	{
		final RecordLineage.GroupInfo group = new RecordLineage.GroupInfo("1", "Group 1", 1, Collections.<RecordLineage.GroupMember>emptyList(),
			RecordLineageTest.membersOf(1), false, false);
		RecordLineageTest.check("null isolated -> never derived", "GOLDEN".equals(RecordLineage.deriveState("GOLDEN", null, group)));
	}

	// -------------------------------------------------------------------------------------------------------- group

	private static void group_noSuspects_emptyArraysZeroTruncated()
	{
		final RecordLineage.GroupInfo group = new RecordLineage.GroupInfo("1833022115544083", "BPCE", 1, Collections.<RecordLineage.GroupMember>emptyList(),
			Collections.<RecordLineage.GroupMember>emptyList(), false, false);
		final ObjectNode json = RecordLineageTest.assembleWithMerge(MergeInfo.present("GOLDEN", "1833022115544083", "1", false, "GOLDEN", group));
		final JsonNode groupNode = json.get("merge").get("group");
		RecordLineageTest.check("group id carried through", "1833022115544083".equals(groupNode.get("id").asText()));
		RecordLineageTest.check("memberCount is matched.size()+1", groupNode.get("memberCount").asInt() == 1);
		RecordLineageTest.check("matched is empty", groupNode.get("matched").size() == 0);
		RecordLineageTest.check("suspects is empty", groupNode.get("suspects").size() == 0);
		RecordLineageTest.check("not truncated", !groupNode.get("truncated").asBoolean());
	}

	private static void group_oneSuspectAndOneMatch_memberCountAndFieldsCarried()
	{
		final RecordLineage.GroupInfo group = new RecordLineage.GroupInfo("42", "BPCE", 2,
			RecordLineage.listOf(RecordLineageTest.member("2", "BPCE Retail", "SAP", null)),
			RecordLineage.listOf(RecordLineageTest.member("141", "BPCE CRM copy", "CRM", "77")), false, true);
		final ObjectNode json = RecordLineageTest.assembleWithMerge(MergeInfo.present("GOLDEN", "42", "1", false, "GOLDEN_WITH_SUSPECTS", group));
		final JsonNode groupNode = json.get("merge").get("group");
		RecordLineageTest.check("derivedState carried through", "GOLDEN_WITH_SUSPECTS".equals(json.get("merge").get("derivedState").asText()));
		RecordLineageTest.check("raw state unchanged", "GOLDEN".equals(json.get("merge").get("state").asText()));
		RecordLineageTest.check("memberCount = 1 matched + 1 (the golden itself)", groupNode.get("memberCount").asInt() == 2);
		final JsonNode matched = groupNode.get("matched").get(0);
		RecordLineageTest.check("matched member's key/label/sourceSystem carried", "2".equals(matched.get("masterKey").asText())
			&& "BPCE Retail".equals(matched.get("label").asText()) && "SAP".equals(matched.get("sourceSystem").asText()));
		RecordLineageTest.check("matched member never carries a processId", matched.get("processId") == null);
		final JsonNode suspect = groupNode.get("suspects").get(0);
		RecordLineageTest.check("suspect's key/label/sourceSystem/processId carried", "141".equals(suspect.get("masterKey").asText())
			&& "BPCE CRM copy".equals(suspect.get("label").asText()) && "CRM".equals(suspect.get("sourceSystem").asText()) && "77".equals(suspect.get("processId").asText()));
	}

	/** {@code ebx.MameGroups#CAP} (50) caps both matched and suspects independently - a fixture of 60 suspects proves the model carries the truncation flag through, whatever the reader hands it (this class never re-derives the cap itself). */
	private static void group_sixtySuspects_cappedAtFiftyMarkedTruncated()
	{
		final List<RecordLineage.GroupMember> sixty = RecordLineageTest.membersOf(60); // simulates what the reader would have ALREADY capped to 50 before building GroupInfo - here we exercise the model's own "truncated" flag pass-through directly instead of re-deriving the cap
		final List<RecordLineage.GroupMember> fifty = sixty.subList(0, 50);
		final RecordLineage.GroupInfo group = new RecordLineage.GroupInfo("1", "Group 1", 1, Collections.<RecordLineage.GroupMember>emptyList(), fifty, true, true);
		final ObjectNode json = RecordLineageTest.assembleWithMerge(MergeInfo.present("GOLDEN", "1", "1", false, "GOLDEN_WITH_SUSPECTS", group));
		final JsonNode groupNode = json.get("merge").get("group");
		RecordLineageTest.check("exactly 50 suspects carried (capped)", groupNode.get("suspects").size() == 50);
		RecordLineageTest.check("truncated flag set", groupNode.get("truncated").asBoolean());
	}

	/** A hidden suspect/matched member renders as {restricted:true} ONLY - no masterKey, no label, no sourceSystem, no processId, even though the fixture deliberately still carries real values (same defense-in-depth discipline as every other redaction test in this file). */
	private static void group_hiddenSuspect_restrictedOnlyNoKeyLeaked()
	{
		final RecordLineage.GroupMember hidden = RecordLineage.GroupMember.restricted();
		final RecordLineage.GroupInfo group = new RecordLineage.GroupInfo("1", "Group 1", 1, Collections.<RecordLineage.GroupMember>emptyList(),
			RecordLineage.listOf(hidden), false, false);
		final ObjectNode json = RecordLineageTest.assembleWithMerge(MergeInfo.present("GOLDEN", "1", "1", false, "GOLDEN", group));
		final JsonNode suspect = json.get("merge").get("group").get("suspects").get(0);
		RecordLineageTest.check("restricted true", suspect.get("restricted").asBoolean());
		RecordLineageTest.check("exactly one key (restricted) - nothing else leaks", suspect.size() == 1);
	}

	private static void group_actionsCanAcceptSuspects_carriedThrough()
	{
		final RecordLineage.GroupInfo canAct = new RecordLineage.GroupInfo("1", "G", 1, Collections.<RecordLineage.GroupMember>emptyList(),
			RecordLineageTest.membersOf(1), false, true);
		final RecordLineage.GroupInfo cannotAct = new RecordLineage.GroupInfo("1", "G", 1, Collections.<RecordLineage.GroupMember>emptyList(),
			RecordLineageTest.membersOf(1), false, false);
		RecordLineageTest.check("canAcceptSuspects true carried through",
			RecordLineageTest.assembleWithMerge(MergeInfo.present("GOLDEN", "1", "1", false, "GOLDEN_WITH_SUSPECTS", canAct)).get("merge").get("group").get("actions")
				.get("canAcceptSuspects").asBoolean());
		RecordLineageTest.check("canAcceptSuspects false carried through",
			!RecordLineageTest.assembleWithMerge(MergeInfo.present("GOLDEN", "1", "1", false, "GOLDEN_WITH_SUSPECTS", cannotAct)).get("merge").get("group").get("actions")
				.get("canAcceptSuspects").asBoolean());
	}

	/** {@code suspectSources} is a SEPARATE top-level array from {@code sources} - a suspect lane must never be mixed into the confirmed-contributor list the "champ par champ" field table's own column set is built from. */
	private static void suspectSources_separateArrayNeverMixedIntoSources()
	{
		final SourceChain contributing = RecordLineageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1");
		final SourceChain suspectLane = new SourceChain("141", false, "CRM", false, "ACC-1", "Companies_Landing", "ACCOUNT", "ACC-1", "L1", "STAGED", new Date(0L),
			"Company", "CRM|ACC-1", null, null, null, true);
		RecordLineageTest.check("a suspect lane really is marked suspect", suspectLane.suspect);
		RecordLineageTest.check("a plain contributing chain is never marked suspect", !contributing.suspect);

		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			RecordLineage.listOf(contributing), Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null, null, null,
			RecordLineage.listOf(suspectLane)));
		RecordLineageTest.check("exactly one entry in sources[]", json.get("sources").size() == 1);
		RecordLineageTest.check("exactly one entry in suspectSources[]", json.get("suspectSources").size() == 1);
		RecordLineageTest.check("the suspectSources entry is marked suspect:true", json.get("suspectSources").get(0).get("suspect").asBoolean());
		RecordLineageTest.check("the sources entry carries no suspect flag at all", json.get("sources").get(0).get("suspect") == null);
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	private static SourceChain chain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final String pSourceId, final String pStagingKey)
	{
		return RecordLineageTest.chain(pMasterKey, pGolden, pSourceSystem, false, pSourceId, pStagingKey);
	}

	private static SourceChain chain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final boolean pRestricted, final String pSourceId,
		final String pStagingKey)
	{
		return new SourceChain(pMasterKey, pGolden, pSourceSystem, pRestricted, pSourceId, "Companies_Landing",
			"SUPPLIER".equals(pSourceSystem) || "ERP".equals(pSourceSystem) ? "SUPPLIER" : "ACCOUNT", pSourceId, "L1", "STAGED", new Date(0L), "Company", pStagingKey);
	}

	private static FieldContribution contribution(final String pMasterKey, final String pSourceSystem, final String pLandingColumn, final String pRawValue,
		final String pStagingValue, final List<String> pCleaningSteps)
	{
		return new FieldContribution(pMasterKey, pSourceSystem, false, pLandingColumn, pRawValue, pStagingValue, pCleaningSteps);
	}

	private static FieldContribution restrictedContribution(final String pMasterKey, final String pSourceSystem)
	{
		// deliberately still carries values, to prove assemble() itself redacts them rather than trusting the caller
		return new FieldContribution(pMasterKey, pSourceSystem, true, "SECRET_COLUMN", "should never be emitted", "should never be emitted",
			Collections.singletonList("TRIM"));
	}

	private static Map<String, FieldValue> recordFields(final String pField, final String pValue)
	{
		final Map<String, FieldValue> out = new LinkedHashMap<String, FieldValue>();
		out.put(pField, FieldValue.of(pValue));
		return out;
	}

	// ------------------------------------------------------------------------------------------------------ tests

	/** A record fed by exactly one system: one chain entry, one contribution per field, never "differs". */
	private static void oneSystem_noMatchingYet_singleChainNoDiffer()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("name",
			RecordLineage.listOf(RecordLineageTest.contribution("1", "ERP", "LEGAL_NAME", "Acme Industries", "Acme Industries", Collections.<String>emptyList()))));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineageTest.recordFields("name", "Acme Industries"),
			sources, fields, MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));

		RecordLineageTest.check("one source in the chain", json.get("sources").size() == 1);
		RecordLineageTest.check("merge is absent", !json.get("merge").get("present").asBoolean());
		RecordLineageTest.check("reason is NO_MATCHING_YET", MergeInfo.NO_MATCHING_YET.equals(json.get("merge").get("reason").asText()));
		RecordLineageTest.check("no detail on a plain absent reason", json.get("merge").get("detail") == null);
		RecordLineageTest.check("one field", json.get("fields").size() == 1);
		RecordLineageTest.check("a single source never differs", !json.get("fields").get(0).get("differs").asBoolean());
		RecordLineageTest.check("no restricted record fields", json.get("record").get("restrictedFields").size() == 0);
	}

	/**
	 * Two systems disagreeing on a field's casing ONLY ("Paris" vs "PARIS") must NOT be flagged as differing -
	 * product-owner decision, 2026-09-22, coordinator review, fixing a reported false disagreement (live
	 * record Companies_Master/Master//root/Company id 108, "BANK OF CHINA"/"Bank of China": a single-source
	 * record showed a "differs"-shaped DIFF badge with the value struck through purely from case, read by the
	 * user as "why is this value rejected?"). {@code differs} is now judged on the FOLDED comparable value
	 * (trim/collapse-whitespace/strip-accents/upper-case, {@code CleaningSteps.foldKey}) - this method's own
	 * name/assertion is the inverse of what it was before that decision (a genuine, non-fold-equivalent
	 * difference is covered separately by {@link #twoSystems_genuinelyDifferentCity_stillDiffers}).
	 */
	private static void twoSystems_caseOnlyCityDifference_noLongerDiffers()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"),
			RecordLineageTest.chain("2", false, "CRM", "ACC-1", "CRM|ACC-1"));
		final List<FieldContribution> cityContributions = Arrays.asList(
			RecordLineageTest.contribution("1", "ERP", "CITY", "PARIS", "PARIS", Collections.<String>emptyList()),
			RecordLineageTest.contribution("2", "CRM", "BILLING_CITY", "Paris", "Paris", Collections.<String>emptyList()));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("city", cityContributions));
		final ObjectNode json = RecordLineage.assemble(
			new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources, fields,
				MergeInfo.present("MERGED", "42", "1", false), null));

		RecordLineageTest.check("two contributions for city", json.get("fields").get(0).get("contributions").size() == 2);
		RecordLineageTest.check("PARIS vs Paris no longer differs - a case-only difference", !json.get("fields").get(0).get("differs").asBoolean());
	}

	/** A genuine difference (not just case/accents/whitespace) still differs - the fold fix must not paper over a real disagreement. */
	private static void twoSystems_genuinelyDifferentCity_stillDiffers()
	{
		final List<FieldContribution> cityContributions = Arrays.asList(
			RecordLineageTest.contribution("1", "ERP", "CITY", "Paris", "Paris", Collections.<String>emptyList()),
			RecordLineageTest.contribution("2", "CRM", "BILLING_CITY", "Berlin", "Berlin", Collections.<String>emptyList()));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("city", cityContributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), fields, MergeInfo.present("MERGED", "42", "1", false), null));

		RecordLineageTest.check("Paris vs Berlin genuinely differs", json.get("fields").get(0).get("differs").asBoolean());
	}

	/**
	 * {@code equivalentToGolden}: true only for a case/accent/whitespace-only difference from the golden
	 * value, covering all three fold dimensions separately (product-owner decision, 2026-09-22).
	 */
	private static void equivalentToGolden_caseAccentAndWhitespaceOnlyDifferences()
	{
		RecordLineageTest.checkEquivalent("case-only difference", "BANK OF CHINA", "Bank of China", true);
		RecordLineageTest.checkEquivalent("accent-only difference", "BANQUE GENERALE", "Banque Générale", true);
		RecordLineageTest.checkEquivalent("whitespace-only difference", "ACME  INDUSTRIES", "Acme Industries", true);
		RecordLineageTest.checkEquivalent("a genuine difference is never equivalent", "Bank of China", "Bank of Japan", false);
		RecordLineageTest.checkEquivalent("an exact match is never marked equivalent (matchesGolden already says so)", "Acme", "Acme", null);
	}

	/** Fixture helper for {@link #equivalentToGolden_caseAccentAndWhitespaceOnlyDifferences}: one contribution against one golden value, checking {@code equivalentToGolden}. {@code pExpected} is {@code null} for the exact-match case (checked separately as "false", since it is emitted, never absent). */
	private static void checkEquivalent(final String pWhat, final String pGoldenValue, final String pContributionValue, final Boolean pExpectedEquivalent)
	{
		final List<FieldContribution> contributions = RecordLineage
			.listOf(RecordLineageTest.contribution("1", "ERP", "NAME", pContributionValue, pContributionValue, Collections.<String>emptyList()));
		final List<FieldContribution> withDecoy = Arrays.asList(contributions.get(0), RecordLineageTest.contribution("2", "CRM", "NAME", "decoy", "decoy", Collections.<String>emptyList()));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineageTest.recordFields("name", pGoldenValue),
			Collections.<SourceChain>emptyList(), RecordLineage.listOf(new FieldInput("name", withDecoy)), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		final JsonNode equivalent = json.get("fields").get(0).get("contributions").get(0).get("equivalentToGolden");
		if (pExpectedEquivalent == null)
		{
			RecordLineageTest.check(pWhat + ": exact match is equivalentToGolden false, not null/true", equivalent.isBoolean() && !equivalent.asBoolean());
		}
		else
		{
			RecordLineageTest.check(pWhat, equivalent.isBoolean() && equivalent.asBoolean() == pExpectedEquivalent.booleanValue());
		}
	}

	/** No golden value to compare against: {@code equivalentToGolden} is null (unknown), same guard as {@code matchesGolden}. */
	private static void equivalentToGolden_noGoldenValue_isNull()
	{
		final List<FieldContribution> contributions = RecordLineage
			.listOf(RecordLineageTest.contribution("1", "ERP", "NAME", "Bank of China", "Bank of China", Collections.<String>emptyList()));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), RecordLineage.listOf(new FieldInput("name", contributions)), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		RecordLineageTest.check("no golden value -> equivalentToGolden is null", json.get("fields").get(0).get("contributions").get(0).get("equivalentToGolden").isNull());
	}

	/** A field with an accepted cleaning-step chain (country -> ISO2) carries every step, in order, in the JSON. */
	private static void fieldWithCleaningSteps_stepsCarriedInOrder()
	{
		final List<FieldContribution> countryContributions = RecordLineage
			.listOf(RecordLineageTest.contribution("1", "CRM", "BILLING_COUNTRY", "France", "FR", Arrays.asList("TRIM", "COUNTRY_TO_ISO2")));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("country", countryContributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			RecordLineage.listOf(RecordLineageTest.chain("1", true, "CRM", "ACC-1", "CRM|ACC-1")), fields, MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));

		final JsonNode steps = json.get("fields").get(0).get("contributions").get(0).get("cleaningSteps");
		RecordLineageTest.check("two steps", steps.size() == 2);
		RecordLineageTest.check("TRIM first", "TRIM".equals(steps.get(0).asText()));
		RecordLineageTest.check("COUNTRY_TO_ISO2 second", "COUNTRY_TO_ISO2".equals(steps.get(1).asText()));
		RecordLineageTest.check("raw value kept", "France".equals(json.get("fields").get(0).get("contributions").get(0).get("rawValue").asText()));
		RecordLineageTest.check("staged value is the cleaned one", "FR".equals(json.get("fields").get(0).get("contributions").get(0).get("stagingValue").asText()));
	}

	/** Every documented reason for "merge information absent" renders the same shape, never an error. */
	private static void mergeAbsent_eachReason()
	{
		for (final String reason : Arrays.asList(MergeInfo.NO_MATCHING_YET, MergeInfo.ADDON_NOT_AVAILABLE, MergeInfo.NO_METADATA))
		{
			final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
				Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(), MergeInfo.absent(reason), null));
			RecordLineageTest.check("present is false for " + reason, !json.get("merge").get("present").asBoolean());
			RecordLineageTest.check("reason echoed for " + reason, reason.equals(json.get("merge").get("reason").asText()));
			RecordLineageTest.check("no state leaks through for " + reason, json.get("merge").get("state") == null);
		}
	}

	/** A read failure in the merge block degrades to METADATA_UNREADABLE with a non-sensitive diagnostic detail - never an exception reaching the caller. */
	private static void mergeAbsent_metadataUnreadable_carriesDetail()
	{
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), Collections.<FieldInput>emptyList(),
			MergeInfo.absent(MergeInfo.METADATA_UNREADABLE, "PathAccessException: no child named 'goldenId'"), null));
		RecordLineageTest.check("present is false", !json.get("merge").get("present").asBoolean());
		RecordLineageTest.check("reason is METADATA_UNREADABLE", MergeInfo.METADATA_UNREADABLE.equals(json.get("merge").get("reason").asText()));
		RecordLineageTest.check("detail carried through", json.get("merge").get("detail").asText().startsWith("PathAccessException"));
	}

	/** A golden with two merged sources: both appear in the chain (one flagged golden), and a field they both fed lists both contributions. */
	private static void goldenWithTwoMergedSources()
	{
		final List<SourceChain> sources = Arrays.asList(RecordLineageTest.chain("12", true, "ERP", "SUP-1", "ERP|SUP-1"),
			RecordLineageTest.chain("7", false, "CRM", "ACC-1", "CRM|ACC-1"));
		final List<FieldContribution> vatContributions = Arrays.asList(
			RecordLineageTest.contribution("12", "ERP", "VAT_NO", "FR12345678901", "FR12345678901", Collections.<String>emptyList()),
			RecordLineageTest.contribution("7", "CRM", null, null, null, Collections.<String>emptyList()));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("vatNo", vatContributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "12", new LinkedHashMap<String, FieldValue>(), sources, fields,
			MergeInfo.present("GOLDEN", "42", "12", false), null));

		RecordLineageTest.check("two contributing master records", json.get("sources").size() == 2);
		int goldenCount = 0;
		for (final JsonNode source : json.get("sources"))
		{
			if (source.get("golden").asBoolean())
			{
				goldenCount++;
				RecordLineageTest.check("the golden entry is master key 12", "12".equals(source.get("masterKey").asText()));
			}
		}
		RecordLineageTest.check("exactly one entry flagged golden", goldenCount == 1);
		RecordLineageTest.check("merge state is GOLDEN", "GOLDEN".equals(json.get("merge").get("state").asText()));
		RecordLineageTest.check("goldenKey points at the anchor", "12".equals(json.get("merge").get("goldenKey").asText()));
	}

	/** A field only one of two sources feeds must not throw, and must not be reported as differing (only one real value exists). */
	private static void fieldMissingFromOneSource_noExceptionNoSpuriousDiffer()
	{
		final List<FieldContribution> contributions = Arrays.asList(
			RecordLineageTest.contribution("1", "ERP", "LEI", "969500...", "969500...", Collections.<String>emptyList()),
			RecordLineageTest.contribution("2", "CRM", null, null, null, Collections.<String>emptyList()));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("lei", contributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), fields, MergeInfo.present("MERGED", "1", "1", false), null));

		RecordLineageTest.check("no exception, both contributions present", json.get("fields").get(0).get("contributions").size() == 2);
		RecordLineageTest.check("a lone real value never differs", !json.get("fields").get(0).get("differs").asBoolean());
		RecordLineageTest.check("the missing side has a null staging value", json.get("fields").get(0).get("contributions").get(1).get("stagingValue").isNull());
	}

	/** A composite staging key containing the '|' separator is carried through unmodified - never re-split or reinterpreted (CLAUDE.md sec 9). */
	private static void compositeKeyContainingPipe_carriedThroughUnmodified()
	{
		final String pipeKey = "CRM|001N9Jkq|extra";
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageTest.chain("1", true, "CRM", "001N9Jkq|extra", pipeKey));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Contact", "/root/Contact", "1", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));

		RecordLineageTest.check("staging key kept exactly as given, pipes and all",
			pipeKey.equals(json.get("sources").get(0).get("staging").get("key").asText()));
		RecordLineageTest.check("sourceId kept exactly as given, pipes and all",
			"001N9Jkq|extra".equals(json.get("sources").get(0).get("sourceId").asText()));
	}

	/** A restricted source (landing or staging record hidden) is rendered with its system name only - no key, no values - EVEN THOUGH the fixture below deliberately still carries a sourceId/staging key, to prove assemble() itself redacts rather than trusting the caller. */
	private static void restrictedSource_onlySystemNameEmitted()
	{
		final SourceChain restricted = RecordLineageTest.chain("2", false, "CRM", true, "SHOULD-NOT-LEAK", "SHOULD-NOT-LEAK-EITHER");
		final List<SourceChain> sources = Arrays.asList(RecordLineageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"), restricted);
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(), sources,
			Collections.<FieldInput>emptyList(), MergeInfo.present("MERGED", "1", "1", false), null));

		JsonNode restrictedNode = null;
		for (final JsonNode node : json.get("sources"))
		{
			if (node.get("restricted") != null && node.get("restricted").asBoolean())
			{
				restrictedNode = node;
			}
		}
		RecordLineageTest.check("a restricted source is present", restrictedNode != null);
		RecordLineageTest.check("only masterKey/golden/sourceSystem/restricted are emitted", restrictedNode.size() == 4);
		RecordLineageTest.check("sourceSystem is kept", "CRM".equals(restrictedNode.get("sourceSystem").asText()));
		RecordLineageTest.check("sourceId never leaks", restrictedNode.get("sourceId") == null);
		RecordLineageTest.check("no landing block leaks", restrictedNode.get("landing") == null);
		RecordLineageTest.check("no staging block leaks", restrictedNode.get("staging") == null);
	}

	/** A field hidden on one of two sources: that contribution shows only restricted:true (values redacted even though the fixture supplies them), and "differs" becomes null (unknown), never false. */
	private static void restrictedFieldOnOneOfTwoSources_valueOmittedDiffersNull()
	{
		final List<FieldContribution> contributions = Arrays.asList(RecordLineageTest.contribution("1", "ERP", "VAT_NO", "FR1", "FR1", Collections.<String>emptyList()),
			RecordLineageTest.restrictedContribution("2", "CRM"));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("vatNo", contributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), fields, MergeInfo.present("MERGED", "1", "1", false), null));

		final JsonNode contributionsNode = json.get("fields").get(0).get("contributions");
		JsonNode restrictedNode = null;
		for (final JsonNode node : contributionsNode)
		{
			if (node.get("restricted") != null && node.get("restricted").asBoolean())
			{
				restrictedNode = node;
			}
		}
		RecordLineageTest.check("a restricted contribution is present", restrictedNode != null);
		RecordLineageTest.check("only masterKey/sourceSystem/restricted are emitted", restrictedNode.size() == 3);
		RecordLineageTest.check("no rawValue leaks", restrictedNode.get("rawValue") == null);
		RecordLineageTest.check("no stagingValue leaks", restrictedNode.get("stagingValue") == null);
		RecordLineageTest.check("no landingColumn leaks", restrictedNode.get("landingColumn") == null);
		RecordLineageTest.check("no cleaningSteps leak", restrictedNode.get("cleaningSteps") == null);
		RecordLineageTest.check("differs is null (unknown), not false", json.get("fields").get(0).get("differs").isNull());
	}

	/** A field hidden on the master record itself is dropped from record.fields and listed by name in restrictedFields - never emitted with a value. */
	private static void restrictedMasterField_droppedFromFieldsListedAsRestricted()
	{
		final Map<String, FieldValue> recordFields = new LinkedHashMap<String, FieldValue>();
		recordFields.put("name", FieldValue.of("Acme Industries SAS"));
		recordFields.put("vatNo", FieldValue.restricted());
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", recordFields, Collections.<SourceChain>emptyList(),
			Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));

		RecordLineageTest.check("visible field kept", "Acme Industries SAS".equals(json.get("record").get("fields").get("name").asText()));
		RecordLineageTest.check("restricted field absent from fields", json.get("record").get("fields").get("vatNo") == null);
		RecordLineageTest.check("restricted field named in restrictedFields", "vatNo".equals(json.get("record").get("restrictedFields").get(0).asText()));
	}

	/** Everything visible: unchanged from the shape before permission redaction existed - no stray "restricted" keys anywhere. */
	private static void allVisible_unchangedShape()
	{
		final List<SourceChain> sources = RecordLineage.listOf(RecordLineageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1"));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("name",
			RecordLineage.listOf(RecordLineageTest.contribution("1", "ERP", "LEGAL_NAME", "Acme Industries", "Acme Industries", Collections.<String>emptyList()))));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineageTest.recordFields("name", "Acme Industries"),
			sources, fields, MergeInfo.present("GOLDEN", "1", "1", false), null));

		RecordLineageTest.check("no restricted flag on the visible source", json.get("sources").get(0).get("restricted") == null);
		RecordLineageTest.check("no restricted flag on the visible contribution", json.get("fields").get(0).get("contributions").get(0).get("restricted") == null);
		RecordLineageTest.check("no restricted record fields", json.get("record").get("restrictedFields").size() == 0);
		RecordLineageTest.check("differs stays a real boolean, not null", json.get("fields").get(0).get("differs").isBoolean());
	}

	// ------------------------------------------------------------------------------ deduced-retained rule (2026-09-21)

	/** Exactly one contributing source matches the golden value: that one is marked retained (deduced) - a logical certainty, not a guess. */
	private static void deducedRetained_uniqueMatch_isDeduced()
	{
		final List<FieldContribution> contributions = Arrays.asList(RecordLineageTest.contribution("1", "SAP", "NAME", "BPCE SA", "BPCE SA", Collections.<String>emptyList()),
			RecordLineageTest.contribution("2", "ERP", "NAME", "BPCE", "BPCE", Collections.<String>emptyList()),
			RecordLineageTest.contribution("3", "CRM", "NAME", "BPCE", "BPCE", Collections.<String>emptyList()));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("name", contributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineageTest.recordFields("name", "BPCE SA"),
			Collections.<SourceChain>emptyList(), fields, MergeInfo.present("GOLDEN", "118", "1", false), null));

		final JsonNode contributionsNode = json.get("fields").get(0).get("contributions");
		RecordLineageTest.check("golden value is echoed on the field", "BPCE SA".equals(json.get("fields").get(0).get("goldenValue").asText()));
		RecordLineageTest.check("the SAP contribution matches the golden value", contributionsNode.get(0).get("matchesGolden").asBoolean());
		RecordLineageTest.check("the SAP contribution is retained", contributionsNode.get(0).get("retained").asBoolean());
		RecordLineageTest.check("the reason is DEDUCED, not KNOWN", "DEDUCED".equals(contributionsNode.get(0).get("retainedReason").asText()));
		RecordLineageTest.check("the two other, non-matching contributions are not retained", !contributionsNode.get(1).get("retained").asBoolean()
			&& !contributionsNode.get(2).get("retained").asBoolean());
		RecordLineageTest.check("a non-matching contribution has no retainedReason", contributionsNode.get(1).get("retainedReason") == null);
	}

	/** Two contributing sources both carry the golden value: genuinely ambiguous - neither is guessed to be THE retained one. */
	private static void deducedRetained_twoMatches_noneRetained()
	{
		final List<FieldContribution> contributions = Arrays.asList(RecordLineageTest.contribution("1", "ERP", "COUNTRY", "FR", "FR", Collections.<String>emptyList()),
			RecordLineageTest.contribution("2", "SAP", "COUNTRY", "FR", "FR", Collections.<String>emptyList()));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("country", contributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineageTest.recordFields("country", "FR"),
			Collections.<SourceChain>emptyList(), fields, MergeInfo.present("GOLDEN", "118", "1", false), null));

		final JsonNode contributionsNode = json.get("fields").get(0).get("contributions");
		RecordLineageTest.check("both agree with the golden value", contributionsNode.get(0).get("matchesGolden").asBoolean()
			&& contributionsNode.get(1).get("matchesGolden").asBoolean());
		RecordLineageTest.check("neither is retained - two matches is ambiguous, never guessed", !contributionsNode.get(0).get("retained").asBoolean()
			&& !contributionsNode.get(1).get("retained").asBoolean());
	}

	/** No contributing source carries the golden value at all (e.g. it was typed by a data steward): nothing is retained. */
	private static void deducedRetained_noMatch_noneRetained()
	{
		final List<FieldContribution> contributions = Arrays.asList(RecordLineageTest.contribution("1", "ERP", "NAME", "BPCE", "BPCE", Collections.<String>emptyList()),
			RecordLineageTest.contribution("2", "SAP", "NAME", "Groupe BPCE", "Groupe BPCE", Collections.<String>emptyList()));
		final List<FieldInput> fields = RecordLineage.listOf(new FieldInput("name", contributions));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineageTest.recordFields("name", "BPCE SA"),
			Collections.<SourceChain>emptyList(), fields, MergeInfo.present("GOLDEN", "118", "1", false), null));

		final JsonNode contributionsNode = json.get("fields").get(0).get("contributions");
		RecordLineageTest.check("neither contribution matches the golden value", !contributionsNode.get(0).get("matchesGolden").asBoolean()
			&& !contributionsNode.get(1).get("matchesGolden").asBoolean());
		RecordLineageTest.check("nothing retained when no source matches", !contributionsNode.get(0).get("retained").asBoolean()
			&& !contributionsNode.get(1).get("retained").asBoolean());
	}

	/** A KNOWN retained answer (the FieldMergeLineageSource seam, once implemented) wins over the deduction, even when the deduction would have picked a different, agreeing source. */
	private static void knownRetained_takesPrecedenceOverDeduction()
	{
		final List<FieldContribution> contributions = Arrays.asList(RecordLineageTest.contribution("1", "ERP", "NAME", "BPCE SA", "BPCE SA", Collections.<String>emptyList()),
			RecordLineageTest.contribution("2", "SAP", "NAME", "BPCE SA", "BPCE SA", Collections.<String>emptyList()));
		// two sources agree with the golden value - the DEDUCED rule alone would retain neither (ambiguous) -
		// but a KNOWN answer names source "2" specifically, and that one wins.
		final FieldInput field = new FieldInput("name", contributions, "2");
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineageTest.recordFields("name", "BPCE SA"),
			Collections.<SourceChain>emptyList(), RecordLineage.listOf(field), MergeInfo.present("GOLDEN", "118", "1", false), null));

		final JsonNode contributionsNode = json.get("fields").get(0).get("contributions");
		RecordLineageTest.check("the named source is retained", contributionsNode.get(1).get("retained").asBoolean());
		RecordLineageTest.check("the reason is KNOWN, not DEDUCED", "KNOWN".equals(contributionsNode.get(1).get("retainedReason").asText()));
		RecordLineageTest.check("the other agreeing source is NOT retained despite also matching", !contributionsNode.get(0).get("retained").asBoolean());
	}

	/** No golden value at all (the master field is itself restricted): matchesGolden/retained answer null/false for every contribution - never guessed from an unknown anchor. */
	private static void noGoldenValue_matchesGoldenIsNullNeverRetained()
	{
		final List<FieldContribution> contributions = RecordLineage
			.listOf(RecordLineageTest.contribution("1", "ERP", "NAME", "BPCE SA", "BPCE SA", Collections.<String>emptyList()));
		final Map<String, FieldValue> recordFields = new LinkedHashMap<String, FieldValue>();
		recordFields.put("name", FieldValue.restricted());
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", recordFields, Collections.<SourceChain>emptyList(),
			RecordLineage.listOf(new FieldInput("name", contributions)), MergeInfo.present("GOLDEN", "118", "1", false), null));

		final JsonNode fieldNode = json.get("fields").get(0);
		RecordLineageTest.check("goldenRestricted is true", fieldNode.get("goldenRestricted").asBoolean());
		RecordLineageTest.check("no goldenValue leaks", fieldNode.get("goldenValue") == null);
		RecordLineageTest.check("matchesGolden is null (unknown), never a guess", fieldNode.get("contributions").get(0).get("matchesGolden").isNull());
		RecordLineageTest.check("never retained without a readable golden value", !fieldNode.get("contributions").get(0).get("retained").asBoolean());
	}

	/** A restricted contribution is never marked retained, even when its (deliberately still-carried, per the fixture) value would otherwise be the unique match - the "restricted" branch returns before the retained computation ever runs. */
	private static void restrictedContributionNeverRetainedEvenIfItWouldMatch()
	{
		final List<FieldContribution> contributions = Arrays.asList(RecordLineageTest.restrictedContribution("2", "CRM"),
			RecordLineageTest.contribution("1", "ERP", "NAME", "something else", "something else", Collections.<String>emptyList()));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineageTest.recordFields("name", "should never be emitted"),
			Collections.<SourceChain>emptyList(), RecordLineage.listOf(new FieldInput("name", contributions)), MergeInfo.present("GOLDEN", "118", "1", false), null));

		final JsonNode restrictedNode = json.get("fields").get(0).get("contributions").get(0);
		RecordLineageTest.check("still only the redacted shape - no retained key at all", restrictedNode.get("retained") == null);
		RecordLineageTest.check("no matchesGolden either", restrictedNode.get("matchesGolden") == null);
	}

	/** "Open in EBX" links are carried through verbatim for a visible source/master, and NEVER emitted for a restricted source - even when the fixture deliberately still supplies one, proving assemble() redacts it rather than trusting the caller (same discipline as every other restricted field). */
	private static void openUrls_carriedThroughAndRedactedWhenRestricted()
	{
		final SourceChain visible = new SourceChain("1", true, "ERP", false, "SUP-1", "Companies_Landing", "SUPPLIER", "SUP-1", "L1", "STAGED", new Date(0L), "Company",
			"ERP|SUP-1", "/ebx-ui/ui/advanced/data/BCompanies_Landing/Companies_Landing/root/SUPPLIER?predicate=./id%3D%271%27",
			"/ebx-ui/ui/advanced/data/BCompanies_Staging/Companies_Staging/root/Company?predicate=./id%3D%271%27");
		final SourceChain restrictedButUrlSupplied = new SourceChain("2", false, "CRM", true, "SHOULD-NOT-LEAK", "Companies_Landing", "ACCOUNT", "SHOULD-NOT-LEAK", "L1",
			"STAGED", new Date(0L), "Company", "SHOULD-NOT-LEAK-EITHER", "https://should-not-leak.example/landing", "https://should-not-leak.example/staging");
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Arrays.asList(visible, restrictedButUrlSupplied), Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null,
			"/ebx-ui/ui/advanced/data/BCompanies_Master/Companies_Master/root/Company?predicate=./id%3D%271%27"));

		RecordLineageTest.check("masterUrl carried through", json.get("masterUrl").asText().contains("Companies_Master"));
		JsonNode visibleNode = null;
		JsonNode restrictedNode = null;
		for (final JsonNode node : json.get("sources"))
		{
			if ("1".equals(node.get("masterKey").asText()))
			{
				visibleNode = node;
			}
			else
			{
				restrictedNode = node;
			}
		}
		RecordLineageTest.check("the visible source's landing/staging urls are carried through", visibleNode.get("landing").get("url").asText().contains("SUPPLIER")
			&& visibleNode.get("staging").get("url").asText().contains("Company"));
		RecordLineageTest.check("the restricted source emits no url anywhere, despite the fixture supplying one", restrictedNode.get("landing") == null
			&& restrictedNode.get("staging") == null && restrictedNode.size() == 4);
	}

	// ------------------------------------------------------------------------- single-source rule (2026-09-22)

	/**
	 * A field with exactly ONE contribution trivially "matches" the golden value (there is no other candidate
	 * it could have come from) - coordinator review, 2026-09-22, live record Company 183 (BPCE, ERP only):
	 * every one of six fields showed "retained (deduced)", which is technically true and tells the reader
	 * nothing. The deduction must only ever fire once a field has more than one contribution to choose among.
	 */
	private static void singleContribution_neverDeducedRetained_evenThoughItTriviallyMatches()
	{
		final List<FieldContribution> contributions = RecordLineage
			.listOf(RecordLineageTest.contribution("1", "ERP", "NAME", "BPCE SA", "BPCE SA", Collections.<String>emptyList()));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", RecordLineageTest.recordFields("name", "BPCE SA"),
			Collections.<SourceChain>emptyList(), RecordLineage.listOf(new FieldInput("name", contributions)), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));

		final JsonNode contributionNode = json.get("fields").get(0).get("contributions").get(0);
		RecordLineageTest.check("the single contribution still matches the golden value", contributionNode.get("matchesGolden").asBoolean());
		RecordLineageTest.check("but it is never marked retained - a single source is never a deduction", !contributionNode.get("retained").asBoolean());
		RecordLineageTest.check("no retainedReason either", contributionNode.get("retainedReason") == null);
	}

	/** {@code FieldInput#fieldLabel} passes through as {@code fieldLabel} on the field node when present, and is absent (never a blank string) otherwise - {@code RecordLineagePage}/the graphic screen fall back to the bare {@code field} name themselves. */
	private static void fieldLabelPassesThroughWhenPresent_absentOtherwise()
	{
		final FieldInput withLabel = new FieldInput("vatNo", Collections.<FieldContribution>emptyList(), null, null, "VAT number");
		final ObjectNode jsonWithLabel = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), RecordLineage.listOf(withLabel), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		RecordLineageTest.check("the label passes through", "VAT number".equals(jsonWithLabel.get("fields").get(0).get("fieldLabel").asText()));

		final FieldInput withoutLabel = new FieldInput("vatNo", Collections.<FieldContribution>emptyList());
		final ObjectNode jsonWithoutLabel = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			Collections.<SourceChain>emptyList(), RecordLineage.listOf(withoutLabel), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null));
		RecordLineageTest.check("no fabricated label when the caller sent none", jsonWithoutLabel.get("fields").get(0).get("fieldLabel") == null);
	}

	// -------------------------------------------------------------------- "compare with suspects" (2026-09-22)

	private static Map<String, FieldValue> fields(final Object... pNameValuePairs)
	{
		final Map<String, FieldValue> out = new LinkedHashMap<String, FieldValue>();
		for (int i = 0; i < pNameValuePairs.length; i += 2)
		{
			final String name = (String) pNameValuePairs[i];
			final Object value = pNameValuePairs[i + 1];
			out.put(name, value == RESTRICTED ? FieldValue.restricted() : FieldValue.of((String) value));
		}
		return out;
	}

	/** Sentinel passed to {@link #fields} to mean "this field is restricted" - never a real value. */
	private static final Object RESTRICTED = new Object();

	private static ObjectNode assembleWithComparison(final Map<String, FieldValue> pAnchorFields, final List<FieldInput> pFieldInputs,
		final List<PlanFieldRef> pPlanFields, final List<SuspectFieldSet> pSuspectFieldSets)
	{
		return RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", pAnchorFields, Collections.<SourceChain>emptyList(),
			pFieldInputs == null ? Collections.<FieldInput>emptyList() : pFieldInputs, MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null, null, null,
			Collections.<SourceChain>emptyList(), pPlanFields, pSuspectFieldSets));
	}

	/** No suspect field sets at all -> no `comparison` object whatsoever, not even an empty one (nothing to decide). */
	private static void comparison_absentWhenNoSuspectFieldSets()
	{
		final ObjectNode json = RecordLineageTest.assembleWithComparison(RecordLineageTest.fields("name", "Acme"), null, null,
			Collections.<SuspectFieldSet>emptyList());
		RecordLineageTest.check("no comparison node when there is nothing to compare", !json.has("comparison"));
	}

	/** One suspect, one field of each chip kind - EQ (identical), EQUIVALENT (case/accent/space fold), DIFF (genuinely different), EMPTY (suspect has none). */
	private static void comparison_oneSuspect_chipsForEachCase()
	{
		final Map<String, FieldValue> anchor = RecordLineageTest.fields("name", "Bank of China", "country", "FR", "city", "Lyon", "notes", null);
		final SuspectFieldSet suspect = SuspectFieldSet.of("141",
			RecordLineageTest.fields("name", "Bank of China", "country", "fr", "city", "Paris", "notes", null));
		final ObjectNode json = RecordLineageTest.assembleWithComparison(anchor, Collections.<FieldInput>emptyList(), Collections.<PlanFieldRef>emptyList(),
			RecordLineage.listOf(suspect));
		final JsonNode suspectNode = json.get("comparison").get("suspects").get(0);
		RecordLineageTest.check("suspect masterKey carried", "141".equals(suspectNode.get("masterKey").asText()));
		RecordLineageTest.check("EQ for an identical value", "EQ".equals(suspectNode.get("fields").get("name").get("chip").asText()));
		RecordLineageTest.check("EQUIVALENT for a case-only difference", "EQUIVALENT".equals(suspectNode.get("fields").get("country").get("chip").asText()));
		RecordLineageTest.check("DIFF for a genuinely different value", "DIFF".equals(suspectNode.get("fields").get("city").get("chip").asText()));
		RecordLineageTest.check("EMPTY when both sides have no value", "EMPTY".equals(suspectNode.get("fields").get("notes").get("chip").asText()));
	}

	/** A globally restricted suspect (the whole record hidden) carries NOTHING beyond {masterKey, restricted:true} - same all-or-nothing rule as GroupMember. */
	private static void comparison_restrictedSuspect_onlyMasterKeyAndRestrictedFlag()
	{
		final ObjectNode json = RecordLineageTest.assembleWithComparison(RecordLineageTest.fields("name", "Acme"), Collections.<FieldInput>emptyList(),
			Collections.<PlanFieldRef>emptyList(), RecordLineage.listOf(SuspectFieldSet.restricted("141")));
		final JsonNode suspectNode = json.get("comparison").get("suspects").get(0);
		RecordLineageTest.check("restricted true", suspectNode.get("restricted").asBoolean());
		RecordLineageTest.check("no fields object leaked for a restricted suspect", suspectNode.get("fields") == null);
		RecordLineageTest.check("exactly masterKey + restricted, nothing else", suspectNode.size() == 2);
	}

	/** A field hidden on the (visible) suspect's own record renders {restricted:true, chip:UNKNOWN} for that field only - never a value, never a confident chip. */
	private static void comparison_restrictedFieldOnSuspect_unknownChipNoValue()
	{
		final Map<String, FieldValue> suspectFields = new LinkedHashMap<String, FieldValue>();
		suspectFields.put("name", FieldValue.of("Acme"));
		suspectFields.put("vatNo", FieldValue.restricted());
		final ObjectNode json = RecordLineageTest.assembleWithComparison(RecordLineageTest.fields("name", "Acme", "vatNo", "FR123"),
			Collections.<FieldInput>emptyList(), Collections.<PlanFieldRef>emptyList(), RecordLineage.listOf(SuspectFieldSet.of("141", suspectFields)));
		final JsonNode vatNode = json.get("comparison").get("suspects").get(0).get("fields").get("vatNo");
		RecordLineageTest.check("restricted true on the field", vatNode.get("restricted").asBoolean());
		RecordLineageTest.check("chip is UNKNOWN, never a guess", "UNKNOWN".equals(vatNode.get("chip").asText()));
		RecordLineageTest.check("no value leaked", vatNode.get("value") == null);
		RecordLineageTest.check("the visible sibling field is unaffected", "EQ".equals(json.get("comparison").get("suspects").get(0).get("fields").get("name").get("chip").asText()));
	}

	/** A field hidden on the ANCHOR's own record (never even reaching {@code record.fields}) makes the comparison for that field UNKNOWN too, even though the suspect's own value is visible - never a confident chip guessed from only one visible side. */
	private static void comparison_restrictedAnchorField_unknownChipEvenIfSuspectVisible()
	{
		final Map<String, FieldValue> anchor = new LinkedHashMap<String, FieldValue>();
		anchor.put("vatNo", FieldValue.restricted());
		final ObjectNode json = RecordLineageTest.assembleWithComparison(anchor, Collections.<FieldInput>emptyList(), Collections.<PlanFieldRef>emptyList(),
			RecordLineage.listOf(SuspectFieldSet.of("141", RecordLineageTest.fields("vatNo", "FR123"))));
		final JsonNode chip = json.get("comparison").get("suspects").get(0).get("fields").get("vatNo").get("chip");
		RecordLineageTest.check("UNKNOWN when the anchor's own field is restricted", "UNKNOWN".equals(chip.asText()));
	}

	/** {@code comparison.planFields} carries the plan's own fields, in order, with algorithm codes untranslated (a human-words translation is the renderer's job). */
	private static void comparison_planFieldsCarriedInOrderWithAlgorithm()
	{
		final List<PlanFieldRef> plan = Arrays.asList(new PlanFieldRef("name", "ebx-addon-mame@fuzzyFullText"), new PlanFieldRef("country", "ebx-addon-mame@exact"));
		final ObjectNode json = RecordLineageTest.assembleWithComparison(RecordLineageTest.fields("name", "Acme"), Collections.<FieldInput>emptyList(), plan,
			RecordLineage.listOf(SuspectFieldSet.of("141", RecordLineageTest.fields("name", "Acme"))));
		final JsonNode planFieldsNode = json.get("comparison").get("planFields");
		RecordLineageTest.check("two plan fields, in order", planFieldsNode.size() == 2 && "name".equals(planFieldsNode.get(0).get("field").asText())
			&& "country".equals(planFieldsNode.get(1).get("field").asText()));
		RecordLineageTest.check("algorithm code passed through verbatim, not translated", "ebx-addon-mame@fuzzyFullText".equals(planFieldsNode.get(0).get("algorithm").asText()));
	}

	/** Three suspects, as the task brief's own fixture requirement names ("anchor + 3") - every one present, in order, each with its own chips. */
	private static void comparison_threeSuspects_allPresent()
	{
		final List<SuspectFieldSet> suspects = Arrays.asList(SuspectFieldSet.of("141", RecordLineageTest.fields("name", "Acme")),
			SuspectFieldSet.of("142", RecordLineageTest.fields("name", "ACME")), SuspectFieldSet.of("143", RecordLineageTest.fields("name", "Other")));
		final ObjectNode json = RecordLineageTest.assembleWithComparison(RecordLineageTest.fields("name", "Acme"), Collections.<FieldInput>emptyList(),
			Collections.<PlanFieldRef>emptyList(), suspects);
		final JsonNode suspectsNode = json.get("comparison").get("suspects");
		RecordLineageTest.check("three suspects present", suspectsNode.size() == 3);
		RecordLineageTest.check("first EQ", "EQ".equals(suspectsNode.get(0).get("fields").get("name").get("chip").asText()));
		RecordLineageTest.check("second EQUIVALENT (case fold)", "EQUIVALENT".equals(suspectsNode.get(1).get("fields").get("name").get("chip").asText()));
		RecordLineageTest.check("third DIFF", "DIFF".equals(suspectsNode.get(2).get("fields").get("name").get("chip").asText()));
	}

	/** {@code SourceChain#cleaningStepKinds} (fix, 2026-09-22, task brief item D) is null unless a caller sets it (a plain contributing source never does) - carried through {@code sources[]}/{@code suspectSources[]} verbatim when present. */
	private static void cleaningStepKinds_nullForPlainSource_carriedForSuspectWhenSet()
	{
		final SourceChain plain = RecordLineageTest.chain("1", true, "ERP", "SUP-1", "ERP|SUP-1");
		RecordLineageTest.check("a plain fixture chain carries no cleaningStepKinds by default", plain.cleaningStepKinds == null);
		final SourceChain suspectWithKinds = new SourceChain("141", false, "CRM", false, "ACC-1", "Companies_Landing", "ACCOUNT", "ACC-1", "L1", "STAGED", new Date(0L),
			"Company", "CRM|ACC-1", null, null, null, true, Arrays.asList("TRIM"));
		final ObjectNode json = RecordLineage.assemble(new Input("Companies", "Company", "/root/Company", "1", new LinkedHashMap<String, FieldValue>(),
			RecordLineage.listOf(plain), Collections.<FieldInput>emptyList(), MergeInfo.absent(MergeInfo.NO_MATCHING_YET), null, null, null,
			RecordLineage.listOf(suspectWithKinds)));
		RecordLineageTest.check("a plain source never emits cleaningStepKinds in the JSON", json.get("sources").get(0).get("cleaningStepKinds") == null);
		final JsonNode suspectNode = json.get("suspectSources").get(0);
		RecordLineageTest.check("the suspect's own cleaningStepKinds is emitted", suspectNode.get("cleaningStepKinds").size() == 1
			&& "TRIM".equals(suspectNode.get("cleaningStepKinds").get(0).asText()));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
