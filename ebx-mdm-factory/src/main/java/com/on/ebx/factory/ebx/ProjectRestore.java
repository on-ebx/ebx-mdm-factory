package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.backup.ArchiveReader;
import com.on.ebx.factory.backup.BackupFormat;
import com.on.ebx.factory.backup.BackupTables;
import com.on.ebx.factory.backup.IdRemap;
import com.on.ebx.factory.backup.RestoreAdjust;
import com.on.ebx.factory.backup.ValueCodec;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.ValueContextForUpdate;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;

/**
 * The restore of a project backup (audit F-30, wave 10), the counterpart of {@link ProjectBackup}: reads the archive as a stream and writes the
 * project's rows into an instance where that project does NOT exist (refused otherwise: nothing is merged, nothing overwritten). Administrators only.
 * <p>
 * <b>Dry run</b> ({@code pDryRun}): reads the whole archive, checks its header, its tables, every value's type and every reference, and reports what
 * would be written, per table, with the warnings; writes nothing. Run it first - it is the only check of a download that was cut short.
 * <p>
 * <b>Renumbering.</b> The auto-increment {@code id} of the archive cannot be kept (another project of this instance may use it): each row gets a new id
 * and every foreign key (and the {@code FactoryJob.stepId} text) is rewritten through {@link IdRemap}; a reference to a row written later is
 * written once the whole archive is read. Free text that happens to quote an id (a step's JSON detail) is not rewritten.
 * <p>
 * <b>Nothing comes back running</b> ({@link RestoreAdjust}): the automatic intake is OFF, distribution targets DISABLED, steps and runs that were RUNNING are
 * INTERRUPTED. The generated EBX objects are not restored: the report repeats the archive's {@code references}.
 * <p>
 * <b>Failure.</b> The write is not one transaction (a project can hold millions of rows): batches of {@link #BATCH}. A failure - or an archive that ends without
 * its {@code complete} marker - deletes everything this restore wrote (the table of the project did not exist before, so every row of the project is ours) and
 * reports it; the instance is left as it was. A restore records a {@code PROJECT_RESTORE} step, with the user, once it has succeeded.
 */
public final class ProjectRestore
{
	static final int BATCH = 500;
	private static final int MAX_MESSAGES = 30;

	/** Code of the refusal of an archive whose project style is not open (F3-13, 1.4.2). */
	public static final String STYLE_CODE = "MDMF-STYLE-3";

	private ProjectRestore()
	{
	}

	/** One decoded row waiting to be written. */
	private static final class Pending
	{
		final String oldKey;
		final Map<String, Object> values;
		final List<ProjectBackup.Column> deferredColumns = new ArrayList<ProjectBackup.Column>();
		final List<List<String>> deferredOlds = new ArrayList<List<String>>();

		Pending(final String pOldKey, final Map<String, Object> pValues)
		{
			this.oldKey = pOldKey;
			this.values = pValues;
		}
	}

	/** Everything one run keeps. */
	private static final class Run
	{
		final Session session;
		final Adaptation factory;
		final String code;
		final boolean dryRun;
		final String user;
		final IdRemap remap = new IdRemap();
		final Map<String, Long> read = new LinkedHashMap<String, Long>();
		final Map<String, Long> written = new LinkedHashMap<String, Long>();
		final List<String> warnings = new ArrayList<String>();
		final List<String> errors = new ArrayList<String>();
		final List<String> notes = new ArrayList<String>();
		/** Table -> the keys this restore created (not kept for the volume-scale tables, which a rollback deletes by project). */
		final Map<String, List<String>> created = new LinkedHashMap<String, List<String>>();
		/** Keys of the archive per table, for the dry run's reference check (the real run uses the remap). */
		final Map<String, Set<String>> seenKeys = new HashMap<String, Set<String>>();
		final Set<String> warnedFields = new HashSet<String>();
		int tableIndex = -1;
		ArrayNode references;
		ArrayNode notIncluded;
		String createdAt = "";
		String createdBy = "";
		boolean complete;
		long dangling;
		/** True once the first write was attempted: only then is there anything of this run to roll back (a refused restore must never delete the rows of a project that exists). */
		boolean writing;
		/** F2-11: set when the archive crossed a bound (bytes or rows): the coded refusal thrown after the rollback. */
		OperationException limit;
		long totalRead;
		final long maxRows = com.on.ebx.factory.backup.RestoreLimits.maxRows();

