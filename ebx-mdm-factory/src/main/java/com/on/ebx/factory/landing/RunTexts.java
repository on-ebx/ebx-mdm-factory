package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.wizard.EngineMessageCodec;

/**
 * Run panel i18n (2026-10-17): the sentences the landing RUN itself builds and the wizard shows while it runs and after it (cell summaries such as "Built." and
 * "customerId (chosen by a person)", what a running stage is doing, the journal "What the factory is doing", the end-of-run job messages). They used to be stored
 * in English and printed as stored, so the French wizard read English in its live panel. Each of them is now a CODE (a bundle key {@code wizard.runtext.*}) plus its
 * arguments, stored the way {@link KeyTexts} already stores a key rationale: the English sentence followed by the marker of {@link EngineMessageCodec}
 * ({@code "Built. [mdmf:wizard.runtext.built]"}). A reader that ignores the marker (REST, MCP, logs) sees the same English as before; the wizard words the code in
 * the viewer's language ({@code wizard.RunPanelText}): on the server-rendered panel, in Recent tasks, and in the answer of the jobs route that the live panel's script
 * repaints from ({@code ?lang=fr}; without it, English with no marker).
 * <p>
 * <b>Arguments.</b> A plain value, or a NESTED text: an argument that is itself a stored coded text ("customerId (chosen by a person) [mdmf:...]") is shown in the
 * viewer's language before it is substituted, and an argument that is the bare key of a fragment ({@code wizard.runtext.frag.low}) is replaced by that
 * fragment. Plural forms are chosen where the sentence is BUILT (one code per form: {@code loadFailed.one} / {@code loadFailed.other}), so a pattern is always a plain
 * {@code {0}}-style substitution.
 * <p>
 * <b>Old runs.</b> A run stored before this lot has no marker: {@link #parse} recognises the fixed English templates (and the nested key summary / waiting reason)
 * and gives back the code and arguments, so those rows display in French with no migration. Free text (a connector's error, an EBX exception, the language
 * model's words) is not recognised and is shown as it is. The English of a code lives here ONCE ({@link #ENGLISH}); {@code Messages_en} says the same (a test pins it).
 * Pure, EBX-free.
 */
public final class RunTexts
{
	/** Prefix of every message key of this class in the module bundle. */
	public static final String P = "wizard.runtext.";

	public static final String LOADED = RunTexts.P + "loaded";
	public static final String BUILT = RunTexts.P + "built";
	public static final String CREATED = RunTexts.P + "created";
	public static final String KEY_CONFIRMED_LOADED = RunTexts.P + "keyConfirmedLoaded";
	public static final String BUILT_LOADED = RunTexts.P + "builtLoaded";
	public static final String CREATED_LOADED = RunTexts.P + "createdLoaded";
	public static final String KEY_CONFIRMED_BUILT = RunTexts.P + "keyConfirmedBuilt";
	public static final String KEY_CHOSEN = RunTexts.P + "keyChosen";
	/** Landing key validated in the walkthrough (2026-10-17): the KEY cell of a table whose deduced key the person validated, and the failure when a measure proves it not unique. */
	public static final String KEY_VALIDATED_CHECKED = RunTexts.P + "keyValidated.checked";
	public static final String KEY_VALIDATED_UNCHECKED = RunTexts.P + "keyValidated.unchecked";
	public static final String KEY_VALIDATED_NOT_UNIQUE_SAMPLE = RunTexts.P + "keyValidated.notUniqueSample";
	public static final String KEY_VALIDATED_NOT_UNIQUE_SOURCE = RunTexts.P + "keyValidated.notUniqueSource";
	public static final String STOPPED_BY_PERSON = RunTexts.P + "stoppedByPerson";
	public static final String STOPPED = RunTexts.P + "stopped";
	public static final String WAITS_FOR_KEY = RunTexts.P + "waitsForKey";
	public static final String KEY_FAILED_NO_FLOW = RunTexts.P + "keyFailedNoFlow";
	public static final String TABLE_FAILED_NO_FLOW = RunTexts.P + "tableFailedNoFlow";
	public static final String TABLE_FAILED_NO_LOAD = RunTexts.P + "tableFailedNoLoad";
	public static final String UNEXPECTED = RunTexts.P + "unexpected";
	public static final String NO_KEY_YET = RunTexts.P + "noKeyYet";
	public static final String NO_COLUMN_CERTAIN = RunTexts.P + "noColumnCertain";
	public static final String NO_PLANNED_TABLE = RunTexts.P + "noPlannedTable";
	public static final String TABLE_NOT_BUILT = RunTexts.P + "tableNotBuilt";
	public static final String FLOW_NOT_CREATED = RunTexts.P + "flowNotCreated";
	public static final String LOAD_FAILED_ONE = RunTexts.P + "loadFailed.one";
	public static final String LOAD_FAILED_OTHER = RunTexts.P + "loadFailed.other";
	public static final String SEE_DETAIL = RunTexts.P + "seeDetail";
	public static final String KEY_SUMMARY_NONE = RunTexts.P + "keySummary.none";
	public static final String KEY_SUMMARY = RunTexts.P + "keySummary.";
	public static final String KEY_WAIT = RunTexts.P + "keyWait";
	public static final String WAIT_NO_EVIDENCE = RunTexts.P + "wait.noEvidence";
	public static final String WAIT_NOT_UNIQUE = RunTexts.P + "wait.notUnique";
	public static final String WAIT_SAMPLE_ONLY = RunTexts.P + "wait.sampleOnly";
	public static final String WAIT_LLM = RunTexts.P + "wait.llm";
	public static final String FRAG_LOW = RunTexts.P + "frag.low";
	public static final String FRAG_SOURCE_UNIQUE = RunTexts.P + "frag.sourceUnique";
	public static final String FRAG_SOURCE_NOT_UNIQUE = RunTexts.P + "frag.sourceNotUnique";

	public static final String RUN_ACCEPTED_11 = RunTexts.P + "runAccepted.11";
	public static final String RUN_ACCEPTED_1N = RunTexts.P + "runAccepted.1n";
	public static final String RUN_ACCEPTED_N1 = RunTexts.P + "runAccepted.n1";
	public static final String RUN_ACCEPTED_NN = RunTexts.P + "runAccepted.nn";
	public static final String PARALLEL = RunTexts.P + "parallel";
	public static final String LOOKING_KEY = RunTexts.P + "lookingKey";
	public static final String LOOKING_KEY_LLM = RunTexts.P + "lookingKeyLlm";
	public static final String BUILDING_TABLES = RunTexts.P + "buildingTables";
	public static final String CREATING_FLOW = RunTexts.P + "creatingFlow";
	public static final String LOADING_DATA = RunTexts.P + "loadingData";
	public static final String CREATING_DATASET = RunTexts.P + "creatingDataset";
	public static final String MODEL_REUSED = RunTexts.P + "modelReused";
	public static final String CREATING_MODEL_ONE = RunTexts.P + "creatingModel.one";
	public static final String CREATING_MODEL_OTHER = RunTexts.P + "creatingModel.other";
	public static final String PUBLISHING_MODEL = RunTexts.P + "publishingModel";
	public static final String COPYING = RunTexts.P + "copying";
	public static final String COPYING_OF = RunTexts.P + "copyingOf";
	public static final String ROW_COPIED_ONE = RunTexts.P + "rowCopied.one";
	public static final String ROW_COPIED_OTHER = RunTexts.P + "rowCopied.other";
	public static final String CHANGED_ROW_COPIED_ONE = RunTexts.P + "changedRowCopied.one";
	public static final String CHANGED_ROW_COPIED_OTHER = RunTexts.P + "changedRowCopied.other";
	public static final String COPIED_SO_FAR = RunTexts.P + "copiedSoFar";

	public static final String FINISHED = RunTexts.P + "finished";
	public static final String FINISHED_FAILURES_ON = RunTexts.P + "finishedFailuresOn";
	public static final String PARTIAL_TABLE_WAS = RunTexts.P + "partial.tableWas";
	public static final String PARTIAL_TABLES_WAS = RunTexts.P + "partial.tablesWas";
	public static final String PARTIAL_TABLES_WERE = RunTexts.P + "partial.tablesWere";
	public static final String RUN_STOPPED = RunTexts.P + "runStopped";
	public static final String RUN_STOPPED_BY_PERSON = RunTexts.P + "runStoppedByPerson";
	public static final String RUN_PAUSED = RunTexts.P + "runPaused";
	public static final String RUN_LEFT_RUNNING = RunTexts.P + "runLeftRunning";
	public static final String STEP_GONE = RunTexts.P + "stepGone";
	public static final String RESUME_GONE = RunTexts.P + "resumeGone";

	/** The journal's words: {@code ev.stage.<KEY|TABLE|FLOW|LOAD>}, {@code ev.what.<done|failed|eye|waits|skipped>} and the two lines that join them. */
	public static final String EV_STAGE = RunTexts.P + "ev.stage.";
	public static final String EV_WHAT = RunTexts.P + "ev.what.";
	public static final String EV_BARE = RunTexts.P + "ev.bare";
	public static final String EV_WITH = RunTexts.P + "ev.with";

	/**
	 * Staging screens (2026-10-17): the sentences of the staging PROPOSAL run, the staging BUILD (and the generic ones the master build shares) and the sources job's "Ready"
	 * line - the same format as the landing run's. Order matters for reading old English: a more specific template comes before the generic one it would also match.
	 */
	public static final String STG = RunTexts.P + "stg.";
	public static final String PROPOSAL_FINISHED = RunTexts.STG + "proposalFinished";
	public static final String PROPOSAL_STOPPED = RunTexts.STG + "proposalStopped";
	public static final String PROPOSAL_FAILED = RunTexts.STG + "proposalFailed";
	public static final String QUEUED_DESIGN = RunTexts.STG + "queuedDesign";
	public static final String NOT_RUN_STOPPED = RunTexts.STG + "notRunStopped";
	public static final String NOT_RUN = RunTexts.STG + "notRun";
	public static final String NOT_REQUESTED = RunTexts.STG + "notRequested";
	public static final String NOT_SELECTED = RunTexts.STG + "notSelected";
	public static final String RULE_PASS_REFINE = RunTexts.STG + "rulePass.refine";
	public static final String RULE_PASS_REVIEW = RunTexts.STG + "rulePass.review";
	public static final String RULE_PASS_FIELD = RunTexts.STG + "rulePass.field";
	public static final String RULE_PASS_OBJECT = RunTexts.STG + "rulePass.object";
	public static final String NO_LLM_OR_ADMIN = RunTexts.STG + "noLlmOrAdmin";
	public static final String BUILD_ACCEPTED = RunTexts.STG + "buildAccepted";
	public static final String BUILD_FINISHED = RunTexts.STG + "buildFinished";
	public static final String BUILD_STOPPED = RunTexts.STG + "buildStopped";
	public static final String BUILD_FAILED_MODEL = RunTexts.STG + "buildFailed.model";
	public static final String BUILD_FAILED_LOAD = RunTexts.STG + "buildFailed.load";
	public static final String BUILD_FAILED_BOTH = RunTexts.STG + "buildFailed.both";
	public static final String BUILD_FAILED = RunTexts.STG + "buildFailed";
	public static final String STAGING_MODEL_AT = RunTexts.STG + "modelAt";
	public static final String ROWS_WRITTEN = RunTexts.STG + "rowsWritten";
	public static final String STOPPED_MID_RUN = RunTexts.STG + "stoppedMidRun";
	public static final String FAILED_PREFIX = RunTexts.STG + "failed";
	public static final String READING_ONE = RunTexts.STG + "reading.one";
	public static final String READING_OTHER = RunTexts.STG + "reading.other";
	public static final String PROGRESS = RunTexts.STG + "progress";
	public static final String PROGRESS_UNCHANGED = RunTexts.STG + "progress.unchanged";
	public static final String PROGRESS_HELD = RunTexts.STG + "progress.held";
	public static final String PROGRESS_UNCHANGED_HELD = RunTexts.STG + "progress.unchangedHeld";
	public static final String PREPARING_MODEL = RunTexts.STG + "preparingModel";
	public static final String CREATING_STAGING_MODEL_ONE = RunTexts.STG + "creatingModel.one";
	public static final String CREATING_STAGING_MODEL_OTHER = RunTexts.STG + "creatingModel.other";
	public static final String UPDATING_MODEL = RunTexts.STG + "updatingModel";
	public static final String PUBLISHING_STAGING_MODEL = RunTexts.STG + "publishingModel";
	public static final String CREATING_STAGING_DATASET = RunTexts.STG + "creatingDataset";
	public static final String CHECKING_UNIQUE = RunTexts.STG + "checkingUnique";
	/** Lot M11: the unique check counted, but its example query could not run - the load goes on. */
	public static final String UNIQUE_EXAMPLES_UNAVAILABLE = RunTexts.STG + "uniqueExamplesUnavailable";
	public static final String RESOLVING_REFS = RunTexts.STG + "resolvingRefs";
	public static final String COMPUTING_FROM = RunTexts.STG + "computingFrom";
	public static final String MARKING_STAGED = RunTexts.STG + "markingStaged";
	public static final String SOURCE_READY_PLAN_FAILED = RunTexts.STG + "sourceReadyPlanFailed";
	public static final String SOURCE_READY = RunTexts.STG + "sourceReady";
	/** Staging preflight lot H1 (2026-10-17): the check before the build writes any row - what the build's journal and the job's message say. */
	public static final String PREFLIGHT_CHECKING = RunTexts.STG + "preflight.checking";
	public static final String PREFLIGHT_CLEAN = RunTexts.STG + "preflight.clean";
	public static final String PREFLIGHT_WARNINGS = RunTexts.STG + "preflight.warnings";
	public static final String PREFLIGHT_BLOCKED = RunTexts.STG + "preflight.blocked";
	public static final String PREFLIGHT_UNAVAILABLE = RunTexts.STG + "preflight.unavailable";
	public static final String PREFLIGHT_SET_ASIDE = RunTexts.STG + "preflight.setAside";

