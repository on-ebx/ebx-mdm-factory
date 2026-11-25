package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot P1 (2026-10-04): the parameters of a {@code SCRIPT_DERIVATION} that a PERSON settles - pure (no EBX class), the logic behind
 * {@code PUT /projects/{code}/capabilities/{id}/binding} and the wizard's "Save parameters" button ({@code ebx.DerivationParameterSettings}
 * is the thin EBX writer).
 * <p>
 * <b>The defect this closes</b> (measured 2026-10-04 on project Vol1m): the model proposed {@code toE164(value, country)} with the binding
 * {@code {"value":"phone","country":{"literal":""}}}; with a blank country the function returns null for every national-form number, so
 * 68,905 of the 92,175 phones of one source system came out empty. The tool contract FORCED the blank (a derivation could not carry a
 * {@code toBeProvided} entry), the model said so in its own rationale, and the model cannot always know the right constant either - by data
 * policy it sees value shapes with counts, not values. Since this lot a derivation binding may say {@code {"toBeProvided":"why"}}; the new
 * attribute is accepted as such (confidence untouched - see {@code capability.DerivationEvidence}), and the block is at APPLY time
 * ({@code ebx.DerivationApplier.prepare}): a person supplies the value first, here.
 * <p>
 * <b>The rule.</b> Only a parameter that is currently a {@code literal} or a {@code toBeProvided} entry may be settled; an attribute-bound
 * parameter is never changed through this logic (re-binding a source is a different decision). A settled value is stored as
 * {@code {"literal":"<value>"}}. A value may be blank only when the entry already was a literal (a blank literal is the deliberate "no
 * default"); a blank value never settles a {@code toBeProvided}. Nothing here knows what a country or a currency is: the factory checks generic
 * facts only.
 * <p>
 * <b>Stale applied body.</b> When the binding of an already APPLIED suggestion changes, the two stored trigger bodies no longer match what
 * applying again would generate. Regenerating them just to compare would need the library read through MCP, so the writer records the fact
 * itself: {@code applyMessage} is set to {@link #REAPPLY_MARKER} and every reader of the applied state treats that exact sentence as "apply
 * it again" ({@link #isReapplyMarker}). A successful {@code DerivationApplier.apply} overwrites {@code applyMessage}, which clears the marker.
 */
public final class DerivationParameters
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The fixed sentence stored in {@code applyMessage} of an APPLIED derivation whose parameters were changed afterwards - recognised by {@link #isReapplyMarker}, never reworded. */
	public static final String REAPPLY_MARKER = "Parameter(s) changed since this derivation was applied: apply it again to recompute the stored values.";

	private DerivationParameters()
	{
	}

	/** Why a request is refused, with the HTTP status the REST route answers (400 bad body, 404 unknown, 409 state). */
	public static final class Refusal extends Exception
	{
		private static final long serialVersionUID = 1L;
		public final int status;

		public Refusal(final int pStatus, final String pMessage)
		{
			super(pMessage);
			this.status = pStatus;
		}
	}

	/** One parameter of the binding a person may settle: its name, whether it is still to be provided, the current literal ("" for a to-be-provided) and the model's note for a to-be-provided. */
	public static final class Settable
	{
		public final String name;
		public final boolean toBeProvided;
		/** The current literal value; empty for a {@code toBeProvided} entry. */
		public final String value;
		/** The model's own words for a {@code toBeProvided} entry (what is needed and why); empty for a literal. */
		public final String note;

		Settable(final String pName, final boolean pToBeProvided, final String pValue, final String pNote)
		{
			this.name = pName;
			this.toBeProvided = pToBeProvided;
			this.value = pValue == null ? "" : pValue;
			this.note = pNote == null ? "" : pNote;
		}
	}

	/** The result of {@link #settle}: the new binding JSON and what it changes. */
	public static final class Update
	{
		public final String bindingJson;
		/** Parameters whose stored entry actually changed (a different literal, or a {@code toBeProvided} now settled), in binding order. */
		public final List<String> changed;
		/** Parameters still {@code toBeProvided} after the update, in binding order. */
		public final List<String> toBeProvidedRemaining;

		Update(final String pBindingJson, final List<String> pChanged, final List<String> pToBeProvidedRemaining)
		{
			this.bindingJson = pBindingJson;
			this.changed = Collections.unmodifiableList(pChanged);
			this.toBeProvidedRemaining = Collections.unmodifiableList(pToBeProvidedRemaining);
		}
	}

	/** The width of the {@code applyMessage} field ({@code CapabilitySuggestion.applyMessage}, 2000 characters): a longer text is cut to it. */
	public static final int APPLY_MESSAGE_MAX = 2000;

	/**
	 * Whether {@code pApplyMessage} says "apply again": exactly {@link #REAPPLY_MARKER} (surrounding blanks ignored), or a text that ENDS with it after
	 * a space - the form {@link #failedReapplyMessage} writes (lot T2, 2026-10-04). A text that merely starts with the marker (a note appended after
	 * it) is not one.
	 */
	public static boolean isReapplyMarker(final String pApplyMessage)
	{
		if (pApplyMessage == null)
		{
			return false;
		}
		final String text = pApplyMessage.trim();
		return DerivationParameters.REAPPLY_MARKER.equals(text) || text.endsWith(" " + DerivationParameters.REAPPLY_MARKER);
	}

	/**
	 * The {@code applyMessage} of an APPLIED derivation whose RE-apply failed (lot T2, 2026-10-04): the failure text, then the marker, so the row says
	 * both why the last attempt failed and that it must be applied again - {@link #isReapplyMarker} still recognises it, so the wizard keeps offering
	 * "Re-apply". The failure text is cut (never the marker) to fit {@link #APPLY_MESSAGE_MAX}.
	 */
	public static String failedReapplyMessage(final String pFailure)
	{
		final String failure = pFailure == null ? "" : pFailure.trim();
		if (failure.isEmpty())
		{
			return DerivationParameters.REAPPLY_MARKER;
		}
		final int room = DerivationParameters.APPLY_MESSAGE_MAX - DerivationParameters.REAPPLY_MARKER.length() - 1;
		final String cut = failure.length() > room ? failure.substring(0, Math.max(0, room - 3)) + "..." : failure;
		return cut + " " + DerivationParameters.REAPPLY_MARKER;
	}

	/** The sentence appended to a derivation's stored rationale when the model left parameter(s) to be provided. */
	public static String stillToProvideSentence(final List<String> pParameters)
	{
		return "A person must still provide " + String.join(", ", pParameters) + " (wizard, step 4) before this derivation can be applied; until then the new attribute stays empty.";
	}

	/**
	 * The refusal text of {@code DerivationApplier.prepare}: names the parameter(s) and says where a person supplies them. {@code pProjectCode} and
	 * {@code pSuggestionId} fill the REST route; either may be null (a placeholder is written).
	 */
	public static String toBeProvidedMessage(final String pSuggestionId, final List<String> pParameters, final String pProjectCode)
	{
		return "cannot apply '" + pSuggestionId + "': parameter(s) " + pParameters + " are still 'to be provided' in the binding - supply them in the wizard (step 4, "
			+ "'To apply': the parameters of this derivation, then Save parameters) or with PUT /projects/" + (pProjectCode == null ? "{code}" : pProjectCode)
			+ "/capabilities/" + (pSuggestionId == null ? "{id}" : pSuggestionId) + "/binding, then apply again.";
	}

	/** The parameters of {@code pBindingJson} a person may settle (literal or to-be-provided), in binding order; an attribute-bound one is never listed. Empty for an unreadable binding. */
	public static List<Settable> settable(final String pBindingJson)
	{
		final List<Settable> out = new ArrayList<Settable>();
		for (final ConstraintBodyBuilder.BindingEntry entry : ConstraintBodyBuilder.parseBinding(pBindingJson).values())
		{
			if (entry.kind == ConstraintBodyBuilder.BindingEntry.Kind.LITERAL)
			{
				out.add(new Settable(entry.parameter, false, entry.value, ""));
			}
			else if (entry.kind == ConstraintBodyBuilder.BindingEntry.Kind.TO_BE_PROVIDED)
			{
				out.add(new Settable(entry.parameter, true, "", entry.value));
			}
		}
		return out;
	}

	/** The names of the still to-be-provided parameters of {@code pBindingJson}, in binding order. */
	public static List<String> toBeProvidedNames(final String pBindingJson)
	{
		return ConstraintBodyBuilder.toBeProvidedParameters(ConstraintBodyBuilder.parseBinding(pBindingJson));
	}

	/**
	 * Applies {@code pValues} (parameter name to value) to {@code pBindingJson}. Every refusal is a {@link Refusal} with status 400: an empty
	 * request, a parameter the binding does not have (the message lists the settable ones), an attribute-bound parameter, a blank value for a
	 * {@code toBeProvided}, or a {@code null} value. Entries not named are carried over verbatim, in their original order.
	 */
	public static Update settle(final String pBindingJson, final Map<String, String> pValues) throws Refusal
	{
		final List<Settable> settable = DerivationParameters.settable(pBindingJson);
		final Map<String, ConstraintBodyBuilder.BindingEntry> entries = ConstraintBodyBuilder.parseBinding(pBindingJson);
		if (pValues == null || pValues.isEmpty())
		{
			throw new Refusal(400, "no parameter given: send {\"<parameter>\":\"<value>\", ...}; settable now: " + DerivationParameters.names(settable));
		}
		for (final Map.Entry<String, String> given : pValues.entrySet())
		{
			final ConstraintBodyBuilder.BindingEntry entry = entries.get(given.getKey());
			if (entry == null)
			{
				throw new Refusal(400, "unknown parameter '" + given.getKey() + "': the parameters settable on this derivation are " + DerivationParameters.names(settable));
			}
			if (entry.kind == ConstraintBodyBuilder.BindingEntry.Kind.ATTRIBUTE)
			{
				throw new Refusal(400, "parameter '" + given.getKey() + "' is bound to the attribute '" + entry.value
					+ "' and is never changed through this route; the parameters settable on this derivation are " + DerivationParameters.names(settable));
			}
			if (given.getValue() == null)
			{
				throw new Refusal(400, "parameter '" + given.getKey() + "': the value must be a string");
			}
			if (given.getValue().trim().isEmpty() && entry.kind == ConstraintBodyBuilder.BindingEntry.Kind.TO_BE_PROVIDED)
			{
				throw new Refusal(400, "parameter '" + given.getKey() + "' is still to be provided (" + entry.value
					+ "): a blank value does not settle it - give the value, or leave it to be provided");
			}
		}
		final ObjectNode root = DerivationParameters.parseObject(pBindingJson);
		final List<String> changed = new ArrayList<String>();
		final Map<String, JsonNode> rebuilt = new LinkedHashMap<String, JsonNode>();
		final java.util.Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			final String given = pValues.get(field.getKey());
			if (given == null)
			{
				rebuilt.put(field.getKey(), field.getValue());
				continue;
			}
			final ConstraintBodyBuilder.BindingEntry entry = entries.get(field.getKey());
			final boolean sameLiteral = entry.kind == ConstraintBodyBuilder.BindingEntry.Kind.LITERAL && entry.value.equals(given);
			if (!sameLiteral)
			{
				changed.add(field.getKey());
			}
			final ObjectNode literal = DerivationParameters.MAPPER.createObjectNode();
			literal.put("literal", given);
			rebuilt.put(field.getKey(), literal);
		}
		final ObjectNode out = DerivationParameters.MAPPER.createObjectNode();
		for (final Map.Entry<String, JsonNode> entry : rebuilt.entrySet())
		{
			out.set(entry.getKey(), entry.getValue());
		}
		final String json = out.toString();
		return new Update(json, changed, DerivationParameters.toBeProvidedNames(json));
	}

	private static ObjectNode parseObject(final String pBindingJson) throws Refusal
	{
		try
		{
			final JsonNode root = DerivationParameters.MAPPER.readTree(pBindingJson);
			if (root instanceof ObjectNode)
			{
				return (ObjectNode) root;
			}
		}
		catch (final Exception ex)
		{
			// falls through to the refusal below
		}
		throw new Refusal(409, "the stored binding of this suggestion is not a JSON object: nothing can be settled on it");
	}

	private static List<String> names(final List<Settable> pSettable)
	{
		final List<String> out = new ArrayList<String>();
		for (final Settable settable : pSettable)
		{
			out.add(settable.name);
		}
		return out;
	}
}
