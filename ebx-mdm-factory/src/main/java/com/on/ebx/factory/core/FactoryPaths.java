package com.on.ebx.factory.core;

import com.orchestranetworks.schema.Path;

/** Paths of the {@code MdmFactory.xsd} model. Keep in step with tools/build_xsd.py. */
public final class FactoryPaths
{
	private FactoryPaths()
	{
	}

	public static final String MODEL_PATH = "/WEB-INF/ebx/schemas/MdmFactory.xsd";
	/** The EBX module that packages MdmFactory.xsd (module.xml/name); the startup hook passes the real name. */
	public static final String MODULE_NAME = "ebx-mdm-factory";

	/**
	 * Distribution round 3: the outbox model, packaged separately from MdmFactory.xsd (tools/build_xsd.py
	 * only builds that one file) so it can be instantiated as a dataset INSIDE a project's own master
	 * dataspace - see docs/DISTRIBUTION.md sec on round 3 and {@code ebx.DistributionOutboxInstaller}. A
	 * master-table trigger cannot open {@code execute transaction} at all (measured live, PipelineStep
	 * 395), so it can only write through its own ambient {@code _ebx.transaction}, scoped to
	 * {@code _ebx.dataspace} - the outbox has to live there, not in {@link #DATASPACE}.
	 */
	public static final String DISTRIBUTION_OUTBOX_MODEL_PATH = "/WEB-INF/ebx/schemas/DistributionOutbox.xsd";
	/** The dataset name a project's master dataspace gets this model instantiated under, next to its {@code Master} dataset. */
	public static final String DISTRIBUTION_OUTBOX_DATASET = "DistributionOutbox";
	/**
	 * Automatic-intake lot (2026-10-05): the signal model, packaged separately like {@link #DISTRIBUTION_OUTBOX_MODEL_PATH} for the same
	 * reason (a table trigger can only write through its own ambient transaction, scoped to its own dataspace): instantiated as a second
	 * dataset of a project's LANDING dataspace, next to the landing datasets. See {@code ebx.IntakeSignalInstaller}.
	 */
	public static final String INTAKE_SIGNAL_MODEL_PATH = "/WEB-INF/ebx/schemas/IntakeSignal.xsd";
	/** The dataset name a project's landing dataspace gets {@link #INTAKE_SIGNAL_MODEL_PATH} instantiated under. */
	public static final String INTAKE_SIGNAL_DATASET = "MdmFactoryIntakeSignal";
	/**
	 * Lot M-INC (2026-10-12, docs/VOLUME.md "Lot M-INC"): the journal of changed staging rows, packaged separately like {@link #INTAKE_SIGNAL_MODEL_PATH} for the same
	 * reason (a staging-table trigger can only write through its own ambient transaction, scoped to its own dataspace): instantiated as a second dataset of a
	 * project's STAGING dataspace, next to the {@code Staging} dataset. See {@code ebx.MasterJournal}.
	 */
	public static final String MASTER_JOURNAL_MODEL_PATH = "/WEB-INF/ebx/schemas/MasterJournal.xsd";
	/** The dataset name a project's staging dataspace gets {@link #MASTER_JOURNAL_MODEL_PATH} instantiated under. */
	public static final String MASTER_JOURNAL_DATASET = "MdmFactoryMasterJournal";
	/**
	 * Where the factory keeps its projects: {@code -Debx.factory.dataspace} / {@code -Debx.factory.dataset},
	 * default {@code MdmFactory}. The zones a project generates live in dataspaces of their own.
	 */
	public static final String DATASPACE = FactoryPaths.key("ebx.factory.dataspace");
	public static final String DATASET = FactoryPaths.key("ebx.factory.dataset");

	/** The only settings row that is read. */
	public static final String DEFAULT_SETTINGS = "DEFAULT";

	private static String key(final String pProperty)
	{
		final String value = System.getProperty(pProperty);
		return value == null || value.trim().isEmpty() ? "MdmFactory" : value.trim();
	}

	public static final Path SETTINGS = Path.parse("/root/FactorySettings");
	public static final Path PROJECT = Path.parse("/root/Project");
	public static final Path PROJECT_SOURCE = Path.parse("/root/ProjectSource");
	public static final Path LANDING_TABLE = Path.parse("/root/LandingTable");
	public static final Path LANDING_COLUMN = Path.parse("/root/LandingColumn");
	public static final Path LANDING_RELATION = Path.parse("/root/LandingRelation");
	public static final Path STAGING_ENTITY = Path.parse("/root/StagingEntity");
	public static final Path STAGING_ATTRIBUTE = Path.parse("/root/StagingAttribute");
	public static final Path ATTRIBUTE_MAPPING = Path.parse("/root/AttributeMapping");
	/** Target-mapping lot 1 (docs/TARGET-MAPPING.md sec 2.1): one node of a landing -&gt; staging mapping tree. */
	public static final Path TRANSFER_FEED = Path.parse("/root/TransferFeed");
	public static final Path MATCH_PLAN = Path.parse("/root/MatchPlan");
	public static final Path PIPELINE_STEP = Path.parse("/root/PipelineStep");
	public static final Path AGENT_RUN = Path.parse("/root/AgentRun");
	public static final Path AGENT_STEP = Path.parse("/root/AgentStep");
	/** Agentic framework lot (2026-10-30, docs/AGENTS.md): an agent declared as data, see {@code ebx.AgentFramework}. */
	public static final Path AGENT_DEFINITION = Path.parse("/root/AgentDefinition");
	/** Agentic framework lot: one decision of an agent under (or after) human review. */
	public static final Path AGENT_PROPOSAL = Path.parse("/root/AgentProposal");
	/** Source drift lot (2026-10-30, docs/DRIFT.md): one schema change of a source, proposed, decided by a person, then applied. */
	public static final Path SOURCE_DRIFT = Path.parse("/root/SourceDrift");
	public static final Path XREF = Path.parse("/root/Xref");
	public static final Path TRANSFER_REJECT = Path.parse("/root/TransferReject");
	public static final Path MAPPING_STEP = Path.parse("/root/MappingStep");
	public static final Path QUALITY_RULE = Path.parse("/root/QualityRule");
	public static final Path REFERENCE_BINDING = Path.parse("/root/ReferenceBinding");
	public static final Path CAPABILITY_SUGGESTION = Path.parse("/root/CapabilitySuggestion");
	/** Explorer favorites (2026-09-23), ported from ebx-connectors' own Favorite table - see {@code ebx.Favorites}. */
	public static final Path FAVORITE = Path.parse("/root/Favorite");
	/** Lot C1: the durable queue of background jobs, see {@code ebx.FactoryJobs}. */
	public static final Path FACTORY_JOB = Path.parse("/root/FactoryJob");
	/** A pause the factory applied for the length of a load and has not lifted yet, see {@code ebx.LoadPauseLedger}. */
	public static final Path LOAD_PAUSE = Path.parse("/root/LoadPause");
	/** Distribution lot 1 (engine, SPEC-distribution.md): one Connectors connection + write operation per project. */
	public static final Path DISTRIBUTION_TARGET = Path.parse("/root/DistributionTarget");
	/** Distribution lot 1: the outbox a REALTIME target's table trigger writes into. */
	public static final Path DISTRIBUTION_EVENT = Path.parse("/root/DistributionEvent");
	/** Automatic-intake lot (2026-10-05): one row per chain the engine launched, see {@code ebx.IntakeEngine}. */
	public static final Path INTAKE_CHAIN = Path.parse("/root/IntakeChain");
	/** Audit F-02: role of the directory (or Everyone) -> Factory right, administrator-only; see {@code ebx.FactoryAccess}. */
	public static final Path ACCESS_RIGHT = Path.parse("/root/AccessRight");
	/** Lot C1-B (docs/LOT-C1.md sec 5): a pack of the accelerator catalogue chosen for a CENTRALISATION project. */
	public static final Path PROJECT_PACK = Path.parse("/root/ProjectPack");
	/** Lot C1-B: one difference between a chosen pack and the project's need, proposed by the model and decided by a person. */
	public static final Path DIFFERENCE = Path.parse("/root/Difference");
	/** Lot C1-B: an object written by the generation of a CENTRALISATION domain. */
	public static final Path DOMAIN_OBJECT = Path.parse("/root/DomainObject");

