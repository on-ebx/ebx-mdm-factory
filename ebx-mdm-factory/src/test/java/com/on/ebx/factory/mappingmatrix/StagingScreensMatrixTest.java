package com.on.ebx.factory.mappingmatrix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Staging-screens lot (2026-10-17, docs/UX-STAGING-TEST-FIXES-C.md), the Mapping matrix side: ONE count for what the "Accept the N proposals" button takes (the
 * header, the footer button and step 4 of the wizard quote the same N), ONE count of what is still to decide per business object (tab, toolbar chip and overview card),
 * the footer button that builds the staging, the page position that travels with a submit, the French header sentence. JUnit-free: run the main, a failure throws.
 */
public final class StagingScreensMatrixTest
{
	public static void main(final String[] pArgs)
	{
		StagingScreensMatrixTest.acceptAllIsTheButtonsOwnNumberEverywhere();
		StagingScreensMatrixTest.pendingPerObjectIsOneNumber();
		StagingScreensMatrixTest.theHeaderSentenceFormatsInFrench();
		StagingScreensMatrixTest.theFooterBuildsTheStagingAndTheScrollTravels();
		System.out.println("StagingScreensMatrixTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static String render(final MatrixModel.Project pProject, final String pEntity, final Locale pLocale)
	{
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = pEntity;
		state.locale = pLocale;
		return MatrixHtml.render(pProject, state);
	}

	private static int firstNumber(final String pText, final String pRegex)
	{
		final Matcher matcher = Pattern.compile(pRegex).matcher(pText);
		check(matcher.find(), "pattern not found: " + pRegex);
		return Integer.parseInt(matcher.group(1));
	}

	/** The N of the footer button is the bulk plan's size = {@code Tally#acceptAll}; the headline of an "only the button is left" project quotes the same N. */
	private static void acceptAllIsTheButtonsOwnNumberEverywhere()
	{
		final MatrixModel.Project[] projects = { MatrixQueueFixtureTest.persVol9Like(true, false), MatrixQueueFixtureTest.persVol9Like(false, false),
			MatrixQueueFixtureTest.persVol9Like(true, true) };
		for (int i = 0; i < projects.length; i++)
		{
			final MatrixCounter.Tally tally = MatrixCounter.of(projects[i], 60);
			final int plan = MatrixCompute.planProjectBulk(projects[i], 60).toDecide.size();
			check(tally.acceptAll == plan, "project " + i + ": the tally's acceptAll (" + tally.acceptAll + ") is the plan's size (" + plan + ")");
			check(tally.acceptAll >= tally.withButton, "project " + i + ": the button takes at least every item the list says goes with it");
			check((tally.acceptAll == 0) == (tally.withButton == 0), "project " + i + ": the button is empty exactly when no item goes with it");
			final String html = StagingScreensMatrixTest.render(projects[i], projects[i].entities.get(0).id, Locale.ENGLISH);
			if (tally.acceptAll > 0)
			{
				check(StagingScreensMatrixTest.firstNumber(html, "Accept the (\\d+) proposals at 60 % or above") == tally.acceptAll, "project " + i + ": the footer button says " + tally.acceptAll);
			}
		}
		// nothing needs a person, the button takes the rest: the headline and the footer count quote the button's N
		final MatrixModel.Attr nom = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false, "PROPOSED", Integer.valueOf(90));
		final MatrixModel.Attr prenom = new MatrixModel.Attr("a2", "prenom", "STRING", true, false, false, "PROPOSED", Integer.valueOf(90));
		final MatrixModel.Project onlyButton = new MatrixModel.Project("P", null, Arrays.asList(new MatrixModel.Entity("e1", "P", Arrays.asList(nom, prenom),
			new ArrayList<MatrixModel.Feed>(), "ACCEPTED", Integer.valueOf(100))));
		final MatrixCounter.Tally tally = MatrixCounter.of(onlyButton, 60);
		final String html = StagingScreensMatrixTest.render(onlyButton, "e1", Locale.ENGLISH);
		check(tally.needYou == 0 && tally.acceptAll == 2 && html.contains("Nothing needs your attention; 2 items go with the button.")
			&& StagingScreensMatrixTest.firstNumber(html, "Accept the (\\d+) proposals") == 2, "headline, footer count and button all say 2");
	}

	/** One business object: its tab, the toolbar's "to validate" chip and its card of the overview read the same number (it was 49 against 62). */
	private static void pendingPerObjectIsOneNumber()
	{
		final MatrixModel.Project[] projects = { MatrixQueueFixtureTest.persVol9Like(true, false), MatrixQueueFixtureTest.persVol9Like(false, false) };
		for (int i = 0; i < projects.length; i++)
		{
			final MatrixModel.Project project = projects[i];
			final java.util.List<MatrixCompute.StillItem> items = MatrixCompute.stillToDecide(project, 60);
			for (final MatrixModel.Entity entity : project.entities)
			{
				final int expected = MatrixCompute.countOfEntity(items, entity.id);
				final String html = StagingScreensMatrixTest.render(project, entity.id, Locale.ENGLISH);
				if (expected > 0)
				{
					check(StagingScreensMatrixTest.firstNumber(html, "<span class=\"mdmmChip warn\">(\\d+) to validate</span>") == expected,
						"project " + i + ", " + entity.name + ": the toolbar chip says " + expected);
					// the card of the overview: "<span class="mdmmEntName">NAME</span><span class="mdmmEntSub">N to validate"
					final Matcher card = Pattern.compile("mdmmEntName\">" + Pattern.quote(entity.name) + "</span><span class=\"mdmmEntSub\">(\\d+) to validate").matcher(html);
					check(card.find() && Integer.parseInt(card.group(1)) == expected, "project " + i + ", " + entity.name + ": the overview card says " + expected);
					final Matcher tab = Pattern.compile("data-key=\"entity\\|" + Pattern.quote(entity.id) + "\"[^>]*>" + Pattern.quote(entity.name) + " <span class=\"mdmmTabCount\"[^>]*>(\\d+)</span>").matcher(html);
					check(tab.find() && Integer.parseInt(tab.group(1)) == expected, "project " + i + ", " + entity.name + ": the tab says " + expected);
				}
			}
		}
	}

	/** The live report: "Latelier propose {0}, alimentes par {1}..." - the apostrophe is kept and all five placeholders are filled, in the page itself. */
	private static void theHeaderSentenceFormatsInFrench()
	{
		final String html = StagingScreensMatrixTest.render(MatrixQueueFixtureTest.persVol9Like(true, false), "e1", Locale.FRENCH);
		final int at = html.indexOf("L&#39;atelier propose ");
		check(at >= 0, "the apostrophe is kept");
		final String sentence = html.substring(at, html.indexOf("</div>", at));
		check(sentence.indexOf('{') < 0 && sentence.contains(", alimentés par ") && sentence.endsWith("."), "no placeholder is left: " + sentence);
	}

	/** The footer button builds the staging; the page position posted with a submit comes back; the page leaves for the wizard only after a build was started. */
	private static void theFooterBuildsTheStagingAndTheScrollTravels()
	{
		final MatrixModel.Attr done = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false, "ACCEPTED", Integer.valueOf(90));
		final MatrixModel.Project ready = new MatrixModel.Project("P", null, Arrays.asList(new MatrixModel.Entity("e1", "P", Arrays.asList(done), new ArrayList<MatrixModel.Feed>(),
			"ACCEPTED", Integer.valueOf(100))));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		state.wizardUrl = "/ebx-ui/ui/custom/MdmFactory/data/BF/F/root/Project?service=ProjectWizard";
		String html = MatrixHtml.render(ready, state);
		check(html.contains("data-scrolltop=\"-1\"") && html.contains("data-goto=\"0\""), "a first display restores nothing and does not leave");
		check(html.contains("id=\"mdmmScroll\" name=\"mdmmScroll\"") , "the form carries the page position");
		state.scrollTop = 840;
		state.goToWizard = true;
		html = MatrixHtml.render(ready, state);
		check(html.contains("data-scrolltop=\"840\"") && html.contains("data-goto=\"1\""), "the position comes back; the page leaves after a started build");
		final String script = MatrixHtml.script(Locale.ENGLISH);
		check(script.contains("byId('mdmmScroll').value=String(Math.round(scroller().scrollTop))"), "every submit records where the page was");
		check(script.contains("if(root.getAttribute('data-goto')==='1'){var bl=byId('mdmmBackLink');if(bl){bl.click();}}"), "the back link (perspective-aware) takes the page to step 4");
		check(script.contains("function scrollToEl(el){if(!el||!el.scrollIntoView){return;}if(keepTop()>=0){"), "the selection is scrolled into view only when it is off screen once the position is back");
	}
}
