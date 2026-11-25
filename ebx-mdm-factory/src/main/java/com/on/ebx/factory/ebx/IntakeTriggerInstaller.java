package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.intake.IntakeTriggerBody;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Automatic intake lot (docs/AUTOMATIC-INTAKE.md): installs / removes the three generated landing triggers ({@link IntakeTriggerBody}) on every ticked landing
 * table of every source of a project, through the caller's session MCP clients exactly as {@link DistributionTriggerInstaller} obtains them
 * ({@code McpServers.client(session, factory, DATA_MODEL | SCRIPTING)}), by the mechanism {@link ScriptTriggerOps} documents. The landing models are one per source system
 * ({@code <Project>_Landing_<SOURCE>}), so the sequence runs per source: declare every trigger of every table -&gt; publish ONCE if something was newly declared
 * -&gt; author every body -&gt; final publish. {@link #install} first creates the signal dataset and rows in the landing dataspace ({@link IntakeSignalInstaller}).
 * Idempotent: re-running regenerates and republishes every body.
 * <p>
 * The columns the before-modify trigger watches are the table's real fields ({@code describe_table}) minus the factory's technical fields; when that call answers
 * nothing, the {@code LandingColumn} rows of the table (generated names) are used. A landing model that is rebuilt later loses its triggers: the intake must be
 * switched on again (the engine's safety sweep covers the gap meanwhile). <b>Not run live yet.</b>
 */
final class IntakeTriggerInstaller
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String STEP = "INTAKE_TRIGGER_INSTALL";

	private IntakeTriggerInstaller()
	{
	}

	private static final class Pending
	{
		final String tablePath;
		final String tableName;
		final String type;
		final String scriptName;
		final List<String> columns;
		final ObjectNode detail;

		Pending(final String pTablePath, final String pTableName, final String pType, final List<String> pColumns, final ObjectNode pDetail)
		{
			this.tablePath = pTablePath;
			this.tableName = pTableName;
			this.type = pType;
			this.scriptName = IntakeTriggerBody.scriptName(pType);
			this.columns = pColumns;
			this.detail = pDetail;
		}
	}

	/**
	 * F2-102: told after each source whose triggers are installed AND published, so a job can keep a readable state of a long installation (which sources have their
	 * triggers, which do not) and stop between two sources when asked to.
	 */
	interface Progress
	{
		void sourceInstalled(String pSourceSystem, int pTables) throws OperationException;
	}

	static ObjectNode install(final Session pSession, final Adaptation pFactory, final Adaptation pProject) throws OperationException
	{
		return IntakeTriggerInstaller.install(pSession, pFactory, pProject, null);
	}

	static ObjectNode install(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final Progress pProgress) throws OperationException
	{
		final String projectCode = pProject.getString(FactoryPaths.Project.CODE);
		final String projectId = pProject.getOccurrencePrimaryKey().format();
		final List<IntakeSources.Info> sources = IntakeSources.read(pFactory, pProject);
		if (sources.isEmpty())
		{
			throw EngineRefusal.coded("missing.noSource", "Project " + projectCode + " has no source", projectCode);
		}
		final AdaptationHome landingHome = IntakeSources.landingHome(pFactory, pProject);
		if (landingHome == null)
		{
			throw EngineRefusal.coded("missing.landingDataspace", "The landing dataspace " + IntakeSources.landingDataspaceKey(pFactory, pProject) + " of project " + projectCode + " does not exist", IntakeSources.landingDataspaceKey(pFactory, pProject), projectCode);
		}
		final Adaptation signalDataset = IntakeSignalInstaller.ensure(pSession, landingHome, FactoryPaths.MODULE_NAME);
		final List<String> present = new ArrayList<String>();
		for (final IntakeSources.Info source : sources)
		{
			if (IntakeSources.landingDataset(landingHome, source) != null)
			{
				present.add(source.sourceSystem);
			}
		}
		IntakeSignalInstaller.ensureRows(pSession, signalDataset, present);

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, IntakeTriggerInstaller.STEP, "");
		final ObjectNode detail = IntakeTriggerInstaller.MAPPER.createObjectNode();
		final ArrayNode sourceDetails = detail.putArray("sources");
		try
		{
			final McpClient dataModelClient = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
			final McpClient scriptingClient = McpServers.client(pSession, pFactory, McpServers.SCRIPTING);
			int tableCount = 0;
			for (final IntakeSources.Info source : sources)
			{
				final ObjectNode sourceDetail = sourceDetails.addObject();
				sourceDetail.put("sourceSystem", source.sourceSystem);
				sourceDetail.put("model", source.modelName);
				if (!present.contains(source.sourceSystem))
				{
					sourceDetail.put("skipped", "the landing dataset " + source.landingDataset + " does not exist");
					continue;
				}
				if (source.tableNames.isEmpty())
				{
					sourceDetail.put("skipped", "no ticked landing table");
					continue;
				}
				final int installed = IntakeTriggerInstaller.installSource(dataModelClient, scriptingClient, pFactory, source, sourceDetail);
				tableCount += installed;
				if (pProgress != null)
				{
					pProgress.sourceInstalled(source.sourceSystem, installed);
				}
			}
			detail.put("tables", tableCount);
			PipelineSteps.end(pSession, pFactory, stepId, true, "Installed the intake triggers on " + tableCount + " landing table(s)", detail.toString());
			final ObjectNode out = IntakeTriggerInstaller.MAPPER.createObjectNode();
			out.put("status", "DONE");
			out.set("detail", detail);
			return out;
		}
		catch (final McpException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ex.getMessage(), detail.toString());
			throw EngineRefusal.coded("unexpected.installIntake", "Could not install the intake triggers for project " + projectCode + ": " + ex.getMessage(), projectCode);
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ex.getMessage(), detail.toString());
			throw ex;
		}
	}

	private static int installSource(final McpClient pDataModelClient, final McpClient pScriptingClient, final Adaptation pFactory, final IntakeSources.Info pSource,
		final ObjectNode pSourceDetail) throws McpException, OperationException
	{
		final List<Pending> pending = new ArrayList<Pending>();
		boolean anyNew = false;
		final ArrayNode tables = pSourceDetail.putArray("tables");
		for (final String tableName : pSource.tableNames)
		{
			final String tablePath = "/root/" + tableName;
			final ObjectNode tableDetail = tables.addObject();
			tableDetail.put("tablePath", tablePath);
			List<String> columns = IntakeTriggerBody.sourceColumns(ScriptTriggerOps.fieldNames(pDataModelClient, pSource.modelName, tablePath));
			if (columns.isEmpty())
			{
				columns = IntakeTriggerInstaller.columnsFromFactory(pFactory, pSource, tableName);
				tableDetail.put("columnsFrom", "LandingColumn");
			}
			else
			{
				tableDetail.put("columnsFrom", "describe_table");
			}
			tableDetail.put("columns", columns.size());
			for (final String type : IntakeTriggerBody.TYPES)
			{
				final ObjectNode triggerDetail = tableDetail.putObject(type);
				final Pending item = new Pending(tablePath, tableName, type, columns, triggerDetail);
				anyNew = ScriptTriggerOps.declare(pDataModelClient, pScriptingClient, pSource.modelName, tablePath, tableName, type, item.scriptName, triggerDetail) || anyNew;
				pending.add(item);
			}
		}
		// a script that was declared and never published has no revision history for update_script to load (distribution round 6 fix A)
		pSourceDetail.put("declarePublish", anyNew);
		if (anyNew)
		{
			ScriptTriggerOps.publish(pDataModelClient, pSource.modelName, "after DECLARING the intake triggers (needed before authoring a first-ever trigger)");
		}
		for (final Pending item : pending)
		{
			ScriptTriggerOps.author(pScriptingClient, pSource.modelName, item.tablePath, item.tableName, item.type, item.scriptName,
				IntakeTriggerBody.body(item.type, pSource.sourceSystem, item.columns), item.detail);
		}
		ScriptTriggerOps.publish(pDataModelClient, pSource.modelName, "after authoring the intake triggers");
		return pSource.tableNames.size();
	}

	/** The generated field names of the table's {@code LandingColumn} rows - the fallback when {@code describe_table} answered nothing. */
	private static List<String> columnsFromFactory(final Adaptation pFactory, final IntakeSources.Info pSource, final String pTableName)
	{
		final List<String> names = new ArrayList<String>();
		final RequestResult tables = pFactory.getTable(FactoryPaths.LANDING_TABLE)
			.createRequestResult("./projectSource=" + CatalogReader.quote(pSource.id) + " and ./tableName=" + CatalogReader.quote(pTableName));
		try
		{
			final Adaptation table = tables.nextAdaptation();
			if (table == null)
			{
				return names;
			}
			final RequestResult columns = pFactory.getTable(FactoryPaths.LANDING_COLUMN)
				.createRequestResult("./landingTable=" + CatalogReader.quote(table.getOccurrencePrimaryKey().format()));
			try
			{
				for (Adaptation column = columns.nextAdaptation(); column != null; column = columns.nextAdaptation())
				{
					final String name = column.getString(FactoryPaths.LandingColumn.FIELD_NAME);
					if (name != null)
					{
						names.add(name);
					}
				}
			}
			finally
			{
				columns.close();
			}
		}
		finally
		{
			tables.close();
		}
		return IntakeTriggerBody.sourceColumns(names);
	}

	/**
	 * Removes the three triggers of every ticked landing table, then publishes each landing model once. A trigger or model that is already gone is reported in the
	 * answer, never a failure (a landing reset removes them by itself). Never touches another trigger of the table.
	 */
	static ObjectNode uninstall(final Session pSession, final Adaptation pFactory, final Adaptation pProject) throws OperationException
	{
		final ObjectNode out = IntakeTriggerInstaller.MAPPER.createObjectNode();
		final ArrayNode notes = out.putArray("notes");
		final List<IntakeSources.Info> sources = IntakeSources.read(pFactory, pProject);
		final McpClient dataModelClient;
		try
		{
			dataModelClient = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
		}
		catch (final OperationException ex)
		{
			out.put("status", "SKIPPED");
			notes.add("the data-model server is not reachable: " + ex.getMessage());
			return out;
		}
		int removed = 0;
		for (final IntakeSources.Info source : sources)
		{
			boolean touched = false;
			for (final String tableName : source.tableNames)
			{
				for (final String type : IntakeTriggerBody.TYPES)
				{
					final String reason = ScriptTriggerOps.remove(dataModelClient, source.modelName, "/root/" + tableName, IntakeTriggerBody.scriptName(type));
					if (reason == null)
					{
						removed++;
						touched = true;
					}
					else
					{
						notes.add(source.modelName + " " + tableName + " " + IntakeTriggerBody.scriptName(type) + ": " + reason);
					}
				}
			}
			if (touched)
			{
				try
				{
					ScriptTriggerOps.publish(dataModelClient, source.modelName, "after removing the intake triggers");
				}
				catch (final McpException ex)
				{
					notes.add("publish of " + source.modelName + " failed: " + ex.getMessage());
				}
			}
		}
		out.put("status", "DONE");
		out.put("removed", removed);
		return out;
	}
}
