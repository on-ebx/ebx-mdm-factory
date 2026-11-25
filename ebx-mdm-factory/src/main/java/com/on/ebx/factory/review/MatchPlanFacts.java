package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.matching.MatchResultsView;

/**
 * Step 6, the pair card (2026-10-18): what the stored plan ({@code MatchPlan.requirements}) says about the fields it compares and the nodes of its tree, as plain
 * values for {@code matching.PairRows} and {@code matching.MatchResultsView} - so the card compares what the PLAN compares and says which node produced a pair.
 * The tree is read by {@link ReviewDecisionTree}, the same reader as the plan card, so the two never disagree. Pure.
 */
public final class MatchPlanFacts
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MatchPlanFacts()
	{
	}

	private static ReviewDecisionTree.Plan plan(final String pRequirementsJson)
	{
		if (pRequirementsJson == null || pRequirementsJson.trim().isEmpty())
		{
			return new ReviewDecisionTree.Plan();
		}
		try
		{
			return ReviewDecisionTree.readFrom(MatchPlanFacts.MAPPER.readTree(pRequirementsJson));
		}
		catch (final Exception ex)
		{
			return new ReviewDecisionTree.Plan();
		}
	}

	/** The master fields the plan compares on the record itself (relation fields, read through a child entity, are not here), in the order of the tree. */
	public static List<String> planFields(final String pRequirementsJson)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final ReviewDecisionTree.Node node : MatchPlanFacts.plan(pRequirementsJson).nodes)
		{
			for (final ReviewDecisionTree.Field field : node.fields)
			{
				if (field.field != null && !field.field.isEmpty() && !field.isRelation())
				{
					out.add(field.field);
				}
			}
		}
		return new ArrayList<String>(out);
	}

	/** The plan's fields compared for resemblance (an average tier, or an algorithm other than "identical"): the ones that name a record (first and last name, company name). */
	public static List<String> similarityFields(final String pRequirementsJson)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final ReviewDecisionTree.Node node : MatchPlanFacts.plan(pRequirementsJson).nodes)
		{
			for (final ReviewDecisionTree.Field field : node.fields)
			{
				if (field.field == null || field.field.isEmpty() || field.isRelation())
				{
					continue;
				}
				final String algorithm = field.algorithm == null ? "" : field.algorithm.toLowerCase(Locale.ROOT);
				final boolean exact = algorithm.endsWith("@exact") || algorithm.equals("exact");
				if (ReviewDecisionTree.FUNCTION_AVERAGE.equals(node.function) || !exact && !algorithm.isEmpty())
				{
					out.add(field.field);
				}
			}
		}
		return new ArrayList<String>(out);
	}

	/** The nodes of the tree by the name the engine gave them ({@code TreeEvaluation#nodeNames}), reduced to what the card says about a rule. */
	public static Map<String, MatchResultsView.NodeInfo> nodes(final String pRequirementsJson)
	{
		final Map<String, MatchResultsView.NodeInfo> out = new LinkedHashMap<String, MatchResultsView.NodeInfo>();
		for (final ReviewDecisionTree.Node node : MatchPlanFacts.plan(pRequirementsJson).nodes)
		{
			final List<String> fields = new ArrayList<String>();
			for (final ReviewDecisionTree.Field field : node.fields)
			{
				if (field.field != null && !field.field.isEmpty())
				{
					fields.add(field.field);
				}
			}
			// two nodes comparing the same fields get the same engine name; the translator (master.DecisionTreePlan#freezeAndDisambiguateLabels) writes the later ones "<name> (<id>)"
			String engineName = ReviewDecisionTree.engineName(node);
			if (out.containsKey(engineName))
			{
				engineName = engineName + " (" + node.id + ")";
			}
			out.put(engineName, new MatchResultsView.NodeInfo(node.id, node.function, node.minimumScore, node.minimumMatches, fields,
				ReviewDecisionTree.LEAF_SUSPECTED.equals(node.onTrue), ReviewDecisionTree.LEAF_SUSPECTED.equals(node.onFalse)));
		}
		return out;
	}
}
