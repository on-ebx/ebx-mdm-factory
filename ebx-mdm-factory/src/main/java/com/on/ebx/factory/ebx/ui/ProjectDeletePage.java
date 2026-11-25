package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.project.ProjectDeletionPlan;

/**
 * The HTML of the "Delete project" screen, from a plain {@link Model} only (no EBX object) - the same
 * pattern as {@link FactoryConnectionPage}: a fixture-driven, JUnit-free test exercises escaping, both
 * locales, the option blocks' checked/disabled state and the report mode, without a running instance.
 * <p>
 * Two renderings of the same {@link Model}, chosen by {@link Model#deleted}:
 * <ul>
 * <li><b>Plan mode (the default)</b> - the project's own row counts (always deleted, no checkbox), then one
 * block per {@link ProjectDeletionPlan} option ({@code matching}/{@code flows}/{@code dataspaces}/{@code
 * models}), each with a checkbox pre-checked when the block holds at least one eligible item and disabled
 * when it holds none at all, listing every item of that option - an eligible one as a plain line with its
 * {@link Item#detail}, an ineligible one greyed out with its {@link Item#reason} - then the "type the project
 * code" confirm field. The submit/close buttons themselves are configured by {@code ProjectDeleteUserService}
 * (a {@code UserServiceDisplayConfigurator} submit button, the same convention {@link FactoryConnectionPage}
 * already follows), not rendered here.</li>
 * <li><b>Report mode</b>, once a delete has actually run - the same row counts (now "rows deleted"), and the
 * same items, each carrying the outcome the delete call actually recorded ({@link Item#outcome}/
 * {@link Item#message}) instead of a checkbox; a link back to the project explorer closes the loop.</li>
 * </ul>
 */
final class ProjectDeletePage
{
	private ProjectDeletePage()
	{
	}

	static final class Item
	{
		String kind = "";
		String option = "";
		String name = "";
		String detail = "";
		boolean eligible;
		/** Non-empty only when {@link #eligible} is false. */
		String reason = "";
		/** Report mode only: {@code DELETED}/{@code SKIPPED_ABSENT}/{@code KEPT_INELIGIBLE}/{@code NOT_SELECTED}/{@code FAILED}; empty before a delete has run. */
		String outcome = "";
		String message = "";
	}

	static final class Model
	{
		Locale locale = Locale.ENGLISH;
		String projectCode = "";
		String projectLabel = "";
		boolean delivered;
		final List<Item> items = new ArrayList<Item>();
		/** The project's own factory rows, table name to row count, in deletion order - "would delete" in plan mode, "deleted" in report mode. */
		final Map<String, Integer> rowCounts = new LinkedHashMap<String, Integer>();
		/** True once {@code ProjectDeleter.delete} has actually run for this rendering - switches to report mode. */
		boolean deleted;
		/** A notice shown instead of the form (project not found, a refusal from {@code ProjectDeletionPlan.validate}, a partial-failure message) - never both {@link #deleted} and a non-null error on the same rendering. */
		String error;
		/** Built by the caller with {@code FactoryLinks#explorerUrl} - the one link this pure page cannot build itself, rendered only in report mode. */
		String explorerUrl = "";
		/**
		 * Volume-operations lot 8b (2026-09-28, task 3): set right after {@code handleDelete} enqueues a {@code
		 * ProjectDeleteRun} job - mutually exclusive with {@link #deleted}: the plan/confirm form and the report
		 * are both replaced by a plain "running" notice while this is true. {@link #jobId} is what the poll
		 * button posts back to check on.
		 */
		boolean running;
		String jobId = "";
		/** Report mode only, once the job is confirmed done: the job's own summary message (see {@code ProjectDeleteRun}'s own Javadoc for why the rich per-item table this page used to show synchronously is no longer available). */
		String doneMessage = "";
	}

	// --------------------------------------------------------------------------------------------------------- page

