package com.on.ebx.factory.ebx.ui;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import com.on.ebx.factory.review.ReviewMessages;

/**
 * The HTML of the "Cockpit du projet" screen (mockup screen 3), from a {@link CockpitData.Snapshot} only
 * (no EBX object), so it renders from fixtures in {@code CockpitPageTest} — including every value's
 * escaping, the empty states, and both locales. Read-only: no button that does anything (this lot's brief),
 * so there is nothing here to post back.
 */
final class CockpitPage
{
	private CockpitPage()
	{
	}

	static final class Model
	{
		Locale locale = Locale.ENGLISH;
		CockpitData.Snapshot snapshot = new CockpitData.Snapshot();
		/** The instant the page is rendered at, for the "When" column's relative hint ("2 h ago"); injected, never read from the system clock here. */
		Date now = new Date();
		/** Wave 10 (audit F-38): the transfer-reject review screen of this project, offered next to the rejects block; empty = no link. */
		String rejectsUrl = "";
		/** Wave 10 (audit F-30): the project's backup download (administrators only), offered at the foot of the page; empty = no link. */
		String backupUrl = "";
	}

	static String esc(final String pText)
	{
		return FactoryHtml.esc(pText);
	}

	static String m(final Model pModel, final String pKey, final Object... pArgs)
	{
		return Messages.get(CockpitPage.class, pModel.locale, pKey, pArgs);
	}

	static String e(final Model pModel, final String pKey, final Object... pArgs)
	{
		return CockpitPage.esc(CockpitPage.m(pModel, pKey, pArgs));
	}

	static String render(final Model pModel)
	{
		final CockpitData.Snapshot s = pModel.snapshot;
		final StringBuilder sb = new StringBuilder(8192);
		sb.append("<div class=\"mdmf mdmf-cockpit\"><section class=\"mdmf-panel\"><h2>").append(CockpitPage.e(pModel, "cockpit.title", s.project.label))
			.append("</h2><p class=\"mdmf-lead\">").append(CockpitPage.e(pModel, "cockpit.lead")).append("</p>");
		CockpitPage.deliveryBanner(sb, pModel, s);
		CockpitPage.lineageLink(sb, pModel, s);

		CockpitPage.stats(sb, pModel, s);
		CockpitPage.pipeline(sb, pModel, s);
		CockpitPage.volumes(sb, pModel, s);
		CockpitPage.relations(sb, pModel, s);
		CockpitPage.agentRun(sb, pModel, s);
		CockpitPage.loads(sb, pModel, s);
		CockpitPage.rejects(sb, pModel, s);
		CockpitPage.quality(sb, pModel, s);
		CockpitPage.matching(sb, pModel, s);
		CockpitPage.alerts(sb, pModel, s);
		if (!pModel.backupUrl.isEmpty())
		{
			sb.append("<h3 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.backup.title")).append("</h3><p class=\"mdmf-hint\">")
				.append(CockpitPage.e(pModel, "cockpit.backup.lead")).append("</p><p><a class=\"mdmf-btn mdmf-btn-sm mdmf-leave-link\" href=\"")
				.append(CockpitPage.esc(pModel.backupUrl)).append("\">").append(CockpitPage.e(pModel, "cockpit.backup.download")).append("</a></p>");
		}

