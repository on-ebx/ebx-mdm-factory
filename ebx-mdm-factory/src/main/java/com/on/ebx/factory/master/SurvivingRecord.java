package com.on.ebx.factory.master;

import java.util.HashSet;
import java.util.Set;

/**
 * Which master record a foreign key should point to, when the record it was first resolved to has been merged
 * away. A master foreign key (Adresse.personneRef -&gt; Personne) is found through {@code Xref}: the SOURCE
 * record of the parent, an auto-increment id. Once Match and Merge folds that source record into a golden one,
 * the source record stays in the table in state MERGED, and a child written or rewritten afterwards would point at
 * it instead of the golden record the other children were re-pointed to (by the add-on when {@code alignForeignKeys}
 * is AUTOMATIC; since lot MT, 2026-10-04, {@code RelatedTableAlignment.WANTED} is NONE and the master load re-points them). This class follows the chain MERGED -&gt; golden, as many hops as the
 * add-on answers (a golden can itself be merged later), and stops on a cycle or an unknown target.
 * <p>
 * Pure: the add-on read is passed in as {@link GoldenOf}, so the chain logic is unit-tested without EBX.
 */
public final class SurvivingRecord
{
	/** Hops followed at most - far more than any real merge history, only a guard against a loop the cycle check would miss. */
	static final int MAX_HOPS = 16;

	/** The golden record a master record was merged into, or null when it is not merged (or the add-on does not say). */
	public interface GoldenOf
	{
		String goldenOf(String pMasterKey);
	}

	private SurvivingRecord()
	{
	}

	/** {@code pMasterKey} itself when it is not merged away, else the last record of its merge chain; null only for a null key. */
	public static String follow(final String pMasterKey, final GoldenOf pGoldenOf)
	{
		if (pMasterKey == null)
		{
			return null;
		}
		String current = pMasterKey;
		final Set<String> seen = new HashSet<String>();
		seen.add(current);
		for (int hop = 0; hop < SurvivingRecord.MAX_HOPS; hop++)
		{
			final String next = pGoldenOf.goldenOf(current);
			if (next == null || next.trim().isEmpty() || !seen.add(next))
			{
				return current;
			}
			current = next;
		}
		return current;
	}
}