	/** Run texts of the matching and master screens (2026-10-18): the matching job's result, an entity's run message and its scope, the master build's notes and summaries. */
	public static final String JOINED = RunTexts.P + "joined";
	public static final String MTC = RunTexts.P + "mtc.";
	public static final String MTC_RESULT_ONE = RunTexts.MTC + "result.one";
	public static final String MTC_RESULT_OTHER = RunTexts.MTC + "result.other";
	public static final String MTC_FOLLOWS_ONE = RunTexts.MTC + "follows.one";
	public static final String MTC_FOLLOWS_OTHER = RunTexts.MTC + "follows.other";
	public static final String MTC_STOPPED_ONE = RunTexts.MTC + "stopped.one";
	public static final String MTC_STOPPED_OTHER = RunTexts.MTC + "stopped.other";
	public static final String MTC_FINISHED = RunTexts.MTC + "finished";
	public static final String MTC_SCOPE_ALL = RunTexts.MTC + "scope.all";
	public static final String MTC_SCOPE_ALL_NO_COUNT = RunTexts.MTC + "scope.allNoCount";
	public static final String MTC_SCOPE_INC = RunTexts.MTC + "scope.incremental";
	public static final String MTC_SCOPE_INC_NO_COUNT = RunTexts.MTC + "scope.incrementalNoCount";
	public static final String MST = RunTexts.P + "mst.";
	public static final String MST_QUEUED = RunTexts.MST + "queued";
	public static final String MST_TABLES_ONE = RunTexts.MST + "tables.one";
	public static final String MST_TABLES_OTHER = RunTexts.MST + "tables.other";
	public static final String MST_NO_TABLES_COPY = RunTexts.MST + "noTables.copy";
	public static final String MST_NO_TABLES_MATCHING = RunTexts.MST + "noTables.matching";
	public static final String MST_DECLARED_ONE = RunTexts.MST + "declared.one";
	public static final String MST_DECLARED_OTHER = RunTexts.MST + "declared.other";
	public static final String MST_RESOLVING_DEFERRED = RunTexts.MST + "resolvingDeferred";
	public static final String MST_NOTHING_CHANGED_ENTITY = RunTexts.MST + "nothingChangedEntity";
	public static final String MST_ENTITY_COUNTS = RunTexts.MST + "entityCounts";
	public static final String MST_ENTITY_COUNTS_HELD = RunTexts.MST + "entityCountsHeld";
	public static final String MST_COPYING_ONE = RunTexts.MST + "copying.one";
	public static final String MST_COPYING_OTHER = RunTexts.MST + "copying.other";
	public static final String MST_PROGRESS = RunTexts.MST + "progress";
	public static final String MST_PROGRESS_TOTAL = RunTexts.MST + "progress.total";
	public static final String MST_PROGRESS_HELD = RunTexts.MST + "progress.held";
	public static final String MST_PROGRESS_TOTAL_HELD = RunTexts.MST + "progress.totalHeld";
	public static final String MST_ROWS_WRITTEN_SCOPE = RunTexts.MST + "rowsWrittenScope";
	public static final String MST_NOTHING_CHANGED = RunTexts.MST + "nothingChanged";
	public static final String MST_BUILD_FINISHED = RunTexts.MST + "buildFinished";
	public static final String MST_BUILD_STOPPED = RunTexts.MST + "buildStopped";
	public static final String MST_BUILD_FAILED_TABLES = RunTexts.MST + "buildFailed.tables";
	public static final String MST_BUILD_FAILED_COPY = RunTexts.MST + "buildFailed.copy";
	public static final String MST_BUILD_FAILED_BOTH = RunTexts.MST + "buildFailed.both";
	public static final String MST_BUILD_FAILED = RunTexts.MST + "buildFailed";

	/**
	 * Distribution screens (2026-10-18, lot step 7 screen counts): the sentences of a distribution SEND - what the target card, the summary's Distribute card and the job
	 * banner say - coded like the master build's, so the French wizard no longer reads "Sent 980 row(s) across 3 mapping(s), scope=all". A send's per-table counts ride
	 * in the detail variants ("Personne 212, Adresse 534, Achat 234").
	 */
	public static final String DST = RunTexts.P + "dst.";
	public static final String DST_SENT = RunTexts.DST + "sent";
	public static final String DST_SENT_UNCHANGED = RunTexts.DST + "sentUnchanged";
	public static final String DST_SENT_DETAIL = RunTexts.DST + "sentDetail";
	public static final String DST_SENT_UNCHANGED_DETAIL = RunTexts.DST + "sentUnchangedDetail";
	/** The job's sentence of a send: {@code dst.first.*} for a first full send, {@code dst.send.*} for any other send; {@code .ok} / {@code .okBare} / {@code .failed} / {@code .failedBare}. */
	public static final String DST_FIRST = RunTexts.DST + "first.";
	public static final String DST_SEND = RunTexts.DST + "send.";
	public static final String FRAG_SCOPE_ALL = RunTexts.P + "frag.scopeAll";
	public static final String FRAG_SCOPE_CHANGED = RunTexts.P + "frag.scopeChanged";
	public static final String FRAG_STATUS_DONE = RunTexts.P + "frag.statusDone";
	public static final String FRAG_STATUS_STOPPED = RunTexts.P + "frag.statusStopped";

	/** The English of every code, {@code {0}}-style placeholders (plain substitution, no apostrophe rule: the bundle's English is the same text). */
	public static final Map<String, String> ENGLISH = new LinkedHashMap<String, String>();
	/** {@code key#index} of the placeholders that are numbers (the parser of old English reads digits there). */
	private static final Set<String> NUMERIC = new HashSet<String>();
	/** Keys whose arguments at these positions are themselves texts (coded or old English): {@code key#index}. */
	private static final Set<String> NESTED = new HashSet<String>();
	/** Older English still stored by runs written before a sentence changed: {@code key -> English}, read by {@link #parse} like the current one. */
	private static final List<String[]> LEGACY = new ArrayList<String[]>();
	private static final String MIDDLE_DOT = "·";

	/** An English text an older run stored for {@code pKey} (same placeholders, same numeric positions as the current one). */
	private static void legacy(final String pKey, final String pEnglish)
	{
		RunTexts.LEGACY.add(new String[] { pKey, pEnglish });
	}

	private static void put(final String pKey, final String pEnglish, final int... pNumeric)
	{
		RunTexts.ENGLISH.put(pKey, pEnglish);
		for (final int index : pNumeric)
		{
			RunTexts.NUMERIC.add(pKey + "#" + index);
		}
	}

