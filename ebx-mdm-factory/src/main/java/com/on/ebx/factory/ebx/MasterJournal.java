package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.master.JournalConsumption;
import com.on.ebx.factory.master.MasterJournalTriggerBody;
import com.on.ebx.factory.master.MasterLoadScope;
import com.on.ebx.factory.staging.StagingLoadListener;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.StagingNames;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationReference;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.SchemaLocation;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;
import com.onwbp.base.text.UserMessage;

/**
 * Lot M-INC (2026-10-12, docs/VOLUME.md "Lot M-INC - incremental master load"): the EBX side of the journal of changed staging rows. A master load used to re-read the
 * whole staging zone even when one row changed (Vol1mF, 2026-10-04: 29-42 s for 1,465,857 staging rows with nothing to write, linear). The staging tables now carry two
 * generated script triggers, {@code MasterJournalAfterCreate} / {@code MasterJournalAfterModify} ({@link MasterJournalTriggerBody}), that write one row per created or
 * modified record into the dataset {@link FactoryPaths#MASTER_JOURNAL_DATASET} of the project's STAGING dataspace - EBX itself writes it, so every write path is covered:
 * the staging loader, the derivation triggers and their {@code _recompute_} backfill, a steward in the UI, a Data Services write. A master load of scope {@code changed}
 * ({@link MasterLoadScope}) reads those rows instead of the table.
 * <p>
 * <b>Where the journal lives, and why not elsewhere.</b> A table trigger cannot open {@code execute transaction}; it writes through its own ambient {@code
 * _ebx.transaction}, scoped to its OWN dataspace (measured in the distribution lot, PipelineStep 395; the same constraint produced {@code IntakeSignal} and {@code
 * DistributionOutbox}). So the {@code MdmFactory} dataset cannot be the journal. A technical table inside the generated staging model was the other option; it was not taken
 * because (1) the one write mechanism proven live for a generated trigger is "{@code db.findDataset(_ebx.dataspace, name)} + {@code db.findTable<m.T>} + {@code
 * db.recordOf(_ebx.transaction, ...)}" against a PACKAGED model referenced with {@code references model ... from module}, whereas a table of the trigger's own model has no
 * measured syntax; (2) the staging model is generated, diffed and patched by {@code StagingGenerator}/{@code StagingModelDiff} on every regeneration, and an extra table
 * would have to be added to the generator, to the diff and to every existing project by a patch; (3) a packaged model costs nothing at generation time and is
 * instantiated exactly like the intake signal. The price: a new packaged XSD ({@code MasterJournal.xsd}), i.e. the module deployment (a Tomcat restart, which any new
 * Java class needs anyway); {@code MdmFactory.xsd} is NOT changed.
 * <p>
 * <b>Recording and arming.</b> One {@code Control} row per entity. A row EXISTS = the triggers journal that entity's changes (the trigger returns at once without one);
 * {@code armed} = a full load of the entity finished since, so the next load may read only the journal. A full load of an entity creates/updates its row with {@code
 * armed = false} BEFORE reading anything (rows written from then on are journaled) and arms it only when the whole load ended normally; any other end leaves it disarmed,
 * and the next load is full. The journal triggers are installed by the FIRST master load of a project (any scope) - that load is full by construction ({@link
 * MasterLoadScope.Facts#installedNow}) - and verified at each later load with one {@code list_script_table_triggers} call; a missing trigger (a regenerated staging model
 * loses them) installs again and makes that load full.
 * <p>
 * <b>Consumption.</b> A changed load reads the entity's journal rows (and the staging keys of its open MASTER rejects), loads exactly those staging rows, and at the very end
 * of the load, if it ended normally, deletes the rows it read - each only if its {@code changes} counter still has the value read, so a record written again while the
 * load ran stays journaled. A full load snapshot-reads the journal before its own scan and consumes that snapshot the same way (everything visible at the snapshot was
 * committed before the scan, so the scan saw it).
 * <p>
 * <b>Never fails a load.</b> Every problem here (MCP unreachable, the model not packaged yet, an install refused, a write that fails) is a reason to load in full, said in the
 * step detail; none throws out of {@link #prepare}. <b>Not run live yet</b>: nothing in this lot ran against an instance.
 */
