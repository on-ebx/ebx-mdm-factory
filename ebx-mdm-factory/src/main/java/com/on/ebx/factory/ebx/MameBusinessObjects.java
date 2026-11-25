package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.master.BusinessObjectMembers;
import com.on.ebx.factory.master.BusinessObjectPlan;
import com.on.ebx.factory.master.MameAnswer;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.staging.StagingModel;
import com.orchestranetworks.service.OperationException;
import com.onwbp.adaptation.Adaptation;

/**
 * The {@code match-merge} side of the business objects of a master model (`docs/MATCHING.md` sect;33, native relation fields since add-on 6.3.0.5-RC3):
 * which business object a table belongs to, and how a table that belongs to one is activated. The declaration of the objects in the master model is
 * {@link MasterBusinessObjects} (the {@code data-model} server); the pure decisions are {@link BusinessObjectPlan}.
 * <p>
 * Measured live 2026-10-12 on the scratch model W13BO: {@code activate_matching} on a table that is part of a business object is REFUSED ("part of a
 * business object; use activate_business_object_matching"), {@code activate_business_object_matching(businessObject, dataModel)} activates every member
 * (the main table AND the child: a metadataset for both), and {@code get_business_object_activation_status} reads the members. The business-object tools
 * need {@code dataModel} like the table tools; {@code MameCalls.call} sends it on every call.
 */
