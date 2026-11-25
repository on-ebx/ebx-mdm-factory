package com.on.ebx.factory.capability;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory cache of the source text of published script libraries, so that the Mapping matrix can show what a
 * computed field's library function does without asking the scripting server on every render
 * (fix/matrix-computed-field, docs/MAPPING-MATRIX.md sec 14). Pure - the network call is the injected {@link
 * Fetcher} ({@code ebx.LibraryDocs} gives it the {@code scripting} MCP client), the clock is injected too, so the
 * rules are unit-tested without EBX.
 * <p>
 * Rules, per library idePath (one cache entry each):
 * <ul>
 * <li>an entry younger than {@link #freshMillis} is used as it is: <b>no call at all</b> - every postback of the
 * screen re-renders it, a person clicking around never reaches the server;</li>
 * <li>an older entry is revalidated with ONE metadata call (no source - {@code get_script(includeSource:false)}
 * answers the {@code revisionId}): same revision, the entry is kept and its age restarts; a new revision, ONE more
 * call for the source;</li>
 * <li>no entry: ONE call with the source (it carries the revision too);</li>
 * <li>a failed call is remembered for {@link #freshMillis} as well (a screen must not wait for a dead server on
 * every click); a previously good entry is kept and served stale rather than replaced by the failure.</li>
 * </ul>
 * So one render makes at most one call per library (two when the library changed since the last look), and most
 * renders none.
 */
public final class LibraryDocCache
{
	/** What one fetch answers: the revision of the library and, when asked for, its source. */
	public static final class Fetched
	{
		public final String revisionId;
		/** Null when the fetch was made without the source. */
		public final String source;

		public Fetched(final String pRevisionId, final String pSource)
		{
			this.revisionId = pRevisionId == null ? "" : pRevisionId;
			this.source = pSource;
		}
	}

	/** The one place that reaches the scripting server. */
	public interface Fetcher
	{
		/** @throws Exception any failure (server unusable, script unknown, answer not readable) - its message is shown to the person. */
		Fetched fetch(String pLibraryIdePath, boolean pIncludeSource) throws Exception;
	}

	/** What {@link #lookup} answers: the source, or why there is none. */
	public static final class Lookup
	{
		public final String source;
		/** {@code ""} when {@link #source} is set. */
		public final String problem;

		Lookup(final String pSource, final String pProblem)
		{
			this.source = pSource;
			this.problem = pProblem == null ? "" : pProblem;
		}
	}

	private static final class Entry
	{
		String revisionId = "";
		String source;
		String problem = "";
		long checkedAt;
	}

	private final Fetcher fetcher;
	private final long freshMillis;
	private final Map<String, Entry> entries = new HashMap<String, Entry>();

	public LibraryDocCache(final Fetcher pFetcher, final long pFreshMillis)
	{
		this.fetcher = pFetcher;
		this.freshMillis = pFreshMillis;
	}

	/** The library's source as of {@code pNowMillis}, fetching only as the class Javadoc says. Never throws. */
	public Lookup lookup(final String pLibraryIdePath, final long pNowMillis)
	{
		Entry entry;
		synchronized (this.entries)
		{
			entry = this.entries.get(pLibraryIdePath);
		}
		if (entry != null && pNowMillis - entry.checkedAt < this.freshMillis)
		{
			return new Lookup(entry.source, entry.source == null ? entry.problem : "");
		}
		final Entry next = new Entry();
		next.checkedAt = pNowMillis;
		try
		{
			if (entry == null || entry.source == null)
			{
				final Fetched full = this.fetcher.fetch(pLibraryIdePath, true);
				next.revisionId = full.revisionId;
				next.source = full.source;
				next.problem = full.source == null ? "the scripting server answered no source" : "";
			}
			else
			{
				final Fetched meta = this.fetcher.fetch(pLibraryIdePath, false);
				if (meta.revisionId.equals(entry.revisionId) && !entry.revisionId.isEmpty())
				{
					next.revisionId = entry.revisionId;
					next.source = entry.source;
				}
				else
				{
					final Fetched full = this.fetcher.fetch(pLibraryIdePath, true);
					next.revisionId = full.revisionId;
					next.source = full.source;
					next.problem = full.source == null ? "the scripting server answered no source" : "";
				}
			}
		}
		catch (final Exception ex)
		{
			if (entry != null && entry.source != null)
			{
				next.revisionId = entry.revisionId; // served stale rather than replaced by the failure
				next.source = entry.source;
			}
			else
			{
				next.problem = ex.getMessage() == null ? ex.toString() : ex.getMessage();
			}
		}
		synchronized (this.entries)
		{
			this.entries.put(pLibraryIdePath, next);
		}
		return new Lookup(next.source, next.source == null ? next.problem : "");
	}
}
