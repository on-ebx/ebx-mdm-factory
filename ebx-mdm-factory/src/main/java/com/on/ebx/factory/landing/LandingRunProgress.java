package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.wizard.EngineMessageCodec;

/**
 * Sources+landing redesign, lot LANDING (2026-09-24, docs/SOURCES-LANDING-LANDING.md): the JSON progress
 * model of one one-click "Prepare the landing" run - a grid of {@link #STAGES} (columns) by SELECTED
 * landing table (rows), written by {@code ebx.LandingRun} into the parent {@code PipelineStep.detail} it
 * owns ({@code LANDING_RUN}), and read back by the wizard's "while running" screen. Pure: no EBX class, so
 * it is unit-tested directly (round-trip through JSON, cell transitions, {@link #isOver()}, the "n of m
 * stages" count) without a live repository - the same split {@code staging.ProposalProgress} already keeps
 * for the staging-proposal-run lot, extended here from one dimension (a stage) to two (a stage, per table).
 * <p>
 * {@link #STAGES}, in run order: {@link #KEY} (find or confirm the table's key), {@link #TABLE} (the
 * source's own EBX data model/dataset - one per SOURCE, not per table, but reported on every table of that
 * source: see {@code ebx.LandingRun}'s own Javadoc for exactly when it runs), {@link #FLOW} (the connector
 * mapping), {@link #LOAD} (the data copy). Every cell starts {@link #TODO}. {@link #KEY} reaches {@link
 * #WAITING} directly ({@link #waiting}) when the table needs a person; {@link #FLOW}/{@link #LOAD} reach it
 * INDIRECTLY ({@link #waitingStage}, coordinator follow-up round 3, 2026-09-24) whenever they are blocked
 * behind a {@code WAITING} key (their own table's, or - {@link #LOAD} only, which is source-wide - a
 * sibling's) - {@link #SKIPPED} would be wrong there, since the stage WILL still run once the key resolves,
 * it is merely waiting its turn, not permanently skipped. Every {@code WAITING} cell, of any stage, leaves
 * that state only through a person choosing a key (the wizard's key chooser), never through the background
 * run itself.
 * <p>
 * <b>Thread-safe since lot L2 (2026-10-12):</b> the data copy (LOAD) of several sources of a project may run on several threads at once
 * ({@code landing.LandingParallelism}), each reporting into this one grid. A cell is immutable and replaced wholesale in a concurrent map, the
 * journal and the heartbeat are guarded by one lock, and the change listener (the write of the grid into the {@code PipelineStep}) is called OUTSIDE that
 * lock but one caller at a time under {@link #writeLock()}, so a slow write never blocks a report and two writes never cross.
 */
public final class LandingRunProgress
{
	public static final String KEY = "KEY";
	public static final String TABLE = "TABLE";
	public static final String FLOW = "FLOW";
	public static final String LOAD = "LOAD";

	/** Run order - also the column order {@link #toJson()} writes stages in. */
	public static final List<String> STAGES = java.util.Collections
		.unmodifiableList(java.util.Arrays.asList(LandingRunProgress.KEY, LandingRunProgress.TABLE, LandingRunProgress.FLOW, LandingRunProgress.LOAD));

	public static final String TODO = "TODO";
	public static final String RUNNING = "RUNNING";
	public static final String DONE = "DONE";
	public static final String FAILED = "FAILED";
	public static final String SKIPPED = "SKIPPED";
	/** {@link #KEY} only: the table needs a person to choose its key before anything else can proceed for it. */
	public static final String WAITING = "WAITING";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One cell's own state - immutable, {@link TableProgress} replaces it wholesale on every transition. */
	public static final class Cell
	{
		public final String stage;
		public final String state;
		/** One line, or null - a count, an error, or a wait/skip reason; never a paragraph ({@code PipelineStep.detail} is size-capped). */
		public final String summary;
		/**
		 * Live-progress lot (2026-09-26, docs/mockups/landing-live/): when the stage started and ended on this
		 * cell, epoch milliseconds, 0 when unknown (a cell never started, or a grid written before this lot).
		 * They give the running screen its per-cell timer and {@link #typicalDurations} its history.
		 */
		public final long startedAt;
		public final long endedAt;
		/** While {@link #RUNNING}: what the stage is doing right now, one line ("Creating the landing model (24 fields)"), or null. */
		public final String activity;

