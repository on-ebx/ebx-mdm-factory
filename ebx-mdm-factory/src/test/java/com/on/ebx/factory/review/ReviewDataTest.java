package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.List;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class ReviewDataTest
{
	public static void main(final String[] pArgs)
	{
		ReviewDataTest.defaultFilterKeepsOnlyProposed();
		ReviewDataTest.filterByNature();
		ReviewDataTest.filterByOriginAndMinConfidence();
		ReviewDataTest.allFilterMatchesEverything();
		ReviewDataTest.countersReadTheWholeSetNotTheFilteredOne();
		ReviewDataTest.bulkAcceptCandidatesOnlyProposedAboveThreshold();
		ReviewDataTest.bulkAcceptCandidatesSkipTechnicalKeyLines();
		ReviewDataTest.bulkAcceptCandidatesSkipModelWrittenScripts();
		ReviewDataTest.bulkAcceptCandidatesSkipAnEntityNoSourceColumnFeeds();

		ReviewDataTest.statusCountsExcludingFindsOtherMatchingStatuses();
		ReviewDataTest.statusCountsExcludingOmitsZeroCounts();
		ReviewDataTest.statusCountsExcludingOnAllFilterOffersNothingMore();
		ReviewDataTest.defaultStatusIsProposedWhenSomethingIsPending();
		ReviewDataTest.defaultStatusFallsBackToAllWhenNothingIsPending();

		ReviewDataTest.pendingFilterAlsoShowsAnAcceptedScriptConstraintNotYetApplied();
		ReviewDataTest.pendingFilterNeverAddsAnAcceptedLineOfAnyOtherNature();
		ReviewDataTest.explicitAcceptedFilterIsUnaffectedByThePendingRule();
		ReviewDataTest.unappliedConstraintCountCountsOnlyAcceptedScriptConstraintOverTheWholeProject();
		System.out.println("ReviewDataTest OK");
	}

	private static List<ReviewLine> demo()
	{
		final List<ReviewLine> out = new ArrayList<ReviewLine>();
		out.add(new ReviewLine(ReviewLine.RELATION, "r1", "CONTACT.ACCOUNT_ID", "-> ACCOUNT", "RULE", Integer.valueOf(80), "why", ReviewLine.ACCEPTED, false, false));
		out.add(new ReviewLine(ReviewLine.ENTITY, "e1", "Supplier", "New entity", "LLM", Integer.valueOf(84), "why", ReviewLine.PROPOSED, false, false));
		out.add(new ReviewLine(ReviewLine.ENTITY, "e2", "Account", "New entity", "RULE", Integer.valueOf(96), "why", ReviewLine.PROPOSED, false, false));
		out.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", "STRING", "LLM", Integer.valueOf(60), "why", ReviewLine.PROPOSED, false, false));
		out.add(new ReviewLine(ReviewLine.MAPPING, "m1", "SUPPLIER.NAME1 → Account.parentAccountId", "TRIM", "RULE", Integer.valueOf(91), "why", ReviewLine.REJECTED, false, false));
		out.add(new ReviewLine(ReviewLine.MATCH, "p1", "Account", "plan", "USER", null, "", ReviewLine.APPLIED, false, false));
		return out;
	}

	private static void defaultFilterKeepsOnlyProposed()
	{
		final List<ReviewLine> visible = ReviewData.filter(ReviewDataTest.demo(), ReviewFilter.defaultFilter());
		ReviewDataTest.check("default filter: PROPOSED only -> 3 lines (e1, e2, a1)", visible.size() == 3);
		for (final ReviewLine line : visible)
		{
			ReviewDataTest.check("every visible line is PROPOSED", ReviewLine.PROPOSED.equals(line.status));
		}
	}

	private static void filterByNature()
	{
		final ReviewFilter filter = new ReviewFilter(ReviewLine.ENTITY, ReviewFilter.ALL, ReviewFilter.ALL, 0);
		final List<ReviewLine> visible = ReviewData.filter(ReviewDataTest.demo(), filter);
		ReviewDataTest.check("nature=ENTITY, status=ALL -> both entities", visible.size() == 2);
	}

	private static void filterByOriginAndMinConfidence()
	{
		final ReviewFilter filter = new ReviewFilter(ReviewFilter.ALL, "LLM", ReviewFilter.ALL, 70);
		final List<ReviewLine> visible = ReviewData.filter(ReviewDataTest.demo(), filter);
		// LLM origin: e1 (84) and a1 (60); minConfidence 70 keeps only e1
		ReviewDataTest.check("origin=LLM and confidence >= 70 -> only e1", visible.size() == 1 && "e1".equals(visible.get(0).rowId));
	}

	private static void allFilterMatchesEverything()
	{
		final List<ReviewLine> demo = ReviewDataTest.demo();
		final ReviewFilter filter = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0);
		ReviewDataTest.check("ALL/ALL/ALL, no confidence floor -> every line", ReviewData.filter(demo, filter).size() == demo.size());
	}

	private static void countersReadTheWholeSetNotTheFilteredOne()
	{
		final ReviewData.Counters counters = ReviewData.counters(ReviewDataTest.demo());
		ReviewDataTest.check("total", counters.total == 6);
		ReviewDataTest.check("proposed", counters.proposed == 3);
		ReviewDataTest.check("accepted", counters.accepted == 1);
		ReviewDataTest.check("rejected", counters.rejected == 1);
		ReviewDataTest.check("applied", counters.applied == 1);
	}

	private static void bulkAcceptCandidatesOnlyProposedAboveThreshold()
	{
		final List<ReviewLine> visible = ReviewDataTest.demo(); // pretend everything is "shown"
		final List<ReviewLine> candidates = ReviewData.bulkAcceptCandidates(visible, 80);
		// only PROPOSED lines count: e1 (84, LLM) and e2 (96, RULE) qualify; a1 is PROPOSED but only 60
		ReviewDataTest.check("two candidates at or above 80%", candidates.size() == 2);
		ReviewDataTest.check("candidates are e1 and e2", "e1".equals(candidates.get(0).rowId) && "e2".equals(candidates.get(1).rowId));
	}

	/**
	 * A technical-key (primaryKey) line is never a bulk-accept candidate, however high its confidence: it has no decision of its own
	 * (coordinator review, 2026-09-20) - "accept everything above N%" must skip it, not silently count it as accepted.
	 */
	/** Lot M7 (2026-10-18): an entity whose attributes have no live mapping (none, or REJECTED only) is never swept in by a threshold, nor are its attributes; a fed entity is. */
	private static void bulkAcceptCandidatesSkipAnEntityNoSourceColumnFeeds()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e1", "ContactEmail", "New entity", "LLM", Integer.valueOf(89), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a1", "ContactEmail.sourceSystem", "STRING", "LLM", Integer.valueOf(100), "why", ReviewLine.PROPOSED, false, true));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a2", "ContactEmail.email", "STRING", "LLM", Integer.valueOf(96), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.MAPPING, "m0", "WEB.OLD \u2192 ContactEmail.email", "TRIM", "LLM", Integer.valueOf(90), "why", ReviewLine.REJECTED, false, false));
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e2", "Client", "New entity", "LLM", Integer.valueOf(91), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "a3", "Client.email", "STRING", "LLM", Integer.valueOf(95), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.MAPPING, "m1", "WEB.EMAIL \u2192 Client.email", "TRIM", "LLM", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e3", "Achat", "New entity", "LLM", Integer.valueOf(88), "why", ReviewLine.ACCEPTED, false, false));
		final java.util.Set<String> noFeed = ReviewData.entitiesWithoutFeed(lines);
		ReviewDataTest.check("the entity with no live mapping is the one named: " + noFeed, noFeed.size() == 1 && noFeed.contains("ContactEmail"));
		final List<ReviewLine> candidates = ReviewData.bulkAcceptCandidates(lines, 60, noFeed);
		final List<String> ids = new ArrayList<String>();
		for (final ReviewLine line : candidates)
		{
			ids.add(line.rowId);
		}
		ReviewDataTest.check("ContactEmail and its attributes are skipped, Client and its lines are swept in: " + ids, ids.equals(java.util.Arrays.asList("e2", "a3", "m1")));
		ReviewDataTest.check("the two-argument form is unchanged (it does not know the feeds)", ReviewData.bulkAcceptCandidates(lines, 60).size() == 5);
	}

	private static void bulkAcceptCandidatesSkipTechnicalKeyLines()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "tk1", "Account.sourceSystem", "STRING", "RULE", Integer.valueOf(100), "why", ReviewLine.PROPOSED, false, true));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "tk2", "Account.sourceId", "STRING", "RULE", Integer.valueOf(100), "why", ReviewLine.PROPOSED, false, true));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "biz1", "Account.name", "STRING", "RULE", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		final List<ReviewLine> candidates = ReviewData.bulkAcceptCandidates(lines, 80);
		ReviewDataTest.check("only the ordinary business attribute is a candidate, not the two technical keys (100% confidence)",
			candidates.size() == 1 && "biz1".equals(candidates.get(0).rowId));
	}

	/** Audit F-08: a script constraint or derivation is accepted one by one, whatever confidence the model declared for it. */
	private static void bulkAcceptCandidatesSkipModelWrittenScripts()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "sc1", "Account.iban", "isValid", "LLM", Integer.valueOf(100), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.SCRIPT_DERIVATION, "sd1", "Account.phoneE164", "toE164", "LLM", Integer.valueOf(100), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.ATTRIBUTE, "biz1", "Account.name", "STRING", "LLM", Integer.valueOf(90), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.EXTERNAL_SERVICE, "es1", "Account.vat", "vies", "LLM", Integer.valueOf(95), "why", ReviewLine.PROPOSED, false, false));
		final List<ReviewLine> candidates = ReviewData.bulkAcceptCandidates(lines, 0);
		ReviewDataTest.check("only the attribute and the external-service note are bulk candidates: " + candidates.size(),
			candidates.size() == 2 && "biz1".equals(candidates.get(0).rowId) && "es1".equals(candidates.get(1).rowId));
		ReviewDataTest.check("a script constraint needs one-by-one acceptance", ReviewData.needsOneByOneAcceptance(ReviewLine.SCRIPT_CONSTRAINT));
		ReviewDataTest.check("a script derivation needs one-by-one acceptance", ReviewData.needsOneByOneAcceptance(ReviewLine.SCRIPT_DERIVATION));
		ReviewDataTest.check("an attribute does not", !ReviewData.needsOneByOneAcceptance(ReviewLine.ATTRIBUTE));
	}

	// ------------------------------------------------------------------ task B: empty-state and default-status fixes

	/** The product report's own scenario: Status=Pending with nothing pending must still say how many lines match under other statuses. */
	private static void statusCountsExcludingFindsOtherMatchingStatuses()
	{
		final ReviewFilter filter = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.PROPOSED, 0);
		final List<ReviewData.StatusCount> counts = ReviewData.statusCountsExcluding(ReviewDataTest.demo(), filter);
		ReviewDataTest.check("ALL + the three other statuses that actually have a match: " + counts.size(), counts.size() == 4);
		ReviewDataTest.check("ALL is offered with the true total", ReviewDataTest.countOf(counts, ReviewFilter.ALL) == 6);
		ReviewDataTest.check("ACCEPTED has 1", ReviewDataTest.countOf(counts, ReviewLine.ACCEPTED) == 1);
		ReviewDataTest.check("REJECTED has 1", ReviewDataTest.countOf(counts, ReviewLine.REJECTED) == 1);
		ReviewDataTest.check("APPLIED has 1", ReviewDataTest.countOf(counts, ReviewLine.APPLIED) == 1);
	}

	/** A status with zero matches (even under the narrower nature/origin scope) must never be offered as a dead-end link. */
	private static void statusCountsExcludingOmitsZeroCounts()
	{
		final ReviewFilter filter = new ReviewFilter(ReviewLine.MATCH, ReviewFilter.ALL, ReviewLine.PROPOSED, 0);
		final List<ReviewData.StatusCount> counts = ReviewData.statusCountsExcluding(ReviewDataTest.demo(), filter);
		// only p1 is MATCH nature, and it is APPLIED: ACCEPTED/REJECTED must never appear (0 matches)
		ReviewDataTest.check("only ALL and APPLIED have a real match: " + counts.size(), counts.size() == 2);
		ReviewDataTest.check("no ACCEPTED offered (0 matches)", ReviewDataTest.countOf(counts, ReviewLine.ACCEPTED) < 0);
		ReviewDataTest.check("no REJECTED offered (0 matches)", ReviewDataTest.countOf(counts, ReviewLine.REJECTED) < 0);
	}

	/** Already viewing ALL: nothing left to switch to via the pseudo-status ALL itself, only the real statuses that have a match. */
	private static void statusCountsExcludingOnAllFilterOffersNothingMore()
	{
		final ReviewFilter filter = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0);
		final List<ReviewData.StatusCount> counts = ReviewData.statusCountsExcluding(ReviewDataTest.demo(), filter);
		ReviewDataTest.check("no ALL entry when already on ALL", ReviewDataTest.countOf(counts, ReviewFilter.ALL) < 0);
		ReviewDataTest.check("the four real statuses, each with a match", counts.size() == 4);
	}

	private static void defaultStatusIsProposedWhenSomethingIsPending()
	{
		ReviewDataTest.check("3 PROPOSED lines exist for nature=ALL: default stays PROPOSED",
			ReviewLine.PROPOSED.equals(ReviewData.defaultStatusFor(ReviewDataTest.demo(), ReviewFilter.ALL, ReviewFilter.ALL)));
	}

	/** The product report's second fix: nature=MATCH has nothing PROPOSED (p1 is APPLIED) - the default must fall back to ALL, not silently show an empty PROPOSED screen. */
	private static void defaultStatusFallsBackToAllWhenNothingIsPending()
	{
		ReviewDataTest.check("nature=MATCH has no PROPOSED line: falls back to ALL",
			ReviewFilter.ALL.equals(ReviewData.defaultStatusFor(ReviewDataTest.demo(), ReviewLine.MATCH, ReviewFilter.ALL)));
	}

	// ------------------------------------------------------------------ fix/node-scope-multi-attribute, item 6

	/**
	 * The real captured miss: an ACCEPTED SCRIPT_CONSTRAINT line (its apply failed, or was simply never run)
	 * must show under the default/Pending filter too - not only the true PROPOSED lines - so the reviewer
	 * can find it without knowing to switch the status filter to ACCEPTED first.
	 */
	private static void pendingFilterAlsoShowsAnAcceptedScriptConstraintNotYetApplied()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e1", "Supplier", "New entity", "LLM", Integer.valueOf(84), "why", ReviewLine.PROPOSED, false, false));
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "sc1", "Supplier.iban", "iban.validate", "LLM", Integer.valueOf(80), "why", ReviewLine.ACCEPTED,
			false, false));
		final List<ReviewLine> visible = ReviewData.filter(lines, ReviewFilter.defaultFilter());
		ReviewDataTest.check("both the true PROPOSED line and the stuck ACCEPTED constraint show: " + visible.size(), visible.size() == 2);
	}

	/** The same rule must NEVER leak to an ACCEPTED line of any OTHER nature - only SCRIPT_CONSTRAINT gets this special case. */
	private static void pendingFilterNeverAddsAnAcceptedLineOfAnyOtherNature()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e1", "Supplier", "New entity", "LLM", Integer.valueOf(84), "why", ReviewLine.ACCEPTED, false, false));
		final List<ReviewLine> visible = ReviewData.filter(lines, ReviewFilter.defaultFilter());
		ReviewDataTest.check("an ACCEPTED ENTITY line stays hidden under Pending", visible.isEmpty());
	}

	/** Filtering explicitly on ACCEPTED still shows exactly the true ACCEPTED lines - the Pending-only special case never applies there. */
	private static void explicitAcceptedFilterIsUnaffectedByThePendingRule()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "sc1", "Supplier.iban", "iban.validate", "LLM", Integer.valueOf(80), "why", ReviewLine.ACCEPTED,
			false, false));
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e1", "Supplier", "New entity", "LLM", Integer.valueOf(84), "why", ReviewLine.PROPOSED, false, false));
		final ReviewFilter acceptedOnly = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.ACCEPTED, 0);
		final List<ReviewLine> visible = ReviewData.filter(lines, acceptedOnly);
		ReviewDataTest.check("only the true ACCEPTED line, the PROPOSED one stays excluded", visible.size() == 1 && "sc1".equals(visible.get(0).rowId));
	}

	private static void unappliedConstraintCountCountsOnlyAcceptedScriptConstraintOverTheWholeProject()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "sc1", "Supplier.iban", "iban.validate", "LLM", Integer.valueOf(80), "why", ReviewLine.ACCEPTED,
			false, false));
		lines.add(new ReviewLine(ReviewLine.SCRIPT_CONSTRAINT, "sc2", "Supplier.vat", "vat.validate", "LLM", Integer.valueOf(70), "why", ReviewLine.APPLIED, false,
			false));
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e1", "Supplier", "New entity", "LLM", Integer.valueOf(84), "why", ReviewLine.ACCEPTED, false, false));
		ReviewDataTest.check("only sc1 counts: ACCEPTED and SCRIPT_CONSTRAINT both required", ReviewData.unappliedConstraintCount(lines) == 1);
		ReviewDataTest.check("zero when there is none", ReviewData.unappliedConstraintCount(ReviewDataTest.demo()) == 0);
	}

	/** @return the count for {@code pStatus}, or -1 when it is not offered at all (a status the caller must prove is ABSENT, not just "not first"). */
	private static int countOf(final List<ReviewData.StatusCount> pCounts, final String pStatus)
	{
		for (final ReviewData.StatusCount count : pCounts)
		{
			if (pStatus.equals(count.status))
			{
				return count.count;
			}
		}
		return -1;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
