package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.List;

/**
 * Lot MP (2026-10-04): the pure decisions of {@code ebx.MatchingLoadPause} - pausing the Match and Merge add-on's matching on a master table while a master
 * load writes to it, and finding out what to match once it is resumed. No EBX class here, so every branch is unit-tested without an instance.
 * <p>
 * <b>Why a load pauses the matching at all</b> (measured 2026-10-04, project Vol1mF, EBX 6.3.0-rc2): a policy whose execution mode is "on update" makes the
 * add-on run an inline matching at EVERY batch commit of a master load; on the 8th batch of one load a clustering thread recovered the table's Lucene index to
 * an older transaction and every later read of that table failed until Tomcat was restarted. With the matching paused the same load ran to the end. In
 * {@code MANUAL} trigger mode the policy's execution mode is manual (lot MT), a load triggers nothing and there is nothing to pause.
 */
public final class MatchingLoadPauseRule
{
	/** {@code Project.matchingTrigger}: the value that makes a load pause the matching (anything else, a missing field included, reads as manual). */
	public static final String REALTIME = "REALTIME";

	/** Above this many records to match after a pause, the catch-up runs ONE run on the whole table instead of enumerating the keys (a bulk load touched too much). */
	public static final int CATCHUP_ENUMERATED_MAX = 5000;

	/** Keys per enumerated catch-up run: a predicate of 200 numeric keys stays a few kilobytes. */
	public static final int CATCHUP_CHUNK_SIZE = 200;

	private MatchingLoadPauseRule()
	{
	}

	/** What to do with one table, from its {@code get_matching_activation_status} answer. */
	public enum Action
	{
		/** Enabled and running: this load pauses it, and resumes it afterwards. */
		PAUSE,
		/** Already paused (by a person, an earlier failed Configure...): left alone, and NOT resumed by this load. */
		ALREADY_PAUSED,
		/** Not enabled (disabled, never activated, status unreadable): nothing runs on its own, nothing to pause. */
		NOT_ENABLED
	}

	/** True only for the literal {@code REALTIME} (any case): a missing field/node, a null or any other value is the manual mode. */
	public static boolean isRealtime(final Object pRawTrigger)
	{
		return pRawTrigger != null && REALTIME.equalsIgnoreCase(String.valueOf(pRawTrigger).trim());
	}

	/**
	 * {@code get_matching_activation_status} answers {@code state: ENABLED, paused: false} for a running table and {@code state: PAUSED, paused: true} once
	 * {@code set_table_settings {"pauseMatching": true}} was applied (measured 2026-10-04). A table is paused by this load only when it is ENABLED and not
	 * paused; either sign of a pause leaves it alone.
	 */
	public static Action decide(final String pState, final Boolean pPaused)
	{
		if (Boolean.TRUE.equals(pPaused) || "PAUSED".equalsIgnoreCase(pState == null ? "" : pState.trim()))
		{
			return Action.ALREADY_PAUSED;
		}
		return "ENABLED".equalsIgnoreCase(pState == null ? "" : pState.trim()) ? Action.PAUSE : Action.NOT_ENABLED;
	}

	/** How a catch-up matches the records of the window. */
	public enum CatchUpMode
	{
		/** Nothing to match. */
		NONE,
		/** Scoped runs on explicit key predicates, {@link #CATCHUP_CHUNK_SIZE} keys each. */
		ENUMERATED,
		/** One run on the whole table: too many records to enumerate. */
		WHOLE_TABLE
	}

	public static CatchUpMode catchUpMode(final long pSelected, final boolean pOverflow)
	{
		if (pSelected <= 0L)
		{
			return CatchUpMode.NONE;
		}
		return pOverflow ? CatchUpMode.WHOLE_TABLE : CatchUpMode.ENUMERATED;
	}

	/** {@code pKeys} cut into lists of at most {@code pSize} (the last one shorter), in order. */
	public static List<List<String>> chunks(final List<String> pKeys, final int pSize)
	{
		if (pSize <= 0)
		{
			throw new IllegalArgumentException("chunk size must be positive: " + pSize);
		}
		final List<List<String>> out = new ArrayList<List<String>>();
		for (int from = 0; from < pKeys.size(); from += pSize)
		{
			out.add(new ArrayList<String>(pKeys.subList(from, Math.min(pKeys.size(), from + pSize))));
		}
		return out;
	}
}
