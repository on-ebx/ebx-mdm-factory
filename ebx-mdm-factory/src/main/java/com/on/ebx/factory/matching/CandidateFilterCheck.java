package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot M8a (2026-10-04, {@code docs/MATCHING.md} &sect;51): does the Match and Merge add-on get CANDIDATE FILTERS out of this plan's decision tree?
 * <p>
 * <b>The rule being reproduced</b> (read by {@code javap} on the add-on's {@code MandatoryFieldSetFinder}, measured live at 1 M rows on 2026-10-04 -
 * project Vol1m, {@code mdm-volume-test/vol1m/FINDINGS.md}, variants C/D/E/E3). With the JVM option {@code -Debx.addon.mame.matching.
 * mandatoryFieldSets.enabled=true} the add-on derives from the tree ONE FIELD SET PER PATH to {@code MATCHED}/{@code SUSPECTED}. A path's set holds the
 * fields of the "All fields match" ({@code ALL_FIELDS}) nodes the path PASSES (the node's {@code onTrue} edge is the one followed - a node the path leaves
 * through {@code onFalse} says nothing about the pair that took that path, so it adds nothing; this reading is the one the measurements fit: variant D
 * showed the filters {@code {phoneE164}} OR {@code {emailKey}}, not {@code {emailKey, phoneE164}}) that satisfy ALL of:
 * <ul>
 * <li>the field is a field of the table itself (a relation field, {@code via} set, never counts);</li>
 * <li>both {@code bothNull} and {@code oneNull} are {@code NOT_MATCH} (absent = {@code NOT_MATCH}, the value the translator writes for an absent one);</li>
 * <li>its {@code minimumScore} is above 0 (absent = 100, the add-on's own default);</li>
 * <li>its algorithm is {@code ebx-addon-mame@exact} or {@code ebx-addon-mame@exactFullText} (matched on the last {@code @} segment, case-insensitive).</li>
 * </ul>
 * The candidate search then keeps a pair only when it satisfies one of the sets. <b>If ONE path has an empty set, the add-on sets no filter at all</b>
 * and the candidate search falls back to a single weighted score over every field (measured: 9 times slower, ten points of recall lower, at 1 M rows).
 * <p>
 * This class reproduces that rule on the stored plan ({@code MatchPlan.requirements}), so the Factory can refuse, report and explain a plan before the add-on
 * silently ignores its keys. It reads the flat {@code {root, nodes[]}} shape (or the legacy {@code tiers} cascade) as JSON, independently of
 * {@code master.DecisionTreePlan} - the same convention as {@code review.ReviewDecisionTree}, so the language-model pass, the screens and the configurator all
 * share it. Pure: Jackson only, nothing from EBX.
 */
public final class CandidateFilterCheck
{
	/** Every path to a match has a non-empty field set: the add-on gets candidate filters (when its JVM option is on). */
	public static final String VERDICT_FILTERED = "FILTERED";
	/** At least one path to a match has no eligible key: the add-on sets NO filter. */
	public static final String VERDICT_NOT_FILTERED = "NOT_FILTERED";
	/** No tree, or no path leads to a match: nothing to say. */
	public static final String VERDICT_NOT_APPLICABLE = "NOT_APPLICABLE";

	/** The JVM system property that turns the add-on's derived candidate filters on - read at the add-on's start, so a restart is needed to change it. */
	public static final String JVM_OPTION = "ebx.addon.mame.matching.mandatoryFieldSets.enabled";

	/** The node is not an {@code ALL_FIELDS} node, so none of its fields is a candidate key. */
	public static final String REASON_NOT_ALL_FIELDS = "NOT_ALL_FIELDS";
	/** The field is read through a child entity ({@code via}). */
	public static final String REASON_RELATION_FIELD = "RELATION_FIELD";
	/** {@code bothNull} or {@code oneNull} is not {@code NOT_MATCH}. */
	public static final String REASON_NULL_HANDLING = "NULL_HANDLING";
	/** The algorithm is neither {@code exact} nor {@code exactFullText}. */
	public static final String REASON_ALGORITHM = "ALGORITHM";
	/** The field's {@code minimumScore} is not above 0. */
	public static final String REASON_MINIMUM_SCORE = "MINIMUM_SCORE";
	/** Every node of the path is left through its {@code onFalse} edge: no node of the path is passed, so no field can be a key. */
	public static final String REASON_NO_PASSED_NODE = "NO_PASSED_NODE";

	private static final String LEAF_MATCH = "MATCH";
	private static final String LEAF_SUSPECTED = "SUSPECTED";
	private static final String LEAF_NOT_MATCHED = "NOT_MATCHED";
	private static final String FUNCTION_ALL_FIELDS = "ALL_FIELDS";
	private static final String NOT_MATCH = "NOT_MATCH";
	/** Guards a malformed (cyclic) tree: no real plan comes near it ({@code DecisionTreePlan.MAX_DEPTH} is 32). */
	private static final int MAX_DEPTH = 64;

	private CandidateFilterCheck()
	{
	}

	/** Why one node or one field of a path did not give a key. */
	public static final class Rejection
	{
		public final String nodeId;
		/** The field concerned, or {@code null} when the reason concerns the node as a whole. */
		public final String field;
		/** One of the {@code REASON_*} codes. */
		public final String code;
		/** English, one sentence, no trailing period. */
		public final String text;

		Rejection(final String pNodeId, final String pField, final String pCode, final String pText)
		{
			this.nodeId = pNodeId;
			this.field = pField;
			this.code = pCode;
			this.text = pText;
		}
	}

	/** One path from the root to a {@code MATCH}/{@code SUSPECTED} leaf. */
	public static final class PathResult
	{
		/** The ids of the nodes crossed, root first. */
		public final List<String> nodeIds;
		/** {@code MATCH} or {@code SUSPECTED}. */
		public final String outcome;
		/** The fields of the field set of this path, in path order, without duplicates - empty when the path has no eligible key. */
		public final List<String> keyFields;
		/**
		 * Lot M16: for each of {@link #keyFields}, the share (0-100) of its pre-clustering weight the add-on counts towards the score of this field set - the comparator's
		 * {@code minimumScore} ({@code CnyMandatoryFieldSet.computeScore}: weight x minScore / 100): 100 for {@code exact}, the node's {@code minimumScore} (default 100) for {@code exactFullText}. When
		 * two nodes of the path name the same field the add-on keeps the exact comparator, else the higher minimumScore.
		 */
		public final Map<String, Integer> keyPercents;
		/** Why the nodes crossed through {@code onTrue} gave no key (empty when {@link #keyFields} is not). */
		public final List<Rejection> rejections;

		PathResult(final List<String> pNodeIds, final String pOutcome, final List<String> pKeyFields, final Map<String, Integer> pKeyPercents, final List<Rejection> pRejections)
		{
			this.nodeIds = Collections.unmodifiableList(pNodeIds);
			this.outcome = pOutcome;
			this.keyFields = Collections.unmodifiableList(pKeyFields);
			this.keyPercents = Collections.unmodifiableMap(pKeyPercents);
			this.rejections = Collections.unmodifiableList(pRejections);
		}

		/** {@code N2 > N2B > N3}: the ids of the nodes crossed - together with {@link #outcome} they identify the path in the tree. */
		public String label()
		{
			final StringBuilder out = new StringBuilder();
			for (final String id : this.nodeIds)
			{
				out.append(out.length() == 0 ? "" : " > ").append(id);
			}
			return out.toString();
		}

		public boolean hasKey()
		{
			return !this.keyFields.isEmpty();
		}
	}

	/** The verdict, with every path behind it. */
	public static final class Result
	{
		/** {@link #VERDICT_FILTERED}, {@link #VERDICT_NOT_FILTERED} or {@link #VERDICT_NOT_APPLICABLE}. */
		public final String verdict;
		/** Every path to a {@code MATCH}/{@code SUSPECTED} leaf, in tree order ({@code onTrue} before {@code onFalse}). */
		public final List<PathResult> paths;

		Result(final String pVerdict, final List<PathResult> pPaths)
		{
			this.verdict = pVerdict;
			this.paths = Collections.unmodifiableList(pPaths);
		}

		public boolean filtered()
		{
			return VERDICT_FILTERED.equals(this.verdict);
		}

		public boolean notFiltered()
		{
			return VERDICT_NOT_FILTERED.equals(this.verdict);
		}

		/** The paths with no eligible key, in tree order (empty when the verdict is not {@link #VERDICT_NOT_FILTERED}). */
		public List<PathResult> pathsWithoutKey()
		{
			final List<PathResult> out = new ArrayList<PathResult>();
			for (final PathResult path : this.paths)
			{
				if (!path.hasKey())
				{
					out.add(path);
				}
			}
			return out;
		}

		/** The distinct field sets, in path order - the candidate filters the add-on derives, any one of which lets a pair through (OR). Meaningful when {@link #filtered()}. */
		public List<List<String>> keySets()
		{
			final Set<List<String>> distinct = new LinkedHashSet<List<String>>();
			for (final PathResult path : this.paths)
			{
				if (path.hasKey())
				{
					distinct.add(path.keyFields);
				}
			}
			return new ArrayList<List<String>>(distinct);
		}

		/** Every field that appears in any set, distinct, in path order. */
		public List<String> keyFields()
		{
			final Set<String> distinct = new LinkedHashSet<String>();
			for (final List<String> set : this.keySets())
			{
				distinct.addAll(set);
			}
			return new ArrayList<String>(distinct);
		}

		/**
		 * The filters as one English phrase: {@code emailKey OR phoneE164 OR adresseKeys}, a set of several fields in parentheses ({@code (a AND b)}). Empty
		 * when no path has a key.
		 */
		public String filtersPhrase()
		{
			final StringBuilder out = new StringBuilder();
			for (final List<String> set : this.keySets())
			{
				out.append(out.length() == 0 ? "" : " OR ");
				out.append(set.size() == 1 ? set.get(0) : "(" + String.join(" AND ", set) + ")");
			}
			return out.toString();
		}

		/**
		 * One English paragraph a person or the model can act on: the verdict and, for every path with no key, why each node of it gave none. Never null; empty
		 * only for {@link #VERDICT_NOT_APPLICABLE}.
		 */
		public String explain()
		{
			if (VERDICT_NOT_APPLICABLE.equals(this.verdict))
			{
				return "";
			}
			if (this.filtered())
			{
				return "Candidate search is filtered on: " + this.filtersPhrase() + ".";
			}
			final StringBuilder out = new StringBuilder("Candidate search is NOT filtered: the add-on derives candidate filters only when EVERY path to SUSPECTED/MATCH "
				+ "passes an ALL_FIELDS node on an exact key of the record itself, and this plan has " + this.pathsWithoutKey().size() + " path(s) with none. ");
			for (final PathResult path : this.pathsWithoutKey())
			{
				out.append("Path ").append(path.label()).append(" (").append(path.outcome).append("): ");
				if (path.rejections.isEmpty())
				{
					out.append("no node on it gives a key");
				}
				for (int i = 0; i < path.rejections.size(); i++)
				{
					out.append(i == 0 ? "" : "; ").append(path.rejections.get(i).text);
				}
				out.append(". ");
			}
			return out.toString().trim();
		}
	}

	// -------------------------------------------------------------------------------------------------------- check

	/** {@link #checkTree} of {@code requirements.decisionTree}, or of the legacy {@code requirements.tiers} cascade; {@link #VERDICT_NOT_APPLICABLE} when neither is present. Never throws. */
	public static Result check(final JsonNode pRequirements)
	{
		if (pRequirements == null || pRequirements.isMissingNode() || pRequirements.isNull())
		{
			return new Result(VERDICT_NOT_APPLICABLE, new ArrayList<PathResult>());
		}
		if (pRequirements.has("decisionTree"))
		{
			return CandidateFilterCheck.checkTree(pRequirements.path("decisionTree"));
		}
		if (pRequirements.has("tiers"))
		{
			return CandidateFilterCheck.checkTree(CandidateFilterCheck.tiersToTree(pRequirements.path("tiers")));
		}
		return new Result(VERDICT_NOT_APPLICABLE, new ArrayList<PathResult>());
	}

	/** The check on a flat {@code {root, nodes[]}} tree - see the class Javadoc for the rule. Never throws: a malformed tree reads as {@link #VERDICT_NOT_APPLICABLE}. */
	public static Result checkTree(final JsonNode pTree)
	{
		final List<PathResult> paths = new ArrayList<PathResult>();
		if (pTree == null || !pTree.hasNonNull("root") || !pTree.path("nodes").isArray())
		{
			return new Result(VERDICT_NOT_APPLICABLE, paths);
		}
		final Map<String, JsonNode> byId = new LinkedHashMap<String, JsonNode>();
		for (final JsonNode node : pTree.path("nodes"))
		{
			if (node.hasNonNull("id"))
			{
				byId.put(node.path("id").asText(), node);
			}
		}
		CandidateFilterCheck.walk(byId, pTree.path("root").asText(), new ArrayList<String>(), new ArrayList<Boolean>(), paths);
		if (paths.isEmpty())
		{
			return new Result(VERDICT_NOT_APPLICABLE, paths);
		}
		for (final PathResult path : paths)
		{
			if (!path.hasKey())
			{
				return new Result(VERDICT_NOT_FILTERED, paths);
			}
		}
		return new Result(VERDICT_FILTERED, paths);
	}

	/** {@code requirements.unfilteredCandidateSearchReason} - why the model accepted a candidate search without filters - or an empty string. */
	public static String acceptedReason(final JsonNode pRequirements)
	{
		if (pRequirements == null)
		{
			return "";
		}
		final JsonNode reason = pRequirements.path("unfilteredCandidateSearchReason");
		return reason.isTextual() ? reason.asText().trim() : "";
	}

	/** Lot M5: true when the Factory itself kept an unfiltered tree ({@code requirements.unfilteredAcceptedBy = FACTORY}) after the proposals did not reach a filtered one - not the model's choice. */
	public static boolean acceptedByFactory(final JsonNode pRequirements)
	{
		return pRequirements != null && "FACTORY".equals(pRequirements.path("unfilteredAcceptedBy").asText(""));
	}

	/** True when the JVM runs with {@code -D}{@value #JVM_OPTION}{@code =true} - the add-on's candidate filters are on. */
	public static boolean jvmOptionEnabled()
	{
		return CandidateFilterCheck.jvmOptionEnabled(System.getProperty(CandidateFilterCheck.JVM_OPTION));
	}

	/** The pure half of {@link #jvmOptionEnabled()}: the property's value is {@code true} (case-insensitive, trimmed). */
	public static boolean jvmOptionEnabled(final String pValue)
	{
		return pValue != null && "true".equals(pValue.trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * The one-sentence warning for a step message/detail: the candidate filters of a {@link #VERDICT_FILTERED} plan are OFF on this server (JVM option missing), or
	 * the plan is {@link #VERDICT_NOT_FILTERED} (said instead, whatever the option). {@code null} when there is nothing to warn about.
	 */
	public static String warning(final Result pResult, final boolean pJvmOptionEnabled)
	{
		if (pResult.notFiltered())
		{
			return "The candidate search of this plan is NOT filtered (" + pResult.pathsWithoutKey().size() + " path(s) with no exact key): on a large table the run compares by "
				+ "weighted search (measured 9 times slower and 10 points of recall lower at 1 million rows). " + pResult.explain();
		}
		if (pResult.filtered() && !pJvmOptionEnabled)
		{
			return "The add-on's candidate filters are OFF on this server: start the JVM with -D" + CandidateFilterCheck.JVM_OPTION + "=true (the plan is filtered on "
				+ pResult.filtersPhrase() + ", but without the option the run compares by weighted search - 9 times slower and 10 points of recall lower at 1 million rows).";
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------------------- walk

	private static void walk(final Map<String, JsonNode> pById, final String pTarget, final List<String> pNodeIds, final List<Boolean> pPassed, final List<PathResult> pOut)
	{
		if (pTarget == null || pNodeIds.size() > CandidateFilterCheck.MAX_DEPTH)
		{
			return;
		}
		if (LEAF_MATCH.equals(pTarget) || LEAF_SUSPECTED.equals(pTarget))
		{
			pOut.add(CandidateFilterCheck.evaluatePath(pById, pNodeIds, pPassed, pTarget));
			return;
		}
		if (LEAF_NOT_MATCHED.equals(pTarget) || pNodeIds.contains(pTarget))
		{
			return; // not a path to a match; a repeated node would be a cycle
		}
		final JsonNode node = pById.get(pTarget);
		if (node == null)
		{
			return;
		}
		pNodeIds.add(pTarget);
		pPassed.add(Boolean.TRUE);
		CandidateFilterCheck.walk(pById, node.path("onTrue").asText(null), pNodeIds, pPassed, pOut);
		pPassed.set(pPassed.size() - 1, Boolean.FALSE);
		CandidateFilterCheck.walk(pById, node.path("onFalse").asText(null), pNodeIds, pPassed, pOut);
		pNodeIds.remove(pNodeIds.size() - 1);
		pPassed.remove(pPassed.size() - 1);
	}

	private static PathResult evaluatePath(final Map<String, JsonNode> pById, final List<String> pNodeIds, final List<Boolean> pPassed, final String pOutcome)
	{
		final Set<String> keys = new LinkedHashSet<String>();
		final Map<String, Integer> percents = new LinkedHashMap<String, Integer>();
		final List<Rejection> rejections = new ArrayList<Rejection>();
		boolean anyPassed = false;
		for (int i = 0; i < pNodeIds.size(); i++)
		{
			if (!pPassed.get(i).booleanValue())
			{
				continue;
			}
			anyPassed = true;
			final String id = pNodeIds.get(i);
			final JsonNode node = pById.get(id);
			final String function = node.path("function").asText("");
			if (!FUNCTION_ALL_FIELDS.equals(function))
			{
				rejections.add(new Rejection(id, null, REASON_NOT_ALL_FIELDS, "node " + id + " is " + CandidateFilterCheck.functionWords(function)
					+ ", not an 'all fields match' (ALL_FIELDS) node, so none of its fields can be a candidate key"));
				continue;
			}
			for (final JsonNode field : node.path("fields"))
			{
				final String name = field.path("field").asText("?");
				final List<Rejection> fieldRejections = CandidateFilterCheck.rejectField(id, name, field);
				if (fieldRejections.isEmpty())
				{
					keys.add(name);
					final int percent = CandidateFilterCheck.keyPercent(field);
					final Integer seen = percents.get(name);
					percents.put(name, Integer.valueOf(seen == null ? percent : (seen.intValue() == 100 || percent == 100 ? 100 : Math.max(seen.intValue(), percent))));
				}
				else
				{
					rejections.addAll(fieldRejections);
				}
			}
		}
		if (!anyPassed)
		{
			rejections.add(new Rejection(null, null, REASON_NO_PASSED_NODE, "the path leaves every node through its 'does not pass' branch, so it has no node to take a key from"));
		}
		if (!keys.isEmpty())
		{
			rejections.clear();
		}
		return new PathResult(new ArrayList<String>(pNodeIds), pOutcome, new ArrayList<String>(keys), percents, rejections);
	}

	/** Lot M16: 100 for an {@code exact} key, the field's {@code minimumScore} (default 100, capped at 100) for an {@code exactFullText} key. */
	private static int keyPercent(final JsonNode pField)
	{
		final String algorithm = pField.path("algorithm").asText("").trim();
		final int at = algorithm.lastIndexOf('@');
		final String suffix = (at >= 0 ? algorithm.substring(at + 1) : algorithm).toLowerCase(Locale.ROOT);
		if (!"exactfulltext".equals(suffix))
		{
			return 100;
		}
		final int minimum = pField.hasNonNull("minimumScore") ? pField.path("minimumScore").asInt(100) : 100;
		return Math.max(1, Math.min(100, minimum));
	}

	/** Why one field of an {@code ALL_FIELDS} node is not an eligible key - empty when it is. */
	private static List<Rejection> rejectField(final String pNodeId, final String pName, final JsonNode pField)
	{
		final List<Rejection> out = new ArrayList<Rejection>();
		final String prefix = "node " + pNodeId + ", field '" + pName + "': ";
		if (pField.hasNonNull("via") && !pField.path("via").asText().trim().isEmpty())
		{
			out.add(new Rejection(pNodeId, pName, REASON_RELATION_FIELD,
				prefix + "it is a field of the related records '" + pField.path("via").asText() + "', not of the record itself"));
		}
		final String bothNull = CandidateFilterCheck.nullHandling(pField, "bothNull", "bothNullValueHandling");
		final String oneNull = CandidateFilterCheck.nullHandling(pField, "oneNull", "oneNullValueHandling");
		if (!NOT_MATCH.equals(bothNull) || !NOT_MATCH.equals(oneNull))
		{
			out.add(new Rejection(pNodeId, pName, REASON_NULL_HANDLING, prefix + "empty values must never match (bothNull and oneNull both NOT_MATCH), but bothNull is "
				+ bothNull + " and oneNull is " + oneNull));
		}
		final String algorithm = pField.path("algorithm").asText("");
		if (!CandidateFilterCheck.isKeyAlgorithm(algorithm))
		{
			out.add(new Rejection(pNodeId, pName, REASON_ALGORITHM,
				prefix + "its algorithm '" + algorithm + "' is neither exact nor exactFullText, the only two that give a candidate filter"));
		}
		if (pField.hasNonNull("minimumScore") && pField.path("minimumScore").asInt(0) <= 0)
		{
			out.add(new Rejection(pNodeId, pName, REASON_MINIMUM_SCORE, prefix + "its minimumScore is " + pField.path("minimumScore").asText() + ", it must be above 0"));
		}
		return out;
	}

	private static String nullHandling(final JsonNode pField, final String pShortKey, final String pLongKey)
	{
		if (pField.hasNonNull(pShortKey))
		{
			return pField.path(pShortKey).asText();
		}
		if (pField.hasNonNull(pLongKey))
		{
			return pField.path(pLongKey).asText();
		}
		return NOT_MATCH;
	}

	/** The add-on's two algorithms that give a filter: the last {@code @} segment is {@code exact} or {@code exactFullText}, case-insensitive. */
	static boolean isKeyAlgorithm(final String pAlgorithm)
	{
		final String trimmed = pAlgorithm == null ? "" : pAlgorithm.trim();
		final int at = trimmed.lastIndexOf('@');
		final String suffix = (at >= 0 ? trimmed.substring(at + 1) : trimmed).toLowerCase(Locale.ROOT);
		return "exact".equals(suffix) || "exactfulltext".equals(suffix);
	}

	private static String functionWords(final String pFunction)
	{
		if ("AVERAGE".equals(pFunction))
		{
			return "an 'average' (AVERAGE) node";
		}
		if ("AT_LEAST_N".equals(pFunction))
		{
			return "an 'at least N fields match' (AT_LEAST_N) node";
		}
		return pFunction.isEmpty() ? "a node of unknown function" : "a " + pFunction + " node";
	}

	/** The legacy {@code tiers} cascade as the flat tree {@code master.DecisionTreePlan.fromTiers} builds from it: tier i = node Ti, its outcome on {@code onTrue}, the next tier (or {@code NOT_MATCHED}) on {@code onFalse}. */
	private static JsonNode tiersToTree(final JsonNode pTiers)
	{
		final ObjectNode tree = JsonNodeFactory.instance.objectNode();
		final ArrayNode nodes = tree.putArray("nodes");
		final List<JsonNode> tiers = new ArrayList<JsonNode>();
		for (final JsonNode tier : pTiers)
		{
			tiers.add(tier);
		}
		for (int i = 0; i < tiers.size(); i++)
		{
			final JsonNode tier = tiers.get(i);
			final ObjectNode node = nodes.addObject();
			node.put("id", "T" + (i + 1));
			node.put("function", "AVERAGE".equals(tier.path("type").asText()) ? "AVERAGE" : FUNCTION_ALL_FIELDS);
			final ArrayNode fields = node.putArray("fields");
			for (final JsonNode field : tier.path("fields"))
			{
				final ObjectNode copy = fields.addObject();
				copy.put("field", field.path("field").asText(null));
				copy.put("algorithm", field.path("algorithm").asText(null));
				if (field.hasNonNull("minimumScore"))
				{
					copy.put("minimumScore", field.path("minimumScore").asInt());
				}
				if (field.hasNonNull("bothNullValueHandling"))
				{
					copy.put("bothNull", field.path("bothNullValueHandling").asText());
				}
				if (field.hasNonNull("oneNullValueHandling"))
				{
					copy.put("oneNull", field.path("oneNullValueHandling").asText());
				}
			}
			node.put("onTrue", tier.path("outcome").asText(LEAF_SUSPECTED));
			node.put("onFalse", i + 1 < tiers.size() ? "T" + (i + 2) : LEAF_NOT_MATCHED);
		}
		if (!tiers.isEmpty())
		{
			tree.put("root", "T1");
		}
		return tree;
	}
}
