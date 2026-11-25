package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.review.ReviewLine;

/**
 * Everything the "Assistant de projet" wizard (PLAN.md screen 1 of the Ecrans list, mockup screen 2, 7
 * steps) needs to decide which step is reachable and what to render, as plain value objects: no EBX
 * class anywhere in this file or in the rest of the {@code com.on.ebx.factory.wizard} package, so
 * {@link WizardRules} and {@link WizardPage} are unit-tested without a running instance. The EBX-bound
 * reader that builds one of these from the factory dataset is {@code com.on.ebx.factory.ebx.WizardData}
 * (thin: reads rows, resolves foreign keys to the labels a screen needs, nothing decided there).
 * <p>
 * A brand new, not-yet-created project is represented by {@link #project} being null: every list stays
 * empty and {@link WizardRules#stepStates} reports every step but "Projet" as blocked.
 */
public final class WizardSnapshot
{
	/** Null until the project row exists (step 1 not saved yet). */
	public ProjectInfo project;
	public final List<SourceInfo> sources = new ArrayList<SourceInfo>();
	/** Every {@code StagingEntity} of the project, its attributes nested - the shape the mockup's entity cards need (step 4). */
	public final List<StagingEntityInfo> stagingEntities = new ArrayList<StagingEntityInfo>();
	/**
	 * Pipeline-buttons lot (2026-09-23): every project-scope (blank {@code PipelineStep.scope}) step of the
	 * chain the wizard now drives past staging suggestion - {@code STAGING_SUGGEST}/{@code QUALITY_SUGGEST}/
	 * {@code QUALITY_SUGGEST_LLM}/{@code CAPABILITY_SUGGEST_LLM}/{@code STAGING_MODEL}/{@code STAGING_DATASET}/
	 * {@code STAGING_LOAD}/{@code MASTER_MODEL}/{@code MASTER_DATASET}/{@code MASTER_LOAD}/
	 * {@code MATCHING_SUGGEST_LLM} - latest row per step, same shape as {@link SourceInfo#pipelineSteps} but
	 * for the whole project rather than one source. {@code MATCHING_CONFIG}/{@code MATCHING_RUN} are NOT here:
	 * they run per-entity ({@code scope} = the entity name), so they are not a single project-wide track.
	 */
	public final List<PipelineStepInfo> projectSteps = new ArrayList<PipelineStepInfo>();
	/**
	 * Pipeline-buttons lot: every {@code MatchPlan} of a promoted entity of this project (step 6) - one row
	 * per entity that has a plan at all, in no particular guaranteed order (as read from the entities list).
	 */
	public final List<MatchPlanInfo> matchPlans = new ArrayList<MatchPlanInfo>();
	/**
	 * UX lot D (2026-10-14): true when the ticked landing tables that are not loaded are exactly the ones the latest landing run reported as failed, and at
	 * least one other ticked table is loaded ({@link PartialFailures#landingGapIsKnown}). Set by the reader of the job queue, not by {@code WizardData}; read
	 * by {@link WizardRules#stepStates}: the staging step is then open (with a warning) instead of blocked until every table loads.
	 */
	public boolean landingGapIsKnown;
	/**
	 * Audit F-32 (2026-10-06): every review line of the project as {@code ProposalReviews.lines} answered while the snapshot was read (null when the
	 * snapshot did not read them - not a GREENFIELD project, or none read yet), and the locale it was read with. The snapshot already needs them to
	 * group the inline proposals; the wizard's step 6 plan cards need the same lines, so they are kept (a {@link ReviewLine} is immutable) instead of
	 * being read a second time in the same render. Never rendered.
	 */
	public List<ReviewLine> reviewLines;
	public java.util.Locale reviewLinesLocale;
	/**
	 * How many rows are still {@code PROPOSED} for this project, one count per {@code ReviewLine} nature
	 * ({@code MAPPING}/{@code MAPPING_STEP}/{@code QUALITY}/{@code SCRIPT_CONSTRAINT}/{@code MATCH}) - step
	 * 4's one-line header ("N proposal(s) waiting for review: ..."). Inline-reviews lot (2026-09-23): every
	 * nature but {@code MATCH} is now ALSO rendered in place, decidable, right under its entity - this count
	 * is the header only, never a substitute for the pointer-to-Proposal-review block it replaced (the product owner:
	 * "l'acces aux reviews devrait se faire dans l'ecran, sans avoir a le quitter").
	 */
	public final java.util.Map<String, Integer> proposedCounts = new java.util.LinkedHashMap<String, Integer>();
	/**
	 * Live bug fix (2026-09-25, PersVol2 volume test): the same natures as {@link #proposedCounts}
	 * ({@code MAPPING}/{@code MAPPING_STEP}/{@code QUALITY}/{@code SCRIPT_CONSTRAINT}), but counting every row
	 * NOT {@code REJECTED} (so {@code PROPOSED}/{@code ACCEPTED}/{@code APPLIED} all count) rather than
	 * {@code PROPOSED} only - "what this proposal run produced", for the "Proposal ready for review" summary
	 * line ({@code WizardPage#renderProposalDone}). That block stays visible after every mapping/quality rule/
	 * check has been accepted and loaded (nothing un-publishes it, see {@link #proposedCounts}'s own header
	 * use case, which legitimately needs PROPOSED-only); reading {@link #proposedCounts} there made the summary
	 * read "0 field links, 0 quality rules, 0 checks" once a person had accepted everything, even though 39
	 * mappings/7 quality rules were sitting right there, ACCEPTED and loaded.
	 */
	public final java.util.Map<String, Integer> decidedCounts = new java.util.LinkedHashMap<String, Integer>();

