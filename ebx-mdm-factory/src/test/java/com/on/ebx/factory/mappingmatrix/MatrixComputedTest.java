package com.on.ebx.factory.mappingmatrix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.capability.FunctionDoc;
import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.review.ReviewLine;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws.
 * <p>
 * fix/matrix-computed-field (docs/MAPPING-MATRIX.md sec 14): what the Mapping matrix says about a computed
 * ({@code SCRIPT}) field - the call and its binding, the library's own doc text, the measured counts, the chip, the
 * decision, and that the field and its function are ONE item left to decide. The model is a PersVol9-like entity
 * (project PersVol9, attribute {@code phoneE164} read from {@code phone}); the counts of the "loaded" state are MADE UP
 * for the test and labelled so - they are not a measurement of any instance.
 */
public final class MatrixComputedTest
{
	/** The library's own doc comment of {@code toE164}, as read through the scripting server. */
	private static final String LIBRARY_SOURCE = String.join("\n",
		"/**",
		" * The canonical form to store: '+' then the calling code then the national significant number,",
		" * no separators. This is the value a phone column should hold.",
		" * @param value the number, in either form, may be null",
		" * @param country an ISO 3166-1 alpha-2 code; leave null or blank to read the country off an",
		" *        international value, which then only has to satisfy isValid()",
		" * @return the E.164 string, or null when the number is not valid",
		" */",
		"export function toE164(value: string, country: string): string",
		"begin",
		"  return null;",
		"end");
	private static final String BINDING = "{\"value\":\"phone\",\"country\":{\"literal\":\"\"}}";
	private static final Locale EN = Locale.ENGLISH;

	public static void main(final String[] pArgs)
	{
		MatrixComputedTest.callTextShowsFieldsByNameAndConstantsWithTheirParameter();
		MatrixComputedTest.argLinesNameTheFieldTheConstantAndAVisibleBlank();
		MatrixComputedTest.returnsShortKeepsTheFirstClauseOfTheLibrarysReturnText();
		MatrixComputedTest.notLoadedSaysSoAndShowsNoNumber();
		MatrixComputedTest.loadedShowsTheCountsPerSourceWithOneExampleOfEachKind();
		MatrixComputedTest.notAppliedAndUnreadableAreSaidNotMeasured();
		MatrixComputedTest.sampledLinesSayHowManyRowsWereEvaluated();
		MatrixComputedTest.cellSaysWhatIsComputedAndTheChipOpensTheSameInspector();
		MatrixComputedTest.withoutTheLibraryTextTheInspectorFallsBackToTheRationaleAndSaysSo();
		MatrixComputedTest.decisionFollowsTheFieldAndTheEntity();
		MatrixComputedTest.theFieldAndItsFunctionAreOneItemLeftToDecide();
		MatrixComputedTest.otherDerivationsKeepTheirCurrentText();
		MatrixComputedTest.libraryAndRationaleTextsAreHtmlEscaped();
		MatrixComputedTest.scriptHandsComputedCellsAndChipsToTheirOwnInspector();
		System.out.println("MatrixComputedTest OK");
	}

	// ------------------------------------------------------------------------------------------------ fixtures

	private static MatrixModel.Computation computation(final String pBinding, final MatrixModel.Measurement pMeasurement, final boolean pWithDoc, final String pSuggestionStatus)
	{
		return new MatrixModel.Computation("98", pSuggestionStatus, "library/Fasttrack/lib/phone", "toE164", pBinding,
			"Converts supported phone inputs to a canonical international form.", pWithDoc ? FunctionDoc.parse(MatrixComputedTest.LIBRARY_SOURCE, "toE164") : null,
			pWithDoc ? "" : "the scripting server is not usable", pMeasurement);
	}

	private static Guard guard(final String pStatus)
	{
		return new Guard("98", ReviewLine.SCRIPT_DERIVATION, "95", "1345", "phone.toE164", "", null, null, pStatus, "LLM", Integer.valueOf(86),
			"Converts supported phone inputs to a canonical international form.", null, null, null);
	}

