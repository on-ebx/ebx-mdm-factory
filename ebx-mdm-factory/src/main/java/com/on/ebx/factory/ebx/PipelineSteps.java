package com.on.ebx.factory.ebx;

import java.util.Date;
import java.util.Locale;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.DetailFit;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.master.StepAmendment;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * {@code PipelineStep} bookkeeping shared by the generators of this package: a step already {@code DONE}
 * for the same project and scope is skipped (so a run interrupted or failed partway resumes instead of
 * restarting), and {@link #begin}/{@link #end} are each written in their own procedure so the record of
 * what ran survives whatever the step's own work does next (a rollback of a failed work procedure never
 * takes the record of that failure down with it). Both {@code LandingGenerator} ({@code LANDING_MODEL},
 * {@code LANDING_DATASET}) and {@code LandingFlows} ({@code LANDING_FLOWS}) call this; behaviour is
 * unchanged from when {@code LandingGenerator} carried its own private copy of it.
 */
final class PipelineSteps
{
	private PipelineSteps()
	{
	}

	static boolean isDone(final Adaptation pFactory, final String pProjectId, final String pStep, final String pScope)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP)
			.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(pStep) + " and "
				+ PipelineSteps.scopeEquals("./scope", pScope) + " and ./status=" + CatalogReader.quote("DONE"));
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * The XPath fragment matching {@code pFieldPath} against {@code pScope} - never {@code pFieldPath + "=" +
	 * CatalogReader.quote(pScope)} for an empty/null scope. Found the hard way, 2026-09-20: EBX stores an empty
	 * string as NULL, so {@code ./scope=''} never matches a project-scope step (an empty {@code scope} is how
	 * every generator here marks "the whole project", not per-source or per-entity) - {@link #isDone} answered
	 * {@code false} for EVERY project-scope step, always, which one live call surfaced as "Project Companies has
	 * no loaded staging entity yet" although {@code STAGING_LOAD} genuinely was {@code DONE}. Read live the same
	 * day, `mcp__data-access__get_xpath_syntax`, "Null / empty checks": <i>{@code osd:is-null(field)} - Field is
	 * null. Example: {@code osd:is-null(./Main/Description)}</i> - the correct predicate for "this optional field
	 * was never set (or was cleared to blank)", which is exactly what an empty {@code scope} means here.
	 * <p>
	 * Pure (a plain string transform, no EBX class), so it is unit-tested directly - empty, null, a plain value,
	 * and a value containing a quote (through {@link CatalogReader#quote}'s own doubling) - without needing a
	 * running instance to exercise the four cases.
	 */
	static String scopeEquals(final String pFieldPath, final String pScope)
	{
		return pScope == null || pScope.isEmpty() ? "osd:is-null(" + pFieldPath + ")" : pFieldPath + "=" + CatalogReader.quote(pScope);
	}

	/**
	 * The id of the most recently STARTED step of {@code pStep} for the project (any scope), or null when there is none - how the end of a load finds the step
	 * row of the load it just ran to say something on it. A scan of one project's rows of one step kind: only called on the rare path that has something to report.
	 */
	static String latestStepId(final Adaptation pFactory, final String pProjectId, final String pStep)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP)
			.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(pStep));
		try
		{
			String best = null;
			long bestAt = Long.MIN_VALUE;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object started = row.get(FactoryPaths.PipelineStep.STARTED_AT);
				final long at = started instanceof Date ? ((Date) started).getTime() : 0L;
				if (best == null || at >= bestAt)
				{
					best = row.getOccurrencePrimaryKey().format();
					bestAt = at;
				}
			}
			return best;
		}
		finally
		{
			rows.close();
		}
	}

	/** Written ahead of the work the step records: it must survive whatever the work does next. @return the new row's id */
	static String begin(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pStep, final String pScope) throws OperationException
	{
		final AdaptationTable steps = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
		final Actor actor = Actor.current(pSession);
		final String[] holder = new String[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(steps);
				value.setValue(pProjectId, FactoryPaths.PipelineStep.PROJECT);
				value.setValue(pStep, FactoryPaths.PipelineStep.STEP);
				value.setValue(pScope, FactoryPaths.PipelineStep.SCOPE);
				value.setValue("RUNNING", FactoryPaths.PipelineStep.STATUS);
				value.setValue(new Date(), FactoryPaths.PipelineStep.STARTED_AT);
				value.setValue(actor.userId, FactoryPaths.PipelineStep.STARTED_BY);
				value.setValue(actor.via, FactoryPaths.PipelineStep.VIA);
				holder[0] = pContext.doCreateOccurrence(value, steps).getOccurrencePrimaryKey().format();
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.stepStart", "Could not record the start of step " + pStep + " (" + pScope + "): " + result.getExceptionFullMessage(Locale.ENGLISH), pStep, pScope);
		}
		return holder[0];
	}

	/**
	 * Written after the work the step records is over, in its own procedure so a rollback of that work never
	 * takes this record down with it. Best-effort: a failure here is logged, not thrown, so a bookkeeping
	 * hiccup does not hide the step's actual outcome from the caller.
	 */
	static void end(final Session pSession, final Adaptation pFactory, final String pStepId, final boolean pOk, final String pMessage, final String pDetailJson)
	{
		PipelineSteps.endAs(pSession, pFactory, pStepId, pOk ? "DONE" : "FAILED", pMessage, pDetailJson);
	}

	/** F2-06: same as {@link #end}, for a step that finished but could not write part of its closing writes: status PARTIAL, neither DONE nor FAILED (free-text status, like STOPPED and SKIPPED). */
	static void endPartial(final Session pSession, final Adaptation pFactory, final String pStepId, final String pMessage, final String pDetailJson)
	{
		PipelineSteps.endAs(pSession, pFactory, pStepId, "PARTIAL", pMessage, pDetailJson);
	}

	/** Same as {@link #end}, for a run a person (or the time limit) stopped: status STOPPED, neither done nor failed. */
	static void endStopped(final Session pSession, final Adaptation pFactory, final String pStepId, final String pMessage, final String pDetailJson)
	{
		PipelineSteps.endAs(pSession, pFactory, pStepId, "STOPPED", pMessage, pDetailJson);
	}

	/**
	 * Fix lot (2026-09-26): same as {@link #end}, for an outcome that is neither a success nor a failure - the
	 * deduced-scan pass finding out this connection is an enrichment (looks up one record at a time, never
	 * lists) is an EXPECTED outcome, not an error, yet {@code end(pSession, ..., false, ...)} used to record it
	 * as {@code FAILED} (the only two choices {@code end} itself offers), which then read as a red, broken run
	 * both on this step and on the {@code FactoryJob} it belongs to. Status {@code SKIPPED}, same free-text-status
	 * convention {@link #endStopped} already established (this table's own {@code status} field is plain text,
	 * not a fixed XSD enumeration - a new ad hoc value costs nothing here).
	 */
	static void endSkipped(final Session pSession, final Adaptation pFactory, final String pStepId, final String pMessage, final String pDetailJson)
	{
		PipelineSteps.endAs(pSession, pFactory, pStepId, "SKIPPED", pMessage, pDetailJson);
	}

	/**
	 * Wave 2 (2026-09-30): rewrites ONLY the {@code detail} of a step row, leaving its status, dates and message alone - how the
	 * stored matching results of an already finished {@code MATCHING_RUN} row are refreshed after a decision. Never truncates
	 * (a cut JSON would not parse): a detail over {@link TextLimits#DETAIL_MAX_LENGTH} is refused (false). False too when the row
	 * is gone or the write failed (logged).
	 */
	static boolean updateDetail(final Session pSession, final Adaptation pFactory, final String pStepId, final String pDetailJson)
	{
		if (pDetailJson != null && pDetailJson.length() > TextLimits.DETAIL_MAX_LENGTH)
		{
			return false;
		}
		final AdaptationTable steps = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
		final boolean[] written = new boolean[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = steps.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pStepId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pDetailJson, FactoryPaths.PipelineStep.DETAIL);
				pContext.doModifyContent(row, value);
				written[0] = true;
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not update the detail of step " + pStepId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
			return false;
		}
		return written[0];
	}

	/**
	 * Lot MP (2026-10-04): adds to a step that ALREADY ENDED what happened after its work - the matching resumed and caught up once the master load had ended its
	 * {@code MASTER_LOAD} step. Status, dates and the rest of the detail are left alone ({@link StepAmendment}); {@code pMessagePrefix} (a warning) goes first,
	 * {@code pMessageSuffix} last. Best-effort like {@link #end}: false when the row is gone or the write failed (logged), never an exception.
	 */
	static boolean amendEnded(final Session pSession, final Adaptation pFactory, final String pStepId, final String pMessagePrefix, final String pMessageSuffix,
		final String pDetailKey, final com.fasterxml.jackson.databind.JsonNode pDetailValue)
	{
		final AdaptationTable steps = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
		final boolean[] written = new boolean[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = steps.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pStepId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(TextLimits.truncate(StepAmendment.composeMessage(pMessagePrefix, row.getString(FactoryPaths.PipelineStep.MESSAGE), pMessageSuffix,
					TextLimits.MESSAGE_MAX_LENGTH), TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.PipelineStep.MESSAGE);
				value.setValue(DetailFit.fit(StepAmendment.mergeDetail(row.getString(FactoryPaths.PipelineStep.DETAIL), pDetailKey, pDetailValue),
					TextLimits.DETAIL_MAX_LENGTH), FactoryPaths.PipelineStep.DETAIL);
				pContext.doModifyContent(row, value);
				written[0] = true;
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not amend the ended step " + pStepId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
			return false;
		}
		return written[0];
	}

	private static void endAs(final Session pSession, final Adaptation pFactory, final String pStepId, final String pStatus, final String pMessage,
		final String pDetailJson)
	{
		final AdaptationTable steps = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
		final String message = TextLimits.truncate(pMessage, TextLimits.MESSAGE_MAX_LENGTH);
		// audit F-29: a detail over the cap stays valid JSON with its ownership keys (DetailFit) - the cleanup readers take unparsable as "no evidence"
		final String detail = DetailFit.fit(pDetailJson, TextLimits.DETAIL_MAX_LENGTH);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = steps.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pStepId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pStatus, FactoryPaths.PipelineStep.STATUS);
				value.setValue(new Date(), FactoryPaths.PipelineStep.ENDED_AT);
				value.setValue(message, FactoryPaths.PipelineStep.MESSAGE);
				value.setValue(detail, FactoryPaths.PipelineStep.DETAIL);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not record the end of step " + pStepId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}
}
