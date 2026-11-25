package com.on.ebx.factory.migration;

/**
 * Decides whether a DMA publication row of {@code ebx-dataModels-publications} needs its owner filled in.
 * <p>
 * EBX Staging imports a DMA publication with {@code originalDataModel = null} (measured 2026-09-27 on the 6.3.0-RC2
 * instance: 69 of 76 rows, every one that came from the archive). The {@code data-model} MCP server compares that
 * field with the model it publishes: {@code publish_data_model} then answers PUBLICATION_OWNED_BY_ANOTHER_MODEL and
 * {@code get_publication} answers PUBLICATION_NOT_FOUND, so the Factory can no longer republish a migrated model. A
 * publication created on the instance itself carries the name of its model there.
 */
public final class PublicationOwnerRule
{
	public enum Action
	{
		/** the owner is already set: never overwritten */
		KEEP,
		/** the owner is empty and the linked version names its model */
		SET,
		/** the owner is empty but the linked version gives no model name */
		UNKNOWN
	}

	private PublicationOwnerRule()
	{
	}

	/**
	 * @param pCurrentOwner {@code originalDataModel} as stored
	 * @param pLinkedModelName {@code dataModelName} of the version the publication's {@code dataModel} points at
	 */
	public static Action decide(final String pCurrentOwner, final String pLinkedModelName)
	{
		if (!PublicationOwnerRule.isBlank(pCurrentOwner))
		{
			return Action.KEEP;
		}
		return PublicationOwnerRule.isBlank(pLinkedModelName) ? Action.UNKNOWN : Action.SET;
	}

	private static boolean isBlank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}
}
