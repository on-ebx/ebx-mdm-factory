package com.on.ebx.factory.master;

/**
 * Lot M-INC (2026-10-12, docs/VOLUME.md "Lot M-INC - incremental master load"): the pure decisions behind the scope of a master load. A load either reads the
 * WHOLE staging table of an entity ({@link #FULL}, the behaviour of lots V3-M, V3-M2 and V3-M3, unchanged) or only the staging rows the generated journal triggers
 * recorded as created or modified since the last successful load ({@link #CHANGED}). No EBX class here: {@code ebx.MasterLoader} hands over plain facts, so the whole
 * decision table is unit-tested ({@code MasterLoadScopeTest}).
 * <p>
 * <b>{@link #CHANGED} is a REQUEST, never a promise.</b> {@link #decide} answers {@link Decision#changed} only when every condition that makes the journal a complete
 * account of what changed holds, and otherwise says in {@link Decision#reason} why the entity is loaded in full - the load never fails, and never silently misses a
 * row, because the journal is missing or stale:
 * <ol>
 * <li>the journal dataset exists, the generated triggers are installed on the entity's staging table and could be verified ({@link Facts#journalUsable});</li>
 * <li>they were not installed by THIS load (rows written before they existed were not recorded) ({@link Facts#installedNow});</li>
 * <li>a full load of the entity finished since recording started and nothing disarmed it since ({@link Facts#armed}) - this is also "the last load of the entity
 * did not fail": a full load disarms the entity when it starts and arms it again only when it ends;</li>
 * <li>what the load copies is the plan the armed load copied ({@link Facts#fingerprintMatches}): a field promoted since, a foreign key changed, a REJECT script rule
 * added or a master field that appeared in the published model all change the result for staging rows that did NOT change;</li>
 * <li>the master table is not empty ({@link Facts#masterEmpty});</li>
 * <li>the entity has no deferred (self-reference) foreign key ({@link Facts#deferredReferences}): its second pass reads the whole staging table anyway;</li>
 * <li>the journal does not hold more rows than {@link #MAX_JOURNAL_ROWS} ({@link Facts#journalRows}).</li>
 * </ol>
 */
public final class MasterLoadScope
{
	public static final String FULL = "full";
	public static final String CHANGED = "changed";

	/** The JVM property naming the scope used when a caller names none: {@link #FULL} (default) or {@link #CHANGED}. Read at each load. */
	public static final String DEFAULT_SCOPE_PROPERTY = "mdm.factory.masterLoad.defaultScope";
	/** The JVM property bounding the journal rows read by primary-key lookups; above it the staging table is scanned once and filtered. */
	public static final String LOOKUP_MAX_PROPERTY = "mdm.factory.masterLoad.journalLookupMax";
	/** The JVM property that switches the whole journal off ({@code false}): no trigger installed, no row read, every load full. */
	public static final String JOURNAL_ENABLED_PROPERTY = "mdm.factory.masterLoad.journalEnabled";

	/** 20,000 journal rows: read by primary key (a few ms each at worst); more is cheaper as one filtered streaming scan. */
	public static final long DEFAULT_LOOKUP_MAX = 20000L;
	/**
	 * The journal is a set of keys and counters held in memory while a load runs (about 250 bytes a row: the staging key, the two key fields, the counter, the map and
	 * set nodes): above this many rows the entity is loaded in full - 2,000,000 rows are about 500 MB, and a full scan of that many rows costs less than the
	 * bookkeeping of a journal that size.
	 */
	public static final long MAX_JOURNAL_ROWS = 2000000L;
	/** A FULL load snapshot-reads the journal to consume it afterwards (an optimisation, never needed for correctness): above this many rows it leaves the journal alone. */
	public static final long MAX_SNAPSHOT_ROWS = 1000000L;

	private MasterLoadScope()
	{
	}

	// ------------------------------------------------------------------------------------------------------ requested

	/**
	 * The scope a caller asked for, as {@link #FULL} or {@link #CHANGED}: {@code changed} (any case, whitespace tolerated) is the only value that asks for the
	 * journal; a blank value takes {@code pDefaultFromProperty} (the {@link #DEFAULT_SCOPE_PROPERTY} value, itself parsed the same way); anything else - a typo - is
	 * {@link #FULL}, never silently promoted to the lighter read (same convention as {@code transfer.IncrementalTransfer#isAllScope}).
	 */
	public static String parse(final String pScope, final String pDefaultFromProperty)
	{
		final String scope = pScope == null ? "" : pScope.trim();
		if (scope.isEmpty())
		{
			return MasterLoadScope.CHANGED.equalsIgnoreCase(pDefaultFromProperty == null ? "" : pDefaultFromProperty.trim()) ? MasterLoadScope.CHANGED : MasterLoadScope.FULL;
		}
		return MasterLoadScope.CHANGED.equalsIgnoreCase(scope) ? MasterLoadScope.CHANGED : MasterLoadScope.FULL;
	}

	/** {@link #parse} with the property read now. */
	public static String requested(final String pScope)
	{
		return MasterLoadScope.parse(pScope, System.getProperty(MasterLoadScope.DEFAULT_SCOPE_PROPERTY));
	}

