package com.on.ebx.factory.mappingmatrix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.wizard.WizardRules;

/**
 * Every derived fact the Mapping matrix screen needs, computed purely from {@link MatrixModel} - no EBX
 * type, unit-tested in {@code MatrixComputeTest} with 1/5/8 sources and 1/6 entities (docs/MAPPING-MATRIX.md,
 * "generalises the mockup's fixed 5-source layout"). Mirrors the reference mockup's own {@code statusOf}/
 * {@code statsFor}/overview-geometry functions ({@code docs/mockups/mapping-matrix-5-sources.dc.html}), ported
 * from the mockup's client-side JS to server-side Java: the effective cell status, per-feed and per-entity
 * counters, the bulk-accept candidate set (a PROPOSED mapping at or above the bulk-accept confidence
 * threshold), and the overview graphic's node/curve geometry.
 */
public final class MatrixCompute
{
	/** Same default as the wizard's own bulk-accept threshold convention (docs/TARGET-MAPPING.md, "N proposals >= 60%"). */
	public static final int DEFAULT_BULK_THRESHOLD = 60;

	private MatrixCompute()
	{
	}

	/**
	 * {@link MatrixModel#DERIVED} for a derived attribute (computed at load, never fed by a source link - whatever
	 * cell a feed might carry for it); otherwise the cell's own status when one exists; otherwise {@link
	 * MatrixModel#MISSING} for a mandatory attribute, {@link MatrixModel#NONE} otherwise.
	 */
	public static String effectiveStatus(final MatrixModel.Attr pAttr, final MatrixModel.Feed pFeed)
	{
		if (pAttr.isDerived())
		{
			return MatrixModel.DERIVED;
		}
		final MatrixModel.Cell cell = pFeed.cellsByAttributeId.get(pAttr.id);
		if (cell != null)
		{
			return cell.status;
		}
		return pAttr.mandatory ? MatrixModel.MISSING : MatrixModel.NONE;
	}

	/** Per-feed counters over every attribute of the entity - the matrix column header's stacked bar and "Accept N (&gt;= T%)" button. */
	public static final class FeedStats
	{
		public int accepted;
		public int proposed;
		public int rejected;
		public int missing;
		/** Staging-proposal matrix lot: how many of the {@link #proposed}/{@link #accepted} cells above also carry an {@code AttentionRules} flag - "needs your eye" (delta 2). */
		public int attention;
		public final List<String> toAcceptMappingIds = new ArrayList<String>();
	}

	public static FeedStats feedStats(final List<MatrixModel.Attr> pAttrs, final MatrixModel.Feed pFeed, final int pBulkThreshold)
	{
		final FeedStats out = new FeedStats();
		for (final MatrixModel.Attr attr : pAttrs)
		{
			final MatrixModel.Cell cell = pFeed.cellsByAttributeId.get(attr.id);
			final String status = MatrixCompute.effectiveStatus(attr, pFeed);
			if (MatrixModel.ACCEPTED.equals(status))
			{
				out.accepted++;
			}
			else if (MatrixModel.PROPOSED.equals(status))
			{
				out.proposed++;
				if (cell != null && cell.isFlagged())
				{
					out.attention++;
				}
				else if (cell != null && cell.confidence != null && cell.confidence.intValue() >= pBulkThreshold && cell.mappingId != null)
				{
					out.toAcceptMappingIds.add(cell.mappingId);
				}
			}
			else if (MatrixModel.REJECTED.equals(status))
			{
				out.rejected++;
				if (attr.mandatory)
				{
					out.missing++;
				}
			}
			else if (MatrixModel.MISSING.equals(status))
			{
				out.missing++;
			}
		}
		// coordinator review (2026-09-23): a feed still PROPOSED itself is "to validate" even when every one
		// of its mappings already reads ACCEPTED - counted here (not as a mapping-cell status, there is no
		// cell for it) so it propagates into entityStats/statsBySource automatically, and so the per-feed
		// header bar/count never claims a feed is done while the feed's OWN decision is still open.
		if (ReviewLine.PROPOSED.equals(pFeed.status))
		{
			out.proposed++;
		}
		return out;
	}

