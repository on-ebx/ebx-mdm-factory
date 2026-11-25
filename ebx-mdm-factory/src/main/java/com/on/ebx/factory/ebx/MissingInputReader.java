package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.capability.MissingInputs;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.stagingdesk.DeskInput;
import com.on.ebx.factory.wizard.StillToDecide;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;

/**
 * Validation desk lot L5 (docs/STAGING-DESK.md): reads, for one project, the two things the "an input no field holds" cards need - the capability suggestions whose
 * binding still says {@code toBeProvided} (the measured fact the model is given, {@link MissingInputs.Need}) and the computed fields already proposed for them (a
 * {@code SCRIPT_DERIVATION} suggestion whose rationale carries the marker {@link MissingInputs#marker}, with its {@code StagingAttribute}). The detection of a pending
 * input is the wizard card's own ({@link StillToDecide#queryToWrite}); a suggestion that is rejected, superseded or set aside is not read, like {@link GuardReader}.
 * Read only; the caller chooses the view (the user's, for the desk; the engine's, for the job).
 */
public final class MissingInputReader
{
	private MissingInputReader()
	{
	}

	/** Needs and proposed fields of the project (the {@link DeskInput#jobRunning} flag is left to the caller). */
	public static DeskInput read(final Adaptation pFactory, final String pProjectId)
	{
		final DeskInput out = new DeskInput();
		final Map<String, String> entityNames = MissingInputReader.entityNames(pFactory, pProjectId);
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String status = MissingInputReader.text(row, FactoryPaths.CapabilitySuggestion.STATUS);
				if (ReviewLine.REJECTED.equals(status) || ReviewLine.SUPERSEDED.equals(status) || ReviewLine.SET_ASIDE.equals(status))
				{
					continue;
				}
				final String nature = MissingInputReader.text(row, FactoryPaths.CapabilitySuggestion.NATURE);
				final String id = row.getOccurrencePrimaryKey().format();
				final String entityName = MissingInputReader.nameOf(entityNames, MissingInputReader.text(row, FactoryPaths.CapabilitySuggestion.ENTITY));
				if (ReviewLine.SCRIPT_DERIVATION.equals(nature))
				{
					final DeskInput.Field field = MissingInputReader.field(pFactory, row, id, entityName);
					if (field != null)
					{
						out.fields.add(field);
					}
					continue;
				}
				if (!ReviewLine.SCRIPT_CONSTRAINT.equals(nature) && !ReviewLine.EXTERNAL_SERVICE.equals(nature))
				{
					continue;
				}
				final String binding = MissingInputReader.text(row, FactoryPaths.CapabilitySuggestion.BINDING);
				if (!StillToDecide.queryToWrite(binding))
				{
					continue;
				}
				out.needs.addAll(MissingInputs.needsOf(id, nature, entityName, MissingInputReader.labelOf(row, nature), binding));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** The connector for a service, {@code address.geocode} for a script check. */
	private static String labelOf(final Adaptation pRow, final String pNature)
	{
		if (ReviewLine.EXTERNAL_SERVICE.equals(pNature))
		{
			final String connector = MissingInputReader.text(pRow, FactoryPaths.CapabilitySuggestion.CONNECTOR_ID);
			return connector.isEmpty() ? MissingInputReader.text(pRow, FactoryPaths.CapabilitySuggestion.CONNECTOR_OPERATION) : connector;
		}
		final String importName = MissingInputReader.text(pRow, FactoryPaths.CapabilitySuggestion.TARGET_IMPORT_NAME);
		return MissingInputs.scriptLabel(importName.isEmpty() ? MissingInputReader.text(pRow, FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY) : importName,
			MissingInputReader.text(pRow, FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION));
	}

	/** The proposed field behind a SCRIPT_DERIVATION suggestion that carries the marker; null for any other derivation, a rejected one, or an attribute that cannot be read. */
	private static DeskInput.Field field(final Adaptation pFactory, final Adaptation pSuggestion, final String pSuggestionId, final String pEntityName)
	{
		final String rationale = MissingInputReader.text(pSuggestion, FactoryPaths.CapabilitySuggestion.RATIONALE);
		final List<MissingInputs.Supply> supplies = MissingInputs.parseSupplies(rationale);
		if (supplies.isEmpty())
		{
			return null;
		}
		final Adaptation attribute = ProposalReviews.byId(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), MissingInputReader.text(pSuggestion, FactoryPaths.CapabilitySuggestion.ATTRIBUTE));
		if (attribute == null)
		{
			return null;
		}
		final String attributeStatus = MissingInputReader.text(attribute, FactoryPaths.StagingAttribute.STATUS);
		if (ReviewLine.REJECTED.equals(attributeStatus) || ReviewLine.SUPERSEDED.equals(attributeStatus))
		{
			return null;
		}
		final Object confidence = UserView.get(pSuggestion, FactoryPaths.CapabilitySuggestion.CONFIDENCE);
		return new DeskInput.Field(attribute.getOccurrencePrimaryKey().format(), MissingInputReader.text(attribute, FactoryPaths.StagingAttribute.NAME),
			MissingInputReader.text(attribute, FactoryPaths.StagingAttribute.LABEL), MissingInputReader.text(attribute, FactoryPaths.StagingAttribute.DATA_TYPE), attributeStatus, pSuggestionId,
			pEntityName, MissingInputReader.text(pSuggestion, FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY), MissingInputReader.text(pSuggestion, FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION),
			MissingInputReader.text(pSuggestion, FactoryPaths.CapabilitySuggestion.BINDING), MissingInputs.stripMarker(rationale),
			confidence instanceof Number ? Integer.valueOf(((Number) confidence).intValue()) : null, supplies);
	}

	private static Map<String, String> entityNames(final Adaptation pFactory, final String pProjectId)
	{
		final Map<String, String> out = new HashMap<String, String>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ENTITY), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.put(row.getOccurrencePrimaryKey().format(), MissingInputReader.text(row, FactoryPaths.StagingEntity.NAME));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static String text(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final String value = UserView.string(pRow, pPath);
		return value == null ? "" : value;
	}

	private static String nameOf(final Map<String, String> pNames, final String pEntityId)
	{
		final String name = pNames.get(pEntityId);
		return name == null ? "" : name;
	}

	/** The needs only, as a list (the job's input). */
	public static List<MissingInputs.Need> needs(final Adaptation pFactory, final String pProjectId)
	{
		return new ArrayList<MissingInputs.Need>(MissingInputReader.read(pFactory, pProjectId).needs);
	}
}
