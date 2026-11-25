package com.on.ebx.factory.master;

/**
 * Master-load lot V3-M2 (2026-10-04, docs/VOLUME.md "Lot V3-M2"): whether an entity's master load may build its in-memory indexes
 * (one streaming read per table, then a join in memory) or must keep the row-by-row lookups of lot V3-M. Pure, no EBX class.
 * <p>
 * The indexes hold a 128-bit key digest and one or two {@code long}s per row (lot IDX: about 30 bytes a row for the {@code Xref} index, 38 for the master one; before it, strings in
 * {@code HashMap}s, 145 and 130: see {@code ebx.MasterLoadReads}), so the bound is a ROW COUNT, the same kind of bound as {@code ebx.ChildKeysPass.MAX_PAIRS_IN_MEMORY}.
 * It is read from the system property {@link #PROPERTY} when the load starts; 0 (or a negative value) disables the indexes altogether, which is
 * also the way to run the old per-row path on purpose - the comparison that proves the two modes classify the rows alike.
 */
public final class ScanBudget
{
	/** The system property that overrides {@link #DEFAULT_MAX_INDEXED_ROWS}. */
	public static final String PROPERTY = "mdm.factory.masterLoad.maxIndexedRows";

	/**
	 * Lot V3-S2 (2026-10-04): the same bound for the STAGING load (scope {@code all}) - one index per staging table (key and digests of its rows,
	 * {@code ebx.StagingLoadReads}), so the bound is the row count of that one table. Same default, same meaning of 0.
	 */
	public static final String STAGING_PROPERTY = "mdm.factory.stagingLoad.maxIndexedRows";

	/**
	 * Lot V3-M3 (2026-10-04): the bound for the golden-key refresh ({@code ebx.GoldenKeyRefresher}), which holds one index of the add-on's {@code RecordMetadata}
	 * table (lot IDX: about 31 bytes a row; 90 plus about 50 per distinct golden before it), so the bound is that table's row count. Same default, same meaning of 0 (the per-record path).
	 */
	public static final String GOLDEN_REFRESH_PROPERTY = "mdm.factory.goldenRefresh.maxIndexedRows";

	/**
	 * 15,000,000 rows (lot IDX, 2026-10-12; was 5,000,000). The indexes are compact since that lot (docs/VOLUME.md "Lot IDX - compact indexes", measured on synthetic keys): about
	 * 30 bytes a row for {@code Xref}, 38 for the master digests and for a staging table, 31 for a merged record, against 155 / 131 / 139 / 88 before. The worst entity at
	 * the bound (all three master indexes full, plus its parent's {@code Xref} index kept for the foreign keys) is about 1.9 GB - what 5,000,000 rows weighed with the
	 * {@code HashMap}s - so the bound grew by the measured gain of 3 (not 4: the three indexes can coexist with the add-on's own caches), leaving a margin of more than 4 on the
	 * 8 GB heap of the FastTrack server. Raise it per instance only after reading {@code indexBytes} in a step detail on that data.
	 */
	public static final long DEFAULT_MAX_INDEXED_ROWS = 15000000L;

	private ScanBudget()
	{
	}

	/** The bound from a property value: the default when it is missing or not a whole number, otherwise the number (0 or less = indexes disabled). */
	public static long parse(final String pPropertyValue)
	{
		if (pPropertyValue == null || pPropertyValue.trim().isEmpty())
		{
			return ScanBudget.DEFAULT_MAX_INDEXED_ROWS;
		}
		try
		{
			return Long.parseLong(pPropertyValue.trim());
		}
		catch (final NumberFormatException ex)
		{
			return ScanBudget.DEFAULT_MAX_INDEXED_ROWS;
		}
	}

	/** The bound in force now ({@link #PROPERTY}, read at each load so a change needs no restart). */
	public static long current()
	{
		return ScanBudget.parse(System.getProperty(ScanBudget.PROPERTY));
	}

	/** The staging load's bound in force now ({@link #STAGING_PROPERTY}, read at each load so a change needs no restart). */
	public static long currentStaging()
	{
		return ScanBudget.parse(System.getProperty(ScanBudget.STAGING_PROPERTY));
	}

	/** The golden-key refresh's bound in force now ({@link #GOLDEN_REFRESH_PROPERTY}, read at each refresh so a change needs no restart). */
	public static long currentGoldenRefresh()
	{
		return ScanBudget.parse(System.getProperty(ScanBudget.GOLDEN_REFRESH_PROPERTY));
	}

	/** Lot V3-M3: null when the metadata index may be built, otherwise the reason it is not (it goes into the refresh's {@code readReason}). */
	public static String goldenRefreshRefusal(final long pMax, final long pMetadataRows)
	{
		if (pMax <= 0L)
		{
			return "disabled (" + ScanBudget.GOLDEN_REFRESH_PROPERTY + "=" + pMax + ")";
		}
		if (pMetadataRows > pMax)
		{
			return pMetadataRows + " RecordMetadata rows exceed " + ScanBudget.GOLDEN_REFRESH_PROPERTY + "=" + pMax;
		}
		return null;
	}

	/**
	 * Lot V3-S2: null when one table's index may be built, otherwise the reason it is not.
	 *
	 * @param pProperty the property that sets the bound, named in the reason
	 * @param pMax the bound
	 * @param pRows rows of the table the index would hold
	 */
	public static String refusal(final String pProperty, final long pMax, final long pRows)
	{
		if (pMax <= 0L)
		{
			return "disabled (" + pProperty + "=" + pMax + ")";
		}
		if (pRows > pMax)
		{
			return pRows + " staging rows exceed " + pProperty + "=" + pMax;
		}
		return null;
	}

	/**
	 * Null when the indexes may be built, otherwise the reason they are not (it goes into the step detail as {@code lookupReason}).
	 *
	 * @param pMax the bound, see {@link #current}
	 * @param pStagingRows rows of the entity's staging table - the rows to read
	 * @param pMasterRows rows of the entity's master table - what the master index would hold
	 */
	public static String refusal(final long pMax, final long pStagingRows, final long pMasterRows)
	{
		if (pMax <= 0L)
		{
			return "disabled (" + ScanBudget.PROPERTY + "=" + pMax + ")";
		}
		if (pStagingRows > pMax)
		{
			return pStagingRows + " staging rows exceed " + ScanBudget.PROPERTY + "=" + pMax;
		}
		if (pMasterRows > pMax)
		{
			return pMasterRows + " master rows exceed " + ScanBudget.PROPERTY + "=" + pMax;
		}
		return null;
	}
}