	static String render(final Model pModel)
	{
		final StringBuilder sb = new StringBuilder(8192);
		sb.append("<div class=\"mdmf mdmf-delete\">");
		sb.append("<section class=\"mdmf-panel\"><h2>").append(ProjectDeletePage.e(pModel, "delete.section.title")).append("</h2>");
		sb.append("<p class=\"mdmf-lead\">")
			.append(ProjectDeletePage.e(pModel, pModel.deleted ? "delete.report.lead" : "delete.section.lead", ProjectDeletePage.projectTitle(pModel)))
			.append("</p>");

		if (pModel.error != null)
		{
			sb.append("<div class=\"mdmf-notice mdmf-notice-bad\" role=\"alert\">").append(ProjectDeletePage.esc(pModel.error)).append("</div>");
		}

		// volume-operations lot 8b (2026-09-28, task 3): the deletion now runs as a background job - a plain
		// "running" notice with a hidden self-poll (ProjectDeleteUserService.write's own inline script), mutually
		// exclusive with the plan/confirm form below and the report further down.
		if (pModel.running)
		{
			sb.append("<div class=\"mdmf-notice mdmf-notice-info\" id=\"mdmfDeleteRunning\" data-mdmf-delete-job=\"").append(ProjectDeletePage.esc(pModel.jobId))
				.append("\">").append(ProjectDeletePage.e(pModel, "delete.running.lead", ProjectDeletePage.projectTitle(pModel))).append("</div>");
			sb.append("</section>");
			sb.append("</div>");
			return sb.toString();
		}

		if (pModel.delivered)
		{
			sb.append("<div class=\"mdmf-notice mdmf-notice-info\">").append(ProjectDeletePage.e(pModel, "delete.notice.delivered")).append("</div>");
		}
		if (!pModel.deleted)
		{
			sb.append("<div class=\"mdmf-notice mdmf-notice-bad\">").append(ProjectDeletePage.e(pModel, "delete.notice.irreversible")).append("</div>");
		}

		// volume-operations lot 8b (2026-09-28, task 3): the async job path (ProjectDeleteRun) has no per-item/
		// per-table detail to show any more - see Model#doneMessage's own Javadoc for why - so a non-empty
		// doneMessage with no items means "async success": skip the (otherwise misleading, all-zero) row-counts
		// table and the items list, and show the job's own summary sentence instead (it already states the row/
		// table totals). A caller that still fills items/rowCounts (none does today, but the rendering is kept
		// working) gets the original detailed report.
		final boolean asyncSuccess = pModel.deleted && pModel.items.isEmpty() && !pModel.doneMessage.isEmpty();
		if (!asyncSuccess)
		{
			ProjectDeletePage.rowCountsTable(sb, pModel);
		}

		if (pModel.deleted)
		{
			if (asyncSuccess)
			{
				sb.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(ProjectDeletePage.esc(pModel.doneMessage)).append("</div>");
			}
			else
			{
				ProjectDeletePage.reportItems(sb, pModel);
			}
			sb.append("<p class=\"mdmf-hint\"><a class=\"mdmf-leave-link\" href=\"").append(ProjectDeletePage.esc(pModel.explorerUrl)).append("\" target=\"_top\">")
				.append(ProjectDeletePage.e(pModel, "delete.report.backToExplorer")).append("</a></p>");
			sb.append("</section>");
			sb.append("</div>");
			return sb.toString();
		}

		sb.append("<h3 class=\"mdmf-subtitle\">").append(ProjectDeletePage.e(pModel, "delete.options.title")).append("</h3>");
		ProjectDeletePage.optionBlock(sb, pModel, ProjectDeletionPlan.OPTION_MATCHING, "delete.option.matching.label", "delete.option.matching.hint");
		ProjectDeletePage.optionBlock(sb, pModel, ProjectDeletionPlan.OPTION_FLOWS, "delete.option.flows.label", "delete.option.flows.hint");
		ProjectDeletePage.optionBlock(sb, pModel, ProjectDeletionPlan.OPTION_DATASPACES, "delete.option.dataspaces.label", "delete.option.dataspaces.hint");
		ProjectDeletePage.optionBlock(sb, pModel, ProjectDeletionPlan.OPTION_MODELS, "delete.option.models.label", "delete.option.models.hint");
		ProjectDeletePage.optionBlock(sb, pModel, ProjectDeletionPlan.OPTION_CONNECTIONS, "delete.option.connections.label", "delete.option.connections.hint");

		sb.append("<div class=\"mdmf-field\"><label for=\"mdmfConfirm\">")
			.append(ProjectDeletePage.e(pModel, "delete.confirm.label", ProjectDeletePage.esc(pModel.projectCode))).append("</label>")
			.append("<input type=\"text\" id=\"mdmfConfirm\" name=\"mdmfConfirm\" value=\"\" autocomplete=\"off\"/>")
			.append("<p class=\"mdmf-hint\">").append(ProjectDeletePage.e(pModel, "delete.confirm.hint")).append("</p></div>");

		sb.append("</section>");
		sb.append("</div>");
		return sb.toString();
	}

