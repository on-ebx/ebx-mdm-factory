package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.factory.matching.CandidateFilterCheck;

/**
 * Builds the decision-tree block of the Matching review line's expanded body (section 1) - binary-decision-
 * tree-screen lot, 2026-09-22, REPLACES the single-column cascade drawing entirely
 * (`docs/mockups/review-match-plan-binary-tree.html`, the product owner: "C'est parfait" - `docs/MATCHING.md`
 * &sect;16, lot 2). Geometry comes from {@link MatchPlanTreeLayout} (pure, separately unit-tested); this
 * class only turns a laid-out {@link ReviewDecisionTree.Plan} into markup: the general binary SVG (node
 * cards, elbow connectors, leaf boxes, folding for a tree beyond {@link MatchPlanTreeLayout#FOLD_LEAF_THRESHOLD}
 * leaves) plus, alongside it, one pre-rendered (mostly hidden) detail panel per node - selecting a node and
 * expanding a fold are both handled client-side (see {@link #SCRIPT}), no round trip.
 * <p>
 * Package-private: {@link MatchPlanView} is the only caller. Every dynamic string ({@link ReviewPage#esc})
 * is escaped before it reaches the output - a field name or a model-proposed rationale is ordinary data
 * (it can come from an upstream language model), never trusted.
 * <p>
 * <b>{@code autoMerge} and the {@code MATCH} leaf</b> (mirrors {@code master.MatchingPolicyTranslator}
 * exactly, generalized from "the last tier" to "every {@code MATCH} leaf occurrence anywhere in the tree",
 * since a general tree can reach more than one): a leaf occurrence whose outcome is {@code MATCH} renders
 * as the real, green "merged automatically" box only when {@code autoMerge} is true; otherwise it renders
 * exactly like a {@code SUSPECTED} leaf (the translator forces the very leaf token itself to
 * {@code SUSPECTED} - {@code MatchingPolicyTranslator.forceMatchedLeavesToSuspected} - so drawing it as
 * MATCH would show a branch that is not, in fact, the one the add-on ever executes). A single STANDING note
 * ("AUTOMATIC MERGE - disabled for this plan", the exact wording of {@link MatchPlanTreeDiagram}'s own
 * pre-lot leaf annotation) is shown once, right under the block's lead line, whenever {@code autoMerge} is
 * false and the plan is not empty - regardless of whether the plan actually has a {@code MATCH}-terminal
 * leaf at all (this documents the STANDING RULE, exactly as the pre-lot per-plan annotation did, not a
 * specific leaf's own proposal).
 * <p>
 * <b>tree-diagram-labels fix lot, 2026-09-22</b> ({@code docs/MATCHING.md} &sect;16.10 - the product owner's live
 * screenshot of the expanded MATCH-1 row, one AVERAGE node): two rendering defects fixed. (1) A branch label
 * used to sit centred on the elbow connector's own horizontal run, which collided with the vertical segment
 * whenever the two ends of that run were close together (the AVERAGE node's own {@code >= 80}/{@code < 80}
 * labels, in the product owner's screenshot) - {@link #renderConnector} now places the label BESIDE the connector's
 * FIRST vertical segment (its own x, +8, vertically centred on that segment) with a white halo behind the
 * text ({@code paint-order: stroke}) so it stays legible crossing a line. (2) A leaf box was a fixed 120px
 * regardless of its own text (the SUSPECT leaf's "Proposed to a data steward" clipped) - the leaf wording is
 * shortened in both bundles ({@code Messages_en}/{@code Messages_fr.properties}) AND {@link
 * MatchPlanTreeLayout} now sizes every leaf from its own text as a safety net (see that class' own Javadoc);
 * {@link #computeLeafWidths} resolves the localized strings this class already has (locale, {@code autoMerge})
 * into the plain {@code Map<leafKind, width>} the (i18n-free) layout class takes. Node-card body text
 * ({@link #bodyText}) is now truncated the same way the rationale line already was, with the untruncated
 * text in a {@code <title>} tooltip - a node with many fields (e.g. an {@code AT_LEAST_N} over 6 fields) no
 * longer overflows its 220px card silently.
 */
final class MatchPlanTreeDiagram
{
	private static final int MARGIN = 24;
	/** Height of the clustering band drawn above row 0 - unchanged from the pre-lot diagram. */
	private static final int CLUSTER_BAND_HEIGHT = 48;
	private static final int CLUSTER_BAND_TO_ROOT_GAP = 24;
	/** Rough characters-per-line budget for a node card's own rationale note - SVG text never wraps on its own. */
	private static final int NOTE_CHARS_BUDGET = 46;
	/**
	 * Rough characters-per-line budget for a node card's own compact field-summary line (11.5px, vs. the
	 * rationale's 10px) - same reasoning as {@link #NOTE_CHARS_BUDGET}, scaled down for the larger font over the
	 * same ~196px available width (tree-diagram-labels fix lot, 2026-09-22).
	 */
	private static final int NODE_BODY_CHARS_BUDGET = 40;
	/**
	 * tree-plain-words lot (2026-09-23): the node card is now {@link MatchPlanTreeLayout#WIDE_NODE_WIDTH} wide and
	 * its field list WRAPS over up to {@link #NODE_BODY_MAX_LINES} lines of this many characters (11.5px text over
	 * ~276px) instead of being cut at {@link #NODE_BODY_CHARS_BUDGET} - the product owner: "l'affichage est rogne, il faut
	 * utiliser plus de place". Only a list longer than three full lines is still cut (tooltip keeps the rest).
	 */
	private static final int NODE_BODY_WRAP_CHARS = 44;
	private static final int NODE_BODY_MAX_LINES = 3;
	/** ~px per character of the cluster band's 12px text - the SVG is widened so the band's own sentence never overflows a narrow tree. */
	private static final double BAND_CHAR_WIDTH_PX = 6.3;
	/** Extra band height when the "about N groups" second line is known. */
	private static final int CLUSTER_BAND_NUMBERS_LINE = 15;
	/** Horizontal gap from a connector's first vertical segment to its own label - tree-diagram-labels fix lot, 2026-09-22. */
	private static final int BRANCH_LABEL_X_GAP = 8;
	/** White halo behind a branch label so it stays legible crossing a connector line - {@code paint-order: stroke} draws the stroke behind the fill, no separate element needed. */
	private static final String BRANCH_LABEL_HALO_STYLE = "paint-order:stroke;stroke:#fff;stroke-width:4px;stroke-linejoin:round";

	private MatchPlanTreeDiagram()
	{
	}

	static String build(final JsonNode pRequirements, final Map<String, MatchPlanFieldEvidence> pEvidenceByField, final boolean pAutoMerge, final String pEntityName,
		final Locale pLocale)
	{
		return MatchPlanTreeDiagram.build(pRequirements, pEvidenceByField, pAutoMerge, pEntityName, pLocale, false);
	}

