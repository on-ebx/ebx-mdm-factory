package com.on.ebx.factory.wizard;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.landing.RunTexts;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.review.ReviewRules;

/**
 * JUnit-free: run the main, a failure throws. Step 7 screen counts (2026-10-18), measured on E2EMASTER20261018181651 (master of 699 Personne rows - 212 golden, 487 merged -,
 * 1068 Adresse and 234 Achat rows; a first full send of "980 rows across 3 mappings"): the figures the distribution step and the summary show, the French of a send, the
 * connections of another project, the last send of a target, the open points.
 */
public final class Step7ScreenCountsTest
{
	private static final Locale FR = Locale.FRENCH;
	private static final Locale EN = Locale.ENGLISH;
	private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
	private static final String NB = " ";

	public static void main(final String[] pArgs)
	{
		Step7ScreenCountsTest.theSendIsWordedInFrenchWithItsTables();
		Step7ScreenCountsTest.aSendReadsBackFromItsStoredTextAndFromOldEnglish();
		Step7ScreenCountsTest.theJobSentenceOfASendIsWorded();
		Step7ScreenCountsTest.pivotsOfARunAreReadFromItsMessage();
		Step7ScreenCountsTest.datesAndLastSend();
		Step7ScreenCountsTest.retireFactsReadWellAtZero();
		Step7ScreenCountsTest.theMainObjectIsTheOneNoOtherIsAChildOf();
		Step7ScreenCountsTest.step7QuotesTheGoldenRecordsOfTheMainObjectAndTheRowsInAll();
		Step7ScreenCountsTest.aDisabledTargetShowsItsLastSendAndKeepsItsChip();
		Step7ScreenCountsTest.aConnectionOfAnotherProjectIsNotALikelyTarget();
		Step7ScreenCountsTest.theMasterCardListsOneLinePerTable();
		Step7ScreenCountsTest.theDistributeCardAndItsOpenPoint();
		Step7ScreenCountsTest.aLinkToTheParentIsNoMappingGap();
		Step7ScreenCountsTest.theMappingWarningIsWordedInFrench();
		Step7ScreenCountsTest.aChildThatFollowsItsParentIsMeasuredOnItsGoldenRecords();
		Step7ScreenCountsTest.theRecentSendsShowTheFormattedTimeAndKeepTheIsoInATitle();
		System.out.println("Step7ScreenCountsTest OK");
	}

	// --------------------------------------------------------------------------------------------------------------- 2: the send

	private static void theSendIsWordedInFrenchWithItsTables()
	{
		final String detail = "Personne 212, Adresse 534, Achat 234";
		final String stored = RunTexts.sent(980, 0, 3, "all", detail).stored();
		Step7ScreenCountsTest.check("English is what the engine always said, plus the tables: " + stored, stored.startsWith("Sent 980 row(s) across 3 mapping(s), scope=all: " + detail + " [mdmf:"));
		Step7ScreenCountsTest.check("French: " + RunPanelText.display(stored, FR),
			RunPanelText.display(stored, FR).equals("980 lignes envoyées dans 3 tables (tout le master)" + NB + ": " + detail));
		Step7ScreenCountsTest.check("English display", RunPanelText.display(stored, EN).equals("Sent 980 row(s) across 3 mapping(s), scope=all: " + detail));
		final String plain = RunTexts.sent(980, 0, 3, "all", "").stored();
		Step7ScreenCountsTest.check("French without the tables: " + RunPanelText.display(plain, FR), RunPanelText.display(plain, FR).equals("980 lignes envoyées dans 3 tables (tout le master)"));
		Step7ScreenCountsTest.check("one row, one table", RunPanelText.display(RunTexts.sent(1, 0, 1, "all", "").stored(), FR).equals("1 ligne envoyée dans 1 table (tout le master)"));
		Step7ScreenCountsTest.check("a changed send with unchanged rows: " + RunPanelText.display(RunTexts.sent(5, 3, 2, "changed", "").stored(), FR),
			RunPanelText.display(RunTexts.sent(5, 3, 2, "changed", "").stored(), FR).equals("5 lignes envoyées, 3 inchangées, dans 2 tables (ce qui a changé)"));
		Step7ScreenCountsTest.check("no marker on screen", !RunPanelText.display(stored, FR).contains("mdmf") && !RunPanelText.display(stored, EN).contains("mdmf"));
	}

