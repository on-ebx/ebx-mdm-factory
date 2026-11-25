package com.on.ebx.factory.wizard;

import java.util.Locale;

/**
 * Staging-screens lot (2026-10-17, docs/UX-STAGING-TEST-FIXES-C.md), step 4 of the wizard: the counts agree with the matrix (the matrix' own N, optional suggestions
 * apart and never blocking), the build button comes first once nothing blocks, a build that left rows out says so at the TOP (neutral about the cause, with the hook for
 * lot B's causes), "Continue to the master" is secondary while rows are open, and the first-proposal layout (one title, one vocabulary, the button first).
 * JUnit-free: run the main, a failure throws.
 */
public final class StagingScreensTest
{
	public static void main(final String[] pArgs)
	{
		StagingScreensTest.optionalSuggestionsNeverBlockTheBuild();
		StagingScreensTest.theReviewLineQuotesTheMatrixFigures();
		StagingScreensTest.rowsNotLoadedAreSaidAtTheTop();
		StagingScreensTest.continueIsSecondaryWhileRowsAreOpen();
		StagingScreensTest.theFirstProposalHasOneTitleOneVocabularyAndTheButtonFirst();
		StagingScreensTest.theStagingGridFitsItsPanel();
		System.out.println("StagingScreensTest OK");
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

	private static WizardPage.Model model(final WizardSnapshot pSnapshot)
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.snapshot = pSnapshot;
		model.stepStates = WizardRules.stepStates(pSnapshot);
		model.administrator = true;
		model.designer = true;
		model.currentStep = WizardRules.STEP_STAGING;
		return model;
	}

