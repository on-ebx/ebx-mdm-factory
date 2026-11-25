package com.on.ebx.factory.ebx.ui;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.ebx.Favorites;

/**
 * The HTML of the "Project explorer" screen (PLAN.md screen 6), from a plain {@link Model} only (no EBX
 * object) - like {@link CockpitPage}, renders from fixtures in a JUnit-free test, including escaping, the
 * empty state, the disabled "New project" button and both locales. The faceted chrome (search box, sort,
 * facet rail, active-filter chips, card groups) is {@link FacetedPage}, copied and made pure for this module
 * (see its own Javadoc); this class only ever supplies the data (facet vocabularies computed from
 * {@link Model#cards}, one {@code <article>} per project) and the client-side script that ties them together.
 * <p>
 * Every link (Wizard, Cockpit, New project, the advanced-view link) is built here through the small, equally
 * pure {@link FactoryLinks} - {@code Model} only ever carries the plain {@link Model#dataspaceKey}/
 * {@link Model#datasetName} strings the caller reads off the dataset's REAL home key/name (an EBX object,
 * CLAUDE.md's own warning against {@code AdaptationName.toString()}), never a pre-built URL: keeping the page
 * itself the one place that decides the URL shape means a future link-form change never has to touch the
 * caller too.
 */
final class ProjectExplorerPage
{
	private ProjectExplorerPage()
	{
	}

	/** Group order (task brief: "in pipeline order DRAFT to LIVE to ARCHIVED", nothing pinned) - also the only legal values of {@code Project.status} (an EBX enumeration), so every card belongs to exactly one of these groups. */
	static final String[] STATUS_ORDER = { "DRAFT", "LANDING", "STAGING", "LIVE", "ARCHIVED" };
	/** {@code Project.style}'s declared enumeration order (tools/build_xsd.py). */
	private static final String[] STYLE_ORDER = { "CONSOLIDATION", "CENTRALISATION", "COEXISTENCE", "REGISTRY" };
	/** {@code Project.mode}'s declared enumeration order (tools/build_xsd.py; {@code TARGET_EXISTS} is the schema default). */
	private static final String[] MODE_ORDER = { "TARGET_EXISTS", "GREENFIELD" };

	/** One project, exactly as the explorer shows and filters it - built by {@code ProjectExplorerUserService}, read only by {@link #render}. */
	static final class Card
	{
		String code = "";
		/** {@code Project}'s occurrence primary key, formatted ({@code getOccurrencePrimaryKey().format()}) - the favorite's own target key, kept distinct from {@link #code} the same way ebx-connectors' own {@code Card.key} is (moot here since {@code Project}'s key is the single field {@code code}, but the same call shape as every other primary-key read in this module - CLAUDE.md's own warning against building a key from a raw field). */
		String key = "";
		/** Whether the current session's login has this project pinned ({@code ebx.Favorites.keys}, read by {@code ProjectExplorerUserService}) - 2026-09-23, ported from ebx-connectors' own connector explorer favorites. */
		boolean favorite;
		String label = "";
		String description = "";
		/** One of {@link #STATUS_ORDER}. */
		String status = "";
		/** One of {@link #STYLE_ORDER}. */
		String style = "";
		/** One of {@link #MODE_ORDER}. */
		String mode = "";
		String owner = "";
		/** Null: {@code Project.createdAt} was never set (a row written before the field existed). */
		Date createdAt;
		int sourceCount;
		/** Distinct {@code ProjectSource.sourceSystem} codes of this project, in the order read - the multi-valued "Source system" advanced filter. */
		final List<String> sourceSystems = new ArrayList<String>();
		int stagingEntityCount;
		boolean hasLanding;
		boolean hasStaging;
		boolean hasMaster;
		/** Null: no {@code PipelineStep} row was ever read for this project - the activity line then says so instead of showing nothing. */
		String lastActivityStep;
		Date lastActivityAt;
		/** What the server-paged sort compares for "name" (the old {@code data-name}: the label in lower case) - set by {@code ProjectIndex}. */
		String sortName = "";
		/**
		 * 2026-10-14 (EBX permissions): the {@code ProjectView.F_*} values hidden for the current user on this card (field hidden for the dataset or for this
		 * record, or a value read from a table or field the user cannot see). Their values are already blanked ({@code ProjectView.mask}); the page also
		 * drops their line, so a "No source" never stands for "you may not see the sources". Empty on the shared cards of the index.
		 */
		final java.util.Set<String> hidden = new java.util.HashSet<String>();
		/** 2026-10-14: the user may run the Cockpit service on this record ({@code SessionPermissions.getNodeActionPermissionForService}). */
		boolean canCockpit = true;

