package com.on.ebx.factory.landing;

/**
 * Names the artifacts generated for one project source: fixed patterns, no EBX class, so a naming
 * question can be settled and tested here rather than by re-deriving it inside {@code LandingGenerator}.
 * Pure.
 */
public final class LandingNames
{
	private LandingNames()
	{
	}

	/** {@code <ProjectCode>_Landing_<sourceSystem>}: the data model built and published for one source system. */
	public static String modelName(final String pProjectCode, final String pSourceSystem)
	{
		return pProjectCode + "_Landing_" + pSourceSystem;
	}

	/**
	 * {@code <ProjectCode><landingSuffix>}: the landing dataspace of the whole project, shared by every
	 * source system of it (one dataset per source system inside).
	 *
	 * @param pLandingSuffix {@code FactorySettings.landingSuffix}; blank (including a settings row written
	 *        before that field existed) falls back to the model's own default, {@code _Landing}.
	 */
	public static String dataspaceKey(final String pProjectCode, final String pLandingSuffix)
	{
		final String suffix = pLandingSuffix == null || pLandingSuffix.trim().isEmpty() ? "_Landing" : pLandingSuffix.trim();
		return pProjectCode + suffix;
	}
}
