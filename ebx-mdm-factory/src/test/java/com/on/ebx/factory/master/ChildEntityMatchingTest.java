package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.review.ReviewRules;

/**
 * JUnit-free: run the main, a failure throws. Lot M2 (2026-10-17, docs/MATCHING.md section 57): who is a child entity (and so follows its parent), the refusals of a child's own
 * plan (a MATCH leaf; a business object member table), and the wording. The scenario is the measured one: a person with an address and a phone, a purchase that references nothing.
 */
public final class ChildEntityMatchingTest
{
	public static void main(final String[] pArgs)
	{
		ChildEntityMatchingTest.addressAndPhoneFollowThePerson();
		ChildEntityMatchingTest.aSelfReferenceIsNotAChild();
		ChildEntityMatchingTest.twoEntitiesThatReferenceEachOtherKeepTheirOwnPlans();
		ChildEntityMatchingTest.parentsFromRawReferencesFollowTheCanonicalEntity();
		ChildEntityMatchingTest.aMatchLeafIsFoundInAFlatPlanAndInATierPlan();
		ChildEntityMatchingTest.aChildPlanWithAMatchLeafIsRefusedWithTheReason();
		ChildEntityMatchingTest.aSuspectOnlyChildPlanPassesTheLeafRule();
		ChildEntityMatchingTest.aBusinessObjectMemberChildIsRefusedAtConfigure();
		ChildEntityMatchingTest.reviewRuleRefusesOnlyAChildWithAMatchLeaf();
		ChildEntityMatchingTest.wordingNamesTheParent();
		ChildEntityMatchingTest.aMemberIsNeverRunAlone();
		System.out.println("ChildEntityMatchingTest OK");
	}

	/** Lot M4 (2026-10-18): the run leaves a child entity (a member of its parent's business object) to the run of the parent, whatever the status of its plan. */
	private static void aMemberIsNeverRunAlone()
	{
		final MasterModel.Result model = ChildEntityMatchingTest.personModel();
		ChildEntityMatchingTest.check("Adresse and TelephoneClient are not run alone", !ChildEntityMatching.runsAlone(ChildEntityMatching.parentsByChild(model).get("Adresse"))
			&& !ChildEntityMatching.runsAlone(ChildEntityMatching.parentsByChild(model).get("TelephoneClient")));
		ChildEntityMatchingTest.check("the person (a parent) is run", ChildEntityMatching.runsAlone(ChildEntityMatching.parentsByChild(model).get("Personne")));
		ChildEntityMatchingTest.check("an entity with no parent (Transaction) is run", ChildEntityMatching.runsAlone(ChildEntityMatching.parentsByChild(model).get("Transaction")));
		ChildEntityMatchingTest.check("an empty parent list is run", ChildEntityMatching.runsAlone(Collections.<String>emptyList()));
		ChildEntityMatchingTest.check("the rule follows the defect switch: with the add-on fixed, a member is run again", ChildEntityMatching.MEMBER_MERGE_DEFECT_PRESENT);
	}

	private static MasterModel.AttributeInfo technical(final String pName)
	{
		return new MasterModel.AttributeInfo(pName, pName, "STRING", Integer.valueOf(40), true, null, "ACCEPTED");
	}

	private static MasterModel.AttributeInfo attribute(final String pName, final String pReference)
	{
		return new MasterModel.AttributeInfo(pName, pName, "STRING", Integer.valueOf(80), false, pReference, "ACCEPTED");
	}

	private static MasterModel.EntityInfo entity(final String pName, final MasterModel.AttributeInfo... pAttributes)
	{
		final List<MasterModel.AttributeInfo> attributes = new ArrayList<MasterModel.AttributeInfo>(Arrays.asList(technical("sourceSystem"), technical("sourceId")));
		attributes.addAll(Arrays.asList(pAttributes));
		return new MasterModel.EntityInfo(pName, pName, "ACCEPTED", attributes, Collections.<String>emptyList());
	}

	private static MasterModel.Result personModel()
	{
		return MasterModel.build(Arrays.asList(
			ChildEntityMatchingTest.entity("Personne", ChildEntityMatchingTest.attribute("lastName", null)),
			ChildEntityMatchingTest.entity("Adresse", ChildEntityMatchingTest.attribute("street", null), ChildEntityMatchingTest.attribute("personneRef", "Personne")),
			ChildEntityMatchingTest.entity("TelephoneClient", ChildEntityMatchingTest.attribute("number", null), ChildEntityMatchingTest.attribute("personneRef", "Personne")),
			ChildEntityMatchingTest.entity("Transaction", ChildEntityMatchingTest.attribute("ticket", null))));
	}

