package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.wizard.WizardMessages;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Lot M10c (2026-10-18): the Factory's own proposal of a person name key. The fixtures name
 * the two attributes {@code p1}/{@code p2} on purpose: the rule must never read an attribute name, whatever the language of the source - the pair is an INPUT
 * ({@link NameKeyRule.NameParts}), the one thing the model decides.
 */
public final class NameKeyRuleTest
{
	public static void main(final String[] pArgs)
	{
		NameKeyRuleTest.findFunctionNeedsTheLibraryAndTheSignature();
		NameKeyRuleTest.candidatesAreTheTextAttributesThatAreNeitherComputedNorReferences();
		NameKeyRuleTest.proposedWhenLibraryPromotedPairAndNoDerivation();
		NameKeyRuleTest.refusedWhenTheLibraryIsAbsent();
		NameKeyRuleTest.refusedWhenTheEntityIsNotEligible();
		NameKeyRuleTest.eligibilityIgnoresPromotion();
		NameKeyRuleTest.neverDuplicatesAModelProposalOfThisRun();
		NameKeyRuleTest.neverDuplicatesAStoredProposalWhateverItsStatus();
		NameKeyRuleTest.aDerivationOfAnotherLibraryDoesNotBlock();
		NameKeyRuleTest.needsTwoCandidates();
		NameKeyRuleTest.aPairOutsideTheCandidatesIsNotProposed();
		NameKeyRuleTest.theNewAttributeNeverTakesAnExistingName();
		NameKeyRuleTest.theStoredBindingIsReadByTheSameParserAsTheModelsOwn();
		NameKeyRuleTest.theMeasuredCoverageDecidesTheStoredConfidenceLikeAnyKey();
		NameKeyRuleTest.theBundleSentencesExistInBothLanguages();
		System.out.println("NameKeyRuleTest OK");
	}

	// ---------------------------------------------------------------------------------------------------------------- fixtures

	private static final String LIBRARY_IDE_PATH = "library/Fasttrack/lib/names";

	private static CapabilityLlmPass.FunctionRef sortedKeyOf(final String pFirstType, final String pSecondType, final String pReturnType)
	{
		return new CapabilityLlmPass.FunctionRef(NameKeyRuleTest.LIBRARY_IDE_PATH, NameKeyRule.LIBRARY_IMPORT_NAME, "Names", "Order-independent name keys.", NameKeyRule.FUNCTION_NAME,
			Arrays.asList(new CapabilityLlmPass.ParamRef("firstName", pFirstType), new CapabilityLlmPass.ParamRef("lastName", pSecondType)), pReturnType, "key");
	}

	private static Map<String, List<CapabilityLlmPass.FunctionRef>> inventory(final CapabilityLlmPass.FunctionRef... pFunctions)
	{
		final Map<String, List<CapabilityLlmPass.FunctionRef>> out = new LinkedHashMap<String, List<CapabilityLlmPass.FunctionRef>>();
		for (final CapabilityLlmPass.FunctionRef function : pFunctions)
		{
			List<CapabilityLlmPass.FunctionRef> forLibrary = out.get(function.libraryIdePath);
			if (forLibrary == null)
			{
				forLibrary = new ArrayList<CapabilityLlmPass.FunctionRef>();
				out.put(function.libraryIdePath, forLibrary);
			}
			forLibrary.add(function);
		}
		return out;
	}

	private static CapabilityLlmPass.FunctionRef phone()
	{
		return new CapabilityLlmPass.FunctionRef("library/Fasttrack/lib/phone", "Fasttrack.lib.phone", "Phone", "Phone numbers.", "toE164",
			Arrays.asList(new CapabilityLlmPass.ParamRef("value", "string")), "string", "doc");
	}

	private static CapabilityLlmPass.AttributeInfo attribute(final String pName, final String pType)
	{
		return new CapabilityLlmPass.AttributeInfo(pName, pType, 100);
	}