	private static String projectTitle(final Model pModel)
	{
		final String label = pModel.projectLabel == null ? "" : pModel.projectLabel.trim();
		return label.isEmpty() ? pModel.projectCode : label + " (" + pModel.projectCode + ")";
	}

	private static void rowCountsTable(final StringBuilder pOut, final Model pModel)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">")
			.append(ProjectDeletePage.e(pModel, pModel.deleted ? "delete.rows.title.done" : "delete.rows.title.plan")).append("</h3>");
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(ProjectDeletePage.e(pModel, "delete.rows.table"))
			.append("</th><th class=\"num\">").append(ProjectDeletePage.e(pModel, "delete.rows.count")).append("</th></tr></thead><tbody>");
		int total = 0;
		for (final Map.Entry<String, Integer> entry : pModel.rowCounts.entrySet())
		{
			total += entry.getValue().intValue();
			pOut.append("<tr><td>").append(ProjectDeletePage.esc(entry.getKey())).append("</td><td class=\"num\">").append(entry.getValue()).append("</td></tr>");
		}
		pOut.append("<tr><td><b>").append(ProjectDeletePage.e(pModel, "delete.rows.total")).append("</b></td><td class=\"num\"><b>").append(total)
			.append("</b></td></tr>");
		pOut.append("</tbody></table></div>");
	}

	// ------------------------------------------------------------------------------------------------- option blocks

	private static void optionBlock(final StringBuilder pOut, final Model pModel, final String pOption, final String pLabelKey, final String pHintKey)
	{
		final List<Item> items = ProjectDeletePage.itemsOf(pModel, pOption);
		final boolean hasEligible = ProjectDeletePage.hasEligible(items);
		final boolean disabled = items.isEmpty() || !hasEligible;
		pOut.append("<div class=\"mdmf-delete-block\"><label class=\"mdmf-delete-option\"><input type=\"checkbox\" name=\"mdmfGenerated\" value=\"")
			.append(ProjectDeletePage.esc(pOption)).append("\"").append(hasEligible ? " checked=\"checked\"" : "").append(disabled ? " disabled=\"disabled\"" : "")
			.append("/><b>").append(ProjectDeletePage.e(pModel, pLabelKey)).append("</b></label>");
		pOut.append("<p class=\"mdmf-hint\">").append(ProjectDeletePage.e(pModel, pHintKey)).append("</p>");
		if (items.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(ProjectDeletePage.e(pModel, "delete.option.nothing")).append("</p>");
		}
		else
		{
			pOut.append("<ul class=\"mdmf-delete-items\">");
			for (final Item item : items)
			{
				ProjectDeletePage.itemLine(pOut, pModel, item, false);
			}
			pOut.append("</ul>");
		}
		pOut.append("</div>");
	}

	private static void reportItems(final StringBuilder pOut, final Model pModel)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(ProjectDeletePage.e(pModel, "delete.report.items.title")).append("</h3>");
		pOut.append("<ul class=\"mdmf-delete-items\">");
		for (final Item item : pModel.items)
		{
			ProjectDeletePage.itemLine(pOut, pModel, item, true);
		}
		pOut.append("</ul>");
	}

	private static void itemLine(final StringBuilder pOut, final Model pModel, final Item pItem, final boolean pReportMode)
	{
		final String cls = pReportMode ? "mdmf-delete-item mdmf-delete-item-" + ProjectDeletePage.outcomeClass(pItem.outcome)
			: pItem.eligible ? "mdmf-delete-item" : "mdmf-delete-item mdmf-delete-item-ineligible";
		pOut.append("<li class=\"").append(cls).append("\"><span class=\"mdmf-delete-item-name\">").append(ProjectDeletePage.esc(pItem.name)).append("</span>");
		pOut.append(" <span class=\"mdmf-chip mdmf-chip-").append(ProjectDeletePage.kindChipKind(pItem)).append("\">")
			.append(ProjectDeletePage.e(pModel, "delete.kind." + pItem.kind)).append("</span>");
		if (pReportMode)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-").append(ProjectDeletePage.outcomeClass(pItem.outcome)).append("\">")
				.append(ProjectDeletePage.e(pModel, "delete.outcome." + pItem.outcome)).append("</span>");
			if (!pItem.message.isEmpty())
			{
				pOut.append("<p class=\"mdmf-hint\">").append(ProjectDeletePage.esc(pItem.message)).append("</p>");
			}
		}
		else if (pItem.eligible)
		{
			if (!pItem.detail.isEmpty())
			{
				pOut.append("<p class=\"mdmf-hint\">").append(ProjectDeletePage.esc(pItem.detail)).append("</p>");
			}
		}
		else
		{
			pOut.append("<p class=\"mdmf-hint\">").append(ProjectDeletePage.esc(pItem.reason)).append("</p>");
		}
		pOut.append("</li>");
	}

	private static String kindChipKind(final Item pItem)
	{
		if ("MATCHING_CONFIG".equals(pItem.kind) || "MATCHING_BUSINESS_OBJECT".equals(pItem.kind) || "DISTRIBUTION_TRIGGERS".equals(pItem.kind))
		{
			return "accent";
		}
		if ("LANDING_FLOW".equals(pItem.kind) || "DISTRIBUTION_FLOW".equals(pItem.kind))
		{
			return "info";
		}
		if (pItem.kind.endsWith("_DATASPACE"))
		{
			return "warn";
		}
		if ("CATALOG_SOURCE".equals(pItem.kind) || "CONNECTION".equals(pItem.kind))
		{
			return "accent";
		}
		return "muted";
	}

	private static String outcomeClass(final String pOutcome)
	{
		if ("DELETED".equals(pOutcome))
		{
			return "real";
		}
		if ("FAILED".equals(pOutcome))
		{
			return "bad";
		}
		if ("SKIPPED_ABSENT".equals(pOutcome) || "NOT_SELECTED".equals(pOutcome))
		{
			return "muted";
		}
		return "target";
	}

	private static List<Item> itemsOf(final Model pModel, final String pOption)
	{
		final List<Item> out = new ArrayList<Item>();
		for (final Item item : pModel.items)
		{
			if (pOption.equals(item.option))
			{
				out.add(item);
			}
		}
		return out;
	}

	private static boolean hasEligible(final List<Item> pItems)
	{
		for (final Item item : pItems)
		{
			if (item.eligible)
			{
				return true;
			}
		}
		return false;
	}

	// --------------------------------------------------------------------------------------------------------- misc

	static String esc(final String pText)
	{
		return FactoryHtml.esc(pText);
	}

	static String m(final Model pModel, final String pKey, final Object... pArgs)
	{
		return Messages.get(ProjectDeletePage.class, pModel.locale, pKey, pArgs);
	}

	static String e(final Model pModel, final String pKey, final Object... pArgs)
	{
		return ProjectDeletePage.esc(ProjectDeletePage.m(pModel, pKey, pArgs));
	}
}
