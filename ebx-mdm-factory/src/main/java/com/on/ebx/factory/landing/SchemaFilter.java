package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether a scanned schema belongs to a {@code ProjectSource.schemaFilter}. The catalog stores a schema
 * name either bare, for a source scanned inside one database ({@code TPCH_SF1}), or database-qualified,
 * for a source that spans several ({@code SNOWFLAKE_LEARNING_DB.ERP_SOURCE}): a filter entry matches
 * either form as long as it names the schema's trailing, whole dot-separated segments - {@code
 * ERP_SOURCE} matches {@code SNOWFLAKE_LEARNING_DB.ERP_SOURCE}, but {@code LEARNING_DB.ERP_SOURCE} does
 * not (segments are whole, never a partial word inside one), and a fully qualified entry
 * ({@code OTHER_DB.ERP_SOURCE}) must match exactly. Comparison is case-insensitive; blank entries are
 * ignored; an empty (or blank-only) filter keeps everything, including a null schema name - a null schema
 * name is kept by nothing else. Pure: no EBX class.
 */
public final class SchemaFilter
{
	private SchemaFilter()
	{
	}

	/** One filter entry and every distinct scanned schema name it matched. */
	public static final class Match
	{
		public final String entry;
		public final List<String> schemas;

		Match(final String pEntry, final List<String> pSchemas)
		{
			this.entry = pEntry;
			this.schemas = pSchemas;
		}
	}

	/** What a filter, checked against a whole source's scanned schemas, is worth reporting back. */
	public static final class Report
	{
		/** Filter entries that matched no scanned schema at all: likely a typo, or a schema the source no longer has. */
		public final List<String> unmatched;
		/** Filter entries that matched more than one distinct schema name - usually the same schema name in several databases. */
		public final List<Match> ambiguous;

		Report(final List<String> pUnmatched, final List<Match> pAmbiguous)
		{
			this.unmatched = pUnmatched;
			this.ambiguous = pAmbiguous;
		}
	}

	/** True when {@code pSchema} (possibly null) belongs to {@code pFilter} (blank entries ignored; empty or blank-only = everything). */
	public static boolean keeps(final List<String> pFilter, final String pSchema)
	{
		final List<String> entries = SchemaFilter.entries(pFilter);
		if (entries.isEmpty())
		{
			return true;
		}
		if (pSchema == null)
		{
			return false;
		}
		for (final String entry : entries)
		{
			if (SchemaFilter.matches(entry, pSchema))
			{
				return true;
			}
		}
		return false;
	}

	/** For a whole source: which filter entries matched nothing, and which matched more than one distinct schema. */
	public static Report report(final List<String> pFilter, final List<String> pScannedSchemas)
	{
		final List<String> entries = SchemaFilter.entries(pFilter);
		final List<String> scanned = pScannedSchemas == null ? Collections.<String>emptyList() : pScannedSchemas;
		final List<String> unmatched = new ArrayList<String>();
		final List<Match> ambiguous = new ArrayList<Match>();
		for (final String entry : entries)
		{
			final Set<String> matched = new LinkedHashSet<String>();
			for (final String schema : scanned)
			{
				if (schema != null && SchemaFilter.matches(entry, schema))
				{
					matched.add(schema);
				}
			}
			if (matched.isEmpty())
			{
				unmatched.add(entry);
			}
			else if (matched.size() > 1)
			{
				ambiguous.add(new Match(entry, new ArrayList<String>(matched)));
			}
		}
		return new Report(unmatched, ambiguous);
	}

	private static List<String> entries(final List<String> pFilter)
	{
		final List<String> out = new ArrayList<String>();
		if (pFilter != null)
		{
			for (final String raw : pFilter)
			{
				if (raw != null && !raw.trim().isEmpty())
				{
					out.add(raw.trim());
				}
			}
		}
		return out;
	}

	/** Same whole trailing run of dot segments, case-insensitive; a shorter schema than the entry can never match. */
	private static boolean matches(final String pEntry, final String pSchema)
	{
		final String[] entrySegments = SchemaFilter.segments(pEntry);
		final String[] schemaSegments = SchemaFilter.segments(pSchema);
		if (entrySegments.length > schemaSegments.length)
		{
			return false;
		}
		final int offset = schemaSegments.length - entrySegments.length;
		for (int i = 0; i < entrySegments.length; i++)
		{
			if (!entrySegments[i].equalsIgnoreCase(schemaSegments[offset + i]))
			{
				return false;
			}
		}
		return true;
	}

	private static String[] segments(final String pName)
	{
		return pName.split("[.]");
	}
}
