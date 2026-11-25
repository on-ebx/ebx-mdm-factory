package com.on.ebx.factory.mappingmatrix;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.staging.CheckNote;
import com.on.ebx.factory.wizard.RationaleText;

/**
 * Pure HTML/JS renderer of the Mapping matrix screen (docs/MAPPING-MATRIX.md) - no EBX type, unit-tested by
 * checking the rendered string for expected fragments ({@code MatrixHtmlTest}/{@code MatrixComputeTest}),
 * the same convention {@code wizard.WizardPage}/{@code review.ReviewPage} already follow.
 * <p>
 * <b>fix/mapping-matrix-live (2026-09-24) - the JS never ran:</b> the first cut of this class wrote its own
 * interaction code as a hand-authored {@code <script>...</script>} tag inside the pane's HTML. EBX does not
 * execute a script added that way (CLAUDE.md &sect;9: "JavaScript goes through {@code addJS(String)}, not a
 * hand-written {@code <script>} tag") - every click handler was dead: no entity-card navigation, no cell
 * selection, no inspector, no Accept/Reject. {@link #render} now returns ONLY html (no script tag anywhere -
 * {@code MatrixHtmlTest} asserts this); {@link #script} returns the same JS as a plain string, and {@code
 * ebx.ui.MappingMatrixUserService#write} feeds it to {@code UserServicePaneWriter#addJS_cr} instead - the
 * exact mechanism {@code ProjectWizardUserService}/{@code FeedDesignerUserService} already use for their own
 * (much larger) inline scripts. The click handlers themselves stay plain inline {@code onclick="..."}
 * attributes (never rewritten to a data-attribute/addEventListener scheme): once the script genuinely runs,
 * the {@code window.mdmmX} functions it defines exist before any click can reach them (the script always
 * executes synchronously, right after the HTML that references it, on every render), so inline handlers work
 * correctly - CLAUDE.md &sect;9's own "at least make sure the globals exist before any click" is satisfied by
 * fixing the delivery mechanism alone.
 * <p>
 * <b>What is genuinely client-side, and what is a postback (documented simplifications against the full
 * mockup, docs/MAPPING-MATRIX.md "Simplifications"):</b> selecting a cell (the inspector fills from the
 * cell's own {@code data-cell} JSON attribute, {@link #cellJson}, no round trip - the one interaction the
 * task brief called out explicitly), the "only what's left to do" toggle and the source-focus highlight
 * (both plain CSS class toggles) are pure client-side JS. Switching the active entity tab, and every
 * accept/reject/bulk-accept decision (entity, attribute, feed, mapping), are real form submits (one shared
 * hidden submit button, {@code mdmmSubmit}, driven by hidden fields {@code mdmmAction}/{@code mdmmRowId}/
 * {@code mdmmRowId2}/{@code mdmmComment}/{@code mdmmEntity}/{@code mdmmFocus} - the exact {@code
 * data-mdmf-action} convention {@code ebx.ui.ProjectWizardUserService} already uses).
 * <p>
 * <b>Coordinator review (2026-09-23) - the missing decisions:</b> the toolbar's entity Accept/Reject, the row
 * header's attribute Accept/Reject (skipping the technical {@link MatrixModel.Attr#sourceKey} row), the
 * column header's feed Accept/Reject (with an optional reject comment stored in {@code
 * TransferFeed.reviewComment}), and the cell's own two-step "Accept the attribute and this mapping" - never
 * silently cascading to the entity ({@link MatrixCompute#cellAcceptAction}).
 * <p>
 * <b>Coordinator review (2026-09-24) - the overview graphic:</b> the curves and the absolutely-positioned
 * cards now share ONE pixel coordinate system ({@link #OV_WIDTH}: the SVG has explicit {@code width}/{@code
 * height} attributes instead of a scaled {@code viewBox}, and the container is pinned to the same width) -
 * {@code x1}/{@code x2} are the actual right/left edges of the source/entity cards, not independently chosen
 * numbers. Each curve now carries a status chip ({@link MatrixCompute#curveChipPoint}/{@link
 * MatrixCompute#chipT}, computed but never rendered before this fix), and a source card shows the landing
 * table name(s) feeding it, next to its tag.
 */
public final class MatrixHtml
{
	/** The overview's own fixed pixel width - the ONE coordinate system the SVG curves and the absolutely-positioned cards both use (class Javadoc, fix/mapping-matrix-live). */
	private static final int OV_WIDTH = 1180;
	/** Right edge of a source card ({@code left:10px;width:200px}, see {@link #renderStyle}) - where every curve starts. */
	private static final double OV_X1 = 210;
	/** Left edge of an entity card ({@code left:920px}, see {@link #renderStyle}) - where every curve ends. */
	private static final double OV_X2 = 920;

	private MatrixHtml()
	{
	}

	/** Everything about the CURRENT rendering that is not part of the data itself - carried across a postback via hidden fields the caller re-reads (see the class Javadoc). */
	public static final class State
	{
		public Locale locale = Locale.ENGLISH;
		public String activeEntityId;
		/** Highlighted source of the overview graphic - client-side only, but echoed back through a hidden field so it survives a decision postback. */
		public String focusSourceId;
		public boolean onlyTodo;
		/** {@code feedId|attributeId} of the cell to pre-select on load (defaults to the first data cell when null/not found). */
		public String selectedCellKey;
		public int bulkThreshold = MatrixCompute.DEFAULT_BULK_THRESHOLD;
		public String message;
		public boolean messageIsError;
		public boolean readOnly;
		/** fix/wizard-live-wave1 (2026-09-24, live bug item 2): the wizard's own URL (step 4, Staging), set by {@code ebx.ui.MappingMatrixUserService} when a project is known - null/empty renders no "Back to the wizard" link. */
		public String wizardUrl;
		/** Validation desk lot L1: the desk's URL (the fourth view of step 4), set by {@code ebx.ui.MappingMatrixUserService} - null/empty draws the entry as a plain label. */
		public String deskUrl;
		/** The "left to decide" panel: "" closed, "open" listing what needs a person, "all" listing everything the footer counts - survives a postback through the {@code mdmmQueue} hidden field. */
		public String queueMode = "";
		/** True on the render that follows a postback (a decision, a tab switch): the script then scrolls the selected item into view. Never on a first load, which opens at the top. */
		public boolean scrollToSelection;
		/**
		 * Staging-screens lot (2026-10-17): the scroll offset of the page's scrolling container as the person left it, posted back in {@code mdmmScroll}; the script puts it
		 * back after the render so a click on the queue no longer sends the page to the top. Negative = none (first display).
		 */
		public int scrollTop = -1;
		/** Staging-screens lot (2026-10-17): the staging build was started by this postback - the page goes back to the wizard's step 4, where the live panel is. */
		public boolean goToWizard;
	}

	/** HTML only - see the class Javadoc for why the interaction script is a SEPARATE method now ({@link #script}), never embedded as a {@code <script>} tag here. */
	public static String render(final MatrixModel.Project pProject, final State pState)
	{
		final Locale locale = pState.locale == null ? Locale.ENGLISH : pState.locale;
		final MatrixModel.Entity active = pProject.entityById(pState.activeEntityId);
		final StringBuilder out = new StringBuilder(16384);
		out.append("<div id=\"mdmm\" class=\"mdmmRoot").append(pState.onlyTodo ? " mdmmOnlyTodo" : "").append("\" data-focus=\"")
			.append(MatrixHtml.esc(pState.focusSourceId == null ? "" : pState.focusSourceId)).append("\" data-scroll=\"").append(pState.scrollToSelection ? "1" : "0").append("\" data-scrolltop=\"").append(pState.scrollTop)
			.append("\" data-goto=\"").append(pState.goToWizard ? "1" : "0").append("\">");
		MatrixHtml.renderStyle(out);
		// fix/wizard-live-wave1 (2026-09-24, live bug item 2): a visible way back to the wizard, top-left, same
		// style/position as the wizard's own leave links - Close alone (FactoryHtml#originReturnScript) only
		// works when this screen was reached from the wizard in THIS SAME session; a bookmarked/reopened tab has
		// no such token. Omitted when the caller has no wizard URL to offer (e.g. no project resolved).
		if (pState.wizardUrl != null && !pState.wizardUrl.isEmpty())
		{
			out.append("<div class=\"mdmmBackToWizard\"><a id=\"mdmmBackLink\" class=\"mdmf-matrix-designer-link\" target=\"_top\" href=\"").append(MatrixHtml.esc(pState.wizardUrl)).append("\">")
				.append("&larr; ").append(MatrixHtml.esc(MatrixMessages.get(locale, "mappingMatrix.backToWizard"))).append("</a></div>");
		}
		if (pState.message != null)
		{
			out.append("<div class=\"mdmmMsg").append(pState.messageIsError ? " mdmmErr" : "").append("\">").append(MatrixHtml.esc(pState.message)).append("</div>");
		}
		out.append("<div class=\"mdmmTitle\" style=\"display:flex;align-items:flex-end;justify-content:space-between;gap:16px\"><div><div class=\"mdmmEyebrow\">")
			.append(MatrixHtml.esc(MatrixMessages.get(locale, "mappingMatrix.eyebrow", pProject.projectLabel))).append("</div><h1>")
			.append(MatrixHtml.esc(MatrixMessages.get(locale, "mappingMatrix.title"))).append("</h1><p>").append(MatrixHtml.esc(MatrixMessages.get(locale, "mappingMatrix.lead")))
			.append("</p></div>");
		// delta 6: Matrix | Designer segmented control - Designer opens the active object's own ROOT
		// source link (the only kind core.FeedDesignerLink accepts, MatrixModel.Feed class Javadoc).
		MatrixHtml.renderModeSwitch(out, active, pState.focusSourceId, locale, pState.deskUrl);
		out.append("</div>");

		// UX lot U6: ONE tally per render, from the very lists the queue panel is drawn from - the header line, the step boxes,
		// the footer and the panel can never disagree (MatrixCounter).
		final List<MatrixCompute.StillItem> items = MatrixCompute.stillToDecide(pProject, pState.bulkThreshold);
		final List<MatrixCompute.ServiceGroup> groups = MatrixCompute.optionalServices(pProject);
		final MatrixCounter.Tally tally = MatrixCounter.of(items, groups, MatrixCompute.planProjectBulk(pProject, pState.bulkThreshold).toDecide.size());
		// delta 5: the three-step guidance banner, the summary sentence and the glossary line - above the overview.
		MatrixHtml.renderGuidance(out, pProject, pState, locale, tally);

		MatrixHtml.renderOverview(out, pProject, pState, locale, items);

		if (active == null)
		{
			out.append("<p class=\"mdmmEmpty\">").append(MatrixHtml.esc(MatrixMessages.get(locale, "mappingMatrix.noEntity"))).append("</p></div>");
			MatrixHtml.renderHiddenFields(out, pState);
			return out.toString();
		}

		// matrix review queue lot (2026-09-30): ONE list feeds the footer's count, the "left to decide" panel, the
		// tab counters, the row filter, the walk-through and the default selection (MatrixCompute#stillToDecide).
		if (!MatrixCompute.ownsKey(active, pState.selectedCellKey))
		{
			// nothing selected yet (or a selection left over from another business object's tab): the first thing
			// still to decide in THIS object, not the first data cell - which is often the source key
			final MatrixCompute.StillItem first = MatrixCompute.firstOfEntity(items, active.id);
			pState.selectedCellKey = first == null ? null : first.key;
		}
		MatrixHtml.renderToolbar(out, pProject, active, pState, locale, items);
		MatrixHtml.renderMatrix(out, pProject, active, pState, locale, items);
		// the inspector, its walk-through bar, the list panel and the footer form ONE block pinned under the grid
		MatrixHtml.renderDock(out, pProject, pState, locale, items, groups, tally);
		MatrixHtml.renderHiddenFields(out, pState);
		out.append("</div>");
		return out.toString();
	}

