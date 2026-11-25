package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Pure fixtures (no EBX): the per-path half of the cross-system guard,
 * 2026-10-12, reproducing the case measured live on Vol100kN (four systems, {@code birthDate} never filled by ECOM).
 */
public final class CrossSystemPathRuleTest
{
	public static void main(final String[] pArgs)
	{
		CrossSystemPathRuleTest.measuredCase_otherPathsCover_accepted_withTheWarningText();
		CrossSystemPathRuleTest.singlePathOnTheEmptyField_refused();
		CrossSystemPathRuleTest.everyPathNeedsTheField_refused_naming_theUncoveredSystem();
		CrossSystemPathRuleTest.fieldOnlyOnAnOnFalseEdge_neverBlocks();
		CrossSystemPathRuleTest.averageNode_fieldWeightNeeded_blocks();
		CrossSystemPathRuleTest.averageNode_fieldWeightNotNeeded_accepted();
		CrossSystemPathRuleTest.averageNode_doNothingIsNotRequired();
		CrossSystemPathRuleTest.atLeastNNode_blocksOnlyWhenTooFewFieldsRemain();
		CrossSystemPathRuleTest.twoDeferredFieldsEmptyInTheSameSystem_cannotLeanOnEachOther();
		CrossSystemPathRuleTest.noPositivePath_refused();
		CrossSystemPathRuleTest.relationFieldAndOtherSystemsAreNotJudged();
		System.out.println("CrossSystemPathRuleTest OK");
	}

	// ------------------------------------------------------------------------------------------------- fixtures

	private static DecisionTreePlan.FieldRef field(final String pName, final String pOneNull, final Integer pWeight)
	{
		final DecisionTreePlan.FieldRef ref = new DecisionTreePlan.FieldRef(pName, "ebx-addon-mame@exact");
		ref.oneNull = pOneNull;
		ref.bothNull = pOneNull;
		ref.weight = pWeight;
		return ref;
	}

	private static DecisionTreePlan.Node node(final String pId, final String pFunction, final String pOnTrue, final String pOnFalse,
		final DecisionTreePlan.FieldRef... pFields)
	{
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node(pId, pFunction);
		node.onTrue = pOnTrue;
		node.onFalse = pOnFalse;
		node.fields.addAll(Arrays.asList(pFields));
		return node;
	}