	static
	{
		RunTexts.put(RunTexts.LOADED, "Loaded.");
		RunTexts.put(RunTexts.BUILT, "Built.");
		RunTexts.put(RunTexts.CREATED, "Created.");
		RunTexts.put(RunTexts.KEY_CONFIRMED_LOADED, "Key confirmed (the table is already loaded).");
		RunTexts.put(RunTexts.BUILT_LOADED, "Built (the table is already loaded).");
		RunTexts.put(RunTexts.CREATED_LOADED, "Created (the table is already loaded).");
		RunTexts.put(RunTexts.KEY_CONFIRMED_BUILT, "Key confirmed (the table is already built).");
		RunTexts.put(RunTexts.KEY_CHOSEN, "Key chosen by a person.");
		RunTexts.put(RunTexts.KEY_VALIDATED_CHECKED, "{0} (validated by a person, unique in the sample)");
		RunTexts.put(RunTexts.KEY_VALIDATED_UNCHECKED, "{0} (validated by a person, uniqueness not measured)");
		RunTexts.put(RunTexts.KEY_VALIDATED_NOT_UNIQUE_SAMPLE,
			"The key {0}, validated by a person, is not unique in the sample (a value repeats or is empty): it was not applied. Choose another key.");
		RunTexts.put(RunTexts.KEY_VALIDATED_NOT_UNIQUE_SOURCE,
			"The key {0}, validated by a person, is not unique across the whole source: it was not applied. Choose another key.");
		RunTexts.put(RunTexts.STOPPED_BY_PERSON, "Stopped by a person.");
		RunTexts.put(RunTexts.STOPPED, "Stopped ({0}).");
		RunTexts.put(RunTexts.WAITS_FOR_KEY, "Waits for the key.");
		RunTexts.put(RunTexts.KEY_FAILED_NO_FLOW, "The key step failed: nothing to build a flow on.");
		RunTexts.put(RunTexts.TABLE_FAILED_NO_FLOW, "The table step failed: nothing to build a flow on.");
		RunTexts.put(RunTexts.TABLE_FAILED_NO_LOAD, "The table step failed: nothing to load.");
		RunTexts.put(RunTexts.UNEXPECTED, "Unexpected error on this application - see ebxLog/kernel.log.");
		RunTexts.put(RunTexts.NO_KEY_YET, "No key yet: pick the key columns.");
		RunTexts.put(RunTexts.NO_COLUMN_CERTAIN, "No column is certain.");
		RunTexts.put(RunTexts.NO_PLANNED_TABLE, "No table is planned yet for this application: build the landing table first.");
		RunTexts.put(RunTexts.TABLE_NOT_BUILT, "The landing table has not been built yet.");
		RunTexts.put(RunTexts.FLOW_NOT_CREATED, "The landing flow has not been created yet.");
		RunTexts.put(RunTexts.LOAD_FAILED_ONE, "{0} of {1} table failed ({2}).", 0, 1);
		RunTexts.put(RunTexts.LOAD_FAILED_OTHER, "{0} of {1} tables failed ({2}).", 0, 1);
		RunTexts.put(RunTexts.SEE_DETAIL, "see detail");
		RunTexts.put(RunTexts.KEY_SUMMARY_NONE, "No key.");
		RunTexts.put(RunTexts.KEY_SUMMARY + "SOURCE", "{0} (declared by the source)");
		RunTexts.put(RunTexts.KEY_SUMMARY + "USER", "{0} (chosen by a person)");
		RunTexts.put(RunTexts.KEY_SUMMARY + "DEDUCED", "{0} (proposed by the language model during a deduced scan, confirmed by a person)");
		RunTexts.put(RunTexts.KEY_SUMMARY + "LLM", "{0} (proposed by the language model)");
		RunTexts.put(RunTexts.KEY_SUMMARY + "RULE", "{0} (proposed by the naming rule)");
		RunTexts.put(RunTexts.KEY_WAIT, "{0} - {1}");
		RunTexts.NESTED.add(RunTexts.KEY_WAIT + "#0");
		RunTexts.NESTED.add(RunTexts.KEY_WAIT + "#1");
		RunTexts.put(RunTexts.WAIT_NO_EVIDENCE, "No sample evidence yet.");
		RunTexts.put(RunTexts.WAIT_NOT_UNIQUE, "Not unique (or empty on some rows) in the sample.");
		RunTexts.put(RunTexts.WAIT_SAMPLE_ONLY, "Unique in the sample but not across the whole source.");
		RunTexts.put(RunTexts.WAIT_LLM, "Suggested by the language model ({0}%{1},{2} waiting for a person to confirm it.", 0);
		RunTexts.put(RunTexts.FRAG_LOW, ", low confidence");
		RunTexts.put(RunTexts.FRAG_SOURCE_UNIQUE, " unique across the whole source,");
		RunTexts.put(RunTexts.FRAG_SOURCE_NOT_UNIQUE, " NOT unique across the whole source,");

		RunTexts.put(RunTexts.RUN_ACCEPTED_11, "Run accepted: 1 table from 1 application.");
		RunTexts.put(RunTexts.RUN_ACCEPTED_1N, "Run accepted: 1 table from {0} applications.", 0);
		RunTexts.put(RunTexts.RUN_ACCEPTED_N1, "Run accepted: {0} tables from 1 application.", 0);
		RunTexts.put(RunTexts.RUN_ACCEPTED_NN, "Run accepted: {0} tables from {1} applications.", 0, 1);
		RunTexts.put(RunTexts.PARALLEL, "Up to {0} applications load at the same time ({1}).", 0);
		RunTexts.put(RunTexts.LOOKING_KEY, "Looking for the key: reading a sample of rows.");
		RunTexts.put(RunTexts.LOOKING_KEY_LLM, "Looking for the key: reading a sample of rows, then asking the language model if no column is certain.");
		RunTexts.put(RunTexts.BUILDING_TABLES, "Building the landing tables.");
		RunTexts.put(RunTexts.CREATING_FLOW, "Creating the Connectors flow that copies this table.");
		RunTexts.put(RunTexts.LOADING_DATA, "Loading the data.");
		RunTexts.put(RunTexts.CREATING_DATASET, "Creating the landing dataset {0} in {1}.");
		RunTexts.put(RunTexts.MODEL_REUSED, "The landing model {0} already exists: reused.");
		RunTexts.put(RunTexts.CREATING_MODEL_ONE, "Creating the landing model {0} (1 table, {1} fields, about one second each).", 1);
		RunTexts.put(RunTexts.CREATING_MODEL_OTHER, "Creating the landing model {0} ({1} tables, {2} fields, about one second each).", 1, 2);
		RunTexts.put(RunTexts.PUBLISHING_MODEL, "Publishing the landing model {0}.");
		RunTexts.put(RunTexts.COPYING, "Copying the rows of {0} through Connectors.");
		RunTexts.put(RunTexts.COPYING_OF, "Copying the rows of {0} through Connectors (table {1} of {2}).", 1, 2);
		RunTexts.put(RunTexts.ROW_COPIED_ONE, "{0}: {1} row copied.", 1);
		RunTexts.put(RunTexts.ROW_COPIED_OTHER, "{0}: {1} rows copied.", 1);
		RunTexts.put(RunTexts.CHANGED_ROW_COPIED_ONE, "{0}: {1} changed row copied.", 1);
		RunTexts.put(RunTexts.CHANGED_ROW_COPIED_OTHER, "{0}: {1} changed rows copied.", 1);
		RunTexts.put(RunTexts.COPIED_SO_FAR, "{0}: {1} row(s) copied so far...", 1);

		RunTexts.put(RunTexts.FINISHED, "Landing run finished: {0} of {1} stages done.", 0, 1);
		RunTexts.put(RunTexts.FINISHED_FAILURES_ON, "Landing run finished with failures on: {0}.");
		RunTexts.put(RunTexts.PARTIAL_TABLE_WAS, "Landing run finished with failures: {0} of {1} table was not loaded ({2}).", 0, 1);
		RunTexts.put(RunTexts.PARTIAL_TABLES_WAS, "Landing run finished with failures: {0} of {1} tables was not loaded ({2}).", 0, 1);
		RunTexts.put(RunTexts.PARTIAL_TABLES_WERE, "Landing run finished with failures: {0} of {1} tables were not loaded ({2}).", 0, 1);
		RunTexts.put(RunTexts.RUN_STOPPED, "Landing run stopped ({0}).");
		RunTexts.put(RunTexts.RUN_STOPPED_BY_PERSON, "Landing run stopped by a person.");
		RunTexts.put(RunTexts.RUN_PAUSED, "Landing run paused, waiting for a key choice.");
		RunTexts.put(RunTexts.RUN_LEFT_RUNNING, "Landing run left running: not every stage has settled yet.");
		RunTexts.put(RunTexts.STEP_GONE, "The landing run step is no longer running.");
		RunTexts.put(RunTexts.RESUME_GONE, "The landing run this resume belonged to is no longer running.");

		RunTexts.put(RunTexts.EV_STAGE + "KEY", "Key");
		RunTexts.put(RunTexts.EV_STAGE + "TABLE", "Table");
		RunTexts.put(RunTexts.EV_STAGE + "FLOW", "Flow");
		RunTexts.put(RunTexts.EV_STAGE + "LOAD", "Load");
		RunTexts.put(RunTexts.EV_WHAT + "done", "done");
		RunTexts.put(RunTexts.EV_WHAT + "failed", "failed");
		RunTexts.put(RunTexts.EV_WHAT + "eye", "needs your eye");
		RunTexts.put(RunTexts.EV_WHAT + "waits", "waits for the key");
		RunTexts.put(RunTexts.EV_WHAT + "skipped", "skipped");
		RunTexts.put(RunTexts.EV_BARE, "{0} " + RunTexts.MIDDLE_DOT + " {1} {2}.");
		RunTexts.put(RunTexts.EV_WITH, "{0} " + RunTexts.MIDDLE_DOT + " {1} {2}: {3}");

		RunTexts.put(RunTexts.PROPOSAL_FINISHED, "Proposal run finished.");
		RunTexts.put(RunTexts.PROPOSAL_STOPPED, "Proposal run stopped ({0}).");
		RunTexts.put(RunTexts.PROPOSAL_FAILED, "Proposal run failed: {0}");
		RunTexts.put(RunTexts.QUEUED_DESIGN, "Queued: waiting for the design of this project to be free.");
		RunTexts.put(RunTexts.NOT_RUN_STOPPED, "Not run: stopped ({0}).");
		RunTexts.put(RunTexts.NOT_RUN, "Not run: {0}.");
		RunTexts.put(RunTexts.NOT_REQUESTED, "Not requested for this run.");
		RunTexts.put(RunTexts.NOT_SELECTED, "Not selected.");
		RunTexts.put(RunTexts.RULE_PASS_REFINE, "The rule pass did not complete: nothing to refine.");
		RunTexts.put(RunTexts.RULE_PASS_REVIEW, "The rule pass did not complete: no mapping to review.");
		RunTexts.put(RunTexts.RULE_PASS_FIELD, "The rule pass did not complete: no field to check.");
		RunTexts.put(RunTexts.RULE_PASS_OBJECT, "The rule pass did not complete: no business object to check.");
		RunTexts.put(RunTexts.NO_LLM_OR_ADMIN, "No language model is configured, or you are not an administrator.");
		RunTexts.put(RunTexts.BUILD_ACCEPTED, "Build accepted.");
		RunTexts.put(RunTexts.BUILD_FINISHED, "Staging build finished.");
		RunTexts.put(RunTexts.BUILD_STOPPED, "Staging build stopped ({0}).");
		RunTexts.put(RunTexts.BUILD_FAILED_MODEL, "Staging build failed: the model step did not complete - see the grid below for why.");
		RunTexts.put(RunTexts.BUILD_FAILED_LOAD, "Staging build failed: the load step did not complete - see the grid below for why.");
		RunTexts.put(RunTexts.BUILD_FAILED_BOTH, "Staging build failed: the model step and the load step did not complete - see the grid below for why.");
		RunTexts.put(RunTexts.BUILD_FAILED, "Staging build failed: {0}");
		RunTexts.put(RunTexts.STAGING_MODEL_AT, "Staging model at {0}/{1}.");
		RunTexts.put(RunTexts.ROWS_WRITTEN, "Rows written: {0}; source rows read: {1} ({2} unchanged, {3} held back).", 0, 1, 2, 3);
		RunTexts.legacy(RunTexts.ROWS_WRITTEN, "{0} row(s) written from {1} source row(s) read ({2} unchanged, {3} held back).");
		RunTexts.put(RunTexts.STOPPED_MID_RUN, "Stopped mid-run: {0}");
		RunTexts.NESTED.add(RunTexts.STOPPED_MID_RUN + "#0");
		RunTexts.put(RunTexts.FAILED_PREFIX, "Failed: {0}");
		RunTexts.NESTED.add(RunTexts.FAILED_PREFIX + "#0");
		RunTexts.put(RunTexts.READING_ONE, "{0} " + RunTexts.MIDDLE_DOT + " {1}: reading {2} row.", 2);
		RunTexts.put(RunTexts.READING_OTHER, "{0} " + RunTexts.MIDDLE_DOT + " {1}: reading {2} rows.", 2);
		RunTexts.put(RunTexts.PROGRESS, "{0} " + RunTexts.MIDDLE_DOT + " {1}: {2} of {3} rows read, {4} written.", 2, 3, 4);
		RunTexts.put(RunTexts.PROGRESS_UNCHANGED, "{0} " + RunTexts.MIDDLE_DOT + " {1}: {2} of {3} rows read, {4} written, {5} unchanged.", 2, 3, 4, 5);
		RunTexts.put(RunTexts.PROGRESS_HELD, "{0} " + RunTexts.MIDDLE_DOT + " {1}: {2} of {3} rows read, {4} written, {5} held back.", 2, 3, 4, 5);
		RunTexts.put(RunTexts.PROGRESS_UNCHANGED_HELD, "{0} " + RunTexts.MIDDLE_DOT + " {1}: {2} of {3} rows read, {4} written, {5} unchanged, {6} held back.", 2, 3, 4, 5, 6);
		RunTexts.put(RunTexts.PREPARING_MODEL, "Preparing the staging model {0}.");
		RunTexts.put(RunTexts.CREATING_STAGING_MODEL_ONE, "Creating the staging model {0} (1 table): about one second per field.");
		RunTexts.put(RunTexts.CREATING_STAGING_MODEL_OTHER, "Creating the staging model {0} ({1} tables): about one second per field.", 1);
		RunTexts.put(RunTexts.UPDATING_MODEL, "Updating the staging model {0} with the new decisions.");
		RunTexts.put(RunTexts.PUBLISHING_STAGING_MODEL, "Publishing the staging model {0}.");
		RunTexts.put(RunTexts.CREATING_STAGING_DATASET, "Creating the staging dataset {0} in {1}.");
		RunTexts.put(RunTexts.CHECKING_UNIQUE, "{0}: checking that {1} is unique.");
		RunTexts.put(RunTexts.UNIQUE_EXAMPLES_UNAVAILABLE, "{0}: unique check on {1}: examples unavailable ({2}).");
		RunTexts.put(RunTexts.RESOLVING_REFS, "{0}: resolving the references to {1}.");
		RunTexts.put(RunTexts.COMPUTING_FROM, "{0}: computing {1} from the {2} records.");
		RunTexts.put(RunTexts.MARKING_STAGED, "Marking the landing rows as staged.");
		RunTexts.put(RunTexts.SOURCE_READY_PLAN_FAILED, "Ready: application {0} added (source {1}), but planning its landing tables failed: {2}");
		RunTexts.legacy(RunTexts.SOURCE_READY_PLAN_FAILED, "Ready: application {0} added (source {1}), but the landing plan failed: {2}");
		RunTexts.put(RunTexts.SOURCE_READY, "Ready: application {0} added (source {1}).");
		RunTexts.put(RunTexts.PREFLIGHT_CHECKING, "Checking the accepted links on the landing values before any row is written.");
		RunTexts.put(RunTexts.PREFLIGHT_CLEAN, "Check before the build: nothing found.");
		RunTexts.put(RunTexts.PREFLIGHT_WARNINGS, "Check before the build: nothing would be refused, {0} warning(s).", 0);
		RunTexts.put(RunTexts.PREFLIGHT_BLOCKED, "Nothing to do: the build was not started - {0} row(s) on {1} link(s) would be refused. See the check at the top of step 4.", 0, 1);
		RunTexts.put(RunTexts.PREFLIGHT_UNAVAILABLE, "The check before the build could not run ({0}): the build goes on.");
		RunTexts.put(RunTexts.PREFLIGHT_SET_ASIDE, "Build anyway: the check before the build was set aside by a person.");

		RunTexts.put(RunTexts.JOINED, "{0} {1}");
		RunTexts.NESTED.add(RunTexts.JOINED + "#0");
		RunTexts.NESTED.add(RunTexts.JOINED + "#1");
		RunTexts.put(RunTexts.MTC_RESULT_ONE, "{0} entity run, {1} not applied, {2} refused.", 0, 1, 2);
		RunTexts.put(RunTexts.MTC_RESULT_OTHER, "{0} entities run, {1} not applied, {2} refused.", 0, 1, 2);
		RunTexts.put(RunTexts.MTC_FOLLOWS_ONE, "1 child object has no plan of its own: its records follow the matching of the parent.");
		RunTexts.put(RunTexts.MTC_FOLLOWS_OTHER, "{0} child objects have no plan of their own: their records follow the matching of the parent.", 0);
		RunTexts.put(RunTexts.MTC_STOPPED_ONE, "Matching run stopped ({0}): {1} entity run before the stop, {2} not started.", 1, 2);
		RunTexts.put(RunTexts.MTC_STOPPED_OTHER, "Matching run stopped ({0}): {1} entities run before the stop, {2} not started.", 1, 2);
		RunTexts.put(RunTexts.MTC_FINISHED, "Matching run on {0} finished ({1}); Xref.goldenKey refreshed ({2} set, {3} cleared, {4} unchanged). {5}{6}", 2, 3, 4);
		RunTexts.NESTED.add(RunTexts.MTC_FINISHED + "#5");
		RunTexts.put(RunTexts.MTC_SCOPE_ALL, "Scope: all records - the {0} records of the table were pivots.");
		RunTexts.put(RunTexts.MTC_SCOPE_ALL_NO_COUNT, "Scope: all records of the table (the record count was not available).");
		RunTexts.put(RunTexts.MTC_SCOPE_INC, "Scope: incremental - {0} of {1} records were pivots (the last loaded batch); the other {2} were not matched in this run.");
		RunTexts.put(RunTexts.MTC_SCOPE_INC_NO_COUNT, "Scope: incremental - only the most recently loaded batch was matched (the record counts were not available).");
		RunTexts.put(RunTexts.MST_QUEUED, "Queued: waiting for the master zone of this project to be free.");
		RunTexts.put(RunTexts.MST_TABLES_ONE, "1 business object, dataset {0}/{1}.");
		RunTexts.put(RunTexts.MST_TABLES_OTHER, "{0} business objects, dataset {1}/{2}.", 0);
		RunTexts.put(RunTexts.MST_NO_TABLES_COPY, "The master tables were not created: nothing to copy into.");
		RunTexts.put(RunTexts.MST_NO_TABLES_MATCHING, "The master tables were not created.");
		RunTexts.put(RunTexts.MST_DECLARED_ONE, "1 business object declared to Match and Merge.");
		RunTexts.put(RunTexts.MST_DECLARED_OTHER, "{0} business objects declared to Match and Merge.", 0);
		RunTexts.put(RunTexts.MST_RESOLVING_DEFERRED, "{0}: resolving the references left for the end.");
		RunTexts.put(RunTexts.MST_NOTHING_CHANGED_ENTITY, "{0}: nothing changed (empty journal).");
		RunTexts.put(RunTexts.MST_ENTITY_COUNTS, "{0}: {1} row(s) read, {2} written, {3} unchanged.", 1, 2, 3);
		RunTexts.put(RunTexts.MST_ENTITY_COUNTS_HELD, "{0}: {1} row(s) read, {2} written, {3} unchanged, {4} held back.", 1, 2, 3, 4);
		RunTexts.put(RunTexts.MST_COPYING_ONE, "{0}: copying 1 row.");
		RunTexts.put(RunTexts.MST_COPYING_OTHER, "{0}: copying {1} rows.", 1);
		RunTexts.put(RunTexts.MST_PROGRESS, "{0}: {1} rows read, {2} copied.", 1, 2);
		RunTexts.put(RunTexts.MST_PROGRESS_TOTAL, "{0}: {1} of {2} rows read, {3} copied.", 1, 2, 3);
		RunTexts.put(RunTexts.MST_PROGRESS_HELD, "{0}: {1} rows read, {2} copied, {3} held back.", 1, 2, 3);
		RunTexts.put(RunTexts.MST_PROGRESS_TOTAL_HELD, "{0}: {1} of {2} rows read, {3} copied, {4} held back.", 1, 2, 3, 4);
		RunTexts.put(RunTexts.MST_ROWS_WRITTEN_SCOPE, "Rows written: {0}; source rows read: {1} ({2} unchanged, {3} held back; scope {4}, {5} journal row(s)).", 0, 1, 2, 3, 5);
		RunTexts.legacy(RunTexts.MST_ROWS_WRITTEN_SCOPE, "{0} row(s) written from {1} source row(s) read ({2} unchanged, {3} held back; scope {4}, {5} journal row(s)).");
		RunTexts.put(RunTexts.MST_NOTHING_CHANGED, "Nothing changed (scope changed, empty journal, no staging row read).");
		RunTexts.put(RunTexts.MST_BUILD_FINISHED, "Master build finished.");
		RunTexts.put(RunTexts.MST_BUILD_STOPPED, "Master build stopped ({0}).");
		RunTexts.put(RunTexts.MST_BUILD_FAILED_TABLES, "Master build failed: the master tables step did not complete - see the grid below for why.");
		RunTexts.put(RunTexts.MST_BUILD_FAILED_COPY, "Master build failed: the copy step did not complete - see the grid below for why.");
		RunTexts.put(RunTexts.MST_BUILD_FAILED_BOTH, "Master build failed: the master tables step and the copy step did not complete - see the grid below for why.");
		RunTexts.put(RunTexts.MST_BUILD_FAILED, "Master build failed: {0}");

		// a more specific template comes before the generic one it would also match (the parser reads old English in this order)
		RunTexts.put(RunTexts.DST_SENT_UNCHANGED_DETAIL, "Sent {0} row(s), {1} unchanged, across {2} mapping(s), scope={3}: {4}", 0, 1, 2);
		RunTexts.put(RunTexts.DST_SENT_UNCHANGED, "Sent {0} row(s), {1} unchanged, across {2} mapping(s), scope={3}", 0, 1, 2);
		RunTexts.put(RunTexts.DST_SENT_DETAIL, "Sent {0} row(s) across {1} mapping(s), scope={2}: {3}", 0, 1);
		RunTexts.put(RunTexts.DST_SENT, "Sent {0} row(s) across {1} mapping(s), scope={2}", 0, 1);
		for (final String[] kind : new String[][] { { RunTexts.DST_FIRST, "First full send of target" }, { RunTexts.DST_SEND, "Send of target" } })
		{
			// failures first: a failure's text may hold " - " and would read as a done send otherwise
			RunTexts.put(kind[0] + "failed", kind[1] + " {0} failed: {1}");
			RunTexts.NESTED.add(kind[0] + "failed#1");
			RunTexts.put(kind[0] + "failedBare", kind[1] + " {0} failed.");
			RunTexts.put(kind[0] + "ok", kind[1] + " {0}: {1} - {2}");
			RunTexts.NESTED.add(kind[0] + "ok#2");
			RunTexts.put(kind[0] + "okBare", kind[1] + " {0}: {1}.");
		}
		RunTexts.put(RunTexts.FRAG_SCOPE_ALL, "all");
		RunTexts.put(RunTexts.FRAG_SCOPE_CHANGED, "changed");
		RunTexts.put(RunTexts.FRAG_STATUS_DONE, "DONE");
		RunTexts.put(RunTexts.FRAG_STATUS_STOPPED, "STOPPED");
	}

