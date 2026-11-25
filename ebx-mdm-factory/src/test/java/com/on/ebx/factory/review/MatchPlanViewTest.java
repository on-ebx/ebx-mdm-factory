package com.on.ebx.factory.review;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MatchPlanViewTest
{
	/** The real MATCH-1 plan (2026-09-22), quoted verbatim in the task brief. */
	private static final String REAL_PLAN_REQUIREMENTS = "{\"tiers\":[{\"outcome\":\"SUSPECTED\",\"type\":\"AVERAGE\",\"minimumScore\":80,"
		+ "\"fields\":[{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"weight\":10},"
		+ "{\"field\":\"country\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":5},"
		+ "{\"field\":\"cityKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":5}]}],"
		+ "\"clusteringFields\":[\"country\"],"
		+ "\"mergePolicy\":{\"mode\":\"ONLY_DUPLICATES\",\"recordSelection\":\"mostCompleted\",\"defaultFieldMerge\":\"longest\"}}";

	public static void main(final String[] pArgs)
	{
		MatchPlanViewTest.realPlanFixture_rendersAllThreeBlocks();
		MatchPlanViewTest.realPlanFixture_collapsedTitleSaysSuspectOnly();
		MatchPlanViewTest.exactTierAutoMergeTrue_realMatchLeaf();
		MatchPlanViewTest.exactTierAutoMergeFalse_dashedDisabledLeaf();
		MatchPlanViewTest.noEvidence_saysNotRecorded();
		MatchPlanViewTest.threeTierPlan_rendersEveryTier();
		MatchPlanViewTest.xssInFieldName_isEscaped();
		MatchPlanViewTest.cannotSimulate_showsReasonAndDisabledButton();
		MatchPlanViewTest.canSimulateWithResults_rendersFamilyOneRow();
		MatchPlanViewTest.activationNote_shownInHeaderWhenPresent_hiddenOtherwise();
		MatchPlanViewTest.evidence_nodeMentionWordsAndUnionOfMissingFields();
		MatchPlanViewTest.parseEvidence_validBySystemRoundTrips();
		MatchPlanViewTest.parseEvidence_noValidBySystemKey_staysNull();
		MatchPlanViewTest.autoMergeControl_hiddenWhenStatusNull();
		MatchPlanViewTest.autoMergeControl_editableCheckboxWhenProposedOrAccepted();
		MatchPlanViewTest.autoMergeControl_readOnlyChipWhenApplied();
		MatchPlanViewTest.autoMergeControl_readOnlyChipWithNoAppliedHintWhenRejected();
		MatchPlanViewTest.summaryStrip_notSimulatedYet_neverSaysNoRecordFound();
		MatchPlanViewTest.failedSimulation_showsOneLineUnderTheButton_inTheStripAndInTheBlock();
		MatchPlanViewTest.failedSimulation_longMessageIsTruncated_htmlEscaped();
		MatchPlanViewTest.relationTiersNotSimulated_saidInTheStripAndInTheBlock_hiddenWhenEmpty();
		System.out.println("MatchPlanViewTest OK");
	}

	/**
	 * Live bug fix (2026-09-25, PersVol2 volume test): {@code renderSummaryStrip} used to say "No record
	 * found to sample for this entity yet" ({@code review.match.simulate.none}) whenever {@code
	 * Input#simulation} is null - which is the state of EVERY fresh page render (CLAUDE.md sec 9,
	 * {@code ProjectWizardUserService} is rebuilt every request, so {@code matchSimulationResults} starts
	 * empty each time), not evidence that sampling found nothing. On PersVol2 the master table held 2996 real
	 * records, yet the strip read exactly like the lookup was broken. It must say "not simulated yet" instead,
	 * and never the "found nothing" wording, which stays reserved for {@code MatchSimulationBlock} (a
	 * genuinely empty {@code pairs} list AFTER a simulation actually ran).
	 */
	private static void summaryStrip_notSimulatedYet_neverSaysNoRecordFound()
	{
		final MatchPlanView.Input input = new MatchPlanView.Input("Personne", MatchPlanViewTest.REAL_PLAN_REQUIREMENTS, null, false, true, null, null, 10, "MATCH-1",
			Locale.ENGLISH);
		final String html = MatchPlanView.renderSummaryStrip(input);
		MatchPlanViewTest.check("says not simulated yet", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.match.simulate.notRunYet")));
		MatchPlanViewTest.check("never claims no record was found to sample",
			!html.contains(ReviewMessages.get(Locale.ENGLISH, "review.match.simulate.none")));
	}

	/**
	 * Lot V2-B (2026-09-30, Vol100k): a Simulate click that FAILED left the strip on "Not simulated yet" (the failure only reached the notice at
	 * the top of the page) - it must now say why, in one line, under the button.
	 */
	private static void failedSimulation_showsOneLineUnderTheButton_inTheStripAndInTheBlock()
	{
		final MatchPlanView.SimulationResult failed = MatchPlanView.SimulationResult.failed("Simulation failed: generate_matching_policy answered a soft miss");
		final MatchPlanView.Input input = new MatchPlanView.Input("Personne", MatchPlanViewTest.REAL_PLAN_REQUIREMENTS, null, false, true, null, failed, 10, "MATCH-1",
			Locale.ENGLISH);
		final String strip = MatchPlanView.renderSummaryStrip(input);
		MatchPlanViewTest.check("the strip carries the failure", strip.contains("Simulation failed: generate_matching_policy answered a soft miss"));
		MatchPlanViewTest.check("the strip no longer says not simulated yet", !strip.contains(ReviewMessages.get(Locale.ENGLISH, "review.match.simulate.notRunYet")));
		MatchPlanViewTest.check("the strip shows no statistics of a run that never happened", !strip.contains("mdmf-strip-chips"));
		final String block = MatchPlanView.render(input);
		MatchPlanViewTest.check("the block carries the failure too", block.contains("Simulation failed: generate_matching_policy answered a soft miss"));
		MatchPlanViewTest.check("as a warning line", block.contains("mdmf-hint mdmf-hint-warn"));
	}

	private static void failedSimulation_longMessageIsTruncated_htmlEscaped()
	{
		final StringBuilder longMessage = new StringBuilder("<b>x</b>");
		for (int index = 0; index < 100; index++)
		{
			longMessage.append("0123456789");
		}
		final MatchPlanView.Input input = new MatchPlanView.Input("Personne", MatchPlanViewTest.REAL_PLAN_REQUIREMENTS, null, false, true, null,
			MatchPlanView.SimulationResult.failed(longMessage.toString()), 10, "MATCH-1", Locale.ENGLISH);
		final String strip = MatchPlanView.renderSummaryStrip(input);
		MatchPlanViewTest.check("escaped", strip.contains("&lt;b&gt;x&lt;/b&gt;") && !strip.contains("<b>x</b>"));
		MatchPlanViewTest.check("truncated with an ellipsis", strip.contains("...") && !strip.contains(longMessage.substring(0, 700)));
	}

	/** Lot V2-B: "tier P3 compares Adresse fields: not simulated, its pairs are reported as decided by the remaining tiers" - said whenever tiers were left out. */
	private static void relationTiersNotSimulated_saidInTheStripAndInTheBlock_hiddenWhenEmpty()
	{
		final java.util.List<String> skipped = new java.util.ArrayList<String>();
		skipped.add("P3 (Adresse.street, Adresse.city)");
		final MatchPlanView.SimulationResult partial = new MatchPlanView.SimulationResult(new java.util.ArrayList<MatchPlanView.SimulationPair>(), 20, true, "Personne__preview",
			null, null, true, null, skipped, "the relation fields could not be linked in the add-on: boom", null);
		final MatchPlanView.Input input = new MatchPlanView.Input("Personne", MatchPlanViewTest.REAL_PLAN_REQUIREMENTS, null, false, true, null, partial, 10, "MATCH-1",
			Locale.ENGLISH);
		for (final String html : new String[] { MatchPlanView.renderSummaryStrip(input), MatchPlanView.render(input) })
		{
			MatchPlanViewTest.check("names the tier and its fields", html.contains("P3 (Adresse.street, Adresse.city)"));
			MatchPlanViewTest.check("says the pairs are decided by the remaining tiers", html.contains("decided by the remaining tiers"));
			MatchPlanViewTest.check("gives the reason", html.contains("boom"));
		}
		final MatchPlanView.SimulationResult full = new MatchPlanView.SimulationResult(new java.util.ArrayList<MatchPlanView.SimulationPair>(), 20, true, "Personne__preview");
		final MatchPlanView.Input fullInput = new MatchPlanView.Input("Personne", MatchPlanViewTest.REAL_PLAN_REQUIREMENTS, null, false, true, null, full, 10, "MATCH-1",
			Locale.ENGLISH);
		MatchPlanViewTest.check("nothing said when every tier was simulated", !MatchPlanView.renderSummaryStrip(fullInput).contains("decided by the remaining tiers"));
	}

	/** Lot 1 (2026-09-23, quality-informed survivorship): {@link MatchPlanView#parseEvidence} reads {@code validBySystem} back into {@code MatchPlanFieldEvidence.Validity} per system - the read-side mirror of {@code master.MatchPlanEvidence.toJson}. */
	private static void parseEvidence_validBySystemRoundTrips()
	{
		final String json = "[{\"field\":\"vatNo\",\"role\":\"SURVIVORSHIP\",\"sharedAcrossSystems\":null,\"distinct\":null,\"filledBySystem\":{},"
			+ "\"verdict\":\"OK\",\"note\":\"\",\"confidence\":\"high\",\"validBySystem\":{\"ERP\":{\"checked\":52,\"valid\":48},\"CRM\":{\"checked\":50,\"valid\":10}}}]";
		final java.util.List<MatchPlanFieldEvidence> parsed = MatchPlanView.parseEvidence(json);
		MatchPlanViewTest.check("one entry", parsed.size() == 1);
		final MatchPlanFieldEvidence field = parsed.get(0);
		MatchPlanViewTest.check("validBySystem present", field.validBySystem != null);
		MatchPlanViewTest.check("ERP checked/valid", field.validBySystem.get("ERP").checked == 52 && field.validBySystem.get("ERP").valid == 48);
		MatchPlanViewTest.check("CRM checked/valid", field.validBySystem.get("CRM").checked == 50 && field.validBySystem.get("CRM").valid == 10);
	}

	/** A plan written before lot 1 (or a field with no validity rule): no {@code validBySystem} key at all in the JSON - parses to null, never an empty map. */
	private static void parseEvidence_noValidBySystemKey_staysNull()
	{
		final String json = "[{\"field\":\"city\",\"role\":\"ANCHOR_FUZZY\",\"sharedAcrossSystems\":null,\"distinct\":null,\"filledBySystem\":{},"
			+ "\"verdict\":\"NOT_MEASURED\",\"note\":\"\"}]";
		final java.util.List<MatchPlanFieldEvidence> parsed = MatchPlanView.parseEvidence(json);
		MatchPlanViewTest.check("validBySystem stays null when absent from the JSON", parsed.get(0).validBySystem == null);
	}

	// ------------------------------------------------------------------------------------------------- fixture 1

	/** The real plan's own evidence, reconstructed from its rationale prose (task brief): country 9/13 shared, ERP 55/CRM 52 filled; cityKey 20/31 shared, ERP 55/CRM 51 filled; name (fuzzy anchor) not measured by the guard. */
	private static String realPlanEvidenceJson()
	{
		return "["
			+ "{\"field\":\"name\",\"role\":\"ANCHOR_FUZZY\",\"sharedAcrossSystems\":null,\"distinct\":null,\"filledBySystem\":{},\"verdict\":\"NOT_MEASURED\","
			+ "\"note\":\"Fuzzy comparison: the cross-system usability guard does not evaluate this algorithm.\"},"
			+ "{\"field\":\"country\",\"role\":\"SUPPORTING_EXACT,CLUSTERING\",\"sharedAcrossSystems\":9,\"distinct\":13,\"filledBySystem\":{\"ERP\":55,\"CRM\":52},"
			+ "\"verdict\":\"OK\",\"note\":\"\"},"
			+ "{\"field\":\"cityKey\",\"role\":\"SUPPORTING_EXACT\",\"sharedAcrossSystems\":20,\"distinct\":31,\"filledBySystem\":{\"ERP\":55,\"CRM\":51},\"verdict\":\"OK\","
			+ "\"note\":\"\"},"
			+ "{\"field\":\"vatNo\",\"role\":\"REJECTED\",\"sharedAcrossSystems\":0,\"distinct\":46,\"filledBySystem\":{\"ERP\":26,\"CRM\":0},\"verdict\":\"REFUSED\","
			+ "\"note\":\"entirely empty for source system CRM\"},"
			+ "{\"field\":\"legalForm\",\"role\":\"REJECTED\",\"sharedAcrossSystems\":0,\"distinct\":11,\"filledBySystem\":{\"ERP\":49,\"CRM\":0},\"verdict\":\"REFUSED\","
			+ "\"note\":\"entirely empty for source system CRM\"}]";
	}

	private static void realPlanFixture_rendersAllThreeBlocks()
	{
		final MatchPlanView.Input input = new MatchPlanView.Input("Company", MatchPlanViewTest.REAL_PLAN_REQUIREMENTS, MatchPlanViewTest.realPlanEvidenceJson(), false,
			false, null, null, 10, "MATCH-1", Locale.ENGLISH);
		final String html = MatchPlanView.render(input);

		// block 1: the tree
		MatchPlanViewTest.check("fuzzy anchor field name present", html.contains("name"));
		MatchPlanViewTest.check("fuzzy algorithm words", html.contains("resembles (fuzzy, full text)"));
		MatchPlanViewTest.check("exact algorithm words for country/cityKey", html.contains("identical"));
		MatchPlanViewTest.check("tier weight shown", html.contains(">10<") || html.contains("10"));
		MatchPlanViewTest.check("suspect leaf present", html.contains("A data steward decides"));
		MatchPlanViewTest.check("suspect leaf sub-text present", html.contains("nothing is merged without a human decision"));
		MatchPlanViewTest.check("autoMerge false: the dashed disabled annotation is drawn", html.contains("still goes to a data steward"));
		MatchPlanViewTest.check("final not-a-duplicate leaf present", html.contains("Both are kept"));
		MatchPlanViewTest.check("clustering field named", html.contains("country"));

		// block 2: evidence
		MatchPlanViewTest.check("evidence table has country row", html.contains("cityKey"));
		MatchPlanViewTest.check("rejected fields shown with reason", html.contains("vatNo") && html.contains("legalForm"));
		MatchPlanViewTest.check("filled-by-system numbers shown", html.contains("55") && html.contains("52"));

		// block 3: simulation
		MatchPlanViewTest.check("simulate button present", html.contains("Simulate on 10 pivots"));
		// simulation-preview-policy lot (2026-09-22): the default disabled-reason bundle text no longer says
		// "accept it first" (Simulate now runs on a preview policy for a not-yet-APPLIED plan) - the only
		// remaining static disabled case is "no matching plan at all for this entity".
		MatchPlanViewTest.check("simulate disabled reason shown (no plan)", html.contains("No matching plan to simulate for this entity"));
		MatchPlanViewTest.check("simulate button carries the row id", html.contains("data-mdmf-id=\"MATCH-1\""));
	}

	private static void realPlanFixture_collapsedTitleSaysSuspectOnly()
	{
		final String title = MatchPlanView.collapsedTitle(MatchPlanViewTest.REAL_PLAN_REQUIREMENTS, false, Locale.ENGLISH);
		MatchPlanViewTest.check("one tier, suspect only: " + title, title.contains("1") && title.contains("suspect only"));
	}

	// ------------------------------------------------------------------------------------------------- fixture 2/3

	private static String exactMatchTierRequirements()
	{
		return "{\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\","
			+ "\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],"
			+ "\"clusteringFields\":[\"vatNo\"],\"mergePolicy\":{\"mode\":\"ONLY_DUPLICATES\",\"recordSelection\":\"mostCompleted\",\"defaultFieldMerge\":\"longest\"}}";
	}

	/** autoMerge true + a MATCH-terminal exact tier: the true leaf is the REAL "merged automatically" leaf, and the dashed placeholder never appears. */
	private static void exactTierAutoMergeTrue_realMatchLeaf()
	{
		final MatchPlanView.Input input = new MatchPlanView.Input("Company", MatchPlanViewTest.exactMatchTierRequirements(), null, true, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-2", Locale.ENGLISH);
		final String html = MatchPlanView.render(input);
		MatchPlanViewTest.check("real merged-automatically leaf shown", html.contains("Merged automatically"));
		MatchPlanViewTest.check("no dashed disabled annotation when autoMerge is true", !html.contains("still goes to a data steward"));
		MatchPlanViewTest.check("title says may merge automatically",
			MatchPlanView.collapsedTitle(MatchPlanViewTest.exactMatchTierRequirements(), true, Locale.ENGLISH).contains("may merge automatically"));
	}

	/** Same tree, autoMerge false: the translator forces this tier's outcome to SUSPECTED - the true leaf is the ordinary suspect leaf, plus the dashed "disabled" annotation. */
	private static void exactTierAutoMergeFalse_dashedDisabledLeaf()
	{
		final MatchPlanView.Input input = new MatchPlanView.Input("Company", MatchPlanViewTest.exactMatchTierRequirements(), null, false, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-3", Locale.ENGLISH);
		final String html = MatchPlanView.render(input);
		MatchPlanViewTest.check("the tree drawn must be the tree actually executed: no real merged leaf when autoMerge is false", !html.contains("Merged automatically"));
		MatchPlanViewTest.check("suspect leaf shown instead (the translator's own forced outcome)", html.contains("A data steward decides"));
		MatchPlanViewTest.check("dashed disabled annotation shown", html.contains("AUTOMATIC MERGE") && html.contains("still goes to a data steward"));
		MatchPlanViewTest.check("title says suspect only",
			MatchPlanView.collapsedTitle(MatchPlanViewTest.exactMatchTierRequirements(), false, Locale.ENGLISH).contains("suspect only"));
	}

	// ------------------------------------------------------------------------------------------------- fixture 4

	private static void noEvidence_saysNotRecorded()
	{
		final MatchPlanView.Input input = new MatchPlanView.Input("Company", MatchPlanViewTest.exactMatchTierRequirements(), null, false, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-4", Locale.ENGLISH);
		final String html = MatchPlanView.render(input);
		MatchPlanViewTest.check("says evidence was never recorded", html.contains("Evidence not recorded for this plan"));

		final MatchPlanView.Input blank = new MatchPlanView.Input("Company", MatchPlanViewTest.exactMatchTierRequirements(), "  ", false, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-4b", Locale.ENGLISH);
		MatchPlanViewTest.check("a blank (whitespace-only) evidence string degrades the same way",
			MatchPlanView.render(blank).contains("Evidence not recorded for this plan"));

		final MatchPlanView.Input malformed = new MatchPlanView.Input("Company", MatchPlanViewTest.exactMatchTierRequirements(), "not json at all", false, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-4c", Locale.ENGLISH);
		MatchPlanViewTest.check("malformed evidence JSON never throws, degrades the same way",
			MatchPlanView.render(malformed).contains("Evidence not recorded for this plan"));
	}

	// ------------------------------------------------------------------------------------------------- fixture 5

	private static void threeTierPlan_rendersEveryTier()
	{
		final String requirements = "{\"tiers\":["
			+ "{\"outcome\":\"MATCH\",\"type\":\"EXACT\",\"fields\":[{\"field\":\"gtin\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]},"
			+ "{\"outcome\":\"MATCH\",\"type\":\"EXACT\",\"fields\":[{\"field\":\"isbn\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]},"
			+ "{\"outcome\":\"SUSPECTED\",\"type\":\"AVERAGE\",\"minimumScore\":75,"
			+ "\"fields\":[{\"field\":\"title\",\"algorithm\":\"ebx-addon-mame@levenshtein\",\"weight\":6},"
			+ "{\"field\":\"brand\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":4}]}"
			+ "],\"clusteringFields\":[\"gtin\",\"isbn\"],"
			+ "\"mergePolicy\":{\"mode\":\"ONLY_DUPLICATES\",\"recordSelection\":\"lastUpdate\",\"defaultFieldMerge\":\"longest\"}}";
		final MatchPlanView.Input input = new MatchPlanView.Input("Product", requirements, null, true, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-5", Locale.ENGLISH);
		final String html = MatchPlanView.render(input);
		// binary-decision-tree-screen lot (2026-09-22): node cards are now headed "T1 - ALL FIELDS MATCH" etc.
		// (ReviewDecisionTree.fromTiers' own T1..Tn naming), not "TIER 1" - see MatchPlanTreeDiagram#headerText.
		MatchPlanViewTest.check("node T1 shown", html.contains("T1"));
		MatchPlanViewTest.check("node T2 shown", html.contains("T2"));
		MatchPlanViewTest.check("node T3 shown", html.contains("T3"));
		MatchPlanViewTest.check("gtin field shown", html.contains("gtin"));
		MatchPlanViewTest.check("isbn field shown", html.contains("isbn"));
		MatchPlanViewTest.check("levenshtein algorithm words", html.contains("close spelling"));
		MatchPlanViewTest.check("jaroWinkler algorithm words", html.contains("Jaro-Winkler"));
		MatchPlanViewTest.check("three tiers in the collapsed title",
			MatchPlanView.collapsedTitle(requirements, true, Locale.ENGLISH).contains("3"));
	}

	// ------------------------------------------------------------------------------------------------- fixture 6 (xss)

	private static void xssInFieldName_isEscaped()
	{
		final String requirements = "{\"tiers\":[{\"outcome\":\"SUSPECTED\",\"type\":\"AVERAGE\",\"minimumScore\":80,"
			+ "\"fields\":[{\"field\":\"\\\"><script>alert(1)</script>\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":10}]}],"
			+ "\"clusteringFields\":[],\"mergePolicy\":{}}";
		final String evidence = "[{\"field\":\"\\\"><script>alert(1)</script>\",\"role\":\"SUPPORTING_EXACT\",\"sharedAcrossSystems\":3,\"distinct\":9,"
			+ "\"filledBySystem\":{\"ERP\":5},\"verdict\":\"OK\",\"note\":\"\\\"><script>alert(2)</script>\"}]";
		final MatchPlanView.Input input = new MatchPlanView.Input("Company", requirements, evidence, false, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-6", Locale.ENGLISH);
		final String html = MatchPlanView.render(input);
		MatchPlanViewTest.check("no raw <script> tag reaches the output", !html.contains("<script>"));
		MatchPlanViewTest.check("the field name text is still present, escaped", html.contains("&lt;script&gt;"));
	}

	// ------------------------------------------------------------------------------------------------- simulation

	private static void cannotSimulate_showsReasonAndDisabledButton()
	{
		final MatchPlanView.Input input = new MatchPlanView.Input("Company", MatchPlanViewTest.REAL_PLAN_REQUIREMENTS, null, false, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-1", Locale.ENGLISH);
		final String html = MatchPlanView.render(input);
		MatchPlanViewTest.check("button carries disabled attribute", html.contains("disabled=\"disabled\""));
		MatchPlanViewTest.check("reason shown", html.contains("accept it first, then Simulate"));
	}

	/**
	 * Review-match-plan-simulation lot (2026-09-22): a pair caught without a matching exact key (cityKey
	 * differs) but still SUSPECTED - the "caught by fuzzy" family (block 3, family 1). Deep coverage of the
	 * pure analysis/family selection lives in {@code MatchSimulationBlockTest}; this test only checks the
	 * integration - {@link MatchPlanView#render} actually reaches {@link MatchSimulationBlock} and the result
	 * lands in the page.
	 */
	private static void canSimulateWithResults_rendersFamilyOneRow()
	{
		final Map<String, String> pivotValues = new LinkedHashMap<String, String>();
		pivotValues.put("name", "BPCE");
		pivotValues.put("country", "FR");
		pivotValues.put("cityKey", "PARIS");
		final Map<String, String> targetValues = new LinkedHashMap<String, String>();
		targetValues.put("name", "BPCE");
		targetValues.put("country", "FR");
		targetValues.put("cityKey", "LYON");
		final MatchPlanView.SimulationPair pair = new MatchPlanView.SimulationPair("BPCE . Paris . 290", "ERP", "BPCE . Lyon . 248", "CRM", pivotValues, targetValues,
			new LinkedHashMap<String, Double>(), "SUSPECTED");
		final MatchPlanView.SimulationResult result = new MatchPlanView.SimulationResult(java.util.Collections.singletonList(pair), 5);
		final MatchPlanView.Input input = new MatchPlanView.Input("Company", MatchPlanViewTest.REAL_PLAN_REQUIREMENTS, null, false, true, null, result, 10, "MATCH-1",
			Locale.ENGLISH);
		final String html = MatchPlanView.render(input);
		MatchPlanViewTest.check("pivot label shown", html.contains("BPCE . Paris . 290"));
		MatchPlanViewTest.check("target label shown", html.contains("BPCE . Lyon . 248"));
		MatchPlanViewTest.check("equal exact field renders with =", html.contains("= FR"));
		MatchPlanViewTest.check("differing exact field renders with the not-equal sign", html.contains("PARIS ≠ LYON"));
		// diagnostic-visibility lot (2026-09-22, task brief item 1): "score not exposed by the engine" is no
		// longer shown bare on every such row - "name" is a fuzzyFullText field (REAL_PLAN_REQUIREMENTS), so the
		// cell now shows a short "n/a" with the full-text HYPOTHESIS wording in its title attribute instead.
		MatchPlanViewTest.check("short n/a shown for the fuzzy field with no score", html.contains(">n/a<"));
		MatchPlanViewTest.check("full-text reason given in the cell's title, not the generic \"not exposed\" one",
			html.contains("full-text algorithm") && !html.contains("score not exposed by the engine"));
		MatchPlanViewTest.check("suspect verdict chip shown", html.contains("Suspect"));
		MatchPlanViewTest.check("summary lead line shown", html.contains("5 pivot"));
		// review-match-plan-simulation-probes lot (2026-09-22): family 1 (caught by fuzzy) gets the real row -
		// no empty line for it; families 2 and 3 stay empty for this single-pair fixture and now each say why,
		// with real numbers (0 probed pairs of either kind, 1 pair examined overall - never the bare pre-lot
		// "No pair of this kind in this sample.", which no longer appears anywhere).
		MatchPlanViewTest.check("the old bare empty message is gone", !html.contains("No pair of this kind in this sample."));
		MatchPlanViewTest.check("family 2 says why, with numbers",
			html.contains("No refusal among the 0 shared-key pair(s) probed (1 pair(s) examined overall)"));
		MatchPlanViewTest.check("family 3 says why, with numbers", html.contains("No pair scored within 3 points of the threshold, across 1 pair(s) examined"));
		MatchPlanViewTest.check("family 1 origin column tags the row as an engine candidate", html.contains("Engine candidate"));
	}

	/**
	 * self-heal-simulate-activation lot (2026-09-22): {@code SimulationResult#activationNote} non-null (the
	 * table's matching activation was repaired before this run - {@code ebx.MatchingActivationGuard}) is shown
	 * in the panel header, warn-styled; null (the ordinary case) shows nothing extra.
	 */
	private static void activationNote_shownInHeaderWhenPresent_hiddenOtherwise()
	{
		final MatchPlanView.SimulationResult repaired = new MatchPlanView.SimulationResult(new java.util.ArrayList<MatchPlanView.SimulationPair>(), 0, false, "Company",
			"matching trigger was not attached after the last boot - re-activated");
		final MatchPlanView.Input repairedInput = new MatchPlanView.Input("Company", MatchPlanViewTest.REAL_PLAN_REQUIREMENTS, null, false, true, null, repaired, 10,
			"MATCH-1", Locale.ENGLISH);
		final String repairedHtml = MatchPlanView.render(repairedInput);
		MatchPlanViewTest.check("repaired note text shown", repairedHtml.contains("the factory repaired it automatically"));
		MatchPlanViewTest.check("repaired note carries the warn hint class", repairedHtml.contains("mdmf-hint-warn"));

		final MatchPlanView.SimulationResult ordinary = new MatchPlanView.SimulationResult(new java.util.ArrayList<MatchPlanView.SimulationPair>(), 0, false, "Company");
		final MatchPlanView.Input ordinaryInput = new MatchPlanView.Input("Company", MatchPlanViewTest.REAL_PLAN_REQUIREMENTS, null, false, true, null, ordinary, 10,
			"MATCH-1", Locale.ENGLISH);
		final String ordinaryHtml = MatchPlanView.render(ordinaryInput);
		MatchPlanViewTest.check("no repaired note when activationNote is null", !ordinaryHtml.contains("mdmf-hint-warn"));
	}

	// ------------------------------------------------------------------------------------------------ evidence union

	/**
	 * Binary-decision-tree-screen lot (2026-09-22), task brief item 2: "the fields come from ALL nodes (union),
	 * with the role wording per node; a field used in several nodes lists them all". Two-node tree: N1
	 * (ALL_FIELDS on {@code country}) falling through to N4 (AVERAGE on {@code name}/{@code country}) - {@code
	 * country} is used by BOTH nodes and must list both; {@code name} is used only by N4 but is entirely
	 * MISSING from the stored evidence (a plan re-saved before evidence tracked node membership) and must still
	 * appear, unioned in with a {@code NOT_MEASURED} stub row.
	 */
	private static void evidence_nodeMentionWordsAndUnionOfMissingFields()
	{
		final String requirements = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"country\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"MATCH\",\"onFalse\":\"N4\"},"
			+ "{\"id\":\"N4\",\"function\":\"AVERAGE\",\"minimumScore\":80,\"fields\":["
			+ "{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"weight\":10},"
			+ "{\"field\":\"country\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":5}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}"
			+ "]},\"clusteringFields\":[]}";
		final String evidence = "[{\"field\":\"country\",\"role\":\"SUPPORTING_EXACT\",\"sharedAcrossSystems\":9,\"distinct\":13,\"filledBySystem\":{},"
			+ "\"verdict\":\"OK\",\"note\":\"\"}]";
		final MatchPlanView.Input input = new MatchPlanView.Input("Company", requirements, evidence, false, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-7", Locale.ENGLISH);
		final String html = MatchPlanView.render(input);
		MatchPlanViewTest.check("country lists BOTH nodes that use it: " + html,
			html.contains("N1 all fields match") && html.contains("N4 average, weight 5"));
		MatchPlanViewTest.check("name (missing from evidence) is unioned in with N4's own mention",
			html.contains("N4 average, weight 10"));
		MatchPlanViewTest.check("the unioned name row still shows as not measured", html.contains(ReviewPage.esc(ReviewMessages.get(Locale.ENGLISH,
			"review.match.verdict.notMeasured"))));
	}

	// --------------------------------------------------------------------------------- automerge-toggle control

	/** {@link MatchPlanView.Input#matchPlanStatus} null (every caller except {@code review.ReviewPage} - e.g. the wizard): no control at all. */
	private static void autoMergeControl_hiddenWhenStatusNull()
	{
		final MatchPlanView.Input input = new MatchPlanView.Input("Company", MatchPlanViewTest.exactMatchTierRequirements(), null, false, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-8", Locale.ENGLISH);
		final String html = MatchPlanView.render(input);
		MatchPlanViewTest.check("no auto-merge block when status is null", !html.contains("mdmf-auto-merge-toggle") && !html.contains("mdmf-auto-merge\""));
	}

	/** PROPOSED/ACCEPTED: a real, editable checkbox, checked exactly when {@code autoMerge} is true. */
	private static void autoMergeControl_editableCheckboxWhenProposedOrAccepted()
	{
		final MatchPlanView.Input proposedOff = new MatchPlanView.Input("Company", MatchPlanViewTest.exactMatchTierRequirements(), null, false, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-9", Locale.ENGLISH, null, ReviewLine.PROPOSED);
		final String htmlOff = MatchPlanView.render(proposedOff);
		MatchPlanViewTest.check("checkbox present (PROPOSED)", htmlOff.contains("mdmf-auto-merge-toggle"));
		MatchPlanViewTest.check("checkbox carries the row id", htmlOff.contains("data-mdmf-id=\"MATCH-9\""));
		MatchPlanViewTest.check("unchecked when autoMerge is false", !htmlOff.contains("checked=\"checked\""));
		MatchPlanViewTest.check("no read-only chip wording while editable", !htmlOff.contains(ReviewMessages.get(Locale.ENGLISH, "review.match.autoMerge.off")));

		final MatchPlanView.Input acceptedOn = new MatchPlanView.Input("Company", MatchPlanViewTest.exactMatchTierRequirements(), null, true, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-10", Locale.ENGLISH, null, ReviewLine.ACCEPTED);
		final String htmlOn = MatchPlanView.render(acceptedOn);
		MatchPlanViewTest.check("checkbox present (ACCEPTED)", htmlOn.contains("mdmf-auto-merge-toggle"));
		MatchPlanViewTest.check("checked when autoMerge is true", htmlOn.contains("checked=\"checked\""));
	}

	/** APPLIED: no checkbox - a read-only chip, plus the "needs a re-configure" hint the task brief asked for. */
	private static void autoMergeControl_readOnlyChipWhenApplied()
	{
		final MatchPlanView.Input input = new MatchPlanView.Input("Company", MatchPlanViewTest.exactMatchTierRequirements(), null, true, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-11", Locale.ENGLISH, null, ReviewLine.APPLIED);
		final String html = MatchPlanView.render(input);
		MatchPlanViewTest.check("no editable checkbox once APPLIED", !html.contains("mdmf-auto-merge-toggle"));
		MatchPlanViewTest.check("read-only chip shown (on)", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.match.autoMerge.on")));
		MatchPlanViewTest.check("re-configure hint shown", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.match.autoMerge.appliedHint")));
	}

	/** REJECTED (or any other non-editable, non-APPLIED status): read-only chip, but never the APPLIED-only re-configure hint. */
	private static void autoMergeControl_readOnlyChipWithNoAppliedHintWhenRejected()
	{
		final MatchPlanView.Input input = new MatchPlanView.Input("Company", MatchPlanViewTest.exactMatchTierRequirements(), null, false, false,
			"Simulate needs the plan configured on the add-on - accept it first, then Simulate.", null, 10, "MATCH-12", Locale.ENGLISH, null, ReviewLine.REJECTED);
		final String html = MatchPlanView.render(input);
		MatchPlanViewTest.check("no editable checkbox", !html.contains("mdmf-auto-merge-toggle"));
		MatchPlanViewTest.check("read-only chip shown (off)", html.contains(ReviewMessages.get(Locale.ENGLISH, "review.match.autoMerge.off")));
		MatchPlanViewTest.check("no re-configure hint outside APPLIED", !html.contains(ReviewMessages.get(Locale.ENGLISH, "review.match.autoMerge.appliedHint")));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