		Run(final Session pSession, final Adaptation pFactory, final String pCode, final boolean pDryRun)
		{
			this.session = pSession;
			this.factory = pFactory;
			this.code = pCode;
			this.dryRun = pDryRun;
			final String who = Actor.current(pSession).userId;
			this.user = who == null ? "" : who;
		}

		void warn(final String pMessage)
		{
			if (this.warnings.size() < ProjectRestore.MAX_MESSAGES)
			{
				this.warnings.add(pMessage);
			}
			else if (this.warnings.size() == ProjectRestore.MAX_MESSAGES)
			{
				this.warnings.add("... more warnings not listed.");
			}
		}

		void error(final String pMessage)
		{
			if (this.errors.size() < ProjectRestore.MAX_MESSAGES && !this.errors.contains(pMessage))
			{
				this.errors.add(pMessage);
			}
		}
	}

	// ======================================================================================================== entry

	/**
	 * Restores (or rehearses) the archive read from {@code pIn}. {@code pExpectedCode} is the project of the route: the archive must be of that project.
	 *
	 * @return the report; for a dry run with problems {@code ok} is false and nothing was written; a real run either succeeds or throws after rolling back
	 */
	public static ObjectNode restore(final Session pSession, final Adaptation pFactory, final String pExpectedCode, final InputStream pIn, final boolean pDryRun)
		throws OperationException
	{
		FactoryAccess.require(pSession, FactoryRight.ADMINISTRATOR, "the restore of a project");
		final Run run = new Run(pSession, pFactory, pExpectedCode, pDryRun);
		final long maxBytes = com.on.ebx.factory.backup.RestoreLimits.maxBytes();
		try (JsonParser parser = ProjectBackup.MAPPER.getFactory().createParser(new com.on.ebx.factory.backup.RestoreLimits.Bounded(pIn, maxBytes)))
		{
			parser.setCodec(ProjectBackup.MAPPER);
			ProjectRestore.read(run, parser);
			if (!run.complete)
			{
				run.error("The archive ends without its 'complete' marker: the file is cut short or was not written by the backup route.");
			}
			if (run.errors.isEmpty())
			{
				ProjectRestore.applyDeferred(run);
			}
		}
		catch (final IOException ex)
		{
			final com.on.ebx.factory.backup.RestoreLimits.BytesExceeded exceeded = com.on.ebx.factory.backup.RestoreLimits.bytesExceeded(ex);
			if (exceeded != null)
			{
				run.limit = ProjectRestore.limitRefusal("bytes", exceeded.limit);
				run.error(exceeded.getMessage());
			}
			else
			{
				run.error("The archive could not be read as JSON: " + ex.getMessage());
			}
		}
		catch (final OperationException ex)
		{
			run.error(ex.getMessage());
		}
		catch (final Exception ex)
		{
			run.error("The restore failed: " + ex);
		}
		if (!run.errors.isEmpty())
		{
			if (!pDryRun)
			{
				if (run.writing)
				{
					ProjectRestore.rollback(run);
				}
				LoggingCategory.getKernel().error("[mdm-factory] restore of project " + pExpectedCode + " by " + run.user + " failed" + (run.writing ? " and was rolled back" : "") + ": " + run.errors);
				if (run.limit != null)
				{
					throw run.limit; // F2-11: the archive went beyond a bound - the coded refusal itself, not the generic failure
				}
				throw EngineRefusal.coded("unexpected.restoreFailed", "The restore of project " + pExpectedCode + " failed and " + (run.writing ? "everything it had written was deleted" : "nothing was written")
					+ ": " + run.errors.get(0) + (run.errors.size() > 1 ? " (+" + (run.errors.size() - 1) + " more)" : ""), pExpectedCode);
			}
		}
		else if (!pDryRun)
		{
			long total = 0L;
			for (final Long n : run.written.values())
			{
				total += n.longValue();
			}
			LoggingCategory.getKernel().info("[mdm-factory] project " + pExpectedCode + " restored by " + run.user + ": " + total + " rows");
			ProjectBackup.record(pSession, pFactory, pExpectedCode, "PROJECT_RESTORE", "Restored from a backup of " + run.createdAt + (run.createdBy.isEmpty() ? "" : " (by " + run.createdBy + ")")
				+ ": " + total + " rows.");
		}
		return ProjectRestore.report(run);
	}

