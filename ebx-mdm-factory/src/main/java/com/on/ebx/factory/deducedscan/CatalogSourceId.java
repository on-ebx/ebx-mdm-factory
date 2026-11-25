package com.on.ebx.factory.deducedscan;

import java.util.Locale;
import java.util.Set;

/**
 * Fix lot (2026-09-25): the catalog {@code Source} identifier a connector gets when it is registered from
 * the deduced-scan entry point ({@code ebx.DeducedScanCatalog#ensureSource}) - ported VERBATIM (never
 * re-derived) from the official path's own naming rule, {@code com.on.ebx.connect.core.CatalogDetection
 * #sourceIdFor} in the {@code ebx-connectors} repository (read-only reference, {@code ~/Dev/ebx-connectors}):
 * {@code connectors_catalog_source}'s own {@code CREATE}/{@code SET_CONNECTOR} plan action names a brand new
 * Source with exactly this transform (measured live, contract's own fixtures: connector {@code SourceBidon}
 * -&gt; Source {@code SOURCEBIDON}). Reusing the identical algorithm here means a deduced-scanned connection
 * that later grows an official catalog operation lands on the SAME Source id the official path would have
 * picked, instead of a second, orphaned one.
 * <p>
 * {@code Source.id} (contract/live schema, {@code describe_table} on {@code DataCatalog}/{@code Source},
 * 2026-09-25) is a plain mandatory {@code string}, max 40 characters - NOT an auto-increment. ({@code Asset}
 * and {@code Column} lost their auto-increment {@code id} with audit D-07: they are keyed {@code
 * source:qualifiedName} / {@code assetKey.name}, which {@link ebx.DeducedScanCatalogWriter} sets explicitly -
 * see {@code core.CatalogKeys}.) A create that leaves {@code Source.id} unset fails with EBX's own "primary
 * key has unset element(s)" error - the live bug this class fixes.
 */
public final class CatalogSourceId
{
	private CatalogSourceId()
	{
	}

	/** Pure: a Source id from a connector id ({@code sap-odata prod} -&gt; {@code SAP_ODATA_PROD}), at most 40 characters - identical to {@code CatalogDetection#sourceIdFor}. */
	public static String forConnector(final String pConnectorId)
	{
		String s = (pConnectorId == null ? "" : pConnectorId).toUpperCase(Locale.ENGLISH).replaceAll("[^A-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
		if (s.isEmpty())
		{
			s = "SOURCE";
		}
		return s.length() > 40 ? s.substring(0, 40) : s;
	}

	/**
	 * {@link #forConnector(String)}, disambiguated against {@code pExistingIds} (every {@code Source.id}
	 * already in the catalog) by appending {@code _2}, {@code _3}, … - never returns a value already in
	 * {@code pExistingIds}, always at most 40 characters (the base is truncated first to leave room for the
	 * suffix when needed). {@code pExistingIds} must NOT include the id this connector's own Source might
	 * already use (a caller that found a Source already naming this connector reuses it directly - see
	 * {@code DeducedScanCatalog#ensureSource} - and never calls this method at all in that case).
	 */
	public static String unique(final String pConnectorId, final Set<String> pExistingIds)
	{
		final String base = CatalogSourceId.forConnector(pConnectorId);
		if (pExistingIds == null || !pExistingIds.contains(base))
		{
			return base;
		}
		for (int n = 2; n < 1000; n++)
		{
			final String suffix = "_" + n;
			final String trimmedBase = base.length() + suffix.length() > 40 ? base.substring(0, 40 - suffix.length()) : base;
			final String candidate = trimmedBase + suffix;
			if (!pExistingIds.contains(candidate))
			{
				return candidate;
			}
		}
		// defensive: 999 collisions on the same base is not expected in practice
		return base;
	}
}
