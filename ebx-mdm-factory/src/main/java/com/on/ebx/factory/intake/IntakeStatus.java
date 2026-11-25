package com.on.ebx.factory.intake;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Automatic intake lot: the status DTO {@code ebx.Intake#status} fills and {@code GET /projects/{code}/intake} returns - the contract with the screens
 * (docs/AUTOMATIC-INTAKE.md "IntakeStatus"). Pure data + JSON ({@link #toJson}), no EBX class. Every field is documented where it is declared; absent
 * values are JSON {@code null} (never missing keys), so a screen can read any field without testing for it.
 * <p>
 * Times are ISO-8601 UTC instants with seconds ({@code 2026-10-05T12:00:00Z}); {@link #serverTime} is the clock the relative values ("12 s ago", "at the
 * latest 14:50") must be computed against, not the browser's.
 */
public final class IntakeStatus
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static final String KIND_CONNECTORS = "CONNECTORS";
	public static final String KIND_PUSHED = "PUSHED";
	public static final String STATE_RUNNING = "RUNNING";

	/** One application of the project. */
	public static final class Source
	{
		/** {@code ProjectSource.sourceSystem}: the application's code, also the landing dataset name and the {@code {source}} of the batch-complete path. */
		public String sourceSystem;
		/** A person-readable name (the source's catalog label when known, else the code). */
		public String label;
		/** {@code CONNECTORS}: the Factory runs a Connectors flow that reads it (it knows when a read ends, so it needs nothing). {@code PUSHED}: the application writes into the landing itself. */
		public String kind;
		public int quietSeconds;
		public int maxWaitMinutes;
		public int minRows;
		public boolean batchSignal;
		/** Rows at NEW counted by the engine at its last count (when due, after a chain, at startup, by the sweep); null when never counted since the engine started. Never counted on the status path. */
		public Long rowsWaiting;
		/** The signal's {@code lastRowAt}: when a waiting row was last written (at most 5 s late); null when none. */
		public Instant lastRowAt;
		/** When the engine first saw rows waiting in the current period; null when nothing waits. */
		public Instant pendingSince;
		/** When the chain starts if nothing new arrives (or now if it is due): null when nothing waits. */
		public Instant dueAt;
		/** {@code pendingSince + longest wait}: the chain starts at the latest then; null when nothing waits. */
		public Instant latestAt;
		/** {@code IDLE} (nothing waits), {@code RECEIVING} (rows wait, not due), {@code DUE} (due: a chain starts at the next tick), {@code RUNNING} (a chain of the project runs and includes the application). */
		public String state;
		/** Why it would start: {@code QUIET}, {@code MAX_WAIT}, {@code BATCH_COMPLETE}, {@code MANUAL}, {@code SWEEP}; null when nothing waits. */
		public String reason;
		/** The REST path an application calls when its batch is complete (POST, no body), e.g. {@code /ebx-mdm-factory/rest/v1/projects/Personnes/intake/Salesforce:complete}. */
		public String batchCompletePath;
	}

	/** One chain (an instance of the workflow MdmFactoryPipeline the engine launched): a row of {@code IntakeChain}. */
	public static final class Chain
	{
		public String id;
		public Instant startedAt;
		/** Null while running. */
		public Instant endedAt;
		/** {@code QUIET}, {@code MAX_WAIT}, {@code BATCH_COMPLETE}, {@code MANUAL}, {@code SWEEP}. */
		public String reason;
		/** Source systems that had rows waiting. */
		public List<String> sources = new ArrayList<String>();
		/** New rows counted at launch (null when unknown). */
		public Long rowsWaiting;
		public Long written;
		public Long unchanged;
		/** Rows held back (transfer rejects of the chain's staging load). */
		public Long rejected;
		/** {@code RUNNING}, {@code DONE}, {@code DONE_WITH_REJECTS}, {@code STOPPED}, {@code FAILED}. */
		public String status;
		public String message;
		public String workflowKey;
	}

	/** The published pipeline model, as far as the intake is concerned. */
	public static final class Pipeline
	{
		/** The MdmFactoryPipeline publication exists and is enabled. */
		public boolean published;
		/** It declares the {@code intakeChain} input (an older publication does not). */
		public boolean declaresIntake;
		/** The publication does not exist (or is disabled). */
		public static final String NOT_PUBLISHED = "NOT_PUBLISHED";
		/** The publication exists but predates automatic intake. */
		public static final String PREDATES_INTAKE = "PREDATES_INTAKE";
		/** {@link #NOT_PUBLISHED} or {@link #PREDATES_INTAKE} when {@code !declaresIntake}, else null (audit F2-108: a code, not an English sentence). */
		public String problemCode;
	}

	/** {@code RejectReview.warning} when the review workflow is not published (a code the screens word; audit F2-108). */
	public static final String REVIEW_NOT_PUBLISHED = "REVIEW_NOT_PUBLISHED";

	/** The non-blocking review of the rows held back. */
	public static final class RejectReview
	{
		/** A review workflow launched by the engine still runs. */
		public boolean open;
		/** Unresolved staging rejects of the project. */
		public Long heldBack;
		/** E.g. the MdmFactoryRejectReview publication does not exist: the chain is still reported, the review is not opened. Null when fine. */
		public String warning;
	}

	public String project;
	/** {@code OFF}, {@code ON}, {@code PAUSED}. */
	public String state;
	public String pauseReason;
	public String enabledBy;
	public Instant enabledAt;
	public boolean reviewRejects = true;
	/** 1-100 or null (never stop). */
	public Integer stopRejectPercent;
	/** Every blocking readiness line is ok: the switch-on is allowed. */
	public boolean ready;
	public List<IntakeReadiness.Line> readiness = new ArrayList<IntakeReadiness.Line>();
	public Pipeline pipeline = new Pipeline();
	public List<Source> sources = new ArrayList<Source>();
	/** The running chain, or null. */
	public Chain currentChain;
	/** "Process now" was pressed while a chain ran: a chain starts right after it. */
	public boolean queuedAfterChain;
	/** The last chains, newest first (the running one included). */
	public List<Chain> chains = new ArrayList<Chain>();
	public RejectReview rejectReview = new RejectReview();
	/** True when the engine thread runs in this JVM (false: it failed to start, nothing starts by itself). */
	public boolean engineRunning;
	/** The CALLING session may change the intake (settings, switch on/off, pause, resume, process now): an administrator or a holder of the role of setting {@code pipelineLauncherRole}. Hide those actions when false. */
	public boolean mayOperate;
	public Instant serverTime;

	private static void put(final ObjectNode pNode, final String pName, final Instant pValue)
	{
		if (pValue == null)
		{
			pNode.putNull(pName);
		}
		else
		{
			pNode.put(pName, pValue.toString());
		}
	}

	private static void put(final ObjectNode pNode, final String pName, final Long pValue)
	{
		if (pValue == null)
		{
			pNode.putNull(pName);
		}
		else
		{
			pNode.put(pName, pValue.longValue());
		}
	}

	private static void put(final ObjectNode pNode, final String pName, final String pValue)
	{
		if (pValue == null)
		{
			pNode.putNull(pName);
		}
		else
		{
			pNode.put(pName, pValue);
		}
	}

	private static ObjectNode chainJson(final Chain pChain)
	{
		final ObjectNode node = IntakeStatus.MAPPER.createObjectNode();
		IntakeStatus.put(node, "id", pChain.id);
		IntakeStatus.put(node, "startedAt", pChain.startedAt);
		IntakeStatus.put(node, "endedAt", pChain.endedAt);
		IntakeStatus.put(node, "reason", pChain.reason);
		final ArrayNode sources = node.putArray("sources");
		for (final String source : pChain.sources)
		{
			sources.add(source);
		}
		IntakeStatus.put(node, "rowsWaiting", pChain.rowsWaiting);
		IntakeStatus.put(node, "written", pChain.written);
		IntakeStatus.put(node, "unchanged", pChain.unchanged);
		IntakeStatus.put(node, "rejected", pChain.rejected);
		IntakeStatus.put(node, "status", pChain.status);
		IntakeStatus.put(node, "message", pChain.message);
		IntakeStatus.put(node, "workflowKey", pChain.workflowKey);
		return node;
	}

	/** The JSON of the contract. Keys are always present, absent values are null. */
	public ObjectNode toJson()
	{
		final ObjectNode out = IntakeStatus.MAPPER.createObjectNode();
		IntakeStatus.put(out, "project", this.project);
		IntakeStatus.put(out, "state", this.state);
		IntakeStatus.put(out, "pauseReason", this.pauseReason);
		IntakeStatus.put(out, "enabledBy", this.enabledBy);
		IntakeStatus.put(out, "enabledAt", this.enabledAt);
		out.put("reviewRejects", this.reviewRejects);
		if (this.stopRejectPercent == null)
		{
			out.putNull("stopRejectPercent");
		}
		else
		{
			out.put("stopRejectPercent", this.stopRejectPercent.intValue());
		}
		out.put("ready", this.ready);
		final ArrayNode readiness = out.putArray("readiness");
		for (final IntakeReadiness.Line line : this.readiness)
		{
			final ObjectNode node = readiness.addObject();
			node.put("key", line.key);
			node.put("ok", line.ok);
			node.put("blocking", line.blocking);
			node.put("text", line.text);
			IntakeStatus.put(node, "count", line.count);
		}
		final ObjectNode pipeline = out.putObject("pipeline");
		pipeline.put("published", this.pipeline.published);
		pipeline.put("declaresIntake", this.pipeline.declaresIntake);
		IntakeStatus.put(pipeline, "problemCode", this.pipeline.problemCode);
		final ArrayNode sources = out.putArray("sources");
		for (final Source source : this.sources)
		{
			final ObjectNode node = sources.addObject();
			node.put("sourceSystem", source.sourceSystem);
			IntakeStatus.put(node, "label", source.label);
			node.put("kind", source.kind);
			final ObjectNode settings = node.putObject("settings");
			settings.put("quietSeconds", source.quietSeconds);
			settings.put("maxWaitMinutes", source.maxWaitMinutes);
			settings.put("minRows", source.minRows);
			settings.put("batchSignal", source.batchSignal);
			IntakeStatus.put(node, "rowsWaiting", source.rowsWaiting);
			IntakeStatus.put(node, "lastRowAt", source.lastRowAt);
			IntakeStatus.put(node, "pendingSince", source.pendingSince);
			IntakeStatus.put(node, "dueAt", source.dueAt);
			IntakeStatus.put(node, "latestAt", source.latestAt);
			node.put("state", source.state);
			IntakeStatus.put(node, "reason", source.reason);
			IntakeStatus.put(node, "batchCompletePath", source.batchCompletePath);
		}
		if (this.currentChain == null)
		{
			out.putNull("currentChain");
		}
		else
		{
			out.set("currentChain", IntakeStatus.chainJson(this.currentChain));
		}
		out.put("queuedAfterChain", this.queuedAfterChain);
		final ArrayNode chains = out.putArray("chains");
		for (final Chain chain : this.chains)
		{
			chains.add(IntakeStatus.chainJson(chain));
		}
		final ObjectNode review = out.putObject("rejectReview");
		review.put("open", this.rejectReview.open);
		IntakeStatus.put(review, "heldBack", this.rejectReview.heldBack);
		IntakeStatus.put(review, "warning", this.rejectReview.warning);
		out.put("engineRunning", this.engineRunning);
		out.put("mayOperate", this.mayOperate);
		IntakeStatus.put(out, "serverTime", this.serverTime);
		return out;
	}
}
