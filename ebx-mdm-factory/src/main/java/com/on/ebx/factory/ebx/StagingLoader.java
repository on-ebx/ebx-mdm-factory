package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.MappingEngine;
import com.on.ebx.connect.core.TransformationContext;
import com.on.ebx.connect.core.Transformations;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.rejects.RefusalHints;
import com.on.ebx.factory.rejects.RejectCategory;
import com.on.ebx.factory.rejects.RejectClassifier;
import com.on.ebx.factory.rejects.RejectReason;
import com.on.ebx.factory.rejects.RejectReasons;
import com.on.ebx.factory.rejects.RejectText;
import com.on.ebx.factory.quality.ChildKeysDerivation;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.CrossSystemFold;
import com.on.ebx.factory.quality.QualityDetectors;
import com.on.ebx.factory.quality.QualityRuleCheck;
import com.on.ebx.factory.quality.ValidValueDerivation;
import com.on.ebx.factory.master.MasterLoadOutcome;
import com.on.ebx.factory.master.ScanBudget;
import com.on.ebx.factory.staging.ComputedFieldFill;
import com.on.ebx.factory.staging.DerivedFieldRule;
import com.on.ebx.factory.staging.ScriptDerivationGate;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.on.ebx.factory.transfer.BatchCircuitBreaker;
import com.on.ebx.factory.transfer.BatchRetry;
import com.on.ebx.factory.transfer.EmptyChildRule;
import com.on.ebx.factory.transfer.FeedRules;
import com.on.ebx.factory.transfer.FieldAvailabilityRule;
import com.on.ebx.factory.transfer.FieldFillCheck;
import com.on.ebx.factory.transfer.IncrementalTransfer;
import com.on.ebx.factory.transfer.LandingTableFanout;
import com.on.ebx.factory.transfer.NullFillRule;
import com.on.ebx.factory.transfer.PipelineCompat;
import com.on.ebx.factory.transfer.StagingRowDiff;
import com.on.ebx.factory.transfer.TransferPlan;
import com.on.ebx.factory.landing.RunTexts;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The landing -&gt; staging transfer engine of one project (docs/TARGET-MAPPING.md, target-mapping lot 1):
 * for every ROOT {@link TransferPlan.Feed} of every entity in {@link TransferPlan}'s load order, streams its
 * landing table row by row, computes every mapped value of the WHOLE feed with ONE call to Connectors'
 * {@code MappingEngine.compute} (never one call per field any more - see "One MappingEngine call per feed"
 * below), recurses depth-first into the feed's child feeds (a different target entity, fed from the SAME
 * landing row or an element found in it), and UPSERTs every row it produces into its own entity's staging
 * table by primary key, in batches of {@code FactorySettings.transferBatchSize} ROOT rows per {@link
 * Procedure} ({@link BatchRetry}: one failing row inside a failing batch is retried alone, so only it is
 * rejected). Thin on purpose: every decision that does not need EBX lives in {@link
 * TransferPlan}/{@link PipelineCompat}/{@link BatchRetry}/{@code quality.CleaningSteps}/{@code
 * quality.QualityRuleCheck}/{@code staging.DerivedFieldRule}, all pure and unit-tested without a running
 * instance; this class only drives the reads/writes around them.
 * <p>
 * <b>One {@code MappingEngine} call per feed, not per field</b> (docs/TARGET-MAPPING.md sec 3.3, replacing
 * the retired {@code transfer.Transformations#apply} call this class used to make once per field): {@link
 * #prepareFeed} builds ONE Connectors {@code MappingDefinition} per {@link TransferPlan.Feed}, once for the
 * whole run (never per row) - one {@code MappingRule} per {@link TransferPlan.FieldPlan} that is actually
 * WRITABLE on the live schema ({@code position} = its index, {@code sourcePath} = the field's own {@link
 * TransferPlan.FieldPlan#sourcePath}, {@code targetPath} = {@code "./" + stagingField}, {@code
 * transformation} = the field's already-resolved {@link TransferPlan.FieldPlan#pipeline}, {@code policy} =
 * {@code ALWAYS}). {@link #computeRow} then calls {@code MappingEngine.compute(definition, document, ctx)}
 * exactly once per row per feed, and reads every field's xs-string result off the returned {@code Values}
 * ({@code values.get("./" + stagingField)}) - {@code Values.getErrors()} becomes this row's reject reasons,
 * named by target field exactly like the retired per-field rejects were. One {@link
 * com.on.ebx.connect.core.TransformationContext} is built ONCE for the WHOLE RUN ({@code new
 * TransformationContext(stagingDataset, null, session)}, in {@link #load}) and reused for every feed and
 * every row - it lets a pipeline's {@code fk}/{@code lookup} step read reference tables of the STAGING
 * dataset (docs/TARGET-MAPPING.md sec 8: a binding into a DIFFERENT dataset, e.g. Geographies, is out of
 * scope for this lot - {@code lookup} only ever resolves against the staging dataset itself).
 * <p>
 * <b>One landing row -&gt; one JSON document</b> (docs/TARGET-MAPPING.md sec 3.2): {@link #buildRowDocument}
 * reads every field of the landing table's own schema (never a second, hand-maintained column list) as its
 * XML-schema string ({@code SchemaNode.formatToXsString}), keyed by field name - the technical fields
 * {@code _sourceId}/{@code _sourceSystem}/{@code _loadId}/{@code _receivedAt}/{@code _rowHash}/{@code
 * _status} included, since they are ordinary fields of the same landing table. This is the document every
 * rule, and every {@code concat(...)} path, resolves against - the same shape the EBX connector's own
 * {@code search} operation answers, so a mapping written for the Factory is a valid Connectors mapping too
 * (docs/TARGET-MAPPING.md sec 1).
 * <p>
 * <b>Child feeds, recursively, inline - never a separate top-level pass and never a lookup</b>
 * (docs/TARGET-MAPPING.md sec 3.3 point 6): after a feed's OWN row is computed (and, if it passes every
 * check, planned to be written), {@link #computeRow} recurses into {@link TransferPlan.Feed#children}: for
 * each child feed, the element(s) it reads are the row's own document (an empty {@link
 * TransferPlan.Feed#collectionPath} - the only shape any real configuration produces today, landing rows
 * being flat) or every element of the JSON array/object found at that path (not exercised by any real
 * configuration yet, but handled the same general way {@code MappingEngine.applyChild} reads a collection -
 * kept for parity with Connectors and a future JSON-typed landing column). Each element becomes its own
 * child DOCUMENT - a COPY of the element (or of the row's own document, for the flat case), plus the
 * immediate parent's document reachable under the key {@code _parent} (so a child pipeline can read
 * {@code concat(_parent.ID)} even when its own element has no {@code ID} of its own). The child's {@code
 * sourceId} is its own {@link TransferPlan.Feed#sourceIdPipeline} applied to that document when set, else a
 * DEFAULT this class computes (see {@link #computeChildren}'s own Javadoc for the exact rule and why the
 * design's "position among its siblings" phrase needed a concrete reading). {@code parentLinkField} is
 * written from the PARENT ROW'S OWN, just-computed staging key
 * ({@code parentTable.computePrimaryKey(new Object[]{parentSourceSystem, parentSourceId}).format()}) -
 * needing no database lookup at all, and therefore no "deferred until the parent table is loaded" pass the
 * way an ordinary foreign key needs one (see {@link TransferPlan}'s own Javadoc). A child whose own {@code
 * sourceId} computes empty is a reject of the CHILD only; the parent row is still written. A child is never
 * even attempted when the PARENT row itself was rejected (there is no parent key to link it to).
 * <p>
 * <b>Rejects never reach staging.</b> A row a blocking EBX constraint would refuse (max length, mandatory -
 * the knowledge base's own "blocking vs non-blocking" rule) is caught BEFORE the write by {@link
 * PipelineCompat#checkConstraints}; a row EBX still refuses at save time (caught by the batch-retry's
 * per-row attempt) is treated the same way. Either way the row is never written to staging - the landing
 * model has no {@code _rejectReason} field: the landing row's own {@code _status} is set to {@code REJECTED}
 * (root feeds only - see below) and every reason is written to a {@code TransferReject} row instead. A row
 * that goes through cleanly gets the landing row's own {@code _status} set to {@code STAGED}.
 * <p>
 * <b>Landing status is written for the ROOT feed's own row only, once</b> - a child feed (today, always
 * reading the SAME landing row as its root, {@code collectionPath} empty) never writes its own landing
 * status: there is only one landing row, and the root's own outcome already marks it. A child's rejection
 * is recorded as its own {@code TransferReject} (naming the CHILD entity), independent of the landing row's
 * {@code STAGED} status - see this class's own note on why a child failing never un-stages its parent.
 * <p>
 * <b>...and that write-back is deferred to the very end of the WHOLE run</b> (bug fix ported from {@code
 * main} - commit {@code 9358a45}, "landing status write-back must not happen before every feed of every
 * entity has run", found live by the coordinator on workflow instance 564, project {@code Companies},
 * 2026-09-23, BEFORE this lot's feed-tree rewrite existed, and re-applied here since the rewrite has the
 * exact same hazard: a landing table can feed more than one ROOT feed - a canonical entity and one of its
 * members both read {@code SUPPLIER}/{@code ACCOUNT}). Writing a landing row's {@code _status} back to
 * {@code STAGED} as soon as the FIRST root feed had processed it meant every LATER root feed reading the
 * SAME landing table under scope {@code new} found nothing left at {@code _status='NEW'} - measured live:
 * {@code Company} read 0 where scope {@code all} on the same data reads 107 (55 Supplier rows + 52 Account
 * rows). {@link #loadRootFeed} now only merges every root feed's decision into a whole-run accumulator
 * ({@link PendingLandingStatus} - see its own Javadoc), and {@link #load} writes it back ONCE, in batches of
 * {@code FactorySettings.transferBatchSize} rows per landing table, only after pass 1 AND pass 2 both
 * complete without throwing. Two decisions for the SAME landing row from different root feeds within one
 * run are merged with {@code transfer.IncrementalTransfer#mergeLandingStatus}: {@code REJECTED} wins over
 * {@code STAGED}. Crash safety: a crash anywhere in pass 1/2 discards the accumulator unwritten, leaving
 * every touched row at {@code _status=NEW} - safe (retried next run, upsert idempotent by primary key), and
 * actually safer than the per-root-feed write-back it replaces.
 * <p>
 * <b>Incremental by default, since 2026-09-23 (the product owner's incremental-transfer brief, {@code
 * docs/INCREMENTAL-TRANSFER.md}) - unchanged by this lot, extended to every entity in the feed tree.</b>
 * {@link #load} takes an explicit {@code pAllRows} scope: {@code false} (scope {@code new}, the default
 * everywhere but {@code ebx.ui.ProjectWizardUserService#loadStagingData}) reads only the landing rows still
 * at "version received, not yet transferred" ({@code transfer.IncrementalTransfer#landingPredicate}, an
 * XPath filter on {@code _status}); {@code true} (scope {@code all}) reads every landing row, whatever its
 * {@code _status}. Within scope {@code new}, a second, finer check ({@code
 * transfer.IncrementalTransfer#shouldWrite}) skips the actual staging WRITE for a row (root OR child alike,
 * checked independently per entity) whose incoming hash still equals the {@code _sourceHash} its OWN staging
 * row already carries - counted separately ({@code unchanged}) from {@code written}, per entity. A child
 * row's incoming hash is its ROOT ancestor's own landing {@code _rowHash} (same source version - see
 * {@code transfer.IncrementalTransfer}'s own Javadoc): an unchanged root landing row therefore also skips
 * every one of its descendants' writes, since their content is a pure function of the SAME unchanged input -
 * still visited (read, checked), never written.
 * <p>
 * <b>Scope {@code all} writes only the rows whose result differs - lot V3-S (2026-10-04, docs/VOLUME.md "Lot V3-S").</b> Measured on Vol1m: a scope {@code all}
 * load with nothing changed "wrote" 1,465,857 rows in 349 s - every row was submitted with {@code doModifyContent} (running the table triggers, rewriting
 * its {@code Xref}, stamping {@code _loadId}/{@code _receivedAt}). Scope {@code all} still re-applies the whole pipeline (mappings, cleaning, derivations) to
 * every landing row, but {@link #processRow} now reads the existing staging row ONCE, outside any Procedure, compares the planned values with it
 * ({@code transfer.StagingRowDiff}: planned xs-string parsed by the node, compared typed) and buffers the row as {@code unchanged} when nothing differs - no
 * write, no {@code Xref} write, {@code _loadId}/{@code _receivedAt} untouched (they then mean "the load that last CHANGED this row", as in scope {@code new}).
 * A row that does differ is written as before ({@link #upsertOne} reuses the record already read). Rejects are decided before the comparison. A landing
 * row already at the status the run gives it is not written back either. Scope {@code new} is untouched.
 * <p>
 * <b>A field not currently supplied by a feed is written as an explicit {@code null} on every row, not left
 * untouched</b> (review-quality lot, task A, 2026-09-21 - {@link NullFillRule}), computed ONCE per feed (root
 * or child alike - see {@link #prepareFeed}), never per row.
 * <p>
 * <b>A field that does not exist yet on the PUBLISHED staging table is never written, whatever plans to
 * write it</b> (coordinator review, 2026-09-21 - {@link FieldAvailabilityRule}): checked ONCE per feed
 * (including a child feed's own {@code parentLinkField}, which is exactly as liable to lag behind {@code
 * staging/generate} as any other field - see {@link #prepareFeed}). Reported once per entity: {@code
 * StagingEntity.lastLoadMessage} and the load's own JSON answer ({@code entities[].missingFields}).
 * <p>
 * <b>Cleaning pipeline (normalisation/quality lot, task D), unchanged in spirit, now against the document</b>:
 * an ACCEPTED-or-APPLIED {@code MappingStep} pipeline runs on the raw landing text BEFORE this field's own
 * (now Connectors) pipeline - {@link #processRow} applies every field's cleaning steps into a COPY of the
 * feed's document (never the shared original) before handing that copy to {@code MappingEngine.compute}.
 * <b>Per field, lot M6 (2026-10-04)</b>: the cleaned value is stored under the FIELD'S OWN alias column
 * ({@link FeedRules#cleanedColumn}) and only that field's rule reads it; the shared source column keeps the raw
 * landing text, so two mappings on one column (a regex for the first name and one for the last name) never see
 * each other's cleaning (the cleaning of one used to turn the other's value empty on every row - see {@link
 * FeedRules}). A {@code concat(...)} naming the source column reads the RAW text.
 * The reference lookups a lookup-based step needs ({@code quality.CleaningSteps.Lookups}) are loaded ONCE for
 * the whole run, exactly as before this lot.
 * <p>
 * <b>Quality rules run on the xs-string result</b> (docs/TARGET-MAPPING.md sec 3.3 point 4, settling sec 8's
 * open point): {@code QualityRuleCheck.passes} already only ever acts on a {@code String} value (a
 * non-{@code String} is a defensive no-op - see its own Javadoc) and REFERENCE/CHECK_DIGIT/PATTERN rules are
 * only ever proposed for {@code STRING} attributes in the first place, so handing it the field's own xs
 * string needs no change to that class at all.
 * <p>
 * <b>Foreign keys, immediate and deferred</b> (see {@link TransferPlan}'s own Javadoc) - unchanged from
 * before this lot, just reading the xs string directly as the key text (Connectors' {@code MappingEngine}
 * already coerces a decimal to its trimmed plain-string form the same way the retired {@code
 * Transformations#keyText} did): an immediate reference is resolved and checked for an orphan in the same
 * pass as the row itself; a deferred one (a self reference, or an edge inside an unresolved cycle) is left
 * {@code null} in this first pass and resolved in a second, {@link #resolveDeferredReferences}. A CHILD
 * feed's structural {@code parentLinkField} is a DIFFERENT mechanism entirely and never goes through this
 * deferred machinery at all - see {@link TransferPlan.Feed#parentLinkField}'s own Javadoc for why.
 * <p>
 * <b>A child feed's own mapping of that SAME field never goes through the ordinary lookup either</b> (bug
 * fix, found live 2026-09-23 on project Personnes' first real run - see {@link
 * TransferPlan.FieldPlan#structuralParentLink}'s own Javadoc): {@link #processRow} checks the flag before
 * ever entering the foreign-key block, so a language-model-proposed rule mapping the child entity's own
 * link-to-parent attribute (harmless-looking, redundant with the structural write) is never resolved by
 * lookup against the parent's staging table - on a first load the parent row of the SAME landing row is
 * still buffered in this batch, not yet written, so that lookup would always miss and reject the child row
 * as an "unknown parent", passing on a retry only once the parent happened to already exist from an earlier
 * run. The structural write from {@code pParentKey} (the parent's own, just-computed key) is the only
 * source of truth for that field on a child row - never a value read off {@code MappingEngine.compute}'s
 * own result for it.
 * <p>
 * <b>The composite key is never built or split as a string</b> - unchanged from before this lot: every
 * staging primary key and {@code &lt;attribute&gt;Ref}/{@code parentLinkField} value goes through {@code
 * AdaptationTable.computePrimaryKey(Object[])} and {@code PrimaryKey.format()}, never manual concatenation
 * (CLAUDE.md sec 9, and this class's own history - see git blame of the pre-lot-1 version for the incident
 * this fixed).
 * <p>
 * Runs with the CALLER's session (unlike {@code LandingGenerator}/{@code StagingGenerator}, which act as the
 * factory's MCP agent account): no MCP call is made here at all, every read and write is a direct EBX API
 * call - see {@link #load}'s own Javadoc for the access rights this needs.
 */
public final class StagingLoader
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String STEP = "STAGING_LOAD";
	/** Both {@code PipelineStep} steps of {@code StagingGenerator} run at project scope; this one does too - one transfer run covers every entity of the project. */
	private static final String SCOPE = "";
	private static final int DEFAULT_BATCH_SIZE = 500;
	private static final String REJECT_STAGE = "STAGING";
	private static final int MAX_REPORTED_REJECTS = 10;
	/** {@code QualityRule.lastLoadExamples}: at most this many failing-row keys kept per rule (task C). */
	private static final int MAX_QUALITY_RULE_EXAMPLES = 10;

	private static final Path STAGING_SOURCE_SYSTEM = Path.parse("./sourceSystem");
	private static final Path STAGING_SOURCE_ID = Path.parse("./sourceId");
	private static final Path STAGING_LOAD_ID = Path.parse("./_loadId");
	private static final Path STAGING_RECEIVED_AT = Path.parse("./_receivedAt");
	/** Package-private since lot V3-S2: {@code StagingLoadReads} reads the same columns when it builds the staging index. */
	static final Path STAGING_STATUS = Path.parse("./_status");
	/** 2026-09-23, incremental-transfer lot: the landing row's own {@code _rowHash} the staging row was last (re)built from - see {@code transfer.IncrementalTransfer}. */
	static final Path STAGING_SOURCE_HASH = Path.parse("./_sourceHash");

	private static final Path LANDING_SOURCE_SYSTEM = Path.parse("./" + FactoryPaths.Landing.SOURCE_SYSTEM);
	private static final Path LANDING_SOURCE_ID = Path.parse("./" + FactoryPaths.Landing.SOURCE_ID);
	private static final Path LANDING_LOAD_ID = Path.parse("./" + FactoryPaths.Landing.LOAD_ID);
	private static final Path LANDING_RECEIVED_AT = Path.parse("./" + FactoryPaths.Landing.RECEIVED_AT);
	private static final Path LANDING_STATUS = Path.parse("./" + FactoryPaths.Landing.STATUS);
	private static final Path LANDING_ROW_HASH = Path.parse("./" + FactoryPaths.Landing.ROW_HASH);

	private StagingLoader()
	{
	}

	// ============================================================================================== entry point

	/**
	 * Runs the transfer of every entity of the project, in {@link TransferPlan}'s load order, then the second
	 * pass for deferred references. Records one {@code PipelineStep} {@code STAGING_LOAD} (project scope,
	 * never skipped when a previous run is {@code DONE}: a transfer is meant to be repeated - the same
	 * resumability posture as {@code LandingFlows#load}).
	 * <p>
	 * <b>Access rights the caller needs</b>: write access to the project's staging dataspace/dataset (created
	 * by {@code staging/generate} under {@code Profile.ADMINISTRATOR} - an administrator session, or a role
	 * explicitly granted write access to that dataspace, is required); write access to every landing
	 * dataspace/dataset of the project's sources (the {@code _status} write-back); and write access to the
	 * factory's own dataset (the {@code TransferReject}, {@code Xref}, {@code StagingEntity} and {@code
	 * QualityRule} counter writes). The REST route restricts this to administrators, matching
	 * {@code /staging/generate}.
	 *
	 * @param pAllRows scope: {@code false} (the ordinary default - {@code new}) reads only landing rows still
	 *            at {@code _status='NEW'}/null (see {@code transfer.IncrementalTransfer}); {@code true} ({@code
	 *            all}) reads every landing row, for a full re-transform after a mapping/cleaning-step/derived-
	 *            field change.
	 */
	/**
	 * Distribution lot 1 (SPEC-distribution.md "Real time"): a scope-{@code all} load is one of the three
	 * "FULL reload" steps that pause the project's armed REALTIME distribution targets while it runs, with a
	 * CHANGED catch-up send once it is over ({@code ebx.DistributionPause}) - a scope-{@code new} load is the
	 * ordinary incremental transfer and never pauses anything. The actual work is unchanged, in
	 * {@link #loadInternal}.
	 */
	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pAllRows) throws OperationException
	{
		return StagingLoader.load(pSession, pFactory, pProjectCode, pAllRows, com.on.ebx.factory.staging.StagingLoadListener.NONE);
	}

	/** Same, telling {@code pListener} each business object x application cell and its real row counts after each batch (staging-live lot, 2026-09-27). */
	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pAllRows,
		final com.on.ebx.factory.staging.StagingLoadListener pListener) throws OperationException
	{
		return StagingLoader.load(pSession, pFactory, pProjectCode, pAllRows, pListener, null);
	}

	/**
	 * Volume lot 3 (2026-09-28, docs/VOLUME.md task 8, "cancellation + tracking info"): {@code pHandle} - null
	 * for every synchronous caller (the REST route, a workflow task bean) - lets a job-driven run ({@link
	 * StagingLoadRun}, the only caller that ever has one) be stopped cleanly between batches: what is already
	 * written stays written, the step ends {@code STOPPED} (never {@code FAILED}) with the counts reached so
	 * far, and {@link com.on.ebx.factory.ebx.FactoryJobs.Handle#cancelled()} being checked once per batch also
	 * keeps the job's own inactivity deadline alive (lot 1b, {@code Handle#touch()}) - a multi-hour load is
	 * never killed merely for taking a long time, only for genuinely stopping to respond or being asked to stop.
	 */
	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pAllRows,
		final com.on.ebx.factory.staging.StagingLoadListener pListener, final FactoryJobs.Handle pHandle) throws OperationException
	{
		// audit F-15: every caller (the queued job, the workflow step, ?sync=true) works on the staging with the project's STAGING resource held - see EngineClaim
		final EngineClaim claim = EngineClaim.enter(pFactory, pSession, pProjectCode, "STAGING_LOAD_DIRECT", FactoryJobs.STAGING);
		try
		{
			return StagingLoader.loadPaused(pSession, pFactory, pProjectCode, pAllRows, pListener, pHandle);
		}
		finally
		{
			claim.release();
		}
	}

	private static ObjectNode loadPaused(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pAllRows,
		final com.on.ebx.factory.staging.StagingLoadListener pListener, final FactoryJobs.Handle pHandle) throws OperationException
	{
		if (!pAllRows)
		{
			return StagingLoader.loadInternal(pSession, pFactory, pProjectCode, false, pListener, pHandle);
		}
		final Adaptation pauseProject = StagingLoader.findProject(pFactory, pProjectCode);
		final String pauseProjectId = pauseProject == null ? null : pauseProject.getOccurrencePrimaryKey().format();
		// every pause is recorded durably under this owner (LoadPauseLedger): a stop before the resume below leaves the proof the module needs to lift it at its next start
		final LoadPauseLedger.Owner pauseOwner = LoadPauseLedger.open(StagingLoader.STEP, pauseProjectId);
		final java.util.List<String> paused = pauseProjectId == null ? java.util.Collections.<String>emptyList()
			: DistributionPause.pause(pSession, pFactory, pauseProjectId, pauseOwner);
		try
		{
			return StagingLoader.loadInternal(pSession, pFactory, pProjectCode, true, pListener, pHandle);
		}
		finally
		{
			try
			{
				DistributionPause.resumeAndReport(pSession, pFactory, pProjectCode, pauseProjectId, paused, pauseOwner, StagingLoader.STEP);
			}
			finally
			{
				pauseOwner.close();
			}
		}
	}

	/** The {@code status} of a finished load's result: STOPPED when a stop was requested, FAILED when every row read was rejected (the master load's rule), else DONE. */
	static String resultStatus(final boolean pStopped, final long pRead, final long pRejected)
	{
		return pStopped ? "STOPPED" : MasterLoadOutcome.isTotalFailure(pRead, pRejected) ? "FAILED" : "DONE";
	}

	/** {@link #resultStatus(boolean, long, long)} from the run's own verdict ({@link #isTotalFailure}). */
	static String resultStatus(final boolean pStopped, final boolean pTotalFailure)
	{
		return pStopped ? "STOPPED" : pTotalFailure ? "FAILED" : "DONE";
	}

	/**
	 * Staging load rejects (2026-10-17): a load whose rows were all set aside and that wrote nothing. It used to be {@code read == rejected}, true only while {@code read} counted
	 * the ROOT rows alone; a child entity now counts the rows it builds from its parent's rows as read too (an empty child is read and neither written nor rejected), so the
	 * test is the one that was always meant: something was read and rejected, and nothing was written, found unchanged or skipped.
	 */
	static boolean isTotalFailure(final long pRead, final long pRejected, final long pWritten, final long pUnchanged, final long pSkipped)
	{
		return pRead > 0 && pRejected > 0 && pWritten == 0 && pUnchanged == 0 && pSkipped == 0;
	}

	static ObjectNode loadInternal(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pAllRows,
		final com.on.ebx.factory.staging.StagingLoadListener pListener, final FactoryJobs.Handle pHandle) throws OperationException
	{
		final Adaptation project = StagingLoader.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final String stagingDataspaceKey = project.getString(FactoryPaths.Project.STAGING_DATASPACE);
		final String stagingDatasetName = project.getString(FactoryPaths.Project.STAGING_DATASET);
		if (stagingDataspaceKey == null || stagingDataspaceKey.trim().isEmpty() || stagingDatasetName == null || stagingDatasetName.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.noStagingZone", "Project " + pProjectCode + " has no staging zone yet: run POST /projects/" + pProjectCode + "/staging/generate first", pProjectCode);
		}

		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome stagingHome = repository.lookupHome(HomeKey.forBranchName(stagingDataspaceKey));
		final Adaptation stagingDataset = stagingHome == null ? null : stagingHome.findAdaptationOrNull(AdaptationName.forName(stagingDatasetName));
		if (stagingDataset == null)
		{
			throw EngineRefusal.coded("missing.stagingDataset", "Staging dataset " + stagingDataspaceKey + "/" + stagingDatasetName + " of project " + pProjectCode
				+ " could not be found: run POST /projects/" + pProjectCode + "/staging/generate first", stagingDataspaceKey + "/" + stagingDatasetName);
		}

		// fix/staging-proposal-quality (2026-10-17, item 1): an accepted script derivation is applied by the load itself, before a row is written
		// (see DerivationAutoApply: accepted-but-never-applied held phoneE164 / personNameKey empty on every row of TiersB2CRetestLanding)
		final List<String> autoApplyNotes = new ArrayList<String>();
		final ArrayNode autoApplied = DerivationAutoApply.applyAccepted(pSession, pFactory, pProjectCode, projectId, autoApplyNotes);
		// staging integration (2026-10-17): the accepted STAGING-zone script constraints too - accepted before the model existed, they were never applied
		ConstraintAutoApply.applyAccepted(pSession, pFactory, pProjectCode, projectId, autoApplyNotes);
		// fix/derivation-apply-backfill (2026-10-17, lot G1, item 2): a row the loader will find unchanged is never written, so no trigger would compute an applied derivation
		// for it - recompute the rows a trigger never reached (applied after they were written, or the backfill could not run), before the first row is read
		final List<String> repairNotes = new ArrayList<String>();
		final ArrayNode repaired = DerivationAutoApply.repairStale(pSession, pFactory, pProjectCode, projectId, repairNotes);

		final Map<String, String> entityIdByName = new LinkedHashMap<String, String>();
		final List<StagingModel.EntityInfo> entityInfos = StagingLoader.readEntities(pFactory, projectId, entityIdByName);
		final StagingModel.Result modelResult = StagingModel.build(entityInfos);
		final Set<String> builtEntities = new HashSet<String>(modelResult.builtEntities);

		final Map<String, String> foreignKeyFieldByEntityAttribute = new HashMap<String, String>();
		for (final StagingModel.ForeignKeyInfo fk : modelResult.foreignKeys)
		{
			foreignKeyFieldByEntityAttribute.put(fk.entityName + "#" + fk.attributeName, fk.fieldName);
		}

		final Map<String, StagingModel.EntityInfo> entityInfoByName = new HashMap<String, StagingModel.EntityInfo>();
		for (final StagingModel.EntityInfo entity : entityInfos)
		{
			entityInfoByName.put(entity.name, entity);
		}
		final Map<String, Map<String, String>> foreignKeyFieldByAttributeByEntity = new HashMap<String, Map<String, String>>();
		for (final StagingModel.ForeignKeyInfo fk : modelResult.foreignKeys)
		{
			Map<String, String> byAttribute = foreignKeyFieldByAttributeByEntity.get(fk.entityName);
			if (byAttribute == null)
			{
				byAttribute = new HashMap<String, String>();
				foreignKeyFieldByAttributeByEntity.put(fk.entityName, byAttribute);
			}
			byAttribute.put(fk.attributeName, fk.fieldName);
		}
		// 2026-09-22 (quality.CrossSystemFold/DerivedFieldRule): entity name -> its own derived fields; empty
		// for an entity with none, never null - see readDerivedFields' own Javadoc.
		final Map<String, List<DerivedFieldRule.Field>> derivedFieldsByEntity = StagingLoader.readDerivedFields(pFactory, entityIdByName);

		final List<TransferPlan.MappingInfo> mappings = StagingLoader.readMappings(pFactory, projectId, project, entityInfos, builtEntities, foreignKeyFieldByEntityAttribute);
		final TransferPlan.Result plan = TransferPlan.plan(mappings);
		if (plan.loadOrder.isEmpty())
		{
			final int waiting = StagingLoader.countWaitingMappings(pFactory, projectId, entityInfos, builtEntities);
			throw EngineRefusal.coded("missing.noMappedAttribute", "Project " + pProjectCode + " has no staging entity with an accepted, mapped attribute: nothing to transfer. "
				+ (waiting > 0
					? waiting + " mapping(s) are still waiting for review: accept them in the proposal review, or run staging/suggest to align them with their accepted attributes."
					: "Accept entities, attributes and mappings first (POST /projects/" + pProjectCode + "/staging/suggest, then review)."), pProjectCode);
		}

		final Map<String, AdaptationTable> stagingTables = new LinkedHashMap<String, AdaptationTable>();
		for (final TransferPlan.Entity entity : plan.loadOrder)
		{
			stagingTables.put(entity.name, stagingDataset.getTable(Path.parse(StagingModel.tablePath(entity.name))));
		}
		// lot M3 (2026-10-03): the CHILD_KEYS attributes of the project, validated BEFORE any row is read - a definition that cannot be right
		// fails the load now, never a silently missing matching key afterwards (ChildKeysPass#read)
		final List<ChildKeysPass.Definition> childKeyDefinitions = ChildKeysPass.read(pFactory, entityIdByName, builtEntities, foreignKeyFieldByAttributeByEntity, entityInfos);

		final int batchSize = StagingLoader.batchSize(pFactory);
		final Date now = new Date();
		// task D: the reference lookups a MappingStep's COUNTRY_TO_ISO2/CURRENCY_TO_ISO/TRANSCODE_REFERENCE kind
		// needs, loaded once per run - the exact same reader QualitySuggester itself uses to propose these steps
		final List<String> lookupNotes = new ArrayList<String>();
		final CleaningSteps.Lookups lookups = QualitySuggester.buildLookups(pFactory, lookupNotes);
		// target-mapping lot 1: one TransformationContext for the whole run - see the class Javadoc's own note
		final TransformationContext ctx = new TransformationContext(stagingDataset, null, pSession);

		// task C (QualityRule execution): one counters accumulator for the whole run, keyed by QualityRule id.
		final Map<String, RuleRunCounters> ruleCounters = new LinkedHashMap<String, RuleRunCounters>();

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, StagingLoader.STEP, StagingLoader.SCOPE);
		final ObjectNode detail = StagingLoader.MAPPER.createObjectNode();
		final ArrayNode entityDetails = detail.putArray("entities");
		// lot M5 (2026-10-04): a script derivation that is not applied is held empty - say so, in the pane, the detail and the message
		final List<String> scriptNotAppliedNotes = new ArrayList<String>();
		if (autoApplied.size() > 0)
		{
			detail.set("derivationsAutoApplied", autoApplied);
		}
		// lot M4 (2026-10-18, point C): the notes of the auto-apply (derivations AND constraints) reach the job whatever applied - they were forwarded only when a derivation had been applied,
		// so a load that applied no derivation hid why an accepted constraint stayed ACCEPTED
		if (!autoApplyNotes.isEmpty())
		{
			final ArrayNode autoApplyNode = detail.putArray("autoApplyNotes");
			for (final String note : autoApplyNotes)
			{
				autoApplyNode.add(note);
				pListener.note(note);
			}
		}
		if (repaired.size() > 0)
		{
			detail.set("derivationsRepaired", repaired);
		}
		for (final String note : repairNotes)
		{
			pListener.note(note);
		}
		// read again: the derivations just applied no longer count as not applied
		final List<ScriptDerivationGate.Entry> scriptEntries = ScriptDerivations.read(pFactory, projectId);
		for (final ScriptDerivationGate.Entry entry : ScriptDerivationGate.notApplied(scriptEntries))
		{
			if (stagingTables.containsKey(entry.entityName))
			{
				scriptNotAppliedNotes.add(entry.entityName + "." + ScriptDerivationGate.loadNote(entry));
			}
		}
		if (!scriptNotAppliedNotes.isEmpty())
		{
			final ArrayNode scriptNode = detail.putArray("scriptDerivationsNotApplied");
			for (final String note : scriptNotAppliedNotes)
			{
				scriptNode.add(note);
				pListener.note(note);
			}
		}
		if (!lookupNotes.isEmpty())
		{
			final ArrayNode lookupNotesNode = detail.putArray("referenceLookupNotes");
			for (final String note : lookupNotes)
			{
				lookupNotesNode.add(note);
			}
		}
		final List<String> firstRejectReasons = new ArrayList<String>();
		final Map<String, Counters> countersByEntity = new LinkedHashMap<String, Counters>();
		for (final TransferPlan.Entity entity : plan.loadOrder)
		{
			countersByEntity.put(entity.name, new Counters());
		}
		// 2026-09-23 bug fix (found live on `main`, workflow instance 564, project Companies - see the class
		// Javadoc "Landing status write-back is deferred"): every root feed's landing-status decision, across
		// EVERY entity of the WHOLE run, accumulated here and written back only ONCE, after pass 1 AND pass 2
		// both succeed - never per root-feed call, which is what let an earlier entity's root feed mark a
		// landing table's rows STAGED before a LATER entity's root feed on the SAME table (a canonical entity
		// and one of its members, or two entities fed from the same landing table any other way) got a chance
		// to read them at _status='NEW'.
		final StagingEndWrites endWrites = new StagingEndWrites(); // F2-06: what the closing writes could not write
		final PendingLandingStatus pendingLandingStatus = new PendingLandingStatus(endWrites);
		// lot M6 (2026-10-04): the feed contexts of the run, read once at the end for the "mapped but empty" report (FieldFillCheck)
		final List<FeedContext> preparedFeeds = new ArrayList<FeedContext>();

		// volume lot 3 (2026-09-28, task 1, "landing status per batch"): how many ROOT feeds of THIS run read
		// each landing table - computed ONCE, before any row is read (small, model-scale: one entry per
		// distinct landing table this run touches, never per row). A table read by exactly one feed (the
		// overwhelming common case) writes its _status back per BATCH, with no run-long accumulation at all;
		// only a table shared by two-or-more root feeds still goes through pendingLandingStatus - see
		// LandingTableFanout's and PendingLandingStatus's own Javadoc for why the split is safe.
		final List<String> rootFeedLandingTableKeys = new ArrayList<String>();
		for (final TransferPlan.Entity entity : plan.loadOrder)
		{
			for (final TransferPlan.Feed rootFeed : entity.feeds)
			{
				rootFeedLandingTableKeys.add(LandingTableFanout.key(rootFeed.landingDataspace, rootFeed.landingDataset, rootFeed.landingTableName));
			}
		}
		final Map<String, Integer> landingTableReaderCounts = LandingTableFanout.countReaders(rootFeedLandingTableKeys);

		// staging-live lot: every business object x application cell, declared before the first row is read
		for (final TransferPlan.Entity entity : plan.loadOrder)
		{
			for (final TransferPlan.Feed rootFeed : entity.feeds)
			{
				StagingLoader.planTree(rootFeed, rootFeed.sourceSystem, pListener);
			}
		}

		// lot V3-S2 (2026-10-04): scope all only - the staging-table indexes (one streaming read per table instead of three random reads per landing row);
		// null in scope new, and when they cannot be declared (the load then runs exactly as lot V3-S did)
		final StagingLoadReads reads = pAllRows
			? StagingLoader.newReads(plan, stagingTables, entityInfoByName, foreignKeyFieldByAttributeByEntity, derivedFieldsByEntity)
			: null;

		// volume lot 3 (task 8, "cancellation"): true once pHandle.cancelled() stopped the run early - checked
		// per batch inside loadRootFeed/resolveDeferredReferences (see their own Javadoc) and once more here,
		// between entities/FK edges, so a run made up of very small/short feeds is still stopped promptly.
		boolean stopped = false;
		try
		{
			// pass 1: every ROOT feed of every entity, parents first - see TransferPlan's own Javadoc for why this
			// order matters. A child feed is never iterated here: it is reached exclusively by recursion, inline,
			// while its own ancestor root feed's landing rows are processed - see the class Javadoc.
			entityLoop:
			for (final TransferPlan.Entity entity : plan.loadOrder)
			{
				final long entityStart = System.currentTimeMillis();
				for (final TransferPlan.Feed rootFeed : entity.feeds)
				{
					final FeedContext context = StagingLoader.prepareFeed(rootFeed, stagingTables, entityInfoByName, foreignKeyFieldByAttributeByEntity,
						derivedFieldsByEntity, countersByEntity, reads, false);
					preparedFeeds.add(context);
					final boolean completed = StagingLoader.loadRootFeed(pSession, pFactory, projectId, entityIdByName, context, stagingDataset, stagingTables,
						batchSize, lookups, ctx, ruleCounters, countersByEntity, firstRejectReasons, pAllRows, pendingLandingStatus, pListener,
						landingTableReaderCounts, pHandle, stepId, reads);
					if (!completed)
					{
						stopped = true;
						break entityLoop;
					}
					if (reads != null)
					{
						reads.releaseAfter(rootFeed); // lot V3-S2: the indexes no later root feed reads
					}
				}
				countersByEntity.get(entity.name).durationMs = Math.max(1, System.currentTimeMillis() - entityStart);

				// task C: UNIQUE is a whole-entity, whole-load concern - run once per entity, right after every one
				// of its feeds (root and, recursively, child) has finished writing this run.
				final String entityId = entityIdByName.get(entity.name);
				final AdaptationTable stagingTable = stagingTables.get(entity.name);
				for (final UniqueRuleRef ref : StagingLoader.readUniqueRules(pFactory, entityId))
				{
					pListener.note(RunTexts.checkingUnique(entity.name, ref.attributeName).stored());
					final RuleRunCounters uniqueCounters = StagingLoader.runUniqueCheck(pSession, stagingDataset, stagingTable, entity.name, ref.attributeName);
					ruleCounters.put(ref.ruleId, uniqueCounters);
					if (uniqueCounters.examplesUnavailable != null)
					{
						// lot M11: the examples are an advisory - the load goes on, the detail says why there are none
						pListener.note(RunTexts.uniqueExamplesUnavailable(entity.name, ref.attributeName, uniqueCounters.examplesUnavailable).stored());
					}
				}

				if (pHandle != null && pHandle.cancelled())
				{
					stopped = true;
					break;
				}
			}

			if (reads != null)
			{
				reads.releaseAll(); // lot V3-S2: pass 2 and the child-keys pass read the tables themselves
			}

			// pass 2: deferred references (self references, and edges inside an unresolved cycle) - ordinary
			// mapping-based foreign keys only, never a feed's structural parentLinkField (see the class Javadoc).
			// Skipped entirely once pass 1 was stopped early - resuming it is exactly what the next run is for.
			if (!stopped)
			{
				for (final TransferPlan.ForeignKeyPlan fk : plan.foreignKeys)
				{
					if (!fk.deferred)
					{
						continue;
					}
					if (pHandle != null && pHandle.cancelled())
					{
						stopped = true;
						break;
					}
					pListener.note(RunTexts.resolvingReferences(fk.entityName, fk.targetEntityName).stored());
					final AdaptationTable ownTable = stagingTables.get(fk.entityName);
					final AdaptationTable targetTable = stagingTables.get(fk.targetEntityName);
					final Counters counters = countersByEntity.get(fk.entityName);
					if (ownTable == null || targetTable == null || counters == null)
					{
						continue;
					}
					if (!StagingLoader.resolveDeferredReferences(pSession, stagingDataset, fk, ownTable, targetTable, batchSize, counters, pHandle, endWrites))
					{
						stopped = true;
						break;
					}
				}
			}

			// lot M3 (2026-10-03): the CHILD_KEYS post-pass - every child entity of the run is loaded and its parent references are final
			// (pass 2 is over), so each parent can now get one exact key per child. See ChildKeysPass/ChildKeysSweep. A failure throws and
			// ends the step FAILED (the shared-table landing statuses are then not written back: those rows are read again next time).
			if (!childKeyDefinitions.isEmpty())
			{
				final ArrayNode childKeysDetail = detail.putArray("childKeys");
				for (final ChildKeysPass.Definition definition : childKeyDefinitions)
				{
					final Counters parentCounters = countersByEntity.get(definition.entityName);
					final Counters childCounters = definition.spec == null ? null : countersByEntity.get(definition.spec.childEntity);
					final long parentWritten = parentCounters == null ? 0 : parentCounters.written;
					final long childWritten = childCounters == null ? 0 : childCounters.written;
					if (stopped || (pHandle != null && pHandle.cancelled()))
					{
						stopped = true;
						childKeysDetail.add(ChildKeysPass.stopped(pSession, pFactory, definition, parentWritten, childWritten));
						continue;
					}
					pListener.note(RunTexts.computingFrom(definition.entityName, definition.attributeName, definition.spec.childEntity).stored());
					childKeysDetail.add(ChildKeysPass.run(pSession, pFactory, stagingDataset, definition, pAllRows, parentWritten, childWritten, batchSize, pHandle, stepId));
				}
			}
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			// lot MP (2026-10-04): the same hole as ebx.MasterLoader's MASTER_LOAD - a failure while READING (the staging-table indexes of lot V3-S2, a scan, the
			// add-on's "requested transaction ... is in the future") is no OperationException, so the step stayed RUNNING for ever with no message. Ended FAILED with the
			// reason, the exception still goes to the caller. Minimal on purpose: the loader is not restructured (the entity counters are only gathered at the normal end).
			PipelineSteps.end(pSession, pFactory, stepId, false, "Staging load failed: " + ex.getClass().getName() + ": " + ex.getMessage(), detail.toString());
			throw ex;
		}

		// 2026-09-23 bug fix: only NOW, after every entity's every root feed (and, recursively, every child feed)
		// has read every landing row it will ever read this run (or the run was cleanly stopped - what is already
		// decided is still worth writing back), are the SHARED-table accumulated decisions actually written back -
		// see PendingLandingStatus's own Javadoc. A crash anywhere above (the catch block just above rethrows
		// before reaching here) simply discards this accumulator: every landing row this run touched stays at
		// _status=NEW, which is safe - scope=new picks them all back up on the next run, and the staging upsert
		// is idempotent by primary key. A single-reader table's own rows were already written back per batch
		// inside loadRootFeed - this only ever flushes the (rare) shared-table remainder.
		pListener.note(RunTexts.markingStaged().stored());
		StagingLoader.flushPendingLandingStatus(pSession, pendingLandingStatus, batchSize, pAllRows);

		// fix/staging-proposal-quality (2026-10-17, item 1): a computed field empty on EVERY row of its table is reported on the entity's result
		final Map<String, List<String>> emptyComputedByEntity = stopped ? new LinkedHashMap<String, List<String>>()
			: StagingLoader.collectEmptyComputed(plan.loadOrder, stagingTables, derivedFieldsByEntity, scriptEntries);
		final List<String> emptyComputedNotes = new ArrayList<String>();
		for (final List<String> notes : emptyComputedByEntity.values())
		{
			emptyComputedNotes.addAll(notes);
		}
		for (final String note : emptyComputedNotes)
		{
			pListener.note(note);
		}

		long totalRead = 0;
		long totalWritten = 0;
		long totalSkipped = 0;
		long totalRejected = 0;
		long totalUnchanged = 0;
		long totalEmptyChildrenSkipped = 0;
		long totalEmptyChildrenStaleFound = 0;
		boolean sourceHashFieldEverywhere = true;
		for (final TransferPlan.Entity entity : plan.loadOrder)
		{
			final Counters counters = countersByEntity.get(entity.name);
			totalRead += counters.read;
			totalWritten += counters.written;
			totalSkipped += counters.skipped;
			totalRejected += counters.rejected;
			totalUnchanged += counters.unchanged;
			totalEmptyChildrenSkipped += counters.emptyChildrenSkipped;
			totalEmptyChildrenStaleFound += counters.emptyChildrenStaleFound;
			sourceHashFieldEverywhere = sourceHashFieldEverywhere && counters.sourceHashField;
			final ObjectNode entityDetail = entityDetails.addObject();
			entityDetail.put("entity", entity.name);
			entityDetail.put("read", counters.read);
			entityDetail.put("written", counters.written);
			entityDetail.put("unchanged", counters.unchanged);
			entityDetail.put("skipped", counters.skipped);
			entityDetail.put("rejected", counters.rejected);
			// staging load rejects (2026-10-17): why they were set aside, by RejectCategory - what the wizard words its "set aside" sentence from
			final ObjectNode rejectedBy = entityDetail.putObject("rejectedBy");
			for (final Map.Entry<String, Long> byCategory : counters.rejectedByCategory.entrySet())
			{
				rejectedBy.put(byCategory.getKey(), byCategory.getValue().longValue());
			}
			entityDetail.put("emptyChildrenSkipped", counters.emptyChildrenSkipped);
			entityDetail.put("emptyChildrenStaleFound", counters.emptyChildrenStaleFound);
			entityDetail.put("sourceHashField", counters.sourceHashField);
			entityDetail.put("durationMs", counters.durationMs);
			entityDetail.put("rowsPerSecond", counters.read * 1000.0 / counters.durationMs);
			final List<String> emptyComputed = emptyComputedByEntity.get(entity.name);
			if (emptyComputed != null && !emptyComputed.isEmpty())
			{
				final ArrayNode emptyComputedNode = entityDetail.putArray("computedFieldsEmpty");
				for (final String note : emptyComputed)
				{
					emptyComputedNode.add(note);
				}
			}
			if (reads != null)
			{
				// lot V3-S2: readMode (SCAN / LOOKUP and why), the index size and build time, and what the load did with it (scope all only)
				reads.describe(entity.name, entityDetail);
			}
			if (!counters.missingFields.isEmpty())
			{
				final ArrayNode missingFieldsNode = entityDetail.putArray("missingFields");
				for (final String fieldName : counters.missingFields)
				{
					missingFieldsNode.add(fieldName);
				}
			}
		}
		// lot M6: a mapped attribute whose source column is filled but whose staging value is empty on (nearly) every row - never silent
		final List<String> unfilledNotes = new ArrayList<String>();
		final ArrayNode unfilledDetail = StagingLoader.MAPPER.createArrayNode();
		for (final FeedContext prepared : preparedFeeds)
		{
			StagingLoader.collectUnfilled(prepared, unfilledNotes, unfilledDetail);
		}
		if (!unfilledNotes.isEmpty())
		{
			detail.set("attributesNotFilled", unfilledDetail);
			for (final String note : unfilledNotes)
			{
				pListener.note(note);
			}
		}
		StagingLoader.writeEntityCounters(pSession, pFactory, entityIdByName, countersByEntity, now);
		// lot M7 (2026-10-18): a built entity that no accepted mapping feeds is not in the plan - say so on its row, never leave it with the counters of an older load (or none)
		final List<String> noFeedEntities = StagingLoader.entitiesWithoutFeed(entityInfos, builtEntities, countersByEntity.keySet());
		if (!noFeedEntities.isEmpty())
		{
			StagingLoader.writeNoFeedMessages(pSession, pFactory, entityIdByName, noFeedEntities, now);
			final ArrayNode noFeedNode = detail.putArray("entitiesWithoutFeed");
			for (final String name : noFeedEntities)
			{
				noFeedNode.add(name);
				pListener.note(name + ": " + StagingLoader.NO_FEED_MESSAGE);
			}
		}
		StagingLoader.writeQualityRuleCounters(pSession, pFactory, ruleCounters, now);

		final long durationMs = Math.max(1, System.currentTimeMillis() - now.getTime());
		final double rowsPerSecond = totalRead * 1000.0 / durationMs;
		String message = "Wrote " + totalWritten + " row(s) from " + totalRead + " source row(s) read (" + totalUnchanged + " unchanged, " + totalSkipped
			+ " skipped, " + totalRejected + " rejected). Scope: " + (pAllRows ? "all" : "new") + ".";
		// empty-child bug fix, 2026-09-25 - see processRow's own Javadoc: say it in the step message, not only the JSON.
		if (totalEmptyChildrenSkipped > 0)
		{
			message += " " + totalEmptyChildrenSkipped + " empty child row(s) not created (every mapped field was empty).";
		}
		if (totalEmptyChildrenStaleFound > 0)
		{
			message += " " + totalEmptyChildrenStaleFound
				+ " previously-written empty child row(s) found and left in place - review and remove manually if unwanted.";
		}
		// lot M3: one sentence per CHILD_KEYS attribute that was computed, skipped or stopped - what the run did with each, never silent
		if (detail.has("childKeys"))
		{
			for (final JsonNode childKeys : detail.get("childKeys"))
			{
				final String status = childKeys.path("status").asText("");
				final String name = childKeys.path("entity").asText("") + "." + childKeys.path("attribute").asText("");
				if ("DONE".equals(status))
				{
					message += " " + name + " (child keys): " + childKeys.path("summary").asText("");
				}
				else if (!"NOT_NEEDED".equals(status))
				{
					message += " " + name + " (child keys) " + status + ": " + childKeys.path("reason").asText("");
				}
			}
		}
		// lot M5: one sentence per script derivation held empty because it is not applied - never a silent empty column
		for (final String note : scriptNotAppliedNotes)
		{
			message += " " + note + ".";
		}
		// lot M6: one sentence per (entity, feed, attribute) mapped from a filled source column and left empty - see FieldFillCheck
		for (final String note : unfilledNotes)
		{
			message += " " + note + ".";
		}
		// fix/staging-proposal-quality (2026-10-17, item 1): derivations applied by this load, and computed fields left empty on every row
		for (final String note : autoApplyNotes)
		{
			message += " " + note + ".";
		}
		for (final String note : repairNotes)
		{
			message += " " + note + ".";
		}
		for (final String note : emptyComputedNotes)
		{
			message += " " + note + ".";
		}

		// volume lot 3 (task 8): a run pHandle.cancelled() stopped ends STOPPED, never DONE or FAILED - what was
		// already written stays written (see loadRootFeed/resolveDeferredReferences' own Javadoc), and re-running
		// the same scope simply resumes it (scope=new never re-reads an already-STAGED/REJECTED row; scope=all
		// re-reads everything, idempotent by primary key either way).
		// the same rule as the master load: a load whose every row READ was rejected wrote nothing, and a step, a job and a route that say DONE for it are
		// indistinguishable from a genuinely empty, successful run (MasterLoadOutcome says why "0 written" alone is not the test)
		final boolean totalFailure = StagingLoader.isTotalFailure(totalRead, totalRejected, totalWritten, totalUnchanged, totalSkipped);
		if (stopped)
		{
			message = "Staging load stopped (" + (pHandle == null ? "?" : pHandle.stopReason()) + ") after " + message;
			PipelineSteps.endStopped(pSession, pFactory, stepId, message, detail.toString());
		}
		else
		{
			if (totalFailure)
			{
				message += " Every source row read was rejected, nothing was written: see the TransferReject rows for the reasons.";
			}
			// F2-06: a closing write that failed is never a DONE - the step says what was not written and what to run again
			// (first: the step message is cut at its maximum length, and this is the sentence that must survive)
			if (endWrites.any())
			{
				message = endWrites.message().trim() + " " + message;
			}
			if (endWrites.any())
			{
				detail.put("referencesNotWritten", endWrites.referenceRows());
				detail.put("landingStatusNotWritten", endWrites.landingRows());
			}
			final String endStatus = endWrites.status(false, totalFailure);
			if (StagingEndWrites.PARTIAL.equals(endStatus))
			{
				PipelineSteps.endPartial(pSession, pFactory, stepId, message, detail.toString());
			}
			else
			{
				PipelineSteps.end(pSession, pFactory, stepId, !totalFailure, message, detail.toString());
			}
		}

		long qualityRulesFailing = 0;
		for (final RuleRunCounters counters : ruleCounters.values())
		{
			if (counters.failing > 0)
			{
				qualityRulesFailing++;
			}
		}

		final ObjectNode out = StagingLoader.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("scope", pAllRows ? "all" : "new");
		out.put("batchSize", batchSize);
		out.put("read", totalRead);
		out.put("written", totalWritten);
		out.put("unchanged", totalUnchanged);
		out.put("skipped", totalSkipped);
		out.put("rejected", totalRejected);
		out.put("emptyChildrenSkipped", totalEmptyChildrenSkipped);
		out.put("emptyChildrenStaleFound", totalEmptyChildrenStaleFound);
		out.put("sourceHashField", sourceHashFieldEverywhere);
		out.put("durationMs", durationMs);
		out.put("rowsPerSecond", rowsPerSecond);
		out.put("qualityRulesChecked", ruleCounters.size());
		out.put("qualityRulesFailing", qualityRulesFailing);
		int fieldsNotYetInModel = 0;
		for (final Counters counters : countersByEntity.values())
		{
			fieldsNotYetInModel += counters.missingFields.size();
		}
		out.put("fieldsNotYetInModel", fieldsNotYetInModel);
		out.set("entities", entityDetails);
		if (detail.has("scriptDerivationsNotApplied"))
		{
			out.set("scriptDerivationsNotApplied", detail.get("scriptDerivationsNotApplied"));
		}
		if (detail.has("childKeys"))
		{
			out.set("childKeys", detail.get("childKeys"));
		}
		if (detail.has("attributesNotFilled"))
		{
			out.set("attributesNotFilled", detail.get("attributesNotFilled"));
		}
		if (detail.has("derivationsAutoApplied"))
		{
			out.set("derivationsAutoApplied", detail.get("derivationsAutoApplied"));
		}
		if (detail.has("derivationsRepaired"))
		{
			out.set("derivationsRepaired", detail.get("derivationsRepaired"));
		}
		if (!emptyComputedNotes.isEmpty())
		{
			final ArrayNode emptyComputedOut = out.putArray("computedFieldsEmpty");
			for (final String note : emptyComputedNotes)
			{
				emptyComputedOut.add(note);
			}
		}
		final ArrayNode rejectsNode = out.putArray("firstRejectReasons");
		for (final String reason : firstRejectReasons)
		{
			rejectsNode.add(reason);
		}
		out.put("status", endWrites.status(stopped, totalFailure));
		out.put("referencesNotWritten", endWrites.referenceRows());
		out.put("landingStatusNotWritten", endWrites.landingRows());
		out.put("endMessage", endWrites.any() ? endWrites.message().trim() : "");
		return out;
	}

	// ================================================================================================== pass 1

	private static final class Counters
	{
		long read;
		long written;
		long skipped;
		long rejected;
		long unchanged;
		/** {@link FeedBuffers#emptyChildrenSkipped} folded in across every feed of this entity - see {@code processRow}'s own Javadoc. */
		long emptyChildrenSkipped;
		/** {@link FeedBuffers#emptyChildrenStaleFound} folded in across every feed of this entity - see {@code processRow}'s own Javadoc. */
		long emptyChildrenStaleFound;
		/** Staging load rejects (2026-10-17): why this entity's rows were set aside, by {@link RejectCategory} (what the wizard words the "set aside" sentence from). */
		final Map<String, Long> rejectedByCategory = new LinkedHashMap<String, Long>();
		boolean sourceHashField = true;
		long durationMs = 1;
		final Set<String> missingFields = new LinkedHashSet<String>();
	}

	/**
	 * Tests every one of {@code pCandidateFieldNames} against the live table's own schema, once per feed (never
	 * per row) - {@code SchemaNode.getNode} is the same "does this path actually exist" check CLAUDE.md sec 9
	 * already uses for a record. {@link FieldAvailabilityRule#classify} is the pure set-difference this feeds.
	 */
	private static Set<String> existingFieldNames(final AdaptationTable pStagingTable, final Set<String> pCandidateFieldNames)
	{
		final Set<String> out = new HashSet<String>();
		final SchemaNode root = pStagingTable.getTableOccurrenceRootNode();
		for (final String name : pCandidateFieldNames)
		{
			if (root.getNode(Path.parse("./" + name)) != null)
			{
				out.add(name);
			}
		}
		return out;
	}

	/**
	 * Lot V3-S (2026-10-04): one staging column as the scope {@code all} comparison ({@link StagingRowDiff}) sees it - its {@link Path} and
	 * {@link SchemaNode}, resolved once per feed. {@link #parse} is the very call {@code setValueFromXsString} makes (javap -c on {@code
	 * CfContentHolder}: {@code getNode(path).parseXsString(xs)} then {@code setValue}), so the typed value compared is the value a write would store.
	 */
	static final class StagingColumn implements StagingRowDiff.Field
	{
		final Path path;
		private final SchemaNode node;
		private final boolean comparable;
		private final boolean reference;

		StagingColumn(final Path pPath, final SchemaNode pNode)
		{
			this.path = pPath;
			this.node = pNode;
			// only a single-valued terminal column that EBX does not compute is reasoned about; anything else is written as before
			this.comparable = pNode != null && pNode.isTerminalValue() && !pNode.isTerminalValueComputed() && pNode.getMaxOccurs() == 1;
			this.reference = pNode != null && pNode.getFacetOnTableReference() != null;
		}

		@Override
		public boolean comparable()
		{
			return this.comparable;
		}

		@Override
		public boolean reference()
		{
			return this.reference;
		}

		@Override
		public Object parse(final String pXsString) throws Exception
		{
			return this.node.parseXsString(pXsString);
		}

		@Override
		public String format(final Object pStoredValue)
		{
			return this.node.formatToXsString(pStoredValue);
		}
	}

	/** Lot V3-S: the column named {@code pName} of the feed's staging table, resolved on first use. */
	private static StagingColumn column(final FeedContext pContext, final String pName)
	{
		StagingColumn column = pContext.columns.get(pName);
		if (column == null)
		{
			final Path path = Path.parse("./" + pName);
			column = new StagingColumn(path, pContext.stagingTable.getTableOccurrenceRootNode().getNode(path));
			pContext.columns.put(pName, column);
		}
		return column;
	}

	/** Lot V3-S: an existing staging row seen through {@link StagingRowDiff.Stored}. */
	private static final class StoredStagingRow implements StagingRowDiff.Stored
	{
		private final FeedContext context;
		private final Adaptation row;

		StoredStagingRow(final FeedContext pContext, final Adaptation pRow)
		{
			this.context = pContext;
			this.row = pRow;
		}

		@Override
		public StagingRowDiff.Field field(final String pName)
		{
			return StagingLoader.column(this.context, pName);
		}

		@Override
		public Object value(final String pName)
		{
			return this.row.get(StagingLoader.column(this.context, pName).path);
		}

		@Override
		public String status()
		{
			return this.row.getString(StagingLoader.STAGING_STATUS);
		}

		@Override
		public Object sourceHash()
		{
			return this.row.get(StagingLoader.STAGING_SOURCE_HASH);
		}
	}

	// ============================================================================================ task C: QualityRule

	private static final class RuleRunCounters
	{
		long checked;
		long failing;
		final List<String> exampleKeys = new ArrayList<String>();
		/** Lot M11: set when the advisory example query could not run (or only partly): the reason, shown in the step detail; the counts are unaffected. */
		String examplesUnavailable;
	}

	private static void recordQualityCheck(final Map<String, RuleRunCounters> pRuleCounters, final QualityRuleCheck.Rule pRule, final boolean pPassed,
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
			if (counters.exampleKeys.size() < StagingLoader.MAX_QUALITY_RULE_EXAMPLES)
			{
				counters.exampleKeys.add(pRowKey);
			}
		}
	}

	private static final class UniqueRuleRef
	{
		final String ruleId;
		final String attributeName;

		UniqueRuleRef(final String pRuleId, final String pAttributeName)
		{
			this.ruleId = pRuleId;
			this.attributeName = pAttributeName;
		}
	}

	private static List<UniqueRuleRef> readUniqueRules(final Adaptation pFactory, final String pEntityId)
	{
		final List<UniqueRuleRef> out = new ArrayList<UniqueRuleRef>();
		if (pEntityId == null || pEntityId.trim().isEmpty())
		{
			return out;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.QUALITY_RULE)
			.createRequestResult("./entity=" + CatalogReader.quote(pEntityId) + " and ./kind='" + QualityDetectors.UNIQUE + "'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.QualityRule.STATUS)))
				{
					continue;
				}
				final String attributeId = row.getString(FactoryPaths.QualityRule.ATTRIBUTE);
				if (attributeId == null || attributeId.trim().isEmpty())
				{
					continue;
				}
				final Adaptation attributeRow = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(attributeId));
				if (attributeRow == null)
				{
					continue;
				}
				out.add(new UniqueRuleRef(row.getOccurrencePrimaryKey().format(), attributeRow.getString(FactoryPaths.StagingAttribute.NAME)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Volume lot 3 (2026-09-28, docs/VOLUME.md task 2, "UNIQUE rule in SQL"): the pre-lot version loaded the
	 * WHOLE staging table (not merely this run's rows) into a {@code List<RowValue>}, then grouped it in Java -
	 * O(table size) memory regardless of how many rows are actually duplicated. Replaced with three bounded SQL
	 * round trips, the exact shapes validated live 2026-09-28 against a real staging table (345 rows, one
	 * genuinely duplicated column) via the {@code data-access} MCP server's {@code execute_query} - see
	 * docs/VOLUME.md's own "Lot 3" section for the verbatim results: (1) {@code COUNT(field)} for the non-null
	 * total ({@link #queryUniqueChecked}); (2) {@code SUM} over a {@code GROUP BY ... HAVING COUNT(*) > 1}
	 * subquery for the exact total failing row count, however many distinct duplicated values exist -
	 * {@link #queryUniqueFailing} - never capped, unlike the examples below; (3) up to {@code
	 * MAX_QUALITY_RULE_EXAMPLES} example row keys, gathered from up to 11 duplicated values ({@code LIMIT 11},
	 * confirmed live to work exactly as written - CLAUDE.md's older "EBX SQL only has OFFSET...FETCH" note was
	 * stale for this build: plain {@code LIMIT n} is supported directly) via {@link #queryUniqueExampleKeys}.
	 * An unquotable entity/attribute name (should never happen - both come from this factory's own generated
	 * field/table names) or any SQL failure falls back to {@link #runUniqueCheckByScan} - the original,
	 * unbounded-but-correct behaviour - rather than silently reporting nothing.
	 */
	private static RuleRunCounters runUniqueCheck(final Session pSession, final Adaptation pStagingDataset, final AdaptationTable pStagingTable,
		final String pEntityName, final String pAttributeName)
	{
		if (!CrossSystemMeasures.isQuotable(pEntityName) || !CrossSystemMeasures.isQuotable(pAttributeName))
		{
			return StagingLoader.runUniqueCheckByScan(pStagingTable, pAttributeName);
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String field = CrossSystemMeasures.quoteIdentifier(pAttributeName);
		final Long checked = StagingLoader.queryUniqueChecked(pSession, pStagingDataset, table, field);
		final Long failing = StagingLoader.queryUniqueFailing(pSession, pStagingDataset, table, field);
		if (checked == null || failing == null)
		{
			return StagingLoader.runUniqueCheckByScan(pStagingTable, pAttributeName);
		}
		final RuleRunCounters counters = new RuleRunCounters();
		counters.checked = checked.longValue();
		counters.failing = failing.longValue();
		final String kind = StagingLoader.uniqueColumnKind(pStagingTable, pAttributeName);
		final UniqueExamples examples = StagingLoader.gatherUniqueExamples(new SqlExampleQueries(pSession, pStagingDataset, table, field, kind));
		counters.exampleKeys.addAll(examples.keys);
		counters.examplesUnavailable = examples.unavailableReason;
		return counters;
	}

	/** Lot M11: the value kind of a staging column for a SQL literal - {@code date}, {@code dateTime}, {@code time}, {@code decimal}, {@code int}, {@code boolean}, or {@code null} (a string, or unknown: the literal then follows the value's own Java class). */
	static String uniqueColumnKind(final AdaptationTable pStagingTable, final String pAttributeName)
	{
		try
		{
			final SchemaNode node = pStagingTable.getTableOccurrenceRootNode().getNode(Path.parse("./" + pAttributeName));
			if (node == null)
			{
				return null;
			}
			final com.orchestranetworks.schema.SchemaTypeName type = node.getXsTypeName();
			if (type != null)
			{
				if (com.orchestranetworks.schema.SchemaTypeName.XS_DATE.equals(type))
				{
					return "date";
				}
				if (com.orchestranetworks.schema.SchemaTypeName.XS_DATETIME.equals(type))
				{
					return "dateTime";
				}
				if (com.orchestranetworks.schema.SchemaTypeName.XS_TIME.equals(type))
				{
					return "time";
				}
				if (com.orchestranetworks.schema.SchemaTypeName.XS_BOOLEAN.equals(type))
				{
					return "boolean";
				}
				if (com.orchestranetworks.schema.SchemaTypeName.XS_INT.equals(type) || com.orchestranetworks.schema.SchemaTypeName.XS_INTEGER.equals(type))
				{
					return "int";
				}
				if (com.orchestranetworks.schema.SchemaTypeName.XS_DECIMAL.equals(type))
				{
					return "decimal";
				}
			}
			final Class<?> javaClass = node.getOccurrencesJavaClass();
			if (javaClass == null)
			{
				return null;
			}
			if (java.math.BigDecimal.class.equals(javaClass))
			{
				return "decimal";
			}
			if (Integer.class.equals(javaClass) || Long.class.equals(javaClass))
			{
				return "int";
			}
			if (Boolean.class.equals(javaClass))
			{
				return "boolean";
			}
			return null;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * Lot M11 (2026-10-18, job 642, project E2EMASTER20261018070131): the SQL literal for a value read back from a
	 * {@code GROUP BY} of a staging column. The example query used to write {@code String.valueOf(value)} between
	 * quotes; for a DATE key that is the {@code java.util.Date} text {@code Sun Apr 01 00:00:00 CEST 2018}, which
	 * the query engine compiles into a DATE constant and refuses ({@code Invalid DATE value}, an
	 * {@code ExceptionInInitializerError} - an Error, not an Exception - that killed the whole load). Each kind gets
	 * its own SQL literal: {@code DATE 'yyyy-MM-dd'}, {@code TIMESTAMP 'yyyy-MM-dd HH:mm:ss[.SSS]'}, {@code TIME
	 * 'HH:mm:ss'}, a bare number, {@code TRUE}/{@code FALSE}, a quoted string for anything else. The date text is
	 * written in the JVM's default zone, which is how EBX reads a date (local midnight).
	 */
	static String sqlLiteralFor(final Object pValue, final String pKind)
	{
		if (pValue == null)
		{
			return "NULL";
		}
		if (pValue instanceof java.time.LocalDate)
		{
			return "DATE '" + pValue + "'";
		}
		if (pValue instanceof java.time.LocalDateTime)
		{
			return StagingLoader.timestampLiteral(java.util.Date.from(((java.time.LocalDateTime) pValue).atZone(java.time.ZoneId.systemDefault()).toInstant()));
		}
		if (pValue instanceof java.time.LocalTime)
		{
			return "TIME '" + ((java.time.LocalTime) pValue).withNano(0) + "'";
		}
		if (pValue instanceof java.util.Date)
		{
			final java.util.Date date = (java.util.Date) pValue;
			final java.util.Calendar calendar = java.util.Calendar.getInstance();
			calendar.setTime(date);
			final boolean noTime = calendar.get(java.util.Calendar.HOUR_OF_DAY) == 0 && calendar.get(java.util.Calendar.MINUTE) == 0
				&& calendar.get(java.util.Calendar.SECOND) == 0 && calendar.get(java.util.Calendar.MILLISECOND) == 0;
			final boolean epochDay = calendar.get(java.util.Calendar.YEAR) == 1970 && calendar.get(java.util.Calendar.DAY_OF_YEAR) == 1;
			final String kind = pKind != null ? pKind : epochDay && !noTime ? "time" : noTime ? "date" : "dateTime";
			if ("time".equals(kind))
			{
				return "TIME '" + new java.text.SimpleDateFormat("HH:mm:ss").format(date) + "'";
			}
			if ("date".equals(kind))
			{
				return "DATE '" + new java.text.SimpleDateFormat("yyyy-MM-dd").format(date) + "'";
			}
			return StagingLoader.timestampLiteral(date);
		}
		if (pValue instanceof Boolean)
		{
			return ((Boolean) pValue).booleanValue() ? "TRUE" : "FALSE";
		}
		if (pValue instanceof BigDecimal)
		{
			return ((BigDecimal) pValue).toPlainString();
		}
		if (pValue instanceof Number)
		{
			return String.valueOf(pValue);
		}
		return MatchPlanSimulationRunner.sqlStringLiteral(String.valueOf(pValue));
	}

	private static String timestampLiteral(final java.util.Date pDate)
	{
		final java.util.Calendar calendar = java.util.Calendar.getInstance();
		calendar.setTime(pDate);
		final String pattern = calendar.get(java.util.Calendar.MILLISECOND) == 0 ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd HH:mm:ss.SSS";
		return "TIMESTAMP '" + new java.text.SimpleDateFormat(pattern).format(pDate) + "'";
	}

	/** Lot M11: why a failure left the examples out - the root cause's class and message, one short line (an {@code ExceptionInInitializerError} says nothing itself: its cause does). */
	static String describeFailure(final Throwable pFailure)
	{
		Throwable root = pFailure;
		for (int depth = 0; depth < 8 && root.getCause() != null && root.getCause() != root; depth++)
		{
			root = root.getCause();
		}
		final String message = root.getMessage() == null ? "" : root.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
		final String text = root.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
		return text.length() > 200 ? text.substring(0, 200) + "..." : text;
	}

	/** Lot M11: what the example gathering found - the keys, and the reason when something failed on the way (the keys gathered before it are kept). */
	static final class UniqueExamples
	{
		final List<String> keys = new ArrayList<String>();
		String unavailableReason;
	}

	/** Lot M11: the two reads the example gathering needs, so the loop (and its failure handling) is testable without a repository. */
	interface UniqueExampleQueries
	{
		/** Up to 11 duplicated values of the column. */
		List<Object> duplicatedValues();

		/** Up to {@code pLimit} primary keys of the rows holding {@code pValue}. */
		List<String> keysFor(Object pValue, int pLimit);
	}

	/**
	 * Lot M11: gathers up to {@link #MAX_QUALITY_RULE_EXAMPLES} example keys and NEVER throws: the examples are an
	 * advisory, the counts already carry the check. A failure of either read - an Exception or a {@link LinkageError}
	 * such as the {@code ExceptionInInitializerError} the query engine raises for a literal it refuses - stops the
	 * gathering, keeps what was found and records the reason.
	 */
	static UniqueExamples gatherUniqueExamples(final UniqueExampleQueries pQueries)
	{
		final UniqueExamples out = new UniqueExamples();
		try
		{
			for (final Object value : pQueries.duplicatedValues())
			{
				if (out.keys.size() >= StagingLoader.MAX_QUALITY_RULE_EXAMPLES)
				{
					break;
				}
				if (value == null)
				{
					continue;
				}
				try
				{
					out.keys.addAll(pQueries.keysFor(value, StagingLoader.MAX_QUALITY_RULE_EXAMPLES - out.keys.size()));
				}
				catch (final RuntimeException | LinkageError ex)
				{
					// this value's keys could not be fetched: keep the others, say why for the first one
					if (out.unavailableReason == null)
					{
						out.unavailableReason = StagingLoader.describeFailure(ex);
					}
				}
			}
		}
		catch (final RuntimeException | LinkageError ex)
		{
			out.unavailableReason = StagingLoader.describeFailure(ex);
		}
		return out;
	}

	/** Lot M11: the repository-backed reads of {@link #gatherUniqueExamples}. */
	private static final class SqlExampleQueries implements UniqueExampleQueries
	{
		private final Session session;
		private final Adaptation dataset;
		private final String quotedTable;
		private final String quotedField;
		private final String kind;

		SqlExampleQueries(final Session pSession, final Adaptation pDataset, final String pQuotedTable, final String pQuotedField, final String pKind)
		{
			this.session = pSession;
			this.dataset = pDataset;
			this.quotedTable = pQuotedTable;
			this.quotedField = pQuotedField;
			this.kind = pKind;
		}

		@Override
		public List<Object> duplicatedValues()
		{
			final List<Object> values = new ArrayList<Object>();
			final String groupsSql = "SELECT c." + this.quotedField + " AS V, COUNT(*) AS N FROM " + this.quotedTable + " c WHERE c." + this.quotedField
				+ " IS NOT NULL GROUP BY c." + this.quotedField + " HAVING COUNT(*) > 1 LIMIT 11";
			final Query<Tuple> groupsQuery = this.dataset.createQuery(groupsSql);
			groupsQuery.setSession(this.session);
			final QueryResult<Tuple> groupsResult = groupsQuery.getResult();
			try
			{
				final java.util.Iterator<Tuple> groups = groupsResult.iterator();
				while (groups.hasNext())
				{
					values.add(groups.next().get("V"));
				}
			}
			finally
			{
				groupsResult.close();
			}
			return values;
		}

		@Override
		public List<String> keysFor(final Object pValue, final int pLimit)
		{
			final List<String> keys = new ArrayList<String>();
			final String keysSql = "SELECT c.\"$pk\" AS PK FROM " + this.quotedTable + " c WHERE c." + this.quotedField + " = "
				+ StagingLoader.sqlLiteralFor(pValue, this.kind) + " LIMIT " + pLimit;
			final Query<Tuple> keysQuery = this.dataset.createQuery(keysSql);
			keysQuery.setSession(this.session);
			final QueryResult<Tuple> keysResult = keysQuery.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = keysResult.iterator();
				while (it.hasNext())
				{
					final Object pk = it.next().get("PK");
					if (pk != null)
					{
						keys.add(String.valueOf(pk));
					}
				}
			}
			finally
			{
				keysResult.close();
			}
			return keys;
		}
	}

	/** {@code SELECT COUNT(field) AS N FROM table c} - the non-null value count, O(1) memory regardless of table size. {@code null} on any failure (never a partial/zero count masquerading as real). */
	private static Long queryUniqueChecked(final Session pSession, final Adaptation pStagingDataset, final String pQuotedTable, final String pQuotedField)
	{
		return StagingLoader.queryScalarLong(pSession, pStagingDataset, "SELECT COUNT(c." + pQuotedField + ") AS N FROM " + pQuotedTable + " c");
	}

	/** {@code SELECT SUM(t.N) AS N FROM (SELECT COUNT(*) AS N FROM table c WHERE field IS NOT NULL GROUP BY field HAVING COUNT(*) > 1) t} - the exact total failing row count across EVERY duplicated value, never capped (unlike the examples list) - validated live 2026-09-28, see the class Javadoc. */
	private static Long queryUniqueFailing(final Session pSession, final Adaptation pStagingDataset, final String pQuotedTable, final String pQuotedField)
	{
		final String sql = "SELECT SUM(t.N) AS N FROM (SELECT COUNT(*) AS N FROM " + pQuotedTable + " c WHERE c." + pQuotedField + " IS NOT NULL GROUP BY c."
			+ pQuotedField + " HAVING COUNT(*) > 1) t";
		final Long sum = StagingLoader.queryScalarLong(pSession, pStagingDataset, sql);
		return sum == null ? Long.valueOf(0L) : sum; // SUM() over zero groups (no duplicates at all) is SQL NULL, not zero - a genuine "0 failing", not a query failure
	}

	/** One scalar aggregate, aliased {@code N} - {@code null} on any failure (a transport error, an unsupported construct), never on a legitimate NULL result (the caller decides what NULL means for its own query). */
	private static Long queryScalarLong(final Session pSession, final Adaptation pStagingDataset, final String pSql)
	{
		try
		{
			final Query<Tuple> query = pStagingDataset.createQuery(pSql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				if (!it.hasNext())
				{
					return Long.valueOf(0L);
				}
				final Object value = it.next().get("N");
				return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException | LinkageError ex) // lot M11: an Error from the query engine's generated code is a failed query too (the scan fallback answers)
		{
			return null;
		}
	}

	/** The original, unbounded-but-correct behaviour - kept as the fallback when the SQL path ({@link #runUniqueCheck}) cannot run at all (an unquotable name, a transport error). */
	private static RuleRunCounters runUniqueCheckByScan(final AdaptationTable pStagingTable, final String pAttributeName)
	{
		final Path fieldPath = Path.parse("./" + pAttributeName);
		final List<QualityRuleCheck.RowValue> rowValues = new ArrayList<QualityRuleCheck.RowValue>();
		final RequestResult rows = pStagingTable.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String value;
				try
				{
					value = row.getString(fieldPath);
				}
				catch (final RuntimeException ex)
				{
					continue;
				}
				rowValues.add(new QualityRuleCheck.RowValue(row.getOccurrencePrimaryKey().format(), value));
			}
		}
		finally
		{
			rows.close();
		}
		final QualityRuleCheck.UniqueOutcome outcome = QualityRuleCheck.duplicatesAmong(rowValues, StagingLoader.MAX_QUALITY_RULE_EXAMPLES);
		final RuleRunCounters counters = new RuleRunCounters();
		counters.checked = outcome.checked;
		counters.failing = outcome.failing;
		counters.exampleKeys.addAll(outcome.exampleKeys);
		return counters;
	}

	// ========================================================================================= feed preparation

	/**
	 * Everything about ONE {@link TransferPlan.Feed} that never depends on a specific row - computed ONCE
	 * (root feed and every descendant, recursively) before any landing row is read, and reused for every row
	 * that feed (or its recursion) touches. Bundles what the pre-lot-1 {@code loadFeed} used to compute inline
	 * for a single (implicit) feed per landing table.
	 */
	private static final class FeedContext
	{
		final TransferPlan.Feed feed;
		final String entityName;
		final AdaptationTable stagingTable;
		/** One Connectors rule per WRITABLE {@link TransferPlan.FieldPlan} of this feed - see the class Javadoc's "One MappingEngine call per feed". */
		final MappingDefinition definition;
		final Set<String> writableFields;
		/** {@code NullFillRule.defaultsFor}'s answer, already filtered to {@link #writableFields} - values are always {@code null}. */
		final Map<String, Object> nullDefaults;
		/** This entity's derived fields, already filtered to {@link #writableFields}. */
		final List<DerivedFieldRule.Field> derivedFields;
		final boolean sourceHashFieldExists;
		/** Volume lot 3 (2026-09-28, task 7): true when at least one WRITABLE field of this feed has a non-empty cleaning pipeline - computed ONCE here so {@link #processRow} can skip its own document copy entirely for a feed that never cleans anything (the common case for most child feeds). */
		final boolean hasCleaningSteps;
		/** Lot M6 (2026-10-04): one fill counter per writable field that reads a landing column, in field order - see {@link FieldFillCheck}. */
		final Map<String, FieldFillCheck.Counter> fillCounters = new LinkedHashMap<String, FieldFillCheck.Counter>();
		final List<FeedContext> children;
		/** Lot V3-S (2026-10-04): the staging columns the scope {@code all} comparison reads, resolved ONCE per feed, lazily (name -> path, node, flags) - never re-parsed per row. */
		final Map<String, StagingColumn> columns = new HashMap<String, StagingColumn>();
		/** Lot V3-S2 (2026-10-04): the staging-table indexes of a scope {@code all} load ({@link StagingLoadReads}); null in scope {@code new}, which never uses them. */
		final StagingLoadReads reads;
		/** Lot V3-S2: the position, among the layouts of this feed's entity, of the column set this feed plans ({@link #plannedColumns}); -1 without {@link #reads}. */
		int layoutIndex = -1;

		FeedContext(final TransferPlan.Feed pFeed, final AdaptationTable pStagingTable, final MappingDefinition pDefinition, final Set<String> pWritableFields,
			final Map<String, Object> pNullDefaults, final List<DerivedFieldRule.Field> pDerivedFields, final boolean pSourceHashFieldExists,
			final boolean pHasCleaningSteps, final List<FeedContext> pChildren, final StagingLoadReads pReads)
		{
			this.reads = pReads;
			this.feed = pFeed;
			this.entityName = pFeed.entityName;
			this.stagingTable = pStagingTable;
			this.definition = pDefinition;
			this.writableFields = pWritableFields;
			this.nullDefaults = pNullDefaults;
			this.derivedFields = pDerivedFields;
			this.sourceHashFieldExists = pSourceHashFieldExists;
			this.hasCleaningSteps = pHasCleaningSteps;
			this.children = pChildren;
		}
	}

	/**
	 * Builds the {@link FeedContext} of {@code pFeed} and, recursively, of every one of its {@link
	 * TransferPlan.Feed#children} - see {@link FeedContext}'s own Javadoc. Also folds this feed's own {@code
	 * missingFields}/{@code sourceHashField} into {@code pCountersByEntity}'s entry for {@link
	 * TransferPlan.Feed#entityName} (an entity fed by more than one feed - a canonical entity's members, or a
	 * child entity reached from more than one parent - accumulates across every one of them, never overwrites).
	 */
	private static FeedContext prepareFeed(final TransferPlan.Feed pFeed, final Map<String, AdaptationTable> pStagingTables,
		final Map<String, StagingModel.EntityInfo> pEntityInfoByName, final Map<String, Map<String, String>> pForeignKeyFieldByAttributeByEntity,
		final Map<String, List<DerivedFieldRule.Field>> pDerivedFieldsByEntity, final Map<String, Counters> pCountersByEntity, final StagingLoadReads pReads,
		final boolean pChild)
	{
		final AdaptationTable stagingTable = pStagingTables.get(pFeed.entityName);
		final StagingModel.EntityInfo entityInfo = pEntityInfoByName.get(pFeed.entityName);
		final List<StagingModel.AttributeInfo> entityAttributes = entityInfo == null ? null : entityInfo.attributes;
		final Map<String, String> entityForeignKeyFields = pForeignKeyFieldByAttributeByEntity.get(pFeed.entityName);
		final List<DerivedFieldRule.Field> entityDerivedFields = pDerivedFieldsByEntity.get(pFeed.entityName);

		final Set<String> feedAttributeNames = new HashSet<String>();
		for (final TransferPlan.FieldPlan field : pFeed.fields)
		{
			feedAttributeNames.add(field.stagingField);
		}
		final Map<String, Object> nullDefaults = NullFillRule.defaultsFor(entityAttributes, entityForeignKeyFields, feedAttributeNames);

		final Set<String> candidateFieldNames = new HashSet<String>(nullDefaults.keySet());
		for (final TransferPlan.FieldPlan field : pFeed.fields)
		{
			candidateFieldNames.add(field.stagingField);
			if (field.foreignKeyField != null)
			{
				candidateFieldNames.add(field.foreignKeyField);
			}
		}
		if (pFeed.parentLinkField != null)
		{
			candidateFieldNames.add(pFeed.parentLinkField);
		}
		if (entityDerivedFields != null)
		{
			for (final DerivedFieldRule.Field derived : entityDerivedFields)
			{
				candidateFieldNames.add(derived.keyFieldName);
			}
		}
		final Set<String> existingFieldNames = StagingLoader.existingFieldNames(stagingTable, candidateFieldNames);
		final FieldAvailabilityRule.Result availability = FieldAvailabilityRule.classify(candidateFieldNames, existingFieldNames);
		final Counters counters = pCountersByEntity.get(pFeed.entityName);
		if (counters != null)
		{
			counters.missingFields.addAll(availability.missing);
		}
		final boolean sourceHashFieldExists = stagingTable.getTableOccurrenceRootNode().getNode(StagingLoader.STAGING_SOURCE_HASH) != null;
		if (counters != null)
		{
			counters.sourceHashField = counters.sourceHashField && sourceHashFieldExists;
		}

		final Map<String, Object> writableNullDefaults = new LinkedHashMap<String, Object>(nullDefaults);
		writableNullDefaults.keySet().retainAll(availability.writable);
		final List<DerivedFieldRule.Field> writableDerivedFields = new ArrayList<DerivedFieldRule.Field>();
		if (entityDerivedFields != null)
		{
			for (final DerivedFieldRule.Field derived : entityDerivedFields)
			{
				if (availability.writable.contains(derived.keyFieldName))
				{
					writableDerivedFields.add(derived);
				}
			}
		}

		// lot M6: one rule per writable field, each reading its OWN cleaned alias when it has cleaning steps - see FeedRules
		final MappingDefinition definition = FeedRules.definition(pFeed.feedId, StagingModel.tablePath(pFeed.entityName), pFeed.fields, availability.writable);
		// volume lot 3 (task 7): computed once here, not per row - see FeedContext#hasCleaningSteps' own Javadoc.
		final boolean hasCleaningSteps = FeedRules.hasCleaningSteps(pFeed.fields, availability.writable);

		final List<FeedContext> children = new ArrayList<FeedContext>();
		for (final TransferPlan.Feed child : pFeed.children)
		{
			children.add(StagingLoader.prepareFeed(child, pStagingTables, pEntityInfoByName, pForeignKeyFieldByAttributeByEntity, pDerivedFieldsByEntity,
				pCountersByEntity, pReads, true));
		}
		final FeedContext context = new FeedContext(pFeed, stagingTable, definition, availability.writable, writableNullDefaults, writableDerivedFields,
			sourceHashFieldExists, hasCleaningSteps, children, pReads);
		if (pReads != null)
		{
			context.layoutIndex = pReads.addLayout(pFeed.entityName, StagingLoader.plannedColumns(context, pChild));
		}
		for (final TransferPlan.FieldPlan field : pFeed.fields)
		{
			if (availability.writable.contains(field.stagingField) && field.sourcePath != null && !field.sourcePath.isEmpty())
			{
				context.fillCounters.put(field.stagingField, new FieldFillCheck.Counter(field.stagingField, field.sourcePath));
			}
		}
		return context;
	}

	/**
	 * Lot V3-S2 (2026-10-04): the staging columns the rows of this feed will carry in their planned map ({@code RowPlan.fields}), known before any row is
	 * planned - what the scope {@code all} digests are built over. Mirrors, step by step, how {@link #processRow} fills the map: the forced-null defaults, each
	 * writable mapped field, its immediate foreign-key field (a child feed's structural parent link is written from the parent's key instead), the child's
	 * {@code parentLinkField}, and the derived fields {@code DerivedFieldRule.apply} puts. A set that is not exactly this one is never compared
	 * ({@code StagingRowDigest.sameColumns} at planning time, counted as {@code layoutMismatches}): the row takes the read path, so an error here costs speed
	 * and shows in the detail, never a wrong "unchanged".
	 */
	private static List<String> plannedColumns(final FeedContext pContext, final boolean pChild)
	{
		final Set<String> out = new LinkedHashSet<String>(pContext.nullDefaults.keySet());
		for (final TransferPlan.FieldPlan field : pContext.feed.fields)
		{
			if (!pContext.writableFields.contains(field.stagingField))
			{
				continue;
			}
			out.add(field.stagingField);
			final boolean parentLinkWins = pChild && field.structuralParentLink;
			if (field.foreignKeyField != null && !field.foreignKeyDeferred && !parentLinkWins && pContext.writableFields.contains(field.foreignKeyField))
			{
				out.add(field.foreignKeyField);
			}
		}
		if (pChild && pContext.feed.parentLinkField != null && pContext.writableFields.contains(pContext.feed.parentLinkField))
		{
			out.add(pContext.feed.parentLinkField);
		}
		for (final DerivedFieldRule.Field derived : pContext.derivedFields)
		{
			if (DerivedFieldRule.producesKey(derived))
			{
				out.add(derived.keyFieldName);
			}
		}
		return new ArrayList<String>(out);
	}

	/**
	 * Lot V3-S2: the staging tables a root feed's whole tree reads during a scope {@code all} load - its own entity, every descendant feed's entity, and the
	 * target entity of every immediate foreign key (the parent-row check) - so that their indexes exist before its first landing row and live until the last
	 * root feed that needs them.
	 */
	private static void collectNeeds(final FeedContext pContext, final Map<String, AdaptationTable> pStagingTables, final Set<String> pOut)
	{
		pOut.add(pContext.entityName);
		for (final TransferPlan.FieldPlan field : pContext.feed.fields)
		{
			if (field.foreignKeyField != null && !field.foreignKeyDeferred && field.targetEntityName != null && pStagingTables.containsKey(field.targetEntityName))
			{
				pOut.add(field.targetEntityName);
			}
		}
		for (final FeedContext child : pContext.children)
		{
			StagingLoader.collectNeeds(child, pStagingTables, pOut);
		}
	}

	/**
	 * Lot V3-S2: the indexes of a scope {@code all} load, declared before any row is read: every feed's layout, and for each root feed (in load order) the
	 * tables its tree needs. The feeds are prepared here a first time, throw-away (an empty counters map: nothing of it reaches the load's counters), only to
	 * learn their layouts - an entity's indexes must know ALL its layouts before the first scan, and a feed of a later entity is not prepared yet. A failure
	 * here costs the indexes, never the load: the result is then null, and the load runs as lot V3-S did.
	 */
	private static StagingLoadReads newReads(final TransferPlan.Result pPlan, final Map<String, AdaptationTable> pStagingTables,
		final Map<String, StagingModel.EntityInfo> pEntityInfoByName, final Map<String, Map<String, String>> pForeignKeyFieldByAttributeByEntity,
		final Map<String, List<DerivedFieldRule.Field>> pDerivedFieldsByEntity)
	{
		try
		{
			final StagingLoadReads reads = new StagingLoadReads(ScanBudget.currentStaging());
			int sequence = 0;
			for (final TransferPlan.Entity entity : pPlan.loadOrder)
			{
				for (final TransferPlan.Feed rootFeed : entity.feeds)
				{
					final FeedContext probe = StagingLoader.prepareFeed(rootFeed, pStagingTables, pEntityInfoByName, pForeignKeyFieldByAttributeByEntity,
						pDerivedFieldsByEntity, new HashMap<String, Counters>(), reads, false);
					final Set<String> needs = new LinkedHashSet<String>();
					StagingLoader.collectNeeds(probe, pStagingTables, needs);
					reads.need(rootFeed, sequence++, needs);
				}
			}
			return reads;
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[mdm-factory] staging load: the read indexes could not be declared, using per-row lookups instead: " + ex);
			return null;
		}
	}

	/**
	 * Lot V3-S2: does the staging row at {@code pKey} of this feed's own table exist - the empty-child check. Answered from the table's index when the load
	 * has one (a key present when the index was built, or written since), by the lookup of lot V3-S otherwise; the entity's counters say which.
	 */
	private static boolean stagingRowExists(final FeedContext pContext, final PrimaryKey pKey)
	{
		if (pContext.reads != null)
		{
			final StagingLoadReads.Entity own = pContext.reads.entity(pContext.entityName);
			final StagingLoadReads.Entity index = pContext.reads.scanning(pContext.entityName);
			if (index != null)
			{
				own.emptyChildChecksByIndex++;
				return index.contains(pKey.format());
			}
			own.emptyChildChecksByLookup++;
		}
		return pContext.stagingTable.lookupAdaptationByPrimaryKey(pKey) != null;
	}

	/** Lot V3-S2: does the parent row exist in the target entity's staging table - the foreign-key check of a child feed; same two ways as {@link #stagingRowExists}. */
	private static boolean parentRowExists(final FeedContext pContext, final String pTargetEntity, final AdaptationTable pParentTable, final PrimaryKey pParentKey)
	{
		if (pContext.reads != null)
		{
			final StagingLoadReads.Entity own = pContext.reads.entity(pContext.entityName);
			final StagingLoadReads.Entity index = pContext.reads.scanning(pTargetEntity);
			if (index != null)
			{
				own.parentChecksByIndex++;
				return index.contains(pParentKey.format());
			}
			own.parentChecksByLookup++;
		}
		return pParentTable.lookupAdaptationByPrimaryKey(pParentKey) != null;
	}

	/**
	 * Fix/staging-proposal-quality (2026-10-17, item 1): for every loaded entity, the computed fields (folded / validity keys of
	 * {@code pDerivedFieldsByEntity}, every script derivation of {@code pScriptEntries}) that hold NO value on any row of the staging table, as
	 * {@link ComputedFieldFill#report} sentences by entity name. Two reads per field: the row count of the table (once per entity) and whether a
	 * single row carries a value (the request stops at the first one - only the empty case scans the table). A field not in the published model is
	 * left to the "missing fields" report.
	 */
	private static Map<String, List<String>> collectEmptyComputed(final List<TransferPlan.Entity> pLoadOrder, final Map<String, AdaptationTable> pStagingTables,
		final Map<String, List<DerivedFieldRule.Field>> pDerivedFieldsByEntity, final List<ScriptDerivationGate.Entry> pScriptEntries)
	{
		final Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
		for (final TransferPlan.Entity entity : pLoadOrder)
		{
			final AdaptationTable table = pStagingTables.get(entity.name);
			if (table == null)
			{
				continue;
			}
			// attribute -> source attribute name (null when unknown), and whether it is a script derivation that is not applied
			final Map<String, String> computed = new LinkedHashMap<String, String>();
			final Set<String> scriptNotApplied = new HashSet<String>();
			final List<DerivedFieldRule.Field> derived = pDerivedFieldsByEntity.get(entity.name);
			if (derived != null)
			{
				for (final DerivedFieldRule.Field field : derived)
				{
					computed.put(field.keyFieldName, field.sourceFieldName);
				}
			}
			for (final ScriptDerivationGate.Entry entry : pScriptEntries)
			{
				if (entity.name.equals(entry.entityName))
				{
					computed.put(entry.attributeName, entry.sourceName);
					if (!entry.applied)
					{
						scriptNotApplied.add(entry.attributeName);
					}
				}
			}
			if (computed.isEmpty())
			{
				continue;
			}
			long rows = -1L;
			for (final Map.Entry<String, String> field : computed.entrySet())
			{
				if (table.getTableOccurrenceRootNode().getNode(Path.parse("./" + field.getKey())) == null)
				{
					continue;
				}
				if (rows < 0L)
				{
					final RequestResult all = table.createRequestResult(null);
					try
					{
						rows = all.getSize();
					}
					finally
					{
						all.close();
					}
				}
				if (rows == 0L)
				{
					break;
				}
				boolean filled;
				final RequestResult withValue = table.createRequestResult("osd:is-not-null(./" + field.getKey() + ")");
				try
				{
					filled = withValue.nextAdaptation() != null;
				}
				finally
				{
					withValue.close();
				}
				if (ComputedFieldFill.emptyEverywhere(rows, filled ? 1L : 0L))
				{
					List<String> notes = out.get(entity.name);
					if (notes == null)
					{
						notes = new ArrayList<String>();
						out.put(entity.name, notes);
					}
					notes.add(ComputedFieldFill.report(entity.name, field.getKey(), field.getValue(), rows, scriptNotApplied.contains(field.getKey())));
				}
			}
		}
		return out;
	}

	/**
	 * Lot M6: appends, for {@code pContext} and every descendant feed, one line per mapped attribute that {@link
	 * FieldFillCheck#report} flags (entity prefixed) to {@code pNotes} and a JSON object to {@code pDetail}.
	 */
	private static void collectUnfilled(final FeedContext pContext, final List<String> pNotes, final ArrayNode pDetail)
	{
		for (final FieldFillCheck.Counter counter : pContext.fillCounters.values())
		{
			final String line = FieldFillCheck.report(counter, pContext.feed.landingTableName);
			if (line == null)
			{
				continue;
			}
			pNotes.add(pContext.entityName + "." + line);
			final ObjectNode item = pDetail.addObject();
			item.put("entity", pContext.entityName);
			item.put("feed", pContext.feed.feedId);
			item.put("attribute", counter.attribute);
			item.put("sourceColumn", pContext.feed.landingTableName + "." + counter.sourceColumn);
			item.put("rows", counter.rows);
			item.put("sourceFilled", counter.sourceFilled);
			item.put("filled", counter.filled);
			item.put("message", pContext.entityName + "." + line);
		}
		for (final FeedContext child : pContext.children)
		{
			StagingLoader.collectUnfilled(child, pNotes, pDetail);
		}
	}

	// ============================================================================================ landing read

	/** One entity's worth of pending writes - see {@link #flushTree}. Mirrors the shape of a {@link FeedContext} tree, one-to-one. */
	static final class FeedBuffers
	{
		final List<RowPlan> stage = new ArrayList<RowPlan>();
		final List<RejectPlan> reject = new ArrayList<RejectPlan>();
		final List<RowPlan> unchanged = new ArrayList<RowPlan>();
		/** {@link EmptyChildRule#isEmpty} said yes, and no staging row exists yet at that key: nothing written, nothing rejected - see {@code processRow}'s own Javadoc. */
		long emptyChildrenSkipped;
		/** Staging load rejects (2026-10-17): rows a CHILD feed was asked to build from its parent's rows in this batch - folded into the entity's {@code read} counter by {@code flushOne} (a root feed's own {@code read} is counted per landing row, so this stays 0 there). */
		long read;
		/** {@link EmptyChildRule#isEmpty} said yes, but a staging row ALREADY exists at that key (written by a run before this fix, or by a source that used to carry this child and no longer does): left in place, never deleted here - see {@code processRow}'s own Javadoc. */
		long emptyChildrenStaleFound;
		/**
		 * Lot V3-S: the staging keys planned in this batch of a scope {@code all} load. A key met a second time in the same batch (two landing rows that
		 * compute the same staging key) is never compared against the row read before the batch: the first one may be about to create or change it, so the
		 * second is written, and finds the row inside its Procedure as it always did.
		 */
		final Set<String> plannedKeys = new HashSet<String>();
		final List<FeedBuffers> children;

		FeedBuffers(final int pChildCount)
		{
			this.children = new ArrayList<FeedBuffers>(pChildCount);
			for (int i = 0; i < pChildCount; i++)
			{
				this.children.add(null); // filled in as children are built, same size/order as FeedContext#children
			}
		}

		int size()
		{
			return this.stage.size() + this.reject.size() + this.unchanged.size();
		}
	}

	private static FeedBuffers newBuffers(final FeedContext pContext)
	{
		final FeedBuffers buffers = new FeedBuffers(pContext.children.size());
		for (int i = 0; i < pContext.children.size(); i++)
		{
			buffers.children.set(i, StagingLoader.newBuffers(pContext.children.get(i)));
		}
		return buffers;
	}

	/**
	 * One ROOT feed's landing table, row by row: builds each row's JSON document, computes and buffers its own
	 * feed (and, recursively, every child feed's rows off the SAME document), and flushes every entity touched
	 * by this feed's tree together, bounded by the ROOT's own row count reaching {@code pBatchSize} - a
	 * deliberate approximation (a child entity's own batch is not independently bounded) kept simple on
	 * purpose; see this lot's own final report.
	 * <p>
	 * <b>Landing status write-back, per batch when this table has a single reader</b> (volume lot 3, 2026-09-28,
	 * task 1 - replacing the pre-lot whole-run accumulation): {@code pLandingTableReaderCounts} (computed once
	 * for the whole run, {@link LandingTableFanout}) says whether this root feed is the ONLY one reading its own
	 * landing table this run. When it is (the common case), each batch's own decisions are written back
	 * DIRECTLY, right after that batch's {@link #flushTree} - bounded by {@code pBatchSize}, never accumulated
	 * for the whole run. When it is not (a landing table shared by two-or-more root feeds - a canonical entity
	 * and one of its members both reading it, e.g. {@code Company} and {@code Supplier} on Companies'
	 * {@code SUPPLIER} table), the ORIGINAL whole-run {@code pPendingLandingStatus} accumulation is used instead
	 * - see that class' own Javadoc for the bug this still avoids (an earlier feed's per-row write starving a
	 * later feed's own {@code _status='NEW'} read) and for why this remaining case is a documented, bounded
	 * residual (its memory cost is proportional to the SHARED table's own row count only, not the whole run's).
	 * <p>
	 * <b>Cancellation</b> (task 8): {@code pHandle} (nullable) is checked once per batch, right after that
	 * batch's flush/write-back - a clean stopping point, since nothing partially written or half-decided is ever
	 * left behind (the SAME per-batch boundary {@link BatchCircuitBreaker} already commits at). Returns
	 * {@code false} the moment a stop is detected (the remaining landing rows of this feed - and of the whole
	 * run, since the caller stops entering further feeds/entities too - are simply picked up by the next run,
	 * the ordinary incremental/idempotent posture this class already relies on everywhere else); {@code true} on
	 * a normal, complete pass over every row this call was asked to read.
	 */
	private static boolean loadRootFeed(final Session pSession, final Adaptation pFactory, final String pProjectId, final Map<String, String> pEntityIdByName,
		final FeedContext pRootContext, final Adaptation pStagingDataset, final Map<String, AdaptationTable> pStagingTables, final int pBatchSize,
		final CleaningSteps.Lookups pLookups, final TransformationContext pCtx, final Map<String, RuleRunCounters> pRuleCounters,
		final Map<String, Counters> pCountersByEntity, final List<String> pFirstRejectReasons, final boolean pAllRows,
		final PendingLandingStatus pPendingLandingStatus, final com.on.ebx.factory.staging.StagingLoadListener pListener,
		final Map<String, Integer> pLandingTableReaderCounts, final FactoryJobs.Handle pHandle, final String pStepId, final StagingLoadReads pReads)
		throws OperationException
	{
		final TransferPlan.Feed rootFeed = pRootContext.feed;
		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome landingHome = repository.lookupHome(HomeKey.forBranchName(rootFeed.landingDataspace));
		final Adaptation landingDataset = landingHome == null ? null : landingHome.findAdaptationOrNull(AdaptationName.forName(rootFeed.landingDataset));
		if (landingDataset == null)
		{
			throw EngineRefusal.coded("missing.landingDataset", "Landing dataset " + rootFeed.landingDataspace + "/" + rootFeed.landingDataset + " (feeding " + rootFeed.entityName + ") could not be found.", rootFeed.landingDataspace + "/" + rootFeed.landingDataset);
		}
		final AdaptationTable landingTable = landingDataset.getTable(Path.parse("/root/" + rootFeed.landingTableName));
		final boolean sharedLandingTable = LandingTableFanout.isShared(pLandingTableReaderCounts,
			LandingTableFanout.key(rootFeed.landingDataspace, rootFeed.landingDataset, rootFeed.landingTableName));
		// lot V3-S (2026-10-04): in scope all most landing rows are already at the status this run would give them (STAGED): writing it again is a
		// modification EBX has to index for nothing. A table read by ONE root feed records only the rows whose status changes; a SHARED table still
		// records every decision (the merge "REJECTED wins" needs both feeds' answers) and drops the up-to-date rows when it writes back
		// (writeLandingStatus with pSkipUpToDate). Scope new: every row read is at NEW, so nothing would ever be skipped - the path is left alone.
		final boolean skipUpToDateLandingStatus = pAllRows && !sharedLandingTable;
		// volume lot 3 (task 7): every field's Path/SchemaNode resolved ONCE for this landing table, never
		// re-parsed/re-walked per row - see buildRowDocument's own Javadoc.
		final List<FieldRef> landingFieldRefs = StagingLoader.fieldRefsOf(landingTable.getTableOccurrenceRootNode());

		// lot V3-S2 (scope all): the index of every staging table this feed's tree reads is built NOW, before its first landing row (a stop request during
		// the build stops the load, like one between batches)
		if (pReads != null && !pReads.prepare(rootFeed, pStagingTables, pListener, pHandle))
		{
			return false;
		}

		FeedBuffers buffers = StagingLoader.newBuffers(pRootContext);
		final Map<String, String> landingStatus = new LinkedHashMap<String, String>();
		// volume-guards lot (2026-09-28, task item 6): one breaker per root feed's own flush loop, shared by
		// every entity of its tree (root and, recursively, every child) - see BatchCircuitBreaker's own Javadoc.
		final BatchCircuitBreaker breaker = new BatchCircuitBreaker();

		final RequestResult rows = landingTable.createRequestResult(IncrementalTransfer.landingPredicate(pAllRows));
		final FeedTracker tracker = new FeedTracker(pRootContext, pCountersByEntity, pListener);
		tracker.start(rows.getSize());
		boolean ok = false;
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				pCountersByEntity.get(rootFeed.entityName).read++;
				final ObjectNode document = StagingLoader.buildRowDocument(row, landingFieldRefs);
				final String landingRowKey = row.getOccurrencePrimaryKey().format();
				final String sourceSystem = row.getString(StagingLoader.LANDING_SOURCE_SYSTEM);
				final String defaultSourceId = row.getString(StagingLoader.LANDING_SOURCE_ID);
				final String loadId = row.getString(StagingLoader.LANDING_LOAD_ID);
				final Object rawReceivedAt = row.get(StagingLoader.LANDING_RECEIVED_AT);
				final Date receivedAt = rawReceivedAt instanceof Date ? (Date) rawReceivedAt : null;
				final String incomingHash = row.getString(StagingLoader.LANDING_ROW_HASH);

				if (sourceSystem == null || sourceSystem.trim().isEmpty() || defaultSourceId == null || defaultSourceId.trim().isEmpty())
				{
					buffers.reject.add(new RejectPlan(landingRowKey, sourceSystem, defaultSourceId, loadId, RejectReason.noKey()));
					StagingLoader.decideLandingStatus(landingStatus, landingRowKey, "REJECTED", row, skipUpToDateLandingStatus);
				}
				else
				{
					String rootSourceId = defaultSourceId;
					if (rootFeed.sourceIdPipeline != null && !rootFeed.sourceIdPipeline.trim().isEmpty())
					{
						final String computed = StagingLoader.evaluatePipeline(rootFeed.sourceIdPipeline, document, pCtx);
						rootSourceId = computed == null ? "" : computed;
					}
					final boolean staged = StagingLoader.processRow(pRootContext, buffers, document, sourceSystem, rootSourceId, loadId, receivedAt, landingRowKey,
						incomingHash, null, pLookups, pCtx, pRuleCounters, pAllRows, pStagingTables, null);
					StagingLoader.decideLandingStatus(landingStatus, landingRowKey, staged ? "STAGED" : "REJECTED", row, skipUpToDateLandingStatus);
				}

				if (buffers.size() >= pBatchSize)
				{
					StagingLoader.markRefusedLandingRows(landingStatus, StagingLoader.flushTree(pSession, pFactory, pProjectId, pEntityIdByName, pRootContext, buffers,
						pStagingDataset, pCountersByEntity, pFirstRejectReasons, breaker, pStepId, pAllRows));
					if (sharedLandingTable)
					{
						pPendingLandingStatus.merge(rootFeed, landingDataset, rootFeed.landingTableName, landingStatus);
					}
					else
					{
						// volume lot 3 (task 1): the common case - no other feed this run reads this table, so this
						// batch's decisions are final and written back right away, bounded by pBatchSize.
						// lot V3-S: a scope all batch whose rows were all up to date has nothing to write (no Procedure opened)
						if (!landingStatus.isEmpty())
						{
							StagingLoader.writeLandingStatus(pSession, landingDataset, rootFeed.landingTableName, landingStatus, false, pPendingLandingStatus.endWrites);
						}
					}
					landingStatus.clear();
					buffers = StagingLoader.newBuffers(pRootContext);
					tracker.report();
					if (pHandle != null && pHandle.cancelled())
					{
						ok = true; // a clean stop, not a failure - see this method's own Javadoc
						return false; // not completed: the caller stops entering further feeds/entities too
					}
				}
			}
			if (buffers.size() > 0)
			{
				StagingLoader.markRefusedLandingRows(landingStatus, StagingLoader.flushTree(pSession, pFactory, pProjectId, pEntityIdByName, pRootContext, buffers,
					pStagingDataset, pCountersByEntity, pFirstRejectReasons, breaker, pStepId, pAllRows));
			}
			if (!landingStatus.isEmpty())
			{
				if (sharedLandingTable)
				{
					pPendingLandingStatus.merge(rootFeed, landingDataset, rootFeed.landingTableName, landingStatus);
				}
				else
				{
					StagingLoader.writeLandingStatus(pSession, landingDataset, rootFeed.landingTableName, landingStatus, false, pPendingLandingStatus.endWrites);
				}
			}
			tracker.report();
			ok = true;
			return true;
		}
		finally
		{
			rows.close();
			tracker.end(ok);
		}
	}

	/** A root feed and, recursively, its child feeds: one planned cell each, all under the root's application. */
	private static void planTree(final TransferPlan.Feed pFeed, final String pSource, final com.on.ebx.factory.staging.StagingLoadListener pListener)
	{
		pListener.planned(pFeed.entityName, pSource);
		for (final TransferPlan.Feed child : pFeed.children)
		{
			StagingLoader.planTree(child, pSource, pListener);
		}
	}

	/**
	 * Staging-live lot (2026-09-27): turns the per-ENTITY run counters ({@link Counters}, shared by every feed of
	 * the entity) into per-CELL counters for the listener - it snapshots the counters of every entity of the feed
	 * tree when the root feed starts, and reports the difference after each batch. The root cell knows its total
	 * (the landing rows it reads); a child entity's cell does not (-1).
	 */
	private static final class FeedTracker
	{
		private final com.on.ebx.factory.staging.StagingLoadListener listener;
		private final String source;
		private final String rootEntity;
		private final Map<String, Counters> countersByEntity;
		private final Map<String, long[]> baseline = new LinkedHashMap<String, long[]>();

		FeedTracker(final FeedContext pRoot, final Map<String, Counters> pCountersByEntity, final com.on.ebx.factory.staging.StagingLoadListener pListener)
		{
			this.listener = pListener;
			this.source = pRoot.feed.sourceSystem;
			this.rootEntity = pRoot.entityName;
			this.countersByEntity = pCountersByEntity;
			this.collect(pRoot);
		}

		private void collect(final FeedContext pContext)
		{
			if (!this.baseline.containsKey(pContext.entityName))
			{
				final Counters counters = this.countersByEntity.get(pContext.entityName);
				this.baseline.put(pContext.entityName, counters == null ? new long[4] : new long[] { counters.read, counters.written, counters.rejected, counters.unchanged });
			}
			for (final FeedContext child : pContext.children)
			{
				this.collect(child);
			}
		}

		void start(final long pRootTotal)
		{
			for (final String entity : this.baseline.keySet())
			{
				this.listener.started(entity, this.source, entity.equals(this.rootEntity) ? pRootTotal : -1L);
			}
		}

		void report()
		{
			for (final Map.Entry<String, long[]> entry : this.baseline.entrySet())
			{
				final Counters counters = this.countersByEntity.get(entry.getKey());
				if (counters == null)
				{
					continue;
				}
				final long[] base = entry.getValue();
				// lot V3-S: unchanged rows are told too - a scope all load of unchanged data writes nothing, and a cell reading "0 written" alone would look idle
				this.listener.progress(entry.getKey(), this.source, counters.read - base[0], counters.written - base[1], counters.unchanged - base[3],
					counters.rejected - base[2]);
			}
		}

		void end(final boolean pOk)
		{
			for (final String entity : this.baseline.keySet())
			{
				this.listener.ended(entity, this.source, pOk);
			}
		}
	}

	// ============================================================================== deferred landing status write-back

	/**
	 * Accumulates every ROOT feed's landing-status decision across the WHOLE run ({@link #load}, every
	 * entity, every root feed), merged per landing table ({@code
	 * transfer.IncrementalTransfer#mergeLandingStatusMaps}), and written back only ONCE, at the very end of a
	 * successful run ({@link #flushPendingLandingStatus}) - see {@link #load}'s and {@link #loadRootFeed}'s own
	 * Javadoc for the bug this fixes and the crash-safety argument. Ported from {@code main} (commit
	 * {@code 9358a45}, "landing status write-back must not happen before every feed of every entity has run")
	 * into this lot's feed-tree rewrite: keyed exactly the same way ({@code
	 * landingDataspace|landingDataset|landingTableName}, plain strings already on {@link TransferPlan.Feed} -
	 * never {@code Adaptation}/{@code AdaptationHome} identity, CLAUDE.md sec 9), but fed only by a ROOT feed's
	 * own {@link #loadRootFeed} - a child feed never writes a landing status of its own (see the class
	 * Javadoc's "Landing status is written for the ROOT feed's own row only, once").
	 */
	private static final class PendingLandingStatus
	{
		/** F2-06: where a refused write-back is recorded (shared with {@link #load}). */
		final StagingEndWrites endWrites;

		PendingLandingStatus(final StagingEndWrites pEndWrites)
		{
			this.endWrites = pEndWrites;
		}

		private final Map<String, Adaptation> datasetByKey = new LinkedHashMap<String, Adaptation>();
		private final Map<String, String> tableNameByKey = new LinkedHashMap<String, String>();
		private final Map<String, Map<String, String>> statusByRowKeyByTable = new LinkedHashMap<String, Map<String, String>>();

		/** Merges {@code pIncoming} (one root feed's, one batch's decisions) into this table's running total, via {@code IncrementalTransfer#mergeLandingStatusMaps}/{@code #mergeLandingStatus} for any row two different feeds both touched this run. */
		void merge(final TransferPlan.Feed pFeed, final Adaptation pLandingDataset, final String pLandingTableName, final Map<String, String> pIncoming)
		{
			if (pIncoming.isEmpty())
			{
				return;
			}
			final String key = pFeed.landingDataspace + "|" + pFeed.landingDataset + "|" + pLandingTableName;
			Map<String, String> existing = this.statusByRowKeyByTable.get(key);
			if (existing == null)
			{
				existing = new LinkedHashMap<String, String>();
				this.statusByRowKeyByTable.put(key, existing);
				this.datasetByKey.put(key, pLandingDataset);
				this.tableNameByKey.put(key, pLandingTableName);
			}
			IncrementalTransfer.mergeLandingStatusMaps(existing, pIncoming);
		}

		Set<String> tableKeys()
		{
			return this.statusByRowKeyByTable.keySet();
		}

		Adaptation datasetOf(final String pKey)
		{
			return this.datasetByKey.get(pKey);
		}

		String tableNameOf(final String pKey)
		{
			return this.tableNameByKey.get(pKey);
		}

		Map<String, String> statusOf(final String pKey)
		{
			return this.statusByRowKeyByTable.get(pKey);
		}
	}

	/**
	 * Writes every landing table's accumulated status decisions back, ONE landing table at a time, in
	 * batches of at most {@code pBatchSize} rows each (never one giant {@code Procedure}) - {@link
	 * #writeLandingStatus} itself already writes a whole map in one {@code Procedure}, so this only ever
	 * hands it a bounded chunk.
	 */
	private static void flushPendingLandingStatus(final Session pSession, final PendingLandingStatus pPending, final int pBatchSize, final boolean pSkipUpToDate)
	{
		for (final String key : pPending.tableKeys())
		{
			final Adaptation landingDataset = pPending.datasetOf(key);
			final String landingTableName = pPending.tableNameOf(key);
			final List<Map.Entry<String, String>> entries = new ArrayList<Map.Entry<String, String>>(pPending.statusOf(key).entrySet());
			for (int i = 0; i < entries.size(); i += pBatchSize)
			{
				final Map<String, String> chunk = new LinkedHashMap<String, String>();
				for (int j = i; j < Math.min(i + pBatchSize, entries.size()); j++)
				{
					chunk.put(entries.get(j).getKey(), entries.get(j).getValue());
				}
				StagingLoader.writeLandingStatus(pSession, landingDataset, landingTableName, chunk, pSkipUpToDate, pPending.endWrites);
			}
		}
	}

	/** One field of a landing table's schema, resolved ONCE per table (never per row) - see {@link #buildRowDocument}'s own Javadoc, volume lot 3 task 7. */
	static final class FieldRef
	{
		final String name;
		final Path path;
		final SchemaNode node;

		FieldRef(final String pName, final Path pPath, final SchemaNode pNode)
		{
			this.name = pName;
			this.path = pPath;
			this.node = pNode;
		}
	}

	/**
	 * Volume lot 3 (2026-09-28, task 7): every terminal-value field of {@code pRootNode} (a landing table's own
	 * occurrence root, or any row's {@code getSchemaNode()} - identical either way, schema nodes are structural,
	 * shared by every row of the same table), resolved ONCE (name, {@code Path.parse("./" + name)}, and the
	 * {@link SchemaNode} itself for {@link SchemaNode#formatToXsString}) - {@link #buildRowDocument} used to
	 * re-walk this same schema and re-parse the same {@code Path} for EVERY field of EVERY row.
	 */
	static List<FieldRef> fieldRefsOf(final SchemaNode pRootNode)
	{
		final List<FieldRef> out = new ArrayList<FieldRef>();
		for (final SchemaNode child : pRootNode.getNodeChildren())
		{
			if (!child.isTerminalValue())
			{
				continue; // landing tables are flat by construction; defensive, never assumed
			}
			final String name = child.getPathInSchema().getLastStep().format();
			out.add(new FieldRef(name, Path.parse("./" + name), child));
		}
		return out;
	}

	/**
	 * Every field of {@code pRow}'s own landing table, as its XML-schema string, keyed by field name - see the
	 * class Javadoc's "One landing row -&gt; one JSON document". {@code pFields} is {@link #fieldRefsOf} applied
	 * to this row's own table, resolved ONCE per landing table by the caller (the hot loop, {@link
	 * #loadRootFeed}) and reused for every row - never re-walking the schema or re-parsing a {@code Path} per
	 * row per field any more (volume lot 3, task 7).
	 */
	static ObjectNode buildRowDocument(final Adaptation pRow, final List<FieldRef> pFields)
	{
		final ObjectNode document = StagingLoader.MAPPER.createObjectNode();
		for (final FieldRef field : pFields)
		{
			final Object value = pRow.get(field.path);
			final String xs = value == null ? null : field.node.formatToXsString(value);
			if (xs == null)
			{
				document.putNull(field.name);
			}
			else
			{
				document.put(field.name, xs);
			}
		}
		return document;
	}

	/**
	 * One-row convenience overload for a caller that only needs a HANDFUL of rows (never the hot loop) -
	 * {@code ebx.MappingMatrixReader}'s own bounded "before -&gt; after" sample (coordinator review,
	 * 2026-09-23). Re-walks the row's own schema on every call, exactly like before volume lot 3 - fine at that
	 * caller's scale; {@link #loadRootFeed} precomputes {@link #fieldRefsOf} once and calls the two-argument
	 * overload directly instead.
	 */
	static ObjectNode buildRowDocument(final Adaptation pRow)
	{
		return StagingLoader.buildRowDocument(pRow, StagingLoader.fieldRefsOf(pRow.getSchemaNode()));
	}

	/**
	 * One row of one feed (root or child): cleans, computes every field with ONE {@code MappingEngine.compute}
	 * call, checks constraints/quality/foreign-keys, then either buffers a reject or plans the write and
	 * recurses into {@link TransferPlan.Feed#children} - see the class Javadoc. Never touches EBX beyond
	 * reading the parent staging tables map (for an immediate foreign key) and the reference lookups.
	 * <p>
	 * <b>Empty-child bug fix, 2026-09-25</b> (volume test project {@code PersVol2}): a CHILD feed with {@code
	 * collectionPath} empty (sec 3.3 step 6) runs once per parent row whether or not the source actually
	 * carries that child - two {@code Adresse} child feeds (MAILING, OTHER), each with only {@code addressType
	 * &lt;- constant(...)} telling them apart, on a contact with no OTHER address still produced an {@code
	 * Adresse} row carrying nothing but that constant, the technical identity fields and the structural link
	 * to the parent (737 such rows on that run). Once every reject check above has passed, a CHILD row
	 * ({@code pParentKey != null}) whose {@link EmptyChildRule#isEmpty} says yes is neither written nor
	 * rejected: it is silently uninteresting, not an error. A row ALREADY staged at that key - written by a
	 * run before this fix, or by a source version that used to carry this child and no longer does - is
	 * deliberately left exactly as it is (deleting live staging data on a guess is not this method's call to
	 * make); it is only counted separately ({@link FeedBuffers#emptyChildrenStaleFound}) so {@code
	 * StagingLoader.load}'s own report surfaces it for a person to look at, instead of the previous silent
	 * "written, 737 more Adresse rows, nobody notices".
	 *
	 * @param pParentKey the immediate parent's own, just-computed staging key ({@code
	 *            computePrimaryKey(...).format()}), to write into {@link TransferPlan.Feed#parentLinkField};
	 *            {@code null} for a root feed.
	 * @return whether the row was successfully planned (written, unchanged, or a legitimately empty child
	 *         skipped) - {@code false} means rejected. Only meaningful for the caller's own landing-status
	 *         decision at the ROOT level; a child's own outcome never affects it (see the class Javadoc).
	 */
	private static boolean processRow(final FeedContext pContext, final FeedBuffers pBuffers, final ObjectNode pDocument, final String pSourceSystem,
		final String pSourceId, final String pLoadId, final Date pReceivedAt, final String pLandingRowKey, final String pIncomingHash, final String pParentKey,
		final CleaningSteps.Lookups pLookups, final TransformationContext pCtx, final Map<String, RuleRunCounters> pRuleCounters, final boolean pAllRows,
		final Map<String, AdaptationTable> pStagingTables, final RejectReason pParentRejected)
	{
		final List<RejectReason> reasons = new ArrayList<RejectReason>();
		final Map<String, Object> fields = new LinkedHashMap<String, Object>(pContext.nullDefaults);
		try
		{
			// task D: an ACCEPTED-or-APPLIED MappingStep pipeline runs on the raw landing text BEFORE this
			// field's own (Connectors) pipeline - applied into a COPY of the document, never the original, so a
			// concat(...) elsewhere in the SAME feed also reads the cleaned value.
			// volume lot 3 (2026-09-28, task 7): a feed with NO cleaning step at all (most child feeds, and any
			// root feed nobody ever proposed a cleaning pipeline for) never needs this copy in the first place -
			// FeedContext#hasCleaningSteps is computed once per feed, not per row, so this is a cheap per-row
			// check that skips the whole copy+loop below.
			// lot M6: the copy carries one alias column per field that cleans; the shared source columns stay raw
			final ObjectNode cleanedDocument = pContext.hasCleaningSteps
				? FeedRules.cleanedDocument(pDocument, pContext.feed.fields, pContext.writableFields, pLookups)
				: pDocument;

			// target-mapping lot 1: ONE MappingEngine call for the whole feed - see the class Javadoc
			final MappingEngine.Values values = MappingEngine.compute(pContext.definition, cleanedDocument, pCtx);
			for (final String error : values.getErrors())
			{
				reasons.add(RejectReason.compute(error));
			}

			final String rowKeyForQuality = pContext.stagingTable.computePrimaryKey(new Object[] { pSourceSystem, pSourceId }).format();
			for (final TransferPlan.FieldPlan field : pContext.feed.fields)
			{
				if (!pContext.writableFields.contains(field.stagingField))
				{
					continue;
				}
				final String xs = values.get("./" + field.stagingField);
				final RejectReason constraintReject = StagingLoader.constraintReason(field, xs);
				if (constraintReject != null)
				{
					reasons.add(constraintReject);
				}
				fields.put(field.stagingField, xs);
				final FieldFillCheck.Counter fillCounter = pContext.fillCounters.get(field.stagingField);
				if (fillCounter != null)
				{
					fillCounter.count(StagingLoader.text(pDocument.get(field.sourcePath)), xs);
				}

				if (!field.qualityRules.isEmpty() && xs != null && !xs.trim().isEmpty())
				{
					for (final QualityRuleCheck.Rule rule : field.qualityRules)
					{
						// desk lot L4: a rule with a condition is evaluated only on the rows whose condition attribute holds one of its values
						// (the produced staging value of that attribute); outside it the rule is not checked, so the last-load counters count only the rows it covered
						if (rule.hasCondition() && !rule.appliesTo(values.get("./" + rule.conditionField)))
						{
							continue;
						}
						final QualityRuleCheck.Verdict verdict = QualityRuleCheck.verdict(rule, xs, pLookups);
						if (verdict == QualityRuleCheck.Verdict.NOT_VALIDATED)
						{
							QualityRuleProblems.reportOnce(rule); // a refused or timed-out pattern: the row is not validated by this rule, never counted as a pass
							continue;
						}
						final boolean ok = verdict == QualityRuleCheck.Verdict.PASS;
						StagingLoader.recordQualityCheck(pRuleCounters, rule, ok, rowKeyForQuality);
						if (!ok && rule.isRejectSeverity())
						{
							reasons.add(RejectReason.quality(field.stagingField, field.sourcePath, rule.kind, rule.argument));
						}
					}
				}

				// bug fix, 2026-09-23 (project Personnes, first real run - see TransferPlan.FieldPlan#structuralParentLink's
				// own Javadoc): a CHILD row's own link-to-parent field is never resolved by lookup, whatever mapping the
				// language-model pass may have proposed for it - the structural write a few lines below (pParentKey) is
				// the parent's own, just-computed staging key, from the SAME landing row this method is processing right
				// now; the parent itself is still buffered in this batch, not yet written, so a lookup against the
				// staging table would always miss it on a first load ("unknown parent ...", the exact symptom this fixes).
				// Only ever true when pParentKey != null (a child row) AND the field is flagged - a root feed's own
				// mapping of the same attribute (a genuine two-table source, resolved by an ordinary lookup) is untouched.
				final boolean parentLinkWins = pParentKey != null && field.structuralParentLink;
				if (field.foreignKeyField != null && !field.foreignKeyDeferred && !parentLinkWins)
				{
					if (!pContext.writableFields.contains(field.foreignKeyField))
					{
						continue;
					}
					if (xs == null)
					{
						fields.put(field.foreignKeyField, null);
					}
					else
					{
						final AdaptationTable parentTable = pStagingTables.get(field.targetEntityName);
						if (parentTable == null)
						{
							reasons.add(RejectReason.compute("unknown parent " + field.targetEntityName + ": its staging table was not loaded in this transfer run"));
						}
						else
						{
							final PrimaryKey parentKey = parentTable.computePrimaryKey(new Object[] { pSourceSystem, xs });
							// lot V3-S2: answered from the parent entity's index in a scope all load, by the lookup of lot V3-S otherwise
							if (!StagingLoader.parentRowExists(pContext, field.targetEntityName, parentTable, parentKey))
							{
								reasons.add(RejectReason.unknownParent(field.stagingField, field.sourcePath, field.targetEntityName, parentKey.format()));
							}
							else
							{
								fields.put(field.foreignKeyField, parentKey.format());
							}
						}
					}
				}
			}

			if (pParentKey != null)
			{
				if (pContext.feed.parentLinkField != null && pContext.writableFields.contains(pContext.feed.parentLinkField))
				{
					fields.put(pContext.feed.parentLinkField, pParentKey);
				}
			}
		}
		catch (final RuntimeException ex)
		{
			reasons.add(RejectReason.read(ex.getMessage()));
		}
		catch (final Exception ex) // Transformations.apply/MappingEngine.compute's own checked Exception
		{
			reasons.add(RejectReason.compute(ex.getMessage()));
		}

		if (!reasons.isEmpty())
		{
			if (pParentRejected != null)
			{
				reasons.add(0, pParentRejected); // the parent's rejection is the first thing to say: fixing it may be all that is needed
			}
			pBuffers.reject.add(new RejectPlan(pLandingRowKey, pSourceSystem, pSourceId, pLoadId, reasons));
			// staging load rejects (2026-10-17): the rows computed from this one are not written either (no orphan in staging) - they are rejected with the reason of this row
			StagingLoader.computeChildren(pContext, pBuffers, pDocument, pSourceSystem, pSourceId, pLoadId, pReceivedAt, pLandingRowKey, pIncomingHash, pLookups, pCtx,
				pRuleCounters, pAllRows, pStagingTables, pParentRejected != null ? pParentRejected : RejectReason.parentRejected(pContext.entityName, reasons.get(0)));
			return false;
		}

		// empty-child bug fix, 2026-09-25 - see this method's own Javadoc. Checked on the RAW fields (before
		// DerivedFieldRule.apply): a derived default must never mask a genuinely empty child. Root feeds are
		// never checked (pParentKey == null) - an empty root row is exactly as legitimate as today.
		if (pParentKey != null && EmptyChildRule.isEmpty(pContext.feed.fields, fields))
		{
			final PrimaryKey emptyChildKey = pContext.stagingTable.computePrimaryKey(new Object[] { pSourceSystem, pSourceId });
			// lot V3-S2: answered from this table's index in a scope all load, by the lookup of lot V3-S otherwise
			if (StagingLoader.stagingRowExists(pContext, emptyChildKey))
			{
				pBuffers.emptyChildrenStaleFound++;
			}
			else
			{
				pBuffers.emptyChildrenSkipped++;
			}
			return true; // not a reject; no children of a row that was never written - see the class Javadoc
		}

		if (pParentRejected != null)
		{
			// staging load rejects (2026-10-17): this row belongs to a rejected parent - it is NOT written (it would be an orphan: an address of a customer that is not in
			// staging), it is rejected with the parent's reason, and so are the rows computed from it
			pBuffers.reject.add(new RejectPlan(pLandingRowKey, pSourceSystem, pSourceId, pLoadId, pParentRejected));
			StagingLoader.computeChildren(pContext, pBuffers, pDocument, pSourceSystem, pSourceId, pLoadId, pReceivedAt, pLandingRowKey, pIncomingHash, pLookups, pCtx,
				pRuleCounters, pAllRows, pStagingTables, pParentRejected);
			return false;
		}

		final Map<String, Object> fieldsWithDerived = DerivedFieldRule.apply(fields, pContext.derivedFields, pLookups);
		final RowPlan planned = new RowPlan(pSourceSystem, pSourceId, fieldsWithDerived, pLoadId, pReceivedAt, pLandingRowKey, pIncomingHash);
		planned.parentKey = pParentKey;

		boolean unchanged = false;
		if (pAllRows)
		{
			// lot V3-S (2026-10-04): scope all re-applies the whole pipeline to every row but writes only a row whose result differs from what staging
			// holds. The existing row is read ONCE, here, outside any Procedure (nothing is written before flush, so no predicate read is interleaved with
			// a write), compared with the planned values, and handed on in the RowPlan for upsertOne to modify - no second read when it exists. Done AFTER
			// every reject check above and the empty-child check, so a REJECT-severity rule still rejects a row whatever its unchanged status.
			// Lot V3-S2 (2026-10-04): when the table has an index (StagingLoadReads) the row is first classified from it - the planned digest equals the
			// stored one: unchanged, no read at all; the key is not in the table: a new row, nothing to read or compare. Only a different or unknown digest, a
			// key written earlier in this load, and a table without an index take the read below, exactly as in lot V3-S.
			final PrimaryKey stagingKey = pContext.stagingTable.computePrimaryKey(new Object[] { pSourceSystem, pSourceId });
			planned.keyText = stagingKey.format();
			if (pBuffers.plannedKeys.add(planned.keyText))
			{
				boolean readRow = true;
				final StagingLoadReads.Entity index = pContext.reads == null ? null : pContext.reads.scanning(pContext.entityName);
				if (index != null)
				{
					final long[] stored = index.entry(planned.keyText);
					if (stored == null)
					{
						index.newByIndex++;
						readRow = false; // no such row in the table: upsertOne creates it
					}
					else if (index.sameAsPlanned(stored, pContext.layoutIndex, planned.fields, pIncomingHash))
					{
						index.unchangedByIndex++;
						unchanged = true;
						readRow = false;
					}
				}
				if (readRow)
				{
					if (pContext.reads != null)
					{
						pContext.reads.entity(pContext.entityName).stagingRowReads++;
					}
					final Adaptation existingStagingRow = pContext.stagingTable.lookupAdaptationByPrimaryKey(stagingKey);
					planned.existing = existingStagingRow;
					if (existingStagingRow != null)
					{
						try
						{
							unchanged = StagingRowDiff.unchanged(planned.fields, pIncomingHash, pContext.sourceHashFieldExists,
								new StoredStagingRow(pContext, existingStagingRow));
						}
						catch (final RuntimeException ex)
						{
							unchanged = false; // a doubt costs a write, never a lost update (StagingRowDiff)
						}
					}
				}
			}
			if (unchanged)
			{
				planned.existing = null; // nothing will be written: do not hold the record until the end of the batch
			}
		}
		else if (pContext.sourceHashFieldExists)
		{
			final PrimaryKey stagingKey = pContext.stagingTable.computePrimaryKey(new Object[] { pSourceSystem, pSourceId });
			final Adaptation existingStagingRow = pContext.stagingTable.lookupAdaptationByPrimaryKey(stagingKey);
			final String existingHash = existingStagingRow == null ? null : existingStagingRow.getString(StagingLoader.STAGING_SOURCE_HASH);
			unchanged = !IncrementalTransfer.shouldWrite(existingHash, pIncomingHash, pAllRows, pContext.sourceHashFieldExists);
		}
		if (unchanged)
		{
			pBuffers.unchanged.add(planned);
		}
		else
		{
			pBuffers.stage.add(planned);
		}

		// recurse into every child feed of THIS feed, off the SAME document - see the class Javadoc and
		// computeChildren's own Javadoc for the sourceId default rule
		StagingLoader.computeChildren(pContext, pBuffers, pDocument, pSourceSystem, pSourceId, pLoadId, pReceivedAt, pLandingRowKey, pIncomingHash, pLookups, pCtx,
			pRuleCounters, pAllRows, pStagingTables, null);
		return true;
	}

	/**
	 * Recurses into every child feed of {@code pContext}, off {@code pDocument} (the row's own document, BEFORE
	 * this feed's own cleaning - a child's cleaning/mapping runs independently, on its OWN copy). Never called
	 * when the row itself was rejected (see {@link #processRow}).
	 * <p>
	 * <b>The child's default {@code sourceId}</b> (docs/TARGET-MAPPING.md sec 2.1: "{@code &lt;parent
	 * sourceId&gt;#&lt;position of the child among its siblings&gt;}") is read here as TWO independent axes,
	 * both suffixed onto the parent's own {@code sourceId}, because the phrase alone is ambiguous between them
	 * and each axis matters for a different real shape: (1) the child FEED's own 0-based position among {@code
	 * TransferPlan.Feed#children} of the SAME parent - what tells apart two SIBLING feeds of the same entity
	 * fed from the SAME parent row (the person/address demo's "MAIN" and "SHIP" addresses, each its own child
	 * feed, {@code collectionPath} empty); (2) the 0-based position of the element WITHIN one feed's own
	 * collection - what would tell apart several rows produced by a SINGLE feed's non-empty {@code
	 * collectionPath} (not exercised by any real configuration today). Default = {@code
	 * "<parentSourceId>#<feedIndex>"} when {@code collectionPath} is empty (a single element - axis 2 is always
	 * 0 and adds nothing), {@code "<parentSourceId>#<feedIndex>-<elementIndex>"} otherwise. A person or the
	 * language-model pass is still expected to give an EXPLICIT {@code sourceIdPipeline} whenever it reads
	 * better (docs/TARGET-MAPPING.md sec 4.2's own guidance, {@code concat(ID)|suffix(#MAIN)}) - the default
	 * exists so an unconfigured child feed still produces a stable, collision-free key rather than none at all.
	 */
	private static void computeChildren(final FeedContext pContext, final FeedBuffers pBuffers, final ObjectNode pDocument, final String pParentSourceSystem,
		final String pParentSourceId, final String pLoadId, final Date pReceivedAt, final String pLandingRowKey, final String pIncomingHash,
		final CleaningSteps.Lookups pLookups, final TransformationContext pCtx, final Map<String, RuleRunCounters> pRuleCounters, final boolean pAllRows,
		final Map<String, AdaptationTable> pStagingTables, final RejectReason pParentRejected)
	{
		final String parentKey = pContext.stagingTable.computePrimaryKey(new Object[] { pParentSourceSystem, pParentSourceId }).format();
		for (int feedIndex = 0; feedIndex < pContext.feed.children.size(); feedIndex++)
		{
			final TransferPlan.Feed childFeed = pContext.feed.children.get(feedIndex);
			final FeedContext childContext = pContext.children.get(feedIndex);
			final FeedBuffers childBuffers = pBuffers.children.get(feedIndex);
			final boolean flat = childFeed.collectionPath == null || childFeed.collectionPath.isEmpty();

			final List<JsonNode> elements = new ArrayList<JsonNode>();
			if (flat)
			{
				elements.add(pDocument);
			}
			else
			{
				final JsonNode found = Json.at(pDocument, childFeed.collectionPath);
				if (found != null && found.isArray())
				{
					for (final JsonNode element : found)
					{
						elements.add(element);
					}
				}
				else if (found != null && found.isObject())
				{
					elements.add(found);
				}
			}

			int elementIndex = 0;
			for (final JsonNode element : elements)
			{
				final ObjectNode childDocument = element != null && element.isObject() ? ((ObjectNode) element).deepCopy() : StagingLoader.MAPPER.createObjectNode();
				childDocument.set("_parent", pDocument);

				String childSourceId;
				if (childFeed.sourceIdPipeline != null && !childFeed.sourceIdPipeline.trim().isEmpty())
				{
					childSourceId = StagingLoader.evaluatePipeline(childFeed.sourceIdPipeline, childDocument, pCtx);
				}
				else
				{
					// multi-child lot (2026-09-24): a named role (MAILING, OTHER) is a stabler suffix than the feed's
					// position among its siblings, which changes when a sibling is added or rejected
					childSourceId = pParentSourceId + "#" + (childFeed.variant != null ? childFeed.variant : String.valueOf(feedIndex))
						+ (flat ? "" : "-" + elementIndex);
				}
				elementIndex++;
				childBuffers.read++; // staging load rejects (2026-10-17): a child row is "read" from its parent's row - the counter of its entity said "of 0 read"

				if (childSourceId == null || childSourceId.trim().isEmpty())
				{
					childBuffers.reject.add(new RejectPlan(pLandingRowKey, childFeed.sourceSystem, pParentSourceId, pLoadId,
						RejectReason.sourceIdEmpty(childFeed.entityName, childFeed.feedId)));
					continue; // the CHILD only rejects - the parent row is still written, see the class Javadoc
				}

				StagingLoader.processRow(childContext, childBuffers, childDocument, childFeed.sourceSystem, childSourceId, pLoadId, pReceivedAt, pLandingRowKey,
					pIncomingHash, parentKey, pLookups, pCtx, pRuleCounters, pAllRows, pStagingTables, pParentRejected);
			}
		}
	}

	/** {@code pPipeline} applied to {@code pDocument} with no input value (a {@code sourceIdPipeline} reads everything it needs through {@code concat(...)} against the document root) - never throws: a failure becomes an empty result, which the caller already treats as "sourceId is empty" (a reject). */
	private static String evaluatePipeline(final String pPipeline, final ObjectNode pDocument, final TransformationContext pCtx)
	{
		pCtx.setDocumentRoot(pDocument);
		try
		{
			return Transformations.text(Transformations.apply(pPipeline, null, pCtx));
		}
		catch (final Exception ex)
		{
			return null;
		}
	}

	private static String text(final JsonNode pNode)
	{
		return pNode == null || pNode.isNull() || pNode.isMissingNode() ? null : pNode.asText(null);
	}

	// ======================================================================================================= flush

	/**
	 * Commits every entity touched by {@code pContext}'s tree, recursively: staging upsert (with per-row
	 * retry), the {@code TransferReject} rows and the {@code Xref} rows that follow from it - one {@link
	 * Procedure} per entity per call, exactly as {@code flush} always has, just called once per feed in the
	 * tree instead of once for the (only ever one, before this lot) implicit feed.
	 */
	private static Refusals flushTree(final Session pSession, final Adaptation pFactory, final String pProjectId, final Map<String, String> pEntityIdByName,
		final FeedContext pContext, final FeedBuffers pBuffers, final Adaptation pStagingDataset, final Map<String, Counters> pCountersByEntity,
		final List<String> pFirstRejectReasons, final BatchCircuitBreaker pBreaker, final String pStepId, final boolean pAllRows) throws OperationException
	{
		final Refusals refused = StagingLoader.flushOne(pSession, pFactory, pProjectId, pEntityIdByName.get(pContext.entityName), pContext.entityName, pContext.feed,
			pContext.stagingTable, pStagingDataset, pContext.sourceHashFieldExists, pBuffers, pCountersByEntity.get(pContext.entityName), pFirstRejectReasons, pBreaker,
			pStepId, pAllRows, pContext.reads);
		for (int i = 0; i < pContext.children.size(); i++)
		{
			// staging load rejects (2026-10-17): a row EBX refused to save is not in staging, so the rows computed from it (its children, same landing row) must not be
			// written either - they would be orphans. The parent is flushed first, so its refusals are known here, before the children's own flush.
			StagingLoader.rejectOrphans(pContext.entityName, refused, pBuffers.children.get(i));
			StagingLoader.flushTree(pSession, pFactory, pProjectId, pEntityIdByName, pContext.children.get(i), pBuffers.children.get(i), pStagingDataset,
				pCountersByEntity, pFirstRejectReasons, pBreaker, pStepId, pAllRows);
		}
		return refused;
	}

	/**
	 * Staging load rejects (2026-10-17): a landing row whose ROOT row EBX refused to save used to keep the status the planning gave it (STAGED), as if it had been loaded. It
	 * is REJECTED, like a row rejected while it was planned - and, where the table has a single reader, written back with the batch's other decisions.
	 */
	static void markRefusedLandingRows(final Map<String, String> pLandingStatus, final Refusals pRefused)
	{
		for (final String landingRowKey : pRefused.landingRowKeys)
		{
			pLandingStatus.put(landingRowKey, "REJECTED");
		}
	}

	/** What a flush learned about the rows EBX refused to save: by formatted staging key (to find their children) and the landing rows they came from (to mark them REJECTED). */
	static final class Refusals
	{
		static final Refusals NONE = new Refusals();
		final Map<String, RejectReason> byStagingKey = new HashMap<String, RejectReason>();
		final Set<String> landingRowKeys = new LinkedHashSet<String>();
	}

	/**
	 * Staging load rejects (2026-10-17), item "orphan children": the rows of {@code pChildBuffers} that were computed from a parent row EBX refused to save are taken out of the
	 * write list and rejected with "parent rejected: <the parent's reason>" (a parent that is itself a rejected child passes its own root cause on). A child row that is
	 * UNCHANGED (already in staging from an earlier load) is left alone: it was not going to be written and is not an orphan of this run. A parent rejected while it was
	 * PLANNED never gets here: {@code processRow} rejects its children at once.
	 */
	static void rejectOrphans(final String pParentEntityName, final Refusals pRefused, final FeedBuffers pChildBuffers)
	{
		if (pRefused.byStagingKey.isEmpty() || pChildBuffers.stage.isEmpty())
		{
			return;
		}
		final List<RowPlan> kept = new ArrayList<RowPlan>(pChildBuffers.stage.size());
		for (final RowPlan row : pChildBuffers.stage)
		{
			final RejectReason parentReason = row.parentKey == null ? null : pRefused.byStagingKey.get(row.parentKey);
			if (parentReason == null)
			{
				kept.add(row);
				continue;
			}
			final RejectReason reason = RejectReason.PARENT.equals(parentReason.code) ? parentReason : RejectReason.parentRejected(pParentEntityName, parentReason);
			pChildBuffers.reject.add(new RejectPlan(row.landingRowKey, row.sourceSystem, row.sourceId, row.loadId, reason));
		}
		pChildBuffers.stage.clear();
		pChildBuffers.stage.addAll(kept);
	}

	/** The reason an entity's EBX refusal stands for: the conversion failure named by field, column and value when EBX's message says so, else the raw message as detail. */
	static RejectReason refusalReason(final TransferPlan.Feed pFeed, final RowPlan pRow, final String pRaw)
	{
		final List<RejectClassifier.FieldInfo> infos = new ArrayList<RejectClassifier.FieldInfo>(pFeed.fields.size());
		for (final TransferPlan.FieldPlan field : pFeed.fields)
		{
			// EBX refusal classes lot H4 (2026-10-17): the length, the mandatory flag and the target entity let the classifier name the field of a refusal that does not
			infos.add(new RejectClassifier.FieldInfo(field.stagingField, field.sourcePath, field.dataType, field.maxLength, field.mandatory, field.targetEntityName));
		}
		return RejectClassifier.refusal(pRaw, infos, pRow.fields);
	}

	/** {@link PipelineCompat#checkConstraints} as a coded reason: what EBX would BLOCK a save on, found before it is asked (mandatory but empty, longer than the field allows). */
	static RejectReason constraintReason(final TransferPlan.FieldPlan pField, final String pValue)
	{
		if (pValue == null)
		{
			return pField.mandatory ? RejectReason.mandatory(pField.stagingField, pField.sourcePath) : null;
		}
		if (pField.maxLength != null && pValue.length() > pField.maxLength.intValue())
		{
			return RejectReason.tooLong(pField.stagingField, pField.sourcePath, pValue.length(), pField.maxLength.intValue());
		}
		return null;
	}

	private static Refusals flushOne(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityId, final String pEntityName,
		final TransferPlan.Feed pFeed, final AdaptationTable pStagingTable, final Adaptation pStagingDataset, final boolean pSourceHashFieldExists,
		final FeedBuffers pBuffers, final Counters pCounters, final List<String> pFirstRejectReasons, final BatchCircuitBreaker pBreaker, final String pStepId,
		final boolean pAllRows, final StagingLoadReads pReads) throws OperationException
	{
		// empty-child bug fix, 2026-09-25: fold the counts in even when they are the ONLY thing this feed
		// produced this run (every element of a child feed was an empty child, nothing else) - the early
		// return just below must not silently drop them.
		pCounters.emptyChildrenSkipped += pBuffers.emptyChildrenSkipped;
		pCounters.emptyChildrenStaleFound += pBuffers.emptyChildrenStaleFound;
		pCounters.read += pBuffers.read; // staging load rejects (2026-10-17): the rows a child feed built from its parent's rows
		pBuffers.read = 0;
		if (pBuffers.stage.isEmpty() && pBuffers.reject.isEmpty() && pBuffers.unchanged.isEmpty() && pBuffers.emptyChildrenSkipped == 0
			&& pBuffers.emptyChildrenStaleFound == 0)
		{
			return Refusals.NONE;
		}
		final Refusals refusals = new Refusals();
		final List<RejectPlan> allRejects = new ArrayList<RejectPlan>(pBuffers.reject);
		final List<RowPlan> written = new ArrayList<RowPlan>();

		if (!pBuffers.stage.isEmpty())
		{
			final BatchRetry.Outcome<RowPlan> outcome = BatchRetry.commit(pBuffers.stage,
				new StagingAttempt(pSession, pStagingDataset, pStagingTable, pSourceHashFieldExists, pStepId, pEntityName));
			written.addAll(outcome.written);
			for (final BatchRetry.Rejected<RowPlan> rejected : outcome.rejected)
			{
				final RejectReason reason = StagingLoader.refusalReason(pFeed, rejected.item, rejected.reason);
				allRejects.add(new RejectPlan(rejected.item.landingRowKey, rejected.item.sourceSystem, rejected.item.sourceId, rejected.item.loadId, reason));
				refusals.byStagingKey.put(pStagingTable.computePrimaryKey(new Object[] { rejected.item.sourceSystem, rejected.item.sourceId }).format(), reason);
				refusals.landingRowKeys.add(rejected.item.landingRowKey);
			}
			// volume-guards lot (2026-09-28, task item 6): a systematic failure (connectivity, a permission
			// dropped mid-run, a schema mismatch affecting every row identically) must abort the load instead of
			// spending one Procedure per remaining batch, then one per remaining row once bisected, all rejected
			// the same way - see BatchCircuitBreaker's own Javadoc.
			if (pBreaker.report(false, outcome.written.isEmpty()))
			{
				throw EngineRefusal.coded("external.loadAborted", "Staging load aborted for " + pEntityName + ": " + pBreaker.consecutiveFailures()
					+ " consecutive batch(es) failed entirely (every row rejected, even after retrying row by row) - this looks like a systematic failure "
					+ "(connectivity, a permission dropped mid-run, a schema mismatch) rather than a few bad rows. Rows already written earlier in this run "
					+ "are kept; fix the underlying issue (see the reject reasons above) and re-run.", pEntityName);
			}
		}

		if (pReads != null)
		{
			// lot V3-S2: what this batch really committed is in the index from now on - present for the parent and empty-child checks of the next
			// batches, and "unknown" as to its values (a later landing row with the same key is read and compared with what was written)
			final StagingLoadReads.Entity index = pReads.entity(pEntityName);
			for (final RowPlan row : written)
			{
				index.recordWritten(row.keyText);
			}
		}
		pCounters.written += written.size();
		pCounters.unchanged += pBuffers.unchanged.size();
		pCounters.rejected += allRejects.size();
		for (final RejectPlan reject : allRejects)
		{
			final String category = reject.first().category;
			final Long soFar = pCounters.rejectedByCategory.get(category);
			pCounters.rejectedByCategory.put(category, Long.valueOf(soFar == null ? 1L : soFar.longValue() + 1L));
			if (pFirstRejectReasons.size() < StagingLoader.MAX_REPORTED_REJECTS)
			{
				pFirstRejectReasons.add(pEntityName + " " + reject.sourceSystem + "/" + reject.sourceId + ": " + reject.english());
			}
		}

		if (!allRejects.isEmpty())
		{
			StagingLoader.writeRejects(pSession, pFactory, pProjectId, pEntityId, pFeed.landingDataspace, pFeed.landingDataset, pFeed.landingTableName, allRejects,
				new Date(), pStepId, pEntityName);
		}
		if (!written.isEmpty())
		{
			StagingLoader.writeXref(pSession, pFactory, pProjectId, pEntityId, pEntityName, pStagingTable, pFeed.landingDataset, pFeed.landingTableName, written,
				new Date(), pStepId, pAllRows);
		}

		pBuffers.stage.clear();
		pBuffers.reject.clear();
		pBuffers.unchanged.clear();
		return refusals;
	}

	/** {@link BatchRetry.Attempt} plugged with a real EBX write: the whole batch as one {@code Procedure}, then (only on failure) one {@code Procedure} per row. */
	private static final class StagingAttempt implements BatchRetry.Attempt<RowPlan>
	{
		private final Session session;
		private final Adaptation stagingDataset;
		private final AdaptationTable stagingTable;
		private final boolean sourceHashFieldExists;
		/** Volume lot 3 (2026-09-28, task 8, "tracking info"): {@code "mdm-factory:<stepId>:staging:<entity>"}, set on the Procedure via {@code setExecutionInformation} - lets an administrator correlate a long-running load's own batches in EBX's execution monitoring. */
		private final String executionInformation;

		StagingAttempt(final Session pSession, final Adaptation pStagingDataset, final AdaptationTable pStagingTable, final boolean pSourceHashFieldExists,
			final String pStepId, final String pEntityName)
		{
			this.session = pSession;
			this.stagingDataset = pStagingDataset;
			this.stagingTable = pStagingTable;
			this.sourceHashFieldExists = pSourceHashFieldExists;
			this.executionInformation = "mdm-factory:" + pStepId + ":staging:" + pEntityName;
		}

		@Override
		public String tryBatch(final List<RowPlan> pItems)
		{
			final ProcedureResult result = ProgrammaticService.createForSession(this.session, this.stagingDataset.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setExecutionInformation(StagingAttempt.this.executionInformation);
					for (final RowPlan item : pItems)
					{
						StagingLoader.upsertOne(pContext, StagingAttempt.this.stagingTable, item, StagingAttempt.this.sourceHashFieldExists);
					}
				}
			});
			return result.hasFailed() ? RefusalHints.header(result.getException()) + RejectText.cut(RejectText.detail(result.getExceptionFullMessage(Locale.ENGLISH)), 1500) : null;
		}

		@Override
		public String tryOne(final RowPlan pItem)
		{
			final ProcedureResult result = ProgrammaticService.createForSession(this.session, this.stagingDataset.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setExecutionInformation(StagingAttempt.this.executionInformation);
					StagingLoader.upsertOne(pContext, StagingAttempt.this.stagingTable, pItem, StagingAttempt.this.sourceHashFieldExists);
				}
			});
			return result.hasFailed() ? RefusalHints.header(result.getException()) + RejectText.cut(RejectText.detail(result.getExceptionFullMessage(Locale.ENGLISH)), 1500) : null;
		}
	}

	/**
	 * Lot V3-S: a scope {@code all} row reuses the existing record {@code processRow} already read ({@link RowPlan#existing}); in every other case the
	 * lookup happens here, inside the Procedure, as before.
	 * <p>
	 * Writes every mapped/derived field via {@code setValueFromXsString} - {@code MappingEngine} already
	 * yields the write-ready xs-string form, EBX types it at write time (docs/TARGET-MAPPING.md sec 2.2/3.3
	 * point 7: {@code checkTypeMatch} is retired, a bad shape is caught here, by EBX itself, as an ordinary
	 * per-row EBX refusal). A {@code null} entry (a {@link NullFillRule} forced default, or a genuinely empty
	 * mapped value) clears the field with a plain {@code setValue(null, ...)} instead - {@code
	 * setValueFromXsString} has no {@code null} form of its own (the same convention {@code
	 * MappingEngine#applyValues} itself follows).
	 */
	private static void upsertOne(final ProcedureContext pContext, final AdaptationTable pStagingTable, final RowPlan pRow, final boolean pSourceHashFieldExists)
		throws Exception
	{
		// lot V3-S: a scope all load already read the row (processRow) - reused, no second read. A row it found missing, a key planned twice in one
		// batch, and every scope new row keep the lookup, here inside the Procedure, exactly as before (an insert may meet the row an earlier row of
		// the same batch just created).
		final Adaptation existing = pRow.existing != null ? pRow.existing
			: pStagingTable.lookupAdaptationByPrimaryKey(pStagingTable.computePrimaryKey(new Object[] { pRow.sourceSystem, pRow.sourceId }));
		final ValueContextForUpdate value;
		if (existing == null)
		{
			value = pContext.getContextForNewOccurrence(pStagingTable);
			value.setValue(pRow.sourceSystem, StagingLoader.STAGING_SOURCE_SYSTEM);
			value.setValue(pRow.sourceId, StagingLoader.STAGING_SOURCE_ID);
		}
		else
		{
			value = pContext.getContext(existing.getAdaptationName());
		}
		for (final Map.Entry<String, Object> field : pRow.fields.entrySet())
		{
			final Path path = Path.parse("./" + field.getKey());
			final Object fieldValue = field.getValue();
			if (fieldValue == null)
			{
				value.setValue(null, path);
			}
			else
			{
				value.setValueFromXsString(String.valueOf(fieldValue), path);
			}
		}
		value.setValue(pRow.loadId, StagingLoader.STAGING_LOAD_ID);
		value.setValue(pRow.receivedAt, StagingLoader.STAGING_RECEIVED_AT);
		value.setValue("STAGED", StagingLoader.STAGING_STATUS);
		if (pSourceHashFieldExists)
		{
			value.setValue(pRow.sourceHash, StagingLoader.STAGING_SOURCE_HASH);
		}
		if (existing == null)
		{
			pContext.doCreateOccurrence(value, pStagingTable);
		}
		else
		{
			pContext.doModifyContent(existing, value);
		}
		// volume lot 3 (2026-09-28, task 6): a first-time insert is the ONLY case that could be resolving an
		// earlier STAGING-stage TransferReject (an UPDATE row already reached staging once before, so it can
		// never have one still open) - see writeXref's own Javadoc for how this cheap signal avoids an
		// unconditional extra lookup per written row.
		pRow.inserted = existing == null;
	}

	// ======================================================================================= pass 2: deferred FK

	/**
	 * Volume lot 2a (2026-09-28, docs/VOLUME.md, task 4) - two phases, never overlapping:
	 * <ol>
	 * <li>a server-side predicate restricts the scan to rows that actually carry a reference to resolve (most
	 * self-referencing rows have none at all - e.g. a root account with no parent), collecting only PRIMARY KEY
	 * strings, never full rows;</li>
	 * <li>the {@link RequestResult} above is fully closed before any write - each batch's own {@link Procedure}
	 * re-reads its rows fresh by primary key (a plain indexed lookup) and modifies {@code pOwnTable}, which a
	 * still-open {@code RequestResult} on the very same table would otherwise race against.</li>
	 * </ol>
	 * Also skips a write whose resolved value already matches what is stored (never a redundant re-write).
	 *
	 * @return false when {@code pHandle} was cancelled mid-way (volume lot 3, task 8) - the caller stops
	 *         iterating further deferred foreign keys too; {@code true} on a normal, complete pass.
	 */
	private static boolean resolveDeferredReferences(final Session pSession, final Adaptation pStagingDataset, final TransferPlan.ForeignKeyPlan pFk,
		final AdaptationTable pOwnTable, final AdaptationTable pTargetTable, final int pBatchSize, final Counters pCounters, final FactoryJobs.Handle pHandle,
		final StagingEndWrites pEnd) throws OperationException
	{
		final Path attributePath = Path.parse("./" + pFk.attributeName);
		final Path fieldPath = Path.parse("./" + pFk.fieldName);

		final List<String> candidateKeys = new ArrayList<String>();
		final RequestResult rows = pOwnTable.createRequestResult("osd:is-not-null(./" + pFk.attributeName + ")");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				candidateKeys.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}

		final List<RefUpdate> buffer = new ArrayList<RefUpdate>();
		for (final String rowKey : candidateKeys)
		{
			final Adaptation row = pOwnTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(rowKey));
			if (row == null)
			{
				continue; // deleted meanwhile - defensive
			}
			final Object attributeValue;
			try
			{
				attributeValue = row.get(attributePath);
			}
			catch (final RuntimeException ex)
			{
				continue;
			}
			final String keyText = attributeValue == null ? null
				: attributeValue instanceof BigDecimal ? ((BigDecimal) attributeValue).stripTrailingZeros().toPlainString() : String.valueOf(attributeValue);
			if (keyText == null)
			{
				continue; // defensive - osd:is-not-null already filtered these out above
			}
			final String sourceSystem = row.getString(StagingLoader.STAGING_SOURCE_SYSTEM);
			final PrimaryKey candidateKey = pTargetTable.computePrimaryKey(new Object[] { sourceSystem, keyText });
			final Adaptation parent = pTargetTable.lookupAdaptationByPrimaryKey(candidateKey);
			if (parent == null)
			{
				pCounters.skipped++;
				continue;
			}
			final String resolvedKey = candidateKey.format();
			if (resolvedKey.equals(row.getString(fieldPath)))
			{
				continue; // already correct - never re-write an unchanged value
			}
			buffer.add(new RefUpdate(rowKey, resolvedKey));
			if (buffer.size() >= pBatchSize)
			{
				StagingLoader.flushRefUpdates(pSession, pStagingDataset, pOwnTable, fieldPath, buffer, pEnd);
				buffer.clear();
				if (pHandle != null && pHandle.cancelled())
				{
					return false; // volume lot 3 (task 8): a clean stop - the caller stops iterating further deferred FKs too
				}
			}
		}
		if (!buffer.isEmpty())
		{
			StagingLoader.flushRefUpdates(pSession, pStagingDataset, pOwnTable, fieldPath, buffer, pEnd);
		}
		return true;
	}

	private static final class RefUpdate
	{
		final String rowKey;
		final String parentKey;

		RefUpdate(final String pRowKey, final String pParentKey)
		{
			this.rowKey = pRowKey;
			this.parentKey = pParentKey;
		}
	}

	/** F2-06: a refused batch is recorded on {@code pEnd} (the step then ends PARTIAL), not only logged. */
	private static void flushRefUpdates(final Session pSession, final Adaptation pStagingDataset, final AdaptationTable pOwnTable, final Path pFieldPath,
		final List<RefUpdate> pUpdates, final StagingEndWrites pEnd)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pStagingDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final RefUpdate update : pUpdates)
				{
					final Adaptation row = pOwnTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(update.rowKey));
					if (row == null)
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(update.parentKey, pFieldPath);
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			final String cause = result.getExceptionFullMessage(Locale.ENGLISH);
			LoggingCategory.getKernel().error("[mdm-factory] could not resolve " + pUpdates.size() + " deferred reference(s) on " + pFieldPath.format() + ": " + cause);
			pEnd.referencesNotWritten(pFieldPath.format(), pUpdates.size(), cause);
		}
	}

	// ======================================================================================================= writes

	/**
	 * Records one root landing row's decision for the batch being built. Lot V3-S: when {@code pSkipUpToDate} (a scope {@code all} load of a table read by
	 * a single root feed) a row that already carries the status it would be given is left out - a landing row still {@code NEW} (reloaded since the last
	 * transfer), {@code null} or in the other status differs, so it is recorded and written back; the end state of every landing row is the same.
	 */
	private static void decideLandingStatus(final Map<String, String> pLandingStatus, final String pLandingRowKey, final String pDecision, final Adaptation pLandingRow,
		final boolean pSkipUpToDate)
	{
		if (!IncrementalTransfer.landingStatusNeedsWrite(pLandingRow.getString(StagingLoader.LANDING_STATUS), pDecision, pSkipUpToDate))
		{
			return;
		}
		pLandingStatus.put(pLandingRowKey, pDecision);
	}

	/**
	 * Writes back the landing status of {@code pStatusByRowKey}, one {@code Procedure}. {@code pSkipUpToDate} (lot V3-S, scope {@code all}): the rows are
	 * read first, outside the Procedure, and only the ones whose status differs from the decision are modified - none, no Procedure. Scope {@code new}
	 * passes {@code false} and does exactly what it always did (read and write each row inside the Procedure).
	 */
	private static void writeLandingStatus(final Session pSession, final Adaptation pLandingDataset, final String pLandingTableName, final Map<String, String> pStatusByRowKey,
		final boolean pSkipUpToDate, final StagingEndWrites pEnd)
	{
		final AdaptationTable landingTable = pLandingDataset.getTable(Path.parse("/root/" + pLandingTableName));
		final Map<String, Adaptation> preRead = new HashMap<String, Adaptation>();
		final Map<String, String> toWrite;
		if (pSkipUpToDate)
		{
			toWrite = new LinkedHashMap<String, String>();
			for (final Map.Entry<String, String> entry : pStatusByRowKey.entrySet())
			{
				final Adaptation row = landingTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(entry.getKey()));
				if (row == null || !IncrementalTransfer.landingStatusNeedsWrite(row.getString(StagingLoader.LANDING_STATUS), entry.getValue(), true))
				{
					continue;
				}
				preRead.put(entry.getKey(), row);
				toWrite.put(entry.getKey(), entry.getValue());
			}
			if (toWrite.isEmpty())
			{
				return;
			}
		}
		else
		{
			toWrite = pStatusByRowKey;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pLandingDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Map.Entry<String, String> entry : toWrite.entrySet())
				{
					final Adaptation row = pSkipUpToDate ? preRead.get(entry.getKey())
						: landingTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(entry.getKey()));
					if (row == null)
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(entry.getValue(), StagingLoader.LANDING_STATUS);
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			final String cause = result.getExceptionFullMessage(Locale.ENGLISH);
			LoggingCategory.getKernel().error(
				"[mdm-factory] could not write back the landing status of " + pStatusByRowKey.size() + " row(s) of " + pLandingTableName + ": " + cause);
			pEnd.landingStatusNotWritten(pLandingTableName, toWrite.size(), cause);
		}
	}

	/**
	 * Volume lot 3 (2026-09-28, docs/VOLUME.md task 6, "rejects deduplicated"): {@code TransferReject}'s
	 * primary key is now the natural key {@code (project, entity, stage, sourceSystem, sourceId)} -
	 * {@code tools/build_xsd.py} - so a row rejected again on a later run UPDATES the same reject row (new
	 * reasons, new {@code rejectedAt}, {@code resolved} reset to {@code false}) instead of piling up a new one
	 * every run: an indexed lookup by that key, never a full-table scan, and never an unbounded, ever-growing
	 * history for a row that keeps failing the same way.
	 */
	private static void writeRejects(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityId, final String pLandingDataspace,
		final String pLandingDatasetName, final String pLandingTableName, final List<RejectPlan> pRejects, final Date pNow, final String pStepId,
		final String pEntityName) throws OperationException
	{
		final AdaptationTable rejectsTable = pFactory.getTable(FactoryPaths.TRANSFER_REJECT);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				// volume lot 3 (task 8, "tracking info")
				pContext.setExecutionInformation("mdm-factory:" + pStepId + ":staging-reject:" + pEntityName);
				for (final RejectPlan reject : pRejects)
				{
					final Adaptation existing = rejectsTable.lookupAdaptationByPrimaryKey(rejectsTable
						.computePrimaryKey(new Object[] { pProjectId, pEntityId, StagingLoader.REJECT_STAGE, reject.sourceSystem, reject.sourceId }));
					final ValueContextForUpdate value = existing == null ? pContext.getContextForNewOccurrence(rejectsTable) : pContext.getContext(existing.getAdaptationName());
					value.setValue(pProjectId, FactoryPaths.TransferReject.PROJECT);
					value.setValue(pEntityId, FactoryPaths.TransferReject.ENTITY);
					value.setValue(StagingLoader.REJECT_STAGE, FactoryPaths.TransferReject.STAGE);
					value.setValue(reject.sourceSystem, FactoryPaths.TransferReject.SOURCE_SYSTEM);
					value.setValue(reject.sourceId, FactoryPaths.TransferReject.SOURCE_ID);
					value.setValue(pLandingDatasetName, FactoryPaths.TransferReject.LANDING_DATASET);
					value.setValue(pLandingTableName, FactoryPaths.TransferReject.LANDING_TABLE);
					value.setValue(reject.loadId, FactoryPaths.TransferReject.LOAD_ID);
					value.setValue(RejectReasons.store(reject.reasons, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.TransferReject.REASONS);
					// staging load rejects (2026-10-17): the cause, what identical reasons share (the screen groups by it) and EBX's raw text, kept for a folded detail
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
			throw EngineRefusal.coded("unexpected.saveRejects", "Could not record " + pRejects.size() + " transfer reject(s) of " + pLandingTableName + ": "
				+ result.getExceptionFullMessage(Locale.ENGLISH), pRejects.size());
		}
	}

	/**
	 * Volume lot 3 (2026-09-28, task 6): also resolves an earlier STAGING-stage {@code TransferReject} of the
	 * SAME (project, entity, sourceSystem, sourceId) when this row is a first-time INSERT ({@link
	 * RowPlan#inserted}) - an UPDATE row already reached staging once before (it cannot have an open reject of
	 * this stage), so this never costs an extra lookup for the common "already staged, just refreshing" case,
	 * only for a row that might be coming back from a fix. One extra indexed PK lookup per INSERT, in the SAME
	 * procedure/round trip as the Xref write itself - never a separate pass over {@code TransferReject}.
	 * <p>
	 * <b>Lot V3-S (2026-10-04), scope {@code all} only ({@code pSkipUnchangedXref})</b>: the {@code Xref} rows of the written rows are read before the
	 * Procedure, and a row whose {@code Xref} already holds every text this write would set (everything except {@code loadId}/{@code occurredAt}, which
	 * say when it was last written) gets no {@code doModifyContent}; an insert still goes through the Procedure so its prior reject is resolved as before;
	 * no Procedure is opened when nothing needs writing. Scope {@code new} writes every row's {@code Xref}, as before.
	 */
	private static void writeXref(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityId, final String pEntityName,
		final AdaptationTable pStagingTable, final String pLandingDatasetName, final String pLandingTableName, final List<RowPlan> pWritten, final Date pNow,
		final String pStepId, final boolean pSkipUnchangedXref) throws OperationException
	{
		final AdaptationTable xrefTable = pFactory.getTable(FactoryPaths.XREF);
		final AdaptationTable rejectsTable = pFactory.getTable(FactoryPaths.TRANSFER_REJECT);
		// Lot V3-S (2026-10-04), scope all only: the Xref rows of the written rows are read BEFORE the Procedure (indexed PK lookups, the Xref table is not
		// written until that Procedure runs), and a row whose Xref already holds every text this write would set - everything but loadId/occurredAt, which
		// only say when it was last written - gets no doModifyContent. An insert always goes through the Procedure: its earlier STAGING reject may have to be
		// resolved, exactly as before. Scope new reads nothing here and writes every row's Xref, as it always did.
		final List<XrefWork> work = new ArrayList<XrefWork>(pWritten.size());
		for (final RowPlan row : pWritten)
		{
			final String stagingKey = pStagingTable.computePrimaryKey(new Object[] { row.sourceSystem, row.sourceId }).format();
			Adaptation existing = null;
			boolean writeXref = true;
			if (pSkipUnchangedXref)
			{
				// volume lot 2a (2026-09-28, docs/VOLUME.md): Xref's primary key is the natural key (project, stagingTable, stagingKey) - an indexed PK lookup
				existing = xrefTable.lookupAdaptationByPrimaryKey(xrefTable.computePrimaryKey(new Object[] { pProjectId, pEntityName, stagingKey }));
				if (existing != null && StagingRowDiff.sameTexts(
					new String[] { pProjectId, pEntityName, stagingKey, pLandingDatasetName, pLandingTableName, row.landingRowKey, row.sourceSystem, row.sourceId },
					new String[] { existing.getString(FactoryPaths.Xref.PROJECT), existing.getString(FactoryPaths.Xref.STAGING_TABLE),
						existing.getString(FactoryPaths.Xref.STAGING_KEY), existing.getString(FactoryPaths.Xref.LANDING_DATASET),
						existing.getString(FactoryPaths.Xref.LANDING_TABLE), existing.getString(FactoryPaths.Xref.LANDING_KEY),
						existing.getString(FactoryPaths.Xref.SOURCE_SYSTEM), existing.getString(FactoryPaths.Xref.SOURCE_ID) }))
				{
					writeXref = false;
				}
				if (!writeXref && !(row.inserted && pEntityId != null))
				{
					continue; // nothing to write, nothing to resolve (an update row never has an open reject)
				}
			}
			work.add(new XrefWork(row, stagingKey, existing, writeXref));
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
					final RowPlan row = item.row;
					if (item.writeXref)
					{
						// a row read before the Procedure is reused; otherwise (scope new, or no Xref yet) the lookup happens here, as it always did
						final Adaptation existing = item.xref != null ? item.xref
							: xrefTable.lookupAdaptationByPrimaryKey(xrefTable.computePrimaryKey(new Object[] { pProjectId, pEntityName, item.stagingKey }));
						final ValueContextForUpdate value = existing == null ? pContext.getContextForNewOccurrence(xrefTable) : pContext.getContext(existing.getAdaptationName());
						value.setValue(pProjectId, FactoryPaths.Xref.PROJECT);
						value.setValue(pEntityName, FactoryPaths.Xref.STAGING_TABLE);
						value.setValue(item.stagingKey, FactoryPaths.Xref.STAGING_KEY);
						value.setValue(pLandingDatasetName, FactoryPaths.Xref.LANDING_DATASET);
						value.setValue(pLandingTableName, FactoryPaths.Xref.LANDING_TABLE);
						value.setValue(row.landingRowKey, FactoryPaths.Xref.LANDING_KEY);
						value.setValue(row.sourceSystem, FactoryPaths.Xref.SOURCE_SYSTEM);
						value.setValue(row.sourceId, FactoryPaths.Xref.SOURCE_ID);
						value.setValue(row.loadId, FactoryPaths.Xref.LOAD_ID);
						value.setValue(pNow, FactoryPaths.Xref.OCCURRED_AT);
						if (existing == null)
						{
							pContext.doCreateOccurrence(value, xrefTable);
						}
						else
						{
							pContext.doModifyContent(existing, value);
						}
					}

					if (row.inserted && pEntityId != null)
					{
						final Adaptation priorReject = rejectsTable.lookupAdaptationByPrimaryKey(rejectsTable.computePrimaryKey(
							new Object[] { pProjectId, pEntityId, StagingLoader.REJECT_STAGE, row.sourceSystem, row.sourceId }));
						if (priorReject != null && !Boolean.TRUE.equals(priorReject.get(FactoryPaths.TransferReject.RESOLVED)))
						{
							final ValueContextForUpdate rejectValue = pContext.getContext(priorReject.getAdaptationName());
							rejectValue.setValue(Boolean.TRUE, FactoryPaths.TransferReject.RESOLVED);
							pContext.doModifyContent(priorReject, rejectValue);
						}
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveXref", "Could not record " + pWritten.size() + " Xref row(s) of " + pEntityName + ": " + result.getExceptionFullMessage(Locale.ENGLISH), pWritten.size());
		}
	}

	/** Lot V3-S: one written row whose {@code Xref} needs attention - {@code xref} the row read before the Procedure (null: look it up inside), {@code writeXref} false when only an insert's prior reject is left to resolve. */
	private static final class XrefWork
	{
		final RowPlan row;
		final String stagingKey;
		final Adaptation xref;
		final boolean writeXref;

		XrefWork(final RowPlan pRow, final String pStagingKey, final Adaptation pXref, final boolean pWriteXref)
		{
			this.row = pRow;
			this.stagingKey = pStagingKey;
			this.xref = pXref;
			this.writeXref = pWriteXref;
		}
	}

	/**
	 * Lot M7 (2026-10-18): what the load writes in {@code StagingEntity.lastLoadMessage} of a built entity no accepted mapping feeds, instead of nothing (measured on
	 * E2EMASTER20261017235630: {@code ContactEmail} stayed APPLIED with no message and an empty table). The e2e scenario reads the "No feed" head.
	 */
	public static final String NO_FEED_MESSAGE = "No feed: no accepted mapping feeds this entity, nothing was loaded into it. "
		+ "Map at least one attribute to a landing column and accept the mapping, or reject the entity.";

	/** The built (ACCEPTED / APPLIED) entities the plan does not load - no accepted mapping on any of their attributes - in entity order (lot M7). Pure. */
	static List<String> entitiesWithoutFeed(final List<StagingModel.EntityInfo> pEntities, final Set<String> pBuiltEntities, final Set<String> pPlannedEntities)
	{
		final List<String> out = new ArrayList<String>();
		for (final StagingModel.EntityInfo entity : pEntities)
		{
			if (TechnicalKeyRule.isAcceptedLike(entity.status) && pBuiltEntities.contains(entity.name) && !pPlannedEntities.contains(entity.name))
			{
				out.add(entity.name);
			}
		}
		return out;
	}

	/** Writes {@link #NO_FEED_MESSAGE} and zero counters on every entity of {@code pNoFeedEntities} (lot M7). */
	private static void writeNoFeedMessages(final Session pSession, final Adaptation pFactory, final Map<String, String> pEntityIdByName, final List<String> pNoFeedEntities,
		final Date pNow) throws OperationException
	{
		final AdaptationTable entitiesTable = pFactory.getTable(FactoryPaths.STAGING_ENTITY);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final String name : pNoFeedEntities)
				{
					final String entityId = pEntityIdByName.get(name);
					final Adaptation row = entityId == null ? null : entitiesTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(entityId));
					if (row == null)
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(pNow, FactoryPaths.StagingEntity.LAST_LOAD_AT);
					value.setValue(BigDecimal.ZERO, FactoryPaths.StagingEntity.LAST_LOAD_READ);
					value.setValue(BigDecimal.ZERO, FactoryPaths.StagingEntity.LAST_LOAD_WRITTEN);
					value.setValue(BigDecimal.ZERO, FactoryPaths.StagingEntity.LAST_LOAD_SKIPPED);
					value.setValue(BigDecimal.ZERO, FactoryPaths.StagingEntity.LAST_LOAD_REJECTED);
					value.setValue(BigDecimal.ZERO, FactoryPaths.StagingEntity.LAST_LOAD_UNCHANGED);
					value.setValue(TextLimits.truncate(StagingLoader.NO_FEED_MESSAGE, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.StagingEntity.LAST_LOAD_MESSAGE);
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveCounters", "Transfer ran, but writing the no-feed message of " + pNoFeedEntities.size() + " entity(ies) failed: "
				+ result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	private static void writeEntityCounters(final Session pSession, final Adaptation pFactory, final Map<String, String> pEntityIdByName,
		final Map<String, Counters> pCountersByEntity, final Date pNow) throws OperationException
	{
		final AdaptationTable entitiesTable = pFactory.getTable(FactoryPaths.STAGING_ENTITY);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Map.Entry<String, Counters> entry : pCountersByEntity.entrySet())
				{
					final String entityId = pEntityIdByName.get(entry.getKey());
					if (entityId == null)
					{
						continue;
					}
					final Adaptation row = entitiesTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(entityId));
					if (row == null)
					{
						continue;
					}
					final Counters counters = entry.getValue();
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(pNow, FactoryPaths.StagingEntity.LAST_LOAD_AT);
					value.setValue(BigDecimal.valueOf(counters.read), FactoryPaths.StagingEntity.LAST_LOAD_READ);
					value.setValue(BigDecimal.valueOf(counters.written), FactoryPaths.StagingEntity.LAST_LOAD_WRITTEN);
					value.setValue(BigDecimal.valueOf(counters.skipped), FactoryPaths.StagingEntity.LAST_LOAD_SKIPPED);
					value.setValue(BigDecimal.valueOf(counters.rejected), FactoryPaths.StagingEntity.LAST_LOAD_REJECTED);
					value.setValue(BigDecimal.valueOf(counters.unchanged), FactoryPaths.StagingEntity.LAST_LOAD_UNCHANGED);
					String message = counters.written + " written, " + counters.unchanged + " unchanged, " + counters.skipped + " skipped, " + counters.rejected
						+ " rejected, of " + counters.read + " read.";
					// empty-child bug fix, 2026-09-25 - see processRow's own Javadoc.
					if (counters.emptyChildrenSkipped > 0)
					{
						message += " " + counters.emptyChildrenSkipped + " empty child row(s) not created.";
					}
					if (counters.emptyChildrenStaleFound > 0)
					{
						message += " " + counters.emptyChildrenStaleFound + " previously-written empty child row(s) found, left in place.";
					}
					if (!counters.missingFields.isEmpty())
					{
						message += " Accepted but not yet in the staging model (run staging/generate): " + StagingLoader.join(new ArrayList<String>(counters.missingFields))
							+ ".";
					}
					value.setValue(TextLimits.truncate(message, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.StagingEntity.LAST_LOAD_MESSAGE);
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveCounters", "Transfer ran, but writing back the entity counters failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	private static void writeQualityRuleCounters(final Session pSession, final Adaptation pFactory, final Map<String, RuleRunCounters> pRuleCounters, final Date pNow)
	{
		if (pRuleCounters.isEmpty())
		{
			return;
		}
		final AdaptationTable rulesTable = pFactory.getTable(FactoryPaths.QUALITY_RULE);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Map.Entry<String, RuleRunCounters> entry : pRuleCounters.entrySet())
				{
					final Adaptation row = rulesTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(entry.getKey()));
					if (row == null)
					{
						continue;
					}
					final RuleRunCounters counters = entry.getValue();
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(BigDecimal.valueOf(counters.checked), FactoryPaths.QualityRule.LAST_LOAD_CHECKED);
					value.setValue(BigDecimal.valueOf(counters.failing), FactoryPaths.QualityRule.LAST_LOAD_FAILING);
					value.setValue(counters.exampleKeys.isEmpty() ? null : TextLimits.truncate(StagingLoader.join(counters.exampleKeys), TextLimits.MESSAGE_MAX_LENGTH),
						FactoryPaths.QualityRule.LAST_LOAD_EXAMPLES);
					value.setValue(pNow, FactoryPaths.QualityRule.LAST_LOAD_CHECKED_AT);
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel()
				.error("[mdm-factory] transfer ran, but writing back " + pRuleCounters.size() + " quality rule counter(s) failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	// ======================================================================================================= reads

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

	private static List<StagingModel.EntityInfo> readEntities(final Adaptation pFactory, final String pProjectId, final Map<String, String> pEntityIdByName)
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

		// lot M5 (2026-10-04): which script-derived attributes have no applied function - their field is held empty by NullFillRule
		final java.util.Set<String> unappliedScript = new HashSet<String>();
		for (final ScriptDerivationGate.Entry entry : ScriptDerivationGate.notApplied(ScriptDerivations.read(pFactory, pProjectId)))
		{
			unappliedScript.add(entry.entityName + "." + entry.attributeName);
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
				row.getString(FactoryPaths.StagingEntity.STATUS), StagingLoader.readAttributes(pFactory, entityId, entityIdToName, unappliedScript), memberNames));
		}
		return out;
	}

	private static List<StagingModel.AttributeInfo> readAttributes(final Adaptation pFactory, final String pEntityId, final Map<String, String> pEntityIdToName,
		final java.util.Set<String> pUnappliedScript)
	{
		final List<StagingModel.AttributeInfo> out = new ArrayList<StagingModel.AttributeInfo>();
		final String entityName = pEntityIdToName.get(pEntityId);
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
				// lot M3 (2026-10-03): a CHILD_KEYS attribute travels with its derivation so that NullFillRule can leave its field alone
				// (a row rewritten by its feed must keep the keys of its children - only the post-pass writes them).
				// lot M5 (2026-10-04): a SCRIPT attribute travels with it too, with whether its function is applied: NullFillRule then leaves an
				// applied one alone (its triggers compute it) and holds an unapplied one EMPTY. Until then the exclusion of SCRIPT in NullFillRule never
				// took effect through this reader, which passed no derivation but CHILD_KEYS. FOLD_KEY/VALID_VALUE stay unpassed on purpose.
				final String derivation = row.getString(FactoryPaths.StagingAttribute.DERIVATION);
				final String name = row.getString(FactoryPaths.StagingAttribute.NAME);
				final boolean script = ScriptDerivationGate.isScript(derivation);
				final boolean carried = script || ChildKeysDerivation.DERIVATION_CHILD_KEYS.equals(derivation);
				out.add(new StagingModel.AttributeInfo(name, row.getString(FactoryPaths.StagingAttribute.LABEL),
					row.getString(FactoryPaths.StagingAttribute.DATA_TYPE), maxLength, mandatory, primaryKey, referenceEntityName,
					row.getString(FactoryPaths.StagingAttribute.STATUS), carried ? derivation : null,
					!script || pUnappliedScript == null || !pUnappliedScript.contains(entityName + "." + name)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static Map<String, List<DerivedFieldRule.Field>> readDerivedFields(final Adaptation pFactory, final Map<String, String> pEntityIdByName)
	{
		final Map<String, List<DerivedFieldRule.Field>> out = new LinkedHashMap<String, List<DerivedFieldRule.Field>>();
		for (final Map.Entry<String, String> entity : pEntityIdByName.entrySet())
		{
			final String entityId = entity.getValue();
			final Map<String, String> attributeNameById = new HashMap<String, String>();
			final List<Adaptation> derivedRows = new ArrayList<Adaptation>();
			final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(entityId));
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					attributeNameById.put(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.StagingAttribute.NAME));
					final String derivation = row.getString(FactoryPaths.StagingAttribute.DERIVATION);
					if (derivation != null && !derivation.trim().isEmpty() && TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.StagingAttribute.STATUS)))
					{
						derivedRows.add(row);
					}
				}
			}
			finally
			{
				rows.close();
			}
			final List<DerivedFieldRule.Field> derivedFields = new ArrayList<DerivedFieldRule.Field>();
			for (final Adaptation row : derivedRows)
			{
				final String sourceAttributeId = row.getString(FactoryPaths.StagingAttribute.DERIVED_FROM);
				final String sourceFieldName = sourceAttributeId == null ? null : attributeNameById.get(sourceAttributeId);
				// lot M3: a CHILD_KEYS attribute is not computed row by row from a source attribute of the same entity - the post-pass
				// (ChildKeysPass) writes it; DerivedFieldRule would otherwise read an unknown derivation as a fold
				// lot M5: nor is a SCRIPT attribute - its triggers compute it when applied, and it is held empty (NullFillRule) when not; handed to
				// DerivedFieldRule it was read as a fold, i.e. a copy of its source (phoneE164 = phone on every row, measured 2026-10-04)
				final String derivationKind = row.getString(FactoryPaths.StagingAttribute.DERIVATION);
				if (sourceFieldName != null && !ChildKeysDerivation.DERIVATION_CHILD_KEYS.equals(derivationKind) && !ScriptDerivationGate.isScript(derivationKind))
				{
					final String derivation = row.getString(FactoryPaths.StagingAttribute.DERIVATION);
					final List<QualityRuleCheck.Rule> validityRules = ValidValueDerivation.DERIVATION_VALID_VALUE.equals(derivation)
						? StagingLoader.readQualityRules(pFactory, sourceAttributeId) : Collections.<QualityRuleCheck.Rule>emptyList();
					derivedFields.add(new DerivedFieldRule.Field(sourceFieldName, row.getString(FactoryPaths.StagingAttribute.NAME), derivation, validityRules));
				}
			}
			if (!derivedFields.isEmpty())
			{
				out.put(entity.getKey(), derivedFields);
			}
		}
		return out;
	}

	/**
	 * One {@link TransferPlan.MappingInfo} per (accepted-like attribute) x (accepted-like {@code
	 * AttributeMapping}), for every entity {@code StagingModel.build} actually built a table for. The two
	 * technical identity attributes ({@code sourceSystem}/{@code sourceId}) are never included: {@link
	 * #processRow}/{@link #loadRootFeed} fill them directly for every feed alike (see the class Javadoc).
	 * <p>
	 * Target-mapping lot 1: resolves {@code AttributeMapping.feed} to a {@link TransferPlan.FeedRef} (via
	 * {@link #transferFeedRef}), and resolves the mapping's own pipeline - {@code AttributeMapping.pipeline}
	 * when set, else {@link PipelineCompat#translate} from {@code transformation}/{@code argument}
	 * (docs/TARGET-MAPPING.md sec 2.2). A mapping whose pipeline cannot be translated ({@link
	 * PipelineCompat#translate}'s own {@link IllegalArgumentException} - a structurally incomplete row, e.g.
	 * {@code PARSE_DATE} with no pattern) is SKIPPED for this run, not thrown: one mis-configured mapping must
	 * never abort the whole project's transfer - counted nowhere today (a residual gap, see this lot's own
	 * final report) but at least never fatal.
	 */
	private static List<TransferPlan.MappingInfo> readMappings(final Adaptation pFactory, final String pProjectId, final Adaptation pProject,
		final List<StagingModel.EntityInfo> pEntities, final Set<String> pBuiltEntities, final Map<String, String> pForeignKeyFieldByEntityAttribute)
	{
		final String landingDataspace = pProject.getString(FactoryPaths.Project.LANDING_DATASPACE);
		final Map<String, LandingColumnRef> columnCache = new HashMap<String, LandingColumnRef>();
		final Map<String, LandingTableRef> tableCache = new HashMap<String, LandingTableRef>();
		final Map<String, String> sourceSystemCache = new HashMap<String, String>();
		final Map<String, String> landingDatasetCache = new HashMap<String, String>();
		final Map<String, TransferPlan.FeedRef> feedCache = new HashMap<String, TransferPlan.FeedRef>();

		final List<TransferPlan.MappingInfo> out = new ArrayList<TransferPlan.MappingInfo>();
		for (final StagingModel.EntityInfo entity : pEntities)
		{
			if (!pBuiltEntities.contains(entity.name))
			{
				continue;
			}
			for (final StagingModel.AttributeInfo attribute : entity.attributes)
			{
				if (attribute.primaryKey || !TechnicalKeyRule.isAcceptedLike(attribute.status))
				{
					continue;
				}
				if (ScriptDerivationGate.isScript(attribute.derivation))
				{
					// lot M5 (2026-10-04): a script-derived attribute is never fed by a mapping - a mapping on it would write the raw source
					// value into a field its triggers compute (applied) or into one that must stay empty (not applied)
					continue;
				}
				final String foreignKeyField = pForeignKeyFieldByEntityAttribute.get(entity.name + "#" + attribute.name);
				final String attributeId = StagingLoader.attributeId(pFactory, pProjectId, entity.name, attribute.name);
				if (attributeId == null)
				{
					// live bug fix (coordinator, 2026-09-24): a name this project's own StagingModel.build
					// considered a valid candidate must always resolve to a real StagingAttribute row of THIS
					// project - if it does not (a bug elsewhere, or a row deleted between the two reads), skip
					// this one mapping rather than build a predicate from a null id (never reached in the
					// scoped-lookup case this fix targets, but kept as a last line of defence).
					continue;
				}
				final List<QualityRuleCheck.Rule> qualityRules = StagingLoader.readQualityRules(pFactory, attributeId, true);
				final String mappingPredicate = StagingLoader.attributeEqualsPredicate(attributeId);
				if (mappingPredicate == null)
				{
					continue; // defensive - attributeId was just checked non-null/blank above
				}
				final RequestResult mappingRows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).createRequestResult(mappingPredicate);
				try
				{
					for (Adaptation mappingRow = mappingRows.nextAdaptation(); mappingRow != null; mappingRow = mappingRows.nextAdaptation())
					{
						if (!TechnicalKeyRule.isAcceptedLike(mappingRow.getString(FactoryPaths.AttributeMapping.STATUS)))
						{
							continue;
						}
						final String landingColumnId = mappingRow.getString(FactoryPaths.AttributeMapping.LANDING_COLUMN);
						final LandingColumnRef column = StagingLoader.landingColumn(pFactory, landingColumnId, columnCache);
						if (column == null)
						{
							continue;
						}
						final LandingTableRef table = StagingLoader.landingTableRef(pFactory, column.landingTableId, tableCache);
						if (table == null)
						{
							continue;
						}
						final String sourceSystem = StagingLoader.projectSourceField(pFactory, table.projectSourceId, FactoryPaths.ProjectSource.SOURCE_SYSTEM, sourceSystemCache);
						final String landingDataset = StagingLoader.projectSourceField(pFactory, table.projectSourceId, FactoryPaths.ProjectSource.LANDING_DATASET,
							landingDatasetCache);
						if (sourceSystem == null || landingDataset == null)
						{
							continue;
						}
						final String mappingId = mappingRow.getOccurrencePrimaryKey().format();
						final String explicitPipeline = mappingRow.getString(FactoryPaths.AttributeMapping.PIPELINE);
						final String pipeline;
						if (explicitPipeline != null && !explicitPipeline.trim().isEmpty())
						{
							pipeline = explicitPipeline;
						}
						else
						{
							try
							{
								pipeline = PipelineCompat.translate(mappingRow.getString(FactoryPaths.AttributeMapping.TRANSFORMATION),
									mappingRow.getString(FactoryPaths.AttributeMapping.ARGUMENT), attribute.dataType);
							}
							catch (final IllegalArgumentException ex)
							{
								// a structurally incomplete mapping (e.g. PARSE_DATE with no pattern) - never aborts
								// the whole project's transfer; see this method's own Javadoc
								continue;
							}
						}
						final String feedId = mappingRow.getString(FactoryPaths.AttributeMapping.FEED);
						final TransferPlan.FeedRef feedRef = StagingLoader.transferFeedRef(pFactory, feedId, feedCache);
						out.add(new TransferPlan.MappingInfo(entity.name, attribute.name, attribute.dataType, attribute.maxLength, attribute.mandatory,
							attribute.referenceEntity, foreignKeyField, pipeline, column.fieldName, landingDataspace, landingDataset, table.tableName, sourceSystem,
							StagingLoader.readCleaningSteps(pFactory, mappingId), qualityRules, feedRef, mappingId));
					}
				}
				finally
				{
					mappingRows.close();
				}
			}
		}
		return out;
	}

	/**
	 * Staging preflight lot H1 (2026-10-17): the accepted mappings the load of {@code pProjectCode} would run, read EXACTLY as {@link #loadInternal} reads them (same entities, same
	 * built-entity rule, same foreign keys, same {@link #readMappings}) - each carries its {@code AttributeMapping.id}. Nothing is written and no staging zone is needed: the
	 * check reads the factory's own rows and the landing, before any model is built. Empty when the project has no accepted, mapped attribute.
	 */
	static List<TransferPlan.MappingInfo> acceptedMappings(final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = StagingLoader.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Map<String, String> entityIdByName = new LinkedHashMap<String, String>();
		final List<StagingModel.EntityInfo> entityInfos = StagingLoader.readEntities(pFactory, projectId, entityIdByName);
		final StagingModel.Result modelResult = StagingModel.build(entityInfos);
		final Map<String, String> foreignKeyFieldByEntityAttribute = new HashMap<String, String>();
		for (final StagingModel.ForeignKeyInfo fk : modelResult.foreignKeys)
		{
			foreignKeyFieldByEntityAttribute.put(fk.entityName + "#" + fk.attributeName, fk.fieldName);
		}
		return StagingLoader.readMappings(pFactory, projectId, project, entityInfos, new HashSet<String>(modelResult.builtEntities), foreignKeyFieldByEntityAttribute);
	}

	/** {@code AttributeMapping.feed} resolved to a {@link TransferPlan.FeedRef} - {@link TransferPlan.FeedRef#none()} for a pre-lot-1 row with no feed at all. Cached: several mappings of the same feed share one lookup. */
	private static TransferPlan.FeedRef transferFeedRef(final Adaptation pFactory, final String pFeedId, final Map<String, TransferPlan.FeedRef> pCache)
	{
		if (pFeedId == null || pFeedId.trim().isEmpty())
		{
			return TransferPlan.FeedRef.none();
		}
		final TransferPlan.FeedRef cached = pCache.get(pFeedId);
		if (cached != null)
		{
			return cached;
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.TRANSFER_FEED).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pFeedId));
		final TransferPlan.FeedRef ref;
		if (row == null)
		{
			ref = TransferPlan.FeedRef.none();
		}
		else
		{
			final String parentFeedId = row.getString(FactoryPaths.TransferFeed.PARENT_FEED);
			final String collectionPath = row.getString(FactoryPaths.TransferFeed.COLLECTION_PATH);
			final String parentLinkField = row.getString(FactoryPaths.TransferFeed.PARENT_LINK_FIELD);
			final String sourceIdPipeline = row.getString(FactoryPaths.TransferFeed.SOURCE_ID_PIPELINE);
			final String variant = row.getString(FactoryPaths.TransferFeed.VARIANT);
			ref = new TransferPlan.FeedRef(pFeedId, parentFeedId, collectionPath, parentLinkField, sourceIdPipeline, variant);
		}
		pCache.put(pFeedId, ref);
		return ref;
	}

	private static int countWaitingMappings(final Adaptation pFactory, final String pProjectId, final List<StagingModel.EntityInfo> pEntities,
		final Set<String> pBuiltEntities)
	{
		int waiting = 0;
		for (final StagingModel.EntityInfo entity : pEntities)
		{
			if (!pBuiltEntities.contains(entity.name))
			{
				continue;
			}
			for (final StagingModel.AttributeInfo attribute : entity.attributes)
			{
				if (attribute.primaryKey || !TechnicalKeyRule.isAcceptedLike(attribute.status))
				{
					continue;
				}
				final String attributeId = StagingLoader.attributeId(pFactory, pProjectId, entity.name, attribute.name);
				final String equalsPredicate = StagingLoader.attributeEqualsPredicate(attributeId);
				if (equalsPredicate == null)
				{
					continue;
				}
				final RequestResult mappingRows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).createRequestResult(equalsPredicate + " and ./status='PROPOSED'");
				try
				{
					while (mappingRows.nextAdaptation() != null)
					{
						waiting++;
					}
				}
				finally
				{
					mappingRows.close();
				}
			}
		}
		return waiting;
	}

	/**
	 * The accepted-or-applied rules of an attribute that judge a VALUE ALONE: a rule with a condition (desk lot L4) is left out, because the callers of this overload
	 * (validity derivations, match plans, the proposal's own measures) have no row to read the condition attribute on.
	 */
	static List<QualityRuleCheck.Rule> readQualityRules(final Adaptation pFactory, final String pAttributeId)
	{
		return StagingLoader.readQualityRules(pFactory, pAttributeId, false);
	}

	/** {@code pWithConditions}: also return the rules that carry a condition (the transfer, which reads the whole row). */
	static List<QualityRuleCheck.Rule> readQualityRules(final Adaptation pFactory, final String pAttributeId, final boolean pWithConditions)
	{
		final List<QualityRuleCheck.Rule> out = new ArrayList<QualityRuleCheck.Rule>();
		if (pAttributeId == null || pAttributeId.trim().isEmpty())
		{
			return out;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.QUALITY_RULE).createRequestResult("./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.QualityRule.STATUS)))
				{
					continue;
				}
				final String kind = row.getString(FactoryPaths.QualityRule.KIND);
				if (!QualityDetectors.REFERENCE.equals(kind) && !QualityDetectors.CHECK_DIGIT.equals(kind) && !QualityDetectors.PATTERN.equals(kind))
				{
					continue;
				}
				String conditionField = null;
				List<String> conditionValues = null;
				final String conditionAttributeId = row.getString(FactoryPaths.QualityRule.CONDITION_ATTRIBUTE);
				if (conditionAttributeId != null && !conditionAttributeId.trim().isEmpty())
				{
					if (!pWithConditions)
					{
						continue;
					}
					final Adaptation conditionAttribute = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(conditionAttributeId));
					final List<?> raw = row.getList(FactoryPaths.QualityRule.CONDITION_VALUES);
					if (conditionAttribute == null || raw == null || raw.isEmpty())
					{
						continue; // a condition that cannot be read is not guessed away into "always": the rule is not run
					}
					conditionField = conditionAttribute.getString(FactoryPaths.StagingAttribute.NAME);
					conditionValues = new ArrayList<String>();
					for (final Object value : raw)
					{
						conditionValues.add(String.valueOf(value));
					}
				}
				out.add(new QualityRuleCheck.Rule(row.getOccurrencePrimaryKey().format(), kind, row.getString(FactoryPaths.QualityRule.ARGUMENT),
					row.getString(FactoryPaths.QualityRule.SEVERITY), conditionField, conditionValues));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	static List<CleaningSteps.Step> readCleaningSteps(final Adaptation pFactory, final String pMappingId)
	{
		final List<MappingStepIndex.StepRow> rows = new ArrayList<MappingStepIndex.StepRow>();
		final RequestResult stepRows = pFactory.getTable(FactoryPaths.MAPPING_STEP).createRequestResult("./mapping=" + CatalogReader.quote(pMappingId));
		try
		{
			for (Adaptation row = stepRows.nextAdaptation(); row != null; row = stepRows.nextAdaptation())
			{
				final Object position = row.get(FactoryPaths.MappingStep.POSITION);
				rows.add(new MappingStepIndex.StepRow(row.getString(FactoryPaths.MappingStep.MAPPING), row.getString(FactoryPaths.MappingStep.STATUS),
					row.getString(FactoryPaths.MappingStep.KIND), row.getString(FactoryPaths.MappingStep.ARGUMENT),
					position instanceof Number ? Integer.valueOf(((Number) position).intValue()) : null, row));
			}
		}
		finally
		{
			stepRows.close();
		}
		return MappingStepIndex.cleaningStepsOf(rows);
	}

	/**
	 * Audit F2-100 (2026-10-24): the same steps for callers that read many mappings in one go (the Mapping matrix): from a {@link MappingStepIndex} already
	 * swept ONCE, instead of one {@code ./mapping=} scan of the shared {@code MappingStep} table per mapping. Same eligibility and order as {@link
	 * #readCleaningSteps(Adaptation, String)} ({@code MappingStepIndexTest} compares the two on simulated rows).
	 */
	static List<CleaningSteps.Step> readCleaningSteps(final MappingStepIndex pIndex, final String pMappingId)
	{
		return MappingStepIndex.cleaningStepsOf(pIndex.rowsOf(pMappingId));
	}

	/**
	 * Live bug fix (coordinator, 2026-09-24): the one place an attribute id becomes an {@code ./attribute=...}
	 * XPath equality predicate - pure (no EBX class), so it is unit-tested directly rather than only through
	 * {@link #attributeId}'s own EBX-bound lookup. Null/blank in, null out - the caller must skip the row
	 * rather than run a query built from nothing, exactly the rule CLAUDE.md &sect;9 states ("never build a
	 * predicate from a null") and the one this class violated before this fix: an unguarded {@code "./attribute="
	 * + CatalogReader.quote(attributeId)} with {@code attributeId == null} produced the malformed XPath EBX
	 * refused outright (see {@link #attributeId}'s own Javadoc for the full chain).
	 */
	static String attributeEqualsPredicate(final String pAttributeId)
	{
		if (pAttributeId == null || pAttributeId.trim().isEmpty())
		{
			return null;
		}
		return "./attribute=" + CatalogReader.quote(pAttributeId);
	}

	/**
	 * Live bug fix (coordinator, 2026-09-24): the entity lookup used to filter on {@code ./name} alone, with
	 * NO project scope - harmless as long as no two projects ever proposed an entity of the same name, which
	 * stopped being true the moment a second project existed: {@code Personnes} has an entity named {@code
	 * Personne} (id 24, {@code PROPOSED}) and {@code Vague1} independently proposed and ACCEPTED its own,
	 * unrelated entity also named {@code Personne} (id 28). {@code entityRows.nextAdaptation()} returns
	 * whichever row the table happens to return first (lowest primary key in practice) - id 24, the WRONG
	 * project's entity - and the attribute lookup that follows then searches for {@code pAttributeName} under
	 * THAT entity, finds no match (the two projects' attribute sets are unrelated), and returns null. Back in
	 * {@link #readMappings}, that null went straight into an XPath predicate (`./attribute=` + null) with no
	 * quoting at all, and EBX refused it outright: {@code MalformedXPathPredicateException: The specified
	 * value: null is invalid for: /root/AttributeMapping[*]/attribute} - which aborted {@link #load} before
	 * its own {@code STAGING_LOAD} {@code PipelineStep} was even created (see {@link #load}'s own Javadoc:
	 * {@code PipelineSteps.begin} runs after {@link #readMappings}), so nothing at all recorded the failure.
	 * Fixed by scoping the entity lookup to {@code pProjectId} too - see CLAUDE.md &sect;9's own "never build a
	 * predicate from a null" rule, which this violated a different way (an outright null, not merely an empty
	 * string that silently matches nothing).
	 */
	private static String attributeId(final Adaptation pFactory, final String pProjectId, final String pEntityName, final String pAttributeName)
	{
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY)
			.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./name=" + CatalogReader.quote(pEntityName));
		final String entityId;
		try
		{
			final Adaptation entityRow = entityRows.nextAdaptation();
			entityId = entityRow == null ? null : entityRow.getOccurrencePrimaryKey().format();
		}
		finally
		{
			entityRows.close();
		}
		if (entityId == null)
		{
			return null;
		}
		final RequestResult attributeRows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE)
			.createRequestResult("./entity=" + CatalogReader.quote(entityId) + " and ./name=" + CatalogReader.quote(pAttributeName));
		try
		{
			final Adaptation attributeRow = attributeRows.nextAdaptation();
			return attributeRow == null ? null : attributeRow.getOccurrencePrimaryKey().format();
		}
		finally
		{
			attributeRows.close();
		}
	}

	private static final class LandingColumnRef
	{
		final String fieldName;
		final String landingTableId;

		LandingColumnRef(final String pFieldName, final String pLandingTableId)
		{
			this.fieldName = pFieldName;
			this.landingTableId = pLandingTableId;
		}
	}

	private static LandingColumnRef landingColumn(final Adaptation pFactory, final String pLandingColumnId, final Map<String, LandingColumnRef> pCache)
	{
		if (pLandingColumnId == null)
		{
			return null;
		}
		if (pCache.containsKey(pLandingColumnId))
		{
			return pCache.get(pLandingColumnId);
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.LANDING_COLUMN).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingColumnId));
		final LandingColumnRef ref = row == null ? null
			: new LandingColumnRef(row.getString(FactoryPaths.LandingColumn.FIELD_NAME), row.getString(FactoryPaths.LandingColumn.LANDING_TABLE));
		pCache.put(pLandingColumnId, ref);
		return ref;
	}

	private static final class LandingTableRef
	{
		final String tableName;
		final String projectSourceId;

		LandingTableRef(final String pTableName, final String pProjectSourceId)
		{
			this.tableName = pTableName;
			this.projectSourceId = pProjectSourceId;
		}
	}

	private static LandingTableRef landingTableRef(final Adaptation pFactory, final String pLandingTableId, final Map<String, LandingTableRef> pCache)
	{
		if (pLandingTableId == null)
		{
			return null;
		}
		if (pCache.containsKey(pLandingTableId))
		{
			return pCache.get(pLandingTableId);
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingTableId));
		final LandingTableRef ref = row == null ? null
			: new LandingTableRef(row.getString(FactoryPaths.LandingTable.TABLE_NAME), row.getString(FactoryPaths.LandingTable.PROJECT_SOURCE));
		pCache.put(pLandingTableId, ref);
		return ref;
	}

	private static String projectSourceField(final Adaptation pFactory, final String pProjectSourceId, final Path pField, final Map<String, String> pCache)
	{
		if (pProjectSourceId == null)
		{
			return null;
		}
		final String cacheKey = pProjectSourceId + "#" + pField.format();
		if (pCache.containsKey(cacheKey))
		{
			return pCache.get(cacheKey);
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
		final String value = row == null ? null : row.getString(pField);
		pCache.put(cacheKey, value);
		return value;
	}

	// ======================================================================================================= misc

	/** Carries the ordered key VALUES ({@link #sourceSystem}, {@link #sourceId}) - never a formatted/concatenated key string; only {@code AdaptationTable.computePrimaryKey}/{@code PrimaryKey.format()}, at the point of an actual EBX call, ever produce one (see the class Javadoc). */
	static final class RowPlan
	{
		final String sourceSystem;
		final String sourceId;
		/** Every value is a plain xs-string, or {@code null} (a forced default, or a genuinely empty computed value) - see {@link #upsertOne}. */
		final Map<String, Object> fields;
		final String loadId;
		final Date receivedAt;
		final String landingRowKey;
		final String sourceHash;
		/** Written by {@link #upsertOne}: whether this write created a new staging row (true) or modified an existing one (false) - volume lot 3 task 6, see {@link #writeXref}'s own Javadoc. */
		boolean inserted;
		/** Lot V3-S2: {@code computePrimaryKey(sourceSystem, sourceId).format()}, set by a scope {@code all} load - what the staging index is keyed on; null in scope {@code new}. */
		String keyText;
		/** Lot V3-S: the existing staging row, read once at planning time by a scope {@code all} load; {@code null} when none exists (or the scope is {@code new}) - {@link #upsertOne} then looks it up itself, as it always did. */
		Adaptation existing;
		/** Staging load rejects (2026-10-17): the formatted staging key of the parent row this one was computed from (null for a root row) - what a refused parent is looked up by, see {@code flushTree}. */
		String parentKey;

		RowPlan(final String pSourceSystem, final String pSourceId, final Map<String, Object> pFields, final String pLoadId, final Date pReceivedAt,
			final String pLandingRowKey, final String pSourceHash)
		{
			this.sourceSystem = pSourceSystem;
			this.sourceId = pSourceId;
			this.fields = pFields;
			this.loadId = pLoadId;
			this.receivedAt = pReceivedAt;
			this.landingRowKey = pLandingRowKey;
			this.sourceHash = pSourceHash;
		}
	}

	static final class RejectPlan
	{
		final String landingRowKey;
		final String sourceSystem;
		final String sourceId;
		final String loadId;
		/** Staging load rejects (2026-10-17): coded reasons (never plain strings) - the first one gives the category, the grouping signature and the raw detail. */
		final List<RejectReason> reasons;

		RejectPlan(final String pLandingRowKey, final String pSourceSystem, final String pSourceId, final String pLoadId, final List<RejectReason> pReasons)
		{
			this.landingRowKey = pLandingRowKey;
			this.sourceSystem = pSourceSystem;
			this.sourceId = pSourceId;
			this.loadId = pLoadId;
			this.reasons = pReasons;
		}

		RejectPlan(final String pLandingRowKey, final String pSourceSystem, final String pSourceId, final String pLoadId, final RejectReason pReason)
		{
			this(pLandingRowKey, pSourceSystem, pSourceId, pLoadId, Collections.singletonList(pReason));
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

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}

	static int batchSize(final Adaptation pFactory) throws OperationException
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (settings == null)
		{
			throw EngineRefusal.coded("missing.settings", "MDM Factory settings: the DEFAULT row is missing");
		}
		final Object raw = settings.get(FactoryPaths.Settings.TRANSFER_BATCH_SIZE);
		final int value = raw instanceof Number ? ((Number) raw).intValue() : 0;
		return value > 0 ? value : StagingLoader.DEFAULT_BATCH_SIZE;
	}
}