	/** Whether the session driving the wizard is an administrator: gates the long-running/agent-account actions (landing generate/flows/load, staging LLM pass), same rule as {@code FactoryResource}. */
	public boolean administrator;
	/**
	 * Audit F-02: whether the session holds the MDM Factory right DESIGN (administrators included) - gates the design and run
	 * actions and the language-model passes the wizard offers ({@link WizardRules#actionGates}); {@link #administrator} keeps
	 * gating what stays with the EBX administrator (resets, the arming override).
	 */
	public boolean designer;
	/** Audit F-02: the session's widest Factory right (null = none), for the read-only rendering of the wizard. */
	public com.on.ebx.factory.core.FactoryRight right;
	/** False when the data catalog module is not installed: step 2 says so instead of silently listing nothing. */
	public boolean catalogAvailable = true;
	/**
	 * Audit F-02 (catalog reads): true when something the wizard reads in the data catalog (sources, tables, columns,
	 * deduced tables, data product) exists there but the person's catalog right hides it: steps 2 and 3 then say so,
	 * instead of showing an empty list that reads as "nothing found".
	 */
	public boolean catalogRestricted;
	/** {@code FactorySettings.llmConnector} is set: gates "Affiner avec le modele de langage" in step 4. */
	public boolean llmConfigured;
	/**
	 * wizard-llm-value-sharing lot (2026-09-28): the configured language-model connector's own label (its id
	 * when it has no label, empty when none is configured) - named in step 1's "sample values" consequence
	 * line, so a person reads WHERE the values would go, not just that they would.
	 */
	public String llmConnectorLabel = "";
	/**
	 * wizard-llm-value-sharing lot (2026-09-28): {@code FactorySettings.llmValueSharingMax} as {@code
	 * ebx.LlmDataPolicy} reads it ({@code NONE}/{@code SHAPES_ONLY}/{@code SAMPLE_VALUES}, an unreadable
	 * value failing closed to {@code NONE}) - the instance cap step 1's control shows and never exceeds.
	 * Default {@code SHAPES_ONLY}, the policy's own default.
	 */
	public String llmValueSharingMax = "SHAPES_ONLY";
	/** Every catalog source available to pick from in step 2 (id, label, platform, connector), only populated when {@link #catalogAvailable}. */
	public final List<CatalogSourceInfo> catalogSources = new ArrayList<CatalogSourceInfo>();

	/**
	 * Distribution lot 3 (wizard UI, 2026-09-24): true when the project has at least one {@code
	 * DistributionTarget} in state {@code ARMED} or {@code PAUSED} (SPEC-distribution.md: "the rail shows it
	 * done when at least one target is ARMED/active") - drives the step-7 rail checkmark ({@link
	 * WizardRules#stepStates}) only. A best-effort read ({@code ebx.WizardData#readSnapshot} swallows any
	 * failure and leaves this {@code false}) so a Connectors/distribution glitch never breaks the whole
	 * wizard snapshot.
	 */
	public boolean distributionActive;

	// ---------------------------------------------------------------------------------- lot C1-B: CENTRALISATION (docs/LOT-C1.md sec 5-6)

	/** The packs of the accelerator catalogue the Pack step offers, in catalogue order; filled by {@code ebx.AccelData} (empty when no catalogue is reachable). */
	public final List<PackInfo> packCatalogue = new ArrayList<PackInfo>();
	/** The packs chosen for the project ({@code ProjectPack} rows), in id order. */
	public final List<ProjectPackInfo> projectPacks = new ArrayList<ProjectPackInfo>();
	/** Every {@code Difference} of the project, in pass then id order, KEEP lines included. */
	public final List<DifferenceInfo> differences = new ArrayList<DifferenceInfo>();
	/** What the generation wrote ({@code DomainObject} rows), in id order. */
	public final List<DomainObjectInfo> domainObjects = new ArrayList<DomainObjectInfo>();
	/** The accelerator job (a {@code FactoryJob} whose kind starts with {@code ACCEL_}) queued or running for the project; null when none. */
	public AccelJobInfo accelJob;
	/** Blank start: the person chose "Skip the packs, start blank" ({@code Project.accelBlankStart}); the language model then proposes the whole model from the need. */
	public boolean accelBlankStart;
	/** The need as stored on the project ({@code Project.accelSpecification}); empty for a project that predates the field (the pack rows then carry it). */
	public String accelSpecification = "";

	/** One pack of the catalogue, plain text in the language of the reading. {@code tables}/{@code fields} count what the pack's core creates. */
	public static final class PackInfo
	{
		public String key = "";
		public String version = "";
		public String label = "";
		public String description = "";
		public int tables;
		public int fields;
		public int referenceRows;
		public final List<String> blocks = new ArrayList<String>();
		public final List<String> options = new ArrayList<String>();
		/** Keys of the packs this one requires (reference-data for most). */
		public final List<String> requires = new ArrayList<String>();

		public PackInfo(final String pKey, final String pVersion, final String pLabel, final String pDescription)
		{
			this.key = pKey;
			this.version = pVersion;
			this.label = pLabel;
			this.description = pDescription;
		}
	}

	/** One {@code ProjectPack} row. */
	public static final class ProjectPackInfo
	{
		public String id = "";
		public String packKey = "";
		public String packVersion = "";
		public final List<String> options = new ArrayList<String>();
		public String specification = "";
		public String specificationKind = "TEXT";
		/** {@code LLM} or {@code USER}. */
		public String chosenBy = "USER";
		/** {@code CHOSEN} or {@code GENERATED}. */
		public String status = "CHOSEN";
	}

	/** One {@code Difference} row; {@code status}/{@code origin}/{@code confidence}/{@code rationale} are the usual proposal fields. */
	public static final class DifferenceInfo
	{
		public String id = "";
		public int pass = 1;
		public String nature = "ADD";
		public String what = "";
		public String where = "";
		/** {@code PLACED} or {@code TO_PLACE}. */
		public String placeState = "PLACED";
		/** The JSON the model gave (the field to add, the property to change...), as stored. */
		public String detail = "";
		public String why = "";
		public String packKey = "";
		public String blockKey = "";
		public String libraryFunction = "";
		public String workflowType = "";
		public String appliedObject = "";
		public String origin = "LLM";
		public Integer confidence;
		public String rationale = "";
		public String status = "PROPOSED";
	}

	/** One {@code DomainObject} row. {@code stepStatus} is the status of its {@code PipelineStep} ({@code DONE}, {@code RUNNING}, {@code FAILED}...), empty when it has none. */
	public static final class DomainObjectInfo
	{
		public String id = "";
		public String kind = "";
		public String name = "";
		public String path = "";
		public String detail = "";
		public String createdOn = "";
		public String stepStatus = "";
	}

	/** A queued or running accelerator job, with what the wizard shows of its progress. */
	public static final class AccelJobInfo
	{
		/** The {@code FactoryJob.kind}, e.g. {@code ACCEL_PROPOSAL}. */
		public String kind = "";
		/** {@code QUEUED} or {@code RUNNING}. */
		public String status = "RUNNING";
		/** The engine's message, or the parent step's. */
		public String message = "";
		/** The parent {@code PipelineStep}'s progress JSON ({@code StagingProposalRun} shape), empty when unknown. */
		public String progressJson = "";
	}

