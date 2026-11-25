package com.on.ebx.factory.mappingmatrix;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.review.ReviewLine;

/**
 * The PersVol9-like project of the matrix review queue lot (2026-09-30): 3 sources, 2 business objects
 * (Personne with 2 feeds, Adresse with 1), and the eight items the footer used to leave people looking for after
 * "Accept the unflagged proposals" - three matching keys, one script-computed field with its SCRIPT_DERIVATION
 * suggestion, and three checks below the 60 % bar. Shared by {@link MatrixComputeTest} and {@link MatrixHtmlTest};
 * run with a file path it also writes the static page ({@code java ... MatrixQueueFixtureTest <out.html>}, the script
 * inlined) to open in a browser.
 */
public final class MatrixQueueFixtureTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final MatrixModel.Project project = MatrixQueueFixtureTest.persVol9Like(true, false);
		final List<MatrixCompute.StillItem> items = MatrixCompute.stillToDecide(project, 60);
		if (items.size() != 5)
		{
			throw new AssertionError("the PersVol9-like fixture must leave exactly 5 items (the computed field and its derivation are one; the two look-up suggestions are optional and listed apart), found " + items.size());
		}
		if (pArgs.length > 0)
		{
			MatrixQueueFixtureTest.writePage(project, new File(pArgs[0]), pArgs.length > 1 ? pArgs[1] : "", pArgs.length > 2 ? pArgs[2] : "");
		}
		System.out.println("MatrixQueueFixtureTest OK");
	}

	/** Writes the page with its script inlined; {@code pMode} is the panel state ("" closed, "open", "all"), {@code pSelected} an optional selection key. */
	static void writePage(final MatrixModel.Project pProject, final File pOut, final String pMode, final String pSelected) throws IOException
	{
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		state.queueMode = pMode;
		state.selectedCellKey = pSelected.isEmpty() ? null : pSelected;
		state.wizardUrl = "#wizard";
		final String html = MatrixHtml.render(pProject, state);
		final String page = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Mapping matrix - review queue</title>"
			+ "<style>body{margin:0;padding:16px;font-family:system-ui,sans-serif}</style></head><body>" + "<form onsubmit=\"return false\">" + html
			+ "<button type=\"button\" id=\"mdmmSubmit\" style=\"display:none\" onclick=\"window.mdmmLastAction=document.getElementById('mdmmAction').value+'|'+document.getElementById('mdmmRowId').value+'|'+document.getElementById('mdmmRowId2').value+'|'+document.getElementById('mdmmSelected').value+'|'+document.getElementById('mdmmEntity').value;document.title=window.mdmmLastAction;\"></button></form>"
			+ "<script>" + MatrixHtml.script(Locale.ENGLISH) + "</script></body></html>";
		Files.write(pOut.toPath(), page.getBytes(Charset.forName("UTF-8")));
	}

	private static MatrixModel.Cell cell(final String pStatus, final String pMappingId, final String pColumn, final int pConfidence)
	{
		return new MatrixModel.Cell(pStatus, pMappingId, pColumn, null, Integer.valueOf(pConfidence), "LLM", "because", null, 0, null,
			MatrixModel.Sample.of(pColumn.toLowerCase(Locale.ENGLISH) + " value", pColumn.toLowerCase(Locale.ENGLISH) + " value"));
	}

	private static MatrixModel.Cell keyCell(final String pColumn)
	{
		return new MatrixModel.Cell(MatrixModel.KEY, null, pColumn, null, null, null, null, null, 0, "the source key", null);
	}

	private static Guard guard(final String pId, final String pNature, final String pEntityId, final String pAttributeId, final String pKind, final String pStatus,
		final int pConfidence)
	{
		return new Guard(pId, pNature, pEntityId, pAttributeId, pKind, "", ReviewLine.QUALITY.equals(pNature) ? "WARN" : null, null, pStatus, "LLM",
			Integer.valueOf(pConfidence), "suggested by the model", null, null, null);
	}

	/**
	 * @param pOrdinaryAccepted the ordinary fields, links, feeds and rules are already ACCEPTED (what is left after the bulk button, under the old rules)
	 * @param pKeysAccepted the three matching keys are ACCEPTED too (what is left under the new rules)
	 */
	public static MatrixModel.Project persVol9Like(final boolean pOrdinaryAccepted, final boolean pKeysAccepted)
	{
		final String ord = pOrdinaryAccepted ? ReviewLine.ACCEPTED : ReviewLine.PROPOSED;
		final String key = pKeysAccepted ? ReviewLine.ACCEPTED : ReviewLine.PROPOSED;
		final String entityStatus = pOrdinaryAccepted ? ReviewLine.ACCEPTED : ReviewLine.PROPOSED;
		final List<MatrixModel.Source> sources = Arrays.asList(new MatrixModel.Source("s1", "SAP", "#2f6fd0"), new MatrixModel.Source("s2", "ECOM", "#0e9384"),
			new MatrixModel.Source("s3", "CRM", "#c77700"));

		final MatrixModel.Attr sourceId = new MatrixModel.Attr("a0", "sourceId", "STRING", true, false, true);
		final MatrixModel.Attr firstName = new MatrixModel.Attr("a1", "firstName", "STRING", true, false, false, ord, Integer.valueOf(92),
			Arrays.asList(MatrixQueueFixtureTest.guard("g1", ReviewLine.EXTERNAL_SERVICE, "e1", "a1", "gender.lookup", ReviewLine.PROPOSED, 35)));
		final MatrixModel.Attr lastName = new MatrixModel.Attr("a2", "lastName", "STRING", true, false, false, ord, Integer.valueOf(95));
		final MatrixModel.Attr email = new MatrixModel.Attr("a3", "email", "STRING", false, false, false, ord, Integer.valueOf(90),
			Arrays.asList(MatrixQueueFixtureTest.guard("g2", ReviewLine.QUALITY, "e1", "a3", "PATTERN", ord, 90)));
		final MatrixModel.Attr phone = new MatrixModel.Attr("a4", "phone", "STRING", false, false, false, ord, Integer.valueOf(88));
		final MatrixModel.Attr firstNameKey = new MatrixModel.Attr("a5", "firstNameKey", "STRING", false, false, false, key, Integer.valueOf(100), null, "FOLD_KEY", "firstName");
		final MatrixModel.Attr lastNameKey = new MatrixModel.Attr("a6", "lastNameKey", "STRING", false, false, false, key, Integer.valueOf(100), null, "FOLD_KEY", "lastName");
		final MatrixModel.Attr emailKey = new MatrixModel.Attr("a7", "emailKey", "STRING", false, false, false, key, Integer.valueOf(100), null, "FOLD_KEY", "email");
		final MatrixModel.Attr phoneE164 = new MatrixModel.Attr("a8", "phoneE164", "STRING", false, false, false, ReviewLine.PROPOSED, Integer.valueOf(80),
			Arrays.asList(MatrixQueueFixtureTest.guard("g3", ReviewLine.SCRIPT_DERIVATION, "e1", "a8", "toE164", ReviewLine.PROPOSED, 85)), "SCRIPT", "phone");

		final Map<String, MatrixModel.Cell> sapCells = new LinkedHashMap<String, MatrixModel.Cell>();
		sapCells.put("a0", MatrixQueueFixtureTest.keyCell("PARTNER"));
		sapCells.put("a1", MatrixQueueFixtureTest.cell(ord, "m1", "FIRST_NAME", 92));
		sapCells.put("a2", MatrixQueueFixtureTest.cell(ord, "m2", "LAST_NAME", 95));
		sapCells.put("a3", MatrixQueueFixtureTest.cell(ord, "m3", "E_MAIL", 90));
		sapCells.put("a4", MatrixQueueFixtureTest.cell(ord, "m4", "TEL_NUMBER", 88));
		final Map<String, MatrixModel.Cell> ecomCells = new LinkedHashMap<String, MatrixModel.Cell>();
		ecomCells.put("a0", MatrixQueueFixtureTest.keyCell("customer_id"));
		ecomCells.put("a1", MatrixQueueFixtureTest.cell(ord, "m5", "first_name", 90));
		ecomCells.put("a2", MatrixQueueFixtureTest.cell(ord, "m6", "last_name", 91));
		ecomCells.put("a3", MatrixQueueFixtureTest.cell(ord, "m7", "email", 93));
		ecomCells.put("a4", MatrixQueueFixtureTest.cell(ord, "m8", "mobile", 86));
		final MatrixModel.Feed sap = new MatrixModel.Feed("f1", "s1", "t1", "BUT000", ord, null, "", false, null, sapCells, Integer.valueOf(95), "");
		final MatrixModel.Feed ecom = new MatrixModel.Feed("f2", "s2", "t2", "customers", ord, null, "", false, null, ecomCells, Integer.valueOf(94), "");
		final MatrixModel.Entity personne = new MatrixModel.Entity("e1", "Personne",
			Arrays.asList(sourceId, firstName, lastName, email, phone, firstNameKey, lastNameKey, emailKey, phoneE164), Arrays.asList(sap, ecom), entityStatus,
			Integer.valueOf(100));

		final MatrixModel.Attr addrKey = new MatrixModel.Attr("a9", "sourceId", "STRING", true, false, true);
		final MatrixModel.Attr street = new MatrixModel.Attr("a10", "street", "STRING", false, false, false, ord, Integer.valueOf(90));
		final MatrixModel.Attr city = new MatrixModel.Attr("a11", "city", "STRING", false, false, false, ord, Integer.valueOf(90));
		final MatrixModel.Attr postalCode = new MatrixModel.Attr("a12", "postalCode", "STRING", false, false, false, ord, Integer.valueOf(90),
			Arrays.asList(MatrixQueueFixtureTest.guard("g4", ReviewLine.EXTERNAL_SERVICE, "e2", "a12", "postal.validate", ReviewLine.PROPOSED, 50)));
		final MatrixModel.Attr validFrom = new MatrixModel.Attr("a13", "validFrom", "DATE", false, false, false, ord, Integer.valueOf(90),
			Arrays.asList(MatrixQueueFixtureTest.guard("g5", ReviewLine.SCRIPT_CONSTRAINT, "e2", "a13", "isValid", ReviewLine.PROPOSED, 15)));
		final MatrixModel.Attr country = new MatrixModel.Attr("a14", "country", "STRING", false, false, false, ord, Integer.valueOf(90));
		final Map<String, MatrixModel.Cell> adrcCells = new LinkedHashMap<String, MatrixModel.Cell>();
		adrcCells.put("a9", MatrixQueueFixtureTest.keyCell("ADDRNUMBER"));
		adrcCells.put("a10", MatrixQueueFixtureTest.cell(ord, "m9", "STREET", 90));
		adrcCells.put("a11", MatrixQueueFixtureTest.cell(ord, "m10", "CITY1", 90));
		adrcCells.put("a12", MatrixQueueFixtureTest.cell(ord, "m11", "POST_CODE1", 90));
		adrcCells.put("a13", MatrixQueueFixtureTest.cell(ord, "m12", "VALID_FROM", 90));
		adrcCells.put("a14", MatrixQueueFixtureTest.cell(ord, "m13", "COUNTRY", 90));
		final MatrixModel.Feed adrc = new MatrixModel.Feed("f3", "s1", "t3", "ADRC", ord, null, "", false, null, adrcCells, Integer.valueOf(92), "");
		final MatrixModel.Entity adresse = new MatrixModel.Entity("e2", "Adresse", Arrays.asList(addrKey, street, city, postalCode, validFrom, country), Arrays.asList(adrc),
			entityStatus, Integer.valueOf(100));
		return new MatrixModel.Project("PersVol9", sources, new ArrayList<MatrixModel.Entity>(Arrays.asList(personne, adresse)));
	}
}
