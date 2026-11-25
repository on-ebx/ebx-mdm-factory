package com.on.ebx.factory.wizard;

/**
 * The inline SVG diagram on each implementation-style card (step 1), redesigned 2026-09-21 (product
 * owner: "the diagrams must say precisely WHERE THE DATA IS CREATED - that is the whole point of the
 * choice"). Three always-captioned columns - source applications, EBX (the MDM hub), consumers - laid out
 * identically on every card so the four compare at a glance; within them:
 * <ul>
 * <li>a box where master data is CREATED AND MAINTAINED is filled with the accent colour and carries a
 * text tag ({@link Labels#createdTag}) - the fill and the tag are what carry the fact, on their own, no
 * icon;</li>
 * <li>a box that only receives a copy is neutral with a text tag (Consolidation's hub: {@link
 * Labels#copyTag}, "Copie consolidée / lecture seule" - a business term the product owner asked for
 * verbatim, not a generic "Copy");</li>
 * <li>the registry's hub is an outline (plus its own small "two keys" mark) tagged {@link
 * Labels#keysOnlyTag};</li>
 * <li>consumers are always neutral, tagged {@link Labels#readTag}.</li>
 * <li>each of the two source boxes carries its own tiny left-aligned app label ({@link
 * Labels#sourceApp1}/{@link Labels#sourceApp2}) so they read as two applications, not two identical
 * rectangles.</li>
 * </ul>
 * <b>2026-09-21 revision (product owner, zoomed screenshot of the "Création" boxes): "I do not
 * understand the stroke at the very top."</b> That first version drew a free-floating pencil stroke
 * above the box, touching nothing - unreadable at card size and unexplained. The fix was NOT to redraw a
 * better pencil: a badge-plus-glyph version was built (a small full-opacity circle wholly inside the box
 * with a white pencil glyph on top - body, triangular tip, eraser cap, all three parts a pencil needs to
 * read as one) and measured in a real browser both blown up in isolation (where it clearly read as a
 * pencil) AND at the diagram's true rendered size inside a real card (~270px wide, the width the product
 * owner actually sees) - at that true size it read as an unidentifiable small dot, not a pencil, failing
 * the very test it was built to pass. So the icon was dropped entirely, per the product owner's own
 * stated rule ("if a person would not say pencil within a second, drop the icon and keep fill + tag
 * only"): the accent fill and the {@link Labels#createdTag} text tag already carry the fact on their
 * own, and no other mark on these diagrams (the registry's two rings, the dashed federated-read path,
 * the arrowheads) needed anything added or removed by that same close-up review - each already sits
 * next to a text tag or label that explains it.
 * <p>
 * Arrows follow {@link WizardRules.Flow} (visible filled-triangle arrowheads, never a hairline marker):
 * {@code IN_ONLY} draws one sources-&gt;hub arrow, {@code OUT_ONLY} one hub-&gt;sources arrow (Centralisation:
 * sources are subscribers, there is deliberately no arrow INTO the hub), {@code BOTH} draws TWO distinct
 * arrows rather than one double-headed line (Coexistence, per the product owner: "two distinct arrows, not
 * a double-headed hairline"). Every style except the registry also gets a plain hub-&gt;consumers arrow.
 * <p>
 * The registry ({@link WizardRules.GoldenRecord#NONE}) is the one case that does not fit that generic
 * hub-&gt;consumers arrow at all - consumers get no data straight from the hub, only keys - so it is drawn
 * differently: a thin sources-&gt;hub arrow labelled "keys" (the key registration), and a DASHED two-segment
 * path consumers-&gt;hub-&gt;sources standing for the federated read a consumer actually performs (look up the
 * key in the hub, then fetch the real data at the source) - chosen over a single consumers-&gt;sources arrow
 * because the whole point of this style is that the hub sits in the middle of that read, and over a plain
 * hub-&gt;consumers arrow because that would misleadingly suggest the hub serves data itself.
 * <p>
 * Every visual fact is derived from {@link WizardRules.ImplementationStyle}'s three enums (never four
 * hand-copied diagrams): {@code sourcesCreate = authoring != HUB}, {@code hubCreates = authoring != SOURCES},
 * {@code hubIsCopy = !hubCreates &amp;&amp; goldenRecord == READ_ONLY}, {@code hubIsKeysOnly = goldenRecord ==
 * NONE}. Pure: no EBX class, every string passed in already resolved by the caller ({@link WizardPage}, via
 * {@link WizardMessages}) and only XML-escaped here. No external resource, no hardcoded colour anywhere -
 * every shape is {@code currentColor} (a "neutral" box is the same hue at lower opacity, not a second
 * hardcoded colour, so the card's own CSS state keeps tinting the whole diagram for free). A {@code <title>}
 * names the diagram and a {@code <desc>} spells out the three tags in words, both for a screen reader; the
 * decorative shapes sit in one {@code aria-hidden="true"} group.
 */