	private static DecisionTreePlan.Plan plan(final String pRoot, final DecisionTreePlan.Node... pNodes)
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = pRoot;
		plan.nodes.addAll(Arrays.asList(pNodes));
		return plan;
	}

	private static Map<String, List<String>> empty(final String pField, final String... pSystems)
	{
		final Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
		out.put(pField, Arrays.asList(pSystems));
		return out;
	}

	/** The Vol100kN tree: e-mail exact -> MATCH, else nameMatchKey + birthDate exact -> SUSPECTED, else NOT_MATCHED. */
	private static DecisionTreePlan.Plan vol100kNTree()
	{
		return CrossSystemPathRuleTest.plan("N1",
			CrossSystemPathRuleTest.node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS, "MATCH", "N2", CrossSystemPathRuleTest.field("emailKey", "NOT_MATCH", null)),
			CrossSystemPathRuleTest.node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS, "SUSPECTED", "NOT_MATCHED", CrossSystemPathRuleTest.field("nameMatchKey", "NOT_MATCH", null),
				CrossSystemPathRuleTest.field("birthDate", "NOT_MATCH", null)));
	}

	// ----------------------------------------------------------------------------------------------------- tests

	/** The measured case: birthDate 0 % in ECOM, but the e-mail path does not need it - accepted, and the warning says which path is silent and which still covers ECOM. */
	private static void measuredCase_otherPathsCover_accepted_withTheWarningText()
	{
		final Map<String, CrossSystemPathRule.Decision> decisions = CrossSystemPathRule.decide(CrossSystemPathRuleTest.vol100kNTree(),
			CrossSystemPathRuleTest.empty("birthDate", "ECOM"));
		final CrossSystemPathRule.Decision decision = decisions.get("birthDate");
		CrossSystemPathRuleTest.check("accepted", !decision.refuse);
		CrossSystemPathRuleTest.check("exact wording",
			("field 'birthDate' is empty in system ECOM: the path(s) N1 > N2 (nameMatchKey + birthDate -> SUSPECTED) will never fire for pairs involving ECOM; "
				+ "the other path(s) N1 (emailKey -> MATCH) still cover them.").equals(decision.warning));
	}

	/** The old case: one path, on the empty field - nothing covers ECOM, refused. */
	private static void singlePathOnTheEmptyField_refused()
	{
		final DecisionTreePlan.Plan plan = CrossSystemPathRuleTest.plan("N1", CrossSystemPathRuleTest.node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS, "SUSPECTED",
			"NOT_MATCHED", CrossSystemPathRuleTest.field("nameMatchKey", "NOT_MATCH", null), CrossSystemPathRuleTest.field("birthDate", "NOT_MATCH", null)));
		final CrossSystemPathRule.Decision decision = CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM")).get("birthDate");
		CrossSystemPathRuleTest.check("refused", decision.refuse);
		CrossSystemPathRuleTest.check("names the uncovered system", decision.uncoveredSystems.equals(Collections.singletonList("ECOM")));
		CrossSystemPathRuleTest.check("lists the path", decision.pathsNote.contains("N1 (nameMatchKey + birthDate -> SUSPECTED)"));
	}

	/** Two paths, both through the empty field: refused; the system named is the one nobody covers. */
	private static void everyPathNeedsTheField_refused_naming_theUncoveredSystem()
	{
		final DecisionTreePlan.Plan plan = CrossSystemPathRuleTest.plan("N1",
			CrossSystemPathRuleTest.node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS, "MATCH", "N2", CrossSystemPathRuleTest.field("birthDate", "NOT_MATCH", null),
				CrossSystemPathRuleTest.field("emailKey", "NOT_MATCH", null)),
			CrossSystemPathRuleTest.node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS, "SUSPECTED", "NOT_MATCHED", CrossSystemPathRuleTest.field("birthDate", "NOT_MATCH", null),
				CrossSystemPathRuleTest.field("nameMatchKey", "NOT_MATCH", null)));
		final CrossSystemPathRule.Decision decision = CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM", "CRM")).get("birthDate");
		CrossSystemPathRuleTest.check("refused", decision.refuse);
		CrossSystemPathRuleTest.check("both systems uncovered", decision.uncoveredSystems.equals(Arrays.asList("ECOM", "CRM")));
	}

	/** A node left through onFalse is what a pair with a null does: it never blocks. */
	private static void fieldOnlyOnAnOnFalseEdge_neverBlocks()
	{
		// N1 compares birthDate; the pair FAILS it (null), then N2 (email) -> MATCH. The only positive path passes N2 only.
		final DecisionTreePlan.Plan plan = CrossSystemPathRuleTest.plan("N1",
			CrossSystemPathRuleTest.node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS, "NOT_MATCHED", "N2", CrossSystemPathRuleTest.field("birthDate", "NOT_MATCH", null)),
			CrossSystemPathRuleTest.node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS, "MATCH", "NOT_MATCHED", CrossSystemPathRuleTest.field("emailKey", "NOT_MATCH", null)));
		final CrossSystemPathRule.Decision decision = CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM")).get("birthDate");
		CrossSystemPathRuleTest.check("accepted", !decision.refuse);
		CrossSystemPathRuleTest.check("says no path needs it", decision.warning.contains("no path to MATCH or SUSPECTED needs it"));
	}

	/** AVERAGE, birthDate weight 3 of 4 with NOT_MATCH, minimum 90: the best reachable average is 25 - the weight is needed, refused. */
	private static void averageNode_fieldWeightNeeded_blocks()
	{
		final DecisionTreePlan.Plan plan = CrossSystemPathRuleTest.plan("N1", CrossSystemPathRuleTest.node("N1", DecisionTreePlan.FUNCTION_AVERAGE, "SUSPECTED", "NOT_MATCHED",
			CrossSystemPathRuleTest.field("birthDate", "NOT_MATCH", Integer.valueOf(3)), CrossSystemPathRuleTest.field("nameMatchKey", "NOT_MATCH", Integer.valueOf(1))));
		plan.nodes.get(0).minimumScore = Integer.valueOf(90);
		CrossSystemPathRuleTest.check("refused", CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM")).get("birthDate").refuse);
	}

	/** AVERAGE, birthDate weight 1 of 10 with NOT_MATCH, minimum 85: the best reachable average is 90 - not needed, accepted. */
	private static void averageNode_fieldWeightNotNeeded_accepted()
	{
		final DecisionTreePlan.Plan plan = CrossSystemPathRuleTest.plan("N1", CrossSystemPathRuleTest.node("N1", DecisionTreePlan.FUNCTION_AVERAGE, "SUSPECTED", "NOT_MATCHED",
			CrossSystemPathRuleTest.field("birthDate", "NOT_MATCH", Integer.valueOf(1)), CrossSystemPathRuleTest.field("nameMatchKey", "NOT_MATCH", Integer.valueOf(9))));
		plan.nodes.get(0).minimumScore = Integer.valueOf(85);
		final CrossSystemPathRule.Decision decision = CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM")).get("birthDate");
		CrossSystemPathRuleTest.check("accepted", !decision.refuse);
		CrossSystemPathRuleTest.check("the path is not blocked", decision.warning.contains("no path to MATCH or SUSPECTED needs it"));
		// same weights at minimum 95: 90 < 95, the weight is now needed
		plan.nodes.get(0).minimumScore = Integer.valueOf(95);
		CrossSystemPathRuleTest.check("needed at 95", CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM")).get("birthDate").refuse);
	}

	/** An AVERAGE node with the field at DO_NOTHING drops it from the average: not required, even at weight 3 of 4 and minimum 100. */
	private static void averageNode_doNothingIsNotRequired()
	{
		final DecisionTreePlan.Plan plan = CrossSystemPathRuleTest.plan("N1", CrossSystemPathRuleTest.node("N1", DecisionTreePlan.FUNCTION_AVERAGE, "SUSPECTED", "NOT_MATCHED",
			CrossSystemPathRuleTest.field("birthDate", "DO_NOTHING", Integer.valueOf(3)), CrossSystemPathRuleTest.field("nameMatchKey", "NOT_MATCH", Integer.valueOf(1))));
		plan.nodes.get(0).minimumScore = Integer.valueOf(100);
		CrossSystemPathRuleTest.check("accepted", !CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM")).get("birthDate").refuse);
	}

	private static void atLeastNNode_blocksOnlyWhenTooFewFieldsRemain()
	{
		final DecisionTreePlan.Node atLeast = CrossSystemPathRuleTest.node("N1", DecisionTreePlan.FUNCTION_AT_LEAST_N, "SUSPECTED", "NOT_MATCHED",
			CrossSystemPathRuleTest.field("birthDate", "NOT_MATCH", null), CrossSystemPathRuleTest.field("nameMatchKey", "NOT_MATCH", null),
			CrossSystemPathRuleTest.field("postalKey", "NOT_MATCH", null));
		atLeast.minimumMatches = Integer.valueOf(2);
		final DecisionTreePlan.Plan plan = CrossSystemPathRuleTest.plan("N1", atLeast);
		CrossSystemPathRuleTest.check("2 of 3 still reachable without birthDate: accepted",
			!CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM")).get("birthDate").refuse);
		atLeast.minimumMatches = Integer.valueOf(3);
		CrossSystemPathRuleTest.check("3 of 3 needs it: refused", CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM")).get("birthDate").refuse);
	}

	/** birthDate and phone both empty in ECOM; the two paths each need one of them: neither field can lean on the other path. */
	private static void twoDeferredFieldsEmptyInTheSameSystem_cannotLeanOnEachOther()
	{
		final DecisionTreePlan.Plan plan = CrossSystemPathRuleTest.plan("N1",
			CrossSystemPathRuleTest.node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS, "MATCH", "N2", CrossSystemPathRuleTest.field("phoneE164", "NOT_MATCH", null)),
			CrossSystemPathRuleTest.node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS, "SUSPECTED", "NOT_MATCHED", CrossSystemPathRuleTest.field("birthDate", "NOT_MATCH", null)));
		final Map<String, List<String>> deferred = new LinkedHashMap<String, List<String>>();
		deferred.put("phoneE164", Collections.singletonList("ECOM"));
		deferred.put("birthDate", Collections.singletonList("ECOM"));
		final Map<String, CrossSystemPathRule.Decision> decisions = CrossSystemPathRule.decide(plan, deferred);
		CrossSystemPathRuleTest.check("both refused", decisions.get("phoneE164").refuse && decisions.get("birthDate").refuse);
		// each alone: the other path covers ECOM
		CrossSystemPathRuleTest.check("birthDate alone accepted", !CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM")).get("birthDate").refuse);
	}

	private static void noPositivePath_refused()
	{
		final DecisionTreePlan.Plan plan = CrossSystemPathRuleTest.plan("N1", CrossSystemPathRuleTest.node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS, "NOT_MATCHED",
			"NOT_MATCHED", CrossSystemPathRuleTest.field("birthDate", "NOT_MATCH", null)));
		final CrossSystemPathRule.Decision decision = CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM")).get("birthDate");
		CrossSystemPathRuleTest.check("refused", decision.refuse);
		CrossSystemPathRuleTest.check("says so", decision.pathsNote.contains("no path to MATCH or SUSPECTED"));
	}

	/** A relation field of the same name is another thing (read through a child); a node without the empty field is never blocked. */
	private static void relationFieldAndOtherSystemsAreNotJudged()
	{
		final DecisionTreePlan.FieldRef viaField = CrossSystemPathRuleTest.field("birthDate", "NOT_MATCH", null);
		viaField.via = "Adresse";
		final DecisionTreePlan.Plan plan = CrossSystemPathRuleTest.plan("N1", CrossSystemPathRuleTest.node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS, "SUSPECTED",
			"NOT_MATCHED", CrossSystemPathRuleTest.field("nameMatchKey", "NOT_MATCH", null), viaField));
		CrossSystemPathRuleTest.check("a via field never blocks", !CrossSystemPathRule.decide(plan, CrossSystemPathRuleTest.empty("birthDate", "ECOM")).get("birthDate").refuse);
		final CrossSystemPathRule.Paths paths = CrossSystemPathRule.positivePaths(plan);
		CrossSystemPathRuleTest.check("one positive path, not truncated", paths.paths.size() == 1 && !paths.truncated);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
