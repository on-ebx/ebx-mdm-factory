package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.landing.KeyTexts;
import com.on.ebx.factory.landing.LandingChainOutcome;
import com.on.ebx.factory.landing.LandingRunProgress;
import com.on.ebx.factory.landing.RunTexts;

/**
 * JUnit-free: run the main, a failure throws. Run panel i18n (2026-10-17): every code of the landing run's own sentences (round trip, the old English parsed, English and
 * French wording, the bundle pinned to the built-in English), the journal, the stored progress, the partial-failure reasons, the end-of-run messages, Recent tasks, the
 * answer the jobs route gives the live panel's script.
 */
public final class RunPanelTextTest
{
	private static final Locale EN = Locale.ENGLISH;
	private static final Locale FR = Locale.FRENCH;

	public static void main(final String[] pArgs)
	{
		RunPanelTextTest.bundles();
		final Map<String, KeyTexts.Text> texts = RunPanelTextTest.allTexts();
		RunPanelTextTest.eachCode(texts);
		RunPanelTextTest.frenchWords();
		RunPanelTextTest.journal();
		RunPanelTextTest.storedProgress();
		RunPanelTextTest.endOfRun();
		RunPanelTextTest.partialFailures();
		RunPanelTextTest.recentTasks();
		RunPanelTextTest.serverRenderedPanel();
		RunPanelTextTest.foreignAndFree();
		RunPanelTextTest.jobsAnswer();
		RunPanelTextTest.matchingRunLine();
		System.out.println("RunPanelTextTest OK");
	}

	// ------------------------------------------------------------------------------------------------------------- bundles

	private static void bundles()
	{
		final Pattern placeholder = Pattern.compile("\\{(\\d+)\\}");
		for (final String key : RunTexts.ENGLISH.keySet())
		{
			final String bundleEn = WizardMessages.get(EN, key);
			final String builtIn = RunTexts.ENGLISH.get(key);
			// the stored English keeps the historic words "deduced scan"; the screen's English avoids that internal word (WizardPageVocabularyTest)
			// ... and the stored English of an entity's run keeps the technical name of the field it refreshes (Xref.goldenKey: REST, cockpit), the screen's English says "golden record keys"
			check("bundle English == built-in English for " + key, builtIn.equals(bundleEn) || key.endsWith("keySummary.DEDUCED") && bundleEn.contains("found from the data")
				|| key.equals(RunTexts.MTC_FINISHED) && bundleEn.contains("golden record keys refreshed"));
			final String fr = WizardMessages.get(FR, key);
			check("French exists for " + key, !key.equals(fr) && !fr.isEmpty());
			check("French differs from English for " + key, !fr.equals(bundleEn) || key.endsWith("keyWait") || key.endsWith("ev.stage.TABLE") || key.equals(RunTexts.JOINED));
			check("same placeholders in both languages for " + key, placeholders(placeholder, bundleEn).equals(placeholders(placeholder, fr)));
			// a French apostrophe is doubled exactly where the pattern has a placeholder (and only there): the browser undoes it by the same rule
			check("apostrophes of " + key, fr.indexOf('{') >= 0 ? !fr.replace("''", "").contains("'") : !fr.contains("''"));
		}
	}

	private static String placeholders(final Pattern pPattern, final String pText)
	{
		final List<String> found = new ArrayList<String>();
		final Matcher matcher = pPattern.matcher(pText);
		while (matcher.find())
		{
			found.add(matcher.group(1));
		}
		java.util.Collections.sort(found);
		return found.toString();
	}

	// ------------------------------------------------------------------------------------------------------------- every code

