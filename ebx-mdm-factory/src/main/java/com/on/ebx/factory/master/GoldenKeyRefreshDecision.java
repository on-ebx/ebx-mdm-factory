package com.on.ebx.factory.master;

/**
 * Whether and how one {@code Xref} row's {@code goldenKey} should change, given what the add-on's own
 * {@code RecordMetadata} says about the source record it points at (`docs/MASTER-ZONE.md` &sect;1.2,
 * &sect;3.4; `docs/MATCHING.md` &sect;4). Pure: no EBX class, so every branch is unit-tested without a
 * running instance - the EBX read (does a {@code RecordMetadata} row exist for this key, what is its
 * {@code goldenId}) is isolated in {@code ebx.GoldenKeyRefresher}, the only class that ever calls this one.
 * <p>
 * <b>"Belongs to its own golden record" includes a record that never merged into anything</b>: {@code
 * RecordMetadata.goldenId} is null both for a record that is itself the surviving golden AND for one
 * matching has not yet run on, so this class needs a third input, {@code pHasMetadata}, to tell those two
 * cases apart - a null {@code goldenId} WITH a {@code RecordMetadata} row present means "this record is
 * currently its own golden" (goldenKey becomes its own master key, so a lineage read never has to special-
 * case "not merged" vs. "merged"); a null {@code goldenId} with NO {@code RecordMetadata} row at all means
 * "matching has not touched this record yet" (goldenKey is left/cleared blank, matching the XSD field's own
 * comment, "Empty until the matching lot fills it").
 */
public final class GoldenKeyRefreshDecision
{
	private GoldenKeyRefreshDecision()
	{
	}

	public enum Action
	{
		NO_CHANGE, SET, CLEAR
	}

	public static final class Decision
	{
		public final Action action;
		/** Non-null only when {@link #action} is {@link Action#SET}. */
		public final String newGoldenKey;

		private Decision(final Action pAction, final String pNewGoldenKey)
		{
			this.action = pAction;
			this.newGoldenKey = pNewGoldenKey;
		}
	}

	/**
	 * @param pHasMetadata whether a {@code RecordMetadata} row exists for this record's own master key (false: matching never touched it, or the metadataset itself does not exist yet).
	 * @param pGoldenIdFromMetadata {@code RecordMetadata.goldenId} - null/blank when the record was never merged away (it may still be, itself, the golden).
	 * @param pOwnMasterKey the record's own master key ({@code Xref.masterKey}) - what {@code goldenKey} becomes when the record has not been merged into a DIFFERENT record.
	 * @param pCurrentGoldenKey {@code Xref.goldenKey} as currently stored.
	 */
	public static Decision decide(final boolean pHasMetadata, final String pGoldenIdFromMetadata, final String pOwnMasterKey, final String pCurrentGoldenKey)
	{
		final boolean currentBlank = GoldenKeyRefreshDecision.blank(pCurrentGoldenKey);
		if (!pHasMetadata)
		{
			return currentBlank ? new Decision(Action.NO_CHANGE, null) : new Decision(Action.CLEAR, null);
		}
		final String effective = GoldenKeyRefreshDecision.blank(pGoldenIdFromMetadata) ? pOwnMasterKey : pGoldenIdFromMetadata.trim();
		if (effective.equals(pCurrentGoldenKey))
		{
			return new Decision(Action.NO_CHANGE, null);
		}
		return new Decision(Action.SET, effective);
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}
}
