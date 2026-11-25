package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * JUnit-free: run the main, a failure throws. The server-paged explorer's search, facet counts, sort and paging ({@link ProjectExplorerQuery}), checked
 * against the rules of the browser script they replace - including a brute-force reference for the facet counts on random fixtures.
 */
public final class ProjectExplorerQueryTest
{
	public static void main(final String[] pArgs)
	{
		ProjectExplorerQueryTest.searchFoldsAccentsPunctuationAndPlural();
		ProjectExplorerQueryTest.parseIgnoresUnknownKeysAndEmptyValues();
		ProjectExplorerQueryTest.facetCountsUseEveryOtherFacet();
		ProjectExplorerQueryTest.facetCountsMatchABruteForceReference();
		ProjectExplorerQueryTest.groupsArePagedPerStatus();
		ProjectExplorerQueryTest.showMorePagesOneGroupOnly();
		ProjectExplorerQueryTest.sortNaturalAndFlipped();
		ProjectExplorerQueryTest.limitIsCapped();
		ProjectExplorerQueryTest.unknownStatusIsCountedButNotShown();
		System.out.println("ProjectExplorerQueryTest OK");
	}

	private static void searchFoldsAccentsPunctuationAndPlural()
	{
		ProjectExplorerQueryTest.check("terms fold accents and drop a final plural s", Arrays.asList("societe", "client").equals(ProjectExplorerQuery.terms("  Sociétés, CLIENTS! ")));
		ProjectExplorerQueryTest.check("a short word keeps its s", Arrays.asList("bus").equals(ProjectExplorerQuery.terms("bus")));
		ProjectExplorerQueryTest.check("blank search has no term", ProjectExplorerQuery.terms("  - ").isEmpty() && ProjectExplorerQuery.terms(null).isEmpty());
		final ProjectExplorerPage.Card card = ProjectExplorerQueryTest.card("C-1", "Référentiel Sociétés", "DRAFT", "CONSOLIDATION", "TARGET_EXISTS", "o'brien", "SAP-ECC");
		final String key = ProjectExplorerQuery.searchKey(card);
		ProjectExplorerQueryTest.check("search key folded and compacted, one field per word group: " + key, key.equals("referentielsocietes c1 obrien sapecc"));
		final List<ProjectExplorerPage.Card> cards = Collections.singletonList(card);
		final List<String> keys = Collections.singletonList(key);
		ProjectExplorerQueryTest.check("words in any order, any case, any accent", ProjectExplorerQueryTest.run(cards, keys, "SOCIETE referentiel", "").total == 1);
		ProjectExplorerQueryTest.check("punctuation ignored across words (sap-ecc as sapecc)", ProjectExplorerQueryTest.run(cards, keys, "sapecc", "").total == 1);
		ProjectExplorerQueryTest.check("a word that is not there excludes", ProjectExplorerQueryTest.run(cards, keys, "societe zzz", "").total == 0);
		// 2026-10-14 (EBX permissions): a word is searched inside one field, never across two (a blanked field can then never complete a match)
		ProjectExplorerQueryTest.check("a word spanning two fields does not match", ProjectExplorerQueryTest.run(cards, keys, "societesc1", "").total == 0);
	}

	private static void parseIgnoresUnknownKeysAndEmptyValues()
	{
		final Map<String, Set<String>> sel = ProjectExplorerQuery.parse("status=DRAFT|LIVE;nope=X;owner=;source=|ERP|;;=x");
		ProjectExplorerQueryTest.check("known keys kept: " + sel, sel.keySet().equals(new HashSet<String>(Arrays.asList("status", "source"))));
		ProjectExplorerQueryTest.check("values split", sel.get("status").equals(new HashSet<String>(Arrays.asList("DRAFT", "LIVE"))));
		ProjectExplorerQueryTest.check("empty values dropped", sel.get("source").equals(Collections.singleton("ERP")));
		ProjectExplorerQueryTest.check("null and empty read as nothing", ProjectExplorerQuery.parse(null).isEmpty() && ProjectExplorerQuery.parse("").isEmpty());
	}