	private static ObjectNode report(final Run pRun)
	{
		final ObjectNode out = ProjectBackup.MAPPER.createObjectNode();
		out.put("dryRun", pRun.dryRun);
		out.put("ok", pRun.errors.isEmpty());
		out.put("project", pRun.code);
		out.put("backupCreatedAt", pRun.createdAt);
		out.put("backupCreatedBy", pRun.createdBy);
		final ObjectNode tables = out.putObject(pRun.dryRun ? "wouldWrite" : "written");
		long total = 0L;
		for (final Map.Entry<String, Long> entry : (pRun.dryRun ? pRun.read : pRun.written).entrySet())
		{
			tables.put(entry.getKey(), entry.getValue().longValue());
			total += entry.getValue().longValue();
		}
		out.put("totalRows", total);
		out.put("renumberedKeys", pRun.remap.size());
		out.put("danglingReferences", pRun.dangling);
		final ArrayNode warnings = out.putArray("warnings");
		for (final String warning : pRun.warnings)
		{
			warnings.add(warning);
		}
		final ArrayNode errors = out.putArray("errors");
		for (final String error : pRun.errors)
		{
			errors.add(error);
		}
		final ArrayNode notes = out.putArray("adjustments");
		for (final String note : pRun.notes)
		{
			notes.add(note);
		}
		out.set("references", pRun.references == null ? ProjectBackup.MAPPER.createArrayNode() : pRun.references);
		out.set("notIncluded", pRun.notIncluded == null ? ProjectBackup.MAPPER.createArrayNode() : pRun.notIncluded);
		return out;
	}

	// ======================================================================================================== reading

