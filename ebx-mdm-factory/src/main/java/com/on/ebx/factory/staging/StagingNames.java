package com.on.ebx.factory.staging;

/**
 * Names the artifacts {@code StagingGenerator} builds for one project: fixed patterns, no EBX class, the
 * same idea as {@code LandingNames} for the landing zone. Unlike landing (one data model and one dataset
 * per source system), staging is one data model and one dataset for the WHOLE project, since every
 * accepted entity - whatever source system(s) it came from - lands in the same staging zone (PLAN.md,
 * "Zones": {@code dataspace <Projet>_Staging}). Pure.
 */
public final class StagingNames
{
	/** The dataset every staging dataspace gets, fixed - not derived from the project or a source system. */
	public static final String DATASET = "Staging";

	private StagingNames()
	{
	}

	/** {@code <ProjectCode>_Staging}: the data model built and published for the whole project's staging zone. */
	public static String modelName(final String pProjectCode)
	{
		return pProjectCode + "_Staging";
	}

	/**
	 * {@code <ProjectCode><stagingSuffix>}: the staging dataspace of the project.
	 *
	 * @param pStagingSuffix {@code FactorySettings.stagingSuffix}; blank (including a settings row written
	 *        before that field existed) falls back to the model's own default, {@code _Staging}.
	 */
	public static String dataspaceKey(final String pProjectCode, final String pStagingSuffix)
	{
		final String suffix = pStagingSuffix == null || pStagingSuffix.trim().isEmpty() ? "_Staging" : pStagingSuffix.trim();
		return pProjectCode + suffix;
	}
}