		sb.append("</section></div>");
		return sb.toString();
	}

	private static void stats(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		pOut.append("<div class=\"mdmf-stats\">");
		CockpitPage.stat(pOut, pModel, "", String.valueOf(pS.sourceCount), "cockpit.stat.sources");
		CockpitPage.stat(pOut, pModel, "", String.valueOf(pS.landingTableCount), "cockpit.stat.landingTables");
		CockpitPage.stat(pOut, pModel, "good", String.valueOf(pS.rowsLoaded), "cockpit.stat.rowsLoaded");
		CockpitPage.stat(pOut, pModel, "good", String.valueOf(pS.stagingRowsWritten), "cockpit.stat.stagingRows");
		CockpitPage.stat(pOut, pModel, pS.relationsTotal > 0 && pS.relationsAccepted == pS.relationsTotal ? "good" : "", pS.relationsAccepted + " / " + pS.relationsTotal,
			"cockpit.stat.relations");
		final int qualityRulesTotal = pS.qualityRulesAccepted + pS.qualityRulesPending;
		CockpitPage.stat(pOut, pModel, qualityRulesTotal > 0 && pS.qualityRulesAccepted == qualityRulesTotal ? "good" : "",
			pS.qualityRulesAccepted + " / " + qualityRulesTotal, "cockpit.stat.qualityRules");
		CockpitPage.stat(pOut, pModel, pS.rejects > 0 ? "warn" : "good", String.valueOf(pS.rejects), "cockpit.stat.rejects");
		CockpitPage.stat(pOut, pModel, pS.stagingEntityCount == 0 ? "warn" : "", String.valueOf(pS.stagingEntityCount), "cockpit.stat.stagingEntities");
		pOut.append("</div>");
	}

	private static void stat(final StringBuilder pOut, final Model pModel, final String pKind, final String pValue, final String pLabelKey)
	{
		pOut.append("<div").append(pKind.isEmpty() ? "" : " class=\"" + pKind + "\"").append("><b>").append(CockpitPage.esc(pValue)).append("</b><span>")
			.append(CockpitPage.e(pModel, pLabelKey)).append("</span></div>");
	}

	/**
	 * Delivery lot 2 ({@code docs/DELIVERY.md} sec 6.2): read-only, always at the top when the project was
	 * delivered here by EBX Staging ({@code delivery.OriginRule}) - same {@code .mdmf-notice} shape the rest
	 * of the Cockpit already uses ({@link #rejects}/{@link #alerts}), the new {@code -info} variant (never
	 * {@code -warn}: a delivered project is the normal, expected state on a TARGET instance, not a problem).
	 * The instance's own role is shown next to it - the Cockpit has no dedicated settings block to put it in,
	 * so it rides the same hint line, whether or not this particular project happens to be delivered.
	 */
	private static void deliveryBanner(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		if (pS.delivered)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-info\">").append(CockpitPage.e(pModel, "cockpit.delivered", pS.originRepository)).append("</div>");
		}
		if (!pS.instanceRole.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(CockpitPage.e(pModel, "cockpit.instanceRole." + pS.instanceRole)).append("</p>");
		}
	}

	/**
	 * Lot 4 (record lineage): a plain link to {@code RecordLineage}, pre-filled with this project's own
	 * code - the Cockpit's "obvious place" for it (task brief) without restructuring the screen: one line
	 * under the lead paragraph, before the read-only stats/tables the rest of this class already renders.
	 * A plain {@code <a>}, not a {@code .mdmf-btn} (Cockpit loads only {@code factory/factory.css}, never
	 * {@code review.css}, which is the only place {@code .mdmf-btn} is declared - CLAUDE.md's own rule
	 * against nesting a second EBX shell does not apply here either way, since this navigates the TOP
	 * window like any ordinary link, never {@code buildButtonPreview}'s modal iframe). No entity/key: the
	 * Cockpit has no single record in view, only the project - RecordLineage's own header form completes
	 * the rest. UTF-8 URL-encoded (a project code is free text, CLAUDE.md's own rule against putting
	 * unencoded values in a URL).
	 */
	private static void lineageLink(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		pOut.append("<p class=\"mdmf-hint\">").append(CockpitPage.e(pModel, "cockpit.lineage.hint")).append(" <a class=\"mdmf-leave-link\" href=\"")
			.append(CockpitPage.esc(CockpitPage.lineageUrl(pS.project.code))).append("\" target=\"_top\">").append(CockpitPage.e(pModel, "cockpit.lineage.link"))
			.append("</a></p>");
	}

	static String lineageUrl(final String pProjectCode)
	{
		return "/ebx/?branch=MdmFactory&instance=MdmFactory&xpath=/root/Project&service=RecordLineage&project=" + CockpitPage.urlEncode(pProjectCode);
	}

	private static String urlEncode(final String pValue)
	{
		try
		{
			return URLEncoder.encode(pValue == null ? "" : pValue, "UTF-8");
		}
		catch (final UnsupportedEncodingException ex)
		{
			// UTF-8 is always supported - unreachable in practice, kept only so this stays a checked-exception-free call site
			return pValue == null ? "" : pValue;
		}
	}

	private static void pipeline(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.pipeline.title")).append("</h3>");
		if (pS.pipeline.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(CockpitPage.e(pModel, "cockpit.pipeline.none")).append("</p>");
			return;
		}
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(CockpitPage.e(pModel, "cockpit.pipeline.step"))
			.append("</th><th>").append(CockpitPage.e(pModel, "cockpit.pipeline.status")).append("</th><th>").append(CockpitPage.e(pModel, "cockpit.pipeline.when"))
			.append("</th><th>").append(CockpitPage.e(pModel, "cockpit.pipeline.detail")).append("</th></tr></thead><tbody>");
		for (final CockpitData.SourceBlock block : pS.pipeline)
		{
			final String groupLabel = block.project ? CockpitPage.m(pModel, "cockpit.pipeline.group.project") : block.sourceSystem;
			pOut.append("<tr class=\"mdmf-table-group\"><th colspan=\"4\">").append(CockpitPage.esc(groupLabel)).append("</th></tr>");
			if (block.steps.isEmpty())
			{
				pOut.append("<tr><td colspan=\"4\" class=\"mdmf-hint\">").append(CockpitPage.e(pModel, "cockpit.pipeline.sourceNone")).append("</td></tr>");
				continue;
			}
			for (final CockpitData.StepRow step : block.steps)
			{
				pOut.append("<tr><td>").append(CockpitPage.esc(StepLabels.of(pModel.locale, step.step)));
				if (step.runs > 1)
				{
					pOut.append(' ').append(CockpitPage.runsBadge(pModel, step.runs));
				}
				pOut.append("</td><td>").append(CockpitPage.statusChip(pModel, step.status));
				if (step.stuck)
				{
					pOut.append(' ').append(CockpitPage.chipWithTitle("warn", CockpitPage.m(pModel, "cockpit.pipeline.stuck"),
						CockpitPage.m(pModel, "cockpit.pipeline.stuck.title")));
				}
				pOut.append("</td><td>").append(CockpitPage.when(pModel, step)).append("</td><td>")
					.append(step.message.isEmpty() ? "" : CockpitPage.esc(step.message)).append("</td></tr>");
			}
		}
		pOut.append("</tbody></table></div>");
	}

	/** The "When" cell: the latest run's own end time (relative, with the absolute time as a tooltip), or, while
	 * that run is still going, how long it has been running for. An em dash only when neither time is known. */
	private static String when(final Model pModel, final CockpitData.StepRow pStep)
	{
		if (pStep.endedAt != null)
		{
			return CockpitPage.relativeWithTitle(pModel, pStep.endedAt);
		}
		if (pStep.startedAt != null)
		{
			return CockpitPage.e(pModel, "cockpit.pipeline.when.running", CockpitPage.relative(pModel, pStep.startedAt));
		}
		return "—";
	}

	private static String relativeWithTitle(final Model pModel, final Date pAt)
	{
		final DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, pModel.locale);
		return "<span title=\"" + CockpitPage.esc(format.format(pAt)) + "\">" + CockpitPage.esc(CockpitPage.relative(pModel, pAt)) + "</span>";
	}

	/**
	 * A short, locale-aware "how long ago" phrase (never negative: a clock a little ahead of {@code pAt}, e.g.
	 * the record was just written, reads as "just now" rather than a nonsensical negative duration). Pure: only
	 * {@code pModel.now} and {@code pAt} are read, never the system clock.
	 */
	static String relative(final Model pModel, final Date pAt)
	{
		final long diffMs = Math.max(0L, pModel.now.getTime() - pAt.getTime());
		final long minutes = diffMs / 60000L;
		if (minutes < 1L)
		{
			return CockpitPage.m(pModel, "cockpit.when.now");
		}
		if (minutes < 60L)
		{
			return CockpitPage.m(pModel, "cockpit.when.minutes", Long.valueOf(minutes));
		}
		final long hours = minutes / 60L;
		if (hours < 24L)
		{
			return CockpitPage.m(pModel, "cockpit.when.hours", Long.valueOf(hours));
		}
		return CockpitPage.m(pModel, "cockpit.when.days", Long.valueOf(hours / 24L));
	}

	private static String runsBadge(final Model pModel, final int pRuns)
	{
		return CockpitPage.chipWithTitle("accent", "×" + pRuns, CockpitPage.m(pModel, "cockpit.pipeline.runs.title", Integer.valueOf(pRuns)));
	}

	private static void volumes(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.volumes.title")).append("</h3>");
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(CockpitPage.e(pModel, "cockpit.volumes.zone"))
			.append("</th><th>").append(CockpitPage.e(pModel, "cockpit.volumes.table")).append("</th><th>").append(CockpitPage.e(pModel, "cockpit.volumes.rows"))
			.append("</th><th>").append(CockpitPage.e(pModel, "cockpit.volumes.status")).append("</th></tr></thead><tbody>");
		String lastZone = null;
		for (final CockpitData.VolumeRow row : pS.volumes)
		{
			final boolean newZone = !row.zone.equals(lastZone);
			lastZone = row.zone;
			pOut.append("<tr>").append(newZone ? "<td>" + CockpitPage.e(pModel, "cockpit.volumes.zone." + row.zone) + "</td>" : "<td></td>");
			pOut.append("<td>").append(CockpitPage.volumeTable(pModel, row)).append(CockpitPage.volumeDetail(pModel, row)).append("</td><td class=\"num\">")
				.append(row.rows == null ? "—" : CockpitPage.esc(String.valueOf(row.rows))).append("</td><td>").append(CockpitPage.volumeStatus(pModel, row))
				.append("</td></tr>");
		}
		pOut.append("</tbody></table></div>");
	}

	private static String volumeTable(final Model pModel, final CockpitData.VolumeRow pRow)
	{
		if (pRow.table != null)
		{
			return CockpitPage.esc(pRow.table);
		}
		if ("master".equals(pRow.zone))
		{
			return "master".equals(pRow.zone) && "TARGET_EXISTS".equals(pModel.snapshot.project.mode) ? CockpitPage.e(pModel, "cockpit.volumes.master.unnamed") : "—";
		}
		return "—";
	}

	private static String volumeStatus(final Model pModel, final CockpitData.VolumeRow pRow)
	{
		if ("landing".equals(pRow.zone))
		{
			return CockpitPage.statusChip(pModel, pRow.status);
		}
		if ("staging".equals(pRow.zone))
		{
			// NONE: no entity accepted yet at all. NOT_GENERATED: accepted, but Project.stagingDataset does not
			// exist yet. LOADED/GENERATED: real StagingEntity statuses, reuse the same status.* chip as everywhere else.
			if ("NONE".equals(pRow.status))
			{
				return CockpitPage.chip(pModel, "target", "cockpit.volumes.status.notProposed");
			}
			if ("NOT_GENERATED".equals(pRow.status))
			{
				return CockpitPage.chip(pModel, "target", "cockpit.volumes.status.notGenerated");
			}
			return CockpitPage.statusChip(pModel, pRow.status);
		}
		// master: always reported empty in this lot; the wording differs only by whether a target model exists
		return "TARGET_EXISTS".equals(pModel.snapshot.project.mode) ? CockpitPage.chip(pModel, "target", "cockpit.master.targetExists") : CockpitPage.chip(pModel, "target",
			"cockpit.master.none");
	}

	/** The small hint line under a staging entity's name: which systems feed it, when it last loaded, how many
	 * rows were rejected at that load — whichever of the three apply. Empty string (nothing appended) for a
	 * landing or master row, or a staging row with none of this to say (e.g. the honest empty state). */
	private static String volumeDetail(final Model pModel, final CockpitData.VolumeRow pRow)
	{
		final StringBuilder detail = new StringBuilder();
		if (pRow.memberSummary != null && !pRow.memberSummary.isEmpty())
		{
			CockpitPage.appendDetailPart(detail, CockpitPage.e(pModel, "cockpit.volumes.fedBy", pRow.memberSummary));
		}
		if (pRow.lastAt != null)
		{
			// same composition as the relation evidence's "checked ..." text: the key ends without a placeholder,
			// and the tooltip-bearing time span (already its own safe, escaped HTML) is appended separately -
			// passing it as a MessageFormat argument would have the final e() escape re-mangle its own <span> tag
			CockpitPage.appendDetailPart(detail, CockpitPage.e(pModel, "cockpit.volumes.lastLoad") + " " + CockpitPage.relativeWithTitle(pModel, pRow.lastAt));
		}
		if (pRow.rejected != null)
		{
			CockpitPage.appendDetailPart(detail, CockpitPage.e(pModel, "cockpit.volumes.rejectedCount", String.valueOf(pRow.rejected)));
		}
		return detail.length() == 0 ? "" : "<br/><span class=\"mdmf-hint\">" + detail + "</span>";
	}

	private static void appendDetailPart(final StringBuilder pDetail, final String pPart)
	{
		if (pDetail.length() > 0)
		{
			pDetail.append(" · ");
		}
		pDetail.append(pPart);
	}

	private static void relations(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.relations.title")).append("</h3>");
		if (pS.relations.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(CockpitPage.e(pModel, "cockpit.relations.none")).append("</p>");
			return;
		}
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(CockpitPage.e(pModel, "cockpit.relations.col.relation"))
			.append("</th><th>").append(CockpitPage.e(pModel, "cockpit.relations.col.status")).append("</th><th>")
			.append(CockpitPage.e(pModel, "cockpit.relations.col.evidence")).append("</th></tr></thead><tbody>");
		for (final CockpitData.RelationEvidence relation : pS.relations)
		{
			pOut.append("<tr><td>").append(CockpitPage.esc(relation.fromTable)).append(" → ").append(CockpitPage.esc(relation.toTable)).append("</td><td>")
				.append(CockpitPage.statusChip(pModel, relation.status)).append("</td><td>").append(CockpitPage.evidence(pModel, relation)).append("</td></tr>");
		}
		pOut.append("</tbody></table></div>");
	}

	private static String evidence(final Model pModel, final CockpitData.RelationEvidence pRelation)
	{
		if (pRelation.checkedAt == null)
		{
			return CockpitPage.e(pModel, "cockpit.relations.never");
		}
		final String childValues = pRelation.childValues == null ? "0" : String.valueOf(pRelation.childValues);
		final String orphanValues = pRelation.orphanValues == null ? "0" : String.valueOf(pRelation.orphanValues);
		return CockpitPage.e(pModel, "cockpit.relations.evidence", childValues, orphanValues) + " " + CockpitPage.relativeWithTitle(pModel, pRelation.checkedAt);
	}

	/**
	 * Task C's compact quality block: one line per entity that has at least one QualityRule (accepted/pending
	 * counts, failing rows at the last load) - a plain, non-actionable hint points to Proposal Review filtered
	 * to Quality for the details, same "read-only, no button that does anything" posture as the rest of this
	 * screen (the class Javadoc) rather than an unverified cross-service deep link.
	 */
	private static void quality(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.quality.title")).append("</h3>");
		if (pS.quality.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(CockpitPage.e(pModel, "cockpit.quality.none")).append("</p>");
			return;
		}
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(CockpitPage.e(pModel, "cockpit.quality.col.entity"))
			.append("</th><th>").append(CockpitPage.e(pModel, "cockpit.quality.col.accepted")).append("</th><th>")
			.append(CockpitPage.e(pModel, "cockpit.quality.col.pending")).append("</th><th>").append(CockpitPage.e(pModel, "cockpit.quality.col.failing"))
			.append("</th></tr></thead><tbody>");
		for (final CockpitData.QualityEntitySummary row : pS.quality)
		{
			pOut.append("<tr><td>").append(CockpitPage.esc(row.entityName)).append("</td><td class=\"num\">").append(row.accepted).append("</td><td class=\"num\">")
				.append(row.pending).append("</td><td class=\"num\">")
				.append(row.lastLoadFailing > 0 ? "<span class=\"mdmf-chip mdmf-chip-warn\">" + row.lastLoadFailing + "</span>" : String.valueOf(row.lastLoadFailing))
				.append("</td></tr>");
		}
		pOut.append("</tbody></table></div><p class=\"mdmf-hint\">").append(CockpitPage.e(pModel, "cockpit.quality.hint")).append("</p>");
	}

	/**
	 * Reject/repropose lot (2026-09-22): one line per entity that has a {@code MatchPlan} row at all - a plain
	 * status chip, and for a {@code REJECTED} one ("No matching for this entity", the review screen's own
	 * action) the reviewer's own comment inline, so the reason an entity is left out of matching is visible
	 * without leaving the Cockpit - same read-only, no-button posture as {@link #quality}.
	 */
	private static void matching(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.matching.title")).append("</h3>");
		if (pS.matching.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(CockpitPage.e(pModel, "cockpit.matching.none")).append("</p>");
			return;
		}
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(CockpitPage.e(pModel, "cockpit.matching.col.entity"))
			.append("</th><th>").append(CockpitPage.e(pModel, "cockpit.matching.col.status")).append("</th></tr></thead><tbody>");
		for (final CockpitData.MatchEntitySummary row : pS.matching)
		{
			pOut.append("<tr><td>").append(CockpitPage.esc(row.entityName)).append("</td><td>");
			if ("REJECTED".equals(row.status))
			{
				pOut.append("<span class=\"mdmf-chip mdmf-chip-warn\">").append(CockpitPage.e(pModel, "cockpit.matching.excludedByDecision")).append("</span>");
				if (row.comment != null && !row.comment.isEmpty())
				{
					pOut.append("<p class=\"mdmf-hint\">").append(CockpitPage.esc(row.comment)).append("</p>");
				}
			}
			else
			{
				// reuses the SAME review.status.* labels the Proposal Review screen shows (ReviewMessages, pure,
				// no EBX) - one status vocabulary, never a second copy of the wording.
				pOut.append(CockpitPage.esc(ReviewMessages.get(pModel.locale, "review.status." + row.status)));
			}
			pOut.append("</td></tr>");
		}
		pOut.append("</tbody></table></div>");
	}

	private static void agentRun(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.agent.title")).append("</h3>");
		final CockpitData.AgentRunInfo run = pS.lastAgentRun;
		if (run == null)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(CockpitPage.e(pModel, "cockpit.agent.none")).append("</p>");
			return;
		}
		final String statusLabel = CockpitPage.m(pModel, "status." + run.status);
		final String steps = run.steps == null ? "—" : String.valueOf(run.steps);
		final String inputTokens = run.inputTokens == null ? "—" : String.valueOf(run.inputTokens);
		final String outputTokens = run.outputTokens == null ? "—" : String.valueOf(run.outputTokens);
		pOut.append("<p class=\"mdmf-hint\">")
			.append(CockpitPage.e(pModel, "cockpit.agent.line", run.phase.isEmpty() ? "—" : run.phase, statusLabel, steps, inputTokens, outputTokens))
			.append("</p>");
	}

	private static void loads(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.loads.title")).append("</h3>");
		if (pS.loads.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(CockpitPage.e(pModel, "cockpit.loads.none")).append("</p>");
			return;
		}
		final DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, pModel.locale);
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(CockpitPage.e(pModel, "cockpit.loads.table")).append("</th><th>")
			.append(CockpitPage.e(pModel, "cockpit.loads.loadId")).append("</th><th>").append(CockpitPage.e(pModel, "cockpit.loads.rows")).append("</th><th>")
			.append(CockpitPage.e(pModel, "cockpit.loads.at")).append("</th><th>").append(CockpitPage.e(pModel, "cockpit.loads.status")).append("</th></tr></thead><tbody>");
		for (final CockpitData.LoadRow row : pS.loads)
		{
			pOut.append("<tr><td>").append(CockpitPage.esc(row.table)).append("</td><td><code>").append(CockpitPage.esc(row.loadId)).append("</code></td><td class=\"num\">")
				.append(row.loadedRows).append(row.sourceRows == null ? "" : " / " + row.sourceRows).append("</td><td>")
				.append(row.loadedAt == null ? "—" : CockpitPage.esc(format.format(row.loadedAt))).append("</td><td>").append(CockpitPage.statusChip(pModel, row.status))
				.append("</td></tr>");
		}
		pOut.append("</tbody></table></div>");
	}

	private static void rejects(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.rejects.title")).append("</h3>");
		if (pS.latestRejects.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(CockpitPage.e(pModel, "cockpit.rejects.none")).append("</div>");
			return;
		}
		if (!pModel.rejectsUrl.isEmpty())
		{
			pOut.append("<p><a class=\"mdmf-btn mdmf-btn-sm mdmf-leave-link\" href=\"").append(CockpitPage.esc(pModel.rejectsUrl)).append("\" target=\"_top\">")
				.append(CockpitPage.e(pModel, "cockpit.rejects.review")).append("</a></p>");
		}
		if (pS.rejectSummaryPartial)
		{
			// robustness audit F2-105: the aggregate failed, the breakdown below only covers the latest rejects
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(CockpitPage.e(pModel, "cockpit.rejects.summary.partial")).append("</div>");
		}
		if (pS.rejectSummaries.isEmpty())
		{
			// rejects exist, but every one of them is already resolved - still worth a calm word, not silence
			pOut.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(CockpitPage.e(pModel, "cockpit.rejects.summary.none")).append("</div>");
		}
		else
		{
			pOut.append("<h4 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.rejects.summary.title")).append("</h4>");
			pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(CockpitPage.e(pModel, "cockpit.rejects.summary.entity"))
				.append("</th><th>").append(CockpitPage.e(pModel, "cockpit.rejects.summary.stage")).append("</th><th>")
				.append(CockpitPage.e(pModel, "cockpit.rejects.summary.count")).append("</th></tr></thead><tbody>");
			for (final CockpitData.RejectSummary summary : pS.rejectSummaries)
			{
				pOut.append("<tr><td>").append(CockpitPage.esc(summary.entityName)).append("</td><td>").append(CockpitPage.e(pModel, "cockpit.rejects.stage." + summary.stage))
					.append("</td><td class=\"num\">").append(summary.unresolvedCount).append("</td></tr>");
			}
			pOut.append("</tbody></table></div>");
		}

		pOut.append("<h4 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.rejects.latest.title")).append("</h4>");
		final DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, pModel.locale);
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(CockpitPage.e(pModel, "cockpit.rejects.latest.entity"))
			.append("</th><th>").append(CockpitPage.e(pModel, "cockpit.rejects.latest.stage")).append("</th><th>")
			.append(CockpitPage.e(pModel, "cockpit.rejects.latest.source")).append("</th><th>").append(CockpitPage.e(pModel, "cockpit.rejects.latest.reasons"))
			.append("</th><th>").append(CockpitPage.e(pModel, "cockpit.rejects.latest.at")).append("</th><th>")
			.append(CockpitPage.e(pModel, "cockpit.rejects.latest.status")).append("</th></tr></thead><tbody>");
		for (final CockpitData.RejectInfo reject : pS.latestRejects)
		{
			// sourceId is an identifier of the rejected row, shown as such - never the row's own field values, which
			// this table never reads in the first place (TransferReject only ever stores identifiers and engine text)
			pOut.append("<tr><td>").append(CockpitPage.esc(reject.entityName)).append("</td><td>").append(CockpitPage.e(pModel, "cockpit.rejects.stage." + reject.stage))
				.append("</td><td><code>").append(CockpitPage.esc(reject.sourceSystem)).append(':').append(CockpitPage.esc(reject.sourceId)).append("</code></td><td>")
				.append(reject.reasons.isEmpty() ? "" : CockpitPage.esc(reject.reasons)).append("</td><td>")
				.append(reject.rejectedAt == null ? "—" : CockpitPage.esc(format.format(reject.rejectedAt))).append("</td><td>")
				.append(CockpitPage.chip(pModel, reject.resolved ? "real" : reject.acknowledged ? "info" : "warn",
					reject.resolved ? "cockpit.rejects.resolved" : reject.acknowledged ? "cockpit.rejects.acknowledged" : "cockpit.rejects.unresolved"))
				.append("</td></tr>");
		}
		pOut.append("</tbody></table></div>");
	}

	private static void alerts(final StringBuilder pOut, final Model pModel, final CockpitData.Snapshot pS)
	{
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(CockpitPage.e(pModel, "cockpit.alerts.title")).append("</h3>");
		if (pS.alerts.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(CockpitPage.e(pModel, "cockpit.alerts.none")).append("</div>");
			return;
		}
		for (final CockpitData.Alert alert : pS.alerts)
		{
			final String statusLabel = CockpitPage.m(pModel, "status." + alert.status);
			final String message = alert.message.isEmpty() ? CockpitPage.m(pModel, "cockpit.alerts.noMessage") : alert.message;
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">⚠ ").append(CockpitPage.e(pModel, "cockpit.alerts.line", alert.table, statusLabel, message))
				.append("</div>");
		}
	}

	// ------------------------------------------------------------------ status chips

	private static String statusChip(final Model pModel, final String pStatus)
	{
		final String kind = CockpitPage.statusKind(pStatus);
		return CockpitPage.chip(pModel, kind, "status." + pStatus);
	}

	private static String statusKind(final String pStatus)
	{
		if ("DONE".equals(pStatus) || "LOADED".equals(pStatus))
		{
			return "real";
		}
		if ("FAILED".equals(pStatus) || "DRIFTED".equals(pStatus))
		{
			return "bad";
		}
		if ("RUNNING".equals(pStatus))
		{
			return "accent";
		}
		return "target";
	}

	private static String chip(final Model pModel, final String pKind, final String pKey)
	{
		return "<span class=\"mdmf-chip mdmf-chip-" + pKind + "\">" + CockpitPage.e(pModel, pKey) + "</span>";
	}

	/** Like {@link #chip}, but the content and the {@code title} tooltip are given as already-resolved raw text, not a message key. */
	private static String chipWithTitle(final String pKind, final String pText, final String pTitle)
	{
		return "<span class=\"mdmf-chip mdmf-chip-" + pKind + "\" title=\"" + CockpitPage.esc(pTitle) + "\">" + CockpitPage.esc(pText) + "</span>";
	}
}
