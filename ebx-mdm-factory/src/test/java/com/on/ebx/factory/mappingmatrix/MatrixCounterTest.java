package com.on.ebx.factory.mappingmatrix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.review.ReviewLine;

/**
 * UX lot U6 (docs/UX-MATRIX-QUEUE.md): the ONE number of the Mapping matrix, the optional look-up suggestions grouped per field, the default of "Only what's
 * left to do", the scope lines and the secondary per-link action. JUnit-free, like its neighbours: run the main, a failure throws.
 */
public final class MatrixCounterTest
{
	public static void main(final String[] pArgs)
	{
		MatrixCounterTest.tallyCountsWhatNeedsAPersonAndSetsOptionalSuggestionsApart();
		MatrixCounterTest.flaggedFieldsAreOnlyThoseStillWaiting();
		MatrixCounterTest.headerAndFooterAreTheSameNumberOnEveryRender();
		MatrixCounterTest.nothingLeftReadsDoneEverywhere();
		MatrixCounterTest.optionalSuggestionsAreOneFoldedLinePerFieldWithTheirReadiness();
		MatrixCounterTest.optionalSuggestionsNeverKeepTheStagingClosed();
		MatrixCounterTest.onlyWhatIsLeftIsTheDefaultOnceSomethingIsDecided();
		MatrixCounterTest.scopeLinesAndTheSecondaryPerLinkAction();
		MatrixCounterTest.linksTakeThePerspectiveRouteWhenDrawn();
		System.out.println("MatrixCounterTest OK");
	}

	private static Guard service(final String pId, final String pAttrId, final String pOperation, final String pConnector, final Boolean pReady, final String pDetail,
		final String pPricing, final String pStatus)
	{
		return new Guard(pId, ReviewLine.EXTERNAL_SERVICE, "e1", pAttrId, pOperation, "", null, null, pStatus, "LLM", Integer.valueOf(70), "suggested", null, null, null, pConnector,
			pReady, pDetail, pPricing);
	}

	/** UxCrmSf-like: Adresse with four geocoders on street, one look-up each on postalCode and city; nothing else left. */
	private static MatrixModel.Project servicesOnly()
	{
		final MatrixModel.Attr key = new MatrixModel.Attr("a0", "sourceId", "STRING", true, false, true);
		final MatrixModel.Attr street = new MatrixModel.Attr("a1", "street", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(90),
			Arrays.asList(MatrixCounterTest.service("g1", "a1", "geocode", "Nominatim", Boolean.TRUE, "", "FREE", ReviewLine.PROPOSED),
				MatrixCounterTest.service("g2", "a1", "geocode", "Google", Boolean.TRUE, "", "PAID", ReviewLine.PROPOSED),
				MatrixCounterTest.service("g3", "a1", "geocode", "Here", Boolean.FALSE, "its required authentication secret is missing", "FREEMIUM", ReviewLine.PROPOSED),
				MatrixCounterTest.service("g4", "a1", "search", "CompaniesHouse", Boolean.FALSE, "", "", ReviewLine.PROPOSED),
				MatrixCounterTest.service("g9", "a1", "geocode", "Old", Boolean.TRUE, "", "FREE", ReviewLine.SET_ASIDE)));
		final MatrixModel.Attr postal = new MatrixModel.Attr("a2", "postalCode", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(90),
			Arrays.asList(MatrixCounterTest.service("g5", "a2", "geocode", "Nominatim", Boolean.TRUE, "", "FREE", ReviewLine.PROPOSED)));
		final MatrixModel.Entity adresse = new MatrixModel.Entity("e1", "Adresse", Arrays.asList(key, street, postal), new ArrayList<MatrixModel.Feed>(), "ACCEPTED", Integer.valueOf(100));
		return new MatrixModel.Project("P", null, new ArrayList<MatrixModel.Entity>(Arrays.asList(adresse)));
	}

