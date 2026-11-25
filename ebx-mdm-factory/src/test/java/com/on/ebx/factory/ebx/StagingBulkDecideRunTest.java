package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.wizard.WizardSnapshot;

/** JUnit-free: run the main, a failure throws. Audit F-47: the bulk sweep that moved into a job - its order, its counts, its stop, its sentence. */
public final class StagingBulkDecideRunTest
{
	/** Records every decision; refuses the ids listed in {@code refuse}. */
	private static final class Recorder implements StagingBulkDecideRun.Decider
	{
		final List<String> calls = new ArrayList<String>();
		final List<String> refuse;
		final boolean applyAllowed;

		Recorder(final boolean pApplyAllowed, final String... pRefuse)
		{
			this.applyAllowed = pApplyAllowed;
			this.refuse = Arrays.asList(pRefuse);
		}

		@Override
		public boolean decide(final String pNature, final String pRowId, final String pTargetStatus)
		{
			this.calls.add(pNature + ":" + pRowId + ":" + pTargetStatus);
			return !this.refuse.contains(pRowId);
		}

		@Override
		public boolean applyAllowed(final String pZone)
		{
			return this.applyAllowed;
		}
	}

	private static WizardSnapshot.StagingEntityInfo entity(final String pId, final String pStatus, final int pConfidence)
	{
		final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
		entity.id = pId;
		entity.status = pStatus;
		entity.confidence = Integer.valueOf(pConfidence);
		return entity;
	}

	private static WizardSnapshot.AttributeInfo attribute(final String pId, final int pConfidence, final boolean pPrimaryKey, final String... pMappingIds)
	{
		final WizardSnapshot.AttributeInfo attribute = new WizardSnapshot.AttributeInfo();
		attribute.id = pId;
		attribute.confidence = Integer.valueOf(pConfidence);
		attribute.primaryKey = pPrimaryKey;
		for (final String mappingId : pMappingIds)
		{
			final WizardSnapshot.MappingInfo mapping = new WizardSnapshot.MappingInfo();
			mapping.id = mappingId;
			mapping.confidence = Integer.valueOf(90);
			attribute.mappings.add(mapping);
		}
		return attribute;
	}

