package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.ebx.ui.EngineNotice;
import com.on.ebx.factory.matching.MatchResultsSnapshot;
import com.on.ebx.factory.review.MatchPlanView;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.UserReference;

/**
 * F2-102 (robustness audit 2026-10-23): four more actions of the wizard that ran inside the HTTP request, as jobs of {@link FactoryJobQueue} (the fifth, the intake switch, is {@link
 * IntakeSwitchRun}). One kind per action, the request only checks what is cheap and queues; the wizard's jobs banner follows the job like any other.
 * <ul>
 * <li>{@link #PREFLIGHT_STEP} - "Check before building" and "Apply the fix" of step 4 (reads the landing's distinct values, replays every accepted link); resource STAGING. The report is
 * stored by {@link StagingPreflight#runAndStore} for the page, as before; the job's message is the sentence the page used to show.</li>
 * <li>{@link #SIMULATE_STEP} - "Simulate" on a matching plan card (20 candidate searches plus the coverage queries); resource MATCHING. The result is kept in memory under the person
 * who asked, like {@link KeyStudyRun}, and the wizard takes it at its next display ({@link #take}).</li>
 * <li>{@link #BULK_MERGE_STEP} - "Merge the N pairs at or above the threshold" of step 6 (up to 60 Match and Merge decisions); resource MATCHING, right STEWARD. The request reads the
 * pending pairs as the PERSON (their permissions decide which pairs they see) and hands the eligible keys to the job.</li>
 * <li>{@link #PIPELINE_STEP} - "Chain the loads" of the summary: launches the pipeline workflow; resource DESIGN. The instance's creator is the person who asked (the pipeline's own
 * gate checks that creator's right), not the job's system session.</li>
 * </ul>
 * Permissions are checked where the job is queued (the right of each kind, {@link FactoryJobKinds}) and again when it starts.
 */
