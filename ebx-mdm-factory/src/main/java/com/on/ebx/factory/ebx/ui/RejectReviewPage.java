package com.on.ebx.factory.ebx.ui;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.ebx.RejectReview;
import com.on.ebx.factory.rejects.RejectFilter;
import com.on.ebx.factory.rejects.RejectReasons;
import com.on.ebx.factory.rejects.RejectRow;

/**
 * The HTML of the "Transfer rejects" screen (audit F-38, wave 10), from plain values only (no EBX object): the summary per entity and stage, the
 * filter, one page of rejects with a checkbox each, the steward's actions and the pager. Pure, so {@code RejectReviewPageTest} renders it from
 * fixtures in both locales and checks the escaping, the empty states and that a viewer gets no action. Every control posts through the
 * screen's one submit button: a click sets the hidden field {@value #ACTION_FIELD} and clicks it (same idiom as the project deletion screen).
 */
final class RejectReviewPage
{
	static final String ACTION_FIELD = "mdmfRjAction";
	static final String SUBMIT_ID = "mdmfRjSubmit";
	/** The id of the "back" link: its target is the screen this one was opened from (see {@code FactoryHtml#originBackLinkScript}). */
	static final String BACK_ID = "mdmfRjBack";
	/** Staging load rejects (2026-10-17): the groups of reasons shown at most (the biggest ones); the rest is said in a line. */
	static final int GROUPS_SHOWN = 10;

	private RejectReviewPage()
	{
	}

	static final class Model
	{
		Locale locale = Locale.ENGLISH;
		String projectCode = "";
		String projectLabel = "";
		/** Entities of the filter's drop-down: formatted primary key and name. */
		final List<String[]> entities = new ArrayList<String[]>();
		final List<RejectReview.Summary> summaries = new ArrayList<RejectReview.Summary>();
		/** The biggest groups of reasons of the filter and how many groups it has in all (staging load rejects, 2026-10-17). */
		final List<RejectReview.Group> groups = new ArrayList<RejectReview.Group>();
		long groupTotal;
		RejectFilter filter = new RejectFilter();
		final List<RejectRow> rows = new ArrayList<RejectRow>();
		long total;
		/** Index of the first row of the page. */
		int offset;
		int pageSize = RejectReview.PAGE_SIZE;
		/** How many OPEN rows (resp. ACKNOWLEDGED rows) the filter matches - the number a bulk button announces. */
		long openMatching;
		long acknowledgedMatching;
		boolean canSteward;
		String csvUrl = "";
		/** F2-103: the filter holds more rows than a download carries: the export is a background job. The files already prepared for this person: name and download URL. */
		boolean exportAsJob;
		/** More rows than a download carries (the button is the steward's: a viewer reads the hint). */
		boolean exportTooBig;
		final List<String[]> exportFiles = new ArrayList<String[]>();
		String cockpitUrl = "";
		/** A localized one-line result of the last action ("3 rejects acknowledged."), or an error when {@link #error}. */
		String notice = "";
		boolean error;
		/** The comment text typed before the last action, shown again. */
		String comment = "";
		Date now = new Date();
	}

	static String esc(final String pText)
	{
		return FactoryHtml.esc(pText);
	}

	private static String m(final Model pModel, final String pKey, final Object... pArgs)
	{
		return Messages.get(RejectReviewPage.class, pModel.locale, pKey, pArgs);
	}

	private static String e(final Model pModel, final String pKey, final Object... pArgs)
	{
		return RejectReviewPage.esc(RejectReviewPage.m(pModel, pKey, pArgs));
	}