	private static Map<String, KeyTexts.Text> allTexts()
	{
		final Map<String, KeyTexts.Text> out = new LinkedHashMap<String, KeyTexts.Text>();
		out.put("loaded", RunTexts.loaded());
		out.put("built", RunTexts.built());
		out.put("created", RunTexts.created());
		out.put("keyConfirmedLoaded", RunTexts.keyConfirmedLoaded());
		out.put("builtLoaded", RunTexts.builtLoaded());
		out.put("createdLoaded", RunTexts.createdLoaded());
		out.put("keyConfirmedBuilt", RunTexts.keyConfirmedBuilt());
		out.put("keyChosen", RunTexts.keyChosen());
		out.put("keyValidatedChecked", RunTexts.keyValidated("customerId", true));
		out.put("keyValidatedUnchecked", RunTexts.keyValidated("order id,line no", false));
		out.put("keyValidatedNotUniqueSample", RunTexts.keyValidatedNotUnique("customerId", false));
		out.put("keyValidatedNotUniqueSource", RunTexts.keyValidatedNotUnique("card_number", true));
		out.put("stoppedByPerson", RunTexts.stoppedByPerson());
		out.put("stopped", RunTexts.stopped("stopped by admin"));
		out.put("waitsForKey", RunTexts.waitsForKey());
		out.put("keyFailedNoFlow", RunTexts.keyFailedNoFlow());
		out.put("tableFailedNoFlow", RunTexts.tableFailedNoFlow());
		out.put("tableFailedNoLoad", RunTexts.tableFailedNoLoad());
		out.put("unexpected", RunTexts.unexpected());
		out.put("noKeyYet", RunTexts.noKeyYet());
		out.put("noColumnCertain", RunTexts.noColumnCertain());
		out.put("noPlannedTable", RunTexts.noPlannedTable());
		out.put("tableNotBuilt", RunTexts.tableNotBuilt());
		out.put("flowNotCreated", RunTexts.flowNotCreated());
		out.put("loadFailedOne", RunTexts.loadFailed(1, 3, "connection refused"));
		out.put("loadFailedOther", RunTexts.loadFailed(2, 2, "Table 'X' (not found)"));
		out.put("loadFailedDetail", RunTexts.loadFailed(2, 2, null));
		out.put("keySummaryNone", RunTexts.keySummary("", "USER"));
		for (final String origin : new String[] { "SOURCE", "USER", "DEDUCED", "LLM", "RULE" })
		{
			out.put("keySummary" + origin, RunTexts.keySummary("order id,line no", origin));
		}
		out.put("keySummaryAccents", RunTexts.keySummary("Numéro client", "USER"));
		out.put("waitNoEvidence", RunTexts.waitReason(KeyTexts.WHY_NO_EVIDENCE, 0, null));
		out.put("waitNotUnique", RunTexts.waitReason(KeyTexts.WHY_NOT_UNIQUE, 0, null));
		out.put("waitSampleOnly", RunTexts.waitReason(KeyTexts.WHY_SAMPLE_ONLY, 0, Boolean.FALSE));
		out.put("waitNone", RunTexts.waitReason(KeyTexts.WHY_NONE_CERTAIN, 0, null));
		out.put("waitLlmLowUnique", RunTexts.waitReason(KeyTexts.WHY_LLM, 62, Boolean.TRUE));
		out.put("waitLlmLowNotUnique", RunTexts.waitReason(KeyTexts.WHY_LLM, 62, Boolean.FALSE));
		out.put("waitLlmHigh", RunTexts.waitReason(KeyTexts.WHY_LLM, 95, null));
		out.put("keyWaitLlm", RunTexts.keyWait(RunTexts.keySummary("email,name", "LLM"), RunTexts.waitReason(KeyTexts.WHY_LLM, 62, Boolean.TRUE)));
		out.put("keyWaitNoKey", RunTexts.keyWait(RunTexts.keySummary("", "RULE"), RunTexts.waitReason(KeyTexts.WHY_NO_EVIDENCE, 0, null)));
		out.put("keyWaitNone", RunTexts.keyWait(RunTexts.keySummary("id", "RULE"), RunTexts.noColumnCertain()));
		out.put("runAccepted11", RunTexts.runAccepted(1, 1));
		out.put("runAccepted1n", RunTexts.runAccepted(1, 3));
		out.put("runAcceptedn1", RunTexts.runAccepted(12, 1));
		out.put("runAcceptednn", RunTexts.runAccepted(12, 3));
		out.put("parallel", RunTexts.parallel(2, "mdm.factory.landing.parallelSources"));
		out.put("lookingKey", RunTexts.lookingKey(false));
		out.put("lookingKeyLlm", RunTexts.lookingKey(true));
		out.put("buildingTables", RunTexts.buildingTables());
		out.put("creatingFlow", RunTexts.creatingFlow());
		out.put("loadingData", RunTexts.loadingData());
		out.put("creatingDataset", RunTexts.creatingDataset("ECOM_Clients", "B2C_Landing"));
		out.put("modelReused", RunTexts.modelReused("ECOM_Clients_Landing"));
		out.put("creatingModelOne", RunTexts.creatingModel("ECOM_Clients_Landing", 1, 19));
		out.put("creatingModelOther", RunTexts.creatingModel("SALESFORCE_Landing", 3, 40));
		out.put("publishingModel", RunTexts.publishingModel("ECOM_Clients_Landing"));
		out.put("copying", RunTexts.copying("Contact", 1, 1));
		out.put("copyingOf", RunTexts.copying("Contact", 2, 2));
		out.put("rowCopiedOne", RunTexts.rowsCopied("Contact", 1, false));
		out.put("rowCopiedOther", RunTexts.rowsCopied("Contact", 0, false));
		out.put("changedOne", RunTexts.rowsCopied("Contact", 1, true));
		out.put("changedOther", RunTexts.rowsCopied("Contact", 12345, true));
		out.put("copiedSoFar", RunTexts.copiedSoFar("Contact", 5000));
		out.put("finished", RunTexts.finished(12, 12));
		out.put("finishedFailuresOn", RunTexts.finishedFailuresOn("ECOM, SALESFORCE"));
		out.put("partialSingle", RunTexts.partial(1, 1, "A.T"));
		out.put("partialOneOfMany", RunTexts.partial(1, 4, "A.T"));
		out.put("partialMany", RunTexts.partial(2, 4, "A.T, B.U"));
		out.put("runStopped", RunTexts.runStopped("stopped by admin"));
		out.put("runStoppedByPerson", RunTexts.runStoppedByPerson());
		out.put("runPaused", RunTexts.runPaused());
		out.put("runLeftRunning", RunTexts.runLeftRunning());
		out.put("stepGone", RunTexts.stepGone());
		out.put("resumeGone", RunTexts.resumeGone());
		// staging screens (2026-10-17): the staging proposal, the staging build and the sources job's "Ready" line
		out.put("proposalFinished", RunTexts.proposalFinished());
		out.put("proposalStopped", RunTexts.proposalStopped("stopped by admin"));
		out.put("proposalFailed", RunTexts.proposalFailed("Connection refused"));
		out.put("queuedDesign", RunTexts.queuedDesign());
		out.put("notRunStopped", RunTexts.notRunStopped("stopped by admin"));
		out.put("notRun", RunTexts.notRun("stopped by admin"));
		out.put("notRequested", RunTexts.notRequested());
		out.put("notSelected", RunTexts.notSelected());
		out.put("rulePassRefine", RunTexts.rulePassIncomplete("refine"));
		out.put("rulePassReview", RunTexts.rulePassIncomplete("review"));
		out.put("rulePassField", RunTexts.rulePassIncomplete("field"));
		out.put("rulePassObject", RunTexts.rulePassIncomplete("object"));
		out.put("noLlmOrAdmin", RunTexts.noLlmOrAdmin());
		out.put("buildAccepted", RunTexts.buildAccepted());
		// staging preflight lot H1 (2026-10-17): the check before the build
		out.put("preflightChecking", RunTexts.preflightChecking());
		out.put("preflightClean", RunTexts.preflightClean());
		out.put("preflightWarnings", RunTexts.preflightWarnings(3L));
		out.put("preflightBlocked", RunTexts.preflightBlocked(140L, 1L));
		out.put("preflightUnavailable", RunTexts.preflightUnavailable("boom"));
		out.put("preflightSetAside", RunTexts.preflightSetAside());
		out.put("buildFinished", RunTexts.buildFinished());
		out.put("buildStopped", RunTexts.buildStopped("stopped by admin"));
		out.put("buildFailedModel", RunTexts.buildFailedStages(true, false));
		out.put("buildFailedLoad", RunTexts.buildFailedStages(false, true));
		out.put("buildFailedBoth", RunTexts.buildFailedStages(true, true));
		out.put("buildFailed", RunTexts.buildFailed("Schema has errors"));
		out.put("stagingModelAt", RunTexts.stagingModelAt("BTiers_Staging", "Tiers_Staging"));
		out.put("rowsWritten", RunTexts.rowsWritten(394, 534, 0, 140));
		out.put("stoppedMidRun", RunTexts.stoppedMidRun(RunTexts.rowsWritten(10, 20, 0, 0).stored()));
		out.put("failedLine", RunTexts.failedLine(RunTexts.buildFailed("boom").stored()));
		out.put("readingOne", RunTexts.reading("Customer", "ECOM", 1));
		out.put("readingOther", RunTexts.reading("Customer", "ECOM", 160));
		out.put("progress", RunTexts.loadProgress("Customer", "ECOM", 96, 165, 95, 0, 0));
		out.put("progressUnchanged", RunTexts.loadProgress("Customer", "ECOM", 96, 165, 90, 5, 0));
		out.put("progressHeld", RunTexts.loadProgress("Customer", "ECOM", 96, 165, 95, 0, 1));
		out.put("progressUnchangedHeld", RunTexts.loadProgress("Customer", "ECOM", 96, 165, 90, 5, 1));
		out.put("preparingModel", RunTexts.preparingModel("Tiers_Staging"));
		out.put("creatingStagingModelOne", RunTexts.creatingStagingModel("Tiers_Staging", 1));
		out.put("creatingStagingModelOther", RunTexts.creatingStagingModel("Tiers_Staging", 3));
		out.put("updatingModel", RunTexts.updatingModel("Tiers_Staging"));
		out.put("publishingStagingModel", RunTexts.publishingStagingModel("Tiers_Staging"));
		out.put("creatingStagingDataset", RunTexts.creatingStagingDataset("Staging", "BTiers"));
		out.put("checkingUnique", RunTexts.checkingUnique("Customer", "customerId"));
		out.put("uniqueExamplesUnavailable", RunTexts.uniqueExamplesUnavailable("Customer", "birthDate", "IllegalArgumentException: Invalid DATE value"));
		out.put("resolvingReferences", RunTexts.resolvingReferences("CustomerAddress", "Customer"));
		out.put("computingFrom", RunTexts.computingFrom("Customer", "addressCount", "CustomerAddress"));
		out.put("markingStaged", RunTexts.markingStaged());
		out.put("sourceReady", RunTexts.sourceReady("ECOM_Clients_v2", "ECOM_CLIENTS_V2", null));
		out.put("sourceReadyPlanFailed", RunTexts.sourceReady("ECOM_Clients_v2", "ECOM_CLIENTS_V2", "Table 'X' (not found)."));
		// matching and master screens (2026-10-18): the job's result, an entity's scope, the master build (joined / finished are tested in MatchingMasterRunTextsTest: neither is read back from old English)
		out.put("matchingResultOne", RunTexts.matchingResult(1, 0, 0));
		out.put("matchingResultOther", RunTexts.matchingResult(3, 1, 2));
		out.put("matchingFollowsOne", RunTexts.matchingFollows(1));
		out.put("matchingFollowsOther", RunTexts.matchingFollows(2));
		out.put("matchingStoppedOne", RunTexts.matchingStopped("stopped by admin", 1, 2));
		out.put("matchingStoppedOther", RunTexts.matchingStopped("stopped by admin", 3, 0));
		out.put("scopeAll", RunTexts.matchingScope(true, 81, 81));
		out.put("scopeAllNoCount", RunTexts.matchingScope(true, -1, -1));
		out.put("scopeIncremental", RunTexts.matchingScope(false, 30, 1081));
		out.put("scopeIncrementalNoCount", RunTexts.matchingScope(false, -1, 81));
		out.put("masterQueued", RunTexts.masterQueued());
		out.put("masterTablesOne", RunTexts.masterTables(1, "BPersonnes", "Master"));
		out.put("masterTablesOther", RunTexts.masterTables(3, "BPersonnes", "Master"));
		out.put("masterNoTablesCopy", RunTexts.masterNoTablesCopy());
		out.put("masterNoTablesMatching", RunTexts.masterNoTablesMatching());
		out.put("masterDeclaredOne", RunTexts.masterDeclared(1));
		out.put("masterDeclaredOther", RunTexts.masterDeclared(4));
		out.put("masterResolving", RunTexts.masterResolvingDeferred("Personne"));
		out.put("masterNothingChangedEntity", RunTexts.masterNothingChangedEntity("Personne"));
		out.put("masterCounts", RunTexts.masterEntityCounts("Personne", 234, 234, 0, 0));
		out.put("masterCountsHeld", RunTexts.masterEntityCounts("Personne", 234, 200, 4, 30));
		out.put("masterCopyingOne", RunTexts.masterCopying("Personne", 1));
		out.put("masterCopyingOther", RunTexts.masterCopying("Personne", 534));
		out.put("masterProgress", RunTexts.masterProgress("Personne", 96, -1, 95, 0));
		out.put("masterProgressTotal", RunTexts.masterProgress("Personne", 96, 534, 95, 0));
		out.put("masterProgressHeld", RunTexts.masterProgress("Personne", 96, -1, 95, 1));
		out.put("masterProgressTotalHeld", RunTexts.masterProgress("Personne", 96, 534, 90, 6));
		out.put("masterRowsWrittenScope", RunTexts.masterRowsWrittenScope(12, 40, 0, 1, "changed", 40));
		out.put("masterNothingChanged", RunTexts.masterNothingChanged());
		out.put("masterBuildFinished", RunTexts.masterBuildFinished());
		out.put("masterBuildStopped", RunTexts.masterBuildStopped("stopped by admin"));
		out.put("masterBuildFailedTables", RunTexts.masterBuildFailedStages(true, false));
		out.put("masterBuildFailedCopy", RunTexts.masterBuildFailedStages(false, true));
		out.put("masterBuildFailedBoth", RunTexts.masterBuildFailedStages(true, true));
		out.put("masterBuildFailed", RunTexts.masterBuildFailed("Schema has errors"));
		// step 7 screen counts (2026-10-18): the sentences of a distribution send
		out.put("sent", RunTexts.sent(980, 0, 3, "all", ""));
		out.put("sentDetail", RunTexts.sent(980, 0, 3, "all", "Personne 212, Adresse 534, Achat 234"));
		out.put("sentUnchanged", RunTexts.sent(5, 3, 2, "changed", ""));
		out.put("sentUnchangedDetail", RunTexts.sent(5, 3, 2, "changed", "Personne 5, Adresse 0"));
		final String sentStored = RunTexts.sent(980, 0, 3, "all", "Personne 212, Adresse 534, Achat 234").stored();
		out.put("firstOk", RunTexts.sendEnded(true, "22", "DONE", sentStored));
		out.put("firstOkBare", RunTexts.sendEnded(true, "22", "STOPPED", ""));
		out.put("firstFailed", RunTexts.sendFailed(true, "22", "broker down"));
		out.put("firstFailedBare", RunTexts.sendFailed(true, "22", ""));
		out.put("sendOk", RunTexts.sendEnded(false, "22", "DONE", sentStored));
		out.put("sendOkBare", RunTexts.sendEnded(false, "22", "DONE", ""));
		out.put("sendFailed", RunTexts.sendFailed(false, "22", "broker down - retry"));
		out.put("sendFailedBare", RunTexts.sendFailed(false, "22", ""));
		return out;
	}

