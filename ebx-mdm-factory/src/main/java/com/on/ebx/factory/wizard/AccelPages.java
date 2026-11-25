package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.review.DecisionActions;
import com.on.ebx.factory.review.ReviewLine;

/**
 * Lot C1-B (docs/LOT-C1.md sec 6, mockups {@code docs/mockups/accelerators/*.dc.html}): the screens of the CENTRALISATION path - Pack,
 * Specification, Proposal, Generation and Summary - written in the wizard's own style, with its own building blocks
 * ({@code WizardPage#renderPageHead}, {@code render2Col*}, {@code renderSideCard*}, {@code renderPrimaryActionBlock*}, {@code renderExplainCard},
 * {@code renderKpi*}). Pure: no EBX class, rendered from a {@link WizardPage.Model} and its {@link WizardSnapshot} alone, so
 * {@code AccelPagesTest} renders every screen from a fixture. {@code WizardPage} only delegates ({@link #render}, {@link #hiddenFields}).
 * <p>
 * Every button is a {@code data-mdmf-action} the wizard script posts: {@code ACCEL_SAVE_NEED}, {@code ACCEL_SUGGEST_PACKS},
 * {@code ACCEL_CHOOSE_PACKS}, {@code ACCEL_RUN_PROPOSAL}, {@code ACCEL_RUN_PASS}, {@code ACCEL_GENERATE}, plus the wizard's existing
 * {@code DECIDE_ACCEPT}/{@code DECIDE_REJECT} with nature {@code DIFFERENCE}. A difference the check of sub-lot C flagged as "looks like something
 * that exists" carries {@code "similarTo"} (and optionally {@code "similarNote"}) in its JSON detail; a rule the model wrote with nothing to start
 * from carries {@code "draft": true} there. Both are read here, nothing else of the JSON is interpreted.
 */
final class AccelPages
{
	/** The four passes of the proposal (docs/ACCELERATORS.md sec 6b), by number; the message key {@code wizard.accel.pass.<n>} names each. */
	static final int PASSES = 4;