final class WizardStyleDiagram
{
	private static final int VIEW_W = 260;
	private static final int VIEW_H = 172;

	private static final int SOURCES_X = 8;
	private static final int SOURCES_W = 70;
	private static final int HUB_X = 104;
	private static final int HUB_W = 70;
	private static final int CONSUMERS_X = 198;
	private static final int CONSUMERS_W = 54;

	private static final int BOX_TOP = 20;
	private static final int BOX_BOTTOM = 68;
	private static final int SOURCE_BOX_H = 22;
	private static final int TAG_Y = 84;
	private static final int TAG_LINE_H = 12;
	private static final int CAPTION_Y = 116;
	private static final int CAPTION_LINE_H = 13;

	private static final int FONT_TAG = 11;
	private static final int FONT_CAPTION = 11;
	private static final int FONT_ARROW_LABEL = 10;
	private static final int FONT_APP_LABEL = 9;

	private WizardStyleDiagram()
	{
	}

	/** Every locale-resolved string the diagram needs, built once by {@link WizardPage} from {@link WizardMessages} - this class never looks up a message key itself. */
	static final class Labels
	{
		final String title;
		final String sourcesColumn;
		final String hubColumn;
		final String consumersColumn;
		final String createdTag;
		final String copyTag;
		final String keysOnlyTag;
		final String subscribersTag;
		final String readTag;
		final String keysArrowLabel;
		final String sourceApp1;
		final String sourceApp2;

		Labels(final String pTitle, final String pSourcesColumn, final String pHubColumn, final String pConsumersColumn, final String pCreatedTag, final String pCopyTag,
			final String pKeysOnlyTag, final String pSubscribersTag, final String pReadTag, final String pKeysArrowLabel, final String pSourceApp1, final String pSourceApp2)
		{
			this.title = pTitle;
			this.sourcesColumn = pSourcesColumn;
			this.hubColumn = pHubColumn;
			this.consumersColumn = pConsumersColumn;
			this.createdTag = pCreatedTag;
			this.copyTag = pCopyTag;
			this.keysOnlyTag = pKeysOnlyTag;
			this.subscribersTag = pSubscribersTag;
			this.readTag = pReadTag;
			this.keysArrowLabel = pKeysArrowLabel;
			this.sourceApp1 = pSourceApp1;
			this.sourceApp2 = pSourceApp2;
		}
	}

	private enum BoxState
	{
		CREATE, COPY, KEYS_ONLY, NEUTRAL
	}

