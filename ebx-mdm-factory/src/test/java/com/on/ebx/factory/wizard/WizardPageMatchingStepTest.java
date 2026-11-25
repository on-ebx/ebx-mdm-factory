package com.on.ebx.factory.wizard;

import java.util.Locale;

import com.on.ebx.factory.matching.MatchResultsView;
import com.on.ebx.factory.matching.MatchStats;
import com.on.ebx.factory.matching.MatchingActivity;
import com.on.ebx.factory.review.ReviewLine;

/**
 * JUnit-free: run the main, a failure throws. UX lot U1 (2026-10-13, RAPPORT 2.11): step 6 as rendered - one proposal button, a card for every accepted object
 * without a plan, no development note, excuse lines only while true, volume warnings folded on a small table, the results row in one sentence. The scenario is
 * the measured one: project UxCrmSf, Organisation / Personne / Adresse accepted, 81 rows.
 */
public final class WizardPageMatchingStepTest
{
	private static final String NOT_FILTERED_PLAN = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":80,\"fields\":["
		+ "{\"field\":\"lastName\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":3}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]},\"clusteringFields\":[]}";
	private static final String RAW_LOG = "Matching run on Adresse FAILED: the add-on's process failed at 75.0 % - reason from ebx-addon-mame.log: [MatchingManager] Got exception "
		+ "in matching operation | [MatchingManager] Process Exception. Scope: incremental - 30 of 81 records were pivots (the last loaded batch); the other 51 were not matched.";

	public static void main(final String[] pArgs)
	{
		WizardPageMatchingStepTest.afterTheRulesEveryAcceptedObjectHasACard();
		WizardPageMatchingStepTest.afterTheModelTheSkippedObjectSaysWhy();
		WizardPageMatchingStepTest.theModelCanBeAskedFromTheCardOnlyWhenItCanAnswer();
		WizardPageMatchingStepTest.excuseLinesAreShownOnlyWhileTrue();
		WizardPageMatchingStepTest.volumeWarningsFoldOnASmallTableAndShowOnABigOne();
		WizardPageMatchingStepTest.theResultsRowIsOneSentenceWithTheRawTextFolded();
		WizardPageMatchingStepTest.runWaitsForTheConfiguration();
		WizardPageMatchingStepTest.anObjectTheRunDidNotApplyGetsItsOwnRow();
		WizardPageMatchingStepTest.theGoldenRecordsBlockFoldsTheRawRunText();
		WizardPageMatchingStepTest.theFactorysFallbackPlanHasItsOwnBadgeAndNote();
		WizardPageMatchingStepTest.thePlanCardReadsTheEntitysAttributeLabels();
		WizardPageMatchingStepTest.theRunLineIsWordedInThePersonsLanguage();
		System.out.println("WizardPageMatchingStepTest OK");
	}

	/** Lot M12 (2026-10-18): "2 entities run, 0 not applied, 0 refused. 1 child object has no plan..." stayed English on a French page, and so did the entity's scope fold. */
	private static void theRunLineIsWordedInThePersonsLanguage()
	{
		final WizardPage.Model model = WizardPageMatchingStepTest.model(false);
		model.locale = java.util.Locale.FRENCH;
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp1", "Personne", "LLM", "APPLIED"));
		final MatchingActivity activity = new MatchingActivity();
		activity.kind = MatchingActivity.RUN;
		activity.status = MatchingActivity.DONE;
		activity.startedAt = 1000L;
		activity.endedAt = 4000L;
		activity.message = com.on.ebx.factory.landing.RunTexts.joined(com.on.ebx.factory.landing.RunTexts.matchingResult(2, 0, 0).stored(),
			com.on.ebx.factory.landing.RunTexts.matchingFollows(1).stored()).stored();
		final MatchingActivity.Entity personne = new MatchingActivity.Entity();
		personne.name = "Personne";
		personne.status = MatchingActivity.DONE;
		personne.startedAt = 1000L;
		personne.endedAt = 2000L;
		personne.message = com.on.ebx.factory.landing.RunTexts.matchingFinished("Personne", "DONE", 81, 0, 0, com.on.ebx.factory.landing.RunTexts.matchingScope(true, 81, 81).stored(), "").stored();
		activity.entities.add(personne);
		model.matchingActivity = activity;
		final String html = WizardPage.render(model);
		final int panel = html.indexOf("id=\"mdmfMatchingLive\"");
		final String live = html.substring(panel, html.indexOf("</table>", panel));
		WizardPageMatchingStepTest.check("the run line is French, no marker", live.contains("Objets m\u00E9tier ex\u00E9cut\u00E9s") && live.contains("objet enfant") && !live.contains("entities run")
			&& !live.contains("child object") && !live.contains("[mdmf:") && !live.contains("mdmf%3A"));
		WizardPageMatchingStepTest.check("the entity's scope fold is French", !live.contains("Scope: all records") && !live.contains("Matching run on"));
		WizardPageMatchingStepTest.check("the page polls in its own language", live.contains("matching/activity?lang=fr"));
	}

	private static WizardPage.Model model(final boolean pLlm)
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.administrator = true;
		model.designer = true;
		final WizardSnapshot snapshot = new WizardSnapshot();
		snapshot.llmConfigured = pLlm;
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.id = "1";
		project.code = "UxCrmSf";
		project.label = "UxCrmSf";
		snapshot.project = project;
		for (final String name : new String[] { "Organisation", "Personne", "Adresse" })
		{
			final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
			entity.id = name;
			entity.name = name;
			entity.status = "ACCEPTED";
			snapshot.stagingEntities.add(entity);
		}
		snapshot.projectSteps.add(new WizardSnapshot.PipelineStepInfo("MASTER_LOAD", "DONE", "loaded"));
		final WizardSnapshot.SourceInfo source = new WizardSnapshot.SourceInfo();
		final WizardSnapshot.TableInfo table = new WizardSnapshot.TableInfo();
		table.sourceRowCountKnown = true;
		table.sourceRowCount = 81L;
		source.tables.add(table);
		snapshot.sources.add(source);
		model.snapshot = snapshot;
		model.currentStep = WizardRules.STEP_MATCHING;
		model.stepStates = WizardRules.stepStates(snapshot);
		return model;
	}

	private static WizardSnapshot.MatchPlanInfo plan(final String pId, final String pEntity, final String pOrigin, final String pStatus)
	{
		final WizardSnapshot.MatchPlanInfo plan = new WizardSnapshot.MatchPlanInfo();
		plan.id = pId;
		plan.entityName = pEntity;
		plan.origin = pOrigin;
		plan.status = pStatus;
		plan.requirements = "Same name.";
		return plan;
	}

	private static void rulesRanTask(final WizardPage.Model pModel)
	{
		final RecentTasks.Task task = new RecentTasks.Task();
		task.kind = "MATCHING_SUGGEST_RULES";
		task.status = "DONE";
		task.message = "Nothing to do: ...";
		pModel.recentTasks.add(task);
	}

	private static String en(final String pKey, final Object... pArgs)
	{
		return WizardPage.esc(WizardMessages.get(Locale.ENGLISH, pKey, pArgs));
	}

	// the measured case: "0 plans written, 3 skipped" and a page that kept "No matching plan proposed yet"
	private static void afterTheRulesEveryAcceptedObjectHasACard()
	{
		final WizardPage.Model model = WizardPageMatchingStepTest.model(false);
		String html = WizardPage.render(model);
		WizardPageMatchingStepTest.check("nothing ran yet: the empty notice and no gap card", html.contains(WizardPageMatchingStepTest.en("wizard.step6.empty")) && !html.contains("data-mdmf-gap="));
		WizardPageMatchingStepTest.rulesRanTask(model);
		html = WizardPage.render(model);
		WizardPageMatchingStepTest.check("the empty notice is gone", !html.contains(WizardPageMatchingStepTest.en("wizard.step6.empty")));
		for (final String name : new String[] { "Organisation", "Personne", "Adresse" })
		{
			WizardPageMatchingStepTest.check("a card for " + name, html.contains("data-mdmf-gap=\"" + name + "\"") && html.contains(WizardPageMatchingStepTest.en("wizard.matching.gap.title", name)));
		}
		WizardPageMatchingStepTest.check("the rules' reason in user words", html.contains(WizardPageMatchingStepTest.en("wizard.matching.gap.reason.rules")));
		WizardPageMatchingStepTest.check("what would unblock the rules", html.contains(WizardPageMatchingStepTest.en("wizard.matching.gap.unblock")));
		WizardPageMatchingStepTest.check("the summary names the objects", html.contains("3 of the 3 accepted objects have no matching plan: Organisation, Personne, Adresse."));
		WizardPageMatchingStepTest.check("no internal rule name on the page", !html.contains("CHECK_DIGIT"));
		WizardPageMatchingStepTest.check("the one primary button is named for the plans", html.contains(WizardPageMatchingStepTest.en("wizard.step6.suggest"))
			&& WizardMessages.get(Locale.ENGLISH, "wizard.step6.suggest").equals("Propose the matching plans"));
		WizardPageMatchingStepTest.check("the development note is gone", !html.contains("Validated detail screens") && !html.contains("review-match-plan mockups"));
		final int advanced = html.indexOf("mdmf-step6-advanced");
		WizardPageMatchingStepTest.check("Refine with the language model sits under Advanced", advanced > 0 && html.indexOf("data-mdmf-action=\"MATCHING_SUGGEST_LLM\"") > advanced);
		WizardPageMatchingStepTest.check("no connector: the gap cards offer no model", !html.contains(WizardPageMatchingStepTest.en("wizard.matching.gap.askModel")));
	}

	// the measured case, second half: the model wrote Personne and Adresse and said nothing visible about Organisation
	private static void afterTheModelTheSkippedObjectSaysWhy()
	{
		final WizardPage.Model model = WizardPageMatchingStepTest.model(true);
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp1", "Personne", "LLM", "PROPOSED"));
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp2", "Adresse", "LLM", "PROPOSED"));
		final WizardSnapshot.PipelineStepInfo step = new WizardSnapshot.PipelineStepInfo("MATCHING_SUGGEST_LLM", "DONE", "2 plans");
		step.detail = "{\"notes\":[\"Organisation skipped: names differ too much between the two systems and no identifier is filled.\"]}";
		model.snapshot.projectSteps.add(step);
		final String html = WizardPage.render(model);
		WizardPageMatchingStepTest.check("Organisation has its card", html.contains("data-mdmf-gap=\"Organisation\""));
		WizardPageMatchingStepTest.check("Personne and Adresse have plan cards, not gap cards", !html.contains("data-mdmf-gap=\"Personne\"") && !html.contains("data-mdmf-gap=\"Adresse\""));
		WizardPageMatchingStepTest.check("the model's own words", html.contains("names differ too much between the two systems and no identifier is filled."));
		WizardPageMatchingStepTest.check("the card sits among the plans, in the staging order", html.indexOf("data-mdmf-gap=\"Organisation\"") < html.indexOf(">Personne<"));
		WizardPageMatchingStepTest.check("1 of 3 are missing", html.contains("1 of the 3 accepted objects have no matching plan: Organisation."));
		WizardPageMatchingStepTest.check("the model already ran: no Ask the language model on the card", !html.contains(WizardPageMatchingStepTest.en("wizard.matching.gap.askModel")));
	}

	private static void theModelCanBeAskedFromTheCardOnlyWhenItCanAnswer()
	{
		final WizardPage.Model model = WizardPageMatchingStepTest.model(true);
		WizardPageMatchingStepTest.rulesRanTask(model);
		final String html = WizardPage.render(model);
		WizardPageMatchingStepTest.check("connector configured, model not run: each card offers it", WizardPageMatchingStepTest.count(html, WizardPageMatchingStepTest.en("wizard.matching.gap.askModel")) == 3);
		WizardPageMatchingStepTest.check("the chained pass is announced by the primary button", html.contains(WizardPageMatchingStepTest.en("wizard.step6.suggest.chained")));
		model.designer = false;
		WizardPageMatchingStepTest.check("without the DESIGN right: no Ask button", !WizardPage.render(model).contains(WizardPageMatchingStepTest.en("wizard.matching.gap.askModel")));
	}

	private static void excuseLinesAreShownOnlyWhileTrue()
	{
		final String needAccepted = WizardPageMatchingStepTest.en("wizard.gate.needAcceptedMatchPlan");
		final String needConfigured = WizardPageMatchingStepTest.en("wizard.gate.needConfiguredMatching");
		final String configureFirst = WizardPageMatchingStepTest.en("wizard.step6.run.configureFirst");
		final String saveHint = WizardPageMatchingStepTest.en("wizard.step6.trigger.hint");

		final WizardPage.Model model = WizardPageMatchingStepTest.model(false);
		String html = WizardPage.render(model);
		WizardPageMatchingStepTest.check("no plan: one 'accept' line, once", WizardPageMatchingStepTest.count(html, needAccepted) == 1);
		WizardPageMatchingStepTest.check("no plan: the contradictory 'configure at least one' is gone", !html.contains(needConfigured));
		WizardPageMatchingStepTest.check("no plan: no 'configure matching again' beside Save", !html.contains(saveHint));

		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp1", "Personne", "LLM", "ACCEPTED"));
		html = WizardPage.render(model);
		WizardPageMatchingStepTest.check("accepted, not configured: 'configure first' and no 'accept' excuse", html.contains(configureFirst) && !html.contains(needAccepted) && !html.contains(needConfigured));
		WizardPageMatchingStepTest.check("Configure is enabled then", !html.contains("data-mdmf-action=\"MATCHING_CONFIGURE\" disabled"));

		model.snapshot.matchPlans.get(0).status = "APPLIED";
		html = WizardPage.render(model);
		WizardPageMatchingStepTest.check("configured: no excuse at all", !html.contains(needAccepted) && !html.contains(needConfigured) && !html.contains(configureFirst));
		WizardPageMatchingStepTest.check("configured: Run is enabled", !html.contains("data-mdmf-action=\"MATCHING_RUN\" data-mdmf-scope=\"all\" disabled"));
		WizardPageMatchingStepTest.check("configured: the choice needs a new Configure, and the hint says so", html.contains(saveHint));
	}

	private static void volumeWarningsFoldOnASmallTableAndShowOnABigOne()
	{
		final WizardPage.Model small = WizardPageMatchingStepTest.model(false);
		WizardPageMatchingStepTest.addPlanCard(small, "mp1", "Personne", "PROPOSED");
		final String smallHtml = WizardPage.render(small);
		final int fold = smallHtml.indexOf("mdmf-tree-volume-fold");
		WizardPageMatchingStepTest.check("81 rows: the notes sit under Details", fold > 0);
		WizardPageMatchingStepTest.check("81 rows: automatic merge off is inside the fold", smallHtml.indexOf("AUTOMATIC MERGE IS OFF") > fold);
		final String cost = WizardPage.esc(com.on.ebx.factory.review.ReviewMessages.get(Locale.ENGLISH, "review.match.tree.clustering.notFiltered.cost"));
		WizardPageMatchingStepTest.check("81 rows: the cost sentence is shown once, inside the fold, not in the diagram band",
			WizardPageMatchingStepTest.count(smallHtml, cost) == 1 && smallHtml.indexOf(cost) > fold && smallHtml.indexOf(cost) < smallHtml.indexOf("mdmf-tree-scroll"));

		final WizardPage.Model big = WizardPageMatchingStepTest.model(false);
		big.snapshot.sources.get(0).tables.get(0).sourceRowCount = 1200000L;
		WizardPageMatchingStepTest.addPlanCard(big, "mp1", "Personne", "PROPOSED");
		final String bigHtml = WizardPage.render(big);
		WizardPageMatchingStepTest.check("1.2 million rows: nothing folded, the warning stays inline", !bigHtml.contains("mdmf-tree-volume-fold") && bigHtml.contains("AUTOMATIC MERGE IS OFF"));
		WizardPageMatchingStepTest.check("1.2 million rows: the cost sentence stays in the diagram band", WizardPageMatchingStepTest.count(bigHtml, cost) == 1 && bigHtml.indexOf(cost) > bigHtml.indexOf("mdmf-tree-scroll"));

		// a decided plan: the notice under the card folds the same way
		final WizardPage.Model decided = WizardPageMatchingStepTest.model(false);
		decided.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp1", "Personne", "LLM", "ACCEPTED"));
		decided.snapshot.matchPlans.get(0).requirements = WizardPageMatchingStepTest.NOT_FILTERED_PLAN;
		final String decidedHtml = WizardPage.render(decided);
		WizardPageMatchingStepTest.check("decided plan, 81 rows: candidate-search notice under Details", decidedHtml.contains("mdmf-candidate-search-fold"));
	}

	/** Lot M9 (2026-10-18): the card of a fallback plan (origin RULE, requirements.fallback) says it is the Factory's fallback, not a rule proposal, and why it exists. */
	private static void theFactorysFallbackPlanHasItsOwnBadgeAndNote()
	{
		final WizardPage.Model model = WizardPageMatchingStepTest.model(true);
		final String last = "the proposed decision tree is not valid: UNKNOWN_BRANCH_TARGET: node 'N5'.onFalse = 'N7' is neither a leaf nor a known node id";
		final String fallbackPlan = "{\"decisionTree\":{\"root\":\"F1\",\"nodes\":[{\"id\":\"F1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"mobileE164\",\"algorithm\":\"ebx-addon-mame@exact\","
			+ "\"minimumScore\":100,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]},\"clusteringFields\":[],"
			+ "\"fallback\":{\"afterRefusedProposals\":5,\"lastRefusal\":\"" + last + "\",\"endedBy\":\"SAME_REFUSAL\"}}";
		final WizardSnapshot.MatchPlanInfo plan = WizardPageMatchingStepTest.plan("mp9", "Personne", "RULE", "PROPOSED");
		plan.requirements = fallbackPlan;
		plan.confidence = Integer.valueOf(40);
		model.snapshot.matchPlans.add(plan);
		model.matchLines.put("mp9", new ReviewLine(ReviewLine.MATCH, "mp9", "Personne", "1 tier - suspect only", "RULE", Integer.valueOf(40), "Factory fallback plan ...", "PROPOSED", false, false, null,
			null, false, null, fallbackPlan, null, false, null, null));
		model.matchCanSimulate.put("mp9", Boolean.FALSE);
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp2", "Adresse", "RULE", "PROPOSED"));
		final String html = WizardPage.render(model);
		MatchingPlanCoverage.FallbackInfo marker = MatchingPlanCoverage.fallbackOf(fallbackPlan);
		WizardPageMatchingStepTest.check("the marker is read", marker != null && marker.attempts == 5);
		WizardPageMatchingStepTest.check("the fallback badge, not the rules' one, on the fallback card", html.contains(WizardPageMatchingStepTest.en("wizard.step6.origin.RULE_FALLBACK"))
			&& WizardMessages.get(Locale.ENGLISH, "wizard.step6.origin.RULE_FALLBACK").equals("Factory fallback plan"));
		WizardPageMatchingStepTest.check("the rule plan keeps the rules' badge", html.contains(WizardPageMatchingStepTest.en("wizard.step6.origin.RULE")));
		WizardPageMatchingStepTest.check("the note says how many proposals and quotes the last refusal",
			html.contains("mdmf-match-plan-fallback") && html.contains(WizardPageMatchingStepTest.en("wizard.step6.fallback.note", Integer.valueOf(5), last)));
		WizardPageMatchingStepTest.check("the French badge", WizardMessages.get(Locale.FRENCH, "wizard.step6.origin.RULE_FALLBACK").equals("plan de repli de la fabrique"));
	}

	private static void addPlanCard(final WizardPage.Model pModel, final String pId, final String pEntity, final String pStatus)
	{
		final WizardSnapshot.MatchPlanInfo plan = WizardPageMatchingStepTest.plan(pId, pEntity, "LLM", pStatus);
		plan.requirements = WizardPageMatchingStepTest.NOT_FILTERED_PLAN;
		pModel.snapshot.matchPlans.add(plan);
		pModel.matchLines.put(pId, new ReviewLine(ReviewLine.MATCH, pId, pEntity, "1 tier - suspect only", "LLM", Integer.valueOf(65), "why", pStatus, false, false, null, null, false,
			null, WizardPageMatchingStepTest.NOT_FILTERED_PLAN, null, false, null, null));
		pModel.matchCanSimulate.put(pId, Boolean.FALSE);
	}

	private static void theResultsRowIsOneSentenceWithTheRawTextFolded()
	{
		final WizardPage.Model model = WizardPageMatchingStepTest.model(false);
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp1", "Personne", "LLM", "APPLIED"));
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp2", "Adresse", "LLM", "APPLIED"));
		final MatchingActivity activity = new MatchingActivity();
		activity.kind = MatchingActivity.RUN;
		activity.status = MatchingActivity.FAILED;
		activity.startedAt = 1000L;
		activity.endedAt = 4000L;
		final MatchingActivity.Entity personne = new MatchingActivity.Entity();
		personne.name = "Personne";
		personne.status = MatchingActivity.DONE;
		personne.startedAt = 1000L;
		personne.endedAt = 2000L;
		personne.message = "Matching run on Personne finished (DONE); Xref.goldenKey refreshed (30 set). Scope: incremental - 30 of 81 records were pivots (the last loaded batch); "
			+ "the other 51 were not matched in this run. WARNING: The candidate search of this plan is NOT filtered: measured 9 times slower at 1 million rows.";
		final MatchingActivity.Entity adresse = new MatchingActivity.Entity();
		adresse.name = "Adresse";
		adresse.status = MatchingActivity.FAILED;
		adresse.startedAt = 2000L;
		adresse.endedAt = 3000L;
		adresse.message = WizardPageMatchingStepTest.RAW_LOG;
		activity.entities.add(personne);
		activity.entities.add(adresse);
		model.matchingActivity = activity;
		final MatchResultsView.Input results = new MatchResultsView.Input();
		results.entityId = "Personne";
		results.entityName = "Personne";
		results.kpis = new MatchStats.Kpis(81, new java.util.LinkedHashMap<String, Integer>(), 81, 0, 0, 0, 51);
		model.matchResults.put("mp1", results);
		final String html = WizardPage.render(model);
		final int panel = html.indexOf("id=\"mdmfMatchingLive\"");
		final String live = html.substring(panel, html.indexOf("</table>", panel));
		WizardPageMatchingStepTest.check("columns: compared, pairs, merged, kept apart", live.contains(">Compared<") && live.contains(">Pairs to decide<") && live.contains(">Merged groups<")
			&& live.contains(">Kept apart<"));
		WizardPageMatchingStepTest.check("Personne: the sentence with its scope", live.contains("30 of 81 records compared, no pair to decide, nothing merged"));
		WizardPageMatchingStepTest.check("Personne: the scope in its own column", live.contains("<td>30 / 81</td>"));
		WizardPageMatchingStepTest.check("Adresse: one sentence for the failure", live.contains("Matching failed: the Match and Merge add-on stopped with an internal error. Details below."));
		WizardPageMatchingStepTest.check("Adresse: its scope is still known", WizardPageMatchingStepTest.count(live, "<td>30 / 81</td>") == 2);
		WizardPageMatchingStepTest.check("the raw log text appears only inside a Details fold", WizardPageMatchingStepTest.count(live, "[MatchingManager]") == 2
			&& live.indexOf("[MatchingManager]") > live.indexOf("mdmf-ma-details"));
		WizardPageMatchingStepTest.check("the volume warning is folded on 81 rows (inside Details, never beside the sentence)", live.indexOf("measured 9 times slower") > live.indexOf("mdmf-ma-details")
			&& !live.contains("mdmf-hint mdmf-hint-warn"));
		final WizardPage.Model big = WizardPageMatchingStepTest.model(false);
		big.snapshot.sources.get(0).tables.get(0).sourceRowCount = 2000000L;
		big.snapshot.matchPlans.addAll(model.snapshot.matchPlans);
		big.matchingActivity = activity;
		final MatchResultsView.Input bigResults = new MatchResultsView.Input();
		bigResults.entityId = "Personne";
		bigResults.entityName = "Personne";
		bigResults.kpis = new MatchStats.Kpis(2000000, new java.util.LinkedHashMap<String, Integer>(), 2000000, 0, 0, 0, 1999970);
		big.matchResults.put("mp1", bigResults);
		final String bigHtml = WizardPage.render(big);
		WizardPageMatchingStepTest.check("2 million rows: the warning is shown inline", bigHtml.substring(bigHtml.indexOf("id=\"mdmfMatchingLive\"")).contains("mdmf-hint mdmf-hint-warn"));
	}


	/** Step 6 plan card (2026-10-18): the labels the plan card reads its fields by are the staging attributes' own labels of THAT entity. */
	private static void thePlanCardReadsTheEntitysAttributeLabels()
	{
		final WizardPage.Model model = WizardPageMatchingStepTest.model(false);
		for (final WizardSnapshot.StagingEntityInfo entity : model.snapshot.stagingEntities)
		{
			if (entity.name.equals("Personne"))
			{
				final WizardSnapshot.AttributeInfo dated = new WizardSnapshot.AttributeInfo();
				dated.name = "purchaseDate";
				dated.label = " Date d'achat ";
				final WizardSnapshot.AttributeInfo bare = new WizardSnapshot.AttributeInfo();
				bare.name = "storeCode";
				entity.attributes.add(dated);
				entity.attributes.add(bare);
			}
		}
		final java.util.Map<String, String> labels = WizardPage.attributeLabels(model, "Personne");
		WizardPageMatchingStepTest.check("a labelled attribute reads as its (trimmed) label, an unlabelled one is left out", labels.size() == 1 && "Date d'achat".equals(labels.get("purchaseDate")));
		WizardPageMatchingStepTest.check("another entity has none", WizardPage.attributeLabels(model, "Adresse").isEmpty() && WizardPage.attributeLabels(model, "Nobody").isEmpty());
	}

	private static MatchingActivity activity(final String pKind, final String pStatus)
	{
		final MatchingActivity activity = new MatchingActivity();
		activity.kind = pKind;
		activity.status = pStatus;
		activity.startedAt = 1000L;
		activity.endedAt = MatchingActivity.RUNNING.equals(pStatus) || MatchingActivity.QUEUED.equals(pStatus) ? 0L : 4000L;
		return activity;
	}

	private static MatchingActivity.Entity entity(final String pName, final String pStatus, final String pMessage)
	{
		final MatchingActivity.Entity entity = new MatchingActivity.Entity();
		entity.name = pName;
		entity.status = pStatus;
		entity.startedAt = 1000L;
		entity.endedAt = 2000L;
		entity.message = pMessage;
		return entity;
	}

	/** Measured 2026-10-13 (UxCrmSf2): Run clicked 6 s after Configure, while it was still running, answered "1 entity run, 2 not applied". */
	private static void runWaitsForTheConfiguration()
	{
		final WizardPage.Model model = WizardPageMatchingStepTest.model(false);
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp1", "Personne", "LLM", "APPLIED"));
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp2", "Organisation", "LLM", "ACCEPTED"));
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp3", "Adresse", "LLM", "ACCEPTED"));
		final MatchingActivity configuring = WizardPageMatchingStepTest.activity(MatchingActivity.CONFIGURE, MatchingActivity.RUNNING);
		model.matchingActivity = configuring;
		String html = WizardPage.render(model);
		WizardPageMatchingStepTest.check("while Configure runs: both Run buttons are disabled",
			html.contains("data-mdmf-action=\"MATCHING_RUN\" data-mdmf-scope=\"all\" disabled") && html.contains("data-mdmf-action=\"MATCHING_RUN\" data-mdmf-scope=\"incremental\" disabled"));
		WizardPageMatchingStepTest.check("while Configure runs: the one-line reason counts the objects", html.contains(WizardPageMatchingStepTest.en("wizard.step6.run.configuring.other", Integer.valueOf(3)))
			&& html.contains("Configuring 3 objects..."));
		WizardPageMatchingStepTest.check("while Configure runs: the 'configure first' excuse is not printed next to it", !html.contains(WizardPageMatchingStepTest.en("wizard.step6.run.configureAgain")));
		configuring.status = MatchingActivity.QUEUED;
		WizardPageMatchingStepTest.check("queued behind another job counts too", WizardPage.render(model).contains("Configuring 3 objects..."));
		// the Configure ended: Run is back, the reason is gone (the usual excuse of an accepted-but-unconfigured plan takes over)
		model.matchingActivity = WizardPageMatchingStepTest.activity(MatchingActivity.CONFIGURE, MatchingActivity.DONE);
		html = WizardPage.render(model);
		WizardPageMatchingStepTest.check("after Configure: Run is enabled again", !html.contains("data-mdmf-action=\"MATCHING_RUN\" data-mdmf-scope=\"all\" disabled") && !html.contains("Configuring 3 objects"));
		model.snapshot.matchPlans.get(1).status = "APPLIED";
		model.snapshot.matchPlans.get(2).status = "APPLIED";
		html = WizardPage.render(model);
		WizardPageMatchingStepTest.check("every plan configured: no reason at all", !html.contains("mdmfStep6Configuring") && !html.contains(WizardPageMatchingStepTest.en("wizard.step6.run.configureAgain")));
		// a RUN in progress does not disable anything here (it is its own job)
		model.matchingActivity = WizardPageMatchingStepTest.activity(MatchingActivity.RUN, MatchingActivity.RUNNING);
		WizardPageMatchingStepTest.check("a running Run is not 'configuring'", !WizardPage.render(model).contains("mdmfStep6Configuring"));
		WizardPageMatchingStepTest.check("count is 0 without any activity", WizardPage.step6ConfiguringCount(WizardPageMatchingStepTest.model(false)) == 0);
	}

	private static void anObjectTheRunDidNotApplyGetsItsOwnRow()
	{
		final WizardPage.Model model = WizardPageMatchingStepTest.model(false);
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp1", "Personne", "LLM", "APPLIED"));
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp2", "Organisation", "LLM", "ACCEPTED"));
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp3", "Adresse", "LLM", "ACCEPTED"));
		final MatchingActivity run = WizardPageMatchingStepTest.activity(MatchingActivity.RUN, MatchingActivity.DONE);
		run.message = "1 entity run, 2 not applied, 0 refused.";
		run.entities.add(WizardPageMatchingStepTest.entity("Personne", MatchingActivity.DONE, "Scope: incremental - 30 of 81 records were pivots"));
		model.matchingActivity = run;
		final String html = WizardPage.render(model);
		final int panel = html.indexOf("id=\"mdmfMatchingLive\"");
		final String live = html.substring(panel, html.indexOf("</table>", panel));
		final String sentence = WizardPageMatchingStepTest.en("wizard.step6.result.notConfigured");
		WizardPageMatchingStepTest.check("three rows: the run one and the two not applied", WizardPageMatchingStepTest.count(live, "<tr data-mdmf-ma-entity=") == 3);
		WizardPageMatchingStepTest.check("Organisation and Adresse say 'Not configured yet: Configure first.'", WizardPageMatchingStepTest.count(live, sentence) == 2
			&& live.contains("data-mdmf-ma-entity=\"Organisation\"") && live.contains("data-mdmf-ma-entity=\"Adresse\""));
		// a plan that did run keeps its own row only
		run.entities.add(WizardPageMatchingStepTest.entity("Organisation", MatchingActivity.DONE, "ok"));
		final String again = WizardPage.render(model);
		final String live2 = again.substring(again.indexOf("id=\"mdmfMatchingLive\""), again.indexOf("</table>", again.indexOf("id=\"mdmfMatchingLive\"")));
		WizardPageMatchingStepTest.check("Organisation ran: only Adresse is listed as not configured", WizardPageMatchingStepTest.count(live2, sentence) == 1);
		// a Configure activity lists no such row (the rows of a Configure are its own entities)
		model.matchingActivity = WizardPageMatchingStepTest.activity(MatchingActivity.CONFIGURE, MatchingActivity.DONE);
		model.matchingActivity.entities.add(WizardPageMatchingStepTest.entity("Personne", MatchingActivity.DONE, "configured"));
		final String configured = WizardPage.render(model);
		WizardPageMatchingStepTest.check("after a Configure, no 'not configured' row", !configured.contains(sentence));
	}

	/** Measured 2026-10-13: the block under the results table printed "Last run: DONE, Matching run on Person finished (DONE); Xref.goldenKey refreshed ... WARNING: ...". */
	private static void theGoldenRecordsBlockFoldsTheRawRunText()
	{
		final String raw = "Matching run on Person finished (DONE); Xref.goldenKey refreshed (30 set, 0 cleared, 5 unchanged). Scope: incremental - 30 of 81 records were pivots "
			+ "(the last loaded batch); the other 51 were not matched in this run. WARNING: The candidate search of this plan is NOT filtered: 9 times slower at 1 million rows.";
		final MatchStats.Kpis kpis = new MatchStats.Kpis(81, new java.util.LinkedHashMap<String, Integer>(), 81, 0, 0, 0, 51);
		final MatchingResultRow row = MatchingResultRow.of(Locale.ENGLISH, "DONE", raw, kpis);
		final WizardPage.Model model = WizardPageMatchingStepTest.model(false);
		model.snapshot.matchPlans.add(WizardPageMatchingStepTest.plan("mp1", "Person", "LLM", "APPLIED"));
		final MatchResultsView.Input results = new MatchResultsView.Input();
		results.entityId = "Person";
		results.entityName = "Person";
		results.kpis = kpis;
		results.lastRunText = WizardMessages.get(Locale.ENGLISH, "wizard.step6.results.lastRun", row.sentence);
		results.lastRunDetails = row.details;
		model.matchResults.put("mp1", results);
		final String html = WizardPage.render(model);
		final String block = html.substring(html.indexOf("id=\"mdmf-step6-results\""));
		WizardPageMatchingStepTest.check("the block carries the one-sentence summary", block.contains("Last run: 30 of 81 records compared, no pair to decide, nothing merged"));
		WizardPageMatchingStepTest.check("no 'DONE,' status word and no raw run text outside the fold", !block.contains("Last run: DONE"));
		final int fold = block.indexOf("mdmf-ma-details");
		WizardPageMatchingStepTest.check("the raw text is under a Details fold", fold > 0 && block.indexOf("Xref.goldenKey") > fold && block.indexOf("9 times slower") > fold);
		WizardPageMatchingStepTest.check("the raw text appears once", WizardPageMatchingStepTest.count(block, "Xref.goldenKey") == 1);
		results.lastRunText = null;
		results.lastRunDetails = null;
		WizardPageMatchingStepTest.check("no sentence, no fold (the table above carries the run)", !WizardPage.render(model).substring(WizardPage.render(model).indexOf("id=\"mdmf-step6-results\"")).contains("Xref.goldenKey"));
	}

	private static int count(final String pHaystack, final String pNeedle)
	{
		int count = 0;
		int index = 0;
		while ((index = pHaystack.indexOf(pNeedle, index)) >= 0)
		{
			count++;
			index += pNeedle.length();
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
