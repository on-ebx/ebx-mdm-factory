package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;

/**
 * JUnit-free, like the sibling {@code master} tests: run the main, a failure throws.
 * <p>
 * Covers the LLM-answer side of the survivorship lot (`docs/MATCHING.md` &sect;15,
 * {@link MatchPlanLlmPass.Session#proposeMatchPlan}'s new {@code survivorship} argument): structural
 * validation (unknown field/function dropped with a named reason, a per-source-key/technical field
 * dropped, an empty chain or blank rationale dropped, none of it ever refusing the whole {@code
 * propose_match_plan} call the way an invalid tier does), the {@code trustedSourceList} override surviving
 * into {@code requirements}, a "low" confidence kept rather than dropped, the evidence entry {@link
 * MatchPlanEvidence.Field#confidence} carries, and {@link MatchPlanLlmPass#sameStructure}'s own new
 * survivorship comparison. {@link MatchPlanLlmPassTest} keeps covering everything about the mandatory
 * tiers/clustering contract; this file is additive, scoped to the optional survivorship object only.
 */
public final class MatchPlanSurvivorshipTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		MatchPlanSurvivorshipTest.unknownFieldDropped_planStillCreated();
		MatchPlanSurvivorshipTest.unknownFunctionDropped();
		MatchPlanSurvivorshipTest.sourceKeyFieldDropped();
		MatchPlanSurvivorshipTest.emptyChainDropped();
		MatchPlanSurvivorshipTest.blankRationaleDropped();
		MatchPlanSurvivorshipTest.lowConfidenceKeptAndFlagged();
		MatchPlanSurvivorshipTest.missingConfidenceDefaultsToMedium();
		MatchPlanSurvivorshipTest.trustedSourceListOverridePreserved();
		MatchPlanSurvivorshipTest.trustedSourceListLabelMappedToCode_run30Case();
		MatchPlanSurvivorshipTest.trustedSourceListUnknownEntryDropped();
		MatchPlanSurvivorshipTest.trustedSourceListNoConfiguredSources_allDropped();
		MatchPlanSurvivorshipTest.noSurvivorshipArgument_noKeyInRequirements();
		MatchPlanSurvivorshipTest.dateFieldPath_validDateAttribute_kept();
		MatchPlanSurvivorshipTest.dateFieldPath_technicalReceivedAt_accepted();
		MatchPlanSurvivorshipTest.dateFieldPath_notADateAttribute_droppedButFunctionKept();
		MatchPlanSurvivorshipTest.dateFieldPath_unknownField_droppedButFunctionKept();
		MatchPlanSurvivorshipTest.evidenceRecordsRoleAndConfidence();
		MatchPlanSurvivorshipTest.evidenceMergesWithAnExistingMatchingRole();
		MatchPlanSurvivorshipTest.sameStructure_survivorshipOnlyChange_isADifference();
		MatchPlanSurvivorshipTest.sameStructure_identicalSurvivorship_isNoDifference();
		MatchPlanSurvivorshipTest.sameStructure_bothWithoutSurvivorship_stillWorksAsBefore();
		System.out.println("MatchPlanSurvivorshipTest OK");
	}

	// -------------------------------------------------------------------------------------------------- fixtures

	private static MatchPlanLlmPass.AttributeInfo business(final String pName)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 80, null, Double.valueOf(.9), "EXACT", Integer.valueOf(50), "EXACT", null, null, null, null,
			true, null, Collections.<String> emptyList(), Collections.<String> emptyList());
	}

	/** Near-unique among its filled values (>= 95%, >= 30% filled) - a valid MATCH-terminal identifier, same numbers as {@code MatchPlanLlmPassTest#identifierAttribute}. */
	private static MatchPlanLlmPass.AttributeInfo identifier(final String pName)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 34, null, Double.valueOf(.95), "EXACT", Integer.valueOf(96), "EXACT", null, null, null, null,
			true, null, Collections.<String> emptyList(), Collections.<String> emptyList());
	}

	private static MatchPlanLlmPass.AttributeInfo sourceKey(final String pName)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 40, null, Double.valueOf(1.0), "EXACT", Integer.valueOf(100), "EXACT", null, null, null, null,
			true, MatchPlanLlmPass.AttributeInfo.ROLE_SOURCE_KEY, Collections.<String> emptyList(), Collections.<String> emptyList());
	}

	private static MatchPlanLlmPass.EntityInfo company()
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanSurvivorshipTest.identifier("vatNo"));
		attributes.add(MatchPlanSurvivorshipTest.business("city"));
		attributes.add(MatchPlanSurvivorshipTest.business("name"));
		attributes.add(MatchPlanSurvivorshipTest.sourceKey("supplierId"));
		return new MatchPlanLlmPass.EntityInfo("Company", "Company", 100, attributes);
	}

	private static MatchPlanLlmPass.AllowedVocabulary vocabulary()
	{
		return new MatchPlanLlmPass.AllowedVocabulary(Arrays.asList("ebx-addon-mame@exact", "ebx-addon-mame@fuzzyFullText"), Arrays.asList("NOT_MATCH", "DO_NOTHING"),
			Arrays.asList("mostCompleted", "mostTrustedSource"));
	}

	private static MatchPlanLlmPass.Session session()
	{
		return MatchPlanSurvivorshipTest.session(Collections.<MatchPlanLlmPass.TrustedSourceInfo> emptyList());
	}

	/** @param pTrustedSources the project's own trusted-source ranking - run-30 validation lot (2026-09-22): a {@code trustedSourceList} entry is only ever accepted when it names one of these. */
	private static MatchPlanLlmPass.Session session(final List<MatchPlanLlmPass.TrustedSourceInfo> pTrustedSources)
	{
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put("Company", MatchPlanSurvivorshipTest.company());
		return new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(),
			MatchPlanSurvivorshipTest.vocabulary(), null, null, null, pTrustedSources);
	}

	/** ERP/CRM, the project's declared trusted sources for every test below that needs one - {@code ERP}'s description mirrors run 30's real wizard label. */
	private static List<MatchPlanLlmPass.TrustedSourceInfo> erpAndCrmTrustedSources()
	{
		return Arrays.asList(new MatchPlanLlmPass.TrustedSourceInfo("ERP", 1, "ERP - Snowflake - analytics (demo)"),
			new MatchPlanLlmPass.TrustedSourceInfo("CRM", 2, "CRM system"));
	}

	/** A minimal but valid propose_match_plan call: one MATCH-terminal exact tier on vatNo, no clustering, plus whatever {@code pSurvivorshipJson} says (a bare {"fields":[...]} object, or omitted with null). */
	private static LocalTool.Result proposeWithSurvivorship(final MatchPlanLlmPass.Session pSession, final String pSurvivorshipJson)
	{
		final String args = "{\"entity\":\"Company\",\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\",\"fields\":["
			+ "{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],\"clusteringFields\":[],"
			+ (pSurvivorshipJson == null ? "" : "\"survivorship\":" + pSurvivorshipJson + ",") + "\"rationale\":\"vatNo is near-unique.\",\"confidence\":80}";
		return pSession.proposeMatchPlan(MatchPlanSurvivorshipTest.parse(args));
	}

	// ------------------------------------------------------------------------------------------------ validation

	private static void unknownFieldDropped_planStillCreated()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session,
			"{\"fields\":[{\"field\":\"ghost\",\"chain\":[{\"function\":\"longest\"}],\"rationale\":\"x\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("the whole plan is still accepted - an unknown survivorship field is never a reason to refuse", !r.isError);
		final JsonNode requirements = MatchPlanSurvivorshipTest.requirementsOf(session);
		MatchPlanSurvivorshipTest.check("no survivorship field was kept", requirements.path("survivorship").path("fields").size() == 0);
		MatchPlanSurvivorshipTest.check("the drop is named in the rationale", session.createdPlans().get(0).rationale.contains("not an accepted attribute"));
	}

	private static void unknownFunctionDropped()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session,
			"{\"fields\":[{\"field\":\"city\",\"chain\":[{\"function\":\"constant\",\"constantValue\":\"X\"}],\"rationale\":\"x\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		MatchPlanSurvivorshipTest.check("'constant' is a technical-only function, never legal on a business field: dropped",
			MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").size() == 0);
		MatchPlanSurvivorshipTest.check("the drop names the disallowed function", session.createdPlans().get(0).rationale.contains("unknown or disallowed merge function"));
	}

	private static void sourceKeyFieldDropped()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session,
			"{\"fields\":[{\"field\":\"supplierId\",\"chain\":[{\"function\":\"longest\"}],\"rationale\":\"x\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		MatchPlanSurvivorshipTest.check("a per-source-key field is never given a business survivorship rule",
			MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").size() == 0);
		MatchPlanSurvivorshipTest.check("the drop explains why (per-source record key)",
			session.createdPlans().get(0).rationale.contains("per-source record key"));
	}

	private static void emptyChainDropped()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session, "{\"fields\":[{\"field\":\"city\",\"chain\":[],\"rationale\":\"x\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		MatchPlanSurvivorshipTest.check("empty chain dropped", MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").size() == 0);
	}

	private static void blankRationaleDropped()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session,
			"{\"fields\":[{\"field\":\"city\",\"chain\":[{\"function\":\"longest\"}],\"rationale\":\"   \",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		MatchPlanSurvivorshipTest.check("blank rationale dropped", MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").size() == 0);
	}

	private static void lowConfidenceKeptAndFlagged()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session,
			"{\"fields\":[{\"field\":\"city\",\"chain\":[{\"function\":\"last update\",\"dateFieldPath\":\"/_receivedAt\"}],\"rationale\":\"newer on CRM\",\"confidence\":\"low\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		final JsonNode fields = MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields");
		MatchPlanSurvivorshipTest.check("a low-confidence rule is KEPT, not dropped", fields.size() == 1);
		MatchPlanSurvivorshipTest.check("confidence low is carried over", "low".equals(fields.get(0).path("confidence").asText()));
		MatchPlanSurvivorshipTest.check("dateFieldPath carried over", "/_receivedAt".equals(fields.get(0).path("chain").get(0).path("dateFieldPath").asText()));
	}

	private static void missingConfidenceDefaultsToMedium()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session,
			"{\"fields\":[{\"field\":\"city\",\"chain\":[{\"function\":\"longest\"}],\"rationale\":\"x\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		final JsonNode fields = MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields");
		MatchPlanSurvivorshipTest.check("kept, not dropped, for a missing confidence", fields.size() == 1);
		MatchPlanSurvivorshipTest.check("defaults to medium", "medium".equals(fields.get(0).path("confidence").asText()));
	}

	/** Both entries name real, declared codes of this project - kept verbatim, in order. */
	private static void trustedSourceListOverridePreserved()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session(MatchPlanSurvivorshipTest.erpAndCrmTrustedSources());
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session, "{\"fields\":[{\"field\":\"vatNo\","
			+ "\"chain\":[{\"function\":\"most trusted source\",\"trustedSourceList\":[\"ERP\",\"CRM\"]}],\"rationale\":\"ERP is the legal registry\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		final JsonNode chain = MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").get(0).path("chain").get(0);
		MatchPlanSurvivorshipTest.check("function carried over", "most trusted source".equals(chain.path("function").asText()));
		MatchPlanSurvivorshipTest.check("trustedSourceList carried over in order",
			"ERP".equals(chain.path("trustedSourceList").get(0).asText()) && "CRM".equals(chain.path("trustedSourceList").get(1).asText()));
	}

	/**
	 * The exact run-30 defect (2026-09-22, `docs/MATCHING.md` &sect;15, agent run 30): the LLM answered {@code
	 * trustedSourceList:["ERP - Snowflake - analytics (demo)"]} - the source's DESCRIPTION (the wizard's own
	 * label), not its {@code "ERP"} code. Mapped to the real code, with a note in the plan's own rationale so a
	 * reviewer can see the substitution happened - never silently forwarded toward {@code set_trusted_sources},
	 * which would have auto-created a phantom source.
	 */
	private static void trustedSourceListLabelMappedToCode_run30Case()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session(MatchPlanSurvivorshipTest.erpAndCrmTrustedSources());
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session, "{\"fields\":[{\"field\":\"vatNo\","
			+ "\"chain\":[{\"function\":\"most trusted source\",\"trustedSourceList\":[\"ERP - Snowflake - analytics (demo)\"]}],"
			+ "\"rationale\":\"ERP is the legal registry\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		final JsonNode chain = MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").get(0).path("chain").get(0);
		MatchPlanSurvivorshipTest.check("the label was mapped to the real code, not forwarded verbatim",
			chain.path("trustedSourceList").size() == 1 && "ERP".equals(chain.path("trustedSourceList").get(0).asText()));
		MatchPlanSurvivorshipTest.check("the mapping is named in the rationale, never silent",
			session.createdPlans().get(0).rationale.contains("LABEL") && session.createdPlans().get(0).rationale.contains("mapped to 'ERP'"));
	}

	/** An entry naming no known source of this project is dropped (UNKNOWN_SOURCE_CODE); a valid entry in the SAME list still survives. */
	private static void trustedSourceListUnknownEntryDropped()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session(MatchPlanSurvivorshipTest.erpAndCrmTrustedSources());
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session, "{\"fields\":[{\"field\":\"vatNo\","
			+ "\"chain\":[{\"function\":\"most trusted source\",\"trustedSourceList\":[\"ERP\",\"GHOST_SOURCE\"]}],\"rationale\":\"x\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		final JsonNode chain = MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").get(0).path("chain").get(0);
		MatchPlanSurvivorshipTest.check("ERP kept, GHOST_SOURCE dropped",
			chain.path("trustedSourceList").size() == 1 && "ERP".equals(chain.path("trustedSourceList").get(0).asText()));
		MatchPlanSurvivorshipTest.check("the drop is named UNKNOWN_SOURCE_CODE in the rationale",
			session.createdPlans().get(0).rationale.contains("GHOST_SOURCE") && session.createdPlans().get(0).rationale.contains("UNKNOWN_SOURCE_CODE"));
	}

	/** No trusted source is configured for this project at all - every entry is unknown, the whole override is dropped, the plain 'most trusted source' function still survives (never the whole rule). */
	private static void trustedSourceListNoConfiguredSources_allDropped()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session, "{\"fields\":[{\"field\":\"vatNo\","
			+ "\"chain\":[{\"function\":\"most trusted source\",\"trustedSourceList\":[\"ERP\",\"CRM\"]}],\"rationale\":\"x\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		final JsonNode chain = MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").get(0).path("chain").get(0);
		MatchPlanSurvivorshipTest.check("function survives", "most trusted source".equals(chain.path("function").asText()));
		MatchPlanSurvivorshipTest.check("no trustedSourceList at all - falls back to the table's own ranking", !chain.has("trustedSourceList"));
		MatchPlanSurvivorshipTest.check("the whole-override drop is named in the rationale",
			session.createdPlans().get(0).rationale.contains("dropped entirely"));
	}

	private static void noSurvivorshipArgument_noKeyInRequirements()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session, null);
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		MatchPlanSurvivorshipTest.check("no survivorship key at all when the model never sends one (field with no rule = policy default)",
			!MatchPlanSurvivorshipTest.requirementsOf(session).has("survivorship"));
	}

	// --------------------------------------------------------------------------- lot 1 (2026-09-23, sec 23): dateFieldPath

	private static MatchPlanLlmPass.AttributeInfo dateAttribute(final String pName)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "DATE", null, null, Double.valueOf(.9), "EXACT", Integer.valueOf(50), "EXACT", null, null, null, null,
			true, null, Collections.<String> emptyList(), Collections.<String> emptyList());
	}

	private static MatchPlanLlmPass.Session sessionWithDateAttribute()
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanSurvivorshipTest.identifier("vatNo"));
		attributes.add(MatchPlanSurvivorshipTest.business("city"));
		attributes.add(MatchPlanSurvivorshipTest.business("name"));
		attributes.add(MatchPlanSurvivorshipTest.sourceKey("supplierId"));
		attributes.add(MatchPlanSurvivorshipTest.dateAttribute("receivedDate"));
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put("Company", new MatchPlanLlmPass.EntityInfo("Company", "Company", 100, attributes));
		return new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(),
			MatchPlanSurvivorshipTest.vocabulary(), null, null, null, Collections.<MatchPlanLlmPass.TrustedSourceInfo> emptyList());
	}

	/** A {@code dateFieldPath} naming a real DATE attribute of the entity is kept. */
	private static void dateFieldPath_validDateAttribute_kept()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.sessionWithDateAttribute();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session, "{\"fields\":[{\"field\":\"city\","
			+ "\"chain\":[{\"function\":\"last update\",\"dateFieldPath\":\"/receivedDate\"}],\"rationale\":\"CRM more current\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		final JsonNode chain = MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").get(0).path("chain").get(0);
		MatchPlanSurvivorshipTest.check("function carried over", "last update".equals(chain.path("function").asText()));
		MatchPlanSurvivorshipTest.check("dateFieldPath kept, real DATE attribute", "/receivedDate".equals(chain.path("dateFieldPath").asText()));
	}

	/** The fixed technical field {@code /_receivedAt} is always accepted, even though it is never one of the entity's own accepted attributes. */
	private static void dateFieldPath_technicalReceivedAt_accepted()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session, "{\"fields\":[{\"field\":\"city\","
			+ "\"chain\":[{\"function\":\"max\",\"dateFieldPath\":\"/_receivedAt\"}],\"rationale\":\"freshest wins\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		final JsonNode chain = MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").get(0).path("chain").get(0);
		MatchPlanSurvivorshipTest.check("dateFieldPath kept", "/_receivedAt".equals(chain.path("dateFieldPath").asText()));
	}

	/** A {@code dateFieldPath} naming a real but non-date attribute (city, STRING) is dropped - the FUNCTION still survives, never the whole rule. */
	private static void dateFieldPath_notADateAttribute_droppedButFunctionKept()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session, "{\"fields\":[{\"field\":\"name\","
			+ "\"chain\":[{\"function\":\"last update\",\"dateFieldPath\":\"/city\"}],\"rationale\":\"x\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		final JsonNode chain = MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").get(0).path("chain").get(0);
		MatchPlanSurvivorshipTest.check("function still present", "last update".equals(chain.path("function").asText()));
		MatchPlanSurvivorshipTest.check("dateFieldPath dropped - city is STRING, not DATE/TIMESTAMP", !chain.has("dateFieldPath"));
		MatchPlanSurvivorshipTest.check("the drop is named in the rationale",
			session.createdPlans().get(0).rationale.contains("dateFieldPath") && session.createdPlans().get(0).rationale.contains("dropped"));
	}

	/** A {@code dateFieldPath} naming no attribute at all is dropped the same way as a non-date one. */
	private static void dateFieldPath_unknownField_droppedButFunctionKept()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final LocalTool.Result r = MatchPlanSurvivorshipTest.proposeWithSurvivorship(session, "{\"fields\":[{\"field\":\"name\","
			+ "\"chain\":[{\"function\":\"max\",\"dateFieldPath\":\"/ghostDate\"}],\"rationale\":\"x\",\"confidence\":\"high\"}]}");
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		final JsonNode chain = MatchPlanSurvivorshipTest.requirementsOf(session).path("survivorship").path("fields").get(0).path("chain").get(0);
		MatchPlanSurvivorshipTest.check("function still present", "max".equals(chain.path("function").asText()));
		MatchPlanSurvivorshipTest.check("dateFieldPath dropped - no such attribute", !chain.has("dateFieldPath"));
	}

	// -------------------------------------------------------------------------------------------------- evidence

	private static void evidenceRecordsRoleAndConfidence()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		MatchPlanSurvivorshipTest.proposeWithSurvivorship(session,
			"{\"fields\":[{\"field\":\"city\",\"chain\":[{\"function\":\"longest\"}],\"rationale\":\"CRM abbreviates\",\"confidence\":\"medium\"}]}");
		final JsonNode evidence = MatchPlanSurvivorshipTest.parse(session.createdPlans().get(0).evidenceJson);
		JsonNode cityEntry = null;
		for (final JsonNode entry : evidence)
		{
			if ("city".equals(entry.path("field").asText()))
			{
				cityEntry = entry;
			}
		}
		MatchPlanSurvivorshipTest.check("city has an evidence entry", cityEntry != null);
		MatchPlanSurvivorshipTest.check("role carries SURVIVORSHIP", cityEntry.path("role").asText().contains("SURVIVORSHIP"));
		MatchPlanSurvivorshipTest.check("confidence recorded", "medium".equals(cityEntry.path("confidence").asText()));
		MatchPlanSurvivorshipTest.check("note carries the rationale", cityEntry.path("note").asText().contains("CRM abbreviates"));
	}

	/** A field used as a plain clustering field AND given a survivorship rule ends up as ONE evidence entry with a combined role - never two entries for "city". */
	private static void evidenceMergesWithAnExistingMatchingRole()
	{
		final MatchPlanLlmPass.Session session = MatchPlanSurvivorshipTest.session();
		final String args = "{\"entity\":\"Company\",\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\",\"fields\":["
			+ "{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],\"clusteringFields\":[\"vatNo\"],"
			+ "\"survivorship\":{\"fields\":[{\"field\":\"vatNo\",\"chain\":[{\"function\":\"longest\"}],\"rationale\":\"x\",\"confidence\":\"high\"}]},"
			+ "\"rationale\":\"vatNo anchors and clusters.\",\"confidence\":80}";
		final LocalTool.Result r = session.proposeMatchPlan(MatchPlanSurvivorshipTest.parse(args));
		MatchPlanSurvivorshipTest.check("accepted", !r.isError);
		final JsonNode evidence = MatchPlanSurvivorshipTest.parse(session.createdPlans().get(0).evidenceJson);
		int vatNoEntries = 0;
		JsonNode vatNoEntry = null;
		for (final JsonNode entry : evidence)
		{
			if ("vatNo".equals(entry.path("field").asText()))
			{
				vatNoEntries++;
				vatNoEntry = entry;
			}
		}
		MatchPlanSurvivorshipTest.check("exactly one evidence entry for vatNo, not two", vatNoEntries == 1);
		MatchPlanSurvivorshipTest.check("its role is a combination, including SURVIVORSHIP", vatNoEntry.path("role").asText().contains("SURVIVORSHIP"));
	}

	// ---------------------------------------------------------------------------------------------- sameStructure

	private static void sameStructure_survivorshipOnlyChange_isADifference()
	{
		final String base = "{\"tiers\":[],\"clusteringFields\":[]}";
		final String withRule = "{\"tiers\":[],\"clusteringFields\":[],\"survivorship\":{\"fields\":[{\"field\":\"city\",\"chain\":[{\"function\":\"longest\"}],"
			+ "\"rationale\":\"x\",\"confidence\":\"high\"}]}}";
		MatchPlanSurvivorshipTest.check("a re-proposal that only adds a survivorship rule is a real, reportable change",
			!MatchPlanLlmPass.sameStructure(base, withRule));
	}

	/**
	 * Unlike the plan-wide {@code rationale} (always free prose, deliberately excluded from the tiers/
	 * clusteringFields comparison), a survivorship rule's OWN {@code rationale}/{@code confidence} are compared
	 * verbatim as part of the {@code survivorship} subtree - {@link MatchPlanLlmPass#sameStructure} does a plain
	 * structural {@code equals()} on it, the simplest rule that still satisfies the task brief ("a survivorship-
	 * only change is a real change") without inventing a second, field-scoped "ignore the prose" carve-out this
	 * lot's own brief never asked for. This test only changes the outer {@code mergePolicy} hint (deliberately
	 * ignored, same as before this lot) to prove the comparison still tolerates THAT kind of prose-adjacent
	 * difference.
	 */
	private static void sameStructure_identicalSurvivorship_isNoDifference()
	{
		final String a = "{\"tiers\":[],\"clusteringFields\":[],\"survivorship\":{\"fields\":[{\"field\":\"city\",\"chain\":[{\"function\":\"longest\"}],"
			+ "\"rationale\":\"x\",\"confidence\":\"high\"}]}}";
		final String b = "{\"tiers\":[],\"clusteringFields\":[],\"survivorship\":{\"fields\":[{\"field\":\"city\",\"chain\":[{\"function\":\"longest\"}],"
			+ "\"rationale\":\"x\",\"confidence\":\"high\"}]},\"mergePolicy\":{\"mode\":\"ONLY_DUPLICATES\"}}";
		MatchPlanSurvivorshipTest.check("identical survivorship (byte for byte) - only the ignored mergePolicy hint differs",
			MatchPlanLlmPass.sameStructure(a, b));
	}

	private static void sameStructure_bothWithoutSurvivorship_stillWorksAsBefore()
	{
		final String a = "{\"tiers\":[{\"outcome\":\"MATCH\"}],\"clusteringFields\":[\"x\"]}";
		final String b = "{\"tiers\":[{\"outcome\":\"MATCH\"}],\"clusteringFields\":[\"x\"],\"rationale\":\"different\"}";
		MatchPlanSurvivorshipTest.check("no survivorship key on either side - the pre-lot comparison keeps working unchanged", MatchPlanLlmPass.sameStructure(a, b));
	}

	// ------------------------------------------------------------------------------------------------------ helpers

	private static JsonNode requirementsOf(final MatchPlanLlmPass.Session pSession)
	{
		return MatchPlanSurvivorshipTest.parse(pSession.createdPlans().get(0).requirementsJson);
	}

	private static JsonNode parse(final String pJson)
	{
		try
		{
			return MatchPlanSurvivorshipTest.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