	private static CapabilityLlmPass.EntityInfo entity(final List<CapabilityLlmPass.ExistingSuggestion> pExisting, final CapabilityLlmPass.AttributeInfo... pAttributes)
	{
		return new CapabilityLlmPass.EntityInfo("Thing", "Thing", true, Arrays.asList(pAttributes), pExisting);
	}

	private static CapabilityLlmPass.EntityInfo twoTextAttributes()
	{
		return NameKeyRuleTest.entity(null, NameKeyRuleTest.attribute("p1", "STRING"), NameKeyRuleTest.attribute("p2", "STRING"), NameKeyRuleTest.attribute("n", "INTEGER"));
	}

	private static CapabilityLlmPass.ExistingSuggestion storedDerivation(final String pLibrary, final String pStatus)
	{
		return new CapabilityLlmPass.ExistingSuggestion(CapabilityLlmPass.SCRIPT_DERIVATION, pLibrary, "sortedKeyAny", null, null, pStatus);
	}

	private static CapabilityLlmPass.ProposedScriptDerivation modelDerivation(final String pLibraryIdePath, final String pName)
	{
		return new CapabilityLlmPass.ProposedScriptDerivation(pName, pName, "STRING", "p1", Arrays.asList("p1"), pLibraryIdePath, "x.y", "f", "{\"v\":\"p1\"}", 80, true, "r");
	}

	private static NameKeyRule.Skip check(final CapabilityLlmPass.EntityInfo pEntity, final boolean pPromoted, final CapabilityLlmPass.FunctionRef pFunction,
		final List<CapabilityLlmPass.ProposedScriptDerivation> pByModel)
	{
		return NameKeyRule.check(pEntity, pPromoted, pFunction, pByModel, NameKeyRule.candidates(pEntity, Collections.<String>emptySet()));
	}

	// ---------------------------------------------------------------------------------------------------------------- tests

	private static void findFunctionNeedsTheLibraryAndTheSignature()
	{
		NameKeyRuleTest.check("found with the exact signature", NameKeyRule.findFunction(NameKeyRuleTest.inventory(NameKeyRuleTest.sortedKeyOf("string", "string", "string"))) != null);
		NameKeyRuleTest.check("an unknown parameter type does not block", NameKeyRule.findFunction(NameKeyRuleTest.inventory(NameKeyRuleTest.sortedKeyOf(null, null, "string"))) != null);
		NameKeyRuleTest.check("library absent", NameKeyRule.findFunction(NameKeyRuleTest.inventory(NameKeyRuleTest.phone())) == null);
		NameKeyRuleTest.check("empty inventory", NameKeyRule.findFunction(NameKeyRuleTest.inventory()) == null);
		NameKeyRuleTest.check("null inventory", NameKeyRule.findFunction(null) == null);
		NameKeyRuleTest.check("a numeric parameter is another function", NameKeyRule.findFunction(NameKeyRuleTest.inventory(NameKeyRuleTest.sortedKeyOf("string", "decimal", "string"))) == null);
		NameKeyRuleTest.check("a json result is not a key", NameKeyRule.findFunction(NameKeyRuleTest.inventory(NameKeyRuleTest.sortedKeyOf("string", "string", "json_object"))) == null);
		final CapabilityLlmPass.FunctionRef threeParameters = new CapabilityLlmPass.FunctionRef(NameKeyRuleTest.LIBRARY_IDE_PATH, NameKeyRule.LIBRARY_IMPORT_NAME, "Names", "d",
			NameKeyRule.FUNCTION_NAME, Arrays.asList(new CapabilityLlmPass.ParamRef("a", "string"), new CapabilityLlmPass.ParamRef("b", "string"),
				new CapabilityLlmPass.ParamRef("c", "string")), "string", "d");
		NameKeyRuleTest.check("another arity is another function", NameKeyRule.findFunction(NameKeyRuleTest.inventory(threeParameters)) == null);
	}