	private RunTexts()
	{
	}

	/** Looks a pattern up in some language; null when the key is unknown there. The text is the bundle's raw pattern. */
	public interface Patterns
	{
		String pattern(String pKey);

		/** A stored text coded by ANOTHER family (a refusal, a key rationale) shown in the language; null when this language cannot word it. */
		default String foreign(final String pStored)
		{
			return null;
		}
	}

	/** The built-in English. */
	public static final Patterns ENGLISH_PATTERNS = new Patterns()
	{
		@Override
		public String pattern(final String pKey)
		{
			return RunTexts.ENGLISH.get(pKey);
		}
	};

	// ----------------------------------------------------------------------------------------------------------- building

	private static boolean isRef(final Object pArg)
	{
		return pArg instanceof String && ((String) pArg).startsWith(RunTexts.P) && ((String) pArg).indexOf(' ') < 0 && RunTexts.ENGLISH.containsKey(pArg);
	}

	/** The English a stored/nested/fragment argument contributes to the English sentence. */
	private static String englishOf(final Object pArg)
	{
		if (RunTexts.isRef(pArg))
		{
			return RunTexts.ENGLISH.get(pArg);
		}
		final String text = pArg == null ? "" : String.valueOf(pArg);
		return EngineMessageCodec.decode(text).raw;
	}

