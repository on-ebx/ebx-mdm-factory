package com.on.ebx.factory.ebx.ui;

import java.util.Locale;
import java.util.Set;

import com.on.ebx.factory.core.Assets;
import com.on.ebx.factory.delivery.TargetRule;
import com.on.ebx.factory.ebx.Favorites;
import com.on.ebx.factory.ebx.ProjectOrigin;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceAjaxContext;
import com.orchestranetworks.userservice.UserServiceAjaxRequest;
import com.orchestranetworks.userservice.UserServiceAjaxResponse;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/**
 * The project explorer. Every {@code Project} row of the factory dataset is shown - deliberately the whole table, never the filtered table-view selection:
 * this screen IS the overview, and a native quick-search or saved view left active on the table must never make a project silently vanish from it (unlike,
 * say, the connector explorer, which purposely defers to the table's own filtered selection because that screen sits ON the table view).
 * <p>
 * Per project the card also shows three facts: {@code ProjectSource} (count and distinct {@code sourceSystem} codes), {@code StagingEntity} (a plain row
 * count - every entity ever proposed, not only the accepted ones {@code CockpitData} scopes its own staging volumes to, since this overview answers "how far
 * has staging gone", not "what is actually generated"), and the most recently started {@code PipelineStep}. Audit F-36 gathered them in one streaming pass
 * per table ({@link ExplorerFacts}).
 * <p>
 * Server-paged since 2026-10-11: the projects and their facts live in a {@link ProjectIndex} per dataset (rebuilt only when one of the four tables moves),
 * the page itself carries no card, and the AJAX request {@link #queryRequest} answers one page of cards per status group plus every count
 * ({@link ProjectExplorerQuery}); the browser asks on each change of the search, the facets or the sort, and on "show more". Cards, facets, favorites and
 * links look and behave as before.
 * <p>
 * EBX permissions (2026-10-14): the shared index is only a cache of values. Every page, count, search, facet, favorite and action comes from the user's
 * own {@link ProjectAccess} view (records the session returns, fields visible to it, service and creation rights), and a closed dataspace, dataset or
 * table shows EBX's refusal instead of the explorer. See {@code docs/PROJECT-EXPLORER-PERMISSIONS.md}.
 */
public final class ProjectExplorerUserService implements UserService<TableViewEntitySelection>
{
	private Adaptation dataset;
	private Locale locale = Locale.ENGLISH;
	/** project-deletion lot (2026-09-23): {@code session.isUserInRole(Profile.ADMINISTRATOR)}, resolved once in {@link #setupDisplay} (which has the session; {@link #write} does not) and read from there. */
	private boolean administrator;
	/** Audit F-02: the MDM Factory right DESIGN, resolved with {@link #administrator}. */
	private boolean designer;

	/**
	 * The star: toggles the favorite of one card ({@link Favorites#toggle}) and answers its new state - ported
	 * 2026-09-23 from ebx-connectors' own {@code ConnectorCatalogUserService#favoriteRequest}, at the product owner's
	 * request that the project explorer be "consistent" with the connector explorer.
	 */
	private final UserServiceAjaxRequest favoriteRequest = new UserServiceAjaxRequest()
	{
		@Override
		public void processRequest(final UserServiceAjaxContext pContext, final UserServiceAjaxResponse pResponse)
		{
			final Session session = pContext.getSession();
			final Locale userLocale = session == null ? Locale.ENGLISH : session.getLocale();
			final Favorites.Outcome outcome = ProjectExplorerUserService.this.toggle(session, userLocale, pContext.getParameter("type"), pContext.getParameter("key"));
			pResponse.getWriter().add(Favorites.json(outcome, outcome.error == null ? null : FavoriteStars.errorText(userLocale, outcome.error)));
		}
	};

