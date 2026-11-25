package com.on.ebx.factory.ebx;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.master.MatchPlanRules;
import com.on.ebx.factory.master.MatchingPolicyTranslator;
import com.on.ebx.factory.master.RelationTierStripperTest;

/**
 * JUnit-free: run the main, a failure throws. Covers the PURE pieces of {@link MatchingPreviewPolicy} - the
 * ones that need only a hand-built {@code get_matching_config} JSON fixture, no live {@code Session}/{@code
 * McpClient} (this codebase never constructs a real one in a test - see {@code MatchingConfiguratorTest}'s
 * own class Javadoc for the same discipline). {@link MatchingPreviewPolicy#ensure}/{@link
 * MatchingPreviewPolicy#remove} themselves are NOT exercised here (they need a live add-on) - the module's
 * own final report lists that as a residual risk, same as every other write path in this package.
 * <p>
 * {@link MatchingPreviewPolicy#buildApplyConfigArgs} pins the {@code apply_matching_config} argument shape the
 * coordinator corrected 2026-09-22 22:10 from the LIVE tool signature - {@code config} NESTED, never {@code
 * matchingPolicies}/{@code mergePolicies} as bare top-level arguments (an earlier revision of this class sent
 * the flat shape, guessed by analogy with {@code generate_matching_policy} - wrong; see {@link
 * MatchingPreviewPolicy}'s own class Javadoc for the corrected contract in full).
 * <p>
 * {@link MatchingPreviewPolicy#applyPreviewSafetyOverrides} pins the SECOND live correction, same day (22:45):
 * an {@code active: false} preview answered "invalid state - matching paused or policy inactive" on the real
 * Company table - {@code simulateMatch} cannot evaluate an inactive policy at all. The preview is kept
 * harmless by its EXECUTION MODES instead ({@code matchingOnCreation}/{@code matchingOnUpdate} forced to
 * {@code 0} = MANUAL, `javap` on the add-on's {@code MatchingPolicy$ExecutionMode}), {@code active} left at
 * {@link com.on.ebx.factory.master.MatchingPolicyTranslator#buildMatchingPolicy}'s own default ({@code true}).
 * <p>
 * {@link MatchingPreviewPolicy#forFreshGenerate}/{@link MatchingPreviewPolicy#previewPolicyPresent} pin the
 * THIRD live correction, 2026-09-23 12:38 (PipelineStep 159, {@code /root/Contact}, docs/MATCHING.md &sect;25):
 * {@code generate_matching_policy} on a table with NO configuration refused outright ({@code LOCATOR_NOT_FOUND})
 * when {@code mergePolicyCode} named a merge policy that did not exist yet, and the old {@code
 * tolerateAlreadyExists} helper (removed) swallowed that soft miss - and the following {@code
 * generate_merge_policy}'s own soft miss too - as "already exists", ending the pipeline step {@code DONE} with
 * nothing actually written. {@link MatchingPreviewPolicy#ensureFresh} now mirrors {@code
 * MatchingConfigurator.configureOne}'s {@code GENERATE_FRESH} case exactly (generate without the link, generate
 * the merge policy, {@code patch_matching_config}'s {@code set_merge_policy_link}, each required to succeed) and
 * reads back {@code get_matching_config} before declaring success - {@link MatchingPreviewPolicy#ensure}/{@link
 * MatchingPreviewPolicy#remove} themselves still need a live add-on and are not exercised here.
 */
public final class MatchingPreviewPolicyTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchingPreviewPolicyTest.policyCode_isEntityNamePlusSuffix();
		MatchingPreviewPolicyTest.isPreviewCode_onlyMatchesTheSuffix();
		MatchingPreviewPolicyTest.isPreviewOnly_emptyAnswer_true();
		MatchingPreviewPolicyTest.isPreviewOnly_onlyPreviewEntries_true();
		MatchingPreviewPolicyTest.isPreviewOnly_aRealMatchingPolicy_false();
		MatchingPreviewPolicyTest.isPreviewOnly_aRealMergePolicyOnly_false();
		MatchingPreviewPolicyTest.isPreviewOnly_differentEntitysPreview_false();
		MatchingPreviewPolicyTest.firstNonPreviewCode_skipsPreviewEntries();
		MatchingPreviewPolicyTest.firstNonPreviewCode_allPreview_null();
		MatchingPreviewPolicyTest.firstNonPreviewCode_emptyList_null();
		MatchingPreviewPolicyTest.buildApplyConfigArgs_nestsUnderConfig_noDataModel();
		MatchingPreviewPolicyTest.buildApplyConfigArgs_dataModelStaysTopLevel_outsideConfig();
		MatchingPreviewPolicyTest.applyPreviewSafetyOverrides_activeTrueManualExecutionModes();
		MatchingPreviewPolicyTest.applyPreviewSafetyOverrides_overridesAnExistingNonManualValue();
		MatchingPreviewPolicyTest.forFreshGenerate_stripsMergePolicyCode_leavesOriginalUntouched();
		MatchingPreviewPolicyTest.forFreshGenerate_noMergePolicyCodeToBeginWith_noOp();
		MatchingPreviewPolicyTest.previewPolicyPresent_bothEntriesPresent_true();
		MatchingPreviewPolicyTest.previewPolicyPresent_matchingOnly_false();
		MatchingPreviewPolicyTest.previewPolicyPresent_mergeOnly_false();
		MatchingPreviewPolicyTest.previewPolicyPresent_softMiss_false();
		MatchingPreviewPolicyTest.previewPolicyPresent_nullJson_false();
		MatchingPreviewPolicyTest.removeAnswer_aPreviewThatStaysIsLeftNotGone();
		// lot V2-B (2026-09-30, docs/MATCHING.md sec 47): the preview goes through the same translation as the real policy
		MatchingPreviewPolicyTest.rootCause_plainTranslationNoLongerSendsTheChildFieldsAsParentFields();
		MatchingPreviewPolicyTest.translate_relationPlan_writtenAsBusinessObjectFields();
		MatchingPreviewPolicyTest.translate_noBusinessObject_fallsBackToTheStrippedPlanAndSaysWhy();
		MatchingPreviewPolicyTest.translate_unknownChildTable_fallsBackToTheStrippedPlan();
		MatchingPreviewPolicyTest.translate_stripRequested_neverWritesAChildField();
		MatchingPreviewPolicyTest.translate_masterModelUnreadable_fallsBackToTheStrippedPlan();
		MatchingPreviewPolicyTest.translate_everyTierIsRelation_refused();
		MatchingPreviewPolicyTest.translate_plainPlan_sameAsBuildMatchingPolicy_nothingSkipped();
		MatchingPreviewPolicyTest.carriesRelationFieldsOutsidePreview_liveVol100kPersonnePolicy_true();
		MatchingPreviewPolicyTest.carriesRelationFieldsOutsidePreview_plainPolicy_false();
		MatchingPreviewPolicyTest.carriesRelationFieldsOutsidePreview_onlyAPreviewCarriesThem_false();
		MatchingPreviewPolicyTest.carriesRelationFieldsOutsidePreview_nullOrMissingMode_false();
		System.out.println("MatchingPreviewPolicyTest OK");
	}

	// -------------------------------------------------------------------------------------------------- policyCode

	private static void policyCode_isEntityNamePlusSuffix()
	{
		MatchingPreviewPolicyTest.check("Company -> Company__preview", "Company__preview".equals(MatchingPreviewPolicy.policyCode("Company")));
	}

	private static void isPreviewCode_onlyMatchesTheSuffix()
	{
		MatchingPreviewPolicyTest.check("Company__preview is a preview code", MatchingPreviewPolicy.isPreviewCode("Company__preview"));
		MatchingPreviewPolicyTest.check("Company is not a preview code", !MatchingPreviewPolicy.isPreviewCode("Company"));
		MatchingPreviewPolicyTest.check("null is not a preview code", !MatchingPreviewPolicy.isPreviewCode(null));
		MatchingPreviewPolicyTest.check("a name that merely CONTAINS the suffix mid-string is not one (suffix, not substring)",
			!MatchingPreviewPolicy.isPreviewCode("Company__previewish"));
	}

	// ------------------------------------------------------------------------------------------------- isPreviewOnly

	/** CONFIG_NOT_FOUND-shaped soft miss - no matchingPolicies/mergePolicies field at all - reads as "nothing real", same as an explicit empty pair of arrays. */
	private static void isPreviewOnly_emptyAnswer_true() throws Exception
	{
		final JsonNode answer = MatchingPreviewPolicyTest.parse("{\"success\":false,\"code\":\"CONFIG_NOT_FOUND\"}");
		MatchingPreviewPolicyTest.check("no policies at all reads as preview-only (nothing real)", MatchingPreviewPolicy.isPreviewOnly(answer, "Company"));
	}

	private static void isPreviewOnly_onlyPreviewEntries_true() throws Exception
	{
		final JsonNode answer = MatchingPreviewPolicyTest.parse("{\"success\":true,\"data\":{"
			+ "\"matchingPolicies\":[{\"processCode\":\"Company__preview\"}],\"mergePolicies\":[{\"code\":\"Company__preview\"}]}}");
		MatchingPreviewPolicyTest.check("only a leftover preview on both sides: preview-only", MatchingPreviewPolicy.isPreviewOnly(answer, "Company"));
	}

	/** The exact interaction MatchingConfigurator.configureOne must not misread as "a stranger's config": a real Company policy alongside a leftover preview is NOT preview-only. */
	private static void isPreviewOnly_aRealMatchingPolicy_false() throws Exception
	{
		final JsonNode answer = MatchingPreviewPolicyTest.parse("{\"success\":true,\"data\":{"
			+ "\"matchingPolicies\":[{\"processCode\":\"Company\"},{\"processCode\":\"Company__preview\"}],"
			+ "\"mergePolicies\":[{\"code\":\"Company\"},{\"code\":\"Company__preview\"}]}}");
		MatchingPreviewPolicyTest.check("a real matching policy alongside the preview: not preview-only", !MatchingPreviewPolicy.isPreviewOnly(answer, "Company"));
	}

	/** A real MERGE policy alone (no real matching policy) is enough to disqualify "preview-only" too - either list carrying something real is enough. */
	private static void isPreviewOnly_aRealMergePolicyOnly_false() throws Exception
	{
		final JsonNode answer = MatchingPreviewPolicyTest.parse("{\"success\":true,\"data\":{"
			+ "\"matchingPolicies\":[{\"processCode\":\"Company__preview\"}],\"mergePolicies\":[{\"code\":\"Company\"}]}}");
		MatchingPreviewPolicyTest.check("a real merge policy alone: not preview-only", !MatchingPreviewPolicy.isPreviewOnly(answer, "Company"));
	}

	/** A preview policy for a DIFFERENT entity sharing the table (not expected in this codebase's own model, never assumed away - class Javadoc) must not be mistaken for THIS entity's own preview. */
	private static void isPreviewOnly_differentEntitysPreview_false() throws Exception
	{
		final JsonNode answer = MatchingPreviewPolicyTest.parse("{\"success\":true,\"data\":{"
			+ "\"matchingPolicies\":[{\"processCode\":\"Supplier__preview\"}],\"mergePolicies\":[]}}");
		MatchingPreviewPolicyTest.check("another entity's preview code is not OUR preview: not preview-only for Company",
			!MatchingPreviewPolicy.isPreviewOnly(answer, "Company"));
	}

	// -------------------------------------------------------------------------------------------- firstNonPreviewCode

	private static void firstNonPreviewCode_skipsPreviewEntries() throws Exception
	{
		final JsonNode policies = MatchingPreviewPolicyTest
			.parse("[{\"code\":\"Company__preview\"},{\"code\":\"Company\"},{\"code\":\"Other__preview\"}]");
		MatchingPreviewPolicyTest.check("finds the real code, skipping the preview ones", "Company".equals(MatchingPreviewPolicy.firstNonPreviewCode(policies, "code")));
	}

	private static void firstNonPreviewCode_allPreview_null() throws Exception
	{
		final JsonNode policies = MatchingPreviewPolicyTest.parse("[{\"code\":\"Company__preview\"}]");
		MatchingPreviewPolicyTest.check("every entry is a preview: null", MatchingPreviewPolicy.firstNonPreviewCode(policies, "code") == null);
	}

	private static void firstNonPreviewCode_emptyList_null() throws Exception
	{
		final JsonNode policies = MatchingPreviewPolicyTest.parse("[]");
		MatchingPreviewPolicyTest.check("empty list: null", MatchingPreviewPolicy.firstNonPreviewCode(policies, "code") == null);
	}

	// ------------------------------------------------------------------------------------------ buildApplyConfigArgs

	/**
	 * The exact live shape (coordinator, 2026-09-22 22:10): {@code table} and a NESTED {@code config} object
	 * carrying {@code matchingPolicies}/{@code mergePolicies} - never those two as bare top-level arguments,
	 * and no {@code dataModel} key at all when none is given (the tool's own {@code businessObject} is never
	 * sent by this class either way, so it is not asserted absent here - simply never written).
	 */
	private static void buildApplyConfigArgs_nestsUnderConfig_noDataModel() throws Exception
	{
		final ArrayNode matchingPolicies = (ArrayNode) MatchingPreviewPolicyTest.parse("[{\"processCode\":\"Company\"},{\"processCode\":\"Company__preview\"}]");
		final ArrayNode mergePolicies = (ArrayNode) MatchingPreviewPolicyTest.parse("[{\"code\":\"Company\"}]");
		final ObjectNode args = MatchingPreviewPolicy.buildApplyConfigArgs("/root/Company", null, matchingPolicies, mergePolicies);
		MatchingPreviewPolicyTest.check("table at top level", "/root/Company".equals(args.path("table").asText(null)));
		MatchingPreviewPolicyTest.check("no bare top-level matchingPolicies", args.path("matchingPolicies").isMissingNode());
		MatchingPreviewPolicyTest.check("no bare top-level mergePolicies", args.path("mergePolicies").isMissingNode());
		MatchingPreviewPolicyTest.check("no dataModel key when none given", args.path("dataModel").isMissingNode());
		final JsonNode config = args.path("config");
		MatchingPreviewPolicyTest.check("config is an object, not a string", config.isObject());
		MatchingPreviewPolicyTest.check("config.matchingPolicies carries both entries", config.path("matchingPolicies").size() == 2);
		MatchingPreviewPolicyTest.check("config.mergePolicies carries the one entry", config.path("mergePolicies").size() == 1);
	}

	/** {@code dataModel}, when given, stays a TOP-LEVEL argument alongside {@code table} - never inside {@code config} (the live signature lists it as its own parameter, not a {@code MameConfig} member). */
	private static void buildApplyConfigArgs_dataModelStaysTopLevel_outsideConfig() throws Exception
	{
		final ArrayNode empty = MatchingPreviewPolicyTest.MAPPER.createArrayNode();
		final ObjectNode args = MatchingPreviewPolicy.buildApplyConfigArgs("/root/Company", "Companies_Master", empty, empty);
		MatchingPreviewPolicyTest.check("dataModel is a top-level argument", "Companies_Master".equals(args.path("dataModel").asText(null)));
		MatchingPreviewPolicyTest.check("dataModel is NOT inside config", args.path("config").path("dataModel").isMissingNode());
	}

	// ------------------------------------------------------------------------------------------ applyPreviewSafetyOverrides

	/**
	 * Live refusal, 2026-09-22 22:45 (the product owner, real Company table): {@code simulateMatch} refused an {@code
	 * active: false} preview outright ("invalid state - matching paused or policy inactive"). The fix keeps the
	 * preview harmless through its EXECUTION MODES instead - both forced to {@code 0} (MANUAL, `javap` on the
	 * add-on's {@code MatchingPolicy$ExecutionMode}) - while leaving {@code active} at whatever the caller
	 * already set (this method never touches it, see its own Javadoc: {@code
	 * MatchingPolicyTranslator#buildMatchingPolicy} already defaults it to {@code true}, and re-asserting the
	 * literal here would risk silently overriding a future default change back to a stale value).
	 */
	private static void applyPreviewSafetyOverrides_activeTrueManualExecutionModes() throws Exception
	{
		final ObjectNode policy = (ObjectNode) MatchingPreviewPolicyTest
			.parse("{\"processCode\":\"Company__preview\",\"active\":true,\"matchingOnCreation\":1,\"matchingOnUpdate\":1}");
		MatchingPreviewPolicy.applyPreviewSafetyOverrides(policy, "Company");
		MatchingPreviewPolicyTest.check("active is left untouched (still true, from the caller)", policy.path("active").asBoolean(false));
		MatchingPreviewPolicyTest.check("matchingOnCreation forced to 0 (MANUAL)", policy.path("matchingOnCreation").asInt(-1) == 0);
		MatchingPreviewPolicyTest.check("matchingOnUpdate forced to 0 (MANUAL)", policy.path("matchingOnUpdate").asInt(-1) == 0);
		MatchingPreviewPolicyTest.check("mergePolicyCode set to the given link", "Company".equals(policy.path("mergePolicyCode").asText(null)));
	}

	/** Same overrides, but starting from {@code MatchingPolicyTranslator#buildMatchingPolicy}'s own real default execution modes (1 = AFTER_SUBMISSION, the live Company policy's own value) - the override must win over a non-MANUAL starting value, not just a blank one. */
	private static void applyPreviewSafetyOverrides_overridesAnExistingNonManualValue() throws Exception
	{
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Company__preview",
			MatchingPreviewPolicyTest.parse(MatchPlanRules.propose(java.util.Collections.singletonList(new MatchPlanRules.EntityInfo("Company",
				java.util.Collections.singletonList(new MatchPlanRules.AttributeInfo("siren", "STRING", null, "SIREN"))))).get(0).requirementsJson),
			false);
		MatchingPreviewPolicyTest.check("buildMatchingPolicy's own default is AFTER_SUBMISSION (1), confirming the override actually changes something",
			policy.path("matchingOnCreation").asInt(-1) == 1 && policy.path("matchingOnUpdate").asInt(-1) == 1);
		MatchingPreviewPolicy.applyPreviewSafetyOverrides(policy, "Company__preview");
		MatchingPreviewPolicyTest.check("matchingOnCreation overridden to 0 (MANUAL)", policy.path("matchingOnCreation").asInt(-1) == 0);
		MatchingPreviewPolicyTest.check("matchingOnUpdate overridden to 0 (MANUAL)", policy.path("matchingOnUpdate").asInt(-1) == 0);
		MatchingPreviewPolicyTest.check("active still true (buildMatchingPolicy's own default, never touched)", policy.path("active").asBoolean(false));
	}

	// ------------------------------------------------------------------------------------------------ forFreshGenerate

	/**
	 * The live bug, measured 2026-09-23 12:38 (PipelineStep 159, {@code /root/Contact}, docs/MATCHING.md
	 * &sect;25): {@code generate_matching_policy} on a table with NO configuration refuses outright ({@code
	 * LOCATOR_NOT_FOUND}) when {@code mergePolicyCode} names a merge policy that does not exist yet. {@link
	 * MatchingPreviewPolicy#ensureFresh} now strips it for that one call via {@link
	 * MatchingPreviewPolicy#forFreshGenerate} - this pins that the stripped copy really lacks the key, while the
	 * ORIGINAL object (still needed unchanged elsewhere - it is never re-derived) keeps it.
	 */
	private static void forFreshGenerate_stripsMergePolicyCode_leavesOriginalUntouched() throws Exception
	{
		final ObjectNode original = (ObjectNode) MatchingPreviewPolicyTest
			.parse("{\"processCode\":\"Company__preview\",\"active\":true,\"mergePolicyCode\":\"Company__preview\"}");
		final ObjectNode copy = MatchingPreviewPolicy.forFreshGenerate(original);
		MatchingPreviewPolicyTest.check("the copy has no mergePolicyCode", copy.path("mergePolicyCode").isMissingNode());
		MatchingPreviewPolicyTest.check("the copy still carries every other field", "Company__preview".equals(copy.path("processCode").asText(null)));
		MatchingPreviewPolicyTest.check("the ORIGINAL object is untouched (still carries mergePolicyCode)",
			"Company__preview".equals(original.path("mergePolicyCode").asText(null)));
	}

	/** A policy with no {@code mergePolicyCode} to begin with (should never happen in practice - {@link MatchingPreviewPolicy#applyPreviewSafetyOverrides} always sets it - but the helper must not throw either way): a harmless no-op. */
	private static void forFreshGenerate_noMergePolicyCodeToBeginWith_noOp() throws Exception
	{
		final ObjectNode original = (ObjectNode) MatchingPreviewPolicyTest.parse("{\"processCode\":\"Company__preview\"}");
		final ObjectNode copy = MatchingPreviewPolicy.forFreshGenerate(original);
		MatchingPreviewPolicyTest.check("still no mergePolicyCode", copy.path("mergePolicyCode").isMissingNode());
		MatchingPreviewPolicyTest.check("processCode preserved", "Company__preview".equals(copy.path("processCode").asText(null)));
	}

	// ---------------------------------------------------------------------------------------------- previewPolicyPresent

	/** The read-back {@link MatchingPreviewPolicy#ensureFresh} runs after its three write calls (class Javadoc, docs/MATCHING.md &sect;25): both entries present is the only shape that counts as success. */
	private static void previewPolicyPresent_bothEntriesPresent_true() throws Exception
	{
		final JsonNode answer = MatchingPreviewPolicyTest.parse("{\"success\":true,\"data\":{"
			+ "\"matchingPolicies\":[{\"processCode\":\"Contact__preview\"}],\"mergePolicies\":[{\"code\":\"Contact__preview\"}]}}");
		MatchingPreviewPolicyTest.check("both entries present: true", MatchingPreviewPolicy.previewPolicyPresent(answer, "Contact__preview"));
	}

	/**
	 * The EXACT failure shape measured live, 2026-09-23 12:38: {@code generate_matching_policy} answered a soft
	 * miss and never wrote anything, so a matching-policy-only (or, as measured, neither) read-back must not be
	 * mistaken for success just because the tool calls themselves did not transport-fail.
	 */
	private static void previewPolicyPresent_matchingOnly_false() throws Exception
	{
		final JsonNode answer = MatchingPreviewPolicyTest
			.parse("{\"success\":true,\"data\":{\"matchingPolicies\":[{\"processCode\":\"Contact__preview\"}],\"mergePolicies\":[]}}");
		MatchingPreviewPolicyTest.check("matching side only: false", !MatchingPreviewPolicy.previewPolicyPresent(answer, "Contact__preview"));
	}

	private static void previewPolicyPresent_mergeOnly_false() throws Exception
	{
		final JsonNode answer = MatchingPreviewPolicyTest
			.parse("{\"success\":true,\"data\":{\"matchingPolicies\":[],\"mergePolicies\":[{\"code\":\"Contact__preview\"}]}}");
		MatchingPreviewPolicyTest.check("merge side only: false", !MatchingPreviewPolicy.previewPolicyPresent(answer, "Contact__preview"));
	}

	/** The read-back call itself answering a soft miss (e.g. still {@code CONFIG_NOT_FOUND}, another facet of the same live bug) is never mistaken for presence. */
	private static void previewPolicyPresent_softMiss_false() throws Exception
	{
		final JsonNode answer = MatchingPreviewPolicyTest.parse("{\"success\":false,\"code\":\"CONFIG_NOT_FOUND\"}");
		MatchingPreviewPolicyTest.check("soft miss read-back: false", !MatchingPreviewPolicy.previewPolicyPresent(answer, "Contact__preview"));
	}

	/** A transport failure on the read-back call itself ({@code ToolResult.json == null}) is never mistaken for presence either. */
	private static void previewPolicyPresent_nullJson_false() throws Exception
	{
		MatchingPreviewPolicyTest.check("null json: false", !MatchingPreviewPolicy.previewPolicyPresent(null, "Contact__preview"));
	}

	// ------------------------------------------------------------------------------------- relation fields (V2-B)

	private static Map<String, String> adresseChild()
	{
		final Map<String, String> childInfo = new HashMap<String, String>();
		childInfo.put("Adresse", "/root/Adresse");
		return childInfo;
	}

	/** The table is the main table of a declared business object (what {@code MameBusinessObjects.resolve} answers when the master model was generated with one). */
	private static MameBusinessObjects.Scope declaredBusinessObject()
	{
		return new MameBusinessObjects.Scope("/root/Personne", Collections.singletonList("/root/Personne"), com.on.ebx.factory.master.BusinessObjectPlan.Route.BUSINESS_OBJECT);
	}

	/** No business object declared on the table (a master generated before lot W13). */
	private static MameBusinessObjects.Scope noBusinessObject()
	{
		return new MameBusinessObjects.Scope(null, new java.util.ArrayList<String>(), com.on.ebx.factory.master.BusinessObjectPlan.Route.REFUSE_MISSING_BUSINESS_OBJECT);
	}

	private static MatchingPreviewPolicy.TranslationInputs inputs(final String pRequirements, final String pStripReason, final Map<String, String> pChildPaths,
		final MameBusinessObjects.Scope pScope, final String pInputsError) throws Exception
	{
		return new MatchingPreviewPolicy.TranslationInputs(MatchingPreviewPolicyTest.parse(pRequirements), false, pStripReason, pChildPaths, pScope, pInputsError);
	}

	private static boolean hasField(final JsonNode pPolicy, final String pPath)
	{
		for (final JsonNode field : pPolicy.path("fields"))
		{
			if (pPath.equals(field.path("field").asText()))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean anyChildFieldWritten(final JsonNode pPolicy)
	{
		return MatchingPreviewPolicyTest.hasField(pPolicy, "/street") || MatchingPreviewPolicyTest.hasField(pPolicy, "/houseNumber")
			|| MatchingPreviewPolicyTest.hasField(pPolicy, "/postalCode") || MatchingPreviewPolicyTest.hasField(pPolicy, "/city");
	}

	/**
	 * The live failure of 2026-09-29 (Vol100k, PipelineStep 774): the preview policy was built by the plain translation, which declared {@code /street}
	 * as a field of Personne, and generate_matching_policy answered "Matching field '/street' does not resolve to a field of table /root/Personne".
	 * Since lot W13 the plain entry point REFUSES a plan that compares a child field (no child table known) instead of declaring it on the parent.
	 */
	private static void rootCause_plainTranslationNoLongerSendsTheChildFieldsAsParentFields() throws Exception
	{
		try
		{
			MatchingPolicyTranslator.buildMatchingPolicy("Personne__preview", MatchingPreviewPolicyTest.parse(RelationTierStripperTest.VOL100K_PERSONNE), false);
			throw new AssertionError("expected a refusal: the plain translation cannot place a child field");
		}
		catch (final IllegalStateException ex)
		{
			MatchingPreviewPolicyTest.check("refused, naming the child entity: " + ex.getMessage(), ex.getMessage().contains("child entity 'Adresse'"));
		}
	}

	private static boolean hasBusinessObjectField(final JsonNode pPolicy, final String pPath)
	{
		for (final JsonNode field : pPolicy.path("fields"))
		{
			if (pPath.equals(field.path("businessObjectField").asText(null)))
			{
				return true;
			}
		}
		return false;
	}

	private static void translate_relationPlan_writtenAsBusinessObjectFields() throws Exception
	{
		final MatchingPreviewPolicy.Translation translation = MatchingPreviewPolicy.translate("Personne__preview", MatchingPreviewPolicyTest.inputs(RelationTierStripperTest.VOL100K_PERSONNE,
			null, MatchingPreviewPolicyTest.adresseChild(), MatchingPreviewPolicyTest.declaredBusinessObject(), null));
		MatchingPreviewPolicyTest.check("the four child fields of P3/P4 are relation fields: " + translation.relationFields, translation.relationFields.size() == 4);
		MatchingPreviewPolicyTest.check("every one is the absolute path of the child's field", translation.relationFields.contains("/root/Adresse/street")
			&& translation.relationFields.contains("/root/Adresse/houseNumber") && translation.relationFields.contains("/root/Adresse/postalCode")
			&& translation.relationFields.contains("/root/Adresse/city"));
		MatchingPreviewPolicyTest.check("nothing skipped", translation.skippedTiers.isEmpty() && translation.skipReason == null);
		MatchingPreviewPolicyTest.check("no child field is written as a field of the parent", !MatchingPreviewPolicyTest.anyChildFieldWritten(translation.matchingPolicy));
		MatchingPreviewPolicyTest.check("declared with businessObjectField", MatchingPreviewPolicyTest.hasBusinessObjectField(translation.matchingPolicy, "/root/Adresse/street"));
		MatchingPreviewPolicyTest.check("the policy keeps its own id", "Personne__preview".equals(translation.matchingPolicy.path("processCode").asText()));
	}

	private static void translate_noBusinessObject_fallsBackToTheStrippedPlanAndSaysWhy() throws Exception
	{
		// a master generated before lot W13: no business object declared on the table
		final MatchingPreviewPolicy.Translation translation = MatchingPreviewPolicy.translate("Personne__preview", MatchingPreviewPolicyTest.inputs(RelationTierStripperTest.VOL100K_PERSONNE,
			null, MatchingPreviewPolicyTest.adresseChild(), MatchingPreviewPolicyTest.noBusinessObject(), null));
		MatchingPreviewPolicyTest.check("no relation field written", translation.relationFields.isEmpty());
		MatchingPreviewPolicyTest.check("both relation tiers reported: " + translation.skippedTiers, translation.skippedTiers.size() == 2);
		MatchingPreviewPolicyTest.check("the reason says what is missing: " + translation.skipReason,
			translation.skipReason != null && translation.skipReason.contains("declares no business object") && translation.skipReason.contains("generate the master model again"));
		MatchingPreviewPolicyTest.check("no child field is written", !MatchingPreviewPolicyTest.anyChildFieldWritten(translation.matchingPolicy));
		MatchingPreviewPolicyTest.check("the email tier is still simulated", MatchingPreviewPolicyTest.hasField(translation.matchingPolicy, "/emailKey"));
		// the same when the business object could not even be looked up
		final MatchingPreviewPolicy.Translation unknown = MatchingPreviewPolicy.translate("Personne__preview", MatchingPreviewPolicyTest.inputs(RelationTierStripperTest.VOL100K_PERSONNE,
			null, MatchingPreviewPolicyTest.adresseChild(), null, null));
		MatchingPreviewPolicyTest.check("no scope read: stripped as well", unknown.skippedTiers.size() == 2 && unknown.relationFields.isEmpty());
	}

	private static void translate_unknownChildTable_fallsBackToTheStrippedPlan() throws Exception
	{
		final MatchingPreviewPolicy.Translation translation = MatchingPreviewPolicy.translate("Personne__preview", MatchingPreviewPolicyTest.inputs(RelationTierStripperTest.VOL100K_PERSONNE,
			null, Collections.<String, String>emptyMap(), MatchingPreviewPolicyTest.declaredBusinessObject(), null));
		MatchingPreviewPolicyTest.check("stripped, with the translator's refusal as the reason: " + translation.skipReason,
			translation.skippedTiers.size() == 2 && translation.skipReason.contains("cannot be written to the add-on") && translation.skipReason.contains("no master table is known"));
	}

	private static void translate_stripRequested_neverWritesAChildField() throws Exception
	{
		final MatchingPreviewPolicy.Translation translation = MatchingPreviewPolicy.translate("Personne__preview", MatchingPreviewPolicyTest.inputs(RelationTierStripperTest.VOL100K_PERSONNE,
			"the relation fields could not be written to the add-on: boom", Collections.<String, String>emptyMap(), MatchingPreviewPolicyTest.declaredBusinessObject(), null));
		MatchingPreviewPolicyTest.check("skipped with the caller's reason", translation.skippedTiers.size() == 2 && translation.skipReason.endsWith("boom"));
		MatchingPreviewPolicyTest.check("no child field is written", !MatchingPreviewPolicyTest.anyChildFieldWritten(translation.matchingPolicy));
		MatchingPreviewPolicyTest.check("no relation field", translation.relationFields.isEmpty());
	}

	private static void translate_masterModelUnreadable_fallsBackToTheStrippedPlan() throws Exception
	{
		final MatchingPreviewPolicy.Translation translation = MatchingPreviewPolicy.translate("Personne__preview", MatchingPreviewPolicyTest.inputs(RelationTierStripperTest.VOL100K_PERSONNE,
			null, Collections.<String, String>emptyMap(), null, "the master model could not be read: x"));
		MatchingPreviewPolicyTest.check("stripped, with the read failure as the reason", translation.skippedTiers.size() == 2 && "the master model could not be read: x".equals(translation.skipReason));
	}

	private static void translate_everyTierIsRelation_refused() throws Exception
	{
		final String allRelation = "{\"decisionTree\":{\"root\":\"R\",\"nodes\":[{\"id\":\"R\",\"function\":\"ALL_FIELDS\",\"fields\":["
			+ "{\"field\":\"city\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}";
		try
		{
			MatchingPreviewPolicy.translate("Personne__preview", MatchingPreviewPolicyTest.inputs(allRelation, "x", Collections.<String, String>emptyMap(), null, null));
			throw new AssertionError("expected a refusal: nothing is left to simulate");
		}
		catch (final IllegalStateException ex)
		{
			MatchingPreviewPolicyTest.check("says why: " + ex.getMessage(), ex.getMessage().contains("nothing is left to simulate"));
		}
	}

	private static void translate_plainPlan_sameAsBuildMatchingPolicy_nothingSkipped() throws Exception
	{
		final String plain = "{\"decisionTree\":{\"root\":\"A\",\"nodes\":[{\"id\":\"A\",\"function\":\"ALL_FIELDS\",\"fields\":["
			+ "{\"field\":\"nameKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]},\"clusteringFields\":[\"nameKey\"]}";
		final MatchingPreviewPolicy.Translation translation = MatchingPreviewPolicy.translate("Company__preview", MatchingPreviewPolicyTest.inputs(plain, null,
			Collections.<String, String>emptyMap(), null, null));
		MatchingPreviewPolicyTest.check("identical to the ordinary translation",
			translation.matchingPolicy.equals(MatchingPolicyTranslator.buildMatchingPolicy("Company__preview", MatchingPreviewPolicyTest.parse(plain), false)));
		MatchingPreviewPolicyTest.check("nothing skipped, nothing relational", translation.skippedTiers.isEmpty() && translation.relationFields.isEmpty());
	}

	/** The Personne policy as get_matching_config answered it on 2026-09-30 (Vol100k, configured): four fields read through Adresse. */
	private static void carriesRelationFieldsOutsidePreview_liveVol100kPersonnePolicy_true() throws Exception
	{
		final JsonNode policies = MatchingPreviewPolicyTest.parse("[{\"processCode\":\"Personne\",\"fields\":["
			+ "{\"field\":\"/emailKey\",\"useRelationships\":\"NO\",\"location\":null},"
			+ "{\"field\":\"/street\",\"useRelationships\":\"BY_FOREIGN_KEY\",\"location\":{\"associationTable\":\"/root/Adresse/personneRef\"}}]}]");
		MatchingPreviewPolicyTest.check("a real policy with a BY_FOREIGN_KEY field must not go through apply_matching_config", MatchingPreviewPolicy.carriesRelationFieldsOutsidePreview(policies));
	}

	private static void carriesRelationFieldsOutsidePreview_plainPolicy_false() throws Exception
	{
		final JsonNode policies = MatchingPreviewPolicyTest.parse("[{\"processCode\":\"Company\",\"fields\":[{\"field\":\"/name\",\"useRelationships\":\"NO\"}]}]");
		MatchingPreviewPolicyTest.check("plain fields only", !MatchingPreviewPolicy.carriesRelationFieldsOutsidePreview(policies));
		MatchingPreviewPolicyTest.check("no policy at all", !MatchingPreviewPolicy.carriesRelationFieldsOutsidePreview(MatchingPreviewPolicyTest.MAPPER.createArrayNode()));
	}

	/** A preview's own relation fields do not block rewriting the preview: they are replaced by the same call. */
	private static void carriesRelationFieldsOutsidePreview_onlyAPreviewCarriesThem_false() throws Exception
	{
		final JsonNode policies = MatchingPreviewPolicyTest.parse("[{\"processCode\":\"Personne__preview\",\"fields\":["
			+ "{\"field\":\"/street\",\"useRelationships\":\"BY_FOREIGN_KEY\"}]}]");
		MatchingPreviewPolicyTest.check("preview only", !MatchingPreviewPolicy.carriesRelationFieldsOutsidePreview(policies));
	}

	private static void carriesRelationFieldsOutsidePreview_nullOrMissingMode_false() throws Exception
	{
		final JsonNode policies = MatchingPreviewPolicyTest.parse("[{\"processCode\":\"Company\",\"fields\":[{\"field\":\"/name\",\"useRelationships\":null},{\"field\":\"/city\"}]}]");
		MatchingPreviewPolicyTest.check("null/absent useRelationships means a plain field", !MatchingPreviewPolicy.carriesRelationFieldsOutsidePreview(policies));
	}

	/**
	 * Lot M2 (2026-10-17, docs/MATCHING.md section 57): live, the {@code Personne__preview} stayed on a person table that is the main table of a business object, and the answer
	 * of {@code remove} ({@code removed:false} plus prose) read like "nothing to remove". The answer now carries {@code left} + {@code leftReason}, and the pre-generate removal
	 * no longer records {@code true} whatever it answered.
	 */
	private static void removeAnswer_aPreviewThatStaysIsLeftNotGone() throws Exception
	{
		final ObjectNode left = MatchingPreviewPolicyTest.MAPPER.createObjectNode();
		left.put("removed", false);
		MatchingPreviewPolicy.markLeft(left, MatchingPreviewPolicy.LEFT_BUSINESS_OBJECT);
		MatchingPreviewPolicyTest.check("a preview that exists and stays is left", MatchingPreviewPolicy.wasLeft(left) && !MatchingPreviewPolicy.isGone(left));
		MatchingPreviewPolicyTest.check("the reason names the business object and the tools that cannot help",
			left.path("leftReason").asText().contains("business object") && left.path("leftReason").asText().contains("delete_matching_config"));
		final JsonNode removed = MatchingPreviewPolicyTest.parse("{\"removed\":true,\"note\":\"Preview policy X__preview removed from /root/X.\"}");
		MatchingPreviewPolicyTest.check("a removed preview is gone", MatchingPreviewPolicy.isGone(removed) && !MatchingPreviewPolicy.wasLeft(removed));
		final JsonNode nothing = MatchingPreviewPolicyTest.parse("{\"removed\":false,\"note\":\"No preview policy X__preview found on /root/X - nothing to remove.\"}");
		MatchingPreviewPolicyTest.check("nothing to remove is gone too, never reported as left", MatchingPreviewPolicy.isGone(nothing) && !MatchingPreviewPolicy.wasLeft(nothing));
		MatchingPreviewPolicyTest.check("null answers are neither", !MatchingPreviewPolicy.wasLeft(null) && !MatchingPreviewPolicy.isGone(null));
		final ObjectNode relation = MatchingPreviewPolicyTest.MAPPER.createObjectNode();
		MatchingPreviewPolicy.markLeft(relation, MatchingPreviewPolicy.LEFT_RELATION_FIELDS);
		MatchingPreviewPolicyTest.check("the relation-fields reason is its own", MatchingPreviewPolicy.wasLeft(relation) && relation.path("leftReason").asText().contains("relation fields"));
	}

	// ------------------------------------------------------------------------------------------------------- helpers

	private static JsonNode parse(final String pJson) throws Exception
	{
		return MatchingPreviewPolicyTest.MAPPER.readTree(pJson);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