		public Cell(final String pStage, final String pState, final String pSummary)
		{
			this(pStage, pState, pSummary, 0L, 0L, null);
		}

		public Cell(final String pStage, final String pState, final String pSummary, final long pStartedAt, final long pEndedAt, final String pActivity)
		{
			this.stage = pStage;
			this.state = pState;
			this.summary = pSummary;
			this.startedAt = pStartedAt;
			this.endedAt = pEndedAt;
			this.activity = pActivity;
		}

		/** Milliseconds the stage ran (ended) or has been running (now), 0 when it never started. */
		public long elapsedMs(final long pNow)
		{
			if (this.startedAt <= 0L)
			{
				return 0L;
			}
			return Math.max(0L, (this.endedAt > 0L ? this.endedAt : pNow) - this.startedAt);
		}
	}

	/** One line of the run's activity journal ("What the factory is doing"): epoch milliseconds and a plain-language text. */
	public static final class Event
	{
		public final long at;
		public final String text;

		public Event(final long pAt, final String pText)
		{
			this.at = pAt;
			this.text = pText;
		}
	}

	/** The journal keeps the latest events only: the detail column is size-capped and the screen shows a handful. */
	public static final int MAX_EVENTS = 12;
	private static final int MAX_LINE = 240;

	/** One landing table's row: its identity (for rendering, never re-derived from EBX by this pure class) and its four cells. */
	public static final class TableProgress
	{
		public final String id;
		public final String tableName;
		public final String sourceSystem;
		private final Map<String, Cell> cells = new java.util.concurrent.ConcurrentHashMap<String, Cell>();

		private TableProgress(final String pId, final String pTableName, final String pSourceSystem)
		{
			this.id = pId;
			this.tableName = pTableName;
			this.sourceSystem = pSourceSystem;
			for (final String stage : LandingRunProgress.STAGES)
			{
				this.cells.put(stage, new Cell(stage, LandingRunProgress.TODO, null));
			}
		}

		public Cell cell(final String pStage)
		{
			return this.cells.get(pStage);
		}

		public List<Cell> cells()
		{
			final List<Cell> out = new ArrayList<Cell>();
			for (final String stage : LandingRunProgress.STAGES)
			{
				out.add(this.cells.get(stage));
			}
			return out;
		}
	}

	/** What {@link #newRun} needs about one selected landing table - the caller ({@code ebx.LandingRun}) reads these from EBX once, at the start of a run. */
	public static final class TableRef
	{
		public final String id;
		public final String tableName;
		public final String sourceSystem;

		public TableRef(final String pId, final String pTableName, final String pSourceSystem)
		{
			this.id = pId;
			this.tableName = pTableName;
			this.sourceSystem = pSourceSystem;
		}
	}

	private final List<TableProgress> tables = new ArrayList<TableProgress>();
	private final Map<String, TableProgress> byId = new LinkedHashMap<String, TableProgress>();
	/** Newest first, at most {@link #MAX_EVENTS}. */
	private final List<Event> events = new ArrayList<Event>();
	private volatile long startedAt;
	private volatile long updatedAt;
	private java.util.function.LongSupplier clock = System::currentTimeMillis;
	/** Guards {@link #events}. Never held while the change listener runs. */
	private final Object eventsLock = new Object();
	/** Serialises the change listener (the write of the grid into its step) and the callers that write the grid themselves ({@link #writeLock()}). */
	private final Object writeLock = new Object();
	/**
	 * Live-progress lot: called after every change, so the job that owns this grid can persist it at once
	 * ({@code ebx.LandingRun} writes the {@code PipelineStep.detail}). Before this lot a stage's
	 * {@link #RUNNING} state lived in memory only and the detail was written at the END of the stage, so the
	 * screen showed every cell "To do" for the whole first stage - minutes when the language model is asked.
	 */
	private Runnable onChange;