	public static final class ProjectInfo
	{
		public String id = "";
		public String code = "";
		public String label = "";
		public String description = "";
		/** {@code GREENFIELD} or {@code TARGET_EXISTS}; only GREENFIELD is selectable in this lot (PLAN.md, this task). */
		public String mode = "GREENFIELD";
		/** {@code CONSOLIDATION}/{@code CENTRALISATION}/{@code COEXISTENCE}/{@code REGISTRY} ({@code Project.style}); CONSOLIDATION and CENTRALISATION are selectable. */
		public String style = "CONSOLIDATION";
		public String status = "DRAFT";
		public String landingDataspace = "";
		public String stagingDataspace = "";
		/** Pipeline-buttons lot: empty until {@code master/generate} has run once. */
		public String masterDataspace = "";
		public String masterDataset = "";
		/**
		 * Live bug fix (2026-09-25, PersVol2 volume test): {@code ebx.LlmDataPolicy.Level} effective for this
		 * project ({@code NONE}/{@code SHAPES_ONLY}/{@code SAMPLE_VALUES} - the more restrictive of the
		 * instance's own maximum and this project's own {@code llmValueSharing} choice), so steps 4 and 6 can
		 * say truthfully what actually leaves this instance instead of a fixed "never row values" sentence -
		 * since 2026-09-24 the staging LLM pass sends value SHAPES of text columns (never a real value) when
		 * this level allows it, and the matching pass may send shapes or samples depending on it too (see
		 * {@code ebx.MatchPlanLlmSuggester}). Defaults to {@code SHAPES_ONLY}, the policy's own default.
		 */
		public String llmDataPolicyLevel = "SHAPES_ONLY";
		/**
		 * wizard-llm-value-sharing lot (2026-09-28): the project's OWN {@code Project.llmValueSharing} choice
		 * ({@code NONE}/{@code SHAPES_ONLY}/{@code SAMPLE_VALUES}), as stored - distinct from {@link
		 * #llmDataPolicyLevel}, which is what actually applies once the instance cap is taken into account.
		 * Step 1 shows the choice, and says so when the cap lowers it.
		 */
		public String llmValueSharing = "SHAPES_ONLY";
		/** wizard-llm-value-sharing lot (2026-09-28): {@code Project.llmSampleSize} as stored (default 20). */
		public int llmSampleSize = WizardRules.LLM_SAMPLE_SIZE_DEFAULT;
		/** Lot MT (2026-10-04): {@code Project.matchingTrigger} as stored, {@code MANUAL} (default, also when empty) or {@code REALTIME} - see {@code master.MatchingTriggerMode}. */
		public String matchingTrigger = "MANUAL";
		/** Lot C1-B: CENTRALISATION only, empty until the generation ran ({@code Project.domainDataspace}/{@code domainDataset}/{@code domainModel}). */
		public String domainDataspace = "";
		public String domainDataset = "";
		public String domainModel = "";
		/** {@code Project.owner}, shown in the traceability block. */
		public String owner = "";
	}

	public static final class CatalogSourceInfo
	{
		public final String id;
		public final String label;
		public final String platform;
		public final String connector;

		public CatalogSourceInfo(final String pId, final String pLabel, final String pPlatform, final String pConnector)
		{
			// source-combobox lot (2026-09-23): CatalogReader.text answers null for a blank label/connector; the
			// picker builds its search text and option rows from these, so they are normalised once here
			this.id = pId == null ? "" : pId;
			this.label = pLabel == null ? "" : pLabel;
			this.platform = pPlatform == null ? "" : pPlatform;
			this.connector = pConnector == null ? "" : pConnector;
		}
	}

	/** One application source of the project (step 2), with its landing plan (step 3) nested. */
	public static final class SourceInfo
	{
		public String id = "";
		public String sourceSystem = "";
		public String catalogSourceId = "";
		public String catalogSourceLabel = "";
		/**
		 * Sources-landing live fix (2026-09-24, project A / PERSONNES_SALESFORCE + PERSONNES_SAP_SAMPLE):
		 * the connector's OWN {@code type} (e.g. {@code sample-file}) - the Review screen's "kind" chip.
		 * Used to read {@code Source.platform} (a coarse catalog category - CLOUD_STORAGE/DATABASE/OTHER -
		 * measured live as {@code OTHER} for both sample-file sources above, so the chip always read "Live
		 * connection"); {@code WizardData#sourceInfo} now reads the connector's own type instead, falling
		 * back to {@code Source.platform} only when the connector row itself cannot be found. Empty when
		 * unknown.
		 */
		public String platform = "";
		public String connector = "";
		/** Same live fix: the connector's OWN {@code label} - a readable fallback for the application title when no catalog source label exists ({@link #catalogSourceId} blank). Empty when unknown. */
		public String connectorLabel = "";
		/** Wizard summary lot (2026-09-24): {@code ProjectSource.landingDataset} - the generated dataset name of this source's own landing zone (dataspace = the project's {@code landingDataspace}), used to build an "Open the tables" link from step 7. Empty until the landing model/dataset have been generated for this source. */
		public String landingDataset = "";
		/** {@code ProjectSource.trustRank}: lower wins, feeds the most-trusted-source order of the eventual merge policy. */
		public int trustRank = 100;
		public final List<String> schemas = new ArrayList<String>();
		/** Landing-walkthrough lot (2026-10-16): {@code ProjectSource.landingValidated} - the person validated this application's tables and keys at the Landing step - with who and when (empty when not validated). */
		public boolean landingValidated;
		public String landingValidatedBy = "";
		public String landingValidatedAt = "";
		public int scannedTableCount = -1;
		/** {@code SELECTED}/{@code GENERATED}/{@code LOADED}/{@code FAILED} ({@code ProjectSource.status}). */
		public String status = "SELECTED";
		public String message = "";
		public final List<TableInfo> tables = new ArrayList<TableInfo>();
		public final List<RelationInfo> relations = new ArrayList<RelationInfo>();
		/** Parents-first table names, comma-joined, from the last {@code LandingPlanner.plan} run; empty before a first plan. */
		public String loadOrder = "";
		/** Table names on a load-order cycle, comma-joined; empty when the last plan found none. */
		public String loadOrderCycle = "";
		/** True once a plan has run at least once (there may still be zero tables if the source has none scanned). */
		public boolean planned;
		/** {@code PipelineStep} rows of this source (LANDING_MODEL/LANDING_DATASET/LANDING_FLOWS/LANDING_LOAD), latest per step, for the mockup's pipeline track. */
		public final List<PipelineStepInfo> pipelineSteps = new ArrayList<PipelineStepInfo>();
		/**
		 * Fix (2026-09-27): whether the Review card may offer "Run a deduced scan" - set by {@code
		 * ebx.WizardData#attachDeducedScanEligibility} for an application with no table only (the wizard page's
		 * own model, never the background runs' snapshots); {@link DeducedScanEligibility#NOT_CHECKED} otherwise.
		 */
		public DeducedScanEligibility deducedScan = DeducedScanEligibility.NOT_CHECKED;
		/** The {@code connectors_describe} failure when {@link #deducedScan} is {@link DeducedScanEligibility#UNREACHABLE}; empty otherwise. */
		public String deducedScanError = "";
		/**
		 * UX lot U2 (2026-10-13): for an application with no planned table, the objects the catalog holds for its source and whether each
		 * carries a scanned column - the card's per-table reasons. Set by {@code ebx.WizardData#attachUnplannedAssets} (the wizard page only); empty otherwise.
		 */
		public final List<UnplannedAsset> unplannedAssets = new ArrayList<UnplannedAsset>();