	static String render(final Model pModel)
	{
		final StringBuilder sb = new StringBuilder(8192);
		sb.append("<div class=\"mdmf mdmf-rejects\"><section class=\"mdmf-panel\"><h2>")
			.append(RejectReviewPage.e(pModel, "rejects.title", pModel.projectLabel.isEmpty() ? pModel.projectCode : pModel.projectLabel)).append("</h2><p class=\"mdmf-lead\">")
			.append(RejectReviewPage.e(pModel, "rejects.lead")).append("</p>");
		if (!pModel.cockpitUrl.isEmpty())
		{
			// staging load rejects (2026-10-17): the link goes to the Cockpit unless this screen was opened from somewhere else (the wizard's step 4): the page's script then
			// points it back there and words it ("Back to the wizard") from the labels below, like the Close button already does
			sb.append("<p><a id=\"").append(RejectReviewPage.BACK_ID).append("\" class=\"mdmf-leave-link\" href=\"").append(RejectReviewPage.esc(pModel.cockpitUrl))
				.append("\" target=\"_top\" data-label-wizard=\"").append(RejectReviewPage.e(pModel, "rejects.back.wizard")).append("\" data-label-back=\"")
				.append(RejectReviewPage.e(pModel, "rejects.back.back")).append("\">").append(RejectReviewPage.e(pModel, "rejects.back.cockpit")).append("</a></p>");
		}
		if (!pModel.notice.isEmpty())
		{
			sb.append("<div class=\"mdmf-notice mdmf-notice-").append(pModel.error ? "warn" : "ok").append("\">").append(RejectReviewPage.esc(pModel.notice)).append("</div>");
		}
		RejectReviewPage.summary(sb, pModel);
		RejectReviewPage.groups(sb, pModel);
		RejectReviewPage.filter(sb, pModel);
		RejectReviewPage.list(sb, pModel);
		sb.append("<input type=\"hidden\" id=\"").append(RejectReviewPage.ACTION_FIELD).append("\" name=\"").append(RejectReviewPage.ACTION_FIELD).append("\" value=\"\"/>");
		sb.append("<input type=\"hidden\" name=\"mdmfRjOffset\" value=\"").append(pModel.offset).append("\"/>");
		sb.append("</section></div>");
		return sb.toString();
	}

	// ---------------------------------------------------------------------------------------------------- summary

