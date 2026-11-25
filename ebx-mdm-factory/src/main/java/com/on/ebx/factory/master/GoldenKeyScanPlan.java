package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.index.KeyValueIndex;

/**
 * Lot V3-M3 (2026-10-04, docs/VOLUME.md "Lot V3-M3"): the pure half of {@code ebx.GoldenKeyRefresher.refresh} when it works by scans instead of per record.
 * <p>
 * <b>The problem.</b> The refresh after a matching run (and after every catch-up of lot MP) read, for each {@code Xref} row of the entity, the row by key, the
 * master record by key and the add-on's state of that record - three random reads a row, about 50 s on 576,692 rows when nothing changed. The two facts it
 * needs are in two tables that can each be read in ONE streaming request: the add-on's {@code RecordMetadata} (state and golden of every master record) and {@code
 * Xref} itself (the current {@code goldenKey}). This class takes the first as an in-memory index and decides each {@code Xref} row of the second as it
 * streams past, keeping ONLY the rows to write - nothing about an unchanged row is held.
 * <p>
 * <b>The rule is {@link GoldenKeyRefreshDecision#decide}, unchanged</b>, fed as the per-record path fed it: {@code hasMetadata} = the record has a {@code
 * RecordMetadata} row with a state (any of GOLDEN, MERGED, PENDING_APPROVAL, DELETED, UNSET); the golden id = the record's golden only when its state is MERGED.
 * One refinement over the per-record path: a MERGED record's golden is followed through the index ({@link SurvivingRecord}, a golden that was itself merged
 * later, cycle-guarded) so the key written is the record that survives, the same hop-by-hop answer the master load gives a child's foreign key.
 * <p>
 * <b>The index value</b> is a {@code String} per record, so a record costs no object beyond its key: {@link #PRESENT} (any state but MERGED), {@link
 * #MERGED_NO_GOLDEN} (merged, no {@code goldenId} - the per-record path then also fell back to the record's own key), or the golden's key itself.
 */
public final class GoldenKeyScanPlan
{
	/** Value of a record whose state is not MERGED (it is its own golden record, or pending, or deleted). */
	public static final String PRESENT = "\u0000present";
	/** Value of a MERGED record with no usable {@code goldenId}. */
	public static final String MERGED_NO_GOLDEN = "\u0000merged";

	/** The add-on's {@code State} enumeration key of MERGED ({@code UNSET}=0 {@code GOLDEN}=1 {@code MERGED}=2 {@code PENDING_APPROVAL}=3 {@code DELETED}=4). */
	public static final int STATE_MERGED = 2;

	/** Lot IDX (2026-10-12): what the plan asks of the metadata index - the encoded state of a record, or null when it has no metadata. A {@code Map} and the compact {@link KeyValueIndex} both fit. */
	public interface Lookup
	{
		String get(String pFunctionalId);
	}

	private final Lookup metadata;
	private final List<String> writeKeys = new ArrayList<String>();
	/** Parallel to {@link #writeKeys}: the new {@code goldenKey}, null for a CLEAR. */
	private final List<String> writeValues = new ArrayList<String>();
	private int set;
	private int cleared;
	private int unchanged;

	/** @param pMetadata {@code functionalId -> encoded state} (see {@link #encode}); an empty map means "no record has metadata". */
	public GoldenKeyScanPlan(final Map<String, String> pMetadata)
	{
		this.metadata = new Lookup()
		{
			@Override
			public String get(final String pFunctionalId)
			{
				return pMetadata.get(pFunctionalId);
			}
		};
	}

	/** Lot IDX: the same plan over the compact index {@code ebx.MameMetadatasetReads#readRecordMetadataIndex} builds (about 30 bytes a record instead of about 90). */
	public GoldenKeyScanPlan(final KeyValueIndex pMetadata)
	{
		this.metadata = new Lookup()
		{
			@Override
			public String get(final String pFunctionalId)
			{
				return pMetadata.get(pFunctionalId);
			}
		};
	}

	/** The index value of a record: {@code pGoldenId} is used only for state {@link #STATE_MERGED}, blank means none. */
	public static String encode(final int pState, final String pGoldenId)
	{
		if (pState != GoldenKeyScanPlan.STATE_MERGED)
		{
			return GoldenKeyScanPlan.PRESENT;
		}
		final String golden = pGoldenId == null ? "" : pGoldenId.trim();
		return golden.isEmpty() ? GoldenKeyScanPlan.MERGED_NO_GOLDEN : golden;
	}

	/**
	 * Decides one {@code Xref} row; records the write if there is one.
	 *
	 * @param pXrefKey the row's own primary key (what the writer looks it up by)
	 * @param pMasterKey {@code Xref.masterKey}
	 * @param pCurrentGoldenKey {@code Xref.goldenKey} as stored
	 */
	public GoldenKeyRefreshDecision.Decision accept(final String pXrefKey, final String pMasterKey, final String pCurrentGoldenKey)
	{
		final String entry = pMasterKey == null ? null : this.metadata.get(pMasterKey);
		final boolean hasMetadata = entry != null;
		final String goldenId = hasMetadata ? this.survivor(pMasterKey, entry) : null;
		final GoldenKeyRefreshDecision.Decision decision = GoldenKeyRefreshDecision.decide(hasMetadata, goldenId, pMasterKey, pCurrentGoldenKey);
		switch (decision.action)
		{
		case SET:
			this.set++;
			this.writeKeys.add(pXrefKey);
			this.writeValues.add(decision.newGoldenKey);
			break;
		case CLEAR:
			this.cleared++;
			this.writeKeys.add(pXrefKey);
			this.writeValues.add(null);
			break;
		default:
			this.unchanged++;
			break;
		}
		return decision;
	}

	/** The golden to hand to the decision: null unless the record is MERGED with a golden; the end of its merge chain otherwise. */
	private String survivor(final String pMasterKey, final String pEntry)
	{
		if (!GoldenKeyScanPlan.isGolden(pEntry))
		{
			return null;
		}
		return SurvivingRecord.follow(pMasterKey, new SurvivingRecord.GoldenOf()
		{
			@Override
			public String goldenOf(final String pKey)
			{
				final String value = GoldenKeyScanPlan.this.metadata.get(pKey);
				return value != null && GoldenKeyScanPlan.isGolden(value) ? value : null;
			}
		});
	}

	private static boolean isGolden(final String pValue)
	{
		return !GoldenKeyScanPlan.PRESENT.equals(pValue) && !GoldenKeyScanPlan.MERGED_NO_GOLDEN.equals(pValue);
	}

	public int set()
	{
		return this.set;
	}

	public int cleared()
	{
		return this.cleared;
	}

	public int unchanged()
	{
		return this.unchanged;
	}

	public int writeCount()
	{
		return this.writeKeys.size();
	}

	/** The primary key of the i-th row to write. */
	public String writeKey(final int pIndex)
	{
		return this.writeKeys.get(pIndex);
	}

	/** The new {@code goldenKey} of the i-th row to write; null = clear it. */
	public String writeValue(final int pIndex)
	{
		return this.writeValues.get(pIndex);
	}
}
