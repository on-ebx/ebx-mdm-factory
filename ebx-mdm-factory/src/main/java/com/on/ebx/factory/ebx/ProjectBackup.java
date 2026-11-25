package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.backup.BackupFormat;
import com.on.ebx.factory.backup.BackupTables;
import com.on.ebx.factory.backup.ReferenceCollector;
import com.on.ebx.factory.backup.ValueCodec;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.core.ModuleVersion;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.staging.StagingNames;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.schema.info.SchemaFacetTableRef;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * The backup of ONE project (audit F-30, wave 10): every row the Factory holds for it in the tables of {@link BackupTables}, as one JSON document
 * ({@link BackupFormat}) written as a stream - a project with millions of {@code Xref} rows is never held in memory, only the keys of the parent
 * tables that scope their children. The fields of each table come from the SCHEMA (the stored, non-computed, non-association fields), so a field added
 * to the XSD later is in the next backup without a code change; {@link #columns} is shared with {@link ProjectRestore}.
 * <p>
 * Administrators only (the route and this method both ask). What it does not hold - the generated EBX objects - is listed under {@code references}
 * (taken from the rows) and {@code notIncluded} in the archive itself. A successful export is recorded as a {@code PROJECT_BACKUP} step of the project
 * (who, when, how many rows); the step is written after the archive, so it is not in the archive.
 */
public final class ProjectBackup
{
	static final ObjectMapper MAPPER = new ObjectMapper();

	private ProjectBackup()
	{
	}

	/** One stored field of a table. */
	static final class Column
	{
		final String name;
		final Path path;
		final SchemaNode node;
		final boolean multi;
		final boolean autoIncrement;
		final Class<?> javaClass;
		/** The table a foreign key points to (name only), null when the field is no foreign key. */
		final String fkTable;

		Column(final String pName, final Path pPath, final SchemaNode pNode, final boolean pMulti, final boolean pAutoIncrement, final Class<?> pJavaClass, final String pFkTable)
		{
			this.name = pName;
			this.path = pPath;
			this.node = pNode;
			this.multi = pMulti;
			this.autoIncrement = pAutoIncrement;
			this.javaClass = pJavaClass;
			this.fkTable = pFkTable;
		}
	}

	/** The stored fields of a table, from its schema; never a field {@link BackupTables#neverExported}. */
	static List<Column> columns(final AdaptationTable pTable)
	{
		final List<Column> out = new ArrayList<Column>();
		for (final SchemaNode node : pTable.getTableOccurrenceRootNode().getNodeChildren())
		{
			if (node.isComplex() || !node.isTerminalValue() || node.isTerminalValueComputed() || node.isAssociationNode() || node.isSelectNode() || node.isLinkedNode())
			{
				continue;
			}
			final String name = node.getPathInSchema().getLastStep().format();
			if (BackupTables.neverExported(name))
			{
				continue;
			}
			String fk = null;
			final SchemaFacetTableRef ref = node.getFacetOnTableReference();
			if (ref != null && ref.getTablePath() != null)
			{
				fk = ref.getTablePath().getLastStep().format();
			}
			final Class<?> javaClass = node.getOccurrencesJavaClass() == null ? String.class : node.getOccurrencesJavaClass();
			out.add(new Column(name, Path.parse("./" + name), node, node.getMaxOccurs() != 1, node.isAutoIncrement(), javaClass, fk));
		}
		return out;
	}

	/** The result of an export: how many rows of each table, in the archive's order. */
	public static final class Result
	{
		public final Map<String, Long> rows = new LinkedHashMap<String, Long>();
		public long total;
	}

	/**
	 * Writes the archive of {@code pProjectCode} into {@code pOut} (not closed). {@code pExclude}: names of tables to leave out (never {@code Project}); they are
	 * named in the archive under {@code excluded}.
	 */
	public static Result export(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Set<String> pExclude, final OutputStream pOut)
		throws OperationException, IOException
	{
		FactoryAccess.require(pSession, FactoryRight.ADMINISTRATOR, "the backup of a project");
		final AdaptationTable projects = pFactory.getTable(FactoryPaths.PROJECT);
		final Adaptation project = projects.lookupAdaptationByPrimaryKey(projects.computePrimaryKey(new Object[] { pProjectCode }));
		if (project == null)
		{
			throw EngineRefusal.of("engine.projectNotFound", "No project '" + pProjectCode + "' to back up.", pProjectCode);
		}
		final String user = Actor.current(pSession).userId;
		final Set<String> exclude = new HashSet<String>(pExclude == null ? new HashSet<String>() : pExclude);
		exclude.remove("Project");
		final boolean agentsOpen = FeatureSwitches.agents();
		final List<String> omitted = new java.util.ArrayList<String>();
		final Result result = new Result();
		final ReferenceCollector references = new ReferenceCollector();
		final JsonGenerator g = ProjectBackup.MAPPER.getFactory().createGenerator(pOut, JsonEncoding.UTF8);
		g.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
		try
		{
			g.writeStartObject();
			g.writeStringField("format", BackupFormat.FORMAT);
			g.writeNumberField("formatVersion", BackupFormat.VERSION);
			g.writeStringField("project", pProjectCode);
			g.writeStringField("createdAt", ValueCodec.toJson(new Date()).toString());
			g.writeStringField("createdBy", user == null ? "" : user);
			g.writeObjectFieldStart("module");
			g.writeStringField("version", ModuleVersion.version());
			g.writeStringField("commit", ModuleVersion.commit());
			g.writeEndObject();
			g.writeArrayFieldStart("excluded");
			for (final String name : exclude)
			{
				g.writeString(name);
			}
			g.writeEndArray();
			g.writeArrayFieldStart("tables");
			final Map<String, Set<String>> keys = new LinkedHashMap<String, Set<String>>();
			for (final BackupTables.Spec spec : BackupTables.all())
			{
				if (exclude.contains(spec.name))
				{
					continue;
				}
				final AdaptationTable table = pFactory.getTable(Path.parse(spec.path()));
				// 1.4.2 (F3-06): master values of the agent tables stay out of the file while the governed agents are closed
				final List<Column> columns = ProjectBackup.withoutOmitted(ProjectBackup.columns(table), spec.name, agentsOpen, omitted);
				final Set<String> mine = spec.keepKeys ? new HashSet<String>() : null;
				if (mine != null)
				{
					keys.put(spec.name, mine);
				}
				g.writeStartObject();
				g.writeStringField("table", spec.name);
				g.writeStringField("path", spec.path());
				g.writeArrayFieldStart("rows");
				long count = 0L;
				if (spec.scope == BackupTables.Scope.SELF)
				{
					ProjectBackup.writeRow(g, project, columns, spec, mine, references);
					count = 1L;
				}
				else
				{
					final Set<String> parentKeys = spec.scope == BackupTables.Scope.PARENT ? keys.get(spec.parent) : null;
					if (spec.scope == BackupTables.Scope.PROJECT || parentKeys != null && !parentKeys.isEmpty())
					{
						String predicate = null;
						if (spec.scope == BackupTables.Scope.PROJECT)
						{
							predicate = "./" + spec.scopeField + "=" + CatalogReader.quote(pProjectCode);
							if (!spec.extraPredicate.isEmpty())
							{
								predicate += " and " + spec.extraPredicate;
							}
						}
						final RequestResult rows = table.createRequestResult(predicate);
						try
						{
							for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
							{
								if (parentKeys != null && !parentKeys.contains(row.getString(Path.parse("./" + spec.scopeField))))
								{
									continue;
								}
								ProjectBackup.writeRow(g, row, columns, spec, mine, references);
								count++;
							}
						}
						finally
						{
							rows.close();
						}
					}
				}
				g.writeEndArray();
				g.writeEndObject();
				result.rows.put(spec.name, Long.valueOf(count));
				result.total += count;
			}
			g.writeEndArray();
			g.writeObjectFieldStart("rowCounts");
			for (final Map.Entry<String, Long> entry : result.rows.entrySet())
			{
				g.writeNumberField(entry.getKey(), entry.getValue().longValue());
			}
			g.writeEndObject();
			references.offerModels(StagingNames.modelName(pProjectCode), MasterNames.modelName(pProjectCode));
			g.writeArrayFieldStart("references");
			for (final ReferenceCollector.Reference reference : references.references())
			{
				g.writeStartObject();
				g.writeStringField("kind", reference.kind);
				g.writeStringField("name", reference.name);
				g.writeStringField("detail", reference.detail);
				g.writeEndObject();
			}
			g.writeEndArray();
			// 1.4.2 (F3-06): which master-value fields were left out because the governed agents are closed (empty when they are open)
			g.writeArrayFieldStart("valuesOmitted");
			for (final String field : omitted)
			{
				g.writeString(field);
			}
			g.writeEndArray();
			g.writeArrayFieldStart("notIncluded");
			for (final String line : BackupTables.NOT_INCLUDED)
			{
				g.writeString(line);
			}
			g.writeEndArray();
			g.writeBooleanField("complete", true); // a restore refuses an archive without it: a download cut short is not a backup
			g.writeEndObject();
			g.flush();
		}
		finally
		{
			g.close();
		}
		LoggingCategory.getKernel().info("[mdm-factory] backup of project " + pProjectCode + " by " + user + ": " + result.total + " rows in " + result.rows.size() + " tables");
		ProjectBackup.record(pSession, pFactory, pProjectCode, "PROJECT_BACKUP", "Backup written: " + result.total + " rows in " + result.rows.size() + " tables"
			+ (exclude.isEmpty() ? "" : " (left out: " + exclude + ")") + ".");
		return result;
	}

	/** The columns of a table without the master-value fields {@link BackupTables#valueOmitted} leaves out; the omitted ones are named in {@code pOmitted}. */
	static List<Column> withoutOmitted(final List<Column> pColumns, final String pTable, final boolean pAgentsOpen, final List<String> pOmitted)
	{
		final List<Column> out = new java.util.ArrayList<Column>();
		for (final Column column : pColumns)
		{
			if (BackupTables.valueOmitted(pTable, column.name, pAgentsOpen))
			{
				pOmitted.add(pTable + "." + column.name);
			}
			else
			{
				out.add(column);
			}
		}
		return out;
	}

	private static void writeRow(final JsonGenerator pG, final Adaptation pRow, final List<Column> pColumns, final BackupTables.Spec pSpec, final Set<String> pKeys,
		final ReferenceCollector pReferences) throws IOException
	{
		final String key = pRow.getOccurrencePrimaryKey().format();
		if (pKeys != null)
		{
			pKeys.add(key);
		}
		final Map<String, Object> plain = new LinkedHashMap<String, Object>();
		pG.writeStartObject();
		pG.writeStringField("key", key);
		pG.writeObjectFieldStart("values");
		for (final Column column : pColumns)
		{
			final Object value = pRow.get(column.node);
			if (value == null || value instanceof List && ((List<?>) value).isEmpty())
			{
				continue;
			}
			plain.put(column.name, value);
			pG.writeFieldName(column.name);
			if (column.multi)
			{
				pG.writeStartArray();
				for (final Object item : (List<?>) value)
				{
					ProjectBackup.writeValue(pG, item);
				}
				pG.writeEndArray();
			}
			else
			{
				ProjectBackup.writeValue(pG, value);
			}
		}
		pG.writeEndObject();
		pG.writeEndObject();
		pReferences.offer(pSpec.name, plain);
	}

	private static void writeValue(final JsonGenerator pG, final Object pValue) throws IOException
	{
		final Object json = ValueCodec.toJson(pValue);
		if (json instanceof Boolean)
		{
			pG.writeBoolean(((Boolean) json).booleanValue());
		}
		else if (json == null)
		{
			pG.writeNull();
		}
		else
		{
			pG.writeString(String.valueOf(json));
		}
	}

	/** Records a backup or restore of the project as a step (who and when come from the step itself); a failure to record is logged, never thrown. */
	static void record(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pStep, final String pMessage)
	{
		try
		{
			final String id = PipelineSteps.begin(pSession, pFactory, pProjectCode, pStep, "");
			PipelineSteps.end(pSession, pFactory, id, true, pMessage, null);
		}
		catch (final OperationException | RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] could not record the " + pStep + " step of project " + pProjectCode + ": " + ex.getMessage());
		}
	}
}
