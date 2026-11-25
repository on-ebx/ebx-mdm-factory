package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

/**
 * F2-06 (audit 2026-10-23): the two writes that close a staging load - the deferred foreign keys (pass 2) and the landing status written back to the landing
 * rows - used to log their failure and let the step end DONE ({@code ProcedureResult.hasFailed()} was read, then dropped). This collector keeps what was NOT written,
 * so the step ends {@link #PARTIAL} with a coded message that names it and says what to run again. Pure: no EBX class, tested without a runtime.
 */
final class StagingEndWrites
{
	/** The step and result status of a load that finished but could not write one of its closing writes: neither DONE nor FAILED. */
	static final String PARTIAL = "PARTIAL";

	static final String CODE_REFERENCES = "STAGING_END_REFERENCES_NOT_WRITTEN";
	static final String CODE_LANDING_STATUS = "STAGING_END_LANDING_STATUS_NOT_WRITTEN";

	private final List<String> references = new ArrayList<String>();
	private final List<String> landing = new ArrayList<String>();
	private long referenceRows;
	private long landingRows;

	/** A batch of deferred references that was refused: {@code pRows} rows of {@code pField} keep an empty reference. */
	void referencesNotWritten(final String pField, final int pRows, final String pCause)
	{
		this.referenceRows += pRows;
		this.references.add(pRows + " on " + pField + " (" + StagingEndWrites.cut(pCause) + ")");
	}

	/** A landing status write-back that was refused: {@code pRows} rows of {@code pLandingTable} keep their previous status. */
	void landingStatusNotWritten(final String pLandingTable, final int pRows, final String pCause)
	{
		this.landingRows += pRows;
		this.landing.add(pRows + " of " + pLandingTable + " (" + StagingEndWrites.cut(pCause) + ")");
	}

	boolean any()
	{
		return !this.references.isEmpty() || !this.landing.isEmpty();
	}

	long referenceRows()
	{
		return this.referenceRows;
	}

	long landingRows()
	{
		return this.landingRows;
	}

	/** {@code FAILED} when every row read was rejected, else {@link #PARTIAL} when a closing write failed, else {@code DONE}; a stop is never turned into a failure. */
	String status(final boolean pStopped, final boolean pTotalFailure)
	{
		if (pStopped)
		{
			return "STOPPED";
		}
		if (pTotalFailure)
		{
			return "FAILED";
		}
		return this.any() ? StagingEndWrites.PARTIAL : "DONE";
	}

	/** The sentences appended to the step message, one per kind of write that failed, each with its code, what was not written and what to run again; empty when all was written. */
	String message()
	{
		final StringBuilder out = new StringBuilder();
		if (!this.references.isEmpty())
		{
			out.append(" [").append(StagingEndWrites.CODE_REFERENCES).append("] ").append(this.referenceRows).append(" deferred reference(s) could not be written: ")
				.append(StagingEndWrites.join(this.references)).append(". Those staging rows keep an empty reference and the master would be built without it:")
				.append(" fix the cause, then run the staging load again with scope all (POST /projects/{code}/staging/load?scope=all, or Load the staging in the wizard).");
		}
		if (!this.landing.isEmpty())
		{
			out.append(" [").append(StagingEndWrites.CODE_LANDING_STATUS).append("] the landing status of ").append(this.landingRows).append(" row(s) could not be written back: ")
				.append(StagingEndWrites.join(this.landing)).append(". Those landing rows keep their previous status and are read again by the next load:")
				.append(" run the staging load again (scope new is enough; the load is idempotent by primary key).");
		}
		return out.toString();
	}

	private static String join(final List<String> pItems)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pItems.size(); i++)
		{
			if (i == 3)
			{
				out.append(", and ").append(pItems.size() - 3).append(" more");
				break;
			}
			out.append(i == 0 ? "" : ", ").append(pItems.get(i));
		}
		return out.toString();
	}

	private static String cut(final String pCause)
	{
		final String text = pCause == null ? "no detail" : pCause.replace('\n', ' ').trim();
		return text.length() > 160 ? text.substring(0, 157) + "..." : text;
	}
}
