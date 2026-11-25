package com.on.ebx.factory.ebx.ui;

import java.util.Date;
import java.util.Locale;

/** JUnit-free: run the main, a failure throws. Exercises {@link ProjectExplorerPage} without any EBX type. */
public final class ProjectExplorerPageTest
{
	private static final Date NOW = new Date(1790000000000L);

	public static void main(final String[] pArgs)
	{
		ProjectExplorerPageTest.oneCardRendersItsFields();
		ProjectExplorerPageTest.labelAndDescriptionAreEscaped();
		ProjectExplorerPageTest.bothLocalesRender();
		ProjectExplorerPageTest.emptyListRendersNoCardsButKeepsTheEmptyMessage();
		ProjectExplorerPageTest.disabledNewProjectButtonCarriesTheReasonAsATitle();
		ProjectExplorerPageTest.enabledNewProjectButtonIsAPlainLink();
		ProjectExplorerPageTest.newProjectNotOfferedWithoutDesign();
		ProjectExplorerPageTest.advancedViewLinkCarriesTheVoluntaryLeaveMarkerClass();
		ProjectExplorerPageTest.multiValuedSourceAttributeListsEverySystem();
		ProjectExplorerPageTest.zonesRenderPresentAndAbsent();
		ProjectExplorerPageTest.scriptNamesTheMultiValuedAttributes();
		ProjectExplorerPageTest.cardLinksEncodeACodeCarryingAQuoteAndASpace();
		ProjectExplorerPageTest.favoriteStarRendersOnAndOffAndEscapesTheKey();
		ProjectExplorerPageTest.favoritesFacetIsFirstAndAlwaysOffered();
		ProjectExplorerPageTest.favoriteEndpointIsRenderedAsAHiddenSpan();
		ProjectExplorerPageTest.deleteButtonAbsentForNonAdmin();
		ProjectExplorerPageTest.deleteButtonPresentForAdmin();
		ProjectExplorerPageTest.serverPagedPageCarriesNoCard();
		ProjectExplorerPageTest.queryAnswerCarriesPagesCountsAndFavorites();
		ProjectExplorerPageTest.dumpServerScriptsForTheParser();

		System.out.println("ProjectExplorerPageTest OK");
	}

