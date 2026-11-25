package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The {@code DomainObject} rows of the generation (lot C1-D): what each step wrote, for the summary, the step reset and the project deletion.
 * One row per (project, kind, name); a step run again updates the row it already wrote instead of adding a second.
 */
final class DomainObjectWrites
{
	/** A row read back. */
	static final class Row
	{
		final String id;
		final String kind;
		final String name;
		final String path;
		final String detail;
		final String stepId;

		Row(final String pId, final String pKind, final String pName, final String pPath, final String pDetail, final String pStepId)
		{
			this.id = pId;
			this.kind = pKind;
			this.name = pName;
			this.path = pPath;
			this.detail = pDetail;
			this.stepId = pStepId;
		}
	}

	private DomainObjectWrites()
	{
	}

	/** Writes (or updates) the row. {@code pStepId} is the primary key of the {@code PipelineStep} that wrote it. */
	static void record(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pKind, final String pName, final String pPath,
		final String pDetail, final String pStepId) throws OperationException
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.DOMAIN_OBJECT);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				Adaptation existing = null;
				final RequestResult rows = table.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./kind=" + CatalogReader.quote(pKind)
					+ " and ./name=" + CatalogReader.quote(pName));
				try
				{
					existing = rows.nextAdaptation();
				}
				finally
				{
					rows.close();
				}
				final ValueContextForUpdate value = existing == null ? pContext.getContextForNewOccurrence(table) : pContext.getContext(existing.getAdaptationName());
				if (existing == null)
				{
					value.setValue(pProjectId, FactoryPaths.DomainObject.PROJECT);
					value.setValue(pKind, FactoryPaths.DomainObject.KIND);
					value.setValue(pName, FactoryPaths.DomainObject.NAME);
				}
				value.setValue(pPath, FactoryPaths.DomainObject.PATH);
				value.setValue(pDetail, FactoryPaths.DomainObject.DETAIL);
				value.setValue(new Date(), FactoryPaths.DomainObject.CREATED_ON);
				if (pStepId != null)
				{
					value.setValue(pStepId, FactoryPaths.DomainObject.STEP);
				}
				if (existing == null)
				{
					pContext.doCreateOccurrence(value, table);
				}
				else
				{
					pContext.doModifyContent(existing, value);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.domainObjectWrite", "Could not record the generated " + pKind + " " + pName + ": " + result.getExceptionFullMessage(Locale.ENGLISH),
				pKind, pName);
		}
	}

	static List<Row> list(final Adaptation pFactory, final String pProjectId)
	{
		final List<Row> out = new ArrayList<Row>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.DOMAIN_OBJECT).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(new Row(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.DomainObject.KIND), row.getString(FactoryPaths.DomainObject.NAME),
					row.getString(FactoryPaths.DomainObject.PATH), row.getString(FactoryPaths.DomainObject.DETAIL), row.getString(FactoryPaths.DomainObject.STEP)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Deletes the rows (by primary key) once the objects they describe are gone. */
	static void delete(final Session pSession, final Adaptation pFactory, final List<String> pIds) throws OperationException
	{
		if (pIds.isEmpty())
		{
			return;
		}
		final AdaptationTable table = pFactory.getTable(FactoryPaths.DOMAIN_OBJECT);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final String id : pIds)
				{
					final Adaptation row = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(id));
					if (row != null)
					{
						pContext.doDelete(row.getAdaptationName(), false);
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.domainObjectDelete", "Could not delete the DomainObject rows: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	static String projectLabelOrCode(final Adaptation pProject)
	{
		final String label = pProject.getString(FactoryPaths.Project.LABEL);
		return label == null || label.trim().isEmpty() ? pProject.getString(FactoryPaths.Project.CODE) : label;
	}
}
