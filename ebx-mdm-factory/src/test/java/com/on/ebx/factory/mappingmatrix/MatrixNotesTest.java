package com.on.ebx.factory.mappingmatrix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.staging.CheckNote;
import com.on.ebx.factory.staging.NoteTexts;
import com.on.ebx.factory.staging.ProposalChecks;

/**
 * Lot G2 (2026-10-17): the reason a proposal was held back, read from its own text, is what the matrix card shows first - and a proposal that carries a
 * "reject it" check has Reject as its primary action. Fixtures are made up (names and sentences), the check sentences come from the bundle itself.
 */
public final class MatrixNotesTest
{
	public static void main(final String[] pArgs)
	{
		MatrixNotesTest.leadingCheckIsRecognisedInEnglishAndFrench();
		MatrixNotesTest.leadingCheckWithArgumentsAndAnyApostrophe();
		MatrixNotesTest.otherRationalesAreNotChecks();
		MatrixNotesTest.valueCheckIsReadWithoutItsMarker();
		MatrixNotesTest.theQueueLineShowsTheCheckAndMakesRejectPrimary();
		MatrixNotesTest.theInspectorBlocksCarryTheCheckAndTheRejectHighlight();
		MatrixNotesTest.aLinkShowsItsValueCheckAndKeepsAcceptPrimary();
		MatrixNotesTest.theCardsDoNotPrintTheNoteTwice();
		MatrixNotesTest.aCodedNoteIsWordedInTheViewersLanguage();
		System.out.println("MatrixNotesTest OK");
	}

	private static final String CHECK_OK = "To check: no source column feeds this field, it would stay empty on every row (suggestion: reject it).";

	private static void leadingCheckIsRecognisedInEnglishAndFrench()
	{
		final MatrixNotes.Note en = MatrixNotes.leadingCheck(CHECK_OK + " The sales table carries a first name per ticket.");
		MatrixNotesTest.check("english sentence recognised: " + (en == null ? "null" : en.sentence), en != null && CHECK_OK.equals(en.sentence));
		MatrixNotesTest.check("it proposes rejecting", en != null && en.proposesReject);
		MatrixNotesTest.check("the rest is the proposer's own text", en != null && "The sales table carries a first name per ticket.".equals(en.rest));
		final String frSentence = MatrixMessages.get(Locale.FRENCH, ProposalChecks.KEY_NO_SOURCE);
		final MatrixNotes.Note fr = MatrixNotes.leadingCheck(frSentence + " La table des ventes associe un prénom à chaque ticket.");
		MatrixNotesTest.check("french sentence recognised (the live row, 2026-10-17): " + (fr == null ? "null" : fr.sentence), fr != null && frSentence.equals(fr.sentence));
		MatrixNotesTest.check("french rest", fr != null && fr.rest.startsWith("La table des ventes"));
		MatrixNotesTest.check("a sentence alone has an empty rest", MatrixNotes.leadingCheck(CHECK_OK) != null && MatrixNotes.leadingCheck(CHECK_OK).rest.isEmpty());
	}

