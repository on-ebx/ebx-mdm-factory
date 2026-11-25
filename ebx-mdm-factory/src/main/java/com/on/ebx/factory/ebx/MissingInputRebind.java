package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.capability.MissingInputs;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.quality.ScriptDerivation;
import com.on.ebx.factory.review.ReviewLine;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Validation desk lot L5 (docs/STAGING-DESK.md): when a computed field proposed for waiting suggestions is ACCEPTED, the {@code toBeProvided} input of each of those
 * suggestions is bound to it, in the very procedure that accepts the field ({@code ProposalReviews#decide}). The suggestions are the ones named by the marker the field's
 * suggestion carries ({@link MissingInputs#parseSupplies}) - nothing is searched by name. A suggestion that was decided meanwhile (rejected, set aside, superseded), that
 * belongs to another business object, or whose input is no longer {@code toBeProvided} (somebody settled it) is left alone. The binding is rewritten with {@link
 * MissingInputs#rebind}; the rationale of the re-bound suggestion gets one sentence saying so ({@link MissingInputs#reboundNote}). Nothing here is done when the field is
 * rejected. No model (XSD) field is involved.
 */
final class MissingInputRebind
{
	private static final int RATIONALE_MAX_LENGTH = 2000;

	private MissingInputRebind()
	{
	}

	/**
	 * Binds the inputs the just-accepted attribute supplies. A no-op (one read of the attribute row) for an attribute that is not a script-computed one.
	 *
	 * @return how many inputs were bound
	 */
	static int afterAccept(final ProcedureContext pContext, final Adaptation pFactory, final String pAttributeId) throws OperationException
	{
		final Adaptation attribute = ProposalReviews.byId(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), pAttributeId);
		if (attribute == null || !ScriptDerivation.DERIVATION_SCRIPT.equals(UserView.string(attribute, FactoryPaths.StagingAttribute.DERIVATION)))
		{
			return 0;
		}
		final String attributeName = UserView.string(attribute, FactoryPaths.StagingAttribute.NAME);
		final String entityId = UserView.string(attribute, FactoryPaths.StagingAttribute.ENTITY);
		final List<Adaptation> derivations = new ArrayList<Adaptation>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION),
			"./attribute=" + CatalogReader.quote(pAttributeId) + " and ./nature='" + ReviewLine.SCRIPT_DERIVATION + "'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				derivations.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		int bound = 0;
		for (final Adaptation derivation : derivations)
		{
			final String status = UserView.string(derivation, FactoryPaths.CapabilitySuggestion.STATUS);
			if (ReviewLine.REJECTED.equals(status) || ReviewLine.SUPERSEDED.equals(status))
			{
				continue;
			}
			for (final MissingInputs.Supply supply : MissingInputs.parseSupplies(UserView.string(derivation, FactoryPaths.CapabilitySuggestion.RATIONALE)))
			{
				bound += MissingInputRebind.bind(pContext, pFactory, supply, attributeName, entityId);
			}
		}
		return bound;
	}

	private static int bind(final ProcedureContext pContext, final Adaptation pFactory, final MissingInputs.Supply pSupply, final String pAttributeName, final String pEntityId)
		throws OperationException
	{
		final Adaptation suggestion = ProposalReviews.byId(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION), pSupply.suggestionId);
		if (suggestion == null)
		{
			return 0;
		}
		final String status = UserView.string(suggestion, FactoryPaths.CapabilitySuggestion.STATUS);
		final String nature = UserView.string(suggestion, FactoryPaths.CapabilitySuggestion.NATURE);
		if (!ReviewLine.PROPOSED.equals(status) && !ReviewLine.ACCEPTED.equals(status))
		{
			return 0; // decided meanwhile (rejected, set aside, superseded) or already applied
		}
		if (!ReviewLine.SCRIPT_CONSTRAINT.equals(nature) && !ReviewLine.EXTERNAL_SERVICE.equals(nature))
		{
			return 0;
		}
		if (!pEntityId.equals(UserView.string(suggestion, FactoryPaths.CapabilitySuggestion.ENTITY)))
		{
			return 0; // a field only feeds suggestions of its own business object
		}
		final String binding = UserView.string(suggestion, FactoryPaths.CapabilitySuggestion.BINDING);
		final String rebound = MissingInputs.rebind(binding, pSupply.inputName, pAttributeName);
		if (rebound == null)
		{
			return 0;
		}
		final String rationale = UserView.string(suggestion, FactoryPaths.CapabilitySuggestion.RATIONALE);
		final String note = MissingInputs.reboundNote(pSupply.inputName, pAttributeName);
		final String text = rationale == null || rationale.trim().isEmpty() ? note : rationale.trim() + " " + note;
		final ValueContextForUpdate value = pContext.getContext(suggestion.getAdaptationName());
		value.setValue(rebound, FactoryPaths.CapabilitySuggestion.BINDING);
		value.setValue(TextLimits.truncate(text, MissingInputRebind.RATIONALE_MAX_LENGTH), FactoryPaths.CapabilitySuggestion.RATIONALE);
		pContext.doModifyContent(suggestion, value);
		return 1;
	}
}
