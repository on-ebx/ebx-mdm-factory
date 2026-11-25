package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The {@code patch_matching_config} operations that turn a STORED matching policy into the DESIRED one in place - the rewrite route of a table that belongs to a
 * business object (lot W13b, 2026-10-12, `docs/MATCHING.md` &sect;33.13). Measured on Vol1mR the same night: on such a table {@code apply_matching_config} is
 * refused and {@code generate_matching_policy} does NOT replace a policy ("A matching policy with processCode 'X' already exists on this table ... Use
 * patch_matching_config to change it"), so a changed plan can only be written op by op.
 * <p>
 * What this class derives, and the exact op for each kind of difference (every {@code value} is a JSON STRING, like every other op the Factory sends;
 * {@code policyCode} = the policy's {@code processCode}):
 * <ul>
 * <li>a pre-clustering field ({@code fields[]}) only in the stored policy: {@code remove_matching_field} {@code target {policyCode, field}};</li>
 * <li>one only in the desired policy: {@code add_matching_field} {@code target {policyCode, field}} then, for what is not the add-on default (weight 1,
 * {@code @default}, {@code DO_NOTHING}), the three ops below;</li>
 * <li>a different weight: {@code set_field_weight} {@code target {policyCode, field}} value = the integer; a different search strategy:
 * {@code set_field_preclustering} value = the template key; a different null handling: {@code set_field_null_handling} {@code target {policyCode, field,
 * paramName: bothNullValueHandling|oneNullValueHandling}} value = {@code DO_NOTHING|NOT_MATCH|MATCH}. A native relation field is addressed with
 * {@code businessObjectField} instead of {@code field} (exactly one of the two);</li>
 * <li>a decision-tree node, located by its stored {@code name} ({@code target.decisionTreeNode}; names are unique in a tree): a different algorithm of one of its
 * fields: {@code set_algorithm} {@code target {policyCode, decisionTreeNode, field}} value = the algorithm code; a different or missing field parameter
 * (including {@code minimumScore}, {@code weight}, {@code jaroWinklerThreshold}): {@code set_field_param} {@code target {policyCode, decisionTreeNode, field,
 * paramName}} value = the parameter value, and a different tree-field null handling is the same op with {@code paramName bothNullValueHandling|oneNullValueHandling}
 * (value {@code MATCH|NOT_MATCH}); a node-level {@code minimumScore} alone: {@code set_threshold} {@code target {policyCode, decisionTreeNode}}; a different node
 * function, or any other node-level parameter ({@code minimumNumberOfMatches}): {@code set_decision_node_function} {@code target {policyCode,
 * decisionTreeNode}} value = the function, with {@code node: {params: [the desired node parameters]}}. Field operations of a node come BEFORE its function
 * operation (the add-on rejects a function swap whose required field parameters are absent), and {@code set_algorithm} comes before the parameters of the
 * new algorithm.</li>
 * </ul>
 * What it does NOT derive, and reports instead in {@link Result#structural}: a node on one side only, a leaf and a comparison node facing each other, a leaf
 * whose outcome differs (MATCHED/SUSPECTED/NOT_MATCHED), a node whose set of compared fields differs. Those need {@code add_decision_node}/{@code
 * remove_decision_node}, which rebuild the whole tree and cannot be combined with the in-place edits above in one call; the configurator refuses (coded
 * {@code conflict.policyRewriteNeedsManualChange}) rather than half-rewrite a tree.
 * <p>
 * Everything is compared with the SAME tolerance as {@link MatchingPolicyComparator} (numeric parameter values, a parameter only the add-on stored is its default,
 * {@code DO_NOTHING} on a tree field reads {@code NOT_MATCH}, leaf outcomes in token or display form): a policy the comparator calls the same yields no operation here.
 * The merge policy is {@link MergeConfigPatchPlanner}'s. Pure Jackson, no EBX class, no MCP call.
 */
public final class MatchingPolicyPatchPlanner
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MatchingPolicyPatchPlanner()
	{
	}

	/** The operations in the order they must be sent, and what the in-place route cannot express. */
	public static final class Result
	{
		public final ArrayNode operations;
		public final List<String> structural;

		Result(final ArrayNode pOperations, final List<String> pStructural)
		{
			this.operations = pOperations;
			this.structural = pStructural;
		}

		/** The tree differs in a way no in-place operation expresses: the policy has to be deleted and configured again. */
		public boolean needsManualChange()
		{
			return !this.structural.isEmpty();
		}

		/** Nothing to write and nothing to refuse. */
		public boolean isEmpty()
		{
			return this.operations.size() == 0 && this.structural.isEmpty();
		}

		/** The structural differences as one sentence fragment ("node X: ...; node Y: ..."). */
		public String describeStructural()
		{
			return String.join("; ", this.structural);
		}
	}

	/**
	 * @param pPolicyCode the policy's {@code processCode} (patch target {@code policyCode})
	 * @param pStored the stored {@code matchingPolicies[]} entry of a fresh {@code get_matching_config}; null or missing reads as an empty policy (everything
	 *            desired is then an addition, and the tree a structural difference)
	 * @param pDesired the translator's output for the accepted plan
	 */
	public static Result plan(final String pPolicyCode, final JsonNode pStored, final ObjectNode pDesired)
	{
		final JsonNode stored = pStored == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : pStored;
		final ArrayNode ops = MatchingPolicyPatchPlanner.MAPPER.createArrayNode();
		final List<String> structural = new ArrayList<String>();
		MatchingPolicyPatchPlanner.planPolicyFields(ops, pPolicyCode, stored.path("fields"), pDesired.path("fields"));
		MatchingPolicyPatchPlanner.planTree(ops, structural, pPolicyCode, "", stored.path("decisionTree"), pDesired.path("decisionTree"));
		return new Result(ops, structural);
	}

	// ----------------------------------------------------------------------------------------------- pre-clustering fields

	private static final class Candidate
	{
		final String locatorKey;
		final String path;
		final int weight;
		final String searchStrategy;
		final String both;
		final String one;

		Candidate(final JsonNode pField, final String pLocatorKey, final String pPath)
		{
			this.locatorKey = pLocatorKey;
			this.path = pPath;
			this.weight = pField.path("weight").asInt(1);
			this.searchStrategy = pField.path("searchStrategy").asText("@default");
			final String both = MatchingPolicyComparator.text(pField, "bothNullValueHandling");
			final String one = MatchingPolicyComparator.text(pField, "oneNullValueHandling");
			this.both = both.isEmpty() ? "DO_NOTHING" : both;
			this.one = one.isEmpty() ? "DO_NOTHING" : one;
		}
	}

	private static Map<String, Candidate> indexCandidates(final JsonNode pFields)
	{
		final Map<String, Candidate> out = new LinkedHashMap<String, Candidate>();
		for (final JsonNode field : pFields)
		{
			final String businessObjectField = MatchingPolicyComparator.text(field, "businessObjectField").trim();
			final String plain = MatchingPolicyComparator.text(field, "field").trim();
			if (!businessObjectField.isEmpty())
			{
				out.put(businessObjectField, new Candidate(field, "businessObjectField", businessObjectField));
			}
			else if (!plain.isEmpty())
			{
				out.put(plain, new Candidate(field, "field", plain));
			}
		}
		return out;
	}

	private static void planPolicyFields(final ArrayNode pOps, final String pPolicyCode, final JsonNode pStoredFields, final JsonNode pDesiredFields)
	{
		final Map<String, Candidate> stored = MatchingPolicyPatchPlanner.indexCandidates(pStoredFields);
		final Map<String, Candidate> desired = MatchingPolicyPatchPlanner.indexCandidates(pDesiredFields);
		for (final Candidate storedField : stored.values())
		{
			if (!desired.containsKey(storedField.path))
			{
				MatchingPolicyPatchPlanner.op(pOps, "remove_matching_field", MatchingPolicyPatchPlanner.fieldTarget(pPolicyCode, storedField), null);
			}
		}
		for (final Candidate wanted : desired.values())
		{
			Candidate current = stored.get(wanted.path);
			if (current == null)
			{
				MatchingPolicyPatchPlanner.op(pOps, "add_matching_field", MatchingPolicyPatchPlanner.fieldTarget(pPolicyCode, wanted), null);
				// a field just added carries the add-on defaults
				current = new Candidate(MAPPER.createObjectNode(), wanted.locatorKey, wanted.path);
			}
			if (wanted.weight != current.weight)
			{
				MatchingPolicyPatchPlanner.op(pOps, "set_field_weight", MatchingPolicyPatchPlanner.fieldTarget(pPolicyCode, wanted), String.valueOf(wanted.weight));
			}
			if (!wanted.searchStrategy.equals(current.searchStrategy))
			{
				MatchingPolicyPatchPlanner.op(pOps, "set_field_preclustering", MatchingPolicyPatchPlanner.fieldTarget(pPolicyCode, wanted), wanted.searchStrategy);
			}
			if (!wanted.both.equals(current.both))
			{
				final ObjectNode target = MatchingPolicyPatchPlanner.fieldTarget(pPolicyCode, wanted);
				target.put("paramName", "bothNullValueHandling");
				MatchingPolicyPatchPlanner.op(pOps, "set_field_null_handling", target, wanted.both);
			}
			if (!wanted.one.equals(current.one))
			{
				final ObjectNode target = MatchingPolicyPatchPlanner.fieldTarget(pPolicyCode, wanted);
				target.put("paramName", "oneNullValueHandling");
				MatchingPolicyPatchPlanner.op(pOps, "set_field_null_handling", target, wanted.one);
			}
		}
	}

	private static ObjectNode fieldTarget(final String pPolicyCode, final Candidate pField)
	{
		final ObjectNode target = MatchingPolicyPatchPlanner.MAPPER.createObjectNode();
		target.put("policyCode", pPolicyCode);
		target.put(pField.locatorKey, pField.path);
		return target;
	}

	// --------------------------------------------------------------------------------------------------------- decision tree

	private static void planTree(final ArrayNode pOps, final List<String> pStructural, final String pPolicyCode, final String pPosition, final JsonNode pStored,
		final JsonNode pDesired)
	{
		final boolean storedMissing = pStored == null || pStored.isMissingNode() || pStored.isNull();
		final boolean desiredMissing = pDesired == null || pDesired.isMissingNode() || pDesired.isNull();
		final String where = pPosition.isEmpty() ? "root" : pPosition;
		if (storedMissing && desiredMissing)
		{
			return;
		}
		if (storedMissing || desiredMissing)
		{
			pStructural.add(where + ": " + (storedMissing ? "node only in the plan" : "node only in the stored policy"));
			return;
		}
		final boolean storedLeaf = !pStored.hasNonNull("function");
		final boolean desiredLeaf = !pDesired.hasNonNull("function");
		if (storedLeaf != desiredLeaf)
		{
			pStructural.add(where + ": " + (storedLeaf ? "an outcome in the stored policy, a comparison in the plan" : "a comparison in the stored policy, an outcome in the plan"));
			return;
		}
		if (storedLeaf)
		{
			final String a = MatchingPolicyComparator.canonicalLeafOutcome(pStored.path("name").asText(null));
			final String b = MatchingPolicyComparator.canonicalLeafOutcome(pDesired.path("name").asText(null));
			if (!java.util.Objects.equals(a, b))
			{
				pStructural.add(where + ": outcome " + a + " in the stored policy, " + b + " in the plan");
			}
			return;
		}
		final String nodeName = MatchingPolicyComparator.text(pStored, "name");
		final Map<String, JsonNode> storedFields = MatchingPolicyPatchPlanner.indexTreeFields(pStored.path("fields"));
		final Map<String, JsonNode> desiredFields = MatchingPolicyPatchPlanner.indexTreeFields(pDesired.path("fields"));
		final Set<String> onlyStored = new LinkedHashSet<String>(storedFields.keySet());
		onlyStored.removeAll(desiredFields.keySet());
		final Set<String> onlyDesired = new LinkedHashSet<String>(desiredFields.keySet());
		onlyDesired.removeAll(storedFields.keySet());
		if (!onlyStored.isEmpty() || !onlyDesired.isEmpty())
		{
			pStructural.add("node " + nodeName + ": compared fields differ" + (onlyStored.isEmpty() ? "" : " (only stored: " + String.join(", ", onlyStored) + ")")
				+ (onlyDesired.isEmpty() ? "" : " (only in the plan: " + String.join(", ", onlyDesired) + ")"));
		}
		else
		{
			for (final Map.Entry<String, JsonNode> entry : storedFields.entrySet())
			{
				MatchingPolicyPatchPlanner.planTreeField(pOps, pPolicyCode, nodeName, entry.getKey(), entry.getValue(), desiredFields.get(entry.getKey()));
			}
			MatchingPolicyPatchPlanner.planNodeLevel(pOps, pPolicyCode, nodeName, pStored, pDesired);
		}
		MatchingPolicyPatchPlanner.planTree(pOps, pStructural, pPolicyCode, nodeName + " / true", pStored.path("trueNode"), pDesired.path("trueNode"));
		MatchingPolicyPatchPlanner.planTree(pOps, pStructural, pPolicyCode, nodeName + " / false", pStored.path("falseNode"), pDesired.path("falseNode"));
	}

	private static Map<String, JsonNode> indexTreeFields(final JsonNode pFields)
	{
		final Map<String, JsonNode> out = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode field : pFields)
		{
			final String path = MatchingPolicyComparator.text(field, "field").trim();
			if (!path.isEmpty())
			{
				out.put(path, field);
			}
		}
		return out;
	}

	private static ObjectNode nodeTarget(final String pPolicyCode, final String pNodeName)
	{
		final ObjectNode target = MatchingPolicyPatchPlanner.MAPPER.createObjectNode();
		target.put("policyCode", pPolicyCode);
		target.put("decisionTreeNode", pNodeName);
		return target;
	}

	private static void planTreeField(final ArrayNode pOps, final String pPolicyCode, final String pNodeName, final String pFieldPath, final JsonNode pStored,
		final JsonNode pDesired)
	{
		final String storedAlgorithm = MatchingPolicyComparator.text(pStored, "algorithm");
		final String desiredAlgorithm = MatchingPolicyComparator.text(pDesired, "algorithm");
		if (!desiredAlgorithm.isEmpty() && !desiredAlgorithm.equals(storedAlgorithm))
		{
			final ObjectNode target = MatchingPolicyPatchPlanner.nodeTarget(pPolicyCode, pNodeName);
			target.put("field", pFieldPath);
			MatchingPolicyPatchPlanner.op(pOps, "set_algorithm", target, desiredAlgorithm);
		}
		for (final String nullName : new String[] { "bothNullValueHandling", "oneNullValueHandling" })
		{
			final String wanted = MatchingPolicyComparator.treeNullHandling(pDesired, nullName);
			if (!wanted.equals(MatchingPolicyComparator.treeNullHandling(pStored, nullName)))
			{
				final ObjectNode target = MatchingPolicyPatchPlanner.nodeTarget(pPolicyCode, pNodeName);
				target.put("field", pFieldPath);
				target.put("paramName", nullName);
				MatchingPolicyPatchPlanner.op(pOps, "set_field_param", target, wanted);
			}
		}
		final Map<String, String> storedParams = MatchingPolicyComparator.indexParams(pStored.path("params"));
		for (final Map.Entry<String, String> wanted : MatchingPolicyComparator.indexParams(pDesired.path("params")).entrySet())
		{
			// a parameter only the stored side carries is an add-on default: left alone, like the comparator does
			if (!storedParams.containsKey(wanted.getKey()) || !MatchingPolicyComparator.sameParamValue(storedParams.get(wanted.getKey()), wanted.getValue()))
			{
				final ObjectNode target = MatchingPolicyPatchPlanner.nodeTarget(pPolicyCode, pNodeName);
				target.put("field", pFieldPath);
				target.put("paramName", wanted.getKey());
				MatchingPolicyPatchPlanner.op(pOps, "set_field_param", target, wanted.getValue());
			}
		}
	}

	private static void planNodeLevel(final ArrayNode pOps, final String pPolicyCode, final String pNodeName, final JsonNode pStored, final JsonNode pDesired)
	{
		final String storedFunction = MatchingPolicyComparator.text(pStored, "function");
		final String desiredFunction = MatchingPolicyComparator.text(pDesired, "function");
		final Map<String, String> storedParams = MatchingPolicyComparator.indexParams(pStored.path("params"));
		final Map<String, String> desiredParams = MatchingPolicyComparator.indexParams(pDesired.path("params"));
		final List<String> differingParams = new ArrayList<String>();
		for (final Map.Entry<String, String> wanted : desiredParams.entrySet())
		{
			if (!storedParams.containsKey(wanted.getKey()) || !MatchingPolicyComparator.sameParamValue(storedParams.get(wanted.getKey()), wanted.getValue()))
			{
				differingParams.add(wanted.getKey());
			}
		}
		final boolean sameFunction = storedFunction.equals(desiredFunction);
		if (sameFunction && differingParams.isEmpty())
		{
			return;
		}
		if (sameFunction && differingParams.size() == 1 && "minimumScore".equals(differingParams.get(0)))
		{
			MatchingPolicyPatchPlanner.op(pOps, "set_threshold", MatchingPolicyPatchPlanner.nodeTarget(pPolicyCode, pNodeName), desiredParams.get("minimumScore"));
			return;
		}
		final ObjectNode operation = MatchingPolicyPatchPlanner.op(pOps, "set_decision_node_function", MatchingPolicyPatchPlanner.nodeTarget(pPolicyCode, pNodeName),
			desiredFunction);
		final ArrayNode params = operation.putObject("node").putArray("params");
		for (final Map.Entry<String, String> wanted : desiredParams.entrySet())
		{
			final ObjectNode param = params.addObject();
			param.put("name", wanted.getKey());
			param.put("value", wanted.getValue());
		}
	}

	private static ObjectNode op(final ArrayNode pOps, final String pOp, final ObjectNode pTarget, final String pValue)
	{
		final ObjectNode operation = pOps.addObject();
		operation.put("op", pOp);
		operation.set("target", pTarget);
		if (pValue != null)
		{
			operation.put("value", pValue);
		}
		return operation;
	}
}
