package com.on.ebx.factory.ebx;

import java.util.Date;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.staging.MappingScope;
import com.on.ebx.factory.staging.ProposalProgress;
import com.on.ebx.factory.wizard.WizardMessages;
import com.on.ebx.factory.wizard.WizardRules;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.on.ebx.factory.landing.KeyTexts;
import com.on.ebx.factory.landing.RunTexts;
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
 * Staging-proposal-run lot (2026-09-24, docs/STAGING-PROPOSAL.md "run"): the one-click "Propose the
 * staging" button - launches the full chain: (a) the deterministic rule pass ({@link StagingSuggester}),
 * (b) the language-model structure pass ({@link StagingLlmSuggester}, only when its own gate -
 * administrator, a connector configured, the same rule {@code wizard.WizardRules#actionGates}'s {@code
 * suggestStagingLlm} already computes for the wizard's own button - allows it), (c) {@link
 * LandingColumnProfiler#profile}, (d) the anomaly pass ({@link AnomalyLlmSuggester}), (e) the quality-rule
 * language-model pass over the PROPOSED structure ({@link QualityLlmSuggester}, scope {@link
 * MappingScope#PROPOSAL}), (f) the script-check/capability language-model pass, same scope ({@link
 * CapabilityLlmSuggester}). One parent {@code PipelineStep} ({@link #STEP}) per run, {@code detail}
 * holding a {@link ProposalProgress} JSON the wizard's "while running" screen polls - updated as each
 * stage starts and ends, on top of the per-pass {@code PipelineStep} rows each stage's own class already
 * writes. Since lot C1 (2026-09-24) the run is a job of {@link FactoryJobQueue} (kind {@link #STEP},
 * resource DESIGN) instead of its own daemon executor - see {@link StagingLoadRun}'s own Javadoc for the
 * queue's shape; {@link #start} never refuses any more, {@link #status} reports QUEUED the same way.
 * <p>
 * <b>Dependency graph</b> (see each stage's own {@code progress.skip} call site for the exact reason
 * string): {@link ProposalProgress#PROFILE} is independent of everything else and always attempted. {@link
 * ProposalProgress#STRUCTURE_LLM}/{@link ProposalProgress#ANOMALY}/{@link ProposalProgress#QUALITY}/{@link
 * ProposalProgress#CAPABILITY} are all skipped outright when {@link ProposalProgress#STRUCTURE_RULE}
 * failed - nothing exists yet for any of them to work over. {@link ProposalProgress#QUALITY}/{@link
 * ProposalProgress#CAPABILITY} are additionally skipped when the caller did not select them ({@code
 * pIncludeQuality}/{@code pIncludeCapability} - the wizard's own checkboxes). {@link
 * ProposalProgress#STRUCTURE_LLM}/{@link ProposalProgress#ANOMALY}/{@link ProposalProgress#QUALITY}/{@link
 * ProposalProgress#CAPABILITY} are all skipped when no language model is configured or the caller is not an
 * administrator (the same gate every existing stand-alone language-model button already applies). A stage
 * that runs and fails (an {@code OperationException}, or any other exception - caught broadly on purpose,
 * one stage's own bug must never take the rest of the run down with it) never stops a LATER stage that does
 * not depend on it. A stop asked mid-run ({@link FactoryJobs.Handle#cancelled}) is checked before every
 * stage decision (not only inside {@link #runStage}, since several stages are skipped without ever reaching
 * it): every stage not yet started is recorded {@code SKIPPED} "Not run: stopped (&lt;reason&gt;)." and the
 * run ends with that same wording - the queue's own dispatcher then marks the job {@code CANCELLED}.
 * <p>
 * <b>Role and locale, since lot C1:</b> the job runs with the SYSTEM session, inside {@link Actor#as} for
 * the launcher (agent runs and MCP calls already carry the launcher's identity through {@link Actor}/
 * {@link SessionToken} on their own) - but {@code pSession.isUserInRole(ADMINISTRATOR)} and {@code
 * pSession.getLocale()} would read the SYSTEM user's role/locale, not the launcher's, if read from the job
 * session. {@link #start} therefore captures both from the LAUNCHING session at enqueue time and carries
 * them in the job's params ({@code admin}, {@code locale}); the handler reads them from there, never from
 * the job session.
 */
public final class StagingProposalRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "STAGING_PROPOSAL";

	private StagingProposalRun()
	{
	}

	/**
	 * Queues the run (lot C1, {@link FactoryJobQueue}, resource DESIGN) and returns at once - never refused,
	 * see {@link StagingLoadRun#start}. The launching session's role and locale are captured here (see this
	 * class's own Javadoc, "Role and locale") since the job itself runs with the system session.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pIncludeQuality,
		final boolean pIncludeCapability) throws OperationException
	{
		final Adaptation project = StagingProposalRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = StagingProposalRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("includeQuality", pIncludeQuality);
		params.put("includeCapability", pIncludeCapability);
		// audit F-02: "may use the language model" = the person holds DESIGN (was: is an administrator); the field keeps its name in queued jobs
		params.put("admin", FactoryAccess.hasInPerson(pSession, com.on.ebx.factory.core.FactoryRight.DESIGN));
		params.put("locale", StagingProposalRun.locale(pSession).toLanguageTag());
		final FactoryJobQueue.Queued queued =
			FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), StagingProposalRun.STEP, params);
		final ObjectNode out = StagingProposalRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The job's work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId,
			final JsonNode pParams) throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final boolean includeQuality = pParams.path("includeQuality").asBoolean(false);
			final boolean includeCapability = pParams.path("includeCapability").asBoolean(false);
			final boolean admin = pParams.path("admin").asBoolean(false);
			final Locale locale = StagingProposalRun.localeFromParams(pParams);
			final String stepId = PipelineSteps.begin(pJobSession, pFactory, pProjectId, StagingProposalRun.STEP, "");
			FactoryJobs.attach(pHandle, stepId);
			final ProposalProgress initial = ProposalProgress.newRun();
			StagingProposalRun.updateDetail(pJobSession, pFactory, stepId, initial);
			return StagingProposalRun.runInBackground(pJobSession, pFactory, pProjectId, projectCode, stepId, pHandle, includeQuality, includeCapability,
				admin, locale);
		}
	};

	/** Current state of the latest run of {@code pProjectCode} (if any) - what the wizard's "while running"/"when done" screens poll. Never throws for "no run yet": {@code exists=false}. */
	public static ObjectNode status(final Adaptation pFactory, final String pProjectCode)
	{
		final ObjectNode out = StagingProposalRun.MAPPER.createObjectNode();
		final Adaptation project = StagingProposalRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			out.put("exists", false);
			return out;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Adaptation job = FactoryJobQueue.latest(pFactory, projectId, StagingProposalRun.STEP);
		final boolean queued = job != null && FactoryJobQueue.QUEUED.equals(job.getString(FactoryPaths.FactoryJob.STATUS));
		final Adaptation row = StagingProposalRun.latestRow(pFactory, projectId);
		if (row == null && !queued)
		{
			out.put("exists", false);
			return out;
		}
		final String status = queued ? FactoryJobQueue.QUEUED : row.getString(FactoryPaths.PipelineStep.STATUS);
		// lot C1: RUNNING only while a job really runs it (an orphan row is INTERRUPTED at module start); QUEUED counts as running for the page
		final boolean running = queued || "RUNNING".equals(status) && FactoryJobs.isLive(projectId, StagingProposalRun.STEP);
		out.put("exists", true);
		out.put("status", status);
		out.put("running", running);
		out.put("queued", queued);
		if (job != null)
		{
			out.put("jobId", job.getOccurrencePrimaryKey().format());
		}
		out.put("stale", false);
		if (queued)
		{
			out.put("message", RunTexts.queuedDesign().english);
			out.put("detailJson", "");
			return out;
		}
		final String message = row.getString(FactoryPaths.PipelineStep.MESSAGE);
		if (message != null)
		{
			out.put("message", message);
		}
		final String detail = row.getString(FactoryPaths.PipelineStep.DETAIL);
		// raw JSON, so a caller that already has ProposalProgress.parse (ProjectWizardUserService/WizardPage,
		// which need the real typed object, not this method's own flattened "stages" array below) can rebuild
		// it without a second round trip.
		out.put("detailJson", detail == null ? "" : detail);
		final ProposalProgress progress = ProposalProgress.parse(detail);
		final com.fasterxml.jackson.databind.node.ArrayNode stages = out.putArray("stages");
		for (final ProposalProgress.Stage stage : progress.stages())
		{
			final ObjectNode stageOut = stages.addObject();
			stageOut.put("key", stage.key);
			stageOut.put("state", stage.state);
			if (stage.startedAt != null)
			{
				stageOut.put("startedAt", stage.startedAt.longValue());
			}
			if (stage.endedAt != null)
			{
				stageOut.put("endedAt", stage.endedAt.longValue());
			}
			if (stage.summary != null)
			{
				stageOut.put("summary", stage.summary);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------ the run itself

	/**
	 * Runs on the job pool thread. Wrapped end to end in {@code try/catch}: whatever happens, the parent step
	 * is always ended (never left {@code RUNNING} forever with no error anywhere). Every stage is wrapped in
	 * its own {@code try/catch (Exception)} inside {@link #runStage} - deliberately the broadest catch in this
	 * module, since nothing downstream of a stage's own uncaught exception would ever record it anywhere a
	 * person could see. {@code pAdmin}/{@code pLocale} come from the job's params (see this class's own
	 * Javadoc, "Role and locale"), never from {@code pSession}, which is the system session here.
	 */
	private static String runInBackground(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode,
		final String pStepId, final FactoryJobs.Handle pHandle, final boolean pIncludeQuality, final boolean pIncludeCapability, final boolean pAdmin,
		final Locale pLocale)
	{
		// audit F2-110: the normalisation cache of the project (up to 400,000 values) is dropped on EVERY way out - an exception, an Error, a stop - not only at the end of the normal path
		return StagingProposalRun.releasingAfter(new Body()
		{
			@Override
			public String run()
			{
				return StagingProposalRun.runBody(pSession, pFactory, pProjectId, pProjectCode, pStepId, pHandle, pIncludeQuality, pIncludeCapability, pAdmin, pLocale);
			}
		}, new Runnable()
		{
			@Override
			public void run()
			{
				NormalisationHooks.endOfRun(pFactory, pProjectCode);
			}
		});
	}

	/** A body that answers a text. */
	interface Body
	{
		String run();
	}

	/** Runs {@code pBody}, then {@code pRelease} whatever happened (return, exception or Error). Pure, package-visible for the tests. */
	static String releasingAfter(final Body pBody, final Runnable pRelease)
	{
		try
		{
			return pBody.run();
		}
		finally
		{
			pRelease.run();
		}
	}

	private static String runBody(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode,
		final String pStepId, final FactoryJobs.Handle pHandle, final boolean pIncludeQuality, final boolean pIncludeCapability, final boolean pAdmin,
		final Locale pLocale)
	{
		final ProposalProgress progress = ProposalProgress.newRun();
		boolean structureOk = false;
		try
		{
			// (a) rule pass - always attempted
			structureOk = StagingProposalRun.runStage(pSession, pFactory, pStepId, pHandle, progress, ProposalProgress.STRUCTURE_RULE, new Stage()
			{
				@Override
				public String run() throws OperationException
				{
					final ObjectNode result = StagingSuggester.suggest(pSession, pFactory, pProjectCode);
					// staging normalisation lot D (2026-10-17): profile the landing columns, propose the verified cleaning steps, check the transforms - before the model pass
					return WizardMessages.get(pLocale, "wizard.step4.run.stage.structureRule.summary",
						WizardMessages.countWords(pLocale, result.path("entitiesCreated").asInt(0), "wizard.unit.businessObject"))
						+ NormalisationHooks.afterRulePass(pSession, pFactory, pProjectCode, pLocale);
				}
			});

			// (b) language-model structure pass - same gate as the wizard's own "Refine with the language model" button
			if (pHandle.cancelled())
			{
				progress.skip(ProposalProgress.STRUCTURE_LLM, RunTexts.notRunStopped(pHandle.stopReason()).stored());
			}
			else if (!structureOk)
			{
				progress.skip(ProposalProgress.STRUCTURE_LLM, RunTexts.rulePassIncomplete("refine").stored());
			}
			else
			{
				final WizardSnapshot afterRules = WizardData.readSnapshot(pFactory, pProjectId, pSession);
				// lot C1: afterRules.administrator was computed from pSession.isUserInRole(...) - the SYSTEM
				// session here, not the launcher's. Overridden with the role captured at enqueue time (start()).
				// audit F-02: what was captured is the launcher's DESIGN right (in person), the flag the gates read now
				afterRules.administrator = pAdmin;
				afterRules.designer = pAdmin;
				final WizardRules.ActionGates gates = WizardRules.actionGates(afterRules, afterRules.designer);
				if (!gates.suggestStagingLlm.enabled)
				{
					// Bug fix (staging-proposal live run, 2026-09-24): every other progress.skip call in this
					// method passes an already-formatted plain sentence (ProposalProgress.Stage#summary's own
					// contract - see its Javadoc). gates.suggestStagingLlm.reasonKey is a wizard.gate.* MESSAGE
					// BUNDLE KEY, not text (the same key WizardPage/ProjectWizardUserService resolve through
					// WizardMessages.get/Messages.get before ever showing it to a person) - passed straight
					// through here it rendered as the raw key "wizard.gate.llmProposalsPending" in the run
					// screen. Resolved HERE, at write time, with the launching session's own locale - not at
					// render time in WizardPage, because every other stage's summary already reaches WizardPage
					// as plain text (WizardPage's stage rendering only ever escapes and prints stage.summary,
					// never resolves it - see its own "PROPOSAL_STAGE_KEYS" rendering) and this call is the only
					// place a key could ever appear; resolving once here keeps Stage#summary's "always plain
					// text" contract true for every caller downstream instead of only for this one case.
					progress.skip(ProposalProgress.STRUCTURE_LLM, WizardMessages.get(pLocale, gates.suggestStagingLlm.reasonKey));
				}
				else
				{
					StagingProposalRun.runStage(pSession, pFactory, pStepId, pHandle, progress, ProposalProgress.STRUCTURE_LLM, new Stage()
					{
						@Override
						public String run() throws OperationException
						{
							final ObjectNode result = StagingLlmSuggester.suggest(pSession, pFactory, pProjectCode, pLocale);
							// staging normalisation lot D: the links the model just wrote go through the same steps and the same verifier
							return WizardMessages.get(pLocale, "wizard.step4.run.stage.structureLlm.summary",
								WizardMessages.countWords(pLocale, result.path("entitiesCreated").asInt(0), "wizard.unit.businessObject"),
								WizardMessages.countWords(pLocale, result.path("feedsCreated").asInt(0), "wizard.unit.sourceLink"))
								+ NormalisationHooks.afterLlmPass(pSession, pFactory, pProjectCode, pLocale);
						}
					});
				}
			}

			// (c) profiling - independent of (a)/(b), always attempted
			StagingProposalRun.runStage(pSession, pFactory, pStepId, pHandle, progress, ProposalProgress.PROFILE, new Stage()
			{
				@Override
				public String run() throws OperationException
				{
					final int written = LandingColumnProfiler.profile(pSession, pFactory, pProjectCode, false);
					return WizardMessages.get(pLocale, "wizard.step4.run.stage.profile.summary",
						WizardMessages.countWords(pLocale, written, "wizard.unit.landingColumn"));
				}
			});

			final boolean llmGateOpen = pAdmin && StagingProposalRun.isLlmConfigured(pFactory);

			// (d) anomaly pass
			if (pHandle.cancelled())
			{
				progress.skip(ProposalProgress.ANOMALY, RunTexts.notRunStopped(pHandle.stopReason()).stored());
			}
			else if (!structureOk)
			{
				progress.skip(ProposalProgress.ANOMALY, RunTexts.rulePassIncomplete("review").stored());
			}
			else if (!llmGateOpen)
			{
				progress.skip(ProposalProgress.ANOMALY, RunTexts.noLlmOrAdmin().stored());
			}
			else
			{
				StagingProposalRun.runStage(pSession, pFactory, pStepId, pHandle, progress, ProposalProgress.ANOMALY, new Stage()
				{
					@Override
					public String run() throws OperationException
					{
						final AnomalyLlmSuggester.Result result = AnomalyLlmSuggester.suggest(pSession, pFactory, pProjectCode, pLocale);
						return WizardMessages.get(pLocale, "wizard.step4.run.stage.anomaly.summary",
							WizardMessages.countWords(pLocale, result.replacementsProposed, "wizard.unit.replacementProposed"),
							WizardMessages.countWords(pLocale, result.constantColumnFlags, "wizard.unit.constantMatch"),
							WizardMessages.countWords(pLocale, result.emptyColumnFlags, "wizard.unit.emptyField"),
							WizardMessages.countWords(pLocale, result.duplicateFieldFlags, "wizard.unit.duplicateFieldFlag"));
					}
				});
			}

			// (e) quality-rule language-model pass, scope PROPOSAL
			if (pHandle.cancelled())
			{
				progress.skip(ProposalProgress.QUALITY, RunTexts.notRunStopped(pHandle.stopReason()).stored());
			}
			else if (!pIncludeQuality)
			{
				progress.skip(ProposalProgress.QUALITY, RunTexts.notSelected().stored());
			}
			else if (!structureOk)
			{
				progress.skip(ProposalProgress.QUALITY, RunTexts.rulePassIncomplete("field").stored());
			}
			else if (!llmGateOpen)
			{
				progress.skip(ProposalProgress.QUALITY, RunTexts.noLlmOrAdmin().stored());
			}
			else
			{
				StagingProposalRun.runStage(pSession, pFactory, pStepId, pHandle, progress, ProposalProgress.QUALITY, new Stage()
				{
					@Override
					public String run() throws OperationException
					{
						// PersVol2 volume test (2026-09-25): the deterministic pass first - it is the one that proposes the
						// FOLD_KEY matching keys (firstNameKey, lastNameKey, emailKey...) the matching plan needs when one
						// source writes names in upper case without accents; best effort, it never blocks the LLM pass.
						try
						{
							QualitySuggester.suggest(pSession, pFactory, pProjectCode, null, MappingScope.PROPOSAL);
						}
						catch (final OperationException ex)
						{
							LoggingCategory.getKernel().warn("[mdm-factory] staging proposal: the deterministic quality pass failed: " + ex.getMessage());
						}
						final ObjectNode result = QualityLlmSuggester.suggest(pSession, pFactory, pProjectCode, null, MappingScope.PROPOSAL);
						// staging normalisation lot D: the steps the model just proposed (and what they do to each chain) are verified too; ends the run's cache
						return WizardMessages.get(pLocale, "wizard.step4.run.stage.quality.summary",
							WizardMessages.countWords(pLocale, result.path("entitiesTotal").asInt(0), "wizard.unit.businessObject"))
							+ NormalisationHooks.afterQualityPass(pSession, pFactory, pProjectCode, pLocale);
					}
				});
			}

			// (f) script-check/capability language-model pass, scope PROPOSAL
			if (pHandle.cancelled())
			{
				progress.skip(ProposalProgress.CAPABILITY, RunTexts.notRunStopped(pHandle.stopReason()).stored());
			}
			else if (!pIncludeCapability)
			{
				progress.skip(ProposalProgress.CAPABILITY, RunTexts.notSelected().stored());
			}
			else if (!structureOk)
			{
				progress.skip(ProposalProgress.CAPABILITY, RunTexts.rulePassIncomplete("object").stored());
			}
			else if (!llmGateOpen)
			{
				progress.skip(ProposalProgress.CAPABILITY, RunTexts.noLlmOrAdmin().stored());
			}
			else
			{
				StagingProposalRun.runStage(pSession, pFactory, pStepId, pHandle, progress, ProposalProgress.CAPABILITY, new Stage()
				{
					@Override
					public String run() throws OperationException
					{
						final ObjectNode result = CapabilityLlmSuggester.suggest(pSession, pFactory, pProjectCode, null, null, MappingScope.PROPOSAL, pLocale);
						return WizardMessages.get(pLocale, "wizard.step4.run.stage.capability.summary",
							WizardMessages.countWords(pLocale, result.path("entitiesConsidered").asInt(0), "wizard.unit.businessObject"));
					}
				});
			}

			if (pHandle.cancelled())
			{
				// staging screens (2026-10-17): the step's own message stays plain English (REST, cockpit); the job's message is the coded text the screens word in the viewer's language
				final KeyTexts.Text stopped = RunTexts.proposalStopped(pHandle.stopReason());
				PipelineSteps.endStopped(pSession, pFactory, pStepId, stopped.english, progress.toJson());
				return stopped.stored();
			}
			PipelineSteps.end(pSession, pFactory, pStepId, true, RunTexts.proposalFinished().english, progress.toJson());
			return RunTexts.proposalFinished().stored();
		}
		catch (final Exception ex) // deliberately broad - see this method's own Javadoc
		{
			LoggingCategory.getKernel().error("[mdm-factory] staging proposal run failed for project " + pProjectCode + ": " + ex, ex);
			try
			{
				PipelineSteps.end(pSession, pFactory, pStepId, false, RunTexts.proposalFailed(ex.getMessage()).english, progress.toJson());
			}
			catch (final Exception logged) // PipelineSteps.end itself never throws (best-effort) - this is defence in depth only
			{
				LoggingCategory.getKernel().error("[mdm-factory] could not even record the failure of staging proposal run for project " + pProjectCode
					+ ": " + logged, logged);
			}
			throw new IllegalStateException(RunTexts.proposalFailed(ex.getMessage()).stored(), ex);
		}
	}

	/** One stage's own work, returning its one-line summary on success. */
	private interface Stage
	{
		String run() throws OperationException;
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live-bug items 8/10): the one place every stage summary above resolves
	 * its wording locale, so each stage message can go through {@link WizardMessages#get}/{@code countWords}
	 * (plain words, plural-correct) instead of the earlier hardcoded English "(s)" text - same fallback rule
	 * already established at the STRUCTURE_LLM skip reason above, now shared instead of repeated per call site.
	 * Used by {@link #start} to capture the launching session's locale into the job's params.
	 */
	private static Locale locale(final Session pSession)
	{
		return pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale();
	}

	/** {@code pParams}'s {@code "locale"} (a BCP 47 tag captured by {@link #start}) -&gt; a {@link Locale}, defaulting to {@link Locale#ENGLISH} exactly like {@link #locale(Session)} did for a session with no locale. */
	private static Locale localeFromParams(final JsonNode pParams)
	{
		final String tag = pParams.path("locale").asText("");
		return tag.isEmpty() ? Locale.ENGLISH : Locale.forLanguageTag(tag);
	}

	/** Runs one stage: marks it {@code RUNNING}, persists, runs the work, marks {@code DONE}/{@code FAILED}, persists again. @return whether it succeeded. */
	private static boolean runStage(final Session pSession, final Adaptation pFactory, final String pStepId, final FactoryJobs.Handle pHandle,
		final ProposalProgress pProgress, final String pKey, final Stage pStage)
	{
		if (pHandle.cancelled())
		{
			// lot C1: a stop asked right before this stage - the stage is recorded as not run, not attempted
			pProgress.skip(pKey, RunTexts.notRunStopped(pHandle.stopReason()).stored());
			StagingProposalRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return false;
		}
		pProgress.start(pKey, System.currentTimeMillis());
		StagingProposalRun.updateDetail(pSession, pFactory, pStepId, pProgress);
		try
		{
			final String summary = pStage.run();
			pProgress.finish(pKey, true, summary, System.currentTimeMillis());
			StagingProposalRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return true;
		}
		catch (final Exception ex) // deliberately broad - one stage's own bug must never take the rest of the run down with it
		{
			pProgress.finish(pKey, false, TextLimits.truncate(ErrorText.of(ex), 500), System.currentTimeMillis());
			StagingProposalRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return false;
		}
	}

	/** Best-effort partial update of the parent step's {@code detail} only - same posture as {@link PipelineSteps#end}, logged not thrown. */
	private static void updateDetail(final Session pSession, final Adaptation pFactory, final String pStepId, final ProposalProgress pProgress)
	{
		final AdaptationTable steps = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
		final String detail = TextLimits.truncate(pProgress.toJson(), TextLimits.DETAIL_MAX_LENGTH);
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
				value.setValue(detail, FactoryPaths.PipelineStep.DETAIL);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not update the progress of step " + pStepId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	private static boolean isLlmConfigured(final Adaptation pFactory)
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		final String llm = settings == null ? null : settings.getString(FactoryPaths.Settings.LLM_CONNECTOR);
		return llm != null && !llm.trim().isEmpty();
	}

	/** The most recently STARTED {@code PipelineStep} row of {@link #STEP} for {@code pProjectId} - project-wide scope (""), so a run scoped under any other key is never confused with this one. Null when none exists yet. */
	private static Adaptation latestRow(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(StagingProposalRun.STEP) + " and "
				+ PipelineSteps.scopeEquals("./scope", ""));
		try
		{
			Adaptation latest = null;
			Date latestStartedAt = null;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Date startedAt = (Date) row.get(FactoryPaths.PipelineStep.STARTED_AT);
				if (latest == null || (startedAt != null && (latestStartedAt == null || startedAt.after(latestStartedAt))))
				{
					latest = row;
					latestStartedAt = startedAt;
				}
			}
			return latest;
		}
		finally
		{
			rows.close();
		}
	}

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}
}
