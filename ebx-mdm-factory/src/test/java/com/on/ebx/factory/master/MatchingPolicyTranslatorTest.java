package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws.
 * <p>
 * {@link MatchingPolicyTranslator} translates the {@code MatchPlan.requirements} JSON shape, whoever wrote
 * it - the deterministic {@code MatchPlanRules} (exact-tier-only since 2026-09-21, see that class'
 * Javadoc) or the language-model pass (which DOES propose a fuzzy/{@code AVERAGE} tier, from real
 * per-attribute metadata rather than a field's name). A two-tier (exact + fuzzy) requirements document is
 * therefore built directly here with {@link #twoTierRequirements}, the same shape either producer emits,
 * rather than through {@code MatchPlanRules} (which no longer ever produces one on its own) - this keeps
 * {@link MatchingPolicyTranslator}'s own nested-decision-tree behaviour covered without depending on
 * removed, company-specific heuristics.
 * <p>
 * <b>2026-09-21, 22:16 fix</b> - every field path this class asserts on now carries the leading slash
 * {@link MatchingPolicyTranslator#toFieldPath} adds (record-relative, `matching_config_schema`'s own
 * documented shape and the real, live {@code PointOfSale} configuration - see that class' own Javadoc). The
 * new tests below ({@link #tonightsRealPlan_unionDeclaresTheFuzzyAnchorToo}, {@link
 * #treeOnlyField_notInClusteringFieldsNorAverageTier_defaultsToWeightOne}, {@link
 * #alreadySlashedField_idempotent}, {@link #groupedFieldPath_keepsItsInternalSlash}) cover the two real bugs
 * tonight's first live {@code matching/configure} call hit, one at a time.
 */
public final class MatchingPolicyTranslatorTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		MatchingPolicyTranslatorTest.twoTierRequirements_translatesToNestedDecisionTree();
		MatchingPolicyTranslatorTest.exactOnlyRequirements_falseNodeGoesStraightToNotMatched();
		MatchingPolicyTranslatorTest.autoMergeFalse_exactTierForcedToSuspected();
		MatchingPolicyTranslatorTest.autoMergeTrue_exactTierStaysMatched_fuzzyTierStillSuspected();
		MatchingPolicyTranslatorTest.mergePolicy_carriesModeAndChains();
		MatchingPolicyTranslatorTest.mergePolicy_useForManualMergeIsTrue();
		MatchingPolicyTranslatorTest.mergePolicy_neverCarriesRelatedTables();
		MatchingPolicyTranslatorTest.tonightsRealPlan_unionDeclaresTheFuzzyAnchorToo();
		MatchingPolicyTranslatorTest.treeOnlyField_notInClusteringFieldsNorAverageTier_defaultsToWeightOne();
		MatchingPolicyTranslatorTest.alreadySlashedField_idempotent();
		MatchingPolicyTranslatorTest.groupedFieldPath_keepsItsInternalSlash();
		MatchingPolicyTranslatorTest.blankFieldName_refusedRatherThanGuessed();
		MatchingPolicyTranslatorTest.matchKeyFieldPath_cityKeyAndNameBothSlashed();
		MatchingPolicyTranslatorTest.mergePolicy_technicalFieldSurvivorsAlwaysPresent();
		MatchingPolicyTranslatorTest.lotM1_goldenSourceIsAutoCreateNewGoldenSource_neverAConstantSurvivor();
		MatchingPolicyTranslatorTest.lotM1_noConstantSurvivorEverLeavesTheTranslator();
		MatchingPolicyTranslatorTest.mergePolicy_sourceKeyFieldGetsNoMergeSurvivor();
		MatchingPolicyTranslatorTest.mergePolicy_recordSelectionTriesMostTrustedSourceFirst();
		MatchingPolicyTranslatorTest.mergePolicy_recordSelectionHintAlreadyMostTrustedSource_singleEntry();
		MatchingPolicyTranslatorTest.mergePolicy_defaultFieldMergeTriesMostTrustedSourceFirst();
		MatchingPolicyTranslatorTest.mergePolicy_sourceKeyFieldNamedLikeATechnicalField_notDuplicated();
		MatchingPolicyTranslatorTest.mergePolicy_businessSurvivorRuleAdded();
		MatchingPolicyTranslatorTest.mergePolicy_businessSurvivorRuleDroppedWhenCollidesWithTechnical();
		MatchingPolicyTranslatorTest.mergePolicy_businessSurvivorRuleStripsTrustedSourceListFromWireChain();
		MatchingPolicyTranslatorTest.mergePolicy_businessSurvivorRuleWithNoSurvivorshipKeyAtAll();
		MatchingPolicyTranslatorTest.mergePolicy_validValueFieldGetsLongestOnlySurvivor();
		MatchingPolicyTranslatorTest.mergePolicy_validValueFieldNamedLikeATechnicalField_notDuplicated();
		MatchingPolicyTranslatorTest.mergePolicy_validValueSurvivorWinsOverBusinessRuleOnSameField();
		MatchingPolicyTranslatorTest.mergePolicy_noValidValueFields_unaffected();
		MatchingPolicyTranslatorTest.technicalSurvivorFieldNames_hasTheFourFixedFields();
		MatchingPolicyTranslatorTest.extractFieldTrustedSourceOverrides_oneFieldWithOverride();
		MatchingPolicyTranslatorTest.extractFieldTrustedSourceOverrides_emptyWithNoOverride();
		MatchingPolicyTranslatorTest.extractFieldTrustedSourceOverrides_ignoresNonTrustedSourceFunctions();
		MatchingPolicyTranslatorTest.extractFieldTrustedSourceOverrides_run30Case_labelMappedToCode();
		MatchingPolicyTranslatorTest.extractFieldTrustedSourceOverrides_unknownEntryDropped_knownEntryKept();
		MatchingPolicyTranslatorTest.extractFieldTrustedSourceOverrides_everyEntryUnknown_overrideDroppedEntirely();
		MatchingPolicyTranslatorTest.relationFields_writtenAsBusinessObjectFields();
		MatchingPolicyTranslatorTest.relationFields_unknownChildEntity_refused();
		MatchingPolicyTranslatorTest.relationFields_noRelationFields_emptyList();
		MatchingPolicyTranslatorTest.relationFields_writtenManual_matchingOnCreationAndUpdateAreZero();
		MatchingPolicyTranslatorTest.ordinaryPlan_matchingOnCreationAndUpdateAreOne();
		// node-naming fix (PersVol5, 2026-09-26 live failure): the node name written must be the plan's label (frozen before the relation fields are rewritten).
		MatchingPolicyTranslatorTest.relationFields_nodeNameKeptPreRewrite_isThePlansLabel();
		MatchingPolicyTranslatorTest.relationFields_twoRelationFieldsInOneNode_distinctPaths();
		// SAME_OUTCOME_BOTH_BRANCHES (PersVol5, 2026-09-26 live failure): the configure step (this translator,
		// called from ebx.MatchingConfigurator BEFORE any MCP call) must refuse such a stored plan itself, rather
		// than letting generate_matching_policy/apply_matching_config fail uninformatively.
		MatchingPolicyTranslatorTest.buildMatchingPolicy_sameOutcomeBothBranches_refusedBeforeAnyMcpCall();
		// lot R13 (2026-09-28, docs/MATCHING.md dated entry): the decision tree keeps each node's OWN null
		// handling for a field used in more than one node - never flattened to the field's first appearance
		// (that flattening is real, but scoped to the top-level pre-clustering declaration only).
		MatchingPolicyTranslatorTest.decisionTree_keepsPerNodeNullHandling_notFlattenedToFirstAppearance();
		// PersVol6 live failure (PipelineStep 526, docs/MATCHING.md sect;36, 2026-09-27): forcing a MATCHED leaf to
		// SUSPECTED can make a node's two branches collide AFTER validate() already ran on the original plan - the
		// generic bottom-up collapse below, and its interaction with the relation-fields spec list.
		MatchingPolicyTranslatorTest.collapse_downgradeCollisionCollapsesNodeToSuspected_cascadesToParentButNotRoot();
		// lot R8 (2026-09-27, live PersVol7, PipelineStep 587, docs/MATCHING.md dated entry): generate_matching_policy
		// refused a tree with two nodes sharing the same add-on-stored name - translate must disambiguate before ever building the nested tree.
		MatchingPolicyTranslatorTest.buildMatchingPolicy_duplicateAutoLabels_distinctNamesInNestedOutput();
		MatchingPolicyTranslatorTest.buildMatchingPolicy_noCollision_namesUnchanged();
		MatchingPolicyTranslatorTest.translate_twoNodesSameChildField_distinctNodeNames();
		MatchingPolicyTranslatorTest.collapse_autoMergeTrue_leavesTreeUnchanged();
		MatchingPolicyTranslatorTest.collapse_rootCollapse_refused();
		MatchingPolicyTranslatorTest.collapse_confirmationNodeShapeNotCollapsed_onFalseIsNotMatched();
		MatchingPolicyTranslatorTest.collapse_relationNodeCollapsed_noBusinessObjectFieldInTree();
		System.out.println("MatchingPolicyTranslatorTest OK");
	}

	private static void twoTierRequirements_translatesToNestedDecisionTree()
	{
		final JsonNode requirements = MatchingPolicyTranslatorTest.twoTierRequirements();
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, true);
		MatchingPolicyTranslatorTest.check("processCode set", "Company".equals(policy.path("processCode").asText()));
		MatchingPolicyTranslatorTest.check("active true", policy.path("active").asBoolean());
		MatchingPolicyTranslatorTest.check("matchAgainstMergedRecords false", !policy.path("matchAgainstMergedRecords").asBoolean(true));
		MatchingPolicyTranslatorTest.check("clustering fields include vatNo, name, country, city", MatchingPolicyTranslatorTest.hasClusteringField(policy, "vatNo")
			&& MatchingPolicyTranslatorTest.hasClusteringField(policy, "name") && MatchingPolicyTranslatorTest.hasClusteringField(policy, "country")
			&& MatchingPolicyTranslatorTest.hasClusteringField(policy, "city"));
		MatchingPolicyTranslatorTest.check("every declared field path carries a leading slash", MatchingPolicyTranslatorTest.everyFieldIsSlashed(policy));
		MatchingPolicyTranslatorTest.check("name clustering field carries weight 3", MatchingPolicyTranslatorTest.clusteringFieldWeight(policy, "name") == 3);

		final JsonNode root = policy.path("decisionTree");
		MatchingPolicyTranslatorTest.check("root function is All fields match (exact tier first)", "All fields match".equals(root.path("function").asText()));
		MatchingPolicyTranslatorTest.check("root field is /vatNo", "/vatNo".equals(root.path("fields").get(0).path("field").asText()));
		MatchingPolicyTranslatorTest.check("root field minimumScore param is 100", "100".equals(MatchingPolicyTranslatorTest.paramValue(root.path("fields").get(0), "minimumScore")));
		MatchingPolicyTranslatorTest.check("root trueNode is MATCHED leaf",
			"MATCHED".equals(root.path("trueNode").path("name").asText()) && root.path("trueNode").path("function").isNull());

		final JsonNode fuzzyNode = root.path("falseNode");
		MatchingPolicyTranslatorTest.check("second node is Average (the fuzzy tier)", "Average".equals(fuzzyNode.path("function").asText()));
		MatchingPolicyTranslatorTest.check("Average node minimumScore param is 90", "90".equals(MatchingPolicyTranslatorTest.paramValue(fuzzyNode, "minimumScore")));
		MatchingPolicyTranslatorTest.check("three fields under the Average node", fuzzyNode.path("fields").size() == 3);
		MatchingPolicyTranslatorTest.check("name field under Average carries weight param 3",
			"3".equals(MatchingPolicyTranslatorTest.paramValue(MatchingPolicyTranslatorTest.fieldNode(fuzzyNode, "name"), "weight")));
		MatchingPolicyTranslatorTest.check("fuzzy trueNode is SUSPECTED leaf", "SUSPECTED".equals(fuzzyNode.path("trueNode").path("name").asText()));
		MatchingPolicyTranslatorTest.check("fuzzy falseNode is the terminal NOT_MATCHED leaf",
			"NOT_MATCHED".equals(fuzzyNode.path("falseNode").path("name").asText()) && fuzzyNode.path("falseNode").path("function").isNull());
	}

	private static void exactOnlyRequirements_falseNodeGoesStraightToNotMatched()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("LegalEntity",
			Collections.singletonList(new MatchPlanRules.AttributeInfo("lei", "STRING", null, "LEI")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("LegalEntity", requirements, true);
		final JsonNode root = policy.path("decisionTree");
		MatchingPolicyTranslatorTest.check("root is the exact tier", "All fields match".equals(root.path("function").asText()));
		MatchingPolicyTranslatorTest.check("root field is /lei", "/lei".equals(root.path("fields").get(0).path("field").asText()));
		MatchingPolicyTranslatorTest.check("falseNode is the terminal NOT_MATCHED leaf directly (no fuzzy tier)",
			"NOT_MATCHED".equals(root.path("falseNode").path("name").asText()) && root.path("falseNode").path("function").isNull());
	}

	/** CLAUDE.md sec 8: mode alone never gates auto-merge - only the leaf name does. autoMerge=false (the schema default) must force every outcome to SUSPECTED. */
	private static void autoMergeFalse_exactTierForcedToSuspected()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("LegalEntity",
			Collections.singletonList(new MatchPlanRules.AttributeInfo("lei", "STRING", null, "LEI")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("LegalEntity", requirements, false);
		final JsonNode root = policy.path("decisionTree");
		MatchingPolicyTranslatorTest.check("exact tier's trueNode is SUSPECTED, not MATCHED, when autoMerge is false",
			"SUSPECTED".equals(root.path("trueNode").path("name").asText()));
	}

	private static void autoMergeTrue_exactTierStaysMatched_fuzzyTierStillSuspected()
	{
		final JsonNode requirements = MatchingPolicyTranslatorTest.twoTierRequirements();
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, true);
		final JsonNode root = policy.path("decisionTree");
		MatchingPolicyTranslatorTest.check("exact tier's trueNode is MATCHED when autoMerge is true", "MATCHED".equals(root.path("trueNode").path("name").asText()));
		MatchingPolicyTranslatorTest.check("the fuzzy tier's own trueNode stays SUSPECTED even with autoMerge true (a fuzzy tier is never MATCH-terminal)",
			"SUSPECTED".equals(root.path("falseNode").path("trueNode").path("name").asText()));
	}

	private static void mergePolicy_carriesModeAndChains()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("code set", "Company".equals(mergePolicy.path("code").asText()));
		MatchingPolicyTranslatorTest.check("mode ONLY_DUPLICATES", "ONLY_DUPLICATES".equals(mergePolicy.path("mode").asText()));
		MatchingPolicyTranslatorTest.check("record selection chain tries most trusted source first, then mostCompleted's i18n key",
			mergePolicy.path("recordSelectionFunctionChain").size() == 2
				&& mergePolicy.path("recordSelectionFunctionChain").get(0).path("function").asText().endsWith("mostTrustedSource.label")
				&& mergePolicy.path("recordSelectionFunctionChain").get(1).path("function").asText().endsWith("mostCompleted.label"));
		MatchingPolicyTranslatorTest.check("default field merge chain tries most trusted source first, then longest",
			"most trusted source".equals(mergePolicy.path("defaultFieldMergeFunctionChain").get(0).path("function").asText())
				&& "longest".equals(mergePolicy.path("defaultFieldMergeFunctionChain").get(1).path("function").asText()));
		MatchingPolicyTranslatorTest.check("survivors always carry the four fixed technical fields, even with no source-key attribute",
			mergePolicy.path("survivors").size() == 4);
	}

	/**
	 * Coordinator follow-up, 2026-09-22 (after `feature/lineage-suspect-decision`'s own "decide on a suspect"
	 * lot): measured live the SAME day that a policy this factory writes carried `"useForManualMerge":false`
	 * on `/root/Company` - which makes `MergeOperations.mergeGroup` (the call the Record lineage screen's own
	 * accept action makes) refuse on every instance, since that call is documented to require a policy with
	 * `Used for manual merge` set to true (`.../service/merge/MergeOperations.html`, quoted in
	 * `docs/RECORD-LINEAGE.md`). Fixed at the source, in {@link MatchingPolicyTranslator#buildMergePolicy}
	 * itself - a fixed `true`, not a per-project choice, since a policy a steward cannot use by hand from that
	 * screen is useless to this factory's whole model.
	 */
	private static void mergePolicy_useForManualMergeIsTrue()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("useForManualMerge is true, so a steward can actually merge from the Record lineage screen",
			mergePolicy.path("useForManualMerge").asBoolean(false));
	}

	private static void mergePolicy_neverCarriesRelatedTables()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("no relatedTables key at all (CLAUDE.md sec 8: generate_merge_policy adds them itself)", !mergePolicy.has("relatedTables"));
	}

	/** The gap this lot fixes (class Javadoc "the gap"): every technical field gets an explicit rule, never left to the trusted-source/longest default. */
	private static void mergePolicy_technicalFieldSurvivorsAlwaysPresent()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		// lot M1 (2026-10-17): never a constant - the add-on applies survivors to a singleton in place (all 534 sourceSystem values became MDM)
		MatchingPolicyTranslatorTest.check("sourceSystem never merges (a record keeps its own source), NOT a constant",
			"no merge".equals(MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/sourceSystem").get(0).path("function").asText())
				&& !MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/sourceSystem").get(0).hasNonNull("constantValue"));
		MatchingPolicyTranslatorTest.check("sourceId never merges",
			"no merge".equals(MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/sourceId").get(0).path("function").asText()));
		MatchingPolicyTranslatorTest.check("_loadId never merges",
			"no merge".equals(MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/_loadId").get(0).path("function").asText()));
		MatchingPolicyTranslatorTest.check("_receivedAt survives as the max",
			"max".equals(MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/_receivedAt").get(0).path("function").asText()));
	}

	/**
	 * Lot M1 (2026-10-17), live evidence E2EMASTER20261017212914: a {@code constant "MDM"} survivor on {@code /sourceSystem} rewrote the source of ALL 534 master records
	 * (the add-on merges a singleton of a business-object main table in place and applies the survivors to it). The golden's source is the policy's
	 * {@code autoCreateNewGoldenSource}, which the add-on reads only for a golden it creates.
	 */
	private static void lotM1_goldenSourceIsAutoCreateNewGoldenSource_neverAConstantSurvivor()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("the merge policy names the source of the goldens it creates: MDM", "MDM".equals(mergePolicy.path("autoCreateNewGoldenSource").asText()));
		MatchingPolicyTranslatorTest.check("...and that is the translator's public constant", MatchingPolicyTranslator.GOLDEN_SOURCE_CODE.equals(mergePolicy.path("autoCreateNewGoldenSource").asText()));
		MatchingPolicyTranslatorTest.check("sourceSystem carries NO constant entry",
			!MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/sourceSystem").get(0).path("function").asText().equals("constant"));
	}

	/** Lot M1 guard: whatever the plan carries (hints, source keys, valid-value fields, a model proposal for sourceSystem), no survivor chain of the built policy holds a constant. */
	private static void lotM1_noConstantSurvivorEverLeavesTheTranslator()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithOneSurvivorshipField("sourceSystem", "longest", null);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.singletonList("supplierId"), Collections.singletonList("vatNoValid"));
		boolean constantFound = false;
		for (final JsonNode survivor : mergePolicy.path("survivors"))
		{
			for (final JsonNode entry : survivor.path("mergeFunctionChain"))
			{
				constantFound |= "constant".equals(entry.path("function").asText());
			}
		}
		MatchingPolicyTranslatorTest.check("no 'constant' function in any survivor chain of the policy", !constantFound);
		boolean refused = false;
		try
		{
			// the self-check itself: a policy with a constant survivor is refused by the translator's own well-formedness assertion
			final ObjectNode poisoned = mergePolicy.deepCopy();
			((ObjectNode) poisoned.path("survivors").get(0).path("mergeFunctionChain").get(0)).put("function", "constant").put("constantValue", "MDM");
			MatchingPolicyTranslator.assertMergePolicyWellFormed(poisoned);
		}
		catch (final IllegalStateException ex)
		{
			refused = ex.getMessage().contains("constant");
		}
		MatchingPolicyTranslatorTest.check("a 'constant' survivor is refused by the translator's own self-check", refused);
	}

	private static void mergePolicy_sourceKeyFieldGetsNoMergeSurvivor()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Arrays.asList("supplierId"), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("five survivors now (four technical + the source-key attribute)", mergePolicy.path("survivors").size() == 5);
		MatchingPolicyTranslatorTest.check("supplierId never merges",
			"no merge".equals(MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/supplierId").get(0).path("function").asText()));
	}

	private static void mergePolicy_recordSelectionTriesMostTrustedSourceFirst()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("two entries: most trusted source, then the project's own default",
			mergePolicy.path("recordSelectionFunctionChain").size() == 2);
	}

	/** A hint that already IS mostTrustedSource must not be duplicated into a two-entry chain. */
	private static void mergePolicy_recordSelectionHintAlreadyMostTrustedSource_singleEntry()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.MAPPER.createObjectNode();
		requirements.putArray("tiers");
		requirements.putArray("clusteringFields");
		final ObjectNode mergePolicyHints = requirements.putObject("mergePolicy");
		mergePolicyHints.put("recordSelection", "mostTrustedSource");
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("single entry, not duplicated", mergePolicy.path("recordSelectionFunctionChain").size() == 1);
		MatchingPolicyTranslatorTest.check("it is mostTrustedSource's own i18n key",
			mergePolicy.path("recordSelectionFunctionChain").get(0).path("function").asText().endsWith("mostTrustedSource.label"));
	}

	/** Same collapse rule, one level down, for the literal-string default field merge chain. */
	private static void mergePolicy_defaultFieldMergeTriesMostTrustedSourceFirst()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.MAPPER.createObjectNode();
		requirements.putArray("tiers");
		requirements.putArray("clusteringFields");
		final ObjectNode mergePolicyHints = requirements.putObject("mergePolicy");
		mergePolicyHints.put("defaultFieldMerge", "most trusted source");
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("single entry, not duplicated, when the hint already is 'most trusted source'",
			mergePolicy.path("defaultFieldMergeFunctionChain").size() == 1
				&& "most trusted source".equals(mergePolicy.path("defaultFieldMergeFunctionChain").get(0).path("function").asText()));
	}

	/** A source-key attribute happening to be literally named like a technical field must never produce two survivor rules for the same path - the first (technical) rule wins. */
	private static void mergePolicy_sourceKeyFieldNamedLikeATechnicalField_notDuplicated()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Arrays.asList("sourceId"), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("still exactly four survivors - never duplicated", mergePolicy.path("survivors").size() == 4);
	}

	// ------------------------------------------------------------------ survivorship lot (2026-09-22, sec 15)

	private static ObjectNode requirementsWithOneSurvivorshipField(final String pField, final String pFunction, final List<String> pTrustedSourceList)
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final ObjectNode requirements = (ObjectNode) MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode fieldRule = requirements.putObject("survivorship").putArray("fields").addObject();
		fieldRule.put("field", pField);
		final ObjectNode chainEntry = fieldRule.putArray("chain").addObject();
		chainEntry.put("function", pFunction);
		if (pTrustedSourceList != null)
		{
			final ArrayNode list = chainEntry.putArray("trustedSourceList");
			for (final String code : pTrustedSourceList)
			{
				list.add(code);
			}
		}
		fieldRule.put("rationale", "test rationale");
		fieldRule.put("confidence", "high");
		return requirements;
	}

	/** A business field's LLM-proposed survivor rule reaches the wire {@code survivors[]}, in addition to the four fixed technical ones. */
	private static void mergePolicy_businessSurvivorRuleAdded()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithOneSurvivorshipField("city", "most frequent", null);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("five survivors: four technical + one business", mergePolicy.path("survivors").size() == 5);
		MatchingPolicyTranslatorTest.check("city survives on 'most frequent'",
			"most frequent".equals(MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/city").get(0).path("function").asText()));
	}

	/** Task brief's own contract: technical survivors ALWAYS win over an LLM rule on the same field - the business rule is silently dropped, never a second rule for the same path. */
	private static void mergePolicy_businessSurvivorRuleDroppedWhenCollidesWithTechnical()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithOneSurvivorshipField("sourceSystem", "longest", null);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("still exactly four survivors - the business rule on sourceSystem never wins", mergePolicy.path("survivors").size() == 4);
		MatchingPolicyTranslatorTest.check("sourceSystem still keeps the factory's own rule (no merge), not 'longest'",
			"no merge".equals(MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/sourceSystem").get(0).path("function").asText()));
	}

	/** {@code trustedSourceList} belongs in the trusted-sources payload (see {@link #extractFieldTrustedSourceOverrides_oneFieldWithOverride}), never in the merge-policy wire chain. */
	private static void mergePolicy_businessSurvivorRuleStripsTrustedSourceListFromWireChain()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithOneSurvivorshipField("vatNo", "most trusted source", Arrays.asList("ERP", "CRM"));
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		final JsonNode entry = MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/vatNo").get(0);
		MatchingPolicyTranslatorTest.check("function carried over", "most trusted source".equals(entry.path("function").asText()));
		MatchingPolicyTranslatorTest.check("trustedSourceList never reaches the merge-policy wire chain", !entry.has("trustedSourceList"));
	}

	/** A requirements document with no {@code survivorship} key at all (every plan built before this lot) must translate exactly as before - four technical survivors, nothing else. */
	private static void mergePolicy_businessSurvivorRuleWithNoSurvivorshipKeyAtAll()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		MatchingPolicyTranslatorTest.check("no survivorship key in a plain MatchPlanRules document", !requirements.has("survivorship"));
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("still exactly four survivors", mergePolicy.path("survivors").size() == 4);
	}

	// ---------------------------------------------------------------- lot 1 (2026-09-23, sec 23): *Valid fields

	/** A {@code *Valid} derived field named via the fourth {@code buildMergePolicy} parameter gets an explicit, longest-ONLY survivor rule, next to the four fixed technical ones. */
	private static void mergePolicy_validValueFieldGetsLongestOnlySurvivor()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(),
			Arrays.asList("vatNoValid"));
		MatchingPolicyTranslatorTest.check("five survivors now (four technical + the *Valid field)", mergePolicy.path("survivors").size() == 5);
		final ArrayNode chain = MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/vatNoValid");
		MatchingPolicyTranslatorTest.check("exactly one chain entry", chain.size() == 1);
		MatchingPolicyTranslatorTest.check("longest, and nothing else", "longest".equals(chain.get(0).path("function").asText()));
		MatchingPolicyTranslatorTest.check("never a trusted-source step", !chain.get(0).has("trustedSourceList"));
	}

	/** A {@code *Valid} field happening to be literally named like a technical field must never produce two survivor rules for the same path - technical always wins. */
	private static void mergePolicy_validValueFieldNamedLikeATechnicalField_notDuplicated()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(),
			Arrays.asList("_receivedAt"));
		MatchingPolicyTranslatorTest.check("still exactly four survivors - never duplicated", mergePolicy.path("survivors").size() == 4);
		MatchingPolicyTranslatorTest.check("_receivedAt still survives as 'max', never 'longest'",
			"max".equals(MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/_receivedAt").get(0).path("function").asText()));
	}

	/** Task brief's own precedence: a *Valid field's longest-only rule wins over an LLM business rule proposed for the SAME field - the business rule is silently dropped, exactly like a collision with a technical field. */
	private static void mergePolicy_validValueSurvivorWinsOverBusinessRuleOnSameField()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithOneSurvivorshipField("vatNoValid", "most frequent", null);
		final ObjectNode mergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(),
			Arrays.asList("vatNoValid"));
		MatchingPolicyTranslatorTest.check("five survivors: four technical + the *Valid field, the LLM rule never adds a sixth",
			mergePolicy.path("survivors").size() == 5);
		final ArrayNode chain = MatchingPolicyTranslatorTest.survivorChain(mergePolicy, "/vatNoValid");
		MatchingPolicyTranslatorTest.check("longest wins, never the LLM's own 'most frequent'", chain.size() == 1 && "longest".equals(chain.get(0).path("function").asText()));
	}

	/** An empty/null fourth parameter (every caller/plan built before this lot) changes nothing - four survivors, exactly as before. */
	private static void mergePolicy_noValidValueFields_unaffected()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		final ObjectNode withEmptyList = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), Collections.<String> emptyList());
		MatchingPolicyTranslatorTest.check("empty list: still four", withEmptyList.path("survivors").size() == 4);
		final ObjectNode withNull = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.<String> emptyList(), null);
		MatchingPolicyTranslatorTest.check("null: still four, never throws", withNull.path("survivors").size() == 4);
	}

	private static void technicalSurvivorFieldNames_hasTheFourFixedFields()
	{
		final java.util.Set<String> names = MatchingPolicyTranslator.technicalSurvivorFieldNames();
		MatchingPolicyTranslatorTest.check("exactly four", names.size() == 4);
		MatchingPolicyTranslatorTest.check("sourceSystem/sourceId/_loadId/_receivedAt",
			names.contains("sourceSystem") && names.contains("sourceId") && names.contains("_loadId") && names.contains("_receivedAt"));
	}

	/** ERP/CRM, the two codes every {@code extractFieldTrustedSourceOverrides} test below validates against - {@code ERP}'s description deliberately mirrors run 30's real wizard label, the exact string the model mistook for a code. */
	private static List<TrustedSourceRanking.Source> erpAndCrmSources()
	{
		return Arrays.asList(new TrustedSourceRanking.Source("ERP", Integer.valueOf(1), "ERP - Snowflake - analytics (demo)"),
			new TrustedSourceRanking.Source("CRM", Integer.valueOf(2), "CRM system"));
	}

	private static void extractFieldTrustedSourceOverrides_oneFieldWithOverride()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithOneSurvivorshipField("vatNo", "most trusted source", Arrays.asList("ERP", "CRM"));
		final ArrayNode overrides = MatchingPolicyTranslator.extractFieldTrustedSourceOverrides(requirements, MatchingPolicyTranslatorTest.erpAndCrmSources());
		MatchingPolicyTranslatorTest.check("one override", overrides.size() == 1);
		MatchingPolicyTranslatorTest.check("field path carries the leading slash", "/vatNo".equals(overrides.get(0).path("field").asText()));
		MatchingPolicyTranslatorTest.check("trustedSourceList carried over in order",
			"ERP".equals(overrides.get(0).path("trustedSourceList").get(0).asText()) && "CRM".equals(overrides.get(0).path("trustedSourceList").get(1).asText()));
	}

	private static void extractFieldTrustedSourceOverrides_emptyWithNoOverride()
	{
		final MatchPlanRules.EntityInfo entity = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN")));
		final JsonNode requirements = MatchingPolicyTranslatorTest.parse(MatchPlanRules.propose(Collections.singletonList(entity)).get(0).requirementsJson);
		MatchingPolicyTranslatorTest.check("empty when there is no survivorship section at all",
			MatchingPolicyTranslator.extractFieldTrustedSourceOverrides(requirements, MatchingPolicyTranslatorTest.erpAndCrmSources()).size() == 0);
	}

	private static void extractFieldTrustedSourceOverrides_ignoresNonTrustedSourceFunctions()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithOneSurvivorshipField("city", "most frequent", null);
		MatchingPolicyTranslatorTest.check("a 'most frequent' rule (no trustedSourceList concept) yields no override",
			MatchingPolicyTranslator.extractFieldTrustedSourceOverrides(requirements, MatchingPolicyTranslatorTest.erpAndCrmSources()).size() == 0);
	}

	/**
	 * The exact run-30 defect (2026-09-22, `docs/MATCHING.md` &sect;15, agent run 30): the LLM answered {@code
	 * trustedSourceList:["ERP - Snowflake - analytics (demo)"]} - the source's DESCRIPTION, not its {@code "ERP"}
	 * code - for a plan stored before this validation existed. The translator, the last gate before {@code
	 * set_trusted_sources}, must map it to the real code rather than forward the phantom string.
	 */
	private static void extractFieldTrustedSourceOverrides_run30Case_labelMappedToCode()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithOneSurvivorshipField("legalForm", "most trusted source",
			Collections.singletonList("ERP - Snowflake - analytics (demo)"));
		final List<String> notes = new java.util.ArrayList<String>();
		final ArrayNode overrides = MatchingPolicyTranslator.extractFieldTrustedSourceOverrides(requirements, MatchingPolicyTranslatorTest.erpAndCrmSources(), notes);
		MatchingPolicyTranslatorTest.check("one override kept, mapped to the real code", overrides.size() == 1
			&& "ERP".equals(overrides.get(0).path("trustedSourceList").get(0).asText()));
		MatchingPolicyTranslatorTest.check("the mapping is named, not silent",
			notes.size() == 1 && notes.get(0).contains("LABEL") && notes.get(0).contains("ERP - Snowflake - analytics (demo)") && notes.get(0).contains("'ERP'"));
	}

	/** An entry naming no known source is dropped (UNKNOWN_SOURCE_CODE); a valid entry in the SAME list survives untouched. */
	private static void extractFieldTrustedSourceOverrides_unknownEntryDropped_knownEntryKept()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithOneSurvivorshipField("vatNo", "most trusted source",
			Arrays.asList("ERP", "GHOST_SOURCE"));
		final List<String> notes = new java.util.ArrayList<String>();
		final ArrayNode overrides = MatchingPolicyTranslator.extractFieldTrustedSourceOverrides(requirements, MatchingPolicyTranslatorTest.erpAndCrmSources(), notes);
		MatchingPolicyTranslatorTest.check("ERP kept, GHOST_SOURCE dropped", overrides.size() == 1 && overrides.get(0).path("trustedSourceList").size() == 1
			&& "ERP".equals(overrides.get(0).path("trustedSourceList").get(0).asText()));
		MatchingPolicyTranslatorTest.check("the drop is named UNKNOWN_SOURCE_CODE",
			notes.size() == 1 && notes.get(0).contains("GHOST_SOURCE") && notes.get(0).contains("UNKNOWN_SOURCE_CODE"));
	}

	/** When EVERY entry of a field's override is unknown, the field gets NO override entry at all (falls back to the table's own ranking) - never an empty {@code trustedSourceList}. */
	private static void extractFieldTrustedSourceOverrides_everyEntryUnknown_overrideDroppedEntirely()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithOneSurvivorshipField("vatNo", "most trusted source",
			Collections.singletonList("GHOST_SOURCE"));
		final List<String> notes = new java.util.ArrayList<String>();
		final ArrayNode overrides = MatchingPolicyTranslator.extractFieldTrustedSourceOverrides(requirements, MatchingPolicyTranslatorTest.erpAndCrmSources(), notes);
		MatchingPolicyTranslatorTest.check("no override entry at all, never an empty trustedSourceList", overrides.size() == 0);
		MatchingPolicyTranslatorTest.check("two notes: the unknown entry, then the whole-override drop",
			notes.size() == 2 && notes.get(1).contains("dropped entirely"));
	}

	private static ArrayNode survivorChain(final ObjectNode pMergePolicy, final String pFieldPath)
	{
		for (final JsonNode survivor : pMergePolicy.path("survivors"))
		{
			if (pFieldPath.equals(survivor.path("field").asText()))
			{
				return (ArrayNode) survivor.path("mergeFunctionChain");
			}
		}
		throw new AssertionError("survivor not found: " + pFieldPath);
	}

	/**
	 * Tonight's exact live failure (`docs/MATCHING.md`, 2026-09-21, 22:16), reproduced as a requirements
	 * document and translated with the FIXED code: {@code clusteringFields: ["city","country"]}, but the
	 * decision tree also compares {@code name} (the EXACT identifier tier AND the fuzzy anchor of the
	 * AVERAGE tier) - a field never listed in {@code clusteringFields} at all. UNDER THE OLD CODE, {@code
	 * policy.fields} would have been exactly {@code [city, country]} (raw, no leading slash) - missing
	 * {@code name} entirely, which is the literal live refusal quoted in this class' own Javadoc
	 * ({@code INVALID_PARAMS}, "Decision tree references a field not declared on the policy: name"). Both
	 * assertions below - {@code name} present, every path slashed - are exactly what the old code got wrong.
	 */
	private static void tonightsRealPlan_unionDeclaresTheFuzzyAnchorToo()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.MAPPER.createObjectNode();
		final ArrayNode tiers = requirements.putArray("tiers");

		final ObjectNode exactTier = tiers.addObject();
		exactTier.put("outcome", "MATCH");
		exactTier.put("type", "EXACT");
		MatchingPolicyTranslatorTest.exactField(exactTier.putArray("fields"), "name");

		final ObjectNode fuzzyTier = tiers.addObject();
		fuzzyTier.put("outcome", "SUSPECTED");
		fuzzyTier.put("type", "AVERAGE");
		fuzzyTier.put("minimumScore", 85);
		final ArrayNode fuzzyFields = fuzzyTier.putArray("fields");
		MatchingPolicyTranslatorTest.fuzzyField(fuzzyFields, "name", "ebx-addon-mame@fuzzyFullText", 10, "DO_NOTHING", "DO_NOTHING");
		MatchingPolicyTranslatorTest.fuzzyField(fuzzyFields, "city", "ebx-addon-mame@levenshtein", 5, "DO_NOTHING", "DO_NOTHING");
		MatchingPolicyTranslatorTest.fuzzyField(fuzzyFields, "country", "ebx-addon-mame@exact", 5, "DO_NOTHING", "DO_NOTHING");

		final ArrayNode clusteringFields = requirements.putArray("clusteringFields");
		clusteringFields.add("city");
		clusteringFields.add("country");

		final ObjectNode mergePolicy = requirements.putObject("mergePolicy");
		mergePolicy.put("mode", "ONLY_DUPLICATES");
		mergePolicy.put("recordSelection", "mostCompleted");
		mergePolicy.put("defaultFieldMerge", "longest");

		// buildMatchingPolicy must not throw (the self-check at the end of it passing IS the "fixed" assertion).
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);

		MatchingPolicyTranslatorTest.check("fields declares exactly city, country, name - the union, name included",
			policy.path("fields").size() == 3 && MatchingPolicyTranslatorTest.hasClusteringField(policy, "city")
				&& MatchingPolicyTranslatorTest.hasClusteringField(policy, "country") && MatchingPolicyTranslatorTest.hasClusteringField(policy, "name"));
		MatchingPolicyTranslatorTest.check("order is clustering fields first (city, country), then the tree-only field (name) appended",
			"/city".equals(policy.path("fields").get(0).path("field").asText()) && "/country".equals(policy.path("fields").get(1).path("field").asText())
				&& "/name".equals(policy.path("fields").get(2).path("field").asText()));
		MatchingPolicyTranslatorTest.check("city weight 5", MatchingPolicyTranslatorTest.clusteringFieldWeight(policy, "city") == 5);
		MatchingPolicyTranslatorTest.check("country weight 5", MatchingPolicyTranslatorTest.clusteringFieldWeight(policy, "country") == 5);
		// lot M16 (docs/MATCHING.md sec 68): the dominance guard of lot R16 raised "name" (the plan's own MATCH-terminal root field) to 11 here; it is gone - nothing raises a weight any more,
		// "name" keeps the highest weight a node gives it (10) and the shares of the key sets are measured, not applied (CandidateScoreShareTest).
		MatchingPolicyTranslatorTest.check("name keeps its own weight 10", MatchingPolicyTranslatorTest.clusteringFieldWeight(policy, "name") == 10);
		MatchingPolicyTranslatorTest.check("every declared field path carries a leading slash", MatchingPolicyTranslatorTest.everyFieldIsSlashed(policy));

		final JsonNode root = policy.path("decisionTree");
		MatchingPolicyTranslatorTest.check("root (EXACT tier) field is /name", "/name".equals(root.path("fields").get(0).path("field").asText()));
		final JsonNode fuzzyNode = root.path("falseNode");
		MatchingPolicyTranslatorTest.check("fuzzy tier fields are all slashed",
			"/name".equals(MatchingPolicyTranslatorTest.fieldNode(fuzzyNode, "name").path("field").asText())
				&& "/city".equals(MatchingPolicyTranslatorTest.fieldNode(fuzzyNode, "city").path("field").asText())
				&& "/country".equals(MatchingPolicyTranslatorTest.fieldNode(fuzzyNode, "country").path("field").asText()));
	}

	/** A field the tree compares in an EXACT tier only (no explicit weight there) and that is never listed in {@code clusteringFields} nor in any AVERAGE tier: must default to weight 1, never 0. */
	private static void treeOnlyField_notInClusteringFieldsNorAverageTier_defaultsToWeightOne()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.MAPPER.createObjectNode();
		final ArrayNode tiers = requirements.putArray("tiers");
		final ObjectNode exactTier = tiers.addObject();
		exactTier.put("outcome", "MATCH");
		exactTier.put("type", "EXACT");
		MatchingPolicyTranslatorTest.exactField(exactTier.putArray("fields"), "lei");
		requirements.putArray("clusteringFields"); // deliberately empty: lei is used by the tree, never listed here
		requirements.putObject("mergePolicy");

		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("LegalEntity", requirements, false);
		MatchingPolicyTranslatorTest.check("lei is still declared (union includes tree-only fields)", MatchingPolicyTranslatorTest.hasClusteringField(policy, "lei"));
		MatchingPolicyTranslatorTest.check("lei defaults to weight 1, never 0", MatchingPolicyTranslatorTest.clusteringFieldWeight(policy, "lei") == 1);
	}

	/** {@code toFieldPath} must not double a leading slash a caller already supplied - defensive, since no producer in this codebase emits one today. */
	private static void alreadySlashedField_idempotent()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.MAPPER.createObjectNode();
		final ArrayNode tiers = requirements.putArray("tiers");
		final ObjectNode exactTier = tiers.addObject();
		exactTier.put("outcome", "MATCH");
		exactTier.put("type", "EXACT");
		MatchingPolicyTranslatorTest.exactField(exactTier.putArray("fields"), "/vatNo");
		requirements.putArray("clusteringFields").add("/vatNo");
		requirements.putObject("mergePolicy");

		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);
		MatchingPolicyTranslatorTest.check("exactly one field declared (a leading slash on input must not create a second, differently-keyed entry)",
			policy.path("fields").size() == 1);
		MatchingPolicyTranslatorTest.check("single leading slash, not doubled", "/vatNo".equals(policy.path("fields").get(0).path("field").asText()));
		MatchingPolicyTranslatorTest.check("tree field path is also a single leading slash",
			"/vatNo".equals(policy.path("decisionTree").path("fields").get(0).path("field").asText()));
	}

	/** A grouped attribute's own internal slash (e.g. a hypothetical {@code Address/City}) must survive untouched - only a LEADING slash is ever added or stripped. */
	private static void groupedFieldPath_keepsItsInternalSlash()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.MAPPER.createObjectNode();
		final ArrayNode tiers = requirements.putArray("tiers");
		final ObjectNode exactTier = tiers.addObject();
		exactTier.put("outcome", "MATCH");
		exactTier.put("type", "EXACT");
		MatchingPolicyTranslatorTest.exactField(exactTier.putArray("fields"), "Address/City");
		requirements.putArray("clusteringFields").add("Address/City");
		requirements.putObject("mergePolicy");

		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);
		MatchingPolicyTranslatorTest.check("grouped path gets exactly one leading slash, internal slash untouched",
			"/Address/City".equals(policy.path("fields").get(0).path("field").asText()));
	}

	/** {@code normalizeFieldName}'s own refusal - a blank/missing field name is a defect upstream, never guessed. */
	private static void blankFieldName_refusedRatherThanGuessed()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.MAPPER.createObjectNode();
		final ArrayNode tiers = requirements.putArray("tiers");
		final ObjectNode exactTier = tiers.addObject();
		exactTier.put("outcome", "MATCH");
		exactTier.put("type", "EXACT");
		MatchingPolicyTranslatorTest.exactField(exactTier.putArray("fields"), "  ");
		requirements.putArray("clusteringFields");
		requirements.putObject("mergePolicy");

		try
		{
			MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);
			throw new AssertionError("expected IllegalStateException for a blank field name");
		}
		catch (final IllegalStateException expected)
		{
			// expected
		}
	}

	/**
	 * 2026-09-22 matching-key contract: {@link MatchingPolicyTranslator} needs no code change at all for it - a
	 * plan whose {@code clusteringFields} holds {@code cityKey} (a matching key, already substituted upstream by
	 * {@code master.MatchPlanLlmPass.Session}/{@code MatchPlanRules} - this class never knows or cares that it IS
	 * a key, it is just another field name) and whose EXACT tier compares {@code cityKey} while the AVERAGE tier
	 * compares {@code name} produces {@code /cityKey} and {@code /name} in {@code matchingPolicy.fields} and in
	 * the decision tree, exactly like any other pair of field names.
	 */
	private static void matchKeyFieldPath_cityKeyAndNameBothSlashed()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.MAPPER.createObjectNode();
		final ArrayNode tiers = requirements.putArray("tiers");

		final ObjectNode exactTier = tiers.addObject();
		exactTier.put("outcome", "MATCH");
		exactTier.put("type", "EXACT");
		MatchingPolicyTranslatorTest.exactField(exactTier.putArray("fields"), "cityKey");

		final ObjectNode fuzzyTier = tiers.addObject();
		fuzzyTier.put("outcome", "SUSPECTED");
		fuzzyTier.put("type", "AVERAGE");
		fuzzyTier.put("minimumScore", 90);
		MatchingPolicyTranslatorTest.fuzzyField(fuzzyTier.putArray("fields"), "name", "ebx-addon-mame@fuzzyFullText", 3, "NOT_MATCH", "NOT_MATCH");

		requirements.putArray("clusteringFields").add("cityKey");
		final ObjectNode mergePolicy = requirements.putObject("mergePolicy");
		mergePolicy.put("mode", "ONLY_DUPLICATES");
		mergePolicy.put("recordSelection", "mostCompleted");
		mergePolicy.put("defaultFieldMerge", "longest");

		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Site", requirements, false);
		MatchingPolicyTranslatorTest.check("matchingPolicy.fields declares /cityKey and /name",
			MatchingPolicyTranslatorTest.hasClusteringField(policy, "cityKey") && MatchingPolicyTranslatorTest.hasClusteringField(policy, "name"));
		MatchingPolicyTranslatorTest.check("every declared field path carries a leading slash", MatchingPolicyTranslatorTest.everyFieldIsSlashed(policy));

		final JsonNode root = policy.path("decisionTree");
		MatchingPolicyTranslatorTest.check("the exact tier's own field is /cityKey", "/cityKey".equals(root.path("fields").get(0).path("field").asText()));
		final JsonNode fuzzyNode = root.path("falseNode");
		MatchingPolicyTranslatorTest.check("the fuzzy tier's own field is /name",
			"/name".equals(MatchingPolicyTranslatorTest.fieldNode(fuzzyNode, "name").path("field").asText()));
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	/** The shape either producer (MatchPlanRules for the exact tier, the language-model pass for the fuzzy one) emits: exact vatNo, then a fuzzy name/country/city Average tier. */
	private static JsonNode twoTierRequirements()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.MAPPER.createObjectNode();
		final ArrayNode tiers = requirements.putArray("tiers");
		final ObjectNode exactTier = tiers.addObject();
		exactTier.put("outcome", "MATCH");
		exactTier.put("type", "EXACT");
		MatchingPolicyTranslatorTest.exactField(exactTier.putArray("fields"), "vatNo");

		final ObjectNode fuzzyTier = tiers.addObject();
		fuzzyTier.put("outcome", "SUSPECTED");
		fuzzyTier.put("type", "AVERAGE");
		fuzzyTier.put("minimumScore", 90);
		final ArrayNode fuzzyFields = fuzzyTier.putArray("fields");
		MatchingPolicyTranslatorTest.fuzzyField(fuzzyFields, "name", "ebx-addon-mame@fuzzyFullText", 3, "NOT_MATCH", "NOT_MATCH");
		MatchingPolicyTranslatorTest.fuzzyField(fuzzyFields, "country", "ebx-addon-mame@exact", 1, "DO_NOTHING", "DO_NOTHING");
		MatchingPolicyTranslatorTest.fuzzyField(fuzzyFields, "city", "ebx-addon-mame@fuzzyFullText", 1, "DO_NOTHING", "DO_NOTHING");

		final ArrayNode clusteringFields = requirements.putArray("clusteringFields");
		clusteringFields.add("vatNo");
		clusteringFields.add("name");
		clusteringFields.add("country");
		clusteringFields.add("city");

		final ObjectNode mergePolicy = requirements.putObject("mergePolicy");
		mergePolicy.put("mode", "ONLY_DUPLICATES");
		mergePolicy.put("recordSelection", "mostCompleted");
		mergePolicy.put("defaultFieldMerge", "longest");
		return requirements;
	}

	private static void exactField(final ArrayNode pFields, final String pName)
	{
		final ObjectNode field = pFields.addObject();
		field.put("field", pName);
		field.put("algorithm", "ebx-addon-mame@exact");
		field.put("minimumScore", 100);
	}

	private static void fuzzyField(final ArrayNode pFields, final String pName, final String pAlgorithm, final int pWeight, final String pBothNull, final String pOneNull)
	{
		final ObjectNode field = pFields.addObject();
		field.put("field", pName);
		field.put("algorithm", pAlgorithm);
		field.put("weight", pWeight);
		field.put("bothNullValueHandling", pBothNull);
		field.put("oneNullValueHandling", pOneNull);
	}

	// ------------------------------------------------------------------------------------------- relation fields

	private static ObjectNode requirementsWithDecisionTree(final DecisionTreePlan.Plan pPlan, final List<String> pClusteringFields)
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTest.MAPPER.createObjectNode();
		requirements.set("decisionTree", DecisionTreePlan.toJson(pPlan));
		final ArrayNode clustering = requirements.putArray("clusteringFields");
		for (final String field : pClusteringFields)
		{
			clustering.add(field);
		}
		return requirements;
	}

	/** A relation field is written natively: businessObjectField (policy), field + businessObjectField (tree), absolute path of the child's field; no parent field stands in for it. */
	private static void relationFields_writtenAsBusinessObjectFields()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		node.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact"));
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		relationField.dataType = "STRING";
		node.fields.add(relationField);
		node.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		node.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(node);
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.singletonList("firstName"));
		final Map<String, String> childInfo = new HashMap<String, String>();
		childInfo.put("Adresse", "/root/Adresse");

		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslator.translate("Personne", requirements, true, childInfo);

		MatchingPolicyTranslatorTest.check("one relation field built, at its absolute path, weight 0 by default",
			result.relationFieldWeights.size() == 1 && Integer.valueOf(0).equals(result.relationFieldWeights.get("/root/Adresse/cityKey")));
		MatchingPolicyTranslatorTest.check("policy declares firstName and lastName as candidate fields",
			MatchingPolicyTranslatorTest.hasClusteringField(result.matchingPolicy, "firstName") && MatchingPolicyTranslatorTest.hasClusteringField(result.matchingPolicy, "lastName"));
		JsonNode policyRelation = null;
		for (final JsonNode field : result.matchingPolicy.path("fields"))
		{
			if ("/root/Adresse/cityKey".equals(field.path("businessObjectField").asText(null)))
			{
				policyRelation = field;
			}
		}
		MatchingPolicyTranslatorTest.check("the policy field is businessObjectField with no `field`: " + policyRelation, policyRelation != null && !policyRelation.hasNonNull("field"));
		final JsonNode treeFields = result.matchingPolicy.path("decisionTree").path("fields");
		boolean treeRelation = false;
		boolean plainCityKey = false;
		boolean parentFieldReplaced = false;
		for (final JsonNode field : treeFields)
		{
			if ("/root/Adresse/cityKey".equals(field.path("field").asText()) && "/root/Adresse/cityKey".equals(field.path("businessObjectField").asText(null)))
			{
				treeRelation = true;
			}
			if ("/cityKey".equals(field.path("field").asText()))
			{
				plainCityKey = true;
			}
			if ("/firstName".equals(field.path("field").asText()))
			{
				parentFieldReplaced = true;
			}
		}
		MatchingPolicyTranslatorTest.check("the tree field is field + businessObjectField, the absolute path", treeRelation);
		MatchingPolicyTranslatorTest.check("the child's field never reaches the payload as a field of the parent", !plainCityKey);
		MatchingPolicyTranslatorTest.check("no parent field stands in for the relation field any more", !parentFieldReplaced);
	}

	/** A relation field whose child entity has no known master table is refused before anything is sent. */
	private static void relationFields_unknownChildEntity_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		relationField.dataType = "STRING";
		node.fields.add(relationField);
		node.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		node.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(node);
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.<String>emptyList());

		try
		{
			MatchingPolicyTranslator.translate("Personne", requirements, true, Collections.<String, String>emptyMap());
			throw new AssertionError("expected a refusal: no master table is known for the child entity");
		}
		catch (final IllegalStateException ex)
		{
			MatchingPolicyTranslatorTest.check("refusal names the problem: " + ex.getMessage(), ex.getMessage().contains("no master table is known for child entity 'Adresse'"));
		}
	}

	private static void relationFields_noRelationFields_emptyList()
	{
		final JsonNode requirements = MatchingPolicyTranslatorTest.twoTierRequirements();
		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslator.translate("Company", requirements, true, Collections.<String, String>emptyMap());
		MatchingPolicyTranslatorTest.check("no relation fields in an ordinary plan", !result.hasRelationFields() && result.relationFieldWeights.isEmpty());
		MatchingPolicyTranslatorTest.check("policy still builds normally", "Company".equals(result.matchingPolicy.path("processCode").asText()));
		MatchingPolicyTranslatorTest.check("and is the one buildMatchingPolicy answers", result.matchingPolicy.equals(MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, true)));
	}

	private static void relationFields_writtenManual_matchingOnCreationAndUpdateAreZero()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		node.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact"));
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		relationField.dataType = "STRING";
		node.fields.add(relationField);
		node.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		node.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(node);
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.singletonList("firstName"));
		final Map<String, String> childInfo = new HashMap<String, String>();
		childInfo.put("Adresse", "/root/Adresse");

		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslator.translate("Personne", requirements, true, childInfo);
		MatchingPolicyTranslatorTest.check("matchingOnCreation is MANUAL (0) when the tree has a relation field",
			result.matchingPolicy.path("matchingOnCreation").asInt(-1) == 0);
		MatchingPolicyTranslatorTest.check("matchingOnUpdate is MANUAL (0) when the tree has a relation field",
			result.matchingPolicy.path("matchingOnUpdate").asInt(-1) == 0);
	}

	/** An ordinary plan with no relation field keeps the pre-existing AFTER_SUBMISSION (1/1) behaviour, unchanged. */
	private static void ordinaryPlan_matchingOnCreationAndUpdateAreOne()
	{
		final JsonNode requirements = MatchingPolicyTranslatorTest.twoTierRequirements();
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, true);
		MatchingPolicyTranslatorTest.check("matchingOnCreation stays AFTER_SUBMISSION (1) with no relation field", policy.path("matchingOnCreation").asInt(-1) == 1);
		MatchingPolicyTranslatorTest.check("matchingOnUpdate stays AFTER_SUBMISSION (1) with no relation field", policy.path("matchingOnUpdate").asInt(-1) == 1);
	}

	/** The node keeps the name the plan shows ("Same Adresse.city"), never a name built from the absolute path it is rewritten to. */
	private static void relationFields_nodeNameKeptPreRewrite_isThePlansLabel()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("city", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		relationField.dataType = "STRING";
		node.fields.add(relationField);
		node.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		node.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(node);
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.singletonList("email"));
		final Map<String, String> childInfo = new HashMap<String, String>();
		childInfo.put("Adresse", "/root/Adresse");

		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslator.translate("Personne", requirements, true, childInfo);
		final String writtenName = result.matchingPolicy.path("decisionTree").path("name").asText();
		MatchingPolicyTranslatorTest.check("the node name is the label of the plan: '" + writtenName + "'", writtenName.equals(DecisionTreePlan.labelFor(DecisionTreePlan.readFrom(requirements).nodes.get(0))));
		MatchingPolicyTranslatorTest.check("and mentions Adresse.city, never the absolute path", writtenName.contains("Adresse.city") && !writtenName.contains("/root"));
	}

	/** Two relation fields of the SAME node (both via Adresse) are two distinct business-object fields in the node and in the policy. */
	private static void relationFields_twoRelationFieldsInOneNode_distinctPaths()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef cityField = new DecisionTreePlan.FieldRef("city", "ebx-addon-mame@exact");
		cityField.via = "Adresse";
		cityField.dataType = "STRING";
		final DecisionTreePlan.FieldRef postalCodeField = new DecisionTreePlan.FieldRef("postalCode", "ebx-addon-mame@exact");
		postalCodeField.via = "Adresse";
		postalCodeField.dataType = "STRING";
		node.fields.add(cityField);
		node.fields.add(postalCodeField);
		node.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		node.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(node);
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Arrays.asList("firstName", "lastName"));
		final Map<String, String> childInfo = new HashMap<String, String>();
		childInfo.put("Adresse", "/root/Adresse");

		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslator.translate("Personne", requirements, true, childInfo);
		MatchingPolicyTranslatorTest.check("two relation fields built", result.relationFieldWeights.size() == 2
			&& result.relationFieldWeights.containsKey("/root/Adresse/city") && result.relationFieldWeights.containsKey("/root/Adresse/postalCode"));
		final JsonNode treeFields = result.matchingPolicy.path("decisionTree").path("fields");
		MatchingPolicyTranslatorTest.check("the node carries both, in order", treeFields.size() == 2 && "/root/Adresse/city".equals(treeFields.get(0).path("field").asText())
			&& "/root/Adresse/postalCode".equals(treeFields.get(1).path("field").asText()));
		int relationDeclared = 0;
		for (final JsonNode field : result.matchingPolicy.path("fields"))
		{
			if (field.hasNonNull("businessObjectField"))
			{
				relationDeclared++;
			}
		}
		MatchingPolicyTranslatorTest.check("both declared on the policy", relationDeclared == 2);
	}

	/**
	 * PersVol5 (2026-09-26 live failure): a stored plan whose node has onTrue and onFalse both {@code SUSPECTED}
	 * used to reach {@code generate_matching_policy} unchecked and fail there with an uninformative message. {@link
	 * MatchingPolicyTranslator#buildMatchingPolicy} (called by {@code ebx.MatchingConfigurator#configureOne} to
	 * BUILD the policy, strictly before any {@code generate_matching_policy}/{@code apply_matching_config} MCP
	 * call) already runs {@code DecisionTreePlan.validate} and throws {@link IllegalStateException} on a
	 * structurally invalid plan (`buildMatchingPolicyFromPlan`'s own pre-existing guard) - this test proves the
	 * NEW {@code SAME_OUTCOME_BOTH_BRANCHES} rule is caught there too, with no MCP call ever attempted.
	 */
	private static void buildMatchingPolicy_sameOutcomeBothBranches_refusedBeforeAnyMcpCall()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		node.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		node.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		node.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		plan.nodes.add(node);
		final JsonNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.<String>emptyList());
		try
		{
			MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, true);
			throw new AssertionError("expected a refusal: onTrue and onFalse are both SUSPECTED");
		}
		catch (final IllegalStateException ex)
		{
			MatchingPolicyTranslatorTest.check("refusal names SAME_OUTCOME_BOTH_BRANCHES: " + ex.getMessage(), ex.getMessage().contains("SAME_OUTCOME_BOTH_BRANCHES"));
		}
	}

	/**
	 * Lot R13 (2026-09-28, docs/MATCHING.md dated entry): {@link MatchingPolicyTranslator#nullHandlingByField}'s own
	 * "first appearance wins" aggregation is scoped to the TOP-LEVEL {@code matchingPolicy.fields[]} declaration
	 * only - the decision TREE itself must keep each node's own, independently given null handling for the same
	 * field name. Two nodes both compare {@code country}: the root with {@code oneNullValueHandling} NOT_MATCH,
	 * a second node (reached via the root's own onFalse) with DO_NOTHING - proving the generated
	 * {@code decisionTree...fields[]} carries BOTH values, per node, never flattened to whichever the field first
	 * appeared with.
	 */
	private static void decisionTree_keepsPerNodeNullHandling_notFlattenedToFirstAppearance()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef n1Country = new DecisionTreePlan.FieldRef("country", "ebx-addon-mame@exact");
		n1Country.oneNull = "NOT_MATCH";
		n1.fields.add(n1Country);
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = "N2";
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef n2Country = new DecisionTreePlan.FieldRef("country", "ebx-addon-mame@exact");
		n2Country.oneNull = "DO_NOTHING";
		// lot R14 (2026-09-28, CLAUDE.md &sect;15 "single-field ALL_FIELDS null exemption too broad"): this fixture
		// is a single-field ALL_FIELDS node whose onTrue is SUSPECTED - exactly the shape
		// IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH now refuses DO_NOTHING on, unless marked referenceRole. This
		// test is about the TRANSLATOR keeping per-node null handling separate (not about that counting rule), so
		// the field is marked referenceRole here purely to keep the fixture legal - the assertions below are
		// unaffected (referenceRole is round-trip metadata, never interpreted by the translator itself).
		n2Country.referenceRole = true;
		n2.fields.add(n2Country);
		n2.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);

		final JsonNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.<String>emptyList());
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, true);

		final JsonNode root = policy.path("decisionTree");
		MatchingPolicyTranslatorTest.check("root node's own field keeps its own NOT_MATCH",
			"NOT_MATCH".equals(MatchingPolicyTranslatorTest.fieldNode(root, "country").path("oneNullValueHandling").asText()));
		final JsonNode n2Node = root.path("falseNode");
		MatchingPolicyTranslatorTest.check("second node's SAME field keeps ITS OWN DO_NOTHING, not flattened to the root's NOT_MATCH",
			"DO_NOTHING".equals(MatchingPolicyTranslatorTest.fieldNode(n2Node, "country").path("oneNullValueHandling").asText()));

		// The top-level pre-clustering declaration is, by contrast, necessarily ONE entry per field name - it
		// mirrors the field's FIRST tier appearance (the root's own NOT_MATCH here), a documented, deliberate
		// simplification (MatchingPolicyTranslator#nullHandlingByField's own Javadoc), never a bug this lot fixes.
		boolean foundTopLevelCountry = false;
		for (final JsonNode field : policy.path("fields"))
		{
			if ("/country".equals(field.path("field").asText()))
			{
				foundTopLevelCountry = true;
				MatchingPolicyTranslatorTest.check("top-level declaration mirrors the field's first appearance (root's NOT_MATCH)",
					"NOT_MATCH".equals(field.path("oneNullValueHandling").asText()));
			}
		}
		MatchingPolicyTranslatorTest.check("country is declared at the top level", foundTopLevelCountry);
	}

	// ------------------------------------------------------------------------------- downgrade-collision collapse

	/**
	 * A 3-level chain where forcing N3's own {@code MATCH} leaf to {@code SUSPECTED} makes N3 collapse into a leaf
	 * (its {@code onFalse} was already {@code SUSPECTED}) - then N2, whose {@code onTrue} now reaches that SAME
	 * collapsed leaf and whose own {@code onFalse} is ALSO {@code SUSPECTED}, collapses too (the cascade). N1's own
	 * {@code onFalse} is {@code NOT_MATCHED} (different from the collapsed {@code SUSPECTED}), so N1 itself stays a
	 * real decision node - the cascade stops there rather than reaching the root.
	 */
	private static void collapse_downgradeCollisionCollapsesNodeToSuspected_cascadesToParentButNotRoot()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AVERAGE);
		n1.minimumScore = Integer.valueOf(85);
		n1.fields.add(new DecisionTreePlan.FieldRef("name", "ebx-addon-mame@fuzzyFullText"));
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(new DecisionTreePlan.FieldRef("postalCode", "ebx-addon-mame@exact"));
		n2.onTrue = "N3";
		n2.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		final DecisionTreePlan.Node n3 = new DecisionTreePlan.Node("N3", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n3.fields.add(new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact"));
		n3.onTrue = DecisionTreePlan.LEAF_MATCH;
		n3.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		plan.nodes.add(n3);
		final JsonNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.<String>emptyList());

		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Personne", requirements, false);
		final JsonNode root = policy.path("decisionTree");
		MatchingPolicyTranslatorTest.check("root (N1) is still a genuine decision node", root.hasNonNull("function"));
		MatchingPolicyTranslatorTest.check("root's onFalse is still the terminal NOT_MATCHED leaf, untouched",
			"NOT_MATCHED".equals(root.path("falseNode").path("name").asText()) && root.path("falseNode").path("function").isNull());
		final JsonNode collapsedN2 = root.path("trueNode");
		MatchingPolicyTranslatorTest.check("N2 collapsed into a leaf (no function any more)", !collapsedN2.hasNonNull("function"));
		MatchingPolicyTranslatorTest.check("N2's collapsed leaf is SUSPECTED (both its own branches led there after N3's own collapse)",
			"SUSPECTED".equals(collapsedN2.path("name").asText()));
	}

	/** The exact same 3-level chain, but with {@code autoMerge=true}: nothing is forced to SUSPECTED, so nothing collapses - the tree keeps all three real nodes. */
	private static void collapse_autoMergeTrue_leavesTreeUnchanged()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AVERAGE);
		n1.minimumScore = Integer.valueOf(85);
		n1.fields.add(new DecisionTreePlan.FieldRef("name", "ebx-addon-mame@fuzzyFullText"));
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(new DecisionTreePlan.FieldRef("postalCode", "ebx-addon-mame@exact"));
		n2.onTrue = "N3";
		n2.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		final DecisionTreePlan.Node n3 = new DecisionTreePlan.Node("N3", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n3.fields.add(new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact"));
		n3.onTrue = DecisionTreePlan.LEAF_MATCH;
		n3.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		plan.nodes.add(n3);
		final JsonNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.<String>emptyList());

		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Personne", requirements, true);
		final JsonNode root = policy.path("decisionTree");
		MatchingPolicyTranslatorTest.check("root (N1) still a decision node", root.hasNonNull("function"));
		MatchingPolicyTranslatorTest.check("N2 still a decision node (not collapsed) with autoMerge=true", root.path("trueNode").hasNonNull("function"));
		MatchingPolicyTranslatorTest.check("N3 still a decision node (not collapsed) with autoMerge=true",
			root.path("trueNode").path("trueNode").hasNonNull("function"));
		MatchingPolicyTranslatorTest.check("N3's own onTrue stays the real MATCHED leaf (autoMerge=true never forces it down)",
			"MATCHED".equals(root.path("trueNode").path("trueNode").path("trueNode").path("name").asText()));
	}

	/** A single root node whose two branches collapse to the SAME leaf - the whole tree decides nothing, refused rather than silently sent to the add-on. */
	private static void collapse_rootCollapse_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		plan.nodes.add(n1);
		final JsonNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.<String>emptyList());

		try
		{
			MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);
			throw new AssertionError("expected a refusal: the only node collapses to a single outcome, the policy decides nothing");
		}
		catch (final IllegalStateException ex)
		{
			MatchingPolicyTranslatorTest.check("refusal names the collapsed outcome: " + ex.getMessage(),
				ex.getMessage().contains("SUSPECTED") && ex.getMessage().contains("decide nothing"));
		}
	}

	/**
	 * 2026-09-27 reversal (`docs/MATCHING.md` &sect;36): the child parent-confirmation shape now routes its own
	 * {@code onFalse} to {@code NOT_MATCHED}, never {@code SUSPECTED} - so forcing its {@code onTrue} MATCH down to
	 * SUSPECTED (autoMerge=false) no longer collides with anything: {@code SUSPECTED != NOT_MATCHED}, so the node
	 * survives the collapse pass untouched. {@link MatchingPolicyTranslator} itself knows nothing about REFERENCE
	 * roles or confirmation nodes - this test uses the same generic field names as the rest of this file (a plain
	 * two-level identity/confirmation SHAPE, not a role-specific one), matching the coordinator's own note that the
	 * fix is generic, not confirmation-node-specific.
	 */
	private static void collapse_confirmationNodeShapeNotCollapsed_onFalseIsNotMatched()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AVERAGE);
		n1.minimumScore = Integer.valueOf(90);
		n1.fields.add(new DecisionTreePlan.FieldRef("email", "ebx-addon-mame@fuzzyFullText"));
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(new DecisionTreePlan.FieldRef("companyId", "ebx-addon-mame@exact"));
		n2.onTrue = DecisionTreePlan.LEAF_MATCH;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final JsonNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.<String>emptyList());

		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Contact", requirements, false);
		final JsonNode n2Written = policy.path("decisionTree").path("trueNode");
		MatchingPolicyTranslatorTest.check("N2 is NOT collapsed (onTrue SUSPECTED vs onFalse NOT_MATCHED are different leaves)", n2Written.hasNonNull("function"));
		MatchingPolicyTranslatorTest.check("N2's onTrue was downgraded to SUSPECTED (autoMerge=false)",
			"SUSPECTED".equals(n2Written.path("trueNode").path("name").asText()));
		MatchingPolicyTranslatorTest.check("N2's onFalse stays NOT_MATCHED, unaffected by the downgrade",
			"NOT_MATCHED".equals(n2Written.path("falseNode").path("name").asText()));
	}

	/**
	 * A relation field's own node (N2) collapses under autoMerge=false exactly like any other node (the three-level cascade of
	 * {@code collapse_downgradeCollisionCollapsesNodeToSuspected_cascadesToParentButNotRoot}) - and a collapsed node leaves no relation field in the written tree.
	 * Since lot W13 the relation field is also declared on the policy only because the tree compared it: it stays declared (the policy lists every field the plan
	 * names), but the tree carries no business-object field. Lot R9 (2026-09-27, docs/MATCHING.md sect;40): N2's own onTrue/onFalse cannot be MATCH/SUSPECTED
	 * directly (RELATION_NODE_OUTCOMES_MUST_DIFFER), hence N3.
	 */
	private static void collapse_relationNodeCollapsed_noBusinessObjectFieldInTree()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact"));
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		relationField.dataType = "STRING";
		n2.fields.add(relationField);
		n2.onTrue = "N3";
		n2.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		final DecisionTreePlan.Node n3 = new DecisionTreePlan.Node("N3", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n3.fields.add(new DecisionTreePlan.FieldRef("postalCode", "ebx-addon-mame@exact"));
		n3.onTrue = DecisionTreePlan.LEAF_MATCH;
		n3.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		plan.nodes.add(n3);
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.singletonList("firstName"));
		final Map<String, String> childInfo = new HashMap<String, String>();
		childInfo.put("Adresse", "/root/Adresse");

		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslator.translate("Personne", requirements, false, childInfo);

		final JsonNode root = result.matchingPolicy.path("decisionTree");
		MatchingPolicyTranslatorTest.check("root (N1) survives", root.hasNonNull("function"));
		MatchingPolicyTranslatorTest.check("N2 collapsed away in the cascade (N3 collapsed to SUSPECTED, matching N2's own onFalse)",
			!root.path("trueNode").hasNonNull("function"));
		MatchingPolicyTranslatorTest.check("N2's collapsed leaf is SUSPECTED", "SUSPECTED".equals(root.path("trueNode").path("name").asText()));
		MatchingPolicyTranslatorTest.check("no business-object field is left in the written tree", !root.toString().contains("businessObjectField"));
	}

	private static JsonNode parse(final String pJson)
	{
		try
		{
			return MatchingPolicyTranslatorTest.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	/** @param pName the bare attribute name (no leading slash) - the helper adds it, matching {@link MatchingPolicyTranslator#toFieldPath}'s own output shape. */
	private static boolean hasClusteringField(final ObjectNode pPolicy, final String pName)
	{
		final String path = "/" + pName;
		for (final JsonNode field : pPolicy.path("fields"))
		{
			if (path.equals(field.path("field").asText()))
			{
				return true;
			}
		}
		return false;
	}

	private static int clusteringFieldWeight(final ObjectNode pPolicy, final String pName)
	{
		final String path = "/" + pName;
		for (final JsonNode field : pPolicy.path("fields"))
		{
			if (path.equals(field.path("field").asText()))
			{
				return field.path("weight").asInt();
			}
		}
		throw new AssertionError("clustering field not found: " + pName);
	}

	/** @param pFieldName the bare attribute name (no leading slash). */
	private static JsonNode fieldNode(final JsonNode pNode, final String pFieldName)
	{
		final String path = "/" + pFieldName;
		for (final JsonNode field : pNode.path("fields"))
		{
			if (path.equals(field.path("field").asText()))
			{
				return field;
			}
		}
		throw new AssertionError("field not found: " + pFieldName);
	}

	private static boolean everyFieldIsSlashed(final ObjectNode pPolicy)
	{
		for (final JsonNode field : pPolicy.path("fields"))
		{
			final String path = field.path("field").asText();
			if (path.isEmpty() || path.charAt(0) != '/')
			{
				return false;
			}
		}
		return true;
	}

	private static String paramValue(final JsonNode pNode, final String pParamName)
	{
		for (final JsonNode param : pNode.path("params"))
		{
			if (pParamName.equals(param.path("name").asText()))
			{
				return param.path("value").asText();
			}
		}
		throw new AssertionError("param not found: " + pParamName);
	}

	// ------------------------------------------------------------------------------------------------ lot R8 (2026-09-27)

	/** Two AVERAGE nodes comparing the exact same fields (only {@code minimumScore} differs - what {@code DecisionTreePlan.autoLabel} never reads) - the live PersVol7 shape, PipelineStep 587. */
	private static DecisionTreePlan.Node averageNode(final String pId, final int pMinimumScore, final String pOnTrue, final String pOnFalse, final String... pFields)
	{
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node(pId, DecisionTreePlan.FUNCTION_AVERAGE);
		node.minimumScore = Integer.valueOf(pMinimumScore);
		for (final String field : pFields)
		{
			node.fields.add(new DecisionTreePlan.FieldRef(field, "ebx-addon-mame@jaroWinkler"));
		}
		node.onTrue = pOnTrue;
		node.onFalse = pOnFalse;
		return node;
	}

	/** Live shape: a stricter AVERAGE node (N1) falling back to a looser AVERAGE node (N2) over the SAME fields - {@code generate_matching_policy}'s own "Duplicate decision node name in the decision tree" refusal. */
	private static void buildMatchingPolicy_duplicateAutoLabels_distinctNamesInNestedOutput()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = MatchingPolicyTranslatorTest.averageNode("N1", 95, DecisionTreePlan.LEAF_MATCH, "N2", "firstName", "lastName");
		final DecisionTreePlan.Node n2 = MatchingPolicyTranslatorTest.averageNode("N2", 75, DecisionTreePlan.LEAF_SUSPECTED, DecisionTreePlan.LEAF_NOT_MATCHED, "firstName", "lastName");
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.<String>emptyList());

		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Personne", requirements, true);
		final JsonNode root = policy.path("decisionTree");
		final JsonNode child = root.path("falseNode");
		MatchingPolicyTranslatorTest.check("root node keeps the plain auto-label", "Similar firstName and lastName".equals(root.path("name").asText()));
		MatchingPolicyTranslatorTest.check("the second, colliding node gets its own id appended: " + child.path("name").asText(),
			"Similar firstName and lastName (N2)".equals(child.path("name").asText()));
		MatchingPolicyTranslatorTest.check("the two nested names are distinct", !root.path("name").asText().equals(child.path("name").asText()));
	}

	private static void buildMatchingPolicy_noCollision_namesUnchanged()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = MatchingPolicyTranslatorTest.averageNode("N1", 90, DecisionTreePlan.LEAF_MATCH, "N2", "firstName", "lastName");
		final DecisionTreePlan.Node n2 = MatchingPolicyTranslatorTest.averageNode("N2", 80, DecisionTreePlan.LEAF_SUSPECTED, DecisionTreePlan.LEAF_NOT_MATCHED, "email");
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.<String>emptyList());

		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Personne", requirements, true);
		final JsonNode root = policy.path("decisionTree");
		MatchingPolicyTranslatorTest.check("a plan with no collision renders exactly as before this fix (root)",
			"Similar firstName and lastName".equals(root.path("name").asText()));
		MatchingPolicyTranslatorTest.check("a plan with no collision renders exactly as before this fix (child, no suffix)",
			"Similar email".equals(root.path("falseNode").path("name").asText()));
	}

	/** Two nodes both comparing the SAME child (Adresse) field - their auto-labels collide ("Same Adresse.city"), the written names stay distinct and the field is declared once on the policy. */
	private static void translate_twoNodesSameChildField_distinctNodeNames()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.FieldRef relationFieldOne = new DecisionTreePlan.FieldRef("city", "ebx-addon-mame@exact");
		relationFieldOne.via = "Adresse";
		relationFieldOne.dataType = "STRING";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(relationFieldOne);
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = "N2";

		final DecisionTreePlan.FieldRef relationFieldTwo = new DecisionTreePlan.FieldRef("city", "ebx-addon-mame@exact");
		relationFieldTwo.via = "Adresse";
		relationFieldTwo.dataType = "STRING";
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(relationFieldTwo);
		n2.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;

		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final ObjectNode requirements = MatchingPolicyTranslatorTest.requirementsWithDecisionTree(plan, Collections.singletonList("email"));
		final Map<String, String> childInfo = new HashMap<String, String>();
		childInfo.put("Adresse", "/root/Adresse");

		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslator.translate("Personne", requirements, true, childInfo);
		MatchingPolicyTranslatorTest.check("one relation field (the same path in both nodes)", result.relationFieldWeights.size() == 1);
		final String rootName = result.matchingPolicy.path("decisionTree").path("name").asText();
		final String childName = result.matchingPolicy.path("decisionTree").path("falseNode").path("name").asText();
		MatchingPolicyTranslatorTest.check("the two nodes' names are distinct: '" + rootName + "' vs '" + childName + "'", !rootName.equals(childName));
		int declared = 0;
		for (final JsonNode field : result.matchingPolicy.path("fields"))
		{
			if ("/root/Adresse/city".equals(field.path("businessObjectField").asText(null)))
			{
				declared++;
			}
		}
		MatchingPolicyTranslatorTest.check("declared once on the policy", declared == 1);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
