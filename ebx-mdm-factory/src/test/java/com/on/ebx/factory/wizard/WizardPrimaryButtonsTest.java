package com.on.ebx.factory.wizard;

import java.util.Locale;

import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.staging.RunProgress;

/**
 * JUnit-free: run the main, a failure throws. UX lot U4 (2026-10-13, RAPPORT 2.2 / 2.7 / 2.9 / 3.4): one primary button per step. The pure rules of
 * {@link WizardRules} (project code from the label, the single step 4 action, the rail note) and the pages they decide - step 1 without a project (the button
 * above the fold, the options folded, "Next" hidden), step 4 in each of its six states, the "Apply" buttons that wait for the staging, the reset button at the end
 * of the step, the rail that says why a step is closed.
 */
public final class WizardPrimaryButtonsTest
{
	private static final String PRIMARY = "mdmf-btn-primary";

	public static void main(final String[] pArgs)
	{
		WizardPrimaryButtonsTest.projectCodeIsProposedFromTheLabel();
		WizardPrimaryButtonsTest.step4PrimaryRule();
		WizardPrimaryButtonsTest.railNoteAndNextRules();
		WizardPrimaryButtonsTest.step1BeforeTheProject();
		WizardPrimaryButtonsTest.step1AfterTheProject();
		WizardPrimaryButtonsTest.step1AdvancedOpensOnlyWhenSomethingInsideNeedsThePerson();
		WizardPrimaryButtonsTest.step4Propose();
		WizardPrimaryButtonsTest.step4Running();
		WizardPrimaryButtonsTest.step4Review();
		WizardPrimaryButtonsTest.step4Build();
		WizardPrimaryButtonsTest.step4Continue();
		WizardPrimaryButtonsTest.step4LoadingShowsTheLivePanelOnly();
		WizardPrimaryButtonsTest.applyWaitsForTheStaging();
		WizardPrimaryButtonsTest.resetIsTheLastThingOfTheStepAndSaysItIsDestructive();
		WizardPrimaryButtonsTest.theRailSaysWhyAStepIsClosed();
		WizardPrimaryButtonsTest.noGreyedButtonWithExcusesInStep4();
		System.out.println("WizardPrimaryButtonsTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static int count(final String pHaystack, final String pNeedle)
	{
		int count = 0;
		int at = pHaystack.indexOf(pNeedle);
		while (at >= 0)
		{
			count++;
			at = pHaystack.indexOf(pNeedle, at + pNeedle.length());
		}
		return count;
	}

	/** What a person sees without opening a fold of step 4: everything above the "Advanced" per-object cards (the cards keep their own, folded, buttons). */
	private static String visible(final String pStep4Html)
	{
		final int at = pStep4Html.indexOf("id=\"mdmf-step4-passes\"");
		return at < 0 ? pStep4Html : pStep4Html.substring(0, at);
	}

	private static String en(final String pKey, final Object... pArgs)
	{
		return WizardPage.esc(WizardMessages.get(Locale.ENGLISH, pKey, pArgs));
	}

	private static WizardPage.Model model(final WizardSnapshot pSnapshot, final int pStep)
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.snapshot = pSnapshot;
		model.stepStates = WizardRules.stepStates(pSnapshot);
		model.administrator = true;
		model.designer = true;
		model.currentStep = pStep;
		return model;
	}

	/** Step 4 with every attribute decided and no proposal line, capability or mapping left waiting: nothing to review. */
	private static WizardSnapshot decidedSnapshot()
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		snapshot.stagingEntities.get(0).attributes.get(2).status = "ACCEPTED";
		snapshot.stagingEntities.get(1).attributes.get(1).status = "ACCEPTED";
		snapshot.stagingEntities.get(0).proposalLines.clear();
		for (final WizardSnapshot.CapabilityInfo capability : snapshot.stagingEntities.get(0).capabilities)
		{
			capability.status = "SET_ASIDE";
		}
		return snapshot;
	}

	// ---------------------------------------------------------------------------------------------------------------- pure rules