	private static void addressAndPhoneFollowThePerson()
	{
		final MasterModel.Result model = ChildEntityMatchingTest.personModel();
		final Map<String, List<String>> parents = ChildEntityMatching.parentsByChild(model);
		ChildEntityMatchingTest.check("Adresse follows Personne", Collections.singletonList("Personne").equals(parents.get("Adresse")));
		ChildEntityMatchingTest.check("the phone follows Personne", Collections.singletonList("Personne").equals(parents.get("TelephoneClient")));
		ChildEntityMatchingTest.check("the parent keeps its own plan", !parents.containsKey("Personne"));
		ChildEntityMatchingTest.check("an entity that references nothing keeps its own plan", !parents.containsKey("Transaction"));
		ChildEntityMatchingTest.check("follows() agrees", ChildEntityMatching.follows(model, "Adresse") && !ChildEntityMatching.follows(model, "Personne"));
		ChildEntityMatchingTest.check("parentsOf of a non-child is empty, never null", ChildEntityMatching.parentsOf(model, "Transaction").isEmpty());
	}

	private static void aSelfReferenceIsNotAChild()
	{
		final MasterModel.Result model = MasterModel.build(Arrays.asList(
			ChildEntityMatchingTest.entity("Party", ChildEntityMatchingTest.attribute("name", null), ChildEntityMatchingTest.attribute("parentRef", "Party"))));
		ChildEntityMatchingTest.check("a hierarchy (Party.parentRef -> Party) is no child relation", ChildEntityMatching.parentsByChild(model).isEmpty());
	}

	private static void twoEntitiesThatReferenceEachOtherKeepTheirOwnPlans()
	{
		final MasterModel.Result model = MasterModel.build(Arrays.asList(
			ChildEntityMatchingTest.entity("A", ChildEntityMatchingTest.attribute("name", null), ChildEntityMatchingTest.attribute("bRef", "B")),
			ChildEntityMatchingTest.entity("B", ChildEntityMatchingTest.attribute("name", null), ChildEntityMatchingTest.attribute("aRef", "A"))));
		ChildEntityMatchingTest.check("neither of two mutually referencing entities follows the other (nobody would be matched)", ChildEntityMatching.parentsByChild(model).isEmpty());
	}

	private static void parentsFromRawReferencesFollowTheCanonicalEntity()
	{
		final Map<String, List<String>> references = new LinkedHashMap<String, List<String>>();
		references.put("Contact", Arrays.asList("Account", "Ghost"));
		references.put("Company", Collections.<String>emptyList());
		final Map<String, String> canonical = Collections.singletonMap("Account", "Company");
		final Map<String, List<String>> parents = ChildEntityMatching.parentsByChild(new LinkedHashSet<String>(Arrays.asList("Contact", "Company")), references, canonical);
		ChildEntityMatchingTest.check("a reference to a member reads as its canonical entity, an unknown one is ignored", Collections.singletonList("Company").equals(parents.get("Contact")));
		ChildEntityMatchingTest.check("only the child is listed", parents.size() == 1);
	}

	private static final String FLAT_WITH_MATCH = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"street\",\"algorithm\":\"exact\"}],"
		+ "\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}]}}";
	private static final String FLAT_SUSPECT_ONLY = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"street\",\"algorithm\":\"exact\"}],"
		+ "\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}";
	private static final String TIERS_WITH_MATCH = "{\"tiers\":[{\"fields\":[{\"field\":\"street\",\"algorithm\":\"exact\"}],\"outcome\":\"MATCH\"}]}";

	private static void aMatchLeafIsFoundInAFlatPlanAndInATierPlan()
	{
		ChildEntityMatchingTest.check("a flat plan routing to MATCH", ChildEntityMatching.hasMatchLeaf(ChildEntityMatchingTest.FLAT_WITH_MATCH));
		ChildEntityMatchingTest.check("a flat plan with suspects only", !ChildEntityMatching.hasMatchLeaf(ChildEntityMatchingTest.FLAT_SUSPECT_ONLY));
		ChildEntityMatchingTest.check("a legacy tier plan with a MATCH outcome", ChildEntityMatching.hasMatchLeaf(ChildEntityMatchingTest.TIERS_WITH_MATCH));
		ChildEntityMatchingTest.check("blank, null and broken JSON are not a MATCH leaf (the guard never throws)",
			!ChildEntityMatching.hasMatchLeaf(null) && !ChildEntityMatching.hasMatchLeaf("  ") && !ChildEntityMatching.hasMatchLeaf("{not json"));
	}

