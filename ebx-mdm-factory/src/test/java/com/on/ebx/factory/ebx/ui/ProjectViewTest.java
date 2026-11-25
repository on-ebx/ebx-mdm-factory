package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JUnit-free: run the main, a failure throws. One user's view of the project explorer (2026-10-14, EBX permissions, {@link ProjectView}): no card, count,
 * search hit, facet value, favorite or button beyond what the user's EBX permissions allow - fixtures standing for what {@code ProjectAccess} reads.
 */
public final class ProjectViewTest
{
	public static void main(final String[] pArgs)
	{
		ProjectViewTest.invisibleProjectsAreNeitherShownNorCounted();
		ProjectViewTest.hiddenFieldIsNotShownSearchedOrFaceted();
		ProjectViewTest.recordDependentRuleMasksOneCardOnly();
		ProjectViewTest.hiddenStatusGoesToItsOwnGroup();
		ProjectViewTest.childCountsFollowTheVisibleRows();
		ProjectViewTest.selectionAndSortIgnoreHiddenValues();
		ProjectViewTest.pageDropsHiddenLinesAndRefusedButtons();
		System.out.println("ProjectViewTest OK");
	}

	private static void invisibleProjectsAreNeitherShownNorCounted()
	{
		final List<ProjectExplorerPage.Card> cards = Arrays.asList(ProjectViewTest.card("A", "Alpha", "LIVE", "alice"), ProjectViewTest.card("B", "Beta", "LIVE", "bob"),
			ProjectViewTest.card("C", "Secret", "DRAFT", "carol"));
		final ProjectView.Access access = ProjectViewTest.access("A", "B");
		final ProjectView.Cards view = ProjectViewTest.build(cards, access);
		ProjectViewTest.check("only the visible projects", view.cards.size() == 2 && view.searchKeys.size() == 2);
		final Set<String> favorites = ProjectView.favorites(new HashSet<String>(Arrays.asList("A", "C")), access.visibleProjects);
		ProjectViewTest.check("a pin on an invisible project is dropped", favorites.equals(Collections.singleton("A")));
		final ProjectExplorerQuery.Result r = ProjectViewTest.run(view, favorites, "secret", "");
		ProjectViewTest.check("not found by search, not in the total", r.all == 2 && r.total == 0);
		final ProjectExplorerQuery.Result all = ProjectViewTest.run(view, favorites, "", "");
		ProjectViewTest.check("no count of the invisible owner or status", !all.facets.get("owner").containsKey("carol") && !all.facets.get("status").containsKey("DRAFT"));
		ProjectViewTest.check("favorite counted once", Integer.valueOf(1).equals(all.facets.get("fav").get("1")));
		ProjectViewTest.check("the shared cards are untouched", cards.get(0).hidden.isEmpty());
	}

	private static void hiddenFieldIsNotShownSearchedOrFaceted()
	{
		final List<ProjectExplorerPage.Card> cards = Arrays.asList(ProjectViewTest.card("A", "Alpha", "LIVE", "alice"), ProjectViewTest.card("B", "Beta", "LIVE", "bob"));
		final ProjectView.Access access = ProjectViewTest.access("A", "B");
		access.hidden.add(ProjectView.F_OWNER);
		final ProjectView.Cards view = ProjectViewTest.build(cards, access);
		ProjectViewTest.check("owner blanked and marked", view.cards.get(0).owner.isEmpty() && view.cards.get(0).hidden.contains(ProjectView.F_OWNER));
		ProjectViewTest.check("owner no longer searched", ProjectViewTest.run(view, null, "alice", "").total == 0);
		ProjectViewTest.check("the other fields still are", ProjectViewTest.run(view, null, "alpha", "").total == 1);
		ProjectViewTest.check("no owner facet value", ProjectViewTest.run(view, null, "", "").facets.get("owner").isEmpty());
	}

	private static void recordDependentRuleMasksOneCardOnly()
	{
		final ProjectExplorerPage.Card a = ProjectViewTest.card("A", "Alpha", "LIVE", "alice");
		a.description = "confidential plan";
		final ProjectExplorerPage.Card b = ProjectViewTest.card("B", "Beta", "LIVE", "bob");
		b.description = "confidential too";
		final ProjectView.Access access = ProjectViewTest.access("A", "B");
		access.hiddenByRecord.put("A", Collections.singleton(ProjectView.F_DESCRIPTION));
		final ProjectView.Cards view = ProjectViewTest.build(Arrays.asList(a, b), access);
		ProjectViewTest.check("A's description hidden, B's kept", view.cards.get(0).description.isEmpty() && "confidential too".equals(view.cards.get(1).description));
		final ProjectExplorerQuery.Result r = ProjectViewTest.run(view, null, "confidential", "");
		ProjectViewTest.check("the search finds B only", r.total == 1 && "B".equals(r.groups.get("LIVE").page.get(0).key));
	}

