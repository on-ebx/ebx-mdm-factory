package com.on.ebx.factory.wizard;

import java.util.Locale;

/** Landing-walkthrough lot (2026-10-16). JUnit-free: run the main, a failure throws. Exercises {@link LandingWalk} and {@link LandingWalkPage} (through {@link WizardPage#render}). */
public final class LandingWalkTest
{
	public static void main(final String[] pArgs)
	{
		LandingWalkTest.keyStatesFollowTheOrigin();
		LandingWalkTest.emptyTablesAreFoldedAndNotKeptListedBelow();
		LandingWalkTest.pagesFollowTheOrderAndTheValidations();
		LandingWalkTest.subRailKeepsTheOrderOfTheApplications();
		LandingWalkTest.validateIsClosedWithTheReasonWhenNothingIsTicked();
		LandingWalkTest.launchOpensOnlyWhenEverythingIsValidated();
		LandingWalkTest.languageModelChoiceOnlyOnPagesWithAnUncertainKey();
		LandingWalkTest.aRunHidesTheWalkthroughUntilItIsReviewed();
		LandingWalkTest.applicationsAreNamedByTheirConnection();
		LandingWalkTest.leadTextFollowsTheView();
		LandingWalkTest.notKeptTablesAreOrderedSampleLastAndKeyActionsOnTickedRowsOnly();
		LandingWalkTest.aFlattenedDocumentIsFlagged();
		LandingWalkTest.onlyOneKeyChooserUnderARow();
		LandingWalkTest.theRerunCheckboxFollowsTheLastChoice();
		LandingWalkTest.step1ButtonComesBeforeTheStyleCards();
		LandingWalkTest.sampleFilesTableScrollsAndProposesAFreeRank();
		LandingWalkTest.readyApplicationsAreNotSaidToBeStuck();
		LandingWalkTest.aTableLeftWaitingByARunOffersTheChooserAgain();
		System.out.println("LandingWalkTest OK");
	}

	/** Landing test fixes (2026-10-17): a table whose columns are customers[0].x says why, in the Why column. */
	private static void aFlattenedDocumentIsFlagged()
	{
		final WizardSnapshot.SourceInfo source = LandingWalkTest.source("9", "ECOM");
		final WizardSnapshot.TableInfo table = LandingWalkTest.table("91", "ECOM_Clients", true, 1, "", "", 0);
		table.columnNames.clear();
		table.columnNames.add("customers[0].customerId");
		table.columnNames.add("customers[0].email");
		source.tables.add(table);
		LandingWalkTest.check("flattened columns: why = flattened", "flattened".equals(LandingWalk.why(source, table)));
		LandingWalkTest.check("the reason has a text in both languages", !WizardMessages.get(Locale.ENGLISH, "wizard.walk.why.flattened").startsWith("!")
			&& !WizardMessages.get(Locale.FRENCH, "wizard.walk.why.flattened").equals(WizardMessages.get(Locale.ENGLISH, "wizard.walk.why.flattened")));
	}