	private static void eachCode(final Map<String, KeyTexts.Text> pTexts)
	{
		final java.util.Set<String> covered = new java.util.HashSet<String>();
		for (final Map.Entry<String, KeyTexts.Text> entry : pTexts.entrySet())
		{
			final String name = entry.getKey();
			final KeyTexts.Text text = entry.getValue();
			covered.add(text.key);
			final String stored = text.stored();
			// code round trip: the English first, the marker last, nothing lost
			final EngineMessageCodec.Decoded decoded = EngineMessageCodec.decode(stored);
			check(name + ": the stored form is the English then the marker", decoded.raw.equals(text.english) && stored.endsWith("]") && stored.startsWith(text.english + " [mdmf:" + text.key));
			check(name + ": the code comes back", text.key.equals(decoded.key));
			check(name + ": English display of the coded text is the English", RunPanelTextTest.sameEnglish(RunPanelText.display(stored, EN), text.english));
			final String french = RunPanelText.display(stored, FR);
			check(name + ": French exists and is not the English", !french.equals(text.english) && !french.contains("[mdmf:") && !french.isEmpty());
			// the older rows: the bare English is recognised and shown the same way
			check(name + ": old English is read back as " + text.key, RunTexts.parse(text.english) != null);
			check(name + ": old English displays like the coded text (fr)", RunPanelText.display(text.english, FR).equals(french));
			check(name + ": old English displays like the coded text (en)", RunPanelText.display(text.english, EN).equals(RunPanelText.display(stored, EN)));
			check(name + ": no marker survives in the display", !RunPanelText.display(stored, FR).contains("mdmf") && !RunPanelText.display(stored, EN).contains("mdmf"));
		}
		// the codes the factories above reach: every code of the class except the journal's own words and the fragments / the key of the bare constants
		for (final String key : RunTexts.ENGLISH.keySet())
		{
			if (key.startsWith(RunTexts.P + "ev.") || key.startsWith(RunTexts.P + "frag.") || key.equals(RunTexts.SEE_DETAIL))
			{
				continue;
			}
			check("a factory builds " + key, covered.contains(key) || key.equals(RunTexts.JOINED) || key.equals(RunTexts.MTC_FINISHED) || key.equals(RunTexts.P + "loadFailed.one") || key.equals(RunTexts.P + "loadFailed.other"));
		}
		check("loadFailed one and other", covered.contains(RunTexts.LOAD_FAILED_ONE) && covered.contains(RunTexts.LOAD_FAILED_OTHER));
		// the English sentences are the ones the engine has always written
		check("loaded", RunTexts.loaded().english.equals("Loaded."));
		check("runAccepted", RunTexts.runAccepted(2, 2).english.equals("Run accepted: 2 tables from 2 applications.") && RunTexts.runAccepted(1, 1).english.equals("Run accepted: 1 table from 1 application."));
		check("loadFailed english", RunTexts.loadFailed(2, 2, null).english.equals("2 of 2 tables failed (see detail).") && RunTexts.loadFailed(1, 3, "x").english.equals("1 of 3 table failed (x)."));
		check("creatingModel english", RunTexts.creatingModel("M", 1, 19).english.equals("Creating the landing model M (1 table, 19 fields, about one second each).")
			&& RunTexts.creatingModel("M", 3, 40).english.equals("Creating the landing model M (3 tables, 40 fields, about one second each)."));
		check("copying english", RunTexts.copying("Contact", 2, 2).english.equals("Copying the rows of Contact through Connectors (table 2 of 2).")
			&& RunTexts.copying("Contact", 1, 1).english.equals("Copying the rows of Contact through Connectors."));
		check("rows copied english", RunTexts.rowsCopied("Contact", 0, false).english.equals("Contact: 0 rows copied.") && RunTexts.rowsCopied("Contact", 1, true).english.equals("Contact: 1 changed row copied."));
		check("key wait english", RunTexts.keyWait(RunTexts.keySummary("email,name", "LLM"), RunTexts.waitReason(KeyTexts.WHY_LLM, 62, Boolean.TRUE)).english
			.equals("email+name (proposed by the language model) - Suggested by the language model (62%, low confidence, unique across the whole source, waiting for a person to confirm it."));
		check("lookingKey english", RunTexts.lookingKey(true).english.equals("Looking for the key: reading a sample of rows, then asking the language model if no column is certain.")
			&& RunTexts.lookingKey(false).english.equals("Looking for the key: reading a sample of rows."));
	}