		public boolean isPlanned()
		{
			return this.planned;
		}

		public boolean hasSelectedTable()
		{
			for (final TableInfo table : this.tables)
			{
				if (table.selected)
				{
					return true;
				}
			}
			return false;
		}

		public boolean allSelectedGenerated()
		{
			boolean any = false;
			for (final TableInfo table : this.tables)
			{
				if (!table.selected)
				{
					continue;
				}
				any = true;
				if ("PROPOSED".equals(table.status))
				{
					return false;
				}
			}
			return any;
		}

		public boolean allSelectedLoaded()
		{
			boolean any = false;
			for (final TableInfo table : this.tables)
			{
				if (!table.selected)
				{
					continue;
				}
				any = true;
				if (!"LOADED".equals(table.status))
				{
					return false;
				}
			}
			return any;
		}
	}

	/** One {@code PipelineStep} row: {@code step} is {@code LANDING_MODEL}/{@code LANDING_DATASET}/{@code LANDING_FLOWS}/{@code LANDING_LOAD}, {@code status} is {@code PENDING}/{@code RUNNING}/{@code DONE}/{@code FAILED}/{@code SKIPPED}. */
	public static final class PipelineStepInfo
	{
		public final String step;
		public final String status;
		public final String message;
		/**
		 * Long-passes-visible lot (2026-09-23): {@code PipelineStep.startedAt} in epoch millis, 0 when unknown
		 * - only meaningful while {@link #status} is {@code RUNNING} ({@code endedAt} is still null then), so
		 * {@link com.on.ebx.factory.wizard.WizardPage#renderTrack} can show "running since N min" instead of a
		 * bare "Running" that looks identical whether it started 10 seconds or 10 minutes ago (the product owner,
		 * 2026-09-23: clicked "Suggest script constraints" and, seeing nothing after the page re-rendered,
		 * concluded it proposed nothing - the pass was still genuinely running, 4 minutes in).
		 */
		public final long startedAtMillis;
		/**
		 * Wave 2 (2026-09-30): the row's {@code detail} JSON, set ONLY for a {@code MATCHING_RUN} step (and, UX lot U1, the project-wide {@code MATCHING_SUGGEST_LLM} one: its notes and refusals) read by {@code
		 * ebx.WizardData} (the stored matching results ride in it - {@code matching.MatchResultsSnapshot}); empty for every
		 * other step info, which never read the (up to 100 000 characters) column.
		 */
		public String detail = "";

		public PipelineStepInfo(final String pStep, final String pStatus, final String pMessage)
		{
			this(pStep, pStatus, pMessage, 0L);
		}

		public PipelineStepInfo(final String pStep, final String pStatus, final String pMessage, final long pStartedAtMillis)
		{
			this.step = pStep;
			this.status = pStatus;
			this.message = pMessage == null ? "" : pMessage;
			this.startedAtMillis = pStartedAtMillis;
		}
	}

