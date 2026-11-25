package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.factory.landing.KeyPrimeRunner;
import com.on.ebx.factory.wizard.PartialFailures;

/**
 * F2-06 / F2-15 (audit 2026-10-23): the decisions that turn a half-written result into a job status. The suppliers existed ({@code StagingLoader} answers {@code status: PARTIAL} when a
 * closing write was refused, {@code KeyPrimer.prime} counts {@code Outcome.failed}); nothing read them, so the job ended green. A job that did its work for the most part but could not
 * write something ends {@link FactoryJobQueue#DONE_WITH_FAILURES} through {@link FactoryJobQueue.PartialOutcome}, with the coded message and what is missing. Pure: no EBX session.
 */
final class JobOutcomes
{
	static final String CODE_KEY_PRIME = "KEY_PRIME_FAILED";

	private JobOutcomes()
	{
	}

	/** Whether a {@code StagingLoader.load} answer says a closing write was not made ({@link StagingEndWrites#PARTIAL}). */
	static boolean stagingPartial(final JsonNode pResult)
	{
		return pResult != null && StagingEndWrites.PARTIAL.equals(pResult.path("status").asText(""));
	}

	/** The coded sentence the load carries for its closing writes (empty when it has none). */
	static String stagingMessage(final JsonNode pResult)
	{
		final long references = pResult == null ? 0L : pResult.path("referencesNotWritten").asLong(0L);
		final long landing = pResult == null ? 0L : pResult.path("landingStatusNotWritten").asLong(0L);
		final String carried = pResult == null ? "" : pResult.path("endMessage").asText("").trim();
		if (!carried.isEmpty())
		{
			return carried;
		}
		return "[" + StagingEndWrites.CODE_REFERENCES + "] " + references + " deferred reference(s) and [" + StagingEndWrites.CODE_LANDING_STATUS + "] " + landing
			+ " landing status row(s) were not written: run the staging load again with scope all.";
	}

	/** The partial outcome of a staging load answer, null when it was complete (or failed, which is another path). */
	static FactoryJobQueue.PartialOutcome stagingOutcome(final JsonNode pResult, final String pDoneMessage)
	{
		if (!JobOutcomes.stagingPartial(pResult))
		{
			return null;
		}
		final List<String> entries = new ArrayList<String>();
		if (pResult.path("referencesNotWritten").asLong(0L) > 0)
		{
			entries.add("references: " + pResult.path("referencesNotWritten").asLong(0L) + " deferred reference(s) not written");
		}
		if (pResult.path("landingStatusNotWritten").asLong(0L) > 0)
		{
			entries.add("landing status: " + pResult.path("landingStatusNotWritten").asLong(0L) + " row(s) keep their previous status");
		}
		final int total = (int) Math.max(pResult.path("read").asLong(0L), entries.size() + 1L);
		final String done = pDoneMessage == null || pDoneMessage.isEmpty() ? "" : " " + pDoneMessage;
		return new FactoryJobQueue.PartialOutcome(JobOutcomes.stagingMessage(pResult) + done, PartialFailures.fromEntries(entries, total));
	}

	/** The partial outcome of a key-priming pass that could not prime every key, null when it primed (or tried) cleanly. */
	static FactoryJobQueue.PartialOutcome keyPrimeOutcome(final KeyPrimeRunner.Outcome pOutcome, final String pDoneMessage)
	{
		if (pOutcome == null || pOutcome.failed <= 0)
		{
			return null;
		}
		final String done = pDoneMessage == null || pDoneMessage.isEmpty() ? "" : pDoneMessage + " ";
		final String message = done + "[" + JobOutcomes.CODE_KEY_PRIME + "] " + pOutcome.failed + " table key(s) could not be proposed automatically: those landing tables have no key yet, set it in the landing step (the reason is on each table).";
		final List<String> entries = new ArrayList<String>();
		entries.add("keys: " + pOutcome.failed + " table key(s) not primed");
		return new FactoryJobQueue.PartialOutcome(message, PartialFailures.fromEntries(entries, Math.max(pOutcome.studied, pOutcome.failed + 1)));
	}
}
