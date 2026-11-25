package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MatchPlanEvidenceTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanEvidenceTest.toJsonProducesTheDocumentedShape();
		MatchPlanEvidenceTest.nullNumbersBecomeJsonNull();
		MatchPlanEvidenceTest.emptyListProducesAnEmptyArray();
		MatchPlanEvidenceTest.combineRoleAddsClusteringOnce();
		MatchPlanEvidenceTest.combineRoleLeavesOtherCombinationsAlone();
		MatchPlanEvidenceTest.validBySystem_writtenWhenPresent();
		MatchPlanEvidenceTest.validBySystem_omittedWhenAbsent();
		System.out.println("MatchPlanEvidenceTest OK");
	}

	/** Lot 1 (2026-09-23, quality-informed survivorship): {@link MatchPlanEvidence.Field#validBySystem} writes {@code {checked, valid}} per system when present. */
	private static void validBySystem_writtenWhenPresent() throws Exception
	{
		final Map<String, MatchPlanEvidence.Validity> validBySystem = new LinkedHashMap<String, MatchPlanEvidence.Validity>();
		validBySystem.put("ERP", new MatchPlanEvidence.Validity(52, 48));
		validBySystem.put("CRM", new MatchPlanEvidence.Validity(50, 10));
		final List<MatchPlanEvidence.Field> fields = new ArrayList<MatchPlanEvidence.Field>();
		fields.add(new MatchPlanEvidence.Field("vatNo", MatchPlanEvidence.ROLE_SURVIVORSHIP, null, null, null, MatchPlanEvidence.VERDICT_OK, "test", "high",
			validBySystem));
		final JsonNode entry = MatchPlanEvidenceTest.MAPPER.readTree(MatchPlanEvidence.toJson(fields)).get(0);
		MatchPlanEvidenceTest.check("validBySystem.ERP.checked", entry.path("validBySystem").path("ERP").path("checked").asLong() == 52L);
		MatchPlanEvidenceTest.check("validBySystem.ERP.valid", entry.path("validBySystem").path("ERP").path("valid").asLong() == 48L);
		MatchPlanEvidenceTest.check("validBySystem.CRM.checked", entry.path("validBySystem").path("CRM").path("checked").asLong() == 50L);
		MatchPlanEvidenceTest.check("validBySystem.CRM.valid", entry.path("validBySystem").path("CRM").path("valid").asLong() == 10L);
	}

	/** {@code validBySystem} is OMITTED entirely (never {@code {}}) when null - a field with no validity rule, or never built through the 9-arg constructor, must not gain a fabricated empty object. */
	private static void validBySystem_omittedWhenAbsent() throws Exception
	{
		final List<MatchPlanEvidence.Field> fields = new ArrayList<MatchPlanEvidence.Field>();
		fields.add(new MatchPlanEvidence.Field("city", MatchPlanEvidence.ROLE_ANCHOR_FUZZY, null, null, null, MatchPlanEvidence.VERDICT_NOT_MEASURED, ""));
		final JsonNode entry = MatchPlanEvidenceTest.MAPPER.readTree(MatchPlanEvidence.toJson(fields)).get(0);
		MatchPlanEvidenceTest.check("no validBySystem key at all", !entry.has("validBySystem"));
	}

	private static void toJsonProducesTheDocumentedShape() throws Exception
	{
		final Map<String, Long> filled = new LinkedHashMap<String, Long>();
		filled.put("ERP", Long.valueOf(55));
		filled.put("CRM", Long.valueOf(52));
		final List<MatchPlanEvidence.Field> fields = new ArrayList<MatchPlanEvidence.Field>();
		fields.add(new MatchPlanEvidence.Field("country", MatchPlanEvidence.combineRole(MatchPlanEvidence.ROLE_SUPPORTING_EXACT, true), Integer.valueOf(9),
			Integer.valueOf(13), filled, MatchPlanEvidence.VERDICT_OK, ""));

		final JsonNode array = MatchPlanEvidenceTest.MAPPER.readTree(MatchPlanEvidence.toJson(fields));
		MatchPlanEvidenceTest.check("one entry", array.size() == 1);
		final JsonNode entry = array.get(0);
		MatchPlanEvidenceTest.check("field", "country".equals(entry.path("field").asText()));
		MatchPlanEvidenceTest.check("role combines supporting-exact and clustering", "SUPPORTING_EXACT,CLUSTERING".equals(entry.path("role").asText()));
		MatchPlanEvidenceTest.check("sharedAcrossSystems", entry.path("sharedAcrossSystems").asInt() == 9);
		MatchPlanEvidenceTest.check("distinct", entry.path("distinct").asInt() == 13);
		MatchPlanEvidenceTest.check("filledBySystem.ERP", entry.path("filledBySystem").path("ERP").asLong() == 55L);
		MatchPlanEvidenceTest.check("filledBySystem.CRM", entry.path("filledBySystem").path("CRM").asLong() == 52L);
		MatchPlanEvidenceTest.check("verdict", "OK".equals(entry.path("verdict").asText()));
		MatchPlanEvidenceTest.check("note is an empty string, not absent", entry.has("note") && entry.path("note").asText().isEmpty());
	}

	private static void nullNumbersBecomeJsonNull() throws Exception
	{
		final List<MatchPlanEvidence.Field> fields = new ArrayList<MatchPlanEvidence.Field>();
		fields.add(new MatchPlanEvidence.Field("name", MatchPlanEvidence.ROLE_ANCHOR_FUZZY, null, null, null, MatchPlanEvidence.VERDICT_NOT_MEASURED,
			"Fuzzy comparison: the cross-system usability guard does not evaluate this algorithm."));
		final JsonNode entry = MatchPlanEvidenceTest.MAPPER.readTree(MatchPlanEvidence.toJson(fields)).get(0);
		MatchPlanEvidenceTest.check("sharedAcrossSystems is JSON null, not 0", entry.path("sharedAcrossSystems").isNull());
		MatchPlanEvidenceTest.check("distinct is JSON null, not 0", entry.path("distinct").isNull());
		MatchPlanEvidenceTest.check("filledBySystem is an empty object, never null", entry.path("filledBySystem").isObject() && entry.path("filledBySystem").size() == 0);
	}

	private static void emptyListProducesAnEmptyArray()
	{
		MatchPlanEvidenceTest.check("empty list -> []", "[]".equals(MatchPlanEvidence.toJson(new ArrayList<MatchPlanEvidence.Field>())));
	}

	private static void combineRoleAddsClusteringOnce()
	{
		final String once = MatchPlanEvidence.combineRole(MatchPlanEvidence.ROLE_SUPPORTING_EXACT, true);
		MatchPlanEvidenceTest.check("first combine adds clustering", "SUPPORTING_EXACT,CLUSTERING".equals(once));
		final String twice = MatchPlanEvidence.combineRole(once, true);
		MatchPlanEvidenceTest.check("re-combining does not duplicate the tag", "SUPPORTING_EXACT,CLUSTERING".equals(twice));
	}

	private static void combineRoleLeavesOtherCombinationsAlone()
	{
		MatchPlanEvidenceTest.check("no clustering requested: role unchanged",
			MatchPlanEvidence.ROLE_SUPPORTING_EXACT.equals(MatchPlanEvidence.combineRole(MatchPlanEvidence.ROLE_SUPPORTING_EXACT, false)));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
