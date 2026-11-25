package com.on.ebx.factory.master;

/**
 * Names the artifacts {@code ebx.MasterGenerator} builds for one project - the same idea as
 * {@code staging.StagingNames}, with one deliberate difference: the master DATA MODEL name is always
 * {@code <ProjectCode>_Master} (fixed, like staging's own {@code _Staging}), but the master DATASPACE key
 * uses a setting of its own ({@code FactorySettings.masterSuffix}), per the coordinator's brief - not the
 * fixed {@code stagingSuffix} pattern, so a project can rename its master dataspace independently of its
 * staging one. Pure.
 */
public final class MasterNames
{
	/** The dataset every master dataspace gets, fixed - the same convention as {@code StagingNames.DATASET}. */
	public static final String DATASET = "Master";

	private MasterNames()
	{
	}

	/** {@code <ProjectCode>_Master}: the data model built and published for the whole project's master zone. */
	public static String modelName(final String pProjectCode)
	{
		return pProjectCode + "_Master";
	}

	/**
	 * {@code <ProjectCode><masterSuffix>}: the master dataspace of the project.
	 *
	 * @param pMasterSuffix {@code FactorySettings.masterSuffix}; blank (including a settings row written before that field existed) falls back to the model's own default, {@code _Master}.
	 */
	public static String dataspaceKey(final String pProjectCode, final String pMasterSuffix)
	{
		final String suffix = pMasterSuffix == null || pMasterSuffix.trim().isEmpty() ? "_Master" : pMasterSuffix.trim();
		return pProjectCode + suffix;
	}
}
