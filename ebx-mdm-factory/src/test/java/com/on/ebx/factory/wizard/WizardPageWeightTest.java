package com.on.ebx.factory.wizard;

import java.util.Locale;

import com.on.ebx.factory.review.ReviewLine;

/**
 * JUnit-free: run the main, a failure throws. Wave 11 (audit F-42): what the wizard page weighs and what it folds - the step shown is the only one written in
 * full, step 4 folds its business objects beyond {@link WizardPage#ENTITY_FOLD_THRESHOLD} (never dropping one), filters them, and cuts the long groups of
 * its "decisions left" card. The fixture is a realistic project (a business object has 25 fields, each with two mappings, a feed with 25 mapping rules and
 * a quality proposal on every other field). {@code -Dmdmf.sizeReport=true} prints the sizes the documentation quotes.
 */
public final class WizardPageWeightTest
{
	public static void main(final String[] pArgs)
	{
		WizardPageWeightTest.onlyTheStepShownIsWrittenInFull();
		WizardPageWeightTest.everyStepKeepsItsPanelAndItsRailEntry();
		WizardPageWeightTest.theFormerPageStillExistsForARendererTest();
		WizardPageWeightTest.step4FoldsBeyondTheThresholdAndDropsNothing();
		WizardPageWeightTest.anOpenCardIsWrittenInFullAndOffersToFold();
		WizardPageWeightTest.unfoldAllOpensEveryCardButTheOnesFoldedAfterwards();
		WizardPageWeightTest.theFilterFindsEntitiesAndFieldsAndFoldsTheOthers();
		WizardPageWeightTest.fewEntitiesAreNotFolded();
		WizardPageWeightTest.theDecisionsLeftCardCutsLongGroupsAndOffersTheRest();
		WizardPageWeightTest.aCardWithoutFactsSaysItIsBeingChecked();
		WizardPageWeightTest.theWeightFallsAsDocumented();
		System.out.println("WizardPageWeightTest OK");
	}

	// ------------------------------------------------------------------------------------------------ one step in full

	private static void onlyTheStepShownIsWrittenInFull()
	{
		for (int step = 1; step <= WizardRules.STEP_COUNT; step++)
		{
			final String html = WizardPage.render(WizardPageWeightTest.model(3, 6, step, true));
			for (int other = 1; other <= WizardRules.STEP_COUNT; other++)
			{
				final boolean placeholder = html.contains("data-mdmf-deferred-step=\"" + other + "\"");
				WizardPageWeightTest.check("step " + step + " shown: the placeholder of step " + other + " is " + (other == step ? "absent" : "present"), placeholder == (other != step));
			}
		}
		final String step4 = WizardPage.render(WizardPageWeightTest.model(3, 6, 4, true));
		WizardPageWeightTest.check("step 4 shown: its own cards are there", step4.contains("id=\"mdmf-ENTITY-E0\"") && step4.contains("id=\"mdmf-ATTRIBUTE-A0_0\""));
		final String step1 = WizardPage.render(WizardPageWeightTest.model(3, 6, 1, true));
		WizardPageWeightTest.check("step 1 shown: step 4's cards are not written", !step1.contains("id=\"mdmf-ENTITY-E0\"") && !step1.contains("id=\"mdmf-ATTRIBUTE-A0_0\""));
		WizardPageWeightTest.check("step 1 shown: its own fields are", step1.contains("id=\"mdmfCode\""));
	}

