package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpException;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.master.MatchPlanLlmPass;
import com.on.ebx.factory.master.ScanBudget;
import com.on.ebx.factory.master.SimulationFeedback;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lot F1 (2026-10-19, {@code docs/MATCHING.md} &sect;70): the FEEDBACK TURN of the language-model matching pass - the numeric feedback a model gets AFTER it proposed a plan.
 * <p>
 * <b>The rule of this module</b> (the product owner, 2026-10-18): the Factory never decides the matching configuration in place of the model; it gives the model measured facts, explained rules and a
 * numeric feedback after its answer, and the model converges by itself. Nothing here sets a weight, a threshold or a leaf: the pass simulates the plans the model proposed
 * ({@link MatchPlanSimulationRunner#run}, the very simulation of the Simulate button, same pivots draw, same probes), measures what each plan does to the pairs the engine evaluated
 * ({@link SimulationFeedback}: counts only), tells the model, and records its answer - a revised plan, judged by the same checks as any proposal, or the plan kept with the model's
 * rationale. Either answer stands.
 * <p>
 * <b>Where it runs</b>: inside {@link MatchPlanLlmSuggester} (the whole-project proposal job), after the plans are written - the simulation needs the stored plan row (the add-on is told the
 * plan through a preview policy). It is skipped, with the reason in the step detail ({@code feedback.entities.<Entity>.reason}), for an entity whose master table is above
 * {@link SimulationFeedback#MAX_ROWS_PROPERTY} (default: the bound of {@link ScanBudget}), once the time bound of {@link SimulationFeedback#MAX_PHASE_MILLIS} is spent, for the Factory's own
 * fallback plan (not the model's), and for a single-entity "Propose another" (the person has the Simulate button and the statements it stores on the plan for the next proposal). A failed
 * simulation, or a failed turn, never fails the proposal: the plans written first stand.
 * <p>
 * <b>Recorded</b>: the turn is an {@code AgentRun} of its own (phase {@value #PHASE}, its steps are the model's answers), the plan's rationale starts with one line
 * ({@link MatchPlanFeedbackText}), and the step detail carries {@code feedback.*} (counts, outcome per entity) - see {@link #run}.
 */
final class MatchPlanFeedbackPass
{
	/** {@code AgentRun.phase} of the feedback turn. */
	static final String PHASE = "MATCHING_SUGGEST_LLM_FEEDBACK";
	/** How many {@code evaluatePair} calls one simulation of the pass may make: the add-on's visit order is recorded for that many pairs, so the per-leaf counts cover more of them. */
	static final int DETAIL_BUDGET = 150;
	/** The most of the model's reason for keeping a plan that goes into the step detail. */
	private static final int KEPT_REASON_DETAIL_LENGTH = 300;

	private MatchPlanFeedbackPass()
	{
	}

	/** What the pass needs from the proposal that just ran. */
	static final class Context
	{
		Session session;
		Adaptation factory;
		String projectId;
		String projectCode;
		/** The facts given to the model in its first turn (every eligible entity). */
		MatchPlanLlmPass.ProjectInfo project;
		MatchPlanLlmPass.Session llmSession;
		Map<String, String> entityIdByName;
		String systemPrompt;
		/** The launcher's locale: the language of the rationale line. */
		Locale locale;
		/** The simulation of one plan; null: the real one ({@link MatchPlanSimulationRunner#run}). A seam for the tests of the boundary. */
		Simulator simulator;
	}

	/** One simulation of a stored plan. */
	interface Simulator
	{
		MatchPlanSimulationRunner.Run simulate(Context pContext, String pEntityName, String pRequirementsJson, String pRowId) throws Exception;
	}

	/** A phase that is run behind the boundary of {@link #guarded}. */
	interface Phase
	{
		void run() throws Exception;
	}

	/**
	 * The boundary of the phase (lot F1b, 2026-10-19): ANY {@link Throwable} but the errors of the virtual machine ({@link NonFatal}) is recorded in {@code feedback.error}, logged at WARN, and the
	 * proposal goes on. Live, a Calcite code-generation failure surfaced as an {@code ExceptionInInitializerError} - an {@link Error} - and took the whole proposal down.
	 */
	static void guarded(final Context pContext, final ObjectNode pNode, final Phase pPhase)
	{
		try
		{
			pPhase.run();
		}
		catch (final Throwable ex)
		{
			NonFatal.rethrowIfFatal(ex);
			LoggingCategory.getKernel().warn("[mdm-factory] Matching plan feedback on project " + pContext.projectCode + " failed: " + NonFatal.reason(ex, 600));
			pNode.put("error", NonFatal.reason(ex, MatchPlanLlmSuggester.LAST_REFUSAL_MAX_LENGTH));
		}
	}

	/**
	 * One simulation behind its own boundary: the {@link MatchPlanSimulationRunner.Run} to tell the model, or null with the reason in {@code pEntityNode} ({@code ran=false}, {@code reason}) -
	 * the simulation failed (any Throwable but a virtual machine error) or judged no pair.
	 */
	static MatchPlanSimulationRunner.Run simulateGuarded(final Context pContext, final String pEntityName, final String pRequirementsJson, final String pRowId, final ObjectNode pEntityNode)
	{
		try
		{
			final Simulator simulator = pContext.simulator != null ? pContext.simulator : new Simulator()
			{
				@Override
				public MatchPlanSimulationRunner.Run simulate(final Context pCtx, final String pEntity, final String pRequirements, final String pRow) throws Exception
				{
					return MatchPlanSimulationRunner.run(pCtx.session, pCtx.factory, pCtx.projectCode, pCtx.projectId, pEntity, pRequirements, pRow, MatchPlanFeedbackPass.DETAIL_BUDGET);
				}
			};
			final MatchPlanSimulationRunner.Run run = simulator.simulate(pContext, pEntityName, pRequirementsJson, pRowId);
			if (run == null || run.feedback == null)
			{
				MatchPlanFeedbackPass.skip(pEntityNode, "the simulation judged no pair" + (run == null ? "" : " (" + run.result.pivotCount + " pivot(s) drawn)"));
				return null;
			}
			return run;
		}
		catch (final Throwable ex)
		{
			NonFatal.rethrowIfFatal(ex);
			MatchPlanFeedbackPass.skip(pEntityNode, "the simulation failed: " + NonFatal.reason(ex, 600));
			LoggingCategory.getKernel().warn("[mdm-factory] Matching plan feedback for " + pEntityName + ": the simulation failed - " + NonFatal.reason(ex, 600));
			return null;
		}
	}

	/**
	 * Runs the feedback phase and records it in {@code pOut.feedback}:
	 * <ul>
	 * <li>{@code enabled}, {@code maxRows}, {@code skippedAll} (a reason) when nothing could run;</li>
	 * <li>{@code agentRun.{id,status,steps,inputTokens,outputTokens}} - the model's turn;</li>
	 * <li>{@code entities.<Entity>}: {@code ran}; when false {@code reason}; when true {@code pivots}, {@code pairs}, {@code match}, {@code suspected}, {@code notMatched}, {@code probed},
	 * {@code suspectedOnKeys}, {@code notMatchedOnKeys}, {@code matchOnKeys}, {@code engineWithLeaf}, {@code keySets[]} ({@code fields}, {@code safe}, {@code engine}, {@code probe}),
	 * {@code leaves[]}, {@code outcome} ({@code REVISED}/{@code KEPT}/{@code NO_ANSWER}), {@code keepRefusedOnce}, {@code keptReason}, {@code stored}, {@code rationaleLine}, {@code revisedRefusal}.</li>
	 * </ul>
	 * Never throws: a failure is recorded where it happened.
	 */
	static void run(final Context pContext, final ObjectNode pOut)
	{
		final ObjectNode node = pOut.putObject("feedback");
		final ObjectNode entitiesNode = node.putObject("entities");
		final long maxRows = SimulationFeedback.maxRows(System.getProperty(SimulationFeedback.MAX_ROWS_PROPERTY), ScanBudget.current());
		node.put("maxRows", maxRows);
		node.put("enabled", maxRows > 0L);
		MatchPlanFeedbackPass.guarded(pContext, node, new Phase()
		{
			@Override
			public void run() throws Exception
			{
				MatchPlanFeedbackPass.runUnchecked(pContext, pOut, node, entitiesNode, maxRows);
			}
		});
	}

	private static void runUnchecked(final Context pContext, final ObjectNode pOut, final ObjectNode pNode, final ObjectNode pEntitiesNode, final long pMaxRows)
	{
		final long started = System.currentTimeMillis();
		final Map<String, SimulationFeedback.Result> results = new LinkedHashMap<String, SimulationFeedback.Result>();
		final Map<String, List<String>> coverageFacts = new LinkedHashMap<String, List<String>>();
		final List<MatchPlanLlmPass.CreatedPlan> candidates = pContext.llmSession.createdPlans();
		for (final MatchPlanLlmPass.CreatedPlan plan : candidates)
		{
			final String entityName = plan.entityName;
			final ObjectNode entityNode = pEntitiesNode.putObject(entityName);
			if (plan.isFallback())
			{
				MatchPlanFeedbackPass.skip(entityNode, "the plan is the Factory's own fallback plan, not the model's: no feedback turn for it");
				continue;
			}
			final Adaptation row = MatchPlanLlmSuggester.findMatchPlan(pContext.factory, pContext.entityIdByName.get(entityName));
			final String stored = row == null ? null : row.getString(FactoryPaths.MatchPlan.REQUIREMENTS);
			if (row == null || !MatchPlanLlmPass.isRewritable(row.getString(FactoryPaths.MatchPlan.STATUS)) || !"LLM".equals(row.getString(FactoryPaths.MatchPlan.ORIGIN))
				|| !MatchPlanLlmPass.sameStructure(stored, plan.requirementsJson))
			{
				MatchPlanFeedbackPass.skip(entityNode, "the plan row does not hold this proposal (not stored, decided by a person, or replaced): nothing to simulate");
				continue;
			}
			final MatchPlanLlmPass.EntityInfo entity = pContext.project.entityNamed(entityName);
			final String refusal = SimulationFeedback.gateRefusal(entity == null ? 0L : entity.rowCount, pMaxRows, System.currentTimeMillis() - started);
			if (refusal != null)
			{
				MatchPlanFeedbackPass.skip(entityNode, refusal);
				continue;
			}
			final MatchPlanSimulationRunner.Run run = MatchPlanFeedbackPass.simulateGuarded(pContext, entityName, stored, row.getOccurrencePrimaryKey().format(), entityNode);
			if (run == null)
			{
				continue;
			}
			results.put(entityName, run.feedback);
			final List<String> coverage = new ArrayList<String>();
			for (final String fact : run.storedFacts)
			{
				if (!fact.startsWith(MatchPlanSimulationRunner.FEEDBACK_FACT_PREFIX))
				{
					coverage.add(fact);
				}
			}
			coverageFacts.put(entityName, coverage);
			entityNode.put("ran", true);
			LoggingCategory.getKernel().info("[mdm-factory] Matching plan feedback for " + entityName + " (project " + pContext.projectCode + "): " + run.feedback.summaryLine());
		}
		if (results.isEmpty())
		{
			pNode.put("skippedAll", "no plan could be simulated");
			return;
		}

		// the turn: the plan the model proposed (as stored, now carrying the statements), the counts, the two ways to answer
		final List<String> opened = pContext.llmSession.beginFeedback(results.keySet());
		final List<MatchPlanLlmPass.EntityInfo> subset = new ArrayList<MatchPlanLlmPass.EntityInfo>();
		final List<MatchPlanLlmPass.FeedbackItem> items = new ArrayList<MatchPlanLlmPass.FeedbackItem>();
		for (final String entityName : opened)
		{
			final MatchPlanLlmPass.EntityInfo entity = pContext.project.entityNamed(entityName);
			if (entity == null)
			{
				continue;
			}
			entity.startingPlan = null;
			subset.add(entity);
			final Adaptation row = MatchPlanLlmSuggester.findMatchPlan(pContext.factory, pContext.entityIdByName.get(entityName));
			final List<String> statements = new ArrayList<String>(results.get(entityName).facts());
			final List<String> coverage = coverageFacts.get(entityName);
			if (coverage != null)
			{
				statements.addAll(coverage);
			}
			items.add(new MatchPlanLlmPass.FeedbackItem(entityName, row == null ? pContext.llmSession.createdPlan(entityName).requirementsJson : row.getString(FactoryPaths.MatchPlan.REQUIREMENTS),
				statements));
		}
		if (items.isEmpty())
		{
			pNode.put("skippedAll", "no entity could be opened for the feedback turn");
			return;
		}
		final MatchPlanLlmPass.ProjectInfo turnProject = new MatchPlanLlmPass.ProjectInfo(pContext.project.code, pContext.project.label, subset, pContext.project.childNotes,
			pContext.project.guideTexts, pContext.project.shareValues, Collections.<MatchPlanLlmPass.ExistingPlan>emptyList(), pContext.project.trustedSources);
		turnProject.vocabularyNote = pContext.project.vocabularyNote;
		try
		{
			final ToolSet tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), Collections.singletonMap("factory", pContext.llmSession.feedbackToolGroup()));
			final AgentRuntime.Outcome outcome = AgentRuntime.run(pContext.session, pContext.factory, pContext.projectId, MatchPlanFeedbackPass.PHASE,
				"Weigh the simulation feedback on the matching plans of project " + pContext.projectCode + ": keep each plan with a rationale, or revise it.", pContext.systemPrompt,
				MatchPlanLlmPass.feedbackPrompt(turnProject, items), tools);
			final ObjectNode agentRun = pNode.putObject("agentRun");
			agentRun.put("id", outcome.runId);
			agentRun.put("status", AgentRuntime.statusOf(outcome.status));
			agentRun.put("steps", outcome.steps);
			agentRun.put("inputTokens", outcome.inputTokens);
			agentRun.put("outputTokens", outcome.outputTokens);
			if (outcome.detail != null)
			{
				agentRun.put("detail", TextLimits.truncate(outcome.detail, MatchPlanLlmSuggester.LAST_REFUSAL_MAX_LENGTH));
			}
		}
		catch (final Throwable ex)
		{
			NonFatal.rethrowIfFatal(ex);
			// the plans stand as they were proposed; the entities the model did not answer are recorded as such
			pNode.put("turnError", TextLimits.truncate(ErrorText.of(ex), MatchPlanLlmSuggester.LAST_REFUSAL_MAX_LENGTH));
			LoggingCategory.getKernel().warn("[mdm-factory] Matching plan feedback turn on project " + pContext.projectCode + " failed: " + ErrorText.of(ex));
		}

		MatchPlanFeedbackPass.settle(pContext, pEntitiesNode, results);
	}

	/** Records the model's answer per entity: the rationale line on the plan, the revised plan stored, the figures and the outcome in the step detail. */
	private static void settle(final Context pContext, final ObjectNode pEntitiesNode, final Map<String, SimulationFeedback.Result> pResults)
	{
		final Map<String, MatchPlanLlmPass.Session.FeedbackOutcome> outcomes = pContext.llmSession.feedbackOutcomes();
		final List<MatchPlanLlmPass.CreatedPlan> revised = new ArrayList<MatchPlanLlmPass.CreatedPlan>();
		for (final Map.Entry<String, MatchPlanLlmPass.Session.FeedbackOutcome> entry : outcomes.entrySet())
		{
			final String entityName = entry.getKey();
			final MatchPlanLlmPass.Session.FeedbackOutcome outcome = entry.getValue();
			final SimulationFeedback.Result feedback = pResults.get(entityName);
			final ObjectNode entityNode = (ObjectNode) pEntitiesNode.get(entityName);
			if (feedback == null || entityNode == null)
			{
				continue;
			}
			final String line = MatchPlanFeedbackText.line(pContext.locale, feedback, outcome.kind);
			final String rationale = MatchPlanFeedbackText.rationale(pContext.locale, feedback, outcome.kind, outcome.keptRationale, outcome.after.rationale);
			final MatchPlanLlmPass.CreatedPlan settled = pContext.llmSession.settleFeedback(entityName, rationale);
			MatchPlanFeedbackPass.putFigures(entityNode, feedback);
			entityNode.put("outcome", outcome.kind);
			entityNode.put("keepRefusedOnce", outcome.keepRefusedOnce);
			entityNode.put("rationaleLine", line);
			if (!outcome.keptRationale.isEmpty())
			{
				entityNode.put("keptReason", TextLimits.truncate(outcome.keptRationale, KEPT_REASON_DETAIL_LENGTH));
			}
			if (MatchPlanLlmPass.Session.FeedbackOutcome.REVISED.equals(outcome.kind))
			{
				revised.add(settled);
			}
			else
			{
				final Adaptation row = MatchPlanLlmSuggester.findMatchPlan(pContext.factory, pContext.entityIdByName.get(entityName));
				entityNode.put("stored", MatchPlanLlmSuggester.updateRationale(pContext.session, pContext.factory, row, rationale));
			}
			LoggingCategory.getKernel().info("[mdm-factory] Matching plan feedback for " + entityName + " (project " + pContext.projectCode + "): the model's answer: " + outcome.kind + " - " + line);
		}
		if (!revised.isEmpty())
		{
			final ObjectNode scratch = pEntitiesNode.objectNode();
			try
			{
				MatchPlanLlmSuggester.write(pContext.session, pContext.factory, revised, pContext.entityIdByName, scratch);
			}
			catch (final OperationException ex)
			{
				LoggingCategory.getKernel().warn("[mdm-factory] Matching plan feedback: the revised plan(s) could not be stored - " + ErrorText.of(ex));
			}
			for (final MatchPlanLlmPass.CreatedPlan plan : revised)
			{
				final ObjectNode entityNode = (ObjectNode) pEntitiesNode.get(plan.entityName);
				final String refused = scratch.path("refused").path(plan.entityName).asText("");
				final boolean stored = MatchPlanFeedbackPass.contains(scratch.path("refreshed"), plan.entityName) || MatchPlanFeedbackPass.contains(scratch.path("written"), plan.entityName);
				entityNode.put("stored", stored);
				if (!refused.isEmpty())
				{
					entityNode.put("revisedRefusal", TextLimits.truncate(refused, MatchPlanLlmSuggester.LAST_REFUSAL_MAX_LENGTH));
				}
			}
		}
	}

	private static boolean contains(final JsonNode pArray, final String pText)
	{
		if (pArray != null && pArray.isArray())
		{
			for (final JsonNode element : pArray)
			{
				if (pText.equals(element.asText()))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static void skip(final ObjectNode pEntityNode, final String pReason)
	{
		pEntityNode.put("ran", false);
		pEntityNode.put("reason", pReason);
	}

	/** The counts of the feedback in the step detail, flat for the ones a scenario reads, with the key sets and leaves from {@link SimulationFeedback.Result#toJson}. */
	private static void putFigures(final ObjectNode pEntityNode, final SimulationFeedback.Result pFeedback)
	{
		final ObjectNode figures = pFeedback.toJson();
		pEntityNode.put("pivots", pFeedback.pivots);
		pEntityNode.put("pairs", pFeedback.engine.pairs);
		pEntityNode.put("match", pFeedback.engine.match);
		pEntityNode.put("suspected", pFeedback.engine.suspected);
		pEntityNode.put("notMatched", pFeedback.engine.notMatched);
		pEntityNode.put("probed", pFeedback.probe.pairs);
		pEntityNode.put("suspectedOnKeys", pFeedback.suspectedOnKeys);
		pEntityNode.put("notMatchedOnKeys", pFeedback.notMatchedOnKeys);
		pEntityNode.put("matchOnKeys", pFeedback.matchOnKeys);
		pEntityNode.put("engineWithLeaf", pFeedback.engineWithLeaf);
		final ArrayNode keySets = pEntityNode.putArray("keySets");
		for (final JsonNode set : figures.path("keySets"))
		{
			if (set.path("countable").asBoolean(false) && set.path("engine").path("pairs").asInt(0) > 0)
			{
				final ObjectNode one = keySets.addObject();
				final StringBuilder fields = new StringBuilder();
				for (final JsonNode field : set.path("fields"))
				{
					fields.append(fields.length() == 0 ? "" : " + ").append(field.asText());
				}
				one.put("fields", fields.toString());
				one.put("safe", set.path("safe").asBoolean(false));
				one.set("engine", set.path("engine"));
				one.set("probe", set.path("probe"));
			}
		}
		pEntityNode.set("leaves", figures.path("leaves"));
	}
}
