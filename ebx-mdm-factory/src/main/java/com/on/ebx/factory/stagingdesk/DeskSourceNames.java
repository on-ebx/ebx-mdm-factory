package com.on.ebx.factory.stagingdesk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.mappingmatrix.MatrixModel;

/**
 * Validation desk (docs/STAGING-DESK.md, "Live fixes, round 2"): how the desk names a source. A project source created from a landing dataset often carries that dataset's name
 * as its system ({@code PERSONNESSAPSAMPLE} for the table {@code Personnes_SAP_Sample}), which is not what a person says ("SAP"). When the system only repeats its landing
 * table's name, the desk shows the words of the table name that tell the sources of the project APART (the words every source's table shares - "Personnes", "Sample" - are
 * dropped): measured on the project's own tables, no domain list. Any other system name is shown as it is. Pure, unit-tested ({@code DeskSourceNamesTest}).
 */
public final class DeskSourceNames
{
	private DeskSourceNames()
	{
	}

	/** Source id to the name the desk prints. */
	public static Map<String, String> of(final MatrixModel.Project pProject)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pProject == null)
		{
			return out;
		}
		final Map<String, String> tableOf = new LinkedHashMap<String, String>();
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			for (final MatrixModel.Feed feed : entity.feeds)
			{
				if (feed.sourceId != null && feed.landingTableName != null && !feed.landingTableName.isEmpty() && !tableOf.containsKey(feed.sourceId))
				{
					tableOf.put(feed.sourceId, feed.landingTableName);
				}
			}
		}
		// the words shared by the tables of every source
		Set<String> shared = null;
		for (final String table : tableOf.values())
		{
			final Set<String> words = new LinkedHashSet<String>();
			for (final String word : DeskSourceNames.words(table))
			{
				words.add(word.toLowerCase(Locale.ROOT));
			}
			if (shared == null)
			{
				shared = words;
			}
			else
			{
				shared.retainAll(words);
			}
		}
		for (final MatrixModel.Source source : pProject.sources)
		{
			String name = source.sourceSystem == null ? "" : source.sourceSystem;
			final String table = tableOf.get(source.id);
			if (table != null && shared != null && tableOf.size() > 1 && DeskSourceNames.squash(name).equals(DeskSourceNames.squash(table)))
			{
				final List<String> kept = new ArrayList<String>();
				for (final String word : DeskSourceNames.words(table))
				{
					if (!shared.contains(word.toLowerCase(Locale.ROOT)))
					{
						kept.add(word);
					}
				}
				if (!kept.isEmpty())
				{
					name = String.join(" ", kept);
				}
			}
			out.put(source.id, name);
		}
		return out;
	}

	/** The name of one source; the table name of the feed when the source is unknown. */
	public static String name(final MatrixModel.Project pProject, final MatrixModel.Feed pFeed)
	{
		final String name = DeskSourceNames.of(pProject).get(pFeed.sourceId);
		return name == null ? pFeed.landingTableName : name;
	}

	private static List<String> words(final String pTable)
	{
		final List<String> out = new ArrayList<String>();
		for (final String word : pTable.split("[^A-Za-z0-9]+"))
		{
			if (!word.isEmpty())
			{
				out.add(word);
			}
		}
		return out;
	}

	private static String squash(final String pText)
	{
		return pText.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
	}
}
