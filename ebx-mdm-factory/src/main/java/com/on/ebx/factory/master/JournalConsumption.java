package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lot M-INC (2026-10-12, docs/VOLUME.md "Lot M-INC"): the pure rules of what a {@link MasterLoadScope#CHANGED} load reads from the journal and of which journal rows it
 * may delete afterwards. No EBX class: {@code ebx.MasterJournal} hands over keys and counters, so the rules are unit-tested ({@code JournalConsumptionTest}).
 * <ul>
 * <li><b>What is read</b> ({@link #keysToRead}): the journal's keys, then the staging keys of the entity's open MASTER rejects that the journal does not hold - a row
 * rejected earlier (for instance "unknown parent") can be accepted now although its staging row did not change, and only a re-read finds that out.</li>
 * <li><b>What is consumed</b> ({@link #consumable}): only the journal's own keys, and only when the whole load of the entity ended normally. A load that failed or was
 * stopped consumes nothing: the next one re-reads the same rows, which is idempotent.</li>
 * <li><b>How a row is deleted</b> ({@link #shouldDelete}): only if its change counter still holds the value read at the start. A staging record written again while the
 * load ran has a higher counter: its row stays, and the next load reads it.</li>
 * </ul>
 */
public final class JournalConsumption
{
	private JournalConsumption()
	{
	}

	/** The staging keys to read: {@code pJournal}'s keys in their order, then the reject keys not already there (no duplicate, nulls and blanks dropped). */
	public static Set<String> keysToRead(final Map<String, ?> pJournal, final Collection<String> pRejectKeys)
	{
		final Set<String> out = new LinkedHashSet<String>();
		if (pJournal != null)
		{
			for (final String key : pJournal.keySet())
			{
				if (key != null && !key.isEmpty())
				{
					out.add(key);
				}
			}
		}
		if (pRejectKeys != null)
		{
			for (final String key : pRejectKeys)
			{
				if (key != null && !key.isEmpty())
				{
					out.add(key);
				}
			}
		}
		return out;
	}

	/** The journal keys a load may try to delete: all of them when the entity ended normally, none otherwise. */
	public static List<String> consumable(final Map<String, ?> pJournalRead, final boolean pEntityEndedNormally)
	{
		if (!pEntityEndedNormally || pJournalRead == null)
		{
			return new ArrayList<String>();
		}
		return new ArrayList<String>(pJournalRead.keySet());
	}

	/** True when the journal row may be deleted: it still exists ({@code pCurrentChanges} not null) and its counter is the one read. A null read counter never matches a number. */
	public static boolean shouldDelete(final Integer pReadChanges, final Integer pCurrentChanges)
	{
		if (pCurrentChanges == null)
		{
			return false;
		}
		return pReadChanges != null && pReadChanges.intValue() == pCurrentChanges.intValue();
	}

	/** {@code pItems} cut into consecutive slices of at most {@code pSize} (one slice = one Procedure). */
	public static <T> List<List<T>> batches(final List<T> pItems, final int pSize)
	{
		final List<List<T>> out = new ArrayList<List<T>>();
		final int size = Math.max(1, pSize);
		for (int from = 0; from < pItems.size(); from += size)
		{
			out.add(new ArrayList<T>(pItems.subList(from, Math.min(pItems.size(), from + size))));
		}
		return out;
	}
}
