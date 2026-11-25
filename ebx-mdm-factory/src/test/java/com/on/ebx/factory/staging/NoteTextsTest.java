package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.CodeMap;
import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.wizard.EngineMessageCodec;
import com.on.ebx.factory.wizard.RationaleText;
import com.on.ebx.factory.wizard.WizardMessages;

/**
 * JUnit-free: run the main, a failure throws. Staging last points, item 1 (2026-10-17): the notes the staging passes write are coded - stored in English with
 * a marker, worded in the viewer's language by {@link RationaleText}; an old row (plain English) stays readable; REST readers lose the marker.
 */
public final class NoteTextsTest
{
	private static final Locale EN = Locale.ENGLISH;
	private static final Locale FR = Locale.FRENCH;

	public static void main(final String[] pArgs)
	{
		bundleSaysTheSameAsTheBuiltInEnglish();
		roundTrip();
		verifierNoteIsWordedInFrench();
		valueCheckInARationale();
		oldRowsStayReadable();
		cutKeepsTheMarker();
		alignmentNoteMatchesTheLiveSentence();
		normalisationStepsAreCoded();
		codeTranscodingKeepsTheModelsWords();
		editedCodeTable();
		restReadersSeeEnglishWithoutMarker();
		System.out.println("NoteTextsTest OK");
	}

	private static void bundleSaysTheSameAsTheBuiltInEnglish()
	{
		for (final String key : NoteTexts.ENGLISH.keySet())
		{
			final String full = NoteTexts.P + key;
			final Object[] args = new Object[] { "AAA", "BBB", "CCC", "DDD", "EEE", "FFF", "GGG" };
			final String builtIn = NoteTexts.ENGLISH_WORDS.get(full, args);
			final String bundle = WizardMessages.get(EN, full, args);
			check("bundle English == built-in English for " + key, builtIn.equals(bundle));
			final String french = WizardMessages.get(FR, full, args);
			check("French exists for " + key, !full.equals(french));
			if (!key.equals(NoteTexts.DOT) && !key.startsWith("n.alignNext"))
			{
				check("French differs from English for " + key, !french.equals(bundle));
			}
			// no argument left unfilled
			check("no placeholder left in French for " + key, !french.matches(".*\\{\\d+\\}.*"));
		}
	}

	private static void roundTrip()
	{
		final NoteTexts.Note note = NoteTexts.Note.of(NoteTexts.V_GIVES, "'trim|isodate()'", NoteTexts.frag(NoteTexts.F_TYPE + "DATE"), Long.valueOf(0), Long.valueOf(140))
			.add(NoteTexts.DOT);
		final String stored = note.stored(2000);
		check("the stored text starts with the English", stored.startsWith("'trim|isodate()' gives a valid date (yyyy-MM-dd) for 0 of 140 rows."));
		check("then the marker", stored.endsWith("]") && stored.contains(" [mdmf:staging.note.seq|"));
		final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(stored);
		check("decoded raw is the English", decoded.raw.equals(note.english()));
		final NoteTexts.Note back = NoteTexts.noteOf(decoded);
		check("the note comes back", back != null && back.english().equals(note.english()) && back.segments().size() == 2);
		check("English display equals the stored English", RationaleText.one(stored, EN).equals(note.english()));
		final String fr = RationaleText.one(stored, FR);
		check("French display: " + fr, fr.equals("'trim|isodate()' donne une date valide (yyyy-MM-dd) pour 0 lignes sur 140."));
		check("a null locale reads English", RationaleText.one(stored, null).equals(note.english()));
		// an argument with the characters of the marker syntax survives
		final NoteTexts.Note tricky = NoteTexts.Note.of(NoteTexts.N_ALIGN_FIRST, Integer.valueOf(2), "A|B] [mdmf:x|s:y%").add(NoteTexts.N_ALIGN_TAIL, Integer.valueOf(0), Integer.valueOf(5));
		final String trickyStored = tricky.stored(2000);
		check("a name with marker characters round-trips", NoteTexts.noteOf(EngineMessageCodec.decode(trickyStored)).english().equals(tricky.english()));
		check("a damaged marker is plain English", RationaleText.one("Text [mdmf:staging.note.seq|s:v.gives|n:9|s:x]", FR).startsWith("Text"));
		check("an empty or null text", RationaleText.one(null, FR).isEmpty() && RationaleText.display("", FR).isEmpty());
	}