	private static void candidatesAreTheTextAttributesThatAreNeitherComputedNorReferences()
	{
		final CapabilityLlmPass.EntityInfo entity = NameKeyRuleTest.entity(null, NameKeyRuleTest.attribute("p1", "STRING"), NameKeyRuleTest.attribute("p2", "string"),
			NameKeyRuleTest.attribute("computed", "STRING"), NameKeyRuleTest.attribute("ref", "STRING"), NameKeyRuleTest.attribute("age", "INTEGER"),
			NameKeyRuleTest.attribute("when", "DATE"));
		final Set<String> excluded = new LinkedHashSet<String>(Arrays.asList("computed", "ref"));
		final List<CapabilityLlmPass.AttributeInfo> candidates = NameKeyRule.candidates(entity, excluded);
		NameKeyRuleTest.check("only the two plain text attributes: " + candidates.size(), candidates.size() == 2 && candidates.get(0).name.equals("p1") && candidates.get(1).name.equals("p2"));
	}

	private static void proposedWhenLibraryPromotedPairAndNoDerivation()
	{
		final CapabilityLlmPass.FunctionRef function = NameKeyRuleTest.sortedKeyOf("string", "string", "string");
		final CapabilityLlmPass.EntityInfo entity = NameKeyRuleTest.twoTextAttributes();
		final List<CapabilityLlmPass.AttributeInfo> candidates = NameKeyRule.candidates(entity, Collections.<String>emptySet());
		NameKeyRuleTest.check("allowed", NameKeyRuleTest.check(entity, true, function, Collections.<CapabilityLlmPass.ProposedScriptDerivation>emptyList()) == NameKeyRule.Skip.NONE);
		final CapabilityLlmPass.ProposedScriptDerivation proposal = NameKeyRule.propose(function, new NameKeyRule.NameParts("p1", "p2"), candidates,
			new LinkedHashSet<String>(Arrays.asList("p1", "p2", "n")), "Label", "Why.");
		NameKeyRuleTest.check("a proposal", proposal != null);
		NameKeyRuleTest.check("origin RULE", "RULE".equals(proposal.origin));
		NameKeyRuleTest.check("confidence 70 before the measured coverage", proposal.confidence == 70);
		NameKeyRuleTest.check("flagged as a cross-system key, so the coverage is measured like any key", proposal.crossSystemKey);
		NameKeyRuleTest.check("a text attribute: " + proposal.dataType, "STRING".equals(proposal.dataType));
		NameKeyRuleTest.check("named personNameKey: " + proposal.newAttributeName, "personNameKey".equals(proposal.newAttributeName));
		NameKeyRuleTest.check("the library as the model's proposals carry it", NameKeyRuleTest.LIBRARY_IDE_PATH.equals(proposal.libraryIdePath)
			&& "Fasttrack.lib.names".equals(proposal.libraryImportName) && "sortedKeyOf".equals(proposal.functionName));
		NameKeyRuleTest.check("binding by the library's parameter names: " + proposal.bindingJson, "{\"firstName\":\"p1\",\"lastName\":\"p2\"}".equals(proposal.bindingJson));
		NameKeyRuleTest.check("source = the first name, both attributes bound", "p1".equals(proposal.sourceAttribute) && proposal.boundAttributes.equals(Arrays.asList("p1", "p2")));
		NameKeyRuleTest.check("label and rationale carried", "Label".equals(proposal.newAttributeLabel) && "Why.".equals(proposal.rationale));
	}

	private static void refusedWhenTheLibraryIsAbsent()
	{
		NameKeyRuleTest.check("no function, no proposal", NameKeyRuleTest.check(NameKeyRuleTest.twoTextAttributes(), true, null,
			Collections.<CapabilityLlmPass.ProposedScriptDerivation>emptyList()) == NameKeyRule.Skip.LIBRARY_ABSENT);
		NameKeyRuleTest.check("propose refuses too", NameKeyRule.propose(null, new NameKeyRule.NameParts("p1", "p2"),
			NameKeyRule.candidates(NameKeyRuleTest.twoTextAttributes(), Collections.<String>emptySet()), Collections.<String>emptySet(), "l", "r") == null);
	}

