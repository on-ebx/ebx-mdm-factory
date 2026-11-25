package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.intake.IntakeStatus;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Automatic intake lot: every read and write of the factory's own intake rows ({@code Project} intake fields, {@code IntakeChain}) in one place. Writes are one
 * small Procedure each with all privileges - the intake's own bookkeeping, like {@code PipelineSteps}/{@code FactoryJobQueue} (authorisation is the facade's job,
 * {@code ebx.Intake}). Reads of project fields are guarded (a model written before this lot lacks them).
 */
final class IntakeStore
{
	private IntakeStore()
	{
	}

	// ------------------------------------------------------------------------------------------------------------ project

	static String state(final Adaptation pProject)
	{
		final Object value = IntakeSources.valueOrNull(pProject, FactoryPaths.Project.INTAKE_STATE);
		final String text = value == null ? null : value.toString();
		return text == null || text.trim().isEmpty() ? FactoryPaths.Project.INTAKE_OFF : text.trim();
	}

	static String text(final Adaptation pRow, final Path pPath)
	{
		final Object value = IntakeSources.valueOrNull(pRow, pPath);
		return value == null ? null : value.toString();
	}

	/** Empty counts as true. */
	static boolean reviewRejects(final Adaptation pProject)
	{
		return !Boolean.FALSE.equals(IntakeSources.valueOrNull(pProject, FactoryPaths.Project.INTAKE_REVIEW_REJECTS));
	}

	static Integer stopRejectPercent(final Adaptation pProject)
	{
		return IntakeSources.intOrNull(pProject, FactoryPaths.Project.INTAKE_STOP_REJECT_PERCENT);
	}