	/** Every table of the model, in creation order; the status route counts them. */
	public static final Path[] TABLES = { SETTINGS, PROJECT, PROJECT_SOURCE, LANDING_TABLE, LANDING_COLUMN, SOURCE_DRIFT, LANDING_RELATION, STAGING_ENTITY,
		STAGING_ATTRIBUTE, TRANSFER_FEED, ATTRIBUTE_MAPPING, MAPPING_STEP, QUALITY_RULE, REFERENCE_BINDING, CAPABILITY_SUGGESTION, MATCH_PLAN, PIPELINE_STEP,
		AGENT_RUN, AGENT_STEP, AGENT_DEFINITION, AGENT_PROPOSAL, XREF, TRANSFER_REJECT, FAVORITE, DISTRIBUTION_TARGET, DISTRIBUTION_EVENT, FACTORY_JOB, LOAD_PAUSE, INTAKE_CHAIN, ACCESS_RIGHT,
		PROJECT_PACK, DIFFERENCE, DOMAIN_OBJECT };

	/** Technical fields the factory adds to every landing table, after the source columns. */
	public static final class Landing
	{
		private Landing()
		{
		}

		/** Key of the landing row: {@code _sourceId} alone, one landing dataset belonging to one source system. */
		public static final String SOURCE_ID = "_sourceId";
		public static final String SOURCE_SYSTEM = "_sourceSystem";
		public static final String LOAD_ID = "_loadId";
		public static final String RECEIVED_AT = "_receivedAt";
		public static final String ROW_HASH = "_rowHash";
		public static final String STATUS = "_status";
	}

	public static final class Project
	{
		private Project()
		{
		}

		public static final Path CODE = Path.parse("./code");
		public static final Path LABEL = Path.parse("./label");
		public static final Path DESCRIPTION = Path.parse("./description");
		public static final Path MODE = Path.parse("./mode");
		/** {@code CONSOLIDATION}/{@code CENTRALISATION}/{@code COEXISTENCE}/{@code REGISTRY}; {@code CONSOLIDATION} and {@code CENTRALISATION} are implemented (lot C1). */
		public static final Path STYLE = Path.parse("./style");
		public static final Path STATUS = Path.parse("./status");
		public static final Path LANDING_DATASPACE = Path.parse("./landingDataspace");
		public static final Path STAGING_DATASPACE = Path.parse("./stagingDataspace");
		public static final Path STAGING_DATASET = Path.parse("./stagingDataset");
		public static final Path MASTER_DATASPACE = Path.parse("./masterDataspace");
		public static final Path MASTER_DATASET = Path.parse("./masterDataset");
		public static final Path OWNER = Path.parse("./owner");
		public static final Path CREATED_AT = Path.parse("./createdAt");
		/** Delivery lot 1: {@code FactorySettings.instanceId} of the instance the project was created on; see {@code delivery.OriginRule}. */
		public static final Path ORIGIN_REPOSITORY = Path.parse("./originRepository");
		/** {@code SHAPES_ONLY} (default) / {@code SAMPLE_VALUES} - what {@code quality/suggest-llm} may send to the language model for this project; see {@code ebx.QualityLlmSuggester}. */
		public static final Path LLM_VALUE_SHARING = Path.parse("./llmValueSharing");
		/** {@code SAMPLE_VALUES} mode only: how many of a column's most frequent distinct values are sent. */
		public static final Path LLM_SAMPLE_SIZE = Path.parse("./llmSampleSize");
		/** Lot MT (2026-10-04): {@code MANUAL} (default, also when empty) / {@code REALTIME} - when the add-on matches master records; see {@code master.MatchingTriggerMode}. */
		public static final Path MATCHING_TRIGGER = Path.parse("./matchingTrigger");
		/** Automatic-intake lot (2026-10-05): {@code OFF} (default, also when empty) / {@code ON} / {@code PAUSED} - see {@code ebx.Intake}. May be absent from a model that predates the lot: read it guarded. */
		public static final Path INTAKE_STATE = Path.parse("./intakeState");
		public static final Path INTAKE_PAUSE_REASON = Path.parse("./intakePauseReason");
		/** User id of whoever switched the intake on: the identity an intake chain runs as (see {@code workflow.IntakeGate}). */
		public static final Path INTAKE_ENABLED_BY = Path.parse("./intakeEnabledBy");
		public static final Path INTAKE_ENABLED_AT = Path.parse("./intakeEnabledAt");
		/** Boolean, empty counts as true. */
		public static final Path INTAKE_REVIEW_REJECTS = Path.parse("./intakeReviewRejects");
		/** Int 1-100; empty = never stop. */
		public static final Path INTAKE_STOP_REJECT_PERCENT = Path.parse("./intakeStopRejectPercent");

		/** Blank start: the need of a CENTRALISATION project, kept on the project so that it exists with or without a pack (ProjectPack rows still carry a copy). */
		public static final Path ACCEL_SPECIFICATION = Path.parse("./accelSpecification");
		/** Blank start: true when the person chose "Skip the packs, start blank". Absent from a model that predates it: read it guarded. */
		public static final Path ACCEL_BLANK_START = Path.parse("./accelBlankStart");
		/** Lot C1-B: CENTRALISATION only, filled by the generation (sub-lot D). */
		public static final Path DOMAIN_DATASPACE = Path.parse("./domainDataspace");
		public static final Path DOMAIN_DATASET = Path.parse("./domainDataset");
		public static final Path DOMAIN_MODEL = Path.parse("./domainModel");

		public static final String INTAKE_OFF = "OFF";
		public static final String INTAKE_ON = "ON";
		public static final String INTAKE_PAUSED = "PAUSED";
	}

	public static final class ProjectSource
	{
		private ProjectSource()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path SOURCE_SYSTEM = Path.parse("./sourceSystem");
		public static final Path CATALOG_SOURCE = Path.parse("./catalogSource");
		public static final Path CONNECTOR = Path.parse("./connector");
		public static final Path SCHEMA_FILTER = Path.parse("./schemaFilter");
		public static final Path TRUST_RANK = Path.parse("./trustRank");
		public static final Path LANDING_MODEL = Path.parse("./landingModel");
		public static final Path LANDING_PUBLICATION = Path.parse("./landingPublication");
		public static final Path LANDING_DATASET = Path.parse("./landingDataset");
		public static final Path STATUS = Path.parse("./status");
		public static final Path MESSAGE = Path.parse("./message");
		/** Landing-walkthrough lot (2026-10-16): the person's validation of the application at the Landing step. */
		public static final Path LANDING_VALIDATED = Path.parse("./landingValidated");
		public static final Path LANDING_VALIDATED_BY = Path.parse("./landingValidatedBy");
		public static final Path LANDING_VALIDATED_AT = Path.parse("./landingValidatedAt");
		/** Automatic-intake lot (2026-10-05), all per application, empty = default; see {@code intake.IntakeSettings}. */
		public static final Path INTAKE_QUIET_SECONDS = Path.parse("./intakeQuietSeconds");
		public static final Path INTAKE_MAX_WAIT_MINUTES = Path.parse("./intakeMaxWaitMinutes");
		public static final Path INTAKE_MIN_ROWS = Path.parse("./intakeMinRows");
		public static final Path INTAKE_BATCH_SIGNAL = Path.parse("./intakeBatchSignal");
	}