	private static void verifierNoteIsWordedInFrench()
	{
		// the live case: a text date mapped with isodate() on 140 rows of a source
		final List<Detectors.ValueCount> values = new ArrayList<Detectors.ValueCount>();
		values.add(new Detectors.ValueCount("10/06/1963", 100L));
		values.add(new Detectors.ValueCount("25/12/1980", 40L));
		final NormalisationProfile profile = NormalisationProfiler.profile(values, -1L);
		final TransformVerifier.Result result = TransformVerifier.verify("DATE", "isodate()", values, profile, new TransformVerifier.Evaluator()
		{
			@Override
			public String apply(final String pPipeline, final String pValue) throws Exception
			{
				return pPipeline.startsWith("isodate") ? pValue : pValue.substring(6) + "-" + pValue.substring(3, 5) + "-" + pValue.substring(0, 2);
			}
		}, false);
		check("a verdict that needs words", result.note != null && !result.note.isEmpty());
		check("the reason is the note's English", result.reason.equals(result.note.english()));
		final String stored = CheckNote.with("Maps the date.", result.note, Integer.valueOf(85), 2000);
		check("the rationale keeps the proposer's text first", stored.startsWith("Maps the date. || Value check [was 85]: "));
		check("and ends with one marker", stored.endsWith("]") && stored.indexOf("[mdmf:") == stored.lastIndexOf("[mdmf:"));
		check("the English display carries the label and the sentence", RationaleText.display(stored, EN).equals("Maps the date. || Value check: " + result.reason));
		final String fr = RationaleText.display(stored, FR);
		check("French display: " + fr, fr.startsWith("Maps the date. || Vérification des valeurs : ") && fr.contains("donne une date valide (yyyy-MM-dd) pour") && !fr.contains("gives a valid")
			&& !fr.contains("[mdmf:") && !fr.contains("[was"));
		check("the matrix reads the note without its label", RationaleText.valueCheck(stored, FR).startsWith("'isodate()' donne une date valide"));
	}

	private static void valueCheckInARationale()
	{
		final NoteTexts.Note codeGap = NoteTexts.Note.of(NoteTexts.N_CODE_GAP, Long.valueOf(140), Long.valueOf(140), Long.valueOf(2));
		final String stored = CheckNote.with("Code transcoding: x", codeGap.addAll(NoteTexts.Note.of(NoteTexts.N_CODE_CONFLICT, Integer.valueOf(1))), Integer.valueOf(80), 2000);
		final String en = RationaleText.valueCheck(stored, EN);
		check("two notes join with a space: " + en, en.equals("code table: 140 of 140 rows (2 distinct codes) have no target code in this source's table; a person completes the table or rejects it. "
			+ "code table: 1 pair(s) of codes read the same once case and punctuation are ignored but go to different targets; a person corrects the table."));
		final String fr = RationaleText.valueCheck(stored, FR);
		check("French: " + fr, fr.startsWith("table de codes : 140 lignes sur 140 (2 codes distincts) n’ont pas de code cible dans la table de cette source"));
		check("a second note replaces the first", CheckNote.with(stored, NoteTexts.Note.of(NoteTexts.N_FIXED), null, 2000).indexOf(CheckNote.DELIMITER) == CheckNote.with(stored,
			NoteTexts.Note.of(NoteTexts.N_FIXED), null, 2000).lastIndexOf(CheckNote.DELIMITER));
		check("the confidence it replaced is still readable", Integer.valueOf(80).equals(CheckNote.originalConfidence(stored)));
		check("no note, no value check", RationaleText.valueCheck("Plain.", FR).isEmpty() && RationaleText.display("Plain.", FR).equals("Plain."));
		check("cleaning the note leaves the base", CheckNote.base(stored).equals("Code transcoding: x"));
	}