	private static void projectCodeIsProposedFromTheLabel()
	{
		check("UxCrmSf".equals(WizardRules.projectCodeFromLabel("Ux Crm Sf")), "words joined, first letters up");
		check("UxCRMSf".equals(WizardRules.projectCodeFromLabel("ux CRM sf")), "the rest of a word is left as typed");
		check("Societes2026".equals(WizardRules.projectCodeFromLabel("Sociétés 2026")), "accents dropped, digits kept");
		check("Fournisseurs".equals(WizardRules.projectCodeFromLabel("  Fournisseurs !! ")), "punctuation and blanks are separators");
		check("Tiers".equals(WizardRules.projectCodeFromLabel("2026 - Tiers")), "a leading digit run is dropped: a code starts with a letter");
		check("".equals(WizardRules.projectCodeFromLabel("2026")) && "".equals(WizardRules.projectCodeFromLabel("   ")) && "".equals(WizardRules.projectCodeFromLabel(null)),
			"no letter, no code");
		final StringBuilder longLabel = new StringBuilder();
		for (int i = 0; i < 30; i++)
		{
			longLabel.append("word ");
		}
		check(WizardRules.projectCodeFromLabel(longLabel.toString()).length() == WizardRules.CODE_MAX, "cut at the code maximum");
		check(WizardRules.projectCodeFromLabel("Customer 360").matches(WizardRules.CODE_PATTERN), "what is proposed is always a valid code");
	}

	private static void step4PrimaryRule()
	{
		final WizardRules.Step4Primary propose = WizardRules.Step4Primary.PROPOSE;
		check(WizardRules.step4Primary(false, false, false, false, 0, false) == propose, "nothing proposed: propose");
		check(WizardRules.step4Primary(true, false, false, false, 0, false) == WizardRules.Step4Primary.RUNNING, "the proposal runs");
		check(WizardRules.step4Primary(true, false, true, true, 5, true) == WizardRules.Step4Primary.RUNNING, "a running proposal wins over what is on screen");
		check(WizardRules.step4Primary(false, true, true, true, 0, false) == WizardRules.Step4Primary.LOADING, "the build runs");
		check(WizardRules.step4Primary(false, false, true, true, 16, false) == WizardRules.Step4Primary.REVIEW, "points to review: review");
		check(WizardRules.step4Primary(false, false, true, true, 1, true) == WizardRules.Step4Primary.REVIEW, "review wins over continue while a point waits, built or not");
		check(WizardRules.step4Primary(false, false, true, true, 0, false) == WizardRules.Step4Primary.BUILD, "nothing left, an accepted object: build and load");
		check(WizardRules.step4Primary(false, false, true, true, 0, true) == WizardRules.Step4Primary.CONTINUE, "built and loaded, nothing left: continue");
		check(WizardRules.step4Primary(false, false, true, false, 0, false) == propose, "every object rejected: propose again");
		// from a snapshot: SUPERSEDED counts nowhere
		final WizardSnapshot snapshot = decidedSnapshot();
		check(WizardRules.step4Primary(snapshot, false, false) == WizardRules.Step4Primary.BUILD, "the decided ret snapshot builds");
		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		check(WizardRules.step4Primary(snapshot, false, false) == WizardRules.Step4Primary.CONTINUE, "and continues once loaded");
		check(WizardRules.step4Primary(WizardRulesTest.retSnapshot(), false, false) == WizardRules.Step4Primary.REVIEW, "ret as measured: review");
		check(WizardRules.step4Primary(new WizardSnapshot(), false, false) == propose, "an empty project proposes");
		final WizardSnapshot superseded = decidedSnapshot();
		superseded.stagingEntities.get(0).status = ReviewLine.SUPERSEDED;
		superseded.stagingEntities.get(1).status = ReviewLine.SUPERSEDED;
		check(WizardRules.step4Primary(superseded, false, false) == propose, "only superseded objects: nothing to build");
		check(!WizardRules.stagingExists(new WizardSnapshot()), "no staging before its dataset step is done");
		final WizardSnapshot staged = new WizardSnapshot();
		staged.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_DATASET", "DONE", ""));
		check(WizardRules.stagingExists(staged), "the staging exists once its dataset step is done");
	}

