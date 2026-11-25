package com.on.ebx.factory.ebx;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.factory.wizard.SourceOnboarding;

/**
 * Wave 11 (audit F-48): the last {@link SourceOnboarding.Facts} read for each connection, with the time it was read. {@code SourceOnboarder.facts} costs up to three
 * Connectors round trips (describe, catalog-source plan, mappings), and the wizard used to pay them again at every display, for every application card still
 * in its 15-minute window. Every live read now leaves its result here - the onboarding job reads it several times while it runs, the person's own
 * actions read it too - and a display only looks it up: a fact that is missing or too old is asked for again off the display's thread
 * ({@code SourceOnboarder#refreshInBackground}), and the card says "checking" until it comes.
 * <p>
 * Pure (no EBX type, the clock is injected) so it is tested offline. Bounded: the oldest entry goes when {@link #MAX_ENTRIES} is passed (a connection
 * list is a handful, never thousands - the bound only keeps a misuse from growing without end).
 */
final class SourceFactsCache
{
	static final int MAX_ENTRIES = 200;

	/** What the cache holds for one connection: the facts and the instant (clock millis) they were read. */
	static final class Entry
	{
		final SourceOnboarding.Facts facts;
		final long readAt;

		Entry(final SourceOnboarding.Facts pFacts, final long pReadAt)
		{
			this.facts = pFacts;
			this.readAt = pReadAt;
		}
	}

	/** A source of "now" in milliseconds; {@code System::currentTimeMillis} outside the tests. */
	interface Clock
	{
		long now();
	}

	private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
	private final Clock clock;

	SourceFactsCache(final Clock pClock)
	{
		this.clock = pClock;
	}

	synchronized void put(final String pConnectorId, final SourceOnboarding.Facts pFacts)
	{
		if (pConnectorId == null || pConnectorId.isEmpty() || pFacts == null)
		{
			return;
		}
		this.entries.remove(pConnectorId); // re-insert: the map's order is the age order
		this.entries.put(pConnectorId, new Entry(pFacts, this.clock.now()));
		while (this.entries.size() > SourceFactsCache.MAX_ENTRIES)
		{
			final Iterator<String> oldest = this.entries.keySet().iterator();
			oldest.next();
			oldest.remove();
		}
	}

	/** The entry of {@code pConnectorId}, or {@code null} when nothing was read since the module started (or since {@link #forget}). */
	synchronized Entry get(final String pConnectorId)
	{
		return this.entries.get(pConnectorId);
	}

	/** How long ago {@code pEntry} was read, in milliseconds (never negative). */
	long ageMillis(final Entry pEntry)
	{
		return Math.max(0L, this.clock.now() - pEntry.readAt);
	}

	/** Whether {@code pEntry} is no older than {@code pMaxAgeMillis}. */
	boolean fresh(final Entry pEntry, final long pMaxAgeMillis)
	{
		return pEntry != null && this.ageMillis(pEntry) <= pMaxAgeMillis;
	}

	synchronized void forget(final String pConnectorId)
	{
		this.entries.remove(pConnectorId);
	}

	synchronized int size()
	{
		return this.entries.size();
	}
}
