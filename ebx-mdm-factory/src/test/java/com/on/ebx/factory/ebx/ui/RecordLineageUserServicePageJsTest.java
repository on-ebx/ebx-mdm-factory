package com.on.ebx.factory.ebx.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.lineage.RecordLineagePage;
import com.on.ebx.factory.lineage.RecordLineagePage.EntityOption;
import com.on.ebx.factory.lineage.RecordLineagePage.ProjectOption;

/**
 * JUnit-free, like {@code RecordLineagePageTest}: exercises the CONTRACT between {@link RecordLineagePage#render}
 * (the plain HTML) and {@link AbstractRecordLineageUserService#pageJs} (the delegate script that fills the
 * hidden fields and clicks the one real submit button, CLAUDE.md &sect;9) - without any EBX type, since
 * {@code pageJs()} is a static method returning a plain {@code String} and {@code render} is pure. {@code
 * pageJs} moved from {@code RecordLineageUserService} into the shared {@code AbstractRecordLineageUserService}
 * (coordinator review, 2026-09-22, "Record lineage" record-level action lot, merged after this test was
 * written) - package-private, so this test (same package) still reaches it directly.
 * <p>
 * <b>What this guards against (fix/lineage-load-button, 2026-09-22)</b>: the Load button
 * (first step, no project resolved yet) posted back nothing at all, because {@code pageJs}'s
 * {@code SELECT_PROJECT} branch wrote the picked value into an id, {@code mdmfProjectSelect}, that no
 * rendered form field ever carried as a {@code name} - {@link RecordLineagePage} only ever names
 * {@code mdmfAction}/{@code mdmfProjectCode}/{@code mdmfEntity}/{@code mdmfKey} ({@code renderHiddenFields}),
 * and the {@code <select id="mdmfProjectSelect">} itself has no {@code name} attribute so it is never part
 * of the form submission either way. The server-side handler then always read an empty string, discarding
 * every project pick - a postback genuinely happened, but with no visible effect, which is indistinguishable
 * from "the button does nothing". Every check below is a property that would have failed under that bug and
 * holds once the script instead overwrites the EXISTING {@code mdmfProjectCode} hidden field (the same
 * "read the live control, overwrite the hidden mirror" technique {@code ProposalReviewUserService.pageJs}
 * already uses for its own project dropdown).
 */