	private static void oneCardRendersItsFields()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		model.cards.add(ProjectExplorerPageTest.card("Companies", "Companies 360", "A golden view of every company.", "LIVE", "CONSOLIDATION",
			"TARGET_EXISTS", "Alice", new String[] { "ERP" }, 3));
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("the code is shown", html.indexOf("Companies") >= 0);
		ProjectExplorerPageTest.check("the label is shown", html.indexOf("Companies 360") >= 0);
		ProjectExplorerPageTest.check("the description is shown", html.indexOf("A golden view of every company.") >= 0);
		ProjectExplorerPageTest.check("the owner line is shown", html.indexOf("Alice") >= 0);
		ProjectExplorerPageTest.check("one card is rendered", html.indexOf("mdmc-card") >= 0);
		ProjectExplorerPageTest.check("the group is the LIVE status, correctly labelled", html.indexOf(Messages.get(ProjectExplorerPage.class, Locale.ENGLISH, "wizard.status.LIVE")) >= 0);
		ProjectExplorerPageTest.check("the wizard link carries the project's own code",
			html.indexOf(FactoryLinks.wizardUrl(model.dataspaceKey, model.datasetName, "Companies").replace("&", "&amp;")) >= 0);
		ProjectExplorerPageTest.check("the cockpit link carries the project's own code",
			html.indexOf(FactoryLinks.cockpitUrl(model.dataspaceKey, model.datasetName, "Companies").replace("&", "&amp;")) >= 0);
		ProjectExplorerPageTest.check("no delete button for a non-admin (canDelete defaults false)", html.indexOf("mdmc-btn-danger") < 0);
		ProjectExplorerPageTest.check("every action link opens the top window", countOccurrences(html, "target=\"_top\"") >= 3); // New project + advanced view + wizard + cockpit
	}

	private static void labelAndDescriptionAreEscaped()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		final ProjectExplorerPage.Card card = ProjectExplorerPageTest.card("X", "<script>alert('x')</script>", "quote \" and apostrophe ' and <b>tag</b>", "DRAFT",
			"CONSOLIDATION", "GREENFIELD", "O'Brien & co", new String[0], 0);
		model.cards.add(card);
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("no raw <script> tag leaked from the label", html.indexOf("<script>alert") < 0);
		ProjectExplorerPageTest.check("the label is present, escaped", html.indexOf("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;") >= 0);
		ProjectExplorerPageTest.check("no raw <b> tag from the description", html.indexOf("<b>tag</b>") < 0);
		ProjectExplorerPageTest.check("the description is present, escaped", html.indexOf("&lt;b&gt;tag&lt;/b&gt;") >= 0);
		ProjectExplorerPageTest.check("a quote in the description is escaped, never breaking an attribute", html.indexOf("quote &quot;") >= 0);
		ProjectExplorerPageTest.check("an apostrophe-carrying owner is escaped in the data-owner attribute", html.indexOf("data-owner=\"O&#39;Brien &amp; co\"") >= 0);
	}

	private static void bothLocalesRender()
	{
		final ProjectExplorerPage.Model en = ProjectExplorerPageTest.model(Locale.ENGLISH);
		en.cards.add(ProjectExplorerPageTest.card("A", "A", "", "DRAFT", "CONSOLIDATION", "GREENFIELD", "", new String[0], 0));
		final String htmlEn = ProjectExplorerPage.render(en);
		ProjectExplorerPageTest.check("English wording is used", htmlEn.indexOf("New project") >= 0);
		ProjectExplorerPageTest.check("no stray backslash-u escape leaked into the rendered page", htmlEn.indexOf("\\u") < 0);

		final ProjectExplorerPage.Model fr = ProjectExplorerPageTest.model(Locale.FRENCH);
		fr.cards.add(ProjectExplorerPageTest.card("A", "A", "", "DRAFT", "CONSOLIDATION", "GREENFIELD", "", new String[0], 0));
		final String htmlFr = ProjectExplorerPage.render(fr);
		ProjectExplorerPageTest.check("French wording is used", htmlFr.indexOf("Nouveau projet") >= 0);
		ProjectExplorerPageTest.check("the French status label is used, correctly accented", htmlFr.indexOf("Brouillon") >= 0);
		ProjectExplorerPageTest.check("no stray backslash-u escape leaked into the rendered page", htmlFr.indexOf("\\u") < 0);
	}

	private static void emptyListRendersNoCardsButKeepsTheEmptyMessage()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("no card is rendered", html.indexOf("mdmc-card\"") < 0);
		ProjectExplorerPageTest.check("no group section is rendered", html.indexOf("mdmc-group") < 0);
		// the empty-state message is always present in the markup (FacetedPage's own convention: filtering,
		// including "there are zero cards at all", is entirely client-side, never a server-side branch)
		ProjectExplorerPageTest.check("the empty-state message is present", html.indexOf(Messages.get(ProjectExplorerPage.class, Locale.ENGLISH, "explorer.empty")) >= 0);
	}

	private static void disabledNewProjectButtonCarriesTheReasonAsATitle()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		model.canCreateProject = false; model.canDesign = true; // audit F-02
		model.createDisabledReason = "This instance is a delivery target: projects are designed on the development instance and arrive by EBX Staging.";
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("a disabled button, not a link", html.indexOf("<button type=\"button\" class=\"mdmx-newproject\" disabled") >= 0);
		ProjectExplorerPageTest.check("the refusal is carried as the title", html.indexOf(ProjectExplorerPageTest.esc(model.createDisabledReason)) >= 0);
		ProjectExplorerPageTest.check("no enabled link variant is also present", html.indexOf("<a class=\"mdmx-newproject\"") < 0);
	}

	/** Audit F-02: below DESIGN the explorer does not offer "New project" at all (the wizard would refuse SAVE_PROJECT). */
	private static void newProjectNotOfferedWithoutDesign()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		model.canCreateProject = true;
		model.canDesign = false;
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("no new-project link or button without DESIGN", html.indexOf("class=\"mdmx-newproject\"") < 0);
	}

	private static void enabledNewProjectButtonIsAPlainLink()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		model.canCreateProject = true; model.canDesign = true; // audit F-02
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("a plain link, not a disabled button", html.indexOf("<a class=\"mdmx-newproject\" href=\"") >= 0);
		ProjectExplorerPageTest.check("it opens the top window",
			html.indexOf("<a class=\"mdmx-newproject\" href=\"" + ProjectExplorerPageTest.esc(FactoryLinks.newProjectUrl(model.dataspaceKey, model.datasetName))
				+ "\" target=\"_top\">") >= 0);
		ProjectExplorerPageTest.check("no disabled button variant is also present", html.indexOf("class=\"mdmx-newproject\" disabled") < 0);
	}

	/**
	 * Leave-guard lot (2026-09-23): the discreet "native table view" link is a full-page ({@code target="_top"})
	 * link that is NOT part of the return-to-explorer token scheme ({@code .mdmc-btn}/{@code .mdmx-newproject},
	 * armed by {@code originCaptureScript}) - it must still carry {@code mdmf-leave-link}, the class {@code
	 * ProjectExplorerUserService}'s own {@code voluntaryLeaveScript} call arms, so it never triggers EBX's own
	 * "Quitter le site Web ?" prompt either.
	 */
	private static void advancedViewLinkCarriesTheVoluntaryLeaveMarkerClass()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("the advanced-view link keeps its own class alongside the new one, and opens the top window",
			html.contains("<a class=\"mdmc-advanced mdmf-leave-link\" href=\""
				+ ProjectExplorerPageTest.esc(FactoryLinks.advancedViewUrl(model.dataspaceKey, model.datasetName)) + "\" target=\"_top\">"));
	}

	private static void multiValuedSourceAttributeListsEverySystem()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		model.cards.add(ProjectExplorerPageTest.card("X", "X", "", "DRAFT", "CONSOLIDATION", "GREENFIELD", "", new String[] { "ERP", "CRM" }, 0));
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("both source systems are space-separated in one data-source attribute", html.indexOf("data-source=\"ERP CRM\"") >= 0);
		ProjectExplorerPageTest.check("the sources line names both systems", html.indexOf("(ERP, CRM)") >= 0);
	}

	private static void zonesRenderPresentAndAbsent()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		final ProjectExplorerPage.Card card = ProjectExplorerPageTest.card("X", "X", "", "DRAFT", "CONSOLIDATION", "GREENFIELD", "", new String[0], 0);
		card.hasLanding = true;
		card.hasStaging = false;
		card.hasMaster = false;
		model.cards.add(card);
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("a present zone gets the real/good chip", html.indexOf("mdmf-chip-real") >= 0);
		ProjectExplorerPageTest.check("an absent zone gets the dashed target chip", html.indexOf("mdmf-chip-target") >= 0);
		ProjectExplorerPageTest.check("only landing is offered as an advanced zone filter (the only zone any card has)", html.indexOf("data-value=\"landing\"") >= 0);
		ProjectExplorerPageTest.check("staging is not offered - no card has it", html.indexOf("data-value=\"staging\"") < 0);
	}

	private static void scriptNamesTheMultiValuedAttributes()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		final String script = ProjectExplorerPage.script(model);
		ProjectExplorerPageTest.check("source is declared multi-valued", script.indexOf("MULTI={source:1,zone:1}") >= 0);
		ProjectExplorerPageTest.check("the script binds the explorer's own root class", script.indexOf(".mdmc-explorer") >= 0);
		ProjectExplorerPageTest.check("no EBX-flavoured identifier leaked in (a plain sanity check the script is pure JS text)", script.indexOf("Adaptation") < 0);
	}

	/** 2026-09-23: the exact cockpit (predicate) and wizard (base64 serviceParams) URLs for a code {@link FactoryLinks} must never leave raw-encoded. */
	private static void cardLinksEncodeACodeCarryingAQuoteAndASpace()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		model.cards.add(ProjectExplorerPageTest.card("It's 2 [x]", "It's 2 [x]", "", "DRAFT", "CONSOLIDATION", "GREENFIELD", "", new String[0], 0));
		final String html = ProjectExplorerPage.render(model);
		final String expectedCockpit = FactoryLinks.cockpitUrl(model.dataspaceKey, model.datasetName, "It's 2 [x]").replace("&", "&amp;");
		final String expectedWizard = FactoryLinks.wizardUrl(model.dataspaceKey, model.datasetName, "It's 2 [x]").replace("&", "&amp;");
		ProjectExplorerPageTest.check("the exact cockpit predicate URL is rendered",
			expectedCockpit.equals("/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project?predicate=./code%3D%27It%27%27s%202%20%5Bx%5D%27&amp;service=FactoryCockpit&amp;serviceMode=RECORD")
				&& html.indexOf(expectedCockpit) >= 0);
		ProjectExplorerPageTest.check("the exact wizard serviceParams URL is rendered", html.indexOf(expectedWizard) >= 0);
		// the label itself is plain escaped HTML text (brackets need no HTML escaping, only the apostrophe does)
		// - the point of this case is the two href attributes above, never left with a raw quote breaking them
		ProjectExplorerPageTest.check("the label's apostrophe is HTML-escaped in the page text", html.indexOf("It&#39;s 2 [x]") >= 0);
	}

	/**
	 * 2026-09-23, favorites ported from ebx-connectors' own connector explorer: the star's on/off markup and
	 * {@code data-fav} attribute, and that a key carrying a quote never breaks the {@code data-key} attribute.
	 */
	private static void favoriteStarRendersOnAndOffAndEscapesTheKey()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		final ProjectExplorerPage.Card pinned = ProjectExplorerPageTest.card("Companies", "Companies", "", "LIVE", "CONSOLIDATION", "TARGET_EXISTS", "", new String[0], 0);
		pinned.key = "It's Companies";
		pinned.favorite = true;
		model.cards.add(pinned);
		final ProjectExplorerPage.Card notPinned = ProjectExplorerPageTest.card("Other", "Other", "", "LIVE", "CONSOLIDATION", "TARGET_EXISTS", "", new String[0], 0);
		notPinned.key = "Other";
		model.cards.add(notPinned);
		final String html = ProjectExplorerPage.render(model);

		ProjectExplorerPageTest.check("a pinned project's card is marked data-fav=\"1\"", html.indexOf("data-fav=\"1\"") >= 0);
		ProjectExplorerPageTest.check("an un-pinned project's card is marked data-fav=\"0\"", html.indexOf("data-fav=\"0\"") >= 0);
		ProjectExplorerPageTest.check("the pinned card's star carries the \"on\" class", html.indexOf("class=\"mdmx-fav on\"") >= 0);
		ProjectExplorerPageTest.check("the pinned card's star is aria-pressed=\"true\"",
			html.indexOf("class=\"mdmx-fav on\" data-type=\"PROJECT\" data-key=\"It&#39;s Companies\" aria-pressed=\"true\"") >= 0);
		ProjectExplorerPageTest.check("the un-pinned card's star has no \"on\" class and is aria-pressed=\"false\"",
			html.indexOf("class=\"mdmx-fav\" data-type=\"PROJECT\" data-key=\"Other\" aria-pressed=\"false\"") >= 0);
		ProjectExplorerPageTest.check("a quote in the key never breaks the data-key attribute", html.indexOf("data-key=\"It's Companies\"") < 0);
		ProjectExplorerPageTest.check("every star names the one favorite kind, PROJECT", html.indexOf("data-type=\"PROJECT\"") >= 0);
	}

	/** The "Favorites" facet is first in the rail and offered even when nobody has pinned anything yet. */
	private static void favoritesFacetIsFirstAndAlwaysOffered()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		model.cards.add(ProjectExplorerPageTest.card("X", "X", "", "DRAFT", "CONSOLIDATION", "GREENFIELD", "", new String[0], 0));
		final String html = ProjectExplorerPage.render(model);
		final int favFacet = html.indexOf("data-filter=\"fav\"");
		final int statusFacet = html.indexOf("data-filter=\"status\"");
		ProjectExplorerPageTest.check("the favorites facet exists", favFacet >= 0);
		ProjectExplorerPageTest.check("the favorites facet is offered before the status facet", favFacet < statusFacet);
		ProjectExplorerPageTest.check("the single favorites value is offered even with zero favorites pinned",
			html.indexOf("data-value=\"1\" data-label=\"" + Messages.get(ProjectExplorerPage.class, Locale.ENGLISH, "explorer.favorites.mine") + "\"") >= 0);
	}

	/** The favorite star's AJAX endpoint URL is rendered as a hidden span, escaped, whatever the caller resolved it to. */
	private static void favoriteEndpointIsRenderedAsAHiddenSpan()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		model.favoriteEndpointUrl = "/ebx-ui/ajax?a=1&b=2";
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("the endpoint span is present, hidden, with the escaped URL",
			html.indexOf("<span id=\"mdmxFavEndpoint\" hidden data-url=\"/ebx-ui/ajax?a=1&amp;b=2\"></span>") >= 0);
	}

	/** project-deletion lot (2026-09-23): {@code canDelete} defaults false in the shared fixture - already exercised by {@link #oneCardRendersItsFields}; this test only makes the absence explicit and self-contained. */
	private static void deleteButtonAbsentForNonAdmin()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		model.canDelete = false;
		model.cards.add(ProjectExplorerPageTest.card("Companies", "Companies 360", "", "LIVE", "CONSOLIDATION", "TARGET_EXISTS", "", new String[0], 0));
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("no delete button", html.indexOf("mdmc-btn-danger") < 0);
		ProjectExplorerPageTest.check("the delete URL is not present either",
			html.indexOf(FactoryLinks.deleteUrl(model.dataspaceKey, model.datasetName, "Companies").replace("&", "&amp;")) < 0);
	}

	private static void deleteButtonPresentForAdmin()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		model.canDelete = true;
		model.cards.add(ProjectExplorerPageTest.card("Companies", "Companies 360", "", "LIVE", "CONSOLIDATION", "TARGET_EXISTS", "", new String[0], 0));
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("the delete button is present, danger-styled", html.indexOf("mdmc-btn-danger") >= 0);
		ProjectExplorerPageTest.check("the delete link carries the project's own code",
			html.indexOf(FactoryLinks.deleteUrl(model.dataspaceKey, model.datasetName, "Companies").replace("&", "&amp;")) >= 0);
		ProjectExplorerPageTest.check("one more action link opens the top window than without the delete button",
			ProjectExplorerPageTest.countOccurrences(html, "target=\"_top\"") >= 4);
	}

	// ------------------------------------------------------------------------------------------------------- fixtures

	/** Server-paged explorer (2026-10-11): the page holds the chrome, the facet vocabularies and empty groups; the cards come from the "query" request. */
	private static void serverPagedPageCarriesNoCard()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.ENGLISH);
		model.queryEndpointUrl = "/ebx-ui/ajax?q=1&amp=2";
		model.cards.add(ProjectExplorerPageTest.card("Companies", "Companies 360", "d", "LIVE", "CONSOLIDATION", "TARGET_EXISTS", "Alice", new String[] { "ERP" }, 3));
		model.cards.add(ProjectExplorerPageTest.card("Products", "Products", "d", "DRAFT", "REGISTRY", "GREENFIELD", "Bob", new String[] { "CRM" }, 0));
		final String html = ProjectExplorerPage.render(model);
		ProjectExplorerPageTest.check("no card is written into the page", html.indexOf("<article") < 0);
		ProjectExplorerPageTest.check("the query endpoint is on the root, escaped", html.indexOf("class=\"mdmf mdmc-explorer mdmx\" data-query=\"/ebx-ui/ajax?q=1&amp;amp=2\"") >= 0);
		ProjectExplorerPageTest.check("the facet vocabularies still come from every project", html.indexOf("data-value=\"Alice\"") >= 0 && html.indexOf("data-value=\"Bob\"") >= 0
			&& html.indexOf("data-value=\"ERP\"") >= 0 && html.indexOf("data-value=\"REGISTRY\"") >= 0);
		ProjectExplorerPageTest.check("one hidden group per status, plus the hidden-status group", countOccurrences(html, "class=\"mdmc-group\"") == ProjectExplorerQuery.GROUP_ORDER.length
			&& countOccurrences(html, "\" hidden data-n=\"0\"") == ProjectExplorerQuery.GROUP_ORDER.length);
		ProjectExplorerPageTest.check("the empty-state starts hidden", html.indexOf("id=\"mdmcEmpty\" class=\"mdmc-empty\" style=\"display:none\"") >= 0);
		final String script = ProjectExplorerPage.script(model);
		ProjectExplorerPageTest.check("the server script reads data-query", script.indexOf("root.dataset.query") >= 0 && script.indexOf("MULTI=") < 0);
		ProjectExplorerPageTest.check("the failure notice is localized into the script",
			script.indexOf(FactoryHtml.jsStringBody(Messages.get(ProjectExplorerPage.class, Locale.ENGLISH, "explorer.queryFailed"))) >= 0);
		final ProjectExplorerPage.Model old = ProjectExplorerPageTest.model(Locale.ENGLISH);
		old.cards.addAll(model.cards);
		ProjectExplorerPageTest.check("without an endpoint, the cards are still written (the browser-side page)", ProjectExplorerPage.render(old).indexOf("<article") >= 0
			&& ProjectExplorerPage.script(old).indexOf("MULTI={source:1,zone:1}") >= 0);
	}

	private static void queryAnswerCarriesPagesCountsAndFavorites()
	{
		final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(Locale.FRENCH);
		final java.util.List<ProjectExplorerPage.Card> cards = new java.util.ArrayList<ProjectExplorerPage.Card>();
		final ProjectExplorerPage.Card one = ProjectExplorerPageTest.card("A\"1", "Quote \" and </script>", "line\nbreak", "LIVE", "CONSOLIDATION", "TARGET_EXISTS", "Alice", new String[] { "ERP" }, 1);
		one.key = "A\"1";
		one.sortName = "quote";
		final ProjectExplorerPage.Card two = ProjectExplorerPageTest.card("B", "Beta", "", "DRAFT", "REGISTRY", "GREENFIELD", "Bob", new String[0], 0);
		two.key = "B";
		two.sortName = "beta";
		cards.add(one);
		cards.add(two);
		final java.util.List<String> keys = new java.util.ArrayList<String>();
		for (final ProjectExplorerPage.Card card : cards)
		{
			keys.add(ProjectExplorerQuery.searchKey(card));
		}
		final java.util.Set<String> favorites = java.util.Collections.singleton("A\"1");
		final ProjectExplorerQuery.Result result = ProjectExplorerQuery.run(cards, keys, favorites, "", ProjectExplorerQuery.parse(null), "name", 0, null, 0, 12);
		final String json = ProjectExplorerPage.queryAnswer(model, result, favorites);
		ProjectExplorerPageTest.check("counts first: " + json.substring(0, 40), json.startsWith("{\"all\":2,\"total\":2,\"facets\":{"));
		ProjectExplorerPageTest.check("a facet count", json.indexOf("\"owner\":{") >= 0 && json.indexOf("\"Alice\":1") >= 0);
		ProjectExplorerPageTest.check("every group listed with its count", json.indexOf("{\"g\":\"DRAFT\",\"n\":1,\"html\":\"<article") >= 0 && json.indexOf("{\"g\":\"STAGING\",\"n\":0,\"html\":\"\"}") >= 0);
		ProjectExplorerPageTest.check("no raw newline or unescaped quote in the JSON", json.indexOf('\n') < 0 && json.indexOf("\\u000a") >= 0);
		ProjectExplorerPageTest.check("the session's favorite is on its own card", json.indexOf("class=\\\"mdmx-fav on\\\"") >= 0 && json.indexOf("class=\\\"mdmx-fav\\\"") >= 0);
		ProjectExplorerPageTest.check("the shared cards are not touched", !one.favorite && !two.favorite);
		final ProjectExplorerPage.Model full = ProjectExplorerPageTest.model(Locale.FRENCH);
		final ProjectExplorerPage.Card pinned = one.copy();
		pinned.favorite = true;
		full.cards.add(pinned);
		ProjectExplorerPageTest.check("a card of the answer is the card the page used to write",
			ProjectExplorerPage.render(full).indexOf(ProjectExplorerPage.cardsHtml(model, java.util.Collections.singletonList(pinned))) >= 0);
	}

	/** The server-paged scripts, for tools/jobnotice_test.js (parsed by JavaScriptCore on a Mac). */
	private static void dumpServerScriptsForTheParser()
	{
		final String dir = System.getProperty("mdmf.jsDump");
		if (dir == null || dir.isEmpty())
		{
			return;
		}
		try
		{
			java.nio.file.Files.createDirectories(java.nio.file.Paths.get(dir));
			for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
			{
				final ProjectExplorerPage.Model model = ProjectExplorerPageTest.model(locale);
				model.queryEndpointUrl = "/q";
				final String js = ProjectExplorerPage.script(model) + "\n(function(){var root=document.querySelector('.mdmc-explorer'),F=root.mdmxFacets;" + FavoriteStars.DELEGATED_JS + "})();\n"
					+ FactoryHtml.originCaptureDelegatedScript("alice", ".mdmc-explorer", ".mdmc-card .mdmc-btn");
				java.nio.file.Files.write(java.nio.file.Paths.get(dir, "explorer-" + locale.getLanguage() + ".js"), js.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
		}
		catch (final java.io.IOException failure)
		{
			throw new IllegalStateException(failure);
		}
	}

	private static ProjectExplorerPage.Model model(final Locale pLocale)
	{
		final ProjectExplorerPage.Model model = new ProjectExplorerPage.Model();
		model.locale = pLocale;
		model.canCreateProject = true; model.canDesign = true; // audit F-02
		model.dataspaceKey = "BMdmFactory";
		model.datasetName = "MdmFactory";
		return model;
	}

	private static ProjectExplorerPage.Card card(final String pCode, final String pLabel, final String pDescription, final String pStatus, final String pStyle,
		final String pMode, final String pOwner, final String[] pSourceSystems, final int pStagingEntityCount)
	{
		final ProjectExplorerPage.Card card = new ProjectExplorerPage.Card();
		card.code = pCode;
		card.label = pLabel;
		card.description = pDescription;
		card.status = pStatus;
		card.style = pStyle;
		card.mode = pMode;
		card.owner = pOwner;
		card.createdAt = ProjectExplorerPageTest.NOW;
		card.sourceCount = pSourceSystems.length;
		for (final String system : pSourceSystems)
		{
			card.sourceSystems.add(system);
		}
		card.stagingEntityCount = pStagingEntityCount;
		return card;
	}

	private static String esc(final String pText)
	{
		return FactoryHtml.esc(pText);
	}

	private static int countOccurrences(final String pHaystack, final String pNeedle)
	{
		int count = 0;
		int from = 0;
		while (true)
		{
			final int at = pHaystack.indexOf(pNeedle, from);
			if (at < 0)
			{
				return count;
			}
			count++;
			from = at + pNeedle.length();
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