	private static MatrixModel.Project project(final String pEntityStatus, final String pFieldStatus, final String pGuardStatus, final MatrixModel.Computation pComputation)
	{
		final MatrixModel.Attr key = new MatrixModel.Attr("a0", "sourceId", "STRING", true, false, true, "", null);
		final MatrixModel.Attr phone = new MatrixModel.Attr("1331", "phone", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(95));
		final MatrixModel.Attr phoneE164 = new MatrixModel.Attr("1345", "phoneE164", "STRING", false, false, false, pFieldStatus, Integer.valueOf(86),
			Arrays.asList(MatrixComputedTest.guard(pGuardStatus)), "SCRIPT", "phone", pComputation);
		final List<MatrixModel.Feed> feeds = new ArrayList<MatrixModel.Feed>();
		for (final String[] source : new String[][] { { "crm", "PERSVOLCRM" }, { "erp", "PERSVOLERP" }, { "ecom", "PERSVOLECOM" } })
		{
			final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
			cells.put("1331", new MatrixModel.Cell(MatrixModel.ACCEPTED, "m" + source[0], "PHONE", null, Integer.valueOf(95), "RULE", null, null, 0, null, null));
			feeds.add(new MatrixModel.Feed("f-" + source[0], source[0], "t-" + source[0], source[1], "ACCEPTED", null, "", false, null, cells));
		}
		final MatrixModel.Entity entity = new MatrixModel.Entity("95", "Personne", Arrays.asList(key, phone, phoneE164), feeds, pEntityStatus, Integer.valueOf(100));
		return new MatrixModel.Project("PersVol9", Arrays.asList(new MatrixModel.Source("crm", "CRM", "#2f6fd0"), new MatrixModel.Source("erp", "ERP", "#0e9384"),
			new MatrixModel.Source("ecom", "ECOM", "#c77700")), Arrays.asList(entity));
	}

	private static String render(final MatrixModel.Project pProject)
	{
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "95";
		return MatrixHtml.render(pProject, state);
	}

	private static MatrixModel.Attr computedAttr(final MatrixModel.Project pProject)
	{
		return pProject.entities.get(0).attrs.get(2);
	}

	/** Made-up counts for the "loaded" state - NOT measured on any instance. */
	private static MatrixModel.Measurement testCounts()
	{
		return MatrixModel.Measurement.measured(300, Arrays.asList(
			new MatrixModel.SourceCount("CRM", 600, 600, 300, 300, 0, "+33 6 12 34 56 78", "+33612345678", null),
			new MatrixModel.SourceCount("ERP", 500, 480, 300, 290, 10, "+49 30 123456", "+4930123456", "12345"),
			new MatrixModel.SourceCount("ECOM", 313, 313, 300, 0, 300, null, null, "0162059624")));
	}

	// ---------------------------------------------------------------------------------------------------- tests

	private static void callTextShowsFieldsByNameAndConstantsWithTheirParameter()
	{
		final MatrixModel.Computation c = MatrixComputedTest.computation(MatrixComputedTest.BINDING, null, true, "PROPOSED");
		MatrixComputedTest.check("field by name, blank constant as country = \"\"", "toE164(phone, country = \"\")".equals(MatrixComputed.callText(c)));
		final MatrixModel.Computation fr = MatrixComputedTest.computation("{\"value\":\"phone\",\"country\":{\"literal\":\"FR\"}}", null, true, "PROPOSED");
		MatrixComputedTest.check("a non-blank constant is quoted", "toE164(phone, country = \"FR\")".equals(MatrixComputed.callText(fr)));
		final MatrixModel.Computation later = MatrixComputedTest.computation("{\"value\":\"phone\",\"country\":{\"toBeProvided\":\"which country\"}}", null, true, "PROPOSED");
		MatrixComputedTest.check("a still-to-provide argument reads = ?", "toE164(phone, country = ?)".equals(MatrixComputed.callText(later)));
		MatrixComputedTest.check("no function: empty", MatrixComputed.callText(null).isEmpty());
		MatrixComputedTest.check("an unreadable binding: the bare call, never a failure",
			"toE164()".equals(MatrixComputed.callText(MatrixComputedTest.computation("not json", null, true, "PROPOSED"))));
	}