	public static final class LandingTable
	{
		private LandingTable()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT_SOURCE = Path.parse("./projectSource");
		public static final Path CATALOG_ASSET = Path.parse("./catalogAsset");
		public static final Path SOURCE_OBJECT = Path.parse("./sourceObject");
		public static final Path TABLE_NAME = Path.parse("./tableName");
		public static final Path SELECTED = Path.parse("./selected");
		public static final Path COLUMN_COUNT = Path.parse("./columnCount");
		public static final Path SOURCE_ROW_COUNT = Path.parse("./sourceRowCount");
		public static final Path INBOUND_MAPPING = Path.parse("./inboundMapping");
		public static final Path STATUS = Path.parse("./status");
		public static final Path LAST_LOAD_ID = Path.parse("./lastLoadId");
		public static final Path LAST_LOAD_AT = Path.parse("./lastLoadAt");
		public static final Path LAST_LOAD_ROWS = Path.parse("./lastLoadRows");
		public static final Path MESSAGE = Path.parse("./message");
		/** Landing key-study lot (2026-09-23): 0-100 of whoever proposed the current key, and its rationale. */
		public static final Path KEY_CONFIDENCE = Path.parse("./keyConfidence");
		public static final Path KEY_RATIONALE = Path.parse("./keyRationale");
		/** Volume wave 2, lot V2-E (2026-10-03): the source column that carries a row's last change (the feed watermark), its kind, and the one the last successful load read through. */
		public static final Path CHANGE_COLUMN = Path.parse("./changeColumn");
		public static final Path CHANGE_COLUMN_KIND = Path.parse("./changeColumnKind");
		public static final Path CHANGE_COLUMN_LOADED = Path.parse("./changeColumnLoaded");
	}

	public static final class PipelineStep
	{
		private PipelineStep()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path STEP = Path.parse("./step");
		public static final Path SCOPE = Path.parse("./scope");
		public static final Path STATUS = Path.parse("./status");
		public static final Path STARTED_AT = Path.parse("./startedAt");
		public static final Path ENDED_AT = Path.parse("./endedAt");
		public static final Path MESSAGE = Path.parse("./message");
		public static final Path DETAIL = Path.parse("./detail");
		/** pipeline-audit lot: see {@code ebx.Actor}. */
		public static final Path STARTED_BY = Path.parse("./startedBy");
		public static final Path VIA = Path.parse("./via");
	}

	public static final class LandingColumn
	{
		private LandingColumn()
		{
		}

		public static final Path LANDING_TABLE = Path.parse("./landingTable");
		public static final Path POSITION = Path.parse("./position");
		public static final Path SOURCE_COLUMN = Path.parse("./sourceColumn");
		public static final Path FIELD_NAME = Path.parse("./fieldName");
		public static final Path SOURCE_DATA_TYPE = Path.parse("./sourceDataType");
		public static final Path SOURCE_NULLABLE = Path.parse("./sourceNullable");
		public static final Path SOURCE_KEY = Path.parse("./sourceKey");
		public static final Path SOURCE_KEY_ORIGIN = Path.parse("./sourceKeyOrigin");
		public static final Path FILL_RATE = Path.parse("./fillRate");
		public static final Path DISTINCT_COUNT = Path.parse("./distinctCount");
		public static final Path MAX_LENGTH = Path.parse("./maxLength");
		public static final Path INFERRED_TYPE = Path.parse("./inferredType");
		public static final Path TAGS = Path.parse("./tags");
	}

	/** Source drift lot (2026-10-30, docs/DRIFT.md). */
	public static final class SourceDrift
	{
		private SourceDrift()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path LANDING_TABLE = Path.parse("./landingTable");
		public static final Path KIND = Path.parse("./kind");
		public static final Path SOURCE_COLUMN = Path.parse("./sourceColumn");
		public static final Path FIELD_NAME = Path.parse("./fieldName");
		public static final Path OLD_TYPE = Path.parse("./oldType");
		public static final Path NEW_TYPE = Path.parse("./newType");
		public static final Path NEW_DATA_TYPE = Path.parse("./newDataType");
		public static final Path SOURCE_KEY = Path.parse("./sourceKey");
		public static final Path SIGNATURE = Path.parse("./signature");
		public static final Path LANDING_CHANGE = Path.parse("./landingChange");
		public static final Path STAGING_CHANGE = Path.parse("./stagingChange");
		public static final Path MAPPING_CHANGE = Path.parse("./mappingChange");
		public static final Path OPERATIONS = Path.parse("./operations");
		public static final Path TABLE_STATUS_BEFORE = Path.parse("./tableStatusBefore");
		public static final Path STATUS = Path.parse("./status");
		public static final Path DETECTED_AT = Path.parse("./detectedAt");
		public static final Path DECIDED_BY = Path.parse("./decidedBy");
		public static final Path DECIDED_AT = Path.parse("./decidedAt");
		public static final Path APPLIED_BY = Path.parse("./appliedBy");
		public static final Path APPLIED_AT = Path.parse("./appliedAt");
		public static final Path APPLY_MESSAGE = Path.parse("./applyMessage");
	}

	public static final class LandingRelation
	{
		private LandingRelation()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT_SOURCE = Path.parse("./projectSource");
		public static final Path FROM_TABLE = Path.parse("./fromTable");
		public static final Path FROM_COLUMNS = Path.parse("./fromColumns");
		public static final Path TO_TABLE = Path.parse("./toTable");
		public static final Path TO_COLUMNS = Path.parse("./toColumns");
		public static final Path CARDINALITY = Path.parse("./cardinality");
		public static final Path SELF_REFERENCE = Path.parse("./selfReference");
		public static final Path ORIGIN = Path.parse("./origin");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path RATIONALE = Path.parse("./rationale");
		public static final Path STATUS = Path.parse("./status");
		public static final Path CHECKED_AT = Path.parse("./checkedAt");
		public static final Path CHILD_VALUES = Path.parse("./childValues");
		public static final Path ORPHAN_VALUES = Path.parse("./orphanValues");
		public static final Path MESSAGE = Path.parse("./message");
	}

	public static final class StagingEntity
	{
		private StagingEntity()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path NAME = Path.parse("./name");
		public static final Path LABEL = Path.parse("./label");
		public static final Path TARGET_TABLE = Path.parse("./targetTable");
		/** Multi-valued foreign key to /root/StagingEntity: the rule-proposed entities a canonical (LLM) entity unifies; empty for an entity fed by one landing table. */
		public static final Path MEMBER_ENTITIES = Path.parse("./memberEntities");
		public static final Path ORIGIN = Path.parse("./origin");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path RATIONALE = Path.parse("./rationale");
		public static final Path STATUS = Path.parse("./status");
		/** Written by {@code StagingLoader} (transfer package) after each STAGING_LOAD run of this entity - not a proposal field. */
		public static final Path LAST_LOAD_AT = Path.parse("./lastLoadAt");
		public static final Path LAST_LOAD_READ = Path.parse("./lastLoadRead");
		public static final Path LAST_LOAD_WRITTEN = Path.parse("./lastLoadWritten");
		public static final Path LAST_LOAD_SKIPPED = Path.parse("./lastLoadSkipped");
		public static final Path LAST_LOAD_REJECTED = Path.parse("./lastLoadRejected");
		/** 2026-09-23, incremental-transfer lot: read this run (its {@code _status} said so), but its content's hash matched the staging row's own {@code _sourceHash} - never written to staging, only the landing status was set to {@code STAGED}. */
		public static final Path LAST_LOAD_UNCHANGED = Path.parse("./lastLoadUnchanged");
		public static final Path LAST_LOAD_MESSAGE = Path.parse("./lastLoadMessage");
	}