	private static void railNoteAndNextRules()
	{
		final WizardRules.StepState[] states = WizardRules.stepStates(new WizardSnapshot());
		check(WizardRules.railBlockedNote(states[WizardRules.STEP_SOURCES]) != null && "wizard.blocked.needProject".equals(WizardRules.railBlockedNote(states[WizardRules.STEP_SOURCES])),
			"a closed step gives its reason");
		check(WizardRules.railBlockedNote(states[WizardRules.STEP_PROJECT]) == null, "an open step gives none");
		check(WizardRules.railBlockedNote(null) == null, "null-safe");
		check(!WizardRules.nextButtonVisible(false) && WizardRules.nextButtonVisible(true), "Next exists once the project does");
		check(WizardRules.step1AdvancedOpen(true, false) && WizardRules.step1AdvancedOpen(false, true) && !WizardRules.step1AdvancedOpen(false, false), "the fold opens only when needed");
	}

	// ---------------------------------------------------------------------------------------------------------------- step 1

	private static void step1BeforeTheProject()
	{
		final String page = WizardPage.render(WizardPrimaryButtonsTest.model(new WizardSnapshot(), WizardRules.STEP_PROJECT));
		final String html = WizardPageTest.section(page, 1);
		check(WizardPrimaryButtonsTest.count(html, PRIMARY) == 1, "step 1 holds ONE primary button: " + WizardPrimaryButtonsTest.count(html, PRIMARY));
		final int button = html.indexOf("data-mdmf-action=\"SAVE_PROJECT\"");
		check(button > 0 && html.lastIndexOf(PRIMARY, button) > html.lastIndexOf("<button", button - 1) - 80, "the one primary is Create the project");
		check(html.contains(WizardPrimaryButtonsTest.en("wizard.step1.save.create")), "its label");
		// the label comes first, the code follows it and is proposed from it
		check(html.indexOf("id=\"mdmfLabel\"") > 0 && html.indexOf("id=\"mdmfLabel\"") < html.indexOf("id=\"mdmfCode\""), "label before code");
		check(html.contains("id=\"mdmfLabel\" name=\"mdmfLabelValue\" value=\"\" autocomplete=\"off\" data-mdmf-code-source=\"mdmfCode\""), "the label feeds the code");
		check(html.contains(WizardPrimaryButtonsTest.en("wizard.field.code.hint.proposed")), "the hint says the code is proposed");
		// two real cards on the page, the three others folded behind one line, all BEFORE the button (landing leftovers, 2026-10-17: form, style, button)
		check(html.contains("name=\"mdmfStyleRadio\" value=\"CONSOLIDATION\" checked=\"checked\"/>") && html.contains("name=\"mdmfStyleRadio\" value=\"CENTRALISATION\" disabled=\"disabled\"/>"),
			"consolidation (checked) and centralisation (coming soon, disabled) are the cards");
		final int later = html.indexOf("mdmf-style-later");
		check(later > 0 && later < button, "the folded styles sit above the button");
		check(html.indexOf("value=\"REGISTRY\"") > later && html.indexOf("value=\"COEXISTENCE\"") > later, "registry and coexistence are inside the fold");
		check(html.indexOf("value=\"CENTRALISATION\"") < later && html.indexOf("value=\"CONSOLIDATION\"") < later, "the two real cards are outside it");
		check(html.contains(WizardPrimaryButtonsTest.en("wizard.style.later")), "the fold's line");
		// the two options nobody has to touch: under Advanced, below the button, closed, with their defaults in one sentence
		final int advanced = html.indexOf("mdmf-step1-advanced");
		check(advanced > button, "Advanced follows the button");
		check(html.indexOf("name=\"mdmfModeRadio\"") > advanced && html.indexOf("name=\"mdmfLlmSharingRadio\"") > advanced, "staging mode and language-model sharing are inside it");
		check(!html.substring(advanced, html.indexOf(">", advanced)).contains("open"), "closed by default");
		check(html.contains("class=\"mdmf-hint mdmf-step1-defaults\"") && html.contains(WizardPrimaryButtonsTest.en("wizard.step1.advanced.mode.GREENFIELD"))
			&& html.contains("<b id=\"mdmfAdvLlm\">Shapes only</b>"), "the defaults in one sentence");
		// no project yet: no Next at all, Back disabled
		check(page.contains("id=\"mdmfWizNext\" hidden=\"hidden\""), "Next is hidden until the project exists");
		check(WizardPrimaryButtonsTest.count(page, "id=\"mdmfWizNext\"") == 1 && !page.contains("mdmf-btn mdmf-btn-primary\" id=\"mdmfWizNext\""), "and it is never green");
	}