	/**
	 * Delta 6: a plain segmented control, Matrix (current screen, always "on") | Designer. fix/designer-all-sources
	 * (2026-10-19): Designer opens the root feed of the HIGHLIGHTED source when one is (mdmmFocus keeps the link in
	 * step client-side through {@code data-by-source}), else the object's first root feed - the designer then lists
	 * every application of the object anyway.
	 */
	private static void renderModeSwitch(final StringBuilder pOut, final MatrixModel.Entity pActive, final String pFocusSourceId, final Locale pLocale,
		final String pDeskUrl)
	{
		String designerUrl = "";
		final StringBuilder bySource = new StringBuilder("{");
		if (pActive != null)
		{
			for (final MatrixModel.Feed feed : pActive.feeds)
			{
				if (feed.parentFeedId != null || feed.designerUrl.isEmpty())
				{
					continue;
				}
				if (designerUrl.isEmpty() || (feed.sourceId != null && feed.sourceId.equals(pFocusSourceId)))
				{
					designerUrl = feed.designerUrl;
				}
				if (feed.sourceId != null && bySource.indexOf(MatrixHtml.jsonQuote(feed.sourceId) + ":") < 0)
				{
					if (bySource.length() > 1)
					{
						bySource.append(',');
					}
					bySource.append(MatrixHtml.jsonQuote(feed.sourceId)).append(':').append(MatrixHtml.jsonQuote(feed.designerUrl));
				}
			}
		}
		bySource.append('}');
		pOut.append("<nav class=\"mdmmSeg\" aria-label=\"").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.mode.label"))).append("\">");
		// Validation desk lot L1: the desk is the other view of the same decisions, first in the switch (same voluntary-departure class as the Designer link)
		if (pDeskUrl != null && !pDeskUrl.isEmpty())
		{
			pOut.append("<a id=\"mdmmDeskSwitch\" class=\"mdmf-matrix-designer-link\" target=\"_top\" href=\"").append(MatrixHtml.esc(pDeskUrl)).append("\">")
				.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.mode.desk"))).append("</a>");
		}
		pOut.append("<span class=\"on\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.mode.matrix"))).append("</span>");
		if (designerUrl.isEmpty())
		{
			pOut.append("<span>").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.mode.designer"))).append("</span>");
		}
		else
		{
			// same "no return token, voluntary departure only" simplification as the inspector's own Designer link
			// (fix/mapping-matrix-live sec 10.6) - class mdmf-matrix-designer-link is armed the same way.
			pOut.append("<a id=\"mdmmDesignerSwitch\" class=\"mdmf-matrix-designer-link\" target=\"_top\" href=\"").append(MatrixHtml.esc(designerUrl))
				.append("\" data-default=\"").append(MatrixHtml.esc(designerUrl)).append("\" data-by-source=\"").append(MatrixHtml.esc(bySource.toString())).append("\">")
				.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.mode.designer"))).append("</a>");
		}
		pOut.append("</nav>");
	}

	/**
	 * Delta 5: the three-step banner with live counts, the one-sentence summary, and the glossary line. UX lot U6: the "points need your
	 * attention" line and the footer's "items need you" are the SAME {@link MatrixCounter.Tally#needYou}; the first box counts the flagged
	 * fields still waiting; the second reads done when nothing is left.
	 */
	private static void renderGuidance(final StringBuilder pOut, final MatrixModel.Project pProject, final State pState, final Locale pLocale,
		final MatrixCounter.Tally pTally)
	{
		final MatrixCompute.ProjectSummary summary = MatrixCompute.summarize(pProject);
		// fix/wizard-live-wave1 (2026-09-24, live bug item 12): plain words + plural keys, never a bare "(s)" -
		// each count is pre-pluralized through the SAME wizard.unit.* convention the wizard screens already use
		// (com.on.ebx.factory.wizard.WizardMessages, reused rather than duplicated - see this file's class Javadoc).
		pOut.append("<div class=\"mdmmSummary\">")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.summary",
				com.on.ebx.factory.wizard.WizardMessages.countWords(pLocale, summary.businessObjects, "wizard.unit.businessObject"),
				com.on.ebx.factory.wizard.WizardMessages.countWords(pLocale, summary.sources, "wizard.unit.application"),
				com.on.ebx.factory.wizard.WizardMessages.countWords(pLocale, summary.fieldLinks, "wizard.unit.fieldLink"),
				com.on.ebx.factory.wizard.WizardMessages.countWords(pLocale, summary.rules, "wizard.unit.qualityRule"),
				com.on.ebx.factory.wizard.WizardMessages.countWords(pLocale, summary.checks, "wizard.unit.check"))))
			.append("</div>")
			.append("<div class=\"mdmmSummary\" id=\"mdmmHeadline\">").append(MatrixHtml.esc(MatrixHtml.headlineText(pTally, pLocale))).append("</div>");
		final boolean flaggedDone = pTally.flaggedFields == 0;
		pOut.append("<div class=\"mdmmGuide\">");
		pOut.append("<div class=\"mdmmGuideCard\"><span class=\"mdmmChip ").append(flaggedDone ? "good" : "warn").append("\">").append(flaggedDone ? "&#10003;" : String.valueOf(pTally.flaggedFields))
			.append("</span><div><b>").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.guide.step1.title"))).append("</b><div style=\"color:#667085\">")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, flaggedDone ? "mappingMatrix.guide.step1.done" : "mappingMatrix.guide.step1.detail"))).append("</div></div></div>");
		pOut.append("<div class=\"mdmmGuideCard\"><span class=\"mdmmChip ").append(pTally.allDone() ? "good" : "acc").append("\">").append(pTally.allDone() ? "&#10003;" : "2")
			.append("</span><div><b>").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.guide.step2.title"))).append("</b><div style=\"color:#667085\">")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, pTally.allDone() ? "mappingMatrix.guide.step2.done" : "mappingMatrix.guide.step2.detail"))).append("</div></div></div>");
		pOut.append("<div class=\"mdmmGuideCard\"><span class=\"mdmmChip good\">3</span><div><b>")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.guide.step3.title"))).append("</b><div style=\"color:#667085\">")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, pTally.allDone() ? "mappingMatrix.guide.step3.ready" : "mappingMatrix.guide.step3.detail"))).append("</div></div></div>");
		pOut.append("</div>");
		pOut.append("<div class=\"mdmmGlossary\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.glossary"))).append("</div>");
	}

	/** The first line of the screen, from the tally alone ({@link MatrixCounter.Tally#headline}); the footer's count is the same number, worded as items. */
	static String headlineText(final MatrixCounter.Tally pTally, final Locale pLocale)
	{
		final MatrixCounter.Headline headline = pTally.headline();
		if (headline == MatrixCounter.Headline.NEED_YOU)
		{
			return com.on.ebx.factory.wizard.WizardMessages.plural(pLocale, pTally.needYou, "mappingMatrix.summary.attention.one", "mappingMatrix.summary.attention.other");
		}
		if (headline == MatrixCounter.Headline.ONLY_BUTTON)
		{
			return com.on.ebx.factory.wizard.WizardMessages.plural(pLocale, pTally.acceptAll, "mappingMatrix.summary.onlyButton.one", "mappingMatrix.summary.onlyButton.other");
		}
		return MatrixMessages.get(pLocale, "mappingMatrix.summary.nothingLeft");
	}

	/**
	 * Matrix review queue lot (2026-09-30): the pinned block under the grid - the "left to decide" panel (a popover
	 * above it), the walk-through bar, the inspector and the footer. {@code position:sticky} keeps it on screen
	 * while the grid scrolls; the panel is absolute INSIDE it, so it always opens right above the footer that
	 * controls it, wherever the page is scrolled.
	 */
	private static void renderDock(final StringBuilder pOut, final MatrixModel.Project pProject, final State pState, final Locale pLocale,
		final List<MatrixCompute.StillItem> pItems, final List<MatrixCompute.ServiceGroup> pGroups, final MatrixCounter.Tally pTally)
	{
		pOut.append("<div class=\"mdmmDock\">");
		MatrixHtml.renderQueuePanel(pOut, pProject, pState, pLocale, pItems, pGroups, pTally);
		MatrixHtml.renderQueueBar(pOut, pLocale, pItems);
		MatrixHtml.renderInspectorShell(pOut, pLocale);
		// delta 4: the project-wide footer bar - the bulk accept, the count of what is left, "Load the staging ->".
		MatrixHtml.renderFooter(pOut, pProject, pState, pLocale, pTally);
		pOut.append("</div>");
	}

	/**
	 * Delta 4: project-wide bulk accept + the clickable count of what is left + the "Load the staging" shortcut link. UX lot U6: the count is
	 * {@link MatrixCounter.Tally#needYou} - the very number of the header line; optional look-up suggestions are appended apart ("+ N optional
	 * suggestions") and never keep "Load the staging" closed; one line under the button says what it covers.
	 */
	private static void renderFooter(final StringBuilder pOut, final MatrixModel.Project pProject, final State pState, final Locale pLocale,
		final MatrixCounter.Tally pTally)
	{
		final int needYou = pTally.needYou;
		final boolean ready = pTally.allDone();
		// the count opens the panel listing every item: what needs a person first, then - on request - what the
		// button below takes. With nothing needing a person the count still opens it, worded "all go with the button".
		final String label = ready ? ""
			: needYou > 0 ? com.on.ebx.factory.wizard.WizardMessages.plural(pLocale, needYou, "mappingMatrix.footer.needYou.one", "mappingMatrix.footer.needYou.other")
				: com.on.ebx.factory.wizard.WizardMessages.plural(pLocale, pTally.acceptAll, "mappingMatrix.footer.allWithButton.one", "mappingMatrix.footer.allWithButton.other");
		final boolean othersWithButton = needYou > 0 && pTally.withButton > 0;
		pOut.append("<div class=\"mdmmFooter\">");
		pOut.append("<div class=\"mdmmFootTxt\" id=\"mdmmFootTxt\">");
		if (ready)
		{
			pOut.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.footer.ready")));
		}
		else
		{
			pOut.append("<button type=\"button\" class=\"mdmmFootCount\" aria-expanded=\"").append(pState.queueMode.isEmpty() ? "false" : "true")
				.append("\" onclick=\"mdmmQueueToggle()\">").append(MatrixHtml.esc(label)).append("</button>");
			if (othersWithButton)
			{
				pOut.append(" <span class=\"mdmmFootHint\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.footer.othersWithButton"))).append("</span>");
			}
		}
		if (pTally.optionalSuggestions > 0)
		{
			// apart from the count above, and a button of its own so it is reachable when nothing else is left
			pOut.append(" <button type=\"button\" class=\"mdmmFootOpt\" onclick=\"mdmmQueueToggle()\">")
				.append(MatrixHtml.esc(com.on.ebx.factory.wizard.WizardMessages.plural(pLocale, pTally.optionalSuggestions, "mappingMatrix.footer.optional.one",
					"mappingMatrix.footer.optional.other")))
				.append("</button>");
		}
		pOut.append("</div>");
		// the global button and the line saying what it covers, stacked
		pOut.append("<span class=\"mdmmScope\">");
		pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\"").append(pTally.acceptAll == 0 || pState.readOnly ? " disabled=\"disabled\"" : "")
			.append(" onclick=\"mdmmBulkProject()\">")
			.append(pTally.acceptAll == 0 ? MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.footer.bulk.none"))
				: MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.footer.bulk", Integer.valueOf(pTally.acceptAll), Integer.valueOf(pState.bulkThreshold))))
			.append("</button>");
		// live bug fix (2026-09-25, PersVol2 volume test): the button's own count (plan.toDecide.size()) can
		// never include a mapping's own cleaning steps or a derived attribute's own capability suggestion -
		// mappingmatrix.MatrixModel/MatrixCompute have no EBX access and never model MappingStep rows at all
		// (see ebx.ui.MappingMatrixUserService#sweepMappingSteps's own Javadoc, which decides them in the SAME
		// click anyway). Said here in plain words instead of trying to fold an EBX-only count into a pure N.
		// UX lot U6: one line under the button saying what it covers (it replaces the bare "(their cleaning steps ... are accepted too)" hint)
		pOut.append("<small class=\"mdmmScopeNote\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.scope.project", Integer.valueOf(pState.bulkThreshold))))
			.append("</small></span>");
		// staging-screens lot (2026-10-17): the button BUILDS the staging (the wizard's own STAGING_LOAD_RUN, started by the service) and then brings the person back to
		// step 4, where the live panel runs. It used to be a link that only went back to the wizard ("Load the staging" loaded nothing: the real button was below the fold
		// there). Enabled only when nothing is left to decide - the service checks it again before starting; optional suggestions never keep it closed.
		final boolean canBuild = ready && !pState.readOnly;
		pOut.append("<button type=\"button\" class=\"mdmmBtn").append(ready ? " mdmmBtnPrimary mdmmBtnBuild" : "").append("\" id=\"mdmmBuild\" aria-disabled=\"").append(canBuild ? "false" : "true").append("\"");
		if (canBuild)
		{
			pOut.append(" onclick=\"mdmmLoadStaging()\"");
		}
		else
		{
			pOut.append(" disabled=\"disabled\" title=\"")
				.append(MatrixHtml.esc(ready ? "" : othersWithButton ? label + ". " + MatrixMessages.get(pLocale, "mappingMatrix.footer.othersWithButton") : label)).append("\"");
		}
		pOut.append(">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.footer.build"))).append("</button>");
		pOut.append("</div>");
	}

	// ---------------------------------------------------------------------------------------- the "left to decide" list

	/**
	 * The walk-through bar above the inspector: previous / "k of N" / next over the same list as the panel. N and
	 * the enabled state are refreshed client-side on every selection ({@code refreshBar} in {@link #script}); the
	 * text rendered here is what shows before the script has run. Absent when nothing is left.
	 */
	private static void renderQueueBar(final StringBuilder pOut, final Locale pLocale, final List<MatrixCompute.StillItem> pItems)
	{
		if (pItems.isEmpty())
		{
			return;
		}
		pOut.append("<div class=\"mdmmQBar\"><button type=\"button\" class=\"mdmmBtn mdmmBtnXs\" id=\"mdmmQPrev\" onclick=\"mdmmQueueStep(-1)\">&#9664; ")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.previous"))).append("</button><span class=\"mdmmQPos\" id=\"mdmmQPos\">")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.count", Integer.valueOf(pItems.size()))))
			.append("</span><button type=\"button\" class=\"mdmmBtn mdmmBtnXs\" id=\"mdmmQNext\" onclick=\"mdmmQueueStep(1)\">")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.next"))).append(" &#9654;</button></div>");
	}

	/**
	 * The panel: every item the footer counts, grouped by business object, each line a kind chip, the item's name,
	 * why the bulk button did not take it, and its own decision buttons (dispatched through {@code submitAction},
	 * i.e. the same {@code MappingMatrixUserService#handle} actions as everywhere else on this screen). What the
	 * button takes is listed too but hidden until asked for ({@code .bulk}) - unless nothing else is left.
	 */
	private static void renderQueuePanel(final StringBuilder pOut, final MatrixModel.Project pProject, final State pState, final Locale pLocale,
		final List<MatrixCompute.StillItem> pItems, final List<MatrixCompute.ServiceGroup> pGroups, final MatrixCounter.Tally pTally)
	{
		final boolean open = !pState.queueMode.isEmpty();
		final int needYou = pTally.needYou;
		final boolean all = "all".equals(pState.queueMode) || needYou == 0;
		pOut.append("<div id=\"mdmmQueue\" class=\"mdmmQueue").append(open ? " mdmmQueueOpen" : "").append(all ? " mdmmQueueAllOn" : "").append("\">");
		pOut.append("<div class=\"mdmmQHead\"><b>").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.title"))).append("</b>");
		if (needYou > 0 && needYou < pItems.size())
		{
			pOut.append("<label class=\"mdmmQAll\"><input type=\"checkbox\"").append("all".equals(pState.queueMode) ? " checked=\"checked\"" : "")
				.append(" onchange=\"mdmmQueueAll(this.checked)\"/> ")
				.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.showAll", Integer.valueOf(pItems.size() - needYou)))).append("</label>");
		}
		pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs mdmmQClose\" onclick=\"mdmmQueueToggle()\">")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.close"))).append("</button></div>");
		if (pItems.isEmpty())
		{
			pOut.append("<p class=\"mdmmMuted\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.empty"))).append("</p>");
		}
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			final int count = MatrixCompute.countOfEntity(pItems, entity.id);
			if (count == 0)
			{
				continue;
			}
			pOut.append("<div class=\"mdmmQGroup\"><div class=\"mdmmQGroupName\">").append(MatrixHtml.esc(entity.name)).append(" <span class=\"mdmmChip warn\">")
				.append(count).append("</span></div>");
			for (final MatrixCompute.StillItem item : pItems)
			{
				if (item.entityId.equals(entity.id))
				{
					MatrixHtml.renderQueueItem(pOut, pProject, entity, item, pState, pLocale);
				}
			}
			pOut.append("</div>");
		}
		MatrixHtml.renderOptionalServices(pOut, pProject, pState, pLocale, pGroups);
		pOut.append("</div>");
	}

	/**
	 * UX lot U6: the optional look-up suggestions, ONE folded line per target field ("4 services could check Adresse.street") with the candidates and their
	 * readiness inside, "Set aside all" / "Reject all" on the line and the per-suggestion decision inside the fold. Lines carry the class {@code mdmmSItem}, never
	 * {@code mdmmQItem}: they are outside the walk-through (Previous / Next) and the counts of the list above, and a click on one still opens its chip in the grid
	 * (same {@code mdmmQueueGo}).
	 */
	private static void renderOptionalServices(final StringBuilder pOut, final MatrixModel.Project pProject, final State pState, final Locale pLocale,
		final List<MatrixCompute.ServiceGroup> pGroups)
	{
		if (pGroups.isEmpty())
		{
			return;
		}
		final String disabled = pState.readOnly ? " disabled=\"disabled\"" : "";
		pOut.append("<div class=\"mdmmQOpt\"><div class=\"mdmmQGroupName\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.optional.title")))
			.append(" <span class=\"mdmmChip none\">").append(MatrixCompute.optionalSuggestionCount(pGroups)).append("</span></div><p class=\"mdmmMuted\">")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.optional.lead"))).append("</p>");
		for (final MatrixCompute.ServiceGroup group : pGroups)
		{
			final MatrixModel.Entity entity = pProject.entityById(group.entityId);
			final String target = group.attributeName.isEmpty() ? group.entityName : group.entityName + "." + group.attributeName;
			final int ready = group.readyCount();
			pOut.append("<details class=\"mdmmQOptGroup\" data-ids=\"").append(MatrixHtml.esc(MatrixHtml.join(group.guardIds(), ","))).append("\"><summary><b>")
				.append(MatrixHtml.esc(com.on.ebx.factory.wizard.WizardMessages.plural(pLocale, group.items.size(), "mappingMatrix.optional.group.one",
					"mappingMatrix.optional.group.other", target)))
				.append("</b> <span class=\"mdmmMuted\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.optional.readySummary", Integer.valueOf(ready),
					Integer.valueOf(group.items.size()))))
				.append("</span><span class=\"mdmmQActions\">")
				.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs\"").append(disabled).append(" onclick=\"mdmmServiceGroup(this,'SETASIDE')\">")
				.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.optional.setAsideAll"))).append("</button>")
				.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs\"").append(disabled).append(" onclick=\"mdmmServiceGroup(this,'REJECT')\">")
				.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.optional.rejectAll"))).append("</button></span></summary>");
			for (final MatrixCompute.StillItem item : group.items)
			{
				final String id = MatrixHtml.jsAttr(item.id);
				pOut.append("<div class=\"mdmmSItem\" data-key=\"").append(MatrixHtml.esc(item.key)).append("\" data-entity=\"").append(MatrixHtml.esc(item.entityId))
					.append("\" data-item=\"").append(MatrixHtml.esc(entity == null ? "{}" : MatrixHtml.itemJson(entity, item, pState, pLocale)))
					.append("\" onclick=\"mdmmQueueGo(this)\"><span class=\"mdmmQMain\"><b>").append(MatrixHtml.esc(MatrixHtml.serviceName(item.guard)))
					.append("</b> <span class=\"mdmmQWhy\">").append(MatrixHtml.esc(MatrixHtml.readinessText(item.guard, pLocale))).append("</span></span>")
					.append("<span class=\"mdmmQActions\"><button type=\"button\" class=\"mdmmBtn mdmmBtnXs\"").append(disabled).append(" onclick=\"mdmmServiceDecide(event,")
					.append(id).append(",'SETASIDE')\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.setAside"))).append("</button>")
					.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs\"").append(disabled).append(" onclick=\"mdmmServiceDecide(event,").append(id)
					.append(",'REJECT')\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.reject"))).append("</button></span></div>");
			}
			pOut.append("</details>");
		}
		pOut.append("</div>");
	}

	/** "provider - operation" of a service suggestion; the operation alone when the provider is not known. */
	static String serviceName(final Guard pGuard)
	{
		return pGuard.serviceConnector.isEmpty() ? pGuard.label() : pGuard.serviceConnector + " \u00B7 " + pGuard.label();
	}

	/** "ready, free" / "ready" / "needs set-up: &lt;why&gt;" - the readiness and the price when the suggestion carries them; never a guess when unknown. */
	static String readinessText(final Guard pGuard, final Locale pLocale)
	{
		final String pricing = pGuard.servicePricing.trim().toUpperCase(java.util.Locale.ROOT);
		final String price = "FREE".equals(pricing) || "FREEMIUM".equals(pricing) || "PAID".equals(pricing)
			? MatrixMessages.get(pLocale, "mappingMatrix.optional.pricing." + pricing) : "";
		if (Boolean.FALSE.equals(pGuard.serviceReady))
		{
			final String why = pGuard.serviceReadinessDetail.isEmpty() ? MatrixMessages.get(pLocale, "mappingMatrix.optional.notReady")
				: MatrixMessages.get(pLocale, "mappingMatrix.optional.notReadyDetail", pGuard.serviceReadinessDetail);
			return price.isEmpty() ? why : why + ", " + price;
		}
		final String ready = Boolean.TRUE.equals(pGuard.serviceReady) ? MatrixMessages.get(pLocale, "mappingMatrix.optional.ready") : "";
		if (ready.isEmpty())
		{
			return price;
		}
		return price.isEmpty() ? ready : ready + ", " + price;
	}

	private static void renderQueueItem(final StringBuilder pOut, final MatrixModel.Project pProject, final MatrixModel.Entity pEntity,
		final MatrixCompute.StillItem pItem, final State pState, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmmQItem").append(pItem.takenByTheButton() ? " bulk" : "").append("\" data-key=\"").append(MatrixHtml.esc(pItem.key))
			.append("\" data-entity=\"").append(MatrixHtml.esc(pItem.entityId)).append("\" data-item=\"")
			.append(MatrixHtml.esc(MatrixHtml.itemJson(pEntity, pItem, pState, pLocale))).append("\" onclick=\"mdmmQueueGo(this)\">");
		pOut.append("<span class=\"mdmmChip acc\">").append(MatrixHtml.esc(MatrixHtml.kindLabel(pItem, pLocale))).append("</span>");
		pOut.append("<span class=\"mdmmQMain\"><b>").append(MatrixHtml.esc(pItem.label)).append("</b>");
		if (!pItem.attributeName.isEmpty() && (MatrixCompute.KIND_RULE.equals(pItem.kind) || MatrixCompute.KIND_CHECK.equals(pItem.kind)))
		{
			pOut.append(" <span class=\"mdmmMuted\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.onField", pItem.attributeName))).append("</span>");
		}
		// lot G2 (2026-10-17): the proposer's own reason first (a check of lot A, a value check of lot D/E), then the generic one
		final MatrixNotes.Note check = MatrixHtml.noteOf(pEntity, pItem, pLocale);
		if (check != null)
		{
			pOut.append(" <span class=\"mdmmQCheck\">").append(MatrixHtml.esc(check.sentence)).append("</span>");
		}
		pOut.append(" <span class=\"mdmmQWhy\">").append(MatrixHtml.esc(MatrixHtml.reasonText(pItem, pState.bulkThreshold, pLocale))).append("</span></span>");
		pOut.append("<span class=\"mdmmQActions\">");
		final String disabled = pState.readOnly ? " disabled=\"disabled\"" : "";
		final String key = MatrixHtml.jsAttr(pItem.key);
		final boolean rejectFirst = check != null && check.proposesReject;
		if (MatrixHtml.canAccept(pItem))
		{
			pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs").append(rejectFirst ? "" : " mdmmBtnPrimary").append("\"").append(disabled)
				.append(" onclick=\"mdmmItemDecide(event,").append(key)
				.append(",'ACCEPT')\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.accept"))).append("</button>");
		}
		if (pItem.setAside)
		{
			pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs\"").append(disabled).append(" onclick=\"mdmmItemDecide(event,").append(key)
				.append(",'SETASIDE')\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.setAside"))).append("</button>");
		}
		pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs").append(rejectFirst ? " mdmmBtnPrimary" : "").append("\"").append(disabled)
			.append(" onclick=\"mdmmItemDecide(event,").append(key)
			.append(",'REJECT')\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.reject"))).append("</button>");
		pOut.append("</span></div>");
	}

	/**
	 * Lot G2: the note an item shows ahead of the generic reason - the leading check of a field's rationale (it may propose rejecting), or the
	 * "Value check" of a field link (localized label, never proposes anything). Null when the item has neither.
	 */
	private static MatrixNotes.Note noteOf(final MatrixModel.Entity pEntity, final MatrixCompute.StillItem pItem, final Locale pLocale)
	{
		final MatrixNotes.Note check = MatrixNotes.of(pEntity, pItem);
		if (check != null)
		{
			return check;
		}
		final MatrixNotes.Note value = MatrixNotes.valueCheckOfLink(pEntity, pItem, pLocale);
		return value == null ? null : new MatrixNotes.Note(MatrixMessages.get(pLocale, "mappingMatrix.check.valueCheck", value.sentence), false, value.rest);
	}

	/** Whether the line offers Accept at all: never for a connector service (set aside or reject, like the wizard's card), never for a link whose business object is not accepted yet. */
	private static boolean canAccept(final MatrixCompute.StillItem pItem)
	{
		if (pItem.setAside)
		{
			return false;
		}
		if (MatrixCompute.KIND_FIELD_LINK.equals(pItem.kind))
		{
			return !pItem.id.isEmpty() && pItem.cellAccept != MatrixCompute.CellAcceptAction.ENTITY_NOT_ACCEPTED;
		}
		return true;
	}

	private static String kindLabel(final MatrixCompute.StillItem pItem, final Locale pLocale)
	{
		return MatrixMessages.get(pLocale, "mappingMatrix.queue.kind." + pItem.kind);
	}

	/** The reason in plain words - never "in bulk" or "unflagged". */
	public static String reasonText(final MatrixCompute.StillItem pItem, final int pThreshold, final Locale pLocale)
	{
		final String reason = pItem.reason;
		if (MatrixCompute.REASON_BELOW_BAR.equals(reason))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.queue.why.belowBar", Integer.valueOf(pItem.confidence == null ? 0 : pItem.confidence.intValue()),
				Integer.valueOf(pThreshold));
		}
		if (MatrixCompute.REASON_ATTENTION.equals(reason))
		{
			return pItem.detail.isEmpty() ? MatrixMessages.get(pLocale, "mappingMatrix.queue.why.attention")
				: MatrixMessages.get(pLocale, "mappingMatrix.queue.why.attentionDetail", pItem.detail);
		}
		if (MatrixCompute.REASON_PARENT_FIRST.equals(reason))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.queue.why.parentFirst", pItem.detail);
		}
		if (MatrixCompute.REASON_FIELD_FIRST.equals(reason))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.queue.why.fieldFirst", pItem.detail);
		}
		if (MatrixCompute.REASON_SOURCE_FIRST.equals(reason))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.queue.why.sourceFirst", pItem.detail);
		}
		if (MatrixCompute.REASON_COMPUTED.equals(reason))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.queue.why.computed");
		}
		if (MatrixCompute.REASON_BULK.equals(reason))
		{
			return MatrixMessages.get(pLocale, "mappingMatrix.queue.why.button");
		}
		return MatrixMessages.get(pLocale, "mappingMatrix.queue.why.decide");
	}

	/**
	 * One list line's data for the script, hand-built like {@link #cellJson} (and HTML-escaped by the caller): what
	 * {@code mdmmItemDecide} needs to post the right action ({@code op}/{@code id}/{@code attrId}/{@code nature}/
	 * {@code cellAccept}), and what {@code showItem} needs to fill the inspector for an item that has no cell of its
	 * own (a business object, a field row, a source link column, or a guard that has no chip in the grid - a computed
	 * field's). A guard also carries its full {@link #guardJson}, so the ordinary guard inspector can show it.
	 */
	private static String itemJson(final MatrixModel.Entity pEntity, final MatrixCompute.StillItem pItem, final State pState, final Locale pLocale)
	{
		final StringBuilder j = new StringBuilder(384);
		j.append('{');
		MatrixHtml.jsonField(j, "key", pItem.key, true);
		MatrixHtml.jsonField(j, "entityId", pItem.entityId, true);
		MatrixHtml.jsonField(j, "entity", pItem.entityName, true);
		final String op = ReviewLine.ENTITY.equals(pItem.nature) ? "ENTITY" : ReviewLine.ATTRIBUTE.equals(pItem.nature) ? "ATTRIBUTE"
			: ReviewLine.FEED.equals(pItem.nature) ? "FEED" : ReviewLine.MAPPING.equals(pItem.nature) ? "CELL" : "GUARD";
		MatrixHtml.jsonField(j, "op", op, true);
		MatrixHtml.jsonField(j, "id", pItem.id, true);
		MatrixHtml.jsonField(j, "attrId", pItem.attributeId, true);
		MatrixHtml.jsonField(j, "nature", pItem.nature, true);
		MatrixHtml.jsonField(j, "kindLabel", MatrixHtml.kindLabel(pItem, pLocale), true);
		MatrixHtml.jsonField(j, "title", pItem.label, true);
		MatrixHtml.jsonField(j, "field", pItem.attributeName, true);
		MatrixHtml.jsonField(j, "why", MatrixHtml.reasonText(pItem, pState.bulkThreshold, pLocale), true);
		final MatrixNotes.Note note = MatrixHtml.noteOf(pEntity, pItem, pLocale);
		MatrixHtml.jsonField(j, "check", note == null ? "" : note.sentence, true);
		MatrixHtml.jsonField(j, "checkReject", note != null && note.proposesReject ? "true" : "false", false);
		MatrixHtml.jsonField(j, "rationale", note == null ? "" : note.rest, true);
		MatrixHtml.jsonField(j, "confidence", pItem.confidence == null ? "" : String.valueOf(pItem.confidence), false);
		MatrixHtml.jsonField(j, "cellAccept", pItem.cellAccept == null ? "" : pItem.cellAccept.name(), true);
		MatrixHtml.jsonField(j, "canAccept", MatrixHtml.canAccept(pItem) ? "true" : "false", false);
		MatrixHtml.jsonField(j, "setAside", pItem.setAside ? "true" : "false", false);
		MatrixHtml.jsonField(j, "readOnly", pState.readOnly ? "true" : "false", false);
		String explain = "";
		if (pItem.guard == null)
		{
			for (final MatrixModel.Attr attr : pEntity.attrs)
			{
				if (attr.id.equals(pItem.attributeId) && ReviewLine.ATTRIBUTE.equals(pItem.nature) && attr.isDerived())
				{
					explain = MatrixHtml.derivedLabel(attr, "mappingMatrix.explain.derived.", pLocale);
				}
			}
		}
		MatrixHtml.jsonField(j, "explain", explain, true);
		if (pItem.guard != null)
		{
			MatrixModel.Attr owner = null;
			for (final MatrixModel.Attr attr : pEntity.attrs)
			{
				if (attr.id.equals(pItem.attributeId))
				{
					owner = attr;
				}
			}
			j.append("\"guard\":").append(MatrixHtml.guardJson(pEntity, owner, pItem.guard, pLocale)).append(',');
		}
		if (j.charAt(j.length() - 1) == ',')
		{
			j.setLength(j.length() - 1);
		}
		j.append('}');
		return j.toString();
	}

	// ------------------------------------------------------------------------------------------------------ overview

	private static void renderOverview(final StringBuilder pOut, final MatrixModel.Project pProject, final State pState, final Locale pLocale,
		final List<MatrixCompute.StillItem> pItems)
	{
		final int sourceCount = pProject.sources.size();
		final int entityCount = pProject.entities.size();
		final double top = 20;
		final double rowHeight = Math.max(38, Math.min(60, 320.0 / Math.max(1, sourceCount)));
		final double height = Math.max(140, Math.max(sourceCount, 1) * rowHeight + 40);
		final double x1 = MatrixHtml.OV_X1, x2 = MatrixHtml.OV_X2;
		final double[] sourceY = MatrixCompute.rowPositions(sourceCount, top, rowHeight);
		final double entityRowHeight = height / Math.max(1, entityCount);
		final double[] entityY = MatrixCompute.rowPositions(entityCount, 0, entityRowHeight);
		final long svgHeight = Math.round(height + 20);

		// fix/mapping-matrix-live: the container is pinned to the SAME px width the SVG and the absolutely
		// positioned cards below both assume (class Javadoc, OV_WIDTH) - it no longer stretches to the pane's
		// own (variable) width, which is what desynchronised the curves from the cards.
		// fix/wizard-live-wave1 (2026-09-24, task item 13): at a narrow pane width (~600px measured) the fixed
		// OV_WIDTH box used to be squeezed by its own "max-width:100%" while the SVG/cards inside it kept their
		// real pixel size - the box shrank but its content did not, so the right-hand entity cards and the
		// curves' far end rendered past the shrunk box's edge with no way to reach them (no scrollbar, since
		// nothing was actually scrollable). Fix: an outer wrapper takes the "fit my container" job instead,
		// with overflow-x:auto - .mdmmOv itself goes back to its own fixed OV_WIDTH with no clipping, so the
		// SVG/cards' shared coordinate system is untouched (the whole point of pinning it in the first place),
		// and a pane narrower than OV_WIDTH scrolls horizontally instead of clipping content invisibly.
		pOut.append("<div class=\"mdmmOvWrap\">");
		pOut.append("<div class=\"mdmmOv\" style=\"height:").append(svgHeight).append("px;width:").append(MatrixHtml.OV_WIDTH).append("px\">");
		pOut.append("<div class=\"mdmmOvLabel\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.overview.label"))).append("</div>");
		// explicit width/height attributes (never a scaled viewBox/preserveAspectRatio): 1 SVG user unit = 1
		// real pixel, the same unit the cards below use for their own "left:Npx" - class Javadoc.
		pOut.append("<svg class=\"mdmmOvSvg\" width=\"").append(MatrixHtml.OV_WIDTH).append("\" height=\"").append(svgHeight).append("\">");
		final List<ChipInfo> chips = new java.util.ArrayList<ChipInfo>();
		for (int e = 0; e < pProject.entities.size(); e++)
		{
			final MatrixModel.Entity entity = pProject.entities.get(e);
			final Map<String, MatrixCompute.FeedStats> bySource = MatrixCompute.statsBySource(entity, pState.bulkThreshold);
			for (int s = 0; s < pProject.sources.size(); s++)
			{
				final MatrixModel.Source source = pProject.sources.get(s);
				final MatrixCompute.FeedStats stats = bySource.get(source.id);
				if (stats == null)
				{
					continue; // this source has no feed at all into this entity - no curve to draw
				}
				final boolean active = entity.id.equals(pState.activeEntityId);
				final boolean faded = (pState.focusSourceId != null && !pState.focusSourceId.equals(source.id)) || !active;
				final boolean keyLinked = MatrixHtml.anyKeyLinked(entity, source.id);
				final String path = MatrixCompute.curvePath(x1, sourceY[s], x2, entityY[e]);
				pOut.append("<path d=\"").append(path).append("\" fill=\"none\" stroke=\"").append(MatrixHtml.esc(source.color))
					.append("\" stroke-width=\"2\" opacity=\"").append(faded ? "0.15" : "0.7").append("\"")
					// audit F-44: a key-linked curve takes the perspective's main colour (#mdmm path.mdmmKeyLink, renderStyle)
					.append(keyLinked ? " class=\"mdmmKeyLink\" stroke-dasharray=\"6 4\"" : "").append("/>");
				// fix/mapping-matrix-live: MatrixCompute#curveChipPoint existed but was never called - the
				// status chip on each curve, added here, rendered as an HTML button AFTER the </svg> (same
				// absolutely-positioned-over-the-SVG technique the source/entity cards already use).
				// live fix (bug 7): per-(entity,SOURCE) t, not just per-entity - MatrixCompute#chipT's own
				// 4-arg overload Javadoc explains why the plain per-entity t alone let two sources' chips
				// land on the exact same x.
				final double t = MatrixCompute.chipT(e, pProject.entities.size(), s, pProject.sources.size());
				chips.add(new ChipInfo(MatrixCompute.curveChipPoint(x1, sourceY[s], x2, entityY[e], t), source, entity, stats, faded,
					MatrixHtml.arrivalModeFor(entity, source.id)));
			}
		}
		pOut.append("</svg>");
		for (int s = 0; s < pProject.sources.size(); s++)
		{
			final MatrixModel.Source source = pProject.sources.get(s);
			final boolean on = source.id.equals(pState.focusSourceId);
			final boolean off = pState.focusSourceId != null && !on;
			final String tables = MatrixHtml.landingTablesOf(pProject, source.id);
			pOut.append("<button type=\"button\" class=\"mdmmSrcCard").append(on ? " on" : "").append(off ? " off" : "")
				.append("\" style=\"top:").append(Math.round(sourceY[s] - 18)).append("px\" onclick=\"mdmmFocus(").append(MatrixHtml.jsAttr(source.id)).append(")\">")
				.append("<span class=\"mdmmSrcTag\" style=\"background:").append(MatrixHtml.esc(source.color)).append("\">")
				.append(MatrixHtml.esc(source.sourceSystem)).append("</span>")
				// fix/mapping-matrix-live: the landing table(s) of this source, next to its tag (mockup parity -
				// "the source cards show only the source tag" was one of the reported live symptoms).
				.append("<span class=\"mdmmSrcTbl\">").append(MatrixHtml.esc(tables)).append("</span></button>");
		}
		for (final ChipInfo chip : chips)
		{
			final String statusText = chip.stats.missing > 0 ? MatrixMessages.get(pLocale, "mappingMatrix.status.missing", Integer.valueOf(chip.stats.missing))
				: chip.stats.proposed > 0 ? MatrixMessages.get(pLocale, "mappingMatrix.status.toValidate", Integer.valueOf(chip.stats.proposed))
					: MatrixMessages.get(pLocale, "mappingMatrix.status.allValidated");
			final String text = chip.arrivalMode.isEmpty() ? statusText : chip.arrivalMode + " \u00b7 " + statusText;
			final String tone = chip.stats.missing > 0 ? "bad" : chip.stats.proposed > 0 ? "warn" : "good";
			pOut.append("<button type=\"button\" class=\"mdmmOvChip ").append(tone).append(chip.faded ? " faded" : "").append("\" style=\"left:")
				.append(Math.round(chip.point.x)).append("px;top:").append(Math.round(chip.point.y)).append("px\" onclick=\"mdmmOpenEntityFocus(")
				.append(MatrixHtml.jsAttr(chip.entity.id)).append(",").append(MatrixHtml.jsAttr(chip.source.id)).append(")\" title=\"")
				.append(MatrixHtml.esc(chip.source.sourceSystem + " \u2192 " + chip.entity.name + ": " + text)).append("\">").append(MatrixHtml.esc(text))
				.append("</button>");
		}
		for (int e = 0; e < pProject.entities.size(); e++)
		{
			final MatrixModel.Entity entity = pProject.entities.get(e);
			final MatrixCompute.FeedStats stats = MatrixCompute.entityStats(entity, pState.bulkThreshold);
			// staging-screens lot (2026-10-17): "to validate" of a business object is the figure of its tab and of the queue (items still to decide), not a fourth tally
			final int pending = MatrixCompute.countOfEntity(pItems, entity.id);
			final String statusText = stats.missing > 0
				? MatrixMessages.get(pLocale, "mappingMatrix.status.missing", Integer.valueOf(stats.missing))
				: pending > 0 ? MatrixMessages.get(pLocale, "mappingMatrix.status.toValidate", Integer.valueOf(pending))
					: MatrixMessages.get(pLocale, "mappingMatrix.status.allValidated");
			final boolean on = entity.id.equals(pState.activeEntityId);
			pOut.append("<button type=\"button\" class=\"mdmmEntCard").append(on ? " on" : "").append("\" style=\"top:")
				.append(Math.round(entityY[e] - 30)).append("px\" onclick=\"mdmmOpenEntity(").append(MatrixHtml.jsAttr(entity.id)).append(")\">")
				.append("<span class=\"mdmmEntName\">").append(MatrixHtml.esc(entity.name)).append("</span><span class=\"mdmmEntSub\">")
				.append(MatrixHtml.esc(statusText)).append("</span></button>");
		}
		pOut.append("</div>"); // .mdmmOv
		pOut.append("</div>"); // .mdmmOvWrap
	}

	/** One status chip's precomputed position/text material - built while walking the (entity, source) pairs once, rendered right after, so the loop above stays readable. */
	private static final class ChipInfo
	{
		final MatrixCompute.Point point;
		final MatrixModel.Source source;
		final MatrixModel.Entity entity;
		final MatrixCompute.FeedStats stats;
		final boolean faded;
		final String arrivalMode;

		ChipInfo(final MatrixCompute.Point pPoint, final MatrixModel.Source pSource, final MatrixModel.Entity pEntity, final MatrixCompute.FeedStats pStats,
			final boolean pFaded, final String pArrivalMode)
		{
			this.point = pPoint;
			this.source = pSource;
			this.entity = pEntity;
			this.stats = pStats;
			this.faded = pFaded;
			this.arrivalMode = pArrivalMode;
		}
	}

	private static boolean anyKeyLinked(final MatrixModel.Entity pEntity, final String pSourceId)
	{
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			if (pSourceId.equals(feed.sourceId) && feed.keyLinked)
			{
				return true;
			}
		}
		return false;
	}

	/** The first non-blank {@link MatrixModel.Feed#arrivalMode} of this source's own feeds into this entity - "same row", "collection X[]", "linked by key" (task brief: the overview chip names how a child/collection/key feed arrives). Blank for the common "one root feed per source" case, same as {@link MatrixModel.Feed#arrivalMode} itself. */
	private static String arrivalModeFor(final MatrixModel.Entity pEntity, final String pSourceId)
	{
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			if (pSourceId.equals(feed.sourceId) && !feed.arrivalMode.isEmpty())
			{
				return feed.arrivalMode;
			}
		}
		return "";
	}

	/** Every DISTINCT landing table name this source feeds, across every entity of the project (not only the active one) - joined " + ", the mockup's own "Contact + BUT_ADDRESS" convention. */
	private static String landingTablesOf(final MatrixModel.Project pProject, final String pSourceId)
	{
		final Set<String> tables = new LinkedHashSet<String>();
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			for (final MatrixModel.Feed feed : entity.feeds)
			{
				if (pSourceId.equals(feed.sourceId) && feed.landingTableName != null && !feed.landingTableName.isEmpty())
				{
					tables.add(feed.landingTableName);
				}
			}
		}
		return MatrixHtml.join(new java.util.ArrayList<String>(tables), " + ");
	}

	// ------------------------------------------------------------------------------------------------------ toolbar

	private static void renderToolbar(final StringBuilder pOut, final MatrixModel.Project pProject, final MatrixModel.Entity pActive, final State pState,
		final Locale pLocale, final List<MatrixCompute.StillItem> pItems)
	{
		pOut.append("<div class=\"mdmmToolbar\">");
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			final boolean on = entity.id.equals(pActive.id);
			// matrix review queue lot: each tab says how many items of ITS business object are still to decide (hidden at 0)
			final int left = MatrixCompute.countOfEntity(pItems, entity.id);
			pOut.append("<button type=\"button\" class=\"mdmmTab").append(on ? " on" : "").append("\" data-key=\"").append(MatrixHtml.esc(MatrixCompute.KEY_ENTITY + entity.id))
				.append("\" onclick=\"mdmmOpenEntity(").append(MatrixHtml.jsAttr(entity.id)).append(")\">").append(MatrixHtml.esc(entity.name));
			if (left > 0)
			{
				pOut.append(" <span class=\"mdmmTabCount\" title=\"").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.tab.leftTitle", Integer.valueOf(left))))
					.append("\">").append(left).append("</span>");
			}
			pOut.append("</button>");
		}
		// coordinator review (2026-09-23): the entity's own Accept/Reject - every attribute/feed/mapping accept
		// depends on this happening first (review.ReviewRules#canAcceptStagingAttribute), so this is the first
		// decision a reviewer needs on any freshly proposed entity.
		if (MatrixCompute.canDecideEntity(pActive.status))
		{
			pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary mdmmBtnXs\"").append(pState.readOnly ? " disabled=\"disabled\"" : "")
				.append(" onclick=\"mdmmAcceptEntity(").append(MatrixHtml.jsAttr(pActive.id)).append(")\">")
				.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.entity.accept"))).append("</button>");
			pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs\"").append(pState.readOnly ? " disabled=\"disabled\"" : "")
				.append(" onclick=\"mdmmRejectEntity(").append(MatrixHtml.jsAttr(pActive.id)).append(")\">")
				.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.entity.reject"))).append("</button>");
		}
		final MatrixCompute.FeedStats stats = MatrixCompute.entityStats(pActive, pState.bulkThreshold);
		pOut.append("<span class=\"mdmmSep\"></span>");
		pOut.append("<span class=\"mdmmChip good\">").append(stats.accepted).append(" ").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.count.accepted"))).append("</span>");
		// staging-screens lot (2026-10-17): the same figure as this object's tab above (items still to decide), never the cell tally of entityStats
		pOut.append("<span class=\"mdmmChip warn\">").append(MatrixCompute.countOfEntity(pItems, pActive.id)).append(" ").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.count.proposed"))).append("</span>");
		pOut.append("<span class=\"mdmmChip bad\">").append(stats.rejected).append(" ").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.count.rejected"))).append("</span>");
		pOut.append("<span class=\"mdmmChip bad\">").append(stats.missing).append(" ").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.count.missing"))).append("</span>");
		pOut.append("<span class=\"mdmmGrow\"></span>");
		pOut.append("<button type=\"button\" class=\"mdmmToggle").append(pState.onlyTodo ? " on" : "").append("\" onclick=\"mdmmToggleTodo()\">")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.onlyTodo"))).append("</button>");
		// coordinator review (2026-09-23): the bulk button's own count/action now comes from MatrixCompute's
		// pure planner (entity, then non-key attributes, then feeds, then mappings - the SAME cascade order and
		// the SAME WizardRules.bulkEligibility rule ProjectWizardUserService#bulkStaging uses for the rows it
		// already knows), never the old mapping-only count.
		final int bulkCount = MatrixCompute.planEntityBulk(pActive, pState.bulkThreshold).toDecide.size();
		// UX lot U6: one line under the per-object button saying what it covers (the global button of the footer has its own)
		pOut.append("<span class=\"mdmmScope\"><button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\"").append(bulkCount == 0 || pState.readOnly ? " disabled=\"disabled\"" : "")
			.append(" onclick=\"mdmmBulk(").append(MatrixHtml.jsAttr(pActive.id)).append(")\">")
			.append(bulkCount == 0 ? MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.bulk.none"))
				: MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.bulk.entity", Integer.valueOf(bulkCount), Integer.valueOf(pState.bulkThreshold))))
			.append("</button><small class=\"mdmmScopeNote\">")
			.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.scope.entity", pActive.name, Integer.valueOf(pState.bulkThreshold)))).append("</small></span>");
		pOut.append("</div>");
	}

	// ------------------------------------------------------------------------------------------------------- matrix

	private static void renderMatrix(final StringBuilder pOut, final MatrixModel.Project pProject, final MatrixModel.Entity pActive, final State pState,
		final Locale pLocale, final List<MatrixCompute.StillItem> pItems)
	{
		final Map<String, MatrixModel.Source> sourcesById = MatrixHtml.sourcesById(pProject);
		// delta 1: one more grid column, "Rules and checks", at the right of the attribute x source-link matrix.
		pOut.append("<div class=\"mdmmMatrix\" style=\"grid-template-columns:220px repeat(").append(Math.max(1, pActive.feeds.size()))
			.append(",minmax(0,1fr)) 240px\">");
		pOut.append("<div class=\"mdmmCorner\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.corner", pActive.name, Integer.valueOf(pActive.attrs.size()))))
			.append("</div>");
		for (final MatrixModel.Feed feed : pActive.feeds)
		{
			final MatrixModel.Source source = sourcesById.get(feed.sourceId);
			final MatrixCompute.FeedStats feedStats = MatrixCompute.feedStats(pActive.attrs, feed, pState.bulkThreshold);
			final int total = Math.max(1, feedStats.accepted + feedStats.proposed + feedStats.rejected);
			final boolean off = pState.focusSourceId != null && !pState.focusSourceId.equals(feed.sourceId);
			pOut.append("<div class=\"mdmmHead").append(off ? " off" : "").append("\" data-source=\"").append(MatrixHtml.esc(feed.sourceId)).append("\" data-key=\"")
				.append(MatrixHtml.esc(MatrixCompute.KEY_FEED + feed.id)).append("\">");
			pOut.append("<span class=\"mdmmSrcTag\" style=\"background:").append(MatrixHtml.esc(source == null ? "#98a2b3" : source.color)).append("\">")
				.append(MatrixHtml.esc(source == null ? "?" : source.sourceSystem)).append("</span>");
			pOut.append("<span class=\"mdmmTbl\">").append(MatrixHtml.esc(feed.landingTableName)).append("</span>");
			if (!feed.arrivalMode.isEmpty())
			{
				pOut.append("<span class=\"mdmmMode\">").append(MatrixHtml.esc(feed.arrivalMode)).append("</span>");
			}
			pOut.append("<div class=\"mdmmBar\"><i style=\"width:").append(100 * feedStats.accepted / total).append("%;background:#12b76a\"></i>")
				.append("<i style=\"width:").append(100 * feedStats.proposed / total).append("%;background:#f5b400\"></i>")
				.append("<i style=\"width:").append(100 * feedStats.rejected / total).append("%;background:#f04438\"></i></div>");
			pOut.append("<span class=\"mdmmBarTxt\">").append(feedStats.accepted).append("/").append(feedStats.proposed).append("/").append(feedStats.rejected).append("</span>");
			if (feed.unusedColumns.isEmpty())
			{
				pOut.append("<span class=\"mdmmUnused\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.unused.none"))).append("</span>");
			}
			else
			{
				pOut.append("<span class=\"mdmmUnused\" title=\"").append(MatrixHtml.esc(MatrixHtml.join(feed.unusedColumns))).append("\">")
					.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.unused.some", Integer.valueOf(feed.unusedColumns.size())))).append("</span>");
			}
			// coordinator review: a rejected feed's own reviewComment (TransferFeed.reviewComment) shown as a
			// header tooltip - the inspector also carries it (see the cellJson "feedComment" field below) for
			// whichever cell of this feed the reviewer has selected.
			if (MatrixModel.REJECTED.equals(feed.status) && !feed.reviewComment.isEmpty())
			{
				pOut.append("<span class=\"mdmmFeedComment\" title=\"").append(MatrixHtml.esc(feed.reviewComment)).append("\">")
					.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.feed.rejectedComment"))).append("</span>");
			}
			if (MatrixCompute.canDecideFeed(feed))
			{
				pOut.append("<div class=\"mdmmHeadActions\">");
				pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs mdmmBtnPrimary\"").append(pState.readOnly ? " disabled=\"disabled\"" : "")
					.append(" onclick=\"mdmmAcceptFeed(").append(MatrixHtml.jsAttr(feed.id)).append(")\">")
					.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.feed.accept"))).append("</button>");
				pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs\"").append(pState.readOnly ? " disabled=\"disabled\"" : "")
					.append(" onclick=\"mdmmRejectFeed(").append(MatrixHtml.jsAttr(feed.id)).append(")\">")
					.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.feed.reject"))).append("</button>");
				pOut.append("</div>");
			}
			// UX lot U6: the per-source-link "Accept N" is a SECONDARY action of the card (small, link-like, with its scope in the tooltip), no longer a
			// primary-looking button next to the two that matter (the object's and the project's)
			final int feedBulkCount = MatrixCompute.planFeedBulk(pActive, feed, pState.bulkThreshold).toDecide.size();
			pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs mdmmBtnLink\"").append(feedBulkCount == 0 || pState.readOnly ? " disabled=\"disabled\"" : "")
				.append(" title=\"").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.scope.feed", Integer.valueOf(pState.bulkThreshold))))
				.append("\" onclick=\"mdmmBulkFeed(").append(MatrixHtml.jsAttr(feed.id)).append(")\">")
				.append(feedBulkCount == 0 ? MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.bulk.none"))
					: MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.bulk.feed", Integer.valueOf(feedBulkCount), Integer.valueOf(pState.bulkThreshold))))
				.append("</button>");
			pOut.append("</div>");
		}
		// delta 1: the "Rules and checks" column header, plus every entity-level guard (no attribute of its
		// own) as a small line right under it.
		pOut.append("<div class=\"mdmmGuardHead\"><span class=\"mdmmNewTag\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.guards.newTag")))
			.append("</span><span>").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.guards.header"))).append("</span>");
		if (!pActive.entityGuards.isEmpty())
		{
			pOut.append("<div class=\"mdmmGuardChips\">");
			for (final Guard guard : pActive.entityGuards)
			{
				MatrixHtml.renderGuardChip(pOut, pActive, null, guard, pState, pLocale);
			}
			pOut.append("</div>");
		}
		pOut.append("</div>");

		String defaultKey = pState.selectedCellKey;
		boolean sawSelected = false;
		for (final MatrixModel.Attr attr : pActive.attrs)
		{
			// matrix review queue lot: one display:contents wrapper per field row, so "Only what's left to do" can hide the
			// WHOLE row (header, link cells, guard cell) with one class; the row stays when the field, one of its links or one
			// of its guards is still to decide (MatrixCompute#rowHasWork). The cells' own class strings are untouched.
			pOut.append("<div class=\"mdmmRow").append(MatrixCompute.rowHasWork(pActive, attr, pItems) ? "" : " mdmmRowDone").append("\">");
			pOut.append("<div class=\"mdmmAttr\" data-key=\"").append(MatrixHtml.esc(MatrixCompute.KEY_ATTRIBUTE + attr.id))
				.append("\"><div class=\"mdmmAttrTop\"><span class=\"mdmmTypeChip\">").append(MatrixHtml.esc(attr.dataType))
				.append("</span><span class=\"mdmmAttrName\">").append(MatrixHtml.esc(attr.name)).append("</span></div><div class=\"mdmmAttrTop\">");
			if (attr.isDerived())
			{
				// fix/matrix-derived-key-rows (2026-09-27): "Matching key of firstName" - the same wording the
				// proposal review already uses for this row (review.prop.attribute.foldkey), so the two screens agree.
				pOut.append("<span class=\"mdmmFlagChip derived\">").append(MatrixHtml.esc(MatrixHtml.derivedLabel(attr, "mappingMatrix.flag.derived.", pLocale))).append("</span>");
			}
			else if (attr.mandatory)
			{
				pOut.append("<span class=\"mdmmFlagChip req\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.flag.mandatory"))).append("</span>");
			}
			else if (attr.parentLink)
			{
				pOut.append("<span class=\"mdmmFlagChip\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.flag.parentLink"))).append("</span>");
			}
			else if (attr.sourceKey)
			{
				pOut.append("<span class=\"mdmmFlagChip\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.flag.sourceKey"))).append("</span>");
			}
			// delta 2: the field row's own "needs your eye" chip - true when ANY of its cells (any source) is flagged.
			if (MatrixCompute.attributeFlagged(pActive, attr.id))
			{
				pOut.append("<span class=\"mdmmFlagChip attn\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.attention.chip"))).append("</span>");
			}
			if (attr.isDerived())
			{
				// no "0 / N source links" on a row that has no source link by design - the merged cell says what feeds it
				pOut.append("<span class=\"mdmmCov\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.coverage.derived"))).append("</span></div>");
			}
			else
			{
				int covered = 0;
				for (final MatrixModel.Feed feed : pActive.feeds)
				{
					final String status = MatrixCompute.effectiveStatus(attr, feed);
					if (!MatrixModel.NONE.equals(status) && !MatrixModel.MISSING.equals(status))
					{
						covered++;
					}
				}
				pOut.append("<span class=\"mdmmCov\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.coverage", Integer.valueOf(covered), Integer.valueOf(pActive.feeds.size()))))
					.append("</span></div>");
			}
			// coordinator review: the row header's own Accept/Reject - never for the technical sourceKey row,
			// which has no decision of its own (it follows its entity, MatrixCompute#canDecideAttribute).
			if (MatrixCompute.canDecideAttribute(attr))
			{
				pOut.append("<div class=\"mdmmAttrActions\">");
				pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs mdmmBtnPrimary\"").append(pState.readOnly ? " disabled=\"disabled\"" : "")
					.append(" onclick=\"mdmmAcceptAttribute(").append(MatrixHtml.jsAttr(attr.id)).append(")\">")
					.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.attribute.accept"))).append("</button>");
				pOut.append("<button type=\"button\" class=\"mdmmBtn mdmmBtnXs\"").append(pState.readOnly ? " disabled=\"disabled\"" : "")
					.append(" onclick=\"mdmmRejectAttribute(").append(MatrixHtml.jsAttr(attr.id)).append(")\">")
					.append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.attribute.reject"))).append("</button>");
				pOut.append("</div>");
			}
			pOut.append("</div>");
			if (attr.isDerived())
			{
				// fix/matrix-derived-key-rows (2026-09-27): ONE cell spanning every source column - the value is
				// computed once per staging row from the entity's own attribute, whichever feed wrote that row,
				// so there is nothing per source to show or decide. No data-source: the source-focus dimming
				// (mdmmFocus) leaves it alone. Selectable like any cell: the inspector explains it.
				final String key = "derived|" + attr.id;
				final boolean todo = ReviewLine.PROPOSED.equals(attr.status);
				// fix/matrix-computed-field: a SCRIPT field whose function is known says WHAT it computes (call and result),
				// opens a richer inspector and carries its suggestion as a chip; FOLD_KEY / VALID_VALUE keep the text above.
				final boolean computed = MatrixComputed.hasComputation(attr);
				final String baseJson = MatrixHtml.cellJson(pActive, attr, null, null, MatrixModel.DERIVED, null, pLocale);
				final String dataCell = computed ? MatrixComputed.cellJson(baseJson, pActive, attr, pLocale) : baseJson;
				pOut.append("<button type=\"button\" class=\"mdmmCell link derived").append(todo ? " todo" : "").append("\" style=\"grid-column:span ")
					.append(Math.max(1, pActive.feeds.size())).append("\" data-key=\"").append(MatrixHtml.esc(key)).append("\" data-source=\"\" data-cell=\"")
					.append(MatrixHtml.esc(dataCell)).append("\" onclick=\"mdmmSelect(this)\">");
				pOut.append("<span class=\"c1\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.cell.derived.line1"))).append("</span><span class=\"c2\">")
					.append(MatrixHtml.esc(computed ? MatrixComputed.cellLine2(attr, pLocale) : MatrixHtml.derivedLabel(attr, "mappingMatrix.cell.derived.line2.", pLocale)))
					.append("</span>");
				pOut.append("</button>");
				if (computed && !attr.guards.isEmpty())
				{
					// the "Rules and checks" cell of a computed field: its own function as a chip (same inspector as the cell), then any other guard
					pOut.append("<div class=\"mdmmGuards\">");
					for (final Guard guard : attr.guards)
					{
						if (ReviewLine.SCRIPT_DERIVATION.equals(guard.nature))
						{
							MatrixHtml.renderComputedChip(pOut, attr, guard, dataCell, pState, pLocale);
						}
						else
						{
							MatrixHtml.renderGuardChip(pOut, pActive, attr, guard, pState, pLocale);
						}
					}
					pOut.append("</div>");
				}
				else
				{
					// the "Rules and checks" cell: a computed field carries no rule of its own - never "none proposed"
					pOut.append("<div class=\"mdmmGuards\"><span class=\"mdmmGuardNone\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.guards.notApplicable")))
						.append("</span></div>");
				}
				pOut.append("</div>"); // .mdmmRow
				continue;
			}
			for (final MatrixModel.Feed feed : pActive.feeds)
			{
				final String key = feed.id + "|" + attr.id;
				final String status = MatrixCompute.effectiveStatus(attr, feed);
				final MatrixModel.Cell cell = feed.cellsByAttributeId.get(attr.id);
				final MatrixModel.Source source = sourcesById.get(feed.sourceId);
				if (defaultKey == null && MatrixHtml.isDataOrStructural(status))
				{
					defaultKey = key;
				}
				final boolean isSelected = key.equals(defaultKey) && !sawSelected && key.equals(pState.selectedCellKey);
				if (key.equals(pState.selectedCellKey))
				{
					sawSelected = true;
				}
				final boolean flagged = cell != null && cell.isFlagged();
				final boolean todo = MatrixModel.PROPOSED.equals(status) || MatrixModel.MISSING.equals(status);
				final boolean dim = (pState.focusSourceId != null && !pState.focusSourceId.equals(feed.sourceId));
				// delta 2: a flagged cell gets its own distinct tone (amber dashed - same "attn" look the mockup
				// uses) regardless of its underlying ACCEPTED/PROPOSED status.
				pOut.append("<button type=\"button\" class=\"mdmmCell ").append(flagged ? "attn" : MatrixHtml.toneClass(status)).append(dim ? " dim" : "")
					.append(todo ? " todo" : "").append("\" data-key=\"").append(MatrixHtml.esc(key)).append("\" data-source=\"").append(MatrixHtml.esc(feed.sourceId))
					.append("\" data-cell=\"").append(MatrixHtml.esc(MatrixHtml.cellJson(pActive, attr, feed, cell, status, source, pLocale)))
					.append("\" onclick=\"mdmmSelect(this)\">");
				MatrixHtml.renderCellLines(pOut, attr, feed, cell, status, pLocale);
				pOut.append("</button>");
			}
			// delta 1: the "Rules and checks" cell of this row - one chip per guard of this attribute, or "none proposed".
			pOut.append("<div class=\"mdmmGuards\">");
			if (attr.guards.isEmpty())
			{
				pOut.append("<span class=\"mdmmGuardNone\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.guards.none"))).append("</span>");
			}
			else
			{
				for (final Guard guard : attr.guards)
				{
					MatrixHtml.renderGuardChip(pOut, pActive, attr, guard, pState, pLocale);
				}
			}
			pOut.append("</div>");
			pOut.append("</div>"); // .mdmmRow
		}
		pOut.append("</div>");
		pState.selectedCellKey = defaultKey;
	}

	/**
	 * The localized text of a derived row for one message-key prefix ({@code mappingMatrix.flag.derived.},
	 * {@code mappingMatrix.cell.derived.line2.}, {@code mappingMatrix.explain.derived.}): the key is the prefix
	 * plus the derivation ({@code FOLD_KEY}/{@code VALID_VALUE}/{@code SCRIPT}), {@code {0}} the source
	 * attribute's name. An unknown derivation, or one whose key is not in the bundle, falls back to the
	 * generic {@code <prefix>OTHER} entry; an unresolved source name reads as the localized "another field".
	 */
	static String derivedLabel(final MatrixModel.Attr pAttr, final String pKeyPrefix, final Locale pLocale)
	{
		final String from = pAttr.derivedFrom.isEmpty() ? MatrixMessages.get(pLocale, "mappingMatrix.derived.unknownSource") : pAttr.derivedFrom;
		final String key = pKeyPrefix + pAttr.derivation;
		final String text = MatrixMessages.get(pLocale, key, from);
		return key.equals(text) ? MatrixMessages.get(pLocale, pKeyPrefix + "OTHER", from) : text;
	}

	private static boolean isDataOrStructural(final String pStatus)
	{
		return MatrixModel.ACCEPTED.equals(pStatus) || MatrixModel.PROPOSED.equals(pStatus) || MatrixModel.REJECTED.equals(pStatus) || MatrixModel.KEY.equals(pStatus)
			|| MatrixModel.ENGINE.equals(pStatus);
	}

	private static void renderCellLines(final StringBuilder pOut, final MatrixModel.Attr pAttr, final MatrixModel.Feed pFeed, final MatrixModel.Cell pCell,
		final String pStatus, final Locale pLocale)
	{
		final String line1;
		final String line2;
		if (MatrixModel.MISSING.equals(pStatus))
		{
			line1 = MatrixMessages.get(pLocale, "mappingMatrix.cell.missing.line1");
			line2 = MatrixMessages.get(pLocale, "mappingMatrix.cell.missing.line2");
		}
		else if (MatrixModel.NONE.equals(pStatus))
		{
			line1 = "-";
			line2 = MatrixMessages.get(pLocale, "mappingMatrix.cell.none.line2");
		}
		else if (MatrixModel.ENGINE.equals(pStatus))
		{
			line1 = MatrixMessages.get(pLocale, "mappingMatrix.cell.engine.line1");
			line2 = MatrixMessages.get(pLocale, "mappingMatrix.cell.engine.line2");
		}
		else if (MatrixModel.KEY.equals(pStatus))
		{
			line1 = pCell == null || pCell.landingColumn == null ? "?" : pCell.landingColumn;
			line2 = MatrixMessages.get(pLocale, "mappingMatrix.cell.key.line2");
		}
		else if (pCell != null && pCell.isFlagged())
		{
			// live fix (delta 2/bug 5, docs/STAGING-PROPOSAL.md "live fixes"): a flagged cell shows WHY it
			// needs attention right in the grid, instead of the pipeline - the pipeline is still one click
			// away in the inspector, but a person scanning the grid should not have to select every amber
			// cell to find out which of the "needs your eye" reasons applies to it.
			line1 = pCell.landingColumn == null ? "?" : pCell.landingColumn;
			line2 = pCell.attentionReason.isEmpty() ? MatrixMessages.get(pLocale, "mappingMatrix.attention.chip") : pCell.attentionReason;
		}
		else
		{
			line1 = pCell == null || pCell.landingColumn == null ? "?" : pCell.landingColumn;
			final String pipe = pCell == null || pCell.pipelineSteps.isEmpty() ? MatrixMessages.get(pLocale, "mappingMatrix.pipeline.direct")
				: MatrixHtml.join(pCell.pipelineSteps, " \u203a ");
			line2 = MatrixModel.PROPOSED.equals(pStatus) && pCell != null && pCell.confidence != null ? pipe + " \u00b7 " + pCell.confidence + "%" : pipe;
		}
		pOut.append("<span class=\"c1\">").append(MatrixHtml.esc(line1)).append("</span><span class=\"c2\">").append(MatrixHtml.esc(line2)).append("</span>");
		// fix/mapping-matrix-live (2026-09-24, coordinator: "the product owner explicitly asked for example values that
		// help"): a THIRD line, right in the cell, showing the sample "before -> after" - visible without
		// selecting the cell at all, never only in the inspector.
		if (pCell != null && pCell.sample != null && pCell.sample.isAvailable() && pCell.sample.after != null && !pCell.sample.after.isEmpty())
		{
			pOut.append("<span class=\"c3\">").append(MatrixHtml.esc(pCell.sample.before)).append(" \u2192 ").append(MatrixHtml.esc(pCell.sample.after)).append("</span>");
		}
	}

	private static String toneClass(final String pStatus)
	{
		if (MatrixModel.ACCEPTED.equals(pStatus))
		{
			return "good";
		}
		if (MatrixModel.PROPOSED.equals(pStatus))
		{
			// live fix (bug 5, docs/STAGING-PROPOSAL.md "live fixes"): a plain to-validate cell must read as
			// NEUTRAL (mockup docs/mockups/staging-proposal/Matrix.dc.html: ".cell.prop", white/grey), not
			// amber-dashed like ".attn" - "warn" used to be reused for both, making a flagged cell
			// indistinguishable from an ordinary PROPOSED one in the grid (only the inspector showed the
			// reason). "prop" is its own tone now, styled separately below; "warn" itself is untouched for
			// whatever else in this module's shared chrome still uses that name.
			return "prop";
		}
		if (MatrixModel.REJECTED.equals(pStatus))
		{
			return "bad";
		}
		if (MatrixModel.KEY.equals(pStatus) || MatrixModel.ENGINE.equals(pStatus))
		{
			return "link";
		}
		if (MatrixModel.MISSING.equals(pStatus))
		{
			return "missing";
		}
		return "none";
	}

	// ---------------------------------------------------------------------------------------------------- inspector

	private static void renderInspectorShell(final StringBuilder pOut, final Locale pLocale)
	{
		pOut.append("<div id=\"mdmmInspector\" class=\"mdmmInspector\"></div>");
		pOut.append("<template id=\"mdmmInspectorTpl\">").append(MatrixHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.inspector.empty"))).append("</template>");
	}

	/**
	 * One cell's full detail, as a JS object literal - read back by {@code mdmmSelect} (see {@link #script}).
	 * Hand-built (never Jackson): this class stays free of a JSON-library dependency, matching every other
	 * pure {@code *Html}/{@code *Page} class of this module. Carries {@code attrId}/{@code attrStatus}/
	 * {@code entityStatus} so the inspector can pick the right accept action client-side ({@link
	 * MatrixCompute#cellAcceptAction}, mirrored in JS - see {@link #script}'s own {@code cellAcceptAction}
	 * branches) without a round trip just to find out which button to show.
	 */
	private static String cellJson(final MatrixModel.Entity pEntity, final MatrixModel.Attr pAttr, final MatrixModel.Feed pFeed, final MatrixModel.Cell pCell,
		final String pStatus, final MatrixModel.Source pSource, final Locale pLocale)
	{
		final StringBuilder j = new StringBuilder(512);
		j.append('{');
		MatrixHtml.jsonField(j, "status", pStatus, true);
		MatrixHtml.jsonField(j, "statusLabel", MatrixMessages.get(pLocale, "mappingMatrix.statusLabel." + pStatus), true);
		MatrixHtml.jsonField(j, "entity", pEntity.name, true);
		MatrixHtml.jsonField(j, "entityStatus", pEntity.status, true);
		MatrixHtml.jsonField(j, "attr", pAttr.name, true);
		MatrixHtml.jsonField(j, "attrId", pAttr.id, true);
		MatrixHtml.jsonField(j, "attrStatus", pAttr.status, true);
		MatrixHtml.jsonField(j, "srcName", pSource == null ? (MatrixModel.DERIVED.equals(pStatus) ? MatrixMessages.get(pLocale, "mappingMatrix.statusLabel.DERIVED") : "?") : pSource.sourceSystem, true);
		MatrixHtml.jsonField(j, "srcColor", pSource == null ? "#98a2b3" : pSource.color, true);
		MatrixHtml.jsonField(j, "col", MatrixModel.DERIVED.equals(pStatus) ? pAttr.derivedFrom : pCell == null || pCell.landingColumn == null ? "" : pCell.landingColumn, true);
		final boolean isData = pCell != null && pCell.isData();
		MatrixHtml.jsonField(j, "isData", isData ? "true" : "false", false);
		MatrixHtml.jsonField(j, "isStruct",
			MatrixModel.KEY.equals(pStatus) || MatrixModel.ENGINE.equals(pStatus) || MatrixModel.DERIVED.equals(pStatus) ? "true" : "false", false);
		MatrixHtml.jsonField(j, "isEmpty", MatrixModel.NONE.equals(pStatus) || MatrixModel.MISSING.equals(pStatus) ? "true" : "false", false);
		final String explain;
		if (MatrixModel.ENGINE.equals(pStatus))
		{
			explain = MatrixMessages.get(pLocale, "mappingMatrix.explain.engine");
		}
		else if (MatrixModel.KEY.equals(pStatus))
		{
			explain = MatrixMessages.get(pLocale, "mappingMatrix.explain.key", pCell == null || pCell.landingColumn == null ? "?" : pCell.landingColumn);
		}
		else if (MatrixModel.MISSING.equals(pStatus))
		{
			explain = MatrixMessages.get(pLocale, "mappingMatrix.explain.missing", pSource == null ? "?" : pSource.sourceSystem, pAttr.name);
		}
		else if (MatrixModel.NONE.equals(pStatus))
		{
			explain = MatrixMessages.get(pLocale, "mappingMatrix.explain.none", pSource == null ? "?" : pSource.sourceSystem, pAttr.name);
		}
		else if (MatrixModel.DERIVED.equals(pStatus))
		{
			explain = MatrixHtml.derivedLabel(pAttr, "mappingMatrix.explain.derived.", pLocale);
		}
		else
		{
			explain = "";
		}
		MatrixHtml.jsonField(j, "explain", explain, true);
		MatrixHtml.jsonField(j, "steps", pCell == null ? "" : MatrixHtml.join(pCell.pipelineSteps), true);
		MatrixHtml.jsonField(j, "confidence", pCell == null || pCell.confidence == null ? "" : String.valueOf(pCell.confidence), false);
		MatrixHtml.jsonField(j, "origin", pCell == null || pCell.origin == null ? "" : MatrixMessages.get(pLocale, "mappingMatrix.origin." + pCell.origin), true);
		// lot G2 (2026-10-17): the "Value check" note of lot D/E and the field's own check sentence (lot A) come out of the running text into
		// blocks of their own, so the reason a link or a field is held back is read first and never printed twice
		MatrixHtml.jsonField(j, "rationale", pCell == null || pCell.rationale == null ? "" : RationaleText.one(CheckNote.base(pCell.rationale), pLocale).trim(), true);
		final MatrixNotes.Note valueCheck = pCell == null ? null : MatrixNotes.valueCheck(pCell.rationale, pLocale);
		MatrixHtml.jsonField(j, "valueCheck", valueCheck == null ? "" : MatrixMessages.get(pLocale, "mappingMatrix.check.valueCheck", valueCheck.sentence), true);
		final MatrixNotes.Note fieldCheck = pCell == null || !pCell.isData() ? null : MatrixNotes.leadingCheck(pAttr.rationale);
		MatrixHtml.jsonField(j, "check", fieldCheck == null ? "" : fieldCheck.sentence, true);
		MatrixHtml.jsonField(j, "reviewComment", pCell == null || pCell.reviewComment == null ? "" : pCell.reviewComment, true);
		MatrixHtml.jsonField(j, "sampleIn", pCell == null || pCell.sample == null || !pCell.sample.isAvailable() ? "" : pCell.sample.before, true);
		MatrixHtml.jsonField(j, "sampleOut", pCell == null || pCell.sample == null || !pCell.sample.isAvailable() ? "" : pCell.sample.after, true);
		MatrixHtml.jsonField(j, "sampleUnavailable", pCell == null || pCell.sample == null ? "" : pCell.sample.isAvailable() ? "" : pCell.sample.unavailableReason, true);
		MatrixHtml.jsonField(j, "sampleNote", pCell == null || pCell.sample == null ? "" : pCell.sample.note, true);
		MatrixHtml.jsonField(j, "mappingId", pCell == null || pCell.mappingId == null ? "" : pCell.mappingId, true);
		// coordinator review (2026-09-23): the cell's own accept action depends on the attribute's and the
		// entity's status, not only the cell's own - see MatrixCompute#cellAcceptAction and this class'
		// Javadoc. canReject is unaffected: review.ReviewRules never gates REJECT on the parent's status.
		final MatrixCompute.CellAcceptAction acceptAction = MatrixCompute.cellAcceptAction(pEntity.status, pAttr.status);
		MatrixHtml.jsonField(j, "acceptAction", isData && !MatrixModel.ACCEPTED.equals(pStatus) ? acceptAction.name() : "", true);
		MatrixHtml.jsonField(j, "canReject", isData && !MatrixModel.REJECTED.equals(pStatus) ? "true" : "false", false);
		MatrixHtml.jsonField(j, "feedComment", pFeed == null ? "" : pFeed.reviewComment, true);
		// fix/mapping-matrix-live (2026-09-24): "Open this feed in the Designer" - only ever set for a ROOT
		// feed (ebx.MappingMatrixReader only computes it for one; core.FeedDesignerLink accepts nothing else).
		MatrixHtml.jsonField(j, "designerUrl", isData && pFeed != null ? pFeed.designerUrl : "", true);
		// staging-proposal matrix lot (delta 2/3): the attention flag/reason baked onto the cell by
		// ebx.MappingMatrixReader, and the suggested fix(es) this flagged cell offers.
		final boolean flagged = pCell != null && pCell.isFlagged();
		MatrixHtml.jsonField(j, "attnFlag", pCell == null ? "" : pCell.attentionFlag, true);
		MatrixHtml.jsonField(j, "attnReason", pCell == null ? "" : pCell.attentionReason, true);
		if (flagged && pCell.alternative != null)
		{
			MatrixHtml.jsonField(j, "altMappingId", pCell.alternative.mappingId, true);
			MatrixHtml.jsonField(j, "altCol", pCell.alternative.landingColumn, true);
			MatrixHtml.jsonField(j, "altSteps", MatrixHtml.join(pCell.alternative.pipelineSteps), true);
			MatrixHtml.jsonField(j, "altRationale", RationaleText.display(pCell.alternative.rationale, pLocale), true);
			MatrixHtml.jsonField(j, "altSampleIn",
				pCell.alternative.sample == null || !pCell.alternative.sample.isAvailable() ? "" : pCell.alternative.sample.before, true);
			MatrixHtml.jsonField(j, "altSampleOut",
				pCell.alternative.sample == null || !pCell.alternative.sample.isAvailable() ? "" : pCell.alternative.sample.after, true);
		}
		else
		{
			MatrixHtml.jsonField(j, "altMappingId", "", true);
		}
		// delta 3: "Remove the field" (EMPTY_COLUMN on every source) / "Remove the N duplicate fields"
		// (DUPLICATE_FIELD) - both scoped to the whole attribute, not just this one cell, so every flagged
		// cell of the same field offers the same fix.
		MatrixHtml.jsonField(j, "removeFieldAttrId", flagged && MatrixCompute.attributeEmptyEverywhere(pEntity, pAttr) ? pAttr.id : "", true);
		final List<String> dupIds = flagged ? MatrixCompute.duplicateFieldAttributeIds(pEntity) : java.util.Collections.<String>emptyList();
		MatrixHtml.jsonField(j, "removeDuplicatesEntityId", flagged && dupIds.contains(pAttr.id) ? pEntity.id : "", true);
		MatrixHtml.jsonField(j, "removeDuplicatesCount", String.valueOf(dupIds.size()), false);
		MatrixHtml.jsonField(j, "codeLists", MatrixHtml.codeListsJson(pCell, pLocale), false);
		if (j.charAt(j.length() - 1) == ',')
		{
			j.setLength(j.length() - 1);
		}
		j.append('}');
		return j.toString();
	}

	/** Codes transcoding lot H3: the code tables of a cell as a JSON array (see the inspector's {@code codeBlock}); {@code []} for almost every cell. */
	static String codeListsJson(final MatrixModel.Cell pCell, final Locale pLocale)
	{
		if (pCell == null || pCell.codeLists.isEmpty())
		{
			return "[]";
		}
		final StringBuilder j = new StringBuilder(256);
		j.append('[');
		for (final MatrixModel.CodeList list : pCell.codeLists)
		{
			if (j.length() > 1)
			{
				j.append(',');
			}
			j.append('{');
			MatrixHtml.jsonField(j, "id", list.stepId, true);
			MatrixHtml.jsonField(j, "status", list.status, true);
			MatrixHtml.jsonField(j, "statusLabel", MatrixMessages.get(pLocale, "mappingMatrix.statusLabel." + list.status), true);
			MatrixHtml.jsonField(j, "open", list.isOpen() ? "true" : "false", false);
			MatrixHtml.jsonField(j, "confidence", list.confidence == null ? "" : String.valueOf(list.confidence), false);
			MatrixHtml.jsonField(j, "basedOn", list.basedOn, true);
			MatrixHtml.jsonField(j, "total", String.valueOf(list.total), false);
			MatrixHtml.jsonField(j, "unresolved", String.valueOf(list.unresolved), false);
			// staging last points (2026-10-17): Accept is disabled while an observed code with rows has no target; the card says what to fill
			MatrixHtml.jsonField(j, "blocked", list.acceptBlocked() ? "true" : "false", false);
			MatrixHtml.jsonField(j, "fill", list.acceptBlocked() ? MatrixMessages.get(pLocale, "review.refuse.codeTable", com.on.ebx.factory.review.ReviewRules.codesText(list.missing)) : "", true);
			final StringBuilder targets = new StringBuilder("[");
			for (final String target : list.targets)
			{
				targets.append(targets.length() > 1 ? "," : "").append('"').append(MatrixHtml.jsonEscape(target)).append('"');
			}
			MatrixHtml.jsonField(j, "targets", targets.append(']').toString(), false);
			final StringBuilder rows = new StringBuilder("[");
			for (final MatrixModel.CodeRow row : list.rows)
			{
				rows.append(rows.length() > 1 ? "," : "").append("{\"c\":\"").append(MatrixHtml.jsonEscape(row.code)).append("\",\"t\":\"").append(MatrixHtml.jsonEscape(row.target))
					.append("\",\"n\":").append(row.count).append('}');
			}
			MatrixHtml.jsonField(j, "rows", rows.append(']').toString(), false);
			j.setLength(j.length() - 1);
			j.append('}');
		}
		return j.append(']').toString();
	}

	// ------------------------------------------------------------------------------------------------------ guards

	/** One "Rules and checks" chip (delta 1) - a rule (QUALITY), a check (SCRIPT_CONSTRAINT/EXTERNAL_SERVICE/SCRIPT_DERIVATION), selectable exactly like a cell. */
	private static void renderGuardChip(final StringBuilder pOut, final MatrixModel.Entity pEntity, final MatrixModel.Attr pAttr, final Guard pGuard,
		final State pState, final Locale pLocale)
	{
		final String key = "guard|" + pGuard.id;
		final boolean rule = ReviewLine.QUALITY.equals(pGuard.nature);
		final String toneClass = ReviewLine.ACCEPTED.equals(pGuard.status) || ReviewLine.APPLIED.equals(pGuard.status) ? "good" : rule ? "rule" : "check";
		final boolean sel = key.equals(pState.selectedCellKey);
		pOut.append("<button type=\"button\" class=\"mdmmGChip ").append(toneClass).append(sel ? " sel" : "").append("\" data-key=\"").append(MatrixHtml.esc(key))
			.append("\" data-guard=\"").append(MatrixHtml.esc(MatrixHtml.guardJson(pEntity, pAttr, pGuard, pLocale))).append("\" onclick=\"mdmmSelectGuard(this)\">")
			.append(MatrixHtml.esc((rule ? MatrixMessages.get(pLocale, "mappingMatrix.guards.rulePrefix") : MatrixMessages.get(pLocale, "mappingMatrix.guards.checkPrefix"))
				+ pGuard.label()))
			.append("</button>");
	}

	/**
	 * fix/matrix-computed-field: the chip of a computed field's own function ({@code Script · toE164}) in the Rules and
	 * checks column. It carries the SAME JSON as the field's merged cell ({@code data-cell}), so selecting it opens the
	 * same inspector sections; the script's {@code mdmmSelectGuard} hands it over to the computed-field inspector.
	 */
	private static void renderComputedChip(final StringBuilder pOut, final MatrixModel.Attr pAttr, final Guard pGuard, final String pDataCell, final State pState,
		final Locale pLocale)
	{
		final String key = "guard|" + pGuard.id;
		final String toneClass = ReviewLine.ACCEPTED.equals(pGuard.status) || ReviewLine.APPLIED.equals(pGuard.status) ? "good" : "check";
		pOut.append("<button type=\"button\" class=\"mdmmGChip ").append(toneClass).append(key.equals(pState.selectedCellKey) ? " sel" : "").append("\" data-key=\"")
			.append(MatrixHtml.esc(key)).append("\" data-cell=\"").append(MatrixHtml.esc(pDataCell)).append("\" onclick=\"mdmmSelectGuard(this)\">")
			.append(MatrixHtml.esc(MatrixComputed.chipLabel(pAttr, pGuard, pLocale))).append("</button>");
	}

	/** One guard's full detail, mirroring {@link #cellJson}'s own hand-built-JSON convention. */
	private static String guardJson(final MatrixModel.Entity pEntity, final MatrixModel.Attr pAttr, final Guard pGuard, final Locale pLocale)
	{
		final StringBuilder j = new StringBuilder(512);
		j.append('{');
		MatrixHtml.jsonField(j, "id", pGuard.id, true);
		MatrixHtml.jsonField(j, "nature", pGuard.nature, true);
		final boolean rule = ReviewLine.QUALITY.equals(pGuard.nature);
		MatrixHtml.jsonField(j, "kindLabel", rule ? MatrixMessages.get(pLocale, "mappingMatrix.guards.kind.rule")
			: ReviewLine.SCRIPT_DERIVATION.equals(pGuard.nature) ? MatrixMessages.get(pLocale, "mappingMatrix.guards.kind.derivation")
				: MatrixMessages.get(pLocale, "mappingMatrix.guards.kind.check"), true);
		MatrixHtml.jsonField(j, "label", pGuard.label(), true);
		MatrixHtml.jsonField(j, "entity", pEntity.name, true);
		MatrixHtml.jsonField(j, "attr", pAttr == null ? "" : pAttr.name, true);
		MatrixHtml.jsonField(j, "status", pGuard.status, true);
		MatrixHtml.jsonField(j, "statusLabel", MatrixMessages.get(pLocale, "mappingMatrix.statusLabel." + pGuard.status), true);
		MatrixHtml.jsonField(j, "origin", pGuard.origin == null ? "" : MatrixMessages.get(pLocale, "mappingMatrix.origin." + pGuard.origin), true);
		MatrixHtml.jsonField(j, "confidence", pGuard.confidence == null ? "" : String.valueOf(pGuard.confidence), false);
		MatrixHtml.jsonField(j, "rationale", pGuard.rationale == null ? "" : pGuard.rationale, true);
		// delta 1: what it checks - a rule (kind + argument digest) or a check (the library function/connector operation).
		final String what;
		if (rule)
		{
			what = MatrixMessages.get(pLocale, "mappingMatrix.guards.what.rule", pGuard.kind, pGuard.argument);
		}
		else if (ReviewLine.SCRIPT_DERIVATION.equals(pGuard.nature))
		{
			what = MatrixMessages.get(pLocale, "mappingMatrix.guards.what.derivation", pGuard.kind);
		}
		else if (ReviewLine.EXTERNAL_SERVICE.equals(pGuard.nature))
		{
			what = MatrixMessages.get(pLocale, "mappingMatrix.guards.what.externalService", pGuard.kind);
		}
		else
		{
			what = MatrixMessages.get(pLocale, "mappingMatrix.guards.what.check", pGuard.kind);
		}
		MatrixHtml.jsonField(j, "what", what, true);
		final String seen;
		if (pGuard.valuesSeen != null)
		{
			seen = MatrixMessages.get(pLocale, "mappingMatrix.guards.seen", String.valueOf(pGuard.valuesFailing == null ? 0 : pGuard.valuesFailing),
				String.valueOf(pGuard.valuesSeen));
		}
		else
		{
			seen = pGuard.examples == null ? "" : pGuard.examples;
		}
		MatrixHtml.jsonField(j, "seen", seen, true);
		// delta 1: the severity/transferPolicy control - QUALITY (WARN/REJECT/MODEL_CONSTRAINT) and
		// SCRIPT_CONSTRAINT (blank/WARN/REJECT) only; EXTERNAL_SERVICE/SCRIPT_DERIVATION show no control at all.
		MatrixHtml.jsonField(j, "hasSeverity", MatrixCompute.guardHasSeverityControl(pGuard) ? "true" : "false", false);
		MatrixHtml.jsonField(j, "severity", pGuard.severity == null ? "" : pGuard.severity, true);
		MatrixHtml.jsonField(j, "transferPolicy", pGuard.transferPolicy == null ? "" : pGuard.transferPolicy, true);
		MatrixHtml.jsonField(j, "canDecide", MatrixCompute.canDecideGuard(pGuard) ? "true" : "false", false);
		if (j.charAt(j.length() - 1) == ',')
		{
			j.setLength(j.length() - 1);
		}
		j.append('}');
		return j.toString();
	}

	static void jsonField(final StringBuilder pOut, final String pKey, final String pValue, final boolean pQuoted)
	{
		pOut.append('"').append(pKey).append("\":");
		if (pQuoted)
		{
			pOut.append('"').append(MatrixHtml.jsonEscape(pValue == null ? "" : pValue)).append('"');
		}
		else
		{
			// fix/matrix-perspective-loss (2026-09-27): an unquoted value left empty (a link or a guard with no
			// confidence) used to write "key":, - invalid JSON, so the inspector's JSON.parse threw at load and took
			// every later script of the page down with it. Empty means "no value": null.
			pOut.append(pValue == null ? "false" : pValue.isEmpty() ? "null" : pValue);
		}
		pOut.append(',');
	}

	static String jsonEscape(final String pValue)
	{
		final StringBuilder out = new StringBuilder(pValue.length() + 8);
		for (int i = 0; i < pValue.length(); i++)
		{
			final char c = pValue.charAt(i);
			if (c == '"' || c == '\\')
			{
				out.append('\\').append(c);
			}
			else if (c == '\n')
			{
				out.append("\\n");
			}
			else if (c == '\r')
			{
				out.append("\\r");
			}
			else if (c < 0x20)
			{
				out.append(' ');
			}
			else
			{
				out.append(c);
			}
		}
		return out.toString();
	}

	// -------------------------------------------------------------------------------------------- hidden state / JS

	private static void renderHiddenFields(final StringBuilder pOut, final State pState)
	{
		pOut.append("<input type=\"hidden\" id=\"mdmmAction\" name=\"mdmmAction\" value=\"\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmRowId\" name=\"mdmmRowId\" value=\"\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmRowId2\" name=\"mdmmRowId2\" value=\"\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmComment\" name=\"mdmmComment\" value=\"\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmEntity\" name=\"mdmmEntity\" value=\"").append(MatrixHtml.esc(pState.activeEntityId == null ? "" : pState.activeEntityId))
			.append("\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmFocus\" name=\"mdmmFocus\" value=\"").append(MatrixHtml.esc(pState.focusSourceId == null ? "" : pState.focusSourceId))
			.append("\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmOnlyTodo\" name=\"mdmmOnlyTodo\" value=\"").append(pState.onlyTodo ? "1" : "0").append("\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmQueueMode\" name=\"mdmmQueue\" value=\"").append(MatrixHtml.esc(pState.queueMode)).append("\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmScroll\" name=\"mdmmScroll\" value=\"\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmSelected\" name=\"mdmmSelected\" value=\"")
			.append(MatrixHtml.esc(pState.selectedCellKey == null ? "" : pState.selectedCellKey)).append("\"/>");
	}

	/**
	 * The whole interaction script, as a plain JS string - NEVER wrapped in a {@code <script>} tag by this
	 * class (class Javadoc: {@code ebx.ui.MappingMatrixUserService#write} feeds this straight to {@code
	 * UserServicePaneWriter#addJS_cr}, the only way EBX actually executes a screen's own script). An IIFE, so
	 * repeated calls (this runs again on every postback re-render) never leak variables into the global scope
	 * - only the {@code window.mdmmX} functions the inline {@code onclick} handlers call are intentionally
	 * global.
	 */
	public static String script(final Locale pLocale)
	{
		final StringBuilder pOut = new StringBuilder(4096);
		// staging-proposal matrix lot (delta 5): every English string the script used to hard-code is now
		// read from the bundle (mappingMatrix.js.*) and JS-escaped once here, instead of literal text baked
		// into the generated JS - the FR translation now genuinely applies to the inspector, not just the
		// server-rendered HTML around it.
		pOut.append("(function(){\n").append("var T=").append(MatrixHtml.jsStringsObject(pLocale)).append(";\n")
			.append("function byId(id){return document.getElementById(id);}\n")
			// security review 2026-09-24, findings I1/I3: e() HTML-escapes any data-derived value before it is
			// dropped into an html string built by plain JS concatenation (every window.mdmmX handler below does
			// this, then assigns the result with insp.innerHTML=html - an unescaped value there is a stored DOM
			// XSS: a source name, an LLM rationale, a review comment, all real data can carry markup/script).
			// eAttr() additionally JS-string-escapes first (backslash, then the single quote), for a value dropped
			// straight inside an onclick=\"fn('HERE')\" attribute - the plain-Java equivalent is MatrixHtml#jsAttr,
			// used by render() itself; this script is emitted once as plain JS text, so it needs its own JS-side
			// copy. eColor() only lets a well-formed hex color through (data.srcColor is dropped into a style
			// attribute unescaped otherwise - a CSS/attribute-injection vector of its own).
			.append("function e(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;').replace(/'/g,'&#39;');}\n")
			.append("function eAttr(s){s=String(s==null?'':s).replace(/\\\\/g,'\\\\\\\\').replace(/'/g,\"\\\\'\");return e(s);}\n")
			.append("function eColor(s){return /^#[0-9a-fA-F]{3,8}$/.test(s||'')?s:'#98a2b3';}\n")
			// staging-screens lot (2026-10-17): inside a UserServicePane the window never scrolls, an EBX container div does (CLAUDE.md sec 9): its scrollTop travels in the
			// form (mdmmScroll) and comes back in data-scrolltop, so a click on the queue keeps the page where it was
			.append("function scroller(){var el=byId('mdmm');el=el?el.parentElement:null;while(el&&el!==document.body&&el!==document.documentElement){var st=window.getComputedStyle(el);if(el.scrollHeight>el.clientHeight&&(st.overflowY==='auto'||st.overflowY==='scroll')){return el;}el=el.parentElement;}return document.scrollingElement||document.documentElement;}\n")
			.append("function submitAction(action,rowId,rowId2,comment){try{byId('mdmmScroll').value=String(Math.round(scroller().scrollTop));}catch(x){}byId('mdmmAction').value=action;byId('mdmmRowId').value=rowId||'';byId('mdmmRowId2').value=rowId2||'';byId('mdmmComment').value=comment||'';var b=byId('mdmmSubmit');if(b){b.click();}}\n")
			.append("window.mdmmFocus=function(id){var root=byId('mdmm');var cur=root.getAttribute('data-focus');var next=(cur===id)?'':id;root.setAttribute('data-focus',next);byId('mdmmFocus').value=next;var dsw=byId('mdmmDesignerSwitch');if(dsw){var dmap={};try{dmap=JSON.parse(dsw.getAttribute('data-by-source')||'{}');}catch(ex){}dsw.setAttribute('href',(next&&dmap[next])?dmap[next]:dsw.getAttribute('data-default'));}var srcCards=document.querySelectorAll('.mdmmSrcCard');for(var i=0;i<srcCards.length;i++){srcCards[i].classList.remove('on','off');if(next){if(srcCards[i].getAttribute('onclick').indexOf(\"'\"+next+\"'\")>=0){srcCards[i].classList.add('on');}else{srcCards[i].classList.add('off');}}}var heads=document.querySelectorAll('.mdmmHead,.mdmmCell');for(var j=0;j<heads.length;j++){var s=heads[j].getAttribute('data-source');if(next&&s&&s!==next){heads[j].classList.add(heads[j].classList.contains('mdmmCell')?'dim':'off');}else{heads[j].classList.remove('dim');heads[j].classList.remove('off');}}};\n")
			.append("window.mdmmApplyTodo=function(on){var root=byId('mdmm');if(!root){return;}root.classList.toggle('mdmmOnlyTodo',!!on);byId('mdmmOnlyTodo').value=on?'1':'0';var t=document.querySelectorAll('.mdmmToggle');for(var i=0;i<t.length;i++){t[i].classList.toggle('on',!!on);}};\n")
			// UX lot U6: the person's own choice is remembered per user and project by the page's store (MappingMatrixUserService writes mdmmTodoRemember)
			.append("window.mdmmToggleTodo=function(){var root=byId('mdmm');var on=!root.classList.contains('mdmmOnlyTodo');window.mdmmApplyTodo(on);if(window.mdmmTodoRemember){window.mdmmTodoRemember(on);}};\n")
			// fix/matrix-goto-entity (2026-09-27): the hidden mdmmEntity field must carry the TARGET entity before the
			// submit. EBX runs setupObjectContext AFTER the event callback on a postback (UserServiceControllerImpl:
			// validate() -> processEvent -> nextDisplay, then beforeDisplay() -> setupObjectContext -> setupDisplay,
			// javap on ebx-manager.jar 6.3.0-RC2; the UserService Javadoc's own "Display" sequence says the same), and
			// ebx.ui.MappingMatrixUserService#setupObjectContext re-reads mdmmEntity on every request - so a GOTO_ENTITY
			// that only posted the id in mdmmRowId was applied by handle() and then overwritten by the stale hidden
			// field: clicking Adresse always landed back on Personne (seen live 2026-09-27, project B). Every other
			// hidden field (mdmmFocus, mdmmOnlyTodo, mdmmSelected) was already updated client-side before the submit.
			.append("window.mdmmOpenEntity=function(id){byId('mdmmEntity').value=id;submitAction('GOTO_ENTITY',id,'','');};\n")
			// fix/mapping-matrix-live: a curve's own status chip opens its entity WITH that source focused, in
			// one click - the \"clicking a chip opens that entity with that source highlighted\" task brief.
			.append("window.mdmmOpenEntityFocus=function(entityId,sourceId){byId('mdmmEntity').value=entityId;byId('mdmmFocus').value=sourceId;submitAction('GOTO_ENTITY',entityId,'','');};\n")
			.append("window.mdmmBulk=function(entityId){submitAction('BULK_ENTITY',entityId,'','');};\n")
			.append("window.mdmmBulkFeed=function(feedId){submitAction('BULK_FEED',feedId,'','');};\n")
			.append("window.mdmmAcceptEntity=function(entityId){submitAction('ACCEPT_ENTITY',entityId,'','');};\n")
			.append("window.mdmmRejectEntity=function(entityId){submitAction('REJECT_ENTITY',entityId,'','');};\n")
			.append("window.mdmmAcceptAttribute=function(attrId){submitAction('ACCEPT_ATTRIBUTE',attrId,'','');};\n")
			.append("window.mdmmRejectAttribute=function(attrId){submitAction('REJECT_ATTRIBUTE',attrId,'','');};\n")
			.append("window.mdmmAcceptFeed=function(feedId){submitAction('ACCEPT_FEED',feedId,'','');};\n")
			.append("window.mdmmRejectFeed=function(feedId){var c=window.prompt('');if(c===null){return;}submitAction('REJECT_FEED',feedId,'',c);};\n")
			.append("window.mdmmAccept=function(mappingId){byId('mdmmSelected').value=window.mdmmSelectedKey||'';submitAction('ACCEPT_MAPPING',mappingId,'','');};\n")
			.append("window.mdmmAcceptAttrThenMapping=function(attrId,mappingId){byId('mdmmSelected').value=window.mdmmSelectedKey||'';submitAction('ACCEPT_ATTR_THEN_MAPPING',attrId,mappingId,'');};\n")
			.append("window.mdmmReject=function(mappingId){var c=window.prompt('');byId('mdmmSelected').value=window.mdmmSelectedKey||'';submitAction('REJECT_MAPPING',mappingId,'',c===null?'':c);};\n")
			// delta 2: the flagged cell's own reason, right under the header, before the sample/pipeline detail.
			// delta 3: the suggested fix(es) of a flagged cell - an alternative mapping, \"remove the field\", or
			// \"remove the N duplicate fields\", each its own primary button, shown BEFORE the ordinary accept/reject.
			// coordinator review (2026-09-23): three distinct accept situations, never a silent cascade to the
			// entity - mirrors MatrixCompute#cellAcceptAction exactly.
			// fix/mapping-matrix-live: \"Open this feed in the Designer\" - target=_top so it opens as a whole
			// page, never inside a modal iframe (CLAUDE.md sec 9); class mdmf-matrix-designer-link is marked a
			// voluntary departure by ebx.ui.MappingMatrixUserService#write (FactoryHtml.voluntaryLeaveScript),
			// so no \"leave site?\" prompt appears.
			.append("window.mdmmSelect=function(btn){var cells=document.querySelectorAll('.mdmmCell');for(var i=0;i<cells.length;i++){cells[i].classList.remove('sel');}btn.classList.add('sel');var key=btn.getAttribute('data-key');window.mdmmSelectedKey=key;byId('mdmmSelected').value=key;var data=JSON.parse(btn.getAttribute('data-cell'));var insp=byId('mdmmInspector');var html='';html+='<div class=\"mdmmInspHead\"><span class=\"mdmmSrcTag\" style=\"background:'+eColor(data.srcColor)+'\">'+e(data.srcName)+'</span>';html+='<span class=\"mdmmFieldChip src\">'+e(data.col||'-')+'</span><span class=\"mdmmArrow\">&#8594;</span>';html+='<span class=\"mdmmFieldChip dst\">'+e(data.entity)+'.'+e(data.attr)+'</span>';html+='<span class=\"mdmmChip '+toneOf(data.status)+'\" style=\"margin-left:auto\">'+e(data.statusLabel)+'</span></div>';if(data.isStruct||data.isEmpty){html+='<p class=\"mdmmInspText\">'+e(data.explain)+'</p>';}\n")
			.append("if(data.feedComment){html+='<p class=\"mdmmInspText mdmmReject\">'+T.feedRejected+' '+e(data.feedComment)+'</p>';}if(data.attnFlag){html+='<p class=\"mdmmInspText mdmmAttn\">'+e(data.attnReason)+'</p>';}if(data.check){html+='<p class=\"mdmmInspText mdmmAttn mdmmCheck\">'+e(data.check)+'</p>';}if(data.valueCheck){html+='<p class=\"mdmmInspText mdmmAttn mdmmCheck\">'+e(data.valueCheck)+'</p>';}if(data.isData){html+='<div class=\"mdmmInspRow\">';if(data.steps){var parts=data.steps.split(String.fromCharCode(0x203a));for(var k=0;k<parts.length;k++){html+='<span class=\"mdmmStepChip\">'+e(parts[k].replace(/\\|/g,' '))+'</span>';}}else{html+='<span class=\"mdmmMuted\">&mdash;</span>';}html+='<span class=\"mdmmMuted\">'+T.sample+'</span><span class=\"mdmmSampleVal\">'+(data.sampleUnavailable?('('+e(data.sampleUnavailable)+')'):e(data.sampleIn||''))+'</span>';if(!data.sampleUnavailable){html+='<span class=\"mdmmMuted\">&#8594;</span><span class=\"mdmmSampleVal\">'+e(data.sampleOut||'')+'</span>';}html+='</div>';if(data.sampleNote){html+='<p class=\"mdmmInspText mdmmMuted\">'+e(data.sampleNote)+'</p>';}html+='<div class=\"mdmmInspRow\">'+T.origin+' <b>'+e(data.origin||'')+'</b>'+(data.confidence?(' '+T.confidence+' '+data.confidence+'%'):'')+'</div>';if(data.rationale){html+='<p class=\"mdmmInspText\">'+e(data.rationale)+'</p>';}if(data.reviewComment){html+='<p class=\"mdmmInspText mdmmReject\">'+e(data.reviewComment)+'</p>';}if(data.altMappingId){html+='<div class=\"mdmmInspRow mdmmFix\"><b>'+T.suggestedUse+' '+e(data.altCol||'')+'</b></div>';if(data.altSteps){html+='<div class=\"mdmmInspRow\">';var aparts=data.altSteps.split(String.fromCharCode(0x203a));for(var m=0;m<aparts.length;m++){html+='<span class=\"mdmmStepChip\">'+e(aparts[m])+'</span>';}html+='</div>';}if(data.altSampleIn||data.altSampleOut){html+='<div class=\"mdmmInspRow\"><span class=\"mdmmSampleVal\">'+e(data.altSampleIn||'')+'</span><span class=\"mdmmMuted\">&#8594;</span><span class=\"mdmmSampleVal\">'+e(data.altSampleOut||'')+'</span></div>';}if(data.altRationale){html+='<p class=\"mdmmInspText\">'+e(data.altRationale)+'</p>';}html+='<div class=\"mdmmInspRow\"><button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\" onclick=\"mdmmAcceptAlternative(\\''+eAttr(data.altMappingId)+'\\')\">'+T.acceptAlternative+'</button></div>';}\n")
			.append("if(data.removeFieldAttrId){html+='<div class=\"mdmmInspRow\"><button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\" onclick=\"mdmmRemoveField(\\''+eAttr(data.removeFieldAttrId)+'\\')\">'+T.removeField+'</button></div>';}\n")
			.append("if(data.removeDuplicatesEntityId){html+='<div class=\"mdmmInspRow\"><button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\" onclick=\"mdmmRemoveDuplicates(\\''+eAttr(data.removeDuplicatesEntityId)+'\\')\">'+T.removeDuplicates.replace('{0}',e(data.removeDuplicatesCount))+'</button></div>';}\n")
			.append("html+='<div class=\"mdmmInspRow\">';if(data.acceptAction==='ENTITY_NOT_ACCEPTED'){html+='<span class=\"mdmmMuted\">'+T.acceptEntityFirst+'</span>';}else if(data.acceptAction==='ACCEPT_ATTRIBUTE_THEN_MAPPING'){html+='<button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\" onclick=\"mdmmAcceptAttrThenMapping(\\''+eAttr(data.attrId)+'\\',\\''+eAttr(data.mappingId)+'\\')\">'+T.acceptAttrThenMapping+'</button>';}else if(data.acceptAction==='ACCEPT_MAPPING_ONLY'){html+='<button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\" onclick=\"mdmmAccept(\\''+eAttr(data.mappingId)+'\\')\">'+T.accept+'</button>';}if(data.canReject){html+='<button type=\"button\" class=\"mdmmBtn\" onclick=\"mdmmReject(\\''+eAttr(data.mappingId)+'\\')\">'+T.reject+'</button>';}if(data.designerUrl){html+='<a class=\"mdmmBtn mdmf-matrix-designer-link\" target=\"_top\" href=\"'+e(data.designerUrl)+'\">'+T.openDesigner+'</a>';}html+='</div>';}\n")
			.append("insp.innerHTML=html;};\n")
			// delta 1: the guard chip's own inspector - kind, what it checks, the severity/transferPolicy select
			// (only when the guard's nature has one), evidence, Accept/Reject. Same escaping discipline as
			// window.mdmmSelect above (security review 2026-09-24, findings I1/I3).
			.append("window.mdmmSelectGuard=function(btn){var cells=document.querySelectorAll('.mdmmCell,.mdmmGChip');for(var i=0;i<cells.length;i++){cells[i].classList.remove('sel');}btn.classList.add('sel');var key=btn.getAttribute('data-key');window.mdmmSelectedKey=key;byId('mdmmSelected').value=key;var g=JSON.parse(btn.getAttribute('data-guard'));var insp=byId('mdmmInspector');var html='';html+='<div class=\"mdmmInspHead\"><span class=\"mdmmChip acc\">'+e(g.kindLabel)+'</span><span class=\"mdmmFieldChip\">'+e(g.label)+'</span>';if(g.attr){html+='<span class=\"mdmmMuted\">on</span><span class=\"mdmmFieldChip dst\">'+e(g.entity)+'.'+e(g.attr)+'</span>';}html+='<span class=\"mdmmChip '+toneOf(g.status)+'\" style=\"margin-left:auto\">'+e(g.statusLabel)+'</span></div>';html+='<p class=\"mdmmInspText\">'+e(g.what)+'</p>';if(g.hasSeverity){html+='<div class=\"mdmmInspRow\">'+T.whenFails+' <select onchange=\"mdmmSetGuardSeverity(this,\\''+eAttr(g.id)+'\\',\\''+eAttr(g.nature)+'\\')\"><option value=\"WARN\"'+(g.severity==='WARN'||g.transferPolicy==='WARN'?' selected':'')+'>'+T.flagRecord+'</option><option value=\"REJECT\"'+(g.severity==='REJECT'||g.transferPolicy==='REJECT'?' selected':'')+'>'+T.stopRow+'</option></select></div>';}if(g.seen){html+='<p class=\"mdmmInspText mdmmMuted\">'+e(g.seen)+'</p>';}if(g.rationale){html+='<p class=\"mdmmInspText\">'+e(g.rationale)+'</p>';}html+='<div class=\"mdmmInspRow\">';if(g.canDecide){html+=(g.nature==='EXTERNAL_SERVICE'?'<button type=\"button\" class=\"mdmmBtn\" onclick=\"mdmmGuardDecide(\\''+eAttr(g.id)+'\\',\\''+eAttr(g.nature)+'\\',\\'SETASIDE\\')\">'+T.setAside+'</button>':'<button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\" onclick=\"mdmmGuardDecide(\\''+eAttr(g.id)+'\\',\\''+eAttr(g.nature)+'\\',\\'ACCEPT\\')\">'+T.accept+'</button>')+'<button type=\"button\" class=\"mdmmBtn\" onclick=\"mdmmGuardDecide(\\''+eAttr(g.id)+'\\',\\''+eAttr(g.nature)+'\\',\\'REJECT\\')\">'+T.reject+'</button>';}html+='</div>';insp.innerHTML=html;};\n")
			.append("window.mdmmSetGuardSeverity=function(sel,id,nature){submitAction('SET_GUARD_SEVERITY',id,nature,sel.value);};\n")
			.append("window.mdmmGuardDecide=function(id,nature,verb){submitAction(verb==='ACCEPT'?'ACCEPT_GUARD':verb==='SETASIDE'?'SET_ASIDE_GUARD':'REJECT_GUARD',id,nature,'');};\n")
			// UX lot U6: the optional look-up suggestions of the panel - one candidate, or every candidate of a field at once (ids joined by commas in data-ids)
			.append("window.mdmmServiceDecide=function(ev,id,verb){if(ev&&ev.stopPropagation){ev.stopPropagation();}submitAction(verb==='SETASIDE'?'SET_ASIDE_GUARD':'REJECT_GUARD',id,'EXTERNAL_SERVICE','');};\n")
			.append("window.mdmmServiceGroup=function(btn,verb){var d=btn.closest?btn.closest('details'):null;var ids=d?d.getAttribute('data-ids'):'';if(!ids){return;}submitAction(verb==='SETASIDE'?'SET_ASIDE_GUARDS':'REJECT_GUARDS',ids,'EXTERNAL_SERVICE','');};\n")
			.append("window.mdmmAcceptAlternative=function(mappingId){submitAction('ACCEPT_ALTERNATIVE',mappingId,'','');};\n")
			.append("window.mdmmRemoveField=function(attrId){submitAction('REJECT_ATTRIBUTE',attrId,'','');};\n")
			.append("window.mdmmRemoveDuplicates=function(entityId){submitAction('REMOVE_DUPLICATES',entityId,'','');};\n")
			.append("window.mdmmBulkProject=function(){submitAction('BULK_PROJECT','','','');};\n")
			.append("window.mdmmLoadStaging=function(){submitAction('LOAD_STAGING','','','');};\n")
			.append("function toneOf(s){if(s==='ACCEPTED')return 'good';if(s==='PROPOSED')return 'warn';if(s==='REJECTED')return 'bad';if(s==='KEY'||s==='ENGINE'||s==='DERIVED')return 'link';if(s==='MISSING')return 'bad';return 'none';}\n")
			// integration (2026-09-30): the computed-field wrappers go FIRST so the review queue's own wrappers below wrap them in
			// turn (highlight + prev/next bar refresh for a computed cell too); the queue's restore-selection line is the only one kept.
			// fix/matrix-computed-field (docs/MAPPING-MATRIX.md sec 14): the inspector of a COMPUTED field (its cell and its
			// "Script - function" chip carry {computed:true,...} in data-cell). Added as wrappers of the two selection handlers above, so
			// every other cell and guard keeps going through them untouched. Same escaping discipline as mdmmSelect (e/eAttr/eColor).
			.append("function selComputed(btn,d){var cs=document.querySelectorAll('.mdmmCell,.mdmmGChip');for(var i=0;i<cs.length;i++){cs[i].classList.remove('sel');}btn.classList.add('sel');var key=btn.getAttribute('data-key');window.mdmmSelectedKey=key;byId('mdmmSelected').value=key;var h='<div class=\"mdmmInspHead\"><span class=\"mdmmSrcTag\" style=\"background:'+eColor(d.srcColor)+'\">'+e(d.srcName)+'</span><span class=\"mdmmFieldChip src\">'+e(d.col||'-')+'</span><span class=\"mdmmArrow\">&#8594;</span><span class=\"mdmmFieldChip dst\">'+e(d.entity)+'.'+e(d.attr)+'</span><span class=\"mdmmChip '+toneOf(d.status)+'\" style=\"margin-left:auto\">'+e(d.statusLabel)+'</span></div>';\n")
			.append("h+='<div class=\"mdmmInspRow\"><b>'+e(d.cWhatTitle)+'</b></div><p class=\"mdmmInspText'+(d.cWhatMuted?' mdmmMuted':'')+'\">'+e(d.cWhat)+'</p><p class=\"mdmmInspText\"><code>'+e(d.cCall)+'</code></p>';var as=d.cArgs||[];for(var k=0;k<as.length;k++){h+='<p class=\"mdmmInspText\">'+e(as[k].line)+(as[k].text?' &mdash; '+e(as[k].text):'')+'</p>';}\n")
			.append("h+='<div class=\"mdmmInspRow\"><b>'+e(d.cEmptyTitle)+'</b></div><p class=\"mdmmInspText'+(d.cEmptyMuted?' mdmmMuted':'')+'\">'+e(d.cEmpty)+'</p>';h+='<div class=\"mdmmInspRow\"><b>'+e(d.cMeasureTitle)+'</b></div>';if(d.cMeasureState){h+='<p class=\"mdmmInspText mdmmMuted\">'+e(d.cMeasureState)+'</p>';}var ms=d.cMeasure||[];for(var m=0;m<ms.length;m++){h+='<p class=\"mdmmInspText\">'+e(ms[m].line)+'</p>';if(ms[m].conv){h+='<p class=\"mdmmInspText mdmmMuted\">'+e(ms[m].conv)+'</p>';}if(ms[m].empty){h+='<p class=\"mdmmInspText mdmmMuted\">'+e(ms[m].empty)+'</p>';}}\n")
			.append("if(d.cWhy){h+='<div class=\"mdmmInspRow\"><b>'+e(d.cWhyTitle)+'</b></div><p class=\"mdmmInspText\">'+e(d.cWhy)+'</p>';}\n")
			.append("if(d.cDecide==='FIELD'){h+='<div class=\"mdmmInspRow\"><button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\" onclick=\"mdmmAcceptAttribute(\\''+eAttr(d.attrId)+'\\')\">'+e(d.cAcceptLabel)+'</button><button type=\"button\" class=\"mdmmBtn\" onclick=\"mdmmRejectAttribute(\\''+eAttr(d.attrId)+'\\')\">'+e(d.cRejectLabel)+'</button></div>';}else if(d.cDecide==='ENTITY_FIRST'){h+='<div class=\"mdmmInspRow\"><span class=\"mdmmMuted\">'+e(d.cEntityFirst)+'</span><button type=\"button\" class=\"mdmmBtn\" onclick=\"mdmmRejectAttribute(\\''+eAttr(d.attrId)+'\\')\">'+e(d.cRejectLabel)+'</button></div>';}else if(d.cDecide==='GUARD'){h+='<div class=\"mdmmInspRow\"><button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\" onclick=\"mdmmGuardDecide(\\''+eAttr(d.cGuardId)+'\\',\\'SCRIPT_DERIVATION\\',\\'ACCEPT\\')\">'+e(d.cAcceptLabel)+'</button><button type=\"button\" class=\"mdmmBtn\" onclick=\"mdmmGuardDecide(\\''+eAttr(d.cGuardId)+'\\',\\'SCRIPT_DERIVATION\\',\\'REJECT\\')\">'+e(d.cRejectLabel)+'</button></div>';}byId('mdmmInspector').innerHTML=h;}\n")
			.append("var baseSelect=window.mdmmSelect;window.mdmmSelect=function(btn){var d=null;try{d=JSON.parse(btn.getAttribute('data-cell'));}catch(x){}if(d&&d.computed){selComputed(btn,d);return;}baseSelect(btn);};\n")
			.append("var baseSelectGuard=window.mdmmSelectGuard;window.mdmmSelectGuard=function(btn){var raw=btn.getAttribute('data-cell');if(raw){var d=JSON.parse(raw);if(d.computed){selComputed(btn,d);return;}}baseSelectGuard(btn);};\n")
			// matrix review queue lot (2026-09-30): the "left to decide" list, the walk-through, the pinned inspector's item view.
			.append("function qitems(){return document.querySelectorAll('#mdmmQueue .mdmmQItem');}\n")
			.append("function qdata(li){try{return JSON.parse(li.getAttribute('data-item'));}catch(x){return null;}}\n")
			.append("function qindex(key){var l=qitems();for(var i=0;i<l.length;i++){if(l[i].getAttribute('data-key')===key){return i;}}return -1;}\n")
			.append("function qbykey(key){var i=qindex(key);return i<0?null:qitems()[i];}\n")
			.append("function keySel(key){return '[data-key=\"'+String(key).replace(/\"/g,'')+'\"]';}\n")
			// staging-screens lot (2026-10-17): with the page's position restored (data-scrolltop), the selected item is brought into view only when it is off screen
			.append("function keepTop(){var r=byId('mdmm');var v=r?parseInt(r.getAttribute('data-scrolltop'),10):-1;return isNaN(v)?-1:v;}\n")
			.append("function scrollToEl(el){if(!el||!el.scrollIntoView){return;}if(keepTop()>=0){scroller().scrollTop=keepTop();var r=el.getBoundingClientRect();var sc=scroller().getBoundingClientRect();var dock=document.querySelector('.mdmmDock');var dTop=dock?dock.getBoundingClientRect().top:sc.bottom;if(r.top>=sc.top&&r.bottom<=dTop){return;}}el.scrollIntoView({block:'center',inline:'nearest'});}\n")
			.append("function hiliteKey(key){var old=document.querySelectorAll('.mdmmPick');for(var i=0;i<old.length;i++){old[i].classList.remove('mdmmPick');}if(!key){return null;}var k=keySel(key);var el=document.querySelector('.mdmmAttr'+k+',.mdmmHead'+k+',.mdmmTab'+k);if(el){el.classList.add('mdmmPick');}return el;}\n")
			.append("function fakeBtn(key,json){return {classList:{add:function(){},remove:function(){}},getAttribute:function(n){return n==='data-key'?key:json;}};}\n")
			.append("function witems(){var p=byId('mdmmQueue');var all=!p||p.classList.contains('mdmmQueueAllOn');return document.querySelectorAll('#mdmmQueue .mdmmQItem'+(all?'':':not(.bulk)'));}\n")
			.append("function windex(key){var l=witems();for(var i=0;i<l.length;i++){if(l[i].getAttribute('data-key')===key){return i;}}return -1;}\n")
			.append("function qkey(sel){sel=sel||'';return sel.indexOf('derived|')===0?'attr|'+sel.substring(8):sel;}\n")
			.append("function refreshBar(){var pos=byId('mdmmQPos');if(!pos){return;}var n=witems().length;var i=windex(qkey(byId('mdmmSelected').value));pos.textContent=i<0?T.queueCount.replace('{0}',n):T.queuePos.replace('{0}',i+1).replace('{1}',n);byId('mdmmQPrev').disabled=(i===0);byId('mdmmQNext').disabled=(i>=0&&i===n-1);}\n")
			.append("function itemButtons(it){var k=eAttr(it.key);var d=it.readOnly?' disabled=\"disabled\"':'';var h='';if(it.canAccept){h+='<button type=\"button\" class=\"mdmmBtn'+(it.checkReject?'':' mdmmBtnPrimary')+'\"'+d+' onclick=\"mdmmItemDecide(event,\\''+k+'\\',\\'ACCEPT\\')\">'+T.accept+'</button>';}if(it.setAside){h+='<button type=\"button\" class=\"mdmmBtn\"'+d+' onclick=\"mdmmItemDecide(event,\\''+k+'\\',\\'SETASIDE\\')\">'+T.setAside+'</button>';}h+='<button type=\"button\" class=\"mdmmBtn'+(it.checkReject?' mdmmBtnPrimary':'')+'\"'+d+' onclick=\"mdmmItemDecide(event,\\''+k+'\\',\\'REJECT\\')\">'+T.reject+'</button>';return h;}\n")
			.append("window.mdmmShowItem=function(it){var html='<div class=\"mdmmInspHead\"><span class=\"mdmmChip acc\">'+e(it.kindLabel)+'</span><span class=\"mdmmFieldChip dst\">'+e(it.title)+'</span>';if(it.op!=='ENTITY'){html+='<span class=\"mdmmMuted\">'+e(it.entity)+'</span>';}html+='<span class=\"mdmmChip warn\" style=\"margin-left:auto\">'+e(T.toValidate)+'</span></div>';if(it.explain){html+='<p class=\"mdmmInspText\">'+e(it.explain)+'</p>';}if(it.check){html+='<p class=\"mdmmInspText mdmmAttn mdmmCheck\">'+e(it.check)+'</p>';}html+='<p class=\"mdmmInspText\">'+e(it.why.charAt(0).toUpperCase()+it.why.slice(1))+'</p>';if(it.rationale){html+='<p class=\"mdmmInspText mdmmMuted\">'+e(it.rationale)+'</p>';}if(it.confidence){html+='<div class=\"mdmmInspRow\"><span class=\"mdmmMuted\">'+T.confidence+' '+e(it.confidence)+'%</span></div>';}html+='<div class=\"mdmmInspRow\">'+itemButtons(it)+'</div>';byId('mdmmInspector').innerHTML=html;};\n")
			.append("window.mdmmItemDecide=function(ev,key,verb){if(ev&&ev.stopPropagation){ev.stopPropagation();}var li=qbykey(key);var it=li?qdata(li):null;if(!it){return;}window.mdmmSelectedKey=key;byId('mdmmSelected').value=key;if(verb==='SETASIDE'){submitAction('SET_ASIDE_GUARD',it.id,'','');return;}var acc=(verb==='ACCEPT');var op=it.op;if(op==='ENTITY'){submitAction(acc?'ACCEPT_ENTITY':'REJECT_ENTITY',it.id,'','');}else if(op==='ATTRIBUTE'){submitAction(acc?'ACCEPT_ATTRIBUTE':'REJECT_ATTRIBUTE',it.id,'','');}else if(op==='FEED'){if(acc){submitAction('ACCEPT_FEED',it.id,'','');}else{var cf=window.prompt(T.rejectReason);if(cf===null){return;}submitAction('REJECT_FEED',it.id,'',cf);}}else if(op==='CELL'){if(acc){if(it.cellAccept==='ACCEPT_ATTRIBUTE_THEN_MAPPING'){submitAction('ACCEPT_ATTR_THEN_MAPPING',it.attrId,it.id,'');}else{submitAction('ACCEPT_MAPPING',it.id,'','');}}else{var cm=window.prompt(T.rejectReason);if(cm===null){return;}submitAction('REJECT_MAPPING',it.id,'',cm);}}else{submitAction(acc?'ACCEPT_GUARD':'REJECT_GUARD',it.id,it.nature,'');}};\n")
			.append("var origSelect=window.mdmmSelect;window.mdmmSelect=function(b){origSelect(b);hiliteKey('');refreshBar();};\n")
			.append("// codes transcoding lot H3 (2026-10-17): the code tables of the selected mapping (TRANSCODE_REFERENCE steps with a table of their own), a two-column table with Accept / Edit / Reject; added as a wrapper so the cell inspector above stays untouched\n")
			.append("function codeBlock(btn){var raw=btn.getAttribute('data-cell');if(!raw){return;}var d=null;try{d=JSON.parse(raw);}catch(x){return;}var ls=d.codeLists||[];if(!ls.length){return;}var insp=byId('mdmmInspector');if(!insp){return;}var h='';for(var i=0;i<ls.length;i++){var l=ls[i];h+='<div class=\"mdmmCodes\" data-step=\"'+e(l.id)+'\"><div class=\"mdmmInspRow\"><b>'+e(T.codesTitle)+'</b><span class=\"mdmmChip '+toneOf(l.status)+'\">'+e(l.statusLabel)+'</span>';if(l.confidence!==null&&l.confidence!==undefined){h+='<span class=\"mdmmMuted\">'+e(T.confidence)+' '+e(l.confidence)+'%</span>';}h+='</div><p class=\"mdmmInspText mdmmMuted\">'+e(T.codesTargets.replace('{0}',l.targets.join(', ')))+(l.basedOn?' ('+e(l.basedOn)+')':'')+'</p>';if(l.unresolved>0){h+='<p class=\"mdmmInspText mdmmAttn\">'+e(T.codesIncomplete.replace('{0}',l.unresolved).replace('{1}',l.total))+'</p>';}if(l.blocked&&l.open){h+='<p class=\"mdmmInspText mdmmAttn mdmmCodesFill\">'+e(l.fill)+'</p>';}h+='<table class=\"mdmmCodeTable\"><thead><tr><th>'+e(T.codesFrom)+'</th><th>'+e(T.codesTo)+'</th><th>'+e(T.codesRows)+'</th></tr></thead><tbody>';for(var k=0;k<l.rows.length;k++){var r=l.rows[k];h+='<tr><td>'+e(r.c)+'</td><td class=\"mdmmCodeTo\" data-code=\"'+e(r.c)+'\" data-target=\"'+e(r.t)+'\">'+(r.t?e(r.t):'<span class=\"mdmmMuted\">'+e(T.codesNoTarget)+'</span>')+'</td><td>'+e(r.n)+'</td></tr>';}h+='</tbody></table>';if(l.open){h+='<div class=\"mdmmInspRow mdmmCodeButtons\"><button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\" data-act=\"ACCEPT_CODES\" data-step=\"'+e(l.id)+'\"'+(l.blocked?' disabled=\"disabled\" title=\"'+e(l.fill)+'\"':'')+' onclick=\"mdmmCodesDecide(this)\">'+e(T.accept)+'</button><button type=\"button\" class=\"mdmmBtn\" onclick=\"mdmmCodesEdit(this)\">'+e(T.codesEdit)+'</button><button type=\"button\" class=\"mdmmBtn\" data-act=\"REJECT_CODES\" data-step=\"'+e(l.id)+'\" onclick=\"mdmmCodesDecide(this)\">'+e(T.reject)+'</button></div>';}h+='</div>';}insp.insertAdjacentHTML('beforeend',h);}\n")
			.append("window.mdmmCodesDecide=function(btn){byId('mdmmSelected').value=window.mdmmSelectedKey||'';submitAction(btn.getAttribute('data-act'),btn.getAttribute('data-step'),'','');};\n")
			.append("window.mdmmCodesEdit=function(btn){var box=btn.closest('.mdmmCodes');if(!box){return;}var cells=box.querySelectorAll('td.mdmmCodeTo');for(var i=0;i<cells.length;i++){var inp=document.createElement('input');inp.type='text';inp.className='mdmmCodeInput';inp.maxLength=100;inp.value=cells[i].getAttribute('data-target')||'';cells[i].innerHTML='';cells[i].appendChild(inp);}var row=box.querySelector('.mdmmCodeButtons');if(row){row.innerHTML='<button type=\"button\" class=\"mdmmBtn mdmmBtnPrimary\" data-step=\"'+e(box.getAttribute('data-step'))+'\" onclick=\"mdmmCodesSave(this)\">'+e(T.codesSave)+'</button><button type=\"button\" class=\"mdmmBtn\" onclick=\"mdmmCodesCancel()\">'+e(T.codesCancel)+'</button>';}};\n")
			.append("window.mdmmCodesCancel=function(){var sel=document.querySelector('.mdmmCell.sel');if(sel){window.mdmmSelect(sel);}};\n")
			.append("window.mdmmCodesSave=function(btn){var box=btn.closest('.mdmmCodes');if(!box){return;}var cells=box.querySelectorAll('td.mdmmCodeTo');var o={};for(var i=0;i<cells.length;i++){var inp=cells[i].querySelector('input');o[cells[i].getAttribute('data-code')]=inp?inp.value:'';}byId('mdmmSelected').value=window.mdmmSelectedKey||'';submitAction('SAVE_CODES',btn.getAttribute('data-step'),'',JSON.stringify(o));};\n")
			.append("var codeBase=window.mdmmSelect;window.mdmmSelect=function(b){codeBase(b);codeBlock(b);};\n")
			.append("var origSelectGuard=window.mdmmSelectGuard;window.mdmmSelectGuard=function(b){origSelectGuard(b);hiliteKey('');refreshBar();};\n")
			.append("function selectKey(key,scroll){var k=keySel(key);var tgt=null;var el=document.querySelector('.mdmmCell'+k);if(!el&&key.indexOf('attr|')===0){var dc=document.querySelector('.mdmmCell'+keySel('derived|'+key.substring(5)));if(dc&&/\"computed\":true/.test(dc.getAttribute('data-cell')||'')){window.mdmmSelect(dc);window.mdmmSelectedKey=key;byId('mdmmSelected').value=key;if(scroll){scrollToEl(dc);}refreshBar();return true;}}if(el){window.mdmmSelect(el);tgt=el;}else{var g=document.querySelector('.mdmmGChip'+k);if(g){window.mdmmSelectGuard(g);tgt=g;}else{var li=qbykey(key);var it=li?qdata(li):null;if(!it){return false;}window.mdmmSelectedKey=key;byId('mdmmSelected').value=key;if(it.op==='GUARD'&&it.guard){window.mdmmSelectGuard(fakeBtn(key,JSON.stringify(it.guard)));}else{window.mdmmShowItem(it);}tgt=hiliteKey(key);}}if(scroll){scrollToEl(tgt);}refreshBar();return true;}\n")
			.append("function setMode(){var p=byId('mdmmQueue');if(!p){return;}var cb=document.querySelector('#mdmmQueue .mdmmQAll input');byId('mdmmQueueMode').value=p.classList.contains('mdmmQueueOpen')?((cb&&cb.checked)?'all':'open'):'';}\n")
			.append("function syncGroups(){var p=byId('mdmmQueue');if(!p){return;}var all=p.classList.contains('mdmmQueueAllOn');var gs=p.querySelectorAll('.mdmmQGroup');for(var i=0;i<gs.length;i++){var shown=gs[i].querySelectorAll(all?'.mdmmQItem':'.mdmmQItem:not(.bulk)').length;gs[i].style.display=shown?'':'none';var c=gs[i].querySelector('.mdmmQGroupName .mdmmChip');if(c){c.textContent=String(shown);}}}\n")
			.append("window.mdmmQueueToggle=function(){var p=byId('mdmmQueue');if(!p){return;}p.classList.toggle('mdmmQueueOpen');setMode();var fc=document.querySelector('.mdmmFootCount');if(fc){fc.setAttribute('aria-expanded',p.classList.contains('mdmmQueueOpen')?'true':'false');}};\n")
			.append("window.mdmmQueueAll=function(on){var p=byId('mdmmQueue');if(!p){return;}p.classList.toggle('mdmmQueueAllOn',!!on);setMode();syncGroups();refreshBar();};\n")
			.append("function goItem(li){var it=qdata(li);if(!it){return;}var p=byId('mdmmQueue');if(p){p.classList.remove('mdmmQueueOpen');setMode();}if(it.entityId!==byId('mdmmEntity').value){byId('mdmmEntity').value=it.entityId;byId('mdmmSelected').value=it.key;submitAction('GOTO_ENTITY',it.entityId,'','');return;}selectKey(it.key,true);}\n")
			.append("window.mdmmQueueGo=function(li){goItem(li);};\n")
			.append("window.mdmmQueueStep=function(d){var l=witems();if(!l.length){return;}var i=windex(qkey(byId('mdmmSelected').value));var n=i<0?(d>0?0:l.length-1):i+d;if(n<0||n>=l.length){return;}goItem(l[n]);};\n")
			.append("syncGroups();var sel=byId('mdmmSelected').value;var shown=false;if(sel){shown=selectKey(sel,byId('mdmm').getAttribute('data-scroll')==='1');}if(!shown){var ql=qitems();for(var qi=0;qi<ql.length;qi++){if(ql[qi].getAttribute('data-entity')===byId('mdmmEntity').value){var qd=qdata(ql[qi]);if(qd){selectKey(qd.key,false);}break;}}}refreshBar();var focus=byId('mdmm').getAttribute('data-focus');if(focus){mdmmFocus(focus);mdmmFocus(focus);}\n")
			// UX lot U6: the links of this screen (Back to the wizard, Load the staging, Matrix | Designer) carry the SAME route form as the explorer's and the
			// cockpit's from the moment the page is drawn - ui/custom/<perspective>/data/... inside a perspective, not only rewritten at click time
			.append("try{var pm=/\\/ui\\/custom\\/([^\\/]+)\\//.exec(window.top.location.pathname);if(pm){var ls=document.querySelectorAll('#mdmm a[href*=\"/ui/advanced/data/\"]');for(var li=0;li<ls.length;li++){ls[li].setAttribute('href',ls[li].getAttribute('href').split('/ui/advanced/data/').join('/ui/custom/'+pm[1]+'/data/'));}}}catch(x){}\n")
			// staging-screens lot (2026-10-17): put the page back where it was (twice: now, and once the layout has settled), then make sure the selected item is on screen;
			// after "Build and load" the page leaves for the wizard's step 4 through the back link, whose perspective rewrite is armed above
			.append("var root=byId('mdmm');if(keepTop()>=0){window.mdmmRestoreScroll=function(){scroller().scrollTop=keepTop();};if(window.requestAnimationFrame){window.requestAnimationFrame(window.mdmmRestoreScroll);}else{window.setTimeout(window.mdmmRestoreScroll,0);}}\n")
			.append("if(root.getAttribute('data-goto')==='1'){var bl=byId('mdmmBackLink');if(bl){bl.click();}}\n")
			.append("})();");
		return pOut.toString();
	}

	/** Every string {@link #script} used to hard-code in English, now read from the bundle - {@code mappingMatrix.js.*} keys, delta 5. */
	private static final String[] JS_STRING_KEYS = { "feedRejected", "sample", "origin", "confidence", "acceptEntityFirst", "acceptAttrThenMapping", "accept",
		"reject", "openDesigner", "whenFails", "flagRecord", "stopRow", "suggestedUse", "removeField", "removeDuplicates", "acceptAlternative", "setAside", "toValidate",
		"rejectReason", "queuePos", "queueCount", "codesTitle", "codesFrom", "codesTo", "codesRows", "codesEdit", "codesSave", "codesCancel", "codesTargets", "codesNoTarget",
		"codesIncomplete" };

	private static String jsStringsObject(final Locale pLocale)
	{
		final StringBuilder out = new StringBuilder(512);
		out.append('{');
		for (final String key : MatrixHtml.JS_STRING_KEYS)
		{
			out.append(key).append(':').append(MatrixHtml.js(MatrixMessages.get(pLocale, "mappingMatrix.js." + key))).append(',');
		}
		out.setLength(out.length() - 1);
		out.append('}');
		return out.toString();
	}

	// ----------------------------------------------------------------------------------------------------- helpers

	private static Map<String, MatrixModel.Source> sourcesById(final MatrixModel.Project pProject)
	{
		final Map<String, MatrixModel.Source> out = new java.util.LinkedHashMap<String, MatrixModel.Source>();
		for (final MatrixModel.Source source : pProject.sources)
		{
			out.put(source.id, source);
		}
		return out;
	}

	private static String join(final List<String> pValues)
	{
		return MatrixHtml.join(pValues, ", ");
	}

	private static String join(final List<String> pValues, final String pSeparator)
	{
		final StringBuilder out = new StringBuilder();
		for (final String value : pValues)
		{
			if (out.length() > 0)
			{
				out.append(pSeparator);
			}
			out.append(value);
		}
		return out.toString();
	}

	private static void renderStyle(final StringBuilder pOut)
	{
		pOut.append("<style>")
			// audit F-44: the brand colours follow the EBX perspective (ThemeTokens writes the --ebx-* tokens on :root), the Factory's
			// own colours as the fallback; the same token names as factory.css's .mdmf block. Semantic colours (good/warn/bad) stay.
			.append("#mdmm{--accent:var(--ebx-main,#684bc0);--accent-text:var(--ebx-main-text,#fff);--accent-fg:var(--ebx-main-ink,#684bc0);--accent-faint:color-mix(in srgb,var(--accent) 2%,#fff);")
			.append("--accent-soft:color-mix(in srgb,var(--accent) 9%,#fff);--accent-line:color-mix(in srgb,var(--accent) 24%,#fff);")
			.append("--accent-ring:color-mix(in srgb,var(--accent) 30%,transparent);--accent-ring-strong:color-mix(in srgb,var(--accent) 45%,transparent);")
			.append("--select:var(--ebx-selection,var(--accent));--btn:var(--ebx-button,var(--accent));")
			.append("--btn-text:var(--ebx-button-text,#fff);--head:var(--ebx-header,#172033);--head-text:var(--ebx-header-text,#fff)}")
			// fix/wizard-live-wave1 (2026-09-24, live bug item 4): same bottom clearance as the wizard's own
			// .mdmf-wizard - EBX's fixed "Close" bar otherwise overlaps this screen's own last controls too.
			.append("#mdmm{font-family:Inter,system-ui,sans-serif;color:#172033;padding-bottom:60px}#mdmm path.mdmmKeyLink{stroke:var(--accent-fg)}")
			.append("#mdmm .mdmmMsg{padding:8px 12px;border-radius:8px;background:#ecfdf3;color:#087443;margin-bottom:8px}")
			.append("#mdmm .mdmmMsg.mdmmErr{background:#fef2f2;color:#b42318}")
			// fix/wizard-live-wave1 (2026-09-24, live bug item 2): top-left "Back to the wizard" link
			.append("#mdmm .mdmmBackToWizard{margin-bottom:10px}")
			.append("#mdmm .mdmmBackToWizard a{color:#5f6368;text-decoration:none;font-size:12.5px;font-weight:600}")
			.append("#mdmm .mdmmBackToWizard a:hover{color:var(--accent-fg);text-decoration:underline}")
			.append("#mdmm .mdmmTitle{margin-bottom:10px}#mdmm .mdmmEyebrow{font-size:11px;font-weight:700;text-transform:uppercase;color:#98a2b3}")
			.append("#mdmm h1{margin:4px 0;font-size:20px}#mdmm .mdmmTitle p{margin:0;color:#667085;font-size:13px}")
			// fix/mapping-matrix-live: the container no longer stretches (position:relative alone) - it is
			// pinned to OV_WIDTH inline (render's own style="...width:1180px"), the SAME px unit the SVG's
			// explicit width/height and the cards' own "left:Npx" all share now (class Javadoc).
			// fix/wizard-live-wave1 (task item 13): .mdmmOvWrap is the one that fits the card's own width and
			// scrolls (never .mdmmOv itself, which must keep its full OV_WIDTH so its coordinate system stays
			// in sync with the absolutely-positioned cards inside it - see renderOverview's own comment).
			.append("#mdmm .mdmmOvWrap{overflow-x:auto;overflow-y:hidden;max-width:100%;margin-bottom:10px}")
			.append("#mdmm .mdmmOv{position:relative;border:1px solid #e6eaf0;border-radius:12px;overflow:visible}")
			.append("#mdmm .mdmmOvLabel{position:absolute;left:").append(Math.round(MatrixHtml.OV_X1)).append("px;top:6px;font-size:10px;color:#98a2b3;text-transform:uppercase}")
			.append("#mdmm .mdmmOvSvg{position:absolute;left:0;top:0}")
			.append("#mdmm .mdmmSrcCard{position:absolute;left:10px;width:200px;height:32px;border:1px solid #e6eaf0;border-radius:8px;background:#fff;cursor:pointer;display:flex;align-items:center;gap:6px;padding:0 8px;overflow:hidden}")
			.append("#mdmm .mdmmSrcCard.on{border-color:#172033}#mdmm .mdmmSrcCard.off{opacity:.4}")
			.append("#mdmm .mdmmSrcTag{font-size:9px;font-weight:800;padding:3px 7px;border-radius:6px;color:#fff;flex:none}")
			.append("#mdmm .mdmmSrcTbl{font-size:10px;color:#475467;font-family:monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
			.append("#mdmm .mdmmEntCard{position:absolute;left:920px;width:220px;height:60px;border:1px solid #e6eaf0;border-radius:10px;background:#f8fafc;cursor:pointer;text-align:left;padding:6px 10px}")
			.append("#mdmm .mdmmEntCard.on{background:var(--accent-soft);border-color:var(--select)}")
			.append("#mdmm .mdmmEntName{display:block;font-weight:800}#mdmm .mdmmEntSub{display:block;font-size:11px;color:#667085}")
			// fix/mapping-matrix-live: the curve status chip (MatrixCompute#curveChipPoint/#chipT) - a small
			// pill centered on its own computed (left,top), same absolute-over-the-svg technique as the cards.
			.append("#mdmm .mdmmOvChip{position:absolute;transform:translate(-50%,-50%);max-width:170px;padding:2px 7px;border-radius:8px;font-size:9px;font-weight:700;")
			.append("border:1px solid;background:#fff;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}")
			.append("#mdmm .mdmmOvChip.good{color:#087443;border-color:#b7e3ca;background:#ecfdf3}#mdmm .mdmmOvChip.warn{color:#b54708;border-color:#fedf89;background:#fffaeb}")
			.append("#mdmm .mdmmOvChip.bad{color:#b42318;border-color:#fecaca;background:#fef2f2}#mdmm .mdmmOvChip.faded{opacity:.25}")
			.append("#mdmm .mdmmToolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:8px}")
			.append("#mdmm .mdmmTab{border:1px solid #d0d5dd;background:#fff;border-radius:8px;padding:5px 12px;cursor:pointer}#mdmm .mdmmTab.on{background:var(--accent);color:var(--accent-text);border-color:var(--accent)}")
			.append("#mdmm .mdmmChip{display:inline-flex;font-size:11px;font-weight:700;padding:3px 8px;border-radius:999px;border:1px solid}")
			.append("#mdmm .mdmmChip.good{background:#ecfdf3;color:#087443;border-color:#b7e3ca}#mdmm .mdmmChip.warn{background:#fffaeb;color:#b54708;border-color:#fedf89}")
			.append("#mdmm .mdmmChip.bad{background:#fef2f2;color:#b42318;border-color:#fecaca}#mdmm .mdmmSep{width:6px}#mdmm .mdmmGrow{flex-grow:1}")
			.append("#mdmm .mdmmToggle{border:1px solid #d0d5dd;background:#fff;border-radius:999px;padding:4px 10px;cursor:pointer}#mdmm .mdmmToggle.on{background:#172033;color:#fff}")
			.append("#mdmm .mdmmBtn{border:1px solid #d0d5dd;background:#fff;border-radius:8px;padding:5px 10px;cursor:pointer}")
			.append("#mdmm .mdmmBtnPrimary{background:var(--btn);border-color:var(--btn);color:var(--btn-text)}#mdmm .mdmmBtnXs{padding:3px 6px;font-size:10px}")
			.append("#mdmm .mdmmMatrix{display:grid;gap:6px;margin-bottom:10px}")
			.append("#mdmm .mdmmCorner{font-size:11px;color:#98a2b3}")
			.append("#mdmm .mdmmHead{display:flex;flex-direction:column;gap:4px;padding:6px 8px;border:1px solid #e6eaf0;border-radius:8px}#mdmm .mdmmHead.off{opacity:.35}")
			.append("#mdmm .mdmmTbl{font-family:monospace;font-size:10px;color:#475467}#mdmm .mdmmMode{font-size:9px;color:var(--accent-fg);font-weight:700}")
			.append("#mdmm .mdmmBar{display:flex;height:5px;border-radius:3px;overflow:hidden;background:#eef0f4}#mdmm .mdmmBar i{display:block;height:100%}")
			.append("#mdmm .mdmmBarTxt,#mdmm .mdmmUnused{font-size:9.5px;color:#667085}")
			.append("#mdmm .mdmmFeedComment{font-size:9.5px;color:#b42318;font-style:italic;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
			.append("#mdmm .mdmmHeadActions,#mdmm .mdmmAttrActions{display:flex;gap:4px}")
			.append("#mdmm .mdmmAttr{display:flex;flex-direction:column;justify-content:center;gap:3px;padding:4px 8px;border:1px solid #e6eaf0;border-radius:8px}")
			.append("#mdmm .mdmmAttrTop{display:flex;align-items:center;gap:6px}#mdmm .mdmmAttrName{font-weight:700}")
			.append("#mdmm .mdmmTypeChip{font-size:8.5px;background:#eef0f4;color:#667085;padding:1px 5px;border-radius:5px}")
			.append("#mdmm .mdmmFlagChip{font-size:8.5px;background:#f2f4f7;color:#667085;padding:1px 6px;border-radius:8px}#mdmm .mdmmFlagChip.req{background:#fff4ed;color:#b93815}")
			.append("#mdmm .mdmmCov{font-size:9.5px;color:#98a2b3}")
			.append("#mdmm .mdmmCell{display:flex;flex-direction:column;justify-content:center;align-items:flex-start;gap:2px;padding:4px 8px;border-radius:8px;border:1px solid;min-height:48px;cursor:pointer;background:#fff;text-align:left}")
			.append("#mdmm .mdmmCell .c1{font-family:monospace;font-size:10.5px;font-weight:700}#mdmm .mdmmCell .c2{font-size:9.5px;color:#475467}")
			.append("#mdmm .mdmmCell .c3{font-size:9px;color:var(--accent-fg);font-family:monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:100%}")
			.append("#mdmm .mdmmCell.good{background:#ecfdf3;border-color:#b7e3ca;color:#087443}")
			// live fix (bug 5): plain "to validate" tone, neutral white/grey - see toneClass()'s own comment
			// for why this is now separate from .attn instead of sharing .warn's amber-dashed look.
			.append("#mdmm .mdmmCell.prop{background:#fff;border-color:#d0d5dd;color:#344054}")
			.append("#mdmm .mdmmCell.warn{background:#fffaeb;border-color:#f5c451;border-style:dashed;color:#b54708}")
			.append("#mdmm .mdmmCell.bad{background:#fef2f2;border-color:#fecaca;color:#b42318}#mdmm .mdmmCell.bad .c1{text-decoration:line-through}")
			.append("#mdmm .mdmmCell.link{background:var(--accent-soft);border-color:var(--accent-line);color:var(--accent-fg)}")
			.append("#mdmm .mdmmCell.derived{border-style:dashed;justify-content:center;align-items:center;text-align:center}#mdmm .mdmmCell.derived .c1{font-family:inherit}")
			.append("#mdmm .mdmmFlagChip.derived{background:var(--accent-soft);color:var(--accent-fg)}")
			.append("#mdmm .mdmmCell.none{background:transparent;border:1px dashed #e0e4ea;color:#b0b7c3}")
			.append("#mdmm .mdmmCell.missing{background:#fff;border:1.5px dashed #f04438;color:#b42318}")
			.append("#mdmm .mdmmCell.sel{box-shadow:0 0 0 3px var(--accent-ring);border-color:var(--select)}#mdmm .mdmmCell.dim{opacity:.3}")
			.append("#mdmm.mdmmOnlyTodo .mdmmCell:not(.todo):not(.sel){opacity:.25}")
			// matrix review queue lot: "Only what's left to do" now HIDES a field row with nothing left (the wrapper is display:contents
			// so its header, cells and guard cell still sit in the grid), and the pinned dock / list panel / counters.
			.append("#mdmm .mdmmRow{display:contents}#mdmm.mdmmOnlyTodo .mdmmRow.mdmmRowDone{display:none}")
			.append("#mdmm .mdmmTabCount{display:inline-block;min-width:16px;padding:0 5px;margin-left:4px;border-radius:999px;background:#f5b400;color:#172033;font-size:10px;font-weight:800;text-align:center}")
			.append("#mdmm .mdmmTab.on .mdmmTabCount{background:var(--accent-text);color:var(--accent-fg)}")
			.append("#mdmm .mdmmPick{box-shadow:0 0 0 3px var(--accent-ring);border-color:var(--accent-fg)}#mdmm .mdmmTab.mdmmPick{box-shadow:0 0 0 3px var(--accent-ring-strong)}")
			// the dock sticks to the bottom of the scrolling pane (60px clear of EBX's fixed Close bar, the same clearance as #mdmm's own padding-bottom);
			// no ancestor between it and the pane may set overflow, or sticky stops working - #mdmm sets none.
			.append("#mdmm .mdmmDock{position:sticky;bottom:60px;z-index:30;margin-top:10px;padding-top:6px;background:#fff;box-shadow:0 -8px 14px -10px rgba(16,24,40,.35)}")
			.append("#mdmm .mdmmDock .mdmmInspector{max-height:42vh;overflow:auto;padding:8px 12px;gap:6px}#mdmm .mdmmDock .mdmmInspector>.mdmmInspRow:last-child{position:sticky;bottom:-8px;background:#fff;padding:6px 0}#mdmm .mdmmDock .mdmmFooter{margin-top:6px;flex-wrap:wrap;padding:8px 14px}")
			.append("#mdmm .mdmmQBar{display:flex;align-items:center;gap:8px;margin-bottom:6px}#mdmm .mdmmQPos{font-size:11.5px;font-weight:700;color:#475467}")
			.append("#mdmm .mdmmQBar .mdmmBtn:disabled{opacity:.4;cursor:default}")
			// UX lot U6: a button and the line saying what it covers, stacked; the optional-suggestions fold of the panel; the secondary per-link action
			.append("#mdmm .mdmmScope{display:inline-flex;flex-direction:column;align-items:flex-end;gap:2px;max-width:340px}#mdmm .mdmmScopeNote{font-size:10px;line-height:1.25;color:#667085;text-align:right}")
			.append("#mdmm .mdmmFooter .mdmmScopeNote{color:inherit;opacity:.8}#mdmm .mdmmBtnLink{background:none;border-color:transparent;color:#475467;text-decoration:underline;padding:2px 4px}")
			.append("#mdmm .mdmmFootOpt{background:none;border:0;color:inherit;font:inherit;font-size:11.5px;opacity:.85;cursor:pointer;padding:0 0 0 8px;text-decoration:underline}")
			.append("#mdmm .mdmmQOpt{margin-top:10px;padding-top:8px;border-top:1px solid #e4e7ec}#mdmm .mdmmQOptGroup{margin:4px 0;border:1px solid #e4e7ec;border-radius:8px;padding:4px 8px;background:#fff}")
			.append("#mdmm .mdmmQOptGroup>summary{cursor:pointer;display:flex;align-items:center;gap:8px;flex-wrap:wrap;font-size:12px}#mdmm .mdmmQOptGroup>summary .mdmmQActions{margin-left:auto}")
			.append("#mdmm .mdmmSItem{display:flex;align-items:center;gap:8px;padding:5px 4px;border-top:1px solid #f2f4f7;font-size:12px;cursor:pointer}#mdmm .mdmmSItem .mdmmQMain{flex:1}#mdmm .mdmmSItem:hover{background:#f8fafc}")
			.append("#mdmm .mdmmFootCount{background:none;border:0;border-bottom:1px dashed currentColor;color:inherit;font:inherit;font-weight:800;cursor:pointer;padding:0}")
			.append("#mdmm .mdmmFootHint{font-size:11.5px;opacity:.85}")
			.append("#mdmm .mdmmQueue{display:none;position:absolute;left:0;right:0;bottom:100%;max-height:46vh;overflow:auto;margin-bottom:6px;padding:10px 12px;background:#fff;border:1px solid #d0d5dd;border-radius:12px;box-shadow:0 10px 24px rgba(16,24,40,.22)}")
			.append("#mdmm .mdmmQueue.mdmmQueueOpen{display:block}")
			.append("#mdmm .mdmmQHead{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:4px}#mdmm .mdmmQAll{font-size:11.5px;color:#475467;flex-grow:1}#mdmm .mdmmQClose{margin-left:auto}")
			.append("#mdmm .mdmmQGroupName{margin-top:8px;font-weight:800;font-size:12.5px}")
			.append("#mdmm .mdmmQItem{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:4px;padding:5px 8px;border:1px solid #eef0f4;border-radius:8px;cursor:pointer;font-size:12px}")
			.append("#mdmm .mdmmQItem:hover{background:#f8fafc;border-color:var(--accent-line)}#mdmm .mdmmQueue:not(.mdmmQueueAllOn) .mdmmQItem.bulk{display:none}")
			.append("#mdmm .mdmmQMain{flex:1 1 220px;min-width:0}#mdmm .mdmmQWhy{display:block;color:#667085;font-size:11px}#mdmm .mdmmQCheck{display:block;color:#93370d;font-size:11.5px;font-weight:600}")
			.append("#mdmm .mdmmQActions{display:flex;gap:4px;margin-left:auto}")
			.append("#mdmm .mdmmInspector{border:1px solid #e6eaf0;border-radius:12px;padding:12px 16px;display:flex;flex-direction:column;gap:8px}")
			.append("#mdmm .mdmmInspHead{display:flex;align-items:center;gap:10px;flex-wrap:wrap}")
			.append("#mdmm .mdmmFieldChip{border:1px solid #e6eaf0;border-radius:9px;padding:5px 10px;font-weight:700;font-size:12px}")
			.append("#mdmm .mdmmFieldChip.src{border-color:var(--accent-line);background:var(--accent-soft);font-family:monospace}#mdmm .mdmmFieldChip.dst{border-color:#b7e3ca;background:#ecfdf3}")
			.append("#mdmm .mdmmStepChip{display:inline-flex;font-family:monospace;font-size:11px;padding:3px 7px;border-radius:6px;background:#f2f4f7;border:1px solid #e6eaf0;margin-right:4px}")
			.append("#mdmm .mdmmSampleVal{font-family:monospace;font-size:11px;padding:2px 6px;border-radius:6px;background:#f8fafc;border:1px solid #e6eaf0}")
			.append("#mdmm .mdmmInspRow{display:flex;align-items:center;gap:8px;flex-wrap:wrap;font-size:12px}")
			.append("#mdmm .mdmmInspText{margin:0;font-size:12px;color:#475467}#mdmm .mdmmInspText.mdmmReject{color:#b42318;font-style:italic}")
			.append("#mdmm .mdmmInspText.mdmmAttn{color:#93370d;background:#fffaeb;border:1px solid #fedf89;border-radius:8px;padding:6px 10px}")
			// codes transcoding lot H3 (2026-10-17): the two-column code table of the inspector
			.append("#mdmm .mdmmCodes{margin-top:10px;padding-top:8px;border-top:1px solid #eaecf0}#mdmm .mdmmCodeTable{border-collapse:collapse;width:100%;font-size:12px;margin:6px 0}")
			.append("#mdmm .mdmmCodeTable th{text-align:left;color:#667085;font-weight:600;padding:3px 8px;border-bottom:1px solid #eaecf0}#mdmm .mdmmCodeTable td{padding:3px 8px;border-bottom:1px solid #f2f4f7}")
			.append("#mdmm .mdmmCodeTable td:last-child{text-align:right;color:#667085}#mdmm .mdmmCodeInput{width:100%;box-sizing:border-box;font-size:12px;padding:2px 6px}")
			.append("#mdmm .mdmmInspRow.mdmmFix{font-size:12.5px;color:#172033}")
			.append("#mdmm .mdmmMuted{color:#98a2b3;font-size:11px}")
			// staging-proposal matrix lot: attention tone (delta 2), the "Rules and checks" column (delta 1),
			// the guidance banner and footer bar (delta 5), the mode switch (delta 6).
			.append("#mdmm .mdmmCell.attn{background:#fffaeb;border-color:#f5c451;border-style:dashed;color:#93370d}")
			.append("#mdmm .mdmmFlagChip.attn{background:#fffaeb;color:#93370d}")
			.append("#mdmm .mdmmChip.acc{background:var(--accent-soft);color:var(--accent-fg);border-color:var(--accent-line)}")
			.append("#mdmm .mdmmGuardHead{display:flex;flex-direction:column;justify-content:flex-end;gap:4px;padding:6px 8px;background:var(--accent-faint);border:1px solid var(--accent-line);border-radius:8px;font-size:10px;color:var(--accent-fg);font-weight:700}")
			.append("#mdmm .mdmmNewTag{align-self:flex-start;font-size:8px;font-weight:800;letter-spacing:.05em;padding:1px 6px;border-radius:5px;background:var(--accent);color:var(--accent-text)}")
			.append("#mdmm .mdmmGuardChips{display:flex;flex-wrap:wrap;gap:4px}")
			.append("#mdmm .mdmmGuards{display:flex;flex-wrap:wrap;align-content:center;gap:4px;padding:4px 8px;border:1px dashed #e0e4ea;border-radius:8px;min-height:48px}")
			.append("#mdmm .mdmmGuardNone{font-size:10px;color:#b0b7c3}")
			.append("#mdmm .mdmmGChip{display:inline-flex;align-items:center;gap:4px;font-size:10px;font-weight:700;padding:3px 8px;border-radius:999px;border:1px solid;cursor:pointer;background:#fff;white-space:nowrap}")
			.append("#mdmm .mdmmGChip.rule{background:var(--accent-soft);color:var(--accent-fg);border-color:var(--accent-line)}#mdmm .mdmmGChip.check{background:#f2f4f7;color:#344054;border-color:#d0d5dd}")
			.append("#mdmm .mdmmGChip.good{background:#ecfdf3;color:#087443;border-color:#b7e3ca}#mdmm .mdmmGChip.sel{box-shadow:0 0 0 3px var(--accent-ring)}")
			.append("#mdmm .mdmmGuide{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-bottom:10px}")
			.append("#mdmm .mdmmGuideCard{background:#fff;border:1px solid #e6eaf0;border-radius:12px;padding:8px 12px;display:flex;gap:10px;align-items:center;font-size:11.5px}")
			.append("#mdmm .mdmmSummary{font-size:12.5px;color:#475467;margin-bottom:6px}#mdmm .mdmmGlossary{font-size:10.5px;color:#98a2b3;margin-bottom:10px}")
			.append("#mdmm .mdmmSeg{display:inline-flex;background:#eef0f4;border-radius:10px;padding:3px;gap:2px}")
			.append("#mdmm .mdmmSeg a,#mdmm .mdmmSeg span{display:inline-flex;align-items:center;padding:5px 12px;border-radius:8px;font-size:11.5px;font-weight:700;color:#475467;text-decoration:none}")
			.append("#mdmm .mdmmSeg a.on,#mdmm .mdmmSeg span.on{background:#fff;color:var(--accent-fg)}")
			.append("#mdmm .mdmmFooter{margin-top:10px;padding:12px 16px;display:flex;align-items:center;gap:12px;background:var(--head);border-radius:14px;color:var(--head-text)}")
			.append("#mdmm .mdmmFooter .mdmmFootTxt{flex-grow:1;font-size:12.5px}#mdmm .mdmmFooter .mdmmBtn.mdmmBtnPrimary{border-color:var(--btn)}")
			.append("#mdmm a.mdmmBtn{display:inline-flex;align-items:center;text-decoration:none}#mdmm .mdmmFooter a.mdmmBtn{color:#344054}#mdmm .mdmmFooter a.mdmmBtn[aria-disabled=true]{opacity:.6;cursor:not-allowed}")
			.append("#mdmm a.mdmmBtn[aria-disabled=\"true\"]{opacity:.45;pointer-events:none}")
			.append("</style>");
	}

	// security review 2026-09-24, finding I3: aligned on ebx.ui.FactoryHtml#esc (escapes the single quote too,
	// not just & < > ").
	private static String esc(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}

	/** A JS string literal, single-quoted - used inside inline {@code onclick} attributes. Callers that place
	 * this directly inside a double-quoted HTML attribute (every {@code onclick="...('...')"} in this class)
	 * MUST wrap the result with {@link #jsAttr} instead of calling this directly - {@code js} alone only
	 * makes the value safe as a JS string, not as HTML markup; a value carrying a {@code "} would otherwise
	 * close the attribute early (security review 2026-09-24, finding I3). */
	private static String js(final String pValue)
	{
		return "'" + com.on.ebx.factory.ebx.ui.FactoryHtml.jsStringBody(pValue) + "'";
	}

	/**
	 * {@link #js}, then HTML-escapes only {@code &} and {@code "} (not the full {@link #esc}) - a value safe
	 * to drop straight inside a double-quoted {@code onclick="...('HERE')"} attribute built by plain Java
	 * string concatenation (never JS string concatenation - see {@link #script} for that case, which needs
	 * the JS-side equivalent, {@code eAttr}, instead). {@code "} is the one character that can actually break
	 * out of this attribute (the delimiter); {@code &} is neutralized so a value can never accidentally form
	 * a stray entity reference. {@code js} already turned every {@code '} into {@code \'} (still a single
	 * quote followed by a backslash, never the attribute's own delimiter), so escaping it again to {@code
	 * &#39;} is unnecessary here and would just make the rendered markup diverge from what a reader/test
	 * expects to see for an ordinary id with no special characters. Security review 2026-09-24, finding I3.
	 */
	private static String jsAttr(final String pValue)
	{
		return MatrixHtml.js(pValue).replace("&", "&amp;").replace("\"", "&quot;");
	}

	/** A JSON string literal (quotes, backslashes and control characters escaped) - for the small maps written into data attributes. */
	private static String jsonQuote(final String pValue)
	{
		final StringBuilder out = new StringBuilder("\"");
		for (final char c : (pValue == null ? "" : pValue).toCharArray())
		{
			if (c == '"' || c == '\\')
			{
				out.append('\\').append(c);
			}
			else if (c < 0x20)
			{
				out.append(String.format("\\u%04x", Integer.valueOf(c)));
			}
			else
			{
				out.append(c);
			}
		}
		return out.append('"').toString();
	}
}
