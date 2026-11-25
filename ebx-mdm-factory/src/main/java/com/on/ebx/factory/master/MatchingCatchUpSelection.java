package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lot MP (2026-10-04): which master records to match once the add-on's matching is resumed after a master load. Pure - fed one record at a time by the single
 * streaming read of the master table ({@code ebx.MatchingCatchUp}), with the keys the add-on's metadataset already knows read by ONE other streaming request.
 * <p>
 * A record written while the matching was paused was not matched - by the load, or by anyone else (a data steward in the UI, another feed). It is selected when
 * <ol>
 * <li>its EBX last-modification time is at or after the instant the pause started ({@code Adaptation.getTimeOfLastModification}); or</li>
 * <li>it has no row in the metadataset's {@code RecordMetadata}: it was never matched at all.</li>
 * </ol>
 * A record the add-on merged away ({@code state MERGED}) is never selected - it is no longer a pivot. A record whose time is unknown is selected (matching it
 * once too often costs time, missing it costs a wrong result). When the metadataset could not be read ({@code pKnownKeys} null) only the time criterion applies.
 * The keys are kept only up to {@code pMaxKeys}: past it the caller runs one whole-table run and only needs the counts, so the counting goes on without storing.
 */
public final class MatchingCatchUpSelection
{
	private final long pauseStartedAtMillis;
	private final Set<String> knownKeys;
	private final Set<String> mergedKeys;
	private final int maxKeys;
	private final List<String> keys = new ArrayList<String>();
	private long scanned;
	private long selected;
	private long modifiedInWindow;
	private long neverMatched;
	private long skippedMerged;

	/**
	 * @param pPauseStartedAtMillis wall-clock instant taken BEFORE the first pause call
	 * @param pKnownKeys the {@code functionalId} of every {@code RecordMetadata} row, or null when the metadataset could not be read
	 * @param pMergedKeys the {@code functionalId} of the rows whose state is MERGED, or null for none
	 * @param pMaxKeys how many selected keys are stored (the enumeration bound)
	 */
	public MatchingCatchUpSelection(final long pPauseStartedAtMillis, final Set<String> pKnownKeys, final Set<String> pMergedKeys, final int pMaxKeys)
	{
		this.pauseStartedAtMillis = pPauseStartedAtMillis;
		this.knownKeys = pKnownKeys;
		this.mergedKeys = pMergedKeys;
		this.maxKeys = pMaxKeys;
	}

	/**
	 * @param pLastModifiedMillis the record's last-modification time in milliseconds, or a negative value when unknown
	 * @return true when the record was selected
	 */
	public boolean accept(final String pKey, final long pLastModifiedMillis)
	{
		this.scanned++;
		if (this.mergedKeys != null && this.mergedKeys.contains(pKey))
		{
			this.skippedMerged++;
			return false;
		}
		final boolean modified = pLastModifiedMillis < 0L || pLastModifiedMillis >= this.pauseStartedAtMillis;
		final boolean never = this.knownKeys != null && !this.knownKeys.contains(pKey);
		if (modified)
		{
			this.modifiedInWindow++;
		}
		if (never)
		{
			this.neverMatched++;
		}
		if (!modified && !never)
		{
			return false;
		}
		this.selected++;
		if (this.keys.size() < this.maxKeys)
		{
			this.keys.add(pKey);
		}
		return true;
	}

	/** The selected keys in the order read, at most {@code pMaxKeys} of them. */
	public List<String> keys()
	{
		return this.keys;
	}

	/** True when more records were selected than {@code pMaxKeys}: the keys list is then incomplete and the run covers the whole table. */
	public boolean overflow()
	{
		return this.selected > this.maxKeys;
	}

	public MatchingLoadPauseRule.CatchUpMode mode()
	{
		return MatchingLoadPauseRule.catchUpMode(this.selected, this.overflow());
	}

	public long scanned()
	{
		return this.scanned;
	}

	public long selected()
	{
		return this.selected;
	}

	/** Selected (or not) because modified in the window - a record both modified and never matched counts in both numbers. */
	public long modifiedInWindow()
	{
		return this.modifiedInWindow;
	}

	public long neverMatched()
	{
		return this.neverMatched;
	}

	public long skippedMerged()
	{
		return this.skippedMerged;
	}

	/** False when the metadataset could not be read: the never-matched criterion was not applied. */
	public boolean neverMatchedKnown()
	{
		return this.knownKeys != null;
	}
}