	private static void facetCountsUseEveryOtherFacet()
	{
		final List<ProjectExplorerPage.Card> cards = new ArrayList<ProjectExplorerPage.Card>();
		cards.add(ProjectExplorerQueryTest.card("A", "Alpha", "DRAFT", "CONSOLIDATION", "TARGET_EXISTS", "alice", "ERP", "CRM"));
		cards.add(ProjectExplorerQueryTest.card("B", "Beta", "LIVE", "CONSOLIDATION", "GREENFIELD", "bob", "ERP"));
		cards.add(ProjectExplorerQueryTest.card("C", "Gamma", "LIVE", "REGISTRY", "GREENFIELD", "alice"));
		final ProjectExplorerQuery.Result r = ProjectExplorerQueryTest.run(cards, ProjectExplorerQueryTest.keys(cards), "", "status=LIVE;owner=alice");
		ProjectExplorerQueryTest.check("only C passes both", r.total == 1 && r.groups.get("LIVE").page.size() == 1 && "C".equals(r.groups.get("LIVE").page.get(0).code));
		ProjectExplorerQueryTest.check("status counts ignore the status facet: DRAFT 1 (A, alice), LIVE 1", Integer.valueOf(1).equals(r.facets.get("status").get("DRAFT"))
			&& Integer.valueOf(1).equals(r.facets.get("status").get("LIVE")));
		ProjectExplorerQueryTest.check("owner counts ignore the owner facet: alice 1, bob 1", Integer.valueOf(1).equals(r.facets.get("owner").get("alice"))
			&& Integer.valueOf(1).equals(r.facets.get("owner").get("bob")));
		ProjectExplorerQueryTest.check("a card failing one facet counts only for that facet (A, failing status, adds no style count)", !r.facets.get("style").containsKey("CONSOLIDATION"));
		ProjectExplorerQueryTest.check("multi-valued source: OR inside, A counted for both", ProjectExplorerQueryTest.run(cards, ProjectExplorerQueryTest.keys(cards), "", "source=CRM").total == 1);
		final Set<String> favorites = Collections.singleton("B");
		final ProjectExplorerQuery.Result fav = ProjectExplorerQuery.run(cards, ProjectExplorerQueryTest.keys(cards), favorites, "", ProjectExplorerQuery.parse("fav=1"), "name", 0, null, 0, 12);
		ProjectExplorerQueryTest.check("my favorites", fav.total == 1 && "B".equals(fav.groups.get("LIVE").page.get(0).code));
		ProjectExplorerQueryTest.check("favorite facet counts: 1 pinned, 2 not", Integer.valueOf(1).equals(fav.facets.get("fav").get("1")) && Integer.valueOf(2).equals(fav.facets.get("fav").get("0")));
		final ProjectExplorerQuery.Result zones = ProjectExplorerQueryTest.run(cards, ProjectExplorerQueryTest.keys(cards), "", "zone=staging");
		ProjectExplorerQueryTest.check("zone: only A has a staging dataspace", zones.total == 1 && Integer.valueOf(3).equals(zones.facets.get("zone").get("landing")));
	}

