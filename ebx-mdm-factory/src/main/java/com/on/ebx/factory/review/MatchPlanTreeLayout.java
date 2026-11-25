package com.on.ebx.factory.review;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure geometry for {@link MatchPlanTreeDiagram} - binary-decision-tree-screen lot, 2026-09-22, validated
 * against {@code docs/mockups/review-match-plan-binary-tree.html}'s own six-node Company example (see
 * {@code MatchPlanTreeLayoutTest}, which pins these exact coordinates node by node). No SVG, no HTML, no
 * i18n - {@link MatchPlanTreeDiagram} is the only caller.
 * <p>
 * <b>The algorithm</b> (task brief: "one column per leaf, one row per level, a leaf sits right under its
 * parent"):
 * <ol>
 * <li>Walk the tree in pre-order, TRUE branch before FALSE (matching the "left = passes" convention of the
 * mockup), assigning every LEAF OCCURRENCE (not every leaf outcome - the same outcome, e.g. {@code
 * SUSPECTED}, can appear as several distinct boxes) the next column index, {@code 0, 1, 2, ...}.</li>
 * <li>A node's own column range is the {@code [min, max]} of its descendant leaves' columns (bottom-up);
 * its {@code x} is the MIDPOINT of that range, never the average of its two children's own {@code x} (the
 * two are only the same when a subtree's leaves are evenly spread - not true in general, see the class
 * Javadoc example measured against the mockup: {@code N1}'s two children cover 3 and 4 leaf-columns
 * respectively, so averaging their centres would be off by 35 units against the mockup's own drawing).
 * The SAME formula gives a leaf's own {@code x} too (a leaf's range is exactly its own single column), so
 * {@link Box#cx} is computed identically for a node or a leaf.</li>
 * <li>{@code y} is simply {@code depth * ROW_SPACING} - a leaf's depth is its parent's depth plus one, so a
 * leaf reached one level down from the root sits in the SAME row as a sibling internal node reached at the
 * same depth (the mockup's own leaves are NOT all pushed down to the tree's maximum depth).</li>
 * </ol>
 * <b>Folding</b> (task brief: "beyond 12 leaves, subtrees below depth 2 fold"): when the tree has more than
 * {@link #FOLD_LEAF_THRESHOLD} leaf occurrences, every INTERNAL node at depth exactly 2 becomes a
 * {@link Box#foldRoot} - every box in its subtree (including itself) carries {@link Box#hiddenGroupId} set
 * to that node's own id, which {@link MatchPlanTreeDiagram} uses to wrap the real content in an initially
 * hidden SVG group behind a "+N nodes" placeholder. Depth exactly 2 is enough to catch every deeper node
 * too: depth increases by exactly one per edge, so any node at depth &ge; 2 has exactly one ancestor at
 * depth 2 (or IS that ancestor) - there is never more than one fold boundary on any root-to-leaf path.
 * <b>Known simplification, documented rather than silently accepted</b>: folding hides CONTENT, never
 * RESERVED WIDTH - a folded subtree's leaves still occupy their normal columns (so the canvas is sized for
 * the FULL, unfolded tree even while parts of it are collapsed). This keeps one single layout pass valid
 * whether or not anything ends up folded (expanding a fold never needs a re-layout, since the coordinates
 * were already correct) at the cost of a folded tree sometimes leaving blank horizontal space next to a
 * collapsed placeholder - acceptable given the task brief's own "wider than the page scrolls horizontally"
 * rule and the effort this lot had budgeted for a fully compacting alternative (a nested, re-centred local
 * layout per collapsed subtree).
 * <p>
 * <b>Leaf width follows its own text, column spacing follows the widest leaf</b> (tree-diagram-labels fix lot,
 * 2026-09-22, {@code docs/MATCHING.md} &sect;16.10 - the product owner's live screenshot showed the SUSPECT leaf's own
 * wording clipped at the pre-lot fixed 120px): {@link #leafWidthFor} sizes a leaf from the LONGER of its two
 * text lines (~6.2px/char at 10.5px bold, +24px padding, clamped to {@link #MIN_LEAF_WIDTH}/{@link
 * #MAX_LEAF_WIDTH}) - the caller ({@link MatchPlanTreeDiagram#computeLeafWidths}) resolves the actual localized
 * strings and passes back only a plain {@code Map<leafKind, width>}, so this class stays free of i18n. The
 * column pitch is no longer the fixed 140 it always was - it is {@code max(MIN_COLUMN_SPACING, widestLeaf +
 * 20)}, widest over every leaf occurrence THIS plan actually draws, so a wide leaf never overlaps its neighbour
 * column regardless of how the two-line text measures. {@link #layout(ReviewDecisionTree.Plan)} (no widths)
 * keeps every leaf at {@link #MIN_LEAF_WIDTH} - the exact pre-lot behaviour, column pitch included - which is
 * why {@code MatchPlanTreeLayoutTest}'s own mockup-pinned coordinates needed no change.
 */
final class MatchPlanTreeLayout
{
	/** Floor for the column pitch - the pre-lot fixed value, kept as a minimum once a wide leaf can grow it. */
	static final int MIN_COLUMN_SPACING = 140;
	static final int ROW_SPACING = 110;
	static final int NODE_WIDTH = 220;
	static final int NODE_HEIGHT = 60;
	/** The pre-lot fixed leaf width - now also the floor of {@link #leafWidthFor}'s clamp. */
	static final int MIN_LEAF_WIDTH = 120;
	/** Ceiling of {@link #leafWidthFor}'s clamp - task brief: "maximum 200". */
	static final int MAX_LEAF_WIDTH = 200;
	static final int LEAF_HEIGHT = 40;
	/** Task brief: "beyond 12 leaves". */
	static final int FOLD_LEAF_THRESHOLD = 12;
	/** Depth at which a subtree folds - task brief: "subtrees below depth 2 fold" (see class Javadoc for why depth 2 alone catches every deeper node too). */
	static final int FOLD_DEPTH = 2;
	/** ~px per character at the leaf's 10.5px bold text, for {@link #leafWidthFor} - task brief's own approximation. */
	private static final double LEAF_CHAR_WIDTH_PX = 6.2;
	/** Left+right padding budget added on top of the raw character width, for {@link #leafWidthFor}. */
	private static final int LEAF_TEXT_PADDING_PX = 24;

	private MatchPlanTreeLayout()
	{
	}

	/**
	 * Width (px) for a leaf box sized to the LONGER of its two text lines - task brief formula: ~{@link
	 * #LEAF_CHAR_WIDTH_PX} per character at 10.5px bold, plus {@link #LEAF_TEXT_PADDING_PX} of padding, clamped
	 * to [{@link #MIN_LEAF_WIDTH}, {@link #MAX_LEAF_WIDTH}]. Pure text-length math, no i18n: the caller resolves
	 * the actual localized title/body strings first. Either argument may be {@code null} (treated as empty).
	 */
	static int leafWidthFor(final String pLine1, final String pLine2)
	{
		final int len1 = pLine1 == null ? 0 : pLine1.length();
		final int len2 = pLine2 == null ? 0 : pLine2.length();
		final int longest = Math.max(len1, len2);
		final int computed = (int) Math.ceil(longest * MatchPlanTreeLayout.LEAF_CHAR_WIDTH_PX) + MatchPlanTreeLayout.LEAF_TEXT_PADDING_PX;
		return Math.max(MatchPlanTreeLayout.MIN_LEAF_WIDTH, Math.min(MatchPlanTreeLayout.MAX_LEAF_WIDTH, computed));
	}

	/** One node or leaf-occurrence box, positioned. A leaf occurrence's own id is synthetic (never collides with a real node id): {@code "<parentNodeId>:T"}/{@code ":F"}. */
	static final class Box
	{
		final String id;
		final boolean leaf;
		/** {@link ReviewDecisionTree#LEAF_MATCH}/{@code LEAF_SUSPECTED}/{@code LEAF_NOT_MATCHED} for a leaf, else null. */
		final String leafKind;
		/** The owning node's id - null only for the root box itself. */
		final String parentId;
		/** {@code TRUE} (this box is its parent's {@code onTrue} target), {@code FALSE}, or null for the root. */
		final Boolean parentBranch;
		final int depth;
		final int minLeafColumn;
		final int maxLeafColumn;
		final int boxWidth;
		final int y;
		/** Set once by {@link #placeX} once the plan-wide column spacing is known (needs every leaf's width first) - never read before {@link #layout} returns. */
		double cx;
		/** Only ever true for an internal (non-leaf) node - see class Javadoc "Folding". */
		boolean foldRoot;
		/** Non-null when this box sits inside a folded subtree (including the fold root itself) - the id of that subtree's own fold root. Null when nothing is folded, or this box is fully visible. */
		String hiddenGroupId;

		/** tree-plain-words lot (2026-09-23): a node card's height for THIS plan (uniform across the plan's nodes, see {@link Geometry}); {@link #LEAF_HEIGHT} for a leaf. */
		final int boxHeight;

		private Box(final String pId, final boolean pLeaf, final String pLeafKind, final String pParentId, final Boolean pParentBranch, final int pDepth,
			final int pMinLeafColumn, final int pMaxLeafColumn, final int pWidth, final int pHeight, final int pRowSpacing)
		{
			this.id = pId;
			this.leaf = pLeaf;
			this.leafKind = pLeafKind;
			this.parentId = pParentId;
			this.parentBranch = pParentBranch;
			this.depth = pDepth;
			this.minLeafColumn = pMinLeafColumn;
			this.maxLeafColumn = pMaxLeafColumn;
			this.boxWidth = pWidth;
			this.boxHeight = pHeight;
			this.y = pDepth * pRowSpacing;
		}

		/** Sets {@link #cx} from this box's own leaf-column range and the plan-wide column pitch, once {@link #layout} knows it (needs the widest leaf across every box first). */
		void placeX(final int pColSpacing)
		{
			this.cx = pColSpacing / 2.0 + (this.minLeafColumn + this.maxLeafColumn) / 2.0 * pColSpacing;
		}

		int width()
		{
			return this.boxWidth;
		}

		int height()
		{
			return this.boxHeight;
		}

		double left()
		{
			return this.cx - this.width() / 2.0;
		}

		double right()
		{
			return this.cx + this.width() / 2.0;
		}

		int bottom()
		{
			return this.y + this.height();
		}
	}

	static final class Result
	{
		/** Keyed by {@link Box#id} - every real node id, plus one synthetic entry per leaf occurrence. Iteration order is pre-order (build order), stable for tests. */
		final Map<String, Box> boxes = new LinkedHashMap<String, Box>();
		final String rootId;
		final int leafCount;
		final int maxDepth;
		final boolean folded;
		/** The column pitch actually used for this plan - {@link #MIN_COLUMN_SPACING} unless a leaf's own width pushed it wider. */
		final double colSpacing;
		final double width;
		final double height;

		private Result(final String pRootId, final int pLeafCount, final int pMaxDepth, final boolean pFolded, final double pColSpacing, final double pWidth,
			final double pHeight)
		{
			this.rootId = pRootId;
			this.leafCount = pLeafCount;
			this.maxDepth = pMaxDepth;
			this.folded = pFolded;
			this.colSpacing = pColSpacing;
			this.width = pWidth;
			this.height = pHeight;
		}
	}

	/**
	 * tree-plain-words lot (2026-09-23): the node-card size for one plan - {@code MatchPlanTreeDiagram} widens
	 * the cards to {@link #WIDE_NODE_WIDTH} and grows them by one text line per extra body line (the field
	 * list now wraps instead of being cut at 40 characters - the product owner: "l'affichage est rogne, il faut utiliser
	 * plus de place"), uniform across the plan so rows stay aligned. {@link #DEFAULT} is the pre-lot geometry,
	 * what the two-argument {@link #layout(ReviewDecisionTree.Plan, Map)} still uses (its pinned test
	 * coordinates are unchanged).
	 */
	static final class Geometry
	{
		static final Geometry DEFAULT = new Geometry(MatchPlanTreeLayout.NODE_WIDTH, MatchPlanTreeLayout.NODE_HEIGHT, MatchPlanTreeLayout.ROW_SPACING);

		final int nodeWidth;
		final int nodeHeight;
		final int rowSpacing;

		Geometry(final int pNodeWidth, final int pNodeHeight, final int pRowSpacing)
		{
			this.nodeWidth = pNodeWidth;
			this.nodeHeight = pNodeHeight;
			this.rowSpacing = pRowSpacing;
		}

		/** A card of {@code pBodyLines} body lines: {@link #NODE_HEIGHT} for one line, {@link #BODY_LINE_HEIGHT} more per extra line; the row pitch keeps the pre-lot 50px gap under the card. */
		static Geometry forBodyLines(final int pBodyLines)
		{
			final int height = MatchPlanTreeLayout.NODE_HEIGHT + Math.max(0, pBodyLines - 1) * MatchPlanTreeLayout.BODY_LINE_HEIGHT;
			return new Geometry(MatchPlanTreeLayout.WIDE_NODE_WIDTH, height, height + (MatchPlanTreeLayout.ROW_SPACING - MatchPlanTreeLayout.NODE_HEIGHT));
		}
	}

	/** tree-plain-words lot (2026-09-23): the node card width the diagram now draws (the pre-lot {@link #NODE_WIDTH} stays the default geometry's). */
	static final int WIDE_NODE_WIDTH = 300;
	/** Vertical pitch of one wrapped body line inside a node card (11.5px text). */
	static final int BODY_LINE_HEIGHT = 15;

	/** Every leaf at {@link #MIN_LEAF_WIDTH} - the exact pre-lot geometry (column pitch included). */
	static Result layout(final ReviewDecisionTree.Plan pPlan)
	{
		return MatchPlanTreeLayout.layout(pPlan, null);
	}

	/** Pre-lot node geometry ({@link Geometry#DEFAULT}) - see the three-argument form. */
	static Result layout(final ReviewDecisionTree.Plan pPlan, final Map<String, Integer> pLeafWidthByKind)
	{
		return MatchPlanTreeLayout.layout(pPlan, pLeafWidthByKind, Geometry.DEFAULT);
	}

	/**
	 * @param pLeafWidthByKind width (px) to use for every leaf occurrence of a given raw kind ({@link
	 * ReviewDecisionTree#LEAF_MATCH}/{@code LEAF_SUSPECTED}/{@code LEAF_NOT_MATCHED}), typically from {@link
	 * #leafWidthFor} over the ALREADY-resolved localized text for that kind ({@link
	 * MatchPlanTreeDiagram#computeLeafWidths}). {@code null}, or a kind missing from the map, falls back to
	 * {@link #MIN_LEAF_WIDTH}.
	 */
	static Result layout(final ReviewDecisionTree.Plan pPlan, final Map<String, Integer> pLeafWidthByKind, final Geometry pGeometry)
	{
		final Map<String, MatchPlanTreeLayout.Box> boxes = new LinkedHashMap<String, MatchPlanTreeLayout.Box>();
		final int[] leafCounter = new int[] { 0 };
		final int[] maxDepth = new int[] { 0 };
		if (pPlan.root != null && pPlan.byId(pPlan.root) != null)
		{
			MatchPlanTreeLayout.build(pPlan.root, null, null, 0, pPlan, boxes, leafCounter, maxDepth, new LinkedHashSet<String>(), pLeafWidthByKind, pGeometry);
		}
		final int leafCount = leafCounter[0];
		final boolean folded = leafCount > MatchPlanTreeLayout.FOLD_LEAF_THRESHOLD;
		if (folded)
		{
			MatchPlanTreeLayout.markFoldRoots(boxes);
			MatchPlanTreeLayout.markHiddenGroups(boxes);
		}
		int widestLeaf = MatchPlanTreeLayout.MIN_LEAF_WIDTH;
		for (final MatchPlanTreeLayout.Box box : boxes.values())
		{
			if (box.leaf)
			{
				widestLeaf = Math.max(widestLeaf, box.width());
			}
		}
		// tree-plain-words lot (2026-09-23): a node spans at least two leaf columns, so a card wider than two
		// pitches would overlap its neighbour's subtree - the pitch grows to half the card width (+10) when needed.
		final int colSpacing = Math.max(Math.max(MatchPlanTreeLayout.MIN_COLUMN_SPACING, widestLeaf + 20), pGeometry.nodeWidth / 2 + 10);
		for (final MatchPlanTreeLayout.Box box : boxes.values())
		{
			box.placeX(colSpacing);
		}
		double maxRight = 0;
		double maxBottom = 0;
		for (final MatchPlanTreeLayout.Box box : boxes.values())
		{
			maxRight = Math.max(maxRight, box.right());
			maxBottom = Math.max(maxBottom, box.bottom());
		}
		final Result result = new Result(pPlan.root, leafCount, maxDepth[0], folded, colSpacing, maxRight + colSpacing / 2.0, maxBottom);
		result.boxes.putAll(boxes);
		return result;
	}

	/** {@link #MIN_LEAF_WIDTH} when {@code pLeafWidthByKind} is {@code null} or has no entry for {@code pLeafKind}. */
	private static int leafWidth(final Map<String, Integer> pLeafWidthByKind, final String pLeafKind)
	{
		if (pLeafWidthByKind == null)
		{
			return MatchPlanTreeLayout.MIN_LEAF_WIDTH;
		}
		final Integer width = pLeafWidthByKind.get(pLeafKind);
		return width == null ? MatchPlanTreeLayout.MIN_LEAF_WIDTH : width.intValue();
	}

	/** @return the box just built and registered into {@code pOut} (a leaf occurrence, or a node once both its children are placed). */
	private static Box build(final String pTarget, final String pParentId, final Boolean pParentBranch, final int pDepth, final ReviewDecisionTree.Plan pPlan,
		final Map<String, Box> pOut, final int[] pLeafCounter, final int[] pMaxDepth, final Set<String> pAncestors, final Map<String, Integer> pLeafWidthByKind,
		final Geometry pGeometry)
	{
		pMaxDepth[0] = Math.max(pMaxDepth[0], pDepth);
		if (ReviewDecisionTree.isLeaf(pTarget) || pAncestors.contains(pTarget) || pPlan.byId(pTarget) == null)
		{
			// a real leaf, OR a defensive fallback for a cycle/unknown target this package never validates
			// (validation is master.DecisionTreePlan's job, already run before any plan is ever stored) - rendered
			// as a NOT_MATCHED-looking box rather than throwing or looping forever.
			final String leafKind = ReviewDecisionTree.isLeaf(pTarget) ? pTarget : ReviewDecisionTree.LEAF_NOT_MATCHED;
			final int column = pLeafCounter[0]++;
			final String id = MatchPlanTreeLayout.leafId(pParentId, pParentBranch);
			final int width = MatchPlanTreeLayout.leafWidth(pLeafWidthByKind, leafKind);
			final Box box = new Box(id, true, leafKind, pParentId, pParentBranch, pDepth, column, column, width, MatchPlanTreeLayout.LEAF_HEIGHT, pGeometry.rowSpacing);
			pOut.put(id, box);
			return box;
		}
		final ReviewDecisionTree.Node node = pPlan.byId(pTarget);
		pAncestors.add(pTarget);
		final Box trueChild = MatchPlanTreeLayout.build(node.onTrue, node.id, Boolean.TRUE, pDepth + 1, pPlan, pOut, pLeafCounter, pMaxDepth, pAncestors,
			pLeafWidthByKind, pGeometry);
		final Box falseChild = MatchPlanTreeLayout.build(node.onFalse, node.id, Boolean.FALSE, pDepth + 1, pPlan, pOut, pLeafCounter, pMaxDepth, pAncestors,
			pLeafWidthByKind, pGeometry);
		pAncestors.remove(pTarget);
		final int minCol = Math.min(trueChild.minLeafColumn, falseChild.minLeafColumn);
		final int maxCol = Math.max(trueChild.maxLeafColumn, falseChild.maxLeafColumn);
		final Box box = new Box(node.id, false, null, pParentId, pParentBranch, pDepth, minCol, maxCol, pGeometry.nodeWidth, pGeometry.nodeHeight, pGeometry.rowSpacing);
		pOut.put(node.id, box);
		return box;
	}

	/** {@code "<parentId>:T"}/{@code ":F"}, or {@code "root:LEAF"} for the degenerate one-leaf-alone plan (root itself names a leaf - never produced by {@code master.DecisionTreePlan}, kept only so this method never needs a null id). */
	private static String leafId(final String pParentId, final Boolean pParentBranch)
	{
		if (pParentId == null)
		{
			return "root:LEAF";
		}
		return pParentId + (Boolean.TRUE.equals(pParentBranch) ? ":T" : ":F");
	}

	private static void markFoldRoots(final Map<String, Box> pBoxes)
	{
		for (final Box box : pBoxes.values())
		{
			if (!box.leaf && box.depth == MatchPlanTreeLayout.FOLD_DEPTH)
			{
				box.foldRoot = true;
			}
		}
	}

	private static void markHiddenGroups(final Map<String, Box> pBoxes)
	{
		for (final Box box : pBoxes.values())
		{
			if (box.foldRoot)
			{
				box.hiddenGroupId = box.id;
				continue;
			}
			String cursor = box.parentId;
			String found = null;
			while (cursor != null)
			{
				final Box parent = pBoxes.get(cursor);
				if (parent == null)
				{
					break;
				}
				if (parent.foldRoot)
				{
					found = parent.id;
					break;
				}
				cursor = parent.parentId;
			}
			box.hiddenGroupId = found;
		}
	}

	/** Every box (fold root included) whose {@link Box#hiddenGroupId} equals {@code pFoldRootId} - the count {@code MatchPlanTreeDiagram} shows on the "+N nodes" placeholder. */
	static int subtreeSize(final Map<String, Box> pBoxes, final String pFoldRootId)
	{
		int count = 0;
		for (final Box box : pBoxes.values())
		{
			if (pFoldRootId.equals(box.hiddenGroupId))
			{
				count++;
			}
		}
		return count;
	}
}
