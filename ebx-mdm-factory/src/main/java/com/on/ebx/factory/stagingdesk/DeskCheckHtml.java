package com.on.ebx.factory.stagingdesk;

import java.util.Locale;

import com.on.ebx.factory.ebx.ui.FactoryHtml;
import com.on.ebx.factory.quality.FailureBreakdown;
import com.on.ebx.factory.quality.QualityDetectors;
import com.on.ebx.factory.review.ReviewLine;

/**
 * The check view of the Validation desk (docs/STAGING-DESK.md L4, mockup {@code Check.dc.html}): one quality rule shown on the values it would flag. Opened from a line of the
 * "Checks" group ({@code DeskHtml#renderChecksCentre} calls {@link #render}); "&larr; Validation desk" goes back to the list.
 * <p>
 * Three blocks: the title, the figures and the sentence of the check; the breakdown of the failing values by the other field the study found to separate them; the choices as
 * cards. Accept and Reject are the existing decisions on the {@code QualityRule} row ({@code ACCEPT_GUARD} / {@code REJECT_GUARD}, handled by the matrix' own handler); the one
 * new action is {@code ACCEPT_GUARD_AS_PROPOSED}, which the same handler runs to drop a proposed condition before accepting. {@code MEASURE_CHECK} queues the study of a rule that
 * was never broken down. Plain HTML string building, no script of its own - the page's functions {@code mdskDo} and {@code mdskOpenCheck} do the posting.
 */
public final class DeskCheckHtml
{
	/** Posted by "Accept as proposed" of a rule that carries a proposed condition: the condition is dropped, then the rule is accepted. */
	public static final String ACCEPT_AS_PROPOSED = "ACCEPT_GUARD_AS_PROPOSED";
	/** Posted by "Measure": queues the breakdown of the rule as a background job. */
	public static final String MEASURE = "MEASURE_CHECK";

	private DeskCheckHtml()
	{
	}

