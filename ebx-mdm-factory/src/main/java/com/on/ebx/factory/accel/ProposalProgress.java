package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot C1-C: the JSON progress of one adapted-proposal run (passes 1 to 4), written by {@code ebx.AdaptationProposalRun} into the parent
 * {@code PipelineStep.detail} ({@code ACCEL_PROPOSAL}) and polled by the wizard, like {@code staging.ProposalProgress}. Pure. Each stage
 * also carries the counts of its pass: lines written, lines held "to place", lines refused, lines dropped (out of pass), KEEP rows.
 */
public final class ProposalProgress
{
	public static final String MODEL = "MODEL";
	public static final String RULES = "RULES";
	public static final String FORMS = "FORMS";
	public static final String WORKFLOWS = "WORKFLOWS";

	/** Run order = pass 1 to 4. */
	public static final List<String> STAGES = Collections.unmodifiableList(Arrays.asList(MODEL, RULES, FORMS, WORKFLOWS));

	public static final String TODO = "TODO";
	public static final String RUNNING = "RUNNING";
	public static final String DONE = "DONE";
	public static final String FAILED = "FAILED";
	public static final String SKIPPED = "SKIPPED";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Stage key of a pass number (1 to 4). */
	public static String keyOf(final int pPass)
	{
		if (pPass < 1 || pPass > ProposalProgress.STAGES.size())
		{
			throw new IllegalArgumentException("pass " + pPass);
		}
		return ProposalProgress.STAGES.get(pPass - 1);
	}

	/** Pass number of a stage key. */
	public static int passOf(final String pKey)
	{
		final int i = ProposalProgress.STAGES.indexOf(pKey);
		if (i < 0)
		{
			throw new IllegalArgumentException(pKey);
		}
		return i + 1;
	}

	/** What a pass wrote and what the Java check did with the rest. */
	public static final class Counts
	{
		/** Lines written and placed. */
		public int kept;
		/** Lines written with {@code placeState = TO_PLACE}. */
		public int toPlace;
		/** KEEP rows written (one per untouched pack table, pass 1). */
		public int keepRows;
		/** Lines still refused after the re-ask: not written. */
		public int refused;
		/** Lines whose nature is outside the pass: not written. */
		public int dropped;
		/** Lines beyond the cap of the pass or unreadable: not written. */
		public int ignored;
		/** Whether the answer was cut off or not readable at all. */
		public boolean noAnswer;

		/** Lines a person has to decide. */
		public int written()
		{
			return this.kept + this.toPlace;
		}
	}

	/** One stage's state, immutable. */
	public static final class Stage
	{
		public final String key;
		public final String state;
		public final Long startedAt;
		public final Long endedAt;
		public final String summary;
		public final Counts counts;

		public Stage(final String pKey, final String pState, final Long pStartedAt, final Long pEndedAt, final String pSummary, final Counts pCounts)
		{
			this.key = pKey;
			this.state = pState;
			this.startedAt = pStartedAt;
			this.endedAt = pEndedAt;
			this.summary = pSummary;
			this.counts = pCounts;
		}
	}

	private final Map<String, Stage> byKey = new LinkedHashMap<String, Stage>();

	private ProposalProgress()
	{
	}

	public static ProposalProgress newRun()
	{
		final ProposalProgress out = new ProposalProgress();
		for (final String key : ProposalProgress.STAGES)
		{
			out.byKey.put(key, new Stage(key, ProposalProgress.TODO, null, null, null, null));
		}
		return out;
	}

	public Stage stage(final String pKey)
	{
		return this.byKey.get(pKey);
	}

	public List<Stage> stages()
	{
		final List<Stage> out = new ArrayList<Stage>();
		for (final String key : ProposalProgress.STAGES)
		{
			out.add(this.byKey.get(key));
		}
		return out;
	}

	public boolean isOver()
	{
		for (final Stage s : this.byKey.values())
		{
			if (ProposalProgress.TODO.equals(s.state) || ProposalProgress.RUNNING.equals(s.state))
			{
				return false;
			}
		}
		return true;
	}