	private static void everyStepKeepsItsPanelAndItsRailEntry()
	{
		final String html = WizardPage.render(WizardPageWeightTest.model(3, 6, 4, true));
		for (int step = 1; step <= WizardRules.STEP_COUNT; step++)
		{
			WizardPageWeightTest.check("a panel for step " + step + " wizard.js can find", html.contains("<section class=\"mdmf-wiz-panel\" data-step=\"" + step + "\"")
				|| html.contains("class=\"mdmf-panel mdmf-wiz-panel\" data-step=\"" + step + "\""));
			WizardPageWeightTest.check("the rail entry of step " + step, html.contains("<li data-step=\"" + step + "\""));
		}
		WizardPageWeightTest.check("one panel only is visible", WizardPageWeightTest.count(html, "data-step=\"4\" hidden=\"hidden\"") == 0
			&& WizardPageWeightTest.count(html, "<section class=\"mdmf-wiz-panel\" data-step=\"3\" hidden=\"hidden\"") == 1);
		WizardPageWeightTest.check("a placeholder carries the STEP_VIEW fallback button", html.contains("data-mdmf-action=\"STEP_VIEW\""));
		WizardPageWeightTest.check("the placeholder is in the viewer's language", WizardPage.render(WizardPageWeightTest.modelIn(Locale.FRENCH)).contains("pas encore affich"));
		// landing-walkthrough lot (2026-10-16): the placeholder does not claim to be loading, names the step on its button, and carries the loading line wizard.js swaps in
		final String placeholderPage = WizardPage.render(WizardPageWeightTest.model(3, 6, 4, true));
		WizardPageWeightTest.check("the placeholder says the step is not shown yet", placeholderPage.contains("This step is not shown yet.") && !placeholderPage.contains(">Loading this step"));
		WizardPageWeightTest.check("its button names the step", placeholderPage.contains("Show the Landing step") || placeholderPage.contains("Show the Landing"));
		WizardPageWeightTest.check("it carries the loading text for wizard.js", placeholderPage.contains("data-mdmf-loading-text="));
		WizardPageWeightTest.check("Back and Next are still there", html.contains("id=\"mdmfWizBack\"") && html.contains("id=\"mdmfWizNext\""));
	}

	private static void theFormerPageStillExistsForARendererTest()
	{
		final String all = WizardPage.render(WizardPageWeightTest.model(3, 6, 1, false));
		WizardPageWeightTest.check("without currentStepOnly every step is written", all.contains("id=\"mdmf-ENTITY-E0\"") && !all.contains("data-mdmf-deferred-step=\"4\""));
	}

	// ------------------------------------------------------------------------------------------------------ step 4 folds

