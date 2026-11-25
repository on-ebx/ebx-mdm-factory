package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.accel.DomainPlan;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;

/** Reads the decisions of a project (chosen packs, ACCEPTED differences) as the pure {@link DomainPlan} wants them. */
final class DomainInputs
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DomainInputs()
	{
	}

	static List<DomainPlan.PackChoice> packs(final Adaptation pFactory, final String pProjectId)
	{
		final List<DomainPlan.PackChoice> out = new ArrayList<DomainPlan.PackChoice>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT_PACK).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final List<String> options = new ArrayList<String>();
				final List<?> raw = row.getList(FactoryPaths.ProjectPack.OPTIONS);
				if (raw != null)
				{
					for (final Object o : raw)
					{
						options.add(String.valueOf(o));
					}
				}
				out.add(new DomainPlan.PackChoice(row.getString(FactoryPaths.ProjectPack.PACK_KEY), options));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	static List<DomainPlan.Decision> accepted(final Adaptation pFactory, final String pProjectId)
	{
		final List<DomainPlan.Decision> out = new ArrayList<DomainPlan.Decision>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.DIFFERENCE).createRequestResult("./project=" + CatalogReader.quote(pProjectId)
			+ " and (./status='ACCEPTED' or ./status='APPLIED')"); // EBX XPath has no 'in' operator (first real run, 2026-10-12)
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final DomainPlan.Decision d = new DomainPlan.Decision();
				d.id = row.getOccurrencePrimaryKey().format();
				final Object pass = row.get(FactoryPaths.Difference.PASS);
				d.pass = pass instanceof Number ? ((Number) pass).intValue() : 0;
				d.nature = row.getString(FactoryPaths.Difference.NATURE);
				d.what = row.getString(FactoryPaths.Difference.WHAT);
				d.where = row.getString(FactoryPaths.Difference.WHERE);
				d.packKey = row.getString(FactoryPaths.Difference.PACK_KEY);
				d.blockKey = row.getString(FactoryPaths.Difference.BLOCK_KEY);
				d.libraryFunction = row.getString(FactoryPaths.Difference.LIBRARY_FUNCTION);
				d.workflowType = row.getString(FactoryPaths.Difference.WORKFLOW_TYPE);
				final String detail = row.getString(FactoryPaths.Difference.DETAIL);
				if (detail != null && !detail.trim().isEmpty())
				{
					try
					{
						d.detail = DomainInputs.MAPPER.readTree(detail);
					}
					catch (final IOException ex)
					{
						d.detail = null;
					}
				}
				out.add(d);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}
}
