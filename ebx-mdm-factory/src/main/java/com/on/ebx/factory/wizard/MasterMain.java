package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 7 screen counts (2026-10-18): which master object is the MAIN one, the one whose golden records the screens quote. A master built from several tables holds an object and
 * its children (Personne, and the Adresse and Achat rows that point to it); the golden records of the object are what matches and is sent, the rows of its children
 * follow it. The main object is the built object that no other built object is a child of, the one with the most rows when there are several. Pure ({@code MasterMainTest}).
 */
public final class MasterMain
{
	private MasterMain()
	{
	}

	/**
	 * @param pBuilt the built master objects, in build order
	 * @param pChildren the objects that point to another built object through a foreign key (children of it)
	 * @param pRows the rows of each object ({@code null} or missing = not counted)
	 * @return the main object's name, or an empty string when nothing is built
	 */
	public static String pick(final List<String> pBuilt, final Set<String> pChildren, final Map<String, Long> pRows)
	{
		final List<String> candidates = new ArrayList<String>();
		for (final String name : pBuilt)
		{
			if (!pChildren.contains(name))
			{
				candidates.add(name);
			}
		}
		if (candidates.isEmpty())
		{
			candidates.addAll(new LinkedHashSet<String>(pBuilt)); // every object is a child of another (a cycle): none is better than the others
		}
		String best = "";
		long bestRows = -1L;
		for (final String name : candidates)
		{
			final Long rows = pRows == null ? null : pRows.get(name);
			final long count = rows == null ? 0L : rows.longValue();
			if (best.isEmpty() || count > bestRows)
			{
				best = name;
				bestRows = count;
			}
		}
		return best;
	}
}