	private static void summary(final StringBuilder pOut, final Model pModel)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(RejectReviewPage.e(pModel, "rejects.summary.title")).append("</h3>");
		if (pModel.summaries.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(RejectReviewPage.e(pModel, "rejects.summary.none")).append("</div>");
			return;
		}
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(RejectReviewPage.e(pModel, "rejects.col.entity")).append("</th><th>")
			.append(RejectReviewPage.e(pModel, "rejects.col.stage")).append("</th><th>").append(RejectReviewPage.e(pModel, "rejects.status.OPEN")).append("</th><th>")
			.append(RejectReviewPage.e(pModel, "rejects.status.ACKNOWLEDGED")).append("</th><th>").append(RejectReviewPage.e(pModel, "rejects.status.RESOLVED"))
			.append("</th></tr></thead><tbody>");
		long open = 0L;
		long acknowledged = 0L;
		long resolved = 0L;
		for (final RejectReview.Summary summary : pModel.summaries)
		{
			open += summary.open;
			acknowledged += summary.acknowledged;
			resolved += summary.resolved;
			pOut.append("<tr><td>").append(RejectReviewPage.esc(summary.entityName)).append("</td><td>").append(RejectReviewPage.e(pModel, "rejects.stage." + summary.stage))
				.append("</td><td class=\"num\">").append(summary.open).append("</td><td class=\"num\">").append(summary.acknowledged).append("</td><td class=\"num\">")
				.append(summary.resolved).append("</td></tr>");
		}
		pOut.append("<tr><td colspan=\"2\"><b>").append(RejectReviewPage.e(pModel, "rejects.summary.total")).append("</b></td><td class=\"num\"><b>").append(open)
			.append("</b></td><td class=\"num\"><b>").append(acknowledged).append("</b></td><td class=\"num\"><b>").append(resolved).append("</b></td></tr>");
		pOut.append("</tbody></table></div>");
	}

	// ---------------------------------------------------------------------------------------------------- groups

	/**
	 * Staging load rejects (2026-10-17): WHY the rows were set aside, identical reasons grouped ("140 rows: date not in the expected format"), each with what to do and one
	 * example, and a button that filters the list to that kind of reason. Nothing is written when no row matches the filter.
	 */
	private static void groups(final StringBuilder pOut, final Model pModel)
	{
		if (pModel.groups.isEmpty())
		{
			return;
		}
		// staging last points (2026-10-17): the groups that still have rows to look at come first, with their own counts (open / acknowledged / resolved); a group a later load
		// resolved entirely is not a problem any more and stays behind a fold, opened only when the person filters on resolved rows
		final List<RejectReview.Group> pending = new ArrayList<RejectReview.Group>();
		final List<RejectReview.Group> resolved = new ArrayList<RejectReview.Group>();
		for (final RejectReview.Group group : pModel.groups)
		{
			(group.resolvedOnly() ? resolved : pending).add(group);
		}
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(RejectReviewPage.e(pModel, "rejects.groups.title")).append("</h3>");
		if (!pending.isEmpty())
		{
			RejectReviewPage.groupTable(pOut, pModel, pending, false);
		}
		else if (!RejectFilter.RESOLVED.equals(pModel.filter.status))
		{
			pOut.append("<p class=\"mdmf-hint\">").append(RejectReviewPage.e(pModel, "rejects.groups.nonePending")).append("</p>");
		}
		if (!resolved.isEmpty())
		{
			final boolean open = RejectFilter.RESOLVED.equals(pModel.filter.status);
			pOut.append("<details class=\"mdmf-rejects-resolved\"").append(open ? " open" : "").append("><summary>")
				.append(RejectReviewPage.e(pModel, "rejects.groups.resolved.title", Integer.valueOf(resolved.size()))).append("</summary><p class=\"mdmf-hint\">")
				.append(RejectReviewPage.e(pModel, "rejects.groups.resolved.hint")).append("</p>");
			RejectReviewPage.groupTable(pOut, pModel, resolved, true);
			pOut.append("</details>");
		}
		if (pModel.groupTotal > pModel.groups.size())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(RejectReviewPage.e(pModel, "rejects.groups.more", Long.valueOf(pModel.groupTotal - pModel.groups.size()))).append("</p>");
		}
	}

	/** The words of a group's rows: "12 open, 3 acknowledged, 140 resolved" (a zero is left out); a group built without its status counts reads "N rows". */
	static String countsText(final Model pModel, final RejectReview.Group pGroup)
	{
		if (pGroup.open + pGroup.acknowledged + pGroup.resolved == 0L)
		{
			return com.on.ebx.factory.wizard.WizardMessages.plural(pModel.locale, pGroup.count, "rejects.groups.rows.one", "rejects.groups.rows.other");
		}
		final List<String> parts = new ArrayList<String>();
		if (pGroup.open > 0L)
		{
			parts.add(RejectReviewPage.m(pModel, "rejects.groups.n.open", Long.valueOf(pGroup.open)));
		}
		if (pGroup.acknowledged > 0L)
		{
			parts.add(RejectReviewPage.m(pModel, "rejects.groups.n.acknowledged", Long.valueOf(pGroup.acknowledged)));
		}
		if (pGroup.resolved > 0L)
		{
			parts.add(RejectReviewPage.m(pModel, "rejects.groups.n.resolved", Long.valueOf(pGroup.resolved)));
		}
		return String.join(", ", parts);
	}

	private static void groupTable(final StringBuilder pOut, final Model pModel, final List<RejectReview.Group> pGroups, final boolean pResolved)
	{
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table mdmf-rejects-groups").append(pResolved ? " mdmf-rejects-groups-resolved" : "").append("\"><thead><tr><th>")
			.append(RejectReviewPage.e(pModel, "rejects.col.entity")).append("</th><th>").append(RejectReviewPage.e(pModel, "rejects.filter.code")).append("</th><th></th></tr></thead><tbody>");
		for (final RejectReview.Group group : pGroups)
		{
			final List<RejectReasons.Shown> shown = RejectReasons.read(group.sampleReasons, group.sampleCategory, group.sampleDetail, pModel.locale);
			final RejectReasons.Shown first = shown.isEmpty() ? null : shown.get(0);
			final boolean coded = !group.reasonCode.isEmpty() && first != null && !first.group.isEmpty();
			pOut.append("<tr").append(pResolved ? " class=\"mdmf-rejects-resolved-row\"" : "").append("><td>").append(RejectReviewPage.esc(group.entityName)).append("<div class=\"mdmf-hint\">")
				.append(RejectReviewPage.esc(RejectReviewPage.countsText(pModel, group))).append("</div></td><td>");
			pOut.append(coded ? "<b>" + RejectReviewPage.esc(first.group) + "</b>" : RejectReviewPage.e(pModel, "rejects.groups.uncoded"));
			if (coded && !first.fix.isEmpty() && !pResolved)
			{
				pOut.append("<div class=\"mdmf-hint\"><b>").append(RejectReviewPage.e(pModel, "rejects.fix.label")).append(" :</b> ").append(RejectReviewPage.esc(first.fix)).append("</div>");
			}
			if (first != null)
			{
				pOut.append("<div class=\"mdmf-hint\">").append(RejectReviewPage.e(pModel, "rejects.groups.example", first.sentence)).append("</div>");
			}
			final String code = group.reasonCode.isEmpty() ? RejectFilter.UNCODED : group.reasonCode;
			// the primary button is for what is left to look at; the rows of a resolved group are shown on request, as resolved rows
			pOut.append("</td><td><button type=\"button\" class=\"mdmf-btn").append(pResolved ? "" : " mdmf-btn-primary").append(" mdmf-btn-sm\" onclick=\"mdmfRjGroup('")
				.append(RejectReviewPage.esc(FactoryHtml.jsStringBody(group.entityId))).append("','").append(RejectReviewPage.esc(FactoryHtml.jsStringBody(code))).append("'")
				.append(pResolved ? ",'" + RejectFilter.RESOLVED + "'" : ",''").append(")\">").append(RejectReviewPage.e(pModel, pResolved ? "rejects.groups.showResolved" : "rejects.groups.show"))
				.append("</button></td></tr>");
		}
		pOut.append("</tbody></table></div>");
	}

	// ----------------------------------------------------------------------------------------------------- filter

	private static void filter(final StringBuilder pOut, final Model pModel)
	{
		final RejectFilter f = pModel.filter;
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(RejectReviewPage.e(pModel, "rejects.filter.title")).append("</h3><div class=\"mdmf-rejects-filter\">");
		pOut.append("<label>").append(RejectReviewPage.e(pModel, "rejects.col.entity")).append(" <select name=\"mdmfRjEntity\"><option value=\"\">")
			.append(RejectReviewPage.e(pModel, "rejects.filter.any")).append("</option>");
		for (final String[] entity : pModel.entities)
		{
			pOut.append("<option value=\"").append(RejectReviewPage.esc(entity[0])).append('"').append(entity[0].equals(f.entityId) ? " selected" : "").append('>')
				.append(RejectReviewPage.esc(entity[1])).append("</option>");
		}
		pOut.append("</select></label> ");
		pOut.append("<label>").append(RejectReviewPage.e(pModel, "rejects.col.stage")).append(" <select name=\"mdmfRjStage\"><option value=\"\">")
			.append(RejectReviewPage.e(pModel, "rejects.filter.any")).append("</option>");
		for (final String stage : new String[] { "STAGING", "MASTER" })
		{
			pOut.append("<option value=\"").append(stage).append('"').append(stage.equals(f.stage) ? " selected" : "").append('>')
				.append(RejectReviewPage.e(pModel, "rejects.stage." + stage)).append("</option>");
		}
		pOut.append("</select></label> ");
		pOut.append("<label>").append(RejectReviewPage.e(pModel, "rejects.col.status")).append(" <select name=\"mdmfRjStatus\"><option value=\"\">")
			.append(RejectReviewPage.e(pModel, "rejects.filter.any")).append("</option>");
		for (final String status : new String[] { RejectFilter.OPEN, RejectFilter.ACKNOWLEDGED, RejectFilter.RESOLVED })
		{
			pOut.append("<option value=\"").append(status).append('"').append(status.equals(f.status) ? " selected" : "").append('>')
				.append(RejectReviewPage.e(pModel, "rejects.status." + status)).append("</option>");
		}
		pOut.append("</select></label> ");
		pOut.append("<label>").append(RejectReviewPage.e(pModel, "rejects.filter.reason")).append(" <input type=\"text\" name=\"mdmfRjReason\" value=\"")
			.append(RejectReviewPage.esc(f.reasonContains)).append("\" maxlength=\"100\"/></label> ");
		pOut.append("<label>").append(RejectReviewPage.e(pModel, "rejects.filter.from")).append(" <input type=\"date\" name=\"mdmfRjFrom\" value=\"")
			.append(RejectReviewPage.esc(RejectFilter.dayText(f.from))).append("\"/></label> ");
		pOut.append("<label>").append(RejectReviewPage.e(pModel, "rejects.filter.to")).append(" <input type=\"date\" name=\"mdmfRjTo\" value=\"")
			.append(RejectReviewPage.esc(RejectFilter.dayText(f.to))).append("\"/></label> ");
		// staging load rejects (2026-10-17): one KIND of reason, set by a group's "Show these rows" button; shown as a chip while it holds, cleared with the filter
		pOut.append("<input type=\"hidden\" name=\"mdmfRjCode\" value=\"").append(RejectReviewPage.esc(f.reasonCode)).append("\"/>");
		if (!f.reasonCode.isEmpty())
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-info\" title=\"").append(RejectReviewPage.esc(f.reasonCode)).append("\">").append(RejectReviewPage.e(pModel, "rejects.filter.codeOne"))
				.append("</span> ");
		}
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-sm\" onclick=\"mdmfRj('APPLY')\">").append(RejectReviewPage.e(pModel, "rejects.filter.apply"))
			.append("</button> <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" onclick=\"mdmfRj('CLEAR')\">").append(RejectReviewPage.e(pModel, "rejects.filter.clear"))
			.append("</button></div>");
	}

	// ------------------------------------------------------------------------------------------------------ list

	private static void list(final StringBuilder pOut, final Model pModel)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(RejectReviewPage.e(pModel, "rejects.list.title")).append("</h3>");
		if (pModel.rows.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(RejectReviewPage.e(pModel, "rejects.list.none")).append("</div>");
			RejectReviewPage.export(pOut, pModel);
			return;
		}
		final int from = pModel.offset + 1;
		final int to = pModel.offset + pModel.rows.size();
		pOut.append("<p class=\"mdmf-hint\">").append(RejectReviewPage.e(pModel, "rejects.list.range", Integer.valueOf(from), Integer.valueOf(to), Long.valueOf(pModel.total)));
		pOut.append(' ');
		if (pModel.offset > 0)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" onclick=\"mdmfRj('PAGE_PREV')\">").append(RejectReviewPage.e(pModel, "rejects.list.prev")).append("</button> ");
		}
		if (to < pModel.total)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" onclick=\"mdmfRj('PAGE_NEXT')\">").append(RejectReviewPage.e(pModel, "rejects.list.next")).append("</button>");
		}
		pOut.append("</p>");
		final DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, pModel.locale);
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr>");
		if (pModel.canSteward)
		{
			pOut.append("<th></th>");
		}
		pOut.append("<th>").append(RejectReviewPage.e(pModel, "rejects.col.source")).append("</th><th>").append(RejectReviewPage.e(pModel, "rejects.col.entity")).append("</th><th>")
			.append(RejectReviewPage.e(pModel, "rejects.col.stage")).append("</th><th>").append(RejectReviewPage.e(pModel, "rejects.col.reasons")).append("</th><th>")
			.append(RejectReviewPage.e(pModel, "rejects.col.at")).append("</th><th>").append(RejectReviewPage.e(pModel, "rejects.col.status")).append("</th></tr></thead><tbody>");
		for (final RejectRow row : pModel.rows)
		{
			final String status = row.status();
			pOut.append("<tr>");
			if (pModel.canSteward)
			{
				pOut.append("<td>");
				if (!RejectFilter.RESOLVED.equals(status))
				{
					pOut.append("<input type=\"checkbox\" name=\"mdmfRjSel\" value=\"").append(RejectReviewPage.esc(row.ref().encode())).append("\"/>");
				}
				pOut.append("</td>");
			}
			// the source id is an identifier of the rejected row, never one of its field values (TransferReject only stores identifiers and engine text)
			pOut.append("<td><code>").append(RejectReviewPage.esc(row.sourceSystem)).append(':').append(RejectReviewPage.esc(row.sourceId)).append("</code></td><td>")
				.append(RejectReviewPage.esc(row.entityName)).append("</td><td>").append(RejectReviewPage.e(pModel, "rejects.stage." + row.stage)).append("</td><td>");
			RejectReviewPage.reasons(pOut, pModel, row);
			pOut.append("</td><td>").append(row.rejectedAt == null ? "&mdash;" : RejectReviewPage.esc(format.format(row.rejectedAt)))
				.append("</td><td><span class=\"mdmf-chip mdmf-chip-").append(RejectFilter.OPEN.equals(status) ? "warn" : RejectFilter.RESOLVED.equals(status) ? "real" : "info")
				.append("\">").append(RejectReviewPage.e(pModel, "rejects.status." + status)).append("</span>");
			if (!row.reviewedBy.isEmpty() || !row.reviewComment.isEmpty())
			{
				pOut.append("<div class=\"mdmf-hint\">").append(RejectReviewPage.e(pModel, "rejects.review.line", row.reviewedBy,
					row.reviewedAt == null ? "" : format.format(row.reviewedAt), row.reviewComment)).append("</div>");
			}
			pOut.append("</td></tr>");
		}
		pOut.append("</tbody></table></div>");
		RejectReviewPage.actions(pOut, pModel);
		RejectReviewPage.export(pOut, pModel);
	}

	/**
	 * Staging load rejects (2026-10-17): the reasons of one reject in the viewer's language, each as ONE escaped sentence, and EBX's raw text folded under the first. The text
	 * is escaped here and nowhere before (the stored text is unescaped once when it is written), so a ">" shows as a ">" and not as "&amp;gt;".
	 */
	private static void reasons(final StringBuilder pOut, final Model pModel, final RejectRow pRow)
	{
		final List<RejectReasons.Shown> shown = RejectReasons.read(pRow.reasons, pRow.category, pRow.detail, pModel.locale);
		if (shown.isEmpty())
		{
			pOut.append("&mdash;");
			return;
		}
		for (final RejectReasons.Shown reason : shown)
		{
			pOut.append("<div>").append(RejectReviewPage.esc(reason.sentence)).append("</div>");
			if (!reason.raw.isEmpty())
			{
				pOut.append("<details class=\"mdmf-reject-detail\"><summary>").append(RejectReviewPage.e(pModel, "rejects.detail.show")).append("</summary><pre>")
					.append(RejectReviewPage.esc(reason.raw)).append("</pre></details>");
			}
		}
	}

	private static void actions(final StringBuilder pOut, final Model pModel)
	{
		if (!pModel.canSteward)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(RejectReviewPage.e(pModel, "rejects.viewOnly")).append("</p>");
			return;
		}
		pOut.append("<div class=\"mdmf-rejects-actions\"><label>").append(RejectReviewPage.e(pModel, "rejects.action.comment"))
			.append(" <input type=\"text\" name=\"mdmfRjComment\" size=\"60\" maxlength=\"").append(RejectReview.COMMENT_MAX).append("\" value=\"")
			.append(RejectReviewPage.esc(pModel.comment)).append("\"/></label> ");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-sm\" onclick=\"mdmfRj('ACK_SELECTED')\">").append(RejectReviewPage.e(pModel, "rejects.action.ackSelected"))
			.append("</button> ");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" onclick=\"mdmfRj('REOPEN_SELECTED')\">").append(RejectReviewPage.e(pModel, "rejects.action.reopenSelected"))
			.append("</button>");
		if (pModel.openMatching > 0)
		{
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" onclick=\"if(confirm('")
				.append(RejectReviewPage.esc(FactoryHtml.jsStringBody(RejectReviewPage.m(pModel, "rejects.action.confirmAll", Long.valueOf(pModel.openMatching))))).append("'))mdmfRj('ACK_ALL')\">")
				.append(RejectReviewPage.e(pModel, "rejects.action.ackAll", Long.valueOf(pModel.openMatching))).append("</button>");
		}
		if (pModel.acknowledgedMatching > 0)
		{
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" onclick=\"if(confirm('")
				.append(RejectReviewPage.esc(FactoryHtml.jsStringBody(RejectReviewPage.m(pModel, "rejects.action.confirmReopenAll", Long.valueOf(pModel.acknowledgedMatching)))))
				.append("'))mdmfRj('REOPEN_ALL')\">").append(RejectReviewPage.e(pModel, "rejects.action.reopenAll", Long.valueOf(pModel.acknowledgedMatching))).append("</button>");
		}
		pOut.append("</div>");
	}

	private static void export(final StringBuilder pOut, final Model pModel)
	{
		if (pModel.exportAsJob)
		{
			pOut.append("<p><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" onclick=\"mdmfRj('EXPORT_JOB')\">")
				.append(RejectReviewPage.e(pModel, "rejects.export.job", Long.valueOf(pModel.total))).append("</button></p>");
		}
		else if (pModel.exportTooBig)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(RejectReviewPage.e(pModel, "rejects.export.needSteward", Long.valueOf(pModel.total))).append("</p>");
		}
		else if (!pModel.csvUrl.isEmpty())
		{
			pOut.append("<p><a class=\"mdmf-btn mdmf-btn-sm mdmf-leave-link\" href=\"").append(RejectReviewPage.esc(pModel.csvUrl)).append("\">")
				.append(RejectReviewPage.e(pModel, "rejects.export")).append("</a></p>");
		}
		if (!pModel.exportFiles.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(RejectReviewPage.e(pModel, "rejects.export.files")).append(": ");
			for (final String[] file : pModel.exportFiles)
			{
				pOut.append("<a class=\"mdmf-leave-link\" href=\"").append(RejectReviewPage.esc(file[1])).append("\">").append(RejectReviewPage.e(pModel, "rejects.export.file", file[0]))
					.append("</a> ");
			}
			pOut.append("</p>");
		}
	}

	/** The click helper: sets the action field and clicks the screen's submit button. */
	static String script()
	{
		return "window.mdmfRj=function(a){var f=document.getElementById('" + RejectReviewPage.ACTION_FIELD + "');var b=document.getElementById('" + RejectReviewPage.SUBMIT_ID
			+ "');if(f&&b){f.value=a;b.click();}};"
			// staging load rejects (2026-10-17): a group's "Show these rows" - the entity and the kind of reason of the group, then the filter is applied
			+ "window.mdmfRjGroup=function(e,c,st){var s=document.querySelector('[name=mdmfRjEntity]');if(s){s.value=e;}var h=document.querySelector('[name=mdmfRjCode]');if(h){h.value=c;}var t=document.querySelector('[name=mdmfRjStatus]');if(t&&typeof st==='string'){t.value=st;}window.mdmfRj('APPLY');};"
			// 2026-10-07: EBX draws the screen's own submit button in its footer ("Apply the filter" at the bottom left); the page has its own
			// buttons, so the native one is hidden and only clicked from them (the wizard does the same with mdmfWizSubmit)
			+ "(function(){var b=document.getElementById('" + RejectReviewPage.SUBMIT_ID + "');if(b){b.style.display='none';}})();";
	}
}