	/**
	 * {@code {n}} is the argument n; {@code {n?one|other}} (a French pattern only: the English of a code uses plain placeholders) is {@code one} when the numeric argument n is 0 or 1
	 * and {@code other} otherwise - "{0} ligne{0?|s} envoy\u00E9e{0?|s}".
	 */
	private static String fill(final String pPattern, final String[] pArgs)
	{
		final Matcher matcher = Pattern.compile("\\{(\\d+)(?:\\?([^|}]*)\\|([^}]*))?\\}").matcher(pPattern);
		final StringBuffer out = new StringBuffer();
		while (matcher.find())
		{
			final int index = Integer.parseInt(matcher.group(1));
			String value;
			if (matcher.group(2) != null)
			{
				value = index < pArgs.length && RunTexts.number(pArgs[index]) <= 1L ? matcher.group(2) : matcher.group(3);
			}
			else
			{
				value = index < pArgs.length ? pArgs[index] : matcher.group();
			}
			matcher.appendReplacement(out, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	private static KeyTexts.Text text(final String pKey, final Object... pArgs)
	{
		final String[] shown = new String[pArgs.length];
		for (int i = 0; i < pArgs.length; i++)
		{
			shown[i] = RunTexts.englishOf(pArgs[i]);
		}
		return new KeyTexts.Text(pKey, pArgs, RunTexts.fill(RunTexts.ENGLISH.get(pKey), shown));
	}

	private static Long n(final long pValue)
	{
		return Long.valueOf(pValue);
	}

	public static KeyTexts.Text loaded()
	{
		return RunTexts.text(RunTexts.LOADED);
	}

	public static KeyTexts.Text built()
	{
		return RunTexts.text(RunTexts.BUILT);
	}

	public static KeyTexts.Text created()
	{
		return RunTexts.text(RunTexts.CREATED);
	}

	public static KeyTexts.Text keyConfirmedLoaded()
	{
		return RunTexts.text(RunTexts.KEY_CONFIRMED_LOADED);
	}

	public static KeyTexts.Text builtLoaded()
	{
		return RunTexts.text(RunTexts.BUILT_LOADED);
	}

	public static KeyTexts.Text createdLoaded()
	{
		return RunTexts.text(RunTexts.CREATED_LOADED);
	}

	public static KeyTexts.Text keyConfirmedBuilt()
	{
		return RunTexts.text(RunTexts.KEY_CONFIRMED_BUILT);
	}

	public static KeyTexts.Text keyChosen()
	{
		return RunTexts.text(RunTexts.KEY_CHOSEN);
	}

	/** Landing key validated in the walkthrough (2026-10-17): "{columns} (validated by a person, unique in the sample)", or "... uniqueness not measured" when the sample could not tell. */
	public static KeyTexts.Text keyValidated(final String pColumnsCsv, final boolean pMeasured)
	{
		return RunTexts.text(pMeasured ? RunTexts.KEY_VALIDATED_CHECKED : RunTexts.KEY_VALIDATED_UNCHECKED, RunTexts.columnsOf(pColumnsCsv));
	}

	/** The failure of the KEY cell when a measure proves the validated key not unique: {@code pSource} true = the whole source, false = the sample. */
	public static KeyTexts.Text keyValidatedNotUnique(final String pColumnsCsv, final boolean pSource)
	{
		return RunTexts.text(pSource ? RunTexts.KEY_VALIDATED_NOT_UNIQUE_SOURCE : RunTexts.KEY_VALIDATED_NOT_UNIQUE_SAMPLE, RunTexts.columnsOf(pColumnsCsv));
	}

	private static String columnsOf(final String pColumnsCsv)
	{
		return pColumnsCsv == null ? "" : pColumnsCsv.trim().replace(",", "+");
	}

	public static KeyTexts.Text stoppedByPerson()
	{
		return RunTexts.text(RunTexts.STOPPED_BY_PERSON);
	}

	/** "Stopped (reason)." - the reason is the engine's own English words ("stopped by admin"), shown as they are. */
	public static KeyTexts.Text stopped(final String pReason)
	{
		return RunTexts.text(RunTexts.STOPPED, pReason == null ? "" : pReason);
	}

	public static KeyTexts.Text waitsForKey()
	{
		return RunTexts.text(RunTexts.WAITS_FOR_KEY);
	}

	public static KeyTexts.Text keyFailedNoFlow()
	{
		return RunTexts.text(RunTexts.KEY_FAILED_NO_FLOW);
	}

	public static KeyTexts.Text tableFailedNoFlow()
	{
		return RunTexts.text(RunTexts.TABLE_FAILED_NO_FLOW);
	}

	public static KeyTexts.Text tableFailedNoLoad()
	{
		return RunTexts.text(RunTexts.TABLE_FAILED_NO_LOAD);
	}

	public static KeyTexts.Text unexpected()
	{
		return RunTexts.text(RunTexts.UNEXPECTED);
	}

	public static KeyTexts.Text noKeyYet()
	{
		return RunTexts.text(RunTexts.NO_KEY_YET);
	}

	public static KeyTexts.Text noColumnCertain()
	{
		return RunTexts.text(RunTexts.NO_COLUMN_CERTAIN);
	}

	public static KeyTexts.Text noPlannedTable()
	{
		return RunTexts.text(RunTexts.NO_PLANNED_TABLE);
	}

	public static KeyTexts.Text tableNotBuilt()
	{
		return RunTexts.text(RunTexts.TABLE_NOT_BUILT);
	}

	public static KeyTexts.Text flowNotCreated()
	{
		return RunTexts.text(RunTexts.FLOW_NOT_CREATED);
	}

	/** "{failed} of {total} table(s) failed ({reason})." - a null/empty reason reads "see detail". */
	public static KeyTexts.Text loadFailed(final int pFailed, final int pTotal, final String pReason)
	{
		final String reason = pReason == null || pReason.isEmpty() ? RunTexts.SEE_DETAIL : pReason;
		return RunTexts.text(pFailed == 1 ? RunTexts.LOAD_FAILED_ONE : RunTexts.LOAD_FAILED_OTHER, RunTexts.n(pFailed), RunTexts.n(pTotal), reason);
	}

	/** "{columns} ({origin})" or "No key." for the KEY cell and the journal; {@code pColumnsCsv} is the comma list, shown joined by "+". */
	public static KeyTexts.Text keySummary(final String pColumnsCsv, final String pOrigin)
	{
		final String columns = pColumnsCsv == null ? "" : pColumnsCsv.trim();
		if (columns.isEmpty())
		{
			return RunTexts.text(RunTexts.KEY_SUMMARY_NONE);
		}
		return RunTexts.text(RunTexts.KEY_SUMMARY + RunTexts.originOf(pOrigin), columns.replace(",", "+"));
	}

	private static String originOf(final String pOrigin)
	{
		return "SOURCE".equals(pOrigin) || "USER".equals(pOrigin) || "DEDUCED".equals(pOrigin) || "LLM".equals(pOrigin) ? pOrigin : "RULE";
	}

	/**
	 * Why a proposed key is not applied, the waiting reason of the KEY cell.
	 *
	 * @param pReason one of the {@code KeyTexts.WHY_*} values
	 * @param pConfidence the proposal's confidence (only {@link KeyTexts#WHY_LLM} shows it)
	 * @param pUniqueAtSource {@code TRUE}/{@code FALSE}/{@code null} (not measured), only for {@link KeyTexts#WHY_LLM}
	 */
	public static KeyTexts.Text waitReason(final String pReason, final int pConfidence, final Boolean pUniqueAtSource)
	{
		if (KeyTexts.WHY_NO_EVIDENCE.equals(pReason))
		{
			return RunTexts.text(RunTexts.WAIT_NO_EVIDENCE);
		}
		if (KeyTexts.WHY_NOT_UNIQUE.equals(pReason))
		{
			return RunTexts.text(RunTexts.WAIT_NOT_UNIQUE);
		}
		if (KeyTexts.WHY_SAMPLE_ONLY.equals(pReason))
		{
			return RunTexts.text(RunTexts.WAIT_SAMPLE_ONLY);
		}
		if (KeyTexts.WHY_LLM.equals(pReason))
		{
			return RunTexts.text(RunTexts.WAIT_LLM, RunTexts.n(pConfidence), pConfidence < KeyCertainty.LLM_CONFIDENCE_MIN ? RunTexts.FRAG_LOW : "",
				Boolean.TRUE.equals(pUniqueAtSource) ? RunTexts.FRAG_SOURCE_UNIQUE : Boolean.FALSE.equals(pUniqueAtSource) ? RunTexts.FRAG_SOURCE_NOT_UNIQUE : "");
		}
		return RunTexts.noColumnCertain();
	}

	/** The KEY cell of a table waiting for a person: "{proposed key} - {reason}". */
	public static KeyTexts.Text keyWait(final KeyTexts.Text pProposed, final KeyTexts.Text pReason)
	{
		return RunTexts.text(RunTexts.KEY_WAIT, pProposed.stored(), pReason.stored());
	}

	public static KeyTexts.Text runAccepted(final int pTables, final int pApplications)
	{
		if (pTables == 1)
		{
			return pApplications == 1 ? RunTexts.text(RunTexts.RUN_ACCEPTED_11) : RunTexts.text(RunTexts.RUN_ACCEPTED_1N, RunTexts.n(pApplications));
		}
		return pApplications == 1 ? RunTexts.text(RunTexts.RUN_ACCEPTED_N1, RunTexts.n(pTables)) : RunTexts.text(RunTexts.RUN_ACCEPTED_NN, RunTexts.n(pTables), RunTexts.n(pApplications));
	}

	public static KeyTexts.Text parallel(final int pBound, final String pProperty)
	{
		return RunTexts.text(RunTexts.PARALLEL, RunTexts.n(pBound), pProperty);
	}

	public static KeyTexts.Text lookingKey(final boolean pThenModel)
	{
		return RunTexts.text(pThenModel ? RunTexts.LOOKING_KEY_LLM : RunTexts.LOOKING_KEY);
	}

	public static KeyTexts.Text buildingTables()
	{
		return RunTexts.text(RunTexts.BUILDING_TABLES);
	}

	public static KeyTexts.Text creatingFlow()
	{
		return RunTexts.text(RunTexts.CREATING_FLOW);
	}

	public static KeyTexts.Text loadingData()
	{
		return RunTexts.text(RunTexts.LOADING_DATA);
	}

	public static KeyTexts.Text creatingDataset(final String pDataset, final String pDataspace)
	{
		return RunTexts.text(RunTexts.CREATING_DATASET, pDataset, pDataspace);
	}

	public static KeyTexts.Text modelReused(final String pModel)
	{
		return RunTexts.text(RunTexts.MODEL_REUSED, pModel);
	}

	public static KeyTexts.Text creatingModel(final String pModel, final int pTables, final int pFields)
	{
		return pTables == 1 ? RunTexts.text(RunTexts.CREATING_MODEL_ONE, pModel, RunTexts.n(pFields))
			: RunTexts.text(RunTexts.CREATING_MODEL_OTHER, pModel, RunTexts.n(pTables), RunTexts.n(pFields));
	}

	public static KeyTexts.Text publishingModel(final String pModel)
	{
		return RunTexts.text(RunTexts.PUBLISHING_MODEL, pModel);
	}

	public static KeyTexts.Text copying(final String pTable, final int pPosition, final int pTables)
	{
		return pTables > 1 ? RunTexts.text(RunTexts.COPYING_OF, pTable, RunTexts.n(pPosition), RunTexts.n(pTables)) : RunTexts.text(RunTexts.COPYING, pTable);
	}

	public static KeyTexts.Text rowsCopied(final String pTable, final long pRows, final boolean pChanged)
	{
		final String key = pChanged ? (pRows == 1 ? RunTexts.CHANGED_ROW_COPIED_ONE : RunTexts.CHANGED_ROW_COPIED_OTHER)
			: pRows == 1 ? RunTexts.ROW_COPIED_ONE : RunTexts.ROW_COPIED_OTHER;
		return RunTexts.text(key, pTable, RunTexts.n(pRows));
	}

	public static KeyTexts.Text copiedSoFar(final String pTable, final long pRows)
	{
		return RunTexts.text(RunTexts.COPIED_SO_FAR, pTable, RunTexts.n(pRows));
	}

	/** "Landing run finished: N of M stages done." */
	public static KeyTexts.Text finished(final int pDone, final int pTotal)
	{
		return RunTexts.text(RunTexts.FINISHED, RunTexts.n(pDone), RunTexts.n(pTotal));
	}

	/** "Landing run finished with failures on: A, B." - the names are the applications. */
	public static KeyTexts.Text finishedFailuresOn(final String pNames)
	{
		return RunTexts.text(RunTexts.FINISHED_FAILURES_ON, pNames);
	}

	/** "Landing run finished with failures: 2 of 4 tables were not loaded (A, B)." */
	public static KeyTexts.Text partial(final int pCount, final int pTotal, final String pNames)
	{
		final String key = pTotal == 1 ? RunTexts.PARTIAL_TABLE_WAS : pCount == 1 ? RunTexts.PARTIAL_TABLES_WAS : RunTexts.PARTIAL_TABLES_WERE;
		return RunTexts.text(key, RunTexts.n(pCount), RunTexts.n(pTotal), pNames);
	}

	public static KeyTexts.Text runStopped(final String pReason)
	{
		return RunTexts.text(RunTexts.RUN_STOPPED, pReason == null ? "" : pReason);
	}

	public static KeyTexts.Text runStoppedByPerson()
	{
		return RunTexts.text(RunTexts.RUN_STOPPED_BY_PERSON);
	}

	public static KeyTexts.Text runPaused()
	{
		return RunTexts.text(RunTexts.RUN_PAUSED);
	}

	public static KeyTexts.Text runLeftRunning()
	{
		return RunTexts.text(RunTexts.RUN_LEFT_RUNNING);
	}

	public static KeyTexts.Text stepGone()
	{
		return RunTexts.text(RunTexts.STEP_GONE);
	}

	public static KeyTexts.Text resumeGone()
	{
		return RunTexts.text(RunTexts.RESUME_GONE);
	}

	// ----------------------------------------------------------------------------------------------------------- staging screens (2026-10-17)

	public static KeyTexts.Text proposalFinished()
	{
		return RunTexts.text(RunTexts.PROPOSAL_FINISHED);
	}

	/** "Proposal run stopped (reason)." - the reason is the engine's own words ("stopped by admin"). */
	public static KeyTexts.Text proposalStopped(final String pReason)
	{
		return RunTexts.text(RunTexts.PROPOSAL_STOPPED, pReason == null ? "" : pReason);
	}

	/** "Proposal run failed: {the exception's message}" - the tail is free text, shown as it is. */
	public static KeyTexts.Text proposalFailed(final String pMessage)
	{
		return RunTexts.text(RunTexts.PROPOSAL_FAILED, pMessage == null ? "" : pMessage);
	}

	public static KeyTexts.Text queuedDesign()
	{
		return RunTexts.text(RunTexts.QUEUED_DESIGN);
	}

	/** The proposal run's "Not run: stopped (reason)." of a stage that a stop request cut off. */
	public static KeyTexts.Text notRunStopped(final String pReason)
	{
		return RunTexts.text(RunTexts.NOT_RUN_STOPPED, pReason == null ? "" : pReason);
	}

	/** The builds' "Not run: reason." of a stage that a stop request cut off. */
	public static KeyTexts.Text notRun(final String pReason)
	{
		return RunTexts.text(RunTexts.NOT_RUN, pReason == null ? "" : pReason);
	}

	public static KeyTexts.Text notRequested()
	{
		return RunTexts.text(RunTexts.NOT_REQUESTED);
	}

	public static KeyTexts.Text notSelected()
	{
		return RunTexts.text(RunTexts.NOT_SELECTED);
	}

	/** Why a pass after the rule pass did not run: {@code pWhat} is one of {@code refine}, {@code review}, {@code field}, {@code object}. */
	public static KeyTexts.Text rulePassIncomplete(final String pWhat)
	{
		return RunTexts.text("refine".equals(pWhat) ? RunTexts.RULE_PASS_REFINE : "review".equals(pWhat) ? RunTexts.RULE_PASS_REVIEW : "field".equals(pWhat) ? RunTexts.RULE_PASS_FIELD
			: RunTexts.RULE_PASS_OBJECT);
	}

	public static KeyTexts.Text noLlmOrAdmin()
	{
		return RunTexts.text(RunTexts.NO_LLM_OR_ADMIN);
	}

	public static KeyTexts.Text buildAccepted()
	{
		return RunTexts.text(RunTexts.BUILD_ACCEPTED);
	}

	public static KeyTexts.Text buildFinished()
	{
		return RunTexts.text(RunTexts.BUILD_FINISHED);
	}

	public static KeyTexts.Text buildStopped(final String pReason)
	{
		return RunTexts.text(RunTexts.BUILD_STOPPED, pReason == null ? "" : pReason);
	}

	/** "Staging build failed: the model step / the load step / both did not complete - see the grid below for why." */
	public static KeyTexts.Text buildFailedStages(final boolean pModelFailed, final boolean pLoadFailed)
	{
		return RunTexts.text(pModelFailed && pLoadFailed ? RunTexts.BUILD_FAILED_BOTH : pModelFailed ? RunTexts.BUILD_FAILED_MODEL : RunTexts.BUILD_FAILED_LOAD);
	}

	/** "Staging build failed: {the exception's message}" - free text tail. */
	public static KeyTexts.Text buildFailed(final String pMessage)
	{
		return RunTexts.text(RunTexts.BUILD_FAILED, pMessage == null ? "" : pMessage);
	}

	public static KeyTexts.Text stagingModelAt(final String pDataspace, final String pDataset)
	{
		return RunTexts.text(RunTexts.STAGING_MODEL_AT, pDataspace, pDataset);
	}

	public static KeyTexts.Text rowsWritten(final long pWritten, final long pRead, final long pUnchanged, final long pHeld)
	{
		return RunTexts.text(RunTexts.ROWS_WRITTEN, RunTexts.n(pWritten), RunTexts.n(pRead), RunTexts.n(pUnchanged), RunTexts.n(pHeld));
	}

	/** "Stopped mid-run: {the stage's summary}" - the summary is a coded text of its own, worded in its turn. */
	public static KeyTexts.Text stoppedMidRun(final String pSummaryStored)
	{
		return RunTexts.text(RunTexts.STOPPED_MID_RUN, pSummaryStored == null ? "" : pSummaryStored);
	}

	/** The journal line of a stage that failed: "Failed: {its summary}". */
	public static KeyTexts.Text failedLine(final String pSummaryStored)
	{
		return RunTexts.text(RunTexts.FAILED_PREFIX, pSummaryStored == null ? "" : pSummaryStored);
	}

	public static KeyTexts.Text reading(final String pEntity, final String pSource, final long pRows)
	{
		return RunTexts.text(pRows == 1L ? RunTexts.READING_ONE : RunTexts.READING_OTHER, pEntity, pSource, RunTexts.n(pRows));
	}

	/** "{entity} . {source}: {read} of {total} rows read, {written} written[, {unchanged} unchanged][, {held} held back]." */
	public static KeyTexts.Text loadProgress(final String pEntity, final String pSource, final long pRead, final long pTotal, final long pWritten, final long pUnchanged, final long pHeld)
	{
		if (pUnchanged > 0L && pHeld > 0L)
		{
			return RunTexts.text(RunTexts.PROGRESS_UNCHANGED_HELD, pEntity, pSource, RunTexts.n(pRead), RunTexts.n(pTotal), RunTexts.n(pWritten), RunTexts.n(pUnchanged), RunTexts.n(pHeld));
		}
		if (pUnchanged > 0L)
		{
			return RunTexts.text(RunTexts.PROGRESS_UNCHANGED, pEntity, pSource, RunTexts.n(pRead), RunTexts.n(pTotal), RunTexts.n(pWritten), RunTexts.n(pUnchanged));
		}
		if (pHeld > 0L)
		{
			return RunTexts.text(RunTexts.PROGRESS_HELD, pEntity, pSource, RunTexts.n(pRead), RunTexts.n(pTotal), RunTexts.n(pWritten), RunTexts.n(pHeld));
		}
		return RunTexts.text(RunTexts.PROGRESS, pEntity, pSource, RunTexts.n(pRead), RunTexts.n(pTotal), RunTexts.n(pWritten));
	}

	public static KeyTexts.Text preparingModel(final String pModel)
	{
		return RunTexts.text(RunTexts.PREPARING_MODEL, pModel);
	}

	public static KeyTexts.Text creatingStagingModel(final String pModel, final int pTables)
	{
		return pTables == 1 ? RunTexts.text(RunTexts.CREATING_STAGING_MODEL_ONE, pModel) : RunTexts.text(RunTexts.CREATING_STAGING_MODEL_OTHER, pModel, RunTexts.n(pTables));
	}

	public static KeyTexts.Text updatingModel(final String pModel)
	{
		return RunTexts.text(RunTexts.UPDATING_MODEL, pModel);
	}

	public static KeyTexts.Text publishingStagingModel(final String pModel)
	{
		return RunTexts.text(RunTexts.PUBLISHING_STAGING_MODEL, pModel);
	}

	public static KeyTexts.Text creatingStagingDataset(final String pDataset, final String pDataspace)
	{
		return RunTexts.text(RunTexts.CREATING_STAGING_DATASET, pDataset, pDataspace);
	}

	public static KeyTexts.Text checkingUnique(final String pEntity, final String pAttribute)
	{
		return RunTexts.text(RunTexts.CHECKING_UNIQUE, pEntity, pAttribute);
	}

	public static KeyTexts.Text uniqueExamplesUnavailable(final String pEntity, final String pAttribute, final String pReason)
	{
		return RunTexts.text(RunTexts.UNIQUE_EXAMPLES_UNAVAILABLE, pEntity, pAttribute, pReason);
	}

	public static KeyTexts.Text resolvingReferences(final String pEntity, final String pTarget)
	{
		return RunTexts.text(RunTexts.RESOLVING_REFS, pEntity, pTarget);
	}

	public static KeyTexts.Text computingFrom(final String pEntity, final String pAttribute, final String pChild)
	{
		return RunTexts.text(RunTexts.COMPUTING_FROM, pEntity, pAttribute, pChild);
	}

	public static KeyTexts.Text markingStaged()
	{
		return RunTexts.text(RunTexts.MARKING_STAGED);
	}

	/** The sources job's end line: "Ready: application X added (source Y)." - with a landing plan that failed, the reason follows (free text). */
	public static KeyTexts.Text sourceReady(final String pApplication, final String pSource, final String pPlanFailure)
	{
		return pPlanFailure == null || pPlanFailure.isEmpty() ? RunTexts.text(RunTexts.SOURCE_READY, pApplication, pSource)
			: RunTexts.text(RunTexts.SOURCE_READY_PLAN_FAILED, pApplication, pSource, pPlanFailure);
	}

	/** Staging preflight lot H1 (2026-10-17): the check before the build starts. */
	public static KeyTexts.Text preflightChecking()
	{
		return RunTexts.text(RunTexts.PREFLIGHT_CHECKING);
	}

	public static KeyTexts.Text preflightClean()
	{
		return RunTexts.text(RunTexts.PREFLIGHT_CLEAN);
	}

	public static KeyTexts.Text preflightWarnings(final long pWarnings)
	{
		return RunTexts.text(RunTexts.PREFLIGHT_WARNINGS, RunTexts.n(pWarnings));
	}

	/** The job's end sentence when the check stopped the build: it starts with "Nothing to do" so the task reads amber, never a green "Done". */
	public static KeyTexts.Text preflightBlocked(final long pRows, final long pLinks)
	{
		return RunTexts.text(RunTexts.PREFLIGHT_BLOCKED, RunTexts.n(pRows), RunTexts.n(pLinks));
	}

	public static KeyTexts.Text preflightUnavailable(final String pReason)
	{
		return RunTexts.text(RunTexts.PREFLIGHT_UNAVAILABLE, pReason == null ? "" : pReason);
	}

	public static KeyTexts.Text preflightSetAside()
	{
		return RunTexts.text(RunTexts.PREFLIGHT_SET_ASIDE);
	}

	// ----------------------------------------------------------------------------------------------- matching and master (2026-10-18)

	/** Two sentences of this class (or free text) as one: "{first} {second}". */
	public static KeyTexts.Text joined(final String pFirstStored, final String pSecondStored)
	{
		return RunTexts.text(RunTexts.JOINED, pFirstStored == null ? "" : pFirstStored, pSecondStored == null ? "" : pSecondStored);
	}

	/** The matching job's result: "2 entities run, 0 not applied, 1 refused." */
	public static KeyTexts.Text matchingResult(final int pRan, final int pNotApplied, final int pRefused)
	{
		return RunTexts.text(pRan == 1 ? RunTexts.MTC_RESULT_ONE : RunTexts.MTC_RESULT_OTHER, RunTexts.n(pRan), RunTexts.n(pNotApplied), RunTexts.n(pRefused));
	}

	/** The child objects the run did not touch because they follow their parent. */
	public static KeyTexts.Text matchingFollows(final int pCount)
	{
		return pCount == 1 ? RunTexts.text(RunTexts.MTC_FOLLOWS_ONE) : RunTexts.text(RunTexts.MTC_FOLLOWS_OTHER, RunTexts.n(pCount));
	}

	/** The first sentence of a stopped matching run (its failures and warnings, free text, follow as joined sentences). */
	public static KeyTexts.Text matchingStopped(final String pReason, final int pRan, final int pNotStarted)
	{
		return RunTexts.text(pRan == 1 ? RunTexts.MTC_STOPPED_ONE : RunTexts.MTC_STOPPED_OTHER, pReason == null ? "" : pReason, RunTexts.n(pRan), RunTexts.n(pNotStarted));
	}

	/** An entity's matching run message: {@code pScopeStored} is {@link #matchingScope}'s text, {@code pTail} the free text that follows it (a parent's pending suspects, a warning), possibly empty. */
	public static KeyTexts.Text matchingFinished(final String pEntity, final String pStatus, final long pSet, final long pCleared, final long pUnchanged, final String pScopeStored,
		final String pTail)
	{
		return RunTexts.text(RunTexts.MTC_FINISHED, pEntity, pStatus == null ? "" : pStatus, RunTexts.n(pSet), RunTexts.n(pCleared), RunTexts.n(pUnchanged),
			pScopeStored == null ? "" : pScopeStored, pTail == null ? "" : pTail);
	}

	/** What a run covered; the English is {@code master.MatchingRunScope#describe}'s, word for word. A count of {@code -1} is "not available". */
	public static KeyTexts.Text matchingScope(final boolean pScopeAll, final long pPivots, final long pTotal)
	{
		if (pScopeAll)
		{
			return pTotal >= 0 ? RunTexts.text(RunTexts.MTC_SCOPE_ALL, RunTexts.thousands(pTotal)) : RunTexts.text(RunTexts.MTC_SCOPE_ALL_NO_COUNT);
		}
		if (pPivots < 0 || pTotal < 0)
		{
			return RunTexts.text(RunTexts.MTC_SCOPE_INC_NO_COUNT);
		}
		return RunTexts.text(RunTexts.MTC_SCOPE_INC, RunTexts.thousands(pPivots), RunTexts.thousands(pTotal), RunTexts.thousands(Math.max(0, pTotal - pPivots)));
	}

	private static String thousands(final long pValue)
	{
		return String.format(java.util.Locale.ROOT, "%,d", Long.valueOf(pValue));
	}

	public static KeyTexts.Text masterQueued()
	{
		return RunTexts.text(RunTexts.MST_QUEUED);
	}

	public static KeyTexts.Text masterTables(final int pObjects, final String pDataspace, final String pDataset)
	{
		return pObjects == 1 ? RunTexts.text(RunTexts.MST_TABLES_ONE, pDataspace, pDataset) : RunTexts.text(RunTexts.MST_TABLES_OTHER, RunTexts.n(pObjects), pDataspace, pDataset);
	}

	public static KeyTexts.Text masterNoTablesCopy()
	{
		return RunTexts.text(RunTexts.MST_NO_TABLES_COPY);
	}

	public static KeyTexts.Text masterNoTablesMatching()
	{
		return RunTexts.text(RunTexts.MST_NO_TABLES_MATCHING);
	}

	public static KeyTexts.Text masterDeclared(final int pObjects)
	{
		return pObjects == 1 ? RunTexts.text(RunTexts.MST_DECLARED_ONE) : RunTexts.text(RunTexts.MST_DECLARED_OTHER, RunTexts.n(pObjects));
	}

	public static KeyTexts.Text masterResolvingDeferred(final String pEntity)
	{
		return RunTexts.text(RunTexts.MST_RESOLVING_DEFERRED, pEntity);
	}

	public static KeyTexts.Text masterNothingChangedEntity(final String pEntity)
	{
		return RunTexts.text(RunTexts.MST_NOTHING_CHANGED_ENTITY, pEntity);
	}

	/** "Personne: 234 row(s) read, 234 written, 0 unchanged[, 3 held back]." */
	public static KeyTexts.Text masterEntityCounts(final String pEntity, final long pRead, final long pWritten, final long pUnchanged, final long pHeld)
	{
		return pHeld > 0L ? RunTexts.text(RunTexts.MST_ENTITY_COUNTS_HELD, pEntity, RunTexts.n(pRead), RunTexts.n(pWritten), RunTexts.n(pUnchanged), RunTexts.n(pHeld))
			: RunTexts.text(RunTexts.MST_ENTITY_COUNTS, pEntity, RunTexts.n(pRead), RunTexts.n(pWritten), RunTexts.n(pUnchanged));
	}

	public static KeyTexts.Text masterCopying(final String pEntity, final long pRows)
	{
		return pRows == 1L ? RunTexts.text(RunTexts.MST_COPYING_ONE, pEntity) : RunTexts.text(RunTexts.MST_COPYING_OTHER, pEntity, RunTexts.n(pRows));
	}

	/** "{entity}: {read}[ of {total}] rows read, {written} copied[, {held} held back]." - {@code pTotal} negative when unknown. */
	public static KeyTexts.Text masterProgress(final String pEntity, final long pRead, final long pTotal, final long pWritten, final long pHeld)
	{
		if (pTotal >= 0L)
		{
			return pHeld > 0L ? RunTexts.text(RunTexts.MST_PROGRESS_TOTAL_HELD, pEntity, RunTexts.n(pRead), RunTexts.n(pTotal), RunTexts.n(pWritten), RunTexts.n(pHeld))
				: RunTexts.text(RunTexts.MST_PROGRESS_TOTAL, pEntity, RunTexts.n(pRead), RunTexts.n(pTotal), RunTexts.n(pWritten));
		}
		return pHeld > 0L ? RunTexts.text(RunTexts.MST_PROGRESS_HELD, pEntity, RunTexts.n(pRead), RunTexts.n(pWritten), RunTexts.n(pHeld))
			: RunTexts.text(RunTexts.MST_PROGRESS, pEntity, RunTexts.n(pRead), RunTexts.n(pWritten));
	}

	/** The copy stage's summary of a load that did not read everything: "Rows written: 12; source rows read: 40 (0 unchanged, 1 held back; scope changed, 40 journal row(s))." */
	public static KeyTexts.Text masterRowsWrittenScope(final long pWritten, final long pRead, final long pUnchanged, final long pHeld, final String pScope, final long pJournalRows)
	{
		return RunTexts.text(RunTexts.MST_ROWS_WRITTEN_SCOPE, RunTexts.n(pWritten), RunTexts.n(pRead), RunTexts.n(pUnchanged), RunTexts.n(pHeld), pScope, RunTexts.n(pJournalRows));
	}

	public static KeyTexts.Text masterNothingChanged()
	{
		return RunTexts.text(RunTexts.MST_NOTHING_CHANGED);
	}

	public static KeyTexts.Text masterBuildFinished()
	{
		return RunTexts.text(RunTexts.MST_BUILD_FINISHED);
	}

	public static KeyTexts.Text masterBuildStopped(final String pReason)
	{
		return RunTexts.text(RunTexts.MST_BUILD_STOPPED, pReason == null ? "" : pReason);
	}

	public static KeyTexts.Text masterBuildFailedStages(final boolean pTablesFailed, final boolean pCopyFailed)
	{
		return RunTexts.text(pTablesFailed && pCopyFailed ? RunTexts.MST_BUILD_FAILED_BOTH : pTablesFailed ? RunTexts.MST_BUILD_FAILED_TABLES : RunTexts.MST_BUILD_FAILED_COPY);
	}

	public static KeyTexts.Text masterBuildFailed(final String pMessage)
	{
		return RunTexts.text(RunTexts.MST_BUILD_FAILED, pMessage == null ? "" : pMessage);
	}


	// ----------------------------------------------------------------------------------------------------------- distribution sends (2026-10-18)

	/** The scope of a send as an argument: the fragment of {@code all} / {@code changed}, any other word as it is. */
	private static String scopeArg(final String pScope)
	{
		return "all".equals(pScope) ? RunTexts.FRAG_SCOPE_ALL : "changed".equals(pScope) ? RunTexts.FRAG_SCOPE_CHANGED : pScope == null ? "" : pScope;
	}

	/**
	 * "Sent 980 row(s) across 3 mapping(s), scope=all[: Personne 212, Adresse 534, Achat 234]" - {@code pDetail} is the per-table counts in the order the send ran, blank
	 * when the caller has none; {@code pUnchanged} is shown only when above zero.
	 */
	public static KeyTexts.Text sent(final long pSent, final long pUnchanged, final int pMappings, final String pScope, final String pDetail)
	{
		final boolean unchanged = pUnchanged > 0L;
		final boolean detail = pDetail != null && !pDetail.trim().isEmpty();
		if (unchanged)
		{
			return detail ? RunTexts.text(RunTexts.DST_SENT_UNCHANGED_DETAIL, RunTexts.n(pSent), RunTexts.n(pUnchanged), RunTexts.n(pMappings), RunTexts.scopeArg(pScope), pDetail.trim())
				: RunTexts.text(RunTexts.DST_SENT_UNCHANGED, RunTexts.n(pSent), RunTexts.n(pUnchanged), RunTexts.n(pMappings), RunTexts.scopeArg(pScope));
		}
		return detail ? RunTexts.text(RunTexts.DST_SENT_DETAIL, RunTexts.n(pSent), RunTexts.n(pMappings), RunTexts.scopeArg(pScope), pDetail.trim())
			: RunTexts.text(RunTexts.DST_SENT, RunTexts.n(pSent), RunTexts.n(pMappings), RunTexts.scopeArg(pScope));
	}

	/** The job's sentence of a send that ended: "Send of target 22: DONE - {message}" ({@code pMessageStored} may be blank: "Send of target 22: DONE."). */
	public static KeyTexts.Text sendEnded(final boolean pFirstFull, final String pTargetId, final String pStatus, final String pMessageStored)
	{
		final String prefix = pFirstFull ? RunTexts.DST_FIRST : RunTexts.DST_SEND;
		final String target = pTargetId == null ? "" : pTargetId;
		final String status = "DONE".equals(pStatus) ? RunTexts.FRAG_STATUS_DONE : "STOPPED".equals(pStatus) ? RunTexts.FRAG_STATUS_STOPPED : pStatus == null ? "?" : pStatus;
		return pMessageStored == null || pMessageStored.isEmpty() ? RunTexts.text(prefix + "okBare", target, status) : RunTexts.text(prefix + "ok", target, status, pMessageStored);
	}

	/** The job's sentence of a send that failed: "Send of target 22 failed: {message}". */
	public static KeyTexts.Text sendFailed(final boolean pFirstFull, final String pTargetId, final String pMessageStored)
	{
		final String prefix = pFirstFull ? RunTexts.DST_FIRST : RunTexts.DST_SEND;
		final String target = pTargetId == null ? "" : pTargetId;
		return pMessageStored == null || pMessageStored.isEmpty() ? RunTexts.text(prefix + "failedBare", target) : RunTexts.text(prefix + "failed", target, pMessageStored);
	}

	/**
	 * How many rows the stored text of a send says it sent (the first number of the "Sent N row(s)..." sentence, coded or old English), or -1 when the text is not such a sentence
	 * (a failure, a stop, nothing sent yet).
	 */
	public static long sentCount(final String pStored)
	{
		if (pStored == null || pStored.isEmpty())
		{
			return -1L;
		}
		final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(pStored);
		String key = null;
		Object[] args = null;
		if (decoded.coded())
		{
			key = decoded.key;
			args = decoded.args;
		}
		else
		{
			final Parsed parsed = RunTexts.parse(decoded.raw);
			if (parsed != null)
			{
				key = parsed.key;
				args = parsed.args;
			}
		}
		if (key == null || args == null || args.length == 0 || !key.startsWith(RunTexts.DST + "sent"))
		{
			return -1L;
		}
		return RunTexts.longOf(args[0]);
	}

	private static final Pattern PIVOTS_ALL = Pattern.compile("the (\\d+) records of the table were pivots");
	private static final Pattern PIVOTS_INCREMENTAL = Pattern.compile("Scope: incremental - (\\d+) of (\\d+) records were pivots");

	/**
	 * The records a matching run of one object took as pivots (the records it compared), read in the entity step's stored message - its scope sentence, coded or old English - or -1
	 * when the message does not say (a run that failed, an older text).
	 */
	public static long pivotCount(final String pStored)
	{
		if (pStored == null || pStored.isEmpty())
		{
			return -1L;
		}
		final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(pStored);
		if (decoded.coded())
		{
			if ((RunTexts.MTC_SCOPE_ALL.equals(decoded.key) || RunTexts.MTC_SCOPE_INC.equals(decoded.key)) && decoded.args.length > 0)
			{
				return RunTexts.longOf(decoded.args[0]);
			}
			if (RunTexts.MTC_FINISHED.equals(decoded.key) && decoded.args.length > 5 && decoded.args[5] instanceof String)
			{
				final long nested = RunTexts.pivotCount((String) decoded.args[5]);
				if (nested >= 0L)
				{
					return nested;
				}
			}
		}
		final Matcher all = RunTexts.PIVOTS_ALL.matcher(decoded.raw);
		if (all.find())
		{
			return RunTexts.number(all.group(1));
		}
		final Matcher incremental = RunTexts.PIVOTS_INCREMENTAL.matcher(decoded.raw);
		return incremental.find() ? RunTexts.number(incremental.group(1)) : -1L;
	}

	private static long longOf(final Object pValue)
	{
		return pValue instanceof Number ? ((Number) pValue).longValue() : pValue == null ? -1L : RunTexts.number(String.valueOf(pValue));
	}

	// ----------------------------------------------------------------------------------------------------------- the journal

	/** The journal's English word for a settled stage/state - {@code KEY}/{@code TABLE}/{@code FLOW}/{@code LOAD}; the code key is {@code ev.stage.*}. */
	static String stageCode(final String pStage)
	{
		return "KEY".equals(pStage) || "TABLE".equals(pStage) || "FLOW".equals(pStage) ? pStage : "LOAD";
	}

	/** The English of a stage name, as the journal has always written it. */
	public static String stageName(final String pStage)
	{
		return RunTexts.ENGLISH.get(RunTexts.EV_STAGE + RunTexts.stageCode(pStage));
	}

	/** The English of what happened to a stage ({@code done}, {@code failed}, {@code eye}, {@code waits}, {@code skipped}), as the journal has always written it. */
	public static String whatName(final String pWhat)
	{
		return RunTexts.ENGLISH.get(RunTexts.EV_WHAT + pWhat);
	}

	// ----------------------------------------------------------------------------------------------------------- showing

	private static String unquote(final String pPattern)
	{
		return pPattern.indexOf('{') >= 0 ? pPattern.replace("''", "'") : pPattern;
	}

	/** The sentence of a code in some language, null for a code this class does not know (or one the language lacks). */
	public static String render(final Patterns pPatterns, final String pKey, final Object[] pArgs)
	{
		if (pKey == null || !pKey.startsWith(RunTexts.P) || !RunTexts.ENGLISH.containsKey(pKey))
		{
			return null;
		}
		final String pattern = pPatterns.pattern(pKey);
		if (pattern == null)
		{
			return null;
		}
		final Object[] args = pArgs == null ? new Object[0] : pArgs;
		final String[] shown = new String[args.length];
		for (int i = 0; i < args.length; i++)
		{
			shown[i] = RunTexts.argText(pPatterns, pKey, i, args[i]);
		}
		return RunTexts.fill(RunTexts.unquote(pattern), shown);
	}

	private static String argText(final Patterns pPatterns, final String pKey, final int pIndex, final Object pArg)
	{
		if (pArg == null)
		{
			return "";
		}
		if (RunTexts.isRef(pArg))
		{
			final String fragment = pPatterns.pattern((String) pArg);
			return fragment == null ? RunTexts.ENGLISH.get(pArg) : RunTexts.unquote(fragment);
		}
		if (!(pArg instanceof String))
		{
			return String.valueOf(pArg);
		}
		final String text = (String) pArg;
		if (EngineMessageCodec.decode(text).coded() || RunTexts.NESTED.contains(pKey + "#" + pIndex))
		{
			return RunTexts.display(text, pPatterns);
		}
		return text;
	}

	/**
	 * A stored text of the run (a cell's summary or activity, a job message) in some language. Coded by this class: worded from the code; coded by another family: whatever
	 * {@link Patterns#foreign} makes of it; no code: the fixed English templates are recognised ({@link #parse}); anything else is free text and comes back as it is, without a marker.
	 */
	public static String display(final String pStored, final Patterns pPatterns)
	{
		if (pStored == null || pStored.isEmpty())
		{
			return "";
		}
		final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(pStored);
		if (decoded.coded())
		{
			if (decoded.key.startsWith(RunTexts.P))
			{
				final String text = RunTexts.render(pPatterns, decoded.key, decoded.args);
				return text == null ? decoded.raw : text;
			}
			final String foreign = pPatterns.foreign(pStored);
			return foreign == null ? decoded.raw : foreign;
		}
		final Parsed parsed = RunTexts.parse(decoded.raw);
		if (parsed != null)
		{
			final String text = RunTexts.render(pPatterns, parsed.key, parsed.args);
			if (text != null)
			{
				return text;
			}
		}
		return decoded.raw;
	}

	private static final Pattern STAGE_EVENT = Pattern.compile("\\A(.+?) " + RunTexts.MIDDLE_DOT + " (Key|Table|Flow|Load) (done|failed|needs your eye|waits for the key|skipped)"
		+ "(?:\\.|: ([\\s\\S]*))\\z", Pattern.DOTALL);
	private static final Pattern ACTIVITY_EVENT = Pattern.compile("\\A(.+?) " + RunTexts.MIDDLE_DOT + " ([\\s\\S]+)\\z", Pattern.DOTALL);

	/**
	 * One line of the journal in some language: "{tables} {dot} {Stage} {what}: {summary}" for a settled stage, "{tables} {dot} {activity}" for what a stage is doing, a note
	 * as is. The summary/activity part is worded like any stored text ({@link #display}); the stage words come from the bundle.
	 */
	public static String displayEvent(final String pStored, final Patterns pPatterns)
	{
		if (pStored == null || pStored.isEmpty())
		{
			return "";
		}
		final EngineMessageCodec.Decoded whole = EngineMessageCodec.decode(pStored);
		final String raw = whole.raw;
		final String marker = pStored.substring(raw.length());
		// staging screens (2026-10-17): a line that IS one sentence of this class (the staging run's own "entity . source: reading 12 rows.", written with its code, or the
		// same English from an older run) is worded as a whole - its dot is part of the sentence, not the landing journal's "table . activity" separator
		final String wholeLine = RunTexts.wholeSentence(whole, raw, pPatterns);
		if (wholeLine != null)
		{
			return wholeLine;
		}
		final Matcher stage = RunTexts.STAGE_EVENT.matcher(raw);
		if (stage.matches())
		{
			final String what = "done".equals(stage.group(3)) ? "done" : "failed".equals(stage.group(3)) ? "failed" : "needs your eye".equals(stage.group(3)) ? "eye"
				: "waits for the key".equals(stage.group(3)) ? "waits" : "skipped";
			final String summary = stage.group(4);
			final Object[] args = new Object[] { stage.group(1), RunTexts.EV_STAGE + stage.group(2).toUpperCase(java.util.Locale.ROOT), RunTexts.EV_WHAT + what,
				summary == null ? "" : RunTexts.display(summary + marker, pPatterns) };
			final String line = RunTexts.render(pPatterns, summary == null ? RunTexts.EV_BARE : RunTexts.EV_WITH, args);
			return line == null ? RunTexts.display(pStored, pPatterns) : line;
		}
		final Matcher activity = RunTexts.ACTIVITY_EVENT.matcher(raw);
		if (activity.matches())
		{
			return activity.group(1) + " " + RunTexts.MIDDLE_DOT + " " + RunTexts.display(activity.group(2) + marker, pPatterns);
		}
		return RunTexts.display(pStored, pPatterns);
	}

	/** The line worded as ONE sentence of this class, or null when it is not one (a landing journal line, free text). Coded: only when the code's own English is the whole line. */
	private static String wholeSentence(final EngineMessageCodec.Decoded pDecoded, final String pRaw, final Patterns pPatterns)
	{
		// only the staging sentences: the landing's generic templates ("{0} (chosen by a person)") would swallow a whole journal line "table . Key done: ..." as their argument
		if (pDecoded.coded() && pDecoded.key.startsWith(RunTexts.STG))
		{
			final String english = RunTexts.render(RunTexts.ENGLISH_PATTERNS, pDecoded.key, pDecoded.args);
			return english != null && english.equals(pRaw) ? RunTexts.render(pPatterns, pDecoded.key, pDecoded.args) : null;
		}
		if (pDecoded.coded())
		{
			return null;
		}
		final Parsed parsed = RunTexts.parse(pRaw);
		return parsed == null || !parsed.key.startsWith(RunTexts.STG) ? null : RunTexts.render(pPatterns, parsed.key, parsed.args);
	}

	// ----------------------------------------------------------------------------------------------------------- reading old English

	/** A sentence recognised in a stored English text: the code and its arguments. */
	public static final class Parsed
	{
		public final String key;
		public final Object[] args;

		Parsed(final String pKey, final Object[] pArgs)
		{
			this.key = pKey;
			this.args = pArgs;
		}
	}

	private static final class Template
	{
		final String key;
		final Pattern pattern;
		final boolean[] numeric;

		Template(final String pKey, final String pEnglish)
		{
			this.key = pKey;
			final Matcher placeholders = Pattern.compile("\\{(\\d+)\\}").matcher(pEnglish);
			final StringBuilder regex = new StringBuilder("\\A");
			final List<Boolean> numerics = new ArrayList<Boolean>();
			int last = 0;
			while (placeholders.find())
			{
				regex.append(Pattern.quote(pEnglish.substring(last, placeholders.start())));
				final boolean number = RunTexts.NUMERIC.contains(pKey + "#" + placeholders.group(1));
				regex.append(number ? "(\\d+)" : "(.+?)");
				numerics.add(Boolean.valueOf(number));
				last = placeholders.end();
			}
			regex.append(Pattern.quote(pEnglish.substring(last))).append("\\z");
			this.pattern = Pattern.compile(regex.toString(), Pattern.DOTALL);
			this.numeric = new boolean[numerics.size()];
			for (int i = 0; i < this.numeric.length; i++)
			{
				this.numeric[i] = numerics.get(i).booleanValue();
			}
		}
	}

	private static final List<Template> TEMPLATES = new ArrayList<Template>();
	private static final Map<String, String> REF_BY_ENGLISH = new LinkedHashMap<String, String>();
	/** English word -> fragment key, for the words a distribution send sentence shows through a fragment (read back only inside {@code dst.*} sentences). */
	private static final Map<String, String> SEND_WORDS = new LinkedHashMap<String, String>();

	static
	{
		RunTexts.SEND_WORDS.put("all", RunTexts.FRAG_SCOPE_ALL);
		RunTexts.SEND_WORDS.put("changed", RunTexts.FRAG_SCOPE_CHANGED);
		RunTexts.SEND_WORDS.put("DONE", RunTexts.FRAG_STATUS_DONE);
		RunTexts.SEND_WORDS.put("STOPPED", RunTexts.FRAG_STATUS_STOPPED);
	}

	static
	{
		for (final Map.Entry<String, String> entry : RunTexts.ENGLISH.entrySet())
		{
			final String key = entry.getKey();
			if (key.startsWith(RunTexts.P + "frag.") || key.equals(RunTexts.SEE_DETAIL))
			{
				// the scope and status words of a distribution send are plain words ("all", "changed", "DONE") that other sentences carry as they are: they are
				// turned into fragments only inside the send sentences (see #sendWord), never everywhere
				if (!RunTexts.SEND_WORDS.containsKey(entry.getValue()) || !RunTexts.SEND_WORDS.get(entry.getValue()).equals(key))
				{
					RunTexts.REF_BY_ENGLISH.put(entry.getValue(), key);
				}
				continue;
			}
			// JOINED ("{0} {1}") would match any text with a space; MTC_FINISHED ends with two adjacent placeholders (a scope sentence and a free tail): neither is read back from old English
			if (key.startsWith(RunTexts.P + "ev.") || key.equals(RunTexts.KEY_WAIT) || key.equals(RunTexts.WAIT_LLM) || key.equals(RunTexts.JOINED) || key.equals(RunTexts.MTC_FINISHED))
			{
				continue;
			}
			RunTexts.TEMPLATES.add(new Template(key, entry.getValue()));
			// the older English of a sentence is tried right after it, before the generic templates that come later (a failure variant before the plain sentence)
			for (final String[] legacy : RunTexts.LEGACY)
			{
				if (legacy[0].equals(key))
				{
					RunTexts.TEMPLATES.add(new Template(legacy[0], legacy[1]));
				}
			}
		}
	}

	private static final Pattern WAIT_LLM_PATTERN = Pattern.compile("\\ASuggested by the language model \\((\\d+)%(, low confidence)?,( unique across the whole source,| NOT unique across the whole source,)?"
		+ " waiting for a person to confirm it\\.\\z");

	/** Recognises a stored English text built by this module (with or without its marker); null when it is none of the fixed templates (free text). */
	public static Parsed parse(final String pEnglish)
	{
		if (pEnglish == null || pEnglish.isEmpty())
		{
			return null;
		}
		final String text = EngineMessageCodec.decode(pEnglish).raw;
		for (final Template template : RunTexts.TEMPLATES)
		{
			final Matcher matcher = template.pattern.matcher(text);
			if (matcher.matches())
			{
				final Object[] args = new Object[matcher.groupCount()];
				for (int i = 0; i < args.length; i++)
				{
					final String value = matcher.group(i + 1);
					args[i] = template.numeric[i] ? (Object) Long.valueOf(RunTexts.number(value)) : RunTexts.REF_BY_ENGLISH.containsKey(value) ? RunTexts.REF_BY_ENGLISH.get(value)
						: template.key.startsWith(RunTexts.DST) && RunTexts.SEND_WORDS.containsKey(value) ? RunTexts.SEND_WORDS.get(value) : value;
				}
				return new Parsed(template.key, args);
			}
		}
		final Matcher llm = RunTexts.WAIT_LLM_PATTERN.matcher(text);
		if (llm.matches())
		{
			return new Parsed(RunTexts.WAIT_LLM, new Object[] { Long.valueOf(RunTexts.number(llm.group(1))), llm.group(2) == null ? "" : RunTexts.FRAG_LOW,
				llm.group(3) == null ? "" : llm.group(3).contains("NOT") ? RunTexts.FRAG_SOURCE_NOT_UNIQUE : RunTexts.FRAG_SOURCE_UNIQUE });
		}
		// "{proposed key} - {reason}": both sides must be sentences of this class
		for (int cut = text.indexOf(" - "); cut >= 0; cut = text.indexOf(" - ", cut + 1))
		{
			final String left = text.substring(0, cut);
			final String right = text.substring(cut + 3);
			final Parsed proposed = RunTexts.parse(left);
			final Parsed reason = RunTexts.parse(right);
			if (proposed != null && reason != null && proposed.key.startsWith(RunTexts.KEY_SUMMARY) && reason.key.startsWith(RunTexts.P)
				&& (reason.key.startsWith(RunTexts.P + "wait.") || RunTexts.NO_COLUMN_CERTAIN.equals(reason.key)))
			{
				return new Parsed(RunTexts.KEY_WAIT, new Object[] { left, right });
			}
		}
		return null;
	}

	private static long number(final String pValue)
	{
		try
		{
			return Long.parseLong(pValue.trim());
		}
		catch (final NumberFormatException ex)
		{
			return 0L;
		}
	}
}
