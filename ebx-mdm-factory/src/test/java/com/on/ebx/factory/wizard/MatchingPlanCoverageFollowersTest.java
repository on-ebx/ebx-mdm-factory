package com.on.ebx.factory.wizard;

import java.util.List;
import java.util.Locale;

/**
 * JUnit-free: run the main, a failure throws. Lot M2 (2026-10-17, docs/MATCHING.md section 57): the wizard knows which accepted objects are children that follow their parent -
 * they are not "objects without a plan", their names are in the job messages, and a plan row an earlier pass wrote keeps its own card.
 */
public final class MatchingPlanCoverageFollowersTest
{
	public static void main(final String[] pArgs)
	{
		MatchingPlanCoverageFollowersTest.aChildIsNotACandidateForAPlan();
		MatchingPlanCoverageFollowersTest.aReferenceToAMemberReadsAsItsCanonicalEntity();
		MatchingPlanCoverageFollowersTest.anUnacceptedReferenceMakesNoChild();
		MatchingPlanCoverageFollowersTest.followersAreNamedInTheMessageUnlessTheyHaveAPlanRow();
		System.out.println("MatchingPlanCoverageFollowersTest OK");
	}

	private static WizardSnapshot.StagingEntityInfo entity(final WizardSnapshot pSnapshot, final String pName, final String pReference, final String pAttributeStatus)
	{
		final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
		entity.name = pName;
		entity.status = "ACCEPTED";
		if (pReference != null)
		{
			final WizardSnapshot.AttributeInfo attribute = new WizardSnapshot.AttributeInfo();
			attribute.name = "personneRef";
			attribute.referenceEntityName = pReference;
			attribute.status = pAttributeStatus;
			entity.attributes.add(attribute);
		}
		pSnapshot.stagingEntities.add(entity);
		return entity;
	}

	private static WizardSnapshot person()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		MatchingPlanCoverageFollowersTest.entity(snapshot, "Personne", null, null);
		MatchingPlanCoverageFollowersTest.entity(snapshot, "Adresse", "Personne", "ACCEPTED");
		MatchingPlanCoverageFollowersTest.entity(snapshot, "Transaction", null, null);
		return snapshot;
	}

	private static void aChildIsNotACandidateForAPlan()
	{
		final WizardSnapshot snapshot = MatchingPlanCoverageFollowersTest.person();
		MatchingPlanCoverageFollowersTest.check("the candidates are the parent and the standalone entity", java.util.Arrays.asList("Personne", "Transaction").equals(MatchingPlanCoverage.candidates(snapshot)));
		final List<MatchingPlanCoverage.Follower> followers = MatchingPlanCoverage.followers(snapshot);
		MatchingPlanCoverageFollowersTest.check("Adresse follows Personne", followers.size() == 1 && "Adresse".equals(followers.get(0).entity) && followers.get(0).parents.contains("Personne"));
		MatchingPlanCoverageFollowersTest.check("no gap is raised for the child once the rules ran",
			MatchingPlanCoverage.names(MatchingPlanCoverage.gaps(MatchingPlanCoverage.candidates(snapshot), MatchingPlanCoverage.withPlan(snapshot), false, null, null)).equals(
				java.util.Arrays.asList("Personne", "Transaction")));
	}

	private static void aReferenceToAMemberReadsAsItsCanonicalEntity()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		MatchingPlanCoverageFollowersTest.entity(snapshot, "Company", null, null);
		MatchingPlanCoverageFollowersTest.entity(snapshot, "Account", null, null).supersededBy = "Company";
		MatchingPlanCoverageFollowersTest.entity(snapshot, "Contact", "Account", "ACCEPTED");
		final List<MatchingPlanCoverage.Follower> followers = MatchingPlanCoverage.followers(snapshot);
		MatchingPlanCoverageFollowersTest.check("Contact follows Company (Account is a member of it, never promoted)", followers.size() == 1 && "Contact".equals(followers.get(0).entity)
			&& followers.get(0).parents.contains("Company"));
		MatchingPlanCoverageFollowersTest.check("the member itself is no candidate", !MatchingPlanCoverage.candidates(snapshot).contains("Account"));
	}

	private static void anUnacceptedReferenceMakesNoChild()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		MatchingPlanCoverageFollowersTest.entity(snapshot, "Personne", null, null);
		MatchingPlanCoverageFollowersTest.entity(snapshot, "Adresse", "Personne", "REJECTED");
		MatchingPlanCoverageFollowersTest.check("a rejected reference attribute is no foreign key: Adresse keeps its own plan", MatchingPlanCoverage.followers(snapshot).isEmpty()
			&& MatchingPlanCoverage.candidates(snapshot).contains("Adresse"));
	}

	private static void followersAreNamedInTheMessageUnlessTheyHaveAPlanRow()
	{
		final WizardSnapshot snapshot = MatchingPlanCoverageFollowersTest.person();
		final String message = MatchingPlanCoverage.withFollows(Locale.ENGLISH, "2 matching plans written.", MatchingPlanCoverage.followers(snapshot), MatchingPlanCoverage.withPlan(snapshot));
		MatchingPlanCoverageFollowersTest.check("the sentence names the child and its parent: " + message,
			message.startsWith("2 matching plans written.") && message.contains("Adresse follows Personne") && message.contains("no matching plan of its own"));
		final WizardSnapshot.MatchPlanInfo plan = new WizardSnapshot.MatchPlanInfo();
		plan.entityName = "Adresse";
		snapshot.matchPlans.add(plan);
		MatchingPlanCoverageFollowersTest.check("a child that has a plan row (an earlier pass wrote it) is not announced as plan-less",
			"x".equals(MatchingPlanCoverage.withFollows(Locale.ENGLISH, "x", MatchingPlanCoverage.followers(snapshot), MatchingPlanCoverage.withPlan(snapshot))));
		MatchingPlanCoverageFollowersTest.check("French wording exists", MatchingPlanCoverage.withFollows(Locale.FRENCH, "", MatchingPlanCoverage.followers(MatchingPlanCoverageFollowersTest.person()),
			java.util.Collections.<String>emptySet()).contains("suit Personne"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
