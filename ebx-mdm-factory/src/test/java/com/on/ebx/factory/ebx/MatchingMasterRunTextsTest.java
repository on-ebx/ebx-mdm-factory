package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import com.on.ebx.factory.landing.RunTexts;
import com.on.ebx.factory.master.MatchingRunScope;
import com.on.ebx.factory.matching.MatchStats;
import com.on.ebx.factory.wizard.EngineMessageCodec;
import com.on.ebx.factory.wizard.MatchingResultRow;
import com.on.ebx.factory.wizard.RunPanelText;

/**
 * Run texts of the matching and master screens (2026-10-18): the matching job's result and its "child objects follow their parent" sentence, an entity's run message with its
 * scope, the master build's notes - stored in English with their code, shown in the viewer's language, the English unchanged for every reader that ignores the marker.
 * JUnit-free: run the main, a failure throws.
 */
public final class MatchingMasterRunTextsTest
{
	private static final Locale FR = Locale.FRENCH;
	private static final Locale EN = Locale.ENGLISH;

	public static void main(final String[] pArgs)
	{
		MatchingMasterRunTextsTest.theJobResult();
		MatchingMasterRunTextsTest.theEntityMessage();
		MatchingMasterRunTextsTest.theScopeIsTheEnginesEnglish();
		MatchingMasterRunTextsTest.theMasterBuildLines();
		System.out.println("MatchingMasterRunTextsTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static void theJobResult()
	{
		final String stored = MatchingRunRun.coded(false, null, 1, 0, 0, 0, 0, Collections.<String>emptyList(), 1);
		final String english = MatchingRunRun.withFollows(MatchingRunRun.message(false, null, 1, 0, 0, 0, 0, Collections.<String>emptyList()), 1);
		check("the English is what the job has always said: " + EngineMessageCodec.decode(stored).raw, EngineMessageCodec.decode(stored).raw.equals(english)
			&& english.equals("1 entity run, 0 not applied, 0 refused. 1 child object has no plan of its own: its records follow the matching of the parent."));
		final String fr = RunPanelText.display(stored, FR);
		check("French: " + fr, fr.equals("Objets métier exécutés : 1 ; non appliqués : 0 ; refusés : 0. 1 objet enfant n'a pas de plan propre : ses enregistrements suivent le rapprochement de son parent."));
		check("English display is the English", RunPanelText.display(stored, EN).equals(english));
		check("no marker on screen", !fr.contains("mdmf") && !RunPanelText.display(stored, EN).contains("mdmf"));
		final String many = MatchingRunRun.coded(false, null, 3, 1, 2, 0, 0, Collections.<String>emptyList(), 2);
		check("plural English", EngineMessageCodec.decode(many).raw.equals("3 entities run, 1 not applied, 2 refused. 2 child objects have no plan of their own: their records follow the matching of the parent."));
		check("plural French", RunPanelText.display(many, FR).contains("2 objets enfants n'ont pas de plan propre") && RunPanelText.display(many, FR).contains("leur parent"));
		// a stopped run: the stop sentence is coded, the failures it names stay as the engine wrote them
		final String stopped = MatchingRunRun.coded(true, "stopped by admin", 2, 0, 0, 1, 0, Arrays.asList("Adresse - boom"), 0);
		final String stoppedEnglish = MatchingRunRun.message(true, "stopped by admin", 2, 0, 0, 1, 0, Arrays.asList("Adresse - boom"));
		check("stopped: the English is unchanged", EngineMessageCodec.decode(stopped).raw.equals(stoppedEnglish));
		check("stopped: French", RunPanelText.display(stopped, FR).startsWith("Rapprochement arrêté (stopped by admin) : objets métier exécutés avant l'arrêt : 2, non démarrés : 1.")
			&& RunPanelText.display(stopped, FR).endsWith("Adresse - boom."));
		// the plain run still reads the old English
		check("an older stored job message is worded too", RunPanelText.display("1 entity run, 0 not applied, 0 refused.", FR).startsWith("Objets métier exécutés"));
	}

	private static void theEntityMessage()
	{
		final String stored = RunTexts.matchingFinished("Personne", "DONE", 534, 0, 0, RunTexts.matchingScope(true, 81, 81).stored(), "").stored();
		final String english = "Matching run on Personne finished (DONE); Xref.goldenKey refreshed (534 set, 0 cleared, 0 unchanged). Scope: all records - the 81 records of the table were pivots.";
		check("English: " + EngineMessageCodec.decode(stored).raw, EngineMessageCodec.decode(stored).raw.equals(english));
		final String fr = RunPanelText.display(stored, FR);
		check("French: " + fr, fr.equals("Rapprochement de Personne termin\u00E9 (DONE)\u00A0; cl\u00E9s des golden records actualis\u00E9es (pos\u00E9es\u00A0: 534, effac\u00E9es\u00A0: 0, inchang\u00E9es\u00A0: 0). P\u00E9rim\u00E8tre\u00A0: tous les enregistrements - les 81 enregistrements de la table \u00E9taient des pivots."));
		// the row of step 6 reads its scope and its warning from the English, shows the message in French
		final String withWarning = RunTexts.matchingFinished("Personne", "DONE", 534, 0, 0, RunTexts.matchingScope(false, 30, 81).stored(), " WARNING: candidate search NOT filtered.").stored();
		final MatchingResultRow row = MatchingResultRow.of(FR, "DONE", withWarning, new MatchStats.Kpis(81, new java.util.LinkedHashMap<String, Integer>(), 81, 0, 0, 0, 51));
		check("the scope is read from the English: " + row.compared, row.compared.equals("30 / 81"));
		check("the warning is the engine's, with no marker: " + row.warning, row.warning.equals("candidate search NOT filtered."));
		check("the details are in French, with no marker: " + row.details, row.details.startsWith("Rapprochement de Personne terminé") && !row.details.contains("mdmf")
			&& row.details.contains("Périmètre : incrémental - 30 enregistrements sur 81"));
		final MatchingResultRow english2 = MatchingResultRow.of(EN, "DONE", withWarning, null);
		check("English details", english2.details.startsWith("Matching run on Personne finished (DONE)") && !english2.details.contains("mdmf"));
		// a run stored before the lot: the raw English keeps working in the row
		final MatchingResultRow old = MatchingResultRow.of(FR, "DONE", english, null);
		check("an older message still gives its scope", old.compared.equals("81 / 81"));
		check("two sentences joined", RunPanelText.display(RunTexts.joined(RunTexts.matchingResult(1, 0, 0).stored(), "Free text.").stored(), FR).endsWith("refusés : 0. Free text."));
	}

	private static void theScopeIsTheEnginesEnglish()
	{
		final long[][] cases = { { 1, 81, 81 }, { 1, -1, -1 }, { 0, 30, 1081 }, { 0, -1, 81 }, { 0, 5, 1234567 }, { 1, 1234567, 1234567 } };
		for (final long[] c : cases)
		{
			final boolean all = c[0] == 1;
			check("scope English equals MatchingRunScope.describe for " + java.util.Arrays.toString(c), RunTexts.matchingScope(all, c[1], c[2]).english.equals(MatchingRunScope.describe(all, c[1], c[2])));
		}
	}

	private static void theMasterBuildLines()
	{
		final String resolving = RunTexts.masterResolvingDeferred("Personne").stored();
		check("resolving: English", EngineMessageCodec.decode(resolving).raw.equals("Personne: resolving the references left for the end."));
		check("resolving: French", RunPanelText.display(resolving, FR).equals("Personne : résolution des références laissées pour la fin."));
		final String counts = RunTexts.masterEntityCounts("Personne", 234, 234, 0, 0).stored();
		check("counts: English is the line the panel always showed", EngineMessageCodec.decode(counts).raw.equals("Personne: 234 row(s) read, 234 written, 0 unchanged."));
		check("counts: French, no '(s)'", RunPanelText.display(counts, FR).equals("Personne : lignes lues : 234, écrites : 234, inchangées : 0."));
		check("counts held back", EngineMessageCodec.decode(RunTexts.masterEntityCounts("P", 5, 4, 0, 1).stored()).raw.equals("P: 5 row(s) read, 4 written, 0 unchanged, 1 held back."));
		check("an older note is worded too", RunPanelText.display("Personne: 234 row(s) read, 234 written, 0 unchanged.", FR).startsWith("Personne : lignes lues"));
		check("an older resolving note is worded too", RunPanelText.display("Personne: resolving the references left for the end.", FR).contains("résolution"));
		final String copying = RunTexts.masterCopying("Personne", 1).stored();
		check("copying 1 row", RunPanelText.display(copying, FR).equals("Personne : copie de 1 ligne.") && EngineMessageCodec.decode(copying).raw.equals("Personne: copying 1 row."));
		check("progress with a total", RunPanelText.display(RunTexts.masterProgress("Personne", 96, 534, 90, 6).stored(), FR).equals("Personne : 96 lignes lues sur 534, 90 copiées, 6 mises de côté."));
		check("progress English", EngineMessageCodec.decode(RunTexts.masterProgress("Personne", 96, -1, 95, 0).stored()).raw.equals("Personne: 96 rows read, 95 copied."));
		check("scope summary", RunPanelText.display(RunTexts.masterRowsWrittenScope(12, 40, 0, 1, "changed", 40).stored(), FR).contains("périmètre changed, lignes de journal : 40"));
		final String failed = RunTexts.masterBuildFailedStages(true, true).stored();
		check("build failed both", RunPanelText.display(failed, FR).contains("tables maîtres et l'étape de copie")
			&& EngineMessageCodec.decode(failed).raw.equals("Master build failed: the master tables step and the copy step did not complete - see the grid below for why."));
	}
}
