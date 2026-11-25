package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what deleting a project would actually touch, and whether it is allowed to right now - no EBX
 * type anywhere, so every rule is unit-tested without a running instance. {@code ebx.ProjectDeleter} gathers
 * the live evidence into a {@link Facts} value and hands it here; this class only ever computes and refuses,
 * never reads or writes EBX itself.
 * <p>
 * <b>Two parts, always in this order</b> (the product owner's own framing, 2026-09-23): the OPTIONAL cleanup of the
 * generated objects the caller ticked, then the deletion of the project's own rows in the factory dataset
 * (always, unconditionally - {@code ebx.ProjectDeleter} does that part, not this class). If any ticked
 * generated-object deletion fails, the caller stops before the factory-row deletion so a retry stays
 * possible - {@link #build} always recomputes from the facts it is given, never from a cache, so calling it
 * again after a partial failure sees exactly the live state.
 * <p>
 * <b>Eligibility, one rule per bullet, in the order they are actually checked</b> (an ineligible item is
 * still LISTED, with its reason, never silently dropped - the person sees the whole picture before deciding;
 * only a genuinely absent stored name drops an item from the plan entirely, since there is then nothing to
 * even describe):
 * <ol>
 * <li>a blank stored name (no dataspace/dataset/mapping was ever recorded) - the item is not generated at
 * all, so it is simply absent from {@link Plan#items}, never listed as "ineligible" for something that does
 * not exist;</li>
 * <li>a delivered project (this row was staged here by EBX Staging, not designed here) - every generated
 * item is ineligible: "remove it on the origin instance, or by hand" (same posture as {@code
 * delivery.OriginRule}: decisions are taken where the project was designed, never re-derived on a target);</li>
 * <li>a stored name that is not the exact name this factory would itself compute - ineligible: never delete
 * a hand-typed or foreign dataspace, the same rule {@code master.MasterResetEligibility} already applies to
 * a master-zone reset;</li>
 * <li>no {@code PipelineStep} evidence that the factory actually ran the step (and, for a dataspace, that it
 * CREATED it rather than merely found one already there under a matching name) - ineligible, refusing to
 * guess ownership from a name alone;</li>
 * <li>a master zone (dataspace or model) of a {@code TARGET_EXISTS} project - ineligible: that zone belongs
 * to the target instance, never the factory's to delete;</li>
 * <li>a Connectors flow (a {@code LandingTable.inboundMapping} id) whose mapping does not write into the project's own landing
 * dataspace, or could not be read to check - ineligible ("shared or foreign": two projects on the same source system once shared one
 * mapping, 2026-10-03; the id stored on a table is only a pointer, the mapping's own dataspace says whose it is);</li>
 * <li>a Connectors OUTBOUND flow of a distribution target ({@code DistributionTarget.connectorsMappingIds}; fix of 2026-10-12, the targets of a
 * deleted project and their mappings used to be left behind) - ineligible unless the mapping's label is the one {@code DistributionConfigurator}
 * writes for THAT target ({@code "Distribution: target <id> <tablePath>"}) and its dataspace, when it names one, is this project's master
 * dataspace; one that could not be read is never offered ({@link #distributionFlowReason});</li>
 * <li>(lot M18, 2026-10-18) a Match and Merge business object of the master model ({@code MATCHING_BUSINESS_OBJECT}, read from the add-on's own configuration dataset) -
 * eligible like the master zone it belongs to; planned BEFORE its members' matching configurations, which can only be deleted once their table no longer belongs to a
 * business object and while the model still exists;</li>
 * <li>a matching configuration - eligible only when its table's master-zone dataspace item is ITSELF
 * eligible and the table is actually configured (a live {@code get_matching_config} answer) - nothing to
 * delete otherwise;</li>
 * <li>(lot C, 2026-10-17) a catalogue source or a sample-file connection the factory CREATED for the project (proof: its PipelineStep rows,
 * {@link ConnectionProvenance}) - eligible only when nothing else uses it: not picked from the existing catalogue, not shared with another project,
 * not named by a data product or a mapping this plan does not remove; see {@link #catalogSourceItem} / {@link #connectionItem} and {@code
 * docs/PROJECT-DELETION.md}. Listed last: the flows that run on a connection are removed first.</li>
 * </ol>
 * <p>
 * <b>Execution order is exactly {@link Plan#items}' own order</b> - business objects, then matching configurations first (CLAUDE.md
 * &sect;8: a matching config owns a metadataset INSIDE the master dataspace; deleting the dataspace first
 * would orphan it), then Connectors flows, then dataspaces (master, staging, landing - a dataspace deletion
 * takes its own datasets with it, noted in the item's own {@link Item#detail}), then data models (master,
 * staging, landing per source). {@link #validate} additionally refuses {@code dataspaces} without {@code
 * matching} when the plan holds an eligible, configured matching item whose master dataspace is also
 * eligible - deleting the master dataspace out from under a live matching configuration would orphan its
 * metadataset the same way, so both must go together.
 */
public final class ProjectDeletionPlan
{
	private ProjectDeletionPlan()
	{
	}

	public enum Kind
	{
		/**
		 * Lot S9 (2026-10-18): the distribution script triggers ({@code DistributionAfterCreate/AfterModify/AfterDelete}) the factory declared on the master tables for a real-time
		 * target. One item per master model, planned FIRST: they read the add-on's {@code Linked_Field_State} on EVERY table that carries it (members included), so deleting any
		 * table's matching configuration while they exist makes the model fail to recompile, and every configuration deleted after that answers "Table not found".
		 */
		DISTRIBUTION_TRIGGERS,
		/**
		 * Lot M18 (2026-10-18): the Match and Merge business object of the master model (a main table and its members) - its configuration row in the add-on's dataset and
		 * the links of the member table configurations to it. Planned BEFORE the {@link #MATCHING_CONFIG} items: a member's configuration can only be deleted once the table
		 * no longer belongs to a business object, and only while the master model still exists.
		 */
		MATCHING_BUSINESS_OBJECT, MATCHING_CONFIG, LANDING_FLOW, DISTRIBUTION_FLOW, MASTER_DATASPACE, STAGING_DATASPACE, LANDING_DATASPACE, MASTER_MODEL, STAGING_MODEL, LANDING_MODEL,
		/** Lot C (2026-10-17): a catalogue source the factory created for the project (with its assets, columns and catalogue flows). */
		CATALOG_SOURCE,
		/** Lot C (2026-10-17): a sample-file connection the factory created for the project. */
		CONNECTION
	}

	/** The five checkboxes offered to the user; every {@link Kind} belongs to exactly one, see {@link #optionOf}. */
	public static final String OPTION_MATCHING = "matching";
	public static final String OPTION_FLOWS = "flows";
	public static final String OPTION_DATASPACES = "dataspaces";
	public static final String OPTION_MODELS = "models";
	/** Lot C (2026-10-17): the sample-file connections and the catalogue sources the factory created for the project. */
	public static final String OPTION_CONNECTIONS = "connections";
	public static final String[] OPTIONS = { ProjectDeletionPlan.OPTION_MATCHING, ProjectDeletionPlan.OPTION_FLOWS, ProjectDeletionPlan.OPTION_DATASPACES,
		ProjectDeletionPlan.OPTION_MODELS, ProjectDeletionPlan.OPTION_CONNECTIONS };

	public static String optionOf(final Kind pKind)
	{
		switch (pKind)
		{
			case DISTRIBUTION_TRIGGERS:
			case MATCHING_BUSINESS_OBJECT:
			case MATCHING_CONFIG:
				return ProjectDeletionPlan.OPTION_MATCHING;
			case LANDING_FLOW:
			case DISTRIBUTION_FLOW:
				return ProjectDeletionPlan.OPTION_FLOWS;
			case MASTER_DATASPACE:
			case STAGING_DATASPACE:
			case LANDING_DATASPACE:
				return ProjectDeletionPlan.OPTION_DATASPACES;
			case CATALOG_SOURCE:
			case CONNECTION:
				return ProjectDeletionPlan.OPTION_CONNECTIONS;
			default:
				return ProjectDeletionPlan.OPTION_MODELS;
		}
	}

	private static boolean isKnownOption(final String pOption)
	{
		for (final String option : ProjectDeletionPlan.OPTIONS)
		{
			if (option.equals(pOption))
			{
				return true;
			}
		}
		return false;
	}

	// =================================================================================================== input facts

	/** One {@code ProjectSource} of the project - the landing zone is per source system, everything else is per project. */
	public static final class SourceFacts
	{
		public final String sourceSystem;
		/** {@code ProjectSource.landingModel}; blank means this source never reached {@code LANDING_MODEL}. */
		public final String landingModel;
		/** {@code ProjectSource.landingDataset}; blank means this source never reached {@code LANDING_DATASET}. */
		public final String landingDataset;
		/** A DONE {@code LANDING_MODEL} step of this source. */
		public final boolean landingModelStepDone;
		/** A DONE {@code LANDING_DATASET} step of this source whose own {@code detail} JSON recorded {@code dataspace.created == true} - see the class Javadoc of {@code ebx.LandingGenerator.runDatasetStep}. */
		public final boolean landingDatasetCreatedByFactory;
		/** Non-blank {@code LandingTable.inboundMapping} ids of this source - the Connectors flow mappings a {@code flows} deletion would remove. */
		public final List<String> inboundMappingIds;
		/**
		 * Fix of 2026-10-03: the dataspace each {@link #inboundMappingIds} mapping WRITES INTO, as {@code connectors_mapping_get} reports it
		 * - or {@link #MAPPING_GONE} / {@link #MAPPING_UNREADABLE}. A mapping is the project's own only when that dataspace is its
		 * landing dataspace: two projects reading the same source system once shared one mapping, and deleting either project
		 * would have deleted the other's flow. An id missing from the map was not checked (a caller that predates this evidence): treated as eligible.
		 */
		public final Map<String, String> inboundMappingDataspaces;

		/** {@link #inboundMappingDataspaces} value: the mapping no longer exists, so there is nothing to protect (the deletion reports it absent). */
		public static final String MAPPING_GONE = "(gone)";
		/** {@link #inboundMappingDataspaces} value: the mapping could not be read, so its ownership is unknown and it is not offered for deletion. */
		public static final String MAPPING_UNREADABLE = "(unreadable)";

		public SourceFacts(final String pSourceSystem, final String pLandingModel, final String pLandingDataset, final boolean pLandingModelStepDone,
			final boolean pLandingDatasetCreatedByFactory, final List<String> pInboundMappingIds)
		{
			this(pSourceSystem, pLandingModel, pLandingDataset, pLandingModelStepDone, pLandingDatasetCreatedByFactory, pInboundMappingIds, new LinkedHashMap<String, String>());
		}

		public SourceFacts(final String pSourceSystem, final String pLandingModel, final String pLandingDataset, final boolean pLandingModelStepDone,
			final boolean pLandingDatasetCreatedByFactory, final List<String> pInboundMappingIds, final Map<String, String> pInboundMappingDataspaces)
		{
			this.sourceSystem = pSourceSystem;
			this.landingModel = pLandingModel;
			this.landingDataset = pLandingDataset;
			this.landingModelStepDone = pLandingModelStepDone;
			this.landingDatasetCreatedByFactory = pLandingDatasetCreatedByFactory;
			this.inboundMappingIds = pInboundMappingIds;
			this.inboundMappingDataspaces = pInboundMappingDataspaces == null ? new LinkedHashMap<String, String>() : pInboundMappingDataspaces;
		}
	}

	/**
	 * Why {@code pMappingId} must NOT be deleted as a flow of the project whose landing dataspace is {@code pLandingDataspace}, or
	 * {@code null} when it may: it writes into another dataspace (shared with, or taken over by, another project - or edited by hand),
	 * or it could not be read to find out. A mapping that is gone, or whose dataspace was not collected, passes. Shared by the project
	 * deletion plan and {@code StepResetPlan}.
	 */
	public static String foreignFlowReason(final String pLandingDataspace, final SourceFacts pSource, final String pMappingId)
	{
		final String dataspace = pSource.inboundMappingDataspaces.get(pMappingId);
		if (dataspace == null || SourceFacts.MAPPING_GONE.equals(dataspace))
		{
			return null;
		}
		if (SourceFacts.MAPPING_UNREADABLE.equals(dataspace))
		{
			return "mapping " + pMappingId + " could not be read from Connectors, so it cannot be shown to belong to this project: not deleted.";
		}
		if (!dataspace.equals(pLandingDataspace == null ? "" : pLandingDataspace.trim()))
		{
			return "shared or foreign: mapping " + pMappingId + " writes into dataspace " + dataspace + ", not this project's landing dataspace "
				+ (blank(pLandingDataspace) ? "(none)" : pLandingDataspace) + " - it belongs to another project, not deleted.";
		}
		return null;
	}

	/** One PROMOTED master entity's live matching state - only ever built when the project has a master dataspace at all. */
	public static final class MatchingFacts
	{
		public final String entityName;
		public final String tablePath;
		/** The live answer of {@code get_matching_config}: {@code true} on a found configuration, {@code false} on {@code CONFIG_NOT_FOUND}/{@code TABLE_NOT_FOUND}. */
		public final boolean configured;

		public MatchingFacts(final String pEntityName, final String pTablePath, final boolean pConfigured)
		{
			this.entityName = pEntityName;
			this.tablePath = pTablePath;
			this.configured = pConfigured;
		}
	}

	/** One OUTBOUND mapping a {@code DistributionTarget} sends through, with the evidence of whose it is (read from Connectors by the caller). */
	public static final class TargetFlowFacts
	{
		/** The mapping was read: {@link #label} and {@link #dataspace} are what Connectors answered. */
		public static final String STATE_READ = "READ";
		/** The mapping no longer exists, so there is nothing to protect (the deletion reports it absent). */
		public static final String STATE_GONE = "GONE";
		/** The mapping could not be read, so its ownership is unknown and it is not offered for deletion. */
		public static final String STATE_UNREADABLE = "UNREADABLE";

		public final String mappingId;
		public final String tablePath;
		public final String state;
		/** The mapping's label, {@code null} unless {@link #STATE_READ}. */
		public final String label;
		/** The dataspace the mapping reads from, {@code null} when it names none (a mapping written before the dataspace was recorded) or is not {@link #STATE_READ}. */
		public final String dataspace;

		public TargetFlowFacts(final String pMappingId, final String pTablePath, final String pState, final String pLabel, final String pDataspace)
		{
			this.mappingId = pMappingId;
			this.tablePath = pTablePath;
			this.state = pState;
			this.label = pLabel;
			this.dataspace = pDataspace;
		}
	}

	/** One {@code DistributionTarget} row of the project and the OUTBOUND mappings its {@code connectorsMappingIds} names. */
	public static final class TargetFacts
	{
		public final String targetId;
		public final List<TargetFlowFacts> flows;

		public TargetFacts(final String pTargetId, final List<TargetFlowFacts> pFlows)
		{
			this.targetId = pTargetId;
			this.flows = pFlows;
		}
	}

	/** The label {@code ebx.DistributionConfigurator.label(targetId, tablePath)} gives the outbound mapping of a target's table, without the table path: the ownership marker. */
	public static String distributionLabelPrefix(final String pTargetId)
	{
		return "Distribution: target " + pTargetId + " ";
	}

	/**
	 * Why the OUTBOUND mapping {@code pFlow} of target {@code pTargetId} must NOT be deleted with the project whose master dataspace is
	 * {@code pMasterDataspace}, or {@code null} when it may. A mapping that is gone passes (the deletion reports it absent); one that could not
	 * be read, whose label is not this target's, or whose dataspace names another dataspace than the project's master one is refused.
	 * A mapping that names no dataspace passes on its label alone.
	 */
	public static String distributionFlowReason(final String pMasterDataspace, final String pTargetId, final TargetFlowFacts pFlow)
	{
		if (TargetFlowFacts.STATE_GONE.equals(pFlow.state))
		{
			return null;
		}
		if (!TargetFlowFacts.STATE_READ.equals(pFlow.state))
		{
			return "mapping " + pFlow.mappingId + " could not be read from Connectors, so it cannot be shown to belong to this project: not deleted.";
		}
		final String prefix = ProjectDeletionPlan.distributionLabelPrefix(pTargetId);
		if (pFlow.label == null || !pFlow.label.startsWith(prefix))
		{
			return "foreign: mapping " + pFlow.mappingId + " is labelled \"" + (pFlow.label == null ? "" : pFlow.label) + "\", not \"" + prefix
				+ "...\" - it is not the outbound flow of distribution target " + pTargetId + ", not deleted.";
		}
		if (!blank(pFlow.dataspace) && !pFlow.dataspace.trim().equals(pMasterDataspace == null ? "" : pMasterDataspace.trim()))
		{
			return "shared or foreign: mapping " + pFlow.mappingId + " reads dataspace " + pFlow.dataspace + ", not this project's master dataspace "
				+ (blank(pMasterDataspace) ? "(none)" : pMasterDataspace) + " - it belongs to another project, not deleted.";
		}
		return null;
	}

	/** A Connectors mapping: its id (the primary key) and its label. */
	public static final class MappingRef
	{
		public final String id;
		public final String label;

		public MappingRef(final String pId, final String pLabel)
		{
			this.id = pId;
			this.label = pLabel == null ? "" : pLabel;
		}
	}

	/**
	 * Lot C (2026-10-17): one Connectors connection the factory has a trace of for the project, with the catalogue source it created on it - what a
	 * deletion needs to decide whether the factory may remove them. The evidence ({@code ...CreatedByFactory}, {@code connectionStepSeen}) comes from the
	 * project's own PipelineStep rows ({@link ConnectionProvenance}); everything else is read live by {@code ebx.ConnectionCleanup}. Plain mutable fields:
	 * the gathering class fills them, the plan only reads them.
	 */
	public static final class ConnectionFacts
	{
		public final String connectionId;
		/** A {@code SOURCE_CONNECTION} step of the project names this connection (written when the sample file was imported), whether it created the row or found it. */
		public boolean connectionStepSeen;
		/** That step recorded {@code created: true}: the factory wrote the Connectors row. */
		public boolean connectionCreatedByFactory;
		/** The Connectors row exists now. */
		public boolean connectionExists;
		/** Its type, {@code ""} when it does not exist. */
		public String connectionType = "";
		/** The catalogue source a {@code SOURCES_RUN} step of the project recorded as CREATED by the factory ({@code connectors_catalog_source} answered CREATE), {@code ""} when none. */
		public String catalogSourceId = "";
		public boolean catalogSourceCreatedByFactory;
		/** The Source row exists now. */
		public boolean catalogSourceExists;
		/** What the Source row names as its connection now ({@code ""} when it does not exist): if it is not {@link #connectionId}, somebody re-pointed it. */
		public String catalogSourceConnector = "";
		/** Codes of the OTHER projects whose application uses this connection or this catalogue source. */
		public List<String> otherProjects = new ArrayList<String>();
		/** Catalogue sources other than {@link #catalogSourceId} that name this connection. */
		public List<String> otherSourceIds = new ArrayList<String>();
		/** The mappings of the connection that are the catalogue source's own flows (label {@code Catalog <kind>: <source id>}): removed with the source. */
		public List<MappingRef> catalogFlows = new ArrayList<MappingRef>();
		/** Every other mapping on the connection (the project's landing / distribution flows, or somebody else's). */
		public List<MappingRef> otherMappings = new ArrayList<MappingRef>();
		/** Catalogue objects that still use the source or its assets and are NOT removed with it (data product, access request, workflow, output port, product field): one sentence each. */
		public List<String> catalogBlockers = new ArrayList<String>();
		/** Connectors rows that reference the connection and are NOT removed with it (publication, conflict, default LLM of a right): one sentence each. */
		public List<String> connectionBlockers = new ArrayList<String>();
		public int assets;
		public int columns;

		public ConnectionFacts(final String pConnectionId)
		{
			this.connectionId = pConnectionId;
		}
	}

	public static final class Facts
	{
		public final String projectCode;
		/** {@code GREENFIELD}/{@code TARGET_EXISTS}. */
		public final String mode;
		/** {@code delivery.OriginRule.isDelivered}. */
		public final boolean delivered;

		public final String landingDataspaceStored;
		public final String landingDataspaceComputed;

		public final String stagingDataspaceStored;
		public final String stagingDatasetStored;
		public final String stagingDataspaceComputed;
		public final String stagingDatasetComputed;
		public final boolean stagingModelStepDone;
		/** A DONE {@code STAGING_DATASET} step (scope {@code ""}) whose detail recorded {@code dataspace.created == true}. */
		public final boolean stagingDatasetCreatedByFactory;

		public final String masterDataspaceStored;
		public final String masterDatasetStored;
		public final String masterDataspaceComputed;
		public final String masterDatasetComputed;
		public final boolean masterModelStepDone;
		/** A DONE {@code MASTER_DATASET} step (scope {@code ""}) whose detail recorded {@code dataspace.created == true}. */
		public final boolean masterDatasetCreatedByFactory;

		public final List<SourceFacts> sources;
		public final List<MatchingFacts> matching;
		/** The {@code DistributionTarget} rows of the project and their outbound mappings; empty for a caller that predates them (a step reset, which never touches targets). */
		public final List<TargetFacts> targets;
		/** Lot C (2026-10-17): the connections the factory created (or found) for the project and the catalogue sources it created on them; empty for a caller that predates them (a step reset). */
		public final List<ConnectionFacts> connections;
		/**
		 * Lot M18 (2026-10-18): the Match and Merge business objects of the master publication, read from the add-on's own configuration dataset (rows whose {@code dataModel}
		 * is {@code urn:ebx:publication:<master model>}); empty when there is none, when the add-on has no dataset, or for a caller that predates them (a step reset).
		 * Set after construction by the caller that reads them ({@code ebx.ProjectDeleter#gather}), so no constructor changes.
		 */
		public List<MatchingConfigRows.BusinessObjectFacts> businessObjects = new ArrayList<MatchingConfigRows.BusinessObjectFacts>();
		/**
		 * Lot S9 (2026-10-18): the master table paths the distribution triggers were (or may have been) declared on - the promoted master tables of the project, only when a
		 * {@code DISTRIBUTION_TRIGGER_INSTALL} step was ever recorded for it; empty otherwise. Set after construction by {@code ebx.ProjectDeleter#gather}, like {@link #businessObjects}.
		 */
		public List<String> distributionTriggerTables = new ArrayList<String>();

		/**
		 * The data model names {@code MASTER_MODEL}/{@code STAGING_MODEL} would delete - computed by the caller
		 * ({@code master.MasterNames.modelName}/{@code staging.StagingNames.modelName}, a fixed pattern the
		 * project's own record never stores) rather than re-derived here, so this class does not need to depend
		 * on those packages' naming conventions to stay pure and independently testable. The per-source landing
		 * model name has no such field: {@link SourceFacts#landingModel} (the STORED value) is used directly, it
		 * already is the real published name.
		 */
		public final String masterModelName;
		public final String stagingModelName;

		/** Rows the factory-row deletion (always run, never optional) will delete, per table, in deletion order - carried through to {@link Plan#rowCounts} unchanged. */
		public final Map<String, Integer> rowCounts;

		public Facts(final String pProjectCode, final String pMode, final boolean pDelivered, final String pLandingDataspaceStored,
			final String pLandingDataspaceComputed, final String pStagingDataspaceStored, final String pStagingDatasetStored, final String pStagingDataspaceComputed,
			final String pStagingDatasetComputed, final boolean pStagingModelStepDone, final boolean pStagingDatasetCreatedByFactory, final String pMasterDataspaceStored,
			final String pMasterDatasetStored, final String pMasterDataspaceComputed, final String pMasterDatasetComputed, final boolean pMasterModelStepDone,
			final boolean pMasterDatasetCreatedByFactory, final List<SourceFacts> pSources, final List<MatchingFacts> pMatching, final String pMasterModelName,
			final String pStagingModelName, final Map<String, Integer> pRowCounts)
		{
			this(pProjectCode, pMode, pDelivered, pLandingDataspaceStored, pLandingDataspaceComputed, pStagingDataspaceStored, pStagingDatasetStored, pStagingDataspaceComputed,
				pStagingDatasetComputed, pStagingModelStepDone, pStagingDatasetCreatedByFactory, pMasterDataspaceStored, pMasterDatasetStored, pMasterDataspaceComputed,
				pMasterDatasetComputed, pMasterModelStepDone, pMasterDatasetCreatedByFactory, pSources, pMatching, new ArrayList<TargetFacts>(), pMasterModelName,
				pStagingModelName, pRowCounts);
		}

		public Facts(final String pProjectCode, final String pMode, final boolean pDelivered, final String pLandingDataspaceStored,
			final String pLandingDataspaceComputed, final String pStagingDataspaceStored, final String pStagingDatasetStored, final String pStagingDataspaceComputed,
			final String pStagingDatasetComputed, final boolean pStagingModelStepDone, final boolean pStagingDatasetCreatedByFactory, final String pMasterDataspaceStored,
			final String pMasterDatasetStored, final String pMasterDataspaceComputed, final String pMasterDatasetComputed, final boolean pMasterModelStepDone,
			final boolean pMasterDatasetCreatedByFactory, final List<SourceFacts> pSources, final List<MatchingFacts> pMatching, final List<TargetFacts> pTargets,
			final String pMasterModelName, final String pStagingModelName, final Map<String, Integer> pRowCounts)
		{
			this(pProjectCode, pMode, pDelivered, pLandingDataspaceStored, pLandingDataspaceComputed, pStagingDataspaceStored, pStagingDatasetStored, pStagingDataspaceComputed,
				pStagingDatasetComputed, pStagingModelStepDone, pStagingDatasetCreatedByFactory, pMasterDataspaceStored, pMasterDatasetStored, pMasterDataspaceComputed,
				pMasterDatasetComputed, pMasterModelStepDone, pMasterDatasetCreatedByFactory, pSources, pMatching, pTargets, new ArrayList<ConnectionFacts>(), pMasterModelName,
				pStagingModelName, pRowCounts);
		}

		public Facts(final String pProjectCode, final String pMode, final boolean pDelivered, final String pLandingDataspaceStored,
			final String pLandingDataspaceComputed, final String pStagingDataspaceStored, final String pStagingDatasetStored, final String pStagingDataspaceComputed,
			final String pStagingDatasetComputed, final boolean pStagingModelStepDone, final boolean pStagingDatasetCreatedByFactory, final String pMasterDataspaceStored,
			final String pMasterDatasetStored, final String pMasterDataspaceComputed, final String pMasterDatasetComputed, final boolean pMasterModelStepDone,
			final boolean pMasterDatasetCreatedByFactory, final List<SourceFacts> pSources, final List<MatchingFacts> pMatching, final List<TargetFacts> pTargets,
			final List<ConnectionFacts> pConnections, final String pMasterModelName, final String pStagingModelName, final Map<String, Integer> pRowCounts)
		{
			this.projectCode = pProjectCode;
			this.mode = pMode;
			this.delivered = pDelivered;
			this.landingDataspaceStored = pLandingDataspaceStored;
			this.landingDataspaceComputed = pLandingDataspaceComputed;
			this.stagingDataspaceStored = pStagingDataspaceStored;
			this.stagingDatasetStored = pStagingDatasetStored;
			this.stagingDataspaceComputed = pStagingDataspaceComputed;
			this.stagingDatasetComputed = pStagingDatasetComputed;
			this.stagingModelStepDone = pStagingModelStepDone;
			this.stagingDatasetCreatedByFactory = pStagingDatasetCreatedByFactory;
			this.masterDataspaceStored = pMasterDataspaceStored;
			this.masterDatasetStored = pMasterDatasetStored;
			this.masterDataspaceComputed = pMasterDataspaceComputed;
			this.masterDatasetComputed = pMasterDatasetComputed;
			this.masterModelStepDone = pMasterModelStepDone;
			this.masterDatasetCreatedByFactory = pMasterDatasetCreatedByFactory;
			this.sources = pSources;
			this.matching = pMatching;
			this.targets = pTargets == null ? new ArrayList<TargetFacts>() : pTargets;
			this.connections = pConnections == null ? new ArrayList<ConnectionFacts>() : pConnections;
			this.masterModelName = pMasterModelName;
			this.stagingModelName = pStagingModelName;
			this.rowCounts = pRowCounts;
		}
	}

	// ========================================================================================================== plan

	public static final class Item
	{
		public final Kind kind;
		public final String option;
		public final String name;
		public final String detail;
		public final boolean eligible;
		/** Non-null only when {@link #eligible} is false. */
		public final String reason;
		/**
		 * Lot C (2026-10-17): the ids of the Connectors flows (LANDING_FLOW / DISTRIBUTION_FLOW items of this plan) that still use this connection and are removed by
		 * the {@code flows} option - the connection can only go once they have: {@link #validate} refuses {@code connections} without {@code flows} then.
		 * Empty for every other item.
		 */
		public final List<String> needsFlows;

		Item(final Kind pKind, final String pName, final String pDetail, final boolean pEligible, final String pReason)
		{
			this(pKind, pName, pDetail, pEligible, pReason, new ArrayList<String>());
		}

		Item(final Kind pKind, final String pName, final String pDetail, final boolean pEligible, final String pReason, final List<String> pNeedsFlows)
		{
			this.needsFlows = pNeedsFlows;
			this.kind = pKind;
			this.option = ProjectDeletionPlan.optionOf(pKind);
			this.name = pName;
			this.detail = pDetail;
			this.eligible = pEligible;
			this.reason = pReason;
		}
	}

	public static final class Plan
	{
		public final String projectCode;
		public final List<Item> items;
		public final Map<String, Integer> rowCounts;

		Plan(final String pProjectCode, final List<Item> pItems, final Map<String, Integer> pRowCounts)
		{
			this.projectCode = pProjectCode;
			this.items = pItems;
			this.rowCounts = pRowCounts;
		}

		public List<Item> eligibleOf(final String pOption)
		{
			final List<Item> out = new ArrayList<Item>();
			for (final Item item : this.items)
			{
				if (item.eligible && pOption.equals(item.option))
				{
					out.add(item);
				}
			}
			return out;
		}

		public List<Item> allOf(final String pOption)
		{
			final List<Item> out = new ArrayList<Item>();
			for (final Item item : this.items)
			{
				if (pOption.equals(item.option))
				{
					out.add(item);
				}
			}
			return out;
		}
	}

	/**
	 * The factory's own tables a project deletion removes in ONE transaction, in deletion order (audit F3-10 / F3-103, 2026-11-12): every table comes before the
	 * tables its foreign keys point to, because EBX deletes a referenced row without a word and leaves the referrer pointing at nothing (CLAUDE.md section 9).
	 * {@code SourceDrift} points to {@code LandingTable}; {@code AgentProposal} to {@code AgentRun} and {@code AgentDefinition}; {@code AgentRun} to
	 * {@code AgentDefinition}; {@code AgentDefinition} to {@code Project} (deleted last by the caller). The volume tables ({@code Xref}, {@code TransferReject},
	 * {@code DistributionEvent}, {@code IntakeChain}, {@code DomainObject}, {@code Difference}, {@code ProjectPack}) are batched before this list and are not in it.
	 */
	public static final List<String> FACTORY_ROW_ORDER = java.util.Collections.unmodifiableList(java.util.Arrays.asList("MappingStep", "AttributeMapping", "QualityRule",
		"CapabilitySuggestion", "MatchPlan", "TransferFeed", "StagingAttribute", "StagingEntity", "SourceDrift", "LandingColumn", "LandingRelation", "LandingTable",
		"ProjectSource", "AgentStep", "AgentProposal", "AgentRun", "AgentDefinition", "PipelineStep", "LoadPause", "DistributionTarget"));

	/** The same rows, keyed by table, put in {@link #FACTORY_ROW_ORDER}; a table missing from the input is absent from the output, an unknown one is a programming error. */
	public static <T> Map<String, T> inDeletionOrder(final Map<String, T> pByTable)
	{
		for (final String name : pByTable.keySet())
		{
			if (!ProjectDeletionPlan.FACTORY_ROW_ORDER.contains(name))
			{
				throw new IllegalArgumentException("Table " + name + " is not in the project deletion order");
			}
		}
		final Map<String, T> out = new LinkedHashMap<String, T>();
		for (final String name : ProjectDeletionPlan.FACTORY_ROW_ORDER)
		{
			if (pByTable.containsKey(name))
			{
				out.put(name, pByTable.get(name));
			}
		}
		return out;
	}

	/** The one place every eligibility rule in the class Javadoc is actually applied, in the execution order {@link Plan#items} is then read in. */
	public static Plan build(final Facts pFacts)
	{
		final List<Item> items = new ArrayList<Item>();

		// 1. matching configurations - depend on the master dataspace item below, so that item is evaluated first
		// (read-only, not added to the list yet) and reused both here and where it IS added, further down.
		// Lot M18: the business objects come FIRST - a member's configuration can only be deleted once its table is no longer part of one.
		final Eligibility masterDataspaceEligibility = ProjectDeletionPlan.dataspaceEligibility(pFacts.delivered, pFacts.masterDataspaceStored,
			pFacts.masterDataspaceComputed, pFacts.masterModelStepDone && pFacts.masterDatasetCreatedByFactory, "TARGET_EXISTS".equals(pFacts.mode),
			"the master zone belongs to the target instance (TARGET_EXISTS project): not the factory's to delete");
		if (!ProjectDeletionPlan.blank(pFacts.masterDataspaceStored))
		{
			// lot S9: the distribution triggers first - nothing after them can then break the master model's compile
			if (!pFacts.distributionTriggerTables.isEmpty())
			{
				items.add(ProjectDeletionPlan.distributionTriggersItem(pFacts, masterDataspaceEligibility));
			}
			for (final MatchingConfigRows.BusinessObjectFacts businessObject : pFacts.businessObjects)
			{
				items.add(ProjectDeletionPlan.businessObjectItem(businessObject, masterDataspaceEligibility));
			}
			for (final MatchingFacts matching : ProjectDeletionPlan.matchingInDeletionOrder(pFacts.matching, pFacts.businessObjects))
			{
				items.add(ProjectDeletionPlan.matchingItem(matching, masterDataspaceEligibility));
			}
		}

		// 2. Connectors flows - one per non-blank inboundMapping id, whatever source it belongs to
		for (final SourceFacts source : pFacts.sources)
		{
			for (final String mappingId : source.inboundMappingIds)
			{
				items.add(ProjectDeletionPlan.flowItem(source, mappingId, pFacts.delivered, pFacts.landingDataspaceStored));
			}
		}

		// 2b. Connectors OUTBOUND flows of the distribution targets - one per mapping a target's connectorsMappingIds names
		for (final TargetFacts target : pFacts.targets)
		{
			for (final TargetFlowFacts flow : target.flows)
			{
				items.add(ProjectDeletionPlan.distributionFlowItem(target, flow, pFacts.delivered, pFacts.masterDataspaceStored));
			}
		}

		// 3. dataspaces - master, then staging, then landing (execution order: master's metadataset dependency is
		// already handled by step 1 running first; staging/landing have no such dependency between themselves)
		if (!ProjectDeletionPlan.blank(pFacts.masterDataspaceStored))
		{
			items.add(new Item(Kind.MASTER_DATASPACE, pFacts.masterDataspaceStored,
				"Dataset " + pFacts.masterDatasetStored + " and its own tables go with it.", masterDataspaceEligibility.eligible, masterDataspaceEligibility.reason));
		}
		if (!ProjectDeletionPlan.blank(pFacts.stagingDataspaceStored))
		{
			final Eligibility e = ProjectDeletionPlan.dataspaceEligibility(pFacts.delivered, pFacts.stagingDataspaceStored, pFacts.stagingDataspaceComputed,
				pFacts.stagingModelStepDone && pFacts.stagingDatasetCreatedByFactory, false, null);
			items.add(new Item(Kind.STAGING_DATASPACE, pFacts.stagingDataspaceStored, "Dataset " + pFacts.stagingDatasetStored + " and its own tables go with it.",
				e.eligible, e.reason));
		}
		if (!ProjectDeletionPlan.blank(pFacts.landingDataspaceStored))
		{
			final boolean anyCreatedByFactory = ProjectDeletionPlan.anyLandingDatasetCreatedByFactory(pFacts.sources);
			final Eligibility e = ProjectDeletionPlan.dataspaceEligibility(pFacts.delivered, pFacts.landingDataspaceStored, pFacts.landingDataspaceComputed,
				anyCreatedByFactory, false, null);
			items.add(new Item(Kind.LANDING_DATASPACE, pFacts.landingDataspaceStored,
				"Shared by every source of this project; its per-source datasets go with it.", e.eligible, e.reason));
		}

		// 4. data models - master, staging, landing per source (present only when the matching zone/source exists at all)
		if (!ProjectDeletionPlan.blank(pFacts.masterDataspaceStored))
		{
			final Eligibility e = ProjectDeletionPlan.modelEligibility(pFacts.delivered, pFacts.masterModelStepDone, "TARGET_EXISTS".equals(pFacts.mode),
				"the master zone belongs to the target instance (TARGET_EXISTS project): not the factory's to delete");
			items.add(new Item(Kind.MASTER_MODEL, pFacts.masterModelName, "The published data model, not the dataspace/dataset above.", e.eligible, e.reason));
		}
		if (!ProjectDeletionPlan.blank(pFacts.stagingDataspaceStored))
		{
			final Eligibility e = ProjectDeletionPlan.modelEligibility(pFacts.delivered, pFacts.stagingModelStepDone, false, null);
			items.add(new Item(Kind.STAGING_MODEL, pFacts.stagingModelName, "The published data model, not the dataspace/dataset above.", e.eligible, e.reason));
		}
		for (final SourceFacts source : pFacts.sources)
		{
			if (ProjectDeletionPlan.blank(source.landingModel))
			{
				continue;
			}
			final Eligibility e = ProjectDeletionPlan.modelEligibility(pFacts.delivered, source.landingModelStepDone, false, null);
			items.add(new Item(Kind.LANDING_MODEL, source.landingModel, "Landing model of source " + source.sourceSystem + ".", e.eligible, e.reason));
		}

		// 5. (lot C, 2026-10-17) the catalogue sources the factory created for the project, then the connections it created - last: a catalogue source's own flows
		// and the project's landing flows (step 2) must be gone before the connection they run on can be
		for (final ConnectionFacts connection : pFacts.connections)
		{
			if (connection.catalogSourceCreatedByFactory && !ProjectDeletionPlan.blank(connection.catalogSourceId))
			{
				items.add(ProjectDeletionPlan.catalogSourceItem(connection, pFacts.delivered));
			}
		}
		for (final ConnectionFacts connection : pFacts.connections)
		{
			if (connection.connectionStepSeen)
			{
				items.add(ProjectDeletionPlan.connectionItem(connection, pFacts.delivered, items));
			}
		}

		return new Plan(pFacts.projectCode, items, pFacts.rowCounts == null ? new LinkedHashMap<String, Integer>() : pFacts.rowCounts);
	}

	/**
	 * Lot S9 (2026-10-18 22:17, live): the order the {@code MATCHING_CONFIG} items run in, after the business-object items. Every table that is NOT the main table of a business
	 * object goes first, in path order; the main tables go last (path order too). Deleting the main table's configuration removes its {@code Linked_Field_*} fields, which the
	 * distribution triggers of a real-time target read: the master model then fails to recompile ("Cannot resolve symbol 'Linked_Field_State'"), the add-on resolves no table any
	 * more and the configurations deleted after that one stay behind as orphans. Pure; {@code pMatching} is not modified.
	 */
	static List<MatchingFacts> matchingInDeletionOrder(final List<MatchingFacts> pMatching, final List<MatchingConfigRows.BusinessObjectFacts> pBusinessObjects)
	{
		final java.util.Set<String> mains = new java.util.HashSet<String>();
		for (final MatchingConfigRows.BusinessObjectFacts businessObject : pBusinessObjects)
		{
			mains.add(businessObject.mainTablePath);
		}
		final List<MatchingFacts> others = new ArrayList<MatchingFacts>();
		final List<MatchingFacts> mainTables = new ArrayList<MatchingFacts>();
		for (final MatchingFacts matching : pMatching)
		{
			(mains.contains(matching.tablePath) ? mainTables : others).add(matching);
		}
		final java.util.Comparator<MatchingFacts> byPath = new java.util.Comparator<MatchingFacts>()
		{
			@Override
			public int compare(final MatchingFacts pA, final MatchingFacts pB)
			{
				return pA.tablePath.compareTo(pB.tablePath);
			}
		};
		java.util.Collections.sort(others, byPath);
		java.util.Collections.sort(mainTables, byPath);
		others.addAll(mainTables);
		return others;
	}

	private static Item distributionTriggersItem(final Facts pFacts, final Eligibility pMasterDataspaceEligibility)
	{
		final StringBuilder tables = new StringBuilder();
		for (final String table : pFacts.distributionTriggerTables)
		{
			tables.append(tables.length() == 0 ? "" : ", ").append(table);
		}
		final String detail = "Distribution script triggers on " + tables + " (removed by name, the model republished once).";
		if (!pMasterDataspaceEligibility.eligible)
		{
			return new Item(Kind.DISTRIBUTION_TRIGGERS, pFacts.masterModelName, detail, false,
				"the master zone these triggers belong to is not eligible for deletion: " + pMasterDataspaceEligibility.reason);
		}
		return new Item(Kind.DISTRIBUTION_TRIGGERS, pFacts.masterModelName, detail, true, null);
	}

	/** Lot M18: the business object of the master model, shown as its own item ({@code Business object of Client: Client, Adresse}), eligible like the master zone it belongs to. */
	private static Item businessObjectItem(final MatchingConfigRows.BusinessObjectFacts pBusinessObject, final Eligibility pMasterDataspaceEligibility)
	{
		final String detail = MatchingConfigRows.planDetail(pBusinessObject);
		if (!pMasterDataspaceEligibility.eligible)
		{
			return new Item(Kind.MATCHING_BUSINESS_OBJECT, pBusinessObject.mainTablePath, detail, false,
				"the master zone this business object belongs to is not eligible for deletion: " + pMasterDataspaceEligibility.reason);
		}
		return new Item(Kind.MATCHING_BUSINESS_OBJECT, pBusinessObject.mainTablePath, detail, true, null);
	}

	private static Item matchingItem(final MatchingFacts pMatching, final Eligibility pMasterDataspaceEligibility)
	{
		if (!pMasterDataspaceEligibility.eligible)
		{
			return new Item(Kind.MATCHING_CONFIG, pMatching.tablePath,
				"Entity " + pMatching.entityName + ".", false, "the master zone this table belongs to is not eligible for deletion: " + pMasterDataspaceEligibility.reason);
		}
		if (!pMatching.configured)
		{
			return new Item(Kind.MATCHING_CONFIG, pMatching.tablePath, "Entity " + pMatching.entityName + ".", false,
				"no matching configuration found for " + pMatching.tablePath + ": nothing to delete.");
		}
		return new Item(Kind.MATCHING_CONFIG, pMatching.tablePath, "Entity " + pMatching.entityName + ".", true, null);
	}

	private static Item flowItem(final SourceFacts pSource, final String pMappingId, final boolean pDelivered, final String pLandingDataspace)
	{
		final String detail = "Source " + pSource.sourceSystem + ".";
		if (pDelivered)
		{
			return new Item(Kind.LANDING_FLOW, pMappingId, detail, false, ProjectDeletionPlan.DELIVERED_REASON);
		}
		final String foreign = ProjectDeletionPlan.foreignFlowReason(pLandingDataspace, pSource, pMappingId);
		if (foreign != null)
		{
			return new Item(Kind.LANDING_FLOW, pMappingId, detail, false, foreign);
		}
		return new Item(Kind.LANDING_FLOW, pMappingId, detail, true, null);
	}

	private static Item distributionFlowItem(final TargetFacts pTarget, final TargetFlowFacts pFlow, final boolean pDelivered, final String pMasterDataspace)
	{
		final String detail = "Distribution target " + pTarget.targetId + ", table " + pFlow.tablePath + ".";
		if (pDelivered)
		{
			return new Item(Kind.DISTRIBUTION_FLOW, pFlow.mappingId, detail, false, ProjectDeletionPlan.DELIVERED_REASON);
		}
		final String refusal = ProjectDeletionPlan.distributionFlowReason(pMasterDataspace, pTarget.targetId, pFlow);
		return new Item(Kind.DISTRIBUTION_FLOW, pFlow.mappingId, detail, refusal == null, refusal);
	}


	// ------------------------------------------------------------------------------------- connections and catalogue sources (lot C)

	/** The reasons a connection or a catalogue source is kept, one sentence each (public: the tests and the wording of the plan are pinned on them). */
	public static final class ConnectionReasons
	{
		private ConnectionReasons()
		{
		}

		public static String sourceOnPickedConnection(final String pSourceId, final String pConnectionId)
		{
			return "the connection " + pConnectionId + " was not created by this factory for the project (it was picked from the catalogue, or already existed): the catalogue source "
				+ pSourceId + " created on it is kept, other people may rely on its scan.";
		}

		public static String shared(final String pWhat, final List<String> pProjects)
		{
			return "shared: " + (pProjects.size() == 1 ? "project " : "projects ") + ConnectionReasons.join(pProjects) + " also use" + (pProjects.size() == 1 ? "s " : " ") + pWhat
				+ ": not deleted.";
		}

		public static String sourceNamesAnotherConnection(final String pSourceId, final String pOther, final String pConnectionId)
		{
			return "the catalogue source " + pSourceId + " now names connection " + pOther + ", not " + pConnectionId + ": it was changed by hand after the factory created it, not deleted.";
		}

		public static String stillUsed(final List<String> pBlockers)
		{
			return "still in use, not removed with it: " + ConnectionReasons.join(pBlockers) + " - not deleted.";
		}

		public static String connectionFound()
		{
			return "this connection already existed when the project used it (the factory found it, it did not create it): kept.";
		}

		public static String connectionNotSampleFile(final String pType)
		{
			return "a " + pType + " connection: the factory only ever creates sample-file connections, so this one is not deleted.";
		}

		public static String connectionKeepsItsSource(final String pSourceId, final String pSourceReason)
		{
			return "its catalogue source " + pSourceId + " is kept (" + pSourceReason + "), and that source names this connection: kept too.";
		}

		public static String foreignSources(final List<String> pSourceIds)
		{
			return "catalogue source(s) " + ConnectionReasons.join(pSourceIds) + " name this connection and were not created by this factory: kept.";
		}

		public static String mappingStillUses(final MappingRef pMapping)
		{
			return "mapping " + pMapping.id + (pMapping.label.isEmpty() ? "" : " (" + pMapping.label + ")") + " still runs on this connection and is not removed by this deletion: kept.";
		}

		private static String join(final List<String> pValues)
		{
			final StringBuilder sb = new StringBuilder();
			for (final String value : pValues)
			{
				sb.append(sb.length() == 0 ? "" : ", ").append(value);
			}
			return sb.toString();
		}
	}

	static Item catalogSourceItem(final ConnectionFacts pConnection, final boolean pDelivered)
	{
		final String detail = "Catalogue source of connection " + pConnection.connectionId + ": " + pConnection.assets + " asset(s), " + pConnection.columns + " column(s) and "
			+ pConnection.catalogFlows.size() + " catalogue flow(s) go with it.";
		final String source = pConnection.catalogSourceId;
		if (pDelivered)
		{
			return new Item(Kind.CATALOG_SOURCE, source, detail, false, ProjectDeletionPlan.DELIVERED_REASON);
		}
		if (!pConnection.connectionCreatedByFactory)
		{
			return new Item(Kind.CATALOG_SOURCE, source, detail, false, ConnectionReasons.sourceOnPickedConnection(source, pConnection.connectionId));
		}
		if (pConnection.catalogSourceExists && !pConnection.connectionId.equals(pConnection.catalogSourceConnector))
		{
			return new Item(Kind.CATALOG_SOURCE, source, detail, false,
				ConnectionReasons.sourceNamesAnotherConnection(source, pConnection.catalogSourceConnector, pConnection.connectionId));
		}
		if (!pConnection.otherProjects.isEmpty())
		{
			return new Item(Kind.CATALOG_SOURCE, source, detail, false, ConnectionReasons.shared("this catalogue source", pConnection.otherProjects));
		}
		if (!pConnection.catalogBlockers.isEmpty())
		{
			return new Item(Kind.CATALOG_SOURCE, source, detail, false, ConnectionReasons.stillUsed(pConnection.catalogBlockers));
		}
		return new Item(Kind.CATALOG_SOURCE, source, detail, true, null);
	}

	static Item connectionItem(final ConnectionFacts pConnection, final boolean pDelivered, final List<Item> pItemsSoFar)
	{
		final String detail = "Sample-file connection created for this project; its provenance and metrics rows go with it.";
		final String name = pConnection.connectionId;
		if (pDelivered)
		{
			return new Item(Kind.CONNECTION, name, detail, false, ProjectDeletionPlan.DELIVERED_REASON);
		}
		if (!pConnection.connectionCreatedByFactory)
		{
			return new Item(Kind.CONNECTION, name, detail, false, ConnectionReasons.connectionFound());
		}
		if (pConnection.connectionExists && !"sample-file".equals(pConnection.connectionType))
		{
			return new Item(Kind.CONNECTION, name, detail, false, ConnectionReasons.connectionNotSampleFile(pConnection.connectionType));
		}
		if (!pConnection.otherProjects.isEmpty())
		{
			return new Item(Kind.CONNECTION, name, detail, false, ConnectionReasons.shared("this connection", pConnection.otherProjects));
		}
		if (!pConnection.otherSourceIds.isEmpty())
		{
			return new Item(Kind.CONNECTION, name, detail, false, ConnectionReasons.foreignSources(pConnection.otherSourceIds));
		}
		if (!ProjectDeletionPlan.blank(pConnection.catalogSourceId) && pConnection.catalogSourceExists)
		{
			for (final Item item : pItemsSoFar)
			{
				if (item.kind == Kind.CATALOG_SOURCE && item.name.equals(pConnection.catalogSourceId) && !item.eligible)
				{
					return new Item(Kind.CONNECTION, name, detail, false, ConnectionReasons.connectionKeepsItsSource(item.name, item.reason));
				}
			}
		}
		// every mapping left on the connection must be one this deletion removes: an eligible flow item of this plan names it
		final List<String> needsFlows = new ArrayList<String>();
		for (final MappingRef mapping : pConnection.otherMappings)
		{
			boolean removed = false;
			for (final Item item : pItemsSoFar)
			{
				if ((item.kind == Kind.LANDING_FLOW || item.kind == Kind.DISTRIBUTION_FLOW) && item.eligible && item.name.equals(mapping.id))
				{
					removed = true;
				}
			}
			if (!removed)
			{
				return new Item(Kind.CONNECTION, name, detail, false, ConnectionReasons.mappingStillUses(mapping));
			}
			needsFlows.add(mapping.id);
		}
		if (!pConnection.connectionBlockers.isEmpty())
		{
			return new Item(Kind.CONNECTION, name, detail, false, ConnectionReasons.stillUsed(pConnection.connectionBlockers));
		}
		return new Item(Kind.CONNECTION, name, detail, true, null, needsFlows);
	}

	private static boolean anyLandingDatasetCreatedByFactory(final List<SourceFacts> pSources)
	{
		for (final SourceFacts source : pSources)
		{
			if (source.landingDatasetCreatedByFactory)
			{
				return true;
			}
		}
		return false;
	}

	private static final String DELIVERED_REASON = "this project was delivered here by EBX Staging: remove it on the origin instance, or by hand.";

	private static final class Eligibility
	{
		final boolean eligible;
		final String reason;

		private Eligibility(final boolean pEligible, final String pReason)
		{
			this.eligible = pEligible;
			this.reason = pReason;
		}

		private static Eligibility ok()
		{
			return new Eligibility(true, null);
		}

		private static Eligibility refuse(final String pReason)
		{
			return new Eligibility(false, pReason);
		}
	}

	/**
	 * The shared decision behind every dataspace item: delivered, then a foreign (non-computed) stored name,
	 * then missing creation evidence, then an optional caller-supplied gate (the {@code TARGET_EXISTS} master
	 * rule) - in that order, each with its own wording.
	 */
	private static Eligibility dataspaceEligibility(final boolean pDelivered, final String pStored, final String pComputed, final boolean pCreatedByFactory,
		final boolean pExtraGate, final String pExtraGateReason)
	{
		if (pDelivered)
		{
			return Eligibility.refuse(ProjectDeletionPlan.DELIVERED_REASON);
		}
		if (pExtraGate)
		{
			return Eligibility.refuse(pExtraGateReason);
		}
		if (!pComputed.equals(pStored.trim()))
		{
			return Eligibility.refuse("this dataspace (" + pStored + ") is not the name this factory would itself generate (" + pComputed
				+ "): never deleting a dataspace this factory did not create - if it needs to go, do it by hand.");
		}
		if (!pCreatedByFactory)
		{
			return Eligibility.refuse("no PipelineStep evidence that the factory actually CREATED this dataspace (as opposed to finding one that already existed under "
				+ "a matching name): refusing to guess at ownership from the name alone.");
		}
		return Eligibility.ok();
	}

	/** Data models carry no separate stored/computed name to compare (see the class Javadoc): delivered, the extra gate, then step evidence only. */
	private static Eligibility modelEligibility(final boolean pDelivered, final boolean pStepDone, final boolean pExtraGate, final String pExtraGateReason)
	{
		if (pDelivered)
		{
			return Eligibility.refuse(ProjectDeletionPlan.DELIVERED_REASON);
		}
		if (pExtraGate)
		{
			return Eligibility.refuse(pExtraGateReason);
		}
		if (!pStepDone)
		{
			return Eligibility.refuse("no PipelineStep evidence that the factory actually published this data model: refusing to guess at ownership from the name alone.");
		}
		return Eligibility.ok();
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	// ======================================================================================================= validate

	/**
	 * A refusal string, or {@code null} when the call may proceed. {@code pConfirm} must equal the project code
	 * exactly (the same ceremony {@code POST .../master/reset?confirm=} already uses); every value of {@code
	 * pOptions} must be one of {@link #OPTIONS}; and {@code dataspaces} may not be selected without {@code
	 * matching} when the plan holds an eligible, configured matching item whose master dataspace is also
	 * eligible - see the class Javadoc.
	 */
	public static String validate(final Plan pPlan, final Set<String> pOptions, final String pConfirm)
	{
		for (final String option : pOptions)
		{
			if (!ProjectDeletionPlan.isKnownOption(option))
			{
				return "Unknown option '" + option + "': expected one of " + java.util.Arrays.toString(ProjectDeletionPlan.OPTIONS) + ".";
			}
		}
		if (pConfirm == null || !pConfirm.equals(pPlan.projectCode))
		{
			return "Pass ?confirm=" + pPlan.projectCode + " (the exact project code) to actually delete project " + pPlan.projectCode + " - this cannot be undone.";
		}
		if (pOptions.contains(ProjectDeletionPlan.OPTION_DATASPACES) && !pOptions.contains(ProjectDeletionPlan.OPTION_MATCHING))
		{
			boolean masterDataspaceEligible = false;
			for (final Item item : pPlan.items)
			{
				if (item.kind == Kind.MASTER_DATASPACE && item.eligible)
				{
					masterDataspaceEligible = true;
				}
			}
			if (masterDataspaceEligible)
			{
				for (final Item item : pPlan.items)
				{
					if ((item.kind == Kind.MATCHING_CONFIG || item.kind == Kind.MATCHING_BUSINESS_OBJECT) && item.eligible)
					{
						return "The 'dataspaces' option would delete the master dataspace while a live matching configuration (" + item.name + ") still owns a "
							+ "metadataset inside it: select 'matching' too, so the configuration is removed first.";
					}
				}
			}
		}
		if (pOptions.contains(ProjectDeletionPlan.OPTION_CONNECTIONS) && !pOptions.contains(ProjectDeletionPlan.OPTION_FLOWS))
		{
			for (final Item item : pPlan.items)
			{
				if (item.kind == Kind.CONNECTION && item.eligible && !item.needsFlows.isEmpty())
				{
					return "The 'connections' option would delete connection " + item.name + " while flow(s) " + ProjectDeletionPlan.joinIds(item.needsFlows)
						+ " still run on it: select 'flows' too, so they are removed first.";
				}
			}
		}
		return null;
	}

	private static String joinIds(final List<String> pIds)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String id : pIds)
		{
			sb.append(sb.length() == 0 ? "" : ", ").append(id);
		}
		return sb.toString();
	}
}
