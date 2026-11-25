package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.review.ReviewFilter;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.review.ReviewPage;

/**
 * JUnit-free, like {@code RecordLineageUserServicePageJsTest}: exercises the CONTRACT between {@link
 * ReviewPage#render} (the plain HTML of the two MATCH-only inline panels, "Propose another"/"No matching for
 * this entity") and {@link ProposalReviewUserService#pageJs} (the delegate script that reveals a panel, copies
 * its textarea into the hidden {@code mdmfComment} field, and refuses to submit while a required confirmation
 * checkbox is unchecked) - reject/repropose lot, 2026-09-22. Same package as {@code ProposalReviewUserService}
 * on purpose, to reach its package-private {@code pageJs} directly.
 */
public final class ProposalReviewUserServiceMatchJsTest
{
	public static void main(final String[] pArgs)
	{
		ProposalReviewUserServiceMatchJsTest.toggleButtonsAreHandledSeparatelyFromActionButtons();
		ProposalReviewUserServiceMatchJsTest.commentFromCopiesTheTextareaIntoTheNamedHiddenField();
		ProposalReviewUserServiceMatchJsTest.requiresCheckboxBlocksTheSubmitWhenUnchecked();
		ProposalReviewUserServiceMatchJsTest.reproposeIsQueuedSoItArmsNoStrip();
		ProposalReviewUserServiceMatchJsTest.renderedPanelIdsAreWhatTheToggleButtonsReference();
		ProposalReviewUserServiceMatchJsTest.hashChangeHandlerOpensTheMatchingLineTheHashNames();
		System.out.println("ProposalReviewUserServiceMatchJsTest OK");
	}

	/**
	 * tree-diagram-labels fix lot (2026-09-22), item B/C ({@code docs/MATCHING.md} &sect;16.10): the counter's
	 * own anchors ({@code #mdmf-line-<rowId>}, {@code ReviewPage.renderMatchCounter}) land on the collapsed
	 * row's own id (native browser scroll, no JS needed for that alone) - this handler additionally opens that
	 * row's {@code <details>} (a plain anchor never does), on both page load (a bookmarked/shared link) and
	 * {@code hashchange} (clicking a counter anchor while already on the page). Looks up the details element via
	 * {@code nextElementSibling} - never a raw attribute-selector built from the rowId string, which would need
	 * escaping a value that might contain a quote (same defensive reasoning as ReviewPage.renderRow's own id
	 * attribute, {@code getElementById} needs no escaping at all).
	 */
	private static void hashChangeHandlerOpensTheMatchingLineTheHashNames()
	{
		final String script = ProposalReviewUserService.pageJs(Locale.ENGLISH);
		ProposalReviewUserServiceMatchJsTest.check("a hashchange listener is registered", script.contains("addEventListener('hashchange',mdmfOpenFromHash)"));
		ProposalReviewUserServiceMatchJsTest.check("it also runs once immediately (a bookmarked/shared link already carries the hash)",
			script.contains(");mdmfOpenFromHash();"));
		ProposalReviewUserServiceMatchJsTest.check("it only reacts to the counter's own hash shape", script.contains("indexOf('#mdmf-line-')!==0"));
		ProposalReviewUserServiceMatchJsTest.check("finds the row by id (no attribute-selector escaping needed)",
			script.contains("document.getElementById(h.substring(1))"));
		ProposalReviewUserServiceMatchJsTest.check("opens the expanded row's own <details> via nextElementSibling, never a rebuilt selector",
			script.contains("row.nextElementSibling") && script.contains("d.open=true"));
	}

	/** A toggle button must never be caught by the [data-mdmf-action] handler (it carries no data-mdmf-action at all - see ReviewPage.renderRow) - it has its own, separate listener that only shows/hides a panel. */
	private static void toggleButtonsAreHandledSeparatelyFromActionButtons()
	{
		final String script = ProposalReviewUserService.pageJs(Locale.ENGLISH);
		ProposalReviewUserServiceMatchJsTest.check("a dedicated [data-mdmf-toggle] handler exists", script.contains("querySelectorAll('[data-mdmf-toggle]')"));
		ProposalReviewUserServiceMatchJsTest.check("it toggles the referenced panel by id",
			script.contains("document.getElementById(t.getAttribute('data-mdmf-toggle'))"));
	}