	static String svg(final WizardRules.ImplementationStyle pStyle, final Labels pLabels)
	{
		final boolean sourcesCreate = pStyle.authoring != WizardRules.Authoring.HUB;
		final boolean hubCreates = pStyle.authoring != WizardRules.Authoring.SOURCES;
		final boolean hubIsKeysOnly = pStyle.goldenRecord == WizardRules.GoldenRecord.NONE;

		final BoxState sourcesState = sourcesCreate ? BoxState.CREATE : BoxState.NEUTRAL;
		final BoxState hubState = hubCreates ? BoxState.CREATE : hubIsKeysOnly ? BoxState.KEYS_ONLY : BoxState.COPY;
		final String sourcesTag = sourcesCreate ? pLabels.createdTag : pLabels.subscribersTag;
		final String hubTag = hubCreates ? pLabels.createdTag : hubIsKeysOnly ? pLabels.keysOnlyTag : pLabels.copyTag;

		final StringBuilder svg = new StringBuilder(4096);
		svg.append("<svg class=\"mdmf-style-diagram\" viewBox=\"0 0 ").append(WizardStyleDiagram.VIEW_W).append(' ').append(WizardStyleDiagram.VIEW_H)
			.append("\" role=\"img\" focusable=\"false\" xmlns=\"http://www.w3.org/2000/svg\">");
		svg.append("<title>").append(WizardPage.esc(pLabels.title)).append("</title>");
		svg.append("<desc>").append(WizardPage.esc(pLabels.sourcesColumn + ": " + sourcesTag + ". " + pLabels.hubColumn + ": " + hubTag + ". " + pLabels.consumersColumn + ": "
			+ pLabels.readTag + ".")).append("</desc>");
		svg.append("<g aria-hidden=\"true\">");

		// ---- sources column: two boxes, one PER APPLICATION (own tiny caption each, so they read as two
		// applications and not two anonymous rectangles), a shared tag, one column caption. No icon: the
		// accent fill + the "Création" tag are what carry the fact - see the class Javadoc for the badge+
		// pencil version that was built, measured in a real browser at true card size, and dropped because it
		// read as an unidentifiable dot rather than a pencil (2026-09-21).
		final int sourcesCx = WizardStyleDiagram.SOURCES_X + WizardStyleDiagram.SOURCES_W / 2;
		final int sourceBox1Y = WizardStyleDiagram.BOX_TOP;
		final int sourceBox2Y = WizardStyleDiagram.BOX_BOTTOM - WizardStyleDiagram.SOURCE_BOX_H;
		WizardStyleDiagram.box(svg, WizardStyleDiagram.SOURCES_X, sourceBox1Y, WizardStyleDiagram.SOURCES_W, WizardStyleDiagram.SOURCE_BOX_H, sourcesState);
		WizardStyleDiagram.box(svg, WizardStyleDiagram.SOURCES_X, sourceBox2Y, WizardStyleDiagram.SOURCES_W, WizardStyleDiagram.SOURCE_BOX_H, sourcesState);
		WizardStyleDiagram.appLabel(svg, WizardStyleDiagram.SOURCES_X, sourceBox1Y, WizardStyleDiagram.SOURCES_W, WizardStyleDiagram.SOURCE_BOX_H, pLabels.sourceApp1);
		WizardStyleDiagram.appLabel(svg, WizardStyleDiagram.SOURCES_X, sourceBox2Y, WizardStyleDiagram.SOURCES_W, WizardStyleDiagram.SOURCE_BOX_H, pLabels.sourceApp2);
		WizardStyleDiagram.tag(svg, sourcesCx, sourcesTag);
		WizardStyleDiagram.caption(svg, sourcesCx, pLabels.sourcesColumn);

		// ---- hub column (EBX): one taller box, its own tag/caption, the registry's keys mark
		final int hubCx = WizardStyleDiagram.HUB_X + WizardStyleDiagram.HUB_W / 2;
		final int hubBoxH = WizardStyleDiagram.BOX_BOTTOM - WizardStyleDiagram.BOX_TOP;
		WizardStyleDiagram.box(svg, WizardStyleDiagram.HUB_X, WizardStyleDiagram.BOX_TOP, WizardStyleDiagram.HUB_W, hubBoxH, hubState);
		if (hubIsKeysOnly)
		{
			WizardStyleDiagram.keysMark(svg, hubCx, (WizardStyleDiagram.BOX_TOP + WizardStyleDiagram.BOX_BOTTOM) / 2);
		}
		WizardStyleDiagram.tag(svg, hubCx, hubTag);
		WizardStyleDiagram.caption(svg, hubCx, pLabels.hubColumn);

		// ---- consumers column: one box, always neutral/"read", no pencil
		final int consumersCx = WizardStyleDiagram.CONSUMERS_X + WizardStyleDiagram.CONSUMERS_W / 2;
		final int consumersBoxH = 32;
		final int consumersBoxY = WizardStyleDiagram.BOX_TOP + (WizardStyleDiagram.BOX_BOTTOM - WizardStyleDiagram.BOX_TOP - consumersBoxH) / 2;
		WizardStyleDiagram.box(svg, WizardStyleDiagram.CONSUMERS_X, consumersBoxY, WizardStyleDiagram.CONSUMERS_W, consumersBoxH, BoxState.NEUTRAL);
		WizardStyleDiagram.tag(svg, consumersCx, pLabels.readTag);
		WizardStyleDiagram.caption(svg, consumersCx, pLabels.consumersColumn);

		// ---- arrows
		final int sourcesRight = WizardStyleDiagram.SOURCES_X + WizardStyleDiagram.SOURCES_W;
		final int hubLeft = WizardStyleDiagram.HUB_X;
		final int hubRight = WizardStyleDiagram.HUB_X + WizardStyleDiagram.HUB_W;
		final int consumersLeft = WizardStyleDiagram.CONSUMERS_X;
		final int midY = (WizardStyleDiagram.BOX_TOP + WizardStyleDiagram.BOX_BOTTOM) / 2;

		if (hubIsKeysOnly)
		{
			// registry: a thin labelled "keys" arrow sources -> hub (registration), never the generic hub ->
			// consumers arrow (the hub serves no data itself) - see the class Javadoc for why a dashed
			// consumers -> hub -> sources path was chosen for the federated read instead
			WizardStyleDiagram.arrow(svg, sourcesRight, midY - 6, hubLeft, midY - 6, false, true, false);
			WizardStyleDiagram.arrowLabel(svg, (sourcesRight + hubLeft) / 2, midY - 10, pLabels.keysArrowLabel);
			WizardStyleDiagram.arrow(svg, consumersLeft, midY + 10, hubRight, midY + 10, false, true, true);
			WizardStyleDiagram.arrow(svg, hubLeft, midY + 18, sourcesRight, midY + 18, false, true, true);
		}
		else
		{
			if (pStyle.flow != WizardRules.Flow.OUT_ONLY)
			{
				WizardStyleDiagram.arrow(svg, sourcesRight, midY - (pStyle.flow == WizardRules.Flow.BOTH ? 6 : 0), hubLeft,
					midY - (pStyle.flow == WizardRules.Flow.BOTH ? 6 : 0), false, true, false);
			}
			if (pStyle.flow != WizardRules.Flow.IN_ONLY)
			{
				// a second, DISTINCT arrow (own y) for Coexistence - never a double-headed hairline on the same line
				WizardStyleDiagram.arrow(svg, hubLeft, midY + (pStyle.flow == WizardRules.Flow.BOTH ? 6 : 0), sourcesRight,
					midY + (pStyle.flow == WizardRules.Flow.BOTH ? 6 : 0), false, true, false);
			}
			WizardStyleDiagram.arrow(svg, hubRight, midY, consumersLeft, midY, false, true, false);
		}

		svg.append("</g></svg>");
		return svg.toString();
	}