	private static void oldRowsStayReadable()
	{
		final String old = "Maps the mobile column. || Value check [was 85]: this rule gives 0 of 140 sampled values that the DATE field can store.";
		check("an old row shows its English in English", RationaleText.display(old, EN).equals("Maps the mobile column. || Value check: this rule gives 0 of 140 sampled values that the DATE field can store."));
		check("and in a French page (nothing to word it from)", RationaleText.display(old, FR).equals("Maps the mobile column. || Vérification des valeurs : this rule gives 0 of 140 sampled values that the DATE field can store."));
		check("an old normalisation rationale is untouched", RationaleText.display("Normalisation: 160 of 160 rows are phone numbers written with separators; they are removed and the leading + is kept.", FR)
			.startsWith("Normalisation: 160 of 160 rows"));
		check("the old CheckNote.with(String) still works", CheckNote.has(CheckNote.with("a", "b", null, 2000)));
	}

	private static void cutKeepsTheMarker()
	{
		final NoteTexts.Note note = NoteTexts.Note.of(NoteTexts.N_CODE_GAP, Long.valueOf(140), Long.valueOf(140), Long.valueOf(2));
		final String base = "Type-driven mapping.";
		final String roomy = CheckNote.with(base, note, null, 2000);
		check("it fits with its marker", roomy.length() <= 2000 && roomy.endsWith("]"));
		final String tight = CheckNote.with(base, note, Integer.valueOf(85), 220);
		check("tight room: the text gives way to the marker (" + tight.length() + ")", tight.length() <= 220 && tight.endsWith("]") && EngineMessageCodec.decode(tight.substring(tight.indexOf(CheckNote.DELIMITER))).coded());
		check("a cut note is still worded in full in French", RationaleText.valueCheck(tight, FR).startsWith("table de codes"));
		final String none = CheckNote.with(base, note, null, 60);
		check("no room for the marker: plain English, never longer than the field", none.length() <= 60 && !none.contains("[mdmf:"));
		check("no room at all: the proposer's text", CheckNote.with("x", note, null, 20).equals("x"));
	}

	private static void alignmentNoteMatchesTheLiveSentence()
	{
		// the sentence of the live finding, 2026-10-17 (made-up source names)
		final NoteTexts.Note note = new NoteTexts.Note().add(NoteTexts.N_ALIGN_FIRST, Integer.valueOf(2), "LOYALTY").add(NoteTexts.N_ALIGN_NEXT, Integer.valueOf(3), "CRM")
			.add(NoteTexts.N_ALIGN_TAIL, Integer.valueOf(0), Integer.valueOf(5));
		check("English: " + note.english(), note.english().equals("codes to align: 2 distinct in LOYALTY, 3 distinct in CRM, 0 in common out of 5. No reference or mapping exists for them: "
			+ "a person decides which code means which."));
		final String fr = RationaleText.one(note.stored(2000), FR);
		check("French: " + fr, fr.equals("codes à aligner : 2 distincts dans LOYALTY, 3 distincts dans CRM, 0 en commun sur 5. Aucune référence ni correspondance "
			+ "n’existe pour eux : une personne décide quel code signifie quoi."));
	}

	private static void normalisationStepsAreCoded()
	{
		final List<Detectors.ValueCount> values = new ArrayList<Detectors.ValueCount>();
		values.add(new Detectors.ValueCount("+33 6 46 86 05 23", 100L));
		values.add(new Detectors.ValueCount("06.12.34.56.78", 60L));
		final NormalisationProfile profile = NormalisationProfiler.profile(values, -1L);
		final NormalisationPass.Column column = new NormalisationPass.Column("m1", "a1", "mobile", "mobile", "WEB", "STRING", "", profile, values, 2L, new java.util.LinkedHashSet<String>());
		final List<NormalisationPass.Column> columns = new ArrayList<NormalisationPass.Column>();
		columns.add(column);
		final NormalisationPass.Result result = NormalisationPass.propose(columns, CleaningSteps.Lookups.empty());
		NormalisationPass.Step phone = null;
		for (final NormalisationPass.Step step : result.stepsOf("m1"))
		{
			if (CleaningSteps.STRIP_CHARS.equals(step.kind))
			{
				phone = step;
			}
		}
		check("a STRIP_CHARS step for the phone column", phone != null);
		check("its English starts with the marker word the sweeps read", phone.rationale.startsWith(NormalisationPass.MARKER) && NormalisationPass.isOwnRationale(phone.rationale));
		final String stored = phone.storedRationale(2000);
		check("stored: still own", NormalisationPass.isOwnRationale(stored) && stored.contains("[mdmf:staging.note.seq|"));
		final String fr = RationaleText.display(stored, FR);
		check("French: " + fr, fr.startsWith("Normalisation : 160 lignes sur 160") || fr.startsWith("Normalisation : ") && fr.contains("numéros de téléphone écrits avec des séparateurs"));
		check("English display is the English text", RationaleText.display(stored, EN).equals(phone.rationale));
		// Detectors proposals carry a note too (trim)
		final List<Detectors.ValueCount> spaced = new ArrayList<Detectors.ValueCount>();
		spaced.add(new Detectors.ValueCount(" Paris", 10L));
		spaced.add(new Detectors.ValueCount("Lyon", 30L));
		final List<Detectors.StepProposal> proposals = Detectors.propose(spaced, new Detectors.ColumnContext("city", "city", "STRING", new ArrayList<String>()), 2L);
		check("a TRIM proposal with a note", !proposals.isEmpty() && proposals.get(0).note != null && proposals.get(0).rationale.equals(proposals.get(0).note.english()));
		check("its stored form words in French", RationaleText.one(proposals.get(0).stored(2000), FR).startsWith("10 valeur(s) sur 40 avaient des espaces"));
	}

