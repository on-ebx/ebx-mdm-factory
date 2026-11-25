package com.on.ebx.factory.backup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The renumbering of a restore (audit F-30): the ids of the archive (an auto-increment {@code id}) cannot be kept - another project of the target
 * instance may already use them - so every restored row of such a table gets a fresh id and every reference to it is rewritten.
 * {@link #put} records old key -> new key per table, {@link #get} translates a reference. A reference to a row that is not written yet (a forward
 * reference: an entity listing its member entities, a feed its parent feed) is {@link #defer deferred} and written when the whole archive has been read.
 * Pure: the engine owns the EBX calls.
 */
public final class IdRemap
{
	/** Tables whose rows nothing refers to and that can hold millions of rows: no map is kept for them. */
	private static final Set<String> UNTRACKED = new HashSet<String>(java.util.Arrays.asList("Xref", "TransferReject", "DistributionEvent"));

	private final Map<String, Map<String, String>> maps = new HashMap<String, Map<String, String>>();
	private final List<Deferred> deferred = new ArrayList<Deferred>();

	/** One reference written after everything else. */
	public static final class Deferred
	{
		public final String table;
		public final String newKey;
		public final String field;
		public final String targetTable;
		/** The archive's keys the field points to (one for a single-valued field). */
		public final List<String> oldTargets;
		public final boolean multi;

		Deferred(final String pTable, final String pNewKey, final String pField, final String pTargetTable, final List<String> pOldTargets, final boolean pMulti)
		{
			this.table = pTable;
			this.newKey = pNewKey;
			this.field = pField;
			this.targetTable = pTargetTable;
			this.oldTargets = pOldTargets;
			this.multi = pMulti;
		}
	}

	public static boolean tracked(final String pTable)
	{
		return !IdRemap.UNTRACKED.contains(pTable);
	}

	public void put(final String pTable, final String pOldKey, final String pNewKey)
	{
		if (!IdRemap.tracked(pTable))
		{
			return;
		}
		Map<String, String> map = this.maps.get(pTable);
		if (map == null)
		{
			map = new HashMap<String, String>();
			this.maps.put(pTable, map);
		}
		map.put(pOldKey, pNewKey);
	}

	/** The new key of an archived row, null when that row has not been written (yet, or never). */
	public String get(final String pTable, final String pOldKey)
	{
		final Map<String, String> map = this.maps.get(pTable);
		return map == null ? null : map.get(pOldKey);
	}

	public void defer(final String pTable, final String pNewKey, final String pField, final String pTargetTable, final List<String> pOldTargets, final boolean pMulti)
	{
		this.deferred.add(new Deferred(pTable, pNewKey, pField, pTargetTable, new ArrayList<String>(pOldTargets), pMulti));
	}

	public List<Deferred> deferred()
	{
		return this.deferred;
	}

	/** How many keys are mapped (all tables): what the report says about the renumbering. */
	public int size()
	{
		int total = 0;
		for (final Map<String, String> map : this.maps.values())
		{
			total += map.size();
		}
		return total;
	}
}