	private static void refusedWhenTheEntityIsNotEligible()
	{
		NameKeyRuleTest.check("a rejected, superseded or absorbed entity gets no key", NameKeyRuleTest.check(NameKeyRuleTest.twoTextAttributes(), false, NameKeyRuleTest.sortedKeyOf("string", "string", "string"),
			Collections.<CapabilityLlmPass.ProposedScriptDerivation>emptyList()) == NameKeyRule.Skip.NOT_ELIGIBLE);
	}

	private static void eligibilityIgnoresPromotion()
	{
		NameKeyRuleTest.check("a proposed entity is eligible (the master zone does not exist yet)", NameKeyRule.eligible("PROPOSED", false));
		NameKeyRuleTest.check("an accepted entity", NameKeyRule.eligible("ACCEPTED", false));
		NameKeyRuleTest.check("no status known", NameKeyRule.eligible(null, false));
		NameKeyRuleTest.check("rejected", !NameKeyRule.eligible("REJECTED", false));
		NameKeyRuleTest.check("superseded", !NameKeyRule.eligible("superseded", false));
		NameKeyRuleTest.check("a member that a canonical entity absorbs", !NameKeyRule.eligible("ACCEPTED", true));
	}

	private static void neverDuplicatesAModelProposalOfThisRun()
	{
		final CapabilityLlmPass.FunctionRef function = NameKeyRuleTest.sortedKeyOf("string", "string", "string");
		NameKeyRuleTest.check("the model already proposed a derivation of the library", NameKeyRuleTest.check(NameKeyRuleTest.twoTextAttributes(), true, function,
			Arrays.asList(NameKeyRuleTest.modelDerivation(NameKeyRuleTest.LIBRARY_IDE_PATH, "whatever"))) == NameKeyRule.Skip.DERIVATION_EXISTS);
	}

	private static void neverDuplicatesAStoredProposalWhateverItsStatus()
	{
		final CapabilityLlmPass.FunctionRef function = NameKeyRuleTest.sortedKeyOf("string", "string", "string");
		for (final String status : new String[] { "PROPOSED", "ACCEPTED", "APPLIED", "REJECTED", "SUPERSEDED" })
		{
			final CapabilityLlmPass.EntityInfo entity = NameKeyRuleTest.entity(Arrays.asList(NameKeyRuleTest.storedDerivation(NameKeyRuleTest.LIBRARY_IDE_PATH, status)),
				NameKeyRuleTest.attribute("p1", "STRING"), NameKeyRuleTest.attribute("p2", "STRING"));
			NameKeyRuleTest.check("a stored derivation of the library (" + status + ") stands", NameKeyRuleTest.check(entity, true, function,
				Collections.<CapabilityLlmPass.ProposedScriptDerivation>emptyList()) == NameKeyRule.Skip.DERIVATION_EXISTS);
		}
	}

	private static void aDerivationOfAnotherLibraryDoesNotBlock()
	{
		final CapabilityLlmPass.FunctionRef function = NameKeyRuleTest.sortedKeyOf("string", "string", "string");
		final CapabilityLlmPass.EntityInfo entity = NameKeyRuleTest.entity(Arrays.asList(NameKeyRuleTest.storedDerivation("library/Fasttrack/lib/phone", "APPLIED"),
			new CapabilityLlmPass.ExistingSuggestion(CapabilityLlmPass.SCRIPT_CONSTRAINT, NameKeyRuleTest.LIBRARY_IDE_PATH, "x", null, null, "APPLIED")),
			NameKeyRuleTest.attribute("p1", "STRING"), NameKeyRuleTest.attribute("p2", "STRING"));
		NameKeyRuleTest.check("a phone derivation and a constraint on the names library do not count", NameKeyRuleTest.check(entity, true, function,
			Arrays.asList(NameKeyRuleTest.modelDerivation("library/Fasttrack/lib/phone", "phoneE164"))) == NameKeyRule.Skip.NONE);
	}

