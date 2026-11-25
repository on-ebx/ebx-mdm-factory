package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.List;

/**
 * UX lot U2 (2026-10-13): what a landing plan that wrote nothing has to say. Measured live the same day: the plan of a
 * catalog source whose only asset came from OpenLineage (no scanned column) answered "0 table(s) planned" in green,
 * 0 s, while the planner's own report ({@code LandingPlanner}, {@code tables[].outcome}) held the reason line by line.
 * This pure class turns those outcomes into (1) the English text of the job's result - which starts with {@link #NOTHING_TO_DO}
 * when nothing was written, the convention shared by the UX lots - and (2) the message key of the one-line reason a card shows
 * per table. No EBX access: unit-tested in {@code PlanOutcomesTest}.
 */
public final class PlanOutcomes
{
	/** First words of a job message that produced nothing: the task list shows such a job as "nothing to do", never as a green success (convention shared with the other UX lots). */
	public static final String NOTHING_TO_DO = "Nothing to do:";

	public static final String OUTCOME_PLANNED = "PLANNED";
	public static final String OUTCOME_ALREADY_PLANNED = "ALREADY_PLANNED";
	public static final String OUTCOME_DRIFTED = "DRIFTED";
	/** Landing test fixes (2026-10-17): a table planned and not generated whose columns were brought in line with the catalog by a rescan. */
	public static final String OUTCOME_REFRESHED = "REFRESHED";
	public static final String OUTCOME_NO_COLUMN = "SKIPPED_NO_SCANNED_COLUMN";

	/** How many table names a job message lists before saying "and N more". */
	static final int MESSAGE_NAMES = 4;

	private PlanOutcomes()
	{
	}

	/** One line of the planner's report: the object of the source and what became of it. */
	public static final class Line
	{
		public final String sourceObject;
		public final String outcome;

		public Line(final String pSourceObject, final String pOutcome)
		{
			this.sourceObject = pSourceObject == null ? "" : pSourceObject;
			this.outcome = pOutcome == null ? "" : pOutcome;
		}
	}

	/** The bundle key of the one-line reason a card shows for a table that was not planned, or null when the outcome is not a problem (planned, already planned). */
	public static String reasonKey(final String pOutcome)
	{
		if (PlanOutcomes.OUTCOME_NO_COLUMN.equals(pOutcome))
		{
			return "wizard.src2.reason.noColumn";
		}
		if (PlanOutcomes.OUTCOME_DRIFTED.equals(pOutcome))
		{
			return com.on.ebx.factory.ebx.FeatureSwitches.sourceDrift() ? "wizard.src2.reason.drifted" : "wizard.src2.reason.driftedClosed";
		}
		return null;
	}

	/** Whether scanning the application again can change this outcome (a table without scanned column does not need anything else). */
	public static boolean scanAgainHelps(final String pOutcome)
	{
		return PlanOutcomes.OUTCOME_NO_COLUMN.equals(pOutcome);
	}

