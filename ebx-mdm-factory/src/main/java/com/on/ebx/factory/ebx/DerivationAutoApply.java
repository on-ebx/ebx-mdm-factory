package com.on.ebx.factory.ebx;

import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.staging.DerivationStaleness;
import com.on.ebx.factory.staging.ScriptDerivationGate;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.onwbp.adaptation.Adaptation;

/**
 * Fix/staging-proposal-quality (2026-10-17, item 1): a staging load applies the ACCEPTED script derivations of the project that are not applied
 * yet, before it reads a row. Root cause (measured live, TiersB2CRetestLanding, capabilities 201/202): {@code Customer.phoneE164} and
 * {@code Customer.personNameKey} were {@code ACCEPTED} with {@code appliedAt}/{@code appliedModel} empty - accepting a derivation and applying it
 * are two moments (docs/SCRIPT-DERIVATION.md sec 11), the apply being a button of step 4's "To apply" list, and "Build and load with what is
 * accepted" ran neither it nor anything that replaces it. The loader then held both attributes empty (lot M5: an unapplied derivation is never
 * copied from its source) on all 394 rows, while emailKey and addressKeys, computed by Java at load, were filled. So "accepted" now means
 * "applied by the time the rows are written": same {@link DerivationApplier#apply} as the button, the same publication of the two triggers on the
 * staging model, run once per not-applied accepted derivation. A failure never fails the load: the attribute stays held empty as before and the
 * load says why (one note per attribute, and the post-load check reports the empty column).
 * <p>
 * <b>Lot G1 (2026-10-17, fix/derivation-apply-backfill) corrects the paragraph that stood here.</b> It said the apply's own backfill cannot claim the STAGING
 * resource the load holds and answers SKIPPED, "which only matters for rows already in the table". It matters: measured live (TiersB2CRetestFinal, capability 226, 534
 * rows) the apply JOB's own claim refused its backfill, and the next load wrote nothing (every row unchanged), so the column stayed empty with no way out. The backfill now
 * re-enters the claim of the calling thread ({@code DerivationBackfillPass}), so an apply inside this load recomputes the rows already in the table, and {@link #repairStale}
 * (run by the load right after {@link #applyAccepted}) recomputes the rows of every APPLIED derivation that a trigger never reached - whatever skipped the backfill before.
 */
