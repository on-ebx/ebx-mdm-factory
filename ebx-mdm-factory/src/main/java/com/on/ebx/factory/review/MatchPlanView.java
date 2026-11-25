package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.matching.CandidateFilterCheck;
import com.on.ebx.factory.matching.KeyFindings;

/**
 * Renders the expanded body of the "Matching" review line (PLAN.md, 2026-09-22, "Maquette validee ...
 * la ligne Matching de la revue, dépliée" - {@code docs/mockups/review-match-plan.html}): the decision
 * tree as it will be executed, the per-field evidence, and the "what it would give on your data"
 * simulation block. Pure: no EBX class - {@code review.ReviewPage} calls this for a {@link
 * com.on.ebx.factory.review.ReviewLine#MATCH} line's expanded body, the EBX-bound side ({@code
 * ebx.ProposalReviews}/{@code ebx.ui.ProposalReviewUserService}) reads {@code MatchPlan.requirements}/
 * {@code evidence}/{@code autoMerge} and any stored simulation result and builds the {@link Input}.
 * <p>
 * Every dynamic string is HTML-escaped ({@link ReviewPage#esc}, same package) before it reaches the
 * output - nothing here trusts that a caller already escaped anything, the same discipline {@link
 * ReviewPage} itself follows.
 * <p>
 * <b>The tree drawn here must be the tree {@code master.MatchingPolicyTranslator} will actually send</b>
 * (task brief, block 1): a tier whose own proposed outcome is {@code MATCH} is drawn reaching a real
 * "Automatic merge" leaf ONLY when {@link Input#autoMerge} is true (wording shortened from "Merged
 * automatically" - tree-diagram-labels fix lot, 2026-09-22, {@code docs/MATCHING.md} &sect;16.10) -
 * otherwise it is drawn reaching the SAME "to a data steward" leaf every {@code SUSPECTED} tier reaches,
 * with a separate, dashed, informational "automatic merge - disabled for this plan" box next to it (never a real branch -
 * the translator forces the leaf itself to {@code SUSPECTED}, see that class' own Javadoc, "autoMerge and
 * the MATCH outcome").
 */