	private static void argLinesNameTheFieldTheConstantAndAVisibleBlank()
	{
		final List<MatrixComputed.Arg> args = MatrixComputed.args(MatrixComputedTest.computation(MatrixComputedTest.BINDING, null, true, "PROPOSED"));
		MatrixComputedTest.check("two arguments, in binding order", args.size() == 2 && "value".equals(args.get(0).parameter) && "country".equals(args.get(1).parameter));
		final String field = MatrixComputed.argLine(args.get(0), EN);
		MatrixComputedTest.check("the field-fed argument: name -> field phone", field.startsWith("value ") && field.endsWith("field phone"));
		final String blank = MatrixComputed.argLine(args.get(1), EN);
		MatrixComputedTest.check("an empty constant is shown as blank, never as an invisible nothing", blank.startsWith("country ") && blank.endsWith("blank (constant)"));
		final MatrixComputed.Arg fr = MatrixComputed.args(MatrixComputedTest.computation("{\"country\":{\"literal\":\"FR\"}}", null, true, "PROPOSED")).get(0);
		MatrixComputedTest.check("a non-blank constant is shown quoted", MatrixComputed.argLine(fr, EN).endsWith("constant \"FR\""));
		// lot P1 (2026-10-04): a to-be-provided parameter says where it is supplied - wording only, no input on this screen
		final MatrixComputed.Arg later = MatrixComputed.args(MatrixComputedTest.computation("{\"country\":{\"toBeProvided\":\"which country\"}}", null, true, "PROPOSED")).get(0);
		MatrixComputedTest.check("a to-be-provided parameter reads: " + MatrixComputed.argLine(later, EN), MatrixComputed.argLine(later, EN).equals("country \u2192 to be provided before it can be applied (wizard, step 4)"));
		MatrixComputedTest.check("and in French: " + MatrixComputed.argLine(later, java.util.Locale.FRENCH), MatrixComputed.argLine(later, java.util.Locale.FRENCH)
			.equals("country \u2192 \u00E0 fournir avant de pouvoir l\u2019appliquer (assistant, \u00E9tape 4)"));
	}

	private static void returnsShortKeepsTheFirstClauseOfTheLibrarysReturnText()
	{
		final MatrixModel.Computation c = MatrixComputedTest.computation(MatrixComputedTest.BINDING, null, true, "PROPOSED");
		MatrixComputedTest.check("the E.164 string (the rest is the 'when it is empty' detail)", "the E.164 string".equals(MatrixComputed.returnsShort(c)));
		MatrixComputedTest.check("no library text: nothing invented", MatrixComputed.returnsShort(MatrixComputedTest.computation(MatrixComputedTest.BINDING, null, false, "PROPOSED")).isEmpty());
	}

	private static void notLoadedSaysSoAndShowsNoNumber()
	{
		final MatrixModel.Project project = MatrixComputedTest.project("ACCEPTED", "PROPOSED", "PROPOSED",
			MatrixComputedTest.computation(MatrixComputedTest.BINDING, MatrixModel.Measurement.notLoaded(), true, "PROPOSED"));
		final String json = MatrixComputed.cellJson("{\"status\":\"DERIVED\"}", project.entities.get(0), MatrixComputedTest.computedAttr(project), EN);
		MatrixComputedTest.check("exactly the sentence asked for", json.contains("\"cMeasureState\":\"Measured after the first staging load.\""));
		MatrixComputedTest.check("and no line of counts", json.contains("\"cMeasure\":[]"));
	}