	private static final Pattern DONE = Pattern.compile("\"done\"\\s*:\\s*(\\d+)");
	private static final Pattern TOTAL = Pattern.compile("\"total\"\\s*:\\s*(\\d+)");
	private static final Pattern SIMILAR = Pattern.compile("\"similarTo\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
	private static final Pattern SIMILAR_NOTE = Pattern.compile("\"similarNote\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
	private static final Pattern DRAFT = Pattern.compile("\"draft\"\\s*:\\s*true");

	private AccelPages()
	{
	}

	/** The hidden fields the screens post through (filled by wizard.js right before a submit): the need, the ticked packs and options, a pass, the edited detail. */
	static String hiddenFields()
	{
		final StringBuilder out = new StringBuilder();
		for (final String name : new String[] { "mdmfAccelNeed", "mdmfAccelPacks", "mdmfAccelOptions", "mdmfAccelPass", "mdmfAccelRunAll", "mdmfDetail" })
		{
			out.append("<input type=\"hidden\" id=\"").append(name).append("\" name=\"").append(name).append("\" value=\"\"/>");
		}
		return out.toString();
	}

	/** One step of the path; {@code pStep} is a {@link WizardRules} step id ({@code STEP_PACK}...{@code STEP_GENERATION}, or {@code STEP_SUMMARY} on a CENTRALISATION project). */
	static void render(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale, final int pStep)
	{
		pOut.append("<section class=\"mdmf-wiz-panel\" data-step=\"").append(pStep).append("\"").append(pStep == pModel.currentStep ? "" : " hidden=\"hidden\"").append(">");
		switch (pStep)
		{
			case WizardRules.STEP_PACK:
				AccelPages.renderPack(pOut, pModel, pLocale);
				break;
			case WizardRules.STEP_SPECIFICATION:
				AccelPages.renderSpecification(pOut, pModel, pLocale);
				break;
			case WizardRules.STEP_PROPOSAL:
				AccelPages.renderProposal(pOut, pModel, pLocale);
				break;
			case WizardRules.STEP_GENERATION:
				AccelPages.renderGeneration(pOut, pModel, pLocale);
				break;
			default:
				AccelPages.renderSummary(pOut, pModel, pLocale);
				break;
		}
		pOut.append("</section>");
	}

	// ------------------------------------------------------------------------------------------------------------------ shared bits

	private static String m(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		return WizardMessages.get(pLocale, pKey, pArgs);
	}

	private static String esc(final String pText)
	{
		return WizardPage.esc(pText);
	}

	/** A button posting {@code pAction}; disabled with the gate's own sentence as its tooltip when {@code pGate} refuses. */
	private static void button(final StringBuilder pOut, final Locale pLocale, final String pAction, final String pLabelKey, final String pClasses, final WizardRules.Gate pGate,
		final String pExtraAttributes)
	{
		final boolean disabled = pGate != null && !pGate.enabled;
		pOut.append("<button type=\"button\" class=\"mdmf-btn ").append(pClasses).append("\" data-mdmf-action=\"").append(pAction).append("\"")
			.append(pExtraAttributes == null ? "" : " " + pExtraAttributes);
		if (disabled)
		{
			pOut.append(" disabled=\"disabled\" title=\"").append(esc(m(pLocale, pGate.reasonKey) + (pGate.detail == null ? "" : " " + pGate.detail))).append("\"");
		}
		pOut.append(">").append(esc(m(pLocale, pLabelKey))).append("</button>");
	}

	private static WizardSnapshot.PackInfo packOf(final WizardSnapshot pSnapshot, final String pKey)
	{
		for (final WizardSnapshot.PackInfo pack : pSnapshot.packCatalogue)
		{
			if (pack.key.equals(pKey))
			{
				return pack;
			}
		}
		return null;
	}

	private static String packLabel(final WizardSnapshot pSnapshot, final String pKey)
	{
		final WizardSnapshot.PackInfo pack = AccelPages.packOf(pSnapshot, pKey);
		return pack == null || pack.label.isEmpty() ? pKey : pack.label;
	}

	/** The need as the screens show it: what was typed this round trip, otherwise the need stored on the project, otherwise the one on the chosen packs. */
	static String needOf(final WizardPage.Model pModel)
	{
		if (pModel.accelNeed != null && !pModel.accelNeed.isEmpty())
		{
			return pModel.accelNeed;
		}
		if (!pModel.snapshot.accelSpecification.isEmpty())
		{
			return pModel.snapshot.accelSpecification;
		}
		for (final WizardSnapshot.ProjectPackInfo pack : pModel.snapshot.projectPacks)
		{
			if (!pack.specification.isEmpty())
			{
				return pack.specification;
			}
		}
		return "";
	}

	/** Keys of the packs in force on the Pack screen: the ticked ones after an action, otherwise the saved ones. */
	private static List<String> tickedPacks(final WizardPage.Model pModel)
	{
		if (!pModel.accelTickedPacks.isEmpty())
		{
			return pModel.accelTickedPacks;
		}
		final List<String> saved = new ArrayList<String>();
		for (final WizardSnapshot.ProjectPackInfo pack : pModel.snapshot.projectPacks)
		{
			saved.add(pack.packKey);
		}
		return saved;
	}

	private static boolean optionTicked(final WizardPage.Model pModel, final String pPack, final String pOption)
	{
		final String entry = pPack + ":" + pOption;
		if (!pModel.accelTickedOptions.isEmpty() || !pModel.accelTickedPacks.isEmpty())
		{
			return pModel.accelTickedOptions.contains(entry);
		}
		for (final WizardSnapshot.ProjectPackInfo pack : pModel.snapshot.projectPacks)
		{
			if (pack.packKey.equals(pPack) && pack.options.contains(pOption))
			{
				return true;
			}
		}
		return false;
	}

	private static String humanize(final String pKey)
	{
		return pKey.replace('-', ' ').replace('_', ' ');
	}

	// ----------------------------------------------------------------------------------------------------------------- step: Pack

	private static void renderPack(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale)
	{
		final WizardSnapshot snapshot = pModel.snapshot;
		final WizardRules.ActionGates gates = WizardRules.actionGates(snapshot, pModel.designer, null);
		WizardPage.renderPageHead(pOut, m(pLocale, "wizard.accel.pack.title"), m(pLocale, "wizard.accel.pack.lead"));
		WizardPage.render2ColStart(pOut);

		// the need
		pOut.append("<div class=\"mdmf-panel\"><div class=\"mdmf-field\"><label for=\"mdmfAccelNeedBox\">").append(esc(m(pLocale, "wizard.accel.need.label"))).append("</label>")
			.append("<textarea id=\"mdmfAccelNeedBox\" class=\"mdmf-accel-need\" rows=\"5\">").append(esc(AccelPages.needOf(pModel))).append("</textarea>")
			.append("<p class=\"mdmf-hint\">").append(esc(m(pLocale, "wizard.accel.need.hint"))).append("</p></div>");
		pOut.append("<div class=\"mdmf-actions\">");
		AccelPages.button(pOut, pLocale, "ACCEL_SUGGEST_PACKS", "wizard.accel.suggest", "mdmf-btn-primary", gates.accelSuggestPacks, null);
		pOut.append(" ");
		AccelPages.button(pOut, pLocale, "ACCEL_SAVE_NEED", "wizard.accel.needSave", "", gates.accelSuggestPacks, null);
		pOut.append("</div></div>");

		// the packs
		pOut.append("<div class=\"mdmf-panel\"><span class=\"mdmf-eyebrow\">").append(esc(m(pLocale, "wizard.accel.packs.title"))).append("</span>");
		if (snapshot.packCatalogue.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(esc(m(pLocale, "wizard.accel.packs.none"))).append("</div>");
		}
		final List<String> ticked = AccelPages.tickedPacks(pModel);
		final List<String> required = new ArrayList<String>();
		for (final WizardSnapshot.PackInfo pack : snapshot.packCatalogue)
		{
			if (ticked.contains(pack.key))
			{
				for (final String key : pack.requires)
				{
					if (!required.contains(key))
					{
						required.add(key);
					}
				}
			}
		}
		pOut.append("<div class=\"mdmf-accel-packs\">");
		for (final WizardSnapshot.PackInfo pack : snapshot.packCatalogue)
		{
			final boolean on = ticked.contains(pack.key) || required.contains(pack.key);
			pOut.append("<div class=\"mdmf-choice-card mdmf-accel-pack-card").append(on ? " selected" : "").append("\"><label>")
				.append("<input type=\"checkbox\" class=\"mdmf-accel-pack\" value=\"").append(esc(pack.key)).append("\"").append(on ? " checked=\"checked\"" : "").append("/> ")
				.append("<span class=\"mdmf-choice-card-title\">").append(esc(pack.label)).append("</span></label> ");
			if (pModel.accelSuggestedPacks.contains(pack.key))
			{
				pOut.append("<span class=\"mdmf-chip mdmf-chip-accent\">").append(esc(m(pLocale, "wizard.accel.chip.suggested"))).append("</span> ");
			}
			if (required.contains(pack.key))
			{
				pOut.append("<span class=\"mdmf-chip mdmf-chip-target\">").append(esc(m(pLocale, "wizard.accel.chip.prerequisite"))).append("</span> ");
			}
			pOut.append("<p class=\"mdmf-hint\">").append(esc(pack.description)).append("</p>");
			pOut.append("<p class=\"mdmf-hint\">").append(esc(m(pLocale, "wizard.accel.pack.basis", pack.version.isEmpty() ? "-" : pack.version, Integer.valueOf(pack.tables),
				Integer.valueOf(pack.fields)))).append("</p>");
			if (!pack.options.isEmpty())
			{
				pOut.append("<p class=\"mdmf-hint\"><b>").append(esc(m(pLocale, "wizard.accel.pack.options"))).append("</b> ");
				for (final String option : pack.options)
				{
					pOut.append("<label class=\"mdmf-radio-row\"><input type=\"checkbox\" class=\"mdmf-accel-option\" value=\"").append(esc(pack.key + ":" + option)).append("\"")
						.append(AccelPages.optionTicked(pModel, pack.key, option) ? " checked=\"checked\"" : "").append("/> ").append(esc(AccelPages.humanize(option))).append("</label> ");
				}
				pOut.append("</p>");
			}
			pOut.append("</div>");
		}
		pOut.append("</div>");
		if (!pModel.accelWhy.isEmpty())
		{
			pOut.append("<div class=\"mdmf-panel mdmf-panel-muted mdmf-accel-why\"><span class=\"mdmf-eyebrow\">").append(esc(m(pLocale, "wizard.accel.why.title"))).append("</span><p>")
				.append(esc(pModel.accelWhy)).append("</p></div>");
		}
		pOut.append("</div>");

		// continue
		WizardPage.renderPrimaryActionBlockOpen(pOut);
		AccelPages.button(pOut, pLocale, "ACCEL_CHOOSE_PACKS", "wizard.accel.continue", "mdmf-btn-primary mdmf-btn-big", gates.accelSuggestPacks, null);
		pOut.append("<span class=\"mdmf-primary-text\">").append(esc(m(pLocale, "wizard.accel.continue.text"))).append(" ");
		AccelPages.button(pOut, pLocale, "ACCEL_CHOOSE_PACKS", "wizard.accel.blank", "mdmf-btn-sm", gates.accelSuggestPacks, "data-mdmf-accel-blank=\"true\"");
		pOut.append("</span>");
		WizardPage.renderPrimaryActionBlockClose(pOut);

		WizardPage.render2ColSplit(pOut);
		AccelPages.renderCounters(pOut, pModel, pLocale, ticked, required);
		WizardPage.renderGlossaryCard(pOut, pLocale, "wizard.accel.glossary.pack", "wizard.accel.glossary.block", "wizard.accel.glossary.option");
		WizardPage.render2ColEnd(pOut);
	}

	/** The "selected packs bring" card: tables, fields, reference rows and blocks of the ticked packs and the packs they require. */
	private static void renderCounters(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale, final List<String> pTicked, final List<String> pRequired)
	{
		int tables = 0;
		int fields = 0;
		int rows = 0;
		final List<String> blocks = new ArrayList<String>();
		for (final WizardSnapshot.PackInfo pack : pModel.snapshot.packCatalogue)
		{
			if (pTicked.contains(pack.key) || pRequired.contains(pack.key))
			{
				tables += pack.tables;
				fields += pack.fields;
				rows += pack.referenceRows;
				for (final String block : pack.blocks)
				{
					if (!blocks.contains(block))
					{
						blocks.add(block);
					}
				}
			}
		}
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.accel.counters.title");
		WizardPage.renderKpiRowOpen(pOut);
		WizardPage.renderKpiTile(pOut, String.valueOf(tables), m(pLocale, "wizard.accel.counters.tables"));
		WizardPage.renderKpiTile(pOut, String.valueOf(fields), m(pLocale, "wizard.accel.counters.fields"));
		WizardPage.renderKpiTile(pOut, String.valueOf(rows), m(pLocale, "wizard.accel.counters.rows"));
		WizardPage.renderKpiRowClose(pOut);
		final StringBuilder names = new StringBuilder();
		for (final String block : blocks)
		{
			names.append(names.length() == 0 ? "" : ", ").append(AccelPages.humanize(block));
		}
		pOut.append("<p class=\"mdmf-hint\"><b>").append(esc(m(pLocale, "wizard.accel.counters.blocks"))).append("</b> ").append(esc(names.length() == 0 ? "-" : names.toString())).append("</p>");
		WizardPage.renderSideCardClose(pOut);
	}

	// ------------------------------------------------------------------------------------------------------- step: Specification

	private static void renderSpecification(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale)
	{
		final WizardSnapshot snapshot = pModel.snapshot;
		final WizardRules.ActionGates gates = WizardRules.actionGates(snapshot, pModel.designer, null);
		WizardPage.renderPageHead(pOut, m(pLocale, "wizard.accel.spec.title"), m(pLocale, "wizard.accel.spec.lead"));
		WizardPage.render2ColStart(pOut);
		pOut.append("<div class=\"mdmf-panel\"><div class=\"mdmf-field\"><label for=\"mdmfAccelSpecBox\">").append(esc(m(pLocale, "wizard.accel.spec.label"))).append("</label>")
			.append("<textarea id=\"mdmfAccelSpecBox\" class=\"mdmf-accel-need\" rows=\"8\">").append(esc(AccelPages.needOf(pModel))).append("</textarea>")
			.append("<p class=\"mdmf-hint\">").append(esc(m(pLocale, "wizard.accel.spec.hint"))).append("</p></div>");
		pOut.append("<div class=\"mdmf-actions\">");
		AccelPages.button(pOut, pLocale, "ACCEL_SAVE_NEED", "wizard.accel.needSave", "", gates.accelSuggestPacks, null);
		pOut.append("</div></div>");

		pOut.append("<div class=\"mdmf-panel\"><span class=\"mdmf-eyebrow\">").append(esc(m(pLocale, "wizard.accel.spec.chosen"))).append("</span>");
		if (snapshot.projectPacks.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(esc(m(pLocale, snapshot.accelBlankStart ? "wizard.accel.spec.blank" : "wizard.accel.spec.noPack"))).append("</p>");
		}
		pOut.append("<ul class=\"mdmf-plain-list\">");
		for (final WizardSnapshot.ProjectPackInfo pack : snapshot.projectPacks)
		{
			pOut.append("<li><b>").append(esc(AccelPages.packLabel(snapshot, pack.packKey))).append("</b> <span class=\"mdmf-hint\">v").append(esc(pack.packVersion.isEmpty() ? "-" : pack.packVersion))
				.append("</span> ");
			pOut.append("<span class=\"mdmf-chip ").append("LLM".equals(pack.chosenBy) ? "mdmf-chip-accent" : "mdmf-chip-real").append("\">")
				.append(esc(m(pLocale, "LLM".equals(pack.chosenBy) ? "wizard.accel.chosenBy.llm" : "wizard.accel.chosenBy.user"))).append("</span>");
			if (!pack.options.isEmpty())
			{
				pOut.append(" <span class=\"mdmf-hint\">").append(esc(m(pLocale, "wizard.accel.spec.options", joinHumanized(pack.options)))).append("</span>");
			}
			pOut.append("</li>");
		}
		pOut.append("</ul></div>");

		WizardPage.renderPrimaryActionBlockOpen(pOut);
		AccelPages.button(pOut, pLocale, "ACCEL_RUN_PROPOSAL", "wizard.accel.run", "mdmf-btn-primary mdmf-btn-big", gates.accelRunProposal, null);
		pOut.append("<span class=\"mdmf-primary-text\">").append(esc(m(pLocale, "wizard.accel.run.text"))).append("</span>");
		WizardPage.renderPrimaryActionBlockClose(pOut);
		AccelPages.renderJob(pOut, snapshot, pLocale);

		WizardPage.render2ColSplit(pOut);
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.accel.spec.how.title");
		WizardPage.renderExplainCard(pOut, 1, m(pLocale, "wizard.accel.spec.how.1.title"), m(pLocale, "wizard.accel.spec.how.1.text"));
		WizardPage.renderExplainCard(pOut, 2, m(pLocale, "wizard.accel.spec.how.2.title"), m(pLocale, "wizard.accel.spec.how.2.text"));
		WizardPage.renderExplainCard(pOut, 3, m(pLocale, "wizard.accel.spec.how.3.title"), m(pLocale, "wizard.accel.spec.how.3.text"));
		WizardPage.renderSideCardClose(pOut);
		WizardPage.render2ColEnd(pOut);
	}

	private static String joinHumanized(final List<String> pKeys)
	{
		final StringBuilder out = new StringBuilder();
		for (final String key : pKeys)
		{
			out.append(out.length() == 0 ? "" : ", ").append(AccelPages.humanize(key));
		}
		return out.toString();
	}

	/** The accelerator job in progress, if any: its message and, when the step's progress JSON carries {@code done} and {@code total}, a bar. */
	private static void renderJob(final StringBuilder pOut, final WizardSnapshot pSnapshot, final Locale pLocale)
	{
		final WizardSnapshot.AccelJobInfo job = pSnapshot.accelJob;
		if (job == null)
		{
			return;
		}
		pOut.append("<div class=\"mdmf-panel mdmf-accel-job\" role=\"status\"><span class=\"mdmf-chip mdmf-chip-accent\">")
			.append(esc(m(pLocale, "QUEUED".equals(job.status) ? "wizard.accel.job.queued" : "wizard.accel.job.running"))).append("</span> ")
			.append(esc(job.message.isEmpty() ? m(pLocale, "wizard.accel.job.wait") : job.message));
		final Matcher done = AccelPages.DONE.matcher(job.progressJson);
		final Matcher total = AccelPages.TOTAL.matcher(job.progressJson);
		if (done.find() && total.find() && Integer.parseInt(total.group(1)) > 0)
		{
			final int percent = Math.min(100, Integer.parseInt(done.group(1)) * 100 / Integer.parseInt(total.group(1)));
			pOut.append("<div class=\"mdmf-proposal-bar\"><div class=\"mdmf-proposal-bar-fill\" style=\"width:").append(percent).append("%\"></div></div>");
		}
		pOut.append("</div>");
	}

	// ---------------------------------------------------------------------------------------------------------- step: Proposal

	private static int count(final List<WizardSnapshot.DifferenceInfo> pRows, final String pStatus)
	{
		int n = 0;
		for (final WizardSnapshot.DifferenceInfo row : pRows)
		{
			if (pStatus.equals(row.status) && !"KEEP".equals(row.nature))
			{
				n++;
			}
		}
		return n;
	}

	private static void renderProposal(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale)
	{
		final WizardSnapshot snapshot = pModel.snapshot;
		final WizardRules.ActionGates gates = WizardRules.actionGates(snapshot, pModel.designer, null);
		final int toDecide = WizardRules.differencesToDecide(snapshot);
		final int total = snapshot.differences.size();
		WizardPage.renderPageHead(pOut, m(pLocale, "wizard.accel.proposal.title"), m(pLocale, "wizard.accel.proposal.lead"));
		AccelPages.renderJob(pOut, snapshot, pLocale);
		WizardPage.render2ColStart(pOut);

		final Map<Integer, List<WizardSnapshot.DifferenceInfo>> byPass = new LinkedHashMap<Integer, List<WizardSnapshot.DifferenceInfo>>();
		for (int pass = 1; pass <= AccelPages.PASSES; pass++)
		{
			byPass.put(Integer.valueOf(pass), new ArrayList<WizardSnapshot.DifferenceInfo>());
		}
		for (final WizardSnapshot.DifferenceInfo row : snapshot.differences)
		{
			final Integer pass = Integer.valueOf(Math.max(1, Math.min(AccelPages.PASSES, row.pass)));
			byPass.get(pass).add(row);
		}
		final List<WizardSnapshot.DifferenceInfo> kept = new ArrayList<WizardSnapshot.DifferenceInfo>();
		final List<WizardSnapshot.DifferenceInfo> flagged = new ArrayList<WizardSnapshot.DifferenceInfo>();

		if (total == 0)
		{
			pOut.append("<div class=\"mdmf-panel\"><p class=\"mdmf-hint\">").append(esc(m(pLocale, snapshot.accelJob != null ? "wizard.accel.proposal.running" : "wizard.accel.proposal.empty")))
				.append("</p></div>");
		}
		for (int pass = 1; pass <= AccelPages.PASSES; pass++)
		{
			final List<WizardSnapshot.DifferenceInfo> rows = byPass.get(Integer.valueOf(pass));
			final boolean previousDecided = pass == 1 || !byPass.get(Integer.valueOf(pass - 1)).isEmpty() && AccelPages.count(byPass.get(Integer.valueOf(pass - 1)), "PROPOSED") == 0;
			if (rows.isEmpty() && !(previousDecided && snapshot.accelJob == null && !snapshot.differences.isEmpty()))
			{
				continue;
			}
			pOut.append("<div class=\"mdmf-panel mdmf-accel-pass\" id=\"mdmf-accel-pass-").append(pass).append("\"><span class=\"mdmf-eyebrow\">")
				.append(esc(m(pLocale, "wizard.accel.pass.head", Integer.valueOf(pass), m(pLocale, "wizard.accel.pass." + pass)))).append("</span> ")
				.append("<span class=\"mdmf-hint\">").append(esc(m(pLocale, "wizard.accel.pass.counts", Integer.valueOf(rows.size()), Integer.valueOf(rows.size() - AccelPages.count(rows, "PROPOSED")))))
				.append("</span> ");
			if (previousDecided && snapshot.accelJob == null)
			{
				AccelPages.button(pOut, pLocale, "ACCEL_RUN_PASS", rows.isEmpty() ? "wizard.accel.pass.run" : "wizard.accel.pass.rerun", "mdmf-btn-sm", gates.accelRunProposal,
					"data-mdmf-accel-pass=\"" + pass + "\"");
			}
			if (!rows.isEmpty())
			{
				pOut.append("<table class=\"mdmf-table mdmf-accel-table\"><thead><tr><th>").append(esc(m(pLocale, "wizard.accel.col.nature"))).append("</th><th>")
					.append(esc(m(pLocale, "wizard.accel.col.proposal"))).append("</th><th>").append(esc(m(pLocale, "wizard.accel.col.where"))).append("</th><th>")
					.append(esc(m(pLocale, "wizard.accel.col.why"))).append("</th><th>").append(esc(m(pLocale, "wizard.accel.col.decision"))).append("</th></tr></thead><tbody>");
				for (final WizardSnapshot.DifferenceInfo row : rows)
				{
					if ("KEEP".equals(row.nature))
					{
						kept.add(row);
						continue;
					}
					AccelPages.renderDifferenceRow(pOut, pLocale, row, gates);
					if (AccelPages.SIMILAR.matcher(row.detail).find())
					{
						flagged.add(row);
					}
				}
				pOut.append("</tbody></table>");
			}
			pOut.append("</div>");
		}

		for (final WizardSnapshot.DifferenceInfo row : flagged)
		{
			final Matcher similar = AccelPages.SIMILAR.matcher(row.detail);
			final Matcher note = AccelPages.SIMILAR_NOTE.matcher(row.detail);
			similar.find();
			pOut.append("<div class=\"mdmf-notice mdmf-accel-check\" role=\"note\"><b>").append(esc(m(pLocale, "wizard.accel.check.title"))).append("</b> ")
				.append(esc(m(pLocale, "wizard.accel.check.text", row.what, unescapeJson(similar.group(1))))).append(note.find() ? " " + esc(unescapeJson(note.group(1))) : "").append("</div>");
		}
		if (!kept.isEmpty())
		{
			pOut.append("<details class=\"mdmf-disclosure mdmf-accel-kept\"><summary>").append(esc(m(pLocale, "wizard.accel.kept.show", Integer.valueOf(kept.size())))).append("</summary><ul class=\"mdmf-plain-list\">");
			for (final WizardSnapshot.DifferenceInfo row : kept)
			{
				pOut.append("<li><code>").append(esc(row.where)).append("</code> ").append(esc(row.what)).append("</li>");
			}
			pOut.append("</ul></details>");
		}

		WizardPage.renderPrimaryActionBlockOpen(pOut);
		AccelPages.button(pOut, pLocale, "ACCEL_GENERATE", "wizard.accel.generate", "mdmf-btn-primary mdmf-btn-big", gates.accelGenerate, null);
		pOut.append("<span class=\"mdmf-primary-text\">").append(esc(toDecide > 0 ? m(pLocale, "wizard.accel.generate.left", Integer.valueOf(toDecide)) : m(pLocale, "wizard.accel.generate.ready")))
			.append("</span>");
		WizardPage.renderPrimaryActionBlockClose(pOut);

		WizardPage.render2ColSplit(pOut);
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.accel.decisions.title");
		WizardPage.renderKpiRowOpen(pOut);
		WizardPage.renderKpiTile(pOut, String.valueOf(AccelPages.count(snapshot.differences, "ACCEPTED") + AccelPages.count(snapshot.differences, "APPLIED")), m(pLocale, "wizard.accel.decisions.accepted"));
		WizardPage.renderKpiTile(pOut, String.valueOf(AccelPages.count(snapshot.differences, "REJECTED")), m(pLocale, "wizard.accel.decisions.rejected"));
		WizardPage.renderKpiTile(pOut, String.valueOf(toDecide), m(pLocale, "wizard.accel.decisions.left"));
		WizardPage.renderKpiRowClose(pOut);
		pOut.append("<p class=\"mdmf-hint\">").append(esc(m(pLocale, "wizard.accel.decisions.kept", Integer.valueOf(kept.size())))).append("</p>");
		WizardPage.renderSideCardClose(pOut);
		WizardPage.renderGlossaryCard(pOut, pLocale, "wizard.accel.glossary.difference", "wizard.accel.glossary.wrapped", "wizard.accel.glossary.draft");
		WizardPage.render2ColEnd(pOut);
	}

	private static String unescapeJson(final String pText)
	{
		return pText.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", " ");
	}

	private static String natureChipClass(final String pNature)
	{
		return pNature.startsWith("USE_") ? "mdmf-chip-accent" : "REMOVE".equals(pNature) ? "mdmf-chip-bad" : "mdmf-chip-real";
	}

	private static void renderDifferenceRow(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.DifferenceInfo pRow, final WizardRules.ActionGates pGates)
	{
		final boolean draft = AccelPages.DRAFT.matcher(pRow.detail).find();
		pOut.append("<tr id=\"mdmf-DIFFERENCE-").append(esc(pRow.id)).append("\"><td><span class=\"mdmf-chip ").append(natureChipClass(pRow.nature)).append("\">")
			.append(esc(m(pLocale, "wizard.accel.nature." + pRow.nature))).append("</span></td><td><b>").append(esc(pRow.what)).append("</b>");
		if (draft)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\">").append(esc(m(pLocale, "wizard.accel.chip.draft"))).append("</span>");
		}
		pOut.append("</td><td><code>").append(esc(pRow.where)).append("</code>");
		if ("TO_PLACE".equals(pRow.placeState))
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\" title=\"").append(esc(m(pLocale, "wizard.accel.toPlace.hint"))).append("\">").append(esc(m(pLocale, "wizard.accel.chip.toPlace")))
				.append("</span>");
		}
		pOut.append("</td><td>").append(esc(pRow.why.isEmpty() ? pRow.rationale : pRow.why)).append("</td><td>");
		final DecisionActions.Actions actions = DecisionActions.forStatus(pRow.status, ReviewLine.DIFFERENCE);
		// a decided line shows its status, then the opposite decision the status still allows (an ACCEPTED line can be rejected, a REJECTED one accepted; APPLIED neither)
		if (!"PROPOSED".equals(pRow.status))
		{
			pOut.append("<span class=\"mdmf-chip ").append("REJECTED".equals(pRow.status) ? "mdmf-chip-bad" : "mdmf-chip-real").append("\">")
				.append(esc(m(pLocale, "wizard.accel.status." + pRow.status))).append("</span> ");
		}
		if (actions.accept || actions.reject)
		{
			final String attrs = "data-mdmf-nature=\"" + ReviewLine.DIFFERENCE + "\" data-mdmf-id=\"" + esc(pRow.id) + "\"";
			if (actions.accept)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"DECIDE_ACCEPT\" ").append(attrs).append(">")
					.append(esc(m(pLocale, "wizard.accept"))).append("</button> ");
			}
			if (DecisionActions.editable(pRow.status, ReviewLine.DIFFERENCE))
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"mdmf-accel-edit-").append(esc(pRow.id)).append("\">")
					.append(esc(m(pLocale, "wizard.accel.edit"))).append("</button> ");
			}
			if (actions.reject)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DECIDE_REJECT\" ").append(attrs).append(">")
					.append(esc(m(pLocale, "wizard.reject"))).append("</button>");
			}
		}
		pOut.append("</td></tr>");
		if (DecisionActions.editable(pRow.status, ReviewLine.DIFFERENCE))
		{
			pOut.append("<tr id=\"mdmf-accel-edit-").append(esc(pRow.id)).append("\" hidden=\"hidden\"><td colspan=\"5\"><label for=\"mdmf-accel-detail-").append(esc(pRow.id)).append("\">")
				.append(esc(m(pLocale, "wizard.accel.edit.label"))).append("</label><textarea id=\"mdmf-accel-detail-").append(esc(pRow.id)).append("\" rows=\"6\" data-mdmf-no-dirty=\"true\">")
				.append(esc(pRow.detail)).append("</textarea> <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"")
				.append(ReviewLine.DIFFERENCE).append("\" data-mdmf-id=\"").append(esc(pRow.id)).append("\" data-mdmf-detail-from=\"mdmf-accel-detail-").append(esc(pRow.id)).append("\">")
				.append(esc(m(pLocale, "wizard.accel.edit.accept"))).append("</button></td></tr>");
		}
	}

	// -------------------------------------------------------------------------------------------------------- step: Generation

	private static String objectState(final WizardSnapshot.DomainObjectInfo pObject)
	{
		if ("RUNNING".equals(pObject.stepStatus))
		{
			return "RUNNING";
		}
		if ("FAILED".equals(pObject.stepStatus) || "INTERRUPTED".equals(pObject.stepStatus))
		{
			return "NEEDS_PERSON";
		}
		return "PENDING".equals(pObject.stepStatus) ? "WAITING" : "DONE";
	}

	private static boolean isDraft(final WizardSnapshot.DomainObjectInfo pObject)
	{
		return "draft".equalsIgnoreCase(pObject.detail.trim()) || AccelPages.DRAFT.matcher(pObject.detail).find();
	}

	private static void renderGeneration(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale)
	{
		final WizardSnapshot snapshot = pModel.snapshot;
		final WizardRules.ActionGates gates = WizardRules.actionGates(snapshot, pModel.designer, null);
		final boolean generated = snapshot.project != null && !snapshot.project.domainModel.isEmpty();
		WizardPage.renderPageHead(pOut, m(pLocale, "wizard.accel.gen.title"), m(pLocale, "wizard.accel.gen.lead"));
		AccelPages.renderJob(pOut, snapshot, pLocale);
		WizardPage.render2ColStart(pOut);

		pOut.append("<div class=\"mdmf-panel\"><span class=\"mdmf-eyebrow\">").append(esc(m(pLocale, "wizard.accel.gen.objects"))).append("</span>");
		if (snapshot.domainObjects.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(esc(m(pLocale, snapshot.accelJob != null ? "wizard.accel.gen.running" : "wizard.accel.gen.none"))).append("</p>");
		}
		final List<WizardSnapshot.DomainObjectInfo> drafts = new ArrayList<WizardSnapshot.DomainObjectInfo>();
		int done = 0;
		pOut.append("<ul class=\"mdmf-plain-list mdmf-accel-objects\">");
		for (final WizardSnapshot.DomainObjectInfo object : snapshot.domainObjects)
		{
			final String state = AccelPages.objectState(object);
			done += "DONE".equals(state) ? 1 : 0;
			if (AccelPages.isDraft(object))
			{
				drafts.add(object);
			}
			pOut.append("<li>");
			WizardPage.renderStepStateCircle(pOut, AccelPages.isDraft(object) ? "NEEDS_PERSON" : state);
			pOut.append(" <b>").append(esc(m(pLocale, "wizard.accel.kind." + object.kind))).append("</b> <code>").append(esc(object.name)).append("</code>");
			if (!object.path.isEmpty())
			{
				pOut.append(" <span class=\"mdmf-hint\">").append(esc(object.path)).append("</span>");
			}
			if (AccelPages.isDraft(object))
			{
				pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\">").append(esc(m(pLocale, "wizard.accel.chip.draft"))).append("</span>");
			}
			pOut.append("</li>");
		}
		pOut.append("</ul>");
		if (!snapshot.domainObjects.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(esc(m(pLocale, "wizard.accel.gen.count", Integer.valueOf(done), Integer.valueOf(snapshot.domainObjects.size())))).append("</p>");
		}
		pOut.append("</div>");

		if (!drafts.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn mdmf-accel-draft\" role=\"note\"><b>").append(esc(m(pLocale, "wizard.accel.draft.title"))).append("</b> ")
				.append(esc(m(pLocale, "wizard.accel.draft.text", Integer.valueOf(drafts.size())))).append("</div>");
		}

		if (!generated && snapshot.accelJob == null)
		{
			WizardPage.renderPrimaryActionBlockOpen(pOut);
			AccelPages.button(pOut, pLocale, "ACCEL_GENERATE", "wizard.accel.generate", "mdmf-btn-primary mdmf-btn-big", gates.accelGenerate, null);
			pOut.append("<span class=\"mdmf-primary-text\">").append(esc(m(pLocale, "wizard.accel.gen.start.text"))).append("</span>");
			WizardPage.renderPrimaryActionBlockClose(pOut);
		}
		else if (generated)
		{
			pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-goto-step=\"").append(WizardRules.STEP_SUMMARY).append("\">")
				.append(esc(m(pLocale, "wizard.accel.gen.toSummary"))).append("</button></div>");
		}

		WizardPage.render2ColSplit(pOut);
		AccelPages.renderTraceability(pOut, snapshot, pLocale);
		WizardPage.render2ColEnd(pOut);
	}

	/** The traceability block: pack versions, decisions, the project owner and when the domain was written. Who decided each line, and when, is in the EBX history of the Difference table. */
	private static void renderTraceability(final StringBuilder pOut, final WizardSnapshot pSnapshot, final Locale pLocale)
	{
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.accel.trace.title");
		final StringBuilder packs = new StringBuilder();
		for (final WizardSnapshot.ProjectPackInfo pack : pSnapshot.projectPacks)
		{
			packs.append(packs.length() == 0 ? "" : ", ").append(AccelPages.packLabel(pSnapshot, pack.packKey)).append(" v").append(pack.packVersion.isEmpty() ? "-" : pack.packVersion);
		}
		pOut.append("<p class=\"mdmf-hint\"><b>").append(esc(m(pLocale, "wizard.accel.trace.packs"))).append("</b> ").append(esc(packs.length() == 0 ? "-" : packs.toString())).append("</p>");
		final int accepted = AccelPages.count(pSnapshot.differences, "ACCEPTED") + AccelPages.count(pSnapshot.differences, "APPLIED");
		pOut.append("<p class=\"mdmf-hint\"><b>").append(esc(m(pLocale, "wizard.accel.trace.decided"))).append("</b> ")
			.append(esc(m(pLocale, "wizard.accel.trace.decidedCounts", Integer.valueOf(pSnapshot.differences.size()), Integer.valueOf(accepted),
				Integer.valueOf(AccelPages.count(pSnapshot.differences, "REJECTED"))))).append("</p>");
		if (pSnapshot.project != null && !pSnapshot.project.owner.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\"><b>").append(esc(m(pLocale, "wizard.accel.trace.owner"))).append("</b> ").append(esc(pSnapshot.project.owner)).append("</p>");
		}
		String last = "";
		for (final WizardSnapshot.DomainObjectInfo object : pSnapshot.domainObjects)
		{
			if (!object.createdOn.isEmpty() && object.createdOn.compareTo(last) > 0)
			{
				last = object.createdOn;
			}
		}
		if (!last.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\"><b>").append(esc(m(pLocale, "wizard.accel.trace.when"))).append("</b> ").append(esc(last)).append("</p>");
		}
		pOut.append("<p class=\"mdmf-hint\">").append(esc(m(pLocale, "wizard.accel.trace.history"))).append("</p>");
		WizardPage.renderSideCardClose(pOut);
	}

	// ----------------------------------------------------------------------------------------------------------- step: Summary

	private static void renderSummary(final StringBuilder pOut, final WizardPage.Model pModel, final Locale pLocale)
	{
		final WizardSnapshot snapshot = pModel.snapshot;
		final boolean generated = snapshot.project != null && !snapshot.project.domainModel.isEmpty();
		WizardPage.renderPageHead(pOut, m(pLocale, "wizard.accel.sum.title"), m(pLocale, generated ? "wizard.accel.sum.lead.done" : "wizard.accel.sum.lead.todo"));
		WizardPage.render2ColStart(pOut);
		pOut.append("<div class=\"mdmf-panel\">");
		WizardPage.renderKpiRowOpen(pOut);
		WizardPage.renderKpiTile(pOut, String.valueOf(snapshot.projectPacks.size()), m(pLocale, "wizard.accel.sum.packs"));
		WizardPage.renderKpiTile(pOut, String.valueOf(snapshot.differences.size() - WizardRules.differencesToDecide(snapshot)), m(pLocale, "wizard.accel.sum.decided"));
		WizardPage.renderKpiTile(pOut, String.valueOf(snapshot.domainObjects.size()), m(pLocale, "wizard.accel.sum.objects"));
		WizardPage.renderKpiRowClose(pOut);
		if (generated)
		{
			pOut.append("<ul class=\"mdmf-plain-list\"><li><b>").append(esc(m(pLocale, "wizard.accel.sum.dataspace"))).append("</b> <code>").append(esc(snapshot.project.domainDataspace))
				.append("</code></li><li><b>").append(esc(m(pLocale, "wizard.accel.sum.dataset"))).append("</b> <code>").append(esc(snapshot.project.domainDataset))
				.append("</code></li><li><b>").append(esc(m(pLocale, "wizard.accel.sum.model"))).append("</b> <code>").append(esc(snapshot.project.domainModel)).append("</code></li></ul>");
		}
		else
		{
			pOut.append("<p class=\"mdmf-hint\">").append(esc(m(pLocale, "wizard.accel.sum.notYet"))).append("</p>");
		}
		pOut.append("</div>");
		pOut.append("<div class=\"mdmf-panel\"><span class=\"mdmf-eyebrow\">").append(esc(m(pLocale, "wizard.accel.sum.manual.title"))).append("</span><ul class=\"mdmf-plain-list\">");
		for (final String key : new String[] { "wizard.accel.sum.manual.permissions", "wizard.accel.sum.manual.secrets", "wizard.accel.sum.manual.toolbars", "wizard.accel.sum.manual.drafts" })
		{
			pOut.append("<li>").append(esc(m(pLocale, key))).append("</li>");
		}
		pOut.append("</ul></div>");
		WizardPage.render2ColSplit(pOut);
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.accel.sum.after.title");
		pOut.append("<p class=\"mdmf-hint\">").append(esc(m(pLocale, "wizard.accel.sum.after.load"))).append("</p><p class=\"mdmf-hint\">").append(esc(m(pLocale, "wizard.accel.sum.after.version")))
			.append("</p>");
		WizardPage.renderSideCardClose(pOut);
		AccelPages.renderTraceability(pOut, snapshot, pLocale);
		WizardPage.render2ColEnd(pOut);
	}
}
