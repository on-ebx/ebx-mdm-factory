package com.on.ebx.factory.staging;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Fix/staging-proposal-quality (2026-10-17, item 1): the post-load check that a computed field is not empty on EVERY row of its table.
 * Measured on TiersB2CRetestLanding: {@code Customer.phoneE164} and {@code Customer.personNameKey} were accepted and empty on all 394 rows
 * while the folded keys beside them were filled, and nothing on the load result said so. A computed field is a script derivation (stored by
 * two triggers) or a folded / validity key (Java); whichever computes it, "no value anywhere" is never a result a person wants to find out by
 * opening a record. Pure: {@code ebx.StagingLoader} counts, this class words the sentence (one per field, kept on the entity's load result).
 */
public final class ComputedFieldFill
{
	private ComputedFieldFill()
	{
	}

	/** Whether the check reports: the table holds rows and not one carries a value in the field. */
	public static boolean emptyEverywhere(final long pRows, final long pFilled)
	{
		return pRows > 0L && pFilled <= 0L;
	}

	/**
	 * The one-line report, or {@code null} when the field has a value somewhere (or the table is empty).
	 *
	 * @param pSourceName the attribute the field is computed from, or {@code null}
	 * @param pScriptNotApplied true for a script derivation that is not applied (the cause is then known)
	 */
	public static String report(final String pEntity, final String pAttribute, final String pSourceName, final long pRows, final boolean pScriptNotApplied)
	{
		final NumberFormat format = NumberFormat.getIntegerInstance(Locale.ENGLISH);
		return pEntity + "." + pAttribute + ": computed field empty on all " + format.format(pRows) + " row(s) - "
			+ (pScriptNotApplied ? "its script derivation is not applied and could not be applied by this load"
				: "check that " + (pSourceName == null ? "its source attribute" : "its source " + pSourceName)
					+ " is filled and, for a script derivation, that its triggers are live on the staging model");
	}
}
