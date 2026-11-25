package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.master.MatchPlanRules;
import com.on.ebx.factory.matching.CandidateFilterCheck;
import com.on.ebx.factory.matching.CandidateFilterCheckTest;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. Lot M8a (2026-10-04, {@code docs/MATCHING.md} &sect;51), items 4 and 5: the read-only
 * candidate-search record of {@link MatchingConfigurator#recordCandidateSearch} (step detail + WARNING for {@code matching/configure} and {@code matching/run}) and what the
 * rule-based {@code matching/suggest} pass records about the plans it produced ({@link MatchPlanSuggester#candidateFilterCheck}). Same package on purpose, to reach the
 * package-private statics.
 */
public final class MatchingCandidateSearchRecordTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		final String previous = System.getProperty(CandidateFilterCheck.JVM_OPTION);
		try
		{
			MatchingCandidateSearchRecordTest.filteredPlan_optionOff_recordsAndWarns();
			MatchingCandidateSearchRecordTest.filteredPlan_optionOn_recordsWithoutWarning();
			MatchingCandidateSearchRecordTest.notFilteredPlan_warnsWhateverTheOption();
			MatchingCandidateSearchRecordTest.acceptedReasonIsRecorded();
			MatchingCandidateSearchRecordTest.noTree_recordsNothing();
			MatchingCandidateSearchRecordTest.requirementsOrNull_neverThrows();
			MatchingCandidateSearchRecordTest.ruleBasedProposal_isCheckedAndFiltered();
			MatchingCandidateSearchRecordTest.suggestPass_rationaleCarriesTheWarningOnlyWhenThereIsOne();
		}
		finally
		{
			if (previous == null)
			{
				System.clearProperty(CandidateFilterCheck.JVM_OPTION);
			}
			else
			{
				System.setProperty(CandidateFilterCheck.JVM_OPTION, previous);
			}
		}
		System.out.println("MatchingCandidateSearchRecordTest OK");
	}

	private static JsonNode notFiltered() throws Exception
	{
		return MatchingCandidateSearchRecordTest.MAPPER.readTree("{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":80,"
			+ "\"fields\":[{\"field\":\"lastName\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":3}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}");
	}

	private static void filteredPlan_optionOff_recordsAndWarns()
	{
		System.clearProperty(CandidateFilterCheck.JVM_OPTION);
		final ObjectNode detail = MatchingCandidateSearchRecordTest.MAPPER.createObjectNode();
		final String warning = MatchingConfigurator.recordCandidateSearch(detail, CandidateFilterCheckTest.e3Requirements());
		check("a warning comes back: " + warning, warning != null && warning.contains("candidate filters are OFF on this server")
			&& warning.contains("-Debx.addon.mame.matching.mandatoryFieldSets.enabled=true"));
		final JsonNode node = detail.path("candidateSearch");
		check("the verdict is recorded", "FILTERED".equals(node.path("verdict").asText()));
		check("the option's state is recorded", "OFF".equals(node.path("jvmOption").asText()));
		check("the filters are recorded", "emailKey OR phoneE164 OR adresseKeys".equals(node.path("filters").asText()));
		check("no path lacks a key", node.path("pathsWithoutKey").size() == 0);
		check("the warning is in the detail too", warning.equals(node.path("warning").asText()));
	}

	private static void filteredPlan_optionOn_recordsWithoutWarning()
	{
		System.setProperty(CandidateFilterCheck.JVM_OPTION, "true");
		final ObjectNode detail = MatchingCandidateSearchRecordTest.MAPPER.createObjectNode();
		final String warning = MatchingConfigurator.recordCandidateSearch(detail, CandidateFilterCheckTest.e3Requirements());
		check("no warning", warning == null);
		check("still recorded, option ON, no warning field", "ON".equals(detail.path("candidateSearch").path("jvmOption").asText()) && detail.path("candidateSearch").path("warning").isMissingNode());
	}

	private static void notFilteredPlan_warnsWhateverTheOption() throws Exception
	{
		for (final String option : new String[] { "true", null })
		{
			if (option == null)
			{
				System.clearProperty(CandidateFilterCheck.JVM_OPTION);
			}
			else
			{
				System.setProperty(CandidateFilterCheck.JVM_OPTION, option);
			}
			final ObjectNode detail = MatchingCandidateSearchRecordTest.MAPPER.createObjectNode();
			final String warning = MatchingConfigurator.recordCandidateSearch(detail, MatchingCandidateSearchRecordTest.notFiltered());
			check("a NOT filtered warning (option " + option + "): " + warning, warning != null && warning.contains("NOT filtered") && !warning.contains("OFF on this server"));
			check("the path without a key is listed", "N1".equals(detail.path("candidateSearch").path("pathsWithoutKey").get(0).asText()));
			check("verdict NOT_FILTERED", "NOT_FILTERED".equals(detail.path("candidateSearch").path("verdict").asText()));
		}
	}

	private static void acceptedReasonIsRecorded() throws Exception
	{
		final ObjectNode requirements = (ObjectNode) MatchingCandidateSearchRecordTest.notFiltered();
		requirements.put("unfilteredCandidateSearchReason", "No exact key exists.");
		final ObjectNode detail = MatchingCandidateSearchRecordTest.MAPPER.createObjectNode();
		MatchingConfigurator.recordCandidateSearch(detail, requirements);
		check("the model's reason is recorded", "No exact key exists.".equals(detail.path("candidateSearch").path("acceptedReason").asText()));
	}

	private static void noTree_recordsNothing()
	{
		final ObjectNode detail = MatchingCandidateSearchRecordTest.MAPPER.createObjectNode();
		check("null requirements: no warning", MatchingConfigurator.recordCandidateSearch(detail, null) == null);
		check("and nothing recorded", detail.size() == 0);
	}

	private static void requirementsOrNull_neverThrows()
	{
		check("null", MatchingConfigurator.requirementsOrNull(null) == null);
		check("blank", MatchingConfigurator.requirementsOrNull("  ") == null);
		check("garbage", MatchingConfigurator.requirementsOrNull("{not json") == null);
		check("valid", MatchingConfigurator.requirementsOrNull("{\"a\":1}") != null);
	}

	private static void ruleBasedProposal_isCheckedAndFiltered() throws Exception
	{
		// the rule-based pass proposes one ALL_FIELDS exact node per validated identifier: it never invents a key, and what it produces is FILTERED
		final MatchPlanRules.EntityInfo company = new MatchPlanRules.EntityInfo("Company", Arrays.asList(new MatchPlanRules.AttributeInfo("vatNo", "STRING", null, "IBAN"),
			new MatchPlanRules.AttributeInfo("name", "STRING", null, null)));
		final List<MatchPlanRules.Proposal> proposals = MatchPlanRules.propose(java.util.Collections.singletonList(company));
		final CandidateFilterCheck.Result result = MatchPlanSuggester.candidateFilterCheck(proposals.get(0).requirementsJson);
		check("FILTERED on vatNo: " + result.verdict + " " + result.filtersPhrase(), result.filtered() && "vatNo".equals(result.filtersPhrase()));
		check("an unparsable stored plan is not applicable, never an exception", CandidateFilterCheck.VERDICT_NOT_APPLICABLE.equals(MatchPlanSuggester.candidateFilterCheck("{bad").verdict));
	}

	private static void suggestPass_rationaleCarriesTheWarningOnlyWhenThereIsOne()
	{
		check("no warning: the rationale is the proposal's own", "why".equals(MatchPlanSuggester.rationaleWithWarning("why", null)));
		check("blank warning: unchanged", "why".equals(MatchPlanSuggester.rationaleWithWarning("why", "  ")));
		check("a warning follows the rationale", "why WARN".equals(MatchPlanSuggester.rationaleWithWarning("why", "WARN")));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