	public void start(final String pKey, final long pAtMillis)
	{
		this.byKey.put(pKey, new Stage(pKey, ProposalProgress.RUNNING, Long.valueOf(pAtMillis), null, null, null));
	}

	public void finish(final String pKey, final boolean pOk, final String pSummary, final Counts pCounts, final long pAtMillis)
	{
		final Stage before = this.byKey.get(pKey);
		this.byKey.put(pKey, new Stage(pKey, pOk ? ProposalProgress.DONE : ProposalProgress.FAILED, before == null ? null : before.startedAt, Long.valueOf(pAtMillis),
			pSummary, pCounts));
	}

	public void skip(final String pKey, final String pReason)
	{
		this.byKey.put(pKey, new Stage(pKey, ProposalProgress.SKIPPED, null, null, pReason, null));
	}

	public String toJson()
	{
		final ObjectNode root = ProposalProgress.MAPPER.createObjectNode();
		final ArrayNode stages = root.putArray("stages");
		int finished = 0;
		for (final String key : ProposalProgress.STAGES)
		{
			final Stage s = this.byKey.get(key);
			if (s != null && !ProposalProgress.TODO.equals(s.state) && !ProposalProgress.RUNNING.equals(s.state))
			{
				finished++;
			}
			final ObjectNode n = stages.addObject();
			n.put("key", key);
			n.put("pass", ProposalProgress.passOf(key));
			n.put("state", s == null ? ProposalProgress.TODO : s.state);
			if (s == null)
			{
				continue;
			}
			if (s.startedAt != null)
			{
				n.put("startedAt", s.startedAt.longValue());
			}
			if (s.endedAt != null)
			{
				n.put("endedAt", s.endedAt.longValue());
			}
			if (s.summary != null)
			{
				n.put("summary", s.summary);
			}
			if (s.counts != null)
			{
				final ObjectNode c = n.putObject("counts");
				c.put("kept", s.counts.kept);
				c.put("toPlace", s.counts.toPlace);
				c.put("keepRows", s.counts.keepRows);
				c.put("refused", s.counts.refused);
				c.put("dropped", s.counts.dropped);
				c.put("ignored", s.counts.ignored);
				c.put("noAnswer", s.counts.noAnswer);
			}
		}
		// the wizard's bar reads these two (AccelPages.renderJob)
		root.put("done", finished);
		root.put("total", ProposalProgress.STAGES.size());
		return root.toString();
	}

	/** Never throws: null, blank or unreadable gives {@link #newRun}. */
	public static ProposalProgress parse(final String pJson)
	{
		final ProposalProgress out = ProposalProgress.newRun();
		if (pJson == null || pJson.trim().isEmpty())
		{
			return out;
		}
		final JsonNode root;
		try
		{
			root = ProposalProgress.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			return out;
		}
		final JsonNode stages = root == null ? null : root.path("stages");
		if (stages == null || !stages.isArray())
		{
			return out;
		}
		for (final JsonNode n : stages)
		{
			final String key = n.path("key").asText(null);
			if (key == null || !out.byKey.containsKey(key))
			{
				continue;
			}
			Counts counts = null;
			if (n.path("counts").isObject())
			{
				final JsonNode c = n.get("counts");
				counts = new Counts();
				counts.kept = c.path("kept").asInt(0);
				counts.toPlace = c.path("toPlace").asInt(0);
				counts.keepRows = c.path("keepRows").asInt(0);
				counts.refused = c.path("refused").asInt(0);
				counts.dropped = c.path("dropped").asInt(0);
				counts.ignored = c.path("ignored").asInt(0);
				counts.noAnswer = c.path("noAnswer").asBoolean(false);
			}
			out.byKey.put(key, new Stage(key, n.path("state").asText(ProposalProgress.TODO),
				n.hasNonNull("startedAt") ? Long.valueOf(n.path("startedAt").asLong()) : null,
				n.hasNonNull("endedAt") ? Long.valueOf(n.path("endedAt").asLong()) : null, n.hasNonNull("summary") ? n.path("summary").asText() : null, counts));
		}
		return out;
	}
}
