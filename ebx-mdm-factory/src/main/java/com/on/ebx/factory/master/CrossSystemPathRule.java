package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-PATH half of the cross-system guard (2026-10-12, `docs/MATCHING.md` &sect;4.2.3, measured live on project Vol100kN: four source systems, CRM/ERP/LOYALTY
 * fill {@code birthDate} in 85-95 % of their rows and ECOM never; the model was refused a path {@code nameMatchKey + birthDate -> SUSPECTED} for the
 * 4,700 true pairs that share neither an e-mail nor an address, because {@link CrossSystemFieldGuard} refused the FIELD as "entirely empty for ECOM" -
 * although the plan's other paths (e-mail, address keys, phone) already cover every pair involving ECOM, and the refused path would only have
 * been silent for those pairs).
 * <p>
 * A field that one source system never fills can bridge nothing for pairs involving that system, but it only hurts the plan when NO path to a positive
 * outcome ({@code MATCH}/{@code SUSPECTED}) can fire for such a pair. This class decides it on the assembled tree, purely structurally (no field name, no
 * domain): for each source system S, the set E(S) of fields empty in S is the set of fields the guard deferred for S, and a positive path is
 * <b>blocked for S</b> when one node it PASSES (leaves through {@code onTrue}) cannot be passed by a pair with those fields null on one side:
 * <ul>
 * <li>{@code ALL_FIELDS}: blocked when an E(S) field has {@code oneNull} NOT_MATCH (absent = NOT_MATCH, the add-on's default), or when the node compares nothing else;</li>
 * <li>{@code AT_LEAST_N}: blocked when fewer than {@code minimumMatches} fields remain able to match (an E(S) field with NOT_MATCH cannot; DO_NOTHING counts, the add-on's counting function reads it as agreement);</li>
 * <li>{@code AVERAGE}: blocked when the best reachable average, the E(S) fields scoring 0 (NOT_MATCH) or leaving the average (DO_NOTHING: not required), is below {@code minimumScore} - i.e. when their weight is needed to reach it;</li>
 * <li>anything else ({@code PREDICATE}): blocked (cannot be judged, the old refusal stands).</li>
 * </ul>
 * A node left through {@code onFalse} never blocks: a pair with a null cannot pass it, which is exactly the route it takes. Relation fields ({@code via}) are
 * not judged. A field is then refused only when, for one of ITS empty systems S, every positive path is blocked for S; otherwise it is accepted and a warning
 * names the paths that will not fire and the ones that still cover S. The bothNull handling concerns pairs inside S only, which this guard (a "bridge
 * the systems" check) never asked about. A tree with more than {@link #MAX_PATHS} positive paths is not analysed (truncated: the caller keeps the old
 * refusal). Pure: no EBX class.
 */
public final class CrossSystemPathRule
{
	/** Paths are enumerated by depth-first search; a real plan (at most {@link DecisionTreePlan#MAX_NODES} nodes, a few paths) is nowhere near this. */
	static final int MAX_PATHS = 512;
	/** How many paths a sentence lists before it says "and N more". */
	static final int MAX_LISTED_PATHS = 5;

	private CrossSystemPathRule()
	{
	}

	/** One path from the root to a {@code MATCH}/{@code SUSPECTED} leaf. */
	public static final class PositivePath
	{
		/** Ids of the nodes crossed, root first. */
		public final List<String> nodeIds;
		/** The nodes the path passes through their {@code onTrue} edge - the only ones that can block it. */
		final List<DecisionTreePlan.Node> passedNodes;
		/** {@code MATCH} or {@code SUSPECTED}. */
		public final String outcome;
		/** Lot M14: true when the last node of the path (the last of {@link #nodeIds}) reaches the positive leaf through its {@code onFalse} edge - a comparison that FAILED led to the leaf. */
		final boolean leafViaOnFalse;

		PositivePath(final List<String> pNodeIds, final List<DecisionTreePlan.Node> pPassedNodes, final String pOutcome, final boolean pLeafViaOnFalse)
		{
			this.nodeIds = pNodeIds;
			this.passedNodes = pPassedNodes;
			this.outcome = pOutcome;
			this.leafViaOnFalse = pLeafViaOnFalse;
		}

		/** {@code N1 > N3 (nameMatchKey + birthDate -> SUSPECTED)}: node ids crossed, the fields the path compares through its passed nodes, the outcome. */
		public String label()
		{
			final StringBuilder out = new StringBuilder();
			for (final String id : this.nodeIds)
			{
				out.append(out.length() == 0 ? "" : " > ").append(id);
			}
			final List<String> fields = new ArrayList<String>();
			for (final DecisionTreePlan.Node node : this.passedNodes)
			{
				for (final DecisionTreePlan.FieldRef field : node.fields)
				{
					if (field.field != null && !fields.contains(field.field))
					{
						fields.add(field.field);
					}
				}
			}
			out.append(" (");
			for (int i = 0; i < fields.size(); i++)
			{
				out.append(i == 0 ? "" : " + ").append(fields.get(i));
			}
			out.append(fields.isEmpty() ? "" : " ").append("-> ").append(this.outcome).append(')');
			return out.toString();
		}
	}

	/** The positive paths of a tree. */
	public static final class Paths
	{
		public final List<PositivePath> paths;
		/** True when the enumeration stopped at {@link #MAX_PATHS}: nothing can be concluded. */
		public final boolean truncated;

		Paths(final List<PositivePath> pPaths, final boolean pTruncated)
		{
			this.paths = pPaths;
			this.truncated = pTruncated;
		}
	}

	/** What to do with one deferred field. */
	public static final class Decision
	{
		/** True when, for at least one of the field's empty systems, every positive path is blocked (or the tree could not be analysed). */
		public final boolean refuse;
		/** The source systems for which no positive path survives (empty unless {@link #refuse}). */
		public final List<String> uncoveredSystems;
		/** {@link #refuse}: the paths of the tree to MATCH/SUSPECTED, listed for the refusal text; otherwise empty. */
		public final String pathsNote;
		/** Not {@link #refuse}: one sentence per empty system, worded for the model and the reviewer. */
		public final String warning;

		Decision(final boolean pRefuse, final List<String> pUncoveredSystems, final String pPathsNote, final String pWarning)
		{
			this.refuse = pRefuse;
			this.uncoveredSystems = pUncoveredSystems;
			this.pathsNote = pPathsNote;
			this.warning = pWarning;
		}
	}

	/** Every path from the root of {@code pPlan} to a {@code MATCH}/{@code SUSPECTED} leaf, {@code onTrue} before {@code onFalse}; cycle-safe. */
	public static Paths positivePaths(final DecisionTreePlan.Plan pPlan)
	{
		final List<PositivePath> out = new ArrayList<PositivePath>();
		if (pPlan == null || pPlan.root == null)
		{
			return new Paths(out, false);
		}
		final boolean complete = CrossSystemPathRule.walk(pPlan, pPlan.root, false, new ArrayList<String>(), new ArrayList<DecisionTreePlan.Node>(), new LinkedHashSet<String>(), out);
		return new Paths(out, !complete);
	}

	/** @return false when the cap was hit. */
	private static boolean walk(final DecisionTreePlan.Plan pPlan, final String pTarget, final boolean pViaOnFalse, final List<String> pIds, final List<DecisionTreePlan.Node> pPassed,
		final Set<String> pOnStack, final List<PositivePath> pOut)
	{
		if (pTarget == null)
		{
			return true;
		}
		if (DecisionTreePlan.isLeaf(pTarget))
		{
			if (DecisionTreePlan.LEAF_MATCH.equals(pTarget) || DecisionTreePlan.LEAF_SUSPECTED.equals(pTarget))
			{
				if (pOut.size() >= CrossSystemPathRule.MAX_PATHS)
				{
					return false;
				}
				pOut.add(new PositivePath(new ArrayList<String>(pIds), new ArrayList<DecisionTreePlan.Node>(pPassed), pTarget, pViaOnFalse));
			}
			return true;
		}
		final DecisionTreePlan.Node node = pPlan.byId(pTarget);
		if (node == null || !pOnStack.add(pTarget))
		{
			return true;
		}
		pIds.add(pTarget);
		pPassed.add(node);
		boolean complete = CrossSystemPathRule.walk(pPlan, node.onTrue, false, pIds, pPassed, pOnStack, pOut);
		pPassed.remove(pPassed.size() - 1);
		if (complete)
		{
			complete = CrossSystemPathRule.walk(pPlan, node.onFalse, true, pIds, pPassed, pOnStack, pOut);
		}
		pIds.remove(pIds.size() - 1);
		pOnStack.remove(pTarget);
		return complete;
	}

	/** True when {@code pPath} cannot fire for a pair whose fields {@code pEmptyFields} are null on one side. */
	public static boolean blocked(final PositivePath pPath, final Set<String> pEmptyFields)
	{
		for (final DecisionTreePlan.Node node : pPath.passedNodes)
		{
			if (CrossSystemPathRule.nodeBlocked(node, pEmptyFields))
			{
				return true;
			}
		}
		return false;
	}

	/** {@code oneNull} blocks unless it is explicitly something else than NOT_MATCH (absent = NOT_MATCH, as {@code CandidateFilterCheck} and the translator read it). */
	private static boolean oneNullBlocks(final DecisionTreePlan.FieldRef pField)
	{
		return pField.oneNull == null || pField.oneNull.trim().isEmpty() || pField.oneNull.trim().toUpperCase(Locale.ROOT).contains("NOT_MATCH");
	}

	private static boolean nodeBlocked(final DecisionTreePlan.Node pNode, final Set<String> pEmptyFields)
	{
		final List<DecisionTreePlan.FieldRef> empty = new ArrayList<DecisionTreePlan.FieldRef>();
		for (final DecisionTreePlan.FieldRef field : pNode.fields)
		{
			if (!field.isRelation() && field.field != null && pEmptyFields.contains(field.field))
			{
				empty.add(field);
			}
		}
		if (empty.isEmpty())
		{
			return false;
		}
		if (empty.size() == pNode.fields.size() && !DecisionTreePlan.FUNCTION_AVERAGE.equals(pNode.function))
		{
			// nothing else to compare: the node would "pass" on no evidence at all
			return true;
		}
		if (DecisionTreePlan.FUNCTION_ALL_FIELDS.equals(pNode.function))
		{
			for (final DecisionTreePlan.FieldRef field : empty)
			{
				if (CrossSystemPathRule.oneNullBlocks(field))
				{
					return true;
				}
			}
			return false;
		}
		if (DecisionTreePlan.FUNCTION_AT_LEAST_N.equals(pNode.function))
		{
			int unable = 0;
			for (final DecisionTreePlan.FieldRef field : empty)
			{
				if (CrossSystemPathRule.oneNullBlocks(field))
				{
					unable++;
				}
			}
			final int needed = pNode.minimumMatches == null ? pNode.fields.size() : pNode.minimumMatches.intValue();
			return pNode.fields.size() - unable < needed;
		}
		if (DecisionTreePlan.FUNCTION_AVERAGE.equals(pNode.function))
		{
			double total = 0;
			double leaves = 0;
			double zeroed = 0;
			for (final DecisionTreePlan.FieldRef field : pNode.fields)
			{
				final double weight = field.weight == null ? 1 : field.weight.intValue();
				total += weight;
				if (empty.contains(field))
				{
					if (CrossSystemPathRule.oneNullBlocks(field))
					{
						zeroed += weight;
					}
					else
					{
						leaves += weight;
					}
				}
			}
			final double denominator = total - leaves;
			if (denominator <= 0)
			{
				return true;
			}
			final double best = 100.0 * (denominator - zeroed) / denominator;
			final double minimum = pNode.minimumScore == null ? 100 : pNode.minimumScore.intValue();
			return best < minimum;
		}
		// PREDICATE, or a function this class does not know: cannot be judged - the old refusal stands
		return true;
	}

	/**
	 * Decides every deferred field at once (two deferred fields empty in the same system must not each lean on a path the other one blocks).
	 *
	 * @param pPlan the assembled, validated tree.
	 * @param pEmptySystemsByField field name (as written in the tree) mapped to the source systems where it is entirely empty; only fields the guard deferred.
	 * @return one {@link Decision} per field of {@code pEmptySystemsByField}, same order.
	 */
	public static Map<String, Decision> decide(final DecisionTreePlan.Plan pPlan, final Map<String, List<String>> pEmptySystemsByField)
	{
		final Map<String, Decision> out = new LinkedHashMap<String, Decision>();
		final Paths positive = CrossSystemPathRule.positivePaths(pPlan);
		final Map<String, Set<String>> emptyFieldsBySystem = new LinkedHashMap<String, Set<String>>();
		for (final Map.Entry<String, List<String>> entry : pEmptySystemsByField.entrySet())
		{
			for (final String system : entry.getValue())
			{
				Set<String> fields = emptyFieldsBySystem.get(system);
				if (fields == null)
				{
					fields = new LinkedHashSet<String>();
					emptyFieldsBySystem.put(system, fields);
				}
				fields.add(entry.getKey());
			}
		}
		for (final Map.Entry<String, List<String>> entry : pEmptySystemsByField.entrySet())
		{
			final String field = entry.getKey();
			if (positive.truncated)
			{
				out.put(field, new Decision(true, entry.getValue(), "", null));
				continue;
			}
			final List<String> uncovered = new ArrayList<String>();
			final StringBuilder warning = new StringBuilder();
			for (final String system : entry.getValue())
			{
				final Set<String> emptyHere = emptyFieldsBySystem.get(system);
				final List<String> blockedLabels = new ArrayList<String>();
				final List<String> coveringLabels = new ArrayList<String>();
				for (final PositivePath path : positive.paths)
				{
					(CrossSystemPathRule.blocked(path, emptyHere) ? blockedLabels : coveringLabels).add(path.label());
				}
				if (coveringLabels.isEmpty())
				{
					uncovered.add(system);
					continue;
				}
				if (warning.length() > 0)
				{
					warning.append(' ');
				}
				warning.append("field '").append(field).append("' is empty in system ").append(system).append(": ");
				if (blockedLabels.isEmpty())
				{
					warning.append("no path to MATCH or SUSPECTED needs it, so pairs involving ").append(system).append(" are not held back by it; the path(s) ")
						.append(CrossSystemPathRule.list(coveringLabels)).append(" cover them.");
				}
				else
				{
					warning.append("the path(s) ").append(CrossSystemPathRule.list(blockedLabels)).append(" will never fire for pairs involving ").append(system)
						.append("; the other path(s) ").append(CrossSystemPathRule.list(coveringLabels)).append(" still cover them.");
				}
			}
			if (uncovered.isEmpty())
			{
				out.put(field, new Decision(false, Collections.<String>emptyList(), "", warning.toString()));
			}
			else
			{
				final List<String> all = new ArrayList<String>();
				for (final PositivePath path : positive.paths)
				{
					all.add(path.label());
				}
				out.put(field, new Decision(true, uncovered, all.isEmpty() ? "this plan has no path to MATCH or SUSPECTED at all" : CrossSystemPathRule.list(all), null));
			}
		}
		return out;
	}

	private static String list(final List<String> pLabels)
	{
		final StringBuilder out = new StringBuilder();
		final int shown = Math.min(pLabels.size(), CrossSystemPathRule.MAX_LISTED_PATHS);
		for (int i = 0; i < shown; i++)
		{
			out.append(i == 0 ? "" : ", ").append(pLabels.get(i));
		}
		if (pLabels.size() > shown)
		{
			out.append(", and ").append(pLabels.size() - shown).append(" more");
		}
		return out.toString();
	}
}
