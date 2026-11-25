package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws. Lot M4 (2026-10-18, docs/MATCHING.md section 59): is the business object bound, what each member holds, what must be written on it, and the
 * shape of the inert policies. The fixtures are the SHAPES of the live answers of 2026-10-18: project E2EMASTER20261017222251 (the object has no id although the main table has a
 * configuration) and project E2EMASTER20261017222813 (bound, id 12, member /root/AdresseClient listed with no policy and no default).
 */
public final class BusinessObjectMembersTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** get_business_object_config of /root/Personne, project ...222251: set_trusted_sources made the configuration, the object has no record. */
	private static final String STRAY = "{\"success\":true,\"data\":{\"businessObject\":{\"dataModel\":null,\"id\":null,\"mainTable\":\"/root/Personne\",\"published\":false},"
		+ "\"members\":[{\"matchingPolicies\":[],\"mergePolicies\":[],\"table\":\"/root/Personne\",\"tableSettings\":{\"defaultMatchingPolicy\":null}}]}}";

	/** ...222813: bound; the main table holds its policies, the member its (empty) configuration. */
	private static final String BOUND_MEMBER_EMPTY = "{\"success\":true,\"data\":{\"businessObject\":{\"id\":\"12\",\"mainTable\":\"/root/Client\",\"published\":true},"
		+ "\"members\":[{\"matchingPolicies\":[{\"processCode\":\"Client__preview\"},{\"processCode\":\"Client\"}],\"mergePolicies\":[{\"code\":\"Client__preview\"},{\"code\":\"Client\"}],"
		+ "\"table\":\"/root/Client\",\"tableSettings\":{\"defaultMatchingPolicy\":\"Client\"}},"
		+ "{\"matchingPolicies\":[],\"mergePolicies\":[],\"table\":\"/root/AdresseClient\",\"tableSettings\":{\"defaultMatchingPolicy\":null}}]}}";

	public static void main(final String[] pArgs) throws Exception
	{
		BusinessObjectMembersTest.bindingFromTheLiveAnswers();
		BusinessObjectMembersTest.strayRefusalSaysWhatToDo();
		BusinessObjectMembersTest.membersAreEveryTableButTheMain();
		BusinessObjectMembersTest.theMemberOfThePerson2813NeedsBothPolicies();
		BusinessObjectMembersTest.aMemberWithPoliciesIsNeverRewritten();
		BusinessObjectMembersTest.theDefaultMustNameARealPolicy();
		BusinessObjectMembersTest.theInertMatchingPolicyIsInertAndAccepted();
		BusinessObjectMembersTest.theInertMergePolicyIsIdentity();
		BusinessObjectMembersTest.theMemberMergeModeIsTheMainTables();
		BusinessObjectMembersTest.aMemberInAnotherModeIsAligned();
		BusinessObjectMembersTest.wording();
		System.out.println("BusinessObjectMembersTest OK");
	}

	/** Lot M8 (2026-10-18 02:57): get_business_object_config of /root/Client, project E2EMASTER20261018005227, as get_matching_config read both tables - Client ONLY_DUPLICATES, Adresse DISABLED. */
	private static final String LIVE_M8 = "{\"success\":true,\"data\":{\"businessObject\":{\"id\":\"16\",\"mainTable\":\"/root/Client\",\"published\":true},"
		+ "\"members\":[{\"table\":\"/root/Client\",\"matchingPolicies\":[{\"processCode\":\"Client\",\"mergePolicyCode\":\"Client\"}],"
		+ "\"mergePolicies\":[{\"code\":\"Client\",\"mode\":\"ONLY_DUPLICATES\",\"autoCreateNewGoldenSource\":\"MDM\"}],\"tableSettings\":{\"defaultMatchingPolicy\":\"Client\"}},"
		+ "{\"table\":\"/root/Adresse\",\"matchingPolicies\":[{\"processCode\":\"Adresse\",\"mergePolicyCode\":\"Adresse\"}],"
		+ "\"mergePolicies\":[{\"code\":\"Adresse\",\"mode\":\"DISABLED\",\"autoCreateNewGoldenSource\":null}],\"tableSettings\":{\"defaultMatchingPolicy\":\"Adresse\"}}]}}";

	private static void theMemberMergeModeIsTheMainTables() throws Exception
	{
		BusinessObjectMembersTest.check("the main table's mode is read off its own merge policy in the get_business_object_config answer (live: ONLY_DUPLICATES)",
			"ONLY_DUPLICATES".equals(BusinessObjectMembers.mainMergeMode(BusinessObjectMembersTest.json(LIVE_M8), "/root/Client")));
		BusinessObjectMembersTest.check("an answer whose merge policies carry no mode gives null, never a guess",
			BusinessObjectMembers.mainMergeMode(BusinessObjectMembersTest.json(BOUND_MEMBER_EMPTY), "/root/Client") == null);
		BusinessObjectMembersTest.check("no answer, no mode", BusinessObjectMembers.mainMergeMode(null, "/root/Client") == null);
		final String previewFirst = "{\"success\":true,\"data\":{\"businessObject\":{\"id\":\"3\"},\"members\":[{\"table\":\"/root/Client\",\"matchingPolicies\":[],"
			+ "\"mergePolicies\":[{\"code\":\"Client__preview\",\"mode\":\"DISABLED\"},{\"code\":\"Other\",\"mode\":\"DUPLICATES_AND_SINGLETONS\"}],\"tableSettings\":{}}]}}";
		BusinessObjectMembersTest.check("without a policy named after the entity, the first non-preview merge policy gives the mode",
			"DUPLICATES_AND_SINGLETONS".equals(BusinessObjectMembers.mainMergeMode(BusinessObjectMembersTest.json(previewFirst), "/root/Client")));
		BusinessObjectMembersTest.check("the mode the members get: the main table's first", "DISABLED".equals(BusinessObjectMembers.memberMergeMode("DISABLED", "ONLY_DUPLICATES")));
		BusinessObjectMembersTest.check("then the mode the configure writes on the main table", "DUPLICATES_AND_SINGLETONS".equals(BusinessObjectMembers.memberMergeMode(null, "DUPLICATES_AND_SINGLETONS")));
		BusinessObjectMembersTest.check("then the Factory's own default for a main table (MatchingPolicyTranslator)", "ONLY_DUPLICATES".equals(BusinessObjectMembers.memberMergeMode(" ", null))
			&& "ONLY_DUPLICATES".equals(BusinessObjectMembers.MERGE_MODE_DEFAULT));
		final ObjectNode merge = BusinessObjectMembers.inertMergePolicy("Adresse", BusinessObjectMembers.memberMergeMode("ONLY_DUPLICATES", null));
		BusinessObjectMembersTest.check("the inert merge policy written in the main table's mode keeps identity survivors, no constant, no golden source (the add-on merges singletons in place in"
			+ " ONLY_DUPLICATES on a business-object table: with these, a record is rewritten with its own values)", "ONLY_DUPLICATES".equals(merge.path("mode").asText())
			&& !merge.has("autoCreateNewGoldenSource") && !merge.toString().contains("constant") && merge.path("survivors").size() == 4);
	}

	private static void aMemberInAnotherModeIsAligned() throws Exception
	{
		final List<BusinessObjectMembers.Member> members = BusinessObjectMembers.membersOf(BusinessObjectMembersTest.json(LIVE_M8), "/root/Client");
		BusinessObjectMembersTest.check("the member's merge modes are read", members.size() == 1 && "DISABLED".equals(members.get(0).mergeModes.get("Adresse")));
		final BusinessObjectMembers.Writes writes = BusinessObjectMembers.writesFor(members.get(0), "ONLY_DUPLICATES");
		BusinessObjectMembersTest.check("the live member of 2026-10-18 02:57 needs ONE write: the mode of its merge policy Adresse aligned to ONLY_DUPLICATES - nothing else is rewritten",
			!writes.matching && !writes.merge && !writes.defaultPolicy && writes.alignMergeModes.equals(java.util.Collections.singletonList("Adresse")) && "ONLY_DUPLICATES".equals(writes.mergeMode)
				&& !writes.nothing());
		BusinessObjectMembersTest.check("it is a member to write", BusinessObjectMembers.membersToWrite(members, "ONLY_DUPLICATES").size() == 1);
		BusinessObjectMembersTest.check("in the main table's own mode the same member needs nothing (the re-read after the patch)",
			BusinessObjectMembers.writesFor(members.get(0), "DISABLED").nothing() && BusinessObjectMembers.membersToWrite(members, "DISABLED").isEmpty());
		final BusinessObjectMembers.Member unknownMode = new BusinessObjectMembers.Member("/root/Adresse", Arrays.asList("Adresse"), Arrays.asList("Adresse"), "Adresse");
		BusinessObjectMembersTest.check("a merge policy whose mode the answer does not give is never aligned (unknown is not different)",
			BusinessObjectMembers.writesFor(unknownMode, "ONLY_DUPLICATES").nothing());
		final String refusal = BusinessObjectMembers.stillMissingRefusal("/root/Client", members, "ONLY_DUPLICATES");
		BusinessObjectMembersTest.check("the 'still missing' refusal names the mode that stands and the one the main table has",
			refusal.contains("/root/Adresse") && refusal.contains("Adresse in mode DISABLED") && refusal.contains("/root/Client is in mode ONLY_DUPLICATES"), refusal);
	}

	private static JsonNode json(final String pText) throws Exception
	{
		return BusinessObjectMembersTest.MAPPER.readTree(pText);
	}

	private static void bindingFromTheLiveAnswers() throws Exception
	{
		BusinessObjectMembersTest.check("an object with an id is BOUND", BusinessObjectMembers.binding(BusinessObjectMembersTest.json(BOUND_MEMBER_EMPTY)) == BusinessObjectMembers.Binding.BOUND);
		BusinessObjectMembersTest.check("the object read with no id although the main table has a configuration is STRAY (project 222251)",
			BusinessObjectMembers.binding(BusinessObjectMembersTest.json(STRAY)) == BusinessObjectMembers.Binding.STRAY);
		BusinessObjectMembersTest.check("CONFIG_NOT_FOUND (declared, nothing configured) is CLEAN: the first generate binds it",
			BusinessObjectMembers.binding(BusinessObjectMembersTest.json("{\"success\":false,\"code\":\"CONFIG_NOT_FOUND\"}")) == BusinessObjectMembers.Binding.CLEAN);
		BusinessObjectMembersTest.check("TABLE_NOT_FOUND (a member has no dataset) is CLEAN too: the generate says what is missing",
			BusinessObjectMembers.binding(BusinessObjectMembersTest.json("{\"success\":false,\"code\":\"TABLE_NOT_FOUND\"}")) == BusinessObjectMembers.Binding.CLEAN);
		BusinessObjectMembersTest.check("no id and no member listed is CLEAN",
			BusinessObjectMembers.binding(BusinessObjectMembersTest.json("{\"success\":true,\"data\":{\"businessObject\":{\"id\":null},\"members\":[]}}")) == BusinessObjectMembers.Binding.CLEAN);
		BusinessObjectMembersTest.check("BUSINESS_OBJECT_NOT_FOUND is NOT_DECLARED",
			BusinessObjectMembers.binding(BusinessObjectMembersTest.json("{\"success\":false,\"code\":\"BUSINESS_OBJECT_NOT_FOUND\"}")) == BusinessObjectMembers.Binding.NOT_DECLARED);
		BusinessObjectMembersTest.check("no answer is NOT_DECLARED", BusinessObjectMembers.binding(null) == BusinessObjectMembers.Binding.NOT_DECLARED);
		BusinessObjectMembersTest.check("an empty id text is not an id",
			BusinessObjectMembers.binding(BusinessObjectMembersTest.json("{\"success\":true,\"data\":{\"businessObject\":{\"id\":\"\"},\"members\":[{\"table\":\"/root/X\"}]}}")) == BusinessObjectMembers.Binding.STRAY);
	}

	private static void strayRefusalSaysWhatToDo()
	{
		final String text = BusinessObjectMembers.strayRefusal("/root/Personne", "P_Master");
		BusinessObjectMembersTest.check("the stray refusal names the table, the cause and the way out", text.contains("/root/Personne") && text.contains("set_trusted_sources")
			&& text.contains("no tool") && text.contains("Administration > Match and Merge"), text);
	}

	private static void membersAreEveryTableButTheMain() throws Exception
	{
		final List<BusinessObjectMembers.Member> members = BusinessObjectMembers.membersOf(BusinessObjectMembersTest.json(BOUND_MEMBER_EMPTY), "/root/Client");
		BusinessObjectMembersTest.check("the main table is not a member", members.size() == 1 && "/root/AdresseClient".equals(members.get(0).table) && "AdresseClient".equals(members.get(0).entity()));
		BusinessObjectMembersTest.check("no answer, no member", BusinessObjectMembers.membersOf(null, "/root/Client").isEmpty());
		BusinessObjectMembersTest.check("the entity of a table path", "Adresse".equals(BusinessObjectMembers.entityOf("/root/Adresse")));
	}

	private static void theMemberOfThePerson2813NeedsBothPolicies() throws Exception
	{
		final List<BusinessObjectMembers.Member> members = BusinessObjectMembers.membersOf(BusinessObjectMembersTest.json(BOUND_MEMBER_EMPTY), "/root/Client");
		final BusinessObjectMembers.Writes writes = BusinessObjectMembers.writesFor(members.get(0), "ONLY_DUPLICATES");
		BusinessObjectMembersTest.check("an empty member needs an inert matching policy and an inert merge policy, no default to set apart, nothing to align",
			writes.matching && writes.merge && !writes.defaultPolicy && writes.alignMergeModes.isEmpty() && !writes.nothing());
		BusinessObjectMembersTest.check("it is the member to write", BusinessObjectMembers.membersToWrite(members, "ONLY_DUPLICATES").size() == 1);
	}

	private static void aMemberWithPoliciesIsNeverRewritten() throws Exception
	{
		final String complete = "{\"success\":true,\"data\":{\"businessObject\":{\"id\":\"3\"},\"members\":[{\"table\":\"/root/Personne\",\"matchingPolicies\":[{\"processCode\":\"Personne\"}],"
			+ "\"mergePolicies\":[{\"code\":\"Personne\"}],\"tableSettings\":{\"defaultMatchingPolicy\":\"Personne\"}},{\"table\":\"/root/Adresse\",\"matchingPolicies\":[{\"processCode\":\"Adresse\"}],"
			+ "\"mergePolicies\":[{\"code\":\"Adresse\"}],\"tableSettings\":{\"defaultMatchingPolicy\":\"Adresse\"}}]}}";
		BusinessObjectMembersTest.check("a member holding a default matching policy and a merge policy needs nothing",
			BusinessObjectMembers.membersToWrite(BusinessObjectMembers.membersOf(BusinessObjectMembersTest.json(complete), "/root/Personne"), "ONLY_DUPLICATES").isEmpty());
		final String mergeOnly = complete.replace("\"mergePolicies\":[{\"code\":\"Adresse\"}]", "\"mergePolicies\":[]");
		final BusinessObjectMembers.Writes writes = BusinessObjectMembers
			.writesFor(BusinessObjectMembers.membersOf(BusinessObjectMembersTest.json(mergeOnly), "/root/Personne").get(0), "ONLY_DUPLICATES");
		BusinessObjectMembersTest.check("a member with its own matching policy and no merge policy gets the merge policy only", !writes.matching && writes.merge && !writes.defaultPolicy);
	}

	private static void theDefaultMustNameARealPolicy() throws Exception
	{
		final String noDefault = "{\"success\":true,\"data\":{\"businessObject\":{\"id\":\"3\"},\"members\":[{\"table\":\"/root/Adresse\",\"matchingPolicies\":[{\"processCode\":\"Adresse__preview\"},"
			+ "{\"processCode\":\"Adresse\"}],\"mergePolicies\":[{\"code\":\"Adresse\"}],\"tableSettings\":{\"defaultMatchingPolicy\":null}}]}}";
		final BusinessObjectMembers.Member member = BusinessObjectMembers.membersOf(BusinessObjectMembersTest.json(noDefault), "/root/Personne").get(0);
		final BusinessObjectMembers.Writes writes = BusinessObjectMembers.writesFor(member, "ONLY_DUPLICATES");
		BusinessObjectMembersTest.check("policies but no default: only the default is set", !writes.matching && !writes.merge && writes.defaultPolicy);
		BusinessObjectMembersTest.check("the default candidate is the first policy that is not a preview", "Adresse".equals(BusinessObjectMembers.defaultCandidate(member)));
		final String dangling = noDefault.replace("\"defaultMatchingPolicy\":null", "\"defaultMatchingPolicy\":\"Gone\"");
		BusinessObjectMembersTest.check("a default that names no policy of the table does not count",
			BusinessObjectMembers.writesFor(BusinessObjectMembers.membersOf(BusinessObjectMembersTest.json(dangling), "/root/Personne").get(0), "ONLY_DUPLICATES").defaultPolicy);
	}

	private static void theInertMatchingPolicyIsInertAndAccepted()
	{
		final ObjectNode policy = BusinessObjectMembers.inertMatchingPolicy("AdresseClient");
		BusinessObjectMembersTest.check("the code is the entity name", "AdresseClient".equals(policy.path("processCode").asText()));
		BusinessObjectMembersTest.check("no execution mode is sent: a member refuses them (Vol1mR step 1433)", !policy.has("matchingOnCreation") && !policy.has("matchingOnUpdate"));
		BusinessObjectMembersTest.check("the comparison field is declared in the pre-clustering fields (generate refuses a tree field that is not)",
			policy.path("fields").size() == 1 && "/id".equals(policy.path("fields").get(0).path("field").asText()) && policy.path("fields").get(0).path("weight").asInt() >= 1);
		final JsonNode tree = policy.path("decisionTree");
		BusinessObjectMembersTest.check("one comparison on the technical id", tree.path("fields").size() == 1 && "/id".equals(tree.path("fields").get(0).path("field").asText())
			&& "ebx-addon-mame@exact".equals(tree.path("fields").get(0).path("algorithm").asText()) && "All fields match".equals(tree.path("function").asText()));
		BusinessObjectMembersTest.check("the true branch is SUSPECTED, never MATCHED (nothing can merge on its own)", "SUSPECTED".equals(tree.path("trueNode").path("name").asText()));
		BusinessObjectMembersTest.check("the false branch is NOT_MATCHED: the add-on refuses a node whose two branches reach the same leaf",
			"NOT_MATCHED".equals(tree.path("falseNode").path("name").asText()) && !tree.path("trueNode").path("name").asText().equals(tree.path("falseNode").path("name").asText()));
		BusinessObjectMembersTest.check("the leaves have a null function", tree.path("trueNode").path("function").isNull() && tree.path("falseNode").path("function").isNull());
		BusinessObjectMembersTest.check("null handling is the vocabulary the tree accepts (MATCH / NOT_MATCH, never DO_NOTHING)",
			"NOT_MATCH".equals(tree.path("fields").get(0).path("bothNullValueHandling").asText()) && "NOT_MATCH".equals(tree.path("fields").get(0).path("oneNullValueHandling").asText()));
		BusinessObjectMembersTest.check("the policy is a policy the Factory's own self-check can compare: no MATCHED leaf anywhere", !policy.toString().contains("\"MATCHED\""));
	}

	private static void theInertMergePolicyIsIdentity()
	{
		final ObjectNode merge = BusinessObjectMembers.inertMergePolicy("AdresseClient", "ONLY_DUPLICATES");
		BusinessObjectMembersTest.check("code, and the mode is the one given (the main table's, lot M8)", "AdresseClient".equals(merge.path("code").asText()) && "ONLY_DUPLICATES".equals(merge.path("mode").asText()));
		BusinessObjectMembersTest.check("any mode of the add-on is written as given", "DISABLED".equals(BusinessObjectMembers.inertMergePolicy("AdresseClient", "DISABLED").path("mode").asText()));
		BusinessObjectMembersTest.check("no golden source and no constant survivor (lot M1): nothing can rewrite a record", !merge.has("autoCreateNewGoldenSource") && !merge.toString().contains("constant"));
		BusinessObjectMembersTest.check("no trusted-source function: a member has no trusted-source ranking (TRUSTED_SOURCE_INCOMPLETE otherwise)", !merge.toString().contains("most trusted source")
			&& !merge.toString().contains("mostTrustedSource"));
		BusinessObjectMembersTest.check("record selection and default field chains are present (both required)", merge.path("recordSelectionFunctionChain").size() == 1
			&& merge.path("defaultFieldMergeFunctionChain").size() == 1);
		BusinessObjectMembersTest.check("the technical fields keep their values: no merge on the source fields, max on the date",
			merge.path("survivors").size() == 4 && "no merge".equals(merge.path("survivors").get(0).path("mergeFunctionChain").get(0).path("function").asText())
				&& "/sourceSystem".equals(merge.path("survivors").get(0).path("field").asText()) && "max".equals(merge.path("survivors").get(3).path("mergeFunctionChain").get(0).path("function").asText()));
		BusinessObjectMembersTest.check("it is the manual-merge policy of the table, as every Factory merge policy", merge.path("useForManualMerge").asBoolean());
	}

	private static void wording()
	{
		final BusinessObjectMembers.Writes both = BusinessObjectMembers.writesFor(BusinessObjectMembersTest.emptyMember(), "ONLY_DUPLICATES");
		final String sentence = BusinessObjectMembers.writtenSentence("Adresse", "Personne", both, null);
		BusinessObjectMembersTest.check("the sentence says what was written, where the mode comes from, why, and that the member never runs alone", sentence.contains("inert matching policy")
			&& sentence.contains("inert merge policy") && sentence.contains("mode ONLY_DUPLICATES, the mode of Personne's merge policy") && sentence.contains("never run on its own")
			&& sentence.contains("follows Personne") && !sentence.contains("set to"), sentence);
		final List<BusinessObjectMembers.Member> missing = Arrays.asList(BusinessObjectMembersTest.emptyMember());
		final String refusal = BusinessObjectMembers.stillMissingRefusal("/root/Personne", missing, "ONLY_DUPLICATES");
		BusinessObjectMembersTest.check("the 'still missing' refusal names the member and what lacks", refusal.contains("/root/Adresse") && refusal.contains("no matching policy") && refusal.contains("no merge policy")
			&& !refusal.contains("in mode"), refusal);
		// lot M8: the live member of 2026-10-18 02:57, aligned
		final java.util.Map<String, String> before = new java.util.LinkedHashMap<String, String>();
		before.put("Adresse", "DISABLED");
		final BusinessObjectMembers.Writes aligned = BusinessObjectMembers.writesFor(new BusinessObjectMembers.Member("/root/Adresse", Arrays.asList("Adresse"), Arrays.asList("Adresse"), "Adresse", before),
			"ONLY_DUPLICATES");
		final String alignedSentence = BusinessObjectMembers.writtenSentence("Adresse", "Client", aligned, before);
		BusinessObjectMembersTest.check("an aligned member says which policy left which mode for which, and why", alignedSentence.contains("merge policy Adresse (was DISABLED) set to ONLY_DUPLICATES")
			&& alignedSentence.contains("the mode of Client's merge policy") && alignedSentence.contains("another mode") && !alignedSentence.contains("inert merge policy"), alignedSentence);
	}

	private static BusinessObjectMembers.Member emptyMember()
	{
		return new BusinessObjectMembers.Member("/root/Adresse", Arrays.<String>asList(), Arrays.<String>asList(), null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		BusinessObjectMembersTest.check(pWhat, pCondition, "");
	}

	private static void check(final String pWhat, final boolean pCondition, final String pDetail)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat + (pDetail.isEmpty() ? "" : " | " + pDetail));
		}
	}
}
