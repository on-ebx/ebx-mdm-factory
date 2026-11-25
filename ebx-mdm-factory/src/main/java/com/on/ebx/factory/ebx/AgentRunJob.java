package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.agent.AgentTrigger;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * How an agent run is started (agentic framework lot, 2026-10-30): always as a background job of kind {@link AgentFramework#RUN_STEP} (resource DESIGN, the
 * language-model passes' own), so a model call of minutes never holds an HTTP thread or the job that raised an event.
 * <ul>
 * <li>{@link #start}: a person's click (REST {@code POST /agents/{code}/run}) - the job acts for that person.</li>
 * <li>{@link #fire}: a Factory event (end of a matching run, of a master or a staging load) - one job per enabled agent of the project listening to it, acting
 * for the person who switched the agent on (the server-signed activation, {@link AgentIdentity#actor}; production review 2026-11-12, F3-02).</li>
 * <li>{@link #tick}: the scheduled task ({@code scheduler.AgentScheduledTask}) - one job per enabled scheduled agent whose interval has passed.</li>
 * </ul>
 * The job re-checks, when it runs, that the person it acts for still holds the Factory right STEWARD ({@link AgentFramework#run}).
 * <p>
 * Production review of 2026-11-12 (F3-08): with {@link FeatureSwitches#agents()} false nothing here queues or runs an agent. A path started by a person answers
 * {@link FeatureSwitches#requireAgents()}; an event or a tick (no person) does nothing and writes ONE INFO line per start of the server ({@link #noteClosed}).
 */
public final class AgentRunJob
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private AgentRunJob()
	{
	}

	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final AgentFramework.Outcome outcome = AgentFramework.run(pJobSession, pFactory, pParams.path("agent").asText(""), pParams.path("via").asText("manual"));
			return "Agent " + pParams.path("agent").asText("") + ": " + outcome.sentence() + " (run " + outcome.runId + ")";
		}
	};

	/** Pure: the event a successful job of that kind raises, null for a kind no agent listens to. */
	static String eventAfter(final String pJobKind)
	{
		if (MatchingRunRun.STEP.equals(pJobKind))
		{
			return AgentTrigger.MATCHING_RUN_DONE;
		}
		if (MasterBuildRun.STEP.equals(pJobKind))
		{
			return AgentTrigger.MASTER_LOAD_DONE;
		}
		if (StagingLoadRun.STEP.equals(pJobKind))
		{
			return AgentTrigger.STAGING_LOAD_DONE;
		}
		return null;
	}

	/** Queues a run of {@code pAgentCode} for the caller ({@code pSession}'s user, or the {@link Actor} it carries). */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pAgentCode, final String pVia) throws OperationException
	{
		FeatureSwitches.requireAgents();
		final Adaptation definition = AgentFramework.definition(pFactory, pAgentCode);
		if (definition == null)
		{
			throw EngineRefusal.coded("missing.agent", "No agent " + pAgentCode + " is declared.", String.valueOf(pAgentCode));
		}
		if ("manual".equals(pVia))
		{
			// 1.4.2 (F3-08 point 1): a run by hand needs the agent switched on too (valid signed activation), whatever its autonomy - a disabled agent in ACT mode used to run
			final String problem = AgentIdentity.manualRunProblem(Boolean.TRUE.equals(definition.get(FactoryPaths.AgentDefinition.ENABLED)), AgentIdentity.actor(pFactory, definition));
			if (problem != null)
			{
				throw EngineRefusal.coded(problem, "Agent " + pAgentCode + " is not switched on: nobody signed its activation for its current settings. Switch it on with the enable route first.",
					String.valueOf(pAgentCode), String.valueOf(Actor.current(pSession).userId));
			}
		}
		final ObjectNode params = AgentRunJob.MAPPER.createObjectNode();
		params.put("agent", definition.getString(FactoryPaths.AgentDefinition.CODE));
		params.put("via", pVia);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, definition.getString(FactoryPaths.AgentDefinition.PROJECT), AgentFramework.RUN_STEP,
			params);
		final ObjectNode out = AgentRunJob.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		return out;
	}

	/**
	 * A Factory event for a project: queues one run per enabled agent of that project listening to it, each acting for its {@code enabledBy}. Never throws
	 * (an event comes from a job that must not fail for an agent); returns the agents queued.
	 */
	public static List<String> fire(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEvent)
	{
		final List<String> queued = new ArrayList<String>();
		if (pFactory == null || pProjectId == null || pEvent == null)
		{
			return queued;
		}
		if (!FeatureSwitches.agents())
		{
			AgentRunJob.noteClosed("an event");
			return queued;
		}
		try
		{
			for (final Adaptation definition : AgentRunJob.definitionsOf(pFactory, "./project='" + pProjectId.replace("'", "''") + "' and ./trigger='EVENT'"))
			{
				// F3-02: the person is the one the server signed, never the text of the row; a row nobody signed (or whose settings changed since) is not switched on
				final String actor = AgentIdentity.actor(pFactory, definition);
				if (AgentTrigger.wakesOn(definition.getString(FactoryPaths.AgentDefinition.TRIGGER), definition.getString(FactoryPaths.AgentDefinition.TRIGGER_EVENT),
					Boolean.TRUE.equals(definition.get(FactoryPaths.AgentDefinition.ENABLED)), actor, pEvent))
				{
					final String code = definition.getString(FactoryPaths.AgentDefinition.CODE);
					AgentRunJob.queueFor(pSession, pFactory, code, actor.trim(), "event " + pEvent);
					queued.add(code);
				}
				else if (Boolean.TRUE.equals(definition.get(FactoryPaths.AgentDefinition.ENABLED)) && actor == null)
				{
					AgentRunJob.noteUnsigned(definition.getString(FactoryPaths.AgentDefinition.CODE));
				}
			}
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] agents: event " + pEvent + " of project " + pProjectId + " not delivered: " + ex);
		}
		return queued;
	}

	/** The scheduled task's tick: queues every enabled scheduled agent that is due. Never throws; returns the agents queued. */
	public static List<String> tick(final Session pSession, final Adaptation pFactory, final long pNowMillis)
	{
		final List<String> queued = new ArrayList<String>();
		if (!FeatureSwitches.agents())
		{
			AgentRunJob.noteClosed("the schedule");
			return queued;
		}
		try
		{
			for (final Adaptation definition : AgentRunJob.definitionsOf(pFactory, "./trigger='SCHEDULE' and ./enabled=true"))
			{
				final Object minutes = definition.get(FactoryPaths.AgentDefinition.SCHEDULE_MINUTES);
				final Date last = definition.getDate(FactoryPaths.AgentDefinition.LAST_RUN_AT);
				final String actor = AgentIdentity.actor(pFactory, definition); // F3-02: signed by the server, not the text of the row
				if (actor == null)
				{
					AgentRunJob.noteUnsigned(definition.getString(FactoryPaths.AgentDefinition.CODE));
					continue;
				}
				if (AgentTrigger.isDue(definition.getString(FactoryPaths.AgentDefinition.TRIGGER), minutes instanceof Integer ? (Integer) minutes : null, true, actor,
					last == null ? 0L : last.getTime(), pNowMillis))
				{
					final String code = definition.getString(FactoryPaths.AgentDefinition.CODE);
					// stamped before queueing: a slow queue never makes the next tick queue the same agent again
					AgentFramework.touchLastRun(pSession, pFactory, definition);
					AgentRunJob.queueFor(pSession, pFactory, code, actor.trim(), "schedule");
					queued.add(code);
				}
			}
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] agents: schedule tick failed: " + ex);
		}
		return queued;
	}

	private static final AtomicBoolean CLOSED_NOTED = new AtomicBoolean(false);
	private static final Set<String> UNSIGNED_NOTED = new HashSet<String>();

	/** One INFO line per start of the server, not one per tick or event: the agents are closed ({@link FeatureSwitches#AGENTS_PROPERTY}) and {@code pWho} did nothing. */
	static boolean noteClosed(final String pWho)
	{
		if (!AgentRunJob.CLOSED_NOTED.compareAndSet(false, true))
		{
			return false;
		}
		LoggingCategory.getKernel().info("[mdm-factory] agents: closed on this instance (" + FeatureSwitches.AGENTS_PROPERTY + " is not true): " + pWho
			+ " runs no agent; this line is written once per start");
		return true;
	}

	/** Test hook: forgets what was already said, as a new start of the server would. */
	static void forgetNotes()
	{
		AgentRunJob.CLOSED_NOTED.set(false);
		synchronized (AgentRunJob.UNSIGNED_NOTED)
		{
			AgentRunJob.UNSIGNED_NOTED.clear();
		}
	}

	/** An agent marked enabled whose activation nobody signed (or whose settings changed since): treated as not switched on, said once per start. */
	static boolean noteUnsigned(final String pAgentCode)
	{
		synchronized (AgentRunJob.UNSIGNED_NOTED)
		{
			if (!AgentRunJob.UNSIGNED_NOTED.add(String.valueOf(pAgentCode)))
			{
				return false;
			}
		}
		LoggingCategory.getKernel().warn("[mdm-factory] agents: agent " + pAgentCode + " is marked enabled but its activation is not signed for its current settings: it is treated as not"
			+ " switched on. Switch it on again with POST /agents/" + pAgentCode + "/enable.");
		return true;
	}

	private static void queueFor(final Session pSession, final Adaptation pFactory, final String pCode, final String pUser, final String pVia)
	{
		try
		{
			Actor.as(pUser, "agent " + pCode + " · " + pVia, () -> AgentRunJob.start(pSession, pFactory, pCode, pVia));
		}
		catch (final OperationException | RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] agent " + pCode + " (" + pVia + ") not queued: " + ex.getMessage());
		}
	}

	private static List<Adaptation> definitionsOf(final Adaptation pFactory, final String pPredicate)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.AGENT_DEFINITION).createRequestResult(pPredicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}
}
