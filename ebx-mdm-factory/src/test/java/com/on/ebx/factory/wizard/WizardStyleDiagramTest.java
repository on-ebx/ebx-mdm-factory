package com.on.ebx.factory.wizard;

/**
 * JUnit-free: run the main, a failure throws. Exercises {@link WizardStyleDiagram} without any EBX type,
 * against the redesign of 2026-09-21 (product owner: "the diagrams must say precisely WHERE THE DATA IS
 * CREATED"): per style, which boxes carry the "created here" tag and the accent fill, which arrows exist
 * and in which direction, captions present, escaping. Also guards the SAME DAY's follow-up fix: a pencil
 * badge was tried inside the "Création" boxes, measured at true card size, found unreadable, and dropped -
 * {@link #noIconMarkupRemainsOnAnyDiagram()} pins that decision so it cannot silently regress.
 */
public final class WizardStyleDiagramTest
{
	private static final WizardStyleDiagram.Labels LABELS = new WizardStyleDiagram.Labels("TITLE", "SOURCES_COL", "HUB_COL", "CONSUMERS_COL", "CREATED", "COPY", "KEYS_ONLY",
		"SUBSCRIBERS", "READ", "keys", "APP1", "APP2");

	public static void main(final String[] pArgs)
	{
		WizardStyleDiagramTest.titleAndDescAreEscaped();
		WizardStyleDiagramTest.everyDiagramHasAnAriaHiddenGroup();
		WizardStyleDiagramTest.everyDiagramShowsAllThreeCaptions();
		WizardStyleDiagramTest.consolidationSourcesCreateHubIsCopy();
		WizardStyleDiagramTest.centralisationHubCreatesSourcesAreSubscribersNoArrowIntoHub();
		WizardStyleDiagramTest.coexistenceBothCreateTwoDistinctArrows();
		WizardStyleDiagramTest.registryHubIsKeysOnlyWithKeysArrowAndDashedFederatedRead();
		WizardStyleDiagramTest.onlyCreatingBoxesGetTheAccentFill();
		WizardStyleDiagramTest.noIconMarkupRemainsOnAnyDiagram();
		WizardStyleDiagramTest.sourceBoxesCarryTheirOwnAppLabel();
		WizardStyleDiagramTest.everyDiagramIsSelfContainedNoExternalResource();
		WizardStyleDiagramTest.wrapSplitsLongTextNearTheMiddle();
		WizardStyleDiagramTest.wrapKeepsShortTextOnOneLine();

		System.out.println("WizardStyleDiagramTest OK");
	}

	private static void titleAndDescAreEscaped()
	{
		final WizardStyleDiagram.Labels labels = new WizardStyleDiagram.Labels("a & <b>", "s", "h", "c", "Created", "Copy", "Keys", "Subs", "Read", "keys", "App1", "App2");
		final String svg = WizardStyleDiagram.svg(WizardRules.styleByCode("CONSOLIDATION"), labels);
		WizardStyleDiagramTest.check("title is present and escaped", svg.contains("<title>a &amp; &lt;b&gt;</title>"));
		WizardStyleDiagramTest.check("no raw markup leaks from the title", !svg.contains("<b>a"));
		WizardStyleDiagramTest.check("a <desc> is present", svg.contains("<desc>"));
	}

