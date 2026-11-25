package com.on.ebx.factory.master;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws (same convention as every other test in this codebase).
 * <p>
 * Covers {@link MatchingConfigRewriteBuilder#build} against hand-built {@code get_matching_config}-shaped
 * fixtures - a stranger's entry on the same table, the entity's own STALE entry, and a leftover {@code
 * __preview} entry, all in the same lists - proving the entity's own entry is REPLACED, everything else is
 * copied VERBATIM, the preview is dropped from both lists, and every operational/admin field the stored entry
 * already carries survives untouched (see {@link MatchingConfigRewriteBuilder}'s own class Javadoc for exactly
 * which fields those are).
 * <p>
 * {@link #build_liveCompanyCase_sourceKeySurvivorSurvivesTheRewrite} pins the 2026-09-23 live defect
 * (PipelineStep 108, main `117e193`): a rewrite of the real {@code Company} table answered {@code "differs:
 * decisionTree, fields (candidate gate), merge policy chains, merge policy survivors"} and applied, but the
 * survivors it actually SENT were missing {@code /supplierId} - {@code Company}'s own structurally-detected
 * source-key attribute ({@code ebx.MatchingConfigurator#readSourceKeyFieldNames}, `docs/MATCHING.md` sec 19)
 * - even though the fresh-configure path and the survivorship route both pass it to {@code
 * MatchingPolicyTranslator#buildMergePolicy}. This test builds the DESIRED merge policy through the real
 * translator with {@code sourceKeyFieldNames = ["supplierId"]} - exactly what {@code readSourceKeyFieldNames}
 * returns for {@code Company} - runs it through {@link MatchingConfigRewriteBuilder#build} against a stored
 * fixture whose OWN survivors never had it (the pre-lot shape), and asserts the REWRITTEN survivors carry a
 * {@code /supplierId} "no merge" rule. `ebx.MatchingConfigurator.configureOne` itself could not be exercised
 * live this session (no EBX access) - this is the end-to-end proof at the pure-translation layer that the
 * plumbing this fix relies on (source-key names in, survivor rule out, all the way through the rewrite
 * builder) is intact; see that method's own 2026-09-23 comment for the `detail.sourceKeyFieldNames` diagnostic
 * added alongside this test, in case a future live gap has a different root cause (an empty
 * {@code sourceKeyFieldNames} list itself, upstream of this pipeline).
 */
public final class MatchingConfigRewriteBuilderTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchingConfigRewriteBuilderTest.build_replacesEntityEntry_keepsAdminFields_dropsPreview_copiesStrangerVerbatim();
		MatchingConfigRewriteBuilderTest.build_mergePolicyReplaced_keepsModeRelatedTablesUseForManualMerge();
		MatchingConfigRewriteBuilderTest.build_missingMatchingPolicyEntry_throws();
		MatchingConfigRewriteBuilderTest.build_missingMergePolicyEntry_throws();
		MatchingConfigRewriteBuilderTest.build_liveCompanyCase_sourceKeySurvivorSurvivesTheRewrite();
		MatchingConfigRewriteBuilderTest.build_cascade_childTableSetToAutomatic_nonChildKeptManual();
		MatchingConfigRewriteBuilderTest.build_cascade_namesAndPathsKeptVerbatim();
		MatchingConfigRewriteBuilderTest.build_lotM1_goldenSourceReplacesTheLegacyConstantSurvivor();
		System.out.println("MatchingConfigRewriteBuilderTest OK");
	}

	private static void build_replacesEntityEntry_keepsAdminFields_dropsPreview_copiesStrangerVerbatim() throws Exception
	{
		final JsonNode existingMatchingPolicies = MatchingConfigRewriteBuilderTest.parse("[" + "{\"processCode\":\"Contact\",\"decisionTree\":{\"stranger\":true}},"
			+ "{\"processCode\":\"Company\",\"active\":false,\"matchingOnCreation\":1,\"matchingOnUpdate\":1,\"executePurge\":\"MANUAL\",\"showMatchType\":true,"
			+ "\"matchAgainstMergedRecords\":true,\"workflowOnCreate\":\"X\",\"mergePolicyCode\":\"Company\",\"decisionTree\":{\"old\":true},"
			+ "\"fields\":[{\"field\":\"/country\"}]},"
			+ "{\"processCode\":\"Company__preview\",\"decisionTree\":{\"preview\":true}}" + "]");
		final JsonNode existingMergePolicies = MatchingConfigRewriteBuilderTest.parse("[{\"code\":\"Company\",\"mode\":\"ONLY_DUPLICATES\",\"useForManualMerge\":true,"
			+ "\"applyPermission\":false,\"relatedTables\":[{\"name\":\"Roles\"}],\"recordSelectionFunctionChain\":[{\"function\":\"old\"}],\"survivors\":[]}]");

		final ObjectNode desiredMatchingPolicy = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		desiredMatchingPolicy.putObject("decisionTree").put("fresh", true);
		desiredMatchingPolicy.putArray("fields").addObject().put("field", "/nameKey");
		final ObjectNode desiredMergePolicy = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		desiredMergePolicy.putArray("recordSelectionFunctionChain").addObject().put("function", "new");
		desiredMergePolicy.putArray("defaultFieldMergeFunctionChain").addObject().put("function", "longest");
		desiredMergePolicy.putArray("survivors");

		final MatchingConfigRewriteBuilder.Rewrite rewrite = MatchingConfigRewriteBuilder.build(existingMatchingPolicies, existingMergePolicies, "Company",
			"Company__preview", desiredMatchingPolicy, "Company", desiredMergePolicy, Collections.<String>emptySet());

		MatchingConfigRewriteBuilderTest.check("stranger entity untouched (2 entries left: Contact + rewritten Company)", rewrite.matchingPolicies.size() == 2);
		JsonNode stranger = null;
		JsonNode rewritten = null;
		for (final JsonNode entry : rewrite.matchingPolicies)
		{
			if ("Contact".equals(entry.path("processCode").asText()))
			{
				stranger = entry;
			}
			if ("Company".equals(entry.path("processCode").asText()))
			{
				rewritten = entry;
			}
		}
		MatchingConfigRewriteBuilderTest.check("the preview entry is gone", rewrite.matchingPolicies.size() == 2);
		MatchingConfigRewriteBuilderTest.check("Contact copied verbatim (its own stranger decisionTree untouched)",
			stranger != null && stranger.path("decisionTree").path("stranger").asBoolean(false));
		MatchingConfigRewriteBuilderTest.check("Company found and rewritten", rewritten != null);
		MatchingConfigRewriteBuilderTest.check("decisionTree replaced with the desired one", rewritten.path("decisionTree").path("fresh").asBoolean(false));
		MatchingConfigRewriteBuilderTest.check("fields replaced with the desired one",
			"/nameKey".equals(rewritten.path("fields").get(0).path("field").asText()));
		MatchingConfigRewriteBuilderTest.check("mergePolicyCode set to the desired code", "Company".equals(rewritten.path("mergePolicyCode").asText()));
		MatchingConfigRewriteBuilderTest.check("active KEPT from the stored entry (false, not the translator's own default true)",
			!rewritten.path("active").asBoolean(true));
		MatchingConfigRewriteBuilderTest.check("matchingOnCreation KEPT", rewritten.path("matchingOnCreation").asInt() == 1);
		MatchingConfigRewriteBuilderTest.check("executePurge KEPT", "MANUAL".equals(rewritten.path("executePurge").asText()));
		MatchingConfigRewriteBuilderTest.check("workflowOnCreate KEPT (an admin field this class never even names explicitly)",
			"X".equals(rewritten.path("workflowOnCreate").asText()));
	}

	private static void build_mergePolicyReplaced_keepsModeRelatedTablesUseForManualMerge() throws Exception
	{
		final JsonNode existingMatchingPolicies = MatchingConfigRewriteBuilderTest
			.parse("[{\"processCode\":\"Company\",\"mergePolicyCode\":\"Company\",\"decisionTree\":{},\"fields\":[]}]");
		final JsonNode existingMergePolicies = MatchingConfigRewriteBuilderTest.parse("[{\"code\":\"Company\",\"mode\":\"ONLY_DUPLICATES\","
			+ "\"useForManualMerge\":true,\"applyPermission\":false,\"relatedTables\":[{\"name\":\"Roles\"}],"
			+ "\"recordSelectionFunctionChain\":[{\"function\":\"old1\"},{\"function\":\"old2\"}],\"defaultFieldMergeFunctionChain\":[{\"function\":\"longest\"}],"
			+ "\"survivors\":[{\"field\":\"/sourceSystem\",\"mergeFunctionChain\":[{\"function\":\"constant\",\"constantValue\":\"MDM\"}]}]}]");

		final ObjectNode desiredMatchingPolicy = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		desiredMatchingPolicy.putObject("decisionTree");
		desiredMatchingPolicy.putArray("fields");
		final ObjectNode desiredMergePolicy = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		desiredMergePolicy.putArray("recordSelectionFunctionChain").addObject().put("function", "mostTrustedSource");
		desiredMergePolicy.putArray("defaultFieldMergeFunctionChain").addObject().put("function", "most trusted source");
		desiredMergePolicy.putArray("survivors").addObject().put("field", "/name");

		final MatchingConfigRewriteBuilder.Rewrite rewrite = MatchingConfigRewriteBuilder.build(existingMatchingPolicies, existingMergePolicies, "Company", null,
			desiredMatchingPolicy, "Company", desiredMergePolicy, Collections.<String>emptySet());

		MatchingConfigRewriteBuilderTest.check("one merge policy in the output", rewrite.mergePolicies.size() == 1);
		final JsonNode rewritten = rewrite.mergePolicies.get(0);
		MatchingConfigRewriteBuilderTest.check("mode KEPT from the stored entry", "ONLY_DUPLICATES".equals(rewritten.path("mode").asText()));
		MatchingConfigRewriteBuilderTest.check("useForManualMerge KEPT", rewritten.path("useForManualMerge").asBoolean(false));
		MatchingConfigRewriteBuilderTest.check("relatedTables KEPT", rewritten.path("relatedTables").size() == 1);
		MatchingConfigRewriteBuilderTest.check("recordSelectionFunctionChain REPLACED",
			rewritten.path("recordSelectionFunctionChain").size() == 1 && "mostTrustedSource".equals(rewritten.path("recordSelectionFunctionChain").get(0).path("function").asText()));
		MatchingConfigRewriteBuilderTest.check("survivors REPLACED", "/name".equals(rewritten.path("survivors").get(0).path("field").asText()));
	}

	/** Lot M1 (2026-10-17): a REWRITE of a table stored with the legacy {@code constant "MDM"} survivor writes the desired survivors AND the golden source. */
	private static void build_lotM1_goldenSourceReplacesTheLegacyConstantSurvivor() throws Exception
	{
		final JsonNode existingMatchingPolicies = MatchingConfigRewriteBuilderTest
			.parse("[{\"processCode\":\"Personne\",\"mergePolicyCode\":\"Personne\",\"decisionTree\":{},\"fields\":[]}]");
		final JsonNode existingMergePolicies = MatchingConfigRewriteBuilderTest.parse("[{\"code\":\"Personne\",\"mode\":\"ONLY_DUPLICATES\",\"autoCreateNewGoldenSource\":null,"
			+ "\"useForManualMerge\":true,\"relatedTables\":[],\"survivors\":[{\"field\":\"/sourceSystem\",\"mergeFunctionChain\":[{\"function\":\"constant\",\"constantValue\":\"MDM\"}]}]}]");
		final ObjectNode desiredMatchingPolicy = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		desiredMatchingPolicy.putObject("decisionTree");
		desiredMatchingPolicy.putArray("fields");
		final ObjectNode desiredMergePolicy = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		desiredMergePolicy.put("autoCreateNewGoldenSource", "MDM");
		desiredMergePolicy.putArray("recordSelectionFunctionChain").addObject().put("function", "mostTrustedSource");
		desiredMergePolicy.putArray("defaultFieldMergeFunctionChain").addObject().put("function", "most trusted source");
		desiredMergePolicy.putArray("survivors").addObject().put("field", "/sourceSystem").putArray("mergeFunctionChain").addObject().put("function", "no merge");
		final MatchingConfigRewriteBuilder.Rewrite rewrite = MatchingConfigRewriteBuilder.build(existingMatchingPolicies, existingMergePolicies, "Personne", null,
			desiredMatchingPolicy, "Personne", desiredMergePolicy, Collections.<String>emptySet());
		final JsonNode rewritten = rewrite.mergePolicies.get(0);
		MatchingConfigRewriteBuilderTest.check("the golden source is written", "MDM".equals(rewritten.path("autoCreateNewGoldenSource").asText()));
		MatchingConfigRewriteBuilderTest.check("the /sourceSystem survivor is no merge, not the stored constant",
			"no merge".equals(rewritten.path("survivors").get(0).path("mergeFunctionChain").get(0).path("function").asText())
				&& !rewritten.path("survivors").get(0).path("mergeFunctionChain").get(0).has("constantValue"));
		// a desired policy that names no golden source (never the translator's) leaves the stored value alone
		final ObjectNode withoutSource = desiredMergePolicy.deepCopy();
		withoutSource.remove("autoCreateNewGoldenSource");
		final MatchingConfigRewriteBuilder.Rewrite second = MatchingConfigRewriteBuilder.build(existingMatchingPolicies, existingMergePolicies, "Personne", null,
			desiredMatchingPolicy, "Personne", withoutSource, Collections.<String>emptySet());
		MatchingConfigRewriteBuilderTest.check("no desired golden source: the stored (null) one is kept as is", !second.mergePolicies.get(0).hasNonNull("autoCreateNewGoldenSource"));
	}

	/** See class Javadoc - the 2026-09-23 live defect (PipelineStep 108) and what this test proves about it. */
	private static void build_liveCompanyCase_sourceKeySurvivorSurvivesTheRewrite() throws Exception
	{
		// the stored ("morning") Company config: real, but its OWN survivors never had /supplierId at all -
		// the exact pre-lot shape the live gap was measured against.
		final JsonNode existingMatchingPolicies = MatchingConfigRewriteBuilderTest.parse("[{\"processCode\":\"Company\",\"mergePolicyCode\":\"Company\","
			+ "\"active\":true,\"decisionTree\":{\"old\":true},\"fields\":[{\"field\":\"/country\"}]}]");
		final JsonNode existingMergePolicies = MatchingConfigRewriteBuilderTest.parse("[{\"code\":\"Company\",\"mode\":\"ONLY_DUPLICATES\","
			+ "\"useForManualMerge\":true,\"applyPermission\":false,\"relatedTables\":[],"
			+ "\"recordSelectionFunctionChain\":[{\"function\":\"mame.addon.reference.data.recordSelectionPolicy.mostCompleted.label\"}],"
			+ "\"defaultFieldMergeFunctionChain\":[{\"function\":\"longest\"}],"
			+ "\"survivors\":[{\"field\":\"/sourceSystem\",\"mergeFunctionChain\":[{\"function\":\"constant\",\"constantValue\":\"MDM\"}]},"
			+ "{\"field\":\"/sourceId\",\"mergeFunctionChain\":[{\"function\":\"no merge\"}]},"
			+ "{\"field\":\"/_loadId\",\"mergeFunctionChain\":[{\"function\":\"no merge\"}]},"
			+ "{\"field\":\"/_receivedAt\",\"mergeFunctionChain\":[{\"function\":\"max\"}]}]}]");

		// the DESIRED shapes, built through the REAL translator exactly the way ebx.MatchingConfigurator.configureOne
		// does - one shared call, sourceKeyFieldNames = ["supplierId"] (what readSourceKeyFieldNames returns for
		// Company's own structurally-identified source-key attribute), used for BOTH the comparison and the rewrite.
		final JsonNode requirements = MatchingConfigRewriteBuilderTest.run31LikeRequirements();
		final ObjectNode desiredMatchingPolicy = MatchingPolicyTranslator.buildMatchingPolicy("Company", requirements, false);
		final ObjectNode desiredMergePolicy = MatchingPolicyTranslator.buildMergePolicy("Company", requirements, Collections.singletonList("supplierId"), Collections.<String> emptyList());

		final MatchingConfigRewriteBuilder.Rewrite rewrite = MatchingConfigRewriteBuilder.build(existingMatchingPolicies, existingMergePolicies, "Company", null,
			desiredMatchingPolicy, "Company", desiredMergePolicy, Collections.<String>emptySet());

		JsonNode supplierIdSurvivor = null;
		for (final JsonNode survivor : rewrite.mergePolicies.get(0).path("survivors"))
		{
			if ("/supplierId".equals(survivor.path("field").asText()))
			{
				supplierIdSurvivor = survivor;
			}
		}
		MatchingConfigRewriteBuilderTest.check("the rewritten survivors carry a /supplierId rule at all", supplierIdSurvivor != null);
		MatchingConfigRewriteBuilderTest.check("it is a 'no merge' rule, the source-key convention",
			"no merge".equals(supplierIdSurvivor.path("mergeFunctionChain").get(0).path("function").asText()));
	}

	/** {@code MatchingPolicyTranslator.buildMatchingPolicy}/{@code buildMergePolicy} both need SOME valid decisionTree/mergePolicy hints - the exact shape does not matter for this test, only that {@code sourceKeyFieldNames} makes it through {@code buildMergePolicy} unharmed. */
	private static JsonNode run31LikeRequirements() throws Exception
	{
		return MatchingConfigRewriteBuilderTest.parse("{" + "\"clusteringFields\":[\"nameKey\"],"
			+ "\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":80,\"fields\":["
			+ "{\"field\":\"name\",\"algorithm\":\"fuzzyFullText\",\"weight\":3}]," + "\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}]},"
			+ "\"mergePolicy\":{\"recordSelection\":\"lastUpdate\",\"defaultFieldMerge\":\"longest\"}" + "}");
	}

	private static void build_missingMatchingPolicyEntry_throws() throws Exception
	{
		final JsonNode existingMatchingPolicies = MatchingConfigRewriteBuilderTest.parse("[{\"processCode\":\"OtherEntity\"}]");
		final JsonNode existingMergePolicies = MatchingConfigRewriteBuilderTest.parse("[{\"code\":\"Company\"}]");
		final ObjectNode desired = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		boolean threw = false;
		try
		{
			MatchingConfigRewriteBuilder.build(existingMatchingPolicies, existingMergePolicies, "Company", null, desired, "Company", desired, Collections.<String>emptySet());
		}
		catch (final IllegalStateException ex)
		{
			threw = true;
		}
		MatchingConfigRewriteBuilderTest.check("no Company entry in matchingPolicies[]: refuses rather than build an incomplete rewrite", threw);
	}

	private static void build_missingMergePolicyEntry_throws() throws Exception
	{
		final JsonNode existingMatchingPolicies = MatchingConfigRewriteBuilderTest.parse("[{\"processCode\":\"Company\"}]");
		final JsonNode existingMergePolicies = MatchingConfigRewriteBuilderTest.parse("[{\"code\":\"OtherPolicy\"}]");
		final ObjectNode desired = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		boolean threw = false;
		try
		{
			MatchingConfigRewriteBuilder.build(existingMatchingPolicies, existingMergePolicies, "Company", null, desired, "Company", desired, Collections.<String>emptySet());
		}
		catch (final IllegalStateException ex)
		{
			threw = true;
		}
		MatchingConfigRewriteBuilderTest.check("no Company entry in mergePolicies[]: refuses rather than build an incomplete rewrite", threw);
	}

	// -------------------------------------------------------------------------------------------------------- cascade

	/**
	 * Cascade lot (2026-09-23, `docs/MATCHING.md` &sect;12.4/&sect;22): {@code Company}'s own merge policy carries three
	 * {@code relatedTables[]} entries as the add-on itself would leave them (every one {@code MANUAL} by
	 * default, CLAUDE.md &sect;8) - {@code /root/Contact} (a promoted CHILD entity of {@code Company}), {@code
	 * /root/Roles} (a table belonging to another project's model entirely - never touched), and {@code
	 * /root/Address} (NOT a child of THIS entity, even though it happens to be promoted in this project - it is
	 * not one of the paths the caller supplies). Only the {@code Contact} entry is expected to flip to {@code
	 * NONE} (lot MT, 2026-10-04: {@code RelatedTableAlignment.WANTED}).
	 */
	private static void build_cascade_childTableSetToAutomatic_nonChildKeptManual() throws Exception
	{
		final JsonNode existingMatchingPolicies = MatchingConfigRewriteBuilderTest
			.parse("[{\"processCode\":\"Company\",\"mergePolicyCode\":\"Company\",\"decisionTree\":{},\"fields\":[]}]");
		final JsonNode existingMergePolicies = MatchingConfigRewriteBuilderTest.parse("[{\"code\":\"Company\",\"mode\":\"ONLY_DUPLICATES\","
			+ "\"useForManualMerge\":true,\"applyPermission\":false,\"relatedTables\":["
			+ "{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"MANUAL\"},"
			+ "{\"name\":\"Roles\",\"path\":\"/root/Roles\",\"alignForeignKeys\":\"MANUAL\"},"
			+ "{\"name\":\"Address\",\"path\":\"/root/Address\",\"alignForeignKeys\":\"NONE\"}],"
			+ "\"recordSelectionFunctionChain\":[],\"defaultFieldMergeFunctionChain\":[],\"survivors\":[]}]");

		final ObjectNode desiredMatchingPolicy = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		desiredMatchingPolicy.putObject("decisionTree");
		desiredMatchingPolicy.putArray("fields");
		final ObjectNode desiredMergePolicy = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		desiredMergePolicy.putArray("recordSelectionFunctionChain");
		desiredMergePolicy.putArray("defaultFieldMergeFunctionChain");
		desiredMergePolicy.putArray("survivors");

		final Set<String> childTablePaths = new LinkedHashSet<String>();
		childTablePaths.add("/root/Contact");

		final MatchingConfigRewriteBuilder.Rewrite rewrite = MatchingConfigRewriteBuilder.build(existingMatchingPolicies, existingMergePolicies, "Company", null,
			desiredMatchingPolicy, "Company", desiredMergePolicy, childTablePaths);

		final JsonNode relatedTables = rewrite.mergePolicies.get(0).path("relatedTables");
		MatchingConfigRewriteBuilderTest.check("all three entries still present - never added or removed", relatedTables.size() == 3);
		for (final JsonNode entry : relatedTables)
		{
			final String path = entry.path("path").asText();
			if ("/root/Contact".equals(path))
			{
				MatchingConfigRewriteBuilderTest.check("Contact (a promoted child) flipped to NONE", "NONE".equals(entry.path("alignForeignKeys").asText()));
			}
			else if ("/root/Roles".equals(path))
			{
				MatchingConfigRewriteBuilderTest.check("Roles (a stranger table, not a child) stays MANUAL exactly as stored",
					"MANUAL".equals(entry.path("alignForeignKeys").asText()));
			}
			else if ("/root/Address".equals(path))
			{
				MatchingConfigRewriteBuilderTest.check("Address (promoted, but not THIS entity's child) stays NONE exactly as stored",
					"NONE".equals(entry.path("alignForeignKeys").asText()));
			}
			else
			{
				throw new AssertionError("unexpected relatedTables entry: " + entry);
			}
		}
	}

	/** Coordinator review, 2026-09-23: every entry's own {@code name}/{@code path} must survive the rewrite VERBATIM - only the enum changes on a promoted child's own path. */
	private static void build_cascade_namesAndPathsKeptVerbatim() throws Exception
	{
		final JsonNode existingMatchingPolicies = MatchingConfigRewriteBuilderTest
			.parse("[{\"processCode\":\"Company\",\"mergePolicyCode\":\"Company\",\"decisionTree\":{},\"fields\":[]}]");
		final JsonNode existingMergePolicies = MatchingConfigRewriteBuilderTest.parse("[{\"code\":\"Company\",\"mode\":\"ONLY_DUPLICATES\","
			+ "\"useForManualMerge\":true,\"applyPermission\":false,\"relatedTables\":["
			+ "{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"MANUAL\",\"someOtherKey\":\"kept too\"}],"
			+ "\"recordSelectionFunctionChain\":[],\"defaultFieldMergeFunctionChain\":[],\"survivors\":[]}]");

		final ObjectNode desiredMatchingPolicy = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		desiredMatchingPolicy.putObject("decisionTree");
		desiredMatchingPolicy.putArray("fields");
		final ObjectNode desiredMergePolicy = MatchingConfigRewriteBuilderTest.MAPPER.createObjectNode();
		desiredMergePolicy.putArray("recordSelectionFunctionChain");
		desiredMergePolicy.putArray("defaultFieldMergeFunctionChain");
		desiredMergePolicy.putArray("survivors");

		final Set<String> childTablePaths = Collections.singleton("/root/Contact");
		final MatchingConfigRewriteBuilder.Rewrite rewrite = MatchingConfigRewriteBuilder.build(existingMatchingPolicies, existingMergePolicies, "Company", null,
			desiredMatchingPolicy, "Company", desiredMergePolicy, childTablePaths);

		final JsonNode entry = rewrite.mergePolicies.get(0).path("relatedTables").get(0);
		MatchingConfigRewriteBuilderTest.check("name kept verbatim", "Contact".equals(entry.path("name").asText()));
		MatchingConfigRewriteBuilderTest.check("path kept verbatim", "/root/Contact".equals(entry.path("path").asText()));
		MatchingConfigRewriteBuilderTest.check("any other key on the entry kept verbatim too", "kept too".equals(entry.path("someOtherKey").asText()));
		MatchingConfigRewriteBuilderTest.check("only alignForeignKeys actually changed", "NONE".equals(entry.path("alignForeignKeys").asText()));
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return MatchingConfigRewriteBuilderTest.MAPPER.readTree(pJson);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
