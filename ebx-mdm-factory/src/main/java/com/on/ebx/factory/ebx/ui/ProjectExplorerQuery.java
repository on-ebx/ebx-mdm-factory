package com.on.ebx.factory.ebx.ui;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The project explorer's search, facet counts, sort and paging, on the server (2026-10-11, server-paged explorer): what
 * {@link FacetedPage#script} used to do in the browser over every card of the page, done here over the cards of a {@link
 * ProjectIndex}, so the browser only ever receives one page of cards per status group plus the counts. Pure: no EBX type,
 * fixture-tested by {@code ProjectExplorerQueryTest}.
 * <p>
 * Same rules as the browser script it replaces: every word typed must appear in the card's search text, in any order,
 * ignoring case, accents, spaces, punctuation and a final plural "s"; filters combine as OR inside a facet and AND between
 * facets; a facet value counts the cards that pass the search and every OTHER facet (classic faceted counting: a card that
 * fails exactly one facet still counts for that facet's values); groups are the statuses of {@link #STATUS_ORDER}, each
 * sorted by the chosen criterion ({@code name} A&rarr;Z, {@code created} and {@code sources} highest first, unless the
 * direction is flipped), ties kept in the order of the index (the table order the page used to show).
 */
final class ProjectExplorerQuery
{
	/** Facet keys, as the page names them ({@code data-filter}). {@code source} and {@code zone} are multi-valued. */
	static final String[] KEYS = { "fav", "status", "style", "mode", "owner", "source", "zone" };
	/** The statuses, in display order - {@code Project.status}'s enumeration. */
	static final String[] STATUS_ORDER = ProjectExplorerPage.STATUS_ORDER;
	/** The groups, in display order: the statuses, then the cards whose status is hidden for the user ({@link ProjectView#HIDDEN_STATUS}, 2026-10-14). */
	static final String[] GROUP_ORDER = ProjectExplorerQuery.groups();
	/** The largest page a request may ask for. */
	static final int MAX_LIMIT = 96;

	private static final Pattern MARKS = Pattern.compile("\\p{M}+");
	private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

	private ProjectExplorerQuery()
	{
	}

	/** One status group of an answer: how many cards of the result are in it, and the requested slice of them. */
	static final class Group
	{
		final String status;
		int count;
		final List<ProjectExplorerPage.Card> page = new ArrayList<ProjectExplorerPage.Card>();

		Group(final String pStatus)
		{
			this.status = pStatus;
		}
	}

	static final class Result
	{
		/** Cards of the index. */
		int all;
		/** Cards that pass the search and every facet. */
		int total;
		/** Per facet key, value &rarr; number of cards that pass the search and every other facet. */
		final Map<String, Map<String, Integer>> facets = new LinkedHashMap<String, Map<String, Integer>>();
		/** Per group of {@link #GROUP_ORDER}, in that order (empty groups included, count 0). */
		final Map<String, Group> groups = new LinkedHashMap<String, Group>();
	}

	// ------------------------------------------------------------------------------------------------------ text

	/** Lower case, accents removed - the browser's {@code ftFold}. */
	static String fold(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		return ProjectExplorerQuery.MARKS.matcher(Normalizer.normalize(pText, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
	}

	/** The words of a search: folded, split on anything that is not a letter or a digit, a final "s" dropped from a word of more than three letters - the browser's {@code ftTerms}. */
	static List<String> terms(final String pText)
	{
		final List<String> out = new ArrayList<String>();
		for (final String word : ProjectExplorerQuery.NON_WORD.split(ProjectExplorerQuery.fold(pText)))
		{
			if (word.isEmpty())
			{
				continue;
			}
			out.add(word.length() > 3 && word.charAt(word.length() - 1) == 's' ? word.substring(0, word.length() - 1) : word);
		}
		return out;
	}

	/**
	 * What a card is searched in: label, description, code, owner and source systems, each folded with every non-letter non-digit removed (the browser's
	 * {@code ftMatch} key), then joined with a space. Since 2026-10-14 (EBX permissions) a word must be found inside ONE field, never across two, so a
	 * field blanked for the user ({@code ProjectView.mask}) can never make a card match, not even by completing a word started in another field. The
	 * separator is safe: a search term never contains a space ({@link #terms}).
	 */
	static String searchKey(final ProjectExplorerPage.Card pCard)
	{
		final StringBuilder text = new StringBuilder();
		ProjectExplorerQuery.appendField(text, pCard.label);
		ProjectExplorerQuery.appendField(text, pCard.description);
		ProjectExplorerQuery.appendField(text, pCard.code);
		ProjectExplorerQuery.appendField(text, pCard.owner);
		for (final String system : pCard.sourceSystems)
		{
			ProjectExplorerQuery.appendField(text, system);
		}
		return text.toString();
	}

	private static void appendField(final StringBuilder pOut, final String pValue)
	{
		final String compact = ProjectExplorerQuery.NON_WORD.matcher(ProjectExplorerQuery.fold(pValue)).replaceAll("");
		if (compact.isEmpty())
		{
			return;
		}
		if (pOut.length() > 0)
		{
			pOut.append(' ');
		}
		pOut.append(compact);
	}

	private static String[] groups()
	{
		final String[] out = new String[ProjectExplorerPage.STATUS_ORDER.length + 1];
		System.arraycopy(ProjectExplorerPage.STATUS_ORDER, 0, out, 0, ProjectExplorerPage.STATUS_ORDER.length);
		out[out.length - 1] = ProjectView.HIDDEN_STATUS;
		return out;
	}

	// ----------------------------------------------------------------------------------------------------- facets

	/**
	 * The selected facet values, from {@code "status=DRAFT|LIVE;owner=alice"}; an unknown key, an empty value and a blank
	 * selection are ignored. A value may not contain {@code |} or {@code ;}: the page sends values URL-encoded inside one
	 * parameter, and the separators are the only characters reserved (a value carrying one simply never matches).
	 */
	static Map<String, Set<String>> parse(final String pSelection)
	{
		final Map<String, Set<String>> out = new HashMap<String, Set<String>>();
		if (pSelection == null || pSelection.isEmpty())
		{
			return out;
		}
		for (final String part : pSelection.split(";"))
		{
			final int eq = part.indexOf('=');
			if (eq <= 0 || ProjectExplorerQuery.keyIndex(part.substring(0, eq)) < 0)
			{
				continue;
			}
			final Set<String> values = new LinkedHashSet<String>();
			for (final String value : part.substring(eq + 1).split("\\|"))
			{
				if (!value.isEmpty())
				{
					values.add(value);
				}
			}
			if (!values.isEmpty())
			{
				out.put(part.substring(0, eq), values);
			}
		}
		return out;
	}

	private static int keyIndex(final String pKey)
	{
		for (int i = 0; i < ProjectExplorerQuery.KEYS.length; i++)
		{
			if (ProjectExplorerQuery.KEYS[i].equals(pKey))
			{
				return i;
			}
		}
		return -1;
	}

	/** The values a card carries for each of {@link #KEYS} (the card's old {@code data-*} attributes); {@code pFavorite} is the session's own pin. */
	static List<List<String>> values(final ProjectExplorerPage.Card pCard, final boolean pFavorite)
	{
		final List<List<String>> out = new ArrayList<List<String>>(ProjectExplorerQuery.KEYS.length);
		out.add(Collections.singletonList(pFavorite ? "1" : "0"));
		// an empty value (never set, or blanked because it is hidden for the user, 2026-10-14) is no facet value: it is neither offered nor counted
		out.add(ProjectExplorerQuery.single(pCard.status));
		out.add(ProjectExplorerQuery.single(pCard.style));
		out.add(ProjectExplorerQuery.single(pCard.mode));
		out.add(ProjectExplorerQuery.single(pCard.owner));
		out.add(pCard.sourceSystems);
		final List<String> zones = new ArrayList<String>(3);
		if (pCard.hasLanding)
		{
			zones.add("landing");
		}
		if (pCard.hasStaging)
		{
			zones.add("staging");
		}
		if (pCard.hasMaster)
		{
			zones.add("master");
		}
		out.add(zones);
		return out;
	}

	private static List<String> single(final String pValue)
	{
		return pValue == null || pValue.isEmpty() ? Collections.<String> emptyList() : Collections.singletonList(pValue);
	}

	// ------------------------------------------------------------------------------------------------------ query

	/**
	 * One answer. {@code pSearchKeys} is parallel to {@code pCards} ({@link #searchKey} of each, computed once by the index);
	 * {@code pSort} is {@code name}, {@code created} or {@code sources} (anything else reads as {@code name}); {@code pDir} is
	 * {@code 1} (ascending), {@code -1} (descending) or {@code 0} (the criterion's natural direction). {@code pGroup} null or
	 * empty pages every group from {@code pOff}; otherwise only that group's page is filled (the "show more" of one group),
	 * the counts are still those of every group.
	 */
	static Result run(final List<ProjectExplorerPage.Card> pCards, final List<String> pSearchKeys, final Set<String> pFavorites, final String pText,
		final Map<String, Set<String>> pSelection, final String pSort, final int pDir, final String pGroup, final int pOff, final int pLimit)
	{
		final Result out = new Result();
		out.all = pCards.size();
		for (final String key : ProjectExplorerQuery.KEYS)
		{
			out.facets.put(key, new HashMap<String, Integer>());
		}
		final Map<String, List<ProjectExplorerPage.Card>> matches = new LinkedHashMap<String, List<ProjectExplorerPage.Card>>();
		for (final String status : ProjectExplorerQuery.GROUP_ORDER)
		{
			out.groups.put(status, new Group(status));
			matches.put(status, new ArrayList<ProjectExplorerPage.Card>());
		}
		final List<String> terms = ProjectExplorerQuery.terms(pText);
		final Set<String> favorites = pFavorites == null ? Collections.<String> emptySet() : pFavorites;
		for (int c = 0; c < pCards.size(); c++)
		{
			final ProjectExplorerPage.Card card = pCards.get(c);
			if (!ProjectExplorerQuery.matches(pSearchKeys.get(c), terms))
			{
				continue;
			}
			final List<List<String>> values = ProjectExplorerQuery.values(card, favorites.contains(card.key));
			int failures = 0;
			int failed = -1;
			for (int k = 0; k < ProjectExplorerQuery.KEYS.length && failures < 2; k++)
			{
				final Set<String> wanted = pSelection.get(ProjectExplorerQuery.KEYS[k]);
				if (wanted == null)
				{
					continue;
				}
				boolean hit = false;
				for (final String value : values.get(k))
				{
					if (wanted.contains(value))
					{
						hit = true;
						break;
					}
				}
				if (!hit)
				{
					failures++;
					failed = k;
				}
			}
			if (failures > 1)
			{
				continue;
			}
			for (int k = 0; k < ProjectExplorerQuery.KEYS.length; k++)
			{
				if (failures == 0 || k == failed)
				{
					final Map<String, Integer> counts = out.facets.get(ProjectExplorerQuery.KEYS[k]);
					for (final String value : values.get(k))
					{
						final Integer n = counts.get(value);
						counts.put(value, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
					}
				}
			}
			if (failures == 0)
			{
				out.total++;
				final List<ProjectExplorerPage.Card> group = matches.get(card.status);
				if (group != null)
				{
					group.add(card); // a status outside the enumeration is counted, never shown - as the page did (no group for it)
				}
			}
		}
		final Comparator<ProjectExplorerPage.Card> order = ProjectExplorerQuery.comparator(pSort, pDir);
		final int off = Math.max(0, pOff);
		final int limit = Math.max(1, Math.min(ProjectExplorerQuery.MAX_LIMIT, pLimit));
		for (final Map.Entry<String, List<ProjectExplorerPage.Card>> e : matches.entrySet())
		{
			final List<ProjectExplorerPage.Card> list = e.getValue();
			final Group group = out.groups.get(e.getKey());
			group.count = list.size();
			if (pGroup != null && !pGroup.isEmpty() && !pGroup.equals(e.getKey()))
			{
				continue;
			}
			Collections.sort(list, order); // stable: ties keep the index order
			group.page.addAll(list.subList(Math.min(off, list.size()), Math.min(off + limit, list.size())));
		}
		return out;
	}

	private static boolean matches(final String pSearchKey, final List<String> pTerms)
	{
		for (final String term : pTerms)
		{
			if (pSearchKey.indexOf(term) < 0)
			{
				return false;
			}
		}
		return true;
	}

	/** The browser's order: {@code data-name} (the label in lower case) compared as plain strings, the two figures numerically; a missing creation date reads 0. */
	static Comparator<ProjectExplorerPage.Card> comparator(final String pSort, final int pDir)
	{
		final String sort = "created".equals(pSort) || "sources".equals(pSort) ? pSort : "name";
		final int dir = pDir != 0 ? (pDir > 0 ? 1 : -1) : ("name".equals(sort) ? 1 : -1);
		if ("created".equals(sort))
		{
			return new Comparator<ProjectExplorerPage.Card>()
			{
				@Override
				public int compare(final ProjectExplorerPage.Card pA, final ProjectExplorerPage.Card pB)
				{
					return dir * Long.compare(pA.createdAt == null ? 0L : pA.createdAt.getTime(), pB.createdAt == null ? 0L : pB.createdAt.getTime());
				}
			};
		}
		if ("sources".equals(sort))
		{
			return new Comparator<ProjectExplorerPage.Card>()
			{
				@Override
				public int compare(final ProjectExplorerPage.Card pA, final ProjectExplorerPage.Card pB)
				{
					return dir * Integer.compare(pA.sourceCount, pB.sourceCount);
				}
			};
		}
		return new Comparator<ProjectExplorerPage.Card>()
		{
			@Override
			public int compare(final ProjectExplorerPage.Card pA, final ProjectExplorerPage.Card pB)
			{
				return dir * pA.sortName.compareTo(pB.sortName);
			}
		};
	}
}