	public static final class StagingAttribute
	{
		private StagingAttribute()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path ENTITY = Path.parse("./entity");
		public static final Path NAME = Path.parse("./name");
		public static final Path LABEL = Path.parse("./label");
		public static final Path DATA_TYPE = Path.parse("./dataType");
		public static final Path MAX_LENGTH = Path.parse("./maxLength");
		public static final Path MANDATORY = Path.parse("./mandatory");
		public static final Path BUSINESS_KEY = Path.parse("./businessKey");
		/** The technical identity of a staging row (sourceSystem/sourceId), distinct from {@link #BUSINESS_KEY} (the source's own key). No default in the XSD: null means the row was never told either way (written before this field existed). */
		public static final Path PRIMARY_KEY = Path.parse("./primaryKey");
		public static final Path ENUMERATED = Path.parse("./enumerated");
		public static final Path REFERENCE_ENTITY = Path.parse("./referenceEntity");
		/** The {@code LandingRelation} this reference was deduced from; empty when a person or the model set the reference directly (see {@code ReviewRules}). */
		public static final Path FROM_RELATION = Path.parse("./fromRelation");
		public static final Path TARGET_PATH = Path.parse("./targetPath");
		/** Foreign key to another {@code StagingAttribute} of the SAME entity - set only on a derived attribute (see {@link #DERIVATION}). Empty for an ordinary attribute. */
		public static final Path DERIVED_FROM = Path.parse("./derivedFrom");
		/** {@code FOLD_KEY}, {@code VALID_VALUE}, {@code SCRIPT}, {@code CHILD_KEYS} or empty - how this attribute is computed ({@code FOLD_KEY}/{@code VALID_VALUE}/{@code SCRIPT} from {@link #DERIVED_FROM}; {@code CHILD_KEYS} from the child records, named by {@link #DERIVATION_ARGUMENT}). */
		public static final Path DERIVATION = Path.parse("./derivation");
		/** Lot M3 (2026-10-03): {@code CHILD_KEYS} only - the JSON definition ({@code quality.ChildKeysDerivation#parse}); empty for every other derivation. May be absent from a model that predates the lot: read it guarded. */
		public static final Path DERIVATION_ARGUMENT = Path.parse("./derivationArgument");
		/** Lot M3: {@code CHILD_KEYS} only - when the post-pass of {@code ebx.StagingLoader} last finished for this attribute; cleared when a run changed rows and the post-pass did not complete, so the next run recomputes. Guarded read, like {@link #DERIVATION_ARGUMENT}. */
		public static final Path DERIVATION_LAST_RUN_AT = Path.parse("./derivationLastRunAt");
		/** Lot M3: {@code CHILD_KEYS} only - the counters of the last post-pass, one line. Guarded read. */
		public static final Path DERIVATION_LAST_RUN_MESSAGE = Path.parse("./derivationLastRunMessage");
		public static final Path ORIGIN = Path.parse("./origin");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path RATIONALE = Path.parse("./rationale");
		public static final Path STATUS = Path.parse("./status");
	}

	public static final class AttributeMapping
	{
		private AttributeMapping()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path ATTRIBUTE = Path.parse("./attribute");
		public static final Path LANDING_COLUMN = Path.parse("./landingColumn");
		public static final Path TRANSFORMATION = Path.parse("./transformation");
		public static final Path ARGUMENT = Path.parse("./argument");
		/** Target-mapping lot 1: the {@code TransferFeed} this rule belongs to - empty only for a row written before this lot ({@code FeedBackfill}). */
		public static final Path FEED = Path.parse("./feed");
		/** Target-mapping lot 1: a Connectors rule pipeline, replacing {@link #TRANSFORMATION}/{@link #ARGUMENT} entirely when non-empty. */
		public static final Path PIPELINE = Path.parse("./pipeline");
		public static final Path ORIGIN = Path.parse("./origin");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path RATIONALE = Path.parse("./rationale");
		public static final Path STATUS = Path.parse("./status");
		/** Mapping-matrix lot (docs/MAPPING-MATRIX.md): free-text reviewer comment on a REJECTED mapping - same convention as {@code MatchPlan#REVIEW_COMMENT}. */
		public static final Path REVIEW_COMMENT = Path.parse("./reviewComment");
		/** Staging-proposal lot 0 (docs/STAGING-PROPOSAL.md): another {@code AttributeMapping} this one is proposed to replace - empty for an ordinary mapping. Nothing writes it yet in this lot. */
		public static final Path ALTERNATIVE_TO = Path.parse("./alternativeTo");
	}

	/** Target-mapping lot 1 (docs/TARGET-MAPPING.md sec 2.1): one node of a landing -&gt; staging mapping tree. */
	public static final class TransferFeed
	{
		private TransferFeed()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path ENTITY = Path.parse("./entity");
		public static final Path LANDING_TABLE = Path.parse("./landingTable");
		/** Empty on a root feed; set on a child feed. */
		public static final Path PARENT_FEED = Path.parse("./parentFeed");
		/** JSON path of the array/object of child elements in the parent document; empty means "the parent row itself is the one element". */
		public static final Path COLLECTION_PATH = Path.parse("./collectionPath");
		/** Child only: the staging field of the child entity holding the parent's staging key. */
		public static final Path PARENT_LINK_FIELD = Path.parse("./parentLinkField");
		/** Connectors pipeline computing the child's {@code sourceId}; empty means the default of docs/TARGET-MAPPING.md sec 2.1. */
		public static final Path SOURCE_ID_PIPELINE = Path.parse("./sourceIdPipeline");
		public static final Path VARIANT = Path.parse("./variant");
		public static final Path ORIGIN = Path.parse("./origin");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path RATIONALE = Path.parse("./rationale");
		public static final Path STATUS = Path.parse("./status");
		/** Mapping-matrix lot (docs/MAPPING-MATRIX.md): free-text reviewer comment on a REJECTED feed - same convention as {@code MatchPlan#REVIEW_COMMENT}. */
		public static final Path REVIEW_COMMENT = Path.parse("./reviewComment");
	}

	public static final class MappingStep
	{
		private MappingStep()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path MAPPING = Path.parse("./mapping");
		public static final Path POSITION = Path.parse("./position");
		public static final Path KIND = Path.parse("./kind");
		public static final Path ARGUMENT = Path.parse("./argument");
		public static final Path ORIGIN = Path.parse("./origin");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path RATIONALE = Path.parse("./rationale");
		public static final Path STATUS = Path.parse("./status");
		public static final Path VALUES_SEEN = Path.parse("./valuesSeen");
		public static final Path VALUES_CHANGED = Path.parse("./valuesChanged");
		public static final Path VALUES_UNRESOLVED = Path.parse("./valuesUnresolved");
		public static final Path EXAMPLES = Path.parse("./examples");
		public static final Path CHECKED_AT = Path.parse("./checkedAt");
	}

	public static final class QualityRule
	{
		private QualityRule()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path ENTITY = Path.parse("./entity");
		public static final Path ATTRIBUTE = Path.parse("./attribute");
		public static final Path KIND = Path.parse("./kind");
		public static final Path ARGUMENT = Path.parse("./argument");
		public static final Path SEVERITY = Path.parse("./severity");
		public static final Path ORIGIN = Path.parse("./origin");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path RATIONALE = Path.parse("./rationale");
		public static final Path STATUS = Path.parse("./status");
		public static final Path VALUES_SEEN = Path.parse("./valuesSeen");
		public static final Path VALUES_FAILING = Path.parse("./valuesFailing");
		public static final Path EXAMPLES = Path.parse("./examples");
		public static final Path CHECKED_AT = Path.parse("./checkedAt");
		/** Desk lot L4: the failures grouped by one other attribute (JSON), and the condition under which the rule applies. */
		public static final Path FAILURE_BREAKDOWN = Path.parse("./failureBreakdown");
		public static final Path CONDITION_ATTRIBUTE = Path.parse("./conditionAttribute");
		public static final Path CONDITION_VALUES = Path.parse("./conditionValues");
		/** Execution counters (review-quality lot, task C) - written by {@code ebx.StagingLoader} at transfer time, distinct from the PROPOSAL evidence above that {@code quality/suggest} overwrites on every re-run. */
		public static final Path LAST_LOAD_CHECKED = Path.parse("./lastLoadChecked");
		public static final Path LAST_LOAD_FAILING = Path.parse("./lastLoadFailing");
		public static final Path LAST_LOAD_EXAMPLES = Path.parse("./lastLoadExamples");
		public static final Path LAST_LOAD_CHECKED_AT = Path.parse("./lastLoadCheckedAt");
	}

