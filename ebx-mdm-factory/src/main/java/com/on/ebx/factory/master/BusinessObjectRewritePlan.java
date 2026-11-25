package com.on.ebx.factory.master;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the configurator does to rewrite the policies of a table that belongs to a business object when the accepted plan differs from what the add-on stores
 * (lot W13b, 2026-10-12, `docs/MATCHING.md` &sect;33.13). Measured on Vol1mR (steps 1435/1436): {@code apply_matching_config} is refused on such a table and
 * {@code generate_matching_policy} answers "A matching policy with processCode 'X' already exists on this table ... Use patch_matching_config", so each part is
 * decided on its own:
 * <ul>
 * <li>the matching policy: {@link Action#GENERATE} when the table stores none of that code, otherwise {@link Action#PATCH} with the in-place operations of
 * {@link MatchingPolicyPatchPlanner} (or {@link Action#NONE});</li>
 * <li>the merge policy: {@link Action#GENERATE} when none of that code is stored (Vol1mR's {@code Adresse} had its matching policy and no merge policy at all),
 * otherwise {@link Action#PATCH} with {@link MergeConfigPatchPlanner}'s chain and survivor operations (or {@link Action#NONE});</li>
 * <li>the link ({@code set_merge_policy_link}): needed when anything was generated or the stored matching policy links to another merge policy.</li>
 * </ul>
 * A structural difference of the decision tree makes the whole plan a refusal ({@link #refuses}): nothing is written. Pure Jackson.
 */
public final class BusinessObjectRewritePlan
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public enum Action
	{
		/** Nothing to write for this part. */
		NONE,
		/** The part does not exist yet: {@code generate_matching_policy} / {@code generate_merge_policy}. */
		GENERATE,
		/** The part exists and differs: {@code patch_matching_config} operations. */
		PATCH
	}

	public final Action matching;
	public final Action merge;
	/** The in-place operations of the matching policy ({@link MatchingPolicyPatchPlanner}); empty unless {@link #matching} is {@link Action#PATCH}. */
	public final ArrayNode matchingOperations;
	/** The chain and survivor operations of the merge policy ({@link MergeConfigPatchPlanner}); empty unless {@link #merge} is {@link Action#PATCH}. */
	public final ArrayNode mergeOperations;
	public final boolean linkNeeded;
	public final List<String> structural;
	private final String policyCode;

	private BusinessObjectRewritePlan(final String pPolicyCode, final Action pMatching, final Action pMerge, final ArrayNode pMatchingOperations,
		final ArrayNode pMergeOperations, final boolean pLinkNeeded, final List<String> pStructural)
	{
		this.policyCode = pPolicyCode;
		this.matching = pMatching;
		this.merge = pMerge;
		this.matchingOperations = pMatchingOperations;
		this.mergeOperations = pMergeOperations;
		this.linkNeeded = pLinkNeeded;
		this.structural = pStructural;
	}

	/**
	 * @param pPolicyCode the entity name: both the matching policy's {@code processCode} and the merge policy's {@code code}
	 * @param pStoredMatchingPolicies {@code matchingPolicies[]} of a fresh {@code get_matching_config} (null reads as empty)
	 * @param pStoredMergePolicies {@code mergePolicies[]} of the same answer (null reads as empty)
	 */
	public static BusinessObjectRewritePlan build(final String pPolicyCode, final JsonNode pStoredMatchingPolicies, final JsonNode pStoredMergePolicies,
		final ObjectNode pDesiredMatching, final ObjectNode pDesiredMerge)
	{
		final JsonNode storedMatching = pStoredMatchingPolicies == null ? MissingNode.getInstance()
			: MatchingPolicyComparator.findPolicyEntry(pStoredMatchingPolicies, "processCode", pPolicyCode);
		final JsonNode storedMerge = pStoredMergePolicies == null ? MissingNode.getInstance()
			: MatchingPolicyComparator.findPolicyEntry(pStoredMergePolicies, "code", pPolicyCode);
		final boolean matchingStored = !storedMatching.isMissingNode();
		final boolean mergeStored = !storedMerge.isMissingNode();

		Action matching = Action.GENERATE;
		ArrayNode matchingOps = BusinessObjectRewritePlan.MAPPER.createArrayNode();
		List<String> structural = new java.util.ArrayList<String>();
		if (matchingStored)
		{
			final MatchingPolicyPatchPlanner.Result result = MatchingPolicyPatchPlanner.plan(pPolicyCode, storedMatching, pDesiredMatching);
			matchingOps = result.operations;
			structural = result.structural;
			matching = matchingOps.size() > 0 ? Action.PATCH : Action.NONE;
		}
		Action merge = Action.GENERATE;
		ArrayNode mergeOps = BusinessObjectRewritePlan.MAPPER.createArrayNode();
		if (mergeStored)
		{
			mergeOps = MergeConfigPatchPlanner.plan(pPolicyCode, storedMerge, pDesiredMerge);
			merge = mergeOps.size() > 0 ? Action.PATCH : Action.NONE;
		}
		final String storedLink = !matchingStored ? "" : MatchingPolicyComparator.text(storedMatching, "mergePolicyCode");
		final boolean linkNeeded = matching == Action.GENERATE || merge == Action.GENERATE || !pPolicyCode.equals(storedLink);
		return new BusinessObjectRewritePlan(pPolicyCode, matching, merge, matchingOps, mergeOps, linkNeeded, structural);
	}

	/** The decision tree differs in a way no in-place operation expresses: nothing is written, the person deletes the policy and configures again. */
	public boolean refuses()
	{
		return !this.structural.isEmpty();
	}

	/** Nothing to write at all: the stored policies already are the plan's (after the add-on's known read-back differences). */
	public boolean identical()
	{
		return !this.refuses() && this.matching == Action.NONE && this.merge == Action.NONE && !this.linkNeeded;
	}

	/** The structural differences as one fragment for the refusal sentence. */
	public String describeStructural()
	{
		return String.join("; ", this.structural);
	}

	/**
	 * The single {@code patch_matching_config} call that follows the generations: matching operations, merge operations, then the link (after both policies
	 * exist). Empty when there is nothing to patch.
	 */
	public ArrayNode patchOperations()
	{
		final ArrayNode ops = BusinessObjectRewritePlan.MAPPER.createArrayNode();
		ops.addAll(this.matchingOperations);
		ops.addAll(this.mergeOperations);
		if (this.linkNeeded)
		{
			final ObjectNode link = ops.addObject();
			link.put("op", "set_merge_policy_link");
			link.putObject("target").put("policyCode", this.policyCode);
			link.put("value", this.policyCode);
		}
		return ops;
	}
}
