package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Landing leftovers (2026-10-17): the rule-only key study run in the background job that planned a table, for the tables the planner could
 * not propose a key for because the source gave no profile statistics (a sample file never has any: the naming rule {@code SourceKeyRule}
 * needs a fill rate and a uniqueness to propose {@code customerId}). Pure: the reads and writes are given as {@link Study}, {@link Apply} and
 * {@link Note}, so the selection, the bound and the failure handling are tested without EBX. Never the language model: the study handed in is
 * the evidence-only one, and nothing here can ask for another.
 */
public final class KeyPrimeRunner
{
	/** Tables studied by one job, ticked tables first. */
	public static final int MAX_TABLES = 20;
	/** Time given to the read of one table's 25 rows; a table that does not answer is noted and left. */
	public static final long PER_TABLE_MILLIS = 20000L;

	private KeyPrimeRunner()
	{
	}

	/** What the runner needs to know of a landing table. */
	public static final class Candidate
	{
		public final String id;
		public final String name;
		public final boolean ticked;
		public final boolean proposed;
		public final boolean hasKey;
		public final boolean hasProfile;

		public Candidate(final String pId, final String pName, final boolean pTicked, final boolean pProposed, final boolean pHasKey, final boolean pHasProfile)
		{
			this.id = pId;
			this.name = pName;
			this.ticked = pTicked;
			this.proposed = pProposed;
			this.hasKey = pHasKey;
			this.hasProfile = pHasProfile;
		}
	}

	/** The rule-only study of one table: the proposal, or null when the rule found none. */
	public interface Study
	{
		KeyStudyReport.Proposal study(String pTableId) throws Exception;
	}

	/** Records a proposal exactly as the study's rule outcome is recorded when the person applies it. */
	public interface Apply
	{
		void apply(String pTableId, KeyStudyReport.Proposal pProposal) throws Exception;
	}

	/** Writes the failure on the table's message. */
	public interface Note
	{
		void note(String pTableId, String pText);
	}

	public static final class Outcome
	{
		public int studied;
		public int proposed;
		public int failed;
		/** Tables left because the bound was reached. */
		public int skipped;
	}

	/** The tables to study: still proposed, no key, no profile statistics; ticked first, then by name; at most {@link #MAX_TABLES}. */
	public static List<Candidate> select(final List<Candidate> pAll)
	{
		final List<Candidate> eligible = new ArrayList<Candidate>();
		for (final Candidate candidate : pAll)
		{
			if (candidate.proposed && !candidate.hasKey && !candidate.hasProfile)
			{
				eligible.add(candidate);
			}
		}
		Collections.sort(eligible, new Comparator<Candidate>()
		{
			@Override
			public int compare(final Candidate pLeft, final Candidate pRight)
			{
				if (pLeft.ticked != pRight.ticked)
				{
					return pLeft.ticked ? -1 : 1;
				}
				return String.valueOf(pLeft.name).compareToIgnoreCase(String.valueOf(pRight.name));
			}
		});
		return eligible.size() > KeyPrimeRunner.MAX_TABLES ? new ArrayList<Candidate>(eligible.subList(0, KeyPrimeRunner.MAX_TABLES)) : eligible;
	}

	/**
	 * Studies the selected tables one after another. A study that fails, times out or cannot be applied is noted on its table and never stops the
	 * others nor throws. {@code pCancelled} is checked between tables.
	 */
	public static Outcome run(final List<Candidate> pAll, final Study pStudy, final Apply pApply, final Note pNote, final long pPerTableMillis,
		final java.util.concurrent.Callable<Boolean> pCancelled)
	{
		final Outcome outcome = new Outcome();
		final List<Candidate> selected = KeyPrimeRunner.select(pAll);
		if (selected.isEmpty())
		{
			return outcome;
		}
		final ExecutorService pool = Executors.newSingleThreadExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(final Runnable pRunnable)
			{
				final Thread thread = new Thread(pRunnable, "ebx-mdm-factory-key-prime");
				thread.setDaemon(true);
				return thread;
			}
		});
		try
		{
			for (int i = 0; i < selected.size(); i++)
			{
				final Candidate candidate = selected.get(i);
				if (KeyPrimeRunner.cancelled(pCancelled))
				{
					outcome.skipped += selected.size() - i;
					break;
				}
				outcome.studied++;
				final Future<KeyStudyReport.Proposal> future = pool.submit(new Callable<KeyStudyReport.Proposal>()
				{
					@Override
					public KeyStudyReport.Proposal call() throws Exception
					{
						return pStudy.study(candidate.id);
					}
				});
				try
				{
					final KeyStudyReport.Proposal proposal = future.get(pPerTableMillis, TimeUnit.MILLISECONDS);
					if (proposal != null)
					{
						pApply.apply(candidate.id, proposal);
						outcome.proposed++;
					}
				}
				catch (final TimeoutException ex)
				{
					future.cancel(true);
					outcome.failed++;
					KeyPrimeRunner.noteText(pNote, candidate, KeyTexts.notMeasuredTimeout(pPerTableMillis / 1000));
				}
				catch (final InterruptedException ex)
				{
					future.cancel(true);
					Thread.currentThread().interrupt();
					outcome.skipped += selected.size() - i - 1;
					outcome.failed++;
					break;
				}
				catch (final ExecutionException ex)
				{
					outcome.failed++;
					final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
					KeyPrimeRunner.note(pNote, candidate, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
				}
				catch (final Exception ex)
				{
					// the apply step
					outcome.failed++;
					KeyPrimeRunner.note(pNote, candidate, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
				}
			}
		}
		finally
		{
			pool.shutdownNow();
		}
		return outcome;
	}

	private static boolean cancelled(final Callable<Boolean> pCancelled)
	{
		try
		{
			return pCancelled != null && Boolean.TRUE.equals(pCancelled.call());
		}
		catch (final Exception ex)
		{
			return false;
		}
	}

	private static void note(final Note pNote, final Candidate pCandidate, final String pReason)
	{
		KeyPrimeRunner.noteText(pNote, pCandidate, KeyTexts.notMeasured(TextLimits.truncate(pReason == null ? "" : pReason.trim(), 300)));
	}

	/** Key rationale i18n (2026-10-17): the note is the English sentence followed by its code, so a wizard screen words it in the viewer's language. */
	private static void noteText(final Note pNote, final Candidate pCandidate, final KeyTexts.Text pText)
	{
		try
		{
			pNote.note(pCandidate.id, pText.stored());
		}
		catch (final RuntimeException ex)
		{
			// noting is best effort: it must never fail the source job
		}
	}
}