	private static void needsTwoCandidates()
	{
		final CapabilityLlmPass.EntityInfo entity = NameKeyRuleTest.entity(null, NameKeyRuleTest.attribute("p1", "STRING"), NameKeyRuleTest.attribute("n", "INTEGER"));
		NameKeyRuleTest.check("one text attribute cannot be a pair", NameKeyRuleTest.check(entity, true, NameKeyRuleTest.sortedKeyOf("string", "string", "string"),
			Collections.<CapabilityLlmPass.ProposedScriptDerivation>emptyList()) == NameKeyRule.Skip.TOO_FEW_CANDIDATES);
	}

	private static void aPairOutsideTheCandidatesIsNotProposed()
	{
		final CapabilityLlmPass.FunctionRef function = NameKeyRuleTest.sortedKeyOf("string", "string", "string");
		final List<CapabilityLlmPass.AttributeInfo> candidates = NameKeyRule.candidates(NameKeyRuleTest.twoTextAttributes(), Collections.<String>emptySet());
		NameKeyRuleTest.check("unknown first", NameKeyRule.propose(function, new NameKeyRule.NameParts("zz", "p2"), candidates, Collections.<String>emptySet(), "l", "r") == null);
		NameKeyRuleTest.check("unknown last", NameKeyRule.propose(function, new NameKeyRule.NameParts("p1", "zz"), candidates, Collections.<String>emptySet(), "l", "r") == null);
		NameKeyRuleTest.check("the same attribute twice", NameKeyRule.propose(function, new NameKeyRule.NameParts("p1", "p1"), candidates, Collections.<String>emptySet(), "l", "r") == null);
		NameKeyRuleTest.check("no pair", NameKeyRule.propose(function, null, candidates, Collections.<String>emptySet(), "l", "r") == null);
		NameKeyRuleTest.check("a non-text attribute is not a candidate", NameKeyRule.propose(function, new NameKeyRule.NameParts("p1", "n"), candidates, Collections.<String>emptySet(), "l", "r") == null);
	}

	private static void theNewAttributeNeverTakesAnExistingName()
	{
		NameKeyRuleTest.check("free", "personNameKey".equals(NameKeyRule.attributeName(Collections.<String>emptySet())));
		NameKeyRuleTest.check("taken once", "personNameKey2".equals(NameKeyRule.attributeName(new LinkedHashSet<String>(Arrays.asList("personNameKey")))));
		NameKeyRuleTest.check("taken twice", "personNameKey3".equals(NameKeyRule.attributeName(new LinkedHashSet<String>(Arrays.asList("personNameKey", "personNameKey2")))));
		final CapabilityLlmPass.FunctionRef function = NameKeyRuleTest.sortedKeyOf("string", "string", "string");
		final CapabilityLlmPass.ProposedScriptDerivation proposal = NameKeyRule.propose(function, new NameKeyRule.NameParts("p1", "p2"),
			NameKeyRule.candidates(NameKeyRuleTest.twoTextAttributes(), Collections.<String>emptySet()), new LinkedHashSet<String>(Arrays.asList("personNameKey")), " ", "r");
		NameKeyRuleTest.check("the proposal avoids it; a blank label falls back to the name: " + proposal.newAttributeName,
			"personNameKey2".equals(proposal.newAttributeName) && "personNameKey2".equals(proposal.newAttributeLabel));
	}

