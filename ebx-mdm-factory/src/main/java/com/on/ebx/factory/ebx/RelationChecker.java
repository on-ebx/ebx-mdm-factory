package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.RelationCheck;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.query.CteQueryNotImplementableException;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryParseException;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Checks every {@code LandingRelation} of one project source, whose two tables are both {@code LOADED},
 * against the values actually loaded: {@link RelationCheck} builds the SQL, this class runs it with
 * {@code Adaptation.createQuery}/{@code QueryResult} (see the class Javadoc for the exact iterate/close
 * shape) against the project source's landing dataset, under the caller's own session - unlike the
 * generators of this package, this never calls out to an MCP server or acts as the factory's agent account:
 * it only reads landing data and writes evidence into the factory dataset, both with the rights of whoever
 * called it. {@code checkedAt}, {@code childValues}, {@code orphanValues} and {@code cardinality} are
 * always refreshed for every relation checked, whatever its own status - a person may want to reconsider an
 * already-{@code REJECTED} relation once its evidence changes. {@code confidence} is the one field this
 * only ever adjusts for a relation still {@code RULE}/{@code PROPOSED}: a line a person has already decided
 * (or one that came from a declared source, the language model, or a person directly) keeps whatever
 * confidence it already had - {@link #confidenceNote} always says why, one way or the other.
 * <p>
 * <b>No {@code PipelineStep} is written for this action.</b> Every other generator in this package
 * (`LandingGenerator`, `LandingFlows`) records one because the model declares a fixed {@code step}
 * enumeration and each of those names a real member of it; this check has no member of its own, and the
 * XSD is off limits for this change (the fields this needs already exist) - so nothing is recorded, and the
 * answer says so explicitly ({@code pipelineStepRecorded: false}) rather than leaving the caller to notice
 * an absence.
 * <p>
 * A statement {@link RelationCheck#build} generates that the EBX SQL engine refuses to compile
 * ({@link QueryParseException}/{@link CteQueryNotImplementableException} - both unchecked, see the class
 * Javadoc's own evidence) falls back, for that one relation only, to a record-by-record scan
 * ({@link #scanFallback}: a {@code HashSet} of parent keys built with the same
 * {@code AdaptationTable.createRequestResult} pattern {@code CatalogReader} already establishes elsewhere
 * in this module) - the rest of the run is unaffected, and the answer names which path
 * ({@code "SQL"}/{@code "SCAN"}) each relation actually took.
 */
public final class RelationChecker
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private RelationChecker()
	{
	}

	public static ObjectNode check(final Session pSession, final Adaptation pFactory, final String pProjectSourceId) throws OperationException
	{
		final Adaptation source = RelationChecker.projectSource(pFactory, pProjectSourceId);
		final String projectId = source.getString(FactoryPaths.ProjectSource.PROJECT);
		final Adaptation project = RelationChecker.project(pFactory, projectId, pProjectSourceId);
		final String landingDataspace = project.getString(FactoryPaths.Project.LANDING_DATASPACE);
		if (landingDataspace == null || landingDataspace.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.landingZone", "Project source " + pProjectSourceId + " has no landing dataspace yet: generate and load its landing first", pProjectSourceId);
		}
		final String landingDatasetName = source.getString(FactoryPaths.ProjectSource.LANDING_DATASET);
		if (landingDatasetName == null || landingDatasetName.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.landingZone", "Project source " + pProjectSourceId + " has no landing dataset yet: generate and load its landing first", pProjectSourceId);
		}
		final Adaptation landing = RelationChecker.landingDataset(pFactory, landingDataspace, landingDatasetName);
		if (landing == null)
		{
			throw EngineRefusal.coded("missing.landingZone", "Landing dataset " + landingDataspace + "/" + landingDatasetName + " of project source " + pProjectSourceId + " does not exist: generate its landing first", pProjectSourceId);
		}

		final Map<String, TableInfo> tables = RelationChecker.tablesOf(pFactory, pProjectSourceId);
		final List<Adaptation> relations = new ArrayList<Adaptation>();
		final RequestResult relationRows = pFactory.getTable(FactoryPaths.LANDING_RELATION).createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			for (Adaptation row = relationRows.nextAdaptation(); row != null; row = relationRows.nextAdaptation())
			{
				relations.add(row);
			}
		}
		finally
		{
			relationRows.close();
		}

		final ObjectNode out = RelationChecker.MAPPER.createObjectNode();
		out.put("projectSource", pProjectSourceId);
		final ArrayNode relationsOut = out.putArray("relations");

		final Map<String, Map<String, String>> fieldNameCache = new HashMap<String, Map<String, String>>();
		final List<Write> pending = new ArrayList<Write>();
		int checked = 0;
		int skippedNotLoaded = 0;
		int skippedColumnNotFound = 0;
		int skippedUnsafeIdentifier = 0;
		int skippedTooLarge = 0;
		int sqlPath = 0;
		int scanPath = 0;

		for (final Adaptation relation : relations)
		{
			final String relationId = relation.getOccurrencePrimaryKey().format();
			final String fromTableId = relation.getString(FactoryPaths.LandingRelation.FROM_TABLE);
			final String toTableId = relation.getString(FactoryPaths.LandingRelation.TO_TABLE);
			final TableInfo fromInfo = tables.get(fromTableId);
			final TableInfo toInfo = tables.get(toTableId);

			final ObjectNode line = relationsOut.addObject();
			line.put("id", relationId);
			line.put("fromTable", fromInfo == null ? fromTableId : fromInfo.tableName);
			line.put("toTable", toInfo == null ? toTableId : toInfo.tableName);

			if (fromInfo == null || toInfo == null || !"LOADED".equals(fromInfo.status) || !"LOADED".equals(toInfo.status))
			{
				line.put("outcome", "SKIPPED_NOT_LOADED");
				skippedNotLoaded++;
				continue;
			}

			final List<String> fromSourceColumns = RelationChecker.strings(relation.getList(FactoryPaths.LandingRelation.FROM_COLUMNS));
			final List<String> toSourceColumns = RelationChecker.strings(relation.getList(FactoryPaths.LandingRelation.TO_COLUMNS));
			final List<String> fromFields = RelationChecker.resolveFields(pFactory, fromTableId, fromSourceColumns, fieldNameCache);
			final List<String> toFields = RelationChecker.resolveFields(pFactory, toTableId, toSourceColumns, fieldNameCache);
			if (fromFields == null || toFields == null)
			{
				line.put("outcome", "SKIPPED_COLUMN_NOT_FOUND");
				skippedColumnNotFound++;
				continue;
			}

			final RelationCheck.Statements statements;
			try
			{
				statements = RelationCheck.build(fromInfo.tableName, fromFields, toInfo.tableName, toFields);
			}
			catch (final IllegalArgumentException ex)
			{
				line.put("outcome", "SKIPPED_UNSAFE_IDENTIFIER");
				line.put("error", ex.getMessage());
				skippedUnsafeIdentifier++;
				continue;
			}

			// not final: the try block has two statements that could each throw, so a static definite-assignment
			// check cannot tell whether "figures" already holds a value by the time a catch block runs - only
			// the fallback branches need to (re)assign these, and they always do, exactly once each.
			Figures figures;
			List<String> sample;
			String path;
			try
			{
				figures = RelationChecker.runCount(landing, pSession, statements.countSql);
				sample = RelationChecker.runSample(landing, pSession, statements.sampleSql);
				path = "SQL";
				sqlPath++;
			}
			catch (final QueryParseException ex)
			{
				line.put("sqlRefused", ex.getMessage());
				final FallbackResult fallback = RelationChecker.scanFallback(landing, fromInfo.tableName, fromFields, toInfo.tableName, toFields);
				if (!fallback.complete)
				{
					line.put("outcome", "SKIPPED_TOO_LARGE");
					line.put("message", "Not checked: the SQL engine refused the anti-join and the tables hold more than "
						+ RelationChecker.MAX_SCAN_KEYS + " distinct key values, beyond what the record-by-record fallback keeps in memory.");
					skippedTooLarge++;
					continue;
				}
				figures = new Figures(fallback.childRows, fallback.distinctChild, fallback.orphanChild);
				sample = fallback.sample;
				path = "SCAN";
				scanPath++;
			}
			catch (final CteQueryNotImplementableException ex)
			{
				line.put("sqlRefused", ex.getMessage());
				final FallbackResult fallback = RelationChecker.scanFallback(landing, fromInfo.tableName, fromFields, toInfo.tableName, toFields);
				if (!fallback.complete)
				{
					line.put("outcome", "SKIPPED_TOO_LARGE");
					line.put("message", "Not checked: the SQL engine refused the anti-join and the tables hold more than "
						+ RelationChecker.MAX_SCAN_KEYS + " distinct key values, beyond what the record-by-record fallback keeps in memory.");
					skippedTooLarge++;
					continue;
				}
				figures = new Figures(fallback.childRows, fallback.distinctChild, fallback.orphanChild);
				sample = fallback.sample;
				path = "SCAN";
				scanPath++;
			}

			final RelationCheck.Result interpreted = RelationCheck.interpret(figures.distinctChild, figures.childRows, figures.orphanChild, sample);

			final Integer confidenceBefore = RelationChecker.confidence(relation);
			final String origin = relation.getString(FactoryPaths.LandingRelation.ORIGIN);
			final String status = relation.getString(FactoryPaths.LandingRelation.STATUS);
			final boolean eligible = "RULE".equals(origin) && "PROPOSED".equals(status);
			Integer confidenceAfter = confidenceBefore;
			if (eligible && confidenceBefore != null && interpreted.confidenceDelta != 0)
			{
				confidenceAfter = Integer.valueOf(RelationCheck.adjustConfidence(confidenceBefore.intValue(), interpreted.confidenceDelta));
			}
			final String message = TextLimits.truncate(
				interpreted.message + RelationChecker.confidenceNote(eligible, confidenceBefore, confidenceAfter, origin, status), TextLimits.MESSAGE_MAX_LENGTH);

			pending.add(new Write(relationId, figures.distinctChild, figures.orphanChild, interpreted.cardinality, message,
				eligible && confidenceBefore != null && !confidenceAfter.equals(confidenceBefore) ? confidenceAfter : null));

			line.put("outcome", interpreted.outcome.name());
			if (interpreted.cardinality != null)
			{
				line.put("cardinality", interpreted.cardinality);
			}
			line.put("distinctChildValues", figures.distinctChild);
			line.put("orphanValues", figures.orphanChild);
			line.put("path", path);
			if (confidenceBefore != null)
			{
				line.put("confidenceBefore", confidenceBefore.intValue());
			}
			if (confidenceAfter != null)
			{
				line.put("confidenceAfter", confidenceAfter.intValue());
			}
			line.put("message", message);
			checked++;
		}

		if (!pending.isEmpty())
		{
			RelationChecker.writeAll(pSession, pFactory, pending);
		}

		out.put("checked", checked);
		out.put("skippedNotLoaded", skippedNotLoaded);
		out.put("skippedColumnNotFound", skippedColumnNotFound);
		out.put("skippedUnsafeIdentifier", skippedUnsafeIdentifier);
		out.put("skippedTooLarge", skippedTooLarge);
		out.put("sqlPath", sqlPath);
		out.put("scanPath", scanPath);
		out.put("pipelineStepRecorded", false);
		out.put("note", "No PipelineStep is written for this check: RELATION_CHECK is not one of the model's declared step values, and the XSD is off limits for this change.");
		return out;
	}

	private static String confidenceNote(final boolean pEligible, final Integer pBefore, final Integer pAfter, final String pOrigin, final String pStatus)
	{
		if (pEligible)
		{
			if (pBefore == null)
			{
				return " Confidence left unset: no starting value to adjust.";
			}
			if (pAfter.equals(pBefore))
			{
				return " Confidence unchanged at " + pBefore + ".";
			}
			return " Confidence " + pBefore + " -> " + pAfter + ".";
		}
		return pBefore == null ? "" : " Confidence left at " + pBefore + " (" + pOrigin + "/" + pStatus + ": not a RULE proposal still pending, never adjusted by this check).";
	}

	// --------------------------------------------------------------------------------------------------- lookups

	private static Adaptation projectSource(final Adaptation pFactory, final String pProjectSourceId) throws OperationException
	{
		final Adaptation source = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
		if (source == null)
		{
			throw EngineRefusal.coded("missing.projectSource", "Project source " + pProjectSourceId + " not found", pProjectSourceId);
		}
		return source;
	}

	private static Adaptation project(final Adaptation pFactory, final String pProjectId, final String pProjectSourceId) throws OperationException
	{
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
		if (project == null)
		{
			throw EngineRefusal.coded("missing.projectOfSource", "Project source " + pProjectSourceId + " names project " + pProjectId + ", which was not found", pProjectId, pProjectSourceId);
		}
		return project;
	}

	private static Adaptation landingDataset(final Adaptation pFactory, final String pLandingDataspace, final String pLandingDataset)
	{
		final AdaptationHome home = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(pLandingDataspace));
		return home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pLandingDataset));
	}

	/** One {@code LandingTable} row of the project source, down to what this class needs of it. */
	private static final class TableInfo
	{
		final String tableName;
		final String status;

		TableInfo(final String pTableName, final String pStatus)
		{
			this.tableName = pTableName;
			this.status = pStatus;
		}
	}

	private static Map<String, TableInfo> tablesOf(final Adaptation pFactory, final String pProjectSourceId)
	{
		final Map<String, TableInfo> out = new HashMap<String, TableInfo>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_TABLE).createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.put(row.getOccurrencePrimaryKey().format(),
					new TableInfo(row.getString(FactoryPaths.LandingTable.TABLE_NAME), row.getString(FactoryPaths.LandingTable.STATUS)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Source column name -&gt; generated landing field name, read from {@code LandingColumn}, one lookup per table cached across every relation of the run. */
	private static Map<String, String> fieldNamesOf(final Adaptation pFactory, final String pLandingTableId)
	{
		final Map<String, String> out = new HashMap<String, String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(pLandingTableId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.put(row.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN), row.getString(FactoryPaths.LandingColumn.FIELD_NAME));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** @return the landing field names of {@code pSourceColumns} for table {@code pLandingTableId}, in the same order; {@code null} if any of them cannot be resolved (a column the current landing model no longer has - see {@code LandingPlanner}'s DRIFTED handling). */
	private static List<String> resolveFields(final Adaptation pFactory, final String pLandingTableId, final List<String> pSourceColumns,
		final Map<String, Map<String, String>> pCache)
	{
		Map<String, String> byColumn = pCache.get(pLandingTableId);
		if (byColumn == null)
		{
			byColumn = RelationChecker.fieldNamesOf(pFactory, pLandingTableId);
			pCache.put(pLandingTableId, byColumn);
		}
		final List<String> fields = new ArrayList<String>();
		for (final String sourceColumn : pSourceColumns)
		{
			final String field = byColumn.get(sourceColumn);
			if (field == null)
			{
				return null;
			}
			fields.add(field);
		}
		return fields;
	}

	private static List<String> strings(final List<?> pValues)
	{
		final List<String> out = new ArrayList<String>();
		if (pValues != null)
		{
			for (final Object value : pValues)
			{
				out.add(String.valueOf(value));
			}
		}
		return out;
	}

	private static Integer confidence(final Adaptation pRow)
	{
		final Object value = pRow.get(FactoryPaths.LandingRelation.CONFIDENCE);
		return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
	}

	// --------------------------------------------------------------------------------------------------- SQL execution

	/** The three figures {@code RelationCheck.Statements#countSql} answers in one row (or the scan fallback computes by hand). */
	private static final class Figures
	{
		final long childRows;
		final long distinctChild;
		final long orphanChild;

		Figures(final long pChildRows, final long pDistinctChild, final long pOrphanChild)
		{
			this.childRows = pChildRows;
			this.distinctChild = pDistinctChild;
			this.orphanChild = pOrphanChild;
		}
	}

	/**
	 * Runs {@code pSql} (one row, {@code CHILD_ROWS}/{@code DISTINCT_CHILD}/{@code ORPHAN_CHILD}) against the
	 * landing dataset under the caller's own session. {@code Query<Tuple>} is what {@code
	 * Adaptation.createQuery(String)} returns (verified by {@code javap} against the exact jar path the task
	 * gave: {@code public default Query<Tuple> createQuery(String)}); {@code QueryResult<Tuple>} - what
	 * {@code Query#getResult()} answers - is an {@code Iterable<Tuple>} that {@code AutoCloseable} requires
	 * closing after use (its own shipped Javadoc: "a QueryResult instance must always be closed after its
	 * use", one active iterator at a time, not thread-safe): a plain {@code try}/{@code finally} around the
	 * iteration, the same shape {@code RequestResult} already uses everywhere else in this module.
	 * {@code Tuple#get(String)} reads a column untyped - {@link #asLong} coerces whatever {@code Number}
	 * subtype comes back (this Java query API's own worked example types a bare {@code COUNT(*)} as
	 * {@code Long} via {@code QueryBuilder.build(sql, Long.class)}, unlike EBX Script's {@code
	 * db.field<T>}, which the knowledge base documents coercing a SQL {@code BIGINT} to {@code
	 * BigDecimal} - a different engine, not assumed to share that coercion here without being able to
	 * write a probe row to confirm it live, hence the defensive {@code Number} handling rather than a
	 * hard {@code (Long)} cast).
	 */
	private static Figures runCount(final Adaptation pLanding, final Session pSession, final String pSql)
	{
		final Query<Tuple> query = pLanding.createQuery(pSql);
		query.setSession(pSession);
		final QueryResult<Tuple> result = query.getResult();
		try
		{
			final java.util.Iterator<Tuple> it = result.iterator();
			if (!it.hasNext())
			{
				return new Figures(0, 0, 0);
			}
			final Tuple row = it.next();
			return new Figures(RelationChecker.asLong(row.get("CHILD_ROWS")), RelationChecker.asLong(row.get("DISTINCT_CHILD")),
				RelationChecker.asLong(row.get("ORPHAN_CHILD")));
		}
		finally
		{
			result.close();
		}
	}

	/** Runs the sample statement (one column, {@code ORPHAN_VALUE}, up to {@link RelationCheck#SAMPLE_SIZE} rows), same iterate/close shape as {@link #runCount}. */
	private static List<String> runSample(final Adaptation pLanding, final Session pSession, final String pSql)
	{
		final List<String> out = new ArrayList<String>();
		final Query<Tuple> query = pLanding.createQuery(pSql);
		query.setSession(pSession);
		final QueryResult<Tuple> result = query.getResult();
		try
		{
			for (final Tuple row : result)
			{
				final Object value = row.get(0);
				out.add(value == null ? "" : String.valueOf(value));
			}
		}
		finally
		{
			result.close();
		}
		return out;
	}

	private static long asLong(final Object pValue)
	{
		if (pValue instanceof Number)
		{
			return ((Number) pValue).longValue();
		}
		return pValue == null ? 0L : Long.parseLong(String.valueOf(pValue));
	}

	// --------------------------------------------------------------------------------------------------- fallback scan

	/** The most distinct key values the fallback keeps in memory, per side (lot C4, 2026-09-24): past it, the relation is not checked. */
	static final int MAX_SCAN_KEYS = 2000000;

	private static final class FallbackResult
	{
		final boolean complete;
		final long childRows;
		final long distinctChild;
		final long orphanChild;
		final List<String> sample;

		FallbackResult(final boolean pComplete, final long pChildRows, final long pDistinctChild, final long pOrphanChild, final List<String> pSample)
		{
			this.complete = pComplete;
			this.childRows = pChildRows;
			this.distinctChild = pDistinctChild;
			this.orphanChild = pOrphanChild;
			this.sample = pSample;
		}
	}

	/**
	 * The documented fallback (docs/SOURCE-RELATIONS.md &sect;5) for a relation whose generated anti-join
	 * the EBX SQL engine refuses to compile: the same question, answered record by record instead of by one
	 * SQL statement. Reads every parent row's key into a {@code HashSet} (the same {@code
	 * AdaptationTable#createRequestResult}/{@code RequestResult#nextAdaptation} pattern {@code
	 * CatalogReader} already uses throughout this module), then scans the child table once, counting rows,
	 * distinct values and orphans as it goes - one full table scan of each side, against EBX's own indexed
	 * record store rather than the query engine, which is why this stays a fallback and not the default.
	 */
	private static FallbackResult scanFallback(final Adaptation pLanding, final String pChildTable, final List<String> pChildFields, final String pParentTable,
		final List<String> pParentFields)
	{
		final java.util.Set<String> parentKeys = new java.util.HashSet<String>();
		final AdaptationTable parentTable = pLanding.getTable(Path.parse("/root/" + pParentTable));
		final RequestResult parentRows = parentTable.createRequestResult(null);
		try
		{
			for (Adaptation row = parentRows.nextAdaptation(); row != null; row = parentRows.nextAdaptation())
			{
				final String combo = RelationChecker.combo(row, pParentFields);
				if (combo != null && parentKeys.add(combo) && parentKeys.size() > RelationChecker.MAX_SCAN_KEYS)
				{
					return new FallbackResult(false, 0L, 0L, 0L, new ArrayList<String>());
				}
			}
		}
		finally
		{
			parentRows.close();
		}

		long childRows = 0;
		final java.util.Set<String> distinctChild = new java.util.HashSet<String>();
		long orphanCount = 0L;
		// only the SAMPLE_SIZE smallest orphan values are kept (the sample shown), not all of them
		final java.util.TreeSet<String> orphans = new java.util.TreeSet<String>();
		final AdaptationTable childTable = pLanding.getTable(Path.parse("/root/" + pChildTable));
		final RequestResult childRowsResult = childTable.createRequestResult(null);
		try
		{
			for (Adaptation row = childRowsResult.nextAdaptation(); row != null; row = childRowsResult.nextAdaptation())
			{
				final String combo = RelationChecker.combo(row, pChildFields);
				if (combo == null)
				{
					continue;
				}
				childRows++;
				if (!distinctChild.add(combo))
				{
					continue; // already counted, orphan or not
				}
				if (distinctChild.size() > RelationChecker.MAX_SCAN_KEYS)
				{
					return new FallbackResult(false, 0L, 0L, 0L, new ArrayList<String>());
				}
				if (!parentKeys.contains(combo))
				{
					orphanCount++;
					orphans.add(combo);
					if (orphans.size() > RelationCheck.SAMPLE_SIZE)
					{
						orphans.pollLast();
					}
				}
			}
		}
		finally
		{
			childRowsResult.close();
		}

		final List<String> sample = new ArrayList<String>();
		for (final String value : orphans)
		{
			if (sample.size() >= RelationCheck.SAMPLE_SIZE)
			{
				break;
			}
			sample.add(value);
		}
		return new FallbackResult(true, childRows, distinctChild.size(), orphanCount, sample);
	}

	/** {@code '|'}-joined value of every field, or {@code null} when any of them is missing (not the same "non-null, non-empty" filter {@link RelationCheck#build}'s generated SQL applies in the WHERE clause, reproduced here by hand since this path never reaches that SQL). */
	private static String combo(final Adaptation pRow, final List<String> pFields)
	{
		final StringBuilder out = new StringBuilder();
		for (final String field : pFields)
		{
			final String value = pRow.getString(Path.parse("./" + field));
			if (value == null || value.isEmpty())
			{
				return null;
			}
			if (out.length() > 0)
			{
				out.append('|');
			}
			out.append(value);
		}
		return out.toString();
	}

	// --------------------------------------------------------------------------------------------------- write-back

	private static final class Write
	{
		final String relationId;
		final long distinctChildValues;
		final long orphanValues;
		final String cardinality;
		final String message;
		/** {@code null} when confidence must be left untouched. */
		final Integer newConfidence;

		Write(final String pRelationId, final long pDistinctChildValues, final long pOrphanValues, final String pCardinality, final String pMessage,
			final Integer pNewConfidence)
		{
			this.relationId = pRelationId;
			this.distinctChildValues = pDistinctChildValues;
			this.orphanValues = pOrphanValues;
			this.cardinality = pCardinality;
			this.message = pMessage;
			this.newConfidence = pNewConfidence;
		}
	}

	/** All relation writes of one run in a single procedure: childValues/orphanValues are xs:decimal (see the knowledge base's write-back warning on a java.lang.Long there) - BigDecimal, always. */
	private static void writeAll(final Session pSession, final Adaptation pFactory, final List<Write> pWrites) throws OperationException
	{
		final AdaptationTable relations = pFactory.getTable(FactoryPaths.LANDING_RELATION);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Write write : pWrites)
				{
					final Adaptation row = relations.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(write.relationId));
					if (row == null)
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(new Date(), FactoryPaths.LandingRelation.CHECKED_AT);
					value.setValue(BigDecimal.valueOf(write.distinctChildValues), FactoryPaths.LandingRelation.CHILD_VALUES);
					value.setValue(BigDecimal.valueOf(write.orphanValues), FactoryPaths.LandingRelation.ORPHAN_VALUES);
					if (write.cardinality != null)
					{
						value.setValue(write.cardinality, FactoryPaths.LandingRelation.CARDINALITY);
					}
					value.setValue(write.message, FactoryPaths.LandingRelation.MESSAGE);
					if (write.newConfidence != null)
					{
						value.setValue(write.newConfidence, FactoryPaths.LandingRelation.CONFIDENCE);
					}
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveRelationChecks", "Writing the relation check results failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}
}
