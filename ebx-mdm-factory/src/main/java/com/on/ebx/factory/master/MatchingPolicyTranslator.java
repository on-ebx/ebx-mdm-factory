package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.matching.CandidateFilterCheck;

/**
 * Translates {@code MatchPlan.requirements} (the structured JSON {@link MatchPlanRules} writes - a flat
 * list of tiers, see that class' own Javadoc) into the two payloads the real match-merge MCP tools want:
 * a {@code matchingPolicy} object for {@code generate_matching_policy}, and a {@code mergePolicy} object
 * for {@code generate_merge_policy} - both shapes read live, in full, from {@code matching_config_schema}
 * (`docs/MATCHING.md` &sect;3; the real, live {@code Companies} decision tree quoted there is the concrete
 * shape this class' output structurally mirrors: nested exact tiers ending {@code Match}, an innermost
 * fuzzy {@code Average} tier ending {@code Suspected}, and a final {@code Not Match} leaf).
 * <p>
 * <b>2026-09-21, 22:16 - first real call, real refusal, two bugs fixed.</b> The coordinator's first live
 * {@code POST /projects/Companies/matching/configure} got past {@code get_matching_config} (a genuine
 * {@code CONFIG_NOT_FOUND}) and failed at {@code generate_matching_policy} itself: {@code
 * {"code":"INVALID_PARAMS","message":"Decision tree references a field not declared on the policy: name"}}.
 * The plan's {@code clusteringFields} was {@code ["city","country"]}, but its decision tree also compared
 * {@code name} (the fuzzy anchor of the {@code AVERAGE} tier) - a field used for COMPARISON has never had to
 * also be a blocking/clustering field, so the old {@link #buildMatchingPolicy} (which only ever declared
 * {@code matchingPolicy.fields} from {@code clusteringFields}, verbatim) simply never told the add-on about
 * {@code name} at all. Fixed by declaring the UNION of every field the tree compares and every {@code
 * clusteringFields} entry (see {@link #buildMatchingPolicy}'s own comments) - exactly the invariant
 * {@code generate_matching_policy} itself enforces, now also checked by this class before a payload is ever
 * sent (see {@link #assertEveryTreeFieldIsDeclared}).
 * <p>
 * The second bug, found the same minute by re-reading {@code matching_config_schema(table=...)}'s own field
 * descriptions and the real, live, already-running {@code PointOfSale} configuration (`docs/MATCHING.md`
 * &sect;2.1/&sect;2.5): a field path is RECORD-RELATIVE WITH A LEADING SLASH (the schema's own words:
 * "Field paths are record-relative, the same form {@code matchingPolicies[].fields[].field} takes" -
 * {@code fieldCapabilities} lists them as {@code /name}, {@code /city}, {@code /country}, ...; the live
 * {@code PointOfSale} policy stores {@code /Name}, {@code /Address/City}, {@code /Address/Country}, {@code
 * /Retailer} in BOTH {@code matchingPolicies[].fields[].field} and every {@code decisionTree...fields[].field}).
 * This codebase's own internal representation ({@code MatchPlanRules}/{@code MatchPlanLlmPass}' {@code
 * requirements} JSON, {@code MatchPlanProfileReader}'s parsed {@code fieldCapabilities} map) stays
 * slash-less throughout - {@link #toFieldPath} is the ONE place a bare attribute name becomes the wire
 * path, applied only when writing {@code matchingPolicy.fields[].field} and every {@code
 * decisionTree...fields[].field}, idempotently (a name that already carries a leading slash - e.g. a future
 * grouped attribute path such as {@code /Address/City} - is returned unchanged, never double-slashed).
 * <p>
 * <b>Never exercised against the live instance after this fix</b> - {@code generate_matching_policy}/
 * {@code generate_merge_policy} are write tools, forbidden in this session; every shape below is built
 * strictly from {@code matching_config_schema}'s own field descriptions and the live {@code PointOfSale}
 * read, quoted in this class' Javadoc where a choice was not obvious, plus tonight's own measured refusal,
 * reproduced as a regression fixture (`MatchingPolicyTranslatorTest#realRunPayload_...`).
 * <p>
 * <b>A genuine, unresolved discrepancy, flagged rather than silently picked</b>: the schema's own
 * {@code trueNode}/{@code falseNode}.{@code name} enum is {@code MATCHED}/{@code SUSPECTED}/
 * {@code NOT_MATCHED}, but the real, live {@code Companies} configuration (read this session,
 * `docs/MATCHING.md` &sect;1) stores its terminal leaves as {@code "Match"}/{@code "Suspect"}/
 * {@code "Not Match"} - display-style strings, not the documented enum tokens. This class writes the
 * SCHEMA's documented enum values ({@link #MATCHED}/{@link #SUSPECTED}/{@link #NOT_MATCHED}), since that
 * is what the write-facing schema declares; whoever first runs {@code generate_matching_policy} for real
 * must check the re-read result (`get_matching_config` after generating) still shows a leaf name the UI
 * renders sensibly, and adjust this class if the live tool actually expects the older display-string form.
 * <p>
 * <p>
 * <b>{@code autoMerge} and the MATCH outcome</b> - CLAUDE.md &sect;8's own finding, load-bearing here:
 * "MATCHED groups are merged automatically during the run even with merge mode DISABLED... If a data
 * steward must confirm every merge, route MATCH outcomes to SUSPECTED in the tree." A merge policy's
 * {@code mode} does NOT gate this - only the decision tree's own leaf name does. So {@code
 * MatchPlan.autoMerge} is applied HERE, at translation time, not as a merge-policy setting: with the schema
 * default {@code false}, {@link #buildMatchingPolicy} forces every tier's outcome to {@code SUSPECTED},
 * including an exact/identifier tier {@link MatchPlanRules} itself proposed as {@code MATCH} - nothing can
 * auto-merge until a human sets {@code autoMerge=true} on the accepted plan. The fuzzy tier's own proposed
 * outcome is always {@code SUSPECTED} regardless of {@code autoMerge} ({@link MatchPlanRules} never
 * proposes anything else for it) - {@code autoMerge} only ever promotes an EXACT tier's own {@code MATCH}.
 * <p>
 * <b>Trusted sources and survivorship, 2026-09-22 (`docs/MATCHING.md` "Survivorship and trusted sources").</b>
 * {@link #buildMergePolicy} gained a THIRD parameter ({@code pSourceKeyFieldNames}) and now always builds a
 * real {@code survivors} array instead of an empty one - the gap this lot exists to close: the factory's
 * first real merge (Company 322 "BPCE") took {@code sourceSystem} from one contributing source and {@code
 * sourceId}/its own source-key attribute from another, purely because the single default merge function
 * ({@code longest}) never treats a technical bookkeeping field any differently from a business one. Every
 * merge policy this class now builds carries an EXPLICIT rule for the four fixed technical fields ({@code
 * sourceSystem}/{@code sourceId}/{@code _loadId} -&gt; {@code no merge}, {@code
 * _receivedAt} -&gt; {@code max}) plus one {@code no merge} rule per structurally-identified source-key
 * business attribute, and both {@code recordSelectionFunctionChain}/{@code defaultFieldMergeFunctionChain}
 * now try {@code most trusted source} before falling back to the project's own hint. The trusted-source
 * RANKING itself (which source code actually IS most trusted) is a separate, per-table {@code
 * set_trusted_sources} call this class knows nothing about - see {@link TrustedSourceRanking} and {@code
 * ebx.MatchingConfigurator#configureOne}/{@code #applySurvivorship}.
 * <p>
 * <b>Lot M1 (2026-10-17, `docs/MATCHING.md` sect;56): the golden record's source is {@code autoCreateNewGoldenSource}, NEVER a {@code constant}
 * survivor.</b> Until this lot {@code sourceSystem} carried a {@code constant "MDM"} survivor. On a table that belongs to a business object (its table configuration points to
 * the object: {@code BusinessObjectUtil.isBusinessObject}) the add-on (6.3.0.5-RC3, ONLY_DUPLICATES) runs the merge engine on every SINGLETON of an entire-table run and applies the survivors to the record IN PLACE
 * ({@code DeduplicationServiceImpl.mergesSingletons}: "business object and ONLY_DUPLICATES"; summary line {@code Number of merged singletons: 534}) - so the
 * constant overwrote the {@code sourceSystem} of ALL 534 master records of project E2EMASTER20261017212914 (ECOM/LOYALTY/STORES became MDM, the source trace
 * lost). {@code autoCreateNewGoldenSource} is read by the add-on only for a golden it CREATES ({@code AutoMergingExecutor}: the custom source cell is built
 * only when the policy creates goldens and no golden exists yet, and {@code SurvivorServiceImpl} drops the survivor rule of the source field), so it
 * stamps the new golden and leaves a singleton alone. The remaining survivors are identity functions on one record ({@code no merge}, {@code max},
 * {@code most trusted source}, {@code longest}); {@link #assertMergePolicyWellFormed} refuses any {@code constant} so the defect cannot come back.
 * <p>
 * Pure: no EBX class, Jackson only - unit-tested without a running instance.
 */