	public static final class CapabilitySuggestion
	{
		private CapabilitySuggestion()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path ENTITY = Path.parse("./entity");
		public static final Path ATTRIBUTE = Path.parse("./attribute");
		public static final Path NATURE = Path.parse("./nature");
		public static final Path ZONE = Path.parse("./zone");
		public static final Path TARGET_LIBRARY = Path.parse("./targetLibrary");
		public static final Path TARGET_FUNCTION = Path.parse("./targetFunction");
		public static final Path TARGET_IMPORT_NAME = Path.parse("./targetImportName");
		public static final Path CONNECTOR_ID = Path.parse("./connectorId");
		public static final Path CONNECTOR_OPERATION = Path.parse("./connectorOperation");
		public static final Path BINDING = Path.parse("./binding");
		/** SCRIPT_CONSTRAINT only, apply-ux fix (2026-09-21): the LLM-written sentence a business user reads on a flagged record - empty on a row suggested before this field existed. */
		public static final Path USER_MESSAGE = Path.parse("./userMessage");
		public static final Path SEVERITY = Path.parse("./severity");
		/** quality-script-rule lot (2026-09-23): SCRIPT_CONSTRAINT + zone STAGING only - blank/WARN/REJECT, read by {@code ebx.MasterLoader} at the staging -> master transfer. See {@code tools/build_xsd.py}'s own CAPABILITY_TRANSFER_POLICY comment. */
		public static final Path TRANSFER_POLICY = Path.parse("./transferPolicy");
		public static final Path READY = Path.parse("./ready");
		public static final Path READINESS_DETAIL = Path.parse("./readinessDetail");
		public static final Path PRICING = Path.parse("./pricing");
		public static final Path PRICING_NOTE = Path.parse("./pricingNote");
		public static final Path ALTERNATIVE_TO = Path.parse("./alternativeTo");
		public static final Path ORIGIN = Path.parse("./origin");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path RATIONALE = Path.parse("./rationale");
		public static final Path STATUS = Path.parse("./status");
		// apply lot (2026-09-21) - written by ebx.CapabilityApplier once a SCRIPT_CONSTRAINT row is applied; see docs/CAPABILITY-APPLY.md
		public static final Path SCRIPT_NAME = Path.parse("./scriptName");
		public static final Path CONSTRAINT_SCOPE = Path.parse("./constraintScope");
		public static final Path APPLIED_MODEL = Path.parse("./appliedModel");
		public static final Path APPLIED_ELEMENT_PATH = Path.parse("./appliedElementPath");
		public static final Path APPLIED_SCRIPT_IDE_PATH = Path.parse("./appliedScriptIdePath");
		public static final Path SCRIPT_BODY = Path.parse("./scriptBody");
		public static final Path EXPLANATION = Path.parse("./explanation");
		public static final Path RECORDS_CHECKED = Path.parse("./recordsChecked");
		public static final Path RECORDS_REPORTED = Path.parse("./recordsReported");
		public static final Path OFFENDING_SHAPES = Path.parse("./offendingShapes");
		public static final Path MEASURED_AT = Path.parse("./measuredAt");
		public static final Path APPLIED_AT = Path.parse("./appliedAt");
		public static final Path APPLY_MESSAGE = Path.parse("./applyMessage");
		/** quality-script-rule lot (2026-09-23): transfer-time execution counters, written by {@code ebx.MasterLoader} - same shape/purpose as {@code QualityRule.lastLoad*}. */
		public static final Path LAST_LOAD_CHECKED = Path.parse("./lastLoadChecked");
		public static final Path LAST_LOAD_FAILING = Path.parse("./lastLoadFailing");
		public static final Path LAST_LOAD_EXAMPLES = Path.parse("./lastLoadExamples");
		public static final Path LAST_LOAD_CHECKED_AT = Path.parse("./lastLoadCheckedAt");
	}

	public static final class ReferenceBinding
	{
		private ReferenceBinding()
		{
		}

		public static final Path KIND = Path.parse("./kind");
		public static final Path DATASPACE = Path.parse("./dataspace");
		public static final Path DATASET = Path.parse("./dataset");
		public static final Path TABLE_PATH = Path.parse("./tablePath");
		public static final Path CODE_FIELD = Path.parse("./codeField");
		public static final Path LABEL_FIELDS = Path.parse("./labelFields");
		public static final Path SCOPE_FIELD = Path.parse("./scopeField");
		public static final Path SYNONYMS = Path.parse("./synonyms");
	}

	public static final class AgentRun
	{
		private AgentRun()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path PHASE = Path.parse("./phase");
		public static final Path GOAL = Path.parse("./goal");
		public static final Path LLM_CONNECTOR = Path.parse("./llmConnector");
		public static final Path MODEL = Path.parse("./model");
		public static final Path STATUS = Path.parse("./status");
		public static final Path STEPS = Path.parse("./steps");
		public static final Path INPUT_TOKENS = Path.parse("./inputTokens");
		public static final Path OUTPUT_TOKENS = Path.parse("./outputTokens");
		public static final Path STARTED_BY = Path.parse("./startedBy");
		public static final Path STARTED_AT = Path.parse("./startedAt");
		public static final Path ENDED_AT = Path.parse("./endedAt");
		public static final Path OUTCOME = Path.parse("./outcome");
		// agentic framework lot (2026-10-30)
		public static final Path AGENT = Path.parse("./agent");
		public static final Path ACTING_FOR = Path.parse("./actingFor");
		public static final Path VIA = Path.parse("./via");
		public static final Path INPUTS = Path.parse("./inputs");
		public static final Path DECISION = Path.parse("./decision");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path APPLIED = Path.parse("./applied");
		public static final Path ESCALATED = Path.parse("./escalated");
		public static final Path REFUSED = Path.parse("./refused");
		public static final Path ESCALATION = Path.parse("./escalation");
	}

	/** Agentic framework lot (2026-10-30, docs/AGENTS.md). */
	public static final class AgentDefinition
	{
		private AgentDefinition()
		{
		}

		public static final Path CODE = Path.parse("./code");
		public static final Path LABEL = Path.parse("./label");
		public static final Path PURPOSE = Path.parse("./purpose");
		public static final Path KIND = Path.parse("./kind");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path ENTITY = Path.parse("./entity");
		public static final Path ZONE = Path.parse("./zone");
		public static final Path TRIGGER = Path.parse("./trigger");
		public static final Path TRIGGER_EVENT = Path.parse("./triggerEvent");
		public static final Path SCHEDULE_MINUTES = Path.parse("./scheduleMinutes");
		public static final Path ALLOWED_TOOLS = Path.parse("./allowedTools");
		public static final Path AUTONOMY = Path.parse("./autonomy");
		public static final Path CONFIDENCE_THRESHOLD = Path.parse("./confidenceThreshold");
		public static final Path OWNER_PROFILE = Path.parse("./ownerProfile");
		public static final Path MAX_ITEMS = Path.parse("./maxItems");
		public static final Path INSTRUCTIONS = Path.parse("./instructions");
		public static final Path ENABLED = Path.parse("./enabled");
		public static final Path ENABLED_BY = Path.parse("./enabledBy");
		public static final Path LAST_RUN_AT = Path.parse("./lastRunAt");
	}

	/** Agentic framework lot (2026-10-30, docs/AGENTS.md). */
	public static final class AgentProposal
	{
		private AgentProposal()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path RUN = Path.parse("./run");
		public static final Path AGENT = Path.parse("./agent");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path TOOL = Path.parse("./tool");
		public static final Path TARGET = Path.parse("./target");
		public static final Path ACTION = Path.parse("./action");
		public static final Path ARGUMENTS = Path.parse("./arguments");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path RATIONALE = Path.parse("./rationale");
		public static final Path STATUS = Path.parse("./status");
		public static final Path APPLY_AFTER_APPROVAL = Path.parse("./applyAfterApproval");
		public static final Path DECIDED_BY = Path.parse("./decidedBy");
		public static final Path DECIDED_AT = Path.parse("./decidedAt");
		public static final Path APPLY_MESSAGE = Path.parse("./applyMessage");
	}

	public static final class AgentStep
	{
		private AgentStep()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path RUN = Path.parse("./run");
		public static final Path STEP_INDEX = Path.parse("./stepIndex");
		public static final Path KIND = Path.parse("./kind");
		public static final Path SERVER = Path.parse("./server");
		public static final Path TOOL = Path.parse("./tool");
		public static final Path ARGUMENTS = Path.parse("./arguments");
		public static final Path RESULT = Path.parse("./result");
		public static final Path ERROR = Path.parse("./error");
		public static final Path DURATION_MS = Path.parse("./durationMs");
		public static final Path OCCURRED_AT = Path.parse("./occurredAt");
	}

