package com.on.ebx.factory.agent;

/**
 * What the runner does with ONE decision of an agent (agentic framework lot, 2026-10-30, {@code docs/AGENTS.md}). Pure: the autonomy of the agent, its
 * confidence threshold, the confidence of the decision and two facts the runner checked before (is the decision's tool on the agent's allow-list, and do
 * the acting user's EBX permissions allow it) give one {@link Disposition}.
 * <ul>
 * <li>A tool outside the allow-list, or an action the acting user may not do: {@link Disposition#REFUSED} - recorded, never applied, never offered.</li>
 * <li>An abstention (the agent said it cannot decide): {@link Disposition#NO_ACTION}.</li>
 * <li>{@link Autonomy#SUGGEST}: {@link Disposition#SUGGEST} - offered to the owner, never applied by the runner even once approved.</li>
 * <li>{@link Autonomy#ACT_WITH_APPROVAL}: {@link Disposition#AWAIT_APPROVAL} - offered to the owner, applied when approved.</li>
 * <li>{@link Autonomy#ACT}: {@link Disposition#APPLY} at or above the threshold, {@link Disposition#AWAIT_APPROVAL} below it.</li>
 * </ul>
 */
public final class AgentPolicy
{
	/** {@code AgentDefinition.autonomy}. */
	public enum Autonomy
	{
		SUGGEST, ACT_WITH_APPROVAL, ACT;

		/** The stored code; anything unknown or empty reads as {@link #SUGGEST}, the safe side. */
		public static Autonomy parse(final String pCode)
		{
			if (pCode != null)
			{
				for (final Autonomy one : Autonomy.values())
				{
					if (one.name().equalsIgnoreCase(pCode.trim()))
					{
						return one;
					}
				}
			}
			return SUGGEST;
		}
	}

	/** What happens to a decision. */
	public enum Disposition
	{
		/** Applied now, by the runner, with the acting user's permissions. */
		APPLY,
		/** Offered to the owner profile; applied by the runner once approved. */
		AWAIT_APPROVAL,
		/** Offered to the owner profile; never applied by the runner. */
		SUGGEST,
		/** Outside the allow-list or the acting user's permissions: recorded only. */
		REFUSED,
		/** The agent abstained. */
		NO_ACTION;

		/** Whether the decision goes to the owner's review task. */
		public boolean escalates()
		{
			return this == AWAIT_APPROVAL || this == SUGGEST;
		}
	}

	/** The confidence threshold a definition without one gets. */
	public static final int DEFAULT_THRESHOLD = 90;

	private AgentPolicy()
	{
	}

	/**
	 * @param pThreshold the agent's threshold, clamped to 0..100 ({@code null} = {@link #DEFAULT_THRESHOLD})
	 * @param pConfidence the decision's confidence, 0..100 ({@code null} = 0: a decision without a confidence never acts alone)
	 * @param pAbstains the decision is an abstention
	 * @param pToolAllowed the decision's tool is on the agent's allow-list
	 * @param pPermitted the acting user's EBX permissions allow the action
	 */
	public static Disposition decide(final Autonomy pAutonomy, final Integer pThreshold, final Integer pConfidence, final boolean pAbstains, final boolean pToolAllowed,
		final boolean pPermitted)
	{
		if (pAbstains)
		{
			return Disposition.NO_ACTION;
		}
		if (!pToolAllowed || !pPermitted)
		{
			return Disposition.REFUSED;
		}
		final Autonomy autonomy = pAutonomy == null ? Autonomy.SUGGEST : pAutonomy;
		switch (autonomy)
		{
		case ACT:
			return AgentPolicy.confidence(pConfidence) >= AgentPolicy.threshold(pThreshold) ? Disposition.APPLY : Disposition.AWAIT_APPROVAL;
		case ACT_WITH_APPROVAL:
			return Disposition.AWAIT_APPROVAL;
		default:
			return Disposition.SUGGEST;
		}
	}

	/** Pure: the threshold actually used. */
	public static int threshold(final Integer pThreshold)
	{
		return pThreshold == null ? AgentPolicy.DEFAULT_THRESHOLD : AgentPolicy.clamp(pThreshold.intValue());
	}

	/** Pure: the confidence actually compared. */
	public static int confidence(final Integer pConfidence)
	{
		return pConfidence == null ? 0 : AgentPolicy.clamp(pConfidence.intValue());
	}

	static int clamp(final int pValue)
	{
		return pValue < 0 ? 0 : pValue > 100 ? 100 : pValue;
	}
}
