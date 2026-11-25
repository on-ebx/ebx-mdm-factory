package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.distribution.EventCoalescer.Coalesced;
import com.on.ebx.factory.distribution.EventCoalescer.Event;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class EventCoalescerTest
{
	public static void main(final String[] pArgs)
	{
		EventCoalescerTest.singleCreatePassesThrough();
		EventCoalescerTest.createThenModifyStaysCreate();
		EventCoalescerTest.createThenDeleteNetsToNone();
		EventCoalescerTest.modifyThenModifyStaysModify();
		EventCoalescerTest.modifyThenDeleteNetsToDelete();
		EventCoalescerTest.retireWinsOverAnythingBefore();
		EventCoalescerTest.deleteThenCreateRestarts();
		EventCoalescerTest.unsortedInputIsSortedBySequence();
		EventCoalescerTest.emptyListThrows();
		EventCoalescerTest.supersededBySendClosesOlderModifyInBackoff();
		EventCoalescerTest.supersededBySendNeverClosesDeleteOrRetire();
		EventCoalescerTest.supersededBySendItselfIsDeleteOrRetireClosesNothing();
		EventCoalescerTest.supersededBySendIgnoresLaterSequence();
		EventCoalescerTest.supersededMessageOnSentNamesTheRepresentative();
		EventCoalescerTest.supersededMessageOnSkippedNamesTheRepresentative();
		EventCoalescerTest.supersededMessageOnFailedExhaustedSaysSo();
		EventCoalescerTest.supersededMessageOnFailedRetryIsNullSoNothingIsClosedYet();
		EventCoalescerTest.nettedToNoneEveryIdIsSkippedWithTheSameMessage();
		System.out.println("EventCoalescerTest OK");
	}

	private static List<Event> events(final Object... pChangeThenSequence)
	{
		final List<Event> out = new ArrayList<Event>();
		int i = 0;
		int index = 0;
		for (final Object o : pChangeThenSequence)
		{
			if (o instanceof String)
			{
				final String change = (String) o;
				final long sequence = ((Number) pChangeThenSequence[index + 1]).longValue();
				out.add(new Event("e" + i, change, sequence));
				i++;
			}
			index++;
		}
		return out;
	}

	private static void singleCreatePassesThrough()
	{
		final Coalesced c = EventCoalescer.coalesce(EventCoalescerTest.events("CREATE", 1L));
		EventCoalescerTest.check("single CREATE stays CREATE", EventCoalescer.CREATE.equals(c.effectiveChange));
		EventCoalescerTest.check("survivor is the only event", "e0".equals(c.survivingId));
		EventCoalescerTest.check("nothing to skip", c.idsToSkip().isEmpty());
	}

	private static void createThenModifyStaysCreate()
	{
		final Coalesced c = EventCoalescer.coalesce(EventCoalescerTest.events("CREATE", 1L, "MODIFY", 2L));
		EventCoalescerTest.check("CREATE+MODIFY stays CREATE", EventCoalescer.CREATE.equals(c.effectiveChange));
		EventCoalescerTest.check("the surviving row is the last one (still sent as CREATE)", "e1".equals(c.survivingId));
		EventCoalescerTest.check("the first row is skipped", c.idsToSkip().contains("e0") && c.idsToSkip().size() == 1);
	}

	private static void createThenDeleteNetsToNone()
	{
		final Coalesced c = EventCoalescer.coalesce(EventCoalescerTest.events("CREATE", 1L, "DELETE", 2L));
		EventCoalescerTest.check("CREATE+DELETE nets to NONE", EventCoalescer.NONE.equals(c.effectiveChange));
		EventCoalescerTest.check("nothing survives", c.survivingId == null);
		EventCoalescerTest.check("every id is skipped", c.idsToSkip().size() == 2);
	}

	private static void modifyThenModifyStaysModify()
	{
		final Coalesced c = EventCoalescer.coalesce(EventCoalescerTest.events("MODIFY", 1L, "MODIFY", 2L));
		EventCoalescerTest.check("MODIFY+MODIFY stays MODIFY", EventCoalescer.MODIFY.equals(c.effectiveChange));
		EventCoalescerTest.check("the last row survives", "e1".equals(c.survivingId));
	}

	private static void modifyThenDeleteNetsToDelete()
	{
		final Coalesced c = EventCoalescer.coalesce(EventCoalescerTest.events("MODIFY", 1L, "DELETE", 2L));
		EventCoalescerTest.check("MODIFY+DELETE nets to DELETE", EventCoalescer.DELETE.equals(c.effectiveChange));
		EventCoalescerTest.check("the delete row survives", "e1".equals(c.survivingId));
	}

	private static void retireWinsOverAnythingBefore()
	{
		final Coalesced c = EventCoalescer.coalesce(EventCoalescerTest.events("MODIFY", 1L, "MODIFY", 2L, "RETIRE", 3L));
		EventCoalescerTest.check("RETIRE wins", EventCoalescer.RETIRE.equals(c.effectiveChange));
		EventCoalescerTest.check("the retire row survives", "e2".equals(c.survivingId));
		EventCoalescerTest.check("both modifies are skipped", c.idsToSkip().size() == 2);
	}

	private static void deleteThenCreateRestarts()
	{
		final Coalesced c = EventCoalescer.coalesce(EventCoalescerTest.events("DELETE", 1L, "CREATE", 2L));
		EventCoalescerTest.check("DELETE+CREATE restarts at CREATE", EventCoalescer.CREATE.equals(c.effectiveChange));
		EventCoalescerTest.check("the create row survives", "e1".equals(c.survivingId));
	}

	private static void unsortedInputIsSortedBySequence()
	{
		// given out of order, but sequence says CREATE(1) happened before DELETE(2): same net effect as createThenDeleteNetsToNone
		final Coalesced c = EventCoalescer.coalesce(EventCoalescerTest.events("DELETE", 2L, "CREATE", 1L));
		EventCoalescerTest.check("input order does not matter, only sequence", EventCoalescer.NONE.equals(c.effectiveChange));
	}

	private static void emptyListThrows()
	{
		boolean threw = false;
		try
		{
			EventCoalescer.coalesce(new ArrayList<Event>());
		}
		catch (final IllegalArgumentException ex)
		{
			threw = true;
		}
		EventCoalescerTest.check("an empty list throws", threw);
	}

	/**
	 * Round 5 (2026-09-26, task brief item 2): the exact live DistribTest shape - outbox row 4 (a MODIFY, attempt
	 * 7, sitting in retry backoff, sequence 4) and row 5 (a fresh MODIFY, sequence 5) for the same key. Row 5 is
	 * the one actually sent; row 4 must come back as superseded so it is closed instead of later re-sending the
	 * same now-stale state.
	 */
	private static void supersededBySendClosesOlderModifyInBackoff()
	{
		final List<Event> others = EventCoalescerTest.events("MODIFY", 4L);
		final List<String> superseded = EventCoalescer.idsSupersededBySend(EventCoalescer.MODIFY, 5L, others);
		EventCoalescerTest.check("the older backed-off MODIFY is superseded", superseded.size() == 1 && superseded.contains("e0"));
	}

	/** A pending DELETE or RETIRE for the same key is never closed by a later CREATE/MODIFY send - each has its own dedicated handling. */
	private static void supersededBySendNeverClosesDeleteOrRetire()
	{
		final List<Event> others = new ArrayList<Event>();
		others.add(new Event("del", EventCoalescer.DELETE, 1L));
		others.add(new Event("ret", EventCoalescer.RETIRE, 2L));
		final List<String> superseded = EventCoalescer.idsSupersededBySend(EventCoalescer.MODIFY, 10L, others);
		EventCoalescerTest.check("neither the pending DELETE nor the pending RETIRE is superseded", superseded.isEmpty());
	}

	/** A DELETE or RETIRE send closes nothing here at all - those paths never call this coalescing rule. */
	private static void supersededBySendItselfIsDeleteOrRetireClosesNothing()
	{
		final List<Event> others = EventCoalescerTest.events("MODIFY", 1L, "CREATE", 2L);
		EventCoalescerTest.check("a DELETE send supersedes nothing", EventCoalescer.idsSupersededBySend(EventCoalescer.DELETE, 10L, others).isEmpty());
		EventCoalescerTest.check("a RETIRE send supersedes nothing", EventCoalescer.idsSupersededBySend(EventCoalescer.RETIRE, 10L, others).isEmpty());
	}

	/** A pending CREATE/MODIFY with a HIGHER sequence than the one just sent (queued after the send started) is not superseded. */
	private static void supersededBySendIgnoresLaterSequence()
	{
		final List<Event> others = EventCoalescerTest.events("MODIFY", 20L);
		EventCoalescerTest.check("a later-sequenced pending event is left alone", EventCoalescer.idsSupersededBySend(EventCoalescer.MODIFY, 5L, others).isEmpty());
	}

	/**
	 * Round 6 (2026-09-26, task brief - DistribTest live incident, rows 6/7): the exact bug this round fixes
	 * was that a same-pass coalesced-away id (e.g. row 6, folded into row 7 by {@link EventCoalescer#coalesce})
	 * was deleted with NO history row at all, before row 7's own send even had a known outcome. These cases
	 * pin down {@link EventCoalescer#supersededMessage} - the ONE rule {@code ebx.DistributionDispatcher} now
	 * uses for both the same-pass ({@code coalesce().idsToSkip()}) and cross-pass ({@link
	 * EventCoalescer#idsSupersededBySend}) superseded ids - so the message wording and the retry-safety rule
	 * can never drift apart between the two call sites (task brief: "prefer one code path for both cases").
	 */
	private static void supersededMessageOnSentNamesTheRepresentative()
	{
		EventCoalescerTest.check("SENT: same wording as pre-round-6 closeSupersededRows",
			"superseded by event e7".equals(EventCoalescer.supersededMessage("e7", EventCoalescer.OUTCOME_SENT)));
	}

	private static void supersededMessageOnSkippedNamesTheRepresentative()
	{
		EventCoalescerTest.check("SKIPPED: the representative was still settled (never sent, but never retried under this id either)",
			"superseded by event e7".equals(EventCoalescer.supersededMessage("e7", EventCoalescer.OUTCOME_SKIPPED)));
	}

	private static void supersededMessageOnFailedExhaustedSaysSo()
	{
		EventCoalescerTest.check("FAILED past the retry budget: the folded ids share that fate, message says so",
			"superseded by event e7 (failed)".equals(EventCoalescer.supersededMessage("e7", EventCoalescer.OUTCOME_FAILED_EXHAUSTED)));
	}

	/**
	 * The core round-6 timing fix: while the representative is STILL retrying (not yet terminal), nothing is
	 * closed - {@code null} tells {@code ebx.DistributionDispatcher#closeSupersededIds} to leave every folded id
	 * exactly as it was (still PENDING), so a send that might still succeed on a later attempt never causes a
	 * folded id to be prematurely written off.
	 */
	private static void supersededMessageOnFailedRetryIsNullSoNothingIsClosedYet()
	{
		EventCoalescerTest.check("FAILED but still within the retry budget: not terminal yet, nothing closed", EventCoalescer.supersededMessage("e7", EventCoalescer.OUTCOME_FAILED_RETRY) == null);
	}

	/** A NONE result (e.g. CREATE+DELETE) has no representative id at all - every input id is skipped with the same dedicated message, and this is itself a terminal outcome (there is nothing left to send, ever, for this burst). */
	private static void nettedToNoneEveryIdIsSkippedWithTheSameMessage()
	{
		final Coalesced c = EventCoalescer.coalesce(EventCoalescerTest.events("CREATE", 1L, "DELETE", 2L));
		EventCoalescerTest.check("NONE nets to no survivor", c.survivingId == null);
		EventCoalescerTest.check("every id is in idsToSkip/allIds, ready for the dedicated NETTED_TO_NONE_MESSAGE", c.idsToSkip().size() == 2 && c.allIds.size() == 2);
		EventCoalescerTest.check("the dedicated message names no representative (there is none)", !EventCoalescer.NETTED_TO_NONE_MESSAGE.contains("event e"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