	public static final class TableInfo
	{
		public String id = "";
		public String sourceObject = "";
		public String tableName = "";
		public boolean selected = true;
		public int columnCount;
		public long sourceRowCount;
		/** Sources-landing live fix (2026-09-24): whether {@link #sourceRowCount} is a REAL profiled value (the catalog rarely profiles a sample-file source's row count) - false means {@link #sourceRowCount} is just the unset default 0 and must not be shown as "0 rows"; see {@link TableRowCount}. */
		public boolean sourceRowCountKnown;
		/** Every known source column name of this table, position order - from {@code LandingColumn.sourceColumn} (task: a real column picker for the source key instead of free text). Empty for a table whose columns are not readable for some reason; the key editor falls back to a free-text field in that case. */
		public final List<String> columnNames = new ArrayList<String>();
		/** Source column name(s) proposed/edited as the source key, comma-joined - same single-field shape as the approved mockup's "Cle proposee" column. */
		public String keyColumns = "";
		public int keyConfidence;
		public String keyRationale = "";
		/** Confidence of a proposal kept on a table that still has NO key (below the certainty bar, {@code WizardData.recordKeyProposal}); 0 when the table has a key or no proposal. */
		public int pendingKeyConfidence;
		/** Landing key-study lot (2026-09-23): who proposed the current key - {@code RULE}, {@code LLM} or {@code USER} ({@code LandingColumn.sourceKeyOrigin} of the key columns, USER winning over LLM over RULE); empty when no key is set. */
		public String keyOrigin = "";
		/**
		 * Volume wave 2, lot V2-E (2026-10-03): the source column that carries a row's last change ({@code LandingTable.changeColumn}, empty
		 * = the table is reloaded in full) and how its values compare ({@code TIMESTAMP}/{@code MONOTONIC_ID}/{@code ORDERED_STRING}); {@link
		 * #columnTypes} maps every column of the table to its normalised type ({@code LandingColumn.inferredType}), what the wizard's selector
		 * reads to pre-select the kind of the column a person picks.
		 */
		public String changeColumn = "";
		public String changeColumnKind = "";
		public final java.util.Map<String, String> columnTypes = new java.util.LinkedHashMap<String, String>();
		/**
		 * Sources-landing lot SOURCES (2026-09-24): the primary-key column names the SOURCE ITSELF declared
		 * for this table ({@code CatalogReader.primaryKeyColumns} on the asset key {@code CatalogReader.resolveAssetKey} finds), in
		 * column order - distinct from {@link #keyColumns}/{@link #keyOrigin} (the landing's OWN key decision,
		 * RULE/LLM/USER, which may differ from what the source declares, or exist when the source declares
		 * none). Empty when the source declared no primary key for this table, or it could not be read.
		 */
		public final List<String> declaredKeyColumns = new ArrayList<String>();
		/** {@code PROPOSED}/{@code GENERATED}/{@code LOADED}/{@code DRIFTED}/{@code FAILED} ({@code LandingTable.status}). */
		public String status = "PROPOSED";
		public String lastLoadId = "";
		public long lastLoadRows;
		public String lastLoadAt = "";
		public String message = "";
		/**
		 * Deduced-scan lot (2026-09-24, read-recipe-contract.md sec 1): {@code Asset.scanOrigin}/{@code
		 * scanState}/{@code scanConfidence}/{@code scanRationale}, read live off the asset of {@code LandingTable.catalogAsset}
		 * ({@code CatalogReader.scanInfo}, itself guarded: the fields may not exist yet on an older catalog).
		 * {@link #scanOrigin} empty or {@code "OFFICIAL"} = a normally-scanned table, never blocks anything;
		 * {@code "DEDUCED"} with {@link #scanState} {@code "TO_CONFIRM"} is what {@link
		 * com.on.ebx.factory.wizard.WizardRules#hasUnconfirmedDeducedTable} checks for. {@link
		 * #scanState} empty means CONFIRMED (contract: "empty = CONFIRMED").
		 */
		public String scanOrigin = "";
		public String scanState = "";
		public int scanConfidence;
		public String scanRationale = "";
		/** UX lot U2 (2026-10-13): {@code Asset.scanConfirmedBy} and {@code scanConfirmedAt} (epoch millis, 0 = unknown) of a deduced table - "confirmed by X on D" instead of "confirmed by a person". */
		public String scanConfirmedBy = "";
		public long scanConfirmedAt;
		/** {@code Asset.readRecipe}, parsed - "How it is read" line of board 2e. Never null; a blank/unparsable recipe has empty {@code connector}/{@code operation}. */
		public com.on.ebx.factory.deducedscan.ReadRecipe readRecipe = com.on.ebx.factory.deducedscan.ReadRecipe.parse(null);
	}

	public static final class RelationInfo
	{
		public String id = "";
		public String fromTable = "";
		public String toTable = "";
		public boolean selfReference;
		/** {@code RULE}/{@code LLM}/{@code USER}/{@code DECLARED}. */
		public String origin = "RULE";
		public Integer confidence;
		public String rationale = "";
		/** {@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}. */
		public String status = "PROPOSED";
	}

	public static final class StagingEntityInfo
	{
		public String id = "";
		public String name = "";
		public String label = "";
		public String targetTable = "";
		public String origin = "RULE";
		public Integer confidence;
		public String rationale = "";
		public String status = "PROPOSED";
		/** {@code StagingEntity.memberEntities} formatted by {@code ReviewEntityFormat.unifiesText}, e.g. {@code "Supplier (ERP), Account (CRM)"}; empty when this is not a canonical (language-model) entity or its members are not yet resolved. */
		public String memberEntitiesText = "";
		/** Name of the canonical entity whose {@code memberEntities} lists this one, or empty. Only rendered when {@link #status} is {@code SUPERSEDED} (the language-model pass absorbed this rule entity). */
		public String supersededBy = "";
		public final List<AttributeInfo> attributes = new ArrayList<AttributeInfo>();
		/**
		 * Inline-reviews lot (2026-09-23): every {@code MAPPING_STEP}/{@code QUALITY}/{@code SCRIPT_CONSTRAINT}
		 * proposal that concerns this entity or one of its attributes/mappings - read once for the whole
		 * project ({@code ProposalReviews.lines}, the SAME reader the Proposal review screen itself uses, never
		 * a second one) and grouped here by {@code WizardData} so step 4 can decide them in place instead of
		 * pointing at that other screen (the product owner, 2026-09-23: "l'acces aux reviews devrait se faire dans
		 * l'ecran, sans avoir a le quitter").
		 */
		public final List<ProposalLineInfo> proposalLines = new ArrayList<ProposalLineInfo>();
		/**
		 * Target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): every {@code TransferFeed} row of THIS entity -
		 * a root feed (empty {@link FeedInfo#parentFeedId}) or a child feed of some OTHER entity's own feed
		 * (the parent feed lives on the PARENT entity's card, per {@code TransferFeed.entity} - a child feed
		 * never appears twice). Rendered as its own block, separate from {@link #proposalLines} (a tree with
		 * editable pipeline/{@code sourceIdPipeline} fields, not a generic accept/reject table row) - see
		 * {@code WizardPage#renderFeeds}.
		 */
		public final List<FeedInfo> feeds = new ArrayList<FeedInfo>();
		/**
		 * Staging-still-to-decide lot (2026-09-27, project ret): every {@code CapabilitySuggestion} of nature
		 * {@code EXTERNAL_SERVICE} of this entity, whatever its status. Kept apart from {@link #proposalLines}
		 * on purpose: those lines render in the entity cards with plain Accept/Reject, while a service
		 * suggestion is advice about a connector a person may not be able to run yet - step 4's "decisions
		 * left" card offers "Set aside"/"Configure" for it instead ({@link StillToDecide}), and nothing else in
		 * the wizard rendered these rows before this lot (they only ever appeared on the Proposal review
		 * screen, which is exactly why they looked like loose ends nobody knew how to close).
		 */
		public final List<CapabilityInfo> capabilities = new ArrayList<CapabilityInfo>();
	}

