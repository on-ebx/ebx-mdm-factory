package com.on.ebx.factory.delivery;

/**
 * Whether a project was delivered here from another instance. {@code FactorySettings.instanceId} is a
 * random identifier the installer stamps once per instance; {@code Project.originRepository} is the
 * instanceId of the instance the project was created on. A staged {@code Project} row keeps its origin, so
 * on a target the two differ - and every {@code suggest*} route refuses: proposals are made where the
 * decisions are taken (the source), never re-derived on a target. Pure. {@code docs/DELIVERY.md} sec 5.
 */
public final class OriginRule
{
	private OriginRule()
	{
	}

	/** True only when both values are known and differ; an unstamped project (older row) counts as local. */
	public static boolean isDelivered(final String pOriginRepository, final String pInstanceId)
	{
		final String origin = OriginRule.trim(pOriginRepository);
		final String here = OriginRule.trim(pInstanceId);
		return origin != null && here != null && !origin.equals(here);
	}

	public static String refusal(final String pProjectCode, final String pOriginRepository)
	{
		return "Project " + pProjectCode + " was delivered from instance " + OriginRule.trim(pOriginRepository)
			+ ": suggestions run on the instance the project was designed on, never on a target.";
	}

	private static String trim(final String pText)
	{
		if (pText == null)
		{
			return null;
		}
		final String trimmed = pText.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