	private static void leadingCheckWithArgumentsAndAnyApostrophe()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final String sentence = MatrixMessages.get(locale, ProposalChecks.KEY_DUPLICATES_RELATED, "Customer", "Order");
			final MatrixNotes.Note note = MatrixNotes.leadingCheck(sentence + " Some reason.");
			MatrixNotesTest.check("duplicates sentence (" + locale + "): " + (note == null ? "null" : note.sentence), note != null && sentence.equals(note.sentence) && note.proposesReject);
			MatrixNotesTest.check("its rest (" + locale + ")", note != null && "Some reason.".equals(note.rest));
		}
		// a straight apostrophe where the bundle writes a typographic one (a model may rewrite the text): still recognised
		final String frSentence = MatrixMessages.get(Locale.FRENCH, ProposalChecks.KEY_NO_SOURCE).replace('’', '\'');
		MatrixNotesTest.check("apostrophes tolerated either way", MatrixNotes.leadingCheck(frSentence + " x") != null);
	}

	private static void otherRationalesAreNotChecks()
	{
		MatrixNotesTest.check("null", MatrixNotes.leadingCheck(null) == null);
		MatrixNotesTest.check("blank", MatrixNotes.leadingCheck("   ") == null);
		MatrixNotesTest.check("an ordinary rationale", MatrixNotes.leadingCheck("The first name of the buyer, kept as given.") == null);
		MatrixNotesTest.check("the sentence is only a check at the START of the text", MatrixNotes.leadingCheck("Something first. " + CHECK_OK) == null);
		MatrixNotesTest.check("a feed sentence (one row per record) is no field check",
			MatrixNotes.leadingCheck(MatrixMessages.get(Locale.ENGLISH, ProposalChecks.KEY_ONE_ROW_PER_RECORD, "Tickets", "Order")) == null);
	}

	private static void valueCheckIsReadWithoutItsMarker()
	{
		final MatrixNotes.Note flagged = MatrixNotes.valueCheck("Maps the mobile column. || Value check [was 85]: this rule gives 0 of 140 sampled values that the DATE field can store.");
		MatrixNotesTest.check("the note without its [was N]: " + (flagged == null ? "null" : flagged.sentence),
			flagged != null && "this rule gives 0 of 140 sampled values that the DATE field can store.".equals(flagged.sentence));
		MatrixNotesTest.check("it never proposes rejecting", flagged != null && !flagged.proposesReject);
		MatrixNotesTest.check("the rest is the rationale before the note", flagged != null && "Maps the mobile column.".equals(flagged.rest));
		final MatrixNotes.Note fixed = MatrixNotes.valueCheck("Why. || Value check: fixed. 'date(yyyy-MM-dd)' gave 0 rows, 'trim|date(dd/MM/yyyy,yyyy-MM-dd)' converts all of them.");
		MatrixNotesTest.check("a note without [was N]", fixed != null && fixed.sentence.startsWith("fixed."));
		MatrixNotesTest.check("no note, no result", MatrixNotes.valueCheck("Just a rationale.") == null && MatrixNotes.valueCheck(null) == null);
	}

	// ------------------------------------------------------------------------------------------------- rendering

	private static MatrixModel.Project project(final String pFieldRationale, final String pCellRationale)
	{
		final MatrixModel.Attr key = new MatrixModel.Attr("a0", "sourceId", "STRING", true, false, true, "", null);
		final MatrixModel.Attr field = new MatrixModel.Attr("a1", "buyerFirstName", "STRING", false, false, false, "PROPOSED", Integer.valueOf(20), null, "", "", null,
			pFieldRationale);
		final MatrixModel.Attr other = new MatrixModel.Attr("a2", "city", "STRING", false, false, false, "ACCEPTED", Integer.valueOf(90));
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		cells.put("a2", new MatrixModel.Cell(MatrixModel.PROPOSED, "m9", "CITY", null, Integer.valueOf(40), "LLM", pCellRationale, null, 0, null,
			MatrixModel.Sample.of("paris", "paris")));
		final List<MatrixModel.Feed> feeds = new ArrayList<MatrixModel.Feed>();
		feeds.add(new MatrixModel.Feed("f1", "s1", "t1", "SALES", "ACCEPTED", null, "", false, null, cells));
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Order", Arrays.asList(key, field, other), feeds, "ACCEPTED", Integer.valueOf(100));
		return new MatrixModel.Project("P", Arrays.asList(new MatrixModel.Source("s1", "SALES", "#2f6fd0")), Arrays.asList(entity));
	}

	private static String render(final MatrixModel.Project pProject, final Locale pLocale)
	{
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = "e1";
		state.queueMode = "open";
		state.locale = pLocale;
		return MatrixHtml.render(pProject, state);
	}

	/** The html of the queue line whose key is {@code pKey}. */
	private static String line(final String pHtml, final String pKey)
	{
		final int start = pHtml.indexOf("<div class=\"mdmmQItem");
		int from = start;
		while (from >= 0)
		{
			final int end = pHtml.indexOf("</span></div>", from);
			final String candidate = pHtml.substring(from, end + 13);
			if (candidate.contains("data-key=\"" + pKey + "\""))
			{
				return candidate;
			}
			from = pHtml.indexOf("<div class=\"mdmmQItem", from + 10);
		}
		return "";
	}

	private static void theQueueLineShowsTheCheckAndMakesRejectPrimary()
	{
		final String html = MatrixNotesTest.render(MatrixNotesTest.project(CHECK_OK + " The sales table carries a first name per ticket.", "Maps the city."), Locale.ENGLISH);
		final String line = MatrixNotesTest.line(html, "attr|a1");
		MatrixNotesTest.check("the field has a line in the queue", !line.isEmpty());
		MatrixNotesTest.check("the line carries the check sentence in its own element", line.contains("<span class=\"mdmmQCheck\">To check: no source column feeds this field"));
		MatrixNotesTest.check("and still the generic reason", line.contains("mdmmQWhy"));
		final int accept = line.indexOf("'ACCEPT'");
		final int reject = line.indexOf("'REJECT'");
		MatrixNotesTest.check("Reject is the primary button", line.substring(line.lastIndexOf("<button", reject), reject).contains("mdmmBtnPrimary"));
		MatrixNotesTest.check("Accept is not", !line.substring(line.lastIndexOf("<button", accept), accept).contains("mdmmBtnPrimary"));
		// a field without a check keeps Accept as its primary action
		final String plain = MatrixNotesTest.line(MatrixNotesTest.render(MatrixNotesTest.project("A plain reason.", "Maps the city."), Locale.ENGLISH), "attr|a1");
		MatrixNotesTest.check("no check: no check element", !plain.contains("mdmmQCheck"));
		MatrixNotesTest.check("no check: Accept stays primary", plain.substring(plain.lastIndexOf("<button", plain.indexOf("'ACCEPT'")), plain.indexOf("'ACCEPT'")).contains("mdmmBtnPrimary"));
	}

	private static void theInspectorBlocksCarryTheCheckAndTheRejectHighlight()
	{
		final String html = MatrixNotesTest.render(MatrixNotesTest.project(CHECK_OK + " The sales table carries a first name per ticket.", "Maps the city."), Locale.ENGLISH);
		MatrixNotesTest.check("the item data carries the check", html.contains("&quot;check&quot;:&quot;To check: no source column feeds this field"));
		MatrixNotesTest.check("and that it proposes rejecting", html.contains("&quot;checkReject&quot;:true"));
		MatrixNotesTest.check("and the proposer's own text apart from the sentence", html.contains("&quot;rationale&quot;:&quot;The sales table carries a first name per ticket.&quot;"));
		final String script = MatrixHtml.script(Locale.ENGLISH);
		MatrixNotesTest.check("the inspector prints the check before the generic reason", script.contains("if(it.check){html+=") && script.indexOf("if(it.check)") < script.indexOf("it.why.charAt(0)"));
		MatrixNotesTest.check("the buttons follow checkReject", script.contains("(it.checkReject?'':' mdmmBtnPrimary')") && script.contains("(it.checkReject?' mdmmBtnPrimary':'')"));
		final String fr = MatrixNotesTest.render(MatrixNotesTest.project(MatrixMessages.get(Locale.FRENCH, ProposalChecks.KEY_NO_SOURCE) + " Texte du modèle.", "Maps the city."), Locale.FRENCH);
		MatrixNotesTest.check("the french sentence reaches the card as it was written", fr.contains("À vérifier : aucune colonne source n&#39;alimente") || fr.contains("À vérifier : aucune colonne source n’alimente"));
	}

	private static void aLinkShowsItsValueCheckAndKeepsAcceptPrimary()
	{
		final String html = MatrixNotesTest.render(MatrixNotesTest.project("A plain reason.",
			"Maps the city. || Value check [was 85]: this rule gives 0 of 140 sampled values that the DATE field can store."), Locale.ENGLISH);
		final String line = MatrixNotesTest.line(html, "f1|a2");
		MatrixNotesTest.check("the link has a line", !line.isEmpty());
		MatrixNotesTest.check("it shows the value check, labelled", line.contains("<span class=\"mdmmQCheck\">Value check: this rule gives 0 of 140"));
		MatrixNotesTest.check("Accept stays primary (a value check proposes nothing)", line.substring(line.lastIndexOf("<button", line.indexOf("'ACCEPT'")), line.indexOf("'ACCEPT'")).contains("mdmmBtnPrimary"));
		MatrixNotesTest.check("the item data carries it, no reject highlight", html.contains("&quot;check&quot;:&quot;Value check: this rule gives 0 of 140") && html.contains("&quot;checkReject&quot;:false"));
		final String fr = MatrixNotesTest.render(MatrixNotesTest.project("A plain reason.", "Maps the city. || Value check: fixed."), Locale.FRENCH);
		MatrixNotesTest.check("the label is in the viewer's language", MatrixNotesTest.line(fr, "f1|a2").contains("Vérification des valeurs : fixed."));
	}

	private static void theCardsDoNotPrintTheNoteTwice()
	{
		final String html = MatrixNotesTest.render(MatrixNotesTest.project("A plain reason.",
			"Maps the city. || Value check [was 85]: this rule gives 0 of 140 sampled values that the DATE field can store."), Locale.ENGLISH);
		// the cell's data (the grid cell and its inspector): the running rationale is the base, the note has its own field
		MatrixNotesTest.check("the cell rationale is the base text", html.contains("&quot;rationale&quot;:&quot;Maps the city.&quot;"));
		MatrixNotesTest.check("the note is its own field", html.contains("&quot;valueCheck&quot;:&quot;Value check: this rule gives 0 of 140"));
		MatrixNotesTest.check("no raw delimiter in the page", !html.contains("|| Value check"));
		MatrixNotesTest.check("the cell inspector prints both blocks", MatrixHtml.script(Locale.ENGLISH).contains("if(data.check){") && MatrixHtml.script(Locale.ENGLISH).contains("if(data.valueCheck){"));
	}

	/**
	 * Staging last points, item 1 (2026-10-17): the passes store their notes coded (English plus a marker); the matrix words them in the viewer's language in the
	 * queue line, the item data and the cell inspector - the live finding was an English "codes to align..." under a French label.
	 */
	private static void aCodedNoteIsWordedInTheViewersLanguage()
	{
		final NoteTexts.Note note = new NoteTexts.Note().add(NoteTexts.N_ALIGN_FIRST, Integer.valueOf(2), "LOYALTY").add(NoteTexts.N_ALIGN_NEXT, Integer.valueOf(3), "CRM")
			.add(NoteTexts.N_ALIGN_TAIL, Integer.valueOf(0), Integer.valueOf(5)).add(NoteTexts.N_CODE_GAP, Long.valueOf(140), Long.valueOf(140), Long.valueOf(2));
		final String rationale = CheckNote.with("Maps the title.", note, Integer.valueOf(85), 2000);
		final MatrixModel.Project project = MatrixNotesTest.project("A plain reason.", rationale);
		final String fr = MatrixNotesTest.render(project, Locale.FRENCH);
		final String line = MatrixNotesTest.line(fr, "f1|a2");
		MatrixNotesTest.check("the French queue line words the note: " + line, line.contains("V\u00E9rification des valeurs : codes \u00E0 aligner") && !line.contains("codes to align")
			&& !line.contains("[mdmf"));
		MatrixNotesTest.check("the cell data too", fr.contains("&quot;valueCheck&quot;:&quot;V\u00E9rification des valeurs : codes \u00E0 aligner") && !fr.contains("[mdmf"));
		MatrixNotesTest.check("the proposer's text stays the cell rationale, without the marker", fr.contains("&quot;rationale&quot;:&quot;Maps the title.&quot;"));
		final String en = MatrixNotesTest.render(project, Locale.ENGLISH);
		MatrixNotesTest.check("English reads the stored English", en.contains("Value check: codes to align: 2 distinct in LOYALTY, 3 distinct in CRM, 0 in common out of 5.") && !en.contains("[mdmf"));
		final MatrixNotes.Note direct = MatrixNotes.valueCheck(rationale, Locale.FRENCH);
		MatrixNotesTest.check("MatrixNotes.valueCheck(locale)", direct != null && direct.sentence.startsWith("codes \u00E0 aligner") && "Maps the title.".equals(direct.rest));
		MatrixNotesTest.check("the one-argument form reads English", MatrixNotes.valueCheck(rationale).sentence.startsWith("codes to align"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("MatrixNotesTest: " + pWhat);
		}
	}
}
