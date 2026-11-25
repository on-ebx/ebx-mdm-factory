package com.on.ebx.factory.wizard;

import java.util.Locale;

/**
 * JUnit-free test of {@link AccelPages} (lot C1-B, docs/LOT-C1.md sec 6 and 8): the five screens of the CENTRALISATION path are rendered from a
 * {@link WizardSnapshot} fixture, in both languages, and the key texts, the actions and the rail are checked. No EBX class.
 */
public final class AccelPagesTest
{
	private AccelPagesTest()
	{
	}

	public static void main(final String[] pArgs)
	{
		AccelPagesTest.railFollowsTheStyle();
		AccelPagesTest.packScreen();
		AccelPagesTest.packScreenWithoutAnyPackStillSays();
		AccelPagesTest.specificationScreen();
		AccelPagesTest.proposalScreen();
		AccelPagesTest.generationScreen();
		AccelPagesTest.summaryScreen();
		AccelPagesTest.noMessageKeyLeaksInEitherLanguage();
		AccelPagesTest.consolidationRailIsUntouched();
		System.out.println("AccelPagesTest OK");
	}

	// ------------------------------------------------------------------------------------------------------------ fixtures

	private static WizardSnapshot snapshot()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.id = "7";
		project.code = "Suppliers";
		project.label = "Suppliers hub";
		project.style = "CENTRALISATION";
		project.owner = "j.martin";
		snapshot.project = project;
		snapshot.llmConfigured = true;
		snapshot.designer = true;
		snapshot.administrator = true;

