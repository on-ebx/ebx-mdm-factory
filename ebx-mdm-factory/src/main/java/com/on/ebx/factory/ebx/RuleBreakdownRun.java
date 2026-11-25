package com.on.ebx.factory.ebx;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.quality.FailureBreakdown;
import com.on.ebx.factory.quality.RuleFocus;
import com.on.ebx.factory.stagingdesk.DeskMessages;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Validation desk lot L4 (2026-10-22; L4b same day: it then asks the model about a condition, {@link QualityLlmSuggester#suggestForRule}): the "Measure" button of a check that was never measured - where does this rule fail? One background job (kind {@link #STEP}, resource
 * {@link FactoryJobs#DESIGN}, shared with the proposal passes so it never runs over them) that calls {@link FailureBreakdowns#measureRule}: a whole-table read per grouping
 * attribute, which must not block a postback. The rule id travels in the job's params, so two clicks on the same check fold into one job.
 */
public final class RuleBreakdownRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "QUALITY_BREAKDOWN";

	private RuleBreakdownRun()
	{
	}

	/** Queues the measure of one rule of the project and returns at once. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pRuleId) throws OperationException
	{
		// the rule must be one of this project's: a posted id is never trusted (rule -> entity -> project)
		final Adaptation rule = pRuleId == null || pRuleId.isEmpty() ? null
			: pFactory.getTable(FactoryPaths.QUALITY_RULE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRuleId));
		final Adaptation entity = rule == null ? null
			: pFactory.getTable(FactoryPaths.STAGING_ENTITY).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(rule.getString(FactoryPaths.QualityRule.ENTITY)));
		if (entity == null || !pProjectId.equals(entity.getString(FactoryPaths.StagingEntity.PROJECT)))
		{
			throw EngineRefusal.coded("missing.qualityRule", "The check " + pRuleId + " does not belong to this project.", pRuleId);
		}
		final ObjectNode params = RuleBreakdownRun.MAPPER.createObjectNode();
		params.put("ruleId", pRuleId);
		params.put("locale", pSession == null || pSession.getLocale() == null ? "en" : pSession.getLocale().toLanguageTag());
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProjectId, RuleBreakdownRun.STEP, params);
		final ObjectNode out = RuleBreakdownRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		return out;
	}

	/** True when a measure of this rule is queued or running for the project. */
	public static boolean measuring(final Adaptation pFactory, final String pProjectId, final String pRuleId)
	{
		for (final Adaptation job : FactoryJobQueue.active(pFactory, pProjectId))
		{
			if (RuleBreakdownRun.STEP.equals(job.getString(FactoryPaths.FactoryJob.KIND)))
			{
				try
				{
					if (pRuleId.equals(RuleBreakdownRun.MAPPER.readTree(job.getString(FactoryPaths.FactoryJob.PARAMS)).path("ruleId").asText("")))
					{
						return true;
					}
				}
				catch (final java.io.IOException ex)
				{
					// a params text that is not JSON is not ours
				}
			}
		}
		return false;
	}

	/** The job's work, registered with the queue at module start ({@link FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams) throws Exception
		{
			final String ruleId = pParams.path("ruleId").asText("");
			final String tag = pParams.path("locale").asText("");
			final Locale locale = tag.isEmpty() ? Locale.ENGLISH : Locale.forLanguageTag(tag);
			if (ruleId.isEmpty() || pFactory.getTable(FactoryPaths.QUALITY_RULE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(ruleId)) == null)
			{
				return DeskMessages.get(locale, "desk.l4.job.gone");
			}
			final int groups = FailureBreakdowns.measureRule(pJobSession, pFactory, ruleId);
			if (groups == 0)
			{
				return DeskMessages.get(locale, "desk.l4.job.none");
			}
			// lot L4b: the breakdown is stored; now the model is asked, on that rule alone, whether it proposes a condition
			final Adaptation rule = pFactory.getTable(FactoryPaths.QUALITY_RULE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(ruleId));
			final FailureBreakdown.Result stored = rule == null ? null : FailureBreakdown.parse(rule.getString(FactoryPaths.QualityRule.FAILURE_BREAKDOWN));
			final String by = stored == null ? "" : stored.attribute;
			RuleFocus focus;
			try
			{
				focus = QualityLlmSuggester.suggestForRule(pJobSession, pFactory, ruleId);
			}
			catch (final Exception ex)
			{
				// no connection, a refusal of the engine...: the breakdown stays, the message says why the model was not asked
				focus = new RuleFocus(null, null, null);
				focus.notAskedReason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			}
			return RuleBreakdownRun.endMessage(locale, by, focus);
		}
	};

	/** The job's end message (pure): what was measured, then what the model said - or why it was not asked. */
	static String endMessage(final Locale pLocale, final String pGroupedBy, final RuleFocus pFocus)
	{
		final String measured = DeskMessages.get(pLocale, "desk.l4b.job.measured", pGroupedBy);
		final String answer;
		if (pFocus.conditionAttribute != null)
		{
			answer = DeskMessages.get(pLocale, "desk.l4b.job.proposes", pFocus.conditionAttribute, String.join(", ", pFocus.conditionValues));
		}
		else if (pFocus.asked)
		{
			answer = pFocus.note == null || pFocus.note.isEmpty() ? DeskMessages.get(pLocale, "desk.l4b.job.noCondition")
				: DeskMessages.get(pLocale, "desk.l4b.job.noConditionNote", pFocus.note);
		}
		else
		{
			answer = DeskMessages.get(pLocale, "desk.l4b.job.notAsked", pFocus.notAskedReason == null ? "?" : pFocus.notAskedReason);
		}
		return measured + " " + answer;
	}
}
