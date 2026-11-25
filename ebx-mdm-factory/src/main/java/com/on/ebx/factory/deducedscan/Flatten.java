package com.on.ebx.factory.deducedscan;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Deduced-scan lot (2026-09-24, read-recipe-contract.md sec "Why"/3): flattens one arbitrary JSON row into
 * dotted paths ({@code properties.email}), the same shape a Connectors inbound feed's {@code source path}
 * rule expects (contract: "the same dotted path is the Column name, so the landing rule's source path
 * works"). Deliberately pure (no EBX import) so it can be unit-tested with {@code main()} and reused by both
 * the measuring pass ({@link ColumnMeasurer}) and, later, the landing generator that turns a confirmed
 * {@code Column} name straight into a feed rule's source path.
 * <p>
 * Ported from {@code ebx.LandingPreview#flatten} (same idea, same dotted/bracketed convention for arrays),
 * moved here and made array-index-free for object KEYS: {@link ColumnMeasurer} only ever measures the
 * dotted-object-path columns an item's TOP-LEVEL object structure has in common across the sample - an array
 * element's own index is not a stable column name across different rows (row A's {@code tags[0]} is not the
 * same "column" as row B's {@code tags[0]}), so arrays are flattened to a single joined text value instead of
 * per-index paths, unlike {@code LandingPreview}'s free-form preview table. A blank prefix at the root never
 * happens in practice (the sample rows this class receives are always JSON objects), but is handled the same
 * way as any other empty/null leaf for defensiveness.
 */
public final class Flatten
{
	private Flatten()
	{
	}

	/** One flattened row: dotted path -&gt; text value ("" for null/empty, a joined summary for a non-empty array). Iteration order follows the source object's own field order. */
	public static Map<String, String> row(final JsonNode pNode)
	{
		final LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
		Flatten.flatten("", pNode, out);
		return out;
	}

	private static void flatten(final String pPrefix, final JsonNode pNode, final Map<String, String> pOut)
	{
		if (pNode == null || pNode.isNull() || pNode.isMissingNode())
		{
			if (!pPrefix.isEmpty())
			{
				pOut.put(pPrefix, "");
			}
			return;
		}
		if (pNode.isObject())
		{
			if (pNode.size() == 0)
			{
				if (!pPrefix.isEmpty())
				{
					pOut.put(pPrefix, "");
				}
				return;
			}
			for (final Iterator<Map.Entry<String, JsonNode>> it = pNode.fields(); it.hasNext();)
			{
				final Map.Entry<String, JsonNode> entry = it.next();
				Flatten.flatten(pPrefix.isEmpty() ? entry.getKey() : pPrefix + "." + entry.getKey(), entry.getValue(), pOut);
			}
			return;
		}
		if (pNode.isArray())
		{
			// see class Javadoc: arrays are joined into one text value at this path, not exploded per index.
			final StringBuilder joined = new StringBuilder();
			for (int i = 0; i < pNode.size(); i++)
			{
				final JsonNode element = pNode.get(i);
				if (i > 0)
				{
					joined.append("; ");
				}
				joined.append(element.isTextual() ? element.asText() : element.toString());
			}
			pOut.put(pPrefix, joined.toString());
			return;
		}
		pOut.put(pPrefix, pNode.asText());
	}
}
