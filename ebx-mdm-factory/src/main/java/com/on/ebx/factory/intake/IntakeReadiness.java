package com.on.ebx.factory.intake;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Automatic intake lot: the gate for switching the intake on, from plain facts. Pure. The proofs behind the facts are the SAME the distribution arming gate and the
 * master generator use ({@code ebx.IntakeReadinessReader}: every ticked landing table loaded, {@code master.MasterStagingReadiness} for staging,
 * {@code distribution.DistributionMasterReadiness} for the master): the first full load stays a deliberate action, automation takes over from there.
 * <p>
 * Three blocking lines, one informative: {@code PIPELINE_MODEL} (the published MdmFactoryPipeline declares the intake inputs), {@code LANDING_LOADED},
 * {@code STAGING_AND_MASTER_LOADED}; {@code REJECTS_REVIEWED} is only informative ("N rows held back still to review"), never blocking.
 */
public final class IntakeReadiness
{
	public static final String PIPELINE_MODEL = "PIPELINE_MODEL";
	public static final String LANDING_LOADED = "LANDING_LOADED";
	public static final String STAGING_AND_MASTER_LOADED = "STAGING_AND_MASTER_LOADED";
	public static final String REJECTS_REVIEWED = "REJECTS_REVIEWED";

	public static final class Line
	{
		public final String key;
		public final boolean ok;
		public final boolean blocking;
		/** English text; the UI may localise from {@link #key}. */
		public final String text;
		/** The count the text mentions (rows held back, sources not loaded), or null. */
		public final Long count;

		Line(final String pKey, final boolean pOk, final boolean pBlocking, final String pText, final Long pCount)
		{
			this.key = pKey;
			this.ok = pOk;
			this.blocking = pBlocking;
			this.text = pText;
			this.count = pCount;
		}
	}

	public static final class Result
	{
		/** Every blocking line is ok. */
		public final boolean ready;
		public final List<Line> lines;

		Result(final boolean pReady, final List<Line> pLines)
		{
			this.ready = pReady;
			this.lines = Collections.unmodifiableList(pLines);
		}

		/** The first blocking line that is not ok, or null. */
		public Line firstBlocker()
		{
			for (final Line line : this.lines)
			{
				if (line.blocking && !line.ok)
				{
					return line;
				}
			}
			return null;
		}
	}

	private IntakeReadiness()
	{
	}

	/**
	 * @param pPipelineDeclaresIntake the published MdmFactoryPipeline exists and declares the {@code intakeChain} input
	 * @param pSourceCount the project's sources
	 * @param pSourcesNotLoaded the sources with a ticked landing table not loaded
	 * @param pStagingLoaded {@code MasterStagingReadiness.decide(...).ready}
	 * @param pMasterLoaded {@code DistributionMasterReadiness.ready(...)}
	 * @param pRejectsToReview unresolved staging rejects of the project (informative)
	 */
	public static Result evaluate(final boolean pPipelineDeclaresIntake, final int pSourceCount, final int pSourcesNotLoaded, final boolean pStagingLoaded,
		final boolean pMasterLoaded, final long pRejectsToReview)
	{
		final List<Line> lines = new ArrayList<Line>();
		lines.add(new Line(IntakeReadiness.PIPELINE_MODEL, pPipelineDeclaresIntake, true,
			pPipelineDeclaresIntake ? "The pipeline workflow is published with the intake inputs."
				: "The published pipeline workflow predates automatic intake: publish the current model.", null));
		final boolean landingOk = pSourceCount > 0 && pSourcesNotLoaded == 0;
		lines.add(new Line(IntakeReadiness.LANDING_LOADED, landingOk, true,
			pSourceCount == 0 ? "The project has no source yet."
				: landingOk ? "Landing loaded from every application." : pSourcesNotLoaded + " application(s) not loaded into the landing yet.",
			Long.valueOf(pSourcesNotLoaded)));
		final boolean stagingMasterOk = pStagingLoaded && pMasterLoaded;
		lines.add(new Line(IntakeReadiness.STAGING_AND_MASTER_LOADED, stagingMasterOk, true,
			stagingMasterOk ? "Staging and master loaded once in full."
				: !pStagingLoaded ? "The staging has not been loaded in full yet." : "The master has not been loaded in full yet.", null));
		lines.add(new Line(IntakeReadiness.REJECTS_REVIEWED, pRejectsToReview <= 0, false,
			pRejectsToReview <= 0 ? "No row held back waits for review." : pRejectsToReview + " row(s) held back still to review.", Long.valueOf(Math.max(0L, pRejectsToReview))));
		boolean ready = true;
		for (final Line line : lines)
		{
			ready = ready && (!line.blocking || line.ok);
		}
		return new Result(ready, lines);
	}
}