		/** A copy (source systems included) - the shared cards of {@code ProjectIndex} are never modified, a session's favorite is set on a copy. */
		Card copy()
		{
			final Card out = new Card();
			out.code = this.code;
			out.key = this.key;
			out.favorite = this.favorite;
			out.label = this.label;
			out.description = this.description;
			out.status = this.status;
			out.style = this.style;
			out.mode = this.mode;
			out.owner = this.owner;
			out.createdAt = this.createdAt;
			out.sourceCount = this.sourceCount;
			out.sourceSystems.addAll(this.sourceSystems);
			out.stagingEntityCount = this.stagingEntityCount;
			out.hasLanding = this.hasLanding;
			out.hasStaging = this.hasStaging;
			out.hasMaster = this.hasMaster;
			out.lastActivityStep = this.lastActivityStep;
			out.lastActivityAt = this.lastActivityAt;
			out.sortName = this.sortName;
			out.hidden.addAll(this.hidden);
			out.canCockpit = this.canCockpit;
			return out;
		}
	}

	static final class Model
	{
		Locale locale = Locale.ENGLISH;
		final List<Card> cards = new ArrayList<Card>();
		/** {@code TargetRule.projectCreationAllowed(...)}, resolved by the caller (a live EBX read) - the pure page only renders the result. */
		boolean canCreateProject = true;
		/** {@code TargetRule.refusal()} when {@link #canCreateProject} is false - plain English, never localized, the same convention {@code ProjectWizardUserService#writeBlocked} already follows for the very same sentence. */
		String createDisabledReason = "";
		/** The dataset's real home key ({@code dataset.getHome().getKey().format()}, e.g. {@code "BMdmFactory"}) - read by the caller, since only an EBX object can resolve it; every link is then built here, through {@link FactoryLinks}. */
		String dataspaceKey = "";
		/** The dataset's real name ({@code dataset.getAdaptationName().getStringName()} - never {@code toString()}, CLAUDE.md's own warning). */
		String datasetName = "";
		/** {@code UserServicePaneWriter.getURLForAjaxRequest(...)} of the favorite-toggle endpoint, resolved by the caller (only a live {@code UserServicePaneWriter} can build it) - the pure page only renders it into the hidden {@code #mdmxFavEndpoint} span {@link FavoriteStars#JS} reads. 2026-09-23, ported from ebx-connectors' own connector explorer favorites. */
		String favoriteEndpointUrl = "";
		/** {@code session.isUserInRole(Profile.ADMINISTRATOR)}, resolved by the caller - project-deletion lot (2026-09-23): a card's "Delete" button is only ever offered to an administrator, the same restriction {@code ProjectDeleteServiceDeclaration} itself enforces server-side. */
		boolean canDelete;
		/** Audit F-02: the session holds the MDM Factory right DESIGN - "New project" is only offered then (the wizard refuses SAVE_PROJECT otherwise). */
		boolean canDesign;
		/**
		 * Server-paged explorer (2026-10-11): {@code getURLForAjaxRequest(...)} of the "query" request. When set, {@link #cards} only feeds the facet
		 * vocabularies: the page is written with empty status groups and {@link #script} asks the server for each page of cards and for the counts
		 * ({@link ProjectExplorerQuery}). Empty: every card is written into the page and filtered in the browser, as before.
		 */
		String queryEndpointUrl = "";
		/** Wave 13 (security): the EBX login, written in {@code data-user} of the root: the page's browser storage is keyed by it ({@code userstore.js}). */
		String user = "";
		/** 2026-10-14 (EBX permissions): the user may run the wizard service on the Project table ({@code getTableActionPermissionForService}). */
		boolean canWizard = true;
		/** 2026-10-14: the {@code ProjectView.F_*} values hidden for the whole dataset: the sorts reading them are not offered. */
		final java.util.Set<String> hidden = new java.util.HashSet<String>();
	}

	// --------------------------------------------------------------------------------------------------------- page

