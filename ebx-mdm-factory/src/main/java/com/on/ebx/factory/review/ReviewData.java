package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects and normalises the lines the review screen shows: applies a {@link ReviewFilter} and
 * computes the per-status counters, over plain {@link ReviewLine} value objects. Pure: no EBX class,
 * unit-tested as is. The EBX-bound reader ({@code ProposalReviews}) does the reading and the
 * foreign-key resolution that produces the {@link ReviewLine} list this works on.
 */
public final class ReviewData
{
	private ReviewData()
	{
	}

	/** Counts over the WHOLE project, never over a filtered subset: a filter narrows what is listed, not what the counters report. */
	public static final class Counters
	{
		public final int total;
		public final int proposed;
		public final int accepted;
		public final int rejected;
		public final int applied;

		Counters(final int pTotal, final int pProposed, final int pAccepted, final int pRejected, final int pApplied)
		{
			this.total = pTotal;
			this.proposed = pProposed;
			this.accepted = pAccepted;
			this.rejected = pRejected;
			this.applied = pApplied;
		}
	}

	public static List<ReviewLine> filter(final List<ReviewLine> pLines, final ReviewFilter pFilter)
	{
		final List<ReviewLine> out = new ArrayList<ReviewLine>();
		final ReviewFilter filter = pFilter == null ? ReviewFilter.defaultFilter() : pFilter;
		for (final ReviewLine line : pLines)
		{
			if (filter.matches(line))
			{
				out.add(line);
			}
		}
		return out;
	}

	public static Counters counters(final List<ReviewLine> pLines)
	{
		int proposed = 0;
		int accepted = 0;
		int rejected = 0;
		int applied = 0;
		for (final ReviewLine line : pLines)
		{
			if (ReviewLine.PROPOSED.equals(line.status))
			{
				proposed++;
			}
			else if (ReviewLine.ACCEPTED.equals(line.status))
			{
				accepted++;
			}
			else if (ReviewLine.REJECTED.equals(line.status))
			{
				rejected++;
			}
			else if (ReviewLine.APPLIED.equals(line.status))
			{
				applied++;
			}
		}
		return new Counters(pLines.size(), proposed, accepted, rejected, applied);
	}

	/**
	 * Lines eligible for the bulk "accept everything shown above N%" action: currently PROPOSED, at or
	 * above {@code pMinConfidence}, drawn from an already-filtered/visible list (the caller decides what
	 * "shown" means by what it passes in here) - a technical-key line (a {@link ReviewLine#primaryKey}
	 * attribute) is always skipped, never counted as a candidate: it has no decision of its own to bulk-accept
	 * (coordinator review, 2026-09-20 - it follows its entity's own decision, cascaded elsewhere).
	 * <p>
	 * Audit F-08: a {@link ReviewLine#SCRIPT_CONSTRAINT} or {@link ReviewLine#SCRIPT_DERIVATION} line is never a candidate either. Accepting one publishes
	 * a model whose script body the language model wrote, and the percentage is the model's own self-declared confidence; each is accepted one by one
	 * (or in an explicit selection), by a person who chose it.
	 */
	public static List<ReviewLine> bulkAcceptCandidates(final List<ReviewLine> pVisibleLines, final int pMinConfidence)
	{
		return ReviewData.bulkAcceptCandidates(pVisibleLines, pMinConfidence, java.util.Collections.<String>emptySet());
	}

	/**
	 * Lot M7 (2026-10-18): {@link #bulkAcceptCandidates(List, int)} that also skips every {@link ReviewLine#ENTITY} line of {@code pNoFeedEntities} and every
	 * {@link ReviewLine#ATTRIBUTE} line of those entities - an entity no source column feeds (see {@link #entitiesWithoutFeed}) is never accepted by a
	 * threshold, whatever its confidence: it would be built and loaded with nothing (E2EMASTER20261017235630, {@code ContactEmail}). The caller says so.
	 */
	public static List<ReviewLine> bulkAcceptCandidates(final List<ReviewLine> pVisibleLines, final int pMinConfidence, final java.util.Set<String> pNoFeedEntities)
	{
		final List<ReviewLine> out = new ArrayList<ReviewLine>();
		for (final ReviewLine line : pVisibleLines)
		{
			if (line.primaryKey || ReviewData.needsOneByOneAcceptance(line.nature))
			{
				continue;
			}
			if (pNoFeedEntities != null && !pNoFeedEntities.isEmpty() && pNoFeedEntities.contains(ReviewData.entityOf(line)))
			{
				continue;
			}
			final int confidence = line.confidence == null ? 0 : line.confidence.intValue();
			if (ReviewLine.PROPOSED.equals(line.status) && confidence >= pMinConfidence)
			{
				out.add(line);
			}
		}
		return out;
	}

	/** Whether accepting a line of this nature publishes model-written code, so it is never accepted by a confidence threshold (audit F-08). */
	public static boolean needsOneByOneAcceptance(final String pNature)
	{
		return ReviewLine.SCRIPT_CONSTRAINT.equals(pNature) || ReviewLine.SCRIPT_DERIVATION.equals(pNature);
	}

