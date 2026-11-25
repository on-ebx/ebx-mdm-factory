package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.staging.PreflightFinding;
import com.on.ebx.factory.staging.PreflightReport;

/**
 * Staging preflight lot H1 (2026-10-17): the report of the check before the build, as the block at the TOP of step 4 - in the viewer's language, built from the report's structured
 * fields (counts, ids, format names, pipelines), never from its English sentences. One line per link: "140 rows of LOYALTY would be refused: birthDate &lt;- date_naissance (dates
 * written dd/MM/yyyy, transformation isodate()) - fix it in the matrix", the proposed fix when the profile has one, and the buttons: "Apply the fix" (one link, or every fixable
 * link), "Build anyway" (blocking findings only: it sets the check aside) and "Check again". Warnings never carry "Build anyway": nothing stops the build. Pure; no EBX class.
 */
public final class PreflightHtml
{
	/** The bundle prefix of every key of this block. */
	public static final String P = "wizard.step4.preflight.";

	private PreflightHtml()
	{
	}

	/**
	 * @param pMatrixUrlByEntity the Mapping matrix link of each business object (entity name to URL); may be empty
	 * @param pCanAct whether the viewer may act (design right): without it the report is read, no button is drawn
	 * @return the block, or {@code ""} when the report is null or has nothing to say
	 */
	public static String render(final PreflightReport pReport, final Locale pLocale, final Map<String, String> pMatrixUrlByEntity, final boolean pCanAct)
	{
		if (pReport == null || pReport.clean())
		{
			return "";
		}
		final boolean blocking = pReport.blocking();
		final StringBuilder out = new StringBuilder();
		out.append("<div class=\"mdmf-notice").append(blocking ? " mdmf-notice-warn" : "").append(" mdmf-step4-preflight\" id=\"mdmf-step4-preflight\" data-mdmf-blocking=\"")
			.append(blocking).append("\">");
		if (blocking)
		{
			out.append("<b>").append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "title.blocked", WizardMessages.countWords(pLocale, pReport.blockingRows(), "wizard.unit.row"),
				WizardMessages.countWords(pLocale, pReport.blockingLinks(), P + "unit.link")))).append("</b> ");
			out.append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "lead.blocked")));
		}
		else
		{
			out.append("<b>").append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "title.warnings", WizardMessages.countWords(pLocale, pReport.warnings(), P + "unit.warning"))))
				.append("</b> ").append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "lead.warnings")));
		}
		out.append(" <span class=\"mdmf-hint mdmf-preflight-meta\">").append(PreflightHtml.esc(PreflightHtml.meta(pReport, pLocale))).append("</span>");

		for (final Map.Entry<String, List<PreflightFinding>> entity : pReport.byEntity().entrySet())
		{
			out.append("<div class=\"mdmf-preflight-entity\"><b>").append(PreflightHtml.esc(entity.getKey())).append("</b>");
			final String matrixUrl = pMatrixUrlByEntity == null ? null : pMatrixUrlByEntity.get(entity.getKey());
			out.append("<ul class=\"mdmf-preflight-list\">");
			for (final PreflightFinding finding : entity.getValue())
			{
				PreflightHtml.renderFinding(out, finding, pLocale, matrixUrl, pCanAct);
			}
			out.append("</ul></div>");
		}
		if (!pReport.derivationsWaiting.isEmpty())
		{
			out.append("<div class=\"mdmf-preflight-entity\"><ul class=\"mdmf-preflight-list\">");
			for (final PreflightReport.Waiting waiting : pReport.derivationsWaiting)
			{
				out.append("<li class=\"mdmf-preflight-warn\">").append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "issue.derivation", waiting.entityName, waiting.attributeName,
					String.join(", ", waiting.parameters)))).append("</li>");
			}
			out.append("</ul></div>");
		}
		if (!pReport.notes.isEmpty())
		{
			out.append("<details class=\"mdmf-disclosure mdmf-preflight-notes\"><summary>").append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "notes"))).append("</summary><ul>");
			for (final String note : pReport.notes)
			{
				out.append("<li>").append(PreflightHtml.esc(note)).append("</li>");
			}
			out.append("</ul></details>");
		}
		if (pCanAct)
		{
			out.append("<div class=\"mdmf-actions mdmf-preflight-actions\">");
			final int fixable = pReport.fixable().size();
			if (fixable > 1)
			{
				out.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"STAGING_PREFLIGHT_FIX\" data-mdmf-id=\"*\">")
					.append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "btn.fixAll", Integer.valueOf(fixable)))).append("</button>");
			}
			if (blocking)
			{
				out.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STAGING_LOAD_RUN_ANYWAY\">").append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "btn.anyway")))
					.append("</button>");
			}
			out.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STAGING_PREFLIGHT\">").append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "btn.recheck")))
				.append("</button>");
			if (blocking)
			{
				out.append("<span class=\"mdmf-hint\">").append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "hint.anyway"))).append("</span>");
			}
			out.append("</div>");
		}
		out.append("</div>");
		return out.toString();
	}

	private static String meta(final PreflightReport pReport, final Locale pLocale)
	{
		final java.text.DateFormat format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, pLocale == null ? Locale.ENGLISH : pLocale);
		final String checked = WizardMessages.countWords(pLocale, pReport.linksChecked, P + "unit.link");
		return pReport.linksNotChecked > 0
			? WizardMessages.get(pLocale, P + "meta.notChecked", format.format(new java.util.Date(pReport.at)), checked, WizardMessages.countWords(pLocale, pReport.linksNotChecked, P + "unit.link"))
			: WizardMessages.get(pLocale, P + "meta", format.format(new java.util.Date(pReport.at)), checked);
	}

	private static void renderFinding(final StringBuilder pOut, final PreflightFinding pFinding, final Locale pLocale, final String pMatrixUrl, final boolean pCanAct)
	{
		for (final PreflightFinding.Issue issue : pFinding.issues)
		{
			if (PreflightFinding.NOT_CHECKED.equals(issue.kind))
			{
				continue;
			}
			pOut.append("<li class=\"").append(issue.blocking ? "mdmf-preflight-block" : "mdmf-preflight-warn").append("\" data-mdmf-kind=\"").append(PreflightHtml.esc(issue.kind))
				.append("\">").append(PreflightHtml.esc(PreflightHtml.sentence(pFinding, issue, pLocale)));
			if (issue.blocking && PreflightHtml.fixAppliesTo(issue))
			{
				if (pFinding.fixable())
				{
					pOut.append(" <span class=\"mdmf-preflight-fix\">").append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "fix.proposal", pFinding.fixPipeline))).append("</span>");
				}
				else if (pFinding.ambiguousPipeline != null)
				{
					pOut.append(" <span class=\"mdmf-preflight-fix\">").append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "fix.ambiguous", pFinding.ambiguousPipeline))).append("</span>");
				}
			}
			if (issue.blocking && pCanAct)
			{
				pOut.append(" <span class=\"mdmf-preflight-links\">");
				if (pFinding.fixable() && PreflightHtml.fixAppliesTo(issue))
				{
					pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"STAGING_PREFLIGHT_FIX\" data-mdmf-id=\"")
						.append(PreflightHtml.esc(pFinding.mappingId)).append("\">").append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "btn.fix"))).append("</button> ");
				}
				if (pMatrixUrl != null && !pMatrixUrl.isEmpty())
				{
					pOut.append("<a class=\"mdmf-btn mdmf-btn-sm\" href=\"").append(PreflightHtml.esc(pMatrixUrl)).append("\" target=\"_top\">")
						.append(PreflightHtml.esc(WizardMessages.get(pLocale, P + "btn.matrix"))).append("</a>");
				}
				pOut.append("</span>");
			}
			pOut.append("</li>");
		}
	}

	/** A pipeline fix is for a conversion that fails; a value too long or a mandatory attribute left empty has none (map a column, make the attribute optional). */
	private static boolean fixAppliesTo(final PreflightFinding.Issue pIssue)
	{
		return !PreflightFinding.TOO_LONG.equals(pIssue.kind) && !PreflightFinding.MANDATORY_EMPTY.equals(pIssue.kind);
	}

	/** One issue in words, in the viewer's language: rows, source, attribute, column, how it is written, the transformation. Package-visible: the tests read it. */
	static String sentence(final PreflightFinding pFinding, final PreflightFinding.Issue pIssue, final Locale pLocale)
	{
		String rows = WizardMessages.countWords(pLocale, pIssue.rows, "wizard.unit.row");
		if (pIssue.atLeast)
		{
			rows = WizardMessages.get(pLocale, P + "atLeast", rows);
		}
		final String source = pFinding.sourceSystem.isEmpty() ? pFinding.tableName : pFinding.sourceSystem;
		final String what = PreflightHtml.parenthesis(pFinding, pLocale);
		final String key;
		switch (pIssue.kind)
		{
			case PreflightFinding.CONVERSION:
				key = "issue.conversion";
				break;
			case PreflightFinding.LOST:
				key = "issue.lost";
				break;
			case PreflightFinding.TOO_LONG:
				key = "issue.tooLong";
				break;
			case PreflightFinding.LOST_PLUS:
				key = "issue.lostPlus";
				break;
			case PreflightFinding.MANDATORY_EMPTY:
				key = "issue.mandatory";
				break;
			case PreflightFinding.DUPLICATES:
				key = "issue.duplicates";
				break;
			case PreflightFinding.NO_FEED:
				// lot M7: an entity-level warning - the entity is the whole sentence, no rows, source or column
				return WizardMessages.get(pLocale, P + "issue.noFeed", pFinding.entityName);
			case PreflightFinding.NOT_FED_BY_SOURCE:
				// lot M15: "Client.mobile: fed by ECOM and LOYALTY, not by STORES although STORES.TELEPHONE is filled (234 of 234) - map it or say why"
				return WizardMessages.get(pLocale, P + "issue.notFedBySource", pFinding.entityName + "." + pFinding.attributeName,
					PreflightHtml.andList(pFinding.fedBy, pLocale), source, pFinding.column, Long.valueOf(pIssue.rows), Long.valueOf(pIssue.ofRows));
			case PreflightFinding.SHAPE_OUTLIERS:
				// B2B test (2026-10-23): "Organisation.siren: 25 of 150 values from SAP.<column> do not have the shape 9(9) that SALESFORCE and WEB give it ..."
				return WizardMessages.get(pLocale, P + "issue.shapeOutliers", pFinding.entityName + "." + pFinding.attributeName, Long.valueOf(pIssue.rows),
					Long.valueOf(pIssue.ofRows), source, pFinding.column, pFinding.expectedShape, PreflightHtml.andList(pFinding.fedBy, pLocale));
			default:
				key = "issue.other";
				break;
		}
		return WizardMessages.get(pLocale, P + key, rows, source, pFinding.attributeName, pFinding.column, what);
	}

	/** {@code ECOM}, {@code ECOM and LOYALTY}, {@code A, B and C} - in the viewer's language. */
	static String andList(final List<String> pItems, final Locale pLocale)
	{
		if (pItems.size() <= 1)
		{
			return pItems.isEmpty() ? "" : pItems.get(0);
		}
		return String.join(", ", pItems.subList(0, pItems.size() - 1)) + WizardMessages.get(pLocale, P + "list.and") + pItems.get(pItems.size() - 1);
	}

	/** {@code dates written dd/MM/yyyy, transformation isodate()} - the two facts a person needs to see why. */
	private static String parenthesis(final PreflightFinding pFinding, final Locale pLocale)
	{
		final List<String> parts = new ArrayList<String>();
		if (!pFinding.written.isEmpty())
		{
			parts.add(WizardMessages.get(pLocale, P + ("number".equals(pFinding.writtenKind) ? "part.written.number" : "part.written.date"), pFinding.written));
		}
		parts.add(pFinding.pipeline.isEmpty() ? WizardMessages.get(pLocale, P + "part.noTransformation") : WizardMessages.get(pLocale, P + "part.transformation", pFinding.pipeline));
		return String.join(", ", parts);
	}

	private static String esc(final String pText)
	{
		return pText == null ? "" : pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}
}