	/** The archive's tokens, as {@link com.on.ebx.factory.backup.ArchiveReader} reports them. */
	private static void read(final Run pRun, final JsonParser pParser) throws Exception
	{
		final Map<String, ProjectBackup.Column> byName = new HashMap<String, ProjectBackup.Column>();
		final Map<String, String> stringRefs = new HashMap<String, String>();
		final List<Pending> batch = new ArrayList<Pending>();
		final BackupTables.Spec[] current = new BackupTables.Spec[1];
		final AdaptationTable[] currentTable = new AdaptationTable[1];
		final List<List<ProjectBackup.Column>> currentColumns = new ArrayList<List<ProjectBackup.Column>>();
		ArchiveReader.read(pParser, new ArchiveReader.Handler()
		{
			@Override
			public void header(final String pFormat, final Integer pVersion, final String pProject) throws Exception
			{
				ProjectRestore.checkHeader(pRun, pFormat, pVersion, pProject);
			}

			@Override
			public void tableStart(final String pTable) throws Exception
			{
				ProjectRestore.endTable(pRun, current[0], currentTable[0], currentColumns.isEmpty() ? null : currentColumns.get(0), batch);
				final BackupTables.Spec spec = BackupTables.byName(pTable);
				if (spec == null)
				{
					throw EngineRefusal.coded("invalid.restoreTable", "The archive holds a table this build does not restore: '" + pTable + "'.", pTable);
				}
				final int index = BackupTables.all().indexOf(spec);
				if (index <= pRun.tableIndex)
				{
					throw EngineRefusal.coded("invalid.restoreOrder", "Table '" + pTable + "' is out of order or repeated in the archive.", pTable);
				}
				pRun.tableIndex = index;
				current[0] = spec;
				currentTable[0] = pRun.factory.getTable(Path.parse(spec.path()));
				currentColumns.clear();
				currentColumns.add(ProjectBackup.columns(currentTable[0]));
				byName.clear();
				for (final ProjectBackup.Column column : currentColumns.get(0))
				{
					byName.put(column.name, column);
				}
				stringRefs.clear();
				for (final Map.Entry<String, String> entry : BackupTables.stringReferences().entrySet())
				{
					if (entry.getKey().startsWith(pTable + "."))
					{
						stringRefs.put(entry.getKey().substring(pTable.length() + 1), entry.getValue());
					}
				}
				pRun.read.put(pTable, Long.valueOf(0L));
				pRun.written.put(pTable, Long.valueOf(0L));
			}

			@Override
			public void row(final String pTable, final JsonNode pRow) throws Exception
			{
				pRun.read.put(pTable, Long.valueOf(pRun.read.get(pTable).longValue() + 1L));
				pRun.totalRead++;
				if (pRun.totalRead > pRun.maxRows)
				{
					pRun.limit = ProjectRestore.limitRefusal("rows", pRun.maxRows);
					throw pRun.limit; // F2-11: refused as soon as the bound is crossed, the dry run included
				}
				final Pending pending = ProjectRestore.decode(pRun, current[0], pRow, byName, stringRefs);
				if (!pRun.dryRun && !pRun.errors.isEmpty())
				{
					throw EngineRefusal.coded("invalid.restoreRow", pRun.errors.get(0)); // a real run stops at the first unusable row; the dry run lists them
				}
				if (pending == null)
				{
					return;
				}
				if (pRun.dryRun)
				{
					ProjectRestore.dryRecord(pRun, current[0], pending);
					return;
				}
				batch.add(pending);
				if (batch.size() >= ProjectRestore.BATCH)
				{
					ProjectRestore.flush(pRun, current[0], currentTable[0], currentColumns.get(0), batch);
					if (!pRun.errors.isEmpty())
					{
						throw EngineRefusal.coded("invalid.restoreRow", pRun.errors.get(0));
					}
				}
			}

			@Override
			public void field(final String pName, final JsonNode pValue) throws Exception
			{
				if ("createdAt".equals(pName))
				{
					pRun.createdAt = pValue.asText();
				}
				else if ("createdBy".equals(pName))
				{
					pRun.createdBy = pValue.asText();
				}
				else if ("references".equals(pName) && pValue.isArray())
				{
					pRun.references = (ArrayNode) pValue;
				}
				else if ("notIncluded".equals(pName) && pValue.isArray())
				{
					pRun.notIncluded = (ArrayNode) pValue;
				}
				else if ("complete".equals(pName))
				{
					pRun.complete = pValue.asBoolean(false);
				}
			}
		});
		ProjectRestore.endTable(pRun, current[0], currentTable[0], currentColumns.isEmpty() ? null : currentColumns.get(0), batch);
	}

	/** The last rows of a table are written when the next table starts (and at the end of the archive). */
	private static void endTable(final Run pRun, final BackupTables.Spec pSpec, final AdaptationTable pTable, final List<ProjectBackup.Column> pColumns, final List<Pending> pBatch)
		throws OperationException
	{
		if (pSpec == null || pRun.dryRun || pBatch.isEmpty())
		{
			return;
		}
		ProjectRestore.flush(pRun, pSpec, pTable, pColumns, pBatch);
		if (!pRun.errors.isEmpty())
		{
			throw EngineRefusal.coded("invalid.restoreRow", pRun.errors.get(0));
		}
	}

	private static void checkHeader(final Run pRun, final String pFormat, final Integer pVersion, final String pProject) throws OperationException
	{
		final String refusal = BackupFormat.refuse(pFormat, pVersion);
		if (refusal != null)
		{
			throw EngineRefusal.coded("invalid.backupFormat", refusal);
		}
		if (!BackupFormat.validProjectCode(pProject))
		{
			throw EngineRefusal.coded("invalid.backupNoProject", "The backup names no valid project code.");
		}
		if (!pProject.equals(pRun.code))
		{
			throw EngineRefusal.coded("invalid.backupOtherProject", "The backup is of project '" + pProject + "', not of '" + pRun.code + "': a project is restored under its own code.", pProject, pRun.code);
		}
		final AdaptationTable projects = pRun.factory.getTable(FactoryPaths.PROJECT);
		if (projects.lookupAdaptationByPrimaryKey(projects.computePrimaryKey(new Object[] { pProject })) != null)
		{
			throw EngineRefusal.coded("conflict.projectExists", "Project '" + pProject + "' already exists on this instance: a restore never merges into or overwrites a project. Delete it first (project deletion), "
				+ "or restore on another instance.", pProject);
		}
	}