	private LandingRunProgress()
	{
	}

	/** Tests only: a fixed clock. */
	void clock(final java.util.function.LongSupplier pClock)
	{
		this.clock = pClock;
	}

	public void onChange(final Runnable pListener)
	{
		this.onChange = pListener;
	}

	/**
	 * The lock whoever writes this grid into its {@code PipelineStep} holds while it does (the change listener does; {@code ebx.LandingRun#updateDetail} too):
	 * the JSON is then built and stored by one thread at a time, so the last write is always the newest state. Re-entrant: the listener may call back.
	 */
	public Object writeLock()
	{
		return this.writeLock;
	}

	private void changed()
	{
		this.updatedAt = this.clock.getAsLong();
		final Runnable listener = this.onChange;
		if (listener != null)
		{
			synchronized (this.writeLock)
			{
				try
				{
					listener.run();
				}
				catch (final RuntimeException ex)
				{
					// a failed progress write must never take the run down - the next change writes again
				}
			}
		}
	}

	/** Epoch milliseconds when the run was accepted, 0 when unknown (a grid written before the live-progress lot). */
	public long startedAt()
	{
		return this.startedAt;
	}

	/** Epoch milliseconds of the last change - the screen's "last update" heartbeat. */
	public long updatedAt()
	{
		return this.updatedAt;
	}

	public List<Event> events()
	{
		synchronized (this.eventsLock)
		{
			return new ArrayList<Event>(this.events);
		}
	}

	/** Every cell of every table {@link #TODO} - the shape written the instant a run is accepted, before its background thread has done anything. */
	public static LandingRunProgress newRun(final List<TableRef> pTables)
	{
		return LandingRunProgress.newRun(pTables, System.currentTimeMillis());
	}

	static LandingRunProgress newRun(final List<TableRef> pTables, final long pNow)
	{
		final LandingRunProgress out = new LandingRunProgress();
		for (final TableRef ref : pTables)
		{
			final TableProgress table = new TableProgress(ref.id, ref.tableName, ref.sourceSystem);
			out.tables.add(table);
			out.byId.put(ref.id, table);
		}
		out.startedAt = pNow;
		out.updatedAt = pNow;
		return out;
	}

	public List<TableProgress> tables()
	{
		return new ArrayList<TableProgress>(this.tables);
	}

	public TableProgress table(final String pTableId)
	{
		return this.byId.get(pTableId);
	}

	/** True once no cell is left {@link #TODO}/{@link #RUNNING} - either the whole run finished, or every remaining table is {@link #WAITING} on a person. Distinct from "fully done": see {@link #doneStageCount()}. */
	public boolean isOver()
	{
		for (final TableProgress table : this.tables)
		{
			for (final Cell cell : table.cells.values())
			{
				if (LandingRunProgress.TODO.equals(cell.state) || LandingRunProgress.RUNNING.equals(cell.state))
				{
					return false;
				}
			}
		}
		return true;
	}

	/** True once every cell of every table is {@link #DONE}/{@link #FAILED}/{@link #SKIPPED} - no {@link #WAITING} left either, i.e. nothing left for anyone to do. */
	public boolean isFullyDone()
	{
		for (final TableProgress table : this.tables)
		{
			for (final Cell cell : table.cells.values())
			{
				if (!(LandingRunProgress.DONE.equals(cell.state) || LandingRunProgress.FAILED.equals(cell.state) || LandingRunProgress.SKIPPED.equals(cell.state)))
				{
					return false;
				}
			}
		}
		return true;
	}

