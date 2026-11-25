package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders the "Revue des propositions" / "Proposal review" screen (PLAN.md screen 3, mockup screen
 * 4) as an HTML fragment meant to be dropped inside a {@code UserServicePane}: no {@code <html>}/
 * {@code <head>}, the caller (the EBX-bound user service) links the stylesheets - BOTH {@code
 * factory/factory.css} (the shared tokens and components: {@code .mdmf}, {@code .mdmf-panel},
 * {@code .mdmf-chip*}, {@code .mdmf-table*}, {@code .mdmf-notice*}...) and this screen's own
 * {@code factory/review.css} (the Accept/Reject/filter buttons, the confidence bar, the expandable
 * rationale - nothing else has these) - and wraps this in the page chrome. Pure: no EBX class, so it
 * is unit-tested without one - only {@link Input}, a plain value object the EBX-bound reader/writer
 * builds from what it read. Every value is HTML-escaped; nothing here trusts that a caller already
 * escaped anything.
 * <p>
 * Reproduces the approved mockup ({@code docs/mockups/mdm-factory.html}, screen "Revue des
 * propositions"): same {@code mdmf-} class names and tokens as {@code factory.css}/{@code review.css} -
 * copy any change to one into the other, they must keep agreeing.
 */
public final class ReviewPage
{
	private ReviewPage()
	{
	}

	// ------------------------------------------------------------------------------------------------------- input

	public static final class ProjectOption
	{
		public final String code;
		public final String label;

		public ProjectOption(final String pCode, final String pLabel)
		{
			this.code = pCode;
			this.label = pLabel;
		}
	}

	/** A decision the screen is about to make in bulk, offered for confirmation before anything is written - never applied silently. */
	public static final class Confirmation
	{
		public final String message;
		/** The action value the Confirm button posts back ({@code BULK_ACCEPT_CONFIRM} / {@code CASCADE_CONFIRM}). */
		public final String action;
		public final String confirmLabel;
		public final List<ReviewRules.AffectedRow> rows;

		public Confirmation(final String pMessage, final String pAction, final String pConfirmLabel, final List<ReviewRules.AffectedRow> pRows)
		{
			this.message = pMessage;
			this.action = pAction;
			this.confirmLabel = pConfirmLabel;
			this.rows = pRows;
		}
	}

	public static final class Input
	{
		public final Locale locale;
		/** Every project of the factory, for the selector shown when no project is resolved from the launch context. */
		public final List<ProjectOption> projects;
		/** Null while no project is resolved yet: {@link #render} then only shows the selector. */
		public final String selectedProjectCode;
		public final String selectedProjectLabel;
		/** True when the project came from the record the service was launched on (toolbar action on a Project or a proposal record): no "change project" offered. */
		public final boolean projectLocked;
		/** Every line of the selected project, unfiltered: {@link ReviewData#counters} reads the true totals from this, never from {@link #visibleLines}. */
		public final List<ReviewLine> allLines;
		/** {@code ReviewData.filter(allLines, filter)}: what the table actually lists. */
		public final List<ReviewLine> visibleLines;
		public final ReviewFilter filter;
		public final int bulkThreshold;
		public final String notice;
		/** {@code "ok"} or {@code "warn"}. */
		public final String noticeKind;
		public final Confirmation confirmation;
		/**
		 * review-match-plan lot (2026-09-22): per {@link ReviewLine#MATCH} line (keyed by {@link ReviewLine#rowId}),
		 * whether the "Simulate" button of that line's expanded body is currently usable - the plan must be {@code
		 * APPLIED}/configured on the add-on ({@code MatchingConfigurator.simulate}'s own precondition). A rowId
		 * with no entry here reads as {@code false} (never simulatable by default - see {@link #render}).
		 */
		public final Map<String, Boolean> matchCanSimulate;
		/** Non-null entry only for a rowId whose {@link #matchCanSimulate} is false - why (falls back to a generic reason when absent). */
		public final Map<String, String> matchCannotSimulateReason;
		/** Non-null entry only for a rowId whose Simulate button was clicked this round - kept on the calling user service instance, never persisted (see {@code review.MatchPlanView.SimulationResult}). */
		public final Map<String, MatchPlanView.SimulationResult> matchSimulationResults;
		/** How many pivots a "Simulate" click asks for, screen-wide - {@code ebx.MatchPlanSimulationRunner#MAX_PIVOTS}, passed in so this pure page never hardcodes the EBX-bound cap. */
		public final int matchMaxSimulationPairs;
		/**
		 * tree-diagram-labels fix lot (2026-09-22), item C: every {@link ReviewLine#MATCH} line's expanded body
		 * ({@link #renderMatchExpandedRow}) now renders COLLAPSED by default (product report, the product owner: the
		 * expanded Company line pushed the Contact line far enough down that it went unnoticed) - a rowId in
		 * this set is the one exception, reopened automatically so the reviewer who just acted on it (Simulate,
		 * Accept, Reject, a comment...) is not left wondering whether the click did anything. {@code
		 * ebx.ui.ProposalReviewUserService} is the only real caller that ever populates this (from the SAME
		 * request's own {@code mdmfId}, kept on the service instance across the decide-then-repaint round trip,
		 * the same "instance field survives one repaint" idea {@code pendingSuspectAction} already uses
		 * elsewhere) - every unit test keeps using the constructor below, which defaults this to empty (every
		 * line collapsed), unless it explicitly wants to test the reopen behaviour (see the constructor overload
		 * further down).
		 */
		public final Set<String> expandedMatchRowIds;
		/**
		 * Feed-designer lot (2026-09-23): the "Designer" link's URL ({@code core.FeedDesignerLink#url}), keyed
		 * by {@link ReviewLine#rowId}, for a {@link ReviewLine#FEED} line whose {@link ReviewLine#element} does
		 * NOT carry the " (child of " suffix {@code ebx.ProposalReviews#addFeedLines} appends for a child feed -
		 * a root feed only, same rule {@code wizard.WizardPage} follows for its own copy of this link (root
		 * feeds only; a child feed's tree opens from its root's designer). A rowId with no entry here gets no
		 * link. This class stays pure (no EBX type): {@code ebx.ui.ProposalReviewUserService} computes the map.
		 */
		public final Map<String, String> feedDesignerUrls;

		public Input(final Locale pLocale, final List<ProjectOption> pProjects, final String pSelectedProjectCode, final String pSelectedProjectLabel,
			final boolean pProjectLocked, final List<ReviewLine> pAllLines, final List<ReviewLine> pVisibleLines, final ReviewFilter pFilter,
			final int pBulkThreshold, final String pNotice, final String pNoticeKind, final Confirmation pConfirmation, final Map<String, Boolean> pMatchCanSimulate,
			final Map<String, String> pMatchCannotSimulateReason, final Map<String, MatchPlanView.SimulationResult> pMatchSimulationResults,
			final int pMatchMaxSimulationPairs)
		{
			this(pLocale, pProjects, pSelectedProjectCode, pSelectedProjectLabel, pProjectLocked, pAllLines, pVisibleLines, pFilter, pBulkThreshold, pNotice,
				pNoticeKind, pConfirmation, pMatchCanSimulate, pMatchCannotSimulateReason, pMatchSimulationResults, pMatchMaxSimulationPairs, null);
		}

		/** See {@link #expandedMatchRowIds} (tree-diagram-labels fix lot, item C) - every other field identical to the constructor above. */
		public Input(final Locale pLocale, final List<ProjectOption> pProjects, final String pSelectedProjectCode, final String pSelectedProjectLabel,
			final boolean pProjectLocked, final List<ReviewLine> pAllLines, final List<ReviewLine> pVisibleLines, final ReviewFilter pFilter,
			final int pBulkThreshold, final String pNotice, final String pNoticeKind, final Confirmation pConfirmation, final Map<String, Boolean> pMatchCanSimulate,
			final Map<String, String> pMatchCannotSimulateReason, final Map<String, MatchPlanView.SimulationResult> pMatchSimulationResults,
			final int pMatchMaxSimulationPairs, final Set<String> pExpandedMatchRowIds)
		{
			this(pLocale, pProjects, pSelectedProjectCode, pSelectedProjectLabel, pProjectLocked, pAllLines, pVisibleLines, pFilter, pBulkThreshold, pNotice,
				pNoticeKind, pConfirmation, pMatchCanSimulate, pMatchCannotSimulateReason, pMatchSimulationResults, pMatchMaxSimulationPairs, pExpandedMatchRowIds, null);
		}

		/** See {@link #feedDesignerUrls} (feed-designer lot) - every other field identical to the constructor above. */
		public Input(final Locale pLocale, final List<ProjectOption> pProjects, final String pSelectedProjectCode, final String pSelectedProjectLabel,
			final boolean pProjectLocked, final List<ReviewLine> pAllLines, final List<ReviewLine> pVisibleLines, final ReviewFilter pFilter,
			final int pBulkThreshold, final String pNotice, final String pNoticeKind, final Confirmation pConfirmation, final Map<String, Boolean> pMatchCanSimulate,
			final Map<String, String> pMatchCannotSimulateReason, final Map<String, MatchPlanView.SimulationResult> pMatchSimulationResults,
			final int pMatchMaxSimulationPairs, final Set<String> pExpandedMatchRowIds, final Map<String, String> pFeedDesignerUrls)
		{
			this.locale = pLocale;
			this.projects = pProjects;
			this.selectedProjectCode = pSelectedProjectCode;
			this.selectedProjectLabel = pSelectedProjectLabel;
			this.projectLocked = pProjectLocked;
			this.allLines = pAllLines;
			this.visibleLines = pVisibleLines;
			this.filter = pFilter;
			this.bulkThreshold = pBulkThreshold;
			this.notice = pNotice;
			this.noticeKind = pNoticeKind;
			this.confirmation = pConfirmation;
			this.matchCanSimulate = pMatchCanSimulate == null ? java.util.Collections.<String, Boolean>emptyMap() : pMatchCanSimulate;
			this.matchCannotSimulateReason = pMatchCannotSimulateReason == null ? java.util.Collections.<String, String>emptyMap() : pMatchCannotSimulateReason;
			this.matchSimulationResults = pMatchSimulationResults == null ? java.util.Collections.<String, MatchPlanView.SimulationResult>emptyMap()
				: pMatchSimulationResults;
			this.matchMaxSimulationPairs = pMatchMaxSimulationPairs;
			this.expandedMatchRowIds = pExpandedMatchRowIds == null ? Collections.<String>emptySet() : pExpandedMatchRowIds;
			this.feedDesignerUrls = pFeedDesignerUrls == null ? java.util.Collections.<String, String>emptyMap() : pFeedDesignerUrls;
		}
	}

	// ------------------------------------------------------------------------------------------------------ render

	public static String render(final Input pInput)
	{
		final Locale locale = pInput.locale == null ? Locale.ENGLISH : pInput.locale;
		final StringBuilder out = new StringBuilder();
		out.append("<div class=\"mdmf mdmf-review\">");
		ReviewPage.renderHiddenFields(out, pInput);
		ReviewPage.renderProgress(out);
		if (pInput.notice != null)
		{
			out.append("<div class=\"mdmf-notice mdmf-notice-").append("warn".equals(pInput.noticeKind) ? "warn" : "ok").append("\">")
				.append(ReviewPage.esc(pInput.notice)).append("</div>");
		}
		if (pInput.selectedProjectCode == null)
		{
			ReviewPage.renderProjectSelector(out, pInput, locale);
			out.append("</div>");
			return out.toString();
		}
		out.append("<div class=\"mdmf-panel\"><h2>").append(ReviewPage.esc(ReviewMessages.get(locale, "review.title", pInput.selectedProjectLabel)))
			.append("</h2><p class=\"mdmf-lead\">").append(ReviewPage.esc(ReviewMessages.get(locale, "review.lead"))).append("</p>");
		if (!pInput.projectLocked)
		{
			out.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(locale, "review.project.current", pInput.selectedProjectLabel)))
				.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"CHANGE_PROJECT\">")
				.append(ReviewPage.esc(ReviewMessages.get(locale, "review.project.change"))).append("</button></p>");
		}

		if (pInput.confirmation != null)
		{
			ReviewPage.renderConfirmation(out, pInput.confirmation, locale);
		}

		ReviewPage.renderMatchCounter(out, pInput.allLines, locale);
		ReviewPage.renderUnappliedConstraintsBanner(out, pInput.allLines, locale);
		ReviewPage.renderCounters(out, pInput.allLines, locale);
		ReviewPage.renderFilters(out, pInput.filter, locale);
		ReviewPage.renderBulk(out, pInput.bulkThreshold, locale);
		ReviewPage.renderTable(out, pInput.allLines, pInput.visibleLines, pInput.filter, pInput, locale);
		out.append("</div></div>");
		return out.toString();
	}

	/**
	 * The fields the page's own JavaScript (written by the EBX-bound user service, which owns the single
	 * real submit button - see CLAUDE.md, "posting a user service's own inputs") fills in before clicking
	 * it. {@code mdmfFilter*}/{@code mdmfBulkThreshold}/{@code mdmfProjectCode} are pre-filled from the
	 * CURRENT state on every render, so an action that does not touch them (e.g. accepting one row) still
	 * posts the filter the user was looking at; {@code mdmfNature}/{@code mdmfId} are left blank here -
	 * transient, set fresh by whichever row button was actually clicked.
	 */
	private static void renderHiddenFields(final StringBuilder pOut, final Input pInput)
	{
		pOut.append("<input type=\"hidden\" id=\"mdmfAction\" name=\"mdmfAction\"/>").append("<input type=\"hidden\" id=\"mdmfNature\" name=\"mdmfNature\"/>")
			.append("<input type=\"hidden\" id=\"mdmfId\" name=\"mdmfId\"/>")
			// reject/repropose lot (2026-09-22): the MATCH inline panels' own comment/reason, filled by PAGE_JS from
			// the relevant <textarea> right before submit (data-mdmf-comment-from) - always blank on render, exactly
			// like mdmfNature/mdmfId above.
			.append("<input type=\"hidden\" id=\"mdmfComment\" name=\"mdmfComment\"/>")
			// automerge-toggle lot (2026-09-23): the "Merge confirmed matches automatically" checkbox's own posted
			// value ("true"/"false") - filled by PAGE_JS's dedicated .mdmf-auto-merge-toggle change listener right
			// before it clicks the submit button. Always blank on render, like mdmfNature/mdmfId above.
			.append("<input type=\"hidden\" id=\"mdmfAutoMergeValue\" name=\"mdmfAutoMergeValue\"/>")
			// fix-apply-ux (2026-09-21), item 3: the selection bar's own posted value - "nature|id" pairs, comma
			// joined, filled by PAGE_JS from the checked .mdmf-row-select boxes right before the submit click.
			// Always blank on render, like mdmfNature/mdmfId above: the selection itself is never part of the
			// page's own state, so a fresh render (after any postback) starts with nothing checked.
			.append("<input type=\"hidden\" id=\"mdmfSelectedIds\" name=\"mdmfSelectedIds\"/>")
			.append("<input type=\"hidden\" id=\"mdmfProjectCode\" name=\"mdmfProjectCode\" value=\"")
			.append(ReviewPage.esc(pInput.selectedProjectCode)).append("\"/>").append("<input type=\"hidden\" id=\"mdmfFilterNature\" name=\"mdmfFilterNature\" value=\"")
			.append(ReviewPage.esc(pInput.filter.nature)).append("\"/>").append("<input type=\"hidden\" id=\"mdmfFilterOrigin\" name=\"mdmfFilterOrigin\" value=\"")
			.append(ReviewPage.esc(pInput.filter.origin)).append("\"/>").append("<input type=\"hidden\" id=\"mdmfFilterStatus\" name=\"mdmfFilterStatus\" value=\"")
			.append(ReviewPage.esc(pInput.filter.status)).append("\"/>")
			.append("<input type=\"hidden\" id=\"mdmfFilterMinConfidence\" name=\"mdmfFilterMinConfidence\" value=\"").append(String.valueOf(pInput.filter.minConfidence))
			.append("\"/>")
			// the Reference search box's own posted value (coordinator report, 2026-09-21) - same pre-filled-on-
			// every-render discipline as every other hidden filter field above, so a row action taken while a
			// reference lookup is active does not silently clear it
			.append("<input type=\"hidden\" id=\"mdmfFilterReference\" name=\"mdmfFilterReference\" value=\"").append(ReviewPage.esc(pInput.filter.reference))
			.append("\"/>").append("<input type=\"hidden\" id=\"mdmfBulkThreshold\" name=\"mdmfBulkThreshold\" value=\"").append(String.valueOf(pInput.bulkThreshold))
			.append("\"/>");
	}

	/**
	 * fix-apply-ux (2026-09-21), item 4: the indeterminate progress strip - reused verbatim (same markup
	 * shape, same class-switch/animation technique) from {@code MaterialSearchUserService.writeProgress}
	 * (ebx-ps-fasttrack-web, {@code com.on.ebx.material.ui.search}), never a second implementation. Always
	 * rendered (hidden until {@code ProposalReviewUserService.pageJs} switches it "on"), on both the
	 * project-selector screen and the table screen, so the ids are guaranteed present the instant a button
	 * is clicked. Replaces the plain-text {@code mdmf-applying-cue} notice an earlier round used.
	 * <p>
	 * <b>fix/review-assets (2026-09-21)</b>: product report - on a long table, the strip (rendered once at
	 * the TOP of the page) was invisible when Accept was clicked on a row far down, off-screen. It is now a
	 * fixed, bottom-centre toast (review.css - {@code position:fixed}, high {@code z-index}, above the
	 * sticky decision column's own {@code z-index:1}) instead of an in-flow element at the page's own top -
	 * same ids, same markup shape, same {@code progress(msg)} function, just positioned so it is visible
	 * wherever the user actually is on the page. Deliberately narrow/bottom rather than a full-width bar,
	 * so it does not sit on top of the row/button the user just clicked (whichever row that is). Carries an
	 * inline {@code style="display:none"} IN ADDITION TO the {@code .mdmf-progress} class - the same
	 * degrade-gracefully idea as the selection bar's count span (see {@link #renderSelectionBar}): even a
	 * browser still serving a STALE {@code review.css} (the exact bug this fix responds to) sees the strip
	 * hidden by the inline style until JS explicitly shows it, and {@code pageJs}'s own {@code progress(...)}
	 * now sets {@code box.style.display} directly, not only the class - so the strip still becomes visible
	 * (even if unstyled) rather than staying invisible under a stale sheet that has neither rule at all.
	 */
	private static void renderProgress(final StringBuilder pOut)
	{
		pOut.append("<div id=\"mdmfProgress\" class=\"mdmf-progress\" style=\"display:none\"><div class=\"mdmf-progress-track\"><div class=\"mdmf-progress-bar\"></div></div>")
			.append("<span id=\"mdmfProgressLabel\" class=\"mdmf-progress-label\"></span></div>");
	}

	private static void renderProjectSelector(final StringBuilder pOut, final Input pInput, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-panel\"><h2>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.title.noProject"))).append("</h2><p class=\"mdmf-lead\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.project.help"))).append("</p>");
		if (pInput.projects.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.project.none"))).append("</div></div>");
			return;
		}
		pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfProjectSelect\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.project.label")))
			.append("</label><select id=\"mdmfProjectSelect\">");
		for (final ProjectOption project : pInput.projects)
		{
			pOut.append("<option value=\"").append(ReviewPage.esc(project.code)).append("\">").append(ReviewPage.esc(project.label)).append(" (")
				.append(ReviewPage.esc(project.code)).append(")</option>");
		}
		pOut.append("</select></div><div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"SELECT_PROJECT\" data-mdmf-project-select=\"mdmfProjectSelect\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.project.load"))).append("</button></div></div>");
	}

	private static void renderConfirmation(final StringBuilder pOut, final Confirmation pConfirmation, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\"><p>").append(ReviewPage.esc(pConfirmation.message)).append("</p>");
		if (!pConfirmation.rows.isEmpty())
		{
			pOut.append("<ul class=\"mdmf-confirm-rows\">");
			for (final ReviewRules.AffectedRow row : pConfirmation.rows)
			{
				pOut.append("<li>").append(ReviewPage.esc(row.label)).append(" &middot; ")
					.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.status." + row.status))).append("</li>");
			}
			pOut.append("</ul>");
		}
		pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"").append(ReviewPage.esc(pConfirmation.action))
			.append("\">").append(ReviewPage.esc(pConfirmation.confirmLabel)).append("</button>")
			.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"DISMISS\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.confirm.cancel")))
			.append("</button></div></div>");
	}

	/**
	 * tree-diagram-labels fix lot (2026-09-22), item B ({@code docs/MATCHING.md} &sect;16.10): every {@link
	 * ReviewLine#MATCH} line still awaiting a decision ({@link ReviewLine#PROPOSED}/{@link
	 * ReviewLine#SUPERSEDED}) - same set the counter (below) names and the position chip ({@link
	 * #renderMatchExpandedRow}) numbers against, so the two never disagree about what "N" counts. Order is
	 * {@code pAllLines}' own order (never re-sorted) - the same order the table itself lists them in.
	 */
	private static List<ReviewLine> matchLinesToReview(final List<ReviewLine> pAllLines)
	{
		final List<ReviewLine> out = new ArrayList<ReviewLine>();
		for (final ReviewLine line : pAllLines)
		{
			if (ReviewLine.MATCH.equals(line.nature) && (ReviewLine.PROPOSED.equals(line.status) || ReviewLine.SUPERSEDED.equals(line.status)))
			{
				out.add(line);
			}
		}
		return out;
	}

	/**
	 * tree-diagram-labels fix lot (2026-09-22), item B: product report - a second Matching proposal (Contact)
	 * went unnoticed because the first one's (Company) expanded body pushed it far down the page. One line,
	 * always at the very top of the panel (right under the title/lead and any confirmation banner, before the
	 * unapplied-constraints banner and the table itself): "N matching proposal(s) to review: Company, Contact"
	 * - each name a plain in-page anchor to that line's own {@code id} ({@code mdmf-line-<rowId>}, set on its
	 * collapsed row by {@link #renderRow}); {@code ProposalReviewUserService.pageJs}'s own {@code hashchange}
	 * handler additionally opens that line's {@code <details>} when the anchor is followed (native scrolling
	 * needs no JS of its own). Silent (no empty "0 matching proposals" line) when nothing is awaiting review.
	 */
	private static void renderMatchCounter(final StringBuilder pOut, final List<ReviewLine> pAllLines, final Locale pLocale)
	{
		final List<ReviewLine> toReview = ReviewPage.matchLinesToReview(pAllLines);
		if (toReview.isEmpty())
		{
			return;
		}
		final StringBuilder names = new StringBuilder();
		for (int i = 0; i < toReview.size(); i++)
		{
			if (i > 0)
			{
				names.append(", ");
			}
			final ReviewLine line = toReview.get(i);
			names.append("<a href=\"#mdmf-line-").append(ReviewPage.esc(line.rowId)).append("\">").append(ReviewPage.esc(line.element)).append("</a>");
		}
		pOut.append("<p class=\"mdmf-hint mdmf-match-counter\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.counter.line", Integer.valueOf(toReview.size())))).append(' ').append(names)
			.append("</p>");
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 6: "N accepted constraint(s) are not on the model
	 * yet" - the real captured miss was a product owner who could not FIND an ACCEPTED SCRIPT_CONSTRAINT
	 * line stuck behind a failed apply, because the screen opens on Status = Pending and that line's own
	 * status is ACCEPTED, not PROPOSED. {@link ReviewFilter#isUnappliedConstraintUnderPending} already makes
	 * such a line show under the Pending filter itself; this banner additionally names the count at the top
	 * of the screen, in EVERY filter state (never only when empty), with a one-click link isolating exactly
	 * those lines - same {@code data-mdmf-action="FILTER"} technique the rest of the page's filter controls
	 * already use, this time setting BOTH {@code data-mdmf-filter-nature} and {@code data-mdmf-filter-status}
	 * on the one button (the page's own JS handler, {@code ProposalReviewUserService.PAGE_JS}, already reads
	 * both independently off any clicked element - see its own comments).
	 */
	private static void renderUnappliedConstraintsBanner(final StringBuilder pOut, final List<ReviewLine> pAllLines, final Locale pLocale)
	{
		final int count = ReviewData.unappliedConstraintCount(pAllLines);
		if (count <= 0)
		{
			return;
		}
		pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\"><p>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.capability.unapplied.banner", Integer.valueOf(count))))
			.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"FILTER\" data-mdmf-filter-nature=\"")
			.append(ReviewLine.SCRIPT_CONSTRAINT).append("\" data-mdmf-filter-status=\"").append(ReviewLine.ACCEPTED).append("\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.capability.unapplied.link"))).append("</button></p></div>");
	}

	private static void renderCounters(final StringBuilder pOut, final List<ReviewLine> pAllLines, final Locale pLocale)
	{
		final ReviewData.Counters counters = ReviewData.counters(pAllLines);
		pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.counters", Integer.valueOf(counters.total),
			Integer.valueOf(counters.accepted), Integer.valueOf(counters.rejected), Integer.valueOf(counters.proposed), Integer.valueOf(counters.applied))))
			.append("</p>");
	}

	private static final String[] NATURES = { ReviewLine.RELATION, ReviewLine.ENTITY, ReviewLine.ATTRIBUTE, ReviewLine.FEED, ReviewLine.MAPPING, ReviewLine.MAPPING_STEP,
		ReviewLine.QUALITY, ReviewLine.SCRIPT_CONSTRAINT, ReviewLine.EXTERNAL_SERVICE, ReviewLine.SCRIPT_DERIVATION, ReviewLine.MATCH };
	private static final String[] ORIGINS = { "RULE", "LLM", "USER", "DECLARED" };
	/** Staging-still-to-decide lot (2026-09-27): SET_ASIDE listed last - a capability suggestion set aside from the wizard stays findable here, and decidable. */
	private static final String[] STATUSES = { ReviewLine.PROPOSED, ReviewLine.ACCEPTED, ReviewLine.REJECTED, ReviewLine.APPLIED, ReviewLine.SET_ASIDE };

	private static void renderFilters(final StringBuilder pOut, final ReviewFilter pFilter, final Locale pLocale)
	{
		final String nature = pFilter.nature == null ? ReviewFilter.ALL : pFilter.nature;
		final String origin = pFilter.origin == null ? ReviewFilter.ALL : pFilter.origin;
		final String status = pFilter.status == null ? ReviewFilter.ALL : pFilter.status;
		pOut.append("<div class=\"mdmf-actions\" id=\"mdmfFilters\"><span class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.filter.nature")))
			.append(" </span><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm").append(ReviewFilter.ALL.equals(nature) ? " active" : "")
			.append("\" data-mdmf-action=\"FILTER\" data-mdmf-filter-nature=\"").append(ReviewFilter.ALL).append("\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.nature.ALL"))).append("</button>");
		for (final String candidate : ReviewPage.NATURES)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm").append(candidate.equals(nature) ? " active" : "")
				.append("\" data-mdmf-action=\"FILTER\" data-mdmf-filter-nature=\"").append(candidate).append("\">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.nature." + candidate))).append("</button>");
		}
		pOut.append("<label class=\"mdmf-hint\" style=\"display:inline-flex;align-items:center;gap:6px;margin-left:10px\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.filter.origin"))).append(" <select id=\"mdmfOriginSelect\">")
			.append("<option value=\"ALL\"").append(ReviewFilter.ALL.equals(origin) ? " selected=\"selected\"" : "").append(">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.origin.ALL"))).append("</option>");
		for (final String candidate : ReviewPage.ORIGINS)
		{
			pOut.append("<option value=\"").append(candidate).append("\"").append(candidate.equals(origin) ? " selected=\"selected\"" : "").append(">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.origin." + candidate))).append("</option>");
		}
		pOut.append("</select></label><label class=\"mdmf-hint\" style=\"display:inline-flex;align-items:center;gap:6px\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.filter.status"))).append(" <select id=\"mdmfStatusSelect\">")
			.append("<option value=\"ALL\"").append(ReviewFilter.ALL.equals(status) ? " selected=\"selected\"" : "").append(">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.status.ALL"))).append("</option>");
		for (final String candidate : ReviewPage.STATUSES)
		{
			pOut.append("<option value=\"").append(candidate).append("\"").append(candidate.equals(status) ? " selected=\"selected\"" : "").append(">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.status." + candidate))).append("</option>");
		}
		pOut.append("</select></label><label class=\"mdmf-hint\" style=\"display:inline-flex;align-items:center;gap:6px\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.filter.minConfidence")))
			.append(" <input type=\"number\" id=\"mdmfMinConfidenceInput\" min=\"0\" max=\"100\" style=\"width:64px\" value=\"")
			.append(String.valueOf(pFilter.minConfidence)).append("\"/></label>");
		// coordinator report, 2026-09-21: "Je ne vois pas les Ids des choses a revoir dans l'ecran" - a Reference
		// lookup (STEP-8, step 8, step8, or a bare 8 combined with the Nature filter above - see
		// ReviewFilter.matches) shows the named line whatever the other filters say, never hidden by Status:
		// Pending.
		pOut.append("<label class=\"mdmf-hint\" style=\"display:inline-flex;align-items:center;gap:6px\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.filter.reference")))
			.append(" <input type=\"text\" id=\"mdmfReferenceInput\" style=\"width:110px\" placeholder=\"")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.filter.reference.placeholder"))).append("\" value=\"")
			.append(ReviewPage.esc(pFilter.reference)).append("\"/></label>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"FILTER\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.filter.apply")))
			.append("</button></div>");
	}

	private static void renderBulk(final StringBuilder pOut, final int pBulkThreshold, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-actions\"><label class=\"mdmf-hint\" style=\"display:inline-flex;align-items:center;gap:6px\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.bulk.threshold")))
			.append(" <input type=\"number\" id=\"mdmfBulkThresholdInput\" min=\"0\" max=\"100\" style=\"width:64px\" value=\"").append(String.valueOf(pBulkThreshold))
			.append("\"/> %</label><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"BULK_ACCEPT_PREVIEW\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.bulk.button"))).append("</button></div>");
	}

	/**
	 * @param pAllLines the WHOLE project, unfiltered - only used, when {@code pVisibleLines} turns out empty,
	 *            to compute {@link ReviewData#statusCountsExcluding} for the "N lines match under another
	 *            status" links (task B, product report 2026-09-21: "the empty state must say how many lines
	 *            match the other filters ... and offer one-click links to show them").
	 */
	private static void renderTable(final StringBuilder pOut, final List<ReviewLine> pAllLines, final List<ReviewLine> pVisibleLines, final ReviewFilter pFilter,
		final Input pInput, final Locale pLocale)
	{
		if (pVisibleLines.isEmpty())
		{
			ReviewPage.renderEmptyState(pOut, pAllLines, pFilter, pLocale);
			return;
		}
		final List<ReviewLine> pLines = pVisibleLines;
		// fix-apply-ux (2026-09-21), item 3: a checkbox column (and the selection bar above the table) only
		// makes sense when at least one VISIBLE line is decidable (DecisionActions.Actions#decidable) - a
		// screen showing only APPLIED lines, say, gets neither: nothing there to select.
		boolean anySelectable = false;
		for (final ReviewLine line : pLines)
		{
			if (!line.primaryKey && DecisionActions.forStatus(line.status, line.nature).decidable())
			{
				anySelectable = true;
				break;
			}
		}
		if (anySelectable)
		{
			ReviewPage.renderSelectionBar(pOut, pLocale);
		}
		// fix-apply-ux (2026-09-21), item 5: the decision column carries its own class on BOTH th and td
		// (mdmf-col-decide) so review.css can pin it to the right edge of the horizontally-scrolling
		// .mdmf-table-wrap (product report, live screenshot: Accept clipped at a narrower pane width) - the
		// decision is always the one column a reviewer must still see while scrolling a wide row into view.
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr>");
		if (anySelectable)
		{
			// "select all shown" - toggles every .mdmf-row-select checkbox currently in the tbody (PAGE_JS);
			// first column, narrow (review.css mdmf-col-select) - item 3's own instruction
			// audit F-45: a checkbox needs a name a screen reader reads, not only a tooltip
			pOut.append("<th class=\"mdmf-col-select\"><input type=\"checkbox\" id=\"mdmfSelectAll\" title=\"")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.select.all"))).append("\" aria-label=\"").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.select.all")))
				.append("\"/></th>");
		}
		pOut.append("<th>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.col.nature"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.col.element"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.col.proposition"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.col.origin"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.col.confidence"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.col.rationale"))).append("</th><th class=\"mdmf-col-decide\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.col.decision"))).append("</th></tr></thead><tbody>");
		final int columnCount = (anySelectable ? 1 : 0) + 7;
		for (final ReviewLine line : pLines)
		{
			ReviewPage.renderRow(pOut, line, pLocale, anySelectable, pInput.feedDesignerUrls);
			if (ReviewLine.MATCH.equals(line.nature) && !line.primaryKey)
			{
				ReviewPage.renderMatchExpandedRow(pOut, line, pInput, columnCount, pLocale);
			}
		}
		pOut.append("</tbody></table></div>");
	}

	/**
	 * fix-apply-ux (2026-09-21), item 3: hidden by default (review.css), shown by PAGE_JS the moment at
	 * least one row checkbox is checked - "N selected" plus Accept/Reject selected (always offered once the
	 * bar is visible at all) and Apply selected (shown only while the current selection includes at least
	 * one decidable-by-apply line - PAGE_JS toggles it from each checkbox's own
	 * {@code data-mdmf-can-apply}). Clicking any of the three posts {@code mdmfSelectedIds} and previews the
	 * decision before anything is written (see {@code ProposalReviewUserService#previewSelected}) - never
	 * applied/decided directly from here.
	 */
	/**
	 * fix/review-assets (2026-09-21): product report - the count span read as glued directly onto "Accept
	 * selected" (no border/background/spacing at all, "Reject selected" looked like plain text next to the
	 * filled buttons) - the exact rendering a browser still serving a STALE {@code review.css} (missing
	 * {@code .mdmf-selection-bar}'s own {@code display:flex;gap:...}, review.css lines ~104-105) would
	 * produce. The count span's {@code style="margin-right:10px"} below is a DELIBERATE inline fallback, on
	 * top of (never instead of) the CSS class - the class still governs a browser with the current
	 * stylesheet (border/background/full gap between every child), the inline style alone is what still
	 * keeps the count from touching the first button under a stale one. See {@link #renderProgress}'s own
	 * Javadoc for the same idea applied to the progress strip's visibility.
	 */
	private static void renderSelectionBar(final StringBuilder pOut, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-selection-bar\" id=\"mdmfSelectionBar\"><span class=\"mdmf-hint\" id=\"mdmfSelectionCount\" style=\"margin-right:10px\"></span>")
			.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"SELECT_ACCEPT_PREVIEW\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.selection.accept"))).append("</button>")
			.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-quiet\" data-mdmf-action=\"SELECT_REJECT_PREVIEW\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.selection.reject"))).append("</button>")
			.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" id=\"mdmfSelectApplyBtn\" data-mdmf-action=\"SELECT_APPLY_PREVIEW\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.selection.apply"))).append("</button></div>");
	}

	/**
	 * The empty-state fix (task B): never a bare "nothing pending" when the project actually has proposals,
	 * just not under the CURRENT status filter - {@link ReviewData#statusCountsExcluding} finds every other
	 * status (same nature/origin/minConfidence) with at least one match, and each becomes a one-click button
	 * (the same {@code data-mdmf-action="FILTER"} technique every other filter control on this page already
	 * uses, with a new {@code data-mdmf-filter-status} attribute the page script also sets the status
	 * {@code <select>} from - see {@code ProposalReviewUserService.PAGE_JS}).
	 */
	private static void renderEmptyState(final StringBuilder pOut, final List<ReviewLine> pAllLines, final ReviewFilter pFilter, final Locale pLocale)
	{
		final List<ReviewData.StatusCount> others = ReviewData.statusCountsExcluding(pAllLines, pFilter);
		pOut.append("<div class=\"mdmf-notice mdmf-notice-target\"><p>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.empty"))).append("</p>");
		if (!others.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.empty.otherStatuses"))).append("</p><div class=\"mdmf-actions\">");
			for (final ReviewData.StatusCount other : others)
			{
				final String statusLabel = ReviewPage.esc(ReviewMessages.get(pLocale, "review.status." + other.status));
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"FILTER\" data-mdmf-filter-status=\"").append(ReviewPage.esc(other.status))
					.append("\">").append(statusLabel).append(" (").append(other.count).append(")</button>");
			}
			pOut.append("</div>");
		}
		pOut.append("</div>");
	}

	/**
	 * fix-apply-ux (2026-09-21), item 3: an empty cell for a non-decidable line (keeps the column aligned -
	 * "nobody thinks of selecting sourceSystem/sourceId either", same reasoning as the decision cell's own
	 * technical-key special case), otherwise a checkbox carrying everything {@code ProposalReviewUserService
	 * .PAGE_JS} needs with NO further lookup: which of Accept/Reject/Apply this line's CURRENT status allows
	 * ({@code data-mdmf-can-accept}/{@code -reject}/{@code -apply}), so the selection bar can show/hide
	 * "Apply selected" and the eventual server-side preview can be built from the exact same
	 * {@link DecisionActions} decision a single-row button would have used.
	 */
	private static void renderSelectCell(final StringBuilder pOut, final ReviewLine pLine, final Locale pLocale)
	{
		if (pLine.primaryKey)
		{
			pOut.append("<td class=\"mdmf-col-select\"></td>");
			return;
		}
		final DecisionActions.Actions actions = DecisionActions.forStatus(pLine.status, pLine.nature);
		if (!actions.decidable())
		{
			pOut.append("<td class=\"mdmf-col-select\"></td>");
			return;
		}
		// reject/repropose lot (2026-09-22): can-reject is false for a MATCH "No matching for this entity" (actions
		// .confirmReject) even when actions.reject itself is true - that action has no selection-bar confirmation
		// dialog, only its own single-row inline one (ReviewPage.renderMatchInlinePanels), so it must never read as
		// bulk-eligible here (ProposalReviewUserService.previewSelected enforces the same rule server-side).
		final boolean canReject = actions.reject && !actions.confirmReject;
		// audit F-45: each row's checkbox is named after the row (element and proposition)
		pOut.append("<td class=\"mdmf-col-select\"><input type=\"checkbox\" class=\"mdmf-row-select\" data-mdmf-nature=\"").append(ReviewPage.esc(pLine.nature))
			.append("\" data-mdmf-id=\"").append(ReviewPage.esc(pLine.rowId)).append("\" data-mdmf-can-accept=\"").append(actions.accept ? "1" : "0")
			.append("\" data-mdmf-can-reject=\"").append(canReject ? "1" : "0").append("\" data-mdmf-can-apply=\"").append(actions.apply ? "1" : "0")
			.append("\" aria-label=\"").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.select.row", ReviewPage.rowName(pLine)))).append("\"/></td>");
	}

	/**
	 * The coordinator's own short reference ("MAP-63") next to the nature badge - coordinator report,
	 * 2026-09-21: "Je ne vois pas les Ids des choses a revoir dans l'ecran", he reads the factory
	 * tables by id and had no way to point the product owner at a line. Small, monospace, muted ({@code .mdmf}
	 * already sets {@code code}'s font-family to monospace; review.css's {@code .mdmf-ref} adds the
	 * small/muted look), plain selectable/copyable text (no button, no link), with a {@code title}
	 * tooltip naming the table and key ("AttributeMapping 63") - both strings come from
	 * {@link ReviewReference}, the ONE pure place that also feeds the Reference search box
	 * ({@link #renderFilters}) and the REST side ({@code ebx.CapabilityApplier}).
	 */
	private static void renderReferenceBadge(final StringBuilder pOut, final ReviewLine pLine)
	{
		final String reference = ReviewReference.format(pLine.nature, pLine.rowId);
		if (reference == null)
		{
			return;
		}
		final String tooltip = ReviewReference.tooltip(pLine.nature, pLine.rowId);
		pOut.append(" <code class=\"mdmf-ref\"");
		if (tooltip != null)
		{
			pOut.append(" title=\"").append(ReviewPage.esc(tooltip)).append("\"");
		}
		pOut.append(">").append(ReviewPage.esc(reference)).append("</code>");
	}

	/** @param pShowSelectColumn whether the table has a checkbox column at all this render (item 3) - decided once, per {@link #renderTable}, from whether ANY visible line is decidable; when true, every row still gets a (possibly empty) select cell so the column stays aligned. */
	private static void renderRow(final StringBuilder pOut, final ReviewLine pLine, final Locale pLocale, final boolean pShowSelectColumn,
		final Map<String, String> pFeedDesignerUrls)
	{
		// fix-apply-ux (2026-09-21), item 5: element/proposition are the two columns most likely to carry a
		// long value (a full attribute path, a multi-clause rationale-like proposition) - mdmf-col-wrap lets
		// them absorb the squeeze (word-break, capped width) instead of forcing the whole table wider than
		// the decision column can still be reached from (review.css)
		pOut.append("<tr data-mdmf-nature=\"").append(ReviewPage.esc(pLine.nature)).append("\" data-mdmf-origin=\"").append(ReviewPage.esc(pLine.origin)).append("\" data-mdmf-status=\"")
			.append(ReviewPage.esc(pLine.status)).append('"');
		// tree-diagram-labels fix lot (2026-09-22), item B: an anchor target for the top-of-page counter (and
		// for any future direct link) - only a MATCH line ever has an expanded row right after it (the same
		// !primaryKey guard renderTable itself uses before calling renderMatchExpandedRow at all).
		if (ReviewLine.MATCH.equals(pLine.nature) && !pLine.primaryKey)
		{
			pOut.append(" id=\"mdmf-line-").append(ReviewPage.esc(pLine.rowId)).append('"');
		}
		pOut.append('>');
		if (pShowSelectColumn)
		{
			ReviewPage.renderSelectCell(pOut, pLine, pLocale);
		}
		pOut.append("<td><span class=\"mdmf-chip mdmf-chip-accent\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.nature." + pLine.nature))).append("</span>");
		ReviewPage.renderReferenceBadge(pOut, pLine);
		pOut.append("</td><td class=\"mdmf-col-wrap\">")
			.append(ReviewPage.esc(pLine.element));
		if (pLine.primaryKey)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.key.technical"))).append("</span>");
		}
		else if (pLine.businessKey)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.key.business"))).append("</span>");
		}
		// feed-designer lot (2026-09-23): a FEED line whose root feed has a designer URL (ReviewPage.Input's
		// own Javadoc explains why only a root feed ever has one) - target="_top" so it opens as a whole page,
		// never inside a modal iframe (CLAUDE.md sec 9), same as WizardPage's copy of this same link.
		if (ReviewLine.FEED.equals(pLine.nature) && pFeedDesignerUrls.containsKey(pLine.rowId))
		{
			pOut.append(" <a class=\"mdmf-btn mdmf-btn-sm mdmf-feed-designer-link\" href=\"").append(ReviewPage.esc(pFeedDesignerUrls.get(pLine.rowId)))
				.append("\" target=\"_top\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.feed.designer"))).append("</a>");
		}
		pOut.append("</td><td class=\"mdmf-col-wrap\">").append(ReviewPage.esc(pLine.proposition)).append("</td><td><span class=\"mdmf-chip mdmf-chip-accent\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.origin." + pLine.origin))).append("</span></td><td>");
		if (pLine.confidence == null)
		{
			pOut.append("&mdash;");
		}
		else
		{
			final int confidence = Math.max(0, Math.min(100, pLine.confidence.intValue()));
			pOut.append("<span class=\"mdmf-conf\"><span class=\"mdmf-conf-bar\"><i style=\"width:").append(String.valueOf(confidence)).append("%\"></i></span>")
				.append(String.valueOf(confidence)).append(" %</span>");
		}
		pOut.append("</td><td>");
		if (pLine.rationale != null && !pLine.rationale.isEmpty())
		{
			pOut.append("<details class=\"mdmf-rationale\"><summary>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.rationale.show")))
				.append("</summary><p>").append(ReviewPage.esc(pLine.rationale)).append("</p></details>");
		}
		// apply lot (2026-09-21): an APPLIED SCRIPT_CONSTRAINT line shows what the constraint does and what it
		// found - a reviewer who will never read the script source (product owner's own decision)
		if (pLine.explanation != null && !pLine.explanation.isEmpty())
		{
			pOut.append("<p class=\"mdmf-capability-explanation\">").append(ReviewPage.esc(pLine.explanation)).append("</p>");
		}
		if (pLine.measuredEffect != null && !pLine.measuredEffect.isEmpty())
		{
			pOut.append("<p class=\"mdmf-capability-effect\">").append(ReviewPage.esc(pLine.measuredEffect)).append("</p>");
		}
		// fix-apply-ux (2026-09-21), item 5: the status badge sits on its OWN line (mdmf-decide-status-row),
		// the action buttons in a SEPARATE wrapping flex row below (mdmf-decide-actions) - product report, live
		// screenshot: Accept clipped when the pane was not full width, because the previous single flex row put
		// the badge and every button side by side with nothing forcing an early wrap. mdmf-col-decide (also on
		// the th, above) pins this column to the right edge of the horizontally-scrolling table.
		pOut.append("</td><td class=\"mdmf-decide mdmf-col-decide\">");
		// the technical key (primaryKey) has no decision of its own (TechnicalKeyRule, coordinator review 2026-09-20:
		// "nobody thinks of accepting sourceSystem and sourceId, and they should not have to") - ONE chip only
		// ("show the status once", 2026-09-21: the earlier version showed the ordinary status chip here too),
		// never a status chip AND this one, and never an Accept/Reject button whatever its status
		if (pLine.primaryKey)
		{
			pOut.append("<div class=\"mdmf-decide-status-row\"><span class=\"mdmf-decide-status mdmf-chip mdmf-chip-accent\">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.key.followsEntity"))).append("</span></div>");
		}
		else
		{
			final String statusChip = ReviewPage.APPLIED_STATUS.equals(pLine.status) ? "mdmf-chip-real" : ReviewLine.ACCEPTED.equals(pLine.status) ? "mdmf-chip-real"
				: ReviewLine.REJECTED.equals(pLine.status) ? "mdmf-chip-bad" : "mdmf-chip-target";
			pOut.append("<div class=\"mdmf-decide-status-row\"><span class=\"mdmf-decide-status mdmf-chip ").append(statusChip).append("\"");
			// SCRIPT_CONSTRAINT and SCRIPT_DERIVATION (script-backed-derivation lot, 2026-09-23) are the natures
			// an APPLIED line CAN still be changed on (Remove, apply lot 2026-09-21) - the "cannot be changed any
			// more" title would be misleading right next to that very button
			if (ReviewPage.APPLIED_STATUS.equals(pLine.status) && !ReviewLine.SCRIPT_CONSTRAINT.equals(pLine.nature) && !ReviewLine.SCRIPT_DERIVATION.equals(pLine.nature))
			{
				pOut.append(" title=\"").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.applied.title"))).append("\"");
			}
			pOut.append(">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.status." + pLine.status))).append("</span>");
			// Re-apply lot (2026-09-21): a discreet marker right next to the status chip - never on its own
			// line, never instead of the status - "Out of date" with the plain-words reason in its tooltip;
			// pLine.stale is only ever true for an APPLIED SCRIPT_CONSTRAINT line (ProposalReviews only computes
			// it for that case), so no extra nature/status check is needed here.
			if (pLine.stale)
			{
				pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\" title=\"").append(ReviewPage.esc(pLine.staleReason)).append("\">")
					.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.capability.stale"))).append("</span>");
			}
			pOut.append("</div>");
			// reject/repropose lot (2026-09-22): a MATCH line's own reviewComment (why it was set aside/ruled out)
			// and matchNote (the system's own note on the last "Propose another" attempt) - both escaped, both
			// under the status, never instead of it ("show the status once" still holds - this is EXTRA context,
			// not a replacement chip).
			if (ReviewLine.MATCH.equals(pLine.nature))
			{
				if (pLine.reviewComment != null && !pLine.reviewComment.isEmpty())
				{
					pOut.append("<p class=\"mdmf-match-comment\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.comment.label")))
						.append(" “").append(ReviewPage.esc(pLine.reviewComment)).append("”</p>");
				}
				if (pLine.matchNote != null && !pLine.matchNote.isEmpty())
				{
					pOut.append("<p class=\"mdmf-match-note\">").append(ReviewPage.esc(pLine.matchNote)).append("</p>");
				}
			}
			pOut.append("<div class=\"mdmf-decide-actions\">");
			// exactly the actions this status still allows (product owner, 2026-09-21, live screenshot: "the
			// Accepted tag seems to be set, but I can still Accept them" - the previous version drew both
			// buttons on every non-APPLIED row regardless of its current status) - shared with the wizard's
			// staging step so the two screens cannot drift, see DecisionActions' own Javadoc for why ACCEPTED
			// offers only Reject and not an "undo to PROPOSED". The nature-aware overload additionally offers
			// Remove on an APPLIED SCRIPT_CONSTRAINT line (apply lot, 2026-09-21) - every other nature/status
			// combination behaves exactly like the plain overload. The STALENESS-aware overload (Re-apply lot)
			// additionally offers Re-apply on a stale one - see DecisionActions.forStatus(String, String, boolean).
			final DecisionActions.Actions actions = DecisionActions.forStatus(pLine.status, pLine.nature, pLine.stale);
			if (actions.accept)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"ACCEPT\" data-mdmf-nature=\"").append(ReviewPage.esc(pLine.nature))
					.append("\" data-mdmf-id=\"").append(ReviewPage.esc(pLine.rowId)).append("\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.accept")))
					.append("</button>");
			}
			// reject/repropose lot (2026-09-22): a MATCH "No matching for this entity" never submits directly - it
			// opens its own inline confirmation panel instead (rendered below, after this actions row) via
			// data-mdmf-toggle, never data-mdmf-action, so a click here can never bypass the checkbox confirmation.
			if (actions.reject && actions.confirmReject)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-quiet\" data-mdmf-toggle=\"mdmf-match-noMatch-").append(ReviewPage.esc(pLine.rowId))
					.append("\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.noMatching"))).append("</button>");
			}
			else if (actions.reject)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-quiet\" data-mdmf-action=\"REJECT\" data-mdmf-nature=\"").append(ReviewPage.esc(pLine.nature))
					.append("\" data-mdmf-id=\"").append(ReviewPage.esc(pLine.rowId)).append("\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.reject")))
					.append("</button>");
			}
			if (actions.repropose)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"mdmf-match-repropose-").append(ReviewPage.esc(pLine.rowId))
					.append("\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.repropose"))).append("</button>");
			}
			if (actions.apply)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"APPLY\" data-mdmf-nature=\"").append(ReviewPage.esc(pLine.nature))
					.append("\" data-mdmf-id=\"").append(ReviewPage.esc(pLine.rowId)).append("\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.apply")))
					.append("</button>");
			}
			if (actions.measureAgain)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"MEASURE\" data-mdmf-nature=\"").append(ReviewPage.esc(pLine.nature))
					.append("\" data-mdmf-id=\"").append(ReviewPage.esc(pLine.rowId)).append("\">")
					.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.measureAgain"))).append("</button>");
			}
			if (actions.reapply)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"REAPPLY\" data-mdmf-nature=\"").append(ReviewPage.esc(pLine.nature))
					.append("\" data-mdmf-id=\"").append(ReviewPage.esc(pLine.rowId)).append("\">")
					.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.reapply"))).append("</button>");
			}
			if (actions.remove)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-quiet\" data-mdmf-action=\"REMOVE\" data-mdmf-nature=\"").append(ReviewPage.esc(pLine.nature))
					.append("\" data-mdmf-id=\"").append(ReviewPage.esc(pLine.rowId)).append("\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.remove")))
					.append("</button>");
			}
			pOut.append("</div>");
			ReviewPage.renderMatchInlinePanels(pOut, pLine, actions, pLocale);
		}
		pOut.append("</td></tr>");
	}

	/**
	 * review-match-plan lot (2026-09-22): the "Matching" line's expanded body - the decision tree, the
	 * per-field evidence, and the simulation block (`docs/mockups/review-match-plan.html`) - a second, wide
	 * {@code <tr>} right after the line's own row, wrapped in a native {@code <details>} (collapsible: no
	 * round trip needed either to expand OR to collapse it, a plain browser feature). {@link
	 * ReviewLine#matchRequirementsJson} is null for a line with no plan content to show (should not happen for
	 * a real {@link ReviewLine#MATCH} row, but never assumed) - nothing is rendered in that case rather than an
	 * empty/broken block.
	 * <p>
	 * <b>tree-diagram-labels fix lot (2026-09-22), item C</b> ({@code docs/MATCHING.md} &sect;16.10 - product
	 * report, the product owner: an expanded Company line pushed the Contact line far enough down the page that he never
	 * noticed it): EVERY status now renders COLLAPSED by default (this used to open PROPOSED/SUPERSEDED/
	 * ACCEPTED/APPLIED and only keep REJECTED collapsed - a line "worth seeing at a glance" was exactly the
	 * problem). The one exception is {@link Input#expandedMatchRowIds} - the rowId the CURRENT round trip just
	 * acted on (Simulate, Accept, Reject, a comment...) reopens automatically, so a click inside the panel
	 * never reads as "did nothing" after the repaint. The {@code <summary>} itself is the "unmistakable
	 * affordance to expand" the task brief asked for - review.css styles it as a full-width, chevroned button
	 * row, never a plain inline link - and additionally carries the line's own position among every Matching
	 * proposal still awaiting review ("2 / 3", {@link #matchLinesToReview}) so a reviewer working through them
	 * in order always knows how many are left. A {@link ReviewLine#SUPERSEDED} line keeps its explicit
	 * "superseded - waiting for a new proposal" note right above the tree (unaffected by collapsing - it is
	 * still there the moment the panel opens, never assuming a reviewer remembers what the status chip meant).
	 */
	private static void renderMatchExpandedRow(final StringBuilder pOut, final ReviewLine pLine, final Input pInput, final int pColumnCount, final Locale pLocale)
	{
		if (pLine.matchRequirementsJson == null)
		{
			return;
		}
		final Boolean canSimulate = pInput.matchCanSimulate.get(pLine.rowId);
		// Survivorship-review lot (2026-09-22): pLine.survivorshipInput is null until ebx.ProposalReviews
		// .attachSurvivorshipInputs has run for this line (or for a plan whose entity that pass could not resolve)
		// - MatchPlanView.Input's own constructor already degrades a null to MatchSurvivorshipBlock.emptyInput(),
		// so section 4 still renders its fixed sentences/technical rows rather than failing.
		// automerge-toggle lot (2026-09-23): this is the ONLY caller that passes pLine.status through - see
		// MatchPlanView.Input#matchPlanStatus's own Javadoc for why (the control is scoped to this screen).
		final MatchPlanView.Input viewInput = new MatchPlanView.Input(pLine.element, pLine.matchRequirementsJson, pLine.matchEvidenceJson, pLine.matchAutoMerge,
			Boolean.TRUE.equals(canSimulate), pInput.matchCannotSimulateReason.get(pLine.rowId), pInput.matchSimulationResults.get(pLine.rowId),
			pInput.matchMaxSimulationPairs, pLine.rowId, pLocale, pLine.survivorshipInput, pLine.status);
		final boolean openByDefault = pInput.expandedMatchRowIds.contains(pLine.rowId);
		pOut.append("<tr class=\"mdmf-match-expanded-row\"><td colspan=\"").append(pColumnCount).append("\"><details").append(openByDefault ? " open=\"open\"" : "")
			.append("><summary>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.expand.summary")));
		final List<ReviewLine> toReview = ReviewPage.matchLinesToReview(pInput.allLines);
		final int position = toReview.indexOf(pLine);
		if (position >= 0)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-muted mdmf-match-position\">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.position", Integer.valueOf(position + 1), Integer.valueOf(toReview.size()))))
				.append("</span>");
		}
		pOut.append("</summary>");
		if (ReviewLine.SUPERSEDED.equals(pLine.status))
		{
			pOut.append("<p class=\"mdmf-match-note\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.superseded.note"))).append("</p>");
		}
		pOut.append(MatchPlanView.render(viewInput));
		pOut.append("</details></td></tr>");
	}

	/**
	 * Reject/repropose lot (2026-09-22): the two MATCH-only inline panels, both hidden until their own toggle
	 * button (rendered above, in {@link #renderRow}) reveals them - plain DOM, posted through the SAME hidden-
	 * submit mechanism as every other action on this page (CLAUDE.md &sect;9: one real submit button, everything
	 * else a {@code type="button"} that fills hidden fields first): a real {@code <textarea>} the page's own
	 * script ({@code ProposalReviewUserService.pageJs}) copies into the hidden {@code mdmfComment} field via
	 * {@code data-mdmf-comment-from} right before clicking the confirm/send button, which itself carries the
	 * ordinary {@code data-mdmf-action}/{@code -nature}/{@code -id} triad every other action button already
	 * uses. "No matching for this entity" additionally requires a checkbox ({@code data-mdmf-requires-checkbox}
	 * on its confirm button) - the script refuses to submit at all while it is unchecked, so this action can
	 * never fire without the explicit confirmation the product asked for.
	 */
	private static void renderMatchInlinePanels(final StringBuilder pOut, final ReviewLine pLine, final DecisionActions.Actions pActions, final Locale pLocale)
	{
		final String id = ReviewPage.esc(pLine.rowId);
		if (pActions.repropose)
		{
			final String panelId = "mdmf-match-repropose-" + id;
			final String textareaId = panelId + "-text";
			pOut.append("<div class=\"mdmf-match-inline\" id=\"").append(panelId).append("\" style=\"display:none\">")
				.append("<textarea class=\"mdmf-match-comment-input\" id=\"").append(textareaId).append("\" aria-label=\"")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.repropose.placeholder"))).append("\" placeholder=\"")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.repropose.placeholder"))).append("\"></textarea>")
				.append("<div class=\"mdmf-actions\">")
				.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"REPROPOSE\" data-mdmf-nature=\"").append(ReviewPage.esc(pLine.nature))
				.append("\" data-mdmf-id=\"").append(id).append("\" data-mdmf-comment-from=\"").append(textareaId).append("\">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.repropose.send"))).append("</button>")
				.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"").append(panelId).append("\">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.comment.cancel"))).append("</button></div></div>");
		}
		if (pActions.reject && pActions.confirmReject)
		{
			final String panelId = "mdmf-match-noMatch-" + id;
			final String textareaId = panelId + "-text";
			final String checkboxId = panelId + "-confirm";
			pOut.append("<div class=\"mdmf-match-inline\" id=\"").append(panelId).append("\" style=\"display:none\">")
				.append("<label class=\"mdmf-hint\"><input type=\"checkbox\" id=\"").append(checkboxId).append("\"/> ")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.noMatching.confirm"))).append("</label>")
				.append("<textarea class=\"mdmf-match-comment-input\" id=\"").append(textareaId).append("\" aria-label=\"")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.noMatching.placeholder"))).append("\" placeholder=\"")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.noMatching.placeholder"))).append("\"></textarea>")
				.append("<div class=\"mdmf-actions\">")
				.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-quiet\" data-mdmf-action=\"REJECT\" data-mdmf-nature=\"").append(ReviewPage.esc(pLine.nature))
				.append("\" data-mdmf-id=\"").append(id).append("\" data-mdmf-comment-from=\"").append(textareaId).append("\" data-mdmf-requires-checkbox=\"")
				.append(checkboxId).append("\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.noMatching.send"))).append("</button>")
				.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"").append(panelId).append("\">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.comment.cancel"))).append("</button></div></div>");
		}
	}

	private static final String APPLIED_STATUS = ReviewLine.APPLIED;

	static String esc(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}

	/** Audit F-45: what names a row for a screen reader - its element and its proposition, as the row shows them. */
	static String rowName(final ReviewLine pLine)
	{
		final String element = pLine.element == null ? "" : pLine.element.trim();
		final String proposition = pLine.proposition == null ? "" : pLine.proposition.trim();
		return element.isEmpty() ? proposition : proposition.isEmpty() ? element : element + " - " + proposition;
	}
}
