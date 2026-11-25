package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Wave 2 (2026-09-30, lot V2-A): the stored form of wizard step 6's "Matching results" block for ONE master entity.
 * <p>
 * <b>Why it exists.</b> {@code ebx.MatchingResultsBuilder} computes that block with several aggregates over the master
 * table and one add-on call per pair shown; measured on the 100 000-row volume test, running it while the wizard renders
 * made every first render (whatever the step) take 32 to 46 seconds. The block changes only when matching runs or a
 * person decides a pair, so it is computed at those moments, stored, and only READ (and parsed - a few milliseconds) when
 * the page is written.
 * <p>
 * <b>Where it lives</b> (no XSD change - {@code PipelineStep.step} is a static enumeration and a new key would be stored
 * but flagged invalid): as the {@value #KEY} member of the {@code detail} JSON of the entity's latest {@code MATCHING_RUN}
 * {@code PipelineStep} row ({@code ebx.MatchingResultsStore}). This class is the pure half: the JSON shape, its size
 * budget, the staleness rule and the local patch applied when a decision is made. No EBX class here.
 * <p>
 * <b>Shape</b> ({@link #VERSION} 1, all members optional on read): {@code v}, {@code at} (epoch ms of the computation),
 * {@code stale}, {@code kpis{total,bySource{},golden,merged,pending,distinct}}, {@code goldensWithSuspects}, {@code
 * band[{l,n}]}, {@code colors{}}, {@code pending[{id,g,s,gl,sl,gs,ss,f[{l,a,b,v}]}]}, {@code pendingTotal}, {@code
 * maxDetail}, {@code tree{id:{ok,why,nodes[],last,scores[{f,s}]}}}, {@code merged[{k,l,s,ms[],mk[]}]}, {@code
 * distinct[{a,b,by,at}]}. A stored value longer than {@value #MAX_VALUE_CHARS} characters is cut (the comparison table
 * shows values, not documents); the whole block is kept under a caller budget by dropping detail first, then pairs
 * ({@link #encode}), because a JSON cut by a plain string truncation would not parse.
 */
public final class MatchResultsSnapshot
{
	public static final int VERSION = 1;
	/** Name of the member of the run row's detail JSON that holds this snapshot. */
	public static final String KEY = "results";
	/** A stored field value (left/right of a comparison, a label) is cut to this many characters. */
	public static final int MAX_VALUE_CHARS = 80;
	/** Default size budget of one entity's snapshot: 100 000 is the column's cap ({@code TextLimits.DETAIL_MAX_LENGTH}), the run's own detail and a safety margin take the rest. */
	public static final int DEFAULT_BUDGET_CHARS = 70000;

	public static final String FRESH = "FRESH";
	public static final String STALE = "STALE";
	public static final String MISSING = "MISSING";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MatchResultsSnapshot()
	{
	}

	/** A snapshot read back: the computed part of the block, when it was computed and whether a decision has touched it since. */
	public static final class Decoded
	{
		public final MatchResultsView.Input input;
		public final long computedAt;
		public final boolean stale;

		Decoded(final MatchResultsView.Input pInput, final long pComputedAt, final boolean pStale)
		{
			this.input = pInput;
			this.computedAt = pComputedAt;
			this.stale = pStale;
		}
	}

	/** One decision made from the queue - what {@link #applyDecisions} needs to keep the stored figures honest until the refresh job recomputes them. */
	public static final class Decision
	{
		public final String goldenKey;
		public final String suspectKey;
		public final boolean accepted;
		/** Who and when, already formatted - shown in the "Different" tab for a rejection. */
		public final String by;
		public final String at;

		public Decision(final String pGoldenKey, final String pSuspectKey, final boolean pAccepted, final String pBy, final String pAt)
		{
			this.goldenKey = pGoldenKey;
			this.suspectKey = pSuspectKey;
			this.accepted = pAccepted;
			this.by = pBy;
			this.at = pAt;
		}
	}

	// ------------------------------------------------------------------------------------------------ state rule

	/**
	 * The one staleness rule. MISSING: nothing usable stored (never computed, an older shape, or the run that would carry
	 * it has not finished). STALE: a decision touched it since ({@code stale} flag), or it was computed before the
	 * latest change of the master data ({@code pLatestChangeAtMillis}, 0 = unknown - the rule then never fires). FRESH
	 * otherwise. STALE still shows the stored figures, with a note and a refresh button; only MISSING shows no figure.
	 */
	public static String state(final Decoded pDecoded, final long pLatestChangeAtMillis)
	{
		if (pDecoded == null)
		{
			return MatchResultsSnapshot.MISSING;
		}
		if (pDecoded.stale || (pLatestChangeAtMillis > 0 && pDecoded.computedAt > 0 && pDecoded.computedAt < pLatestChangeAtMillis))
		{
			return MatchResultsSnapshot.STALE;
		}
		return MatchResultsSnapshot.FRESH;
	}

	// ------------------------------------------------------------------------------------------------ encode

	/** {@link #encode(MatchResultsView.Input, long, boolean, int)} with {@link #DEFAULT_BUDGET_CHARS}. */
	public static String encode(final MatchResultsView.Input pInput, final long pComputedAt, final boolean pStale)
	{
		return MatchResultsSnapshot.encode(pInput, pComputedAt, pStale, MatchResultsSnapshot.DEFAULT_BUDGET_CHARS);
	}

	/**
	 * The JSON of {@code pInput}'s computed part, at most {@code pBudgetChars} long: when the full form is over budget the
	 * per-pair decision-tree details are dropped first, then the pairs shown are halved until it fits (the totals are kept -
	 * "N more not shown" then says so). Never returns a cut string.
	 */
	public static String encode(final MatchResultsView.Input pInput, final long pComputedAt, final boolean pStale, final int pBudgetChars)
	{
		int pairLimit = Math.max(pInput.pendingPairs.size(), pInput.pendingGroups.size());
		boolean withTree = true;
		boolean allRows = true;
		String json = MatchResultsSnapshot.build(pInput, pComputedAt, pStale, pairLimit, withTree, allRows);
		if (json.length() > pBudgetChars)
		{
			// pair card 2026-10-18: the rows only "Compare field by field" shows go first (the card keeps what it is decided on), then the evaluation detail
			allRows = false;
			json = MatchResultsSnapshot.build(pInput, pComputedAt, pStale, pairLimit, withTree, allRows);
		}
		if (json.length() > pBudgetChars)
		{
			withTree = false;
			json = MatchResultsSnapshot.build(pInput, pComputedAt, pStale, pairLimit, withTree, allRows);
		}
		while (json.length() > pBudgetChars && pairLimit > 0)
		{
			pairLimit = pairLimit / 2;
			json = MatchResultsSnapshot.build(pInput, pComputedAt, pStale, pairLimit, withTree, allRows);
		}
		return json;
	}

	private static String build(final MatchResultsView.Input pIn, final long pComputedAt, final boolean pStale, final int pPairLimit, final boolean pWithTree, final boolean pAllRows)
	{
		final ObjectNode root = MatchResultsSnapshot.MAPPER.createObjectNode();
		root.put("v", MatchResultsSnapshot.VERSION);
		root.put("at", pComputedAt);
		root.put("stale", pStale);
		final ObjectNode kpis = root.putObject("kpis");
		if (pIn.kpis != null)
		{
			kpis.put("total", pIn.kpis.totalRecords);
			final ObjectNode bySource = kpis.putObject("bySource");
			for (final Map.Entry<String, Integer> entry : pIn.kpis.recordsBySource.entrySet())
			{
				bySource.put(entry.getKey(), entry.getValue() == null ? 0 : entry.getValue().intValue());
			}
			kpis.put("golden", pIn.kpis.goldenRecords);
			kpis.put("merged", pIn.kpis.mergedGroups);
			kpis.put("pending", pIn.kpis.pendingSuspectPairs);
			kpis.put("distinct", pIn.kpis.distinctPairs);
			kpis.put("unmatched", pIn.kpis.unmatchedRecords);
		}
		root.put("goldensWithSuspects", pIn.goldensWithSuspects);
		final ArrayNode band = root.putArray("band");
		for (final MatchStats.BandSegment segment : pIn.band)
		{
			final ObjectNode node = band.addObject();
			node.put("l", segment.label);
			node.put("n", segment.count);
		}
		final ObjectNode colors = root.putObject("colors");
		if (pIn.colorBySource != null)
		{
			for (final Map.Entry<String, String> entry : pIn.colorBySource.entrySet())
			{
				colors.put(entry.getKey(), entry.getValue());
			}
		}
		if (pIn.fieldLabels != null && !pIn.fieldLabels.isEmpty())
		{
			final ObjectNode labels = root.putObject("labels");
			for (final Map.Entry<String, String> entry : pIn.fieldLabels.entrySet())
			{
				labels.put(entry.getKey(), MatchResultsSnapshot.cut(entry.getValue()));
			}
		}
		final ArrayNode pending = root.putArray("pending");
		final Map<String, Boolean> kept = new LinkedHashMap<String, Boolean>();
		for (int i = 0; i < pIn.pendingPairs.size() && i < pPairLimit; i++)
		{
			final MatchStats.SuspectPair pair = pIn.pendingPairs.get(i);
			kept.put(pair.id, Boolean.TRUE);
			final ObjectNode node = pending.addObject();
			node.put("id", pair.id);
			node.put("g", pair.goldenKey);
			node.put("s", pair.suspectKey);
			node.put("gl", MatchResultsSnapshot.cut(pair.goldenLabel));
			node.put("sl", MatchResultsSnapshot.cut(pair.suspectLabel));
			node.put("gs", pair.goldenSource);
			node.put("ss", pair.suspectSource);
			if (!pair.goldenSourceId.isEmpty())
			{
				node.put("gi", MatchResultsSnapshot.cut(pair.goldenSourceId));
			}
			if (!pair.suspectSourceId.isEmpty())
			{
				node.put("si", MatchResultsSnapshot.cut(pair.suspectSourceId));
			}
			final ArrayNode fields = node.putArray("f");
			for (final MatchStats.FieldComparison field : pair.fields)
			{
				if (!pAllRows && !field.glance)
				{
					continue;
				}
				final ObjectNode f = fields.addObject();
				f.put("l", field.label);
				f.put("a", MatchResultsSnapshot.cut(field.left));
				f.put("b", MatchResultsSnapshot.cut(field.right));
				f.put("v", field.verdict);
				// pair card 2026-10-18: the master field behind a label, the raw values under a derived key, whether the row is shown at a glance (absent = it is, as every row stored before)
				if (!field.name.equals(field.label))
				{
					f.put("n", field.name);
				}
				if (!field.rawName.isEmpty())
				{
					f.put("rn", field.rawName);
					f.put("ra", MatchResultsSnapshot.cut(field.rawLeft));
					f.put("rb", MatchResultsSnapshot.cut(field.rawRight));
				}
				if (!field.glance)
				{
					f.put("g", false);
				}
			}
		}
		// step 6 queue per group (2026-10-18)
		final ArrayNode groups = root.putArray("groups");
		for (int i = 0; i < pIn.pendingGroups.size() && i < pPairLimit; i++)
		{
			final MatchStats.SuspectGroup group = pIn.pendingGroups.get(i);
			final ObjectNode node = groups.addObject();
			node.put("id", group.id);
			final ArrayNode members = node.putArray("m");
			for (final MatchStats.GroupMember member : group.members)
			{
				final ObjectNode m = members.addObject();
				m.put("k", member.key);
				m.put("s", member.source);
				if (!member.sourceId.isEmpty())
				{
					m.put("i", MatchResultsSnapshot.cut(member.sourceId));
				}
				m.put("l", MatchResultsSnapshot.cut(member.label));
				if (member.golden)
				{
					m.put("g", true);
				}
			}
			final ArrayNode rows = node.putArray("r");
			for (final MatchStats.GroupRow row : group.rows)
			{
				if (!pAllRows && !row.glance)
				{
					continue;
				}
				final ObjectNode r = rows.addObject();
				r.put("l", row.label);
				if (!row.name.equals(row.label))
				{
					r.put("n", row.name);
				}
				if (!row.rawName.isEmpty())
				{
					r.put("rn", row.rawName);
				}
				if (!row.glance)
				{
					r.put("g", false);
				}
				final ArrayNode values = r.putArray("v");
				for (final String value : row.values)
				{
					values.add(MatchResultsSnapshot.cut(value));
				}
				if (!row.rawName.isEmpty())
				{
					final ArrayNode raws = r.putArray("rv");
					for (final String raw : row.raws)
					{
						raws.add(MatchResultsSnapshot.cut(raw));
					}
				}
			}
			final ArrayNode pairs = node.putArray("p");
			for (final String[] pair : group.pairs)
			{
				pairs.addArray().add(pair[0]).add(pair[1]);
			}
			if (pWithTree)
			{
				final ArrayNode nodes = node.putArray("nodes");
				for (final String name : group.nodes)
				{
					nodes.add(MatchResultsSnapshot.cut(name));
				}
			}
		}
		root.put("groupsTotal", pIn.pendingGroupsTotal);
		root.put("pendingTotal", pIn.pendingPairsTotal);
		root.put("maxDetail", pIn.maxDetailPairs);
		final ObjectNode tree = root.putObject("tree");
		if (pWithTree && pIn.treeDetail != null)
		{
			for (final Map.Entry<String, MatchResultsView.TreeDetail> entry : pIn.treeDetail.entrySet())
			{
				if (!kept.containsKey(entry.getKey()) || entry.getValue() == null)
				{
					continue;
				}
				final MatchResultsView.TreeDetail detail = entry.getValue();
				final ObjectNode node = tree.putObject(entry.getKey());
				node.put("ok", detail.available);
				if (detail.unavailableReason != null)
				{
					node.put("why", MatchResultsSnapshot.cut(detail.unavailableReason));
				}
				final ArrayNode nodes = node.putArray("nodes");
				for (final String name : detail.nodeNames)
				{
					nodes.add(MatchResultsSnapshot.cut(name));
				}
				if (detail.lastNodeName != null)
				{
					node.put("last", MatchResultsSnapshot.cut(detail.lastNodeName));
				}
				final ArrayNode scores = node.putArray("scores");
				for (final MatchResultsView.EngineFieldScore score : detail.fieldScores)
				{
					final ObjectNode s = scores.addObject();
					s.put("f", score.field);
					s.put("s", score.score);
				}
			}
		}
		final ArrayNode merged = root.putArray("merged");
		for (final MatchResultsView.GroupSummary group : pIn.mergedGroups)
		{
			final ObjectNode node = merged.addObject();
			node.put("k", group.goldenKey);
			node.put("l", MatchResultsSnapshot.cut(group.goldenLabel));
			node.put("s", group.goldenSource);
			final ArrayNode sources = node.putArray("ms");
			for (final String source : group.memberSources)
			{
				sources.add(source);
			}
			final ArrayNode keys = node.putArray("mk");
			for (final String key : group.memberKeys)
			{
				keys.add(key);
			}
		}
		final ArrayNode distinct = root.putArray("distinct");
		for (final MatchResultsView.DistinctPair pair : pIn.distinctPairs)
		{
			final ObjectNode node = distinct.addObject();
			node.put("a", pair.sourceId);
			node.put("b", pair.targetId);
			if (pair.decidedBy != null)
			{
				node.put("by", pair.decidedBy);
			}
			if (pair.decidedAt != null)
			{
				node.put("at", pair.decidedAt);
			}
		}
		return root.toString();
	}

	private static String cut(final String pValue)
	{
		if (pValue == null)
		{
			return "";
		}
		return pValue.length() <= MatchResultsSnapshot.MAX_VALUE_CHARS ? pValue : pValue.substring(0, MatchResultsSnapshot.MAX_VALUE_CHARS - 1) + "…";
	}

	// ------------------------------------------------------------------------------------------------ decode

	/** The snapshot held by the {@link #KEY} member of a run row's detail JSON, or null when there is none, the detail is not JSON, or the shape is another version. */
	public static Decoded fromDetail(final String pDetailJson)
	{
		if (pDetailJson == null || pDetailJson.isEmpty())
		{
			return null;
		}
		try
		{
			final JsonNode root = MatchResultsSnapshot.MAPPER.readTree(pDetailJson);
			return root == null ? null : MatchResultsSnapshot.decode(root.get(MatchResultsSnapshot.KEY));
		}
		catch (final Exception ex)
		{
			return null;
		}
	}

	/** Decodes the JSON produced by {@link #encode}; null when it is not that JSON or carries another version. */
	public static Decoded decode(final String pJson)
	{
		if (pJson == null || pJson.isEmpty())
		{
			return null;
		}
		try
		{
			return MatchResultsSnapshot.decode(MatchResultsSnapshot.MAPPER.readTree(pJson));
		}
		catch (final Exception ex)
		{
			return null;
		}
	}

	private static Decoded decode(final JsonNode pRoot)
	{
		if (pRoot == null || !pRoot.isObject() || pRoot.path("v").asInt(-1) != MatchResultsSnapshot.VERSION)
		{
			return null;
		}
		final MatchResultsView.Input in = new MatchResultsView.Input();
		final JsonNode k = pRoot.path("kpis");
		final Map<String, Integer> bySource = new LinkedHashMap<String, Integer>();
		final Iterator<Map.Entry<String, JsonNode>> sources = k.path("bySource").fields();
		while (sources.hasNext())
		{
			final Map.Entry<String, JsonNode> entry = sources.next();
			bySource.put(entry.getKey(), Integer.valueOf(entry.getValue().asInt(0)));
		}
		in.kpis = new MatchStats.Kpis(k.path("total").asInt(0), bySource, k.path("golden").asInt(0), k.path("merged").asInt(0), k.path("pending").asInt(0),
			k.path("distinct").asInt(0), k.path("unmatched").asInt(0));
		in.goldensWithSuspects = pRoot.path("goldensWithSuspects").asLong(0);
		for (final JsonNode node : pRoot.path("band"))
		{
			in.band.add(new MatchStats.BandSegment(node.path("l").asText(""), node.path("n").asInt(0)));
		}
		in.colorBySource = new LinkedHashMap<String, String>();
		final Iterator<Map.Entry<String, JsonNode>> colors = pRoot.path("colors").fields();
		while (colors.hasNext())
		{
			final Map.Entry<String, JsonNode> entry = colors.next();
			in.colorBySource.put(entry.getKey(), entry.getValue().asText(""));
		}
		final Iterator<Map.Entry<String, JsonNode>> labels = pRoot.path("labels").fields();
		if (labels.hasNext())
		{
			in.fieldLabels = new LinkedHashMap<String, String>();
		}
		while (labels.hasNext())
		{
			final Map.Entry<String, JsonNode> entry = labels.next();
			in.fieldLabels.put(entry.getKey(), entry.getValue().asText(""));
		}
		for (final JsonNode node : pRoot.path("pending"))
		{
			final List<MatchStats.FieldComparison> fields = new ArrayList<MatchStats.FieldComparison>();
			for (final JsonNode f : node.path("f"))
			{
				fields.add(new MatchStats.FieldComparison(f.path("l").asText(""), f.path("n").asText(""), f.path("a").asText(""), f.path("b").asText(""), f.path("ra").asText(""),
					f.path("rb").asText(""), f.path("rn").asText(""), f.path("g").asBoolean(true), f.path("v").asText(MatchStats.MISSING)));
			}
			in.pendingPairs.add(new MatchStats.SuspectPair(node.path("id").asText(""), node.path("g").asText(""), node.path("s").asText(""), node.path("gl").asText(""),
				node.path("sl").asText(""), node.path("gs").asText(""), node.path("ss").asText(""), node.path("gi").asText(""), node.path("si").asText(""), fields,
				MatchStats.PENDING));
		}
		for (final JsonNode node : pRoot.path("groups"))
		{
			final List<MatchStats.GroupMember> members = new ArrayList<MatchStats.GroupMember>();
			for (final JsonNode m : node.path("m"))
			{
				members.add(new MatchStats.GroupMember(m.path("k").asText(""), m.path("s").asText(""), m.path("i").asText(""), m.path("l").asText(""), m.path("g").asBoolean(false)));
			}
			final List<MatchStats.GroupRow> rows = new ArrayList<MatchStats.GroupRow>();
			for (final JsonNode r : node.path("r"))
			{
				final List<String> values = new ArrayList<String>();
				for (final JsonNode v : r.path("v"))
				{
					values.add(v.asText(""));
				}
				final List<String> raws = new ArrayList<String>();
				for (final JsonNode v : r.path("rv"))
				{
					raws.add(v.asText(""));
				}
				rows.add(new MatchStats.GroupRow(r.path("l").asText(""), r.path("n").asText(""), r.path("rn").asText(""), r.path("g").asBoolean(true), values, raws));
			}
			final List<String[]> pairs = new ArrayList<String[]>();
			for (final JsonNode pair : node.path("p"))
			{
				pairs.add(new String[] { pair.path(0).asText(""), pair.path(1).asText("") });
			}
			final List<String> nodeNames = new ArrayList<String>();
			for (final JsonNode name : node.path("nodes"))
			{
				nodeNames.add(name.asText(""));
			}
			in.pendingGroups.add(new MatchStats.SuspectGroup(node.path("id").asText(""), members, rows, pairs, nodeNames));
		}
		in.pendingGroupsTotal = pRoot.path("groupsTotal").asInt(0);
		in.pendingPairsTotal = pRoot.path("pendingTotal").asInt(0);
		in.maxDetailPairs = pRoot.path("maxDetail").asInt(0);
		in.treeDetail = new LinkedHashMap<String, MatchResultsView.TreeDetail>();
		final Iterator<Map.Entry<String, JsonNode>> tree = pRoot.path("tree").fields();
		while (tree.hasNext())
		{
			final Map.Entry<String, JsonNode> entry = tree.next();
			final JsonNode node = entry.getValue();
			final List<String> nodes = new ArrayList<String>();
			for (final JsonNode name : node.path("nodes"))
			{
				nodes.add(name.asText(""));
			}
			final List<MatchResultsView.EngineFieldScore> scores = new ArrayList<MatchResultsView.EngineFieldScore>();
			for (final JsonNode s : node.path("scores"))
			{
				scores.add(new MatchResultsView.EngineFieldScore(s.path("f").asText(""), s.path("s").asDouble(0)));
			}
			in.treeDetail.put(entry.getKey(), new MatchResultsView.TreeDetail(node.path("ok").asBoolean(false), node.has("why") ? node.path("why").asText("") : null, nodes,
				node.has("last") ? node.path("last").asText("") : null, scores));
		}
		for (final JsonNode node : pRoot.path("merged"))
		{
			final List<String> memberSources = new ArrayList<String>();
			for (final JsonNode s : node.path("ms"))
			{
				memberSources.add(s.asText(""));
			}
			final List<String> memberKeys = new ArrayList<String>();
			for (final JsonNode key : node.path("mk"))
			{
				memberKeys.add(key.asText(""));
			}
			in.mergedGroups.add(new MatchResultsView.GroupSummary(node.path("k").asText(""), node.path("l").asText(""), node.path("s").asText(""), memberSources, memberKeys));
		}
		for (final JsonNode node : pRoot.path("distinct"))
		{
			in.distinctPairs.add(new MatchResultsView.DistinctPair(node.path("a").asText(""), node.path("b").asText(""), node.has("by") ? node.path("by").asText("") : null,
				node.has("at") ? node.path("at").asText("") : null));
		}
		return new Decoded(in, pRoot.path("at").asLong(0), pRoot.path("stale").asBoolean(false));
	}

	// ------------------------------------------------------------------------------------------------ detail merge

	/**
	 * {@code pDetailJson} (the run row's existing detail, any JSON object - or blank/not JSON, replaced) with {@code
	 * pSnapshotJson} set as its {@link #KEY} member. Every other member is kept as it was.
	 */
	public static String mergeIntoDetail(final String pDetailJson, final String pSnapshotJson)
	{
		ObjectNode root = null;
		if (pDetailJson != null && !pDetailJson.isEmpty())
		{
			try
			{
				final JsonNode parsed = MatchResultsSnapshot.MAPPER.readTree(pDetailJson);
				if (parsed != null && parsed.isObject())
				{
					root = (ObjectNode) parsed;
				}
			}
			catch (final Exception ex)
			{
				root = null;
			}
		}
		if (root == null)
		{
			root = MatchResultsSnapshot.MAPPER.createObjectNode();
		}
		try
		{
			root.set(MatchResultsSnapshot.KEY, MatchResultsSnapshot.MAPPER.readTree(pSnapshotJson));
		}
		catch (final Exception ex)
		{
			throw new IllegalArgumentException("not a snapshot JSON: " + ex.getMessage(), ex);
		}
		return root.toString();
	}

	/** Size in characters {@code pDetailJson} would have once its {@link #KEY} member is dropped - what is left of the column's budget for a snapshot. */
	public static int sizeWithoutSnapshot(final String pDetailJson)
	{
		if (pDetailJson == null || pDetailJson.isEmpty())
		{
			return 0;
		}
		try
		{
			final JsonNode parsed = MatchResultsSnapshot.MAPPER.readTree(pDetailJson);
			if (parsed != null && parsed.isObject())
			{
				final ObjectNode copy = ((ObjectNode) parsed).deepCopy();
				copy.remove(MatchResultsSnapshot.KEY);
				return copy.toString().length();
			}
		}
		catch (final Exception ex)
		{
			// not JSON: it will be replaced, nothing of it is kept
		}
		return 0;
	}

	// ------------------------------------------------------------------------------------------------ patch

	private static boolean holds(final MatchStats.SuspectGroup pGroup, final String pKey)
	{
		for (final MatchStats.GroupMember member : pGroup.members)
		{
			if (member.key.equals(pKey))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Applies decisions to a stored snapshot so the page shows the effect at once, while the refresh job recomputes the real
	 * figures: each decided pair leaves the pending list (an accepted merge also takes out every other pair that involves the
	 * merged record, which is no longer a golden), the pending total and KPI drop by one per decision (never below zero), a
	 * rejection adds a "Different" line and raises that count. Merged-group and record figures are NOT guessed - they wait for
	 * the refresh. The result is always flagged stale.
	 */
	public static Decoded applyDecisions(final Decoded pDecoded, final List<Decision> pDecisions)
	{
		final MatchResultsView.Input in = pDecoded.input;
		final MatchResultsView.Input out = new MatchResultsView.Input();
		out.entityId = in.entityId;
		out.entityName = in.entityName;
		out.colorBySource = in.colorBySource;
		out.band = in.band;
		out.mergedGroups = in.mergedGroups;
		out.maxDetailPairs = in.maxDetailPairs;
		out.goldensWithSuspects = in.goldensWithSuspects;
		out.fieldLabels = in.fieldLabels;
		final List<MatchStats.SuspectGroup> groupsLeft = new ArrayList<MatchStats.SuspectGroup>(in.pendingGroups);
		int groupsTotal = in.pendingGroupsTotal;
		final List<MatchStats.SuspectPair> pending = new ArrayList<MatchStats.SuspectPair>(in.pendingPairs);
		final Map<String, MatchResultsView.TreeDetail> tree = new LinkedHashMap<String, MatchResultsView.TreeDetail>();
		if (in.treeDetail != null)
		{
			tree.putAll(in.treeDetail);
		}
		final List<MatchResultsView.DistinctPair> distinct = new ArrayList<MatchResultsView.DistinctPair>(in.distinctPairs);
		int total = in.pendingPairsTotal;
		int distinctCount = in.kpis == null ? distinct.size() : in.kpis.distinctPairs;
		for (final Decision decision : pDecisions)
		{
			total = Math.max(0, total - 1);
			for (final Iterator<MatchStats.SuspectPair> it = pending.iterator(); it.hasNext();)
			{
				final MatchStats.SuspectPair pair = it.next();
				final boolean same = (pair.goldenKey.equals(decision.goldenKey) && pair.suspectKey.equals(decision.suspectKey))
					|| (pair.goldenKey.equals(decision.suspectKey) && pair.suspectKey.equals(decision.goldenKey));
				final boolean involvesMerged = decision.accepted && (pair.goldenKey.equals(decision.suspectKey) || pair.suspectKey.equals(decision.suspectKey));
				if (same || involvesMerged)
				{
					it.remove();
					tree.remove(pair.id);
				}
			}
			// a group with two of its records decided together is decided: it leaves the queue (what is left of it, a record set aside, is found again by the refresh)
			for (final Iterator<MatchStats.SuspectGroup> it = groupsLeft.iterator(); it.hasNext();)
			{
				final MatchStats.SuspectGroup group = it.next();
				if (MatchResultsSnapshot.holds(group, decision.goldenKey) && MatchResultsSnapshot.holds(group, decision.suspectKey))
				{
					it.remove();
					groupsTotal = Math.max(0, groupsTotal - 1);
				}
			}
			if (!decision.accepted)
			{
				distinct.add(0, new MatchResultsView.DistinctPair(decision.goldenKey, decision.suspectKey, decision.by, decision.at));
				distinctCount++;
			}
		}
		out.pendingPairs = pending;
		out.pendingGroups = groupsLeft;
		out.pendingGroupsTotal = groupsTotal;
		out.pendingPairsTotal = total;
		out.treeDetail = tree;
		out.distinctPairs = distinct;
		out.kpis = in.kpis == null ? null
			: new MatchStats.Kpis(in.kpis.totalRecords, in.kpis.recordsBySource, in.kpis.goldenRecords, in.kpis.mergedGroups, total, distinctCount,
				in.kpis.unmatchedRecords);
		return new Decoded(out, pDecoded.computedAt, true);
	}
}
