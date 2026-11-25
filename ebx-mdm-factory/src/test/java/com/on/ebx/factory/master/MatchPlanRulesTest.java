package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MatchPlanRulesTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanRulesTest.identifierBackedEntity_proposedWithExactTierOnly();
		MatchPlanRulesTest.twoIdentifiers_twoExactTiers();
		MatchPlanRulesTest.childEntity_nowProposedLikeAnyOther_unguarded();
		MatchPlanRulesTest.childEntity_referenceOfferedAsClusteringFieldWhenGuardAccepts();
		MatchPlanRulesTest.childEntity_referenceExcludedFromClusteringWhenGuardRefuses();
		MatchPlanRulesTest.childEntity_noIdentifier_stillSkippedLikeAnyOther();
		MatchPlanRulesTest.selfReference_notTreatedAsChild();
		MatchPlanRulesTest.noIdentifier_notProposed_regardlessOfFieldNames();
		MatchPlanRulesTest.autoMergeNeverSetTrue_mergeHintsAreFixedDefaults();
		MatchPlanRulesTest.guardNotWired_unwiredBehaviourUnchanged();
		MatchPlanRulesTest.guardNotWired_evidenceReadsNotMeasured();
		MatchPlanRulesTest.singleIdentifierFailsGuard_entityFallsBackToNoUsableIdentifier();
		MatchPlanRulesTest.oneOfTwoIdentifiersFailsGuard_otherStillProposed();
		MatchPlanRulesTest.identifierWithMatchKey_comparedAndClusteredViaTheKey();
		System.out.println("MatchPlanRulesTest OK");
	}

	/**
	 * A validated identifier proposes an exact, MATCH-terminal tier - whatever the attribute is actually
	 * called ("vatNo" here, deliberately not "name"/"country"/"city": the rule must not depend on those
	 * names any more). A same-entity field literally named "name" carries no quality rule and must NOT earn
	 * any role: this pass no longer guesses a fuzzy anchor from a field's name.
	 */
	private static void identifierBackedEntity_proposedWithExactTierOnly() throws Exception
	{
		final MatchPlanRules.EntityInfo company = new MatchPlanRules.EntityInfo("Company",
			Arrays.asList(MatchPlanRulesTest.attribute("sourceSystem", "STRING", null, null), MatchPlanRulesTest.attribute("sourceId", "STRING", null, null),
				MatchPlanRulesTest.attribute("vatNo", "STRING", null, "IBAN"), MatchPlanRulesTest.attribute("name", "STRING", null, null),
				MatchPlanRulesTest.attribute("country", "STRING", null, null), MatchPlanRulesTest.attribute("city", "STRING", null, null),
				MatchPlanRulesTest.attribute("legalForm", "STRING", null, null)));

		final List<MatchPlanRules.Proposal> proposals = MatchPlanRules.propose(Collections.singletonList(company));
		MatchPlanRulesTest.check("one proposal", proposals.size() == 1);
		final MatchPlanRules.Proposal proposal = proposals.get(0);
		MatchPlanRulesTest.check("proposed", proposal.propose);
		MatchPlanRulesTest.check("no skip reason", proposal.skipReason == null);
		MatchPlanRulesTest.check("confidence 85 (identifier present)", proposal.confidence == 85);
		MatchPlanRulesTest.check("rationale mentions vatNo", proposal.rationale.contains("vatNo"));

		final JsonNode requirements = MatchPlanRulesTest.MAPPER.readTree(proposal.requirementsJson);
		final JsonNode nodes = requirements.path("decisionTree").path("nodes");
		MatchPlanRulesTest.check("exactly one node: the exact vatNo node, no fuzzy node any more", nodes.size() == 1);
		final JsonNode exactNode = nodes.get(0);
		MatchPlanRulesTest.check("exact node onTrue MATCH", "MATCH".equals(exactNode.path("onTrue").asText()));
		MatchPlanRulesTest.check("exact node onFalse NOT_MATCHED", "NOT_MATCHED".equals(exactNode.path("onFalse").asText()));
		MatchPlanRulesTest.check("exact node function ALL_FIELDS", "ALL_FIELDS".equals(exactNode.path("function").asText()));
		MatchPlanRulesTest.check("exact node field is vatNo", "vatNo".equals(exactNode.path("fields").get(0).path("field").asText()));
		MatchPlanRulesTest.check("exact algorithm", "ebx-addon-mame@exact".equals(exactNode.path("fields").get(0).path("algorithm").asText()));
		MatchPlanRulesTest.check("exact minimumScore 100", exactNode.path("fields").get(0).path("minimumScore").asInt() == 100);
		MatchPlanRulesTest.check("root points at the only node", exactNode.path("id").asText().equals(requirements.path("decisionTree").path("root").asText()));

		MatchPlanRulesTest.check("name never appears in the requirements (no fuzzy tier is guessed from a field name any more)",
			!proposal.requirementsJson.contains("\"name\""));
		MatchPlanRulesTest.check("country never appears", !proposal.requirementsJson.contains("country"));
		MatchPlanRulesTest.check("city never appears", !proposal.requirementsJson.contains("city"));
		MatchPlanRulesTest.check("legalForm never appears either", !proposal.requirementsJson.contains("legalForm"));

		final JsonNode mergePolicy = requirements.path("mergePolicy");
		MatchPlanRulesTest.check("merge mode ONLY_DUPLICATES (fixed default)", "ONLY_DUPLICATES".equals(mergePolicy.path("mode").asText()));
		MatchPlanRulesTest.check("record selection mostCompleted (fixed default)", "mostCompleted".equals(mergePolicy.path("recordSelection").asText()));
		MatchPlanRulesTest.check("default field merge longest (fixed default)", "longest".equals(mergePolicy.path("defaultFieldMerge").asText()));
	}

	/** An entity can carry more than one validated identifier: each gets its own exact tier. */
	private static void twoIdentifiers_twoExactTiers() throws Exception
	{
		final MatchPlanRules.EntityInfo product = new MatchPlanRules.EntityInfo("Product",
			Arrays.asList(MatchPlanRulesTest.attribute("gtin", "STRING", null, "GTIN"), MatchPlanRulesTest.attribute("isbn", "STRING", null, "ISBN"),
				MatchPlanRulesTest.attribute("description", "STRING", null, null)));
		final MatchPlanRules.Proposal proposal = MatchPlanRules.propose(Collections.singletonList(product)).get(0);
		MatchPlanRulesTest.check("proposed", proposal.propose);
		final JsonNode nodes = MatchPlanRulesTest.MAPPER.readTree(proposal.requirementsJson).path("decisionTree").path("nodes");
		MatchPlanRulesTest.check("two exact nodes, one per identifier, chained", nodes.size() == 2);
		MatchPlanRulesTest.check("both onTrue MATCH", "MATCH".equals(nodes.get(0).path("onTrue").asText()) && "MATCH".equals(nodes.get(1).path("onTrue").asText()));
		MatchPlanRulesTest.check("the first node's onFalse chains to the second", nodes.get(0).path("onFalse").asText().equals(nodes.get(1).path("id").asText()));
		MatchPlanRulesTest.check("the second (last) node's onFalse is the final NOT_MATCHED leaf", "NOT_MATCHED".equals(nodes.get(1).path("onFalse").asText()));
		MatchPlanRulesTest.check("description (no identifier, just a name-like field) plays no role", !proposal.requirementsJson.contains("description"));
	}

	/**
	 * 2026-09-22, reversed (`docs/MATCHING.md`): Contact.accountIdRef -> Company is a child entity, but now gets
	 * a plan proposed exactly like any other entity that carries a validated identifier. The unwired ({@code
	 * propose(List)}) overload never touches the cross-system guard, so the reference field is never offered as
	 * a clustering candidate either (nothing to measure it with) - only the rationale names the child link.
	 */
	private static void childEntity_nowProposedLikeAnyOther_unguarded()
	{
		final MatchPlanRules.EntityInfo contact = new MatchPlanRules.EntityInfo("Contact",
			Arrays.asList(MatchPlanRulesTest.attribute("email", "STRING", null, "SIREN"), MatchPlanRulesTest.attribute("accountId", "STRING", "Company", null)));
		final List<MatchPlanRules.Proposal> proposals = MatchPlanRules.propose(Collections.singletonList(contact));
		final MatchPlanRules.Proposal proposal = proposals.get(0);
		MatchPlanRulesTest.check("proposed - a child entity is no longer skipped", proposal.propose);
		MatchPlanRulesTest.check("no skip reason", proposal.skipReason == null);
		MatchPlanRulesTest.check("clusteringFields carries only the identifier (email) - the reference is never offered when the guard is not wired",
			!proposal.requirementsJson.contains("accountId"));
		MatchPlanRulesTest.check("rationale names the child link and that the guard must be wired first",
			proposal.rationale.contains("child of Company") && proposal.rationale.contains("accountId") && proposal.rationale.contains("guard is wired"));
	}

	/** With the guard wired and both source systems sharing values of the parent reference, it is added as an ADDITIONAL clustering field - never a tier field (see class Javadoc for why). */
	private static void childEntity_referenceOfferedAsClusteringFieldWhenGuardAccepts() throws Exception
	{
		final MatchPlanRules.EntityInfo contact = new MatchPlanRules.EntityInfo("Contact",
			Arrays.asList(MatchPlanRulesTest.attribute("email", "STRING", null, "SIREN"), MatchPlanRulesTest.attribute("accountId", "STRING", "Company", null)));
		final Map<String, Long> filled = new LinkedHashMap<String, Long>();
		filled.put("ERP", Long.valueOf(55));
		filled.put("CRM", Long.valueOf(52));
		final MatchPlanLlmPass.CrossSystemUsageLookup shared = new MatchPlanLlmPass.CrossSystemUsageLookup()
		{
			@Override
			public Integer sharedCount(final String pEntityName, final String pFieldName)
			{
				return Integer.valueOf(4);
			}
		};
		final MatchPlanLlmPass.PerSystemFillLookup perSystem = new MatchPlanLlmPass.PerSystemFillLookup()
		{
			@Override
			public Map<String, Long> filledCountBySystem(final String pEntityName, final String pFieldName)
			{
				return filled;
			}
		};
		final MatchPlanRules.Proposal proposal = MatchPlanRules.propose(Collections.singletonList(contact), shared, perSystem).get(0);
		MatchPlanRulesTest.check("proposed", proposal.propose);
		final JsonNode requirements = MatchPlanRulesTest.MAPPER.readTree(proposal.requirementsJson);
		boolean foundAccountId = false;
		for (final JsonNode field : requirements.path("clusteringFields"))
		{
			if ("accountId".equals(field.asText()))
			{
				foundAccountId = true;
			}
		}
		MatchPlanRulesTest.check("the parent reference is offered as a clustering candidate", foundAccountId);
		MatchPlanRulesTest.check("the reference never appears in a decision-tree node (never a decision field - see class Javadoc)",
			!requirements.path("decisionTree").path("nodes").toString().contains("accountId"));
		MatchPlanRulesTest.check("rationale names the offer", proposal.rationale.contains("accountId") && proposal.rationale.contains("supporting clustering field"));
	}

	/**
	 * With the guard wired but the parent reference entirely empty for one system, it is excluded from
	 * clustering, naming why - the entity itself is still proposed on its own identifier (fully filled in both
	 * systems, unlike the reference field, so the guard treats the two fields differently).
	 */
	private static void childEntity_referenceExcludedFromClusteringWhenGuardRefuses() throws Exception
	{
		final MatchPlanRules.EntityInfo contact = new MatchPlanRules.EntityInfo("Contact",
			Arrays.asList(MatchPlanRulesTest.attribute("email", "STRING", null, "SIREN"), MatchPlanRulesTest.attribute("accountId", "STRING", "Company", null)));
		final Map<String, Long> identifierFilled = new LinkedHashMap<String, Long>();
		identifierFilled.put("ERP", Long.valueOf(55));
		identifierFilled.put("CRM", Long.valueOf(52));
		final Map<String, Long> referenceFilled = new LinkedHashMap<String, Long>();
		referenceFilled.put("ERP", Long.valueOf(55));
		referenceFilled.put("CRM", Long.valueOf(0));
		final MatchPlanLlmPass.PerSystemFillLookup perSystem = new MatchPlanLlmPass.PerSystemFillLookup()
		{
			@Override
			public Map<String, Long> filledCountBySystem(final String pEntityName, final String pFieldName)
			{
				return "accountId".equals(pFieldName) ? referenceFilled : identifierFilled;
			}
		};
		final MatchPlanLlmPass.CrossSystemUsageLookup shared = new MatchPlanLlmPass.CrossSystemUsageLookup()
		{
			@Override
			public Integer sharedCount(final String pEntityName, final String pFieldName)
			{
				return Integer.valueOf(4);
			}
		};
		final MatchPlanRules.Proposal proposal = MatchPlanRules.propose(Collections.singletonList(contact), shared, perSystem).get(0);
		MatchPlanRulesTest.check("still proposed on its own identifier", proposal.propose);
		MatchPlanRulesTest.check("the reference is NOT in clusteringFields", !proposal.requirementsJson.contains("accountId"));
		MatchPlanRulesTest.check("rationale names the exclusion and why", proposal.rationale.contains("accountId") && proposal.rationale.contains("excluded from clustering"));
	}

	/** A child entity with no validated identifier is skipped exactly like any other entity - the child relationship is no longer a separate reason. */
	private static void childEntity_noIdentifier_stillSkippedLikeAnyOther()
	{
		final MatchPlanRules.EntityInfo contact = new MatchPlanRules.EntityInfo("Contact",
			Arrays.asList(MatchPlanRulesTest.attribute("email", "STRING", null, null), MatchPlanRulesTest.attribute("accountId", "STRING", "Company", null)));
		final MatchPlanRules.Proposal proposal = MatchPlanRules.propose(Collections.singletonList(contact)).get(0);
		MatchPlanRulesTest.check("not proposed - no validated identifier, same reason as any other entity", !proposal.propose);
		MatchPlanRulesTest.check("skip reason is the ordinary 'no validated identifier' one, not a child-specific one",
			proposal.skipReason.contains("no validated identifier") && !proposal.skipReason.contains("child"));
	}

	/** Account.parentAccountId -> Account (itself): a self reference, never counted as a child. */
	private static void selfReference_notTreatedAsChild()
	{
		final MatchPlanRules.EntityInfo account = new MatchPlanRules.EntityInfo("Account",
			Arrays.asList(MatchPlanRulesTest.attribute("registrationNumber", "STRING", null, "SIREN"),
				MatchPlanRulesTest.attribute("parentAccountId", "STRING", "Account", null)));
		final List<MatchPlanRules.Proposal> proposals = MatchPlanRules.propose(Collections.singletonList(account));
		MatchPlanRulesTest.check("proposed (self-reference is not a child relationship)", proposals.get(0).propose);
	}

	/**
	 * No validated identifier: never proposed, regardless of how promising the field NAMES look (a field
	 * literally called "name" earns no role any more - only an accepted CHECK_DIGIT quality rule does).
	 */
	private static void noIdentifier_notProposed_regardlessOfFieldNames()
	{
		final MatchPlanRules.EntityInfo widget = new MatchPlanRules.EntityInfo("Widget",
			Arrays.asList(MatchPlanRulesTest.attribute("sku", "STRING", null, null), MatchPlanRulesTest.attribute("weight", "DECIMAL", null, null)));
		final MatchPlanRules.Proposal widgetProposal = MatchPlanRules.propose(Collections.singletonList(widget)).get(0);
		MatchPlanRulesTest.check("not proposed", !widgetProposal.propose);
		MatchPlanRulesTest.check("skip reason explains why", widgetProposal.skipReason.contains("no validated identifier"));
		MatchPlanRulesTest.check("skip reason points at the language-model pass", widgetProposal.skipReason.contains("matching/suggest-llm"));

		final MatchPlanRules.EntityInfo person = new MatchPlanRules.EntityInfo("Person",
			Collections.singletonList(MatchPlanRulesTest.attribute("fullName", "STRING", null, null)));
		final MatchPlanRules.Proposal personProposal = MatchPlanRules.propose(Collections.singletonList(person)).get(0);
		MatchPlanRulesTest.check("a promising field name (fullName) alone is still not proposed: no identifier, no fuzzy-tier guess any more", !personProposal.propose);
	}

	private static void autoMergeNeverSetTrue_mergeHintsAreFixedDefaults()
	{
		// MatchPlanRules never decides autoMerge at all (the schema default, false, is left to the caller/MatchPlan row) -
		// this test only pins that an identifier-backed proposal always carries the same fixed merge-policy hints.
		final MatchPlanRules.EntityInfo withIdentifier = new MatchPlanRules.EntityInfo("A", Collections.singletonList(MatchPlanRulesTest.attribute("siren", "STRING", null, "SIREN")));
		final MatchPlanRules.Proposal proposal = MatchPlanRules.propose(Collections.singletonList(withIdentifier)).get(0);
		MatchPlanRulesTest.check("confidence is the fixed 85 whenever an identifier backs the proposal", proposal.confidence == 85);
	}

	// ------------------------------------------------------------------------------------- CrossSystemFieldGuard

	/** {@link MatchPlanRules#propose(List)}, the 1-arg overload, never touches the guard at all - byte-for-byte the original behaviour, even for an identifier the guard would refuse if it were wired. */
	private static void guardNotWired_unwiredBehaviourUnchanged()
	{
		final MatchPlanRules.EntityInfo company = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(MatchPlanRulesTest.attribute("legalForm", "STRING", null, "SIREN")));
		final MatchPlanRules.Proposal proposal = MatchPlanRules.propose(Collections.singletonList(company)).get(0);
		MatchPlanRulesTest.check("proposed even though a wired guard would refuse this identifier - the guard was never asked", proposal.propose);
		MatchPlanRulesTest.check("no guard-related text in the rationale when the guard is not wired", !proposal.rationale.contains("cross-system"));
	}

	/**
	 * `PLAN.md`'s "Rapprochement" own rule: "a rule must not propose what the guard would refuse." A single
	 * validated identifier that fails the cross-system guard (entirely empty for one source system, Plan A's
	 * own shape) leaves the entity with no usable identifier at all - skipped, naming why.
	 */
	private static void singleIdentifierFailsGuard_entityFallsBackToNoUsableIdentifier()
	{
		final MatchPlanRules.EntityInfo company = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(MatchPlanRulesTest.attribute("legalForm", "STRING", null, "SIREN")));
		final Map<String, Long> filled = new LinkedHashMap<String, Long>();
		filled.put("ERP", Long.valueOf(55));
		filled.put("CRM", Long.valueOf(0));
		final MatchPlanLlmPass.PerSystemFillLookup perSystem = new MatchPlanLlmPass.PerSystemFillLookup()
		{
			@Override
			public Map<String, Long> filledCountBySystem(final String pEntityName, final String pFieldName)
			{
				return filled;
			}
		};
		final MatchPlanRules.Proposal proposal = MatchPlanRules.propose(Collections.singletonList(company), null, perSystem).get(0);
		MatchPlanRulesTest.check("not proposed: the only identifier failed the guard", !proposal.propose);
		MatchPlanRulesTest.check("skip reason names the field", proposal.skipReason.contains("legalForm"));
		MatchPlanRulesTest.check("skip reason names the cross-system usability guard", proposal.skipReason.contains("cross-system usability guard"));
		MatchPlanRulesTest.check("skip reason names the empty system", proposal.skipReason.contains("CRM"));
	}

	/** Two validated identifiers, one fails the guard (Plan B's own shape - filled everywhere, zero shared) and one passes: the entity is still proposed, with only the usable identifier's exact tier. */
	private static void oneOfTwoIdentifiersFailsGuard_otherStillProposed() throws Exception
	{
		final MatchPlanRules.EntityInfo company = new MatchPlanRules.EntityInfo("Company",
			Arrays.asList(MatchPlanRulesTest.attribute("vatNo", "STRING", null, "IBAN"), MatchPlanRulesTest.attribute("country", "STRING", null, "SIREN")));
		final Map<String, Long> filledBoth = new LinkedHashMap<String, Long>();
		filledBoth.put("ERP", Long.valueOf(55));
		filledBoth.put("CRM", Long.valueOf(52));
		final MatchPlanLlmPass.CrossSystemUsageLookup shared = new MatchPlanLlmPass.CrossSystemUsageLookup()
		{
			@Override
			public Integer sharedCount(final String pEntityName, final String pFieldName)
			{
				return "country".equals(pFieldName) ? Integer.valueOf(0) : Integer.valueOf(4);
			}
		};
		final MatchPlanLlmPass.PerSystemFillLookup perSystem = new MatchPlanLlmPass.PerSystemFillLookup()
		{
			@Override
			public Map<String, Long> filledCountBySystem(final String pEntityName, final String pFieldName)
			{
				return filledBoth;
			}
		};
		final MatchPlanRules.Proposal proposal = MatchPlanRules.propose(Collections.singletonList(company), shared, perSystem).get(0);
		MatchPlanRulesTest.check("still proposed: vatNo passed the guard", proposal.propose);
		final JsonNode nodes = MatchPlanRulesTest.MAPPER.readTree(proposal.requirementsJson).path("decisionTree").path("nodes");
		MatchPlanRulesTest.check("exactly one exact node, for vatNo only", nodes.size() == 1 && "vatNo".equals(nodes.get(0).path("fields").get(0).path("field").asText()));
		MatchPlanRulesTest.check("country never appears in the requirements - excluded by the guard", !proposal.requirementsJson.contains("country"));
		MatchPlanRulesTest.check("rationale names the excluded identifier and why", proposal.rationale.contains("country") && proposal.rationale.contains("no value is shared"));
		MatchPlanRulesTest.check("rationale also carries the accepted identifier's own cross-system measurement (item 5, both routes)",
			proposal.rationale.contains("Cross-system usability evidence:") && proposal.rationale.contains("vatNo: 4 value(s) shared across systems")
				&& proposal.rationale.contains("ERP 55 filled, CRM 52 filled"));

		// review-match-plan lot (2026-09-22): the SAME facts, structured - one entry for the accepted identifier
		// (OK), one for the excluded one (REFUSED), both carrying the guard's own numbers.
		final JsonNode evidence = MatchPlanRulesTest.MAPPER.readTree(proposal.evidenceJson);
		MatchPlanRulesTest.check("two evidence entries", evidence.size() == 2);
		JsonNode vatNoEvidence = null;
		JsonNode countryEvidence = null;
		for (final JsonNode entry : evidence)
		{
			if ("vatNo".equals(entry.path("field").asText()))
			{
				vatNoEvidence = entry;
			}
			else if ("country".equals(entry.path("field").asText()))
			{
				countryEvidence = entry;
			}
		}
		MatchPlanRulesTest.check("vatNo entry present", vatNoEvidence != null);
		MatchPlanRulesTest.check("vatNo verdict OK", "OK".equals(vatNoEvidence.path("verdict").asText()));
		MatchPlanRulesTest.check("vatNo sharedAcrossSystems is 4", vatNoEvidence.path("sharedAcrossSystems").asInt() == 4);
		MatchPlanRulesTest.check("vatNo filledBySystem carries both systems",
			vatNoEvidence.path("filledBySystem").path("ERP").asLong() == 55L && vatNoEvidence.path("filledBySystem").path("CRM").asLong() == 52L);
		MatchPlanRulesTest.check("country entry present", countryEvidence != null);
		MatchPlanRulesTest.check("country verdict REFUSED", "REFUSED".equals(countryEvidence.path("verdict").asText()));
		MatchPlanRulesTest.check("country role REJECTED", "REJECTED".equals(countryEvidence.path("role").asText()));
		MatchPlanRulesTest.check("country note explains why", countryEvidence.path("note").asText().contains("no value is shared"));
	}

	/** {@link MatchPlanRules#propose(java.util.List)} (guard never wired): every candidate identifier's evidence entry reads NOT_MEASURED, never a fabricated OK/REFUSED verdict. */
	private static void guardNotWired_evidenceReadsNotMeasured() throws Exception
	{
		final MatchPlanRules.EntityInfo company = new MatchPlanRules.EntityInfo("Company", Collections.singletonList(MatchPlanRulesTest.attribute("legalForm", "STRING", null, "SIREN")));
		final MatchPlanRules.Proposal proposal = MatchPlanRules.propose(Collections.singletonList(company)).get(0);
		final JsonNode evidence = MatchPlanRulesTest.MAPPER.readTree(proposal.evidenceJson);
		MatchPlanRulesTest.check("one evidence entry", evidence.size() == 1);
		MatchPlanRulesTest.check("verdict NOT_MEASURED when the guard is not wired", "NOT_MEASURED".equals(evidence.get(0).path("verdict").asText()));
	}

	/**
	 * 2026-09-22 matching-key contract, applied identically to the language-model route
	 * ({@code MatchPlanLlmPassTest}'s own matching-key tests): a validated identifier compared/clustered via its
	 * own derived matching key names the KEY, not the source, in both the built exact tier and {@code
	 * clusteringFields} - purely defensive symmetry (a derived attribute never itself carries a quality rule per
	 * the other agent's contract, so this shape is not expected in practice, but the substitution must still hold
	 * if it ever occurs).
	 */
	private static void identifierWithMatchKey_comparedAndClusteredViaTheKey() throws Exception
	{
		final MatchPlanRules.EntityInfo site = new MatchPlanRules.EntityInfo("Site",
			Collections.singletonList(new MatchPlanRules.AttributeInfo("city", "STRING", null, "SIREN", "cityKey")));
		final MatchPlanRules.Proposal proposal = MatchPlanRules.propose(Collections.singletonList(site)).get(0);
		MatchPlanRulesTest.check("proposed", proposal.propose);
		final JsonNode requirements = MatchPlanRulesTest.MAPPER.readTree(proposal.requirementsJson);
		MatchPlanRulesTest.check("exact node field is the KEY, not the source",
			"cityKey".equals(requirements.path("decisionTree").path("nodes").get(0).path("fields").get(0).path("field").asText()));
		MatchPlanRulesTest.check("clusteringFields carries the KEY too", "cityKey".equals(requirements.path("clusteringFields").get(0).asText()));
		MatchPlanRulesTest.check("the source name never appears as a field path", !proposal.requirementsJson.contains("\"field\":\"city\""));
		MatchPlanRulesTest.check("rationale names both the source (for the check-digit sentence) and the key (for the substitution note)",
			proposal.rationale.contains("city") && proposal.rationale.contains("cityKey") && proposal.rationale.contains("matching key"));
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	private static MatchPlanRules.AttributeInfo attribute(final String pName, final String pDataType, final String pReferenceEntity, final String pIdentifierKind)
	{
		return new MatchPlanRules.AttributeInfo(pName, pDataType, pReferenceEntity, pIdentifierKind);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
