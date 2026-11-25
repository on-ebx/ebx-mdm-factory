package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit F-36: what the cockpit shows of a project's {@code PipelineStep} rows is, per (scope, step) pair, the LATEST run and how many times it ran
 * ({@code CockpitData.latestPerStep}). This keeps exactly that while the rows are streamed past it, one at a time, so the screen no longer materialises every row of a
 * project that has run for months: memory is the number of distinct (scope, step) pairs, whatever the history. {@link #offer} may be given the rows in any order;
 * the result carries each pair's run count in {@code StepInfo.runs}, which {@code CockpitData} adds up instead of counting list entries. Pure: no EBX type.
 */
final class StepDigest
{
	private final Map<String, CockpitData.StepInfo> latest = new LinkedHashMap<String, CockpitData.StepInfo>();

	/** Takes one row into account. The row itself is kept only while it is the latest of its pair. */
	void offer(final CockpitData.StepInfo pRow)
	{
		final String key = pRow.scope + "\u0001" + pRow.step;
		final CockpitData.StepInfo kept = this.latest.get(key);
		if (kept == null)
		{
			this.latest.put(key, pRow);
			return;
		}
		final int runs = kept.runs + pRow.runs;
		if (CockpitData.isLater(pRow, kept))
		{
			this.latest.put(key, pRow);
			pRow.runs = runs;
		}
		else
		{
			kept.runs = runs;
		}
	}

	/** One entry per (scope, step) pair, in the order the pairs were first seen. */
	List<CockpitData.StepInfo> result()
	{
		return new ArrayList<CockpitData.StepInfo>(this.latest.values());
	}
}
