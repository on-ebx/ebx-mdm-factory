package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lot C3 (2026-09-24): the wizard's stand-alone Apply/Re-apply and Measure-again actions on a {@code
 * SCRIPT_CONSTRAINT} capability suggestion ({@code ProjectWizardUserService}'s {@code REAPPLY}/{@code MEASURE}),
 * as jobs of {@link FactoryJobQueue} instead of running inside the HTTP postback.
 * <p>
 * {@link CapabilityApplier#apply} publishes a data-model script - the same MCP-heavy family as {@code
 * StagingGenerator}/{@code MasterGenerator} ({@link StagingLoadRun}/{@link MasterBuildRun}'s own Javadoc). {@link
 * CapabilityApplier#measure} walks the WHOLE target table's validation report ({@code CapabilityApplier}'s own
 * Javadoc: "{@code AdaptationTable.getValidationReport()} walks the whole table"). Both act on the model of ONE
 * zone (STAGING or MASTER, {@code CapabilitySuggestion.zone}) - applying (or even just measuring) a constraint
 * while that same zone's build republishes its model would race the exact same model, exactly the reason {@link
 * StagingLoadRun}/{@link MasterBuildRun} themselves hold the STAGING/MASTER resource. Since {@link FactoryJobQueue}
 * binds one fixed resource per KIND at {@link FactoryJobQueue#register}, this class registers FOUR kinds - apply
 * and measure, crossed with the two zones - rather than one kind whose resource would need to vary per job; {@link
 * #applyKindFor}/{@link #measureKindFor} pick the right one from the suggestion's own zone, read server-side
 * (never trust the client - same discipline {@code WizardData.capabilityZone}'s own callers already follow).
 * <p>
 * Neither call opens (or needs) a FRESH parent {@code PipelineStep} from this class: {@link CapabilityApplier#apply}
 * already begins/ends its own {@code CAPABILITY_APPLY} step (scoped to the suggestion id) internally, and {@link
 * CapabilityApplier#measure} is deliberately not traced as a step at all (its own Javadoc: "a lightweight,
 * repeatable read+write, unlike apply/remove's own model-changing sequence") - so {@link FactoryJobs#attach} is
 * never called here; the jobs banner still follows the job itself (queued/running/done, and the final result
 * message below) from the {@code FactoryJob} row alone.
 */
public final class CapabilityApplyRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static final String APPLY_STAGING = "CAPABILITY_APPLY_STAGING";
	public static final String APPLY_MASTER = "CAPABILITY_APPLY_MASTER";
	public static final String MEASURE_STAGING = "CAPABILITY_MEASURE_STAGING";
	public static final String MEASURE_MASTER = "CAPABILITY_MEASURE_MASTER";
	/** Audit F-34: taking an applied constraint or derivation back off the model republishes it - queued like the apply, same resource. */
	public static final String REMOVE_STAGING = "CAPABILITY_REMOVE_STAGING";
	public static final String REMOVE_MASTER = "CAPABILITY_REMOVE_MASTER";
	/** Audit F-34 (wave 10): the proposal review's "Apply selected" and the apply that follows a bulk accept - several suggestions of one zone in ONE job, so the model is republished once, as {@link CapabilityDispatcher#applyAll} batches them. */
	public static final String APPLY_ALL_STAGING = "CAPABILITY_APPLY_ALL_STAGING";
	public static final String APPLY_ALL_MASTER = "CAPABILITY_APPLY_ALL_MASTER";

	private static final String ZONE_MASTER = "MASTER";
	private static final String ZONE_STAGING = "STAGING";

	private CapabilityApplyRun()
	{
	}

	/** The Apply/Re-apply job kind for {@code pZone} ("STAGING"/"MASTER") - null for any other value (the caller re-checks before enqueuing, like every other action gate in this package). */
	public static String applyKindFor(final String pZone)
	{
		if (CapabilityApplyRun.ZONE_MASTER.equals(pZone))
		{
			return CapabilityApplyRun.APPLY_MASTER;
		}
		return CapabilityApplyRun.ZONE_STAGING.equals(pZone) ? CapabilityApplyRun.APPLY_STAGING : null;
	}

	/** The Measure job kind for {@code pZone}, same rule as {@link #applyKindFor}. */
	public static String measureKindFor(final String pZone)
	{
		if (CapabilityApplyRun.ZONE_MASTER.equals(pZone))
		{
			return CapabilityApplyRun.MEASURE_MASTER;
		}
		return CapabilityApplyRun.ZONE_STAGING.equals(pZone) ? CapabilityApplyRun.MEASURE_STAGING : null;
	}

	/** Audit F-34: the Remove job kind for {@code pZone}, same rule as {@link #applyKindFor}. */
	public static String removeKindFor(final String pZone)
	{
		if (CapabilityApplyRun.ZONE_MASTER.equals(pZone))
		{
			return CapabilityApplyRun.REMOVE_MASTER;
		}
		return CapabilityApplyRun.ZONE_STAGING.equals(pZone) ? CapabilityApplyRun.REMOVE_STAGING : null;
	}

	/** Audit F-34 (wave 10): the batch-apply job kind for {@code pZone}, same rule as {@link #applyKindFor}. */
	public static String applyAllKindFor(final String pZone)
	{
		if (CapabilityApplyRun.ZONE_MASTER.equals(pZone))
		{
			return CapabilityApplyRun.APPLY_ALL_MASTER;
		}
		return CapabilityApplyRun.ZONE_STAGING.equals(pZone) ? CapabilityApplyRun.APPLY_ALL_STAGING : null;
	}

	/** Pure: groups suggestion ids by the zone {@code pZones} names for each ("STAGING"/"MASTER"; an id whose zone is unknown goes under ""), keeping the order of {@code pIds}. */
	public static Map<String, List<String>> groupByZone(final List<String> pIds, final Map<String, String> pZones)
	{
		final Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
		for (final String id : pIds)
		{
			final String zone = pZones.get(id);
			final String key = CapabilityApplyRun.ZONE_MASTER.equals(zone) || CapabilityApplyRun.ZONE_STAGING.equals(zone) ? zone : "";
			List<String> list = out.get(key);
			if (list == null)
			{
				list = new ArrayList<String>();
				out.put(key, list);
			}
			list.add(id);
		}
		return out;
	}

	/**
	 * Audit F-34 (wave 10): queues the application of several suggestions of ONE zone ({@code pKind} from {@link #applyAllKindFor}) as one job; {@code pLocale} writes its
	 * result sentence. The same ids queued twice fold into the one job.
	 */
	public static ObjectNode startApplyAll(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pKind, final String pProjectCode,
		final List<String> pSuggestionIds) throws OperationException
	{
		final ObjectNode params = CapabilityApplyRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", (pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale()).toLanguageTag());
		final ArrayNode ids = params.putArray("suggestionIds");
		for (final String id : pSuggestionIds)
		{
			ids.add(id);
		}
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProjectId, pKind, params);
		final ObjectNode out = CapabilityApplyRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** Queues an apply/re-apply (or a measure, {@code pKind} picked by {@link #applyKindFor}/{@link #measureKindFor}) of one suggestion id - never refused, like every other job start in this package (a busy resource simply keeps the job in line). */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pKind, final String pProjectCode,
		final String pSuggestionId) throws OperationException
	{
		final ObjectNode params = CapabilityApplyRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("suggestionId", pSuggestionId);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProjectId, pKind, params);
		final ObjectNode out = CapabilityApplyRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The job's work for either apply kind - registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler APPLY_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String suggestionId = pParams.path("suggestionId").asText("");
			// lot M5 (2026-10-04): by the row's own nature - a SCRIPT_DERIVATION goes to DerivationApplier
			CapabilityDispatcher.apply(pJobSession, pFactory, projectCode, suggestionId, null);
			return "Accepted and applied to the model.";
		}
	};

	/** Audit F-34: the job's work for either remove kind - {@link CapabilityDispatcher#remove}, which the wizard's Remove ran inside the request. */
	static final FactoryJobQueue.Handler REMOVE_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String suggestionId = pParams.path("suggestionId").asText("");
			CapabilityDispatcher.remove(pJobSession, pFactory, projectCode, suggestionId);
			return "Removed from the model.";
		}
	};

	/** Audit F-34 (wave 10): the job's work for either apply-all kind - {@link CapabilityDispatcher#applyAll}, which the proposal review ran inside the request. A suggestion that fails to apply stays ACCEPTED with its own message; the job then ends FAILED so the notice says so. */
	static final FactoryJobQueue.Handler APPLY_ALL_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String tag = pParams.path("locale").asText("");
			final Locale locale = tag.isEmpty() ? Locale.ENGLISH : Locale.forLanguageTag(tag);
			final List<String> ids = new ArrayList<String>();
			for (final JsonNode id : pParams.path("suggestionIds"))
			{
				ids.add(id.asText(""));
			}
			final ObjectNode result = CapabilityDispatcher.applyAll(pJobSession, pFactory, projectCode, ids, null);
			final int applied = result.path("applied").asInt(0);
			final int failed = result.path("failed").asInt(0);
			final String summary = WizardMessages.get(locale, "review.bulk.applied.summary", Integer.valueOf(applied), Integer.valueOf(failed));
			if (failed > 0)
			{
				throw new FactoryJobQueue.ExpectedFailure(summary);
			}
			return summary;
		}
	};

	/** The job's work for either measure kind - see {@link #APPLY_HANDLER}. */
	static final FactoryJobQueue.Handler MEASURE_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String suggestionId = pParams.path("suggestionId").asText("");
			CapabilityApplier.measure(pJobSession, pFactory, projectCode, suggestionId);
			return "Measured again.";
		}
	};
}