public final class MatchingPolicyTranslator
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String MATCHED = "MATCHED";
	private static final String SUSPECTED = "SUSPECTED";

	/** {@code matching_config_schema} -> {@code mergeChains.chains.record_selection}: identifier = label for the built-ins; only the two this codebase's own rules ever propose (docs/MATCHING.md sec 2/3) are mapped. */
	private static final Map<String, String> RECORD_SELECTION_FUNCTIONS = MatchingPolicyTranslator.recordSelectionFunctions();

	/** {@code mostTrustedSource}'s own short key - the ONE entry of {@link #RECORD_SELECTION_FUNCTIONS} the trusted-sources lot always prepends (docs/MATCHING.md, 2026-09-22 "Survivorship and trusted sources"). */
	private static final String RECORD_SELECTION_MOST_TRUSTED_SOURCE = "mostTrustedSource";
	private static final String RECORD_SELECTION_DEFAULT = "mostCompleted";

	/**
	 * {@code defaultFieldMergeFunctionChain}/survivor {@code mergeFunctionChain} entries are literal enum
	 * strings, NEVER i18n keys (unlike {@link #RECORD_SELECTION_FUNCTIONS}) - {@code matching_config_schema}'s
	 * own {@code defaultFieldMergeFunctionChain.items.properties.function.enum}: {@code "last update",
	 * "longest", "max", "min", "most frequent", "most trusted source"}; the survivor {@code
	 * mergeFunctionChain} enum additionally allows {@code "constant"} (must be last, needs {@code
	 * constantValue}) and {@code "no merge"} - task brief's own contract, 2026-09-22.
	 */
	private static final String FUNCTION_MOST_TRUSTED_SOURCE = "most trusted source";
	private static final String FUNCTION_CONSTANT = "constant";
	private static final String FUNCTION_NO_MERGE = "no merge";
	private static final String FUNCTION_MAX = "max";
	/** Lot 1 (2026-09-23, quality-informed survivorship): the ONLY function a {@code *Valid} derived field's own survivor rule ever carries - see {@link #addValidValueSurvivors}'s own Javadoc. */
	private static final String FUNCTION_LONGEST = "longest";

	/**
	 * The master table's own technical field names - {@code master.MasterModel}'s private constants of the
	 * same name, duplicated here rather than shared cross-package (same discipline as {@code
	 * MasterModel.ebxType} mirroring {@code StagingModel.ebxType}: this class stays independently
	 * unit-testable without depending on {@code MasterModel}'s internal representation, and the convention -
	 * a master table ALWAYS carries plain {@code sourceSystem}/{@code sourceId}/{@code _loadId}/{@code
	 * _receivedAt} fields under these exact names - is stable product-wide, not something this class expects
	 * to ever need to parameterize).
	 */
	private static final String TECHNICAL_FIELD_SOURCE_SYSTEM = "sourceSystem";
	private static final String TECHNICAL_FIELD_SOURCE_ID = "sourceId";
	private static final String TECHNICAL_FIELD_LOAD_ID = "_loadId";
	private static final String TECHNICAL_FIELD_RECEIVED_AT = "_receivedAt";
	/**
	 * The source code stamped on a golden record the add-on CREATES ({@code mergePolicies[].autoCreateNewGoldenSource}) - CLAUDE.md's own convention for a value the
	 * factory itself wrote. It must be a REGISTERED source of the add-on: {@link TrustedSourceRanking#buildTrustedSourcesPayload} appends it, last, to every table
	 * ranking it writes. Lot M1 (2026-10-17): never a {@code constant} survivor - see the class Javadoc.
	 */
	public static final String GOLDEN_SOURCE_CODE = "MDM";

	private MatchingPolicyTranslator()
	{
	}

	private static Map<String, String> recordSelectionFunctions()
	{
		final Map<String, String> out = new HashMap<String, String>();
		out.put(MatchingPolicyTranslator.RECORD_SELECTION_DEFAULT, "mame.addon.reference.data.recordSelectionPolicy.mostCompleted.label");
		out.put("lastUpdate", "mame.addon.reference.data.recordSelectionPolicy.lastUpdate.label");
		out.put(MatchingPolicyTranslator.RECORD_SELECTION_MOST_TRUSTED_SOURCE, "mame.addon.reference.data.recordSelectionPolicy.mostTrustedSource.label");
		return out;
	}

	// -------------------------------------------------------------------------------------------------- matchingPolicy

	/**
	 * @param pProcessCode the matching policy's own code ({@code matchingPolicies[].processCode}) - {@code
	 *            MatchingConfigurator} uses the entity name, the same convention {@code StagingModel}/
	 *            {@code MasterModel} use for table names.
	 * @param pRequirements the parsed {@code MatchPlan.requirements} ({@link MatchPlanRules} shape).
	 * @param pAutoMerge {@code MatchPlan.autoMerge} - see class Javadoc "autoMerge and the MATCH outcome": when
	 *            false (the schema default), EVERY tier's outcome is forced to {@code SUSPECTED}, so nothing in
	 *            the generated tree can auto-merge without a data steward; a tier {@link MatchPlanRules} itself
	 *            proposed as {@code SUSPECTED} (the fuzzy tier) is never promoted to {@code MATCHED} by this
	 *            flag either way - only an exact/identifier tier's own proposed {@code MATCH} outcome is ever
	 *            gated by it.
	 * @throws IllegalStateException if {@code pRequirements} names a blank/missing field anywhere, or if - after
	 *             this method has built the policy - the decision tree still ends up referencing a field
	 *             {@code matchingPolicy.fields} does not declare. The second case should be structurally
	 *             impossible given the union built below; it is asserted anyway ({@link
	 *             #assertEveryTreeFieldIsDeclared}) so a future change that breaks the invariant fails loudly,
	 *             in a unit test, rather than as tonight's live {@code INVALID_PARAMS} refusal.
	 */
	public static ObjectNode buildMatchingPolicy(final String pProcessCode, final JsonNode pRequirements, final boolean pAutoMerge)
	{
		return MatchingPolicyTranslator.translate(pProcessCode, pRequirements, pAutoMerge, Collections.<String, String>emptyMap()).matchingPolicy;
	}

	/** Weight written for a relation (child) field's pre-clustering entry when {@code requirements.relationClusteringWeights} has none: the field is compared in the tree but takes no part in the candidate search (lot M2, 2026-10-03, `docs/MATCHING.md` sect;48). */
	public static final int DEFAULT_RELATION_PRECLUSTERING_WEIGHT = 0;

	/**
	 * {@link #translate}'s result: the policy ready for {@code generate_matching_policy}/{@code apply_matching_config}, plus what the caller records or needs.
	 */
	public static final class TranslationResult
	{
		public final ObjectNode matchingPolicy;
		/**
		 * Lot M16 (2026-10-18, `docs/MATCHING.md` &sect;68), replacing the dominance notes of lot R16: the share of the total pre-clustering weight each key set of the tree holds, as the weights stand
		 * ({@link CandidateScoreShare#analyze}; {@code applied: false} - facts, not an action: the Factory no longer changes a weight). {@code null} when the tree has no key set (no path passes
		 * an exact key). {@code ebx.MatchingConfigurator} records it verbatim under {@code detail.candidateScoreShare}.
		 */
		public final ObjectNode candidateScoreShare;
		/** Native relation fields (add-on 6.3.0.5-RC3): absolute path of the member table's field (e.g. {@code /root/Adresse/city}) -&gt; the pre-clustering weight written for it. Empty for a plan without relation field. */
		public final Map<String, Integer> relationFieldWeights;

		TranslationResult(final ObjectNode pMatchingPolicy, final ObjectNode pCandidateScoreShare, final Map<String, Integer> pRelationFieldWeights)
		{
			this.matchingPolicy = pMatchingPolicy;
			this.candidateScoreShare = pCandidateScoreShare;
			this.relationFieldWeights = pRelationFieldWeights;
		}

		/** True when the policy compares a child field: it can only be written through the business-object tools. */
		public boolean hasRelationFields()
		{
			return !this.relationFieldWeights.isEmpty();
		}
	}

	/**
	 * THE translation of an accepted plan into a matching policy (one path since lot W13, 2026-10-12; `docs/MATCHING.md` sect;33).
	 * <p>
	 * A relation field ({@link DecisionTreePlan.FieldRef#via}: {@code Adresse.city} on a {@code Personne} plan) is written the way the add-on 6.3.0.5-RC3 reads
	 * it natively, measured live on the scratch model W13BO: the matching-policy field carries {@code businessObjectField = /root/Adresse/city}
	 * (the ABSOLUTE path of the member table's field) and no {@code field} - the server sets {@code useRelationships}/{@code location} from the
	 * business object relationship, nothing of it is sent - and the decision-tree field carries {@code field = /root/Adresse/city} and the same
	 * {@code businessObjectField}. The pre-clustering weight of a relation field stays {@code requirements.relationClusteringWeights} (default 0,
	 * `docs/MATCHING.md` sect;48): out of the candidate search, still compared in the tree.
	 *
	 * @param pChildTablePathByEntity {@link DecisionTreePlan.FieldRef#via} (the child entity name) -&gt; its master table path ({@code /root/Adresse}); read
	 *            only for a plan that compares a child field
	 * @throws IllegalStateException a relation field names a child entity this map does not know, or the plan/tree is not well formed
	 */
	public static TranslationResult translate(final String pProcessCode, final JsonNode pRequirements, final boolean pAutoMerge,
		final Map<String, String> pChildTablePathByEntity)
	{
		// 2026-09-22, binary-decision-tree-model lot (`docs/MATCHING.md` &sect;16): DecisionTreePlan.readFrom reads
		// EITHER the current flat {root, nodes[]} shape (requirements.decisionTree - what both MatchPlanRules and
		// MatchPlanLlmPass write from now on) or a pre-lot tiers[] cascade transparently (its own fromTiers
		// fallback) - this method never has to know which one produced the plan it is translating.
		final DecisionTreePlan.Plan plan = DecisionTreePlan.readFrom(pRequirements);
		final Map<String, String> childTablePathByEntity = pChildTablePathByEntity == null ? Collections.<String, String>emptyMap() : pChildTablePathByEntity;
		// counted BEFORE the rewrite below changes the relation fields' names (the keys are "<via>.<child field>")
		final int relationWeightSum = MatchingPolicyTranslator.relationWeightSum(plan, pRequirements);
		final Map<String, Integer> relationClusteringWeights = MatchingPolicyTranslator.readRelationClusteringWeights(pRequirements);

		// Every node label is frozen BEFORE a relation field is renamed: DecisionTreePlan.labelFor builds a blank label from the node's CURRENT field
		// names, so a label computed after the rewrite would read the absolute paths (PersVol5, 2026-09-26: the name sent to the add-on must be the
		// one the plan shows). Idempotent - buildMatchingPolicyFromPlan calls it again.
		DecisionTreePlan.freezeAndDisambiguateLabels(plan);
		final Map<String, Integer> relationWeightByPath = new LinkedHashMap<String, Integer>();
		for (final DecisionTreePlan.Node node : plan.nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if (!field.isRelation())
				{
					continue;
				}
				final String childTablePath = childTablePathByEntity.get(field.via);
				if (childTablePath == null || childTablePath.trim().isEmpty())
				{
					throw new IllegalStateException("MatchingPolicyTranslator: node '" + DecisionTreePlan.labelFor(node) + "' compares '" + field.via + "." + field.field
						+ "' but no master table is known for child entity '" + field.via + "': the child must be a promoted entity holding a foreign key to this one.");
				}
				final String relationPath = MatchingPolicyTranslator.normalizeFieldName(childTablePath + "/" + MatchingPolicyTranslator.normalizeFieldName(field.field));
				relationWeightByPath.put(relationPath, Integer.valueOf(MatchingPolicyTranslator.relationClusteringWeight(relationClusteringWeights, field.via, field.field)));
				field.field = relationPath;
			}
		}
		final PolicyBuildResult built = MatchingPolicyTranslator.buildMatchingPolicyFromPlan(pProcessCode, plan, pRequirements, pAutoMerge, relationWeightSum,
			relationWeightByPath);
		final Map<String, Integer> absoluteWeights = new LinkedHashMap<String, Integer>();
		for (final Map.Entry<String, Integer> entry : relationWeightByPath.entrySet())
		{
			absoluteWeights.put("/" + entry.getKey(), entry.getValue());
		}
		return new TranslationResult(built.matchingPolicy, built.candidateScoreShare, absoluteWeights);
	}

	/**
	 * Lot R16 (2026-09-28, `docs/MATCHING.md` &sect;46): {@link #buildMatchingPolicyFromPlan}'s own result - the
	 * policy plus the note of the candidate-score shares (lot M16, {@link CandidateScoreShare}). Private: only {@link #translate} sees this type.
	 */
	private static final class PolicyBuildResult
	{
		final ObjectNode matchingPolicy;
		final ObjectNode candidateScoreShare;

		PolicyBuildResult(final ObjectNode pMatchingPolicy, final ObjectNode pCandidateScoreShare)
		{
			this.matchingPolicy = pMatchingPolicy;
			this.candidateScoreShare = pCandidateScoreShare;
		}
	}

	/**
	 * The body of {@link #translate}.
	 *
	 * @param pRelationWeightSum sum of the effective pre-clustering weights of the plan's relation fields ({@link
	 *            #relationWeightSum}) - the dominance guard counts it (lot M2, `docs/MATCHING.md` &sect;48).
	 * @param pRelationWeightByPath bare (slash-less) business-object path of each relation field ({@code root/Adresse/city}, which is also its name in
	 *            {@code pPlan} by now) -&gt; its pre-clustering weight; these fields are written with {@code businessObjectField} and no {@code field}
	 * @param pRequirementsForClustering the ORIGINAL {@code MatchPlan.requirements} document, read only for its
	 *            {@code clusteringFields} array (never for {@code decisionTree}/{@code tiers} - {@code pPlan} is
	 *            the single source of truth for the tree itself).
	 */
	private static PolicyBuildResult buildMatchingPolicyFromPlan(final String pProcessCode, final DecisionTreePlan.Plan pPlan, final JsonNode pRequirementsForClustering,
		final boolean pAutoMerge, final int pRelationWeightSum, final Map<String, Integer> pRelationWeightByPath)
	{
		final ObjectNode policy = MatchingPolicyTranslator.MAPPER.createObjectNode();
		policy.put("processCode", pProcessCode);
		policy.put("active", true);
		// Relation-fields lot (2026-09-26, coordinator review item 2, docs/MATCHING.md &sect;33.8): a policy whose
		// tree compares at least one relation (child) field is written MANUAL (0/0) instead of the ordinary
		// AFTER_SUBMISSION (1/1) - a parent row is created (and, under AFTER_SUBMISSION, matched) before its child
		// rows exist, and a child row's own creation/update never re-triggers the parent's matching (nothing in
		// this codebase declares such a trigger - grepped). Left MANUAL, the explicit MATCHING_RUN step
		// (ebx.MatchingConfigurator#run, scoped by master.MatchingRunScope) - run once the WHOLE load, parent and
		// children both, has completed - is what actually applies matching for such an entity, against complete
		// evidence. Nothing to do with how a relation field is written, only with WHEN the add-on itself should run matching.
		final boolean hasRelationFields = MatchingPolicyTranslator.hasAnyRelationField(pPlan);
		policy.put("matchingOnCreation", hasRelationFields ? 0 : 1);
		policy.put("matchingOnUpdate", hasRelationFields ? 0 : 1);
		policy.put("matchAgainstMergedRecords", false);
		policy.put("executePurge", "MANUAL");
		policy.put("showMatchType", false);

		final DecisionTreePlan.Plan plan = pPlan;
		// 2026-09-22, coordinator follow-up: validated HERE, before toNested ever walks it - the one thing this
		// guarantees, together with MatchPlanLlmPass.Session's own validate() call before a plan is ever stored,
		// is that toNested (see its own Javadoc) never has to "unroll" a shared node into two copies: every node
		// has exactly one parent (DecisionTreePlan.validate's own NODE_SHARED rule), same discipline as this
		// class' own assertEveryTreeFieldIsDeclared/assertMergePolicyWellFormed self-checks below.
		final DecisionTreePlan.ValidationResult planValidation = DecisionTreePlan.validate(plan);
		if (!planValidation.ok)
		{
			throw new IllegalStateException(
				"MatchingPolicyTranslator was asked to translate a MatchPlan.requirements whose decision tree is not structurally valid: "
					+ planValidation.reasons + " - this should have been caught at proposal time (MatchPlanLlmPass.Session.proposeMatchPlan already "
					+ "calls DecisionTreePlan.validate before storing a plan); fix the plan (or whoever produced it) before calling this method.");
		}

		// Lot R8 (2026-09-27, live PersVol7, PipelineStep 587, docs/MATCHING.md dated entry): freeze/disambiguate
		// every node's label BEFORE toNested ever reads one - a plain (non-relation) plan never went through
		// translate's own freeze above, so this is a no-op for a plan it already froze;
		// idempotent (DecisionTreePlan.freezeAndDisambiguateLabels' own Javadoc) for a plan that already has, since
		// the relation-fields caller runs this same call earlier, before ever reaching here.
		DecisionTreePlan.freezeAndDisambiguateLabels(plan);

		// matchingPolicy.fields must declare the UNION of every field the decision tree compares and every
		// clusteringFields entry (2026-09-21 fix, see class Javadoc): a fuzzy tier's anchor field is compared but
		// was never necessarily also a blocking field, and generate_matching_policy refuses outright when the tree
		// mentions a field this array does not carry. Order is stable: clusteringFields first (the caller's own
		// declared blocking order), then any additional tree field in first-appearance order - never alphabetised,
		// never re-derived on every read, so a re-generated policy is byte-for-byte reproducible from the same plan.
		final LinkedHashSet<String> fieldOrder = new LinkedHashSet<String>();
		for (final JsonNode clusteringField : pRequirementsForClustering.path("clusteringFields"))
		{
			fieldOrder.add(MatchingPolicyTranslator.normalizeFieldName(clusteringField.asText(null)));
		}
		for (final DecisionTreePlan.Node node : plan.nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				fieldOrder.add(MatchingPolicyTranslator.normalizeFieldName(field.field));
			}
		}

		final Map<String, Integer> weightByField = MatchingPolicyTranslator.weightsByField(plan);
		final Map<String, String[]> nullHandlingByField = MatchingPolicyTranslator.nullHandlingByField(plan);

		// CLUSTERING WEIGHTS (lot M16, 2026-10-18, docs/MATCHING.md &sect;68; replaces the dominance guard of lot R16): the model's own requirements.clusteringWeights are applied to
		// weightByField (nothing else raises a weight), and the share of the total weight each key set of the tree holds is measured and recorded (CandidateScoreShare). Run BEFORE the
		// fields[] loop below, which reads weightByField, so MatchingPolicyComparator (which re-derives the desired policy through this same method) sees the same value.
		final ObjectNode candidateScoreShare = MatchingPolicyTranslator.applyClusteringWeights(pRequirementsForClustering, fieldOrder, weightByField, pRelationWeightSum,
			pRelationWeightByPath.keySet());

		final ArrayNode clusteringFields = policy.putArray("fields");
		for (final String name : fieldOrder)
		{
			final ObjectNode field = clusteringFields.addObject();
			final boolean relationField = pRelationWeightByPath.containsKey(name);
			if (relationField)
			{
				// native relation field (add-on 6.3.0.5-RC3, measured on W13BO): the ABSOLUTE path of the member table's field, no `field`;
				// useRelationships/location are set by the server from the business object relationship and are not sent
				field.put("businessObjectField", MatchingPolicyTranslator.toFieldPath(name));
			}
			else
			{
				field.put("field", MatchingPolicyTranslator.toFieldPath(name));
			}
			field.put("searchStrategy", "@default");
			// Pre-clustering weight: the field's own weight as carried by any tier (a clustering field that is
			// also compared keeps that weight; a field never given an explicit weight - every EXACT-tier field,
			// since minimumScore is its own threshold, not weight - defaults to 1). Never 0 for a field of the
			// entity's own table: matching_guide says a threshold of 0 is rejected outright. A relation field is
			// the exception: its weight is requirements.relationClusteringWeights (default 0, `docs/MATCHING.md`
			// sect;48 - the add-on's schema allows an integer >= 0 and a relation field out of the candidate search is
			// the measured recommendation).
			final Integer weight = relationField ? pRelationWeightByPath.get(name) : weightByField.get(name);
			field.put("weight", relationField ? weight.intValue() : (weight == null || weight.intValue() < 1 ? 1 : weight.intValue()));
			// Null handling mirrors the tree's own value for this field's FIRST tier appearance (the live
			// PointOfSale configuration does exactly this - docs/MATCHING.md &sect;2.1) rather than a hardcoded
			// DO_NOTHING: a field only ever named in clusteringFields, never compared in any tier, has no tree
			// value to mirror and keeps the DO_NOTHING default.
			final String[] nullHandling = nullHandlingByField.get(name);
			field.put("bothNullValueHandling", nullHandling == null ? "DO_NOTHING" : nullHandling[0]);
			field.put("oneNullValueHandling", nullHandling == null ? "DO_NOTHING" : nullHandling[1]);
		}

		// 2026-09-22, binary-decision-tree-model lot: DecisionTreePlan.toNested already builds the exact nested
		// {name, function, params, fields, trueNode, falseNode} shape recursively over the whole graph (not merely
		// a chain) - this class' own job is only the ONE product-specific policy decision DecisionTreePlan itself
		// stays neutral about: forcing a MATCHED leaf back to SUSPECTED when autoMerge is false (see class Javadoc
		// "autoMerge and the MATCH outcome").
		final ObjectNode nestedTree = DecisionTreePlan.toNested(plan);
		if (!pAutoMerge)
		{
			MatchingPolicyTranslator.forceMatchedLeavesToSuspected(nestedTree);
			// PersVol6 live failure (PipelineStep 526, docs/MATCHING.md sect;36, 2026-09-27): forcing a MATCHED leaf
			// down to SUSPECTED can make a node's two branches reach the SAME leaf - a collision DecisionTreePlan#validate
			// never sees (it runs on the ORIGINAL plan, before this downgrade). match-merge itself refuses that with the
			// uninformative "cannot connect to the same type of relationship status" message, so collapse it away here,
			// bottom-up, before the policy is ever sent - a collapsed node is replaced by the leaf it now always reaches,
			// which can itself make its own PARENT collapse in turn (handled by the recursion, not a second pass).
			final String collapsedRootLeaf = MatchingPolicyTranslator.collapseSameOutcomeNodes(nestedTree);
			if (collapsedRootLeaf != null)
			{
				throw new IllegalStateException("MatchingPolicyTranslator: with autoMerge=false, forcing every MATCHED leaf to SUSPECTED collapses this "
					+ "policy's entire decision tree down to a single outcome ('" + collapsedRootLeaf + "') - the tree would decide nothing. Fix the plan (route "
					+ "at least one branch to a genuinely different outcome), or set autoMerge=true if an exact tier's own MATCH outcome is actually intended to "
					+ "survive translation.");
			}
		}
		policy.set("decisionTree", nestedTree);
		MatchingPolicyTranslator.assertEveryTreeFieldIsDeclared(policy);
		return new PolicyBuildResult(policy, candidateScoreShare);
	}

	/**
	 * Lot M16 (2026-10-18, `docs/MATCHING.md` &sect;68): the model's own pre-clustering weights - {@code requirements.clusteringWeights}, {@code {"<field>": <integer >= 1>}}, an entry wins over
	 * the weight a node gives the field - are applied to {@code pWeightByField} (mutated IN PLACE); nothing else changes a weight (the dominance guard of lot R16 is gone: it was the cause of the
	 * miss measured in run 11, and the Factory no longer decides a weight - it measures, {@link CandidateScoreShare}, and tells the model). The shares the weights give the tree's key sets are
	 * returned as a note for the step detail ({@code applied: false}: facts, never an action).
	 *
	 * @param pRelationWeightSum the effective weights of the relation fields (they count in the total)
	 * @return the note, or {@code null} when the tree has no key set
	 */
	private static ObjectNode applyClusteringWeights(final JsonNode pRequirements, final LinkedHashSet<String> pFieldOrder, final Map<String, Integer> pWeightByField,
		final int pRelationWeightSum, final Set<String> pRelationNames)
	{
		final CandidateScoreShare.Analysis analysis = MatchingPolicyTranslator.shareAnalysis(pRequirements, pFieldOrder, pWeightByField, pRelationWeightSum, pRelationNames);
		return analysis == null ? null : analysis.toJson();
	}

	private static CandidateScoreShare.Analysis shareAnalysis(final JsonNode pRequirements, final LinkedHashSet<String> pFieldOrder, final Map<String, Integer> pWeightByField,
		final int pRelationWeightSum, final Set<String> pRelationNames)
	{
		final JsonNode explicit = pRequirements == null ? null : pRequirements.path("clusteringWeights");
		if (explicit != null && explicit.isObject())
		{
			final Iterator<Map.Entry<String, JsonNode>> entries = explicit.fields();
			while (entries.hasNext())
			{
				final Map.Entry<String, JsonNode> entry = entries.next();
				final String name = MatchingPolicyTranslator.normalizeFieldName(entry.getKey());
				if (pFieldOrder.contains(name) && !pRelationNames.contains(name) && entry.getValue().canConvertToInt() && entry.getValue().asInt() >= 1)
				{
					pWeightByField.put(name, Integer.valueOf(entry.getValue().asInt()));
				}
			}
		}
		final Map<String, Integer> own = MatchingPolicyTranslator.ownWeights(pFieldOrder, pWeightByField, pRelationNames);
		return CandidateScoreShare.analyze(own, pRelationWeightSum,
			CandidateScoreShare.keySets(CandidateFilterCheck.check(pRequirements), new java.util.function.Function<String, String>()
			{
				@Override
				public String apply(final String pName)
				{
					return MatchingPolicyTranslator.normalizeFieldName(pName);
				}
			}, own.keySet()));
	}

	/**
	 * Lot M16: the shares the key sets of {@code pRequirements}' tree hold under the weights the policy would be written with (the plan's node weights, the model's
	 * {@code clusteringWeights} on top, the relation fields at their configured weight) - what the language-model pass tells the model and the plan card shows. Pure; {@code null} when the tree has
	 * no key set. A relation field counts in the total but is never a member of a set.
	 */
	public static CandidateScoreShare.Analysis analyzeShares(final JsonNode pRequirements)
	{
		final DecisionTreePlan.Plan plan = DecisionTreePlan.readFrom(pRequirements);
		final LinkedHashSet<String> fieldOrder = new LinkedHashSet<String>();
		for (final JsonNode clusteringField : pRequirements.path("clusteringFields"))
		{
			final String name = MatchingPolicyTranslator.normalizeFieldName(clusteringField.asText(null));
			if (name != null && !name.isEmpty())
			{
				fieldOrder.add(name);
			}
		}
		for (final DecisionTreePlan.Node node : plan.nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if (!field.isRelation())
				{
					fieldOrder.add(MatchingPolicyTranslator.normalizeFieldName(field.field));
				}
			}
		}
		return MatchingPolicyTranslator.shareAnalysis(pRequirements, fieldOrder, MatchingPolicyTranslator.weightsByField(plan), MatchingPolicyTranslator.relationWeightSum(plan, pRequirements),
			Collections.<String>emptySet());
	}

	/** The weight the policy will write for each own-table field of {@code pFieldOrder}: the entry of {@code pWeightByField}, 1 when absent or under 1 (a relation field is left out). */
	private static Map<String, Integer> ownWeights(final LinkedHashSet<String> pFieldOrder, final Map<String, Integer> pWeightByField, final Set<String> pRelationNames)
	{
		final Map<String, Integer> own = new LinkedHashMap<String, Integer>();
		for (final String name : pFieldOrder)
		{
			if (pRelationNames.contains(name))
			{
				continue;
			}
			final Integer weight = pWeightByField.get(name);
			own.put(name, Integer.valueOf(weight == null || weight.intValue() < 1 ? 1 : weight.intValue()));
		}
		return own;
	}

	/** The highest {@code weight} any node gives a field (defaulting a missing/absent weight to 1 - see the caller's own comment), keyed by the field's bare (slash-less) name. */
	private static Map<String, Integer> weightsByField(final DecisionTreePlan.Plan pPlan)
	{
		final Map<String, Integer> out = new LinkedHashMap<String, Integer>();
		for (final DecisionTreePlan.Node node : pPlan.nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if (field.isRelation())
				{
					// lot M2 (2026-10-03): a relation field is a separate matching-field entry whose weight is requirements.relationClusteringWeights
					continue;
				}
				final String name = MatchingPolicyTranslator.normalizeFieldName(field.field);
				final int weight = field.weight == null ? 1 : field.weight.intValue();
				final Integer existing = out.get(name);
				if (existing == null || weight > existing.intValue())
				{
					out.put(name, Integer.valueOf(weight));
				}
			}
		}
		return out;
	}

	/**
	 * {@code {bothNullValueHandling, oneNullValueHandling}} exactly as the field's FIRST node appearance carries it
	 * (the same default, {@code NOT_MATCH}, {@link DecisionTreePlan#toNested} itself falls back to for a tree
	 * field), keyed by the field's bare (slash-less) name.
	 * <p>
	 * <b>Lot R13 (2026-09-28), verified: this "first appearance wins" flattening is scoped to the TOP-LEVEL
	 * {@code matchingPolicy.fields[]} pre-clustering declaration ONLY, never to the decision tree itself.</b> The
	 * add-on's schema genuinely carries two SEPARATE null-handling settings for the same field name -
	 * {@code matchingPolicy.fields[].bothNullValueHandling}/{@code oneNullValueHandling} (this method's own output,
	 * one entry per field NAME, read below by {@link #buildMatchingPolicyFromPlan}) and each occurrence's own
	 * {@code decisionTree...fields[].bothNullValueHandling}/{@code oneNullValueHandling} (the DECISION-FIELD
	 * override, per {@code matching_config_schema}) - and {@link DecisionTreePlan#toNested}/{@link
	 * DecisionTreePlan#nestedField} already write the LATTER straight from each node's own {@link
	 * DecisionTreePlan.FieldRef#bothNull}/{@link DecisionTreePlan.FieldRef#oneNull}, per occurrence, never
	 * consulting this map or any other cross-node aggregation - so a field compared in two different nodes with two
	 * DIFFERENT null-handling settings (e.g. NOT_MATCH inside an identity-counting node, DO_NOTHING inside a
	 * contradiction node - {@link DecisionTreePlan}'s own {@code IDENTITY_COUNT_FIELD_ONE_NULL_MUST_NOT_MATCH}/
	 * {@code CONTRADICTION_NODE_NULL_HANDLING_MUST_BE_DO_NOTHING} rules) is already written correctly, per node, in
	 * the generated tree - confirmed by {@code MatchingPolicyTranslatorTest#decisionTree_keepsPerNodeNullHandling_notFlattenedToFirstAppearance}.
	 * This method's own "first appearance" choice for the TOP-LEVEL entry is a NECESSARY simplification, not a bug:
	 * the schema allows only ONE {@code matchingPolicy.fields[]} entry per field name, so there is no "correct"
	 * single value to pick when two nodes disagree - the pre-existing convention (mirror the field's first tier
	 * appearance, matching the live {@code PointOfSale} configuration's own shape) is kept unchanged.
	 */
	private static Map<String, String[]> nullHandlingByField(final DecisionTreePlan.Plan pPlan)
	{
		final Map<String, String[]> out = new LinkedHashMap<String, String[]>();
		for (final DecisionTreePlan.Node node : pPlan.nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				final String name = MatchingPolicyTranslator.normalizeFieldName(field.field);
				if (!out.containsKey(name))
				{
					out.put(name, new String[] { field.bothNull == null ? "NOT_MATCH" : field.bothNull, field.oneNull == null ? "NOT_MATCH" : field.oneNull });
				}
			}
		}
		return out;
	}

	/**
	 * Recursively replaces every leaf named {@code MATCHED} with {@code SUSPECTED} throughout a nested tree
	 * {@link DecisionTreePlan#toNested} built - see class Javadoc "autoMerge and the MATCH outcome": this is a
	 * product policy decision ({@code MatchPlan.autoMerge}), deliberately kept OUT of {@link DecisionTreePlan}
	 * itself (which stays neutral about it, writing whatever leaf the flat plan actually names). Mutates
	 * {@code pNode} in place (every node/leaf {@link DecisionTreePlan#toNested} builds is a fresh, unshared
	 * {@code ObjectNode} - safe to mutate).
	 */
	private static void forceMatchedLeavesToSuspected(final ObjectNode pNode)
	{
		if (!pNode.hasNonNull("function"))
		{
			if (MatchingPolicyTranslator.MATCHED.equals(pNode.path("name").asText()))
			{
				pNode.put("name", MatchingPolicyTranslator.SUSPECTED);
			}
			return;
		}
		MatchingPolicyTranslator.forceMatchedLeavesToSuspected((ObjectNode) pNode.path("trueNode"));
		MatchingPolicyTranslator.forceMatchedLeavesToSuspected((ObjectNode) pNode.path("falseNode"));
	}

	/**
	 * Bottom-up collapse (2026-09-27, PersVol6 live failure, `docs/MATCHING.md` &sect;36): after {@link
	 * #forceMatchedLeavesToSuspected} runs, a node whose {@code trueNode}/{@code falseNode} now reach the EXACT SAME
	 * leaf decides nothing - the comparison it makes never changes the outcome, and match-merge itself refuses this
	 * shape outright ({@code generate_matching_policy}'s "cannot connect to the same type of relationship status").
	 * {@link com.on.ebx.factory.master.DecisionTreePlan#validate}'s own {@code SAME_OUTCOME_BOTH_BRANCHES} rule never
	 * catches THIS collision, because it runs on the ORIGINAL plan, before the downgrade above ever creates it.
	 * <p>
	 * Collapses {@code pNode} IN PLACE into the shared leaf (clears every other field, keeps only {@code name}) when
	 * both branches resolved to leaves of the same name - {@link com.fasterxml.jackson.databind.node.ObjectNode}'s
	 * own {@code removeAll()} then a single {@code put}, mirroring {@link
	 * com.on.ebx.factory.master.DecisionTreePlan#nestedLeaf}'s own leaf shape ({@code name}, {@code function: null}
	 * left absent here since a caller only ever tests {@link
	 * com.fasterxml.jackson.databind.node.ObjectNode#hasNonNull(String)}, true only for an explicit non-null value -
	 * an ABSENT {@code function} key reads exactly the same as an explicit {@code null} one). Recursing into {@code
	 * trueNode}/{@code falseNode} FIRST (post-order) is what lets a collapse CASCADE into the node's own parent in
	 * the very same pass - no second walk needed.
	 *
	 * @return the leaf name {@code pNode} now IS - either it always was a leaf, or it just collapsed into one -
	 *         or {@code null} when {@code pNode} is still a genuine decision node (its two branches disagree).
	 */
	private static String collapseSameOutcomeNodes(final ObjectNode pNode)
	{
		if (!pNode.hasNonNull("function"))
		{
			return pNode.path("name").asText();
		}
		final String trueLeaf = MatchingPolicyTranslator.collapseSameOutcomeNodes((ObjectNode) pNode.path("trueNode"));
		final String falseLeaf = MatchingPolicyTranslator.collapseSameOutcomeNodes((ObjectNode) pNode.path("falseNode"));
		if (trueLeaf != null && trueLeaf.equals(falseLeaf))
		{
			pNode.removeAll();
			pNode.put("name", trueLeaf);
			return trueLeaf;
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------- field paths

	/**
	 * The bare (slash-less) field name this codebase's own {@code requirements} JSON, {@code
	 * MatchPlanRules}/{@code MatchPlanLlmPass}, and {@code MatchPlanProfileReader}'s parsed {@code
	 * fieldCapabilities} map all key on - a leading slash, if the caller already carries one (defensive: never
	 * observed on this codebase's own output, but {@code fieldCapabilities} itself reports paths this way), is
	 * stripped so the same field is never double-counted under two different keys.
	 *
	 * @throws IllegalStateException if {@code pFieldName} is null, blank, or all whitespace - a
	 *             {@code requirements} document naming no field, or an empty string, is a defect in whoever
	 *             produced it ({@code MatchPlanRules}/{@code MatchPlanLlmPass}), not something this translator
	 *             can safely paper over with a guessed name.
	 */
	private static String normalizeFieldName(final String pFieldName)
	{
		if (pFieldName == null || pFieldName.trim().isEmpty())
		{
			throw new IllegalStateException(
				"MatchPlan.requirements names a blank/missing field: cannot build a matching policy from it. This is a defect in whoever wrote requirements "
					+ "(MatchPlanRules/MatchPlanLlmPass), not something MatchingPolicyTranslator can guess a name for.");
		}
		final String trimmed = pFieldName.trim();
		return trimmed.charAt(0) == '/' ? trimmed.substring(1) : trimmed;
	}

	/**
	 * The record-relative, leading-slash field path {@code matching_config_schema}'s own field descriptions
	 * document and the real, live {@code PointOfSale} configuration actually stores (`docs/MATCHING.md`
	 * &sect;2.1/&sect;2.5, measured 2026-09-21) - the shape BOTH {@code matchingPolicy.fields[].field} and every
	 * {@code decisionTree...fields[].field} must carry. Idempotent ({@link #normalizeFieldName} strips any
	 * leading slash first, so a name that already carries one - e.g. a future grouped attribute path such as
	 * {@code Address/City} written with its own leading slash - is never doubled), and the ONE place in this
	 * class a bare attribute name becomes a wire path: every other method in this class (weight/null-handling
	 * lookups, the field-order union, the self-check below) works on the bare name throughout.
	 */
	private static String toFieldPath(final String pFieldName)
	{
		return "/" + MatchingPolicyTranslator.normalizeFieldName(pFieldName);
	}

	/**
	 * True when {@code pPlan} compares at least one relation (child) field ({@link DecisionTreePlan.FieldRef#isRelation()})
	 * anywhere in its tree - see {@link #buildMatchingPolicyFromPlan}'s own execution-mode comment. Works
	 * unmodified whether {@code pPlan} is the ORIGINAL plan or one {@link #translate} has already renamed the relation fields of: that rewrite
	 * never clears {@link DecisionTreePlan.FieldRef#via}.
	 */
	private static boolean hasAnyRelationField(final DecisionTreePlan.Plan pPlan)
	{
		for (final DecisionTreePlan.Node node : pPlan.nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if (field.isRelation())
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Tonight's exact failure, re-created as a standing assertion (`docs/MATCHING.md`, 2026-09-21, 22:16):
	 * {@code generate_matching_policy} refuses outright ({@code INVALID_PARAMS}, "Decision tree references a
	 * field not declared on the policy: ...") when the tree compares a field {@code matchingPolicy.fields} does
	 * not declare. The field-order union built in {@link #buildMatchingPolicy} makes this structurally
	 * impossible today - this walks the built tree and checks anyway, so a future change that breaks that
	 * invariant is caught here, by a unit test, rather than by the next live call.
	 *
	 * @throws IllegalStateException naming every undeclared field, if any is found.
	 */
	private static void assertEveryTreeFieldIsDeclared(final ObjectNode pPolicy)
	{
		final Set<String> declared = new HashSet<String>();
		for (final JsonNode field : pPolicy.path("fields"))
		{
			declared.add(field.path("field").asText());
			declared.add(field.path("businessObjectField").asText());
		}
		final List<String> missing = new ArrayList<String>();
		MatchingPolicyTranslator.collectUndeclaredTreeFields(pPolicy.path("decisionTree"), declared, missing);
		if (!missing.isEmpty())
		{
			throw new IllegalStateException("MatchingPolicyTranslator built a decision tree referencing field(s) matchingPolicy.fields does not declare: "
				+ missing + " - this is exactly the refusal generate_matching_policy itself gives (INVALID_PARAMS, \"Decision tree references a field not "
				+ "declared on the policy\"). Fix the translator before sending this payload live.");
		}
	}

	/**
	 * Add-on build 6.3.0.5-RC3 (2026-10-10), measured live 2026-10-12 on Vol100kM: {@code matching_config_schema} lists two null-handling
	 * enums, {@code DO_NOTHING, NOT_MATCH, MATCH} for a matching-POLICY field ({@code matchingPolicies[].fields[]}) and {@code MATCH, NOT_MATCH} for a
	 * DECISION-TREE field, and {@code generate_matching_policy} refuses the whole entity on a tree field carrying {@code DO_NOTHING}
	 * ({@code INVALID_PARAMS "Invalid null-value handling on a decision-tree field: DO_NOTHING", nextStep "Use one of: MATCH, NOT_MATCH."}).
	 * The build of 2026-09-27 accepted the same payload.
	 * <p>
	 * This is the PAYLOAD-TIME translation: every {@code bothNullValueHandling}/{@code oneNullValueHandling} of a tree field of
	 * {@code pMatchingPolicy.decisionTree} (mutated in place) that is {@code DO_NOTHING} while {@code pTreeAllowed} (the live tree enum) has no
	 * {@code DO_NOTHING} becomes {@code NOT_MATCH}. The stored plan ({@code MatchPlan.requirements}) is NOT rewritten - {@link DecisionTreePlan},
	 * {@link CrossSystemPathRule} and the candidate-filter reasoning keep reading {@code DO_NOTHING} as "not required"; only what is sent changes.
	 * The matching-policy fields ({@code matchingPolicy.fields[]}) are left alone: they accept {@code DO_NOTHING}.
	 * <ul>
	 * <li>{@code oneNull: DO_NOTHING} -&gt; {@code NOT_MATCH}: a missing value no longer abstains, it fails the field (on an ALL_FIELDS /
	 * AT_LEAST_N node a missing value must not confirm).</li>
	 * <li>{@code bothNull: DO_NOTHING} -&gt; {@code NOT_MATCH}: two missing values no longer abstain, they fail the field.</li>
	 * <li>In an AVERAGE node a field with {@code DO_NOTHING} was "not required" (left out of the average when null); as {@code NOT_MATCH} the
	 * average counts it as 0, so the threshold of that node may deserve lowering - said in the note.</li>
	 * </ul>
	 * A tree whose allowed list is null/empty (schema not read), or that still contains {@code DO_NOTHING} (the 2026-09-27 build), is returned
	 * untouched and the result is empty. A value the list allows is never touched; a tree list without {@code NOT_MATCH} translates nothing.
	 *
	 * @param pEntityName only used in the note texts
	 * @return one human-readable line per translated value (empty when nothing was translated) - recorded as {@code nullHandlingTranslated}
	 *         in the configure step detail and summarised in its message ({@link #summariseNullHandlingTranslations})
	 */
	public static List<String> translateTreeNullHandling(final ObjectNode pMatchingPolicy, final Collection<String> pTreeAllowed, final String pEntityName)
	{
		final List<String> notes = new ArrayList<String>();
		if (pMatchingPolicy == null || pTreeAllowed == null || pTreeAllowed.isEmpty() || pTreeAllowed.contains("DO_NOTHING") || !pTreeAllowed.contains("NOT_MATCH"))
		{
			return notes;
		}
		MatchingPolicyTranslator.translateTreeNode(pMatchingPolicy.path("decisionTree"), pEntityName, notes);
		return notes;
	}

	private static void translateTreeNode(final JsonNode pNode, final String pEntityName, final List<String> pNotes)
	{
		if (pNode == null || pNode.isMissingNode() || !pNode.has("fields"))
		{
			return;
		}
		final String nestedFunction = pNode.path("function").asText("");
		final boolean average = DecisionTreePlan.NESTED_FUNCTION_AVERAGE.equals(nestedFunction);
		final String nodeName = pNode.path("name").asText("?");
		for (final JsonNode fieldNode : pNode.path("fields"))
		{
			if (!(fieldNode instanceof ObjectNode))
			{
				continue;
			}
			final ObjectNode field = (ObjectNode) fieldNode;
			for (final String property : new String[] { "bothNullValueHandling", "oneNullValueHandling" })
			{
				if (!"DO_NOTHING".equals(field.path(property).asText(null)))
				{
					continue;
				}
				field.put(property, "NOT_MATCH");
				pNotes.add(pEntityName + " / node '" + nodeName + "' (" + nestedFunction + ") / field " + field.path("field").asText("?") + " / " + property
					+ ": DO_NOTHING -> NOT_MATCH (the add-on's decision tree accepts only MATCH, NOT_MATCH; DO_NOTHING is reserved for matching-policy fields). "
					+ (average
						? "The average now counts this field as 0 when a value is missing instead of leaving it out: lower the node's minimumScore if that is too strict."
						: "A missing value no longer abstains: it fails this field, so it cannot confirm the node."));
			}
		}
		MatchingPolicyTranslator.translateTreeNode(pNode.path("trueNode"), pEntityName, pNotes);
		MatchingPolicyTranslator.translateTreeNode(pNode.path("falseNode"), pEntityName, pNotes);
	}

	/** The sentence added to a configure step's message when {@link #translateTreeNullHandling} changed something; empty string when {@code pNotes} is empty. */
	public static String summariseNullHandlingTranslations(final List<String> pNotes)
	{
		if (pNotes == null || pNotes.isEmpty())
		{
			return "";
		}
		return " NOTE: " + pNotes.size() + " decision-tree null-handling value(s) translated DO_NOTHING -> NOT_MATCH, because the add-on's decision tree accepts only "
			+ "MATCH, NOT_MATCH (the stored plan is unchanged; a missing value now fails the field instead of abstaining, and an AVERAGE node counts it as 0 - review "
			+ "its threshold). See nullHandlingTranslated in the step detail.";
	}

	private static void collectUndeclaredTreeFields(final JsonNode pNode, final Set<String> pDeclared, final List<String> pMissing)
	{
		if (pNode == null || pNode.isMissingNode() || !pNode.has("fields"))
		{
			return;
		}
		for (final JsonNode field : pNode.path("fields"))
		{
			final String path = field.path("field").asText();
			if (!pDeclared.contains(path) && !pMissing.contains(path))
			{
				pMissing.add(path);
			}
		}
		MatchingPolicyTranslator.collectUndeclaredTreeFields(pNode.path("trueNode"), pDeclared, pMissing);
		MatchingPolicyTranslator.collectUndeclaredTreeFields(pNode.path("falseNode"), pDeclared, pMissing);
	}

	// -------------------------------------------------------------------------------------- relation fields

	/**
	 * Reads the optional {@code requirements.relationClusteringWeights} object: {@code "<childEntity>.<field>"} (the
	 * relation field as the plan's tree names it: {@link DecisionTreePlan.FieldRef#via} + "." + the child's own
	 * attribute) -&gt; the pre-clustering weight of the relationship matching field, an integer &gt;= 0. Absent key, or an
	 * entry absent from it = {@link #DEFAULT_RELATION_PRECLUSTERING_WEIGHT} (<b>0</b> since lot M2, 2026-10-03; it
	 * was 1 before - `docs/MATCHING.md` &sect;48). Example: {@code {"Adresse.street":1,"Adresse.city":1}} puts those two
	 * child fields back into the candidate search.
	 *
	 * @throws IllegalStateException on a value that is not an integer &gt;= 0 (a silently ignored typo would run a
	 *             25-minute experiment on the wrong weights)
	 */
	static Map<String, Integer> readRelationClusteringWeights(final JsonNode pRequirements)
	{
		final Map<String, Integer> out = new LinkedHashMap<String, Integer>();
		final JsonNode node = pRequirements == null ? null : pRequirements.path("relationClusteringWeights");
		if (node == null || node.isMissingNode() || node.isNull())
		{
			return out;
		}
		if (!node.isObject())
		{
			throw new IllegalStateException("MatchingPolicyTranslator: requirements.relationClusteringWeights must be an object {\"<childEntity>.<field>\": <int >= 0>}, got " + node);
		}
		final Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
		while (entries.hasNext())
		{
			final Map.Entry<String, JsonNode> entry = entries.next();
			final JsonNode value = entry.getValue();
			if (!value.isIntegralNumber() || value.asInt() < 0)
			{
				throw new IllegalStateException("MatchingPolicyTranslator: requirements.relationClusteringWeights['" + entry.getKey()
					+ "'] must be an integer >= 0, got " + value);
			}
			out.put(entry.getKey(), Integer.valueOf(value.asInt()));
		}
		return out;
	}

	/** The weight for relation field {@code pVia.pField}, or {@link #DEFAULT_RELATION_PRECLUSTERING_WEIGHT} when {@code pWeights} has no entry. */
	static int relationClusteringWeight(final Map<String, Integer> pWeights, final String pVia, final String pField)
	{
		final Integer weight = pWeights.get(pVia + "." + pField);
		return weight == null ? MatchingPolicyTranslator.DEFAULT_RELATION_PRECLUSTERING_WEIGHT : weight.intValue();
	}

	/**
	 * Sum of the effective pre-clustering weights ({@link #relationClusteringWeight}) of the plan's DISTINCT relation fields
	 * (one matching-field row per {@code <childEntity>.<field>}, whichever nodes name it). 0 for a plan without relation
	 * field or when every one keeps the default weight 0. Read from the ORIGINAL {@code requirements}, so it gives the same
	 * answer before and after the rewrite of the relation fields' names in {@link #translate}.
	 */
	static int relationWeightSum(final DecisionTreePlan.Plan pPlan, final JsonNode pRequirements)
	{
		final Map<String, Integer> weights = MatchingPolicyTranslator.readRelationClusteringWeights(pRequirements);
		final Set<String> seen = new LinkedHashSet<String>();
		int sum = 0;
		for (final DecisionTreePlan.Node node : pPlan.nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if (field.isRelation() && seen.add(field.via + "." + field.field))
				{
					sum += MatchingPolicyTranslator.relationClusteringWeight(weights, field.via, field.field);
				}
			}
		}
		return sum;
	}

	// ----------------------------------------------------------------------------------------------------- mergePolicy

	/**
	 * @param pSourceKeyFieldNames the entity's own SOURCE-KEY business attribute(s) (e.g. {@code supplierId}
	 *            on {@code Company}) - bare (slash-less) master field names, identified STRUCTURALLY by the
	 *            caller through {@code StagingAttribute.businessKey} (never by matching a name against a
	 *            guessed list - see {@code ebx.MatchingConfigurator#readSourceKeyFieldNames}'s own Javadoc for
	 *            exactly which schema role this reads and why a business-key attribute that is ALSO a foreign
	 *            key is deliberately excluded there). May be {@code null} or empty (an entity with no
	 *            business-key attribute at all, or none the caller could resolve to a plain master field
	 *            name) - then only the four fixed technical fields get a survivor rule. Never re-derived or
	 *            second-guessed here: this method trusts the caller's own structural read verbatim, exactly
	 *            like {@code pRequirements} itself.
	 *
	 * @param pValidValueFieldNames lot 1 (2026-09-23, quality-informed survivorship, `docs/MATCHING.md`
	 *            &sect;23): the entity's own accepted {@code VALID_VALUE} derived attributes ({@code
	 *            ebx.MatchingConfigurator#readValidValueFieldNames}'s own structural read of {@code
	 *            StagingAttribute.derivation = VALID_VALUE}) - bare master field names (e.g. {@code
	 *            vatNoValid}). Each gets an explicit, {@code longest}-ONLY survivor rule (never a
	 *            trusted-source step - see {@link #addValidValueSurvivors}'s own Javadoc for why), added right
	 *            after the source-key rules and BEFORE any LLM-proposed business rule, so the precedence is:
	 *            technical/source-key (always wins) &gt; {@code *Valid} field (always {@code longest}, never
	 *            overridable by the model) &gt; LLM business rule (only ever reaches a field none of the above
	 *            already claimed). May be {@code null} or empty - then this parameter adds nothing, exactly
	 *            like {@code pSourceKeyFieldNames}.
	 * @throws IllegalStateException if the built policy is not well-formed (see {@link
	 *             #assertMergePolicyWellFormed}) - a defect in this method, never in the caller's input,
	 *             surfaced the same way {@link #buildMatchingPolicy}'s own {@code
	 *             assertEveryTreeFieldIsDeclared} self-check is.
	 */
	public static ObjectNode buildMergePolicy(final String pMergePolicyCode, final JsonNode pRequirements, final List<String> pSourceKeyFieldNames,
		final List<String> pValidValueFieldNames)
	{
		final JsonNode hints = pRequirements.path("mergePolicy");
		final ObjectNode mergePolicy = MatchingPolicyTranslator.MAPPER.createObjectNode();
		mergePolicy.put("code", pMergePolicyCode);
		mergePolicy.put("mode", hints.path("mode").asText("ONLY_DUPLICATES"));
		// true, not false (coordinator follow-up, 2026-09-22, after `feature/lineage-suspect-decision`'s own
		// "decide on a suspect" lot): `MergeOperations.mergeGroup(AdaptationTable, BigDecimal)` - the call the
		// Record lineage screen's own accept action makes after `Group.acceptSuspectsAndMergeGroups` - is
		// documented to require "a merge policy ... with the property `Used for manual merge` set to true"
		// (`.../service/merge/MergeOperations.html`, quoted in full in `docs/RECORD-LINEAGE.md` "Deciding on a
		// suspect"). A policy this factory writes with the flag false would be refused by that exact call on
		// every instance, not just this one - measured live 2026-09-22, `get_matching_config` on `/root/Company`
		// answered `"useForManualMerge":false` for the policy `matching/configure` had just written. The whole
		// point of this factory's model is a human deciding a merge from the lineage screen (`docs/PLAN.md`'s
		// own "Objectif") - a policy a steward cannot use by hand that way is useless here, so this is a fixed
		// default, not a per-project choice, until a real product need for the opposite ever appears.
		mergePolicy.put("useForManualMerge", true);
		mergePolicy.put("applyPermission", false);
		// lot M1 (2026-10-17): the source of a golden record the add-on creates (class Javadoc) - replaces the former constant("MDM") survivor on /sourceSystem
		mergePolicy.put("autoCreateNewGoldenSource", MatchingPolicyTranslator.GOLDEN_SOURCE_CODE);
		// relatedTables deliberately omitted: CLAUDE.md sec 8 - generate_merge_policy adds every inverse-FK
		// table on its own, MANUAL by default; passing one explicitly here would just end up duplicated.

		// recordSelectionFunctionChain: "most trusted source" always tried FIRST (trusted-sources lot,
		// 2026-09-22 - docs/MATCHING.md "Survivorship and trusted sources") - a group with a clear most-trusted
		// contributor picks it outright; MAME's own chain semantics fall through to the next entry only when
		// the first cannot decide (e.g. no trusted-source ranking configured for any contributing source), so
		// the project's own hint (default mostCompleted) stays as the SECOND entry, a safety net, never
		// dropped. If the hint itself already IS mostTrustedSource, the chain collapses to that one entry -
		// never duplicated.
		final ArrayNode recordSelection = mergePolicy.putArray("recordSelectionFunctionChain");
		final String recordSelectionHintKey = hints.path("recordSelection").asText(MatchingPolicyTranslator.RECORD_SELECTION_DEFAULT);
		if (!MatchingPolicyTranslator.RECORD_SELECTION_MOST_TRUSTED_SOURCE.equals(recordSelectionHintKey))
		{
			MatchingPolicyTranslator.addRecordSelectionEntry(recordSelection, MatchingPolicyTranslator.RECORD_SELECTION_MOST_TRUSTED_SOURCE);
		}
		MatchingPolicyTranslator.addRecordSelectionEntry(recordSelection, recordSelectionHintKey);

		// defaultFieldMergeFunctionChain: same "most trusted source first, then the project's own default"
		// rule, one literal-string level down (this chain's own enum has no i18n keys at all - see the
		// FUNCTION_* constants' own Javadoc).
		final ArrayNode defaultFieldMerge = mergePolicy.putArray("defaultFieldMergeFunctionChain");
		final String defaultFieldMergeHint = hints.path("defaultFieldMerge").asText("longest");
		if (!MatchingPolicyTranslator.FUNCTION_MOST_TRUSTED_SOURCE.equals(defaultFieldMergeHint))
		{
			defaultFieldMerge.addObject().put("function", MatchingPolicyTranslator.FUNCTION_MOST_TRUSTED_SOURCE);
		}
		defaultFieldMerge.addObject().put("function", defaultFieldMergeHint);

		// survivors: the technical fields ALWAYS get an explicit rule (never left to fall through to the
		// trusted-source/longest default, which is exactly the bug this lot exists to fix - see class Javadoc
		// "the gap"), plus one "no merge" rule per structurally-identified source-key business attribute.
		final ArrayNode survivors = mergePolicy.putArray("survivors");
		final Set<String> survivorFieldPaths = new HashSet<String>();
		// lot M1 (2026-10-17): "no merge", never constant("MDM") - a singleton of a business-object main table is merged IN PLACE and a constant rewrote its
		// source (class Javadoc); the golden the add-on creates gets its source from autoCreateNewGoldenSource (put on the policy above)
		MatchingPolicyTranslator.addSurvivor(survivors, survivorFieldPaths, MatchingPolicyTranslator.TECHNICAL_FIELD_SOURCE_SYSTEM,
			MatchingPolicyTranslator.functionEntry(MatchingPolicyTranslator.FUNCTION_NO_MERGE));
		MatchingPolicyTranslator.addSurvivor(survivors, survivorFieldPaths, MatchingPolicyTranslator.TECHNICAL_FIELD_SOURCE_ID,
			MatchingPolicyTranslator.functionEntry(MatchingPolicyTranslator.FUNCTION_NO_MERGE));
		MatchingPolicyTranslator.addSurvivor(survivors, survivorFieldPaths, MatchingPolicyTranslator.TECHNICAL_FIELD_LOAD_ID,
			MatchingPolicyTranslator.functionEntry(MatchingPolicyTranslator.FUNCTION_NO_MERGE));
		MatchingPolicyTranslator.addSurvivor(survivors, survivorFieldPaths, MatchingPolicyTranslator.TECHNICAL_FIELD_RECEIVED_AT,
			MatchingPolicyTranslator.functionEntry(MatchingPolicyTranslator.FUNCTION_MAX));
		if (pSourceKeyFieldNames != null)
		{
			for (final String sourceKeyField : pSourceKeyFieldNames)
			{
				if (sourceKeyField != null && !sourceKeyField.trim().isEmpty())
				{
					MatchingPolicyTranslator.addSurvivor(survivors, survivorFieldPaths, sourceKeyField.trim(),
						MatchingPolicyTranslator.functionEntry(MatchingPolicyTranslator.FUNCTION_NO_MERGE));
				}
			}
		}
		// Lot 1 (2026-09-23, quality-informed survivorship): every *Valid derived field gets its own, fixed,
		// non-overridable longest-only rule NEXT - after technical/source-key (which always win via addSurvivor's
		// own dedupe below), BEFORE the LLM's own business rules (so a *Valid field can never be reassigned a
		// different function by a language-model proposal - see addValidValueSurvivors' own Javadoc).
		MatchingPolicyTranslator.addValidValueSurvivors(survivors, survivorFieldPaths, pValidValueFieldNames);

		// Survivorship lot (2026-09-22, docs/MATCHING.md sec 15): the LLM-proposed business-field rules, read
		// straight off pRequirements.survivorship.fields[] - master.MatchPlanLlmPass.Session#buildSurvivorship
		// already validated the field/function vocabulary at proposal time, but this method never trusts that
		// blindly (same discipline as every other read of pRequirements in this class): addSurvivor's own dedupe
		// makes "technical/source-key/*Valid always wins" automatic here - a business rule on a field already
		// claimed by any rule above (technical, source-key, or a *Valid field) is silently skipped, never a
		// second rule for the same path.
		MatchingPolicyTranslator.addBusinessSurvivors(survivors, survivorFieldPaths, pRequirements.path("survivorship").path("fields"));

		MatchingPolicyTranslator.assertMergePolicyWellFormed(mergePolicy);
		return mergePolicy;
	}

	/**
	 * The four fixed technical field names {@link #buildMergePolicy} always writes a survivor rule for - exposed so
	 * a caller/test can recognise (and message a reviewer about) a business survivorship rule dropped because it
	 * collided with one of these, without duplicating the literal list. Does NOT include a source-key business
	 * attribute (those are per-entity, structurally identified by the caller - see {@code
	 * ebx.MatchingConfigurator#readSourceKeyFieldNames}).
	 */
	public static Set<String> technicalSurvivorFieldNames()
	{
		final Set<String> out = new LinkedHashSet<String>();
		out.add(MatchingPolicyTranslator.TECHNICAL_FIELD_SOURCE_SYSTEM);
		out.add(MatchingPolicyTranslator.TECHNICAL_FIELD_SOURCE_ID);
		out.add(MatchingPolicyTranslator.TECHNICAL_FIELD_LOAD_ID);
		out.add(MatchingPolicyTranslator.TECHNICAL_FIELD_RECEIVED_AT);
		return out;
	}

	/**
	 * Adds one survivor entry per {@code pFieldsNode} item ({@code MatchPlan.requirements.survivorship.fields[]}'s
	 * own shape - {@code {field, chain:[{function, constantValue?, dateFieldPath?, trustedSourceList?}], rationale,
	 * confidence}}) - {@code trustedSourceList} is stripped (it belongs in the {@code set_trusted_sources} payload,
	 * see {@link #extractFieldTrustedSourceOverrides}, never in a merge-policy chain entry) and {@code rationale}/
	 * {@code confidence} are dropped too (they live in {@code MatchPlan.evidence}, not the wire payload). Defensive
	 * only: a chain entry using {@code constant}/{@code no merge} (never legal on a business field - {@code
	 * master.MatchPlanLlmPass.BUSINESS_SURVIVOR_FUNCTIONS} already excludes both) or an empty/blank field or chain
	 * silently drops the WHOLE rule, the same "never trust upstream blindly, never crash either" discipline this
	 * class applies everywhere else - {@code MatchPlanLlmPass.Session} is expected to have already refused these
	 * at proposal time, so this is a second line of defence, not the primary check.
	 */
	private static void addBusinessSurvivors(final ArrayNode pSurvivors, final Set<String> pSeenFieldPaths, final JsonNode pFieldsNode)
	{
		for (final JsonNode fieldRule : pFieldsNode)
		{
			final String field = fieldRule.path("field").asText(null);
			final JsonNode chainNode = fieldRule.path("chain");
			if (field == null || field.trim().isEmpty() || !chainNode.isArray() || chainNode.size() == 0)
			{
				continue;
			}
			final String path = MatchingPolicyTranslator.toFieldPath(field.trim());
			if (pSeenFieldPaths.contains(path))
			{
				// technical/source-key rule already claims this field path - it always wins (class Javadoc,
				// "Trusted sources and survivorship").
				continue;
			}
			final ArrayNode builtChain = MatchingPolicyTranslator.MAPPER.createArrayNode();
			boolean valid = true;
			for (final JsonNode chainEntry : chainNode)
			{
				final String function = chainEntry.path("function").asText("");
				if (function.isEmpty() || MatchingPolicyTranslator.FUNCTION_CONSTANT.equals(function) || MatchingPolicyTranslator.FUNCTION_NO_MERGE.equals(function))
				{
					valid = false;
					break;
				}
				final ObjectNode builtEntry = MatchingPolicyTranslator.MAPPER.createObjectNode();
				builtEntry.put("function", function);
				if (chainEntry.hasNonNull("constantValue"))
				{
					builtEntry.put("constantValue", chainEntry.path("constantValue").asText());
				}
				if (chainEntry.hasNonNull("dateFieldPath"))
				{
					builtEntry.put("dateFieldPath", chainEntry.path("dateFieldPath").asText());
				}
				builtChain.add(builtEntry);
			}
			if (!valid || builtChain.size() == 0)
			{
				continue;
			}
			pSeenFieldPaths.add(path);
			final ObjectNode survivor = pSurvivors.addObject();
			survivor.put("field", path);
			survivor.set("mergeFunctionChain", builtChain);
		}
	}

	/**
	 * Lot 1 (2026-09-23, quality-informed survivorship, `docs/MATCHING.md` &sect;23): one {@code {field:
	 * "/<name>", mergeFunctionChain: [{function: "longest"}]}} survivor per entry of {@code pFieldNames} - the
	 * entity's own accepted {@code VALID_VALUE} derived attributes ({@code
	 * ebx.MatchingConfigurator#readValidValueFieldNames}). {@code longest} ONLY, never a trusted-source step:
	 * the whole point of a {@code *Valid} field ({@code quality.ValidValueDerivation}'s own Javadoc) is that it
	 * already carries {@code null} for every contributing record whose value fails the source attribute's own
	 * quality rule - {@code longest} then structurally prefers whichever contributing record's value is BOTH
	 * present and non-empty (a {@code null}/empty value is never the longest candidate once at least one
	 * contributor has a real one) without the add-on ever evaluating the rule itself.
	 * <p>
	 * <b>UNVERIFIED (task brief's own instruction to say so): the add-on's actual behaviour of {@code longest}
	 * when every contributing record's value is empty/null has never been measured live this lot</b> (no EBX
	 * access this session, same constraint as every other class this lot touches). Reasoned only, from the
	 * function's own name and the add-on's documented "longest non-empty value wins" framing (`matching_guide`,
	 * merge-strategy topic): an empty string has length zero, so it should never beat a non-empty one, and a
	 * chain of ONE entry with nothing left to fall through to presumably leaves the field simply empty on the
	 * golden record when every contributor is invalid - never confirmed against a live {@code
	 * generate_merge_policy}/real merge run. See `docs/MATCHING.md` &sect;23 for this and every other unverified
	 * point of this lot, gathered in one place.
	 * <p>
	 * Never a trusted-source override either: unlike {@link #addBusinessSurvivors}, this never reads {@code
	 * pRequirements} at all (no second read of requirements, per the task brief's own instruction - the *Valid
	 * field names arrive already resolved, the same "list the caller already receives" pattern {@code
	 * pSourceKeyFieldNames} itself established) - a business rule the model might otherwise have proposed for
	 * this exact field path is silently superseded by {@link #addBusinessSurvivors}'s own dedupe (the survivor
	 * already claims the path by the time that method runs), never a second, competing rule.
	 */
	private static void addValidValueSurvivors(final ArrayNode pSurvivors, final Set<String> pSeenFieldPaths, final List<String> pFieldNames)
	{
		if (pFieldNames == null)
		{
			return;
		}
		for (final String fieldName : pFieldNames)
		{
			if (fieldName == null || fieldName.trim().isEmpty())
			{
				continue;
			}
			MatchingPolicyTranslator.addSurvivor(pSurvivors, pSeenFieldPaths, fieldName.trim(),
				MatchingPolicyTranslator.functionEntry(MatchingPolicyTranslator.FUNCTION_LONGEST));
		}
	}

	/**
	 * The {@code set_trusted_sources} {@code trustedSources.fieldTrustedSources[]} payload derived from {@code
	 * pRequirements.survivorship.fields[]} - one entry per business field whose chain carries a {@code "most
	 * trusted source"} function WITH a non-empty, VALID {@code trustedSourceList} override (a plain {@code "most
	 * trusted source"} entry with no override, or one whose override resolved to nothing, needs no field-level
	 * entry: the table's own {@code tableTrustedSourceList} already applies). Only the FIRST such chain entry per
	 * field is read (a chain normally carries at most one {@code "most trusted source"} entry - {@code
	 * master.MatchPlanLlmPass}'s own tool schema does not forbid two, but nothing in this codebase ever proposes
	 * it).
	 * <p>
	 * <b>Run-30 self-check lot (2026-09-22, `docs/MATCHING.md` &sect;15).</b> {@code
	 * master.MatchPlanLlmPass.Session#buildSurvivorship} already validates a {@code trustedSourceList} entry
	 * against the project's real codes AT PROPOSAL TIME (its own Javadoc, added the same date) - but this method
	 * is the LAST call before {@code set_trusted_sources}, and it must not simply trust that a stored plan went
	 * through that gate: a plan created before this lot existed (the run-30 case: {@code
	 * ["ERP - Snowflake - analytics (demo)"]}, the source's LABEL, not its code {@code "ERP"}) is read exactly the
	 * same way a freshly-proposed one is. So this method REPEATS the same two-step resolution independently here
	 * (never trust upstream blindly - same discipline {@link #addBusinessSurvivors} already applies to the rest of
	 * a survivorship rule): an entry equal to a {@code pProjectSources} code is kept; one equal to a source's
	 * description (case-insensitive, trimmed) is mapped to its code; anything else is dropped
	 * (UNKNOWN_SOURCE_CODE) - and if a field's whole list resolves to nothing, the field gets no override entry at
	 * all (falls back to the table's own ranking), never an empty {@code trustedSourceList}. A final self-check
	 * ({@link #assertEveryFieldTrustedSourceIsKnown}) then asserts the built output never carries a code outside
	 * {@code pProjectSources} - a belt-and-braces {@link IllegalStateException} that should never fire given the
	 * filtering just above, guarding only against a FUTURE change to that filtering breaking the invariant, same
	 * pattern as {@link #assertMergePolicyWellFormed}.
	 * <p>
	 * Per the hotfix contract {@code master.TrustedSourceRanking#buildTrustedSourcesPayload} already documents ("a
	 * supplied fieldTrustedSources list deletes any override not present"), the caller ({@code
	 * ebx.MatchingConfigurator}) must always send the FULL list this method returns, never a partial/incremental
	 * one - this method itself has no state to make that mistake with (pure, stateless, one requirements document
	 * in, one full list out every time).
	 *
	 * @param pProjectSources the project's OWN trusted-source ranking ({@code
	 * ebx.MatchingConfigurator#readTrustedSources}'s own read, order irrelevant here - only {@code code}/{@code
	 * description} are consulted) - the sole vocabulary a {@code trustedSourceList} entry may legitimately name.
	 * Never null; an empty list resolves every entry as unknown (a project with no declared sources has no valid
	 * trusted-source code at all).
	 */
	public static ArrayNode extractFieldTrustedSourceOverrides(final JsonNode pRequirements, final List<TrustedSourceRanking.Source> pProjectSources)
	{
		return MatchingPolicyTranslator.extractFieldTrustedSourceOverrides(pRequirements, pProjectSources, new ArrayList<String>());
	}

	/**
	 * Full form - see the 2-arg overload's own Javadoc for the resolution/self-check contract.
	 *
	 * @param pDroppedNotes appended with one human-readable reason per entry (or whole override) dropped/mapped -
	 * same discipline as {@code master.MatchPlanLlmPass.Session#buildSurvivorship}'s own {@code pDroppedNotes}, so
	 * a caller ({@code ebx.MatchingConfigurator}) can surface a LEGACY plan's correction in its own recorded
	 * detail exactly the way a freshly-proposed plan's own drops are surfaced in its rationale. Never null.
	 */
	public static ArrayNode extractFieldTrustedSourceOverrides(final JsonNode pRequirements, final List<TrustedSourceRanking.Source> pProjectSources,
		final List<String> pDroppedNotes)
	{
		final ArrayNode out = MatchingPolicyTranslator.MAPPER.createArrayNode();
		for (final JsonNode fieldRule : pRequirements.path("survivorship").path("fields"))
		{
			final String field = fieldRule.path("field").asText(null);
			if (field == null || field.trim().isEmpty())
			{
				continue;
			}
			for (final JsonNode chainEntry : fieldRule.path("chain"))
			{
				if (!MatchingPolicyTranslator.FUNCTION_MOST_TRUSTED_SOURCE.equals(chainEntry.path("function").asText("")))
				{
					continue;
				}
				final JsonNode listNode = chainEntry.path("trustedSourceList");
				if (listNode.isArray() && listNode.size() > 0)
				{
					final ArrayNode list = MatchingPolicyTranslator.MAPPER.createArrayNode();
					for (final JsonNode code : listNode)
					{
						final String codeText = code.asText("").trim();
						if (codeText.isEmpty())
						{
							continue;
						}
						final String resolved = MatchingPolicyTranslator.resolveTrustedSourceCode(codeText, field.trim(), pProjectSources, pDroppedNotes);
						if (resolved != null)
						{
							list.add(resolved);
						}
					}
					if (list.size() > 0)
					{
						final ObjectNode override = out.addObject();
						override.put("field", MatchingPolicyTranslator.toFieldPath(field.trim()));
						override.set("trustedSourceList", list);
					}
					else
					{
						pDroppedNotes.add("trusted-source override for '" + field.trim()
							+ "' dropped entirely: no valid source code remained (kept the table's own trusted-source ranking).");
					}
				}
				break;
			}
		}
		MatchingPolicyTranslator.assertEveryFieldTrustedSourceIsKnown(out, pProjectSources);
		return out;
	}

	/**
	 * Resolves ONE {@code trustedSourceList} entry against {@code pProjectSources} - see {@link
	 * #extractFieldTrustedSourceOverrides}'s own Javadoc for the exact-code/description-mapping/unknown contract.
	 * Mirrors {@code master.MatchPlanLlmPass.Session#resolveTrustedSourceCode} (proposal-time twin of this
	 * acceptance-time check) deliberately kept as two small, independent copies rather than one shared helper:
	 * they run at different times, against different failure consequences (a dropped proposal note vs. a
	 * mis-configured live {@code set_trusted_sources} call), and {@code master} classes never depend on {@code
	 * ebx} classes or vice versa in this codebase.
	 *
	 * @return the resolved CODE, or {@code null} when {@code pCodeText} names no known source (a reason is
	 * already appended to {@code pDroppedNotes} in that case).
	 */
	private static String resolveTrustedSourceCode(final String pCodeText, final String pField, final List<TrustedSourceRanking.Source> pProjectSources,
		final List<String> pDroppedNotes)
	{
		for (final TrustedSourceRanking.Source source : pProjectSources)
		{
			if (source.code.equals(pCodeText))
			{
				return source.code;
			}
		}
		for (final TrustedSourceRanking.Source source : pProjectSources)
		{
			if (source.description.trim().equalsIgnoreCase(pCodeText))
			{
				pDroppedNotes.add("trusted-source override for '" + pField + "': entry '" + pCodeText + "' is a source's LABEL, not its code - mapped to '"
					+ source.code + "'.");
				return source.code;
			}
		}
		pDroppedNotes.add("trusted-source override for '" + pField + "': entry '" + pCodeText
			+ "' does not match any of this project's trusted-source codes (UNKNOWN_SOURCE_CODE) - dropped.");
		return null;
	}

	/**
	 * Self-check on THIS class' own {@link #extractFieldTrustedSourceOverrides} output (never on caller input -
	 * same "defensive only" discipline {@link #assertMergePolicyWellFormed} already applies) - a phantom code must
	 * never reach {@code set_trusted_sources}, whatever future edit might otherwise let one slip through the
	 * filtering above.
	 *
	 * @throws IllegalStateException naming the specific unknown code found, if any.
	 */
	private static void assertEveryFieldTrustedSourceIsKnown(final ArrayNode pOverrides, final List<TrustedSourceRanking.Source> pProjectSources)
	{
		final Set<String> knownCodes = new HashSet<String>();
		for (final TrustedSourceRanking.Source source : pProjectSources)
		{
			knownCodes.add(source.code);
		}
		for (final JsonNode override : pOverrides)
		{
			for (final JsonNode code : override.path("trustedSourceList"))
			{
				if (!knownCodes.contains(code.asText("")))
				{
					throw new IllegalStateException("MatchingPolicyTranslator built a fieldTrustedSources entry for '" + override.path("field").asText()
						+ "' naming an unknown trusted-source code '" + code.asText() + "' - refusing to let it reach set_trusted_sources: " + pOverrides);
				}
			}
		}
	}

	private static void addRecordSelectionEntry(final ArrayNode pChain, final String pKey)
	{
		final String function = MatchingPolicyTranslator.RECORD_SELECTION_FUNCTIONS.get(pKey);
		pChain.addObject().put("function",
			function == null ? MatchingPolicyTranslator.RECORD_SELECTION_FUNCTIONS.get(MatchingPolicyTranslator.RECORD_SELECTION_DEFAULT) : function);
	}

	/** Adds one survivor rule, skipping a field path already carrying one (first rule wins - defensive, e.g. a source-key attribute that happened to be literally named {@code sourceId}) - see {@link #toFieldPath} for the path convention. */
	private static void addSurvivor(final ArrayNode pSurvivors, final Set<String> pSeenFieldPaths, final String pFieldName, final ObjectNode pEntry)
	{
		final String path = MatchingPolicyTranslator.toFieldPath(pFieldName);
		if (!pSeenFieldPaths.add(path))
		{
			return;
		}
		final ObjectNode survivor = pSurvivors.addObject();
		survivor.put("field", path);
		survivor.putArray("mergeFunctionChain").add(pEntry);
	}

	private static ObjectNode functionEntry(final String pFunction)
	{
		return MatchingPolicyTranslator.MAPPER.createObjectNode().put("function", pFunction);
	}

	/**
	 * The merge-policy analogue of {@link #assertEveryTreeFieldIsDeclared}: a self-check on THIS method's own
	 * output, never on caller input, so a future change to the chain-building logic above that breaks one of
	 * {@code matching_config_schema}'s own contracts (a {@code constant} entry not last, a blank function, a
	 * duplicated survivor field) is caught here, by a unit test, rather than as a live {@code
	 * generate_merge_policy}/{@code patch_matching_config} refusal.
	 *
	 * @throws IllegalStateException naming the specific defect found, if any.
	 */
	static void assertMergePolicyWellFormed(final ObjectNode pMergePolicy)
	{
		MatchingPolicyTranslator.assertChainWellFormed(pMergePolicy.path("recordSelectionFunctionChain"), false, "recordSelectionFunctionChain");
		MatchingPolicyTranslator.assertChainWellFormed(pMergePolicy.path("defaultFieldMergeFunctionChain"), false, "defaultFieldMergeFunctionChain");
		final Set<String> seenFields = new HashSet<String>();
		for (final JsonNode survivor : pMergePolicy.path("survivors"))
		{
			final String field = survivor.path("field").asText("");
			if (field.isEmpty())
			{
				throw new IllegalStateException("MatchingPolicyTranslator built a survivor entry with a blank field path: " + pMergePolicy);
			}
			if (!seenFields.add(field))
			{
				throw new IllegalStateException("MatchingPolicyTranslator built two survivor entries for the same field " + field + ": " + pMergePolicy);
			}
			MatchingPolicyTranslator.assertChainWellFormed(survivor.path("mergeFunctionChain"), true, "survivors[" + field + "].mergeFunctionChain");
			for (final JsonNode chainEntry : survivor.path("mergeFunctionChain"))
			{
				if (MatchingPolicyTranslator.FUNCTION_CONSTANT.equals(chainEntry.path("function").asText("")))
				{
					// lot M1 (2026-10-17): the add-on applies the survivors to a SINGLETON record in place on a business-object main table, so a constant
					// overwrites a real record (all 534 sourceSystem values became "MDM"): a golden's source is autoCreateNewGoldenSource, never a constant.
					throw new IllegalStateException("MatchingPolicyTranslator built a 'constant' survivor for " + field
						+ " - refused: the add-on applies survivors to singleton records in place (use autoCreateNewGoldenSource for a golden's source): " + pMergePolicy);
				}
			}
		}
		if (!pMergePolicy.hasNonNull("autoCreateNewGoldenSource") || pMergePolicy.path("autoCreateNewGoldenSource").asText("").isEmpty())
		{
			throw new IllegalStateException("MatchingPolicyTranslator built a merge policy without autoCreateNewGoldenSource (the source of a golden it creates): " + pMergePolicy);
		}
	}

	private static void assertChainWellFormed(final JsonNode pChain, final boolean pAllowConstantOrNoMerge, final String pWhere)
	{
		if (!pChain.isArray() || pChain.size() == 0)
		{
			throw new IllegalStateException("MatchingPolicyTranslator built an empty/non-array chain at " + pWhere);
		}
		for (int i = 0; i < pChain.size(); i++)
		{
			final JsonNode entry = pChain.get(i);
			final String function = entry.path("function").asText("");
			if (function.isEmpty())
			{
				throw new IllegalStateException("MatchingPolicyTranslator built a chain entry with a blank function at " + pWhere + "[" + i + "]");
			}
			final boolean isConstant = MatchingPolicyTranslator.FUNCTION_CONSTANT.equals(function);
			final boolean isNoMerge = MatchingPolicyTranslator.FUNCTION_NO_MERGE.equals(function);
			if ((isConstant || isNoMerge) && !pAllowConstantOrNoMerge)
			{
				throw new IllegalStateException(
					"MatchingPolicyTranslator built a '" + function + "' entry in " + pWhere + " - only a survivor chain may use it (matching_config_schema's own enum)");
			}
			if (isConstant)
			{
				if (i != pChain.size() - 1)
				{
					throw new IllegalStateException("MatchingPolicyTranslator built a 'constant' entry that is not last in " + pWhere);
				}
				if (entry.path("constantValue").asText("").isEmpty())
				{
					throw new IllegalStateException("MatchingPolicyTranslator built a 'constant' entry with no constantValue in " + pWhere);
				}
			}
		}
	}
}
