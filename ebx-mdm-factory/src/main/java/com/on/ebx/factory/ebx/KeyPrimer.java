package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.KeyPrimeRunner;
import com.on.ebx.factory.landing.KeyStudyReport;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.schema.Path;

/**
 * Landing leftovers (2026-10-17): after a job planned (or re-planned) the landing tables of an application, proposes a key for those the planner
 * could not, because the source gave no profile statistics (a sample file never does, so {@code SourceKeyRule} could not see that {@code customerId}
 * has no empty value and only distinct values). It runs the existing rule-only key study ({@link KeyStudy#study(Session, Adaptation, String, boolean,
 * boolean)} with the language model off) on the 25-row sample, and records its proposal exactly as the "Apply" of that study does (origin RULE,
 * confidence 60, the rule's rationale). Only from a background job: the wizard's display never calls Connectors (wave 11). Bounded by {@link
 * KeyPrimeRunner} (20 tables, ticked first, a time budget per table); a failed read is noted on the table's message and never fails the job.
 */
public final class KeyPrimer
{
	private KeyPrimer()
	{
	}

	/** One attempt whose failure is reported, not thrown: {@code null} when it worked, the cause text when it did not (F2-15, pure so the tests drive it). */
	interface Attempt
	{
		String run();
	}

	/**
	 * F2-15: runs one attempt and, when it failed (a text answered, or a {@link RuntimeException}), logs the cause and counts it on {@code pOutcome.failed}.
	 * Nothing is swallowed: the caller reads the outcome. @return true when the attempt worked.
	 */
	static boolean record(final String pWhat, final Attempt pAttempt, final KeyPrimeRunner.Outcome pOutcome)
	{
		String failure;
		try
		{
			failure = pAttempt.run();
		}
		catch (final RuntimeException ex)
		{
			failure = ex.getClass().getName() + ": " + ex.getMessage();
		}
		if (failure == null)
		{
			return true;
		}
		pOutcome.failed++;
		LoggingCategory.getKernel().warn("[mdm-factory] key priming: " + pWhat + " failed: " + failure);
		return false;
	}

	/** The tables of one application that the pass would look at, with what the rule needs to know of each (separated from the reads for the tests of the selection). */
	public static KeyPrimeRunner.Outcome prime(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final FactoryJobs.Handle pHandle)
	{
		// the notes that could not be written (F2-15): counted on the outcome the caller gets, never swallowed
		final KeyPrimeRunner.Outcome noteFailures = new KeyPrimeRunner.Outcome();
		final KeyPrimeRunner.Outcome[] holder = new KeyPrimeRunner.Outcome[1];
		final boolean ran = KeyPrimer.record("the pass on source " + pProjectSourceId, new Attempt()
		{
			@Override
			public String run()
			{
				holder[0] = KeyPrimer.runPass(pSession, pFactory, pProjectSourceId, pHandle, noteFailures);
				return null;
			}
		}, noteFailures);
		final KeyPrimeRunner.Outcome outcome = ran ? holder[0] : new KeyPrimeRunner.Outcome();
		// the pass is a courtesy of the planning job and never throws into it; its failures are in the outcome (failed) and in kernel.log
		outcome.failed += noteFailures.failed;
		return outcome;
	}

	private static KeyPrimeRunner.Outcome runPass(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final FactoryJobs.Handle pHandle,
		final KeyPrimeRunner.Outcome pNoteFailures)
	{
		final List<KeyPrimeRunner.Candidate> candidates = KeyPrimer.candidates(pFactory, pProjectSourceId);
		return KeyPrimeRunner.run(candidates, new KeyPrimeRunner.Study()
		{
			@Override
			public KeyStudyReport.Proposal study(final String pTableId) throws Exception
			{
				// no language model, whatever the settings: allowLlm false
				final KeyStudyReport report = KeyStudy.study(pSession, pFactory, pTableId, false, false);
				if (report.proposal == null && report.sampleError != null && !report.sampleError.trim().isEmpty())
				{
					throw new Exception(report.sampleError.trim());
				}
				return report.proposal;
			}
		}, new KeyPrimeRunner.Apply()
		{
			@Override
			public void apply(final String pTableId, final KeyStudyReport.Proposal pProposal) throws Exception
			{
				WizardData.writeTableKey(pSession, pFactory, pTableId, pProposal.columnsCsv(), pProposal.origin, pProposal.confidence, pProposal.rationale, false);
			}
		}, new KeyPrimeRunner.Note()
		{
			@Override
			public void note(final String pTableId, final String pText)
			{
				KeyPrimer.record("the message of landing table " + pTableId, new Attempt()
				{
					@Override
					public String run()
					{
						return KeyPrimer.note(pSession, pFactory, pTableId, pText);
					}
				}, pNoteFailures);
			}
		}, KeyPrimeRunner.PER_TABLE_MILLIS, new Callable<Boolean>()
		{
			@Override
			public Boolean call()
			{
				return Boolean.valueOf(pHandle != null && pHandle.cancelled());
			}
		});
	}

	private static List<KeyPrimeRunner.Candidate> candidates(final Adaptation pFactory, final String pProjectSourceId)
	{
		final List<KeyPrimeRunner.Candidate> out = new ArrayList<KeyPrimeRunner.Candidate>();
		final List<Adaptation> tables = new ArrayList<Adaptation>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_TABLE).createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				tables.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		for (final Adaptation table : tables)
		{
			final String id = table.getOccurrencePrimaryKey().format();
			final boolean proposed = "PROPOSED".equals(table.getString(FactoryPaths.LandingTable.STATUS));
			if (!proposed)
			{
				continue;
			}
			boolean hasKey = false;
			boolean hasProfile = false;
			final RequestResult columns = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(id));
			try
			{
				for (Adaptation column = columns.nextAdaptation(); column != null; column = columns.nextAdaptation())
				{
					hasKey |= Boolean.TRUE.equals(column.get(FactoryPaths.LandingColumn.SOURCE_KEY));
					hasProfile |= column.get(FactoryPaths.LandingColumn.FILL_RATE) != null || column.get(FactoryPaths.LandingColumn.DISTINCT_COUNT) != null;
				}
			}
			finally
			{
				columns.close();
			}
			final Object selected = table.get(FactoryPaths.LandingTable.SELECTED);
			out.add(new KeyPrimeRunner.Candidate(id, table.getString(FactoryPaths.LandingTable.TABLE_NAME), !Boolean.FALSE.equals(selected), true, hasKey, hasProfile));
		}
		return out;
	}

	/** @return null when the note is written (or there is nothing to write), else why it was not (the procedure's own failure text). */
	private static String note(final Session pSession, final Adaptation pFactory, final String pTableId, final String pText)
	{
		final Adaptation table = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTableId));
		if (table == null)
		{
			return null;
		}
		final String current = table.getString(FactoryPaths.LandingTable.MESSAGE);
		final Path message = FactoryPaths.LandingTable.MESSAGE;
		if (current != null && current.contains(pText))
		{
			return null;
		}
		final String text = TextLimits.truncate((current == null || current.isEmpty() ? "" : current + " ") + pText, TextLimits.MESSAGE_MAX_LENGTH);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final com.orchestranetworks.service.ValueContextForUpdate value = pContext.getContext(table.getAdaptationName());
				value.setValue(text, message);
				pContext.doModifyContent(table, value);
			}
		});
		return result.hasFailed() ? result.getExceptionFullMessage(Locale.ENGLISH) : null;
	}
}
