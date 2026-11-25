package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot M10c (2026-10-18): the Factory's own proposal of a person NAME KEY, so that its existence never depends on the language model remembering to propose it.
 * <p>
 * <b>The measured motive.</b> The seventh end-to-end run (2026-10-18, scenario of {@code tools/e2e/b2c_master.py}) gave the same entity (first name + last name, three
 * source systems) two different outcomes: in one project the capability pass proposed {@code Fasttrack.lib.names.sortedKeyOf(first, last)} as a derived attribute
 * (precision of the matching pairs 0.97), in the other it did not, the matching plan had no exact name key and the precision fell to 0.67. The model proposes among a score
 * of other things in the same run; a key that the matching plan depends on cannot be left to that variance.
 * <p>
 * <b>The rule</b> ({@link #check}) is structural: an entity that is still in play (not rejected, not superseded, not a member that a canonical entity absorbs - {@link #eligible}), whose published library {@value #LIBRARY_IMPORT_NAME} offers a function of the
 * shape {@value #FUNCTION_NAME}(text, text): text, that has two STRING attributes declared as a first name and a last name ({@link NameParts}), and for which no derivation
 * of that library exists yet (proposed by the model in this run, or stored by an earlier run, in any status - a rejected one included: a person's refusal stands) gets the
 * derivation {@code newAttribute = names.sortedKeyOf(first, last)} proposed with origin {@code RULE}, exactly the rows the model's own proposal writes (a
 * {@code StagingAttribute} with {@code derivation = SCRIPT} and a {@code CapabilitySuggestion} {@code SCRIPT_DERIVATION}), flagged as a cross-system key so that the measured
 * coverage decides its stored confidence like any other key ({@link DerivationEvidence}). The matching pass then measures it like every key; nothing changes there.
 * <p>
 * <b>Where the roles come from.</b> The Factory stores no first-name/last-name role on a {@code StagingAttribute} (checked: no such field, and the catalogue's
 * {@code PERSON_NAME} tag does not tell the two apart and is absent from file sources). The rule therefore takes the pair as an input, {@link NameParts}; the only
 * producer today is {@link NamePartsPass}, one closed-vocabulary question ("which attributes of this entity are the first name and the last name, if any") whose answer
 * can only be an attribute of the entity - never a name matched by Java in any language. If a later staging pass stores the roles, it builds the same {@link NameParts}
 * and nothing else here changes. The library signature is pinned by this class ({@link #findFunction}), the way a rule that positions a given library must.
 * <p>
 * Pure: no EBX class. {@code ebx.CapabilityLlmSuggester} reads the data, calls this class and writes the rows.
 */
public final class NameKeyRule
{
	/** The import name of the published library the rule uses. */
	public static final String LIBRARY_IMPORT_NAME = "Fasttrack.lib.names";
	/** The function: two optional text parameters (first name, last name), one text result - order-independent, accent-folded, null-safe (the library's own documentation). */
	public static final String FUNCTION_NAME = "sortedKeyOf";
	/** The stored confidence of the proposal before the measured coverage decides ({@link DerivationEvidence}); the matching bulk accept reads the decided number. */
	public static final int CONFIDENCE = 70;
	/** The name given to the new attribute; a number is appended when an attribute of the entity already has it. */
	public static final String ATTRIBUTE_BASE_NAME = "personNameKey";

	private static final Pattern STRING_TYPE = Pattern.compile("(?i)string|text");
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private NameKeyRule()
	{
	}

	/** The two attributes of an entity declared as the first name and the last name of a person. */
	public static final class NameParts
	{
		public final String firstName;
		public final String lastName;

		public NameParts(final String pFirstName, final String pLastName)
		{
			this.firstName = pFirstName;
			this.lastName = pLastName;
		}
	}

	/** Why the rule does not propose; {@link #NONE} when it may. {@code NOT_ELIGIBLE}: the entity is rejected, superseded or absorbed by a canonical entity (lot M11; it read {@code NOT_PROMOTED} in lot M10c). */
	public enum Skip
	{
		NONE, NOT_ELIGIBLE, LIBRARY_ABSENT, DERIVATION_EXISTS, TOO_FEW_CANDIDATES;
	}

	/**
	 * The library function the rule binds: {@value #FUNCTION_NAME} of {@value #LIBRARY_IMPORT_NAME} with exactly two parameters of a text type (an unknown type is accepted: it
	 * blocks nothing, the apply compiles the call) and a result that becomes a text attribute. Null when the library is not published, lacks the function, or the signature
	 * changed - the rule then stays silent, like any proposal that cannot be backed by the inventory.
	 */
	public static CapabilityLlmPass.FunctionRef findFunction(final Map<String, List<CapabilityLlmPass.FunctionRef>> pFunctionsByLibrary)
	{
		if (pFunctionsByLibrary == null)
		{
			return null;
		}
		for (final List<CapabilityLlmPass.FunctionRef> functions : pFunctionsByLibrary.values())
		{
			for (final CapabilityLlmPass.FunctionRef function : functions)
			{
				if (!NameKeyRule.LIBRARY_IMPORT_NAME.equals(function.libraryImportName) || !NameKeyRule.FUNCTION_NAME.equals(function.functionName))
				{
					continue;
				}
				if (function.parameters.size() != 2 || !NameKeyRule.isText(function.parameters.get(0).type) || !NameKeyRule.isText(function.parameters.get(1).type))
				{
					continue;
				}
				if (!"STRING".equals(DerivationBodyBuilder.dataTypeForReturnType(function.returnType)))
				{
					continue;
				}
				return function;
			}
		}
		return null;
	}

	private static boolean isText(final String pType)
	{
		return pType == null || NameKeyRule.STRING_TYPE.matcher(pType.trim()).matches();
	}

	/**
	 * The attributes the pair may be chosen among: STRING attributes of the entity that are neither computed (a derived key is not a name) nor a reference to another
	 * entity. {@code pExcluded} carries the names of those, read by the caller from the stored attributes.
	 */
	public static List<CapabilityLlmPass.AttributeInfo> candidates(final CapabilityLlmPass.EntityInfo pEntity, final Set<String> pExcluded)
	{
		final List<CapabilityLlmPass.AttributeInfo> out = new ArrayList<CapabilityLlmPass.AttributeInfo>();
		for (final CapabilityLlmPass.AttributeInfo attribute : pEntity.attributes)
		{
			final boolean text = attribute.dataType == null || attribute.dataType.trim().isEmpty() || "STRING".equalsIgnoreCase(attribute.dataType.trim());
			if (text && (pExcluded == null || !pExcluded.contains(attribute.name)))
			{
				out.add(attribute);
			}
		}
		return out;
	}

	/**
	 * Lot M11 (2026-10-18): whether the entity is a candidate for a name key at all. The first version asked that the entity "reach the master zone", but the capability
	 * pass runs BEFORE the master model exists, so promotion is not decided yet and the check read {@code NOT_PROMOTED} on every entity of the seventh end-to-end run. The
	 * test is now the one the model's own key derivations (a phone's E.164 form...) use: the entity is neither rejected nor superseded, and is not a member that a canonical
	 * entity absorbs ({@code StagingEntity.memberEntities}); whether it is promoted is not asked.
	 *
	 * @param pStatus the entity's status ({@code PROPOSED}, {@code ACCEPTED}, {@code APPLIED}, {@code REJECTED}, {@code SUPERSEDED}).
	 * @param pAbsorbedByCanonical the entity appears in the {@code memberEntities} of another entity that is itself in play.
	 */
	public static boolean eligible(final String pStatus, final boolean pAbsorbedByCanonical)
	{
		if (pAbsorbedByCanonical)
		{
			return false;
		}
		return pStatus == null || !("REJECTED".equalsIgnoreCase(pStatus.trim()) || "SUPERSEDED".equalsIgnoreCase(pStatus.trim()));
	}

	/**
	 * Whether the rule has any business with this entity, decided BEFORE the question about the roles is asked (so an entity the rule can never serve costs no model call).
	 *
	 * @param pEligible {@link #eligible}'s answer for the entity.
	 * @param pFunction {@link #findFunction}'s answer.
	 * @param pProposedThisRun the derivations the model proposed in the same run.
	 */
	public static Skip check(final CapabilityLlmPass.EntityInfo pEntity, final boolean pEligible, final CapabilityLlmPass.FunctionRef pFunction,
		final List<CapabilityLlmPass.ProposedScriptDerivation> pProposedThisRun, final List<CapabilityLlmPass.AttributeInfo> pCandidates)
	{
		if (pFunction == null)
		{
			return Skip.LIBRARY_ABSENT;
		}
		if (!pEligible)
		{
			return Skip.NOT_ELIGIBLE;
		}
		if (NameKeyRule.usesLibrary(pEntity, pFunction, pProposedThisRun))
		{
			return Skip.DERIVATION_EXISTS;
		}
		if (pCandidates == null || pCandidates.size() < 2)
		{
			return Skip.TOO_FEW_CANDIDATES;
		}
		return Skip.NONE;
	}

	/**
	 * A derivation of the library already exists for the entity: one the model proposed in this run, or a stored one of any nature-SCRIPT_DERIVATION row ({@code
	 * entity.existing}, every status). The test is the library, not the exact attribute pair: a derivation of the names library on a full-name attribute, or on other
	 * parts, already is somebody's decision about how the name is keyed.
	 */
	public static boolean usesLibrary(final CapabilityLlmPass.EntityInfo pEntity, final CapabilityLlmPass.FunctionRef pFunction,
		final List<CapabilityLlmPass.ProposedScriptDerivation> pProposedThisRun)
	{
		if (pProposedThisRun != null)
		{
			for (final CapabilityLlmPass.ProposedScriptDerivation proposed : pProposedThisRun)
			{
				if (pFunction.libraryIdePath.equals(proposed.libraryIdePath))
				{
					return true;
				}
			}
		}
		for (final CapabilityLlmPass.ExistingSuggestion existing : pEntity.existing)
		{
			if (CapabilityLlmPass.SCRIPT_DERIVATION.equals(existing.nature) && pFunction.libraryIdePath.equals(existing.targetLibrary))
			{
				return true;
			}
		}
		return false;
	}

	/** The name of the new attribute: {@value #ATTRIBUTE_BASE_NAME}, then {@code ...2}, {@code ...3} when an attribute of the entity (any status) already has it. */
	public static String attributeName(final Set<String> pTakenNames)
	{
		String name = NameKeyRule.ATTRIBUTE_BASE_NAME;
		int n = 2;
		while (pTakenNames != null && pTakenNames.contains(name))
		{
			name = NameKeyRule.ATTRIBUTE_BASE_NAME + n;
			n++;
		}
		return name;
	}

	/**
	 * The proposal for a pair that passed {@link #check}, or null when the pair does not hold on this entity (an attribute missing from the candidates, the two the same).
	 * Origin {@code RULE}, {@link #CONFIDENCE}, cross-system key; the binding is the one the model's own proposals carry: the library's parameter names mapped to the
	 * attributes ({@code {"firstName":"prenom","lastName":"nom"}}), first parameter = first name, second = last name.
	 *
	 * @param pLabel the label of the new attribute, in the person's language.
	 * @param pRationale the rationale, in the person's language.
	 */
	public static CapabilityLlmPass.ProposedScriptDerivation propose(final CapabilityLlmPass.FunctionRef pFunction, final NameParts pParts,
		final List<CapabilityLlmPass.AttributeInfo> pCandidates, final Set<String> pTakenNames, final String pLabel, final String pRationale)
	{
		if (pFunction == null || pParts == null || pParts.firstName == null || pParts.lastName == null || pParts.firstName.equals(pParts.lastName))
		{
			return null;
		}
		final Set<String> names = new LinkedHashSet<String>();
		for (final CapabilityLlmPass.AttributeInfo candidate : pCandidates)
		{
			names.add(candidate.name);
		}
		if (!names.contains(pParts.firstName) || !names.contains(pParts.lastName))
		{
			return null;
		}
		final String dataType = DerivationBodyBuilder.dataTypeForReturnType(pFunction.returnType);
		if (dataType == null)
		{
			return null;
		}
		final ObjectNode binding = NameKeyRule.MAPPER.createObjectNode();
		binding.put(pFunction.parameters.get(0).name, pParts.firstName);
		binding.put(pFunction.parameters.get(1).name, pParts.lastName);
		final String attributeName = NameKeyRule.attributeName(pTakenNames);
		return new CapabilityLlmPass.ProposedScriptDerivation(attributeName, pLabel == null || pLabel.trim().isEmpty() ? attributeName : pLabel.trim(), dataType,
			pParts.firstName, Collections.unmodifiableList(java.util.Arrays.asList(pParts.firstName, pParts.lastName)), pFunction.libraryIdePath,
			pFunction.libraryImportName, pFunction.functionName, binding.toString(), NameKeyRule.CONFIDENCE, true, pRationale, "RULE");
	}

	/** The candidates by name, in entity order - a convenience for the question about the roles. */
	public static Map<String, CapabilityLlmPass.AttributeInfo> byName(final List<CapabilityLlmPass.AttributeInfo> pCandidates)
	{
		final Map<String, CapabilityLlmPass.AttributeInfo> out = new LinkedHashMap<String, CapabilityLlmPass.AttributeInfo>();
		for (final CapabilityLlmPass.AttributeInfo candidate : pCandidates)
		{
			out.put(candidate.name, candidate);
		}
		return out;
	}
}