	private static boolean sameEnglish(final String pShown, final String pBuiltIn)
	{
		return pShown.equals(pBuiltIn) || pShown.replace("when the tables were found from the data", "during a deduced scan").equals(pBuiltIn);
	}

	private static void frenchWords()
	{
		check("built", RunPanelText.display(RunTexts.built().stored(), FR).equals("Construite."));
		check("loaded", RunPanelText.display("Loaded.", FR).equals("Chargé."));
		check("created", RunPanelText.display("Created.", FR).equals("Créé."));
		check("waits", RunPanelText.display("Waits for the key.", FR).equals("Attend la clé."));
		check("chosen by a person", RunPanelText.display("customerId (chosen by a person)", FR).equals("customerId (choisie par une personne)"));
		check("declared by the source", RunPanelText.display(RunTexts.keySummary("id", "SOURCE").stored(), FR).equals("id (déclarée par la source)"));
		check("table step failed", RunPanelText.display("The table step failed: nothing to build a flow on.", FR).equals("L'étape de la table a échoué : rien sur quoi construire un flux."));
		check("flow not created", RunPanelText.display("The landing flow has not been created yet.", FR).equals("Le flux du landing n'a pas encore été créé."));
		check("load failed", RunPanelText.display("2 of 2 tables failed (see detail).", FR).equals("2 tables sur 2 en échec (voir le détail)."));
		check("load failed with a reason", RunPanelText.display(RunTexts.loadFailed(1, 2, "connection refused").stored(), FR).equals("1 table sur 2 en échec (connection refused)."));
		check("run accepted", RunPanelText.display("Run accepted: 2 tables from 2 applications.", FR).equals("Exécution acceptée : 2 tables de 2 applications."));
		check("looking", RunPanelText.display("Looking for the key: reading a sample of rows.", FR).equals("Recherche de la clé : lecture d'un échantillon de lignes."));
		check("building", RunPanelText.display("Building the landing tables.", FR).equals("Construction des tables du landing."));
		check("creating the model", RunPanelText.display("Creating the landing model X (1 table, 19 fields, about one second each).", FR)
			.equals("Création du modèle du landing X (1 table, 19 champs, environ une seconde chacun)."));
		check("dataset", RunPanelText.display("Creating the landing dataset X in Y.", FR).equals("Création du jeu de données du landing X dans Y."));
		check("parallel", RunPanelText.display("Up to 2 applications load at the same time (mdm.factory.landing.parallelSources).", FR)
			.equals("Jusqu'à 2 applications sont chargées en même temps (mdm.factory.landing.parallelSources)."));
		check("copying", RunPanelText.display("Copying the rows of Contact through Connectors (table 2 of 2).", FR).equals("Copie des lignes de Contact par Connectors (table 2 sur 2)."));
		check("rows copied", RunPanelText.display("Contact: 0 rows copied.", FR).equals("Contact : 0 lignes copiées."));
		check("rows copied, thousands are not grouped", RunPanelText.display(RunTexts.rowsCopied("Contact", 12345, true).stored(), FR).equals("Contact : 12345 lignes modifiées copiées."));
		check("finished", RunPanelText.display("Landing run finished: 12 of 12 stages done.", FR).equals("Exécution du landing terminée : 12 étapes sur 12 faites."));
		check("paused", RunPanelText.display("Landing run paused, waiting for a key choice.", FR).equals("Exécution du landing en pause, en attente du choix d'une clé."));
		check("failures", RunPanelText.display("Landing run finished with failures: 2 of 4 tables were not loaded (A.T, B.U).", FR)
			.equals("Exécution du landing terminée avec des échecs : 2 tables sur 4 n'ont pas été chargées (A.T, B.U)."));
		check("the waiting cell, nested", RunPanelText.display("email+name (proposed by the language model) - Suggested by the language model (62%, low confidence, unique across the whole source, waiting for a person to confirm it.", FR)
			.equals("email+name (proposée par le modèle de langage) - Suggérée par le modèle de langage (62 %, confiance faible, unique sur toute la source, en attente de confirmation par une personne)."));
		check("the waiting cell, nested and coded", RunPanelText.display(RunTexts.keyWait(RunTexts.keySummary("a", "RULE"), RunTexts.noColumnCertain()).stored(), FR)
			.equals("a (proposée par la règle de nommage) - Aucune colonne n'est certaine."));
		check("a stop reason is the engine's own words", RunPanelText.display("Stopped (stopped by admin).", FR).equals("Arrêté (stopped by admin)."));
	}