	static String render(final Model pModel)
	{
		final FacetedPage page = new FacetedPage(pModel.locale);
		final StringBuilder out = new StringBuilder(4096);
		// the favorite star's AJAX endpoint (FavoriteStars.JS reads its data-url) - same placement as
		// ebx-connectors' own ConnectorCatalogUserService, ported here since the endpoint URL is a plain string
		// the pure page can render without crossing any EBX type into it
		out.append("<span id=\"mdmxFavEndpoint\" hidden data-url=\"").append(ProjectExplorerPage.esc(pModel.favoriteEndpointUrl)).append("\"></span>");
		out.append("<div class=\"mdmf mdmc-explorer mdmx\"").append(FactoryHtml.userAttr(pModel.user));
		if (ProjectExplorerPage.serverPaged(pModel))
		{
			out.append(" data-query=\"").append(ProjectExplorerPage.esc(pModel.queryEndpointUrl)).append("\"");
		}
		out.append(">");
		ProjectExplorerPage.tools(out, page, pModel);
		page.openRail(out, ProjectExplorerPage.linksHtml(pModel));
		ProjectExplorerPage.facets(out, page, pModel);
		page.openMain(out, ProjectExplorerPage.m(pModel, "explorer.empty"), ProjectExplorerPage.serverPaged(pModel));
		if (ProjectExplorerPage.serverPaged(pModel))
		{
			ProjectExplorerPage.emptyGroups(out, page, pModel);
		}
		else
		{
			ProjectExplorerPage.groups(out, page, pModel);
		}
		page.closeMain(out);
		out.append("</div>");
		return out.toString();
	}

	/**
	 * The client-side script - kept separate from {@link #render}, never embedded as a hand-written
	 * {@code <script>} tag (CLAUDE.md &sect;9): the caller adds it with {@code UserServicePaneWriter.addJS_cr}
	 * the same way every other screen of this module does. Still pure (a plain string, no EBX type), so a
	 * fixture-driven test can check it names the right root/ids/multi-valued attributes.
	 */
	static String script(final Model pModel)
	{
		if (ProjectExplorerPage.serverPaged(pModel))
		{
			return new FacetedPage(pModel.locale).serverScript(".mdmc-explorer", "mdmxSearch", "mdmxSort", ProjectExplorerPage.m(pModel, "explorer.queryFailed"));
		}
		return new FacetedPage(pModel.locale).script(".mdmc-explorer", "mdmxSearch", "mdmxSort", "source", "zone");
	}

	static boolean serverPaged(final Model pModel)
	{
		return pModel.queryEndpointUrl != null && !pModel.queryEndpointUrl.isEmpty();
	}

	/** The cards of one page of a "query" answer, exactly as {@link #render} writes them into a group (each card carries its own {@link Card#favorite}). */
	static String cardsHtml(final Model pModel, final List<Card> pCards)
	{
		final StringBuilder out = new StringBuilder(pCards.size() * 1200 + 16);
		for (final Card card : pCards)
		{
			ProjectExplorerPage.card(out, pModel, card);
		}
		return out.toString();
	}

	private static void tools(final StringBuilder pOut, final FacetedPage pPage, final Model pModel)
	{
		pOut.append("<div class=\"mdmc-tools\"><div class=\"mdmc-search-row\">");
		pPage.railToggle(pOut);
		pOut.append("<label class=\"mdmc-search\"><span>&#128269;</span><input id=\"mdmxSearch\" type=\"search\" placeholder=\"")
			.append(ProjectExplorerPage.esc(ProjectExplorerPage.m(pModel, "explorer.search"))).append("\"/></label>");
		final Map<String, String> sorts = new LinkedHashMap<String, String>();
		sorts.put("name", ProjectExplorerPage.m(pModel, "explorer.sort.name"));
		if (!pModel.hidden.contains(ProjectView.F_CREATED))
		{
			sorts.put("created", ProjectExplorerPage.m(pModel, "explorer.sort.created"));
		}
		if (!pModel.hidden.contains(ProjectView.F_SOURCES))
		{
			sorts.put("sources", ProjectExplorerPage.m(pModel, "explorer.sort.sources"));
		}
		pPage.sortControl(pOut, "mdmxSort", sorts);
		pPage.advancedToggle(pOut);
		ProjectExplorerPage.newProjectButton(pOut, pModel);
		pOut.append("</div>");
		pPage.openAdvanced(pOut);
		pPage.pillRow(pOut, "source", ProjectExplorerPage.m(pModel, "explorer.filter.sourceSystem"), ProjectExplorerPage.sourceSystemValues(pModel));
		pPage.pillRow(pOut, "zone", ProjectExplorerPage.m(pModel, "explorer.filter.zones"), ProjectExplorerPage.zoneValues(pModel));
	}

