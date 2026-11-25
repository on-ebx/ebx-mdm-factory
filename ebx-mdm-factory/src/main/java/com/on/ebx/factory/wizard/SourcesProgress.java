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
 * Sources-landing lot SOURCES (2026-09-24): the JSON progress model of one {@code ebx.SourcesRun} pass - one
 * entry per connection-based application it is preparing (a person can keep dropping sample files or picking
 * catalog sources while it runs; those two ways in stay synchronous, task brief ss2 - only the guided-connection
 * chain, {@code ebx.SourceOnboarder#onboardAll}, is what a run works through here). Same split and same JSON
 * shape convention as {@code staging.ProposalProgress}: pure, no EBX class, round-tripped through {@code
 * PipelineStep.detail} by the live side ({@code ebx.SourcesRun}), read back by the wizard's "Sources" screen
 * poll.
 * <p>
 * Unlike {@link com.on.ebx.factory.staging.ProposalProgress}, whose stage KEYS are a fixed, known-in-advance
 * list, {@link SourcesProgress}'s entries are keyed by CONNECTOR ID - a run only knows, at the moment it
 * starts, which applications are pending; {@link #newRun(List)} seeds one {@link Entry} per connector id
 * given, state {@link #WAITING}, and {@link #start}/{@link #finish} transition one entry at a time as the
 * run works through them IN ORDER, never in parallel (task brief ss2: "one {@code ebx.SourcesRun} per project
 * handling each pending application in turn").
 */
public final class SourcesProgress
{
	public static final String WAITING = "WAITING";
	public static final String RUNNING = "RUNNING";
	public static final String DONE = "DONE";
	public static final String FAILED = "FAILED";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One application's own state within the run - immutable, {@link SourcesProgress} replaces it wholesale on every transition. */
	public static final class Entry
	{
		public final String connectorId;
		public final String state;
		/** Epoch millis, or null before this entry starts. */
		public final Long startedAt;
		/** Epoch millis, or null while {@link #WAITING}/{@link #RUNNING}. */
		public final Long endedAt;
		/** One line - the connection's own display label plus, once finished, a short outcome (a count, "ready", or an error); never a paragraph, same size-capped-detail discipline as {@code ProposalProgress.Stage#summary}. */
		public final String summary;
		/**
		 * Never null, possibly empty: the {@code sourceSystem} code a person typed for this application BEFORE
		 * it was queued (the sample-files table's own code field) - {@code ebx.SourcesRun}'s auto-add-on-ready
		 * uses it instead of auto-suggesting one from the schema name, when non-empty. Empty for every entry
		 * queued from the guided-connection path (no such field exists there - the code is always auto-suggested).
		 */
		public final String codeHint;
		/** Same idea as {@link #codeHint}, for the sample-files table's own trust-rank field; 100 (the project-wide default) when no hint was given. */
		public final int trustRankHint;

		public Entry(final String pConnectorId, final String pState, final Long pStartedAt, final Long pEndedAt, final String pSummary)
		{
			this(pConnectorId, pState, pStartedAt, pEndedAt, pSummary, "", 100);
		}

		public Entry(final String pConnectorId, final String pState, final Long pStartedAt, final Long pEndedAt, final String pSummary, final String pCodeHint,
			final int pTrustRankHint)
		{
			this.connectorId = pConnectorId;
			this.state = pState;
			this.startedAt = pStartedAt;
			this.endedAt = pEndedAt;
			this.summary = pSummary;
			this.codeHint = pCodeHint == null ? "" : pCodeHint;
			this.trustRankHint = pTrustRankHint;
		}
	}

	private final List<String> order = new ArrayList<String>();
	private final Map<String, Entry> byId = new LinkedHashMap<String, Entry>();

	private SourcesProgress()
	{
	}

	/** Every {@code pConnectorIds} entry {@link #WAITING}, none started - the shape written the instant a run is accepted. Duplicate ids are collapsed (kept once, first position) - a caller building the list from a live query should not pass duplicates, but a defensive collapse costs nothing and avoids a confusing double row. */
	public static SourcesProgress newRun(final List<String> pConnectorIds)
	{
		final SourcesProgress out = new SourcesProgress();
		for (final String id : pConnectorIds)
		{
			if (out.byId.containsKey(id))
			{
				continue;
			}
			out.order.add(id);
			out.byId.put(id, new Entry(id, SourcesProgress.WAITING, null, null, null));
		}
		return out;
	}

	/** Adds one new {@link #WAITING} entry with a code/trust-rank hint, or is a no-op if {@code pConnectorId} is already present (queued, running, or already finished - never re-queued, same rule {@code ebx.SourcesRun#start} relies on). */
	public void enqueue(final String pConnectorId, final String pCodeHint, final int pTrustRankHint)
	{
		if (this.byId.containsKey(pConnectorId))
		{
			return;
		}
		this.order.add(pConnectorId);
		this.byId.put(pConnectorId, new Entry(pConnectorId, SourcesProgress.WAITING, null, null, null, pCodeHint, pTrustRankHint));
	}

	/**
	 * Sources-landing lot SOURCES (2026-09-24): the Review screen's "Remove" on a still-stuck application -
	 * drops the entry outright (never marks it removed/tombstoned: a removed connector id can simply be
	 * re-queued later like a brand new one). If the background loop is mid-processing this exact entry when it
	 * is removed, its later {@code start}/{@code finish} calls become silent no-ops (both already guard on
	 * {@code byId.containsKey} - see their own Javadoc), which is the intended effect: whatever that call was
	 * about to record is simply discarded.
	 */
	public void remove(final String pConnectorId)
	{
		this.order.remove(pConnectorId);
		this.byId.remove(pConnectorId);
	}

	public Entry entry(final String pConnectorId)
	{
		return this.byId.get(pConnectorId);
	}

	/** In seed/run order (not alphabetical) - the order the run processes them in, and the order the "preparing" cards should list them in. */
	public List<Entry> entries()
	{
		final List<Entry> out = new ArrayList<Entry>();
		for (final String id : this.order)
		{
			out.add(this.byId.get(id));
		}
		return out;
	}

	/** True once every entry is {@link #DONE}/{@link #FAILED} - none left {@link #WAITING}/{@link #RUNNING}. An empty run (nothing was pending) is trivially over. */
	public boolean isOver()
	{
		for (final Entry entry : this.byId.values())
		{
			if (SourcesProgress.WAITING.equals(entry.state) || SourcesProgress.RUNNING.equals(entry.state))
			{
				return false;
			}
		}
		return true;
	}

	public void start(final String pConnectorId, final long pAtMillis, final String pSummary)
	{
		final Entry before = this.byId.get(pConnectorId);
		if (before == null)
		{
			return; // defensive: never add an entry outside newRun's/enqueue's own seed list
		}
		// position is NEVER touched here - entries() stays in seed/queue order regardless of processing order,
		// so a JSON round trip (SourcesProgressTest) sees the same order it started with. codeHint/trustRankHint
		// carry over from the seed entry - start() never had a reason to know or change them.
		this.byId.put(pConnectorId, new Entry(pConnectorId, SourcesProgress.RUNNING, Long.valueOf(pAtMillis), null, pSummary, before.codeHint, before.trustRankHint));
	}

	public void finish(final String pConnectorId, final boolean pOk, final String pSummary, final long pAtMillis)
	{
		final Entry before = this.byId.get(pConnectorId);
		if (before == null)
		{
			return; // defensive, same as start()
		}
		this.byId.put(pConnectorId,
			new Entry(pConnectorId, pOk ? SourcesProgress.DONE : SourcesProgress.FAILED, before.startedAt, Long.valueOf(pAtMillis), pSummary, before.codeHint, before.trustRankHint));
	}

	public String toJson()
	{
		final ObjectNode root = SourcesProgress.MAPPER.createObjectNode();
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
			if (entry.summary != null)
			{
				entryNode.put("summary", entry.summary);
			}
			if (!entry.codeHint.isEmpty())
			{
				entryNode.put("codeHint", entry.codeHint);
			}
			if (entry.trustRankHint != 100)
			{
				entryNode.put("trustRankHint", entry.trustRankHint);
			}
		}
		return root.toString();
	}

	/** Never throws: a null/blank/unparsable {@code pJson} (no run yet, or a corrupted/truncated detail) returns an empty run - {@link #isOver} then reads true (vacuously, nothing to wait for), which is the safe default for a poller that finds no run at all. */
	public static SourcesProgress parse(final String pJson)
	{
		final SourcesProgress out = new SourcesProgress();
		if (pJson == null || pJson.trim().isEmpty())
		{
			return out;
		}
		try
		{
			final JsonNode root = SourcesProgress.MAPPER.readTree(pJson);
			for (final JsonNode entryNode : root.path("entries"))
			{
				final String id = entryNode.path("connectorId").asText("");
				if (id.isEmpty())
				{
					continue;
				}
				final Long startedAt = entryNode.has("startedAt") ? Long.valueOf(entryNode.path("startedAt").asLong()) : null;
				final Long endedAt = entryNode.has("endedAt") ? Long.valueOf(entryNode.path("endedAt").asLong()) : null;
				final String summary = entryNode.has("summary") ? entryNode.path("summary").asText("") : null;
				final String codeHint = entryNode.path("codeHint").asText("");
				final int trustRankHint = entryNode.path("trustRankHint").asInt(100);
				out.order.add(id);
				out.byId.put(id, new Entry(id, entryNode.path("state").asText(SourcesProgress.WAITING), startedAt, endedAt, summary, codeHint, trustRankHint));
			}
		}
		catch (final Exception ex) // deliberately broad, same posture as ProposalProgress.parse - a corrupted detail must never break the poll
		{
			return SourcesProgress.parse(null);
		}
		return out;
	}
}