	/** True when at least one table's {@link #KEY} cell is {@link #WAITING} right now - drives the running screen's "one table waits for you" banner. */
	public boolean hasWaiting()
	{
		return !this.waitingTableIds().isEmpty();
	}

	public List<String> waitingTableIds()
	{
		final List<String> out = new ArrayList<String>();
		for (final TableProgress table : this.tables)
		{
			if (LandingRunProgress.WAITING.equals(table.cell(LandingRunProgress.KEY).state))
			{
				out.add(table.id);
			}
		}
		return out;
	}

	/** Total number of cells (tables * {@link #STAGES}.size()) - the "of m" in "n of m stages". */
	public int totalStageCount()
	{
		return this.tables.size() * LandingRunProgress.STAGES.size();
	}

	/** Cells {@link #DONE}/{@link #FAILED}/{@link #SKIPPED} - {@link #WAITING}/{@link #TODO}/{@link #RUNNING} are not counted as finished, since a {@link #WAITING} cell still needs a person and a table's later stages are blocked behind it. The "n" in "n of m stages". */
	public int doneStageCount()
	{
		int n = 0;
		for (final TableProgress table : this.tables)
		{
			for (final Cell cell : table.cells.values())
			{
				if (LandingRunProgress.DONE.equals(cell.state) || LandingRunProgress.FAILED.equals(cell.state) || LandingRunProgress.SKIPPED.equals(cell.state))
				{
					n++;
				}
			}
		}
		return n;
	}

	// ------------------------------------------------------------------------------------------------- transitions

	public void start(final String pTableId, final String pStage)
	{
		this.start(pTableId, pStage, null);
	}

	/** Starts the stage and says what it does - the activity also goes to the journal. */
	public void start(final String pTableId, final String pStage, final String pActivity)
	{
		this.startAll(java.util.Collections.singletonList(pTableId), pStage, pActivity);
	}

	/**
	 * Starts a SOURCE-wide stage ({@link #TABLE}, {@link #LOAD}) on every table of that source with ONE journal
	 * line, not one per table: the stage is one piece of work reported on several rows.
	 */
	public void startAll(final List<String> pTableIds, final String pStage, final String pActivity)
	{
		final long now = this.clock.getAsLong();
		for (final String tableId : pTableIds)
		{
			this.put(tableId, new Cell(pStage, LandingRunProgress.RUNNING, null, now, 0L, LandingRunProgress.line(pActivity)));
		}
		this.journal(pTableIds, pActivity);
		this.changed();
	}

	/** What a {@link #RUNNING} stage is doing now; ignored on a cell that is not running. Goes to the journal. */
	public void activity(final String pTableId, final String pStage, final String pActivity)
	{
		this.activityAll(java.util.Collections.singletonList(pTableId), pStage, pActivity);
	}

	public void activityAll(final List<String> pTableIds, final String pStage, final String pActivity)
	{
		final List<String> running = new ArrayList<String>();
		for (final String tableId : pTableIds)
		{
			final TableProgress table = this.byId.get(tableId);
			if (table == null || !LandingRunProgress.RUNNING.equals(table.cell(pStage).state))
			{
				continue;
			}
			final Cell cell = table.cell(pStage);
			table.cells.put(pStage, new Cell(pStage, cell.state, cell.summary, cell.startedAt, 0L, LandingRunProgress.line(pActivity)));
			running.add(tableId);
		}
		if (running.isEmpty())
		{
			return;
		}
		this.journal(running, pActivity);
		this.changed();
	}

	/** A journal line that belongs to no cell ("Run accepted: 2 tables from 2 applications."). */
	public void note(final String pText)
	{
		this.addEvent(pText);
		this.changed();
	}

	public void finish(final String pTableId, final String pStage, final boolean pOk, final String pSummary)
	{
		this.set(pTableId, pStage, pOk ? LandingRunProgress.DONE : LandingRunProgress.FAILED, pSummary);
	}

