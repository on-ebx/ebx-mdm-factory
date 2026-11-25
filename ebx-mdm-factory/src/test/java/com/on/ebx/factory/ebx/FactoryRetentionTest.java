package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.Set;

/** Lot C4: the history purge never deletes evidence, a running step or the latest success of a (project, step, scope). */
public final class FactoryRetentionTest
{
	public static void main(final String[] pArgs)
	{
		final long day = 24L * 3600L * 1000L;
		final long now = 1000L * day;
		final long cutoff = now - 90L * day;
		final Set<String> purged = FactoryRetention.pipelineStepsToPurge(Arrays.asList(
			new FactoryRetention.StepRow("1", "p|LANDING_MODEL|a", "DONE", cutoff - 50L * day, false),
			new FactoryRetention.StepRow("2", "p|LANDING_MODEL|a", "DONE", cutoff - 10L * day, false),
			new FactoryRetention.StepRow("3", "p|LANDING_MODEL|a", "FAILED", cutoff - 5L * day, false),
			new FactoryRetention.StepRow("4", "p|STAGING_DATASET|", "DONE", cutoff - 60L * day, true),
			new FactoryRetention.StepRow("5", "p|STAGING_DATASET|", "DONE", cutoff - 30L * day, false),
			new FactoryRetention.StepRow("6", "p|MASTER_LOAD|", "RUNNING", cutoff - 30L * day, false),
			new FactoryRetention.StepRow("7", "p|MASTER_LOAD|", "FAILED", cutoff + day, false),
			new FactoryRetention.StepRow("8", "p|MASTER_LOAD|", "FAILED", 0L, false)), cutoff);
		FactoryRetentionTest.check("an older DONE row superseded by a later DONE is purged", purged.contains("1"));
		FactoryRetentionTest.check("the latest DONE of its key is kept even when old", !purged.contains("2"));
		FactoryRetentionTest.check("an old FAILED row is purged", purged.contains("3"));
		FactoryRetentionTest.check("a creation proof is kept whatever its age", !purged.contains("4"));
		FactoryRetentionTest.check("the latest DONE of the proof's key is kept", !purged.contains("5"));
		FactoryRetentionTest.check("a RUNNING row is never purged", !purged.contains("6"));
		FactoryRetentionTest.check("a recent row is kept", !purged.contains("7"));
		FactoryRetentionTest.check("a row without a date is kept", !purged.contains("8"));
		FactoryRetentionTest.check("exactly two rows purged", purged.size() == 2);

		FactoryRetentionTest.check("dataspace created is a proof", FactoryRetention.creationProof("{\"dataspace\":{\"created\":true}}"));
		FactoryRetentionTest.check("dataset created is a proof", FactoryRetention.creationProof("{\"dataset\":{\"created\":true,\"name\":\"x\"}}"));
		FactoryRetentionTest.check("created false is no proof", !FactoryRetention.creationProof("{\"dataspace\":{\"created\":false}}"));
		FactoryRetentionTest.check("no detail is no proof", !FactoryRetention.creationProof(null));
		FactoryRetentionTest.check("unreadable detail mentioning created is kept as a proof", FactoryRetention.creationProof("{\"created\":tr"));

		// audit F-36 (wave 5): the streaming scan gives the same verdict, and reads the creation proof only for a row it would otherwise delete
		final java.util.List<String> asked = new java.util.ArrayList<String>();
		final FactoryRetention.StepScan scan = new FactoryRetention.StepScan(cutoff);
		scan.offer("5", "p|STAGING_DATASET|", "DONE", cutoff - 30L * day);
		scan.offer("4", "p|STAGING_DATASET|", "DONE", cutoff - 60L * day);
		scan.offer("1", "p|LANDING_MODEL|a", "DONE", cutoff - 50L * day);
		scan.offer("2", "p|LANDING_MODEL|a", "DONE", cutoff - 10L * day);
		scan.offer("3", "p|LANDING_MODEL|a", "FAILED", cutoff - 5L * day);
		scan.offer("6", "p|MASTER_LOAD|", "RUNNING", cutoff - 30L * day);
		scan.offer("7", "p|MASTER_LOAD|", "FAILED", cutoff + day);
		scan.offer("8", "p|MASTER_LOAD|", "FAILED", 0L);
		scan.offer("9", "p|MASTER_LOAD|", "DONE", cutoff + 2L * day); // recent: never a candidate, but it is the latest DONE of its key
		final Set<String> streamed = scan.toPurge(new java.util.function.Predicate<String>()
		{
			@Override
			public boolean test(final String pId)
			{
				asked.add(pId);
				return "4".equals(pId); // row 4 is the one that carries a creation proof
			}
		});
		FactoryRetentionTest.check("streamed verdict: " + streamed, streamed.size() == 2 && streamed.contains("1") && streamed.contains("3"));
		FactoryRetentionTest.check("the detail is looked at for the rows that would be deleted only: " + asked, asked.size() == 3 && asked.contains("1") && asked.contains("3") && asked.contains("4"));
		final FactoryRetention.StepScan empty = new FactoryRetention.StepScan(cutoff);
		empty.offer("10", "p|X|", "DONE", cutoff + day);
		FactoryRetentionTest.check("nothing old: nothing to purge and the proof is never asked", empty.toPurge(new java.util.function.Predicate<String>()
		{
			@Override
			public boolean test(final String pId)
			{
				throw new AssertionError("the proof of a row that is not a candidate must not be read");
			}
		}).isEmpty());
		// audit F-38 (wave 10): an acknowledged reject ages out like a resolved one; an open one has no predicate that reaches it
		FactoryRetentionTest.check("the reject purge reaches resolved and acknowledged rows", FactoryRetention.REJECT_PURGE_PREDICATE.contains("./resolved=true()")
			&& FactoryRetention.REJECT_PURGE_PREDICATE.contains("./acknowledged=true()") && FactoryRetention.REJECT_PURGE_PREDICATE.contains(" or "));
		FactoryRetentionTest.check("and never names an open row", !FactoryRetention.REJECT_PURGE_PREDICATE.contains("not("));
		FactoryRetentionTest.agentProposalsAndDrifts(day, now, cutoff);
		System.out.println("FactoryRetentionTest OK");
	}

