package com.on.ebx.factory.master;

/**
 * What {@code ebx.MasterLoader} does with one staged (promoted-entity) row, given only two facts: whether an
 * {@code Xref} row for it already carries a {@code masterKey}, and - only when it does - whether that master
 * record is currently MERGED AWAY (its {@code RecordMetadata.goldenId} is set: the add-on's Match and Merge
 * moved it into another, golden record). `docs/MASTER-ZONE.md` §3.1 and this class's own brief agree on the
 * three outcomes:
 * <ul>
 * <li>No {@code Xref.masterKey} yet -&gt; {@link Action#INSERT}: this staging row has never reached master.</li>
 * <li>A {@code masterKey} exists and the record is NOT merged away -&gt; {@link Action#UPDATE}: a plain
 * content update, the same as {@code staging.StagingLoader}'s own upsert.</li>
 * <li>A {@code masterKey} exists and the record IS merged away -&gt; {@link Action#SKIP_MERGED}: never
 * overwritten blindly. The real {@code Companies} table's own {@code tableSettings.modifyMergedRecords} is
 * {@code false} (READ (live), `docs/MASTER-ZONE.md` §1.1/§3.1) - EBX itself is expected to refuse a content
 * update on such a record today, and until that refusal (or a future project setting enabling
 * {@code modifyMergedRecords}) is confirmed live with a real write, the safe default is to never attempt the
 * write at all and count the row instead.</li>
 * </ul>
 * How "merged away" itself is determined is deliberately NOT this class's concern - {@code ebx.MasterLoader}
 * reads it through a small {@code MergeCheck} interface (SQL against the add-on's own
 * {@code RecordMetadata.goldenId}, see its own Javadoc) and hands the single resulting boolean in here. This
 * split is what "keep the check behind a small interface with a pure test" (the coordinator's brief) means in
 * this codebase: the EBX-bound LOOKUP lives in {@code ebx.MasterLoader}, the DECISION it feeds is pure and
 * unit-tested here without any EBX class, mirroring {@code staging.TechnicalKeyRule}'s own split between a
 * status test and what a caller does with it.
 */
public final class MasterUpsertDecision
{
	private MasterUpsertDecision()
	{
	}

	public enum Action
	{
		INSERT, UPDATE, SKIP_MERGED
	}

	public static Action decide(final boolean pHasMasterKey, final boolean pMergedAway)
	{
		if (!pHasMasterKey)
		{
			return Action.INSERT;
		}
		return pMergedAway ? Action.SKIP_MERGED : Action.UPDATE;
	}
}