	private static void everyDiagramHasAnAriaHiddenGroup()
	{
		for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			final String svg = WizardStyleDiagram.svg(style, WizardStyleDiagramTest.LABELS);
			WizardStyleDiagramTest.check(style.code + ": decorative shapes sit in one aria-hidden group", svg.contains("<g aria-hidden=\"true\">"));
			WizardStyleDiagramTest.check(style.code + ": role=img on the root", svg.contains("role=\"img\""));
		}
	}

	/** Captions are ALWAYS visible under the boxes, whatever the style (product owner: "captions always visible"). */
	private static void everyDiagramShowsAllThreeCaptions()
	{
		for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			final String svg = WizardStyleDiagram.svg(style, WizardStyleDiagramTest.LABELS);
			WizardStyleDiagramTest.check(style.code + ": sources caption present", svg.contains(">SOURCES_COL<"));
			WizardStyleDiagramTest.check(style.code + ": hub caption present", svg.contains(">HUB_COL<"));
			WizardStyleDiagramTest.check(style.code + ": consumers caption present", svg.contains(">CONSUMERS_COL<"));
		}
	}

	private static void consolidationSourcesCreateHubIsCopy()
	{
		final String svg = WizardStyleDiagram.svg(WizardRules.styleByCode("CONSOLIDATION"), WizardStyleDiagramTest.LABELS);
		WizardStyleDiagramTest.check("sources tagged Created", svg.contains(">CREATED<"));
		WizardStyleDiagramTest.check("hub tagged Copy, not Created", svg.contains(">COPY<") && WizardStyleDiagramTest.count(svg, ">CREATED<") == 1);
		WizardStyleDiagramTest.check("consumers tagged Read", svg.contains(">READ<"));
		WizardStyleDiagramTest.check("no subscribers tag", !svg.contains(">SUBSCRIBERS<"));
		WizardStyleDiagramTest.check("no keys-only tag", !svg.contains(">KEYS_ONLY<"));
	}

	/** Centralisation: EBX is the only place that creates; sources are subscribers; NO arrow goes into the hub. */
	private static void centralisationHubCreatesSourcesAreSubscribersNoArrowIntoHub()
	{
		final String svg = WizardStyleDiagram.svg(WizardRules.styleByCode("CENTRALISATION"), WizardStyleDiagramTest.LABELS);
		WizardStyleDiagramTest.check("hub tagged Created", WizardStyleDiagramTest.count(svg, ">CREATED<") == 1);
		WizardStyleDiagramTest.check("sources tagged Subscribers, not Created", svg.contains(">SUBSCRIBERS<"));
		WizardStyleDiagramTest.check("no copy/keys-only tag", !svg.contains(">COPY<") && !svg.contains(">KEYS_ONLY<"));
		// the source->hub arrow's line runs from x=78 (sources right edge) to x=104 (hub left edge); an arrow
		// INTO the hub would need an arrowhead polygon whose tip sits at x=104 - none should exist
		WizardStyleDiagramTest.check("no arrowhead tip lands on the hub's own left edge (no arrow into the hub)", !svg.contains("<polygon points=\"104,"));
	}

	/** Coexistence: both create, so there must be TWO separate <line> elements between sources and hub, not one double-headed line. */
	private static void coexistenceBothCreateTwoDistinctArrows()
	{
		final String svg = WizardStyleDiagram.svg(WizardRules.styleByCode("COEXISTENCE"), WizardStyleDiagramTest.LABELS);
		WizardStyleDiagramTest.check("both sources and hub tagged Created", WizardStyleDiagramTest.count(svg, ">CREATED<") == 2);
		final int sourceHubLines = WizardStyleDiagramTest.count(svg, "x1=\"78\"") + WizardStyleDiagramTest.count(svg, "x2=\"78\"");
		WizardStyleDiagramTest.check("at least two distinct line segments touch the sources/hub boundary (not a single double-headed line)", sourceHubLines >= 2);
	}

	private static void registryHubIsKeysOnlyWithKeysArrowAndDashedFederatedRead()
	{
		final String svg = WizardStyleDiagram.svg(WizardRules.styleByCode("REGISTRY"), WizardStyleDiagramTest.LABELS);
		WizardStyleDiagramTest.check("hub tagged Keys only", svg.contains(">KEYS_ONLY<"));
		WizardStyleDiagramTest.check("sources tagged Created (registry still authors at the sources)", svg.contains(">CREATED<"));
		WizardStyleDiagramTest.check("the keys arrow label is drawn", svg.contains(">keys<"));
		WizardStyleDiagramTest.check("a dashed federated-read path is drawn", svg.contains("stroke-dasharray"));
		WizardStyleDiagramTest.check("the registry's own two-ring keys mark is drawn (two r=4 circles)", WizardStyleDiagramTest.count(svg, "r=\"4\" fill=\"none\"") == 2);
		WizardStyleDiagramTest.check("no plain solid hub->consumers arrow label/tag reused as Copy", !svg.contains(">COPY<"));
	}

	private static void onlyCreatingBoxesGetTheAccentFill()
	{
		// Centralisation: only the hub creates -> exactly one filled ("create") box
		final String centralisation = WizardStyleDiagram.svg(WizardRules.styleByCode("CENTRALISATION"), WizardStyleDiagramTest.LABELS);
		WizardStyleDiagramTest.check("exactly one accent-filled (0.20) box for Centralisation", WizardStyleDiagramTest.count(centralisation, "fill-opacity=\"0.20\"") == 1);

		// Consolidation: only the sources create -> two filled boxes
		final String consolidation = WizardStyleDiagram.svg(WizardRules.styleByCode("CONSOLIDATION"), WizardStyleDiagramTest.LABELS);
		WizardStyleDiagramTest.check("two accent-filled source boxes for Consolidation", WizardStyleDiagramTest.count(consolidation, "fill-opacity=\"0.20\"") == 2);

		// Coexistence: both sources and hub create -> three filled boxes (2 sources + hub)
		final String coexistence = WizardStyleDiagram.svg(WizardRules.styleByCode("COEXISTENCE"), WizardStyleDiagramTest.LABELS);
		WizardStyleDiagramTest.check("three accent-filled boxes for Coexistence", WizardStyleDiagramTest.count(coexistence, "fill-opacity=\"0.20\"") == 3);
	}

	/**
	 * Pins the 2026-09-21 follow-up decision (product owner, zoomed screenshot: "I do not understand the
	 * stroke at the very top... nobody can read it as a pencil"): a badge-plus-pencil-glyph version was
	 * built and measured at the diagram's TRUE rendered size inside a real card (~270px wide) in a real
	 * browser - at that size it read as an unidentifiable small dot, not a pencil, so it was dropped
	 * entirely rather than kept as a mark nobody can explain. No icon of any kind remains on any of the four
	 * diagrams: no free-floating stroke ({@code stroke-linecap}, the original defect), no badge circle, no
	 * white glyph fill. The accent fill and the text tag are the only things that say "created here", on
	 * their own - which is also why this guard belongs next to the "accent fill" test above.
	 */
	private static void noIconMarkupRemainsOnAnyDiagram()
	{
		for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			final String svg = WizardStyleDiagram.svg(style, WizardStyleDiagramTest.LABELS);
			WizardStyleDiagramTest.check(style.code + ": no free-floating stroke technique (the original pencil defect)", !svg.contains("stroke-linecap"));
			WizardStyleDiagramTest.check(style.code + ": no badge circle behind an icon (r=8, full-opacity currentColor)", !svg.contains("r=\"8\" fill=\"currentColor\""));
			WizardStyleDiagramTest.check(style.code + ": no hardcoded colour of any kind (no white glyph, no black)", !svg.contains("#fff") && !svg.contains("#000"));
		}
	}

	/** The two stacked source boxes must read as two distinct applications, not two anonymous rectangles. */
	private static void sourceBoxesCarryTheirOwnAppLabel()
	{
		final String svg = WizardStyleDiagram.svg(WizardRules.styleByCode("CONSOLIDATION"), WizardStyleDiagramTest.LABELS);
		WizardStyleDiagramTest.check("first app label present", svg.contains(">APP1<"));
		WizardStyleDiagramTest.check("second app label present", svg.contains(">APP2<"));
	}

	private static void everyDiagramIsSelfContainedNoExternalResource()
	{
		for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			final String svg = WizardStyleDiagram.svg(style, WizardStyleDiagramTest.LABELS);
			WizardStyleDiagramTest.check(style.code + ": no external resource referenced", !svg.contains("<image") && !svg.contains("xlink:href"));
			WizardStyleDiagramTest.check(style.code + ": uses currentColor, not a hardcoded colour, so CSS state (selected/disabled) tints it",
				svg.contains("currentColor") && !svg.contains("#fff") && !svg.contains("#000"));
		}
	}

	private static void wrapSplitsLongTextNearTheMiddle()
	{
		final String[] lines = WizardStyleDiagram.wrap("Consolidated copy read only");
		WizardStyleDiagramTest.check("long text is split into two lines", lines.length == 2);
		WizardStyleDiagramTest.check("no word is lost", (lines[0] + " " + lines[1]).equals("Consolidated copy read only"));
	}

	private static void wrapKeepsShortTextOnOneLine()
	{
		final String[] lines = WizardStyleDiagram.wrap("Read");
		WizardStyleDiagramTest.check("short text stays on one line", lines.length == 1 && "Read".equals(lines[0]));
	}

	private static int count(final String pHaystack, final String pNeedle)
	{
		int count = 0;
		int index = 0;
		while ((index = pHaystack.indexOf(pNeedle, index)) >= 0)
		{
			count++;
			index += pNeedle.length();
		}
		return count;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