	private static void loadedShowsTheCountsPerSourceWithOneExampleOfEachKind()
	{
		final MatrixModel.Project project = MatrixComputedTest.project("ACCEPTED", "PROPOSED", "PROPOSED",
			MatrixComputedTest.computation(MatrixComputedTest.BINDING, MatrixComputedTest.testCounts(), true, "APPLIED"));
		final String json = MatrixComputed.cellJson("{\"status\":\"DERIVED\"}", project.entities.get(0), MatrixComputedTest.computedAttr(project), EN);
		MatrixComputedTest.check("no 'measured after' sentence once there are counts", json.contains("\"cMeasureState\":\"\""));
		MatrixComputedTest.check("ECOM: nothing converted, all empty", json.contains("ECOM: on the first 300 of 313 filled values (out of 313 rows), 0 were converted and 300 came out empty.")
			|| json.contains("ECOM: of 313 filled values (out of 313 rows), 0 were converted and 300 came out empty."));
		MatrixComputedTest.check("CRM line", json.contains("CRM: on the first 300 of 600 filled values (out of 600 rows), 300 were converted and 0 came out empty."));
		MatrixComputedTest.check("the example of a converted value", json.contains("Converted: +33 6 12 34 56 78 \\u2192 +33612345678") || json.contains("Converted: +33 6 12 34 56 78 → +33612345678"));
		MatrixComputedTest.check("the example of an empty one (the national form that comes out empty)", json.contains("Empty: 0162059624 → (empty)"));
		MatrixComputedTest.check("a source with no empty case has no empty example", json.contains("\"empty\":\"\""));
	}

	private static void notAppliedAndUnreadableAreSaidNotMeasured()
	{
		final MatrixModel.Project notApplied = MatrixComputedTest.project("ACCEPTED", "PROPOSED", "PROPOSED",
			MatrixComputedTest.computation(MatrixComputedTest.BINDING, MatrixModel.Measurement.notApplied(), true, "ACCEPTED"));
		final String json = MatrixComputed.cellJson("{}", notApplied.entities.get(0), MatrixComputedTest.computedAttr(notApplied), EN);
		MatrixComputedTest.check("not applied: said so, no number", json.contains("Not measured: the function is not applied to the staging model yet") && json.contains("\"cMeasure\":[]"));
		final MatrixModel.Project unreadable = MatrixComputedTest.project("ACCEPTED", "PROPOSED", "PROPOSED",
			MatrixComputedTest.computation(MatrixComputedTest.BINDING, MatrixModel.Measurement.unavailable("staging dataset X/Y not found"), true, "APPLIED"));
		final String json2 = MatrixComputed.cellJson("{}", unreadable.entities.get(0), MatrixComputedTest.computedAttr(unreadable), EN);
		MatrixComputedTest.check("unreadable: the reason, no number", json2.contains("Not measured: the staging table could not be read (staging dataset X/Y not found).") && json2.contains("\"cMeasure\":[]"));
	}

	private static void sampledLinesSayHowManyRowsWereEvaluated()
	{
		final String sampled = MatrixComputed.measureLine(new MatrixModel.SourceCount("ECOM", 313, 313, 300, 0, 300, null, null, "x"), EN);
		MatrixComputedTest.check("300 of 313: the line says 'on the first 300 of 313'", sampled.contains("on the first 300 of 313 filled values"));
		final String full = MatrixComputed.measureLine(new MatrixModel.SourceCount("ECOM", 40, 40, 40, 30, 10, null, null, null), EN);
		MatrixComputedTest.check("every filled value read: no 'first N' qualifier", !full.contains("on the first") && full.contains("of 40 filled values"));
		MatrixComputedTest.check("no row at all", MatrixComputed.measureLine(new MatrixModel.SourceCount("ERP", 0, 0, 0, 0, 0, null, null, null), EN).contains("no staging row yet"));
		MatrixComputedTest.check("never filled", MatrixComputed.measureLine(new MatrixModel.SourceCount("ERP", 12, 0, 0, 0, 0, null, null, null), EN).contains("never filled in these 12 rows"));
		MatrixComputedTest.check("French wording exists", MatrixComputed.measureLine(new MatrixModel.SourceCount("ECOM", 313, 313, 300, 0, 300, null, null, null), Locale.FRENCH).contains("sur les 300 premi"));
	}

