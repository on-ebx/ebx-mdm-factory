package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.on.ebx.factory.quality.ChildKeysDerivation;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Lot M8b, 2026-10-04. */
public final class ChildKeysProposalRuleTest
{
	public static void main(final String[] pArgs)
	{
		ChildKeysProposalRuleTest.acceptedProposalCarriesTheDefinitionAndTheReference();
		ChildKeysProposalRuleTest.sameChecksAsTheLoadTimeResolution();
		ChildKeysProposalRuleTest.partsThatCannotIdentifyAChildRecord();
		ChildKeysProposalRuleTest.argumentJsonIsWhatTheDerivationParses();
		System.out.println("ChildKeysProposalRuleTest OK");
	}

	private static StagingModel.AttributeInfo attribute(final String pName, final String pReferenceEntity, final boolean pPrimaryKey)
	{
		return new StagingModel.AttributeInfo(pName, pName, "STRING", Integer.valueOf(50), false, pPrimaryKey, pReferenceEntity, "PROPOSED");
	}

	private static StagingModel.EntityInfo entity(final String pName, final StagingModel.AttributeInfo... pAttributes)
	{
		return new StagingModel.EntityInfo(pName, pName, "PROPOSED", new ArrayList<StagingModel.AttributeInfo>(Arrays.asList(pAttributes)), null);
	}

	private static List<StagingModel.EntityInfo> personAddress()
	{
		return Arrays.asList(
			ChildKeysProposalRuleTest.entity("Personne", ChildKeysProposalRuleTest.attribute("sourceSystem", null, true), ChildKeysProposalRuleTest.attribute("firstName", null, false)),
			ChildKeysProposalRuleTest.entity("Adresse", ChildKeysProposalRuleTest.attribute("sourceId", null, true), ChildKeysProposalRuleTest.attribute("zip", null, false),
				ChildKeysProposalRuleTest.attribute("street", null, false), ChildKeysProposalRuleTest.attribute("personne", "Personne", false)));
	}

	private static void acceptedProposalCarriesTheDefinitionAndTheReference()
	{
		final ChildKeysProposalRule.Check check = ChildKeysProposalRule.check("Personne", "addressKeys", "Adresse", Arrays.asList("zip", "street"),
			ChildKeysProposalRuleTest.personAddress());
		ChildKeysProposalRuleTest.check("accepted", check.accepted() && check.refusal == null);
		ChildKeysProposalRuleTest.check("reference attribute resolved", "personne".equals(check.proposal.referenceAttribute));
		ChildKeysProposalRuleTest.check("fields in the model's order", check.proposal.fields.equals(Arrays.asList("zip", "street")));
		ChildKeysProposalRuleTest.check("argument", "{\"child\":\"Adresse\",\"fields\":[\"zip\",\"street\"]}".equals(check.proposal.argument));
	}

	private static void sameChecksAsTheLoadTimeResolution()
	{
		final List<StagingModel.EntityInfo> entities = ChildKeysProposalRuleTest.personAddress();
		ChildKeysProposalRuleTest.check("unknown child", ChildKeysProposalRule.check("Personne", "k", "Nope", Arrays.asList("zip"), entities).refusal.contains("does not exist"));
		ChildKeysProposalRuleTest.check("unknown field", ChildKeysProposalRule.check("Personne", "k", "Adresse", Arrays.asList("nope"), entities).refusal
			.contains("'nope' is not an attribute of Adresse"));
		ChildKeysProposalRuleTest.check("no field", ChildKeysProposalRule.check("Personne", "k", "Adresse", new ArrayList<String>(), entities).refusal.contains("non-empty array"));
		ChildKeysProposalRuleTest.check("a field twice", ChildKeysProposalRule.check("Personne", "k", "Adresse", Arrays.asList("zip", "zip"), entities).refusal.contains("twice"));
		ChildKeysProposalRuleTest.check("the child must refer to the parent", ChildKeysProposalRule.check("Adresse", "k", "Personne", Arrays.asList("firstName"), entities).refusal
			.contains("has no attribute that refers to Adresse"));
		final List<StagingModel.EntityInfo> two = Arrays.asList(entities.get(0),
			ChildKeysProposalRuleTest.entity("Adresse", ChildKeysProposalRuleTest.attribute("zip", null, false), ChildKeysProposalRuleTest.attribute("owner", "Personne", false),
				ChildKeysProposalRuleTest.attribute("billedTo", "Personne", false)));
		final String ambiguous = ChildKeysProposalRule.check("Personne", "k", "Adresse", Arrays.asList("zip"), two).refusal;
		ChildKeysProposalRuleTest.check("two references to the parent are ambiguous: " + ambiguous, ambiguous != null && ambiguous.contains("2 attributes that refer to Personne"));
	}

	private static void partsThatCannotIdentifyAChildRecord()
	{
		final List<StagingModel.EntityInfo> entities = ChildKeysProposalRuleTest.personAddress();
		ChildKeysProposalRuleTest.check("the technical identity", ChildKeysProposalRule.check("Personne", "k", "Adresse", Arrays.asList("sourceId"), entities).refusal
			.contains("technical identity"));
		ChildKeysProposalRuleTest.check("the link to the parent", ChildKeysProposalRule.check("Personne", "k", "Adresse", Arrays.asList("zip", "personne"), entities).refusal
			.contains("reference to another entity"));
		ChildKeysProposalRuleTest.check("a name already on the parent", ChildKeysProposalRule.check("Personne", "FIRSTNAME", "Adresse", Arrays.asList("zip"), entities).refusal
			.contains("already has an attribute"));
		ChildKeysProposalRuleTest.check("a bad name", ChildKeysProposalRule.check("Personne", "a b", "Adresse", Arrays.asList("zip"), entities).refusal.contains("'name' must start"));
		ChildKeysProposalRuleTest.check("an unknown parent", ChildKeysProposalRule.check("Nobody", "k", "Adresse", Arrays.asList("zip"), entities).refusal.contains("unknown entity"));
		ChildKeysProposalRuleTest.check("the parent as its own child", ChildKeysProposalRule.check("Personne", "k", "Personne", Arrays.asList("firstName"), entities).refusal
			.contains("not the entity that carries"));
	}

	private static void argumentJsonIsWhatTheDerivationParses()
	{
		final ChildKeysDerivation.Definition definition = ChildKeysDerivation.parse(ChildKeysProposalRule.argumentJson("Adresse", Arrays.asList("zip", "houseNumber", "street")));
		ChildKeysProposalRuleTest.check("child and fields", "Adresse".equals(definition.childEntity) && definition.fields.equals(Arrays.asList("zip", "houseNumber", "street")));
		ChildKeysProposalRuleTest.check("every field required, hash encoding, no cap", definition.required.size() == 3 && ChildKeysDerivation.ENCODING_HASH.equals(definition.encoding)
			&& definition.maxTokens == null && definition.reference == null);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