		final WizardSnapshot.PackInfo company = new WizardSnapshot.PackInfo("company", "1.0.0", "Organisation / Company (B2B)", "A legal entity: identity, addresses, identifiers.");
		company.tables = 4;
		company.fields = 44;
		company.blocks.add("audit");
		company.blocks.add("contacts");
		company.options.add("e-invoicing");
		company.options.add("bank-accounts");
		company.requires.add("reference-data");
		snapshot.packCatalogue.add(company);
		final WizardSnapshot.PackInfo reference = new WizardSnapshot.PackInfo("reference-data", "1.0.0", "Shared reference data", "Countries, currencies, units.");
		reference.tables = 9;
		reference.fields = 44;
		reference.referenceRows = 4234;
		snapshot.packCatalogue.add(reference);
		return snapshot;
	}

	private static WizardSnapshot.ProjectPackInfo chosen(final String pKey)
	{
		final WizardSnapshot.ProjectPackInfo pack = new WizardSnapshot.ProjectPackInfo();
		pack.id = "1";
		pack.packKey = pKey;
		pack.packVersion = "1.0.0";
		pack.specification = "We need a supplier reference for purchasing.";
		pack.options.add("bank-accounts");
		pack.chosenBy = "LLM";
		return pack;
	}

	private static WizardSnapshot.DifferenceInfo difference(final String pId, final int pPass, final String pNature, final String pStatus, final String pWhat, final String pWhere)
	{
		final WizardSnapshot.DifferenceInfo row = new WizardSnapshot.DifferenceInfo();
		row.id = pId;
		row.pass = pPass;
		row.nature = pNature;
		row.status = pStatus;
		row.what = pWhat;
		row.where = pWhere;
		row.why = "From the specification: " + pWhat;
		row.detail = "{\"field\":\"x\"}";
		return row;
	}

	/** A project that went through the proposal: one line per case the screen distinguishes. */
	private static WizardSnapshot proposed()
	{
		final WizardSnapshot snapshot = AccelPagesTest.snapshot();
		snapshot.projectPacks.add(AccelPagesTest.chosen("company"));
		snapshot.differences.add(AccelPagesTest.difference("1", 1, "ADD", "PROPOSED", "Compliance document", "/root/ComplianceDocument"));
		snapshot.differences.add(AccelPagesTest.difference("2", 1, "KEEP", "ACCEPTED", "Company core: 18 fields", "/root/Company"));
		snapshot.differences.add(AccelPagesTest.difference("3", 1, "USE_BLOCK", "ACCEPTED", "Audit on every table", "Supplier"));
		final WizardSnapshot.DifferenceInfo toPlace = AccelPagesTest.difference("4", 2, "ADD", "PROPOSED", "Kbis younger than 3 months", "ComplianceDocument (table constraint)");
		toPlace.placeState = "TO_PLACE";
		toPlace.detail = "{\"draft\":true}";
		snapshot.differences.add(toPlace);
		final WizardSnapshot.DifferenceInfo similar = AccelPagesTest.difference("5", 2, "ADD", "REJECTED", "Phone number must be valid", "Supplier/Contacts/phone");
		similar.detail = "{\"similarTo\":\"the Contacts block (Fasttrack.lib.phone.isValid)\",\"similarNote\":\"The model agreed.\"}";
		snapshot.differences.add(similar);
		snapshot.differences.add(AccelPagesTest.difference("6", 2, "USE_LIBRARY", "APPLIED", "IBAN checked", "BankAccount/iban"));
		return snapshot;
	}

	private static WizardPage.Model model(final WizardSnapshot pSnapshot, final int pStep)
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.snapshot = pSnapshot;
		model.stepStates = WizardRules.stepStates(pSnapshot);
		model.administrator = true;
		model.designer = true;
		model.currentStep = pStep;
		model.currentStepOnly = false;
		model.accelJobRunning = pSnapshot.accelJob != null; // what ProjectWizardUserService#model copies
		return model;
	}

	private static String render(final WizardSnapshot pSnapshot, final int pStep)
	{
		return WizardPage.render(AccelPagesTest.model(pSnapshot, pStep));
	}

	/** The markup of one step's panel alone (every panel is written in these tests, so a check on a step must not read another's buttons). */
	private static String panel(final String pHtml, final int pStep)
	{
		final int start = pHtml.indexOf("<section class=\"mdmf-wiz-panel\" data-step=\"" + pStep + "\"");
		final int end = pHtml.indexOf("</section>", start);
		return start < 0 ? "" : pHtml.substring(start, end);
	}

	private static String message(final String pKey)
	{
		return WizardPage.esc(WizardMessages.get(Locale.ENGLISH, pKey));
	}

	// ---------------------------------------------------------------------------------------------------------------- tests

	private static void railFollowsTheStyle()
	{
		final String html = AccelPagesTest.render(AccelPagesTest.snapshot(), WizardRules.STEP_PACK);
		for (final int step : new int[] { 1, 9, 10, 11, 12, 8 })
		{
			AccelPagesTest.check("the rail lists step id " + step, html.contains("<li data-step=\"" + step + "\""));
			AccelPagesTest.check("a panel exists for step id " + step, html.contains("<section class=\"mdmf-wiz-panel\" data-step=\"" + step + "\""));
		}
		for (final int step : new int[] { 2, 3, 4, 5, 6, 7 })
		{
			AccelPagesTest.check("the rail has no Consolidation step id " + step, !html.contains("<li data-step=\"" + step + "\""));
		}
		AccelPagesTest.check("the rail numbers by position: Summary is 6", html.contains("<b>6</b><span>" + AccelPagesTest.message("wizard.step.summary") + "</span>"));
		AccelPagesTest.check("the step line reads 2 of 6", html.contains("<span class=\"mdmf-wiz-stepline-n\">2</span>") && html.contains("Step <span class=\"mdmf-wiz-stepline-n\">2</span> of 6"));
		AccelPagesTest.check("the navigation reads 2 of 6", html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.nav.progress", "2", "6"))));
		AccelPagesTest.check("the step names are the new ones", html.contains(AccelPagesTest.message("wizard.step.pack")) && html.contains(AccelPagesTest.message("wizard.step.generation")));
		AccelPagesTest.check("Specification is blocked without a pack, with its reason", html.contains("<li data-step=\"10\" class=\"blocked\" title=\"")
			&& html.contains("title=\"" + AccelPagesTest.message("wizard.blocked.needPack") + "\""));
		AccelPagesTest.check("the Consolidation panels are not written", !html.contains("data-step=\"3\"") && !html.contains("data-step=\"7\""));
		AccelPagesTest.check("the hidden fields of the path are there", html.contains("name=\"mdmfAccelNeed\"") && html.contains("name=\"mdmfAccelPacks\"") && html.contains("name=\"mdmfDetail\"")
			&& html.contains("name=\"mdmfAccelPass\""));
	}

	private static void packScreen()
	{
		final WizardSnapshot snapshot = AccelPagesTest.snapshot();
		final WizardPage.Model model = AccelPagesTest.model(snapshot, WizardRules.STEP_PACK);
		model.accelNeed = "We need a <b>supplier</b> reference.";
		model.accelSuggestedPacks.add("company");
		model.accelTickedPacks.add("company");
		model.accelTickedOptions.add("company:bank-accounts");
		model.accelWhy = "The need is a supplier hub.";
		final String html = WizardPage.render(model);
		AccelPagesTest.check("title", html.contains(AccelPagesTest.message("wizard.accel.pack.title")));
		AccelPagesTest.check("the need is in a textarea, escaped", html.contains("class=\"mdmf-accel-need\"") && html.contains("We need a &lt;b&gt;supplier&lt;/b&gt; reference."));
		AccelPagesTest.check("Suggest packs posts ACCEL_SUGGEST_PACKS", html.contains("data-mdmf-action=\"ACCEL_SUGGEST_PACKS\""));
		AccelPagesTest.check("Save the text posts ACCEL_SAVE_NEED", html.contains("data-mdmf-action=\"ACCEL_SAVE_NEED\""));
		AccelPagesTest.check("Continue posts ACCEL_CHOOSE_PACKS, and so does the blank start", AccelPagesTest.count(html, "data-mdmf-action=\"ACCEL_CHOOSE_PACKS\"") == 2
			&& html.contains("data-mdmf-accel-blank=\"true\""));
		AccelPagesTest.check("both packs are cards with a checkbox", html.contains("class=\"mdmf-accel-pack\" value=\"company\" checked=\"checked\"")
			&& html.contains("class=\"mdmf-accel-pack\" value=\"reference-data\""));
		AccelPagesTest.check("the pack the company requires is ticked and marked prerequisite", html.contains("class=\"mdmf-accel-pack\" value=\"reference-data\" checked=\"checked\"")
			&& html.contains(AccelPagesTest.message("wizard.accel.chip.prerequisite")));
		AccelPagesTest.check("the suggested one says so", html.contains(AccelPagesTest.message("wizard.accel.chip.suggested")));
		AccelPagesTest.check("options are checkboxes named pack:option, the ticked one checked", html.contains("class=\"mdmf-accel-option\" value=\"company:bank-accounts\" checked=\"checked\"")
			&& html.contains("class=\"mdmf-accel-option\" value=\"company:e-invoicing\"/>"));
		AccelPagesTest.check("the why box shows the model's reason", html.contains(AccelPagesTest.message("wizard.accel.why.title")) && html.contains("The need is a supplier hub."));
		AccelPagesTest.check("the counters card adds the packs up: 13 tables, 88 fields, 4234 rows", html.contains("<b>13</b><span>" + AccelPagesTest.message("wizard.accel.counters.tables"))
			&& html.contains("<b>88</b>") && html.contains("<b>4234</b>"));
		AccelPagesTest.check("the building blocks are named", html.contains("audit, contacts"));
		AccelPagesTest.check("the glossary is there", html.contains(AccelPagesTest.message("wizard.accel.glossary.pack.term")));
	}

	private static void packScreenWithoutAnyPackStillSays()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		snapshot.project = AccelPagesTest.snapshot().project;
		final String html = AccelPagesTest.render(snapshot, WizardRules.STEP_PACK);
		AccelPagesTest.check("an empty catalogue is said, not blank", html.contains(AccelPagesTest.message("wizard.accel.packs.none")));
	}

	private static void specificationScreen()
	{
		final WizardSnapshot snapshot = AccelPagesTest.snapshot();
		String html = AccelPagesTest.render(snapshot, WizardRules.STEP_PACK);
		AccelPagesTest.check("without a pack the proposal cannot be run, the button is disabled with the reason", html.contains("data-mdmf-action=\"ACCEL_RUN_PROPOSAL\"")
			&& html.contains("data-mdmf-action=\"ACCEL_RUN_PROPOSAL\" disabled=\"disabled\" title=\"" + AccelPagesTest.message("wizard.blocked.needPack") + "\""));
		snapshot.projectPacks.add(AccelPagesTest.chosen("company"));
		html = AccelPagesTest.render(snapshot, WizardRules.STEP_SPECIFICATION);
		AccelPagesTest.check("title", html.contains(AccelPagesTest.message("wizard.accel.spec.title")));
		AccelPagesTest.check("the need comes back from the stored specification", html.contains("We need a supplier reference for purchasing."));
		AccelPagesTest.check("the chosen pack, its version and who chose it", html.contains("Organisation / Company (B2B)") && html.contains("v1.0.0") && html.contains(AccelPagesTest.message("wizard.accel.chosenBy.llm")));
		AccelPagesTest.check("its options are listed", html.contains("bank accounts"));
		AccelPagesTest.check("Run the proposal is enabled and posts ACCEL_RUN_PROPOSAL", html.contains("data-mdmf-action=\"ACCEL_RUN_PROPOSAL\">"));
		snapshot.accelJob = new WizardSnapshot.AccelJobInfo();
		snapshot.accelJob.message = "Pass 1 of 4";
		snapshot.accelJob.progressJson = "{\"done\":1,\"total\":4}";
		html = AccelPagesTest.render(snapshot, WizardRules.STEP_SPECIFICATION);
		AccelPagesTest.check("a running job shows its message and a bar", html.contains("Pass 1 of 4") && html.contains("style=\"width:25%\""));
		AccelPagesTest.check("and the proposal cannot be run twice", html.contains("data-mdmf-action=\"ACCEL_RUN_PROPOSAL\" disabled=\"disabled\""));
		AccelPagesTest.check("the jobs banner is told a run is on", html.contains("data-mdmf-jobs-active=\"true\""));
	}

	private static void proposalScreen()
	{
		final WizardSnapshot snapshot = AccelPagesTest.proposed();
		final String html = AccelPagesTest.render(snapshot, WizardRules.STEP_PROPOSAL);
		AccelPagesTest.check("title", html.contains(AccelPagesTest.message("wizard.accel.proposal.title")));
		AccelPagesTest.check("grouped by pass: pass 1 and pass 2 heads", html.contains("id=\"mdmf-accel-pass-1\"") && html.contains("id=\"mdmf-accel-pass-2\"")
			&& html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.accel.pass.head", Integer.valueOf(1), WizardMessages.get(Locale.ENGLISH, "wizard.accel.pass.1")))));
		AccelPagesTest.check("a pass nobody ran yet is not drawn as an empty table", !html.contains("id=\"mdmf-accel-pass-3\"") || html.contains(AccelPagesTest.message("wizard.accel.pass.run")));
		AccelPagesTest.check("a PROPOSED line offers Accept, Edit, Reject on the DIFFERENCE nature",
			html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"DIFFERENCE\" data-mdmf-id=\"1\"") && html.contains("data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"DIFFERENCE\" data-mdmf-id=\"1\"")
				&& html.contains("data-mdmf-toggle=\"mdmf-accel-edit-1\""));
		AccelPagesTest.check("Edit holds a textarea with the JSON and an Accept that names it (mdmfDetail)", html.contains("id=\"mdmf-accel-detail-1\"") && html.contains("{&quot;field&quot;:&quot;x&quot;}")
			&& html.contains("data-mdmf-detail-from=\"mdmf-accel-detail-1\""));
		AccelPagesTest.check("a decided line shows its status instead of buttons", html.contains(AccelPagesTest.message("wizard.accel.status.ACCEPTED")) && html.contains(AccelPagesTest.message("wizard.accel.status.REJECTED"))
			&& html.contains(AccelPagesTest.message("wizard.accel.status.APPLIED")));
		AccelPagesTest.check("an ACCEPTED line can still be rejected, a REJECTED one accepted, an APPLIED one neither",
			html.contains("data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"DIFFERENCE\" data-mdmf-id=\"3\"") && !html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"DIFFERENCE\" data-mdmf-id=\"3\"")
				&& html.contains("data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"DIFFERENCE\" data-mdmf-id=\"5\"") && !html.contains("data-mdmf-id=\"6\""));
		AccelPagesTest.check("natures are chips", html.contains(">" + AccelPagesTest.message("wizard.accel.nature.USE_BLOCK") + "</span>") && html.contains(AccelPagesTest.message("wizard.accel.nature.USE_LIBRARY")));
		AccelPagesTest.check("the model's reason is shown", html.contains("From the specification: Compliance document"));
		AccelPagesTest.check("KEEP lines are collapsed behind a disclosure", html.contains("class=\"mdmf-disclosure mdmf-accel-kept\"") && html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.accel.kept.show", Integer.valueOf(1))))
			&& html.contains("/root/Company"));
		AccelPagesTest.check("a KEEP line has no decision buttons", !html.contains("data-mdmf-id=\"2\""));
		AccelPagesTest.check("a line to place and a draft rule are marked", html.contains(AccelPagesTest.message("wizard.accel.chip.toPlace")) && html.contains(AccelPagesTest.message("wizard.accel.chip.draft")));
		AccelPagesTest.check("the look-alike check is a box, with the model's note", html.contains("class=\"mdmf-notice mdmf-accel-check\"") && html.contains("the Contacts block (Fasttrack.lib.phone.isValid)")
			&& html.contains("The model agreed."));
		AccelPagesTest.check("Generate is disabled while lines are open, with its reason", html.contains("data-mdmf-action=\"ACCEL_GENERATE\" disabled=\"disabled\" title=\"" + AccelPagesTest.message("wizard.blocked.needDecisions") + "\"")
			&& html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.accel.generate.left", Integer.valueOf(2)))));
		AccelPagesTest.check("a pass whose predecessor is decided can be run (pass 3 follows a fully decided pass 2? not yet: pass 2 has open lines)", !html.contains("data-mdmf-accel-pass=\"3\""));
		snapshot.differences.get(0).status = "ACCEPTED";
		snapshot.differences.get(3).status = "REJECTED";
		final String ready = AccelPagesTest.render(snapshot, WizardRules.STEP_PROPOSAL);
		AccelPagesTest.check("nothing PROPOSED: Generate is enabled", ready.contains("data-mdmf-action=\"ACCEL_GENERATE\">") && ready.contains(AccelPagesTest.message("wizard.accel.generate.ready")));
		AccelPagesTest.check("and pass 3 can now be run", ready.contains("data-mdmf-action=\"ACCEL_RUN_PASS\"") && ready.contains("data-mdmf-accel-pass=\"3\""));
		AccelPagesTest.check("the decision counters add up", ready.contains("<b>3</b><span>" + AccelPagesTest.message("wizard.accel.decisions.accepted")));
	}

	private static void generationScreen()
	{
		final WizardSnapshot snapshot = AccelPagesTest.proposed();
		for (final WizardSnapshot.DifferenceInfo row : snapshot.differences)
		{
			row.status = "ACCEPTED";
		}
		String html = AccelPagesTest.render(snapshot, WizardRules.STEP_GENERATION);
		AccelPagesTest.check("title", html.contains(AccelPagesTest.message("wizard.accel.gen.title")));
		AccelPagesTest.check("before the generation the primary block posts ACCEL_GENERATE", AccelPagesTest.panel(html, 12).contains("data-mdmf-action=\"ACCEL_GENERATE\">") && html.contains(AccelPagesTest.message("wizard.accel.gen.none")));
		final WizardSnapshot.DomainObjectInfo model = new WizardSnapshot.DomainObjectInfo();
		model.kind = "DATA_MODEL";
		model.name = "Suppliers_Master";
		model.stepStatus = "DONE";
		model.createdOn = "2026-10-12 10:30";
		snapshot.domainObjects.add(model);
		final WizardSnapshot.DomainObjectInfo draft = new WizardSnapshot.DomainObjectInfo();
		draft.kind = "SCRIPT";
		draft.name = "KbisRecent";
		draft.detail = "draft";
		draft.stepStatus = "DONE";
		draft.createdOn = "2026-10-12 10:41";
		snapshot.domainObjects.add(draft);
		final WizardSnapshot.DomainObjectInfo running = new WizardSnapshot.DomainObjectInfo();
		running.kind = "WORKFLOW_MODEL";
		running.name = "SupplierCreationRequest";
		running.stepStatus = "RUNNING";
		snapshot.domainObjects.add(running);
		snapshot.accelJob = new WizardSnapshot.AccelJobInfo();
		snapshot.accelJob.message = "Workflow";
		html = AccelPagesTest.render(snapshot, WizardRules.STEP_GENERATION);
		AccelPagesTest.check("each object is listed with its kind and name", html.contains(AccelPagesTest.message("wizard.accel.kind.DATA_MODEL")) && html.contains("<code>Suppliers_Master</code>")
			&& html.contains("<code>SupplierCreationRequest</code>"));
		AccelPagesTest.check("the state circles follow the step status", html.contains("mdmf-sc-done") && html.contains("mdmf-sc-running") && html.contains("mdmf-sc-needsPerson"));
		AccelPagesTest.check("the count is said", html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.accel.gen.count", Integer.valueOf(2), Integer.valueOf(3)))));
		AccelPagesTest.check("the draft rule is held back and said", html.contains("class=\"mdmf-notice mdmf-notice-warn mdmf-accel-draft\"") && html.contains(AccelPagesTest.message("wizard.accel.draft.title")));
		AccelPagesTest.check("the job in progress is shown", html.contains("class=\"mdmf-panel mdmf-accel-job\"") && html.contains("Workflow"));
		AccelPagesTest.check("no second Generate while a job runs", !AccelPagesTest.panel(html, 12).contains("data-mdmf-action=\"ACCEL_GENERATE\""));
		AccelPagesTest.check("traceability: pack version, decisions, owner, last write", html.contains(AccelPagesTest.message("wizard.accel.trace.title")) && html.contains("Organisation / Company (B2B) v1.0.0")
			&& html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.accel.trace.decidedCounts", Integer.valueOf(6), Integer.valueOf(5), Integer.valueOf(0))))
			&& html.contains("j.martin") && html.contains("2026-10-12 10:41"));
		snapshot.accelJob = null;
		snapshot.project.domainModel = "Suppliers_Master";
		html = AccelPagesTest.render(snapshot, WizardRules.STEP_GENERATION);
		AccelPagesTest.check("once generated, a way on to the summary and no Generate", AccelPagesTest.panel(html, 12).contains("data-mdmf-goto-step=\"8\"") && !AccelPagesTest.panel(html, 12).contains("data-mdmf-action=\"ACCEL_GENERATE\""));
	}

	private static void summaryScreen()
	{
		final WizardSnapshot snapshot = AccelPagesTest.proposed();
		String html = AccelPagesTest.render(snapshot, WizardRules.STEP_SUMMARY);
		AccelPagesTest.check("the summary of a CENTRALISATION project is its own", html.contains(AccelPagesTest.message("wizard.accel.sum.title")) && html.contains(AccelPagesTest.message("wizard.accel.sum.notYet")));
		snapshot.project.domainDataspace = "Suppliers";
		snapshot.project.domainDataset = "Suppliers_Master";
		snapshot.project.domainModel = "Suppliers_Master";
		html = AccelPagesTest.render(snapshot, WizardRules.STEP_SUMMARY);
		AccelPagesTest.check("the domain names are given", html.contains("<code>Suppliers</code>") && html.contains("<code>Suppliers_Master</code>"));
		AccelPagesTest.check("the manual steps are listed", html.contains(AccelPagesTest.message("wizard.accel.sum.manual.title")) && html.contains(AccelPagesTest.message("wizard.accel.sum.manual.permissions")));
		AccelPagesTest.check("what comes after is said", html.contains(AccelPagesTest.message("wizard.accel.sum.after.version")));
		AccelPagesTest.check("a Consolidation summary is not drawn for it", !html.contains(AccelPagesTest.message("wizard.step8.title")));
	}

	private static void noMessageKeyLeaksInEitherLanguage()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final WizardSnapshot snapshot = AccelPagesTest.proposed();
			snapshot.accelJob = new WizardSnapshot.AccelJobInfo();
			final WizardSnapshot.DomainObjectInfo object = new WizardSnapshot.DomainObjectInfo();
			object.kind = "WORKFLOW_PUBLICATION";
			object.name = "X";
			snapshot.domainObjects.add(object);
			for (final int step : new int[] { WizardRules.STEP_PACK, WizardRules.STEP_SPECIFICATION, WizardRules.STEP_PROPOSAL, WizardRules.STEP_GENERATION, WizardRules.STEP_SUMMARY })
			{
				final WizardPage.Model model = AccelPagesTest.model(snapshot, step);
				model.locale = locale;
				final String html = WizardPage.render(model);
				AccelPagesTest.check(locale + " step " + step + ": no raw wizard.accel key on the page", !html.contains("wizard.accel.") && !html.contains("wizard.step.pack") && !html.contains("wizard.blocked."));
			}
		}
		for (final String kind : new String[] { "DATA_MODEL", "PUBLICATION", "DATASPACE", "DATASET", "SCRIPT", "WORKFLOW_MODEL", "WORKFLOW_PUBLICATION", "LAYOUT", "MATCHING_POLICY", "PERMISSIONS", "REFERENCE_ROWS" })
		{
			AccelPagesTest.check("a label for object kind " + kind, !("wizard.accel.kind." + kind).equals(WizardMessages.get(Locale.ENGLISH, "wizard.accel.kind." + kind))
				&& !("wizard.accel.kind." + kind).equals(WizardMessages.get(Locale.FRENCH, "wizard.accel.kind." + kind)));
		}
		for (final String nature : new String[] { "ADD", "CHANGE", "REMOVE", "USE_BLOCK", "USE_LIBRARY", "USE_WORKFLOW", "KEEP" })
		{
			AccelPagesTest.check("a label for nature " + nature, !("wizard.accel.nature." + nature).equals(WizardMessages.get(Locale.FRENCH, "wizard.accel.nature." + nature)));
		}
	}

	private static void consolidationRailIsUntouched()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		snapshot.project = AccelPagesTest.snapshot().project;
		snapshot.project.style = "CONSOLIDATION";
		final String html = AccelPagesTest.render(snapshot, WizardRules.STEP_PROJECT);
		for (int step = 1; step <= 8; step++)
		{
			AccelPagesTest.check("Consolidation keeps step " + step, html.contains("<li data-step=\"" + step + "\"") && html.contains("<b>" + step + "</b>"));
		}
		AccelPagesTest.check("and none of the new ones", !html.contains("<li data-step=\"9\"") && !html.contains("<li data-step=\"12\""));
		AccelPagesTest.check("Consolidation reads x of 8", html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.nav.progress", "1", "8"))));
	}

	private static int count(final String pHaystack, final String pNeedle)
	{
		int count = 0;
		for (int at = pHaystack.indexOf(pNeedle); at >= 0; at = pHaystack.indexOf(pNeedle, at + pNeedle.length()))
		{
			count++;
		}
		return count;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
