package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.capability.ConstraintBodyBuilder;
import com.on.ebx.factory.capability.MeasurementMatch;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.RunTexts;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.master.DeferredFlushOutcome;
import com.on.ebx.factory.master.JournalConsumption;
import com.on.ebx.factory.master.MasterLoadScope;
import com.on.ebx.factory.master.PlanFingerprint;
import com.on.ebx.factory.master.MasterLoadOutcome;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.ParentKeyCache;
import com.on.ebx.factory.master.MasterTransferPlan;
import com.on.ebx.factory.master.MasterUpsertDecision;
import com.on.ebx.factory.master.SurvivingRecord;
import com.on.ebx.factory.quality.ScriptConstraintCheck;
import com.on.ebx.factory.rejects.RefusalHints;
import com.on.ebx.factory.rejects.RejectCategory;
import com.on.ebx.factory.rejects.RejectClassifier;
import com.on.ebx.factory.rejects.RejectReason;
import com.on.ebx.factory.rejects.RejectedParents;
import com.on.ebx.factory.rejects.RejectReasons;
import com.on.ebx.factory.rejects.RejectText;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.transfer.BatchCircuitBreaker;
import com.on.ebx.factory.transfer.BatchRetry;
import com.on.ebx.factory.transfer.FieldAvailabilityRule;
import com.on.ebx.factory.transfer.RowDigest;
import com.on.ebx.factory.transfer.StoredValueEquality;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.onwbp.base.text.Severity;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValidationReport;
import com.orchestranetworks.service.ValidationReportItem;
import com.orchestranetworks.service.ValidationReportItemIterator;
import com.orchestranetworks.service.ValidationReportItemSubjectForAdaptation;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The staging -&gt; master transfer engine of one project (`docs/MASTER-ZONE.md` §3, `PLAN.md` "Zone MDM"
 * lot 3): for every promoted entity of {@code master.MasterTransferPlan}'s load order, streams its own
 * staging table (never a member's - `master.MasterModel`'s own Javadoc), decides insert / update / skip per
 * row through {@code master.MasterUpsertDecision}, and writes into the master zone. Thin on purpose, like
 * {@code ebx.StagingLoader}: every decision that does not need EBX lives in {@code master.MasterTransferPlan}/
 * {@code master.MasterUpsertDecision}, both pure and unit-tested without a running instance; this class only
 * drives the reads/writes around them, and reuses {@code transfer.BatchRetry} for the same "one bad row must
 * not lose the batch" behaviour {@code StagingLoader} already relies on.
 * <p>
 * <b>Identity of a source record in master: {@code Xref}, never a table scan.</b> For each staged row, its own
 * formatted primary key ({@code sourceSystem}+{@code sourceId}, read directly off the live staging
 * {@code Adaptation} - no re-computation needed, unlike {@code StagingLoader}'s own landing-row-only starting
 * point) is looked up against {@code Xref} (project, {@code stagingTable} = the entity name, {@code stagingKey}
 * = that formatted key). An empty {@code Xref.masterKey} means insert; a non-empty one means the record already
 * reached master once - {@link MergeCheck#isMergedAway} decides update vs. skip from there
 * ({@code master.MasterUpsertDecision}, see its own Javadoc for the three-outcome table and why "merged" is
 * checked before ever attempting a write).
 * <p>
 * <b>How "merged away" is determined - {@link MergeCheck}, {@link RecordMetadataMergeCheck}.</b> <b>Refactored
 * 2026-09-24 (CLAUDE.md &sect;9 "use the public API wherever it can do the job") to stop reading the add-on's
 * metadataset directly.</b> Until this fix {@link RecordMetadataMergeCheck} looked the metadataset table
 * {@code RecordMetadata} up by name (`docs/MASTER-ZONE.md` §1.2) and read its {@code goldenId} field
 * (non-null meaning merged away) - a bare string read that depended on {@code MameMetadataset}'s naming
 * convention (one per TABLE, {@code <masterDatasetName>_<tableLastStep>_MDS} in the same dataspace as the
 * master dataset, only measured live 2026-09-22) staying correct. It now resolves the master record itself
 * (the same {@code masterTable.lookupAdaptationByPrimaryKey} lookup {@code ebx.RecordLineageReader}/{@code
 * ebx.GoldenKeyRefresher} already use) and asks {@link MameRecordState#read} for its state - {@code
 * MetadataHelper.getState}, public API, State {@code MERGED} meaning merged away. {@code mergeCheck} is still
 * built ONCE PER ENTITY inside the load loop (`master.MasterModel.tablePath(entity.name)` for the table path),
 * not once for the whole run - see the call site's own comment; only what it looks up has changed. When the
 * master table is not found at all (matching has never been activated for this project - true for every
 * project this lot alone can produce, since {@code activate_matching} is a later lot, `docs/MASTER-ZONE.md`
 * lot 4) or the record itself carries no metadata yet, {@link RecordMetadataMergeCheck} answers "not merged"
 * for every key: nothing COULD be merged yet, so this is correct, not merely a fallback.
 * <p>
 * <b>Foreign keys, already redirected by {@code MasterModel} to the promoted (canonical) entity.</b> A field's
 * raw staging value is the ATTRIBUTE name ({@code MasterTransferPlan.FieldPlan#stagingField}), resolved here to
 * the real staging {@code <attribute>Ref} FIELD name through a map built once from
 * {@code StagingModel.build(...).foreignKeys} (the exact same computation {@code StagingLoader} itself performs
 * to get {@code TransferPlan.MappingInfo#foreignKeyField} - read-only, no EBX write, just recomputing the
 * deterministic field-naming pass). The staging Ref field's own value - a formatted staging primary key, e.g.
 * {@code "CRM|001N9Jkq..."} - is looked up against {@code Xref} keyed by (project, {@code stagingTable} =
 * {@code FieldPlan#targetEntityName} [already the redirected canonical entity - never the original member],
 * that same key value unmodified): this is the "reuse the identical key value, only re-target the lookup"
 * insight `docs/MASTER-ZONE.md` §2.3 worked out, made concrete. A resolved {@code Xref} row's
 * {@code masterKey} becomes the master FK field's value (a plain decimal string, the target's own {@code id});
 * an UNRESOLVED reference (the staging Ref field is set, but {@code Xref} does not yet know that key - the
 * parent has not reached master yet, or this really is a dangling reference) rejects the WHOLE child row into
 * {@code TransferReject} with {@code stage = MASTER} - the same "reject the row, never guess" posture
 * {@code StagingLoader.planRow} already applies to an immediate (non-deferred) landing -&gt; staging reference.
 * A staging Ref field that is simply {@code null} (legitimately no parent) leaves the master field {@code null}
 * too - not a reject. When the parent's source record has since been merged away by Match and Merge, the key
 * written is the surviving golden record's, not the merged one's ({@link ParentKeys}, {@code
 * master.SurvivingRecord}, counted {@code referencesToGolden}) - otherwise a reload re-points a child the
 * add-on had already aligned on the golden back to the merged source record.
 * <p>
 * <b>Self-references and cycles among promoted entities are handled with the same two-pass approach
 * {@code transfer.TransferPlan}/{@code StagingLoader} already use for landing -&gt; staging</b> (a coordinator
 * review, 2026-09-20, of the first version of this class, which left them silently unresolved): {@code
 * master.MasterTransferPlan} classifies every FK edge as immediate or {@link MasterTransferPlan.FieldPlan#deferred}
 * by reusing {@code TransferPlan.loadOrder}'s own classification, never re-deriving it. {@code planRow} (pass 1)
 * never even attempts a deferred field - it is left {@code null} unconditionally, since its target (possibly the
 * SAME entity's own row) does not necessarily have a master key yet. {@link #resolveDeferredReferences} (pass 2,
 * run once every promoted entity has completed pass 1) re-reads the entity's own staging rows a second time and
 * resolves each deferred field the same way an immediate one is resolved, then patches just that field on the
 * already-written master row. None exist in the reference demo ({@code Company}/{@code Contact} are a plain
 * parent/child pair), but the real staging data already has one ready to hit this path the moment a project
 * without a canonical {@code Company} promotes {@code Account} directly: {@code Account.parentAccountId} is a
 * self reference in staging (`PLAN.md`, lot 3, the auto-reference relation confirmed at confidence 80).
 * <p>
 * <b>{@code _loadId}/{@code _receivedAt} are carried through from the staging row</b> (provenance-preserving,
 * the same design choice {@code StagingLoader.RowPlan#receivedAt} already makes one hop earlier) - never a
 * freshly minted "master load id": {@code master.MasterModel} adds no such concept, and this lot does not
 * invent one.
 * <p>
 * <b>Only a row whose value changes is written (lot V3-M, 2026-10-04, docs/VOLUME.md "lot V3-M").</b> Until
 * this lot every update row was rewritten at every load, always with a new {@code _loadId}/{@code _receivedAt},
 * so EBX saw a real change on each of them (index write, the Match and Merge table trigger, the derivation
 * triggers) and a second {@code doModifyContent} followed on the Factory's own {@code Xref}: a reload of 1,465,857
 * master rows with NOTHING changed in staging took 436 to 641 s (measured on project Vol1m, 2026-10-04).
 * {@link #planRow} now reads the existing master record ONCE, outside any {@code Procedure} (the same read also
 * answers the merged-away question - {@link MergeCheck#isMergedAway(String, Adaptation)}), compares every planned
 * value with the stored one ({@code transfer.StoredValueEquality}) and, when nothing differs, counts the row
 * {@link Counters#unchanged} and buffers nothing: a batch made only of such rows opens no {@code Procedure} at
 * all. A row that does differ is written exactly as before (every planned field plus {@code _loadId} and
 * {@code _receivedAt}), reusing the record already read - never read a second time inside the {@code Procedure}.
 * Consequence: on the master, {@code _loadId}/{@code _receivedAt} now say "the load that last CHANGED this row"
 * (what a staging row's own pair already means for a row {@code transfer.IncrementalTransfer} did not rewrite).
 * What does not change: the master after a load holds the same business values as before this lot - a value
 * edited directly in the master still differs from staging, so it is still overwritten; a MERGED record is still
 * skipped; a golden record created by the add-on has no staging row and is never touched.
 * <p>
 * <p>
 * <b>One streaming read per table instead of three random reads per row (lot V3-M2, 2026-10-04, docs/VOLUME.md "Lot V3-M2").</b> After lot V3-M a reload with
 * nothing changed still took 199-263 s on Vol1m, all of it in the reads of {@link #planRow}: the {@code Xref} row, the master record, the add-on's state of it
 * (and one more {@code Xref} lookup per foreign key of a child row). {@link MasterLoadReads} now builds, before an entity's first row, an {@code Xref} index
 * ({@code stagingKey -> masterKey}), a digest of the compared values of every master record ({@code transfer.RowDigest}, the equivalence of {@code
 * StoredValueEquality}) and the set of merged-away master keys, each by ONE streaming request; an unchanged row is then a few hash lookups and no read. A row
 * whose digest differs, an entity above {@code ScanBudget}'s row bound and a failed index build all take the lot V3-M path unchanged ({@code readMode}
 * {@code LOOKUP} in the step detail). Pass 2 and a child entity's foreign keys read the parent's {@code Xref} index, which follows the writes.
 * <p>
 * <b>Scope {@code changed} (lot M-INC, 2026-10-12, docs/VOLUME.md "Lot M-INC").</b> A load used to read the whole staging table of every entity. Generated staging triggers now journal
 * every created or modified staging record ({@link MasterJournal}); with {@code scope=changed} an entity whose journal can stand for a full read ({@link MasterLoadScope#decide}) loads only
 * the journaled rows and the rows of its open MASTER rejects - by primary-key lookup up to {@code mdm.factory.masterLoad.journalLookupMax} keys, in LOOKUP mode with no index, else by one
 * filtered scan - and every other entity is loaded in full, saying why. {@link #chooseScope} decides per entity (and starts recording/disarms a full one before it reads a row), {@link #finishJournal}
 * consumes and arms at the end of a load that ended normally. The journal is installed by the first load of any scope; a problem with it costs speed, never the load.
 * <p>
 * Runs with the CALLER's session, like {@code StagingLoader#load} (no MCP call at all): the REST route
 * restricts this to administrators, and the caller needs write access to the project's master and staging
 * dataspaces plus the factory's own dataset ({@code TransferReject}, {@code Xref}).
 * <p>
 * <b>A field not yet on the PUBLISHED master table is never written</b> (coordinator review, 2026-09-21 -
 * the same hazard {@code ebx.StagingLoader} fixes for staging: staging can be regenerated and promoted before
 * master is, or the reverse for an attribute just promoted, so a field {@code master.MasterTransferPlan}'s
 * plan wants to copy may not exist yet on the live master schema). {@link #loadEntity} tests every candidate
 * {@code masterField} name once per entity ({@code transfer.FieldAvailabilityRule.classify}, the schema read
 * itself via {@code SchemaNode.getNode(...) != null} on {@code AdaptationTable.getTableOccurrenceRootNode()});
 * {@link #planRow} skips a missing field entirely for every row, never a reject. Reported once per entity
 * ({@code entities[].missingFields}, {@code fieldsNotYetInModel}), same shape as the staging loader's own
 * report. Not extended to pass 2 ({@link #resolveDeferredReferences}) - see {@code ebx.StagingLoader}'s own
 * Javadoc for why, and this lot's final report for the residual gap.
 * <p>
 * <b>SCRIPT_CONSTRAINT enforcement (quality-script-rule lot, 2026-09-23) - design A of the task brief.</b> An
 * APPLIED {@code CapabilitySuggestion} (nature {@code SCRIPT_CONSTRAINT}, zone {@code STAGING}) with a
 * non-blank {@code transferPolicy} is read here, never re-run: {@code ebx.CapabilityApplier} already declared
 * and published the constraint on the STAGING model (a real EBX {@code ConstraintOnNode}/{@code
 * ConstraintOnTable}), so by the time a row reaches this loader EBX's OWN validation engine has already
 * evaluated it against the staging record - {@link #planRow} reads that record's own {@code
 * Adaptation#getValidationReport()} once (never the library function, never a script call from Java) and
 * attributes each item to a specific constraint with {@code capability.MeasurementMatch}/{@code
 * quality.ScriptConstraintCheck}, the exact same severity+path+message match {@code ebx.CapabilityApplier}'s
 * own "measured effect" pass already uses (`docs/CAPABILITY-APPLY.md` sec 6). {@code transferPolicy REJECT}
 * routes the whole row into {@code TransferReject} (the same {@code reasons} list an unresolved foreign key
 * already funnels into); {@code WARN} keeps the row and only feeds {@link #recordScriptRuleCheck}'s own
 * counters, written back onto {@code CapabilitySuggestion.lastLoad*} at the very end of {@link #load} - the
 * same {@code lastLoadChecked}/{@code lastLoadFailing}/{@code lastLoadExamples}/{@code lastLoadCheckedAt}
 * shape {@code ebx.StagingLoader} already writes onto {@code QualityRule}. Deliberately staging -&gt; master
 * ONLY: a landing row is not yet a staging {@code Adaptation} when {@code ebx.StagingLoader} runs, so it has
 * no validation report of its own to read yet - see {@code docs/CAPABILITY-TRANSFER.md} for why that leg was
 * left out rather than forced into a fragile post-write re-read/delete pass.
 */
public final class MasterLoader
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String STEP = "MASTER_LOAD";
	private static final String SCOPE = "";
	private static final int DEFAULT_BATCH_SIZE = 500;
	private static final String REJECT_STAGE = "MASTER";
	private static final int MAX_REPORTED_REJECTS = 10;

	private static final Path MASTER_LOAD_ID = Path.parse("./_loadId");
	private static final Path MASTER_RECEIVED_AT = Path.parse("./_receivedAt");
	private static final Path STAGING_SOURCE_SYSTEM = Path.parse("./sourceSystem");
	private static final Path STAGING_SOURCE_ID = Path.parse("./sourceId");

	private MasterLoader()
	{
	}

	// ============================================================================================== entry point

	/**
	 * Distribution lot 1 (SPEC-distribution.md "Real time"): a master load is one of the three "FULL reload"
	 * steps that pause the project's armed REALTIME distribution targets while it runs, with a CHANGED
	 * catch-up send once it is over ({@code ebx.DistributionPause}). The actual work is unchanged, in
	 * {@link #loadInternal}.
	 */
	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		return MasterLoader.load(pSession, pFactory, pProjectCode, com.on.ebx.factory.staging.StagingLoadListener.NONE);
	}

	/**
	 * Same, telling {@code pListener} each business object's copy and its real row counts after each batch
	 * (master-live lot, 2026-09-27) - the listener's "source" is always {@link #COPY_CELL}: the master copies one
	 * staging table per business object, every application already together in it.
	 */
	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectCode, final com.on.ebx.factory.staging.StagingLoadListener pListener)
		throws OperationException
	{
		return MasterLoader.load(pSession, pFactory, pProjectCode, pListener, null);
	}

	/**
	 * Volume lot 3 (2026-09-28, docs/VOLUME.md task 8, "cancellation + tracking info"): {@code pHandle} - null
	 * for every synchronous caller - lets a job-driven run ({@link MasterBuildRun}, the only caller that ever
	 * has one) be stopped cleanly between batches - see {@code ebx.StagingLoader}'s matching overload's own
	 * Javadoc for the full reasoning, identical here.
	 */
	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectCode,
		final com.on.ebx.factory.staging.StagingLoadListener pListener, final FactoryJobs.Handle pHandle) throws OperationException
	{
		return MasterLoader.load(pSession, pFactory, pProjectCode, pListener, pHandle, null);
	}

	/** Lot M-INC: same, with a scope - {@code changed} or {@code full}; blank takes {@code -Dmdm.factory.masterLoad.defaultScope} (default {@code full}). See {@link MasterLoadScope}. */
	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pScope) throws OperationException
	{
		return MasterLoader.load(pSession, pFactory, pProjectCode, com.on.ebx.factory.staging.StagingLoadListener.NONE, null, pScope);
	}

	/**
	 * Lot M-INC (2026-10-12): the full form. {@code pScope} {@code changed} reads, for every entity where the journal of changed staging rows can stand for a full read, only
	 * the journaled rows ({@link MasterLoadScope#decide}), and loads the others in full, saying why in the step detail; {@code full} (and a blank scope, unless {@code
	 * -Dmdm.factory.masterLoad.defaultScope=changed}) is the behaviour of lots V3-M..V3-M3. Either way the load installs the journal triggers when they are missing (see {@link MasterJournal}).
	 */
	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectCode,
		final com.on.ebx.factory.staging.StagingLoadListener pListener, final FactoryJobs.Handle pHandle, final String pScope) throws OperationException
	{
		// audit F-15: every caller (the queued job, the workflow step, ?sync=true) works on the master with the project's MASTER resource held - see EngineClaim
		final EngineClaim claim = EngineClaim.enter(pFactory, pSession, pProjectCode, "MASTER_LOAD_DIRECT", FactoryJobs.MASTER);
		try
		{
			return MasterLoader.loadPaused(pSession, pFactory, pProjectCode, pListener, pHandle, pScope);
		}
		finally
		{
			claim.release();
		}
	}

	private static ObjectNode loadPaused(final Session pSession, final Adaptation pFactory, final String pProjectCode,
		final com.on.ebx.factory.staging.StagingLoadListener pListener, final FactoryJobs.Handle pHandle, final String pScope) throws OperationException
	{
		final Adaptation pauseProject = MasterLoader.findProject(pFactory, pProjectCode);
		final String pauseProjectId = pauseProject == null ? null : pauseProject.getOccurrencePrimaryKey().format();
		// every pause is recorded durably under this owner (LoadPauseLedger): a stop before the resumes below leaves the proof the module needs to lift them at its next start
		final LoadPauseLedger.Owner pauseOwner = LoadPauseLedger.open(MasterLoader.STEP, pauseProjectId);
		final java.util.List<String> paused = pauseProjectId == null ? java.util.Collections.<String>emptyList()
			: DistributionPause.pause(pSession, pFactory, pauseProjectId, pauseOwner);
		// lot MP (2026-10-04): next to the distribution targets, the add-on's matching on the master tables of a REALTIME project is paused for the load - see
		// MatchingLoadPause. INACTIVE (nothing paused, nothing said) in MANUAL trigger mode. Begun after the distribution pause and finished before its resume:
		// the matching catch-up must be over before the CHANGED send of the distribution targets reads the master.
		final MatchingLoadPause matchingPause = MatchingLoadPause.begin(pSession, pFactory, pauseProject, pProjectCode, pauseOwner);
		ObjectNode result = null;
		try
		{
			result = MasterLoader.loadInternal(pSession, pFactory, pProjectCode, pListener, pHandle, matchingPause, pScope);
			return result;
		}
		finally
		{
			try
			{
				matchingPause.finish(pHandle, result);
			}
			finally
			{
				try
				{
					DistributionPause.resumeAndReport(pSession, pFactory, pProjectCode, pauseProjectId, paused, pauseOwner, MasterLoader.STEP);
				}
				finally
				{
					pauseOwner.close();
				}
			}
		}
	}

	/** The one cell per business object the master copy reports on (see {@link #load(Session, Adaptation, String, com.on.ebx.factory.staging.StagingLoadListener)}). */
	public static final String COPY_CELL = "COPY";

	static ObjectNode loadInternal(final Session pSession, final Adaptation pFactory, final String pProjectCode,
		final com.on.ebx.factory.staging.StagingLoadListener pListener, final FactoryJobs.Handle pHandle) throws OperationException
	{
		return MasterLoader.loadInternal(pSession, pFactory, pProjectCode, pListener, pHandle, MatchingLoadPause.INACTIVE, null);
	}

	static ObjectNode loadInternal(final Session pSession, final Adaptation pFactory, final String pProjectCode,
		final com.on.ebx.factory.staging.StagingLoadListener pListener, final FactoryJobs.Handle pHandle, final MatchingLoadPause pMatchingPause, final String pScope)
		throws OperationException
	{
		final Adaptation project = MasterLoader.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		final String stagingDataspaceKey = project.getString(FactoryPaths.Project.STAGING_DATASPACE);
		final String stagingDatasetName = project.getString(FactoryPaths.Project.STAGING_DATASET);
		if (MasterLoader.blank(masterDataspaceKey) || MasterLoader.blank(masterDatasetName))
		{
			throw EngineRefusal.coded("missing.noMasterZone", "Project " + pProjectCode + " has no master zone yet: run POST /projects/" + pProjectCode + "/master/generate first", pProjectCode);
		}
		if (MasterLoader.blank(stagingDataspaceKey) || MasterLoader.blank(stagingDatasetName))
		{
			throw EngineRefusal.coded("missing.noStagingZone", "Project " + pProjectCode + " has no staging zone: run POST /projects/" + pProjectCode + "/staging/generate first", pProjectCode);
		}

		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		final Adaptation masterDataset = masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(masterDatasetName));
		if (masterDataset == null)
		{
			throw EngineRefusal.coded("missing.masterDatasetPath", "Master dataset " + masterDataspaceKey + "/" + masterDatasetName + " of project " + pProjectCode
				+ " could not be found: run POST /projects/" + pProjectCode + "/master/generate first", masterDataspaceKey + "/" + masterDatasetName);
		}
		final AdaptationHome stagingHome = repository.lookupHome(HomeKey.forBranchName(stagingDataspaceKey));
		final Adaptation stagingDataset = stagingHome == null ? null : stagingHome.findAdaptationOrNull(AdaptationName.forName(stagingDatasetName));
		if (stagingDataset == null)
		{
			throw EngineRefusal.coded("missing.stagingDataset", "Staging dataset " + stagingDataspaceKey + "/" + stagingDatasetName + " of project " + pProjectCode + " could not be found.", stagingDataspaceKey + "/" + stagingDatasetName);
		}

		// docs/MASTER-ZONE.md, 2026-09-21 incident: this loader independently recomputes MasterModel.Result to
		// plan the transfer, so it needs memberEntities just as current as master/generate does - the same
		// backfill call, for the same reason (see ebx.MemberEntitiesBackfill's own Javadoc).
		MemberEntitiesBackfill.run(pSession, pFactory, projectId);

		final Map<String, String> entityIdByName = new LinkedHashMap<String, String>();
		final List<StagingModel.EntityInfo> stagingEntities = MasterLoader.readEntities(pFactory, projectId, entityIdByName);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(stagingEntities);
		final MasterTransferPlan.Result plan = MasterTransferPlan.plan(masterModel);
		if (plan.loadOrder.isEmpty())
		{
			throw EngineRefusal.coded("missing.noPromotedEntity", "Project " + pProjectCode
				+ " has no entity promoted to the master zone: run POST /projects/" + pProjectCode + "/master/generate first.", pProjectCode);
		}

		// the exact staging <attribute>Ref field name per (entity, attribute) - recomputed read-only, the same
		// way StagingLoader recovers it for TransferPlan.MappingInfo#foreignKeyField (see class Javadoc)
		final StagingModel.Result stagingModelResult = StagingModel.build(stagingEntities);
		final Map<String, String> stagingForeignKeyFieldByEntityAttribute = new HashMap<String, String>();
		for (final StagingModel.ForeignKeyInfo fk : stagingModelResult.foreignKeys)
		{
			stagingForeignKeyFieldByEntityAttribute.put(fk.entityName + "#" + fk.attributeName, fk.fieldName);
		}

		final Map<String, AdaptationTable> masterTables = new LinkedHashMap<String, AdaptationTable>();
		final Map<String, AdaptationTable> entityStagingTables = new LinkedHashMap<String, AdaptationTable>();
		for (final MasterTransferPlan.Entity entity : plan.loadOrder)
		{
			masterTables.put(entity.name, masterDataset.getTable(Path.parse(MasterModel.tablePath(entity.name))));
			entityStagingTables.put(entity.name, stagingDataset.getTable(Path.parse(StagingModel.tablePath(entity.name))));
		}

		final int batchSize = MasterLoader.batchSize(pFactory);
		final Date now = new Date();
		final Locale locale = pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale();

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, MasterLoader.STEP, MasterLoader.SCOPE);
		final ObjectNode detail = MasterLoader.MAPPER.createObjectNode();
		final ArrayNode entityDetails = detail.putArray("entities");
		// lot MP: what the matching pause did before the load (null/absent in MANUAL trigger mode); MatchingLoadPause#finish amends this step afterwards
		pMatchingPause.describeInto(detail);
		pMatchingPause.stepBegun(stepId);
		final List<String> firstRejectReasons = new ArrayList<String>();
		final Map<String, Counters> countersByEntity = new LinkedHashMap<String, Counters>();
		// quality-script-rule lot (2026-09-23): one counters accumulator for the whole run, keyed by
		// CapabilitySuggestion id - written back onto CapabilitySuggestion.lastLoad* at the very end of this
		// method, same convention as ebx.StagingLoader's own QualityRule counters.
		final Map<String, RuleRunCounters> scriptRuleCounters = new LinkedHashMap<String, RuleRunCounters>();

		// volume lot 3 (task 8, "cancellation"): true once pHandle.cancelled() stopped the run early - see
		// ebx.StagingLoader.loadInternal's own matching flag for the identical reasoning.
		boolean stopped = false;
		// lot V3-M2: the read indexes of the entities loaded so far that a later entity (or pass 2) still reads as a parent - see MasterLoadReads
		final Map<String, MasterLoadReads> readsByEntity = new HashMap<String, MasterLoadReads>();
		// lot MP: the entity being read/loaded, for the message of a failure (null before the first and after the last)
		String currentEntity = null;
		// lot M-INC: the scope asked for, and what each entity ended up doing (see MasterLoadScope / MasterJournal)
		final JournalRun journalRun = new JournalRun(MasterLoadScope.requested(pScope), pProjectCode);
		detail.put("requestedScope", journalRun.requested);
		try
		{
			// lot M-INC: the journal of changed staging rows - verified, or installed when missing (that load is then full); never fails the load
			final List<String> promotedNames = new ArrayList<String>();
			for (final MasterTransferPlan.Entity promoted : plan.loadOrder)
			{
				promotedNames.add(promoted.name);
			}
			journalRun.prepared = MasterJournal.prepare(pSession, pFactory, pProjectCode, stagingHome, promotedNames, pListener);
			detail.set("journal", journalRun.prepared.detail);
			// pass 1: every entity, parents first - an immediate FK is resolved in the same pass as the row
			// itself; a deferred one (self reference, or an edge inside an unresolved cycle) is left null here.
			// mergeCheck is built ONE PER ENTITY here (fixed 2026-09-22, class Javadoc): the add-on's
			// metadataset is per-TABLE, so a single shared instance across several promoted entities would
			// have checked only the first entity's own metadataset for every one of them.
			final ParentKeys parentKeys = new ParentKeys(pFactory, projectId, repository, masterDataspaceKey, masterDatasetName, entityIdByName, readsByEntity);
			final Map<String, Integer> lastReaderIndex = MasterLoader.lastReaderIndexByTarget(plan.loadOrder);
			final Set<String> readInPass2 = MasterLoader.readInPass2(plan.loadOrder);
			int entityIndex = -1;
			for (final MasterTransferPlan.Entity entity : plan.loadOrder)
			{
				pListener.planned(entity.name, MasterLoader.COPY_CELL);
			}
			entityLoop:
			for (final MasterTransferPlan.Entity entity : plan.loadOrder)
			{
				entityIndex++;
				currentEntity = entity.name;
				final Counters counters = new Counters();
				countersByEntity.put(entity.name, counters);
				final long entityStart = System.currentTimeMillis();
				// volume lot 3 (task 5, "light merge check"): matching was never activated for THIS entity (no
				// MatchPlan APPLIED yet - the same fact ParentKeys#matchingActivated already gates its own
				// add-on hop on) means nothing could possibly be merged away, so the real metadata lookup (a
				// master-record fetch plus a MetadataHelper call, per UPDATE row) is skipped entirely - see
				// MergeCheck#NEVER_MERGED's own Javadoc.
				final String entityId = entityIdByName.get(entity.name);
				final boolean matchingActivatedForEntity = "APPLIED".equals(MasterLoader.readMatchPlanStatus(pFactory, entityId));
				final MergeCheck mergeCheck = matchingActivatedForEntity
					? new RecordMetadataMergeCheck(repository, masterDataspaceKey, masterDatasetName, MasterModel.tablePath(entity.name))
					: MergeCheck.NEVER_MERGED;
				final List<ScriptConstraintCheck.Rule> scriptRules = MasterLoader.readScriptConstraintRules(pFactory, projectId, entityId);
				final boolean completed = MasterLoader.loadEntity(pSession, pFactory, projectId, entityId, entity, entityStagingTables.get(entity.name),
					masterTables.get(entity.name), masterDataset, stagingForeignKeyFieldByEntityAttribute, mergeCheck, parentKeys, scriptRules, scriptRuleCounters, locale,
					batchSize, now, counters, firstRejectReasons, pListener, pHandle, stepId, readsByEntity, matchingActivatedForEntity, repository, masterDataspaceKey,
					masterDatasetName, journalRun);
				counters.durationMs = Math.max(1, System.currentTimeMillis() - entityStart);
				MasterLoader.releaseReads(readsByEntity, entityIndex, lastReaderIndex, readInPass2);
				if (!completed)
				{
					stopped = true;
					break entityLoop;
				}
			}

			// pass 2: deferred references - every promoted entity now has a master key (or was rejected/skipped,
			// both harmless here), so a self reference or a cycle edge can finally be resolved through Xref.
			// Skipped entirely once pass 1 was stopped early - the next run resumes it.
			if (!stopped)
			{
				for (final MasterTransferPlan.Entity entity : plan.loadOrder)
				{
					if (pHandle != null && pHandle.cancelled())
					{
						stopped = true;
						break;
					}
					currentEntity = entity.name;
					pListener.note(RunTexts.masterResolvingDeferred(entity.name).stored());
					if (!MasterLoader.resolveDeferredReferences(pSession, pFactory, projectId, entity, entityStagingTables.get(entity.name), masterTables.get(entity.name),
						masterDataset, stagingForeignKeyFieldByEntityAttribute, parentKeys, batchSize, countersByEntity.get(entity.name), pHandle, readsByEntity))
					{
						stopped = true;
						break;
					}
				}
			}
			// inside the try (lot MP): a failure of this write-back also ends the step FAILED
			MasterLoader.writeScriptRuleCounters(pSession, pFactory, scriptRuleCounters, now);
			// lot M-INC: only a load that ended normally consumes the journal rows it read and arms the entities it loaded in full
			if (!stopped)
			{
				MasterLoader.finishJournal(pSession, journalRun, plan.loadOrder, countersByEntity);
			}
		}
		catch (final OperationException ex)
		{
			MasterLoader.endFailed(pSession, pFactory, stepId, detail, entityDetails, countersByEntity, currentEntity, ex.getMessage());
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			// lot MP (2026-10-04, project Vol1mF): a failure while READING the master table - the scan that builds the indexes of lot V3-M2, the add-on's
			// "requested transaction ... is in the future" - is not an OperationException, so the step stayed RUNNING for ever with no message while the enclosing
			// MASTER_BUILD_RUN step did get the reason. Whatever is thrown between the step's begin and its normal end ends it FAILED, with the reason, the entity
			// and the counters written so far; the exception still goes to the caller. (Error included: a LinkageError from the add-on's API, a VerifyError.)
			MasterLoader.endFailed(pSession, pFactory, stepId, detail, entityDetails, countersByEntity, currentEntity, ex.getClass().getName() + ": " + ex.getMessage());
			throw ex;
		}
		finally
		{
			// lot V3-M2: whatever index was kept for a parent goes with the load
			for (final MasterLoadReads reads : readsByEntity.values())
			{
				reads.releaseAll();
			}
			readsByEntity.clear();
		}

		long totalRead = 0;
		long totalWritten = 0;
		long totalUnchanged = 0;
		long totalSkippedMerged = 0;
		long totalRejected = 0;
		long totalDeferredResolved = 0;
		long totalDeferredUnresolved = 0;
		long totalDeferredWriteFailedRows = 0;
		long totalDeferredWriteLost = 0;
		String firstDeferredWriteError = null;
		long totalReferencesToGolden = 0;
		// lot M-INC: the scope in force over the entities, the journal rows that decided it, and how the staging rows were read
		int changedEntities = 0;
		long totalJournalRows = 0;
		long totalJournalConsumed = 0;
		String firstScopeReason = "";
		final Set<String> stagingReadModes = new LinkedHashSet<String>();
		for (final MasterTransferPlan.Entity entity : plan.loadOrder)
		{
			final Counters counters = countersByEntity.get(entity.name);
			if (MasterLoadScope.CHANGED.equals(counters.scope))
			{
				changedEntities++;
				totalJournalRows += counters.journalRows + counters.rejectsReread;
			}
			else if (firstScopeReason.isEmpty() && !counters.scopeReason.isEmpty())
			{
				firstScopeReason = entity.name + ": " + counters.scopeReason;
			}
			totalJournalConsumed += counters.journalConsumed;
			stagingReadModes.add(counters.stagingRead);
			totalRead += counters.read;
			totalWritten += counters.written;
			totalUnchanged += counters.unchanged;
			totalSkippedMerged += counters.skippedMerged;
			totalRejected += counters.rejected;
			totalDeferredResolved += counters.deferredResolved;
			totalDeferredUnresolved += counters.deferredUnresolved;
			totalDeferredWriteFailedRows += counters.deferredWriteFailedRows;
			totalDeferredWriteLost += counters.deferredWriteLost;
			if (firstDeferredWriteError == null)
			{
				firstDeferredWriteError = counters.deferredWriteError;
			}
			totalReferencesToGolden += counters.referencesToGolden;
			MasterLoader.describeEntity(entityDetails.addObject(), entity.name, counters);
		}

		final long durationMs = Math.max(1, System.currentTimeMillis() - now.getTime());
		// lot V3-M: "written" is only what was really written (inserted + updated); a row already holding every value is "unchanged"
		// a batch of deferred references whose write failed is said first: those references are empty or stale although the load went on
		final String deferredWarning = DeferredFlushOutcome.warning(totalDeferredWriteFailedRows, totalDeferredWriteLost, firstDeferredWriteError);
		final String effectiveScope = changedEntities == 0 ? MasterLoadScope.FULL : changedEntities == plan.loadOrder.size() ? MasterLoadScope.CHANGED : "mixed";
		final String stagingReadSummary = stagingReadModes.size() == 1 ? stagingReadModes.iterator().next() : "MIXED";
		// a changed load whose journal (and open rejects) was empty for every entity: it ends at once, nothing was read
		final boolean nothingChanged = !stopped && changedEntities == plan.loadOrder.size() && totalJournalRows == 0L && totalRead == 0L;
		final String scopeSentence = nothingChanged ? ""
			: MasterLoadScope.FULL.equals(effectiveScope)
				? (MasterLoadScope.CHANGED.equals(journalRun.requested) ? "Scope full (changed was asked: " + firstScopeReason + "). " : "")
				: "Scope " + effectiveScope + " (" + totalJournalRows + " journal row(s), read " + stagingReadSummary + "). ";
		final String message = nothingChanged
			? "Nothing changed: scope changed, the journal holds no row and no staging row was read."
			: scopeSentence + (deferredWarning.isEmpty() ? "" : deferredWarning + " ") + "Wrote " + totalWritten + " row(s) from " + totalRead + " source row(s) read (" + totalUnchanged + " unchanged, " + totalSkippedMerged
				+ " skipped merged, " + totalRejected + " rejected, " + totalDeferredResolved + " deferred reference(s) resolved, " + totalDeferredUnresolved + " still unresolved).";
		detail.put("scope", effectiveScope);
		detail.put("journalRows", totalJournalRows);
		detail.put("readMode", stagingReadSummary);
		detail.put("nothingChanged", nothingChanged);
		// docs/MASTER-ZONE.md sec 10, MEASURED LIVE 2026-09-22 12:39: this step ended DONE with "Transferred 0 of
		// 196 ... 196 rejected" (Company's own id field had no auto-increment facet - the defect ebx.MasterGenerator/
		// master.MasterFacetRepair now close) - a step and a route status both reading DONE for a load that wrote
		// NOTHING is indistinguishable from a genuinely empty, successful run. master.MasterLoadOutcome decides
		// this is a genuine failure (every row READ ended up REJECTED, not merely "0 written" - see its own Javadoc
		// for why a legitimately all-skipped-merged run must NOT be misclassified the same way); message and
		// firstRejectReasons stay exactly as computed above either way.
		final boolean totalFailure = MasterLoadOutcome.isTotalFailure(totalRead, totalRejected);
		// volume lot 3 (task 8): a run pHandle.cancelled() stopped ends STOPPED, never DONE or FAILED - see
		// ebx.StagingLoader.loadInternal's own matching handling for the identical reasoning.
		if (stopped)
		{
			PipelineSteps.endStopped(pSession, pFactory, stepId, "Master load stopped (" + (pHandle == null ? "?" : pHandle.stopReason()) + ") after " + message,
				detail.toString());
		}
		else
		{
			PipelineSteps.end(pSession, pFactory, stepId, !totalFailure, message, detail.toString());
		}

		// coordinator fix, 2026-09-21: a top-level count, same reasoning as ebx.StagingLoader's own
		int fieldsNotYetInModel = 0;
		for (final Counters counters : countersByEntity.values())
		{
			fieldsNotYetInModel += counters.missingFields.size();
		}

		final ObjectNode out = MasterLoader.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("batchSize", batchSize);
		out.put("requestedScope", journalRun.requested);
		out.put("scope", effectiveScope);
		out.put("journalRows", totalJournalRows);
		out.put("journalConsumed", totalJournalConsumed);
		out.put("readMode", stagingReadSummary);
		out.put("nothingChanged", nothingChanged);
		out.put("read", totalRead);
		out.put("written", totalWritten);
		out.put("unchanged", totalUnchanged);
		out.put("skippedMerged", totalSkippedMerged);
		out.put("rejected", totalRejected);
		out.put("deferredResolved", totalDeferredResolved);
		out.put("deferredUnresolved", totalDeferredUnresolved);
		out.put("deferredWriteFailedRows", totalDeferredWriteFailedRows);
		out.put("referencesToGolden", totalReferencesToGolden);
		out.put("durationMs", durationMs);
		out.put("fieldsNotYetInModel", fieldsNotYetInModel);
		out.set("entities", entityDetails);
		final ArrayNode rejectsNode = out.putArray("firstRejectReasons");
		for (final String reason : firstRejectReasons)
		{
			rejectsNode.add(reason);
		}
		final ArrayNode notesNode = out.putArray("notes");
		for (final String note : plan.notes)
		{
			notesNode.add(note);
		}
		out.put("status", stopped ? "STOPPED" : totalFailure ? "FAILED" : "DONE");
		return out;
	}

	/**
	 * The counters of one entity as its step-detail node - shared by the normal end and by {@link #endFailed} (lot MP: a failed load keeps the counters written
	 * so far in its detail).
	 */
	private static void describeEntity(final ObjectNode entityDetail, final String pEntityName, final Counters counters)
	{
		entityDetail.put("entity", pEntityName);
		entityDetail.put("read", counters.read);
		entityDetail.put("written", counters.written);
		entityDetail.put("inserted", counters.inserted);
		entityDetail.put("updated", counters.updated);
		entityDetail.put("unchanged", counters.unchanged);
		entityDetail.put("skippedMerged", counters.skippedMerged);
		entityDetail.put("rejected", counters.rejected);
		final ObjectNode rejectedBy = entityDetail.putObject("rejectedBy");
		for (final Map.Entry<String, Long> byCategory : counters.rejectedByCategory.entrySet())
		{
			rejectedBy.put(byCategory.getKey(), byCategory.getValue().longValue());
		}
		entityDetail.put("deferredResolved", counters.deferredResolved);
		entityDetail.put("deferredUnresolved", counters.deferredUnresolved);
		if (counters.deferredWriteFailedRows > 0)
		{
			entityDetail.put("deferredWriteFailedRows", counters.deferredWriteFailedRows);
		}
		entityDetail.put("referencesToGolden", counters.referencesToGolden);
		entityDetail.put("goldenHopsByIndex", counters.goldenHopsByIndex);
		entityDetail.put("goldenHopsLive", counters.goldenHopsLive);
		entityDetail.put("durationMs", counters.durationMs);
		// lot V3-M2: how the entity was read (SCAN / LOOKUP), what each index cost, and how many random reads planning still made
		if (counters.reads != null)
		{
			counters.reads.describe(entityDetail);
		}
		// lot M-INC: the scope, and how many journal rows decided it
		entityDetail.put("scope", counters.scope);
		if (!counters.scopeReason.isEmpty())
		{
			entityDetail.put("scopeReason", counters.scopeReason);
		}
		entityDetail.put("stagingRead", counters.stagingRead);
		entityDetail.put("journalRows", counters.journalRows);
		if (MasterLoadScope.CHANGED.equals(counters.scope))
		{
			entityDetail.put("rejectsReread", counters.rejectsReread);
			entityDetail.put("stagingKeysMissing", counters.stagingKeysMissing);
		}
		entityDetail.put("journalConsumed", counters.journalConsumed);
		entityDetail.put("journalArmed", counters.journalArmed);
		entityDetail.put("unchangedByIndex", counters.unchangedByIndex);
		entityDetail.put("masterRecordReads", counters.masterRecordReads);
		entityDetail.put("xrefLookups", counters.xrefLookups);
		if (!counters.missingFields.isEmpty())
		{
			final ArrayNode missingFieldsNode = entityDetail.putArray("missingFields");
			for (final String fieldName : counters.missingFields)
			{
				missingFieldsNode.add(fieldName);
			}
		}
	}

	/**
	 * Lot MP: ends the {@code MASTER_LOAD} step FAILED with {@code pReason} (prefixed by the entity being loaded when known), the counters of every entity
	 * reached so far in its detail. Best-effort like {@link PipelineSteps#end}; never throws, so it can never mask the exception the caller rethrows.
	 */
	private static void endFailed(final Session pSession, final Adaptation pFactory, final String pStepId, final ObjectNode pDetail, final ArrayNode pEntityDetails,
		final Map<String, Counters> pCountersByEntity, final String pCurrentEntity, final String pReason)
	{
		try
		{
			// the entities array is only filled at the normal end: write what each entity reached, once
			if (pEntityDetails.size() == 0)
			{
				for (final Map.Entry<String, Counters> entry : pCountersByEntity.entrySet())
				{
					MasterLoader.describeEntity(pEntityDetails.addObject(), entry.getKey(), entry.getValue());
				}
			}
			if (pCurrentEntity != null)
			{
				pDetail.put("failedEntity", pCurrentEntity);
			}
			PipelineSteps.end(pSession, pFactory, pStepId, false, "Master load failed" + (pCurrentEntity == null ? "" : " while loading " + pCurrentEntity) + ": " + pReason,
				pDetail.toString());
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not record the failure of the master load step " + pStepId + ": " + ex, ex);
		}
	}

	/** Lot V3-M2: for every entity named as the target of a foreign key, the position in the load order of the LAST entity that reads it as a parent. */
	private static Map<String, Integer> lastReaderIndexByTarget(final List<MasterTransferPlan.Entity> pLoadOrder)
	{
		final Map<String, Integer> out = new HashMap<String, Integer>();
		for (int i = 0; i < pLoadOrder.size(); i++)
		{
			for (final MasterTransferPlan.FieldPlan field : pLoadOrder.get(i).fields)
			{
				if (field.isForeignKey && field.targetEntityName != null)
				{
					out.put(field.targetEntityName, Integer.valueOf(i));
				}
			}
		}
		return out;
	}

	/** Lot V3-M2: the entities whose {@code Xref} keys pass 2 reads - an entity with a deferred foreign key (its own rows) and every target of one (its keys as a parent). */
	private static Set<String> readInPass2(final List<MasterTransferPlan.Entity> pLoadOrder)
	{
		final Set<String> out = new HashSet<String>();
		for (final MasterTransferPlan.Entity entity : pLoadOrder)
		{
			for (final MasterTransferPlan.FieldPlan field : entity.fields)
			{
				if (field.isForeignKey && field.deferred)
				{
					out.add(entity.name);
					if (field.targetEntityName != null)
					{
						out.add(field.targetEntityName);
					}
				}
			}
		}
		return out;
	}

	/** Lot V3-M2: drops the read indexes no later entity and no pass 2 reads any more ({@code pDoneIndex} = position of the entity that just ended). */
	private static void releaseReads(final Map<String, MasterLoadReads> pReadsByEntity, final int pDoneIndex, final Map<String, Integer> pLastReaderIndex,
		final Set<String> pReadInPass2)
	{
		final java.util.Iterator<Map.Entry<String, MasterLoadReads>> it = pReadsByEntity.entrySet().iterator();
		while (it.hasNext())
		{
			final Map.Entry<String, MasterLoadReads> entry = it.next();
			final Integer lastReader = pLastReaderIndex.get(entry.getKey());
			if ((lastReader == null || lastReader.intValue() <= pDoneIndex) && !pReadInPass2.contains(entry.getKey()))
			{
				entry.getValue().releaseAll();
				it.remove();
			}
		}
	}

	/** Lot V3-M2: the master key {@code Xref} holds for a staging row of {@code pEntityName}, from that entity's index when it has one, else by one primary-key lookup (counted). */
	private static String xrefMasterKey(final Map<String, MasterLoadReads> pReadsByEntity, final Adaptation pFactory, final String pProjectId, final String pEntityName,
		final String pStagingKey, final Counters pCounters)
	{
		final MasterLoadReads reads = pReadsByEntity == null ? null : pReadsByEntity.get(pEntityName);
		if (reads != null && reads.xrefMasterKeys != null)
		{
			return reads.xrefMasterKeys.get(pStagingKey);
		}
		pCounters.xrefLookups++;
		return MasterLoader.lookupXrefMasterKey(pFactory, pProjectId, pEntityName, pStagingKey);
	}

	// ======================================================================================= lot M-INC: the journal

	/** The scope asked for, the prepared journal and what each entity did: one per load. */
	private static final class JournalRun
	{
		final String requested;
		final String projectCode;
		final long lookupMax = MasterLoadScope.lookupMax();
		/** Set once, before the first entity; never null while entities load. */
		MasterJournal.Prepared prepared;
		final Map<String, EntityJournal> byEntity = new LinkedHashMap<String, EntityJournal>();

		JournalRun(final String pRequested, final String pProjectCode)
		{
			this.requested = pRequested;
			this.projectCode = pProjectCode;
		}
	}

	/** What one entity does with the journal: the staging keys to read (changed), the rows to consume, and whether the load may arm it. */
	private static final class EntityJournal
	{
		boolean changed;
		MasterLoadScope.ReadMode readMode = MasterLoadScope.ReadMode.SCAN_ALL;
		/** The staging keys a changed entity reads: the journal's, then its open MASTER rejects. */
		Set<String> keys = new LinkedHashSet<String>();
		/** The journal rows read at the start - consumed at the end of a load that ended normally. */
		Map<String, MasterJournal.Row> journalRead = new LinkedHashMap<String, MasterJournal.Row>();
		String fingerprint = "";
		/** A full entity: its recording was started (and it was disarmed) - it may be armed when the load ends. */
		boolean recording;
		/** The entity's own pass ran to the end. */
		boolean completed;
	}

	/** What the entity's load copies, as the part list {@link PlanFingerprint} hashes: a change in any of it makes the unchanged staging rows produce a different master row. */
	private static String planFingerprint(final MasterTransferPlan.Entity pEntity, final Set<String> pWritable, final List<ScriptConstraintCheck.Rule> pRules,
		final Map<String, String> pStagingForeignKeyFieldByEntityAttribute)
	{
		final List<String> parts = new ArrayList<String>();
		parts.add("masterPlan/v1");
		parts.add(pEntity.name);
		for (final MasterTransferPlan.FieldPlan field : pEntity.fields)
		{
			parts.add(field.masterField + "<-" + field.stagingField + "|" + field.dataType + "|fk=" + field.isForeignKey + "|" + field.targetEntityName + "|deferred=" + field.deferred
				+ "|ref=" + (field.isForeignKey ? pStagingForeignKeyFieldByEntityAttribute.get(pEntity.name + "#" + field.stagingField) : ""));
		}
		parts.add("writable");
		final List<String> writable = new ArrayList<String>(pWritable);
		java.util.Collections.sort(writable);
		parts.addAll(writable);
		parts.add("rules");
		for (final ScriptConstraintCheck.Rule rule : pRules)
		{
			parts.add(rule.id + "|" + rule.transferPolicy + "|" + rule.expectedPath);
		}
		return PlanFingerprint.of(parts);
	}

	private static boolean hasDeferredReferences(final MasterTransferPlan.Entity pEntity)
	{
		for (final MasterTransferPlan.FieldPlan field : pEntity.fields)
		{
			if (field.isForeignKey && field.deferred)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Chooses the scope of one entity ({@link MasterLoadScope#decide}) and prepares its journal use. A {@code changed} entity gets its staging keys (journal rows, then open
	 * MASTER rejects) and its read mode. Any other entity is loaded in full; when the journal is usable its recording is started and it is DISARMED here, before a single
	 * row is read, and the journal rows already there are snapshot-read for the load to consume at its end (everything visible now was committed before the scan).
	 */
	private static EntityJournal chooseScope(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityId, final MasterTransferPlan.Entity pEntity,
		final AdaptationTable pStagingTable, final AdaptationTable pMasterTable, final Set<String> pWritable, final List<ScriptConstraintCheck.Rule> pRules,
		final Map<String, String> pStagingForeignKeyFieldByEntityAttribute, final JournalRun pRun, final Counters pCounters)
	{
		try
		{
			return MasterLoader.chooseScopeUnsafe(pSession, pFactory, pProjectId, pEntityId, pEntity, pStagingTable, pMasterTable, pWritable, pRules,
				pStagingForeignKeyFieldByEntityAttribute, pRun, pCounters);
		}
		catch (final RuntimeException ex)
		{
			// the journal costs speed when it fails, never the load: this entity is loaded in full and left disarmed (no recording started, nothing consumed)
			LoggingCategory.getKernel().warn("[mdm-factory] master load of " + pEntity.name + ": the journal could not be read, loading in full: " + ex);
			final EntityJournal fallback = new EntityJournal();
			pRun.byEntity.put(pEntity.name, fallback);
			pCounters.scope = MasterLoadScope.FULL;
			pCounters.scopeReason = MasterLoadScope.CHANGED.equals(pRun.requested) ? "the journal could not be read: " + ex.getClass().getSimpleName() + ": " + ex.getMessage() : "";
			pCounters.stagingRead = MasterLoadScope.ReadMode.SCAN_ALL.name();
			pCounters.journalRows = 0L;
			return fallback;
		}
	}

	private static EntityJournal chooseScopeUnsafe(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityId,
		final MasterTransferPlan.Entity pEntity, final AdaptationTable pStagingTable, final AdaptationTable pMasterTable, final Set<String> pWritable,
		final List<ScriptConstraintCheck.Rule> pRules, final Map<String, String> pStagingForeignKeyFieldByEntityAttribute, final JournalRun pRun, final Counters pCounters)
	{
		final EntityJournal journal = new EntityJournal();
		pRun.byEntity.put(pEntity.name, journal);
		final MasterJournal.Prepared prepared = pRun.prepared;
		final boolean usable = prepared != null && prepared.usable;
		final boolean wantChanged = MasterLoadScope.CHANGED.equals(pRun.requested);
		journal.fingerprint = MasterLoader.planFingerprint(pEntity, pWritable, pRules, pStagingForeignKeyFieldByEntityAttribute);
		final MasterLoadScope.Facts facts = new MasterLoadScope.Facts();
		MasterJournal.Control control = null;
		if (usable)
		{
			facts.journalUsable = true;
			facts.installedNow = prepared.installedNow;
			control = MasterJournal.readControl(prepared, pEntity.name);
			facts.armed = control.exists && control.armed;
			facts.fingerprintMatches = journal.fingerprint.equals(control.fingerprint);
		}
		else
		{
			facts.journalUnusableReason = prepared == null ? "not prepared" : prepared.reason;
		}
		facts.deferredReferences = MasterLoader.hasDeferredReferences(pEntity);
		Set<String> rejectKeys = new LinkedHashSet<String>();
		Map<String, MasterJournal.Row> rows = new LinkedHashMap<String, MasterJournal.Row>();
		if (wantChanged)
		{
			facts.masterEmpty = MasterLoadReads.size(pMasterTable) == 0L;
			if (usable && !facts.installedNow && facts.armed && facts.fingerprintMatches && !facts.deferredReferences && !facts.masterEmpty)
			{
				final long journalCount = MasterJournal.countRows(prepared, pEntity.name);
				if (journalCount <= MasterLoadScope.MAX_JOURNAL_ROWS)
				{
					rows = MasterJournal.readRows(prepared, pEntity.name, pStagingTable, MasterLoadScope.MAX_JOURNAL_ROWS);
					rejectKeys = MasterJournal.openRejectKeys(pFactory, pProjectId, pEntityId, pStagingTable);
					journal.keys = JournalConsumption.keysToRead(rows, rejectKeys);
					facts.journalRows = journal.keys.size();
				}
				else
				{
					facts.journalRows = journalCount;
				}
			}
		}
		final MasterLoadScope.Decision decision = MasterLoadScope.decide(pRun.requested, facts);
		pCounters.scope = decision.scope();
		pCounters.scopeReason = decision.reason;
		if (decision.changed)
		{
			journal.changed = true;
			journal.journalRead = rows;
			journal.readMode = MasterLoadScope.readMode(true, journal.keys.size(), pRun.lookupMax);
			pCounters.journalRows = rows.size();
			pCounters.rejectsReread = rejectKeys.size();
			pCounters.stagingRead = journal.readMode.name();
			return journal;
		}
		journal.keys = new LinkedHashSet<String>();
		if (usable)
		{
			if (control != null && control.exists)
			{
				// rows already journaled are committed, so the scan below sees their staging writes: a load that ends normally consumes this snapshot
				if (MasterJournal.countRows(prepared, pEntity.name) <= MasterLoadScope.MAX_SNAPSHOT_ROWS)
				{
					journal.journalRead = MasterJournal.readRows(prepared, pEntity.name, pStagingTable, MasterLoadScope.MAX_SNAPSHOT_ROWS);
					pCounters.journalRows = journal.journalRead.size();
				}
			}
			journal.recording = MasterJournal.begin(pSession, prepared, pEntity.name);
		}
		return journal;
	}

	/**
	 * The end of a load that ended normally (never called after a stop or a failure): a changed entity deletes the journal rows it read - each only if its counter is still
	 * the one read - a full entity whose recording was started is armed, and consumes the snapshot it read before its scan. An entity that did not run to its end, or
	 * whose every row was rejected, is left as it is (disarmed if it loaded in full: the next load is full).
	 */
	private static void finishJournal(final Session pSession, final JournalRun pRun, final List<MasterTransferPlan.Entity> pLoadOrder, final Map<String, Counters> pCountersByEntity)
	{
		final MasterJournal.Prepared prepared = pRun.prepared;
		if (prepared == null || !prepared.usable)
		{
			return;
		}
		for (final MasterTransferPlan.Entity entity : pLoadOrder)
		{
			final EntityJournal journal = pRun.byEntity.get(entity.name);
			final Counters counters = pCountersByEntity.get(entity.name);
			if (journal == null || counters == null || !journal.completed)
			{
				continue;
			}
			try
			{
				if (journal.changed)
				{
					counters.journalConsumed = MasterJournal.consume(pSession, prepared, entity.name, journal.journalRead);
					counters.journalArmed = MasterJournal.arm(pSession, prepared, entity.name, journal.fingerprint);
				}
				else if (journal.recording && !MasterLoadOutcome.isTotalFailure(counters.read, counters.rejected))
				{
					counters.journalArmed = MasterJournal.arm(pSession, prepared, entity.name, journal.fingerprint);
					if (counters.journalArmed && !journal.journalRead.isEmpty())
					{
						counters.journalConsumed = MasterJournal.consume(pSession, prepared, entity.name, journal.journalRead);
					}
				}
			}
			catch (final RuntimeException ex)
			{
				// bookkeeping of a finished load: the rows stay journaled / the entity stays disarmed, so the next load reads or loads in full - harmless, never a failed load
				LoggingCategory.getKernel().warn("[mdm-factory] master load of " + entity.name + ": the journal bookkeeping failed after the load, it will be done in full next time: " + ex);
			}
		}
	}

	/** Where an entity's staging rows come from: every row, the journal's rows by key, or one scan filtered on the journal's keys. */
	private static final class RowSource
	{
		private final AdaptationTable table;
		private final RequestResult all;
		private final Iterator<String> keyIterator;
		private final Set<String> keySet;
		private final Counters counters;
		private final long total;
		private final long tableRows;

		private RowSource(final AdaptationTable pTable, final RequestResult pAll, final Iterator<String> pKeyIterator, final Set<String> pKeySet, final Counters pCounters,
			final long pTotal, final long pTableRows)
		{
			this.table = pTable;
			this.all = pAll;
			this.keyIterator = pKeyIterator;
			this.keySet = pKeySet;
			this.counters = pCounters;
			this.total = pTotal;
			this.tableRows = pTableRows;
		}

		static RowSource open(final AdaptationTable pTable, final EntityJournal pJournal, final Counters pCounters)
		{
			if (!pJournal.changed)
			{
				final RequestResult all = pTable.createRequestResult(null);
				return new RowSource(pTable, all, null, null, pCounters, all.getSize(), all.getSize());
			}
			if (pJournal.readMode == MasterLoadScope.ReadMode.KEYS)
			{
				return new RowSource(pTable, null, pJournal.keys.iterator(), null, pCounters, pJournal.keys.size(), -1L);
			}
			final RequestResult all = pTable.createRequestResult(null);
			return new RowSource(pTable, all, null, pJournal.keys, pCounters, pJournal.keys.size(), all.getSize());
		}

		/** Rows of the staging table (the Xref index of a scan is sized from it); -1 when the table is not read at all. */
		long tableRows()
		{
			return this.tableRows;
		}

		/** The rows this source will give (the cell's total): the table, or the number of journal keys. */
		long total()
		{
			return this.total;
		}

		Adaptation next()
		{
			if (this.keyIterator != null)
			{
				while (this.keyIterator.hasNext())
				{
					final Adaptation row = this.table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(this.keyIterator.next()));
					if (row != null)
					{
						return row;
					}
					this.counters.stagingKeysMissing++; // deleted since it was journaled
				}
				return null;
			}
			for (Adaptation row = this.all.nextAdaptation(); row != null; row = this.all.nextAdaptation())
			{
				if (this.keySet == null || this.keySet.contains(row.getOccurrencePrimaryKey().format()))
				{
					return row;
				}
			}
			return null;
		}

		void close()
		{
			if (this.all != null)
			{
				this.all.close();
			}
		}
	}

	// ================================================================================================== per entity

	private static final class Counters
	{
		long read;
		long written;
		long inserted;
		long updated;
		/** Lot V3-M (2026-10-04): an update row whose every planned value was already stored on the master record - read, never written ({@code written} counts only {@link #inserted} + {@link #updated}). */
		long unchanged;
		/** A source row whose master record was merged away by Match and Merge: nothing is written for it (counted in {@link #planRow} since lot V3-M - it was never incremented before, so the report always read 0). */
		long skippedMerged;
		long rejected;
		/** EBX refusal classes lot H4 (2026-10-17): why this entity's rows were set aside, by {@link RejectCategory} - what the wizard and the Cockpit word the "set aside" sentence from (as {@code StagingLoader}'s). */
		final Map<String, Long> rejectedByCategory = new LinkedHashMap<String, Long>();
		/** Pass 2 only: a deferred (self-reference / cycle) foreign key resolved once its target had a master key. */
		long deferredResolved;
		/** Pass 2 only: a deferred foreign key still unresolved after every promoted entity finished its first pass - a real, if unusual, data shape (e.g. a root account with no parent), never an error. */
		long deferredUnresolved;
		/** Pass 2 only: master rows whose deferred references could not be written (the batch's procedure failed); their references moved from resolved to unresolved. */
		long deferredWriteFailedRows;
		/** Pass 2 only: the references of those rows, moved from resolved to unresolved. */
		long deferredWriteLost;
		/** The first reason of such a failure, for the message. */
		String deferredWriteError;
		/** A foreign key whose parent source record was merged away, written as the surviving golden record's id instead - {@link ParentKeys}. */
		long referencesToGolden;
		/** Lot V3-M3 (2026-10-04): hops of a parent's merge chain answered by the target entity's merged read ({@link MasterLoadReads#goldenOfMerged}, {@link MasterLoadReads#knownNotMerged}) - no add-on call. */
		long goldenHopsByIndex;
		/** Lot V3-M3: hops asked of the add-on live (a record the index cannot answer: LOOKUP mode, a write to that table since the read, an empty {@code goldenId}); 0 for an unchanged reload in SCAN mode. */
		long goldenHopsLive;
		long durationMs = 1;
		/** Lot V3-M2 (2026-10-04): update rows classified {@code unchanged} from the master index alone - no read of the master record ({@link MasterLoadReads}). */
		long unchangedByIndex;
		/** Lot V3-M2: master records read by primary key to plan a row (zero for an unchanged row in SCAN mode). */
		long masterRecordReads;
		/** Lot V3-M2: {@code Xref} rows read by primary key to plan a row or resolve a reference (zero in SCAN mode, but for an entity whose index was not built). */
		long xrefLookups;
		/** Lot V3-M2: what the entity read before its first row, for the step detail. */
		MasterLoadReads reads;
		/** Lot M-INC (2026-10-12): the scope the entity was loaded in ({@code full} / {@code changed}); {@link #scopeReason} says why it is {@code full} when {@code changed} was asked. */
		String scope = MasterLoadScope.FULL;
		String scopeReason = "";
		/** How the staging rows were read: {@code SCAN_ALL}, {@code KEYS} or {@code FILTERED_SCAN} ({@link MasterLoadScope.ReadMode}). */
		String stagingRead = MasterLoadScope.ReadMode.SCAN_ALL.name();
		/** Journal rows of the entity read at the start (changed: the rows loaded; full: the snapshot a successful load consumes). */
		long journalRows;
		/** Open MASTER rejects of the entity whose staging row was read again (changed scope only). */
		long rejectsReread;
		/** Journal keys whose staging row no longer exists (deleted since). */
		long stagingKeysMissing;
		/** Journal rows deleted once the load ended normally, and whether the entity was armed. */
		long journalConsumed;
		boolean journalArmed;
		/** Coordinator fix, 2026-09-21 (same hazard as {@code ebx.StagingLoader}'s own): a staging field this entity's plan wants to copy into master, that does not exist yet on the PUBLISHED master table (run {@code master/generate}) - never written this run, reported once here instead. */
		final Set<String> missingFields = new LinkedHashSet<String>();
	}

	/** @return false when {@code pHandle} was cancelled mid-way (volume lot 3, task 8) - the caller stops entering further entities too; {@code true} on a normal, complete pass. */
	private static boolean loadEntity(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityId,
		final MasterTransferPlan.Entity pEntity, final AdaptationTable pStagingTable, final AdaptationTable pMasterTable, final Adaptation pMasterDataset,
		final Map<String, String> pStagingForeignKeyFieldByEntityAttribute, final MergeCheck pMergeCheck, final ParentKeys pParentKeys,
		final List<ScriptConstraintCheck.Rule> pScriptRules, final Map<String, RuleRunCounters> pScriptRuleCounters, final Locale pLocale, final int pBatchSize,
		final Date pNow, final Counters pCounters, final List<String> pFirstRejectReasons, final com.on.ebx.factory.staging.StagingLoadListener pListener,
		final FactoryJobs.Handle pHandle, final String pStepId, final Map<String, MasterLoadReads> pReadsByEntity, final boolean pMatchingActivated,
		final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final JournalRun pJournalRun) throws OperationException
	{
		// coordinator fix, 2026-09-21: a staging field can be regenerated (staging/generate ran) before master
		// is (master/generate has not run since) - or the other way for an attribute JUST promoted - so a field
		// this entity's plan wants to copy may not exist yet on the PUBLISHED master table. Tested once per
		// entity (master has no per-feed concept the way staging does - one source table per promoted entity),
		// never per row; same "undefined path throws" hazard as ebx.StagingLoader's own fix, see its Javadoc.
		final Set<String> candidateMasterFields = new HashSet<String>();
		for (final MasterTransferPlan.FieldPlan field : pEntity.fields)
		{
			candidateMasterFields.add(field.masterField);
		}
		final Set<String> existingMasterFields = new HashSet<String>();
		final SchemaNode masterRoot = pMasterTable.getTableOccurrenceRootNode();
		for (final String name : candidateMasterFields)
		{
			if (masterRoot.getNode(Path.parse("./" + name)) != null)
			{
				existingMasterFields.add(name);
			}
		}
		final FieldAvailabilityRule.Result availability = FieldAvailabilityRule.classify(candidateMasterFields, existingMasterFields);
		pCounters.missingFields.addAll(availability.missing);

		// volume lot 3 (2026-09-28, task 7): every field Path this entity could ever need - master fields
		// (write side) and staging fields/staging Ref fields (read side) - resolved ONCE here, never re-parsed
		// per row by planRow/upsertOne any more.
		final Map<String, Path> fieldPaths = new HashMap<String, Path>();
		for (final String name : candidateMasterFields)
		{
			fieldPaths.put(name, Path.parse("./" + name));
		}
		for (final MasterTransferPlan.FieldPlan field : pEntity.fields)
		{
			final String stagingName = field.isForeignKey ? pStagingForeignKeyFieldByEntityAttribute.get(pEntity.name + "#" + field.stagingField) : field.stagingField;
			if (stagingName != null && !fieldPaths.containsKey(stagingName))
			{
				fieldPaths.put(stagingName, Path.parse("./" + stagingName));
			}
		}

		// volume lot 3 (2026-09-28, task 4): every (severity, path) key this entity's own script rules could
		// ever fire on, computed ONCE - see ScriptConstraintCheck#expectedKeys's own Javadoc.
		final Set<String> scriptRuleExpectedKeys = ScriptConstraintCheck.expectedKeys(pScriptRules);

		// EBX refusal classes lot H4 (2026-10-17): what the classifier needs to name the field of a refusal EBX words without it (a master field, the staging field it reads, its target entity)
		final List<RejectClassifier.FieldInfo> fieldInfos = new ArrayList<RejectClassifier.FieldInfo>(pEntity.fields.size());
		for (final MasterTransferPlan.FieldPlan field : pEntity.fields)
		{
			final String stagingName = field.isForeignKey ? pStagingForeignKeyFieldByEntityAttribute.get(pEntity.name + "#" + field.stagingField) : field.stagingField;
			fieldInfos.add(new RejectClassifier.FieldInfo(field.masterField, RejectReason.stagingColumn(stagingName), field.dataType, null, false,
				field.isForeignKey ? field.targetEntityName : null));
		}

		final List<RowPlan> insertBuffer = new ArrayList<RowPlan>();
		final List<RowPlan> updateBuffer = new ArrayList<RowPlan>();
		final List<RejectPlan> rejectBuffer = new ArrayList<RejectPlan>();
		// volume-guards lot (2026-09-28, task item 6): one breaker per entity's own flush loop - see
		// BatchCircuitBreaker's own Javadoc and ebx.StagingLoader#loadRootFeed's matching use.
		final BatchCircuitBreaker breaker = new BatchCircuitBreaker();

		// lot M-INC: the scope of this entity - the journal's rows alone when it can stand for a full read, every staging row otherwise (and then recording starts, disarmed)
		final EntityJournal journal = MasterLoader.chooseScope(pSession, pFactory, pProjectId, pEntityId, pEntity, pStagingTable, pMasterTable, availability.writable, pScriptRules,
			pStagingForeignKeyFieldByEntityAttribute, pJournalRun, pCounters);
		if (journal.changed && journal.keys.isEmpty())
		{
			// nothing changed since the last load: no staging row is read, no index is built
			pListener.started(pEntity.name, MasterLoader.COPY_CELL, 0L);
			pListener.progress(pEntity.name, MasterLoader.COPY_CELL, 0L, 0L, 0L);
			pListener.note(RunTexts.masterNothingChangedEntity(pEntity.name).stored());
			pListener.ended(pEntity.name, MasterLoader.COPY_CELL, true);
			journal.completed = true;
			return true;
		}
		final RowSource rows = RowSource.open(pStagingTable, journal, pCounters);
		// master-live lot: the cell starts with the rows to copy and reports after each batch
		pListener.started(pEntity.name, MasterLoader.COPY_CELL, rows.total());
		boolean ok = false;
		MasterLoadReads reads = null;
		try
		{
			// lot V3-M2: ONE streaming read per table (Xref, master, merged records) before the first row - see MasterLoadReads; LOOKUP mode (bound exceeded,
			// disabled, or a scan failed) keeps the per-row reads of lot V3-M. The indexes are built before the entity's first write.
			// lot M-INC: a few journal rows are read by key (no scan at all): the per-row path of lot V3-M, whose cost follows the number of rows
			reads = journal.readMode == MasterLoadScope.ReadMode.KEYS
				? MasterLoadReads.lookup(pEntity.name, "scope changed: " + journal.keys.size() + " staging row(s) read by key, no index")
				: MasterLoadReads.build(pFactory, pProjectId, pEntity, pMasterTable, rows.tableRows(), pMatchingActivated, pRepository, pMasterDataspaceKey,
					pMasterDatasetName, availability.writable, fieldPaths, pHandle, pListener);
			pCounters.reads = reads;
			pReadsByEntity.put(pEntity.name, reads);
			if (reads.cancelled)
			{
				ok = true; // a clean stop during the index build, nothing was written
				return false;
			}
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				pCounters.read++;
				MasterLoader.planRow(pFactory, pProjectId, pEntity, row, pMasterTable, pStagingForeignKeyFieldByEntityAttribute, availability.writable, pMergeCheck,
					pParentKeys, pCounters, pScriptRules, scriptRuleExpectedKeys, pScriptRuleCounters, pLocale, fieldPaths, insertBuffer, updateBuffer, rejectBuffer, reads);
				// volume lot 3 (2026-09-28, task 3, "rejectBuffer bounded"): the flush threshold now counts
				// rejectBuffer too - an entity that is mostly rejected (a systematic data problem) used to never
				// trigger this check at all (insertBuffer/updateBuffer alone could stay small forever), keeping
				// every RejectPlan of the WHOLE entity in memory until the very end.
				final boolean bufferFull = insertBuffer.size() + updateBuffer.size() + rejectBuffer.size() >= pBatchSize;
				if (bufferFull)
				{
					MasterLoader.flush(pSession, pFactory, pProjectId, pEntityId, pEntity.name, pMasterTable, pMasterDataset, fieldPaths, insertBuffer, updateBuffer,
						rejectBuffer, pNow, pCounters, pFirstRejectReasons, breaker, pStepId, reads, fieldInfos, pParentKeys);
					insertBuffer.clear();
					updateBuffer.clear();
					rejectBuffer.clear();
				}
				// lot V3-M: an unchanged row buffers nothing, so a load where little changed no longer reaches
				// the threshold above at every batch - progress and the stop request are also checked every
				// pBatchSize rows READ, or a fully unchanged entity would report nothing and never be stoppable.
				if (bufferFull || pCounters.read % pBatchSize == 0)
				{
					pListener.progress(pEntity.name, MasterLoader.COPY_CELL, pCounters.read, pCounters.written, pCounters.rejected);
					if (pHandle != null && pHandle.cancelled())
					{
						if (!insertBuffer.isEmpty() || !updateBuffer.isEmpty() || !rejectBuffer.isEmpty())
						{
							// rows already planned must not be dropped by a stop that falls between two flushes
							MasterLoader.flush(pSession, pFactory, pProjectId, pEntityId, pEntity.name, pMasterTable, pMasterDataset, fieldPaths, insertBuffer, updateBuffer,
								rejectBuffer, pNow, pCounters, pFirstRejectReasons, breaker, pStepId, reads, fieldInfos, pParentKeys);
							insertBuffer.clear();
							updateBuffer.clear();
							rejectBuffer.clear();
							pListener.progress(pEntity.name, MasterLoader.COPY_CELL, pCounters.read, pCounters.written, pCounters.rejected);
						}
						ok = true; // a clean stop, not a failure
						return false;
					}
				}
			}
			if (!insertBuffer.isEmpty() || !updateBuffer.isEmpty() || !rejectBuffer.isEmpty())
			{
				MasterLoader.flush(pSession, pFactory, pProjectId, pEntityId, pEntity.name, pMasterTable, pMasterDataset, fieldPaths, insertBuffer, updateBuffer,
					rejectBuffer, pNow, pCounters, pFirstRejectReasons, breaker, pStepId, reads, fieldInfos, pParentKeys);
			}
			pListener.progress(pEntity.name, MasterLoader.COPY_CELL, pCounters.read, pCounters.written, pCounters.rejected);
			// lot V3-M2 fix (2026-10-04): the entity's own pass is over - read its merged keys again once if rows were written, so a child entity (or pass 2)
			// resolving its foreign keys keeps the pre-built answer instead of one live add-on check per row (see MasterLoadReads#refreshMergedKeys)
			reads.refreshMergedKeys(pHandle);
			pListener.note(RunTexts.masterEntityCounts(pEntity.name, pCounters.read, pCounters.written, pCounters.unchanged, pCounters.rejected).stored());
			ok = true;
			journal.completed = true;
			return true;
		}
		finally
		{
			rows.close();
			if (reads != null)
			{
				reads.releaseEntity(); // lot V3-M2: the master index and the layout are only this entity's own rows' business
			}
			pListener.ended(pEntity.name, MasterLoader.COPY_CELL, ok);
		}
	}

	/**
	 * One staging row: resolved field values plus the insert/update/skip decision, or a reject reason - never both.
	 * Also counts a SKIP_MERGED row and, since lot V3-M (2026-10-04), an UNCHANGED row directly (nothing buffered for
	 * either: there is nothing to write).
	 * <p>
	 * <b>The one read of the existing master record</b> happens here, before any {@code Procedure} of this row's own
	 * batch ({@code pMasterTable.lookupAdaptationByPrimaryKey}, by the key {@code Xref} holds): it answers the
	 * merged-away question, feeds the equality comparison, and is handed on in the {@link RowPlan} so {@link
	 * #upsertOne} does not read it again. No predicate read happens on the master table here, and nothing is written
	 * until {@link #flush}, so the "no predicate read interleaved with writes in one Procedure" rule of
	 * {@code docs/VOLUME.md} holds. A vanished record (Xref pointing nowhere) keeps its fallback: the row stays an
	 * update row with no record and {@link #upsertOne} creates it.
	 * <p>
	 * <b>A deferred foreign key on an update row is left out of the plan</b> (it used to be planned as {@code null}
	 * and patched back by pass 2 - a guaranteed change on every row of a self-referencing entity, so such a row
	 * could never be unchanged): pass 2 resolves it and patches only when it differs ({@link
	 * #resolveDeferredReferences}). A staging row with NO deferred reference is still planned as {@code null}, which
	 * clears a reference removed in staging; an insert row also plans {@code null}, as before.
	 */
	private static void planRow(final Adaptation pFactory, final String pProjectId, final MasterTransferPlan.Entity pEntity, final Adaptation pStagingRow,
		final AdaptationTable pMasterTable, final Map<String, String> pStagingForeignKeyFieldByEntityAttribute, final Set<String> pWritableMasterFields, final MergeCheck pMergeCheck,
		final ParentKeys pParentKeys, final Counters pCounters, final List<ScriptConstraintCheck.Rule> pScriptRules, final Set<String> pScriptRuleExpectedKeys,
		final Map<String, RuleRunCounters> pScriptRuleCounters, final Locale pLocale, final Map<String, Path> pFieldPaths,
		final List<RowPlan> pInsertBuffer, final List<RowPlan> pUpdateBuffer, final List<RejectPlan> pRejectBuffer, final MasterLoadReads pReads)
	{
		final String stagingKey = pStagingRow.getOccurrencePrimaryKey().format();
		final String sourceSystem = pStagingRow.getString(MasterLoader.STAGING_SOURCE_SYSTEM);
		final String sourceId = pStagingRow.getString(MasterLoader.STAGING_SOURCE_ID);
		final String loadId = pStagingRow.getString(MasterLoader.MASTER_LOAD_ID);
		final Object rawReceivedAt = pStagingRow.get(MasterLoader.MASTER_RECEIVED_AT);
		final Date receivedAt = rawReceivedAt instanceof Date ? (Date) rawReceivedAt : null;

		// lot V3-M2: in SCAN mode the master key comes from the Xref index and, when the master index knows the key, neither the record nor the add-on is read
		// at all (merged-away from the merged keys, or "never" when matching is not activated); a key the index does not know, a LOOKUP-mode entity or an
		// unreadable merged index takes the single record read of lot V3-M. See MasterLoadReads.
		final String existingMasterKey = pReads.xrefMasterKeys != null ? pReads.xrefMasterKeys.get(stagingKey)
			: MasterLoader.lookupXrefMasterKey(pFactory, pProjectId, pEntity.name, stagingKey);
		if (pReads.xrefMasterKeys == null)
		{
			pCounters.xrefLookups++;
		}
		final boolean hasMasterKey = existingMasterKey != null && !existingMasterKey.trim().isEmpty();
		// lot IDX: the master index is compact (digest words, no object): ask it whether the record is listed, and compare by key when the row is planned
		final boolean indexed = hasMasterKey && pReads.masterIndexed(existingMasterKey);
		Adaptation masterRecord = null;
		final boolean mergedAway;
		if (!hasMasterKey)
		{
			mergedAway = false;
		}
		else if (indexed && (pReads.mergedRead() || pMergeCheck == MergeCheck.NEVER_MERGED))
		{
			mergedAway = pReads.mergedRead() && pReads.isMerged(existingMasterKey);
		}
		else
		{
			masterRecord = pMasterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(existingMasterKey));
			pCounters.masterRecordReads++;
			mergedAway = pMergeCheck.isMergedAway(existingMasterKey, masterRecord);
		}
		// the record exists: read, or listed by the master index (a key the index lacks was read above, and a null read is a vanished record)
		final boolean masterExists = masterRecord != null || indexed;
		final MasterUpsertDecision.Action action = MasterUpsertDecision.decide(hasMasterKey, mergedAway);
		if (action == MasterUpsertDecision.Action.SKIP_MERGED)
		{
			pCounters.skippedMerged++;
			return; // nothing to buffer, see planRow's own Javadoc
		}

		// EBX refusal classes lot H4 (2026-10-17): coded reasons (rejects.RejectReason), the same wording as the staging stage's
		final List<RejectReason> reasons = new ArrayList<RejectReason>();
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		for (final MasterTransferPlan.FieldPlan field : pEntity.fields)
		{
			// coordinator fix, 2026-09-21: this field does not exist yet on the PUBLISHED master table (run
			// master/generate) - skip it entirely for this row, whatever kind it is: no read, no write, not a
			// data error (never added to reasons) - see loadEntity's own comment for where this is reported instead
			if (!pWritableMasterFields.contains(field.masterField))
			{
				continue;
			}
			if (!field.isForeignKey)
			{
				// volume lot 3 (task 7): field Path resolved once per entity (loadEntity's own fieldPaths), never
				// re-parsed per row - a missing entry (should not happen, defensive) falls back to a fresh parse.
				final Path stagingPath = pFieldPaths.get(field.stagingField);
				fields.put(field.masterField, pStagingRow.get(stagingPath != null ? stagingPath : Path.parse("./" + field.stagingField)));
				continue;
			}
			if (field.deferred)
			{
				// self reference, or an edge inside an unresolved cycle: the target (possibly this SAME entity)
				// does not necessarily have a master key yet - never attempted here, only in the second pass
				// (MasterLoader.resolveDeferredReferences), once every promoted entity has completed this pass
				if (masterExists && action == MasterUpsertDecision.Action.UPDATE)
				{
					// lot V3-M: an existing row keeps its stored reference for pass 2 to compare and patch; only a
					// reference REMOVED in staging is cleared here (planned null, written only if the master holds one)
					final String deferredRefField = pStagingForeignKeyFieldByEntityAttribute.get(pEntity.name + "#" + field.stagingField);
					final Path deferredRefPath = deferredRefField == null ? null : pFieldPaths.get(deferredRefField);
					final String deferredRaw = deferredRefField == null ? null
						: pStagingRow.getString(deferredRefPath != null ? deferredRefPath : Path.parse("./" + deferredRefField));
					if (deferredRaw == null || deferredRaw.trim().isEmpty())
					{
						fields.put(field.masterField, null);
					}
					continue;
				}
				fields.put(field.masterField, null);
				continue;
			}
			final String stagingRefField = pStagingForeignKeyFieldByEntityAttribute.get(pEntity.name + "#" + field.stagingField);
			final Path stagingRefPath = stagingRefField == null ? null : pFieldPaths.get(stagingRefField);
			final String rawRef = stagingRefField == null ? null
				: pStagingRow.getString(stagingRefPath != null ? stagingRefPath : Path.parse("./" + stagingRefField));
			if (rawRef == null || rawRef.trim().isEmpty())
			{
				fields.put(field.masterField, null);
				continue;
			}
			final String parentMasterKey = pParentKeys.resolve(field.targetEntityName, rawRef, pCounters);
			if (parentMasterKey == null || parentMasterKey.trim().isEmpty())
			{
				// a parent this very load rejected is the cause (the child is not written either, no orphan, like staging); else the parent has simply not reached master yet
				reasons.add(pParentKeys.parentReason(field.masterField, stagingRefField, field.targetEntityName, rawRef));
			}
			else
			{
				fields.put(field.masterField, parentMasterKey);
			}
		}

		// quality-script-rule lot (2026-09-23): design A - read this staging record's OWN validation report
		// (EBX's validation engine already evaluated the APPLIED constraint when the record was last saved),
		// never call the library function from Java. Guarded by an empty check: a report read is skipped
		// entirely for an entity with no active (APPLIED, non-blank transferPolicy) SCRIPT_CONSTRAINT rule.
		if (!pScriptRules.isEmpty())
		{
			final List<ScriptConstraintCheck.ReportItemView> items = MasterLoader.readReportItems(pStagingRow, pLocale, pScriptRuleExpectedKeys);
			for (final ScriptConstraintCheck.Rule rule : pScriptRules)
			{
				final String firingMessage = ScriptConstraintCheck.firingMessage(rule, items);
				MasterLoader.recordScriptRuleCheck(pScriptRuleCounters, rule, firingMessage == null, stagingKey);
				if (firingMessage != null && rule.isRejectPolicy())
				{
					reasons.add(RejectReason.constraint(RejectCategory.QUALITY_RULE, rule.reasonLabel, firingMessage, ""));
				}
			}
		}

		if (!reasons.isEmpty())
		{
			pRejectBuffer.add(new RejectPlan(stagingKey, sourceSystem, sourceId, loadId, reasons));
			return;
		}
		if (action == MasterUpsertDecision.Action.UPDATE && masterExists)
		{
			// lot V3-M2: the digest of the planned values against the digest the master index holds - equal means every value is already stored, with no read of the record
			if (indexed && pReads.layout != null && pReads.sameAsStored(existingMasterKey, pReads.layout.planned(fields)))
			{
				pCounters.unchanged++;
				pCounters.unchangedByIndex++;
				return;
			}
			if (masterRecord == null)
			{
				// the digests differ (or one is unknown): the record is read, as in lot V3-M, to write it - or to settle what a digest could not
				masterRecord = pMasterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(existingMasterKey));
				pCounters.masterRecordReads++;
				if (masterRecord != null && pMergeCheck != MergeCheck.NEVER_MERGED && pMergeCheck.isMergedAway(existingMasterKey, masterRecord))
				{
					// the merged keys were read before the first write of the entity: a record the add-on merged since is asked live before it is written
					pCounters.skippedMerged++;
					return;
				}
			}
			if (masterRecord != null && !MasterLoader.differsFromStored(fields, masterRecord, pFieldPaths))
			{
				// lot V3-M: every value this load would write is already stored - no write, no Xref write, _loadId and
				// _receivedAt keep naming the load that last CHANGED the row
				pCounters.unchanged++;
				return;
			}
		}
		final RowPlan plan = new RowPlan(stagingKey, existingMasterKey, masterRecord, fields, loadId, receivedAt);
		if (action == MasterUpsertDecision.Action.INSERT)
		{
			pInsertBuffer.add(plan);
		}
		else
		{
			pUpdateBuffer.add(plan);
		}
	}

	/**
	 * Lot V3-M: true when at least one planned value is not what {@code pStored} holds ({@code transfer.StoredValueEquality}:
	 * null = empty string, numbers numerically, dates by time, lists element-wise, anything unprovable = different).
	 * {@code _loadId}/{@code _receivedAt} are not compared - they only change when something else does.
	 */
	private static boolean differsFromStored(final Map<String, Object> pPlanned, final Adaptation pStored, final Map<String, Path> pFieldPaths)
	{
		for (final Map.Entry<String, Object> field : pPlanned.entrySet())
		{
			final Path path = pFieldPaths.get(field.getKey());
			if (!StoredValueEquality.sameValue(field.getValue(), pStored.get(path != null ? path : Path.parse("./" + field.getKey()))))
			{
				return true;
			}
		}
		return false;
	}

	private static void flush(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityId, final String pEntityName,
		final AdaptationTable pMasterTable, final Adaptation pMasterDataset, final Map<String, Path> pFieldPaths, final List<RowPlan> pInsertBuffer,
		final List<RowPlan> pUpdateBuffer, final List<RejectPlan> pRejectBuffer, final Date pNow, final Counters pCounters, final List<String> pFirstRejectReasons,
		final BatchCircuitBreaker pBreaker, final String pStepId, final MasterLoadReads pReads, final List<RejectClassifier.FieldInfo> pFieldInfos,
		final ParentKeys pParentKeys) throws OperationException
	{
		final List<RejectPlan> allRejects = new ArrayList<RejectPlan>(pRejectBuffer);
		final List<RowResult> written = new ArrayList<RowResult>();

		if (!pInsertBuffer.isEmpty())
		{
			final BatchRetry.Outcome<RowPlan> outcome = BatchRetry.commit(pInsertBuffer,
				new MasterAttempt(pSession, pMasterDataset, pMasterTable, true, pFieldPaths, pStepId, pEntityName));
			for (final RowPlan row : outcome.written)
			{
				written.add(new RowResult(row, true));
			}
			for (final BatchRetry.Rejected<RowPlan> rejected : outcome.rejected)
			{
				allRejects.add(new RejectPlan(rejected.item.stagingKey, MasterLoader.str(rejected.item.fields.get("sourceSystem")),
					MasterLoader.str(rejected.item.fields.get("sourceId")), rejected.item.loadId,
					java.util.Collections.singletonList(RejectClassifier.refusal(rejected.reason, pFieldInfos, rejected.item.fields))));
			}
			// volume-guards lot (2026-09-28, task item 6): see ebx.StagingLoader#flushOne's matching guard and
			// BatchCircuitBreaker's own Javadoc - a systematic failure aborts the load rather than burning one
			// Procedure per remaining batch/row.
			if (pBreaker.report(false, outcome.written.isEmpty()))
			{
				throw MasterLoader.circuitBreakerAbort(pEntityName, pBreaker);
			}
		}
		if (!pUpdateBuffer.isEmpty())
		{
			final BatchRetry.Outcome<RowPlan> outcome = BatchRetry.commit(pUpdateBuffer,
				new MasterAttempt(pSession, pMasterDataset, pMasterTable, false, pFieldPaths, pStepId, pEntityName));
			for (final RowPlan row : outcome.written)
			{
				written.add(new RowResult(row, false));
			}
			for (final BatchRetry.Rejected<RowPlan> rejected : outcome.rejected)
			{
				allRejects.add(new RejectPlan(rejected.item.stagingKey, MasterLoader.str(rejected.item.fields.get("sourceSystem")),
					MasterLoader.str(rejected.item.fields.get("sourceId")), rejected.item.loadId,
					java.util.Collections.singletonList(RejectClassifier.refusal(rejected.reason, pFieldInfos, rejected.item.fields))));
			}
			if (pBreaker.report(false, outcome.written.isEmpty()))
			{
				throw MasterLoader.circuitBreakerAbort(pEntityName, pBreaker);
			}
		}

		pCounters.written += written.size();
		pCounters.rejected += allRejects.size();
		for (final RowResult row : written)
		{
			if (row.inserted)
			{
				pCounters.inserted++;
			}
			else
			{
				pCounters.updated++;
			}
		}
		for (final RejectPlan reject : allRejects)
		{
			final String category = reject.first().category;
			final Long soFar = pCounters.rejectedByCategory.get(category);
			pCounters.rejectedByCategory.put(category, Long.valueOf(soFar == null ? 1L : soFar.longValue() + 1L));
			pParentKeys.recordRejected(pEntityName, reject.stagingKey, reject.first());
			if (pFirstRejectReasons.size() < MasterLoader.MAX_REPORTED_REJECTS)
			{
				pFirstRejectReasons.add(pEntityName + " " + reject.sourceSystem + "/" + reject.sourceId + ": " + reject.english());
			}
		}

		if (!allRejects.isEmpty())
		{
			MasterLoader.writeRejects(pSession, pFactory, pProjectId, pEntityId, allRejects, pNow, pStepId, pEntityName);
		}
		if (!written.isEmpty())
		{
			MasterLoader.writeXref(pSession, pFactory, pProjectId, pEntityId, pEntityName, pMasterTable, written, pNow, pStepId);
			// lot V3-M2: the Xref index follows the writes (a child entity resolves its foreign keys through it), and a write ends the pre-built merged keys' use for a parent's keys
			for (final RowResult row : written)
			{
				pReads.recordWritten(row.plan.stagingKey, row.plan.newMasterKey);
			}
		}
	}

	/** Volume-guards lot (2026-09-28, task item 6): the clear abort message {@code #flush} throws once {@link BatchCircuitBreaker#report} trips - same wording family as {@code ebx.StagingLoader#flushOne}'s own. */
	private static OperationException circuitBreakerAbort(final String pEntityName, final BatchCircuitBreaker pBreaker)
	{
		return EngineRefusal.coded("external.loadAborted", "Master load aborted for " + pEntityName + ": " + pBreaker.consecutiveFailures()
			+ " consecutive batch(es) failed entirely (every row rejected, even after retrying row by row) - this looks like a systematic failure "
			+ "(connectivity, a permission dropped mid-run, a schema mismatch) rather than a few bad rows. Rows already written earlier in this run "
			+ "are kept; fix the underlying issue (see the reject reasons above) and re-run.", pEntityName);
	}

	/** {@link BatchRetry.Attempt} plugged with a real EBX write: insert (new occurrence) or update (modify content), the whole batch as one {@code Procedure}, then per-row on failure. */
	private static final class MasterAttempt implements BatchRetry.Attempt<RowPlan>
	{
		private final Session session;
		private final Adaptation masterDataset;
		private final AdaptationTable masterTable;
		private final boolean insert;
		private final Map<String, Path> fieldPaths;
		/** Volume lot 3 (2026-09-28, task 8, "tracking info"): see {@code ebx.StagingLoader.StagingAttempt}'s own matching field. */
		private final String executionInformation;

		MasterAttempt(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable, final boolean pInsert,
			final Map<String, Path> pFieldPaths, final String pStepId, final String pEntityName)
		{
			this.session = pSession;
			this.masterDataset = pMasterDataset;
			this.masterTable = pMasterTable;
			this.insert = pInsert;
			this.fieldPaths = pFieldPaths;
			this.executionInformation = "mdm-factory:" + pStepId + ":master:" + pEntityName;
		}

		@Override
		public String tryBatch(final List<RowPlan> pItems)
		{
			final ProcedureResult result = ProgrammaticService.createForSession(this.session, this.masterDataset.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setExecutionInformation(MasterAttempt.this.executionInformation);
					for (final RowPlan item : pItems)
					{
						MasterLoader.upsertOne(pContext, MasterAttempt.this.masterTable, item, MasterAttempt.this.insert, MasterAttempt.this.fieldPaths);
					}
				}
			});
			return result.hasFailed() ? RefusalHints.header(result.getException()) + MasterLoader.shortText(result.getExceptionFullMessage(Locale.ENGLISH)) : null;
		}

		@Override
		public String tryOne(final RowPlan pItem)
		{
			final ProcedureResult result = ProgrammaticService.createForSession(this.session, this.masterDataset.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setExecutionInformation(MasterAttempt.this.executionInformation);
					MasterLoader.upsertOne(pContext, MasterAttempt.this.masterTable, pItem, MasterAttempt.this.insert, MasterAttempt.this.fieldPaths);
				}
			});
			return result.hasFailed() ? RefusalHints.header(result.getException()) + MasterLoader.shortText(result.getExceptionFullMessage(Locale.ENGLISH)) : null;
		}
	}

	/** Volume lot 3 (2026-09-28, task 7): {@code pFieldPaths} ({@code loadEntity}'s own cache) replaces a fresh {@code Path.parse} per field per row - a missing entry (should not happen, defensive) falls back to a fresh parse. */
	/** Lot V3-M: an update row reuses the record {@link #planRow} already read ({@link RowPlan#existingRecord}) - no second lookup. */
	private static void upsertOne(final ProcedureContext pContext, final AdaptationTable pMasterTable, final RowPlan pRow, final boolean pInsert,
		final Map<String, Path> pFieldPaths) throws Exception
	{
		final ValueContextForUpdate value;
		final Adaptation existing = pInsert ? null : pRow.existingRecord;
		if (pInsert || existing == null) // existing == null defensively covers a Xref pointing at a master row that vanished - falls back to insert rather than throwing
		{
			value = pContext.getContextForNewOccurrence(pMasterTable);
		}
		else
		{
			value = pContext.getContext(existing.getAdaptationName());
		}
		for (final Map.Entry<String, Object> field : pRow.fields.entrySet())
		{
			final Path path = pFieldPaths.get(field.getKey());
			value.setValue(field.getValue(), path != null ? path : Path.parse("./" + field.getKey()));
		}
		value.setValue(pRow.loadId, MasterLoader.MASTER_LOAD_ID);
		value.setValue(pRow.receivedAt, MasterLoader.MASTER_RECEIVED_AT);
		if (pInsert || existing == null)
		{
			pRow.newMasterKey = pContext.doCreateOccurrence(value, pMasterTable).getOccurrencePrimaryKey().format();
		}
		else
		{
			pContext.doModifyContent(existing, value);
			pRow.newMasterKey = pRow.existingMasterKey;
		}
	}

	// ======================================================================================= pass 2: deferred FK

	/**
	 * Re-reads {@code pEntity}'s own STAGING rows a second time (never the master table: the plain attribute
	 * value a deferred field resolves through lives on staging, exactly where {@code planRow} already reads it
	 * for an immediate reference - see {@code MasterModel}'s own Javadoc, "the original attribute is kept as
	 * plain data"), resolves each deferred {@code MasterTransferPlan.FieldPlan} of this entity against {@code
	 * Xref} the same way {@code planRow} resolves an immediate one, and patches only those field(s) on the
	 * already-written master row. A no-op when {@code pEntity} has no deferred field at all (the ordinary case:
	 * neither {@code Company} nor {@code Contact} in the reference demo has one).
	 * <p>
	 * A row not found in {@code Xref} at all (rejected, or skipped as already merged, in pass 1) is skipped -
	 * nothing to patch. A row found but whose deferred reference still cannot be resolved (its own target row
	 * was itself rejected, or a real orphan - e.g. a root account with no parent) is left {@code null} and
	 * counted {@link Counters#deferredUnresolved} - never a reject: the row itself already exists in master,
	 * and un-writing it would cascade into whatever already references it, the same posture {@code
	 * StagingLoader#resolveDeferredReferences} already takes one zone earlier.
	 */
	/**
	 * Volume lot 2a (2026-09-28, docs/VOLUME.md, task 4) - restructured in two phases, never overlapping:
	 * <ol>
	 * <li>a server-side predicate (an {@code or} of {@code osd:is-not-null(...)} over every deferred field's
	 * own staging ref field) restricts the scan to rows that carry AT LEAST ONE deferred reference at all -
	 * most rows of a self-referencing entity have none (e.g. a root account with no parent), and those never
	 * even reach an {@code Xref} lookup any more. Collects only staging PRIMARY KEY strings, never full rows.</li>
	 * <li>the {@link RequestResult} above is fully closed before any write; each candidate row is re-read fresh
	 * by primary key (checking {@code rawRef} again, per field, BEFORE the {@code Xref} lookup - a row whose
	 * only non-null field is one the predicate matched on a DIFFERENT row is otherwise possible with an
	 * {@code or} predicate) and each batch's own {@link Procedure} never races a still-open scan of the same
	 * tables.</li>
	 * </ol>
	 * Also compares each resolved value against the master row's current one and skips the write when unchanged.
	 */
	/** @return false when {@code pHandle} was cancelled mid-way (volume lot 3, task 8) - the caller stops iterating further entities too; {@code true} on a normal, complete pass (including the no-op "nothing deferred" case). */
	private static boolean resolveDeferredReferences(final Session pSession, final Adaptation pFactory, final String pProjectId, final MasterTransferPlan.Entity pEntity,
		final AdaptationTable pStagingTable, final AdaptationTable pMasterTable, final Adaptation pMasterDataset,
		final Map<String, String> pStagingForeignKeyFieldByEntityAttribute, final ParentKeys pParentKeys, final int pBatchSize, final Counters pCounters,
		final FactoryJobs.Handle pHandle, final Map<String, MasterLoadReads> pReadsByEntity) throws OperationException
	{
		final List<MasterTransferPlan.FieldPlan> deferredFields = new ArrayList<MasterTransferPlan.FieldPlan>();
		final StringBuilder predicate = new StringBuilder();
		for (final MasterTransferPlan.FieldPlan field : pEntity.fields)
		{
			if (!field.isForeignKey || !field.deferred)
			{
				continue;
			}
			deferredFields.add(field);
			final String stagingRefField = pStagingForeignKeyFieldByEntityAttribute.get(pEntity.name + "#" + field.stagingField);
			if (stagingRefField != null)
			{
				if (predicate.length() > 0)
				{
					predicate.append(" or ");
				}
				predicate.append("osd:is-not-null(./").append(stagingRefField).append(")");
			}
		}
		if (deferredFields.isEmpty())
		{
			return true;
		}

		final List<String> candidateStagingKeys = new ArrayList<String>();
		if (predicate.length() > 0) // empty only if no deferred field's ref field name could be resolved - defensive, should not happen
		{
			final RequestResult rows = pStagingTable.createRequestResult(predicate.toString());
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					candidateStagingKeys.add(row.getOccurrencePrimaryKey().format());
				}
			}
			finally
			{
				rows.close();
			}
		}

		final List<RefUpdate> buffer = new ArrayList<RefUpdate>();
		for (final String stagingKey : candidateStagingKeys)
		{
			final Adaptation row = pStagingTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(stagingKey));
			if (row == null)
			{
				continue; // deleted meanwhile - defensive
			}
			boolean hasAnyReference = false;
			for (final MasterTransferPlan.FieldPlan field : deferredFields)
			{
				final String stagingRefField = pStagingForeignKeyFieldByEntityAttribute.get(pEntity.name + "#" + field.stagingField);
				final String rawRef = stagingRefField == null ? null : row.getString(Path.parse("./" + stagingRefField));
				if (rawRef != null && !rawRef.trim().isEmpty())
				{
					hasAnyReference = true;
					break;
				}
			}
			if (!hasAnyReference)
			{
				continue; // the OR predicate matched a DIFFERENT row than expected, or the field was cleared since - nothing to resolve here, no Xref lookup needed
			}

			// lot V3-M2: from the entity's Xref index when pass 1 kept it (it holds the keys inserted in pass 1 too), else one lookup as before
			final String ownMasterKey = MasterLoader.xrefMasterKey(pReadsByEntity, pFactory, pProjectId, pEntity.name, stagingKey, pCounters);
			if (ownMasterKey == null || ownMasterKey.trim().isEmpty())
			{
				continue; // never written in pass 1 (rejected, or skipped as already merged) - nothing to patch
			}
			final Adaptation masterRow = pMasterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(ownMasterKey));
			if (masterRow == null)
			{
				continue; // defensive: Xref pointing at a master row that vanished
			}

			final Map<String, Object> updates = new LinkedHashMap<String, Object>();
			int resolvedInUpdates = 0;
			for (final MasterTransferPlan.FieldPlan field : deferredFields)
			{
				final String stagingRefField = pStagingForeignKeyFieldByEntityAttribute.get(pEntity.name + "#" + field.stagingField);
				final String rawRef = stagingRefField == null ? null : row.getString(Path.parse("./" + stagingRefField));
				if (rawRef == null || rawRef.trim().isEmpty())
				{
					continue; // legitimately no reference on this row (e.g. a root account) - nothing to resolve, not unresolved
				}
				final String parentMasterKey = pParentKeys.resolve(field.targetEntityName, rawRef, pCounters);
				if (parentMasterKey == null || parentMasterKey.trim().isEmpty())
				{
					pCounters.deferredUnresolved++;
					// lot V3-M: pass 1 no longer nulls the deferred field of an existing row, so a reference that
					// cannot be resolved now must not keep a value stored by an earlier load (the end state pass 1's
					// null used to give): clear it, only when the master actually holds one
					final String staleValue = masterRow.getString(Path.parse("./" + field.masterField));
					if (staleValue != null && !staleValue.isEmpty())
					{
						updates.put(field.masterField, null);
					}
					continue;
				}
				pCounters.deferredResolved++;
				final String currentValue = masterRow.getString(Path.parse("./" + field.masterField));
				if (!parentMasterKey.equals(currentValue))
				{
					updates.put(field.masterField, parentMasterKey);
					resolvedInUpdates++;
				}
			}
			if (!updates.isEmpty())
			{
				buffer.add(new RefUpdate(ownMasterKey, updates, resolvedInUpdates));
				if (buffer.size() >= pBatchSize)
				{
					MasterLoader.flushAndCount(pSession, pMasterDataset, pMasterTable, buffer, pCounters);
					buffer.clear();
					if (pHandle != null && pHandle.cancelled())
					{
						return false; // volume lot 3 (task 8): a clean stop - the caller stops iterating further entities too
					}
				}
			}
		}
		if (!buffer.isEmpty())
		{
			MasterLoader.flushAndCount(pSession, pMasterDataset, pMasterTable, buffer, pCounters);
		}
		return true;
	}

	private static final class RefUpdate
	{
		final String masterKey;
		final Map<String, Object> fields;
		/** How many of {@link #fields} are references counted resolved when this update was planned (the others clear a stale value, counted unresolved already). */
		final int resolvedFields;

		RefUpdate(final String pMasterKey, final Map<String, Object> pFields, final int pResolvedFields)
		{
			this.masterKey = pMasterKey;
			this.fields = pFields;
			this.resolvedFields = pResolvedFields;
		}
	}

	/**
	 * Writes one batch of deferred references. The pass-1 writes are already committed and a failure here must not undo them, so it is not thrown: it is
	 * returned to the counters instead - the references of the failed batch were counted resolved when planned, so they move to unresolved and the first reason
	 * is kept for the step's message ({@link DeferredFlushOutcome}).
	 */
	private static void flushAndCount(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable, final List<RefUpdate> pUpdates,
		final Counters pCounters)
	{
		final String error = MasterLoader.flushRefUpdates(pSession, pMasterDataset, pMasterTable, pUpdates);
		if (error == null)
		{
			return;
		}
		long lost = 0L;
		for (final RefUpdate update : pUpdates)
		{
			lost += update.resolvedFields;
		}
		final long[] moved = DeferredFlushOutcome.moveToUnresolved(pCounters.deferredResolved, pCounters.deferredUnresolved, lost);
		pCounters.deferredResolved = moved[0];
		pCounters.deferredUnresolved = moved[1];
		pCounters.deferredWriteFailedRows += pUpdates.size();
		pCounters.deferredWriteLost += lost;
		if (pCounters.deferredWriteError == null)
		{
			pCounters.deferredWriteError = error;
		}
	}

	/** One batch of deferred references in one procedure; null when it was written, else why not (also logged). */
	private static String flushRefUpdates(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable, final List<RefUpdate> pUpdates)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pMasterDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final RefUpdate update : pUpdates)
				{
					final Adaptation row = pMasterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(update.masterKey));
					if (row == null)
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					for (final Map.Entry<String, Object> field : update.fields.entrySet())
					{
						value.setValue(field.getValue(), Path.parse("./" + field.getKey()));
					}
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			final String why = result.getExceptionFullMessage(Locale.ENGLISH);
			LoggingCategory.getKernel().error("[mdm-factory] could not resolve " + pUpdates.size() + " deferred master reference(s): " + why);
			return why;
		}
		return null;
	}

	// ======================================================================================================= writes

	/**
	 * Volume lot 3 (2026-09-28, docs/VOLUME.md task 6, "rejects deduplicated"): {@code TransferReject}'s primary
	 * key is now the natural key {@code (project, entity, stage, sourceSystem, sourceId)} - see {@code
	 * ebx.StagingLoader#writeRejects}' own matching Javadoc for the full reasoning, identical here.
	 */
	private static void writeRejects(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityId, final List<RejectPlan> pRejects,
		final Date pNow, final String pStepId, final String pEntityName) throws OperationException
	{
		final AdaptationTable rejectsTable = pFactory.getTable(FactoryPaths.TRANSFER_REJECT);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				// volume lot 3 (task 8, "tracking info")
				pContext.setExecutionInformation("mdm-factory:" + pStepId + ":master-reject:" + pEntityName);
				for (final RejectPlan reject : pRejects)
				{
					final Adaptation existing = rejectsTable.lookupAdaptationByPrimaryKey(rejectsTable
						.computePrimaryKey(new Object[] { pProjectId, pEntityId, MasterLoader.REJECT_STAGE, reject.sourceSystem, reject.sourceId }));
					final ValueContextForUpdate value = existing == null ? pContext.getContextForNewOccurrence(rejectsTable) : pContext.getContext(existing.getAdaptationName());
					value.setValue(pProjectId, FactoryPaths.TransferReject.PROJECT);
					value.setValue(pEntityId, FactoryPaths.TransferReject.ENTITY);
					value.setValue(MasterLoader.REJECT_STAGE, FactoryPaths.TransferReject.STAGE);
					value.setValue(reject.sourceSystem, FactoryPaths.TransferReject.SOURCE_SYSTEM);
					value.setValue(reject.sourceId, FactoryPaths.TransferReject.SOURCE_ID);
					value.setValue(reject.loadId, FactoryPaths.TransferReject.LOAD_ID);
					value.setValue(RejectReasons.store(reject.reasons, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.TransferReject.REASONS);
					// EBX refusal classes lot H4 (2026-10-17): the cause, what identical reasons share and EBX's raw text, as the staging stage writes them
					final RejectReason firstReason = reject.first();
					value.setValue(firstReason.category, FactoryPaths.TransferReject.CATEGORY);
					value.setValue(firstReason.signature(), FactoryPaths.TransferReject.REASON_CODE);
					value.setValue(firstReason.detail.isEmpty() ? null : TextLimits.truncate(firstReason.detail, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.TransferReject.DETAIL);
					value.setValue(pNow, FactoryPaths.TransferReject.REJECTED_AT);
					value.setValue(Boolean.FALSE, FactoryPaths.TransferReject.RESOLVED);
					value.setValue(Boolean.FALSE, FactoryPaths.TransferReject.ACKNOWLEDGED); // wave 10 (audit F-38): a new rejection opens it again, the last review stays on the row
					if (existing == null)
					{
						pContext.doCreateOccurrence(value, rejectsTable);
					}
					else
					{
						pContext.doModifyContent(existing, value);
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveRejects", "Could not record " + pRejects.size() + " master transfer reject(s): " + result.getExceptionFullMessage(Locale.ENGLISH), pRejects.size());
		}
	}

	/**
	 * Writes {@code Xref.masterTable}/{@code masterKey} back onto every {@code Xref} row this batch just inserted
	 * or updated in master - one row per staged source record, idempotent lookup by (project, stagingTable,
	 * stagingKey) exactly like {@code StagingLoader.writeXref}. Volume lot 3 (task 6): also resolves an earlier
	 * MASTER-stage {@code TransferReject} of the SAME (project, entity, sourceSystem, sourceId) when
	 * {@link RowResult#inserted} - an UPDATE row already reached master once before, so it can never have one
	 * open (see {@code ebx.StagingLoader.writeXref}'s matching Javadoc for the identical reasoning) - one extra
	 * indexed PK lookup per INSERT, in the SAME procedure/round trip as the Xref write itself.
	 * <p>
	 * <b>Lot V3-M (2026-10-04): an {@code Xref} row that already holds this {@code masterTable} and {@code masterKey}
	 * is not written again.</b> That is the ordinary case of an update row (its key came out of this very
	 * {@code Xref} row, in {@link #planRow}), and it used to cost a second {@code doModifyContent} per row with
	 * values that had not changed. The {@code Xref} rows are read BEFORE the {@code Procedure} (indexed PK lookups,
	 * the {@code Xref} table is not written until that Procedure runs), the {@code Procedure} receives only the rows
	 * that need something - an insert (always: its reject may need resolving, and its key is new) or a changed key -
	 * and no {@code Procedure} is opened when nothing needs writing.
	 */
	private static void writeXref(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityId, final String pEntityName,
		final AdaptationTable pMasterTable, final List<RowResult> pWritten, final Date pNow, final String pStepId) throws OperationException
	{
		final AdaptationTable xrefTable = pFactory.getTable(FactoryPaths.XREF);
		final AdaptationTable rejectsTable = pFactory.getTable(FactoryPaths.TRANSFER_REJECT);
		final String masterTablePath = MasterModel.tablePath(pEntityName);
		final List<XrefWork> work = new ArrayList<XrefWork>();
		for (final RowResult row : pWritten)
		{
			// volume lot 2a (2026-09-28, docs/VOLUME.md): indexed PK lookup - Xref's natural key
			// (project, stagingTable, stagingKey) is fully known here already, no scan needed.
			final Adaptation existing = xrefTable
				.lookupAdaptationByPrimaryKey(xrefTable.computePrimaryKey(new Object[] { pProjectId, pEntityName, row.plan.stagingKey }));
			if (existing == null) // defensive: STAGING_LOAD always writes one Xref row per staged row before MASTER_LOAD can run
			{
				continue;
			}
			final boolean xrefAlreadyCurrent = row.plan.newMasterKey != null && masterTablePath.equals(existing.getString(FactoryPaths.Xref.MASTER_TABLE))
				&& row.plan.newMasterKey.equals(existing.getString(FactoryPaths.Xref.MASTER_KEY));
			if (xrefAlreadyCurrent && !row.inserted)
			{
				continue; // nothing to write, nothing to resolve (an update row never has an open reject)
			}
			work.add(new XrefWork(row, existing, !xrefAlreadyCurrent));
		}
		if (work.isEmpty())
		{
			return;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				// volume lot 3 (task 8, "tracking info")
				pContext.setExecutionInformation("mdm-factory:" + pStepId + ":xref:" + pEntityName);
				for (final XrefWork item : work)
				{
					final RowResult row = item.row;
					if (item.writeXref)
					{
						final ValueContextForUpdate value = pContext.getContext(item.xref.getAdaptationName());
						value.setValue(masterTablePath, FactoryPaths.Xref.MASTER_TABLE);
						value.setValue(row.plan.newMasterKey, FactoryPaths.Xref.MASTER_KEY);
						pContext.doModifyContent(item.xref, value);
					}

					if (row.inserted && pEntityId != null)
					{
						final String sourceSystem = MasterLoader.str(row.plan.fields.get("sourceSystem"));
						final String sourceId = MasterLoader.str(row.plan.fields.get("sourceId"));
						if (sourceSystem != null && sourceId != null)
						{
							final Adaptation priorReject = rejectsTable.lookupAdaptationByPrimaryKey(
								rejectsTable.computePrimaryKey(new Object[] { pProjectId, pEntityId, MasterLoader.REJECT_STAGE, sourceSystem, sourceId }));
							if (priorReject != null && !Boolean.TRUE.equals(priorReject.get(FactoryPaths.TransferReject.RESOLVED)))
							{
								final ValueContextForUpdate rejectValue = pContext.getContext(priorReject.getAdaptationName());
								rejectValue.setValue(Boolean.TRUE, FactoryPaths.TransferReject.RESOLVED);
								pContext.doModifyContent(priorReject, rejectValue);
							}
						}
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.updateXref", "Could not update " + work.size() + " Xref row(s) of " + pEntityName + " with their master key: "
				+ result.getExceptionFullMessage(Locale.ENGLISH), work.size());
		}
	}

	/** One written row whose {@code Xref} needs attention: {@code writeXref} false when the {@code Xref} row already holds the key and only an insert's prior reject is left to resolve. */
	private static final class XrefWork
	{
		final RowResult row;
		final Adaptation xref;
		final boolean writeXref;

		XrefWork(final RowResult pRow, final Adaptation pXref, final boolean pWriteXref)
		{
			this.row = pRow;
			this.xref = pXref;
			this.writeXref = pWriteXref;
		}
	}

	// ======================================================================================================= reads

	/**
	 * Volume lot 2a (2026-09-28, docs/VOLUME.md): {@code Xref}'s primary key is now the natural key
	 * {@code (project, stagingTable, stagingKey)} - this is an indexed {@code lookupAdaptationByPrimaryKey}
	 * call, never a predicate scan any more.
	 */
	private static String lookupXrefMasterKey(final Adaptation pFactory, final String pProjectId, final String pStagingTable, final String pStagingKey)
	{
		final AdaptationTable xrefTable = pFactory.getTable(FactoryPaths.XREF);
		final Adaptation row = xrefTable.lookupAdaptationByPrimaryKey(xrefTable.computePrimaryKey(new Object[] { pProjectId, pStagingTable, pStagingKey }));
		return row == null ? null : row.getString(FactoryPaths.Xref.MASTER_KEY);
	}

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	/** Package-private (not {@code private}) since 2026-09-22: {@code ebx.MasterReset} reuses it, read-only, to recompute the project's promoted master table paths for its own metadataset guard - see that class' own Javadoc. */
	static List<StagingModel.EntityInfo> readEntities(final Adaptation pFactory, final String pProjectId, final Map<String, String> pEntityIdByName)
	{
		final List<Adaptation> entityRowsList = new ArrayList<Adaptation>();
		final Map<String, String> entityIdToName = new LinkedHashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				entityRowsList.add(row);
				final String id = row.getOccurrencePrimaryKey().format();
				final String name = row.getString(FactoryPaths.StagingEntity.NAME);
				entityIdToName.put(id, name);
				pEntityIdByName.put(name, id);
			}
		}
		finally
		{
			entityRows.close();
		}

		final List<StagingModel.EntityInfo> out = new ArrayList<StagingModel.EntityInfo>();
		for (final Adaptation row : entityRowsList)
		{
			final String entityId = row.getOccurrencePrimaryKey().format();
			final List<String> memberNames = new ArrayList<String>();
			final List<?> rawMemberIds = row.getList(FactoryPaths.StagingEntity.MEMBER_ENTITIES);
			if (rawMemberIds != null)
			{
				for (final Object rawMemberId : rawMemberIds)
				{
					final String memberName = entityIdToName.get(String.valueOf(rawMemberId));
					if (memberName != null)
					{
						memberNames.add(memberName);
					}
				}
			}
			out.add(new StagingModel.EntityInfo(row.getString(FactoryPaths.StagingEntity.NAME), row.getString(FactoryPaths.StagingEntity.LABEL),
				row.getString(FactoryPaths.StagingEntity.STATUS), MasterLoader.readAttributes(pFactory, entityId, entityIdToName), memberNames));
		}
		return out;
	}

	private static List<StagingModel.AttributeInfo> readAttributes(final Adaptation pFactory, final String pEntityId, final Map<String, String> pEntityIdToName)
	{
		final List<StagingModel.AttributeInfo> out = new ArrayList<StagingModel.AttributeInfo>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object rawMaxLength = row.get(FactoryPaths.StagingAttribute.MAX_LENGTH);
				final Integer maxLength = rawMaxLength instanceof Number ? Integer.valueOf(((Number) rawMaxLength).intValue()) : null;
				final boolean mandatory = Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.MANDATORY));
				final boolean primaryKey = Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY));
				final String referenceEntityId = row.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
				final String referenceEntityName = referenceEntityId == null ? null : pEntityIdToName.get(referenceEntityId);
				out.add(new StagingModel.AttributeInfo(row.getString(FactoryPaths.StagingAttribute.NAME), row.getString(FactoryPaths.StagingAttribute.LABEL),
					row.getString(FactoryPaths.StagingAttribute.DATA_TYPE), maxLength, mandatory, primaryKey, referenceEntityName,
					row.getString(FactoryPaths.StagingAttribute.STATUS)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static int batchSize(final Adaptation pFactory) throws OperationException
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (settings == null)
		{
			throw EngineRefusal.coded("missing.settings", "MDM Factory settings: the DEFAULT row is missing");
		}
		final Object raw = settings.get(FactoryPaths.Settings.TRANSFER_BATCH_SIZE);
		final int value = raw instanceof Number ? ((Number) raw).intValue() : 0;
		return value > 0 ? value : MasterLoader.DEFAULT_BATCH_SIZE;
	}

	// ======================================================================================= script constraint enforcement

	/** {@code CapabilitySuggestion}'s own run-wide execution result (quality-script-rule lot, 2026-09-23) - the exact same shape/purpose as {@code ebx.StagingLoader}'s own {@code RuleRunCounters} for {@code QualityRule}, kept as a separate (identically-shaped) class since the two tables/paths are distinct. */
	private static final class RuleRunCounters
	{
		long checked;
		long failing;
		final List<String> exampleKeys = new ArrayList<String>();
	}

	private static void recordScriptRuleCheck(final Map<String, RuleRunCounters> pRuleCounters, final ScriptConstraintCheck.Rule pRule, final boolean pPassed,
		final String pRowKey)
	{
		RuleRunCounters counters = pRuleCounters.get(pRule.id);
		if (counters == null)
		{
			counters = new RuleRunCounters();
			pRuleCounters.put(pRule.id, counters);
		}
		counters.checked++;
		if (!pPassed)
		{
			counters.failing++;
			if (counters.exampleKeys.size() < MasterLoader.MAX_REPORTED_REJECTS)
			{
				counters.exampleKeys.add(pRowKey);
			}
		}
	}

	/**
	 * Every APPLIED {@code CapabilitySuggestion} of this entity that is a {@code SCRIPT_CONSTRAINT} on zone
	 * {@code STAGING} with a non-blank {@code transferPolicy} - the set {@link #planRow} must enforce for every
	 * staging row of this entity. A row with a blank/unrecognised {@code transferPolicy} (the default: the
	 * constraint stays a pure flag, unchanged behaviour) is filtered out here, never reaches {@link
	 * ScriptConstraintCheck}, same discipline {@code ebx.StagingLoader.readQualityRules} already follows for
	 * {@code MODEL_CONSTRAINT}. {@link ConstraintBodyBuilder#extractReportedMessage} is run once per rule here
	 * (not per row) against the row's own stored {@code scriptBody} - pure, no EBX call.
	 */
	private static List<ScriptConstraintCheck.Rule> readScriptConstraintRules(final Adaptation pFactory, final String pProjectId, final String pEntityId)
	{
		final List<ScriptConstraintCheck.Rule> out = new ArrayList<ScriptConstraintCheck.Rule>();
		if (pEntityId == null)
		{
			return out;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./entity=" + CatalogReader.quote(pEntityId) + " and ./nature='SCRIPT_CONSTRAINT'"
				+ " and ./zone='STAGING' and ./status='APPLIED'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String transferPolicy = MasterLoader.trimmedOrNull(row.getString(FactoryPaths.CapabilitySuggestion.TRANSFER_POLICY));
				if (!ScriptConstraintCheck.WARN.equals(transferPolicy) && !ScriptConstraintCheck.REJECT.equals(transferPolicy))
				{
					continue; // blank, or any other value: unenforced - a pure flag on the staging validation report, unchanged
				}
				final String constraintScope = row.getString(FactoryPaths.CapabilitySuggestion.CONSTRAINT_SCOPE);
				final String appliedElementPath = row.getString(FactoryPaths.CapabilitySuggestion.APPLIED_ELEMENT_PATH);
				final String severity = row.getString(FactoryPaths.CapabilitySuggestion.SEVERITY);
				final String scriptBody = row.getString(FactoryPaths.CapabilitySuggestion.SCRIPT_BODY);
				final String targetFunction = row.getString(FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION);
				String alias = MasterLoader.trimmedOrNull(row.getString(FactoryPaths.CapabilitySuggestion.TARGET_IMPORT_NAME));
				if (alias == null)
				{
					alias = row.getString(FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY);
				}
				final String expectedPath = ScriptConstraintCheck.expectedPathFor(constraintScope, appliedElementPath);
				final String scope = "NODE".equals(constraintScope) && !".".equals(expectedPath) ? expectedPath.substring(2) : "record";
				final String reasonLabel = alias + "." + targetFunction + " on " + scope;
				out.add(new ScriptConstraintCheck.Rule(row.getOccurrencePrimaryKey().format(), expectedPath, ScriptConstraintCheck.expectedSeverityLabelFor(severity),
					ConstraintBodyBuilder.extractReportedMessage(scriptBody, severity), transferPolicy, reasonLabel));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static String trimmedOrNull(final String pValue)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return null;
		}
		return pValue.trim();
	}

	/**
	 * {@code pStagingRow}'s OWN validation report ({@code Adaptation#getValidationReport()}, per-RECORD - never
	 * the table-wide overload, same choice {@code ebx.CapabilityApplier} made after {@code
	 * fix/measure-observability} found the table-wide report can miss record-level items, see {@code
	 * docs/CAPABILITY-APPLY.md} sec 6b), reduced to the three facts {@link ScriptConstraintCheck#firingMessage}
	 * needs - severity WARNING or above only (every {@code transferPolicy} rule's own {@code expectedSeverityLabel}
	 * is WARNING or ERROR, never INFO, so INFO items are never walked). An item whose subject is not a per-record
	 * one ({@code getSubjectForAdaptation() == null} - a table-shaped subject, never expected from a per-record
	 * report) is skipped defensively rather than throwing.
	 * <p>
	 * <b>Volume lot 3 (2026-09-28, task 4):</b> {@code pExpectedKeys} ({@link ScriptConstraintCheck#expectedKeys},
	 * computed once per entity) gates {@code item.getMessage().formatMessage(pLocale)} - the actual, potentially
	 * expensive rendering step - to only the items whose (severity, path) could possibly match ONE of this
	 * entity's own rules. A staging record can carry validation warnings entirely unrelated to any enforced
	 * script constraint (a MANDATORY facet, an unrelated PATTERN rule's own constraint, ...); before this lot,
	 * every one of them was formatted anyway, on every row, only to be discarded by {@link
	 * ScriptConstraintCheck#firingMessage}'s own path+severity gate a moment later. An item that fails this
	 * coarse filter can never pass {@code firingMessage}'s own {@code onExpectedPath} gate either (both use the
	 * exact same key), so its {@code renderedMessage} is safely left {@code null} - never read by any caller.
	 */
	private static List<ScriptConstraintCheck.ReportItemView> readReportItems(final Adaptation pStagingRow, final Locale pLocale, final Set<String> pExpectedKeys)
	{
		final List<ScriptConstraintCheck.ReportItemView> out = new ArrayList<ScriptConstraintCheck.ReportItemView>();
		final ValidationReport report = pStagingRow.getValidationReport();
		final ValidationReportItemIterator items = report.getItemsOfSeverityOrMore(Severity.WARNING);
		try
		{
			while (items.hasNext())
			{
				final ValidationReportItem item = items.nextItem();
				final ValidationReportItemSubjectForAdaptation subject = item.getSubjectForAdaptation();
				if (subject == null)
				{
					continue;
				}
				final Severity itemSeverity = item.getSeverity();
				final String severityLabel = itemSeverity == null ? "UNKNOWN" : itemSeverity.toParsableString();
				final String subjectPath = subject.getPathInAdaptation() == null ? null : subject.getPathInAdaptation().format();
				final boolean couldMatch = pExpectedKeys.contains(MeasurementMatch.key(severityLabel, subjectPath));
				final String renderedMessage = couldMatch && item.getMessage() != null ? item.getMessage().formatMessage(pLocale) : null;
				out.add(new ScriptConstraintCheck.ReportItemView(severityLabel, subjectPath, renderedMessage));
			}
		}
		finally
		{
			items.close();
		}
		return out;
	}

	/**
	 * Writes {@code CapabilitySuggestion.lastLoadChecked}/{@code lastLoadFailing}/{@code lastLoadExamples}/
	 * {@code lastLoadCheckedAt} for every rule actually enforced this run (a suggestion id only ever ends up in
	 * {@code pRuleCounters} via {@link #recordScriptRuleCheck}, itself only ever called on a rule {@link
	 * #readScriptConstraintRules} already filtered to a non-blank {@code transferPolicy}). Best-effort like
	 * {@link #flushRefUpdates}: a failure here is logged, not thrown, so a bookkeeping hiccup never undoes the
	 * transfer's own already-committed writes.
	 */
	private static void writeScriptRuleCounters(final Session pSession, final Adaptation pFactory, final Map<String, RuleRunCounters> pRuleCounters, final Date pNow)
	{
		if (pRuleCounters.isEmpty())
		{
			return;
		}
		final AdaptationTable suggestionsTable = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Map.Entry<String, RuleRunCounters> entry : pRuleCounters.entrySet())
				{
					final Adaptation row = suggestionsTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(entry.getKey()));
					if (row == null)
					{
						continue;
					}
					final RuleRunCounters counters = entry.getValue();
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					// 'long'-declared fields are xs:decimal in this DSL: BigDecimal, never a java.lang.Long - same convention ebx.StagingLoader's own writeQualityRuleCounters follows
					value.setValue(BigDecimal.valueOf(counters.checked), FactoryPaths.CapabilitySuggestion.LAST_LOAD_CHECKED);
					value.setValue(BigDecimal.valueOf(counters.failing), FactoryPaths.CapabilitySuggestion.LAST_LOAD_FAILING);
					value.setValue(counters.exampleKeys.isEmpty() ? null : TextLimits.truncate(MasterLoader.join(counters.exampleKeys), TextLimits.MESSAGE_MAX_LENGTH),
						FactoryPaths.CapabilitySuggestion.LAST_LOAD_EXAMPLES);
					value.setValue(pNow, FactoryPaths.CapabilitySuggestion.LAST_LOAD_CHECKED_AT);
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error(
				"[mdm-factory] transfer ran, but writing back " + pRuleCounters.size() + " script constraint counter(s) failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	// ==================================================================================================== merge check

	/** Whether a master record has already been merged away into another one - see the class Javadoc for why this is EBX-bound and kept behind an interface rather than folded into {@code master.MasterUpsertDecision} (which stays pure and unit-tested on the resulting boolean alone). */
	public interface MergeCheck
	{
		boolean isMergedAway(String pMasterKey);

		/**
		 * Lot V3-M (2026-10-04): same question when the caller has ALREADY read the master record ({@code pMasterRecord},
		 * null when it does not exist) - answers from it instead of looking the key up a second time.
		 * {@code ebx.MasterLoader#planRow} reads the record once for the merged-away check AND the changed-value check.
		 */
		boolean isMergedAway(String pMasterKey, Adaptation pMasterRecord);

		/**
		 * Volume lot 3 (2026-09-28, docs/VOLUME.md task 5, "light merge check"): a check that answers "never
		 * merged" for every key without touching the add-on OR the master table at all - correct, not merely a
		 * fallback, whenever matching has never been ACTIVATED for the entity being checked (nothing could
		 * possibly be merged away yet). {@code ebx.MasterLoader.loadInternal} uses this instead of building a
		 * real {@link RecordMetadataMergeCheck} - which would otherwise pay one master-record lookup PLUS one
		 * {@code MetadataHelper} call for every single UPDATE row of every project that has a master zone but
		 * has not yet run matching (a common, ordinary state - matching is a later wizard step) - whenever
		 * {@code MatchPlan.status != APPLIED} for that entity, the exact same fact {@code
		 * MasterLoader.ParentKeys#matchingActivated} already gates its own add-on hop on.
		 */
		MergeCheck NEVER_MERGED = new MergeCheck()
		{
			@Override
			public boolean isMergedAway(final String pMasterKey)
			{
				return false;
			}

			@Override
			public boolean isMergedAway(final String pMasterKey, final Adaptation pMasterRecord)
			{
				return false;
			}
		};
	}

	/**
	 * Reads the master record's own state through {@link MameRecordState} (public API, {@code
	 * MetadataHelper.getState}) - see the class Javadoc for why this no longer touches the add-on's metadataset
	 * directly (refactored 2026-09-24).
	 */
	static final class RecordMetadataMergeCheck implements MergeCheck
	{
		private final AdaptationTable masterTable;

		RecordMetadataMergeCheck(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final String pMasterTablePath)
		{
			AdaptationTable table = null;
			try
			{
				final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pMasterDataspaceKey));
				final Adaptation masterDataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pMasterDatasetName));
				table = masterDataset == null ? null : masterDataset.getTable(Path.parse(pMasterTablePath));
			}
			catch (final RuntimeException ex)
			{
				table = null; // dataset/table not found (or matching not activated yet) - see isMergedAway's own fallback
			}
			this.masterTable = table;
		}

		/**
		 * Volume lot 3 (2026-09-28, task 5): {@link MameRecordState#readStateOnly} instead of the full {@link
		 * MameRecordState#read} - this caller only ever needs the state name, never the group id/auto-created
		 * flag/golden target the full read also fetches (three extra add-on calls per UPDATE row, thrown away
		 * here every time).
		 */
		@Override
		public boolean isMergedAway(final String pMasterKey)
		{
			if (this.masterTable == null || pMasterKey == null)
			{
				return false;
			}
			return this.isMergedAway(pMasterKey, this.masterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pMasterKey)));
		}

		/** Lot V3-M: the state read of an already-fetched record - no lookup. */
		@Override
		public boolean isMergedAway(final String pMasterKey, final Adaptation pMasterRecord)
		{
			if (this.masterTable == null || pMasterRecord == null)
			{
				return false;
			}
			return "MERGED".equals(MameRecordState.get().readStateOnly(pMasterRecord));
		}

		/** The golden record {@code pMasterKey} was merged into, or null when it is not merged (or the add-on cannot say) - {@link SurvivingRecord.GoldenOf}. */
		String goldenOf(final String pMasterKey)
		{
			final MameRecordState.Result state = this.read(pMasterKey);
			return state != null && state.addonPresent && "MERGED".equals(state.state) ? state.goldenKey : null;
		}

		private MameRecordState.Result read(final String pMasterKey)
		{
			// no master table resolved (matching never activated for this project - true for every project this
			// lot alone can produce) or no record for this specific key: correctly "not merged" either way, not
			// merely a defensive fallback - see the class Javadoc
			if (this.masterTable == null || pMasterKey == null)
			{
				return null;
			}
			final Adaptation masterRecord = this.masterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pMasterKey));
			return masterRecord == null ? null : MameRecordState.get().read(masterRecord);
		}
	}

	/**
	 * Resolves the staging value of a reference (the parent's STAGING key, e.g. {@code Adresse.personneRef} =
	 * {@code "SAP|4711"}) to the master record the foreign key must hold: {@code Xref} first - the parent's own
	 * source record, whose key is the auto-increment id EBX gave it - then, when Match and Merge has merged that
	 * record away, the golden record that survived ({@link SurvivingRecord}). Without the second step a child
	 * written after a merge points at the MERGED source record, not at the golden the add-on re-pointed the other
	 * children to. One merge check per target entity (the add-on's state is per table), built on first use.
	 */
	private static final class ParentKeys
	{
		/**
		 * Volume lot 2a (2026-09-28, docs/VOLUME.md, task 3/8): resolved parent keys are cached in {@link
		 * ParentKeyCache} - keyed by {@code (targetEntityName, stagingRef)}, bounded LRU - pulled out to its own
		 * pure, unit-tested class ({@code ParentKeyCacheTest}) so its eviction/key-equality behaviour is
		 * verified without EBX; see that class' own Javadoc for the memory-bound rationale. A fan-out of many
		 * children pointing at the same parent resolves it (the {@code Xref} lookup, and the add-on
		 * golden-chain hop when matching is activated) ONCE per load, not once per child.
		 */
		private final ParentKeyCache cache = new ParentKeyCache();

		private final Adaptation factory;
		private final String projectId;
		private final Repository repository;
		private final String masterDataspaceKey;
		private final String masterDatasetName;
		/** Entity name -&gt; {@code StagingEntity} id, needed only to read {@code MatchPlan.status} once per target entity - see {@link #matchingActivated}. */
		private final Map<String, String> entityIdByName;
		private final Map<String, RecordMetadataMergeCheck> checksByEntity = new HashMap<String, RecordMetadataMergeCheck>();
		/** Lot V3-M2: the read indexes of the entities loaded so far (shared with {@code loadInternal}, which drops one when nobody reads it any more) - a parent entity's {@code Xref} keys and merged keys. */
		private final Map<String, MasterLoadReads> readsByEntity;
		/** Per target entity: whether its {@code MatchPlan.status} is {@code APPLIED} - computed once, see {@link #matchingActivated}. */
		private final Map<String, Boolean> matchingActivatedByEntity = new HashMap<String, Boolean>();
		/** EBX refusal classes lot H4 (2026-10-17): the rows this load rejected, so a child of one says "parent rejected" (see {@link RejectedParents}). */
		private final RejectedParents rejectedParents = new RejectedParents();

		void recordRejected(final String pEntityName, final String pStagingKey, final RejectReason pReason)
		{
			this.rejectedParents.record(pEntityName, pStagingKey, pReason);
		}

		/** Why a row cannot take the parent {@code pStagingRef} of {@code pTargetEntityName}: that parent's rejection in this load, else it has not reached master. */
		RejectReason parentReason(final String pField, final String pStagingField, final String pTargetEntityName, final String pStagingRef)
		{
			return this.rejectedParents.forChild(pField, pStagingField, pTargetEntityName, pStagingRef);
		}

		ParentKeys(final Adaptation pFactory, final String pProjectId, final Repository pRepository, final String pMasterDataspaceKey,
			final String pMasterDatasetName, final Map<String, String> pEntityIdByName, final Map<String, MasterLoadReads> pReadsByEntity)
		{
			this.readsByEntity = pReadsByEntity;
			this.factory = pFactory;
			this.projectId = pProjectId;
			this.repository = pRepository;
			this.masterDataspaceKey = pMasterDataspaceKey;
			this.masterDatasetName = pMasterDatasetName;
			this.entityIdByName = pEntityIdByName;
		}

		/** The master key to write, or null when the parent has not reached master (rejected, or not transferred yet). Counts a redirection to a golden record on {@code pCounters}. */
		String resolve(final String pTargetEntityName, final String pStagingRef, final Counters pCounters)
		{
			final ParentKeyCache.Value cached = this.cache.get(pTargetEntityName, pStagingRef);
			if (cached != null)
			{
				if (cached.redirected)
				{
					pCounters.referencesToGolden++;
				}
				return cached.masterKey;
			}

			final String sourceRecordKey = MasterLoader.xrefMasterKey(this.readsByEntity, this.factory, this.projectId, pTargetEntityName, pStagingRef, pCounters);
			if (MasterLoader.blank(sourceRecordKey))
			{
				this.cache.put(pTargetEntityName, pStagingRef, ParentKeyCache.Value.NOT_FOUND);
				return null;
			}

			final String surviving;
			final boolean redirected;
			// volume lot 2a (task 3): skip the add-on hop entirely (a real master-record lookup plus a
			// MetadataHelper.getState read, per foreign key) when matching was never activated (APPLIED) for
			// the TARGET entity - nothing could have been merged away, so SurvivingRecord.follow would only
			// ever answer "not merged" the slow way. See RecordLineageReader's own class Javadoc for why
			// MatchPlan.status is the fact this module already treats as "is matching activated for real".
			if (this.matchingActivated(pTargetEntityName))
			{
				RecordMetadataMergeCheck check = this.checksByEntity.get(pTargetEntityName);
				if (check == null)
				{
					check = new RecordMetadataMergeCheck(this.repository, this.masterDataspaceKey, this.masterDatasetName, MasterModel.tablePath(pTargetEntityName));
					this.checksByEntity.put(pTargetEntityName, check);
				}
				final RecordMetadataMergeCheck targetCheck = check;
				final MasterLoadReads targetReads = this.readsByEntity.get(pTargetEntityName);
				surviving = SurvivingRecord.follow(sourceRecordKey, new SurvivingRecord.GoldenOf()
				{
					@Override
					public String goldenOf(final String pMasterKey)
					{
						// lot V3-M2: a record absent from the merged keys read at the start of the target's load is not merged - no read of the record nor of the
						// add-on - as long as nothing was written to that table in this load (see MasterLoadReads#knownNotMerged); otherwise the live answer
						if (targetReads != null)
						{
							if (targetReads.knownNotMerged(pMasterKey))
							{
								pCounters.goldenHopsByIndex++;
								return null;
							}
							// lot V3-M3: a merged record's golden comes from the same read (one hop; the chain is followed by SurvivingRecord, hop by hop)
							final String golden = targetReads.goldenOfMerged(pMasterKey);
							if (golden != null)
							{
								pCounters.goldenHopsByIndex++;
								return golden;
							}
						}
						pCounters.goldenHopsLive++;
						return targetCheck.goldenOf(pMasterKey);
					}
				});
				redirected = !sourceRecordKey.equals(surviving);
			}
			else
			{
				surviving = sourceRecordKey;
				redirected = false;
			}
			if (redirected)
			{
				pCounters.referencesToGolden++;
			}
			this.cache.put(pTargetEntityName, pStagingRef, new ParentKeyCache.Value(surviving, redirected));
			return surviving;
		}

		/** {@code pTargetEntityName}'s own {@code MatchPlan.status == APPLIED}, computed once and cached - see {@link #resolve}'s own comment for why this gates the add-on hop. */
		private boolean matchingActivated(final String pTargetEntityName)
		{
			Boolean cached = this.matchingActivatedByEntity.get(pTargetEntityName);
			if (cached == null)
			{
				final String entityId = this.entityIdByName.get(pTargetEntityName);
				cached = Boolean.valueOf(entityId != null && "APPLIED".equals(MasterLoader.readMatchPlanStatus(this.factory, entityId)));
				this.matchingActivatedByEntity.put(pTargetEntityName, cached);
			}
			return cached.booleanValue();
		}
	}

	/** {@code pTargetEntityName}'s own {@code MatchPlan.status}, or null when no plan exists yet - same fact {@code ebx.RecordLineageReader} reads before ever touching the add-on (that class' own Javadoc). */
	static String readMatchPlanStatus(final Adaptation pFactory, final String pEntityId)
	{
		if (pEntityId == null)
		{
			return null;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.MATCH_PLAN).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			final Adaptation row = rows.nextAdaptation();
			return row == null ? null : row.getString(FactoryPaths.MatchPlan.STATUS);
		}
		finally
		{
			rows.close();
		}
	}

	// ======================================================================================================= misc

	private static final class RowPlan
	{
		final String stagingKey;
		final String existingMasterKey;
		/** Lot V3-M: the master record {@link #planRow} read for an update row (null for an insert, or when the record vanished) - what {@link #upsertOne} modifies. */
		final Adaptation existingRecord;
		final Map<String, Object> fields;
		final String loadId;
		final Date receivedAt;
		/** Written by {@link #upsertOne}: the master key the row now has (new on insert, unchanged on update) - what {@link #writeXref} writes back onto {@code Xref}. */
		String newMasterKey;

		RowPlan(final String pStagingKey, final String pExistingMasterKey, final Adaptation pExistingRecord, final Map<String, Object> pFields, final String pLoadId,
			final Date pReceivedAt)
		{
			this.stagingKey = pStagingKey;
			this.existingMasterKey = pExistingMasterKey;
			this.existingRecord = pExistingRecord;
			this.fields = pFields;
			this.loadId = pLoadId;
			this.receivedAt = pReceivedAt;
		}
	}

	private static final class RowResult
	{
		final RowPlan plan;
		final boolean inserted;

		RowResult(final RowPlan pPlan, final boolean pInserted)
		{
			this.plan = pPlan;
			this.inserted = pInserted;
		}
	}

	private static final class RejectPlan
	{
		final String stagingKey;
		final String sourceSystem;
		final String sourceId;
		final String loadId;
		/** EBX refusal classes lot H4 (2026-10-17): coded reasons, as the staging stage's - the first one gives the category, the grouping signature and the raw detail. */
		final List<RejectReason> reasons;

		RejectPlan(final String pStagingKey, final String pSourceSystem, final String pSourceId, final String pLoadId, final List<RejectReason> pReasons)
		{
			this.stagingKey = pStagingKey;
			this.sourceSystem = pSourceSystem;
			this.sourceId = pSourceId;
			this.loadId = pLoadId;
			this.reasons = pReasons;
		}

		RejectReason first()
		{
			return this.reasons.get(0);
		}

		/** The English sentences, joined - what the load result and the logs say. */
		String english()
		{
			final StringBuilder out = new StringBuilder();
			for (final RejectReason reason : this.reasons)
			{
				if (out.length() > 0)
				{
					out.append("; ");
				}
				out.append(reason.english());
			}
			return out.toString();
		}
	}

	private static String join(final List<String> pReasons)
	{
		final StringBuilder out = new StringBuilder();
		for (final String reason : pReasons)
		{
			if (out.length() > 0)
			{
				out.append("; ");
			}
			out.append(reason);
		}
		return out.toString();
	}

	private static String str(final Object pValue)
	{
		return pValue == null ? null : String.valueOf(pValue);
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
