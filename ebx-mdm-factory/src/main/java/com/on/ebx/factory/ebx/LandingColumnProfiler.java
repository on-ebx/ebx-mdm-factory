package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ColumnProfileSql;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Staging-proposal lot 0 (docs/STAGING-PROPOSAL.md): measures {@code LandingColumn.fillRate}/{@code
 * distinctCount}/{@code maxLength} against the real landing data - null on every column for a sample-file
 * source today (measured live on project Personnes, 2026-09-23: {@code LandingPlanner}'s schema scan never
 * profiles a sample file the way a live database connector's own {@code profile} operation would, see
 * PLAN.md's "Suggestion du staging"). One EBX SQL aggregate query per {@code LandingTable} ({@link
 * ColumnProfileSql#build}), never one per column - the pure SQL text and its interpretation live there;
 * this class only resolves the EBX data, runs the query, and writes the result back, the same {@code
 * landing.RelationCheck}/{@code ebx.RelationChecker} split this module already keeps for its other
 * generated-SQL feature.
 * <p>
 * <b>Never throws for one bad table</b> (task brief): a table whose landing dataset cannot be resolved, or
 * whose generated query the EBX SQL engine refuses, is skipped - logged to {@code System.err} (this module
 * has no logging framework anywhere else to plug into - see {@code ColumnProfileSql}'s own Javadoc for
 * where the one real uncertainty of this lot, the {@code CHAR_LENGTH} function name, would surface as
 * exactly this kind of skip) - and the run continues with the next table.
 */
public final class LandingColumnProfiler
{
	private LandingColumnProfiler()
	{
	}

	/**
	 * Measures every selected {@code LandingTable} of the project {@code pProjectCode}, one EBX SQL query per
	 * table, and writes {@code fillRate}/{@code distinctCount}/{@code maxLength} back onto each {@code
	 * LandingColumn} - one {@code Procedure} per table (task brief). {@code pOnlyMissing}: {@code true} skips
	 * a table outright when every one of its columns already carries a non-null {@code fillRate} (no query
	 * run at all), and otherwise still runs the table's query but only OVERWRITES a column whose stored
	 * {@code fillRate} is currently null - an already-profiled column's stored value is left untouched
	 * either way. {@code false} always runs every selected table's query and always overwrites every column.
	 * Returns how many {@code LandingColumn} rows were actually written (0 when the project has no selected,
	 * resolvable table, or nothing needed re-profiling under {@code pOnlyMissing}) - never throws for a
	 * single table's own failure, see the class Javadoc.
	 */
	public static int profile(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pOnlyMissing)
	{
		final Adaptation project = LandingColumnProfiler.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			return 0;
		}
		final String landingDataspace = project.getString(FactoryPaths.Project.LANDING_DATASPACE);
		if (landingDataspace == null || landingDataspace.trim().isEmpty())
		{
			return 0;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		int written = 0;
		for (final ProjectSourceRef source : LandingColumnProfiler.projectSourcesOf(pFactory, projectId))
		{
			final Adaptation landing = LandingColumnProfiler.landingDataset(pFactory, landingDataspace, source.landingDataset);
			if (landing == null)
			{
				System.err.println("LandingColumnProfiler: landing dataset " + landingDataspace + "/" + source.landingDataset + " (project source "
					+ source.id + ") not found - skipping every table of this source.");
				continue;
			}
			for (final TableRef table : LandingColumnProfiler.selectedTablesOf(pFactory, source.id))
			{
				try
				{
					written += LandingColumnProfiler.profileOneTable(pSession, pFactory, landing, table, pOnlyMissing);
				}
				catch (final Exception ex)
				{
					System.err.println(
						"LandingColumnProfiler: table " + table.tableName + " (landing table " + table.id + ") could not be profiled - skipped: " + ex);
				}
			}
		}
		return written;
	}

	// --------------------------------------------------------------------------------------------------- one table

	private static int profileOneTable(final Session pSession, final Adaptation pFactory, final Adaptation pLanding, final TableRef pTable,
		final boolean pOnlyMissing) throws OperationException
	{
		final List<ColumnRef> columns = LandingColumnProfiler.columnsOf(pFactory, pTable.id);
		if (columns.isEmpty())
		{
			return 0;
		}
		if (pOnlyMissing && LandingColumnProfiler.everyColumnAlreadyProfiled(columns))
		{
			return 0;
		}
		final List<ColumnProfileSql.ColumnRef> refs = new ArrayList<ColumnProfileSql.ColumnRef>();
		for (final ColumnRef column : columns)
		{
			refs.add(new ColumnProfileSql.ColumnRef(column.id, column.fieldName));
		}
		// volume-guards lot (2026-09-28, task item 7): bounded sample, not the whole table - see
		// ColumnProfileSql's own Javadoc (EBX SQL LIMIT, confirmed live via get_query_syntax).
		final ColumnProfileSql.Statement statement = ColumnProfileSql.build(pTable.tableName, refs, LandingColumnProfiler.sampleRows(pFactory));
		final Map<String, Object> row = LandingColumnProfiler.runOne(pLanding, pSession, statement);
		if (row == null)
		{
			return 0;
		}
		if (ColumnProfileSql.sampled(statement, LandingColumnProfiler.rowCountOf(row)))
		{
			// diagnostic only (task item 7's "report sampled" - see StagingLlmPass.systemPrompt's own generic
			// caveat for what actually reaches the language model): no LandingColumn/LandingTable field stores
			// this per-table flag today, and adding one is a larger XSD/UI change than this guard-rail lot's own
			// scope - see docs/VOLUME.md "Lot 1b" for the reasoning.
			System.err.println("LandingColumnProfiler: table " + pTable.tableName + " (landing table " + pTable.id + ") profiled on a sample of "
				+ statement.sampleLimit + " row(s) - it may have more rows than that.");
		}
		final List<ColumnProfileSql.ColumnProfile> profiles = ColumnProfileSql.interpret(statement, row);
		return LandingColumnProfiler.writeAll(pSession, pFactory, columns, profiles, pOnlyMissing);
	}

	/** {@code FactorySettings.columnProfileSampleRows} (default 50000, see tools/build_xsd.py); 0 or unreadable settings: unbounded (whole-table profiling, the pre-existing behaviour). */
	private static int sampleRows(final Adaptation pFactory)
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		final Object value = settings == null ? null : settings.get(FactoryPaths.Settings.COLUMN_PROFILE_SAMPLE_ROWS);
		return value instanceof Number ? ((Number) value).intValue() : 0;
	}

	/** The same {@code ROW_COUNT} coercion {@link ColumnProfileSql#interpret} applies internally - read here too, for the {@link ColumnProfileSql#sampled} check, without duplicating the query. */
	private static long rowCountOf(final Map<String, Object> pRow)
	{
		final Object value = pRow.get("ROW_COUNT");
		return value instanceof Number ? ((Number) value).longValue() : 0L;
	}

	private static boolean everyColumnAlreadyProfiled(final List<ColumnRef> pColumns)
	{
		for (final ColumnRef column : pColumns)
		{
			if (!column.alreadyProfiled)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * One row (or {@code null} on an empty result, which should not happen for a bare aggregate query but is
	 * handled defensively), alias -&gt; raw value - the boundary {@link ColumnProfileSql#interpret} reads
	 * through. Reads exactly {@code pStatement.columnIds.size()} {@code F<i>}/{@code D<i>}/{@code L<i>}
	 * triples - the statement itself already fixes how many columns it selected, so this never needs to
	 * guess by probing the tuple.
	 */
	private static Map<String, Object> runOne(final Adaptation pLanding, final Session pSession, final ColumnProfileSql.Statement pStatement)
	{
		final Query<Tuple> query = pLanding.createQuery(pStatement.sql);
		query.setSession(pSession);
		final QueryResult<Tuple> result = query.getResult();
		try
		{
			final java.util.Iterator<Tuple> it = result.iterator();
			if (!it.hasNext())
			{
				return null;
			}
			final Tuple tuple = it.next();
			final Map<String, Object> out = new HashMap<String, Object>();
			out.put("ROW_COUNT", tuple.get("ROW_COUNT"));
			for (int i = 0; i < pStatement.columnIds.size(); i++)
			{
				out.put("F" + i, tuple.get("F" + i));
				out.put("D" + i, tuple.get("D" + i));
				out.put("L" + i, tuple.get("L" + i));
			}
			return out;
		}
		finally
		{
			result.close();
		}
	}

	// --------------------------------------------------------------------------------------------------- write-back

	private static int writeAll(final Session pSession, final Adaptation pFactory, final List<ColumnRef> pColumns,
		final List<ColumnProfileSql.ColumnProfile> pProfiles, final boolean pOnlyMissing) throws OperationException
	{
		final Map<String, ColumnProfileSql.ColumnProfile> byColumnId = new HashMap<String, ColumnProfileSql.ColumnProfile>();
		for (final ColumnProfileSql.ColumnProfile profile : pProfiles)
		{
			byColumnId.put(profile.columnId, profile);
		}
		final List<ColumnRef> toWrite = new ArrayList<ColumnRef>();
		for (final ColumnRef column : pColumns)
		{
			if (pOnlyMissing && column.alreadyProfiled)
			{
				continue;
			}
			if (byColumnId.containsKey(column.id))
			{
				toWrite.add(column);
			}
		}
		if (toWrite.isEmpty())
		{
			return 0;
		}
		final AdaptationTable columns = pFactory.getTable(FactoryPaths.LANDING_COLUMN);
		final int[] count = { 0 };
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final ColumnRef column : toWrite)
				{
					final Adaptation row = columns.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(column.id));
					if (row == null)
					{
						continue;
					}
					final ColumnProfileSql.ColumnProfile profile = byColumnId.get(column.id);
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(profile.fillRatePercent, FactoryPaths.LandingColumn.FILL_RATE);
					value.setValue(BigDecimal.valueOf(profile.distinctCount), FactoryPaths.LandingColumn.DISTINCT_COUNT);
					value.setValue(profile.maxLength, FactoryPaths.LandingColumn.MAX_LENGTH);
					pContext.doModifyContent(row, value);
					count[0]++;
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveColumnProfile", "Writing the column profile failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		return count[0];
	}

	// --------------------------------------------------------------------------------------------------- reads

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	private static final class ProjectSourceRef
	{
		final String id;
		final String landingDataset;

		ProjectSourceRef(final String pId, final String pLandingDataset)
		{
			this.id = pId;
			this.landingDataset = pLandingDataset;
		}
	}

	private static List<ProjectSourceRef> projectSourcesOf(final Adaptation pFactory, final String pProjectId)
	{
		final List<ProjectSourceRef> out = new ArrayList<ProjectSourceRef>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String landingDataset = row.getString(FactoryPaths.ProjectSource.LANDING_DATASET);
				if (landingDataset != null && !landingDataset.trim().isEmpty())
				{
					out.add(new ProjectSourceRef(row.getOccurrencePrimaryKey().format(), landingDataset));
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static final class TableRef
	{
		final String id;
		final String tableName;

		TableRef(final String pId, final String pTableName)
		{
			this.id = pId;
			this.tableName = pTableName;
		}
	}

	/** Every {@code SELECTED} {@code LandingTable} of one project source (same {@code SELECTED} convention as {@code ebx.LandingGenerator}/{@code ebx.LandingFlows}: absent/true means selected, only an explicit {@code false} excludes it). */
	private static List<TableRef> selectedTablesOf(final Adaptation pFactory, final String pProjectSourceId)
	{
		final List<TableRef> out = new ArrayList<TableRef>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_TABLE).createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (Boolean.FALSE.equals(row.get(FactoryPaths.LandingTable.SELECTED)))
				{
					continue;
				}
				final String tableName = row.getString(FactoryPaths.LandingTable.TABLE_NAME);
				if (tableName != null && !tableName.trim().isEmpty())
				{
					out.add(new TableRef(row.getOccurrencePrimaryKey().format(), tableName));
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static final class ColumnRef
	{
		final String id;
		final String fieldName;
		final boolean alreadyProfiled;

		ColumnRef(final String pId, final String pFieldName, final boolean pAlreadyProfiled)
		{
			this.id = pId;
			this.fieldName = pFieldName;
			this.alreadyProfiled = pAlreadyProfiled;
		}
	}

	private static List<ColumnRef> columnsOf(final Adaptation pFactory, final String pLandingTableId)
	{
		final List<ColumnRef> out = new ArrayList<ColumnRef>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(pLandingTableId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String fieldName = row.getString(FactoryPaths.LandingColumn.FIELD_NAME);
				if (fieldName == null || fieldName.trim().isEmpty())
				{
					continue;
				}
				out.add(new ColumnRef(row.getOccurrencePrimaryKey().format(), fieldName, row.get(FactoryPaths.LandingColumn.FILL_RATE) != null));
			}
		}
		finally
		{
			rows.close();
		}
		java.util.Collections.sort(out, new Comparator<ColumnRef>()
		{
			@Override
			public int compare(final ColumnRef pLeft, final ColumnRef pRight)
			{
				return Integer.compare(Integer.parseInt(pLeft.id), Integer.parseInt(pRight.id));
			}
		});
		return out;
	}

	private static Adaptation landingDataset(final Adaptation pFactory, final String pLandingDataspace, final String pLandingDataset)
	{
		final AdaptationHome home = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(pLandingDataspace));
		return home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pLandingDataset));
	}
}
