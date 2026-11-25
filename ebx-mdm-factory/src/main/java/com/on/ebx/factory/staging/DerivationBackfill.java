package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure half of the backfill of a stored script derivation (lot T1, 2026-10-04): which rows of a staging table must be written so that
 * the two table triggers of {@code ebx.DerivationTriggers} compute the derived field of rows that ALREADY exist. No EBX class: the table is
 * seen through {@link Rows} and {@link Writer}, so the decisions, the guard and the batching are tested against an in-memory fake.
 * <p>
 * <b>Why a backfill at all.</b> The derived attribute is an ordinary stored field fed by a {@code beforeCreate} and a {@code beforeModify}
 * trigger; a trigger only fires on a write. A row that was loaded before the derivation was applied holds nothing (or an old copy of its
 * source - the loader used to fill it, docs/SCRIPT-DERIVATION.md sec 8), and nothing would ever compute it until the row changes.
 * <p>
 * <b>The mechanism, and why it is not "write the same value back".</b> Whether EBX fires {@code beforeModify} for a modification that
 * changes nothing is not known from Java (not measured). The modify trigger does recompute when the derived field itself differs between
 * the old and the new record (measured: a hand-written value is overwritten by the computed one), so the backfill WRITES a value that
 * cannot be the computed one - the fixed {@link #SENTINEL} for a text field - and the trigger overwrites it in the same transaction: the
 * sentinel is never persisted. A derived field that is not text cannot take a string; there the current value is replaced by {@code null}
 * (the trigger recomputes on an empty new value as well), and a row whose value is already empty cannot be changed that way at all - it is
 * counted as {@link Result#skippedNullNonString} and left (a documented limitation, docs/SCRIPT-DERIVATION.md sec 10).
 * <p>
 * <b>Guard.</b> If the triggers are not live on the published model (declared but not published, a stale dataset...), the sentinel would be
 * persisted on every row. So the FIRST row to touch is written alone ({@link Writer#writeFirst}) and read back inside its own transaction;
 * the writer throws {@link TriggerNotLive} when the stored value is still the sentinel, which rolls that transaction back and ends the pass
 * with nothing changed.
 * <p>
 * Rows to touch: those whose bound source attributes are NOT all empty, or whose derived value is not empty ({@link #decide}). A row with
 * nothing in, nothing out stays as it is.
 */
public final class DerivationBackfill
{
	/** The text written into a text derived field; never a value a library function returns, and never stored (the modify trigger replaces it). */
	public static final String SENTINEL = "__recompute__";

	/** How often the scan looks at the cancellation flag, in rows. */
	public static final int CANCEL_CHECK_EVERY = 50000;

	private DerivationBackfill()
	{
	}

	/** What to do with one row. */
	public enum Action
	{
		/** Write {@link #SENTINEL}. */
		WRITE_SENTINEL,
		/** Write {@code null} (a derived field that is not text, holding a value). */
		WRITE_NULL,
		/** Nothing to compute: no source value and no derived value. */
		SKIP_NOTHING_TO_DO,
		/** A non-text derived field already empty: the trigger cannot be provoked by a write of {@code null}. */
		SKIP_NULL_NON_STRING
	}

	/**
	 * @param pDerivedIsText whether the derived field is text ({@code string})
	 * @param pAnySourceNonNull whether at least one bound source attribute of the row holds a value
	 * @param pDerivedNonNull whether the derived field of the row holds a value
	 */
	public static Action decide(final boolean pDerivedIsText, final boolean pAnySourceNonNull, final boolean pDerivedNonNull)
	{
		if (!pAnySourceNonNull && !pDerivedNonNull)
		{
			return Action.SKIP_NOTHING_TO_DO;
		}
		if (pDerivedIsText)
		{
			return Action.WRITE_SENTINEL;
		}
		return pDerivedNonNull ? Action.WRITE_NULL : Action.SKIP_NULL_NON_STRING;
	}

	/** The value an action writes: the sentinel, or {@code null}. */
	public static String valueOf(final Action pAction)
	{
		return pAction == Action.WRITE_SENTINEL ? DerivationBackfill.SENTINEL : null;
	}

	/** Receives the rows of one scan, in table order. */
	public interface RowVisitor
	{
		/**
		 * @param pKey the formatted key of the row (messages and tests)
		 * @param pAnySourceNonNull whether a bound source attribute holds a value
		 * @param pDerivedNonNull whether the derived field holds a value
		 * @param pHandle what {@link Writer} needs to modify this very row (the record itself); opaque here
		 * @return {@code false} to stop the scan
		 */
		boolean row(String pKey, boolean pAnySourceNonNull, boolean pDerivedNonNull, Object pHandle) throws Exception;
	}

	/** The table seen as one streaming scan. */
	public interface Rows
	{
		/** Visits every row once; the scan stays open while {@link Writer} writes from inside the visitor (the result is frozen when it starts). */
		void scan(RowVisitor pVisitor) throws Exception;
	}

	/** One row to write. */
	public static final class Update
	{
		public final Object handle;
		public final String key;
		/** The sentinel, or {@code null}. */
		public final String value;

		Update(final Object pHandle, final String pKey, final String pValue)
		{
			this.handle = pHandle;
			this.key = pKey;
			this.value = pValue;
		}
	}

	/** Thrown by {@link Writer#writeFirst} when the row read back still holds the sentinel: the triggers are not live. */
	public static final class TriggerNotLive extends Exception
	{
		private static final long serialVersionUID = 1L;

		public TriggerNotLive(final String pMessage)
		{
			super(pMessage);
		}
	}

	/** The table's write side. */
	public interface Writer
	{
		/**
		 * Writes ONE row in its own transaction, reads it back inside that transaction, and throws {@link TriggerNotLive} (rolling it back) when the
		 * sentinel is still there. For a derived field that is not text the writer has nothing to check and simply writes.
		 */
		void writeFirst(Update pUpdate) throws Exception;

		/** Writes the batch in ONE transaction; must throw when the write fails. */
		void writeBatch(List<Update> pBatch) throws Exception;
	}

	/** What one pass did. */
	public static final class Result
	{
		/** Rows read. */
		public long scanned;
		/** Rows written, so computed by the trigger. */
		public long recomputed;
		/** Rows not written (both reasons below). */
		public long skipped;
		/** Of {@link #skipped}: rows with a non-text derived field already empty, that a write cannot provoke (the documented limitation). */
		public long skippedNullNonString;
		/** Transactions of more than one row. */
		public long writeBatches;
		/** The pass was stopped before the end. */
		public boolean cancelled;
		/** The key of the row written alone as the guard, or {@code null} when no row needed a write. */
		public String guardRow;
		public long durationMs;

		public String summary()
		{
			return "read " + this.scanned + " row(s), recomputed " + this.recomputed + ", skipped " + this.skipped
				+ (this.skippedNullNonString > 0 ? " (" + this.skippedNullNonString + " empty non-text value(s) a write cannot provoke)" : "")
				+ (this.cancelled ? ", stopped before the end" : "") + " in " + this.durationMs + " ms";
		}
	}

	/**
	 * One pass over {@code pRows}: decides each row, writes the first one to touch alone (the guard), the others in batches of {@code
	 * pBatchSize}.
	 *
	 * @throws TriggerNotLive when the guard row came back with the sentinel: nothing was changed
	 */
	public static Result run(final Rows pRows, final Writer pWriter, final boolean pDerivedIsText, final int pBatchSize, final ChildKeysSweep.Cancellation pCancellation)
		throws Exception
	{
		final long started = System.currentTimeMillis();
		final Result result = new Result();
		final int batchSize = Math.max(1, pBatchSize);
		final List<Update> batch = new ArrayList<Update>();
		final boolean[] guarded = new boolean[1];
		pRows.scan(new RowVisitor()
		{
			@Override
			public boolean row(final String pKey, final boolean pAnySourceNonNull, final boolean pDerivedNonNull, final Object pHandle) throws Exception
			{
				result.scanned++;
				final Action action = DerivationBackfill.decide(pDerivedIsText, pAnySourceNonNull, pDerivedNonNull);
				if (action == Action.SKIP_NOTHING_TO_DO)
				{
					result.skipped++;
				}
				else if (action == Action.SKIP_NULL_NON_STRING)
				{
					result.skipped++;
					result.skippedNullNonString++;
				}
				else
				{
					final Update update = new Update(pHandle, pKey, DerivationBackfill.valueOf(action));
					if (!guarded[0])
					{
						pWriter.writeFirst(update);
						guarded[0] = true;
						result.guardRow = pKey;
						result.recomputed++;
					}
					else
					{
						batch.add(update);
						if (batch.size() >= batchSize)
						{
							DerivationBackfill.flush(pWriter, batch, result);
							if (pCancellation != null && pCancellation.cancelled())
							{
								result.cancelled = true;
								return false;
							}
						}
					}
				}
				if (result.scanned % DerivationBackfill.CANCEL_CHECK_EVERY == 0 && pCancellation != null && pCancellation.cancelled())
				{
					result.cancelled = true;
					return false;
				}
				return true;
			}
		});
		if (!batch.isEmpty())
		{
			DerivationBackfill.flush(pWriter, batch, result);
		}
		result.durationMs = System.currentTimeMillis() - started;
		return result;
	}

	private static void flush(final Writer pWriter, final List<Update> pBatch, final Result pResult) throws Exception
	{
		pWriter.writeBatch(new ArrayList<Update>(pBatch));
		pResult.recomputed += pBatch.size();
		pResult.writeBatches++;
		pBatch.clear();
	}
}
