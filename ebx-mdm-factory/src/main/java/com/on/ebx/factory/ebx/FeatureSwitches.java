package com.on.ebx.factory.ebx;

/**
 * Production review of 2026-11-12 (findings F3-08, F3-105; decision "voie A" of the same day): the functions delivered after
 * v1.3.0 stay in the code but are CLOSED on an instance that sets nothing. An administrator of the server opens one with a JVM
 * property; nothing in the repository can open it, so no role of the module can either.
 * <ul>
 * <li>{@value #AGENTS_PROPERTY}{@code =true} - the governed agents: declaring a trigger that fires, running an agent (by hand, on
 * an event, on a schedule), deciding and applying a proposal.</li>
 * <li>{@value #SOURCE_DRIFT_PROPERTY}{@code =true} - source drift: recording a schema change of a source when a landing is
 * planned, marking the landing table, deciding and applying the change. Closed, a landing plan behaves as in v1.3.0.</li>
 * </ul>
 * A closed function answers a coded refusal that names the property ({@link #requireAgents()}, {@link #requireSourceDrift()});
 * code that runs without a person (a trigger, a schedule, a planning pass) asks {@link #agents()} / {@link #sourceDrift()} and
 * does nothing. The property is read at each call: no restart is needed to close a function again, only to set the property.
 */
public final class FeatureSwitches
{
	public static final String AGENTS_PROPERTY = "ebx.factory.agents.enabled";
	public static final String SOURCE_DRIFT_PROPERTY = "ebx.factory.sourceDrift.enabled";

	private FeatureSwitches()
	{
	}

	/** True when this instance opened the governed agents. */
	public static boolean agents()
	{
		return FeatureSwitches.on(FeatureSwitches.AGENTS_PROPERTY);
	}

	/** True when this instance opened source drift (detection, decision and application). */
	public static boolean sourceDrift()
	{
		return FeatureSwitches.on(FeatureSwitches.SOURCE_DRIFT_PROPERTY);
	}

	/** Refuses, with the property to set, when the governed agents are closed on this instance. */
	public static void requireAgents() throws EngineRefusal
	{
		if (!FeatureSwitches.agents())
		{
			throw EngineRefusal.coded("missing.agentsClosed",
				"The governed agents are closed on this instance: a server administrator opens them with -D" + FeatureSwitches.AGENTS_PROPERTY + "=true",
				FeatureSwitches.AGENTS_PROPERTY);
		}
	}

	/** Refuses, with the property to set, when source drift is closed on this instance. */
	public static void requireSourceDrift() throws EngineRefusal
	{
		if (!FeatureSwitches.sourceDrift())
		{
			throw EngineRefusal.coded("missing.sourceDriftClosed",
				"Source drift is closed on this instance: a server administrator opens it with -D" + FeatureSwitches.SOURCE_DRIFT_PROPERTY + "=true",
				FeatureSwitches.SOURCE_DRIFT_PROPERTY);
		}
	}

	/** Only the exact word {@code true} (any case, spaces around ignored) opens: an empty, misspelt or absent value keeps the function closed. */
	static boolean on(final String pProperty)
	{
		final String value = System.getProperty(pProperty);
		return value != null && "true".equalsIgnoreCase(value.trim());
	}
}
