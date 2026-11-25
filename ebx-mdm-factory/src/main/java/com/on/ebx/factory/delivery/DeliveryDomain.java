package com.on.ebx.factory.delivery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The EBX Staging domain of one project: from what the project's rows say was generated, the ordered list
 * of staging components ({@code typeName}, {@code id}, {@code context}, {@code name}) that
 * {@code POST /ebx-dataservices/rest/staging/v1/domains/{domain}/components} takes, one call per
 * component. Pure: no EBX class, so the composition rules are settled and tested here; {@code
 * ebx.DeliveryDomainReader} only reads the rows and hands them over.
 * <p>
 * Design and measurements: {@code docs/DELIVERY.md} sec 3 (the component types and their contexts were
 * each accepted live on 2026-09-22 on a scratch domain, then exported, dry-run imported and deleted).
 * Rules:
 * <ul>
 * <li>a zone travels only when both its model and its dataset steps are {@code DONE} - an unpublished
 * model would be a half-delivery, so the zone is <b>refused</b> by name instead, and the caller reads
 * {@link Result#refused};</li>
 * <li>dependencies come first in the list (dataspace, then model, then publication, then dataset), the
 * order EBX's own dependency check wants;</li>
 * <li>the Factory's own tables travel <b>whole</b> (every project) - a per-project filtered table is a
 * published custom view EBX has no public API to create, see the study's sec 6.1;</li>
 * <li>Connectors' {@code Connector} and {@code ConnectorProperty} tables never travel (secrets); the
 * {@code Mapping}, {@code MappingInput} and {@code MappingRule} tables travel whole, when Connectors is
 * installed and at least one landing zone is delivered;</li>
 * <li>a Match and Merge configuration travels when its plan is {@code APPLIED} and the add-on's own
 * configuration row was found for the master table; a plan applied with no row found is refused, never
 * skipped in silence.</li>
 * </ul>
 */
public final class DeliveryDomain
{
	private DeliveryDomain()
	{
	}

	/** Domain names allow {@code [A-Za-z0-9._]} and no leading underscore (measured: a dash is refused). */
	public static final String DOMAIN_PREFIX = "mdm.factory.";

	public static final String TYPE_DATASPACE = "Dataspace";
	public static final String TYPE_DATAMODEL = "Datamodel";
	public static final String TYPE_PUBLICATION = "DatamodelPublication";
	public static final String TYPE_DATASET = "Dataset";
	public static final String TYPE_DATA_TABLE = "DataTable";
	public static final String TYPE_MATCHING_CONFIGURATION = "ebx-addon-mame-MatchingConfiguration";
	/** A script library's source (id {@code definition.library.<name>}, {@link ScriptImports#stagingElementId}); its publication is not a staging element. */
	public static final String TYPE_SCRIPT_SOURCE = "ScriptSource";

	/** Context keys, as {@code com.orchestranetworks.staging.StgConstants} names them (read by {@code javap}). */
	public static final String CTX_DATASPACE = "Dataspace";
	public static final String CTX_DATASET = "Dataset";

	/** The Factory tables that carry decisions - never the account, the LLM trace or DEV's own runs. */
	public static final String[] FACTORY_TABLES = { "Project", "ProjectSource", "LandingTable", "LandingColumn", "LandingRelation",
		"StagingEntity", "StagingAttribute", "AttributeMapping", "MappingStep", "QualityRule", "ReferenceBinding", "CapabilitySuggestion",
		"MatchPlan" };

	/** Connectors' tables that describe the landing flows and hold no secret. */
	public static final String[] CONNECTORS_TABLES = { "Mapping", "MappingInput", "MappingRule" };

	/** One source application of the project, as its {@code ProjectSource} row says. */
	public static final class Source
	{
		public final String sourceSystem;
		public final String landingModel;
		public final String landingPublication;
		public final String landingDataset;
		public final String connector;
		public final boolean modelDone;
		public final boolean datasetDone;

		public Source(final String pSourceSystem, final String pLandingModel, final String pLandingPublication, final String pLandingDataset,
			final String pConnector, final boolean pModelDone, final boolean pDatasetDone)
		{
			this.sourceSystem = pSourceSystem;
			this.landingModel = pLandingModel;
			this.landingPublication = pLandingPublication;
			this.landingDataset = pLandingDataset;
			this.connector = pConnector;
			this.modelDone = pModelDone;
			this.datasetDone = pDatasetDone;
		}
	}

	/** The project's own generated names and which zone steps are done. */
	public static final class Project
	{
		public final String code;
		public final String landingDataspace;
		public final String stagingDataspace;
		public final String stagingDataset;
		public final String stagingModel;
		public final boolean stagingModelDone;
		public final boolean stagingDatasetDone;
		public final String masterDataspace;
		public final String masterDataset;
		public final String masterModel;
		public final boolean masterModelDone;
		public final boolean masterDatasetDone;

		public Project(final String pCode, final String pLandingDataspace, final String pStagingDataspace, final String pStagingDataset,
			final String pStagingModel, final boolean pStagingModelDone, final boolean pStagingDatasetDone, final String pMasterDataspace,
			final String pMasterDataset, final String pMasterModel, final boolean pMasterModelDone, final boolean pMasterDatasetDone)
		{
			this.code = pCode;
			this.landingDataspace = pLandingDataspace;
			this.stagingDataspace = pStagingDataspace;
			this.stagingDataset = pStagingDataset;
			this.stagingModel = pStagingModel;
			this.stagingModelDone = pStagingModelDone;
			this.stagingDatasetDone = pStagingDatasetDone;
			this.masterDataspace = pMasterDataspace;
			this.masterDataset = pMasterDataset;
			this.masterModel = pMasterModel;
			this.masterModelDone = pMasterModelDone;
			this.masterDatasetDone = pMasterDatasetDone;
		}
	}

	/** One promoted master table and the Match and Merge configuration found for it (or not). */
	public static final class Matching
	{
		public final String entity;
		public final String tablePath;
		public final boolean planApplied;
		/** The add-on's {@code TableConfiguration.id} for the table, or null when no row was found. */
		public final String configurationId;

		public Matching(final String pEntity, final String pTablePath, final boolean pPlanApplied, final String pConfigurationId)
		{
			this.entity = pEntity;
			this.tablePath = pTablePath;
			this.planApplied = pPlanApplied;
			this.configurationId = pConfigurationId;
		}
	}

	/** Where the Factory's and Connectors' tables live on this instance. */
	public static final class Locations
	{
		public final String factoryDataspace;
		public final String factoryDataset;
		/** Null when Connectors is not installed. */
		public final String connectorsDataspace;
		public final String connectorsDataset;

		public Locations(final String pFactoryDataspace, final String pFactoryDataset, final String pConnectorsDataspace,
			final String pConnectorsDataset)
		{
			this.factoryDataspace = pFactoryDataspace;
			this.factoryDataset = pFactoryDataset;
			this.connectorsDataspace = pConnectorsDataspace;
			this.connectorsDataset = pConnectorsDataset;
		}
	}

	/** One staging component, the body of {@code POST .../components} is {@code {"element":{typeName,id,context},"name"}}. */
	public static final class Component
	{
		public final String typeName;
		public final String id;
		public final Map<String, String> context;
		public final String name;

		Component(final String pTypeName, final String pId, final Map<String, String> pContext, final String pName)
		{
			this.typeName = pTypeName;
			this.id = pId;
			this.context = Collections.unmodifiableMap(new LinkedHashMap<String, String>(pContext));
			this.name = pName;
		}
	}

	public static final class Result
	{
		public final String domainName;
		public final List<Component> components;
		/** Zones and configurations left out, each with the reason; empty when everything generated travels. */
		public final List<String> refused;
		/** Facts the operator should know that refuse nothing (Connectors absent, tables staged whole). */
		public final List<String> notes;

		Result(final String pDomainName, final List<Component> pComponents, final List<String> pRefused, final List<String> pNotes)
		{
			this.domainName = pDomainName;
			this.components = Collections.unmodifiableList(pComponents);
			this.refused = Collections.unmodifiableList(pRefused);
			this.notes = Collections.unmodifiableList(pNotes);
		}
	}

	public static String domainName(final String pProjectCode)
	{
		return DeliveryDomain.DOMAIN_PREFIX + pProjectCode;
	}

	/** Dataspace ids of staging elements carry EBX's own {@code B} prefix ({@code BCompanies_Staging}). */
	public static String dataspaceId(final String pDataspaceKey)
	{
		return "B" + pDataspaceKey;
	}

	/** Component names: letters, digits and underscore, so the archive's folder names stay readable. */
	static String safeName(final String pText)
	{
		final StringBuilder out = new StringBuilder();
		for (final char c : pText.toCharArray())
		{
			out.append(Character.isLetterOrDigit(c) && c < 128 ? c : '_');
		}
		return out.toString();
	}

	public static Result compose(final Project pProject, final List<Source> pSources, final List<Matching> pMatching, final Locations pLocations)
	{
		return DeliveryDomain.compose(pProject, pSources, pMatching, pLocations, Collections.<String> emptyList());
	}

	/**
	 * @param pLibraries the script libraries the delivered models' constraints import, closed over their own
	 *        imports ({@link ScriptImports#closure}); they go FIRST, as sources - the target must publish them
	 *        before the model publications import (measured 2026-09-22, {@code docs/DELIVERY.md} sec 6.5)
	 */
	public static Result compose(final Project pProject, final List<Source> pSources, final List<Matching> pMatching, final Locations pLocations,
		final List<String> pLibraries)
	{
		final List<Component> components = new ArrayList<Component>();
		final List<String> refused = new ArrayList<String>();
		final List<String> notes = new ArrayList<String>();

		// script libraries the models' constraints import: sources only, first
		for (final String library : pLibraries)
		{
			components.add(new Component(DeliveryDomain.TYPE_SCRIPT_SOURCE, ScriptImports.stagingElementId(library), Collections.<String, String> emptyMap(),
				"library_" + DeliveryDomain.safeName(library)));
		}
		if (!pLibraries.isEmpty())
		{
			notes.add("Script libraries travel as sources only (" + String.join(", ", pLibraries)
				+ "): a library publication is not a staging element, so the target must publish them (IDE or scripting MCP) after importing them and before importing the model publications.");
		}

		// landing: one dataspace for the project, one model/publication/dataset per source
		int landingDelivered = 0;
		for (final Source source : pSources)
		{
			if (!source.modelDone || !source.datasetDone)
			{
				refused.add("landing " + source.sourceSystem + ": " + (source.modelDone ? "LANDING_DATASET" : "LANDING_MODEL") + " is not done");
				continue;
			}
			if (landingDelivered == 0)
			{
				components.add(DeliveryDomain.dataspace(pProject.landingDataspace, "landing_dataspace"));
			}
			final String name = "landing_" + DeliveryDomain.safeName(source.sourceSystem);
			components.add(DeliveryDomain.model(source.landingModel, name + "_model"));
			components.add(DeliveryDomain.publication(source.landingPublication, name + "_publication"));
			components.add(DeliveryDomain.dataset(pProject.landingDataspace, source.landingDataset, name + "_dataset"));
			landingDelivered++;
		}

		// staging
		if (pProject.stagingModelDone && pProject.stagingDatasetDone)
		{
			components.add(DeliveryDomain.dataspace(pProject.stagingDataspace, "staging_dataspace"));
			components.add(DeliveryDomain.model(pProject.stagingModel, "staging_model"));
			components.add(DeliveryDomain.publication(pProject.stagingModel, "staging_publication"));
			components.add(DeliveryDomain.dataset(pProject.stagingDataspace, pProject.stagingDataset, "staging_dataset"));
		}
		else if (pProject.stagingModelDone || pProject.stagingDatasetDone || pProject.stagingModel != null)
		{
			refused.add("staging: " + (pProject.stagingModelDone ? "STAGING_DATASET" : "STAGING_MODEL") + " is not done");
		}

		// master
		final boolean masterDelivered = pProject.masterModelDone && pProject.masterDatasetDone;
		if (masterDelivered)
		{
			components.add(DeliveryDomain.dataspace(pProject.masterDataspace, "master_dataspace"));
			components.add(DeliveryDomain.model(pProject.masterModel, "master_model"));
			components.add(DeliveryDomain.publication(pProject.masterModel, "master_publication"));
			components.add(DeliveryDomain.dataset(pProject.masterDataspace, pProject.masterDataset, "master_dataset"));
		}
		else if (pProject.masterModelDone || pProject.masterDatasetDone)
		{
			refused.add("master: " + (pProject.masterModelDone ? "MASTER_DATASET" : "MASTER_MODEL") + " is not done");
		}

		// the Factory's decision rows, whole tables (docs/DELIVERY.md sec 6.1)
		for (final String table : DeliveryDomain.FACTORY_TABLES)
		{
			components.add(DeliveryDomain.table(pLocations.factoryDataspace, pLocations.factoryDataset, table, "factory_" + table));
		}
		notes.add("The Factory's tables are delivered whole (every project of this instance): a per-project filtered table is a published view EBX has no API to create.");

		// Connectors' flows
		if (landingDelivered > 0)
		{
			if (pLocations.connectorsDataspace == null)
			{
				notes.add("Connectors is not installed here: the landing flows are not delivered.");
			}
			else
			{
				for (final String table : DeliveryDomain.CONNECTORS_TABLES)
				{
					components.add(DeliveryDomain.table(pLocations.connectorsDataspace, pLocations.connectorsDataset, table, "connectors_" + table));
				}
				notes.add("Connectors' Mapping, MappingInput and MappingRule tables are delivered whole; Connector and ConnectorProperty never are (secrets): create the connectors on the target under the same ids.");
			}
		}

		// Match and Merge configurations of the promoted tables
		for (final Matching matching : pMatching)
		{
			if (!matching.planApplied)
			{
				continue;
			}
			if (!masterDelivered)
			{
				refused.add("matching " + matching.entity + ": the master zone is not delivered");
				continue;
			}
			if (matching.configurationId == null)
			{
				refused.add("matching " + matching.entity + ": no Match and Merge configuration row found for " + matching.tablePath);
				continue;
			}
			components.add(new Component(DeliveryDomain.TYPE_MATCHING_CONFIGURATION, matching.configurationId, Collections.<String, String> emptyMap(),
				"matching_" + DeliveryDomain.safeName(matching.entity)));
		}

		return new Result(DeliveryDomain.domainName(pProject.code), components, refused, notes);
	}

	private static Component dataspace(final String pKey, final String pName)
	{
		return new Component(DeliveryDomain.TYPE_DATASPACE, DeliveryDomain.dataspaceId(pKey), Collections.<String, String> emptyMap(), pName);
	}

	private static Component model(final String pModel, final String pName)
	{
		return new Component(DeliveryDomain.TYPE_DATAMODEL, pModel, Collections.<String, String> emptyMap(), pName);
	}

	private static Component publication(final String pPublication, final String pName)
	{
		return new Component(DeliveryDomain.TYPE_PUBLICATION, pPublication, Collections.<String, String> emptyMap(), pName);
	}

	private static Component dataset(final String pDataspaceKey, final String pDataset, final String pName)
	{
		final Map<String, String> context = new LinkedHashMap<String, String>();
		context.put(DeliveryDomain.CTX_DATASPACE, DeliveryDomain.dataspaceId(pDataspaceKey));
		return new Component(DeliveryDomain.TYPE_DATASET, pDataset, context, pName);
	}

	private static Component table(final String pDataspaceKey, final String pDataset, final String pTable, final String pName)
	{
		final Map<String, String> context = new LinkedHashMap<String, String>();
		context.put(DeliveryDomain.CTX_DATASPACE, DeliveryDomain.dataspaceId(pDataspaceKey));
		context.put(DeliveryDomain.CTX_DATASET, pDataset);
		return new Component(DeliveryDomain.TYPE_DATA_TABLE, "/root/" + pTable, context, pName);
	}

	/** {@code {domainName, components:[{element:{typeName,id,context},name}], refused:[], notes:[]}} - each component is a ready request body. */
	public static ObjectNode toJson(final Result pResult)
	{
		final ObjectNode out = new ObjectMapper().createObjectNode();
		out.put("domainName", pResult.domainName);
		final ArrayNode components = out.putArray("components");
		for (final Component component : pResult.components)
		{
			final ObjectNode body = components.addObject();
			final ObjectNode element = body.putObject("element");
			element.put("typeName", component.typeName);
			element.put("id", component.id);
			final ObjectNode context = element.putObject("context");
			for (final Map.Entry<String, String> entry : component.context.entrySet())
			{
				context.put(entry.getKey(), entry.getValue());
			}
			body.put("name", component.name);
		}
		final ArrayNode refused = out.putArray("refused");
		for (final String text : pResult.refused)
		{
			refused.add(text);
		}
		final ArrayNode notes = out.putArray("notes");
		for (final String text : pResult.notes)
		{
			notes.add(text);
		}
		return out;
	}
}
