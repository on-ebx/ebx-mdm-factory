package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lot L2 (2026-10-12): the final status of a chain of sources, named after the sources that failed. Pure.
 * <p>
 * The sources of a chain no longer settle one after the other, so the message that ends the {@code LANDING_RUN} step cannot be "some failures":
 * the person needs to know WHICH applications to look at. A failed source never cancels the others (each one's cells settle on their own); this
 * only reads the grid once everything has settled.
 */
public final class LandingChainOutcome
{
	private LandingChainOutcome()
	{
	}

	/** The source systems (in the grid's order, each once) that have at least one {@link LandingRunProgress#FAILED} cell. */
	public static List<String> failedSources(final LandingRunProgress pProgress)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final LandingRunProgress.TableProgress table : pProgress.tables())
		{
			for (final LandingRunProgress.Cell cell : table.cells())
			{
				if (LandingRunProgress.FAILED.equals(cell.state))
				{
					out.add(table.sourceSystem == null || table.sourceSystem.isEmpty() ? "?" : table.sourceSystem);
					break;
				}
			}
		}
		return new ArrayList<String>(out);
	}

	/** The distinct source systems of the grid ({@code "?"} for a table with none), in the grid's order. */
	public static List<String> sources(final LandingRunProgress pProgress)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final LandingRunProgress.TableProgress table : pProgress.tables())
		{
			out.add(table.sourceSystem == null || table.sourceSystem.isEmpty() ? "?" : table.sourceSystem);
		}
		return new ArrayList<String>(out);
	}

	/**
	 * UX lot C2 (2026-10-14): whether every application of the grid has a failed cell. Superseded by lot D (2026-10-14) for the job's status: the verdict is
	 * now per TABLE ({@code wizard.PartialFailures#allFailed} / {@code #isPartial}, used by {@code LandingRun}) - an application with two tables of which one
	 * loaded is a partial failure, not a failed job. Kept for the callers that only ask about applications.
	 */
	public static boolean allSourcesFailed(final LandingRunProgress pProgress)
	{
		final List<String> failed = LandingChainOutcome.failedSources(pProgress);
		return !failed.isEmpty() && failed.size() >= LandingChainOutcome.sources(pProgress).size();
	}

	/** The names, each once, in order (the workflow bean lists its failed project sources with it). */
	public static List<String> distinct(final List<String> pNames)
	{
		return new ArrayList<String>(new LinkedHashSet<String>(pNames));
	}

	/** "Landing run finished: N of M stages done." when nothing failed, else "Landing run finished with failures on: A, B." */
	public static String finalMessage(final LandingRunProgress pProgress)
	{
		return LandingChainOutcome.finalText(pProgress).english;
	}

	/** Run panel i18n (2026-10-17): {@link #finalMessage} as a code plus its arguments ({@code .stored()} is the English followed by the marker). */
	public static KeyTexts.Text finalText(final LandingRunProgress pProgress)
	{
		final List<String> failed = LandingChainOutcome.failedSources(pProgress);
		if (failed.isEmpty())
		{
			return RunTexts.finished(pProgress.doneStageCount(), pProgress.totalStageCount());
		}
		return RunTexts.finishedFailuresOn(String.join(", ", failed));
	}
}