	/** Every project whose intake is switched on, read fresh. */
	static List<Adaptation> projectsWithState(final Adaptation pFactory, final String pState)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./intakeState=" + CatalogReader.quote(pState));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Writes the given values (a path -&gt; value map, null clears) on the project row. */
	static void writeProject(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final Map<Path, Object> pValues) throws OperationException
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final ValueContextForUpdate value = pContext.getContext(pProject.getAdaptationName());
				for (final Map.Entry<Path, Object> entry : pValues.entrySet())
				{
					value.setValue(entry.getValue(), entry.getKey());
				}
				pContext.doModifyContent(pProject, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveIntakeState", "Could not write the intake state of the project: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	/** {@code intakeState} + reason (null clears it) - the one write of the state machine. */
	static void setState(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final String pState, final String pReason) throws OperationException
	{
		final Map<Path, Object> values = new LinkedHashMap<Path, Object>();
		values.put(FactoryPaths.Project.INTAKE_STATE, pState);
		values.put(FactoryPaths.Project.INTAKE_PAUSE_REASON, pReason == null ? null : TextLimits.truncate(pReason, 500));
		IntakeStore.writeProject(pSession, pFactory, pProject, values);
	}

	// ------------------------------------------------------------------------------------------------------------ chains

	static Adaptation chain(final Adaptation pFactory, final String pChainId)
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.INTAKE_CHAIN);
		return table.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { pChainId }));
	}

	/** Creates the chain row (status RUNNING). The primary key is the id the caller generated: it is also the token the workflow carries. */
	static void createChain(final Session pSession, final Adaptation pFactory, final String pChainId, final String pProjectId, final String pReason,
		final List<String> pSources, final long pRowsWaiting, final String pStartedFor) throws OperationException
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.INTAKE_CHAIN);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
				value.setValue(pChainId, FactoryPaths.IntakeChain.ID);
				value.setValue(pProjectId, FactoryPaths.IntakeChain.PROJECT);
				value.setValue(new Date(), FactoryPaths.IntakeChain.STARTED_AT);
				value.setValue(pReason, FactoryPaths.IntakeChain.REASON);
				if (pStartedFor != null && !pStartedFor.isEmpty())
				{
					value.setValue(pStartedFor, FactoryPaths.IntakeChain.STARTED_BY);
				}
				value.setValue(IntakeStore.join(pSources), FactoryPaths.IntakeChain.SOURCES);
				value.setValue(BigDecimal.valueOf(pRowsWaiting), FactoryPaths.IntakeChain.ROWS_WAITING);
				value.setValue(FactoryPaths.IntakeChain.STATUS_RUNNING, FactoryPaths.IntakeChain.STATUS);
				pContext.doCreateOccurrence(value, table);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveIntakeChain", "Could not record the intake chain: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	/** Writes values on a chain row; a missing row is not an error (retention may have purged it). */
	static void writeChain(final Session pSession, final Adaptation pFactory, final String pChainId, final Map<Path, Object> pValues)
	{
		final Adaptation row = IntakeStore.chain(pFactory, pChainId);
		if (row == null)
		{
			return;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				for (final Map.Entry<Path, Object> entry : pValues.entrySet())
				{
					value.setValue(entry.getValue(), entry.getKey());
				}
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] could not update intake chain " + pChainId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	/** The chains of a project in status RUNNING (normally zero or one). */
	static List<Adaptation> runningChains(final Adaptation pFactory, final String pProjectIdOrNull)
	{
		final String predicate = "./status=" + CatalogReader.quote(FactoryPaths.IntakeChain.STATUS_RUNNING)
			+ (pProjectIdOrNull == null ? "" : " and ./project=" + CatalogReader.quote(pProjectIdOrNull));
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.INTAKE_CHAIN).createRequestResult(predicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** The latest chains of a project, newest first. */
	static List<IntakeStatus.Chain> latestChains(final Adaptation pFactory, final String pProjectId, final int pLimit)
	{
		final List<Adaptation> rows = new ArrayList<Adaptation>();
		final RequestResult result = pFactory.getTable(FactoryPaths.INTAKE_CHAIN).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = result.nextAdaptation(); row != null; row = result.nextAdaptation())
			{
				rows.add(row);
			}
		}
		finally
		{
			result.close();
		}
		Collections.sort(rows, new Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pA, final Adaptation pB)
			{
				final long a = IntakeStore.time(pA, FactoryPaths.IntakeChain.STARTED_AT);
				final long b = IntakeStore.time(pB, FactoryPaths.IntakeChain.STARTED_AT);
				return a == b ? 0 : a < b ? 1 : -1;
			}
		});
		final List<IntakeStatus.Chain> out = new ArrayList<IntakeStatus.Chain>();
		for (final Adaptation row : rows)
		{
			if (out.size() >= pLimit)
			{
				break;
			}
			out.add(IntakeStore.toChain(row));
		}
		return out;
	}

	static IntakeStatus.Chain toChain(final Adaptation pRow)
	{
		final IntakeStatus.Chain chain = new IntakeStatus.Chain();
		chain.id = pRow.getOccurrencePrimaryKey().format();
		chain.startedAt = IntakeStore.instant(pRow, FactoryPaths.IntakeChain.STARTED_AT);
		chain.endedAt = IntakeStore.instant(pRow, FactoryPaths.IntakeChain.ENDED_AT);
		chain.reason = pRow.getString(FactoryPaths.IntakeChain.REASON);
		final String sources = pRow.getString(FactoryPaths.IntakeChain.SOURCES);
		if (sources != null && !sources.trim().isEmpty())
		{
			for (final String source : sources.split(","))
			{
				if (!source.trim().isEmpty())
				{
					chain.sources.add(source.trim());
				}
			}
		}
		chain.rowsWaiting = IntakeStore.number(pRow, FactoryPaths.IntakeChain.ROWS_WAITING);
		chain.written = IntakeStore.number(pRow, FactoryPaths.IntakeChain.WRITTEN);
		chain.unchanged = IntakeStore.number(pRow, FactoryPaths.IntakeChain.UNCHANGED);
		chain.rejected = IntakeStore.number(pRow, FactoryPaths.IntakeChain.REJECTED);
		chain.status = pRow.getString(FactoryPaths.IntakeChain.STATUS);
		chain.message = pRow.getString(FactoryPaths.IntakeChain.MESSAGE);
		chain.workflowKey = pRow.getString(FactoryPaths.IntakeChain.WORKFLOW_KEY);
		return chain;
	}

	static long time(final Adaptation pRow, final Path pPath)
	{
		final Object value = pRow.get(pPath);
		return value instanceof Date ? ((Date) value).getTime() : 0L;
	}

	static Instant instant(final Adaptation pRow, final Path pPath)
	{
		final Object value = pRow.get(pPath);
		return value instanceof Date ? Instant.ofEpochMilli(((Date) value).getTime()) : null;
	}

	static Long number(final Adaptation pRow, final Path pPath)
	{
		final Object value = pRow.get(pPath);
		return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
	}

	static String join(final List<String> pValues)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String value : pValues)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(value);
		}
		return TextLimits.truncate(sb.toString(), 400);
	}
}
