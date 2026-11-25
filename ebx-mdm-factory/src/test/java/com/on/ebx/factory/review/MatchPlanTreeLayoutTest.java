package com.on.ebx.factory.review;

import java.util.HashMap;
import java.util.Map;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Pins {@link
 * MatchPlanTreeLayout}'s coordinates against the VALIDATED mockup
 * (`docs/mockups/review-match-plan-binary-tree.html`, the product owner: "C'est parfait") node by node - every
 * {@code cx}/{@code y} below is read directly off that file's own SVG (see its `<rect>` elements), so a
 * regression here is a regression against the approved drawing, not merely against this test's own guess.
 * <p>
 * <b>tree-diagram-labels fix lot, 2026-09-22</b> ({@code docs/MATCHING.md} &sect;16.10): {@link
 * #leafWidthFor_matchesTheDocumentedFormula} and {@link #wideLeafWidensColumnSpacing} cover the new
 * text-sized leaf width / widest-leaf column spacing. Every pre-existing test above still calls the
 * no-widths {@link MatchPlanTreeLayout#layout(ReviewDecisionTree.Plan)} overload, which keeps every leaf at
 * {@link MatchPlanTreeLayout#MIN_LEAF_WIDTH} (120) and the column pitch at its old fixed 140 - so none of
 * the mockup-pinned coordinates below needed to change.
 */
public final class MatchPlanTreeLayoutTest
{
	public static void main(final String[] pArgs)
	{
		MatchPlanTreeLayoutTest.sixNodeMockup_matchesTheValidatedDrawingExactly();
		MatchPlanTreeLayoutTest.oneNodeCascade_rendersLikeASingleTier();
		MatchPlanTreeLayoutTest.twelveLeaves_doesNotFold();
		MatchPlanTreeLayoutTest.thirteenLeaves_foldsEveryDepthTwoInternalNode();
		MatchPlanTreeLayoutTest.emptyPlan_producesNoBoxes();
		MatchPlanTreeLayoutTest.leafWidthFor_matchesTheDocumentedFormula();
		MatchPlanTreeLayoutTest.wideLeafWidensColumnSpacing();
		System.out.println("MatchPlanTreeLayoutTest OK");
	}

	// -------------------------------------------------------------------------------------- the mockup's own tree

	/** N1..N6 exactly as {@code docs/mockups/review-match-plan-binary-tree.html} draws them - see class Javadoc. */
	private static ReviewDecisionTree.Plan mockupPlan()
	{
		final ReviewDecisionTree.Plan plan = new ReviewDecisionTree.Plan();
		plan.root = "N1";
		plan.nodes.add(MatchPlanTreeLayoutTest.node("N1", ReviewDecisionTree.FUNCTION_ALL_FIELDS, "N2", "N4"));
		plan.nodes.add(MatchPlanTreeLayoutTest.node("N2", ReviewDecisionTree.FUNCTION_ALL_FIELDS, "MATCH", "N3"));
		plan.nodes.add(MatchPlanTreeLayoutTest.node("N3", ReviewDecisionTree.FUNCTION_ALL_FIELDS, "SUSPECTED", "NOT_MATCHED"));
		plan.nodes.add(MatchPlanTreeLayoutTest.node("N4", ReviewDecisionTree.FUNCTION_AVERAGE, "N5", "NOT_MATCHED"));
		plan.nodes.add(MatchPlanTreeLayoutTest.node("N5", ReviewDecisionTree.FUNCTION_ALL_FIELDS, "SUSPECTED", "N6"));
		plan.nodes.add(MatchPlanTreeLayoutTest.node("N6", ReviewDecisionTree.FUNCTION_AT_LEAST_N, "SUSPECTED", "NOT_MATCHED"));
		return plan;
	}

	private static void sixNodeMockup_matchesTheValidatedDrawingExactly()
	{
		final MatchPlanTreeLayout.Result result = MatchPlanTreeLayout.layout(MatchPlanTreeLayoutTest.mockupPlan());
		MatchPlanTreeLayoutTest.check("6 nodes + 7 leaves = 13 boxes", result.boxes.size() == 13);
		MatchPlanTreeLayoutTest.check("7 leaves", result.leafCount == 7);
		MatchPlanTreeLayoutTest.check("max depth 4", result.maxDepth == 4);
		MatchPlanTreeLayoutTest.check("not folded (7 <= 12)", !result.folded);

		// node boxes - cx read off the mockup's own <rect> centres (x + width/2), y relative to this class' own
		// row-0-at-depth-0 convention (the mockup's row 0 sits at y=68 because of the clustering band above it -
		// MatchPlanTreeDiagram adds that offset when it renders, this layout class never does).
		MatchPlanTreeLayoutTest.checkNode(result, "N1", 490, 0);
		MatchPlanTreeLayoutTest.checkNode(result, "N2", 210, 110);
		MatchPlanTreeLayoutTest.checkNode(result, "N4", 700, 110);
		MatchPlanTreeLayoutTest.checkNode(result, "N3", 280, 220);
		MatchPlanTreeLayoutTest.checkNode(result, "N5", 630, 220);
		MatchPlanTreeLayoutTest.checkNode(result, "N6", 700, 330);

		// leaf occurrences - keyed by their owning node + branch (see MatchPlanTreeLayout.Box#id)
		MatchPlanTreeLayoutTest.checkLeaf(result, "N2:T", "MATCH", 70, 220);
		MatchPlanTreeLayoutTest.checkLeaf(result, "N3:T", "SUSPECTED", 210, 330);
		MatchPlanTreeLayoutTest.checkLeaf(result, "N3:F", "NOT_MATCHED", 350, 330);
		MatchPlanTreeLayoutTest.checkLeaf(result, "N5:T", "SUSPECTED", 490, 330);
		MatchPlanTreeLayoutTest.checkLeaf(result, "N6:T", "SUSPECTED", 630, 440);
		MatchPlanTreeLayoutTest.checkLeaf(result, "N6:F", "NOT_MATCHED", 770, 440);
		MatchPlanTreeLayoutTest.checkLeaf(result, "N4:F", "NOT_MATCHED", 910, 220);

		// "reached by" data - the immediate parent edge every node/leaf carries
		final MatchPlanTreeLayout.Box n4 = result.boxes.get("N4");
		MatchPlanTreeLayoutTest.check("N4 reached by N1.onFalse", "N1".equals(n4.parentId) && Boolean.FALSE.equals(n4.parentBranch));
		final MatchPlanTreeLayout.Box n1 = result.boxes.get("N1");
		MatchPlanTreeLayoutTest.check("N1 (root) has no parent", n1.parentId == null && n1.parentBranch == null);
	}

	private static void checkNode(final MatchPlanTreeLayout.Result pResult, final String pId, final double pCx, final int pY)
	{
		final MatchPlanTreeLayout.Box box = pResult.boxes.get(pId);
		MatchPlanTreeLayoutTest.check(pId + " exists", box != null);
		MatchPlanTreeLayoutTest.check(pId + " is a node, not a leaf", !box.leaf);
		MatchPlanTreeLayoutTest.check(pId + " cx=" + box.cx + " expected " + pCx, Math.abs(box.cx - pCx) < 0.001);
		MatchPlanTreeLayoutTest.check(pId + " y=" + box.y + " expected " + pY, box.y == pY);
	}

	private static void checkLeaf(final MatchPlanTreeLayout.Result pResult, final String pId, final String pKind, final double pCx, final int pY)
	{
		final MatchPlanTreeLayout.Box box = pResult.boxes.get(pId);
		MatchPlanTreeLayoutTest.check(pId + " exists", box != null);
		MatchPlanTreeLayoutTest.check(pId + " is a leaf", box.leaf);
		MatchPlanTreeLayoutTest.check(pId + " kind=" + box.leafKind + " expected " + pKind, pKind.equals(box.leafKind));
		MatchPlanTreeLayoutTest.check(pId + " cx=" + box.cx + " expected " + pCx, Math.abs(box.cx - pCx) < 0.001);
		MatchPlanTreeLayoutTest.check(pId + " y=" + box.y + " expected " + pY, box.y == pY);
	}

	// ------------------------------------------------------------------------------------------- one-node cascade

	/** A single ALL_FIELDS node, both branches leaves - "renders like today's" single-tier cascade: one node box, two leaves either side of it, both at depth 1. */
	private static void oneNodeCascade_rendersLikeASingleTier()
	{
		final ReviewDecisionTree.Plan plan = new ReviewDecisionTree.Plan();
		plan.root = "T1";
		plan.nodes.add(MatchPlanTreeLayoutTest.node("T1", ReviewDecisionTree.FUNCTION_ALL_FIELDS, "MATCH", "NOT_MATCHED"));
		final MatchPlanTreeLayout.Result result = MatchPlanTreeLayoutTest.layout(plan);
		MatchPlanTreeLayoutTest.check("1 node + 2 leaves", result.boxes.size() == 3);
		MatchPlanTreeLayoutTest.check("2 leaves", result.leafCount == 2);
		MatchPlanTreeLayoutTest.check("depth 1", result.maxDepth == 1);
		final MatchPlanTreeLayout.Box root = result.boxes.get("T1");
		MatchPlanTreeLayoutTest.check("root at depth 0, y=0", root.y == 0);
		final MatchPlanTreeLayout.Box trueLeaf = result.boxes.get("T1:T");
		final MatchPlanTreeLayout.Box falseLeaf = result.boxes.get("T1:F");
		MatchPlanTreeLayoutTest.check("true leaf is MATCH, left of false leaf", "MATCH".equals(trueLeaf.leafKind) && trueLeaf.cx < falseLeaf.cx);
		MatchPlanTreeLayoutTest.check("false leaf is NOT_MATCHED", "NOT_MATCHED".equals(falseLeaf.leafKind));
		MatchPlanTreeLayoutTest.check("both leaves one row below the root", trueLeaf.y == MatchPlanTreeLayout.ROW_SPACING && falseLeaf.y == MatchPlanTreeLayout.ROW_SPACING);
		// root sits over the midpoint of its two leaf columns (0 and 1) -> column 0.5
		MatchPlanTreeLayoutTest
			.check("root centred over its two leaves", Math.abs(root.cx - (trueLeaf.cx + falseLeaf.cx) / 2.0) < 0.001);
	}

	// --------------------------------------------------------------------------------------------------- folding

	/** Exactly 12 leaves - at the threshold, never folds ("beyond 12", strictly more). */
	private static void twelveLeaves_doesNotFold()
	{
		final MatchPlanTreeLayout.Result result = MatchPlanTreeLayoutTest.layout(MatchPlanTreeLayoutTest.wideCascade(11));
		MatchPlanTreeLayoutTest.check("11 EXACT nodes chained -> 12 leaves", result.leafCount == 12);
		MatchPlanTreeLayoutTest.check("exactly at the threshold: not folded", !result.folded);
		for (final MatchPlanTreeLayout.Box box : result.boxes.values())
		{
			MatchPlanTreeLayoutTest.check(box.id + " not folded", !box.foldRoot && box.hiddenGroupId == null);
		}
	}

	/** One more leaf (13) crosses the threshold - every internal node at depth exactly 2 becomes a fold root, hiding everything beneath it. */
	private static void thirteenLeaves_foldsEveryDepthTwoInternalNode()
	{
		final ReviewDecisionTree.Plan plan = MatchPlanTreeLayoutTest.wideCascade(12);
		final MatchPlanTreeLayout.Result result = MatchPlanTreeLayout.layout(plan);
		MatchPlanTreeLayoutTest.check("12 EXACT nodes chained -> 13 leaves", result.leafCount == 13);
		MatchPlanTreeLayoutTest.check("beyond the threshold: folded", result.folded);
		// this cascade's own node T3 sits at depth 2 (T1 depth0, T2 depth1, T3 depth2) - it must be the fold root
		final MatchPlanTreeLayout.Box t3 = result.boxes.get("T3");
		MatchPlanTreeLayoutTest.check("T3 (depth 2) is a fold root", t3.foldRoot && "T3".equals(t3.hiddenGroupId));
		final MatchPlanTreeLayout.Box t1 = result.boxes.get("T1");
		final MatchPlanTreeLayout.Box t2 = result.boxes.get("T2");
		MatchPlanTreeLayoutTest.check("T1 (depth 0) stays visible", !t1.foldRoot && t1.hiddenGroupId == null);
		MatchPlanTreeLayoutTest.check("T2 (depth 1) stays visible", !t2.foldRoot && t2.hiddenGroupId == null);
		final MatchPlanTreeLayout.Box t4 = result.boxes.get("T4");
		MatchPlanTreeLayoutTest.check("T4 (depth 3, under T3) is hidden under T3's fold, not itself a fold root",
			!t4.foldRoot && "T3".equals(t4.hiddenGroupId));
		// every leaf reached only through T3 or deeper is hidden under T3 too
		final MatchPlanTreeLayout.Box lastLeaf = result.boxes.get("T12:F");
		MatchPlanTreeLayoutTest.check("the deepest leaf is hidden under T3's fold", "T3".equals(lastLeaf.hiddenGroupId));
		// only T1, T2, and their own true-leaves (T1:T, T2:T) stay visible - everything else (T3 itself and
		// everything under it) is hidden behind the fold placeholder.
		MatchPlanTreeLayoutTest.check("subtree size under T3 counts every hidden box: " + MatchPlanTreeLayout.subtreeSize(result.boxes, "T3"),
			MatchPlanTreeLayout.subtreeSize(result.boxes, "T3") == result.boxes.size() - 4);
	}

	/** A chain of {@code pCount} EXACT nodes, {@code T1..T(pCount)}, each true branch a distinct MATCH leaf, the last false branch NOT_MATCHED - {@code pCount + 1} leaf occurrences. */
	private static ReviewDecisionTree.Plan wideCascade(final int pCount)
	{
		final ReviewDecisionTree.Plan plan = new ReviewDecisionTree.Plan();
		plan.root = "T1";
		for (int i = 1; i <= pCount; i++)
		{
			final String onFalse = i < pCount ? "T" + (i + 1) : ReviewDecisionTree.LEAF_NOT_MATCHED;
			plan.nodes.add(MatchPlanTreeLayoutTest.node("T" + i, ReviewDecisionTree.FUNCTION_ALL_FIELDS, ReviewDecisionTree.LEAF_MATCH, onFalse));
		}
		return plan;
	}

	// ------------------------------------------------------------------------------------- leaf width / column spacing

	/**
	 * {@link MatchPlanTreeLayout#leafWidthFor} against the ACTUAL shortened English leaf wording
	 * ({@code Messages_en.properties}, tree-diagram-labels fix lot) - ties the formula to real content rather
	 * than arbitrary strings. Longest-line char counts: "SUSPECT"/7 vs "To a data steward"/17 -> 130; "MATCH"/5
	 * vs "Automatic merge"/15 -> 117, clamped up to the 120 floor; "NOT A DUPLICATE"/15 vs "Both stay
	 * distinct"/18 -> 136. A one-character pair clamps to the 120 floor from below; a deliberately long line
	 * clamps to the 200 ceiling.
	 */
	private static void leafWidthFor_matchesTheDocumentedFormula()
	{
		MatchPlanTreeLayoutTest.check("SUSPECT / To a data steward -> 130", MatchPlanTreeLayout.leafWidthFor("SUSPECT", "To a data steward") == 130);
		MatchPlanTreeLayoutTest.check("MATCH / Automatic merge -> 117, clamped to the 120 floor",
			MatchPlanTreeLayout.leafWidthFor("MATCH", "Automatic merge") == 120);
		MatchPlanTreeLayoutTest.check("NOT A DUPLICATE / Both stay distinct -> 136",
			MatchPlanTreeLayout.leafWidthFor("NOT A DUPLICATE", "Both stay distinct") == 136);
		MatchPlanTreeLayoutTest.check("a one-character pair clamps to the 120 floor", MatchPlanTreeLayout.leafWidthFor("A", "B") == 120);
		MatchPlanTreeLayoutTest.check("a deliberately long line clamps to the 200 ceiling",
			MatchPlanTreeLayout.leafWidthFor("X", "This line is deliberately far longer than any leaf label should ever realistically need to be") == 200);
	}

	/** A leaf explicitly widened past the default 140 column pitch (120 leaf + 20) widens the WHOLE tree's column spacing, not just that one leaf's own box. */
	private static void wideLeafWidensColumnSpacing()
	{
		final ReviewDecisionTree.Plan plan = new ReviewDecisionTree.Plan();
		plan.root = "T1";
		plan.nodes.add(MatchPlanTreeLayoutTest.node("T1", ReviewDecisionTree.FUNCTION_ALL_FIELDS, "MATCH", "NOT_MATCHED"));
		final Map<String, Integer> widths = new HashMap<String, Integer>();
		widths.put(ReviewDecisionTree.LEAF_MATCH, Integer.valueOf(180));
		final MatchPlanTreeLayout.Result result = MatchPlanTreeLayout.layout(plan, widths);
		MatchPlanTreeLayoutTest.check("column spacing widened to fit the 180px leaf (180 + 20): " + result.colSpacing, result.colSpacing == 200.0);
		final MatchPlanTreeLayout.Box matchLeaf = result.boxes.get("T1:T");
		MatchPlanTreeLayoutTest.check("the widened leaf itself is 180px wide", matchLeaf.width() == 180);
		final MatchPlanTreeLayout.Box notMatchLeaf = result.boxes.get("T1:F");
		MatchPlanTreeLayoutTest.check("the other leaf stays at the default 120px width (no entry in the map)", notMatchLeaf.width() == 120);
		MatchPlanTreeLayoutTest.check("leaf column pitch is 200 (not the old fixed 140) - visible in the gap between the two leaves' cx",
			Math.abs(notMatchLeaf.cx - matchLeaf.cx - 200) < 0.001);
	}

	// --------------------------------------------------------------------------------------------------- helpers

	private static void emptyPlan_producesNoBoxes()
	{
		final MatchPlanTreeLayout.Result result = MatchPlanTreeLayoutTest.layout(new ReviewDecisionTree.Plan());
		MatchPlanTreeLayoutTest.check("no boxes at all", result.boxes.isEmpty());
		MatchPlanTreeLayoutTest.check("no leaves", result.leafCount == 0);
		MatchPlanTreeLayoutTest.check("not folded", !result.folded);
	}

	private static MatchPlanTreeLayout.Result layout(final ReviewDecisionTree.Plan pPlan)
	{
		return MatchPlanTreeLayout.layout(pPlan);
	}

	private static ReviewDecisionTree.Node node(final String pId, final String pFunction, final String pOnTrue, final String pOnFalse)
	{
		final ReviewDecisionTree.Node node = new ReviewDecisionTree.Node(pId, pFunction, null,
			ReviewDecisionTree.FUNCTION_AVERAGE.equals(pFunction) ? Integer.valueOf(80) : null,
			ReviewDecisionTree.FUNCTION_AT_LEAST_N.equals(pFunction) ? Integer.valueOf(2) : null, pOnTrue, pOnFalse, null);
		node.fields.add(new ReviewDecisionTree.Field("f", "ebx-addon-mame@exact", Integer.valueOf(1), Integer.valueOf(100), null, null, null, null));
		return node;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
