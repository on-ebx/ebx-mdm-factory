package com.on.ebx.factory.master;

import java.util.Collection;
import java.util.List;

import com.on.ebx.factory.capability.DerivationBodyBuilder;

/**
 * Lot T2 (2026-10-04): decides whether a script derivation that is applied on a STAGING table also gets its two table triggers on the MASTER table
 * of the same entity, and which model, table and trigger names that means - pure (no EBX class), the decision {@code ebx.DerivationMasterTriggers}
 * acts on and {@code ebx.MasterGenerator} asks for every applied derivation of a promoted entity.
 * <p>
 * <b>Why master needs the triggers at all.</b> In the master zone the derived attribute is otherwise only a copy of the staging value ({@code
 * ebx.MasterLoader}). Nothing would recompute it when a data steward edits the source attribute on a master record, when Match and Merge creates a
 * golden record whose source value survives from one record and whose derived value from another (survivorship is per field), or when a record is
 * created directly in master. The same two triggers (same names, same function) close those three cases.
 * <p>
 * <b>Eligible only when the master table can really run them.</b> The trigger body reads {@code _ebx.record.<attribute>} for every attribute the
 * binding reads and writes the derived one, by those exact names, so each of them must be a field of the master table under the SAME name: a
 * business attribute whose name collides with a field every master table carries ({@link MasterFieldNameRule}: {@code Id} becomes {@code Id_src}) is
 * not, and neither is an attribute that was not promoted. The decision is never an error: it says why it is skipped and the caller records it.
 * Nothing here knows what a phone or a country is.
 */
public final class DerivationMasterTarget
{
	private DerivationMasterTarget()
	{
	}

	/** Why a derivation is, or is not, given master triggers. */
	public enum Kind
	{
		/** The master model and table exist and carry every attribute the triggers read or write, under the same names. */
		ELIGIBLE,
		/** The project has no master model yet: the generator declares the triggers when it builds it. Normal, nothing to report. */
		NO_MASTER_MODEL,
		/** The master model has no table for the entity (not promoted: a member of another entity, or accepted after the last generation). Normal. */
		NO_MASTER_TABLE,
		/** The master table exists but an attribute the triggers need is not on it under the same name: a limitation worth telling. */
		ATTRIBUTE_NOT_ON_MASTER,
		/** The derivation's parameters changed since it was applied: the staging triggers are stale, so is what master would get; the next apply brings both. */
		PARAMETERS_CHANGED
	}

	/** The outcome of {@link #decide}. */
	public static final class Decision
	{
		public final Kind kind;
		/** Why, in one sentence; empty for {@link Kind#ELIGIBLE}. */
		public final String reason;
		public final String modelName;
		public final String tablePath;
		public final String createName;
		public final String modifyName;

		Decision(final Kind pKind, final String pReason, final String pModelName, final String pTablePath, final String pCreateName, final String pModifyName)
		{
			this.kind = pKind;
			this.reason = pReason;
			this.modelName = pModelName;
			this.tablePath = pTablePath;
			this.createName = pCreateName;
			this.modifyName = pModifyName;
		}

		public boolean eligible()
		{
			return this.kind == Kind.ELIGIBLE;
		}

		/** True when the reason deserves a line on the suggestion row ({@code applyMessage}); the ordinary "no master yet" cases stay in the answer and the step detail only. */
		public boolean noteworthy()
		{
			return this.kind == Kind.ATTRIBUTE_NOT_ON_MASTER;
		}
	}

	/**
	 * @param pMasterModelExists the project's master model ({@link MasterNames#modelName}) exists
	 * @param pMasterTableFields the exact field names of the master table of the entity; {@code null} when the master model has no such table
	 * @param pParametersChanged the suggestion carries the re-apply marker ({@code DerivationParameters.isReapplyMarker})
	 */
	public static Decision decide(final String pProjectCode, final String pEntityName, final String pDerivedAttribute, final List<String> pBoundAttributes,
		final boolean pMasterModelExists, final Collection<String> pMasterTableFields, final boolean pParametersChanged)
	{
		final String modelName = MasterNames.modelName(pProjectCode);
		final String tablePath = MasterModel.tablePath(pEntityName);
		final String createName = DerivationBodyBuilder.triggerName(pDerivedAttribute, true);
		final String modifyName = DerivationBodyBuilder.triggerName(pDerivedAttribute, false);
		if (!pMasterModelExists)
		{
			return new Decision(Kind.NO_MASTER_MODEL, "the master model " + modelName + " does not exist yet; generating the master zone declares the triggers", modelName, tablePath,
				createName, modifyName);
		}
		if (pMasterTableFields == null)
		{
			return new Decision(Kind.NO_MASTER_TABLE, "the master model " + modelName + " has no table " + tablePath + " (the entity is not promoted to master)", modelName, tablePath,
				createName, modifyName);
		}
		if (pParametersChanged)
		{
			return new Decision(Kind.PARAMETERS_CHANGED, "the parameters of the derivation changed since it was applied: apply it again to update the master triggers too", modelName,
				tablePath, createName, modifyName);
		}
		final java.util.List<String> needed = new java.util.ArrayList<String>();
		needed.add(pDerivedAttribute);
		if (pBoundAttributes != null)
		{
			for (final String bound : pBoundAttributes)
			{
				if (!needed.contains(bound))
				{
					needed.add(bound);
				}
			}
		}
		for (final String attribute : needed)
		{
			if (MasterFieldNameRule.isReserved(attribute))
			{
				return new Decision(Kind.ATTRIBUTE_NOT_ON_MASTER, "attribute '" + attribute + "' is not on " + modelName + tablePath
					+ " under its own name (its name is one of the fields every master table carries), so the master triggers are not declared", modelName, tablePath, createName,
					modifyName);
			}
			if (!pMasterTableFields.contains(attribute))
			{
				return new Decision(Kind.ATTRIBUTE_NOT_ON_MASTER, "attribute '" + attribute + "' is not a field of " + modelName + tablePath
					+ " (not promoted to master), so the master triggers are not declared", modelName, tablePath, createName, modifyName);
			}
		}
		return new Decision(Kind.ELIGIBLE, "", modelName, tablePath, createName, modifyName);
	}
}