final class MasterJournal
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int DELETE_BATCH = 1000;

	private MasterJournal()
	{
	}

	// =============================================================================================================== prepared

	/** What {@link #prepare} found or did, for one master load of one project. */
	static final class Prepared
	{
		final boolean usable;
		/** Why the journal is not usable (null when it is). */
		final String reason;
		/** True when this call installed the dataset or the triggers: nothing was recorded before it. */
		final boolean installedNow;
		final Adaptation dataset;
		final AdaptationHome home;
		final String projectCode;
		/** The step-detail node ({@code journal}). */
		final ObjectNode detail;

		Prepared(final boolean pUsable, final String pReason, final boolean pInstalledNow, final Adaptation pDataset, final AdaptationHome pHome, final String pProjectCode,
			final ObjectNode pDetail)
		{
			this.usable = pUsable;
			this.reason = pReason;
			this.installedNow = pInstalledNow;
			this.dataset = pDataset;
			this.home = pHome;
			this.projectCode = pProjectCode;
			this.detail = pDetail;
		}

		static Prepared unusable(final String pReason, final String pProjectCode, final ObjectNode pDetail)
		{
			pDetail.put("usable", false);
			pDetail.put("reason", pReason);
			return new Prepared(false, pReason, false, null, null, pProjectCode, pDetail);
		}
	}

	/** The journal's state for one entity. */
	static final class Control
	{
		final boolean exists;
		final boolean armed;
		final String fingerprint;

		Control(final boolean pExists, final boolean pArmed, final String pFingerprint)
		{
			this.exists = pExists;
			this.armed = pArmed;
			this.fingerprint = pFingerprint;
		}
	}

	/** One journal row as read: the staging record it names and the counter to compare at deletion. */
	static final class Row
	{
		final String sourceSystem;
		final String sourceId;
		final Integer changes;

		Row(final String pSourceSystem, final String pSourceId, final Integer pChanges)
		{
			this.sourceSystem = pSourceSystem;
			this.sourceId = pSourceId;
			this.changes = pChanges;
		}
	}

	// ===================================================================================================== prepare / install

	/**
	 * Makes sure the journal can be used by a load of the given entities: the dataset exists in the staging dataspace and both triggers are installed and authored on every
	 * entity's staging table. Installs what is missing (the dataset by {@code doCreateRoot}; the triggers through the {@code data-model} and {@code scripting} servers, the
	 * mechanism {@link ScriptTriggerOps} documents) and then says {@link Prepared#installedNow}. Never throws: anything that goes wrong returns an unusable journal with the reason.
	 */
	static Prepared prepare(final Session pSession, final Adaptation pFactory, final String pProjectCode, final AdaptationHome pStagingHome, final List<String> pEntityNames,
		final StagingLoadListener pListener)
	{
		final ObjectNode detail = MasterJournal.MAPPER.createObjectNode();
		detail.put("dataset", FactoryPaths.MASTER_JOURNAL_DATASET);
		if (!MasterLoadScope.journalEnabled())
		{
			detail.put("recordingStopped", MasterJournal.stopRecording(pSession, pStagingHome));
			return Prepared.unusable("switched off (-D" + MasterLoadScope.JOURNAL_ENABLED_PROPERTY + "=false)", pProjectCode, detail);
		}
		if (pStagingHome == null)
		{
			return Prepared.unusable("the staging dataspace was not found", pProjectCode, detail);
		}
		try
		{
			final String modelName = StagingNames.modelName(pProjectCode);
			detail.put("stagingModel", modelName);
			Adaptation dataset = pStagingHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.MASTER_JOURNAL_DATASET));
			final boolean datasetWasMissing = dataset == null;
			boolean installedNow = false;
			final McpClient dataModelClient;
			try
			{
				dataModelClient = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
			}
			catch (final OperationException ex)
			{
				return Prepared.unusable("the data-model server is not reachable, the triggers cannot be verified: " + ex.getMessage(), pProjectCode, detail);
			}
			final List<String> missing = MasterJournal.missingTriggers(dataModelClient, modelName, pEntityNames, detail);
			if (datasetWasMissing || !missing.isEmpty())
			{
				final List<String> toInstall = datasetWasMissing ? pEntityNames : missing;
				pListener.note("Journal of changed staging rows: " + (datasetWasMissing ? "creating the dataset and " : "") + "installing the triggers on " + toInstall.size()
					+ " staging table(s) (one-off, publishes the staging model).");
				dataset = MasterJournal.ensureDataset(pSession, pStagingHome);
				final McpClient scriptingClient = McpServers.client(pSession, pFactory, McpServers.SCRIPTING);
				MasterJournal.install(dataModelClient, scriptingClient, modelName, pProjectCode, toInstall, detail);
				final List<String> stillMissing = MasterJournal.missingTriggers(dataModelClient, modelName, pEntityNames, detail);
				if (!stillMissing.isEmpty())
				{
					return Prepared.unusable("triggers not reported after the install: " + stillMissing, pProjectCode, detail);
				}
				installedNow = true;
			}
			detail.put("usable", true);
			detail.put("installedNow", installedNow);
			return new Prepared(true, null, installedNow, dataset, pStagingHome, pProjectCode, detail);
		}
		catch (final OperationException | McpException | RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] master load of " + pProjectCode + ": the journal of changed staging rows cannot be used, loading in full: " + ex);
			return Prepared.unusable(ex.getClass().getSimpleName() + ": " + ex.getMessage(), pProjectCode, detail);
		}
	}

	/**
	 * The kill switch: with the journal switched off, the {@code Control} rows of the project are deleted - the generated triggers return at their first lookup, so no
	 * journal row is written any more and nothing needs republishing. Returns how many rows were deleted (0 when there is no journal). Best-effort, never throws.
	 */
	private static int stopRecording(final Session pSession, final AdaptationHome pStagingHome)
	{
		try
		{
			final Adaptation dataset = pStagingHome == null ? null : pStagingHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.MASTER_JOURNAL_DATASET));
			if (dataset == null)
			{
				return 0;
			}
			final AdaptationTable table = dataset.getTable(FactoryPaths.MasterJournal.CONTROL);
			final List<Adaptation> rows = new ArrayList<Adaptation>();
			final RequestResult result = table.createRequestResult(null);
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
			if (rows.isEmpty())
			{
				return 0;
			}
			final ProcedureResult outcome = ProgrammaticService.createForSession(pSession, pStagingHome).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					for (final Adaptation row : rows)
					{
						pContext.doDelete(row.getAdaptationName(), false);
					}
				}
			});
			return outcome.hasFailed() ? 0 : rows.size();
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] master journal: could not stop the recording: " + ex);
			return 0;
		}
	}

	/** The entities whose two journal triggers are not both reported by the data-model server (recorded in {@code pDetail.verify}). */
	private static List<String> missingTriggers(final McpClient pDataModelClient, final String pModelName, final List<String> pEntityNames, final ObjectNode pDetail)
		throws McpException
	{
		final McpClient.ToolResult listed = DerivationTriggers.listTriggers(pDataModelClient, pModelName);
		final List<String> missing = new ArrayList<String>();
		if (listed.error)
		{
			pDetail.put("verifyError", DerivationApplier.shortText(listed.text));
			// the model may simply not exist yet / not list: every entity counts as missing, the install will say why if it cannot go on
			missing.addAll(pEntityNames);
			return missing;
		}
		for (final String entity : pEntityNames)
		{
			final String tablePath = StagingModel.tablePath(entity);
			for (final String name : MasterJournalTriggerBody.scriptNames())
			{
				final DerivationTriggers.State state = DerivationTriggers.stateOf(listed.json, name, tablePath);
				if (state == DerivationTriggers.State.MISSING || state == DerivationTriggers.State.NOT_AUTHORED)
				{
					if (!missing.contains(entity))
					{
						missing.add(entity);
					}
				}
			}
		}
		pDetail.put("triggersMissing", missing.size());
		return missing;
	}

	/** The journal dataset of the staging dataspace, created (a second ROOT dataset, the pattern of {@link IntakeSignalInstaller}) when missing. */
	private static Adaptation ensureDataset(final Session pSession, final AdaptationHome pStagingHome) throws OperationException
	{
		final Adaptation existing = pStagingHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.MASTER_JOURNAL_DATASET));
		if (existing != null)
		{
			return existing;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pStagingHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final Adaptation created = pContext.doCreateRoot(SchemaLocation.forPathInModule(FactoryPaths.MASTER_JOURNAL_MODEL_PATH, FactoryPaths.MODULE_NAME),
					AdaptationReference.forPersistentName(FactoryPaths.MASTER_JOURNAL_DATASET), Profile.ADMINISTRATOR);
				pContext.setInstanceLabel(created, UserMessage.createInfo("Journal of changed staging rows"));
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("external.journalDataset", "Could not create the " + FactoryPaths.MASTER_JOURNAL_DATASET + " dataset in " + pStagingHome.getKey().getName() + ": "
				+ result.getExceptionFullMessage(Locale.ENGLISH), pStagingHome.getKey().getName());
		}
		final Adaptation created = pStagingHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.MASTER_JOURNAL_DATASET));
		if (created == null)
		{
			throw EngineRefusal.coded("external.journalDataset", FactoryPaths.MASTER_JOURNAL_DATASET + " dataset creation reported success but it cannot be found in "
				+ pStagingHome.getKey().getName(), pStagingHome.getKey().getName());
		}
		return created;
	}

	/**
	 * Declares both triggers on every given staging table, publishes the staging model once if something was newly declared (a script declared and never published has
	 * no revision history for {@code update_script}: {@link ScriptTriggerOps}), authors every body, and publishes again so the bodies are live. Idempotent: the bodies are rewritten.
	 */
	private static void install(final McpClient pDataModelClient, final McpClient pScriptingClient, final String pModelName, final String pProjectCode, final List<String> pEntityNames,
		final ObjectNode pDetail) throws McpException, OperationException
	{
		final ArrayNode tables = pDetail.putArray("install");
		final List<Object[]> pending = new ArrayList<Object[]>();
		boolean anyNew = false;
		for (final String entity : pEntityNames)
		{
			final String tablePath = StagingModel.tablePath(entity);
			final ObjectNode tableDetail = tables.addObject();
			tableDetail.put("tablePath", tablePath);
			for (final String type : MasterJournalTriggerBody.TYPES)
			{
				final ObjectNode triggerDetail = tableDetail.putObject(type);
				anyNew = ScriptTriggerOps.declare(pDataModelClient, pScriptingClient, pModelName, tablePath, entity, type, MasterJournalTriggerBody.scriptName(type), triggerDetail) || anyNew;
				pending.add(new Object[] { entity, type, triggerDetail });
			}
		}
		pDetail.put("declarePublish", anyNew);
		if (anyNew)
		{
			ScriptTriggerOps.publish(pDataModelClient, pModelName, "after DECLARING the master journal triggers (needed before authoring a first-ever trigger)");
		}
		for (final Object[] item : pending)
		{
			final String entity = (String) item[0];
			final String type = (String) item[1];
			ScriptTriggerOps.author(pScriptingClient, pModelName, StagingModel.tablePath(entity), entity, type, MasterJournalTriggerBody.scriptName(type),
				MasterJournalTriggerBody.body(type, pProjectCode, entity), (ObjectNode) item[2]);
		}
		ScriptTriggerOps.publish(pDataModelClient, pModelName, "after authoring the master journal triggers");
	}

	// ======================================================================================================= control rows

	/** The entity's {@code Control} row; {@code exists=false} when none. */
	static Control readControl(final Prepared pPrepared, final String pEntity)
	{
		final AdaptationTable table = pPrepared.dataset.getTable(FactoryPaths.MasterJournal.CONTROL);
		final Adaptation row = table.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { pPrepared.projectCode, pEntity }));
		if (row == null)
		{
			return new Control(false, false, null);
		}
		return new Control(true, Boolean.TRUE.equals(row.get(FactoryPaths.MasterJournal.ARMED)), row.getString(FactoryPaths.MasterJournal.PLAN_FINGERPRINT));
	}

	/**
	 * Starts recording an entity and disarms it - called BEFORE a full load reads anything: from now on every staging write is journaled, and if this load does not
	 * finish the entity stays disarmed, so the next load is full. Keeps an earlier {@code recordingSince}.
	 */
	static boolean begin(final Session pSession, final Prepared pPrepared, final String pEntity)
	{
		return MasterJournal.writeControl(pSession, pPrepared, pEntity, Boolean.FALSE, null, true);
	}

	/** Arms an entity: a load of it ended normally and what it copied is described by {@code pFingerprint}. */
	static boolean arm(final Session pSession, final Prepared pPrepared, final String pEntity, final String pFingerprint)
	{
		return MasterJournal.writeControl(pSession, pPrepared, pEntity, Boolean.TRUE, pFingerprint, false);
	}

	private static boolean writeControl(final Session pSession, final Prepared pPrepared, final String pEntity, final Boolean pArmed, final String pFingerprint,
		final boolean pStartRecording)
	{
		final AdaptationTable table = pPrepared.dataset.getTable(FactoryPaths.MasterJournal.CONTROL);
		final Date now = new Date();
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pPrepared.home).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation existing = table.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { pPrepared.projectCode, pEntity }));
				final ValueContextForUpdate value = existing == null ? pContext.getContextForNewOccurrence(table) : pContext.getContext(existing.getAdaptationName());
				if (existing == null)
				{
					value.setValue(pPrepared.projectCode, FactoryPaths.MasterJournal.PROJECT);
					value.setValue(pEntity, FactoryPaths.MasterJournal.ENTITY);
				}
				value.setValue(pArmed, FactoryPaths.MasterJournal.ARMED);
				if (pArmed.booleanValue())
				{
					value.setValue(now, FactoryPaths.MasterJournal.ARMED_AT);
					value.setValue(pFingerprint, FactoryPaths.MasterJournal.PLAN_FINGERPRINT);
				}
				if (existing == null || pStartRecording && existing.get(FactoryPaths.MasterJournal.RECORDING_SINCE) == null)
				{
					value.setValue(now, FactoryPaths.MasterJournal.RECORDING_SINCE);
				}
				value.setValue("master load", FactoryPaths.MasterJournal.UPDATED_BY);
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
			LoggingCategory.getKernel().warn("[mdm-factory] master journal: could not write the control row of " + pEntity + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
			return false;
		}
		return true;
	}

	// ============================================================================================================ the rows

	/** How many journal rows the entity has (a count, no row read). */
	static long countRows(final Prepared pPrepared, final String pEntity)
	{
		final RequestResult rows = pPrepared.dataset.getTable(FactoryPaths.MasterJournal.CHANGE).createRequestResult(MasterJournal.entityPredicate(pPrepared, pEntity));
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * The entity's journal rows, staging key -&gt; row, in table order. The key is computed with {@code computePrimaryKey(...).format()} (never built by hand); a row whose
	 * two key fields are missing is skipped. At most {@code pLimit} rows: more means the caller already decided to load in full.
	 */
	static Map<String, Row> readRows(final Prepared pPrepared, final String pEntity, final AdaptationTable pStagingTable, final long pLimit)
	{
		final Map<String, Row> out = new LinkedHashMap<String, Row>();
		final RequestResult rows = pPrepared.dataset.getTable(FactoryPaths.MasterJournal.CHANGE).createRequestResult(MasterJournal.entityPredicate(pPrepared, pEntity));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null && out.size() < pLimit; row = rows.nextAdaptation())
			{
				final String sourceSystem = row.getString(FactoryPaths.MasterJournal.SOURCE_SYSTEM);
				final String sourceId = row.getString(FactoryPaths.MasterJournal.SOURCE_ID);
				if (sourceSystem == null || sourceId == null)
				{
					continue;
				}
				final Object changes = row.get(FactoryPaths.MasterJournal.CHANGES);
				final Integer counter = changes instanceof Number ? Integer.valueOf(((Number) changes).intValue()) : null;
				out.put(pStagingTable.computePrimaryKey(new Object[] { sourceSystem, sourceId }).format(), new Row(sourceSystem, sourceId, counter));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * The staging keys of the entity's open MASTER rejects (a row refused earlier - for instance "unknown parent" - can be accepted now although its staging row did not
	 * change, so a changed load reads them again). Empty when none.
	 */
	static Set<String> openRejectKeys(final Adaptation pFactory, final String pProjectId, final String pEntityId, final AdaptationTable pStagingTable)
	{
		final Set<String> out = new LinkedHashSet<String>();
		if (pEntityId == null)
		{
			return out;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.TRANSFER_REJECT).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./entity="
			+ CatalogReader.quote(pEntityId) + " and ./stage=" + CatalogReader.quote("MASTER") + " and not(./resolved=" + CatalogReader.quote("true") + ")");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String sourceSystem = row.getString(FactoryPaths.TransferReject.SOURCE_SYSTEM);
				final String sourceId = row.getString(FactoryPaths.TransferReject.SOURCE_ID);
				if (sourceSystem != null && sourceId != null)
				{
					out.add(pStagingTable.computePrimaryKey(new Object[] { sourceSystem, sourceId }).format());
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Deletes the journal rows a load consumed, each only if its counter still equals the one read ({@link JournalConsumption#shouldDelete}); one Procedure per {@value
	 * #DELETE_BATCH} rows. Returns how many were deleted; a failed batch is logged and counted as not deleted (the rows stay, the next load reads them again, harmlessly).
	 */
	static int consume(final Session pSession, final Prepared pPrepared, final String pEntity, final Map<String, Row> pRead)
	{
		final AdaptationTable table = pPrepared.dataset.getTable(FactoryPaths.MasterJournal.CHANGE);
		final List<String> keys = JournalConsumption.consumable(pRead, true);
		final int[] deleted = new int[1];
		for (final List<String> batch : JournalConsumption.batches(keys, MasterJournal.DELETE_BATCH))
		{
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pPrepared.home).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					for (final String key : batch)
					{
						final Row read = pRead.get(key);
						final Adaptation current = table
							.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { pPrepared.projectCode, pEntity, read.sourceSystem, read.sourceId }));
						if (current == null)
						{
							continue;
						}
						final Object counter = current.get(FactoryPaths.MasterJournal.CHANGES);
						final Integer now = counter instanceof Number ? Integer.valueOf(((Number) counter).intValue()) : null;
						if (JournalConsumption.shouldDelete(read.changes, now))
						{
							pContext.doDelete(current.getAdaptationName(), false);
							deleted[0]++;
						}
					}
				}
			});
			if (result.hasFailed())
			{
				LoggingCategory.getKernel().warn("[mdm-factory] master journal: could not delete a batch of consumed rows of " + pEntity + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
			}
		}
		return deleted[0];
	}

	private static String entityPredicate(final Prepared pPrepared, final String pEntity)
	{
		return "./project=" + CatalogReader.quote(pPrepared.projectCode) + " and ./entity=" + CatalogReader.quote(pEntity);
	}
}