	private static void hiddenStatusGoesToItsOwnGroup()
	{
		final ProjectView.Access access = ProjectViewTest.access("A", "B");
		access.hiddenByRecord.put("B", Collections.singleton(ProjectView.F_STATUS));
		final ProjectView.Cards view = ProjectViewTest.build(Arrays.asList(ProjectViewTest.card("A", "Alpha", "LIVE", "x"), ProjectViewTest.card("B", "Beta", "ARCHIVED", "x")),
			access);
		final ProjectExplorerQuery.Result r = ProjectViewTest.run(view, null, "", "");
		ProjectViewTest.check("B in the hidden-status group, ARCHIVED empty", r.groups.get(ProjectView.HIDDEN_STATUS).count == 1 && r.groups.get("ARCHIVED").count == 0);
		ProjectViewTest.check("no status count for B", !r.facets.get("status").containsKey("ARCHIVED"));
	}

	private static void childCountsFollowTheVisibleRows()
	{
		final ProjectExplorerPage.Card a = ProjectViewTest.card("A", "Alpha", "LIVE", "x", "ERP", "CRM");
		a.stagingEntityCount = 2;
		a.lastActivityStep = "MASTER_LOAD";
		a.lastActivityAt = new Date(3000L);
		final List<ProjectView.Row> sources = Arrays.asList(new ProjectView.Row("1", "A", "ERP", null), new ProjectView.Row("2", "A", "CRM", null));
		final List<ProjectView.Row> entities = Arrays.asList(new ProjectView.Row("10", "A", null, null), new ProjectView.Row("11", "A", null, null));
		final List<ProjectView.Row> steps = Arrays.asList(new ProjectView.Row("100", "A", "LANDING_MODEL", new Date(1000L)),
			new ProjectView.Row("101", "A", "MASTER_LOAD", new Date(3000L)));
		final ProjectView.Access all = ProjectViewTest.access("A");
		final ProjectExplorerPage.Card same = ProjectView.build(Collections.singletonList(a), sources, entities, steps, all).cards.get(0);
		ProjectViewTest.check("every row visible: the shared facts", same.sourceCount == 2 && same.stagingEntityCount == 2 && "MASTER_LOAD".equals(same.lastActivityStep));
		final ProjectView.Access part = ProjectViewTest.access("A");
		part.visibleSources = Collections.singleton("1");
		part.visibleEntities = Collections.<String> emptySet();
		part.visibleSteps = Collections.singleton("100");
		final ProjectExplorerPage.Card mine = ProjectView.build(Collections.singletonList(a), sources, entities, steps, part).cards.get(0);
		ProjectViewTest.check("recounted on the visible rows: " + mine.sourceSystems, mine.sourceCount == 1 && mine.sourceSystems.equals(Collections.singletonList("ERP"))
			&& mine.stagingEntityCount == 0 && "LANDING_MODEL".equals(mine.lastActivityStep));
		final ProjectView.Access closed = ProjectViewTest.access("A");
		closed.hidden.add(ProjectView.F_SOURCES);
		closed.hidden.add(ProjectView.F_ACTIVITY);
		final ProjectExplorerPage.Card none = ProjectView.build(Collections.singletonList(a), sources, entities, steps, closed).cards.get(0);
		ProjectViewTest.check("a hidden table: no count, no system, no activity", none.sourceCount == 0 && none.sourceSystems.isEmpty() && none.lastActivityStep == null);
		ProjectViewTest.check("no longer searched on its systems", ProjectViewTest.run(ProjectView.build(Collections.singletonList(a), sources, entities, steps, closed), null, "crm", "").total == 0);
	}

	private static void selectionAndSortIgnoreHiddenValues()
	{
		final Set<String> hidden = new HashSet<String>(Arrays.asList(ProjectView.F_OWNER, ProjectView.F_CREATED, ProjectView.F_SOURCE_SYSTEMS));
		final Map<String, Set<String>> sel = ProjectView.selection(ProjectExplorerQuery.parse("owner=alice;status=LIVE;source=ERP"), hidden);
		ProjectViewTest.check("hidden facets dropped: " + sel, sel.keySet().equals(Collections.singleton("status")));
		ProjectViewTest.check("created sort falls back to name", "name".equals(ProjectView.sort("created", hidden)) && "sources".equals(ProjectView.sort("sources", hidden)));
		final ProjectExplorerPage.Card c = ProjectViewTest.card("ZED", "Alpha", "LIVE", "x");
		ProjectView.mask(c, Collections.singleton(ProjectView.F_LABEL));
		ProjectViewTest.check("a hidden label is not the sort key", c.label.isEmpty() && "zed".equals(c.sortName));
	}

