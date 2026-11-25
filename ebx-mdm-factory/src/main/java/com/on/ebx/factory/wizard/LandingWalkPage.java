package com.on.ebx.factory.wizard;

import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.landing.LandingKeyRules;

/**
 * Landing-walkthrough lot (2026-10-16, docs/mockups/landing-walkthrough/): the markup of step 3 as a walkthrough - the sub-rail, one page per application and the
 * launch page. Rules in {@link LandingWalk}; every button is a {@code data-mdmf-action} of {@code wizard.js}'s ordinary submit loop. A tick posts nothing by itself:
 * the buttons of a page carry {@code data-mdmf-ticks-scope="mdmfWalkPage"}, so the ticks changed since the page was drawn go with the click (the same one-submit
 * convention as step 2's "Keep these tables").
 */
final class LandingWalkPage
{
	static final String PAGE_ID = "mdmfWalkPage";

	private LandingWalkPage()
	{
	}

	/** The whole walkthrough (sub-rail + the current page); the caller wraps it in the step panel. */
	static void render(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale)
	{
		final WizardSnapshot snapshot = pModel.snapshot;
		final String page = LandingWalk.resolvePage(snapshot, pModel.walkPage);
		// landing-walkthrough display fix (2026-10-17): the walkthrough's lead is the page head's (WizardPage.renderStep3), said once
		LandingWalkPage.renderRail(pOut, snapshot, page, pLocale);
		if (LandingWalk.LAUNCH.equals(page))
		{
			LandingWalkPage.renderLaunch(pOut, pModel, pLocale);
		}
		else
		{
			LandingWalkPage.renderApplication(pOut, pModel, pLocale, LandingWalk.source(snapshot, page));
		}
	}

	// ----------------------------------------------------------------------------------------------------------------------------- sub-rail

