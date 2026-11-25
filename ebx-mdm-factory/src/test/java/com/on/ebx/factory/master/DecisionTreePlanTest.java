package com.on.ebx.factory.master;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like every other test of this module: run the main, a failure throws. Covers {@link
 * DecisionTreePlan}'s own contract - the binary decision-tree model `docs/mockups/review-match-plan-binary-tree.html`
 * fixes, 2026-09-22 (`docs/MATCHING.md` &sect;16).
 */
public final class DecisionTreePlanTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		DecisionTreePlanTest.fromTiers_buildsAChainedFlatPlan();
		DecisionTreePlanTest.toNested_matchesTheLegacyLabelAndShapeForACascade();
		DecisionTreePlanTest.toNested_autoMergeForcingIsNotThisClassJob();
		DecisionTreePlanTest.fromNested_tolerantLeafNames_liveCompanyStyleTree();
		DecisionTreePlanTest.fromNested_unknownFunction_keptAsPredicatePassthroughWithWarning();
		DecisionTreePlanTest.roundTrip_toNestedThenFromNested_sameStructure();
		DecisionTreePlanTest.validate_noRoot();
		DecisionTreePlanTest.validate_unknownBranchTarget();
		DecisionTreePlanTest.validate_cycleDetected();
		DecisionTreePlanTest.validate_averageMissingMinimumScore();
		DecisionTreePlanTest.validate_atLeastNInvalidMinimumMatches();
		DecisionTreePlanTest.validate_duplicateNodeId();
		DecisionTreePlanTest.validate_nodeIdReservedLeafName();
		DecisionTreePlanTest.validate_nodeShared();
		// SAME_OUTCOME_BOTH_BRANCHES (PersVol5, 2026-09-26 live failure): a node whose onTrue/onFalse are identical
		// decides nothing, and match-merge itself refuses it uninformatively at generate/apply time.
		DecisionTreePlanTest.validate_sameOutcomeBothBranches_sameLeaf_refused();
		DecisionTreePlanTest.validate_sameOutcomeBothBranches_sameNodeId_refused();
		DecisionTreePlanTest.validate_sameOutcomeBothBranches_differentLeaves_accepted();
		// RELATION_NODE_OUTCOMES_MUST_DIFFER (lot R9, 2026-09-27, docs/MATCHING.md sect;40, live PersVol7 run 169):
		// a relation node whose onTrue/onFalse both collapse to MATCH-or-SUSPECTED once auto-merge is off decides
		// nothing - the child evidence would silently never reach the add-on.
		DecisionTreePlanTest.validate_relationNodeOutcomesMustDiffer_bothSuspected_refused();
		DecisionTreePlanTest.validate_relationNodeOutcomesMustDiffer_matchAndSuspected_refused();
		DecisionTreePlanTest.validate_relationNodeOutcomesMustDiffer_onFalseNotMatched_accepted();
		DecisionTreePlanTest.validate_relationNodeOutcomesMustDiffer_onFalseIsAnotherNode_accepted();
		DecisionTreePlanTest.validate_relationNodeOutcomesMustDiffer_nonRelationNode_notChecked();
		// reachableOnlyViaOnTrue (lot R15, 2026-09-28, docs/MATCHING.md dated entry): a pure structural helper on
		// MatchPlanLlmPass, tested here alongside the plan it operates on - true only when the tree HAS a relation
		// node and every one of them is reached exclusively through onTrue edges (the "child evidence used only as
		// a filter" shape).
		DecisionTreePlanTest.reachableOnlyViaOnTrue_filterOnlyShape_true();
		DecisionTreePlanTest.reachableOnlyViaOnTrue_relationNodeOnOnFalsePath_false();
		DecisionTreePlanTest.reachableOnlyViaOnTrue_relationNodeIsRoot_false();
		DecisionTreePlanTest.reachableOnlyViaOnTrue_noRelationNodeAtAll_false();
		// IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH / CONTRADICTION_NODE_NULL_HANDLING_MUST_BE_DO_NOTHING
		// (lot R13, 2026-09-28, docs/MATCHING.md dated entry, live PersVol7 measurement 2026-09-27 23:51).
		DecisionTreePlanTest.validate_identityCountNode_atLeastN_oneNullDoNothing_refused();
		DecisionTreePlanTest.validate_identityCountNode_atLeastN_oneNullNotMatch_accepted();
		DecisionTreePlanTest.validate_identityCountNode_multiFieldAllFields_oneNullDoNothing_refused();
		DecisionTreePlanTest.validate_identityCountNode_singleFieldAllFields_referenceRole_accepted();
		DecisionTreePlanTest.validate_identityCountNode_singleFieldAllFields_ordinaryField_refused();
		DecisionTreePlanTest.validate_identityCountNode_singleFieldAllFields_ordinaryField_notMatch_accepted();
		DecisionTreePlanTest.validate_identityCountNode_singleFieldAllFields_relationField_accepted();
		DecisionTreePlanTest.validate_identityCountNode_contradictionMarked_neverChecked();
		DecisionTreePlanTest.validate_identityCountNode_relationFieldExempt_accepted();
		DecisionTreePlanTest.validate_identityCountNode_onTrueIsAnotherNode_notChecked();
		DecisionTreePlanTest.validate_identityCountNode_omittedOneNull_defaultsSafely_accepted();
		DecisionTreePlanTest.validate_contradictionNode_effectiveNullHandlingNotDoNothing_refused();
		DecisionTreePlanTest.validate_contradictionNode_doNothing_accepted();
		DecisionTreePlanTest.validate_contradictionNode_omittedNullHandling_defaultsToNotMatch_refused();
		DecisionTreePlanTest.validate_contradictionNode_relationFieldExempt_accepted();
		DecisionTreePlanTest.validate_contradictionNode_markedButWrongShape_refused();
		DecisionTreePlanTest.validate_contradictionNode_unmarkedMatchingShape_neverChecked();
		DecisionTreePlanTest.validate_contradictionNode_onTrueIsLeaf_notChecked();
		DecisionTreePlanTest.validate_validSixNodeMockupTree();
		DecisionTreePlanTest.fromTiers_alwaysProducesASingleParentModel();
		DecisionTreePlanTest.fromNested_alwaysProducesASingleParentModel();
		DecisionTreePlanTest.sameStructure_ignoresIdsAndOrder();
		DecisionTreePlanTest.sameStructure_falseWhenABranchOrThresholdChanges();
		DecisionTreePlanTest.readFrom_prefersDecisionTreeOverTiers();
		DecisionTreePlanTest.fieldFromJson_longKeyFallback_forPlansStoredBeforeTheShortKeyFix();
		DecisionTreePlanTest.relationField_roundTripsViaAndDataType();
		DecisionTreePlanTest.relationField_labelForShowsChildDotField();
		DecisionTreePlanTest.validate_relationField_unknownChildAndAttribute();
		DecisionTreePlanTest.validate_relationField_aloneAtRoot();
		DecisionTreePlanTest.validate_relationField_isClusteringField();
		DecisionTreePlanTest.validate_relationField_validWhenKnownAndNotAloneAtRoot();
		DecisionTreePlanTest.relationFromNestedField_recognisesTheMeasuredShapes();
		DecisionTreePlanTest.toNested_relationField_writesBusinessObjectField();
		DecisionTreePlanTest.relationFromNestedField_nullForPlainField();
		// lot R8 (2026-09-27, live PersVol7, PipelineStep 587): freezeAndDisambiguateLabels must give two nodes
		// with the same auto-label (or the same explicit label) distinct, deterministic final names.
		DecisionTreePlanTest.freezeAndDisambiguateLabels_collidingAutoLabels_secondGetsIdSuffix();
		DecisionTreePlanTest.freezeAndDisambiguateLabels_collidingExplicitLabels_secondGetsIdSuffix();
		DecisionTreePlanTest.freezeAndDisambiguateLabels_noCollision_labelsUnchanged();
		DecisionTreePlanTest.freezeAndDisambiguateLabels_threeWayCollision_eachSuffixDistinct();
		DecisionTreePlanTest.freezeAndDisambiguateLabels_idempotent_secondCallDoesNotResuffix();
		// standaloneIdentifierFieldNames (lot R16, 2026-09-28, docs/MATCHING.md sect;46): the pure structural
		// detector MatchingPolicyTranslator's own PRECLUSTERING_DOMINANCE guard is built on.
		DecisionTreePlanTest.standaloneIdentifierFieldNames_rootExactNode_returnsField();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_referenceRoleField_excluded();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_relationField_excluded();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_afterAverageNode_neverVisited();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_stackedIdentifiers_bothReturned();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_minimumScoreNot100_excluded();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_minimumScore100Explicit_included();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_multiFieldAllFields_excluded();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_onTrueIsAnotherNode_included();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_onTrueReachesMatchedDeeper_included();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_onTrueOnlyReachesNotMatched_excluded();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_onTrueIsAnotherNode_fuzzyRoot_excluded();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_laterChainNodeWithContinuingTrueSide_excluded();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_emptyPlan_returnsEmpty();
		DecisionTreePlanTest.standaloneIdentifierFieldNames_fuzzyAlgorithm_excluded();
		System.out.println("DecisionTreePlanTest OK");
	}

	// ------------------------------------------------------------------------------ standalone identifier detection

	private static void standaloneIdentifierFieldNames_rootExactNode_returnsField() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n0.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact"));
		n0.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n0.onFalse = "N1";
		plan.nodes.add(n0);
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AVERAGE);
		n1.minimumScore = Integer.valueOf(85);
		n1.fields.add(new DecisionTreePlan.FieldRef("name", "fuzzyFullText"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("root exact node found: " + identifiers, identifiers.size() == 1 && "emailKey".equals(identifiers.get(0)));
	}

	/** A parent-confirmation node ({@link DecisionTreePlan.FieldRef#referenceRole}) is a different concept, never an identifier. */
	private static void standaloneIdentifierFieldNames_referenceRoleField_excluded() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef parentRef = new DecisionTreePlan.FieldRef("parentRef", "ebx-addon-mame@exact");
		parentRef.referenceRole = true;
		n0.fields.add(parentRef);
		n0.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n0.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n0);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("referenceRole field never counted as identifier: " + identifiers, identifiers.isEmpty());
	}

	/** A relation ("via") field is a child-evidence concept, never a standalone identifier of this entity. */
	private static void standaloneIdentifierFieldNames_relationField_excluded() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relation = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relation.via = "Adresse";
		n0.fields.add(relation);
		n0.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n0.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n0);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("relation field never counted as identifier: " + identifiers, identifiers.isEmpty());
	}

	/** An exact single-field node placed AFTER (below) an AVERAGE node is never visited - the walk stops at the first non-qualifying node. */
	private static void standaloneIdentifierFieldNames_afterAverageNode_neverVisited() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_AVERAGE);
		n0.minimumScore = Integer.valueOf(85);
		n0.fields.add(new DecisionTreePlan.FieldRef("name", "fuzzyFullText"));
		n0.onTrue = "N1";
		n0.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n0);
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("nothing behind an AVERAGE root is ever visited: " + identifiers, identifiers.isEmpty());
	}

	/** Two exact identifiers stacked before the first similarity node both qualify - stacking, not mutual dominance, is the point. */
	private static void standaloneIdentifierFieldNames_stackedIdentifiers_bothReturned() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n0.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact"));
		n0.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n0.onFalse = "N1";
		plan.nodes.add(n0);
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("nationalId", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = "N2";
		plan.nodes.add(n1);
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_AVERAGE);
		n2.minimumScore = Integer.valueOf(85);
		n2.fields.add(new DecisionTreePlan.FieldRef("name", "fuzzyFullText"));
		n2.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n2);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("both stacked identifiers found, in root-to-leaf order: " + identifiers,
			identifiers.size() == 2 && "emailKey".equals(identifiers.get(0)) && "nationalId".equals(identifiers.get(1)));
	}

	private static void standaloneIdentifierFieldNames_minimumScoreNot100_excluded() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef field = new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact");
		field.minimumScore = Integer.valueOf(95);
		n0.fields.add(field);
		n0.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n0.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n0);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("a non-100 minimumScore is not a strong identifier node: " + identifiers, identifiers.isEmpty());
	}

	private static void standaloneIdentifierFieldNames_minimumScore100Explicit_included() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef field = new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact");
		field.minimumScore = Integer.valueOf(100);
		n0.fields.add(field);
		n0.onTrue = DecisionTreePlan.LEAF_MATCH;
		n0.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n0);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("an explicit minimumScore of 100 is equivalent to omitted: " + identifiers, identifiers.size() == 1);
	}

	private static void standaloneIdentifierFieldNames_multiFieldAllFields_excluded() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n0.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact"));
		n0.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact"));
		n0.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n0.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n0);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("two fields is not a standalone identifier node: " + identifiers, identifiers.isEmpty());
	}

	/** Lot MT (2026-10-04): an exact key whose onTrue continues to another node that can reach SUSPECTED IS a standalone identifier (it used to be excluded). */
	private static void standaloneIdentifierFieldNames_onTrueIsAnotherNode_included() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n0.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact"));
		n0.onTrue = "N1";
		n0.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n0);
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("phone", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("root exact key leading to a node that reaches SUSPECTED: " + identifiers, identifiers.size() == 1 && "emailKey".equals(identifiers.get(0)));
	}

	/** Lot MT: the same, when the node on the true side reaches MATCHED two levels down (and only through its onFalse). */
	private static void standaloneIdentifierFieldNames_onTrueReachesMatchedDeeper_included() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n0.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact"));
		n0.onTrue = "N1";
		n0.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n0);
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AVERAGE);
		n1.minimumScore = Integer.valueOf(90);
		n1.fields.add(new DecisionTreePlan.FieldRef("name", "fuzzyFullText"));
		n1.onTrue = DecisionTreePlan.LEAF_NOT_MATCHED;
		n1.onFalse = "N2";
		plan.nodes.add(n1);
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(new DecisionTreePlan.FieldRef("phone", "ebx-addon-mame@exact"));
		n2.onTrue = DecisionTreePlan.LEAF_MATCH;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n2);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("a path two levels down reaches MATCHED: " + identifiers, identifiers.size() == 1 && "emailKey".equals(identifiers.get(0)));
	}

	/** Lot MT: an exact key whose true side can only ever reach NOT_MATCHED decides nothing positive - no dominance. */
	private static void standaloneIdentifierFieldNames_onTrueOnlyReachesNotMatched_excluded() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n0.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact"));
		n0.onTrue = "N1";
		n0.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		plan.nodes.add(n0);
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("phone", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_NOT_MATCHED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("the true side only reaches NOT_MATCHED: " + identifiers, identifiers.isEmpty());
		DecisionTreePlanTest.check("a missing target is not positive", !DecisionTreePlan.leadsToPositiveOutcome(plan, "NOPE"));
		DecisionTreePlanTest.check("a null target is not positive", !DecisionTreePlan.leadsToPositiveOutcome(plan, null));
	}

	/** Lot MT: a NON-exact root keeps getting no dominance even when its true side leads to another node. */
	private static void standaloneIdentifierFieldNames_onTrueIsAnotherNode_fuzzyRoot_excluded() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n0.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@fuzzyFullText"));
		n0.onTrue = "N1";
		n0.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n0);
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("phone", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);

		DecisionTreePlanTest.check("a fuzzy root: no identifier", DecisionTreePlan.standaloneIdentifierFieldNames(plan).isEmpty());
	}

	/** Lot MT: only the ROOT may continue on its true side - E3's phone (a later chain node) then a names confirmation is still not an identifier. */
	private static void standaloneIdentifierFieldNames_laterChainNodeWithContinuingTrueSide_excluded() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n0.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact"));
		n0.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n0.onFalse = "N1";
		plan.nodes.add(n0);
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("phone", "ebx-addon-mame@exact"));
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_AVERAGE);
		n2.minimumScore = Integer.valueOf(82);
		n2.fields.add(new DecisionTreePlan.FieldRef("name", "fuzzyFullText"));
		n2.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n2);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("only the root key: " + identifiers, identifiers.size() == 1 && "emailKey".equals(identifiers.get(0)));
	}

	private static void standaloneIdentifierFieldNames_emptyPlan_returnsEmpty() throws Exception
	{
		DecisionTreePlanTest.check("null plan", DecisionTreePlan.standaloneIdentifierFieldNames(null).isEmpty());
		DecisionTreePlanTest.check("empty plan", DecisionTreePlan.standaloneIdentifierFieldNames(new DecisionTreePlan.Plan()).isEmpty());
	}

	private static void standaloneIdentifierFieldNames_fuzzyAlgorithm_excluded() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n0.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@hybridFuzzy"));
		n0.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n0.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n0);

		final List<String> identifiers = DecisionTreePlan.standaloneIdentifierFieldNames(plan);
		DecisionTreePlanTest.check("a fuzzy algorithm is never a standalone identifier: " + identifiers, identifiers.isEmpty());
	}

	// --------------------------------------------------------------------------------------------- relation fields

	/** Lot R2 (2026-09-26): {@link DecisionTreePlan.FieldRef#via}/{@code dataType} round-trip through {@link DecisionTreePlan#toJson}/{@link DecisionTreePlan#fromJson}. */
	private static void relationField_roundTripsViaAndDataType() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef ownField = new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact");
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		relationField.dataType = "STRING";
		node.fields.add(ownField);
		node.fields.add(relationField);
		node.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		node.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(node);

		final DecisionTreePlan.Plan roundTripped = DecisionTreePlan.fromJson(DecisionTreePlan.toJson(plan));
		DecisionTreePlanTest.check("one node", roundTripped.nodes.size() == 1);
		final DecisionTreePlan.FieldRef roundTrippedOwn = roundTripped.nodes.get(0).fields.get(0);
		DecisionTreePlanTest.check("own field has no via", !roundTrippedOwn.isRelation());
		final DecisionTreePlan.FieldRef roundTrippedRelation = roundTripped.nodes.get(0).fields.get(1);
		DecisionTreePlanTest.check("relation field is recognised", roundTrippedRelation.isRelation());
		DecisionTreePlanTest.check("via round-trips", "Adresse".equals(roundTrippedRelation.via));
		DecisionTreePlanTest.check("field name round-trips", "cityKey".equals(roundTrippedRelation.field));
		DecisionTreePlanTest.check("dataType round-trips", "STRING".equals(roundTrippedRelation.dataType));
	}

	private static void relationField_labelForShowsChildDotField() throws Exception
	{
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		node.fields.add(relationField);
		node.onTrue = DecisionTreePlan.LEAF_MATCH;
		node.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final String label = DecisionTreePlan.labelFor(node);
		DecisionTreePlanTest.check("auto label names the child: " + label, label.contains("Adresse.cityKey"));
	}

	private static DecisionTreePlan.RelationCatalog personneAdresseCatalog()
	{
		final java.util.Map<String, java.util.Set<String>> byChild = new java.util.LinkedHashMap<String, java.util.Set<String>>();
		final java.util.Set<String> adresseFields = new java.util.LinkedHashSet<String>();
		adresseFields.add("cityKey");
		adresseFields.add("countryCode");
		byChild.put("Adresse", adresseFields);
		return new DecisionTreePlan.RelationCatalog(byChild);
	}

	/** A minimal plan whose root has one relation field and one plain field, and a second node with the relation field too. */
	private static DecisionTreePlan.Plan personneAdressePlan(final boolean pRelationAtRootAlone)
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node root = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		if (!pRelationAtRootAlone)
		{
			root.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact"));
		}
		root.fields.add(relationField);
		root.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		root.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(root);
		return plan;
	}

	private static void validate_relationField_unknownChildAndAttribute() throws Exception
	{
		final DecisionTreePlan.Plan plan = DecisionTreePlanTest.personneAdressePlan(false);
		// unknown child
		final DecisionTreePlan.ValidationResult unknownChild = DecisionTreePlan.validate(plan, null, null, new DecisionTreePlan.RelationCatalog(null), null);
		DecisionTreePlanTest.check("unknown child refused: " + unknownChild.reasons,
			DecisionTreePlanTest.anyContains(unknownChild.reasons, "RELATION_FIELD_UNKNOWN_CHILD"));

		// known child, unknown attribute
		final java.util.Map<String, java.util.Set<String>> byChild = new java.util.LinkedHashMap<String, java.util.Set<String>>();
		byChild.put("Adresse", java.util.Collections.singleton("streetName"));
		final DecisionTreePlan.ValidationResult unknownAttribute = DecisionTreePlan.validate(plan, null, null, new DecisionTreePlan.RelationCatalog(byChild), null);
		DecisionTreePlanTest.check("unknown attribute refused: " + unknownAttribute.reasons,
			DecisionTreePlanTest.anyContains(unknownAttribute.reasons, "RELATION_FIELD_UNKNOWN_ATTRIBUTE"));
	}

	private static void validate_relationField_aloneAtRoot() throws Exception
	{
		final DecisionTreePlan.Plan plan = DecisionTreePlanTest.personneAdressePlan(true);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan, null, null, DecisionTreePlanTest.personneAdresseCatalog(), null);
		DecisionTreePlanTest.check("relation field alone at root refused: " + result.reasons,
			DecisionTreePlanTest.anyContains(result.reasons, "RELATION_FIELD_ALONE_AT_ROOT"));
	}

	private static void validate_relationField_isClusteringField() throws Exception
	{
		final DecisionTreePlan.Plan plan = DecisionTreePlanTest.personneAdressePlan(false);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan, null, null, DecisionTreePlanTest.personneAdresseCatalog(),
			java.util.Collections.singletonList("cityKey"));
		DecisionTreePlanTest.check("relation field as clustering field refused: " + result.reasons,
			DecisionTreePlanTest.anyContains(result.reasons, "RELATION_FIELD_IS_CLUSTERING_FIELD"));
	}

	private static void validate_relationField_validWhenKnownAndNotAloneAtRoot() throws Exception
	{
		final DecisionTreePlan.Plan plan = DecisionTreePlanTest.personneAdressePlan(false);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan, null, null, DecisionTreePlanTest.personneAdresseCatalog(), null);
		DecisionTreePlanTest.check("a well-formed relation field plan validates: " + result.reasons, result.ok);
	}

	/** {@link DecisionTreePlan#relationFromNestedField}: the shapes the add-on 6.3.0.5-RC3 reads back (measured on W13BO, 2026-10-12) - a tree field {@code field = /root/Adresse/city}, a policy field {@code field = null, businessObjectField = ...}. */
	private static void relationFromNestedField_recognisesTheMeasuredShapes() throws Exception
	{
		final JsonNode treeField = DecisionTreePlanTest.MAPPER.readTree("{\"field\":\"/root/Adresse/city\",\"algorithm\":\"ebx-addon-mame@exact\"}");
		final DecisionTreePlan.RelationRef relation = DecisionTreePlan.relationFromNestedField(treeField);
		DecisionTreePlanTest.check("tree field: relation recognised", relation != null);
		DecisionTreePlanTest.check("child entity resolved from the table path", "Adresse".equals(relation.childEntity) && "/root/Adresse".equals(relation.childTablePath));
		DecisionTreePlanTest.check("field name is the child's, no slash", "city".equals(relation.field));
		final JsonNode policyField = DecisionTreePlanTest.MAPPER.readTree("{\"field\":null,\"businessObjectField\":\"/root/Adresse/postalCode\",\"useRelationships\":\"BY_FOREIGN_KEY\","
			+ "\"location\":{\"associationTable\":\"/root/Adresse/personneRef\",\"mode\":\"SAME_DATASET\"}}");
		final DecisionTreePlan.RelationRef fromPolicy = DecisionTreePlan.relationFromNestedField(policyField);
		DecisionTreePlanTest.check("policy field: relation recognised through businessObjectField", fromPolicy != null && "Adresse".equals(fromPolicy.childEntity)
			&& "postalCode".equals(fromPolicy.field));
		final DecisionTreePlan.FromNestedResult read = DecisionTreePlan.fromNested(DecisionTreePlanTest.MAPPER.readTree("{\"name\":\"Same city\",\"function\":\"All fields match\","
			+ "\"fields\":[{\"field\":\"/lastName\",\"algorithm\":\"ebx-addon-mame@exact\"},{\"field\":\"/root/Adresse/city\",\"algorithm\":\"ebx-addon-mame@exact\"}],"
			+ "\"trueNode\":{\"name\":\"Suspect\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}"));
		final List<DecisionTreePlan.FieldRef> fields = read.plan.nodes.get(0).fields;
		DecisionTreePlanTest.check("fromNested reads the stored relation field back as via + field", !fields.get(0).isRelation() && "lastName".equals(fields.get(0).field)
			&& fields.get(1).isRelation() && "Adresse".equals(fields.get(1).via) && "city".equals(fields.get(1).field));
	}

	/** toNested writes a rewritten relation field as field + businessObjectField (same absolute path) and a plain field with field only. */
	private static void toNested_relationField_writesBusinessObjectField()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		node.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact"));
		final DecisionTreePlan.FieldRef relation = new DecisionTreePlan.FieldRef("root/Adresse/city", "ebx-addon-mame@exact");
		relation.via = "Adresse";
		node.fields.add(relation);
		node.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		node.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(node);
		final JsonNode fields = DecisionTreePlan.toNested(plan).path("fields");
		DecisionTreePlanTest.check("a plain field has `field` only", "/lastName".equals(fields.get(0).path("field").asText()) && !fields.get(0).has("businessObjectField"));
		DecisionTreePlanTest.check("a relation field has `field` and the same `businessObjectField`", "/root/Adresse/city".equals(fields.get(1).path("field").asText())
			&& "/root/Adresse/city".equals(fields.get(1).path("businessObjectField").asText()));
	}

	private static void relationFromNestedField_nullForPlainField() throws Exception
	{
		final JsonNode fieldJson = DecisionTreePlanTest.MAPPER.readTree("{\"field\":\"/lastName\",\"algorithm\":\"ebx-addon-mame@exact\"}");
		DecisionTreePlanTest.check("a plain field is never recognised as a relation", DecisionTreePlan.relationFromNestedField(fieldJson) == null);
	}

	// --------------------------------------------------------------------------------------------------- fromTiers

	private static void fromTiers_buildsAChainedFlatPlan() throws Exception
	{
		final JsonNode tiers = DecisionTreePlanTest.MAPPER.readTree("[" + "{\"outcome\":\"MATCH\",\"type\":\"EXACT\",\"fields\":[{\"field\":\"vatNo\","
			+ "\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}," + "{\"outcome\":\"SUSPECTED\",\"type\":\"AVERAGE\",\"minimumScore\":90,"
			+ "\"fields\":[{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"weight\":3}]}]");
		final DecisionTreePlan.Plan plan = DecisionTreePlan.fromTiers(tiers);
		DecisionTreePlanTest.check("two nodes", plan.nodes.size() == 2);
		DecisionTreePlanTest.check("root is the first", "T1".equals(plan.root));
		DecisionTreePlanTest.check("first node ALL_FIELDS", DecisionTreePlan.FUNCTION_ALL_FIELDS.equals(plan.nodes.get(0).function));
		DecisionTreePlanTest.check("first node onTrue MATCH", DecisionTreePlan.LEAF_MATCH.equals(plan.nodes.get(0).onTrue));
		DecisionTreePlanTest.check("first node onFalse chains to the second", "T2".equals(plan.nodes.get(0).onFalse));
		DecisionTreePlanTest.check("second node AVERAGE", DecisionTreePlan.FUNCTION_AVERAGE.equals(plan.nodes.get(1).function));
		DecisionTreePlanTest.check("second node onTrue SUSPECTED", DecisionTreePlan.LEAF_SUSPECTED.equals(plan.nodes.get(1).onTrue));
		DecisionTreePlanTest.check("second (last) node onFalse is the final NOT_MATCHED", DecisionTreePlan.LEAF_NOT_MATCHED.equals(plan.nodes.get(1).onFalse));
		final DecisionTreePlan.ValidationResult validation = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("a converted cascade is itself a valid plan: " + validation.reasons, validation.ok);
	}

	// ----------------------------------------------------------------------------------------------------- toNested

	/** The exact label/shape {@code MatchingPolicyTranslator}'s own pre-lot {@code buildNode} produced for one EXACT tier - a regression pin. */
	private static void toNested_matchesTheLegacyLabelAndShapeForACascade() throws Exception
	{
		final JsonNode tiers = DecisionTreePlanTest.MAPPER.readTree("[{\"outcome\":\"MATCH\",\"type\":\"EXACT\",\"fields\":[{\"field\":\"vatNo\","
			+ "\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}]");
		final DecisionTreePlan.Plan plan = DecisionTreePlan.fromTiers(tiers);
		final ObjectNode nested = DecisionTreePlan.toNested(plan);
		DecisionTreePlanTest.check("auto-generated label matches the legacy 'Same <field>' convention", "Same vatNo".equals(nested.path("name").asText()));
		DecisionTreePlanTest.check("function is the schema's own label", "All fields match".equals(nested.path("function").asText()));
		DecisionTreePlanTest.check("field path is record-relative with a leading slash", "/vatNo".equals(nested.path("fields").get(0).path("field").asText()));
		DecisionTreePlanTest.check("field minimumScore param", "100".equals(nested.path("fields").get(0).path("params").get(0).path("value").asText()));
		DecisionTreePlanTest.check("trueNode is the MATCHED leaf (schema's own enum token)", "MATCHED".equals(nested.path("trueNode").path("name").asText()));
		DecisionTreePlanTest.check("leaf's function is null", nested.path("trueNode").path("function").isNull());
		DecisionTreePlanTest.check("falseNode is the final NOT_MATCHED leaf", "NOT_MATCHED".equals(nested.path("falseNode").path("name").asText()));
	}

	/** DecisionTreePlan.toNested never forces MATCHED down to SUSPECTED - that policy decision is MatchingPolicyTranslator's alone (class Javadoc). */
	private static void toNested_autoMergeForcingIsNotThisClassJob() throws Exception
	{
		final JsonNode tiers = DecisionTreePlanTest.MAPPER
			.readTree("[{\"outcome\":\"MATCH\",\"type\":\"EXACT\",\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}]");
		final ObjectNode nested = DecisionTreePlan.toNested(DecisionTreePlan.fromTiers(tiers));
		DecisionTreePlanTest.check("a MATCH-terminal leaf is always written as MATCHED here, whatever autoMerge would later do",
			"MATCHED".equals(nested.path("trueNode").path("name").asText()));
	}

	// ---------------------------------------------------------------------------------------------------- fromNested

	/**
	 * The LIVE {@code Companies} configuration's own shape (CLAUDE.md &sect;1/&sect;8): one AVERAGE node, leaves
	 * named {@code "Suspect"}/{@code "Not Match"} - display strings, not the schema's documented {@code
	 * SUSPECTED}/{@code NOT_MATCHED} tokens. {@link DecisionTreePlan#fromNested} must read both tolerantly.
	 */
	private static void fromNested_tolerantLeafNames_liveCompanyStyleTree() throws Exception
	{
		final String json = "{\"name\":\"Similar name and country\",\"function\":\"Average\",\"params\":[{\"name\":\"minimumScore\",\"value\":\"80\"}],"
			+ "\"fields\":[{\"field\":\"/name\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"bothNullValueHandling\":\"NOT_MATCH\","
			+ "\"oneNullValueHandling\":\"NOT_MATCH\",\"params\":[{\"name\":\"weight\",\"value\":\"10\"}]}],"
			+ "\"trueNode\":{\"name\":\"Suspect\",\"function\":null},\"falseNode\":{\"name\":\"Not Match\",\"function\":null}}";
		final DecisionTreePlan.FromNestedResult result = DecisionTreePlan.fromNested(DecisionTreePlanTest.MAPPER.readTree(json));
		DecisionTreePlanTest.check("no warnings for a well-formed tree", result.warnings.isEmpty());
		DecisionTreePlanTest.check("one node", result.plan.nodes.size() == 1);
		final DecisionTreePlan.Node node = result.plan.nodes.get(0);
		DecisionTreePlanTest.check("root points at it", node.id.equals(result.plan.root));
		DecisionTreePlanTest.check("function AVERAGE", DecisionTreePlan.FUNCTION_AVERAGE.equals(node.function));
		DecisionTreePlanTest.check("minimumScore read from node params", node.minimumScore != null && node.minimumScore.intValue() == 80);
		DecisionTreePlanTest.check("field 'name' (leading slash stripped)", "name".equals(node.fields.get(0).field));
		DecisionTreePlanTest.check("field weight read from field params", node.fields.get(0).weight != null && node.fields.get(0).weight.intValue() == 10);
		DecisionTreePlanTest.check("'Suspect' tolerantly read as SUSPECTED", DecisionTreePlan.LEAF_SUSPECTED.equals(node.onTrue));
		DecisionTreePlanTest.check("'Not Match' tolerantly read as NOT_MATCHED (not confused with MATCH)", DecisionTreePlan.LEAF_NOT_MATCHED.equals(node.onFalse));
	}

	private static void fromNested_unknownFunction_keptAsPredicatePassthroughWithWarning() throws Exception
	{
		final String json = "{\"name\":\"Weird\",\"function\":\"Something Else\",\"fields\":[{\"field\":\"/x\",\"algorithm\":\"ebx-addon-mame@exact\"}],"
			+ "\"trueNode\":{\"name\":\"MATCHED\",\"function\":null},\"falseNode\":{\"name\":\"NOT_MATCHED\",\"function\":null}}";
		final DecisionTreePlan.FromNestedResult result = DecisionTreePlan.fromNested(DecisionTreePlanTest.MAPPER.readTree(json));
		DecisionTreePlanTest.check("the node is not lost", result.plan.nodes.size() == 1);
		DecisionTreePlanTest.check("kept as a PREDICATE passthrough", DecisionTreePlan.FUNCTION_PREDICATE.equals(result.plan.nodes.get(0).function));
		DecisionTreePlanTest.check("a warning names the unknown function", !result.warnings.isEmpty() && result.warnings.get(0).contains("Something Else"));
	}

	private static void roundTrip_toNestedThenFromNested_sameStructure() throws Exception
	{
		final JsonNode tiers = DecisionTreePlanTest.MAPPER.readTree("[{\"outcome\":\"MATCH\",\"type\":\"EXACT\",\"fields\":[{\"field\":\"vatNo\","
			+ "\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}," + "{\"outcome\":\"SUSPECTED\",\"type\":\"AVERAGE\",\"minimumScore\":80,"
			+ "\"fields\":[{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"weight\":10}]}]");
		final DecisionTreePlan.Plan original = DecisionTreePlan.fromTiers(tiers);
		final DecisionTreePlan.Plan reread = DecisionTreePlan.fromNested(DecisionTreePlan.toNested(original)).plan;
		DecisionTreePlanTest.check("ids differ (T1/T2 vs N1/N2) yet the tree is structurally identical",
			DecisionTreePlan.sameStructure(original, reread));
	}

	// ---------------------------------------------------------------------------------------------------- validate

	private static void validate_noRoot()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("x", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: no root", !result.ok);
		DecisionTreePlanTest.check("reason names NO_ROOT", DecisionTreePlanTest.anyContains(result.reasons, "NO_ROOT"));
	}

	private static void validate_unknownBranchTarget()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("x", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = "N2"; // never declared
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: onFalse points nowhere", !result.ok);
		DecisionTreePlanTest.check("reason names UNKNOWN_BRANCH_TARGET", DecisionTreePlanTest.anyContains(result.reasons, "UNKNOWN_BRANCH_TARGET"));
	}

	private static void validate_cycleDetected()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("x", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = "N2";
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(new DecisionTreePlan.FieldRef("y", "ebx-addon-mame@exact"));
		n2.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n2.onFalse = "N1"; // loops back
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: cycle", !result.ok);
		DecisionTreePlanTest.check("reason names CYCLE_DETECTED", DecisionTreePlanTest.anyContains(result.reasons, "CYCLE_DETECTED"));
	}

	private static void validate_averageMissingMinimumScore()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AVERAGE);
		n1.fields.add(new DecisionTreePlan.FieldRef("x", "ebx-addon-mame@fuzzyFullText"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: AVERAGE with no minimumScore", !result.ok);
		DecisionTreePlanTest.check("reason names AVERAGE_MISSING_MINIMUM_SCORE", DecisionTreePlanTest.anyContains(result.reasons, "AVERAGE_MISSING_MINIMUM_SCORE"));
	}

	private static void validate_atLeastNInvalidMinimumMatches()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AT_LEAST_N);
		n1.minimumMatches = Integer.valueOf(5); // more than the 2 fields below
		n1.fields.add(new DecisionTreePlan.FieldRef("a", "ebx-addon-mame@exact"));
		n1.fields.add(new DecisionTreePlan.FieldRef("b", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: minimumMatches exceeds field count", !result.ok);
		DecisionTreePlanTest.check("reason names AT_LEAST_N_INVALID_MINIMUM_MATCHES", DecisionTreePlanTest.anyContains(result.reasons, "AT_LEAST_N_INVALID_MINIMUM_MATCHES"));
	}

	private static void validate_duplicateNodeId()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("x", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = "N1"; // duplicate id target is itself, but the DUPLICATE check is about two nodes sharing an id
		final DecisionTreePlan.Node duplicate = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		duplicate.fields.add(new DecisionTreePlan.FieldRef("y", "ebx-addon-mame@exact"));
		duplicate.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		duplicate.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(duplicate);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: two nodes share an id", !result.ok);
		DecisionTreePlanTest.check("reason names DUPLICATE_NODE_ID", DecisionTreePlanTest.anyContains(result.reasons, "DUPLICATE_NODE_ID"));
	}

	private static void validate_nodeIdReservedLeafName()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "MATCH";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("MATCH", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("x", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: a node id must not collide with a leaf token", !result.ok);
		DecisionTreePlanTest.check("reason names NODE_ID_RESERVED", DecisionTreePlanTest.anyContains(result.reasons, "NODE_ID_RESERVED"));
	}

	/**
	 * 2026-09-22, coordinator follow-up: a node named by more than one onTrue/onFalse anywhere in the plan is
	 * refused, naming every referencing branch - a node has exactly one parent, like the add-on's own nested
	 * shape (see DecisionTreePlan.validate's own Javadoc for the full story of why this rule exists).
	 */
	private static void validate_nodeShared()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		n1.onTrue = "N2";
		n1.onFalse = "N3";
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(new DecisionTreePlan.FieldRef("country", "ebx-addon-mame@exact"));
		n2.onTrue = "N3"; // shared: N3 is ALSO N1's onFalse target
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n3 = new DecisionTreePlan.Node("N3", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n3.fields.add(new DecisionTreePlan.FieldRef("legalForm", "ebx-addon-mame@exact"));
		n3.onTrue = DecisionTreePlan.LEAF_MATCH;
		n3.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		plan.nodes.add(n3);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: N3 has two parents", !result.ok);
		DecisionTreePlanTest.check("reason names NODE_SHARED with both referencing branches",
			DecisionTreePlanTest.anyContains(result.reasons, "NODE_SHARED") && DecisionTreePlanTest.anyContains(result.reasons, "N1.onFalse")
				&& DecisionTreePlanTest.anyContains(result.reasons, "N2.onTrue"));
	}

	/**
	 * PersVol5 (2026-09-26 live failure): {@code generate_matching_policy} answered {@code INVALID_PARAMS} ("The
	 * outputs from the Same Adresse.emailKey data comparison node cannot connect to the same type of relationship
	 * status") for a node whose onTrue and onFalse were BOTH {@code SUSPECTED} - the comparison decides nothing,
	 * whatever it finds. Refused here, at {@code DecisionTreePlan.validate} time, for every plan (not only a
	 * relation-field one) - the failure had nothing to do with the node's relation field itself.
	 */
	private static void validate_sameOutcomeBothBranches_sameLeaf_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: onTrue and onFalse both SUSPECTED decide nothing", !result.ok);
		DecisionTreePlanTest.check("reason names SAME_OUTCOME_BOTH_BRANCHES and the node: " + result.reasons,
			DecisionTreePlanTest.anyContains(result.reasons, "SAME_OUTCOME_BOTH_BRANCHES") && DecisionTreePlanTest.anyContains(result.reasons, "'N1'"));
	}

	/** The same rule applies when onTrue and onFalse both name the SAME NODE id, not only a shared leaf - either way, the comparison changes nothing about where the pair ends up. */
	private static void validate_sameOutcomeBothBranches_sameNodeId_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		n1.onTrue = "N2";
		n1.onFalse = "N2";
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(new DecisionTreePlan.FieldRef("country", "ebx-addon-mame@exact"));
		n2.onTrue = DecisionTreePlan.LEAF_MATCH;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: onTrue and onFalse both route to the same node id", !result.ok);
		DecisionTreePlanTest.check("reason names SAME_OUTCOME_BOTH_BRANCHES: " + result.reasons, DecisionTreePlanTest.anyContains(result.reasons, "SAME_OUTCOME_BOTH_BRANCHES"));
	}

	/** A node whose onTrue and onFalse genuinely differ is never refused by this rule - the ordinary case. */
	private static void validate_sameOutcomeBothBranches_differentLeaves_accepted() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("a node with genuinely different onTrue/onFalse validates clean: " + result.reasons, result.ok);
	}

	/**
	 * Lot R9 (2026-09-27, docs/MATCHING.md sect;40, live PersVol7 run 169): a node comparing a relation (child)
	 * field whose onTrue is SUSPECTED and onFalse is ALSO SUSPECTED decides nothing once translated - the LIVE
	 * failure's own shape (an ALL_FIELDS node on an Adresse field, onTrue "MATCH (or SUSPECTED)", onFalse
	 * SUSPECTED). Refused with RELATION_NODE_OUTCOMES_MUST_DIFFER, distinct from SAME_OUTCOME_BOTH_BRANCHES since
	 * the two leaves here are not even the identical string in this variant.
	 */
	private static void validate_relationNodeOutcomesMustDiffer_bothSuspected_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact"));
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		n1.fields.add(relationField);
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: a relation node whose onTrue and onFalse are both SUSPECTED decides nothing", !result.ok);
		DecisionTreePlanTest.check("reason names RELATION_NODE_OUTCOMES_MUST_DIFFER: " + result.reasons,
			DecisionTreePlanTest.anyContains(result.reasons, "RELATION_NODE_OUTCOMES_MUST_DIFFER"));
	}

	/** Same rule, the OTHER ambiguous combination: onTrue MATCH, onFalse SUSPECTED - different leaves, but both collapse to SUSPECTED once auto-merge is off. */
	private static void validate_relationNodeOutcomesMustDiffer_matchAndSuspected_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		n1.fields.add(relationField);
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: onTrue MATCH / onFalse SUSPECTED on a relation node still collapses under auto-merge off", !result.ok);
		DecisionTreePlanTest.check("reason names RELATION_NODE_OUTCOMES_MUST_DIFFER: " + result.reasons,
			DecisionTreePlanTest.anyContains(result.reasons, "RELATION_NODE_OUTCOMES_MUST_DIFFER"));
	}

	/** onFalse NOT_MATCHED discriminates from onTrue MATCH/SUSPECTED - the rule is never tripped when the branches genuinely differ. */
	private static void validate_relationNodeOutcomesMustDiffer_onFalseNotMatched_accepted() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		n1.fields.add(relationField);
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("a relation node routing onFalse to NOT_MATCHED validates clean: " + result.reasons, result.ok);
	}

	/** onFalse naming ANOTHER node (not a leaf at all) also discriminates - a coarser child-evidence level or an independent identifier further down the tree. */
	private static void validate_relationNodeOutcomesMustDiffer_onFalseIsAnotherNode_accepted() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		n1.fields.add(relationField);
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = "N2";
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(new DecisionTreePlan.FieldRef("countryCode", "ebx-addon-mame@exact"));
		n2.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("a relation node routing onFalse to another node validates clean: " + result.reasons, result.ok);
	}

	/** The rule only fires for a node that actually carries a relation field - an ordinary node with the same onTrue/onFalse shape is untouched (already legal, e.g. a MATCH-terminal identity tier). */
	private static void validate_relationNodeOutcomesMustDiffer_nonRelationNode_notChecked() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("an ordinary (non-relation) node with onTrue MATCH/onFalse SUSPECTED is not checked by this rule: " + result.reasons, result.ok);
	}

	/** N1 (identity, fuzzy) onTrue -> N2 (relation, exact) onTrue MATCH/onFalse NOT_MATCHED, N1 onFalse NOT_MATCHED - the canonical "filter only" shape: N2 has no other incoming edge. */
	private static void reachableOnlyViaOnTrue_filterOnlyShape_true() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AVERAGE);
		n1.minimumScore = Integer.valueOf(90);
		n1.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@fuzzyFullText"));
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		n2.fields.add(relationField);
		n2.onTrue = DecisionTreePlan.LEAF_MATCH;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		DecisionTreePlanTest.check("N2 (the only relation node) is reached exclusively via N1's onTrue",
			MatchPlanLlmPass.reachableOnlyViaOnTrue(plan));
	}

	/** Same tree, but N2 (the relation node) is ALSO reachable via a third node's onFalse - no longer "only via onTrue". */
	private static void reachableOnlyViaOnTrue_relationNodeOnOnFalsePath_false() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AVERAGE);
		n1.minimumScore = Integer.valueOf(90);
		n1.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@fuzzyFullText"));
		n1.onTrue = "N2";
		n1.onFalse = "N3";
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		n2.fields.add(relationField);
		n2.onTrue = DecisionTreePlan.LEAF_MATCH;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n3 = new DecisionTreePlan.Node("N3", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef independentEvidenceField = new DecisionTreePlan.FieldRef("fullAddressKey", "ebx-addon-mame@exact");
		independentEvidenceField.via = "Adresse";
		n3.fields.add(independentEvidenceField);
		n3.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n3.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		plan.nodes.add(n3);
		DecisionTreePlanTest.check("N3 (also a relation node) is reached via N1's onFalse: no longer filter-only",
			!MatchPlanLlmPass.reachableOnlyViaOnTrue(plan));
	}

	/** A relation node AS THE ROOT has no incoming edge at all - never "gated" by an identity node, so not "only via onTrue" either. */
	private static void reachableOnlyViaOnTrue_relationNodeIsRoot_false() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		n1.fields.add(relationField);
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		DecisionTreePlanTest.check("a relation node at the root is not \"reached only via onTrue\"", !MatchPlanLlmPass.reachableOnlyViaOnTrue(plan));
	}

	/** No relation-evidence field anywhere in the plan - vacuously false (the caller must ask "does the entity have relation evidence" separately). */
	private static void reachableOnlyViaOnTrue_noRelationNodeAtAll_false() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		DecisionTreePlanTest.check("a plan with no relation node at all is not \"reached only via onTrue\"", !MatchPlanLlmPass.reachableOnlyViaOnTrue(plan));
	}

	/** The mockup's own six-node Company tree (`docs/mockups/review-match-plan-binary-tree.html`) - a genuine binary tree, not a cascade, must validate clean; corrected 2026-09-22 (coordinator follow-up: it is a STRICT tree, every node has exactly one parent - N3's false branch is its own NOT_MATCHED leaf, never N5, which only N4's true branch reaches). */
	private static void validate_validSixNodeMockupTree() throws Exception
	{
		final DecisionTreePlan.Plan plan = DecisionTreePlanTest.mockupSixNodeTree();
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("the mockup's own tree validates clean: " + result.reasons, result.ok);
	}

	/** fromTiers builds a strict chain by construction - every node's onFalse names only the next tier (or the final leaf), never shared. */
	private static void fromTiers_alwaysProducesASingleParentModel() throws Exception
	{
		final JsonNode tiers = DecisionTreePlanTest.MAPPER.readTree("[{\"outcome\":\"MATCH\",\"type\":\"EXACT\",\"fields\":[{\"field\":\"vatNo\","
			+ "\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]},{\"outcome\":\"SUSPECTED\",\"type\":\"AVERAGE\",\"minimumScore\":90,"
			+ "\"fields\":[{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"weight\":3}]}]");
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(DecisionTreePlan.fromTiers(tiers));
		DecisionTreePlanTest.check("a converted cascade never has a shared node: " + result.reasons, result.ok);
	}

	/** fromNested builds each node fresh, in pre-order, one per nested occurrence - a nested tree has no way to name a node twice in the first place, so the flat model it produces is single-parent by construction. */
	private static void fromNested_alwaysProducesASingleParentModel() throws Exception
	{
		final String json = "{\"name\":\"Same vatNo\",\"function\":\"All fields match\",\"fields\":[{\"field\":\"/vatNo\",\"algorithm\":\"ebx-addon-mame@exact\"}],"
			+ "\"trueNode\":{\"name\":\"MATCHED\",\"function\":null},"
			+ "\"falseNode\":{\"name\":\"Same country\",\"function\":\"All fields match\",\"fields\":[{\"field\":\"/country\",\"algorithm\":\"ebx-addon-mame@exact\"}],"
			+ "\"trueNode\":{\"name\":\"SUSPECTED\",\"function\":null},\"falseNode\":{\"name\":\"NOT_MATCHED\",\"function\":null}}}";
		final DecisionTreePlan.Plan plan = DecisionTreePlan.fromNested(DecisionTreePlanTest.MAPPER.readTree(json)).plan;
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("a tree read back from the add-on's own nested shape never has a shared node: " + result.reasons, result.ok);
	}

	// -------------------------------------------------------------------------------------------------- sameStructure

	private static void sameStructure_ignoresIdsAndOrder() throws Exception
	{
		final DecisionTreePlan.Plan a = new DecisionTreePlan.Plan();
		a.root = "N1";
		final DecisionTreePlan.Node aRoot = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		aRoot.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		aRoot.onTrue = DecisionTreePlan.LEAF_MATCH;
		aRoot.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		a.nodes.add(aRoot);

		// same tree, different id and (irrelevantly, since there's only one node) order
		final DecisionTreePlan.Plan b = new DecisionTreePlan.Plan();
		b.root = "Z9";
		final DecisionTreePlan.Node bRoot = new DecisionTreePlan.Node("Z9", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		bRoot.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		bRoot.onTrue = DecisionTreePlan.LEAF_MATCH;
		bRoot.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		b.nodes.add(bRoot);

		DecisionTreePlanTest.check("same structure despite different ids", DecisionTreePlan.sameStructure(a, b));
	}

	private static void sameStructure_falseWhenABranchOrThresholdChanges() throws Exception
	{
		final DecisionTreePlan.Plan a = DecisionTreePlanTest.mockupSixNodeTree();
		final DecisionTreePlan.Plan b = DecisionTreePlanTest.mockupSixNodeTree();
		// change N4's minimumScore (80 -> 81 in the fixture builder's own numbering)
		for (final DecisionTreePlan.Node node : b.nodes)
		{
			if ("N4".equals(node.id))
			{
				node.minimumScore = Integer.valueOf(81);
			}
		}
		DecisionTreePlanTest.check("a changed threshold is a different plan", !DecisionTreePlan.sameStructure(a, b));
	}

	// ------------------------------------------------------------------------------------------------------ readFrom

	private static void readFrom_prefersDecisionTreeOverTiers() throws Exception
	{
		final JsonNode requirements = DecisionTreePlanTest.MAPPER.readTree("{\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\",\"fields\":"
			+ "[{\"field\":\"OLD\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],\"decisionTree\":{\"root\":\"N1\",\"nodes\":"
			+ "[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"NEW\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}]}}");
		final DecisionTreePlan.Plan plan = DecisionTreePlan.readFrom(requirements);
		DecisionTreePlanTest.check("decisionTree wins when both are present", "NEW".equals(plan.nodes.get(0).fields.get(0).field));
	}

	/**
	 * Coordinator review of 913775c7 (2026-09-27): a plan STORED before the {@code MatchPlanLlmPass} fix (this
	 * codebase's own `docs/MATCHING.md` &sect;38.3) wrote the add-on's long wire-shape keys
	 * ({@code bothNullValueHandling}/{@code oneNullValueHandling}) directly into {@code requirements.decisionTree
	 * .nodes[].fields[]} instead of this class' own short round-trip keys ({@code bothNull}/{@code oneNull}).
	 * Re-proposing every such plan is not something a fix can force - {@link DecisionTreePlan#fieldFromJson} must
	 * keep reading a plan already sitting in a project's own data exactly as it was written. This pins the fallback:
	 * the short key wins when both are present (never ambiguous, since {@link DecisionTreePlan#fieldToJson} only
	 * ever writes the short one now), and a plan carrying ONLY the long key still reads its real value instead of
	 * silently parsing to {@code null} and falling through to {@link DecisionTreePlan#nestedField}'s own
	 * {@code NOT_MATCH} default forever.
	 */
	private static void fieldFromJson_longKeyFallback_forPlansStoredBeforeTheShortKeyFix() throws Exception
	{
		// only the long (pre-fix) key present - must still be read as DO_NOTHING, not silently dropped to null.
		final JsonNode oldStylePlan = DecisionTreePlanTest.MAPPER.readTree("{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\","
			+ "\"fields\":[{\"field\":\"companyId\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,"
			+ "\"bothNullValueHandling\":\"DO_NOTHING\",\"oneNullValueHandling\":\"DO_NOTHING\"}],\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}]}");
		final DecisionTreePlan.Plan plan = DecisionTreePlan.fromJson(oldStylePlan);
		final DecisionTreePlan.FieldRef field = plan.nodes.get(0).fields.get(0);
		DecisionTreePlanTest.check("long-key bothNullValueHandling still reaches FieldRef.bothNull", "DO_NOTHING".equals(field.bothNull));
		DecisionTreePlanTest.check("long-key oneNullValueHandling still reaches FieldRef.oneNull", "DO_NOTHING".equals(field.oneNull));

		// end to end: the SAME old-style plan, translated by toNested, must carry DO_NOTHING in the add-on payload -
		// not NOT_MATCH (nestedField's own default, which is exactly what this bug silently fell back to before).
		final com.fasterxml.jackson.databind.node.ObjectNode nested = DecisionTreePlan.toNested(plan);
		DecisionTreePlanTest.check("toNested's own bothNullValueHandling is DO_NOTHING, not the NOT_MATCH default",
			"DO_NOTHING".equals(nested.path("fields").get(0).path("bothNullValueHandling").asText()));
		DecisionTreePlanTest.check("toNested's own oneNullValueHandling is DO_NOTHING, not the NOT_MATCH default",
			"DO_NOTHING".equals(nested.path("fields").get(0).path("oneNullValueHandling").asText()));

		// the short key, when present, wins over a (hypothetical) stale long key on the same field object - never
		// ambiguous which one is authoritative once both could be present.
		final JsonNode bothKeysPlan = DecisionTreePlanTest.MAPPER.readTree("{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\","
			+ "\"fields\":[{\"field\":\"companyId\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,"
			+ "\"bothNull\":\"MATCH\",\"bothNullValueHandling\":\"DO_NOTHING\",\"oneNull\":\"MATCH\",\"oneNullValueHandling\":\"DO_NOTHING\"}],"
			+ "\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}]}");
		final DecisionTreePlan.FieldRef bothKeysField = DecisionTreePlan.fromJson(bothKeysPlan).nodes.get(0).fields.get(0);
		DecisionTreePlanTest.check("the short key wins over a stale long key", "MATCH".equals(bothKeysField.bothNull));
		DecisionTreePlanTest.check("the short key wins over a stale long key (oneNull too)", "MATCH".equals(bothKeysField.oneNull));

		// writing (fieldToJson, via toJson) is unaffected - still only ever the short key, going forward.
		final DecisionTreePlan.FieldRef freshField = new DecisionTreePlan.FieldRef("email", "ebx-addon-mame@fuzzyFullText");
		freshField.bothNull = "DO_NOTHING";
		freshField.oneNull = "DO_NOTHING";
		final DecisionTreePlan.Node freshNode = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		freshNode.fields.add(freshField);
		freshNode.onTrue = DecisionTreePlan.LEAF_MATCH;
		freshNode.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Plan freshPlan = new DecisionTreePlan.Plan();
		freshPlan.root = "N1";
		freshPlan.nodes.add(freshNode);
		final JsonNode writtenField = DecisionTreePlan.toJson(freshPlan).path("nodes").get(0).path("fields").get(0);
		DecisionTreePlanTest.check("fieldToJson still writes only the short key", writtenField.hasNonNull("bothNull") && writtenField.hasNonNull("oneNull"));
		DecisionTreePlanTest.check("fieldToJson never writes the long key", writtenField.path("bothNullValueHandling").isMissingNode()
			&& writtenField.path("oneNullValueHandling").isMissingNode());
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	/**
	 * `docs/mockups/review-match-plan-binary-tree.html`'s own six-node Company example, built directly (not
	 * parsed from the mockup's own illustrative JSON, which is deliberately abridged with "..." - this fixture
	 * is a complete, valid tree of the same shape). <b>Corrected 2026-09-22 (coordinator follow-up)</b>: an
	 * earlier revision of this fixture wrongly had N3's false branch reach N5 (misreading the mockup's SVG) -
	 * the mockup is in fact a STRICT tree, every node with exactly one parent: N1 (ALL_FIELDS vatNo) -&gt; true
	 * N2, false N4; N2 (ALL_FIELDS country) -&gt; true MATCH, false N3; N3 (ALL_FIELDS name) -&gt; true
	 * SUSPECTED, false NOT_MATCHED (its own leaf, never N5 - only N4's true branch reaches N5); N4 (AVERAGE
	 * name/country/cityKey, minimumScore 80) -&gt; true N5, false NOT_MATCHED; N5 (ALL_FIELDS legalForm) -&gt;
	 * true SUSPECTED, false N6; N6 (AT_LEAST_N 2 of vatNo/cityKey/legalForm) -&gt; true SUSPECTED, false
	 * NOT_MATCHED.
	 */
	private static DecisionTreePlan.Plan mockupSixNodeTree()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";

		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		n1.onTrue = "N2";
		n1.onFalse = "N4";

		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(new DecisionTreePlan.FieldRef("country", "ebx-addon-mame@exact"));
		n2.onTrue = DecisionTreePlan.LEAF_MATCH;
		n2.onFalse = "N3";

		final DecisionTreePlan.Node n3 = new DecisionTreePlan.Node("N3", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef n3Field = new DecisionTreePlan.FieldRef("name", "ebx-addon-mame@fuzzyFullText");
		n3Field.minimumScore = Integer.valueOf(85);
		n3.fields.add(n3Field);
		n3.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n3.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;

		final DecisionTreePlan.Node n4 = new DecisionTreePlan.Node("N4", DecisionTreePlan.FUNCTION_AVERAGE);
		n4.minimumScore = Integer.valueOf(80);
		final DecisionTreePlan.FieldRef name = new DecisionTreePlan.FieldRef("name", "ebx-addon-mame@fuzzyFullText");
		name.weight = Integer.valueOf(10);
		final DecisionTreePlan.FieldRef country = new DecisionTreePlan.FieldRef("country", "ebx-addon-mame@exact");
		country.weight = Integer.valueOf(5);
		final DecisionTreePlan.FieldRef cityKey = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		cityKey.weight = Integer.valueOf(5);
		n4.fields.add(name);
		n4.fields.add(country);
		n4.fields.add(cityKey);
		n4.onTrue = "N5";
		n4.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;

		final DecisionTreePlan.Node n5 = new DecisionTreePlan.Node("N5", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n5.fields.add(new DecisionTreePlan.FieldRef("legalForm", "ebx-addon-mame@exact"));
		n5.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n5.onFalse = "N6";

		final DecisionTreePlan.Node n6 = new DecisionTreePlan.Node("N6", DecisionTreePlan.FUNCTION_AT_LEAST_N);
		n6.minimumMatches = Integer.valueOf(2);
		n6.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		n6.fields.add(new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact"));
		n6.fields.add(new DecisionTreePlan.FieldRef("legalForm", "ebx-addon-mame@exact"));
		n6.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n6.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;

		plan.nodes.add(n1);
		plan.nodes.add(n2);
		plan.nodes.add(n3);
		plan.nodes.add(n4);
		plan.nodes.add(n5);
		plan.nodes.add(n6);
		return plan;
	}

	private static boolean anyContains(final List<String> pReasons, final String pNeedle)
	{
		for (final String reason : pReasons)
		{
			if (reason.contains(pNeedle))
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------------------- freezeAndDisambiguateLabels (R8)

	/** Two AVERAGE nodes comparing the exact same fields (only their {@code minimumScore} differs, which {@link DecisionTreePlan#autoLabel} never reads) - the live PersVol7 shape, PipelineStep 587. */
	private static DecisionTreePlan.Node averageNode(final String pId, final int pMinimumScore, final String pOnTrue, final String pOnFalse, final String... pFields)
	{
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node(pId, DecisionTreePlan.FUNCTION_AVERAGE);
		node.minimumScore = Integer.valueOf(pMinimumScore);
		for (final String field : pFields)
		{
			node.fields.add(new DecisionTreePlan.FieldRef(field, "ebx-addon-mame@exact"));
		}
		node.onTrue = pOnTrue;
		node.onFalse = pOnFalse;
		return node;
	}

	private static void freezeAndDisambiguateLabels_collidingAutoLabels_secondGetsIdSuffix()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = DecisionTreePlanTest.averageNode("N1", 90, DecisionTreePlan.LEAF_MATCH, "N2", "firstName", "lastName");
		final DecisionTreePlan.Node n2 = DecisionTreePlanTest.averageNode("N2", 70, DecisionTreePlan.LEAF_SUSPECTED, DecisionTreePlan.LEAF_NOT_MATCHED, "firstName", "lastName");
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		DecisionTreePlan.freezeAndDisambiguateLabels(plan);
		DecisionTreePlanTest.check("first occurrence keeps the plain auto-label", "Similar firstName and lastName".equals(n1.label));
		DecisionTreePlanTest.check("second occurrence gets its own id appended: " + n2.label, "Similar firstName and lastName (N2)".equals(n2.label));
		DecisionTreePlanTest.check("the two final labels are distinct", !n1.label.equals(n2.label));
	}

	private static void freezeAndDisambiguateLabels_collidingExplicitLabels_secondGetsIdSuffix()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = DecisionTreePlanTest.averageNode("N1", 90, DecisionTreePlan.LEAF_MATCH, "N2", "email");
		final DecisionTreePlan.Node n2 = DecisionTreePlanTest.averageNode("N2", 70, DecisionTreePlan.LEAF_SUSPECTED, DecisionTreePlan.LEAF_NOT_MATCHED, "phone");
		n1.label = "Strong identity match";
		n2.label = "Strong identity match";
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		DecisionTreePlan.freezeAndDisambiguateLabels(plan);
		DecisionTreePlanTest.check("an explicit LLM-given label that collides is disambiguated too: " + n1.label + " / " + n2.label,
			"Strong identity match".equals(n1.label) && "Strong identity match (N2)".equals(n2.label));
	}

	private static void freezeAndDisambiguateLabels_noCollision_labelsUnchanged()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = DecisionTreePlanTest.averageNode("N1", 90, DecisionTreePlan.LEAF_MATCH, "N2", "firstName", "lastName");
		final DecisionTreePlan.Node n2 = DecisionTreePlanTest.averageNode("N2", 70, DecisionTreePlan.LEAF_SUSPECTED, DecisionTreePlan.LEAF_NOT_MATCHED, "city");
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		DecisionTreePlan.freezeAndDisambiguateLabels(plan);
		DecisionTreePlanTest.check("no collision: first label is the plain auto-label", "Similar firstName and lastName".equals(n1.label));
		DecisionTreePlanTest.check("no collision: second label is the plain auto-label, no suffix", "Similar city".equals(n2.label));
	}

	private static void freezeAndDisambiguateLabels_threeWayCollision_eachSuffixDistinct()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = DecisionTreePlanTest.averageNode("N1", 95, DecisionTreePlan.LEAF_MATCH, "N2", "firstName", "lastName");
		final DecisionTreePlan.Node n2 = DecisionTreePlanTest.averageNode("N2", 85, DecisionTreePlan.LEAF_MATCH, "N3", "firstName", "lastName");
		final DecisionTreePlan.Node n3 = DecisionTreePlanTest.averageNode("N3", 70, DecisionTreePlan.LEAF_SUSPECTED, DecisionTreePlan.LEAF_NOT_MATCHED, "firstName", "lastName");
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		plan.nodes.add(n3);
		DecisionTreePlan.freezeAndDisambiguateLabels(plan);
		DecisionTreePlanTest.check("first keeps the plain label", "Similar firstName and lastName".equals(n1.label));
		DecisionTreePlanTest.check("second gets its own id", "Similar firstName and lastName (N2)".equals(n2.label));
		DecisionTreePlanTest.check("third gets its own id, distinct from the second's", "Similar firstName and lastName (N3)".equals(n3.label));
	}

	/** {@link DecisionTreePlan#freezeAndDisambiguateLabels}'s own Javadoc: a second call on already-disambiguated labels must not append a further suffix. */
	private static void freezeAndDisambiguateLabels_idempotent_secondCallDoesNotResuffix()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = DecisionTreePlanTest.averageNode("N1", 90, DecisionTreePlan.LEAF_MATCH, "N2", "firstName", "lastName");
		final DecisionTreePlan.Node n2 = DecisionTreePlanTest.averageNode("N2", 70, DecisionTreePlan.LEAF_SUSPECTED, DecisionTreePlan.LEAF_NOT_MATCHED, "firstName", "lastName");
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		DecisionTreePlan.freezeAndDisambiguateLabels(plan);
		final String firstPassSecondLabel = n2.label;
		DecisionTreePlan.freezeAndDisambiguateLabels(plan);
		DecisionTreePlanTest.check("a second call leaves an already-disambiguated label untouched: " + n2.label, firstPassSecondLabel.equals(n2.label));
		DecisionTreePlanTest.check("no double suffix was appended", !n2.label.contains("(N2) ("));
	}

	// ---------------------------------------------------------- IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH (R13)

	/** AT_LEAST_N always counts, whatever its field count - the live PersVol7 shape (2 of 3 fields, one equal, one merely null, counted as agreeing). */
	private static void validate_identityCountNode_atLeastN_oneNullDoNothing_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AT_LEAST_N);
		n1.minimumMatches = Integer.valueOf(2);
		final DecisionTreePlan.FieldRef firstNameKey = new DecisionTreePlan.FieldRef("firstNameKey", "ebx-addon-mame@exact");
		firstNameKey.oneNull = "NOT_MATCH";
		final DecisionTreePlan.FieldRef emailKey = new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact");
		emailKey.oneNull = "DO_NOTHING";
		final DecisionTreePlan.FieldRef birthDate = new DecisionTreePlan.FieldRef("birthDate", "ebx-addon-mame@exact");
		birthDate.oneNull = "NOT_MATCH";
		n1.fields.add(firstNameKey);
		n1.fields.add(emailKey);
		n1.fields.add(birthDate);
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: a counting field's own oneNull is DO_NOTHING", !result.ok);
		DecisionTreePlanTest.check("reason names IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH: " + result.reasons,
			DecisionTreePlanTest.anyContains(result.reasons, "IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH"));
	}

	/** The same node, every field's oneNull correctly NOT_MATCH - validates clean. */
	private static void validate_identityCountNode_atLeastN_oneNullNotMatch_accepted() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AT_LEAST_N);
		n1.minimumMatches = Integer.valueOf(2);
		final DecisionTreePlan.FieldRef firstNameKey = new DecisionTreePlan.FieldRef("firstNameKey", "ebx-addon-mame@exact");
		firstNameKey.oneNull = "NOT_MATCH";
		final DecisionTreePlan.FieldRef emailKey = new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact");
		emailKey.oneNull = "NOT_MATCH";
		final DecisionTreePlan.FieldRef birthDate = new DecisionTreePlan.FieldRef("birthDate", "ebx-addon-mame@exact");
		birthDate.oneNull = "NOT_MATCH";
		n1.fields.add(firstNameKey);
		n1.fields.add(emailKey);
		n1.fields.add(birthDate);
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("every field's oneNull is NOT_MATCH: validates clean: " + result.reasons, result.ok);
	}

	/** A multi-field ALL_FIELDS node used the same identity-counting way (the COMBINED IDENTITY paragraph's own shape) is covered too, not only AT_LEAST_N. */
	private static void validate_identityCountNode_multiFieldAllFields_oneNullDoNothing_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef nameKey = new DecisionTreePlan.FieldRef("nameKey", "ebx-addon-mame@exact");
		nameKey.oneNull = "NOT_MATCH";
		final DecisionTreePlan.FieldRef registrationNumber = new DecisionTreePlan.FieldRef("registrationNumber", "ebx-addon-mame@exact");
		registrationNumber.oneNull = "DO_NOTHING";
		n1.fields.add(nameKey);
		n1.fields.add(registrationNumber);
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = DecisionTreePlan.LEAF_SUSPECTED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: a multi-field ALL_FIELDS identity node with a DO_NOTHING field", !result.ok);
		DecisionTreePlanTest.check("reason names IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH: " + result.reasons,
			DecisionTreePlanTest.anyContains(result.reasons, "IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH"));
	}

	/**
	 * Lot R14 (2026-09-28, CLAUDE.md &sect;15 "single-field ALL_FIELDS null exemption too broad", live project
	 * ret): a single-field ALL_FIELDS node is exempt from the counting rule ONLY when its one field is explicitly
	 * marked {@link DecisionTreePlan.FieldRef#referenceRole} - a genuine parent-confirmation node. Before this lot
	 * EVERY single-field ALL_FIELDS node was exempt regardless, which is exactly the loophole
	 * {@link #validate_identityCountNode_singleFieldAllFields_ordinaryField_refused} below now closes.
	 */
	private static void validate_identityCountNode_singleFieldAllFields_referenceRole_accepted() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef companyRef = new DecisionTreePlan.FieldRef("companyRef", "ebx-addon-mame@exact");
		companyRef.oneNull = "DO_NOTHING";
		companyRef.bothNull = "DO_NOTHING";
		companyRef.referenceRole = true;
		n1.fields.add(companyRef);
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("a single-field ALL_FIELDS node marked referenceRole is exempt from the counting rule: " + result.reasons, result.ok);
	}

	/**
	 * The exact live bug (project ret, 2026-09-28): an ORDINARY single-field ALL_FIELDS node - no relation, no
	 * {@code referenceRole} marker - whose onTrue leads straight to SUSPECTED must NOT be exempt merely for having
	 * one field. A missing phone number must not itself let a weak name-only pair through.
	 */
	private static void validate_identityCountNode_singleFieldAllFields_ordinaryField_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef phone = new DecisionTreePlan.FieldRef("phoneE164", "ebx-addon-mame@exact");
		phone.oneNull = "DO_NOTHING";
		n1.fields.add(phone);
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: an ordinary single-field ALL_FIELDS node with a DO_NOTHING field", !result.ok);
		DecisionTreePlanTest.check("reason names IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH: " + result.reasons,
			DecisionTreePlanTest.anyContains(result.reasons, "IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH"));
	}

	/** The same ordinary single-field node with the field's oneNull correctly NOT_MATCH validates clean. */
	private static void validate_identityCountNode_singleFieldAllFields_ordinaryField_notMatch_accepted() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef phone = new DecisionTreePlan.FieldRef("phoneE164", "ebx-addon-mame@exact");
		phone.oneNull = "NOT_MATCH";
		n1.fields.add(phone);
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("oneNull NOT_MATCH validates clean: " + result.reasons, result.ok);
	}

	/** A single-field node whose sole field is a relation ("via") field is exempt the same way a multi-field one already is. */
	private static void validate_identityCountNode_singleFieldAllFields_relationField_accepted() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		relationField.oneNull = "DO_NOTHING";
		n1.fields.add(relationField);
		n1.onTrue = DecisionTreePlan.LEAF_MATCH;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("a single-field relation node is exempt from the counting rule: " + result.reasons, result.ok);
	}

	/** A node marked contradiction:true is never checked by IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH, even when malformed enough to also trip CONTRADICTION_NODE_WRONG_SHAPE. */
	private static void validate_identityCountNode_contradictionMarked_neverChecked()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.contradiction = true;
		final DecisionTreePlan.FieldRef birthDate = new DecisionTreePlan.FieldRef("birthDate", "ebx-addon-mame@exact");
		birthDate.oneNull = "DO_NOTHING";
		n1.fields.add(birthDate);
		// wrong shape on purpose (onTrue a leaf, not another node) - CONTRADICTION_NODE_WRONG_SHAPE fires, but
		// IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH must never fire on a node explicitly marked contradiction.
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH never fires on a contradiction-marked node: " + result.reasons,
			!DecisionTreePlanTest.anyContains(result.reasons, "IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH"));
	}

	/** A relation ("via") field keeps its own deliberate DO_NOTHING contract even inside an otherwise-qualifying counting node - exempt by construction, not by name. */
	private static void validate_identityCountNode_relationFieldExempt_accepted() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AT_LEAST_N);
		n1.minimumMatches = Integer.valueOf(1);
		final DecisionTreePlan.FieldRef lastName = new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact");
		lastName.oneNull = "NOT_MATCH";
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		relationField.oneNull = "DO_NOTHING";
		n1.fields.add(lastName);
		n1.fields.add(relationField);
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("a relation field's own DO_NOTHING is never flagged by this rule: " + result.reasons, result.ok);
	}

	/** The rule only fires when onTrue leads straight to a terminal outcome - a counting node whose onTrue continues to another node is not (yet) the final word, so it is not checked here. */
	private static void validate_identityCountNode_onTrueIsAnotherNode_notChecked() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AT_LEAST_N);
		n1.minimumMatches = Integer.valueOf(1);
		final DecisionTreePlan.FieldRef emailKey = new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact");
		emailKey.oneNull = "DO_NOTHING";
		n1.fields.add(emailKey);
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(new DecisionTreePlan.FieldRef("vatNo", "ebx-addon-mame@exact"));
		n2.onTrue = DecisionTreePlan.LEAF_MATCH;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("a counting node whose onTrue is another node is not (yet) checked: " + result.reasons, result.ok);
	}

	/** An OMITTED oneNull (never set) is safe: {@link DecisionTreePlan#nestedField}'s own default is already NOT_MATCH. */
	private static void validate_identityCountNode_omittedOneNull_defaultsSafely_accepted() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AT_LEAST_N);
		n1.minimumMatches = Integer.valueOf(1);
		n1.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact"));
		n1.fields.add(new DecisionTreePlan.FieldRef("phoneKey", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("an omitted oneNull defaults to the safe NOT_MATCH and is never flagged: " + result.reasons, result.ok);
	}

	// -------------------------------------------------------- CONTRADICTION_NODE_* (R13, gated on Node#contradiction)

	/** A node marked {@code contradiction:true}, correctly shaped, with an explicit NOT_MATCH null handling: refused, since a merely-missing value must never disqualify. */
	private static void validate_contradictionNode_effectiveNullHandlingNotDoNothing_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.contradiction = true;
		final DecisionTreePlan.FieldRef birthDate = new DecisionTreePlan.FieldRef("birthDate", "ebx-addon-mame@exact");
		birthDate.bothNull = "DO_NOTHING";
		birthDate.oneNull = "NOT_MATCH";
		n1.fields.add(birthDate);
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_AVERAGE);
		n2.minimumScore = Integer.valueOf(80);
		n2.fields.add(new DecisionTreePlan.FieldRef("nameKey", "ebx-addon-mame@fuzzy"));
		n2.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: a contradiction node's own oneNull is NOT_MATCH, not DO_NOTHING", !result.ok);
		DecisionTreePlanTest.check("reason names CONTRADICTION_NODE_NULL_HANDLING_MUST_BE_DO_NOTHING: " + result.reasons,
			DecisionTreePlanTest.anyContains(result.reasons, "CONTRADICTION_NODE_NULL_HANDLING_MUST_BE_DO_NOTHING"));
	}

	/** The same marked shape, both null handlings correctly DO_NOTHING - validates clean (a second, unrelated domain: a package-size contradiction guard). */
	private static void validate_contradictionNode_doNothing_accepted() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.contradiction = true;
		final DecisionTreePlan.FieldRef packageSize = new DecisionTreePlan.FieldRef("packageSize", "ebx-addon-mame@exact");
		packageSize.bothNull = "DO_NOTHING";
		packageSize.oneNull = "DO_NOTHING";
		n1.fields.add(packageSize);
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_AVERAGE);
		n2.minimumScore = Integer.valueOf(80);
		n2.fields.add(new DecisionTreePlan.FieldRef("descriptionKey", "ebx-addon-mame@fuzzy"));
		n2.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("both null handlings DO_NOTHING: validates clean: " + result.reasons, result.ok);
	}

	/** Omitting bothNull/oneNull is NOT safe on a marked contradiction node (unlike the counting rule above): {@link DecisionTreePlan#nestedField}'s own default is NOT_MATCH, the opposite of what this node needs. */
	private static void validate_contradictionNode_omittedNullHandling_defaultsToNotMatch_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.contradiction = true;
		n1.fields.add(new DecisionTreePlan.FieldRef("registrationNumber", "ebx-addon-mame@exact"));
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_AVERAGE);
		n2.minimumScore = Integer.valueOf(80);
		n2.fields.add(new DecisionTreePlan.FieldRef("nameKey", "ebx-addon-mame@fuzzy"));
		n2.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: an omitted null handling defaults to NOT_MATCH on a marked contradiction node", !result.ok);
		DecisionTreePlanTest.check("reason names CONTRADICTION_NODE_NULL_HANDLING_MUST_BE_DO_NOTHING: " + result.reasons,
			DecisionTreePlanTest.anyContains(result.reasons, "CONTRADICTION_NODE_NULL_HANDLING_MUST_BE_DO_NOTHING"));
	}

	/** A relation field inside a marked contradiction-shaped node keeps its own contract, exempt from this rule too. */
	private static void validate_contradictionNode_relationFieldExempt_accepted() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.contradiction = true;
		final DecisionTreePlan.FieldRef relationField = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		relationField.via = "Adresse";
		relationField.oneNull = "NOT_MATCH";
		relationField.bothNull = "NOT_MATCH";
		n1.fields.add(relationField);
		final DecisionTreePlan.FieldRef registrationNumber = new DecisionTreePlan.FieldRef("registrationNumber", "ebx-addon-mame@exact");
		registrationNumber.bothNull = "DO_NOTHING";
		registrationNumber.oneNull = "DO_NOTHING";
		n1.fields.add(registrationNumber);
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_AVERAGE);
		n2.minimumScore = Integer.valueOf(80);
		n2.fields.add(new DecisionTreePlan.FieldRef("nameKey", "ebx-addon-mame@fuzzy"));
		n2.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("a relation field's own null handling is never checked by this rule: " + result.reasons, result.ok);
	}

	/** A node marked {@code contradiction:true} but shaped wrong (onTrue is a leaf, not continuing to another node) is refused on its SHAPE, before null handling is even looked at. */
	private static void validate_contradictionNode_markedButWrongShape_refused()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.contradiction = true;
		n1.fields.add(new DecisionTreePlan.FieldRef("registrationNumber", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("invalid: marked contradiction:true but onTrue is a leaf, not continuing to another node", !result.ok);
		DecisionTreePlanTest.check("reason names CONTRADICTION_NODE_WRONG_SHAPE: " + result.reasons,
			DecisionTreePlanTest.anyContains(result.reasons, "CONTRADICTION_NODE_WRONG_SHAPE"));
	}

	/**
	 * The exact live false-positive this lot found and fixed: an ORDINARY "identity gate above a further check"
	 * cascade node (PersVol7's own real shape - an exact-algorithm field, onFalse NOT_MATCHED, onTrue continuing to
	 * another node) that is simply never marked {@code contradiction:true} must validate clean, whatever its null
	 * handling - the shape alone is never enough to trigger either CONTRADICTION_NODE_* rule.
	 */
	private static void validate_contradictionNode_unmarkedMatchingShape_neverChecked() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact"));
		n1.onTrue = "N2";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n2.fields.add(new DecisionTreePlan.FieldRef("companyId", "ebx-addon-mame@exact"));
		n2.onTrue = DecisionTreePlan.LEAF_MATCH;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("an unmarked node with the same shape is never checked by either CONTRADICTION_NODE_* rule: " + result.reasons, result.ok);
	}

	/** onTrue naming a LEAF (not continuing to another node) is not the contradiction-node shape at all - even when marked, refused on shape (covered above); here left UNMARKED, so it is not checked at all. */
	private static void validate_contradictionNode_onTrueIsLeaf_notChecked() throws Exception
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("registrationNumber", "ebx-addon-mame@exact"));
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		DecisionTreePlanTest.check("unmarked node, onTrue is a leaf: never checked: " + result.reasons, result.ok);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