	private static void step1AfterTheProject()
	{
		final WizardPage.Model model = WizardPrimaryButtonsTest.model(WizardRulesTest.retSnapshot(), WizardRules.STEP_PROJECT);
		model.projectDraft.code = "ret";
		model.projectDraft.label = "ret";
		final String page = WizardPage.render(model);
		final String html = WizardPageTest.section(page, 1);
		check(WizardPrimaryButtonsTest.count(html, PRIMARY) == 1, "still one primary once the project exists");
		check(html.contains(PRIMARY + " mdmf-btn-big\" data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"2\">"
			+ WizardPrimaryButtonsTest.en("wizard.step1.continue", WizardMessages.get(Locale.ENGLISH, "wizard.step.sources"))), "the primary is the way to Sources");
		check(html.contains("class=\"mdmf-btn\" data-mdmf-action=\"SAVE_PROJECT\">" + WizardPrimaryButtonsTest.en("wizard.step1.save.update")), "saving the fields is the secondary gesture");
		check(!page.contains("id=\"mdmfWizNext\" hidden"), "Next exists");
		check(!html.contains("data-mdmf-code-source"), "the code no longer follows the label once the project exists");
	}

	private static void step1AdvancedOpensOnlyWhenSomethingInsideNeedsThePerson()
	{
		final WizardPage.Model model = WizardPrimaryButtonsTest.model(new WizardSnapshot(), WizardRules.STEP_PROJECT);
		model.problems.add(new WizardRules.Problem(WizardRules.STEP_PROJECT, "llmSampleSize", "wizard.error.llmSampleSizeInvalid"));
		final String html = WizardPageTest.section(WizardPage.render(model), 1);
		final int advanced = html.indexOf("mdmf-step1-advanced");
		check(html.substring(advanced, html.indexOf(">", advanced)).contains("open=\"open\""), "a refused value inside opens the fold");
		final WizardPage.Model capped = WizardPrimaryButtonsTest.model(new WizardSnapshot(), WizardRules.STEP_PROJECT);
		capped.snapshot.llmValueSharingMax = "NONE";
		final String cappedHtml = WizardPageTest.section(WizardPage.render(capped), 1);
		final int cappedAt = cappedHtml.indexOf("mdmf-step1-advanced");
		check(cappedHtml.substring(cappedAt, cappedHtml.indexOf(">", cappedAt)).contains("open=\"open\""), "a choice above the instance cap opens it");
	}

	// ---------------------------------------------------------------------------------------------------------------- step 4

