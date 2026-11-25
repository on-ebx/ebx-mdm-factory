package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.List;

/**
 * The XPath predicate a SCOPED {@code Xref.goldenKey} refresh is restricted to (`docs/MATCHING.md`,
 * "Keeping Xref.goldenKey fresh (2026-09-23)") - pure, no EBX class, unit-tested without a running
 * instance. Built the exact same way {@link MatchingRunScope#all} already builds its own {@code ./id=...}
 * predicate, over {@code Xref.masterKey} instead of the master table's own PK: {@code
 * ebx.GoldenKeyRefresher#refreshScoped} appends this to its own base predicate (project + masterTable + a
 * non-blank masterKey) so only the given master keys' own {@code Xref} rows are re-read/rewritten - never
 * the whole table, unlike {@code ebx.GoldenKeyRefresher#refresh}'s own unscoped call.
 * <p>
 * Two callers share this: {@code ebx.RecordLineageReader#decideSuspect} (the suspect, the anchor, and every
 * record whose {@code RecordMetadata.goldenId} is the resulting golden - a handful of keys) and {@code
 * ebx.MatchingConfigurator#refreshGoldenKeys} when it is ever given an explicit key list rather than running
 * the whole table.
 */
public final class GoldenKeyRefreshScope
{
	private GoldenKeyRefreshScope()
	{
	}

	/**
	 * @param pMasterKeys the master keys this refresh is restricted to - the caller's own job to collect
	 *            (e.g. a suspect decision's own group members, `docs/MATCHING.md`).
	 * @return an {@code (./masterKey='k1' or ./masterKey='k2' or ...)} predicate over every non-blank,
	 *         trimmed key, or {@code null} when nothing survives filtering - the ONE thing this class must
	 *         never produce is a predicate that matches every row: a caller that gets {@code null} back must
	 *         skip the refresh entirely (see {@code ebx.GoldenKeyRefresher#refreshScoped}'s own Javadoc),
	 *         never silently widen it into an unscoped read.
	 */
	public static String xpathPredicate(final Iterable<String> pMasterKeys)
	{
		final List<String> keys = new ArrayList<String>();
		if (pMasterKeys != null)
		{
			for (final String key : pMasterKeys)
			{
				if (key != null && !key.trim().isEmpty())
				{
					keys.add(key.trim());
				}
			}
		}
		if (keys.isEmpty())
		{
			return null;
		}
		final StringBuilder predicate = new StringBuilder("(");
		for (final String key : keys)
		{
			if (predicate.length() > 1)
			{
				predicate.append(" or ");
			}
			predicate.append("./masterKey=").append(GoldenKeyRefreshScope.quote(key));
		}
		return predicate.append(')').toString();
	}

	/** Same doubling convention {@code ebx.CatalogReader.quote} uses for an XPath string literal - duplicated here since this class must stay pure (no EBX package dependency), same as {@link MatchingRunScope#quote}. */
	private static String quote(final String pValue)
	{
		return "'" + pValue.replace("'", "''") + "'";
	}
}