	private static void aChildPlanWithAMatchLeafIsRefusedWithTheReason()
	{
		final String refusal = ChildEntityMatching.matchLeafRefusal("Adresse", Collections.singletonList("Personne"), ChildEntityMatchingTest.FLAT_WITH_MATCH);
		ChildEntityMatchingTest.check("refused", refusal != null);
		ChildEntityMatchingTest.check("the reason names the child, the parent, SUSPECTED and the add-on defect",
			refusal.contains("Adresse") && refusal.contains("Personne") && refusal.contains("SUSPECTED") && refusal.contains("CascadeMergingService"));
		ChildEntityMatchingTest.check("an entity that is not a child is never refused by this rule",
			ChildEntityMatching.matchLeafRefusal("Personne", Collections.<String>emptyList(), ChildEntityMatchingTest.FLAT_WITH_MATCH) == null);
	}

	private static void aSuspectOnlyChildPlanPassesTheLeafRule()
	{
		ChildEntityMatchingTest.check("SUSPECTED leaves only: allowed",
			ChildEntityMatching.matchLeafRefusal("Adresse", Collections.singletonList("Personne"), ChildEntityMatchingTest.FLAT_SUSPECT_ONLY) == null);
	}

	private static void aBusinessObjectMemberChildIsRefusedAtConfigure()
	{
		final List<String> parents = Collections.singletonList("Personne");
		final String refusal = ChildEntityMatching.memberRunRefusal("Adresse", parents, true, "PersonneBO");
		ChildEntityMatchingTest.check("a member of a business object is refused while the add-on defect is present",
			refusal != null && refusal.contains("PersonneBO") && refusal.contains("even when the policy has SUSPECTED leaves only"));
		ChildEntityMatchingTest.check("not a member: allowed", ChildEntityMatching.memberRunRefusal("Adresse", parents, false, null) == null);
		ChildEntityMatchingTest.check("not a child: allowed", ChildEntityMatching.memberRunRefusal("Personne", Collections.<String>emptyList(), true, "PersonneBO") == null);
		ChildEntityMatchingTest.check("the defect switch is on in this build", ChildEntityMatching.MEMBER_MERGE_DEFECT_PRESENT);
	}

	private static void reviewRuleRefusesOnlyAChildWithAMatchLeaf()
	{
		final List<String> parents = Collections.singletonList("Personne");
		final ReviewRules.Decision refused = ReviewRules.canAcceptMatchPlan("Adresse", parents, ChildEntityMatchingTest.FLAT_WITH_MATCH);
		ChildEntityMatchingTest.check("accept of a child plan with a MATCH leaf is refused, the reason is the sentence", !refused.allowed && refused.reason.contains("SUSPECTED"));
		ChildEntityMatchingTest.check("accept of a suspect-only child plan is allowed", ReviewRules.canAcceptMatchPlan("Adresse", parents, ChildEntityMatchingTest.FLAT_SUSPECT_ONLY).allowed);
		ChildEntityMatchingTest.check("accept of a parent plan with a MATCH leaf is allowed (unchanged)",
			ReviewRules.canAcceptMatchPlan("Personne", Collections.<String>emptyList(), ChildEntityMatchingTest.FLAT_WITH_MATCH).allowed);
	}

	private static void wordingNamesTheParent()
	{
		final List<String> parents = Arrays.asList("Personne", "Societe");
		ChildEntityMatchingTest.check("follows text", "Adresse follows Personne, Societe".equals(ChildEntityMatching.followsText("Adresse", parents)));
		ChildEntityMatchingTest.check("follows note says there is no plan of its own", ChildEntityMatching.followsNote("Adresse", parents).contains("no matching plan of its own"));
		ChildEntityMatchingTest.check("kept-as-is note says it is left and not removed",
			ChildEntityMatching.keptAsIsNote("Adresse", parents).contains("left as it is"));
		final String left = ChildEntityMatching.previewLeftNote("Personne", "Personne__preview", "the table belongs to a business object");
		ChildEntityMatchingTest.check("preview note names the policy, the reason and the way out",
			left.contains("Personne__preview") && left.contains("business object") && left.contains("administration screen"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