	public static final class MatchPlan
	{
		private MatchPlan()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path ENTITY = Path.parse("./entity");
		public static final Path REQUIREMENTS = Path.parse("./requirements");
		public static final Path MATCHING_POLICY = Path.parse("./matchingPolicy");
		public static final Path MERGE_POLICY = Path.parse("./mergePolicy");
		public static final Path AUTO_MERGE = Path.parse("./autoMerge");
		/** {@code master.MatchPlanEvidence}'s own JSON shape - see that class' Javadoc. */
		public static final Path EVIDENCE = Path.parse("./evidence");
		public static final Path ORIGIN = Path.parse("./origin");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path RATIONALE = Path.parse("./rationale");
		public static final Path STATUS = Path.parse("./status");
		public static final Path MESSAGE = Path.parse("./message");
		/** Reject/repropose lot (2026-09-22): free-text reviewer comment on a REJECTED or SUPERSEDED plan. */
		public static final Path REVIEW_COMMENT = Path.parse("./reviewComment");
		/** Reject/repropose lot (2026-09-22): the requirements JSON of the plan a "Propose another" just superseded. */
		public static final Path PREVIOUS_REQUIREMENTS = Path.parse("./previousRequirements");
		/** Reject/repropose lot (2026-09-22): a MatchPlan-only fifth status value - see MATCH_PLAN_STATUS in tools/build_xsd.py. */
		public static final String STATUS_SUPERSEDED = "SUPERSEDED";
	}

	public static final class Xref
	{
		private Xref()
		{
		}

		// Volume lot 2a (2026-09-28, docs/VOLUME.md): the primary key is now the natural key (project,
		// stagingTable, stagingKey) - the auto-increment `id` is gone, there is no Xref.ID any more.
		public static final Path PROJECT = Path.parse("./project");
		public static final Path MASTER_TABLE = Path.parse("./masterTable");
		public static final Path MASTER_KEY = Path.parse("./masterKey");
		public static final Path STAGING_TABLE = Path.parse("./stagingTable");
		public static final Path STAGING_KEY = Path.parse("./stagingKey");
		public static final Path LANDING_DATASET = Path.parse("./landingDataset");
		public static final Path LANDING_TABLE = Path.parse("./landingTable");
		public static final Path LANDING_KEY = Path.parse("./landingKey");
		public static final Path SOURCE_SYSTEM = Path.parse("./sourceSystem");
		public static final Path SOURCE_ID = Path.parse("./sourceId");
		public static final Path LOAD_ID = Path.parse("./loadId");
		public static final Path OCCURRED_AT = Path.parse("./occurredAt");
		/** Reserved for the matching lot; not written by the master-load lot - see the XSD field's own comment. */
		public static final Path GOLDEN_KEY = Path.parse("./goldenKey");
	}

	public static final class TransferReject
	{
		private TransferReject()
		{
		}

		// Volume lot 3 (2026-09-28, docs/VOLUME.md task 6): the primary key is now the natural key (project,
		// entity, stage, sourceSystem, sourceId) - the auto-increment `id` is gone, there is no
		// TransferReject.ID any more (same recipe as lot 2a's Xref - see that table's own comment above).
		public static final Path PROJECT = Path.parse("./project");
		public static final Path ENTITY = Path.parse("./entity");
		public static final Path LANDING_DATASET = Path.parse("./landingDataset");
		public static final Path LANDING_TABLE = Path.parse("./landingTable");
		public static final Path SOURCE_SYSTEM = Path.parse("./sourceSystem");
		public static final Path SOURCE_ID = Path.parse("./sourceId");
		public static final Path LOAD_ID = Path.parse("./loadId");
		public static final Path STAGE = Path.parse("./stage");
		public static final Path REASONS = Path.parse("./reasons");
		// staging load and rejects lot (2026-10-17): the cause category, the grouping signature of the reasons and the raw technical text
		public static final Path CATEGORY = Path.parse("./category");
		public static final Path REASON_CODE = Path.parse("./reasonCode");
		public static final Path DETAIL = Path.parse("./detail");
		public static final Path REJECTED_AT = Path.parse("./rejectedAt");
		public static final Path RESOLVED = Path.parse("./resolved");
		// wave 10 (audit F-38): the steward's handling of a reject, see ebx.RejectReview
		public static final Path ACKNOWLEDGED = Path.parse("./acknowledged");
		public static final Path REVIEWED_BY = Path.parse("./reviewedBy");
		public static final Path REVIEWED_AT = Path.parse("./reviewedAt");
		public static final Path REVIEW_COMMENT = Path.parse("./reviewComment");
	}

	public static final class Settings
	{
		private Settings()
		{
		}

		public static final Path CODE = Path.parse("./code");
		/** Delivery lot 1: random identifier of this instance, stamped once by {@code FactoryInstaller}; see {@code delivery.OriginRule}. */
		public static final Path INSTANCE_ID = Path.parse("./instanceId");
		/** Production review 2026-11-12 (F3-02): the secret HMAC key of the agents' activations, hidden from every person; see {@code ebx.HiddenFieldRule}. */
		public static final Path AGENT_ACTIVATION_KEY = Path.parse("./agentActivationKey");
		/** Delivery lot 2: {@code DEVELOPMENT} (default) / {@code TARGET}; see {@code delivery.TargetRule}. */
		public static final Path ROLE = Path.parse("./role");
		public static final Path LLM_CONNECTOR = Path.parse("./llmConnector");
		public static final Path EBX_BASE_URL = Path.parse("./ebxBaseUrl");
		/** The OpenLineage namespace of this instance in the data catalog; see {@code ebx.LineageNamespace}. */
		public static final Path EBX_NAMESPACE = Path.parse("./ebxNamespace");
		public static final Path AGENT_USER = Path.parse("./agentUser");
		public static final Path AGENT_PASSWORD = Path.parse("./agentPassword");
		/** {@code AUTO} (default) / {@code ALLOWED} / {@code FORBIDDEN}: see {@code ebx.CredentialPolicy}. */
		public static final Path AGENT_FALLBACK = Path.parse("./agentFallback");
		/** Specific role a factory workflow launcher must hold; empty = administrators. See {@code workflow.PipelineLauncher}. */
		public static final Path PIPELINE_LAUNCHER_ROLE = Path.parse("./pipelineLauncherRole");
		/** Lot B: instance cap on what a project may send to the language model, and sensitive column patterns; see {@code ebx.LlmDataPolicy}. */
		public static final Path LLM_VALUE_SHARING_MAX = Path.parse("./llmValueSharingMax");
		public static final Path LLM_SENSITIVE_COLUMNS = Path.parse("./llmSensitiveColumns");
		/** Lot C1: background jobs - pool size (read at module start) and time limit per job; see {@code ebx.FactoryJobs}. */
		public static final Path JOBS_MAX_PARALLEL = Path.parse("./jobsMaxParallel");
		public static final Path JOB_MAX_MINUTES = Path.parse("./jobMaxMinutes");
		public static final Path HISTORY_RETENTION_DAYS = Path.parse("./historyRetentionDays");
		public static final Path MCP_CONNECTORS = Path.parse("./mcpConnectors");
		public static final Path MAX_AGENT_STEPS = Path.parse("./maxAgentSteps");
		public static final Path MAX_AGENT_TOKENS = Path.parse("./maxAgentTokens");
		public static final Path LANDING_SUFFIX = Path.parse("./landingSuffix");
		public static final Path STAGING_SUFFIX = Path.parse("./stagingSuffix");
		public static final Path MASTER_SUFFIX = Path.parse("./masterSuffix");
		public static final Path LENGTH_MARGIN = Path.parse("./lengthMargin");
		public static final Path TRANSFER_BATCH_SIZE = Path.parse("./transferBatchSize");
		/** Volume-guards lot (2026-09-28, task item 7): {@code ebx.LandingColumnProfiler}'s own EBX SQL LIMIT bound. */
		public static final Path COLUMN_PROFILE_SAMPLE_ROWS = Path.parse("./columnProfileSampleRows");
	}