	// ------------------------------------------------------------------------------------------------------------- the journal

	private static void journal()
	{
		final List<LandingRunProgress.TableRef> refs = new ArrayList<LandingRunProgress.TableRef>();
		refs.add(new LandingRunProgress.TableRef("1", "ECOM_Clients", "ECOM"));
		refs.add(new LandingRunProgress.TableRef("2", "Contact", "SALESFORCE"));
		final LandingRunProgress progress = LandingRunProgress.newRun(refs);
		progress.note(RunTexts.runAccepted(2, 2).stored());
		progress.start("1", LandingRunProgress.KEY, RunTexts.lookingKey(true).stored());
		progress.finish("1", LandingRunProgress.KEY, true, RunTexts.keySummary("customerId", "USER").stored());
		progress.startAll(Arrays.asList("1"), LandingRunProgress.TABLE, RunTexts.buildingTables().stored());
		progress.activityAll(Arrays.asList("1"), LandingRunProgress.TABLE, RunTexts.creatingModel("ECOM_Landing", 1, 19).stored());
		progress.finish("1", LandingRunProgress.TABLE, true, RunTexts.built().stored());
		progress.waitingStage("2", LandingRunProgress.FLOW, RunTexts.waitsForKey().stored());
		progress.waiting("2", RunTexts.keyWait(RunTexts.keySummary("id", "RULE"), RunTexts.noColumnCertain()).stored());
		progress.finish("2", LandingRunProgress.LOAD, false, "connection refused: host {x}");
		progress.start("2", LandingRunProgress.TABLE, RunTexts.buildingTables().stored());
		progress.skip("2", LandingRunProgress.TABLE, RunTexts.tableFailedNoLoad().stored());
		progress.startAll(Arrays.asList("1", "2"), LandingRunProgress.LOAD, RunTexts.loadingData().stored());
		progress.activityAll(Arrays.asList("1", "2"), LandingRunProgress.LOAD, RunTexts.rowsCopied("Contact", 0, false).stored());

		final List<String> english = new ArrayList<String>();
		final List<String> french = new ArrayList<String>();
		for (final LandingRunProgress.Event event : progress.events())
		{
			english.add(EngineMessageCodec.stripMarkers(event.text));
			french.add(RunPanelText.event(event.text, FR));
			check("the journal line has one marker at most and no line break: " + event.text, !event.text.contains("\n") && event.text.indexOf("[mdmf:") == event.text.lastIndexOf("[mdmf:"));
		}
		check("journal English is the sentence it has always been", english.contains("ECOM_Clients \u00B7 Key done: customerId (chosen by a person)")
			&& english.contains("ECOM_Clients \u00B7 Table done: Built.") && english.contains("ECOM_Clients, Contact \u00B7 Loading the data.")
			&& english.contains("Contact \u00B7 Flow waits for the key: Waits for the key.") && english.contains("Contact \u00B7 Load failed: connection refused: host {x}"));
		check("journal French: key done", french.contains("ECOM_Clients · Clé : terminé - customerId (choisie par une personne)"));
		check("journal French: table done", french.contains("ECOM_Clients · Table : terminé - Construite."));
		check("journal French: activity", french.contains("ECOM_Clients, Contact · Chargement des données."));
		check("journal French: rows", french.contains("ECOM_Clients, Contact · Contact : 0 lignes copiées."));
		check("journal French: model", french.contains("ECOM_Clients · Création du modèle du landing ECOM_Landing (1 table, 19 champs, environ une seconde chacun)."));
		// (the journal keeps the newest twelve lines: the first two of the run are checked on a run of their own)
		final LandingRunProgress first = LandingRunProgress.newRun(refs);
		first.note(RunTexts.runAccepted(2, 2).stored());
		first.start("1", LandingRunProgress.KEY, RunTexts.lookingKey(true).stored());
		final List<String> firstFrench = new ArrayList<String>();
		final List<String> firstEnglish = new ArrayList<String>();
		for (final LandingRunProgress.Event event : first.events())
		{
			firstFrench.add(RunPanelText.event(event.text, FR));
			firstEnglish.add(EngineMessageCodec.stripMarkers(event.text));
		}
		check("journal: a note", firstFrench.contains("Exécution acceptée : 2 tables de 2 applications.") && firstEnglish.contains("Run accepted: 2 tables from 2 applications."));
		check("journal: the key stage starting", firstFrench.contains("ECOM_Clients \u00B7 Recherche de la clé : lecture d'un échantillon de lignes, puis question au modèle de langage si aucune colonne n'est certaine.")
			&& firstEnglish.contains("ECOM_Clients \u00B7 Looking for the key: reading a sample of rows, then asking the language model if no column is certain."));
		check("journal French: waiting flow", french.contains("Contact · Flux : attend la clé - Attend la clé."));
		check("journal French: needs your eye, nested", french.contains("Contact · Clé : à vérifier - id (proposée par la règle de nommage) - Aucune colonne n'est certaine."));
		check("journal French: free text kept", french.contains("Contact · Chargement : en échec - connection refused: host {x}"));
		check("journal French: skipped", french.contains("Contact · Table : ignoré - L'étape de la table a échoué : rien à charger."));
		// the same lines as stored BEFORE the lot (English, no marker) display the same in French
		for (final LandingRunProgress.Event event : progress.events())
		{
			final String plain = EngineMessageCodec.stripMarkers(event.text);
			check("old journal line displays like the new one: " + plain, RunPanelText.event(plain, FR).equals(RunPanelText.event(event.text, FR)));
			check("old journal line, English: " + plain, RunPanelText.event(plain, EN).equals(RunPanelText.event(event.text, EN)));
		}
		// a note that is plain text (a free line) is shown as it is
		check("a free line", RunPanelText.event("some free note", FR).equals("some free note"));
		// a line is cut at 240 characters of ENGLISH and keeps its marker
		final LandingRunProgress longRun = LandingRunProgress.newRun(refs);
		longRun.note(new String(new char[400]).replace('\0', 'x') + " " + RunTexts.loaded().stored());
		final String cut = longRun.events().get(0).text;
		check("a long line keeps its marker", cut.endsWith("[mdmf:wizard.runtext.loaded]") && EngineMessageCodec.decode(cut).raw.length() == 240);

		// the stored grid keeps the markers; REST readers get none
		final String json = progress.toJson();
		check("the stored progress carries the codes", json.contains("[mdmf:wizard.runtext.built]") && json.contains("[mdmf:wizard.runtext.keySummary.USER|s:customerId]"));
		final String plain = EngineMessageCodec.stripMarkers(json);
		check("REST readers get the English only", !plain.contains("mdmf") && LandingRunProgress.parse(plain).table("1").cell(LandingRunProgress.TABLE).summary.equals("Built."));
		check("the grid round-trips", LandingRunProgress.parse(json).table("1").cell(LandingRunProgress.KEY).summary.equals(RunTexts.keySummary("customerId", "USER").stored()));
	}

