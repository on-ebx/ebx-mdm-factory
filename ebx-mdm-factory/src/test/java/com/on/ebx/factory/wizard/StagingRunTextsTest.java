package com.on.ebx.factory.wizard;

import java.util.Arrays;
import java.util.Locale;

import com.on.ebx.factory.landing.RunTexts;
import com.on.ebx.factory.staging.ProposalProgress;
import com.on.ebx.factory.staging.RunProgress;

/**
 * Staging screens (2026-10-17): the staging proposal and the staging build in the viewer's language - the sentences the live panels, the journal, the job messages and the
 * sources job write (codes as for the landing run, RunTexts), read back for a French page, in the old English too, through the server-rendered panels and the answer the
 * jobs route hands the script. JUnit-free: run the main, a failure throws.
 */
public final class StagingRunTextsTest
{
	private static final Locale FR = Locale.FRENCH;

	public static void main(final String[] pArgs)
	{
		StagingRunTextsTest.theReportedSentencesInFrench();
		StagingRunTextsTest.oldEnglishIsReadToo();
		StagingRunTextsTest.theJournalWordsAWholeSentenceNotItsDot();
		StagingRunTextsTest.aLongCodedLineKeepsItsMarker();
		StagingRunTextsTest.theLiveBuildPanelIsFrench();
		StagingRunTextsTest.theProposalPanelIsFrench();
		StagingRunTextsTest.theJobsAnswerIsWordedForTheStagingJobs();
		System.out.println("StagingRunTextsTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static String fr(final String pStored)
	{
		return RunPanelText.display(pStored, FR);
	}

	private static void theReportedSentencesInFrench()
	{
		check("Proposal run finished.".equals(RunPanelText.display(RunTexts.proposalFinished().stored(), Locale.ENGLISH)), "English stays");
		check("Exécution de la proposition terminée.".equals(fr(RunTexts.proposalFinished().stored())), fr(RunTexts.proposalFinished().stored()));
		check("Construction du staging terminée.".equals(fr(RunTexts.buildFinished().stored())), "build finished");
		check("Mise à jour du modèle du staging Tiers_Staging avec les nouvelles décisions.".equals(fr(RunTexts.updatingModel("Tiers_Staging").stored())), "updating the model");
		check("Prêt : application ECOM ajoutée (source S1).".equals(fr(RunTexts.sourceReady("ECOM", "S1", null).stored())), "ready");
		check(fr(RunTexts.sourceReady("ECOM", "S1", "Table X.").stored()).endsWith("la planification de ses tables du landing a échoué : Table X."), "ready, plan failed keeps the free reason");
		check("Customer · ECOM : 96 lignes lues sur 165, 95 écrites, 1 mises de côté.".equals(fr(RunTexts.loadProgress("Customer", "ECOM", 96, 165, 95, 0, 1).stored())),
			fr(RunTexts.loadProgress("Customer", "ECOM", 96, 165, 95, 0, 1).stored()));
		check("Lignes écrites : 394 ; lignes source lues : 534 (0 inchangées, 140 mises de côté).".equals(fr(RunTexts.rowsWritten(394, 534, 0, 140).stored())), "rows written");
		check(fr(RunTexts.notRunStopped("stopped by admin").stored()).startsWith("Non exécutée"), "not run");
		check(fr(RunTexts.buildFailed("Schema has errors").stored()).equals("Échec de la construction du staging : Schema has errors"), "a failure keeps its free tail");
		check(!fr(RunTexts.proposalFailed("l'erreur").stored()).contains("mdmf"), "no marker");
	}

	private static void oldEnglishIsReadToo()
	{
		check("Exécution de la proposition terminée.".equals(fr("Proposal run finished.")), "old proposal end");
		check("Construction du staging terminée.".equals(fr("Staging build finished.")), "old build end");
		check("Mise à jour du modèle du staging X avec les nouvelles décisions.".equals(fr("Updating the staging model X with the new decisions.")), "old note");
		check("Prêt : application ECOM ajoutée (source S1).".equals(fr("Ready: application ECOM added (source S1).")), "old ready");
		check(fr("Ready: application ECOM added (source S1), but the landing plan failed: boom (x).").equals("Prêt : application ECOM ajoutée (source S1), mais la planification de ses tables du landing a échoué : boom (x)."),
			"the failure variant is not taken for the plain one: " + fr("Ready: application ECOM added (source S1), but the landing plan failed: boom (x)."));
		check(fr("394 row(s) written from 534 source row(s) read (0 unchanged, 140 held back).").startsWith("Lignes écrites : 394"), "old rows written");
		check(fr("Not run: stopped (stopped by admin).").startsWith("Non exécutée : arrêtée (stopped by admin)"), "specific before generic");
		check("Non exécutée : stopped by admin.".equals(fr("Not run: stopped by admin.")), "the build's own shape");
		check(fr("Staging build failed: the model step did not complete - see the grid below for why.").startsWith("Échec de la construction du staging : l'étape du modèle"), "specific failure");
		check(fr("Customer · ECOM: reading 1 row.").equals("Customer · ECOM : lecture de 1 ligne."), "old reading");
		check("Some connector error".equals(fr("Some connector error")), "free text stays");
	}

	/** The staging sentences carry their own dot: the journal words the line as ONE sentence, the landing's "table . Key done: ..." lines still split. */
	private static void theJournalWordsAWholeSentenceNotItsDot()
	{
		final String coded = RunTexts.reading("Customer", "ECOM", 160).stored();
		check("Customer · ECOM : lecture de 160 lignes.".equals(RunPanelText.event(coded, FR)), RunPanelText.event(coded, FR));
		check("Customer · ECOM : lecture de 160 lignes.".equals(RunPanelText.event("Customer · ECOM: reading 160 rows.", FR)), "old line");
		check(RunPanelText.event("ECOM_Clients · Key done: customerId (chosen by a person)", FR).equals("ECOM_Clients · Clé : terminé - customerId (choisie par une personne)"), RunPanelText.event("ECOM_Clients · Key done: customerId (chosen by a person)", FR));
		check(RunPanelText.event(RunTexts.failedLine(RunTexts.buildFailed("boom").stored()).stored(), FR).startsWith("Échec : Échec de la construction du staging"), "a nested coded text is worded in turn");
	}

	private static void aLongCodedLineKeepsItsMarker()
	{
		final StringBuilder longName = new StringBuilder();
		for (int i = 0; i < 300; i++)
		{
			longName.append('x');
		}
		final RunProgress progress = RunProgress.newRun(Arrays.asList("MODEL", "LOAD"));
		progress.note(RunTexts.preparingModel(longName.toString()).stored());
		final String text = progress.events().get(0).text;
		check(text.contains("…") && text.endsWith("]") && EngineMessageCodec.decode(text).coded(), "the English is cut, the marker is whole");
		check(RunPanelText.event(text, FR).startsWith("Préparation du modèle du staging "), "and the line still reads in French");
	}

	private static WizardPage.Model model(final WizardSnapshot pSnapshot)
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.snapshot = pSnapshot;
		model.stepStates = WizardRules.stepStates(pSnapshot);
		model.administrator = true;
		model.designer = true;
		model.currentStep = WizardRules.STEP_STAGING;
		model.locale = FR;
		return model;
	}

	private static void theLiveBuildPanelIsFrench()
	{
		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		final WizardPage.Model model = StagingRunTextsTest.model(snapshot);
		model.stagingLoadRunRunning = true;
		final RunProgress progress = RunProgress.newRun(Arrays.asList("MODEL", "LOAD"));
		progress.note(RunTexts.buildAccepted().stored());
		progress.start("MODEL", 1000L);
		progress.activity("MODEL", RunTexts.updatingModel("Tiers_Staging").stored());
		model.stagingLoadRunProgress = progress;
		model.stagingLoadRunJobId = "77";
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(html.contains("Mise à jour du modèle du staging Tiers_Staging avec les nouvelles décisions."), "the 'now' line and the journal are French");
		check(html.contains("Construction acceptée."), "the first journal line");
		check(!html.contains("Updating the staging model") && !html.contains("[mdmf:") && !html.contains("Build accepted."), "no English, no marker");
	}

	private static void theProposalPanelIsFrench()
	{
		final WizardPage.Model model = StagingRunTextsTest.model(new WizardSnapshot());
		model.proposalRunRunning = true;
		final ProposalProgress progress = ProposalProgress.newRun();
		progress.skip(ProposalProgress.STRUCTURE_LLM, RunTexts.rulePassIncomplete("refine").stored());
		progress.skip(ProposalProgress.QUALITY, RunTexts.notSelected().stored());
		model.proposalRunProgress = progress;
		final String html = WizardPageTest.section(WizardPage.render(model), 4);
		check(html.contains("La passe de règles ne s'est pas terminée : rien à affiner.") || html.contains("La passe de règles ne s&#39;est pas terminée : rien à affiner."), "a skipped stage's reason is French");
		check(html.contains("Non sélectionnée.") && !html.contains("Not selected.") && !html.contains("[mdmf:"), "no English, no marker");
	}

	private static void theJobsAnswerIsWordedForTheStagingJobs()
	{
		final RunProgress progress = RunProgress.newRun(Arrays.asList("MODEL", "LOAD"));
		progress.start("MODEL", 1L);
		progress.finish("MODEL", true, RunTexts.stagingModelAt("BTiers", "Staging").stored(), 2L);
		progress.start("LOAD", 3L);
		progress.activity("LOAD", RunTexts.loadProgress("Customer", "ECOM", 1, 2, 1, 0, 0).stored());
		final String json = progress.toJson();
		final String french = RunPanelText.localizeProgressJson(json, FR);
		check(french.contains("Modèle du staging dans BTiers/Staging.") && french.contains("Customer · ECOM : 1 lignes lues sur 2, 1 écrites.") && !french.contains("[mdmf:"), french);
		check(!EngineMessageCodec.stripMarkers(json).contains("[mdmf:") && EngineMessageCodec.stripMarkers(json).contains("Staging model at BTiers/Staging."), "REST readers get the English, no marker");
		final ProposalProgress proposal = ProposalProgress.newRun();
		proposal.skip(ProposalProgress.QUALITY, RunTexts.notSelected().stored());
		check(RunPanelText.localizeProgressJson(proposal.toJson(), FR).contains("Non sélectionnée."), "the proposal's stages");
		check(com.on.ebx.factory.ebx.FactoryJobStatus.progressIsWorded("STAGING_PROPOSAL") && com.on.ebx.factory.ebx.FactoryJobStatus.progressIsWorded("STAGING_LOAD_RUN")
			&& com.on.ebx.factory.ebx.FactoryJobStatus.progressIsWorded("LANDING_RUN") && !com.on.ebx.factory.ebx.FactoryJobStatus.progressIsWorded("MATCHING_RUN"), "which jobs are worded");
	}
}
