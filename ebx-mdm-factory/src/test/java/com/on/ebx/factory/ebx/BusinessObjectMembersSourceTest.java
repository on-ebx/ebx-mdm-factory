package com.on.ebx.factory.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Lot M4 (2026-10-18, docs/MATCHING.md section 59): where the business object handling sits, read from the sources (each step needs the match-merge server, so it cannot run here).
 * The order is the whole fix: bind the object before anything else is written on its main table, set the trusted sources only after the first policy of such a table, give the member
 * tables their policies after the policies of the main table and before the validation, never run a member alone, complete a project configured before this lot before its run.
 * Point C: a refusal of the apply before any call is written on the row, and the notes of the auto-apply always reach the job.
 */
public final class BusinessObjectMembersSourceTest
{
	private BusinessObjectMembersSourceTest()
	{
	}

	private static String read(final String pName) throws Exception
	{
		return new String(Files.readAllBytes(Paths.get(System.getProperty("mdmf.sourceDir", "src/main/java") + "/com/on/ebx/factory/ebx/" + pName)), StandardCharsets.UTF_8);
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String configurator = BusinessObjectMembersSourceTest.read("MatchingConfigurator.java");
		final int one = configurator.indexOf("private static MatchingConfigurator.ConfigureDecision configureOne(");
		final int binding = configurator.indexOf("MameBusinessObjects.checkBinding(", one);
		final int switchAt = configurator.indexOf("switch (decision)", one);
		final int firstGenerate = configurator.indexOf("MatchingConfigurator.writePoliciesThroughGenerate(client", one);
		BusinessObjectMembersSourceTest.check("the binding of the object is checked before the first write of configureOne", binding > one && binding < switchAt);
		BusinessObjectMembersSourceTest.check("the check is for the main table only (a member is refused earlier)", configurator.substring(binding - 260, binding).contains("!BusinessObjectPlan.isMember("));

		final int trustedBlock = configurator.indexOf("final MatchingConfigurator.WriteStep setTrustedSources = () -> {", one);
		final int trustedRunsFirst = configurator.indexOf("setTrustedSources.run();", one);
		BusinessObjectMembersSourceTest.check("set_trusted_sources is a step handed to the generate sequence", trustedBlock > one && trustedBlock < firstGenerate);
		BusinessObjectMembersSourceTest.check("it runs first only for a table that is in no business object", trustedRunsFirst > trustedBlock && trustedRunsFirst < firstGenerate
			&& configurator.substring(trustedRunsFirst - 80, trustedRunsFirst).contains("!boScope.inBusinessObject()"));
		BusinessObjectMembersSourceTest.check("for a business object table it is handed to writePoliciesThroughGenerate, which runs it between the matching and the merge policy",
			configurator.indexOf("boScope.inBusinessObject() ? setTrustedSources : null", one) > firstGenerate);
		final int generateMatching = configurator.indexOf("\"generate_matching_policy\", generateMatchingArgs");
		final int between = configurator.indexOf("pBetweenMatchingAndMerge.run();", generateMatching);
		final int generateMerge = configurator.indexOf("\"generate_merge_policy\", generateMergeArgs", generateMatching);
		BusinessObjectMembersSourceTest.check("generate_matching_policy, then the step, then generate_merge_policy", generateMatching > 0 && between > generateMatching && generateMerge > between);

		final int members = configurator.indexOf("MameBusinessObjects.ensureMemberPolicies(client", one);
		final int validate = configurator.indexOf("\"validate_matching_config\"", one);
		final int activate = configurator.indexOf("MameBusinessObjects.activate(client", one);
		BusinessObjectMembersSourceTest.check("the member policies are written after the policies of the main table (every branch) and before the validation and the activation",
			members > switchAt && members < validate && members < activate);
		BusinessObjectMembersSourceTest.check("a configure that wrote member policies activates the object again", configurator.indexOf("skipActivateBecauseAlreadyEnabled = false;", members) > members);
		BusinessObjectMembersSourceTest.check("the member sentences reach the configure result and the step message", configurator.contains("\"memberPolicies\", memberPolicies") && configurator.contains("pMemberPolicies.addAll(memberSentences)")
			&& configurator.contains("message += \" \" + String.join(\" \", memberSentences);"));

		final int run = configurator.indexOf("static ObjectNode runInternal(");
		final int alone = configurator.indexOf("ChildEntityMatching.runsAlone(memberParents)", run);
		final int applied = configurator.indexOf("!\"APPLIED\".equals(plan.getString(FactoryPaths.MatchPlan.STATUS))", run);
		BusinessObjectMembersSourceTest.check("the run leaves a member alone before it looks at the plan status (an APPLIED child plan is not run either)", alone > run && alone < applied
			&& configurator.indexOf("followsParentInRun.add(", run) < applied);
		final int ensure = configurator.indexOf("MameBusinessObjects.ensureMembersBeforeRun(", run);
		final int launch = configurator.indexOf("MameMatchingApi.launchScoped(", run);
		BusinessObjectMembersSourceTest.check("a project configured before this lot is completed before the run launches", ensure > run && ensure < launch
			&& configurator.substring(ensure - 400, ensure).contains("!masterModel.childTablePaths(entityName).isEmpty()"));

		final String objects = BusinessObjectMembersSourceTest.read("MameBusinessObjects.java");
		BusinessObjectMembersSourceTest.check("the member policies go through generate_matching_policy / generate_merge_policy with the business object, never apply_matching_config or delete_matching_config",
			objects.contains("\"generate_matching_policy\"") && objects.contains("\"generate_merge_policy\"") && objects.contains("args.put(\"businessObject\", pMainTable)")
				&& !objects.contains("apply_matching_config") && !objects.contains("delete_matching_config"));
		// lot M8 (2026-10-18, docs/MATCHING.md section 62): the member's merge mode is the main table's, read in the answer, and a member in another mode is patched with set_merge_mode
		final int memberWrites = objects.indexOf("static List<String> ensureMemberPolicies(");
		final int modeRead = objects.indexOf("BusinessObjectMembers.mainMergeMode(before, pMainTable)", memberWrites);
		final int firstWrite = objects.indexOf("\"generate_matching_policy\"", memberWrites);
		BusinessObjectMembersSourceTest.check("the main table's merge mode is read off the get_business_object_config answer before any member write", modeRead > memberWrites && modeRead < firstWrite
			&& objects.indexOf("BusinessObjectMembers.memberMergeMode(mainMode, pDesiredMainMode)", modeRead) < firstWrite);
		BusinessObjectMembersSourceTest.check("the inert merge policy is written in that mode, once, with no second mode to fall back on", objects.contains("BusinessObjectMembers.inertMergePolicy(entity, mergeMode)")
			&& !objects.contains("MERGE_MODE_PREFERRED") && !objects.contains("MERGE_MODE_FALLBACK") && !objects.contains("generateMergePolicyFallback"));
		final int align = objects.indexOf("op.put(\"op\", \"set_merge_mode\");", memberWrites);
		BusinessObjectMembersSourceTest.check("a member whose merge policy stands in another mode is aligned by the patch op set_merge_mode, value = the mode (a JSON string), then the link is set",
			align > firstWrite && align < objects.indexOf("\"set_merge_policy_link\"", memberWrites) && objects.indexOf("op.put(\"value\", mergeMode);", align) > align
				&& objects.indexOf("\"patch_matching_config\", alignArgs", align) > align);
		BusinessObjectMembersSourceTest.check("the alignment is recorded in the step detail (member entry and the list memberMergeModeAligned)", objects.contains("entry.set(\"alignMergeMode\"")
			&& objects.contains("\"memberMergeModeAligned\", alignedSentences") && objects.contains("pDetail.put(\"memberMergeMode\", mergeMode)"));
		BusinessObjectMembersSourceTest.check("the configure hands the mode it writes on the main table over, the pre-run completion hands none",
			configurator.contains("MameBusinessObjects.ensureMemberPolicies(client, pModelName, boScope.businessObject, desiredMergePolicy.path(\"mode\").asText(null), detail)")
				&& objects.contains("MameBusinessObjects.ensureMemberPolicies(pClient, pModelName, pTablePath, null, pDetail)"));
		BusinessObjectMembersSourceTest.check("the writes are verified by a re-read, and a member still lacking something refuses the configure",
			objects.indexOf("stillMissingRefusal") > objects.indexOf("final List<BusinessObjectMembers.Member> stillMissing"));
		BusinessObjectMembersSourceTest.check("the stray configuration is refused, nothing is deleted", objects.contains("BusinessObjectMembers.strayRefusal"));

		// point C
		final String applier = BusinessObjectMembersSourceTest.read("CapabilityApplier.java");
		final int prepareCatch = applier.indexOf("resultsById.put(id, CapabilityApplier.failureResult(id, ex.getMessage()));");
		BusinessObjectMembersSourceTest.check("a refusal before any call is written on the row (applyMessage), right where it is caught", prepareCatch > 0
			&& applier.indexOf("CapabilityApplier.recordPrepareFailure(pSession, pFactory, projectId, id, ex.getMessage());", prepareCatch) > prepareCatch);
		final String loader = BusinessObjectMembersSourceTest.read("StagingLoader.java");
		final int notes = loader.indexOf("if (!autoApplyNotes.isEmpty())");
		BusinessObjectMembersSourceTest.check("the notes of the auto-apply reach the job whatever applied (they were forwarded only after a derivation)", notes > 0
			&& loader.indexOf("pListener.note(note);", notes) > notes && !loader.substring(notes - 120, notes).contains("autoApplied.size() > 0"));
		System.out.println("BusinessObjectMembersSourceTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