	/**
	 * One page of the explorer (server-paged, 2026-10-11). Parameters: {@code q} (the search text), {@code f} (the selected facet values,
	 * {@code "status=DRAFT|LIVE;owner=alice"}), {@code sort} ({@code name}, {@code created}, {@code sources}), {@code dir} ({@code 1}, {@code -1}, or
	 * {@code 0} for the criterion's natural direction), {@code off} and {@code lim} (at most {@link ProjectExplorerQuery#MAX_LIMIT} cards per group), and
	 * {@code group} to page one status group only ("show more"). Answer: {@link ProjectExplorerPage#queryAnswer}.
	 */
	private final UserServiceAjaxRequest queryRequest = new UserServiceAjaxRequest()
	{
		@Override
		public void processRequest(final UserServiceAjaxContext pContext, final UserServiceAjaxResponse pResponse)
		{
			final Session session = pContext.getSession();
			try
			{
				final Adaptation dataset = ProjectExplorerUserService.this.dataset;
				final Locale userLocale = session == null ? Locale.ENGLISH : session.getLocale();
				// 2026-10-14 (EBX permissions): every request checks the dataspace, dataset and table again, then answers from the user's own view only
				if (ProjectAccess.refusal(session, dataset, userLocale) != null)
				{
					pResponse.getWriter().add("{\"error\":\"forbidden\"}");
					return;
				}
				final ProjectAccess.Snapshot view = ProjectAccess.of(session, dataset, ProjectIndex.of(dataset));
				final Set<String> favorites = ProjectView.favorites(Favorites.keys(dataset, Favorites.login(session), Favorites.PROJECT), view.access.visibleProjects);
				final ProjectExplorerQuery.Result result = ProjectExplorerQuery.run(view.cards.cards, view.cards.searchKeys, favorites, pContext.getParameter("q"),
					ProjectView.selection(ProjectExplorerQuery.parse(pContext.getParameter("f")), view.access.hidden), ProjectView.sort(pContext.getParameter("sort"), view.access.hidden),
					ProjectExplorerUserService.integer(pContext.getParameter("dir"), 0, -1, 1), pContext.getParameter("group"),
					ProjectExplorerUserService.integer(pContext.getParameter("off"), 0, 0, Integer.MAX_VALUE),
					ProjectExplorerUserService.integer(pContext.getParameter("lim"), 12, 1, ProjectExplorerQuery.MAX_LIMIT));
				if (view.access.checkedRecords.size() < view.access.visibleProjects.size())
				{
					// past ProjectAccess.MAX_RECORD_CHECKS: the record-dependent field rules of the shown cards are evaluated now
					for (final ProjectExplorerQuery.Group group : result.groups.values())
					{
						final java.util.List<ProjectExplorerPage.Card> page = ProjectAccess.finishPage(session, dataset, view, group.page);
						group.page.clear();
						group.page.addAll(page);
					}
				}
				final ProjectExplorerPage.Model model = ProjectExplorerUserService.this.model(userLocale, view);
				pResponse.getWriter().add(ProjectExplorerPage.queryAnswer(model, result, favorites));
			}
			catch (final RuntimeException failure)
			{
				// the page shows its localized "could not be read" notice on an answer without groups
				LoggingCategory.getKernel().error("[mdm-factory] project explorer query failed", failure);
				pResponse.getWriter().add("{\"error\":\"failed\"}");
			}
		}
	};