	/** F2-11: the coded refusal of an archive beyond a bound; {@code pWhat} is "bytes" or "rows". */
	static OperationException limitRefusal(final String pWhat, final long pLimit)
	{
		return EngineRefusal.coded("invalid.restoreTooLarge", com.on.ebx.factory.backup.RestoreLimits.sentence(pWhat, pLimit),
			"bytes".equals(pWhat) ? com.on.ebx.factory.backup.RestoreLimits.MAX_BYTES_PROPERTY : com.on.ebx.factory.backup.RestoreLimits.MAX_ROWS_PROPERTY, Long.toString(pLimit));
	}

	/** The coded refusal of an archive whose project style is not open (null when it is open or absent: absent lands on the default, Consolidation). */
	static String styleRefusal(final String pStyle)
	{
		if (pStyle == null || pStyle.isEmpty() || com.on.ebx.factory.wizard.WizardRules.styleSelectable(pStyle))
		{
			return null;
		}
		return ProjectRestore.STYLE_CODE + ": the archive is of a project in the style " + pStyle + ", which is not open yet (coming soon): it cannot be restored. "
			+ "The project is not created; keep the archive until the style opens.";
	}

	/** Decodes one archive row into the values to write; null (with a recorded error) when the row cannot be used. */
	private static Pending decode(final Run pRun, final BackupTables.Spec pSpec, final JsonNode pRow, final Map<String, ProjectBackup.Column> pColumns,
		final Map<String, String> pStringRefs)
	{
		final String oldKey = pRow.path("key").asText("");
		final JsonNode valuesNode = pRow.path("values");
		if (oldKey.isEmpty() || !valuesNode.isObject())
		{
			pRun.error("A row of " + pSpec.name + " has no key or no values.");
			return null;
		}
		if ("Project".equals(pSpec.name))
		{
			// 1.4.2 (F3-13): a restore creates the project with privileges disabled, so the access rule on Project/style does not see it: the archive's style must be open here
			final String refusal = ProjectRestore.styleRefusal(valuesNode.path("style").asText(""));
			if (refusal != null)
			{
				pRun.error(refusal);
				return null;
			}
		}
		final Map<String, Object> values = new LinkedHashMap<String, Object>();
		final Pending pending = new Pending(oldKey, values);
		final java.util.Iterator<Map.Entry<String, JsonNode>> fields = valuesNode.fields();
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			final ProjectBackup.Column column = pColumns.get(field.getKey());
			if (column == null)
			{
				if (!BackupTables.neverExported(field.getKey()) && pRun.warnedFields.add(pSpec.name + "." + field.getKey()))
				{
					pRun.warn("Field " + pSpec.name + "." + field.getKey() + " of the archive does not exist in this build's model: ignored.");
				}
				continue;
			}
			if (column.autoIncrement)
			{
				continue; // renumbered
			}
			try
			{
				values.put(column.name, column.multi ? ValueCodec.listFromJson(field.getValue(), column.javaClass) : ValueCodec.fromJson(field.getValue(), column.javaClass));
			}
			catch (final IllegalArgumentException ex)
			{
				pRun.error(pSpec.name + " " + oldKey + ", field " + column.name + ": " + ex.getMessage());
				return null;
			}
		}
		if (pSpec.scope == BackupTables.Scope.SELF)
		{
			values.put("code", pRun.code);
		}
		RestoreAdjust.apply(pSpec.name, values, pRun.notes);
		// a reject's primary key holds its entity: without it the row cannot be written, so it is left out with a word
		final boolean needsEntity = "TransferReject".equals(pSpec.name);
		// references: foreign keys and the id texts renumbered with the table they name
		for (final ProjectBackup.Column column : pColumns.values())
		{
			if (column.fkTable != null && values.containsKey(column.name))
			{
				ProjectRestore.resolve(pRun, pSpec, pending, column, column.fkTable);
			}
		}
		if (needsEntity && !values.containsKey("entity"))
		{
			pRun.warn("A TransferReject row (" + oldKey + ") points to an entity that is not in the archive: left out.");
			return null;
		}
		for (final Map.Entry<String, String> ref : pStringRefs.entrySet())
		{
			final Object old = values.get(ref.getKey());
			if (old != null)
			{
				final String renumbered = pRun.dryRun ? String.valueOf(old) : pRun.remap.get(ref.getValue(), String.valueOf(old));
				if (renumbered == null)
				{
					values.remove(ref.getKey());
				}
				else
				{
					values.put(ref.getKey(), renumbered);
				}
			}
		}
		return pending;
	}

	/** Rewrites one foreign key of a row through the remap, defers it when its target is not written yet, drops it (warning) when the target is not in the archive. */
	private static void resolve(final Run pRun, final BackupTables.Spec pSpec, final Pending pPending, final ProjectBackup.Column pColumn, final String pTargetTable)
	{
		final Object raw = pPending.values.get(pColumn.name);
		final List<String> olds = new ArrayList<String>();
		if (raw instanceof List)
		{
			for (final Object item : (List<?>) raw)
			{
				if (item != null)
				{
					olds.add(String.valueOf(item));
				}
			}
		}
		else if (raw != null)
		{
			olds.add(String.valueOf(raw));
		}
		final BackupTables.Spec targetSpec = BackupTables.byName(pTargetTable);
		if (targetSpec == null)
		{
			pRun.warn(pSpec.name + "." + pColumn.name + " points to " + pTargetTable + ", which a backup does not hold: written as it is.");
			return;
		}
		final boolean targetEarlier = BackupTables.all().indexOf(targetSpec) < BackupTables.all().indexOf(pSpec);
		final List<String> resolved = new ArrayList<String>();
		boolean defer = false;
		for (final String old : olds)
		{
			final String now = ProjectRestore.lookup(pRun, pTargetTable, old);
			if (now != null)
			{
				resolved.add(now);
			}
			else if (targetEarlier && !"Project".equals(pTargetTable) && IdRemap.tracked(pTargetTable))
			{
				pRun.dangling++;
				pRun.warn(pSpec.name + " " + pPending.oldKey + ": " + pColumn.name + " points to " + pTargetTable + " " + old + ", which is not in the archive: left empty.");
			}
			else
			{
				defer = true;
			}
		}
		if (defer)
		{
			pPending.values.remove(pColumn.name);
			pPending.deferredColumns.add(pColumn);
			pPending.deferredOlds.add(olds);
			return;
		}
		if (pColumn.multi)
		{
			pPending.values.put(pColumn.name, new ArrayList<Object>(resolved));
		}
		else if (resolved.isEmpty())
		{
			pPending.values.remove(pColumn.name);
		}
		else
		{
			pPending.values.put(pColumn.name, resolved.get(0));
		}
	}

	private static String lookup(final Run pRun, final String pTable, final String pOldKey)
	{
		if ("Project".equals(pTable))
		{
			return pRun.code; // the project's key is its code, the same before and after
		}
		if (pRun.dryRun)
		{
			final Set<String> seen = pRun.seenKeys.get(pTable);
			return seen != null && seen.contains(pOldKey) ? pOldKey : null;
		}
		return pRun.remap.get(pTable, pOldKey);
	}

	// ============================================================================================================ dry run

	private static void dryRecord(final Run pRun, final BackupTables.Spec pSpec, final Pending pPending)
	{
		if (IdRemap.tracked(pSpec.name))
		{
			Set<String> seen = pRun.seenKeys.get(pSpec.name);
			if (seen == null)
			{
				seen = new HashSet<String>();
				pRun.seenKeys.put(pSpec.name, seen);
			}
			seen.add(pPending.oldKey);
		}
		ProjectRestore.countDeferred(pRun, pSpec, pPending);
	}

	private static void countDeferred(final Run pRun, final BackupTables.Spec pSpec, final Pending pPending)
	{
		for (int i = 0; i < pPending.deferredColumns.size(); i++)
		{
			pRun.remap.defer(pSpec.name, pPending.oldKey, pPending.deferredColumns.get(i).name, pPending.deferredColumns.get(i).fkTable, pPending.deferredOlds.get(i),
				pPending.deferredColumns.get(i).multi);
		}
	}

	// ============================================================================================================== writing

	private static void flush(final Run pRun, final BackupTables.Spec pSpec, final AdaptationTable pTable, final List<ProjectBackup.Column> pColumns, final List<Pending> pBatch)
	{
		final List<String> newKeys = new ArrayList<String>();
		pRun.writing = true;
		final ProcedureResult result = ProgrammaticService.createForSession(pRun.session, pRun.factory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true); // the right was checked on entry; the rows are the factory's own
				pContext.setExecutionInformation("mdm-factory:project-restore:" + pRun.code + ":" + pRun.user);
				for (final Pending pending : pBatch)
				{
					final ValueContextForUpdate context = pContext.getContextForNewOccurrence(pTable);
					for (final Map.Entry<String, Object> value : pending.values.entrySet())
					{
						context.setValue(value.getValue(), Path.parse("./" + value.getKey()));
					}
					final Adaptation created = pContext.doCreateOccurrence(context, pTable);
					newKeys.add(created.getOccurrencePrimaryKey().format());
				}
			}
		});
		if (result.hasFailed())
		{
			pRun.error("Writing " + pSpec.name + " failed after " + pRun.written.get(pSpec.name) + " rows: " + result.getExceptionFullMessage(Locale.ENGLISH));
			pBatch.clear();
			return;
		}
		List<String> createdKeys = null;
		if (IdRemap.tracked(pSpec.name))
		{
			createdKeys = pRun.created.get(pSpec.name);
			if (createdKeys == null)
			{
				createdKeys = new ArrayList<String>();
				pRun.created.put(pSpec.name, createdKeys);
			}
		}
		for (int i = 0; i < pBatch.size(); i++)
		{
			final Pending pending = pBatch.get(i);
			pRun.remap.put(pSpec.name, pending.oldKey, newKeys.get(i));
			if (createdKeys != null)
			{
				createdKeys.add(newKeys.get(i));
			}
			for (int d = 0; d < pending.deferredColumns.size(); d++)
			{
				pRun.remap.defer(pSpec.name, newKeys.get(i), pending.deferredColumns.get(d).name, pending.deferredColumns.get(d).fkTable, pending.deferredOlds.get(d),
					pending.deferredColumns.get(d).multi);
			}
		}
		pRun.written.put(pSpec.name, Long.valueOf(pRun.written.get(pSpec.name).longValue() + pBatch.size()));
		pBatch.clear();
	}

	/** The references that pointed to a row written later: written now that every row of the archive is. */
	private static void applyDeferred(final Run pRun) throws OperationException
	{
		final Map<String, List<IdRemap.Deferred>> byRow = new LinkedHashMap<String, List<IdRemap.Deferred>>();
		for (final IdRemap.Deferred deferred : pRun.remap.deferred())
		{
			final String id = deferred.table + "\u0001" + deferred.newKey;
			List<IdRemap.Deferred> list = byRow.get(id);
			if (list == null)
			{
				list = new ArrayList<IdRemap.Deferred>();
				byRow.put(id, list);
			}
			list.add(deferred);
		}
		final List<Map.Entry<String, List<IdRemap.Deferred>>> rows = new ArrayList<Map.Entry<String, List<IdRemap.Deferred>>>(byRow.entrySet());
		for (int from = 0; from < rows.size(); from += ProjectRestore.BATCH)
		{
			final List<Map.Entry<String, List<IdRemap.Deferred>>> batch = rows.subList(from, Math.min(rows.size(), from + ProjectRestore.BATCH));
			final Map<String, Map<String, Object>> toWrite = new LinkedHashMap<String, Map<String, Object>>();
			for (final Map.Entry<String, List<IdRemap.Deferred>> row : batch)
			{
				final Map<String, Object> fields = new LinkedHashMap<String, Object>();
				for (final IdRemap.Deferred deferred : row.getValue())
				{
					final List<Object> resolved = new ArrayList<Object>();
					for (final String old : deferred.oldTargets)
					{
						final String now = ProjectRestore.lookup(pRun, deferred.targetTable, old);
						if (now == null)
						{
							pRun.dangling++;
							pRun.warn(deferred.table + " " + deferred.newKey + ": " + deferred.field + " points to " + deferred.targetTable + " " + old + ", which is not in the archive: left empty.");
						}
						else
						{
							resolved.add(now);
						}
					}
					if (deferred.multi)
					{
						fields.put(deferred.field, resolved);
					}
					else if (!resolved.isEmpty())
					{
						fields.put(deferred.field, resolved.get(0));
					}
				}
				toWrite.put(row.getKey(), fields);
			}
			if (pRun.dryRun)
			{
				continue;
			}
			final ProcedureResult result = ProgrammaticService.createForSession(pRun.session, pRun.factory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					pContext.setExecutionInformation("mdm-factory:project-restore:" + pRun.code + ":" + pRun.user);
					for (final Map.Entry<String, Map<String, Object>> row : toWrite.entrySet())
					{
						final String[] parts = row.getKey().split("\u0001", 2);
						final AdaptationTable table = pRun.factory.getTable(Path.parse("/root/" + parts[0]));
						final Adaptation existing = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(parts[1]));
						if (existing == null || row.getValue().isEmpty())
						{
							continue;
						}
						final ValueContextForUpdate context = pContext.getContext(existing.getAdaptationName());
						for (final Map.Entry<String, Object> value : row.getValue().entrySet())
						{
							context.setValue(value.getValue(), Path.parse("./" + value.getKey()));
						}
						pContext.doModifyContent(existing, context);
					}
				}
			});
			if (result.hasFailed())
			{
				throw EngineRefusal.coded("unexpected.restoreReferences", "Writing the references between rows failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
			}
		}
	}

	// ============================================================================================================ rollback

	/**
	 * Deletes what this run wrote, children before parents: the volume-scale and project-keyed tables by the project (the project did not exist, so every row
	 * of it is this run's), the others by the keys recorded; then the project row. Best effort: a failure is logged with what remains.
	 */
	private static void rollback(final Run pRun)
	{
		final List<BackupTables.Spec> order = new ArrayList<BackupTables.Spec>(BackupTables.all());
		java.util.Collections.reverse(order);
		for (final BackupTables.Spec spec : order)
		{
			try
			{
				final AdaptationTable table = pRun.factory.getTable(Path.parse(spec.path()));
				if (!IdRemap.tracked(spec.name))
				{
					VolumeBatchOps.forEachBatched(pRun.session, pRun.factory.getHome(), table, "./project=" + CatalogReader.quote(pRun.code), "Rolling back the restore of " + pRun.code,
						new VolumeBatchOps.RowOp()
						{
							@Override
							public void apply(final ProcedureContext pContext, final Adaptation pRow) throws Exception
							{
								pContext.setAllPrivileges(true);
								pContext.doDelete(pRow.getAdaptationName(), false);
							}
						});
				}
				else
				{
					final List<String> keys = pRun.created.get(spec.name);
					if (keys != null && !keys.isEmpty())
					{
						ProjectRestore.deleteKeys(pRun, table, keys);
					}
				}
			}
			catch (final OperationException | RuntimeException ex)
			{
				LoggingCategory.getKernel().error("[mdm-factory] rollback of the restore of project " + pRun.code + ": table " + spec.name + " could not be emptied: " + ex.getMessage());
			}
		}
	}

	private static void deleteKeys(final Run pRun, final AdaptationTable pTable, final List<String> pKeys) throws OperationException
	{
		for (int from = 0; from < pKeys.size(); from += ProjectRestore.BATCH)
		{
			final List<String> batch = pKeys.subList(from, Math.min(pKeys.size(), from + ProjectRestore.BATCH));
			final ProcedureResult result = ProgrammaticService.createForSession(pRun.session, pRun.factory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					for (final String key : batch)
					{
						final Adaptation row = pTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(key));
						if (row != null)
						{
							pContext.doDelete(row.getAdaptationName(), false);
						}
					}
				}
			});
			if (result.hasFailed())
			{
				throw EngineRefusal.coded("unexpected.saveFailed", result.getExceptionFullMessage(Locale.ENGLISH));
			}
		}
	}
}
