package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/** JUnit-free: run the main, a failure throws. Landing leftovers (2026-10-17): the planning job proposes a rule key where the source gave no profile. */
public final class KeyPrimeRunnerTest
{
	private static KeyPrimeRunner.Candidate c(final String pId, final boolean pTicked, final boolean pProposed, final boolean pKey, final boolean pProfile)
	{
		return new KeyPrimeRunner.Candidate(pId, "T" + pId, pTicked, pProposed, pKey, pProfile);
	}

	public static void main(final String[] pArgs)
	{
		final KeyStudyReport.Proposal rule = new KeyStudyReport.Proposal(Arrays.asList("customerId"), 60, "only column named like an identifier", KeyStudyReport.ORIGIN_RULE);

		// selection: only proposed tables with no key and no profile; ticked first
		final List<KeyPrimeRunner.Candidate> all = new ArrayList<KeyPrimeRunner.Candidate>();
		all.add(c("1", false, true, false, false));
		all.add(c("2", true, true, false, false));
		all.add(c("3", true, true, true, false)); // has a key
		all.add(c("4", true, true, false, true)); // profiled
		all.add(c("5", true, false, false, false)); // generated
		final List<KeyPrimeRunner.Candidate> picked = KeyPrimeRunner.select(all);
		check("selection", picked.size() == 2 && "2".equals(picked.get(0).id) && "1".equals(picked.get(1).id));

		// a rule proposal is applied as the study returned it (origin RULE, 60); a table with no proposal is left alone; the study is called once per table
		final List<String> studied = new ArrayList<String>();
		final List<String> applied = new ArrayList<String>();
		final List<String> notes = new ArrayList<String>();
		KeyPrimeRunner.Outcome out = KeyPrimeRunner.run(all, new KeyPrimeRunner.Study()
		{
			@Override
			public KeyStudyReport.Proposal study(final String pId)
			{
				studied.add(pId);
				return "2".equals(pId) ? rule : null;
			}
		}, new KeyPrimeRunner.Apply()
		{
			@Override
			public void apply(final String pId, final KeyStudyReport.Proposal pProposal)
			{
				applied.add(pId + ":" + pProposal.columnsCsv() + ":" + pProposal.origin + ":" + pProposal.confidence);
			}
		}, noteTo(notes), 1000L, null);
		check("studied both", studied.equals(Arrays.asList("2", "1")) && out.studied == 2);
		check("rule recorded", applied.equals(Arrays.asList("2:customerId:RULE:60")) && out.proposed == 1 && notes.isEmpty());

		// a failed read is noted, never thrown, and the next table is still studied
		applied.clear();
		out = KeyPrimeRunner.run(all, new KeyPrimeRunner.Study()
		{
			@Override
			public KeyStudyReport.Proposal study(final String pId) throws Exception
			{
				if ("2".equals(pId))
				{
					throw new Exception("connector refused");
				}
				return rule;
			}
		}, new KeyPrimeRunner.Apply()
		{
			@Override
			public void apply(final String pId, final KeyStudyReport.Proposal pProposal)
			{
				applied.add(pId);
			}
		}, noteTo(notes), 1000L, null);
		check("failure noted", out.failed == 1 && notes.size() == 1 && notes.get(0).startsWith("2:Key not measured: connector refused"));
		check("next table still done", applied.equals(Arrays.asList("1")));

		// an apply that fails is noted too
		notes.clear();
		out = KeyPrimeRunner.run(Arrays.asList(c("9", true, true, false, false)), new KeyPrimeRunner.Study()
		{
			@Override
			public KeyStudyReport.Proposal study(final String pId)
			{
				return rule;
			}
		}, new KeyPrimeRunner.Apply()
		{
			@Override
			public void apply(final String pId, final KeyStudyReport.Proposal pProposal) throws Exception
			{
				throw new Exception("write refused");
			}
		}, noteTo(notes), 1000L, null);
		check("apply failure noted", out.failed == 1 && notes.size() == 1);

		// the bound: 20 tables at most, ticked first
		final List<KeyPrimeRunner.Candidate> many = new ArrayList<KeyPrimeRunner.Candidate>();
		for (int i = 0; i < 30; i++)
		{
			many.add(c(String.valueOf(100 + i), i >= 25, true, false, false));
		}
		final List<KeyPrimeRunner.Candidate> bounded = KeyPrimeRunner.select(many);
		check("at most 20", bounded.size() == KeyPrimeRunner.MAX_TABLES && bounded.get(0).ticked && bounded.get(4).ticked && !bounded.get(5).ticked);

		// the time budget: a read that does not answer is noted and the next table goes on
		notes.clear();
		out = KeyPrimeRunner.run(Arrays.asList(c("a", true, true, false, false), c("b", false, true, false, false)), new KeyPrimeRunner.Study()
		{
			@Override
			public KeyStudyReport.Proposal study(final String pId) throws Exception
			{
				if ("Ta".equals("T" + pId))
				{
					Thread.sleep(5000L);
				}
				return null;
			}
		}, new KeyPrimeRunner.Apply()
		{
			@Override
			public void apply(final String pId, final KeyStudyReport.Proposal pProposal)
			{
			}
		}, noteTo(notes), 100L, null);
		check("timeout noted", out.failed == 1 && notes.size() == 1 && notes.get(0).contains("not read within"));

		// cancelled job: nothing studied
		out = KeyPrimeRunner.run(all, new KeyPrimeRunner.Study()
		{
			@Override
			public KeyStudyReport.Proposal study(final String pId)
			{
				throw new AssertionError("must not run");
			}
		}, null, noteTo(notes), 1000L, new Callable<Boolean>()
		{
			@Override
			public Boolean call()
			{
				return Boolean.TRUE;
			}
		});
		check("cancelled", out.studied == 0 && out.skipped == 2);
		System.out.println("KeyPrimeRunnerTest OK");
	}

	private static KeyPrimeRunner.Note noteTo(final List<String> pNotes)
	{
		return new KeyPrimeRunner.Note()
		{
			@Override
			public void note(final String pId, final String pText)
			{
				pNotes.add(pId + ":" + pText);
			}
		};
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
