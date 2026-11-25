package com.on.ebx.factory.distribution;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Distribution lot 1 (engine): {@code DistributionTarget.connectorsMappingIds} holds one
 * {@code "tablePath=mappingId"} pair per master table the target sends through, comma-separated - see that
 * field's own comment in {@code tools/build_xsd.py}. Pure - no EBX class; {@code ebx.DistributionConfigurator}
 * writes it, {@code ebx.DistributionRunner}/{@code DistributionDispatcher} read it. Never split by hand: a
 * table path or a mapping id containing a comma is not expected (EBX paths and mapping ids never do), but
 * this class is still the one place the format is known, so a future change to it costs one class, not every
 * caller.
 */
public final class TargetMappingList
{
	private TargetMappingList()
	{
	}

	/** {@code "/root/Company=41,/root/Contact=42"} -&gt; parsed, in encounter order; blank/malformed entries are skipped rather than thrown on (a hand-edited or half-written value must not crash every caller). */
	public static Map<String, String> parse(final String pStored)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pStored == null || pStored.trim().isEmpty())
		{
			return out;
		}
		for (final String part : pStored.split(","))
		{
			final String trimmed = part.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			final int eq = trimmed.indexOf('=');
			if (eq <= 0 || eq == trimmed.length() - 1)
			{
				continue;
			}
			out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
		}
		return out;
	}

	/** The inverse of {@link #parse}: encounter order of {@code pMappingByTable} is preserved. */
	public static String format(final Map<String, String> pMappingByTable)
	{
		final StringBuilder sb = new StringBuilder();
		for (final Map.Entry<String, String> entry : pMappingByTable.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(entry.getKey()).append('=').append(entry.getValue());
		}
		return sb.toString();
	}
}
