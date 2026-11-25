package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.master.DefaultMatchingPolicyGate;
import com.on.ebx.factory.master.BusinessObjectPlan;
import com.on.ebx.factory.master.MameAnswer;
import com.on.ebx.factory.master.MameDataModelResolution;
import com.on.ebx.factory.master.MasterFieldFilter;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.master.MatchingPolicyTranslator;
import com.on.ebx.factory.master.RelationTierStripper;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * simulation-preview-policy lot (2026-09-22): "Simulate on N pivots" on a matching plan that is not yet
 * {@code APPLIED} used to answer a flat refusal ({@code review.match.simulate.notApplied}) because the
 * add-on can only simulate a policy it already knows about ({@code MatchingOperations.simulateMatch}/{@code
 * executeDecisionTree}, both {@code ebx.MameMatchingApi}, take a {@code processCode}). the product owner's decision
 * ("Oui, c'est bien"): at each Simulate click on a PROPOSED/ACCEPTED plan, write the plan into the add-on as
 * a dedicated, INACTIVE preview policy - {@code <Entity>__preview} - run the simulation on IT instead of the
 * real (not-yet-existing, or stale) policy, and leave it there; it is rewritten on every subsequent click and
 * removed once the entity is actually accepted (see {@link #remove}, called by {@code
 * MatchingConfigurator.configureOne} right after a successful {@code markApplied}).
 * <p>
 * <b>Two very different starting points, per the task brief</b>:
 * <ul>
 * <li>{@link #ensureFresh} - the table has NO configuration at all yet ({@code get_matching_config} answers
 * {@code CONFIG_NOT_FOUND}, e.g. a plan simulated before its entity was ever accepted once): {@code
 * generate_matching_policy}/{@code generate_merge_policy} are the only tools that can write anything on a
 * table with zero configuration (CLAUDE.md &sect;8: {@code apply_matching_config} is a wholesale REPLACE of
 * an existing list, not a way to create the table's very first one) - both ADDITIVE, both under the preview
 * code, never touching the real {@code <Entity>} code this same table will carry once actually accepted. This
 * branch now mirrors {@code MatchingConfigurator.configureOne}'s own {@code GENERATE_FRESH} case EXACTLY
 * (docs/MATCHING.md &sect;25): {@code generate_matching_policy} is called WITHOUT {@code mergePolicyCode}
 * (see {@link #forFreshGenerate} and the bug this fixes, below), then {@code generate_merge_policy}, then a
 * {@code patch_matching_config} {@code set_merge_policy_link} op establishes the link, then a read-back
 * confirms both entries actually exist before this method reports success.</li>
 * <li>{@link #ensureRewrite} - the table already has SOME configuration ({@code get_matching_config} answers
 * a real config), whether that is a genuine {@code <Entity>} policy from a prior accept, a leftover preview
 * from an earlier Simulate click (or both). {@code apply_matching_config} is desired-state and replaces
 * {@code matchingPolicies}/{@code mergePolicies} WHOLESALE (CLAUDE.md &sect;8) - so the only safe way to
 * "rewrite just the preview" is to read the current lists, drop any existing {@code <Entity>__preview} entry
 * from each, add the freshly-built one back, and resend the WHOLE lists. The real {@code <Entity>} policy (if
 * one exists) is copied over completely unchanged - this is the one invariant the task brief calls out
 * explicitly ("never touch the real policy's merge link").</li>
 * </ul>
 * Either way the preview matching policy links to the table's EXISTING real merge policy when one exists
 * (read directly off {@code mergePolicies[]} - the naming convention every real merge policy this factory
 * ever writes follows is {@code MatchingPolicyTranslator.buildMergePolicy(entityName, ...)}, i.e. its own
 * {@code code} equals the entity name, but this class never assumes that: it takes whichever non-preview
 * {@code mergePolicies[]} entry it actually finds, first one, if any); only when the table carries NO real
 * merge policy at all does this class generate a second, equally-preview, merge policy under the SAME
 * {@code <Entity>__preview} code, purely so the preview matching policy has something to link to.
 * <p>
 * <b>{@code dataModel} discipline mirrors {@code MatchingConfigurator} exactly</b> (`docs/MATCHING.md` &sect;28,
 * 2026-09-23 14:20 correction): every call this class makes - {@link #remove} included, which used to send
 * {@code dataModel=null} unconditionally, a real gap rather than a deliberate omission - goes through {@code
 * ebx.MameCalls#call}, which sends {@code dataModel = }{@link MameDataModelResolution#publicationUrn(String)}
 * of the project's master model UNCONDITIONALLY, falling back to {@link MameDataModelResolution}'s own {@code
 * AMBIGUOUS_TABLE} candidate parsing only as a per-call retry-once safety net. The OLDER "resolve once from the
 * first {@code get_matching_config} read, thread the (possibly {@code null}) result through every following
 * call" discipline this note used to describe is exactly what broke live on this table (`ebx.MameCalls`'s own
 * class Javadoc has the full measurement).
 * <p>
 * <b>{@code apply_matching_config}'s exact argument shape was corrected by the coordinator, 2026-09-22 22:10,
 * from the LIVE tool signature</b> (not guessed, unlike almost everything else in this class): its real
 * parameters are {@code table} (required), {@code config} (required - "The full MameConfig to apply, same
 * shape {@code get_matching_config} returns … Nested JSON object or a JSON string encoding it"), an optional
 * {@code dataModel}, and {@code businessObject} (must be omitted - a non-blank value is rejected with {@code
 * INVALID_PARAMS}). So every call in this class sends {@code {table, config: {matchingPolicies:[...],
 * mergePolicies:[...]}}} - {@code config} NESTED, never {@code matchingPolicies}/{@code mergePolicies} as bare
 * top-level arguments (an earlier revision of this file guessed the flat shape, by analogy with {@code
 * generate_matching_policy}'s own bare {@code matchingPolicy} argument - wrong, fixed here). Per that same live
 * description: {@code matchingPolicies}/{@code mergePolicies} must be supplied TOGETHER or both omitted (this
 * class always supplies both); a {@code null} for either is {@code INVALID_PARAMS} - send {@code []} to clear
 * (this class never sends {@code null}, and never needs to clear both to empty); {@code tableSettings} is a
 * PARTIAL update and {@code trustedSources} members REPLACE when supplied (this class omits both, unaffected);
 * several members ({@code useRelationships}, {@code location}, {@code workflowOnCreate}/{@code Update}, {@code
 * pkFieldMergeFunction}, {@code builtIn}, and also {@code executePurge}/{@code showMatchType}) are read-only and
 * ignored on input - copying an existing real policy verbatim (this class' own "copied over VERBATIM" comment
 * below) is therefore safe even though it carries those fields; and "any key the model does not declare is
 * reported back on the successful result rather than dropped in silence" - logged into the caller's own detail/
 * result node by {@link #logIgnoredKeysIfAny} (the exact field name that report uses was not itself quoted to
 * this class - a best-effort guess, see that method's own Javadoc). Applying on an ALREADY-ACTIVATED table is
 * explicitly allowed by the same live description.
 * <p>
 * <b>The bug this fix closes, measured live 2026-09-23 12:38 (PipelineStep 159, table {@code /root/Contact},
 * {@code dataModel urn:ebx:publication:Companies_Master})</b> - docs/MATCHING.md &sect;25 has the full
 * transcript. {@link #ensureFresh} used to send {@code mergePolicyCode} (set by {@link
 * #applyPreviewSafetyOverrides}) straight into the FIRST {@code generate_matching_policy} call, naming a merge
 * policy ({@code Contact__preview}) that does not exist yet on a table with zero configuration. The add-on
 * refused with a soft miss - {@code LOCATOR_NOT_FOUND}, "No merge policy found for locator: Contact__preview",
 * whose own {@code nextStep} says plainly: "Omit mergePolicyCode, then create Contact__preview with
 * generate_merge_policy and wire the link with the set_merge_policy_link operation of
 * patch_matching_config." The old code never read that {@code nextStep} - its (now removed) {@code
 * tolerateAlreadyExists} helper treated ANY soft miss from {@code generate_matching_policy}/{@code
 * generate_merge_policy} as "already exists" (a premise that was never even
 * possible on this branch - {@link #ensureFresh} is only ever entered right after {@code get_matching_config}
 * itself answered {@code CONFIG_NOT_FOUND}) and swallowed it, so the very next {@code generate_merge_policy}
 * call also failed ({@code CONFIG_NOT_FOUND} - no matching configuration exists to attach a merge policy to
 * either, since the first call never created one), got swallowed the same way, and the step was marked
 * {@code DONE} with a success message even though NOTHING had been written. The caller ({@code
 * MatchingActivationGuard.ensureActivatedAfterPreviewEnsure}) then re-read {@code
 * get_matching_activation_status}, still saw {@code CONFIG_NOT_FOUND}, and refused the simulation with a
 * message calling out exactly that contradiction. Fixed by following the add-on's own instruction to the
 * letter - the same sequence {@code MatchingConfigurator.configureOne}'s {@code GENERATE_FRESH} case already
 * uses successfully: generate the matching policy WITHOUT the link ({@link #forFreshGenerate} strips {@code
 * mergePolicyCode} from the object {@link #applyPreviewSafetyOverrides} already stamped, so that method itself
 * stays unchanged and {@link #ensureRewrite} - which sends the whole config to {@code apply_matching_config} in
 * one shot and therefore CAN set the link inline - keeps working exactly as before), generate the merge policy,
 * link them with {@code patch_matching_config}'s {@code set_merge_policy_link} op, then re-read {@code
 * get_matching_config} and verify the preview policy is actually there ({@link #previewPolicyPresent}) before
 * declaring success - never again trust three soft-miss-tolerant calls in a row to mean "it worked".
 * <p>
 * <b>Still never exercised live</b> (task constraint: no EBX access this session, write tools forbidden either
 * way) beyond the argument shapes pinned above and this fix's own read-back check - this exact sequence is the
 * one already proven live by {@code MatchingConfigurator.configureOne}'s {@code GENERATE_FRESH} case (same
 * three calls, same order), so the risk here is narrower than before, but the preview-specific overrides
 * ({@link #applyPreviewSafetyOverrides}, {@link #forFreshGenerate}) were never themselves run against a live
 * add-on. See the module's own final report for the full list.
 * <p>
 * <b>2026-09-23 13:30, a SECOND live pass on the same Contact table (`docs/MATCHING.md` &sect;26), two more
 * gaps closed.</b> After the fix above shipped, `Simulate` on Contact reached {@code generate_merge_policy} for
 * real and it refused ({@code INVALID_PARAMS}, "Survivor field '/emailValid' does not resolve to a field of
 * table /root/Contact") - {@code emailValid} had only ever reached the STAGING zone, never {@code
 * master/generate}. {@link #ensureFresh}/{@link #ensureRewrite} now filter every merge policy they build through
 * {@link #dropSurvivorsNotOnMaster} before it reaches a write call - the same fix {@code
 * ebx.MatchingConfigurator#configureOne}/{@code #applySurvivorship} apply to a real merge policy, see {@code
 * MasterFieldFilter}'s own Javadoc. The SAME read-back also showed {@code tableSettings.defaultMatchingPolicy:
 * "Contact__preview"} - see {@link #applyPreviewSafetyOverrides}'s own corrected note for the measurement and
 * {@link #ensureFresh}/{@link #ensureRewrite}/{@link #remove}'s own comments for exactly where each now corrects
 * it via {@code ebx.MatchingConfigurator#ensureDefaultMatchingPolicy}. Neither gap was replayed live this
 * session either (same constraint) - fixed strictly from the add-on's own measured answers, unit-tested in
 * {@code MasterFieldFilterTest}/{@code DefaultMatchingPolicyGateTest}.
 */
public final class MatchingPreviewPolicy
{
	/** {@code <Entity>__preview} - a legal {@code processCode}/merge-policy {@code code} (plain identifier characters, matching every entity name this factory itself ever generates). */
	static final String PREVIEW_SUFFIX = "__preview";
	private static final String STEP_SIMULATE_PREVIEW = "MATCHING_SIMULATE_PREVIEW";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MatchingPreviewPolicy()
	{
	}

	/** {@code <Entity>__preview} - see class Javadoc for why this exact suffix. */
	public static String policyCode(final String pEntityName)
	{
		return pEntityName + MatchingPreviewPolicy.PREVIEW_SUFFIX;
	}

	/** {@code true} for any code this class itself could have written - the safety filter every reader of a {@code matchingPolicies}/{@code mergePolicies} list must apply (class Javadoc, task brief item 4). */
	public static boolean isPreviewCode(final String pCode)
	{
		return pCode != null && pCode.endsWith(MatchingPreviewPolicy.PREVIEW_SUFFIX);
	}

	/**
	 * The preview policy's own fixed safety shape as prose - {@code active=true} (measured live, 2026-09-22
	 * 22:45: {@code active:false} is NOT simulatable at all, {@link #applyPreviewSafetyOverrides}'s own
	 * Javadoc), execution modes forced to {@code MANUAL} (0) instead. Always the SAME text (both {@link
	 * #ensureFresh}/{@link #ensureRewrite} write the identical shape via {@link #applyPreviewSafetyOverrides})
	 * - kept as one constant so {@link Result#diagnosticSummary} and this class' own Javadoc never drift apart.
	 */
	static final String SAFETY_SHAPE_SUMMARY = "active=true, matchingOnCreation=0 (MANUAL), matchingOnUpdate=0 (MANUAL)";

	public static final class Result
	{
		public final String policyCode;
		/** Human-readable account of what {@link #ensure} actually did - included in the simulation panel's own "simulated on ..." header. */
		public final String note;
		/** {@link #SAFETY_SHAPE_SUMMARY} - appended to a {@code MameMatchingApi} refusal by {@code ebx.MatchPlanSimulationRunner} so a diagnosis names the exact policy shape that was rejected, not just the add-on's own generic refusal text. */
		public final String diagnosticSummary;
		/**
		 * self-heal-simulate-activation-order lot (2026-09-23), updated 2026-09-23 14:20 (`docs/MATCHING.md`
		 * &sect;28): the {@code dataModel} value used for every {@code generate_matching_policy}/{@code
		 * generate_merge_policy}/{@code apply_matching_config}/{@code get_matching_config} call this call made -
		 * always {@link MameDataModelResolution#publicationUrn(String)} of the project's master model now (never
		 * {@code null}/"omit" any more) - {@code ebx.MatchPlanSimulationRunner#simulate} reuses this SAME value
		 * for its own {@code get_matching_activation_status}/{@code activate_matching} calls right after, instead
		 * of independently re-deriving it - the real bug measured live 2026-09-23 07:45 (`docs/MATCHING.md`
		 * &sect;13.8): a table just written to under one resolved data model answered {@code CONFIG_NOT_FOUND} to
		 * a DIFFERENT, independently-resolved one.
		 */
		public final String dataModel;
		/** Lot V2-B: one entry per relation tier the preview does NOT carry ({@code "<node> (Adresse.street, ...)"}); empty when every tier is simulated. Never a silent skip: shown under the simulation. */
		public final List<String> skippedTiers;
		/** Lot V2-B: why {@link #skippedTiers} are skipped (non-null exactly when it is non-empty). */
		public final String skipReason;

		Result(final String pPolicyCode, final String pNote, final String pDataModel)
		{
			this(pPolicyCode, pNote, pDataModel, Collections.<String>emptyList(), null);
		}

		Result(final String pPolicyCode, final String pNote, final String pDataModel, final List<String> pSkippedTiers, final String pSkipReason)
		{
			this.policyCode = pPolicyCode;
			this.note = pNote;
			this.diagnosticSummary = MatchingPreviewPolicy.SAFETY_SHAPE_SUMMARY;
			this.dataModel = pDataModel;
			this.skippedTiers = pSkippedTiers;
			this.skipReason = pSkipReason;
		}
	}

	// ================================================================================================== ensure

	/**
	 * Writes (or rewrites) the preview policy for {@code pEntityName}'s CURRENT {@code pPlan.requirements} and
	 * returns its code, ready to hand straight to {@code ebx.MameMatchingApi#simulate}/{@code #evaluatePair} as
	 * the {@code processCode} argument. Called on every "Simulate on N pivots" click while the plan is not yet
	 * {@code APPLIED} - see {@code ebx.MatchPlanSimulationRunner#simulate}'s own call site.
	 *
	 * @throws OperationException the table's own add-on config could not be read or written (add-on not
	 *             declared on the master model yet, transport failure, {@code apply_matching_config}/{@code
	 *             generate_*} refused outright) - the SAME failure class {@code MatchingConfigurator.configureOne}
	 *             itself surfaces for the same calls.
	 */
	public static Result ensure(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName, final Adaptation pPlan)
		throws OperationException
	{
		return MatchingPreviewPolicy.ensure(pSession, pFactory, pProjectCode, pEntityName, pPlan, null);
	}

	/**
	 * Lot V2-B (2026-09-30, `docs/MATCHING.md` sec 47): the same as {@link #ensure}, but the preview is written from
	 * the plan WITHOUT its relation tiers ({@link RelationTierStripper}) - the fallback {@code
	 * ebx.MatchPlanSimulationRunner} takes when the relation fields of the full preview could not be linked in the add-on. The
	 * result's {@link Result#skippedTiers}/{@link Result#skipReason} say which tiers are not simulated and why,
	 * so the wizard never shows a simulation that silently dropped part of the plan.
	 *
	 * @param pReason why the relation tiers are dropped (shown to the reviewer); never blank
	 */
	public static Result ensureWithoutRelationTiers(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName,
		final Adaptation pPlan, final String pReason) throws OperationException
	{
		return MatchingPreviewPolicy.ensure(pSession, pFactory, pProjectCode, pEntityName, pPlan, pReason == null || pReason.trim().isEmpty() ? "not requested" : pReason);
	}

	private static Result ensure(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName, final Adaptation pPlan,
		final String pStripReasonOrNull) throws OperationException
	{
		final String tablePath = MasterModel.tablePath(pEntityName);
		final String previewCode = MatchingPreviewPolicy.policyCode(pEntityName);
		final McpClient client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);
		final String modelName = MasterNames.modelName(pProjectCode);

		// layer 1 (docs/MATCHING.md sec 28, measured live 2026-09-23 14:20, ebx.MatchingConfigurator's own class
		// Javadoc): dataModel is always urn:ebx:publication:<modelName> from this very first call - never omitted,
		// never resolved once from a read and reused - so a single get_matching_config call is enough here too.
		final String dataModel = MameDataModelResolution.publicationUrn(modelName);
		final ObjectNode getArgs = MatchingPreviewPolicy.MAPPER.createObjectNode();
		getArgs.put("table", tablePath);
		final McpClient.ToolResult getResult;
		try
		{
			getResult = MameCalls.call(client, "get_matching_config", getArgs, modelName);
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.matchingConfigRead", "get_matching_config failed while preparing a preview policy for " + tablePath + ": " + ex.getMessage(), ex, tablePath);
		}

		final boolean found = MameAnswer.success(getResult.json, false);
		final String code = MameAnswer.code(getResult.json);
		final JsonNode requirements = MatchingPreviewPolicy.parseRequirements(pPlan);
		final boolean autoMerge = Boolean.TRUE.equals(pPlan.get(FactoryPaths.MatchPlan.AUTO_MERGE));
		// 2026-09-23 fix, docs/MATCHING.md sec 19.10: the preview's own merge policy used to hardcode an EMPTY
		// source-key list (CLAUDE.md-worthy gap this coordinator follow-up closes) - it now resolves the same
		// way configureOne/applySurvivorship do, via the shared ebx.MatchingConfigurator.readSourceKeyFieldNames
		// (master.SourceKeyResolver underneath), so a preview simulation never omits a survivor rule the REAL
		// policy would carry once accepted.
		final String entityId = pPlan.getString(FactoryPaths.MatchPlan.ENTITY);
		// lot V2-B (2026-09-30): the preview's policy is translated by the SAME code as the real one, relation fields included
		// (MatchingPolicyTranslator.translate - native since lot W13, 2026-10-12: businessObjectField through the table's business object); a
		// plan comparing a child field used to be sent as if /street were a field of the parent table
		// (generate_matching_policy: "Matching field '/street' does not resolve to a field of table /root/Personne").
		final TranslationInputs inputs = MatchingPreviewPolicy.translationInputs(pFactory, client, pProjectCode, pEntityName, requirements, autoMerge, pStripReasonOrNull);

		if (!found)
		{
			if (!"CONFIG_NOT_FOUND".equals(code))
			{
				throw EngineRefusal.coded("conflict.noPreviewPolicy", "Cannot prepare a preview matching policy for " + tablePath + ": get_matching_config answered "
					+ (code == null ? "an unexpected soft miss" : code) + " - run master/generate first if the MAME add-on is not declared on the master model yet.", tablePath);
			}
			return MatchingPreviewPolicy.ensureFresh(pSession, pFactory, client, tablePath, dataModel, previewCode, inputs, entityId, pEntityName, pProjectCode);
		}
		return MatchingPreviewPolicy.ensureRewrite(pSession, pFactory, client, tablePath, dataModel, previewCode, getResult.json, inputs, entityId, pEntityName,
			pProjectCode);
	}

	/**
	 * The table has no configuration at all yet - the only tools that can write anything here are the additive
	 * {@code generate_matching_policy}/{@code generate_merge_policy} (class Javadoc), linked afterwards by
	 * {@code patch_matching_config}'s {@code set_merge_policy_link} op - the EXACT sequence {@code
	 * MatchingConfigurator.configureOne}'s {@code GENERATE_FRESH} case already uses, mirrored here on purpose
	 * (class Javadoc, "the bug this fix closes") after {@code generate_matching_policy} was measured live to
	 * refuse a {@code mergePolicyCode} naming a merge policy that does not exist yet ({@code LOCATOR_NOT_FOUND}).
	 * Every one of the three calls is required to succeed via {@code MatchingConfigurator.requireSuccess} (a
	 * hard transport error OR any soft-miss {@code success:false} fails this step outright - there is no
	 * tolerable soft miss on this branch any more, since {@link #ensureFresh} is only ever entered right after
	 * {@code get_matching_config} itself answered {@code CONFIG_NOT_FOUND}: "already exists" cannot legitimately
	 * happen here), and a final {@code get_matching_config} read-back ({@link #previewPolicyPresent}) confirms
	 * the preview policy is actually there before this method reports success - never again trust three
	 * successful-looking calls in a row without checking what they actually left behind.
	 */
	private static Result ensureFresh(final Session pSession, final Adaptation pFactory, final McpClient pClient, final String pTablePath, final String pDataModel,
		final String pPreviewCode, final TranslationInputs pInputs, final String pEntityId, final String pEntityName, final String pProjectCode)
		throws OperationException
	{
		final JsonNode pRequirements = pInputs.requirements;
		// docs/MATCHING.md sec 28: every call below goes through MameCalls (layer 1 always sends dataModel =
		// pDataModel, layer 2 retries once on a genuine AMBIGUOUS_TABLE) - the bare model name is what MameCalls
		// itself needs (to resolve a candidate list, not to reuse pDataModel's own already-full URN verbatim).
		final String modelName = MasterNames.modelName(pProjectCode);
		final String projectId = MatchingPreviewPolicy.findProjectId(pFactory, pProjectCode);
		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, MatchingPreviewPolicy.STEP_SIMULATE_PREVIEW, pEntityName);
		final ObjectNode detail = MatchingPreviewPolicy.MAPPER.createObjectNode();
		detail.put("branch", "fresh (CONFIG_NOT_FOUND) - generate_matching_policy (no mergePolicyCode) + generate_merge_policy"
			+ " + patch_matching_config(set_merge_policy_link), all under " + pPreviewCode + " (docs/MATCHING.md sec 25)");
		try
		{
			final Translation translation = MatchingPreviewPolicy.translate(pPreviewCode, pInputs);
			MatchingPreviewPolicy.recordTranslation(detail, translation);
			final ObjectNode previewMatchingPolicy = translation.matchingPolicy;
			// 2026-10-12 (add-on build 6.3.0.5-RC3): the preview policy is refused too when a tree field carries DO_NOTHING - same payload-time translation as configure
			MatchingConfigurator.translateTreeNullHandlingForLiveSchema(pClient, previewMatchingPolicy, pEntityName, detail);
			MatchingPreviewPolicy.applyPreviewSafetyOverrides(previewMatchingPolicy, pPreviewCode);

			// docs/MATCHING.md sec 25: generate_matching_policy on a table with NO configuration refuses outright
			// (LOCATOR_NOT_FOUND) when mergePolicyCode names a merge policy that does not exist yet - strip it for
			// THIS call, the real link is established below by patch_matching_config once generate_merge_policy has
			// actually created the merge policy.
			final ObjectNode generateMatchingArgs = MatchingPreviewPolicy.MAPPER.createObjectNode();
			generateMatchingArgs.put("table", pTablePath);
			generateMatchingArgs.set("matchingPolicy", MatchingPreviewPolicy.forFreshGenerate(previewMatchingPolicy));
			MameBusinessObjects.putBusinessObject(generateMatchingArgs, pInputs.scopeOrNull);
			final McpClient.ToolResult matchingResult = MameCalls.call(pClient, "generate_matching_policy", generateMatchingArgs, modelName);
			detail.set("generateMatchingPolicy", MatchingPreviewPolicy.toolDetail(generateMatchingArgs, matchingResult));
			MatchingConfigurator.requireSuccess(matchingResult, "generate_matching_policy");

			final List<String> sourceKeyFieldNamesNotes = new ArrayList<String>();
			final List<String> sourceKeyFieldNames = MatchingConfigurator.readSourceKeyFieldNames(pFactory, pEntityId, sourceKeyFieldNamesNotes);
			if (!sourceKeyFieldNamesNotes.isEmpty())
			{
				MatchingPreviewPolicy.putStrings(detail, "sourceKeyFieldNamesNotes", sourceKeyFieldNamesNotes);
			}
			// lot 1 (2026-09-23, quality-informed survivorship): same fourth list every buildMergePolicy caller now threads.
			final List<String> validValueFieldNames = MatchingConfigurator.readValidValueFieldNames(pFactory, pEntityId);
			final ObjectNode previewMergePolicy = MatchingPolicyTranslator.buildMergePolicy(pPreviewCode, pRequirements, sourceKeyFieldNames, validValueFieldNames);
			// master integration (2026-10-17, live TiersB2CRetestFinal): the preview must never be the table's manual-merge policy - the add-on allows
			// one per table, and the REAL policy's generate_merge_policy (useForManualMerge true) was then refused: "Merge policy <E>__preview is
			// already the manual-merge policy for this table", 0 entities configured, matching impossible after any Simulate click.
			previewMergePolicy.put("useForManualMerge", false);
			// lot M1 (2026-10-17): a preview never merges - no golden source to register (the real policy names it, MatchingPolicyTranslator.GOLDEN_SOURCE_CODE)
			previewMergePolicy.remove("autoCreateNewGoldenSource");
			MatchingPreviewPolicy.dropSurvivorsNotOnMaster(pFactory, pProjectCode, pEntityName, pTablePath, previewMergePolicy, detail);
			final ObjectNode generateMergeArgs = MatchingPreviewPolicy.MAPPER.createObjectNode();
			generateMergeArgs.put("table", pTablePath);
			generateMergeArgs.set("mergePolicy", previewMergePolicy);
			MameBusinessObjects.putBusinessObject(generateMergeArgs, pInputs.scopeOrNull);
			final McpClient.ToolResult mergeResult = MameCalls.call(pClient, "generate_merge_policy", generateMergeArgs, modelName);
			detail.set("generateMergePolicy", MatchingPreviewPolicy.toolDetail(generateMergeArgs, mergeResult));
			MatchingConfigurator.requireSuccess(mergeResult, "generate_merge_policy");

			// same set_merge_policy_link op MatchingConfigurator.configureOne's GENERATE_FRESH case sends, same shape.
			final ObjectNode patchArgs = MatchingPreviewPolicy.MAPPER.createObjectNode();
			patchArgs.put("table", pTablePath);
			final ArrayNode operations = patchArgs.putArray("operations");
			final ObjectNode linkOp = operations.addObject();
			linkOp.put("op", "set_merge_policy_link");
			final ObjectNode linkTarget = linkOp.putObject("target");
			linkTarget.put("policyCode", pPreviewCode);
			linkOp.put("value", pPreviewCode);
			MameBusinessObjects.putBusinessObject(patchArgs, pInputs.scopeOrNull);
			final McpClient.ToolResult patchResult = MameCalls.call(pClient, "patch_matching_config", patchArgs, modelName);
			detail.set("linkMergePolicy", MatchingPreviewPolicy.toolDetail(patchArgs, patchResult));
			MatchingConfigurator.requireSuccess(patchResult, "patch_matching_config (set_merge_policy_link)");

			// docs/MATCHING.md sec 25: three successful-looking calls in a row were exactly what the old
			// tolerateAlreadyExists bug hid behind - read back for real before declaring victory.
			final ObjectNode postGenerateArgs = MatchingPreviewPolicy.MAPPER.createObjectNode();
			postGenerateArgs.put("table", pTablePath);
			final McpClient.ToolResult postGenerateResult = MameCalls.call(pClient, "get_matching_config", postGenerateArgs, modelName);
			detail.set("getConfigAfterGenerate", MatchingPreviewPolicy.toolDetail(postGenerateArgs, postGenerateResult));
			if (!MatchingPreviewPolicy.previewPolicyPresent(postGenerateResult.json, pPreviewCode))
			{
				// caught by the OperationException handler right below, which records the FAILED step exactly once -
				// same discipline as ensureRewrite's own apply_matching_config failure a few lines further down.
				throw OperationException.createError("generate_matching_policy/generate_merge_policy/patch_matching_config all answered success for " + pTablePath
					+ ", but a read-back of get_matching_config right after does not show the preview policy " + pPreviewCode
					+ " in both matchingPolicies[] and mergePolicies[] - see detail.getConfigAfterGenerate.");
			}

			// default-matching-policy lot (2026-09-23 13:30, docs/MATCHING.md sec 26): generate_matching_policy just
			// made this table's own FIRST-EVER matching policy - the preview - its tableSettings.defaultMatchingPolicy.
			// No real policy exists on this branch by construction (ensureFresh only runs after CONFIG_NOT_FOUND), so
			// there is never a real code to fall back to - always CLEAR, never SET_REAL (class Javadoc, "the bug this
			// fix closes" no longer applies once corrected here). Hard failure, never best-effort: MatchingConfigurator
			// #ensureDefaultMatchingPolicy already verifies the write actually stuck.
			MatchingConfigurator.ensureDefaultMatchingPolicy(pClient, pTablePath, modelName, pPreviewCode, null, postGenerateResult.json, detail);

			final String note = "created via generate_matching_policy/generate_merge_policy/patch_matching_config (the table had no configuration at all yet)";
			PipelineSteps.end(pSession, pFactory, stepId, true, "Preview matching policy " + pPreviewCode + " generated for " + pTablePath + " - " + note,
				detail.toString());
			return new Result(pPreviewCode, note, pDataModel, translation.skippedTiers, translation.skipReason);
		}
		catch (final McpException ex)
		{
			detail.put("transportError", ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw EngineRefusal.codedWithCause("external.previewPolicyCall", "MCP call failed while creating the preview matching policy for " + pTablePath + ": " + ex.getMessage(), ex, pTablePath);
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final RuntimeException ex)
		{
			// MatchingPolicyTranslator's own pre-flight self-check throws IllegalStateException (same discipline as
			// MatchingConfigurator.configureOne's own identical catch, docs/MATCHING.md).
			final String message = "preview policy translation failed for " + pTablePath + ": " + ex.getMessage();
			detail.put("translationError", ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, message, detail.toString());
			throw EngineRefusal.codedWithCause("unexpected.previewPolicyFailed", message, ex);
		}
	}

	/** The table already carries SOME configuration - {@code apply_matching_config} desired-state rewrite, real policies copied over untouched (class Javadoc). */
	private static Result ensureRewrite(final Session pSession, final Adaptation pFactory, final McpClient pClient, final String pTablePath, final String pDataModel,
		final String pPreviewCode, final JsonNode pExistingAnswer, final TranslationInputs pInputs, final String pEntityId, final String pEntityName,
		final String pProjectCode) throws OperationException
	{
		final JsonNode pRequirements = pInputs.requirements;
		// docs/MATCHING.md sec 28: same discipline as ensureFresh - MameCalls needs the bare model name, not pDataModel's own already-full URN.
		final String modelName = MasterNames.modelName(pProjectCode);
		final String projectId = MatchingPreviewPolicy.findProjectId(pFactory, pProjectCode);
		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, MatchingPreviewPolicy.STEP_SIMULATE_PREVIEW, pEntityName);
		final ObjectNode detail = MatchingPreviewPolicy.MAPPER.createObjectNode();
		try
		{
			final JsonNode existingMatchingPolicies = MameAnswer.field(pExistingAnswer, "matchingPolicies");
			final JsonNode existingMergePolicies = MameAnswer.field(pExistingAnswer, "mergePolicies");
			final String realMergeCode = MatchingPreviewPolicy.firstNonPreviewCode(existingMergePolicies, "code");
			final String linkedMergeCode = realMergeCode != null ? realMergeCode : pPreviewCode;

			// lot V2-B (2026-09-30): apply_matching_config replaces the whole policy list and does not write a
			// relationship matching field (`useRelationships` is read-only: matching_config_schema). Copying a REAL policy
			// that carries relation fields back verbatim would rewrite them as plain fields of the parent ("/street does
			// not resolve") - refused here, before anything is sent, rather than risk the configured policy.
			final boolean inBusinessObject = pInputs.scopeOrNull != null && pInputs.scopeOrNull.inBusinessObject();
			if (BusinessObjectPlan.mayUseApplyMatchingConfig(inBusinessObject) && MatchingPreviewPolicy.carriesRelationFieldsOutsidePreview(existingMatchingPolicies))
			{
				throw EngineRefusal.coded("conflict.refused", MatchingPreviewPolicy.relationPolicyGuardMessage(pTablePath));
			}
			final Translation translation = MatchingPreviewPolicy.translate(pPreviewCode, pInputs);
			MatchingPreviewPolicy.recordTranslation(detail, translation);
			final ObjectNode previewMatchingPolicy = translation.matchingPolicy;
			// 2026-10-12 (add-on build 6.3.0.5-RC3): the preview policy is refused too when a tree field carries DO_NOTHING - same payload-time translation as configure
			MatchingConfigurator.translateTreeNullHandlingForLiveSchema(pClient, previewMatchingPolicy, pEntityName, detail);
			MatchingPreviewPolicy.applyPreviewSafetyOverrides(previewMatchingPolicy, linkedMergeCode);

			if (!BusinessObjectPlan.mayUseApplyMatchingConfig(inBusinessObject))
			{
				// a business-object table: apply_matching_config is refused there (measured on W13BO, 2026-10-12, with or without businessObject). The preview is written with the
				// policy tools instead: generate_matching_policy with businessObject and the preview's processCode REPLACES only that policy - the real policies are never touched,
				// so the guard above (which protects them from an apply of the whole list) does not apply.
				detail.put("route", BusinessObjectPlan.writeMode(true).name());
				final String linkNote;
				final ObjectNode generateMatchingArgs = MatchingPreviewPolicy.MAPPER.createObjectNode();
				generateMatchingArgs.put("table", pTablePath);
				generateMatchingArgs.set("matchingPolicy", realMergeCode == null ? MatchingPreviewPolicy.forFreshGenerate(previewMatchingPolicy) : previewMatchingPolicy);
				MameBusinessObjects.putBusinessObject(generateMatchingArgs, pInputs.scopeOrNull);
				final McpClient.ToolResult matchingResult = MameCalls.call(pClient, "generate_matching_policy", generateMatchingArgs, modelName);
				detail.set("generateMatchingPolicy", MatchingPreviewPolicy.toolDetail(generateMatchingArgs, matchingResult));
				MatchingConfigurator.requireSuccess(matchingResult, "generate_matching_policy");
				if (realMergeCode == null)
				{
					final List<String> sourceKeyFieldNames = MatchingConfigurator.readSourceKeyFieldNames(pFactory, pEntityId, new ArrayList<String>());
					final List<String> validValueFieldNames = MatchingConfigurator.readValidValueFieldNames(pFactory, pEntityId);
					final ObjectNode ownPreviewMergePolicy = MatchingPolicyTranslator.buildMergePolicy(pPreviewCode, pRequirements, sourceKeyFieldNames, validValueFieldNames);
			// master integration (2026-10-17, live TiersB2CRetestFinal): the preview must never be the table's manual-merge policy - the add-on allows
			// one per table, and the REAL policy's generate_merge_policy (useForManualMerge true) was then refused: "Merge policy <E>__preview is
			// already the manual-merge policy for this table", 0 entities configured, matching impossible after any Simulate click.
			ownPreviewMergePolicy.put("useForManualMerge", false);
			// lot M1 (2026-10-17): a preview never merges - no golden source to register (the real policy names it, MatchingPolicyTranslator.GOLDEN_SOURCE_CODE)
			ownPreviewMergePolicy.remove("autoCreateNewGoldenSource");
					MatchingPreviewPolicy.dropSurvivorsNotOnMaster(pFactory, pProjectCode, pEntityName, pTablePath, ownPreviewMergePolicy, detail);
					final ObjectNode generateMergeArgs = MatchingPreviewPolicy.MAPPER.createObjectNode();
					generateMergeArgs.put("table", pTablePath);
					generateMergeArgs.set("mergePolicy", ownPreviewMergePolicy);
					MameBusinessObjects.putBusinessObject(generateMergeArgs, pInputs.scopeOrNull);
					final McpClient.ToolResult mergeResult = MameCalls.call(pClient, "generate_merge_policy", generateMergeArgs, modelName);
					detail.set("generateMergePolicy", MatchingPreviewPolicy.toolDetail(generateMergeArgs, mergeResult));
					MatchingConfigurator.requireSuccess(mergeResult, "generate_merge_policy");
					final ObjectNode patchArgs = MatchingPreviewPolicy.MAPPER.createObjectNode();
					patchArgs.put("table", pTablePath);
					final ObjectNode linkOp = patchArgs.putArray("operations").addObject();
					linkOp.put("op", "set_merge_policy_link");
					linkOp.putObject("target").put("policyCode", pPreviewCode);
					linkOp.put("value", pPreviewCode);
					MameBusinessObjects.putBusinessObject(patchArgs, pInputs.scopeOrNull);
					final McpClient.ToolResult patchResult = MameCalls.call(pClient, "patch_matching_config", patchArgs, modelName);
					detail.set("linkMergePolicy", MatchingPreviewPolicy.toolDetail(patchArgs, patchResult));
					MatchingConfigurator.requireSuccess(patchResult, "patch_matching_config (set_merge_policy_link)");
					linkNote = "linked to its own preview merge policy " + pPreviewCode + " (this table has no real merge policy configured yet)";
				}
				else
				{
					linkNote = "linked to the table's existing merge policy " + realMergeCode;
				}
				MatchingConfigurator.ensureDefaultMatchingPolicy(pClient, pTablePath, modelName, pPreviewCode, realMergeCode, null, detail);
				final String boNote = linkNote + " (written with the policy tools: the table belongs to a business object)";
				PipelineSteps.end(pSession, pFactory, stepId, true, "Preview matching policy " + pPreviewCode + " (re)written for " + pTablePath + " - " + boNote, detail.toString());
				return new Result(pPreviewCode, boNote, pDataModel, translation.skippedTiers, translation.skipReason);
			}

			final ArrayNode desiredMatchingPolicies = MatchingPreviewPolicy.MAPPER.createArrayNode();
			for (final JsonNode policy : existingMatchingPolicies)
			{
				if (!pPreviewCode.equals(policy.path("processCode").asText(null)))
				{
					// the real policy (or any OTHER preview this table happens to carry, e.g. another entity sharing
					// the table - not expected in this codebase's own 1-table-per-entity model, but never assumed
					// away) is copied over VERBATIM - task brief "never touch the real policy's merge link".
					desiredMatchingPolicies.add(policy.deepCopy());
				}
			}
			desiredMatchingPolicies.add(previewMatchingPolicy);

			final ArrayNode desiredMergePolicies = MatchingPreviewPolicy.MAPPER.createArrayNode();
			for (final JsonNode policy : existingMergePolicies)
			{
				if (!pPreviewCode.equals(policy.path("code").asText(null)))
				{
					desiredMergePolicies.add(policy.deepCopy());
				}
			}
			final String note;
			if (realMergeCode == null)
			{
				final List<String> sourceKeyFieldNamesNotes = new ArrayList<String>();
				final List<String> sourceKeyFieldNames = MatchingConfigurator.readSourceKeyFieldNames(pFactory, pEntityId, sourceKeyFieldNamesNotes);
				if (!sourceKeyFieldNamesNotes.isEmpty())
				{
					MatchingPreviewPolicy.putStrings(detail, "sourceKeyFieldNamesNotes", sourceKeyFieldNamesNotes);
				}
				// lot 1 (2026-09-23, quality-informed survivorship): same fourth list every buildMergePolicy caller now threads.
				final List<String> validValueFieldNames = MatchingConfigurator.readValidValueFieldNames(pFactory, pEntityId);
				final ObjectNode ownPreviewMergePolicy = MatchingPolicyTranslator.buildMergePolicy(pPreviewCode, pRequirements, sourceKeyFieldNames, validValueFieldNames);
			// master integration (2026-10-17, live TiersB2CRetestFinal): the preview must never be the table's manual-merge policy - the add-on allows
			// one per table, and the REAL policy's generate_merge_policy (useForManualMerge true) was then refused: "Merge policy <E>__preview is
			// already the manual-merge policy for this table", 0 entities configured, matching impossible after any Simulate click.
			ownPreviewMergePolicy.put("useForManualMerge", false);
			// lot M1 (2026-10-17): a preview never merges - no golden source to register (the real policy names it, MatchingPolicyTranslator.GOLDEN_SOURCE_CODE)
			ownPreviewMergePolicy.remove("autoCreateNewGoldenSource");
				MatchingPreviewPolicy.dropSurvivorsNotOnMaster(pFactory, pProjectCode, pEntityName, pTablePath, ownPreviewMergePolicy, detail);
				desiredMergePolicies.add(ownPreviewMergePolicy);
				note = "linked to its own preview merge policy " + pPreviewCode + " (this table has no real merge policy configured yet)";
			}
			else
			{
				note = "linked to the table's existing merge policy " + realMergeCode;
			}

			final ObjectNode applyArgs = MatchingPreviewPolicy.buildApplyConfigArgs(pTablePath, pDataModel, desiredMatchingPolicies, desiredMergePolicies);
			final McpClient.ToolResult applyResult = MameCalls.call(pClient, "apply_matching_config", applyArgs, modelName);
			detail.set("apply", MatchingPreviewPolicy.toolDetail(applyArgs, applyResult));
			MatchingPreviewPolicy.logIgnoredKeysIfAny(applyResult, detail);
			if (applyResult.error || !MameAnswer.success(applyResult.json, true))
			{
				throw EngineRefusal.coded("external.previewPolicyWrite", "apply_matching_config failed while (re)writing the preview policy " + pPreviewCode + " for " + pTablePath + ": "
					+ (applyResult.error ? MatchingPreviewPolicy.shortText(applyResult.text) : MatchingPreviewPolicy.shortText(applyResult.json.toString())), pPreviewCode, pTablePath);
			}

			// default-matching-policy lot (2026-09-23 13:30, docs/MATCHING.md sec 26, coordinator's own precision on
			// this fix): the live table this bug was found against was ALREADY exactly in this branch's own starting
			// state ("preview policy only, no merge policy, defaultMatchingPolicy = Contact__preview") - so the very
			// NEXT Simulate click on it takes ensureRewrite, not ensureFresh, and must apply the identical correction.
			// realMergeCode (possibly null) is this table's real, non-preview merge-policy code if one exists - by
			// this factory's own convention (MatchingPolicyTranslator.buildMergePolicy's own caller contract) that is
			// always the entity name, the same "real code" ebx.MatchingConfigurator#configureOne threads through.
			MatchingConfigurator.ensureDefaultMatchingPolicy(pClient, pTablePath, modelName, pPreviewCode, realMergeCode, applyResult.json, detail);

			PipelineSteps.end(pSession, pFactory, stepId, true, "Preview matching policy " + pPreviewCode + " (re)written for " + pTablePath + " - " + note,
				detail.toString());
			return new Result(pPreviewCode, note, pDataModel, translation.skippedTiers, translation.skipReason);
		}
		catch (final McpException ex)
		{
			detail.put("transportError", ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw EngineRefusal.codedWithCause("external.previewPolicyCall", "MCP call failed while (re)writing the preview matching policy for " + pTablePath + ": " + ex.getMessage(), ex, pTablePath);
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final RuntimeException ex)
		{
			final String message = "preview policy translation failed for " + pTablePath + ": " + ex.getMessage();
			detail.put("translationError", ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, message, detail.toString());
			throw EngineRefusal.codedWithCause("unexpected.previewPolicyFailed", message, ex);
		}
	}

	// ============================================================================================ relation fields (V2-B)

	/** What {@link #translate} needs - resolved once per {@link #ensure} call, outside any pipeline step. */
	static final class TranslationInputs
	{
		final JsonNode requirements;
		final boolean autoMerge;
		/** Non-null: write the preview from the plan WITHOUT its relation tiers, for this reason. */
		final String stripReasonOrNull;
		/** Child entity ({@code via}) -&gt; its master table path, for the native relation fields. */
		final Map<String, String> childTablePaths;
		/** The business object the table belongs to (null = none read); relation fields are written only when {@link MameBusinessObjects.Scope#relationFieldsWritable}. */
		final MameBusinessObjects.Scope scopeOrNull;
		/** Non-null when the master model or the business object could not be read (recorded, then the relation tiers fall back to a strip). */
		final String inputsErrorOrNull;

		TranslationInputs(final JsonNode pRequirements, final boolean pAutoMerge, final String pStripReasonOrNull, final Map<String, String> pChildTablePaths,
			final MameBusinessObjects.Scope pScopeOrNull, final String pInputsErrorOrNull)
		{
			this.requirements = pRequirements;
			this.autoMerge = pAutoMerge;
			this.stripReasonOrNull = pStripReasonOrNull;
			this.childTablePaths = pChildTablePaths;
			this.scopeOrNull = pScopeOrNull;
			this.inputsErrorOrNull = pInputsErrorOrNull;
		}

		/** True when the relation (child) fields of the plan can be written: the table is the main table of a declared business object. */
		boolean relationFieldsWritable()
		{
			return this.scopeOrNull != null && this.scopeOrNull.relationFieldsWritable();
		}
	}

	/** {@link #translate}'s outcome: the preview policy (safety overrides NOT applied yet) and what the caller must say. */
	static final class Translation
	{
		final ObjectNode matchingPolicy;
		/** Absolute path of each relation field the preview carries (empty = none, or all stripped). */
		final List<String> relationFields;
		final List<String> skippedTiers;
		final String skipReason;

		Translation(final ObjectNode pMatchingPolicy, final List<String> pRelationFields, final List<String> pSkippedTiers, final String pSkipReason)
		{
			this.matchingPolicy = pMatchingPolicy;
			this.relationFields = pRelationFields;
			this.skippedTiers = pSkippedTiers;
			this.skipReason = pSkipReason;
		}
	}

	/**
	 * Reads what the translation needs: the child tables of this entity, and the business object the table belongs to (one probe per candidate:
	 * {@code MameBusinessObjects.resolve}) - always, since a table that is a member of a business object is written through it even when the plan
	 * compares no child field. A failure to read the master model or the add-on never fails the simulation: it is recorded and the relation tiers
	 * fall back to a strip (never a silent one: the reason reaches the wizard).
	 */
	private static TranslationInputs translationInputs(final Adaptation pFactory, final McpClient pClient, final String pProjectCode, final String pEntityName,
		final JsonNode pRequirements, final boolean pAutoMerge, final String pStripReasonOrNull)
	{
		final boolean hasRelation = RelationTierStripper.hasRelationFields(pRequirements);
		try
		{
			final String projectId = MatchingPreviewPolicy.findProjectId(pFactory, pProjectCode);
			final MasterModel.Result masterModel = MasterModel.buildFromStaging(MatchingConfigurator.readEntities(pFactory, projectId, new HashMap<String, String>()));
			final MameBusinessObjects.Scope scope = MameBusinessObjects.resolve(pClient, MasterNames.modelName(pProjectCode), masterModel, pEntityName, hasRelation,
				MatchingPreviewPolicy.MAPPER.createObjectNode());
			return new TranslationInputs(pRequirements, pAutoMerge, pStripReasonOrNull, MatchingConfigurator.relationChildTablePaths(masterModel, pEntityName), scope, null);
		}
		catch (final OperationException | McpException | RuntimeException ex)
		{
			return new TranslationInputs(pRequirements, pAutoMerge, pStripReasonOrNull, Collections.<String, String>emptyMap(), null,
				"the master model or its business objects could not be read: " + ex.getMessage());
		}
	}

	/**
	 * The ONE translation of a plan into the preview's {@code matchingPolicy} (pure, unit-tested): a plan without a child field goes through
	 * {@link MatchingPolicyTranslator#buildMatchingPolicy} exactly as before; a plan comparing child fields goes through {@link
	 * MatchingPolicyTranslator#translate}, the SAME native translation {@code MatchingConfigurator.configureOne} uses for the real policy (the table
	 * must be the main table of a declared business object) - sending the child field as a plain field of the parent is what failed live (Vol100k,
	 * 2026-09-29: "Matching field '/street' does not resolve to a field of table /root/Personne"). When that translation cannot be done (no business
	 * object declared on the master model, no child table known, master model unreadable) or the caller asks for it, the plan is translated without
	 * its relation tiers ({@link RelationTierStripper}) and the result says so.
	 *
	 * @throws IllegalStateException every tier of the plan compares child fields (nothing left to simulate), or the plan
	 *             is structurally invalid - the callers record it as a FAILED step
	 */
	static Translation translate(final String pPreviewCode, final TranslationInputs pInputs)
	{
		final JsonNode requirements = pInputs.requirements;
		if (!RelationTierStripper.hasRelationFields(requirements))
		{
			return new Translation(MatchingPolicyTranslator.buildMatchingPolicy(pPreviewCode, requirements, pInputs.autoMerge), Collections.<String>emptyList(),
				Collections.<String>emptyList(), null);
		}
		String stripReason = pInputs.stripReasonOrNull;
		if (stripReason == null && pInputs.inputsErrorOrNull != null)
		{
			stripReason = pInputs.inputsErrorOrNull;
		}
		if (stripReason == null && !pInputs.relationFieldsWritable())
		{
			stripReason = "the master model declares no business object whose main table is this entity's table (the add-on reads a child field only through one): "
				+ "generate the master model again to declare it";
		}
		if (stripReason == null)
		{
			try
			{
				final MatchingPolicyTranslator.TranslationResult relational = MatchingPolicyTranslator.translate(pPreviewCode, requirements, pInputs.autoMerge,
					pInputs.childTablePaths);
				return new Translation(relational.matchingPolicy, new ArrayList<String>(relational.relationFieldWeights.keySet()), Collections.<String>emptyList(), null);
			}
			catch (final IllegalStateException ex)
			{
				stripReason = "the relation fields cannot be written to the add-on: " + ex.getMessage();
			}
		}
		final RelationTierStripper.Result stripped = RelationTierStripper.strip(requirements);
		if (stripped.nothingLeft)
		{
			throw new IllegalStateException("every tier of this plan compares fields of a related table (" + stripped.skippedTiers + "), so nothing is left to simulate without them"
				+ " - " + stripReason);
		}
		return new Translation(MatchingPolicyTranslator.buildMatchingPolicy(pPreviewCode, stripped.requirements, pInputs.autoMerge), Collections.<String>emptyList(),
			stripped.skippedTiers, stripReason);
	}

	private static void recordTranslation(final ObjectNode pDetail, final Translation pTranslation)
	{
		if (!pTranslation.relationFields.isEmpty())
		{
			pDetail.put("relationFieldCount", pTranslation.relationFields.size());
		}
		if (!pTranslation.skippedTiers.isEmpty())
		{
			MatchingPreviewPolicy.putStrings(pDetail, "relationTiersNotSimulated", pTranslation.skippedTiers);
			pDetail.put("relationTiersNotSimulatedReason", pTranslation.skipReason);
		}
	}

	/**
	 * {@code true} when a policy of {@code pMatchingPolicies} (a {@code get_matching_config} {@code matchingPolicies[]}) that is NOT a preview
	 * has a matching field read through a related table ({@code useRelationships} other than {@code NO}). Such a table must never go through
	 * {@code apply_matching_config}: it replaces the whole list, ignores {@code useRelationships}/{@code location} on input (matching_config_schema:
	 * read-only) and would write the policy's relation fields back as plain fields of the parent - the same "does not resolve" refusal at best.
	 */
	static boolean carriesRelationFieldsOutsidePreview(final JsonNode pMatchingPolicies)
	{
		for (final JsonNode policy : pMatchingPolicies)
		{
			if (MatchingPreviewPolicy.isPreviewCode(policy.path("processCode").asText(null)))
			{
				continue;
			}
			for (final JsonNode field : policy.path("fields"))
			{
				final String mode = field.path("useRelationships").asText("NO");
				if (!mode.isEmpty() && !"NO".equals(mode) && !"null".equals(mode))
				{
					return true;
				}
			}
		}
		return false;
	}

	static String relationPolicyGuardMessage(final String pTablePath)
	{
		return "Cannot write a preview policy for " + pTablePath + ": the table already carries a configured matching policy with relation fields (fields read through a related table), "
			+ "and rewriting the policy list would turn them into plain fields of the parent and break them. Configure this plan (Configure matching) to simulate it on the real policy.";
	}

	// ================================================================================================== remove

	/**
	 * Drops {@code <Entity>__preview} from {@code matchingPolicies}/{@code mergePolicies} (whichever of the two
	 * actually carries it - a fresh-table preview leaves BOTH under the preview code, a rewritten one may leave
	 * only the matching side if a real merge policy was linked instead) via {@code apply_matching_config}.
	 * Idempotent: nothing to remove (table not found, no configuration, or no entry actually named
	 * {@code <Entity>__preview}) is a no-op, never an error - called both by {@code
	 * MatchingConfigurator.configureOne} right after a successful accept (best-effort: a failure here must never
	 * undo an otherwise-successful {@code configure}) and by the stand-alone cleanup route ({@code
	 * POST /projects/{code}/matching/simulate-preview/remove?entity=}).
	 */
	public static ObjectNode remove(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName) throws OperationException
	{
		final String tablePath = MasterModel.tablePath(pEntityName);
		final String previewCode = MatchingPreviewPolicy.policyCode(pEntityName);
		final McpClient client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);
		// docs/MATCHING.md sec 28: this method used to pass dataModel=null on every call, unconditionally - a real
		// gap (never even attempted resolution) rather than a deliberate omission - now closed the same way as
		// ensure/ensureFresh/ensureRewrite: every call carries the project's own dataModel via MameCalls.
		final String modelName = MasterNames.modelName(pProjectCode);

		final ObjectNode getArgs = MatchingPreviewPolicy.MAPPER.createObjectNode();
		getArgs.put("table", tablePath);
		final McpClient.ToolResult getResult;
		try
		{
			getResult = MameCalls.call(client, "get_matching_config", getArgs, modelName);
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.matchingConfigRead", "get_matching_config failed while removing the preview policy for " + tablePath + ": " + ex.getMessage(), ex, tablePath);
		}
		final ObjectNode out = MatchingPreviewPolicy.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("entity", pEntityName);
		out.put("policyCode", previewCode);
		if (!MameAnswer.success(getResult.json, false))
		{
			out.put("removed", false);
			out.put("note", "No configuration at all exists for " + tablePath + " - nothing to remove.");
			return out;
		}

		final JsonNode existingMatchingPolicies = MameAnswer.field(getResult.json, "matchingPolicies");
		final JsonNode existingMergePolicies = MameAnswer.field(getResult.json, "mergePolicies");
		boolean foundInMatching = false;
		final ArrayNode desiredMatchingPolicies = MatchingPreviewPolicy.MAPPER.createArrayNode();
		for (final JsonNode policy : existingMatchingPolicies)
		{
			if (previewCode.equals(policy.path("processCode").asText(null)))
			{
				foundInMatching = true;
			}
			else
			{
				desiredMatchingPolicies.add(policy.deepCopy());
			}
		}
		boolean foundInMerge = false;
		final ArrayNode desiredMergePolicies = MatchingPreviewPolicy.MAPPER.createArrayNode();
		for (final JsonNode policy : existingMergePolicies)
		{
			if (previewCode.equals(policy.path("code").asText(null)))
			{
				foundInMerge = true;
			}
			else
			{
				desiredMergePolicies.add(policy.deepCopy());
			}
		}
		if (!foundInMatching && !foundInMerge)
		{
			out.put("removed", false);
			out.put("note", "No preview policy " + previewCode + " found on " + tablePath + " - nothing to remove.");
			return out;
		}
		// lot W13 (2026-10-12): apply_matching_config is refused on a table that belongs to a business object (measured on W13BO) and no policy tool deletes one policy:
		// the preview stays (MANUAL, never the default policy) and the note says so.
		if (!BusinessObjectPlan.mayUseApplyMatchingConfig(!MameBusinessObjects.declaredMainsOf(pFactory, client, pProjectCode, modelName, tablePath).isEmpty()))
		{
			out.put("removed", false);
			MatchingPreviewPolicy.markLeft(out, MatchingPreviewPolicy.LEFT_BUSINESS_OBJECT);
			out.put("note", "Preview policy " + previewCode + " left on " + tablePath + ": the table belongs to a business object, whose policies cannot be rewritten as a list (apply_matching_config is refused there).");
			return out;
		}
		// lot V2-B (2026-09-30): removing goes through apply_matching_config (whole list replaced), which would write the REAL
		// policy's relation fields back as plain fields - see carriesRelationFieldsOutsidePreview. The preview stays (MANUAL,
		// never the default policy); the next configure of the entity drops it (MatchingConfigRewriteBuilder).
		if (MatchingPreviewPolicy.carriesRelationFieldsOutsidePreview(desiredMatchingPolicies))
		{
			out.put("removed", false);
			MatchingPreviewPolicy.markLeft(out, MatchingPreviewPolicy.LEFT_RELATION_FIELDS);
			out.put("note", "Preview policy " + previewCode + " left on " + tablePath + ": the table's configured policy carries relation fields and rewriting the policy list would break them.");
			return out;
		}

		// default-matching-policy lot (2026-09-23 13:30, docs/MATCHING.md sec 26, coordinator's own precision): if
		// the table's own tableSettings.defaultMatchingPolicy names the preview being removed, fix it BEFORE the
		// removal apply below - set_table_settings refuses a non-existent code, and it is not known whether removing
		// a policy that is the table's own default would itself be refused, so the safe order is default-first. The
		// remaining real merge-policy code (if any, desiredMergePolicies already excludes the preview) is the
		// fallback; none means CLEAR. Uses getResult.json (the state BEFORE removal) to decide, never a guess.
		final String remainingRealCode = MatchingPreviewPolicy.firstNonPreviewCode(desiredMergePolicies, "code");
		try
		{
			MatchingConfigurator.ensureDefaultMatchingPolicy(client, tablePath, modelName, previewCode, remainingRealCode, getResult.json, out);
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.previewPolicyWrite", "set_table_settings failed while fixing " + tablePath + "'s own defaultMatchingPolicy before removing the "
				+ "preview policy " + previewCode + ": " + ex.getMessage(), ex, previewCode, tablePath);
		}

		// dataModel left null here (MameCalls.call sets the real value from modelName unconditionally below) -
		// buildApplyConfigArgs' OWN dataModel argument stays for the case a future caller already knows a
		// different resolved value in hand (docs/MATCHING.md sec 28: MameCalls always overwrites it regardless).
		final ObjectNode applyArgs = MatchingPreviewPolicy.buildApplyConfigArgs(tablePath, null, desiredMatchingPolicies, desiredMergePolicies);
		final McpClient.ToolResult applyResult;
		try
		{
			applyResult = MameCalls.call(client, "apply_matching_config", applyArgs, modelName);
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.codedWithCause("external.previewPolicyWrite", "apply_matching_config failed while removing the preview policy " + previewCode + " for " + tablePath + ": "
				+ ex.getMessage(), ex, previewCode, tablePath);
		}
		MatchingPreviewPolicy.logIgnoredKeysIfAny(applyResult, out);
		if (applyResult.error || !MameAnswer.success(applyResult.json, true))
		{
			throw EngineRefusal.coded("external.previewPolicyWrite", "apply_matching_config failed while removing the preview policy " + previewCode + " for " + tablePath + ": "
				+ (applyResult.error ? MatchingPreviewPolicy.shortText(applyResult.text) : MatchingPreviewPolicy.shortText(applyResult.json.toString())), previewCode, tablePath);
		}
		out.put("removed", true);
		out.put("note", "Preview policy " + previewCode + " removed from " + tablePath + ".");
		return out;
	}

	/** Why a preview that exists could not be removed: the table belongs to a business object (no tool removes ONE policy there: {@code apply_matching_config} is refused, {@code delete_matching_config} answers INVALID_PARAMS "delete it from that configuration in the UI"). */
	static final String LEFT_BUSINESS_OBJECT = "the table belongs to a business object and no Match and Merge tool removes one policy of such a table (apply_matching_config is refused there, delete_matching_config says to use the administration screen)";
	/** Why a preview that exists could not be removed: rewriting the policy list would break the real policy's relation fields. */
	static final String LEFT_RELATION_FIELDS = "rewriting the policy list would break the relation fields of the table's real policy";

	/**
	 * Lot M2 (2026-10-17): a preview that EXISTS and was not removed says so in the answer ({@code left: true}, {@code leftReason}) instead of only in a prose {@code note} -
	 * the configure job reads it and reports it ({@code MatchingConfigurator}), where the plain {@code removed: false} used to read like "nothing to remove". Live: the
	 * {@code Personne__preview} of a person with child entities stayed on the table after every configure (the table is the main table of a business object), and the step
	 * detail said {@code removed:false} with no one reading it.
	 */
	static void markLeft(final ObjectNode pAnswer, final String pReason)
	{
		pAnswer.put("left", true);
		pAnswer.put("leftReason", pReason);
	}

	/** {@code true} when the answer of {@link #remove} says a preview exists and stays. */
	public static boolean wasLeft(final JsonNode pRemoveAnswer)
	{
		return pRemoveAnswer != null && pRemoveAnswer.path("left").asBoolean(false);
	}

	/** {@code true} when the answer of {@link #remove} says the preview is gone, or never was there. */
	public static boolean isGone(final JsonNode pRemoveAnswer)
	{
		return pRemoveAnswer != null && !MatchingPreviewPolicy.wasLeft(pRemoveAnswer);
	}

	/**
	 * {@code true} when every {@code matchingPolicies[]}/{@code mergePolicies[]} entry {@code
	 * pAnswer} (a {@code get_matching_config} answer for {@code pEntityName}'s own table) carries is this
	 * entity's own preview code - i.e. nothing REAL is configured on the table, whether because {@code
	 * pAnswer} answered {@code CONFIG_NOT_FOUND} to begin with (empty lists, trivially "preview only") or
	 * because the only thing ever written there is a leftover preview from an earlier Simulate click.
	 * <p>
	 * Used by {@code MatchingConfigurator.configureOne} to tell an actual stranger's configuration (refuse,
	 * same as always) apart from "nothing but our own throwaway preview" (proceed exactly as if {@code
	 * CONFIG_NOT_FOUND} had been answered) - without this, the very first successful {@code ensure} call for
	 * an entity would permanently jam that entity's real {@code matching/configure} behind the "refusing to
	 * overwrite a configuration this generator did not create" guard forever, since nothing about a preview
	 * policy ever marks this project's own {@code MATCHING_CONFIG} step {@code DONE}.
	 */
	public static boolean isPreviewOnly(final JsonNode pAnswer, final String pEntityName)
	{
		final String previewCode = MatchingPreviewPolicy.policyCode(pEntityName);
		for (final JsonNode policy : MameAnswer.field(pAnswer, "matchingPolicies"))
		{
			if (!previewCode.equals(policy.path("processCode").asText(null)))
			{
				return false;
			}
		}
		for (final JsonNode policy : MameAnswer.field(pAnswer, "mergePolicies"))
		{
			if (!previewCode.equals(policy.path("code").asText(null)))
			{
				return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------------------------------------------------ misc

	/**
	 * The first {@code mergePolicies[]}/{@code matchingPolicies[]} entry's identifying field whose value is NOT
	 * a preview code - see {@link #isPreviewCode}. Null if every entry is a preview (or the list is empty).
	 * <p>
	 * Package-private (not {@code private}): unit-tested directly by {@code MatchingPreviewPolicyTest}, same
	 * convention {@code MatchingConfigurator#describeValidationMessages} already follows.
	 */
	static String firstNonPreviewCode(final JsonNode pPolicies, final String pCodeField)
	{
		for (final JsonNode policy : pPolicies)
		{
			final String code = policy.path(pCodeField).asText(null);
			if (code != null && !MatchingPreviewPolicy.isPreviewCode(code))
			{
				return code;
			}
		}
		return null;
	}

	/**
	 * A deep copy of {@code pMatchingPolicy} with {@code mergePolicyCode} removed - {@code
	 * generate_matching_policy} on a table with NO configuration at all yet refuses outright ({@code
	 * LOCATOR_NOT_FOUND}, "No merge policy found for locator: ...") when {@code mergePolicyCode} names a merge
	 * policy that does not exist there yet, measured live 2026-09-23 12:38 (class Javadoc, "the bug this fix
	 * closes"). {@link #ensureFresh} calls this right before its FIRST {@code generate_matching_policy} call -
	 * the real link is established afterwards by {@code patch_matching_config}'s {@code set_merge_policy_link}
	 * once {@code generate_merge_policy} has actually created the merge policy. {@link #applyPreviewSafetyOverrides}
	 * itself is left UNCHANGED (still stamps {@code mergePolicyCode}) so {@link #ensureRewrite} - which sends the
	 * whole desired config to {@code apply_matching_config} in one shot and can therefore set the link inline -
	 * keeps doing so unaffected. Pure and total; does not mutate {@code pMatchingPolicy}. Package-private (not
	 * {@code private}): unit-tested directly by {@code MatchingPreviewPolicyTest}.
	 */
	static ObjectNode forFreshGenerate(final ObjectNode pMatchingPolicy)
	{
		final ObjectNode copy = pMatchingPolicy.deepCopy();
		copy.remove("mergePolicyCode");
		return copy;
	}

	/**
	 * {@code true} only when a {@code get_matching_config} answer ({@code pGetConfigJson}, the RAW {@code
	 * ToolResult.json}) shows a successful read that carries BOTH a {@code matchingPolicies[]} entry whose
	 * {@code processCode} equals {@code pPreviewCode} AND a {@code mergePolicies[]} entry whose {@code code}
	 * equals it - the read-back check {@link #ensureFresh} runs right after its three write calls, so a soft
	 * miss or a genuine success that nonetheless does not carry the expected entries (the exact shape of the
	 * bug fixed here, class Javadoc) is caught before this class ever reports success to its own caller. {@code
	 * pGetConfigJson == null} (a call that answered no JSON at all) is always {@code false}.
	 */
	static boolean previewPolicyPresent(final JsonNode pGetConfigJson, final String pPreviewCode)
	{
		if (!MameAnswer.success(pGetConfigJson, false))
		{
			return false;
		}
		boolean matchingFound = false;
		for (final JsonNode policy : MameAnswer.field(pGetConfigJson, "matchingPolicies"))
		{
			if (pPreviewCode.equals(policy.path("processCode").asText(null)))
			{
				matchingFound = true;
				break;
			}
		}
		if (!matchingFound)
		{
			return false;
		}
		for (final JsonNode policy : MameAnswer.field(pGetConfigJson, "mergePolicies"))
		{
			if (pPreviewCode.equals(policy.path("code").asText(null)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * master-field-filter lot (2026-09-23, `docs/MATCHING.md` &sect;26): shared by {@link #ensureFresh}/{@link
	 * #ensureRewrite} - drops every survivor of {@code pMergePolicy} that names a field not (yet) on the
	 * project's real master table for {@code pEntityName} ({@link MasterFieldFilter#dropSurvivorsNotOnMaster},
	 * against {@link MatchingConfigurator#findMasterTable}'s own {@code AdaptationTable}), recording a human
	 * note under {@code pDetail.survivorsDroppedNotOnMaster} for any field dropped - the exact same fix {@code
	 * ebx.MatchingConfigurator#configureOne}/{@code #applySurvivorship} apply to a REAL merge policy, needed here
	 * too since a preview's own merge policy is built from the SAME {@code MatchingPolicyTranslator
	 * #buildMergePolicy} call, from the SAME possibly-not-yet-on-master survivor fields.
	 */
	private static void dropSurvivorsNotOnMaster(final Adaptation pFactory, final String pProjectCode, final String pEntityName, final String pTablePath,
		final ObjectNode pMergePolicy, final ObjectNode pDetail) throws OperationException
	{
		final AdaptationTable masterTable = MatchingConfigurator.findMasterTable(pFactory, pProjectCode, pEntityName);
		final Predicate<String> fieldExistsOnMaster = pFieldName -> masterTable.getTableOccurrenceRootNode().getNode(Path.parse("./" + pFieldName)) != null;
		final List<String> dropped = MasterFieldFilter.dropSurvivorsNotOnMaster(pMergePolicy, pTablePath, fieldExistsOnMaster);
		if (!dropped.isEmpty())
		{
			MatchingPreviewPolicy.putStrings(pDetail, "survivorsDroppedNotOnMaster", dropped);
		}
	}

	private static JsonNode parseRequirements(final Adaptation pPlan) throws OperationException
	{
		try
		{
			return MatchingPreviewPolicy.MAPPER.readTree(pPlan.getString(FactoryPaths.MatchPlan.REQUIREMENTS));
		}
		catch (final java.io.IOException ex)
		{
			throw OperationException.createError("MatchPlan.requirements is not valid JSON: " + ex.getMessage(), ex);
		}
	}

	private static String findProjectId(final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			final Adaptation project = rows.nextAdaptation();
			if (project == null)
			{
				throw EngineRefusal.projectNotFound(pProjectCode);
			}
			return project.getOccurrencePrimaryKey().format();
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * The preview policy's own safety shape - {@code active: true}, execution modes forced to {@code MANUAL}
	 * (0), linked to {@code pMergePolicyCode}. Measured live, 2026-09-22 22:45 (the product owner, real Company table):
	 * an {@code active: false} policy is NOT simulatable at all - {@code simulateMatch} refused outright,
	 * {@code "invalid state - matching paused or policy inactive": "The Run match service cannot be executed.
	 * Please check the Matching policy configuration."} - the exact risk this class' own class Javadoc had
	 * flagged as unconfirmed before this fix. {@link MatchingPolicyTranslator#buildMatchingPolicy} already
	 * defaults {@code active} to {@code true} and {@code matchingOnCreation}/{@code matchingOnUpdate} to {@code
	 * 1} (AFTER_SUBMISSION - the live real {@code Company} policy's own value, `javap` on the add-on's {@code
	 * MatchingPolicy$ExecutionMode} static initializer: {@code MANUAL=0}, {@code AFTER_SUBMISSION=1}, {@code
	 * BEFORE_CREATION=2}, {@code INLINE_MATCHING=3}; the config XSD field is {@code matchingExecOnCreation}/
	 * {@code matchingExecOnUpdate}, {@code xs:int} default {@code 0}, MCP names {@code matchingOnCreation}/
	 * {@code matchingOnUpdate}) - this method OVERRIDES the execution modes to {@code 0} (MANUAL) so the
	 * preview stays harmless the other way instead: nothing on the table (a save, an update) ever triggers
	 * matching against it on its own, and {@link MatchingConfigurator#run} already refuses outright to launch
	 * an EXPLICIT run against anything {@link #isPreviewCode}. {@code active} itself is left at {@link
	 * MatchingPolicyTranslator#buildMatchingPolicy}'s own default ({@code true}) - not re-asserted here as a
	 * literal, so a future default change in that method is never silently overridden back to a stale value by
	 * this one.
	 * <p>
	 * <b>Corrected 2026-09-23 13:30 (`docs/MATCHING.md` &sect;26) - the PRIOR version of this note claimed "the
	 * preview is still never the table's {@code tableSettings.defaultMatchingPolicy} (nothing in this class ever
	 * touches that field)". True as to touching (this method itself never writes {@code tableSettings}) but
	 * FALSE as to the outcome, measured live: the add-on makes the FIRST matching policy ever generated on a
	 * table its default, whatever that policy's own code - the preview's own {@code generate_matching_policy}
	 * call, on a table with no configuration at all yet, makes THE PREVIEW the default as an unavoidable side
	 * effect. {@link #ensureFresh}/{@link #ensureRewrite} now correct this explicitly right after generating/
	 * applying ({@code ebx.MatchingConfigurator#ensureDefaultMatchingPolicy}), and {@link #remove} corrects it
	 * again before removing the preview - see each method's own comment for exactly when.</b>
	 * <p>
	 * Package-private (not {@code private}): unit-tested directly by {@code MatchingPreviewPolicyTest}, same
	 * convention {@link #firstNonPreviewCode} already follows.
	 */
	static void applyPreviewSafetyOverrides(final ObjectNode pPreviewMatchingPolicy, final String pMergePolicyCode)
	{
		pPreviewMatchingPolicy.put("matchingOnCreation", 0);
		pPreviewMatchingPolicy.put("matchingOnUpdate", 0);
		pPreviewMatchingPolicy.put("mergePolicyCode", pMergePolicyCode);
	}

	/** Only ever set from a {@link MameDataModelResolution} resolution - never the publication name directly (same discipline as {@code MatchingConfigurator.putDataModel}). */
	private static void putDataModel(final ObjectNode pArgs, final String pDataModel)
	{
		if (pDataModel != null)
		{
			pArgs.put("dataModel", pDataModel);
		}
	}

	/**
	 * The exact {@code apply_matching_config} argument shape, per the LIVE tool signature the coordinator read
	 * 2026-09-22 22:10 (class Javadoc): {@code table}, an optional {@code dataModel}, and {@code config} - a
	 * NESTED object carrying {@code matchingPolicies}/{@code mergePolicies} - never those two as bare top-level
	 * arguments, and {@code businessObject} never sent at all (this class never touches a business object).
	 * Pure and total (no null checks beyond {@link #putDataModel}'s own) so it can be unit-tested directly
	 * without a live {@code McpClient}/{@code Session} - {@code MatchingPreviewPolicyTest} pins this exact
	 * shape.
	 */
	static ObjectNode buildApplyConfigArgs(final String pTablePath, final String pDataModel, final ArrayNode pMatchingPolicies, final ArrayNode pMergePolicies)
	{
		final ObjectNode args = MatchingPreviewPolicy.MAPPER.createObjectNode();
		args.put("table", pTablePath);
		MatchingPreviewPolicy.putDataModel(args, pDataModel);
		final ObjectNode config = args.putObject("config");
		config.set("matchingPolicies", pMatchingPolicies);
		config.set("mergePolicies", pMergePolicies);
		return args;
	}

	/**
	 * The live {@code apply_matching_config} tool description (read by the coordinator, 2026-09-22 22:10, not
	 * this session): "Any key the model does not declare is reported back on the successful result rather than
	 * dropped in silence." The exact field name carrying that report was not itself quoted to this class, so
	 * this checks the most plausible candidate names (never verified live - a guess, same discipline as every
	 * other unverified shape in this class) and copies whichever one is a non-empty array into {@code pOut}
	 * under {@code applyIgnoredKeys}, purely so it is visible without having to dig through the full raw
	 * answer. {@link #toolDetail} already logs that full raw result regardless (on the two callers that build
	 * a {@code detail} node - {@link #remove} passes its own return value instead, same effect), so a report
	 * under some OTHER name is still visible there even if every one of these guesses is wrong.
	 */
	private static void logIgnoredKeysIfAny(final McpClient.ToolResult pResult, final ObjectNode pOut)
	{
		if (pResult == null || pResult.json == null)
		{
			return;
		}
		for (final String candidateKey : new String[] { "ignoredKeys", "unknownKeys", "ignored", "unrecognizedKeys", "unrecognizedFields" })
		{
			final JsonNode value = MameAnswer.field(pResult.json, candidateKey);
			if (value.isArray() && value.size() > 0)
			{
				pOut.set("applyIgnoredKeys", value);
				return;
			}
		}
	}

	/** Same convention as {@code ebx.MatchingConfigurator.putStrings} - a separate copy per this codebase's own discipline (small, pure, no reason to couple the two classes over it). */
	private static void putStrings(final ObjectNode pOut, final String pKey, final List<String> pValues)
	{
		final ArrayNode array = pOut.putArray(pKey);
		for (final String value : pValues)
		{
			array.add(value);
		}
	}

	private static ObjectNode toolDetail(final ObjectNode pArguments, final McpClient.ToolResult pResult)
	{
		final ObjectNode node = MatchingPreviewPolicy.MAPPER.createObjectNode();
		node.set("arguments", pArguments);
		node.put("error", pResult.error);
		if (pResult.json != null)
		{
			node.set("result", pResult.json);
		}
		else
		{
			node.put("resultText", pResult.text);
		}
		return node;
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