	/** Everything decided, the four connector look-ups of the ret fixture still PROPOSED. */
	private static WizardSnapshot onlyLookupsLeft()
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		snapshot.stagingEntities.get(0).attributes.get(2).status = "ACCEPTED";
		snapshot.stagingEntities.get(1).attributes.get(1).status = "ACCEPTED";
		snapshot.stagingEntities.get(0).proposalLines.clear();
		return snapshot;
	}

	/** Measured live (Tiers B2C): the 3 suggestions left after the whole review kept "Build and load" hidden behind "Review the proposal". */
	private static void optionalSuggestionsNeverBlockTheBuild()
	{
		final WizardSnapshot snapshot = StagingScreensTest.onlyLookupsLeft();
		final Step4Counts counts = Step4Counts.of(snapshot);
		check(counts.waiting() == 0 && counts.optionalWaiting() == 4 && counts.checks.total() == 4, "4 optional suggestions, nothing waiting: " + counts.waiting() + "/" + counts.optionalWaiting());
		check(WizardRules.step4Primary(snapshot, false, false) == WizardRules.Step4Primary.BUILD, "nothing blocks: the step is Build and load");
		final WizardPage.Model model = StagingScreensTest.model(snapshot);
		model.proposalRunExists = true;
		model.proposalDoneMatrixUrl = "/matrix";
		model.matrixNeedYou = 0;
		model.matrixAcceptAll = 0;
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		final int build = html.indexOf("data-mdmf-action=\"STAGING_LOAD_RUN\"");
		check(build > 0 && build < html.indexOf("id=\"mdmf-step4-passes\""), "the build button is in the primary block, above the Advanced fold");
		check(html.contains("class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"STAGING_LOAD_RUN\""), "and it is the primary button");
		check(!html.contains(StagingScreensTest.en("wizard.step4.primary.next.review")) && !html.contains(">Review the proposal</a>"), "no 'will appear here' sentence, no review button");
		check(html.contains(StagingScreensTest.en("wizard.step4.primary.optional.other", Integer.valueOf(4))), "the 4 optional look-ups are named and said not to block");
		check(html.contains("<b>Checks and services</b> 4: 4 optional suggestions") && !html.contains("waiting for review</a>"), "the side card: optional, no 'to review' chip");
		// the card's title counts decisions, not the optional look-ups
		final StillToDecide still = StillToDecide.of(snapshot);
		check(still.blockingTotal() == 0 && still.optionalTotal() == 4 && html.contains(">4 optional suggestions</h3>"), "the card says '4 optional suggestions', not '4 decisions left'");
		// one blocking item brings review back, optional ones still apart
		snapshot.stagingEntities.get(1).attributes.get(1).status = "PROPOSED";
		check(WizardRules.step4Primary(snapshot, false, false) == WizardRules.Step4Primary.REVIEW, "a blocking proposal: review");
	}

	/** "13 points need your attention, the other 93 go with one click": the matrix' own two figures, not a subtraction from the wizard's tally. */
	private static void theReviewLineQuotesTheMatrixFigures()
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		final int waiting = Step4Counts.of(snapshot).waiting();
		final WizardPage.Model model = StagingScreensTest.model(snapshot);
		model.proposalRunExists = true;
		model.proposalDoneMatrixUrl = "/matrix";
		model.matrixNeedYou = 2;
		model.matrixAcceptAll = 93;
		String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(html.contains("<b class=\"mdmf-review-note\">2 points need your attention, the other 93 go with one click</b>"), "the matrix' figures");
		check(!html.contains("the other " + (waiting - 2) + " go"), "not the subtraction (" + (waiting - 2) + ")");
		model.matrixAcceptAll = -1;
		html = WizardPageTest.section(WizardPage.render(model), 4);
		check(!html.contains("mdmf-review-note\""), "unknown acceptAll: no line, never a subtraction from this screen's own tally (staging polish, 2026-10-17)");
		check("13 points need your attention, the other 93 go with one click".equals(WizardPage.reviewNoteText(Locale.ENGLISH, WizardRules.step4MatrixNote(13, 93))), "the pure rule");
		check(WizardPage.reviewNoteText(Locale.FRENCH, WizardRules.step4MatrixNote(13, 93)).equals("13 points demandent votre attention, les 93 autres passent en un clic"), "French");
		final WizardRules.ReviewNote none = WizardRules.step4MatrixNote(0, 93);
		check(none.kind == WizardRules.ReviewNoteKind.NOTHING_FLAGGED && none.others == 93, "nothing flagged keeps the button's figure");
	}

	private static WizardSnapshot loadedSnapshot()
	{
		final WizardSnapshot snapshot = StagingScreensTest.onlyLookupsLeft();
		for (final WizardSnapshot.CapabilityInfo capability : snapshot.stagingEntities.get(0).capabilities)
		{
			capability.status = "SET_ASIDE";
		}
		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_DATASET", "DONE", ""));
		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		return snapshot;
	}

	private static WizardPage.Model modelWithRejects(final WizardSnapshot pSnapshot, final long pRows)
	{
		final WizardPage.Model model = StagingScreensTest.model(pSnapshot);
		model.proposalRunExists = true;
		model.stagingLoadRunExists = true;
		model.proposalDoneMatrixUrl = "/matrix";
		model.stagingRejectsUrl = "/ebx-ui/rejects";
		model.stagingHeldBackCount = pRows;
		final WizardSnapshot.RunGridInfo grid = new WizardSnapshot.RunGridInfo();
		grid.columnLabels.add("ECOM");
		grid.columnLabels.add("In staging");
		final WizardSnapshot.RunGridRow row = new WizardSnapshot.RunGridRow();
		row.label = "Customer";
		final WizardSnapshot.RunGridCell held = new WizardSnapshot.RunGridCell("NEEDS_PERSON", "0 rows", pRows + " held back");
		held.heldBackValue = Long.valueOf(pRows);
		row.cells.add(held);
		row.cells.add(new WizardSnapshot.RunGridCell("DONE", "394 rows", ""));
		grid.rows.add(row);
		model.stagingLoadGrid = grid;
		return model;
	}

	/** The live report: "Continue to the master" on top, the rows not loaded signalled far below and blamed on "a rule set to stop the row" whatever the cause. */
	private static void rowsNotLoadedAreSaidAtTheTop()
	{
		final WizardPage.Model model = StagingScreensTest.modelWithRejects(StagingScreensTest.loadedSnapshot(), 140);
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		final String banner = "140 rows of Customer were not loaded: refused at load time.";
		check(html.contains(banner), "the banner names the rows, the object and a neutral cause");
		check(html.indexOf(banner) < html.indexOf("mdmf-primary-block") && html.indexOf(banner) < html.indexOf("mdmf-run-grid"), "it is the first thing of the step");
		check(html.contains("href=\"/ebx-ui/rejects\" target=\"_top\">Review</a>"), "with its Review link");
		check(!html.toLowerCase(Locale.ROOT).contains("a rule set to stop"), "it no longer blames a rule");
		final String fr = StagingScreensTest.frenchStep4(model);
		check(fr.contains("140 lignes de Customer n&#39;ont pas été chargées : refus au chargement."), "French: " + fr.substring(Math.max(0, fr.indexOf("lignes de Customer") - 30), Math.min(fr.length(), fr.indexOf("lignes de Customer") + 90)));
		// two objects: the breakdown is listed
		final WizardSnapshot.RunGridRow second = new WizardSnapshot.RunGridRow();
		second.label = "Order";
		final WizardSnapshot.RunGridCell held2 = new WizardSnapshot.RunGridCell("NEEDS_PERSON", "0 rows", "12 held back");
		held2.heldBackValue = Long.valueOf(12);
		second.cells.add(held2);
		model.stagingLoadGrid.rows.add(second);
		model.stagingHeldBackCount = 152;
		check(WizardPageTest.section(WizardPage.render(model), 4).contains("152 rows were not loaded: Customer 140, Order 12. Cause: refused at load time."), "several objects: one line with the breakdown");
		// no breakdown from the reader: the total alone
		model.stagingLoadGrid = new WizardSnapshot.RunGridInfo();
		check(WizardPageTest.section(WizardPage.render(model), 4).contains("152 rows were not loaded: refused at load time."), "no breakdown: the total");
		// nothing open, or a build running: no banner
		final WizardPage.Model clean = StagingScreensTest.modelWithRejects(StagingScreensTest.loadedSnapshot(), 0);
		check(!WizardPageTest.section(WizardPage.render(clean), 4).contains("mdmf-step4-rejects"), "no rows open: no banner");
		// the hook for lot B: the cause comes from ONE method, neutral until the categories exist
		check("refused at load time".equals(WizardPage.rejectCauseWording(model, "Customer", Locale.ENGLISH)) && "refus au chargement".equals(WizardPage.rejectCauseWording(model, "Customer", Locale.FRENCH)),
			"rejectCauseWording is the single place the cause is worded");
	}

	private static String frenchStep4(final WizardPage.Model pModel)
	{
		pModel.locale = Locale.FRENCH;
		final String html = WizardPageTest.section(WizardPage.render(pModel), 4);
		pModel.locale = Locale.ENGLISH;
		return html;
	}

	private static void continueIsSecondaryWhileRowsAreOpen()
	{
		final WizardPage.Model open = StagingScreensTest.modelWithRejects(StagingScreensTest.loadedSnapshot(), 140);
		final String html = WizardPageTest.section(WizardPage.render(open), 4);
		check(WizardRules.step4Primary(open.snapshot, false, false) == WizardRules.Step4Primary.CONTINUE, "the staging is loaded and nothing blocks");
		check(html.contains("class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" href=\"/ebx-ui/rejects\" target=\"_top\">Review</a>"), "reviewing the rows is the primary gesture");
		check(html.contains("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-goto-step=\"5\">" + StagingScreensTest.en("wizard.step4.build.continue") + "</button>"), "Continue to the master is plain");
		check(html.contains(StagingScreensTest.en("wizard.step4.rejects.continueNote")), "with the note that the rows stay out of the master");
		final WizardPage.Model clean = StagingScreensTest.modelWithRejects(StagingScreensTest.loadedSnapshot(), 0);
		final String cleanHtml = WizardPageTest.section(WizardPage.render(clean), 4);
		check(cleanHtml.contains("class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-goto-step=\"5\">") && !cleanHtml.contains(StagingScreensTest.en("wizard.step4.rejects.continueNote")),
			"no rows open: Continue is the primary, as before");
	}

	private static void theFirstProposalHasOneTitleOneVocabularyAndTheButtonFirst()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.code = "P";
		project.label = "P";
		project.mode = "GREENFIELD";
		snapshot.project = project;
		final WizardPage.Model model = StagingScreensTest.model(snapshot);
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		final String title = StagingScreensTest.en("wizard.step4.title");
		check(html.split(java.util.regex.Pattern.quote(title), -1).length - 1 == 1, "the title appears once");
		check(!html.contains("Business object = a kind of record the master will hold"), "no inline vocabulary (the side glossary holds it)");
		check(html.contains("class=\"mdmf-glossary") || html.contains(StagingScreensTest.en("wizard.step4.glossary.businessObject.term")), "the side glossary is there");
		final int button = html.indexOf("data-mdmf-action=\"STAGING_PROPOSAL_RUN\"");
		final int intro = html.indexOf(StagingScreensTest.en("wizard.step4.run.intro.lead"));
		check(button > 0 && intro > button, "'Propose the staging' comes before the explanation");
		check(!html.contains("both applications side by side") && html.contains("your applications side by side"), "the glossary does not say 'two applications'");
		check(!StagingScreensTest.en("wizard.step4.glossary.heldBack.def").contains("rule"), "held back is not blamed on a rule");
	}

	private static void theStagingGridFitsItsPanel()
	{
		final WizardPage.Model model = StagingScreensTest.modelWithRejects(StagingScreensTest.loadedSnapshot(), 3);
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(html.contains("<table class=\"mdmf-run-grid mdmf-run-grid-fixed\">"), "the grid has the fixed layout class: " + html.substring(Math.max(0, html.indexOf("mdmf-run-grid") - 40), html.indexOf("mdmf-run-grid") + 80));
	}
}