	/** A prominent link at the top right of the search row - disabled (a plain button, the refusal as its title) on a TARGET instance (task brief; same gate {@code ProjectWizardUserService} itself re-checks server-side). */
	private static void newProjectButton(final StringBuilder pOut, final Model pModel)
	{
		if (!pModel.canDesign)
		{
			return; // audit F-02: do not offer what the user cannot do
		}
		final String label = ProjectExplorerPage.esc(ProjectExplorerPage.m(pModel, "explorer.newProject"));
		if (pModel.canCreateProject)
		{
			pOut.append("<a class=\"mdmx-newproject\" href=\"")
				.append(ProjectExplorerPage.esc(FactoryLinks.newProjectUrl(pModel.dataspaceKey, pModel.datasetName))).append("\" target=\"_top\">").append(label)
				.append("</a>");
		}
		else
		{
			pOut.append("<button type=\"button\" class=\"mdmx-newproject\" disabled title=\"").append(ProjectExplorerPage.esc(pModel.createDisabledReason)).append("\">")
				.append(label).append("</button>");
		}
	}

	/** The active-filter bar's links area: a discreet link to the native table view (task brief); the perspective rewrite runs client-side, see {@code ProjectExplorerUserService}'s own script. */
	private static String linksHtml(final Model pModel)
	{
		return "<a class=\"mdmc-advanced mdmf-leave-link\" href=\"" + ProjectExplorerPage.esc(FactoryLinks.advancedViewUrl(pModel.dataspaceKey, pModel.datasetName)) + "\" target=\"_top\">"
			+ ProjectExplorerPage.esc(ProjectExplorerPage.m(pModel, "explorer.advancedView")) + "</a>";
	}

	private static void facets(final StringBuilder pOut, final FacetedPage pPage, final Model pModel)
	{
		// first in the rail (task brief) - a single checkbox value, exactly like ebx-connectors' own connector
		// explorer "My favorites" facet (ConnectorCatalogUserService#writeCatalog): always offered, never
		// filtered by "does any card already have one" the way the other facets below are, since the option
		// itself is always actionable even with zero favorites pinned yet.
		final Map<String, String> favoriteValues = new LinkedHashMap<String, String>();
		favoriteValues.put("1", ProjectExplorerPage.m(pModel, "explorer.favorites.mine"));
		pPage.facet(pOut, "fav", ProjectExplorerPage.m(pModel, "explorer.filter.favorites"), favoriteValues, 1);
		pPage.facet(pOut, "status", ProjectExplorerPage.m(pModel, "explorer.filter.status"), ProjectExplorerPage.statusValues(pModel), 8);
		pPage.facet(pOut, "style", ProjectExplorerPage.m(pModel, "explorer.filter.style"), ProjectExplorerPage.styleValues(pModel), 8);
		pPage.facet(pOut, "mode", ProjectExplorerPage.m(pModel, "explorer.filter.mode"), ProjectExplorerPage.modeValues(pModel), 8);
		pPage.facet(pOut, "owner", ProjectExplorerPage.m(pModel, "explorer.filter.owner"), ProjectExplorerPage.ownerValues(pModel), 8);
	}

	private static void groups(final StringBuilder pOut, final FacetedPage pPage, final Model pModel)
	{
		int order = 0;
		for (final String status : ProjectExplorerPage.STATUS_ORDER)
		{
			boolean opened = false;
			for (final Card card : pModel.cards)
			{
				if (!status.equals(card.status))
				{
					continue;
				}
				if (!opened)
				{
					pPage.openGroup(pOut, status, order++, false);
					pOut.append(ProjectExplorerPage.esc(ProjectExplorerPage.statusLabel(pModel, status)));
					pPage.closeGroupHeading(pOut, ProjectExplorerPage.m(pModel, "explorer.projectsWord"), null);
					opened = true;
				}
				ProjectExplorerPage.card(pOut, pModel, card);
			}
			if (opened)
			{
				pPage.closeGroup(pOut);
			}
		}
	}