	/**
	 * UX lot U1 (2026-10-13): {@code pFoldVolumeNotes} true moves what only matters at volume - the "automatic merge is off" note and the candidate-search
	 * cost / reason lines of the band ("measured 9 times slower ... at 1 million rows") - out of the diagram into a "Details" disclosure above it. The Proposal
	 * review screen never folds ({@code false}, the pre-lot behaviour); the wizard folds them for a table under {@code wizard.MatchingVolume#INLINE_ROWS} rows.
	 */
	static String build(final JsonNode pRequirements, final Map<String, MatchPlanFieldEvidence> pEvidenceByField, final boolean pAutoMerge, final String pEntityName,
		final Locale pLocale, final boolean pFoldVolumeNotes)
	{
		final ReviewDecisionTree.Plan plan = ReviewDecisionTree.readFrom(pRequirements);
		final StringBuilder out = new StringBuilder();
		final StringBuilder notes = new StringBuilder();
		if (!pAutoMerge && !plan.nodes.isEmpty())
		{
			notes.append("<p class=\"mdmf-hint mdmf-tree-automerge-note\"><b>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.leaf.disabled.title")))
				.append("</b> - ").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.leaf.disabled.text"))).append("</p>");
		}
		if (pFoldVolumeNotes && !plan.nodes.isEmpty())
		{
			final String[] filter = MatchPlanTreeDiagram.candidateFilterLines(pRequirements, pLocale);
			for (int i = 1; i < filter.length; i++)
			{
				if (filter[i] != null)
				{
					notes.append("<p class=\"mdmf-hint mdmf-candidate-search\">").append(ReviewPage.esc(filter[i])).append("</p>");
				}
			}
		}
		if (notes.length() > 0)
		{
			if (pFoldVolumeNotes)
			{
				out.append("<details class=\"mdmf-disclosure mdmf-tree-volume-fold\"><summary>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "wizard.step6.volume.details")))
					.append("</summary>").append(notes).append("</details>");
			}
			else
			{
				out.append(notes);
			}
		}
		out.append("<div class=\"mdmf-tree-layout\"><div class=\"mdmf-tree-diagram\" data-mdmf-tree-id=\"").append(ReviewPage.esc(pEntityName)).append("\">");
		out.append("<div class=\"mdmf-tree-scroll\">");
		out.append(MatchPlanTreeDiagram.buildSvg(plan, pRequirements, pEvidenceByField, pAutoMerge, pEntityName, pLocale, pFoldVolumeNotes));
		out.append("</div>");
		out.append("<div class=\"mdmf-tree-detail-col\">");
		MatchPlanTreeDiagram.renderDetailPanels(out, plan, pLocale);
		out.append("</div>");
		out.append("</div></div>");
		return out.toString();
	}

	// ------------------------------------------------------------------------------------------------------- svg

	private static String buildSvg(final ReviewDecisionTree.Plan pPlan, final JsonNode pRequirements, final Map<String, MatchPlanFieldEvidence> pEvidenceByField,
		final boolean pAutoMerge, final String pEntityName, final Locale pLocale, final boolean pFoldVolumeNotes)
	{
		// tree-plain-words lot (2026-09-23): every card of the plan gets the height of the longest wrapped field
		// list, so rows stay aligned and no connector ever starts inside a taller card.
		int maxBodyLines = 1;
		for (final ReviewDecisionTree.Node node : pPlan.nodes)
		{
			maxBodyLines = Math.max(maxBodyLines, MatchPlanTreeDiagram.wrap(MatchPlanTreeDiagram.bodyText(node, pLocale), MatchPlanTreeDiagram.NODE_BODY_WRAP_CHARS,
				MatchPlanTreeDiagram.NODE_BODY_MAX_LINES).size());
		}
		final MatchPlanTreeLayout.Result layout = MatchPlanTreeLayout.layout(pPlan, MatchPlanTreeDiagram.computeLeafWidths(pAutoMerge, pLocale),
			MatchPlanTreeLayout.Geometry.forBodyLines(maxBodyLines));
		final List<String> clusteringFields = new ArrayList<String>();
		for (final JsonNode field : pRequirements.path("clusteringFields"))
		{
			clusteringFields.add(field.asText(""));
		}
		final String[] bandLines = MatchPlanTreeDiagram.clusterBandLines(clusteringFields, pPlan, pRequirements, pEvidenceByField, pLocale, pFoldVolumeNotes);
		int bandHeight = MatchPlanTreeDiagram.CLUSTER_BAND_HEIGHT;
		for (int i = 1; i < bandLines.length; i++)
		{
			bandHeight += bandLines[i] == null ? 0 : MatchPlanTreeDiagram.CLUSTER_BAND_NUMBERS_LINE;
		}
		final int topOffset = pPlan.nodes.isEmpty() ? 0 : bandHeight + MatchPlanTreeDiagram.CLUSTER_BAND_TO_ROOT_GAP;
		int bandTextWidth = 0;
		for (final String bandLine : bandLines)
		{
			if (bandLine != null)
			{
				bandTextWidth = Math.max(bandTextWidth, (int) Math.ceil(bandLine.length() * MatchPlanTreeDiagram.BAND_CHAR_WIDTH_PX) + 48);
			}
		}
		final int width = Math.max((int) Math.round(layout.width) + MatchPlanTreeDiagram.MARGIN, bandTextWidth);
		final int height = topOffset + (int) Math.round(layout.height) + MatchPlanTreeDiagram.MARGIN;

		final StringBuilder svg = new StringBuilder();
		final String ariaLabel = pEntityName == null || pEntityName.trim().isEmpty() ? ReviewMessages.get(pLocale, "review.match.tree.title")
			: ReviewMessages.get(pLocale, "review.match.tree.title.forEntity", pEntityName);
		svg.append("<svg viewBox=\"0 0 ").append(Math.max(width, 300)).append(' ').append(Math.max(height, 80)).append("\" width=\"").append(Math.max(width, 300))
			.append("\" height=\"").append(Math.max(height, 80)).append("\" role=\"img\" aria-label=\"").append(ReviewPage.esc(ariaLabel)).append("\">");
		svg.append("<defs><marker id=\"mdmfArrow\" viewBox=\"0 0 8 8\" refX=\"7\" refY=\"4\" markerUnits=\"userSpaceOnUse\" markerWidth=\"9\" markerHeight=\"9\" orient=\"auto\">"
			+ "<path d=\"M0,0 L8,4 L0,8 z\" fill=\"#98a2b3\"/></marker></defs>");

		if (pPlan.nodes.isEmpty())
		{
			svg.append("<text x=\"").append(Math.max(width, 300) / 2).append("\" y=\"30\" font-size=\"12\" fill=\"#667085\" text-anchor=\"middle\">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.noTiers"))).append("</text></svg>");
			return svg.toString();
		}

		final int clusterCx = (int) Math.round(layout.boxes.get(pPlan.root).cx);
		MatchPlanTreeDiagram.renderClusterBand(svg, bandLines, bandHeight, Math.max(width, 300), pLocale);
		svg.append("<path d=\"M").append(clusterCx).append(',').append(bandHeight + 2).append(" L").append(clusterCx).append(',')
			.append(topOffset - 2).append("\" stroke=\"#98a2b3\" stroke-width=\"2\" marker-end=\"url(#mdmfArrow)\"/>");

		MatchPlanTreeDiagram.renderSubtree(svg, pPlan, layout, layout.boxes.get(pPlan.root), topOffset, pAutoMerge, pLocale);
		svg.append("</svg>");
		return svg.toString();
	}

	/**
	 * Resolves the localized title/body strings for each of the three raw leaf kinds and turns them into a
	 * {@code Map<leafKind, width>} via {@link MatchPlanTreeLayout#leafWidthFor} - the ONE place this class
	 * bridges its own i18n (locale, {@link ReviewMessages}, the {@code autoMerge} display-kind resolution
	 * {@link #renderLeaf} already does) into the layout class' plain, locale-free geometry. {@code
	 * ReviewDecisionTree#LEAF_MATCH}'s own width follows whichever wording it will actually render with
	 * (real "merged" text when {@code pAutoMerge}, else the same "suspect" wording {@link #renderLeaf} forces
	 * it to) - mirrors that method's own {@code realMatch}/{@code asSuspect} resolution exactly.
	 */
	private static Map<String, Integer> computeLeafWidths(final boolean pAutoMerge, final Locale pLocale)
	{
		final String suspectTitle = ReviewMessages.get(pLocale, "review.match.tree.leaf.suspect.title");
		final String suspectText = ReviewMessages.get(pLocale, "review.match.tree.leaf.suspect.text");
		final String matchTitle = pAutoMerge ? ReviewMessages.get(pLocale, "review.match.tree.leaf.merged.title") : suspectTitle;
		final String matchText = pAutoMerge ? ReviewMessages.get(pLocale, "review.match.tree.leaf.merged.text") : suspectText;
		final String notMatchedTitle = ReviewMessages.get(pLocale, "review.match.tree.leaf.notmatch.title");
		final String notMatchedText = ReviewMessages.get(pLocale, "review.match.tree.leaf.notmatch.text");
		final Map<String, Integer> widths = new LinkedHashMap<String, Integer>();
		widths.put(ReviewDecisionTree.LEAF_MATCH, Integer.valueOf(MatchPlanTreeLayout.leafWidthFor(matchTitle, matchText)));
		widths.put(ReviewDecisionTree.LEAF_SUSPECTED, Integer.valueOf(MatchPlanTreeLayout.leafWidthFor(suspectTitle, suspectText)));
		widths.put(ReviewDecisionTree.LEAF_NOT_MATCHED, Integer.valueOf(MatchPlanTreeLayout.leafWidthFor(notMatchedTitle, notMatchedText)));
		return widths;
	}

	/**
	 * What the band says - candidate-search-wording fix (2026-09-23, the product owner: "Est-ce qu'ils doivent vraiment
	 * avoir les memes noms et prenoms ?"). The add-on's pre-clustering is a CANDIDATE SEARCH on every field the
	 * policy declares - {@code master.MatchingPolicyTranslator} declares the plan's {@code clusteringFields}
	 * first, then every other field the tree compares, all with the {@code @default} search strategy (for a
	 * name it resolves to the add-on's own {@code @Name} template, an approximate search - {@code
	 * docs/MATCHING.md} §3.3a) - so the earlier "only records with the same X are compared" claimed an exact
	 * identity the engine never enforces. Line 1 now names the search fields in the declared order; line 2 says
	 * that only the pairs found this way go through the tree; line 3, only when the evidence measured the first
	 * search field, gives the rough group count. {@code [i]} is null for a line that is not shown.
	 * <p>
	 * Lot M2 (2026-10-03): a RELATION field (compared through a child table) is listed among the search fields only when its
	 * weight in {@code requirements.relationClusteringWeights} is above 0 - the default is 0, so a relation field is
	 * compared in the tree below but not searched; the band then says so in one extra line ({@code
	 * review.match.tree.clustering.relationsNotSearched}, after the "only the pairs found this way" line, before the group count).
	 */
	private static String[] clusterBandLines(final List<String> pClusteringFields, final ReviewDecisionTree.Plan pPlan, final JsonNode pRequirements,
		final Map<String, MatchPlanFieldEvidence> pEvidenceByField, final Locale pLocale, final boolean pFoldVolumeNotes)
	{
		final List<String> others = new ArrayList<String>();
		final List<String> relationsNotSearched = new ArrayList<String>();
		for (final ReviewDecisionTree.Node node : pPlan.nodes)
		{
			for (final ReviewDecisionTree.Field field : node.fields)
			{
				if (field.isRelation() && MatchPlanTreeDiagram.relationClusteringWeight(pRequirements, field) == 0)
				{
					if (!relationsNotSearched.contains(field.field))
					{
						relationsNotSearched.add(field.field);
					}
					continue;
				}
				if (!pClusteringFields.contains(field.field) && !others.contains(field.field))
				{
					others.add(field.field);
				}
			}
		}
		final String and = ReviewMessages.get(pLocale, "review.match.tree.clustering.and");
		final String line1;
		if (pClusteringFields.isEmpty())
		{
			line1 = others.isEmpty() ? ReviewMessages.get(pLocale, "review.match.tree.clustering.none")
				: ReviewMessages.get(pLocale, "review.match.tree.clustering.line.noPrimary", FieldWords.humanizeAll(others, ", "));
		}
		else if (others.isEmpty())
		{
			line1 = ReviewMessages.get(pLocale, "review.match.tree.clustering.line", FieldWords.humanizeAll(pClusteringFields, and));
		}
		else
		{
			line1 = ReviewMessages.get(pLocale, "review.match.tree.clustering.line.then", FieldWords.humanizeAll(pClusteringFields, and), FieldWords.humanizeAll(others, ", "));
		}
		final String line2 = ReviewMessages.get(pLocale, "review.match.tree.clustering.only");
		final String lineRelations = relationsNotSearched.isEmpty() ? null
			: ReviewMessages.get(pLocale, "review.match.tree.clustering.relationsNotSearched", FieldWords.humanizeAll(relationsNotSearched, ", "));
		final MatchPlanFieldEvidence firstEvidence = pClusteringFields.isEmpty() ? null : pEvidenceByField.get(pClusteringFields.get(0));
		String line3 = null;
		if (firstEvidence != null && firstEvidence.distinct != null && firstEvidence.sharedAcrossSystems != null)
		{
			line3 = ReviewMessages.get(pLocale, "review.match.tree.clustering.numbers", firstEvidence.distinct, firstEvidence.sharedAcrossSystems, firstEvidence.distinct);
		}
		final String[] filter = MatchPlanTreeDiagram.candidateFilterLines(pRequirements, pLocale);
		// UX lot U1: the cost and reason lines go to the "Details" disclosure above the diagram when the caller folds them (see build)
		return new String[] { line1, line2, filter[0], pFoldVolumeNotes ? null : filter[1], pFoldVolumeNotes ? null : filter[2], lineRelations, line3 };
	}

	/**
	 * Lot M8a (2026-10-04, {@code docs/MATCHING.md} &sect;51): the verdict of {@link CandidateFilterCheck} on the plan, as up to three band lines - {@code [0]} the
	 * sentence (filtered on which keys, or NOT filtered and which paths have no exact key), {@code [1]} for NOT filtered the measured cost, {@code [2]} for NOT
	 * filtered the reason the plan was kept anyway when the model gave one. All null when there is nothing to say (no tree, or no path to a match). The state of the
	 * add-on JVM option is NOT here (this class stays pure): the wizard plan card says it.
	 */
	static String[] candidateFilterLines(final JsonNode pRequirements, final Locale pLocale)
	{
		final String[] lines = new String[3];
		final CandidateFilterCheck.Result result = CandidateFilterCheck.check(pRequirements);
		if (result.filtered())
		{
			final String or = ReviewMessages.get(pLocale, "review.match.tree.clustering.filtered.or");
			final String and = ReviewMessages.get(pLocale, "review.match.tree.clustering.filtered.and");
			final StringBuilder sets = new StringBuilder();
			for (final java.util.List<String> set : result.keySets())
			{
				sets.append(sets.length() == 0 ? "" : or);
				sets.append(set.size() == 1 ? FieldWords.humanize(set.get(0)) : "(" + FieldWords.humanizeAll(set, and) + ")");
			}
			lines[0] = ReviewMessages.get(pLocale, "review.match.tree.clustering.filtered", sets.toString());
		}
		else if (result.notFiltered())
		{
			final List<CandidateFilterCheck.PathResult> withoutKey = result.pathsWithoutKey();
			final StringBuilder labels = new StringBuilder();
			for (int i = 0; i < withoutKey.size() && i < 3; i++)
			{
				labels.append(i == 0 ? "" : ", ").append('\u00AB').append(withoutKey.get(i).label()).append('\u00BB');
			}
			if (withoutKey.size() > 3)
			{
				labels.append(" (+").append(withoutKey.size() - 3).append(')');
			}
			lines[0] = ReviewMessages.get(pLocale, withoutKey.size() == 1 ? "review.match.tree.clustering.notFiltered.one" : "review.match.tree.clustering.notFiltered.many",
				labels.toString());
			lines[1] = ReviewMessages.get(pLocale, "review.match.tree.clustering.notFiltered.cost");
			final String reason = CandidateFilterCheck.acceptedReason(pRequirements);
			if (!reason.isEmpty())
			{
				lines[2] = ReviewMessages.get(pLocale, CandidateFilterCheck.acceptedByFactory(pRequirements) ? "review.match.tree.clustering.notFiltered.acceptedByFactory"
					: "review.match.tree.clustering.notFiltered.accepted", reason);
			}
		}
		return lines;
	}

	/**
	 * Lot M2: the pre-clustering weight of a relation field as the plan stores it - {@code
	 * requirements.relationClusteringWeights["<via>.<field>"]}, an integer &gt;= 0; <b>0</b> when the key or the entry is
	 * absent (mirrors {@code master.MatchingPolicyTranslator.relationClusteringWeight}, which this package does not import).
	 */
	static int relationClusteringWeight(final JsonNode pRequirements, final ReviewDecisionTree.Field pField)
	{
		final JsonNode value = pRequirements == null ? null : pRequirements.path("relationClusteringWeights").path(pField.via + "." + pField.field);
		return value != null && value.isIntegralNumber() && value.asInt() > 0 ? value.asInt() : 0;
	}

	private static void renderClusterBand(final StringBuilder pOut, final String[] pLines, final int pBandHeight, final int pWidth, final Locale pLocale)
	{
		final int x = 10;
		final int bandWidth = pWidth - 20;
		pOut.append("<rect x=\"").append(x).append("\" y=\"6\" width=\"").append(bandWidth).append("\" height=\"").append(pBandHeight - 6)
			.append("\" rx=\"10\" fill=\"#f2f4f7\" stroke=\"#d0d5dd\"/>");
		pOut.append("<text x=\"").append(x + 14).append("\" y=\"22\" font-size=\"9\" font-weight=\"700\" letter-spacing=\".6\" fill=\"#667085\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.clustering.label"))).append("</text>");
		pOut.append("<text x=\"").append(x + 14).append("\" y=\"37\" font-size=\"12\" fill=\"#172033\">").append(ReviewPage.esc(pLines[0])).append("</text>");
		int y = 37;
		for (int i = 1; i < pLines.length; i++)
		{
			if (pLines[i] == null)
			{
				continue;
			}
			y += MatchPlanTreeDiagram.CLUSTER_BAND_NUMBERS_LINE;
			pOut.append("<text x=\"").append(x + 14).append("\" y=\"").append(y).append("\" font-size=\"11\" fill=\"#667085\">").append(ReviewPage.esc(pLines[i]))
				.append("</text>");
		}
	}

	/** True branch anchor sits 50 units left of the node's own centre, false branch 50 right - matches the mockup exactly, independent of node width. */
	private static final int BRANCH_ANCHOR_OFFSET = 50;

	private static void renderSubtree(final StringBuilder pOut, final ReviewDecisionTree.Plan pPlan, final MatchPlanTreeLayout.Result pLayout,
		final MatchPlanTreeLayout.Box pBox, final int pTopOffset, final boolean pAutoMerge, final Locale pLocale)
	{
		if (pBox.leaf)
		{
			MatchPlanTreeDiagram.renderLeaf(pOut, pBox, pTopOffset, pAutoMerge, pLocale);
			return;
		}
		final ReviewDecisionTree.Node node = pPlan.byId(pBox.id);
		if (pBox.foldRoot)
		{
			MatchPlanTreeDiagram.renderFoldPlaceholder(pOut, pBox, pTopOffset, pLayout, pLocale);
			pOut.append("<g class=\"mdmf-tree-real\" data-tree-real=\"").append(ReviewPage.esc(pBox.id)).append("\" style=\"display:none\">");
			MatchPlanTreeDiagram.renderNodeAndChildren(pOut, pPlan, pLayout, pBox, node, pTopOffset, pAutoMerge, pLocale);
			pOut.append("</g>");
		}
		else
		{
			MatchPlanTreeDiagram.renderNodeAndChildren(pOut, pPlan, pLayout, pBox, node, pTopOffset, pAutoMerge, pLocale);
		}
	}

	private static void renderNodeAndChildren(final StringBuilder pOut, final ReviewDecisionTree.Plan pPlan, final MatchPlanTreeLayout.Result pLayout,
		final MatchPlanTreeLayout.Box pBox, final ReviewDecisionTree.Node pNode, final int pTopOffset, final boolean pAutoMerge, final Locale pLocale)
	{
		MatchPlanTreeDiagram.renderNodeCard(pOut, pBox, pNode, pTopOffset, pLocale);
		final MatchPlanTreeLayout.Box trueChild = MatchPlanTreeDiagram.childBox(pLayout, pBox.id, pNode.onTrue, Boolean.TRUE);
		final MatchPlanTreeLayout.Box falseChild = MatchPlanTreeDiagram.childBox(pLayout, pBox.id, pNode.onFalse, Boolean.FALSE);
		MatchPlanTreeDiagram.renderConnector(pOut, pBox, trueChild, true, pNode, pTopOffset, pLocale);
		MatchPlanTreeDiagram.renderConnector(pOut, pBox, falseChild, false, pNode, pTopOffset, pLocale);
		MatchPlanTreeDiagram.renderSubtree(pOut, pPlan, pLayout, trueChild, pTopOffset, pAutoMerge, pLocale);
		MatchPlanTreeDiagram.renderSubtree(pOut, pPlan, pLayout, falseChild, pTopOffset, pAutoMerge, pLocale);
	}

	private static MatchPlanTreeLayout.Box childBox(final MatchPlanTreeLayout.Result pLayout, final String pParentId, final String pTarget, final Boolean pBranch)
	{
		if (ReviewDecisionTree.isLeaf(pTarget))
		{
			return pLayout.boxes.get(pParentId + (Boolean.TRUE.equals(pBranch) ? ":T" : ":F"));
		}
		final MatchPlanTreeLayout.Box box = pLayout.boxes.get(pTarget);
		return box != null ? box : pLayout.boxes.get(pParentId + (Boolean.TRUE.equals(pBranch) ? ":T" : ":F"));
	}

	private static void renderConnector(final StringBuilder pOut, final MatchPlanTreeLayout.Box pParent, final MatchPlanTreeLayout.Box pChild, final boolean pTrueBranch,
		final ReviewDecisionTree.Node pNode, final int pTopOffset, final Locale pLocale)
	{
		if (pChild == null)
		{
			return;
		}
		final int anchorX = (int) Math.round(pParent.cx) + (pTrueBranch ? -MatchPlanTreeDiagram.BRANCH_ANCHOR_OFFSET : MatchPlanTreeDiagram.BRANCH_ANCHOR_OFFSET);
		final int fromY = pParent.y + pParent.height() + pTopOffset;
		final int toY = pChild.y + pTopOffset;
		final int midY = fromY + (toY - fromY) / 2;
		final int childCx = (int) Math.round(pChild.cx);
		final String color = pTrueBranch ? "#087443" : "#98a2b3";
		final String width = pTrueBranch ? "2.5" : "2";
		pOut.append("<path d=\"M").append(anchorX).append(',').append(fromY).append(" L").append(anchorX).append(',').append(midY).append(" L").append(childCx)
			.append(',').append(midY).append(" L").append(childCx).append(',').append(toY - 2).append("\" stroke=\"").append(color).append("\" stroke-width=\"")
			.append(width).append("\" fill=\"none\" marker-end=\"url(#mdmfArrow)\"/>");
		final String label = MatchPlanTreeDiagram.branchLabel(pNode, pTrueBranch, pLocale);
		// Beside the connector's own FIRST vertical segment (anchorX, from fromY to midY), never centred on the
		// horizontal run - a centred label collides with whichever vertical segment sits closest whenever the
		// two ends of that run are close together (tree-diagram-labels fix lot, 2026-09-22, see class Javadoc).
		final int labelX = anchorX + MatchPlanTreeDiagram.BRANCH_LABEL_X_GAP;
		final int labelY = (fromY + midY) / 2 + 3;
		pOut.append("<text x=\"").append(labelX).append("\" y=\"").append(labelY).append("\" font-size=\"10\" font-weight=\"700\" fill=\"").append(color)
			.append("\" style=\"").append(MatchPlanTreeDiagram.BRANCH_LABEL_HALO_STYLE).append("\">").append(ReviewPage.esc(label)).append("</text>");
	}

	private static String branchLabel(final ReviewDecisionTree.Node pNode, final boolean pTrueBranch, final Locale pLocale)
	{
		if (ReviewDecisionTree.FUNCTION_AVERAGE.equals(pNode.function))
		{
			final int minimumScore = pNode.minimumScore == null ? 90 : pNode.minimumScore.intValue();
			return ReviewMessages.get(pLocale, pTrueBranch ? "review.match.tree.branch.average.true" : "review.match.tree.branch.average.false",
				Integer.valueOf(minimumScore));
		}
		// tree-plain-words lot (2026-09-23): each function gets its own pair of short words ("all identical" /
		// "one differs", "enough" / "not enough"); the generic "passes" / "does not pass" stays for a predicate
		// node and for MatchSimulationBlock's own path wording.
		if (ReviewDecisionTree.FUNCTION_ALL_FIELDS.equals(pNode.function))
		{
			return ReviewMessages.get(pLocale, pTrueBranch ? "review.match.tree.branch.allFields.true" : "review.match.tree.branch.allFields.false");
		}
		if (ReviewDecisionTree.FUNCTION_AT_LEAST_N.equals(pNode.function))
		{
			return ReviewMessages.get(pLocale, pTrueBranch ? "review.match.tree.branch.atLeastN.true" : "review.match.tree.branch.atLeastN.false");
		}
		return ReviewMessages.get(pLocale, pTrueBranch ? "review.match.tree.branch.true" : "review.match.tree.branch.false");
	}

	// ------------------------------------------------------------------------------------------------- node card

	private static void renderNodeCard(final StringBuilder pOut, final MatchPlanTreeLayout.Box pBox, final ReviewDecisionTree.Node pNode, final int pTopOffset,
		final Locale pLocale)
	{
		final int x = (int) Math.round(pBox.left());
		final int y = pBox.y + pTopOffset;
		pOut.append("<g class=\"mdmf-tree-node\" data-tree-node=\"").append(ReviewPage.esc(pBox.id)).append("\">");
		pOut.append("<rect x=\"").append(x).append("\" y=\"").append(y).append("\" width=\"").append(pBox.width()).append("\" height=\"").append(pBox.height())
			.append("\" rx=\"10\" fill=\"#fff\" style=\"stroke:var(--accent-fg)\" stroke-width=\"1.5\"/>"); // audit F-44: the perspective's main colour (factory.css)
		pOut.append("<text x=\"").append(x + 12).append("\" y=\"").append(y + 17)
			.append("\" font-size=\"8.5\" font-weight=\"700\" letter-spacing=\".6\" style=\"fill:var(--accent-fg)\">").append(ReviewPage.esc(MatchPlanTreeDiagram.headerText(pNode, pLocale)))
			.append("</text>");
		final String bodyFull = MatchPlanTreeDiagram.bodyText(pNode, pLocale);
		// tree-plain-words lot (2026-09-23): wrapped over up to NODE_BODY_MAX_LINES tspans (SVG text never wraps
		// on its own); the card's own height was sized for the plan's longest list by buildSvg.
		final List<String> bodyLines = MatchPlanTreeDiagram.wrap(bodyFull, MatchPlanTreeDiagram.NODE_BODY_WRAP_CHARS, MatchPlanTreeDiagram.NODE_BODY_MAX_LINES);
		pOut.append("<text x=\"").append(x + 12).append("\" y=\"").append(y + 35).append("\" font-size=\"11.5\" fill=\"#172033\">");
		for (int i = 0; i < bodyLines.size(); i++)
		{
			pOut.append("<tspan x=\"").append(x + 12).append("\" dy=\"").append(i == 0 ? 0 : MatchPlanTreeLayout.BODY_LINE_HEIGHT).append("\">")
				.append(ReviewPage.esc(bodyLines.get(i))).append("</tspan>");
		}
		if (!String.join(" ", bodyLines).equals(bodyFull))
		{
			// a list longer than three full lines is still cut - the full text reaches the reviewer as a hover tooltip.
			pOut.append("<title>").append(ReviewPage.esc(bodyFull)).append("</title>");
		}
		pOut.append("</text>");
		if (pNode.rationale != null && !pNode.rationale.trim().isEmpty())
		{
			final int rationaleY = y + 35 + bodyLines.size() * MatchPlanTreeLayout.BODY_LINE_HEIGHT;
			pOut.append("<text x=\"").append(x + 12).append("\" y=\"").append(rationaleY).append("\" font-size=\"10\" fill=\"#667085\">")
				.append(ReviewPage.esc(MatchPlanTreeDiagram.truncate(pNode.rationale.trim(), MatchPlanTreeDiagram.NOTE_CHARS_BUDGET))).append("</text>");
		}
		pOut.append("</g>");
	}

	private static String headerText(final ReviewDecisionTree.Node pNode, final Locale pLocale)
	{
		if (ReviewDecisionTree.FUNCTION_AVERAGE.equals(pNode.function))
		{
			return ReviewMessages.get(pLocale, "review.match.tree.node.average", pNode.id, pNode.minimumScore == null ? Integer.valueOf(90) : pNode.minimumScore);
		}
		if (ReviewDecisionTree.FUNCTION_AT_LEAST_N.equals(pNode.function))
		{
			return ReviewMessages.get(pLocale, "review.match.tree.node.atLeastN", pNode.id, pNode.minimumMatches == null ? Integer.valueOf(1) : pNode.minimumMatches,
				Integer.valueOf(pNode.fields.size()));
		}
		if (ReviewDecisionTree.FUNCTION_PREDICATE.equals(pNode.function))
		{
			return ReviewMessages.get(pLocale, "review.match.tree.node.predicate", pNode.id);
		}
		return ReviewMessages.get(pLocale, "review.match.tree.node.allFields", pNode.id);
	}

	/** A compact, single-line field summary - the full per-field detail (algorithm, weight/minimum, null handling) is reserved for {@link #renderDetailPanels}. */
	private static String bodyText(final ReviewDecisionTree.Node pNode, final Locale pLocale)
	{
		if (ReviewDecisionTree.FUNCTION_AVERAGE.equals(pNode.function))
		{
			// tree-plain-words lot (2026-09-23): "First name (weight 2), Last name (weight 3), Email" - a weight of
			// 1 is the default and is not spelled out; the detail panel keeps the exact per-field numbers.
			final StringBuilder out = new StringBuilder();
			for (final ReviewDecisionTree.Field field : pNode.fields)
			{
				if (out.length() > 0)
				{
					out.append(", ");
				}
				out.append(FieldWords.humanize(field.field, field.via));
				final int weight = field.weight == null ? 1 : field.weight.intValue();
				if (weight != 1)
				{
					out.append(' ').append(ReviewMessages.get(pLocale, "review.match.tree.node.body.weight", Integer.valueOf(weight)));
				}
			}
			return out.toString();
		}
		final String fields = MatchPlanTreeDiagram.joinFieldNames(pNode.fields, ", ");
		if (ReviewDecisionTree.FUNCTION_AT_LEAST_N.equals(pNode.function))
		{
			return ReviewMessages.get(pLocale, "review.match.tree.node.body.atLeastN", fields);
		}
		if (ReviewDecisionTree.FUNCTION_PREDICATE.equals(pNode.function))
		{
			return ReviewMessages.get(pLocale, "review.match.tree.node.body.predicate", fields);
		}
		return ReviewMessages.get(pLocale, "review.match.tree.node.body.allFields", fields);
	}

	private static String joinFieldNames(final List<ReviewDecisionTree.Field> pFields, final String pSeparator)
	{
		final StringBuilder out = new StringBuilder();
		for (final ReviewDecisionTree.Field field : pFields)
		{
			if (out.length() > 0)
			{
				out.append(pSeparator);
			}
			out.append(FieldWords.humanize(field.field, field.via));
		}
		return out.toString();
	}

	/**
	 * Greedy word wrap into at most {@code pMaxLines} lines of about {@code pMaxChars} characters (a single word
	 * longer than a line is kept whole on its own line); when the text does not fit, the last line ends with
	 * an ellipsis - tree-plain-words lot (2026-09-23). Package-private for the test.
	 */
	static List<String> wrap(final String pText, final int pMaxChars, final int pMaxLines)
	{
		final List<String> lines = new ArrayList<String>();
		final String text = pText == null ? "" : pText.trim();
		if (text.isEmpty())
		{
			lines.add("");
			return lines;
		}
		final String[] words = text.split(" +");
		StringBuilder current = new StringBuilder();
		int consumed = 0;
		for (final String word : words)
		{
			if (current.length() > 0 && current.length() + 1 + word.length() > pMaxChars)
			{
				if (lines.size() == pMaxLines - 1)
				{
					break;
				}
				lines.add(current.toString());
				current = new StringBuilder();
			}
			if (current.length() > 0)
			{
				current.append(' ');
			}
			current.append(word);
			consumed++;
		}
		if (consumed < words.length)
		{
			// out of lines: cut the last line to fit, with an ellipsis
			lines.add(MatchPlanTreeDiagram.truncate(current.toString() + " …", pMaxChars));
			return lines;
		}
		lines.add(current.toString());
		return lines;
	}

	private static String truncate(final String pText, final int pMaxChars)
	{
		if (pText.length() <= pMaxChars)
		{
			return pText;
		}
		return pText.substring(0, Math.max(0, pMaxChars - 1)) + "…";
	}

	// -------------------------------------------------------------------------------------------------- leaves

	private static void renderLeaf(final StringBuilder pOut, final MatchPlanTreeLayout.Box pBox, final int pTopOffset, final boolean pAutoMerge, final Locale pLocale)
	{
		final int x = (int) Math.round(pBox.left());
		final int y = pBox.y + pTopOffset;
		final boolean realMatch = ReviewDecisionTree.LEAF_MATCH.equals(pBox.leafKind) && pAutoMerge;
		final boolean asSuspect = ReviewDecisionTree.LEAF_SUSPECTED.equals(pBox.leafKind) || ReviewDecisionTree.LEAF_MATCH.equals(pBox.leafKind) && !pAutoMerge;
		final String fill = realMatch ? "#ecfdf3" : asSuspect ? "#fffaeb" : "#fff";
		final String stroke = realMatch ? "#b7e3ca" : asSuspect ? "#fedf89" : "#d0d5dd";
		final String titleColor = realMatch ? "#087443" : asSuspect ? "#b54708" : "#667085";
		final String titleKey = realMatch ? "review.match.tree.leaf.merged.title" : asSuspect ? "review.match.tree.leaf.suspect.title" : "review.match.tree.leaf.notmatch.title";
		final String textKey = realMatch ? "review.match.tree.leaf.merged.text" : asSuspect ? "review.match.tree.leaf.suspect.text" : "review.match.tree.leaf.notmatch.text";
		pOut.append("<g class=\"mdmf-tree-leaf\">");
		pOut.append("<rect x=\"").append(x).append("\" y=\"").append(y).append("\" width=\"").append(pBox.width()).append("\" height=\"").append(pBox.height())
			.append("\" rx=\"9\" fill=\"").append(fill).append("\" stroke=\"").append(stroke).append("\"/>");
		pOut.append("<text x=\"").append(x + 10).append("\" y=\"").append(y + 15)
			.append("\" font-size=\"8.5\" font-weight=\"700\" letter-spacing=\".6\" fill=\"").append(titleColor).append("\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, titleKey))).append("</text>");
		pOut.append("<text x=\"").append(x + 10).append("\" y=\"").append(y + 30).append("\" font-size=\"10.5\" font-weight=\"700\" fill=\"#172033\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, textKey))).append("</text>");
		if (asSuspect)
		{
			// the box itself has no room for a third line at this size (unlike the pre-lot cascade's own taller
			// leaf) - the explanation still reaches the page as a hover tooltip, same wording as before.
			pOut.append("<title>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.leaf.suspect.sub"))).append("</title>");
		}
		pOut.append("</g>");
	}

	// --------------------------------------------------------------------------------------------------- folding

	private static void renderFoldPlaceholder(final StringBuilder pOut, final MatchPlanTreeLayout.Box pBox, final int pTopOffset, final MatchPlanTreeLayout.Result pLayout,
		final Locale pLocale)
	{
		final int x = (int) Math.round(pBox.left());
		final int y = pBox.y + pTopOffset;
		final int count = MatchPlanTreeLayout.subtreeSize(pLayout.boxes, pBox.id);
		pOut.append("<g class=\"mdmf-tree-fold\" data-tree-fold=\"").append(ReviewPage.esc(pBox.id)).append("\">");
		pOut.append("<rect x=\"").append(x).append("\" y=\"").append(y).append("\" width=\"").append(pBox.width()).append("\" height=\"").append(pBox.height())
			.append("\" rx=\"10\" fill=\"#f9fafb\" stroke=\"#d0d5dd\" stroke-dasharray=\"4 3\"/>");
		pOut.append("<text x=\"").append(x + pBox.width() / 2).append("\" y=\"").append(y + pBox.height() / 2 + 5)
			.append("\" font-size=\"12\" font-weight=\"700\" fill=\"#475467\" text-anchor=\"middle\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.fold.label", Integer.valueOf(count)))).append("</text>");
		pOut.append("</g>");
	}

	// -------------------------------------------------------------------------------------------- detail panels

	private static void renderDetailPanels(final StringBuilder pOut, final ReviewDecisionTree.Plan pPlan, final Locale pLocale)
	{
		boolean first = true;
		for (final ReviewDecisionTree.Node node : pPlan.nodes)
		{
			pOut.append("<div class=\"mdmf-tree-detail").append(first ? " mdmf-tree-detail-selected" : "").append("\" data-tree-detail=\"")
				.append(ReviewPage.esc(node.id)).append("\" style=\"display:").append(first ? "block" : "none").append("\">");
			pOut.append("<div class=\"mdmf-tree-detail-head\"><b>").append(ReviewPage.esc(node.id)).append("</b> <span class=\"mdmf-chip mdmf-chip-accent\">")
				.append(ReviewPage.esc(MatchPlanTreeDiagram.headerText(node, pLocale))).append("</span></div>");
			// tree-diagram-labels fix lot, 2026-09-22, item A: table-layout:fixed (review.css) needs the SAME
			// column count/order every row - the value column's header switches Weight/Min by function, same as
			// the cell content already did, so header and cells never disagree about what the column holds.
			final String valueHeaderKey = ReviewDecisionTree.FUNCTION_AVERAGE.equals(node.function) ? "review.match.tree.detail.col.weight"
				: "review.match.tree.detail.col.min";
			pOut.append("<table class=\"mdmf-table mdmf-tree-detail-table\"><tr><th>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.detail.col.field")))
				.append("</th><th>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.detail.col.algorithm"))).append("</th><th>")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, valueHeaderKey))).append("</th><th>")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.detail.col.nulls"))).append("</th></tr>");
			for (final ReviewDecisionTree.Field field : node.fields)
			{
				pOut.append("<tr><td class=\"k\" title=\"").append(ReviewPage.esc(field.field)).append("\">").append(ReviewPage.esc(FieldWords.humanize(field.field, field.via)))
					.append("</td><td title=\"").append(ReviewPage.esc(ProposalWording.matchAlgorithmWords(field.algorithm, pLocale))).append("\">")
					.append(ReviewPage.esc(ProposalWording.matchAlgorithmShortWords(field.algorithm, pLocale))).append("</td><td>");
				if (ReviewDecisionTree.FUNCTION_AVERAGE.equals(node.function))
				{
					pOut.append(field.weight == null ? 1 : field.weight.intValue());
				}
				else if (ReviewDecisionTree.FUNCTION_PREDICATE.equals(node.function))
				{
					pOut.append(ReviewPage.esc(field.predicate == null ? "" : field.predicate));
				}
				else
				{
					pOut.append(field.minimumScore == null ? 100 : field.minimumScore.intValue());
				}
				// the raw bothNull/oneNull values (an add-on enum, can be a longer technical string) reach the
				// reviewer as a hover tooltip; the cell itself shows one of a fixed, always-short set of words.
				pOut.append("</td><td title=\"").append(ReviewPage.esc(MatchPlanTreeDiagram.nullWords(field, pLocale))).append("\">")
					.append(ReviewPage.esc(MatchPlanTreeDiagram.nullShortWord(field, pLocale))).append("</td></tr>");
			}
			pOut.append("</table>");
			if (node.rationale != null && !node.rationale.trim().isEmpty())
			{
				pOut.append("<p class=\"mdmf-hint\"><b>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.detail.rationale"))).append("</b> ")
					.append(ReviewPage.esc(node.rationale)).append("</p>");
			}
			pOut.append("<p class=\"mdmf-hint mdmf-tree-reachedby\">").append(ReviewPage.esc(MatchPlanTreeDiagram.reachedByWords(pPlan, node, pLocale))).append("</p>");
			pOut.append("</div>");
			first = false;
		}
	}

	private static String nullWords(final ReviewDecisionTree.Field pField, final Locale pLocale)
	{
		if (pField.bothNull == null && pField.oneNull == null)
		{
			return "-";
		}
		final StringBuilder out = new StringBuilder();
		if (pField.bothNull != null)
		{
			out.append(pField.bothNull);
		}
		if (pField.oneNull != null)
		{
			if (out.length() > 0)
			{
				out.append(" / ");
			}
			out.append(pField.oneNull);
		}
		return out.toString();
	}

	/**
	 * The detail table's Null cell must always fit its own narrow column ({@code docs/MATCHING.md} &sect;16.10,
	 * item A) - a raw add-on enum value ({@code DO_NOTHING}/{@code NOT_MATCH}/{@code MATCH}, per
	 * {@code master.MatchingPolicyTranslator}) is mapped to one short, always-known-length word; the full
	 * {@link #nullWords} text (both {@code bothNull} and {@code oneNull} when they differ) still reaches the
	 * reviewer as the cell's {@code title} tooltip. {@code bothNull} wins over {@code oneNull} when both are set
	 * and disagree - a reviewer wanting the other one's own value has the tooltip. An enum value this class does
	 * not recognize (a future add-on addition) falls back to showing that raw value verbatim, never blank.
	 */
	private static String nullShortWord(final ReviewDecisionTree.Field pField, final Locale pLocale)
	{
		final String raw = pField.bothNull != null ? pField.bothNull : pField.oneNull;
		if (raw == null)
		{
			return ReviewMessages.get(pLocale, "review.match.tree.detail.null.dash");
		}
		if ("DO_NOTHING".equals(raw))
		{
			return ReviewMessages.get(pLocale, "review.match.tree.detail.null.ignore");
		}
		if ("NOT_MATCH".equals(raw))
		{
			return ReviewMessages.get(pLocale, "review.match.tree.detail.null.noMatch");
		}
		if ("MATCH".equals(raw))
		{
			return ReviewMessages.get(pLocale, "review.match.tree.detail.null.match");
		}
		return raw;
	}

	/** "Reached by: {parentId} {branchWord}" for any node but the root, "Root node" for the root - see task brief, the detail panel's own single-hop parent edge (not the full path, which is section 3's job). */
	private static String reachedByWords(final ReviewDecisionTree.Plan pPlan, final ReviewDecisionTree.Node pNode, final Locale pLocale)
	{
		for (final ReviewDecisionTree.Node candidate : pPlan.nodes)
		{
			if (pNode.id.equals(candidate.onTrue))
			{
				return ReviewMessages.get(pLocale, "review.match.tree.detail.reachedBy", candidate.id,
					MatchPlanTreeDiagram.branchLabel(candidate, true, pLocale));
			}
			if (pNode.id.equals(candidate.onFalse))
			{
				return ReviewMessages.get(pLocale, "review.match.tree.detail.reachedBy", candidate.id,
					MatchPlanTreeDiagram.branchLabel(candidate, false, pLocale));
			}
		}
		return ReviewMessages.get(pLocale, "review.match.tree.detail.root");
	}

	// --------------------------------------------------------------------------------------------------- script

	/**
	 * Node selection and fold expansion - both purely client-side (task brief: "costs no round trip"), scoped
	 * per {@code .mdmf-tree-diagram} container so several expanded MATCH lines on the same page each keep
	 * their own selection independently. Appended by {@code ebx.ui.ProposalReviewUserService.pageJs} into the
	 * page's single {@code addJS_cr} script (CLAUDE.md &sect;9: never a hand-written {@code <script>} tag).
	 */
	static final String SCRIPT = "root.querySelectorAll('.mdmf-tree-node').forEach(function(nodeEl){nodeEl.addEventListener('click',function(){"
		+ "var diagram=nodeEl.closest('.mdmf-tree-diagram');if(!diagram)return;var id=nodeEl.getAttribute('data-tree-node');"
		+ "diagram.querySelectorAll('.mdmf-tree-detail').forEach(function(d){d.style.display='none';});"
		+ "var sel=diagram.querySelector('.mdmf-tree-detail-col [data-tree-detail=\"'+id+'\"]');if(sel)sel.style.display='block';"
		+ "diagram.querySelectorAll('.mdmf-tree-node').forEach(function(n){n.classList.remove('mdmf-tree-node-selected');});"
		+ "nodeEl.classList.add('mdmf-tree-node-selected');});});"
		+ "root.querySelectorAll('.mdmf-tree-fold').forEach(function(foldEl){foldEl.addEventListener('click',function(){"
		+ "var diagram=foldEl.closest('.mdmf-tree-diagram');if(!diagram)return;var id=foldEl.getAttribute('data-tree-fold');"
		+ "var real=diagram.querySelector('[data-tree-real=\"'+id+'\"]');if(real)real.style.display='block';foldEl.style.display='none';});});";
}
