package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws.
 * <p>
 * Covers {@link MatchSurvivorshipBlock}'s pure rendering - source cards, the fixed survivor/default-field
 * sentences, business rows built from {@code MatchPlan.requirements.survivorship.fields[]} (including "most
 * trusted source" resolving to a real source code, with and without a per-field override), the four fixed
 * technical rows plus one per derived matching key, the "to verify" chip on a low-confidence rule, the
 * empty state (no field-specific rule proposed), and HTML escaping - against hand-built fixtures, no EBX
 * involved (same discipline as {@code MatchSimulationBlockTest}).
 */
public final class MatchSurvivorshipBlockTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchSurvivorshipBlockTest.emptyState_technicalRowsOnly_noneMessageShown();
		MatchSurvivorshipBlockTest.emptyState_noEvidenceAtAll_notAvailableMessageShown();
		MatchSurvivorshipBlockTest.businessRow_mostTrustedSourceResolvesToTopRankedSource();
		MatchSurvivorshipBlockTest.businessRow_trustedSourceListOverrideWinsOverTableRanking();
		MatchSurvivorshipBlockTest.businessRow_multiStepChainShowsThenForFallback();
		MatchSurvivorshipBlockTest.businessRow_lowConfidenceShowsToVerifyChip();
		MatchSurvivorshipBlockTest.businessRow_mediumConfidenceNeverShowsToVerifyChip();
		MatchSurvivorshipBlockTest.businessRow_fieldNotOnMaster_showsChip();
		MatchSurvivorshipBlockTest.businessRow_fieldOnMaster_noChip();
		MatchSurvivorshipBlockTest.technicalRows_sourceSystemSourceKeysReceivedAtAlwaysPresent();
		MatchSurvivorshipBlockTest.technicalRows_oneRowPerDerivedKeyField();
		MatchSurvivorshipBlockTest.sourceCards_renderedWithRankDescriptionAndRecordCount();
		MatchSurvivorshipBlockTest.noSources_stillRendersWithoutCrashing();
		MatchSurvivorshipBlockTest.fillBars_renderedFromEvidenceAgainstSourceRecordCounts();
		MatchSurvivorshipBlockTest.validBars_renderedFromEvidenceNextToFillBars();
		MatchSurvivorshipBlockTest.validBars_absentWhenEvidenceHasNoValidBySystem();
		MatchSurvivorshipBlockTest.htmlEscaping_rationaleAndFieldNameEscaped();
		MatchSurvivorshipBlockTest.constantFunction_showsItsValue();
		System.out.println("MatchSurvivorshipBlockTest OK");
	}

	// ---------------------------------------------------------------------------------------------------- fixtures

	private static final String NO_SURVIVORSHIP_REQUIREMENTS = "{\"tiers\":[],\"clusteringFields\":[]}";

	private static String requirementsWithOneRule(final String pField, final String pChainJson, final String pRationale, final String pConfidence)
	{
		return "{\"tiers\":[],\"clusteringFields\":[],\"survivorship\":{\"fields\":[{\"field\":\"" + pField + "\",\"chain\":" + pChainJson + ",\"rationale\":\""
			+ pRationale + "\",\"confidence\":\"" + pConfidence + "\"}]}}";
	}

	private static List<MatchSurvivorshipBlock.SourceCard> twoSources()
	{
		final List<MatchSurvivorshipBlock.SourceCard> out = new ArrayList<MatchSurvivorshipBlock.SourceCard>();
		out.add(new MatchSurvivorshipBlock.SourceCard("ERP", 1, "SAP, referentiel fournisseurs", Long.valueOf(55)));
		out.add(new MatchSurvivorshipBlock.SourceCard("CRM", 2, "Salesforce", Long.valueOf(52)));
		return out;
	}

	private static MatchSurvivorshipBlock.Input input(final List<MatchSurvivorshipBlock.SourceCard> pSources, final List<String> pSourceKeyFieldNames,
		final List<MatchSurvivorshipBlock.DerivedKeyField> pDerivedKeyFields)
	{
		return new MatchSurvivorshipBlock.Input(pSources, "mostCompleted", pSourceKeyFieldNames, pDerivedKeyFields);
	}

	private static JsonNode parse(final String pJson)
	{
		try
		{
			return MatchSurvivorshipBlockTest.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static String render(final String pRequirementsJson, final List<MatchPlanFieldEvidence> pEvidence, final MatchSurvivorshipBlock.Input pInput)
	{
		final StringBuilder out = new StringBuilder();
		MatchSurvivorshipBlock.render(out, MatchSurvivorshipBlockTest.parse(pRequirementsJson), pEvidence, pInput, Locale.ENGLISH);
		return out.toString();
	}

	// ------------------------------------------------------------------------------------------------- empty state

	/**
	 * Coordinator ask, 2026-09-22: a plan the CURRENT pass ran and simply proposed nothing field-specific for
	 * still has SOME evidence (even a single matching-side entry, e.g. an ANCHOR_FUZZY field with no
	 * survivorship data) - this reads as "considered, nothing proposed", never "not available".
	 */
	private static void emptyState_technicalRowsOnly_noneMessageShown()
	{
		final List<MatchPlanFieldEvidence> evidence = new ArrayList<MatchPlanFieldEvidence>();
		evidence.add(new MatchPlanFieldEvidence("name", MatchPlanFieldEvidence.ROLE_ANCHOR_FUZZY, null, null, null, MatchPlanFieldEvidence.VERDICT_NOT_MEASURED, ""));
		final String html = MatchSurvivorshipBlockTest.render(MatchSurvivorshipBlockTest.NO_SURVIVORSHIP_REQUIREMENTS, evidence, MatchSurvivorshipBlock.emptyInput());
		MatchSurvivorshipBlockTest.check("no field-specific rule proposed message shown", html.contains("No field-specific survivorship rule proposed"));
		MatchSurvivorshipBlockTest.check("sourceSystem technical row always present", html.contains(">sourceSystem<"));
		// lot M1 (2026-10-17): a record keeps its own source; only a created golden carries MDM - never shown as a constant
		MatchSurvivorshipBlockTest.check("sourceSystem is NOT a constant any more", !html.contains("constant &quot;MDM&quot;") && !html.contains("constant \"MDM\""));
		MatchSurvivorshipBlockTest.check("sourceSystem row says each record keeps its source and the created golden carries MDM",
			html.contains("each record keeps its own source") && html.contains("carries the source MDM"));
		MatchSurvivorshipBlockTest.check("the review's golden source mirrors the translator's",
			MatchSurvivorshipBlock.GOLDEN_SOURCE_CODE.equals(com.on.ebx.factory.master.MatchingPolicyTranslator.GOLDEN_SOURCE_CODE));
		MatchSurvivorshipBlockTest.check("_receivedAt technical row always present", html.contains(">_receivedAt<"));
	}

	/** Coordinator ask, 2026-09-22: a plan with genuinely NO evidence at all (predates even the review-match-plan lot) says so explicitly, distinct from "considered, nothing proposed". */
	private static void emptyState_noEvidenceAtAll_notAvailableMessageShown()
	{
		final String html = MatchSurvivorshipBlockTest.render(MatchSurvivorshipBlockTest.NO_SURVIVORSHIP_REQUIREMENTS, new ArrayList<MatchPlanFieldEvidence>(),
			MatchSurvivorshipBlock.emptyInput());
		MatchSurvivorshipBlockTest.check("measures-not-available message shown instead of the generic empty-state one",
			html.contains("Measures not available for this plan") && !html.contains("No field-specific survivorship rule proposed"));
		MatchSurvivorshipBlockTest.check("technical rows are still shown even when evidence is entirely absent", html.contains(">sourceSystem<"));
		MatchSurvivorshipBlockTest.check("decide note always shown", html.contains("Accept writes everything"));
	}

	// ---------------------------------------------------------------------------------------------- business rows

	private static void businessRow_mostTrustedSourceResolvesToTopRankedSource()
	{
		final String requirements = MatchSurvivorshipBlockTest.requirementsWithOneRule("vatNo", "[{\"function\":\"most trusted source\"}]", "ERP is the legal registry",
			"high");
		final String html = MatchSurvivorshipBlockTest.render(requirements, new ArrayList<MatchPlanFieldEvidence>(),
			MatchSurvivorshipBlockTest.input(MatchSurvivorshipBlockTest.twoSources(), null, null));
		MatchSurvivorshipBlockTest.check("field name shown", html.contains(">vatNo<"));
		MatchSurvivorshipBlockTest.check("resolves to ERP - the table's own top-ranked source", html.contains("<span class=\"rule\">ERP</span>"));
		MatchSurvivorshipBlockTest.check("rationale shown", html.contains("ERP is the legal registry"));
		MatchSurvivorshipBlockTest.check("proposed by Language model chip", html.contains("Language model"));
	}

	private static void businessRow_trustedSourceListOverrideWinsOverTableRanking()
	{
		// table ranking is [ERP, CRM] (ERP first) but this field's own override is [CRM, ERP] - CRM must win here
		final String requirements = MatchSurvivorshipBlockTest.requirementsWithOneRule("city",
			"[{\"function\":\"most trusted source\",\"trustedSourceList\":[\"CRM\",\"ERP\"]}]", "CRM is kept current by sales reps", "medium");
		final String html = MatchSurvivorshipBlockTest.render(requirements, new ArrayList<MatchPlanFieldEvidence>(),
			MatchSurvivorshipBlockTest.input(MatchSurvivorshipBlockTest.twoSources(), null, null));
		MatchSurvivorshipBlockTest.check("the field-level override (CRM) wins over the table's own ranking (ERP)",
			html.contains("<span class=\"rule\">CRM</span>"));
	}

	private static void businessRow_multiStepChainShowsThenForFallback()
	{
		final String requirements = MatchSurvivorshipBlockTest.requirementsWithOneRule("legalForm",
			"[{\"function\":\"most trusted source\"},{\"function\":\"longest\"}]", "ERP first, then the least abbreviated", "high");
		final String html = MatchSurvivorshipBlockTest.render(requirements, new ArrayList<MatchPlanFieldEvidence>(),
			MatchSurvivorshipBlockTest.input(MatchSurvivorshipBlockTest.twoSources(), null, null));
		MatchSurvivorshipBlockTest.check("primary step bold (rule span)", html.contains("<span class=\"rule\">ERP</span>"));
		MatchSurvivorshipBlockTest.check("fallback step shown with 'then'", html.contains("<span class=\"then\">then longest</span>"));
	}

	private static void businessRow_lowConfidenceShowsToVerifyChip()
	{
		final String requirements = MatchSurvivorshipBlockTest.requirementsWithOneRule("city", "[{\"function\":\"last update\",\"dateFieldPath\":\"/_receivedAt\"}]",
			"CRM is more current", "low");
		final String html = MatchSurvivorshipBlockTest.render(requirements, new ArrayList<MatchPlanFieldEvidence>(),
			MatchSurvivorshipBlockTest.input(MatchSurvivorshipBlockTest.twoSources(), null, null));
		MatchSurvivorshipBlockTest.check("to verify chip shown for a low-confidence rule", html.contains("mdmf-chip-warn\">to verify</span>"));
	}

	/** The fixed decide-note paragraph mentions "to verify" in prose regardless of confidence - only the CHIP (mdmf-chip-warn) must be absent here. */
	private static void businessRow_mediumConfidenceNeverShowsToVerifyChip()
	{
		final String requirements = MatchSurvivorshipBlockTest.requirementsWithOneRule("city", "[{\"function\":\"most frequent\"}]", "identical on shared records",
			"medium");
		final String html = MatchSurvivorshipBlockTest.render(requirements, new ArrayList<MatchPlanFieldEvidence>(),
			MatchSurvivorshipBlockTest.input(MatchSurvivorshipBlockTest.twoSources(), null, null));
		MatchSurvivorshipBlockTest.check("no to-verify CHIP for a medium-confidence rule (the decide note's own prose mention of \"to verify\" is expected and ignored)",
			!html.contains("mdmf-chip-warn\">to verify</span>"));
	}

	/** 2026-09-23 (`docs/MATCHING.md` &sect;26): a field {@code ebx.ProposalReviews#buildSurvivorshipInput} found is not yet on the master table gets a warning chip on its row. */
	private static void businessRow_fieldNotOnMaster_showsChip() throws Exception
	{
		final String requirements = MatchSurvivorshipBlockTest
			.requirementsWithOneRule("emailValid", "[{\"function\":\"longest\"}]", "cleaner value wins", "high");
		final MatchSurvivorshipBlock.Input input = new MatchSurvivorshipBlock.Input(MatchSurvivorshipBlockTest.twoSources(), "mostCompleted", null, null,
			Collections.singleton("emailValid"));
		final String html = MatchSurvivorshipBlockTest.render(requirements, new ArrayList<MatchPlanFieldEvidence>(), input);
		MatchSurvivorshipBlockTest.check("not-on-master chip shown for the flagged field", html.contains("mdmf-chip-warn\">not on the master table yet</span>"));
	}

	/** The same field, NOT flagged this time (the ordinary/every-day case, every other test in this file): no chip at all. */
	private static void businessRow_fieldOnMaster_noChip() throws Exception
	{
		final String requirements = MatchSurvivorshipBlockTest
			.requirementsWithOneRule("emailValid", "[{\"function\":\"longest\"}]", "cleaner value wins", "high");
		final String html = MatchSurvivorshipBlockTest.render(requirements, new ArrayList<MatchPlanFieldEvidence>(),
			MatchSurvivorshipBlockTest.input(MatchSurvivorshipBlockTest.twoSources(), null, null));
		MatchSurvivorshipBlockTest.check("no not-on-master chip when nothing was flagged", !html.contains("not on the master table yet"));
	}

	private static void constantFunction_showsItsValue()
	{
		// a technical row's own constant() function is always shown with its value - proven directly on the fixed sourceSystem row (emptyState test already
		// checks the literal text); this test checks the same function renderer used for a (hypothetical) business rule carrying a constant, since
		// MatchingPolicyTranslator itself would drop such a rule, only the RENDERING side is under test here.
		final String requirements = MatchSurvivorshipBlockTest.requirementsWithOneRule("code", "[{\"function\":\"constant\",\"constantValue\":\"X\"}]", "fixed value",
			"high");
		final String html = MatchSurvivorshipBlockTest.render(requirements, new ArrayList<MatchPlanFieldEvidence>(), MatchSurvivorshipBlock.emptyInput());
		MatchSurvivorshipBlockTest.check("constant value shown", html.contains("constant &quot;X&quot;") || html.contains("constant \"X\""));
	}

	// -------------------------------------------------------------------------------------------------- technical

	private static void technicalRows_sourceSystemSourceKeysReceivedAtAlwaysPresent()
	{
		final String html = MatchSurvivorshipBlockTest.render(MatchSurvivorshipBlockTest.NO_SURVIVORSHIP_REQUIREMENTS, new ArrayList<MatchPlanFieldEvidence>(),
			MatchSurvivorshipBlockTest.input(new ArrayList<MatchSurvivorshipBlock.SourceCard>(), Arrays.asList("supplierId"), null));
		MatchSurvivorshipBlockTest.check("source-key group names supplierId alongside sourceId/_loadId", html.contains("sourceId &middot; supplierId &middot; _loadId")
			|| html.contains("sourceId · supplierId · _loadId"));
		MatchSurvivorshipBlockTest.check("no merge shown for the source-key group", html.contains("no merge"));
		MatchSurvivorshipBlockTest.check("_receivedAt survives as the most recent", html.contains("most recent"));
		MatchSurvivorshipBlockTest.check("every technical row is attributed to the Factory", html.contains("Factory"));
	}

	private static void technicalRows_oneRowPerDerivedKeyField()
	{
		final List<MatchSurvivorshipBlock.DerivedKeyField> derived = new ArrayList<MatchSurvivorshipBlock.DerivedKeyField>();
		derived.add(new MatchSurvivorshipBlock.DerivedKeyField("nameKey", "name"));
		derived.add(new MatchSurvivorshipBlock.DerivedKeyField("cityKey", "city"));
		final String html = MatchSurvivorshipBlockTest.render(MatchSurvivorshipBlockTest.NO_SURVIVORSHIP_REQUIREMENTS, new ArrayList<MatchPlanFieldEvidence>(),
			MatchSurvivorshipBlockTest.input(new ArrayList<MatchSurvivorshipBlock.SourceCard>(), null, derived));
		MatchSurvivorshipBlockTest.check("nameKey row present", html.contains(">nameKey<"));
		MatchSurvivorshipBlockTest.check("nameKey follows name", html.contains("follows name"));
		MatchSurvivorshipBlockTest.check("cityKey row present", html.contains(">cityKey<"));
		MatchSurvivorshipBlockTest.check("cityKey follows city", html.contains("follows city"));
	}

	// ------------------------------------------------------------------------------------------------------ sources

	private static void sourceCards_renderedWithRankDescriptionAndRecordCount()
	{
		final String html = MatchSurvivorshipBlockTest.render(MatchSurvivorshipBlockTest.NO_SURVIVORSHIP_REQUIREMENTS, new ArrayList<MatchPlanFieldEvidence>(),
			MatchSurvivorshipBlockTest.input(MatchSurvivorshipBlockTest.twoSources(), null, null));
		MatchSurvivorshipBlockTest.check("ERP shown", html.contains(">ERP<"));
		MatchSurvivorshipBlockTest.check("rank 1 shown", html.contains("rank 1 - wizard"));
		MatchSurvivorshipBlockTest.check("description shown", html.contains("SAP, referentiel fournisseurs"));
		MatchSurvivorshipBlockTest.check("record count shown", html.contains("55 record(s) in the master table"));
		MatchSurvivorshipBlockTest.check("CRM shown at rank 2", html.contains(">CRM<") && html.contains("rank 2 - wizard"));
		MatchSurvivorshipBlockTest.check("the wizard-editable note is shown", html.contains("Order changeable in the wizard"));
		MatchSurvivorshipBlockTest.check("survivor sentence resolves the fallback to words", html.contains("the most trusted source, then the most complete."));
	}

	private static void noSources_stillRendersWithoutCrashing()
	{
		final String html = MatchSurvivorshipBlockTest.render(MatchSurvivorshipBlockTest.NO_SURVIVORSHIP_REQUIREMENTS, new ArrayList<MatchPlanFieldEvidence>(),
			MatchSurvivorshipBlock.emptyInput());
		MatchSurvivorshipBlockTest.check("no source-card markup when there are no sources", !html.contains("mdmf-surv-src\">"));
		MatchSurvivorshipBlockTest.check("still renders the default field rule sentence", html.contains("the value of the most trusted source, if empty the longest."));
	}

	// -------------------------------------------------------------------------------------------------- fill bars

	private static void fillBars_renderedFromEvidenceAgainstSourceRecordCounts()
	{
		final String requirements = MatchSurvivorshipBlockTest.requirementsWithOneRule("vatNo", "[{\"function\":\"most trusted source\"}]", "quasi absent du CRM", "high");
		final java.util.Map<String, Long> filled = new java.util.LinkedHashMap<String, Long>();
		filled.put("ERP", Long.valueOf(53));
		filled.put("CRM", Long.valueOf(2));
		final List<MatchPlanFieldEvidence> evidence = new ArrayList<MatchPlanFieldEvidence>();
		evidence.add(new MatchPlanFieldEvidence("vatNo", MatchPlanFieldEvidence.ROLE_SURVIVORSHIP, null, null, filled, MatchPlanFieldEvidence.VERDICT_OK,
			"quasi absent du CRM", "high"));
		final String html = MatchSurvivorshipBlockTest.render(requirements, evidence, MatchSurvivorshipBlockTest.input(MatchSurvivorshipBlockTest.twoSources(), null, null));
		// ERP: 53/55 = 96%, CRM: 2/52 = 4%
		MatchSurvivorshipBlockTest.check("ERP fill percentage shown", html.contains("ERP 96%"));
		MatchSurvivorshipBlockTest.check("CRM fill percentage shown", html.contains("CRM 4%"));
		MatchSurvivorshipBlockTest.check("mini bar markup present", html.contains("mdmf-mini-bar"));
	}

	/** Lot 1 (2026-09-23, quality-informed survivorship): a second mini-bar, "valid x/y", next to the fill one - only when the field's own evidence carries {@code validBySystem}. */
	private static void validBars_renderedFromEvidenceNextToFillBars()
	{
		final String requirements = MatchSurvivorshipBlockTest.requirementsWithOneRule("vatNo", "[{\"function\":\"most trusted source\"}]", "ERP is cleaner", "high");
		final java.util.Map<String, MatchPlanFieldEvidence.Validity> validBySystem = new java.util.LinkedHashMap<String, MatchPlanFieldEvidence.Validity>();
		validBySystem.put("ERP", new MatchPlanFieldEvidence.Validity(52, 48));
		validBySystem.put("CRM", new MatchPlanFieldEvidence.Validity(50, 10));
		final List<MatchPlanFieldEvidence> evidence = new ArrayList<MatchPlanFieldEvidence>();
		evidence.add(new MatchPlanFieldEvidence("vatNo", MatchPlanFieldEvidence.ROLE_SURVIVORSHIP, null, null, null, MatchPlanFieldEvidence.VERDICT_OK, "ERP is cleaner",
			"high", validBySystem));
		final String html = MatchSurvivorshipBlockTest.render(requirements, evidence, MatchSurvivorshipBlockTest.input(MatchSurvivorshipBlockTest.twoSources(), null, null));
		MatchSurvivorshipBlockTest.check("ERP valid count shown", html.contains("ERP 48/52"));
		MatchSurvivorshipBlockTest.check("CRM valid count shown", html.contains("CRM 10/50"));
		MatchSurvivorshipBlockTest.check("valid span markup present", html.contains("class=\"valid\""));
	}

	/** No validity rule on the field at all (the overwhelming majority): no valid span rendered, never a fabricated 0/0. */
	private static void validBars_absentWhenEvidenceHasNoValidBySystem()
	{
		final String requirements = MatchSurvivorshipBlockTest.requirementsWithOneRule("city", "[{\"function\":\"most frequent\"}]", "no validity rule on city", "medium");
		final List<MatchPlanFieldEvidence> evidence = new ArrayList<MatchPlanFieldEvidence>();
		evidence.add(new MatchPlanFieldEvidence("city", MatchPlanFieldEvidence.ROLE_SURVIVORSHIP, null, null, null, MatchPlanFieldEvidence.VERDICT_OK,
			"no validity rule on city", "medium"));
		final String html = MatchSurvivorshipBlockTest.render(requirements, evidence, MatchSurvivorshipBlockTest.input(MatchSurvivorshipBlockTest.twoSources(), null, null));
		MatchSurvivorshipBlockTest.check("no valid span rendered", !html.contains("class=\"valid\""));
	}

	// ------------------------------------------------------------------------------------------------------- escape

	private static void htmlEscaping_rationaleAndFieldNameEscaped()
	{
		final String requirements = MatchSurvivorshipBlockTest.requirementsWithOneRule("city", "[{\"function\":\"most frequent\"}]", "value <b>injected</b> & stuff",
			"high");
		final String html = MatchSurvivorshipBlockTest.render(requirements, new ArrayList<MatchPlanFieldEvidence>(), MatchSurvivorshipBlock.emptyInput());
		MatchSurvivorshipBlockTest.check("rationale is escaped, never raw HTML", !html.contains("<b>injected</b>"));
		MatchSurvivorshipBlockTest.check("escaped form present instead", html.contains("&lt;b&gt;injected&lt;/b&gt;"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