	/** {@code pIndex} is 1-based among the {@code pTotal} checks of the group that can be looked at. */
	public static void render(final StringBuilder pOut, final DeskCheck pCheck, final int pIndex, final int pTotal, final boolean pReadOnly, final Locale pLocale)
	{
		pOut.append("<div class=\"mdskCheckTop\"><a href=\"#\" class=\"mdskBack\" onclick=\"mdskOpenCheck('');return false\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.back")))
			.append("</a><span class=\"mdskDim mdskSmall\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.position", Integer.valueOf(pIndex), Integer.valueOf(pTotal), pCheck.entityName)))
			.append("</span></div>");
		DeskCheckHtml.renderHero(pOut, pCheck, pLocale);
		if (pCheck.alreadySafe())
		{
			DeskCheckHtml.renderSafe(pOut, pCheck, pReadOnly, pLocale);
			return;
		}
		pOut.append("<div class=\"mdskCheckCols\">");
		DeskCheckHtml.renderBreakdown(pOut, pCheck, pReadOnly, pLocale);
		DeskCheckHtml.renderChoices(pOut, pCheck, pReadOnly, pLocale);
		pOut.append("</div>");
	}

	/** The check in words: what is tested on which field. */
	static String title(final DeskCheck pCheck, final Locale pLocale)
	{
		final String field = pCheck.attributeName.isEmpty() ? pCheck.entityName : pCheck.attributeName;
		if (QualityDetectors.PATTERN.equals(pCheck.kind))
		{
			return DeskMessages.get(pLocale, "desk.l4.title.pattern", field, pCheck.argument);
		}
		if (QualityDetectors.REFERENCE.equals(pCheck.kind))
		{
			return DeskMessages.get(pLocale, "desk.l4.title.reference", field, pCheck.argument);
		}
		if (QualityDetectors.CHECK_DIGIT.equals(pCheck.kind))
		{
			return DeskMessages.get(pLocale, "desk.l4.title.checkDigit", field, pCheck.argument);
		}
		if (QualityDetectors.MANDATORY.equals(pCheck.kind))
		{
			return DeskMessages.get(pLocale, "desk.l4.title.mandatory", field);
		}
		if (QualityDetectors.UNIQUE.equals(pCheck.kind))
		{
			return DeskMessages.get(pLocale, "desk.l4.title.unique", field);
		}
		return DeskMessages.get(pLocale, "desk.l4.title.other", field, pCheck.kind);
	}

	private static void renderHero(final StringBuilder pOut, final DeskCheck pCheck, final Locale pLocale)
	{
		pOut.append("<section class=\"mdskCard mdskHero\"><span class=\"mdskEyebrow\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.eyebrow"))).append("</span><h1>")
			.append(DeskCheckHtml.esc(DeskCheckHtml.title(pCheck, pLocale))).append("</h1>");
		if (pCheck.measured())
		{
			pOut.append("<p class=\"mdskLead\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.lead", DeskCheckHtml.severityWord(pCheck, pLocale), Long.valueOf(pCheck.passingCount()),
				Long.valueOf(pCheck.seenCount())))).append("</p>");
		}
		if (!pCheck.rationale.isEmpty())
		{
			pOut.append("<p class=\"mdskLead\">").append(DeskCheckHtml.esc(pCheck.rationale)).append("</p>");
		}
		pOut.append("<div class=\"mdskChips\">");
		if (!pCheck.measured())
		{
			pOut.append("<span class=\"mdskChip mut\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.chip.notMeasured"))).append("</span>");
		}
		else
		{
			final String clean = pCheck.cleanGroups();
			pOut.append("<span class=\"mdskChip good\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.chip.pass", Long.valueOf(pCheck.passingCount()))));
			if (!clean.isEmpty())
			{
				pOut.append(" · ").append(DeskCheckHtml.esc(clean));
			}
			pOut.append("</span>");
			if (pCheck.failingCount() > 0)
			{
				pOut.append("<span class=\"mdskChip warn\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.chip.flagged", Long.valueOf(pCheck.failingCount()))));
				if (pCheck.failingGroups() > 0 && pCheck.breakdown() != null)
				{
					pOut.append(" · ").append(DeskCheckHtml.esc(DeskMessages.count(pLocale, pCheck.failingGroups(), "group")));
				}
				pOut.append("</span>");
			}
		}
		pOut.append("</div></section>");
	}

	private static String severityWord(final DeskCheck pCheck, final Locale pLocale)
	{
		return DeskMessages.get(pLocale, QualityDetectors.REJECT.equals(pCheck.severity) ? "desk.l4.severity.reject" : "desk.l4.severity.warn");
	}

	// ----------------------------------------------------------------------------------------------------- the breakdown

	private static void renderBreakdown(final StringBuilder pOut, final DeskCheck pCheck, final boolean pReadOnly, final Locale pLocale)
	{
		pOut.append("<section class=\"mdskCard mdskCheckLeft\">");
		final FailureBreakdown.Result breakdown = pCheck.breakdown();
		if (breakdown != null)
		{
			pOut.append("<span class=\"mdskEyebrow\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.table.title", Long.valueOf(pCheck.failingCount()), breakdown.attribute)))
				.append("</span><table class=\"mdskTable mdskSpaced\"><thead><tr><th>").append(DeskCheckHtml.esc(breakdown.attribute)).append("</th><th>")
				.append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.col.example"))).append("</th><th>").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.col.values")))
				.append("</th><th>").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.col.flagged"))).append("</th></tr></thead><tbody>");
			for (final FailureBreakdown.Group group : breakdown.groups)
			{
				pOut.append("<tr").append(group.failing == 0 ? " class=\"mdskQuietRow\"" : "").append("><td><strong>").append(DeskCheckHtml.esc(group.value)).append("</strong></td><td><code>")
					.append(DeskCheckHtml.esc(group.examples.isEmpty() ? "" : group.examples.get(0))).append("</code></td><td>").append(group.seen).append("</td><td>").append(group.failing)
					.append("</td></tr>");
			}
			pOut.append("</tbody></table><p class=\"mdskDim mdskSmall mdskNote\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.table.note"))).append("</p>");
		}
		else if (pCheck.breakdownMeasured())
		{
			pOut.append("<span class=\"mdskEyebrow\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.none.title"))).append("</span><p class=\"mdskLead\">")
				.append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.none.text"))).append("</p>");
		}
		else
		{
			pOut.append("<span class=\"mdskEyebrow\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.unmeasured.title"))).append("</span><p class=\"mdskLead\">");
			if (!pCheck.breakable())
			{
				pOut.append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.unmeasured.kind")));
			}
			else
			{
				pOut.append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.unmeasured.text")));
			}
			pOut.append("</p>");
			if (pCheck.canMeasure())
			{
				if (pCheck.measuring)
				{
					pOut.append("<span class=\"mdskChip mut\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.measuring"))).append("</span>");
				}
				else
				{
					pOut.append("<button type=\"button\" class=\"mdskBtn mdskBtnPrimary\"").append(pReadOnly ? " disabled=\"disabled\"" : "").append(" onclick=\"mdskDo(")
						.append(DeskCheckHtml.jsAttr(MEASURE)).append(',').append(DeskCheckHtml.jsAttr(pCheck.ruleId)).append(",'',false)\">")
						.append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.btn.measure"))).append("</button>");
				}
			}
		}
		if (pCheck.measuring && (breakdown != null || pCheck.breakdownMeasured()))
		{
			// desk lot L4b: the breakdown is stored, the model is still being asked about a condition
			pOut.append("<span class=\"mdskChip mut\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.measuring"))).append("</span>");
		}
		pOut.append("</section>");
	}

	// ------------------------------------------------------------------------------------------------------ the choices

	private static void renderChoices(final StringBuilder pOut, final DeskCheck pCheck, final boolean pReadOnly, final Locale pLocale)
	{
		pOut.append("<div class=\"mdskChoices\"><span class=\"mdskEyebrow\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.choice"))).append("</span>");
		final boolean conditioned = pCheck.hasCondition();
		if (conditioned)
		{
			final long[] within = pCheck.withinCondition();
			final String values = String.join(", ", pCheck.conditionValues);
			final String text = within == null ? DeskMessages.get(pLocale, "desk.l4.cond.text.unknown", pCheck.conditionAttribute, values)
				: DeskMessages.get(pLocale, "desk.l4.cond.text", pCheck.conditionAttribute, values, Long.valueOf(within[0]), Long.valueOf(within[1]));
			DeskCheckHtml.card(pOut, true, DeskMessages.get(pLocale, "desk.l4.cond.title", pCheck.conditionAttribute, values), text, DeskMessages.get(pLocale, "desk.l4.cond.btn", values), true,
				"ACCEPT_GUARD", pCheck.ruleId, ReviewLine.QUALITY, pReadOnly);
		}
		DeskCheckHtml.card(pOut, !conditioned, DeskMessages.get(pLocale, "desk.l4.asis.title"), DeskMessages.get(pLocale, "desk.l4.asis.text", Long.valueOf(pCheck.flaggedAsProposed())),
			DeskMessages.get(pLocale, conditioned ? "desk.l4.asis.btn" : "desk.l4.asis.btnPlain"), !conditioned, conditioned ? ACCEPT_AS_PROPOSED : "ACCEPT_GUARD", pCheck.ruleId, ReviewLine.QUALITY,
			pReadOnly);
		DeskCheckHtml.card(pOut, false, DeskMessages.get(pLocale, "desk.l4.reject.title", pCheck.attributeName), DeskMessages.get(pLocale, "desk.l4.reject.text"),
			DeskMessages.get(pLocale, "desk.btn.reject"), false, "REJECT_GUARD", pCheck.ruleId, ReviewLine.QUALITY, pReadOnly);
		pOut.append("</div>");
	}

	private static void card(final StringBuilder pOut, final boolean pHighlight, final String pTitle, final String pText, final String pButton, final boolean pPrimary, final String pAction,
		final String pId, final String pId2, final boolean pReadOnly)
	{
		pOut.append("<div class=\"mdskChoice").append(pHighlight ? " on" : "").append("\"><strong>").append(DeskCheckHtml.esc(pTitle)).append("</strong><p>").append(DeskCheckHtml.esc(pText))
			.append("</p><button type=\"button\" class=\"mdskBtn").append(pPrimary ? " mdskBtnPrimary" : "").append("\"").append(pReadOnly ? " disabled=\"disabled\"" : "").append(" onclick=\"mdskDo(")
			.append(DeskCheckHtml.jsAttr(pAction)).append(',').append(DeskCheckHtml.jsAttr(pId)).append(',').append(DeskCheckHtml.jsAttr(pId2)).append(",false)\">")
			.append(DeskCheckHtml.esc(pButton)).append("</button></div>");
	}

	/** A rule that passes everything: nothing to condition. */
	private static void renderSafe(final StringBuilder pOut, final DeskCheck pCheck, final boolean pReadOnly, final Locale pLocale)
	{
		final String disabled = pReadOnly ? " disabled=\"disabled\"" : "";
		pOut.append("<section class=\"mdskCard mdskSafe\"><div><span class=\"mdskChip good\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.safe"))).append("</span> <strong>")
			.append(DeskCheckHtml.esc(DeskCheckHtml.title(pCheck, pLocale))).append("</strong> <span class=\"mdskDim\">").append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.l4.safe.figures",
				Long.valueOf(pCheck.passingCount()), Long.valueOf(pCheck.seenCount())))).append("</span></div><div class=\"mdskBarBtns\"><button type=\"button\" class=\"mdskBtn mdskBtnPrimary\"")
			.append(disabled).append(" onclick=\"mdskDo('ACCEPT_GUARD',").append(DeskCheckHtml.jsAttr(pCheck.ruleId)).append(",'").append(ReviewLine.QUALITY).append("',false)\">")
			.append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.btn.accept"))).append("</button><button type=\"button\" class=\"mdskBtn\"").append(disabled)
			.append(" onclick=\"mdskDo('REJECT_GUARD',").append(DeskCheckHtml.jsAttr(pCheck.ruleId)).append(",'").append(ReviewLine.QUALITY).append("',false)\">")
			.append(DeskCheckHtml.esc(DeskMessages.get(pLocale, "desk.btn.reject"))).append("</button></div></section>");
	}

	/** The style of this view, appended to the page's own. */
	static void renderStyle(final StringBuilder pOut)
	{
		pOut.append("#mdsk .mdskCheckTop{display:flex;justify-content:space-between;align-items:center}#mdsk .mdskBack{font-size:12.5px;font-weight:600;color:var(--accent-fg);text-decoration:none}")
			.append("#mdsk .mdskChips{display:flex;gap:10px;margin-top:14px;flex-wrap:wrap}#mdsk .mdskCheckCols{display:flex;gap:16px;align-items:flex-start}")
			.append("#mdsk .mdskCheckLeft{flex:1;min-width:0}#mdsk .mdskSpaced{margin-top:8px}#mdsk .mdskNote{margin:10px 0 0}#mdsk .mdskQuietRow td{color:#667085}")
			.append("#mdsk .mdskChoices{width:340px;flex:none;display:flex;flex-direction:column;gap:10px}#mdsk .mdskChoice{background:#fff;border:1px solid #e4e7ec;border-radius:12px;padding:14px}")
			.append("#mdsk .mdskChoice.on{border:2px solid var(--accent)}#mdsk .mdskChoice strong{font-size:13.5px}#mdsk .mdskChoice p{margin:6px 0 10px;font-size:12px;color:#475467;line-height:1.45}")
			.append("#mdsk .mdskChoice .mdskBtn{width:100%;box-sizing:border-box}#mdsk .mdskSafe{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:14px 18px;font-size:12.5px}");
	}

	private static String esc(final String pText)
	{
		return pText == null ? "" : pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}

	private static String jsAttr(final String pValue)
	{
		return ("'" + FactoryHtml.jsStringBody(pValue) + "'").replace("&", "&amp;").replace("\"", "&quot;");
	}
}
