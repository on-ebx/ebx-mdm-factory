package com.on.ebx.factory.lineage;

import java.util.Locale;

/**
 * "ONE COLOUR PER SOURCE SYSTEM, from start to end" (task brief, validated mockup {@code
 * docs/mockups/record-lineage.html}/{@code build_record_lineage.py}): a fixed, colour-blind-tolerant
 * palette of 12 hues (distinct in both lightness and hue, never relying on hue alone, and ordered so
 * CONSECUTIVE entries contrast strongly - coordinator review, 2026-09-22, extended from an original 8 the
 * same day after a 9-source fixture showed two lanes sharing the same colour), plus one reserved accent for
 * the golden record itself ({@link #GOLDEN}, never handed out to a source system). Beyond the 12th distinct
 * system, {@link #isExtended(int)} tells the caller to draw that lane with a DASHED/hollow style on top of
 * the (necessarily reused) colour, so two lanes still never look identical even past the palette's own size
 * - see {@code RecordLineageGraphic}'s own Javadoc for exactly which drawing elements that applies to.
 * <p>
 * <b>Two assignment strategies, for two different situations</b>:
 * <ul>
 * <li>{@link #colorForOrdinal(int)} - the PRIMARY path (coordinator review, 2026-09-22): a plain position
 * in the palette. {@code lineage.RecordLineageGraphic} calls this with each source system's position in
 * the PROJECT's own declared order ({@code RecordLineage.SourceChain#sourceOrder} - {@code
 * ProjectSource.trustRank}, falling back to alphabetical-by-name when the rank is absent or ties, exactly
 * as instructed), which is what actually gives "stable for a given system name within a project, with
 * control over which colours end up next to each other" - a property a pure hash of the name alone cannot
 * offer (two names could hash to adjacent, easily-confused palette slots with no way to fix it without
 * renaming a system).</li>
 * <li>{@link #colorFor(String)} - a stateless, name-only fallback (the ORIGINAL, and until 2026-09-22 the
 * only, strategy) for the rare case a system appears with no resolvable project order at all (should not
 * happen for a source that actually contributed a record, but never trusted blindly - {@code
 * RecordLineageGraphic} falls back to it defensively). Still deterministic and stable in the weaker sense
 * "the same name always gets the same colour with no shared state to keep in sync" - just without control
 * over neighbours, and with a real chance of collision (8 buckets, an unbounded name space).</li>
 * </ul>
 * <p>
 * <b>Made {@code public}</b> (wizard-matching-results lot, 2026-09-24) so {@code ebx.MatchingResultsBuilder} -
 * a different package, step 6's "Matching results" band - can reuse the exact same palette instead of a
 * second, driftable copy. Every pre-existing member and caller in this package is unaffected; only the
 * visibility of the type and the members it already exposed within the package changed.
 */
public final class SourceColorPalette
{
	/** Reserved for the golden/master record - see {@link #colorFor(String)}'s own Javadoc for why a source system never gets this value. Matches the mockup's own accent (`build_record_lineage.py`, {@code ACC}). */
	public static final String GOLDEN = "#684bc0"; // theme-fixed (audit F-44): a legend colour chosen with the source palette below, never the perspective's

	/**
	 * 12 entries, none equal to {@link #GOLDEN}, ordered for maximum contrast between CONSECUTIVE entries
	 * (coordinator review, 2026-09-22 - alternating cool/warm hues) - order matters both for that reason and
	 * because it must never change once shipped (CLAUDE.md's own "never re-derive/guess a stable identifier"
	 * spirit: changing this array would repaint every already-seen system a different colour). The first
	 * four are the coordinator's own explicit values, kept exactly - every entry after that was chosen here
	 * to stay visually distinct from every earlier one and from {@link #GOLDEN}.
	 */
	private static final String[] PALETTE = { "#2f6fd0", // blue
		"#0e9384", // teal
		"#c77700", // amber
		"#b4236c", // rose
		"#6b7d12", // olive
		"#0b84a5", // sky
		"#8a5a2b", // brown
		"#475467", // slate
		"#4338ca", // indigo
		"#c2410c", // coral
		"#a21caf", // magenta
		"#15803d" // forest green
	};

	private SourceColorPalette()
	{
	}

	/** The palette colour at this position, wrapping around past {@link #PALETTE}'s own length - never null, never {@link #GOLDEN}. See class Javadoc: this is the PRIMARY strategy, called with a system's position in the project's own declared source order. Pair with {@link #isExtended(int)} to know whether this ordinal reused a colour. */
	public static String colorForOrdinal(final int pOrdinal)
	{
		final int index = ((pOrdinal % SourceColorPalette.PALETTE.length) + SourceColorPalette.PALETTE.length) % SourceColorPalette.PALETTE.length;
		return SourceColorPalette.PALETTE[index];
	}

	/** True once {@code pOrdinal} has run past the palette's own 12 entries and started reusing colours - the caller (coordinator review, 2026-09-22) then draws that lane's ribbon/legend-dot/lane-bar with a dashed/hollow style instead of a solid one, so two same-coloured lanes still read as different at a glance. Negative ordinals (should not occur - {@link RecordLineageGraphic#assignColors} only ever passes a 0-based position) are treated as within range, never "extended". */
	public static boolean isExtended(final int pOrdinal)
	{
		return pOrdinal >= SourceColorPalette.PALETTE.length;
	}

	/** A stable colour for {@code pSourceSystem} - never null, never {@link #GOLDEN}. Blank/null names fall on the palette's first entry (still stable, just not distinguishing). See class Javadoc: a defensive FALLBACK only - {@link #colorForOrdinal(int)} is the strategy actually used for real data. */
	public static String colorFor(final String pSourceSystem)
	{
		final String name = pSourceSystem == null ? "" : pSourceSystem.trim().toUpperCase(Locale.ROOT);
		final int hash = SourceColorPalette.stableHash(name);
		return SourceColorPalette.colorForOrdinal(Math.abs(hash));
	}

	/**
	 * {@code String.hashCode()} is already stable across JVM runs for a given string (it is specified
	 * character-by-character in the {@code String} Javadoc, unlike {@code Object.hashCode()}) - this method
	 * exists only to make that reliance explicit and named, rather than calling {@code .hashCode()} inline
	 * and leaving the next reader to wonder whether it is safe to depend on.
	 */
	private static int stableHash(final String pValue)
	{
		return pValue.hashCode();
	}
}