	/**
	 * Lot M7 (2026-10-18): the names of the PROPOSED entities of {@code pAllLines} that no source column feeds - no {@link ReviewLine#MAPPING} line of any of
	 * their attributes is still alive (not REJECTED, not SUPERSEDED). Read off the lines' own elements ({@code "Supplier"} for an entity, {@code "SUPPLIER.NAME1
	 * → Account.parentAccountId"} for a mapping), the whole project's lines, never the filtered ones: the mappings may be hidden by the filter. Pure.
	 */
	public static java.util.Set<String> entitiesWithoutFeed(final List<ReviewLine> pAllLines)
	{
		final java.util.Set<String> fed = new java.util.HashSet<String>();
		for (final ReviewLine line : pAllLines)
		{
			if (ReviewLine.MAPPING.equals(line.nature) && !ReviewLine.REJECTED.equals(line.status) && !ReviewLine.SUPERSEDED.equals(line.status))
			{
				fed.add(ReviewData.entityOf(line));
			}
		}
		final java.util.Set<String> out = new java.util.LinkedHashSet<String>();
		for (final ReviewLine line : pAllLines)
		{
			if (ReviewLine.ENTITY.equals(line.nature) && ReviewLine.PROPOSED.equals(line.status) && !fed.contains(line.element))
			{
				out.add(line.element);
			}
		}
		return out;
	}

	/** The entity a line belongs to, from its element: the element itself for an entity, {@code Entity} of {@code Entity.attribute} (after the arrow of a mapping), null for the other natures. */
	static String entityOf(final ReviewLine pLine)
	{
		if (pLine.element == null)
		{
			return null;
		}
		if (ReviewLine.ENTITY.equals(pLine.nature))
		{
			return pLine.element;
		}
		if (ReviewLine.ATTRIBUTE.equals(pLine.nature) || ReviewLine.MAPPING.equals(pLine.nature))
		{
			final int arrow = pLine.element.indexOf("\u2192");
			final String target = arrow < 0 ? pLine.element : pLine.element.substring(arrow + 1).trim();
			final int dot = target.indexOf('.');
			return dot < 0 ? target : target.substring(0, dot);
		}
		return null;
	}

	/** One status other than the current filter's, and how many lines of {@code pLines} would show under it with everything else about {@code pFilter} unchanged (nature/origin/minConfidence). */
	public static final class StatusCount
	{
		public final String status;
		public final int count;

		StatusCount(final String pStatus, final int pCount)
		{
			this.status = pStatus;
			this.count = pCount;
		}
	}

	/** Every status of {@link ReviewLine}'s own vocabulary, in the order the filter dropdown lists them - the fixed universe {@link #statusCountsExcluding} walks. */
	private static final String[] ALL_STATUSES = { ReviewLine.PROPOSED, ReviewLine.ACCEPTED, ReviewLine.REJECTED, ReviewLine.APPLIED, ReviewLine.SET_ASIDE };

	/**
	 * The empty-state fix (review-quality lot, task B, product report 2026-09-21): "Status = Pending and
	 * nothing pending" must not read as "nothing at all" when lines exist under another status. For every
	 * OTHER status than {@code pFilter.status} (same nature/origin/minConfidence, status swapped), the count
	 * of lines that would show - only the ones with at least one match, so the screen offers real links, never
	 * a dead end. {@code pFilter.status} itself is excluded (that is the empty result already shown); {@code
	 * ALL} is included as its own option when it differs from the current filter and finds something (it can
	 * find MORE than any single status, since it is the union).
	 */
	public static List<StatusCount> statusCountsExcluding(final List<ReviewLine> pLines, final ReviewFilter pFilter)
	{
		final List<StatusCount> out = new ArrayList<StatusCount>();
		final String currentStatus = pFilter.status == null ? ReviewFilter.ALL : pFilter.status;
		if (!ReviewFilter.ALL.equals(currentStatus))
		{
			final int allCount = ReviewData.filter(pLines, new ReviewFilter(pFilter.nature, pFilter.origin, ReviewFilter.ALL, pFilter.minConfidence)).size();
			if (allCount > 0)
			{
				out.add(new StatusCount(ReviewFilter.ALL, allCount));
			}
		}
		for (final String status : ReviewData.ALL_STATUSES)
		{
			if (status.equals(currentStatus))
			{
				continue;
			}
			final int count = ReviewData.filter(pLines, new ReviewFilter(pFilter.nature, pFilter.origin, status, pFilter.minConfidence)).size();
			if (count > 0)
			{
				out.add(new StatusCount(status, count));
			}
		}
		return out;
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 6: how many {@link ReviewLine#SCRIPT_CONSTRAINT}
	 * lines of the WHOLE project are {@link ReviewLine#ACCEPTED} but not yet {@link ReviewLine#APPLIED} - the
	 * banner's own count, over every line regardless of the current filter (the same "never a filtered
	 * subset" discipline {@link #counters} already follows). Feeds the "N accepted constraint(s) are not on
	 * the model yet" banner {@code ReviewPage} shows whenever this is greater than zero.
	 */
	public static int unappliedConstraintCount(final List<ReviewLine> pLines)
	{
		int count = 0;
		for (final ReviewLine line : pLines)
		{
			if (ReviewLine.SCRIPT_CONSTRAINT.equals(line.nature) && ReviewLine.ACCEPTED.equals(line.status))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * The default-status fallback (review-quality lot, task B, product report 2026-09-21): {@code PROPOSED}
	 * stays the default ONLY when at least one line of {@code pLines} is actually {@code PROPOSED} under the
	 * chosen nature/origin (a project with nothing pending for that combination must not open on a screen
	 * that reads as broken) - otherwise {@code ALL}, so the reviewer sees something on arrival. Confidence
	 * floor is deliberately NOT part of this decision (it defaults to 0, never narrowing this check).
	 */
	public static String defaultStatusFor(final List<ReviewLine> pLines, final String pNature, final String pOrigin)
	{
		final ReviewFilter probe = new ReviewFilter(pNature, pOrigin, ReviewLine.PROPOSED, 0);
		return ReviewData.filter(pLines, probe).isEmpty() ? ReviewFilter.ALL : ReviewLine.PROPOSED;
	}
}