	private static void aSendReadsBackFromItsStoredTextAndFromOldEnglish()
	{
		final String old = "Sent 980 row(s) across 3 mapping(s), scope=all";
		Step7ScreenCountsTest.check("old English is worded in French: " + RunPanelText.display(old, FR), RunPanelText.display(old, FR).equals("980 lignes envoyées dans 3 tables (tout le master)"));
		Step7ScreenCountsTest.check("sentCount of old English", RunTexts.sentCount(old) == 980L);
		Step7ScreenCountsTest.check("sentCount of the coded text", RunTexts.sentCount(RunTexts.sent(212, 0, 3, "all", "A 1, B 2").stored()) == 212L);
		Step7ScreenCountsTest.check("sentCount of an unchanged-rows send is the rows sent", RunTexts.sentCount("Sent 5 row(s), 3 unchanged, across 2 mapping(s), scope=changed") == 5L);
		Step7ScreenCountsTest.check("no count in a failure", RunTexts.sentCount("broker refused") == -1L && RunTexts.sentCount("") == -1L && RunTexts.sentCount(null) == -1L);
		Step7ScreenCountsTest.check("a failure keeps its words", RunPanelText.display("broker refused", FR).equals("broker refused"));
		// the word "changed" elsewhere is not turned into a fragment
		Step7ScreenCountsTest.check("'changed' in another sentence stays a plain word", RunTexts.parse("Rows written: 12; source rows read: 40 (0 unchanged, 1 held back).") != null);
	}

	private static void theJobSentenceOfASendIsWorded()
	{
		final String inner = RunTexts.sent(980, 0, 3, "all", "Personne 212, Adresse 534, Achat 234").stored();
		final String first = RunTexts.sendEnded(true, "22", "DONE", inner).stored();
		Step7ScreenCountsTest.check("English job sentence: " + first, first.startsWith("First full send of target 22: DONE - Sent 980 row(s) across 3 mapping(s), scope=all: Personne 212"));
		final String french = RunPanelText.display(first, FR);
		Step7ScreenCountsTest.check("French job sentence: " + french, french.equals("Premier envoi complet de la cible 22" + NB + ": terminé - 980 lignes envoyées dans 3 tables (tout le master)" + NB
			+ ": Personne 212, Adresse 534, Achat 234"));
		Step7ScreenCountsTest.check("old English job sentence is worded too",
			RunPanelText.display("Send of target 7: DONE - Sent 12 row(s) across 2 mapping(s), scope=all", FR).equals("Envoi de la cible 7" + NB + ": terminé - 12 lignes envoyées dans 2 tables (tout le master)"));
		Step7ScreenCountsTest.check("a failure with a dash in its text is still a failure",
			RunPanelText.display("Send of target 7 failed: broker down - retry", FR).equals("Échec de l’envoi de la cible 7" + NB + ": broker down - retry"));
		Step7ScreenCountsTest.check("bare failure", RunPanelText.display(RunTexts.sendFailed(false, "7", "").stored(), FR).equals("Échec de l’envoi de la cible 7."));
	}

	// ---------------------------------------------------------------------------------------------------------- 7: the compared figure

	private static void pivotsOfARunAreReadFromItsMessage()
	{
		final String inner = RunTexts.matchingScope(true, 534, 534).stored();
		final String coded = "Matching run on Personne finished (DONE); Xref.goldenKey refreshed (534 set, 0 cleared, 0 unchanged). Scope: all records - the 534 records of the table were pivots."
			+ " [mdmf:" + RunTexts.MTC_FINISHED + "|s:Personne|s:DONE|n:534|n:0|n:0|s:" + urlEncode(inner) + "|s:]";
		Step7ScreenCountsTest.check("pivots of a coded run message", RunTexts.pivotCount(coded) == 534L);
		Step7ScreenCountsTest.check("pivots of the English of an older run", RunTexts.pivotCount("Matching run on Achat finished (DONE); Xref.goldenKey refreshed (234 set, 0 cleared, 0 unchanged). "
			+ "Scope: all records - the 234 records of the table were pivots.") == 234L);
		Step7ScreenCountsTest.check("pivots of an incremental run", RunTexts.pivotCount("Scope: incremental - 30 of 1081 records were pivots (the last loaded batch); the other 1051 were not matched in this run.") == 30L);
		Step7ScreenCountsTest.check("a run that does not say", RunTexts.pivotCount("Matching run on X finished (DONE).") == -1L && RunTexts.pivotCount("") == -1L);
		final Map<String, Long> two = new LinkedHashMap<String, Long>();
		two.put("Personne", Long.valueOf(534));
		two.put("Achat", Long.valueOf(234));
		Step7ScreenCountsTest.check("two objects are not added up", "Personne 534, Achat 234 comparés".equals(DistributionTexts.comparedText(two, "comparés")));
		Step7ScreenCountsTest.check("one object", "534 comparés".equals(DistributionTexts.comparedText(Map.of("Personne", Long.valueOf(534)), "comparés")));
		Step7ScreenCountsTest.check("none: no figure", DistributionTexts.comparedText(new LinkedHashMap<String, Long>(), "comparés").isEmpty());
	}

