package com.on.ebx.factory.master;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure decision for the "self-heal on read" step (`docs/MATCHING.md`, "Keeping Xref.goldenKey fresh
 * (2026-09-23)"): {@code ebx.RecordLineageReader#read} already has, in hand, the TRUTH about who belongs to
 * a golden record - the {@code contributingKeys} set {@code ebx.RecordLineageReader#computeMerge} resolves
 * straight from the add-on's own {@code RecordMetadata} table, one query, every time a record is looked up.
 * This class only decides which of the corresponding {@code Xref} rows' CACHED {@code goldenKey} disagree
 * with that truth and must be rewritten - it never re-derives the truth itself (that would mean a second,
 * redundant {@code RecordMetadata} read the caller already avoided by reusing what {@code computeMerge}
 * just computed).
 * <p>
 * No EBX type anywhere, so every branch is unit-tested without a running instance - the EBX read (which
 * {@code Xref} row currently holds which {@code goldenKey}, keyed by master key) and the EBX write are both
 * isolated in {@code ebx.RecordLineageReader#selfHealGoldenKeys}, the only caller.
 */
public final class GoldenKeySelfHeal
{
	private GoldenKeySelfHeal()
	{
	}

	/**
	 * @param pTrueGoldenKey the golden key every row keyed in {@code pCurrentGoldenKeyByMasterKey} should
	 *            carry - every key in {@code contributingKeys} is, by construction, a record that currently
	 *            belongs to this ONE golden. Blank/null answers an empty map, never a guess (there is nothing
	 *            to correct TOWARDS).
	 * @param pCurrentGoldenKeyByMasterKey one entry per contributing master key that has an {@code Xref} row,
	 *            its CURRENT (possibly stale) {@code goldenKey} value - null/blank counts as "not yet set",
	 *            always a mismatch against a real {@code pTrueGoldenKey}.
	 * @return the master keys that need correction, each mapped to {@code pTrueGoldenKey} - a row already
	 *         agreeing with the truth is left out entirely (so "would write these rows" is exactly this map's
	 *         key set). Iteration order follows the input map, so a caller building a deterministic log sees
	 *         one too.
	 */
	public static Map<String, String> rowsToWrite(final String pTrueGoldenKey, final Map<String, String> pCurrentGoldenKeyByMasterKey)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pTrueGoldenKey == null || pTrueGoldenKey.trim().isEmpty() || pCurrentGoldenKeyByMasterKey == null)
		{
			return out;
		}
		final String trueGoldenKey = pTrueGoldenKey.trim();
		for (final Map.Entry<String, String> entry : pCurrentGoldenKeyByMasterKey.entrySet())
		{
			if (!trueGoldenKey.equals(entry.getValue()))
			{
				out.put(entry.getKey(), trueGoldenKey);
			}
		}
		return out;
	}
}
