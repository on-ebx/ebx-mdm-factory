package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. Covers the PURE half of the
 * relation-evidence-required lot R3 (2026-09-26, `docs/MATCHING.md` &sect;34): {@link
 * MatchPlanLlmSuggester#writeRelationEvidence} (the {@code relationEvidence} JSON block folded into {@code
 * suggest()}'s own step-detail {@code out}) and {@link MatchPlanLlmSuggester#relationEvidenceLogLine} (the one
 * {@code kernel.log} line per parent entity). Neither method touches EBX/MCP - {@link
 * MatchPlanLlmSuggester#buildRelationChildren} itself (the live half, five `continue`s each now feeding one of
 * these reports) needs a real MCP server and is not exercised here, same discipline {@code
 * MatchPlanLlmSuggesterIdentityKeyTest} already established for the sibling pure-precedence split.
 */
public final class MatchPlanLlmSuggesterRelationEvidenceTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		MatchPlanLlmSuggesterRelationEvidenceTest.writeRelationEvidence_emptyListOmitsTheKeyEntirely();
		MatchPlanLlmSuggesterRelationEvidenceTest.writeRelationEvidence_writesOfferedAndSkippedPerEntity();
		MatchPlanLlmSuggesterRelationEvidenceTest.relationEvidenceLogLine_offeredOnly();
		MatchPlanLlmSuggesterRelationEvidenceTest.relationEvidenceLogLine_skippedOnly();
		MatchPlanLlmSuggesterRelationEvidenceTest.relationEvidenceLogLine_offeredAndSkipped();
		MatchPlanLlmSuggesterRelationEvidenceTest.relationEvidenceLogLine_neitherOfferedNorSkipped();
		System.out.println("MatchPlanLlmSuggesterRelationEvidenceTest OK");
	}

	// ------------------------------------------------------------------------------------------- writeRelationEvidence

	/** Same "never an empty key" discipline as {@code putStrings} - an empty report list must not leave a stray, empty {@code relationEvidence} object in the step's detail JSON. */
	private static void writeRelationEvidence_emptyListOmitsTheKeyEntirely()
	{
		final ObjectNode out = MatchPlanLlmSuggesterRelationEvidenceTest.MAPPER.createObjectNode();
		MatchPlanLlmSuggester.writeRelationEvidence(out, new ArrayList<MatchPlanLlmSuggester.RelationEvidenceReport>());
		MatchPlanLlmSuggesterRelationEvidenceTest.check("no relationEvidence key at all when there is nothing to report", !out.has("relationEvidence"));
	}

	private static void writeRelationEvidence_writesOfferedAndSkippedPerEntity()
	{
		final List<MatchPlanLlmSuggester.RelationEvidenceReport.Offered> offered = new ArrayList<MatchPlanLlmSuggester.RelationEvidenceReport.Offered>();
		offered.add(new MatchPlanLlmSuggester.RelationEvidenceReport.Offered("Adresse", "personneRef", 2, true));
		final List<MatchPlanLlmSuggester.RelationEvidenceReport.Skipped> skipped = new ArrayList<MatchPlanLlmSuggester.RelationEvidenceReport.Skipped>();
		skipped.add(new MatchPlanLlmSuggester.RelationEvidenceReport.Skipped("Contrat", "no usable (accepted, non-technical, non-reference) attribute on the child"));
		final MatchPlanLlmSuggester.RelationEvidenceReport report = new MatchPlanLlmSuggester.RelationEvidenceReport("Personne", offered, skipped);

		final ObjectNode out = MatchPlanLlmSuggesterRelationEvidenceTest.MAPPER.createObjectNode();
		MatchPlanLlmSuggester.writeRelationEvidence(out, Collections.singletonList(report));

		final JsonNode personne = out.path("relationEvidence").path("Personne");
		MatchPlanLlmSuggesterRelationEvidenceTest.check("keyed by parent entity name", !personne.isMissingNode());
		final JsonNode offeredNode = personne.path("offered").get(0);
		MatchPlanLlmSuggesterRelationEvidenceTest.check("offered child entity", "Adresse".equals(offeredNode.path("childEntity").asText()));
		MatchPlanLlmSuggesterRelationEvidenceTest.check("offered fk field", "personneRef".equals(offeredNode.path("fkField").asText()));
		MatchPlanLlmSuggesterRelationEvidenceTest.check("offered attribute count", offeredNode.path("attributeCount").asInt() == 2);
		MatchPlanLlmSuggesterRelationEvidenceTest.check("offered row stats measured flag", offeredNode.path("rowStatsMeasured").asBoolean());
		final JsonNode skippedNode = personne.path("skipped").get(0);
		MatchPlanLlmSuggesterRelationEvidenceTest.check("skipped child entity", "Contrat".equals(skippedNode.path("childEntity").asText()));
		MatchPlanLlmSuggesterRelationEvidenceTest.check("skipped reason", skippedNode.path("reason").asText().contains("no usable"));
	}

	// ------------------------------------------------------------------------------------------ relationEvidenceLogLine

	private static void relationEvidenceLogLine_offeredOnly()
	{
		final List<MatchPlanLlmSuggester.RelationEvidenceReport.Offered> offered = Collections
			.singletonList(new MatchPlanLlmSuggester.RelationEvidenceReport.Offered("Adresse", "personneRef", 2, true));
		final String line = MatchPlanLlmSuggester
			.relationEvidenceLogLine(new MatchPlanLlmSuggester.RelationEvidenceReport("Personne", offered, new ArrayList<MatchPlanLlmSuggester.RelationEvidenceReport.Skipped>()));
		MatchPlanLlmSuggesterRelationEvidenceTest.check("names the parent: " + line, line.contains("'Personne'"));
		MatchPlanLlmSuggesterRelationEvidenceTest.check("names the offered child, fk field, attribute count and rowStats: " + line,
			line.contains("Adresse") && line.contains("fkField=personneRef") && line.contains("attributes=2") && line.contains("rowStats=measured"));
		MatchPlanLlmSuggesterRelationEvidenceTest.check("no 'skipped' section when nothing was skipped: " + line, !line.contains("skipped"));
	}

	private static void relationEvidenceLogLine_skippedOnly()
	{
		final List<MatchPlanLlmSuggester.RelationEvidenceReport.Skipped> skipped = Collections
			.singletonList(new MatchPlanLlmSuggester.RelationEvidenceReport.Skipped("Contrat", "no staging entity found for this child"));
		final String line = MatchPlanLlmSuggester
			.relationEvidenceLogLine(new MatchPlanLlmSuggester.RelationEvidenceReport("Personne", new ArrayList<MatchPlanLlmSuggester.RelationEvidenceReport.Offered>(), skipped));
		MatchPlanLlmSuggesterRelationEvidenceTest.check("says no child was offered: " + line, line.contains("no child offered"));
		MatchPlanLlmSuggesterRelationEvidenceTest.check("names the skipped child and its reason: " + line,
			line.contains("Contrat") && line.contains("no staging entity found for this child"));
	}

	private static void relationEvidenceLogLine_offeredAndSkipped()
	{
		final List<MatchPlanLlmSuggester.RelationEvidenceReport.Offered> offered = Collections
			.singletonList(new MatchPlanLlmSuggester.RelationEvidenceReport.Offered("Adresse", "personneRef", 2, false));
		final List<MatchPlanLlmSuggester.RelationEvidenceReport.Skipped> skipped = Collections
			.singletonList(new MatchPlanLlmSuggester.RelationEvidenceReport.Skipped("Contrat", "profile_table_for_matching MCP call failed: timeout"));
		final String line = MatchPlanLlmSuggester.relationEvidenceLogLine(new MatchPlanLlmSuggester.RelationEvidenceReport("Personne", offered, skipped));
		MatchPlanLlmSuggesterRelationEvidenceTest.check("both offered and skipped shown: " + line,
			line.contains("Adresse") && line.contains("rowStats=not measured") && line.contains("Contrat") && line.contains("timeout"));
	}

	private static void relationEvidenceLogLine_neitherOfferedNorSkipped()
	{
		final String line = MatchPlanLlmSuggester.relationEvidenceLogLine(new MatchPlanLlmSuggester.RelationEvidenceReport("Personne",
			new ArrayList<MatchPlanLlmSuggester.RelationEvidenceReport.Offered>(), new ArrayList<MatchPlanLlmSuggester.RelationEvidenceReport.Skipped>()));
		MatchPlanLlmSuggesterRelationEvidenceTest.check("degrades to 'no child offered' with no exception: " + line, line.contains("no child offered"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
