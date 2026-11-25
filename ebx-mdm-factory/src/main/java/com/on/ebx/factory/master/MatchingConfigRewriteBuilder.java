package com.on.ebx.factory.master;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the FULL desired {@code matchingPolicies[]}/{@code mergePolicies[]} arrays for {@code
 * apply_matching_config}'s {@code config} argument, when an entity's ALREADY-CONFIGURED matching policy must
 * be rewritten in place because the accepted {@code MatchPlan} changed since the last successful {@code
 * matching/configure} (`docs/MATCHING.md` &sect;19 "the gap"; {@code MatchingPolicyComparator} is what decides
 * a rewrite is actually needed - this class only ever builds the payload once that decision is already made).
 * <p>
 * Deliberately mirrors {@code ebx.MatchingPreviewPolicy#ensureRewrite}'s own pattern (CLAUDE.md &sect;8's own
 * "{@code apply_matching_config} is a wholesale REPLACE" rule): read the current lists, drop the entity's own
 * STALE entry (and any leftover {@code __preview} entry) from each, add the freshly-rewritten one back, resend
 * the WHOLE lists - every OTHER entry on the same table (a different entity sharing it, or - not expected in
 * this codebase's own one-table-per-entity model, but never assumed away - a stray unrelated policy) is copied
 * over VERBATIM.
 * <p>
 * Only the plan-derived parts of each rewritten entry actually change; every operational/admin field the
 * STORED entry itself already carries survives untouched, by CONSTRUCTION (deep-copy the stored entry, then
 * overwrite only the plan-derived keys) - never by re-asserting a literal list of "fields to keep" that could
 * silently drift from a future schema addition:
 * <ul>
 * <li>matching policy: {@code decisionTree}, {@code fields}, {@code mergePolicyCode} are OVERWRITTEN; {@code
 * processCode}, {@code active}, {@code matchingOnCreation}/{@code matchingOnUpdate}, {@code executePurge},
 * {@code showMatchType}, {@code matchAgainstMergedRecords}, any {@code workflowOn*} key, and anything else the
 * stored entry carries are KEPT exactly as stored.</li>
 * <li>merge policy: {@code recordSelectionFunctionChain}, {@code defaultFieldMergeFunctionChain}, {@code
 * survivors} are OVERWRITTEN; {@code code}, {@code mode}, {@code useForManualMerge}, {@code applyPermission},
 * and anything else the stored entry carries are KEPT exactly as stored. {@code relatedTables} is a THIRD,
 * narrower kind of overwrite (lot 2, cascade, 2026-09-23, `docs/MATCHING.md` &sect;12.4/&sect;22): every entry's own {@code
 * name}/{@code path} survive VERBATIM, exactly as stored - only the {@code alignForeignKeys} enum of an entry
 * whose {@code path} is a promoted CHILD entity's own table path is rewritten to {@link RelatedTableAlignment#WANTED} ({@code NONE} since lot MT, 2026-10-04: {@code
 * RelatedTableAlignment#align}, given {@code pChildTablePaths}); this class never adds or removes a {@code
 * relatedTables[]} entry - see that class' own Javadoc for why (the add-on owns list membership, not this
 * generator).</li>
 * </ul>
 * {@code trustedSources}/{@code tableSettings} are never touched by this class at all (they are not even part
 * of {@code apply_matching_config}'s {@code config} argument this class builds) - the survivorship route
 * ({@code set_trusted_sources} + {@code master.MergeConfigPatchPlanner}'s own op list) stays the only writer
 * of the table-level trusted-source ranking (task brief's own contract, `docs/MATCHING.md` &sect;19).
 * <p>
 * <b>Convention assumed, not re-derived:</b> the merge policy this class rewrites is looked up by {@code
 * pDesiredMergePolicyCode} (the entity name itself, by {@code ebx.MatchingConfigurator}'s own fixed
 * convention - it never links an entity's matching policy to any other merge policy), not by whatever {@code
 * mergePolicyCode} the stored matching-policy entry actually carries right now. If those two ever diverged (a
 * corrupted or hand-edited live configuration), this class would rewrite the entity-named merge policy and
 * leave the ACTUALLY-linked one (if it even still exists) untouched - a known, documented limitation, not a
 * case this lot defends against; {@code MatchingPolicyComparator.compare} (which DOES read the real link) is
 * what would surface such a divergence as {@code mergePolicyCodeChanged} in the first place.
 * <p>
 * Pure: no EBX class, Jackson only - unit-tested without a running instance, same convention as every other
 * class in this package.
 */
public final class MatchingConfigRewriteBuilder
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MatchingConfigRewriteBuilder()
	{
	}

	/** {@link #build}'s own result - the two full arrays {@code apply_matching_config}'s {@code config} argument wants, {@code {matchingPolicies, mergePolicies}}. */
	public static final class Rewrite
	{
		public final ArrayNode matchingPolicies;
		public final ArrayNode mergePolicies;

		Rewrite(final ArrayNode pMatchingPolicies, final ArrayNode pMergePolicies)
		{
			this.matchingPolicies = pMatchingPolicies;
			this.mergePolicies = pMergePolicies;
		}
	}

	/**
	 * @param pExistingMatchingPolicies {@code get_matching_config}'s own {@code matchingPolicies[]} (read via
	 *            {@code MameAnswer.field}) - every entry copied over verbatim EXCEPT the entity's own ({@code
	 *            processCode == pEntityCode}) and any leftover preview ({@code processCode == pPreviewCode}).
	 * @param pExistingMergePolicies same, keyed by {@code code}.
	 * @param pEntityCode the entity's own {@code processCode} - the one {@code matchingPolicies[]} entry this
	 *            method actually rewrites.
	 * @param pPreviewCode {@code MatchingPreviewPolicy.policyCode(pEntityCode)}, or {@code null}/blank to skip
	 *            dropping a preview entry (this class never assumes the caller always has one to drop).
	 * @param pDesiredMatchingPolicy {@code MatchingPolicyTranslator.buildMatchingPolicy}'s own output for the
	 *            ACCEPTED plan - only {@code decisionTree}/{@code fields} are read off it.
	 * @param pDesiredMergePolicyCode the merge policy this rewritten matching-policy entry must link to, and
	 *            the {@code code} of the {@code mergePolicies[]} entry this method rewrites (convention: the
	 *            entity name itself - see class Javadoc "Convention assumed, not re-derived").
	 * @param pDesiredMergePolicy {@code MatchingPolicyTranslator.buildMergePolicy}'s own output for the ACCEPTED
	 *            plan - only {@code recordSelectionFunctionChain}/{@code defaultFieldMergeFunctionChain}/{@code
	 *            survivors} (and, since lot M1, {@code autoCreateNewGoldenSource}) are read off it (never {@code relatedTables} - the translator deliberately never
	 *            emits it).
	 * @param pChildTablePaths lot 2, cascade (2026-09-23) - every promoted CHILD entity's own table path of
	 *            this entity ({@code MasterModel.Result#childTablePaths}), passed straight to {@code
	 *            RelatedTableAlignment#align} against the STORED merge policy's own {@code relatedTables[]};
	 *            {@code null} is treated as the empty set (every entry then kept exactly as stored).
	 * @throws IllegalStateException if the entity's own stored entry cannot be found, BY NAME, in either list -
	 *             the caller ({@code ebx.MatchingConfigurator}) is only meant to reach this class once it has
	 *             already established, via a fresh {@code get_matching_config} answer, that a real, owned
	 *             configuration exists for this exact entity; a missing entry here means that assumption broke,
	 *             and this class refuses to guess an admin/operational shape to keep rather than silently
	 *             building an incomplete rewrite.
	 */
	public static Rewrite build(final JsonNode pExistingMatchingPolicies, final JsonNode pExistingMergePolicies, final String pEntityCode, final String pPreviewCode,
		final ObjectNode pDesiredMatchingPolicy, final String pDesiredMergePolicyCode, final ObjectNode pDesiredMergePolicy, final Set<String> pChildTablePaths)
	{
		final JsonNode storedMatchingPolicyNode = MatchingPolicyComparator.findPolicyEntry(pExistingMatchingPolicies, "processCode", pEntityCode);
		if (!(storedMatchingPolicyNode instanceof ObjectNode))
		{
			throw new IllegalStateException("MatchingConfigRewriteBuilder was asked to rewrite " + pEntityCode
				+ " but no matchingPolicies[] entry with that processCode was found in the existing configuration - refusing to build an incomplete rewrite.");
		}
		final ObjectNode rewrittenMatchingPolicy = ((ObjectNode) storedMatchingPolicyNode).deepCopy();
		rewrittenMatchingPolicy.set("decisionTree", pDesiredMatchingPolicy.path("decisionTree").deepCopy());
		rewrittenMatchingPolicy.set("fields", pDesiredMatchingPolicy.path("fields").deepCopy());
		rewrittenMatchingPolicy.put("mergePolicyCode", pDesiredMergePolicyCode);

		final ArrayNode matchingPolicies = MatchingConfigRewriteBuilder.MAPPER.createArrayNode();
		for (final JsonNode policy : pExistingMatchingPolicies)
		{
			final String code = policy.path("processCode").asText(null);
			if (pEntityCode.equals(code) || (pPreviewCode != null && pPreviewCode.equals(code)))
			{
				continue;
			}
			matchingPolicies.add(policy.deepCopy());
		}
		matchingPolicies.add(rewrittenMatchingPolicy);

		final JsonNode storedMergePolicyNode = MatchingPolicyComparator.findPolicyEntry(pExistingMergePolicies, "code", pDesiredMergePolicyCode);
		if (!(storedMergePolicyNode instanceof ObjectNode))
		{
			throw new IllegalStateException("MatchingConfigRewriteBuilder was asked to rewrite " + pEntityCode + "'s linked merge policy " + pDesiredMergePolicyCode
				+ " but no mergePolicies[] entry with that code was found in the existing configuration - refusing to build an incomplete rewrite.");
		}
		final ObjectNode rewrittenMergePolicy = ((ObjectNode) storedMergePolicyNode).deepCopy();
		rewrittenMergePolicy.set("recordSelectionFunctionChain", pDesiredMergePolicy.path("recordSelectionFunctionChain").deepCopy());
		rewrittenMergePolicy.set("defaultFieldMergeFunctionChain", pDesiredMergePolicy.path("defaultFieldMergeFunctionChain").deepCopy());
		rewrittenMergePolicy.set("survivors", pDesiredMergePolicy.path("survivors").deepCopy());
		if (pDesiredMergePolicy.hasNonNull("autoCreateNewGoldenSource"))
		{
			// lot M1 (2026-10-17): the source of a golden the add-on creates (replaces the former constant survivor on /sourceSystem)
			rewrittenMergePolicy.put("autoCreateNewGoldenSource", pDesiredMergePolicy.path("autoCreateNewGoldenSource").asText());
		}
		// lot 2, cascade (2026-09-23): narrower than the three overwrites above - every entry's own name/path
		// survive verbatim, only the alignForeignKeys enum of a promoted child's own entry is rewritten to
		// RelatedTableAlignment.WANTED (RelatedTableAlignment never adds or removes an entry - see its own Javadoc).
		rewrittenMergePolicy.set("relatedTables", RelatedTableAlignment.align(storedMergePolicyNode.path("relatedTables"), pChildTablePaths));

		final ArrayNode mergePolicies = MatchingConfigRewriteBuilder.MAPPER.createArrayNode();
		for (final JsonNode policy : pExistingMergePolicies)
		{
			final String code = policy.path("code").asText(null);
			if (pDesiredMergePolicyCode.equals(code) || (pPreviewCode != null && pPreviewCode.equals(code)))
			{
				continue;
			}
			mergePolicies.add(policy.deepCopy());
		}
		mergePolicies.add(rewrittenMergePolicy);

		return new Rewrite(matchingPolicies, mergePolicies);
	}
}
