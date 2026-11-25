package com.on.ebx.factory.stagingdesk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.review.ReviewLine;

/**
 * JUnit-free: run the main, a failure throws. Validation desk lot L3 - the points to check: the callouts collected from a matrix model (pure) and their HTML. Fixture = the
 * DemoBala case: Adresse.addressType is fed {@code constant(MAILING)} by both feeds, the SAP rationale says the row is the RESIDENTIAL address.
 */
public final class DeskPointsHtmlTest
{
	public static void main(final String[] pArgs)
	{
		DeskPointsHtmlTest.aSharedConstantGivesOneCalloutWithOneRowPerSource();
		DeskPointsHtmlTest.withoutAlternativeTheCalloutOffersToAsk();
		DeskPointsHtmlTest.withAnAlternativeTheButtonsAreTheMatrixDecisions();
		DeskPointsHtmlTest.aDecidedLinkNoLongerWaits();
		DeskPointsHtmlTest.aChildFeedClimbsToItsRootDesigner();
		DeskPointsHtmlTest.nothingIsRenderedWithoutAPoint();
		DeskPointsHtmlTest.aReadOnlySessionSeesDisabledButtonsAndFrenchWords();
		DeskPointsHtmlTest.theCentreCallsTheCalloutAboveThePreview();
		DeskPointsHtmlTest.theOlderListDoesNotRepeatASameConstantFlag();
		DeskPointsHtmlTest.aSourceTheModelKeptIsSaidAndNoLongerAsked();
		System.out.println("DeskPointsHtmlTest OK");
	}

	private static MatrixModel.Cell cell(final String pStatus, final String pMappingId, final String pRationale, final MatrixModel.Alternative pAlternative)
	{
		return new MatrixModel.Cell(pStatus, pMappingId, "Type", Arrays.asList("constant(MAILING)"), Integer.valueOf(90), "LLM", pRationale, null, 0, null, null, "SAME_CONSTANT",
			"every source writes MAILING", pAlternative, null, "ADDRESS_TYPE", Double.valueOf(100.0), "constant(MAILING)");
	}

	private static MatrixModel.Feed feed(final String pId, final String pSourceId, final String pParent, final String pDesigner, final MatrixModel.Cell pCell)
	{
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		if (pCell != null)
		{
			cells.put("a1", pCell);
		}
		return new MatrixModel.Feed(pId, pSourceId, "t" + pId, "TABLE_" + pId, ReviewLine.ACCEPTED, pParent, "", false, null, cells, Integer.valueOf(90), "", pDesigner);
	}

	private static MatrixModel.Project project(final MatrixModel.Cell pSap, final MatrixModel.Cell pSalesforce, final String pParentOfSap, final String pSapDesigner)
	{
		final MatrixModel.Attr type = new MatrixModel.Attr("a1", "addressType", "STRING", false, false, false, ReviewLine.PROPOSED, Integer.valueOf(90));
		final MatrixModel.Entity adresse = new MatrixModel.Entity("e2", "Adresse", Arrays.asList(type),
			Arrays.asList(DeskPointsHtmlTest.feed("f695", "s1", pParentOfSap, pSapDesigner, pSap), DeskPointsHtmlTest.feed("f696", "s2", null, "/designer/696", pSalesforce)),
			ReviewLine.PROPOSED, Integer.valueOf(90));
		final MatrixModel.Entity personne = new MatrixModel.Entity("e1", "Personne", new ArrayList<MatrixModel.Attr>(),
			Arrays.asList(DeskPointsHtmlTest.feed("f1", "s1", null, "/designer/root-sap", null)), ReviewLine.ACCEPTED, Integer.valueOf(90));
		return new MatrixModel.Project("DemoBala", Arrays.asList(new MatrixModel.Source("s1", "SAP", "#000"), new MatrixModel.Source("s2", "Salesforce", "#111")),
			Arrays.asList(personne, adresse));
	}

	private static final String SAP_WHY = "SAP rows are the person's residential address";

	private static List<DeskPointsHtml.Point> collect(final MatrixModel.Project pProject)
	{
		return DeskPointsHtml.collect(pProject, pProject.entityById("e2"));
	}

