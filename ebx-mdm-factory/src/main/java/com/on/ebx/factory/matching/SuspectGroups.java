package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 6, the queue per GROUP (2026-10-18): the add-on stores suspects as pairs ({@code <dataset>_<table>_MDS/root/Suspected}: sourceId, targetId) and a record stays in its own
 * group until a person confirms, so a person who exists in five records shows up as ten pairs. A group here is the connected component of the pairs - plus the golden record some of
 * its records are merged into, which joins it as a column - and is decided as a whole. Pure: the two halves are {@link #components} (which records belong together) and {@link #plan}
 * (which add-on decisions a person's choice means: the pairs to accept, in an order that merges the whole component, and the pairs to reject).
 */
public final class SuspectGroups
{
	private SuspectGroups()
	{
	}

	/** One connected component: its record keys (in first-seen order), the suspect pairs among them and the links to a golden record. */
	public static final class Group
	{
		public final List<String> members;
		/** The suspect pairs as stored: {@code [sourceId, targetId]}, each once. */
		public final List<String[]> pairs;
		/** Golden record keys that joined the component because some of its records are merged into them. */
		public final Set<String> goldens;

		Group(final List<String> pMembers, final List<String[]> pPairs, final Set<String> pGoldens)
		{
			this.members = pMembers;
			this.pairs = pPairs;
			this.goldens = pGoldens;
		}

		/** The smallest member key: the group's stable id. */
		public String id()
		{
			String best = null;
			for (final String member : this.members)
			{
				if (best == null || member.compareTo(best) < 0)
				{
					best = member;
				}
			}
			return best == null ? "" : best;
		}
	}

	/**
	 * The groups of {@code pPairs}. {@code pGoldenOf} (member key to the golden record it is merged into; may be null) adds the golden to the component of its member - a golden
	 * already existing appears as a column. Pairs are deduplicated whichever side is named first; a pair of a record with itself is ignored. The groups come in the order of their
	 * first pair.
	 */
	public static List<Group> components(final List<String[]> pPairs, final Map<String, String> pGoldenOf)
	{
		final Map<String, String> parent = new LinkedHashMap<String, String>();
		final Set<String> seen = new LinkedHashSet<String>();
		final List<String[]> unique = new ArrayList<String[]>();
		for (final String[] pair : pPairs)
		{
			if (pair == null || pair.length < 2 || pair[0] == null || pair[1] == null || pair[0].equals(pair[1]))
			{
				continue;
			}
			final String key = pair[0].compareTo(pair[1]) <= 0 ? pair[0] + "\u0000" + pair[1] : pair[1] + "\u0000" + pair[0];
			if (!seen.add(key))
			{
				continue;
			}
			unique.add(pair);
			SuspectGroups.add(parent, pair[0]);
			SuspectGroups.add(parent, pair[1]);
			SuspectGroups.union(parent, pair[0], pair[1]);
		}
		final Set<String> goldens = new LinkedHashSet<String>();
		if (pGoldenOf != null)
		{
			for (final String member : new ArrayList<String>(parent.keySet()))
			{
				final String golden = pGoldenOf.get(member);
				if (golden != null && !golden.isEmpty() && !golden.equals(member))
				{
					SuspectGroups.add(parent, golden);
					SuspectGroups.union(parent, member, golden);
					goldens.add(golden);
				}
			}
		}
		final Map<String, List<String>> membersByRoot = new LinkedHashMap<String, List<String>>();
		for (final String key : parent.keySet())
		{
			final String root = SuspectGroups.find(parent, key);
			if (!membersByRoot.containsKey(root))
			{
				membersByRoot.put(root, new ArrayList<String>());
			}
			membersByRoot.get(root).add(key);
		}
		final Map<String, List<String[]>> pairsByRoot = new HashMap<String, List<String[]>>();
		for (final String[] pair : unique)
		{
			final String root = SuspectGroups.find(parent, pair[0]);
			if (!pairsByRoot.containsKey(root))
			{
				pairsByRoot.put(root, new ArrayList<String[]>());
			}
			pairsByRoot.get(root).add(pair);
		}
		final List<Group> out = new ArrayList<Group>();
		for (final Map.Entry<String, List<String>> entry : membersByRoot.entrySet())
		{
			final Set<String> groupGoldens = new LinkedHashSet<String>();
			for (final String member : entry.getValue())
			{
				if (goldens.contains(member))
				{
					groupGoldens.add(member);
				}
			}
			final List<String[]> pairs = pairsByRoot.containsKey(entry.getKey()) ? pairsByRoot.get(entry.getKey()) : new ArrayList<String[]>();
			out.add(new Group(entry.getValue(), pairs, groupGoldens));
		}
		return out;
	}

	/** Groups of more records first, then by name ({@code pNameOf} of the group id), then by id - the order of the queue. Never mutates the input. */
	public static List<Group> ordered(final List<Group> pGroups, final Map<String, String> pNameOfMember)
	{
		final List<Group> out = new ArrayList<Group>(pGroups);
		Collections.sort(out, new Comparator<Group>()
		{
			@Override
			public int compare(final Group pA, final Group pB)
			{
				if (pA.members.size() != pB.members.size())
				{
					return pB.members.size() - pA.members.size();
				}
				final String nameA = SuspectGroups.name(pA, pNameOfMember);
				final String nameB = SuspectGroups.name(pB, pNameOfMember);
				final int byName = nameA.compareToIgnoreCase(nameB);
				return byName != 0 ? byName : pA.id().compareTo(pB.id());
			}
		});
		return out;
	}

	private static String name(final Group pGroup, final Map<String, String> pNameOfMember)
	{
		if (pNameOfMember != null)
		{
			for (final String member : pGroup.members)
			{
				final String name = pNameOfMember.get(member);
				if (name != null && !name.isEmpty())
				{
					return name;
				}
			}
		}
		return pGroup.id();
	}

	// ------------------------------------------------------------------------------------------------ decisions

	/** What a person's choice on a group means for the add-on. */
	public static final class Plan
	{
		/** Pairs to reject ({@code rejectSuspects}), as stored: {@code [sourceId, targetId]}. Done FIRST, before any merge changes the groups. */
		public final List<String[]> reject = new ArrayList<String[]>();
		/** Pairs to accept ({@code acceptSuspectsAndMergeGroups}), in an order that connects every record to the first one of its component: each one pulls a record of the pair into the group of the other. */
		public final List<String[]> accept = new ArrayList<String[]>();
		/** The records that end up merged (every member of a component of two or more kept records). */
		public final Set<String> merged = new LinkedHashSet<String>();
		/** Kept records that no suspect pair connects to another kept record: nothing can be merged for them (they were only linked through a record that was set aside). */
		public final List<String> alone = new ArrayList<String>();
		/** The pairs of the group the plan does not touch (pairs among the records set aside). */
		public final List<String[]> untouched = new ArrayList<String[]>();
	}

	/**
	 * "Same person - merge the N" ({@code pMerge}), with {@code pExcluded} the records a person ticked "not this one": the pairs between a kept and a set-aside record are rejected,
	 * the kept records are merged. Not merging ("different people") rejects every pair of the group. The accept pairs are a spanning forest of the kept records over the group's pairs
	 * (a golden already in the group is where each tree starts), so a chain A-B-C is merged with two accepts, never needing a pair A-C the add-on does not hold.
	 */
	public static Plan plan(final Group pGroup, final Set<String> pExcluded, final boolean pMerge)
	{
		final Plan plan = new Plan();
		final Set<String> excluded = pExcluded == null ? new LinkedHashSet<String>() : pExcluded;
		if (!pMerge)
		{
			plan.reject.addAll(pGroup.pairs);
			return plan;
		}
		final Set<String> kept = new LinkedHashSet<String>();
		for (final String member : pGroup.members)
		{
			if (!excluded.contains(member))
			{
				kept.add(member);
			}
		}
		final List<String[]> keptPairs = new ArrayList<String[]>();
		for (final String[] pair : pGroup.pairs)
		{
			final boolean aKept = kept.contains(pair[0]);
			final boolean bKept = kept.contains(pair[1]);
			if (aKept && bKept)
			{
				keptPairs.add(pair);
			}
			else if (aKept || bKept)
			{
				plan.reject.add(pair);
			}
			else
			{
				plan.untouched.add(pair);
			}
		}
		// the spanning forest: start each tree from a golden of the group when it holds one, else from the first kept record
		final List<String> starts = new ArrayList<String>();
		for (final String member : kept)
		{
			if (pGroup.goldens.contains(member))
			{
				starts.add(member);
			}
		}
		starts.addAll(kept);
		final Set<String> reached = new LinkedHashSet<String>();
		for (final String start : starts)
		{
			if (reached.contains(start))
			{
				continue;
			}
			final List<String> tree = new ArrayList<String>();
			tree.add(start);
			reached.add(start);
			for (int head = 0; head < tree.size(); head++)
			{
				final String node = tree.get(head);
				for (final String[] pair : keptPairs)
				{
					final String other = pair[0].equals(node) ? pair[1] : pair[1].equals(node) ? pair[0] : null;
					if (other != null && reached.add(other))
					{
						tree.add(other);
						plan.accept.add(pair);
					}
				}
			}
			if (tree.size() > 1)
			{
				plan.merged.addAll(tree);
			}
			else
			{
				plan.alone.add(start);
			}
		}
		return plan;
	}

	// ------------------------------------------------------------------------------------------------ union-find

	private static void add(final Map<String, String> pParent, final String pKey)
	{
		if (!pParent.containsKey(pKey))
		{
			pParent.put(pKey, pKey);
		}
	}

	private static String find(final Map<String, String> pParent, final String pKey)
	{
		String root = pKey;
		while (!pParent.get(root).equals(root))
		{
			root = pParent.get(root);
		}
		String node = pKey;
		while (!pParent.get(node).equals(root))
		{
			final String next = pParent.get(node);
			pParent.put(node, root);
			node = next;
		}
		return root;
	}

	private static void union(final Map<String, String> pParent, final String pA, final String pB)
	{
		final String rootA = SuspectGroups.find(pParent, pA);
		final String rootB = SuspectGroups.find(pParent, pB);
		if (!rootA.equals(rootB))
		{
			pParent.put(rootB, rootA);
		}
	}
}