	/** Audit F3-10 / F3-103: decided proposals and drifts age out like a run; a line waiting for a decision is never purged and keeps its run. */
	private static void agentProposalsAndDrifts(final long pDay, final long pNow, final long pCutoff)
	{
		final long old = pCutoff - 10L * pDay;
		final long recent = pCutoff + 10L * pDay;
		final java.util.List<FactoryRetention.ProposalRow> rows = Arrays.asList(
			new FactoryRetention.ProposalRow("1", "runA", "APPLIED", true, old),
			new FactoryRetention.ProposalRow("2", "runA", "REJECTED", false, old),
			new FactoryRetention.ProposalRow("3", "runB", "APPLIED", true, old),
			new FactoryRetention.ProposalRow("4", "runB", "PROPOSED", false, 0L),
			new FactoryRetention.ProposalRow("5", "runC", "ACCEPTED", true, 0L),
			new FactoryRetention.ProposalRow("6", "runD", "ACCEPTED", false, recent),
			new FactoryRetention.ProposalRow("7", "runE", "FAILED", false, old),
			new FactoryRetention.ProposalRow("8", "runE", "REJECTED", false, recent));
		final java.util.Set<String> candidates = new java.util.HashSet<String>(Arrays.asList("runA", "runB", "runC", "runD"));
		final FactoryRetention.ProposalPurge purge = FactoryRetention.proposalPurge(rows, candidates, pCutoff);
		FactoryRetentionTest.check("the decided lines of a purged run go with it: " + purge.proposals, purge.proposals.contains("1") && purge.proposals.contains("2"));
		FactoryRetentionTest.check("a run with a PROPOSED line is kept: " + purge.runsKept, purge.runsKept.contains("runB") && !purge.runsKept.contains("runA"));
		FactoryRetentionTest.check("an ACCEPTED line the agent has yet to apply keeps its run", purge.runsKept.contains("runC"));
		FactoryRetentionTest.check("a pending line is never purged", !purge.proposals.contains("4") && !purge.proposals.contains("5"));
		FactoryRetentionTest.check("an old decided line of a kept run goes alone", purge.proposals.contains("3"));
		FactoryRetentionTest.check("a run bound to be purged takes its recent decided line (the foreign key)", purge.proposals.contains("6"));
		FactoryRetentionTest.check("an old decided line of a run that stays goes by its own age", purge.proposals.contains("7") && !purge.proposals.contains("8"));
		FactoryRetentionTest.check("exactly the expected lines: " + purge.proposals, purge.proposals.size() == 5);
		FactoryRetentionTest.check("PROPOSED is pending", FactoryRetention.proposalPending("PROPOSED", false, false));
		FactoryRetentionTest.check("ACCEPTED by hand, to apply, is pending", FactoryRetention.proposalPending("ACCEPTED", true, false));
		FactoryRetentionTest.check("ACCEPTED and applied is not", !FactoryRetention.proposalPending("ACCEPTED", true, true));
		FactoryRetentionTest.check("a suggestion ACCEPTED is decided", !FactoryRetention.proposalPending("ACCEPTED", false, false));

		FactoryRetentionTest.check("an old applied drift is purged", FactoryRetention.driftPurgeable("APPLIED", old, old, old, pCutoff));
		FactoryRetentionTest.check("an old rejected drift is purged on its decision date", FactoryRetention.driftPurgeable("REJECTED", 0L, old, recent, pCutoff));
		FactoryRetentionTest.check("a recent decided drift stays", !FactoryRetention.driftPurgeable("APPLIED", recent, old, old, pCutoff));
		FactoryRetentionTest.check("a drift waiting for a decision is never purged", !FactoryRetention.driftPurgeable("PROPOSED", 0L, 0L, old, pCutoff));
		FactoryRetentionTest.check("an accepted drift not yet applied is never purged", !FactoryRetention.driftPurgeable("ACCEPTED", 0L, old, old, pCutoff));
		FactoryRetentionTest.check("a drift without any date stays", !FactoryRetention.driftPurgeable("REJECTED", 0L, 0L, 0L, pCutoff));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