	private static void theStoredBindingIsReadByTheSameParserAsTheModelsOwn()
	{
		final CapabilityLlmPass.ProposedScriptDerivation proposal = NameKeyRule.propose(NameKeyRuleTest.sortedKeyOf("string", "string", "string"), new NameKeyRule.NameParts("p1", "p2"),
			NameKeyRule.candidates(NameKeyRuleTest.twoTextAttributes(), Collections.<String>emptySet()), Collections.<String>emptySet(), "l", "r");
		final Map<String, ConstraintBodyBuilder.BindingEntry> parsed = ConstraintBodyBuilder.parseBinding(proposal.bindingJson);
		NameKeyRuleTest.check("two attribute entries", parsed.size() == 2);
		NameKeyRuleTest.check("the bound attributes in order: " + ConstraintBodyBuilder.attributeBoundNames(parsed),
			ConstraintBodyBuilder.attributeBoundNames(parsed).equals(Arrays.asList("p1", "p2")));
		NameKeyRuleTest.check("nothing left to provide", ConstraintBodyBuilder.toBeProvidedParameters(parsed).isEmpty());
	}

	private static void theMeasuredCoverageDecidesTheStoredConfidenceLikeAnyKey()
	{
		final CapabilityLlmPass.ProposedScriptDerivation proposal = NameKeyRule.propose(NameKeyRuleTest.sortedKeyOf("string", "string", "string"), new NameKeyRule.NameParts("p1", "p2"),
			NameKeyRule.candidates(NameKeyRuleTest.twoTextAttributes(), Collections.<String>emptySet()), Collections.<String>emptySet(), "l", "r");
		final Set<String> systems = new LinkedHashSet<String>(Arrays.asList("A", "B"));
		final List<DerivationEvidence.SourceFill> fills = Arrays.asList(new DerivationEvidence.SourceFill("p1", "A", 10, 10), new DerivationEvidence.SourceFill("p2", "A", 10, 9),
			new DerivationEvidence.SourceFill("p1", "B", 10, 10), new DerivationEvidence.SourceFill("p2", "B", 10, 10));
		final DerivationEvidence.Decision covered = DerivationEvidence.decide(proposal.confidence, proposal.crossSystemKey, proposal.boundAttributes, systems, fills);
		NameKeyRuleTest.check("filled on every system: the factory's measured level " + covered.confidence, covered.confidence == DerivationEvidence.MEASURED_CROSS_SYSTEM_KEY_CONFIDENCE);
		final DerivationEvidence.Decision gap = DerivationEvidence.decide(proposal.confidence, proposal.crossSystemKey, proposal.boundAttributes, systems,
			Arrays.asList(new DerivationEvidence.SourceFill("p1", "A", 10, 10), new DerivationEvidence.SourceFill("p2", "A", 10, 10), new DerivationEvidence.SourceFill("p1", "B", 10, 10)));
		NameKeyRuleTest.check("a system without the last name: 70 stands " + gap.confidence, gap.confidence == 70);
	}

	private static void theBundleSentencesExistInBothLanguages()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final String label = WizardMessages.get(locale, "capability.nameKey.label");
			NameKeyRuleTest.check("label " + locale + ": " + label, !label.isEmpty() && !label.startsWith("capability."));
			final String rationale = WizardMessages.get(locale, "capability.nameKey.rationale", "p1", "p2", "Fasttrack.lib.names.sortedKeyOf");
			NameKeyRuleTest.check("rationale " + locale + " says the two attributes and the function: " + rationale,
				rationale.contains("p1") && rationale.contains("p2") && rationale.contains("Fasttrack.lib.names.sortedKeyOf") && !rationale.startsWith("capability."));
			NameKeyRuleTest.check("no unresolved placeholder " + locale, !rationale.contains("{"));
		}
		NameKeyRuleTest.check("French is not the English sentence", !WizardMessages.get(Locale.FRENCH, "capability.nameKey.label").equals(WizardMessages.get(Locale.ENGLISH, "capability.nameKey.label")));
	}

	private static boolean check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
		return true;
	}
}
