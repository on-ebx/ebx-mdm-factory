package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.accel.Difference;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Lot C1-C: reads and writes the {@code Difference} rows of the adapted proposal, every write in a {@link Procedure} of its own. A line
 * comes in as {@code origin = LLM, status = PROPOSED}; the KEEP lines (one per untouched pack table) are the Factory's own, written
 * {@code origin = RULE, status = ACCEPTED} so that they never hold back the next pass (the person decides what changes, not what stays).
 * Paths come from {@code FactoryPaths}.
 */
final class DifferenceWrites
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** Lengths of the XSD columns ({@code detail} and {@code specification} 20000, {@code what} 300, {@code where} 500, {@code why} and {@code rationale} 2000). */
	static final int TEXT_MAX = 20000;
	static final int WHAT_MAX = 300;
	static final int WHERE_MAX = 500;
	static final int WHY_MAX = 2000;

	private DifferenceWrites()
	{
	}

	/** Creates one row per line; returns how many. A failed write is thrown, never swallowed (nothing is half-written: one Procedure). */
	static int write(final Session pSession, final Adaptation pFactory, final String pProjectId, final int pPass, final List<Difference> pLines, final String pOrigin,
		final String pStatus, final String pRationale) throws OperationException
	{
		if (pLines.isEmpty())
		{
			return 0;
		}
		final AdaptationTable table = pFactory.getTable(FactoryPaths.DIFFERENCE);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Difference line : pLines)
				{
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
					value.setValue(pProjectId, FactoryPaths.Difference.PROJECT);
					value.setValue(Integer.valueOf(pPass), FactoryPaths.Difference.PASS);
					value.setValue(line.nature().name(), FactoryPaths.Difference.NATURE);
					value.setValue(TextLimits.truncate(line.what(), DifferenceWrites.WHAT_MAX), FactoryPaths.Difference.WHAT);
					value.setValue(TextLimits.truncate(line.where(), DifferenceWrites.WHERE_MAX), FactoryPaths.Difference.WHERE);
					value.setValue(line.placeState().name(), FactoryPaths.Difference.PLACE_STATE);
					value.setValue(line.detail() == null ? null : line.detailJson(), FactoryPaths.Difference.DETAIL);
					value.setValue(TextLimits.truncate(line.why(), DifferenceWrites.WHY_MAX), FactoryPaths.Difference.WHY);
					value.setValue(nullIfEmpty(line.packKey()), FactoryPaths.Difference.PACK_KEY);
					value.setValue(nullIfEmpty(line.blockKey()), FactoryPaths.Difference.BLOCK_KEY);
					value.setValue(nullIfEmpty(line.libraryFunction()), FactoryPaths.Difference.LIBRARY_FUNCTION);
					value.setValue(nullIfEmpty(line.workflowType()), FactoryPaths.Difference.WORKFLOW_TYPE);
					value.setValue(pOrigin, FactoryPaths.Difference.ORIGIN);
					value.setValue(TextLimits.truncate(pRationale, DifferenceWrites.WHY_MAX), FactoryPaths.Difference.RATIONALE);
					value.setValue(pStatus, FactoryPaths.Difference.STATUS);
					pContext.doCreateOccurrence(value, table);
				}
			}
		});
		if (result.hasFailed())
		{
			throw new IllegalStateException("Could not write the differences of pass " + pPass + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		return pLines.size();
	}

	private static String nullIfEmpty(final String pText)
	{
		return pText == null || pText.isEmpty() ? null : pText;
	}

	/**
	 * Removes what an earlier run of the same pass proposed and nobody decided: the {@code LLM} lines still {@code PROPOSED} of the pass and, for pass 1, the
	 * Factory's KEEP lines. Decided lines (accepted, rejected, applied) stay. @return how many rows were removed
	 */
	static int clearProposed(final Session pSession, final Adaptation pFactory, final String pProjectId, final int pPass) throws OperationException
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.DIFFERENCE);
		final List<Adaptation> doomed = new ArrayList<Adaptation>();
		final RequestResult rows = table.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./pass=" + pPass);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String origin = row.getString(FactoryPaths.Difference.ORIGIN);
				final String status = row.getString(FactoryPaths.Difference.STATUS);
				final boolean llmProposal = "LLM".equals(origin) && "PROPOSED".equals(status);
				final boolean keep = "KEEP".equals(row.getString(FactoryPaths.Difference.NATURE)) && "RULE".equals(origin);
				if (llmProposal || keep)
				{
					doomed.add(row);
				}
			}
		}
		finally
		{
			rows.close();
		}
		if (doomed.isEmpty())
		{
			return 0;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Adaptation row : doomed)
				{
					pContext.doDelete(row.getAdaptationName(), false);
				}
			}
		});
		if (result.hasFailed())
		{
			throw new IllegalStateException("Could not clear the earlier proposals of pass " + pPass + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		return doomed.size();
	}

	/** The lines standing before pass {@code pBeforePass}: every row of the project of a lower pass that is not rejected, KEEP excluded. Order of the rows. */
	static List<Difference> standingBefore(final Adaptation pFactory, final String pProjectId, final int pBeforePass)
	{
		final List<Difference> out = new ArrayList<Difference>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.DIFFERENCE).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./pass<" + pBeforePass + " and ./status!='REJECTED' and ./nature!='KEEP'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Difference d = DifferenceWrites.toDifference(row);
				if (d != null)
				{
					out.add(d);
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** How many lines of the pass still wait for a decision. */
	static int countProposed(final Adaptation pFactory, final String pProjectId, final int pPass)
	{
		int n = 0;
		final RequestResult rows = pFactory.getTable(FactoryPaths.DIFFERENCE).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./pass=" + pPass + " and ./status='PROPOSED'");
		try
		{
			while (rows.nextAdaptation() != null)
			{
				n++;
			}
		}
		finally
		{
			rows.close();
		}
		return n;
	}

	/** Null when the row holds a nature the pure side does not know. */
	static Difference toDifference(final Adaptation pRow)
	{
		final Difference.Nature nature = Difference.Nature.parse(pRow.getString(FactoryPaths.Difference.NATURE));
		if (nature == null)
		{
			return null;
		}
		final Object pass = pRow.get(FactoryPaths.Difference.PASS);
		JsonNode detail = null;
		final String detailText = pRow.getString(FactoryPaths.Difference.DETAIL);
		if (detailText != null && !detailText.trim().isEmpty())
		{
			try
			{
				detail = DifferenceWrites.MAPPER.readTree(detailText);
			}
			catch (final Exception ex)
			{
				detail = DifferenceWrites.MAPPER.createObjectNode().put("text", detailText);
			}
		}
		final String place = pRow.getString(FactoryPaths.Difference.PLACE_STATE);
		return new Difference(pass instanceof Number ? ((Number) pass).intValue() : 0, nature, pRow.getString(FactoryPaths.Difference.WHAT),
			pRow.getString(FactoryPaths.Difference.WHERE), "TO_PLACE".equals(place) ? Difference.PlaceState.TO_PLACE : Difference.PlaceState.PLACED, detail,
			pRow.getString(FactoryPaths.Difference.WHY), pRow.getString(FactoryPaths.Difference.PACK_KEY), pRow.getString(FactoryPaths.Difference.BLOCK_KEY),
			pRow.getString(FactoryPaths.Difference.LIBRARY_FUNCTION), pRow.getString(FactoryPaths.Difference.WORKFLOW_TYPE));
	}

	/** Convenience for the run: the paths a set of lines touches. */
	static boolean touches(final Collection<Difference> pLines, final String pTablePath)
	{
		for (final Difference d : pLines)
		{
			final String where = d.where();
			if (where.equals(pTablePath) || where.startsWith(pTablePath + "/"))
			{
				return true;
			}
		}
		return false;
	}
}