	/**
	 * One {@code CapabilitySuggestion} row of nature {@code EXTERNAL_SERVICE} - see {@link
	 * StagingEntityInfo#capabilities}. Raw pieces, not a pre-formatted {@link ReviewLine}: the "decisions
	 * left" card turns {@link #ready}/{@link #binding} into its own readiness chips ({@link
	 * StillToDecide#queryToWrite}), which a single rationale string could not carry.
	 */
	public static final class CapabilityInfo
	{
		public String id = "";
		/** Name of the attribute the suggestion is anchored on - empty for an entity-level suggestion. */
		public String attributeName = "";
		/** Id of that attribute - empty for an entity-level suggestion. */
		public String attributeId = "";
		public String connectorId = "";
		public String connectorOperation = "";
		/** {@code ProposalWording.externalServiceProposition} - the same plain words the Proposal review screen shows. */
		public String proposition = "";
		/** {@code CapabilitySuggestion.ready}: {@code false} only when the row says so explicitly (a missing value reads as ready, same convention as {@code ProposalReviews}). */
		public boolean ready = true;
		public String readinessDetail = "";
		/** {@code CapabilitySuggestion.binding}, raw JSON - read by {@link StillToDecide#queryToWrite}. */
		public String binding = "";
		/** {@code FREE}/{@code FREEMIUM}/{@code PAID}/{@code UNKNOWN}, or empty. */
		public String pricing = "";
		public String pricingNote = "";
		public String rationale = "";
		public Integer confidence;
		/** {@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}/{@code SET_ASIDE}. */
		public String status = "PROPOSED";
	}

	/**
	 * One {@code TransferFeed} row, read once per entity (target-mapping lot 3, docs/TARGET-MAPPING.md sec 4.3)
	 * - see {@link StagingEntityInfo#feeds}. Unlike {@link ProposalLineInfo} this is NOT a {@link ReviewLine}
	 * wrapper: the wizard renders the feed tree and its editable pipeline/{@code sourceIdPipeline} fields
	 * directly, so the raw pieces (landing table name, source system, mappings) are more useful here than one
	 * pre-formatted {@code ReviewLine.rationale} string.
	 */
	public static final class FeedInfo
	{
		public String id = "";
		public String landingTableName = "";
		public String sourceSystem = "";
		/** Empty for a root feed; the parent feed's row id for a child feed (docs/TARGET-MAPPING.md sec 2.1). */
		public String parentFeedId = "";
		/** Child only: the name of the PARENT entity (resolved through the parent feed's own {@code entity}) - empty for a root feed. */
		public String parentEntityName = "";
		/** Child only: {@code TransferFeed.parentLinkField} - empty for a root feed. */
		public String parentLinkField = "";
		/** {@code TransferFeed.sourceIdPipeline} - meaningful for both a root and a child feed (docs/TARGET-MAPPING.md sec 2.1), empty means the loader's own default for that feed kind. */
		public String sourceIdPipeline = "";
		/** {@code RULE}/{@code LLM}/{@code USER}. */
		public String origin = "RULE";
		public Integer confidence;
		/** {@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}. */
		public String status = "PROPOSED";
		public final List<FeedMappingInfo> mappings = new ArrayList<FeedMappingInfo>();
		/**
		 * Feed-designer lot (2026-09-23): the "Designer" link's URL ({@code core.FeedDesignerLink#url}) - set
		 * only on a ROOT feed (empty {@link #parentFeedId}), by {@code ProjectWizardUserService#model} (this
		 * class stays a pure reader, no URL-building of its own). Empty means "no link" - a child feed never
		 * gets one of its own (its tree opens from its root feed's designer instead).
		 */
		public String designerUrl = "";
		/**
		 * Mapping-matrix lot (2026-09-23, docs/MAPPING-MATRIX.md): the "Mapping matrix" link's URL ({@code
		 * core.MappingMatrixLink#url}) opened on this feed's own entity - set on every root feed the same way
		 * {@link #designerUrl} is (by {@code ProjectWizardUserService#model}, never by this pure reader). Empty
		 * means "no link", same convention as {@link #designerUrl}.
		 */
		public String matrixUrl = "";
	}

	/** One {@code AttributeMapping} row of a {@link FeedInfo}, editable in place - see that field's own Javadoc. */
	public static final class FeedMappingInfo
	{
		public String id = "";
		public String landingColumn = "";
		public String attributeName = "";
		/** {@code AttributeMapping.pipeline} - empty when this rule still uses {@code transformation}/{@code argument} ({@link #transformationDisplay}) instead. */
		public String pipeline = "";
		/** {@code transformation} (+ its {@code argument} in parentheses when set) - the fallback display/placeholder when {@link #pipeline} is empty. */
		public String transformationDisplay = "";
		/** {@code RULE}/{@code LLM}/{@code USER}. */
		public String origin = "RULE";
		public Integer confidence;
		/** {@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}. */
		public String status = "PROPOSED";
	}