	private static MatrixModel.Project flaggedProject(final String pAttrStatus, final String pCellStatus)
	{
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a1", new MatrixModel.Cell(pCellStatus, "m1", "COL", null, Integer.valueOf(90), "LLM", null, null, 0, null, null, "EMPTY_COLUMN", "empty in SAP (0 / 165)", null));
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "T", "ACCEPTED", null, "", false, null, cells, Integer.valueOf(90), "");
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "mobilePhone", "STRING", false, false, false, pAttrStatus, Integer.valueOf(90));
		return new MatrixModel.Project("P", new ArrayList<MatrixModel.Source>(Arrays.asList(new MatrixModel.Source("s1", "SAP", "#000"))),
			new ArrayList<MatrixModel.Entity>(Arrays.asList(new MatrixModel.Entity("e1", "P", Arrays.asList(attr), Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100)))));
	}

	private static String renderFrench(final MatrixModel.Project pProject)
	{
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		state.locale = Locale.FRENCH;
		return MatrixHtml.render(pProject, state);
	}

	private static String render(final MatrixModel.Project pProject, final String pEntity)
	{
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = pEntity;
		return MatrixHtml.render(pProject, state);
	}

	/** The pure rule: needs-you = items the button does not take and that are not optional; the optional ones are counted apart. */
	private static void tallyCountsWhatNeedsAPersonAndSetsOptionalSuggestionsApart()
	{
		final MatrixCounter.Tally leftovers = MatrixCounter.of(MatrixQueueFixtureTest.persVol9Like(true, false), 60);
		MatrixCounterTest.check("PersVol9-like: 2 need you, 3 go with the button, 2 optional look-ups on 2 fields",
			leftovers.needYou == 2 && leftovers.withButton == 3 && leftovers.optionalSuggestions == 2 && leftovers.optionalFields == 2 && leftovers.stillToDecide() == 5);
		MatrixCounterTest.check("the headline is 'need you'", leftovers.headline() == MatrixCounter.Headline.NEED_YOU && !leftovers.allDone());
		final MatrixCounter.Tally keysDone = MatrixCounter.of(MatrixQueueFixtureTest.persVol9Like(true, true), 60);
		MatrixCounterTest.check("keys accepted: nothing goes with the button any more, 2 still need you", keysDone.needYou == 2 && keysDone.withButton == 0);
		final MatrixCounter.Tally services = MatrixCounter.of(MatrixCounterTest.servicesOnly(), 60);
		MatrixCounterTest.check("5 proposed look-ups on 2 fields, the set-aside one is neither listed nor counted; nothing needs you",
			services.optionalSuggestions == 5 && services.optionalFields == 2 && services.needYou == 0 && services.withButton == 0);
		MatrixCounterTest.check("nothing but optional suggestions left = nothing left to decide", services.allDone() && services.headline() == MatrixCounter.Headline.NOTHING_LEFT);
		final MatrixModel.Attr nom = new MatrixModel.Attr("a1", "nom", "STRING", true, false, false, "PROPOSED", Integer.valueOf(90));
		final MatrixCounter.Tally onlyButton = MatrixCounter.of(new MatrixModel.Project("P", null,
			Arrays.asList(new MatrixModel.Entity("e1", "P", Arrays.asList(nom), new ArrayList<MatrixModel.Feed>(), "ACCEPTED", Integer.valueOf(100)))), 60);
		MatrixCounterTest.check("what only the button takes reads 'goes with the button'", onlyButton.needYou == 0 && onlyButton.withButton == 1 && onlyButton.headline() == MatrixCounter.Headline.ONLY_BUTTON);
	}

	/** The header's first box counts flagged fields still waiting - a decided flagged field no longer counts (it did, as a stale scope). */
	private static void flaggedFieldsAreOnlyThoseStillWaiting()
	{
		final MatrixCounter.Tally waiting = MatrixCounter.of(MatrixCounterTest.flaggedProject("PROPOSED", "PROPOSED"), 60);
		MatrixCounterTest.check("a flagged field still waiting counts once, whatever its number of links", waiting.flaggedFields == 1 && waiting.needYou >= 1);
		final MatrixCounter.Tally decided = MatrixCounter.of(MatrixCounterTest.flaggedProject("ACCEPTED", "ACCEPTED"), 60);
		MatrixCounterTest.check("once it is accepted it counts no more, though the cell still carries its flag", decided.flaggedFields == 0 && decided.needYou == 0 && decided.allDone());
		MatrixCounterTest.check("the old project-wide figure still read 1 for it (the stale header)", MatrixCompute.summarize(MatrixCounterTest.flaggedProject("ACCEPTED", "ACCEPTED")).attentionPoints == 1);
	}

	private static int firstNumber(final String pText, final Pattern pPattern)
	{
		final Matcher matcher = pPattern.matcher(pText);
		if (!matcher.find())
		{
			throw new AssertionError("pattern not found: " + pPattern);
		}
		return Integer.parseInt(matcher.group(1));
	}

	/** Measured on UxCrmSf: header 4 / queue 20, then 4 / 16, then 1 / 'nothing left'. Now both read one tally, on every fixture and every state. */
	private static void headerAndFooterAreTheSameNumberOnEveryRender()
	{
		final Pattern header = Pattern.compile("id=\"mdmmHeadline\">(\\d+) points? needs? your attention");
		final Pattern footer = Pattern.compile("class=\"mdmmFootCount\"[^>]*>(\\d+) items? needs? you</button>");
		final Pattern tab = Pattern.compile("<span class=\"mdmmTabCount\"[^>]*>(\\d+)</span>");
		final MatrixModel.Project[] projects = { MatrixQueueFixtureTest.persVol9Like(true, false), MatrixQueueFixtureTest.persVol9Like(true, true),
			MatrixQueueFixtureTest.persVol9Like(false, false), MatrixCounterTest.flaggedProject("PROPOSED", "PROPOSED") };
		for (int i = 0; i < projects.length; i++)
		{
			final String html = MatrixCounterTest.render(projects[i], projects[i].entities.get(0).id);
			final int h = MatrixCounterTest.firstNumber(html, header);
			final int f = MatrixCounterTest.firstNumber(html, footer);
			MatrixCounterTest.check("project " + i + ": the header (" + h + ") and the footer (" + f + ") are the same number", h == f);
			MatrixCounterTest.check("project " + i + ": and it is the tally's", h == MatrixCounter.of(projects[i], 60).needYou);
			final MatrixCounter.Tally tally = MatrixCounter.of(projects[i], 60);
			final MatrixCounter.Tally tallyViaLists = MatrixCounter.of(MatrixCompute.stillToDecide(projects[i], 60), MatrixCompute.optionalServices(projects[i]));
			MatrixCounterTest.check("project " + i + ": every route to the tally agrees", tally.needYou == tallyViaLists.needYou && tally.withButton == tallyViaLists.withButton);
			final Matcher tabs = tab.matcher(html);
			int tabSum = 0;
			while (tabs.find())
			{
				tabSum += Integer.parseInt(tabs.group(1));
			}
			MatrixCounterTest.check("project " + i + ": the tabs add up to what is still to decide", tabSum == tally.stillToDecide());
		}
	}

	/** Zero: the header and the footer say the same words, the step boxes read done, "Load the staging" opens. */
	private static void nothingLeftReadsDoneEverywhere()
	{
		final MatrixModel.Project project = MatrixCounterTest.flaggedProject("ACCEPTED", "ACCEPTED");
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		state.wizardUrl = "/ebx-ui/ui/custom/MdmFactory/data/BF/F/root/Project?service=ProjectWizard";
		final String html = MatrixHtml.render(project, state);
		MatrixCounterTest.check("the header line says nothing is left", html.contains("id=\"mdmmHeadline\">Nothing left to decide.</div>"));
		MatrixCounterTest.check("the footer says it too, without a count button", html.contains("<div class=\"mdmmFootTxt\" id=\"mdmmFootTxt\">Nothing left to decide. You can build and load the staging.")
			&& !html.contains("class=\"mdmmFootCount\" aria-expanded"));
		MatrixCounterTest.check("box 1 reads done (no stale '1')", html.contains("<span class=\"mdmmChip good\">&#10003;</span><div><b>1 \u00B7 Decide the flagged points</b><div style=\"color:#667085\">No flagged point left</div>"));
		MatrixCounterTest.check("box 2 reads done", html.contains("<span class=\"mdmmChip good\">&#10003;</span><div><b>2 \u00B7 Accept the rest in one click</b><div style=\"color:#667085\">Done: nothing left to accept</div>"));
		MatrixCounterTest.check("box 3 says it is ready", html.contains("Ready: build and load the staging below"));
		// staging-screens lot (2026-10-17): the footer's button BUILDS the staging (it was a link that only went back to the wizard); the back link is still there at the top
		MatrixCounterTest.check("the footer button builds the staging (a real action, not a link)", html.contains("id=\"mdmmBuild\" aria-disabled=\"false\" onclick=\"mdmmLoadStaging()\">Build and load the staging</button>")
			&& MatrixHtml.script(Locale.ENGLISH).contains("window.mdmmLoadStaging=function(){submitAction('LOAD_STAGING','','','');};")
			&& html.contains("id=\"mdmmBackLink\""));
		MatrixCounterTest.check("no stale 'needs your attention' text is left", !html.contains("needs your attention") && !html.contains("need your attention"));
		final String fr = MatrixCounterTest.renderFrench(project);
		MatrixCounterTest.check("the French header says it too", fr.contains("id=\"mdmmHeadline\">Plus rien \u00E0 d\u00E9cider.</div>"));
	}

	/** Four geocoders for one field = ONE folded line listing them with their readiness; Set aside all / Reject all on the line, one decision each inside. */
	private static void optionalSuggestionsAreOneFoldedLinePerFieldWithTheirReadiness()
	{
		final String html = MatrixCounterTest.render(MatrixCounterTest.servicesOnly(), "e1");
		MatrixCounterTest.check("one folded line for street: '4 services could check Adresse.street'", html.contains("<details class=\"mdmmQOptGroup\" data-ids=\"g1,g2,g3,g4\"><summary><b>4 services could check Adresse.street</b>"));
		MatrixCounterTest.check("one for postalCode, singular", html.contains("1 service could check Adresse.postalCode"));
		MatrixCounterTest.check("the set-aside look-up is not in the fold", !html.contains("mdmmSItem\" data-key=\"guard|g9\"") && !html.contains("data-ids=\"g1,g2,g3,g4,g9\""));
		MatrixCounterTest.check("the summary says how many are ready", html.contains("2 of 4 ready to use"));
		MatrixCounterTest.check("each candidate names its provider and operation", html.contains("<b>Nominatim \u00B7 geocode</b>") && html.contains("<b>Google \u00B7 geocode</b>")
			&& html.contains("<b>CompaniesHouse \u00B7 search</b>"));
		MatrixCounterTest.check("readiness and price: 'ready, free', 'ready, paid'", html.contains(">ready, free</span>") && html.contains(">ready, paid</span>"));
		MatrixCounterTest.check("a candidate that is not ready says why, with its price", html.contains(">needs set-up: its required authentication secret is missing, free up to a limit</span>"));
		MatrixCounterTest.check("one with no detail still says it needs set-up", html.contains(">needs set-up</span>"));
		MatrixCounterTest.check("Set aside all and Reject all sit on the line", html.contains("onclick=\"mdmmServiceGroup(this,'SETASIDE')\">Set aside all</button>")
			&& html.contains("onclick=\"mdmmServiceGroup(this,'REJECT')\">Reject all</button>"));
		MatrixCounterTest.check("the per-suggestion decision is inside the fold", html.contains("onclick=\"mdmmServiceDecide(event,'g1','SETASIDE')\"")
			&& html.contains("onclick=\"mdmmServiceDecide(event,'g3','REJECT')\""));
		MatrixCounterTest.check("the lines are not list items: out of the walk-through and out of the counts", !html.contains("mdmmQItem\" data-key=\"guard|g1\"") && html.contains("mdmmSItem\" data-key=\"guard|g1\""));
		final String script = MatrixHtml.script(Locale.ENGLISH);
		MatrixCounterTest.check("the script posts the group actions with the ids", script.contains("window.mdmmServiceGroup=") && script.contains("'SET_ASIDE_GUARDS'") && script.contains("'REJECT_GUARDS'")
			&& script.contains("getAttribute('data-ids')"));
		MatrixCounterTest.check("hostile text in a readiness detail is escaped", MatrixCounterTest.render(MatrixCounterTest.hostileService(), "e1").contains("&lt;img src=x&gt;")
			&& !MatrixCounterTest.render(MatrixCounterTest.hostileService(), "e1").contains("<img src=x>"));
	}

	private static MatrixModel.Project hostileService()
	{
		final MatrixModel.Attr key = new MatrixModel.Attr("a0", "sourceId", "STRING", true, false, true);
		final MatrixModel.Attr street = new MatrixModel.Attr("a1", "street", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(90),
			Arrays.asList(MatrixCounterTest.service("g1", "a1", "geocode", "Evil", Boolean.FALSE, "<img src=x>", "", ReviewLine.PROPOSED)));
		return new MatrixModel.Project("P", null, new ArrayList<MatrixModel.Entity>(Arrays.asList(
			new MatrixModel.Entity("e1", "Adresse", Arrays.asList(key, street), new ArrayList<MatrixModel.Feed>(), "ACCEPTED", Integer.valueOf(100)))));
	}

	/** "+ N optional suggestions" apart from the count; the staging can be loaded; the header's flagged box is unaffected. */
	private static void optionalSuggestionsNeverKeepTheStagingClosed()
	{
		final MatrixModel.Project project = MatrixCounterTest.servicesOnly();
		MatrixCounterTest.check("the project is ready to load", MatrixCompute.readyToLoad(project, 60) && MatrixCompute.itemsStillToDecide(project, 60) == 0);
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		state.wizardUrl = "/ebx-ui/ui/custom/MdmFactory/data/BF/F/root/Project?service=ProjectWizard";
		final String html = MatrixHtml.render(project, state);
		MatrixCounterTest.check("the footer reads done and offers '+ 5 optional suggestions' in a button of its own", html.contains("Nothing left to decide. You can build and load the staging.")
			&& html.contains("<button type=\"button\" class=\"mdmmFootOpt\" onclick=\"mdmmQueueToggle()\">+ 5 optional suggestions</button>"));
		MatrixCounterTest.check("Build and load the staging is open", html.contains("id=\"mdmmBuild\" aria-disabled=\"false\" onclick=\"mdmmLoadStaging()\""));
		MatrixCounterTest.check("the header's step 1 box counts flagged fields only: done, though 5 suggestions exist", html.contains("<span class=\"mdmmChip good\">&#10003;</span><div><b>1 \u00B7"));
		MatrixCounterTest.check("the panel says nothing is left AND lists the optional section", html.contains("Nothing left to decide") && html.contains("Optional suggestions"));
		final MatrixCounter.Tally mixed = MatrixCounter.of(MatrixQueueFixtureTest.persVol9Like(true, false), 60);
		final String mixedHtml = MatrixCounterTest.render(MatrixQueueFixtureTest.persVol9Like(true, false), "e1");
		MatrixCounterTest.check("with other items left the optional count follows the count, apart", mixedHtml.contains(">2 items need you</button>") && mixedHtml.contains("+ 2 optional suggestions</button>")
			&& mixed.optionalSuggestions == 2);
	}

	private static void onlyWhatIsLeftIsTheDefaultOnceSomethingIsDecided()
	{
		MatrixCounterTest.check("a fresh proposal (nothing decided): off", !MatrixCompute.defaultOnlyTodo(MatrixQueueFixtureTest.persVol9Like(false, false), 9));
		MatrixCounterTest.check("something decided and something left: on", MatrixCompute.defaultOnlyTodo(MatrixQueueFixtureTest.persVol9Like(true, false), 5));
		MatrixCounterTest.check("something decided, nothing left: off (an empty grid would be a blank page)", !MatrixCompute.defaultOnlyTodo(MatrixQueueFixtureTest.persVol9Like(true, true), 0));
		MatrixCounterTest.check("decided means any entity, field, source link or field link no longer proposed", MatrixCompute.hasDecidedProposal(MatrixQueueFixtureTest.persVol9Like(true, false))
			&& !MatrixCompute.hasDecidedProposal(MatrixQueueFixtureTest.persVol9Like(false, false)));
		final MatrixHtml.State on = new MatrixHtml.State();
		on.activeEntityId = "e1";
		on.onlyTodo = true;
		final String html = MatrixHtml.render(MatrixQueueFixtureTest.persVol9Like(true, false), on);
		MatrixCounterTest.check("the root and the button carry the state, and the hidden field posts it", html.contains("class=\"mdmmRoot mdmmOnlyTodo\"") && html.contains("class=\"mdmmToggle on\"")
			&& html.contains("id=\"mdmmOnlyTodo\" name=\"mdmmOnlyTodo\" value=\"1\""));
		final String script = MatrixHtml.script(Locale.ENGLISH);
		MatrixCounterTest.check("the toggle goes through one apply function and tells the page's store", script.contains("window.mdmmApplyTodo=") && script.contains("window.mdmmTodoRemember(on)"));
	}

	private static void scopeLinesAndTheSecondaryPerLinkAction()
	{
		final String html = MatrixCounterTest.render(MatrixQueueFixtureTest.persVol9Like(false, false), "e1");
		MatrixCounterTest.check("a line under the global button says what it covers", html.contains("<small class=\"mdmmScopeNote\">Takes, in every business object, each proposal at 60 % or above that is not flagged, with its cleaning steps and computed fields.</small>"));
		MatrixCounterTest.check("a line under the object's button says what it covers", html.contains("<small class=\"mdmmScopeNote\">Takes, in Personne only, each proposal at 60 % or above that is not flagged.</small>"));
		MatrixCounterTest.check("exactly two scope lines: no third bulk button was given one", html.split("class=\"mdmmScopeNote\"", -1).length == 3);
		final int feed = html.indexOf("onclick=\"mdmmBulkFeed(");
		final int open = html.lastIndexOf("<button", feed);
		final String button = html.substring(open, html.indexOf("</button>", feed));
		MatrixCounterTest.check("the per-link bulk is a small secondary action, never a primary button", button.contains("mdmmBtnXs mdmmBtnLink") && !button.contains("mdmmBtnPrimary") && button.contains("title=\"Takes only this source link"));
		MatrixCounterTest.check("it is drawn as a link in the page's own style", html.contains("#mdmm .mdmmBtnLink{background:none"));
		MatrixCounterTest.check("the French lines exist", MatrixCounterTest.renderFrench(MatrixQueueFixtureTest.persVol9Like(false, false))
			.contains("Prend, dans Personne seulement, chaque proposition \u00E0 60 % ou plus qui n&#39;est pas signal\u00E9e"));
	}

	private static void linksTakeThePerspectiveRouteWhenDrawn()
	{
		final String script = MatrixHtml.script(Locale.ENGLISH);
		MatrixCounterTest.check("at load, every ui/advanced link of the screen is rewritten to ui/custom/<perspective>/ when inside a perspective",
			script.contains("document.querySelectorAll('#mdmm a[href*=\"/ui/advanced/data/\"]')") && script.contains(".split('/ui/advanced/data/').join('/ui/custom/'+pm[1]+'/data/')"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
