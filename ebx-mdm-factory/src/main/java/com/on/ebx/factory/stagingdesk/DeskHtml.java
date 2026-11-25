package com.on.ebx.factory.stagingdesk;

import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.ebx.ui.FactoryHtml;
import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.mappingmatrix.MatrixHtml;
import com.on.ebx.factory.mappingmatrix.MatrixMessages;
import com.on.ebx.factory.review.ReviewLine;

/**
 * The Validation desk page (docs/STAGING-DESK.md), plain HTML string building like {@code MatrixHtml}: a header with the four views of step 4, the groups in a left
 * column, the selected group in the middle, and the empty state when nothing is left. No {@code <script>} tag - the interaction script is {@link #script}, which the
 * service feeds to {@code addJS_cr} (CLAUDE.md &sect;9). Every button is a real {@code <button type="button">} that fills the hidden fields of the page
 * ({@code mdmmAction}/{@code mdmmRowId}/{@code mdmmRowId2}/{@code mdmmComment}, the matrix' own names, so the matrix' own {@code handle} reads them) and clicks the
 * service's one EBX submit button ({@code mdskSubmit}). The hidden group field {@code mdskGroup} is set client-side BEFORE the submit: {@code setupObjectContext}
 * runs after the event on a postback (docs/MAPPING-MATRIX.md sec 13).
 * <p>
 * The centre of an OBJECT group is {@link #renderObjectCentre}, on its own on purpose: lot L2 replaces its list with a preview of real landing rows.
 */
public final class DeskHtml
{
	/** The id of the EBX submit button the service creates - the page clicks it after filling the hidden fields. */
	public static final String SUBMIT_ID = "mdskSubmit";

	private DeskHtml()
	{
	}

	/** Everything about the current rendering that is not the data: posted back through hidden fields or computed by the service from the session. */
	public static final class State
	{
		public Locale locale = Locale.ENGLISH;
		public String selectedKey = "";
		public String message;
		public boolean messageIsError;
		/** True when the session may not decide (no write access): the buttons are drawn disabled and the service refuses a posted decision anyway. */
		public boolean readOnly;
		public int bulkThreshold = MatrixCompute.DEFAULT_BULK_THRESHOLD;
		/** Step 4 of the wizard: the wizard card view, and the place of the build button. */
		public String wizardUrl = "";
		public String matrixUrl = "";
		public String designerUrl = "";
		/** The scroll offset of the page's scrolling container as it was before the submit; negative = none. */
		public int scrollTop = -1;
		/** Lot L2: the rows of the object of the selected group as they will be loaded; null = the L1 list alone (the preview could not be built). */
		public DeskPreview preview;
		/** Lot L5: the suggestions that wait for an input no field holds and the computed fields proposed for them; null = no card. */
		public DeskInput input;
		/** Lot L3: the "points to check" callouts of the selected object ({@link DeskPointsHtml#collect}); empty when none. */
		public List<DeskPointsHtml.Point> points = new java.util.ArrayList<DeskPointsHtml.Point>();
		/** Lot L4: the checks of the selected group that can be looked at (rule id to facts), and the one the person opened ("" = the list). */
		public java.util.Map<String, DeskCheck> checks = new java.util.HashMap<String, DeskCheck>();
		public String openCheckId = "";
	}

	/** The whole page. */
	public static String render(final DeskModel pModel, final State pState)
	{
		final Locale locale = pState.locale == null ? Locale.ENGLISH : pState.locale;
		final StringBuilder out = new StringBuilder(16384);
		out.append("<div id=\"mdsk\" class=\"mdskRoot\" data-scrolltop=\"").append(pState.scrollTop).append("\">");
		DeskHtml.renderStyle(out);
		DeskHtml.renderHeader(out, pModel, pState, locale);
		if (pState.message != null)
		{
			out.append("<div class=\"mdskMsg").append(pState.messageIsError ? " mdskErr" : "").append("\" role=\"status\">").append(DeskHtml.esc(pState.message)).append("</div>");
		}
		if (pState.readOnly)
		{
			out.append("<div class=\"mdskMsg mdskErr\">").append(DeskHtml.esc(DeskMessages.get(locale, "desk.readOnly"))).append("</div>");
		}
		if (pModel.isDone())
		{
			DeskHtml.renderDone(out, pModel, pState, locale);
		}
		else
		{
			final DeskModel.Group selected = pModel.group(pState.selectedKey) == null ? pModel.groups.get(0) : pModel.group(pState.selectedKey);
			out.append("<div class=\"mdskCols\">");
			DeskHtml.renderSide(out, pModel, selected, locale);
			out.append("<main class=\"mdskMain\">");
			if (DeskModel.OBJECT.equals(selected.kind))
			{
				DeskHtml.renderObjectCentre(out, pModel, selected, pState, locale);
			}
			else if (DeskModel.CHECKS.equals(selected.kind))
			{
				DeskHtml.renderChecksCentre(out, selected, pState, locale);
			}
			else
			{
				DeskHtml.renderCannotRunCentre(out, selected, pState, locale);
			}
			out.append("</main></div>");
		}
		DeskHtml.renderHiddenFields(out, pModel, pState);
		out.append("</div>");
		return out.toString();
	}

	// ------------------------------------------------------------------------------------------------------------- header