	/**
	 * The answer of the "query" request (server-paged explorer, 2026-10-11): {@code {"all","total","facets":{filter:{value:count}},"groups":[{"g","n","html"}]}}
	 * - every status group with its whole count {@code n}, and the HTML of its requested page (empty for the groups a "show more" did not ask). Each card
	 * of a page is written as a copy carrying the session's favorite ({@code pFavorites}, primary keys).
	 */
	static String queryAnswer(final Model pModel, final ProjectExplorerQuery.Result pResult, final java.util.Set<String> pFavorites)
	{
		final StringBuilder out = new StringBuilder(8192);
		out.append("{\"all\":").append(pResult.all).append(",\"total\":").append(pResult.total).append(",\"facets\":{");
		boolean firstFacet = true;
		for (final Map.Entry<String, Map<String, Integer>> facet : pResult.facets.entrySet())
		{
			out.append(firstFacet ? "" : ",").append(ProjectExplorerPage.json(facet.getKey())).append(":{");
			firstFacet = false;
			boolean firstValue = true;
			for (final Map.Entry<String, Integer> value : facet.getValue().entrySet())
			{
				out.append(firstValue ? "" : ",").append(ProjectExplorerPage.json(value.getKey())).append(':').append(value.getValue());
				firstValue = false;
			}
			out.append('}');
		}
		out.append("},\"groups\":[");
		boolean firstGroup = true;
		for (final ProjectExplorerQuery.Group group : pResult.groups.values())
		{
			final List<Card> page = new ArrayList<Card>(group.page.size());
			for (final Card card : group.page)
			{
				final Card copy = card.copy();
				copy.favorite = pFavorites != null && pFavorites.contains(card.key);
				page.add(copy);
			}
			out.append(firstGroup ? "" : ",").append("{\"g\":").append(ProjectExplorerPage.json(group.status)).append(",\"n\":").append(group.count).append(",\"html\":")
				.append(ProjectExplorerPage.json(ProjectExplorerPage.cardsHtml(pModel, page))).append('}');
			firstGroup = false;
		}
		return out.append("]}").toString();
	}

