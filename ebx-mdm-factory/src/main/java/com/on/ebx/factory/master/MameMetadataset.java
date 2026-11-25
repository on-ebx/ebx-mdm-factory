package com.on.ebx.factory.master;

/**
 * The Match and Merge add-on's own metadataset naming convention - MEASURED LIVE 2026-09-22 (coordinator,
 * instance 6.3.0-dev, add-on MAME 6.3.0-DEV, `docs/RECORD-LINEAGE.md` "The metadataset naming convention -
 * fixed, not just flagged"): one metadataset per business TABLE, named
 * {@code <masterDatasetName>_<tableLastStep>_MDS}, in the SAME dataspace as the master dataset - e.g. dataspace
 * {@code Companies_Master}, dataset {@code Master}, table {@code /root/Company} -&gt; metadataset
 * {@code Master_Company_MDS}. <b>NOT</b> the OLDER {@code <masterDataspaceKey>_<masterDatasetName>_MDS}
 * convention every EBX-facing class in this module once assumed: it happened to resolve on the one example
 * available before that measurement ({@code Companies}/{@code Companies}), only because the dataspace and the
 * dataset shared that exact name - it does not generalize, and a project whose master dataspace/dataset do not
 * share the entity's own table name silently resolves no metadataset at all ("not merged"/"no merge info",
 * forever, never an error).
 * <p>
 * <b>Pure (no EBX class)</b> - a plain string derivation, moved here 2026-09-22 so every EBX-facing class that
 * needs to look the metadataset up shares ONE implementation and can never again silently disagree on the
 * convention: {@code ebx.RecordLineageReader} (merge status for the lineage screen), {@code
 * ebx.MasterLoader.RecordMetadataMergeCheck} (skip-if-merged-away during a master load), {@code
 * ebx.GoldenKeyRefresher} (refresh {@code Xref.goldenKey}) and {@code ebx.MasterReset} (refuse a reset when
 * real match/merge history exists on any promoted table) - the last two still carried the older, wrong
 * convention until this fix.
 */
public final class MameMetadataset
{
	private static final String SUFFIX = "_MDS";

	private MameMetadataset()
	{
	}

	/**
	 * {@code <pDatasetName>_<last step of pTablePath>_MDS} - null when either input has no usable content (a
	 * blank dataset name, or a table path with no non-empty {@code /}-separated step at all).
	 */
	public static String name(final String pDatasetName, final String pTablePath)
	{
		final String step = MameMetadataset.lastPathStep(pTablePath);
		return step == null || MameMetadataset.blank(pDatasetName) ? null : pDatasetName + "_" + step + MameMetadataset.SUFFIX;
	}

	/**
	 * The last {@code /}-separated, non-empty step of a table path (e.g. {@code /root/Company} -&gt;
	 * {@code Company}) - see {@link #name}'s own Javadoc. Null for a blank/malformed path (no non-empty step at
	 * all).
	 */
	public static String lastPathStep(final String pTablePath)
	{
		if (MameMetadataset.blank(pTablePath))
		{
			return null;
		}
		final String trimmed = pTablePath.endsWith("/") ? pTablePath.substring(0, pTablePath.length() - 1) : pTablePath;
		final int slash = trimmed.lastIndexOf('/');
		final String step = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
		return step.isEmpty() ? null : step;
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}
}
