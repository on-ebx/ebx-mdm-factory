package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code review} package's own read-side mirror of {@code master.DecisionTreePlan} - the SAME flat
 * {@code {root, nodes[]}} JSON shape ({@code MatchPlan.requirements.decisionTree}), or the legacy
 * {@code requirements.tiers} cascade array, parsed INDEPENDENTLY here rather than importing {@code
 * master.DecisionTreePlan} - same "review never imports ebx/master, and vice versa is avoided too"
 * convention {@link MatchPlanFieldEvidence}'s own Javadoc already documents (see also the retired {@code
 * DecisionTreeCompat}, which this class replaces - binary-decision-tree-screen lot, 2026-09-22, lot 2 of
 * the decision-tree rework: `docs/mockups/review-match-plan-binary-tree.html`, `docs/MATCHING.md` &sect;16).
 * <p>
 * Unlike {@code DecisionTreeCompat} (which only ever produced a linear {@code tiers[]} VIEW, {@code null}
 * for any genuinely branching tree), this class reads the tree AS A TREE - every {@code onTrue}/
 * {@code onFalse} is kept exactly as the plan states it (another node id, or one of {@link #LEAF_MATCH}/
 * {@link #LEAF_SUSPECTED}/{@link #LEAF_NOT_MATCHED}), so {@link MatchPlanTreeLayout}/{@link
 * MatchPlanTreeDiagram}/{@link MatchSimulationBlock} can walk a real branch instead of only ever seeing a
 * cascade or an empty view.
 * <p>
 * Deliberately minimal: no validation (a malformed/cyclic plan is {@code master}'s problem, already
 * rejected before it is ever stored - see {@code master.DecisionTreePlan#validate}), no {@code toNested}/
 * {@code fromNested} (this class never talks to the add-on). {@link #engineName(Node)} is the ONE piece of
 * {@code master.DecisionTreePlan.autoLabel}/{@code toNested} this class deliberately DOES replicate,
 * because it must produce the EXACT same string the add-on itself stores as a {@code DecisionNode}'s own
 * name (English, unlocalized, by construction - see that method's own Javadoc) - {@link
 * MatchSimulationBlock} needs it to match a real {@code TreeEvaluation.nodeNames} entry back to a node of
 * this plan.
 */
final class ReviewDecisionTree
{
	static final String LEAF_MATCH = "MATCH";
	static final String LEAF_SUSPECTED = "SUSPECTED";
	static final String LEAF_NOT_MATCHED = "NOT_MATCHED";

	static final String FUNCTION_ALL_FIELDS = "ALL_FIELDS";
	static final String FUNCTION_AT_LEAST_N = "AT_LEAST_N";
	static final String FUNCTION_AVERAGE = "AVERAGE";
	static final String FUNCTION_PREDICATE = "PREDICATE";

	private ReviewDecisionTree()
	{
	}

	static boolean isLeaf(final String pTarget)
	{
		return ReviewDecisionTree.LEAF_MATCH.equals(pTarget) || ReviewDecisionTree.LEAF_SUSPECTED.equals(pTarget)
			|| ReviewDecisionTree.LEAF_NOT_MATCHED.equals(pTarget);
	}

	/** Mirrors {@code master.DecisionTreePlan.FieldRef} - only the properties this package's rendering ever reads. */
	static final class Field
	{
		final String field;
		final String algorithm;
		final Integer weight;
		final Integer minimumScore;
		final String predicate;
		final String bothNull;
		final String oneNull;
		/** Relation-fields lot (2026-09-26, `docs/MATCHING.md` dated entry) - mirrors {@code master.DecisionTreePlan.FieldRef#via}: non-null names the CHILD entity {@link #field} is actually read through (e.g. {@code "Adresse"}). Null on a plain (non-relation) field, including every pre-2026-09-26 stored plan. */
		final String via;

		Field(final String pField, final String pAlgorithm, final Integer pWeight, final Integer pMinimumScore, final String pPredicate, final String pBothNull,
			final String pOneNull, final String pVia)
		{
			this.field = pField;
			this.algorithm = pAlgorithm;
			this.weight = pWeight;
			this.minimumScore = pMinimumScore;
			this.predicate = pPredicate;
			this.bothNull = pBothNull;
			this.oneNull = pOneNull;
			this.via = pVia;
		}

		/** True when this field is read through a child entity ({@link #via} non-blank) - see {@code master.DecisionTreePlan.FieldRef#isRelation}. */
		boolean isRelation()
		{
			return this.via != null && !this.via.trim().isEmpty();
		}

		/** {@code "Adresse › cityKey"} for a relation field, plain {@link #field} otherwise - the ONE place this package renders a relation field's identity, so every screen (tree diagram, table, simulation) shows it the same way. */
		String displayName()
		{
			return this.isRelation() ? this.via + " › " + this.field : this.field;
		}
	}

	/** Mirrors {@code master.DecisionTreePlan.Node}. */
	static final class Node
	{
		final String id;
		final String function;
		final String label;
		final Integer minimumScore;
		final Integer minimumMatches;
		final List<Field> fields = new ArrayList<Field>();
		final String onTrue;
		final String onFalse;
		final String rationale;

		Node(final String pId, final String pFunction, final String pLabel, final Integer pMinimumScore, final Integer pMinimumMatches, final String pOnTrue,
			final String pOnFalse, final String pRationale)
		{
			this.id = pId;
			this.function = pFunction;
			this.label = pLabel;
			this.minimumScore = pMinimumScore;
			this.minimumMatches = pMinimumMatches;
			this.onTrue = pOnTrue;
			this.onFalse = pOnFalse;
			this.rationale = pRationale;
		}
	}

	/** Mirrors {@code master.DecisionTreePlan.Plan}. */
	static final class Plan
	{
		String root;
		final List<Node> nodes = new ArrayList<Node>();

		Node byId(final String pId)
		{
			if (pId == null)
			{
				return null;
			}
			for (final Node node : this.nodes)
			{
				if (pId.equals(node.id))
				{
					return node;
				}
			}
			return null;
		}
	}

	// -------------------------------------------------------------------------------------------------- read

	/** {@code requirements.decisionTree} when present, else {@link #fromTiers} on {@code requirements.tiers}, else an empty {@link Plan}. Never throws. */
	static Plan readFrom(final JsonNode pRequirements)
	{
		if (pRequirements == null || pRequirements.isMissingNode())
		{
			return new Plan();
		}
		if (pRequirements.has("decisionTree"))
		{
			return ReviewDecisionTree.fromJson(pRequirements.path("decisionTree"));
		}
		if (pRequirements.has("tiers"))
		{
			return ReviewDecisionTree.fromTiers(pRequirements.path("tiers"));
		}
		return new Plan();
	}

	static Plan fromJson(final JsonNode pJson)
	{
		final Plan plan = new Plan();
		if (pJson == null || pJson.isMissingNode())
		{
			return plan;
		}
		plan.root = pJson.hasNonNull("root") ? pJson.path("root").asText() : null;
		for (final JsonNode nodeJson : pJson.path("nodes"))
		{
			plan.nodes.add(ReviewDecisionTree.nodeFromJson(nodeJson));
		}
		return plan;
	}

	private static Node nodeFromJson(final JsonNode pJson)
	{
		final Node node = new Node(pJson.path("id").asText(null), pJson.path("function").asText(null), pJson.hasNonNull("label") ? pJson.path("label").asText() : null,
			pJson.hasNonNull("minimumScore") ? Integer.valueOf(pJson.path("minimumScore").asInt()) : null,
			pJson.hasNonNull("minimumMatches") ? Integer.valueOf(pJson.path("minimumMatches").asInt()) : null, pJson.path("onTrue").asText(null),
			pJson.path("onFalse").asText(null), pJson.hasNonNull("rationale") ? pJson.path("rationale").asText() : null);
		for (final JsonNode fieldJson : pJson.path("fields"))
		{
			node.fields.add(ReviewDecisionTree.fieldFromJson(fieldJson));
		}
		return node;
	}

	private static Field fieldFromJson(final JsonNode pJson)
	{
		return new Field(pJson.path("field").asText(null), pJson.path("algorithm").asText(null),
			pJson.hasNonNull("weight") ? Integer.valueOf(pJson.path("weight").asInt()) : null,
			pJson.hasNonNull("minimumScore") ? Integer.valueOf(pJson.path("minimumScore").asInt()) : null,
			pJson.hasNonNull("predicate") ? pJson.path("predicate").asText() : null, pJson.hasNonNull("bothNull") ? pJson.path("bothNull").asText() : null,
			pJson.hasNonNull("oneNull") ? pJson.path("oneNull").asText() : null, pJson.hasNonNull("via") ? pJson.path("via").asText() : null);
	}

	/** Mirrors {@code master.DecisionTreePlan#fromTiers} - a pre-lot cascade converted into the equivalent flat {@link Plan}, ids {@code T1..Tn}. */
	static Plan fromTiers(final JsonNode pTiers)
	{
		final Plan plan = new Plan();
		int index = 0;
		final List<JsonNode> tiers = new ArrayList<JsonNode>();
		for (final JsonNode tier : pTiers)
		{
			tiers.add(tier);
		}
		for (final JsonNode tier : tiers)
		{
			index++;
			final String id = "T" + index;
			final boolean average = "AVERAGE".equals(tier.path("type").asText());
			final List<Field> fields = new ArrayList<Field>();
			for (final JsonNode fieldJson : tier.path("fields"))
			{
				final String bothNull = fieldJson.hasNonNull("bothNullValueHandling") ? fieldJson.path("bothNullValueHandling").asText() : null;
				final String oneNull = fieldJson.hasNonNull("oneNullValueHandling") ? fieldJson.path("oneNullValueHandling").asText() : null;
				if (average)
				{
					fields.add(new Field(fieldJson.path("field").asText(null), fieldJson.path("algorithm").asText(null),
						Integer.valueOf(Math.max(1, fieldJson.path("weight").asInt(1))), null, null, bothNull, oneNull, null));
				}
				else
				{
					fields.add(new Field(fieldJson.path("field").asText(null), fieldJson.path("algorithm").asText(null), null,
						Integer.valueOf(fieldJson.path("minimumScore").asInt(100)), null, bothNull, oneNull, null));
				}
			}
			final String onFalse = index < tiers.size() ? "T" + (index + 1) : ReviewDecisionTree.LEAF_NOT_MATCHED;
			final Node node = new Node(id, average ? ReviewDecisionTree.FUNCTION_AVERAGE : ReviewDecisionTree.FUNCTION_ALL_FIELDS, null,
				average ? Integer.valueOf(tier.path("minimumScore").asInt(90)) : null, null, tier.path("outcome").asText(ReviewDecisionTree.LEAF_SUSPECTED), onFalse,
				null);
			node.fields.addAll(fields);
			plan.nodes.add(node);
		}
		plan.root = plan.nodes.isEmpty() ? null : "T1";
		return plan;
	}

	// ------------------------------------------------------------------------------------------------ engine name

	/**
	 * The EXACT string the add-on stores as this node's {@code DecisionNode.getName()}, replicated from
	 * {@code master.DecisionTreePlan#toNestedTarget}/{@code #autoLabel} - see class Javadoc. Always English,
	 * regardless of the reviewer's own {@link Locale}: this is what the add-on itself was told to call the
	 * node at translation time, never a UI label.
	 */
	static String engineName(final Node pNode)
	{
		if (pNode.label != null && !pNode.label.trim().isEmpty())
		{
			return pNode.label;
		}
		final StringBuilder label = new StringBuilder();
		if (ReviewDecisionTree.FUNCTION_AVERAGE.equals(pNode.function))
		{
			label.append("Similar ");
		}
		else if (ReviewDecisionTree.FUNCTION_AT_LEAST_N.equals(pNode.function))
		{
			label.append("At least ").append(pNode.minimumMatches == null ? 1 : pNode.minimumMatches.intValue()).append(" of ");
		}
		else if (ReviewDecisionTree.FUNCTION_PREDICATE.equals(pNode.function))
		{
			label.append("Predicate on ");
		}
		else
		{
			label.append("Same ");
		}
		boolean first = true;
		for (final Field field : pNode.fields)
		{
			if (!first)
			{
				label.append(" and ");
			}
			label.append(field.isRelation() ? field.via + "." + field.field : field.field);
			first = false;
		}
		return label.toString();
	}
}
