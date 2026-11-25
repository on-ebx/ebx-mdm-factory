package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.delivery.DeliveryDomain;
import com.on.ebx.factory.delivery.DeliveryPreCheck;
import com.on.ebx.factory.delivery.ScriptImports;
import com.on.ebx.factory.master.MameAddonDecision;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;

/**
 * Reads what {@link DeliveryDomain#compose} and {@link DeliveryPreCheck#check} need, under the caller's
 * own session and rights, and writes nothing: the project's rows, its {@code PipelineStep} ledger (a zone
 * travels only when its model and dataset steps are {@code DONE}), the promoted master tables and their
 * Match and Merge configuration rows ({@code ebx-addon-mame/ebx-addon-mame-configuration/root/TableConfiguration},
 * whose {@code id} is the staging element id of a {@code ebx-addon-mame-MatchingConfiguration} - measured on
 * the POS domain, {@code docs/DELIVERY.md} sec 3.1), the source connectors and the reference datasets.
 * Route: {@code GET /rest/v1/projects/{code}/delivery/domain}. No MCP call, no agent account.
 */
public final class DeliveryDomainReader
{
	private DeliveryDomainReader()
	{
	}

	private static final String CONNECTORS_DATASPACE = "Connectors";
	private static final String CONNECTORS_DATASET = "Connectors";
	private static final Path CONNECTOR_TABLE = Path.parse("/root/Connector");
	private static final Path C_ACTIVE = Path.parse("./active");
	private static final Path C_API_KEY = Path.parse("./apiKey");
	private static final Path C_PASSWORD = Path.parse("./password");

	/** The DMA's technical dataspace: one dataset per data model, table {@code /root/toolbarNode} (CLAUDE.md sec 6). */
	private static final String DMA_DATASPACE = "ebx-dma";
	private static final Path TOOLBAR_NODE_TABLE = Path.parse("/root/toolbarNode");
	private static final Path TB_SERVICE_KEY = Path.parse("./actionButton/service/serviceKey");
	private static final Path TB_SERVICE_PARAMETERS = Path.parse("./actionButton/service/serviceParameters");
	private static final Path TB_PARAM_NAME = Path.parse("./name");
	private static final Path TB_PARAM_VALUE = Path.parse("./value");

	/** EBX's own script sources (measured 2026-09-22: the "Dataset:Script sources" every {@code ScriptSource} staging element depends on). */
	private static final String SCRIPT_SOURCE_DATASPACE = "ebx-script-source-2";
	private static final String SCRIPT_SOURCE_DATASET = "ebx-script-source";
	private static final Path SCRIPT_DEFINITION_TABLE = Path.parse("/root/definition");
	private static final Path SCRIPT_SOURCE = Path.parse("./source");

	private static final String MAME_DATASPACE = MameAddonDecision.MAME_NAME;
	private static final String MAME_CONFIGURATION_DATASET = "ebx-addon-mame-configuration";
	private static final Path TABLE_CONFIGURATION = Path.parse("/root/TableConfiguration");
	private static final Path TC_DATA_MODEL = Path.parse("./dataModel");
	private static final Path TC_TABLE = Path.parse("./table");
	/** A DMA publication's {@code SchemaLocation}, as the add-on stores it on a {@code TableConfiguration} row (read live: {@code urn:ebx:publication:Companies_Master}). */
	private static final String PUBLICATION_URN = "urn:ebx:publication:";