	private static void storedProgress()
	{
		// a run stored before the lot: only English; the panel words it all
		final String old = "{\"startedAt\":1,\"updatedAt\":2,\"events\":[{\"at\":2,\"text\":\"ECOM_Clients \\u00B7 Key done: customerId (chosen by a person)\"}],"
			+ "\"tables\":[{\"id\":\"1\",\"tableName\":\"ECOM_Clients\",\"sourceSystem\":\"ECOM\",\"cells\":{\"KEY\":{\"state\":\"DONE\",\"summary\":\"customerId (chosen by a person)\"},"
			+ "\"TABLE\":{\"state\":\"DONE\",\"summary\":\"Built.\"},\"FLOW\":{\"state\":\"FAILED\",\"summary\":\"The landing flow has not been created yet.\"},"
			+ "\"LOAD\":{\"state\":\"FAILED\",\"summary\":\"2 of 2 tables failed (see detail).\"}}}]}";
		final LandingRunProgress progress = LandingRunProgress.parse(old);
		check("old cells", RunPanelText.display(progress.table("1").cell("TABLE").summary, FR).equals("Construite.") && RunPanelText.display(progress.table("1").cell("LOAD").summary, FR)
			.equals("2 tables sur 2 en échec (voir le détail)."));
		check("old event", RunPanelText.event(progress.events().get(0).text, FR).equals("ECOM_Clients · Clé : terminé - customerId (choisie par une personne)"));
	}

	// ------------------------------------------------------------------------------------------------------------- end of run

	private static void endOfRun()
	{
		final List<LandingRunProgress.TableRef> refs = new ArrayList<LandingRunProgress.TableRef>();
		refs.add(new LandingRunProgress.TableRef("1", "A", "ECOM"));
		refs.add(new LandingRunProgress.TableRef("2", "B", "SALESFORCE"));
		final LandingRunProgress progress = LandingRunProgress.newRun(refs);
		for (final String id : new String[] { "1", "2" })
		{
			for (final String stage : LandingRunProgress.STAGES)
			{
				progress.finish(id, stage, true, "x");
			}
		}
		check("final message English (unchanged)", LandingChainOutcome.finalMessage(progress).equals("Landing run finished: 8 of 8 stages done."));
		check("final text coded", LandingChainOutcome.finalText(progress).stored().equals("Landing run finished: 8 of 8 stages done. [mdmf:wizard.runtext.finished|n:8|n:8]"));
		check("final text French", RunPanelText.display(LandingChainOutcome.finalText(progress).stored(), FR).equals("Exécution du landing terminée : 8 étapes sur 8 faites."));
		progress.finish("2", LandingRunProgress.LOAD, false, "boom");
		check("failures message English (unchanged)", LandingChainOutcome.finalMessage(progress).equals("Landing run finished with failures on: SALESFORCE."));
		check("failures message French", RunPanelText.display(LandingChainOutcome.finalText(progress).stored(), FR).equals("Exécution du landing terminée avec des échecs sur : SALESFORCE."));
		final PartialFailures.Failures failures = PartialFailures.fromLanding(progress);
		check("partial message English (unchanged)", PartialFailures.landingMessage(failures).equals("Landing run finished with failures: 1 of 2 tables was not loaded (SALESFORCE.B)."));
		check("partial message other forms", PartialFailures.landingMessage(new PartialFailures.Failures(4, Arrays.asList(new PartialFailures.Item("1", "A", ""), new PartialFailures.Item("2", "B", ""))))
			.equals("Landing run finished with failures: 2 of 4 tables were not loaded (A, B)."));
		check("partial message French", RunPanelText.display(PartialFailures.landingText(failures).stored(), FR)
			.equals("Exécution du landing terminée avec des échecs : 1 table sur 2 n'a pas été chargée (SALESFORCE.B)."));
		check("partial message French, plural", RunPanelText.display(PartialFailures.landingMessage(new PartialFailures.Failures(4, Arrays.asList(new PartialFailures.Item("1", "A", ""), new PartialFailures.Item("2", "B", "")))), FR)
			.equals("Exécution du landing terminée avec des échecs : 2 tables sur 4 n'ont pas été chargées (A, B)."));
		// the generic stop sentence of the queue and the step messages
		check("stopped sentence", RunTexts.stopped("stopped by admin").english.equals("Stopped (stopped by admin).") && RunTexts.runStopped("the server is shutting down").english.equals("Landing run stopped (the server is shutting down)."));
		check("messages stay readable English with the marker cut off", EngineMessageCodec.decode(RunTexts.runPaused().stored()).raw.equals("Landing run paused, waiting for a key choice."));
		check("the nothing-to-do and failure rules still read the English first", JobOutcome.display("LANDING_RUN", "DONE", RunTexts.finishedFailuresOn("A").stored()).equals(JobOutcome.PARTIAL));
	}

	private static void partialFailures()
	{
		final String reason = RunTexts.loadFailed(2, 2, null).stored();
		final PartialFailures.Failures failures = new PartialFailures.Failures(2, Arrays.asList(new PartialFailures.Item("1", "ECOM.Clients", reason),
			new PartialFailures.Item("2", "SF.Account", new String(new char[400]).replace('\0', 'z'))));
		final String json = PartialFailures.toJson(failures);
		final PartialFailures.Failures back = PartialFailures.parse(json);
		check("a coded reason keeps its marker through the stored detail", back.items.get(0).reason.equals(reason));
		check("a long free reason is still cut", back.items.get(1).reason.length() == 200 && back.items.get(1).reason.endsWith("…"));
		check("French reason", RunPanelText.display(back.items.get(0).reason, FR).equals("2 tables sur 2 en échec (voir le détail)."));
		final int budget = 140;
		final String cut = EngineMessageCodec.fit(new String(new char[300]).replace('\0', 'a') + " [mdmf:wizard.runtext.loaded]", budget);
		check("fit keeps the marker", cut.length() == budget && cut.endsWith("[mdmf:wizard.runtext.loaded]"));
	}

