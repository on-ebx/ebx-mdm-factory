package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot V2-B (2026-09-30, `docs/MATCHING.md` sec 47): the pure "simulate the plan WITHOUT its relation tiers" transform.
 * <p>
 * A relation tier is a decision node comparing at least one field read through a child table ({@link
 * DecisionTreePlan.FieldRef#isRelation()}: {@code Adresse.street} on a {@code Personne} plan). The simulation of
 * a preview policy normally carries those fields (native business-object fields since lot W13), but when that cannot be
 * done - no business object declared on the master model, a write the add-on refuses - the plan is simulated on its
 * remaining tiers instead, and the caller says so out loud ({@link Result#skippedTiers}).
 * <p>
 * <b>Semantics: a tier that cannot be evaluated "did not fire".</b> Every reference to a relation node is
 * replaced by that node's own {@code onFalse} target, repeatedly (the same rule the legacy {@code tiers[]}
 * cascade always had: {@link DecisionTreePlan#fromTiers} wires "no match" to the next tier), so a pair that
 * only the relation tier could have suspected is reported as decided by the remaining tiers - a LOWER BOUND on
 * the suspects, never an invented verdict. A node whose two branches then reach the same target is removed
 * (the add-on refuses such a node: "cannot connect to the same type of relationship status"), bottom-up until
 * stable, and nodes no longer reachable from the root are dropped. When nothing but a leaf is left ({@link
 * Result#nothingLeft}) the plan cannot be simulated at all.
 * <p>
 * Pure: no EBX class, unit-tested by {@code RelationTierStripperTest}.
 */
public final class RelationTierStripper
{
	private RelationTierStripper()
	{
	}

	/** {@link #strip}'s outcome. */
	public static final class Result
	{
		/** A deep copy of the requirements with {@code decisionTree} replaced by the stripped plan ({@code tiers} removed); the ORIGINAL object itself when nothing was stripped. */
		public final JsonNode requirements;
		/** One human-readable entry per stripped relation node, in plan order: {@code "<label or id> (Adresse.street, Adresse.city)"}. Empty when the plan compares no relation field. */
		public final List<String> skippedTiers;
		/** {@code true} when no decision node is left once the relation tiers are gone (the plan's root reaches a leaf): nothing to simulate. */
		public final boolean nothingLeft;

		Result(final JsonNode pRequirements, final List<String> pSkippedTiers, final boolean pNothingLeft)
		{
			this.requirements = pRequirements;
			this.skippedTiers = Collections.unmodifiableList(pSkippedTiers);
			this.nothingLeft = pNothingLeft;
		}
	}

	/** {@code true} when the plan {@code pRequirements} carries (decision tree or legacy tiers) compares at least one field read through a child entity. */
	public static boolean hasRelationFields(final JsonNode pRequirements)
	{
		final DecisionTreePlan.Plan plan = DecisionTreePlan.readFrom(pRequirements);
		for (final DecisionTreePlan.Node node : plan.nodes)
		{
			if (RelationTierStripper.isRelationNode(node))
			{
				return true;
			}
		}
		return false;
	}

	/** See the class Javadoc. Total: a plan with no relation node comes back unchanged ({@code skippedTiers} empty, {@code nothingLeft} false); never throws on a malformed graph (a cycle stops the redirection at the offending reference, which is then treated as {@code NOT_MATCHED}). */
	public static Result strip(final JsonNode pRequirements)
	{
		final DecisionTreePlan.Plan plan = DecisionTreePlan.readFrom(pRequirements);
		final List<String> skipped = new ArrayList<String>();
		final Set<String> relationIds = new LinkedHashSet<String>();
		for (final DecisionTreePlan.Node node : plan.nodes)
		{
			if (RelationTierStripper.isRelationNode(node))
			{
				relationIds.add(node.id);
				skipped.add(RelationTierStripper.describe(node));
			}
		}
		if (relationIds.isEmpty())
		{
			return new Result(pRequirements, skipped, false);
		}

		// work on a copy: the caller's plan object is read again later (never mutated here)
		final DecisionTreePlan.Plan work = DecisionTreePlan.fromJson(DecisionTreePlan.toJson(plan));
		final String newRoot = RelationTierStripper.redirect(work, work.root, relationIds);
		if (DecisionTreePlan.isLeaf(newRoot) || newRoot == null)
		{
			return new Result(pRequirements, skipped, true);
		}
		work.root = newRoot;
		final List<DecisionTreePlan.Node> kept = new ArrayList<DecisionTreePlan.Node>();
		for (final DecisionTreePlan.Node node : work.nodes)
		{
			if (!relationIds.contains(node.id))
			{
				node.onTrue = RelationTierStripper.redirect(work, node.onTrue, relationIds);
				node.onFalse = RelationTierStripper.redirect(work, node.onFalse, relationIds);
				kept.add(node);
			}
		}
		work.nodes.clear();
		work.nodes.addAll(kept);
		// a node whose branches now reach the same target decides nothing: replace every reference to it by that
		// target, until stable (removing one can make its parent collapse in turn)
		boolean changed = true;
		String root = work.root;
		while (changed)
		{
			changed = false;
			for (final DecisionTreePlan.Node node : new ArrayList<DecisionTreePlan.Node>(work.nodes))
			{
				if (node.onTrue != null && node.onTrue.equals(node.onFalse))
				{
					final String target = node.onTrue;
					work.nodes.remove(node);
					for (final DecisionTreePlan.Node other : work.nodes)
					{
						if (node.id.equals(other.onTrue))
						{
							other.onTrue = target;
						}
						if (node.id.equals(other.onFalse))
						{
							other.onFalse = target;
						}
					}
					if (node.id.equals(root))
					{
						root = target;
					}
					changed = true;
				}
			}
		}
		if (DecisionTreePlan.isLeaf(root) || root == null)
		{
			return new Result(pRequirements, skipped, true);
		}
		work.root = root;
		RelationTierStripper.pruneUnreachable(work);

		final ObjectNode copy = pRequirements.deepCopy();
		copy.set("decisionTree", DecisionTreePlan.toJson(work));
		copy.remove("tiers");
		return new Result(copy, skipped, false);
	}

	private static boolean isRelationNode(final DecisionTreePlan.Node pNode)
	{
		for (final DecisionTreePlan.FieldRef field : pNode.fields)
		{
			if (field.isRelation())
			{
				return true;
			}
		}
		return false;
	}

	/** {@code "<label or id> (Adresse.street, Adresse.city)"} - the relation fields only, in node order. */
	private static String describe(final DecisionTreePlan.Node pNode)
	{
		final StringBuilder fields = new StringBuilder();
		for (final DecisionTreePlan.FieldRef field : pNode.fields)
		{
			if (field.isRelation())
			{
				if (fields.length() > 0)
				{
					fields.append(", ");
				}
				fields.append(field.via).append('.').append(field.field);
			}
		}
		final String name = pNode.label != null && !pNode.label.trim().isEmpty() ? pNode.label : pNode.id;
		return name + " (" + fields + ")";
	}

	/** Follows {@code onFalse} while {@code pTarget} names a relation node; a leaf or an ordinary node stops it. A cycle or a dangling reference resolves to {@code NOT_MATCHED}. */
	private static String redirect(final DecisionTreePlan.Plan pPlan, final String pTarget, final Set<String> pRelationIds)
	{
		String current = pTarget;
		final Set<String> seen = new HashSet<String>();
		while (current != null && pRelationIds.contains(current))
		{
			if (!seen.add(current))
			{
				return DecisionTreePlan.LEAF_NOT_MATCHED;
			}
			final DecisionTreePlan.Node node = pPlan.byId(current);
			current = node == null ? DecisionTreePlan.LEAF_NOT_MATCHED : node.onFalse;
		}
		return current;
	}

	/** Drops every node not reachable from {@code pPlan.root} (a branch only a stripped node led to). */
	private static void pruneUnreachable(final DecisionTreePlan.Plan pPlan)
	{
		final Set<String> reachable = new HashSet<String>();
		final List<String> queue = new ArrayList<String>();
		queue.add(pPlan.root);
		while (!queue.isEmpty())
		{
			final String id = queue.remove(queue.size() - 1);
			if (id == null || DecisionTreePlan.isLeaf(id) || !reachable.add(id))
			{
				continue;
			}
			final DecisionTreePlan.Node node = pPlan.byId(id);
			if (node != null)
			{
				queue.add(node.onTrue);
				queue.add(node.onFalse);
			}
		}
		final List<DecisionTreePlan.Node> kept = new ArrayList<DecisionTreePlan.Node>();
		for (final DecisionTreePlan.Node node : pPlan.nodes)
		{
			if (reachable.contains(node.id))
			{
				kept.add(node);
			}
		}
		pPlan.nodes.clear();
		pPlan.nodes.addAll(kept);
	}
}
