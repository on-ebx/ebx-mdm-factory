package com.on.ebx.factory.stagingdesk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.mappingmatrix.RowTransform;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.review.ReviewLine;

/**
 * Validation desk lot L2 (docs/STAGING-DESK.md): the preview of real landing rows, built from fixture rows and a fixture matrix model - a constant, a regex, a missing value,
 * a pipeline that needs the load ("computed at load"), the parent-link chip, a matching key, a collection feed and an unreadable landing table falling back to the list, a flag
 * from the matrix' attention and one from a failing rule, the fill line - and the keys the "Accept as shown" button plans on. JUnit-free: run the main, a failure throws.
 * The fixture ({@link #preview}) is shared with {@code DeskHtmlTest}.
 */
public final class DeskPreviewTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		DeskPreviewTest.valuesAreWhatTheLoaderWouldStage();
		DeskPreviewTest.theSourceColumnNamesWhatIsTransformed();
		DeskPreviewTest.theParentLinkAndTheComputedFieldsAreNotValues();
		DeskPreviewTest.aRuleOfTheFieldFlagsTheValueItWouldRejectOrWarnOn();
		DeskPreviewTest.aRuleWithAConditionOnlyFlagsTheRowsThatMeetIt();
		DeskPreviewTest.theMatrixAttentionFlagsTheWholeColumn();
		DeskPreviewTest.theFillLineSaysHowManyRowsLackTheField();
		DeskPreviewTest.theHowLineNamesTheLandingColumnForACopy();
		DeskPreviewTest.aFeedThatCannotBeShownFallsBackWithTheMatrixWording();
		DeskPreviewTest.theKeysAndTheCountsOfTheAcceptButton();
		DeskPreviewTest.aCleaningStepRunsBeforeThePipelineAndLookupsStayOutOfTheMatrixSample();
		System.out.println("DeskPreviewTest OK");
	}

	// -------------------------------------------------------------------------------------------------------------- fixture

	private static ObjectNode row(final String... pPairs)
	{
		final ObjectNode node = DeskPreviewTest.MAPPER.createObjectNode();
		for (int i = 0; i < pPairs.length; i += 2)
		{
			if (pPairs[i + 1] == null)
			{
				node.putNull(pPairs[i]);
			}
			else
			{
				node.put(pPairs[i], pPairs[i + 1]);
			}
		}
		return node;
	}

	private static MatrixModel.Cell cell(final String pMappingId, final String pLabel, final String pField, final String pPipeline, final Double pFillRate, final String pAttention)
	{
		final List<String> steps = new ArrayList<String>();
		if (!pPipeline.isEmpty())
		{
			steps.add(pPipeline);
		}
		return new MatrixModel.Cell(MatrixModel.PROPOSED, pMappingId, pLabel, steps, Integer.valueOf(90), "LLM", "because", null, 0, null, null, pAttention.isEmpty() ? "" : "CONSTANT_AMBIGUOUS",
			pAttention, null, null, pField, pFillRate, pPipeline);
	}

	private static Guard postalRule()
	{
		return new Guard("g1", ReviewLine.QUALITY, "e2", "b3", "PATTERN", "^[0-9]{5}$", "WARN", null, ReviewLine.PROPOSED, "LLM", Integer.valueOf(90), "five digits", null, null, null);
	}

	/**
	 * Adresse, fed by Salesforce (one MailingStreet cut by two regexes, a constant, the postal code and city) and SAP (separate columns, a constant the matrix flags, a city that needs the
	 * load) and by a third feed that reads a sub-element of the row. Fields: houseNumber, street, postalCode (with a PATTERN rule), addressType, city, the parent link {@code personne}
	 * and the matching key {@code cityKey}.
	 */
	static MatrixModel.Project project()
	{
		final List<MatrixModel.Source> sources = Arrays.asList(new MatrixModel.Source("s1", "SAP", "#2f6fd0"), new MatrixModel.Source("s2", "Salesforce", "#0e9384"),
			new MatrixModel.Source("s3", "Legacy", "#999999"));
		final MatrixModel.Attr key = new MatrixModel.Attr("b0", "sourceId", "STRING", true, false, true);
		final MatrixModel.Attr personne = new MatrixModel.Attr("b9", "personne", "STRING", true, true, false, ReviewLine.PROPOSED, Integer.valueOf(100), null, "", "", null, "", "Personne");
		final MatrixModel.Attr number = new MatrixModel.Attr("b1", "houseNumber", "STRING", false, false, false, ReviewLine.PROPOSED, Integer.valueOf(92));
		final MatrixModel.Attr street = new MatrixModel.Attr("b2", "street", "STRING", true, false, false, ReviewLine.PROPOSED, Integer.valueOf(92));
		final MatrixModel.Attr postal = new MatrixModel.Attr("b3", "postalCode", "STRING", false, false, false, ReviewLine.PROPOSED, Integer.valueOf(88),
			Arrays.asList(DeskPreviewTest.postalRule()));
		final MatrixModel.Attr type = new MatrixModel.Attr("b4", "addressType", "STRING", false, false, false, ReviewLine.PROPOSED, Integer.valueOf(91));
		final MatrixModel.Attr city = new MatrixModel.Attr("b5", "city", "STRING", false, false, false, ReviewLine.PROPOSED, Integer.valueOf(90));
		final MatrixModel.Attr cityKey = new MatrixModel.Attr("b6", "cityKey", "STRING", false, false, false, ReviewLine.PROPOSED, Integer.valueOf(90), null, "FOLD_KEY", "city");

		final Map<String, MatrixModel.Cell> sf = new LinkedHashMap<String, MatrixModel.Cell>();
		sf.put("b9", new MatrixModel.Cell(MatrixModel.ENGINE, null, null, null, null, null, null, null, 0, null, null));
		sf.put("b1", DeskPreviewTest.cell("m21", "MailingStreet", "MAILING_STREET", "regex('^([0-9]+) ', 1)", Double.valueOf(100), ""));
		sf.put("b2", DeskPreviewTest.cell("m22", "MailingStreet", "MAILING_STREET", "regex('^[0-9]+ (.*)$', 1)", Double.valueOf(100), ""));
		sf.put("b3", DeskPreviewTest.cell("m23", "MailingPostalCode", "MAILING_POSTAL", "trim()", Double.valueOf(90), ""));
		sf.put("b4", DeskPreviewTest.cell("m24", "constant", "MAILING_STREET", "constant('MAILING')", Double.valueOf(90), ""));
		sf.put("b5", DeskPreviewTest.cell("m25", "MailingCity", "MAILING_CITY", "", Double.valueOf(97), ""));
		final Map<String, MatrixModel.Cell> sap = new LinkedHashMap<String, MatrixModel.Cell>();
		sap.put("b1", DeskPreviewTest.cell("m11", "HouseNumber", "HOUSE_NO", "", Double.valueOf(94), ""));
		sap.put("b2", DeskPreviewTest.cell("m12", "StreetName", "STREET_NAME", "", Double.valueOf(94), ""));
		sap.put("b3", DeskPreviewTest.cell("m13", "PostalCode", "POSTAL", "trim", Double.valueOf(94), ""));
		sap.put("b4", DeskPreviewTest.cell("m14", "constant", "STREET_NAME", "constant('MAILING')", Double.valueOf(94), "the reason says residential but the constant says mailing"));
		sap.put("b5", DeskPreviewTest.cell("m15", "CityName", "CITY_NAME", "fk('City')", Double.valueOf(94), ""));
		final Map<String, MatrixModel.Cell> legacy = new LinkedHashMap<String, MatrixModel.Cell>();
		legacy.put("b2", DeskPreviewTest.cell("m31", "Streets", "STREETS", "", null, ""));

		final MatrixModel.Feed feedSf = new MatrixModel.Feed("fA2", "s2", "t3", "Contact", ReviewLine.PROPOSED, null, "", false, null, sf, Integer.valueOf(90), "", "", "", "BDemo_Landing",
			"SALESFORCE", Long.valueOf(100));
		final MatrixModel.Feed feedSap = new MatrixModel.Feed("fA1", "s1", "t2", "ADRC", ReviewLine.PROPOSED, null, "", false, null, sap, Integer.valueOf(90), "", "", "", "BDemo_Landing", "SAP",
			Long.valueOf(165));
		final MatrixModel.Feed feedLegacy = new MatrixModel.Feed("fA3", "s3", "t4", "Customer", ReviewLine.PROPOSED, null, "", false, null, legacy, Integer.valueOf(80), "", "", "addresses",
			"BDemo_Landing", "LEGACY", Long.valueOf(40));
		final MatrixModel.Entity adresse = new MatrixModel.Entity("e2", "Adresse", Arrays.asList(key, personne, number, street, postal, type, city, cityKey),
			Arrays.asList(feedSf, feedSap, feedLegacy), ReviewLine.ACCEPTED, Integer.valueOf(100));
		return new MatrixModel.Project("DemoBala", sources, new ArrayList<MatrixModel.Entity>(Arrays.asList(adresse)));
	}

	private static DeskPreview.FeedRows salesforceRows()
	{
		final DeskPreview.FeedRows rows = new DeskPreview.FeedRows();
		rows.documents.add(DeskPreviewTest.row("MAILING_STREET", "66 rue de la Republique", "MAILING_POSTAL", "49000", "MAILING_CITY", "Angers"));
		rows.documents.add(DeskPreviewTest.row("MAILING_STREET", "148 impasse des Lilas", "MAILING_POSTAL", "111 20", "MAILING_CITY", "Stockholm"));
		rows.documents.add(DeskPreviewTest.row("MAILING_STREET", "rue sans numero", "MAILING_POSTAL", null, "MAILING_CITY", "Lyon"));
		rows.more = true;
		return rows;
	}

	private static DeskPreview.FeedRows sapRows()
	{
		final DeskPreview.FeedRows rows = new DeskPreview.FeedRows();
		rows.documents.add(DeskPreviewTest.row("HOUSE_NO", "24", "STREET_NAME", "avenue Victor Hugo", "POSTAL", "33000", "CITY_NAME", "Bordeaux"));
		rows.documents.add(DeskPreviewTest.row("HOUSE_NO", "133", "STREET_NAME", "rue Pasteur", "POSTAL", "31000", "CITY_NAME", "Toulouse"));
		rows.offset = 5;
		return rows;
	}

	/** The preview of the fixture, as the reader would hand the rows over. */
	static DeskPreview preview()
	{
		final Map<String, DeskPreview.FeedRows> rows = new HashMap<String, DeskPreview.FeedRows>();
		rows.put("fA2", DeskPreviewTest.salesforceRows());
		rows.put("fA1", DeskPreviewTest.sapRows());
		final MatrixModel.Project project = DeskPreviewTest.project();
		return DeskPreview.build(project, project.entityById("e2"), rows, null, null);
	}

	// ----------------------------------------------------------------------------------------------------------------- tests

	private static List<String> texts(final DeskPreview.Block pBlock, final int pColumn)
	{
		final List<String> out = new ArrayList<String>();
		for (final List<DeskPreview.Cell> row : pBlock.rows)
		{
			out.add(row.get(pColumn).kind + ":" + row.get(pColumn).text);
		}
		return out;
	}

	private static int column(final DeskPreview.Block pBlock, final String pName)
	{
		for (int i = 0; i < pBlock.columns.size(); i++)
		{
			if (pBlock.columns.get(i).name.equals(pName))
			{
				return i;
			}
		}
		throw new AssertionError("no column " + pName);
	}

	private static void valuesAreWhatTheLoaderWouldStage()
	{
		final DeskPreview preview = DeskPreviewTest.preview();
		check("one block per feed, in the order of the object", preview.blocks.size() == 3 && "Salesforce".equals(preview.blocks.get(0).sourceName) && "SAP".equals(preview.blocks.get(1).sourceName));
		final DeskPreview.Block sf = preview.block("fA2");
		check("the table and the row count", "Contact".equals(sf.tableName) && sf.rowCount.longValue() == 100L);
		check("the same three rows for every column", sf.rows.size() == 3 && sf.sourceCells.size() == 3);
		check("a regex cuts the number: " + DeskPreviewTest.texts(sf, DeskPreviewTest.column(sf, "houseNumber")),
			Arrays.asList("VALUE:66", "VALUE:148", "EMPTY:").equals(DeskPreviewTest.texts(sf, DeskPreviewTest.column(sf, "houseNumber"))));
		check("a regex cuts the street, a missing match is an empty cell: " + DeskPreviewTest.texts(sf, DeskPreviewTest.column(sf, "street")),
			Arrays.asList("VALUE:rue de la Republique", "VALUE:impasse des Lilas", "EMPTY:").equals(DeskPreviewTest.texts(sf, DeskPreviewTest.column(sf, "street"))));
		check("a constant is the same on every row", Arrays.asList("VALUE:MAILING", "VALUE:MAILING", "VALUE:MAILING").equals(DeskPreviewTest.texts(sf, DeskPreviewTest.column(sf, "addressType"))));
		check("a plain copy, a missing source value stays empty", Arrays.asList("VALUE:49000", "VALUE:111 20", "EMPTY:").equals(DeskPreviewTest.texts(sf, DeskPreviewTest.column(sf, "postalCode"))));
		check("the how line: the landing column, or the pipeline", "MailingPostalCode".equals(sf.columns.get(DeskPreviewTest.column(sf, "postalCode")).how)
			&& "constant('MAILING')".equals(sf.columns.get(DeskPreviewTest.column(sf, "addressType")).how));
		check("a technical key is not a column", DeskPreviewTest.columnNames(sf).indexOf("sourceId") < 0);
		check("the page offset and the next page", sf.offset == 0 && sf.more && preview.block("fA1").offset == 5 && !preview.block("fA1").more);
	}

	private static List<String> columnNames(final DeskPreview.Block pBlock)
	{
		final List<String> out = new ArrayList<String>();
		for (final DeskPreview.Column column : pBlock.columns)
		{
			out.add(column.name);
		}
		return out;
	}

	private static void theSourceColumnNamesWhatIsTransformed()
	{
		final DeskPreview preview = DeskPreviewTest.preview();
		final DeskPreview.Block sf = preview.block("fA2");
		check("Salesforce: the one column two regexes cut (the constant reads nothing)", "MailingStreet".equals(sf.sourceHeader));
		check("its raw values", "66 rue de la Republique".equals(sf.sourceCells.get(0)) && "rue sans numero".equals(sf.sourceCells.get(2)));
		final DeskPreview.Block sap = preview.block("fA1");
		check("SAP: nothing is transformed, the first two columns read: " + sap.sourceHeader, "HouseNumber · StreetName".equals(sap.sourceHeader));
		check("their values joined", "24 · avenue Victor Hugo".equals(sap.sourceCells.get(0)));
	}

	private static void theParentLinkAndTheComputedFieldsAreNotValues()
	{
		final DeskPreview.Block sf = DeskPreviewTest.preview().block("fA2");
		final int parent = DeskPreviewTest.column(sf, "personne");
		check("the parent link is a chip \"its Personne\", written by the engine", DeskPreview.PARENT.equals(sf.columns.get(parent).kind) && "Personne".equals(sf.columns.get(parent).how)
			&& DeskPreview.ENGINE.equals(sf.rows.get(0).get(parent).kind));
		final int key = DeskPreviewTest.column(sf, "cityKey");
		check("a matching key is computed at load", DeskPreview.COMPUTED.equals(sf.columns.get(key).kind) && DeskPreview.RUNTIME.equals(sf.rows.get(1).get(key).kind));
		final DeskPreview.Block sap = DeskPreviewTest.preview().block("fA1");
		final int city = DeskPreviewTest.column(sap, "city");
		check("a pipeline that needs the load shows no value", DeskPreview.RUNTIME.equals(sap.rows.get(0).get(city).kind) && sap.rows.get(0).get(city).text.isEmpty());
		check("the parent link is absent from a feed that does not carry it", DeskPreviewTest.columnNames(sap).indexOf("personne") < 0);
	}

	private static void aRuleOfTheFieldFlagsTheValueItWouldRejectOrWarnOn()
	{
		final DeskPreview preview = DeskPreviewTest.preview();
		final DeskPreview.Block sf = preview.block("fA2");
		final int postal = DeskPreviewTest.column(sf, "postalCode");
		check("49000 passes", !sf.rows.get(0).get(postal).ruleFails());
		final DeskPreview.Cell bad = sf.rows.get(1).get(postal);
		check("111 20 would warn, the rule is named: " + bad.ruleLabel, bad.ruleFails() && bad.ruleLabel.startsWith("PATTERN") && "WARN".equals(bad.ruleSeverity));
		check("an empty value never fails a rule", !sf.rows.get(2).get(postal).ruleFails());
		check("the column is flagged and its link keys the flag", sf.columns.get(postal).flagged() && preview.keys().flaggedMappingIds.contains("m23"));
		check("the same field fed by SAP passes on its rows", !preview.block("fA1").columns.get(DeskPreviewTest.column(preview.block("fA1"), "postalCode")).flagged());
		// a REJECT rule, and a REFERENCE rule that is not evaluated without the reference data
		final MatrixModel.Project project = DeskPreviewTest.project();
		final MatrixModel.Entity entity = project.entityById("e2");
		final Guard reject = new Guard("g2", ReviewLine.QUALITY, "e2", "b4", "PATTERN", "^SHIP$", "REJECT", null, ReviewLine.ACCEPTED, "LLM", Integer.valueOf(90), "", null, null, null);
		final Guard reference = new Guard("g3", ReviewLine.QUALITY, "e2", "b5", "REFERENCE", "country", "WARN", null, ReviewLine.PROPOSED, "LLM", Integer.valueOf(90), "", null, null, null);
		final Guard modelConstraint = new Guard("g4", ReviewLine.QUALITY, "e2", "b2", "PATTERN", "^X$", "MODEL_CONSTRAINT", null, ReviewLine.PROPOSED, "LLM", Integer.valueOf(90), "", null, null, null);
		final List<MatrixModel.Attr> attrs = new ArrayList<MatrixModel.Attr>();
		for (final MatrixModel.Attr attr : entity.attrs)
		{
			final List<Guard> guards = "b4".equals(attr.id) ? Arrays.asList(reject) : "b5".equals(attr.id) ? Arrays.asList(reference) : "b2".equals(attr.id) ? Arrays.asList(modelConstraint) : attr.guards;
			attrs.add(new MatrixModel.Attr(attr.id, attr.name, attr.dataType, attr.mandatory, attr.parentLink, attr.sourceKey, attr.status, attr.confidence, guards, attr.derivation,
				attr.derivedFrom, null, "", attr.parentEntityName));
		}
		final MatrixModel.Entity changed = new MatrixModel.Entity("e2", "Adresse", attrs, entity.feeds, entity.status, entity.confidence);
		final Map<String, DeskPreview.FeedRows> rows = new HashMap<String, DeskPreview.FeedRows>();
		rows.put("fA2", DeskPreviewTest.salesforceRows());
		final DeskPreview.Block block = DeskPreview.build(project, changed, rows, null, null).block("fA2");
		final DeskPreview.Cell rejected = block.rows.get(0).get(DeskPreviewTest.column(block, "addressType"));
		check("an accepted REJECT rule says it would reject", "REJECT".equals(rejected.ruleSeverity));
		check("a REFERENCE rule without the reference data is not evaluated", !block.rows.get(0).get(DeskPreviewTest.column(block, "city")).ruleFails());
		check("a model constraint is not a per-value check", !block.rows.get(0).get(DeskPreviewTest.column(block, "street")).ruleFails());
	}

	/** Lot L4: "only when city is Stockholm" - a row of another city is not flagged by the rule, and neither is a row when the condition field is not shown. */
	private static void aRuleWithAConditionOnlyFlagsTheRowsThatMeetIt()
	{
		final MatrixModel.Project project = DeskPreviewTest.project();
		final Map<String, DeskPreview.FeedRows> rows = new HashMap<String, DeskPreview.FeedRows>();
		rows.put("fA2", DeskPreviewTest.salesforceRows());
		final Map<String, DeskPreview.RuleCondition> inStockholm = new HashMap<String, DeskPreview.RuleCondition>();
		inStockholm.put("g1", new DeskPreview.RuleCondition("city", Arrays.asList("stockholm")));
		DeskPreview.Block block = DeskPreview.build(project, project.entityById("e2"), rows, null, null, inStockholm).block("fA2");
		int postal = DeskPreviewTest.column(block, "postalCode");
		check("the row of the condition is flagged (case ignored)", block.rows.get(1).get(postal).ruleFails());
		final Map<String, DeskPreview.RuleCondition> inAngers = new HashMap<String, DeskPreview.RuleCondition>();
		inAngers.put("g1", new DeskPreview.RuleCondition("city", Arrays.asList("Angers")));
		block = DeskPreview.build(project, project.entityById("e2"), rows, null, null, inAngers).block("fA2");
		check("a row outside the condition is not flagged by the rule", !block.rows.get(1).get(postal).ruleFails() && !block.columns.get(postal).flagged());
		check("and the link is not in the flagged keys", !DeskPreview.build(project, project.entityById("e2"), rows, null, null, inAngers).keys().flaggedMappingIds.contains("m23"));
		final Map<String, DeskPreview.RuleCondition> unknown = new HashMap<String, DeskPreview.RuleCondition>();
		unknown.put("g1", new DeskPreview.RuleCondition("countryCode", Arrays.asList("FR")));
		block = DeskPreview.build(project, project.entityById("e2"), rows, null, null, unknown).block("fA2");
		check("a condition field the block does not show cannot be met: no flag", !block.rows.get(1).get(postal).ruleFails());
		block = DeskPreview.build(project, project.entityById("e2"), rows, null, null, null).block("fA2");
		check("without conditions nothing changed", block.rows.get(1).get(postal).ruleFails());
	}

	private static void theMatrixAttentionFlagsTheWholeColumn()
	{
		final DeskPreview preview = DeskPreviewTest.preview();
		final DeskPreview.Block sap = preview.block("fA1");
		final DeskPreview.Column type = sap.columns.get(DeskPreviewTest.column(sap, "addressType"));
		check("the matrix' reason is carried as it is", "the reason says residential but the constant says mailing".equals(type.attention) && type.flagged());
		check("its link is flagged", preview.keys().flaggedMappingIds.contains("m14") && !preview.keys().flaggedMappingIds.contains("m24"));
	}

	private static void theFillLineSaysHowManyRowsLackTheField()
	{
		final DeskPreview.Block sf = DeskPreviewTest.preview().block("fA2");
		check("two lines, the larger gap first", sf.fillLines.size() == 2 && sf.fillLines.get(0).missing == 10L && sf.fillLines.get(1).missing == 3L);
		check("10 of 100 lack the postal code, 3 of 100 the city", sf.fillLines.get(0).total == 100L && sf.fillLines.get(0).fields.equals(Arrays.asList("postalCode"))
			&& sf.fillLines.get(1).fields.equals(Arrays.asList("city")));
		// live fix 2026-10-22: SAP's addressType is constant('MAILING') (reads StreetName but never depends on it) and its city is a look-up at the load: neither is blamed
		final DeskPreview.Block sap = DeskPreviewTest.preview().block("fA1");
		check("a constant and a look-up are never in the fill line", sap.fillLines.size() == 1 && sap.fillLines.get(0).missing == 10L && sap.fillLines.get(0).total == 165L
			&& sap.fillLines.get(0).fields.equals(Arrays.asList("houseNumber", "street", "postalCode")));
		check("the constant of the other source (filled in 90 %) is not blamed either", !sf.fillLines.get(0).fields.contains("addressType") && !sf.fillLines.get(1).fields.contains("addressType"));
	}

	private static void theHowLineNamesTheLandingColumnForACopy()
	{
		final DeskPreview.Block sf = DeskPreviewTest.preview().block("fA2");
		final DeskPreview.Block sap = DeskPreviewTest.preview().block("fA1");
		check("a straight copy with the default trim() shows the landing column, not trim()", "MailingPostalCode".equals(sf.columns.get(DeskPreviewTest.column(sf, "postalCode")).how));
		check("trim without brackets is the default too", "PostalCode".equals(sap.columns.get(DeskPreviewTest.column(sap, "postalCode")).how));
		check("no step at all is a copy", "MailingCity".equals(sf.columns.get(DeskPreviewTest.column(sf, "city")).how)
			&& "StreetName".equals(sap.columns.get(DeskPreviewTest.column(sap, "street")).how));
		check("a real transformation shows column and pipeline", "MailingStreet \u00B7 regex('^([0-9]+) ', 1)".equals(sf.columns.get(DeskPreviewTest.column(sf, "houseNumber")).how));
		check("a constant shows the constant alone", "constant('MAILING')".equals(sf.columns.get(DeskPreviewTest.column(sf, "addressType")).how));
		check("a look-up keeps the column and the look-up", "CityName \u00B7 fk('City')".equals(sap.columns.get(DeskPreviewTest.column(sap, "city")).how));
		final MatrixModel.Cell trimThenRegex = new MatrixModel.Cell(MatrixModel.PROPOSED, "mx", "Street", Arrays.asList("trim()", "upper()"), Integer.valueOf(90), "LLM", "because", null, 0, null,
			null, "", "", null, null, "STREET", Double.valueOf(100), "trim() | upper()");
		check("trim() followed by a real step is a transformation", DeskPreview.how(trimThenRegex).startsWith("Street \u00B7 trim()"));
	}

	private static void aFeedThatCannotBeShownFallsBackWithTheMatrixWording()
	{
		final DeskPreview preview = DeskPreviewTest.preview();
		final DeskPreview.Block legacy = preview.block("fA3");
		check("a collection feed falls back, with the matrix' own words", legacy.isFallback() && RowTransform.REASON_COLLECTION.equals(legacy.fallback) && legacy.columns.isEmpty());
		check("its links are known to the fallback list", legacy.mappingIds.contains("m31"));
		check("it is not a shown feed", !preview.keys().shownFeedIds.contains("fA3") && preview.keys().shownFeedIds.contains("fA2") && preview.keys().shownFeedIds.contains("fA1"));
		final MatrixModel.Project project = DeskPreviewTest.project();
		final Map<String, DeskPreview.FeedRows> rows = new HashMap<String, DeskPreview.FeedRows>();
		final DeskPreview.FeedRows missing = new DeskPreview.FeedRows();
		missing.unavailable = RowTransform.REASON_DATASET_NOT_FOUND;
		rows.put("fA2", missing);
		rows.put("fA1", new DeskPreview.FeedRows()); // read, but the table is empty
		final DeskPreview other = DeskPreview.build(project, project.entityById("e2"), rows, null, null);
		check("a landing dataset that cannot be found", RowTransform.REASON_DATASET_NOT_FOUND.equals(other.block("fA2").fallback));
		check("an empty landing table", RowTransform.REASON_EMPTY_TABLE.equals(other.block("fA1").fallback));
		check("no read at all", RowTransform.REASON_NO_LANDING.equals(DeskPreview.build(project, project.entityById("e2"), null, null, null).block("fA2").fallback));
		check("nothing shown, nothing to take", other.keys().shownFeedIds.isEmpty() && other.nothingToTake);
	}

	private static void theKeysAndTheCountsOfTheAcceptButton()
	{
		final DeskPreview preview = DeskPreviewTest.preview();
		final MatrixModel.Entity entity = DeskPreviewTest.project().entityById("e2");
		final MatrixCompute.BulkPlan plan = MatrixCompute.planEntityAsShown(entity, preview.keys());
		check("the plan is the one the button runs: 5 fields", preview.takenFields == 5);
		check("8 of the 10 shown links, 2 flagged ones wait", preview.takenLinks == 8 && preview.totalLinks == 10 && preview.held == 2 && plan.held == 2);
		check("the flagged links are not in the plan", !DeskPreviewTest.planned(plan, ReviewLine.MAPPING, "m23") && !DeskPreviewTest.planned(plan, ReviewLine.MAPPING, "m14")
			&& DeskPreviewTest.planned(plan, ReviewLine.MAPPING, "m24"));
		check("the feed that fell back is not accepted", !DeskPreviewTest.planned(plan, ReviewLine.FEED, "fA3") && DeskPreviewTest.planned(plan, ReviewLine.FEED, "fA2")
			&& !DeskPreviewTest.planned(plan, ReviewLine.MAPPING, "m31"));
		check("the fields shown on columns are the ones a column can be rejected by", preview.fields.size() == 5 && "b1".equals(preview.fields.get(0).id));
		check("the nothing-to-take flag", !preview.nothingToTake);
	}

	static boolean planned(final MatrixCompute.BulkPlan pPlan, final String pNature, final String pId)
	{
		for (final MatrixCompute.PlannedDecision decision : pPlan.toDecide)
		{
			if (decision.nature.equals(pNature) && decision.id.equals(pId))
			{
				return true;
			}
		}
		return false;
	}

	private static void aCleaningStepRunsBeforeThePipelineAndLookupsStayOutOfTheMatrixSample()
	{
		final MatrixModel.Project project = DeskPreviewTest.project();
		final DeskPreview.FeedRows rows = DeskPreviewTest.sapRows();
		rows.stepsByMappingId.put("m12", Arrays.asList(new CleaningSteps.Step(CleaningSteps.UPPER, null)));
		final Map<String, DeskPreview.FeedRows> byFeed = new HashMap<String, DeskPreview.FeedRows>();
		byFeed.put("fA1", rows);
		final DeskPreview.Block sap = DeskPreview.build(project, project.entityById("e2"), byFeed, CleaningSteps.Lookups.empty(), null).block("fA1");
		check("UPPER ran on the street before the (plain) pipeline: " + DeskPreviewTest.texts(sap, DeskPreviewTest.column(sap, "street")),
			Arrays.asList("VALUE:AVENUE VICTOR HUGO", "VALUE:RUE PASTEUR").equals(DeskPreviewTest.texts(sap, DeskPreviewTest.column(sap, "street"))));
		check("the shared transformation is the matrix' one: same method, same answer", "AVENUE VICTOR HUGO".equals(RowTransform.apply(DeskPreviewTest.row("S", "avenue Victor Hugo"), "S",
			Arrays.asList(new CleaningSteps.Step(CleaningSteps.UPPER, null)), null, "", CleaningSteps.Lookups.empty(), null).after));
		check("a pipeline error comes back as an error, never a throw", RowTransform.apply(DeskPreviewTest.row("S", "x"), "S", null, null, "noSuchStep()", null, null).failed());
		check("the runtime-lookup test is the matrix' one", RowTransform.needsRuntimeLookup("trim|fk('City')") && RowTransform.needsRuntimeLookup("LOOKUP(x)") && !RowTransform.needsRuntimeLookup("trim"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