	/**
	 * The star, checked against EBX (2026-10-14): the dataspace, dataset and table must still be open to the user, and a pin may only be ADDED on a project
	 * the user's session returns; removing one of the user's own pins stays possible (it reveals nothing). A refused pin answers like an unknown project.
	 */
	private Favorites.Outcome toggle(final Session pSession, final Locale pLocale, final String pType, final String pKey)
	{
		if (ProjectAccess.refusal(pSession, this.dataset, pLocale) != null || !Favorites.PROJECT.equals(pType))
		{
			return Favorites.refused(Favorites.ERR_BAD_REQUEST);
		}
		final ProjectAccess.Snapshot view = ProjectAccess.of(pSession, this.dataset, ProjectIndex.of(this.dataset));
		if (pKey == null || !view.access.visibleProjects.contains(pKey) && !Favorites.keys(this.dataset, Favorites.login(pSession), Favorites.PROJECT).contains(pKey))
		{
			return Favorites.refused(Favorites.ERR_UNKNOWN_TARGET);
		}
		return Favorites.toggle(this.dataset, pSession, pType, pKey);
	}

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		this.dataset = pContext.getEntitySelection().getDataset();
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		this.administrator = pContext.getSession().isUserInRole(com.orchestranetworks.service.Profile.ADMINISTRATOR);
		this.designer = com.on.ebx.factory.ebx.FactoryAccess.has(pContext.getSession(), com.on.ebx.factory.core.FactoryRight.DESIGN);
		pConfigurator.setTitle(Messages.get(ProjectExplorerUserService.class, this.locale, "explorer.label"));
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setRightButtons(close);
		// 2026-10-14 (EBX permissions): a closed dataspace, dataset or Project table shows the refusal instead of the explorer
		final String refusal = ProjectAccess.refusal(pContext.getSession(), this.dataset, this.locale);
		if (refusal != null)
		{
			pConfigurator.setContent(ScreenFailure.guard("project explorer refusal", this.locale, new UserServicePane()
			{
				@Override
				public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
				{
					pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/factory.css")).add("?v=")
						.add(Assets.version()).add("\"/>");
					pWriter.add("<div class=\"mdmf\"><div class=\"mdmf-notice mdmf-notice-warn\" role=\"alert\">").add(FactoryHtml.esc(refusal)).add("</div></div>");
				}
			}));
			return;
		}
		// audit F-41: an unplanned failure of the rendering becomes a localized notice with a logged reference, never a blank pane
		pConfigurator.setContent(ScreenFailure.guard("project explorer", this.locale, new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				ProjectExplorerUserService.this.write(pWriter, pPaneContext.getSession());
			}
		}));
	}

	private void write(final UserServicePaneWriter pWriter, final Session pSession)
	{
		// fix/review-assets (2026-09-21) convention, followed by every other screen of this module: Assets.version()
		// changes with every build.sh run, so a fixed "?v=1" would keep the browser serving a stale stylesheet
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/factory.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/explorer.css")).add("?v=").add(Assets.version())
			.add("\"/>");

		final ProjectAccess.Snapshot view = ProjectAccess.of(pSession, this.dataset, ProjectIndex.of(this.dataset));
		final ProjectExplorerPage.Model model = new ProjectExplorerPage.Model();
		model.locale = this.locale;
		model.canCreateProject = TargetRule.projectCreationAllowed(ProjectOrigin.role(this.dataset));
		model.createDisabledReason = TargetRule.refusal();
		model.dataspaceKey = this.dataset.getHome().getKey().format();
		model.datasetName = this.dataset.getAdaptationName().getStringName();
		model.favoriteEndpointUrl = pWriter.getURLForAjaxRequest(this.favoriteRequest);
		model.queryEndpointUrl = pWriter.getURLForAjaxRequest(this.queryRequest);
		model.user = FactoryHtml.userId(pSession);
		model.canDelete = this.administrator && view.canDelete;
		// "New project" goes through the wizard and creates a Project record: both EBX rights are needed (2026-10-14)
		model.canDesign = this.designer && view.canCreate;
		model.canWizard = view.canWizard;
		model.hidden.addAll(view.access.hidden);
		// server-paged: the cards only feed the facet vocabularies here, none is written into the page (the script asks for them). Only the user's own
		// cards (2026-10-14): an owner or a source system of a project the user cannot see must not appear as a filter value either.
		model.cards.addAll(view.cards.cards);

		pWriter.add(ProjectExplorerPage.render(model));
		pWriter.addJS_cr(ProjectExplorerPage.script(model));
		// same rewrite as the connector explorer's own script (ConnectorCatalogUserService): ui/advanced/data/ is
		// a perspective-agnostic browser, and the "Advanced view" link should stay inside whatever perspective this
		// screen was opened from when there is one - CLAUDE.md's own note on this exact link family.
		// Server-paged (2026-10-11): the cards arrive after the page with each "query" answer, which the page's script
		// announces with an "mdmxcards" event on its root - the rewrite runs again then (it is idempotent).
		pWriter.addJS_cr("(function(){var m=/\\/ui\\/custom\\/([^\\/]+)\\//.exec(window.top.location.pathname);if(!m)return;"
			+ "var o='/ui/advanced/data/',n='/ui/custom/'+m[1]+'/data/';"
			+ "function rw(){document.querySelectorAll('a[href*=\"'+o+'\"]').forEach(function(a){a.setAttribute('href',a.getAttribute('href').split(o).join(n));});}"
			+ "rw();var root=document.querySelector('.mdmc-explorer');if(root)root.addEventListener('mdmxcards',rw);})();");
		// coordinator, 2026-09-23: Wizard/Cockpit open correctly inside the perspective, but their Close button
		// otherwise lands on the native Project table - arm the three explorer links so that button can come
		// back here instead (FactoryHtml#originReturnScript reads this token on the far side).
		pWriter.addJS_cr(FactoryHtml.originCaptureScript(FactoryHtml.userId(pSession), ".mdmc-btn, .mdmx-newproject"));
		// ... and the Wizard/Cockpit/Delete buttons of the cards, which arrive with each "query" answer (server-paged, 2026-10-11)
		pWriter.addJS_cr(FactoryHtml.originCaptureDelegatedScript(FactoryHtml.userId(pSession), ".mdmc-explorer", ".mdmc-card .mdmc-btn"));
		// fix/cockpit-return-to-wizard (2026-09-26): a fresh start from the explorer forgets any origin a wizard kept
		pWriter.addJS_cr(FactoryHtml.storeOpen(FactoryHtml.userId(pSession)) + "ST.remove('" + FactoryHtml.WIZARD_ORIGIN_KEY + "');})();");
		// leave-guard lot (2026-09-23): the discreet "native table view" link (ProjectExplorerPage.linksHtml,
		// .mdmc-advanced) - not part of the return-to-explorer token scheme above (it never comes back here the
		// same way), only needs the voluntary-leave marking so it never triggers EBX's "Quitter le site Web ?"
		// prompt either.
		pWriter.addJS_cr(FactoryHtml.voluntaryLeaveScript(".mdmf-leave-link"));
		// the favorite star (2026-09-23, ported from ebx-connectors' own connector explorer): root.mdmxFacets
		// (F) is set up synchronously at the end of ProjectExplorerPage.script's own IIFE above, so it already
		// exists here - same ordering ConnectorCatalogUserService relies on for root.ftFacets. Server-paged
		// (2026-10-11): listened to on the root, the cards being replaced by every answer.
		pWriter.addJS_cr("(function(){var root=document.querySelector('.mdmc-explorer'),F=root.mdmxFacets;" + FavoriteStars.DELEGATED_JS + "})();");
	}

	/** What the cards of a "query" answer need of the user's permissions (links, the Delete button). */
	private ProjectExplorerPage.Model model(final Locale pLocale, final ProjectAccess.Snapshot pView)
	{
		final ProjectExplorerPage.Model model = new ProjectExplorerPage.Model();
		model.locale = pLocale;
		model.dataspaceKey = this.dataset.getHome().getKey().format();
		model.datasetName = this.dataset.getAdaptationName().getStringName();
		model.canDelete = pView.canDelete; // administrator AND the ProjectDelete service permission (ProjectAccess)
		model.canWizard = pView.canWizard;
		model.hidden.addAll(pView.access.hidden);
		return model;
	}

	/** A request parameter as a number within bounds; missing or unreadable reads {@code pDefault}. */
	static int integer(final String pValue, final int pDefault, final int pMin, final int pMax)
	{
		int value = pDefault;
		if (pValue != null)
		{
			try
			{
				value = Integer.parseInt(pValue.trim());
			}
			catch (final NumberFormatException unreadable)
			{
				value = pDefault;
			}
		}
		return Math.max(pMin, Math.min(pMax, value));
	}

	@Override
	public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext)
	{
		// read-only: nothing posted to check
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext, final UserServiceEventOutcome pOutcome)
	{
		return pOutcome;
	}
}
