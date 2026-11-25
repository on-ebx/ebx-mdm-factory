package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Staging integration (2026-10-17, automated B2C scenario run E2EB2C20261017180603): a script constraint accepted in the Mapping matrix BEFORE the
 * staging model exists (the normal wizard order: decide, then build) could never be applied - "acceptance applies it" failed with "model ... does not
 * exist yet" and the row stayed ACCEPTED, so EBX never checked it (CapabilitySuggestion 223, 241, 242, 249 live). The load now applies every accepted,
 * not-applied STAGING-zone constraint right after the model is generated and before any row is written, the same way {@link DerivationAutoApply}
 * applies the derivations. A failure never fails the load: it is a note.
 */
final class ConstraintAutoApply
{
	private ConstraintAutoApply()
	{
	}

	/** Applies the accepted STAGING-zone constraints of the project not applied yet; one note per failure, one summary note when some were applied. */
	static void applyAccepted(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pProjectId, final List<String> pNotes)
	{
		final List<String> ids = ConstraintAutoApply.acceptedStagingIds(pFactory, pProjectId);
		if (ids.isEmpty())
		{
			return;
		}
		try
		{
			final JsonNode batch = CapabilityApplier.applyAll(pSession, pFactory, pProjectCode, ids);
			int applied = 0;
			for (final JsonNode result : batch.path("results"))
			{
				if (result.path("ok").asBoolean(false))
				{
					applied++;
				}
				else
				{
					pNotes.add(ConstraintAutoApply.failureNote(result.path("suggestion").asText("?"), result.path("error").asText("")));
				}
			}
			if (applied > 0)
			{
				pNotes.add(applied + " accepted script constraint(s) applied by this load");
			}
		}
		catch (final OperationException | RuntimeException ex)
		{
			pNotes.add("Accepted script constraints could not be applied by this load: " + TextLimits.truncate(String.valueOf(ex.getMessage()), 300));
		}
	}

	/**
	 * Lot M4 (2026-10-18, point C), pure: the note of a constraint the load could not apply. Said once, in the step message and the job note; the same reason stands on the row
	 * ({@code applyMessage}). No trailing full stop of its own (the loader's sentence adds it: the live message read "...before applying..").
	 */
	static String failureNote(final String pSuggestionId, final String pError)
	{
		String reason = TextLimits.truncate(pError == null ? "" : pError.trim(), 300);
		while (reason.endsWith("."))
		{
			reason = reason.substring(0, reason.length() - 1);
		}
		return "Accepted script constraint " + pSuggestionId + " could not be applied by this load, it stays ACCEPTED: " + (reason.isEmpty() ? "no reason was given" : reason);
	}

	/** ACCEPTED SCRIPT_CONSTRAINT rows of the project whose zone is STAGING (or empty: the staging is the default zone of a constraint). */
	static List<String> acceptedStagingIds(final Adaptation pFactory, final String pProjectId)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION).createRequestResult("./project=" + CatalogReader.quote(pProjectId)
			+ " and ./nature='SCRIPT_CONSTRAINT' and ./status='ACCEPTED'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String zone = row.getString(FactoryPaths.CapabilitySuggestion.ZONE);
				if (zone == null || zone.trim().isEmpty() || "STAGING".equals(zone))
				{
					out.add(row.getOccurrencePrimaryKey().format());
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}
}