	private static void cellSaysWhatIsComputedAndTheChipOpensTheSameInspector()
	{
		final MatrixModel.Project project = MatrixComputedTest.project("ACCEPTED", "PROPOSED", "PROPOSED",
			MatrixComputedTest.computation(MatrixComputedTest.BINDING, MatrixModel.Measurement.notLoaded(), true, "PROPOSED"));
		final String html = MatrixComputedTest.render(project);
		MatrixComputedTest.check("line 1 stays 'computed at load'", html.contains("<span class=\"c1\">computed at load</span>"));
		MatrixComputedTest.check("line 2 is the call and what it returns", html.contains("<span class=\"c2\">toE164(phone, country = &quot;&quot;) → the E.164 string</span>"));
		MatrixComputedTest.check("the rules cell no longer says 'not applicable'", !html.contains("not applicable"));
		final String chipLabel = MatrixMessages.get(EN, "mappingMatrix.computed.chip", "toE164");
		MatrixComputedTest.check("the chip reads 'Script - toE164'", chipLabel.startsWith("Script ") && chipLabel.endsWith("toE164") && html.contains(chipLabel + "</button>"));
		MatrixComputedTest.check("the chip is a selectable guard chip", html.contains("class=\"mdmmGChip check\" data-key=\"guard|98\""));
		// the chip and the cell carry the same inspector data
		final int cellAt = html.indexOf("data-cell=\"", html.indexOf("mdmmCell link derived"));
		final String cellData = html.substring(cellAt, html.indexOf('"', cellAt + 11));
		final int chipAt = html.indexOf("data-cell=\"", html.indexOf("mdmmGChip check"));
		final String chipData = html.substring(chipAt, html.indexOf('"', chipAt + 11));
		MatrixComputedTest.check("same data-cell on the cell and on the chip", cellData.equals(chipData) && cellData.contains("&quot;computed&quot;:true"));
		MatrixComputedTest.check("the first sentence of the function's own description", cellData.contains("The canonical form to store"));
		MatrixComputedTest.check("the @param text of an argument", cellData.contains("an ISO 3166-1 alpha-2 code; leave null or blank"));
		MatrixComputedTest.check("the @return text as 'when it is empty'", cellData.contains("the E.164 string, or null when the number is not valid"));
		MatrixComputedTest.check("the rationale as 'why it was proposed'", cellData.contains("Converts supported phone inputs to a canonical international form."));
	}

	private static void withoutTheLibraryTextTheInspectorFallsBackToTheRationaleAndSaysSo()
	{
		final MatrixModel.Project project = MatrixComputedTest.project("ACCEPTED", "PROPOSED", "PROPOSED",
			MatrixComputedTest.computation(MatrixComputedTest.BINDING, MatrixModel.Measurement.notLoaded(), false, "PROPOSED"));
		final String json = MatrixComputed.cellJson("{}", project.entities.get(0), MatrixComputedTest.computedAttr(project), EN);
		MatrixComputedTest.check("says the description could not be read", json.contains("The description of the function toE164 could not be read from the script library."));
		MatrixComputedTest.check("the rationale is still there", json.contains("\"cWhy\":\"Converts supported phone inputs"));
		MatrixComputedTest.check("the arguments are still listed, without a library text", json.contains("country \\u2192 blank") || json.contains("country → blank"));
		final String html = MatrixComputedTest.render(project);
		MatrixComputedTest.check("the cell shows the call alone", html.contains("<span class=\"c2\">toE164(phone, country = &quot;&quot;)</span>"));
	}

	private static void decisionFollowsTheFieldAndTheEntity()
	{
		final String open = MatrixComputedTest.decide("ACCEPTED", "PROPOSED", "PROPOSED");
		MatrixComputedTest.check("field PROPOSED, entity accepted: the field's own Accept/Reject", open.contains("\"cDecide\":\"FIELD\"") && open.contains("Accept the computed field"));
		final String entityFirst = MatrixComputedTest.decide("PROPOSED", "PROPOSED", "PROPOSED");
		MatrixComputedTest.check("entity still PROPOSED: accept the business object first (same rule as a cell)", entityFirst.contains("\"cDecide\":\"ENTITY_FIRST\""));
		final String done = MatrixComputedTest.decide("ACCEPTED", "ACCEPTED", "ACCEPTED");
		MatrixComputedTest.check("field and function decided: no button", done.contains("\"cDecide\":\"\""));
		final String old = MatrixComputedTest.decide("ACCEPTED", "ACCEPTED", "PROPOSED");
		MatrixComputedTest.check("an older row: field accepted, function still PROPOSED keeps its own two buttons", old.contains("\"cDecide\":\"GUARD\"") && old.contains("\"cGuardId\":\"98\"")
			&& old.contains("Accept the function"));
	}