	private static void step4Propose()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		snapshot.project = WizardRulesTest.retSnapshot().project;
		final WizardPage.Model model = WizardPrimaryButtonsTest.model(snapshot, WizardRules.STEP_STAGING);
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(WizardPrimaryButtonsTest.count(WizardPrimaryButtonsTest.visible(html), PRIMARY) == 1, "propose: one primary: " + WizardPrimaryButtonsTest.count(WizardPrimaryButtonsTest.visible(html), PRIMARY));
		check(html.contains(PRIMARY + " mdmf-btn-big\" data-mdmf-action=\"STAGING_PROPOSAL_RUN\" data-mdmf-proposal-checkboxes=\"1\""), "it is Propose the staging");
		check(!html.contains("STAGING_LOAD_RUN") && !html.contains(WizardPrimaryButtonsTest.en("wizard.step4.build.run.title")), "no build block yet, not even greyed");
		final String aboveAdvanced = html.substring(0, html.indexOf("id=\"mdmf-step4-passes\""));
		check(!aboveAdvanced.contains(WizardPrimaryButtonsTest.en("wizard.gate.needAcceptedEntities")) && !html.contains(WizardPrimaryButtonsTest.en("wizard.step4.run.done.title")), "no excuse, no empty review block");
		check(html.contains(WizardPrimaryButtonsTest.en("wizard.step4.primary.next.propose")), "the sentence says what comes next");
		final int options = html.indexOf(WizardPrimaryButtonsTest.en("wizard.step4.primary.options"));
		check(options > html.indexOf("STAGING_PROPOSAL_RUN") && html.indexOf("mdmfProposalIncludeQualityCheckbox") > options, "the three checkboxes are folded under Options, after the button");
		check(html.indexOf("mdmfProposalIncludeQualityCheckbox") == html.lastIndexOf("mdmfProposalIncludeQualityCheckbox"), "the checkbox ids exist once");
	}

	private static void step4Running()
	{
		final WizardPage.Model model = WizardPrimaryButtonsTest.model(WizardRulesTest.retSnapshot(), WizardRules.STEP_STAGING);
		model.proposalRunExists = true;
		model.proposalRunRunning = true;
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(html.contains("id=\"mdmfProposalLive\""), "the live list");
		check(WizardPrimaryButtonsTest.count(WizardPrimaryButtonsTest.visible(html), PRIMARY) == 0, "no primary button while it runs");
		check(!html.contains("id=\"mdmf-still-to-decide\"") && !html.contains("STAGING_LOAD_RUN"), "nothing to decide or build meanwhile");
	}

	private static void step4Review()
	{
		final WizardPage.Model model = WizardPrimaryButtonsTest.model(WizardRulesTest.retSnapshot(), WizardRules.STEP_STAGING);
		model.proposalRunExists = true;
		model.proposalDoneMatrixUrl = "/matrix?o=Personne";
		model.proposalDoneDesignerUrl = "/designer?o=Personne";
		final int points = Step4Counts.of(model.snapshot).waiting();
		check(points > 0, "the fixture has points to review");
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(WizardPrimaryButtonsTest.count(WizardPrimaryButtonsTest.visible(html), PRIMARY) == 1, "review: ONE primary (the item-level Accept buttons are outlined): " + WizardPrimaryButtonsTest.count(WizardPrimaryButtonsTest.visible(html), PRIMARY));
		check(html.contains("class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big mdmf-feed-matrix-link\" href=\"/matrix?o=Personne\" target=\"_top\">Review the proposal</a>"),
			"it is Review the proposal (UX lot C3: no count on the button), and it opens the matrix");
		check(html.contains("class=\"mdmf-btn mdmf-feed-designer-link\" href=\"/designer?o=Personne\""), "the Designer link is secondary");
		check(html.contains(WizardPrimaryButtonsTest.en("wizard.step4.primary.next.review")), "the sentence says Build and load comes after the review");
		final int block = html.indexOf("mdmf-primary-block");
		check(block >= 0 && block < html.indexOf("id=\"mdmf-still-to-decide\""), "the primary block is above the decisions left");
		// building before the review is over is an Advanced gesture, plain, not a second green button
		final int early = html.indexOf(WizardPrimaryButtonsTest.en("wizard.step4.primary.buildEarly"));
		check(early > 0 && html.lastIndexOf("data-mdmf-action=\"STAGING_LOAD_RUN\"", early) > html.lastIndexOf("<details", early), "build with what is accepted is inside the Advanced fold");
		check(!html.contains(PRIMARY + " mdmf-btn-big\" data-mdmf-action=\"STAGING_LOAD_RUN\""), "no green build button while points wait");
		// without a matrix link the button points at the per-object cards, whose fold opens
		model.proposalDoneMatrixUrl = "";
		final String noMatrix = WizardPageTest.section(WizardPage.render(model), 4);
		check(noMatrix.contains("href=\"#mdmf-step4-passes\">Review the proposal</a>") && noMatrix.contains("id=\"mdmf-step4-passes\" open=\"open\""), "no matrix: the cards open");
		// the singular
		final WizardSnapshot one = decidedSnapshot();
		one.stagingEntities.get(1).attributes.get(1).status = "PROPOSED";
		final WizardPage.Model singular = WizardPrimaryButtonsTest.model(one, WizardRules.STEP_STAGING);
		singular.proposalDoneMatrixUrl = "/matrix";
		check(Step4Counts.of(one).waiting() == 1 && WizardPageTest.section(WizardPage.render(singular), 4).contains(">Review the proposal</a>"), "one point");
	}

	private static void step4Build()
	{
		final WizardPage.Model model = WizardPrimaryButtonsTest.model(decidedSnapshot(), WizardRules.STEP_STAGING);
		model.proposalRunExists = true;
		model.proposalDoneMatrixUrl = "/matrix?o=Personne";
		model.proposalDoneDesignerUrl = "/designer?o=Personne";
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(WizardPrimaryButtonsTest.count(WizardPrimaryButtonsTest.visible(html), PRIMARY) == 1, "build: one primary: " + WizardPrimaryButtonsTest.count(WizardPrimaryButtonsTest.visible(html), PRIMARY));
		check(html.contains(PRIMARY + " mdmf-btn-big\" data-mdmf-action=\"STAGING_LOAD_RUN\">" + WizardPrimaryButtonsTest.en("wizard.step4.build.run.button")), "it is Build and load");
		check(html.contains("class=\"mdmf-btn mdmf-feed-matrix-link\"") && html.contains("class=\"mdmf-btn mdmf-feed-designer-link\""), "the review links are secondary now");
		check(!html.contains(">Review the proposal</a>") && !html.contains(" points</a>"), "no review button any more");
		check(!html.contains("name=\"mdmfStagingScopeAll\""), "no recompute option before a first build");
		check(html.contains(WizardPrimaryButtonsTest.en("wizard.step4.build.run.lead")), "the sentence says what the build does");
		// a build that already ran: the scope option is there, under Advanced
		model.stagingLoadRunExists = true;
		check(WizardPageTest.section(WizardPage.render(model), 4).contains("name=\"mdmfStagingScopeAll\""), "recompute every row once a build ran");
		// not an administrator: the button is disabled with ONE reason
		model.administrator = false;
		model.designer = false;
		final String reader = WizardPageTest.section(WizardPage.render(model), 4);
		check(reader.contains("data-mdmf-action=\"STAGING_LOAD_RUN\" disabled=\"disabled\"") && reader.contains(WizardPrimaryButtonsTest.en("wizard.gate.adminOnly")), "a reader sees why");
	}

	private static void step4Continue()
	{
		final WizardSnapshot snapshot = decidedSnapshot();
		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_DATASET", "DONE", ""));
		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_LOAD", "DONE", ""));
		final WizardPage.Model model = WizardPrimaryButtonsTest.model(snapshot, WizardRules.STEP_STAGING);
		model.proposalRunExists = true;
		model.stagingLoadRunExists = true;
		model.proposalDoneMatrixUrl = "/matrix";
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(WizardPrimaryButtonsTest.count(WizardPrimaryButtonsTest.visible(html), PRIMARY) == 1, "continue: one primary: " + WizardPrimaryButtonsTest.count(WizardPrimaryButtonsTest.visible(html), PRIMARY));
		check(html.contains(PRIMARY + " mdmf-btn-big\" data-mdmf-goto-step=\"5\">" + WizardPrimaryButtonsTest.en("wizard.step4.build.continue")), "it is Continue to the master");
		check(html.contains("class=\"mdmf-btn\" data-mdmf-action=\"STAGING_LOAD_RUN\">" + WizardPrimaryButtonsTest.en("wizard.step4.build.run.button.again")), "Build again is secondary");
		check(html.contains(WizardPrimaryButtonsTest.en("wizard.step4.primary.next.continue")), "the sentence");
		// with a point waiting after the load, review is the primary and the way on stays one plain click away
		snapshot.stagingEntities.get(1).attributes.get(1).status = "PROPOSED";
		final String again = WizardPageTest.section(WizardPage.render(model), 4);
		check(WizardPrimaryButtonsTest.count(WizardPrimaryButtonsTest.visible(again), PRIMARY) == 1 && again.contains(">Review the proposal</a>"), "a new point: review is primary again");
		check(again.contains("class=\"mdmf-btn\" data-mdmf-goto-step=\"5\">"), "and Continue to the master stays, plain");
	}

	private static void step4LoadingShowsTheLivePanelOnly()
	{
		final WizardPage.Model model = WizardPrimaryButtonsTest.model(decidedSnapshot(), WizardRules.STEP_STAGING);
		model.stagingLoadRunRunning = true;
		model.stagingLoadRunProgress = RunProgress.newRun(java.util.Arrays.asList("MODEL", "LOAD"));
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(html.contains("id=\"mdmfStagingLive\""), "the live panel");
		check(WizardPrimaryButtonsTest.count(WizardPrimaryButtonsTest.visible(html), PRIMARY) == 0 && !html.contains("data-mdmf-action=\"STAGING_LOAD_RUN\"") && !html.contains("data-mdmf-action=\"STAGING_PROPOSAL_RUN\""),
			"no button of the step while it runs, the stop button of the banner is the way out");
	}

	private static void applyWaitsForTheStaging()
	{
		final String binding = "{\"value\":\"phone\"}";
		final WizardSnapshot before = WizardRulesTest.retSnapshot();
		final ReviewLine line = new ReviewLine(ReviewLine.SCRIPT_DERIVATION, "107", "Personne.phoneE164", "phoneE164 = toE164(phone)", "LLM", Integer.valueOf(90), "", "ACCEPTED", false, false);
		final WizardSnapshot.ProposalLineInfo info = new WizardSnapshot.ProposalLineInfo(line);
		info.binding = binding;
		before.stagingEntities.get(0).proposalLines.add(info);
		final String html = WizardPageTest.section(WizardPage.render(WizardPrimaryButtonsTest.model(before, WizardRules.STEP_STAGING)), 4);
		final String group = WizardPrimaryButtonsTest.visible(html).substring(html.indexOf("data-mdmf-still-group=\"toApply\""));
		check(!group.contains("data-mdmf-action=\"APPLY\""), "no Apply button before the staging exists");
		check(group.contains(WizardPrimaryButtonsTest.en("wizard.step4.still.toApply.later")) && group.contains(WizardPrimaryButtonsTest.en("wizard.step4.still.toApply.leadBeforeBuild")),
			"it says they will be applied after the first build");
		before.projectSteps.add(new WizardSnapshot.PipelineStepInfo("STAGING_DATASET", "DONE", ""));
		final String after = WizardPageTest.section(WizardPage.render(WizardPrimaryButtonsTest.model(before, WizardRules.STEP_STAGING)), 4);
		check(WizardPrimaryButtonsTest.visible(after).contains("class=\"mdmf-btn mdmf-btn-sm mdmf-btn-accept\" data-mdmf-action=\"APPLY\" data-mdmf-nature=\"SCRIPT_DERIVATION\" data-mdmf-id=\"107\">"),
			"the Apply button appears once the staging exists, outlined: not a second green button");
		check(!WizardPrimaryButtonsTest.visible(after).contains(WizardPrimaryButtonsTest.en("wizard.step4.still.toApply.later")), "and the waiting sentence is gone");
	}

	private static void resetIsTheLastThingOfTheStepAndSaysItIsDestructive()
	{
		for (final int step : new int[] { WizardRules.STEP_LANDING, WizardRules.STEP_STAGING, WizardRules.STEP_TRANSFERS, WizardRules.STEP_MATCHING })
		{
			final WizardSnapshot snapshot = decidedSnapshot();
			snapshot.sources.add(new WizardSnapshot.SourceInfo());
			final String html = WizardPageTest.section(WizardPage.render(WizardPrimaryButtonsTest.model(snapshot, step)), step);
			final int reset = html.indexOf("data-mdmf-action=\"STEP_RESET_PLAN\"");
			check(reset > 0, "step " + step + ": the reset button is there");
			check(html.indexOf("data-mdmf-action=", reset + 10) < 0 || html.indexOf("data-mdmf-action=", reset + 10) > html.length(), "step " + step + ": nothing actionable follows it");
			check(html.substring(0, reset).contains("<h1>"), "step " + step + ": the page head comes first");
			check(html.lastIndexOf("<button", reset) > html.indexOf("<h1>") + 400 || step == WizardRules.STEP_LANDING, "step " + step + ": it is not right under the head");
			check(html.contains("class=\"mdmf-btn mdmf-btn-sm mdmf-btn-danger\" data-mdmf-action=\"STEP_RESET_PLAN\""), "step " + step + ": secondary, red");
			check(html.contains(WizardPrimaryButtonsTest.en("wizard.stepReset.warning")), "step " + step + ": with its one-line warning");
		}
	}

	private static void theRailSaysWhyAStepIsClosed()
	{
		final String page = WizardPage.render(WizardPrimaryButtonsTest.model(new WizardSnapshot(), WizardRules.STEP_PROJECT));
		final String reason = WizardPrimaryButtonsTest.en("wizard.blocked.needProject");
		check(page.contains("<li data-step=\"2\" class=\"blocked\" title=\"" + reason + "\" data-mdmf-blocked-note=\"" + reason + "\" tabindex=\"0\">"), "a closed step carries its reason for hover and click");
		check(page.contains("<li data-step=\"1\" class=\"current\">"), "an open step carries none");
		check(page.contains("<p class=\"mdmf-rail-note\" id=\"mdmfRailNote\" role=\"status\" hidden=\"hidden\"></p>"), "the one-line note sits under the rail");
		check(page.indexOf("id=\"mdmfRailNote\"") > page.indexOf("class=\"mdmf-wiz-steps\""), "under it");
	}

	private static void noGreyedButtonWithExcusesInStep4()
	{
		// the case the report measured: a proposal exists, nothing accepted yet - Build and load used to show greyed with two excuse lines
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		for (final WizardSnapshot.StagingEntityInfo entity : snapshot.stagingEntities)
		{
			entity.status = "PROPOSED";
		}
		final WizardPage.Model model = WizardPrimaryButtonsTest.model(snapshot, WizardRules.STEP_STAGING);
		model.proposalRunExists = true;
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(!html.contains("data-mdmf-action=\"STAGING_LOAD_RUN\" disabled"), "no greyed Build and load");
		final String aboveAdvanced = html.substring(0, html.indexOf("id=\"mdmf-step4-passes\""));
		check(!aboveAdvanced.contains(WizardPrimaryButtonsTest.en("wizard.gate.needAcceptedEntities")) && !aboveAdvanced.contains(WizardPrimaryButtonsTest.en("wizard.blocked.needStagingLoad")), "no excuse lines");
		final java.io.File css = new java.io.File(System.getProperty("mdmf.sourceDir", "src/main/java"), "../webapp/www/common/stylesheets/factory/wizard.css");
		if (css.exists())
		{
			try
			{
				final String text = new String(java.nio.file.Files.readAllBytes(css.toPath()), "UTF-8");
				check(text.contains(".mdmf-wiz-steps li.blocked button{pointer-events:none}") && text.contains(".mdmf-btn-accept{"), "the stylesheet carries the rail and accept rules");
			}
			catch (final java.io.IOException e)
			{
				throw new AssertionError(e);
			}
		}
	}
}