	public void skip(final String pTableId, final String pStage, final String pReason)
	{
		this.set(pTableId, pStage, LandingRunProgress.SKIPPED, pReason);
	}

	/** {@link #KEY} only: the table needs a person - the run leaves it here and moves on to other tables/other sources. */
	public void waiting(final String pTableId, final String pReason)
	{
		this.set(pTableId, LandingRunProgress.KEY, LandingRunProgress.WAITING, pReason);
	}

	/**
	 * Coordinator follow-up round 3 (2026-09-24): a DOWNSTREAM stage ({@link #FLOW}/{@link #LOAD}) that
	 * cannot run yet because it is blocked behind a {@link #WAITING} {@link #KEY} (its own table's, or - for
	 * {@link #LOAD}, which is source-wide - a sibling table's) is ALSO {@link #WAITING}, never {@link
	 * #SKIPPED}: {@link #SKIPPED} means "this stage will not run in this pass", which is false here - it
	 * WILL run, the moment the key resolves ({@code ebx.LandingRun#applyKeyAndResume}). {@link #isOver}/{@link
	 * #doneStageCount} already treat {@link #WAITING} as "not settled/not done" exactly like this needs, so
	 * no other method on this class needed to change.
	 */
	public void waitingStage(final String pTableId, final String pStage, final String pReason)
	{
		this.set(pTableId, pStage, LandingRunProgress.WAITING, pReason);
	}

	/**
	 * A settling transition keeps the start time of the cell and stamps the end - only when the cell had actually
	 * started, so a stage skipped without ever running has no duration. A settled cell goes to the journal
	 * ("Personnes_SAP_Sample · Key: PARTNER_ID (declared by the source)").
	 */
	private void set(final String pTableId, final String pStage, final String pState, final String pSummary)
	{
		final TableProgress table = this.byId.get(pTableId);
		if (table == null)
		{
			return; // defensive: a stale reference to a table no longer in this run - never throw from a progress update
		}
		final Cell previous = table.cell(pStage);
		final long started = previous == null ? 0L : previous.startedAt;
		final long ended = started > 0L ? this.clock.getAsLong() : 0L;
		table.cells.put(pStage, new Cell(pStage, pState, pSummary, started, ended, null));
		if (!LandingRunProgress.TODO.equals(pState) && (started > 0L || !LandingRunProgress.SKIPPED.equals(pState)))
		{
			final String what = LandingRunProgress.FAILED.equals(pState) ? "failed"
				: LandingRunProgress.WAITING.equals(pState) ? (LandingRunProgress.KEY.equals(pStage) ? "eye" : "waits")
					: LandingRunProgress.SKIPPED.equals(pState) ? "skipped" : "done";
			// run panel i18n (2026-10-17): the line is still the English sentence; a summary that carries a code keeps its marker at the end of the line
			// (RunTexts.displayEvent words the stage and the summary in the viewer's language)
			this.addEvent(table.tableName + " \u00B7 " + LandingRunProgress.stageName(pStage) + " " + RunTexts.whatName(what)
				+ (pSummary == null || pSummary.isEmpty() ? "." : ": " + pSummary));
		}
		this.changed();
	}

	private void put(final String pTableId, final Cell pCell)
	{
		final TableProgress table = this.byId.get(pTableId);
		if (table != null)
		{
			table.cells.put(pCell.stage, pCell);
		}
	}

	private void journal(final List<String> pTableIds, final String pActivity)
	{
		if (pActivity == null || pActivity.trim().isEmpty())
		{
			return;
		}
		final List<String> names = new ArrayList<String>();
		for (final String tableId : pTableIds)
		{
			final TableProgress table = this.byId.get(tableId);
			if (table != null)
			{
				names.add(table.tableName);
			}
		}
		this.addEvent((names.isEmpty() ? "" : String.join(", ", names) + " \u00B7 ") + pActivity);
	}