	// ------------------------------------------------------------------------------------------------------ shapes

	private static void box(final StringBuilder pOut, final int pX, final int pY, final int pW, final int pH, final BoxState pState)
	{
		final String fillOpacity;
		switch (pState)
		{
			case CREATE:
				fillOpacity = "0.20";
				break;
			case COPY:
			case NEUTRAL:
				fillOpacity = "0.07";
				break;
			default: // KEYS_ONLY
				fillOpacity = "0";
		}
		pOut.append("<rect x=\"").append(pX).append("\" y=\"").append(pY).append("\" width=\"").append(pW).append("\" height=\"").append(pH)
			.append("\" rx=\"5\" fill=\"currentColor\" fill-opacity=\"").append(fillOpacity).append("\" stroke=\"currentColor\" stroke-width=\"1.8\"/>");
	}

	/** Two clearly separate rings (a gap, unlike overlapping circles which read as an infinity loop): the registry's "keys, not attributes" mark. */
	private static void keysMark(final StringBuilder pOut, final int pCx, final int pCy)
	{
		pOut.append("<circle cx=\"").append(pCx - 7).append("\" cy=\"").append(pCy).append("\" r=\"4\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\"/>");
		pOut.append("<circle cx=\"").append(pCx + 7).append("\" cy=\"").append(pCy).append("\" r=\"4\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\"/>");
	}

	/**
	 * A small centred label naming the application a source box represents ("App 1"/"App 2", or the real
	 * system codes where the caller knows them) - so the two stacked source boxes read as two distinct
	 * APPLICATIONS at a glance, not two anonymous rectangles (product owner, 2026-09-21, zoomed screenshot
	 * review). No icon sits next to it - see the class Javadoc for the badge+pencil version that was tried
	 * and dropped, so this label is centred in the box rather than left of a right-side icon.
	 */
	private static void appLabel(final StringBuilder pOut, final int pBoxX, final int pBoxY, final int pBoxW, final int pBoxH, final String pText)
	{
		pOut.append("<text x=\"").append(pBoxX + pBoxW / 2).append("\" y=\"").append(pBoxY + pBoxH / 2 + 3).append("\" text-anchor=\"middle\" style=\"font:700 ")
			.append(WizardStyleDiagram.FONT_APP_LABEL).append("px sans-serif;fill:currentColor;opacity:.8\">").append(WizardPage.esc(pText)).append("</text>");
	}

	private static void tag(final StringBuilder pOut, final int pCx, final String pText)
	{
		WizardStyleDiagram.multilineText(pOut, pCx, WizardStyleDiagram.TAG_Y, WizardStyleDiagram.TAG_LINE_H, WizardStyleDiagram.FONT_TAG, "font-weight:700", pText);
	}

