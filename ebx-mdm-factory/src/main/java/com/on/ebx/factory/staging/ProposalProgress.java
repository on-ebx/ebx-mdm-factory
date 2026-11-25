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
 * Staging-proposal-run lot (2026-09-24, docs/STAGING-PROPOSAL.md "run"): the JSON progress model of one
 * one-click proposal run - stage keys, state per stage, start/end times, a one-line summary - written by
 * {@code ebx.StagingProposalRun} into the parent {@code PipelineStep.detail} it owns ({@code
 * STAGING_PROPOSAL}), and read back by the wizard's "while running" screen. Pure: no EBX class, so it is
 * unit-tested directly (round-trip through JSON, stage ordering, state transitions) without a live
 * repository - the only class of this lot that touches EBX is {@code ebx.StagingProposalRun} itself, the
 * same split every other pure/EBX-bound pair in this package already keeps.
 * <p>
 * {@link #STAGES}, in run order: {@link #STRUCTURE_RULE} (a, the deterministic rule pass), {@link
 * #STRUCTURE_LLM} (b, the language-model structure pass, chained when its own gate allows), {@link
 * #PROFILE} (c, {@code LandingColumnProfiler}), {@link #ANOMALY} (d, the anomaly pass), {@link #QUALITY}
 * (e, the quality-rule language-model pass, scope {@code PROPOSAL}), {@link #CAPABILITY} (f, the script-
 * check/capability language-model pass, scope {@code PROPOSAL}). {@link #STRUCTURE_LLM}/{@link #ANOMALY}/
 * {@link #QUALITY}/{@link #CAPABILITY} depend on {@link #STRUCTURE_RULE} having produced something to work
 * over - {@code StagingProposalRun}'s own Javadoc states the exact dependency graph and what each stage is
 * {@link #SKIPPED} for.
 */
public final class ProposalProgress
{
	public static final String STRUCTURE_RULE = "STRUCTURE_RULE";
	public static final String STRUCTURE_LLM = "STRUCTURE_LLM";
	public static final String PROFILE = "PROFILE";
	public static final String ANOMALY = "ANOMALY";
	public static final String QUALITY = "QUALITY";
	public static final String CAPABILITY = "CAPABILITY";

	/** Run order - also the order {@link #toJson()} writes stages in. */
	public static final List<String> STAGES = java.util.Collections.unmodifiableList(
		java.util.Arrays.asList(ProposalProgress.STRUCTURE_RULE, ProposalProgress.STRUCTURE_LLM, ProposalProgress.PROFILE, ProposalProgress.ANOMALY,
			ProposalProgress.QUALITY, ProposalProgress.CAPABILITY));

	public static final String TODO = "TODO";
	public static final String RUNNING = "RUNNING";
	public static final String DONE = "DONE";
	public static final String FAILED = "FAILED";
	public static final String SKIPPED = "SKIPPED";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One stage's own state - immutable, {@link ProposalProgress} replaces it wholesale on every transition. */
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

		public Stage(final String pKey, final String pState, final Long pStartedAt, final Long pEndedAt, final String pSummary)
		{
			this.key = pKey;
			this.state = pState;
			this.startedAt = pStartedAt;
			this.endedAt = pEndedAt;
			this.summary = pSummary;
		}
	}

	private final Map<String, Stage> byKey = new LinkedHashMap<String, Stage>();

	private ProposalProgress()
	{
	}

	/** Every stage {@link #TODO}, none started yet - the shape written the instant a run is accepted, before its background thread has done anything. */
	public static ProposalProgress newRun()
	{
		final ProposalProgress out = new ProposalProgress();
		for (final String stage : ProposalProgress.STAGES)
		{
			out.byKey.put(stage, new Stage(stage, ProposalProgress.TODO, null, null, null));
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

	/** True once every stage is {@link #DONE}/{@link #FAILED}/{@link #SKIPPED} - none left {@link #TODO}/{@link #RUNNING}. */
	public boolean isOver()
	{
		for (final Stage stage : this.byKey.values())
		{
			if (ProposalProgress.TODO.equals(stage.state) || ProposalProgress.RUNNING.equals(stage.state))
			{
				return false;
			}
		}
		return true;
	}

	public void start(final String pKey, final long pAtMillis)
	{
		this.byKey.put(pKey, new Stage(pKey, ProposalProgress.RUNNING, Long.valueOf(pAtMillis), null, null));
	}

	public void finish(final String pKey, final boolean pOk, final String pSummary, final long pAtMillis)
	{
		final Stage before = this.byKey.get(pKey);
		final Long startedAt = before == null ? null : before.startedAt;
		this.byKey.put(pKey, new Stage(pKey, pOk ? ProposalProgress.DONE : ProposalProgress.FAILED, startedAt, Long.valueOf(pAtMillis), pSummary));
	}

	public void skip(final String pKey, final String pReason)
	{
		this.byKey.put(pKey, new Stage(pKey, ProposalProgress.SKIPPED, null, null, pReason));
	}

	public String toJson()
	{
		final ObjectNode root = ProposalProgress.MAPPER.createObjectNode();
		final ArrayNode stagesNode = root.putArray("stages");
		for (final String key : ProposalProgress.STAGES)
		{
			final Stage stage = this.byKey.get(key);
			final ObjectNode stageNode = stagesNode.addObject();
			stageNode.put("key", key);
			if (stage == null)
			{
				stageNode.put("state", ProposalProgress.TODO);
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
		}
		return root.toString();
	}

	/** Never throws: a null/blank/unparsable {@code pJson} (no run yet, or a corrupted/truncated detail) returns {@link #newRun}'s own all-{@code TODO} shape. */
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
		final JsonNode stagesNode = root.path("stages");
		if (!stagesNode.isArray())
		{
			return out;
		}
		for (final JsonNode stageNode : stagesNode)
		{
			final String key = stageNode.path("key").asText(null);
			if (key == null || !out.byKey.containsKey(key))
			{
				continue;
			}
			final String state = stageNode.path("state").asText(ProposalProgress.TODO);
			final Long startedAt = stageNode.hasNonNull("startedAt") ? Long.valueOf(stageNode.path("startedAt").asLong()) : null;
			final Long endedAt = stageNode.hasNonNull("endedAt") ? Long.valueOf(stageNode.path("endedAt").asLong()) : null;
			final String summary = stageNode.hasNonNull("summary") ? stageNode.path("summary").asText() : null;
			out.byKey.put(key, new Stage(key, state, startedAt, endedAt, summary));
		}
		return out;
	}
}
