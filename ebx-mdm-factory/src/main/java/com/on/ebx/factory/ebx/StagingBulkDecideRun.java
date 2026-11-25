package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.review.ReviewRules;
import com.on.ebx.factory.wizard.WizardRules;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Audit F-47 (wave 5): the wizard's "Accept all" / "Reject all" (step 4) as a job of {@link FactoryJobQueue}, resource {@link FactoryJobs#DESIGN} (the resource
 * of the proposal runs, which write the very rows this sweep decides: the two never overlap for a project). The sweep used to run inside the request - one
 * {@link ProposalReviews#decide} transaction per line, on a project with thousands of attributes and mappings a page frozen for minutes - and then
 * {@link CapabilityDispatcher#applyAll} (one publish per model) synchronously after it.
 * <p>
 * What the job does is exactly what the request did, in the same order (entities, then their attributes, then the attributes' mappings, then the proposal
 * lines; see {@link #sweep}): the decisions are NOT merged into larger transactions. Each {@link ProposalReviews#decide} is one small transaction with its own
 * rule check, cascades and refusal, committed at once, so a stop or a failure leaves every decision made so far in place and loses nothing - the transactions
 * are bounded by construction (one row and its cascade). Batching several lines into one {@code Procedure} would need a multi-row entry point in {@code
 * ProposalReviews}, and a refused line would then have to be pre-checked instead of simply skipped; not done here. What changed is where it runs: a click
 * elsewhere no longer abandons it, "Stop" ends it between two lines ({@code Handle#cancelled}, also the job's check-in), the jobs banner shows its progress and
 * its sentence, and a second click while one is queued or running is refused.
 * <p>
 * The script constraints this sweep itself accepted are applied at its end, still in one batch ({@link CapabilityDispatcher#applyAll}: one publish per
 * model) - inside the job, so the apply no longer holds a request either. A failed apply ends the job {@code FAILED} with the decisions' summary and says the
 * rows stay ACCEPTED (Apply offered to retry), the same row-level recovery as before.
 */
public final class StagingBulkDecideRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "STAGING_BULK_DECIDE_RUN";

	private StagingBulkDecideRun()
	{
	}

	/** The two things the sweep asks of its caller: a decision (true when it was written) and whether a zone's model exists, so an accepted script constraint can be applied. */
	interface Decider
	{
		boolean decide(String pNature, String pRowId, String pTargetStatus);

		boolean applyAllowed(String pZone);
	}

	/** What a sweep did. */
	static final class Outcome
	{
		int ok;
		int skipped;
		/** Staging last points (2026-10-17): of {@link #skipped}, the code tables refused because an observed code with rows still has no target code (counted by the caller's decider). */
		int codeTablesSkipped;
		int leftOut;
		int lowestLeftOutConfidence = 100;
		int deferredApply;
		boolean stopped;
		final List<String> scriptConstraintIdsToApply = new ArrayList<String>();
		/**
		 * Lot M7 (2026-10-18): the PROPOSED entities an ACCEPT sweep left alone, with their attributes and mappings, because no source column feeds them (no
		 * mapping of any of their attributes is still alive) - by name, for the sentence. Measured on E2EMASTER20261017235630: {@code ContactEmail} at 89 %
		 * was swept in with four attributes and no mapping, built, loaded with 0 rows and given an empty master table.
		 */
		final List<String> noFeedEntities = new ArrayList<String>();
	}

	/** Whether at least one mapping of one of the entity's attributes still feeds it (not REJECTED, not SUPERSEDED). The technical key and the link to the parent have no mapping by design and do not count. */
	static boolean hasFeed(final WizardSnapshot.StagingEntityInfo pEntity)
	{
		for (final WizardSnapshot.AttributeInfo attribute : pEntity.attributes)
		{
			for (final WizardSnapshot.MappingInfo mapping : attribute.mappings)
			{
				if (!ReviewLine.REJECTED.equals(mapping.status) && !ReviewLine.SUPERSEDED.equals(mapping.status))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The sweep itself, moved verbatim from the wizard's {@code bulkStaging} (audit F-47) and made independent of the session so its ordering and counts are
	 * tested: dependency order (an entity before its attributes, an attribute before its mappings, then the inline proposal lines), a SUPERSEDED entity and an
	 * attribute that is the technical key skipped without a count, {@link WizardRules#bulkEligibility} deciding eligible / left out below the threshold /
	 * not PROPOSED, and a line a rule refuses counted as skipped.
	 *
	 * @param pStop asked before every decision (null = never); a {@code true} ends the sweep and marks {@link Outcome#stopped}
	 */
	static Outcome sweep(final WizardSnapshot pSnapshot, final boolean pAccept, final WizardSnapshot.BulkFilter pFilter, final int pThreshold, final Decider pDecider,
		final BooleanSupplier pStop)
	{
		final String targetStatus = pAccept ? ReviewLine.ACCEPTED : ReviewLine.REJECTED;
		final Outcome out = new Outcome();
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (pFilter.entityId != null && !pFilter.entityId.equals(entity.id))
			{
				continue;
			}
			// a SUPERSEDED entity's card is not shown at step 4: not reachable through a bulk sweep either
			if (ReviewLine.SUPERSEDED.equals(entity.status))
			{
				continue;
			}
			// natures non-null: "proposal lines of these natures only" - the entity, its attributes and mappings are not part of that sweep
			if (pFilter.natures == null)
			{
				// lot M7: a PROPOSED entity no source column feeds is skipped whole on an ACCEPT (whatever its confidence) and named in the sentence; a REJECT sweeps it as before
				if (pAccept && ReviewLine.PROPOSED.equals(entity.status) && !StagingBulkDecideRun.hasFeed(entity))
				{
					out.noFeedEntities.add(entity.name == null || entity.name.isEmpty() ? entity.id : entity.name);
					continue;
				}
				if (!StagingBulkDecideRun.one(out, pDecider, pStop, ReviewLine.ENTITY, entity.id, entity.status, entity.confidence, pThreshold, pFilter, targetStatus))
				{
					return out;
				}
				for (final WizardSnapshot.AttributeInfo attribute : entity.attributes)
				{
					// the technical key has no decision of its own (it follows its entity's): nothing to decide, nothing refused
					if (attribute.followsEntity())
					{
						continue;
					}
					if (!StagingBulkDecideRun.one(out, pDecider, pStop, ReviewLine.ATTRIBUTE, attribute.id, attribute.status, attribute.confidence, pThreshold, pFilter, targetStatus))
					{
						return out;
					}
					for (final WizardSnapshot.MappingInfo mapping : attribute.mappings)
					{
						if (!StagingBulkDecideRun.one(out, pDecider, pStop, ReviewLine.MAPPING, mapping.id, mapping.status, mapping.confidence, pThreshold, pFilter, targetStatus))
						{
							return out;
						}
					}
				}
			}
			for (final WizardSnapshot.ProposalLineInfo info : entity.proposalLines)
			{
				final ReviewLine line = info.line;
				if (pFilter.natures != null && !pFilter.natures.contains(line.nature))
				{
					continue;
				}
				final WizardRules.BulkEligibility eligibility = WizardRules.bulkEligibility(line.status, line.confidence, pThreshold, pFilter.includeBelowThreshold);
				if (!eligibility.eligible)
				{
					if (eligibility.leftOutBelowThreshold)
					{
						out.leftOut++;
						out.lowestLeftOutConfidence = Math.min(out.lowestLeftOutConfidence, line.confidence == null ? 0 : line.confidence.intValue());
					}
					continue;
				}
				if (pStop != null && pStop.getAsBoolean())
				{
					out.stopped = true;
					return out;
				}
				if (pDecider.decide(line.nature, line.rowId, targetStatus))
				{
					out.ok++;
					if (ReviewRules.shouldApplyAfterAccept(targetStatus, line.nature))
					{
						if (pDecider.applyAllowed(info.zone))
						{
							out.scriptConstraintIdsToApply.add(line.rowId);
						}
						else
						{
							out.deferredApply++;
						}
					}
				}
				else
				{
					out.skipped++;
				}
			}
		}
		return out;
	}

	/** One entity/attribute/mapping row. @return false when the sweep was asked to stop (nothing was decided for this row). */
	private static boolean one(final Outcome pOut, final Decider pDecider, final BooleanSupplier pStop, final String pNature, final String pId, final String pStatus,
		final Integer pConfidence, final int pThreshold, final WizardSnapshot.BulkFilter pFilter, final String pTargetStatus)
	{
		final WizardRules.BulkEligibility eligibility = WizardRules.bulkEligibility(pStatus, pConfidence, pThreshold, pFilter.includeBelowThreshold);
		if (eligibility.eligible)
		{
			if (pStop != null && pStop.getAsBoolean())
			{
				pOut.stopped = true;
				return false;
			}
			if (pDecider.decide(pNature, pId, pTargetStatus))
			{
				pOut.ok++;
			}
			else
			{
				pOut.skipped++;
			}
		}
		else if (eligibility.leftOutBelowThreshold)
		{
			pOut.leftOut++;
			pOut.lowestLeftOutConfidence = Math.min(pOut.lowestLeftOutConfidence, pConfidence == null ? 0 : pConfidence.intValue());
		}
		return true;
	}

	/** The job's one-line English sentence (the banner and {@code FactoryJob.message}), pure. */
	static String summary(final Outcome pOutcome, final boolean pAccept)
	{
		final StringBuilder text = new StringBuilder();
		if (pOutcome.stopped)
		{
			text.append("Stopped on request. ");
		}
		text.append(pOutcome.ok).append(pOutcome.ok == 1 ? " row " : " rows ").append(pAccept ? "accepted" : "rejected").append(", ").append(pOutcome.skipped)
			.append(" skipped (a rule still refused them)");
		if (pOutcome.codeTablesSkipped > 0)
		{
			text.append(" (").append(pOutcome.codeTablesSkipped).append(pOutcome.codeTablesSkipped == 1 ? " code table still lacks" : " code tables still lack")
				.append(" a target code for some codes: fill them in the Mapping matrix first)");
		}
		if (pOutcome.leftOut > 0)
		{
			text.append(", ").append(pOutcome.leftOut).append(" left out below the threshold (lowest: ").append(pOutcome.lowestLeftOutConfidence).append(" %)");
		}
		if (!pOutcome.noFeedEntities.isEmpty())
		{
			text.append(", ").append(String.join(", ", pOutcome.noFeedEntities)).append(" skipped: no source column feeds ")
				.append(pOutcome.noFeedEntities.size() == 1 ? "it" : "them");
		}
		text.append('.');
		if (pOutcome.deferredApply > 0)
		{
			text.append(' ').append(pOutcome.deferredApply).append(pOutcome.deferredApply == 1 ? " accepted script constraint will" : " accepted script constraints will")
				.append(" be applied automatically once its zone's model is generated.");
		}
		return text.toString();
	}

	/** Pure: the nature set of the stored params (null = every nature). */
	static Set<String> naturesOf(final JsonNode pParams)
	{
		final JsonNode natures = pParams.path("natures");
		if (!natures.isArray())
		{
			return null;
		}
		final Set<String> out = new HashSet<String>();
		for (final JsonNode nature : natures)
		{
			out.add(nature.asText(""));
		}
		return out;
	}

	/** True when a bulk decision of this project is already QUEUED or RUNNING: a second click is refused with a sentence instead of queuing a sweep over the same rows. */
	public static boolean isActive(final Adaptation pFactory, final String pProjectId)
	{
		for (final Adaptation job : FactoryJobQueue.active(pFactory, pProjectId))
		{
			if (StagingBulkDecideRun.STEP.equals(job.getString(FactoryPaths.FactoryJob.KIND)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Queues the sweep (resource DESIGN) and returns at once. The same request twice while still QUEUED folds into one job ({@link FactoryJobQueue#enqueue}).
	 *
	 * @param pEntityId null: the whole project
	 * @param pNatures null: every nature
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode, final boolean pAccept,
		final String pEntityId, final Set<String> pNatures, final boolean pIncludeBelowThreshold, final int pThreshold) throws OperationException
	{
		final ObjectNode params = StagingBulkDecideRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("accept", pAccept);
		if (pEntityId != null)
		{
			params.put("entityId", pEntityId);
		}
		if (pNatures != null)
		{
			final ArrayNode natures = params.putArray("natures");
			for (final String nature : new java.util.TreeSet<String>(pNatures))
			{
				natures.add(nature);
			}
		}
		params.put("includeBelowThreshold", pIncludeBelowThreshold);
		params.put("threshold", pThreshold);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProjectId, StagingBulkDecideRun.STEP, params);
		final ObjectNode out = StagingBulkDecideRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The job's work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final boolean accept = pParams.path("accept").asBoolean(true);
			final String entityId = pParams.has("entityId") ? pParams.path("entityId").asText() : null;
			final WizardSnapshot.BulkFilter filter = new WizardSnapshot.BulkFilter(entityId, StagingBulkDecideRun.naturesOf(pParams),
				pParams.path("includeBelowThreshold").asBoolean(false));
			final WizardSnapshot snapshot = WizardData.readSnapshot(pFactory, pProjectId, pJobSession);
			final int[] codeTables = new int[1];
			final Outcome outcome = StagingBulkDecideRun.sweep(snapshot, accept, filter, pParams.path("threshold").asInt(80), new Decider()
			{
				@Override
				public boolean decide(final String pNature, final String pRowId, final String pTargetStatus)
				{
					final ProposalReviews.DecisionResult decided = ProposalReviews.decide(pJobSession, pFactory, pNature, pRowId, pTargetStatus);
					if (!decided.ok && "review.refuse.codeTable".equals(decided.reasonKey))
					{
						codeTables[0]++;
					}
					return decided.ok;
				}

				@Override
				public boolean applyAllowed(final String pZone)
				{
					return WizardRules.capabilityApplyGate(snapshot, pZone).enabled;
				}
			}, new BooleanSupplier()
			{
				@Override
				public boolean getAsBoolean()
				{
					return pHandle.cancelled();
				}
			});
			outcome.codeTablesSkipped = codeTables[0];
			String message = StagingBulkDecideRun.summary(outcome, accept);
			if (outcome.stopped || outcome.scriptConstraintIdsToApply.isEmpty())
			{
				return message;
			}
			try
			{
				final ObjectNode result = CapabilityDispatcher.applyAll(pJobSession, pFactory, projectCode, outcome.scriptConstraintIdsToApply, null);
				final int applied = result.path("applied").asInt(0);
				final int failed = result.path("failed").asInt(0);
				message = message + " Script constraints applied: " + applied + ", failed: " + failed + ".";
				if (failed > 0)
				{
					throw new FactoryJobQueue.ExpectedFailure(message + " The failed rows stay ACCEPTED: use Apply on them to retry.");
				}
				return message;
			}
			catch (final OperationException ex)
			{
				// the decisions already written are valid on their own and are not rolled back (same recovery as before)
				throw new FactoryJobQueue.ExpectedFailure(message + " Applying the accepted script constraints failed: " + ex.getMessage() + " The rows stay ACCEPTED: use Apply on them to retry.");
			}
		}
	};
}
