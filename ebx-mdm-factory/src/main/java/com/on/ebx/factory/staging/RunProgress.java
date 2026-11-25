package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Wizard-visual-pass lot (2026-09-24, docs/WIZARD-VISUAL.md "step 4e/step 5"): a generic version of {@link
 * ProposalProgress} - same JSON shape and state machine (a stage is {@code TODO}/{@code RUNNING}/{@code
 * DONE}/{@code FAILED}/{@code SKIPPED}, with {@code startedAt}/{@code endedAt}/{@code summary}), but the
 * ordered list of stage keys is a constructor argument instead of a hardcoded static list - {@link
 * ProposalProgress#STAGES} is fixed to the six staging-proposal stages, which does not fit a 2-stage
 * "generate + load the staging" run ({@code ebx.StagingLoadRun}) or a 3-stage "build the master" run
 * ({@code ebx.MasterBuildRun}). Both reuse this class rather than each carrying a private copy of the same
 * state machine.
 * <p>
 * Self-describing on the wire: {@link #toJson()} writes the stage keys themselves (not just their states),
 * so {@link #parse(String, List)} can rebuild the exact same order from the JSON alone - the {@code
 * pFallbackStageKeys} argument is only used when {@code pJson} is null/blank/unparsable (no run yet, or a
 * corrupted/truncated {@code PipelineStep.detail}), exactly like {@link ProposalProgress#parse}'s own "never
 * throws, falls back to {@link ProposalProgress#newRun}" contract.
 * <p>
 * Pure: no EBX class anywhere in this file, unit-tested directly (round-trip through JSON, stage ordering,
 * state transitions, the fallback path) without a live repository.
 */
public final class RunProgress
{
	public static final String TODO = "TODO";
	public static final String RUNNING = "RUNNING";
	public static final String DONE = "DONE";
	public static final String FAILED = "FAILED";
	public static final String SKIPPED = "SKIPPED";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One stage's own state - immutable, {@link RunProgress} replaces it wholesale on every transition. Same shape as {@code ProposalProgress.Stage}. */
	public static final class Stage
	{
		public final String key;
		public final String state;
		/** Epoch millis, or null before the stage starts. */
		public final Long startedAt;
		/** Epoch millis, or null while {@link #RUNNING}/{@link #TODO}. */
		public final Long endedAt;
		/** One line, or null - a count, an error, or a skip reason; never a paragraph (the parent {@code PipelineStep.detail} is size-capped). */
		public final String summary;
		/** Staging-live lot (2026-09-27): while {@link #RUNNING}, what the stage does right now, one line, or null. */
		public final String activity;

		public Stage(final String pKey, final String pState, final Long pStartedAt, final Long pEndedAt, final String pSummary)
		{
			this(pKey, pState, pStartedAt, pEndedAt, pSummary, null);
		}

		public Stage(final String pKey, final String pState, final Long pStartedAt, final Long pEndedAt, final String pSummary, final String pActivity)
		{
			this.key = pKey;
			this.state = pState;
			this.startedAt = pStartedAt;
			this.endedAt = pEndedAt;
			this.summary = pSummary;
			this.activity = pActivity;
		}
	}

	/**
	 * Staging-live lot (2026-09-27, docs/mockups/staging-live/): one cell of the run's grid - a business object
	 * ({@link #row}) fed by one application ({@link #col}). Counters are real rows ({@code StagingLoader} writes
	 * in batches of its own and reports after each one); {@link #total} is the landing rows to read, -1 when not
	 * known (a child entity reached through its parent's rows).
	 */
	public static final class Unit
	{
		public final String row;
		public final String col;
		public final String state;
		public final long total;
		public final long read;
		public final long written;
		/** Lot V3-S (2026-10-04): rows read, compared and found already up to date - not written. 0 for a load that has no such notion (landing, master copy before the lot) and for a detail recorded before it. */
		public final long unchanged;
		public final long held;
		public final long startedAt;
		public final long endedAt;

		public Unit(final String pRow, final String pCol, final String pState, final long pTotal, final long pRead, final long pWritten, final long pHeld,
			final long pStartedAt, final long pEndedAt)
		{
			this(pRow, pCol, pState, pTotal, pRead, pWritten, 0L, pHeld, pStartedAt, pEndedAt);
		}

		public Unit(final String pRow, final String pCol, final String pState, final long pTotal, final long pRead, final long pWritten, final long pUnchanged,
			final long pHeld, final long pStartedAt, final long pEndedAt)
		{
			this.unchanged = pUnchanged;
			this.row = pRow;
			this.col = pCol;
			this.state = pState;
			this.total = pTotal;
			this.read = pRead;
			this.written = pWritten;
			this.held = pHeld;
			this.startedAt = pStartedAt;
			this.endedAt = pEndedAt;
		}
	}

	/** One line of the run's journal: epoch milliseconds and plain-language text. */
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

	/** Progress-throttle fix (2026-09-27): the jobs' routine progress writes, at most one per this interval (the page polls every 3 s). */
	public static final long LIVE_WRITE_INTERVAL_MS = 1500L;

	/** The journal keeps the latest events only (the detail column is size-capped). */
	public static final int MAX_EVENTS = 12;
	private static final int MAX_LINE = 240;

	private final List<String> stageKeys;
	private final Map<String, Stage> byKey = new LinkedHashMap<String, Stage>();
	private final Map<String, Unit> units = new LinkedHashMap<String, Unit>();
	/** Newest first, at most {@link #MAX_EVENTS}. */
	private final List<Event> events = new ArrayList<Event>();
	private long startedAt;
	private long updatedAt;
	private java.util.function.LongSupplier clock = System::currentTimeMillis;
	/** Staging-live lot: called after every change, so the job persists the grid at once and the page's poll sees it. */
	private Runnable onChange;
	/**
	 * Progress-throttle fix (2026-09-27, measured live on RET's master copy): the listener is called at most once
	 * per this many milliseconds for a routine change (an activity line, a batch's counters, a journal note); a
	 * transition (a stage or a cell starting or ending) always goes through at once, and the next call always
	 * carries the whole state, so nothing is lost. 0 = every change (the default, and what tests use).
	 */
	private long minIntervalMs;
	private long lastFiredAt;
	private boolean pending;

	private RunProgress(final List<String> pStageKeys)
	{
		this.stageKeys = new ArrayList<String>(pStageKeys);
	}

	/** Tests only: a fixed clock. */
	void clock(final java.util.function.LongSupplier pClock)
	{
		this.clock = pClock;
	}

	public void onChange(final Runnable pListener)
	{
		this.onChange(pListener, 0L);
	}

	/**
	 * Same, but a routine change reaches {@code pListener} at most once per {@code pMinIntervalMs}; a
	 * transition always does at once (see {@link #minIntervalMs}). A job writing its progress to the factory
	 * dataset on every batch competes with the page reading and writing the same dataset: live on RET's master
	 * copy, 24 progress writes in 15 s, while the wizard's own postback came back only after the copy.
	 */
	public void onChange(final Runnable pListener, final long pMinIntervalMs)
	{
		this.onChange = pListener;
		this.minIntervalMs = Math.max(0L, pMinIntervalMs);
	}

	private void changed()
	{
		this.changed(false);
	}

	private void changed(final boolean pTransition)
	{
		this.updatedAt = this.clock.getAsLong();
		if (this.onChange == null)
		{
			return;
		}
		if (!pTransition && this.minIntervalMs > 0L && this.updatedAt - this.lastFiredAt < this.minIntervalMs)
		{
			// written with the next transition or the next change past the interval; the job's own end
			// (PipelineSteps.end with toJson()) writes the whole state anyway
			this.pending = true;
			return;
		}
		this.fire();
	}

	private void fire()
	{
		this.lastFiredAt = this.updatedAt;
		this.pending = false;
		try
		{
			this.onChange.run();
		}
		catch (final RuntimeException ex)
		{
			// a failed progress write must never take the run down - the next change writes again
		}
	}

	/** Epoch milliseconds when the run was accepted, 0 when unknown (a detail written before the staging-live lot). */
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
		return new ArrayList<Event>(this.events);
	}

	public List<Unit> units()
	{
		return new ArrayList<Unit>(this.units.values());
	}

	public Unit unit(final String pRow, final String pCol)
	{
		return this.units.get(pRow + "\u0000" + pCol);
	}

	/** A journal line that belongs to no stage or cell. */
	public void note(final String pText)
	{
		this.addEvent(pText);
		this.changed();
	}

	/** What a {@link #RUNNING} stage does now - ignored on a stage that is not running; goes to the journal. */
	public void activity(final String pKey, final String pText)
	{
		final Stage stage = this.byKey.get(pKey);
		if (stage == null || !RunProgress.RUNNING.equals(stage.state))
		{
			return;
		}
		this.byKey.put(pKey, new Stage(pKey, stage.state, stage.startedAt, null, null, RunProgress.line(pText)));
		this.addEvent(pText);
		this.changed();
	}

	/** Declares a cell before the load reaches it, so the grid shows every business object x application from the start. */
	public void unitPlanned(final String pRow, final String pCol)
	{
		if (this.unit(pRow, pCol) == null)
		{
			this.units.put(pRow + "\u0000" + pCol, new Unit(pRow, pCol, RunProgress.TODO, -1L, 0L, 0L, 0L, 0L, 0L));
			this.changed();
		}
	}

	/** The cell starts; {@code pTotal} the rows to read, -1 when not known. Counters restart from zero. */
	public void unitStart(final String pRow, final String pCol, final long pTotal)
	{
		this.units.put(pRow + "\u0000" + pCol, new Unit(pRow, pCol, RunProgress.RUNNING, pTotal, 0L, 0L, 0L, this.clock.getAsLong(), 0L));
		this.changed(true);
	}

	/** Cumulative counters of a running cell (not deltas). */
	public void unitProgress(final String pRow, final String pCol, final long pRead, final long pWritten, final long pHeld)
	{
		this.unitProgress(pRow, pCol, pRead, pWritten, 0L, pHeld);
	}

	/** Lot V3-S: the same with the rows found already up to date. */
	public void unitProgress(final String pRow, final String pCol, final long pRead, final long pWritten, final long pUnchanged, final long pHeld)
	{
		final Unit before = this.unit(pRow, pCol);
		final long started = before == null || before.startedAt == 0L ? this.clock.getAsLong() : before.startedAt;
		final long total = before == null ? -1L : before.total;
		this.units.put(pRow + "\u0000" + pCol, new Unit(pRow, pCol, RunProgress.RUNNING, total, pRead, pWritten, pUnchanged, pHeld, started, 0L));
		this.changed();
	}

	public void unitEnd(final String pRow, final String pCol, final boolean pOk)
	{
		final Unit before = this.unit(pRow, pCol);
		if (before == null)
		{
			return;
		}
		this.units.put(pRow + "\u0000" + pCol, new Unit(pRow, pCol, pOk ? RunProgress.DONE : RunProgress.FAILED, before.total, before.read, before.written,
			before.unchanged, before.held, before.startedAt, this.clock.getAsLong()));
		this.changed(true);
	}

	/** Rows read so far and rows to read, over the cells whose total is known - the bar of the running screen. */
	public long[] rowsReadOfTotal()
	{
		long read = 0L;
		long total = 0L;
		for (final Unit unit : this.units.values())
		{
			if (unit.total >= 0L)
			{
				total += unit.total;
				read += Math.min(unit.read, unit.total);
			}
		}
		return new long[] { read, total };
	}

	/**
	 * Time left for the rows still to read, at the rate measured so far on this run (rows read / time spent in
	 * the cells that ran); null while no rate exists yet or no total is known. Mirrored by {@code wizard.js}.
	 */
	public Long remainingMs(final long pNow)
	{
		long read = 0L;
		long spent = 0L;
		for (final Unit unit : this.units.values())
		{
			if (unit.startedAt > 0L && unit.total >= 0L)
			{
				read += unit.read;
				spent += Math.max(0L, (unit.endedAt > 0L ? unit.endedAt : pNow) - unit.startedAt);
			}
		}
		final long[] of = this.rowsReadOfTotal();
		if (read <= 0L || spent <= 0L || of[1] <= 0L)
		{
			return null;
		}
		return Long.valueOf((long) ((of[1] - of[0]) * ((double) spent / read)));
	}

	private void addEvent(final String pText)
	{
		if (pText == null || pText.trim().isEmpty())
		{
			return;
		}
		this.events.add(0, new Event(this.clock.getAsLong(), RunProgress.line(pText)));
		while (this.events.size() > RunProgress.MAX_EVENTS)
		{
			this.events.remove(this.events.size() - 1);
		}
	}

	private static String line(final String pText)
	{
		if (pText == null)
		{
			return null;
		}
		final String oneLine = pText.replace('\n', ' ').replace('\r', ' ').trim();
		// staging screens (2026-10-17): a coded text keeps its marker whole - the cap counts the English only (same rule as LandingRunProgress)
		final String raw = com.on.ebx.factory.wizard.EngineMessageCodec.decode(oneLine).raw;
		final String marker = oneLine.substring(raw.length());
		return (raw.length() <= RunProgress.MAX_LINE ? raw : raw.substring(0, RunProgress.MAX_LINE - 1) + "\u2026") + marker;
	}

	/** Every stage {@link #TODO}, none started yet - the shape written the instant a run is accepted, before its background thread has done anything. */
	public static RunProgress newRun(final List<String> pStageKeys)
	{
		final RunProgress out = new RunProgress(pStageKeys);
		for (final String stage : pStageKeys)
		{
			out.byKey.put(stage, new Stage(stage, RunProgress.TODO, null, null, null));
		}
		out.startedAt = System.currentTimeMillis();
		out.updatedAt = out.startedAt;
		return out;
	}

	public Stage stage(final String pKey)
	{
		return this.byKey.get(pKey);
	}

	public List<Stage> stages()
	{
		final List<Stage> out = new ArrayList<Stage>();
		for (final String key : this.stageKeys)
		{
			out.add(this.byKey.get(key));
		}
		return out;
	}

	/** True once every stage is {@link #DONE}/{@link #FAILED}/{@link #SKIPPED} - none left {@link #TODO}/{@link #RUNNING}. */
	public boolean isOver()
	{
		for (final Stage stage : this.byKey.values())
		{
			if (RunProgress.TODO.equals(stage.state) || RunProgress.RUNNING.equals(stage.state))
			{
				return false;
			}
		}
		return true;
	}

	public void start(final String pKey, final long pAtMillis)
	{
		this.byKey.put(pKey, new Stage(pKey, RunProgress.RUNNING, Long.valueOf(pAtMillis), null, null));
		this.changed(true);
	}

	public void finish(final String pKey, final boolean pOk, final String pSummary, final long pAtMillis)
	{
		final Stage before = this.byKey.get(pKey);
		final Long startedAt = before == null ? null : before.startedAt;
		this.byKey.put(pKey, new Stage(pKey, pOk ? RunProgress.DONE : RunProgress.FAILED, startedAt, Long.valueOf(pAtMillis), pSummary));
		if (!pOk)
		{
			this.addEvent(com.on.ebx.factory.landing.RunTexts.failedLine(pSummary).stored());
		}
		this.changed(true);
	}

	public void skip(final String pKey, final String pReason)
	{
		this.byKey.put(pKey, new Stage(pKey, RunProgress.SKIPPED, null, null, pReason));
		this.changed(true);
	}

	public String toJson()
	{
		final ObjectNode root = RunProgress.MAPPER.createObjectNode();
		if (this.startedAt > 0L)
		{
			root.put("startedAt", this.startedAt);
		}
		if (this.updatedAt > 0L)
		{
			root.put("updatedAt", this.updatedAt);
		}
		final ArrayNode eventsNode = root.putArray("events");
		for (final Event event : this.events)
		{
			eventsNode.addObject().put("at", event.at).put("text", event.text);
		}
		final ArrayNode unitsNode = root.putArray("units");
		for (final Unit unit : this.units.values())
		{
			unitsNode.addObject().put("row", unit.row).put("col", unit.col).put("state", unit.state).put("total", unit.total).put("read", unit.read)
				.put("written", unit.written).put("unchanged", unit.unchanged).put("held", unit.held).put("startedAt", unit.startedAt).put("endedAt", unit.endedAt);
		}
		final ArrayNode stagesNode = root.putArray("stages");
		for (final String key : this.stageKeys)
		{
			final Stage stage = this.byKey.get(key);
			final ObjectNode stageNode = stagesNode.addObject();
			stageNode.put("key", key);
			if (stage == null)
			{
				stageNode.put("state", RunProgress.TODO);
				continue;
			}
			stageNode.put("state", stage.state);
			if (stage.startedAt != null)
			{
				stageNode.put("startedAt", stage.startedAt.longValue());
			}
			if (stage.endedAt != null)
			{
				stageNode.put("endedAt", stage.endedAt.longValue());
			}
			if (stage.summary != null)
			{
				stageNode.put("summary", stage.summary);
			}
			if (stage.activity != null)
			{
				stageNode.put("activity", stage.activity);
			}
		}
		return root.toString();
	}

	/** Never throws: a null/blank/unparsable {@code pJson} (no run yet, or a corrupted/truncated detail) returns {@link #newRun}'s own all-{@code TODO} shape over {@code pFallbackStageKeys}. */
	public static RunProgress parse(final String pJson, final List<String> pFallbackStageKeys)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			return RunProgress.newRun(pFallbackStageKeys);
		}
		final JsonNode root;
		try
		{
			root = RunProgress.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			return RunProgress.newRun(pFallbackStageKeys);
		}
		final JsonNode stagesNode = root.path("stages");
		if (!stagesNode.isArray() || stagesNode.size() == 0)
		{
			return RunProgress.newRun(pFallbackStageKeys);
		}
		final List<String> keys = new ArrayList<String>();
		for (final JsonNode stageNode : stagesNode)
		{
			final String key = stageNode.path("key").asText(null);
			if (key != null)
			{
				keys.add(key);
			}
		}
		final RunProgress out = RunProgress.newRun(keys.isEmpty() ? pFallbackStageKeys : keys);
		for (final JsonNode stageNode : stagesNode)
		{
			final String key = stageNode.path("key").asText(null);
			if (key == null || !out.byKey.containsKey(key))
			{
				continue;
			}
			final String state = stageNode.path("state").asText(RunProgress.TODO);
			final Long startedAt = stageNode.hasNonNull("startedAt") ? Long.valueOf(stageNode.path("startedAt").asLong()) : null;
			final Long endedAt = stageNode.hasNonNull("endedAt") ? Long.valueOf(stageNode.path("endedAt").asLong()) : null;
			final String summary = stageNode.hasNonNull("summary") ? stageNode.path("summary").asText() : null;
			final String activity = stageNode.hasNonNull("activity") ? stageNode.path("activity").asText() : null;
			out.byKey.put(key, new Stage(key, state, startedAt, endedAt, summary, activity));
		}
		out.startedAt = root.path("startedAt").asLong(0L);
		out.updatedAt = root.path("updatedAt").asLong(0L);
		for (final JsonNode eventNode : root.path("events"))
		{
			if (out.events.size() < RunProgress.MAX_EVENTS && eventNode.hasNonNull("text"))
			{
				out.events.add(new Event(eventNode.path("at").asLong(0L), eventNode.path("text").asText()));
			}
		}
		for (final JsonNode unitNode : root.path("units"))
		{
			final String row = unitNode.path("row").asText(null);
			final String col = unitNode.path("col").asText(null);
			if (row == null || col == null)
			{
				continue;
			}
			out.units.put(row + "\u0000" + col, new Unit(row, col, unitNode.path("state").asText(RunProgress.TODO), unitNode.path("total").asLong(-1L),
				unitNode.path("read").asLong(0L), unitNode.path("written").asLong(0L), unitNode.path("unchanged").asLong(0L), unitNode.path("held").asLong(0L),
				unitNode.path("startedAt").asLong(0L), unitNode.path("endedAt").asLong(0L)));
		}
		return out;
	}
}