	private static void recentTasks()
	{
		final RecentTasks.Task done = new RecentTasks.Task();
		done.jobId = "7";
		done.kind = "LANDING_RUN";
		done.status = "DONE";
		done.requestedBy = "admin";
		done.message = RunTexts.finished(12, 12).stored();
		done.endedAt = new Date(1000L);
		done.startedAt = new Date(0L);
		final RecentTasks.Task failed = new RecentTasks.Task();
		failed.jobId = "8";
		failed.kind = "LANDING_RUN";
		failed.status = "FAILED";
		failed.requestedBy = "admin";
		failed.message = RunTexts.finishedFailuresOn("ECOM").stored();
		failed.endedAt = new Date(1000L);
		failed.startedAt = new Date(0L);
		final RecentTasks.Task paused = new RecentTasks.Task();
		paused.jobId = "9";
		paused.kind = "LANDING_RUN";
		paused.status = "DONE";
		paused.requestedBy = "admin";
		paused.message = RunTexts.runPaused().stored();
		paused.endedAt = new Date(1000L);
		final RecentTasks.Task oldRow = new RecentTasks.Task();
		oldRow.jobId = "10";
		oldRow.kind = "LANDING_RUN";
		oldRow.status = "DONE";
		oldRow.requestedBy = "admin";
		oldRow.message = "Landing run finished: 3 of 3 stages done.";
		oldRow.endedAt = new Date(1000L);
		final List<RecentTasks.Task> tasks = Arrays.asList(done, failed, paused, oldRow);
		final String fr = RecentTasks.render(tasks, FR, new Date(5000L), null);
		check("Recent tasks, French: finished", fr.contains("Exécution du landing terminée : 12 étapes sur 12 faites.") || fr.contains("Exécution du landing terminée : 12 étapes sur 12 faites."));
		check("Recent tasks, French: failures", fr.contains("terminée avec des échecs sur : ECOM."));
		check("Recent tasks, French: paused", fr.contains("en pause, en attente du choix d"));
		check("Recent tasks, French: a row stored before the lot", fr.contains("3 étapes sur 3 faites"));
		check("no marker on screen", !fr.contains("mdmf:"));
		final String en = RecentTasks.render(tasks, EN, new Date(5000L), null);
		check("Recent tasks, English", en.contains("Landing run finished: 12 of 12 stages done.") && en.contains("Landing run paused, waiting for a key choice.") && !en.contains("mdmf:"));
	}

	/** The live panel as the server writes it: a running landing job, a table waiting for a key, a failure, in both languages. */
	private static void serverRenderedPanel()
	{
		for (final Locale locale : new Locale[] { FR, EN })
		{
			final WizardSnapshot snapshot = new WizardSnapshot();
			final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
			project.id = "1";
			project.code = "B2C";
			project.label = "B2C";
			snapshot.project = project;
			final WizardSnapshot.SourceInfo ecom = new WizardSnapshot.SourceInfo();
			ecom.id = "s1";
			ecom.sourceSystem = "ECOM";
			final WizardSnapshot.TableInfo clients = new WizardSnapshot.TableInfo();
			clients.id = "1";
			clients.tableName = "ECOM_Clients";
			clients.selected = true;
			clients.status = "PROPOSED";
			ecom.tables.add(clients);
			final WizardSnapshot.TableInfo contact = new WizardSnapshot.TableInfo();
			contact.id = "2";
			contact.tableName = "Contact";
			contact.selected = true;
			contact.status = "PROPOSED";
			ecom.tables.add(contact);
			snapshot.sources.add(ecom);
			final List<LandingRunProgress.TableRef> refs = new ArrayList<LandingRunProgress.TableRef>();
			refs.add(new LandingRunProgress.TableRef("1", "ECOM_Clients", "ECOM"));
			refs.add(new LandingRunProgress.TableRef("2", "Contact", "ECOM"));
			final LandingRunProgress progress = LandingRunProgress.newRun(refs);
			progress.finish("1", LandingRunProgress.KEY, true, RunTexts.keySummary("customerId", "USER").stored());
			progress.finish("1", LandingRunProgress.TABLE, true, RunTexts.built().stored());
			progress.waiting("2", RunTexts.keyWait(RunTexts.keySummary("id", "RULE"), RunTexts.noColumnCertain()).stored());
			progress.waitingStage("2", LandingRunProgress.FLOW, RunTexts.waitsForKey().stored());
			progress.start("1", LandingRunProgress.FLOW, RunTexts.creatingFlow().stored());
			progress.finish("2", LandingRunProgress.LOAD, false, RunTexts.loadFailed(2, 2, null).stored());
			final WizardPage.Model model = new WizardPage.Model();
			model.administrator = true;
			model.designer = true;
			model.locale = locale;
			model.snapshot = snapshot;
			model.stepStates = WizardRules.stepStates(snapshot);
			model.currentStep = WizardRules.STEP_LANDING;
			model.landingRunExists = true;
			model.landingRunRunning = true;
			model.landingRunProgress = progress;
			model.nowMs = progress.updatedAt() + 1000L;
			final String html = WizardPage.render(model);
			final boolean fr = locale == FR;
			check("no marker reaches the page (" + locale + ")", !html.contains("[mdmf:"));
			check("the jobs banner asks for the page's language (" + locale + ")", html.contains("/jobs?lang=" + (locale == FR ? "fr" : "en")));
			check("built cell (" + locale + ")", html.contains(fr ? "Construite." : "Built."));
			check("running flow activity (" + locale + ")", html.contains(fr ? "Création du flux Connectors qui copie cette table." : "Creating the Connectors flow that copies this table."));
			check("waiting key cell (" + locale + ")", html.contains(fr ? "id (proposée par la règle de nommage) - Aucune colonne n&#39;est certaine." : "id (proposed by the naming rule) - No column is certain."));
			check("passive waiting cell (" + locale + ")", html.contains(fr ? "Attend la clé." : "Waits for the key."));
			check("failed load (" + locale + ")", html.contains(fr ? "2 tables sur 2 en échec (voir le détail)." : "2 of 2 tables failed (see detail)."));
			check("journal (" + locale + ")", html.contains(fr ? "Contact \u00B7 Clé : à vérifier - id (proposée par la règle de nommage)" : "Contact \u00B7 Key needs your eye: id (proposed by the naming rule)"));
			check("the header's 'now' line (" + locale + ")", html.contains(fr ? "ECOM_Clients \u00B7 Création du flux Connectors qui copie cette table." : "ECOM_Clients \u00B7 Creating the Connectors flow that copies this table."));
			if (fr)
			{
				check("no English sentence of the run is left on the French page", !html.contains("Waits for the key") && !html.contains("Built.") && !html.contains("tables failed") && !html.contains("No column is certain"));
			}
		}
	}

	private static void foreignAndFree()
	{
		final String llm = "The id column is unique in the sample.";
		check("free text untouched", RunPanelText.display(llm, FR).equals(llm) && RunPanelText.event(llm, FR).equals(llm));
		check("a connector error is untouched", RunPanelText.display("Connection refused (Connection refused)", FR).equals("Connection refused (Connection refused)"));
		check("a marker of another family of the module is removed, text kept", RunPanelText.display("Something [mdmf:wizard.other|s:x]", FR).equals("Something"));
		check("a code this class lacks falls back to the English", RunPanelText.display("Some text [mdmf:wizard.runtext.nope|s:x]", FR).equals("Some text"));
		check("empty and null", RunPanelText.display("", FR).isEmpty() && RunPanelText.display(null, FR).isEmpty() && RunPanelText.event(null, FR).isEmpty());
		check("null locale reads English", RunPanelText.display(RunTexts.built().stored(), null).equals("Built."));
		// a key rationale carried in a cell is worded by the key family
		check("a key rationale keeps its words", RunPanelText.display(KeyTexts.declared().stored(), FR).equals("Clé primaire déclarée par la source."));
		// a refusal the Factory raised with a message code is worded from that code (nested in a cell)
		final String refusal = EngineMessageCodec.encode("No landing dataspace for X.", "wizard.keytext.chosen", new Object[0], 300);
		check("another family's code is worded by that family", RunPanelText.display(refusal, FR).equals("Clé choisie par une personne dans l'assistant."));
		check("the nested text of an argument is worded first", RunPanelText.display(RunTexts.keyWait(RunTexts.keySummary("a", "LLM"), RunTexts.noColumnCertain()).stored(), FR).startsWith("a (proposée par le modèle de langage)"));
		// EngineMessageCodec.Decoded.sentence (Recent tasks failures, EngineNotice) words the run's codes too
		check("Decoded.sentence for a run code", EngineMessageCodec.decode(RunTexts.loadFailed(1, 2, "x").stored()).sentence(FR).equals("1 table sur 2 en échec (x)."));
	}

