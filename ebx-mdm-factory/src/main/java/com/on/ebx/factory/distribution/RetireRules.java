package com.on.ebx.factory.distribution;

/**
 * Distribution lot 1 (engine, SPEC-distribution.md "What goes out"): "retire notices (a merged record -&gt;
 * the id of its survivor; a golden DELETED by an unmerge -&gt; retired)". Pure - no EBX class. The caller
 * (a master-table trigger's afterModify/afterDelete body, or {@code ebx.DistributionRunner} building a
 * changed/full send) reads the record's Match and Merge state the way CLAUDE.md sec 8 documents it
 * ({@code MetadataHelper.getState}/{@code getGroupId}/{@code isAutoCreated}, or the metadataset's
 * {@code RecordMetadata} row over SQL/Data Services) and hands the three plain facts here.
 * <p>
 * Two distinct MAME situations both retire a record, for different reasons:
 * <ul>
 * <li>{@code state=MERGED}: this row lost a match/merge and is no longer live; {@code goldenKey} is the
 * record that now carries its data ("An Unmerge leaves the auto-created golden as a PHYSICAL row..." -
 * CLAUDE.md sec 8 - documents the reverse operation, so a target must be told who to point to now).</li>
 * <li>{@code state=DELETED} on an {@code autoCreated} record: an Unmerge leaves the auto-created golden a
 * physical row, state {@code DELETED}, business values intact ("nothing that reads the business table
 * without filtering on the metadataset state tells it from a live record" - CLAUDE.md sec 8) - a target must
 * be retired with NO survivor, since the record simply no longer exists as a golden.</li>
 * </ul>
 * Every other state ({@code GOLDEN}, {@code PENDING_APPROVAL}, {@code UNSET}, or {@code DELETED} on a
 * record that was never auto-created - which never happens for a golden targeted by distribution, but the
 * rule is stated defensively) is not a retire.
 */
public final class RetireRules
{
	private RetireRules()
	{
	}

	public static final String STATE_GOLDEN = "GOLDEN";
	public static final String STATE_MERGED = "MERGED";
	public static final String STATE_PENDING_APPROVAL = "PENDING_APPROVAL";
	public static final String STATE_DELETED = "DELETED";
	public static final String STATE_UNSET = "UNSET";

	/** The retire decision for one record. */
	public static final class Outcome
	{
		public final boolean retire;
		/** The record to point to instead; null when {@link #retire} is false, or when the record is gone with nobody to point to (unmerge-deleted). */
		public final String survivorKey;

		Outcome(final boolean pRetire, final String pSurvivorKey)
		{
			this.retire = pRetire;
			this.survivorKey = pSurvivorKey;
		}

		public static Outcome none()
		{
			return new Outcome(false, null);
		}

		public static Outcome retireTo(final String pSurvivorKey)
		{
			return new Outcome(true, pSurvivorKey);
		}

		public static Outcome retireWithNoSurvivor()
		{
			return new Outcome(true, null);
		}
	}

	/**
	 * @param pState one of {@link #STATE_GOLDEN}/{@link #STATE_MERGED}/{@link #STATE_PENDING_APPROVAL}/{@link #STATE_DELETED}/{@link #STATE_UNSET} (CLAUDE.md sec 8's own vocabulary)
	 * @param pGoldenKey the record's current golden key when {@code pState} is {@link #STATE_MERGED}; ignored otherwise
	 * @param pAutoCreated whether the record was created automatically by a merge (only relevant when {@code pState} is {@link #STATE_DELETED})
	 */
	public static Outcome evaluate(final String pState, final String pGoldenKey, final boolean pAutoCreated)
	{
		if (RetireRules.STATE_MERGED.equals(pState))
		{
			return pGoldenKey == null || pGoldenKey.trim().isEmpty() ? Outcome.retireWithNoSurvivor() : Outcome.retireTo(pGoldenKey);
		}
		if (RetireRules.STATE_DELETED.equals(pState) && pAutoCreated)
		{
			return Outcome.retireWithNoSurvivor();
		}
		return Outcome.none();
	}
}
