package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * An {@code AgentRun} row is written RUNNING when a language-model conversation starts and ended when it finishes; a server stop in between left it RUNNING for ever,
 * and the history purge ({@code FactoryRetention}) keeps every RUNNING row. At module start every such row belongs to a conversation of the previous server run, which
 * is gone: it is marked {@code INTERRUPTED}, with an end date, like the pipeline steps and the background jobs the module already recovers.
 */
final class AgentRunRecovery
{
	static final String STATUS_INTERRUPTED = "INTERRUPTED";
	private static final String NOTE = "Interrupted: the server restarted during this run.";

	private AgentRunRecovery()
	{
	}

	/** The outcome text of a run that was interrupted: the note, then what the row already said, within {@code pMax} characters. */
	static String interruptedOutcome(final String pPrevious, final int pMax)
	{
		final String previous = pPrevious == null ? "" : pPrevious.trim();
		return TextLimits.truncate(previous.isEmpty() ? AgentRunRecovery.NOTE : AgentRunRecovery.NOTE + " Last outcome: " + previous, pMax);
	}

	/** Every {@code AgentRun} row still RUNNING becomes INTERRUPTED. Returns how many; never throws (a failure is logged and counts 0). */
	static int interruptOrphans(final Session pSession, final Adaptation pFactory)
	{
		final AdaptationTable runs = pFactory.getTable(FactoryPaths.AGENT_RUN);
		final List<String> ids = new ArrayList<String>();
		final RequestResult rows = runs.createRequestResult("./status='RUNNING'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				ids.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		if (ids.isEmpty())
		{
			return 0;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (final String id : ids)
				{
					final Adaptation row = runs.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(id));
					if (row == null)
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(AgentRunRecovery.STATUS_INTERRUPTED, FactoryPaths.AgentRun.STATUS);
					value.setValue(new Date(), FactoryPaths.AgentRun.ENDED_AT);
					value.setValue(AgentRunRecovery.interruptedOutcome(row.getString(FactoryPaths.AgentRun.OUTCOME), AgentRuntime.OUTCOME_MAX_LENGTH),
						FactoryPaths.AgentRun.OUTCOME);
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not mark orphan RUNNING agent runs INTERRUPTED: " + result.getExceptionFullMessage(Locale.ENGLISH));
			return 0;
		}
		return ids.size();
	}
}