final class MameBusinessObjects
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MameBusinessObjects()
	{
	}

	/** Which business object(s) a table belongs to, as the add-on declares them, and the route the configurator takes. */
	static final class Scope
	{
		/** The main table passed as {@code businessObject} to the generate/patch/validate tools; null when the table belongs to no declared object. */
		final String businessObject;
		/** Every declared business object the table belongs to (its own and its parents'), in {@link BusinessObjectPlan#candidateMainTables} order. */
		final List<String> declaredMains;
		final BusinessObjectPlan.Route route;

		Scope(final String pBusinessObject, final List<String> pDeclaredMains, final BusinessObjectPlan.Route pRoute)
		{
			this.businessObject = pBusinessObject;
			this.declaredMains = pDeclaredMains;
			this.route = pRoute;
		}

		/** The table belongs to a declared business object: the business-object tools are used for it. */
		boolean inBusinessObject()
		{
			return this.businessObject != null;
		}

		/** The relation (child) fields of the table's plan can be written: the table is the main table of a declared object. */
		boolean relationFieldsWritable()
		{
			return this.route == BusinessObjectPlan.Route.BUSINESS_OBJECT;
		}

		static Scope table()
		{
			return new Scope(null, new ArrayList<String>(), BusinessObjectPlan.Route.TABLE);
		}
	}

	/** Adds {@code businessObject} to the arguments of a generate/patch/validate call when the table is in a business object. */
	static void putBusinessObject(final ObjectNode pArgs, final Scope pScope)
	{
		if (pScope != null && pScope.businessObject != null)
		{
			pArgs.put("businessObject", pScope.businessObject);
		}
	}

	/**
	 * Asks the add-on which of the candidate main tables of {@code pEntityName} is a declared business object ({@code get_business_object_config}, one
	 * read per candidate), decides the route ({@link BusinessObjectPlan#route}) and records it under {@code businessObject} in {@code pDetail}.
	 */
	static Scope resolve(final McpClient pClient, final String pModelName, final MasterModel.Result pMasterModel, final String pEntityName,
		final boolean pPlanHasRelationFields, final ObjectNode pDetail) throws McpException, OperationException
	{
		final List<String> candidates = BusinessObjectPlan.candidateMainTables(pMasterModel, pEntityName);
		final Set<String> declared = MameBusinessObjects.declaredAmong(pClient, pModelName, candidates);
		final List<String> declaredMains = new ArrayList<String>();
		for (final String candidate : candidates)
		{
			if (declared.contains(candidate))
			{
				declaredMains.add(candidate);
			}
		}
		final String tablePath = MasterModel.tablePath(pEntityName);
		final String first = BusinessObjectPlan.firstDeclared(candidates, declared);
		final BusinessObjectPlan.Route route = BusinessObjectPlan.route(pPlanHasRelationFields, declared.contains(tablePath), first);
		final ObjectNode node = pDetail.putObject("businessObject");
		final ArrayNode candidateNode = node.putArray("candidates");
		for (final String candidate : candidates)
		{
			candidateNode.add(candidate);
		}
		final ArrayNode declaredNode = node.putArray("declared");
		for (final String main : declaredMains)
		{
			declaredNode.add(main);
		}
		node.put("planHasRelationFields", pPlanHasRelationFields);
		node.put("route", route.name());
		if (first != null)
		{
			node.put("businessObject", first);
		}
		return new Scope(first, declaredMains, route);
	}

	/** The candidates the add-on answers as declared business objects ({@link BusinessObjectPlan#isDeclared}). */
	static Set<String> declaredAmong(final McpClient pClient, final String pModelName, final List<String> pCandidates) throws McpException, OperationException
	{
		final Set<String> declared = new LinkedHashSet<String>();
		for (final String candidate : pCandidates)
		{
			final ObjectNode args = MameBusinessObjects.MAPPER.createObjectNode();
			args.put("businessObject", candidate);
			final McpClient.ToolResult result = MameCalls.call(pClient, "get_business_object_config", args, pModelName);
			if (!result.error && BusinessObjectPlan.isDeclared(MameAnswer.success(result.json, false), MameAnswer.code(result.json)))
			{
				declared.add(candidate);
			}
		}
		return declared;
	}

	/**
	 * {@code activate_business_object_matching} for every declared object of the scope, then {@code get_business_object_activation_status} of each:
	 * returns whether {@code pTablePath} itself reads ready (member listed, ENABLED, metadataset present). The detail gets one entry per object. Idempotent
	 * on the add-on side.
	 */
	static boolean activate(final McpClient pClient, final String pModelName, final String pTablePath, final Scope pScope, final ObjectNode pDetail)
		throws McpException, OperationException
	{
		boolean ready = false;
		final ArrayNode calls = pDetail.putArray("activateBusinessObjects");
		for (final String main : pScope.declaredMains)
		{
			final ObjectNode activateArgs = MameBusinessObjects.MAPPER.createObjectNode();
			activateArgs.put("businessObject", main);
			final McpClient.ToolResult activateResult = MameCalls.call(pClient, "activate_business_object_matching", activateArgs, pModelName);
			final ObjectNode entry = calls.addObject();
			entry.put("businessObject", main);
			entry.put("activateSuccess", !activateResult.error && MameAnswer.success(activateResult.json, false));
			entry.put("activateCode", String.valueOf(MameAnswer.code(activateResult.json)));
			final ObjectNode statusArgs = MameBusinessObjects.MAPPER.createObjectNode();
			statusArgs.put("businessObject", main);
			final McpClient.ToolResult statusResult = MameCalls.call(pClient, "get_business_object_activation_status", statusArgs, pModelName);
			final boolean memberReady = !statusResult.error && MameAnswer.success(statusResult.json, false) && BusinessObjectPlan.memberReady(statusResult.json, pTablePath);
			entry.put("members", BusinessObjectPlan.describeMembers(statusResult.json));
			entry.put("tableReady", memberReady);
			ready = ready || memberReady;
		}
		return ready;
	}

	// ------------------------------------------------------------------------------------------------ lot M4: the object is bound, its members hold a policy

	/**
	 * Reads {@code get_business_object_config} of {@code pMainTable} and decides whether the object is bound ({@link BusinessObjectMembers#binding}); records it under
	 * {@code businessObjectBinding} in the detail. A {@link BusinessObjectMembers.Binding#STRAY} configuration is refused here, before anything is written (no tool repairs it),
	 * when the plan compares a field of a member table ({@code pPlanHasRelationFields}): that is the write the add-on refuses on it. A plan of the table's own fields is written as
	 * before (the first {@code generate_matching_policy} with {@code businessObject} creates the object record), the state is only recorded.
	 */
	static BusinessObjectMembers.Binding checkBinding(final McpClient pClient, final String pModelName, final String pMainTable, final boolean pPlanHasRelationFields,
		final ObjectNode pDetail) throws McpException, OperationException
	{
		final ObjectNode args = MameBusinessObjects.MAPPER.createObjectNode();
		args.put("businessObject", pMainTable);
		final McpClient.ToolResult result = MameCalls.call(pClient, "get_business_object_config", args, pModelName);
		final BusinessObjectMembers.Binding binding = result.error ? BusinessObjectMembers.Binding.NOT_DECLARED : BusinessObjectMembers.binding(result.json);
		pDetail.put("businessObjectBinding", binding.name());
		if (binding == BusinessObjectMembers.Binding.STRAY && pPlanHasRelationFields)
		{
			throw EngineRefusal.coded("conflict.refused", BusinessObjectMembers.strayRefusal(pMainTable, pModelName));
		}
		return binding;
	}

	/**
	 * Lot M4 (2026-10-18, {@code docs/MATCHING.md} section 59): gives every member table of the business object whose main table is {@code pMainTable} what the add-on needs for a run
	 * of the object - a default matching policy and a merge policy ({@link BusinessObjectMembers}) - by writing the INERT policies on the members that hold none, and the default on
	 * a member that has policies but no default. A member that already holds a policy of its own is not rewritten. Verified by a re-read; refuses when a member still lacks something.
	 * Idempotent: nothing is written when every member is complete.
	 * <p>
	 * Lot M8 (2026-10-18, {@code docs/MATCHING.md} section 62): the inert merge policy takes the MODE of the main table's merge policy, read in the same {@code get_business_object_config}
	 * answer ({@link BusinessObjectMembers#mainMergeMode}; {@code pDesiredMainMode}, the mode of the policy the configure is writing on the main table, then the Factory's default, when the
	 * answer gives none); a member whose merge policy stands in another mode (the {@code DISABLED} inert policy of lot M4, project {@code E2EMASTER20261018005227}) is aligned with the patch
	 * op {@code set_merge_mode} - the add-on refuses the run of the object otherwise ("Ensure the merge policies use the same 'Mode'"). The detail records {@code memberMergeMode}, each
	 * alignment under the member's entry ({@code alignMergeMode}) and the list {@code memberMergeModeAligned}.
	 *
	 * @param pDesiredMainMode the {@code mode} of the merge policy the configure writes on the main table, null when unknown (the pre-run completion)
	 * @return one sentence per member written ({@link BusinessObjectMembers#writtenSentence}), empty when nothing was written
	 */
	static List<String> ensureMemberPolicies(final McpClient pClient, final String pModelName, final String pMainTable, final String pDesiredMainMode, final ObjectNode pDetail)
		throws McpException, OperationException
	{
		final List<String> written = new ArrayList<String>();
		final JsonNode before = MameBusinessObjects.readObject(pClient, pModelName, pMainTable);
		final BusinessObjectMembers.Binding binding = BusinessObjectMembers.binding(before);
		if (binding == BusinessObjectMembers.Binding.STRAY)
		{
			throw EngineRefusal.coded("conflict.refused", BusinessObjectMembers.strayRefusal(pMainTable, pModelName));
		}
		if (binding != BusinessObjectMembers.Binding.BOUND)
		{
			throw EngineRefusal.coded("conflict.refused", "The business object of " + pMainTable + " has no configuration yet (get_business_object_config: "
				+ MameAnswer.code(before) + "): its member tables cannot be given their policies before the main table's own policy has been generated with the business object.");
		}
		final String mainMode = BusinessObjectMembers.mainMergeMode(before, pMainTable);
		final String mergeMode = BusinessObjectMembers.memberMergeMode(mainMode, pDesiredMainMode);
		pDetail.put("memberMergeMode", mergeMode);
		pDetail.put("memberMergeModeSource", mainMode != null ? "the main table's merge policy (get_business_object_config)"
			: pDesiredMainMode != null && !pDesiredMainMode.trim().isEmpty() ? "the merge policy written on the main table by this configure" : "the Factory's default for a main table");
		final ArrayNode memberDetails = pDetail.putArray("memberPolicies");
		final List<String> alignedSentences = new ArrayList<String>();
		final String parentEntity = BusinessObjectMembers.entityOf(pMainTable);
		for (final BusinessObjectMembers.Member member : BusinessObjectMembers.membersToWrite(BusinessObjectMembers.membersOf(before, pMainTable), mergeMode))
		{
			final BusinessObjectMembers.Writes writes = BusinessObjectMembers.writesFor(member, mergeMode);
			final ObjectNode entry = memberDetails.addObject();
			entry.put("table", member.table);
			entry.put("writeMatching", writes.matching);
			entry.put("writeMerge", writes.merge);
			entry.put("setDefault", writes.defaultPolicy);
			entry.put("mergeMode", mergeMode);
			final String entity = member.entity();
			if (writes.matching)
			{
				final ObjectNode args = MameBusinessObjects.MAPPER.createObjectNode();
				args.put("table", member.table);
				args.set("matchingPolicy", BusinessObjectMembers.inertMatchingPolicy(entity));
				args.put("businessObject", pMainTable);
				final McpClient.ToolResult result = MameCalls.call(pClient, "generate_matching_policy", args, pModelName);
				entry.set("generateMatchingPolicy", MatchingConfigurator.toolDetail(args, result));
				MatchingConfigurator.requireSuccess(result, "generate_matching_policy (inert policy of " + member.table + ")");
			}
			if (writes.merge)
			{
				// lot M8: the mode is the main table's - there is no other mode the add-on would accept for a run of the object, so there is no fallback to try
				final ObjectNode mergeArgs = MameBusinessObjects.MAPPER.createObjectNode();
				mergeArgs.put("table", member.table);
				mergeArgs.set("mergePolicy", BusinessObjectMembers.inertMergePolicy(entity, mergeMode));
				mergeArgs.put("businessObject", pMainTable);
				final McpClient.ToolResult mergeResult = MameCalls.call(pClient, "generate_merge_policy", mergeArgs, pModelName);
				entry.set("generateMergePolicy", MatchingConfigurator.toolDetail(mergeArgs, mergeResult));
				MatchingConfigurator.requireSuccess(mergeResult, "generate_merge_policy (inert policy of " + member.table + ", mode " + mergeMode + ")");
			}
			if (!writes.alignMergeModes.isEmpty())
			{
				// lot M8: a merge policy already on the member in another mode (the DISABLED inert policy of lot M4): only its mode is changed, by the patch op set_merge_mode
				// (matching_config_schema -> patchOps; its value, like every patch value, is a JSON string)
				final ObjectNode alignArgs = MameBusinessObjects.MAPPER.createObjectNode();
				alignArgs.put("table", member.table);
				final ArrayNode operations = alignArgs.putArray("operations");
				for (final String code : writes.alignMergeModes)
				{
					final ObjectNode op = operations.addObject();
					op.put("op", "set_merge_mode");
					op.putObject("target").put("policyCode", code);
					op.put("value", mergeMode);
				}
				alignArgs.put("businessObject", pMainTable);
				final McpClient.ToolResult alignResult = MameCalls.call(pClient, "patch_matching_config", alignArgs, pModelName);
				entry.set("alignMergeMode", MatchingConfigurator.toolDetail(alignArgs, alignResult));
				final ArrayNode alignedCodes = entry.putArray("alignMergeModeCodes");
				for (final String code : writes.alignMergeModes)
				{
					alignedCodes.add(code);
					alignedSentences.add(member.table + ": merge policy " + code + " " + member.mergeModes.get(code) + " -> " + mergeMode);
				}
				MatchingConfigurator.requireSuccess(alignResult, "patch_matching_config (set_merge_mode " + mergeMode + " on " + member.table + ")");
			}
			if (writes.matching || writes.merge)
			{
				final ObjectNode linkArgs = MameBusinessObjects.MAPPER.createObjectNode();
				linkArgs.put("table", member.table);
				final ObjectNode linkOp = linkArgs.putArray("operations").addObject();
				linkOp.put("op", "set_merge_policy_link");
				linkOp.putObject("target").put("policyCode", writes.matching ? entity : BusinessObjectMembers.defaultCandidate(member));
				linkOp.put("value", entity);
				linkArgs.put("businessObject", pMainTable);
				final McpClient.ToolResult linkResult = MameCalls.call(pClient, "patch_matching_config", linkArgs, pModelName);
				entry.set("linkMergePolicy", MatchingConfigurator.toolDetail(linkArgs, linkResult));
				MatchingConfigurator.requireSuccess(linkResult, "patch_matching_config (set_merge_policy_link of " + member.table + ")");
			}
			written.add(BusinessObjectMembers.writtenSentence(entity, parentEntity, writes, member.mergeModes));
		}
		if (!alignedSentences.isEmpty())
		{
			MatchingConfigurator.putStrings(pDetail, "memberMergeModeAligned", alignedSentences);
		}
		if (memberDetails.size() == 0)
		{
			pDetail.remove("memberPolicies");
			return written;
		}
		// the default: the add-on makes the first policy of a table its default, but "a member has a default matching policy" is what the run checks - read, correct, read again
		JsonNode after = MameBusinessObjects.readObject(pClient, pModelName, pMainTable);
		for (final BusinessObjectMembers.Member member : BusinessObjectMembers.membersToWrite(BusinessObjectMembers.membersOf(after, pMainTable), mergeMode))
		{
			final String candidate = BusinessObjectMembers.defaultCandidate(member);
			if (candidate != null && !member.hasDefaultMatching())
			{
				final ObjectNode defaultDetail = pDetail.putObject("memberDefault_" + member.entity());
				MatchingConfigurator.ensureDefaultMatchingPolicy(pClient, member.table, pModelName, member.entity() + BusinessObjectMembers.PREVIEW_SUFFIX, candidate, null, defaultDetail);
			}
		}
		after = MameBusinessObjects.readObject(pClient, pModelName, pMainTable);
		final List<BusinessObjectMembers.Member> stillMissing = BusinessObjectMembers.membersToWrite(BusinessObjectMembers.membersOf(after, pMainTable), mergeMode);
		if (!stillMissing.isEmpty())
		{
			throw EngineRefusal.coded("external.toolFailed", BusinessObjectMembers.stillMissingRefusal(pMainTable, stillMissing, mergeMode), "get_business_object_config");
		}
		return written;
	}

	/**
	 * The run's own safety net: before a run on the main table of a declared business object, a member left without a default matching policy or a merge policy (a project configured
	 * before lot M4), or whose merge policy stands in another mode than the main table's (a project configured by lot M4-M7, whose inert policy was DISABLED - lot M8), is completed with
	 * the same inert policies and the object is activated again (it publishes the members). Nothing is called for a table that is not the main table of a
	 * declared object. Returns the sentences of what was written (empty = nothing).
	 */
	static List<String> ensureMembersBeforeRun(final Adaptation pFactory, final McpClient pClient, final String pProjectCode, final String pModelName, final String pTablePath,
		final ObjectNode pDetail) throws McpException, OperationException
	{
		final List<String> mains = MameBusinessObjects.declaredMainsOf(pFactory, pClient, pProjectCode, pModelName, pTablePath);
		if (!mains.contains(pTablePath))
		{
			return new ArrayList<String>();
		}
		// lot M8: before a run there is no policy being written on the main table - the mode comes from its stored merge policy, else the Factory's default
		final List<String> written = MameBusinessObjects.ensureMemberPolicies(pClient, pModelName, pTablePath, null, pDetail);
		if (!written.isEmpty())
		{
			final ObjectNode activateArgs = MameBusinessObjects.MAPPER.createObjectNode();
			activateArgs.put("businessObject", pTablePath);
			final McpClient.ToolResult activateResult = MameCalls.call(pClient, "activate_business_object_matching", activateArgs, pModelName);
			pDetail.set("activateAfterMemberPolicies", MatchingConfigurator.toolDetail(activateArgs, activateResult));
			MatchingConfigurator.requireSuccess(activateResult, "activate_business_object_matching (after the member policies)");
		}
		return written;
	}

	private static JsonNode readObject(final McpClient pClient, final String pModelName, final String pMainTable) throws McpException, OperationException
	{
		final ObjectNode args = MameBusinessObjects.MAPPER.createObjectNode();
		args.put("businessObject", pMainTable);
		final McpClient.ToolResult result = MameCalls.call(pClient, "get_business_object_config", args, pModelName);
		if (result.error)
		{
			throw EngineRefusal.coded("external.toolFailed", "get_business_object_config failed for " + pMainTable + ": " + MameBusinessObjects.shortText(result.text), "get_business_object_config");
		}
		return result.json;
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 400 ? text.substring(0, 400) + "..." : text;
	}

	/**
	 * The declared business objects {@code pTablePath} belongs to, resolved from the staging entities of the project - used by the activation guard
	 * (which only has the table path) to repair an activation. Empty when the table is in none (or the master model cannot be read: the guard then
	 * falls back to {@code activate_matching}, which says clearly if the table is a member).
	 */
	static List<String> declaredMainsOf(final Adaptation pFactory, final McpClient pClient, final String pProjectCode, final String pModelName,
		final String pTablePath)
	{
		try
		{
			final Adaptation project = MatchingConfigurator.findProject(pFactory, pProjectCode);
			if (project == null)
			{
				return new ArrayList<String>();
			}
			final List<StagingModel.EntityInfo> entities = MatchingConfigurator.readEntities(pFactory, project.getOccurrencePrimaryKey().format(), new HashMap<String, String>());
			final MasterModel.Result masterModel = MasterModel.buildFromStaging(entities);
			final String entity = pTablePath.startsWith("/root/") ? pTablePath.substring("/root/".length()) : pTablePath;
			final List<String> candidates = BusinessObjectPlan.candidateMainTables(masterModel, entity);
			final Set<String> declared = MameBusinessObjects.declaredAmong(pClient, pModelName, candidates);
			final List<String> out = new ArrayList<String>();
			for (final String candidate : candidates)
			{
				if (declared.contains(candidate))
				{
					out.add(candidate);
				}
			}
			return out;
		}
		catch (final McpException | OperationException | RuntimeException ex)
		{
			return new ArrayList<String>();
		}
	}
}