public final class RecordLineageUserServicePageJsTest
{
	public static void main(final String[] pArgs)
	{
		RecordLineageUserServicePageJsTest.rootSelectorMatchesTheRenderedWrapperForEveryStep();
		RecordLineageUserServicePageJsTest.selectProjectWritesIntoAHiddenFieldThePageActuallyNames();
		RecordLineageUserServicePageJsTest.selectProjectNoLongerTargetsTheUnnamedSelectId();
		RecordLineageUserServicePageJsTest.lookupBranchWritesIntoHiddenFieldsThePageActuallyNames();
		RecordLineageUserServicePageJsTest.changeProjectAndActionAlwaysTargetNamedFields();
		RecordLineageUserServicePageJsTest.suspectKeyHiddenFieldIsRenderedAndReadGenerically();
		System.out.println("RecordLineageUserServicePageJsTest OK");
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	private static RecordLineagePage.Input noProjectSelected()
	{
		final List<ProjectOption> projects = Arrays.asList(new ProjectOption("COMPANIES360", "Companies 360"));
		return new RecordLineagePage.Input(Locale.ENGLISH, projects, null, null, Collections.<EntityOption>emptyList(), null, "", null, "ok", null);
	}

	private static RecordLineagePage.Input projectSelectedWithEntities()
	{
		final List<ProjectOption> projects = Arrays.asList(new ProjectOption("COMPANIES360", "Companies 360"));
		final List<EntityOption> entities = Arrays.asList(new EntityOption("Company", "Company"));
		return new RecordLineagePage.Input(Locale.ENGLISH, projects, "COMPANIES360", "Companies 360", entities, "Company", "183", null, "ok", null);
	}

	// ------------------------------------------------------------------------------------------------------ tests

	/**
	 * Hypothesis 1 (ruled out, kept as a regression check): {@code pageJs}'s delegate is scoped to
	 * {@code document.querySelector('.mdmf-lineage')} - {@link RecordLineagePage#render} must wrap EVERY step
	 * (no project, project with entities) in an element carrying that class, or none of the buttons on that
	 * step would ever be found by {@code root.querySelectorAll('[data-mdmf-action]')}.
	 */
	private static void rootSelectorMatchesTheRenderedWrapperForEveryStep()
	{
		final String script = AbstractRecordLineageUserService.pageJs();
		RecordLineageUserServicePageJsTest.check("the delegate script is scoped to .mdmf-lineage", script.contains("querySelector('.mdmf-lineage')"));

		final String noProjectHtml = RecordLineagePage.render(RecordLineageUserServicePageJsTest.noProjectSelected());
		RecordLineageUserServicePageJsTest.check("the no-project step is wrapped in .mdmf-lineage", noProjectHtml.startsWith("<div class=\"mdmf mdmf-lineage\">"));

		final String withEntitiesHtml = RecordLineagePage.render(RecordLineageUserServicePageJsTest.projectSelectedWithEntities());
		RecordLineageUserServicePageJsTest.check("the project-selected step is wrapped in .mdmf-lineage",
			withEntitiesHtml.startsWith("<div class=\"mdmf mdmf-lineage\">"));
	}

	/**
	 * The real bug: the first-step Load button ({@code data-mdmf-action="SELECT_PROJECT"}) reads the live
	 * {@code <select id="mdmfProjectSelect">} and must write that value into a hidden field the page actually
	 * names, so it is part of the form submission the click triggers.
	 */
	private static void selectProjectWritesIntoAHiddenFieldThePageActuallyNames()
	{
		final String html = RecordLineagePage.render(RecordLineageUserServicePageJsTest.noProjectSelected());
		RecordLineageUserServicePageJsTest.check("the Load button carries the SELECT_PROJECT action", html.contains("data-mdmf-action=\"SELECT_PROJECT\""));
		RecordLineageUserServicePageJsTest.check("the live project select is rendered with the id the script reads", html.contains("id=\"mdmfProjectSelect\""));

		final String script = AbstractRecordLineageUserService.pageJs();
		RecordLineageUserServicePageJsTest.check("the SELECT_PROJECT branch reads the live select by id",
			script.contains("var sel=document.getElementById('mdmfProjectSelect')"));
		RecordLineageUserServicePageJsTest.check("the SELECT_PROJECT branch writes into mdmfProjectCode",
			script.contains("var sel=document.getElementById('mdmfProjectSelect');set('mdmfProjectCode',sel?sel.value:'')"));
		RecordLineageUserServicePageJsTest.check("mdmfProjectCode is a real, named hidden field on this very step (so the write actually posts)",
			html.contains("name=\"mdmfProjectCode\""));
	}

	/**
	 * Negative regression for the exact bug: the script must never again write into {@code mdmfProjectSelect}
	 * (only the unnamed {@code <select>} itself carries that id - writing there posts nothing), and the page
	 * must never render a hidden field under that name either, so a future edit re-introducing either half of
	 * the bug fails loudly here instead of only in a live click nobody is watching.
	 */
	private static void selectProjectNoLongerTargetsTheUnnamedSelectId()
	{
		final String script = AbstractRecordLineageUserService.pageJs();
		RecordLineageUserServicePageJsTest.check("the script never sets a field literally named mdmfProjectSelect",
			!script.contains("set('mdmfProjectSelect'"));

		final String html = RecordLineagePage.render(RecordLineageUserServicePageJsTest.noProjectSelected());
		RecordLineageUserServicePageJsTest.check("the page never names a form field mdmfProjectSelect (only the <select>'s id, never posted)",
			!html.contains("name=\"mdmfProjectSelect\""));
	}

	/** Same contract, the LOOKUP step: both fields the script fills in before submit are real, named hidden fields. */
	private static void lookupBranchWritesIntoHiddenFieldsThePageActuallyNames()
	{
		final String html = RecordLineagePage.render(RecordLineageUserServicePageJsTest.projectSelectedWithEntities());
		RecordLineageUserServicePageJsTest.check("the Load/lookup button carries the LOOKUP action", html.contains("data-mdmf-action=\"LOOKUP\""));
		RecordLineageUserServicePageJsTest.check("the entity select is rendered with the id the script reads", html.contains("id=\"mdmfEntitySelect\""));
		RecordLineageUserServicePageJsTest.check("the key input is rendered with the id the script reads", html.contains("id=\"mdmfKeyInput\""));
		RecordLineageUserServicePageJsTest.check("mdmfEntity is a real, named hidden field", html.contains("name=\"mdmfEntity\""));
		RecordLineageUserServicePageJsTest.check("mdmfKey is a real, named hidden field", html.contains("name=\"mdmfKey\""));

		final String script = AbstractRecordLineageUserService.pageJs();
		RecordLineageUserServicePageJsTest.check("the LOOKUP branch writes the entity select's value into mdmfEntity",
			script.contains("set('mdmfEntity',ent?ent.value:'')"));
		RecordLineageUserServicePageJsTest.check("the LOOKUP branch writes the key input's value into mdmfKey", script.contains("set('mdmfKey',key?key.value:'')"));
	}

	/** {@code mdmfAction} (every click) and the CHANGE_PROJECT button both target fields the page actually names, on every step. */
	private static void changeProjectAndActionAlwaysTargetNamedFields()
	{
		final String script = AbstractRecordLineageUserService.pageJs();
		RecordLineageUserServicePageJsTest.check("every click writes the action into mdmfAction", script.contains("set('mdmfAction',action)"));

		final String withEntitiesHtml = RecordLineagePage.render(RecordLineageUserServicePageJsTest.projectSelectedWithEntities());
		RecordLineageUserServicePageJsTest.check("mdmfAction is a real, named hidden field", withEntitiesHtml.contains("name=\"mdmfAction\""));
		RecordLineageUserServicePageJsTest.check("the Change project button is rendered once a project is selected",
			withEntitiesHtml.contains("data-mdmf-action=\"CHANGE_PROJECT\""));
	}

	/**
	 * "decide on a suspect" lot (2026-09-22): {@code RecordLineagePage#renderHiddenFields} names {@code
	 * mdmfSuspectKey}, and {@code pageJs}'s rule is GENERIC ("any clicked button carrying {@code
	 * data-mdmf-suspect-key} writes it") rather than one branch per action - so a decision button never needs
	 * a matching change to this script. Checked directly against the rendered HTML/script text, not just
	 * described, so a future edit dropping either half fails here.
	 */
	private static void suspectKeyHiddenFieldIsRenderedAndReadGenerically()
	{
		final String html = RecordLineagePage.render(RecordLineageUserServicePageJsTest.projectSelectedWithEntities());
		RecordLineageUserServicePageJsTest.check("mdmfSuspectKey is a real, named hidden field", html.contains("name=\"mdmfSuspectKey\""));

		final String script = AbstractRecordLineageUserService.pageJs();
		RecordLineageUserServicePageJsTest.check("the script reads data-mdmf-suspect-key generically, not action by action",
			script.contains("if(b.hasAttribute('data-mdmf-suspect-key')){set('mdmfSuspectKey',b.getAttribute('data-mdmf-suspect-key'));}"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