	private void addEvent(final String pText)
	{
		if (pText == null || pText.trim().isEmpty())
		{
			return;
		}
		synchronized (this.eventsLock)
		{
			this.events.add(0, new Event(this.clock.getAsLong(), LandingRunProgress.line(pText)));
			while (this.events.size() > LandingRunProgress.MAX_EVENTS)
			{
				this.events.remove(this.events.size() - 1);
			}
		}
	}

	/**
	 * One line: no line breaks, the English text capped at {@link #MAX_LINE} characters. Run panel i18n (2026-10-17): the code marker a stored text may end with is never
	 * cut, and does not count towards the cap (the text gives way to nothing: a reader that ignores the marker still sees the cut English).
	 */
	private static String line(final String pText)
	{
		if (pText == null)
		{
			return null;
		}
		final String oneLine = pText.replace('\n', ' ').replace('\r', ' ').trim();
		final String raw = EngineMessageCodec.decode(oneLine).raw;
		final String marker = oneLine.substring(raw.length());
		return (raw.length() <= LandingRunProgress.MAX_LINE ? raw : raw.substring(0, LandingRunProgress.MAX_LINE - 1) + "\u2026") + marker;
	}

	/** English stage name for the journal (the grid's column titles are the localized ones). */
	static String stageName(final String pStage)
	{
		return RunTexts.stageName(pStage);
	}

	// ------------------------------------------------------------------------------------------------ estimates

	/**
	 * Live-progress lot: the typical duration of each stage, from finished runs - the median of the cells that
	 * ended {@link #DONE} with both times known. A stage with no sample is absent (no estimate for it). The
	 * source-wide stages ({@link #TABLE}, {@link #LOAD}) are counted once per source and run, not once per table
	 * row that reports them.
	 */
	public static Map<String, Long> typicalDurations(final List<LandingRunProgress> pPastRuns)
	{
		final Map<String, List<Long>> samples = new LinkedHashMap<String, List<Long>>();
		for (final LandingRunProgress run : pPastRuns)
		{
			final java.util.Set<String> seenUnits = new java.util.HashSet<String>();
			for (final TableProgress table : run.tables)
			{
				for (final String stage : LandingRunProgress.STAGES)
				{
					final Cell cell = table.cell(stage);
					if (!LandingRunProgress.DONE.equals(cell.state) || cell.startedAt <= 0L || cell.endedAt < cell.startedAt)
					{
						continue;
					}
					if (!seenUnits.add(stage + "|" + LandingRunProgress.unitOf(table, stage)))
					{
						continue;
					}
					List<Long> list = samples.get(stage);
					if (list == null)
					{
						list = new ArrayList<Long>();
						samples.put(stage, list);
					}
					list.add(Long.valueOf(cell.endedAt - cell.startedAt));
				}
			}
		}
		final Map<String, Long> out = new LinkedHashMap<String, Long>();
		for (final Map.Entry<String, List<Long>> entry : samples.entrySet())
		{
			final List<Long> list = entry.getValue();
			java.util.Collections.sort(list);
			out.put(entry.getKey(), list.get(list.size() / 2));
		}
		return out;
	}

	/** A cell's unit of work: the table for {@link #KEY}/{@link #FLOW}, its source for {@link #TABLE}/{@link #LOAD}. */
	private static String unitOf(final TableProgress pTable, final String pStage)
	{
		return LandingRunProgress.TABLE.equals(pStage) || LandingRunProgress.LOAD.equals(pStage) ? "S:" + pTable.sourceSystem : "T:" + pTable.id;
	}

	/** Overall percent (a running stage counts for the share of its typical duration already spent, at most 90 %) and the time left, null when unknown. */
	public static final class Estimate
	{
		public final int percent;
		public final Long remainingMs;