	private static void renderHeader(final StringBuilder pOut, final DeskModel pModel, final State pState, final Locale pLocale)
	{
		pOut.append("<header class=\"mdskHead\"><div class=\"mdskCrumb\"><b>").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.brand"))).append("</b><span class=\"mdskDim\">/</span><span>")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.project", pModel.projectLabel))).append("</span><span class=\"mdskChip acc\">")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.step"))).append("</span></div>");
		pOut.append("<nav class=\"mdskNav\" aria-label=\"").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.views.label"))).append("\">");
		DeskHtml.viewLink(pOut, pState.wizardUrl, DeskMessages.get(pLocale, "desk.view.wizard"), false);
		pOut.append("<span class=\"mdskBtn mdskBtnPrimary mdskBtnSm\" aria-current=\"page\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.view.desk"))).append("</span>");
		DeskHtml.viewLink(pOut, pState.matrixUrl, DeskMessages.get(pLocale, "desk.view.matrix"), false);
		DeskHtml.viewLink(pOut, pState.designerUrl, DeskMessages.get(pLocale, "desk.view.designer"), false);
		pOut.append("</nav></header>");
	}

	/** A link to another view of step 4 (class {@code mdmf-matrix-designer-link}: a voluntary full-page departure, no "leave site" prompt, rewritten into the perspective route when clicked); a plain label when there is nothing to open. */
	private static void viewLink(final StringBuilder pOut, final String pUrl, final String pLabel, final boolean pCurrent)
	{
		if (pUrl == null || pUrl.isEmpty())
		{
			pOut.append("<span class=\"mdskBtn mdskBtnQuiet mdskBtnSm mdskOff\">").append(DeskHtml.esc(pLabel)).append("</span>");
			return;
		}
		pOut.append("<a class=\"mdskBtn mdskBtnQuiet mdskBtnSm mdmf-matrix-designer-link\" target=\"_top\" href=\"").append(DeskHtml.esc(pUrl)).append("\">").append(DeskHtml.esc(pLabel))
			.append("</a>");
	}

	// ---------------------------------------------------------------------------------------------------------- left column

	private static void renderSide(final StringBuilder pOut, final DeskModel pModel, final DeskModel.Group pSelected, final Locale pLocale)
	{
		pOut.append("<aside class=\"mdskSide\">");
		// the number
		final int open = pModel.openProposals();
		final int total = Math.max(1, pModel.totalProposals);
		final int percent = Math.min(100, Math.round(100f * pModel.decidedProposals / total));
		pOut.append("<section class=\"mdskCard\"><div class=\"mdskBig\"><span id=\"mdskDecisions\">").append(pModel.decisions()).append("</span><span class=\"mdskEyebrow\">")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.decisions"))).append("</span></div><p class=\"mdskLead\">")
			.append(DeskHtml.esc(DeskMessages.plural(pLocale, open, "desk.lead.one", "desk.lead.other", Integer.valueOf(pModel.decisions())))).append("</p>")
			.append("<div class=\"mdskBar\"><i style=\"width:").append(percent).append("%\"></i></div><span class=\"mdskDim mdskSmall\">")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.progress", Integer.valueOf(pModel.decidedProposals), Integer.valueOf(pModel.totalProposals)))).append("</span></section>");
		// the groups
		pOut.append("<section class=\"mdskCard mdskGroups\">");
		DeskHtml.renderGroupSection(pOut, pModel, pSelected, DeskModel.OBJECT, "desk.section.accept", pLocale);
		DeskHtml.renderGroupSection(pOut, pModel, pSelected, DeskModel.CHECKS, "desk.section.checks", pLocale);
		DeskHtml.renderGroupSection(pOut, pModel, pSelected, DeskModel.CANNOT_RUN, "desk.section.cannotRun", pLocale);
		pOut.append("</section>");
		// nothing to decide
		if (!pModel.follows.isEmpty())
		{
			pOut.append("<section class=\"mdskCard mdskDashed\"><span class=\"mdskEyebrow\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.follow.title"))).append("</span><ul class=\"mdskList\">");
			for (final DeskModel.Follow follow : pModel.follows)
			{
				pOut.append("<li><code>").append(DeskHtml.esc(follow.entityName + "." + follow.name)).append("</code> <span class=\"mdskDim\">")
					.append(DeskHtml.esc(DeskMessages.get(pLocale, DeskModel.FOLLOW_KEY.equals(follow.kind) ? "desk.follow.key" : "desk.follow.parent"))).append("</span></li>");
			}
			pOut.append("</ul></section>");
		}
		// optional look-ups that could run: folded, never counted
		if (!pModel.optional.isEmpty())
		{
			int count = 0;
			for (final DeskModel.OptionalLine line : pModel.optional)
			{
				count += line.count;
			}
			pOut.append("<details class=\"mdskCard mdskOptional\"><summary>").append(DeskHtml.esc(DeskMessages.plural(pLocale, count, "desk.optional.title.one", "desk.optional.title.other")))
				.append("</summary><p class=\"mdskDim mdskSmall\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.optional.lead"))).append("</p><ul class=\"mdskList\">");
			for (final DeskModel.OptionalLine line : pModel.optional)
			{
				pOut.append("<li><code>").append(DeskHtml.esc(line.entityName + (line.fieldName.isEmpty() ? "" : "." + line.fieldName))).append("</code> <span class=\"mdskDim\">")
					.append(DeskHtml.esc(DeskMessages.count(pLocale, line.count, "suggestion"))).append("</span></li>");
			}
			pOut.append("</ul></details>");
		}
		pOut.append("</aside>");
	}

	private static void renderGroupSection(final StringBuilder pOut, final DeskModel pModel, final DeskModel.Group pSelected, final String pKind, final String pTitleKey,
		final Locale pLocale)
	{
		boolean any = false;
		for (final DeskModel.Group group : pModel.groups)
		{
			if (!group.kind.equals(pKind))
			{
				continue;
			}
			if (!any)
			{
				pOut.append("<span class=\"mdskEyebrow mdskSection\">").append(DeskHtml.esc(DeskMessages.get(pLocale, pTitleKey))).append("</span>");
				any = true;
			}
			pOut.append("<button type=\"button\" class=\"mdskQ").append(group == pSelected ? " on" : "").append("\" data-group=\"").append(DeskHtml.esc(group.key))
				.append("\" onclick=\"mdskGo(").append(DeskHtml.jsAttr(group.key)).append(")\"><span class=\"mdskQTitle\">").append(DeskHtml.esc(DeskHtml.groupTitle(group, pLocale)))
				.append("</span><span class=\"mdskQSub\">").append(DeskHtml.esc(DeskHtml.groupSub(group, pLocale))).append("</span><span class=\"mdskChips\">");
			if (!DeskModel.CANNOT_RUN.equals(group.kind))
			{
				pOut.append("<span class=\"mdskChip mut\">").append(DeskHtml.esc(DeskMessages.count(pLocale, group.proposals(), "proposal"))).append("</span>");
			}
			if (group.flaggedFields > 0)
			{
				pOut.append("<span class=\"mdskChip warn\">").append(DeskHtml.esc(DeskMessages.plural(pLocale, group.flaggedFields, "desk.chip.eye.one", "desk.chip.eye.other"))).append("</span>");
			}
			pOut.append("</span></button>");
		}
	}

	static String groupTitle(final DeskModel.Group pGroup, final Locale pLocale)
	{
		if (DeskModel.OBJECT.equals(pGroup.kind))
		{
			return DeskMessages.get(pLocale, "desk.nav.object.title", pGroup.entityName);
		}
		if (DeskModel.CHECKS.equals(pGroup.kind))
		{
			return DeskMessages.get(pLocale, "desk.nav.checks.title", pGroup.entityName, DeskMessages.count(pLocale, pGroup.proposals(), "check"));
		}
		return DeskMessages.count(pLocale, pGroup.proposals(), "service");
	}

	static String groupSub(final DeskModel.Group pGroup, final Locale pLocale)
	{
		if (DeskModel.OBJECT.equals(pGroup.kind))
		{
			final StringBuilder sub = new StringBuilder();
			if (pGroup.objects > 0)
			{
				sub.append(DeskMessages.get(pLocale, "desk.nav.object.itself")).append(", ");
			}
			sub.append(DeskMessages.count(pLocale, pGroup.fields, "field")).append(", ").append(DeskMessages.count(pLocale, pGroup.links, "link"));
			if (pGroup.sources > 0)
			{
				sub.append(' ').append(DeskMessages.get(pLocale, "desk.nav.object.from", DeskMessages.count(pLocale, pGroup.sources, "source")));
			}
			return sub.toString();
		}
		if (DeskModel.CHECKS.equals(pGroup.kind))
		{
			return DeskMessages.get(pLocale, "desk.nav.checks.sub");
		}
		final int noSecret = pGroup.servicesWithoutSecret();
		final int needsInput = pGroup.servicesNeedingInput();
		final StringBuilder sub = new StringBuilder();
		if (needsInput > 0)
		{
			sub.append(DeskMessages.plural(pLocale, needsInput, "desk.nav.run.needsInput.one", "desk.nav.run.needsInput.other")).append(' ');
		}
		if (noSecret > 0)
		{
			sub.append(DeskMessages.plural(pLocale, noSecret, "desk.nav.run.noSecret.one", "desk.nav.run.noSecret.other"));
		}
		return sub.toString().trim();
	}

	// --------------------------------------------------------------------------------------------------------- the centre

	/**
	 * The centre of a business object's group: what is proposed for it, one line each, with the points to check first and the bulk "Accept &lt;object&gt;".
	 * On its own so lot L2 can replace the list with a preview of real landing rows without touching the rest of the page.
	 */
	public static void renderObjectCentre(final StringBuilder pOut, final DeskModel pModel, final DeskModel.Group pGroup, final State pState, final Locale pLocale)
	{
		pOut.append("<section class=\"mdskCard mdskHero\"><div class=\"mdskHeroTop\"><div><span class=\"mdskEyebrow\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.section.accept")))
			.append("</span><h1>").append(DeskHtml.esc(pState.preview != null ? DeskMessages.get(pLocale, "desk.object.titlePreview", pGroup.entityName) : pGroup.entityName)).append("</h1><p class=\"mdskLead\">")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, pState.preview != null ? "desk.object.leadPreview" : "desk.object.lead", DeskMessages.count(pLocale, pGroup.fields, "field"),
				DeskMessages.count(pLocale, pGroup.links, "link"))))
			.append("</p></div>");
		if (!pState.matrixUrl.isEmpty())
		{
			pOut.append("<a class=\"mdskBtn mdskBtnSm mdmf-matrix-designer-link\" target=\"_top\" href=\"").append(DeskHtml.esc(pState.matrixUrl)).append("\">")
				.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.btn.openMatrix"))).append("</a>");
		}
		pOut.append("</div>");
		// a SAME_CONSTANT flag is shown by its callout (lot L3) when there is one; this list keeps the other flags and disappears when nothing else is in it
		final java.util.Set<String> calloutAttrs = new java.util.HashSet<String>();
		for (final DeskPointsHtml.Point point : pState.points)
		{
			calloutAttrs.add(point.attributeId);
		}
		final List<DeskModel.Line> eye = new java.util.ArrayList<DeskModel.Line>();
		final java.util.Set<String> eyeFields = new java.util.LinkedHashSet<String>();
		for (final DeskModel.Line line : pGroup.lines)
		{
			if (line.flagged() && !(line.sameConstant && calloutAttrs.contains(line.item.attributeId)))
			{
				eye.add(line);
				eyeFields.add(line.item.attributeId.isEmpty() ? line.item.key : line.item.attributeId);
			}
		}
		if (!eye.isEmpty())
		{
			pOut.append("<div class=\"mdskEye\"><span class=\"mdskChip warn\">").append(DeskHtml.esc(DeskMessages.plural(pLocale, eyeFields.size(), "desk.eye.title.one", "desk.eye.title.other")))
				.append("</span><ul class=\"mdskList\">");
			for (final DeskModel.Line line : eye)
			{
				pOut.append("<li><b>").append(DeskHtml.esc(line.item.label)).append("</b> ").append(DeskHtml.esc(MatrixHtml.reasonText(line.item, pState.bulkThreshold, pLocale))).append("</li>");
			}
			pOut.append("</ul></div>");
		}
		pOut.append("</section>");
		// lot L3: one callout per attribute every source of which writes the same constant, above the preview
		DeskPointsHtml.render(pOut, pState.points, pGroup.entityId, pState, pLocale);

		if (pState.preview != null && pState.preview.entityId.equals(pGroup.entityId))
		{
			DeskHtml.renderPreview(pOut, pGroup, pState, pLocale);
			DeskHtml.renderAsShownBar(pOut, pGroup, pState, pLocale);
			// the L1 list stays reachable: one line per proposal, with its own buttons and the confidence bulk of the matrix
			pOut.append("<details class=\"mdskCard mdskDetails\"><summary>").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.details.title", Integer.valueOf(pGroup.proposals()))))
				.append("</summary><div class=\"mdskTableCard\">");
			DeskHtml.renderTable(pOut, pGroup.lines, true, pState, pLocale);
			pOut.append("</div>");
			DeskHtml.renderConfidenceBulk(pOut, pGroup, pState, pLocale);
			pOut.append("</details>");
			return;
		}
		pOut.append("<section class=\"mdskCard mdskTableCard\">");
		DeskHtml.renderTable(pOut, pGroup.lines, true, pState, pLocale);
		pOut.append("</section>");
		DeskHtml.renderConfidenceBulk(pOut, pGroup, pState, pLocale);
	}

	/** The matrix' own bulk of L1: the proposals above the confidence bar, flagged fields left (button {@code BULK_ENTITY}). */
	private static void renderConfidenceBulk(final StringBuilder pOut, final DeskModel.Group pGroup, final State pState, final Locale pLocale)
	{
		final String disabled = pState.readOnly || pGroup.withButton == 0 ? " disabled=\"disabled\"" : "";
		pOut.append("<section class=\"mdskBulk\"><div class=\"mdskBulkText\">")
			.append(DeskHtml.esc(pGroup.withButton == 0 ? DeskMessages.get(pLocale, "desk.bulk.none")
				: DeskMessages.get(pLocale, "desk.bulk.note", Integer.valueOf(pGroup.withButton), Integer.valueOf(pGroup.proposals()), Integer.valueOf(pGroup.proposals() - pGroup.withButton))))
			.append(" <span class=\"mdskBulkHint\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.bulk.rejectHint"))).append("</span></div>")
			.append("<button type=\"button\" id=\"mdskBulkObject\" class=\"mdskBtn mdskBtnPrimary\"").append(disabled).append(" onclick=\"mdskDo('BULK_ENTITY',").append(DeskHtml.jsAttr(pGroup.entityId))
			.append(",'',false)\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.btn.acceptObject", pGroup.entityName))).append("</button></section>");
	}

	// ------------------------------------------------------------------------------------------------ the preview of real rows (L2)

	/** One block per source of the object: a few landing rows and what the loader would make of them (grey = what the source holds, white = what the staging will hold). */
	private static void renderPreview(final StringBuilder pOut, final DeskModel.Group pGroup, final State pState, final Locale pLocale)
	{
		final DeskPreview preview = pState.preview;
		for (final DeskPreview.Block block : preview.blocks)
		{
			pOut.append("<section class=\"mdskCard mdskBlock\" data-feed=\"").append(DeskHtml.esc(block.feedId)).append("\"><div class=\"mdskBlockHead\"><div><strong>").append(DeskHtml.esc(block.sourceName))
				.append("</strong> <span class=\"mdskChip mut\">").append(DeskHtml.esc(block.tableName));
			if (block.rowCount != null)
			{
				pOut.append(" \u00B7 ").append(DeskHtml.esc(DeskMessages.count(pLocale, block.rowCount.longValue(), "row")));
			}
			pOut.append("</span></div><span class=\"mdskDim mdskSmall\">");
			if (block.isFallback())
			{
				pOut.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prev.noRows")));
			}
			else
			{
				final int first = block.offset + 1;
				final int last = block.offset + block.rows.size();
				pOut.append(DeskHtml.esc(block.rowCount == null ? DeskMessages.get(pLocale, "desk.prev.range", Integer.valueOf(first), Integer.valueOf(last))
					: DeskMessages.get(pLocale, "desk.prev.rangeOf", Integer.valueOf(first), Integer.valueOf(last), block.rowCount)));
				if (block.more || block.offset > 0)
				{
					pOut.append(" \u00B7 <button type=\"button\" class=\"mdskLinkBtn\" onclick=\"mdskDo('NEXT_ROWS',").append(DeskHtml.jsAttr(block.feedId)).append(',')
						.append(DeskHtml.jsAttr(String.valueOf(block.more ? block.offset + DeskPreview.ROWS : 0))).append(",false)\">")
						.append(DeskHtml.esc(DeskMessages.get(pLocale, block.more ? "desk.prev.another" : "desk.prev.first", Integer.valueOf(DeskPreview.ROWS)))).append("</button>");
				}
			}
			pOut.append("</span></div>");
			if (block.isFallback())
			{
				// the rows cannot be shown: the proposals of this source as the L1 list, under the reason
				pOut.append("<p class=\"mdskFallback\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prev.fallback", block.fallback))).append("</p><div class=\"mdskTableCard\">");
				final List<DeskModel.Line> lines = new java.util.ArrayList<DeskModel.Line>();
				for (final DeskModel.Line line : pGroup.lines)
				{
					if ((ReviewLine.FEED.equals(line.item.nature) && line.item.id.equals(block.feedId)) || (ReviewLine.MAPPING.equals(line.item.nature) && block.mappingIds.contains(line.item.id)))
					{
						lines.add(line);
					}
				}
				DeskHtml.renderTable(pOut, lines, false, pState, pLocale);
				pOut.append("</div></section>");
				continue;
			}
			pOut.append("<div class=\"mdskScrollX\"><table class=\"mdskPrev\"><thead><tr>");
			if (!block.sourceHeader.isEmpty())
			{
				pOut.append("<th class=\"raw\">").append(DeskHtml.esc(block.sourceHeader)).append("<span class=\"mdskSub\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prev.source"))).append("</span></th>");
			}
			for (final DeskPreview.Column column : block.columns)
			{
				pOut.append("<th>").append(DeskHtml.esc(column.name)).append("<span class=\"mdskSub").append(DeskPreview.DATA.equals(column.kind) && !column.how.isEmpty() ? " mono" : "").append("\">");
				if (DeskPreview.PARENT.equals(column.kind))
				{
					pOut.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prev.byEngine")));
				}
				else if (DeskPreview.COMPUTED.equals(column.kind))
				{
					pOut.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prev.atLoad")));
				}
				else
				{
					pOut.append(DeskHtml.esc(column.how));
				}
				pOut.append("</span></th>");
			}
			pOut.append("</tr></thead><tbody>");
			for (int r = 0; r < block.rows.size(); r++)
			{
				pOut.append("<tr>");
				if (!block.sourceHeader.isEmpty())
				{
					pOut.append("<td class=\"raw\">").append(DeskHtml.esc(block.sourceCells.get(r))).append("</td>");
				}
				for (int c = 0; c < block.columns.size(); c++)
				{
					DeskHtml.renderPreviewCell(pOut, block.columns.get(c), block.rows.get(r).get(c), r == 0, pLocale);
				}
				pOut.append("</tr>");
			}
			pOut.append("</tbody></table></div>");
			for (final DeskPreview.FillLine fill : block.fillLines)
			{
				pOut.append("<p class=\"mdskFill\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prev.fill", Long.valueOf(fill.missing), Long.valueOf(fill.total), String.join(", ", fill.fields))))
					.append("</p>");
			}
			pOut.append("</section>");
		}
		if (preview.blocks.isEmpty())
		{
			pOut.append("<section class=\"mdskCard\"><p class=\"mdskDim\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prev.noSource"))).append("</p></section>");
		}
	}

	private static void renderPreviewCell(final StringBuilder pOut, final DeskPreview.Column pColumn, final DeskPreview.Cell pCell, final boolean pFirstRow, final Locale pLocale)
	{
		final boolean attention = !pColumn.attention.isEmpty();
		pOut.append("<td");
		if (attention || pCell.ruleFails())
		{
			pOut.append(" class=\"").append(attention ? "eye" : "flag").append("\"");
		}
		if (!pCell.note.isEmpty())
		{
			pOut.append(" title=\"").append(DeskHtml.esc(pCell.note)).append("\"");
		}
		pOut.append('>');
		if (DeskPreview.ENGINE.equals(pCell.kind))
		{
			pOut.append("<span class=\"mdskChip acc\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prev.its", pColumn.how))).append("</span>");
		}
		else if (DeskPreview.RUNTIME.equals(pCell.kind))
		{
			pOut.append("<span class=\"mdskDim\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prev.atLoad"))).append("</span>");
		}
		else if (DeskPreview.EMPTY.equals(pCell.kind))
		{
			pOut.append("<span class=\"mdskDim\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prev.empty"))).append("</span>");
		}
		else if (DeskPreview.ERROR.equals(pCell.kind))
		{
			pOut.append("<span class=\"mdskBadTxt\">").append(DeskHtml.esc(pCell.text)).append("</span>");
		}
		else
		{
			pOut.append(DeskHtml.esc(pCell.text));
		}
		if (pCell.ruleFails())
		{
			pOut.append("<span class=\"mdskSub\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "REJECT".equals(pCell.ruleSeverity) ? "desk.prev.ruleReject" : "desk.prev.ruleWarn", pCell.ruleLabel))).append("</span>");
		}
		else if (attention && pFirstRow)
		{
			pOut.append("<span class=\"mdskSub\">").append(DeskHtml.esc(pColumn.attention)).append("</span>");
		}
		pOut.append("</td>");
	}

	/** The dark bar of the centre: what "Accept &lt;object&gt; as shown" takes and leaves, and the menu that rejects a column. */
	private static void renderAsShownBar(final StringBuilder pOut, final DeskModel.Group pGroup, final State pState, final Locale pLocale)
	{
		final DeskPreview preview = pState.preview;
		final String disabled = pState.readOnly || preview.nothingToTake ? " disabled=\"disabled\"" : "";
		final StringBuilder text = new StringBuilder();
		if (preview.nothingToTake)
		{
			text.append(DeskMessages.get(pLocale, "desk.shown.none"));
		}
		else
		{
			final String links = preview.takenLinks == preview.totalLinks ? DeskMessages.count(pLocale, preview.takenLinks, "link")
				: DeskMessages.get(pLocale, "desk.shown.linksOf", Integer.valueOf(preview.takenLinks), Integer.valueOf(preview.totalLinks));
			text.append(DeskMessages.get(pLocale, "desk.shown.takes", DeskMessages.count(pLocale, preview.takenFields, "field"), links));
		}
		pOut.append("<section class=\"mdskBulk\"><div class=\"mdskBulkText\">").append(DeskHtml.esc(text.toString()));
		pOut.append(" <span class=\"mdskBulkHint\">");
		if (preview.held > 0)
		{
			pOut.append(DeskHtml.esc(DeskMessages.plural(pLocale, preview.held, "desk.shown.held.one", "desk.shown.held.other"))).append(' ');
		}
		pOut.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.shown.rejectHint"))).append("</span></div><div class=\"mdskBarBtns\">");
		if (!preview.fields.isEmpty())
		{
			pOut.append("<details class=\"mdskReject\"><summary class=\"mdskBtn mdskBtnGhost\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.shown.rejectColumn"))).append("</summary><div class=\"mdskMenu\">");
			for (final DeskPreview.FieldRef field : preview.fields)
			{
				pOut.append("<button type=\"button\" class=\"mdskMenuItem\"").append(pState.readOnly ? " disabled=\"disabled\"" : "").append(" onclick=\"mdskDo('REJECT_ATTRIBUTE',")
					.append(DeskHtml.jsAttr(field.id)).append(",'',false)\">").append(DeskHtml.esc(field.name)).append("</button>");
			}
			pOut.append("</div></details>");
		}
		pOut.append("<button type=\"button\" id=\"mdskAsShown\" class=\"mdskBtn mdskBtnPrimary\"").append(disabled).append(" onclick=\"mdskDo('ACCEPT_ENTITY_AS_SHOWN',")
			.append(DeskHtml.jsAttr(pGroup.entityId)).append(",'',false)\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.shown.accept", pGroup.entityName))).append("</button></div></section>");
	}

	private static void renderChecksCentre(final StringBuilder pOut, final DeskModel.Group pGroup, final State pState, final Locale pLocale)
	{
		if (DeskHtml.renderOpenCheck(pOut, pGroup, pState, pLocale))
		{
			return;
		}
		pOut.append("<section class=\"mdskCard mdskHero\"><span class=\"mdskEyebrow\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.section.checks"))).append("</span><h1>")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.checks.title", pGroup.entityName))).append("</h1><p class=\"mdskLead\">")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.checks.lead"))).append("</p></section>");
		pOut.append("<section class=\"mdskCard mdskTableCard\">");
		DeskHtml.renderTable(pOut, pGroup.lines, false, pState, pLocale);
		pOut.append("</section>");
	}

	/** Lot L4: the check the person opened, drawn instead of the list; false when none is open (or it left the group). */
	private static boolean renderOpenCheck(final StringBuilder pOut, final DeskModel.Group pGroup, final State pState, final Locale pLocale)
	{
		if (pState.openCheckId == null || pState.openCheckId.isEmpty() || pState.checks == null || !pState.checks.containsKey(pState.openCheckId))
		{
			return false;
		}
		int index = 0;
		int total = 0;
		for (final DeskModel.Line line : pGroup.lines)
		{
			if (pState.checks.containsKey(line.item.id))
			{
				total++;
				if (line.item.id.equals(pState.openCheckId))
				{
					index = total;
				}
			}
		}
		if (index == 0)
		{
			return false;
		}
		DeskCheckHtml.render(pOut, pState.checks.get(pState.openCheckId), index, total, pState.readOnly, pLocale);
		return true;
	}

	private static void renderCannotRunCentre(final StringBuilder pOut, final DeskModel.Group pGroup, final State pState, final Locale pLocale)
	{
		pOut.append("<section class=\"mdskCard mdskHero\"><span class=\"mdskEyebrow\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.section.cannotRun"))).append("</span><h1>")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.run.title"))).append("</h1><p class=\"mdskLead\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.run.lead")))
			.append("</p></section>");
		// lot L5: one card per input no field holds, above the list (DeskInputHtml)
		final java.util.Set<String> waitingForInput = new java.util.HashSet<String>();
		for (final DeskModel.ServiceLine service : pGroup.services)
		{
			if (service.needsInput)
			{
				waitingForInput.add(service.id);
			}
		}
		DeskInputHtml.render(pOut, pState.input, waitingForInput, pState.readOnly, pLocale);
		pOut.append("<section class=\"mdskCard mdskTableCard\"><table class=\"mdskTable\"><thead><tr><th>").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.col.element")))
			.append("</th><th>").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.col.proposed"))).append("</th><th>").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.col.why")))
			.append("</th><th></th></tr></thead><tbody>");
		final String disabled = pState.readOnly ? " disabled=\"disabled\"" : "";
		for (final DeskModel.ServiceLine service : pGroup.services)
		{
			pOut.append("<tr><td><b><code>").append(DeskHtml.esc(service.element)).append("</code></b></td><td>").append(DeskHtml.esc(service.proposition));
			if (!service.connector.isEmpty())
			{
				pOut.append(" <span class=\"mdskDim\">").append(DeskHtml.esc(service.connector)).append("</span>");
			}
			pOut.append("</td><td>");
			if (service.noSecret)
			{
				pOut.append("<span class=\"mdskChip bad\"").append(service.readinessDetail.isEmpty() ? "" : " title=\"" + DeskHtml.esc(service.readinessDetail) + "\"").append(">")
					.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.run.noSecret"))).append("</span> ");
			}
			if (service.needsInput)
			{
				pOut.append("<span class=\"mdskChip bad\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.run.needsInput"))).append("</span> ");
			}
			if (service.noSecret && !service.readinessDetail.isEmpty())
			{
				pOut.append("<div class=\"mdskDim mdskSmall\">").append(DeskHtml.esc(service.readinessDetail)).append("</div>");
			}
			if (!service.rationale.isEmpty())
			{
				pOut.append("<div class=\"mdskDim mdskSmall\">").append(DeskHtml.esc(service.rationale)).append("</div>");
			}
			pOut.append("</td><td class=\"act\">");
			if (service.canSetAside())
			{
				// WizardData#setCapabilitiesAside moves connector services only: a script check is rejected here, never set aside
				pOut.append("<button type=\"button\" class=\"mdskBtn mdskBtnSm\"").append(disabled).append(" onclick=\"mdskDo('SET_ASIDE_GUARD',").append(DeskHtml.jsAttr(service.id))
					.append(",'',false)\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.btn.setAside"))).append("</button> ");
			}
			pOut.append("<button type=\"button\" class=\"mdskBtn mdskBtnSm\"").append(disabled)
				.append(" onclick=\"mdskDo('REJECT_GUARD',").append(DeskHtml.jsAttr(service.id)).append(',').append(DeskHtml.jsAttr(service.nature)).append(",false)\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.btn.reject")))
				.append("</button></td></tr>");
		}
		pOut.append("</tbody></table></section>");
		final int asideable = pGroup.servicesToSetAside();
		if (asideable == 0)
		{
			return;
		}
		pOut.append("<section class=\"mdskBulk\"><div class=\"mdskBulkText\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.run.bulkNote")))
			.append("</div><button type=\"button\" id=\"mdskSetAside\" class=\"mdskBtn mdskBtnPrimary\"").append(disabled).append(" onclick=\"mdskDo('SET_ASIDE_GUARDS',")
			.append(DeskHtml.jsAttr(pGroup.serviceIds())).append(",'EXTERNAL_SERVICE',false)\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.btn.setAsideAll", Integer.valueOf(asideable))))
			.append("</button></section>");
	}

	// ------------------------------------------------------------------------------------------------------ the item table

	private static void renderTable(final StringBuilder pOut, final List<DeskModel.Line> pLines, final boolean pConfidenceBulk, final State pState, final Locale pLocale)
	{
		pOut.append("<table class=\"mdskTable\"><thead><tr><th>").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.col.kind"))).append("</th><th>")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.col.element"))).append("</th><th>").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.col.proposed"))).append("</th><th>")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.col.why"))).append("</th><th>").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.col.confidence")))
			.append("</th><th></th></tr></thead><tbody>");
		for (final DeskModel.Line line : pLines)
		{
			DeskHtml.renderLine(pOut, line, pConfidenceBulk, pState, pLocale);
		}
		pOut.append("</tbody></table>");
	}

	private static void renderLine(final StringBuilder pOut, final DeskModel.Line pLine, final boolean pConfidenceBulk, final State pState, final Locale pLocale)
	{
		final MatrixCompute.StillItem item = pLine.item;
		pOut.append("<tr class=\"mdskLine").append(pLine.flagged() ? " eye" : "").append("\" data-key=\"").append(DeskHtml.esc(item.key)).append("\"><td><span class=\"mdskChip acc\">")
			.append(DeskHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.kind." + item.kind))).append("</span></td><td><b>").append(DeskHtml.esc(item.label)).append("</b>");
		if (!item.attributeName.isEmpty() && (MatrixCompute.KIND_RULE.equals(item.kind) || MatrixCompute.KIND_CHECK.equals(item.kind)))
		{
			pOut.append(" <span class=\"mdskDim\">").append(DeskHtml.esc(MatrixMessages.get(pLocale, "mappingMatrix.queue.onField", item.attributeName))).append("</span>");
		}
		pOut.append("</td><td>");
		DeskHtml.renderProposition(pOut, pLine, pLocale);
		pOut.append("</td><td><span class=\"mdskWhy").append(pLine.flagged() ? " warn" : "").append("\">").append(DeskHtml.esc(DeskHtml.whyText(item, pConfidenceBulk, pState.bulkThreshold, pLocale))).append("</span>");
		if (!pLine.rationale.isEmpty())
		{
			pOut.append("<div class=\"mdskDim mdskSmall\">").append(DeskHtml.esc(pLine.rationale)).append("</div>");
		}
		pOut.append("</td><td>").append(item.confidence == null ? "" : item.confidence.intValue() + " %").append("</td><td class=\"act\">");
		final String[] accept = DeskHtml.acceptOf(item);
		final String disabled = pState.readOnly ? " disabled=\"disabled\"" : "";
		if (!pConfidenceBulk && pState.checks != null && pState.checks.containsKey(item.id))
		{
			// lot L4: the check on the values it would flag
			pOut.append("<button type=\"button\" class=\"mdskBtn mdskBtnSm\" onclick=\"mdskOpenCheck(").append(DeskHtml.jsAttr(item.id)).append(")\">")
				.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.l4.btn.look"))).append("</button> ");
		}
		if (accept != null)
		{
			pOut.append("<button type=\"button\" class=\"mdskBtn mdskBtnSm mdskBtnPrimary\"").append(disabled).append(" onclick=\"mdskDo(").append(DeskHtml.jsAttr(accept[0])).append(',')
				.append(DeskHtml.jsAttr(accept[1])).append(',').append(DeskHtml.jsAttr(accept[2])).append(",false)\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.btn.accept")))
				.append("</button> ");
		}
		final String[] reject = DeskHtml.rejectOf(item);
		pOut.append("<button type=\"button\" class=\"mdskBtn mdskBtnSm\"").append(disabled).append(" onclick=\"mdskDo(").append(DeskHtml.jsAttr(reject[0])).append(',').append(DeskHtml.jsAttr(reject[1]))
			.append(',').append(DeskHtml.jsAttr(reject[2])).append(',').append("true".equals(reject[3])).append(")\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.btn.reject")))
			.append("</button></td></tr>");
	}

	/**
	 * The reason column of a line, in the desk's own terms. The matrix' wording of "the bulk would take it" ("goes with the Accept button") and "under the N % bar" describes
	 * the matrix' confidence bulk: on the desk it is true only where that bulk is on the page (the folded list of a business object), never in the Checks group, and the
	 * desk's main button ("as shown") takes by what the person saw, not by confidence. So {@code REASON_BULK} prints nothing, {@code REASON_BELOW_BAR} only next to the bulk,
	 * and the "Why" column's own figures (the proposer's rationale) carry the rest.
	 */
	static String whyText(final MatrixCompute.StillItem pItem, final boolean pConfidenceBulk, final int pThreshold, final Locale pLocale)
	{
		if (MatrixCompute.REASON_BULK.equals(pItem.reason) || (MatrixCompute.REASON_BELOW_BAR.equals(pItem.reason) && !pConfidenceBulk)
			|| (MatrixCompute.REASON_DECIDE.equals(pItem.reason) && !pConfidenceBulk))
		{
			return "";
		}
		return MatrixHtml.reasonText(pItem, pThreshold, pLocale);
	}

	/** What is proposed, in the words of its kind. */
	private static void renderProposition(final StringBuilder pOut, final DeskModel.Line pLine, final Locale pLocale)
	{
		final String kind = pLine.item.kind;
		if (MatrixCompute.KIND_FIELD.equals(kind))
		{
			pOut.append("<code>").append(DeskHtml.esc(pLine.dataType)).append("</code>");
			if (pLine.mandatory)
			{
				pOut.append(" <span class=\"mdskDim\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prop.mandatory"))).append("</span>");
			}
		}
		else if (MatrixCompute.KIND_COMPUTED.equals(kind) || MatrixCompute.KIND_MATCHING_KEY.equals(kind))
		{
			pOut.append(DeskHtml.esc(pLine.derivedFrom.isEmpty() ? DeskMessages.get(pLocale, "desk.prop.computed") : DeskMessages.get(pLocale, "desk.prop.computedFrom", pLine.derivedFrom)));
		}
		else if (MatrixCompute.KIND_FIELD_LINK.equals(kind))
		{
			pOut.append("<code>").append(DeskHtml.esc(pLine.column.isEmpty() ? "?" : pLine.column)).append("</code>");
			if (!pLine.steps.isEmpty())
			{
				pOut.append(" <span class=\"mdskDim\">&rarr; ").append(DeskHtml.esc(String.join(" \u2192 ", pLine.steps))).append("</span>");
			}
			if (!pLine.sourceName.isEmpty())
			{
				pOut.append(" <span class=\"mdskChip mut\">").append(DeskHtml.esc(pLine.sourceName)).append("</span>");
			}
		}
		else if (MatrixCompute.KIND_SOURCE_LINK.equals(kind))
		{
			pOut.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prop.sourceLink", pLine.sourceName)));
		}
		else if (MatrixCompute.KIND_ENTITY.equals(kind))
		{
			pOut.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.prop.entity", pLine.item.entityName)));
		}
		else
		{
			pOut.append("<code>").append(DeskHtml.esc(pLine.guardKind)).append("</code>");
			if (!pLine.guardArgument.isEmpty())
			{
				pOut.append(" <code>").append(DeskHtml.esc(pLine.guardArgument)).append("</code>");
			}
			if (!pLine.severity.isEmpty())
			{
				pOut.append(" <span class=\"mdskChip mut\">").append(DeskHtml.esc(MatrixMessages.find(pLocale, "mappingMatrix.severity." + pLine.severity) == null ? pLine.severity
					: MatrixMessages.get(pLocale, "mappingMatrix.severity." + pLine.severity))).append("</span>");
			}
		}
	}

	/**
	 * The matrix' own action for accepting this item - the vocabulary {@code MappingMatrixUserService#handle} reads: {action, row id, second row id}; null when the item cannot be
	 * accepted from here (a link whose business object is not accepted yet, a connector suggestion - set aside or reject only).
	 */
	static String[] acceptOf(final MatrixCompute.StillItem pItem)
	{
		if (pItem.setAside)
		{
			return null;
		}
		if (ReviewLine.ENTITY.equals(pItem.nature))
		{
			return new String[] { "ACCEPT_ENTITY", pItem.id, "" };
		}
		if (ReviewLine.ATTRIBUTE.equals(pItem.nature))
		{
			return new String[] { "ACCEPT_ATTRIBUTE", pItem.id, "" };
		}
		if (ReviewLine.FEED.equals(pItem.nature))
		{
			return new String[] { "ACCEPT_FEED", pItem.id, "" };
		}
		if (ReviewLine.MAPPING.equals(pItem.nature))
		{
			if (pItem.id.isEmpty() || pItem.cellAccept == MatrixCompute.CellAcceptAction.ENTITY_NOT_ACCEPTED)
			{
				return null;
			}
			return pItem.cellAccept == MatrixCompute.CellAcceptAction.ACCEPT_ATTRIBUTE_THEN_MAPPING ? new String[] { "ACCEPT_ATTR_THEN_MAPPING", pItem.attributeId, pItem.id }
				: new String[] { "ACCEPT_MAPPING", pItem.id, "" };
		}
		return new String[] { "ACCEPT_GUARD", pItem.id, pItem.nature };
	}

	/** {action, row id, second row id, "true" when the page asks the person for a reason first (a source link and a field link, like the matrix)}. */
	static String[] rejectOf(final MatrixCompute.StillItem pItem)
	{
		if (ReviewLine.ENTITY.equals(pItem.nature))
		{
			return new String[] { "REJECT_ENTITY", pItem.id, "", "false" };
		}
		if (ReviewLine.ATTRIBUTE.equals(pItem.nature))
		{
			return new String[] { "REJECT_ATTRIBUTE", pItem.id, "", "false" };
		}
		if (ReviewLine.FEED.equals(pItem.nature))
		{
			return new String[] { "REJECT_FEED", pItem.id, "", "true" };
		}
		if (ReviewLine.MAPPING.equals(pItem.nature))
		{
			return new String[] { "REJECT_MAPPING", pItem.id, "", "true" };
		}
		return new String[] { "REJECT_GUARD", pItem.id, pItem.nature, "false" };
	}

	// ---------------------------------------------------------------------------------------------------------- empty state

	private static void renderDone(final StringBuilder pOut, final DeskModel pModel, final State pState, final Locale pLocale)
	{
		final DeskModel.Done done = pModel.done;
		pOut.append("<section class=\"mdskCard mdskDone\"><div><span class=\"mdskChip good\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.done.chip"))).append("</span><h1>")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.done.title"))).append("</h1><p class=\"mdskLead\">")
			.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.done.lead", String.join(", ", done.objectNames), DeskMessages.count(pLocale, done.sources, "source")))).append("</p></div>");
		if (!pState.wizardUrl.isEmpty())
		{
			pOut.append("<a class=\"mdskBtn mdskBtnPrimary mdskBtnBig mdmf-matrix-designer-link\" target=\"_top\" href=\"").append(DeskHtml.esc(pState.wizardUrl)).append("\">")
				.append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.done.build"))).append("</a>");
		}
		pOut.append("</section><div class=\"mdskThree\">");
		pOut.append("<section class=\"mdskCard\"><span class=\"mdskEyebrow\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.done.accepted"))).append("</span><ul class=\"mdskList\">");
		for (final DeskModel.AcceptedObject object : done.acceptedObjects)
		{
			pOut.append("<li>").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.done.acceptedObject", object.name, DeskMessages.count(pLocale, object.fields, "field"),
				DeskMessages.count(pLocale, object.links, "link")))).append("</li>");
		}
		DeskHtml.renderListItems(pOut, done.acceptedChecks, done.moreChecks, pLocale);
		pOut.append("</ul></section><section class=\"mdskCard\"><span class=\"mdskEyebrow\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.done.setAside"))).append("</span><ul class=\"mdskList\">");
		DeskHtml.renderListItems(pOut, done.setAside, done.moreSetAside, pLocale);
		pOut.append("</ul></section><section class=\"mdskCard\"><span class=\"mdskEyebrow\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.done.followed"))).append("</span><ul class=\"mdskList\">");
		DeskHtml.renderListItems(pOut, done.followed, 0, pLocale);
		pOut.append("</ul>");
		if (!pState.matrixUrl.isEmpty())
		{
			pOut.append("<a class=\"mdmf-matrix-designer-link\" target=\"_top\" href=\"").append(DeskHtml.esc(pState.matrixUrl)).append("\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.done.review")))
				.append("</a>");
		}
		pOut.append("</section></div>");
	}

	private static void renderListItems(final StringBuilder pOut, final List<String> pItems, final int pMore, final Locale pLocale)
	{
		for (final String text : pItems)
		{
			pOut.append("<li>").append(DeskHtml.esc(text)).append("</li>");
		}
		if (pMore > 0)
		{
			pOut.append("<li class=\"mdskDim\">").append(DeskHtml.esc(DeskMessages.get(pLocale, "desk.done.more", Integer.valueOf(pMore)))).append("</li>");
		}
	}

	// --------------------------------------------------------------------------------------------------------- hidden fields

	private static void renderHiddenFields(final StringBuilder pOut, final DeskModel pModel, final State pState)
	{
		pOut.append("<input type=\"hidden\" id=\"mdmmAction\" name=\"mdmmAction\" value=\"\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmRowId\" name=\"mdmmRowId\" value=\"\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmRowId2\" name=\"mdmmRowId2\" value=\"\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdmmComment\" name=\"mdmmComment\" value=\"\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdskGroup\" name=\"mdskGroup\" value=\"").append(DeskHtml.esc(pState.selectedKey == null ? "" : pState.selectedKey)).append("\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdskScroll\" name=\"mdskScroll\" value=\"\"/>");
		pOut.append("<input type=\"hidden\" id=\"mdskCheck\" name=\"mdskCheck\" value=\"").append(DeskHtml.esc(pState.openCheckId == null ? "" : pState.openCheckId)).append("\"/>");
	}

	// ------------------------------------------------------------------------------------------------------------- script

	/**
	 * The interaction script, a plain JS string (never wrapped in a {@code <script>} tag: the service feeds it to {@code addJS_cr}). An IIFE; only the {@code window.mdskX}
	 * functions the inline {@code onclick}s call are global. {@code mdskGo} and {@code mdskDo} fill the hidden fields, then click the service's submit button.
	 */
	public static String script(final Locale pLocale)
	{
		return "(function(){\n"
			+ "var T={rejectReason:'" + FactoryHtml.jsStringBody(MatrixMessages.get(pLocale, "mappingMatrix.js.rejectReason")) + "'};\n"
			+ "function byId(id){return document.getElementById(id);}\n"
			// inside a UserServicePane the window never scrolls, an EBX container does (CLAUDE.md sec 9): its offset travels in the form and comes back in data-scrolltop
			+ "function scroller(){var el=byId('mdsk');el=el?el.parentElement:null;while(el&&el!==document.body&&el!==document.documentElement){var st=window.getComputedStyle(el);"
			+ "if(el.scrollHeight>el.clientHeight&&(st.overflowY==='auto'||st.overflowY==='scroll')){return el;}el=el.parentElement;}return document.scrollingElement||document.documentElement;}\n"
			+ "function submit(action,id,id2,comment){try{byId('mdskScroll').value=String(Math.round(scroller().scrollTop));}catch(x){}byId('mdmmAction').value=action;byId('mdmmRowId').value=id||'';"
			+ "byId('mdmmRowId2').value=id2||'';byId('mdmmComment').value=comment||'';var b=byId('" + DeskHtml.SUBMIT_ID + "');if(b){b.click();}}\n"
			// the group is set BEFORE the submit: setupObjectContext runs after the event on a postback and re-reads this field
			+ "window.mdskGo=function(key){byId('mdskGroup').value=key;submit('GOTO_GROUP','','','');};\n"
			// lot L4: open a check (or go back to the list with an empty id) - the id travels in a hidden field, read back by setupObjectContext
			// not "mdskCheck": inside an onclick the form's hidden field of that name hides a global of the same name
			+ "window.mdskOpenCheck=function(id){byId('mdskCheck').value=id||'';submit('GOTO_GROUP','','','');};\n"
			+ "window.mdskDo=function(action,id,id2,askReason){var c='';if(askReason){c=window.prompt(T.rejectReason);if(c===null){return;}}submit(action,id,id2,c);};\n"
			+ "(function(){var root=byId('mdsk');var top=root?parseInt(root.getAttribute('data-scrolltop'),10):-1;if(top>=0){try{scroller().scrollTop=top;}catch(x){}}})();\n"
			+ "})();";
	}

	// ------------------------------------------------------------------------------------------------------------- style

	private static void renderStyle(final StringBuilder pOut)
	{
		pOut.append("<style>");
		DeskCheckHtml.renderStyle(pOut);
		pOut
			// the brand colours follow the EBX perspective (ThemeTokens writes the --ebx-* tokens on :root), the Factory's own colours as the fallback - the same variables as the matrix
			.append("#mdsk{--accent:var(--ebx-main,#684bc0);--accent-text:var(--ebx-main-text,#fff);--accent-fg:var(--ebx-main-ink,#684bc0);--accent-soft:color-mix(in srgb,var(--accent) 9%,#fff);")
			.append("--accent-line:color-mix(in srgb,var(--accent) 24%,#fff);--btn:var(--ebx-button,var(--accent));--btn-text:var(--ebx-button-text,#fff)}")
			.append("#mdsk{font-family:Inter,system-ui,sans-serif;color:#172033;padding-bottom:60px}#mdsk h1{margin:4px 0;font-size:22px}#mdsk code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px}")
			.append("#mdsk .mdskDim{color:#667085}#mdsk .mdskSmall{font-size:11px}#mdsk .mdskOff{opacity:.5}#mdsk .mdskLead{margin:6px 0 0;font-size:13px;line-height:1.45;color:#475467;max-width:680px}")
			.append("#mdsk .mdskEyebrow{font-size:10.5px;font-weight:800;letter-spacing:.07em;text-transform:uppercase;color:#667085}#mdsk .mdskSection{display:block;padding:12px 12px 4px}")
			.append("#mdsk .mdskMsg{padding:8px 12px;border-radius:8px;background:#ecfdf3;color:#087443;margin-bottom:10px}#mdsk .mdskMsg.mdskErr{background:#fef2f2;color:#b42318}")
			.append("#mdsk .mdskHead{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;padding:10px 0 14px}")
			.append("#mdsk .mdskCrumb{display:flex;align-items:center;gap:10px;font-size:13.5px}#mdsk .mdskNav{display:flex;gap:8px;flex-wrap:wrap}")
			.append("#mdsk .mdskBtn{display:inline-flex;align-items:center;justify-content:center;gap:6px;border:1px solid #d0d5dd;background:#fff;color:#344054;border-radius:9px;padding:0 14px;min-height:36px;")
			.append("font:inherit;font-size:12.5px;font-weight:700;cursor:pointer;white-space:nowrap;text-decoration:none}#mdsk .mdskBtn:hover{border-color:var(--accent);color:var(--accent-fg)}")
			.append("#mdsk .mdskBtnSm{min-height:30px;padding:0 10px;font-size:11.5px}#mdsk .mdskBtnBig{min-height:46px;padding:0 22px;font-size:14px;border-radius:11px}")
			.append("#mdsk .mdskBtnPrimary{background:var(--btn);border-color:var(--btn);color:var(--btn-text)}#mdsk .mdskBtnPrimary:hover{color:var(--btn-text);filter:brightness(.92)}")
			.append("#mdsk .mdskBtnQuiet{border-color:transparent;background:transparent;color:#475467}#mdsk .mdskBtn[disabled]{opacity:.45;cursor:not-allowed}")
			.append("#mdsk .mdskChip{display:inline-flex;align-items:center;gap:4px;font-size:10.5px;font-weight:700;padding:2px 8px;border-radius:999px;border:1px solid;white-space:nowrap}")
			.append("#mdsk .mdskChip.mut{background:#f2f4f7;color:#475467;border-color:#d0d5dd}#mdsk .mdskChip.warn{background:#fffaeb;color:#93370d;border-color:#fedf89}")
			.append("#mdsk .mdskChip.acc{background:var(--accent-soft);color:var(--accent-fg);border-color:var(--accent-line)}#mdsk .mdskChip.good{background:#ecfdf3;color:#087443;border-color:#b7e3ca}")
			.append("#mdsk .mdskChip.bad{background:#fef2f2;color:#b42318;border-color:#fecaca}")
			.append("#mdsk .mdskCols{display:flex;gap:20px;align-items:flex-start}#mdsk .mdskSide{width:300px;flex:none;display:flex;flex-direction:column;gap:14px}")
			.append("#mdsk .mdskMain{flex:1;min-width:0;display:flex;flex-direction:column;gap:14px}")
			.append("#mdsk .mdskCard{background:#fff;border:1px solid #e4e7ec;border-radius:14px;padding:16px}#mdsk .mdskGroups{padding:8px;display:flex;flex-direction:column;gap:2px}")
			.append("#mdsk .mdskDashed{border-style:dashed}#mdsk .mdskBig{display:flex;align-items:baseline;justify-content:space-between}#mdsk .mdskBig span:first-child{font-size:26px;font-weight:800}")
			.append("#mdsk .mdskBar{height:6px;background:#eef0f3;border-radius:99px;margin-top:12px;overflow:hidden}#mdsk .mdskBar i{display:block;height:100%;background:var(--accent)}")
			.append("#mdsk .mdskQ{display:flex;flex-direction:column;gap:3px;padding:10px 12px;border-radius:10px;border:1px solid transparent;background:transparent;text-align:left;cursor:pointer;font:inherit;color:#172033}")
			.append("#mdsk .mdskQ:hover{background:var(--accent-soft)}#mdsk .mdskQ.on{background:var(--accent-soft);border-color:var(--accent-line)}")
			.append("#mdsk .mdskQTitle{font-weight:700;font-size:13px}#mdsk .mdskQSub{font-size:11.5px;color:#475467}#mdsk .mdskChips{display:flex;gap:4px;flex-wrap:wrap}")
			.append("#mdsk .mdskList{margin:8px 0 0;padding-left:16px;font-size:12px;color:#475467;line-height:1.7}#mdsk .mdskOptional summary{cursor:pointer;font-size:12px;font-weight:700;color:#475467}")
			.append("#mdsk .mdskHeroTop{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}")
			.append("#mdsk .mdskEye{margin-top:14px;border:1px solid #f5b54a;background:#fff8ec;border-radius:12px;padding:12px 16px;font-size:13px}")
			.append("#mdsk .mdskTableCard{padding:0;overflow-x:auto}#mdsk .mdskTable{border-collapse:separate;border-spacing:0;width:100%;font-size:12.5px}")
			.append("#mdsk .mdskTable th{text-align:left;vertical-align:bottom;padding:8px 10px;border-bottom:1px solid #e4e7ec;background:#fafbfc;font-weight:700;font-size:12px}")
			.append("#mdsk .mdskTable td{padding:8px 10px;border-bottom:1px solid #f0f2f5;vertical-align:top}#mdsk .mdskLine.eye td{background:#fff4e5}")
			.append("#mdsk .mdskWhy.warn{color:#93370d;font-weight:600}#mdsk td.act{white-space:nowrap;text-align:right}")
			.append("#mdsk .mdskBulk{display:flex;align-items:center;justify-content:space-between;gap:16px;background:#172033;color:#fff;border-radius:14px;padding:16px 20px}")
			.append("#mdsk .mdskBulk .mdskBtn{flex:none}#mdsk .mdskBulkText{font-size:13px;line-height:1.5}#mdsk .mdskBulkHint{color:#c7c2ea}")
			.append("#mdsk .mdskBlock{padding:0;overflow:hidden}#mdsk .mdskBlockHead{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:14px 18px;border-bottom:1px solid #e4e7ec}")
			.append("#mdsk .mdskScrollX{overflow-x:auto}#mdsk .mdskPrev{border-collapse:separate;border-spacing:0;width:100%;font-size:12.5px}")
			.append("#mdsk .mdskPrev th{text-align:left;vertical-align:bottom;padding:8px 10px;border-bottom:1px solid #e4e7ec;background:#fafbfc;font-weight:700;font-size:12px}")
			.append("#mdsk .mdskPrev td{padding:8px 10px;border-bottom:1px solid #f0f2f5;vertical-align:top}#mdsk .mdskPrev th.raw{background:#f4f5f8}")
			.append("#mdsk .mdskPrev td.raw{color:#667085;background:#fbfbfd;border-right:1px solid #eef0f3}#mdsk .mdskPrev td.flag{background:#fffaeb}")
			.append("#mdsk .mdskPrev td.eye{background:#fff4e5;box-shadow:inset 0 0 0 1px #f5b54a}#mdsk .mdskSub{display:block;font-weight:500;font-size:10.5px;color:#667085;margin-top:2px}")
			.append("#mdsk .mono{font-family:ui-monospace,Menlo,Consolas,monospace}#mdsk .mdskBadTxt{color:#b42318}#mdsk .mdskFill{margin:0;padding:8px 18px;font-size:11.5px;color:#667085}")
			.append("#mdsk .mdskFallback{margin:0;padding:10px 18px;font-size:12px;color:#93370d;background:#fffaeb}")
			.append("#mdsk .mdskLinkBtn{border:0;background:transparent;padding:0;font:inherit;color:var(--accent-fg);cursor:pointer;text-decoration:underline}")
			.append("#mdsk .mdskDetails{padding:0}#mdsk .mdskDetails>summary{cursor:pointer;padding:14px 18px;font-size:12.5px;font-weight:700;color:#475467}")
			.append("#mdsk .mdskDetails .mdskBulk{border-radius:0 0 14px 14px}#mdsk .mdskBarBtns{display:flex;gap:8px;flex:none;align-items:center}")
			.append("#mdsk .mdskBtnGhost{background:transparent;color:#fff;border-color:#475467;list-style:none}#mdsk .mdskReject{position:relative}#mdsk .mdskReject>summary::-webkit-details-marker{display:none}")
			.append("#mdsk .mdskMenu{position:absolute;bottom:calc(100% + 6px);right:0;min-width:200px;max-height:260px;overflow:auto;background:#fff;color:#172033;border:1px solid #d0d5dd;border-radius:10px;")
			.append("padding:4px;display:flex;flex-direction:column;box-shadow:0 8px 24px rgba(16,24,40,.18)}")
			.append("#mdsk .mdskMenuItem{border:0;background:transparent;text-align:left;padding:7px 10px;border-radius:7px;font:inherit;font-size:12.5px;cursor:pointer}")
			.append("#mdsk .mdskMenuItem:hover{background:var(--accent-soft)}#mdsk .mdskMenuItem[disabled]{opacity:.45;cursor:not-allowed}")
			.append("#mdsk .mdskDone{display:flex;justify-content:space-between;align-items:center;gap:20px;margin-bottom:16px}#mdsk .mdskThree{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}")
			.append("</style>");
	}

	// ------------------------------------------------------------------------------------------------------------- escaping

	static String esc(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}

	/** A single-quoted JS string literal for an inline {@code onclick="..."}: JS-escaped, then {@code &} and {@code "} neutralised for the attribute (same rule as {@code MatrixHtml#jsAttr}). */
	static String jsAttr(final String pValue)
	{
		return ("'" + FactoryHtml.jsStringBody(pValue) + "'").replace("&", "&amp;").replace("\"", "&quot;");
	}
}