	private static String decide(final String pEntity, final String pField, final String pGuard)
	{
		final MatrixModel.Project project = MatrixComputedTest.project(pEntity, pField, pGuard,
			MatrixComputedTest.computation(MatrixComputedTest.BINDING, MatrixModel.Measurement.notLoaded(), true, pGuard));
		return MatrixComputed.cellJson("{}", project.entities.get(0), MatrixComputedTest.computedAttr(project), EN);
	}

	private static void theFieldAndItsFunctionAreOneItemLeftToDecide()
	{
		final MatrixModel.Project open = MatrixComputedTest.project("ACCEPTED", "PROPOSED", "PROPOSED", null);
		MatrixComputedTest.check("field + function PROPOSED: ONE item still to decide, not two", MatrixCompute.itemsStillToDecide(open, MatrixCompute.DEFAULT_BULK_THRESHOLD) == 1);
		MatrixComputedTest.check("the guard column does not count the function on its own", MatrixCompute.entityGuardStats(open.entities.get(0)).proposed == 0);
		MatrixComputedTest.check("but it still is one check of the entity", MatrixCompute.entityGuardStats(open.entities.get(0)).checks == 1);
		final MatrixModel.Project both = MatrixComputedTest.project("ACCEPTED", "ACCEPTED", "ACCEPTED", null);
		MatrixComputedTest.check("both accepted (what the field's Accept does): nothing left, the footer can reach 0",
			MatrixCompute.itemsStillToDecide(both, MatrixCompute.DEFAULT_BULK_THRESHOLD) == 0 && MatrixCompute.readyToLoad(both, MatrixCompute.DEFAULT_BULK_THRESHOLD));
		final MatrixModel.Project legacy = MatrixComputedTest.project("ACCEPTED", "ACCEPTED", "PROPOSED", null);
		MatrixComputedTest.check("field decided but function still PROPOSED (an older row): it is an item of its own again",
			MatrixCompute.itemsStillToDecide(legacy, MatrixCompute.DEFAULT_BULK_THRESHOLD) == 1);
		final MatrixModel.Attr attr = MatrixComputedTest.computedAttr(open);
		MatrixComputedTest.check("decidedWithItsField is true only for the SCRIPT_DERIVATION guard of a PROPOSED computed field",
			MatrixCompute.decidedWithItsField(attr, attr.guards.get(0)) && !MatrixCompute.decidedWithItsField(MatrixComputedTest.computedAttr(both), both.entities.get(0).attrs.get(2).guards.get(0)));
		final Guard quality = new Guard("q1", ReviewLine.QUALITY, "95", "1345", "PATTERN", "x", "WARN", null, "PROPOSED", "LLM", Integer.valueOf(90), null, null, null, null);
		MatrixComputedTest.check("a quality rule on the same field is not decided with it here", !MatrixCompute.decidedWithItsField(attr, quality));
		MatrixComputedTest.check("a sweep never takes the computed field (unchanged) - so not its function either",
			!MatrixCompute.planEntityBulk(open.entities.get(0), 0).toDecide.toString().contains("98") && MatrixComputedTest.noPlannedId(MatrixCompute.planEntityBulk(open.entities.get(0), 0), "1345"));
	}

	private static boolean noPlannedId(final MatrixCompute.BulkPlan pPlan, final String pId)
	{
		for (final MatrixCompute.PlannedDecision decision : pPlan.toDecide)
		{
			if (pId.equals(decision.id) || "98".equals(decision.id))
			{
				return false;
			}
		}
		return true;
	}

