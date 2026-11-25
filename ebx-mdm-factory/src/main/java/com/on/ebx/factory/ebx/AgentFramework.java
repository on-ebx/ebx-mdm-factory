package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.AgentLoop;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.agent.AgentDecisions;
import com.on.ebx.factory.agent.AgentPolicy;
import com.on.ebx.factory.agent.AgentPrompts;
import com.on.ebx.factory.agent.AgentTools;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.SessionPermissions;
import com.orchestranetworks.service.UserReference;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The runner of the agents declared in {@code AgentDefinition} (agentic framework lot, 2026-10-30, {@code docs/AGENTS.md}).
 * <p>
 * One run: the behaviour of the agent ({@link Behaviour}: {@link StewardMatchAgent}, {@link QualityFixAgent}) gathers what the agent decides on, as the
 * ACTING USER sees it; the model is asked ONCE through the Factory's language model path ({@link AgentRuntime#ask}, the Connectors contract {@code llm.ask};
 * else {@link AgentRuntime#run} with one local tool through {@code AgentLlmConnector}), which records the {@code AgentRun} row and the model's turn; each
 * decision then goes through {@link AgentPolicy}: refused (a tool off the allow-list, an action the acting user's EBX permissions do not allow), applied now
 * (autonomy ACT at or above the threshold), or written to {@code AgentProposal} and offered to the agent's owner profile by the escalation workflow
 * ({@link AgentEscalations}). The {@code AgentRun} row then carries the inputs, the decisions with their dispositions, the lowest confidence and the counts.
 * <p>
 * <b>Whose permissions.</b> A run executes as a background job (system session) FOR its acting user: the person who clicked, or for an event or a schedule
 * the person who switched the agent on ({@code enabledBy}), who must still hold the Factory right STEWARD. Every read shown to the model is filtered by that
 * user's EBX permissions ({@link Repository#createSessionPermissionsForUser}, public API), and every action is checked against them before it is applied: a
 * suspect pair needs both records read-write, a correction needs the field read-write. A call to an EBX MCP server runs with a token minted for that user
 * ({@link SessionToken}, the documented exception of the module, OPERATIONS.md 7.4) - and is refused when only the stored agent account could make it. The
 * run is identified as the agent's own: the {@link Actor} of the work says {@code agent <code> run <id>}, which the {@code AgentRun}, {@code PipelineStep}
 * and the procedures' execution information carry.
 * <p>
 * A Connectors flow on the allow-list ({@code connectors:<flow>}) is never run by the job: the in-JVM bridge of ebx-connectors runs as the session it is
 * given, which here is the system session, so such a decision is only ever a suggestion. A decision on a proposal ({@link #decideProposal}) made from a
 * person's own session (REST) applies with that session.
 */
public final class AgentFramework
{
	/** {@code AgentRun.phase} of a run of a declared agent: this prefix and the agent's code. */
	public static final String PHASE_PREFIX = "AGENT:";
	/** The background job kind of a run. */
	public static final String RUN_STEP = "AGENT_RUN";
	/** The action of a generic MCP call, available to every behaviour: arguments {@code {"tool": "<server>/<tool>", "args": {...}}}. */
	public static final String CALL = "CALL";

	static final int ANSWER_MAX_TOKENS = 4000;
	static final int ANSWER_TIMEOUT_MS = 240000;
	static final int INPUTS_MAX_LENGTH = 100000;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private AgentFramework()
	{
	}

	// ------------------------------------------------------------------------------------------------------- behaviours

	/** What an agent reads, what it may decide and what an accepted decision does. */
	interface Behaviour
	{
		/** The actions the model may answer besides {@link AgentDecisions#ABSTAIN} and {@link AgentFramework#CALL}. */
		List<String> actions();

		/** The allow-list entry an action of this behaviour goes through. */
		String tool(String pAction);

		/** The items to decide on, as the acting user may see them. */
		Gathered gather(Run pRun) throws OperationException;

		String system(Run pRun);

		/** The full arguments of a decision: the fixed arguments of its item plus what the model added (never overriding the item's own). */
		ObjectNode arguments(ObjectNode pItemArguments, AgentDecisions.Decision pDecision);

		/** A short readable target for {@code AgentProposal.target}. */
		String describe(ObjectNode pArguments);

		/** Whether {@code pPermissions} allow the action. */
		boolean permitted(Run pRun, SessionPermissions pPermissions, String pAction, ObjectNode pArguments);

		/** Applies the action; returns what was done, throws when it could not be. */
		String apply(Run pRun, Session pSession, String pAction, ObjectNode pArguments) throws OperationException;
	}

	/** What a behaviour gathered: the items shown to the model ({@code inputs}, each with an {@code id}) and the fixed arguments of each item, by id. */
	static final class Gathered
	{
		final ArrayNode inputs = AgentFramework.MAPPER.createArrayNode();
		final Map<String, ObjectNode> arguments = new LinkedHashMap<String, ObjectNode>();
		/** Items the acting user may not see, left out (counted for the outcome). */
		int hidden;

		/** Adds one item: {@code pShown} gets the id; {@code pArguments} stay server-side. */
		void add(final ObjectNode pShown, final ObjectNode pArguments)
		{
			final String id = "I" + (this.arguments.size() + 1);
			final ObjectNode item = AgentFramework.MAPPER.createObjectNode();
			item.put("id", id);
			item.setAll(pShown);
			this.inputs.add(item);
			this.arguments.put(id, pArguments);
		}
	}

	static Behaviour behaviour(final String pKind) throws OperationException
	{
		if ("STEWARD_MATCH".equals(pKind))
		{
			return new StewardMatchAgent();
		}
		if ("QUALITY_FIX".equals(pKind))
		{
			return new QualityFixAgent();
		}
		throw EngineRefusal.coded("invalid.agentKind", "Unknown agent behaviour " + pKind + ".", String.valueOf(pKind));
	}

	// ------------------------------------------------------------------------------------------------------------ a run

	/** Everything one run knows. */
	static final class Run
	{
		final Session session;
		final Adaptation factory;
		final Adaptation definition;
		final Adaptation project;
		final String agentCode;
		final String projectCode;
		final String projectId;
		final String actingFor;
		final String via;
		final SessionPermissions permissions;
		final AgentPrompts.Sharing sharing;
		final LlmDataPolicy policy;
		final AgentPolicy.Autonomy autonomy;
		final AgentTools tools;
		final int maxItems;

		Run(final Session pSession, final Adaptation pFactory, final Adaptation pDefinition, final Adaptation pProject, final String pActingFor, final String pVia)
		{
			this.session = pSession;
			this.factory = pFactory;
			this.definition = pDefinition;
			this.project = pProject;
			this.agentCode = pDefinition.getString(FactoryPaths.AgentDefinition.CODE);
			this.projectCode = pProject.getString(FactoryPaths.Project.CODE);
			this.projectId = pProject.getOccurrencePrimaryKey().format();
			this.actingFor = pActingFor;
			this.via = pVia;
			this.permissions = pFactory.getHome().getRepository().createSessionPermissionsForUser(Profile.forUser(pActingFor));
			this.policy = LlmDataPolicy.of(pFactory);
			final LlmDataPolicy.Level level = this.policy.levelFor(pProject);
			this.sharing = level == LlmDataPolicy.Level.SAMPLE_VALUES ? AgentPrompts.Sharing.VALUES
				: level == LlmDataPolicy.Level.SHAPES_ONLY ? AgentPrompts.Sharing.SHAPES : AgentPrompts.Sharing.NONE;
			this.autonomy = AgentPolicy.Autonomy.parse(pDefinition.getString(FactoryPaths.AgentDefinition.AUTONOMY));
			this.tools = AgentTools.of(pDefinition.getList(FactoryPaths.AgentDefinition.ALLOWED_TOOLS));
			final Object max = pDefinition.get(FactoryPaths.AgentDefinition.MAX_ITEMS);
			final int asked = max instanceof Integer ? ((Integer) max).intValue() : 20;
			this.maxItems = asked < 1 ? 1 : asked > 100 ? 100 : asked;
		}

		String text(final com.orchestranetworks.schema.Path pPath)
		{
			final String value = this.definition.getString(pPath);
			return value == null ? "" : value.trim();
		}

		Integer threshold()
		{
			final Object value = this.definition.get(FactoryPaths.AgentDefinition.CONFIDENCE_THRESHOLD);
			return value instanceof Integer ? (Integer) value : null;
		}
	}

	/** What one run did, for the job's end message and the REST answer. */
	public static final class Outcome
	{
		public String runId;
		public int items;
		public int hidden;
		public int decisions;
		public int applied;
		public int escalated;
		public int refused;
		public int failed;
		public int abstained;
		public String escalation;
		public String note;

		public ObjectNode toJson()
		{
			final ObjectNode out = AgentFramework.MAPPER.createObjectNode();
			out.put("runId", this.runId);
			out.put("items", this.items);
			out.put("hidden", this.hidden);
			out.put("decisions", this.decisions);
			out.put("applied", this.applied);
			out.put("escalated", this.escalated);
			out.put("refused", this.refused);
			out.put("failed", this.failed);
			out.put("abstained", this.abstained);
			if (this.escalation != null)
			{
				out.put("escalation", this.escalation);
			}
			if (this.note != null)
			{
				out.put("note", this.note);
			}
			return out;
		}

		public String sentence()
		{
			return this.items + " item(s), " + this.decisions + " decision(s): " + this.applied + " applied, " + this.escalated + " sent for review, " + this.refused
				+ " refused, " + this.failed + " failed, " + this.abstained + " abstained" + (this.note == null ? "" : " - " + this.note);
		}
	}

	/** The definition of that code, null when there is none. */
	static Adaptation definition(final Adaptation pFactory, final String pAgentCode)
	{
		if (pAgentCode == null || pAgentCode.trim().isEmpty())
		{
			return null;
		}
		final AdaptationTable table = pFactory.getTable(FactoryPaths.AGENT_DEFINITION);
		return table.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { pAgentCode.trim() }));
	}

	/**
	 * Runs one agent for the actor of the current thread ({@link Actor#current}). Called by the job handler ({@link AgentRunJob}) and by the workflow bean.
	 *
	 * @param pVia {@code manual}, {@code event <name>}, {@code schedule} or {@code workflow <key>}
	 */
	public static Outcome run(final Session pSession, final Adaptation pFactory, final String pAgentCode, final String pVia) throws OperationException
	{
		FeatureSwitches.requireAgents(); // production review 2026-11-12 (F3-08): closed = no run, whatever the path (hand, event, schedule, job already queued)
		final Adaptation definition = AgentFramework.definition(pFactory, pAgentCode);
		if (definition == null)
		{
			throw EngineRefusal.coded("missing.agent", "No agent " + pAgentCode + " is declared.", String.valueOf(pAgentCode));
		}
		final String projectKey = definition.getString(FactoryPaths.AgentDefinition.PROJECT);
		final Adaptation project = projectKey == null ? null : pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectKey));
		if (project == null)
		{
			throw EngineRefusal.coded("missing.agentProject", "Agent " + pAgentCode + " names no existing project.", String.valueOf(pAgentCode));
		}
		final String actingFor = Actor.current(pSession).userId;
		final Repository repository = pFactory.getHome().getRepository();
		if (actingFor == null || actingFor.trim().isEmpty() || !FactoryRight.allows(FactoryAccess.grantedTo(repository, Profile.forUser(actingFor)), FactoryRight.STEWARD))
		{
			throw EngineRefusal.coded("conflict.agentActorRight", "Agent " + pAgentCode + " acts for " + actingFor + ", who does not hold the MDM Factory right STEWARD; refused.",
				String.valueOf(actingFor));
		}
		final Behaviour behaviour = AgentFramework.behaviour(definition.getString(FactoryPaths.AgentDefinition.KIND));
		final Run run = new Run(pSession, pFactory, definition, project, actingFor, pVia);
		// F3-02: an event or a schedule acts for the person the SERVER signed at the activation; and that person must still be able to write the project
		final String problem = AgentIdentity.activationProblem(pVia, AgentIdentity.actor(pFactory, definition), actingFor);
		if (problem != null)
		{
			throw EngineRefusal.coded(problem, "Agent " + pAgentCode + " is not switched on for " + actingFor + " (no signed activation for its current settings); refused.",
				String.valueOf(pAgentCode), String.valueOf(actingFor));
		}
		if (AgentIdentity.viaNeedsActivation(pVia))
		{
			AgentIdentity.requireProjectWritable(run.permissions, project, actingFor, pAgentCode);
		}
		return Actor.as(actingFor, "agent " + run.agentCode + " · " + pVia, () -> AgentFramework.execute(run, behaviour));
	}

	private static Outcome execute(final Run pRun, final Behaviour pBehaviour) throws OperationException
	{
		final Outcome outcome = new Outcome();
		final Gathered gathered = pBehaviour.gather(pRun);
		outcome.items = gathered.arguments.size();
		outcome.hidden = gathered.hidden;
		final String phase = AgentFramework.PHASE_PREFIX + pRun.agentCode;
		final String goal = pRun.text(FactoryPaths.AgentDefinition.PURPOSE);
		AgentFramework.touchLastRun(pRun);
		if (gathered.arguments.isEmpty())
		{
			// nothing to decide: the run is still recorded (who, when, why nothing), without asking the model
			outcome.runId = AgentRuntime.begin(pRun.session, pRun.factory, pRun.projectId, phase, goal, "", null);
			outcome.note = "nothing to decide" + (gathered.hidden > 0 ? " (" + gathered.hidden + " item(s) hidden by the permissions of " + pRun.actingFor + ")" : "");
			AgentRuntime.end(pRun.session, pRun.factory, outcome.runId, AgentLoop.Status.DONE, 0, BigDecimal.ZERO, BigDecimal.ZERO, outcome.note, null);
			AgentFramework.recordRun(pRun, outcome, gathered, AgentFramework.MAPPER.createArrayNode(), null);
			return outcome;
		}
		final String system = pBehaviour.system(pRun);
		final String task = AgentPrompts.task("ITEMS TO DECIDE", gathered.inputs);
		final String[] answer = new String[1];
		AgentRuntime.forgetLastBegunRun();
		try
		{
			outcome.runId = AgentFramework.ask(pRun, phase, goal, system, task, answer);
		}
		catch (final OperationException | RuntimeException ex)
		{
			// AgentRuntime already ended its row FAILED with the reason: give it the agent's part too, so the run is found by agent like any other
			outcome.runId = AgentRuntime.lastBegunRunId();
			if (outcome.runId != null)
			{
				AgentFramework.recordRun(pRun, outcome, gathered, AgentFramework.MAPPER.createArrayNode(), null);
			}
			throw ex;
		}
		finally
		{
			AgentRuntime.forgetLastBegunRun();
		}
		final AgentDecisions.Parsed parsed = AgentDecisions.parse(answer[0]);
		if (parsed.problem != null)
		{
			outcome.note = "the model's answer could not be read: " + parsed.problem;
		}
		final ArrayNode decided = AgentFramework.MAPPER.createArrayNode();
		final List<String> pending = new ArrayList<String>();
		int stepIndex = 100; // after the model's own trace rows
		for (final AgentDecisions.Decision decision : parsed.decisions)
		{
			outcome.decisions++;
			final ObjectNode entry = decision.toJson();
			decided.add(entry);
			final ObjectNode itemArguments = gathered.arguments.get(decision.target);
			if (itemArguments == null)
			{
				entry.put("disposition", AgentPolicy.Disposition.REFUSED.name());
				entry.put("why", "unknown target");
				outcome.refused++;
				continue;
			}
			final boolean call = AgentFramework.CALL.equals(decision.action);
			if (!decision.abstains() && !call && !pBehaviour.actions().contains(decision.action))
			{
				entry.put("disposition", AgentPolicy.Disposition.REFUSED.name());
				entry.put("why", "unknown action");
				outcome.refused++;
				continue;
			}
			final ObjectNode arguments = pBehaviour.arguments(itemArguments, decision);
			final String tool = call ? decision.arguments.path("tool").asText("") : pBehaviour.tool(decision.action);
			final boolean allowed = decision.abstains() || pRun.tools.allows(tool);
			final boolean permitted = decision.abstains() || !allowed
				|| (call ? AgentFramework.callPermitted(pRun, tool) : pBehaviour.permitted(pRun, pRun.permissions, decision.action, arguments));
			AgentPolicy.Disposition disposition = AgentPolicy.decide(pRun.autonomy, pRun.threshold(), Integer.valueOf(decision.confidence), decision.abstains(), allowed,
				permitted);
			if (call && tool.startsWith("connectors:") && disposition == AgentPolicy.Disposition.APPLY)
			{
				disposition = AgentPolicy.Disposition.SUGGEST; // a Connectors flow never runs from the job (class Javadoc)
			}
			entry.put("tool", tool);
			entry.put("disposition", disposition.name());
			if (!allowed)
			{
				entry.put("why", "tool not on the allow-list");
			}
			else if (!permitted)
			{
				entry.put("why", "not allowed by the EBX permissions of " + pRun.actingFor);
			}
			switch (disposition)
			{
			case NO_ACTION:
				outcome.abstained++;
				break;
			case REFUSED:
				outcome.refused++;
				AgentFramework.writeProposal(pRun, outcome.runId, tool, pBehaviour.describe(arguments), decision, arguments, "REJECTED", false, entry.path("why").asText(""));
				break;
			case APPLY:
				stepIndex++;
				final long started = System.currentTimeMillis();
				String message;
				boolean failed = false;
				try
				{
					message = call ? AgentFramework.applyCall(pRun, arguments) : pBehaviour.apply(pRun, pRun.session, decision.action, arguments);
					outcome.applied++;
				}
				catch (final OperationException | RuntimeException ex)
				{
					message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
					failed = true;
					outcome.failed++;
				}
				entry.put("applyMessage", message);
				AgentRuntime.writeStep(pRun.session, pRun.factory, outcome.runId, stepIndex, "TOOL", "factory", tool, arguments.toString(), message, failed,
					System.currentTimeMillis() - started);
				AgentFramework.writeProposal(pRun, outcome.runId, tool, pBehaviour.describe(arguments), decision, arguments, failed ? "FAILED" : "APPLIED", false, message);
				break;
			default:
				outcome.escalated++;
				pending.add(AgentFramework.writeProposal(pRun, outcome.runId, tool, pBehaviour.describe(arguments), decision, arguments, "PROPOSED",
					disposition == AgentPolicy.Disposition.AWAIT_APPROVAL, null));
				break;
			}
		}
		if (!pending.isEmpty())
		{
			try
			{
				outcome.escalation = AgentEscalations.launch(pRun.session, pRun.factory, outcome.runId, pRun.agentCode, pRun.text(FactoryPaths.AgentDefinition.OWNER_PROFILE),
					pending.size());
			}
			catch (final OperationException | RuntimeException ex)
			{
				// the proposals stay in AgentProposal, decidable from there (REST); the run is not failed for it
				outcome.note = (outcome.note == null ? "" : outcome.note + "; ") + "review task not opened (" + ex.getMessage() + "): the proposals wait in AgentProposal";
				LoggingCategory.getKernel().warn("[mdm-factory] agent " + pRun.agentCode + " run " + outcome.runId + ": " + outcome.note);
			}
		}
		AgentFramework.recordRun(pRun, outcome, gathered, decided, parsed.lowestConfidence());
		return outcome;
	}

	/** One question to the model; the answer's text in {@code pAnswer[0]}; the {@code AgentRun} id returned. */
	private static String ask(final Run pRun, final String pPhase, final String pGoal, final String pSystem, final String pTask, final String[] pAnswer)
		throws OperationException
	{
		if (AgentRuntime.canAsk(pRun.session))
		{
			final AgentRuntime.AskOutcome asked = AgentRuntime.ask(pRun.session, pRun.factory, pRun.projectId, pPhase, pGoal, pSystem, pTask,
				AgentFramework.ANSWER_MAX_TOKENS, true, AgentFramework.ANSWER_TIMEOUT_MS);
			pAnswer[0] = asked.truncated ? null : asked.text;
			return asked.runId;
		}
		// the earlier way (AgentLlmConnector): one local tool receives the decisions
		final String[] captured = new String[1];
		final JsonNode schema;
		try
		{
			schema = AgentFramework.MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"decisions\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
				+ "\"target\":{\"type\":\"string\"},\"action\":{\"type\":\"string\"},\"arguments\":{\"type\":\"object\"},\"confidence\":{\"type\":\"integer\"},"
				+ "\"rationale\":{\"type\":\"string\"}},\"required\":[\"target\",\"action\",\"confidence\"]}}},\"required\":[\"decisions\"]}");
		}
		catch (final java.io.IOException ex)
		{
			throw new IllegalStateException(ex);
		}
		final LocalTool tool = new LocalTool("submit_decisions", "Submit the decisions, all at once.", schema, new LocalTool.Handler()
		{
			@Override
			public LocalTool.Result handle(final JsonNode pArguments)
			{
				captured[0] = pArguments.toString();
				return LocalTool.Result.ok("received");
			}
		});
		final ToolSet tools;
		try
		{
			tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), Collections.singletonMap("factory", new ToolSet.LocalGroup(Collections.singletonList(tool))));
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.agentTools", "could not build the local tool set of agent " + pRun.agentCode + ": " + ex.getMessage(), ex);
		}
		final AgentRuntime.Outcome outcome = AgentRuntime.run(pRun.session, pRun.factory, pRun.projectId, pPhase, pGoal,
			pSystem + " Call submit_decisions exactly once with that object.", pTask, tools);
		pAnswer[0] = captured[0];
		return outcome.runId;
	}

	// --------------------------------------------------------------------------------------------- generic MCP calls

	/** Pure: the server and the tool of a {@code <server>/<tool>} entry, null when it is not one. */
	static String[] splitTool(final String pTool)
	{
		final int slash = pTool == null ? -1 : pTool.indexOf('/');
		return slash <= 0 || slash == pTool.length() - 1 || pTool.startsWith("factory:") || pTool.startsWith("connectors:") ? null
			: new String[] { pTool.substring(0, slash), pTool.substring(slash + 1) };
	}

	/** A generic call is permitted when it can run as the acting user: a Connectors flow is only suggested, an EBX server needs a minted token. */
	private static boolean callPermitted(final Run pRun, final String pTool)
	{
		if (pTool.startsWith("connectors:"))
		{
			return true; // allowed as a suggestion only (see execute)
		}
		final String[] split = AgentFramework.splitTool(pTool);
		return split != null && !McpServers.CONNECTORS.equals(split[0]) && "session".equals(McpServers.mode(pRun.session, pRun.factory, split[0]));
	}

	private static String applyCall(final Run pRun, final ObjectNode pArguments) throws OperationException
	{
		final String[] split = AgentFramework.splitTool(pArguments.path("tool").asText(""));
		if (split == null)
		{
			throw EngineRefusal.coded("invalid.agentCall", "Not a <server>/<tool> call: " + pArguments.path("tool").asText(""), pArguments.path("tool").asText(""));
		}
		final JsonNode args = pArguments.get("args");
		try
		{
			final McpClient client = McpServers.client(pRun.session, pRun.factory, split[0]);
			final McpClient.ToolResult result = client.callTool(split[1], args != null && args.isObject() ? (ObjectNode) args : AgentFramework.MAPPER.createObjectNode());
			if (result.error)
			{
				throw EngineRefusal.coded("external.agentCall", split[0] + "/" + split[1] + " refused: " + result.text, split[0] + "/" + split[1]);
			}
			return result.text == null ? "done" : result.text.length() > 2000 ? result.text.substring(0, 2000) : result.text;
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.agentCall", split[0] + "/" + split[1] + " failed: " + ex.getMessage(), ex, split[0] + "/" + split[1]);
		}
	}

	// -------------------------------------------------------------------------------------------- proposals and review

	/**
	 * Decides one {@code AgentProposal} (REST, or the escalation workflow). Accepting a line whose agent may act ({@code applyAfterApproval}) applies it with
	 * {@code pSession} and the permissions of {@code pDecidedBy}; a suggestion only becomes ACCEPTED (the person acts). A line that is no longer PROPOSED is
	 * refused.
	 */
	public static ObjectNode decideProposal(final Session pSession, final Adaptation pFactory, final String pProposalId, final boolean pAccept, final String pDecidedBy)
		throws OperationException
	{
		FeatureSwitches.requireAgents(); // production review 2026-11-12 (F3-08): no decision, no application while the agents are closed
		final AdaptationTable proposals = pFactory.getTable(FactoryPaths.AGENT_PROPOSAL);
		final Adaptation row = pProposalId == null ? null : proposals.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProposalId.trim()));
		if (row == null)
		{
			throw EngineRefusal.coded("missing.agentProposal", "No agent proposal " + pProposalId + ".", String.valueOf(pProposalId));
		}
		final String status = row.getString(FactoryPaths.AgentProposal.STATUS);
		if (!"PROPOSED".equals(status) && !("ACCEPTED".equals(status) && pAccept && Boolean.TRUE.equals(row.get(FactoryPaths.AgentProposal.APPLY_AFTER_APPROVAL))))
		{
			throw EngineRefusal.coded("conflict.agentProposalDecided", "Agent proposal " + pProposalId + " is already " + status + ".", String.valueOf(pProposalId),
				String.valueOf(status));
		}
		final ObjectNode out = AgentFramework.MAPPER.createObjectNode();
		out.put("proposal", pProposalId);
		if (!pAccept)
		{
			AgentFramework.setProposal(pSession, pFactory, row, "REJECTED", pDecidedBy, null);
			out.put("status", "REJECTED");
			return out;
		}
		if (!Boolean.TRUE.equals(row.get(FactoryPaths.AgentProposal.APPLY_AFTER_APPROVAL)))
		{
			AgentFramework.setProposal(pSession, pFactory, row, "ACCEPTED", pDecidedBy, "a suggestion: the agent does not act on it, the reviewer does");
			out.put("status", "ACCEPTED");
			return out;
		}
		final String agentCode = row.getString(FactoryPaths.AgentProposal.AGENT);
		final Adaptation definition = AgentFramework.definition(pFactory, agentCode);
		final String projectKey = definition == null ? null : definition.getString(FactoryPaths.AgentDefinition.PROJECT);
		final Adaptation project = projectKey == null ? null : pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectKey));
		if (definition == null || project == null)
		{
			throw EngineRefusal.coded("missing.agent", "No agent " + agentCode + " is declared.", String.valueOf(agentCode));
		}
		final Behaviour behaviour = AgentFramework.behaviour(definition.getString(FactoryPaths.AgentDefinition.KIND));
		final Run run = new Run(pSession, pFactory, definition, project, pDecidedBy, "review");
		final String action = row.getString(FactoryPaths.AgentProposal.ACTION);
		final ObjectNode arguments = AgentFramework.json(row.getString(FactoryPaths.AgentProposal.ARGUMENTS));
		final String tool = row.getString(FactoryPaths.AgentProposal.TOOL);
		final boolean call = AgentFramework.CALL.equals(action);
		// the reviewer's own permissions (a person's session: its own; the workflow: the work item's user)
		final SessionPermissions permissions = pSession != null && !pSession.isSystemUser() ? pSession.getPermissions() : run.permissions;
		final boolean permitted = call ? !tool.startsWith("connectors:") && AgentFramework.callPermitted(run, tool) : behaviour.permitted(run, permissions, action, arguments);
		if (!run.tools.allows(tool) || !permitted)
		{
			final String why = !run.tools.allows(tool) ? "the tool " + tool + " is no longer on the agent's allow-list" : "not allowed by the EBX permissions of " + pDecidedBy;
			AgentFramework.setProposal(pSession, pFactory, row, "FAILED", pDecidedBy, why);
			out.put("status", "FAILED");
			out.put("message", why);
			return out;
		}
		String message;
		String result;
		try
		{
			message = Actor.as(pDecidedBy, "agent " + agentCode + " · review", () -> call ? AgentFramework.applyCall(run, arguments) : behaviour.apply(run, pSession, action, arguments));
			result = "APPLIED";
		}
		catch (final OperationException | RuntimeException ex)
		{
			message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			result = "FAILED";
		}
		AgentFramework.setProposal(pSession, pFactory, row, result, pDecidedBy, message);
		out.put("status", result);
		out.put("message", message);
		return out;
	}

	/** The PROPOSED lines of a run, by id. */
	static List<String> pendingOf(final Adaptation pFactory, final String pRunId)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.AGENT_PROPOSAL).createRequestResult("./run='" + pRunId.replace("'", "''") + "' and ./status='PROPOSED'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** The lines of a run a reviewer set to ACCEPTED by hand and the agent may act on (the escalation workflow applies them once the task is accepted). */
	static List<String> acceptedToApplyOf(final Adaptation pFactory, final String pRunId)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.AGENT_PROPOSAL)
			.createRequestResult("./run='" + pRunId.replace("'", "''") + "' and ./status='ACCEPTED' and ./applyAfterApproval=true and osd:is-null(./decidedAt)");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	// --------------------------------------------------------------------------------------------------------- writes

	private static String writeProposal(final Run pRun, final String pRunId, final String pTool, final String pTarget, final AgentDecisions.Decision pDecision,
		final ObjectNode pArguments, final String pStatus, final boolean pApplyAfterApproval, final String pMessage) throws OperationException
	{
		final AdaptationTable table = pRun.factory.getTable(FactoryPaths.AGENT_PROPOSAL);
		final String[] id = new String[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pRun.session, pRun.factory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true); // the Factory's own bookkeeping, like AgentRun
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
				value.setValue(pRunId, FactoryPaths.AgentProposal.RUN);
				value.setValue(pRun.agentCode, FactoryPaths.AgentProposal.AGENT);
				value.setValue(pRun.projectId, FactoryPaths.AgentProposal.PROJECT);
				value.setValue(TextLimits.truncate(pTool, 200), FactoryPaths.AgentProposal.TOOL);
				value.setValue(TextLimits.truncate(pTarget, 400), FactoryPaths.AgentProposal.TARGET);
				value.setValue(TextLimits.truncate(pDecision.action, 80), FactoryPaths.AgentProposal.ACTION);
				value.setValue(TextLimits.truncate(pArguments.toString(), 20000), FactoryPaths.AgentProposal.ARGUMENTS);
				value.setValue(Integer.valueOf(pDecision.confidence), FactoryPaths.AgentProposal.CONFIDENCE);
				value.setValue(TextLimits.truncate(pDecision.rationale, 4000), FactoryPaths.AgentProposal.RATIONALE);
				value.setValue(pStatus, FactoryPaths.AgentProposal.STATUS);
				value.setValue(Boolean.valueOf(pApplyAfterApproval), FactoryPaths.AgentProposal.APPLY_AFTER_APPROVAL);
				if (!"PROPOSED".equals(pStatus))
				{
					value.setValue("agent " + pRun.agentCode + " for " + pRun.actingFor, FactoryPaths.AgentProposal.DECIDED_BY);
					value.setValue(new Date(), FactoryPaths.AgentProposal.DECIDED_AT);
				}
				if (pMessage != null)
				{
					value.setValue(TextLimits.truncate(pMessage, 2000), FactoryPaths.AgentProposal.APPLY_MESSAGE);
				}
				id[0] = pContext.doCreateOccurrence(value, table).getOccurrencePrimaryKey().format();
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.agentProposalWrite", "Could not record a proposal of agent " + pRun.agentCode + ": "
				+ result.getExceptionFullMessage(Locale.ENGLISH), pRun.agentCode);
		}
		return id[0];
	}

	private static void setProposal(final Session pSession, final Adaptation pFactory, final Adaptation pRow, final String pStatus, final String pDecidedBy,
		final String pMessage) throws OperationException
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final ValueContextForUpdate value = pContext.getContext(pRow.getAdaptationName());
				value.setValue(pStatus, FactoryPaths.AgentProposal.STATUS);
				value.setValue(TextLimits.truncate(pDecidedBy, 120), FactoryPaths.AgentProposal.DECIDED_BY);
				value.setValue(new Date(), FactoryPaths.AgentProposal.DECIDED_AT);
				if (pMessage != null)
				{
					value.setValue(TextLimits.truncate(pMessage, 2000), FactoryPaths.AgentProposal.APPLY_MESSAGE);
				}
				pContext.doModifyContent(pRow, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.agentProposalWrite", "Could not record the decision on agent proposal " + pRow.getOccurrencePrimaryKey().format() + ": "
				+ result.getExceptionFullMessage(Locale.ENGLISH), pRow.getOccurrencePrimaryKey().format());
		}
	}

	/** The agent's part of the {@code AgentRun} row (the model's part was written by {@link AgentRuntime}). Never throws: the run itself is done. */
	private static void recordRun(final Run pRun, final Outcome pOutcome, final Gathered pGathered, final ArrayNode pDecided, final Integer pLowest)
	{
		final AdaptationTable runs = pRun.factory.getTable(FactoryPaths.AGENT_RUN);
		final ProcedureResult result = ProgrammaticService.createForSession(pRun.session, pRun.factory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final Adaptation row = runs.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pOutcome.runId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pRun.agentCode, FactoryPaths.AgentRun.AGENT);
				value.setValue(TextLimits.truncate(pRun.actingFor, 120), FactoryPaths.AgentRun.ACTING_FOR);
				value.setValue(TextLimits.truncate(pRun.via, 200), FactoryPaths.AgentRun.VIA);
				value.setValue(TextLimits.truncate(pGathered.inputs.toString(), AgentFramework.INPUTS_MAX_LENGTH), FactoryPaths.AgentRun.INPUTS);
				value.setValue(TextLimits.truncate(pDecided.toString(), AgentFramework.INPUTS_MAX_LENGTH), FactoryPaths.AgentRun.DECISION);
				value.setValue(pLowest, FactoryPaths.AgentRun.CONFIDENCE);
				value.setValue(Integer.valueOf(pOutcome.applied), FactoryPaths.AgentRun.APPLIED);
				value.setValue(Integer.valueOf(pOutcome.escalated), FactoryPaths.AgentRun.ESCALATED);
				value.setValue(Integer.valueOf(pOutcome.refused), FactoryPaths.AgentRun.REFUSED);
				if (pOutcome.escalation != null)
				{
					value.setValue(TextLimits.truncate(pOutcome.escalation, 200), FactoryPaths.AgentRun.ESCALATION);
				}
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] agent " + pRun.agentCode + ": could not record run " + pOutcome.runId + ": "
				+ result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	/** Sets {@code lastRunAt} before the model is asked, so a schedule tick during a long run does not start a second one. */
	private static void touchLastRun(final Run pRun)
	{
		AgentFramework.touchLastRun(pRun.session, pRun.factory, pRun.definition);
	}

	static void touchLastRun(final Session pSession, final Adaptation pFactory, final Adaptation pDefinition)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final ValueContextForUpdate value = pContext.getContext(pDefinition.getAdaptationName());
				value.setValue(new Date(), FactoryPaths.AgentDefinition.LAST_RUN_AT);
				pContext.doModifyContent(pDefinition, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().warn("[mdm-factory] could not stamp the last run of agent " + pDefinition.getOccurrencePrimaryKey().format() + ": "
				+ result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	/** The JSON object of a stored text, an empty object when it is not one. */
	static ObjectNode json(final String pText)
	{
		try
		{
			final JsonNode node = pText == null ? null : AgentFramework.MAPPER.readTree(pText);
			return node != null && node.isObject() ? (ObjectNode) node : AgentFramework.MAPPER.createObjectNode();
		}
		catch (final java.io.IOException ex)
		{
			return AgentFramework.MAPPER.createObjectNode();
		}
	}

	/** The user of a profile code, null when it is not a user ({@code Ualice} -&gt; alice). Pure. */
	static String userOf(final String pProfile)
	{
		return pProfile != null && pProfile.length() > 1 && pProfile.charAt(0) == 'U' ? pProfile.substring(1) : null;
	}

	/** Whether {@code pUser} holds {@code pProfile} (a role, a user, or a built-in profile). */
	static boolean holds(final Repository pRepository, final UserReference pUser, final String pProfile)
	{
		if (pUser == null || pProfile == null || pProfile.trim().isEmpty())
		{
			return false;
		}
		try
		{
			final Profile profile = Profile.parse(pProfile.trim());
			if (profile.isUserReference())
			{
				return profile.equals(pUser);
			}
			return com.orchestranetworks.service.directory.DirectoryHandler.getInstance(pRepository).isUserInRole(pUser, (com.orchestranetworks.service.Role) profile);
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}
}
