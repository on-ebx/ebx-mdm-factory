package com.on.ebx.factory.ebx;

import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.capability.DerivationParameters;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Lot P1 (2026-10-04): the thin EBX half of "a person settles a derivation's parameter" - the rules are {@link DerivationParameters} (pure). Reads
 * the {@code SCRIPT_DERIVATION} suggestion, applies the person's values to its stored {@code binding}, writes the new binding in a {@link Procedure}
 * (same write path as {@code DerivationApplier.writeApplied}) and, when the suggestion is already APPLIED, records that its stored trigger bodies are
 * now stale by writing {@link DerivationParameters#REAPPLY_MARKER} into {@code applyMessage}. Never applies: applying republishes a model and
 * rewrites a table, it stays an explicit action ({@code POST .../apply}, the wizard's Apply).
 * <p>
 * The stored {@code scriptBody} is deliberately left as it is - it still describes what is live until the next apply replaces it.
 */
public final class DerivationParameterSettings
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String NATURE_SCRIPT_DERIVATION = "SCRIPT_DERIVATION";
	private static final String STATUS_APPLIED = "APPLIED";
	private static final String STATUS_REJECTED = "REJECTED";
	/** {@code CapabilitySuggestion.binding}'s own length bound ({@code CapabilityLlmSuggester.BINDING_MAX_LENGTH}, the XSD field): a longer binding is refused, never silently cut - a cut JSON would read as an empty binding. */
	static final int BINDING_MAX_LENGTH = 4000;

	private DerivationParameterSettings()
	{
	}

	/**
	 * Settles the named parameters of one {@code SCRIPT_DERIVATION} suggestion of project {@code pProjectCode} and answers {@code {"suggestion",
	 * "binding" (the new binding, as stored), "changed", "toBeProvidedRemaining", "reapplyNeeded"}}. {@code reapplyNeeded} is true when the suggestion
	 * is APPLIED and its stored bodies are stale ({@code applyMessage} is the {@link DerivationParameters#REAPPLY_MARKER}).
	 *
	 * @throws DerivationParameters.Refusal 404 project/suggestion not found, 409 not a derivation / rejected / not of this project, 400 the values
	 */
	public static ObjectNode settle(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pSuggestionId,
		final Map<String, String> pValues) throws DerivationParameters.Refusal, OperationException
	{
		final Adaptation project = ProposalReviews.findProjectByCode(pFactory, pProjectCode);
		if (project == null)
		{
			throw new DerivationParameters.Refusal(404, "No project with code " + pProjectCode);
		}
		final AdaptationTable table = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION);
		final Adaptation row = ProposalReviews.byId(table, pSuggestionId);
		if (row == null)
		{
			throw new DerivationParameters.Refusal(404, "capability suggestion '" + pSuggestionId + "' not found");
		}
		if (!project.getOccurrencePrimaryKey().format().equals(DerivationParameterSettings.text(row, FactoryPaths.CapabilitySuggestion.PROJECT)))
		{
			throw new DerivationParameters.Refusal(409, "capability suggestion '" + pSuggestionId + "' does not belong to project " + pProjectCode);
		}
		if (!DerivationParameterSettings.NATURE_SCRIPT_DERIVATION.equals(DerivationParameterSettings.text(row, FactoryPaths.CapabilitySuggestion.NATURE)))
		{
			throw new DerivationParameters.Refusal(409, "only a SCRIPT_DERIVATION suggestion has parameters to settle - '" + pSuggestionId + "' is "
				+ DerivationParameterSettings.text(row, FactoryPaths.CapabilitySuggestion.NATURE));
		}
		final String status = DerivationParameterSettings.text(row, FactoryPaths.CapabilitySuggestion.STATUS);
		if (DerivationParameterSettings.STATUS_REJECTED.equals(status))
		{
			throw new DerivationParameters.Refusal(409, "suggestion '" + pSuggestionId + "' was rejected: there is nothing to settle on it");
		}
		final DerivationParameters.Update update = DerivationParameters.settle(DerivationParameterSettings.text(row, FactoryPaths.CapabilitySuggestion.BINDING), pValues);
		if (update.bindingJson.length() > DerivationParameterSettings.BINDING_MAX_LENGTH)
		{
			throw new DerivationParameters.Refusal(400, "the binding would be " + update.bindingJson.length() + " characters long, more than the " + DerivationParameterSettings.BINDING_MAX_LENGTH
				+ " the suggestion can hold: give shorter values");
		}
		final boolean applied = DerivationParameterSettings.STATUS_APPLIED.equals(status);
		// only an APPLIED derivation has stored bodies that can go stale - and only when a stored entry really changed (the same value again changes nothing)
		final boolean markStale = applied && !update.changed.isEmpty();
		// lot T2 (2026-10-04): a refusal of the last apply attempt is stored in applyMessage now ("parameter(s) [x] are still to be provided"); once a value of a
		// not-yet-applied derivation really changed, that text is about a binding that no longer exists - it is cleared, never left to contradict the screen
		final boolean clearStaleRefusal = !applied && !update.changed.isEmpty();
		final String previousMessage = DerivationParameterSettings.text(row, FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation current = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pSuggestionId));
				if (current == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(current.getAdaptationName());
				value.setValue(update.bindingJson, FactoryPaths.CapabilitySuggestion.BINDING);
				if (markStale)
				{
					value.setValue(DerivationParameters.REAPPLY_MARKER, FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE);
				}
				else if (clearStaleRefusal)
				{
					value.setValue(null, FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE);
				}
				pContext.doModifyContent(current, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveParameters", "could not write the parameters of suggestion " + pSuggestionId + ": "
				+ result.getExceptionFullMessage(pSession == null ? Locale.ENGLISH : pSession.getLocale()), pSuggestionId);
		}
		final ObjectNode out = DerivationParameterSettings.MAPPER.createObjectNode();
		out.put("suggestion", pSuggestionId);
		try
		{
			out.set("binding", DerivationParameterSettings.MAPPER.readTree(update.bindingJson));
		}
		catch (final Exception ex)
		{
			out.put("binding", update.bindingJson);
		}
		final ArrayNode changed = out.putArray("changed");
		for (final String name : update.changed)
		{
			changed.add(name);
		}
		final ArrayNode remaining = out.putArray("toBeProvidedRemaining");
		for (final String name : update.toBeProvidedRemaining)
		{
			remaining.add(name);
		}
		out.put("reapplyNeeded", applied && (markStale || DerivationParameters.isReapplyMarker(previousMessage)));
		return out;
	}

	private static String text(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final String value = pRow.getString(pPath);
		return value == null ? "" : value;
	}
}