	public static ObjectNode read(final Repository pRepository, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = ProposalReviews.findProjectByCode(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final String code = UserView.string(project, FactoryPaths.Project.CODE);

		// sources and their landing steps
		final List<DeliveryDomain.Source> sources = new ArrayList<DeliveryDomain.Source>();
		final Map<String, List<String>> connectorUsers = new LinkedHashMap<String, List<String>>();
		boolean landingDelivered = false;
		final RequestResult sourceRows = UserView.select(pFactory.getTable(FactoryPaths.PROJECT_SOURCE), "./project=" + CatalogReader.quote(projectId));
		try
		{
			for (Adaptation row = sourceRows.nextAdaptation(); row != null; row = sourceRows.nextAdaptation())
			{
				final String sourceSystem = UserView.string(row, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
				final boolean modelDone = PipelineSteps.isDone(pFactory, projectId, "LANDING_MODEL", sourceSystem);
				final boolean datasetDone = PipelineSteps.isDone(pFactory, projectId, "LANDING_DATASET", sourceSystem);
				sources.add(new DeliveryDomain.Source(sourceSystem, UserView.string(row, FactoryPaths.ProjectSource.LANDING_MODEL),
					UserView.string(row, FactoryPaths.ProjectSource.LANDING_PUBLICATION), UserView.string(row, FactoryPaths.ProjectSource.LANDING_DATASET),
					UserView.string(row, FactoryPaths.ProjectSource.CONNECTOR), modelDone, datasetDone));
				final String connector = UserView.string(row, FactoryPaths.ProjectSource.CONNECTOR);
				if (modelDone && datasetDone)
				{
					landingDelivered = true;
					if (connector != null && !connector.trim().isEmpty())
					{
						List<String> users = connectorUsers.get(connector.trim());
						if (users == null)
						{
							users = new ArrayList<String>();
							connectorUsers.put(connector.trim(), users);
						}
						users.add(sourceSystem);
					}
				}
			}
		}
		finally
		{
			sourceRows.close();
		}

		final String stagingModel = com.on.ebx.factory.staging.StagingNames.modelName(code);
		final String masterModel = com.on.ebx.factory.master.MasterNames.modelName(code);
		final boolean masterModelDone = PipelineSteps.isDone(pFactory, projectId, "MASTER_MODEL", "");
		final boolean masterDatasetDone = PipelineSteps.isDone(pFactory, projectId, "MASTER_DATASET", "");
		final DeliveryDomain.Project projectInfo = new DeliveryDomain.Project(code, UserView.string(project, FactoryPaths.Project.LANDING_DATASPACE),
			UserView.string(project, FactoryPaths.Project.STAGING_DATASPACE), UserView.string(project, FactoryPaths.Project.STAGING_DATASET), stagingModel,
			PipelineSteps.isDone(pFactory, projectId, "STAGING_MODEL", ""), PipelineSteps.isDone(pFactory, projectId, "STAGING_DATASET", ""),
			UserView.string(project, FactoryPaths.Project.MASTER_DATASPACE), UserView.string(project, FactoryPaths.Project.MASTER_DATASET), masterModel,
			masterModelDone, masterDatasetDone);

		// promoted master tables: their applied plan and the add-on's configuration row
		final List<DeliveryDomain.Matching> matching = new ArrayList<DeliveryDomain.Matching>();
		final ProjectMasterEntities.Result entities = ProjectMasterEntities.readEntities(pFactory, projectId);
		final Adaptation mameConfiguration = DeliveryDomainReader.dataset(pRepository, DeliveryDomainReader.MAME_DATASPACE,
			DeliveryDomainReader.MAME_CONFIGURATION_DATASET);
		boolean matchingDelivered = false;
		for (final String tablePath : ProjectMasterEntities.promotedMasterTablePaths(pFactory, projectId))
		{
			final String entityName = tablePath.substring(tablePath.lastIndexOf('/') + 1);
			final String entityId = DeliveryDomainReader.entityId(entities, entityName);
			final boolean planApplied = entityId != null && DeliveryDomainReader.planApplied(pFactory, entityId);
			final String configurationId = planApplied && mameConfiguration != null
				? DeliveryDomainReader.tableConfigurationId(mameConfiguration, DeliveryDomainReader.PUBLICATION_URN + masterModel, tablePath)
				: null;
			matchingDelivered |= planApplied && configurationId != null && masterModelDone && masterDatasetDone;
			matching.add(new DeliveryDomain.Matching(entityName, tablePath, planApplied, configurationId));
		}

		// where the tables live here
		final Adaptation connectors = DeliveryDomainReader.dataset(pRepository, DeliveryDomainReader.CONNECTORS_DATASPACE, DeliveryDomainReader.CONNECTORS_DATASET);
		final DeliveryDomain.Locations locations = new DeliveryDomain.Locations(FactoryPaths.DATASPACE, FactoryPaths.DATASET,
			connectors == null ? null : DeliveryDomainReader.CONNECTORS_DATASPACE, connectors == null ? null : DeliveryDomainReader.CONNECTORS_DATASET);

		// the script libraries the delivered models' applied constraints import, closed over their own imports
		final DeliveryDomain.Result withoutLibraries = DeliveryDomain.compose(projectInfo, sources, matching, locations);
		final List<String> deliveredModels = new ArrayList<String>();
		for (final DeliveryDomain.Component component : withoutLibraries.components)
		{
			if (DeliveryDomain.TYPE_DATAMODEL.equals(component.typeName))
			{
				deliveredModels.add(component.id);
			}
		}
		final ScriptImports.Closure libraries = DeliveryDomainReader.libraries(pRepository, pFactory, projectId, deliveredModels);
		final DeliveryDomain.Result domain = DeliveryDomain.compose(projectInfo, sources, matching, locations, libraries.libraries);

		// the pre-check: what Staging does not verify
		final List<DeliveryPreCheck.Connector> connectorFacts = new ArrayList<DeliveryPreCheck.Connector>();
		for (final Map.Entry<String, List<String>> entry : connectorUsers.entrySet())
		{
			final Adaptation row = connectors == null ? null
				: UserView.byKey(connectors.getTable(DeliveryDomainReader.CONNECTOR_TABLE), PrimaryKey.parseString(entry.getKey()));
			connectorFacts.add(new DeliveryPreCheck.Connector(entry.getKey(), entry.getValue(), row != null,
				row != null && !Boolean.FALSE.equals(UserView.get(row, DeliveryDomainReader.C_ACTIVE)), row != null && DeliveryDomainReader.isSet(row, DeliveryDomainReader.C_API_KEY),
				row != null && DeliveryDomainReader.isSet(row, DeliveryDomainReader.C_PASSWORD)));
		}
		final List<DeliveryPreCheck.Reference> references = new ArrayList<DeliveryPreCheck.Reference>();
		final RequestResult bindingRows = UserView.select(pFactory.getTable(FactoryPaths.REFERENCE_BINDING), null);
		try
		{
			for (Adaptation row = bindingRows.nextAdaptation(); row != null; row = bindingRows.nextAdaptation())
			{
				final String dataspace = UserView.string(row, FactoryPaths.ReferenceBinding.DATASPACE);
				final String dataset = UserView.string(row, FactoryPaths.ReferenceBinding.DATASET);
				references.add(new DeliveryPreCheck.Reference(UserView.string(row, FactoryPaths.ReferenceBinding.KIND), dataspace, dataset,
					DeliveryDomainReader.dataset(pRepository, dataspace, dataset) != null));
			}
		}
		finally
		{
			bindingRows.close();
		}
		final boolean addonPresent = pRepository.lookupHome(HomeKey.forBranchName(DeliveryDomainReader.MAME_DATASPACE)) != null;
		final List<DeliveryPreCheck.ToolbarParameter> toolbarParameters = new ArrayList<DeliveryPreCheck.ToolbarParameter>();
		for (final DeliveryDomain.Component component : domain.components)
		{
			if (DeliveryDomain.TYPE_DATAMODEL.equals(component.typeName))
			{
				DeliveryDomainReader.readToolbarParameters(pRepository, component.id, toolbarParameters);
			}
		}
		final DeliveryPreCheck.Result preCheck = DeliveryPreCheck.check(connectorFacts, references, matchingDelivered, addonPresent, connectors != null,
			landingDelivered, toolbarParameters, libraries.libraries, libraries.missing);

		final ObjectNode out = DeliveryDomain.toJson(domain);
		out.put("project", code);
		out.put("originRepository", UserView.string(project, FactoryPaths.Project.ORIGIN_REPOSITORY));
		out.put("instanceId", ProjectOrigin.instanceId(pFactory));
		out.put("stagingApi", "/ebx-dataservices/rest/staging/v1");
		DeliveryPreCheck.toJson(preCheck, out.putObject("preCheck"));
		return out;
	}

	/**
	 * Every service parameter of every toolbar button of one DMA model, read from {@code ebx-dma/<model>/root/toolbarNode}
	 * the way {@code DmaToolbarWriter.readServiceParameters} does ({@code getList} + {@code SchemaNode.executeRead} per
	 * occurrence). A model with no dataset under {@code ebx-dma}, or no toolbar rows, contributes nothing.
	 */
	private static void readToolbarParameters(final Repository pRepository, final String pModel, final List<DeliveryPreCheck.ToolbarParameter> pInto)
	{
		final Adaptation dataset = DeliveryDomainReader.dataset(pRepository, DeliveryDomainReader.DMA_DATASPACE, pModel);
		if (dataset == null || dataset.getSchemaNode().getNode(DeliveryDomainReader.TOOLBAR_NODE_TABLE) == null)
		{
			return;
		}
		final RequestResult rows = UserView.select(dataset.getTable(DeliveryDomainReader.TOOLBAR_NODE_TABLE), null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final List<?> raw = UserView.values(row, DeliveryDomainReader.TB_SERVICE_PARAMETERS);
				if (raw == null || raw.isEmpty())
				{
					continue;
				}
				final com.orchestranetworks.schema.SchemaNode groupNode = row.getSchemaNode().getNode(DeliveryDomainReader.TB_SERVICE_PARAMETERS);
				if (groupNode == null)
				{
					return;
				}
				final com.orchestranetworks.schema.SchemaNode nameNode = groupNode.getNode(DeliveryDomainReader.TB_PARAM_NAME);
				final com.orchestranetworks.schema.SchemaNode valueNode = groupNode.getNode(DeliveryDomainReader.TB_PARAM_VALUE);
				final String serviceKey = UserView.string(row, DeliveryDomainReader.TB_SERVICE_KEY);
				for (final Object occurrence : raw)
				{
					final Object name = nameNode.executeRead(occurrence);
					final Object value = valueNode.executeRead(occurrence);
					pInto.add(new DeliveryPreCheck.ToolbarParameter(pModel, serviceKey, name == null ? null : String.valueOf(name),
						value == null ? null : String.valueOf(value)));
				}
			}
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * The libraries imported by the project's APPLIED script constraints positioned on a delivered model
	 * ({@code CapabilitySuggestion.targetImportName}, e.g. {@code Fasttrack.lib.phone}), closed over their own
	 * imports by reading each source in {@code ebx-script-source-2/ebx-script-source} ({@code /root/definition},
	 * key {@code idePath}). A source the caller cannot read counts as missing.
	 */
	private static ScriptImports.Closure libraries(final Repository pRepository, final Adaptation pFactory, final String pProjectId,
		final List<String> pDeliveredModels)
	{
		final List<String> roots = new ArrayList<String>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION), "./project=" + CatalogReader.quote(pProjectId) + " and ./nature='SCRIPT_CONSTRAINT' and ./status='APPLIED'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String model = UserView.string(row, FactoryPaths.CapabilitySuggestion.APPLIED_MODEL);
				final String library = UserView.string(row, FactoryPaths.CapabilitySuggestion.TARGET_IMPORT_NAME);
				if (model != null && pDeliveredModels.contains(model) && library != null && !library.trim().isEmpty() && !roots.contains(library.trim()))
				{
					roots.add(library.trim());
				}
			}
		}
		finally
		{
			rows.close();
		}
		if (roots.isEmpty())
		{
			return ScriptImports.closure(roots, name -> null);
		}
		final Adaptation scripts = DeliveryDomainReader.dataset(pRepository, DeliveryDomainReader.SCRIPT_SOURCE_DATASPACE, DeliveryDomainReader.SCRIPT_SOURCE_DATASET);
		return ScriptImports.closure(roots, name -> {
			if (scripts == null)
			{
				return null;
			}
			final RequestResult found = UserView.select(scripts.getTable(DeliveryDomainReader.SCRIPT_DEFINITION_TABLE), "./idePath=" + CatalogReader.quote(ScriptImports.idePath(name)));
			try
			{
				final Adaptation row = found.nextAdaptation();
				return row == null ? null : String.valueOf(UserView.get(row, DeliveryDomainReader.SCRIPT_SOURCE));
			}
			finally
			{
				found.close();
			}
		});
	}