	/** Explorer favorites (2026-09-23) - ported from ebx-connectors' own {@code ConnectorsPaths.Favorite}. */
	public static final class FactoryJob
	{
		private FactoryJob()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path RESOURCE = Path.parse("./resource");
		public static final Path KIND = Path.parse("./kind");
		public static final Path PARAMS = Path.parse("./params");
		public static final Path STATUS = Path.parse("./status");
		public static final Path REQUESTED_BY = Path.parse("./requestedBy");
		public static final Path VIA = Path.parse("./via");
		public static final Path REQUESTED_AT = Path.parse("./requestedAt");
		public static final Path STARTED_AT = Path.parse("./startedAt");
		public static final Path ENDED_AT = Path.parse("./endedAt");
		public static final Path STEP_ID = Path.parse("./stepId");
		public static final Path MESSAGE = Path.parse("./message");
		/** UX lot D: for a job that ended DONE_WITH_FAILURES, the items that failed (JSON, see {@code wizard.PartialFailures}). */
		public static final Path DETAIL = Path.parse("./detail");
	}

	public static final class LoadPause
	{
		private LoadPause()
		{
		}

		public static final String KIND_DISTRIBUTION_TARGET = "DISTRIBUTION_TARGET";
		public static final String KIND_MATCHING_TABLE = "MATCHING_TABLE";

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path KIND = Path.parse("./kind");
		public static final Path TARGET = Path.parse("./target");
		public static final Path OWNER_STEP = Path.parse("./ownerStep");
		public static final Path OWNER_TOKEN = Path.parse("./ownerToken");
		public static final Path PAUSED_BY = Path.parse("./pausedBy");
		public static final Path PAUSED_AT = Path.parse("./pausedAt");
	}

	public static final class Favorite
	{
		private Favorite()
		{
		}

		public static final Path USER_LOGIN = Path.parse("./userLogin");
		public static final Path TARGET_TYPE = Path.parse("./targetType");
		public static final Path TARGET_KEY = Path.parse("./targetKey");
		public static final Path CREATED_AT = Path.parse("./createdAt");
	}

	/** Distribution lot 1 (engine, SPEC-distribution.md): see {@code ebx.DistributionConfigurator}/{@code DistributionRunner}/{@code DistributionTriggerInstaller}/{@code DistributionDispatcher} and {@code distribution.DistributionArmingGate}. */
	public static final class DistributionTarget
	{
		private DistributionTarget()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path CONNECTOR = Path.parse("./connector");
		public static final Path OPERATION = Path.parse("./operation");
		public static final Path CONNECTORS_MAPPING_IDS = Path.parse("./connectorsMappingIds");
		/** Round 6: one "tablePath=destination" pair per master table, same encoding/TargetMappingList as {@link #CONNECTORS_MAPPING_IDS} - see field doc in {@code tools/build_xsd.py}. */
		public static final Path TABLE_DESTINATIONS = Path.parse("./tableDestinations");
		public static final Path MODE = Path.parse("./mode");
		public static final Path CRON = Path.parse("./cron");
		public static final Path SCOPE = Path.parse("./scope");
		public static final Path APPROVAL = Path.parse("./approval");
		public static final Path WATCHED_FIELDS = Path.parse("./watchedFields");
		public static final Path INCLUDE_CROSSWALK = Path.parse("./includeCrosswalk");
		public static final Path INCLUDE_PENDING = Path.parse("./includePending");
		public static final Path STATE = Path.parse("./state");
		public static final Path ARMED_VIA_OVERRIDE = Path.parse("./armedViaOverride");
		public static final Path FIRST_FULL_SEND_RUN_ID = Path.parse("./firstFullSendRunId");
		public static final Path LAST_SEND_AT = Path.parse("./lastSendAt");
		public static final Path LAST_STATUS = Path.parse("./lastStatus");
		public static final Path LAST_MESSAGE = Path.parse("./lastMessage");
		public static final Path SCHEDULE_KEY = Path.parse("./scheduleKey");
		public static final Path LAUNCHER_NAME = Path.parse("./launcherName");
		/** "retire notices from the merge log" (SPEC-retire.md): see {@code distribution.MergeLogWatch}/{@code ebx.MergeLogWatcher}. */
		public static final Path RETIRE_MODE = Path.parse("./retireMode");
		public static final Path RETIRE_UNDONE_MERGES = Path.parse("./retireUndoneMerges");
		public static final Path MERGE_LOG_WATERMARK = Path.parse("./mergeLogWatermark");

		public static final String MODE_REALTIME = "REALTIME";
		public static final String MODE_ON_DEMAND = "ON_DEMAND";
		public static final String MODE_SCHEDULED = "SCHEDULED";
		public static final String STATE_DRAFT = "DRAFT";
		public static final String STATE_WAITING = "WAITING";
		public static final String STATE_ARMED = "ARMED";
		public static final String STATE_PAUSED = "PAUSED";
		public static final String STATE_DISABLED = "DISABLED";
		public static final String RETIRE_MODE_NOTICE = "NOTICE";
		public static final String RETIRE_MODE_TOMBSTONE = "TOMBSTONE";
		public static final String RETIRE_MODE_NONE = "NONE";
	}

	/** Distribution lot 1: outbox row - see {@code ebx.DistributionDispatcher}. */
	public static final class DistributionEvent
	{
		private DistributionEvent()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path TARGET = Path.parse("./target");
		public static final Path TABLE_PATH = Path.parse("./tablePath");
		public static final Path RECORD_KEY = Path.parse("./recordKey");
		public static final Path CHANGE = Path.parse("./change");
		public static final Path STATUS = Path.parse("./status");
		public static final Path ATTEMPTS = Path.parse("./attempts");
		public static final Path CREATED_AT = Path.parse("./createdAt");
		public static final Path SENT_AT = Path.parse("./sentAt");
		public static final Path MESSAGE = Path.parse("./message");
		/** coordinator review fix (2026-09-24, item 3): the golden record a RETIRE event's source was merged into. */
		public static final Path SURVIVOR_KEY = Path.parse("./survivorKey");
		/** coordinator review fix (2026-09-24, item 4): see {@code distribution.RetryBackoff}/{@code ebx.DistributionDispatcher}. */
		public static final Path NEXT_ATTEMPT_AT = Path.parse("./nextAttemptAt");
		/** "retire notices from the merge log" (SPEC-retire.md): written only by {@code ebx.MergeLogWatcher} - see {@code distribution.MergeLogWatch}. */
		public static final Path REASON = Path.parse("./reason");
		public static final Path MERGE_ID = Path.parse("./mergeId");

		public static final String CHANGE_CREATE = "CREATE";
		public static final String CHANGE_MODIFY = "MODIFY";
		public static final String CHANGE_DELETE = "DELETE";
		public static final String CHANGE_RETIRE = "RETIRE";
		public static final String STATUS_PENDING = "PENDING";
		public static final String STATUS_SENT = "SENT";
		public static final String STATUS_FAILED = "FAILED";
		public static final String STATUS_SKIPPED = "SKIPPED";
		public static final String REASON_MERGE = "MERGE";
		public static final String REASON_UNMERGE = "UNMERGE";
	}

	/**
	 * Distribution round 3: paths of {@code DistributionOutbox.xsd}'s two tables - {@code /root/Event}, the
	 * per-project outbox a master-table trigger writes into (its own dataspace's own ambient transaction
	 * only - see {@link #DISTRIBUTION_OUTBOX_MODEL_PATH}), drained by {@code ebx.DistributionDispatcher}, and
	 * (volume-operations lot 8b) {@code /root/Control}, the one-row-per-project "is any REALTIME target
	 * ARMED" short-circuit {@code ebx.DistributionControlRow} maintains and {@code
	 * distribution.DistributionTriggerBody}'s generated trigger reads by primary key before ever running its
	 * SQL fallback. Deliberately NOT a foreign key to {@link DistributionTarget} - see the XSD's own
	 * Javadoc-equivalent comment.
	 */
	public static final class DistributionOutbox
	{
		private DistributionOutbox()
		{
		}

		public static final Path EVENT = Path.parse("/root/Event");

		public static final Path ID = Path.parse("./id");
		public static final Path TARGET = Path.parse("./target");
		public static final Path TABLE_PATH = Path.parse("./tablePath");
		public static final Path RECORD_KEY = Path.parse("./recordKey");
		public static final Path CHANGE = Path.parse("./change");
		public static final Path SURVIVOR_KEY = Path.parse("./survivorKey");
		public static final Path STATUS = Path.parse("./status");
		public static final Path ATTEMPTS = Path.parse("./attempts");
		public static final Path CREATED_AT = Path.parse("./createdAt");
		public static final Path NEXT_ATTEMPT_AT = Path.parse("./nextAttemptAt");
		public static final Path SENT_AT = Path.parse("./sentAt");
		public static final Path MESSAGE = Path.parse("./message");
		/** "retire notices from the merge log" (SPEC-retire.md): written only by {@code ebx.MergeLogWatcher}. */
		public static final Path REASON = Path.parse("./reason");
		public static final Path MERGE_ID = Path.parse("./mergeId");