	private static String html(final List<DeskPointsHtml.Point> pPoints, final boolean pReadOnly, final Locale pLocale)
	{
		final DeskHtml.State state = new DeskHtml.State();
		state.readOnly = pReadOnly;
		final StringBuilder out = new StringBuilder();
		DeskPointsHtml.render(out, pPoints, "e2", state, pLocale);
		return out.toString();
	}

	private static void aSharedConstantGivesOneCalloutWithOneRowPerSource()
	{
		final List<DeskPointsHtml.Point> points = DeskPointsHtmlTest.collect(DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m695", DeskPointsHtmlTest.SAP_WHY, null),
			DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m696", "Salesforce mailing address", null), null, "/designer/695"));
		check("one point for the attribute", points.size() == 1 && "addressType".equals(points.get(0).attributeName) && "MAILING".equals(points.get(0).value));
		check("one row per source, in feed order, with the system's name", points.get(0).sources.size() == 2 && "SAP".equals(points.get(0).sources.get(0).sourceName)
			&& "Salesforce".equals(points.get(0).sources.get(1).sourceName));
		check("the rationale is the mapping's own", DeskPointsHtmlTest.SAP_WHY.equals(points.get(0).sources.get(0).rationale));
		final String html = DeskPointsHtmlTest.html(points, false, Locale.ENGLISH);
		check("the sentence is quoted", html.contains("“" + DeskPointsHtmlTest.SAP_WHY.replace("'", "&#39;") + "”"));
		check("what is measured", html.contains("addressType: every source writes MAILING") && html.contains("writes MAILING into addressType"));
		check("another value opens the existing designer", html.contains("href=\"/designer/695\"") && html.contains("Another value..."));
	}

	private static void withoutAlternativeTheCalloutOffersToAsk()
	{
		final List<DeskPointsHtml.Point> points = DeskPointsHtmlTest.collect(DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m695", "x", null),
			DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m696", "y", null), null, "/d"));
		check("it needs an ask", points.get(0).needsAsk());
		final String html = DeskPointsHtmlTest.html(points, false, Locale.ENGLISH);
		check("Ask for a correction posts the desk action with the object", html.contains("mdskDo('ASK_CORRECTION','e2','',false)") && html.contains("Ask for a correction"));
		check("Keep MAILING is the matrix' accept of the original mapping, per source", html.contains("mdskDo('ACCEPT_MAPPING','m695','',false)") && html.contains("Keep MAILING for SAP")
			&& html.contains("mdskDo('ACCEPT_MAPPING','m696','',false)") && html.contains("Keep MAILING for Salesforce"));
		check("no Use button while the model proposed nothing", !html.contains("ACCEPT_ALTERNATIVE"));
	}

	private static void withAnAlternativeTheButtonsAreTheMatrixDecisions()
	{
		final MatrixModel.Alternative alt = new MatrixModel.Alternative("m900", "Type", Arrays.asList("constant(RESIDENTIAL)"), null, "The SAP rationale and the data say residential.");
		final List<DeskPointsHtml.Point> points = DeskPointsHtmlTest.collect(DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m695", "x", alt),
			DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m696", "y", null), null, "/d"));
		final DeskPointsHtml.Source sap = points.get(0).sources.get(0);
		check("the alternative is read from the matrix cell", sap.hasAlternative() && "RESIDENTIAL".equals(sap.altValue) && "m900".equals(sap.altMappingId));
		final String html = DeskPointsHtmlTest.html(points, false, Locale.ENGLISH);
		check("Use RESIDENTIAL for SAP is the matrix' accept of the alternative", html.contains("mdskDo('ACCEPT_ALTERNATIVE','m900','',false)") && html.contains("Use RESIDENTIAL for SAP"));
		check("the model's reason is shown", html.contains("The model proposes RESIDENTIAL:") && html.contains("The SAP rationale and the data say residential."));
		check("the other source still has no proposal, so the ask stays offered", points.get(0).needsAsk() && html.contains("ASK_CORRECTION"));
		final List<DeskPointsHtml.Point> both = DeskPointsHtmlTest.collect(DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m695", "x", alt),
			DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m696", "y", new MatrixModel.Alternative("m901", "Type", Arrays.asList("constant(MAILING)"), null, "")), null, "/d"));
		check("every source answered: no ask", !both.get(0).needsAsk() && !DeskPointsHtmlTest.html(both, false, Locale.ENGLISH).contains("ASK_CORRECTION"));
	}

	/** Lot L3b, the DemoBala case: the model gave SAP RESIDENTIAL (alternative m900) and judged MAILING right for Salesforce (m696). */
	private static void aSourceTheModelKeptIsSaidAndNoLongerAsked()
	{
		final MatrixModel.Alternative alt = new MatrixModel.Alternative("m900", "Type", Arrays.asList("constant(RESIDENTIAL)"), null, "The SAP rationale and the data say residential.");
		final MatrixModel.Project project = DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m695", "x", alt),
			DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m696", "y", null), null, "/d");
		final java.util.Map<String, com.on.ebx.factory.staging.ModelAnswers.Answer> answers = new java.util.HashMap<String, com.on.ebx.factory.staging.ModelAnswers.Answer>();
		answers.putAll(com.on.ebx.factory.staging.ModelAnswers.parse(com.on.ebx.factory.staging.ModelAnswers.withAnswers("{}",
			com.on.ebx.factory.staging.ModelAnswers.answers(Arrays.asList("m695", "m696"), Arrays.asList("m695"), "Mailing is the right type for a CRM billing row."))));
		final List<DeskPointsHtml.Point> points = DeskPointsHtml.collect(project, project.entityById("e2"), answers);
		check("Salesforce is marked kept, SAP is not", points.get(0).sources.get(1).modelKept && !points.get(0).sources.get(0).modelKept);
		check("every flagged mapping was submitted or has a proposal: no ask", !points.get(0).needsAsk());
		final String html = DeskPointsHtmlTest.html(points, false, Locale.ENGLISH);
		check("the callout says the model keeps MAILING for Salesforce, with its note", html.contains("The model keeps MAILING for Salesforce.") && html.contains("Mailing is the right type for a CRM billing row."));
		check("Ask for a correction is gone", !html.contains("ASK_CORRECTION") && !html.contains("Ask for a correction"));
		check("Keep MAILING for Salesforce and Another value stay", html.contains("Keep MAILING for Salesforce") && html.contains("href=\"/designer/696\"") && html.contains("mdskDo('ACCEPT_ALTERNATIVE','m900'"));
		check("French says it too", DeskPointsHtmlTest.html(points, false, Locale.FRENCH).contains("Le mod\u00E8le garde MAILING pour Salesforce."));
		// a mapping the model was never asked about (a newer one) brings the ask back
		final MatrixModel.Project changed = DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m695", "x", alt),
			DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m777", "y", null), null, "/d");
		final List<DeskPointsHtml.Point> after = DeskPointsHtml.collect(changed, changed.entityById("e2"), answers);
		check("Salesforce's mapping changed after the answer: not kept, ask again", !after.get(0).sources.get(1).modelKept && after.get(0).needsAsk()
			&& DeskPointsHtmlTest.html(after, false, Locale.ENGLISH).contains("ASK_CORRECTION"));
		check("never asked: the ask is offered and nothing is claimed", DeskPointsHtml.collect(project, project.entityById("e2"), null).get(0).needsAsk()
			&& !DeskPointsHtmlTest.html(DeskPointsHtml.collect(project, project.entityById("e2"), null), false, Locale.ENGLISH).contains("The model keeps"));
	}

	private static void aDecidedLinkNoLongerWaits()
	{
		final List<DeskPointsHtml.Point> points = DeskPointsHtmlTest.collect(DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.ACCEPTED, "m695", "x", null),
			DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m696", "y", null), null, "/d"));
		check("only the source still to decide stays", points.size() == 1 && points.get(0).sources.size() == 1 && "Salesforce".equals(points.get(0).sources.get(0).sourceName));
		final List<DeskPointsHtml.Point> none = DeskPointsHtmlTest.collect(DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.ACCEPTED, "m695", "x", null),
			DeskPointsHtmlTest.cell(MatrixModel.REJECTED, "m696", "y", null), null, "/d"));
		check("all decided: no callout", none.isEmpty());
	}

	private static void aChildFeedClimbsToItsRootDesigner()
	{
		// SAP's Adresse feed is a child of the Personne feed f1, which carries the designer link
		final MatrixModel.Project project = DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m695", "x", null),
			DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m696", "y", null), "f1", "");
		final List<DeskPointsHtml.Point> points = DeskPointsHtmlTest.collect(project);
		check("the child feed has no link of its own, its root's is used", "/designer/root-sap".equals(points.get(0).sources.get(0).designerUrl));
		check("a root feed keeps its own", "/designer/696".equals(points.get(0).sources.get(1).designerUrl));
	}

	private static void nothingIsRenderedWithoutAPoint()
	{
		check("no point, no markup", DeskPointsHtmlTest.html(new ArrayList<DeskPointsHtml.Point>(), false, Locale.ENGLISH).isEmpty() && DeskPointsHtmlTest.html(null, false, Locale.ENGLISH).isEmpty());
		final MatrixModel.Cell plain = new MatrixModel.Cell(MatrixModel.PROPOSED, "m1", "Type", Arrays.asList("trim()"), Integer.valueOf(90), "LLM", "r", null, 0, null, null, "CONSTANT_COLUMN", "always the same value", null);
		check("another flag is not this callout", DeskPointsHtmlTest.collect(DeskPointsHtmlTest.project(plain, plain, null, "")).isEmpty());
	}

	private static void aReadOnlySessionSeesDisabledButtonsAndFrenchWords()
	{
		final List<DeskPointsHtml.Point> points = DeskPointsHtmlTest.collect(DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m695", "x", null),
			DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m696", "y", null), null, "/d"));
		final String html = DeskPointsHtmlTest.html(points, true, Locale.ENGLISH);
		check("the decision buttons are disabled", html.contains("disabled=\"disabled\" onclick=\"mdskDo('ACCEPT_MAPPING'") && html.contains("disabled=\"disabled\" onclick=\"mdskDo('ASK_CORRECTION'"));
		final String fr = DeskPointsHtmlTest.html(points, false, Locale.FRENCH);
		check("French", fr.contains("Demander une correction") && fr.contains("Garder MAILING pour SAP") && fr.contains("Autre valeur..."));
		final String evil = DeskPointsHtmlTest.html(DeskPointsHtmlTest.collect(DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m695", "<b>\"x\"</b>", null),
			DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m696", "y", null), null, "/d")), false, Locale.ENGLISH);
		check("a rationale is escaped", !evil.contains("<b>\"x\"</b>") && evil.contains("&lt;b&gt;"));
	}

	private static void theCentreCallsTheCalloutAboveThePreview()
	{
		final DeskModel model = DeskGroupsTest.desk();
		final DeskHtml.State state = new DeskHtml.State();
		state.selectedKey = "obj|e2";
		state.points = DeskPointsHtmlTest.collect(DeskPointsHtmlTest.project(DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m695", "x", null),
			DeskPointsHtmlTest.cell(MatrixModel.PROPOSED, "m696", "y", null), null, "/d"));
		final String html = DeskHtml.render(model, state);
		check("the object's centre shows the callout", html.contains("mdskPoint") && html.contains("Point to check first"));
		final String other = DeskHtml.render(model, new DeskHtml.State());
		check("no point, no callout", !other.contains("class=\"mdskPoint\""));
	}

	/** Live fix, round 2: a SAME_CONSTANT flag is shown by its callout only; the older list keeps the other flags and disappears when empty. */
	private static void theOlderListDoesNotRepeatASameConstantFlag()
	{
		final DeskModel model = DeskGroupsTest.desk();
		DeskModel.Group group = null;
		for (final DeskModel.Group candidate : model.groups)
		{
			if ("obj|e2".equals(candidate.key))
			{
				group = candidate;
			}
		}
		check("the fixture has a flagged item in e2", group != null && group.flaggedItems > 0);
		final DeskHtml.State state = new DeskHtml.State();
		state.selectedKey = "obj|e2";
		check("flagged, no callout: the older list shows it", DeskHtml.render(model, state).contains("point to check first"));
		DeskModel.Line kept = null;
		for (final DeskModel.Line line : group.lines)
		{
			if (line.flagged())
			{
				line.sameConstant = true;
				state.points.add(new DeskPointsHtml.Point(line.item.attributeId, "x", "MAILING"));
				kept = line;
			}
		}
		check("every flag is a same-constant one with its callout: the older list is gone", !DeskHtml.render(model, state).contains("point to check first"));
		kept.sameConstant = false;
		check("one other flag stays in the list", DeskHtml.render(model, state).contains("point to check first"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