	private static void pageDropsHiddenLinesAndRefusedButtons()
	{
		final ProjectExplorerPage.Model model = new ProjectExplorerPage.Model();
		model.locale = Locale.ENGLISH;
		model.dataspaceKey = "BMdmFactory";
		model.datasetName = "MdmFactory";
		model.canWizard = false;
		final ProjectExplorerPage.Card c = ProjectViewTest.card("A", "Alpha", "LIVE", "alice", "ERP");
		c.hasMaster = true;
		ProjectView.mask(c, new HashSet<String>(Arrays.asList(ProjectView.F_OWNER, ProjectView.F_SOURCES, ProjectView.F_MASTER, ProjectView.F_ACTIVITY)));
		c.canCockpit = false;
		final String html = ProjectExplorerPage.cardsHtml(model, Collections.singletonList(c));
		ProjectViewTest.check("no owner line", html.indexOf("alice") < 0);
		ProjectViewTest.check("no sources line", html.indexOf(Messages.get(ProjectExplorerPage.class, Locale.ENGLISH, "explorer.card.sources.none")) < 0 && html.indexOf("ERP") < 0);
		ProjectViewTest.check("no activity line", html.indexOf(Messages.get(ProjectExplorerPage.class, Locale.ENGLISH, "explorer.card.noActivity")) < 0);
		ProjectViewTest.check("the master chip is gone, the other two stay", html.indexOf(">" + Messages.get(ProjectExplorerPage.class, Locale.ENGLISH, "cockpit.volumes.zone.master") + "<") < 0
			&& html.indexOf(">" + Messages.get(ProjectExplorerPage.class, Locale.ENGLISH, "cockpit.volumes.zone.landing") + "<") >= 0);
		ProjectViewTest.check("no wizard nor cockpit button", html.indexOf("service=ProjectWizard") < 0 && html.indexOf("service=FactoryCockpit") < 0);
		final ProjectExplorerPage.Card open = ProjectViewTest.card("B", "Beta", "LIVE", "bob");
		model.canWizard = true;
		final String both = ProjectExplorerPage.cardsHtml(model, Collections.singletonList(open));
		ProjectViewTest.check("both buttons when allowed", both.indexOf("service=ProjectWizard") >= 0 && both.indexOf("service=FactoryCockpit") >= 0);
		final ProjectExplorerPage.Card noCode = ProjectViewTest.card("SECRET-CODE", "Gamma", "LIVE", "bob");
		ProjectView.mask(noCode, Collections.singleton(ProjectView.F_CODE));
		final String masked = ProjectExplorerPage.cardsHtml(model, Collections.singletonList(noCode));
		ProjectViewTest.check("a hidden code is not displayed but the links still open the record", masked.indexOf("<code>SECRET-CODE</code>") < 0 && masked.indexOf("SECRET-CODE") > 0);
		model.hidden.add(ProjectView.F_CREATED);
		final ProjectExplorerPage.Model page = model;
		page.cards.add(open);
		ProjectViewTest.check("the created sort is not offered", ProjectExplorerPage.render(page).indexOf("value=\"created\"") < 0);
	}

	// ------------------------------------------------------------------------------------------------------ helpers

	private static ProjectView.Access access(final String... pVisible)
	{
		final ProjectView.Access access = new ProjectView.Access();
		access.visibleProjects = new HashSet<String>(Arrays.asList(pVisible));
		return access;
	}

	private static ProjectView.Cards build(final List<ProjectExplorerPage.Card> pCards, final ProjectView.Access pAccess)
	{
		return ProjectView.build(pCards, Collections.<ProjectView.Row> emptyList(), Collections.<ProjectView.Row> emptyList(), Collections.<ProjectView.Row> emptyList(), pAccess);
	}

	private static ProjectExplorerQuery.Result run(final ProjectView.Cards pView, final Set<String> pFavorites, final String pText, final String pSelection)
	{
		return ProjectExplorerQuery.run(pView.cards, pView.searchKeys, pFavorites, pText, ProjectExplorerQuery.parse(pSelection), "name", 0, null, 0, 96);
	}

	private static ProjectExplorerPage.Card card(final String pKey, final String pLabel, final String pStatus, final String pOwner, final String... pSystems)
	{
		final ProjectExplorerPage.Card card = new ProjectExplorerPage.Card();
		card.code = pKey;
		card.key = pKey;
		card.label = pLabel;
		card.sortName = pLabel.toLowerCase(Locale.ROOT);
		card.status = pStatus;
		card.style = "CONSOLIDATION";
		card.mode = "GREENFIELD";
		card.owner = pOwner;
		card.sourceSystems.addAll(new ArrayList<String>(Arrays.asList(pSystems)));
		card.sourceCount = pSystems.length;
		return card;
	}

	private static int count(final String pText, final String pPart)
	{
		int n = 0;
		for (int i = pText.indexOf(pPart); i >= 0; i = pText.indexOf(pPart, i + 1))
		{
			n++;
		}
		return n;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