public final class MatchPlanView
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MatchPlanView()
	{
	}

	/**
	 * Node selection and fold expansion for the decision-tree block ({@link MatchPlanTreeDiagram#SCRIPT}) -
	 * binary-decision-tree-screen lot, 2026-09-22. {@code ebx.ui.ProposalReviewUserService.pageJs} appends this
	 * into the page's own single {@code addJS_cr} script (CLAUDE.md &sect;9: never a hand-written {@code
	 * <script>} tag) - exposed here because {@link MatchPlanTreeDiagram} itself is package-private and {@code
	 * ebx.ui} is a different package.
	 */
	public static String treeScript()
	{
		return MatchPlanTreeDiagram.SCRIPT;
	}

	// ------------------------------------------------------------------------------------------------------- input

	/**
	 * One candidate pair the add-on's OWN {@code simulateMatch} examined for a drawn pivot -
	 * review-match-plan-simulation lot (2026-09-22), replacing the earlier one-target-per-shared-key shape
	 * entirely (see {@link MatchSimulationBlock}'s own class Javadoc for why, and for exactly what is real vs.
	 * derived below). Built by the EBX-bound caller ({@code ebx.MatchPlanSimulationRunner}) from {@code
	 * ebx.MameMatchingApi.SimulationMatch} (verdict + the two source systems + every field the plan compares,
	 * read directly off the two records the add-on itself just compared) plus, when it could be obtained,
	 * {@code ebx.MameMatchingApi.TreeEvaluation}'s own real per-field scores ({@code
	 * ebx.MameMatchingApi#evaluatePair} - {@code MatchingOperations.executeDecisionTree}).
	 */
	public static final class SimulationPair
	{
		/** Default {@link #origin} (also what the legacy 8-arg constructor sets) - the add-on's own {@code simulateMatch} pre-clustering offered this pair on its own. */
		public static final String ORIGIN_ENGINE = "ENGINE_CANDIDATE";
		/** {@link #origin}: {@code ebx.MatchPlanSimulationRunner} selected this pair itself (a live SQL query, never the add-on's own clustering) because the two records share the plan's identifying key across systems - review-match-plan-simulation-probes lot, 2026-09-22. */
		public static final String ORIGIN_PROBE_SHARED_KEY = "PROBE_SHARED_KEY";
		/** {@link #origin}: selected because the two records share the plan's non-fuzzy exact fields while carrying a DIFFERENT identifying key - same lot. */
		public static final String ORIGIN_PROBE_NEAR_MISS = "PROBE_NEAR_MISS";

		public final String pivotLabel;
		public final String pivotSystem;
		public final String targetLabel;
		public final String targetSystem;
		/** Field name (as it appears in the plan's own tiers) to its raw value on the pivot record - null/missing when the field is empty on that record. */
		public final Map<String, String> pivotValues;
		/** Same shape as {@link #pivotValues}, for the target record. */
		public final Map<String, String> targetValues;
		/**
		 * Field name to the add-on's own real, per-field similarity score (`javap`-found {@code
		 * DecisionTreeExecutionResult$ScoreField.getScore()}, in whatever units the engine itself uses - see
		 * {@code ebx.MameMatchingApi.FieldScore}'s own Javadoc on the unresolved 0-100-vs-0-1 question) - a
		 * field absent from this map means the score could not be obtained for this pair (bounded attempt cap,
		 * or the call failed), NEVER that the engine measured zero for it. Never a factory-side estimate.
		 */
		public final Map<String, Double> fieldScores;
		/** The raw {@code MatchingResultType.name()} (e.g. {@code MATCHED}/{@code SUSPECTED}/{@code NOT_MATCHED}) - translated to a chip at render time, shown as-is when not recognised. */
		public final String verdict;
		/** One of the {@code ORIGIN_*} constants - never null (the legacy 8-arg constructor sets {@link #ORIGIN_ENGINE}). {@code review.MatchSimulationBlock} reads this to word a "Source" cell and to decide whether an empty family 1-3 falls back to family 0. */
		public final String origin;
		/**
		 * {@code Boolean.TRUE}/{@code FALSE} when {@code ebx.MatchPlanSimulationRunner} could read the plan's
		 * own identifying key field (a {@code FOLD_KEY}-derived attribute, e.g. {@code nameKey}, or - absent
		 * one - the plan's own first exact-tier field) on BOTH sides of this pair and compare the two raw
		 * values (case-insensitive, trimmed); {@code null} when no such field exists for this plan, or either
		 * side lacks a value for it - {@code review.MatchSimulationBlock} then falls back to its own older
		 * {@code exactFieldsEqual} test (the plan's exact-TIER fields, a different question) rather than
		 * guessing. Read directly off the two {@code Adaptation}s the runner itself holds, never re-derived by
		 * {@code review.MatchSimulationBlock}, which has no EBX access and cannot look the key field's NAME up
		 * on its own (that lookup needs a Staging table only the {@code ebx} package can reach).
		 */
		public final Boolean sameKey;
		/**
		 * Binary-decision-tree-screen lot (2026-09-22): {@code ebx.MameMatchingApi.TreeEvaluation#nodeNames} for
		 * THIS pair, in the add-on's own visit order - {@code DecisionNode.getName()} for every comparison node
		 * {@code executeDecisionTree} actually walked to reach its verdict, the SAME strings {@link
		 * ReviewDecisionTree#engineName} computes from the stored plan. Empty (never null) when {@code
		 * evaluatePair} was never attempted for this pair or failed - {@code review.MatchSimulationBlock} then
		 * falls back to its own field/score-based heuristic walk (the only thing available before this lot, kept
		 * as the fallback rather than replaced) instead of a real named path. Never a factory-side guess at what
		 * the add-on actually visited.
		 */
		public final List<String> enginePath;

		public SimulationPair(final String pPivotLabel, final String pPivotSystem, final String pTargetLabel, final String pTargetSystem,
			final Map<String, String> pPivotValues, final Map<String, String> pTargetValues, final Map<String, Double> pFieldScores, final String pVerdict)
		{
			this(pPivotLabel, pPivotSystem, pTargetLabel, pTargetSystem, pPivotValues, pTargetValues, pFieldScores, pVerdict, SimulationPair.ORIGIN_ENGINE, null);
		}

		public SimulationPair(final String pPivotLabel, final String pPivotSystem, final String pTargetLabel, final String pTargetSystem,
			final Map<String, String> pPivotValues, final Map<String, String> pTargetValues, final Map<String, Double> pFieldScores, final String pVerdict,
			final String pOrigin, final Boolean pSameKey)
		{
			this(pPivotLabel, pPivotSystem, pTargetLabel, pTargetSystem, pPivotValues, pTargetValues, pFieldScores, pVerdict, pOrigin, pSameKey, null);
		}

		/** Full form - see {@link #enginePath} (binary-decision-tree-screen lot, 2026-09-22). */
		public SimulationPair(final String pPivotLabel, final String pPivotSystem, final String pTargetLabel, final String pTargetSystem,
			final Map<String, String> pPivotValues, final Map<String, String> pTargetValues, final Map<String, Double> pFieldScores, final String pVerdict,
			final String pOrigin, final Boolean pSameKey, final List<String> pEnginePath)
		{
			this.pivotLabel = pPivotLabel;
			this.pivotSystem = pPivotSystem == null ? "" : pPivotSystem;
			this.targetLabel = pTargetLabel;
			this.targetSystem = pTargetSystem == null ? "" : pTargetSystem;
			this.pivotValues = pPivotValues == null ? new LinkedHashMap<String, String>() : pPivotValues;
			this.targetValues = pTargetValues == null ? new LinkedHashMap<String, String>() : pTargetValues;
			this.fieldScores = pFieldScores == null ? new LinkedHashMap<String, Double>() : pFieldScores;
			this.verdict = pVerdict;
			this.origin = pOrigin == null ? SimulationPair.ORIGIN_ENGINE : pOrigin;
			this.sameKey = pSameKey;
			this.enginePath = pEnginePath == null ? new ArrayList<String>() : pEnginePath;
		}
	}

	/**
	 * 2026-09-22 diagnostic-visibility lot: {@code ebx.MatchPlanSimulationRunner}'s own aggregation, across ONE
	 * "Simulate" click, of every {@code ebx.MameMatchingApi.TreeEvaluation#diagnostic} worth surfacing - see
	 * that runner's own Javadoc for exactly which diagnostics get aggregated (an exception always does; a
	 * successful-but-unmatched score only does when the field it was for is not a full-text algorithm, which
	 * has its own, non-alarming, per-cell wording instead - {@link MatchSimulationBlock#renderFieldCell}).
	 * {@code null} on {@link SimulationResult} when nothing was worth surfacing (every score either matched or
	 * was legitimately absent for a full-text field) - never a zero-count instance.
	 */
	public static final class ScoreDiagnostics
	{
		/** How many pairs this happened for - can be larger than {@link #messages}.size(), which is capped. */
		public final int count;
		/** Up to 3 DISTINCT diagnostic strings, in first-seen order - task brief: "count + first distinct messages, capped at 3". Never empty when {@link #count} &gt; 0. */
		public final List<String> messages;

		public ScoreDiagnostics(final int pCount, final List<String> pMessages)
		{
			this.count = pCount;
			this.messages = pMessages == null ? new ArrayList<String>() : pMessages;
		}
	}

	/**
	 * What a "Simulate on N pivots" run produced - EVERY candidate the engine actually examined across every
	 * drawn pivot (not merely the handful eventually shown in a family table), so {@link MatchSimulationBlock}
	 * can compute a truthful numeric summary. Kept on the calling service instance and re-rendered, never
	 * persisted.
	 */
	public static final class SimulationResult
	{
		public final List<SimulationPair> pairs;
		/** How many pivots were actually drawn and submitted to the engine ({@code <= ebx.MatchPlanSimulationRunner#MAX_PIVOTS}) - fewer than the cap when the master table itself has fewer rows. */
		public final int pivotCount;
		/**
		 * simulation-preview-policy lot (2026-09-22): {@code true} when this run simulated on a dedicated,
		 * inactive {@code <Entity>__preview} policy ({@code ebx.MatchingPreviewPolicy}) rather than the real,
		 * {@code APPLIED} one - because the plan itself was not yet {@code APPLIED} at click time. {@link
		 * MatchSimulationBlock} uses this (and {@link #policyCode}) to say which policy was actually simulated.
		 */
		public final boolean onPreviewPolicy;
		/** The {@code processCode}/{@code matchingPolicies[].processCode} the add-on actually evaluated - the entity name itself when {@link #onPreviewPolicy} is false, {@code <Entity>__preview} otherwise. */
		public final String policyCode;
		/**
		 * self-heal-simulate-activation lot (2026-09-22): non-null only when {@code ebx.MatchingActivationGuard
		 * #ensureActivatedBeforeSimulate} actually had to repair this table's matching activation (the
		 * boot-race defect, {@code ebx.MatchingActivationGuard}'s own class Javadoc: the configuration was
		 * published and its metadataset existed, but the {@code MatchingTableTrigger} was never attached after
		 * the last boot) before this run could call the add-on at all - {@code null} on every ordinary run
		 * (matching was already {@code ENABLED}). {@link MatchPlanView#renderSimulationBlock} shows it in the
		 * panel header, next to the preview/applied-policy line, via the
		 * {@code review.match.simulate.activation.reactivated} bundle key.
		 */
		public final String activationNote;
		/** 2026-09-22 diagnostic-visibility lot - see {@link ScoreDiagnostics}' own Javadoc. Null (never a zero-count instance) when nothing was worth surfacing. */
		public final ScoreDiagnostics scoreDiagnostics;
		/**
		 * 2026-09-22 diagnostic-visibility lot: {@code false} only when {@code ebx.MatchPlanSimulationRunner}
		 * could not build a near-miss probe at all for this plan - no non-full-text exact field besides the
		 * plan's own identifying key (task brief item 3: "when the plan has no exact (non-full-text) tree field
		 * besides the key, the near-miss probe cannot be built"). {@code true} (the default, for every caller
		 * built before this lot) otherwise - including when the probe ran but simply found nothing, a DIFFERENT,
		 * ordinary "no pair of this kind" situation {@link MatchSimulationBlock} still words with real numbers.
		 */
		public final boolean nearMissApplicable;
		/**
		 * 2026-09-22 diagnostic-visibility lot: the plan's own identifying key field name (e.g. {@code nameKey})
		 * when {@code ebx.MatchPlanSimulationRunner} could resolve one - see {@code
		 * MatchPlanSimulationRunner#keyFieldName}. Null for every caller built before this lot, in which case
		 * {@link MatchSimulationBlock} keeps its older, generic "same exact key" wording (task brief item 3:
		 * "with a plan whose only exact notion is the derived key, say 'same nameKey' explicitly").
		 */
		public final String keyFieldName;
		/**
		 * Lot V2-B (2026-09-30, `docs/MATCHING.md` sec 47): one entry per relation tier (a decision node comparing
		 * fields of a child table, {@code "P3 (Adresse.street, Adresse.city)"}) this run did NOT simulate - never null,
		 * empty when every tier was. {@link #relationSkipReason} says why. Shown under the simulation ("not
		 * simulated, its pairs are reported as decided by the remaining tiers"): a simulation that dropped part of the plan is never silent.
		 */
		public final List<String> relationTiersNotSimulated;
		/** Lot V2-B: why {@link #relationTiersNotSimulated} were not simulated; null when that list is empty. */
		public final String relationSkipReason;
		/**
		 * Lot V2-B: non-null when the simulation FAILED (the localized one-line reason, already worded by the caller - the full detail stays
		 * in the failed {@code PipelineStep}). Such a result has no pairs; {@link MatchPlanView} shows the line under the Simulate button
		 * instead of "Not simulated yet". Build one with {@link #failed}.
		 */
		public final String failureMessage;
		/**
		 * Lot M16 (docs/MATCHING.md sec 68): the candidate coverage measured on the pivots - for each exact key of the plan, the records sharing its value with a pivot that the add-on never
		 * evaluated as candidates. Null when it could not be measured (no key, a failed read) - the panel then says nothing about it.
		 */
		public final com.on.ebx.factory.matching.CandidateCoverage.Result coverage;
		/** Lot M16: {@link #coverage} as numbered statements with their cause (English), the ones stored on the plan and given to the model in its next prompt; empty when {@link #coverage} is null. */
		public final List<String> coverageFacts;

		/** Lot M16: this result with the candidate coverage and its statements. */
		public SimulationResult withCoverage(final com.on.ebx.factory.matching.CandidateCoverage.Result pCoverage, final List<String> pFacts)
		{
			return new SimulationResult(this.pairs, this.pivotCount, this.onPreviewPolicy, this.policyCode, this.activationNote, this.scoreDiagnostics, this.nearMissApplicable, this.keyFieldName,
				this.relationTiersNotSimulated, this.relationSkipReason, this.failureMessage, pCoverage, pFacts);
		}

		/** Lot V2-B: the result stored when a Simulate click FAILED, so the reviewer sees why under the button instead of an unchanged "Not simulated yet". */
		public static SimulationResult failed(final String pMessage)
		{
			return new SimulationResult(new ArrayList<SimulationPair>(), 0, false, null, null, null, true, null, null, null,
				pMessage == null || pMessage.trim().isEmpty() ? "?" : pMessage);
		}

		/** Convenience for a caller with nothing to say about which policy ran (every test built before the simulation-preview-policy lot) - equivalent to "the real, applied policy". */
		public SimulationResult(final List<SimulationPair> pPairs, final int pPivotCount)
		{
			this(pPairs, pPivotCount, false, null);
		}

		/** Convenience for a caller with nothing to say about the self-heal note (every test built before the self-heal-simulate-activation lot) - equivalent to "matching was already enabled, nothing to repair". */
		public SimulationResult(final List<SimulationPair> pPairs, final int pPivotCount, final boolean pOnPreviewPolicy, final String pPolicyCode)
		{
			this(pPairs, pPivotCount, pOnPreviewPolicy, pPolicyCode, null);
		}

		/** Convenience for a caller with nothing to say about score diagnostics/near-miss-applicability/the key field name (every test built before the diagnostic-visibility lot). */
		public SimulationResult(final List<SimulationPair> pPairs, final int pPivotCount, final boolean pOnPreviewPolicy, final String pPolicyCode,
			final String pActivationNote)
		{
			this(pPairs, pPivotCount, pOnPreviewPolicy, pPolicyCode, pActivationNote, null, true, null);
		}

		/** See {@link #scoreDiagnostics}/{@link #nearMissApplicable}/{@link #keyFieldName} (diagnostic-visibility lot, 2026-09-22): nothing skipped, not failed. */
		public SimulationResult(final List<SimulationPair> pPairs, final int pPivotCount, final boolean pOnPreviewPolicy, final String pPolicyCode,
			final String pActivationNote, final ScoreDiagnostics pScoreDiagnostics, final boolean pNearMissApplicable, final String pKeyFieldName)
		{
			this(pPairs, pPivotCount, pOnPreviewPolicy, pPolicyCode, pActivationNote, pScoreDiagnostics, pNearMissApplicable, pKeyFieldName, null, null, null);
		}

		/** Full form - adds {@link #relationTiersNotSimulated}/{@link #relationSkipReason}/{@link #failureMessage} (lot V2-B, 2026-09-30). */
		public SimulationResult(final List<SimulationPair> pPairs, final int pPivotCount, final boolean pOnPreviewPolicy, final String pPolicyCode,
			final String pActivationNote, final ScoreDiagnostics pScoreDiagnostics, final boolean pNearMissApplicable, final String pKeyFieldName,
			final List<String> pRelationTiersNotSimulated, final String pRelationSkipReason, final String pFailureMessage)
		{
			this(pPairs, pPivotCount, pOnPreviewPolicy, pPolicyCode, pActivationNote, pScoreDiagnostics, pNearMissApplicable, pKeyFieldName, pRelationTiersNotSimulated, pRelationSkipReason,
				pFailureMessage, null, null);
		}

		/** Full form with the candidate coverage (lot M16). */
		public SimulationResult(final List<SimulationPair> pPairs, final int pPivotCount, final boolean pOnPreviewPolicy, final String pPolicyCode,
			final String pActivationNote, final ScoreDiagnostics pScoreDiagnostics, final boolean pNearMissApplicable, final String pKeyFieldName,
			final List<String> pRelationTiersNotSimulated, final String pRelationSkipReason, final String pFailureMessage,
			final com.on.ebx.factory.matching.CandidateCoverage.Result pCoverage, final List<String> pCoverageFacts)
		{
			this.coverage = pCoverage;
			this.coverageFacts = pCoverageFacts == null ? new ArrayList<String>() : pCoverageFacts;
			this.relationTiersNotSimulated = pRelationTiersNotSimulated == null ? new ArrayList<String>() : pRelationTiersNotSimulated;
			this.relationSkipReason = pRelationSkipReason;
			this.failureMessage = pFailureMessage;
			this.pairs = pPairs == null ? new ArrayList<SimulationPair>() : pPairs;
			this.pivotCount = pPivotCount;
			this.onPreviewPolicy = pOnPreviewPolicy;
			this.policyCode = pPolicyCode;
			this.activationNote = pActivationNote;
			this.scoreDiagnostics = pScoreDiagnostics;
			this.nearMissApplicable = pNearMissApplicable;
			this.keyFieldName = pKeyFieldName;
		}
	}

	public static final class Input
	{
		public final String entityName;
		/** {@code MatchPlan.requirements} - {@code MatchPlanRules}/{@code MatchPlanLlmPass}'s own shape (`docs/MATCHING.md` &sect;3). Never null/blank for a real plan; a blank value renders "no tier configured yet". */
		public final String requirementsJson;
		/** {@code MatchPlan.evidence} - {@code master.MatchPlanEvidence}'s own shape. Null/blank for a plan written before this field existed: block 2 then says so rather than guessing. */
		public final String evidenceJson;
		public final boolean autoMerge;
		/** True when this entity's plan is APPLIED on the add-on (Match and Merge configuration activated) - {@code MatchingConfigurator.simulate} requires this. */
		public final boolean canSimulate;
		/** Non-null only when {@code !canSimulate}: why the Simulate button is disabled. */
		public final String cannotSimulateReason;
		/** Null until a Simulate run has produced something to show. */
		public final SimulationResult simulation;
		/** How many pivots a click on "Simulate" asks for - {@code ebx.MatchPlanSimulationRunner#MAX_PIVOTS}, passed in rather than hardcoded here so the pure view never drifts from the EBX-bound cap. */
		public final int maxSimulationPairs;
		/** The row id posted back by the Simulate button ({@code data-mdmf-id}) - the plan's own {@code MatchPlan} primary key. */
		public final String matchPlanRowId;
		public final Locale locale;
		/**
		 * Survivorship lot (2026-09-22, `docs/MATCHING.md` &sect;15): everything section 4 "Survivance" needs
		 * beyond {@link #requirementsJson}/{@link #evidenceJson} - the wizard's trusted-source ranking/
		 * descriptions/record counts, the entity's source-key attribute(s), and its derived matching-key fields.
		 * Never null - a caller with nothing to give (every test/route built before this lot) gets an empty one
		 * ({@link MatchSurvivorshipBlock#emptyInput()}), which still renders the fixed sentences and the four
		 * technical rows, just with no source cards and no derived-key rows.
		 */
		public final MatchSurvivorshipBlock.Input survivorship;
		/**
		 * Automerge-toggle lot (2026-09-23): {@code MatchPlan.status}, passed only by the caller that wants the
		 * "Merge confirmed matches automatically" control shown at all - {@code review.ReviewPage
		 * .renderMatchExpandedRow}, the ONLY real caller that ever sets this (null for every other caller, the
		 * wizard's own {@code WizardPage#renderMatchPlanBody} included - Task 1's brief scopes the control to the
		 * review screen). Null means "render nothing for this control" (every constructor before this lot, and
		 * every unit test, defaults to null); non-null renders it, editable ({@link ReviewLine#PROPOSED}/{@link
		 * ReviewLine#ACCEPTED}) or read-only (any other status - {@link ReviewLine#APPLIED} shows the extra
		 * "needs a re-configure" hint, see {@link #renderAutoMergeControl}).
		 */
		public final String matchPlanStatus;

		/** Convenience for every caller built before the survivorship lot (2026-09-22) - equivalent to passing {@link MatchSurvivorshipBlock#emptyInput()}. */
		public Input(final String pEntityName, final String pRequirementsJson, final String pEvidenceJson, final boolean pAutoMerge, final boolean pCanSimulate,
			final String pCannotSimulateReason, final SimulationResult pSimulation, final int pMaxSimulationPairs, final String pMatchPlanRowId, final Locale pLocale)
		{
			this(pEntityName, pRequirementsJson, pEvidenceJson, pAutoMerge, pCanSimulate, pCannotSimulateReason, pSimulation, pMaxSimulationPairs, pMatchPlanRowId, pLocale,
				null);
		}

		/** See {@link #survivorship} (survivorship lot, 2026-09-22) - every caller before the automerge-toggle lot (2026-09-23), the wizard included, keeps using this: {@link #matchPlanStatus} defaults to null (control never shown). */
		public Input(final String pEntityName, final String pRequirementsJson, final String pEvidenceJson, final boolean pAutoMerge, final boolean pCanSimulate,
			final String pCannotSimulateReason, final SimulationResult pSimulation, final int pMaxSimulationPairs, final String pMatchPlanRowId, final Locale pLocale,
			final MatchSurvivorshipBlock.Input pSurvivorship)
		{
			this(pEntityName, pRequirementsJson, pEvidenceJson, pAutoMerge, pCanSimulate, pCannotSimulateReason, pSimulation, pMaxSimulationPairs, pMatchPlanRowId, pLocale,
				pSurvivorship, null);
		}

		/** Full form - see {@link #matchPlanStatus} (automerge-toggle lot, 2026-09-23). */
		public Input(final String pEntityName, final String pRequirementsJson, final String pEvidenceJson, final boolean pAutoMerge, final boolean pCanSimulate,
			final String pCannotSimulateReason, final SimulationResult pSimulation, final int pMaxSimulationPairs, final String pMatchPlanRowId, final Locale pLocale,
			final MatchSurvivorshipBlock.Input pSurvivorship, final String pMatchPlanStatus)
		{
			this.entityName = pEntityName;
			this.requirementsJson = pRequirementsJson;
			this.evidenceJson = pEvidenceJson;
			this.autoMerge = pAutoMerge;
			this.canSimulate = pCanSimulate;
			this.cannotSimulateReason = pCannotSimulateReason;
			this.simulation = pSimulation;
			this.maxSimulationPairs = pMaxSimulationPairs;
			this.matchPlanRowId = pMatchPlanRowId;
			this.locale = pLocale == null ? Locale.ENGLISH : pLocale;
			this.survivorship = pSurvivorship == null ? MatchSurvivorshipBlock.emptyInput() : pSurvivorship;
			this.matchPlanStatus = pMatchPlanStatus;
		}
	}

	// ------------------------------------------------------------------------------------------------ collapsed title

	/**
	 * {@code "N tier(s) - suspect only"} / {@code "N tier(s) - may merge automatically"} - replaces the
	 * truncated-JSON proposition {@code ebx.ProposalReviews.readMatchPlans} showed before this lot
	 * ({@code review.prop.match.requirements}). "May merge automatically" only when {@code pAutoMerge} AND
	 * at least one tier's own proposed outcome is {@code MATCH} - mirrors {@code
	 * MatchingPolicyTranslator.buildNode}'s exact condition for ever writing a real {@code MATCHED} leaf.
	 * Never throws on a malformed/blank {@code pRequirementsJson} - reads as zero tiers instead.
	 */
	public static String collapsedTitle(final String pRequirementsJson, final boolean pAutoMerge, final Locale pLocale)
	{
		final Locale locale = pLocale == null ? Locale.ENGLISH : pLocale;
		final JsonNode requirements = MatchPlanView.parseOrEmpty(pRequirementsJson);
		// binary-decision-tree-screen lot (2026-09-22): walks the real tree via ReviewDecisionTree - a plain
		// cascade's node count is exactly its old tier count, so this wording is unchanged for every plan this
		// codebase produced before the tree rework; a genuinely branching tree now counts every node instead of
		// answering an empty view.
		final ReviewDecisionTree.Plan plan = ReviewDecisionTree.readFrom(requirements);
		final int tierCount = plan.nodes.size();
		boolean anyMatchTier = false;
		for (final ReviewDecisionTree.Node node : plan.nodes)
		{
			if (ReviewDecisionTree.LEAF_MATCH.equals(node.onTrue) || ReviewDecisionTree.LEAF_MATCH.equals(node.onFalse))
			{
				anyMatchTier = true;
				break;
			}
		}
		final boolean mayMergeAutomatically = pAutoMerge && anyMatchTier;
		// wizard visual pass, step 6/7 round (2026-09-24): proper singular/plural (never a bare "(s)") - same
		// one/other bundle-key convention wizard.WizardPage#plural already uses elsewhere in this codebase.
		final String key = (mayMergeAutomatically ? "review.match.summary.mayMerge" : "review.match.summary.suspectOnly") + (tierCount == 1 ? ".one" : ".other");
		return ReviewMessages.get(locale, key, Integer.valueOf(tierCount));
	}

	// ------------------------------------------------------------------------------------------------------ render

	public static String render(final Input pInput)
	{
		return MatchPlanView.render(pInput, true, true);
	}

	/**
	 * Wizard-match-tree lot (2026-09-23): block 1 ALONE - the decision tree (with its node-detail panel), in
	 * its own {@code mdmf-match-body} grid. The project wizard's Matching step (step 6) shows this part
	 * permanently on every plan still awaiting a decision (the product owner: "Il faut afficher l'arbre ici pour
	 * pouvoir prendre une decision" - the card used to show {@code MatchPlan.requirements} as raw JSON) and
	 * keeps the three other blocks behind {@link #renderDetails}'s fold, so several entities stay readable on
	 * one page (the review screen's own lesson, {@code ReviewPage#renderMatchExpandedRow}). Same input, same
	 * markup and same script ({@link #treeScript}) as {@link #render}: never a second tree implementation.
	 */
	public static String renderTree(final Input pInput)
	{
		return MatchPlanView.render(pInput, true, false, false);
	}

	/** UX lot U1 (2026-10-13): {@link #renderTree(Input)} with the notes that only matter at volume (automatic merge off, candidate-search cost) folded under "Details" - see {@code MatchPlanTreeDiagram#build}. The wizard passes true under {@code wizard.MatchingVolume#INLINE_ROWS} rows. */
	public static String renderTree(final Input pInput, final boolean pFoldVolumeNotes)
	{
		return MatchPlanView.render(pInput, true, false, pFoldVolumeNotes);
	}

	/**
	 * Lot M8a (2026-10-04, {@code docs/MATCHING.md} &sect;51): the candidate-search notice a plan card shows under its text - a warning paragraph, or the empty string when
	 * there is nothing to warn about. (1) The plan is filtered (every path to a match starts with an exact key) but the add-on's JVM option is off on this server
	 * ({@code pJvmOptionEnabled} false): the filters would be ignored, the run compares by weighted search. (2) {@code pShowNotFiltered} and the plan is NOT filtered: the same
	 * sentence the tree diagram's band carries ({@link MatchPlanTreeDiagram#candidateFilterLines}) - a plan whose tree is not drawn (already decided) would otherwise lose it. Pure:
	 * the caller reads the JVM option ({@code matching.CandidateFilterCheck#jvmOptionEnabled}).
	 */
	public static String renderCandidateSearchNotice(final String pRequirementsJson, final boolean pShowNotFiltered, final boolean pJvmOptionEnabled, final Locale pLocale)
	{
		return MatchPlanView.renderCandidateSearchNotice(pRequirementsJson, pShowNotFiltered, pJvmOptionEnabled, pLocale, false);
	}

	/** UX lot U1 (2026-10-13): the same notice, wrapped in a "Details" disclosure when {@code pFold} (the table is small: the warning is about volume). */
	public static String renderCandidateSearchNotice(final String pRequirementsJson, final boolean pShowNotFiltered, final boolean pJvmOptionEnabled, final Locale pLocale,
		final boolean pFold)
	{
		final String notice = MatchPlanView.candidateSearchNotice(pRequirementsJson, pShowNotFiltered, pJvmOptionEnabled, pLocale);
		return notice.isEmpty() || !pFold ? notice : "<details class=\"mdmf-disclosure mdmf-candidate-search-fold\"><summary>"
			+ ReviewPage.esc(ReviewMessages.get(pLocale, "wizard.step6.volume.details")) + "</summary>" + notice + "</details>";
	}

	private static String candidateSearchNotice(final String pRequirementsJson, final boolean pShowNotFiltered, final boolean pJvmOptionEnabled, final Locale pLocale)
	{
		final JsonNode requirements = MatchPlanView.parseOrEmpty(pRequirementsJson);
		final CandidateFilterCheck.Result result = CandidateFilterCheck.check(requirements);
		final StringBuilder out = new StringBuilder();
		if (result.notFiltered() && pShowNotFiltered)
		{
			final String[] lines = MatchPlanTreeDiagram.candidateFilterLines(requirements, pLocale);
			out.append("<p class=\"mdmf-hint mdmf-hint-warn mdmf-candidate-search\">").append(ReviewPage.esc(lines[0])).append(' ').append(ReviewPage.esc(lines[1]));
			if (lines[2] != null)
			{
				out.append(' ').append(ReviewPage.esc(lines[2]));
			}
			out.append("</p>");
		}
		else if (result.filtered() && !pJvmOptionEnabled)
		{
			out.append("<p class=\"mdmf-hint mdmf-hint-warn mdmf-candidate-search\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.candidates.jvmOff"))).append("</p>");
		}
		return out.toString();
	}

	/** Wizard-match-tree lot (2026-09-23): blocks 2 to 4 (evidence, simulation, survivorship) WITHOUT the tree - the complement of {@link #renderTree}, see there. */
	public static String renderDetails(final Input pInput)
	{
		return MatchPlanView.render(pInput, false, true);
	}

	/**
	 * Wizard step 6 lot (2026-09-24): the compact 2-column "Simulation on N random records" + "Which value
	 * wins" strip the board ({@code docs/mockups/wizard-flow/MatchingPlan.dc.html}) shows directly under the
	 * tree, ALWAYS visible while a plan awaits a decision - never behind the "Show details" fold, which stays
	 * for the full evidence table/simulation families/survivorship table ({@link #renderDetails}). Every
	 * number comes from the SAME {@link MatchSimulationBlock#analyze} tally the full simulation panel uses
	 * (never a second computation) and the same survivorship data {@link #renderDetails} passes to {@link
	 * MatchSurvivorshipBlock} - this method only picks a compact subset, never invents a number. Empty
	 * (returns a single "not simulated yet" line) when {@link Input#simulation} is null.
	 */
	public static String renderSummaryStrip(final Input pInput)
	{
		final Locale locale = pInput.locale;
		final JsonNode requirements = MatchPlanView.parseOrEmpty(pInput.requirementsJson);
		final StringBuilder out = new StringBuilder();
		out.append("<div class=\"mdmf-match-summary-strip\">");
		out.append("<div><span class=\"mdmf-eyebrow\">");
		if (pInput.simulation == null)
		{
			out.append(ReviewPage.esc(ReviewMessages.get(locale, "wizard.step6.plan.strip.simulation.title"))).append("</span>");
			// live bug fix (2026-09-25, PersVol2 volume test): this branch only means "no simulation has run in
			// THIS render" - ProjectWizardUserService is rebuilt on every request (CLAUDE.md sec 9, "a transient
			// bean is rebuilt from scratch each round-trip"), so matchSimulationResults is empty on every fresh
			// page load/navigation, whatever the master table actually holds. review.match.simulate.none
			// ("No record found to sample") wrongly implied the sampling itself came up empty - on PersVol2
			// (master table /root/Personne, 2996 records) it read as if the lookup was broken. Use a distinct,
			// accurate wording for "not simulated yet" instead of reusing the "found nothing" one.
			out.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(locale, "review.match.simulate.notRunYet"))).append("</p></div>");
		}
		else if (pInput.simulation.failureMessage != null)
		{
			// lot V2-B (2026-09-30): a click that FAILED says so here, under the Simulate button, instead of leaving "Not simulated yet"
			out.append(ReviewPage.esc(ReviewMessages.get(locale, "wizard.step6.plan.strip.simulation.title"))).append("</span>");
			out.append("<p class=\"mdmf-hint mdmf-hint-warn\">").append(ReviewPage.esc(MatchPlanView.truncate(pInput.simulation.failureMessage, MatchPlanView.MAX_FAILURE_LINE)))
				.append("</p></div>");
		}
		else
		{
			out.append(ReviewPage.esc(ReviewMessages.get(locale, "wizard.step6.plan.strip.simulation.titleN", Integer.valueOf(pInput.simulation.pivotCount))))
				.append("</span>");
			final Set<String> exactFields = MatchSimulationBlock.exactFieldNames(requirements);
			final JsonNode averageTier = MatchSimulationBlock.firstAverageTier(requirements);
			final MatchSimulationBlock.Analysis analysis = MatchSimulationBlock.analyze(requirements, exactFields, averageTier, pInput.simulation.pairs,
				pInput.simulation.pivotCount);
			out.append("<div class=\"mdmf-strip-chips\">");
			out.append("<span class=\"mdmf-chip mdmf-chip-real\">").append(ReviewPage.esc(ReviewMessages.get(locale,
				"wizard.step6.plan.strip.sure" + (analysis.matchCount == 1 ? ".one" : ".other"), Integer.valueOf(analysis.matchCount)))).append("</span>");
			out.append("<span class=\"mdmf-chip mdmf-chip-warn\">")
				.append(ReviewPage.esc(ReviewMessages.get(locale, "wizard.step6.plan.strip.decide", Integer.valueOf(analysis.suspectCount)))).append("</span>");
			out.append("<span class=\"mdmf-chip mdmf-chip-target\">")
				.append(ReviewPage.esc(ReviewMessages.get(locale, "wizard.step6.plan.strip.alone", Integer.valueOf(analysis.notMatchCount)))).append("</span>");
			out.append("</div>");
			final MatchPlanView.SimulationPair example = MatchPlanView.firstSuspect(pInput.simulation.pairs);
			if (example != null)
			{
				out.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(locale, "wizard.step6.plan.strip.example", example.pivotLabel,
					example.pivotSystem, example.targetLabel, example.targetSystem))).append("</p>");
			}
			MatchPlanView.appendRelationSkipNote(out, pInput.simulation, locale);
			out.append("</div>");
		}
		out.append("<div><span class=\"mdmf-eyebrow\">").append(ReviewPage.esc(ReviewMessages.get(locale, "wizard.step6.plan.strip.survivorship.title")))
			.append("</span><p class=\"mdmf-hint\">")
			.append(ReviewPage.esc(MatchSurvivorshipBlock.survivorSummary(requirements, pInput.survivorship, locale))).append("</p></div>");
		out.append("</div>");
		return out.toString();
	}

	/** First {@code SUSPECTED}/{@code SUSPECT} pair, in the list's own (already-sorted-for-display) order - null when none. Used by {@link #renderSummaryStrip} for its one worked example, mirroring the board's own "Example to decide" line. */
	private static MatchPlanView.SimulationPair firstSuspect(final List<SimulationPair> pPairs)
	{
		for (final SimulationPair pair : pPairs)
		{
			final String upper = pair.verdict == null ? "" : pair.verdict.toUpperCase(Locale.ENGLISH);
			if ("SUSPECTED".equals(upper) || "SUSPECT".equals(upper))
			{
				return pair;
			}
		}
		return null;
	}

	private static String render(final Input pInput, final boolean pTree, final boolean pDetails)
	{
		return MatchPlanView.render(pInput, pTree, pDetails, false);
	}

	private static String render(final Input pInput, final boolean pTree, final boolean pDetails, final boolean pFoldVolumeNotes)
	{
		final Locale locale = pInput.locale;
		final JsonNode requirements = MatchPlanView.parseOrEmpty(pInput.requirementsJson);
		final List<MatchPlanFieldEvidence> evidence = MatchPlanView.parseEvidence(pInput.evidenceJson);
		final Map<String, MatchPlanFieldEvidence> evidenceByField = new LinkedHashMap<String, MatchPlanFieldEvidence>();
		for (final MatchPlanFieldEvidence field : evidence)
		{
			evidenceByField.put(field.field, field);
		}

		final StringBuilder out = new StringBuilder();
		out.append("<div class=\"mdmf-match-body\">");
		if (pTree)
		{
			MatchPlanView.renderAutoMergeControl(out, pInput, locale);
			MatchPlanView.renderTreeBlock(out, requirements, evidenceByField, pInput.autoMerge, pInput.entityName, locale, pFoldVolumeNotes);
		}
		if (pDetails)
		{
			// evidence.isEmpty() is a distinct, prior state ("this plan's evidence was never recorded at all" -
			// review.match.evidence.none) from "evidence exists but a node field is missing from it" - the union only
			// ever applies to the latter, so a plan with genuinely no evidence still shows the honest "not recorded"
			// message instead of a table of bare stubs.
			MatchPlanView.renderEvidenceBlock(out, requirements, evidence.isEmpty() ? evidence : MatchPlanView.unionWithNodeFields(evidence, requirements), locale);
			MatchPlanView.renderSimulationBlock(out, requirements, pInput, locale);
			MatchSurvivorshipBlock.render(out, requirements, evidence, pInput.survivorship, locale);
		}
		out.append("</div>");
		return out.toString();
	}

	// -------------------------------------------------------------------------------------------- block 1: the tree

	private static void renderTreeBlock(final StringBuilder pOut, final JsonNode pRequirements, final Map<String, MatchPlanFieldEvidence> pEvidenceByField,
		final boolean pAutoMerge, final String pEntityName, final Locale pLocale, final boolean pFoldVolumeNotes)
	{
		// binary-decision-tree-screen lot (2026-09-22): mdmf-match-block-full - the general tree (plus its own
		// node-detail panel to the right, docs/mockups/review-match-plan-binary-tree.html) needs the full row
		// width the pre-lot single-column cascade never did, not the ~55% left column it used to share with
		// block 2 (evidence, still narrower and now rendered below it).
		pOut.append("<div class=\"mdmf-match-block mdmf-match-block-full\"><h4>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.title")))
			.append("</h4><p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.tree.lead"))).append("</p>");
		pOut.append(MatchPlanView.renderKeyFindings(pRequirements, pLocale));
		pOut.append(MatchPlanTreeDiagram.build(pRequirements, pEvidenceByField, pAutoMerge, pEntityName, pLocale, pFoldVolumeNotes));
		pOut.append("</div>");
	}

	/**
	 * Lot M3 (2026-10-17, {@code docs/MATCHING.md} &sect;56): what the measured data says about the keys of this plan ({@link KeyFindings}) - a path that is safe to merge
	 * automatically, a source system the candidate filter leaves out of reach. Computed from the stored plan ({@code requirements.keyFacts}), so a plan proposed before the lot
	 * has none; the empty string when there is nothing to say. Facts only: nothing here changes the plan.
	 */
	public static String renderKeyFindings(final JsonNode pRequirements, final Locale pLocale)
	{
		final List<KeyFindings.Finding> findings = KeyFindings.check(pRequirements);
		if (findings.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder("<div class=\"mdmf-key-findings\"><span class=\"mdmf-eyebrow\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.findings.title"))).append("</span>");
		for (final KeyFindings.Finding finding : findings)
		{
			if (KeyFindings.KIND_SAFE_AUTOMATIC_PATH.equals(finding.kind))
			{
				out.append("<p class=\"mdmf-hint mdmf-key-finding mdmf-key-finding-safe\">")
					.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.findings.safe", finding.path, Long.valueOf(finding.pairs), finding.key, Long.valueOf(finding.agree),
						Long.valueOf(finding.agree + finding.conflicts), finding.partner, Long.valueOf(finding.conflicts))))
					.append(' ')
					.append(ReviewPage.esc(finding.partnerInPath ? ReviewMessages.get(pLocale, "review.match.findings.safe.inPath")
						: ReviewMessages.get(pLocale, "review.match.findings.safe.add", finding.key, finding.partner)))
					.append("</p>");
			}
			else
			{
				out.append("<p class=\"mdmf-hint mdmf-hint-warn mdmf-key-finding mdmf-key-finding-reach\">")
					.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.findings.unreachable", finding.system, Long.valueOf(finding.unreached), Long.valueOf(finding.pairs),
						String.join(", ", finding.filterKeys))));
				if (!finding.alternatives.isEmpty())
				{
					final StringBuilder alternatives = new StringBuilder();
					for (int i = 0; i < finding.alternatives.size(); i++)
					{
						alternatives.append(i == 0 ? "" : ", ").append(finding.alternatives.get(i)).append(" (").append(finding.alternativeShares.get(i)).append("%)");
					}
					out.append(' ').append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.findings.unreachable.alt", alternatives.toString())));
				}
				out.append("</p>");
			}
		}
		return out.append("</div>").toString();
	}

	// --------------------------------------------------------------------------------- automerge-toggle control

	/**
	 * Automerge-toggle lot (2026-09-23, task 1): "Merge confirmed matches automatically" - the ONLY way, before
	 * this lot, to set {@code MatchPlan.autoMerge} was to edit the record directly (`docs/MATCHING.md` &sect;3.4's
	 * own Javadoc quote: "a human decision", never set by the LLM/rule passes - see {@code
	 * ebx.MatchPlanLlmSuggester}/{@code ebx.MatchPlanSuggester}). Renders nothing when {@link
	 * Input#matchPlanStatus} is null (every caller except {@code review.ReviewPage.renderMatchExpandedRow} - see
	 * that field's own Javadoc). Editable ({@link ReviewLine#PROPOSED}/{@link ReviewLine#ACCEPTED}): a real
	 * checkbox, posted the SAME hidden-submit way as every other control on this screen (CLAUDE.md &sect;9) - see
	 * {@code ebx.ui.ProposalReviewUserService.pageJs}'s own dedicated {@code .mdmf-auto-merge-toggle} change
	 * listener (a change event, not a click - so it does not go through the generic {@code [data-mdmf-action]}
	 * click handler, which has no notion of a control's own current value). {@link ReviewLine#APPLIED} (this
	 * plan is already configured on the add-on - {@code ebx.MatchingConfigurator}): read-only chip plus a hint
	 * that changing it needs a re-configure - task brief: "do NOT try to re-apply the policy from this control".
	 * Any other status (REJECTED/SUPERSEDED): read-only chip, no re-configure hint (nothing is configured for
	 * either). The one-sentence consequence explainer is shown in every case.
	 */
	private static void renderAutoMergeControl(final StringBuilder pOut, final Input pInput, final Locale pLocale)
	{
		if (pInput.matchPlanStatus == null)
		{
			return;
		}
		final boolean editable = ReviewLine.PROPOSED.equals(pInput.matchPlanStatus) || ReviewLine.ACCEPTED.equals(pInput.matchPlanStatus);
		pOut.append("<div class=\"mdmf-match-block mdmf-match-block-full mdmf-auto-merge\">");
		if (editable)
		{
			pOut.append("<label class=\"mdmf-hint mdmf-auto-merge-label\"><input type=\"checkbox\" class=\"mdmf-auto-merge-toggle\" data-mdmf-id=\"")
				.append(ReviewPage.esc(pInput.matchPlanRowId)).append("\"");
			if (pInput.autoMerge)
			{
				pOut.append(" checked=\"checked\"");
			}
			pOut.append("/> ").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.autoMerge.toggle"))).append("</label>");
		}
		else
		{
			pOut.append("<span class=\"mdmf-chip ").append(pInput.autoMerge ? "mdmf-chip-real" : "mdmf-chip-muted").append("\">")
				.append(ReviewPage.esc(ReviewMessages.get(pLocale, pInput.autoMerge ? "review.match.autoMerge.on" : "review.match.autoMerge.off"))).append("</span>");
			if (ReviewLine.APPLIED.equals(pInput.matchPlanStatus))
			{
				pOut.append(" <span class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.autoMerge.appliedHint"))).append("</span>");
			}
		}
		pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.autoMerge.consequence"))).append("</p>");
		pOut.append("</div>");
	}

	// ---------------------------------------------------------------------------------------- block 2: the evidence

	private static void renderEvidenceBlock(final StringBuilder pOut, final JsonNode pRequirements, final List<MatchPlanFieldEvidence> pEvidence, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-match-block\"><h4>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.evidence.title")))
			.append("</h4><p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.evidence.lead"))).append("</p>");
		if (pEvidence.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.evidence.none"))).append("</p></div>");
			return;
		}
		// binary-decision-tree-screen lot (2026-09-22): the Role column now names every NODE a field is used in
		// ("N1 all fields match", "N4 average, weight 10") rather than the coarser ANCHOR_FUZZY/SUPPORTING_EXACT/
		// CLUSTERING tags alone - a field used in several nodes lists every one, joined; a field this tree never
		// actually compares (e.g. a REJECTED field) keeps the legacy, coarser wording (there is no node to name).
		final ReviewDecisionTree.Plan plan = ReviewDecisionTree.readFrom(pRequirements);
		pOut.append("<table class=\"mdmf-table\"><tr><th>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.evidence.col.field"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.evidence.col.role"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.evidence.col.shared"))).append("</th><th>")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.evidence.col.filled"))).append("</th><th></th></tr>");
		for (final MatchPlanFieldEvidence field : pEvidence)
		{
			pOut.append("<tr><td class=\"k\">").append(ReviewPage.esc(field.field)).append("</td><td>")
				.append(ReviewPage.esc(MatchPlanView.nodeRoleWords(field.field, plan, field.role, pLocale))).append("</td><td>");
			MatchPlanView.renderSharedCell(pOut, field);
			pOut.append("</td><td>").append(ReviewPage.esc(MatchPlanView.filledBySystemWords(field.filledBySystem))).append("</td><td>");
			MatchPlanView.renderVerdictChip(pOut, field, pLocale);
			pOut.append("</td></tr>");
		}
		pOut.append("</table>");
		final JsonNode mergePolicy = pRequirements.path("mergePolicy");
		final String recordSelection = mergePolicy.path("recordSelection").asText("");
		final String defaultFieldMerge = mergePolicy.path("defaultFieldMerge").asText("");
		if (!recordSelection.isEmpty() || !defaultFieldMerge.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.evidence.mergeInfo",
				recordSelection.isEmpty() ? "?" : recordSelection, defaultFieldMerge.isEmpty() ? "?" : defaultFieldMerge))).append("</p>");
		}
		pOut.append("</div>");
	}

	private static void renderSharedCell(final StringBuilder pOut, final MatchPlanFieldEvidence pField)
	{
		if (pField.sharedAcrossSystems == null)
		{
			pOut.append(ReviewPage.esc("-"));
			return;
		}
		final int shared = pField.sharedAcrossSystems.intValue();
		final int distinct = pField.distinct == null ? 0 : pField.distinct.intValue();
		final int percent = distinct > 0 ? Math.max(0, Math.min(100, (int) Math.round(shared * 100.0 / distinct))) : shared > 0 ? 100 : 0;
		pOut.append("<span class=\"mdmf-mini-bar\"><i style=\"width:").append(percent).append("%\"></i></span>").append(shared);
		if (pField.distinct != null)
		{
			pOut.append(" / ").append(distinct);
		}
	}

	static String roleWords(final String pRole, final Locale pLocale)
	{
		final Set<String> tags = new LinkedHashSet<String>();
		for (final String tag : pRole.split(","))
		{
			tags.add(tag.trim());
		}
		final List<String> words = new ArrayList<String>();
		if (tags.contains(MatchPlanFieldEvidence.ROLE_ANCHOR_FUZZY))
		{
			words.add(ReviewMessages.get(pLocale, "review.match.role.anchorFuzzy"));
		}
		if (tags.contains(MatchPlanFieldEvidence.ROLE_SUPPORTING_EXACT))
		{
			words.add(ReviewMessages.get(pLocale, "review.match.role.supportingExact"));
		}
		if (tags.contains(MatchPlanFieldEvidence.ROLE_CLUSTERING))
		{
			words.add(ReviewMessages.get(pLocale, "review.match.role.clustering"));
		}
		if (tags.contains(MatchPlanFieldEvidence.ROLE_REJECTED))
		{
			words.add(ReviewMessages.get(pLocale, "review.match.role.rejected"));
		}
		if (words.isEmpty())
		{
			return pRole;
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < words.size(); i++)
		{
			if (i > 0)
			{
				out.append(" - ");
			}
			out.append(words.get(i));
		}
		return out.toString();
	}

	/**
	 * "{@code N1 all fields match}" - every node of {@code pPlan} that actually compares {@code pField},
	 * joined when there is more than one (binary-decision-tree-screen lot, 2026-09-22, task brief item 2: "the
	 * fields come from ALL nodes (union), with the role wording per node; a field used in several nodes lists
	 * them all"). Falls back to the coarser {@link #roleWords} wording when no node of this tree mentions the
	 * field at all - a {@link MatchPlanFieldEvidence#ROLE_REJECTED} field, or one from a plan written before
	 * evidence tracked node membership.
	 */
	static String nodeRoleWords(final String pField, final ReviewDecisionTree.Plan pPlan, final String pLegacyRole, final Locale pLocale)
	{
		final List<String> mentions = new ArrayList<String>();
		for (final ReviewDecisionTree.Node node : pPlan.nodes)
		{
			for (final ReviewDecisionTree.Field field : node.fields)
			{
				if (pField != null && pField.equals(field.field))
				{
					mentions.add(MatchPlanView.nodeMentionWords(node, field, pLocale));
				}
			}
		}
		if (mentions.isEmpty())
		{
			return MatchPlanView.roleWords(pLegacyRole, pLocale);
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < mentions.size(); i++)
		{
			if (i > 0)
			{
				out.append(" · ");
			}
			out.append(mentions.get(i));
		}
		return out.toString();
	}

	private static String nodeMentionWords(final ReviewDecisionTree.Node pNode, final ReviewDecisionTree.Field pField, final Locale pLocale)
	{
		if (ReviewDecisionTree.FUNCTION_AVERAGE.equals(pNode.function))
		{
			return ReviewMessages.get(pLocale, "review.match.evidence.node.average", pNode.id, pField.weight == null ? Integer.valueOf(1) : pField.weight);
		}
		if (ReviewDecisionTree.FUNCTION_AT_LEAST_N.equals(pNode.function))
		{
			return ReviewMessages.get(pLocale, "review.match.evidence.node.atLeastN", pNode.id,
				pNode.minimumMatches == null ? Integer.valueOf(1) : pNode.minimumMatches, Integer.valueOf(pNode.fields.size()));
		}
		if (ReviewDecisionTree.FUNCTION_PREDICATE.equals(pNode.function))
		{
			return ReviewMessages.get(pLocale, "review.match.evidence.node.predicate", pNode.id);
		}
		return ReviewMessages.get(pLocale, "review.match.evidence.node.allFields", pNode.id);
	}

	/**
	 * The stored evidence list, plus one stub row (empty role, {@link MatchPlanFieldEvidence#VERDICT_NOT_MEASURED})
	 * for any field a node of the tree compares but the evidence document never recorded (a plan re-saved before
	 * evidence tracking existed, or a hand-edited plan) - task brief item 2, "the fields come from ALL nodes
	 * (union)": every field any node touches must appear in this table, evidence or not.
	 */
	static List<MatchPlanFieldEvidence> unionWithNodeFields(final List<MatchPlanFieldEvidence> pEvidence, final JsonNode pRequirements)
	{
		final List<MatchPlanFieldEvidence> out = new ArrayList<MatchPlanFieldEvidence>(pEvidence);
		final Set<String> present = new LinkedHashSet<String>();
		for (final MatchPlanFieldEvidence field : pEvidence)
		{
			present.add(field.field);
		}
		for (final ReviewDecisionTree.Node node : ReviewDecisionTree.readFrom(pRequirements).nodes)
		{
			for (final ReviewDecisionTree.Field field : node.fields)
			{
				if (field.field != null && present.add(field.field))
				{
					out.add(new MatchPlanFieldEvidence(field.field, "", null, null, null, MatchPlanFieldEvidence.VERDICT_NOT_MEASURED, ""));
				}
			}
		}
		return out;
	}

	private static String filledBySystemWords(final Map<String, Long> pFilledBySystem)
	{
		if (pFilledBySystem == null || pFilledBySystem.isEmpty())
		{
			return "-";
		}
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, Long> entry : pFilledBySystem.entrySet())
		{
			if (out.length() > 0)
			{
				out.append(" · ");
			}
			out.append(entry.getValue()).append(' ').append(entry.getKey());
		}
		return out.toString();
	}

	private static void renderVerdictChip(final StringBuilder pOut, final MatchPlanFieldEvidence pField, final Locale pLocale)
	{
		final String chipClass = MatchPlanFieldEvidence.VERDICT_OK.equals(pField.verdict) ? "mdmf-chip-real"
			: MatchPlanFieldEvidence.VERDICT_REFUSED.equals(pField.verdict) ? "mdmf-chip-bad" : "mdmf-chip-target";
		final String key = MatchPlanFieldEvidence.VERDICT_OK.equals(pField.verdict) ? "review.match.verdict.ok"
			: MatchPlanFieldEvidence.VERDICT_REFUSED.equals(pField.verdict) ? "review.match.verdict.refused" : "review.match.verdict.notMeasured";
		pOut.append("<span class=\"mdmf-chip ").append(chipClass).append("\"");
		if (pField.note != null && !pField.note.isEmpty())
		{
			pOut.append(" title=\"").append(ReviewPage.esc(pField.note)).append("\"");
		}
		pOut.append(">").append(ReviewPage.esc(ReviewMessages.get(pLocale, key))).append("</span>");
	}

	// ------------------------------------------------------------------------------------------ block 3: simulation

	private static void renderSimulationBlock(final StringBuilder pOut, final JsonNode pRequirements, final Input pInput, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-match-block mdmf-match-block-full\"><h4>").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.title")));
		pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"SIMULATE\" data-mdmf-nature=\"")
			.append(ReviewLine.MATCH).append("\" data-mdmf-id=\"").append(ReviewPage.esc(pInput.matchPlanRowId)).append("\"");
		if (!pInput.canSimulate)
		{
			pOut.append(" disabled=\"disabled\"");
		}
		pOut.append(">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.button", Integer.valueOf(pInput.maxSimulationPairs))))
			.append("</button></h4>");
		if (!pInput.canSimulate)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(
				pInput.cannotSimulateReason == null ? ReviewMessages.get(pLocale, "review.match.simulate.disabled.reason") : pInput.cannotSimulateReason))
				.append("</p></div>");
			return;
		}
		pOut.append("<p class=\"mdmf-hint\">")
			.append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.lead", Integer.valueOf(pInput.maxSimulationPairs)))).append("</p>");
		if (pInput.simulation == null)
		{
			pOut.append("</div>");
			return;
		}
		if (pInput.simulation.failureMessage != null)
		{
			// lot V2-B (2026-09-30): the click failed - one line under the button, nothing else to analyse
			pOut.append("<p class=\"mdmf-hint mdmf-hint-warn\">").append(ReviewPage.esc(MatchPlanView.truncate(pInput.simulation.failureMessage, MatchPlanView.MAX_FAILURE_LINE)))
				.append("</p></div>");
			return;
		}
		// simulation-preview-policy lot (2026-09-22): says WHICH policy the numbers below actually came from - the
		// real, APPLIED one, or a dedicated preview policy written just for this click because the plan was not
		// APPLIED yet (ebx.MatchingPreviewPolicy) - so a reviewer never mistakes a preview run for the plan
		// already being live on the add-on.
		pOut.append("<p class=\"mdmf-hint\">").append(ReviewPage.esc(pInput.simulation.onPreviewPolicy
			? ReviewMessages.get(pLocale, "review.match.simulate.policy.preview", pInput.simulation.policyCode)
			: ReviewMessages.get(pLocale, "review.match.simulate.policy.applied"))).append("</p>");
		// self-heal-simulate-activation lot (2026-09-22): says so when this click had to repair the table's own
		// matching activation first (ebx.MatchingActivationGuard) - a reviewer should know the numbers below did
		// not come for free, and that the underlying boot-race defect may still need a look if it recurs.
		if (pInput.simulation.activationNote != null)
		{
			pOut.append("<p class=\"mdmf-hint mdmf-hint-warn\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.activation.reactivated")))
				.append("</p>");
		}
		// diagnostic-visibility lot (2026-09-22): "score not exposed by the engine" used to be the ONLY trace of
		// a failed/degraded evaluatePair call anywhere - no line in kernel.log, nothing here either. When
		// ebx.MatchPlanSimulationRunner aggregated at least one real problem across this click, say so once, here,
		// muted - never per row (MatchSimulationBlock#renderFieldCell keeps the per-row reason short, in a title).
		if (pInput.simulation.scoreDiagnostics != null)
		{
			pOut.append("<p class=\"mdmf-hint mdmf-hint-muted\">").append(ReviewPage.esc(ReviewMessages.get(pLocale, "review.match.simulate.scoreDiagnostics",
				Integer.valueOf(pInput.simulation.scoreDiagnostics.count), MatchPlanView.join(pInput.simulation.scoreDiagnostics.messages, " | ")))).append("</p>");
		}
		MatchPlanView.appendRelationSkipNote(pOut, pInput.simulation, pLocale);
		MatchSimulationBlock.render(pOut, pRequirements, pInput.simulation, pLocale);
		pOut.append("</div>");
	}

	/** Longest failure line shown under the Simulate button - the full message stays in the failed PipelineStep. */
	static final int MAX_FAILURE_LINE = 400;

	static String truncate(final String pText, final int pMax)
	{
		return pText.length() <= pMax ? pText : pText.substring(0, pMax) + "...";
	}

	/**
	 * Lot V2-B (2026-09-30): "tier P3 compares Adresse fields: not simulated, its pairs are reported as decided by the remaining tiers" - shown
	 * whenever the run left relation tiers out ({@link SimulationResult#relationTiersNotSimulated}), with the reason. Nothing when every tier was simulated.
	 */
	static void appendRelationSkipNote(final StringBuilder pOut, final SimulationResult pSimulation, final Locale pLocale)
	{
		if (pSimulation.relationTiersNotSimulated.isEmpty())
		{
			return;
		}
		pOut.append("<p class=\"mdmf-hint mdmf-hint-warn\">").append(ReviewPage.esc(MatchPlanView.truncate(ReviewMessages.get(pLocale, "review.match.simulate.relationTiersSkipped",
			MatchPlanView.join(pSimulation.relationTiersNotSimulated, " ; "), pSimulation.relationSkipReason == null ? "" : pSimulation.relationSkipReason), 600)))
			.append("</p>");
	}

	/**
	 * Field names in the order they first appear across ALL of the plan's nodes (union - binary-decision-tree-
	 * screen lot, 2026-09-22: a genuinely branching tree can compare different fields on different branches, so
	 * the simulation table's own columns, and every other reader of this method, must show every field ANY
	 * branch touches, not only the ones a single root-to-leaf path happens to pass through) - the simulation
	 * table's own columns; also used by {@link MatchSimulationBlock}.
	 */
	static List<String> comparedFieldNames(final JsonNode pRequirements)
	{
		final LinkedHashSet<String> out = new LinkedHashSet<String>();
		for (final ReviewDecisionTree.Node node : ReviewDecisionTree.readFrom(pRequirements).nodes)
		{
			for (final ReviewDecisionTree.Field field : node.fields)
			{
				if (field.field != null && !field.field.isEmpty())
				{
					out.add(field.field);
				}
			}
		}
		return new ArrayList<String>(out);
	}

	/** " | "-joins {@code pValues} - used only by {@link #renderSimulationBlock}'s own diagnostics line; {@link MatchSimulationBlock} has its own private copy for its different callers. */
	private static String join(final List<String> pValues, final String pSeparator)
	{
		final StringBuilder out = new StringBuilder();
		for (final String value : pValues)
		{
			if (out.length() > 0)
			{
				out.append(pSeparator);
			}
			out.append(value);
		}
		return out.toString();
	}

	// -------------------------------------------------------------------------------------------------------- json

	private static JsonNode parseOrEmpty(final String pJson)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			return MatchPlanView.MAPPER.createObjectNode();
		}
		try
		{
			return MatchPlanView.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			return MatchPlanView.MAPPER.createObjectNode();
		}
	}

	/** {@code master.MatchPlanEvidence.toJson}'s own shape, read back independently (see class Javadoc - JSON is the contract, not a shared Java type). Tolerant: a malformed/blank document reads as no evidence at all, never throws. */
	static List<MatchPlanFieldEvidence> parseEvidence(final String pJson)
	{
		final List<MatchPlanFieldEvidence> out = new ArrayList<MatchPlanFieldEvidence>();
		if (pJson == null || pJson.trim().isEmpty())
		{
			return out;
		}
		final JsonNode array;
		try
		{
			array = MatchPlanView.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			return out;
		}
		if (!array.isArray())
		{
			return out;
		}
		for (final JsonNode node : array)
		{
			final String field = node.path("field").asText(null);
			if (field == null)
			{
				continue;
			}
			final String role = node.path("role").asText(MatchPlanFieldEvidence.ROLE_REJECTED);
			final Integer shared = node.hasNonNull("sharedAcrossSystems") ? Integer.valueOf(node.path("sharedAcrossSystems").asInt()) : null;
			final Integer distinct = node.hasNonNull("distinct") ? Integer.valueOf(node.path("distinct").asInt()) : null;
			final Map<String, Long> filled = new LinkedHashMap<String, Long>();
			final JsonNode filledNode = node.path("filledBySystem");
			final java.util.Iterator<String> systemNames = filledNode.fieldNames();
			while (systemNames.hasNext())
			{
				final String system = systemNames.next();
				filled.put(system, Long.valueOf(filledNode.path(system).asLong()));
			}
			final String verdict = node.path("verdict").asText(MatchPlanFieldEvidence.VERDICT_NOT_MEASURED);
			final String note = node.path("note").asText("");
			final String confidence = node.hasNonNull("confidence") ? node.path("confidence").asText(null) : null;
			// lot 1, 2026-09-23: absent for a field with no validity rule / not measured, distinct from present-
			// but-empty - mirrors master.MatchPlanEvidence.toJson's own "omitted, never {}, when null" convention.
			Map<String, MatchPlanFieldEvidence.Validity> validBySystem = null;
			if (node.has("validBySystem"))
			{
				validBySystem = new LinkedHashMap<String, MatchPlanFieldEvidence.Validity>();
				final JsonNode validNode = node.path("validBySystem");
				final java.util.Iterator<String> validSystemNames = validNode.fieldNames();
				while (validSystemNames.hasNext())
				{
					final String system = validSystemNames.next();
					final JsonNode systemNode = validNode.path(system);
					validBySystem.put(system, new MatchPlanFieldEvidence.Validity(systemNode.path("checked").asLong(0L), systemNode.path("valid").asLong(0L)));
				}
			}
			out.add(new MatchPlanFieldEvidence(field, role, shared, distinct, filled.isEmpty() ? null : filled, verdict, note, confidence, validBySystem));
		}
		return out;
	}
}