	private static String urlEncode(final String pText)
	{
		try
		{
			return java.net.URLEncoder.encode(pText, "UTF-8");
		}
		catch (final java.io.UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	// ------------------------------------------------------------------------------------------------------- 4, 5: dates, last send, retire

	private static void datesAndLastSend()
	{
		Step7ScreenCountsTest.check("French date, in the zone", DistributionTexts.when("2026-10-18T18:23:11Z", FR, PARIS).equals("18/10 20:23"));
		Step7ScreenCountsTest.check("English date", DistributionTexts.when("2026-10-18T18:23:11Z", EN, PARIS).equals("18 Oct 20:23"));
		Step7ScreenCountsTest.check("a local date-time is taken as it is", DistributionTexts.when("2026-10-18T20:23:11", FR, PARIS).equals("18/10 20:23"));
		Step7ScreenCountsTest.check("blank and garbage give nothing", DistributionTexts.when("", FR, PARIS).isEmpty() && DistributionTexts.when("yesterday", FR, PARIS).isEmpty());
		final WizardPage.DistributionTargetRow target = Step7ScreenCountsTest.disabledTarget();
		Step7ScreenCountsTest.check("last send in French: " + DistributionTexts.lastSend(FR, target, PARIS), DistributionTexts.lastSend(FR, target, PARIS).equals("Dernier envoi : 18/10 20:23, 980 lignes"));
		Step7ScreenCountsTest.check("last send in English", DistributionTexts.lastSend(EN, target, PARIS).equals("Last send: 18 Oct 20:23, 980 rows"));
		target.lastSendAt = "";
		Step7ScreenCountsTest.check("no send, no line", DistributionTexts.lastSend(FR, target, PARIS).isEmpty());
		target.lastSendAt = "2026-10-18T18:23:11Z";
		target.lastStatus = "FAILED";
		target.lastMessage = "broker refused";
		Step7ScreenCountsTest.check("a failed send says so", DistributionTexts.lastSend(FR, target, PARIS).equals("Dernier envoi échoué : 18/10 20:23"));
		target.lastStatus = "DONE";
		target.lastMessage = "Sent 12 row(s)";
		Step7ScreenCountsTest.check("a message without a count: the date alone", DistributionTexts.lastSend(FR, target, PARIS).equals("Dernier envoi : 18/10 20:23"));
	}

	private static WizardPage.DistributionTargetRow disabledTarget()
	{
		final WizardPage.DistributionTargetRow target = new WizardPage.DistributionTargetRow();
		target.id = "22";
		target.connector = "KafkaE2EMASTER20261018181651";
		target.connectorLabel = "Kafka - e2e E2EMASTER20261018181651";
		target.operation = "kafka.send";
		target.mode = "REALTIME";
		target.state = "DISABLED";
		target.lastStatus = "DONE";
		target.lastSendAt = "2026-10-18T18:23:11Z";
		target.lastMessage = RunTexts.sent(980, 0, 3, "all", "Personne 212, Adresse 534, Achat 234").stored();
		target.firstFullSendRunId = "run-1";
		return target;
	}

	private static void retireFactsReadWellAtZero()
	{
		Step7ScreenCountsTest.check("none yet, merge states never compared: " + DistributionTexts.retireFacts(FR, 0, false, "", PARIS),
			DistributionTexts.retireFacts(FR, 0, false, "", PARIS).equals("Aucun avis de retrait envoyé pour l’instant · états de fusion pas encore comparés"));
		Step7ScreenCountsTest.check("none yet, compared at 20:23: " + DistributionTexts.retireFacts(FR, 0, false, "2026-10-18T18:23:11Z", PARIS),
			DistributionTexts.retireFacts(FR, 0, false, "2026-10-18T18:23:11Z", PARIS).equals("Aucun avis de retrait envoyé pour l’instant · états de fusion comparés le 18/10 20:23"));
		Step7ScreenCountsTest.check("one", DistributionTexts.retireSent(FR, 1, false).equals("1 avis de retrait envoyé"));
		Step7ScreenCountsTest.check("several", DistributionTexts.retireSent(FR, 12, false).equals("12 avis de retrait envoyés"));
		Step7ScreenCountsTest.check("capped", DistributionTexts.retireSent(FR, 10000, true).equals("10000+ avis de retrait envoyés"));
		Step7ScreenCountsTest.check("English zero", DistributionTexts.retireSent(EN, 0, false).equals("No retire notice sent yet"));
	}

	// ------------------------------------------------------------------------------------------------------------------- 1: the intro

	private static void theMainObjectIsTheOneNoOtherIsAChildOf()
	{
		final Map<String, Long> rows = new LinkedHashMap<String, Long>();
		rows.put("Adresse", Long.valueOf(1068));
		rows.put("Achat", Long.valueOf(234));
		rows.put("Personne", Long.valueOf(699));
		Step7ScreenCountsTest.check("Adresse and Achat point to Personne: Personne is the main object (not the biggest table)",
			"Personne".equals(MasterMain.pick(Arrays.asList("Adresse", "Achat", "Personne"), new HashSet<String>(Arrays.asList("Adresse", "Achat")), rows)));
		Step7ScreenCountsTest.check("two roots: the larger", "Adresse".equals(MasterMain.pick(Arrays.asList("Achat", "Adresse"), new HashSet<String>(), rows)));
		Step7ScreenCountsTest.check("a cycle: the first of the larger", "Adresse".equals(MasterMain.pick(Arrays.asList("Achat", "Adresse"), new HashSet<String>(Arrays.asList("Achat", "Adresse")), rows)));
		Step7ScreenCountsTest.check("nothing built", MasterMain.pick(new ArrayList<String>(), new HashSet<String>(), rows).isEmpty());
	}

	private static void step7QuotesTheGoldenRecordsOfTheMainObjectAndTheRowsInAll()
	{
		Step7ScreenCountsTest.check("French: " + DistributionTexts.masterHolds(FR, "Personne", 212, 699, 2001, 3),
			DistributionTexts.masterHolds(FR, "Personne", 212, 699, 2001, 3).equals("Le master contient 212 golden records de Personne ; 2001 lignes en tout, dans 3 tables."));
		Step7ScreenCountsTest.check("English", DistributionTexts.masterHolds(EN, "Personne", 212, 699, 2001, 3).equals("The master holds 212 golden records of Personne; 2001 rows in all, across 3 tables."));
		Step7ScreenCountsTest.check("without Match and Merge: the rows of the main object",
			DistributionTexts.masterHolds(FR, "Personne", -1, 699, 2001, 3).equals("Le master contient 699 enregistrements de Personne ; 2001 lignes en tout, dans 3 tables."));
		Step7ScreenCountsTest.check("one table", DistributionTexts.masterHolds(FR, "Personne", 1, 5, 5, 1).equals("Le master contient 1 golden record de Personne."));

		final WizardPage.Model model = Step7ScreenCountsTest.model(FR);
		final WizardPage.DistributionModel d = new WizardPage.DistributionModel();
		d.masterMainEntity = "Personne";
		d.masterMainGolden = 212;
		d.masterMainRows = 699;
		d.masterTotalRows = 980;
		d.masterTableCount = 3;
		model.distribution = d;
		final String html = WizardPage.render(model);
		Step7ScreenCountsTest.check("the page quotes the golden records of Personne, not 980 golden records",
			html.contains("Le master contient 212 golden records de Personne ; 980 lignes en tout, dans 3 tables.") && !html.contains("980 golden records"));
	}

	private static WizardPage.Model model(final Locale pLocale)
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.locale = pLocale;
		model.snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.code = "E2EMASTER20261018181651";
		project.label = "E2E master";
		project.mode = "GREENFIELD";
		model.snapshot.project = project;
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.administrator = true;
		model.designer = true;
		model.currentStep = WizardRules.STEP_DISTRIBUTION;
		return model;
	}

	// ------------------------------------------------------------------------------------------------------------------- 5: the card

	private static void aDisabledTargetShowsItsLastSendAndKeepsItsChip()
	{
		final WizardPage.Model model = Step7ScreenCountsTest.model(FR);
		final WizardPage.DistributionModel d = new WizardPage.DistributionModel();
		d.targets.add(Step7ScreenCountsTest.disabledTarget());
		final WizardPage.DistributionConnectionRow connection = new WizardPage.DistributionConnectionRow();
		connection.connector = "KafkaE2EMASTER20261018181651";
		connection.label = "Kafka - e2e E2EMASTER20261018181651";
		connection.type = "kafka";
		connection.hasWriteOperation = true;
		d.connections.add(connection);
		model.distribution = d;
		final String html = WizardPage.render(model);
		Step7ScreenCountsTest.check("the chip still reads Désactivée", html.contains(">Désactivée</span>"));
		final String zoned = DistributionTexts.lastSend(FR, d.targets.get(0), ZoneId.systemDefault());
		Step7ScreenCountsTest.check("the card shows the last send: " + zoned, zoned.startsWith("Dernier envoi : ") && zoned.endsWith(", 980 lignes") && html.contains("data-mdmf-last-send=\"true\">" + zoned));
		Step7ScreenCountsTest.check("the run message is French, not 'Sent 980 row(s)'", html.contains("980 lignes envoyées dans 3 tables (tout le master)") && !html.contains("Sent 980"));
		// the retire facts of the open target: zero reads well
		final WizardPage.DistributionModel open = new WizardPage.DistributionModel();
		final WizardPage.DistributionTargetRow openTarget = Step7ScreenCountsTest.disabledTarget();
		open.targets.add(openTarget);
		open.connections.addAll(d.connections);
		open.openTargetId = openTarget.id;
		open.openTarget = openTarget;
		model.distribution = open;
		final String setup = WizardPage.render(model);
		Step7ScreenCountsTest.check("the retire facts at zero: Aucun avis de retrait envoyé pour l’instant", setup.contains("Aucun avis de retrait envoyé pour l’instant · "));
		openTarget.retiresSent = 3;
		Step7ScreenCountsTest.check("three notices", WizardPage.render(model).contains("3 avis de retrait envoyés · "));
	}

	// ------------------------------------------------------------------------------------------------------------------- 3: connections

	private static void aConnectionOfAnotherProjectIsNotALikelyTarget()
	{
		final List<WizardPage.DistributionConnectionRow> connections = new ArrayList<WizardPage.DistributionConnectionRow>();
		connections.add(Step7ScreenCountsTest.connection("KafkaE2EMASTER20261018171023", "Kafka - e2e E2EMASTER20261018171023", "kafka"));
		connections.add(Step7ScreenCountsTest.connection("KafkaE2EMASTER20261018181651", "Kafka - e2e E2EMASTER20261018181651", "kafka"));
		connections.add(Step7ScreenCountsTest.connection("Salesforce", "Salesforce CRM", "salesforce"));
		connections.add(Step7ScreenCountsTest.connection("KafkaPersVol9", "Kafka of PersVol9", "kafka"));
		connections.add(Step7ScreenCountsTest.connection("KafkaPartner", "Partner feed", "kafka"));
		final java.util.Set<String> none = new HashSet<String>();
		final DistributionConnectionGroups groups = DistributionConnectionGroups.of(connections, none, none, "E2EMASTER20261018181651", none, none);
		Step7ScreenCountsTest.check("the Kafka of the previous scenario project is folded under the other connections: " + names(groups.others),
			names(groups.others).contains("Kafka - e2e E2EMASTER20261018171023") && !names(groups.likely).contains("Kafka - e2e E2EMASTER20261018171023"));
		Step7ScreenCountsTest.check("the one of this project stays likely", names(groups.likely).contains("Kafka - e2e E2EMASTER20261018181651") && names(groups.likely).contains("Salesforce CRM"));
		final DistributionConnectionGroups byCode = DistributionConnectionGroups.of(connections, none, none, "E2EMASTER20261018181651", new HashSet<String>(Arrays.asList("PersVol9", "Companies")), none);
		Step7ScreenCountsTest.check("a connection whose id carries another project's code is folded", names(byCode.others).contains("Kafka of PersVol9") && names(byCode.likely).contains("Partner feed"));
		final DistributionConnectionGroups byTarget = DistributionConnectionGroups.of(connections, none, none, "E2EMASTER20261018181651", none, new HashSet<String>(Arrays.asList("KafkaPartner")));
		Step7ScreenCountsTest.check("a connection a target of another project uses is folded", names(byTarget.others).contains("Partner feed"));
		final DistributionConnectionGroups own = DistributionConnectionGroups.of(connections, new HashSet<String>(Arrays.asList("KafkaE2EMASTER20261018171023")), none, "E2EMASTER20261018181651",
			none, none);
		Step7ScreenCountsTest.check("a connection that carries a target of THIS project stays likely whatever its name", names(own.likely).contains("Kafka - e2e E2EMASTER20261018171023"));
		Step7ScreenCountsTest.check("the old three-argument form is unchanged", DistributionConnectionGroups.of(connections, none, none).likely.size() == 5 - 0
			- DistributionConnectionGroups.of(connections, none, none).others.size());
	}

	private static WizardPage.DistributionConnectionRow connection(final String pId, final String pLabel, final String pType)
	{
		final WizardPage.DistributionConnectionRow row = new WizardPage.DistributionConnectionRow();
		row.connector = pId;
		row.label = pLabel;
		row.type = pType;
		row.hasWriteOperation = true;
		return row;
	}

	private static String names(final List<WizardPage.DistributionConnectionRow> pRows)
	{
		final StringBuilder out = new StringBuilder();
		for (final WizardPage.DistributionConnectionRow row : pRows)
		{
			out.append(row.label).append('|');
		}
		return out.toString();
	}

	// ----------------------------------------------------------------------------------------------------------------- 6, 9: the summary

	private static void theMasterCardListsOneLinePerTable()
	{
		final WizardSummaryData.Input in = new WizardSummaryData.Input();
		in.projectLabel = "E2E master";
		in.landingLoaded = true;
		in.matchingAvailable = true;
		in.goldenCount = 980;
		in.masterMainEntity = "Personne";
		in.masterEntities.add(Step7ScreenCountsTest.entity("Personne", 699, 212L, 487L));
		in.masterEntities.add(Step7ScreenCountsTest.entity("Adresse", 1068, 534L, 534L));
		in.masterEntities.add(Step7ScreenCountsTest.entity("Achat", 234, 234L, 0L));
		in.matchObjects.add(new WizardSummaryData.MatchObjectInput("Personne", "APPLIED", "DONE"));
		in.matchPlansApplied = 1;
		in.matchPlansTotal = 1;
		final WizardSummaryData.ZoneCard master = Step7ScreenCountsTest.zone(WizardSummaryData.compute(in), "MASTER");
		Step7ScreenCountsTest.check("one line per table", master.lines.size() == 3);
		Step7ScreenCountsTest.check("only the main object carries golden and merged records", master.lines.get(0).golden.longValue() == 212L && master.lines.get(0).merged.longValue() == 487L
			&& master.lines.get(1).golden == null && master.lines.get(2).golden == null);
		final WizardPage.Model model = Step7ScreenCountsTest.model(FR);
		model.currentStep = WizardRules.STEP_SUMMARY;
		model.summaryInput = in;
		model.summary = WizardSummaryData.compute(in);
		final String html = WizardPage.render(model);
		Step7ScreenCountsTest.check("Personne: 699 enregistrements dont 212 golden records (487 fusionnés)", html.contains("Personne : 699 enregistrements dont 212 golden records (487 fusionnés)"));
		Step7ScreenCountsTest.check("Adresse: its own rows only", html.contains("Adresse : 1068 enregistrements<") && html.contains("Achat : 234 enregistrements<"));
		Step7ScreenCountsTest.check("no 2001 enregistrements, no 980 golden records", !html.contains("2001 enregistrements") && !html.contains("980 golden records"));
		in.matchObjects.clear();
		Step7ScreenCountsTest.check("before a run: rows only, no golden figure", !WizardPage.render(Step7ScreenCountsTest.withInput(model, in)).contains("212 golden records"));
	}

	private static WizardPage.Model withInput(final WizardPage.Model pModel, final WizardSummaryData.Input pIn)
	{
		pModel.summaryInput = pIn;
		pModel.summary = WizardSummaryData.compute(pIn);
		return pModel;
	}

	private static WizardSummaryData.ZoneEntityInput entity(final String pName, final long pRows, final Long pGolden, final Long pMerged)
	{
		final WizardSummaryData.ZoneEntityInput entity = new WizardSummaryData.ZoneEntityInput();
		entity.name = pName;
		entity.rows = Long.valueOf(pRows);
		entity.golden = pGolden;
		entity.merged = pMerged;
		return entity;
	}

	private static WizardSummaryData.ZoneCard zone(final WizardSummaryData.Output pOut, final String pKey)
	{
		for (final WizardSummaryData.ZoneCard card : pOut.zones)
		{
			if (card.zoneKey.equals(pKey))
			{
				return card;
			}
		}
		throw new AssertionError("no zone " + pKey);
	}

	private static void theDistributeCardAndItsOpenPoint()
	{
		Step7ScreenCountsTest.check("a disabled target that sent is not an open point", !DistributionTexts.notArmedIsOpenPoint("REALTIME", "DISABLED", true));
		Step7ScreenCountsTest.check("a disabled target that never sent is", DistributionTexts.notArmedIsOpenPoint("REALTIME", "DISABLED", false));
		Step7ScreenCountsTest.check("a waiting real-time target is", DistributionTexts.notArmedIsOpenPoint("REALTIME", "WAITING", false));
		Step7ScreenCountsTest.check("an armed one is not, a scheduled one is not", !DistributionTexts.notArmedIsOpenPoint("REALTIME", "ARMED", true) && !DistributionTexts.notArmedIsOpenPoint("SCHEDULED", "DRAFT", false));
		Step7ScreenCountsTest.check("French state after a first send: " + DistributionTexts.summaryState(FR, "DISABLED", true, "2026-10-18T18:23:11Z", disabledTarget().lastMessage, PARIS),
			DistributionTexts.summaryState(FR, "DISABLED", true, "2026-10-18T18:23:11Z", disabledTarget().lastMessage, PARIS).equals("Désactivée après un premier envoi complet (980 lignes le 18/10 20:23)"));
		Step7ScreenCountsTest.check("a disabled target that never sent keeps the plain word", DistributionTexts.summaryState(FR, "DISABLED", false, "", "", PARIS).equals("Désactivée"));
		Step7ScreenCountsTest.check("an armed target keeps its word", DistributionTexts.summaryState(FR, "ARMED", true, "2026-10-18T18:23:11Z", "", PARIS).equals("Armée"));
		Step7ScreenCountsTest.check("without the date or the rows", DistributionTexts.summaryState(FR, "DISABLED", true, "", "", PARIS).equals("Désactivée après un premier envoi complet"));

		final WizardSummaryData.Input in = new WizardSummaryData.Input();
		in.projectLabel = "E2E master";
		final WizardSummaryData.DistributionSummaryTarget target = new WizardSummaryData.DistributionSummaryTarget();
		target.label = "KafkaE2EMASTER20261018181651";
		target.mode = "REALTIME";
		target.state = "DISABLED";
		target.retiresSent = 0;
		target.madeFirstFullSend = true;
		target.lastSendAt = "2026-10-18T18:23:11Z";
		target.lastMessage = disabledTarget().lastMessage;
		in.distributionTargets.add(target);
		final WizardPage.Model model = Step7ScreenCountsTest.model(FR);
		model.currentStep = WizardRules.STEP_SUMMARY;
		model.summaryInput = in;
		model.summary = WizardSummaryData.compute(in);
		final String html = WizardPage.render(model);
		final String expected = "KafkaE2EMASTER20261018181651 &middot; Temps réel &middot; "
			+ WizardPage.esc(DistributionTexts.summaryState(FR, "DISABLED", true, target.lastSendAt, target.lastMessage, ZoneId.systemDefault())) + " &middot; Aucun avis de retrait envoyé pour l’instant";
		Step7ScreenCountsTest.check("the Distribute line: " + html.substring(Math.max(0, html.indexOf("KafkaE2EMASTER")), Math.min(html.length(), html.indexOf("KafkaE2EMASTER") + 400)),
			html.contains(expected) && html.contains("Désactivée après un premier envoi complet (980 lignes le "));
	}

	// ----------------------------------------------------------------------------------------------------------------- 8: the mapping gap

	private static void aLinkToTheParentIsNoMappingGap()
	{
		final List<String> none = new ArrayList<String>();
		// Adresse.personne: a link to Personne, no mapping row of its own, Adresse loaded through the parent's feeds
		Step7ScreenCountsTest.check("a link to the parent with no mapping at all is no gap", ReviewRules.attributeMappingWarning(ReviewLine.APPLIED, false, false, true, none) == null);
		Step7ScreenCountsTest.check("an ordinary attribute with no mapping still is", ReviewRules.attributeMappingWarning(ReviewLine.APPLIED, false, false, false, none) != null);
		Step7ScreenCountsTest.check("a link whose mappings were all rejected still is a gap", ReviewRules.attributeMappingWarning(ReviewLine.ACCEPTED, false, false, true, Arrays.asList(ReviewLine.REJECTED)) != null);
		Step7ScreenCountsTest.check("a link with a live mapping is fine", ReviewRules.attributeMappingWarning(ReviewLine.ACCEPTED, false, false, true, Arrays.asList(ReviewLine.ACCEPTED)) == null);
		Step7ScreenCountsTest.check("the four-argument form is unchanged", ReviewRules.attributeMappingWarning(ReviewLine.ACCEPTED, false, false, none) != null);
	}

	private static void theMappingWarningIsWordedInFrench()
	{
		Step7ScreenCountsTest.check("French sentence", WizardMessages.get(FR, "wizard.step7.open.mappingGap.detail").equals("Acceptée, mais aucun mapping ne l’alimente."));
		Step7ScreenCountsTest.check("English sentence is the constant", WizardMessages.get(EN, "wizard.step7.open.mappingGap.detail").equals(ReviewRules.MAPPING_WARNING));
		// the step-4 chip is worded too
		final WizardPage.Model model = Step7ScreenCountsTest.model(FR);
		final WizardSummaryData.Input in = new WizardSummaryData.Input();
		in.openPoints.add(new WizardSummaryData.OpenPointInput("ERROR", "Adresse : x", WizardMessages.get(FR, "wizard.step7.open.mappingGap.detail"), "", 0, ""));
		model.currentStep = WizardRules.STEP_SUMMARY;
		model.summaryInput = in;
		model.summary = WizardSummaryData.compute(in);
		final String html = WizardPage.render(model);
		Step7ScreenCountsTest.check("the open point reads French only", html.contains("Acceptée, mais aucun mapping ne l’alimente.") && !html.contains("no mapping feeds it"));
	}

	// ------------------------------------------------------------------------------------------------------------------- lot S7

	private static WizardSummaryData.Input childCompletenessInput(final Long pAdresseGolden)
	{
		final WizardSummaryData.Input in = new WizardSummaryData.Input();
		in.projectLabel = "E2E master";
		in.landingLoaded = true;
		in.matchingAvailable = true;
		in.masterMainEntity = "Personne";
		in.masterEntities.add(Step7ScreenCountsTest.entity("Personne", 699, 212L, 487L));
		in.masterEntities.add(Step7ScreenCountsTest.entity("Adresse", 1068, pAdresseGolden, pAdresseGolden == null ? null : Long.valueOf(534L)));
		in.masterEntities.add(Step7ScreenCountsTest.entity("Achat", 234, 234L, 0L));
		// Adresse follows Personne and has no plan: it is not a match object, Personne and Achat are
		in.matchObjects.add(new WizardSummaryData.MatchObjectInput("Personne", "APPLIED", "DONE"));
		in.matchObjects.add(new WizardSummaryData.MatchObjectInput("Achat", "APPLIED", "DONE"));
		in.followerGolden.put("Adresse", Long.valueOf(pAdresseGolden == null ? 0L : pAdresseGolden.longValue()));
		in.matchPlansApplied = 2;
		in.matchPlansTotal = 2;
		for (final String[] fill : new String[][] { { "Personne", "nom", "212" }, { "Adresse", "ville", "534" }, { "Achat", "montant", "234" } })
		{
			final WizardSummaryData.CompletenessInput row = new WizardSummaryData.CompletenessInput();
			row.entityName = fill[0];
			row.attribute = fill[1];
			row.attributeLabel = fill[0] + " - " + fill[1];
			final boolean measured = !"Adresse".equals(fill[0]) || pAdresseGolden != null;
			row.total = measured ? Integer.parseInt(fill[2]) : 0;
			row.filled = row.total;
			in.completeness.add(row);
		}
		return in;
	}

	private static void aChildThatFollowsItsParentIsMeasuredOnItsGoldenRecords()
	{
		final WizardSummaryData.Input in = Step7ScreenCountsTest.childCompletenessInput(Long.valueOf(534L));
		final WizardSummaryData.CompletenessGroup adresse = WizardSummaryData.compute(in).completenessGroups.get(1);
		Step7ScreenCountsTest.check("Adresse (no plan, 534 golden copies) is matched and measured", "Adresse".equals(adresse.entityName) && adresse.matched && adresse.scopeCount == 534L && adresse.rows.size() == 1);
		final WizardPage.Model model = Step7ScreenCountsTest.model(FR);
		model.currentStep = WizardRules.STEP_SUMMARY;
		final String fr = WizardPage.render(Step7ScreenCountsTest.withInput(model, in));
		Step7ScreenCountsTest.check("French: Adresse - mesuré sur les 534 golden records", fr.contains("<b>Adresse</b> - mesuré sur les 534 golden records") && !fr.contains("pas encore rapproché"));
		final WizardPage.Model modelEn = Step7ScreenCountsTest.model(EN);
		modelEn.currentStep = WizardRules.STEP_SUMMARY;
		final String en = WizardPage.render(Step7ScreenCountsTest.withInput(modelEn, in));
		Step7ScreenCountsTest.check("English: Adresse - measured on the 534 golden records", en.contains("<b>Adresse</b> - measured on the 534 golden records") && !en.contains("not matched yet"));
		// a follower whose table has no metadataset / no golden row is "not matched yet", with no bar
		final WizardSummaryData.Input none = Step7ScreenCountsTest.childCompletenessInput(null);
		final WizardSummaryData.CompletenessGroup unmatched = WizardSummaryData.compute(none).completenessGroups.get(1);
		Step7ScreenCountsTest.check("Adresse with no metadataset: not matched yet, no bar", !unmatched.matched && unmatched.rows.isEmpty());
		final String frNone = WizardPage.render(Step7ScreenCountsTest.withInput(Step7ScreenCountsTest.model(FR), none));
		Step7ScreenCountsTest.check("French sentence kept", frNone.contains("<b>Adresse</b> - pas encore rapproché"));
		final WizardSummaryData.Input zero = Step7ScreenCountsTest.childCompletenessInput(Long.valueOf(0L));
		Step7ScreenCountsTest.check("Adresse with a metadataset but no golden row: not matched yet", !WizardSummaryData.compute(zero).completenessGroups.get(1).matched);
		// an object that is neither a match object nor a follower stays unmatched
		final WizardSummaryData.Input stray = Step7ScreenCountsTest.childCompletenessInput(Long.valueOf(534L));
		stray.followerGolden.clear();
		Step7ScreenCountsTest.check("not a follower: still unmatched", !WizardSummaryData.compute(stray).completenessGroups.get(1).matched);
		// the other objects are untouched
		final WizardSummaryData.Output out = WizardSummaryData.compute(in);
		Step7ScreenCountsTest.check("Personne and Achat stay matched", out.completenessGroups.get(0).matched && out.completenessGroups.get(2).matched);
	}

	private static void theRecentSendsShowTheFormattedTimeAndKeepTheIsoInATitle()
	{
		final WizardPage.DistributionTargetRow target = Step7ScreenCountsTest.disabledTarget();
		final WizardPage.EventRow event = new WizardPage.EventRow();
		event.tablePath = "/root/Personne";
		event.recordKey = "17";
		event.change = "CREATE";
		event.status = "SENT";
		event.createdAt = "2026-10-18T19:40:15.889Z";
		target.events.add(event);
		final WizardPage.EventRow odd = new WizardPage.EventRow();
		odd.change = "MODIFY";
		odd.status = "SENT";
		odd.createdAt = "yesterday";
		target.events.add(odd);
		final StringBuilder fr = new StringBuilder();
		WizardPage.renderDistributionHistory(fr, FR, target, PARIS);
		Step7ScreenCountsTest.check("French time in the server zone, ISO whole in the title: " + fr,
			fr.indexOf("<td title=\"2026-10-18T19:40:15.889Z\">18/10 21:40</td>") >= 0 && fr.indexOf(">2026-10-18T19:40:15.889Z</td>") < 0);
		final StringBuilder en = new StringBuilder();
		WizardPage.renderDistributionHistory(en, EN, target, PARIS);
		Step7ScreenCountsTest.check("English time", en.indexOf("<td title=\"2026-10-18T19:40:15.889Z\">18 Oct 21:40</td>") >= 0);
		Step7ScreenCountsTest.check("the same formatter as the card", DistributionTexts.when(event.createdAt, FR, PARIS).equals("18/10 21:40"));
		Step7ScreenCountsTest.check("an unreadable stamp is shown as stored", fr.indexOf("<td title=\"yesterday\">yesterday</td>") >= 0);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