	/**
	 * A {@link ReviewLine} of nature {@code MAPPING_STEP}/{@code QUALITY}/{@code SCRIPT_CONSTRAINT}, wrapped
	 * with the extra fields step 4 needs that {@link ReviewLine} itself does not carry (it is shared with the
	 * Proposal review screen, which does not need them) - see {@link StagingEntityInfo#proposalLines}.
	 */
	public static final class ProposalLineInfo
	{
		public final ReviewLine line;
		/**
		 * {@code QualityRule.severity} lot (2026-09-23, coordinator finding on project Personnes): the row's
		 * CURRENT severity ({@code WARN}/{@code REJECT}/{@code MODEL_CONSTRAINT}) - {@code QUALITY} lines
		 * only, empty for every other nature. {@code ReviewLine} folds severity into its own proposition text
		 * only; this is the raw value the wizard's severity control pre-selects, read directly off the
		 * {@code QualityRule} row - the product owner: "les regles de validation ne devraient pas s'appliquer au
		 * staging pour ne pas laisser arriver en master des donnees erronees?" - REJECT is the mechanism that
		 * actually stops a row at transfer time (StagingLoader/MasterLoader), so a person must be able to set
		 * it from where the rule is reviewed, not only read it.
		 */
		public String severity = "";
		/**
		 * {@code QUALITY} lines only: {@code ProposalWording.qualityRuleEvidence}/{@code qualityRuleLastLoad} -
		 * the same wording {@code ReviewLine.rationale} already folds these numbers into, surfaced again as its
		 * own short line right next to the severity control so a person sees what a REJECT would throw away
		 * before choosing it, without having to open the rationale details first.
		 */
		public String qualityEvidence = "";
		/**
		 * quality-script-rule lot (2026-09-23): the row's CURRENT {@code CapabilitySuggestion.transferPolicy}
		 * ({@code ""}/{@code "WARN"}/{@code "REJECT"}) - {@code SCRIPT_CONSTRAINT} lines of zone {@code STAGING}
		 * only, empty otherwise (a {@code MASTER}-zone constraint has nothing to enforce at transfer, see {@code
		 * ebx.MasterLoader}'s own Javadoc). The wizard's transfer-policy control pre-selects this value, the
		 * same "read the raw value, let a person set it from here" idea {@link #severity} already established
		 * for {@code QUALITY} lines.
		 */
		public String transferPolicy = "";
		/** {@code SCRIPT_CONSTRAINT} lines of zone {@code STAGING} only: {@code lastLoadChecked}/{@code lastLoadFailing} at the last transfer, right next to the transfer-policy control - same idea as {@link #qualityEvidence}. */
		public String capabilityEvidence = "";
		/** {@code true} exactly for a {@code SCRIPT_CONSTRAINT} line whose {@code CapabilitySuggestion.zone} is {@code STAGING} - the wizard draws the transfer-policy control only then (a {@code MASTER}-zone constraint has nothing to enforce at transfer, see {@code ebx.MasterLoader}'s own Javadoc); distinct from {@link #transferPolicy} being blank, which can also mean "STAGING but unenforced". */
		public boolean capabilityStaging = false;
		/**
		 * apply-visibility lot (2026-09-23, coordinator finding on project Personnes): {@code CapabilitySuggestion
		 * .zone} ({@code STAGING}/{@code MASTER}) - {@code SCRIPT_CONSTRAINT} lines only, empty otherwise. Drives
		 * both {@link #capabilityStaging} and {@code WizardRules#capabilityApplyGate} (which zone's model must
		 * exist before Apply can be attempted).
		 */
		public String zone = "";
		/**
		 * apply-visibility lot: {@code CapabilitySuggestion.applyMessage} - non-empty exactly when the LAST apply
		 * attempt (on an {@code ACCEPTED}, not-yet-{@code APPLIED} row) failed, or (on an {@code APPLIED} row) a
		 * best-effort step after the apply itself (explanation/measurement) fell short - never rendered before
		 * this lot ("Pourquoi le bouton Apply des script constraints ne s'enleve jamais ?": id 10-14, zone
		 * MASTER, stuck ACCEPTED because the master model does not exist yet - the reason was there all along,
		 * on the row, just never shown). {@code SCRIPT_CONSTRAINT} lines only, empty otherwise.
		 */
		public String applyMessage = "";
		/**
		 * Lot P1 (2026-10-04): {@code CapabilitySuggestion.binding}, raw JSON - read for a {@code SCRIPT_DERIVATION} line only (the parameters a person may still
		 * settle, {@code capability.DerivationParameters#settable}); empty for every other nature.
		 */
		public String binding = "";
		/** apply-visibility lot: {@code CapabilitySuggestion.appliedModel} - set only once {@link ReviewLine#status} is {@code APPLIED}. */
		public String appliedModel = "";
		/** apply-visibility lot: {@code CapabilitySuggestion.appliedAt}, plainly formatted - set only once {@link ReviewLine#status} is {@code APPLIED}. */
		public String appliedAt = "";
		/**
		 * Staging-still-to-decide lot (2026-09-27): {@code QualityRule.attribute} ({@code QUALITY} lines only,
		 * empty for an entity-level rule and for every other nature) - lets the "decisions left" card tell a rule
		 * whose field is itself still {@code PROPOSED} ("decided with the field") from one decidable on its own.
		 */
		public String attributeId = "";
		/** {@code QualityRule.valuesSeen}/{@code valuesFailing} ({@code QUALITY} lines only, null when never measured) - the card's "measured" chips, raw so they are never re-parsed out of {@link #qualityEvidence}. */
		public Long valuesSeen;
		public Long valuesFailing;

		public ProposalLineInfo(final ReviewLine pLine)
		{
			this.line = pLine;
		}
	}

	/**
	 * Quality-block-bulk lot (2026-09-23): what one bulk accept/reject sweep is restricted to - {@code
	 * entityId} null sweeps the whole project (the two page-wide buttons, unchanged); non-null restricts to
	 * one entity's own card. {@code natures} null covers every nature the sweep already knew about (ENTITY/
	 * ATTRIBUTE/MAPPING plus every {@link StagingEntityInfo#proposalLines} nature - the "Accept all of this
	 * entity" card button); a non-null set restricts the inline-proposal-line half of the sweep to exactly
	 * those natures and skips ENTITY/ATTRIBUTE/MAPPING entirely - the "Accept all quality proposals of this
	 * entity" button, {@code {MAPPING_STEP, QUALITY}}. Replaces a bare entity-id string parameter so a third
	 * bulk variant is a new {@code BulkFilter}, never a third copy of the sweep itself.
	 */
	public static final class BulkFilter
	{
		public final String entityId;
		public final java.util.Set<String> natures;
		/**
		 * Threshold-left-out lot (2026-09-23, coordinator finding on project Personnes): a person who
		 * genuinely wants to sweep everything of a block, sub-threshold rows included, ticks a checkbox next
		 * to the card's own buttons rather than raising the shared threshold input (which would also loosen
		 * the page-wide buttons and every other entity's card). Always {@code false} for the two page-wide
		 * buttons (the checkbox is never offered there - "keep the threshold semantics for the page-wide
		 * buttons").
		 */
		public final boolean includeBelowThreshold;

		public BulkFilter(final String pEntityId, final java.util.Set<String> pNatures, final boolean pIncludeBelowThreshold)
		{
			this.entityId = pEntityId;
			this.natures = pNatures;
			this.includeBelowThreshold = pIncludeBelowThreshold;
		}

		/** The two page-wide buttons: the whole project, every nature, threshold strictly enforced. */
		public static BulkFilter wholeProject()
		{
			return new BulkFilter(null, null, false);
		}
	}

	public static final class AttributeInfo
	{
		public String id = "";
		public String name = "";
		/** {@code StagingAttribute.label} - empty when none was proposed (staging-still-to-decide lot, 2026-09-27: "label + technical name" on the decisions-left card). */
		public String label = "";
		public String dataType = "STRING";
		public Integer maxLength;
		public boolean mandatory;
		/** {@code StagingAttribute.businessKey}: the SOURCE's own key (e.g. {@code contactId}). Distinct from {@link #primaryKey}. */
		public boolean businessKey;
		/** {@code StagingAttribute.primaryKey}: the entity's technical identity ({@code sourceSystem}/{@code sourceId}). Has no decision of its own - it follows its entity's (see {@code com.on.ebx.factory.staging.TechnicalKeyRule}). */
		public boolean primaryKey;
		/** The structural link of a child entity to its parent ({@code com.on.ebx.factory.staging.ParentLinkRule}): {@code Adresse.personne}. */
		public boolean parentLink;

