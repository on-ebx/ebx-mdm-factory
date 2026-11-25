package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.capability.DerivationParameters;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.staging.ScriptDerivationGate;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;

/**
 * Reads, for one project, every accepted-like staging attribute whose {@code derivation} is {@code SCRIPT} together with the state
 * of the {@code SCRIPT_DERIVATION} suggestion that computes it (lot M5, 2026-10-04) - the one reader the staging load, the wizard's
 * attention list and the matching configuration share, so that "applied" means the same thing everywhere
 * ({@link ScriptDerivationGate#isApplied}: {@code appliedModel} and {@code appliedElementPath} both set by {@code
 * ebx.DerivationApplier}). Read only.
 */
public final class ScriptDerivations
{
	private ScriptDerivations()
	{
	}

	/** One {@link ScriptDerivationGate.Entry} per accepted-like {@code SCRIPT} attribute of the project; empty when it has none. */
	public static List<ScriptDerivationGate.Entry> read(final Adaptation pFactory, final String pProjectId)
	{
		final List<ScriptDerivationGate.Entry> out = new ArrayList<ScriptDerivationGate.Entry>();
		if (pProjectId == null || pProjectId.trim().isEmpty())
		{
			return out;
		}
		final Map<String, ScriptDerivationGate.Entry> byAttributeId = new HashMap<String, ScriptDerivationGate.Entry>();
		final Map<String, String> entityNames = new HashMap<String, String>();
		final List<String> entityIds = new ArrayList<String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				entityNames.put(id, row.getString(FactoryPaths.StagingEntity.NAME));
				entityIds.add(id);
			}
		}
		finally
		{
			entityRows.close();
		}
		final Map<String, String> attributeNameById = new HashMap<String, String>();
		final List<Adaptation> scriptRows = new ArrayList<Adaptation>();
		for (final String entityId : entityIds)
		{
			final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(entityId));
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					attributeNameById.put(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.StagingAttribute.NAME));
					if (ScriptDerivationGate.isScript(row.getString(FactoryPaths.StagingAttribute.DERIVATION))
						&& TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.StagingAttribute.STATUS)))
					{
						scriptRows.add(row);
					}
				}
			}
			finally
			{
				rows.close();
			}
		}
		if (scriptRows.isEmpty())
		{
			return out;
		}
		// the suggestion that computes each attribute: an applied one wins over any other, else the first seen
		final Map<String, Adaptation> suggestionByAttributeId = new HashMap<String, Adaptation>();
		final RequestResult suggestions = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION).createRequestResult("./project=" + CatalogReader.quote(pProjectId)
			+ " and ./nature='SCRIPT_DERIVATION'");
		try
		{
			for (Adaptation row = suggestions.nextAdaptation(); row != null; row = suggestions.nextAdaptation())
			{
				final String attributeId = row.getString(FactoryPaths.CapabilitySuggestion.ATTRIBUTE);
				if (attributeId == null)
				{
					continue;
				}
				final Adaptation known = suggestionByAttributeId.get(attributeId);
				if (known == null || (!ScriptDerivations.applied(known) && ScriptDerivations.applied(row)))
				{
					suggestionByAttributeId.put(attributeId, row);
				}
			}
		}
		finally
		{
			suggestions.close();
		}
		for (final Adaptation attribute : scriptRows)
		{
			final String attributeId = attribute.getOccurrencePrimaryKey().format();
			final Adaptation suggestion = suggestionByAttributeId.get(attributeId);
			final String sourceId = attribute.getString(FactoryPaths.StagingAttribute.DERIVED_FROM);
			out.add(new ScriptDerivationGate.Entry(entityNames.get(attribute.getString(FactoryPaths.StagingAttribute.ENTITY)),
				attribute.getString(FactoryPaths.StagingAttribute.NAME), sourceId == null ? null : attributeNameById.get(sourceId),
				suggestion == null ? null : suggestion.getOccurrencePrimaryKey().format(), suggestion != null && ScriptDerivations.applied(suggestion),
				suggestion != null && DerivationParameters.isReapplyMarker(suggestion.getString(FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE)),
				suggestion == null ? null : DerivationParameters.toBeProvidedNames(suggestion.getString(FactoryPaths.CapabilitySuggestion.BINDING)),
				suggestion == null ? null : (java.util.Date) suggestion.get(FactoryPaths.CapabilitySuggestion.APPLIED_AT)));
		}
		return out;
	}

	/**
	 * Fix/derivation-apply-backfill (2026-10-17, lot G1, item 3): the parameters a person still has to provide for the ACCEPTED, not applied {@code SCRIPT_DERIVATION} of one
	 * staging attribute - what a screen says right after the attribute is accepted ("this computed field needs a value only you can give"). Empty when the attribute has no
	 * such derivation or it needs nothing. Read only.
	 */
	public static List<String> parametersToProvide(final Adaptation pFactory, final String pAttributeId)
	{
		final List<String> out = new ArrayList<String>();
		if (pFactory == null || pAttributeId == null || pAttributeId.trim().isEmpty())
		{
			return out;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION).createRequestResult("./attribute=" + CatalogReader.quote(pAttributeId)
			+ " and ./nature='SCRIPT_DERIVATION' and ./status='ACCEPTED'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (ScriptDerivations.applied(row))
				{
					continue;
				}
				for (final String name : DerivationParameters.toBeProvidedNames(row.getString(FactoryPaths.CapabilitySuggestion.BINDING)))
				{
					if (!out.contains(name))
					{
						out.add(name);
					}
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static boolean applied(final Adaptation pSuggestion)
	{
		return ScriptDerivationGate.isApplied(pSuggestion.getString(FactoryPaths.CapabilitySuggestion.STATUS),
			pSuggestion.getString(FactoryPaths.CapabilitySuggestion.APPLIED_MODEL), pSuggestion.getString(FactoryPaths.CapabilitySuggestion.APPLIED_ELEMENT_PATH));
	}
}