	private static void step4FoldsBeyondTheThresholdAndDropsNothing()
	{
		final int entities = WizardPage.ENTITY_FOLD_THRESHOLD + 5;
		final String html = WizardPage.render(WizardPageWeightTest.model(entities, 10, 4, true));
		for (int e = 0; e < entities; e++)
		{
			WizardPageWeightTest.check("entity " + e + " keeps its card (folded)", html.contains("id=\"mdmf-ENTITY-E" + e + "\""));
			WizardPageWeightTest.check("entity " + e + " says Show", html.contains("data-mdmf-action=\"STAGING_ENTITY_OPEN\" data-mdmf-id=\"E" + e + "\""));
		}
		WizardPageWeightTest.check("every card is folded", WizardPageWeightTest.count(html, "mdmf-entity mdmf-entity-folded") == entities);
		WizardPageWeightTest.check("none of their fields is written", !html.contains("id=\"mdmf-ATTRIBUTE-A0_0\""));
		WizardPageWeightTest.check("the summary says what is inside (10 fields, 7 to decide, 5 proposals, 1 source link)",
			html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.fold.summary", 10, 7, 5, 1)));
		WizardPageWeightTest.check("the strip says how many there are and how many are open", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.fold.hint", entities, 0,
			WizardPage.ENTITY_FOLD_THRESHOLD)));
		WizardPageWeightTest.check("the filter box and both fold buttons", html.contains("id=\"mdmfStagingFilterInput\"") && html.contains("data-mdmf-action=\"STAGING_ENTITY_OPEN_ALL\"")
			&& html.contains("data-mdmf-action=\"STAGING_ENTITY_CLOSE_ALL\""));
		WizardPageWeightTest.check("the page-wide bulk buttons are still there", html.contains("data-mdmf-action=\"STAGING_BULK_ACCEPT\""));
	}

	private static void anOpenCardIsWrittenInFullAndOffersToFold()
	{
		final WizardPage.Model model = WizardPageWeightTest.model(WizardPage.ENTITY_FOLD_THRESHOLD + 2, 10, 4, true);
		model.stagingOpenEntities.add("E1");
		final String html = WizardPage.render(model);
		WizardPageWeightTest.check("E1 is written in full", html.contains("id=\"mdmf-ATTRIBUTE-A1_0\"") && !html.contains("id=\"mdmf-ATTRIBUTE-A0_0\""));
		WizardPageWeightTest.check("E1 offers Fold, not Show", html.contains("data-mdmf-action=\"STAGING_ENTITY_CLOSE\" data-mdmf-id=\"E1\"")
			&& !html.contains("data-mdmf-action=\"STAGING_ENTITY_OPEN\" data-mdmf-id=\"E1\""));
		WizardPageWeightTest.check("the others stay folded", WizardPageWeightTest.count(html, "mdmf-entity mdmf-entity-folded") == WizardPage.ENTITY_FOLD_THRESHOLD + 1);
		WizardPageWeightTest.check("the open card keeps its own bulk buttons", html.contains("data-mdmf-action=\"STAGING_BULK_ACCEPT_ENTITY\" data-mdmf-id=\"E1\""));
	}

	private static void unfoldAllOpensEveryCardButTheOnesFoldedAfterwards()
	{
		final WizardPage.Model model = WizardPageWeightTest.model(WizardPage.ENTITY_FOLD_THRESHOLD + 2, 4, 4, true);
		model.stagingOpenAll = true;
		model.stagingClosedEntities.add("E2");
		final String html = WizardPage.render(model);
		WizardPageWeightTest.check("every card but E2 is open", html.contains("id=\"mdmf-ATTRIBUTE-A0_0\"") && html.contains("id=\"mdmf-ATTRIBUTE-A4_0\"") && !html.contains("id=\"mdmf-ATTRIBUTE-A2_0\""));
		WizardPageWeightTest.check("E2 is the one folded", WizardPageWeightTest.count(html, "mdmf-entity mdmf-entity-folded") == 1);
	}

	private static void theFilterFindsEntitiesAndFieldsAndFoldsTheOthers()
	{
		final WizardPage.Model model = WizardPageWeightTest.model(6, 5, 4, true);
		model.snapshot.stagingEntities.get(2).name = "Customer";
		model.snapshot.stagingEntities.get(4).attributes.get(3).name = "emailAddress";
		model.stagingFilter = "customer";
		String html = WizardPage.render(model);
		WizardPageWeightTest.check("the entity named Customer is open", html.contains("id=\"mdmf-ATTRIBUTE-A2_0\"") && !html.contains("id=\"mdmf-ATTRIBUTE-A0_0\""));
		WizardPageWeightTest.check("the others are folded, not removed", WizardPageWeightTest.count(html, "mdmf-entity mdmf-entity-folded") == 5 && html.contains("id=\"mdmf-ENTITY-E0\""));
		WizardPageWeightTest.check("the strip says 1 of 6 matched", html.contains(WizardPage.esc(WizardMessages.get(Locale.ENGLISH, "wizard.step4.filter.result", 1, 6, "customer"))));
		WizardPageWeightTest.check("a way to clear the filter", html.contains("data-mdmf-filter-clear=\"true\""));

		model.stagingFilter = "EMAILADDRESS";
		html = WizardPage.render(model);
		WizardPageWeightTest.check("a field name finds its entity whatever the case", html.contains("id=\"mdmf-ATTRIBUTE-A4_3\"") && !html.contains("id=\"mdmf-ATTRIBUTE-A2_0\""));
		WizardPageWeightTest.check("the matching field is marked", html.contains("<tr id=\"mdmf-ATTRIBUTE-A4_3\" class=\"mdmf-filter-hit\">") && !html.contains("<tr id=\"mdmf-ATTRIBUTE-A4_0\" class=\"mdmf-filter-hit\">"));

		model.stagingFilter = "entity0 attribute3";
		WizardPageWeightTest.check("every word must match, the name of one and a field of the same (only E0)", WizardPageWeightTest.count(WizardPage.render(model), "mdmf-entity mdmf-entity-folded") == 5);
		model.stagingFilter = "entity0 zzz";
		WizardPageWeightTest.check("a word that matches nothing leaves no match", WizardPageWeightTest.count(WizardPage.render(model), "mdmf-entity mdmf-entity-folded") == 6);
		model.stagingFilter = "nothing like it";
		html = WizardPage.render(model);
		WizardPageWeightTest.check("a filter that matches nothing folds all, removes none", WizardPageWeightTest.count(html, "mdmf-entity mdmf-entity-folded") == 6);

		model.stagingFilter = "<b>x</b>";
		WizardPageWeightTest.check("the filter text is escaped", !WizardPage.render(model).contains("<b>x</b>"));
	}

	private static void fewEntitiesAreNotFolded()
	{
		final String html = WizardPage.render(WizardPageWeightTest.model(WizardPage.ENTITY_FOLD_THRESHOLD, 4, 4, true));
		WizardPageWeightTest.check("no fold strip, no folded card", !html.contains("mdmf-entity-fold-bar") && !html.contains("mdmf-entity-folded"));
		WizardPageWeightTest.check("every card is open, as before", html.contains("id=\"mdmf-ATTRIBUTE-A0_0\"") && html.contains("id=\"mdmf-ATTRIBUTE-A2_0\""));
		WizardPageWeightTest.check("no Fold button on a card that cannot be folded", !html.contains("STAGING_ENTITY_CLOSE\""));
	}

	private static void theDecisionsLeftCardCutsLongGroupsAndOffersTheRest()
	{
		final WizardPage.Model model = WizardPageWeightTest.model(4, 20, 4, true);
		for (final WizardSnapshot.StagingEntityInfo entity : model.snapshot.stagingEntities)
		{
			entity.status = "ACCEPTED"; // the card gathers what is left under the accepted business objects
		}
		String html = WizardPage.render(model);
		final String fieldsShown = WizardMessages.get(Locale.ENGLISH, "wizard.step4.still.more.cut", WizardPage.STILL_GROUP_LIMIT, 60);
		WizardPageWeightTest.check("the fields group is cut at the limit and says so (" + fieldsShown + ")", html.contains(fieldsShown));
		WizardPageWeightTest.check("its button names the group and the rest", html.contains("data-mdmf-action=\"STAGING_STILL_MORE\" data-mdmf-id=\"fields\""));
		WizardPageWeightTest.check("the group's title still counts all of them", html.contains("<span class=\"mdmf-chip mdmf-chip-target\">60</span>"));
		final int stillItems = WizardPageWeightTest.count(html.substring(html.indexOf("id=\"mdmf-still-to-decide\"")), "data-mdmf-still-group=\"fields\"");
		WizardPageWeightTest.check("one fields group", stillItems == 1);
		model.stillOpenGroups.add("fields");
		html = WizardPage.render(model);
		WizardPageWeightTest.check("opened: every field is there and the list can be folded again", html.contains("id=\"mdmf-still-ATTRIBUTE-A3_19\"")
			&& html.contains("data-mdmf-action=\"STAGING_STILL_LESS\" data-mdmf-id=\"fields\"") && html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step4.still.more.all", 60, 60)));
		final String small = WizardPage.render(WizardPageWeightTest.model(1, 6, 4, true));
		WizardPageWeightTest.check("a group at or under the limit has no such row", !small.contains("mdmf-still-more"));
	}

	// -------------------------------------------------------------------------------------- the checking card (audit F-48)

	private static void aCardWithoutFactsSaysItIsBeingChecked()
	{
		final WizardPage.Model model = WizardPageWeightTest.model(1, 2, 2, true);
		model.snapshot.sources.clear(); // no application yet: the queued ones show as full cards
		model.sourcesRunRunning = true;
		model.sourcesFactsChecking = true;
		model.sourcesRunCards.add(new WizardPage.Model.SourcesRunCard("CRM_CONN", "CRM_CONN", null, "RUNNING", "", ""));
		final ApplicationStagesHolder ready = new ApplicationStagesHolder();
		model.sourcesRunCards.add(new WizardPage.Model.SourcesRunCard("ERP_CONN", "ERP", ready.pipeline(), "DONE", "Ready", ""));
		final String html = WizardPage.render(model);
		WizardPageWeightTest.check("the root asks the script to look again", html.contains(" data-mdmf-sources-checking=\"true\""));
		WizardPageWeightTest.check("the card says checking, no stage", html.contains(WizardMessages.get(Locale.ENGLISH, "wizard.sources.checking")));
		WizardPageWeightTest.check("and offers Check again", html.contains("id=\"mdmf-app-progress-CRM_CONN\"") && html.contains("data-mdmf-action=\"SOURCES_RUN_POLL\""));
		WizardPageWeightTest.check("the card that has facts still draws its pipeline", html.contains("mdmf-src2-pipe-circle"));
		model.sourcesFactsChecking = false;
		WizardPageWeightTest.check("no attribute when nothing is being checked", !WizardPage.render(model).contains("data-mdmf-sources-checking"));

		// an application is already ready: the cards give way to one line per application that is not, and a connection still being checked is one of them
		final WizardPage.Model withReady = WizardPageWeightTest.model(1, 2, 2, true);
		final WizardSnapshot.SourceInfo ready2 = new WizardSnapshot.SourceInfo();
		ready2.id = "9";
		ready2.sourceSystem = "SAP";
		withReady.snapshot.sources.add(ready2);
		withReady.sourcesRunCards.add(new WizardPage.Model.SourcesRunCard("CRM_CONN", "CRM_CONN", null, "RUNNING", "", ""));
		final String line = WizardPage.render(withReady);
		WizardPageWeightTest.check("a line for it, saying it is checked, not that it is stuck", line.contains("mdmf-src2-stuck-row") && line.contains(WizardMessages.get(Locale.ENGLISH, "wizard.sources.checking"))
			&& !line.contains(WizardMessages.get(Locale.ENGLISH, "wizard.review.stuck.lineOnly")));
	}

	/** A diagnosis of a connection that is ready, built the way the pure rule does. */
	private static final class ApplicationStagesHolder
	{
		ApplicationStages.Pipeline pipeline()
		{
			final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
			facts.connectorId = "ERP_CONN";
			facts.connectorFound = true;
			facts.connectorActive = true;
			facts.catalogOperation = true;
			facts.planAction = "LINKED";
			facts.planSourceId = "S1";
			facts.scanMappingId = Integer.valueOf(5);
			facts.sourceExists = true;
			facts.sourceActive = true;
			facts.assetsScanned = true;
			facts.assetsWithColumns = true;
			facts.lastScanStatus = "OK";
			return ApplicationStages.from(SourceOnboarding.diagnose(facts));
		}
	}

	// ----------------------------------------------------------------------------------------------------- the numbers

	private static void theWeightFallsAsDocumented()
	{
		final int[][] cases = { { 2, 25 }, { 12, 25 }, { 40, 40 } };
		final boolean report = Boolean.getBoolean("mdmf.sizeReport");
		for (final int[] c : cases)
		{
			// what the page weighed before wave 11: every step written, every card open, no group of the decisions-left card cut
			final WizardPage.Model former = WizardPageWeightTest.model(c[0], c[1], 4, false);
			former.stagingOpenAll = true;
			former.stillOpenGroups.addAll(java.util.Arrays.asList("fields", "rules", "checks", "toApply", "links"));
			final int before = WizardPage.render(former).length();
			final StringBuilder line = new StringBuilder(c[0] + " business objects x " + c[1] + " fields: before wave 11 " + before / 1024 + " KB; one step:");
			int worst = 0;
			for (int step = 1; step <= WizardRules.STEP_COUNT; step++)
			{
				final int size = WizardPage.render(WizardPageWeightTest.model(c[0], c[1], step, true)).length();
				worst = Math.max(worst, size);
				line.append(" s").append(step).append("=").append(size / 1024).append("K");
			}
			if (report)
			{
				System.out.println(line);
			}
			if (c[0] > WizardPage.ENTITY_FOLD_THRESHOLD)
			{
				WizardPageWeightTest.check(line + " - at most a fifth of the whole page, and under 200 KB, once the cards fold", worst * 5 < before && worst < 200 * 1024);
			}
			else
			{
				WizardPageWeightTest.check(line + " - the steps other than 4 are small", WizardPage.render(WizardPageWeightTest.model(c[0], c[1], 1, true)).length() < 40 * 1024);
			}
		}
	}

	// ----------------------------------------------------------------------------------------------------------- fixture

	private static WizardPage.Model modelIn(final Locale pLocale)
	{
		final WizardPage.Model model = WizardPageWeightTest.model(1, 2, 1, true);
		model.locale = pLocale;
		return model;
	}

	/**
	 * {@code pEntities} business objects of {@code pFields} fields: each field has two mappings, every fourth is accepted, every other one has a quality proposal;
	 * each object has one feed with a rule per field. Every fourth object is accepted. Every step reachable.
	 */
	static WizardPage.Model model(final int pEntities, final int pFields, final int pStep, final boolean pCurrentStepOnly)
	{
		final WizardPage.Model model = new WizardPage.Model();
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.id = "77";
		project.code = "Vol";
		project.label = "Volume project";
		project.mode = "GREENFIELD";
		snapshot.project = project;
		final WizardSnapshot.SourceInfo source = new WizardSnapshot.SourceInfo();
		source.id = "1";
		source.sourceSystem = "ERP";
		source.status = "GENERATED";
		snapshot.sources.add(source);
		for (int e = 0; e < pEntities; e++)
		{
			final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
			entity.id = "E" + e;
			entity.name = "Entity" + e;
			entity.status = e % 4 == 0 ? "ACCEPTED" : "PROPOSED";
			entity.confidence = Integer.valueOf(80);
			final WizardSnapshot.FeedInfo feed = new WizardSnapshot.FeedInfo();
			feed.id = "F" + e;
			feed.sourceSystem = "ERP";
			feed.landingTableName = "T" + e;
			feed.status = "PROPOSED";
			feed.confidence = Integer.valueOf(75);
			for (int a = 0; a < pFields; a++)
			{
				final WizardSnapshot.AttributeInfo attribute = new WizardSnapshot.AttributeInfo();
				attribute.id = "A" + e + "_" + a;
				attribute.name = "attribute" + a;
				attribute.status = a % 4 == 0 ? "ACCEPTED" : "PROPOSED";
				attribute.confidence = Integer.valueOf(70 + a % 30);
				attribute.maxLength = Integer.valueOf(50);
				attribute.rationale = "Column ATTRIBUTE_" + a + " of the landing table is filled in 98 % of the rows.";
				for (int m = 0; m < 2; m++)
				{
					final WizardSnapshot.MappingInfo mapping = new WizardSnapshot.MappingInfo();
					mapping.id = "M" + e + "_" + a + "_" + m;
					mapping.confidence = Integer.valueOf(80);
					attribute.mappings.add(mapping);
				}
				attribute.mappingCount = 2;
				attribute.mappingFeedingCount = 2;
				entity.attributes.add(attribute);
				final WizardSnapshot.FeedMappingInfo rule = new WizardSnapshot.FeedMappingInfo();
				rule.id = "FM" + e + "_" + a;
				rule.landingColumn = "ATTRIBUTE_" + a;
				rule.attributeName = "attribute" + a;
				rule.confidence = Integer.valueOf(80);
				rule.transformationDisplay = "trim()";
				feed.mappings.add(rule);
				if (a % 2 == 0)
				{
					final ReviewLine line = new ReviewLine(ReviewLine.QUALITY, "Q" + e + "_" + a, "Entity" + e + ".attribute" + a, "WARN - Must not be empty", "RULE",
						Integer.valueOf(90), "Filled in every sampled row.", "PROPOSED", false, false);
					final WizardSnapshot.ProposalLineInfo proposal = new WizardSnapshot.ProposalLineInfo(line);
					proposal.attributeId = attribute.id;
					proposal.severity = "WARN";
					proposal.valuesSeen = Long.valueOf(500);
					proposal.valuesFailing = Long.valueOf(2);
					proposal.qualityEvidence = "500 values seen, 2 failing";
					entity.proposalLines.add(proposal);
				}
			}
			entity.feeds.add(feed);
			snapshot.stagingEntities.add(entity);
		}
		model.snapshot = snapshot;
		model.administrator = true;
		model.designer = true;
		model.locale = Locale.ENGLISH;
		model.stepStates = new WizardRules.StepState[WizardRules.STEP_COUNT + 1];
		for (int step = 1; step <= WizardRules.STEP_COUNT; step++)
		{
			model.stepStates[step] = WizardRules.StepState.reachable(false);
		}
		model.currentStep = pStep;
		model.currentStepOnly = pCurrentStepOnly;
		return model;
	}

	private static int count(final String pText, final String pPart)
	{
		int count = 0;
		for (int at = pText.indexOf(pPart); at >= 0; at = pText.indexOf(pPart, at + pPart.length()))
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
