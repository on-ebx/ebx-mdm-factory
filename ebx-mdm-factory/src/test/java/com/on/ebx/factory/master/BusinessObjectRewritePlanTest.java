package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like every other test of this module: run the main, a failure throws.
 * <p>
 * Lot W13b (2026-10-12, {@code docs/MATCHING.md} sect;33.13), the two findings of Vol1mR (steps 1433, 1435, 1436) as pure functions:
 * a business-object MEMBER is sent without {@code matchingOnCreation}/{@code matchingOnUpdate} ({@link BusinessObjectPlan#isMember},
 * {@link BusinessObjectPlan#dropExecutionModesOfMember}, the step message sentence), and a stored policy is rewritten in place:
 * {@link BusinessObjectRewritePlan} (generate what is missing, patch what differs) over {@link MatchingPolicyPatchPlanner} (the exact operation for each kind of
 * difference) and {@link MergeConfigPatchPlanner}. The stored fixtures are the shapes read live from Vol1mR's {@code Adresse} and {@code Personne}.
 */
public final class BusinessObjectRewritePlanTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		BusinessObjectRewritePlanTest.member_isABusinessObjectTableThatIsNotTheMain();
		BusinessObjectRewritePlanTest.member_policyIsSentWithoutExecutionModes_mainKeepsThem();
		BusinessObjectRewritePlanTest.triggerModeSentence_membersFollowTheirObject();
		BusinessObjectRewritePlanTest.relatedTablesAreNotComparedOnABusinessObjectTable();
		BusinessObjectRewritePlanTest.personne_relatedTablesOnlyDifference_isNoLongerARewrite();
		BusinessObjectRewritePlanTest.adresse_matchingStoredAndSame_mergeMissing_generatesTheMergePolicyAndLinks();
		BusinessObjectRewritePlanTest.identical_afterTheReadBackDifferences_writesNothing();
		BusinessObjectRewritePlanTest.noStoredPolicy_generatesBothAndLinks();
		BusinessObjectRewritePlanTest.fieldLevelDifferences_deriveTheExactOps();
		BusinessObjectRewritePlanTest.policyFieldAddedAndRemoved();
		BusinessObjectRewritePlanTest.nodeLevelDifferences_thresholdFunctionAndParams();
		BusinessObjectRewritePlanTest.structuralDifferences_areRefusedNotPatched();
		BusinessObjectRewritePlanTest.mergePolicyDifferences_useTheChainPlanner();
		BusinessObjectRewritePlanTest.treeFieldDoNothing_readsNotMatch();
		System.out.println("BusinessObjectRewritePlanTest OK");
	}

	// ------------------------------------------------------------------------------------------------- fixtures

	private static JsonNode json(final String pText) throws Exception
	{
		return BusinessObjectRewritePlanTest.MAPPER.readTree(pText.replace('\'', '"'));
	}

	private static ObjectNode object(final String pText) throws Exception
	{
		return (ObjectNode) BusinessObjectRewritePlanTest.json(pText);
	}

	/** The Adresse policy as the add-on stores it (read live on Vol1mR): display leaf names, added {@code jaroWinklerThreshold}, null tree fields DO_NOTHING-free. */
	private static final String STORED_ADRESSE = "{'processCode':'Adresse','active':true,'matchingOnCreation':0,'matchingOnUpdate':0,'mergePolicyCode':null,"
		+ "'fields':[{'field':'/street','businessObjectField':null,'searchStrategy':'@default','weight':1,'bothNullValueHandling':'DO_NOTHING','oneNullValueHandling':'DO_NOTHING','useRelationships':'NO'},"
		+ "{'field':'/personneRef','businessObjectField':null,'searchStrategy':'@default','weight':1,'bothNullValueHandling':'DO_NOTHING','oneNullValueHandling':'DO_NOTHING','useRelationships':'NO'}],"
		+ "'decisionTree':{'name':'Same street','function':'All fields match','params':[],"
		+ "'fields':[{'field':'/street','algorithm':'ebx-addon-mame@fuzzyFullText','bothNullValueHandling':'NOT_MATCH','oneNullValueHandling':'NOT_MATCH','params':[{'name':'minimumScore','value':'90'}]}],"
		+ "'trueNode':{'name':'Same personneRef','function':'Average','params':[{'name':'minimumScore','value':'82'}],"
		+ "'fields':[{'field':'/personneRef','algorithm':'ebx-addon-mame@jarowinkler','bothNullValueHandling':'NOT_MATCH','oneNullValueHandling':'NOT_MATCH','params':[{'name':'weight','value':'4'},{'name':'jaroWinklerThreshold','value':'0.7'}]}],"
		+ "'trueNode':{'name':'Suspect','function':null,'fields':[],'params':[],'trueNode':null,'falseNode':null},"
		+ "'falseNode':{'name':'Not Match','function':null,'fields':[],'params':[],'trueNode':null,'falseNode':null}},"
		+ "'falseNode':{'name':'Not Match','function':null,'fields':[],'params':[],'trueNode':null,'falseNode':null}}}";

	/** What the translator builds for the same plan: token leaf names, no jaroWinklerThreshold, exec modes absent (a member). */
	private static final String DESIRED_ADRESSE = "{'processCode':'Adresse','active':true,"
		+ "'fields':[{'field':'/street','searchStrategy':'@default','weight':1,'bothNullValueHandling':'DO_NOTHING','oneNullValueHandling':'DO_NOTHING'},"
		+ "{'field':'/personneRef','searchStrategy':'@default','weight':1,'bothNullValueHandling':'DO_NOTHING','oneNullValueHandling':'DO_NOTHING'}],"
		+ "'decisionTree':{'name':'Same street','function':'All fields match','params':[],"
		+ "'fields':[{'field':'/street','algorithm':'ebx-addon-mame@fuzzyFullText','bothNullValueHandling':'NOT_MATCH','oneNullValueHandling':'NOT_MATCH','params':[{'name':'minimumScore','value':'90'}]}],"
		+ "'trueNode':{'name':'Same personneRef','function':'Average','params':[{'name':'minimumScore','value':'82'}],"
		+ "'fields':[{'field':'/personneRef','algorithm':'ebx-addon-mame@jarowinkler','bothNullValueHandling':'NOT_MATCH','oneNullValueHandling':'NOT_MATCH','params':[{'name':'weight','value':'4'}]}],"
		+ "'trueNode':{'name':'SUSPECTED','function':null},'falseNode':{'name':'NOT_MATCHED','function':null}},"
		+ "'falseNode':{'name':'NOT_MATCHED','function':null}}}";

	private static final String DESIRED_MERGE = "{'code':'Adresse','mode':'ONLY_DUPLICATES',"
		+ "'recordSelectionFunctionChain':[{'function':'mame.addon.reference.data.recordSelectionPolicy.mostTrustedSource.label'}],"
		+ "'defaultFieldMergeFunctionChain':[{'function':'most trusted source'},{'function':'longest'}],'survivors':[]}";

	private static final String STORED_MERGE = "{'code':'Adresse','mode':'ONLY_DUPLICATES','relatedTables':[],"
		+ "'recordSelectionFunctionChain':[{'function':'mame.addon.reference.data.recordSelectionPolicy.mostTrustedSource.label','constantValue':null,'dateFieldPath':null}],"
		+ "'defaultFieldMergeFunctionChain':[{'function':'most trusted source','constantValue':null,'dateFieldPath':null},{'function':'longest','constantValue':null,'dateFieldPath':null}],'survivors':[]}";

	private static ArrayNode policies(final String... pPolicies) throws Exception
	{
		final ArrayNode array = MAPPER.createArrayNode();
		for (final String policy : pPolicies)
		{
			array.add(BusinessObjectRewritePlanTest.json(policy));
		}
		return array;
	}

	private static BusinessObjectRewritePlan plan(final String pStoredMatching, final String pStoredMerge, final String pDesiredMatching, final String pDesiredMerge)
		throws Exception
	{
		return BusinessObjectRewritePlan.build("Adresse", pStoredMatching == null ? policies() : policies(pStoredMatching), pStoredMerge == null ? policies() : policies(pStoredMerge),
			BusinessObjectRewritePlanTest.object(pDesiredMatching), BusinessObjectRewritePlanTest.object(pDesiredMerge));
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static JsonNode op(final ArrayNode pOps, final int pIndex)
	{
		return pOps.get(pIndex);
	}

	// ------------------------------------------------------------------------------------------------- finding 1

	private static void member_isABusinessObjectTableThatIsNotTheMain()
	{
		check(BusinessObjectPlan.isMember("/root/Personne", "/root/Adresse"), "Adresse is a member of the object /root/Personne");
		check(!BusinessObjectPlan.isMember("/root/Personne", "/root/Personne"), "the main table is not a member");
		check(!BusinessObjectPlan.isMember(null, "/root/Adresse"), "no business object, no member");
	}

	private static void member_policyIsSentWithoutExecutionModes_mainKeepsThem() throws Exception
	{
		final ObjectNode member = BusinessObjectRewritePlanTest.object("{'processCode':'Adresse','matchingOnCreation':1,'matchingOnUpdate':1,'active':true}");
		check(BusinessObjectPlan.dropExecutionModesOfMember(member, true), "something was removed");
		check(!member.has("matchingOnCreation") && !member.has("matchingOnUpdate"), "both execution modes gone");
		check(member.has("processCode") && member.has("active"), "the rest of the policy is untouched");
		check(!BusinessObjectPlan.dropExecutionModesOfMember(member, true), "a second call removes nothing");
		final ObjectNode main = BusinessObjectRewritePlanTest.object("{'processCode':'Personne','matchingOnCreation':1,'matchingOnUpdate':1}");
		check(!BusinessObjectPlan.dropExecutionModesOfMember(main, false), "the main table keeps its execution modes");
		check(main.has("matchingOnCreation") && main.has("matchingOnUpdate"), "main table still carries both");
		check("business object member".equals(BusinessObjectPlan.executionModeSkippedOfMember().path("skipped").asText()), "executionMode detail of a member");
	}

	private static void triggerModeSentence_membersFollowTheirObject()
	{
		final String member = BusinessObjectPlan.triggerModeSentence("MANUAL", 0, true, "/root/Personne");
		check(member.contains("follows its business object /root/Personne") && !member.contains("execution mode 0"), "member sentence: " + member);
		check("trigger mode REALTIME: execution mode 1".equals(BusinessObjectPlan.triggerModeSentence("REALTIME", 1, false, "/root/Personne")), "main/plain sentence unchanged");
	}

	private static void relatedTablesAreNotComparedOnABusinessObjectTable() throws Exception
	{
		final Set<String> children = new LinkedHashSet<String>(Arrays.asList("/root/Adresse"));
		check(BusinessObjectPlan.comparisonChildTablePaths(true, children).isEmpty(), "business-object table: no related-table alignment compared");
		check(BusinessObjectPlan.comparisonChildTablePaths(false, children).equals(children), "ordinary table: unchanged");
	}

	// ------------------------------------------------------------------------------------------------- finding 2

	private static void personne_relatedTablesOnlyDifference_isNoLongerARewrite() throws Exception
	{
		// Vol1mR step 1435: "differs: merge policy related tables (alignForeignKeys)" and nothing else
		final JsonNode stored = BusinessObjectRewritePlanTest.json(STORED_ADRESSE);
		final ObjectNode desired = BusinessObjectRewritePlanTest.object(DESIRED_ADRESSE);
		final ObjectNode storedWithLink = (ObjectNode) stored.deepCopy();
		storedWithLink.put("mergePolicyCode", "Adresse");
		final JsonNode storedMerge = BusinessObjectRewritePlanTest.json(STORED_MERGE.replace("'relatedTables':[]", "'relatedTables':[{'path':'/root/Adresse','name':'Adresse','alignForeignKeys':'MANUAL'}]"));
		final ObjectNode desiredMerge = BusinessObjectRewritePlanTest.object(DESIRED_MERGE);
		final Set<String> children = new LinkedHashSet<String>(Arrays.asList("/root/Adresse"));
		check(!MatchingPolicyComparator.compare(storedWithLink, desired, "Adresse", storedMerge, desiredMerge, children).same(), "with the alignment compared, MANUAL reads as a difference");
		check(MatchingPolicyComparator.compare(storedWithLink, desired, "Adresse", storedMerge, desiredMerge, BusinessObjectPlan.comparisonChildTablePaths(true, children)).same(),
			"on a business-object table the stored policy is the plan's");
	}

	private static void adresse_matchingStoredAndSame_mergeMissing_generatesTheMergePolicyAndLinks() throws Exception
	{
		// Vol1mR step 1436: matching policy stored (identical), mergePolicies = [] and mergePolicyCode null
		final BusinessObjectRewritePlan plan = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE, null, DESIRED_ADRESSE, DESIRED_MERGE);
		check(!plan.refuses(), "no structural difference");
		check(plan.matching == BusinessObjectRewritePlan.Action.NONE, "the matching policy is the plan's: " + plan.matchingOperations);
		check(plan.merge == BusinessObjectRewritePlan.Action.GENERATE, "the merge policy does not exist: generate_merge_policy");
		check(plan.linkNeeded, "the stored policy links to no merge policy");
		final ArrayNode ops = plan.patchOperations();
		check(ops.size() == 1, "one op: the link: " + ops);
		check("set_merge_policy_link".equals(op(ops, 0).path("op").asText()) && "Adresse".equals(op(ops, 0).path("target").path("policyCode").asText())
			&& "Adresse".equals(op(ops, 0).path("value").asText()), "link op: " + op(ops, 0));
		check(!plan.identical(), "not identical");
	}

	private static void identical_afterTheReadBackDifferences_writesNothing() throws Exception
	{
		final String storedLinked = STORED_ADRESSE.replace("'mergePolicyCode':null", "'mergePolicyCode':'Adresse'");
		final BusinessObjectRewritePlan plan = BusinessObjectRewritePlanTest.plan(storedLinked, STORED_MERGE, DESIRED_ADRESSE, DESIRED_MERGE);
		check(plan.identical(), "jaroWinklerThreshold, display leaf names, null date paths, exec modes 0 are read-back differences: " + plan.patchOperations());
		check(plan.patchOperations().size() == 0, "no operation");
	}

	private static void noStoredPolicy_generatesBothAndLinks() throws Exception
	{
		final BusinessObjectRewritePlan plan = BusinessObjectRewritePlanTest.plan(null, null, DESIRED_ADRESSE, DESIRED_MERGE);
		check(plan.matching == BusinessObjectRewritePlan.Action.GENERATE && plan.merge == BusinessObjectRewritePlan.Action.GENERATE && plan.linkNeeded, "generate both, then link");
		check(plan.refuses() == false, "a missing stored tree is not a refusal when there is no stored policy at all");
	}

	private static void fieldLevelDifferences_deriveTheExactOps() throws Exception
	{
		// one tree field: algorithm, minimumScore and null handling changed; one policy field: weight, strategy, null handling
		final String desired = DESIRED_ADRESSE
			.replace("'algorithm':'ebx-addon-mame@fuzzyFullText','bothNullValueHandling':'NOT_MATCH','oneNullValueHandling':'NOT_MATCH','params':[{'name':'minimumScore','value':'90'}]",
				"'algorithm':'ebx-addon-mame@exact','bothNullValueHandling':'MATCH','oneNullValueHandling':'NOT_MATCH','params':[{'name':'minimumScore','value':'100'}]")
			.replace("{'field':'/street','searchStrategy':'@default','weight':1,'bothNullValueHandling':'DO_NOTHING','oneNullValueHandling':'DO_NOTHING'}",
				"{'field':'/street','searchStrategy':'@Levenshtein','weight':5,'bothNullValueHandling':'NOT_MATCH','oneNullValueHandling':'DO_NOTHING'}")
			.replace("{'name':'weight','value':'4'}", "{'name':'weight','value':'7'},{'name':'jaroWinklerThreshold','value':'0.9'}");
		final BusinessObjectRewritePlan plan = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE, null, desired, DESIRED_MERGE);
		check(!plan.refuses(), "in-place");
		final ArrayNode ops = plan.matchingOperations;
		final String text = ops.toString();
		// policy fields first
		check(ops.size() == 8, "8 ops, got " + ops.size() + ": " + text);
		check(op(ops, 0).toString().equals("{\"op\":\"set_field_weight\",\"target\":{\"policyCode\":\"Adresse\",\"field\":\"/street\"},\"value\":\"5\"}"), op(ops, 0).toString());
		check(op(ops, 1).toString().equals("{\"op\":\"set_field_preclustering\",\"target\":{\"policyCode\":\"Adresse\",\"field\":\"/street\"},\"value\":\"@Levenshtein\"}"), op(ops, 1).toString());
		check(op(ops, 2).toString().equals(
			"{\"op\":\"set_field_null_handling\",\"target\":{\"policyCode\":\"Adresse\",\"field\":\"/street\",\"paramName\":\"bothNullValueHandling\"},\"value\":\"NOT_MATCH\"}"), op(ops, 2).toString());
		// tree, root node: algorithm before its params
		check(op(ops, 3).toString().equals(
			"{\"op\":\"set_algorithm\",\"target\":{\"policyCode\":\"Adresse\",\"decisionTreeNode\":\"Same street\",\"field\":\"/street\"},\"value\":\"ebx-addon-mame@exact\"}"), op(ops, 3).toString());
		check(op(ops, 4).toString().equals("{\"op\":\"set_field_param\",\"target\":{\"policyCode\":\"Adresse\",\"decisionTreeNode\":\"Same street\",\"field\":\"/street\","
			+ "\"paramName\":\"bothNullValueHandling\"},\"value\":\"MATCH\"}"), op(ops, 4).toString());
		check(op(ops, 5).toString().equals("{\"op\":\"set_field_param\",\"target\":{\"policyCode\":\"Adresse\",\"decisionTreeNode\":\"Same street\",\"field\":\"/street\","
			+ "\"paramName\":\"minimumScore\"},\"value\":\"100\"}"), op(ops, 5).toString());
		check(op(ops, 6).toString().equals("{\"op\":\"set_field_param\",\"target\":{\"policyCode\":\"Adresse\",\"decisionTreeNode\":\"Same personneRef\",\"field\":\"/personneRef\","
			+ "\"paramName\":\"weight\"},\"value\":\"7\"}"), op(ops, 6).toString());
		check("jaroWinklerThreshold".equals(op(ops, 7).path("target").path("paramName").asText()) && "0.9".equals(op(ops, 7).path("value").asText()), op(ops, 7).toString());
	}

	private static void policyFieldAddedAndRemoved() throws Exception
	{
		final String desired = DESIRED_ADRESSE.replace("{'field':'/street','searchStrategy':'@default'", "{'field':'/city','searchStrategy':'@NGram','weight':3,'bothNullValueHandling':'DO_NOTHING','oneNullValueHandling':'DO_NOTHING'},"
			+ "{'field':'/street','searchStrategy':'@default'");
		final BusinessObjectRewritePlan added = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE, null, desired, DESIRED_MERGE);
		final ArrayNode addOps = added.matchingOperations;
		check(addOps.size() == 3, "add + weight + strategy: " + addOps);
		check(op(addOps, 0).toString().equals("{\"op\":\"add_matching_field\",\"target\":{\"policyCode\":\"Adresse\",\"field\":\"/city\"}}"), op(addOps, 0).toString());
		check("set_field_weight".equals(op(addOps, 1).path("op").asText()) && "3".equals(op(addOps, 1).path("value").asText()), op(addOps, 1).toString());
		check("set_field_preclustering".equals(op(addOps, 2).path("op").asText()) && "@NGram".equals(op(addOps, 2).path("value").asText()), op(addOps, 2).toString());

		final String desiredWithoutReference = DESIRED_ADRESSE.replace(",{'field':'/personneRef','searchStrategy':'@default','weight':1,'bothNullValueHandling':'DO_NOTHING','oneNullValueHandling':'DO_NOTHING'}", "");
		final BusinessObjectRewritePlan removed = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE, null, desiredWithoutReference, DESIRED_MERGE);
		check(removed.matchingOperations.size() == 1
			&& removed.matchingOperations.get(0).toString().equals("{\"op\":\"remove_matching_field\",\"target\":{\"policyCode\":\"Adresse\",\"field\":\"/personneRef\"}}"), removed.matchingOperations.toString());

		// a native relation field is addressed by businessObjectField
		final String relation = DESIRED_ADRESSE.replace("'fields':[{'field':'/street','searchStrategy':'@default'", "'fields':[{'businessObjectField':'/root/Adresse/city','searchStrategy':'@default','weight':0,'bothNullValueHandling':'DO_NOTHING','oneNullValueHandling':'DO_NOTHING'},"
			+ "{'field':'/street','searchStrategy':'@default'");
		final BusinessObjectRewritePlan relationPlan = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE, null, relation, DESIRED_MERGE);
		check(relationPlan.matchingOperations.get(0).toString().equals(
			"{\"op\":\"add_matching_field\",\"target\":{\"policyCode\":\"Adresse\",\"businessObjectField\":\"/root/Adresse/city\"}}"), relationPlan.matchingOperations.toString());
		check("set_field_weight".equals(relationPlan.matchingOperations.get(1).path("op").asText()) && "0".equals(relationPlan.matchingOperations.get(1).path("value").asText()),
			"weight 0 is not the default 1: " + relationPlan.matchingOperations);
	}

	private static void nodeLevelDifferences_thresholdFunctionAndParams() throws Exception
	{
		// minimumScore alone: set_threshold
		final BusinessObjectRewritePlan threshold = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE, null,
			DESIRED_ADRESSE.replace("{'name':'minimumScore','value':'82'}", "{'name':'minimumScore','value':'75'}"), DESIRED_MERGE);
		check(threshold.matchingOperations.size() == 1 && threshold.matchingOperations.get(0).toString().equals(
			"{\"op\":\"set_threshold\",\"target\":{\"policyCode\":\"Adresse\",\"decisionTreeNode\":\"Same personneRef\"},\"value\":\"75\"}"), threshold.matchingOperations.toString());
		// a different function (and its node params): field operations first, then set_decision_node_function with the node payload
		final BusinessObjectRewritePlan function = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE, null, DESIRED_ADRESSE
			.replace("'function':'Average','params':[{'name':'minimumScore','value':'82'}]", "'function':'At least N fields match','params':[{'name':'minimumNumberOfMatches','value':'1'}]")
			.replace("{'name':'weight','value':'4'}", "{'name':'minimumScore','value':'80'}"), DESIRED_MERGE);
		final ArrayNode ops = function.matchingOperations;
		check(ops.size() == 2, "field param then function: " + ops);
		check("set_field_param".equals(op(ops, 0).path("op").asText()) && "minimumScore".equals(op(ops, 0).path("target").path("paramName").asText()), op(ops, 0).toString());
		check(op(ops, 1).toString().equals("{\"op\":\"set_decision_node_function\",\"target\":{\"policyCode\":\"Adresse\",\"decisionTreeNode\":\"Same personneRef\"},"
			+ "\"value\":\"At least N fields match\",\"node\":{\"params\":[{\"name\":\"minimumNumberOfMatches\",\"value\":\"1\"}]}}"), op(ops, 1).toString());
		// another node-level param with the same function: set_decision_node_function too (set_threshold only carries minimumScore)
		final BusinessObjectRewritePlan sameFunction = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE, null,
			DESIRED_ADRESSE.replace("[{'name':'minimumScore','value':'82'}]", "[{'name':'minimumScore','value':'82'},{'name':'minimumNumberOfMatches','value':'2'}]"), DESIRED_MERGE);
		check(sameFunction.matchingOperations.size() == 1 && "set_decision_node_function".equals(sameFunction.matchingOperations.get(0).path("op").asText())
			&& "Average".equals(sameFunction.matchingOperations.get(0).path("value").asText()), sameFunction.matchingOperations.toString());
	}

	private static void structuralDifferences_areRefusedNotPatched() throws Exception
	{
		// a leaf outcome changed
		final BusinessObjectRewritePlan outcome = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE, null, DESIRED_ADRESSE.replace("'name':'SUSPECTED'", "'name':'MATCHED'"), DESIRED_MERGE);
		check(outcome.refuses() && outcome.describeStructural().contains("outcome SUSPECTED in the stored policy, MATCHED in the plan"), outcome.describeStructural());
		// a node removed (the comparison is replaced by a leaf)
		final BusinessObjectRewritePlan removed = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE, null, "{'processCode':'Adresse','fields':[],'decisionTree':{'name':'NOT_MATCHED','function':null}}",
			DESIRED_MERGE);
		check(removed.refuses() && removed.describeStructural().contains("root: a comparison in the stored policy, an outcome in the plan"), removed.describeStructural());
		// a node with another set of compared fields
		final BusinessObjectRewritePlan fields = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE, null,
			DESIRED_ADRESSE.replace("'fields':[{'field':'/personneRef','algorithm'", "'fields':[{'field':'/city','algorithm':'ebx-addon-mame@exact','params':[]},{'field':'/personneRef','algorithm'"), DESIRED_MERGE);
		check(fields.refuses() && fields.describeStructural().contains("node Same personneRef: compared fields differ (only in the plan: /city)"), fields.describeStructural());
		check(!fields.identical(), "a refusal is never identical");
	}

	private static void mergePolicyDifferences_useTheChainPlanner() throws Exception
	{
		final String storedLinked = STORED_ADRESSE.replace("'mergePolicyCode':null", "'mergePolicyCode':'Adresse'");
		final String desiredMerge = DESIRED_MERGE.replace("{'function':'longest'}", "{'function':'max'}");
		final BusinessObjectRewritePlan plan = BusinessObjectRewritePlanTest.plan(storedLinked, STORED_MERGE, DESIRED_ADRESSE, desiredMerge);
		check(plan.merge == BusinessObjectRewritePlan.Action.PATCH && plan.matching == BusinessObjectRewritePlan.Action.NONE && !plan.linkNeeded, "merge chain patch only");
		final ArrayNode ops = plan.patchOperations();
		check(ops.size() == 1 && "set_chain_entry".equals(op(ops, 0).path("op").asText()) && "default_field_merge".equals(op(ops, 0).path("target").path("chain").asText())
			&& 2 == op(ops, 0).path("target").path("position").asInt() && "max".equals(op(ops, 0).path("value").asText()), ops.toString());
		// the stored matching policy links to ANOTHER merge policy: the link is re-set
		final BusinessObjectRewritePlan relink = BusinessObjectRewritePlanTest.plan(STORED_ADRESSE.replace("'mergePolicyCode':null", "'mergePolicyCode':'Old'"), STORED_MERGE, DESIRED_ADRESSE,
			DESIRED_MERGE);
		check(relink.linkNeeded && relink.patchOperations().size() == 1, "re-link only: " + relink.patchOperations());
	}

	private static void treeFieldDoNothing_readsNotMatch() throws Exception
	{
		// the add-on build 6.3.0.5-RC3 stores NOT_MATCH where a plan may still say DO_NOTHING: no difference for the comparator, no op for the planner
		final String desired = DESIRED_ADRESSE.replace("'/street','algorithm':'ebx-addon-mame@fuzzyFullText','bothNullValueHandling':'NOT_MATCH'", "'/street','algorithm':'ebx-addon-mame@fuzzyFullText','bothNullValueHandling':'DO_NOTHING'");
		check(!desired.equals(DESIRED_ADRESSE), "fixture changed");
		check(MatchingPolicyComparator.sameDecisionTree(BusinessObjectRewritePlanTest.json(STORED_ADRESSE).path("decisionTree"), BusinessObjectRewritePlanTest.json(desired).path("decisionTree")),
			"DO_NOTHING on a tree field reads NOT_MATCH");
		check(BusinessObjectRewritePlanTest.plan(STORED_ADRESSE.replace("'mergePolicyCode':null", "'mergePolicyCode':'Adresse'"), STORED_MERGE, desired, DESIRED_MERGE).identical(), "identical");
	}
}