	/**
	 * Sum of every feed's {@link FeedStats} of the entity, PLUS the entity's own pending decision and every
	 * non-technical-key attribute's own pending decision (coordinator review, 2026-09-23: "counters ... must
	 * count undecided entities/attributes/feeds as 'to validate' too") - the toolbar counters and the
	 * overview card's status text.
	 */
	public static FeedStats entityStats(final MatrixModel.Entity pEntity, final int pBulkThreshold)
	{
		final FeedStats out = new FeedStats();
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			final FeedStats feedStats = MatrixCompute.feedStats(pEntity.attrs, feed, pBulkThreshold);
			out.accepted += feedStats.accepted;
			out.proposed += feedStats.proposed;
			out.rejected += feedStats.rejected;
			out.missing += feedStats.missing;
			out.attention += feedStats.attention;
			out.toAcceptMappingIds.addAll(feedStats.toAcceptMappingIds);
		}
		if (ReviewLine.PROPOSED.equals(pEntity.status))
		{
			out.proposed++;
		}
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			if (!attr.sourceKey && !attr.parentLink && ReviewLine.PROPOSED.equals(attr.status))
			{
				out.proposed++;
			}
		}
		return out;
	}

	/** Every PROPOSED mapping id at or above {@code pBulkThreshold}, across every feed of the entity - "Accept the N proposals >= T%". */
	public static List<String> bulkAcceptIdsOfEntity(final MatrixModel.Entity pEntity, final int pBulkThreshold)
	{
		return MatrixCompute.entityStats(pEntity, pBulkThreshold).toAcceptMappingIds;
	}

	/** Aggregated, per SOURCE, over every feed of that source under one entity - what the overview curve/chip shows (docs/MAPPING-MATRIX.md: a source can reach one entity through more than one feed). */
	public static Map<String, FeedStats> statsBySource(final MatrixModel.Entity pEntity, final int pBulkThreshold)
	{
		final Map<String, FeedStats> out = new LinkedHashMap<String, FeedStats>();
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			FeedStats stats = out.get(feed.sourceId);
			if (stats == null)
			{
				stats = new FeedStats();
				out.put(feed.sourceId, stats);
			}
			final FeedStats feedStats = MatrixCompute.feedStats(pEntity.attrs, feed, pBulkThreshold);
			stats.accepted += feedStats.accepted;
			stats.proposed += feedStats.proposed;
			stats.rejected += feedStats.rejected;
			stats.missing += feedStats.missing;
			stats.attention += feedStats.attention;
			stats.toAcceptMappingIds.addAll(feedStats.toAcceptMappingIds);
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------- button visibility

	/**
	 * Whether the toolbar's own Accept/Reject-entity buttons should show - the task brief's own wording,
	 * "Accept / Reject in the toolbar when the active entity is not decided": exactly {@code PROPOSED}, never
	 * {@code ACCEPTED}/{@code REJECTED}/{@code APPLIED} (a decided entity is re-decided nowhere on this
	 * screen - unlike the wizard's own always-visible toggle, this screen only ever asks once).
	 */
	public static boolean canDecideEntity(final String pEntityStatus)
	{
		return ReviewLine.PROPOSED.equals(pEntityStatus);
	}

	/** Row-header Accept/Reject: {@code PROPOSED} and not the technical {@link MatrixModel.Attr#sourceKey} row (it follows its entity, never decided on its own). */
	public static boolean canDecideAttribute(final MatrixModel.Attr pAttr)
	{
		// the parent link (ParentLinkRule) follows its entity too: rejecting it would leave every child row without its parent
		return !pAttr.sourceKey && !pAttr.parentLink && ReviewLine.PROPOSED.equals(pAttr.status);
	}

	/** Column-header Accept/Reject: {@code PROPOSED}. */
	public static boolean canDecideFeed(final MatrixModel.Feed pFeed)
	{
		return ReviewLine.PROPOSED.equals(pFeed.status);
	}

	/**
	 * What the cell's own Accept control should do, given the attribute's and the entity's CURRENT status
	 * (coordinator review, 2026-09-23): mirrors {@code review.ReviewRules#canAcceptAttributeMapping}/{@code
	 * #canAcceptStagingAttribute}'s own chain (mapping needs its attribute accepted-like; attribute needs its
	 * entity accepted-like) so the button never promises something the server will then refuse.
	 */
	public enum CellAcceptAction
	{
		/** The entity itself is not accepted-like yet: no accept attempt from the cell - point at the toolbar instead, never cascade silently. */
		ENTITY_NOT_ACCEPTED,
		/** The attribute is not accepted-like yet (but the entity is): accepting the cell also accepts the attribute first, in one user click. */
		ACCEPT_ATTRIBUTE_THEN_MAPPING,
		/** Both the entity and the attribute are already accepted-like: an ordinary mapping accept. */
		ACCEPT_MAPPING_ONLY
	}

	public static CellAcceptAction cellAcceptAction(final String pEntityStatus, final String pAttributeStatus)
	{
		if (!"ACCEPTED".equals(pEntityStatus) && !"APPLIED".equals(pEntityStatus))
		{
			return CellAcceptAction.ENTITY_NOT_ACCEPTED;
		}
		if (!"ACCEPTED".equals(pAttributeStatus) && !"APPLIED".equals(pAttributeStatus))
		{
			return CellAcceptAction.ACCEPT_ATTRIBUTE_THEN_MAPPING;
		}
		return CellAcceptAction.ACCEPT_MAPPING_ONLY;
	}

	// ------------------------------------------------------------------------------------------------------ attention

	/**
	 * True when at least one of this attribute's own cells (any feed/source) carries an {@code
	 * AttentionRules} flag - "needs your eye" (delta 2). Scoped to the WHOLE attribute (not one cell) on
	 * purpose: the bulk sweeps (delta 4) and the row-level chip both need "is this field safe to
	 * fast-forward" as a single yes/no, not a per-source answer.
	 */
	public static boolean attributeFlagged(final MatrixModel.Entity pEntity, final String pAttributeId)
	{
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			final MatrixModel.Cell cell = feed.cellsByAttributeId.get(pAttributeId);
			if (cell != null && cell.isFlagged())
			{
				return true;
			}
		}
		return false;
	}

	/** Every attribute id of {@code pEntity} whose every covering cell reads {@link MatrixModel#EMPTY_COLUMN} in EVERY feed that has one at all - the "Remove the field" suggested fix (delta 3). */
	public static boolean attributeEmptyEverywhere(final MatrixModel.Entity pEntity, final MatrixModel.Attr pAttr)
	{
		boolean sawAny = false;
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			final MatrixModel.Cell cell = feed.cellsByAttributeId.get(pAttr.id);
			if (cell == null)
			{
				continue;
			}
			sawAny = true;
			if (!"EMPTY_COLUMN".equals(cell.attentionFlag))
			{
				return false;
			}
		}
		return sawAny;
	}

	/** Every attribute id of {@code pEntity} flagged {@code DUPLICATE_FIELD} - the "Remove the N duplicate fields" suggested fix (delta 3). */
	public static List<String> duplicateFieldAttributeIds(final MatrixModel.Entity pEntity)
	{
		final List<String> out = new ArrayList<String>();
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			for (final MatrixModel.Feed feed : pEntity.feeds)
			{
				final MatrixModel.Cell cell = feed.cellsByAttributeId.get(attr.id);
				if (cell != null && "DUPLICATE_FIELD".equals(cell.attentionFlag))
				{
					out.add(attr.id);
					break;
				}
			}
		}
		return out;
	}

	// --------------------------------------------------------------------------------------------------- guards

	/** Accepted/proposed/rejected counters over one flat list of {@link Guard}s - {@link MatrixModel.Entity#entityGuards} or one {@link MatrixModel.Attr#guards}. */
	public static final class GuardStats
	{
		public int accepted;
		public int proposed;
		public int rejected;
		/** {@link ReviewLine#QUALITY} count - the "rules" half of "rules and checks". */
		public int rules;
		/** {@link ReviewLine#SCRIPT_CONSTRAINT}/{@link ReviewLine#EXTERNAL_SERVICE}/{@link ReviewLine#SCRIPT_DERIVATION} count - the "checks" half. */
		public int checks;
	}

	public static GuardStats guardStats(final List<Guard> pGuards)
	{
		final GuardStats out = new GuardStats();
		for (final Guard guard : pGuards)
		{
			if (ReviewLine.ACCEPTED.equals(guard.status) || ReviewLine.APPLIED.equals(guard.status))
			{
				out.accepted++;
			}
			else if (ReviewLine.PROPOSED.equals(guard.status))
			{
				out.proposed++;
			}
			else if (ReviewLine.REJECTED.equals(guard.status))
			{
				out.rejected++;
			}
			if (ReviewLine.QUALITY.equals(guard.nature))
			{
				out.rules++;
			}
			else
			{
				out.checks++;
			}
		}
		return out;
	}

	/** Every guard of {@code pEntity} - its own {@link MatrixModel.Entity#entityGuards} plus every attribute's {@link MatrixModel.Attr#guards}. */
	public static GuardStats entityGuardStats(final MatrixModel.Entity pEntity)
	{
		final List<Guard> all = new ArrayList<Guard>(pEntity.entityGuards);
		int decidedWithTheirField = 0;
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			all.addAll(attr.guards);
			for (final Guard guard : attr.guards)
			{
				if (MatrixCompute.decidedWithItsField(attr, guard))
				{
					decidedWithTheirField++;
				}
			}
		}
		final GuardStats out = MatrixCompute.guardStats(all);
		// fix/matrix-computed-field: the function of a computed field is decided WITH the field (one Accept, one Reject), so
		// while the field itself still counts as an item to decide, its function is not a second one
		out.proposed -= decidedWithTheirField;
		return out;
	}

	/**
	 * fix/matrix-computed-field: true for the {@link ReviewLine#SCRIPT_DERIVATION} guard of a computed ({@code SCRIPT})
	 * attribute that is itself still {@code PROPOSED} and whose own guard is {@code PROPOSED} too - accepting or rejecting
	 * the field takes that guard along ({@code ebx.ProposalReviews}), so the two are ONE thing left to decide, not two.
	 * Once the field is decided, a guard still {@code PROPOSED} (an older row) is a decision of its own again.
	 */
	public static boolean decidedWithItsField(final MatrixModel.Attr pAttr, final Guard pGuard)
	{
		return ReviewLine.SCRIPT_DERIVATION.equals(pGuard.nature) && "SCRIPT".equals(pAttr.derivation) && ReviewLine.PROPOSED.equals(pAttr.status)
			&& ReviewLine.PROPOSED.equals(pGuard.status);
	}

	/** Whether {@code review.ReviewRules}/{@code ProposalReviews#decide} would accept a REJECT for this guard right now - reject is never gated on a parent's status (mirrors {@code cellAcceptAction}'s own "reject is always offered" rule). */
	public static boolean canDecideGuard(final Guard pGuard)
	{
		return ReviewLine.PROPOSED.equals(pGuard.status);
	}

	/** Whether this guard's nature carries a severity/transferPolicy control at all - {@link ReviewLine#QUALITY}/{@link ReviewLine#SCRIPT_CONSTRAINT} only (delta 1: EXTERNAL_SERVICE/SCRIPT_DERIVATION show no severity control). */
	public static boolean guardHasSeverityControl(final Guard pGuard)
	{
		return ReviewLine.QUALITY.equals(pGuard.nature) || ReviewLine.SCRIPT_CONSTRAINT.equals(pGuard.nature);
	}

	// ------------------------------------------------------------------------------------------------ bulk cascade

	/** One row a bulk sweep decided (or is about to) - {@code pNature} is one of {@link ReviewLine#ENTITY}/{@link ReviewLine#ATTRIBUTE}/{@link ReviewLine#FEED}/{@link ReviewLine#MAPPING}. */
	public static final class PlannedDecision
	{
		public final String nature;
		public final String id;

		PlannedDecision(final String pNature, final String pId)
		{
			this.nature = pNature;
			this.id = pId;
		}
	}

	/**
	 * The ordered set of rows one bulk-accept sweep should attempt, plus what it leaves out below the
	 * threshold - the same three-way classification {@code wizard.WizardRules.BulkEligibility}/{@code
	 * ebx.ui.ProjectWizardUserService#bulkStaging} already report (decided / refused-by-a-rule /
	 * left-out-below-threshold), so {@code ebx.ui.MappingMatrixUserService} can build the exact same kind of
	 * notice ("N accepted, N skipped, N left out below M%").
	 */
	public static final class BulkPlan
	{
		public final List<PlannedDecision> toDecide = new ArrayList<PlannedDecision>();
		public int leftOut;
		public int lowestLeftOutConfidence = 100;
		/** Validation desk lot L2 ({@link #planEntityAsShown}): the LINKS the plan holds back because the preview flagged them - they stay {@code PROPOSED} and stay listed. */
		public int held;
	}

	/**
	 * What the Validation desk's preview of real landing rows showed for one business object, as keys: the feeds that were SHOWN (a block of rows - not the ones that fell back to
	 * the list) and the mappings (links) the preview FLAGGED, by the matrix' own attention flag or because a rule of the field would warn or reject on a produced value.
	 * Plain data so {@link #planEntityAsShown} stays pure and the desk's reader stays the only part that touches EBX.
	 */
	public static final class ShownKeys
	{
		public final Set<String> shownFeedIds = new LinkedHashSet<String>();
		public final Set<String> flaggedMappingIds = new LinkedHashSet<String>();
	}

	/**
	 * Whole-entity bulk accept, cascading in the SAME order {@code ProjectWizardUserService#bulkStaging} uses
	 * for the rows it already knows (entity, then its non-key attributes, then its mappings) - extended here
	 * with FEEDS, which {@code bulkStaging} does not touch at all, inserted between attributes and mappings
	 * per the task brief ("entity, then non-key attributes, then feeds, then mappings"). Every row uses the
	 * SAME {@link WizardRules#bulkEligibility} rule {@code bulkStaging} uses - never re-derived - always with
	 * {@code includeBelowThreshold=false} (this screen has no such toggle, unlike the wizard's per-card one).
	 * <p>
	 * Staging-proposal matrix lot (delta 4): a FLAGGED attribute (any of its cells carries an {@code
	 * AttentionRules} flag - {@link #attributeFlagged}) is skipped ENTIRELY - never the attribute itself,
	 * never its guards, never its mappings - "accept what is not flagged" only ever fast-forwards a field a
	 * person has not been asked to look at. Every attribute's own {@link MatrixModel.Attr#guards} (and the
	 * entity-level ones) are folded in right after their field, in the same eligibility rule.
	 */
	public static BulkPlan planEntityBulk(final MatrixModel.Entity pEntity, final int pBulkThreshold)
	{
		final BulkPlan plan = new BulkPlan();
		MatrixCompute.addIfEligible(plan, ReviewLine.ENTITY, pEntity.id, pEntity.status, pEntity.confidence, pBulkThreshold);
		final Set<String> plannedAttributeIds = new LinkedHashSet<String>();
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			if (attr.sourceKey || attr.parentLink)
			{
				continue; // technical key or parent link: no decision of its own, follows the entity - same skip bulkStaging applies to a primaryKey attribute
			}
			if (attr.isDerived())
			{
				continue; // a computed field is never swept; a matching key (FOLD_KEY) is taken in the second pass below, once its source field is known
			}
			if (MatrixCompute.attributeFlagged(pEntity, attr.id))
			{
				continue; // delta 4: a flagged field is never fast-forwarded, not even its own guards
			}
			if (MatrixCompute.planAttribute(plan, attr, pBulkThreshold))
			{
				plannedAttributeIds.add(attr.id);
			}
		}
		// Matching keys (FOLD_KEY), right after the ordinary fields. Accepting a matching key only adds a technical
		// column (the display value is untouched), while forgetting it silently loses matches (PersVol9: firstName
		// shared 0 values across sources raw, 60 with the key) - so a key goes with the sweep like any field, under
		// the same threshold rule. It follows its SOURCE field, though: never taken when that field is flagged,
		// rejected, or itself still waiting (a later Reject of the field only reaches a key still PROPOSED, so an
		// accepted key would be left behind). Every other derivation (SCRIPT, VALID_VALUE) stays a person's choice.
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			if (!attr.sourceKey && MatrixCompute.isFoldKey(attr) && MatrixCompute.foldKeySourceAllows(pEntity, attr, plannedAttributeIds))
			{
				MatrixCompute.planAttribute(plan, attr, pBulkThreshold);
			}
		}
		for (final Guard guard : pEntity.entityGuards) // no single attribute of its own, so never "flagged" the same way
		{
			MatrixCompute.addIfEligible(plan, guard.nature, guard.id, guard.status, guard.confidence, pBulkThreshold);
		}
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			MatrixCompute.addIfEligible(plan, ReviewLine.FEED, feed.id, feed.status, feed.confidence, pBulkThreshold);
		}
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			for (final Map.Entry<String, MatrixModel.Cell> entry : feed.cellsByAttributeId.entrySet())
			{
				final MatrixModel.Cell cell = entry.getValue();
				if (cell.mappingId != null && !MatrixCompute.attributeFlagged(pEntity, entry.getKey()))
				{
					MatrixCompute.addIfEligible(plan, ReviewLine.MAPPING, cell.mappingId, cell.status, cell.confidence, pBulkThreshold);
				}
			}
		}
		return plan;
	}

	/**
	 * "Accept &lt;object&gt; as shown" (Validation desk lot L2, docs/STAGING-DESK.md): the person has seen real rows go through the proposed links, so the plan takes what they saw
	 * WHATEVER ITS CONFIDENCE - unlike {@link #planEntityBulk}, which stops at the threshold -, and leaves what the preview flagged. Same cascade ORDER as {@link #planEntityBulk}
	 * and for the same reason ({@code review.ReviewRules}: a field or a link can be accepted only once the object is, and a link once its field): the object, then its fields, then
	 * the source links (feeds), then the field links (mappings).
	 * <ul>
	 * <li>The object and each feed of {@code pKeys.shownFeedIds}, when still {@code PROPOSED}.</li>
	 * <li>A link (mapping) of a shown feed, when still {@code PROPOSED} and not in {@code pKeys.flaggedMappingIds}. A flagged link waits ({@link BulkPlan#held}).</li>
	 * <li>A field that has at least one link (not rejected) in a shown feed, when still {@code PROPOSED}, unless every one of its shown links is flagged (then the whole field waits).
	 * The technical key, the parent link and the computed fields (matching keys, scripts) are never touched - the first two follow their object, the others are decided in the
	 * list, where their function is described.</li>
	 * <li>A {@code REJECTED} item is never planned, an {@code ACCEPTED}/{@code APPLIED} one is already done.</li>
	 * </ul>
	 * A mapping's cleaning steps are NOT swept: the preview applied only the accepted ones, so what loads is what was shown.
	 */
	public static BulkPlan planEntityAsShown(final MatrixModel.Entity pEntity, final ShownKeys pKeys)
	{
		final BulkPlan plan = new BulkPlan();
		MatrixCompute.addIfProposed(plan, ReviewLine.ENTITY, pEntity.id, pEntity.status);
		// per attribute: its shown links, and how many of them are flagged
		final Map<String, int[]> linksOfAttribute = new LinkedHashMap<String, int[]>(); // [shown, flagged]
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			if (!pKeys.shownFeedIds.contains(feed.id))
			{
				continue;
			}
			for (final Map.Entry<String, MatrixModel.Cell> entry : feed.cellsByAttributeId.entrySet())
			{
				final MatrixModel.Cell cell = entry.getValue();
				if (cell.mappingId == null || !cell.isData() || ReviewLine.REJECTED.equals(cell.status))
				{
					continue;
				}
				int[] counts = linksOfAttribute.get(entry.getKey());
				if (counts == null)
				{
					counts = new int[2];
					linksOfAttribute.put(entry.getKey(), counts);
				}
				counts[0]++;
				if (pKeys.flaggedMappingIds.contains(cell.mappingId))
				{
					counts[1]++;
				}
			}
		}
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			final int[] counts = linksOfAttribute.get(attr.id);
			if (counts == null || attr.sourceKey || attr.parentLink || attr.isDerived())
			{
				continue;
			}
			if (counts[0] > 0 && counts[1] == counts[0])
			{
				continue; // every link of the field is flagged: the field waits with them (counted in held through its links)
			}
			MatrixCompute.addIfProposed(plan, ReviewLine.ATTRIBUTE, attr.id, attr.status);
		}
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			if (pKeys.shownFeedIds.contains(feed.id))
			{
				MatrixCompute.addIfProposed(plan, ReviewLine.FEED, feed.id, feed.status);
			}
		}
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			if (!pKeys.shownFeedIds.contains(feed.id))
			{
				continue;
			}
			for (final Map.Entry<String, MatrixModel.Cell> entry : feed.cellsByAttributeId.entrySet())
			{
				final MatrixModel.Cell cell = entry.getValue();
				if (cell.mappingId == null || !ReviewLine.PROPOSED.equals(cell.status))
				{
					continue;
				}
				if (pKeys.flaggedMappingIds.contains(cell.mappingId))
				{
					plan.held++;
				}
				else
				{
					MatrixCompute.addIfProposed(plan, ReviewLine.MAPPING, cell.mappingId, cell.status);
				}
			}
		}
		return plan;
	}

	/** One row into an "as shown" plan: only a row still {@code PROPOSED} - the confidence is not asked, the person saw the result. */
	private static void addIfProposed(final BulkPlan pPlan, final String pNature, final String pId, final String pStatus)
	{
		if (ReviewLine.PROPOSED.equals(pStatus))
		{
			pPlan.toDecide.add(new PlannedDecision(pNature, pId));
		}
	}

	/** True for a matching key ({@code StagingAttribute.derivation = FOLD_KEY}) - the one derivation a sweep may accept. */
	public static boolean isFoldKey(final MatrixModel.Attr pAttr)
	{
		return "FOLD_KEY".equals(pAttr.derivation);
	}

	/** The field of {@code pEntity} named {@code pName} ({@link MatrixModel.Attr#derivedFrom} is a NAME), or null. */
	private static MatrixModel.Attr attributeByName(final MatrixModel.Entity pEntity, final String pName)
	{
		if (pName == null || pName.isEmpty())
		{
			return null;
		}
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			if (pName.equals(attr.name))
			{
				return attr;
			}
		}
		return null;
	}

	/** Whether a matching key's source field lets a sweep take the key - see the comment in {@link #planEntityBulk}. An unresolved source (name unknown) does not stop it. */
	private static boolean foldKeySourceAllows(final MatrixModel.Entity pEntity, final MatrixModel.Attr pKey, final Set<String> pPlannedAttributeIds)
	{
		final MatrixModel.Attr source = MatrixCompute.attributeByName(pEntity, pKey.derivedFrom);
		if (source == null)
		{
			return true;
		}
		if (ReviewLine.REJECTED.equals(source.status) || MatrixCompute.attributeFlagged(pEntity, source.id))
		{
			return false;
		}
		return !ReviewLine.PROPOSED.equals(source.status) || pPlannedAttributeIds.contains(source.id);
	}

	/** One field and its guards into the plan, same eligibility rule - true when the FIELD itself was planned. */
	private static boolean planAttribute(final BulkPlan pPlan, final MatrixModel.Attr pAttr, final int pBulkThreshold)
	{
		final int before = pPlan.toDecide.size();
		MatrixCompute.addIfEligible(pPlan, ReviewLine.ATTRIBUTE, pAttr.id, pAttr.status, pAttr.confidence, pBulkThreshold);
		final boolean planned = pPlan.toDecide.size() > before;
		for (final Guard guard : pAttr.guards)
		{
			MatrixCompute.addIfEligible(pPlan, guard.nature, guard.id, guard.status, guard.confidence, pBulkThreshold);
		}
		return planned;
	}

	/**
	 * One feed's own bulk accept - task brief: "the feed itself + the eligible attributes its mappings point
	 * to + its eligible mappings" (never the whole entity, never a feed's SIBLING feeds' own attributes) -
	 * same "skip a flagged field entirely, fold in its guards" rule as {@link #planEntityBulk} (delta 4). A
	 * matching key is taken with the feed that feeds its source field, under the same source-field condition.
	 */
	public static BulkPlan planFeedBulk(final MatrixModel.Entity pEntity, final MatrixModel.Feed pFeed, final int pBulkThreshold)
	{
		final BulkPlan plan = new BulkPlan();
		MatrixCompute.addIfEligible(plan, ReviewLine.FEED, pFeed.id, pFeed.status, pFeed.confidence, pBulkThreshold);
		final Set<String> attributeIdsOfThisFeed = new LinkedHashSet<String>();
		for (final Map.Entry<String, MatrixModel.Cell> entry : pFeed.cellsByAttributeId.entrySet())
		{
			if (entry.getValue().mappingId != null)
			{
				attributeIdsOfThisFeed.add(entry.getKey());
			}
		}
		final Set<String> plannedAttributeIds = new LinkedHashSet<String>();
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			if (attr.sourceKey || attr.isDerived() || !attributeIdsOfThisFeed.contains(attr.id) || MatrixCompute.attributeFlagged(pEntity, attr.id))
			{
				continue;
			}
			if (MatrixCompute.planAttribute(plan, attr, pBulkThreshold))
			{
				plannedAttributeIds.add(attr.id);
			}
		}
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			if (attr.sourceKey || !MatrixCompute.isFoldKey(attr))
			{
				continue;
			}
			final MatrixModel.Attr source = MatrixCompute.attributeByName(pEntity, attr.derivedFrom);
			if (source != null && attributeIdsOfThisFeed.contains(source.id) && MatrixCompute.foldKeySourceAllows(pEntity, attr, plannedAttributeIds))
			{
				MatrixCompute.planAttribute(plan, attr, pBulkThreshold);
			}
		}
		for (final Map.Entry<String, MatrixModel.Cell> entry : pFeed.cellsByAttributeId.entrySet())
		{
			final MatrixModel.Cell cell = entry.getValue();
			if (cell.mappingId != null && !MatrixCompute.attributeFlagged(pEntity, entry.getKey()))
			{
				MatrixCompute.addIfEligible(plan, ReviewLine.MAPPING, cell.mappingId, cell.status, cell.confidence, pBulkThreshold);
			}
		}
		return plan;
	}

	/** Every entity's own {@link #planEntityBulk}, concatenated - the footer's project-wide "Accept the N unflagged proposals" (delta 4). */
	public static BulkPlan planProjectBulk(final MatrixModel.Project pProject, final int pBulkThreshold)
	{
		final BulkPlan out = new BulkPlan();
		out.lowestLeftOutConfidence = 100;
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			final BulkPlan one = MatrixCompute.planEntityBulk(entity, pBulkThreshold);
			out.toDecide.addAll(one.toDecide);
			out.leftOut += one.leftOut;
			if (one.leftOut > 0)
			{
				out.lowestLeftOutConfidence = Math.min(out.lowestLeftOutConfidence, one.lowestLeftOutConfidence);
			}
		}
		return out;
	}

	/**
	 * Whether "Load the staging" should be enabled (delta 4: "enabled only when nothing is left PROPOSED or
	 * flagged" - a shortcut the matrix offers; the wizard keeps its own, authoritative gate): every entity's
	 * own {@link #entityStats} reports zero {@code proposed} AND zero {@code attention}, and every entity's
	 * own {@link #entityGuardStats} reports zero {@code proposed} - i.e. {@link #stillToDecide} is empty.
	 */
	public static boolean readyToLoad(final MatrixModel.Project pProject, final int pBulkThreshold)
	{
		return MatrixCompute.itemsStillToDecide(pProject, pBulkThreshold) == 0;
	}

	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug: the footer's "Load the staging" stayed disabled with no
	 * reason shown): the EXACT count {@link #readyToLoad} gates on, so the footer's reason text can say "N
	 * item(s) still to decide" and always agree with why the link is actually disabled. Since the "left to
	 * decide" list (matrix review queue lot) it is the SIZE of {@link #stillToDecide}: one function feeds the
	 * count and the list, so they cannot disagree. (The earlier {@code proposed + attention} sum counted a
	 * flagged PROPOSED cell twice; the list holds it once.) Previously
	 * the footer reused the BULK PLAN's own {@code leftOut} count instead (a narrower notion: only a row
	 * skipped for being below the confidence threshold), which could read "0 left" while {@link #readyToLoad}
	 * was still false for a plain PROPOSED/flagged row that the bulk plan does not track as "left out" at all.
	 */
	public static int itemsStillToDecide(final MatrixModel.Project pProject, final int pBulkThreshold)
	{
		return MatrixCompute.stillToDecide(pProject, pBulkThreshold).size();
	}

	// ------------------------------------------------------------------------------------------ the "left to decide" list

	public static final String KIND_ENTITY = "ENTITY";
	public static final String KIND_FIELD = "FIELD";
	public static final String KIND_MATCHING_KEY = "MATCHING_KEY";
	public static final String KIND_COMPUTED = "COMPUTED";
	public static final String KIND_SOURCE_LINK = "SOURCE_LINK";
	public static final String KIND_FIELD_LINK = "FIELD_LINK";
	public static final String KIND_RULE = "RULE";
	public static final String KIND_CHECK = "CHECK";

	/** Why an item is still to decide - what the "Accept the N proposals" button did (or will do) with it. */
	public static final String REASON_BULK = "BULK";
	public static final String REASON_BELOW_BAR = "BELOW_BAR";
	public static final String REASON_ATTENTION = "ATTENTION";
	public static final String REASON_COMPUTED = "COMPUTED";
	public static final String REASON_PARENT_FIRST = "PARENT_FIRST";
	public static final String REASON_FIELD_FIRST = "FIELD_FIRST";
	public static final String REASON_SOURCE_FIRST = "SOURCE_FIRST";
	public static final String REASON_DECIDE = "DECIDE";

	/** Selection keys of the non-cell items (a cell is {@code feedId|attributeId}, a computed row's merged cell {@code derived|attributeId}). */
	public static final String KEY_ENTITY = "entity|";
	public static final String KEY_ATTRIBUTE = "attr|";
	public static final String KEY_FEED = "feed|";
	public static final String KEY_GUARD = "guard|";

	/**
	 * One line of the "left to decide" list: something still {@code PROPOSED} that {@link #itemsStillToDecide}
	 * counts. Pure facts only - the page turns {@link #kind} and {@link #reason} into words.
	 */
	public static final class StillItem
	{
		/** Selection key: the {@code data-key} of the cell / row header / column header / guard chip this item points at. */
		public final String key;
		/** {@code KIND_*}. */
		public final String kind;
		public final String entityId;
		public final String entityName;
		/** The {@link ReviewLine} nature to decide it with: {@link ReviewLine#ENTITY}/{@link ReviewLine#ATTRIBUTE}/{@link ReviewLine#FEED}/{@link ReviewLine#MAPPING}, or a guard's own nature. */
		public final String nature;
		/** The row id that nature's decision takes. */
		public final String id;
		/** The field the item belongs to (a field itself, one of its links, one of its guards), or "". */
		public final String attributeId;
		public final String attributeName;
		/** The item's own name in plain words - a field name, "SAP / BUT000", "COL -&gt; field", a guard's label. Never localized. */
		public final String label;
		/** {@code REASON_*}. */
		public final String reason;
		/** Extra words for the reason: the attention text, or the name of the field/source that has to be decided first. May be empty. */
		public final String detail;
		public final Integer confidence;
		/** For a field link: which accept the inspector offers ({@link CellAcceptAction}); null otherwise. */
		public final CellAcceptAction cellAccept;
		/** An {@link ReviewLine#EXTERNAL_SERVICE} check is never accepted from a screen, only set aside or rejected - same as the wizard's card. */
		public final boolean setAside;
		/** The guard behind a rule/check item, or null. */
		public final Guard guard;

		StillItem(final String pKey, final String pKind, final MatrixModel.Entity pEntity, final String pNature, final String pId, final MatrixModel.Attr pAttr,
			final String pLabel, final String pReason, final String pDetail, final Integer pConfidence, final CellAcceptAction pCellAccept, final Guard pGuard)
		{
			this.key = pKey;
			this.kind = pKind;
			this.entityId = pEntity.id;
			this.entityName = pEntity.name;
			this.nature = pNature;
			this.id = pId;
			this.attributeId = pAttr == null ? "" : pAttr.id;
			this.attributeName = pAttr == null ? "" : pAttr.name;
			this.label = pLabel;
			this.reason = pReason;
			this.detail = pDetail == null ? "" : pDetail;
			this.confidence = pConfidence;
			this.cellAccept = pCellAccept;
			this.setAside = pGuard != null && ReviewLine.EXTERNAL_SERVICE.equals(pGuard.nature);
			this.guard = pGuard;
		}

		/** True when the plain "Accept the N proposals" button takes it (or would, were it clicked now): the rest is what needs a person. */
		public boolean takenByTheButton()
		{
			return MatrixCompute.REASON_BULK.equals(this.reason);
		}
	}

	/**
	 * EVERY item still to decide, project-wide, in reading order: per business object, the object itself, its
	 * source links, then field by field (the field, its field links, its guards), then the guards with no field
	 * of their own. The footer's count is this list's size ({@link #itemsStillToDecide}), so the two can never
	 * disagree. What is counted, unchanged: a business object, source link, field (never the technical source
	 * key), field link (a mapping cell of a non-computed field) or guard still {@code PROPOSED}. A guard set
	 * aside ({@link ReviewLine#SET_ASIDE}) is not read into the model at all, so it never counts - the wizard's own rule.
	 * <p>
	 * {@link StillItem#reason} explains why the bulk button ({@link #planProjectBulk}) does not simply take it.
	 */
	public static List<StillItem> stillToDecide(final MatrixModel.Project pProject, final int pBulkThreshold)
	{
		final Set<String> planned = new LinkedHashSet<String>();
		for (final PlannedDecision decision : MatrixCompute.planProjectBulk(pProject, pBulkThreshold).toDecide)
		{
			planned.add(decision.nature + "|" + decision.id);
		}
		final Map<String, MatrixModel.Source> sources = new LinkedHashMap<String, MatrixModel.Source>();
		for (final MatrixModel.Source source : pProject.sources)
		{
			sources.put(source.id, source);
		}
		final List<StillItem> out = new ArrayList<StillItem>();
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			final boolean entityBlocks = ReviewLine.PROPOSED.equals(entity.status) && !planned.contains(ReviewLine.ENTITY + "|" + entity.id);
			if (ReviewLine.PROPOSED.equals(entity.status))
			{
				out.add(new StillItem(MatrixCompute.KEY_ENTITY + entity.id, MatrixCompute.KIND_ENTITY, entity, ReviewLine.ENTITY, entity.id, null, entity.name,
					MatrixCompute.simpleReason(planned, ReviewLine.ENTITY, entity.id, entity.status, entity.confidence, pBulkThreshold), "", entity.confidence, null, null));
			}
			for (final MatrixModel.Feed feed : entity.feeds)
			{
				if (ReviewLine.PROPOSED.equals(feed.status))
				{
					final MatrixModel.Source source = sources.get(feed.sourceId);
					out.add(new StillItem(MatrixCompute.KEY_FEED + feed.id, MatrixCompute.KIND_SOURCE_LINK, entity, ReviewLine.FEED, feed.id, null,
						(source == null ? "?" : source.sourceSystem) + " / " + feed.landingTableName,
						MatrixCompute.simpleReason(planned, ReviewLine.FEED, feed.id, feed.status, feed.confidence, pBulkThreshold), "", feed.confidence, null, null));
				}
			}
			for (final MatrixModel.Attr attr : entity.attrs)
			{
				final boolean flagged = MatrixCompute.attributeFlagged(entity, attr.id);
				final String attention = flagged ? MatrixCompute.attentionDetail(entity, attr.id) : "";
				final boolean computed = attr.isDerived() && !MatrixCompute.isFoldKey(attr);
				final boolean attrPending = ReviewLine.PROPOSED.equals(attr.status);
				final boolean attrPlanned = planned.contains(ReviewLine.ATTRIBUTE + "|" + attr.id);
				if (!attr.sourceKey && !attr.parentLink && attrPending)
				{
					final String kind = MatrixCompute.isFoldKey(attr) ? MatrixCompute.KIND_MATCHING_KEY : attr.isDerived() ? MatrixCompute.KIND_COMPUTED : MatrixCompute.KIND_FIELD;
					String reason;
					String detail = "";
					if (flagged)
					{
						reason = MatrixCompute.REASON_ATTENTION;
						detail = attention;
					}
					else if (computed)
					{
						reason = MatrixCompute.REASON_COMPUTED;
					}
					else if (entityBlocks)
					{
						reason = MatrixCompute.REASON_PARENT_FIRST;
						detail = entity.name;
					}
					else if (attrPlanned)
					{
						reason = MatrixCompute.REASON_BULK;
					}
					else if (WizardRules.bulkEligibility(attr.status, attr.confidence, pBulkThreshold, false).leftOutBelowThreshold)
					{
						reason = MatrixCompute.REASON_BELOW_BAR;
					}
					else if (MatrixCompute.isFoldKey(attr))
					{
						reason = MatrixCompute.REASON_SOURCE_FIRST; // not below the bar, not planned: its source field holds it back
						detail = attr.derivedFrom;
					}
					else
					{
						reason = MatrixCompute.REASON_DECIDE;
					}
					out.add(new StillItem(MatrixCompute.KEY_ATTRIBUTE + attr.id, kind, entity, ReviewLine.ATTRIBUTE, attr.id, attr, attr.name, reason, detail, attr.confidence, null,
						null));
				}
				for (final MatrixModel.Feed feed : entity.feeds)
				{
					final MatrixModel.Cell cell = feed.cellsByAttributeId.get(attr.id);
					if (!MatrixModel.PROPOSED.equals(MatrixCompute.effectiveStatus(attr, feed)) || cell == null)
					{
						continue;
					}
					final MatrixModel.Source source = sources.get(feed.sourceId);
					String reason;
					String detail = "";
					if (flagged)
					{
						reason = MatrixCompute.REASON_ATTENTION;
						detail = cell.isFlagged() && !cell.attentionReason.isEmpty() ? cell.attentionReason : attention;
					}
					else if (entityBlocks)
					{
						reason = MatrixCompute.REASON_PARENT_FIRST;
						detail = entity.name;
					}
					else if (WizardRules.bulkEligibility(cell.status, cell.confidence, pBulkThreshold, false).leftOutBelowThreshold)
					{
						reason = MatrixCompute.REASON_BELOW_BAR;
					}
					else if (attrPending && !attrPlanned)
					{
						reason = MatrixCompute.REASON_FIELD_FIRST;
						detail = attr.name;
					}
					else if (cell.mappingId != null && planned.contains(ReviewLine.MAPPING + "|" + cell.mappingId))
					{
						reason = MatrixCompute.REASON_BULK;
					}
					else
					{
						reason = MatrixCompute.REASON_DECIDE;
					}
					out.add(new StillItem(feed.id + "|" + attr.id, MatrixCompute.KIND_FIELD_LINK, entity, ReviewLine.MAPPING, cell.mappingId == null ? "" : cell.mappingId, attr,
						(cell.landingColumn == null ? "?" : cell.landingColumn) + " \u2192 " + attr.name + (source == null ? "" : " (" + source.sourceSystem + ")"), reason, detail,
						cell.confidence, MatrixCompute.cellAcceptAction(entity.status, attr.status), null));
				}
				for (final Guard guard : attr.guards)
				{
					// integration (2026-09-30): the function of a computed field still PROPOSED is decided WITH the field
					// (fix/matrix-computed-field) - one line for the pair, never a second one for the function
					// UX lot U6: a suggested look-up through an external service is optional - never an item that "needs you" nor a
					// reason "Load the staging" stays closed; it is listed apart ({@link #optionalServices}), grouped per field
					if (ReviewLine.PROPOSED.equals(guard.status) && !MatrixCompute.isOptionalService(guard) && !MatrixCompute.decidedWithItsField(attr, guard))
					{
						final String reason = flagged ? MatrixCompute.REASON_ATTENTION : computed ? MatrixCompute.REASON_COMPUTED
							: MatrixCompute.guardReason(planned, guard, pBulkThreshold);
						out.add(MatrixCompute.guardItem(entity, attr, guard, reason, flagged ? attention : ""));
					}
				}
			}
			for (final Guard guard : entity.entityGuards)
			{
				if (ReviewLine.PROPOSED.equals(guard.status) && !MatrixCompute.isOptionalService(guard))
				{
					out.add(MatrixCompute.guardItem(entity, null, guard, MatrixCompute.guardReason(planned, guard, pBulkThreshold), ""));
				}
			}
		}
		return out;
	}

	/** UX lot U6: a connector look-up suggestion ({@link ReviewLine#EXTERNAL_SERVICE}) - never decided to move on, never blocking. */
	public static boolean isOptionalService(final Guard pGuard)
	{
		return ReviewLine.EXTERNAL_SERVICE.equals(pGuard.nature);
	}

	/**
	 * UX lot U6 (docs/UX-MATRIX-QUEUE.md): every still-{@code PROPOSED} look-up suggestion of ONE target field (or of one business object when the
	 * suggestion names no field), the unit the queue shows as ONE line ("4 services could check Adresse.street") with the candidates inside. Measured
	 * on UxCrmSf: 8 of the 16 leftover items were such suggestions, each decided one by one with the same weight as a flagged field.
	 */
	public static final class ServiceGroup
	{
		public final String entityId;
		public final String entityName;
		/** "" for a suggestion with no field of its own. */
		public final String attributeId;
		public final String attributeName;
		/** One {@link StillItem} per candidate (kind {@link #KIND_CHECK}, {@link StillItem#setAside} true), in the order the model lists them. */
		public final List<StillItem> items = new ArrayList<StillItem>();

		ServiceGroup(final MatrixModel.Entity pEntity, final MatrixModel.Attr pAttr)
		{
			this.entityId = pEntity.id;
			this.entityName = pEntity.name;
			this.attributeId = pAttr == null ? "" : pAttr.id;
			this.attributeName = pAttr == null ? "" : pAttr.name;
		}

		/** Candidates that need no further set-up ({@link Guard#serviceReady} not false). */
		public int readyCount()
		{
			int count = 0;
			for (final StillItem item : this.items)
			{
				if (item.guard != null && !Boolean.FALSE.equals(item.guard.serviceReady))
				{
					count++;
				}
			}
			return count;
		}

		/** The guard ids of every candidate - what "Set aside all" / "Reject all" post. */
		public List<String> guardIds()
		{
			final List<String> ids = new ArrayList<String>();
			for (final StillItem item : this.items)
			{
				ids.add(item.id);
			}
			return ids;
		}
	}

	/** The optional look-up suggestions still to decide, grouped per target field, in reading order (business object, then field). Empty when none. */
	public static List<ServiceGroup> optionalServices(final MatrixModel.Project pProject)
	{
		final List<ServiceGroup> out = new ArrayList<ServiceGroup>();
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			final Map<String, ServiceGroup> byField = new LinkedHashMap<String, ServiceGroup>();
			for (final MatrixModel.Attr attr : entity.attrs)
			{
				for (final Guard guard : attr.guards)
				{
					if (ReviewLine.PROPOSED.equals(guard.status) && MatrixCompute.isOptionalService(guard))
					{
						ServiceGroup group = byField.get(attr.id);
						if (group == null)
						{
							group = new ServiceGroup(entity, attr);
							byField.put(attr.id, group);
						}
						group.items.add(MatrixCompute.guardItem(entity, attr, guard, MatrixCompute.REASON_DECIDE, ""));
					}
				}
			}
			ServiceGroup entityLevel = null;
			for (final Guard guard : entity.entityGuards)
			{
				if (ReviewLine.PROPOSED.equals(guard.status) && MatrixCompute.isOptionalService(guard))
				{
					if (entityLevel == null)
					{
						entityLevel = new ServiceGroup(entity, null);
					}
					entityLevel.items.add(MatrixCompute.guardItem(entity, null, guard, MatrixCompute.REASON_DECIDE, ""));
				}
			}
			out.addAll(byField.values());
			if (entityLevel != null)
			{
				out.add(entityLevel);
			}
		}
		return out;
	}

	/** How many candidates the groups hold in all - the "+ N optional suggestions" of the footer. */
	public static int optionalSuggestionCount(final List<ServiceGroup> pGroups)
	{
		int count = 0;
		for (final ServiceGroup group : pGroups)
		{
			count += group.items.size();
		}
		return count;
	}

	/**
	 * Whether the screen opens with "Only what's left to do" ON (UX lot U6): at least one proposal is already decided AND something is left to do (an
	 * empty grid would be a blank page). The person's own choice, remembered by the browser per user and project, overrides this default.
	 */
	public static boolean defaultOnlyTodo(final MatrixModel.Project pProject, final int pItemsLeft)
	{
		return pItemsLeft > 0 && MatrixCompute.hasDecidedProposal(pProject);
	}

	/** True when any business object, field, source link or field link of the project is no longer {@code PROPOSED} (accepted, rejected, applied). */
	public static boolean hasDecidedProposal(final MatrixModel.Project pProject)
	{
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			if (MatrixCompute.isDecided(entity.status))
			{
				return true;
			}
			for (final MatrixModel.Attr attr : entity.attrs)
			{
				if (!attr.sourceKey && !attr.parentLink && MatrixCompute.isDecided(attr.status))
				{
					return true;
				}
			}
			for (final MatrixModel.Feed feed : entity.feeds)
			{
				if (MatrixCompute.isDecided(feed.status))
				{
					return true;
				}
				for (final MatrixModel.Cell cell : feed.cellsByAttributeId.values())
				{
					if (cell.isData() && MatrixCompute.isDecided(cell.status))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean isDecided(final String pStatus)
	{
		return ReviewLine.ACCEPTED.equals(pStatus) || ReviewLine.REJECTED.equals(pStatus) || ReviewLine.APPLIED.equals(pStatus);
	}

	private static StillItem guardItem(final MatrixModel.Entity pEntity, final MatrixModel.Attr pAttr, final Guard pGuard, final String pReason, final String pDetail)
	{
		return new StillItem(MatrixCompute.KEY_GUARD + pGuard.id, ReviewLine.QUALITY.equals(pGuard.nature) ? MatrixCompute.KIND_RULE : MatrixCompute.KIND_CHECK, pEntity,
			pGuard.nature, pGuard.id, pAttr, pGuard.label(), pReason, pDetail, pGuard.confidence, null, pGuard);
	}

	private static String guardReason(final Set<String> pPlanned, final Guard pGuard, final int pThreshold)
	{
		return MatrixCompute.simpleReason(pPlanned, pGuard.nature, pGuard.id, pGuard.status, pGuard.confidence, pThreshold);
	}

	/** Planned (the button takes it) / below the bar / else a plain "decide it". */
	private static String simpleReason(final Set<String> pPlanned, final String pNature, final String pId, final String pStatus, final Integer pConfidence, final int pThreshold)
	{
		if (pPlanned.contains(pNature + "|" + pId))
		{
			return MatrixCompute.REASON_BULK;
		}
		return WizardRules.bulkEligibility(pStatus, pConfidence, pThreshold, false).leftOutBelowThreshold ? MatrixCompute.REASON_BELOW_BAR : MatrixCompute.REASON_DECIDE;
	}

	/** The first flagged cell's own "why" of the field - what a field-level item quotes after "needs your eye". May be empty. */
	private static String attentionDetail(final MatrixModel.Entity pEntity, final String pAttributeId)
	{
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			final MatrixModel.Cell cell = feed.cellsByAttributeId.get(pAttributeId);
			if (cell != null && cell.isFlagged() && !cell.attentionReason.isEmpty())
			{
				return cell.attentionReason;
			}
		}
		return "";
	}

	/** How many items of {@code pItems} the plain bulk button does NOT take - the ones that "need you". */
	public static int needingAPerson(final List<StillItem> pItems)
	{
		int count = 0;
		for (final StillItem item : pItems)
		{
			if (!item.takenByTheButton())
			{
				count++;
			}
		}
		return count;
	}

	/** How many items belong to one business object - the tab's own counter. */
	public static int countOfEntity(final List<StillItem> pItems, final String pEntityId)
	{
		int count = 0;
		for (final StillItem item : pItems)
		{
			if (item.entityId.equals(pEntityId))
			{
				count++;
			}
		}
		return count;
	}

	/** The first item of one business object, or null. */
	public static StillItem firstOfEntity(final List<StillItem> pItems, final String pEntityId)
	{
		for (final StillItem item : pItems)
		{
			if (item.entityId.equals(pEntityId))
			{
				return item;
			}
		}
		return null;
	}

	/**
	 * The item to select after a decision: the first of {@code pFollowingKeys} (the keys that came after the
	 * decided one, in the order the page showed them) still in {@code pItems}; else the first item of all; null
	 * when nothing is left. A decided item that is still in the list (refused, or only part of a cascade) is
	 * kept: the person stays on it.
	 */
	public static StillItem nextAfterDecision(final List<StillItem> pItems, final String pDecidedKey, final List<String> pFollowingKeys)
	{
		for (final StillItem item : pItems)
		{
			if (item.key.equals(pDecidedKey))
			{
				return item;
			}
		}
		if (pFollowingKeys != null)
		{
			for (final String key : pFollowingKeys)
			{
				for (final StillItem item : pItems)
				{
					if (item.key.equals(key))
					{
						return item;
					}
				}
			}
		}
		return pItems.isEmpty() ? null : pItems.get(0);
	}

	/** The keys that followed {@code pKey} in {@code pRendered} (all of them when {@code pKey} was not in it); an empty list when nothing was rendered yet. */
	public static List<String> keysAfter(final List<String> pRendered, final String pKey)
	{
		final List<String> out = new ArrayList<String>();
		if (pRendered == null)
		{
			return out;
		}
		final int at = pKey == null ? -1 : pRendered.indexOf(pKey);
		out.addAll(pRendered.subList(at + 1, pRendered.size()));
		return out;
	}

	/** Whether {@code pKey} names something of this business object: its tab, a field row, a source link column, a link cell, a computed row's cell or a guard. */
	public static boolean ownsKey(final MatrixModel.Entity pEntity, final String pKey)
	{
		if (pKey == null || pKey.isEmpty())
		{
			return false;
		}
		if ((MatrixCompute.KEY_ENTITY + pEntity.id).equals(pKey))
		{
			return true;
		}
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			if ((MatrixCompute.KEY_FEED + feed.id).equals(pKey))
			{
				return true;
			}
		}
		final List<Guard> guards = new ArrayList<Guard>(pEntity.entityGuards);
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			if ((MatrixCompute.KEY_ATTRIBUTE + attr.id).equals(pKey) || ("derived|" + attr.id).equals(pKey))
			{
				return true;
			}
			for (final MatrixModel.Feed feed : pEntity.feeds)
			{
				if ((feed.id + "|" + attr.id).equals(pKey))
				{
					return true;
				}
			}
			guards.addAll(attr.guards);
		}
		for (final Guard guard : guards)
		{
			if ((MatrixCompute.KEY_GUARD + guard.id).equals(pKey))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the row of {@code pAttr} still has something to do - the "Only what's left to do" filter keeps it: the
	 * field itself, any of its link cells or any of its guards is in {@code pItems}, or a mandatory field is still
	 * not covered by some source (a MISSING cell - not an item, but plainly left to do).
	 */
	public static boolean rowHasWork(final MatrixModel.Entity pEntity, final MatrixModel.Attr pAttr, final List<StillItem> pItems)
	{
		for (final StillItem item : pItems)
		{
			if (item.entityId.equals(pEntity.id) && item.attributeId.equals(pAttr.id))
			{
				return true;
			}
		}
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			if (MatrixModel.MISSING.equals(MatrixCompute.effectiveStatus(pAttr, feed)))
			{
				return true;
			}
		}
		return false;
	}

	private static void addIfEligible(final BulkPlan pPlan, final String pNature, final String pId, final String pStatus, final Integer pConfidence,
		final int pThreshold)
	{
		final WizardRules.BulkEligibility eligibility = WizardRules.bulkEligibility(pStatus, pConfidence, pThreshold, false);
		if (eligibility.eligible)
		{
			pPlan.toDecide.add(new PlannedDecision(pNature, pId));
		}
		else if (eligibility.leftOutBelowThreshold)
		{
			pPlan.leftOut++;
			pPlan.lowestLeftOutConfidence = Math.min(pPlan.lowestLeftOutConfidence, pConfidence == null ? 0 : pConfidence.intValue());
		}
	}

	/**
	 * The guidance banner's one-sentence summary (delta 5): counts of business objects, sources, field
	 * links (data cells whose status is ACCEPTED/PROPOSED/REJECTED), rules, checks and "points that need
	 * your eye". A "point" here is one distinct (entity, attribute) pair with at least one flagged cell -
	 * counted once per field, not once per source, even when several sources flag the same field
	 * (documented simplification: the mockup's own worked example groups by ISSUE, not by field; this is a
	 * field-level approximation of that, see docs/STAGING-PROPOSAL.md).
	 */
	public static final class ProjectSummary
	{
		public int businessObjects;
		public int sources;
		public int fieldLinks;
		public int rules;
		public int checks;
		public int attentionPoints;
	}

	public static ProjectSummary summarize(final MatrixModel.Project pProject)
	{
		final ProjectSummary out = new ProjectSummary();
		out.businessObjects = pProject.entities.size();
		out.sources = pProject.sources.size();
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			final GuardStats guardStats = MatrixCompute.entityGuardStats(entity);
			out.rules += guardStats.rules;
			out.checks += guardStats.checks;
			for (final MatrixModel.Attr attr : entity.attrs)
			{
				if (MatrixCompute.attributeFlagged(entity, attr.id))
				{
					out.attentionPoints++;
				}
			}
			for (final MatrixModel.Feed feed : entity.feeds)
			{
				for (final MatrixModel.Cell cell : feed.cellsByAttributeId.values())
				{
					if (cell.isData())
					{
						out.fieldLinks++;
					}
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------- overview layout

	/** One node's screen position, in the same unit the SVG viewBox uses. */
	public static final class Point
	{
		public final double x;
		public final double y;

		public Point(final double pX, final double pY)
		{
			this.x = pX;
			this.y = pY;
		}
	}

	/**
	 * Evenly spaced row positions for {@code pCount} cards between {@code pTop} and {@code pTop + pCount *
	 * pRowHeight}, generalising the mockup's fixed {@code srcY(i) = 44 + i * 46} to any source/entity count
	 * (docs/MAPPING-MATRIX.md: "must work for 1 to ~8 sources and 1 to ~6 entities").
	 */
	public static double[] rowPositions(final int pCount, final double pTop, final double pRowHeight)
	{
		final double[] out = new double[Math.max(0, pCount)];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = pTop + i * pRowHeight + pRowHeight / 2.0;
		}
		return out;
	}

	/** Cubic Bezier point at {@code pT} in [0,1] between the four control values - same formula as the mockup's own {@code bez}. */
	public static double bezier(final double pT, final double pA, final double pB, final double pC, final double pD)
	{
		final double u = 1 - pT;
		return u * u * u * pA + 3 * u * u * pT * pB + 3 * u * pT * pT * pC + pT * pT * pT * pD;
	}

	/** The "M x1 y1 C cx y1 cx y2 x2 y2" path the mockup draws for one source -&gt; entity curve - a horizontal S-curve, control points on the vertical midline. */
	public static String curvePath(final double pX1, final double pY1, final double pX2, final double pY2)
	{
		final double cx = (pX1 + pX2) / 2.0;
		return "M " + MatrixCompute.fmt(pX1) + " " + MatrixCompute.fmt(pY1) + " C " + MatrixCompute.fmt(cx) + " " + MatrixCompute.fmt(pY1) + " "
			+ MatrixCompute.fmt(cx) + " " + MatrixCompute.fmt(pY2) + " " + MatrixCompute.fmt(pX2) + " " + MatrixCompute.fmt(pY2);
	}

	/** The chip's own position along the curve, {@code pT} of the way from source to entity - matches {@link #curvePath}'s control points. */
	public static Point curveChipPoint(final double pX1, final double pY1, final double pX2, final double pY2, final double pT)
	{
		final double cx = (pX1 + pX2) / 2.0;
		return new Point(MatrixCompute.bezier(pT, pX1, cx, cx, pX2), MatrixCompute.bezier(pT, pY1, pY1, pY2, pY2));
	}

	/**
	 * fix/mapping-matrix-live (2026-09-24): {@code curveChipPoint} existed but was never called - no status
	 * chip was ever drawn on a curve. This is the missing "which {@code t}" half: one distinct position per
	 * ENTITY (never per source - two sources' curves converging on the SAME entity already separate
	 * vertically on their own, from their different {@link #rowPositions} row), spread across
	 * {@value #CHIP_T_MIN}..{@value #CHIP_T_MAX} of the curve so chips of DIFFERENT entities never land at
	 * the same depth and collide, whatever the entity count (the mockup's own fixed {@code t=0.25}/{@code
	 * t=0.45} for its fixed 2 entities, generalised). A single entity uses the midpoint of the range.
	 */
	private static final double CHIP_T_MIN = 0.22;
	private static final double CHIP_T_MAX = 0.62;

	public static double chipT(final int pEntityIndex, final int pEntityCount)
	{
		if (pEntityCount <= 1)
		{
			return (MatrixCompute.CHIP_T_MIN + MatrixCompute.CHIP_T_MAX) / 2.0;
		}
		final double fraction = ((double) pEntityIndex) / (pEntityCount - 1);
		return MatrixCompute.CHIP_T_MIN + fraction * (MatrixCompute.CHIP_T_MAX - MatrixCompute.CHIP_T_MIN);
	}

	/**
	 * Live fix (bug 7, docs/STAGING-PROPOSAL.md "live fixes"): {@link #chipT(int, int)} alone gives every
	 * SOURCE feeding the same entity the identical {@code t}, hence the identical x - measured live on
	 * project Personnes (2 sources, 2 entities): "both Personne curves' chips sit at the same x, the Adresse
	 * ones overlap each other and the curves' text". The per-entity assumption ("two sources' curves
	 * converging on the same entity already separate vertically on their own, from their different row") does
	 * not hold up close to where the curves converge - this overload additionally spreads each SOURCE's chip
	 * within its entity's own band (never crossing into a neighbouring entity's band: the jitter is capped at
	 * 35% of the spacing between two consecutive entities' base {@code t}, so the total per-entity spread
	 * never exceeds 70% of that spacing), so two sources feeding the same entity get both a different x AND a
	 * more different y (the Bezier's y weighting on the source's own row changes with {@code t} too).
	 * {@code pSourceCount <= 1} returns the plain entity base, unchanged.
	 */
	private static final double CHIP_SOURCE_JITTER_MAX = 0.06;
	private static final double CHIP_SOURCE_JITTER_ENTITY_SPACING_FRACTION = 0.35;

	public static double chipT(final int pEntityIndex, final int pEntityCount, final int pSourceIndex, final int pSourceCount)
	{
		final double base = MatrixCompute.chipT(pEntityIndex, pEntityCount);
		if (pSourceCount <= 1)
		{
			return base;
		}
		final double entitySpacing = pEntityCount <= 1 ? (MatrixCompute.CHIP_T_MAX - MatrixCompute.CHIP_T_MIN)
			: (MatrixCompute.CHIP_T_MAX - MatrixCompute.CHIP_T_MIN) / (pEntityCount - 1);
		final double maxJitter = Math.min(MatrixCompute.CHIP_SOURCE_JITTER_MAX, entitySpacing * MatrixCompute.CHIP_SOURCE_JITTER_ENTITY_SPACING_FRACTION);
		final double centered = ((double) pSourceIndex) / (pSourceCount - 1) - 0.5; // -0.5..0.5
		return base + centered * 2.0 * maxJitter;
	}

	private static String fmt(final double pValue)
	{
		final long rounded = Math.round(pValue);
		return String.valueOf(rounded);
	}
}