	private static void codeTranscodingKeepsTheModelsWords()
	{
		final NoteTexts.Note note = NoteTexts.Note.of(NoteTexts.C_PREFIX).add(NoteTexts.C_HEAD, Integer.valueOf(2), "LOYALTY", Long.valueOf(140), Integer.valueOf(3)).add(NoteTexts.DOT)
			.add(NoteTexts.C_UNRESOLVED, Long.valueOf(140), Long.valueOf(140));
		final String model = "Titles M. and Mme map to MR and MRS because the CRM writes them that way.";
		final String stored = note.stored(model, 2000);
		check("the English keeps the model's words after the note", EngineMessageCodec.decode(stored).raw.equals(note.english() + " " + model));
		final String fr = RationaleText.display(stored, FR);
		check("French: the note worded, the model's words after: " + fr, fr.startsWith("Transcodage des codes : les sources de ce champ") && fr.endsWith(model));
		check("English display is the stored English", RationaleText.display(stored, EN).equals(note.english() + " " + model));
		check("the pass's own rationale is recognised", CodeTranscodingPass.isOwnRationale(stored));
	}

	private static void editedCodeTable()
	{
		final NoteTexts.Note note = NoteTexts.Note.of(NoteTexts.C_PREFIX).add(NoteTexts.C_UNRESOLVED, Long.valueOf(3), Long.valueOf(5));
		final String stored = note.stored("Model words.", 2000);
		final String edited = NoteTexts.withSegment(stored, NoteTexts.C_EDITED, 2000);
		check("the edit sentence goes into the note", edited != null && RationaleText.display(edited, FR).contains("Modifiée par une personne.") && RationaleText.display(edited, FR).endsWith("Model words."));
		check("English too", RationaleText.display(edited, EN).contains("Edited by a person."));
		check("twice adds it once", NoteTexts.withSegment(edited, NoteTexts.C_EDITED, 2000).equals(edited));
		check("a plain text is left to the caller", NoteTexts.withSegment("Plain text.", NoteTexts.C_EDITED, 2000) == null);
		final CodeMap map = CodeMap.parse("{\"codes\":{\"M.\":\"\"},\"targets\":[\"MR\"],\"counts\":{\"M.\":1}}");
		check("sanity: a CodeMap parses", map != null);
	}

	private static void restReadersSeeEnglishWithoutMarker()
	{
		final String stored = NoteTexts.Note.of(NoteTexts.N_PLUS_REPAIR).stored(2000);
		check("stripMarkers gives the English", EngineMessageCodec.stripMarkers(stored).equals(NoteTexts.Note.of(NoteTexts.N_PLUS_REPAIR).english()));
		final String rationale = CheckNote.with("Base.", NoteTexts.Note.of(NoteTexts.N_PLUS_REPAIR), null, 2000);
		check("a whole rationale too", !EngineMessageCodec.stripMarkers(rationale).contains("[mdmf:") && EngineMessageCodec.stripMarkers(rationale).startsWith("Base. || Value check: the + was removed"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("NoteTextsTest: " + pWhat);
		}
	}
}
