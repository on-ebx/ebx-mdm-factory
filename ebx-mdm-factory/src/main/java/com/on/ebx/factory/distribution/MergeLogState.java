package com.on.ebx.factory.distribution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot S2 (2026-10-18, retire notices on merge): everything {@code DistributionTarget.mergeLogWatermark} holds, in ONE parsed shape, so the two
 * writers of that field - {@code ebx.MergeLogWatcher} (the watermark of the merge log) and {@code ebx.MatchingStateSweeper} (the baseline, the
 * sweep marks and the last-sweep facts) - read, change their own part and write the whole text back WITHOUT dropping the other's. Before this class
 * the watcher parsed only {@code last}/{@code undone} and rewrote the field from those two, which would have erased anything else stored beside them.
 * The field is an existing text field of {@code MdmFactory.xsd}: nothing here changes the data model.
 * <p>
 * JSON shape (the old shape is a strict subset, so a field written before this lot parses unchanged):
 * <pre>
 * { "/root/Personne": { "last": 12, "undone": [3], "baseline": 7, "swept": 12 },
 *   "_sweep": { "at": "2026-10-18T10:00:00Z", "queued": 5, "stamps": { "/root/Personne": 1760781600000 } } }
 * </pre>
 * {@code baseline} = the highest merge id at the moment the target started being watched (its first full send began, or the watch's first pass):
 * a record merged under it was never published as live, so it needs no retire notice. {@code swept} = the highest merge id a sweep has already
 * compared with the outbox and the history. The key {@code _sweep} cannot be a table path (a path starts with a slash). Pure, unit-tested.
 */
public final class MergeLogState
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** The reserved top-level key. */
	public static final String SWEEP_KEY = "_sweep";

	/** One table's marks. Mutable on purpose: the two writers change their own fields. */
	public static final class TableMark
	{
		public long last;
		public Set<Long> undone = new LinkedHashSet<Long>();
		/** Null = not known (a field written before this lot): the sweep then announces nothing on the strength of a merge id. */
		public Long baseline;
		/** Null = never swept. */
		public Long swept;
	}

	/** What the last real sweep did. */
	public static final class SweepFacts
	{
		/** ISO-8601 UTC, empty when no sweep ran yet. */
		public String at = "";
		/** Notices a sweep queued since the field exists (the watch's own notices are not counted here). */
		public long queued;
		/** Per table: the {@code RecordMetadata} table's last modification (epoch ms) when the sweep read it - an unchanged stamp means nothing to compare. */
		public Map<String, Long> stamps = new LinkedHashMap<String, Long>();
	}

	private final Map<String, TableMark> tables = new LinkedHashMap<String, TableMark>();
	private SweepFacts sweep = new SweepFacts();

	public MergeLogState()
	{
	}

	/** Never throws: blank or malformed text is an empty state (every table then reads as "never watched", a safe fresh start - it only re-learns where the log stands). */
	public static MergeLogState parse(final String pStored)
	{
		final MergeLogState out = new MergeLogState();
		if (pStored == null || pStored.trim().isEmpty())
		{
			return out;
		}
		try
		{
			final JsonNode root = MergeLogState.MAPPER.readTree(pStored);
			if (root == null || !root.isObject())
			{
				return out;
			}
			final java.util.Iterator<String> names = root.fieldNames();
			while (names.hasNext())
			{
				final String name = names.next();
				final JsonNode entry = root.path(name);
				if (MergeLogState.SWEEP_KEY.equals(name))
				{
					out.sweep = MergeLogState.readSweep(entry);
					continue;
				}
				final TableMark mark = new TableMark();
				mark.last = entry.path("last").asLong(0L);
				for (final JsonNode idNode : entry.path("undone"))
				{
					mark.undone.add(Long.valueOf(idNode.asLong()));
				}
				mark.baseline = entry.has("baseline") && entry.path("baseline").isNumber() ? Long.valueOf(entry.path("baseline").asLong()) : null;
				mark.swept = entry.has("swept") && entry.path("swept").isNumber() ? Long.valueOf(entry.path("swept").asLong()) : null;
				out.tables.put(name, mark);
			}
		}
		catch (final JsonProcessingException | RuntimeException ex)
		{
			return new MergeLogState();
		}
		return out;
	}

	private static SweepFacts readSweep(final JsonNode pNode)
	{
		final SweepFacts facts = new SweepFacts();
		facts.at = pNode.path("at").asText("");
		facts.queued = pNode.path("queued").asLong(0L);
		final java.util.Iterator<String> names = pNode.path("stamps").fieldNames();
		while (names.hasNext())
		{
			final String table = names.next();
			facts.stamps.put(table, Long.valueOf(pNode.path("stamps").path(table).asLong(0L)));
		}
		return facts;
	}

	public String format()
	{
		final ObjectNode root = MergeLogState.MAPPER.createObjectNode();
		for (final Map.Entry<String, TableMark> entry : this.tables.entrySet())
		{
			final ObjectNode node = root.putObject(entry.getKey());
			node.put("last", entry.getValue().last);
			final ArrayNode undone = node.putArray("undone");
			for (final Long id : entry.getValue().undone)
			{
				undone.add(id.longValue());
			}
			if (entry.getValue().baseline != null)
			{
				node.put("baseline", entry.getValue().baseline.longValue());
			}
			if (entry.getValue().swept != null)
			{
				node.put("swept", entry.getValue().swept.longValue());
			}
		}
		if (!this.sweep.at.isEmpty() || this.sweep.queued != 0L || !this.sweep.stamps.isEmpty())
		{
			final ObjectNode node = root.putObject(MergeLogState.SWEEP_KEY);
			node.put("at", this.sweep.at);
			node.put("queued", this.sweep.queued);
			final ObjectNode stamps = node.putObject("stamps");
			for (final Map.Entry<String, Long> stamp : this.sweep.stamps.entrySet())
			{
				stamps.put(stamp.getKey(), stamp.getValue().longValue());
			}
		}
		return root.toString();
	}

	/** The table's marks, or null when never watched. */
	public TableMark table(final String pTablePath)
	{
		return this.tables.get(pTablePath);
	}

	public Map<String, TableMark> tables()
	{
		return Collections.unmodifiableMap(this.tables);
	}

	public SweepFacts sweep()
	{
		return this.sweep;
	}

	/** Creates the table's marks when absent. */
	public TableMark tableOrCreate(final String pTablePath)
	{
		TableMark mark = this.tables.get(pTablePath);
		if (mark == null)
		{
			mark = new TableMark();
			this.tables.put(pTablePath, mark);
		}
		return mark;
	}

	/** The watch's view of a table (null when never watched). */
	public MergeLogWatch.Watermark watermark(final String pTablePath)
	{
		final TableMark mark = this.tables.get(pTablePath);
		return mark == null ? null : new MergeLogWatch.Watermark(mark.last, mark.undone);
	}

	/**
	 * Stores the watch's decided watermark for a table. A table met for the first time also gets its {@code baseline} (the watermark's own
	 * {@code lastMergeId}, which {@link MergeLogWatch#decide} sets to the current maximum on a first pass) - the moment the target started being watched.
	 */
	public void putWatermark(final String pTablePath, final MergeLogWatch.Watermark pWatermark)
	{
		final boolean isNew = !this.tables.containsKey(pTablePath);
		final TableMark mark = this.tableOrCreate(pTablePath);
		mark.last = pWatermark.lastMergeId;
		mark.undone = new LinkedHashSet<Long>(pWatermark.undoneMergeIds);
		if (isNew && mark.baseline == null)
		{
			mark.baseline = Long.valueOf(pWatermark.lastMergeId);
		}
	}
}
