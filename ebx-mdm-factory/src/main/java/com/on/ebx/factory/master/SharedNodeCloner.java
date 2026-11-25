package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot M5 (2026-10-18, {@code docs/MATCHING.md} &sect;60): a node that several branches lead to is COPIED, not refused.
 * <p>
 * The add-on's decision tree is nested ({@code trueNode} / {@code falseNode}): a node has exactly one parent, so a plan is a tree. The model, writing a plan as a flat list of
 * nodes, naturally ends several branches on one common fall-through node ("N7") - which the pass refused with {@code NODE_SHARED}, over and over (live run 454: three of the seven
 * refusals of one entity, then the budget). That constraint is the Factory's, not the model's problem: before the tree is validated, every node reached a second time is cloned
 * with its whole subtree under fresh ids, so the result is the strict tree the add-on needs. A real cycle (a branch leading back to a node of its own path) is NOT touched:
 * the validation that follows reports it.
 * <p>
 * Pure (Jackson only); works on the built nodes ({@code id}, {@code onTrue}, {@code onFalse}, and whatever else the node carries, copied as it is).
 */
final class SharedNodeCloner
{
	private static final String[] EDGES = { "onTrue", "onFalse" };

	private SharedNodeCloner()
	{
	}

	/**
	 * Clones, in {@code pNodes}, every node reached through more than one branch from {@code pRoot}; the copies are appended to the array.
	 *
	 * @return one English sentence per node that was copied (empty when the tree already was one), for the plan's stored rationale
	 */
	static List<String> declone(final String pRoot, final ArrayNode pNodes)
	{
		final Map<String, ObjectNode> byId = new LinkedHashMap<String, ObjectNode>();
		for (final JsonNode node : pNodes)
		{
			if (node.isObject() && node.hasNonNull("id") && !byId.containsKey(node.path("id").asText()))
			{
				byId.put(node.path("id").asText(), (ObjectNode) node);
			}
		}
		if (pRoot == null || !byId.containsKey(pRoot))
		{
			return new ArrayList<String>();
		}
		final State state = new State(byId, pNodes);
		state.owned.add(pRoot);
		state.visit(pRoot, new LinkedHashSet<String>());
		final List<String> notes = new ArrayList<String>();
		for (final Map.Entry<String, List<String>> entry : state.copies.entrySet())
		{
			notes.add("Node " + entry.getKey() + " was reached from several branches; the Factory copied it as " + String.join(", ", entry.getValue())
				+ " so that every node has one parent, as the add-on's nested tree needs");
		}
		return notes;
	}

	private static final class State
	{
		final Map<String, ObjectNode> byId;
		final ArrayNode nodes;
		/** Nodes that already have their one parent (or are the root). */
		final Set<String> owned = new HashSet<String>();
		/** Original id -&gt; ids of its copies. */
		final Map<String, List<String>> copies = new LinkedHashMap<String, List<String>>();
		private int counter;

		State(final Map<String, ObjectNode> pById, final ArrayNode pNodes)
		{
			this.byId = pById;
			this.nodes = pNodes;
			for (final String id : pById.keySet())
			{
				if (id.matches("[A-Za-z]+\\d+"))
				{
					this.counter = Math.max(this.counter, Integer.parseInt(id.replaceAll("^[A-Za-z]+", "")));
				}
			}
		}

		void visit(final String pId, final Set<String> pPath)
		{
			final ObjectNode node = this.byId.get(pId);
			pPath.add(pId);
			for (final String edge : SharedNodeCloner.EDGES)
			{
				final String target = node.path(edge).asText("");
				if (!this.byId.containsKey(target) || pPath.contains(target))
				{
					continue; // a leaf, an unknown id (reported by the validation) or a cycle (reported by it too)
				}
				if (!this.owned.contains(target))
				{
					this.owned.add(target);
					this.visit(target, pPath);
					continue;
				}
				// a second parent. Both edges of one node on the same target decide nothing: left as they are, for the validation to say so
				final String otherEdge = "onTrue".equals(edge) ? "onFalse" : "onTrue";
				if (target.equals(node.path(otherEdge).asText("")))
				{
					continue;
				}
				final String copy = this.copy(target, new LinkedHashSet<String>(), true);
				if (copy != null)
				{
					node.put(edge, copy);
					this.owned.add(copy);
				}
			}
			pPath.remove(pId);
		}

		/** A deep copy of the subtree under {@code pId}, fresh ids, appended to the node array; null (nothing appended) when the subtree holds a cycle. */
		private String copy(final String pId, final Set<String> pStack, final boolean pTopLevel)
		{
			if (pStack.contains(pId))
			{
				return null;
			}
			final ObjectNode original = this.byId.get(pId);
			pStack.add(pId);
			final ObjectNode clone = original.deepCopy();
			final String freshId = this.freshId(pId);
			for (final String edge : SharedNodeCloner.EDGES)
			{
				final String target = original.path(edge).asText("");
				if (this.byId.containsKey(target))
				{
					final String childCopy = this.copy(target, pStack, false);
					if (childCopy == null)
					{
						pStack.remove(pId);
						return null;
					}
					clone.put(edge, childCopy);
				}
			}
			pStack.remove(pId);
			clone.put("id", freshId);
			this.nodes.add(clone);
			this.byId.put(freshId, clone);
			this.owned.add(freshId);
			if (pTopLevel)
			{
				List<String> list = this.copies.get(pId);
				if (list == null)
				{
					list = new ArrayList<String>();
					this.copies.put(pId, list);
				}
				list.add(freshId);
			}
			return freshId;
		}

		private String freshId(final String pOriginal)
		{
			final String prefix = pOriginal.matches("[A-Za-z]+\\d+") ? pOriginal.replaceAll("\\d+$", "") : "N";
			String id;
			do
			{
				this.counter++;
				id = prefix + this.counter;
			}
			while (this.byId.containsKey(id));
			return id;
		}
	}
}
