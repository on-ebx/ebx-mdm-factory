package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders wizard step 6's "Matching results" block (task brief, wizard-matching-results lot, 2026-09-24;
 * mockup {@code docs/mockups/wizard-step6-matching.dc.html}): KPI cards, the "where golden records come
 * from" band, and the suspect queue (To decide / Merged / Different tabs, a pair's per-field comparison and
 * decide actions). Pure: no EBX class anywhere in this file - {@code wizard.WizardPage} calls this from
 * {@code renderStep6}, below the existing plan cards and run buttons (task brief: those stay untouched).
 * <p>
 * <b>Deliberately NOT a single-page-app re-render.</b> Every tab and every pending pair's detail panel is
 * rendered UP FRONT (bounded - see {@link Input#maxDetailPairs}) and switched purely client-side (a small
 * script, {@link #script()}, injected once by {@code ebx.ui.ProjectWizardUserService} the same way {@code
 * review.MatchPlanView#treeScript()} already is) - a real postback only happens for an actual decision
 * (merge/reject/bulk-merge), never for browsing. {@link Input#selectedTab}/{@link Input#selectedPairId} are
 * what the LAST postback (a decision, or none yet) left selected - re-applied by the script on load so a
 * decision's own repaint does not silently jump back to the first tab/pair (task item 7).
 * <p>
 * <b>No fabricated "golden record preview" panel.</b> The mockup shows one; this implementation deliberately
 * leaves it out (see {@code docs/WIZARD-MATCHING-RESULTS.md}, "Simplifications"): no add-on API was found
 * (this session) that exposes the merge policy's per-field survivor function to plain Java code (only the
 * chain's raw configuration is readable through the {@code match-merge} MCP server, not from inside a
 * {@code UserService}), and the task brief itself says to never fake a winner. The two raw values are still
 * shown, side by side, in the field-comparison table below the pair header - a person can judge survivorship
 * from those directly, and the merged record's real, final values are always visible on the master record
 * itself once a merge actually runs.
 */
public final class MatchResultsView
{
	private MatchResultsView()
	{
	}

	// ------------------------------------------------------------------------------------------------------- input

	public static final class Input
	{
		public String entityId = "";
		public String entityName = "";
		public MatchStats.Kpis kpis;
		public List<MatchStats.BandSegment> band = new ArrayList<MatchStats.BandSegment>();
		/** Source code -> display colour (hex) - {@link MatchStats#MULTIPLE} maps to the reserved golden/accent colour; a code absent here falls back to a neutral grey. */
		public Map<String, String> colorBySource;
		/** Preformatted, already-localized - null when no run has ever happened for this entity. */
		public String lastRunText;
		/** UX lot C2: the run's raw log text, shown under a "Details" fold below {@link #lastRunText} (null/empty: nothing to fold). */
		public String lastRunDetails;
		/** One preformatted, already-localized line per master entity that has no {@code MatchPlan} of its own - task item 1's "Adresse: related table..." line, or "no matching configured" when no relation explains it. */
		public List<String> followsParentLines = new ArrayList<String>();
		public List<MatchStats.SuspectPair> pendingPairs = new ArrayList<MatchStats.SuspectPair>();
		/**
		 * Step 6 queue per group (2026-10-18): the suspects as groups of records (the connected components of the suspect pairs), ordered by size then name, at most the number the
		 * snapshot budget kept. A snapshot stored before this lot has only {@link #pendingPairs}: each is then shown as a group of two.
		 */
		public List<MatchStats.SuspectGroup> pendingGroups = new ArrayList<MatchStats.SuspectGroup>();
		/** The real number of groups before any capping (the figure of the tab and of "N more not shown"); 0 when unknown (a snapshot stored before groups). */
		public int pendingGroupsTotal;
		public List<GroupSummary> mergedGroups = new ArrayList<GroupSummary>();
		public List<DistinctPair> distinctPairs = new ArrayList<DistinctPair>();
		/** Real total before any capping - shown as "N more not shown" when {@link #pendingPairs} was capped by the caller. */
		public int pendingPairsTotal;
		/** How many of {@link #pendingPairs} (in list order) carry a decision-tree evaluation ({@link MatchStats.SuspectPair#id} in {@link #treeDetail}) - the rest show the plain field-comparison table only. */
		public int maxDetailPairs;
		/** Keyed by {@link MatchStats.SuspectPair#id} - present only for the first {@link #maxDetailPairs} pending pairs. Never null values; a pair with no entry here simply has no extra decision-tree detail. */
		public Map<String, TreeDetail> treeDetail;
		/** {@link MatchStats#PENDING}/{@link MatchStats#MERGED}/{@link MatchStats#DISTINCT} - which tab the last postback left selected. */
		public String selectedTab = MatchStats.PENDING;
		public String selectedPairId = "";
		/** Bulk-merge threshold (percent) - the shared {@code mdmfBulkThreshold} field the rest of the wizard already posts. */
		public int bulkThresholdPercent = 80;
		/** True when the current session may decide a suspect pair (administrator, or whatever gate {@code ebx.ui.ProjectWizardUserService} already applies to {@code MATCHING_RUN}) - decide buttons render disabled with a hint otherwise. */
		public boolean canDecide;
		/** Non-null when the LAST decision this postback attempted failed - shown as a notice above the queue. */
		public String lastDecisionError;
		/**
		 * Wave 2 (2026-09-30): goldens that still carry a suspect flag - NOT a pair count (a pair is flagged on both
		 * of its records, so this is up to twice {@link #pendingPairsTotal}, less when a record has several pairs).
		 * Kept only so a caller that wants the figure can name it for what it is; the view itself shows pairs.
		 */
		public long goldensWithSuspects;
		/** {@link MatchResultsSnapshot#FRESH}/{@link MatchResultsSnapshot#STALE}/{@link MatchResultsSnapshot#MISSING}: where the numbers of this block come from - never computed while rendering. */
		public String snapshotState = MatchResultsSnapshot.FRESH;
		/** True while a refresh job of this entity is queued or running - the view then says so instead of offering the refresh button. */
		public boolean refreshRunning;
		/**
		 * The nodes of the entity's plan, by the name the engine gave them ({@link TreeDetail#lastNodeName}): the pair card says which rule produced the pair. Filled by the caller from the plan
		 * ({@code review.MatchPlanFacts}) at each display - never stored. Null/empty: the card names the node only.
		 */
		public Map<String, NodeInfo> planNodes;
		/** Master field name to the attribute's label, for the rule's wording ("même valeur sur e-mail, téléphone"). Null/empty: the field names themselves. */
		public Map<String, String> fieldLabels;
		/** True when the pair cards are left out of this render (another step is the one shown): a placeholder that reloads the step takes their place - see {@code wizard.js} loadDeferredStep. */
		public boolean deferPairs;
	}

	public static final class GroupSummary
	{
		public final String goldenKey;
		public final String goldenLabel;
		public final String goldenSource;
		/** One entry per member: {@code sourceSystem} display code. */
		public final List<String> memberSources;
		public final List<String> memberKeys;

		public GroupSummary(final String pGoldenKey, final String pGoldenLabel, final String pGoldenSource, final List<String> pMemberSources,
			final List<String> pMemberKeys)
		{
			this.goldenKey = pGoldenKey;
			this.goldenLabel = pGoldenLabel;
			this.goldenSource = pGoldenSource;
			this.memberSources = pMemberSources == null ? new ArrayList<String>() : pMemberSources;
			this.memberKeys = pMemberKeys == null ? new ArrayList<String>() : pMemberKeys;
		}
	}

	public static final class DistinctPair
	{
		public final String sourceId;
		public final String targetId;
		public final String decidedBy;
		public final String decidedAt;

		public DistinctPair(final String pSourceId, final String pTargetId, final String pDecidedBy, final String pDecidedAt)
		{
			this.sourceId = pSourceId;
			this.targetId = pTargetId;
			this.decidedBy = pDecidedBy;
			this.decidedAt = pDecidedAt;
		}
	}

	/** One node of the plan, reduced to what the pair card says about the rule that produced a pair. */
	public static final class NodeInfo
	{
		public final String id;
		/** {@code ALL_FIELDS} / {@code AT_LEAST_N} / {@code AVERAGE} / {@code PREDICATE}. */
		public final String function;
		public final Integer minimumScore;
		public final Integer minimumMatches;
		/** The master field names the node compares. */
		public final List<String> fields;
		/** The node sends a pair to a person when it passes / when it fails (decides the wording: "same value on X" or "not enough on X"). */
		public final boolean suspectOnTrue;
		public final boolean suspectOnFalse;

		public NodeInfo(final String pId, final String pFunction, final Integer pMinimumScore, final Integer pMinimumMatches, final List<String> pFields,
			final boolean pSuspectOnTrue, final boolean pSuspectOnFalse)
		{
			this.id = pId;
			this.function = pFunction;
			this.minimumScore = pMinimumScore;
			this.minimumMatches = pMinimumMatches;
			this.fields = pFields == null ? new ArrayList<String>() : pFields;
			this.suspectOnTrue = pSuspectOnTrue;
			this.suspectOnFalse = pSuspectOnFalse;
		}
	}

	/** The add-on's own decision-tree evaluation for one pending pair, already reduced to plain strings ({@code ebx.MameMatchingApi.TreeEvaluation}) - see class Javadoc, "unit not confirmed". */
	public static final class TreeDetail
	{
		public final boolean available;
		/** Shown as-is when {@link #available} is false - never a record value, see {@code MameMatchingApi} class Javadoc. */
		public final String unavailableReason;
		public final List<String> nodeNames;
		public final String lastNodeName;
		public final List<EngineFieldScore> fieldScores;

		public TreeDetail(final boolean pAvailable, final String pUnavailableReason, final List<String> pNodeNames, final String pLastNodeName,
			final List<EngineFieldScore> pFieldScores)
		{
			this.available = pAvailable;
			this.unavailableReason = pUnavailableReason;
			this.nodeNames = pNodeNames == null ? new ArrayList<String>() : pNodeNames;
			this.lastNodeName = pLastNodeName;
			this.fieldScores = pFieldScores == null ? new ArrayList<EngineFieldScore>() : pFieldScores;
		}
	}

	public static final class EngineFieldScore
	{
		public final String field;
		public final double score;

		public EngineFieldScore(final String pField, final double pScore)
		{
			this.field = pField;
			this.score = pScore;
		}
	}

	// ------------------------------------------------------------------------------------------------------ render

	public static String render(final Input pIn, final Locale pLocale)
	{
		final StringBuilder out = new StringBuilder();
		out.append("<div class=\"mdmf-match-results\" id=\"mdmf-match-results-").append(MatchResultsView.esc(pIn.entityId)).append("\" data-mdmf-match-entity=\"")
			.append(MatchResultsView.esc(pIn.entityId)).append("\">");
		// board MatchingResults.dc.html: a section head (title + one-line lead), not a small subtitle
		out.append("<h2 class=\"mdmf-match-results-title\">").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.title")))
			.append(pIn.entityName == null || pIn.entityName.isEmpty() ? "" : " \u00B7 " + MatchResultsView.esc(pIn.entityName)).append("</h2>");
		out.append("<p class=\"mdmf-match-results-lead\">").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.lead"))).append("</p>");

		if (pIn.lastDecisionError != null)
		{
			out.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(MatchResultsView.esc(pIn.lastDecisionError)).append("</div>");
		}

		if (!pIn.followsParentLines.isEmpty())
		{
			for (final String line : pIn.followsParentLines)
			{
				out.append("<p class=\"mdmf-hint mdmf-match-follows\">").append(MatchResultsView.esc(line)).append("</p>");
			}
		}
		if (pIn.lastRunText != null)
		{
			out.append("<p class=\"mdmf-hint\">").append(MatchResultsView.esc(pIn.lastRunText)).append("</p>");
			if (pIn.lastRunDetails != null && !pIn.lastRunDetails.isEmpty())
			{
				out.append("<details class=\"mdmf-notice-details mdmf-ma-details\"><summary>").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.lastRun.details")))
					.append("</summary><div class=\"mdmf-hint\">").append(MatchResultsView.esc(pIn.lastRunDetails)).append("</div></details>");
			}
		}

		if (MatchResultsSnapshot.MISSING.equals(pIn.snapshotState))
		{
			// nothing stored for this entity (never run, or the last run has no results yet): say so, never compute it here
			MatchResultsView.renderSnapshotNotice(out, pIn, pLocale, "results.snapshot.missing");
			out.append("</div>");
			return out.toString();
		}
		if (MatchResultsSnapshot.STALE.equals(pIn.snapshotState))
		{
			MatchResultsView.renderSnapshotNotice(out, pIn, pLocale, "results.snapshot.stale");
		}

		MatchResultsView.renderKpis(out, pIn, pLocale);
		MatchResultsView.renderBand(out, pIn, pLocale);
		MatchResultsView.renderQueue(out, pIn, pLocale);

		out.append("</div>");
		return out.toString();
	}

	/** The "figures are missing / being refreshed" line and, unless a refresh is already queued or running, the button that queues one (MATCH_RESULTS_REFRESH, administrators only - same gate as a decision). */
	private static void renderSnapshotNotice(final StringBuilder pOut, final Input pIn, final Locale pLocale, final String pKey)
	{
		pOut.append("<div class=\"mdmf-notice mdmf-notice-target mdmf-match-snapshot-notice\"><span>")
			.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, pIn.refreshRunning ? "results.snapshot.refreshing" : pKey))).append("</span>");
		if (!pIn.refreshRunning)
		{
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"MATCH_RESULTS_REFRESH\" data-mdmf-id=\"")
				.append(MatchResultsView.esc(pIn.entityId)).append('"').append(pIn.canDecide ? "" : " disabled=\"disabled\"").append('>')
				.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.snapshot.refresh"))).append("</button>");
		}
		pOut.append("</div>");
	}

	private static void renderKpis(final StringBuilder pOut, final Input pIn, final Locale pLocale)
	{
		final MatchStats.Kpis k = pIn.kpis;
		pOut.append("<div class=\"mdmf-match-kpis\">");
		pOut.append(MatchResultsView.kpiCard(String.valueOf(k == null ? 0 : k.totalRecords), MatchResultsMessages.get(pLocale, "results.kpi.records"),
			MatchResultsView.sourceBreakdown(k, pLocale)));
		pOut.append(MatchResultsView.kpiCard(String.valueOf(k == null ? 0 : k.goldenRecords), MatchResultsMessages.get(pLocale, "results.kpi.golden"), ""));
		pOut.append(MatchResultsView.kpiCard(String.valueOf(k == null ? 0 : k.mergedGroups), MatchResultsMessages.get(pLocale, "results.kpi.merged"), ""));
		pOut.append(MatchResultsView.kpiCard(String.valueOf(k == null ? 0 : k.pendingSuspectPairs), MatchResultsMessages.get(pLocale, "results.kpi.pending"), ""));
		pOut.append(MatchResultsView.kpiCard(String.valueOf(k == null ? 0 : k.distinctPairs), MatchResultsMessages.get(pLocale, "results.kpi.distinct"), ""));
		pOut.append("</div>");
		if (k != null && k.unmatchedRecords > 0)
		{
			// lot M4 (2026-10-03, defect 3): the table is bigger than what the last run covered - say it plainly, never let the totals pass for "all matched"
			pOut.append("<p class=\"mdmf-hint mdmf-match-unmatched\">")
				.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.kpi.unmatched", String.valueOf(k.unmatchedRecords), String.valueOf(k.totalRecords))))
				.append("</p>");
		}
	}

	private static String sourceBreakdown(final MatchStats.Kpis pKpis, final Locale pLocale)
	{
		if (pKpis == null || pKpis.recordsBySource.isEmpty())
		{
			return "";
		}
		final StringBuilder detail = new StringBuilder();
		for (final Map.Entry<String, Integer> entry : pKpis.recordsBySource.entrySet())
		{
			if (detail.length() > 0)
			{
				detail.append(" · ");
			}
			detail.append(entry.getKey()).append(' ').append(entry.getValue());
		}
		return detail.toString();
	}

	private static String kpiCard(final String pValue, final String pLabel, final String pDetail)
	{
		final StringBuilder card = new StringBuilder();
		card.append("<div class=\"mdmf-match-kpi\"><span class=\"mdmf-match-kpi-val\">").append(MatchResultsView.esc(pValue)).append("</span><span class=\"mdmf-match-kpi-lbl\">")
			.append(MatchResultsView.esc(pLabel));
		if (pDetail != null && !pDetail.isEmpty())
		{
			card.append("<br>").append(MatchResultsView.esc(pDetail));
		}
		card.append("</span></div>");
		return card.toString();
	}

	private static void renderBand(final StringBuilder pOut, final Input pIn, final Locale pLocale)
	{
		if (pIn.band == null || pIn.band.isEmpty())
		{
			return;
		}
		final int total = MatchResultsView.bandTotal(pIn.band);
		pOut.append("<div class=\"mdmf-match-band-block\"><div class=\"mdmf-match-band-title mdmf-eyebrow\">")
			.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.band.title"))).append("</div><div class=\"mdmf-match-band\">");
		for (final MatchStats.BandSegment segment : pIn.band)
		{
			final int percent = total == 0 ? 0 : (segment.count * 100) / total;
			final String color = MatchResultsView.colorFor(pIn, segment.label);
			pOut.append("<span style=\"width:").append(Math.max(percent, 4)).append("%;background:").append(MatchResultsView.esc(color)).append("\">")
				.append(MatchResultsView.esc(MatchResultsView.bandLabel(segment, pLocale))).append(" · ").append(segment.count).append("</span>");
		}
		pOut.append("</div><div class=\"mdmf-match-band-legend\">");
		for (final MatchStats.BandSegment segment : pIn.band)
		{
			pOut.append("<span><i style=\"background:").append(MatchResultsView.esc(MatchResultsView.colorFor(pIn, segment.label))).append("\"></i>")
				.append(MatchResultsView.esc(MatchResultsView.bandLabel(segment, pLocale))).append("</span>");
		}
		pOut.append("</div></div>");
	}

	private static String bandLabel(final MatchStats.BandSegment pSegment, final Locale pLocale)
	{
		return MatchStats.MULTIPLE.equals(pSegment.label) ? MatchResultsMessages.get(pLocale, "results.band.multiple") : pSegment.label;
	}

	private static String colorFor(final Input pIn, final String pLabel)
	{
		final String color = pIn.colorBySource == null ? null : pIn.colorBySource.get(pLabel);
		return color == null ? "#98a2b3" : color;
	}

	private static int bandTotal(final List<MatchStats.BandSegment> pBand)
	{
		int total = 0;
		for (final MatchStats.BandSegment segment : pBand)
		{
			total += segment.count;
		}
		return total;
	}

	// --------------------------------------------------------------------------------------------------- the queue

	private static void renderQueue(final StringBuilder pOut, final Input pIn, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-match-queue\">");
		pOut.append("<div class=\"mdmf-match-tabs\">");
		MatchResultsView.renderTab(pOut, pIn, MatchStats.PENDING, "results.tab.pending", String.valueOf(MatchResultsView.pendingTabCount(pIn)), pLocale);
		MatchResultsView.renderTab(pOut, pIn, MatchStats.MERGED, "results.tab.merged",
			MatchResultsView.tabCount(pIn.mergedGroups.size(), pIn.kpis == null ? -1 : pIn.kpis.mergedGroups, MatchResultsView.MERGED_SHOWN_MAX), pLocale);
		MatchResultsView.renderTab(pOut, pIn, MatchStats.DISTINCT, "results.tab.distinct",
			MatchResultsView.tabCount(pIn.distinctPairs.size(), pIn.kpis == null ? -1 : pIn.kpis.distinctPairs, MatchResultsView.DISTINCT_SHOWN_MAX), pLocale);
		pOut.append("</div>");

		final List<MatchStats.SuspectGroup> groups = MatchResultsView.groupsOf(pIn);
		final int groupsTotal = Math.max(groups.size(), pIn.pendingGroups.isEmpty() ? pIn.pendingPairsTotal : pIn.pendingGroupsTotal);
		pOut.append("<div class=\"mdmf-match-list\" data-mdmf-match-tab=\"").append(MatchStats.PENDING).append("\">");
		if (groups.isEmpty())
		{
			pOut.append("<p class=\"mdmf-match-empty\">").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.list.empty"))).append("</p>");
		}
		// step 6 queue per group (2026-10-18): one card per group of records that are probably the same object - a table with one column per record - instead of one card per pair
		// (a person in five records was ten cards). A group of two is the two-column case. Paged by groups, ten a page.
		if (pIn.deferPairs && !groups.isEmpty())
		{
			// wave 2 (2026-09-30): the cards are only written when step 6 is the step shown; wizard.js loadDeferredStep reloads the step when this placeholder becomes visible.
			pOut.append("<div class=\"mdmf-notice mdmf-notice-target\" data-mdmf-deferred-step=\"6\">")
				.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.pairs.deferred"))).append("</div>")
				.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STEP_VIEW\">")
				.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.pairs.deferred.load"))).append("</button></div>");
		}
		else if (!groups.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint mdmf-match-counts\">").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.groups.counts", Integer.valueOf(groupsTotal),
				Integer.valueOf(pIn.pendingPairsTotal)))).append("</p>");
			// F2-106: the page is cut HERE (server side), only its groups are written; the pager posts the page number
			final int pages = MatchResultsView.pageCount(groups.size());
			final int page = Math.min(Math.max(0, MatchResultsView.pageOf(pIn.selectedTab)), pages - 1);
			MatchResultsView.renderPager(pOut, pIn.entityId, page, groups.size());
			final int from = page * MatchResultsView.GROUPS_PER_PAGE;
			final int to = Math.min(groups.size(), from + MatchResultsView.GROUPS_PER_PAGE);
			for (int i = from; i < to; i++)
			{
				pOut.append("<div class=\"mdmf-match-page-item\" data-mdmf-page-item=\"").append(i).append("\">");
				MatchResultsView.renderGroupCard(pOut, groups.get(i), pIn, pLocale);
				pOut.append("</div>");
			}
		}
		if (groupsTotal > groups.size())
		{
			pOut.append("<p class=\"mdmf-hint mdmf-match-more\">")
				.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.groups.more", Integer.valueOf(groupsTotal - groups.size())))).append("</p>");
		}
		pOut.append("</div>");

		pOut.append("<div class=\"mdmf-match-list\" data-mdmf-match-tab=\"").append(MatchStats.MERGED).append("\" style=\"display:none\">");
		if (pIn.mergedGroups.isEmpty())
		{
			pOut.append("<p class=\"mdmf-match-empty\">").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.list.empty"))).append("</p>");
		}
		for (final GroupSummary group : pIn.mergedGroups)
		{
			MatchResultsView.renderGroup(pOut, group, pLocale);
		}
		MatchResultsView.renderPartial(pOut, pIn.mergedGroups.size(), pIn.kpis == null ? -1 : pIn.kpis.mergedGroups, MatchResultsView.MERGED_SHOWN_MAX, pLocale);
		pOut.append("</div>");

		pOut.append("<div class=\"mdmf-match-list\" data-mdmf-match-tab=\"").append(MatchStats.DISTINCT).append("\" style=\"display:none\">");
		if (pIn.distinctPairs.isEmpty())
		{
			pOut.append("<p class=\"mdmf-match-empty\">").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.list.empty"))).append("</p>");
		}
		for (final DistinctPair pair : pIn.distinctPairs)
		{
			pOut.append("<div class=\"mdmf-match-distinct-row\"><span>").append(MatchResultsView.esc(pair.sourceId)).append(" / ").append(MatchResultsView.esc(pair.targetId))
				.append("</span><span class=\"mdmf-hint\">")
				.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.distinct.decidedBy", MatchResultsView.orDash(pair.decidedBy),
					MatchResultsView.orDash(pair.decidedAt))))
				.append("</span></div>");
		}
		MatchResultsView.renderPartial(pOut, pIn.distinctPairs.size(), pIn.kpis == null ? -1 : pIn.kpis.distinctPairs, MatchResultsView.DISTINCT_SHOWN_MAX, pLocale);
		pOut.append("</div>");

		pOut.append("</div>");
	}

	/** Merged groups kept in the stored block, at most; the tab says "60+" when it cannot say more. */
	public static final int MERGED_SHOWN_MAX = 60;
	/** Different pairs kept in the stored block, at most. */
	public static final int DISTINCT_SHOWN_MAX = 100;

	/** F2-106: what a tab announces - the real total when the whole-table figure is known and not below what is shown, else "cap+" when the list was cut at its cap, else the count shown. */
	static String tabCount(final int pShown, final int pTotal, final int pCap)
	{
		if (pTotal >= 0 && pTotal >= pShown)
		{
			return String.valueOf(pTotal);
		}
		return pShown >= pCap ? pCap + "+" : String.valueOf(pShown);
	}

	/** "60 of 163 shown" under a list that was cut; "at least 60" when the real total is unknown. Nothing when the list is whole. */
	private static void renderPartial(final StringBuilder pOut, final int pShown, final int pTotal, final int pCap, final Locale pLocale)
	{
		if (pTotal >= 0 && pTotal > pShown)
		{
			pOut.append("<p class=\"mdmf-hint mdmf-match-more\">")
				.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.list.partial", Integer.valueOf(pShown), Integer.valueOf(pTotal)))).append("</p>");
		}
		else if (pTotal < 0 && pShown >= pCap)
		{
			pOut.append("<p class=\"mdmf-hint mdmf-match-more\">")
				.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.list.atLeast", Integer.valueOf(pShown)))).append("</p>");
		}
	}

	/** The tab of a posted selection: "PENDING@3" (tab and page) or "MERGED". */
	static String tabOf(final String pSelected)
	{
		if (pSelected == null)
		{
			return MatchStats.PENDING;
		}
		final int at = pSelected.indexOf('@');
		return at < 0 ? pSelected : pSelected.substring(0, at);
	}

	/** The 0-based page of a posted selection ("PENDING@3" is page 3); 0 when absent or malformed. */
	static int pageOf(final String pSelected)
	{
		final int at = pSelected == null ? -1 : pSelected.indexOf('@');
		if (at < 0)
		{
			return 0;
		}
		try
		{
			return Math.max(0, Integer.parseInt(pSelected.substring(at + 1).trim()));
		}
		catch (final NumberFormatException malformed)
		{
			return 0;
		}
	}

	static int pageCount(final int pItems)
	{
		return Math.max(1, (pItems + MatchResultsView.GROUPS_PER_PAGE - 1) / MatchResultsView.GROUPS_PER_PAGE);
	}

	private static void renderTab(final StringBuilder pOut, final Input pIn, final String pTab, final String pLabelKey, final String pCount, final Locale pLocale)
	{
		final boolean selected = pTab.equals(MatchResultsView.tabOf(pIn.selectedTab));
		pOut.append("<button type=\"button\" class=\"mdmf-match-tab").append(selected ? " on" : "").append("\" data-mdmf-match-tab-select=\"").append(pTab).append("\">")
			.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, pLabelKey))).append(" <span class=\"mdmf-match-tab-n\">").append(pCount).append("</span></button>");
	}

	/** Groups shown per page of the queue. */
	public static final int GROUPS_PER_PAGE = 10;

	/** The groups to show: the stored ones, or - for a snapshot stored before groups - each stored pair as a group of two. */
	static List<MatchStats.SuspectGroup> groupsOf(final Input pIn)
	{
		if (!pIn.pendingGroups.isEmpty())
		{
			return pIn.pendingGroups;
		}
		final List<MatchStats.SuspectGroup> out = new ArrayList<MatchStats.SuspectGroup>();
		for (final MatchStats.SuspectPair pair : pIn.pendingPairs)
		{
			out.add(MatchStats.SuspectGroup.ofPair(pair));
		}
		return out;
	}

	private static int pendingTabCount(final Input pIn)
	{
		return pIn.pendingGroups.isEmpty() ? pIn.pendingPairsTotal : Math.max(pIn.pendingGroupsTotal, pIn.pendingGroups.size());
	}

	/**
	 * Previous / next over the groups (F2-106): server side - each button posts the page wanted ({@code MATCH_PAGE}, the page rides in the tab field as "PENDING@n") and the
	 * next rendering writes only that page. The total is the number of groups the stored block holds (what the pager can reach); the groups beyond it are announced apart.
	 * Nothing when everything fits one page.
	 */
	private static void renderPager(final StringBuilder pOut, final String pEntityId, final int pPage, final int pLoaded)
	{
		if (pLoaded <= MatchResultsView.GROUPS_PER_PAGE)
		{
			return;
		}
		final int pages = MatchResultsView.pageCount(pLoaded);
		final int from = pPage * MatchResultsView.GROUPS_PER_PAGE + 1;
		final int to = Math.min(pLoaded, (pPage + 1) * MatchResultsView.GROUPS_PER_PAGE);
		pOut.append("<div class=\"mdmf-match-pager\" data-mdmf-page-current=\"").append(pPage).append("\">");
		MatchResultsView.pagerButton(pOut, pEntityId, "\u2039", pPage - 1, pPage > 0);
		pOut.append("<span class=\"mdmf-match-pager-pos\">").append(from).append('\u2013').append(to).append(" / ").append(pLoaded).append("</span>");
		MatchResultsView.pagerButton(pOut, pEntityId, "\u203A", pPage + 1, pPage + 1 < pages);
		pOut.append("</div>");
	}

	private static void pagerButton(final StringBuilder pOut, final String pEntityId, final String pLabel, final int pTargetPage, final boolean pEnabled)
	{
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"MATCH_PAGE\" data-mdmf-id=\"").append(MatchResultsView.esc(pEntityId))
			.append("\" data-mdmf-match-tab-value=\"").append(MatchStats.PENDING).append('@')
			.append(Math.max(0, pTargetPage)).append('"').append(pEnabled ? "" : " disabled").append('>').append(pLabel).append("</button>");
	}

	/** "ECOM 30": the source system and the record's key in it; the system alone when the key is unknown, empty when both are. */
	static String origin(final String pSource, final String pSourceId)
	{
		final String source = pSource == null ? "" : pSource.trim();
		final String id = pSourceId == null ? "" : pSourceId.trim();
		return id.isEmpty() ? source : source.isEmpty() ? id : source + " " + id;
	}

	/** The cell text of a value: itself, "-" (a dash) when empty. */
	private static String cell(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() ? "<span class=\"mdmf-match-none\">\u2014</span>" : MatchResultsView.esc(pValue);
	}

	/**
	 * The card of one group: the name and how many records, the table (one column per record, one row per field), the rule that linked them, the three decisions, and the
	 * exhaustive table under "Compare field by field".
	 */
	private static void renderGroupCard(final StringBuilder pOut, final MatchStats.SuspectGroup pGroup, final Input pIn, final Locale pLocale)
	{
		final String domId = "mdmf-match-detail-" + MatchResultsView.esc(pGroup.id);
		final StringBuilder keys = new StringBuilder();
		for (final MatchStats.GroupMember member : pGroup.members)
		{
			keys.append(keys.length() > 0 ? "," : "").append(member.key);
		}
		// class mdmf-match-pair-detail kept on the card: script()'s "Decide later" handler hides its closest one.
		pOut.append("<div class=\"mdmf-match-pair-card mdmf-match-group mdmf-match-pair-detail\" id=\"").append(domId).append("\">");
		pOut.append("<div class=\"mdmf-match-pair-head\"><b>").append(MatchResultsView.esc(pGroup.label())).append("</b><span class=\"mdmf-match-origin\">")
			.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.group.title", Integer.valueOf(pGroup.members.size())))).append("</span></div>");
		MatchResultsView.renderGroupTable(pOut, pGroup, pIn, pLocale, true, false);
		MatchResultsView.renderWhy(pOut, pGroup, pIn, pLocale);
		pOut.append("<div class=\"mdmf-match-decide\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"MATCH_GROUP_MERGE\" data-mdmf-id=\"").append(MatchResultsView.esc(pIn.entityId))
			.append("\" data-mdmf-match-golden=\"").append(MatchResultsView.esc(keys.toString())).append("\" data-mdmf-match-exclude-in=\"").append(domId).append('"')
			.append(pIn.canDecide ? "" : " disabled=\"disabled\"").append('>')
			.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.group.merge", Integer.valueOf(pGroup.members.size())))).append("</button>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"MATCH_GROUP_REJECT\" data-mdmf-id=\"").append(MatchResultsView.esc(pIn.entityId))
			.append("\" data-mdmf-match-golden=\"").append(MatchResultsView.esc(keys.toString())).append('"').append(pIn.canDecide ? "" : " disabled=\"disabled\"").append('>')
			.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.decide.reject"))).append("</button>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-quiet\" data-mdmf-match-pair-later=\"1\">")
			.append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.decide.later"))).append("</button>");
		if (!pIn.canDecide)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.decide.gate"))).append("</span>");
		}
		pOut.append("</div>");
		pOut.append("<details class=\"mdmf-match-pair-more\"><summary>").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.pair.compare")))
			.append("</summary>");
		MatchResultsView.renderGroupTable(pOut, pGroup, pIn, pLocale, false, true);
		pOut.append("</details></div>");
	}

	/**
	 * One column per record (source system and key in it, a golden flagged, a "not this one" box when {@code pExclusion}), one row per field. A value identical on every record
	 * reads neutral; a value that differs from what most records hold is highlighted; an empty one is a dash. A derived key shows its raw value under it.
	 */
	private static void renderGroupTable(final StringBuilder pOut, final MatchStats.SuspectGroup pGroup, final Input pIn, final Locale pLocale, final boolean pGlanceOnly,
		final boolean pFullHead)
	{
		// lot M12: a golden record is the survivor the others merge into: it is the first column, whatever its place among the keys
		final int[] order = MatchResultsView.columnOrder(pGroup.members);
		pOut.append("<table class=\"mdmf-match-glance mdmf-match-group-table\"><thead><tr><th></th>");
		for (final int i : order)
		{
			final MatchStats.GroupMember member = pGroup.members.get(i);
			final String color = MatchResultsView.color(pIn, member.source);
			pOut.append(member.golden ? "<th class=\"mdmf-match-col-golden\" title=\"" + MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.group.survivor")) + "\">" : "<th>");
			pOut.append("<span class=\"mdmf-match-dot\" style=\"background:").append(color).append("\"></span>");
			if (member.golden)
			{
				pOut.append("<b>").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.group.goldenHead", member.source))).append("</b>");
				if (!member.sourceId.trim().isEmpty())
				{
					pOut.append("<small class=\"mdmf-match-id\">").append(MatchResultsView.esc(member.sourceId.trim())).append("</small>");
				}
			}
			else
			{
				pOut.append(MatchResultsView.esc(MatchResultsView.origin(member.source, member.sourceId)));
			}
			if (pGlanceOnly)
			{
				pOut.append("<label class=\"mdmf-match-excl-label\"><input type=\"checkbox\" class=\"mdmf-match-excl\" value=\"").append(MatchResultsView.esc(member.key)).append("\"")
					.append(pIn.canDecide ? "" : " disabled=\"disabled\"").append("> ").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.group.exclude"))).append("</label>");
			}
			pOut.append("</th>");
		}
		pOut.append("</tr></thead><tbody>");
		for (final MatchStats.GroupRow row : pGroup.rows)
		{
			if (pGlanceOnly && !row.glance)
			{
				continue;
			}
			final String verdict = row.verdict();
			final String majority = row.majority();
			pOut.append("<tr class=\"mdmf-match-glance-").append(verdict).append("\"><th>").append(MatchResultsView.esc(row.label)).append("</th>");
			for (final int i : order)
			{
				final String value = row.value(i);
				final boolean differs = MatchStats.DIFF.equals(verdict) && !MatchStats.normalize(value).isEmpty() && !MatchStats.normalize(value).equals(majority);
				final String classes = (differs ? "mdmf-match-cell-diff" : "") + (pGroup.members.get(i).golden ? (differs ? " " : "") + "mdmf-match-col-golden" : "");
				pOut.append(classes.isEmpty() ? "<td>" : "<td class=\"" + classes + "\">");
				final String raw = row.raw(i);
				if (!raw.trim().isEmpty() && !raw.trim().equals(value.trim()) && !value.trim().isEmpty() && MatchResultsView.sameButFolded(raw, value))
				{
					// an e-mail and its folded key: the person reads what the record holds, the key the plan compares comes under it, lighter
					pOut.append(MatchResultsView.esc(raw)).append("<small class=\"mdmf-match-key\">").append(MatchResultsView.esc(value)).append("</small>");
				}
				else
				{
					pOut.append(MatchResultsView.cell(value));
					if (!raw.trim().isEmpty() && !raw.trim().equals(value.trim()))
					{
						pOut.append("<small>").append(MatchResultsView.esc(raw)).append("</small>");
					}
				}
				pOut.append("</td>");
			}
			pOut.append("</tr>");
		}
		pOut.append("</tbody></table>");
	}

	/** The columns in the order shown: the golden records first (their relative order kept), then the others as they came. */
	static int[] columnOrder(final List<MatchStats.GroupMember> pMembers)
	{
		final int[] order = new int[pMembers.size()];
		int next = 0;
		for (int i = 0; i < pMembers.size(); i++)
		{
			if (pMembers.get(i).golden)
			{
				order[next++] = i;
			}
		}
		for (int i = 0; i < pMembers.size(); i++)
		{
			if (!pMembers.get(i).golden)
			{
				order[next++] = i;
			}
		}
		return order;
	}

	/** True when the two texts differ only by case, accents, spaces or punctuation (a raw value and the key folded from it). */
	static boolean sameButFolded(final String pA, final String pB)
	{
		return MatchResultsView.fold(pA).equals(MatchResultsView.fold(pB));
	}

	private static String fold(final String pValue)
	{
		final String decomposed = java.text.Normalizer.normalize(pValue == null ? "" : pValue, java.text.Normalizer.Form.NFD);
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < decomposed.length(); i++)
		{
			final char c = decomposed.charAt(i);
			if (Character.isLetterOrDigit(c))
			{
				out.append(Character.toLowerCase(c));
			}
		}
		return out.toString();
	}

	/**
	 * "Why": the rules of the plan that linked the records (the deciding node of each pair of the group, as the add-on's own evaluation named it), in words - never a percentage.
	 * Nothing when the engine did not evaluate the pairs.
	 */
	private static void renderWhy(final StringBuilder pOut, final MatchStats.SuspectGroup pGroup, final Input pIn, final Locale pLocale)
	{
		final StringBuilder why = new StringBuilder();
		for (final String node : pGroup.nodes)
		{
			final String text = MatchResultsView.whyText(node, pIn, pLocale);
			if (text != null)
			{
				why.append(why.length() > 0 ? " " : "").append(text);
			}
		}
		// lot M12: when the evaluation did not name a rule of the plan (unavailable, or a snapshot stored when the card printed the verdict leaf), say so - never the leaf
		final String shown = why.length() == 0 ? MatchResultsMessages.get(pLocale, "results.why.unavailable") : why.toString();
		pOut.append("<p class=\"mdmf-match-why").append(why.length() == 0 ? " mdmf-match-why-none" : "").append("\"><b>").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.why.lead")))
			.append("</b> ").append(MatchResultsView.esc(shown)).append("</p>");
	}

	/** The wording of one node of the plan by the engine's name. Package-visible for the tests. */
	static String whyText(final String pNodeName, final Input pIn, final Locale pLocale)
	{
		if (pNodeName == null || pNodeName.isEmpty() || DecidingNode.isLeafName(pNodeName))
		{
			return null; // the verdict's leaf is not a rule
		}
		final NodeInfo info = pIn.planNodes == null ? null : pIn.planNodes.get(pNodeName);
		if (info == null)
		{
			return pIn.planNodes == null || pIn.planNodes.isEmpty() ? MatchResultsMessages.get(pLocale, "results.why.node", pNodeName) : null;
		}
		final StringBuilder fields = new StringBuilder();
		final String and = MatchResultsMessages.get(pLocale, "results.why.and");
		for (final String name : info.fields)
		{
			if (fields.length() > 0)
			{
				fields.append(and);
			}
			final String label = pIn.fieldLabels == null ? null : pIn.fieldLabels.get(name);
			fields.append(label == null || label.isEmpty() ? name : label);
		}
		final String id = info.id == null || info.id.isEmpty() ? pNodeName : info.id;
		if (!info.suspectOnTrue && info.suspectOnFalse)
		{
			return MatchResultsMessages.get(pLocale, "results.why.notEnough", id, fields.toString());
		}
		if ("AVERAGE".equals(info.function))
		{
			return MatchResultsMessages.get(pLocale, "results.why.average", id, fields.toString(), Integer.valueOf(info.minimumScore == null ? 90 : info.minimumScore.intValue()));
		}
		if ("AT_LEAST_N".equals(info.function))
		{
			return MatchResultsMessages.get(pLocale, "results.why.atLeastN", id, fields.toString(), Integer.valueOf(info.minimumMatches == null ? 1 : info.minimumMatches.intValue()));
		}
		if ("PREDICATE".equals(info.function))
		{
			return MatchResultsMessages.get(pLocale, "results.why.predicate", id, fields.toString());
		}
		return MatchResultsMessages.get(pLocale, "results.why.allFields", id, fields.toString());
	}

	private static String color(final Input pIn, final String pSource)
	{
		final String c = pIn.colorBySource == null ? null : pIn.colorBySource.get(pSource);
		return c == null ? "#98a2b3" : MatchResultsView.esc(c);
	}

	private static void renderGroup(final StringBuilder pOut, final GroupSummary pGroup, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-match-group-row\"><div class=\"mdmf-match-group-name\">").append(MatchResultsView.esc(pGroup.goldenLabel))
			.append("<span class=\"mdmf-hint\">").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.group.key", pGroup.goldenKey))).append("</span></div>");
		pOut.append("<div class=\"mdmf-match-group-members\">");
		for (int i = 0; i < pGroup.memberKeys.size(); i++)
		{
			final String source = i < pGroup.memberSources.size() ? pGroup.memberSources.get(i) : "";
			pOut.append("<span class=\"mdmf-chip mdmf-chip-muted\"><span class=\"mdmf-match-src-tag\">").append(MatchResultsView.esc(source)).append("</span>")
				.append(MatchResultsView.esc(pGroup.memberKeys.get(i))).append("</span>");
		}
		pOut.append("</div>");
		pOut.append("<p class=\"mdmf-hint\">").append(MatchResultsView.esc(MatchResultsMessages.get(pLocale, "results.group.manageHint"))).append("</p>");
		pOut.append("</div>");
	}

	// -------------------------------------------------------------------------------------------------------- misc

	private static String orDash(final String pValue)
	{
		return pValue == null || pValue.isEmpty() ? "—" : pValue;
	}

	/** Client-side tab/pair switching only - never a postback (class Javadoc). Injected once, after {@code review.MatchPlanView#treeScript()}, by {@code ebx.ui.ProjectWizardUserService#script}. */
	public static String script()
	{
		return "root.querySelectorAll('[data-mdmf-match-tab-select]').forEach(function(b){"
			+ "b.addEventListener('click',function(){var tab=b.getAttribute('data-mdmf-match-tab-select');"
			+ "var scope=b.closest('.mdmf-match-results');if(!scope)return;"
			+ "scope.querySelectorAll('.mdmf-match-tab').forEach(function(t){t.classList.remove('on')});b.classList.add('on');"
			+ "scope.querySelectorAll('.mdmf-match-list').forEach(function(l){l.style.display=(l.getAttribute('data-mdmf-match-tab')===tab)?'':'none'});"
			+ "var hidden=document.getElementById('mdmfMatchTab');if(hidden)hidden.value=tab;});});"
			+ "root.querySelectorAll('[data-mdmf-match-pair-select]').forEach(function(b){"
			+ "b.addEventListener('click',function(){var id=b.getAttribute('data-mdmf-match-pair-select');"
			+ "var scope=b.closest('.mdmf-match-results');if(!scope)return;"
			+ "scope.querySelectorAll('.mdmf-match-pair-item').forEach(function(i){i.classList.remove('sel')});b.classList.add('sel');"
			+ "scope.querySelectorAll('.mdmf-match-pair-detail').forEach(function(d){d.style.display=(d.id==='mdmf-match-detail-'+id)?'':'none'});"
			+ "var hidden=document.getElementById('mdmfMatchPairId');if(hidden)hidden.value=id;});});"
			+ "root.querySelectorAll('[data-mdmf-match-pair-later]').forEach(function(b){"
			+ "b.addEventListener('click',function(){var detail=b.closest('.mdmf-match-pair-detail');if(detail)detail.style.display='none';});});";
	}

	private static String esc(final String pValue)
	{
		if (pValue == null)
		{
			return "";
		}
		final StringBuilder out = new StringBuilder(pValue.length());
		for (int i = 0; i < pValue.length(); i++)
		{
			final char c = pValue.charAt(i);
			switch (c)
			{
				case '&':
					out.append("&amp;");
					break;
				case '<':
					out.append("&lt;");
					break;
				case '>':
					out.append("&gt;");
					break;
				case '"':
					out.append("&quot;");
					break;
				case '\'':
					out.append("&#39;");
					break;
				default:
					out.append(c);
			}
		}
		return out.toString();
	}
}