	/** The lookup bound from a property value: the default when missing or not a whole number; zero or less means "always scan". */
	public static long parseLookupMax(final String pPropertyValue)
	{
		if (pPropertyValue == null || pPropertyValue.trim().isEmpty())
		{
			return MasterLoadScope.DEFAULT_LOOKUP_MAX;
		}
		try
		{
			return Long.parseLong(pPropertyValue.trim());
		}
		catch (final NumberFormatException ex)
		{
			return MasterLoadScope.DEFAULT_LOOKUP_MAX;
		}
	}

	public static long lookupMax()
	{
		return MasterLoadScope.parseLookupMax(System.getProperty(MasterLoadScope.LOOKUP_MAX_PROPERTY));
	}

	/** {@code false} only for an explicit {@code false} (any case); missing, blank or anything else leaves the journal on. */
	public static boolean parseJournalEnabled(final String pPropertyValue)
	{
		return pPropertyValue == null || !"false".equalsIgnoreCase(pPropertyValue.trim());
	}

	public static boolean journalEnabled()
	{
		return MasterLoadScope.parseJournalEnabled(System.getProperty(MasterLoadScope.JOURNAL_ENABLED_PROPERTY));
	}

	// ----------------------------------------------------------------------------------------------------------- decide

	/** What {@code ebx.MasterLoader} knows about one entity when it chooses. */
	public static final class Facts
	{
		/** The journal dataset exists, the triggers are installed on this entity's table and were verified. */
		public boolean journalUsable;
		/** Why it is not usable (null when it is). */
		public String journalUnusableReason;
		/** The triggers were installed, or the entity's recording started, by this very load. */
		public boolean installedNow;
		/** {@code Control.armed} of the entity. */
		public boolean armed;
		/** The stored plan fingerprint equals the one of this load. */
		public boolean fingerprintMatches;
		/** The entity's master table holds no row. */
		public boolean masterEmpty;
		/** The entity's plan has a deferred (self-reference / cycle) foreign key. */
		public boolean deferredReferences;
		/** Journal rows of the entity (plus its open MASTER rejects, which are re-read too). */
		public long journalRows;

		public Facts usable()
		{
			this.journalUsable = true;
			return this;
		}
	}

	/** The scope an entity is loaded in, and why it is not {@link #CHANGED} when {@link #CHANGED} was asked. */
	public static final class Decision
	{
		/** True: read only the journal's rows. */
		public final boolean changed;
		/** Empty when {@link #changed}, or when {@link #FULL} was asked; otherwise the first condition that is not met. */
		public final String reason;

		Decision(final boolean pChanged, final String pReason)
		{
			this.changed = pChanged;
			this.reason = pReason == null ? "" : pReason;
		}

		/** {@link #CHANGED} or {@link #FULL}, for the step detail. */
		public String scope()
		{
			return this.changed ? MasterLoadScope.CHANGED : MasterLoadScope.FULL;
		}
	}

	/** The decision table; see the class Javadoc for the order. */
	public static Decision decide(final String pRequested, final Facts pFacts)
	{
		if (!MasterLoadScope.CHANGED.equals(pRequested))
		{
			return new Decision(false, "");
		}
		if (!pFacts.journalUsable)
		{
			return new Decision(false, "the journal is not usable: " + (pFacts.journalUnusableReason == null ? "unknown reason" : pFacts.journalUnusableReason));
		}
		if (pFacts.installedNow)
		{
			return new Decision(false, "the journal triggers were installed by this load: rows written before it were not recorded");
		}
		if (!pFacts.armed)
		{
			return new Decision(false, "no full load of this entity finished since recording started (or the last one did not finish)");
		}
		if (!pFacts.fingerprintMatches)
		{
			return new Decision(false, "what the load copies changed since the last full load (fields, foreign keys, REJECT rules or master fields)");
		}
		if (pFacts.masterEmpty)
		{
			return new Decision(false, "the master table is empty");
		}
		if (pFacts.deferredReferences)
		{
			return new Decision(false, "the entity has deferred foreign keys (self-reference): its second pass reads the whole staging table");
		}
		if (pFacts.journalRows > MasterLoadScope.MAX_JOURNAL_ROWS)
		{
			return new Decision(false, "the journal holds " + pFacts.journalRows + " rows, above " + MasterLoadScope.MAX_JOURNAL_ROWS);
		}
		return new Decision(true, "");
	}

	// ---------------------------------------------------------------------------------------------------------- read mode

	/** How a {@link #CHANGED} entity reads its staging rows ({@link #SCAN_ALL} = a {@link #FULL} entity). */
	public enum ReadMode
	{
		/** Every staging row (scope full). */
		SCAN_ALL,
		/** One lookup by primary key per journal row. */
		KEYS,
		/** One streaming read of the staging table, keeping the rows whose key is in the journal. */
		FILTERED_SCAN
	}

	/** {@code KEYS} while the journal is at most {@code pLookupMax} rows, else {@code FILTERED_SCAN}; {@code pLookupMax} zero or less always scans. */
	public static ReadMode readMode(final boolean pChanged, final long pJournalRows, final long pLookupMax)
	{
		if (!pChanged)
		{
			return ReadMode.SCAN_ALL;
		}
		return pLookupMax > 0L && pJournalRows <= pLookupMax ? ReadMode.KEYS : ReadMode.FILTERED_SCAN;
	}
}
