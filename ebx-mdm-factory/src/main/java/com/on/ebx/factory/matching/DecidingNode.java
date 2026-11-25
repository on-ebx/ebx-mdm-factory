package com.on.ebx.factory.matching;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Step 6, the group card's "why" (2026-10-18, lot M12): which node of the PLAN linked two records. The add-on's {@code executeDecisionTree} answers the nodes it walked
 * ({@code getDecisionNodes()}) and flags the last one ({@code isLastNode()}); that last one is the verdict's own leaf ("Suspect"), not a rule - the card used to print it ("rule
 * Suspect") on every group. The deciding node is the last node walked BEFORE the leaf. Pure: the names come from {@code MameMatchingApi.TreeEvaluation}, the plan's node names from
 * {@code review.MatchPlanFacts#nodes}.
 */
public final class DecidingNode
{
	private DecidingNode()
	{
	}

	/** True for the names the add-on gives a verdict leaf (the schema's tokens and the display strings of a live configuration). */
	public static boolean isLeafName(final String pName)
	{
		if (pName == null)
		{
			return false;
		}
		final String key = pName.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
		return key.equals("match") || key.equals("matched") || key.equals("suspect") || key.equals("suspected") || key.equals("not match") || key.equals("not matched")
			|| key.equals("no match");
	}

	/**
	 * The name of the node that decided, or null when the walk does not say. {@code pPlanNodeNames} (may be empty) are the engine names of the plan's nodes: the last walked name
	 * that is one of them wins. Without a match in the plan, the node before the leaf in the walk, or the flagged last node when it is not a leaf.
	 */
	public static String pick(final List<String> pNodeNames, final String pLastNodeName, final Set<String> pPlanNodeNames)
	{
		if (pNodeNames != null && pPlanNodeNames != null && !pPlanNodeNames.isEmpty())
		{
			for (int i = pNodeNames.size() - 1; i >= 0; i--)
			{
				final String name = pNodeNames.get(i);
				if (name != null && pPlanNodeNames.contains(name))
				{
					return name;
				}
			}
		}
		if (pNodeNames != null && !pNodeNames.isEmpty())
		{
			// the leaf ends the walk: the node before it is the rule
			int end = pNodeNames.size() - 1;
			while (end >= 0 && (pNodeNames.get(end) == null || DecidingNode.isLeafName(pNodeNames.get(end))))
			{
				end--;
			}
			if (end >= 0 && end < pNodeNames.size() - 1)
			{
				return pNodeNames.get(end);
			}
			if (end == pNodeNames.size() - 1)
			{
				// the walk holds no leaf at all: its last node is the one whose branch ended in the verdict
				return pNodeNames.get(end).isEmpty() ? null : pNodeNames.get(end);
			}
		}
		return pLastNodeName != null && !pLastNodeName.isEmpty() && !DecidingNode.isLeafName(pLastNodeName) ? pLastNodeName : null;
	}
}