		Estimate(final int pPercent, final Long pRemainingMs)
		{
			this.percent = pPercent;
			this.remainingMs = pRemainingMs;
		}
	}

	/**
	 * Mirrored by {@code wizard.js} (the live panel recomputes it every second between two polls): units of work
	 * as in {@link #typicalDurations}; a unit with a cell {@link #RUNNING} is running, else one {@link #TODO} is to
	 * do, else it is settled. {@link #WAITING} units wait for a person: counted in the total, never in the time
	 * left. The time left is unknown as soon as one unit still to run has no typical duration.
	 */
	public Estimate estimate(final Map<String, Long> pTypical, final long pNow)
	{
		final Map<String, String> unitState = new LinkedHashMap<String, String>();
		final Map<String, Long> unitStart = new LinkedHashMap<String, Long>();
		for (final TableProgress table : this.tables)
		{
			for (final String stage : LandingRunProgress.STAGES)
			{
				final Cell cell = table.cell(stage);
				final String unit = stage + "|" + LandingRunProgress.unitOf(table, stage);
				final String previous = unitState.get(unit);
				final String state = LandingRunProgress.RUNNING.equals(cell.state) ? LandingRunProgress.RUNNING
					: LandingRunProgress.TODO.equals(cell.state) ? LandingRunProgress.TODO
						: LandingRunProgress.WAITING.equals(cell.state) ? LandingRunProgress.WAITING : LandingRunProgress.DONE;
				if (previous == null || LandingRunProgress.rank(state) > LandingRunProgress.rank(previous))
				{
					unitState.put(unit, state);
				}
				if (LandingRunProgress.RUNNING.equals(cell.state) && cell.startedAt > 0L)
				{
					unitStart.put(unit, Long.valueOf(cell.startedAt));
				}
			}
		}
		if (unitState.isEmpty())
		{
			return new Estimate(0, null);
		}
		double done = 0d;
		long remaining = 0L;
		boolean known = true;
		for (final Map.Entry<String, String> entry : unitState.entrySet())
		{
			final String stage = entry.getKey().substring(0, entry.getKey().indexOf('|'));
			final Long typical = pTypical == null ? null : pTypical.get(stage);
			final String state = entry.getValue();
			if (LandingRunProgress.DONE.equals(state))
			{
				done += 1d;
			}
			else if (LandingRunProgress.RUNNING.equals(state))
			{
				final Long start = unitStart.get(entry.getKey());
				final long elapsed = start == null ? 0L : Math.max(0L, pNow - start.longValue());
				if (typical == null || typical.longValue() <= 0L)
				{
					known = false;
					done += 0.3d;
				}
				else
				{
					done += Math.min(0.9d, (double) elapsed / typical.longValue());
					remaining += Math.max(0L, typical.longValue() - elapsed);
				}
			}
			else if (LandingRunProgress.TODO.equals(state))
			{
				if (typical == null)
				{
					known = false;
				}
				else
				{
					remaining += typical.longValue();
				}
			}
		}
		final int percent = (int) Math.round(done * 100d / unitState.size());
		return new Estimate(Math.max(0, Math.min(100, percent)), known ? Long.valueOf(remaining) : null);
	}

	private static int rank(final String pState)
	{
		return LandingRunProgress.RUNNING.equals(pState) ? 3 : LandingRunProgress.TODO.equals(pState) ? 2 : LandingRunProgress.WAITING.equals(pState) ? 1 : 0;
	}

	/** The cell running right now whose stage started last (the headline "Now: ..." line), or null. */
	public Cell currentCell()
	{
		Cell best = null;
		for (final TableProgress table : this.tables)
		{
			for (final Cell cell : table.cells.values())
			{
				if (LandingRunProgress.RUNNING.equals(cell.state) && (best == null || cell.startedAt > best.startedAt))
				{
					best = cell;
				}
			}
		}
		return best;
	}