	private static void commentFromCopiesTheTextareaIntoTheNamedHiddenField()
	{
		final String script = ProposalReviewUserService.pageJs(Locale.ENGLISH);
		ProposalReviewUserServiceMatchJsTest.check("reads data-mdmf-comment-from off the clicked button", script.contains("b.getAttribute('data-mdmf-comment-from')"));
		ProposalReviewUserServiceMatchJsTest.check("copies the textarea's value into the real, named mdmfComment hidden field",
			script.contains("set('mdmfComment',ta?ta.value:'')"));
	}

	/** "No matching for this entity" must never submit while its confirmation checkbox is unchecked. */
	private static void requiresCheckboxBlocksTheSubmitWhenUnchecked()
	{
		final String script = ProposalReviewUserService.pageJs(Locale.ENGLISH);
		ProposalReviewUserServiceMatchJsTest.check("reads data-mdmf-requires-checkbox off the clicked button",
			script.contains("b.getAttribute('data-mdmf-requires-checkbox')"));
		ProposalReviewUserServiceMatchJsTest.check("returns without submitting when the checkbox is missing or unchecked",
			script.contains("if(!confirmBox||!confirmBox.checked){if(confirmBox)confirmBox.focus();return;}"));
	}

	private static void reproposeIsQueuedSoItArmsNoStrip()
	{
		final String script = ProposalReviewUserService.pageJs(Locale.ENGLISH);
		// audit F-34 (wave 10): "Propose another" queues the language-model pass (MatchingSuggestLlmRun.startRepropose) and answers at once - no progress strip for it;
		// the lock of every action (audit F-49) still covers the quick postback
		ProposalReviewUserServiceMatchJsTest.check("REPROPOSE no longer arms the progress strip", !script.contains("action==='REPROPOSE'"));
		ProposalReviewUserServiceMatchJsTest.check("its quick postback locks the buttons like every action", script.contains("if(!locked)lockAll("));
	}

	/**
	 * Cross-check against the real rendered page: the panel ids {@link ReviewPage} generates for a MATCH line
	 * are exactly what a toggle button on that SAME line points its {@code data-mdmf-toggle} at - if either
	 * side ever renamed its id scheme alone, this would catch it (the two are computed independently: one in
	 * {@code ReviewPage.renderRow}'s button, the other in {@code ReviewPage.renderMatchInlinePanels}).
	 */
	private static void renderedPanelIdsAreWhatTheToggleButtonsReference()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(new ReviewLine(ReviewLine.MATCH, "42", "Company", "Requirements", "LLM", Integer.valueOf(80), "why", ReviewLine.PROPOSED, false, false));
		final ReviewPage.Input input = new ReviewPage.Input(Locale.ENGLISH,
			java.util.Collections.singletonList(new ReviewPage.ProjectOption("COMPANIES360", "Companies 360")), "COMPANIES360", "Companies 360", true, lines,
			com.on.ebx.factory.review.ReviewData.filter(lines, ReviewFilter.defaultFilter()), ReviewFilter.defaultFilter(), 80, null, "ok", null, null, null, null, 10);
		final String html = ReviewPage.render(input);
		ProposalReviewUserServiceMatchJsTest.check("Propose another toggles the SAME id the repropose panel renders",
			html.contains("data-mdmf-toggle=\"mdmf-match-repropose-42\"") && html.contains("id=\"mdmf-match-repropose-42\""));
		ProposalReviewUserServiceMatchJsTest.check("No matching for this entity toggles the SAME id the no-matching panel renders",
			html.contains("data-mdmf-toggle=\"mdmf-match-noMatch-42\"") && html.contains("id=\"mdmf-match-noMatch-42\""));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
