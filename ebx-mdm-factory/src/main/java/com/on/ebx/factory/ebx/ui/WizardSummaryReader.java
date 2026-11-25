package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.on.ebx.factory.ebx.UserView;
import com.onwbp.adaptation.RequestResult;

/**
 * Wizard step 7 ("Summary") lot, 2026-09-24: the one small EBX reader this lot needed that {@link
 * WizardSnapshot} does not already carry - {@code StagingEntity}'s own last-load counters (written/
 * rejected/at, not read into {@code WizardSnapshot.StagingEntityInfo} today, which only carries the
 * ACCEPT/REJECT decision status) and {@code PipelineStep}'s {@code startedAt}/{@code endedAt} (only
 * {@code startedAtMillis} is on {@code WizardSnapshot.PipelineStepInfo}, enough for "running since N min"
 * but not for a completed step's own duration - what the "Last full run" journal needs). Everything else
 * step 7 needs (sources, staging feeds/mappings, quality/constraint proposal status per entity, match
 * plans) is already on {@link WizardSnapshot} and read from there instead - see {@code
 * ProjectWizardUserService.summaryInput} for how the two are combined. Same self-contained, thin-reader
 * discipline as {@code FactoryCockpitUserService}'s own {@code readX()} methods (that class' own Javadoc)
 * - not touched here on purpose, so this lot never risks a behavioural change on the Cockpit screen.
 * <p>
 * Second permissions pass (2026-10-14): every read takes the viewer's {@link UserView}: only the rows the user may see are listed or counted, and a
 * hidden field reads empty.
 */
final class WizardSummaryReader
{
	private WizardSummaryReader()
	{
	}

	static final class EntityLoad
	{
		String entityName = "";
		/** Rows the last load wrote PLUS rows it found already up to date (lot V3-S: {@code lastLoadWritten + lastLoadUnchanged}) - the rows staging holds from that load; a reload of unchanged data must not read as "0 rows". */
		Long written;
		Long rejected;
		Date at;
	}

	static List<EntityLoad> readEntityLoads(final UserView pView, final Adaptation pDataset, final String pProjectId)
	{
		final List<EntityLoad> out = new ArrayList<EntityLoad>();
		final RequestResult rows = pView.rows(pDataset.getTable(FactoryPaths.STAGING_ENTITY), "./project=" + FactoryHtml.quote(pProjectId));
		for (final Adaptation row : FactoryHtml.rows(rows))
		{
			final EntityLoad load = new EntityLoad();
			load.entityName = pView.text(row, FactoryPaths.StagingEntity.NAME);
			final Long written = WizardSummaryReader.asLong(pView.value(row, FactoryPaths.StagingEntity.LAST_LOAD_WRITTEN));
			final Long unchanged = WizardSummaryReader.asLong(pView.value(row, FactoryPaths.StagingEntity.LAST_LOAD_UNCHANGED));
			load.written = written == null && unchanged == null ? null : Long.valueOf((written == null ? 0L : written.longValue()) + (unchanged == null ? 0L : unchanged.longValue()));
			load.rejected = WizardSummaryReader.asLong(pView.value(row, FactoryPaths.StagingEntity.LAST_LOAD_REJECTED));
			load.at = (Date) pView.value(row, FactoryPaths.StagingEntity.LAST_LOAD_AT);
			out.add(load);
		}
		return out;
	}

	static final class StepRun
	{
		String step = "";
		String scope = "";
		String status = "";
		Date startedAt;
		Date endedAt;
	}

	/** Every {@code PipelineStep} row of the project (every scope: project-wide, per-source, per-entity) - the caller picks the latest run of whichever step it cares about. */
	static List<StepRun> readSteps(final UserView pView, final Adaptation pDataset, final String pProjectId)
	{
		final List<StepRun> out = new ArrayList<StepRun>();
		final RequestResult rows = pView.rows(pDataset.getTable(FactoryPaths.PIPELINE_STEP), "./project=" + FactoryHtml.quote(pProjectId));
		for (final Adaptation row : FactoryHtml.rows(rows))
		{
			final StepRun run = new StepRun();
			run.step = pView.text(row, FactoryPaths.PipelineStep.STEP);
			run.scope = pView.text(row, FactoryPaths.PipelineStep.SCOPE);
			run.status = pView.text(row, FactoryPaths.PipelineStep.STATUS);
			run.startedAt = (Date) pView.value(row, FactoryPaths.PipelineStep.STARTED_AT);
			run.endedAt = (Date) pView.value(row, FactoryPaths.PipelineStep.ENDED_AT);
			out.add(run);
		}
		return out;
	}

	/**
	 * Lot volume-4 (2026-09-28, task brief item 7): a bounded {@code RequestResult.getSize()} with the "not
	 * resolved" test folded into the XPath predicate itself, instead of materializing every {@code
	 * TransferReject} row of the project just to check one field in Java - a project's reject table can hold
	 * millions of rows on a real feed (task brief). {@code not(./resolved=true())} is the exact XPath mirror of
	 * the old {@code !Boolean.TRUE.equals(row.get(RESOLVED))} check: a null/unset {@code resolved} (never
	 * explicitly marked resolved) counts as unresolved either way, since {@code ./resolved=true()} answers false
	 * for a null field. Measured live 2026-09-28 ({@code count_records_by_xpath} against a real project's
	 * populated {@code TransferReject} table, 541 unresolved).
	 */
	/** Wave 10 (audit F-38): a reject a steward acknowledged no longer counts - it was looked at and set aside; this is the number of OPEN rejects. */
	static int unresolvedRejectCount(final UserView pView, final Adaptation pDataset, final String pProjectId)
	{
		final RequestResult rows = pView.rows(pDataset.getTable(FactoryPaths.TRANSFER_REJECT), "./project=" + FactoryHtml.quote(pProjectId) + " and not(./resolved=true()) and not(./acknowledged=true())");
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}

	private static Long asLong(final Object pValue)
	{
		return pValue instanceof Number ? Long.valueOf(((Number) pValue).longValue()) : null;
	}
}