	/** The table a cell of {@link #currentCell} belongs to (by identity), or null. */
	public TableProgress tableOf(final Cell pCell)
	{
		for (final TableProgress table : this.tables)
		{
			if (table.cells.containsValue(pCell))
			{
				return table;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------------------ JSON

	public String toJson()
	{
		final ObjectNode root = LandingRunProgress.MAPPER.createObjectNode();
		if (this.startedAt > 0L)
		{
			root.put("startedAt", this.startedAt);
		}
		if (this.updatedAt > 0L)
		{
			root.put("updatedAt", this.updatedAt);
		}
		final ArrayNode eventsNode = root.putArray("events");
		for (final Event event : this.events())
		{
			eventsNode.addObject().put("at", event.at).put("text", event.text);
		}
		final ArrayNode tablesNode = root.putArray("tables");
		for (final TableProgress table : this.tables)
		{
			final ObjectNode tableNode = tablesNode.addObject();
			tableNode.put("id", table.id);
			tableNode.put("tableName", table.tableName);
			tableNode.put("sourceSystem", table.sourceSystem);
			final ObjectNode cellsNode = tableNode.putObject("cells");
			for (final String stage : LandingRunProgress.STAGES)
			{
				final Cell cell = table.cells.get(stage);
				final ObjectNode cellNode = cellsNode.putObject(stage);
				cellNode.put("state", cell.state);
				if (cell.summary != null)
				{
					cellNode.put("summary", cell.summary);
				}
				if (cell.startedAt > 0L)
				{
					cellNode.put("startedAt", cell.startedAt);
				}
				if (cell.endedAt > 0L)
				{
					cellNode.put("endedAt", cell.endedAt);
				}
				if (cell.activity != null)
				{
					cellNode.put("activity", cell.activity);
				}
			}
		}
		return root.toString();
	}

	/** Never throws: a null/blank/unparsable {@code pJson} (no run yet, or a corrupted/truncated detail) returns an empty run (no tables). */
	public static LandingRunProgress parse(final String pJson)
	{
		final LandingRunProgress out = new LandingRunProgress();
		if (pJson == null || pJson.trim().isEmpty())
		{
			return out;
		}
		final JsonNode root;
		try
		{
			root = LandingRunProgress.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			return out;
		}
		out.startedAt = root.path("startedAt").asLong(0L);
		out.updatedAt = root.path("updatedAt").asLong(0L);
		for (final JsonNode eventNode : root.path("events"))
		{
			if (out.events.size() < LandingRunProgress.MAX_EVENTS && eventNode.hasNonNull("text"))
			{
				out.events.add(new Event(eventNode.path("at").asLong(0L), eventNode.path("text").asText()));
			}
		}
		final JsonNode tablesNode = root.path("tables");
		if (!tablesNode.isArray())
		{
			return out;
		}
		for (final JsonNode tableNode : tablesNode)
		{
			final String id = tableNode.path("id").asText(null);
			if (id == null)
			{
				continue;
			}
			final TableProgress table = new TableProgress(id, tableNode.path("tableName").asText(""), tableNode.path("sourceSystem").asText(""));
			final JsonNode cellsNode = tableNode.path("cells");
			for (final String stage : LandingRunProgress.STAGES)
			{
				final JsonNode cellNode = cellsNode.path(stage);
				if (cellNode.isMissingNode())
				{
					continue;
				}
				final String state = cellNode.path("state").asText(LandingRunProgress.TODO);
				final String summary = cellNode.hasNonNull("summary") ? cellNode.path("summary").asText() : null;
				final String activity = cellNode.hasNonNull("activity") ? cellNode.path("activity").asText() : null;
				table.cells.put(stage, new Cell(stage, state, summary, cellNode.path("startedAt").asLong(0L), cellNode.path("endedAt").asLong(0L), activity));
			}
			out.tables.add(table);
			out.byId.put(id, table);
		}
		return out;
	}
}
