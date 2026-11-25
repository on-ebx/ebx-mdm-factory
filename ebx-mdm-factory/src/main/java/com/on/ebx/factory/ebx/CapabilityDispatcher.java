package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.capability.CapabilityNatureRoute;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * The one place that decides which applier a {@code CapabilitySuggestion} goes to - {@code ebx.CapabilityApplier} for a {@code
 * SCRIPT_CONSTRAINT}, {@code ebx.DerivationApplier} for a {@code SCRIPT_DERIVATION}, a clear refusal for anything else - from the
 * ROW's own {@code nature} (see {@link CapabilityNatureRoute}). Used by the Proposal review screen and by the REST routes {@code
 * .../capabilities/{id}/apply}, {@code .../capabilities/apply[?ids=]} and {@code .../capabilities/{id}/remove}, so a project driven over
 * REST applies a derivation exactly as the screen does (lot M5, 2026-10-04: until then those routes were SCRIPT_CONSTRAINT only and a
 * derived attribute such as {@code phoneE164} stayed unapplied, silently).
 * <p>
 * Same contract as the appliers it fronts: the single-id methods throw {@link OperationException} on failure; {@link #applyAll} never
 * throws for one row's failure and answers {@code {"project","requested","results":[{"suggestion","ok",...}],"applied","failed"}}.
 */
public final class CapabilityDispatcher
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private CapabilityDispatcher()
	{
	}

	/** Applies one suggestion through the applier its nature selects; {@code pMeasureMaxRecords} only matters to a {@code SCRIPT_CONSTRAINT} (null: the applier's default). */
	public static ObjectNode apply(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pSuggestionId,
		final Long pMeasureMaxRecords) throws OperationException
	{
		final String nature = CapabilityDispatcher.natureOf(pFactory, pSuggestionId);
		switch (CapabilityNatureRoute.route(nature))
		{
			case CONSTRAINT:
				return pMeasureMaxRecords == null ? CapabilityApplier.apply(pSession, pFactory, pProjectCode, pSuggestionId)
					: CapabilityApplier.apply(pSession, pFactory, pProjectCode, pSuggestionId, pMeasureMaxRecords.longValue());
			case DERIVATION:
				return DerivationApplier.apply(pSession, pFactory, pProjectCode, pSuggestionId);
			default:
				throw EngineRefusal.coded("conflict.refused", CapabilityNatureRoute.refusalMessage(pSuggestionId, nature));
		}
	}

	/** Takes one applied suggestion back off its model, through the applier its nature selects. */
	public static ObjectNode remove(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pSuggestionId) throws OperationException
	{
		final String nature = CapabilityDispatcher.natureOf(pFactory, pSuggestionId);
		switch (CapabilityNatureRoute.route(nature))
		{
			case CONSTRAINT:
				return CapabilityApplier.remove(pSession, pFactory, pProjectCode, pSuggestionId);
			case DERIVATION:
				return DerivationApplier.remove(pSession, pFactory, pProjectCode, pSuggestionId);
			default:
				throw EngineRefusal.coded("conflict.refused", CapabilityNatureRoute.refusalMessage(pSuggestionId, nature));
		}
	}

	/**
	 * Applies several suggestions, each through the applier its nature selects: the {@code SCRIPT_CONSTRAINT} ones together through
	 * {@link CapabilityApplier#applyAll} (one publish per model), the {@code SCRIPT_DERIVATION} ones one at a time, anything else answered
	 * as a failed result carrying the refusal. {@code pIds} null or empty means every {@code ACCEPTED} {@code SCRIPT_CONSTRAINT} (the
	 * applier's own default) and every {@code ACCEPTED} {@code SCRIPT_DERIVATION} of the project; results follow the order of the ids, or
	 * constraints then derivations for the default.
	 */
	public static ObjectNode applyAll(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<String> pIds, final Long pMeasureMaxRecords)
		throws OperationException
	{
		final Adaptation project = ProposalReviews.findProjectByCode(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final boolean defaultScope = pIds == null || pIds.isEmpty();
		final List<String> order = new ArrayList<String>();
		final List<String> constraintIds = new ArrayList<String>();
		final List<String> derivationIds = new ArrayList<String>();
		final Map<String, ObjectNode> resultsById = new LinkedHashMap<String, ObjectNode>();
		if (defaultScope)
		{
			derivationIds.addAll(CapabilityDispatcher.acceptedDerivationIds(pFactory, project.getOccurrencePrimaryKey().format()));
		}
		else
		{
			final Map<String, String> natureById = new LinkedHashMap<String, String>();
			for (final String id : pIds)
			{
				order.add(id);
				natureById.put(id, CapabilityDispatcher.natureOfOrNull(pFactory, id));
			}
			constraintIds.addAll(CapabilityNatureRoute.idsOfRoute(natureById, CapabilityNatureRoute.Route.CONSTRAINT));
			derivationIds.addAll(CapabilityNatureRoute.idsOfRoute(natureById, CapabilityNatureRoute.Route.DERIVATION));
			for (final String id : CapabilityNatureRoute.idsOfRoute(natureById, CapabilityNatureRoute.Route.REFUSED))
			{
				resultsById.put(id, CapabilityDispatcher.failure(id, natureById.get(id) == null ? "capability suggestion '" + id + "' not found"
					: CapabilityNatureRoute.refusalMessage(id, natureById.get(id))));
			}
		}
		final List<ObjectNode> constraintResults = new ArrayList<ObjectNode>();
		if (defaultScope || !constraintIds.isEmpty())
		{
			final ObjectNode batch = pMeasureMaxRecords == null ? CapabilityApplier.applyAll(pSession, pFactory, pProjectCode, constraintIds)
				: CapabilityApplier.applyAll(pSession, pFactory, pProjectCode, constraintIds, pMeasureMaxRecords.longValue());
			for (final JsonNode result : batch.path("results"))
			{
				constraintResults.add((ObjectNode) result);
				resultsById.put(result.path("suggestion").asText(), (ObjectNode) result);
			}
		}
		for (final String id : derivationIds)
		{
			ObjectNode result;
			try
			{
				result = DerivationApplier.apply(pSession, pFactory, pProjectCode, id);
			}
			catch (final OperationException ex)
			{
				result = CapabilityDispatcher.failure(id, ex.getMessage());
			}
			resultsById.put(id, result);
		}
		final ObjectNode out = CapabilityDispatcher.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		final ArrayNode results = out.putArray("results");
		final List<String> sequence = new ArrayList<String>();
		if (defaultScope)
		{
			for (final ObjectNode result : constraintResults)
			{
				sequence.add(result.path("suggestion").asText());
			}
			sequence.addAll(derivationIds);
		}
		else
		{
			sequence.addAll(order);
		}
		int applied = 0;
		int failed = 0;
		for (final String id : sequence)
		{
			final ObjectNode result = resultsById.get(id);
			if (result == null)
			{
				continue;
			}
			results.add(result);
			if (result.path("ok").asBoolean(false))
			{
				applied++;
			}
			else
			{
				failed++;
			}
		}
		out.put("requested", sequence.size());
		out.put("applied", applied);
		out.put("failed", failed);
		return out;
	}

	/** The row's nature, or throws when no such suggestion exists. */
	private static String natureOf(final Adaptation pFactory, final String pSuggestionId) throws OperationException
	{
		final String nature = CapabilityDispatcher.natureOfOrNull(pFactory, pSuggestionId);
		if (nature == null)
		{
			throw EngineRefusal.coded("missing.suggestion", "capability suggestion '" + pSuggestionId + "' not found", pSuggestionId);
		}
		return nature;
	}

	private static String natureOfOrNull(final Adaptation pFactory, final String pSuggestionId)
	{
		final Adaptation row = ProposalReviews.byId(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION), pSuggestionId);
		if (row == null)
		{
			return null;
		}
		final String nature = row.getString(FactoryPaths.CapabilitySuggestion.NATURE);
		return nature == null ? "" : nature;
	}

	/** Every {@code SCRIPT_DERIVATION} of the project in status {@code ACCEPTED} (an already {@code APPLIED} one is only re-applied when named - same rule as the constraints). */
	static List<String> acceptedDerivationIds(final Adaptation pFactory, final String pProjectId)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION).createRequestResult("./project=" + CatalogReader.quote(pProjectId)
			+ " and ./nature='SCRIPT_DERIVATION' and ./status='ACCEPTED'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static ObjectNode failure(final String pSuggestionId, final String pMessage)
	{
		final ObjectNode out = CapabilityDispatcher.MAPPER.createObjectNode();
		out.put("suggestion", pSuggestionId);
		out.put("ok", false);
		out.put("error", pMessage);
		return out;
	}
}