	/** Landing test fixes (2026-10-17): after "Measure uniqueness" the study's chooser replaces the inline picker of that row; the preselection is the identifier, never the date. */
	private static void onlyOneKeyChooserUnderARow()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		final WizardPage.Model model = LandingWalkTest.model(snapshot);
		model.walkPage = "3";
		final String before = WizardPage.render(model);
		LandingWalkTest.check("without a study the inline picker is there", before.contains("mdmf-walk-keybox") && !before.contains("mdmf-key-chooser"));
		final WizardSnapshot.TableInfo sap = snapshot.sources.get(2).tables.get(0);
		final java.util.List<String> columns = java.util.Arrays.asList("NUM_TICKET", "DATE_ACHAT", "MONTANT_TTC");
		final java.util.List<java.util.List<String>> rows = new java.util.ArrayList<java.util.List<String>>();
		for (int i = 0; i < 5; i++)
		{
			rows.add(java.util.Arrays.asList("T" + (100 + i), "2026-08-0" + (i + 1), (10 + i) + ".50"));
		}
		final com.on.ebx.factory.landing.KeyStudyReport report = new com.on.ebx.factory.landing.KeyStudyReport();
		report.tableId = sap.id;
		report.sampleColumns.addAll(columns);
		report.sampleRows.addAll(rows);
		final java.util.List<com.on.ebx.factory.landing.KeyEvidence.Column> evidence = com.on.ebx.factory.landing.KeyEvidence.analyse(columns, rows);
		for (final String name : columns)
		{
			report.columns.add(new com.on.ebx.factory.landing.KeyStudyReport.ColumnRow(name, "VARCHAR", false, com.on.ebx.factory.landing.KeyEvidence.find(evidence, name)));
		}
		model.landingKeyChooser = report;
		model.landingKeyChooserTableId = sap.id;
		final String html = WizardPage.render(model);
		LandingWalkTest.check("the study's chooser replaces the inline picker", html.contains("mdmf-key-chooser") && !html.contains("mdmf-walk-keybox"));
		final int cards = html.indexOf("mdmf-key-candidates");
		final String candidates = html.substring(cards, html.indexOf("mdmf-key-composite", cards));
		LandingWalkTest.check("identifier first, amount and date after", candidates.indexOf("NUM_TICKET") > 0 && candidates.indexOf("NUM_TICKET") < candidates.indexOf("DATE_ACHAT")
			&& candidates.indexOf("DATE_ACHAT") < candidates.length());
		// landing leftovers (2026-10-17): the chooser is in its own full-width row of the table, in a box that scrolls; the table has a fixed layout in a scrolling wrapper
		final int panelRow = html.indexOf("<tr class=\"mdmf-walk-panel-row\"><td colspan=\"5\"><div class=\"mdmf-scroll-x mdmf-walk-panel\">");
		final int chooser = html.indexOf("mdmf-key-chooser");
		LandingWalkTest.check("the chooser sits in a colspan row of its own", panelRow > 0 && chooser > panelRow && html.lastIndexOf("</tr><tr", chooser) >= panelRow - 6);
		final int rowEnd = html.indexOf("</td></tr>", chooser);
		LandingWalkTest.check("that row closes after the chooser's sample table", rowEnd > html.indexOf("mdmf-sample", chooser) || html.indexOf("mdmf-sample", chooser) < 0);
		final int wrap = html.lastIndexOf("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table mdmf-walk-table mdmf-walk-fixed\"><colgroup>", panelRow);
		LandingWalkTest.check("the table is in a scrolling box, fixed layout, with its colgroup", wrap > 0 && html.indexOf("</table></div>", panelRow) > panelRow);
		final String css = LandingWalkTest.readCss();
		LandingWalkTest.check("fixed layout and a scrolling panel in the stylesheet", css.contains(".mdmf-walk-fixed{table-layout:fixed;width:100%") && css.contains(".mdmf-scroll-x{overflow-x:auto"));
		LandingWalkTest.check("only the identifier is ticked", html.contains("value=\"NUM_TICKET\" checked=\"checked\"") && !html.contains("value=\"DATE_ACHAT\" checked=\"checked\"")
			&& !html.contains("value=\"MONTANT_TTC\" checked=\"checked\""));
	}

	/** Landing test fixes (2026-10-17): the re-run checkbox opens on the last answer, unchecked when none. */
	private static void theRerunCheckboxFollowsTheLastChoice()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		for (final WizardSnapshot.SourceInfo source : snapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				table.status = "LOADED";
			}
		}
		final WizardPage.Model none = LandingWalkTest.model(snapshot);
		final String off = WizardPage.render(none);
		final int box = off.indexOf("id=\"mdmfLandingRunUseLlmCheckbox\"");
		LandingWalkTest.check("a finished run offers the checkbox", box > 0);
		LandingWalkTest.check("never answered: unchecked", !off.substring(box, off.indexOf(">", box)).contains("checked"));
		final WizardPage.Model yes = LandingWalkTest.model(snapshot);
		yes.landingRunUseLlm = true;
		final String on = WizardPage.render(yes);
		final int box2 = on.indexOf("id=\"mdmfLandingRunUseLlmCheckbox\"");
		LandingWalkTest.check("last answer yes: checked", on.substring(box2, on.indexOf(">", box2)).contains("checked=\"checked\""));
	}

	/** Landing leftovers (2026-10-17): the style choice is above the Create button (form, style, button), and the button's block stays pinned above the frame's bar. */
	private static void step1ButtonComesBeforeTheStyleCards()
	{
		final WizardPage.Model model = LandingWalkTest.model(new WizardSnapshot());
		model.currentStep = WizardRules.STEP_PROJECT;
		final String html = WizardPageTest.section(WizardPage.render(model), 1);
		final int button = html.indexOf("data-mdmf-action=\"SAVE_PROJECT\"");
		final int cards = html.indexOf("mdmf-style-cards");
		final int code = html.indexOf("id=\"mdmfCode\"");
		LandingWalkTest.check("form, then style cards, then button", code > 0 && cards > code && button > cards);
		LandingWalkTest.check("both selectable cards sit above the button", html.indexOf("value=\"CONSOLIDATION\"") < button && html.indexOf("value=\"CENTRALISATION\"") < button);
		LandingWalkTest.check("the facts of each card stay folded", html.contains("<details class=\"mdmf-style-more\">") && !html.contains("<details class=\"mdmf-style-more\" open"));
		final String css = LandingWalkTest.readCss();
		LandingWalkTest.check("the button block is sticky above the bottom bar", css.contains("[data-step=\"1\"] .mdmf-primary-block{position:sticky;bottom:64px"));
	}

	/** Landing test fixes (2026-10-17): the dropped-files table scrolls in its own box; the rank proposed is a free one. */
	private static void sampleFilesTableScrollsAndProposesAFreeRank()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		snapshot.catalogAvailable = true;
		snapshot.sources.get(0).trustRank = 1;
		snapshot.sources.get(1).trustRank = 2;
		snapshot.sources.get(2).trustRank = 4;
		final WizardPage.Model model = LandingWalkTest.model(snapshot);
		model.currentStep = WizardRules.STEP_SOURCES;
		final String html = WizardPage.render(model);
		LandingWalkTest.check("the table sits in a scrolling wrapper", html.contains("<div class=\"mdmf-scroll-x\" id=\"mdmfSampleTableWrap\"><table class=\"mdmf-table mdmf-sample-table\""));
		LandingWalkTest.check("the ranks held are on the drop zone", html.contains("id=\"mdmfSampleDropzone\" tabindex=\"0\" role=\"button\" data-mdmf-taken-ranks=\"1,2,4\""));
		LandingWalkTest.check("next free rank", SampleFileRules.nextFreeRank(java.util.Arrays.asList(1, 2, 4)) == 3 && SampleFileRules.nextFreeRank(java.util.Arrays.asList(1, 2, 3)) == 4
			&& SampleFileRules.nextFreeRank(new java.util.ArrayList<Integer>()) == 1);
		LandingWalkTest.check("100 (the default of an unranked application) is not a rank", "1,2".equals(SampleFileRules.takenRanksText(java.util.Arrays.asList(2, 100, 1))));
	}

	/** Landing test fixes (2026-10-17): an application that is one of the project's own and whose run finished is not said to be outside the landing. */
	private static void readyApplicationsAreNotSaidToBeStuck()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		snapshot.sources.get(0).connector = "ECOM";
		final WizardPage.Model model = LandingWalkTest.model(snapshot);
		final WizardPage.Model.SourcesRunCard done = new WizardPage.Model.SourcesRunCard("ECOM", "ECOM", ApplicationStages.from(new SourceOnboarding.Diagnosis(new java.util.ArrayList<SourceOnboarding.Step>(), SourceOnboarding.Action.NONE, null, false)), SourcesProgress.DONE, "");
		LandingWalkTest.check("done and an application of the project: ready", "wizard.review.ready.lineOnly".equals(WizardPage.stuckLineKey(model, done)));
		final WizardPage.Model.SourcesRunCard other = new WizardPage.Model.SourcesRunCard("OTHER", "OTHER", done.pipeline, SourcesProgress.DONE, "");
		LandingWalkTest.check("not an application of the project: the old sentence", "wizard.review.stuck.lineOnly".equals(WizardPage.stuckLineKey(model, other)));
		final WizardPage.Model.SourcesRunCard running = new WizardPage.Model.SourcesRunCard("ECOM", "ECOM", done.pipeline, SourcesProgress.RUNNING, "");
		LandingWalkTest.check("still running: the old sentence", "wizard.review.stuck.lineOnly".equals(WizardPage.stuckLineKey(model, running)));
		LandingWalkTest.check("the ready sentence says ready, in both languages", WizardMessages.get(Locale.ENGLISH, "wizard.review.ready.lineOnly").startsWith("Ready")
			&& WizardMessages.get(Locale.FRENCH, "wizard.review.ready.lineOnly").startsWith("Pr\u00eate"));
	}

	/**
	 * Landing validated key (2026-10-17, project TiersB2CRetestStaging): the run ended with a built table still waiting for its key; its page of the walkthrough (reviewed) used
	 * to show the key chips only (the table is GENERATED, so every key action was hidden). It offers the chooser again, and the chooser's button resumes the run.
	 */
	private static void aTableLeftWaitingByARunOffersTheChooserAgain()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		final WizardSnapshot.TableInfo waiting = snapshot.sources.get(1).tables.get(0);
		waiting.status = "GENERATED";
		final WizardPage.Model plain = LandingWalkTest.model(snapshot);
		plain.walkReview = true;
		plain.walkPage = "2";
		final String before = WizardPage.render(plain);
		LandingWalkTest.check("a built table with no run waiting: no key action", !before.contains("LANDING_KEY_CHOOSE") && !before.contains("mdmf-walk-keybox"));
		final WizardPage.Model model = LandingWalkTest.model(snapshot);
		model.walkReview = true;
		model.walkPage = "2";
		model.landingRunWaitingTableIds.add(waiting.id);
		final String html = WizardPage.render(model);
		LandingWalkTest.check("the page offers the key chooser for the waiting table", html.contains("data-mdmf-action=\"LANDING_KEY_CHOOSE\" data-mdmf-table-id=\"21\""));
		LandingWalkTest.check("it says the table waits for the key", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.walk.key.waitsInRun")));
		LandingWalkTest.check("the key it holds is still shown", html.contains("ACCOUNT_ID"));
		LandingWalkTest.check("no ordinary pre-launch key action on that built table", !html.contains("LANDING_WALK_KEY_CHOOSE") && !html.contains("LANDING_WALK_STUDY\""));
		// the chooser, once open, resumes the run
		final com.on.ebx.factory.landing.KeyStudyReport report = new com.on.ebx.factory.landing.KeyStudyReport();
		report.tableId = waiting.id;
		final java.util.List<String> columns = java.util.Arrays.asList("ACCOUNT_ID", "NAME");
		final java.util.List<java.util.List<String>> rows = new java.util.ArrayList<java.util.List<String>>();
		rows.add(java.util.Arrays.asList("1", "A"));
		rows.add(java.util.Arrays.asList("2", "B"));
		report.sampleColumns.addAll(columns);
		report.sampleRows.addAll(rows);
		final java.util.List<com.on.ebx.factory.landing.KeyEvidence.Column> evidence = com.on.ebx.factory.landing.KeyEvidence.analyse(columns, rows);
		for (final String name : columns)
		{
			report.columns.add(new com.on.ebx.factory.landing.KeyStudyReport.ColumnRow(name, "VARCHAR", false, com.on.ebx.factory.landing.KeyEvidence.find(evidence, name)));
		}
		model.landingKeyChooser = report;
		model.landingKeyChooserTableId = waiting.id;
		final String open = WizardPage.render(model);
		LandingWalkTest.check("the open chooser applies the key AND resumes the run", open.contains("mdmf-key-chooser") && open.contains("data-mdmf-action=\"LANDING_KEY_APPLY\"")
			&& !open.contains("data-mdmf-action=\"LANDING_WALK_KEY_APPLY\""));
		LandingWalkTest.check("the new texts exist in French and differ from the English", !WizardMessages.get(Locale.FRENCH, "wizard.walk.key.waitsInRun").equals(WizardMessages.get(Locale.ENGLISH, "wizard.walk.key.waitsInRun"))
			&& !WizardMessages.get(Locale.FRENCH, "wizard.walk.key.waitsInRun.hint").startsWith("!"));
		// another table, same page: unchanged behaviour (a table that is not waiting in the run)
		final WizardSnapshot other = LandingWalkTest.zefz();
		final WizardPage.Model unrelated = LandingWalkTest.model(other);
		unrelated.walkReview = true;
		unrelated.walkPage = "2";
		unrelated.landingRunWaitingTableIds.add("999");
		LandingWalkTest.check("a waiting id of another table changes nothing here", WizardPage.render(unrelated).contains("LANDING_WALK_STUDY") && !WizardPage.render(unrelated).contains("LANDING_KEY_CHOOSE"));
	}

	private static WizardSnapshot.TableInfo table(final String pId, final String pName, final boolean pTicked, final long pRows, final String pKey, final String pOrigin, final int pConfidence)
	{
		final WizardSnapshot.TableInfo table = new WizardSnapshot.TableInfo();
		table.id = pId;
		table.tableName = pName;
		table.selected = pTicked;
		table.sourceRowCount = pRows;
		table.sourceRowCountKnown = true;
		table.keyColumns = pKey;
		table.keyOrigin = pOrigin;
		table.keyConfidence = pConfidence;
		table.columnNames.add("Id");
		table.columnNames.add("Name");
		return table;
	}

	private static WizardSnapshot.SourceInfo source(final String pId, final String pCode)
	{
		final WizardSnapshot.SourceInfo source = new WizardSnapshot.SourceInfo();
		source.id = pId;
		source.sourceSystem = pCode;
		return source;
	}

	/** Three applications as on project Zefz: Salesforce (declared keys, nothing ticked at first), Snowflake (deduced key), a sample file (no applied key). */
	private static WizardSnapshot zefz()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.code = "Zefz";
		snapshot.project = project;
		final WizardSnapshot.SourceInfo salesforce = LandingWalkTest.source("1", "Salesforce");
		salesforce.tables.add(LandingWalkTest.table("11", "Account", true, 52, "Id", "SOURCE", 100));
		salesforce.tables.add(LandingWalkTest.table("12", "User", false, 6, "Id", "SOURCE", 100));
		salesforce.tables.add(LandingWalkTest.table("13", "Asset", false, 0, "Id", "SOURCE", 100));
		final WizardSnapshot.SourceInfo snowflake = LandingWalkTest.source("2", "Snowflake");
		snowflake.tables.add(LandingWalkTest.table("21", "CRM_SOURCE.ACCOUNT", true, 52, "ACCOUNT_ID", "RULE", 80));
		final WizardSnapshot.SourceInfo sap = LandingWalkTest.source("3", "PersonnesSapSample");
		sap.platform = "sample-file";
		sap.tables.add(LandingWalkTest.table("31", "Personnes_SAP_Sample", true, 0, "BusinessPartner", "LLM", 85));
		sap.tables.get(0).sourceRowCountKnown = false;
		snapshot.sources.add(salesforce);
		snapshot.sources.add(snowflake);
		snapshot.sources.add(sap);
		return snapshot;
	}

	private static WizardPage.Model model(final WizardSnapshot pSnapshot)
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.snapshot = pSnapshot;
		model.stepStates = WizardRules.stepStates(pSnapshot);
		model.administrator = true;
		model.designer = true;
		model.currentStep = WizardRules.STEP_LANDING;
		model.currentStepOnly = false;
		return model;
	}

	private static void keyStatesFollowTheOrigin()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		LandingWalkTest.check("a source key is declared", LandingWalk.keyState(snapshot.sources.get(0).tables.get(0)).kind == LandingWalk.KeyKind.DECLARED);
		final LandingWalk.KeyState deduced = LandingWalk.keyState(snapshot.sources.get(1).tables.get(0));
		LandingWalkTest.check("a rule key is deduced, with its confidence", deduced.kind == LandingWalk.KeyKind.DEDUCED && deduced.confidence == 80);
		final LandingWalk.KeyState proposal = LandingWalk.keyState(snapshot.sources.get(2).tables.get(0));
		LandingWalkTest.check("a language-model key under the bar is a proposal, not a key", proposal.kind == LandingWalk.KeyKind.NONE && "BusinessPartner".equals(proposal.proposal)
			&& proposal.proposalConfidence == 85);
		final WizardSnapshot.TableInfo chosen = LandingWalkTest.table("9", "T", true, 1, "K", "USER", 100);
		LandingWalkTest.check("a person's key is chosen", LandingWalk.keyState(chosen).kind == LandingWalk.KeyKind.CHOSEN);
		final WizardSnapshot.TableInfo none = LandingWalkTest.table("8", "T", true, 1, "", "", 0);
		LandingWalkTest.check("no column, no declared key: none", LandingWalk.keyState(none).kind == LandingWalk.KeyKind.NONE);
	}

	private static void emptyTablesAreFoldedAndNotKeptListedBelow()
	{
		final WizardSnapshot.SourceInfo salesforce = LandingWalkTest.zefz().sources.get(0);
		LandingWalkTest.check("the unticked empty table is folded", LandingWalk.emptyTables(salesforce).size() == 1 && "Asset".equals(LandingWalk.emptyTables(salesforce).get(0).tableName));
		LandingWalkTest.check("the list holds the ticked table first, then the unticked one with rows", LandingWalk.listed(salesforce).size() == 2
			&& "Account".equals(LandingWalk.listed(salesforce).get(0).tableName) && "User".equals(LandingWalk.listed(salesforce).get(1).tableName));
		LandingWalkTest.check("why: only facts", "empty".equals(LandingWalk.why(salesforce, salesforce.tables.get(2))) && "notTicked".equals(LandingWalk.why(salesforce, salesforce.tables.get(1)))
			&& LandingWalk.why(salesforce, salesforce.tables.get(0)) == null);
		final String html = WizardPage.render(LandingWalkTest.model(LandingWalkTest.zefz()));
		LandingWalkTest.check("the page folds the empty tables", html.contains("See the 1 empty table") && html.contains("Not kept"));
	}

	private static void pagesFollowTheOrderAndTheValidations()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		LandingWalkTest.check("the first page is the first application", "1".equals(LandingWalk.resolvePage(snapshot, "")));
		snapshot.sources.get(0).landingValidated = true;
		LandingWalkTest.check("then the next one to validate", "2".equals(LandingWalk.resolvePage(snapshot, "")));
		LandingWalkTest.check("an unknown page falls back", "2".equals(LandingWalk.resolvePage(snapshot, "99")));
		LandingWalkTest.check("a known page is kept", "3".equals(LandingWalk.resolvePage(snapshot, "3")));
		LandingWalkTest.check("after the last application comes the launch", LandingWalk.LAUNCH.equals(LandingWalk.neighbour(snapshot, "3", 1)));
		LandingWalkTest.check("before the first there is nothing", LandingWalk.neighbour(snapshot, "1", -1) == null && "1".equals(LandingWalk.neighbour(snapshot, "2", -1)));
		snapshot.sources.get(1).landingValidated = true;
		snapshot.sources.get(2).landingValidated = true;
		LandingWalkTest.check("everything validated opens on the launch page", LandingWalk.LAUNCH.equals(LandingWalk.resolvePage(snapshot, "")));
	}

	private static void subRailKeepsTheOrderOfTheApplications()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		snapshot.sources.get(0).landingValidated = true;
		final String html = WizardPage.render(LandingWalkTest.model(snapshot));
		final int salesforce = html.indexOf("Salesforce &middot;") >= 0 ? html.indexOf("Salesforce &middot;") : html.indexOf("Salesforce ·");
		final int snowflake = html.indexOf("mdmf-walk-name\">Snowflake");
		final int sap = html.indexOf("mdmf-walk-name\">PersonnesSapSample");
		final int launch = html.indexOf("mdmf-walk-name\">Launch");
		LandingWalkTest.check("the rail lists the applications in the order they were added, then Launch", salesforce > 0 && salesforce < snowflake && snowflake < sap && sap < launch);
		LandingWalkTest.check("the validated one is green with its table count", html.contains("Salesforce · 1 table"));
		LandingWalkTest.check("the validated application is clickable, the later ones are not",
			html.contains("data-mdmf-action=\"LANDING_WALK_GOTO\" data-mdmf-id=\"1\"") && !html.contains("data-mdmf-id=\"3\""));
		final String french = WizardPage.render(LandingWalkTest.frenchModel(snapshot));
		LandingWalkTest.check("in French too", french.contains("Lancer") && french.contains("application 2 sur 3"));
	}

	private static WizardPage.Model frenchModel(final WizardSnapshot pSnapshot)
	{
		final WizardPage.Model model = LandingWalkTest.model(pSnapshot);
		model.locale = Locale.FRENCH;
		return model;
	}

	private static void validateIsClosedWithTheReasonWhenNothingIsTicked()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		snapshot.sources.get(0).tables.get(0).selected = false;
		LandingWalkTest.check("no ticked table: the rule gives the reason", "wizard.walk.validate.needTable".equals(LandingWalk.validateReasonKey(snapshot.sources.get(0))));
		final String html = WizardPage.render(LandingWalkTest.model(snapshot));
		final int button = html.indexOf("data-mdmf-walk-validate=\"1\" disabled=\"disabled\"");
		LandingWalkTest.check("the Validate button is disabled", button > 0);
		LandingWalkTest.check("and the reason is written next to it", html.indexOf("Tick at least one table, or remove the application.", button) > button
			&& !html.substring(button, html.indexOf("Tick at least one table, or remove the application.", button)).contains("hidden=\"hidden\""));
		LandingWalkTest.check("the button names the next application", html.contains("Validate Salesforce and go to Snowflake"));
		snapshot.sources.get(0).tables.get(0).selected = true;
		LandingWalkTest.check("ticking a table opens it", LandingWalk.validateReasonKey(snapshot.sources.get(0)) == null
			&& !WizardPage.render(LandingWalkTest.model(snapshot)).contains("data-mdmf-walk-validate=\"1\" disabled=\"disabled\""));
	}

	private static void launchOpensOnlyWhenEverythingIsValidated()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		LandingWalkTest.check("an application to validate keeps the launch closed", "wizard.walk.launch.needValidation".equals(LandingWalk.launchReasonKey(snapshot)));
		final WizardPage.Model closed = LandingWalkTest.model(snapshot);
		closed.walkPage = LandingWalk.LAUNCH;
		final String closedHtml = WizardPage.render(closed);
		LandingWalkTest.check("the launch button is disabled with the real reason and a way to the first application",
			closedHtml.contains("data-mdmf-action=\"LANDING_WALK_LAUNCH\" disabled=\"disabled\"") && closedHtml.contains("Validate every application first: Salesforce is still to validate.")
				&& closedHtml.contains("Go to Salesforce"));
		for (final WizardSnapshot.SourceInfo source : snapshot.sources)
		{
			source.landingValidated = true;
		}
		LandingWalkTest.check("all validated, tables ticked: open", LandingWalk.launchReasonKey(snapshot) == null);
		final String openHtml = WizardPage.render(LandingWalkTest.model(snapshot));
		LandingWalkTest.check("the launch button counts the tables", openHtml.contains("Prepare the landing · 3 tables") && !openHtml.contains("data-mdmf-action=\"LANDING_WALK_LAUNCH\" disabled=\"disabled\""));
		LandingWalkTest.check("the table of the launch page shows every ticked table with a Modify link", openHtml.contains("CRM_SOURCE.ACCOUNT") && openHtml.contains("Modify"));
		LandingWalkTest.check("the advanced settings are renamed", openHtml.contains("Table-by-table settings (advanced)"));
		for (final WizardSnapshot.SourceInfo source : snapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				table.selected = false;
			}
		}
		LandingWalkTest.check("nothing ticked anywhere: closed even when validated", "wizard.walk.launch.needTable".equals(LandingWalk.launchReasonKey(snapshot)));
	}

	private static void languageModelChoiceOnlyOnPagesWithAnUncertainKey()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		snapshot.llmConfigured = true;
		snapshot.llmConnectorLabel = "OpenAI";
		final WizardPage.Model salesforce = LandingWalkTest.model(snapshot);
		LandingWalkTest.check("declared keys only: no language-model choice", !WizardPage.render(salesforce).contains("mdmfWalkLlmRadio"));
		final WizardPage.Model snowflake = LandingWalkTest.model(snapshot);
		snowflake.walkPage = "2";
		final String html = WizardPage.render(snowflake);
		LandingWalkTest.check("a deduced key: two explicit options, No is the default", html.contains("name=\"mdmfWalkLlmRadio\" value=\"no\" checked=\"checked\"") && html.contains("No data leaves EBX.")
			&& html.contains("are sent to OpenAI."));
		snowflake.walkLlmYes.add("2");
		LandingWalkTest.check("the choice is kept", WizardPage.render(snowflake).contains("name=\"mdmfWalkLlmRadio\" value=\"yes\" checked=\"checked\""));
		snapshot.llmConnectorLabel = "";
		LandingWalkTest.check("a generic wording when the connector is unknown", WizardPage.render(snowflake).contains("are sent to the configured language model."));
		snapshot.llmConfigured = false;
		LandingWalkTest.check("no connector, no choice", !WizardPage.render(snowflake).contains("mdmfWalkLlmRadio"));
		final WizardPage.Model sap = LandingWalkTest.model(snapshot);
		sap.walkPage = "3";
		final String sapHtml = WizardPage.render(sap);
		LandingWalkTest.check("a missing key shows the picker with the proposal ticked and the sentence saying the table waits",
			sapHtml.contains("value=\"BusinessPartner\" checked=\"checked\"") && sapHtml.contains("Use BusinessPartner") && sapHtml.contains("this one waits for you during the run"));
	}

	private static void aRunHidesTheWalkthroughUntilItIsReviewed()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		LandingWalkTest.check("no run: shown", LandingWalk.shown(false, false, false, false));
		LandingWalkTest.check("running: not shown", !LandingWalk.shown(false, true, false, true));
		LandingWalkTest.check("done: not shown, unless reviewed", !LandingWalk.shown(false, false, true, true) && LandingWalk.shown(true, false, true, true));
		for (final WizardSnapshot.SourceInfo source : snapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				table.status = "LOADED";
			}
		}
		final String done = WizardPage.render(LandingWalkTest.model(snapshot));
		LandingWalkTest.check("a finished run keeps its view and offers the review link", done.contains("LANDING_WALK_REVIEW") && !done.contains("mdmf-walk-rail"));
		final WizardPage.Model review = LandingWalkTest.model(snapshot);
		review.walkReview = true;
		final String reviewHtml = WizardPage.render(review);
		LandingWalkTest.check("reviewing re-opens the walkthrough, with a way back", reviewHtml.contains("mdmf-walk-rail") && reviewHtml.contains("LANDING_WALK_CLOSE"));
	}

	/** Landing-walkthrough display fix (2026-10-17): the connection's name, the code as small text, the code added when two applications share a connection. */
	private static void applicationsAreNamedByTheirConnection()
	{
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		final WizardSnapshot.SourceInfo snowflake = snapshot.sources.get(1);
		snowflake.sourceSystem = "DATA_PRODUCTS";
		snowflake.connector = "c-snow";
		snowflake.connectorLabel = "Snowflake";
		LandingWalkTest.check("the connection's label is the name", "Snowflake".equals(LandingWalk.name(snapshot, snowflake)) && "DATA_PRODUCTS".equals(LandingWalk.code(snowflake)));
		snowflake.connectorLabel = "";
		LandingWalkTest.check("without a label, the connector id", "c-snow".equals(LandingWalk.name(snapshot, snowflake)));
		snowflake.connector = "";
		LandingWalkTest.check("without a connector, the code, shown once", "DATA_PRODUCTS".equals(LandingWalk.name(snapshot, snowflake)) && LandingWalk.code(snowflake).isEmpty());
		snowflake.connector = "c-snow";
		snowflake.connectorLabel = "Snowflake";
		snapshot.sources.get(0).sourceSystem = "SALESFORCE";
		snapshot.sources.get(0).connectorLabel = "Salesforce";
		snapshot.sources.get(2).sourceSystem = "PERSONNES_SAP_SAMPLE";
		snapshot.sources.get(2).connectorLabel = "PersonnesSapSample";
		snowflake.landingValidated = false;
		final WizardPage.Model page = LandingWalkTest.model(snapshot);
		page.walkPage = "2";
		final String html = WizardPage.render(page);
		LandingWalkTest.check("title is the name, the code is secondary text", html.contains("mdmf-walk-title\">Snowflake</h3>") && html.contains("<span class=\"mdmf-walk-code\">DATA_PRODUCTS</span> &middot; application 2 of 3"));
		LandingWalkTest.check("rail and buttons use the name, not the code", html.contains("mdmf-walk-name\">Snowflake") && html.contains("Validate Snowflake and go to PersonnesSapSample")
			&& html.contains("Remove Snowflake from the project") && html.contains("Back to Salesforce") && !html.contains("Validate DATA_PRODUCTS"));
		snapshot.sources.get(0).landingValidated = true;
		snapshot.sources.get(2).landingValidated = true;
		LandingWalkTest.check("the validated notice name", "Snowflake".equals(LandingWalk.name(snapshot, snowflake)));
		final WizardSnapshot.SourceInfo second = LandingWalkTest.source("4", "DATA_PRODUCTS_2");
		second.connector = "c-snow";
		second.connectorLabel = "Snowflake";
		snapshot.sources.add(second);
		LandingWalkTest.check("two applications on one connection: the code disambiguates", "Snowflake (DATA_PRODUCTS)".equals(LandingWalk.name(snapshot, snowflake))
			&& "Snowflake (DATA_PRODUCTS_2)".equals(LandingWalk.name(snapshot, second)));
		final String twice = WizardPage.render(page);
		LandingWalkTest.check("in the rail and on the button", twice.contains("mdmf-walk-name\">Snowflake (DATA_PRODUCTS)") && twice.contains("Validate Snowflake (DATA_PRODUCTS) and go to PersonnesSapSample"));
		snapshot.sources.remove(second);
		final WizardPage.Model launch = LandingWalkTest.model(snapshot);
		launch.walkPage = LandingWalk.LAUNCH;
		LandingWalkTest.check("the launch page names applications too", WizardPage.render(launch).contains("<b>Snowflake</b>") && !WizardPage.render(launch).contains("<b>DATA_PRODUCTS</b>"));
	}

	private static void leadTextFollowsTheView()
	{
		final String oldLead = WizardPage.esc("One click finds each table's key");
		final String walkLead = WizardPage.esc("Validate the factory's suggestion for each application");
		final WizardSnapshot snapshot = LandingWalkTest.zefz();
		final String walk = WizardPage.render(LandingWalkTest.model(snapshot));
		LandingWalkTest.check("walkthrough: its own lead, once, and not the old one", !walk.contains(oldLead) && walk.indexOf(walkLead) == walk.lastIndexOf(walkLead) && walk.contains(walkLead)
			&& walk.indexOf(walkLead) < walk.indexOf("mdmf-walk-rail"));
		for (final WizardSnapshot.SourceInfo source : snapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				table.status = "LOADED";
			}
		}
		final String done = WizardPage.render(LandingWalkTest.model(snapshot));
		LandingWalkTest.check("a finished run keeps the old lead", done.contains(oldLead) && !done.contains(walkLead));
		final WizardPage.Model review = LandingWalkTest.model(snapshot);
		review.walkReview = true;
		final String reviewHtml = WizardPage.render(review);
		LandingWalkTest.check("reviewing shows the walkthrough lead only", reviewHtml.contains(walkLead) && !reviewHtml.contains(oldLead));
		final WizardPage.Model french = LandingWalkTest.frenchModel(LandingWalkTest.zefz());
		final String fr = WizardPage.render(french);
		LandingWalkTest.check("in French too", fr.contains("Validez la suggestion de la factory") && !fr.contains("Un clic trouve la cl"));
	}

	private static void notKeptTablesAreOrderedSampleLastAndKeyActionsOnTickedRowsOnly()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.SourceInfo snow = LandingWalkTest.source("1", "DATA_PRODUCTS");
		snow.connectorLabel = "Snowflake";
		snow.tables.add(LandingWalkTest.table("1", "CRM_SOURCE.ACCOUNT", true, 52, "ACCOUNT_ID", "RULE", 80));
		snow.tables.get(0).sourceObject = "DB.CRM_SOURCE.ACCOUNT";
		final String[][] others = { { "2", "TPCH_SF1.LINEITEM", "SNOWFLAKE_SAMPLE_DATA.TPCH_SF1.LINEITEM", "6000000" }, { "3", "TPCH_SF1.ORDERS", "SNOWFLAKE_SAMPLE_DATA.TPCH_SF1.ORDERS", "1500000" },
			{ "4", "CRM_SOURCE.CONTACT", "DB.CRM_SOURCE.CONTACT", "40" }, { "5", "ERP_SOURCE.SUPPLIER", "DB.ERP_SOURCE.SUPPLIER", "900" }, { "6", "ERP_SOURCE.AUDIT", "DB.ERP_SOURCE.AUDIT", "5000000" },
			{ "7", "CRM_SOURCE.LEAD", "DB.CRM_SOURCE.LEAD", "10" }, { "8", "TPCDS_SF10TCL.ITEM", "SAMPLE.TPCDS_SF10TCL.ITEM", "3" } };
		for (final String[] o : others)
		{
			final WizardSnapshot.TableInfo t = LandingWalkTest.table(o[0], o[1], false, Long.parseLong(o[3]), "", "", 0);
			t.sourceObject = o[2];
			snow.tables.add(t);
		}
		// CONTACT has a deduced key, LEAD none, SUPPLIER a declared one
		snow.tables.get(3).keyColumns = "CONTACT_ID";
		snow.tables.get(3).keyOrigin = "RULE";
		snow.tables.get(3).keyConfidence = 80;
		snow.tables.get(4).keyColumns = "SUPPLIER_ID";
		snow.tables.get(4).keyOrigin = "SOURCE";
		snapshot.sources.add(snow);
		LandingWalkTest.check("TPCH_SF1 and TPCDS_SF* are recognised by schema, a business schema is not", LandingWalk.isSampleDataSet(snow.tables.get(1)) && LandingWalk.isSampleDataSet(snow.tables.get(7))
			&& !LandingWalk.isSampleDataSet(snow.tables.get(3)));
		final StringBuilder order = new StringBuilder();
		for (final WizardSnapshot.TableInfo t : LandingWalk.listed(snow))
		{
			order.append(t.tableName).append(' ');
		}
		LandingWalkTest.check("ticked first, then the ticked schema, keyed tables before keyless, then name; samples not in the list: " + order,
			"CRM_SOURCE.ACCOUNT CRM_SOURCE.CONTACT CRM_SOURCE.LEAD ERP_SOURCE.SUPPLIER ERP_SOURCE.AUDIT ".equals(order.toString()));
		LandingWalkTest.check("the sample group holds the TPCH/TPCDS tables by name", LandingWalk.sampleTables(snow).size() == 3 && "TPCDS_SF10TCL.ITEM".equals(LandingWalk.sampleTables(snow).get(0).tableName));
		final WizardPage.Model model = LandingWalkTest.model(snapshot);
		final String whole = WizardPage.render(model);
		final String html = whole.substring(whole.indexOf("id=\"mdmfWalkPage\""));
		LandingWalkTest.check("the page folds the sample group last, labelled with the reason", html.contains("Sample data set (Snowflake sample database) &middot; 3 table(s), not kept")
			|| html.contains("Sample data set (Snowflake sample database) \u00b7 3 table(s), not kept"));
		final int sample = html.indexOf("mdmf-walk-samples");
		final int supplier = html.indexOf("ERP_SOURCE.SUPPLIER");
		final int audit = html.indexOf("ERP_SOURCE.AUDIT");
		LandingWalkTest.check("order in the markup: contact before supplier before audit before the sample group", html.indexOf("CRM_SOURCE.CONTACT") < supplier && supplier < audit && audit < sample && sample > 0);
		final int notKept = html.indexOf("Not kept");
		LandingWalkTest.check("the key buttons exist for the ticked deduced row only", html.indexOf("data-mdmf-action=\"LANDING_WALK_STUDY\"") > 0 && html.indexOf("data-mdmf-action=\"LANDING_WALK_STUDY\"") < notKept
			&& html.indexOf("LANDING_WALK_STUDY", notKept) < 0 && html.indexOf("LANDING_WALK_KEY_CHOOSE", notKept) < 0);
		LandingWalkTest.check("the unticked deduced row still shows its key chip", html.indexOf("CONTACT_ID", notKept) > 0);
		snow.tables.get(3).selected = true;
		LandingWalkTest.check("ticking it brings the buttons", WizardPage.render(model).lastIndexOf("LANDING_WALK_STUDY") > WizardPage.render(model).indexOf("CRM_SOURCE.CONTACT", WizardPage.render(model).indexOf("mdmfWalkPage")));
	}

	private static String readCss()
	{
		try
		{
			return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"),
				"../webapp/www/common/stylesheets/factory/wizard.css")), "UTF-8");
		}
		catch (final java.io.IOException ex)
		{
			throw new AssertionError(ex);
		}
	}

	static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