	private static WizardSnapshot.ProposalLineInfo line(final String pNature, final String pId, final int pConfidence)
	{
		final WizardSnapshot.ProposalLineInfo info = new WizardSnapshot.ProposalLineInfo(
			new ReviewLine(pNature, pId, "el", "prop", "LLM", Integer.valueOf(pConfidence), "", ReviewLine.PROPOSED, false, false));
		info.zone = "STAGING";
		return info;
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.StagingEntityInfo first = StagingBulkDecideRunTest.entity("E1", "PROPOSED", 95);
		first.attributes.add(StagingBulkDecideRunTest.attribute("A0", 99, true));
		first.attributes.add(StagingBulkDecideRunTest.attribute("A1", 90, false, "M1", "M2"));
		first.attributes.add(StagingBulkDecideRunTest.attribute("A2", 50, false));
		first.proposalLines.add(StagingBulkDecideRunTest.line(ReviewLine.QUALITY, "Q1", 85));
		first.proposalLines.add(StagingBulkDecideRunTest.line(ReviewLine.SCRIPT_CONSTRAINT, "S1", 90));
		first.proposalLines.add(StagingBulkDecideRunTest.line(ReviewLine.MAPPING_STEP, "T1", 40));
		snapshot.stagingEntities.add(first);
		snapshot.stagingEntities.add(StagingBulkDecideRunTest.entity("E2", ReviewLine.SUPERSEDED, 99));
		final WizardSnapshot.StagingEntityInfo second = StagingBulkDecideRunTest.entity("E3", ReviewLine.ACCEPTED, 99);
		second.attributes.add(StagingBulkDecideRunTest.attribute("A3", 99, false));
		snapshot.stagingEntities.add(second);

		// whole project, threshold 80: entity, then each attribute followed by its mappings, then the lines; the technical key, the SUPERSEDED entity and the already
		// ACCEPTED entity are not decided (its attribute still is, as before); A2 (50) and T1 (40) are left out below the threshold; the accepted script constraint is queued for the apply
		final Recorder all = new Recorder(true, "Q1");
		final StagingBulkDecideRun.Outcome outcome = StagingBulkDecideRun.sweep(snapshot, true, WizardSnapshot.BulkFilter.wholeProject(), 80, all, null);
		StagingBulkDecideRunTest.check("order: " + all.calls, all.calls.equals(Arrays.asList("ENTITY:E1:ACCEPTED", "ATTRIBUTE:A1:ACCEPTED", "MAPPING:M1:ACCEPTED",
			"MAPPING:M2:ACCEPTED", "QUALITY:Q1:ACCEPTED", "SCRIPT_CONSTRAINT:S1:ACCEPTED", "ATTRIBUTE:A3:ACCEPTED")));
		StagingBulkDecideRunTest.check("counts: ok " + outcome.ok + " skipped " + outcome.skipped + " leftOut " + outcome.leftOut,
			outcome.ok == 6 && outcome.skipped == 1 && outcome.leftOut == 2 && outcome.lowestLeftOutConfidence == 40);
		StagingBulkDecideRunTest.check("the accepted script constraint is queued for the apply", outcome.scriptConstraintIdsToApply.equals(Arrays.asList("S1")) && outcome.deferredApply == 0);

		// a zone whose model does not exist yet: deferred, not queued
		final StagingBulkDecideRun.Outcome deferred = StagingBulkDecideRun.sweep(snapshot, true, WizardSnapshot.BulkFilter.wholeProject(), 80, new Recorder(false), null);
		StagingBulkDecideRunTest.check("deferred apply", deferred.scriptConstraintIdsToApply.isEmpty() && deferred.deferredApply == 1);

		// a reject never applies anything
		final StagingBulkDecideRun.Outcome rejected = StagingBulkDecideRun.sweep(snapshot, false, WizardSnapshot.BulkFilter.wholeProject(), 80, new Recorder(true), null);
		StagingBulkDecideRunTest.check("reject: nothing to apply", rejected.scriptConstraintIdsToApply.isEmpty() && rejected.deferredApply == 0);

		// one entity, natures restricted: only its proposal lines of those natures, no entity/attribute/mapping at all
		final Recorder quality = new Recorder(true);
		StagingBulkDecideRun.sweep(snapshot, true, new WizardSnapshot.BulkFilter("E1", new HashSet<String>(Arrays.asList(ReviewLine.MAPPING_STEP, ReviewLine.QUALITY)), false), 80, quality, null);
		StagingBulkDecideRunTest.check("quality block: " + quality.calls, quality.calls.equals(Arrays.asList("QUALITY:Q1:ACCEPTED")));

		// include below threshold: the rows left out are swept in
		final Recorder below = new Recorder(true);
		final StagingBulkDecideRun.Outcome belowOut = StagingBulkDecideRun.sweep(snapshot, true, new WizardSnapshot.BulkFilter("E1", null, true), 80, below, null);
		StagingBulkDecideRunTest.check("below the threshold included: " + below.calls, belowOut.leftOut == 0 && below.calls.contains("ATTRIBUTE:A2:ACCEPTED") && below.calls.contains("MAPPING_STEP:T1:ACCEPTED"));

		// a stop asked before the third decision: two decisions made, the sweep says so
		final Recorder stopped = new Recorder(true);
		final int[] asked = new int[1];
		final StagingBulkDecideRun.Outcome stoppedOut = StagingBulkDecideRun.sweep(snapshot, true, WizardSnapshot.BulkFilter.wholeProject(), 80, stopped, new java.util.function.BooleanSupplier()
		{
			@Override
			public boolean getAsBoolean()
			{
				return ++asked[0] > 2;
			}
		});
		StagingBulkDecideRunTest.check("stopped after two: " + stopped.calls, stoppedOut.stopped && stopped.calls.size() == 2 && stoppedOut.ok == 2);

		// the sentence
		StagingBulkDecideRunTest.check("summary: " + StagingBulkDecideRun.summary(outcome, true), StagingBulkDecideRun.summary(outcome, true)
			.equals("6 rows accepted, 1 skipped (a rule still refused them), 2 left out below the threshold (lowest: 40 %)."));
		StagingBulkDecideRunTest.check("summary stopped: " + StagingBulkDecideRun.summary(stoppedOut, false), StagingBulkDecideRun.summary(stoppedOut, false).startsWith("Stopped on request. 2 rows rejected"));
		final StagingBulkDecideRun.Outcome codeTables = new StagingBulkDecideRun.Outcome();
		codeTables.ok = 3;
		codeTables.skipped = 2;
		codeTables.codeTablesSkipped = 2;
		StagingBulkDecideRunTest.check("summary names the code tables left for a person (staging last points, 2026-10-17): " + StagingBulkDecideRun.summary(codeTables, true),
			StagingBulkDecideRun.summary(codeTables, true).equals("3 rows accepted, 2 skipped (a rule still refused them) (2 code tables still lack a target code for some codes: fill them in the Mapping matrix first)."));
		StagingBulkDecideRunTest.check("summary deferred", StagingBulkDecideRun.summary(deferred, true).contains("1 accepted script constraint will be applied automatically"));

		// lot M7 (2026-10-18, E2EMASTER20261017235630): a PROPOSED entity no source column feeds (ContactEmail: a technical key, a link to the parent, an e-mail,
		// not one mapping alive) is skipped whole by an ACCEPT whatever its confidence - entity, attributes, mappings - and the sentence names it; a REJECT sweeps it
		final WizardSnapshot snapshot2 = new WizardSnapshot();
		final WizardSnapshot.StagingEntityInfo fed = StagingBulkDecideRunTest.entity("E1", "PROPOSED", 95);
		fed.name = "Client";
		fed.attributes.add(StagingBulkDecideRunTest.attribute("A1", 90, false, "M1"));
		snapshot2.stagingEntities.add(fed);
		final WizardSnapshot.StagingEntityInfo unfed = StagingBulkDecideRunTest.entity("E4", "PROPOSED", 89);
		unfed.name = "ContactEmail";
		unfed.attributes.add(StagingBulkDecideRunTest.attribute("A5", 100, true));
		unfed.attributes.add(StagingBulkDecideRunTest.attribute("A6", 98, false));
		final WizardSnapshot.AttributeInfo rejectedOnly = StagingBulkDecideRunTest.attribute("A7", 96, false, "M7");
		rejectedOnly.mappings.get(0).status = ReviewLine.REJECTED;
		unfed.attributes.add(rejectedOnly);
		unfed.proposalLines.add(StagingBulkDecideRunTest.line(ReviewLine.QUALITY, "Q4", 85));
		snapshot2.stagingEntities.add(unfed);
		final Recorder noFeed = new Recorder(true);
		final StagingBulkDecideRun.Outcome noFeedOut = StagingBulkDecideRun.sweep(snapshot2, true, WizardSnapshot.BulkFilter.wholeProject(), 60, noFeed, null);
		StagingBulkDecideRunTest.check("the unfed entity, its attributes, mappings and lines are not decided: " + noFeed.calls,
			noFeed.calls.equals(Arrays.asList("ENTITY:E1:ACCEPTED", "ATTRIBUTE:A1:ACCEPTED", "MAPPING:M1:ACCEPTED")));
		StagingBulkDecideRunTest.check("it is named, not counted as skipped by a rule nor as left out", noFeedOut.noFeedEntities.equals(Arrays.asList("ContactEmail"))
			&& noFeedOut.ok == 3 && noFeedOut.skipped == 0 && noFeedOut.leftOut == 0);
		StagingBulkDecideRunTest.check("the sentence says so: " + StagingBulkDecideRun.summary(noFeedOut, true),
			StagingBulkDecideRun.summary(noFeedOut, true).equals("3 rows accepted, 0 skipped (a rule still refused them), ContactEmail skipped: no source column feeds it."));
		StagingBulkDecideRunTest.check("hasFeed reads the live mappings only", StagingBulkDecideRun.hasFeed(fed) && !StagingBulkDecideRun.hasFeed(unfed));
		final Recorder rejectAll = new Recorder(true);
		final StagingBulkDecideRun.Outcome rejectOut = StagingBulkDecideRun.sweep(snapshot2, false, WizardSnapshot.BulkFilter.wholeProject(), 60, rejectAll, null);
		StagingBulkDecideRunTest.check("a reject sweeps it as before: " + rejectAll.calls, rejectAll.calls.contains("ENTITY:E4:REJECTED") && rejectOut.noFeedEntities.isEmpty());
		final StagingBulkDecideRun.Outcome two = new StagingBulkDecideRun.Outcome();
		two.ok = 1;
		two.noFeedEntities.add("ContactEmail");
		two.noFeedEntities.add("Loyalty");
		StagingBulkDecideRunTest.check("two entities: them", StagingBulkDecideRun.summary(two, true).endsWith(", ContactEmail, Loyalty skipped: no source column feeds them."));

		// the stored natures
		final ObjectMapper mapper = new ObjectMapper();
		StagingBulkDecideRunTest.check("no natures = every nature", StagingBulkDecideRun.naturesOf(mapper.readTree("{\"accept\":true}")) == null);
		StagingBulkDecideRunTest.check("natures read back", StagingBulkDecideRun.naturesOf(mapper.readTree("{\"natures\":[\"QUALITY\",\"MAPPING_STEP\"]}"))
			.equals(new HashSet<String>(Arrays.asList("QUALITY", "MAPPING_STEP"))));
		System.out.println("StagingBulkDecideRunTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