	private static void caption(final StringBuilder pOut, final int pCx, final String pText)
	{
		WizardStyleDiagram.multilineText(pOut, pCx, WizardStyleDiagram.CAPTION_Y, WizardStyleDiagram.CAPTION_LINE_H, WizardStyleDiagram.FONT_CAPTION, "opacity:.72", pText);
	}

	private static void arrowLabel(final StringBuilder pOut, final int pCx, final int pY, final String pText)
	{
		pOut.append("<text x=\"").append(pCx).append("\" y=\"").append(pY).append("\" text-anchor=\"middle\" style=\"font:").append(WizardStyleDiagram.FONT_ARROW_LABEL)
			.append("px sans-serif;fill:currentColor;opacity:.8\">").append(WizardPage.esc(pText)).append("</text>");
	}

	/** Up to two centred lines (wrapped at the space closest to the middle - generic, works for any locale's text) so a caption/tag never overflows its column. */
	private static void multilineText(final StringBuilder pOut, final int pCx, final int pStartY, final int pLineHeight, final int pFontSize, final String pStyle,
		final String pText)
	{
		final String[] lines = WizardStyleDiagram.wrap(pText);
		for (int i = 0; i < lines.length; i++)
		{
			pOut.append("<text x=\"").append(pCx).append("\" y=\"").append(pStartY + i * pLineHeight).append("\" text-anchor=\"middle\" style=\"font:").append(pFontSize)
				.append("px sans-serif;fill:currentColor;").append(pStyle).append("\">").append(WizardPage.esc(lines[i])).append("</text>");
		}
	}

	/** Splits at the space closest to the middle of the string when it is longer than 15 characters; otherwise returned as a single line. */
	static String[] wrap(final String pText)
	{
		final String text = pText == null ? "" : pText;
		if (text.length() <= 15)
		{
			return new String[] { text };
		}
		final int middle = text.length() / 2;
		int bestSpace = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = 0; i < text.length(); i++)
		{
			if (text.charAt(i) == ' ')
			{
				final int distance = Math.abs(i - middle);
				if (distance < bestDistance)
				{
					bestDistance = distance;
					bestSpace = i;
				}
			}
		}
		if (bestSpace < 0)
		{
			return new String[] { text };
		}
		return new String[] { text.substring(0, bestSpace), text.substring(bestSpace + 1) };
	}

	/**
	 * A line with a plainly visible filled-triangle arrowhead (never a hairline marker) at the destination
	 * end. {@code pDashed} draws a dashed stroke for the registry's federated-read path, to read as visually
	 * distinct from the solid "keys" registration arrow on the same diagram.
	 */
	private static void arrow(final StringBuilder pOut, final int pX1, final int pY1, final int pX2, final int pY2, final boolean pHeadAtStart, final boolean pHeadAtEnd,
		final boolean pDashed)
	{
		pOut.append("<line x1=\"").append(pX1).append("\" y1=\"").append(pY1).append("\" x2=\"").append(pX2).append("\" y2=\"").append(pY2)
			.append("\" stroke=\"currentColor\" stroke-width=\"2\"").append(pDashed ? " stroke-dasharray=\"4 3\"" : "").append("/>");
		final double angle = Math.atan2(pY2 - pY1, pX2 - pX1);
		if (pHeadAtEnd)
		{
			WizardStyleDiagram.arrowhead(pOut, pX2, pY2, angle);
		}
		if (pHeadAtStart)
		{
			WizardStyleDiagram.arrowhead(pOut, pX1, pY1, angle + Math.PI);
		}
	}

	private static void arrowhead(final StringBuilder pOut, final int pTipX, final int pTipY, final double pAngle)
	{
		final double spread = 0.5;
		final int length = 9;
		final long x1 = Math.round(pTipX - length * Math.cos(pAngle - spread));
		final long y1 = Math.round(pTipY - length * Math.sin(pAngle - spread));
		final long x2 = Math.round(pTipX - length * Math.cos(pAngle + spread));
		final long y2 = Math.round(pTipY - length * Math.sin(pAngle + spread));
		pOut.append("<polygon points=\"").append(pTipX).append(",").append(pTipY).append(" ").append(x1).append(",").append(y1).append(" ").append(x2).append(",").append(y2)
			.append("\" fill=\"currentColor\"/>");
	}
}
