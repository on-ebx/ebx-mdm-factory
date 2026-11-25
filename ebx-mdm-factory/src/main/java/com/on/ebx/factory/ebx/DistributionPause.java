package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.DistributionResumeReport;
import com.onwbp.adaptation.Adaptation;
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
 * Distribution lot 1 (engine, SPEC-distribution.md "Real time"): "While the pipeline runs a FULL reload
 * (staging scope all, master load, matching run) the project's realtime targets are PAUSED; at the end a
 * catch-up send with Connectors OutboundScope.CHANGED runs, then they resume." {@link #pause} is called at
 * the head of {@code StagingLoader#load} (only when {@code pAllRows} is true), {@code MasterLoader#load} and
 * {@code MatchingConfigurator#run}; {@link #resume} in a {@code finally} around the same call, so a target
 * is never left {@code PAUSED} by a reload that itself failed.
 * <p>
 * A target already {@code DRAFT}/{@code WAITING}/{@code DISABLED} (never armed, or turned off by a person)
 * is left untouched - only a target that was genuinely {@code ARMED} is paused, and {@link #resume} only
 * ever restores the ids {@link #pause} itself returned, never "every PAUSED target of the project" (a
 * target a person paused by hand, independently, must not be silently re-armed by an unrelated reload).
 * <p>
 * A stop of the server between {@link #pause} and {@link #resume} would leave the target PAUSED for ever, so each pause is written to the {@link
 * LoadPauseLedger} first and the row deleted once the target is ARMED again; {@link LoadPauseRecovery} re-arms a target whose row outlived its load.
 * <b>Not verified live</b>: no reload was actually run against a project with an armed REALTIME target
 * while writing this class. See {@code docs/DISTRIBUTION.md}.
 * <p>
 * Volume-operations lot 8b: {@link #setState} also keeps {@code DistributionControlRow} in step - a resume
 * (PAUSED -&gt; ARMED) bumps it BEFORE the state commits, a pause (ARMED -&gt; PAUSED) refreshes it AFTER,
 * exactly the asymmetry that class' own Javadoc explains.
 */
public final class DistributionPause
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DistributionPause()
	{
	}

	/**
	 * Pauses every currently-ARMED REALTIME target of the project; returns their ids, to hand back to {@link #resume} once the reload is over. Best-effort: never
	 * throws - a bookkeeping failure here must not block the reload itself. A target whose pause could not be recorded in the {@link LoadPauseLedger} is NOT
	 * paused (a pause nobody could recover after a stop is worse than a target that keeps sending during the load) and is said in the log.
	 */
	public static List<String> pause(final Session pSession, final Adaptation pFactory, final String pProjectId)
	{
		return DistributionPause.pause(pSession, pFactory, pProjectId, null);
	}

	static List<String> pause(final Session pSession, final Adaptation pFactory, final String pProjectId, final LoadPauseLedger.Owner pOwner)
	{
		final List<String> candidates = new ArrayList<String>();
		final List<String> pausedIds = new ArrayList<String>();
		try
		{
			final RequestResult rows = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./mode="
				+ CatalogReader.quote(FactoryPaths.DistributionTarget.MODE_REALTIME) + " and ./state=" + CatalogReader.quote(FactoryPaths.DistributionTarget.STATE_ARMED));
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					candidates.add(row.getOccurrencePrimaryKey().format());
				}
			}
			finally
			{
				rows.close();
			}
			for (final String id : candidates)
			{
				if (pOwner != null)
				{
					try
					{
						LoadPauseLedger.claim(pSession, pFactory, pOwner, FactoryPaths.LoadPause.KIND_DISTRIBUTION_TARGET, id);
					}
					catch (final OperationException ex)
					{
						LoggingCategory.getKernel().error("[mdm-factory] distribution target " + id + " of project " + pProjectId + " is NOT paused for the load (the pause could not be "
							+ "recorded, so it could not be lifted after a stop): " + ex.getMessage());
						continue;
					}
				}
				pausedIds.add(id);
				DistributionPause.setState(pSession, pFactory, id, FactoryPaths.DistributionTarget.STATE_PAUSED);
			}
		}
		catch (final OperationException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not pause the realtime distribution targets of project " + pProjectId + ": " + ex.getMessage());
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not pause the realtime distribution targets of project " + pProjectId + ": " + ex);
		}
		return pausedIds;
	}

	/** Sets every id back to {@code ARMED}, then a Connectors {@code changed} catch-up send for each. Never throws: a failure is recorded in the returned detail (read it with {@code DistributionResumeReport}) and on the target's own {@code lastStatus}/{@code lastMessage}, but a reload's own outcome must not be masked by a distribution hiccup. */
	public static ObjectNode resume(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<String> pPausedTargetIds)
	{
		return DistributionPause.resume(pSession, pFactory, pProjectCode, pPausedTargetIds, null);
	}

	static ObjectNode resume(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<String> pPausedTargetIds,
		final LoadPauseLedger.Owner pOwner)
	{
		final ObjectNode out = DistributionPause.MAPPER.createObjectNode();
		final com.fasterxml.jackson.databind.node.ArrayNode targets = out.putArray("targets");
		if (pPausedTargetIds == null || pPausedTargetIds.isEmpty())
		{
			return out;
		}
		for (final String id : pPausedTargetIds)
		{
			final ObjectNode targetOut = targets.addObject();
			targetOut.put("target", id);
			try
			{
				DistributionPause.setState(pSession, pFactory, id, FactoryPaths.DistributionTarget.STATE_ARMED);
			}
			catch (final OperationException ex)
			{
				// still PAUSED, and its ledger row stays: the recovery re-arms it at the next start of the module
				targetOut.put("error", ex.getMessage());
				continue;
			}
			catch (final RuntimeException ex)
			{
				targetOut.put("error", String.valueOf(ex));
				continue;
			}
			// the pause is lifted: the row has done its job whatever the catch-up below does
			if (pOwner != null)
			{
				LoadPauseLedger.release(pSession, pFactory, pOwner, FactoryPaths.LoadPause.KIND_DISTRIBUTION_TARGET, id);
			}
			try
			{
				final ObjectNode sendResult = DistributionRunner.sendNow(pSession, pFactory, pProjectCode, id, "changed");
				targetOut.set("catchUp", sendResult);
			}
			catch (final OperationException ex)
			{
				DistributionPause.catchUpFailed(targetOut, ex.getMessage());
			}
			catch (final RuntimeException ex)
			{
				DistributionPause.catchUpFailed(targetOut, String.valueOf(ex));
			}
		}
		return out;
	}

	private static void catchUpFailed(final ObjectNode pTargetOut, final String pMessage)
	{
		final ObjectNode catchUp = pTargetOut.putObject("catchUp");
		catchUp.put("status", "FAILED");
		catchUp.put("message", pMessage);
	}

	/**
	 * {@link #resume} for the end of a load: the result is read, every failure goes to the log as an ERROR and, when {@code pStepName} names the step of the
	 * load, in front of that step's outcome message and in its detail ({@code distributionResume}). Never throws. Returns the warning, empty when every target
	 * came back cleanly.
	 */
	static String resumeAndReport(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pProjectId, final List<String> pPausedTargetIds,
		final LoadPauseLedger.Owner pOwner, final String pStepName)
	{
		if (pPausedTargetIds == null || pPausedTargetIds.isEmpty())
		{
			return "";
		}
		ObjectNode resumed = null;
		try
		{
			resumed = DistributionPause.resume(pSession, pFactory, pProjectCode, pPausedTargetIds, pOwner);
			final String warning = DistributionResumeReport.warning(resumed);
			if (warning.isEmpty())
			{
				return "";
			}
			LoggingCategory.getKernel().error("[mdm-factory] " + pStepName + " of project " + pProjectCode + ": " + warning);
			final String stepId = pProjectId == null ? null : PipelineSteps.latestStepId(pFactory, pProjectId, pStepName);
			if (stepId != null)
			{
				PipelineSteps.amendEnded(pSession, pFactory, stepId, warning, null, "distributionResume", resumed);
			}
			return warning;
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] the end of the distribution pause of project " + pProjectCode + " failed: " + ex, ex);
			return "WARNING: the distribution targets of the project may still be PAUSED (" + ex + ").";
		}
	}

	static void setState(final Session pSession, final Adaptation pFactory, final String pTargetId, final String pState) throws OperationException
	{
		final Adaptation before = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTargetId));
		final String projectId = before == null ? null : before.getString(FactoryPaths.DistributionTarget.PROJECT);
		final String oldMode = before == null ? null : before.getString(FactoryPaths.DistributionTarget.MODE);
		final String oldState = before == null ? null : before.getString(FactoryPaths.DistributionTarget.STATE);
		// volume-operations lot 8b (2026-09-28, task 1): if this is an ARM transition (resume: PAUSED -> ARMED),
		// bump the control row BEFORE the state below commits - see DistributionControlRow's own Javadoc for why
		// that ordering, never the reverse, is required. A no-op for a PAUSE (DISARM), handled AFTER, below.
		DistributionControlRow.beforeStateChange(pSession, pFactory, projectId, oldMode, oldState, oldMode, pState);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTargetId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pState, FactoryPaths.DistributionTarget.STATE);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveTargetState", "Distribution target " + pTargetId + ": could not set state " + pState + ": " + result.getExceptionFullMessage(java.util.Locale.ENGLISH), pTargetId);
		}
		// volume-operations lot 8a (2026-09-28, task 3): a target's state changed (ARMED<->PAUSED here) -
		// forget any cached "armed REALTIME target" answer for its project. See ArmedTargetCache's own Javadoc
		// for why this cache is not (yet) reachable from the trigger script itself.
		com.on.ebx.factory.distribution.ArmedTargetCache.invalidate(projectId);
		// volume-operations lot 8b: the DISARM half (pause) - safe to refresh after the commit, see this
		// method's own comment above and DistributionControlRow's Javadoc.
		DistributionControlRow.afterStateChange(pSession, pFactory, projectId, oldMode, oldState, oldMode, pState);
	}
}