	private static void renderRail(final StringBuilder pOut, final WizardSnapshot pSnapshot, final String pPage, final Locale pLocale)
	{
		pOut.append("<nav class=\"mdmf-walk-rail\" aria-label=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.rail.label"))).append("\"><ol>");
		int number = 0;
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			number++;
			final boolean current = source.id.equals(pPage);
			final String label = current && !source.landingValidated ? LandingWalk.name(pSnapshot, source)
				: source.landingValidated ? WizardMessages.get(pLocale, "wizard.walk.rail.done", LandingWalk.name(pSnapshot, source),
					WizardPage.plural(pLocale, LandingWalk.tickedTables(source), "wizard.walk.tables.one", "wizard.walk.tables.other"))
					: LandingWalk.name(pSnapshot, source);
			LandingWalkPage.railItem(pOut, String.valueOf(number), label, source.id, pPage, current, source.landingValidated, current || source.landingValidated);
		}
		number++;
		LandingWalkPage.railItem(pOut, String.valueOf(number), WizardMessages.get(pLocale, "wizard.walk.rail.launch"), LandingWalk.LAUNCH, pPage, LandingWalk.LAUNCH.equals(pPage), false,
			LandingWalk.LAUNCH.equals(pPage) || LandingWalk.allValidated(pSnapshot));
		pOut.append("</ol></nav>");
	}

	private static void railItem(final StringBuilder pOut, final String pNumber, final String pLabel, final String pTarget, final String pFromPage,
		final boolean pCurrent, final boolean pValidated, final boolean pClickable)
	{
		pOut.append("<li class=\"mdmf-walk-step").append(pCurrent ? " current" : "").append(pValidated ? " done" : "").append("\"").append(pCurrent ? " aria-current=\"step\"" : "").append(">");
		final String inner = "<span class=\"mdmf-walk-n\">" + (pValidated ? "&#10003;" : pNumber) + "</span><span class=\"mdmf-walk-name\">" + WizardPage.esc(pLabel) + "</span>";
		if (pClickable)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-linkbtn\" data-mdmf-action=\"LANDING_WALK_GOTO\" data-mdmf-id=\"").append(WizardPage.esc(pTarget))
				.append("\" data-mdmf-source-id=\"").append(LandingWalk.LAUNCH.equals(pFromPage) ? "" : WizardPage.esc(pFromPage)).append("\" data-mdmf-ticks-scope=\"").append(PAGE_ID).append("\">").append(inner).append("</button>");
		}
		else
		{
			pOut.append(inner);
		}
		pOut.append("</li>");
	}

	// ----------------------------------------------------------------------------------------------------------------------------- one application

	private static void renderApplication(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource)
	{
		final WizardSnapshot snapshot = pModel.snapshot;
		final int index = snapshot.sources.indexOf(pSource) + 1;
		final String next = LandingWalk.neighbour(snapshot, pSource.id, 1);
		final String previous = LandingWalk.neighbour(snapshot, pSource.id, -1);
		final int uncertain = LandingWalk.tablesWithUncertainKey(pSource);
		final int withoutKey = LandingWalk.tablesWithoutKey(pSource);

		pOut.append("<div class=\"mdmf-panel mdmf-walk-page\" id=\"").append(PAGE_ID).append("\" data-mdmf-walk-source=\"").append(WizardPage.esc(pSource.id)).append("\">");
		pOut.append("<div class=\"mdmf-walk-head\"><h3 class=\"mdmf-walk-title\">").append(WizardPage.esc(LandingWalk.name(snapshot, pSource))).append("</h3><span class=\"mdmf-hint\">");
		// landing-walkthrough display fix (2026-10-17): the technical source-system code is small secondary text next to the name
		if (!LandingWalk.code(pSource).isEmpty())
		{
			pOut.append("<span class=\"mdmf-walk-code\">").append(WizardPage.esc(LandingWalk.code(pSource))).append("</span> &middot; ");
		}
		pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.appOf", index, snapshot.sources.size()))).append(" &middot; ")
			.append(WizardPage.esc(WizardPage.plural(pLocale, pSource.tables.size(), "wizard.walk.read.one", "wizard.walk.read.other"))).append("</span>");
		final String stateKey = pSource.landingValidated ? "validated" : withoutKey > 0 ? "toDecide" : "toValidate";
		pOut.append("<span class=\"mdmf-chip ").append(pSource.landingValidated ? "mdmf-chip-real" : withoutKey > 0 ? "mdmf-chip-warn" : "mdmf-chip-accent").append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.state." + stateKey))).append("</span></div>");

		// the factory's suggestion: what is ticked now, and the state of the keys
		final int ticked = LandingWalk.tickedTables(pSource);
		pOut.append("<p class=\"mdmf-walk-suggestion\"><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.suggest.label"))).append("</b> ");
		if (ticked == 0)
		{
			pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.suggest.none")));
		}
		else
		{
			pOut.append(WizardPage.esc(WizardPage.plural(pLocale, ticked, "wizard.walk.suggest.tables.one", "wizard.walk.suggest.tables.other", new Object[] { LandingWalkPage.rowsText(pLocale, pSource) })));
			pOut.append(' ');
			if (uncertain == 0)
			{
				pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.suggest.keysSet")));
			}
			else
			{
				pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.suggest.keysCheck", uncertain - withoutKey, withoutKey)));
			}
		}
		pOut.append("</p>");

		if (pSource.tables.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.noTables"))).append("</div>");
		}
		else
		{
			LandingWalkPage.renderTables(pOut, pModel, pLocale, pSource);
		}

		if (uncertain > 0 && pModel.designer && pModel.snapshot.llmConfigured)
		{
			LandingWalkPage.renderLlmChoice(pOut, pModel, pLocale, pSource);
		}

		// footer
		pOut.append("<div class=\"mdmf-walk-footer\">");
		if (previous != null && !LandingWalk.LAUNCH.equals(previous))
		{
			final WizardSnapshot.SourceInfo previousSource = LandingWalk.source(snapshot, previous);
			LandingWalkPage.actionButton(pOut, "mdmf-btn mdmf-btn-quiet", "LANDING_WALK_GOTO", "data-mdmf-id=\"" + WizardPage.esc(previous) + "\" data-mdmf-source-id=\"" + WizardPage.esc(pSource.id) + "\" data-mdmf-ticks-scope=\"" + PAGE_ID + "\"",
				WizardMessages.get(pLocale, "wizard.walk.back", LandingWalk.name(snapshot, previousSource)));
		}
		LandingWalkPage.actionButton(pOut, "mdmf-btn mdmf-btn-quiet", "SOURCE_REMOVE",
			"data-mdmf-source-id=\"" + WizardPage.esc(pSource.id) + "\" data-mdmf-confirm=\"" + WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.remove.confirm", LandingWalk.name(snapshot, pSource))) + "\"",
			WizardMessages.get(pLocale, "wizard.walk.remove", LandingWalk.name(snapshot, pSource)));
		pOut.append("<span class=\"mdmf-walk-spacer\"></span>");
		pOut.append("<span class=\"mdmf-hint mdmf-walk-count\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.count", WizardPage.plural(pLocale, ticked, "wizard.walk.tables.one", "wizard.walk.tables.other"),
			LandingWalkPage.rowsText(pLocale, pSource)))).append("</span>");
		final String reasonKey = LandingWalk.validateReasonKey(pSource);
		final String label = LandingWalk.LAUNCH.equals(next) ? WizardMessages.get(pLocale, "wizard.walk.validate.last")
			: WizardMessages.get(pLocale, "wizard.walk.validate", LandingWalk.name(snapshot, pSource), LandingWalk.name(snapshot, LandingWalk.source(snapshot, next)));
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"LANDING_WALK_VALIDATE\" data-mdmf-source-id=\"").append(WizardPage.esc(pSource.id))
			.append("\" data-mdmf-ticks-scope=\"").append(PAGE_ID).append("\" data-mdmf-walk-validate=\"1\"").append(reasonKey == null ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(label)).append("</button>");
		pOut.append("<span class=\"mdmf-hint mdmf-walk-reason\" data-mdmf-walk-reason=\"1\"").append(reasonKey == null ? " hidden=\"hidden\"" : "").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.validate.needTable"))).append("</span>");
		pOut.append("</div>");
		if (pSource.landingValidated && !pSource.landingValidatedBy.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.validatedBy", pSource.landingValidatedBy,
				pSource.landingValidatedAt.replace('T', ' ')))).append("</p>");
		}

		// the two cards under the page, like the mockup's right column
		pOut.append("<div class=\"mdmf-walk-aside\"><div><div class=\"eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.side.validate.title")))
			.append("</div><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.side.validate.text"))).append("</p></div><div><div class=\"eyebrow\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.side.keys.title"))).append("</div><p class=\"mdmf-hint\"><b>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.declared"))).append("</b>: ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.side.keys.declared")))
			.append("<br/><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.side.keys.deducedName"))).append("</b>: ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.side.keys.deduced"))).append("<br/><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.none")))
			.append("</b>: ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.side.keys.none"))).append("</p></div></div>");
		pOut.append("</div>");
	}

	/** "144 rows", or "rows not counted" when no ticked table has a known count, with "+ N not counted" when only some do. */
	private static String rowsText(final Locale pLocale, final WizardSnapshot.SourceInfo pSource)
	{
		final int unknown = LandingWalk.unknownRows(pSource);
		final long rows = LandingWalk.tickedRows(pSource);
		if (unknown > 0 && rows == 0)
		{
			return WizardMessages.get(pLocale, "wizard.walk.rows.notCounted");
		}
		final String text = WizardPage.countText(pLocale, rows, "wizard.unit.row");
		return unknown > 0 ? WizardMessages.get(pLocale, "wizard.walk.rows.plusUnknown", text, unknown) : text;
	}

	private static void actionButton(final StringBuilder pOut, final String pClass, final String pAction, final String pAttributes, final String pLabel)
	{
		pOut.append("<button type=\"button\" class=\"").append(pClass).append("\" data-mdmf-action=\"").append(pAction).append("\" ").append(pAttributes).append(">")
			.append(WizardPage.esc(pLabel)).append("</button>");
	}

	// ----------------------------------------------------------------------------------------------------------------------------- the tables

	/** The five columns of the walkthrough's table, widths in wizard.css ({@code .mdmf-walk-col-*}). */
	static final String COLGROUP = "<colgroup><col class=\"mdmf-walk-col-load\"/><col class=\"mdmf-walk-col-table\"/><col class=\"mdmf-walk-col-rows\"/><col class=\"mdmf-walk-col-key\"/>"
		+ "<col class=\"mdmf-walk-col-why\"/></colgroup>";

	private static void renderTables(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource)
	{
		final List<WizardSnapshot.TableInfo> listed = LandingWalk.listed(pSource);
		final List<WizardSnapshot.TableInfo> empties = LandingWalk.emptyTables(pSource);
		final List<WizardSnapshot.TableInfo> samples = LandingWalk.sampleTables(pSource);
		// landing leftovers (2026-10-17): fixed layout and one box that scrolls: a wide panel under a row (the key chooser's sample) can no longer stretch the table past its
		// box and cut the "Key" column; the panel's own wrapper scrolls instead
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table mdmf-walk-table mdmf-walk-fixed\">").append(LandingWalkPage.COLGROUP).append("<thead><tr><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.col.load")))
			.append("</th><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.table"))).append("</th><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.col.rows")))
			.append("</th><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.col.key"))).append("</th><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.col.why")))
			.append("</th></tr></thead><tbody>");
		boolean notKeptShown = false;
		for (final WizardSnapshot.TableInfo table : listed)
		{
			if (!table.selected && !notKeptShown)
			{
				notKeptShown = true;
				pOut.append("<tr class=\"mdmf-walk-sub\"><td colspan=\"5\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.notKept"))).append("</td></tr>");
			}
			LandingWalkPage.renderTableRow(pOut, pModel, pLocale, pSource, table);
		}
		pOut.append("</tbody></table></div>");
		if (!samples.isEmpty())
		{
			pOut.append("<details class=\"mdmf-walk-empties mdmf-walk-samples\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.sample.group", samples.size())))
				.append("</summary><div class=\"mdmf-table-wrap\"><table class=\"mdmf-table mdmf-walk-table mdmf-walk-fixed\">").append(LandingWalkPage.COLGROUP).append("<tbody>");
			for (final WizardSnapshot.TableInfo table : samples)
			{
				LandingWalkPage.renderTableRow(pOut, pModel, pLocale, pSource, table);
			}
			pOut.append("</tbody></table></div></details>");
		}
		if (!empties.isEmpty())
		{
			pOut.append("<details class=\"mdmf-walk-empties\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, empties.size() == 1 ? "wizard.walk.empty.one" : "wizard.walk.empty.other", empties.size())))
				.append("</summary><div class=\"mdmf-table-wrap\"><table class=\"mdmf-table mdmf-walk-table mdmf-walk-fixed\">").append(LandingWalkPage.COLGROUP).append("<tbody>");
			for (final WizardSnapshot.TableInfo table : empties)
			{
				LandingWalkPage.renderTableRow(pOut, pModel, pLocale, pSource, table);
			}
			pOut.append("</tbody></table></div></details>");
		}
	}

	private static void renderTableRow(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource,
		final WizardSnapshot.TableInfo pTable)
	{
		final boolean frozen = !"PROPOSED".equals(pTable.status);
		final String checkboxId = "mdmfWalkTick-" + pTable.id;
		final TableRowCount rows = SourceTableList.rows(pTable);
		pOut.append("<tr id=\"mdmf-table-").append(WizardPage.esc(pTable.id)).append("\"><td><input type=\"checkbox\" class=\"mdmf-tick\" id=\"").append(WizardPage.esc(checkboxId)).append("\"")
			.append(pTable.selected ? " checked=\"checked\"" : "")
			.append(frozen ? " disabled=\"disabled\" title=\"" + WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.tables.frozen")) + "\"" : "")
			.append(" data-mdmf-tick-id=\"").append(WizardPage.esc(pTable.id)).append("\" aria-label=\"").append(WizardPage.esc(pTable.tableName)).append("\"/></td>");
		pOut.append("<td><label for=\"").append(WizardPage.esc(checkboxId)).append("\"><b>").append(WizardPage.esc(pTable.tableName)).append("</b></label></td>");
		pOut.append("<td class=\"num\">").append(rows.known ? String.valueOf(rows.value) : WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.rows.unknown"))).append("</td>");
		pOut.append("<td>");
		LandingWalkPage.renderKeyCell(pOut, pModel, pLocale, pSource, pTable, frozen);
		pOut.append("</td><td class=\"mdmf-hint\">");
		final String why = LandingWalk.why(pSource, pTable);
		if (why != null)
		{
			pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.why." + why)));
		}
		pOut.append("</td></tr>");
		if (pModel.keyStudy != null && pTable.id.equals(pModel.keyStudyTableId))
		{
			pOut.append("<tr class=\"mdmf-walk-panel-row\"><td colspan=\"5\"><div class=\"mdmf-scroll-x mdmf-walk-panel\">");
			WizardPage.renderKeyStudyPanel(pOut, pModel.keyStudy, pLocale, pSource, pTable, "LANDING_WALK_STUDY_APPLY");
			pOut.append("</div></td></tr>");
		}
		if (pModel.landingKeyChooser != null && pTable.id.equals(pModel.landingKeyChooserTableId))
		{
			// landing leftovers (2026-10-17): the chooser has its own full-width row under the table's row, in a box that scrolls on its own
			pOut.append("<tr class=\"mdmf-walk-panel-row\"><td colspan=\"5\"><div class=\"mdmf-scroll-x mdmf-walk-panel\">");
			// a table the last run left waiting for its key: "Use this key and continue" writes the key AND resumes the run (LANDING_KEY_APPLY); the walkthrough's own
			// apply only writes the key, which a built table refuses
			if (LandingKeyRules.waitsInRun(pModel.landingRunWaitingTableIds, pTable.id))
			{
				WizardPage.renderKeyChooserPanel(pOut, pModel, pLocale, pTable.id, "LANDING_KEY_APPLY", "wizard.step3.run.chooser.apply");
			}
			else
			{
				WizardPage.renderKeyChooserPanel(pOut, pModel, pLocale, pTable.id, "LANDING_WALK_KEY_APPLY", "wizard.walk.key.chooser.apply");
			}
			pOut.append("</div></td></tr>");
		}
	}

	private static void renderKeyCell(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource,
		final WizardSnapshot.TableInfo pTable, final boolean pFrozen)
	{
		final LandingWalk.KeyState key = LandingWalk.keyState(pTable);
		// landing validated key (2026-10-17): a run that ended with this table still waiting for its key offers the chooser here again - the table is built (frozen), so
		// none of the pre-launch key actions below apply, and without this the person had no way forward but to launch again
		if (LandingKeyRules.waitsInRun(pModel.landingRunWaitingTableIds, pTable.id))
		{
			if (key.kind != LandingWalk.KeyKind.NONE)
			{
				WizardPage.renderKeyChips(pOut, pLocale, key.columns, null);
			}
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-warn\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.waitsInRun"))).append("</span>");
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.waitsInRun.hint"))).append("</p><div class=\"mdmf-walk-keyactions\">");
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-sm\" data-mdmf-action=\"LANDING_KEY_CHOOSE\" data-mdmf-table-id=\"").append(WizardPage.esc(pTable.id))
				.append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooseKey"))).append("</button></div>");
			return;
		}
		if (key.kind != LandingWalk.KeyKind.NONE)
		{
			WizardPage.renderKeyChips(pOut, pLocale, key.columns, null);
			final String chipClass = key.kind == LandingWalk.KeyKind.DEDUCED ? "mdmf-chip-warn" : "mdmf-chip-real";
			pOut.append(" <span class=\"mdmf-chip ").append(chipClass).append("\">");
			if (key.kind == LandingWalk.KeyKind.DEDUCED)
			{
				pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.deduced", key.confidence)));
			}
			else
			{
				pOut.append(WizardPage.esc(WizardMessages.get(pLocale, key.kind == LandingWalk.KeyKind.DECLARED ? "wizard.walk.key.declared" : "wizard.walk.key.chosen")));
			}
			pOut.append("</span>");
			// landing-walkthrough display fix (2026-10-17): the key actions are for the tables the person keeps; an unticked row shows the key and its origin only
			if (key.kind == LandingWalk.KeyKind.DEDUCED && !pFrozen && pTable.selected)
			{
				pOut.append("<div class=\"mdmf-walk-keyactions\">");
				LandingWalkPage.tableButton(pOut, "LANDING_WALK_STUDY", pSource, pTable, WizardMessages.get(pLocale, "wizard.walk.key.study"));
				LandingWalkPage.tableButton(pOut, "LANDING_WALK_KEY_CHOOSE", pSource, pTable, WizardMessages.get(pLocale, "wizard.walk.key.other"));
				pOut.append("</div>");
			}
			return;
		}
		pOut.append("<span class=\"mdmf-chip mdmf-chip-danger\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.none"))).append("</span>");
		// landing test fixes (2026-10-17): once the uniqueness study is open under the row its chooser replaces this inline picker - two stacked choosers disagreed about the preselection
		if (pFrozen || !pTable.selected || pModel.landingKeyChooser != null && pTable.id.equals(pModel.landingKeyChooserTableId))
		{
			return;
		}
		// a ticked table with no key: the picker is on the page, the proposal (when there is one) ticked
		java.util.List<String> proposed = LandingWalkPage.split(key.proposal);
		// landing test fixes (2026-10-17): a proposal that is one date, timestamp or amount column (by its name; the type is not on the page) is never ticked for the person
		if (proposed.size() == 1 && com.on.ebx.factory.landing.KeyCandidateRank.rank(proposed.get(0), "", null) == com.on.ebx.factory.landing.KeyCandidateRank.NEVER)
		{
			proposed = new java.util.ArrayList<String>();
		}
		pOut.append("<div class=\"mdmf-walk-keybox\" data-mdmf-use-template=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.use", "{0}"))).append("\"><p class=\"mdmf-hint\">");
		if (proposed.isEmpty())
		{
			pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.noProposal")));
		}
		else
		{
			pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.proposalNote", key.proposal.replace(",", ", "), key.proposalConfidence, LandingWalk.LLM_CONFIDENCE_MIN)));
		}
		pOut.append("</p><div class=\"mdmf-key-picker\">");
		for (final String column : proposed)
		{
			LandingWalkPage.columnCheckbox(pOut, pLocale, column, true, true);
		}
		final List<String> others = new java.util.ArrayList<String>(pTable.columnNames);
		others.removeAll(proposed);
		if (!others.isEmpty())
		{
			pOut.append("</div><details class=\"mdmf-walk-columns\"").append(proposed.isEmpty() ? " open=\"open\"" : "").append("><summary>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.others", others.size()))).append("</summary><div class=\"mdmf-key-picker\">");
			for (final String column : others)
			{
				LandingWalkPage.columnCheckbox(pOut, pLocale, column, false, false);
			}
			pOut.append("</div></details>");
		}
		else
		{
			pOut.append("</div>");
		}
		pOut.append("<div class=\"mdmf-walk-keyactions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-sm\" data-mdmf-action=\"LANDING_WALK_KEY_APPLY\" data-mdmf-source-id=\"")
			.append(WizardPage.esc(pSource.id)).append("\" data-mdmf-table-id=\"").append(WizardPage.esc(pTable.id)).append("\" data-mdmf-key-input=\"1\" data-mdmf-ticks-scope=\"").append(PAGE_ID)
			.append("\" data-mdmf-walk-use=\"1\" data-mdmf-choose-text=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.chooseColumn"))).append("\"").append(proposed.isEmpty() ? " disabled=\"disabled\"" : "").append(">")
			.append(WizardPage.esc(proposed.isEmpty() ? WizardMessages.get(pLocale, "wizard.walk.key.chooseColumn") : WizardMessages.get(pLocale, "wizard.walk.key.use", key.proposal.replace(",", " + "))))
			.append("</button>");
		LandingWalkPage.tableButton(pOut, "LANDING_WALK_KEY_CHOOSE", pSource, pTable, WizardMessages.get(pLocale, "wizard.walk.key.measure"));
		pOut.append("</div><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.waits"))).append("</p></div>");
	}

	private static void columnCheckbox(final StringBuilder pOut, final Locale pLocale, final String pColumn, final boolean pChecked, final boolean pProposed)
	{
		pOut.append("<label><input type=\"checkbox\" class=\"mdmf-key-check\" value=\"").append(WizardPage.esc(pColumn)).append("\"").append(pChecked ? " checked=\"checked\"" : "").append("/> ")
			.append(WizardPage.esc(pColumn));
		if (pProposed)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.proposedTag"))).append("</span>");
		}
		pOut.append("</label>");
	}

	private static void tableButton(final StringBuilder pOut, final String pAction, final WizardSnapshot.SourceInfo pSource, final WizardSnapshot.TableInfo pTable, final String pLabel)
	{
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"").append(pAction).append("\" data-mdmf-source-id=\"").append(WizardPage.esc(pSource.id))
			.append("\" data-mdmf-table-id=\"").append(WizardPage.esc(pTable.id)).append("\" data-mdmf-ticks-scope=\"").append(PAGE_ID).append("\">").append(WizardPage.esc(pLabel)).append("</button>");
	}

	private static List<String> split(final String pCsv)
	{
		final List<String> out = new java.util.ArrayList<String>();
		if (pCsv != null)
		{
			for (final String part : pCsv.split(","))
			{
				if (!part.trim().isEmpty())
				{
					out.add(part.trim());
				}
			}
		}
		return out;
	}

	// ----------------------------------------------------------------------------------------------------------------------------- the language model

	private static void renderLlmChoice(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource)
	{
		final boolean yes = pModel.walkLlmYes.contains(pSource.id);
		final String connector = pModel.snapshot.llmConnectorLabel == null || pModel.snapshot.llmConnectorLabel.isEmpty() ? WizardMessages.get(pLocale, "wizard.walk.llm.generic")
			: pModel.snapshot.llmConnectorLabel;
		pOut.append("<fieldset class=\"mdmf-walk-llm\"><legend class=\"eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.llm.legend"))).append("</legend>");
		pOut.append("<label class=\"mdmf-walk-opt\"><input type=\"radio\" name=\"mdmfWalkLlmRadio\" value=\"no\"").append(yes ? "" : " checked=\"checked\"").append("/> <span><b>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.llm.no.title"))).append("</b><br/><span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.llm.no.text")))
			.append("</span></span></label>");
		pOut.append("<label class=\"mdmf-walk-opt\"><input type=\"radio\" name=\"mdmfWalkLlmRadio\" value=\"yes\"").append(yes ? " checked=\"checked\"" : "").append("/> <span><b>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.llm.yes.title"))).append("</b><br/><span class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.llm.yes.text", connector))).append("</span></span></label></fieldset>");
	}

	// ----------------------------------------------------------------------------------------------------------------------------- the launch page

	private static void renderLaunch(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale)
	{
		final WizardSnapshot snapshot = pModel.snapshot;
		final int total = LandingWalk.tickedTables(snapshot);
		final String reasonKey = LandingWalk.launchReasonKey(snapshot);
		final String firstPending = LandingWalk.firstToValidate(snapshot);
		pOut.append("<div class=\"mdmf-panel mdmf-walk-page\" id=\"").append(PAGE_ID).append("\"><div class=\"mdmf-walk-head\"><h3 class=\"mdmf-walk-title\">")
			.append(WizardPage.esc(WizardPage.plural(pLocale, total, "wizard.walk.launch.title.one", "wizard.walk.launch.title.other"))).append("</h3>");
		pOut.append("<span class=\"mdmf-chip ").append(firstPending == null ? "mdmf-chip-real" : "mdmf-chip-warn").append("\">")
			.append(WizardPage.esc(firstPending == null ? WizardMessages.get(pLocale, "wizard.walk.launch.allValidated")
				: WizardMessages.get(pLocale, "wizard.walk.launch.pending", LandingWalkPage.pendingCount(snapshot))))
			.append("</span></div>");
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table mdmf-walk-table\"><thead><tr><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.launch.col.application")))
			.append("</th><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.table"))).append("</th><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.col.rows")))
			.append("</th><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.col.key"))).append("</th><th></th></tr></thead><tbody>");
		for (final WizardSnapshot.SourceInfo source : snapshot.sources)
		{
			final List<WizardSnapshot.TableInfo> ticked = new java.util.ArrayList<WizardSnapshot.TableInfo>();
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				if (table.selected)
				{
					ticked.add(table);
				}
			}
			final int span = Math.max(1, ticked.size());
			boolean first = true;
			if (ticked.isEmpty())
			{
				pOut.append("<tr><td><b>").append(WizardPage.esc(LandingWalk.name(snapshot, source))).append("</b></td><td class=\"mdmf-hint\" colspan=\"3\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.launch.noTable"))).append("</td>");
				LandingWalkPage.modifyCell(pOut, pLocale, source, 1);
				pOut.append("</tr>");
				continue;
			}
			for (final WizardSnapshot.TableInfo table : ticked)
			{
				final TableRowCount rows = SourceTableList.rows(table);
				pOut.append("<tr><td>").append(first ? "<b>" + WizardPage.esc(LandingWalk.name(snapshot, source)) + "</b>" : "").append("</td><td>").append(WizardPage.esc(table.tableName))
					.append("</td><td class=\"num\">").append(rows.known ? String.valueOf(rows.value) : WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.rows.unknown"))).append("</td><td>");
				final LandingWalk.KeyState key = LandingWalk.keyState(table);
				if (key.kind == LandingWalk.KeyKind.NONE)
				{
					pOut.append("<span class=\"mdmf-chip mdmf-chip-danger\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.key.noneWaits"))).append("</span>");
				}
				else
				{
					WizardPage.renderKeyChips(pOut, pLocale, key.columns, null);
					pOut.append(" <span class=\"mdmf-chip ").append(key.kind == LandingWalk.KeyKind.DEDUCED ? "mdmf-chip-warn" : "mdmf-chip-real").append("\">")
						.append(WizardPage.esc(key.kind == LandingWalk.KeyKind.DEDUCED ? WizardMessages.get(pLocale, "wizard.walk.key.deducedChecked", key.confidence)
							: WizardMessages.get(pLocale, key.kind == LandingWalk.KeyKind.DECLARED ? "wizard.walk.key.declared" : "wizard.walk.key.chosen")))
						.append("</span>");
				}
				pOut.append("</td>");
				if (first)
				{
					LandingWalkPage.modifyCell(pOut, pLocale, source, span);
					first = false;
				}
				pOut.append("</tr>");
			}
		}
		pOut.append("</tbody></table></div></div>");

		// the launch itself
		pOut.append("<div class=\"mdmf-panel mdmf-landing-cta mdmf-walk-launch\"><div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"LANDING_WALK_LAUNCH\"")
			.append(reasonKey == null ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardPage.plural(pLocale, total, "wizard.walk.launch.button.one", "wizard.walk.launch.button.other"))).append("</button>");
		pOut.append("<div class=\"mdmf-walk-launch-text\"><span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.launch.explain"))).append("</span><br/><span class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.launch.time"))).append("</span></div></div>");
		if (reasonKey != null)
		{
			pOut.append("<p class=\"mdmf-hint mdmf-walk-reason\">");
			if (firstPending != null)
			{
				pOut.append(WizardPage.esc(WizardMessages.get(pLocale, reasonKey, LandingWalk.name(snapshot, LandingWalk.source(snapshot, firstPending))))).append(' ');
				LandingWalkPage.actionButton(pOut, "mdmf-linkbtn", "LANDING_WALK_GOTO", "data-mdmf-id=\"" + WizardPage.esc(firstPending) + "\"",
					WizardMessages.get(pLocale, "wizard.walk.launch.goFirst", LandingWalk.name(snapshot, LandingWalk.source(snapshot, firstPending))));
			}
			else
			{
				pOut.append(WizardPage.esc(WizardMessages.get(pLocale, reasonKey)));
			}
			pOut.append("</p>");
		}
		pOut.append("</div>");
	}

	private static int pendingCount(final WizardSnapshot pSnapshot)
	{
		int count = 0;
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			if (!source.landingValidated)
			{
				count++;
			}
		}
		return count;
	}

	private static void modifyCell(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.SourceInfo pSource, final int pRowSpan)
	{
		pOut.append("<td rowspan=\"").append(pRowSpan).append("\" class=\"mdmf-walk-modify\">");
		LandingWalkPage.actionButton(pOut, "mdmf-linkbtn", "LANDING_WALK_GOTO", "data-mdmf-id=\"" + WizardPage.esc(pSource.id) + "\"", WizardMessages.get(pLocale, "wizard.walk.launch.modify"));
		pOut.append("</td>");
	}
}
