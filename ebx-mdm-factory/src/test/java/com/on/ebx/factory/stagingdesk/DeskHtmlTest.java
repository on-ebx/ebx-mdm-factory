package com.on.ebx.factory.stagingdesk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.mappingmatrix.MatrixHtml;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.review.ReviewLine;

/**
 * Validation desk lot L1 (docs/STAGING-DESK.md): what the page prints for the fixture of {@code DeskGroupsTest} - the key texts, the buttons and the action they post -
 * the empty state, the read-only page, the escaping, the script, the two languages, and the entry in the matrix header. JUnit-free: run the main, a failure throws.
 */
public final class DeskHtmlTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		DeskHtmlTest.theHeaderOffersTheFourViews();
		DeskHtmlTest.theLeftColumnShowsTheNumberTheGroupsAndWhatFollows();
		DeskHtmlTest.theObjectCentreListsItsLinesWithTheirActionsAndTheBulkButton();
		DeskHtmlTest.theChecksCentreDecidesLineByLine();
		DeskHtmlTest.aRuleOfTheChecksGroupCanBeOpenedOnTheValuesItWouldFlag();
		DeskHtmlTest.theReasonColumnOnlySaysWhatIsTrueOnTheDesk();
		DeskHtmlTest.theCannotRunCentreSetsThemAsideTogether();
		DeskHtmlTest.theEmptyStateLinksToTheBuild();
		DeskHtmlTest.aReadOnlySessionSeesDisabledButtons();
		DeskHtmlTest.nothingIsUnescapedAndNoScriptTagIsWritten();
		DeskHtmlTest.theScriptFillsTheHiddenFieldsBeforeTheSubmit();
		DeskHtmlTest.everyKeyExistsInBothLanguages();
		DeskHtmlTest.theMatrixHeaderOffersTheDesk();
		DeskHtmlTest.theObjectCentreShowsTheRowsAsTheyWillBeLoaded();
		DeskHtmlTest.theBarSaysWhatAcceptingTakesAndLeaves();
		DeskHtmlTest.thePreviewFallsBackToTheListAndKeepsItReachable();
		DeskHtmlTest.thePreviewEscapesAndSpeaksFrench();
		System.out.println("DeskHtmlTest OK");
	}

	private static DeskHtml.State state(final String pSelected)
	{
		final DeskHtml.State state = new DeskHtml.State();
		state.selectedKey = pSelected;
		state.wizardUrl = "/ebx-ui/ui/advanced/data/wizard";
		state.matrixUrl = "/ebx-ui/ui/advanced/data/matrix";
		state.designerUrl = "/ebx-ui/ui/advanced/data/designer";
		return state;
	}

	private static String page(final String pSelected)
	{
		return DeskHtml.render(DeskGroupsTest.desk(), DeskHtmlTest.state(pSelected));
	}

	private static void theHeaderOffersTheFourViews()
	{
		final String html = DeskHtmlTest.page("obj|e2");
		DeskHtmlTest.check("the project and the step", html.contains("Project DemoBala") && html.contains("Step 4 of 8"));
		DeskHtmlTest.check("the four views, the desk being the current one",
			html.contains(">Wizard card</a>") && html.contains("aria-current=\"page\">Validation desk</span>") && html.contains(">Matrix</a>") && html.contains(">Designer</a>"));
		DeskHtmlTest.check("the links leave the page as a voluntary departure, in the top window",
			html.contains("href=\"/ebx-ui/ui/advanced/data/wizard\"") && html.contains("class=\"mdskBtn mdskBtnQuiet mdskBtnSm mdmf-matrix-designer-link\" target=\"_top\""));
	}

	private static void theLeftColumnShowsTheNumberTheGroupsAndWhatFollows()
	{
		final String html = DeskHtmlTest.page("obj|e2");
		DeskHtmlTest.check("3 decisions for you", html.contains("<span id=\"mdskDecisions\">3</span>") && html.contains("decisions for you"));
		DeskHtmlTest.check("15 proposals open, they come down to 3", html.contains("16 proposals are still open. Grouped by what you actually decide, they come down to 3."));
		DeskHtmlTest.check("7 of 23 decided, with the bar", html.contains("7 of 23 proposals decided") && html.contains("width:30%"));
		DeskHtmlTest.check("the three sections", html.contains("Accept what you see") && html.contains(">Checks<") && html.contains("Cannot run as proposed"));
		DeskHtmlTest.check("the object group: title, sub-line, chips", html.contains("Adresse · fields and source links") && html.contains("3 fields, 7 links from 2 sources")
			&& html.contains("10 proposals") && html.contains("1 point to check"));
		DeskHtmlTest.check("the checks group", html.contains("Adresse · 2 checks") && html.contains("2 proposals"));
		DeskHtmlTest.check("the cannot-run group says both reasons", html.contains("3 services and scripts") && html.contains("2 need an input no field holds.") && html.contains("1 has no secret."));
		DeskHtmlTest.check("a group is selected by posting its key", html.contains("onclick=\"mdskGo('obj|e2')\"") && html.contains("class=\"mdskQ on\""));
		DeskHtmlTest.check("the passive list: nothing to decide", html.contains("Follow their object") && html.contains("<code>Adresse.personne</code>") && html.contains("link to the parent")
			&& html.contains("<code>Adresse.sourceId</code>") && html.contains("technical key"));
		DeskHtmlTest.check("the optional look-up is folded and says it is not counted", html.contains("<details") && html.contains("1 optional look-up suggestion (not counted)"));
		DeskHtmlTest.check("the parent link has no Accept button", !html.contains("ACCEPT_ATTRIBUTE','b1'"));
	}

	private static void theObjectCentreListsItsLinesWithTheirActionsAndTheBulkButton()
	{
		final String html = DeskHtmlTest.page("obj|e2");
		DeskHtmlTest.check("the object, its lead", html.contains("<h1>Adresse</h1>") && html.contains("3 fields and 7 links"));
		DeskHtmlTest.check("the way to the matrix", html.contains("Open in the matrix") && html.contains("href=\"/ebx-ui/ui/advanced/data/matrix\""));
		DeskHtmlTest.check("the point to check first, with the matrix' own words", html.contains("1 point to check first") && html.contains("needs your eye"));
		DeskHtmlTest.check("the columns", html.contains(">Kind<") && html.contains(">Element<") && html.contains(">Proposed<") && html.contains(">Why<") && html.contains(">Confidence<"));
		DeskHtmlTest.check("a field line: type, the proposer's reason, confidence, its own accept and reject",
			html.contains("<b>street</b>") && html.contains("<code>STRING</code>") && html.contains("Street as one text") && html.contains("92 %")
				&& html.contains("mdskDo('ACCEPT_ATTRIBUTE','b2','',false)") && html.contains("mdskDo('REJECT_ATTRIBUTE','b2','',false)"));
		DeskHtmlTest.check("a field link line: column and source; accept goes through the field first (the matrix' two-step action); reject asks why",
			html.contains("<code>PostalCode</code>") && html.contains("SAP") && html.contains("mdskDo('ACCEPT_ATTR_THEN_MAPPING','b3','m12',false)")
				&& html.contains("mdskDo('REJECT_MAPPING','m12','',true)"));
		DeskHtmlTest.check("a source link line: accept and reject (asking why)", html.contains("mdskDo('ACCEPT_FEED','fA1','',false)") && html.contains("mdskDo('REJECT_FEED','fA1','',true)"));
		DeskHtmlTest.check("the flagged line is marked", html.contains("class=\"mdskLine eye\""));
		DeskHtmlTest.check("the bulk bar: how many go with the button, the others wait, the object's name on the button",
			html.contains("Accepting takes ") && html.contains("proposals; the other ") && html.contains("Accept Adresse") && html.contains("mdskDo('BULK_ENTITY','e2','',false)"));
		DeskHtmlTest.check("rejecting a field takes its links", html.contains("Rejecting a field takes its links and checks with it."));
		DeskHtmlTest.check("the centre of an object group is a method of its own (the seam of lot L2)", java.util.Arrays.asList(DeskHtml.class.getDeclaredMethods()).toString().contains("renderObjectCentre"));
	}

	private static void theReasonColumnOnlySaysWhatIsTrueOnTheDesk()
	{
		int bulk = 0;
		int below = 0;
		for (final MatrixCompute.StillItem item : MatrixCompute.stillToDecide(DeskGroupsTest.project(false), MatrixCompute.DEFAULT_BULK_THRESHOLD))
		{
			if (MatrixCompute.REASON_BULK.equals(item.reason))
			{
				bulk++;
				DeskHtmlTest.check("\"goes with the Accept button\" is never printed", DeskHtml.whyText(item, true, 60, Locale.ENGLISH).isEmpty() && DeskHtml.whyText(item, false, 60, Locale.ENGLISH).isEmpty());
			}
			else if (MatrixCompute.REASON_BELOW_BAR.equals(item.reason))
			{
				below++;
				DeskHtmlTest.check("\"under the bar\" only where the confidence bulk is on the page", DeskHtml.whyText(item, true, 60, Locale.ENGLISH).contains("bar") && DeskHtml.whyText(item, false, 60, Locale.ENGLISH).isEmpty());
			}
			else if (MatrixCompute.REASON_ATTENTION.equals(item.reason))
			{
				DeskHtmlTest.check("what needs your eye is always said", !DeskHtml.whyText(item, false, 60, Locale.ENGLISH).isEmpty());
			}
		}
		DeskHtmlTest.check("the fixture holds both kinds of reason: " + bulk + "/" + below, bulk > 0);
	}

	private static void theChecksCentreDecidesLineByLine()
	{
		final String html = DeskHtmlTest.page("chk|e2");
		DeskHtmlTest.check("the title and the lead", html.contains("<h1>Adresse: checks</h1>") && html.contains("Each is decided on its own."));
		DeskHtmlTest.check("the rule and the check, with what they test", html.contains("<code>PATTERN</code>") && html.contains("<code>^[0-9 ]+$</code>") && html.contains("<code>postal.isValid</code>"));
		DeskHtmlTest.check("accept and reject post the guard's id and nature", html.contains("mdskDo('ACCEPT_GUARD','g1','QUALITY',false)") && html.contains("mdskDo('REJECT_GUARD','g2','SCRIPT_CONSTRAINT',false)"));
		DeskHtmlTest.check("no bulk on checks", !html.contains("BULK_ENTITY"));
		// live fix 2026-10-22: the Checks group has no Accept-all button and no confidence bar, so the matrix' wording about them is not printed there
		DeskHtmlTest.check("no \"goes with the Accept button\", no \"under the N % bar\" in Checks", !html.contains("goes with the Accept button") && !html.contains("% bar"));
	}

	/** Lot L4: a QUALITY line has "Look at it"; the opened check replaces the list and goes back with "mdskOpenCheck('')". */
	private static void aRuleOfTheChecksGroupCanBeOpenedOnTheValuesItWouldFlag()
	{
		final DeskHtml.State list = DeskHtmlTest.state("chk|e2");
		list.checks.put("g1", new DeskCheck("g1", "Adresse", "postalCode", "PATTERN", "^[0-9 ]+$", "WARN", "", Long.valueOf(10), Long.valueOf(2), "", "", null));
		final String listHtml = DeskHtml.render(DeskGroupsTest.desk(), list);
		DeskHtmlTest.check("the rule has its button, the script check does not", listHtml.contains("mdskOpenCheck('g1')") && !listHtml.contains("mdskOpenCheck('g2')"));
		DeskHtmlTest.check("the hidden field carries no open check", listHtml.contains("id=\"mdskCheck\" name=\"mdskCheck\" value=\"\""));
		final DeskHtml.State open = DeskHtmlTest.state("chk|e2");
		open.checks.putAll(list.checks);
		open.openCheckId = "g1";
		final String openHtml = DeskHtml.render(DeskGroupsTest.desk(), open);
		DeskHtmlTest.check("the opened check replaces the list", openHtml.contains("Check shown on the values it would flag") && !openHtml.contains("Each is decided on its own."));
		DeskHtmlTest.check("the field remembers it, and the page script can open and close a check", openHtml.contains("name=\"mdskCheck\" value=\"g1\"") && DeskHtml.script(Locale.ENGLISH).contains("window.mdskOpenCheck="));
		final DeskHtml.State gone = DeskHtmlTest.state("chk|e2");
		gone.openCheckId = "g99";
		DeskHtmlTest.check("an id that is not a line of the group shows the list", DeskHtml.render(DeskGroupsTest.desk(), gone).contains("Each is decided on its own."));
	}

	private static void theCannotRunCentreSetsThemAsideTogether()
	{
		final String html = DeskHtmlTest.page("run|");
		DeskHtmlTest.check("the title", html.contains("<h1>Cannot run as proposed</h1>"));
		DeskHtmlTest.check("both reasons, the detail of the first", html.contains("No secret") && html.contains("Needs an input no field holds") && html.contains("its required authentication secret is missing"));
		DeskHtmlTest.check("each service: set aside or reject", html.contains("mdskDo('SET_ASIDE_GUARD','g3','',false)") && html.contains("mdskDo('REJECT_GUARD','g5','EXTERNAL_SERVICE',false)"));
		DeskHtmlTest.check("\"Set the 2 aside\" posts both ids in one go - the wizard card's write", html.contains("Set the 2 aside") && html.contains("mdskDo('SET_ASIDE_GUARDS','g3,g5','EXTERNAL_SERVICE',false)"));
		// live fix 2026-10-22: the script check that cannot run is listed here, rejected with its own nature, and cannot be set aside
		DeskHtmlTest.check("the script check that cannot run is in this group, not in Checks", html.contains("address.geocode") && !DeskHtmlTest.page("chk|e2").contains("address.geocode"));
		DeskHtmlTest.check("it can only be rejected, with its own nature (setCapabilitiesAside moves services only)", html.contains("mdskDo('REJECT_GUARD','g6','SCRIPT_CONSTRAINT',false)")
			&& !html.contains("mdskDo('SET_ASIDE_GUARD','g6'") && !html.contains("g6,"));
	}

	private static void theEmptyStateLinksToTheBuild()
	{
		final DeskModel desk = DeskModel.of(DeskGroupsTest.project(true), DeskGroupsTest.snapshot(true), MatrixCompute.DEFAULT_BULK_THRESHOLD);
		final String html = DeskHtml.render(desk, DeskHtmlTest.state(""));
		DeskHtmlTest.check("0 decisions for you, everything is decided", html.contains("0 decisions for you") && html.contains("Everything is decided. You can build the staging."));
		DeskHtmlTest.check("the objects and sources", html.contains("Personne, Adresse, 2 sources."));
		DeskHtmlTest.check("the build is the wizard's: a link to step 4, not a second button", html.contains("Fill the shared structure") && html.contains("href=\"/ebx-ui/ui/advanced/data/wizard\"")
			&& !html.contains("LOAD_STAGING"));
		DeskHtmlTest.check("the three boxes", html.contains(">Accepted<") && html.contains("Set aside, kept for later") && html.contains("Followed their object"));
		DeskHtmlTest.check("what each holds", html.contains("Adresse: 4 fields, 6 links") && html.contains("Adresse.postalCode: Geo · geocode") && html.contains("<li>Adresse.personne</li>"));
		DeskHtmlTest.check("a way back to a decision", html.contains("Review a decision again"));
		DeskHtmlTest.check("no groups column", !html.contains("<aside") && !html.contains("class=\"mdskQ"));
	}

	private static void aReadOnlySessionSeesDisabledButtons()
	{
		final DeskHtml.State state = DeskHtmlTest.state("obj|e2");
		state.readOnly = true;
		final String html = DeskHtml.render(DeskGroupsTest.desk(), state);
		DeskHtmlTest.check("the page says why", html.contains("You can read this screen but not decide here"));
		DeskHtmlTest.check("the decision buttons are disabled", html.contains("id=\"mdskBulkObject\" class=\"mdskBtn mdskBtnPrimary\" disabled=\"disabled\"")
			&& html.contains("class=\"mdskBtn mdskBtnSm mdskBtnPrimary\" disabled=\"disabled\""));
	}

	private static void nothingIsUnescapedAndNoScriptTagIsWritten()
	{
		final MatrixModel.Project project = DeskGroupsTest.project(false);
		final MatrixModel.Entity adresse = project.entityById("e2");
		final MatrixModel.Entity hostile = new MatrixModel.Entity("e2", "Adr<script>alert(1)</script>'\"", adresse.attrs, adresse.feeds, adresse.status, adresse.confidence);
		project.entities.set(1, hostile);
		final DeskHtml.State state = DeskHtmlTest.state("obj|e2");
		state.message = "<b>done</b>";
		final String html = DeskHtml.render(DeskModel.of(project, null, MatrixCompute.DEFAULT_BULK_THRESHOLD), state);
		DeskHtmlTest.check("a name carrying markup is escaped", !html.contains("<script") && html.contains("Adr&lt;script&gt;alert(1)&lt;/script&gt;&#39;&quot;"));
		DeskHtmlTest.check("a message is escaped", html.contains("&lt;b&gt;done&lt;/b&gt;") && !html.contains("<b>done</b>"));
		DeskHtmlTest.check("the key posted by a button is a JS literal that cannot break out of the attribute", !html.contains("onclick=\"mdskGo('obj|e2')\"\"") && html.contains("mdskGo('obj|e2')"));
		DeskHtmlTest.check("the hidden fields the matrix' handler reads, and the group", html.contains("name=\"mdmmAction\"") && html.contains("name=\"mdmmRowId\"") && html.contains("name=\"mdmmRowId2\"")
			&& html.contains("name=\"mdmmComment\"") && html.contains("name=\"mdskGroup\" value=\"obj|e2\""));
	}

	private static void theScriptFillsTheHiddenFieldsBeforeTheSubmit()
	{
		final String script = DeskHtml.script(Locale.ENGLISH);
		DeskHtmlTest.check("an IIFE with no script tag", script.startsWith("(function(){") && script.endsWith("})();") && !script.contains("<script"));
		DeskHtmlTest.check("it clicks the service's one submit button", script.contains("byId('" + DeskHtml.SUBMIT_ID + "')") && DeskHtml.SUBMIT_ID.equals("mdskSubmit"));
		DeskHtmlTest.check("the group travels in its hidden field, set before the submit", script.contains("window.mdskGo=function(key){byId('mdskGroup').value=key;submit('GOTO_GROUP'"));
		DeskHtmlTest.check("a reject asks why first and stops on cancel", script.contains("window.prompt(T.rejectReason);if(c===null){return;}"));
		DeskHtmlTest.check("the page's scroll travels with the submit and comes back", script.contains("mdskScroll") && script.contains("data-scrolltop"));
	}

	private static void everyKeyExistsInBothLanguages() throws IOException
	{
		final String dir = System.getProperty("mdmf.sourceDir", "src/main/java") + "/com/on/ebx/factory/stagingdesk/";
		final Set<String> keys = new HashSet<String>();
		// lot L5: every class of the package that prints a desk text (DeskInputHtml, and the classes of the lots that follow) is read, not a fixed list of three
		final java.util.List<String> files = new java.util.ArrayList<String>();
		for (final java.io.File file : new java.io.File(dir).listFiles())
		{
			if (file.getName().startsWith("Desk") && file.getName().endsWith(".java") && !file.getName().equals("DeskMessages.java"))
			{
				files.add(file.getName());
			}
		}
		// lot L4: the three classes outside the package that write desk notices (desk.l4.*)
		files.add("../ebx/ui/StagingDeskUserService.java");
		files.add("../ebx/ui/MappingMatrixUserService.java");
		files.add("../ebx/RuleBreakdownRun.java");
		for (final String file : files)
		{
			final String source = new String(Files.readAllBytes(Paths.get(dir + file)), StandardCharsets.UTF_8);
			// outside the package only the keys read through DeskMessages count: those classes also print desk.* notices of the ebx/ui bundle (lot L5)
			final Matcher m = (file.startsWith("../") ? Pattern.compile("DeskMessages\\.get\\([^,]+,\\s*\"(desk\\.[A-Za-z0-9.]*[A-Za-z0-9])\"")
				: Pattern.compile("\"(desk\\.[A-Za-z0-9.]*[A-Za-z0-9])\"")).matcher(source);
			while (m.find())
			{
				keys.add(m.group(1));
			}
		}
		for (final String unit : new String[] { "proposal", "field", "link", "source", "check", "service", "suggestion", "row", "group" })
		{
			keys.add("desk.unit." + unit + ".one");
			keys.add("desk.unit." + unit + ".other");
		}
		for (final String reason : new String[] { "NOT_FOUND", "NOT_PROPOSED", "FAILED" })
		{
			keys.add("desk.l4.notice.clearFailed." + reason); // built from DeskCheckWrites' codes
		}
		DeskHtmlTest.check("the sources name the desk keys: " + keys.size(), keys.size() > 60);
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final ResourceBundle bundle = ResourceBundle.getBundle("com.on.ebx.factory.stagingdesk.DeskMessages", locale);
			for (final String key : keys)
			{
				DeskHtmlTest.check(locale + " has " + key, bundle.containsKey(key));
			}
			// no key is left over in a language that the page no longer uses
			final Enumeration<String> own = bundle.getKeys();
			while (own.hasMoreElements())
			{
				final String key = own.nextElement();
				DeskHtmlTest.check(locale + " carries a key nobody uses: " + key, keys.contains(key));
			}
		}
		final String french = DeskHtml.render(DeskGroupsTest.desk(), DeskHtmlTest.frenchState());
		DeskHtmlTest.check("the French page speaks French and prints no raw key", french.contains("Bureau de validation") && french.contains("décisions pour vous") && french.contains("Accepter Adresse")
			&& !french.contains("desk."));
	}

	private static DeskHtml.State frenchState()
	{
		final DeskHtml.State state = DeskHtmlTest.state("obj|e2");
		state.locale = Locale.FRENCH;
		return state;
	}

	private static void theMatrixHeaderOffersTheDesk()
	{
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e2";
		state.deskUrl = "/ebx-ui/ui/advanced/data/desk?x=1&y=2";
		final String html = MatrixHtml.render(DeskGroupsTest.project(false), state);
		DeskHtmlTest.check("the matrix header has the desk in its view switch, as a voluntary departure",
			html.contains("<a id=\"mdmmDeskSwitch\" class=\"mdmf-matrix-designer-link\" target=\"_top\" href=\"/ebx-ui/ui/advanced/data/desk?x=1&amp;y=2\">Validation desk</a>"));
		state.deskUrl = null;
		DeskHtmlTest.check("without a desk URL nothing is drawn", !MatrixHtml.render(DeskGroupsTest.project(false), state).contains("mdmmDeskSwitch"));
		DeskHtmlTest.check("the reason text the desk quotes is the matrix' own", MatrixHtml.reasonText(MatrixCompute.stillToDecide(DeskGroupsTest.project(false), 60).get(0), 60, Locale.ENGLISH) != null);
	}


	// ------------------------------------------------------------------------------------------------------ lot L2

	private static String previewPage(final boolean pReadOnly, final java.util.Locale pLocale)
	{
		final DeskHtml.State state = DeskHtmlTest.state("obj|e2");
		state.preview = DeskPreviewTest.preview();
		state.readOnly = pReadOnly;
		state.locale = pLocale;
		return DeskHtml.render(DeskGroupsTest.desk(), state);
	}

	private static void theObjectCentreShowsTheRowsAsTheyWillBeLoaded()
	{
		final String html = DeskHtmlTest.previewPage(false, Locale.ENGLISH);
		DeskHtmlTest.check("the title", html.contains("<h1>Adresse, as it will be loaded</h1>") && html.contains("applied to real landing rows"));
		DeskHtmlTest.check("one block per source with its landing table and row count",
			html.contains("<strong>Salesforce</strong> <span class=\"mdskChip mut\">Contact \u00B7 100 rows</span>") && html.contains("<strong>SAP</strong> <span class=\"mdskChip mut\">ADRC \u00B7 165 rows</span>"));
		DeskHtmlTest.check("the range, and the other 5 as pure navigation", html.contains("Rows 1 to 3 of 100") && html.contains("onclick=\"mdskDo('NEXT_ROWS','fA2','5',false)\">another 5</button>"));
		DeskHtmlTest.check("on the last page the link goes back to the first rows", html.contains("Rows 6 to 7 of 165") && html.contains("mdskDo('NEXT_ROWS','fA1','0',false)\">back to the first 5</button>"));
		DeskHtmlTest.check("the grey source column, then the headers with their how",
			html.contains("<th class=\"raw\">MailingStreet<span class=\"mdskSub\">source</span></th>") && html.contains("<th>postalCode<span class=\"mdskSub mono\">MailingPostalCode</span></th>")
				&& html.contains("<span class=\"mdskSub mono\">constant(&#39;MAILING&#39;)</span>"));
		DeskHtmlTest.check("the raw and the produced values", html.contains("<td class=\"raw\">66 rue de la Republique</td>") && html.contains("<td>66</td>") && html.contains("<td>rue de la Republique</td>"));
		DeskHtmlTest.check("a missing value, a value computed at load, the parent chip, the technical key absent", html.contains("<span class=\"mdskDim\">(empty)</span>")
			&& html.contains("<span class=\"mdskDim\">computed at load</span>") && html.contains("<span class=\"mdskChip acc\">its Personne</span>") && html.contains("<span class=\"mdskSub\">by the engine</span>")
			&& !html.contains("<th>sourceId"));
		DeskHtmlTest.check("a failing rule flags the cell with a one-line reason", html.contains("<td class=\"flag\">111 20<span class=\"mdskSub\">PATTERN ^[0-9]{5}$ would warn</span></td>"));
		DeskHtmlTest.check("the matrix' flag colours the column, its reason once", html.contains("<td class=\"eye\">MAILING<span class=\"mdskSub\">the reason says residential but the constant says mailing</span></td>")
			&& html.contains("<td class=\"eye\">MAILING</td>"));
		DeskHtmlTest.check("the fill line", html.contains("10 of 100 rows have no postalCode") && html.contains("3 of 100 rows have no city"));
	}

	private static void theBarSaysWhatAcceptingTakesAndLeaves()
	{
		final String html = DeskHtmlTest.previewPage(false, Locale.ENGLISH);
		DeskHtmlTest.check("the sentence", html.contains("Accepting takes 5 fields and 8 of the 10 links.") && html.contains("2 flagged links wait for you.")
			&& html.contains("A column you reject takes its links and checks with it."));
		DeskHtmlTest.check("the button posts the new action on the object", html.contains("id=\"mdskAsShown\"") && html.contains("onclick=\"mdskDo('ACCEPT_ENTITY_AS_SHOWN','e2','',false)\">Accept Adresse as shown</button>"));
		DeskHtmlTest.check("rejecting a column posts the existing REJECT of that field", html.contains("Reject a column...") && html.contains("onclick=\"mdskDo('REJECT_ATTRIBUTE','b2','',false)\">street</button>"));
		DeskHtmlTest.check("the whole-sentence stays reachable by the old list: folded details with the confidence bulk and the per-line buttons",
			html.contains("<details class=\"mdskCard mdskDetails\"><summary>Details, one line per proposal (10)</summary>") && html.contains("id=\"mdskBulkObject\"") && html.contains("mdskDo('ACCEPT_ATTRIBUTE'"));
		final String readOnly = DeskHtmlTest.previewPage(true, Locale.ENGLISH);
		DeskHtmlTest.check("a read-only session cannot accept or reject", readOnly.contains("id=\"mdskAsShown\" class=\"mdskBtn mdskBtnPrimary\" disabled=\"disabled\"")
			&& readOnly.contains("<button type=\"button\" class=\"mdskMenuItem\" disabled=\"disabled\""));
		DeskHtmlTest.check("with nothing left the button is off and says so", DeskHtmlTest.nothingLeftBar().contains("Nothing is left to accept as shown.")
			&& DeskHtmlTest.nothingLeftBar().contains("id=\"mdskAsShown\" class=\"mdskBtn mdskBtnPrimary\" disabled=\"disabled\""));
	}

	private static String nothingLeftBar()
	{
		final DeskHtml.State state = DeskHtmlTest.state("obj|e2");
		final MatrixModel.Project project = DeskPreviewTest.project();
		state.preview = DeskPreview.build(project, project.entityById("e2"), null, null, null);
		return DeskHtml.render(DeskGroupsTest.desk(), state);
	}

	private static void thePreviewFallsBackToTheListAndKeepsItReachable()
	{
		final String html = DeskHtmlTest.previewPage(false, Locale.ENGLISH);
		DeskHtmlTest.check("a feed that cannot be shown says why, with the matrix' words, then lists its proposals",
			html.contains("Rows cannot be shown for this source (no sample for collection feeds: the loader reads a sub-element of the row this method does not resolve). Its proposals:")
				&& html.contains("<strong>Legacy</strong>"));
		DeskHtmlTest.check("and not the other sources' lines under it", html.indexOf("Its proposals:") < html.indexOf("Details, one line per proposal"));
		DeskHtmlTest.check("without a preview the page is the L1 page", !DeskHtml.render(DeskGroupsTest.desk(), DeskHtmlTest.state("obj|e2")).contains("as it will be loaded"));
	}

	private static void thePreviewEscapesAndSpeaksFrench()
	{
		final MatrixModel.Project project = DeskPreviewTest.project();
		final java.util.Map<String, DeskPreview.FeedRows> rows = new java.util.HashMap<String, DeskPreview.FeedRows>();
		final DeskPreview.FeedRows sap = new DeskPreview.FeedRows();
		sap.documents.add(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("HOUSE_NO", "<b>1</b>").put("STREET_NAME", "\"quote\" & co"));
		rows.put("fA1", sap);
		final DeskHtml.State state = DeskHtmlTest.state("obj|e2");
		state.preview = DeskPreview.build(project, project.entityById("e2"), rows, null, null);
		final String html = DeskHtml.render(DeskGroupsTest.desk(), state);
		DeskHtmlTest.check("a landing value is escaped", html.contains("&lt;b&gt;1&lt;/b&gt;") && html.contains("&quot;quote&quot; &amp; co") && !html.contains("<b>1</b>"));
		final String french = DeskHtmlTest.previewPage(false, Locale.FRENCH);
		DeskHtmlTest.check("French: no raw key, the bar and the rows", !french.contains("desk.") && french.contains("tel qu\u2019il sera charg\u00E9") && french.contains("Accepter Adresse tel quel")
			&& french.contains("Lignes 1 \u00E0 3 sur 100") && french.contains("calcul\u00E9 au chargement") && french.contains("son Personne"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
