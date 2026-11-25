package com.on.ebx.factory.wizard;

import com.on.ebx.factory.review.ReviewLine;

/** JUnit-free: run the main, a failure throws. UX lot U3: step 4's one set of counts ({@link Step4Counts}). */
public final class Step4CountsTest
{
	public static void main(final String[] pArgs)
	{
		Step4CountsTest.everyNatureCountedOnceInTheBucketOfItsStatus();
		Step4CountsTest.technicalKeysAndSupersededObjectsCountNowhere();
		Step4CountsTest.aDecisionMovesTheNumbersAtOnce();
		Step4CountsTest.checksGatherConstraintsDerivationsAndServices();
		Step4CountsTest.emptySnapshotCountsNothing();
		System.out.println("Step4CountsTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static WizardSnapshot.AttributeInfo attribute(final String pName, final String pStatus, final boolean pPrimaryKey, final String... pMappingStatuses)
	{
		final WizardSnapshot.AttributeInfo attribute = new WizardSnapshot.AttributeInfo();
		attribute.id = pName;
		attribute.name = pName;
		attribute.status = pStatus;
		attribute.primaryKey = pPrimaryKey;
		for (final String status : pMappingStatuses)
		{
			final WizardSnapshot.MappingInfo mapping = new WizardSnapshot.MappingInfo();
			mapping.id = pName + status;
			mapping.status = status;
			attribute.mappings.add(mapping);
		}
		return attribute;
	}

	private static void line(final WizardSnapshot.StagingEntityInfo pEntity, final String pNature, final String pStatus)
	{
		pEntity.proposalLines.add(new WizardSnapshot.ProposalLineInfo(new ReviewLine(pNature, "r" + pEntity.proposalLines.size(), "x", "p", "RULE", Integer.valueOf(80), "", pStatus,
			false, false)));
	}

	private static WizardSnapshot.StagingEntityInfo entity(final WizardSnapshot pSnapshot, final String pName, final String pStatus)
	{
		final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
		entity.id = pName;
		entity.name = pName;
		entity.status = pStatus;
		pSnapshot.stagingEntities.add(entity);
		return entity;
	}

	/** The shape of project UxCrmSf, scaled down: fields, links, steps, rules and checks in every status. */
	private static WizardSnapshot sample()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.StagingEntityInfo person = Step4CountsTest.entity(snapshot, "Person", "ACCEPTED");
		person.attributes.add(Step4CountsTest.attribute("sourceSystem", "ACCEPTED", true, "ACCEPTED"));
		person.attributes.add(Step4CountsTest.attribute("name", "ACCEPTED", false, "ACCEPTED", "ACCEPTED"));
		person.attributes.add(Step4CountsTest.attribute("email", "PROPOSED", false, "PROPOSED"));
		person.attributes.add(Step4CountsTest.attribute("fax", "REJECTED", false, "REJECTED"));
		Step4CountsTest.line(person, ReviewLine.MAPPING_STEP, "PROPOSED");
		Step4CountsTest.line(person, ReviewLine.MAPPING_STEP, "ACCEPTED");
		Step4CountsTest.line(person, ReviewLine.QUALITY, "APPLIED");
		Step4CountsTest.line(person, ReviewLine.QUALITY, "PROPOSED");
		Step4CountsTest.line(person, ReviewLine.SCRIPT_CONSTRAINT, "ACCEPTED");
		Step4CountsTest.line(person, ReviewLine.SCRIPT_CONSTRAINT, "SET_ASIDE");
		return snapshot;
	}

	private static void everyNatureCountedOnceInTheBucketOfItsStatus()
	{
		final Step4Counts counts = Step4Counts.of(Step4CountsTest.sample());
		Step4CountsTest.check(counts.businessObjects.total() == 1 && counts.businessObjects.accepted == 1, "one accepted business object");
		// 3 fields besides the technical key: name accepted, email waiting, fax rejected
		Step4CountsTest.check(counts.fields.total() == 3 && counts.fields.accepted == 1 && counts.fields.waiting == 1 && counts.fields.rejected == 1, "fields");
		// links: every attribute's mappings, the technical key's included (it is a feed into the table): 1 + 2 + 1 + 1
		Step4CountsTest.check(counts.sourceLinks.total() == 5 && counts.sourceLinks.accepted == 3 && counts.sourceLinks.waiting == 1 && counts.sourceLinks.rejected == 1, "source links");
		Step4CountsTest.check(counts.cleaningSteps.waiting == 1 && counts.cleaningSteps.accepted == 1 && counts.cleaningSteps.total() == 2, "cleaning steps");
		Step4CountsTest.check(counts.qualityRules.accepted == 1 && counts.qualityRules.waiting == 1, "APPLIED counts as accepted");
		Step4CountsTest.check(counts.checks.accepted == 1 && counts.checks.setAside == 1 && counts.checks.total() == 2, "checks, one set aside");
		Step4CountsTest.check(counts.waiting() == counts.businessObjects.waiting + counts.fields.waiting + counts.sourceLinks.waiting + counts.cleaningSteps.waiting + counts.qualityRules.waiting + counts.checks.waiting && counts.waiting() == 4, "the one waiting number is the sum of the natures: " + counts.waiting());
		// a total can never disagree with its parts
		for (final String key : counts.keys())
		{
			final Step4Counts.Row row = counts.row(key);
			Step4CountsTest.check(row.total() == row.waiting + row.accepted + row.rejected + row.setAside, "total = parts for " + key);
		}
	}

	private static void technicalKeysAndSupersededObjectsCountNowhere()
	{
		final WizardSnapshot snapshot = Step4CountsTest.sample();
		final WizardSnapshot.StagingEntityInfo absorbed = Step4CountsTest.entity(snapshot, "PersonSap", ReviewLine.SUPERSEDED);
		absorbed.attributes.add(Step4CountsTest.attribute("name", "PROPOSED", false, "PROPOSED", "PROPOSED"));
		Step4CountsTest.line(absorbed, ReviewLine.QUALITY, "PROPOSED");
		final Step4Counts counts = Step4Counts.of(snapshot);
		Step4CountsTest.check(counts.businessObjects.total() == 1, "the superseded object is hidden everywhere");
		Step4CountsTest.check(counts.fields.total() == 3 && counts.sourceLinks.total() == 5 && counts.qualityRules.total() == 2, "none of its rows count");
	}

	private static void aDecisionMovesTheNumbersAtOnce()
	{
		final WizardSnapshot snapshot = Step4CountsTest.sample();
		final Step4Counts before = Step4Counts.of(snapshot);
		// the person accepts the waiting field and its link, and sets the rule aside: the block that used to be fixed at the run follows
		snapshot.stagingEntities.get(0).attributes.get(2).status = "ACCEPTED";
		snapshot.stagingEntities.get(0).attributes.get(2).mappings.get(0).status = "ACCEPTED";
		final Step4Counts after = Step4Counts.of(snapshot);
		Step4CountsTest.check(before.fields.waiting == 1 && after.fields.waiting == 0 && after.fields.accepted == 2, "fields move from waiting to accepted");
		Step4CountsTest.check(after.sourceLinks.waiting == 0 && after.sourceLinks.accepted == 4, "so do the links");
		Step4CountsTest.check(after.fields.total() == before.fields.total() && after.sourceLinks.total() == before.sourceLinks.total(), "the totals do not move");
	}

	private static void checksGatherConstraintsDerivationsAndServices()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.StagingEntityInfo entity = Step4CountsTest.entity(snapshot, "Person", "ACCEPTED");
		Step4CountsTest.line(entity, ReviewLine.SCRIPT_CONSTRAINT, "PROPOSED");
		Step4CountsTest.line(entity, ReviewLine.SCRIPT_DERIVATION, "ACCEPTED");
		final WizardSnapshot.CapabilityInfo service = new WizardSnapshot.CapabilityInfo();
		service.status = "SET_ASIDE";
		entity.capabilities.add(service);
		final WizardSnapshot.CapabilityInfo waitingService = new WizardSnapshot.CapabilityInfo();
		waitingService.status = "PROPOSED";
		entity.capabilities.add(waitingService);
		final Step4Counts counts = Step4Counts.of(snapshot);
		// staging-screens lot (2026-10-17): the service still PROPOSED is an optional suggestion - listed in the nature, never waiting (blocking)
		Step4CountsTest.check(counts.checks.total() == 4 && counts.checks.waiting == 1 && counts.checks.optionalWaiting == 1 && counts.checks.accepted == 1 && counts.checks.setAside == 1,
			"checks and services are one nature, as on the 'decisions left' card; the open service is optional");
		Step4CountsTest.check(counts.waiting() == 1 && counts.optionalWaiting() == 1, "optional suggestions never count in the waiting figure that keeps the review open: " + counts.waiting());
	}

	private static void emptySnapshotCountsNothing()
	{
		final Step4Counts counts = Step4Counts.of(new WizardSnapshot());
		Step4CountsTest.check(counts.waiting() == 0 && counts.businessObjects.total() == 0, "empty");
		Step4CountsTest.check(Step4Counts.of(null).waiting() == 0, "null-safe");
	}
}