		public static final String CHANGE_CREATE = "CREATE";
		public static final String CHANGE_MODIFY = "MODIFY";
		public static final String CHANGE_DELETE = "DELETE";
		public static final String CHANGE_RETIRE = "RETIRE";
		public static final String STATUS_PENDING = "PENDING";
		public static final String STATUS_SENT = "SENT";
		public static final String STATUS_FAILED = "FAILED";
		public static final String STATUS_SKIPPED = "SKIPPED";
		public static final String REASON_MERGE = "MERGE";
		public static final String REASON_UNMERGE = "UNMERGE";

		/** Volume-operations lot 8b: {@code /root/Control}, one row per project - see this class' own Javadoc. */
		public static final Path CONTROL = Path.parse("/root/Control");
		public static final Path CONTROL_PROJECT = Path.parse("./project");
		public static final Path CONTROL_ARMED_REALTIME_TARGETS = Path.parse("./armedRealtimeTargets");
		public static final Path CONTROL_UPDATED_AT = Path.parse("./updatedAt");
	}

	/** Audit F-02: {@code /root/AccessRight} of MdmFactory.xsd - one row gives a role of the directory one Factory right. */
	public static final class AccessRight
	{
		private AccessRight()
		{
		}

		/** The formatted profile ({@code Profile.format()}: {@code R<role>} or {@code Beveryone}) - the primary key. */
		public static final Path PROFILE = Path.parse("./profile");
		/** {@code VIEW} / {@code STEWARD} / {@code DESIGN} ({@code core.FactoryRight#grantable}). */
		public static final Path RIGHT = Path.parse("./right");
		public static final Path COMMENT = Path.parse("./comment");
	}

	/** Automatic-intake lot (2026-10-05): {@code /root/IntakeChain} of MdmFactory.xsd. */
	public static final class IntakeChain
	{
		private IntakeChain()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path STARTED_AT = Path.parse("./startedAt");
		/** Wave 10 (audit F-30): the person the chain runs for (who switched the intake on). */
		public static final Path STARTED_BY = Path.parse("./startedBy");
		public static final Path ENDED_AT = Path.parse("./endedAt");
		public static final Path REASON = Path.parse("./reason");
		public static final Path SOURCES = Path.parse("./sources");
		public static final Path ROWS_WAITING = Path.parse("./rowsWaiting");
		public static final Path WRITTEN = Path.parse("./written");
		public static final Path UNCHANGED = Path.parse("./unchanged");
		public static final Path REJECTED = Path.parse("./rejected");
		public static final Path STATUS = Path.parse("./status");
		public static final Path MESSAGE = Path.parse("./message");
		public static final Path WORKFLOW_KEY = Path.parse("./workflowKey");

		public static final String REASON_QUIET = "QUIET";
		public static final String REASON_MAX_WAIT = "MAX_WAIT";
		public static final String REASON_BATCH_COMPLETE = "BATCH_COMPLETE";
		public static final String REASON_MANUAL = "MANUAL";
		public static final String REASON_SWEEP = "SWEEP";
		public static final String STATUS_RUNNING = "RUNNING";
		public static final String STATUS_DONE = "DONE";
		public static final String STATUS_DONE_WITH_REJECTS = "DONE_WITH_REJECTS";
		public static final String STATUS_STOPPED = "STOPPED";
		public static final String STATUS_FAILED = "FAILED";
	}

	/** Automatic-intake lot (2026-10-05): paths of {@code IntakeSignal.xsd}'s only table. See {@link #INTAKE_SIGNAL_MODEL_PATH}. */
	/** Lot M-INC (2026-10-12): the journal model ({@code MasterJournal.xsd}). */
	public static final class MasterJournal
	{
		private MasterJournal()
		{
		}

		public static final Path CONTROL = Path.parse("/root/Control");
		public static final Path CHANGE = Path.parse("/root/Change");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path ENTITY = Path.parse("./entity");
		public static final Path ARMED = Path.parse("./armed");
		public static final Path ARMED_AT = Path.parse("./armedAt");
		public static final Path RECORDING_SINCE = Path.parse("./recordingSince");
		public static final Path PLAN_FINGERPRINT = Path.parse("./planFingerprint");
		public static final Path UPDATED_BY = Path.parse("./updatedBy");
		public static final Path SOURCE_SYSTEM = Path.parse("./sourceSystem");
		public static final Path SOURCE_ID = Path.parse("./sourceId");
		public static final Path CHANGED_AT = Path.parse("./changedAt");
		public static final Path CHANGES = Path.parse("./changes");
	}

	public static final class IntakeSignal
	{
		private IntakeSignal()
		{
		}

		public static final Path SIGNAL = Path.parse("/root/Signal");
		public static final Path SOURCE_SYSTEM = Path.parse("./sourceSystem");
		public static final Path LAST_ROW_AT = Path.parse("./lastRowAt");
		public static final Path UPDATED_BY = Path.parse("./updatedBy");
	}

	/** Lot C1-B: a pack chosen for a project, with the specification it is adapted to. */
	public static final class ProjectPack
	{
		private ProjectPack()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path PACK_KEY = Path.parse("./packKey");
		public static final Path PACK_VERSION = Path.parse("./packVersion");
		public static final Path OPTIONS = Path.parse("./options");
		public static final Path SPECIFICATION = Path.parse("./specification");
		public static final Path SPECIFICATION_KIND = Path.parse("./specificationKind");
		public static final Path CHOSEN_BY = Path.parse("./chosenBy");
		public static final Path STATUS = Path.parse("./status");

		public static final String KIND_TEXT = "TEXT";
		public static final String KIND_FILE = "FILE";
		public static final String KIND_MODEL = "MODEL";
		public static final String CHOSEN_BY_LLM = "LLM";
		public static final String CHOSEN_BY_USER = "USER";
		public static final String STATUS_CHOSEN = "CHOSEN";
		public static final String STATUS_GENERATED = "GENERATED";
	}

	/** Lot C1-B: one difference between a pack and the need. {@code origin}/{@code confidence}/{@code rationale}/{@code status} are the usual proposal fields. */
	public static final class Difference
	{
		private Difference()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path PASS = Path.parse("./pass");
		public static final Path NATURE = Path.parse("./nature");
		public static final Path WHAT = Path.parse("./what");
		public static final Path WHERE = Path.parse("./where");
		public static final Path PLACE_STATE = Path.parse("./placeState");
		public static final Path DETAIL = Path.parse("./detail");
		public static final Path WHY = Path.parse("./why");
		public static final Path PACK_KEY = Path.parse("./packKey");
		public static final Path BLOCK_KEY = Path.parse("./blockKey");
		public static final Path LIBRARY_FUNCTION = Path.parse("./libraryFunction");
		public static final Path WORKFLOW_TYPE = Path.parse("./workflowType");
		public static final Path APPLIED_OBJECT = Path.parse("./appliedObject");
		public static final Path ORIGIN = Path.parse("./origin");
		public static final Path CONFIDENCE = Path.parse("./confidence");
		public static final Path RATIONALE = Path.parse("./rationale");
		public static final Path STATUS = Path.parse("./status");

		public static final String PLACED = "PLACED";
		public static final String TO_PLACE = "TO_PLACE";
	}

	/** Lot C1-B: an object the generation wrote. */
	public static final class DomainObject
	{
		private DomainObject()
		{
		}

		public static final Path ID = Path.parse("./id");
		public static final Path PROJECT = Path.parse("./project");
		public static final Path KIND = Path.parse("./kind");
		public static final Path NAME = Path.parse("./name");
		public static final Path PATH = Path.parse("./path");
		public static final Path DETAIL = Path.parse("./detail");
		public static final Path CREATED_ON = Path.parse("./createdOn");
		public static final Path STEP = Path.parse("./step");
	}
}