final class DerivationAutoApply
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DerivationAutoApply()
	{
	}

	/**
	 * Applies every accepted, not-applied script derivation of the project. Never throws for one derivation: a refusal or a failure is a note
	 * (appended to {@code pNotes}) and a {@code ok:false} node. Answers one node per derivation tried; empty when there was nothing to do.
	 */
	static ArrayNode applyAccepted(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pProjectId, final List<String> pNotes)
	{
		final ArrayNode out = DerivationAutoApply.MAPPER.createArrayNode();
		final List<ScriptDerivationGate.Entry> entries = ScriptDerivations.read(pFactory, pProjectId);
		final List<String> ids = ScriptDerivationGate.idsToApply(entries, CapabilityDispatcher.acceptedDerivationIds(pFactory, pProjectId));
		for (final String id : ids)
		{
			final String name = DerivationAutoApply.nameOf(entries, id);
			ObjectNode result;
			try
			{
				result = DerivationApplier.apply(pSession, pFactory, pProjectCode, id);
				pNotes.add(name + " was an accepted script derivation that was not applied: applied by this load (capability " + id + ")");
			}
			catch (final OperationException ex)
			{
				result = DerivationAutoApply.MAPPER.createObjectNode();
				result.put("suggestion", id);
				result.put("ok", false);
				result.put("error", TextLimits.truncate(String.valueOf(ex.getMessage()), 500));
				pNotes.add(name + " could not be applied by this load (capability " + id + "): " + TextLimits.truncate(String.valueOf(ex.getMessage()), 300));
			}
			catch (final RuntimeException ex)
			{
				result = DerivationAutoApply.MAPPER.createObjectNode();
				result.put("suggestion", id);
				result.put("ok", false);
				result.put("error", TextLimits.truncate(String.valueOf(ex), 500));
				pNotes.add(name + " could not be applied by this load (capability " + id + "): " + TextLimits.truncate(String.valueOf(ex), 300));
			}
			out.add(result);
		}
		return out;
	}

	/**
	 * Lot G1 (2026-10-17, item 2): for every APPLIED script derivation of the project, recomputes the rows of its staging table that hold a stale value
	 * ({@link DerivationStaleness}) - before the load reads a row, because the loader skips a row whose source is unchanged and never writes an applied derivation, so no
	 * trigger would run for them. Never throws and never fails the load: a derivation that cannot be repaired is a note (appended to {@code pNotes}) and an {@code ok:false}
	 * node. Answers one node per derivation that needed something (nothing for a table with no stale row); empty in the common case.
	 */
	static ArrayNode repairStale(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pProjectId, final List<String> pNotes)
	{
		final ArrayNode out = DerivationAutoApply.MAPPER.createArrayNode();
		for (final ScriptDerivationGate.Entry entry : ScriptDerivations.read(pFactory, pProjectId))
		{
			if (!entry.applied || entry.suggestionId == null || entry.appliedAt == null)
			{
				continue;
			}
			final String name = entry.entityName + "." + entry.attributeName;
			if (entry.reapplyNeeded)
			{
				// the live triggers carry the old parameter: recomputing now would store old-parameter values - the row says "apply again" already
				pNotes.add(name + ": its parameters changed since it was applied - apply it again to recompute the stored values (capability " + entry.suggestionId + ")");
				continue;
			}
			ObjectNode node;
			try
			{
				final DerivationApplier.Prepared prepared = DerivationApplier.prepare(pFactory, pProjectCode, entry.suggestionId);
				node = DerivationBackfillPass.repairStale(pSession, pFactory, prepared, entry.suggestionId, entry.appliedAt);
			}
			catch (final OperationException ex)
			{
				node = DerivationAutoApply.MAPPER.createObjectNode();
				node.put("suggestion", entry.suggestionId);
				node.put("status", "FAILED");
				node.put("reason", TextLimits.truncate(String.valueOf(ex.getMessage()), 500));
			}
			catch (final RuntimeException ex)
			{
				node = DerivationAutoApply.MAPPER.createObjectNode();
				node.put("suggestion", entry.suggestionId);
				node.put("status", "FAILED");
				node.put("reason", TextLimits.truncate(String.valueOf(ex), 500));
			}
			node.put("name", name);
			final String status = node.path("status").asText("");
			if ("CURRENT".equals(status) || "EMPTY".equals(status))
			{
				continue;
			}
			out.add(node);
			if ("DONE".equals(status))
			{
				final long recomputed = node.path("recomputed").asLong(0);
				if (recomputed > 0)
				{
					pNotes.add(DerivationStaleness.repairedNote(entry.entityName, entry.attributeName, recomputed, DerivationStaleness.Need.valueOf(node.path("need").asText("EMPTY_DERIVED"))));
				}
				final long limited = node.path("skippedNullNonString").asLong(0);
				if (limited > 0)
				{
					pNotes.add(name + ": " + limited + " existing row(s) with an empty value cannot be recomputed by a write (the value is not text): they are computed the next time their source changes");
				}
			}
			else
			{
				pNotes.add(name + ": the rows already in the staging table could not be recomputed (" + status.toLowerCase(java.util.Locale.ROOT) + ": "
					+ TextLimits.truncate(node.path("reason").asText(""), 300) + ")");
			}
		}
		return out;
	}

	private static String nameOf(final List<ScriptDerivationGate.Entry> pEntries, final String pSuggestionId)
	{
		for (final ScriptDerivationGate.Entry entry : pEntries)
		{
			if (pSuggestionId.equals(entry.suggestionId))
			{
				return entry.entityName + "." + entry.attributeName;
			}
		}
		return "capability " + pSuggestionId;
	}
}