	/** The name a person knows an object by: the last dot-separated segment of its qualified name ({@code DB.SCHEMA.TABLE} gives {@code TABLE}). */
	public static String shortName(final String pSourceObject)
	{
		if (pSourceObject == null)
		{
			return "";
		}
		final String name = pSourceObject.trim();
		final int dot = name.lastIndexOf('.');
		return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1) : name;
	}

	/**
	 * Landing test fixes (2026-10-17): the job's result text when a rescan refreshed tables already planned ({@code Fidelite_Membres: 13 columns now (was 1).}); a plain
	 * success text, never {@link #NOTHING_TO_DO}, since something was written.
	 */
	public static String refreshMessage(final int pPlanned, final List<String> pSummaries)
	{
		final StringBuilder text = new StringBuilder();
		if (pPlanned > 0)
		{
			text.append(pPlanned).append(" table(s) planned. ");
		}
		text.append(pSummaries.size()).append(pSummaries.size() == 1 ? " table refreshed from the new scan: " : " tables refreshed from the new scan: ");
		for (int i = 0; i < pSummaries.size(); i++)
		{
			text.append(i == 0 ? "" : " ").append(pSummaries.get(i));
		}
		return text.toString();
	}

	/**
	 * The job's result text (English, stored as is like the other job messages). When tables were planned: the usual counts.
	 * When nothing was: {@link #NOTHING_TO_DO} then the reasons in user words and the gesture that unblocks.
	 *
	 * @param pUnmatchedSchemas the filter entries of the application that matched no scanned schema (the planner's own {@code unmatchedSchemas}), may be null
	 */
	public static String jobMessage(final int pPlanned, final int pAlreadyPlanned, final List<Line> pLines, final List<String> pUnmatchedSchemas)
	{
		if (pPlanned > 0)
		{
			return pPlanned + " table(s) planned, " + pAlreadyPlanned + " already planned.";
		}
		final List<String> noColumn = new ArrayList<String>();
		int drifted = 0;
		for (final Line line : pLines)
		{
			if (PlanOutcomes.OUTCOME_NO_COLUMN.equals(line.outcome))
			{
				noColumn.add(PlanOutcomes.shortName(line.sourceObject));
			}
			else if (PlanOutcomes.OUTCOME_DRIFTED.equals(line.outcome))
			{
				drifted++;
			}
		}
		final StringBuilder text = new StringBuilder(PlanOutcomes.NOTHING_TO_DO);
		if (pUnmatchedSchemas != null && !pUnmatchedSchemas.isEmpty())
		{
			text.append(" the schema filter (").append(PlanOutcomes.join(pUnmatchedSchemas, MESSAGE_NAMES)).append(") matches nothing the catalog scanned.");
		}
		if (!noColumn.isEmpty())
		{
			text.append(' ').append(PlanOutcomes.join(noColumn, MESSAGE_NAMES)).append(": no column scanned yet.");
		}
		if (drifted > 0)
		{
			if (com.on.ebx.factory.ebx.FeatureSwitches.sourceDrift())
			{
				text.append(' ').append(drifted).append(drifted == 1 ? " table changed in the source since it was planned: its changes wait for a decision (see the source drift guide)." : " tables changed in the source since they were planned: their changes wait for a decision (see the source drift guide).");
			}
			else
			{
				// F3-105, closed: the v1.3.0 warning stays, without the promise of a review screen that never existed
				text.append(' ').append(drifted).append(drifted == 1 ? " table changed in the source since it was planned: prepare its landing again, or clean up from the Landing step, to take the change into account." : " tables changed in the source since they were planned: prepare their landing again, or clean up from the Landing step, to take the change into account.");
			}
		}
		if (noColumn.isEmpty() && drifted == 0 && (pUnmatchedSchemas == null || pUnmatchedSchemas.isEmpty()))
		{
			if (pAlreadyPlanned > 0)
			{
				return text.append(" the ").append(pAlreadyPlanned).append(pAlreadyPlanned == 1 ? " table" : " tables").append(" found ").append(pAlreadyPlanned == 1 ? "was" : "were")
					.append(" already planned.").toString();
			}
			return text.append(" the catalog holds no table for this application yet. Scan the application again.").toString();
		}
		if (noColumn.isEmpty() && (pUnmatchedSchemas == null || pUnmatchedSchemas.isEmpty()))
		{
			return text.toString(); // only changed tables: scanning again does not help
		}
		return text.append(" Scan the application again.").toString();
	}

	/** {@code "Account, Asset"}, at most {@code pMax} names then {@code " and N more"}. */
	static String join(final List<String> pNames, final int pMax)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pNames.size() && i < pMax; i++)
		{
			if (i > 0)
			{
				out.append(", ");
			}
			out.append(pNames.get(i));
		}
		if (pNames.size() > pMax)
		{
			out.append(" and ").append(pNames.size() - pMax).append(" more");
		}
		return out.toString();
	}
}
