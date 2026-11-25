package com.on.ebx.factory.review;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Covers {@link
 * MatchPlanTreeDiagram}'s general-tree rendering - binary-decision-tree-screen lot, 2026-09-22 - against the
 * validated mockup's own six-node Company tree (`docs/mockups/review-match-plan-binary-tree.html`) and a
 * synthetic wide tree for folding. Geometry itself is {@link MatchPlanTreeLayoutTest}'s job; this file only
 * checks that the SVG/HTML markup a reviewer's browser needs (selection attributes, detail panels, fold
 * placeholders, the autoMerge standing note) is actually present.
 * <p>
 * <b>tree-diagram-labels fix lot, 2026-09-22</b> ({@code docs/MATCHING.md} &sect;16.10): {@link
 * #branchLabels_sitBesideTheirSegmentWithAHalo} and {@link #nodeWithManyFields_bodyTextEllipsizedWithTooltip}
 * cover the two rendering defects the product owner's screenshot showed. Both leaf-wording assertions elsewhere in this
 * file were updated for the shortened {@code Messages_en.properties} text ("Proposed to a data steward" -&gt;
 * "To a data steward", "Merged automatically" -&gt; "Automatic merge") - {@code MatchPlanViewTest} has the
 * same updates.
 */
public final class MatchPlanTreeDiagramTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanTreeDiagramTest.sixNodeMockup_rendersEveryNodeAndLeaf();
		MatchPlanTreeDiagramTest.everyNodeIsSelectableAndHasAPreRenderedDetailPanel();
		MatchPlanTreeDiagramTest.rootIsSelectedByDefault();
		MatchPlanTreeDiagramTest.autoMergeFalse_standingNoteShownAndNoRealMatchLeaf();
		MatchPlanTreeDiagramTest.autoMergeTrue_realMatchLeafNoStandingNote();
		MatchPlanTreeDiagramTest.wideTree_foldsBeyondTwelveLeaves();
		MatchPlanTreeDiagramTest.emptyPlan_saysNoTierConfigured();
		MatchPlanTreeDiagramTest.fieldNameIsEscaped();
		MatchPlanTreeDiagramTest.branchLabels_sitBesideTheirSegmentWithAHalo();
		MatchPlanTreeDiagramTest.nodeWithManyFields_bodyTextEllipsizedWithTooltip();
		MatchPlanTreeDiagramTest.detailTable_hasFixedLayoutAndShortHeaders();
		MatchPlanTreeDiagramTest.detailTable_nullCellShowsShortWordWithFullTooltip();
		MatchPlanTreeDiagramTest.relationFields_notSearchedByDefault_listedOnlyInTheNote();
		MatchPlanTreeDiagramTest.relationField_withPositiveWeight_listedAmongTheSearchFields();
		MatchPlanTreeDiagramTest.noRelationField_noRelationNote();
		MatchPlanTreeDiagramTest.relationNote_existsInFrench();
		System.out.println("MatchPlanTreeDiagramTest OK");
	}

	// ------------------------------------------------------------------------------------------------- fixtures

	/** The mockup's own six-node Company tree, same shape as {@code master.DecisionTreePlanTest#mockupSixNodeTree}. */
	private static String sixNodeMockupRequirements()
	{
		return "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"N2\",\"onFalse\":\"N4\",\"rationale\":\"empty on either side never passes\"},"
			+ "{\"id\":\"N2\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"country\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"MATCH\",\"onFalse\":\"N3\"},"
			+ "{\"id\":\"N3\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"minimumScore\":85}],"
			+ "\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"N4\",\"function\":\"AVERAGE\",\"minimumScore\":80,\"fields\":["
			+ "{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"weight\":10},"
			+ "{\"field\":\"country\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":5},"
			+ "{\"field\":\"cityKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":5}],\"onTrue\":\"N5\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"N5\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"legalForm\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"SUSPECTED\",\"onFalse\":\"N6\"},"
			+ "{\"id\":\"N6\",\"function\":\"AT_LEAST_N\",\"minimumMatches\":2,\"fields\":["
			+ "{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100},"
			+ "{\"field\":\"cityKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100},"
			+ "{\"field\":\"legalForm\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}"
			+ "]},\"clusteringFields\":[\"country\"]}";
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return MatchPlanTreeDiagramTest.MAPPER.readTree(pJson);
	}

	private static String buildSixNode(final boolean pAutoMerge) throws Exception
	{
		return MatchPlanTreeDiagram.build(MatchPlanTreeDiagramTest.parse(MatchPlanTreeDiagramTest.sixNodeMockupRequirements()),
			new LinkedHashMap<String, MatchPlanFieldEvidence>(), pAutoMerge, "Company", Locale.ENGLISH);
	}

	// ---------------------------------------------------------------------------------------------------- tests

	private static void sixNodeMockup_rendersEveryNodeAndLeaf() throws Exception
	{
		final String html = MatchPlanTreeDiagramTest.buildSixNode(false);
		for (final String id : new String[] { "N1", "N2", "N3", "N4", "N5", "N6" })
		{
			MatchPlanTreeDiagramTest.check(id + " node card rendered", html.contains("data-tree-node=\"" + id + "\""));
		}
		MatchPlanTreeDiagramTest.check("clustering field named", html.contains("country"));
		MatchPlanTreeDiagramTest.check("N1's own rationale reaches the card", html.contains("empty on either side never passes"));
		MatchPlanTreeDiagramTest.check("horizontal scroll container present", html.contains("mdmf-tree-scroll"));
		// tree-plain-words lot (2026-09-23): the threshold now sits in the card header, the branch words are plain
		MatchPlanTreeDiagramTest.check("N4's header carries the threshold", html.contains("AT LEAST 80 %"));
		MatchPlanTreeDiagramTest.check("N4's average branch labels are plain words", html.contains("similar enough") && html.contains("too different"));
	}

	private static void everyNodeIsSelectableAndHasAPreRenderedDetailPanel() throws Exception
	{
		final String html = MatchPlanTreeDiagramTest.buildSixNode(false);
		for (final String id : new String[] { "N1", "N2", "N3", "N4", "N5", "N6" })
		{
			MatchPlanTreeDiagramTest.check(id + " has a pre-rendered detail panel", html.contains("data-tree-detail=\"" + id + "\""));
		}
		// N4's own detail panel names its algorithm/weight columns and the reached-by edge (N1's false branch)
		final int n4Detail = html.indexOf("data-tree-detail=\"N4\"");
		MatchPlanTreeDiagramTest.check("N4 detail panel found", n4Detail >= 0);
		MatchPlanTreeDiagramTest.check("N4 reached-by names N1", html.substring(n4Detail, Math.min(html.length(), n4Detail + 1500)).contains("N1"));
	}

	private static void rootIsSelectedByDefault() throws Exception
	{
		final String html = MatchPlanTreeDiagramTest.buildSixNode(false);
		final int rootDetail = html.indexOf("data-tree-detail=\"N1\"");
		MatchPlanTreeDiagramTest.check("root detail panel is visible by default",
			html.substring(rootDetail, Math.min(html.length(), rootDetail + 60)).contains("display:block"));
		final int n2Detail = html.indexOf("data-tree-detail=\"N2\"");
		MatchPlanTreeDiagramTest.check("a non-root detail panel starts hidden",
			html.substring(n2Detail, Math.min(html.length(), n2Detail + 60)).contains("display:none"));
	}

	private static void autoMergeFalse_standingNoteShownAndNoRealMatchLeaf() throws Exception
	{
		final String html = MatchPlanTreeDiagramTest.buildSixNode(false);
		MatchPlanTreeDiagramTest.check("standing note shown", html.contains("AUTOMATIC MERGE IS OFF") && html.contains("still goes to a data steward"));
		MatchPlanTreeDiagramTest.check("no real merged-automatically leaf", !html.contains("Merged automatically"));
		MatchPlanTreeDiagramTest.check("the N2-true leaf renders as Suspect instead", html.contains("A data steward decides"));
	}

	private static void autoMergeTrue_realMatchLeafNoStandingNote() throws Exception
	{
		final String html = MatchPlanTreeDiagramTest.buildSixNode(true);
		MatchPlanTreeDiagramTest.check("no standing note when autoMerge is true", !html.contains("still goes to a data steward"));
		MatchPlanTreeDiagramTest.check("the real merged-automatically leaf is shown", html.contains("Merged automatically"));
	}

	/** Beyond {@link MatchPlanTreeLayout#FOLD_LEAF_THRESHOLD} leaves, the depth-2 internal node folds into a placeholder, its real content pre-rendered hidden. */
	private static void wideTree_foldsBeyondTwelveLeaves() throws Exception
	{
		final StringBuilder nodes = new StringBuilder();
		nodes.append("{\"id\":\"T1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"f\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"MATCH\",\"onFalse\":\"T2\"},");
		nodes.append("{\"id\":\"T2\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"f\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"MATCH\",\"onFalse\":\"T3\"},");
		for (int i = 3; i <= 12; i++)
		{
			final String onFalse = i < 12 ? "T" + (i + 1) : "NOT_MATCHED";
			nodes.append("{\"id\":\"T").append(i).append("\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"f\",\"algorithm\":\"ebx-addon-mame@exact\","
				+ "\"minimumScore\":100}],\"onTrue\":\"MATCH\",\"onFalse\":\"").append(onFalse).append("\"}");
			if (i < 12)
			{
				nodes.append(',');
			}
		}
		final String requirements = "{\"decisionTree\":{\"root\":\"T1\",\"nodes\":[" + nodes + "]}}";
		final String html = MatchPlanTreeDiagram.build(MatchPlanTreeDiagramTest.parse(requirements), new LinkedHashMap<String, MatchPlanFieldEvidence>(), true, "Wide",
			Locale.ENGLISH);
		MatchPlanTreeDiagramTest.check("T3 (depth 2) folds into a placeholder", html.contains("data-tree-fold=\"T3\""));
		MatchPlanTreeDiagramTest.check("T3's real content is pre-rendered, hidden", html.contains("data-tree-real=\"T3\"") && html.contains("style=\"display:none\""));
		MatchPlanTreeDiagramTest.check("the placeholder names how many nodes it hides", html.contains("+ ") && html.contains("more steps"));
		MatchPlanTreeDiagramTest.check("T1/T2 (depth 0/1) never fold", !html.contains("data-tree-fold=\"T1\"") && !html.contains("data-tree-fold=\"T2\""));
	}

	private static void emptyPlan_saysNoTierConfigured() throws Exception
	{
		final String html = MatchPlanTreeDiagram.build(MatchPlanTreeDiagramTest.parse("{}"), new LinkedHashMap<String, MatchPlanFieldEvidence>(), true, "Company",
			Locale.ENGLISH);
		MatchPlanTreeDiagramTest.check("says no tier configured", html.contains("No comparison rule yet"));
		MatchPlanTreeDiagramTest.check("no standing note for an empty plan", !html.contains("disabled for this plan"));
	}

	private static void fieldNameIsEscaped() throws Exception
	{
		final String requirements = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\","
			+ "\"fields\":[{\"field\":\"\\\"><script>alert(1)</script>\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}]}}";
		final String html = MatchPlanTreeDiagram.build(MatchPlanTreeDiagramTest.parse(requirements), new LinkedHashMap<String, MatchPlanFieldEvidence>(), true, "Company",
			Locale.ENGLISH);
		MatchPlanTreeDiagramTest.check("no raw <script> tag reaches the output", !html.contains("<script>"));
		MatchPlanTreeDiagramTest.check("the field name text is still present, escaped", html.contains("&lt;script&gt;"));
	}

	/**
	 * Every branch label ({@code passes}/{@code does not pass}/{@code &gt;= 80}/{@code &lt; 80}) sits beside its
	 * connector's own FIRST vertical segment - {@code labelX == anchorX + 8} - carrying the white-halo style,
	 * never centred on the horizontal run (the pre-lot placement, which collided with a nearby vertical segment
	 * whenever the run was short - the product owner's screenshot of the AVERAGE node's {@code >= 80}/{@code < 80}
	 * labels). Deliberately asserts the RELATIONSHIP between the path's own {@code M<anchorX>,<fromY>} start and
	 * the following label's {@code x}, not a hardcoded pixel value - leaf width (hence column spacing, hence
	 * every node's own {@code cx}) is now derived from the localized leaf wording (see {@link
	 * MatchPlanTreeLayout}'s own Javadoc), so a literal coordinate here would be fragile against a future
	 * wording change; the "beside the segment" property is not.
	 */
	private static void branchLabels_sitBesideTheirSegmentWithAHalo() throws Exception
	{
		final String html = MatchPlanTreeDiagramTest.buildSixNode(false);
		final Pattern connectorThenLabel = Pattern.compile(
			"M(\\d+),(\\d+) L\\1,(\\d+) L\\d+,\\3 L\\d+,\\d+\" stroke=\"[^\"]+\" stroke-width=\"[^\"]+\" fill=\"none\" marker-end=\"url\\(#mdmfArrow\\)\"/>"
				+ "<text x=\"(\\d+)\" y=\"(\\d+)\" font-size=\"10\" font-weight=\"700\" fill=\"[^\"]+\" style=\"([^\"]+)\">");
		final Matcher matcher = connectorThenLabel.matcher(html);
		int matches = 0;
		while (matcher.find())
		{
			matches++;
			final int anchorX = Integer.parseInt(matcher.group(1));
			final int fromY = Integer.parseInt(matcher.group(2));
			final int midY = Integer.parseInt(matcher.group(3));
			final int labelX = Integer.parseInt(matcher.group(4));
			final int labelY = Integer.parseInt(matcher.group(5));
			final String style = matcher.group(6);
			MatchPlanTreeDiagramTest.check("label x is the segment's own anchorX + 8 (anchor=" + anchorX + ", label=" + labelX + ")", labelX == anchorX + 8);
			MatchPlanTreeDiagramTest.check("label y sits within the first vertical segment's own span [" + fromY + "," + midY + "], got " + labelY,
				labelY >= Math.min(fromY, midY) && labelY <= Math.max(fromY, midY) + 5);
			MatchPlanTreeDiagramTest.check("label carries the white halo (paint-order:stroke)", style.contains("paint-order:stroke") && style.contains("stroke:#fff"));
		}
		// 6 nodes x 2 branches = 12 labels in the mockup's own tree - every one of them checked above.
		MatchPlanTreeDiagramTest.check("all 12 branch labels of the six-node tree were matched and checked, found " + matches, matches == 12);
	}

	/**
	 * A node comparing many fields (here an {@code AT_LEAST_N} over 6) overflows its 220px card at the
	 * {@link MatchPlanTreeDiagram} field-summary line - the visible text is now truncated with an ellipsis, the
	 * full list preserved in a {@code <title>} tooltip (same idea as the pre-existing rationale-line truncation
	 * and the suspect leaf's own tooltip).
	 */
	private static void nodeWithManyFields_bodyTextEllipsizedWithTooltip() throws Exception
	{
		// tree-plain-words lot (2026-09-23): six short names now WRAP inside the wider card (no cut, no tooltip);
		// only a list longer than three full lines is still ellipsized, its full text in the tooltip.
		final StringBuilder fields = new StringBuilder();
		final String[] names = { "alphaIdentifier", "bravoIdentifier", "charlieIdentifier", "deltaIdentifier", "echoIdentifier", "foxtrotIdentifier",
			"golfIdentifier", "hotelIdentifier", "indiaIdentifier", "julietIdentifier", "kiloIdentifier", "limaIdentifier", "mikeIdentifier", "zuluIdentifier" };
		for (final String name : names)
		{
			if (fields.length() > 0)
			{
				fields.append(',');
			}
			fields.append("{\"field\":\"").append(name).append("\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}");
		}
		final String requirements = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"AT_LEAST_N\",\"minimumMatches\":2,\"fields\":[" + fields
			+ "],\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}]}}";
		final String html = MatchPlanTreeDiagram.build(MatchPlanTreeDiagramTest.parse(requirements), new LinkedHashMap<String, MatchPlanFieldEvidence>(), true, "Company",
			Locale.ENGLISH);
		final int nodeStart = html.indexOf("data-tree-node=\"N1\"");
		MatchPlanTreeDiagramTest.check("N1 node card found", nodeStart >= 0);
		final String nodeBlock = html.substring(nodeStart, Math.min(html.length(), nodeStart + 2000));
		final int titleStart = nodeBlock.indexOf("<title>");
		MatchPlanTreeDiagramTest.check("the untruncated 14-field list reaches a tooltip, as words", titleStart >= 0 && nodeBlock.contains("Zulu identifier"));
		MatchPlanTreeDiagramTest.check("the visible field list wraps over three tspans", nodeBlock.substring(0, titleStart).split("<tspan").length - 1 == 3);
		MatchPlanTreeDiagramTest.check("the visible field list is ellipsized", nodeBlock.substring(0, titleStart).contains("…"));
		MatchPlanTreeDiagramTest.check("the visible (truncated) text does not itself carry the last field name",
			!nodeBlock.substring(0, titleStart).contains("Zulu identifier"));
		// six short fields: wrapped, complete, no tooltip
		final String six = MatchPlanTreeDiagramTest.buildSixNode(false);
		final int n2 = six.indexOf("data-tree-node=\"N2\"");
		final String n2Block = six.substring(n2, six.indexOf("</g>", n2));
		MatchPlanTreeDiagramTest.check("a short field list is never cut", !n2Block.contains("<title>") && !n2Block.contains("…"));
		MatchPlanTreeDiagramTest.check("wrap: one short line stays one line", MatchPlanTreeDiagram.wrap("Same VAT number", 44, 3).size() == 1);
		MatchPlanTreeDiagramTest.check("wrap: a 60-char list splits at a space into two lines",
			MatchPlanTreeDiagram.wrap("First name (weight 2), Last name (weight 3), Email, Account id", 44, 3).size() == 2);
	}

	/**
	 * Item A (coordinator, the product owner live at 19:40, {@code docs/MATCHING.md} &sect;16.10): the detail table used
	 * to spill past its 300px-wide panel (auto column widths, "Null handling" cut off, a long algorithm label
	 * wrapping four lines) - now carries {@code mdmf-tree-detail-table} (the fixed-layout class review.css
	 * targets) and the shortened headers, and never the old long ones. The six-node mockup exercises both
	 * value-column headers: N4 is AVERAGE ("Weight"), every other node is not ("Min").
	 */
	private static void detailTable_hasFixedLayoutAndShortHeaders() throws Exception
	{
		final String html = MatchPlanTreeDiagramTest.buildSixNode(false);
		MatchPlanTreeDiagramTest.check("fixed-layout detail table class present", html.contains("mdmf-tree-detail-table"));
		MatchPlanTreeDiagramTest.check("short headers present: Field/Algorithm/Weight/Min/Null",
			html.contains(">Field<") && html.contains(">Compared how<") && html.contains(">Weight<") && html.contains(">Min. score<") && html.contains(">If empty<"));
		MatchPlanTreeDiagramTest.check("the old long headers never appear", !html.contains("Weight/Min") && !html.contains("Null handling"));
		// tree-plain-words lot (2026-09-23): the algorithm cell shows the short words, the precise ones as a tooltip
		MatchPlanTreeDiagramTest.check("short algorithm words in the cell, full words in the tooltip",
			html.contains("title=\"resembles (fuzzy, full text)\">resembles<") && !html.contains(">resembles (fuzzy, full text)<"));
		MatchPlanTreeDiagramTest.check("short words helper", "close spelling".equals(ProposalWording.matchAlgorithmShortWords("ebx-addon-mame@jarowinkler"))
			&& "identical".equals(ProposalWording.matchAlgorithmShortWords("ebx-addon-mame@exact")));
	}

	/**
	 * A field's raw add-on null-handling enum ({@code DO_NOTHING}/{@code NOT_MATCH}/{@code MATCH}) always
	 * renders as one of the four fixed-length short words in the cell itself, with the full raw value(s) (both
	 * {@code bothNull} and {@code oneNull}, joined, when they differ) in the cell's own {@code title} tooltip -
	 * item A, {@code docs/MATCHING.md} &sect;16.10.
	 */
	private static void detailTable_nullCellShowsShortWordWithFullTooltip() throws Exception
	{
		// the modern node-based field shape uses the SHORT keys (bothNull/oneNull), not the nested add-on shape's
		// bothNullValueHandling/oneNullValueHandling (master.DecisionTreePlan's own documented distinction).
		final String requirements = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":["
			+ "{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,"
			+ "\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"NOT_MATCH\"}],\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}]}}";
		final String html = MatchPlanTreeDiagram.build(MatchPlanTreeDiagramTest.parse(requirements), new LinkedHashMap<String, MatchPlanFieldEvidence>(), true, "Company",
			Locale.ENGLISH);
		MatchPlanTreeDiagramTest.check("the short word (bothNull wins) is shown, full pair in the tooltip",
			html.contains("title=\"DO_NOTHING / NOT_MATCH\">ignore<"));
	}

	// ------------------------------------------------------------------ relation fields in the search band (lot M2)

	/** Personne: N1 compares lastName (own field) then N2 the child fields Adresse.street / Adresse.city; emailKey is the clustering field. */
	private static String personneRelationRequirements(final String pExtra)
	{
		return "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":90,\"fields\":["
			+ "{\"field\":\"lastName\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"weight\":3}],\"onTrue\":\"N2\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"N2\",\"function\":\"ALL_FIELDS\",\"fields\":["
			+ "{\"field\":\"street\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100},"
			+ "{\"field\":\"city\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]},\"clusteringFields\":[\"emailKey\"]" + pExtra + "}";
	}

	private static String bandOf(final String pRequirements, final Locale pLocale) throws Exception
	{
		final String html = MatchPlanTreeDiagram.build(MatchPlanTreeDiagramTest.parse(pRequirements), new LinkedHashMap<String, MatchPlanFieldEvidence>(), false, "Personne", pLocale);
		final int start = html.indexOf("<text", html.indexOf("font-weight=\"700\""));
		return html.substring(start, html.indexOf("</svg>"));
	}

	private static void relationFields_notSearchedByDefault_listedOnlyInTheNote() throws Exception
	{
		final String band = MatchPlanTreeDiagramTest.bandOf(MatchPlanTreeDiagramTest.personneRelationRequirements(""), Locale.ENGLISH);
		final Matcher searched = Pattern.compile("Candidates are first searched on ([^<]*) \\(approximate search\\)").matcher(band);
		MatchPlanTreeDiagramTest.check("the search sentence is there: " + band, searched.find());
		MatchPlanTreeDiagramTest.check("it names the clustering field and the parent's own field", searched.group(1).contains("Email key") && searched.group(1).contains("Last name"));
		MatchPlanTreeDiagramTest.check("it does not name a relation field: " + searched.group(1), !searched.group(1).contains("Street") && !searched.group(1).contains("City"));
		MatchPlanTreeDiagramTest.check("the note says relation fields are compared but not searched",
			band.contains("related records (Street, City) are compared below but take no part in this search"));
	}

	private static void relationField_withPositiveWeight_listedAmongTheSearchFields() throws Exception
	{
		final String band = MatchPlanTreeDiagramTest.bandOf(MatchPlanTreeDiagramTest.personneRelationRequirements(",\"relationClusteringWeights\":{\"Adresse.street\":1}"),
			Locale.ENGLISH);
		final Matcher searched = Pattern.compile("Candidates are first searched on ([^<]*) \\(approximate search\\)").matcher(band);
		MatchPlanTreeDiagramTest.check("the search sentence is there: " + band, searched.find());
		MatchPlanTreeDiagramTest.check("Street (weight 1) is searched: " + searched.group(1), searched.group(1).contains("Street"));
		MatchPlanTreeDiagramTest.check("City (default 0) is not", !searched.group(1).contains("City"));
		MatchPlanTreeDiagramTest.check("the note lists City only", band.contains("related records (City) are compared below"));

		final String zero = MatchPlanTreeDiagramTest.bandOf(MatchPlanTreeDiagramTest.personneRelationRequirements(",\"relationClusteringWeights\":{\"Adresse.street\":0}"),
			Locale.ENGLISH);
		MatchPlanTreeDiagramTest.check("an explicit 0 behaves as the default", zero.contains("related records (Street, City) are compared below"));
	}

	private static void noRelationField_noRelationNote() throws Exception
	{
		final String html = MatchPlanTreeDiagramTest.buildSixNode(false);
		MatchPlanTreeDiagramTest.check("no relation note on a plan without relation field", !html.contains("related records"));
	}

	private static void relationNote_existsInFrench() throws Exception
	{
		final String band = MatchPlanTreeDiagramTest.bandOf(MatchPlanTreeDiagramTest.personneRelationRequirements(""), Locale.FRENCH);
		MatchPlanTreeDiagramTest.check("the French note is rendered: " + band, band.contains("enregistrements li\u00E9s (Street, City)") && band.contains("ne servent pas"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