		/** No decision of its own, it follows its entity: the technical key ({@link #primaryKey}) or the parent link ({@link #parentLink}). */
		public boolean followsEntity()
		{
			return this.primaryKey || this.parentLink;
		}
		/** Name of the entity {@code referenceEntity} points to, or empty when this attribute is not a foreign key. */
		public String referenceEntityName = "";
		public String origin = "RULE";
		public Integer confidence;
		public String rationale = "";
		public String status = "PROPOSED";
		/**
		 * Bulk-mapping-cascade lot (2026-09-23, coordinator finding on project Personnes): every
		 * {@code AttributeMapping} row of this attribute - {@code ProjectWizardUserService.bulkStaging} needs
		 * the real rows (not just a count) to decide each one still {@code PROPOSED} in the same bulk sweep
		 * {@code ReviewRules}/{@code MappingFollowRule} already promise ("entities first, then their
		 * attributes, then the attributes' mappings").
		 */
		public final List<MappingInfo> mappings = new ArrayList<MappingInfo>();
		/** {@link #mappings}{@code .size()} - convenience for {@link WizardPage}, never set independently. */
		public int mappingCount;
		/** How many of {@link #mappings} are NOT {@code REJECTED} - still capable of feeding this attribute. */
		public int mappingFeedingCount;
		/**
		 * {@code ReviewRules.attributeMappingWarning}'s own text (same rule, same wording as the review
		 * screen - not re-derived), or empty: this attribute is {@code ACCEPTED}/{@code APPLIED} but every
		 * mapping that could feed it is {@code REJECTED} - a real field with an accepted decision behind it
		 * and no data path into it (coordinator finding, project Personnes, 2026-09-23: a bulk reject/re-accept
		 * cycle can leave every business attribute exactly in this state although each one reads "Accepted").
		 */
		public String mappingWarning = "";
	}

	/** One {@code AttributeMapping} row, nested under its {@link AttributeInfo} - see that field's own Javadoc. */
	public static final class MappingInfo
	{
		public String id = "";
		/** {@code RULE}/{@code LLM}/{@code USER}. */
		public String origin = "RULE";
		public Integer confidence;
		/** {@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}. */
		public String status = "PROPOSED";
	}

	/** One {@code MatchPlan} row (step 6) - see {@link WizardSnapshot#matchPlans}. */
	public static final class MatchPlanInfo
	{
		public String id = "";
		public String entityName = "";
		/** {@code RULE}/{@code LLM}/{@code USER}. */
		public String origin = "RULE";
		public Integer confidence;
		public String rationale = "";
		/** {@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}/{@code SUPERSEDED}. */
		public String status = "PROPOSED";
		/** {@code MatchPlan.requirements}: what makes two records the same, in plain words. */
		public String requirements = "";
		public String message = "";
		public String reviewComment = "";
		/**
		 * Long-passes-visible lot (2026-09-23): the latest {@code MATCHING_RUN} {@code PipelineStep} of this
		 * entity ({@code scope} = the entity name - {@code MATCHING_RUN} is per-entity, unlike every step in
		 * {@link WizardSnapshot#projectSteps}), null when it has never run. Read so step 6 can show "running
		 * since N min" instead of nothing while a run is still in progress (coordinator finding, project
		 * Personnes: a long-running pass with no visible progress read as "did nothing").
		 */
		public PipelineStepInfo runStep;
	}

	/**
	 * Wizard-visual-pass lot (2026-09-24, docs/mockups/wizard-flow/StagingLoad.dc.html + MasterDone.dc.html):
	 * the generic "business object x column" grid both boards show - step 4e's columns are one per
	 * application plus "In staging" ({@code ebx.StagingLoadGridReader}), step 5's done view columns are the
	 * three build stages ({@code ebx.MasterBuildGridReader}). One shape for both, since the rendering
	 * ({@code WizardPage#renderRunGrid}) does not need to know which - only {@link #columnLabels} (already
	 * localized text, not keys - built by the {@code ebx.*} reader, which has the {@code Locale}) and each
	 * {@link RunGridRow#cells}, aligned by position with {@link #columnLabels}.
	 */
	public static final class RunGridInfo
	{
		public final List<String> columnLabels = new ArrayList<String>();
		public final List<RunGridRow> rows = new ArrayList<RunGridRow>();
	}

	/** One row of a {@link RunGridInfo} - one business object. */
	public static final class RunGridRow
	{
		public String label = "";
		/** e.g. "9 fields - 6 rules and checks"; empty when not meaningful for this grid. */
		public String sublabel = "";
		public final List<RunGridCell> cells = new ArrayList<RunGridCell>();
	}

	/** One cell of a {@link RunGridInfo} - {@link #state} is {@code DONE}/{@code RUNNING}/{@code WAITING}/{@code NEEDS_PERSON}, the same vocabulary {@code WizardPage#renderStepStateCircle} already draws. */
	public static final class RunGridCell
	{
		public String state = "WAITING";
		/** e.g. "165 rows", "Created", "Ready" - blank renders no primary line. */
		public String primaryText = "";
		/** e.g. "3 held back", "from 2 applications" - blank renders no secondary line. */
		public String secondaryText = "";
		/** The raw row count {@link #primaryText} was formatted from, when it is a row count - null otherwise (e.g. "Created"/"Ready" cells). Lets a caller build a KPI tile from real data without re-parsing the localized text. */
		public Long primaryValue;
		/** Staging-screens lot (2026-10-17): the raw count {@link #secondaryText} says "held back" from, when this cell holds any - lets step 4's top banner name the business object and its rows. Null otherwise. */
		public Long heldBackValue;

		public RunGridCell()
		{
		}

		public RunGridCell(final String pState, final String pPrimaryText, final String pSecondaryText)
		{
			this.state = pState;
			this.primaryText = pPrimaryText == null ? "" : pPrimaryText;
			this.secondaryText = pSecondaryText == null ? "" : pSecondaryText;
		}
	}
}
