package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.mappingmatrix.MatrixCounter;
import com.on.ebx.factory.mappingmatrix.MatrixHtml;
import com.on.ebx.factory.mappingmatrix.MatrixModel;

/**
 * JUnit-free: run the main, a failure throws. UX lot C3 (2026-10-14, docs/UX-C3-STEPS-1-4.md): (1) step 1 without a project keeps its button above the fold - a
 * coarse proxy for the page's height (visible block elements before the button, closed folds not counted: it ignores the 2 drawings, which the test checks apart; the
 * real height was measured in headless Chrome, see the doc) and the places the folded things now sit; (2) step 4 in
 * review reads "Review the proposal" with the SAME figure as the Mapping matrix header ("17 points need your attention, the other 99 go with one click").
 */
public final class WizardC3StepsTest
{
	/** Visible block elements before the button of an empty step 1 on the main branch before this lot: 32 (measured by this same proxy on b5c71760). */
	private static final int BLOCKS_BEFORE_MAIN = 32;

	public static void main(final String[] pArgs)
	{
		WizardC3StepsTest.step1HeightProxy();
		WizardC3StepsTest.step1FoldedPieces();
		WizardC3StepsTest.step1AfterTheProjectKeepsItsDescription();
		WizardC3StepsTest.reviewNoteRule();
		WizardC3StepsTest.reviewNoteEqualsTheMatrixHeader();
		WizardC3StepsTest.step4ButtonAndNote();
		System.out.println("WizardC3StepsTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static String en(final String pKey, final Object... pArgs)
	{
		return WizardPage.esc(WizardMessages.get(Locale.ENGLISH, pKey, pArgs));
	}

	static WizardPage.Model model(final WizardSnapshot pSnapshot, final int pStep)
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.snapshot = pSnapshot;
		model.stepStates = WizardRules.stepStates(pSnapshot);
		model.administrator = true;
		model.designer = true;
		model.currentStep = pStep;
		return model;
	}

	/** The html with every CLOSED {@code <details>} body removed (nested ones too): what a person sees without clicking. */
	static String withoutClosedFolds(final String pHtml)
	{
		final StringBuilder out = new StringBuilder();
		final Matcher tag = Pattern.compile("<details([^>]*)>|</details>").matcher(pHtml);
		int last = 0;
		int hiddenDepth = 0; // > 0 while inside a closed fold
		int depthInHidden = 0;
		while (tag.find())
		{
			if (hiddenDepth == 0)
			{
				out.append(pHtml, last, tag.start());
			}
			last = tag.end();
			if (tag.group().startsWith("</"))
			{
				if (hiddenDepth > 0)
				{
					depthInHidden--;
					if (depthInHidden == 0)
					{
						hiddenDepth = 0;
					}
				}
				else
				{
					out.append(tag.group());
				}
			}
			else if (hiddenDepth > 0)
			{
				depthInHidden++;
			}
			else if (tag.group(1).contains("open=\"open\""))
			{
				out.append(tag.group());
			}
			else
			{
				// a closed fold: keep its summary only
				hiddenDepth = 1;
				depthInHidden = 1;
				out.append(tag.group());
				final int sum = pHtml.indexOf("</summary>", tag.end());
				final int next = pHtml.indexOf("<summary>", tag.end());
				if (next == tag.end() && sum > 0)
				{
					out.append(pHtml, tag.end(), sum + "</summary>".length());
				}
			}
		}
		if (hiddenDepth == 0)
		{
			out.append(pHtml.substring(last));
		}
		return out.toString();
	}

	/** Block elements a person sees stacked: the proxy for vertical weight. Radios (visually hidden) and hidden inputs do not count. */
	static int visibleBlocks(final String pHtml)
	{
		final Matcher m = Pattern.compile("<(div|p|label|details|ul|li|textarea|section|h[1-4]|button|input(?![^>]*type=\"(?:radio|hidden)\"))[\\s>]").matcher(pHtml);
		int n = 0;
		while (m.find())
		{
			n++;
		}
		return n;
	}

	private static String step1Before()
	{
		final String page = WizardPage.render(WizardC3StepsTest.model(new WizardSnapshot(), WizardRules.STEP_PROJECT));
		final String html = WizardPageTest.section(page, 1);
		final int button = html.indexOf("data-mdmf-action=\"SAVE_PROJECT\"");
		check(button > 0, "the Create button is on the page");
		return html.substring(0, button);
	}

	private static void step1HeightProxy()
	{
		final String before = WizardC3StepsTest.step1Before();
		final int blocks = WizardC3StepsTest.visibleBlocks(WizardC3StepsTest.withoutClosedFolds(before));
		System.out.println("step 1: " + blocks + " visible block elements before the button (main before the lot: " + WizardC3StepsTest.BLOCKS_BEFORE_MAIN + ")");
		check(blocks <= WizardC3StepsTest.BLOCKS_BEFORE_MAIN - 4, "step 1 is lighter than before by at least 4 blocks: " + blocks);
		check(blocks <= 28, "an absolute ceiling for the blocks above the button: " + blocks);
		// no drawing and no axis before the button unless inside a closed fold: nothing tall left on the page
		final String seen = WizardC3StepsTest.withoutClosedFolds(before);
		check(!seen.contains("<svg") && !seen.contains("mdmf-style-axis"), "the drawing and the axis line are folded");
		check(!seen.contains("<textarea"), "the description is folded");
		check(!seen.contains("mdmf-style-consequence"), "the consequence sentence sits beside the button, not above it");
	}

	private static void step1FoldedPieces()
	{
		final String page = WizardPage.render(WizardC3StepsTest.model(new WizardSnapshot(), WizardRules.STEP_PROJECT));
		final String html = WizardPageTest.section(page, 1);
		final String lead = WizardC3StepsTest.en("wizard.step1.lead");
		check(html.contains(lead) && lead.length() < 100, "the intro is one line: " + lead.length() + " characters");
		final int desc = html.indexOf("mdmf-step1-desc");
		check(desc > 0 && !html.substring(desc, html.indexOf(">", desc)).contains("open"), "the description fold is closed on a new project");
		check(html.indexOf("id=\"mdmfDescription\"") > desc && html.contains("name=\"mdmfDescriptionValue\""), "the textarea is inside it and still posts");
		check(html.contains(WizardC3StepsTest.en("wizard.step1.description.toggle")), "its summary line");
		// the drawing and the axis are in the Details of each real card, in this order: summary, axis, drawing, facts
		final int consolidation = html.indexOf("value=\"CONSOLIDATION\"");
		final int details = html.indexOf("<details class=\"mdmf-style-more\">", consolidation);
		final int axis = html.indexOf("mdmf-style-axis", details);
		final int svg = html.indexOf("<svg", details);
		check(details > 0 && axis > details && svg > axis && html.indexOf("mdmf-style-card-facts", details) > svg, "Details holds axis, drawing, facts");
		check(html.indexOf("<svg", consolidation) > details, "no drawing before the card's own Details");
		final int button = html.indexOf("data-mdmf-action=\"SAVE_PROJECT\"");
		final int consequence = html.indexOf("mdmf-style-consequence");
		check(consequence > button, "the consequence follows the button");
		// the two cards are one row (same grid), one id each
		check(WizardC3StepsTest.count(html, "class=\"mdmf-style-cards mdmf-style-cards-main\"") == 1, "one row of cards");
		// every field still posts: label, code, description, style radio
		check(html.contains("name=\"mdmfLabelValue\"") && html.contains("name=\"mdmfCodeValue\"") && html.contains("name=\"mdmfStyleRadio\""), "controls keep their names");
		// 2026-10-14: the sentence beside the button carries every selectable style's text, so a click on another card can swap it (wizard.js);
		// 2026-11-10: Centralisation is "coming soon" (not selectable), so only Consolidation's sentence rides on the span
		check(html.contains("id=\"mdmfStyleConsequence\"") && html.contains("data-mdmf-style-consolidation=\"") && !html.contains("data-mdmf-style-centralisation=\"")
			&& !html.contains("data-mdmf-style-registry="), "the consequence carries the selectable styles' sentences");
		// a refused description opens its fold
		final WizardPage.Model refused = WizardC3StepsTest.model(new WizardSnapshot(), WizardRules.STEP_PROJECT);
		refused.problems.add(new WizardRules.Problem(WizardRules.STEP_PROJECT, "description", "wizard.error.llmSampleSizeInvalid"));
		final String refusedHtml = WizardPageTest.section(WizardPage.render(refused), 1);
		final int d2 = refusedHtml.indexOf("mdmf-step1-desc");
		check(refusedHtml.substring(d2, refusedHtml.indexOf(">", d2)).contains("open=\"open\""), "a refused description opens its fold");
	}

	private static void step1AfterTheProjectKeepsItsDescription()
	{
		final WizardPage.Model model = WizardC3StepsTest.model(WizardRulesTest.retSnapshot(), WizardRules.STEP_PROJECT);
		model.projectDraft.code = "ret";
		model.projectDraft.label = "ret";
		model.projectDraft.description = "Suppliers of the group";
		final String html = WizardPageTest.section(WizardPage.render(model), 1);
		final int desc = html.indexOf("mdmf-step1-desc");
		check(html.substring(desc, html.indexOf(">", desc)).contains("open=\"open\"") && html.contains("Suppliers of the group"), "a stored description is shown, fold open");
		check(html.contains("class=\"mdmf-style-consequence\""), "the consequence is still there once the project exists");
	}

	private static int count(final String pHaystack, final String pNeedle)
	{
		int n = 0;
		for (int i = pHaystack.indexOf(pNeedle); i >= 0; i = pHaystack.indexOf(pNeedle, i + 1))
		{
			n++;
		}
		return n;
	}

	// ----------------------------------------------------------------------------------------------------------------- step 4

	private static void reviewNoteRule()
	{
		// staging polish (2026-10-17): the rule takes the matrix' two figures only (needYou, acceptAll); the old waiting-based subtraction is gone
		WizardRules.ReviewNote note = WizardRules.step4MatrixNote(17, 99);
		check(note.kind == WizardRules.ReviewNoteKind.FLAGGED && note.attention == 17 && note.others == 99, "17 flagged, 99 with the button");
		note = WizardRules.step4MatrixNote(0, 99);
		check(note.kind == WizardRules.ReviewNoteKind.NOTHING_FLAGGED && note.others == 99, "nothing flagged");
		note = WizardRules.step4MatrixNote(-1, -1);
		check(note.kind == WizardRules.ReviewNoteKind.NONE, "an unknown matrix figure says nothing");
		note = WizardRules.step4MatrixNote(2, -1);
		check(note.kind == WizardRules.ReviewNoteKind.NONE, "half a figure says nothing (never a subtraction)");
		note = WizardRules.step4MatrixNote(0, 0);
		check(note.kind == WizardRules.ReviewNoteKind.NONE, "nothing left in the matrix: the matrix says so, not this line");
		check("17 points need your attention, the other 99 go with one click".equals(WizardPage.reviewNoteText(Locale.ENGLISH, WizardRules.step4MatrixNote(17, 99))), "the sentence");
		check("1 point needs your attention, the other 4 go with one click".equals(WizardPage.reviewNoteText(Locale.ENGLISH, WizardRules.step4MatrixNote(1, 4))), "the singular");
		check("3 points need your attention".equals(WizardPage.reviewNoteText(Locale.ENGLISH, WizardRules.step4MatrixNote(3, 0))), "nothing else waits");
		check("Nothing flagged: one click accepts everything".equals(WizardPage.reviewNoteText(Locale.ENGLISH, WizardRules.step4MatrixNote(0, 8))), "nothing flagged");
		check(WizardPage.reviewNoteText(Locale.ENGLISH, WizardRules.step4MatrixNote(-1, 8)).isEmpty(), "unknown: no line");
		check(WizardPage.reviewNoteText(Locale.FRENCH, WizardRules.step4MatrixNote(17, 99)).startsWith("17 points demandent votre attention, les 99 autres"), "French");
	}

	/** The shape of project UxCrmSf2: 116 proposals still waiting, 17 of them flagged (a flagged link on an already accepted field), 99 the bulk button takes. */
	static MatrixModel.Project uxCrmSf2Shape()
	{
		final java.util.List<MatrixModel.Attr> attrs = new ArrayList<MatrixModel.Attr>();
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		for (int i = 0; i < 116; i++)
		{
			final boolean flagged = i < 17;
			attrs.add(new MatrixModel.Attr("a" + i, "f" + i, "STRING", false, false, false, flagged ? "ACCEPTED" : "PROPOSED", Integer.valueOf(90)));
			if (flagged)
			{
				cells.put("a" + i, new MatrixModel.Cell("PROPOSED", "m" + i, "COL" + i, null, Integer.valueOf(90), "LLM", null, null, 0, null, null, "EMPTY_COLUMN", "empty in SAP", null));
			}
		}
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "T", "ACCEPTED", null, "", false, null, cells, Integer.valueOf(90), "");
		return new MatrixModel.Project("P", new ArrayList<MatrixModel.Source>(Arrays.asList(new MatrixModel.Source("s1", "SAP", "#000"))),
			new ArrayList<MatrixModel.Entity>(Arrays.asList(new MatrixModel.Entity("e1", "P", attrs, Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100)))));
	}

	private static void reviewNoteEqualsTheMatrixHeader()
	{
		final MatrixModel.Project project = WizardC3StepsTest.uxCrmSf2Shape();
		final int needYou = MatrixCounter.needYou(project);
		check(needYou == 17, "the matrix counts 17 on the UxCrmSf2 shape: " + needYou);
		final MatrixCounter.Tally tally = MatrixCounter.of(project, 60);
		check(tally.withButton == 99 && tally.stillToDecide() == 116, "99 go with the button, 116 in all: " + tally.withButton);
		// the header of the matrix itself reads the same number
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		final String matrix = MatrixHtml.render(project, state);
		check(matrix.contains("17 points need your attention"), "the matrix header says 17 points");
		// and the wizard sentence built from that figure and the 116 waiting proposals
		final String sentence = WizardPage.reviewNoteText(Locale.ENGLISH, WizardRules.step4MatrixNote(tally.needYou, tally.acceptAll));
		check("17 points need your attention, the other 99 go with one click".equals(sentence), sentence);
		// the same number, word for word, as the header: the wizard line starts with the header's own sentence
		check(sentence.startsWith("17 points need your attention"), "same wording as the header");
	}

	private static void step4ButtonAndNote()
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		final int waiting = Step4Counts.of(snapshot).waiting();
		check(waiting > 0, "the fixture has points waiting");
		final WizardPage.Model model = WizardC3StepsTest.model(snapshot, WizardRules.STEP_STAGING);
		model.proposalRunExists = true;
		model.proposalDoneMatrixUrl = "/matrix?o=Personne";
		model.matrixNeedYou = 2;
		model.matrixAcceptAll = 5;
		String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(html.contains(">Review the proposal</a>") && !html.contains("Review " + waiting + " point"), "the button names the action, not a count");
		check(html.contains("<b class=\"mdmf-review-note\">2 points need your attention, the other 5 go with one click</b>"), "the line under it: the matrix' figures");
		model.matrixNeedYou = 0;
		html = WizardPageTest.section(WizardPage.render(model), 4);
		check(html.contains("mdmf-review-note\">Nothing flagged: one click accepts everything</b>"), "nothing flagged");
		model.matrixNeedYou = -1;
		model.matrixAcceptAll = -1;
		html = WizardPageTest.section(WizardPage.render(model), 4);
		check(html.contains(">Review the proposal</a>") && !html.contains("mdmf-review-note\""), "unknown figure: the button alone");
	}
}