public final class WizardActionRuns
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String PREFLIGHT_STEP = "STAGING_PREFLIGHT_RUN";
	public static final String SIMULATE_STEP = "MATCHING_SIMULATE_RUN";
	public static final String BULK_MERGE_STEP = "MATCHING_BULK_MERGE_RUN";
	public static final String PIPELINE_STEP = "PIPELINE_LAUNCH_RUN";
	/** How long a finished simulation waits to be taken. */
	static final long KEEP_MILLIS = 30L * 60L * 1000L;
	private static final int KEEP_MAX = 50;
	private static final Map<String, Kept> SIMULATIONS = new LinkedHashMap<String, Kept>();

	private WizardActionRuns()
	{
	}

	private static final class Kept
	{
		final MatchPlanView.SimulationResult result;
		final long at;

		Kept(final MatchPlanView.SimulationResult pResult, final long pAt)
		{
			this.result = pResult;
			this.at = pAt;
		}
	}

	// ------------------------------------------------------------------------------------------------------ queuing

	/** Queues the check before the build; {@code pApplyFix}: writes the fix(es) of the stored report first ({@code pMappingId} empty = every fixable link). */
	public static ObjectNode startPreflight(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pApplyFix, final String pMappingId)
		throws OperationException
	{
		final ObjectNode params = WizardActionRuns.params(pSession, pProjectCode);
		params.put("applyFix", pApplyFix);
		params.put("mappingId", pMappingId == null ? "" : pMappingId);
		return WizardActionRuns.enqueue(pSession, pFactory, pProjectCode, WizardActionRuns.PREFLIGHT_STEP, params);
	}

	/** Queues the simulation of one plan; {@code pEntityName} and {@code pRequirementsJson} are the plan line's own. */
	public static ObjectNode startSimulation(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pRowId, final String pEntityName,
		final String pRequirementsJson) throws OperationException
	{
		final ObjectNode params = WizardActionRuns.params(pSession, pProjectCode);
		params.put("user", Actor.current(pSession).userId);
		params.put("rowId", pRowId);
		params.put("entity", pEntityName);
		params.put("requirements", pRequirementsJson == null ? "" : pRequirementsJson);
		return WizardActionRuns.enqueue(pSession, pFactory, pProjectCode, WizardActionRuns.SIMULATE_STEP, params);
	}

	/** Queues the bulk merge of {@code pPairs} (golden key, suspect key), already read and filtered as the person. */
	public static ObjectNode startBulkMerge(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName, final List<String[]> pPairs)
		throws OperationException
	{
		final ObjectNode params = WizardActionRuns.params(pSession, pProjectCode);
		params.put("entity", pEntityName);
		final ArrayNode pairs = params.putArray("pairs");
		for (final String[] pair : pPairs)
		{
			pairs.addArray().add(pair[0]).add(pair[1]);
		}
		return WizardActionRuns.enqueue(pSession, pFactory, pProjectCode, WizardActionRuns.BULK_MERGE_STEP, params);
	}

	/** Queues the launch of the pipeline workflow for the project. */
	public static ObjectNode startPipeline(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final ObjectNode params = WizardActionRuns.params(pSession, pProjectCode);
		params.put("user", Actor.current(pSession).userId);
		return WizardActionRuns.enqueue(pSession, pFactory, pProjectCode, WizardActionRuns.PIPELINE_STEP, params);
	}

	private static ObjectNode params(final Session pSession, final String pProjectCode)
	{
		final ObjectNode params = WizardActionRuns.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", (pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale()).toLanguageTag());
		return params;
	}

	private static ObjectNode enqueue(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pKind, final ObjectNode pParams)
		throws OperationException
	{
		return RunLauncher.launch(pSession, pFactory, pProjectCode, pKind, pParams);
	}

	// ------------------------------------------------------------------------------------------------------ handlers

	static final FactoryJobQueue.Handler PREFLIGHT_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final Locale locale = WizardActionRuns.localeFromParams(pParams);
			final String projectCode = pParams.path("projectCode").asText("");
			String applied = "";
			if (pParams.path("applyFix").asBoolean(false))
			{
				final StagingPreflight.FixOutcome fix = StagingPreflight.applyFix(pJobSession, pFactory, projectCode, pParams.path("mappingId").asText(""));
				applied = WizardMessages.get(locale, "wizard.step4.preflight.notice.fixed", Integer.valueOf(fix.applied)) + " ";
				if (fix.stale > 0 || fix.missing > 0)
				{
					applied += WizardMessages.get(locale, "wizard.step4.preflight.notice.stale", Integer.valueOf(fix.stale + fix.missing)) + " ";
				}
			}
			final com.on.ebx.factory.staging.PreflightReport report = StagingPreflight.runAndStore(pJobSession, pFactory, projectCode, null, StagingPreflight.NEVER);
			return applied + WizardActionRuns.preflightSentence(report, locale);
		}
	};

	/** The sentence the page used to show after the check: blocked, clean or warnings. */
	static String preflightSentence(final com.on.ebx.factory.staging.PreflightReport pReport, final Locale pLocale)
	{
		if (pReport.blocking())
		{
			return WizardMessages.get(pLocale, "wizard.step4.preflight.notice.blocked", Long.valueOf(pReport.blockingRows()), Integer.valueOf(pReport.blockingLinks()));
		}
		if (pReport.clean())
		{
			return WizardMessages.get(pLocale, "wizard.step4.preflight.notice.clean", Integer.valueOf(pReport.linksChecked));
		}
		return WizardMessages.get(pLocale, "wizard.step4.preflight.notice.warnings", Integer.valueOf(pReport.warnings()));
	}

	static final FactoryJobQueue.Handler SIMULATE_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final Locale locale = WizardActionRuns.localeFromParams(pParams);
			final String user = pParams.path("user").asText("");
			final String rowId = pParams.path("rowId").asText("");
			try
			{
				final MatchPlanView.SimulationResult result = MatchPlanSimulationRunner.simulate(pJobSession, pFactory, pParams.path("projectCode").asText(""), pProjectId,
					pParams.path("entity").asText(""), pParams.path("requirements").asText(""), rowId);
				WizardActionRuns.keep(pProjectId, user, rowId, result, System.currentTimeMillis());
				return WizardMessages.get(locale, "review.match.simulate.done", Integer.valueOf(result.pivotCount), Integer.valueOf(result.pairs.size()));
			}
			catch (final OperationException ex)
			{
				final String raw = ex.getMessage();
				final String line = MatchingActivationGuard.isActivationRefusal(raw)
					? WizardMessages.get(locale, "review.match.simulate.activation.refused", MatchingActivationGuard.stripMarker(raw))
					: WizardMessages.get(locale, "review.match.simulate.failed", EngineNotice.inline(ex, locale));
				// kept as the plan's result too: the card shows the failure under the Simulate button instead of an unchanged "Not simulated yet"
				WizardActionRuns.keep(pProjectId, user, rowId, MatchPlanView.SimulationResult.failed(line), System.currentTimeMillis());
				throw new FactoryJobQueue.ExpectedFailure(line);
			}
		}
	};

	static final FactoryJobQueue.Handler BULK_MERGE_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final Locale locale = WizardActionRuns.localeFromParams(pParams);
			final String projectCode = pParams.path("projectCode").asText("");
			final String entity = pParams.path("entity").asText("");
			final String user = Actor.current(pJobSession).userId;
			int ok = 0;
			int refused = 0;
			final List<MatchResultsSnapshot.Decision> decided = new ArrayList<MatchResultsSnapshot.Decision>();
			for (final JsonNode pair : pParams.path("pairs"))
			{
				if (pHandle.cancelled())
				{
					break;
				}
				final String golden = pair.path(0).asText("");
				final String suspect = pair.path(1).asText("");
				try
				{
					final RecordLineageReader.DecisionResult result = RecordLineageReader.decideSuspect(pJobSession, pFactory, projectCode, entity, golden, suspect, true, false);
					if (result.success)
					{
						ok++;
						decided.add(new MatchResultsSnapshot.Decision(golden, suspect, true, user, java.time.Instant.now().toString()));
					}
					else
					{
						refused++;
					}
				}
				catch (final OperationException ex)
				{
					refused++;
				}
			}
			// the stored results are patched and ONE refresh is queued for the whole batch (decideSuspect's per-decision hook was switched off above)
			MatchingResultsStore.afterDecisions(pJobSession, pFactory, projectCode, pProjectId, entity, decided);
			return WizardMessages.get(locale, "wizard.step6.results.bulkDone", Integer.valueOf(ok), Integer.valueOf(refused));
		}
	};

	static final FactoryJobQueue.Handler PIPELINE_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final Locale locale = WizardActionRuns.localeFromParams(pParams);
			final Repository repository = pFactory.getHome().getRepository();
			if (!PipelineWorkflowLauncher.isPublished(repository))
			{
				throw new FactoryJobQueue.ExpectedFailure(WizardMessages.get(locale, "wizard.step7.gate.notPublished"));
			}
			final String user = pParams.path("user").asText("");
			final String key = PipelineWorkflowLauncher.launch(repository, pJobSession, pParams.path("projectCode").asText(""), user.isEmpty() ? null : UserReference.forUser(user));
			return WizardMessages.get(locale, "wizard.step7.prod.chain.launched", key);
		}
	};

	// ------------------------------------------------------------------------------------------------------ simulation hand-off

	static String key(final String pProjectId, final String pUser, final String pRowId)
	{
		return pProjectId + "|" + pUser + "|" + pRowId;
	}

	static void keep(final String pProjectId, final String pUser, final String pRowId, final MatchPlanView.SimulationResult pResult, final long pNow)
	{
		synchronized (WizardActionRuns.SIMULATIONS)
		{
			WizardActionRuns.purge(pNow);
			WizardActionRuns.SIMULATIONS.put(WizardActionRuns.key(pProjectId, pUser, pRowId), new Kept(pResult, pNow));
			while (WizardActionRuns.SIMULATIONS.size() > WizardActionRuns.KEEP_MAX)
			{
				final java.util.Iterator<String> oldest = WizardActionRuns.SIMULATIONS.keySet().iterator();
				oldest.next();
				oldest.remove();
			}
		}
	}

	/** The finished simulation of this plan for this person, removed from memory; null when there is none (not finished, expired, or taken already). */
	public static MatchPlanView.SimulationResult take(final String pProjectId, final String pUser, final String pRowId)
	{
		return WizardActionRuns.take(pProjectId, pUser, pRowId, System.currentTimeMillis());
	}

	static MatchPlanView.SimulationResult take(final String pProjectId, final String pUser, final String pRowId, final long pNow)
	{
		synchronized (WizardActionRuns.SIMULATIONS)
		{
			WizardActionRuns.purge(pNow);
			final Kept kept = WizardActionRuns.SIMULATIONS.remove(WizardActionRuns.key(pProjectId, pUser, pRowId));
			return kept == null ? null : kept.result;
		}
	}

	/** Whether a simulation job of the project is waiting or running. */
	public static boolean simulationActive(final Adaptation pFactory, final String pProjectId)
	{
		for (final Adaptation row : FactoryJobQueue.active(pFactory, pProjectId))
		{
			if (WizardActionRuns.SIMULATE_STEP.equals(row.getString(FactoryPaths.FactoryJob.KIND)))
			{
				return true;
			}
		}
		return false;
	}

	private static void purge(final long pNow)
	{
		final java.util.Iterator<Kept> it = WizardActionRuns.SIMULATIONS.values().iterator();
		while (it.hasNext())
		{
			if (pNow - it.next().at > WizardActionRuns.KEEP_MILLIS)
			{
				it.remove();
			}
		}
	}

	private static Locale localeFromParams(final JsonNode pParams)
	{
		final String tag = pParams.path("locale").asText("");
		return tag.isEmpty() ? Locale.ENGLISH : Locale.forLanguageTag(tag);
	}
}
