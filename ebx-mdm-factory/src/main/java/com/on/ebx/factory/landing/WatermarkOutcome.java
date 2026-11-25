package com.on.ebx.factory.landing;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Volume wave 2, lot V2-E (2026-10-03): what a Connectors feed run said about its WATERMARK (ebx-connectors
 * docs/REFERENCE.md 2.14.7), read from either of the two answers a landing load can end on - the synchronous
 * {@code connectors_sync} answer ({@code watermarkField}, {@code watermarkBefore}, {@code watermarkAfter},
 * {@code skippedByWatermark}, {@code watermarkAdvanced}, {@code fullRefresh}, {@code watermarkNote}) or the
 * {@code execution{}} object of {@code connectors_run_get} for an asynchronous run ({@code watermarkBefore},
 * {@code watermarkAfter}, {@code skippedByWatermark} only - it has neither the field name nor {@code fullRefresh}:
 * the Factory knows whether it asked for one, and says so through {@code pFullRefreshRequested}).
 * <p>
 * The one decision that matters downstream is {@link #filtered}: the run started from a stored checkpoint and did
 * not ignore it, so the rows it read are only the ones CHANGED since, never the whole table. Everything that
 * compares "rows read" with "rows the source has" ({@link LoadOutcomeRule}) or reads "rows of the last load" as
 * "rows of the landing table" must treat such a run differently. Pure: no EBX class.
 */
public final class WatermarkOutcome
{
	/** A run that carried no watermark at all (a FULL mapping, a file feed, an older Connectors build): every method says "nothing special". */
	public static final WatermarkOutcome NONE = new WatermarkOutcome(false, null, null, 0L, false, null);

	/** Whether the run was an incremental feed (the answer carried watermark fields). */
	public final boolean incremental;
	/** The checkpoint the run started from, {@code null} on a first run. */
	public final String before;
	/** The checkpoint after the run, {@code null} when none exists. */
	public final String after;
	/** Rows the engine dropped for being at or below the checkpoint (not part of the run's {@code count}). */
	public final long skipped;
	/** Whether the checkpoint was ignored and reset for this run. */
	public final boolean fullRefresh;
	/** Something the engine said about the checkpoint (an ignored stored value, a save that failed), or {@code null}. */
	public final String note;

	private WatermarkOutcome(final boolean pIncremental, final String pBefore, final String pAfter, final long pSkipped, final boolean pFullRefresh, final String pNote)
	{
		this.incremental = pIncremental;
		this.before = pBefore;
		this.after = pAfter;
		this.skipped = pSkipped;
		this.fullRefresh = pFullRefresh;
		this.note = pNote;
	}

	/** The watermark of a synchronous {@code connectors_sync} feed answer; {@link #NONE} when it carries none. */
	public static WatermarkOutcome fromSync(final JsonNode pAnswer, final boolean pFullRefreshRequested)
	{
		if (pAnswer == null || !pAnswer.hasNonNull("watermarkField"))
		{
			return WatermarkOutcome.NONE;
		}
		return new WatermarkOutcome(true, WatermarkOutcome.text(pAnswer, "watermarkBefore"), WatermarkOutcome.text(pAnswer, "watermarkAfter"),
			pAnswer.path("skippedByWatermark").asLong(0L), pFullRefreshRequested || pAnswer.path("fullRefresh").asBoolean(false), WatermarkOutcome.text(pAnswer, "watermarkNote"));
	}

	/** The watermark of a {@code connectors_run_get} {@code execution{}} object; {@link #NONE} when it carries none (the fields are absent for a run that is not an incremental feed). */
	public static WatermarkOutcome fromExecution(final JsonNode pExecution, final boolean pFullRefreshRequested)
	{
		if (pExecution == null || !(pExecution.has("watermarkBefore") || pExecution.has("watermarkAfter") || pExecution.has("skippedByWatermark")))
		{
			return WatermarkOutcome.NONE;
		}
		return new WatermarkOutcome(true, WatermarkOutcome.text(pExecution, "watermarkBefore"), WatermarkOutcome.text(pExecution, "watermarkAfter"),
			pExecution.path("skippedByWatermark").asLong(0L), pFullRefreshRequested, null);
	}

	private static String text(final JsonNode pNode, final String pField)
	{
		final String value = pNode.path(pField).asText("");
		return value.isEmpty() ? null : value;
	}

	/**
	 * True when the rows this run read are only the ones changed since a stored checkpoint: an incremental run that
	 * started from a checkpoint and did not ignore it. A first run (nothing stored) and a full refresh read everything.
	 */
	public boolean filtered()
	{
		return this.incremental && this.before != null && !this.fullRefresh;
	}

	/**
	 * Whether a successful run leaves a checkpoint that belongs to the change column it read through - what lets the
	 * Factory record that column as "the one of the last load" ({@code LandingTable.changeColumnLoaded}). A full
	 * refresh over an EMPTY source leaves the old checkpoint untouched (Connectors: "over an empty source the checkpoint
	 * is left as it was"), so it does not count; any other run that carried a watermark does.
	 */
	public boolean checkpointSettled(final long pRowsRead)
	{
		if (!this.incremental)
		{
			return true;
		}
		return !this.fullRefresh || pRowsRead > 0;
	}

	/**
	 * The sentence tail a load message carries ({@code ""} when the run carried no watermark): which checkpoint it
	 * started from, how many rows the watermark dropped, where the checkpoint is now. English, like every load message.
	 */
	public String describe()
	{
		if (!this.incremental)
		{
			return "";
		}
		final StringBuilder out = new StringBuilder();
		if (this.fullRefresh)
		{
			out.append("full refresh, watermark ignored");
		}
		else if (this.before == null)
		{
			out.append("first incremental load, everything read");
		}
		else
		{
			out.append("changed since the watermark ").append(this.before);
		}
		if (this.skipped > 0)
		{
			out.append(", ").append(this.skipped).append(this.skipped == 1 ? " unchanged row" : " unchanged rows").append(" skipped by the watermark");
		}
		if (this.after != null && !this.after.equals(this.before))
		{
			out.append(", watermark now ").append(this.after);
		}
		if (this.note != null)
		{
			out.append(" (").append(this.note).append(")");
		}
		return out.toString();
	}
}