	private static void otherDerivationsKeepTheirCurrentText()
	{
		final MatrixModel.Attr key = new MatrixModel.Attr("a2", "firstNameKey", "STRING", false, false, false, "PROPOSED", Integer.valueOf(90), null, "FOLD_KEY", "firstName");
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Personne", Arrays.asList(key), Arrays.<MatrixModel.Feed>asList(), "ACCEPTED", Integer.valueOf(100));
		final MatrixModel.Project project = new MatrixModel.Project("P", Arrays.<MatrixModel.Source>asList(), Arrays.asList(entity));
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		final String html = MatrixHtml.render(project, state);
		MatrixComputedTest.check("a matching key still says 'not applicable' in the rules cell", html.contains("not applicable"));
		MatrixComputedTest.check("and its cell text is unchanged", html.contains("from firstName, accents stripped, upper case") && !html.contains("\"computed\":true") && !html.contains("&quot;computed&quot;"));
		final MatrixModel.Attr scriptNoSuggestion = new MatrixModel.Attr("a3", "phoneE164", "STRING", false, false, false, "PROPOSED", Integer.valueOf(90), null, "SCRIPT", "phone");
		final MatrixModel.Entity entity2 = new MatrixModel.Entity("e1", "Personne", Arrays.asList(scriptNoSuggestion), Arrays.<MatrixModel.Feed>asList(), "ACCEPTED", Integer.valueOf(100));
		final String html2 = MatrixHtml.render(new MatrixModel.Project("P", Arrays.<MatrixModel.Source>asList(), Arrays.asList(entity2)), state);
		MatrixComputedTest.check("a SCRIPT field with no readable suggestion keeps the generic text", html2.contains("from phone, by a published library function") && html2.contains("not applicable"));
	}

	private static void libraryAndRationaleTextsAreHtmlEscaped()
	{
		final String evilSource = String.join("\n", "/**", " * <img src=x onerror=alert(1)> does things.", " * @param value <script>alert(2)</script>", " * @return </script><b>x</b>", " */",
			"export function toE164(value: string, country: string): string", "begin", "end");
		final MatrixModel.Computation c = new MatrixModel.Computation("98", "PROPOSED", "library/x", "toE164", MatrixComputedTest.BINDING, "<img src=y onerror=alert(3)>",
			FunctionDoc.parse(evilSource, "toE164"), "", MatrixModel.Measurement.notLoaded());
		final String html = MatrixComputedTest.render(MatrixComputedTest.project("ACCEPTED", "PROPOSED", "PROPOSED", c));
		MatrixComputedTest.check("no raw <img> from the library or the rationale in the page", !html.contains("<img src=x") && !html.contains("<img src=y"));
		MatrixComputedTest.check("no raw <script> from the library in the page", !html.contains("<script>alert(2)"));
		MatrixComputedTest.check("escaped instead", html.contains("&lt;img src=x onerror=alert(1)&gt;"));
	}

	private static void scriptHandsComputedCellsAndChipsToTheirOwnInspector()
	{
		final String script = MatrixHtml.script(EN);
		MatrixComputedTest.check("the computed inspector exists", script.contains("function selComputed(btn,d)"));
		MatrixComputedTest.check("it wraps the two selection handlers instead of rewriting them", script.contains("var baseSelect=window.mdmmSelect;") && script.contains("var baseSelectGuard=window.mdmmSelectGuard;"));
		MatrixComputedTest.check("every data-derived field goes through e() before innerHTML", script.contains("e(d.cWhat)") && script.contains("e(as[k].line)") && script.contains("e(ms[m].line)")
			&& script.contains("e(d.cWhy)") && script.contains("e(d.cCall)"));
		MatrixComputedTest.check("the decision buttons reuse the existing handlers (field accept/reject, guard decide)",
			script.contains("mdmmAcceptAttribute(") && script.contains("mdmmRejectAttribute(") && script.contains("mdmmGuardDecide("));
		MatrixComputedTest.check("the sections come in the asked order: what, empty, measured, why, decision",
			script.indexOf("d.cWhatTitle") < script.indexOf("d.cEmptyTitle") && script.indexOf("d.cEmptyTitle") < script.indexOf("d.cMeasureTitle")
				&& script.indexOf("d.cMeasureTitle") < script.indexOf("d.cWhyTitle") && script.indexOf("d.cWhyTitle") < script.indexOf("d.cDecide==='FIELD'"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