	private static String entityId(final ProjectMasterEntities.Result pEntities, final String pEntityName)
	{
		for (final Map.Entry<String, String> entry : pEntities.entityIdToName.entrySet())
		{
			if (pEntityName.equals(entry.getValue()))
			{
				return entry.getKey();
			}
		}
		return null;
	}

	private static boolean planApplied(final Adaptation pFactory, final String pEntityId)
	{
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.MATCH_PLAN), "./entity=" + CatalogReader.quote(pEntityId) + " and ./status='APPLIED'");
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	/** The add-on's {@code TableConfiguration.id} for one table of one publication, or null. */
	private static String tableConfigurationId(final Adaptation pMameConfiguration, final String pDataModelUrn, final String pTablePath)
	{
		final RequestResult rows = UserView.select(pMameConfiguration.getTable(DeliveryDomainReader.TABLE_CONFIGURATION), "./dataModel=" + CatalogReader.quote(pDataModelUrn) + " and ./table=" + CatalogReader.quote(pTablePath));
		try
		{
			final Adaptation row = rows.nextAdaptation();
			return row == null ? null : row.getOccurrencePrimaryKey().format();
		}
		finally
		{
			rows.close();
		}
	}

	private static Adaptation dataset(final Repository pRepository, final String pDataspace, final String pDataset)
	{
		if (pDataspace == null || pDataset == null)
		{
			return null;
		}
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pDataspace));
		return home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pDataset));
	}

	/** Whether a secret field holds something - never its value, which is encrypted and stays where it is. */
	private static boolean isSet(final Adaptation pRow, final Path pField)
	{
		final Object value = UserView.get(pRow, pField);
		return value != null && !String.valueOf(value).trim().isEmpty();
	}
}