	/** What the jobs route hands the wizard's script: the grid of a running landing job already worded in the page's language. */
	private static void jobsAnswer()
	{
		final List<LandingRunProgress.TableRef> refs = new ArrayList<LandingRunProgress.TableRef>();
		refs.add(new LandingRunProgress.TableRef("1", "ECOM_Clients", "ECOM"));
		final LandingRunProgress progress = LandingRunProgress.newRun(refs);
		progress.start("1", LandingRunProgress.KEY, RunTexts.lookingKey(false).stored());
		progress.finish("1", LandingRunProgress.KEY, true, RunTexts.keySummary("customerId", "USER").stored());
		progress.startAll(Arrays.asList("1"), LandingRunProgress.TABLE, RunTexts.buildingTables().stored());
		progress.activityAll(Arrays.asList("1"), LandingRunProgress.TABLE, RunTexts.creatingModel("ECOM_Landing", 1, 19).stored());
		progress.finish("1", LandingRunProgress.LOAD, false, "connection refused: host {x}");
		final String stored = progress.toJson();
		check("the stored grid holds the codes", stored.contains("[mdmf:wizard.runtext."));
		final LandingRunProgress fr = LandingRunProgress.parse(RunPanelText.localizeProgressJson(stored, FR));
		check("French grid: no marker left", !RunPanelText.localizeProgressJson(stored, FR).contains("mdmf"));
		check("French grid: summary", fr.table("1").cell(LandingRunProgress.KEY).summary.equals("customerId (choisie par une personne)"));
		check("French grid: activity", fr.table("1").cell(LandingRunProgress.TABLE).activity.equals("Création du modèle du landing ECOM_Landing (1 table, 19 champs, environ une seconde chacun)."));
		check("French grid: free text kept", fr.table("1").cell(LandingRunProgress.LOAD).summary.equals("connection refused: host {x}"));
		check("French grid: journal", fr.events().get(fr.events().size() - 1).text.endsWith("Recherche de la clé : lecture d'un échantillon de lignes.")
			&& fr.events().get(0).text.contains("Chargement : en échec - connection refused: host {x}"));
		check("French grid: state and times untouched", fr.table("1").cell(LandingRunProgress.KEY).state.equals(LandingRunProgress.DONE) && fr.startedAt() == progress.startedAt());
		final LandingRunProgress en = LandingRunProgress.parse(RunPanelText.localizeProgressJson(stored, EN));
		check("English grid is the English", en.table("1").cell(LandingRunProgress.KEY).summary.equals("customerId (chosen by a person)")
			&& en.table("1").cell(LandingRunProgress.TABLE).activity.equals("Creating the landing model ECOM_Landing (1 table, 19 fields, about one second each)."));
		check("a grid written before the lot is worded too", LandingRunProgress.parse(RunPanelText.localizeProgressJson(EngineMessageCodec.stripMarkers(stored), FR))
			.table("1").cell(LandingRunProgress.KEY).summary.equals("customerId (choisie par une personne)"));
		check("not a grid: markers removed", RunPanelText.localizeProgressJson("not json [mdmf:wizard.runtext.built]", FR).equals("not json")
			&& RunPanelText.localizeProgressJson("[1,2]", FR).equals("[1,2]") && RunPanelText.localizeProgressJson("", FR).isEmpty());
	}

	/**
	 * Lot M12 (2026-10-18): the line under "Run matching" read the step's stored English whatever the language. The step keeps the engine's English, the job of the same run
	 * keeps it with the codes; the reader takes the coded one when it is the same sentence, the page words it, and the polling answer follows the language of the page.
	 */
	private static void matchingRunLine()
	{
		final String coded = RunTexts.joined(RunTexts.matchingResult(2, 0, 0).stored(), RunTexts.matchingFollows(1).stored()).stored();
		final String english = EngineMessageCodec.decode(coded).raw;
		check("the stored English is the sentence of the live run", english.equals("2 entities run, 0 not applied, 0 refused. 1 child object has no plan of its own: its records follow the matching of the parent."));
		check("the reader takes the job's coded text when it is the same sentence", com.on.ebx.factory.ebx.MatchingActivityReader.codedMessage(english, coded).equals(coded));
		check("a job message that says something else is not this step's", com.on.ebx.factory.ebx.MatchingActivityReader.codedMessage(english, RunTexts.matchingResult(1, 0, 0).stored()).equals(english));
		check("an uncoded job message leaves the step's", com.on.ebx.factory.ebx.MatchingActivityReader.codedMessage(english, english).equals(english)
			&& com.on.ebx.factory.ebx.MatchingActivityReader.codedMessage(english, null).equals(english) && com.on.ebx.factory.ebx.MatchingActivityReader.codedMessage(null, null).isEmpty());
		final String chosen = com.on.ebx.factory.ebx.MatchingActivityReader.codedMessage(english, coded);
		final String frLine = RunPanelText.display(chosen, FR);
		check("French line: " + frLine, frLine.contains("Objets m\u00E9tier ex\u00E9cut\u00E9s") && frLine.contains("objet enfant") && !frLine.contains("entities") && !frLine.contains("mdmf"));
		check("English line", RunPanelText.display(chosen, EN).equals(english));
		// the page and the polling answer
		final com.on.ebx.factory.matching.MatchingActivity activity = new com.on.ebx.factory.matching.MatchingActivity();
		activity.kind = com.on.ebx.factory.matching.MatchingActivity.RUN;
		activity.status = com.on.ebx.factory.matching.MatchingActivity.DONE;
		activity.message = chosen;
		final com.fasterxml.jackson.databind.node.ObjectNode polled = activity.toJson(FR);
		check("the polling answer is worded in the page's language", !polled.path("message").asText().contains("mdmf") && !polled.path("message").asText().contains("entities run"));
		check("without a language the engine's English, no marker", activity.toJson().path("message").asText().equals(english));
		final com.on.ebx.factory.matching.MatchingActivity.Entity entity = new com.on.ebx.factory.matching.MatchingActivity.Entity();
		entity.name = "Client";
		entity.status = com.on.ebx.factory.matching.MatchingActivity.DONE;
		entity.message = RunTexts.matchingFinished("Client", "DONE", 534, 0, 0, RunTexts.matchingScope(true, 534, 534).stored(), "").stored();
		activity.entities.add(entity);
		final String entityFr = activity.toJson(FR).path("entities").path(0).path("message").asText();
		check("the entity's scope sentence is French too: " + entityFr, !entityFr.contains("Scope:") && !entityFr.contains("mdmf") && entityFr.length() > 20);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
