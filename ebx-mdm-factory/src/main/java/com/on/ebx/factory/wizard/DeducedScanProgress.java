package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Deduced-scan lot (2026-09-24, task brief: "reuse the background-run + polling pattern already used by the
 * Sources step; reuse, do not invent a second one"): the JSON progress model of one {@code ebx.DeducedScanRun}
 * pass - one {@link Entry} per connection being deduced-scanned (queue grows/is processed one at a time, same
 * as {@link SourcesProgress}), and inside each entry, the FOUR fixed stages board 2d shows (same idea as
 * {@code staging.ProposalProgress}'s fixed stage list): {@link #LIST} ("How to list"), {@link #SAMPLE} ("Read
 * a sample"), {@link #MEASURE} ("Measure"), {@link #NAME} ("Name and key"). Combines both existing patterns
 * rather than picking one, because board 2d genuinely needs both: several applications queued (growing list,
 * {@link SourcesProgress}'s own shape) each going through a known four-step pipeline ({@code
 * ProposalProgress}'s own shape).
 * <p>
 * An entry whose {@link #LIST} stage finds no read-only listing operation at all ends the WHOLE entry
 * {@link #SKIPPED} right there (contract: "no list operation -&gt; looks up one record at a time: an
 * enrichment, not a source, nothing written") - {@link #SAMPLE}/{@link #MEASURE}/{@link #NAME} simply stay
 * {@link #WAITING} forever for that entry, which is fine: {@link #isOver} only cares about the ENTRY's own
 * top-level state, not every stage having run.
 */
public final class DeducedScanProgress
{
	public static final String LIST = "LIST";
	public static final String SAMPLE = "SAMPLE";
	public static final String MEASURE = "MEASURE";
	public static final String NAME = "NAME";
	public static final String[] STAGE_KEYS = { DeducedScanProgress.LIST, DeducedScanProgress.SAMPLE, DeducedScanProgress.MEASURE, DeducedScanProgress.NAME };

	public static final String WAITING = "WAITING";
	public static final String RUNNING = "RUNNING";
	public static final String DONE = "DONE";
	public static final String FAILED = "FAILED";
	/** Entry-level only - see class Javadoc: no read-only listing operation was found. */
	public static final String SKIPPED = "SKIPPED";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One stage's own state - immutable. */
	public static final class Stage
	{
		public final String key;
		public final String state;
		/** One line, plain text (already formatted) - "the model chose list_contacts, list_companies", "25 rows each, read only", etc. Never null (empty when the stage has not started). */
		public final String summary;

		public Stage(final String pKey, final String pState, final String pSummary)
		{
			this.key = pKey;
			this.state = pState;
			this.summary = pSummary == null ? "" : pSummary;
		}
	}

	/** One connection's own progress through the four stages. */
	public static final class Entry
	{
		public final String connectorId;
		public final String state;
		public final Long startedAt;
		public final Long endedAt;
		/** One line - outcome once {@link #state} is {@link #DONE}/{@link #FAILED}/{@link #SKIPPED} (a count of tables written, an error, or why it was skipped). Empty while WAITING/RUNNING. */
		public final String summary;
		public final Map<String, Stage> stages;
		/** Table names written to the catalog by this entry (task brief: several tables from one application, e.g. Contact + Company) - filled once {@link #NAME} finishes and the catalog write succeeds. */
		public final List<String> tablesWritten;

		public Entry(final String pConnectorId, final String pState, final Long pStartedAt, final Long pEndedAt, final String pSummary,
			final Map<String, Stage> pStages, final List<String> pTablesWritten)
		{
			this.connectorId = pConnectorId;
			this.state = pState;
			this.startedAt = pStartedAt;
			this.endedAt = pEndedAt;
			this.summary = pSummary == null ? "" : pSummary;
			this.stages = pStages == null ? DeducedScanProgress.freshStages() : pStages;
			this.tablesWritten = pTablesWritten == null ? new ArrayList<String>() : pTablesWritten;
		}

		public Stage stage(final String pKey)
		{
			return this.stages.get(pKey);
		}
	}

	private final List<String> order = new ArrayList<String>();
	private final Map<String, Entry> byId = new LinkedHashMap<String, Entry>();

	private DeducedScanProgress()
	{
	}

	private static Map<String, Stage> freshStages()
	{
		final Map<String, Stage> out = new LinkedHashMap<String, Stage>();
		for (final String key : DeducedScanProgress.STAGE_KEYS)
		{
			out.put(key, new Stage(key, DeducedScanProgress.WAITING, ""));
		}
		return out;
	}

	/** Every {@code pConnectorIds} entry {@link #WAITING}, four fresh {@link #WAITING} stages - duplicates collapsed (first position kept), same convention as {@code SourcesProgress#newRun}. */
	public static DeducedScanProgress newRun(final List<String> pConnectorIds)
	{
		final DeducedScanProgress out = new DeducedScanProgress();
		for (final String id : pConnectorIds)
		{
			out.enqueue(id);
		}
		return out;
	}

	/** No-op if {@code pConnectorId} is already present (queued, running, or finished) - same rule {@code SourcesProgress#enqueue} follows. */
	public void enqueue(final String pConnectorId)
	{
		if (this.byId.containsKey(pConnectorId))
		{
			return;
		}
		this.order.add(pConnectorId);
		this.byId.put(pConnectorId, new Entry(pConnectorId, DeducedScanProgress.WAITING, null, null, "", null, null));
	}

	public Entry entry(final String pConnectorId)
	{
		return this.byId.get(pConnectorId);
	}

	/** Seed/queue order, not alphabetical - same rule as {@code SourcesProgress#entries}. */
	public List<Entry> entries()
	{
		final List<Entry> out = new ArrayList<Entry>();
		for (final String id : this.order)
		{
			out.add(this.byId.get(id));
		}
		return out;
	}

	/** True once every entry is {@link #DONE}/{@link #FAILED}/{@link #SKIPPED} - none left {@link #WAITING}/{@link #RUNNING}. */
	public boolean isOver()
	{
		for (final Entry entry : this.byId.values())
		{
			if (DeducedScanProgress.WAITING.equals(entry.state) || DeducedScanProgress.RUNNING.equals(entry.state))
			{
				return false;
			}
		}
		return true;
	}

	public void start(final String pConnectorId, final long pAtMillis)
	{
		final Entry before = this.byId.get(pConnectorId);
		if (before == null)
		{
			return;
		}
		this.byId.put(pConnectorId,
			new Entry(pConnectorId, DeducedScanProgress.RUNNING, Long.valueOf(pAtMillis), null, "", before.stages, before.tablesWritten));
	}

	/** Transitions one stage of an already-{@link #RUNNING} entry - a no-op if the entry does not exist (defensive, same posture as {@code SourcesProgress#start}/{@code #finish}). */
	public void stage(final String pConnectorId, final String pStageKey, final String pState, final String pSummary)
	{
		final Entry before = this.byId.get(pConnectorId);
		if (before == null)
		{
			return;
		}
		final Map<String, Stage> stages = new LinkedHashMap<String, Stage>(before.stages);
		stages.put(pStageKey, new Stage(pStageKey, pState, pSummary));
		this.byId.put(pConnectorId, new Entry(pConnectorId, before.state, before.startedAt, before.endedAt, before.summary, stages, before.tablesWritten));
	}

	/** Ends the whole entry - {@code pState} one of {@link #DONE}/{@link #FAILED}/{@link #SKIPPED}. */
	public void finish(final String pConnectorId, final String pState, final String pSummary, final long pAtMillis, final List<String> pTablesWritten)
	{
		final Entry before = this.byId.get(pConnectorId);
		if (before == null)
		{
			return;
		}
		this.byId.put(pConnectorId, new Entry(pConnectorId, pState, before.startedAt, Long.valueOf(pAtMillis), pSummary, before.stages, pTablesWritten));
	}

	public String toJson()
	{
		final ObjectNode root = DeducedScanProgress.MAPPER.createObjectNode();
		final ArrayNode entriesNode = root.putArray("entries");
		for (final String id : this.order)
		{
			final Entry entry = this.byId.get(id);
			final ObjectNode entryNode = entriesNode.addObject();
			entryNode.put("connectorId", entry.connectorId);
			entryNode.put("state", entry.state);
			if (entry.startedAt != null)
			{
				entryNode.put("startedAt", entry.startedAt.longValue());
			}
			if (entry.endedAt != null)
			{
				entryNode.put("endedAt", entry.endedAt.longValue());
			}
			if (!entry.summary.isEmpty())
			{
				entryNode.put("summary", entry.summary);
			}
			final ObjectNode stagesNode = entryNode.putObject("stages");
			for (final String key : DeducedScanProgress.STAGE_KEYS)
			{
				final Stage stage = entry.stages.get(key);
				if (stage == null || (DeducedScanProgress.WAITING.equals(stage.state) && stage.summary.isEmpty()))
				{
					continue; // nothing to say yet - keep the JSON small, same discipline as ProposalProgress
				}
				final ObjectNode stageNode = stagesNode.putObject(key);
				stageNode.put("state", stage.state);
				if (!stage.summary.isEmpty())
				{
					stageNode.put("summary", stage.summary);
				}
			}
			if (!entry.tablesWritten.isEmpty())
			{
				final ArrayNode tablesNode = entryNode.putArray("tablesWritten");
				for (final String table : entry.tablesWritten)
				{
					tablesNode.add(table);
				}
			}
		}
		return root.toString();
	}

	/** Never throws: an unparsable/blank {@code pJson} answers an empty run - see {@code SourcesProgress#parse}'s own Javadoc for why this matters to a poller. */
	public static DeducedScanProgress parse(final String pJson)
	{
		final DeducedScanProgress out = new DeducedScanProgress();
		if (pJson == null || pJson.trim().isEmpty())
		{
			return out;
		}
		try
		{
			final JsonNode root = DeducedScanProgress.MAPPER.readTree(pJson);
			for (final JsonNode entryNode : root.path("entries"))
			{
				final String id = entryNode.path("connectorId").asText("");
				if (id.isEmpty())
				{
					continue;
				}
				final Long startedAt = entryNode.has("startedAt") ? Long.valueOf(entryNode.path("startedAt").asLong()) : null;
				final Long endedAt = entryNode.has("endedAt") ? Long.valueOf(entryNode.path("endedAt").asLong()) : null;
				final String summary = entryNode.path("summary").asText("");
				final Map<String, Stage> stages = DeducedScanProgress.freshStages();
				final JsonNode stagesNode = entryNode.path("stages");
				for (final String key : DeducedScanProgress.STAGE_KEYS)
				{
					final JsonNode stageNode = stagesNode.path(key);
					if (stageNode.isMissingNode())
					{
						continue;
					}
					stages.put(key, new Stage(key, stageNode.path("state").asText(DeducedScanProgress.WAITING), stageNode.path("summary").asText("")));
				}
				final List<String> tablesWritten = new ArrayList<String>();
				for (final JsonNode tableNode : entryNode.path("tablesWritten"))
				{
					tablesWritten.add(tableNode.asText(""));
				}
				out.order.add(id);
				out.byId.put(id, new Entry(id, entryNode.path("state").asText(DeducedScanProgress.WAITING), startedAt, endedAt, summary, stages, tablesWritten));
			}
		}
		catch (final Exception ex) // deliberately broad - see this method's own Javadoc
		{
			return DeducedScanProgress.parse(null);
		}
		return out;
	}
}