	/** The old script's rule, written the slow way: a value counts the cards that pass the search and every facet but its own. */
	private static void facetCountsMatchABruteForceReference()
	{
		final String[] statuses = ProjectExplorerQuery.STATUS_ORDER;
		final String[] styles = { "CONSOLIDATION", "REGISTRY" };
		final String[] owners = { "alice", "bob", "carol" };
		final String[] systems = { "ERP", "CRM", "WEB" };
		for (long seed = 1; seed <= 30; seed++)
		{
			final Random random = new Random(seed);
			final List<ProjectExplorerPage.Card> cards = new ArrayList<ProjectExplorerPage.Card>();
			for (int i = 0; i < 60; i++)
			{
				final List<String> mine = new ArrayList<String>();
				for (final String system : systems)
				{
					if (random.nextBoolean())
					{
						mine.add(system);
					}
				}
				cards.add(ProjectExplorerQueryTest.card("P" + i, (random.nextBoolean() ? "Client " : "Article ") + i, statuses[random.nextInt(statuses.length)], styles[random.nextInt(2)],
					random.nextBoolean() ? "GREENFIELD" : "TARGET_EXISTS", owners[random.nextInt(3)], mine.toArray(new String[0])));
			}
			final String selection = "status=" + statuses[random.nextInt(statuses.length)] + "|" + statuses[random.nextInt(statuses.length)] + ";owner=" + owners[random.nextInt(3)]
				+ (random.nextBoolean() ? ";source=" + systems[random.nextInt(3)] : "");
			final String text = random.nextBoolean() ? "client" : "";
			final List<String> keys = ProjectExplorerQueryTest.keys(cards);
			final Map<String, Set<String>> sel = ProjectExplorerQuery.parse(selection);
			final ProjectExplorerQuery.Result r = ProjectExplorerQuery.run(cards, keys, Collections.<String> emptySet(), text, sel, "name", 0, null, 0, 96);
			for (int k = 0; k < ProjectExplorerQuery.KEYS.length; k++)
			{
				final String key = ProjectExplorerQuery.KEYS[k];
				final Map<String, Integer> expected = new java.util.HashMap<String, Integer>();
				int total = 0;
				for (int c = 0; c < cards.size(); c++)
				{
					if (!text.isEmpty() && keys.get(c).indexOf(text) < 0)
					{
						continue;
					}
					final List<List<String>> values = ProjectExplorerQuery.values(cards.get(c), false);
					boolean others = true;
					boolean own = true;
					for (int o = 0; o < ProjectExplorerQuery.KEYS.length; o++)
					{
						final Set<String> wanted = sel.get(ProjectExplorerQuery.KEYS[o]);
						final boolean pass = wanted == null || !Collections.disjoint(wanted, values.get(o));
						if (o == k)
						{
							own = pass;
						}
						else if (!pass)
						{
							others = false;
						}
					}
					if (others && own)
					{
						total++;
					}
					if (others)
					{
						for (final String v : values.get(k))
						{
							final Integer n = expected.get(v);
							expected.put(v, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
						}
					}
				}
				ProjectExplorerQueryTest.check("seed " + seed + " facet " + key + ": " + expected + " vs " + r.facets.get(key), expected.equals(r.facets.get(key)));
				ProjectExplorerQueryTest.check("seed " + seed + " total", total == r.total);
			}
		}
	}

	private static void groupsArePagedPerStatus()
	{
		final List<ProjectExplorerPage.Card> cards = new ArrayList<ProjectExplorerPage.Card>();
		for (int i = 0; i < 30; i++)
		{
			cards.add(ProjectExplorerQueryTest.card("D" + i, String.format(Locale.ROOT, "Draft %02d", Integer.valueOf(i)), "DRAFT", "CONSOLIDATION", "GREENFIELD", "a"));
		}
		for (int i = 0; i < 5; i++)
		{
			cards.add(ProjectExplorerQueryTest.card("L" + i, "Live " + i, "LIVE", "CONSOLIDATION", "GREENFIELD", "a"));
		}
		final ProjectExplorerQuery.Result r = ProjectExplorerQuery.run(cards, ProjectExplorerQueryTest.keys(cards), null, null, ProjectExplorerQuery.parse(null), "name", 0, "", 0, 8);
		ProjectExplorerQueryTest.check("all and total", r.all == 35 && r.total == 35);
		ProjectExplorerQueryTest.check("every status group is listed, in order, then the hidden-status group", new ArrayList<String>(r.groups.keySet()).equals(Arrays.asList(ProjectExplorerQuery.GROUP_ORDER))
			&& ProjectView.HIDDEN_STATUS.equals(ProjectExplorerQuery.GROUP_ORDER[ProjectExplorerQuery.GROUP_ORDER.length - 1]));
		ProjectExplorerQueryTest.check("DRAFT: 30 in all, 8 on the page", r.groups.get("DRAFT").count == 30 && r.groups.get("DRAFT").page.size() == 8);
		ProjectExplorerQueryTest.check("LIVE: 5 in all, 5 on the page", r.groups.get("LIVE").count == 5 && r.groups.get("LIVE").page.size() == 5);
		ProjectExplorerQueryTest.check("an empty group", r.groups.get("STAGING").count == 0 && r.groups.get("STAGING").page.isEmpty());
		ProjectExplorerQueryTest.check("first page sorted A-Z", "D0".equals(r.groups.get("DRAFT").page.get(0).code) && "D7".equals(r.groups.get("DRAFT").page.get(7).code));
	}

	private static void showMorePagesOneGroupOnly()
	{
		final List<ProjectExplorerPage.Card> cards = new ArrayList<ProjectExplorerPage.Card>();
		for (int i = 0; i < 30; i++)
		{
			cards.add(ProjectExplorerQueryTest.card("D" + i, String.format(Locale.ROOT, "Draft %02d", Integer.valueOf(i)), "DRAFT", "CONSOLIDATION", "GREENFIELD", "a"));
		}
		cards.add(ProjectExplorerQueryTest.card("L", "Live", "LIVE", "CONSOLIDATION", "GREENFIELD", "a"));
		final ProjectExplorerQuery.Result r = ProjectExplorerQuery.run(cards, ProjectExplorerQueryTest.keys(cards), null, "", ProjectExplorerQuery.parse(null), "name", 0, "DRAFT", 24, 24);
		ProjectExplorerQueryTest.check("the rest of DRAFT", r.groups.get("DRAFT").page.size() == 6 && "D24".equals(r.groups.get("DRAFT").page.get(0).code));
		ProjectExplorerQueryTest.check("LIVE not paged by a DRAFT show-more, still counted", r.groups.get("LIVE").page.isEmpty() && r.groups.get("LIVE").count == 1);
		final ProjectExplorerQuery.Result past = ProjectExplorerQuery.run(cards, ProjectExplorerQueryTest.keys(cards), null, "", ProjectExplorerQuery.parse(null), "name", 0, "DRAFT", 500, 24);
		ProjectExplorerQueryTest.check("an offset past the end gives an empty page", past.groups.get("DRAFT").page.isEmpty());
	}

	private static void sortNaturalAndFlipped()
	{
		final List<ProjectExplorerPage.Card> cards = new ArrayList<ProjectExplorerPage.Card>();
		final ProjectExplorerPage.Card a = ProjectExplorerQueryTest.card("A", "bravo", "DRAFT", "CONSOLIDATION", "GREENFIELD", "x", "S1");
		a.createdAt = new Date(1000L);
		final ProjectExplorerPage.Card b = ProjectExplorerQueryTest.card("B", "Alpha", "DRAFT", "CONSOLIDATION", "GREENFIELD", "x", "S1", "S2", "S3");
		b.createdAt = null;
		final ProjectExplorerPage.Card c = ProjectExplorerQueryTest.card("C", "charlie", "DRAFT", "CONSOLIDATION", "GREENFIELD", "x", "S1", "S2");
		c.createdAt = new Date(3000L);
		cards.add(a);
		cards.add(b);
		cards.add(c);
		ProjectExplorerQueryTest.check("name A-Z by default", "BAC".equals(ProjectExplorerQueryTest.order(cards, "name", 0)));
		ProjectExplorerQueryTest.check("name flipped", "CAB".equals(ProjectExplorerQueryTest.order(cards, "name", -1)));
		ProjectExplorerQueryTest.check("created newest first by default, no date last", "CAB".equals(ProjectExplorerQueryTest.order(cards, "created", 0)));
		ProjectExplorerQueryTest.check("created flipped", "BAC".equals(ProjectExplorerQueryTest.order(cards, "created", 1)));
		ProjectExplorerQueryTest.check("sources most first by default", "BCA".equals(ProjectExplorerQueryTest.order(cards, "sources", 0)));
		ProjectExplorerQueryTest.check("an unknown sort reads as name", "BAC".equals(ProjectExplorerQueryTest.order(cards, "drop table", 0)));
		final List<ProjectExplorerPage.Card> ties = new ArrayList<ProjectExplorerPage.Card>();
		for (final String code : new String[] { "T3", "T1", "T2" })
		{
			ties.add(ProjectExplorerQueryTest.card(code, "same", "DRAFT", "CONSOLIDATION", "GREENFIELD", "x"));
		}
		ProjectExplorerQueryTest.check("ties keep the index order", "T3T1T2".equals(ProjectExplorerQueryTest.order(ties, "name", 0)));
	}

	private static void limitIsCapped()
	{
		final List<ProjectExplorerPage.Card> cards = new ArrayList<ProjectExplorerPage.Card>();
		for (int i = 0; i < 200; i++)
		{
			cards.add(ProjectExplorerQueryTest.card("D" + i, "D " + i, "DRAFT", "CONSOLIDATION", "GREENFIELD", "a"));
		}
		final ProjectExplorerQuery.Result r = ProjectExplorerQuery.run(cards, ProjectExplorerQueryTest.keys(cards), null, "", ProjectExplorerQuery.parse(null), "name", 0, null, 0, 100000);
		ProjectExplorerQueryTest.check("at most " + ProjectExplorerQuery.MAX_LIMIT + " cards per group", r.groups.get("DRAFT").page.size() == ProjectExplorerQuery.MAX_LIMIT);
	}

	private static void unknownStatusIsCountedButNotShown()
	{
		final List<ProjectExplorerPage.Card> cards = new ArrayList<ProjectExplorerPage.Card>();
		cards.add(ProjectExplorerQueryTest.card("X", "Odd", "WEIRD", "CONSOLIDATION", "GREENFIELD", "a"));
		final ProjectExplorerQuery.Result r = ProjectExplorerQueryTest.run(cards, ProjectExplorerQueryTest.keys(cards), "", "");
		ProjectExplorerQueryTest.check("counted in the total, in no group (as the page did)", r.total == 1 && r.groups.get("DRAFT").count == 0);
	}

	// ------------------------------------------------------------------------------------------------------ helpers

	private static String order(final List<ProjectExplorerPage.Card> pCards, final String pSort, final int pDir)
	{
		final ProjectExplorerQuery.Result r = ProjectExplorerQuery.run(pCards, ProjectExplorerQueryTest.keys(pCards), null, "", ProjectExplorerQuery.parse(null), pSort, pDir, null, 0, 96);
		final StringBuilder out = new StringBuilder();
		for (final ProjectExplorerPage.Card card : r.groups.get("DRAFT").page)
		{
			out.append(card.code);
		}
		return out.toString();
	}

	private static ProjectExplorerQuery.Result run(final List<ProjectExplorerPage.Card> pCards, final List<String> pKeys, final String pText, final String pSelection)
	{
		return ProjectExplorerQuery.run(pCards, pKeys, Collections.<String> emptySet(), pText, ProjectExplorerQuery.parse(pSelection), "name", 0, null, 0, 12);
	}

	private static List<String> keys(final List<ProjectExplorerPage.Card> pCards)
	{
		final List<String> out = new ArrayList<String>();
		for (final ProjectExplorerPage.Card card : pCards)
		{
			out.add(ProjectExplorerQuery.searchKey(card));
		}
		return out;
	}

	private static ProjectExplorerPage.Card card(final String pCode, final String pLabel, final String pStatus, final String pStyle, final String pMode, final String pOwner,
		final String... pSystems)
	{
		final ProjectExplorerPage.Card card = new ProjectExplorerPage.Card();
		card.code = pCode;
		card.key = pCode;
		card.label = pLabel;
		card.sortName = pLabel.toLowerCase(Locale.ROOT);
		card.status = pStatus;
		card.style = pStyle;
		card.mode = pMode;
		card.owner = pOwner;
		card.sourceSystems.addAll(Arrays.asList(pSystems));
		card.sourceCount = pSystems.length;
		card.hasLanding = true;
		card.hasStaging = pCode.startsWith("A");
		return card;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