	/** A JSON string literal: quote, backslash and every control character escaped, plus U+2028/U+2029. */
	static String json(final String pText)
	{
		final String text = pText == null ? "" : pText;
		final StringBuilder out = new StringBuilder(text.length() + 2).append('"');
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			if (c == '"' || c == '\\')
			{
				out.append('\\').append(c);
			}
			else if (c < 0x20 || c == '\u2028' || c == '\u2029')
			{
				out.append(String.format("\\u%04x", Integer.valueOf(c)));
			}
			else
			{
				out.append(c);
			}
		}
		return out.append('"').toString();
	}

	/** Server-paged: one empty group per status, in pipeline order, hidden until an answer gives it cards (a status nobody has yet stays hidden). */
	private static void emptyGroups(final StringBuilder pOut, final FacetedPage pPage, final Model pModel)
	{
		int order = 0;
		for (final String status : ProjectExplorerQuery.GROUP_ORDER)
		{
			pPage.openGroup(pOut, status, order++, false, true);
			pOut.append(ProjectExplorerPage.esc(ProjectExplorerPage.statusLabel(pModel, status)));
			pPage.closeGroupHeading(pOut, ProjectExplorerPage.m(pModel, "explorer.projectsWord"), null);
			pPage.closeGroup(pOut);
		}
	}

	// --------------------------------------------------------------------------------------------------------- card

	private static void card(final StringBuilder pOut, final Model pModel, final Card pCard)
	{
		final String search = (pCard.label + " " + pCard.description + " " + pCard.code + " " + pCard.owner + " " + ProjectExplorerPage.join(pCard.sourceSystems, " "))
			.toLowerCase(pModel.locale);
		pOut.append("<article class=\"mdmc-card\" data-status=\"").append(ProjectExplorerPage.esc(pCard.status)).append("\" data-style=\"")
			.append(ProjectExplorerPage.esc(pCard.style)).append("\" data-mode=\"").append(ProjectExplorerPage.esc(pCard.mode)).append("\" data-owner=\"")
			.append(ProjectExplorerPage.esc(pCard.owner)).append("\" data-source=\"").append(ProjectExplorerPage.esc(ProjectExplorerPage.join(pCard.sourceSystems, " ")))
			.append("\" data-zone=\"").append(ProjectExplorerPage.esc(ProjectExplorerPage.zoneAttr(pCard))).append("\" data-name=\"")
			.append(ProjectExplorerPage.esc(pCard.label.toLowerCase(pModel.locale))).append("\" data-created=\"")
			.append(pCard.createdAt == null ? "0" : String.valueOf(pCard.createdAt.getTime())).append("\" data-sources=\"").append(pCard.sourceCount)
			.append("\" data-fav=\"").append(pCard.favorite ? "1" : "0")
			.append("\" data-search=\"").append(ProjectExplorerPage.esc(search)).append("\">");
		pOut.append("<div class=\"mdmc-body\">");
		// the star is a plain flex sibling of the chip/code here, never an absolutely positioned overlay the way
		// ebx-connectors' own connector card needs one (that card is a single whole-card link; this one is not -
		// see FavoriteStars' own Javadoc) - and, being a <button> of its own class, it never arms
		// FactoryHtml#originCaptureScript's selector (".mdmc-btn, .mdmx-newproject") nor navigates anywhere.
		pOut.append("<div class=\"mdmc-meta\">");
		if (!pCard.hidden.contains(ProjectView.F_STATUS))
		{
			pOut.append(ProjectExplorerPage.chip(ProjectExplorerPage.statusKind(pCard.status), ProjectExplorerPage.statusLabel(pModel, pCard.status)));
		}
		if (!pCard.code.isEmpty())
		{
			pOut.append("<code>").append(ProjectExplorerPage.esc(pCard.code)).append("</code>");
		}
		pOut.append(FavoriteStars.button(pModel.locale, Favorites.PROJECT, pCard.key, pCard.favorite)).append("</div>");
		pOut.append("<h3>").append(ProjectExplorerPage.esc(ProjectExplorerPage.fallback(pCard.label, ProjectExplorerPage.fallback(pCard.code, "\u2026")))).append("</h3>");
		if (!pCard.description.isEmpty())
		{
			pOut.append("<p class=\"mdmc-desc\">").append(ProjectExplorerPage.esc(pCard.description)).append("</p>");
		}
		if (!pCard.hidden.contains(ProjectView.F_STYLE) || !pCard.hidden.contains(ProjectView.F_MODE))
		{
			pOut.append("<div class=\"mdmc-chips\">");
			if (!pCard.hidden.contains(ProjectView.F_STYLE))
			{
				pOut.append(ProjectExplorerPage.chip("accent", ProjectExplorerPage.styleLabel(pModel, pCard.style)));
			}
			if (!pCard.hidden.contains(ProjectView.F_MODE))
			{
				pOut.append(ProjectExplorerPage.chip("muted", ProjectExplorerPage.modeLabel(pModel, pCard.mode)));
			}
			pOut.append("</div>");
		}
		if (!pCard.owner.isEmpty())
		{
			pOut.append("<p class=\"mdmc-info\">").append(ProjectExplorerPage.e(pModel, "explorer.card.owner", pCard.owner)).append("</p>");
		}
		if (pCard.createdAt != null)
		{
			pOut.append("<p class=\"mdmc-info\">").append(ProjectExplorerPage.e(pModel, "explorer.card.created", ProjectExplorerPage.dateFormat(pModel).format(pCard.createdAt)))
				.append("</p>");
		}
		if (!pCard.hidden.contains(ProjectView.F_SOURCES))
		{
			pOut.append("<p class=\"mdmc-info\">").append(ProjectExplorerPage.sourcesLine(pModel, pCard)).append("</p>");
		}
		if (!pCard.hidden.contains(ProjectView.F_STAGING))
		{
			pOut.append("<p class=\"mdmc-info\">").append(ProjectExplorerPage.stagingLine(pModel, pCard)).append("</p>");
		}
		// a hidden dataspace field drops its chip: an "absent" chip would itself be a value
		final StringBuilder zones = new StringBuilder();
		if (!pCard.hidden.contains(ProjectView.F_LANDING))
		{
			zones.append(ProjectExplorerPage.zoneChip(pModel, "landing", pCard.hasLanding));
		}
		if (!pCard.hidden.contains(ProjectView.F_STAGING_DS))
		{
			zones.append(ProjectExplorerPage.zoneChip(pModel, "staging", pCard.hasStaging));
		}
		if (!pCard.hidden.contains(ProjectView.F_MASTER))
		{
			zones.append(ProjectExplorerPage.zoneChip(pModel, "master", pCard.hasMaster));
		}
		if (zones.length() > 0)
		{
			pOut.append("<div class=\"mdmc-zones\">").append(zones).append("</div>");
		}
		if (!pCard.hidden.contains(ProjectView.F_ACTIVITY))
		{
			pOut.append("<p class=\"mdmc-info\">").append(ProjectExplorerPage.activityLine(pModel, pCard)).append("</p>");
		}
		// the project's primary key (its code) travels in the links even when the code field is hidden: EBX's own record links carry the key too
		final String code = pCard.code.isEmpty() ? pCard.key : pCard.code;
		pOut.append("<div class=\"mdmc-actions\">");
		if (pModel.canWizard)
		{
			pOut.append("<a class=\"mdmc-btn\" href=\"").append(ProjectExplorerPage.esc(FactoryLinks.wizardUrl(pModel.dataspaceKey, pModel.datasetName, code)))
				.append("\" target=\"_top\">").append(ProjectExplorerPage.e(pModel, "explorer.card.wizard")).append("</a>");
		}
		if (pCard.canCockpit)
		{
			pOut.append("<a class=\"mdmc-btn\" href=\"").append(ProjectExplorerPage.esc(FactoryLinks.cockpitUrl(pModel.dataspaceKey, pModel.datasetName, code)))
				.append("\" target=\"_top\">").append(ProjectExplorerPage.e(pModel, "cockpit.label")).append("</a>");
		}
		if (pModel.canDelete)
		{
			pOut.append("<a class=\"mdmc-btn mdmc-btn-danger\" href=\"")
				.append(ProjectExplorerPage.esc(FactoryLinks.deleteUrl(pModel.dataspaceKey, pModel.datasetName, code))).append("\" target=\"_top\">")
				.append(ProjectExplorerPage.e(pModel, "explorer.card.delete")).append("</a>");
		}
		pOut.append("</div>");
		pOut.append("</div></article>");
	}

	private static String sourcesLine(final Model pModel, final Card pCard)
	{
		if (pCard.sourceCount == 0)
		{
			return ProjectExplorerPage.e(pModel, "explorer.card.sources.none");
		}
		final String base = ProjectExplorerPage.e(pModel, pCard.sourceCount == 1 ? "explorer.card.source" : "explorer.card.sources", String.valueOf(pCard.sourceCount));
		if (pCard.sourceSystems.isEmpty())
		{
			return base;
		}
		return base + " (" + ProjectExplorerPage.esc(ProjectExplorerPage.join(pCard.sourceSystems, ", ")) + ")";
	}

	private static String stagingLine(final Model pModel, final Card pCard)
	{
		if (pCard.stagingEntityCount == 0)
		{
			return ProjectExplorerPage.e(pModel, "explorer.card.stagingEntities.none");
		}
		return ProjectExplorerPage.e(pModel, pCard.stagingEntityCount == 1 ? "explorer.card.stagingEntity" : "explorer.card.stagingEntities",
			String.valueOf(pCard.stagingEntityCount));
	}

	private static String activityLine(final Model pModel, final Card pCard)
	{
		if (pCard.lastActivityStep == null)
		{
			return ProjectExplorerPage.e(pModel, "explorer.card.noActivity");
		}
		final String stepLabel = StepLabels.of(pModel.locale, pCard.lastActivityStep);
		final String when = pCard.lastActivityAt == null ? "?" : ProjectExplorerPage.dateFormat(pModel).format(pCard.lastActivityAt);
		return ProjectExplorerPage.e(pModel, "explorer.card.activity", stepLabel, when);
	}

	private static String zoneChip(final Model pModel, final String pZone, final boolean pPresent)
	{
		return ProjectExplorerPage.chip(pPresent ? "real" : "target", ProjectExplorerPage.zoneLabel(pModel, pZone));
	}

	private static String zoneAttr(final Card pCard)
	{
		final StringBuilder sb = new StringBuilder();
		if (pCard.hasLanding)
		{
			sb.append("landing");
		}
		if (pCard.hasStaging)
		{
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append("staging");
		}
		if (pCard.hasMaster)
		{
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append("master");
		}
		return sb.toString();
	}

	// -------------------------------------------------------------------------------------------------------- facets

	/** Only a value at least one card actually carries is offered - same convention as ebx-connectors' own category/status/connection facets (a value nobody has yet only clutters the rail; the script dims a facet value to zero live once other filters are applied, which is a different, already-handled case). */
	private static Map<String, String> statusValues(final Model pModel)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (final String status : ProjectExplorerPage.STATUS_ORDER)
		{
			for (final Card card : pModel.cards)
			{
				if (status.equals(card.status))
				{
					out.put(status, ProjectExplorerPage.statusLabel(pModel, status));
					break;
				}
			}
		}
		return out;
	}

	private static Map<String, String> styleValues(final Model pModel)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (final String style : ProjectExplorerPage.STYLE_ORDER)
		{
			for (final Card card : pModel.cards)
			{
				if (style.equals(card.style))
				{
					out.put(style, ProjectExplorerPage.styleLabel(pModel, style));
					break;
				}
			}
		}
		return out;
	}

	private static Map<String, String> modeValues(final Model pModel)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (final String mode : ProjectExplorerPage.MODE_ORDER)
		{
			for (final Card card : pModel.cards)
			{
				if (mode.equals(card.mode))
				{
					out.put(mode, ProjectExplorerPage.modeLabel(pModel, mode));
					break;
				}
			}
		}
		return out;
	}

	/** Free text, no fixed vocabulary: the value IS the label, like ebx-connectors' own category facet reuses the category's own label. */
	private static Map<String, String> ownerValues(final Model pModel)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (final Card card : pModel.cards)
		{
			if (!card.owner.isEmpty() && !out.containsKey(card.owner))
			{
				out.put(card.owner, card.owner);
			}
		}
		return out;
	}

	private static Map<String, String> sourceSystemValues(final Model pModel)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (final Card card : pModel.cards)
		{
			for (final String system : card.sourceSystems)
			{
				if (!system.isEmpty() && !out.containsKey(system))
				{
					out.put(system, system);
				}
			}
		}
		return out;
	}

	private static Map<String, String> zoneValues(final Model pModel)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (ProjectExplorerPage.anyZone(pModel, "landing"))
		{
			out.put("landing", ProjectExplorerPage.zoneLabel(pModel, "landing"));
		}
		if (ProjectExplorerPage.anyZone(pModel, "staging"))
		{
			out.put("staging", ProjectExplorerPage.zoneLabel(pModel, "staging"));
		}
		if (ProjectExplorerPage.anyZone(pModel, "master"))
		{
			out.put("master", ProjectExplorerPage.zoneLabel(pModel, "master"));
		}
		return out;
	}

	private static boolean anyZone(final Model pModel, final String pZone)
	{
		for (final Card card : pModel.cards)
		{
			if ("landing".equals(pZone) && card.hasLanding)
			{
				return true;
			}
			if ("staging".equals(pZone) && card.hasStaging)
			{
				return true;
			}
			if ("master".equals(pZone) && card.hasMaster)
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------------------------------------------- labels

	/** {@code Project.status} labels already exist under {@code wizard.status.*} (the wizard's own status chip) - reused rather than duplicated. */
	private static String statusLabel(final Model pModel, final String pStatus)
	{
		return ProjectExplorerPage.m(pModel, "wizard.status." + pStatus);
	}

	/** DRAFT/ARCHIVED: muted grey (nothing building, or done). LANDING: info blue (just started). STAGING: warn amber (further along). LIVE: good green - the only four chip colours the task brief allows, one status shares muted with another rather than inventing a fifth. */
	private static String statusKind(final String pStatus)
	{
		if ("LIVE".equals(pStatus))
		{
			return "real";
		}
		if ("STAGING".equals(pStatus))
		{
			return "warn";
		}
		if ("LANDING".equals(pStatus))
		{
			return "info";
		}
		return "muted";
	}

	/** {@code Project.style} short labels already exist under {@code wizard.style.<lowercase>.label} (the wizard's own style picker) - reused rather than duplicated. */
	private static String styleLabel(final Model pModel, final String pStyle)
	{
		return ProjectExplorerPage.m(pModel, "wizard.style." + pStyle.toLowerCase(Locale.ENGLISH) + ".label");
	}

	private static String modeLabel(final Model pModel, final String pMode)
	{
		return ProjectExplorerPage.m(pModel, "explorer.mode." + pMode);
	}

	/** {@code cockpit.volumes.zone.*} already carries exactly these three labels (Landing/Staging/Master) - reused rather than duplicated. */
	private static String zoneLabel(final Model pModel, final String pZone)
	{
		return ProjectExplorerPage.m(pModel, "cockpit.volumes.zone." + pZone);
	}

	// --------------------------------------------------------------------------------------------------------- misc

	private static DateFormat dateFormat(final Model pModel)
	{
		return DateFormat.getDateInstance(DateFormat.MEDIUM, pModel.locale);
	}

	private static String join(final List<String> pValues, final String pSeparator)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String value : pValues)
		{
			if (sb.length() > 0)
			{
				sb.append(pSeparator);
			}
			sb.append(value);
		}
		return sb.toString();
	}

	private static String fallback(final String pValue, final String... pFallbacks)
	{
		if (pValue != null && !pValue.trim().isEmpty())
		{
			return pValue;
		}
		for (final String fallback : pFallbacks)
		{
			if (fallback != null && !fallback.trim().isEmpty())
			{
				return fallback;
			}
		}
		return "";
	}

	/** {@code .mdmf-chip} reused as-is from factory.css (real/target/warn/bad/accent/muted/info variants) - never a bespoke chip class for this screen. */
	private static String chip(final String pKind, final String pText)
	{
		return "<span class=\"mdmf-chip mdmf-chip-" + pKind + "\">" + ProjectExplorerPage.esc(pText) + "</span>";
	}

	private static String esc(final String pText)
	{
		return FactoryHtml.esc(pText);
	}

	private static String m(final Model pModel, final String pKey, final Object... pArgs)
	{
		return Messages.get(ProjectExplorerPage.class, pModel.locale, pKey, pArgs);
	}

	private static String e(final Model pModel, final String pKey, final Object... pArgs)
	{
		return ProjectExplorerPage.esc(ProjectExplorerPage.m(pModel, pKey, pArgs));
	}
}
