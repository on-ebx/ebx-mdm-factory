package com.on.ebx.factory.ebx;

import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.agent.AgentActivation;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.SessionPermissions;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Production review of 2026-11-12 (F3-02): the identity an agent acts under when nobody clicked (an event, a schedule) is the person who switched it on through
 * {@code POST /agents/{code}/enable}, signed by the server ({@link AgentActivation}, keyed with the secret {@code FactorySettings.agentActivationKey}, never the instance identifier) - never a login typed into {@code AgentDefinition.enabledBy}. A row whose value
 * is not such a signature, or whose agent settings changed since the activation, has NO actor and is treated as not switched on.
 */
public final class AgentIdentity
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private AgentIdentity()
	{
	}

	/** The stored secret key (read by the module itself, not session-checked); null when the installer has not drawn one. Never log it or return it. */
	static String activationKey(final Adaptation pFactory)
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		return settings == null ? null : settings.getString(FactoryPaths.Settings.AGENT_ACTIVATION_KEY);
	}

	/** True when the key exists (the only thing {@code /status} says about it). */
	public static boolean activationKeyPresent(final Adaptation pFactory)
	{
		return AgentActivation.keyOf(AgentIdentity.activationKey(pFactory)) != null;
	}

	/** True for a run nobody started by hand: it acts for the stored activation, so it must be signed ({@code event <name>}, {@code schedule}). */
	static boolean viaNeedsActivation(final String pVia)
	{
		return pVia != null && (pVia.startsWith("event ") || "schedule".equals(pVia));
	}

	/**
	 * Pure: the refusal code (suffix of {@code engine.}) for a run that acts for {@code pActingFor} when the definition's verified actor is
	 * {@code pVerifiedActor}; null when the run may go on. A hand-started run needs no activation.
	 */
	static String activationProblem(final String pVia, final String pVerifiedActor, final String pActingFor)
	{
		if (!AgentIdentity.viaNeedsActivation(pVia))
		{
			return null;
		}
		if (pVerifiedActor == null || pVerifiedActor.trim().isEmpty())
		{
			return "conflict.agentNotActivated";
		}
		return pVerifiedActor.equals(pActingFor == null ? null : pActingFor.trim()) ? null : "conflict.agentNotActivated";
	}

	/**
	 * 1.4.2 (F3-08 point 1): the refusal code (suffix of {@code engine.}) for a run started BY HAND: the agent must be switched on with a valid, signed activation for its
	 * current settings, like the scheduler and the events require. Pure. A run by hand still acts for the CALLER, not for the person who switched the agent on.
	 */
	static String manualRunProblem(final boolean pEnabled, final String pVerifiedActor)
	{
		return pEnabled && pVerifiedActor != null && !pVerifiedActor.trim().isEmpty() ? null : "conflict.agentNotActivated";
	}

	/** The settings an activation covers, read from the definition row. */
	static String configOf(final Adaptation pDefinition)
	{
		final Object threshold = pDefinition.get(FactoryPaths.AgentDefinition.CONFIDENCE_THRESHOLD);
		final Object max = pDefinition.get(FactoryPaths.AgentDefinition.MAX_ITEMS);
		return AgentActivation.configOf(pDefinition.getString(FactoryPaths.AgentDefinition.PROJECT), pDefinition.getString(FactoryPaths.AgentDefinition.KIND),
			pDefinition.getString(FactoryPaths.AgentDefinition.ENTITY), pDefinition.getString(FactoryPaths.AgentDefinition.ZONE),
			pDefinition.getString(FactoryPaths.AgentDefinition.AUTONOMY), threshold instanceof Integer ? (Integer) threshold : null,
			max instanceof Integer ? (Integer) max : null, pDefinition.getString(FactoryPaths.AgentDefinition.OWNER_PROFILE),
			AgentIdentity.tools(pDefinition), pDefinition.getString(FactoryPaths.AgentDefinition.INSTRUCTIONS));
	}

	private static java.util.List<String> tools(final Adaptation pDefinition)
	{
		final java.util.List<String> out = new java.util.ArrayList<String>();
		final java.util.List<?> list = pDefinition.getList(FactoryPaths.AgentDefinition.ALLOWED_TOOLS);
		if (list != null)
		{
			for (final Object one : list)
			{
				out.add(one == null ? "" : one.toString());
			}
		}
		return out;
	}

	/**
	 * The person this agent acts for when nobody started it: the signed activation of an ENABLED definition, null for everything else (not enabled, a value
	 * nobody signed, settings changed since, an instance with no identifier).
	 */
	public static String actor(final Adaptation pFactory, final Adaptation pDefinition)
	{
		if (pDefinition == null || !Boolean.TRUE.equals(pDefinition.get(FactoryPaths.AgentDefinition.ENABLED)))
		{
			return null;
		}
		final byte[] key = AgentActivation.keyOf(AgentIdentity.activationKey(pFactory));
		return AgentActivation.verify(key, pDefinition.getString(FactoryPaths.AgentDefinition.CODE), AgentIdentity.configOf(pDefinition),
			pDefinition.getString(FactoryPaths.AgentDefinition.ENABLED_BY));
	}

	/**
	 * The acting person must still be able to write the project (EBX permissions of that person, not the caller's): the activation was given on that condition and
	 * the person may have lost the right since.
	 */
	static void requireProjectWritable(final SessionPermissions pPermissions, final Adaptation pProject, final String pActingFor, final String pAgentCode) throws OperationException
	{
		if (pPermissions == null || pProject == null || !pPermissions.getAdaptationAccessPermission(pProject).isReadWrite())
		{
			throw EngineRefusal.coded("conflict.agentProjectRight", "Agent " + pAgentCode + " acts for " + pActingFor
				+ ", who no longer has write access to the agent's project in EBX; refused.", String.valueOf(pActingFor), String.valueOf(pAgentCode));
		}
	}

	/** Switches an agent on for the caller: the server signs the caller's login together with the agent's current settings. */
	public static ObjectNode enable(final Session pSession, final Adaptation pFactory, final String pAgentCode) throws OperationException
	{
		FeatureSwitches.requireAgents();
		final Adaptation definition = AgentIdentity.mustExist(pFactory, pAgentCode);
		if (pSession == null || pSession.isSystemUser() || pSession.getUserReference() == null)
		{
			throw EngineRefusal.coded("conflict.agentEnableByPerson", "An agent is switched on by a person, not by the system.");
		}
		final String login = pSession.getUserReference().getUserId();
		if (!FactoryRight.allows(FactoryAccess.granted(pSession), FactoryRight.STEWARD))
		{
			throw EngineRefusal.coded("conflict.agentActorRight", "Agent " + pAgentCode + " would act for " + login + ", who does not hold the MDM Factory right STEWARD; refused.", login);
		}
		final byte[] key = AgentActivation.keyOf(AgentIdentity.activationKey(pFactory));
		final String signed = AgentActivation.sign(key, definition.getString(FactoryPaths.AgentDefinition.CODE), AgentIdentity.configOf(definition), login);
		if (signed == null)
		{
			throw EngineRefusal.coded("invalid.agentActivation", "The activation of agent " + pAgentCode + " cannot be signed: the factory settings hold no activation key (the installer has not drawn it yet), or the login is too long.",
				String.valueOf(pAgentCode));
		}
		AgentIdentity.write(pSession, pFactory, definition, Boolean.TRUE, signed);
		final ObjectNode out = AgentIdentity.MAPPER.createObjectNode();
		out.put("agent", definition.getString(FactoryPaths.AgentDefinition.CODE));
		out.put("enabled", true);
		out.put("actingFor", login);
		return out;
	}

	/** Switches an agent off: no event, no schedule runs it any more. Allowed while the agents are closed. */
	public static ObjectNode disable(final Session pSession, final Adaptation pFactory, final String pAgentCode) throws OperationException
	{
		final Adaptation definition = AgentIdentity.mustExist(pFactory, pAgentCode);
		AgentIdentity.write(pSession, pFactory, definition, Boolean.FALSE, null);
		final ObjectNode out = AgentIdentity.MAPPER.createObjectNode();
		out.put("agent", definition.getString(FactoryPaths.AgentDefinition.CODE));
		out.put("enabled", false);
		return out;
	}

	private static Adaptation mustExist(final Adaptation pFactory, final String pAgentCode) throws OperationException
	{
		final Adaptation definition = AgentFramework.definition(pFactory, pAgentCode);
		if (definition == null)
		{
			throw EngineRefusal.coded("missing.agent", "No agent " + pAgentCode + " is declared.", String.valueOf(pAgentCode));
		}
		return definition;
	}

	/** The one writer of {@code enabled} and {@code enabledBy}: the fields are read only for every person ({@link ModuleOnlyFieldRule}). */
	private static void write(final Session pSession, final Adaptation pFactory, final Adaptation pDefinition, final Boolean pEnabled, final String pEnabledBy) throws OperationException
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final ValueContextForUpdate value = pContext.getContext(pDefinition.getAdaptationName());
				value.setValue(pEnabled, FactoryPaths.AgentDefinition.ENABLED);
				value.setValue(pEnabledBy, FactoryPaths.AgentDefinition.ENABLED_BY);
				pContext.doModifyContent(pDefinition, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.agentActivationWrite", "Could not record the activation of agent " + pDefinition.getOccurrencePrimaryKey().format() + ": "
				+ result.getExceptionFullMessage(Locale.ENGLISH), pDefinition.getOccurrencePrimaryKey().format());
		}
	}
}
