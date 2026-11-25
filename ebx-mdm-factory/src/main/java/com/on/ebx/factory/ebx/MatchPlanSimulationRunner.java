package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.master.CandidateScoreShare;
import com.on.ebx.factory.master.DecisionTreePlan;
import com.on.ebx.factory.master.MatchingPolicyTranslator;
import com.on.ebx.factory.master.SimulationFeedback;
import com.on.ebx.factory.matching.KeyFacts;
import com.on.ebx.factory.matching.CandidateCoverage;
import com.on.ebx.factory.matching.CandidateFilterCheck;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.review.MatchPlanView;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * "Simulate on N pivots" (review-match-plan-simulation lot, 2026-09-22 - REWORKS the earlier "10 shared-key
 * pairs" version entirely, product-owner rejection: every one of those pairs shared the clustering key by
 * construction, so every verdict was Suspect and proved nothing). The validated mockup is {@code
 * docs/mockups/review-match-plan-simulation.html} - read it before touching this file, and read {@code
 * review.MatchSimulationBlock}'s own class Javadoc for exactly what is real vs. this class' own best-effort
 * derivation once the numbers reach the review screen.
 * <p>
 * <b>2026-09-22, review-match-plan-simulation-probes lot - the random-pivot draw alone proved empty in
 * practice.</b> the product owner's own live run ("Simulate on 20 pivots"): 20 pivots, 14 candidates examined, all 14
 * SUSPECTED - families "caught without an exact key", "same key but refused" and "borderline" all empty,
 * because {@code MatchingOperations.simulateMatch} only ever returns the pairs the engine's own
 * pre-clustering retains, and on this demo data (ERP/CRM copies differing only by case/accents) that is
 * essentially every candidate it offers. "An empty panel proves nothing" (the product owner's verdict) - this class
 * now ALSO probes for two kinds of pair the engine's own clustering would never volunteer, using
 * {@link MameMatchingApi#evaluatePair} ({@code MatchingOperations.executeDecisionTree}), which - unlike
 * {@link MameMatchingApi#simulate} - can judge ANY pair handed to it, not only ones the engine's own
 * blocking already grouped together:
 * <ul>
 * <li>{@link #probeSharedKeyPairs} - up to {@link #MAX_PROBE_PAIRS} pairs of two records of DIFFERENT
 * source systems sharing the SAME value of the plan's own identifying key field ({@link #keyFieldName} -
 * a {@code FOLD_KEY}-derived attribute such as {@code nameKey} when the plan has one, else the plan's own
 * first exact-tier field). Feeds family 2 ("same key, but refused") - the {@code sharedKeyValues} SQL this
 * uses is a straight restoration of the pre-lot design (`git log -p` on this file, commit c08a5b1: {@code
 * sharedKeyValues}/{@code rowsForKey}), which the coordinator verified live, 2026-09-21 (see that method's
 * own Javadoc for the live-verified statement shape it shares with {@link CrossSystemMeasures#sharedCount}).</li>
 * <li>{@link #probeNearMissPairs} - up to {@link #MAX_PROBE_PAIRS} pairs of two records of DIFFERENT source
 * systems sharing the plan's own non-fuzzy EXACT-tier fields (e.g. {@code country}/{@code cityKey}) while
 * carrying a DIFFERENT identifying key - the pairs where only the fuzzy anchor field can decide. Feeds
 * family 1 ("caught without an exact key"). A NEW query, {@link #nearMissGroupsSql} - same derived-table/
 * {@code GROUP BY}/{@code HAVING} shape as {@link #sharedKeyValuesSql}, extended to more than one grouping
 * column and a second {@code HAVING COUNT(DISTINCT ...) > 1} clause on the key field - never a self-join,
 * for the same reason {@code CrossSystemMeasures}'s own class Javadoc gives (linear in row count; a
 * self-join is on the order of rows-squared, harmless on this project's ~107-row demo table, not on a real
 * one - CLAUDE.md: "EBX tient le volume").</li>
 * </ul>
 * Every probed pair still gets its verdict from the add-on itself ({@code evaluatePair}'s own {@code
 * DecisionTreeExecutionResult.getMatchingResult()} - the SAME verdict category {@link MameMatchingApi#simulate}
 * would report for the same pair, per that method's own Javadoc) - a probe pair whose {@code evaluatePair}
 * call fails or answers no verdict is simply DROPPED, never added with a fabricated verdict. {@code
 * review.MatchSimulationBlock} tags every pair with its {@link MatchPlanView.SimulationPair#origin} so the
 * rendered table can say where a row came from, and reads {@link MatchPlanView.SimulationPair#sameKey}
 * (computed here, once, from the two real records) rather than re-deriving "same key" itself - it has no
 * EBX access and cannot look the key field's own NAME up (that lookup needs the Staging tables, this
 * package only).
 * <p>
 * <b>What changed from the shared-key design</b>: instead of picking pairs that are GUARANTEED to share the
 * plan's own clustering key (and are therefore trivially Suspect), this class draws {@link #MAX_PIVOTS} master
 * records at RANDOM ({@link #PIVOTS_PER_SYSTEM_QUOTA} per source system present on the table, a fresh draw
 * every click - task brief explicitly allows this), and for each drawn pivot asks the add-on's own {@code
 * simulateMatch} for EVERY candidate it finds in that pivot's pre-clustering group, together with the real
 * verdict for each ({@link MameMatchingApi#simulate}). That is a faithful sample of what the CONFIGURED plan
 * actually decides, blocking/clustering included - not a hand-picked set of pairs guaranteed to land in one
 * bucket. {@code review.MatchSimulationBlock} then computes the numeric summary and the three families of
 * instructive pairs from this full candidate set (engine candidates AND the two probe sets above), never
 * from a pre-filtered subset.
 * <p>
 * <b>Per-field real similarity scores, when obtainable</b>: for up to {@link #MAX_DETAIL_PAIRS} total {@code
 * evaluatePair} calls per click (shared across the probe sets, which NEED the call for a verdict at all, and
 * the engine candidates, which only use it to enrich an already-known verdict with per-field scores - probe
 * pairs are evaluated FIRST, see {@link #simulate}'s own ordering comment), this class calls {@link
 * MameMatchingApi#evaluatePair} to get the add-on's OWN real per-field score - see that method's own
 * Javadoc for exactly what it exposes and the one open question (the score's numeric scale). An engine
 * candidate beyond the cap, or one whose {@code evaluatePair} call failed, simply keeps an empty {@code
 * fieldScores} map - {@code review.MatchSimulationBlock} reads that as "not verified for this pair", never
 * as a zero score.
 * <p>
 * <b>Random per-system draw is a BOUNDED scan, not a true whole-table sample</b>: {@link #reservoirSample}
 * scans at most {@link #MAX_SCAN_PER_SYSTEM} rows of a source system before stopping (reservoir sampling -
 * Algorithm R - is only unbiased over the rows it actually sees). At this project's demo scale (hundreds of
 * rows per master table, `docs/MATCHING.md`) this covers every row; on a real project's much larger master
 * table (CLAUDE.md: "EBX tient le volume", hundreds of millions of rows are never marginal here) the draw would
 * only be uniform over the FIRST {@link #MAX_SCAN_PER_SYSTEM} rows the request happens to return, not the whole
 * table - an acceptable trade-off for an interactive reviewer's sampling click, explicitly not attempted to fix
 * here (a bulk/streaming sampling design would be a different, much larger piece of work). The same caveat
 * applies to {@link #rowsForFieldValue}/{@link #rowsForFieldValues} (bounded at {@link
 * #MAX_ROWS_SCANNED_PER_GROUP}) and to {@link #sharedKeyValues}/{@link #nearMissGroups} (bounded at {@link
 * #MAX_CANDIDATE_KEY_VALUES}/{@link #MAX_CANDIDATE_NEAR_MISS_GROUPS} candidate groups scanned before giving
 * up) - every probe query stops well short of a full scan, by design.
 * <p>
 * <b>Never exercised against a live instance</b> (task constraint: no EBX access this session) - the SQL shape
 * ({@link CrossSystemMeasures#rowCountsBySystem}) mirrors that class' own already-verified-live statements as
 * closely as this different question (a plain per-system row count with no field-specific {@code WHERE})
 * allows; {@link #sharedKeyValuesSql} mirrors the ORIGINAL, coordinator-verified-live {@code sharedKeyValues}
 * statement removed by commit c08a5b1 (see that method's own Javadoc); {@link #nearMissGroupsSql} is a NEW
 * statement, built by direct analogy, never itself run live; the add-on Java API calls ({@link
 * MameMatchingApi#simulate}/{@link MameMatchingApi#evaluatePair}) were read from `javap` and the add-on's
 * on-disk Javadoc only, never run.
 * <p>
 * <b>2026-09-22 23:30, self-heal-simulate-activation lot - {@link #simulate} now self-heals a boot-race
 * defect measured live twice on {@code Company} (15:11 and 23:26, both right after a Tomcat restart).</b>
 * The add-on's own canopy/Lucene candidate search threw {@code IllegalArgumentException: field
 * [Linked_Field_State] not found} from deep inside its own code, and the user service showed EBX's generic
 * fatal-error page instead of anything actionable - see {@code ebx.MatchingActivationGuard}'s own class
 * Javadoc for the full root cause. {@link MameMatchingApi#simulate} itself also carries a second,
 * defense-in-depth translation of the same add-on exception, for whatever the self-heal below does not catch.
 * <p>
 * <b>2026-09-23 07:45, second live incident on {@code Contact} - the self-heal's own ORDER and its gate were
 * both wrong, fixed (`docs/MATCHING.md` &sect;13.8).</b> A {@code PROPOSED} plan on a table NEVER configured
 * before answered a genuine {@code CONFIG_NOT_FOUND} from the activation check, which used to read exactly
 * like the boot-race bug but was a different situation entirely: {@link #simulate} DOES call {@code
 * MatchingPreviewPolicy.ensure} (line above, unconditionally first for a preview) before the activation check
 * - the real bugs were narrower. (1) The old gate required {@code published && metadatasetExists} before even
 * attempting {@code activate_matching} - exactly the state a config {@code ensure} JUST generated is always
 * in (never published, no metadataset yet), so it refused instead of self-healing a table being activated for
 * the FIRST time, not merely re-activated after a boot race; {@code MatchingActivationGuard#decide} no longer
 * looks at those two fields at all - ANY found-but-not-{@code ENABLED} config gets one repair attempt.
 * (2) {@code ensure} and this method's OWN activation check each independently resolved {@code
 * AMBIGUOUS_TABLE} from a DIFFERENT tool's own candidate list ({@code get_matching_config} vs. {@code
 * get_matching_activation_status}) - nothing guaranteed they agreed on the same {@code dataModel}, so a config
 * written under one could read back {@code CONFIG_NOT_FOUND} under the other. Fixed by threading {@code
 * ensure}'s OWN resolved {@code dataModel} ({@link MatchingPreviewPolicy.Result#dataModel}) straight into
 * {@code MatchingActivationGuard#ensureActivatedAfterPreviewEnsure} below, verbatim, for the preview branch -
 * the applied branch (no {@code ensure} call at all) keeps self-resolving as before via {@code
 * MatchingActivationGuard#ensureActivatedBeforeSimulate}. See that class' own class Javadoc for the full
 * before/after and the exact call sequence ({@code status} only when already enabled; {@code status, activate,
 * status} - a genuine RE-READ, never a reuse of {@code activate_matching}'s own answer - when a repair is
 * needed) now unit-tested against a hand-written fake ({@code MatchingActivationGuardTest}).
 * <p>
 * <b>2026-09-23 14:20 correction (`docs/MATCHING.md` &sect;28):</b> "the applied branch keeps self-resolving
 * {@code dataModel} from a probe read" above is no longer how {@code ensureActivatedBeforeSimulate} works - it
 * now computes {@code dataModel} directly from {@code pProjectCode}, no probe call, and {@code
 * ensureActivatedAfterPreviewEnsure} takes {@code pProjectCode} too (this call site passes it) purely so its own
 * {@code McpStatusPort} can retry once on a genuine {@code AMBIGUOUS_TABLE} the same way - see {@code
 * MatchingActivationGuard}'s own class Javadoc for why the old probe-based resolution was unsound.
 * <p>
 * <b>A never-before-activated table's companion metadataset is therefore now created as a side effect of a
 * "Simulate" click</b> on a {@code PROPOSED}/{@code ACCEPTED} plan - a one-way but harmless write
 * ({@code activate_matching}'s own fan-out; the metadataset is exactly what the table needs to be simulated
 * or matched at all, and nothing about creating it is undone by leaving the plan itself unaccepted).
 * <p>
 * <b>2026-09-22 23:38, diagnostic-visibility lot - a live "Simulate on 20 pivots" click (Company, preview
 * policy {@code Company__preview}, one AVERAGE &ge; 80 node on {@code name} {@code fuzzyFullText} only) showed
 * "score not exposed by the engine" on all 32 rows with NOTHING in {@code kernel.log}.</b> {@link
 * MameMatchingApi#evaluatePair} now always returns a {@code diagnostic} explaining why (an exception's class/
 * message/top-5-frames, or a "{@code getNodeScores() answered ...}" description on a successful-but-empty
 * call - that class' own Javadoc). This class:
 * <ul>
 * <li>{@link #mergeFieldScores} matches a raw {@code FieldScore#field} name to one of {@code comparedFields} by
 * NORMALISING both sides first ({@link #normalizeFieldPath}/{@link #matchScoreFieldToPlanField} - strip a
 * leading {@code ./}/{@code /}, compare the last path step too) instead of an exact string match, which
 * silently dropped a score whenever the add-on's own raw spelling did not happen to equal the plan's.</li>
 * <li>Collects every {@code TreeEvaluation#diagnostic} worth flagging (an outright failure, or a NON-full-text
 * field the engine was expected to score but didn't - a full-text field never counts, since {@code
 * review.MatchSimulationBlock#fullTextFieldNames}' own Javadoc already treats "the add-on does not expose a
 * per-field score for a full-text algorithm" as an expected characteristic, not a bug - HYPOTHESIS, never
 * measured live) into ONE aggregated {@code MatchPlanView.ScoreDiagnostics} (count + first 3 distinct
 * messages) on the returned {@code SimulationResult}, and logs it to {@code kernel.log} exactly ONCE per
 * click (never once per pair, which would spam it up to {@code MAX_DETAIL_PAIRS} times) - {@link
 * LoggingCategory#getKernel()}, the same category every other class of this module uses (CLAUDE.md &sect;9:
 * the module's own category writes nowhere on this instance).</li>
 * <li>Exposes whether the near-miss probe ({@link #probeNearMissPairs}) could even be BUILT for this plan
 * ({@link #nearMissApplicable}) and the plan's own identifying key field name, both on {@code
 * SimulationResult}, so {@code review.MatchSimulationBlock} can say "not applicable" instead of a misleading
 * "0 near-miss pairs found", and name the key field explicitly ("same nameKey") instead of the generic "same
 * exact key" - task brief item 3.</li>
 * </ul>
 */
public final class MatchPlanSimulationRunner
{
	/** The mockup's own number ("Simulate on 20 pivots", `docs/mockups/review-match-plan-simulation.html`). */
	public static final int MAX_PIVOTS = 20;
	/** The mockup's own split ("10 ERP, 10 CRM") - a per-system TARGET, not a hard per-system cap when more than two systems are present (see {@link #drawPivots}'s own comment). */
	private static final int PIVOTS_PER_SYSTEM_QUOTA = 10;
	/** Bound on how many rows of ONE source system {@link #reservoirSample} scans before stopping - see class Javadoc, "random per-system draw is a bounded scan". */
	private static final int MAX_SCAN_PER_SYSTEM = 2000;
	/** Bound on how many unique candidate pairs (engine candidates AND probe pairs together) get a real per-field score/verdict via {@link MameMatchingApi#evaluatePair} - each is one extra add-on call; generous for this project's demo-scale master tables (`docs/MATCHING.md`: hundreds of rows), never unbounded. Unchanged from the pre-probes lot (task brief: "as today"). */
	static final int MAX_DETAIL_PAIRS = 60;
	/** review-match-plan-simulation-probes lot (2026-09-22): the task brief's own "N = 20" for probe set A, reused for probe set B too for symmetry - neither brief nor mockup gives it a separate number. */
	private static final int MAX_PROBE_PAIRS = 20;
	/** Bound on how many DISTINCT shared-key VALUES {@link #sharedKeyValues} scans before giving up on finding {@link #MAX_PROBE_PAIRS} usable pairs - the same "small multiple of the target" convention the pre-lot {@code MAX_CANDIDATE_KEYS} used (removed by c08a5b1, restored here). */
	private static final int MAX_CANDIDATE_KEY_VALUES = MatchPlanSimulationRunner.MAX_PROBE_PAIRS * 5;
	/** Same convention as {@link #MAX_CANDIDATE_KEY_VALUES}, for {@link #nearMissGroups}'s own distinct exact-field-combination groups. */
	private static final int MAX_CANDIDATE_NEAR_MISS_GROUPS = MatchPlanSimulationRunner.MAX_PROBE_PAIRS * 5;
	/** Bound on how many rows of ONE shared-key value or ONE near-miss group {@link #rowsForFieldValue}/{@link #rowsForFieldValues} scans before giving up - see class Javadoc, "never scan the whole cross product": normally 2-3 rows per group at this project's demo scale, generous enough for an unexpectedly large group without ever approaching a full-table scan. */
	private static final int MAX_ROWS_SCANNED_PER_GROUP = 200;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private MatchPlanSimulationRunner()
	{
	}

	/**
	 * @throws OperationException no master zone yet, the master/entity table could not be found, the {@code
	 *             MatchPlan} row itself could not be found, or (simulation-preview-policy lot, 2026-09-22) the
	 *             plan is not {@code APPLIED} and {@link MatchingPreviewPolicy#ensure} could not write a preview
	 *             policy for it (add-on missing, transport failure, {@code apply_matching_config}/{@code
	 *             generate_*} refused outright) - surfaced once, on the FIRST pivot it is hit for, not swallowed
	 *             per pivot: every pivot shares the same entity/policy, so a precondition failure on one means
	 *             the same failure on all of them.
	 */
	public static MatchPlanView.SimulationResult simulate(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pProjectId,
		final String pEntityName, final String pRequirementsJson, final String pMatchPlanRowId) throws OperationException
	{
		return MatchPlanSimulationRunner.run(pSession, pFactory, pProjectCode, pProjectId, pEntityName, pRequirementsJson, pMatchPlanRowId, MatchPlanSimulationRunner.MAX_DETAIL_PAIRS).result;
	}

	/**
	 * Lot F1 (2026-10-19, {@code docs/MATCHING.md} &sect;70): what one simulation gives back - the result the panel shows, and the numeric feedback measured on the same pairs
	 * ({@link SimulationFeedback}: counts only), with the statements of both (coverage and feedback) as stored on the plan.
	 */
	public static final class Run
	{
		public final MatchPlanView.SimulationResult result;
		/** Null when no pair was judged. */
		public final SimulationFeedback.Result feedback;
		/** The statements stored on the plan ({@code requirements.simulationFacts}): the candidate coverage ones, then the feedback ones - English. */
		public final List<String> storedFacts;

		Run(final MatchPlanView.SimulationResult pResult, final SimulationFeedback.Result pFeedback, final List<String> pStoredFacts)
		{
			this.result = pResult;
			this.feedback = pFeedback;
			this.storedFacts = pStoredFacts;
		}
	}

	/** Prefix of the statements of the feedback in {@code requirements.simulationFacts} (the coverage ones start with {@code "simulation: "}). */
	public static final String FEEDBACK_FACT_PREFIX = "plan simulation: ";

	/**
	 * Lot F1: the same simulation as {@link #simulate} (same pivots draw, same probes, same coverage), with the numeric feedback of the plan measured on its pairs. {@code pDetailBudget}
	 * bounds the {@code evaluatePair} calls of the click ({@value #MAX_DETAIL_PAIRS} for the Simulate button): a larger one records the add-on's visit order for more pairs, so that the
	 * per-leaf counts of the feedback cover more of them.
	 */
	public static Run run(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pProjectId, final String pEntityName,
		final String pRequirementsJson, final String pMatchPlanRowId, final int pDetailBudget) throws OperationException
	{
		final Adaptation project = ProposalReviews.findProjectByCode(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (MatchPlanSimulationRunner.blank(masterDataspaceKey) || MatchPlanSimulationRunner.blank(masterDatasetName))
		{
			throw EngineRefusal.coded("missing.noMasterZone", "Project " + pProjectCode + " has no master zone yet: run master/generate first", pProjectCode);
		}
		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		final Adaptation masterDataset = masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(masterDatasetName));
		if (masterDataset == null)
		{
			throw EngineRefusal.coded("missing.masterDatasetPath", "Master dataset " + masterDataspaceKey + "/" + masterDatasetName + " could not be found.", masterDataspaceKey + "/" + masterDatasetName);
		}
		final AdaptationTable masterTable = masterDataset.getTable(Path.parse(MasterModel.tablePath(pEntityName)));

		// simulation-preview-policy lot (2026-09-22): a plan that is not yet APPLIED gets simulated on a
		// dedicated, inactive preview policy (ebx.MatchingPreviewPolicy) instead of being refused outright - the
		// add-on can only simulate a processCode it actually knows about (ebx.MameMatchingApi#simulate/
		// evaluatePair both take one), so an ACCEPTED-but-not-yet-APPLIED, or even still-PROPOSED, plan needs one
		// written before this method can call either. The plan row is looked up fresh here (never trusted from
		// the caller's own possibly-stale ReviewLine.status) - same "never trust a moment-ago read" discipline
		// every other write path in this package already follows.
		final Adaptation plan = ProposalReviews.lookup(pFactory, FactoryPaths.MATCH_PLAN, pMatchPlanRowId);
		if (plan == null)
		{
			throw EngineRefusal.coded("missing.matchPlan", "Matching plan " + pMatchPlanRowId + " for " + pEntityName + " could not be found.", pMatchPlanRowId, pEntityName);
		}
		// review never imports ebx and ebx avoids importing review the other way (this package's own layering
		// convention) - so the localized "simulated on ..." wording is built by review.MatchPlanView itself, from
		// the plain isPreview/policyCode facts this method hands back on SimulationResult, never resolved here.
		final String planStatus = plan.getString(FactoryPaths.MatchPlan.STATUS);
		final boolean isPreview = !"APPLIED".equals(planStatus);
		MatchingPreviewPolicy.Result preview = null;
		if (isPreview)
		{
			try
			{
				preview = MatchingPreviewPolicy.ensure(pSession, pFactory, pProjectCode, pEntityName, plan);
			}
			catch (final OperationException writeFailure)
			{
				// lot V2-B (2026-09-30, docs/MATCHING.md sec 47), native since lot W13 (2026-10-12): a plan comparing child-table fields (Adresse.street on a
				// Personne plan) is written to the add-on through the table's business object. If the add-on refuses it, the plan is simulated WITHOUT its
				// relation tiers and the result says which tiers were not simulated and why - never a silent failure, never a silent skip.
				if (!com.on.ebx.factory.master.RelationTierStripper.hasRelationFields(MatchPlanSimulationRunner.parseOrEmpty(pRequirementsJson)))
				{
					throw writeFailure;
				}
				preview = MatchingPreviewPolicy.ensureWithoutRelationTiers(pSession, pFactory, pProjectCode, pEntityName, plan,
					"the relation fields could not be written to the add-on: " + writeFailure.getMessage());
			}
		}
		final String policyCode = isPreview ? preview.policyCode : pEntityName;
		// 2026-09-22 22:45 (the product owner, live Company): an active:false preview refused with "invalid state -
		// matching paused or policy inactive" - MatchingPreviewPolicy now writes active:true instead (see its
		// own Javadoc), but if the add-on ever refuses a preview run again for some OTHER reason, this appends
		// exactly what shape the preview policy has to the refusal, so the next diagnosis is immediate.
		final String previewDiagnosticOrNull = isPreview ? preview.diagnosticSummary : null;

		// self-heal-simulate-activation lot (2026-09-22, ORDER fixed 2026-09-23 - docs/MATCHING.md sec 13.8):
		// checked ONCE per click, before the FIRST simulateMatch/executeDecisionTree call below - AFTER the
		// preview policy was just ensured above, never before it (the live Contact incident, 2026-09-23 07:45:
		// checking activation before ensure() ever ran a config into existence answered a genuine CONFIG_NOT_FOUND,
		// which used to read exactly like the boot-race bug but wasn't). The preview path reuses ensure()'s OWN
		// resolved dataModel verbatim (ebx.MatchingActivationGuard's own class Javadoc: independently re-resolving
		// AMBIGUOUS_TABLE from a different tool's own candidate list is what caused that incident); the applied
		// path (no ensure() call above) self-resolves as before. Throws outright (never swallowed) when the table
		// cannot be made runnable. NOTE: on a table that had NEVER been activated before, this call creates its
		// companion metadataset as a side effect (activate_matching's own fan-out) - a one-way but harmless write,
		// the metadataset being exactly what the table needs to be simulated or matched at all.
		final String activationNote = isPreview
			? MatchingActivationGuard.ensureActivatedAfterPreviewEnsure(pSession, pFactory, pProjectCode, MasterModel.tablePath(pEntityName), preview.dataModel)
			: MatchingActivationGuard.ensureActivatedBeforeSimulate(pSession, pFactory, pProjectCode, MasterModel.tablePath(pEntityName));

		final List<String> skippedRelationTiers = preview == null ? new ArrayList<String>() : preview.skippedTiers;
		final String relationSkipReason = preview == null ? null : preview.skipReason;

		final JsonNode requirements = MatchPlanSimulationRunner.parseOrEmpty(pRequirementsJson);
		final List<String> comparedFields = MatchPlanSimulationRunner.comparedFieldNames(requirements);
		// lot F1: the values of the keys the plan or its key facts name are kept on every pair too (the feedback compares them; the panel reads the compared fields only)
		final List<String> valueFields = MatchPlanSimulationRunner.withKeyFields(comparedFields, requirements);
		final List<Adaptation> pivots = MatchPlanSimulationRunner.drawPivots(pSession, masterDataset, masterTable, pEntityName);
		if (pivots.isEmpty())
		{
			// the master table itself has no rows at all (systemCounts empty, see drawPivots) - the probes below
			// would find nothing either, so there is genuinely nothing to simulate, not merely an unlucky draw
			return new Run(new MatchPlanView.SimulationResult(new ArrayList<MatchPlanView.SimulationPair>(), 0, isPreview, policyCode, activationNote, null, true, null,
				skippedRelationTiers, relationSkipReason, null), null, new ArrayList<String>());
		}

		// the plan's own identifying key field (a FOLD_KEY-derived attribute such as nameKey, else the plan's
		// first exact-tier field) - resolved ONCE, used both to compute every pair's own `sameKey` flag and to
		// drive probe set A; null when the entity has neither (both probe sets are then skipped, never guessed)
		final String keyField = MatchPlanSimulationRunner.keyFieldName(pFactory, pProjectId, pEntityName, requirements);
		final List<String> exactFields = MatchPlanSimulationRunner.exactFieldNames(requirements);
		// diagnostic-visibility lot (2026-09-22): the near-miss probe (probe set B) needs a NON-full-text exact
		// field besides the key to build a group query at all - see probeNearMissPairs' own bail-out, restated
		// here so review.MatchSimulationBlock can say "not applicable" instead of a misleading "found none".
		final boolean nearMissApplicable = MatchPlanSimulationRunner.nearMissApplicable(exactFields, keyField);
		final Set<String> fullTextFields = MatchPlanSimulationRunner.fullTextFieldNames(requirements);
		final List<String> scoreDiagnostics = new ArrayList<String>();

		final Set<String> seenPairKeys = new LinkedHashSet<String>();
		final Map<String, Set<String>> candidatesByPivot = new LinkedHashMap<String, Set<String>>();
		final List<PendingPair> engineCandidates = MatchPlanSimulationRunner.collectEngineCandidates(pSession, pivots, policyCode, comparedFields, valueFields, keyField,
			seenPairKeys, previewDiagnosticOrNull, candidatesByPivot);
		final List<ProbeCandidate> probeSharedKey = MatchPlanSimulationRunner.probeSharedKeyPairs(pSession, masterDataset, masterTable, pEntityName, keyField,
			seenPairKeys);
		final List<ProbeCandidate> probeNearMiss = MatchPlanSimulationRunner.probeNearMissPairs(pSession, masterDataset, masterTable, pEntityName, exactFields,
			keyField, seenPairKeys);

		// probe pairs are resolved FIRST: they have no verdict at all until evaluatePair runs (evaluatePair is
		// the only add-on call able to judge a pair the engine's own clustering never offered - class Javadoc),
		// so a probe pair that never gets a detail call is simply dropped, never shown with a fabricated
		// verdict. Engine candidates already carry a real verdict from `simulate` above - they only spend the
		// REMAINING budget enriching it with per-field scores, exactly the pre-probes-lot behaviour.
		final List<MatchPlanView.SimulationPair> pairs = new ArrayList<MatchPlanView.SimulationPair>();
		int detailCalls = 0;
		final List<ProbeCandidate> allProbes = new ArrayList<ProbeCandidate>(probeSharedKey.size() + probeNearMiss.size());
		allProbes.addAll(probeSharedKey);
		allProbes.addAll(probeNearMiss);
		for (final ProbeCandidate probe : allProbes)
		{
			if (detailCalls >= pDetailBudget)
			{
				break;
			}
			detailCalls++;
			final MameMatchingApi.TreeEvaluation evaluation = MameMatchingApi.evaluatePair(pSession, probe.pivotRecord, probe.targetRecord, policyCode);
			if (evaluation.matchingResult == null)
			{
				// evaluatePair itself failed, or answered no verdict for this pair - never fabricate one, drop it
				continue;
			}
			final Map<String, Double> scores = new LinkedHashMap<String, Double>();
			MatchPlanSimulationRunner.mergeFieldScores(scores, evaluation, comparedFields, exactFields, fullTextFields, scoreDiagnostics);
			pairs.add(new MatchPlanView.SimulationPair(MatchPlanSimulationRunner.recordLabel(probe.pivotRecord, comparedFields),
				MatchPlanSimulationRunner.readField(probe.pivotRecord, "sourceSystem"), MatchPlanSimulationRunner.recordLabel(probe.targetRecord, comparedFields),
				MatchPlanSimulationRunner.readField(probe.targetRecord, "sourceSystem"), MatchPlanSimulationRunner.fieldValues(probe.pivotRecord, valueFields),
				MatchPlanSimulationRunner.fieldValues(probe.targetRecord, valueFields), scores, evaluation.matchingResult, probe.origin,
				MatchPlanSimulationRunner.rawFieldsEqual(probe.pivotRecord, probe.targetRecord, keyField), evaluation.nodeNames));
		}
		for (final PendingPair pending : engineCandidates)
		{
			if (detailCalls >= pDetailBudget)
			{
				break;
			}
			detailCalls++;
			final MameMatchingApi.TreeEvaluation evaluation = MameMatchingApi.evaluatePair(pSession, pending.pivotRecord, pending.targetRecord, policyCode);
			MatchPlanSimulationRunner.mergeFieldScores(pending.scores, evaluation, comparedFields, exactFields, fullTextFields, scoreDiagnostics);
			// review.MatchSimulationBlock#leafWords' own named-path feature (binary-decision-tree-screen lot,
			// 2026-09-22) needs the add-on's real visit order - shared-mutable-list trick, same as `scores` above:
			// `pending.pair.enginePath` is this SAME list object, so filling it here reaches the already-built pair.
			pending.enginePath.addAll(evaluation.nodeNames);
		}
		for (final PendingPair pending : engineCandidates)
		{
			pairs.add(pending.pair);
		}
		final MatchPlanView.ScoreDiagnostics diagnostics = MatchPlanSimulationRunner.buildScoreDiagnostics(scoreDiagnostics);
		if (diagnostics != null)
		{
			// diagnostic-visibility lot (2026-09-22): ONE kernel.log warning per click, however many of the up
			// to MAX_DETAIL_PAIRS evaluatePair calls this click made actually failed/degraded - never one line
			// per pair, which would spam it (class Javadoc).
			LoggingCategory.getKernel().warn("[mdm-factory] Simulate on " + pEntityName + " (policy " + policyCode + "): " + diagnostics.count
				+ " pair(s) with an unavailable per-field score - " + MatchPlanSimulationRunner.join(diagnostics.messages, " | "));
		}
		MatchPlanView.SimulationResult result = new MatchPlanView.SimulationResult(pairs, pivots.size(), isPreview, policyCode, activationNote, diagnostics, nearMissApplicable, keyField,
			skippedRelationTiers, relationSkipReason, null);
		// lot M16 (docs/MATCHING.md sec 68): the candidate coverage - a measurement for the model's next prompt and the plan card; its failure never fails the simulation
		List<String> coverageFacts = new ArrayList<String>();
		final List<String> notMeasured = new ArrayList<String>();
		boolean coverageMeasured = false;
		try
		{
			final CandidateCoverage.Result coverage = MatchPlanSimulationRunner.measureCoverage(pSession, masterDataset, masterTable, pEntityName, pivots, candidatesByPivot, requirements, notMeasured);
			if (coverage != null)
			{
				coverageFacts = coverage.facts(MatchPlanSimulationRunner.coverageCause(requirements));
				result = result.withCoverage(coverage, coverageFacts);
				coverageMeasured = true;
			}
		}
		catch (final Throwable ex)
		{
			// lot F1b: an Error too (class initialisation of generated SQL code) - the simulation goes on without the coverage
			NonFatal.rethrowIfFatal(ex);
			LoggingCategory.getKernel().warn("[mdm-factory] Simulate on " + pEntityName + ": the candidate coverage could not be measured - " + NonFatal.reason(ex, 400));
			coverageFacts = new ArrayList<String>();
			coverageFacts.add("simulation: the candidate coverage was not measured (" + NonFatal.reason(ex, 200) + ").");
		}
		// lot F1b: a key whose read failed is said so, next to the keys that were measured
		coverageFacts.addAll(notMeasured);
		// lot F1 (docs/MATCHING.md sec 70): what the plan does to the pairs the engine evaluated - counts only; a failure to measure never fails the simulation either
		SimulationFeedback.Result feedback = null;
		final List<String> feedbackFacts = new ArrayList<String>();
		try
		{
			final SimulationFeedback.Result measured = SimulationFeedback.measure(requirements, MatchPlanSimulationRunner.feedbackPairs(pairs), pivots.size());
			if (!measured.isEmpty())
			{
				feedback = measured;
				for (final String fact : measured.facts())
				{
					feedbackFacts.add(MatchPlanSimulationRunner.FEEDBACK_FACT_PREFIX + fact);
				}
			}
		}
		catch (final Throwable ex)
		{
			NonFatal.rethrowIfFatal(ex);
			LoggingCategory.getKernel().warn("[mdm-factory] Simulate on " + pEntityName + ": the plan feedback could not be measured - " + NonFatal.reason(ex, 400));
		}
		final List<String> stored = new ArrayList<String>(coverageFacts);
		stored.addAll(feedbackFacts);
		if (coverageMeasured || !coverageFacts.isEmpty() || !feedbackFacts.isEmpty())
		{
			// both are kept on the plan for the next "Propose another"; when the two together no longer fit the field, the coverage statements (the older ones) stay alone
			if (!ProposalReviews.recordSimulationFacts(pSession, pFactory, pMatchPlanRowId, stored) && !feedbackFacts.isEmpty())
			{
				ProposalReviews.recordSimulationFacts(pSession, pFactory, pMatchPlanRowId, coverageFacts);
			}
		}
		return new Run(result, feedback, stored);
	}

	/** Lot F1: the pairs of the panel as the plain pairs the feedback measures (verdict, the values of the keys, the add-on's visit order); the probe origins are counted apart. */
	static List<SimulationFeedback.Pair> feedbackPairs(final List<MatchPlanView.SimulationPair> pPairs)
	{
		final List<SimulationFeedback.Pair> out = new ArrayList<SimulationFeedback.Pair>();
		for (final MatchPlanView.SimulationPair pair : pPairs)
		{
			out.add(new SimulationFeedback.Pair(MatchPlanView.SimulationPair.ORIGIN_ENGINE.equals(pair.origin) ? SimulationFeedback.ORIGIN_ENGINE : SimulationFeedback.ORIGIN_PROBE, pair.verdict,
				pair.pivotValues, pair.targetValues, pair.enginePath));
		}
		return out;
	}

	/** Lot F1: the compared fields, then the clustering fields and the keys of the plan's key facts - the fields whose values the feedback compares. Without duplicates, order kept. */
	static List<String> withKeyFields(final List<String> pComparedFields, final JsonNode pRequirements)
	{
		final LinkedHashSet<String> out = new LinkedHashSet<String>(pComparedFields);
		for (final JsonNode field : pRequirements.path("clusteringFields"))
		{
			if (field.isTextual() && !field.asText().isEmpty())
			{
				out.add(field.asText());
			}
		}
		final KeyFacts facts = KeyFacts.fromJson(pRequirements.path("keyFacts"));
		if (facts != null)
		{
			out.addAll(facts.keyNames());
		}
		return new ArrayList<String>(out);
	}

	/**
	 * Lot M16: for each exact key of the plan that opens a path (a field of the key sets {@link CandidateFilterCheck} derives, exact algorithm), how many records sharing its value with a pivot were
	 * NOT among the candidates the engine returned for that pivot. One bulk read per key ({@link #rowsByFieldValue}, the per-value read as its fallback). {@code null} when the plan has no
	 * such key or no pivot fills one.
	 */
	private static CandidateCoverage.Result measureCoverage(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable, final String pEntityName,
		final List<Adaptation> pPivots, final Map<String, Set<String>> pCandidatesByPivot, final JsonNode pRequirements, final List<String> pNotMeasured)
	{
		final Set<String> keys = new LinkedHashSet<String>(CandidateFilterCheck.check(pRequirements).keyFields());
		keys.retainAll(MatchPlanSimulationRunner.exactFieldNames(pRequirements));
		if (keys.isEmpty() || pPivots.isEmpty())
		{
			return null;
		}
		final List<CandidateCoverage.Observation> observations = new ArrayList<CandidateCoverage.Observation>();
		for (final String key : keys)
		{
			// lot F1b: one key whose read fails (any Throwable) is said not measured, the others still are - never the whole simulation
			try
			{
				MatchPlanSimulationRunner.observeKey(pSession, pMasterDataset, pMasterTable, pEntityName, pPivots, key, observations);
			}
			catch (final Throwable ex)
			{
				NonFatal.rethrowIfFatal(ex);
				LoggingCategory.getKernel().warn("[mdm-factory] Simulate on " + pEntityName + ": the coverage of " + key + " could not be read - " + NonFatal.reason(ex, 400));
				pNotMeasured.add("simulation: " + key + ": not measured (query failed: " + NonFatal.reason(ex, 160) + ")");
			}
		}
		return observations.isEmpty() ? null : CandidateCoverage.measure(observations, pCandidatesByPivot, pPivots.size());
	}

	/** Lot F1b: the observations of ONE key (one bulk read, the per-value read as its fallback); throws whatever the reads throw. */
	private static void observeKey(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable, final String pEntityName, final List<Adaptation> pPivots,
		final String key, final List<CandidateCoverage.Observation> observations)
	{
		final Map<Adaptation, String> valueByPivot = new LinkedHashMap<Adaptation, String>();
		final LinkedHashSet<String> values = new LinkedHashSet<String>();
		for (final Adaptation pivot : pPivots)
		{
			final String value = MatchPlanSimulationRunner.readField(pivot, key);
			if (!MatchPlanSimulationRunner.blank(value))
			{
				valueByPivot.put(pivot, value);
				values.add(value);
			}
		}
		if (values.isEmpty())
		{
			return;
		}
		final Map<String, List<Adaptation>> bulk = MatchPlanSimulationRunner.rowsByFieldValue(pSession, pMasterDataset, pMasterTable, pEntityName, key, new ArrayList<String>(values));
		for (final Map.Entry<Adaptation, String> entry : valueByPivot.entrySet())
		{
			final String pivotId = entry.getKey().getOccurrencePrimaryKey().format();
			final List<Adaptation> rows = bulk != null && bulk.containsKey(entry.getValue()) ? bulk.get(entry.getValue()) : MatchPlanSimulationRunner.rowsForFieldValue(pMasterTable, key, entry.getValue());
			final List<String[]> sharing = new ArrayList<String[]>();
			for (final Adaptation row : MatchPlanSimulationRunner.orEmpty(rows))
			{
				final String id = row.getOccurrencePrimaryKey().format();
				if (!id.equals(pivotId))
				{
					sharing.add(new String[] { id, MatchPlanSimulationRunner.readField(row, "sourceSystem") });
				}
			}
			observations.add(new CandidateCoverage.Observation(key, pivotId, MatchPlanSimulationRunner.readField(entry.getKey(), "sourceSystem"), sharing));
		}
	}

	/** Lot M16: why a gap is expected, from the plan's own weights ({@link MatchingPolicyTranslator#analyzeShares}); null when the weights hold every key set above the add-on's 30 %. */
	private static String coverageCause(final JsonNode pRequirements)
	{
		try
		{
			final CandidateScoreShare.Analysis shares = MatchingPolicyTranslator.analyzeShares(pRequirements);
			if (shares == null || shares.ok())
			{
				return null;
			}
			final StringBuilder out = new StringBuilder("the add-on keeps a candidate only when the fields the two records agree on weigh at least 30% of the pivot's own weight (total ")
				.append(shares.totalWeight).append("); ");
			boolean first = true;
			for (final CandidateScoreShare.SetShare set : shares.failing)
			{
				out.append(first ? "" : "; ").append('[').append(set.fieldsText()).append("] holds ").append(set.percentText());
				first = false;
			}
			if (!shares.suggested.isEmpty())
			{
				out.append("; weights that hold them: clusteringWeights ").append(shares.toJson().path("suggestedClusteringWeights"));
			}
			return out.toString();
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * Keeps the two {@code Adaptation}s and the (mutable, shared with the built {@link
	 * MatchPlanView.SimulationPair}) score map alive between the candidate-collection pass and the detail
	 * pass, without re-reading anything from EBX a second time. {@link #enginePath} is the SAME
	 * shared-mutable-list trick as {@link #scores} (binary-decision-tree-screen lot, 2026-09-22): {@link #pair}
	 * is built before {@code evaluatePair} ever runs, so its {@code enginePath} field can only ever be filled
	 * by mutating this SAME list object afterwards.
	 */
	private static final class PendingPair
	{
		final MatchPlanView.SimulationPair pair;
		final Adaptation pivotRecord;
		final Adaptation targetRecord;
		final Map<String, Double> scores;
		final List<String> enginePath;

		PendingPair(final MatchPlanView.SimulationPair pPair, final Adaptation pPivotRecord, final Adaptation pTargetRecord, final Map<String, Double> pScores,
			final List<String> pEnginePath)
		{
			this.pair = pPair;
			this.pivotRecord = pPivotRecord;
			this.targetRecord = pTargetRecord;
			this.scores = pScores;
			this.enginePath = pEnginePath;
		}
	}

	/** A candidate pair {@link #probeSharedKeyPairs}/{@link #probeNearMissPairs} selected itself (never the add-on's own clustering) - no verdict yet, see {@link #simulate}'s own ordering comment. */
	private static final class ProbeCandidate
	{
		final Adaptation pivotRecord;
		final Adaptation targetRecord;
		final String origin;

		ProbeCandidate(final Adaptation pPivotRecord, final Adaptation pTargetRecord, final String pOrigin)
		{
			this.pivotRecord = pPivotRecord;
			this.targetRecord = pTargetRecord;
			this.origin = pOrigin;
		}
	}

	/**
	 * The pre-existing engine-candidate collection loop, unchanged in behaviour except it now also computes each
	 * pair's own {@code sameKey} flag (class Javadoc) - extracted to its own method so {@link #simulate} can
	 * read cleanly top to bottom. {@code pPolicyCode} (simulation-preview-policy lot, 2026-09-22): the REAL
	 * entity-named policy when the plan is {@code APPLIED}, else the {@code <Entity>__preview} policy {@link
	 * MatchingPreviewPolicy#ensure} just wrote - never the bare entity name assumed to already be a configured
	 * policy code. {@code pPreviewDiagnosticOrNull} - non-null only for a preview run - is appended to {@code
	 * MameMatchingApi.simulate}'s own refusal message when it throws (see {@link #simulate}'s own class Javadoc,
	 * 2026-09-22 22:45: an {@code active: false} preview answered exactly this refusal live, on the real Company
	 * table - {@code MatchingPreviewPolicy} now writes {@code active: true} instead, but if the add-on ever
	 * refuses a preview run again for some OTHER reason, the refusal should say which policy and what shape it
	 * had, not just repeat the add-on's own generic text).
	 */
	private static List<PendingPair> collectEngineCandidates(final Session pSession, final List<Adaptation> pPivots, final String pPolicyCode,
		final List<String> pComparedFields, final List<String> pValueFields, final String pKeyField, final Set<String> pSeenPairKeys, final String pPreviewDiagnosticOrNull,
		final Map<String, Set<String>> pCandidatesByPivot) throws OperationException
	{
		final List<PendingPair> pendingPairs = new ArrayList<PendingPair>();
		for (final Adaptation pivotRow : pPivots)
		{
			final String pivotKey = pivotRow.getOccurrencePrimaryKey().format();
			final MameMatchingApi.SimulationOutcome outcome;
			try
			{
				outcome = MameMatchingApi.simulate(pSession, pivotRow, pPolicyCode);
			}
			catch (final OperationException ex)
			{
				if (pPreviewDiagnosticOrNull == null)
				{
					throw ex;
				}
				throw EngineRefusal.codedWithCause("external.simulationFailed", ex.getMessage() + " - preview policy " + pPolicyCode + ": " + pPreviewDiagnosticOrNull, ex);
			}
			final Set<String> candidateKeys = new LinkedHashSet<String>();
			pCandidatesByPivot.put(pivotKey, candidateKeys);
			for (final MameMatchingApi.SimulationMatch match : outcome.matches)
			{
				final String targetKey = match.targetRecordPrimaryKey;
				if (targetKey != null)
				{
					// lot M16: every record the engine evaluated for THIS pivot, before the symmetric-pair deduplication below (a pair seen from the other side is still a candidate of this pivot)
					candidateKeys.add(targetKey);
				}
				if (targetKey == null || targetKey.equals(pivotKey) || match.targetRecord == null)
				{
					// a self-match, or a candidate the add-on reported without a resolvable record - skip, never fabricate
					continue;
				}
				final String pairKey = pivotKey.compareTo(targetKey) <= 0 ? pivotKey + "|" + targetKey : targetKey + "|" + pivotKey;
				if (!pSeenPairKeys.add(pairKey))
				{
					// the SAME unordered pair was already seen from the other side (this target was itself drawn as
					// a pivot earlier, or two different pivots share a candidate) - counted once, not twice
					continue;
				}
				final Map<String, Double> scores = new LinkedHashMap<String, Double>();
				final List<String> enginePath = new ArrayList<String>();
				final MatchPlanView.SimulationPair pair = new MatchPlanView.SimulationPair(
					MatchPlanSimulationRunner.recordLabel(pivotRow, pComparedFields), MatchPlanSimulationRunner.readField(pivotRow, "sourceSystem"),
					MatchPlanSimulationRunner.recordLabel(match.targetRecord, pComparedFields), MatchPlanSimulationRunner.readField(match.targetRecord, "sourceSystem"),
					MatchPlanSimulationRunner.fieldValues(pivotRow, pValueFields), MatchPlanSimulationRunner.fieldValues(match.targetRecord, pValueFields), scores,
					match.result, MatchPlanView.SimulationPair.ORIGIN_ENGINE, MatchPlanSimulationRunner.rawFieldsEqual(pivotRow, match.targetRecord, pKeyField),
					enginePath);
				pendingPairs.add(new PendingPair(pair, pivotRow, match.targetRecord, scores, enginePath));
			}
		}
		return pendingPairs;
	}

	// ------------------------------------------------------------------------------------------------- pivot draw

	/**
	 * Up to {@link #MAX_PIVOTS} rows drawn at random across every source system present on the table -
	 * {@link #PIVOTS_PER_SYSTEM_QUOTA} per system when at most two systems exist (the mockup's own "10 ERP, 10
	 * CRM"); with MORE than two systems, the quota is spread evenly ({@code MAX_PIVOTS / systemCount}, never
	 * less than 1) so the total still respects the cap - a generalisation the task brief itself does not spell
	 * out for more than two systems, made explicit here rather than silently drawing 10 per system regardless
	 * of how many there are. A system with fewer rows than its own quota contributes every row it has (never
	 * padded, never an error); a table with a single source system still draws up to the full quota from it
	 * (nothing here requires two systems - see {@code CrossSystemMeasures#withinSystemDuplicateCount}'s own
	 * "single-source matching is not a lesser case").
	 */
	private static List<Adaptation> drawPivots(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable, final String pEntityName)
	{
		final Map<String, Long> systemCounts = CrossSystemMeasures.rowCountsBySystem(pSession, pMasterDataset, pEntityName);
		if (systemCounts == null || systemCounts.isEmpty())
		{
			return new ArrayList<Adaptation>();
		}
		final int systemCount = systemCounts.size();
		final int perSystemQuota = Math.max(1, Math.min(MatchPlanSimulationRunner.PIVOTS_PER_SYSTEM_QUOTA, MatchPlanSimulationRunner.MAX_PIVOTS / systemCount));
		final Random random = new Random();
		final List<Adaptation> pivots = new ArrayList<Adaptation>();
		for (final String system : systemCounts.keySet())
		{
			if (pivots.size() >= MatchPlanSimulationRunner.MAX_PIVOTS)
			{
				break;
			}
			final int remaining = MatchPlanSimulationRunner.MAX_PIVOTS - pivots.size();
			final int quota = Math.min(perSystemQuota, remaining);
			final String predicate = CatalogReader.equalsPredicate("./sourceSystem", system);
			pivots.addAll(MatchPlanSimulationRunner.reservoirSample(pMasterTable, predicate, quota, MatchPlanSimulationRunner.MAX_SCAN_PER_SYSTEM, random));
		}
		return pivots;
	}

	/**
	 * Algorithm R reservoir sampling over {@code pTable}'s rows matching {@code pXpathPredicate}, stopping
	 * after {@code pMaxScan} rows scanned - see class Javadoc, "random per-system draw is a bounded scan", for
	 * why this is not a true whole-table uniform sample beyond that bound.
	 */
	private static List<Adaptation> reservoirSample(final AdaptationTable pTable, final String pXpathPredicate, final int pSampleSize, final int pMaxScan,
		final Random pRandom)
	{
		final List<Adaptation> reservoir = new ArrayList<Adaptation>(Math.max(0, pSampleSize));
		if (pSampleSize <= 0)
		{
			return reservoir;
		}
		long seen = 0;
		final RequestResult rows = pTable.createRequestResult(pXpathPredicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null && seen < pMaxScan; row = rows.nextAdaptation())
			{
				seen++;
				if (reservoir.size() < pSampleSize)
				{
					reservoir.add(row);
				}
				else
				{
					final int slot = pRandom.nextInt((int) seen);
					if (slot < pSampleSize)
					{
						reservoir.set(slot, row);
					}
				}
			}
		}
		finally
		{
			rows.close();
		}
		return reservoir;
	}

	// ------------------------------------------------------------------------------------------------ probe set A

	/**
	 * Up to {@link #MAX_PROBE_PAIRS} pairs of two records of DIFFERENT source systems sharing the SAME value
	 * of {@code pKeyField} - the restored pre-lot "shared-key pairs" design (class Javadoc), now used ONLY to
	 * feed family 2 ("same key, but refused"), never as the whole simulation on its own (the product-owner
	 * rejection this whole lot answers). Returns an empty list, never throws, when {@code pKeyField} is null
	 * (no identifying key field could be resolved for this plan - see {@link #keyFieldName}) or the query
	 * itself finds nothing shared.
	 */
	private static List<ProbeCandidate> probeSharedKeyPairs(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable,
		final String pEntityName, final String pKeyField, final Set<String> pSeenPairKeys)
	{
		final List<ProbeCandidate> out = new ArrayList<ProbeCandidate>();
		if (pKeyField == null)
		{
			return out;
		}
		final List<String> keyValues = MatchPlanSimulationRunner.sharedKeyValues(pSession, pMasterDataset, pMasterTable, pEntityName, pKeyField);
		// lot volume-4 (2026-09-28): ONE SQL round trip for every candidate value at once (task brief item 5),
		// instead of one XPath scan per value (up to MAX_CANDIDATE_KEY_VALUES = 100). Null (not merely empty)
		// signals the bulk query itself failed (a transport error, or pKeyField not being a string-comparable
		// column - the bulk query embeds each value as a SQL string literal, the same "always quoted" choice
		// the pre-existing per-value rowsForFieldValue/CatalogReader.quote already made over XPath, but SQL is
		// less forgiving of a type mismatch than the core repository's own XPath engine) - the loop below then
		// falls back to the original per-value read for every value, exactly as before this lot, rather than
		// silently reporting zero pairs for an entity whose key field happens not to be a string.
		final Map<String, List<Adaptation>> rowsByValue = MatchPlanSimulationRunner.rowsByFieldValue(pSession, pMasterDataset, pMasterTable, pEntityName, pKeyField,
			keyValues);
		for (final String keyValue : keyValues)
		{
			if (out.size() >= MatchPlanSimulationRunner.MAX_PROBE_PAIRS)
			{
				break;
			}
			final List<Adaptation> rows = rowsByValue != null ? MatchPlanSimulationRunner.orEmpty(rowsByValue.get(keyValue))
				: MatchPlanSimulationRunner.rowsForFieldValue(pMasterTable, pKeyField, keyValue);
			if (rows.size() < 2)
			{
				continue;
			}
			final Adaptation pivot = rows.get(0);
			final Adaptation target = MatchPlanSimulationRunner.firstDifferentSystem(rows, pivot);
			if (target == null)
			{
				continue;
			}
			if (!MatchPlanSimulationRunner.addPairKey(pSeenPairKeys, pivot, target))
			{
				// already an engine candidate, or an earlier probe pair - never counted twice
				continue;
			}
			out.add(new ProbeCandidate(pivot, target, MatchPlanView.SimulationPair.ORIGIN_PROBE_SHARED_KEY));
		}
		return out;
	}

	/**
	 * Every DISTINCT non-null value of {@code pFieldName} shared by at least two different {@code
	 * sourceSystem}s, up to {@link #MAX_CANDIDATE_KEY_VALUES} - the ORIGINAL statement removed by commit
	 * c08a5b1 (`git log -p` on this file), unchanged: the same derived-table/{@code GROUP BY}/{@code HAVING
	 * COUNT(DISTINCT ...) > 1} shape {@link CrossSystemMeasures#sharedCount} independently re-derives for a
	 * plain count (linear in row count, never a self-join - see that method's own Javadoc, coordinator
	 * review 2026-09-21, `docs/MATCHING.md` &sect;4.2.2), except this reads the VALUES themselves. Returns an
	 * empty list ("nothing to probe"), never throws, on any failure.
	 */
	private static List<String> sharedKeyValues(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable, final String pEntityName,
		final String pFieldName)
	{
		if (!CrossSystemMeasures.isQuotable(pEntityName) || !CrossSystemMeasures.isQuotable(pFieldName))
		{
			return new ArrayList<String>();
		}
		final String sql = MatchPlanSimulationRunner.sharedKeyValuesSql(pEntityName, pFieldName);
		final List<String> out = new ArrayList<String>();
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext() && out.size() < MatchPlanSimulationRunner.MAX_CANDIDATE_KEY_VALUES)
				{
					final Object value = it.next().get("V");
					if (value != null)
					{
						out.add(MatchPlanSimulationRunner.xsValue(MatchPlanSimulationRunner.fieldNode(pMasterTable, pFieldName), value));
					}
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final Throwable ex)
		{
			NonFatal.rethrowIfFatal(ex);
			LoggingCategory.getKernel().warn("[mdm-factory] Simulate on " + pEntityName + ": the shared-key probe failed - " + NonFatal.reason(ex, 400));
			return new ArrayList<String>();
		}
		return out;
	}

	/**
	 * Pure SQL-text builder for {@link #sharedKeyValues} - split out so it can be unit-tested (identifier
	 * quoting, exact statement shape) without a live {@code Session}/{@code Adaptation}, the same discipline
	 * {@code CrossSystemMeasuresTest} already applies to {@link CrossSystemMeasures#isQuotable}/{@code
	 * quoteIdentifier}. Callers MUST check {@link CrossSystemMeasures#isQuotable} on both arguments first -
	 * this method itself does not guard against an unsafe identifier, to keep it a pure, total function of its
	 * two arguments.
	 */
	static String sharedKeyValuesSql(final String pEntityName, final String pFieldName)
	{
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String field = CrossSystemMeasures.quoteIdentifier(pFieldName);
		final String sourceSystem = CrossSystemMeasures.quoteIdentifier("sourceSystem");
		// lot volume-4 (2026-09-28, task brief item 5): FETCH FIRST bounds what the SQL engine itself computes,
		// not only what the Java-side loop consumes - EBX SQL dialect reference confirms OFFSET/FETCH FIRST
		// support (get_query_syntax, 2026-09-28). Same MAX_CANDIDATE_KEY_VALUES cap the caller's own iterator
		// loop already enforced.
		return "SELECT a." + field + " AS V FROM " + table + " a WHERE a." + field + " IS NOT NULL GROUP BY a." + field + " HAVING COUNT(DISTINCT a." + sourceSystem
			+ ") > 1 FETCH FIRST " + MatchPlanSimulationRunner.MAX_CANDIDATE_KEY_VALUES + " ROWS ONLY";
	}

	// ------------------------------------------------------------------------------------------------ probe set B

	/**
	 * Up to {@link #MAX_PROBE_PAIRS} pairs of two records of DIFFERENT source systems sharing every one of
	 * {@code pExactFields} while carrying a DIFFERENT value of {@code pKeyField} - the "only the fuzzy field
	 * can decide" pairs, feeding family 1 ("caught without an exact key"). Returns an empty list, never
	 * throws, when {@code pExactFields} is empty, {@code pKeyField} is null, or {@code pKeyField} is itself
	 * one of {@code pExactFields} (nothing coherent to probe: the "different key" side of the question would
	 * contradict the "same exact fields" side).
	 */
	private static List<ProbeCandidate> probeNearMissPairs(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable,
		final String pEntityName, final List<String> pExactFields, final String pKeyField, final Set<String> pSeenPairKeys)
	{
		final List<ProbeCandidate> out = new ArrayList<ProbeCandidate>();
		if (!MatchPlanSimulationRunner.nearMissApplicable(pExactFields, pKeyField))
		{
			return out;
		}
		final List<String[]> groups = MatchPlanSimulationRunner.nearMissGroups(pSession, pMasterDataset, pMasterTable, pEntityName, pExactFields, pKeyField);
		// lot volume-4 (2026-09-28): ONE SQL round trip for every candidate group at once (task brief item 5) -
		// same "null signals total failure, fall back to the original per-group read" discipline as
		// probeSharedKeyPairs above, for the same reason (an exact field embedded as a SQL string literal may
		// not match a non-string column the way the core repository's own XPath engine tolerates).
		final Map<String, List<Adaptation>> rowsByGroup = MatchPlanSimulationRunner.rowsByFieldCombo(pSession, pMasterDataset, pMasterTable, pEntityName, pExactFields,
			groups);
		for (final String[] groupValues : groups)
		{
			if (out.size() >= MatchPlanSimulationRunner.MAX_PROBE_PAIRS)
			{
				break;
			}
			final List<Adaptation> rows = rowsByGroup != null
				? MatchPlanSimulationRunner.orEmpty(rowsByGroup.get(MatchPlanSimulationRunner.comboKey(groupValues)))
				: MatchPlanSimulationRunner.rowsForFieldValues(pMasterTable, pExactFields, groupValues);
			final Adaptation[] nearMissPair = MatchPlanSimulationRunner.firstNearMissPair(rows, pKeyField);
			if (nearMissPair == null)
			{
				continue;
			}
			if (!MatchPlanSimulationRunner.addPairKey(pSeenPairKeys, nearMissPair[0], nearMissPair[1]))
			{
				continue;
			}
			out.add(new ProbeCandidate(nearMissPair[0], nearMissPair[1], MatchPlanView.SimulationPair.ORIGIN_PROBE_NEAR_MISS));
		}
		return out;
	}

	/**
	 * Every DISTINCT combination of {@code pExactFields}' values shared by at least two different {@code
	 * sourceSystem}s AND carrying at least two different values of {@code pKeyField}, up to {@link
	 * #MAX_CANDIDATE_NEAR_MISS_GROUPS} - see {@link #nearMissGroupsSql} for the exact statement shape and why
	 * it is a derived table, never a self-join. Returns an empty list on any failure, same discipline as
	 * {@link #sharedKeyValues}.
	 */
	private static List<String[]> nearMissGroups(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable, final String pEntityName,
		final List<String> pExactFields, final String pKeyField)
	{
		if (!CrossSystemMeasures.isQuotable(pEntityName) || !CrossSystemMeasures.isQuotable(pKeyField))
		{
			return new ArrayList<String[]>();
		}
		for (final String field : pExactFields)
		{
			if (!CrossSystemMeasures.isQuotable(field))
			{
				return new ArrayList<String[]>();
			}
		}
		final String sql = MatchPlanSimulationRunner.nearMissGroupsSql(pEntityName, pExactFields, pKeyField);
		final List<String[]> out = new ArrayList<String[]>();
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext() && out.size() < MatchPlanSimulationRunner.MAX_CANDIDATE_NEAR_MISS_GROUPS)
				{
					final Tuple tuple = it.next();
					final String[] values = new String[pExactFields.size()];
					boolean complete = true;
					for (int i = 0; i < pExactFields.size(); i++)
					{
						final Object value = tuple.get("G" + i);
						if (value == null)
						{
							complete = false;
							break;
						}
						values[i] = MatchPlanSimulationRunner.xsValue(MatchPlanSimulationRunner.fieldNode(pMasterTable, pExactFields.get(i)), value);
					}
					if (complete)
					{
						out.add(values);
					}
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final Throwable ex)
		{
			NonFatal.rethrowIfFatal(ex);
			LoggingCategory.getKernel().warn("[mdm-factory] Simulate on " + pEntityName + ": the near-miss probe failed - " + NonFatal.reason(ex, 400));
			return new ArrayList<String[]>();
		}
		return out;
	}

	/**
	 * Pure SQL-text builder for {@link #nearMissGroups} - unit-testable without EBX, same discipline as
	 * {@link #sharedKeyValuesSql}. Callers MUST check {@link CrossSystemMeasures#isQuotable} on every
	 * identifier first (this method does not guard). Same derived-table/{@code GROUP BY}/{@code HAVING} shape
	 * as {@link #sharedKeyValuesSql} - grouped by EVERY exact field together instead of one, with a SECOND
	 * {@code HAVING COUNT(DISTINCT ...) > 1} clause on {@code pKeyField} (a group must carry at least two
	 * different keys, or it holds no near-miss pair at all) - never a self-join, linear in row count, same
	 * reasoning as {@link CrossSystemMeasures}'s own class Javadoc. For {@code pExactFields = [country,
	 * cityKey]}, {@code pKeyField = nameKey}, {@code pEntityName = Company}:
	 * <pre>{@code
	 * SELECT a."country" AS G0, a."cityKey" AS G1 FROM "Company" a
	 * WHERE a."country" IS NOT NULL AND a."cityKey" IS NOT NULL AND a."nameKey" IS NOT NULL
	 * GROUP BY a."country", a."cityKey"
	 * HAVING COUNT(DISTINCT a."sourceSystem") > 1 AND COUNT(DISTINCT a."nameKey") > 1
	 * }</pre>
	 * Never run live this session (no EBX access) - see class Javadoc.
	 */
	static String nearMissGroupsSql(final String pEntityName, final List<String> pExactFields, final String pKeyField)
	{
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String sourceSystem = CrossSystemMeasures.quoteIdentifier("sourceSystem");
		final String keyField = CrossSystemMeasures.quoteIdentifier(pKeyField);
		final StringBuilder select = new StringBuilder();
		final StringBuilder where = new StringBuilder();
		final StringBuilder groupBy = new StringBuilder();
		for (int i = 0; i < pExactFields.size(); i++)
		{
			final String quoted = CrossSystemMeasures.quoteIdentifier(pExactFields.get(i));
			if (i > 0)
			{
				select.append(", ");
				groupBy.append(", ");
			}
			select.append("a.").append(quoted).append(" AS G").append(i);
			where.append("a.").append(quoted).append(" IS NOT NULL AND ");
			groupBy.append("a.").append(quoted);
		}
		// lot volume-4 (2026-09-28, task brief item 5): same FETCH FIRST bound as sharedKeyValuesSql's own note.
		return "SELECT " + select + " FROM " + table + " a WHERE " + where + "a." + keyField + " IS NOT NULL GROUP BY " + groupBy + " HAVING COUNT(DISTINCT a."
			+ sourceSystem + ") > 1 AND COUNT(DISTINCT a." + keyField + ") > 1 FETCH FIRST " + MatchPlanSimulationRunner.MAX_CANDIDATE_NEAR_MISS_GROUPS + " ROWS ONLY";
	}

	// ------------------------------------------------------------------------------------------- row lookups

	/** Every row of the master table whose {@code pFieldName} equals {@code pValue}, up to {@link #MAX_ROWS_SCANNED_PER_GROUP} - normally 2-3 rows (one per source system sharing the value), never the whole table. */
	private static List<Adaptation> rowsForFieldValue(final AdaptationTable pMasterTable, final String pFieldName, final String pValue)
	{
		return MatchPlanSimulationRunner.rowsForPredicate(pMasterTable, "./" + pFieldName + "=" + CatalogReader.quote(pValue));
	}

	/** Every row of the master table matching ALL of {@code pFields[i] = pValues[i]}, up to {@link #MAX_ROWS_SCANNED_PER_GROUP} - the multi-field counterpart of {@link #rowsForFieldValue}, used for a {@link #nearMissGroups} group. */
	private static List<Adaptation> rowsForFieldValues(final AdaptationTable pMasterTable, final List<String> pFields, final String[] pValues)
	{
		final StringBuilder predicate = new StringBuilder();
		for (int i = 0; i < pFields.size(); i++)
		{
			if (i > 0)
			{
				predicate.append(" and ");
			}
			predicate.append("./").append(pFields.get(i)).append("=").append(CatalogReader.quote(pValues[i]));
		}
		return MatchPlanSimulationRunner.rowsForPredicate(pMasterTable, predicate.toString());
	}

	private static List<Adaptation> rowsForPredicate(final AdaptationTable pMasterTable, final String pXpathPredicate)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = pMasterTable.createRequestResult(pXpathPredicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null && out.size() < MatchPlanSimulationRunner.MAX_ROWS_SCANNED_PER_GROUP; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	// --------------------------------------------------------------------------------- bulk row lookups (lot volume-4)

	/** {@code pList}, or an immutable empty list when it is {@code null} - a one-line null-safety helper shared by {@link #probeSharedKeyPairs}/{@link #probeNearMissPairs} once a bulk lookup's per-key/per-group result is missing (a value/group with genuinely zero matching rows). */
	private static List<Adaptation> orEmpty(final List<Adaptation> pList)
	{
		return pList == null ? java.util.Collections.<Adaptation>emptyList() : pList;
	}

	/** Same escaping rule as {@code ebx.CatalogReader#quote}'s XPath string literal (single-quote doubled) - the EBX SQL dialect reference states the identical convention for a SQL string literal, so one helper covers both. Package-private only to stay unit-testable; every caller in this file already checks {@link CrossSystemMeasures#isQuotable} on the surrounding identifier, never on the VALUE being quoted here (a value is data, not an identifier - doubling its quote is always safe). */
	static String sqlStringLiteral(final String pValue)
	{
		return "'" + (pValue == null ? "" : pValue.replace("'", "''")) + "'";
	}

	/**
	 * ONE SQL round trip fetching every row whose {@code pFieldName} is any of {@code pValues}, bucketed by that
	 * value - the bulk replacement for calling {@link #rowsForFieldValue} once per value (task brief item 5:
	 * "fetch the candidate rows with ONE SQL query ... instead of one XPath per value"). Each bucket is capped at
	 * {@link #MAX_ROWS_SCANNED_PER_GROUP}, same bound {@link #rowsForPredicate} already applies per value, so one
	 * very common value cannot dominate the whole read. Returns {@code null} (not merely an empty map) when the
	 * query itself could not be run at all (an unquotable identifier, a transport error, or - the one case
	 * expected to matter in practice - {@code pFieldName} not being a column the SQL engine will compare against
	 * a string literal, e.g. a numeric matching key; the pre-existing per-value XPath path already quoted every
	 * value string-style over the core repository's XPath engine, which tolerates it, but this codebase makes no
	 * claim the SQL engine does the same coercion) - callers fall back to the ORIGINAL per-value/per-group read
	 * in that case, never silently reporting "nothing found". Never itself exercised live this session (no EBX
	 * write access to set up a real shared-key scenario) - built by direct analogy to {@code
	 * ebx.MatchingResultsBuilder}'s own live-verified {@code IN (...)} query shape ({@code queryMergedMembers}),
	 * changing only the column being filtered and its value TYPE (a quoted string literal here, a bare numeric
	 * id list there).
	 */
	private static Map<String, List<Adaptation>> rowsByFieldValue(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable,
		final String pEntityName, final String pFieldName, final List<String> pValues)
	{
		if (pValues == null || pValues.isEmpty() || !CrossSystemMeasures.isQuotable(pEntityName) || !CrossSystemMeasures.isQuotable(pFieldName))
		{
			return null;
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final String field = CrossSystemMeasures.quoteIdentifier(pFieldName);
		final StringBuilder inList = new StringBuilder();
		for (final String value : pValues)
		{
			if (inList.length() > 0)
			{
				inList.append(", ");
			}
			inList.append(MatchPlanSimulationRunner.typedLiteral(pMasterTable, pFieldName, value));
		}
		// lot F1b: only the primary key is selected - the bucket of a row is the XS string of ITS value (readField), the same text the pivots' values are, whatever the SQL engine returns for the column
		final String sql = "SELECT c.\"$pk\" AS PK FROM " + table + " c WHERE c." + field + " IN (" + inList + ")";
		final Map<String, List<Adaptation>> out = new LinkedHashMap<String, List<Adaptation>>();
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Object pk = it.next().get("PK");
					if (pk == null)
					{
						continue;
					}
					final Adaptation row = pMasterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(String.valueOf(pk)));
					final String value = row == null ? null : MatchPlanSimulationRunner.readField(row, pFieldName);
					if (value == null)
					{
						continue;
					}
					List<Adaptation> bucket = out.get(value);
					if (bucket == null)
					{
						bucket = new ArrayList<Adaptation>();
						out.put(value, bucket);
					}
					if (bucket.size() < MatchPlanSimulationRunner.MAX_ROWS_SCANNED_PER_GROUP)
					{
						bucket.add(row);
					}
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final Throwable ex)
		{
			// lot F1b: an Error too (Calcite's generated code refusing a literal is an ExceptionInInitializerError) - null: the caller falls back or says the key was not measured
			NonFatal.rethrowIfFatal(ex);
			LoggingCategory.getKernel().warn("[mdm-factory] Simulate on " + pEntityName + ": the bulk read of " + pFieldName + " failed - " + NonFatal.reason(ex, 400));
			return null;
		}
		return out;
	}

	/** {@code pExactFields.size()} values joined with a separator no real field value is expected to contain - the Java-side bucket key {@link #rowsByFieldCombo} groups rows under, matching the SQL query's own {@code GROUP BY} column order one-for-one. Package-private for {@code MatchPlanSimulationRunnerTest}. */
	static String comboKey(final String[] pValues)
	{
		final StringBuilder key = new StringBuilder();
		for (int i = 0; i < pValues.length; i++)
		{
			if (i > 0)
			{
				key.append('\u0001');
			}
			key.append(pValues[i]);
		}
		return key.toString();
	}

	/**
	 * ONE SQL round trip fetching every row matching ANY of {@code pGroups} (each an exact-field-value
	 * combination), bucketed by {@link #comboKey} - the bulk replacement for calling {@link #rowsForFieldValues}
	 * once per group (task brief item 5, near-miss probe). The {@code WHERE} clause is an OR of ANDs, one AND
	 * per group - still a single statement, never a query per group. Same {@code null}-signals-total-failure /
	 * per-bucket cap discipline as {@link #rowsByFieldValue} (see that method's own Javadoc) - a caller falls
	 * back to {@link #rowsForFieldValues} per group when this returns {@code null}. Never exercised live this
	 * session - see {@link #rowsByFieldValue}'s own Javadoc for why.
	 */
	private static Map<String, List<Adaptation>> rowsByFieldCombo(final Session pSession, final Adaptation pMasterDataset, final AdaptationTable pMasterTable,
		final String pEntityName, final List<String> pExactFields, final List<String[]> pGroups)
	{
		if (pGroups == null || pGroups.isEmpty() || pExactFields == null || pExactFields.isEmpty() || !CrossSystemMeasures.isQuotable(pEntityName))
		{
			return null;
		}
		final String[] quotedFields = new String[pExactFields.size()];
		for (int i = 0; i < pExactFields.size(); i++)
		{
			if (!CrossSystemMeasures.isQuotable(pExactFields.get(i)))
			{
				return null;
			}
			quotedFields[i] = CrossSystemMeasures.quoteIdentifier(pExactFields.get(i));
		}
		final String table = CrossSystemMeasures.quoteIdentifier(pEntityName);
		final StringBuilder where = new StringBuilder();
		for (final String[] group : pGroups)
		{
			if (group.length != quotedFields.length)
			{
				continue; // defensive: nearMissGroups always builds one value per exact field - never expected
			}
			if (where.length() > 0)
			{
				where.append(" OR ");
			}
			where.append('(');
			for (int i = 0; i < quotedFields.length; i++)
			{
				if (i > 0)
				{
					where.append(" AND ");
				}
				where.append("c.").append(quotedFields[i]).append('=').append(MatchPlanSimulationRunner.typedLiteral(pMasterTable, pExactFields.get(i), group[i]));
			}
			where.append(')');
		}
		if (where.length() == 0)
		{
			return new LinkedHashMap<String, List<Adaptation>>();
		}
		final String sql = "SELECT c.\"$pk\" AS PK FROM " + table + " c WHERE " + where;
		final Map<String, List<Adaptation>> out = new LinkedHashMap<String, List<Adaptation>>();
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Object pk = it.next().get("PK");
					if (pk == null)
					{
						continue;
					}
					final Adaptation row = pMasterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(String.valueOf(pk)));
					if (row == null)
					{
						continue;
					}
					// lot F1b: the bucket is the XS strings of the row's own values (readField), the texts nearMissGroups produced
					final String[] values = new String[pExactFields.size()];
					boolean complete = true;
					for (int i = 0; i < values.length; i++)
					{
						values[i] = MatchPlanSimulationRunner.readField(row, pExactFields.get(i));
						complete &= values[i] != null;
					}
					if (!complete)
					{
						continue;
					}
					final String key = MatchPlanSimulationRunner.comboKey(values);
					List<Adaptation> bucket = out.get(key);
					if (bucket == null)
					{
						bucket = new ArrayList<Adaptation>();
						out.put(key, bucket);
					}
					if (bucket.size() < MatchPlanSimulationRunner.MAX_ROWS_SCANNED_PER_GROUP)
					{
						bucket.add(row);
					}
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final Throwable ex)
		{
			NonFatal.rethrowIfFatal(ex);
			LoggingCategory.getKernel().warn("[mdm-factory] Simulate on " + pEntityName + ": the near-miss bulk read failed - " + NonFatal.reason(ex, 400));
			return null;
		}
		return out;
	}

	private static Adaptation firstDifferentSystem(final List<Adaptation> pRows, final Adaptation pPivot)
	{
		final String pivotSystem = MatchPlanSimulationRunner.readField(pPivot, "sourceSystem");
		for (final Adaptation row : pRows)
		{
			if (row == pPivot)
			{
				continue;
			}
			final String system = MatchPlanSimulationRunner.readField(row, "sourceSystem");
			if (system != null && !system.equals(pivotSystem))
			{
				return row;
			}
		}
		return null;
	}

	/** The first pair within {@code pRows} (a {@link #nearMissGroups} group, all sharing the same exact fields) whose two records are of DIFFERENT source systems AND carry a DIFFERENT (case-insensitive, trimmed) value of {@code pKeyField} - {@code null} if no such pair exists among the rows actually fetched (bounded by {@link #MAX_ROWS_SCANNED_PER_GROUP}). */
	private static Adaptation[] firstNearMissPair(final List<Adaptation> pRows, final String pKeyField)
	{
		for (int i = 0; i < pRows.size(); i++)
		{
			final Adaptation a = pRows.get(i);
			final String systemA = MatchPlanSimulationRunner.readField(a, "sourceSystem");
			final String keyA = MatchPlanSimulationRunner.readField(a, pKeyField);
			if (systemA == null || keyA == null)
			{
				continue;
			}
			for (int j = i + 1; j < pRows.size(); j++)
			{
				final Adaptation b = pRows.get(j);
				final String systemB = MatchPlanSimulationRunner.readField(b, "sourceSystem");
				final String keyB = MatchPlanSimulationRunner.readField(b, pKeyField);
				if (systemB == null || keyB == null || systemA.equals(systemB) || keyA.trim().equalsIgnoreCase(keyB.trim()))
				{
					continue;
				}
				return new Adaptation[] { a, b };
			}
		}
		return null;
	}

	/** {@code true} and marks the pair the first time this unordered pair (by primary key) is seen, {@code false} (never marks again) the second time - the SAME de-duplication rule {@link #simulate}'s own pre-lot engine-candidate loop already applied, extended here so a probe never re-selects a pair an engine candidate (or an earlier probe) already covers. */
	private static boolean addPairKey(final Set<String> pSeenPairKeys, final Adaptation pA, final Adaptation pB)
	{
		final String keyA = pA.getOccurrencePrimaryKey().format();
		final String keyB = pB.getOccurrencePrimaryKey().format();
		final String pairKey = keyA.compareTo(keyB) <= 0 ? keyA + "|" + keyB : keyB + "|" + keyA;
		return pSeenPairKeys.add(pairKey);
	}

	// ------------------------------------------------------------------------------------------- key field lookup

	/**
	 * The entity's own identifying key field: a {@code FOLD_KEY}-derived {@code StagingAttribute} (ACCEPTED or
	 * APPLIED - {@link TechnicalKeyRule#isAcceptedLike}, the same "accepted-like" test {@code
	 * MatchPlanLlmSuggester#readDerivedFromByName} already applies to the same table) when one exists for this
	 * entity, else the plan's own first exact-tier field ({@link #firstExactTierField}). Null when neither
	 * exists (an entity with no identifier and no fuzzy-fold key) - both probe sets are then skipped, never
	 * guessed. Restores the pre-lot {@code pivotFieldName}/{@code foldKeyFieldName} pair (`git log -p` on this
	 * file, commit c08a5b1), with the ACCEPTED-or-APPLIED status guard {@code MatchPlanLlmSuggester} added
	 * afterwards - a still-{@code PROPOSED} or {@code REJECTED} key is not in the master table yet (or never
	 * will be), so probing on it would either find nothing or point at a column the master table does not
	 * actually carry.
	 */
	private static String keyFieldName(final Adaptation pFactory, final String pProjectId, final String pEntityName, final JsonNode pRequirements)
	{
		final String foldKey = MatchPlanSimulationRunner.foldKeyFieldName(pFactory, pProjectId, pEntityName);
		return foldKey != null ? foldKey : MatchPlanSimulationRunner.firstExactTierField(pRequirements);
	}

	private static String foldKeyFieldName(final Adaptation pFactory, final String pProjectId, final String pEntityName)
	{
		if (pProjectId == null || pEntityName == null)
		{
			return null;
		}
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY)
			.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./name=" + CatalogReader.quote(pEntityName));
		try
		{
			final Adaptation entity = entityRows.nextAdaptation();
			if (entity == null)
			{
				return null;
			}
			final String entityId = entity.getOccurrencePrimaryKey().format();
			final RequestResult attributeRows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE)
				.createRequestResult("./entity=" + CatalogReader.quote(entityId) + " and ./derivation=" + CatalogReader.quote("FOLD_KEY"));
			try
			{
				for (Adaptation attribute = attributeRows.nextAdaptation(); attribute != null; attribute = attributeRows.nextAdaptation())
				{
					if (TechnicalKeyRule.isAcceptedLike(attribute.getString(FactoryPaths.StagingAttribute.STATUS)))
					{
						return attribute.getString(FactoryPaths.StagingAttribute.NAME);
					}
				}
				return null;
			}
			finally
			{
				attributeRows.close();
			}
		}
		finally
		{
			entityRows.close();
		}
	}

	/**
	 * The ROOT node's own fields, the first whose {@code algorithm} is the exact one (a trailing {@code @exact}
	 * suffix, case-insensitive - see {@link #isExactAlgorithm}) - binary-decision-tree-screen lot, 2026-09-22:
	 * walks {@link DecisionTreePlan} directly instead of the retired cascade-only compatibility view. Scoped to
	 * the ROOT node alone, exactly as "the plan's first tier" already meant before the tree rework (a cascade's
	 * tier 1 IS its root) - task brief item 3, "'same key' classification uses the root node's field set as
	 * today".
	 */
	private static String firstExactTierField(final JsonNode pRequirements)
	{
		final DecisionTreePlan.Plan plan = DecisionTreePlan.readFrom(pRequirements);
		final DecisionTreePlan.Node root = plan.byId(plan.root);
		if (root == null)
		{
			return null;
		}
		for (final DecisionTreePlan.FieldRef field : root.fields)
		{
			if (MatchPlanSimulationRunner.isExactAlgorithm(field.algorithm))
			{
				if (field.field != null && !field.field.isEmpty())
				{
					return field.field;
				}
			}
		}
		return null;
	}

	/**
	 * Every field named with the exact algorithm ({@code ...@exact}, case-insensitive suffix match) anywhere
	 * across ALL of the plan's nodes (union, pre-order) - binary-decision-tree-screen lot, 2026-09-22:
	 * unchanged for a plain cascade (already scanned every tier before the tree rework), now walks a genuinely
	 * branching tree fully instead of answering an empty view. Independently parsed from the SAME JSON {@code
	 * review.MatchSimulationBlock#exactFieldNames} reads (that class' own package convention: {@code review}
	 * never imports {@code ebx}/{@code master}, and vice versa is avoided too - see {@code
	 * review.MatchPlanFieldEvidence}'s own Javadoc).
	 */
	private static List<String> exactFieldNames(final JsonNode pRequirements)
	{
		final LinkedHashSet<String> out = new LinkedHashSet<String>();
		for (final DecisionTreePlan.Node node : DecisionTreePlan.readFrom(pRequirements).nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if (MatchPlanSimulationRunner.isExactAlgorithm(field.algorithm))
				{
					if (field.field != null && !field.field.isEmpty())
					{
						out.add(field.field);
					}
				}
			}
		}
		return new ArrayList<String>(out);
	}

	private static boolean isExactAlgorithm(final String pAlgorithm)
	{
		final String trimmed = pAlgorithm == null ? "" : pAlgorithm.trim();
		final int at = trimmed.lastIndexOf('@');
		final String suffix = (at >= 0 ? trimmed.substring(at + 1) : trimmed).toLowerCase(java.util.Locale.ROOT);
		return "exact".equals(suffix);
	}

	/**
	 * Every field named with a full-text algorithm ({@code fuzzyFullText}/{@code phoneticFullText}/{@code
	 * exactFullText}, case-insensitive suffix match) anywhere across ALL of the plan's nodes - diagnostic-
	 * visibility lot, 2026-09-22. Independently parsed from the same JSON {@code
	 * review.MatchSimulationBlock#fullTextFieldNames} reads (this package's own convention: {@code ebx} never
	 * imports {@code review}, and vice versa - see that class' own Javadoc for what these algorithms are
	 * HYPOTHESIZED not to expose a per-field score for at all).
	 */
	static Set<String> fullTextFieldNames(final JsonNode pRequirements)
	{
		final LinkedHashSet<String> out = new LinkedHashSet<String>();
		for (final DecisionTreePlan.Node node : DecisionTreePlan.readFrom(pRequirements).nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if (MatchPlanSimulationRunner.isFullTextAlgorithm(field.algorithm))
				{
					if (field.field != null && !field.field.isEmpty())
					{
						out.add(field.field);
					}
				}
			}
		}
		return out;
	}

	private static boolean isFullTextAlgorithm(final String pAlgorithm)
	{
		final String trimmed = pAlgorithm == null ? "" : pAlgorithm.trim();
		final int at = trimmed.lastIndexOf('@');
		final String suffix = (at >= 0 ? trimmed.substring(at + 1) : trimmed).toLowerCase(java.util.Locale.ROOT);
		return "fuzzyfulltext".equals(suffix) || "phoneticfulltext".equals(suffix) || "exactfulltext".equals(suffix);
	}

	/** {@code true} when {@link #probeNearMissPairs} can even be BUILT for this plan (task brief item 3, 2026-09-22 diagnostic-visibility lot) - restates that method's own bail-out condition so {@code MatchPlanView.SimulationResult#nearMissApplicable} can be computed once, before the probe itself runs. */
	static boolean nearMissApplicable(final List<String> pExactFields, final String pKeyField)
	{
		return !pExactFields.isEmpty() && pKeyField != null && !pExactFields.contains(pKeyField);
	}

	// --------------------------------------------------------------------------------------------- score matching

	/**
	 * Copies {@code pEvaluation.fieldScores} into {@code pScores}, matching each raw {@code
	 * MameMatchingApi.FieldScore#field} name to one of {@code pComparedFields} by NORMALISING both sides first
	 * ({@link #matchScoreFieldToPlanField} - task brief item 2, 2026-09-22 diagnostic-visibility lot) instead
	 * of the pre-lot exact string match, which silently dropped a score whenever the add-on's own raw spelling
	 * did not happen to equal the plan's own field name. A raw name matching nothing is dropped, never guessed.
	 * <p>
	 * Also decides whether {@code pEvaluation.diagnostic} is worth adding to {@code pDiagnostics} (the click-wide
	 * aggregation - class Javadoc): always when the call itself {@link MameMatchingApi.TreeEvaluation#failed
	 * failed}; otherwise only when at least one of {@code pComparedFields} is neither an exact field ({@code
	 * pExactFields}) nor a full-text field ({@code pFullTextFields}) - i.e. a field the engine WAS expected to
	 * score - and NONE of them ended up with a matched entry in {@code pScores}. A full-text-only plan (every
	 * non-exact field named in {@code pFullTextFields}) therefore never adds a diagnostic here: the add-on not
	 * scoring a full-text field is treated as an expected characteristic, not a failure (HYPOTHESIS, see {@code
	 * review.MatchSimulationBlock#fullTextFieldNames}'s own Javadoc).
	 */
	static void mergeFieldScores(final Map<String, Double> pScores, final MameMatchingApi.TreeEvaluation pEvaluation, final List<String> pComparedFields,
		final List<String> pExactFields, final Set<String> pFullTextFields, final List<String> pDiagnostics)
	{
		for (final MameMatchingApi.FieldScore score : pEvaluation.fieldScores)
		{
			if (score.field == null)
			{
				continue;
			}
			final String matched = MatchPlanSimulationRunner.matchScoreFieldToPlanField(score.field, pComparedFields);
			if (matched != null)
			{
				pScores.put(matched, Double.valueOf(score.score));
			}
		}
		final List<String> scoredFieldsExpected = new ArrayList<String>();
		for (final String field : pComparedFields)
		{
			if (!pExactFields.contains(field) && !pFullTextFields.contains(field))
			{
				scoredFieldsExpected.add(field);
			}
		}
		final boolean anyExpectedFieldScored = MatchPlanSimulationRunner.anyMatch(pScores.keySet(), scoredFieldsExpected);
		final boolean worthFlagging = pEvaluation.failed || (!scoredFieldsExpected.isEmpty() && !anyExpectedFieldScored);
		if (worthFlagging && pEvaluation.diagnostic != null)
		{
			pDiagnostics.add(pEvaluation.diagnostic);
		}
	}

	private static boolean anyMatch(final Set<String> pHaystack, final List<String> pNeedles)
	{
		for (final String needle : pNeedles)
		{
			if (pHaystack.contains(needle))
			{
				return true;
			}
		}
		return false;
	}

	/** Strips a single leading {@code ./} or {@code /} - the two path-prefix spellings this codebase's own field paths carry; a bare field name is returned unchanged. {@code null} in, {@code null} out. */
	static String normalizeFieldPath(final String pRaw)
	{
		if (pRaw == null)
		{
			return null;
		}
		if (pRaw.startsWith("./"))
		{
			return pRaw.substring(2);
		}
		if (pRaw.startsWith("/"))
		{
			return pRaw.substring(1);
		}
		return pRaw;
	}

	/** The substring after the last {@code /} of an ALREADY-{@link #normalizeFieldPath normalized} path - task brief item 2: "compare the last path step too", so a raw add-on name like {@code root/Company/name} still matches a plan field simply called {@code name}. */
	static String lastPathStep(final String pNormalized)
	{
		if (pNormalized == null)
		{
			return null;
		}
		final int slash = pNormalized.lastIndexOf('/');
		return slash < 0 ? pNormalized : pNormalized.substring(slash + 1);
	}

	/**
	 * Matches a raw {@code MameMatchingApi.FieldScore#field} name to one of {@code pPlanFields} - task brief
	 * item 2, 2026-09-22 diagnostic-visibility lot: an exact, case-insensitive match after {@link
	 * #normalizeFieldPath normalizing} both sides, else a match on the {@link #lastPathStep last path step} of
	 * both sides. Returns the PLAN's own spelling (never the raw add-on one), so the result is a valid key into
	 * the same {@code comparedFields}-keyed maps every reader of {@code
	 * review.MatchPlanView.SimulationPair#fieldScores} already uses. {@code null} when nothing matches - the
	 * caller drops the score rather than inventing a field.
	 */
	static String matchScoreFieldToPlanField(final String pRawFieldName, final List<String> pPlanFields)
	{
		if (pRawFieldName == null)
		{
			return null;
		}
		final String normalizedRaw = MatchPlanSimulationRunner.normalizeFieldPath(pRawFieldName);
		for (final String planField : pPlanFields)
		{
			if (normalizedRaw.equalsIgnoreCase(MatchPlanSimulationRunner.normalizeFieldPath(planField)))
			{
				return planField;
			}
		}
		final String rawLastStep = MatchPlanSimulationRunner.lastPathStep(normalizedRaw);
		for (final String planField : pPlanFields)
		{
			if (rawLastStep.equalsIgnoreCase(MatchPlanSimulationRunner.lastPathStep(MatchPlanSimulationRunner.normalizeFieldPath(planField))))
			{
				return planField;
			}
		}
		return null;
	}

	/** {@code null} (never a zero-count instance) when {@code pDiagnostics} is empty; otherwise count + up to 3 distinct messages, first-seen order - task brief item 1. */
	static MatchPlanView.ScoreDiagnostics buildScoreDiagnostics(final List<String> pDiagnostics)
	{
		if (pDiagnostics.isEmpty())
		{
			return null;
		}
		final LinkedHashSet<String> distinct = new LinkedHashSet<String>();
		for (final String message : pDiagnostics)
		{
			if (distinct.size() < 3)
			{
				distinct.add(message);
			}
		}
		return new MatchPlanView.ScoreDiagnostics(pDiagnostics.size(), new ArrayList<String>(distinct));
	}

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

	// ------------------------------------------------------------------------------------------------------ json

	/**
	 * Field names in the order they first appear across ALL of the plan's nodes (union) - the SAME order
	 * {@code review.MatchPlanView#comparedFieldNames} computes from the SAME JSON, independently parsed here
	 * (see that class' own package - {@code review} never imports {@code ebx}/{@code master}, and vice versa
	 * is avoided by convention too). Binary-decision-tree-screen lot, 2026-09-22: walks {@link
	 * DecisionTreePlan} directly - unchanged for a plain cascade, now complete for a genuinely branching tree.
	 */
	private static List<String> comparedFieldNames(final JsonNode pRequirements)
	{
		final java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
		for (final DecisionTreePlan.Node node : DecisionTreePlan.readFrom(pRequirements).nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if (field.field != null && !field.field.isEmpty())
				{
					out.add(field.field);
				}
			}
		}
		return new ArrayList<String>(out);
	}

	private static JsonNode parseOrEmpty(final String pJson)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			return MatchPlanSimulationRunner.MAPPER.createObjectNode();
		}
		try
		{
			return MatchPlanSimulationRunner.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			return MatchPlanSimulationRunner.MAPPER.createObjectNode();
		}
	}

	// ---------------------------------------------------------------------------------------------------- display

	/** Up to the first two compared fields plus the record's own primary key, joined "name . city . key" style - never claims a value the record does not define (CLAUDE.md sec 9, Adaptation.get(path) on an undefined path). */
	private static String recordLabel(final Adaptation pRow, final List<String> pComparedFields)
	{
		final StringBuilder out = new StringBuilder();
		int shown = 0;
		for (final String field : pComparedFields)
		{
			if (shown >= 2)
			{
				break;
			}
			final String value = MatchPlanSimulationRunner.readField(pRow, field);
			if (value == null)
			{
				continue;
			}
			if (out.length() > 0)
			{
				out.append(" · ");
			}
			out.append(value);
			shown++;
		}
		if (out.length() > 0)
		{
			out.append(" · ");
		}
		out.append(pRow.getOccurrencePrimaryKey().format());
		return out.toString();
	}

	private static Map<String, String> fieldValues(final Adaptation pRow, final List<String> pComparedFields)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (final String field : pComparedFields)
		{
			final String value = MatchPlanSimulationRunner.readField(pRow, field);
			if (value != null)
			{
				out.put(field, value);
			}
		}
		return out;
	}

	/** {@code Boolean.TRUE}/{@code FALSE} when {@code pFieldName} is non-null and readable (case-insensitive, trimmed compare) on BOTH records, {@code null} otherwise (no such field, or either side blank) - never guessed. Feeds {@link MatchPlanView.SimulationPair#sameKey}. */
	private static Boolean rawFieldsEqual(final Adaptation pA, final Adaptation pB, final String pFieldName)
	{
		if (pFieldName == null)
		{
			return null;
		}
		final String valueA = MatchPlanSimulationRunner.readField(pA, pFieldName);
		final String valueB = MatchPlanSimulationRunner.readField(pB, pFieldName);
		if (valueA == null || valueB == null)
		{
			return null;
		}
		return Boolean.valueOf(valueA.trim().equalsIgnoreCase(valueB.trim()));
	}

	/** {@code Adaptation.get(Path)} throws on a path the table does not define (CLAUDE.md sec 9) - guarded rather than assumed, since a plan's field list is not necessarily every field of this particular table. */
	private static String readField(final Adaptation pRow, final String pFieldName)
	{
		final Path path = Path.parse("./" + pFieldName);
		final SchemaNode node = pRow.getSchemaNode() == null ? null : pRow.getSchemaNode().getNode(path);
		if (node == null)
		{
			return null;
		}
		try
		{
			// lot F1b: the XS string of the value (2026-01-20), never Date.toString - the values are compared, grouped and written into SQL literals
			return MatchPlanSimulationRunner.xsValue(node, pRow.get(path));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Lot F1b: the node of field {@code pField} in the occurrences of {@code pTable}, or null. */
	static SchemaNode fieldNode(final AdaptationTable pTable, final String pField)
	{
		try
		{
			final SchemaNode root = pTable.getTableOccurrenceRootNode();
			return root == null ? null : root.getNode(Path.parse("./" + pField));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Lot F1b: {@code pValue} as the field's XS string ({@code SchemaNode.formatToXsString}); a text is itself, null stays null, a value the node cannot format is {@code String.valueOf}. */
	static String xsValue(final SchemaNode pNode, final Object pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		if (pValue instanceof String)
		{
			return (String) pValue;
		}
		if (pNode != null)
		{
			try
			{
				final String formatted = pNode.formatToXsString(pValue);
				if (formatted != null)
				{
					return formatted;
				}
			}
			catch (final RuntimeException ex)
			{
				// falls back below
			}
		}
		return String.valueOf(pValue);
	}

	/** Lot F1b: the EBX SQL literal of the XS string {@code pXsValue} of field {@code pField} ({@link SqlLiterals}). */
	private static String typedLiteral(final AdaptationTable pTable, final String pField, final String pXsValue)
	{
		final SchemaNode node = MatchPlanSimulationRunner.fieldNode(pTable, pField);
		Class<?> javaClass = null;
		try
		{
			javaClass = node == null ? null : node.getOccurrencesJavaClass();
		}
		catch (final RuntimeException ex)
		{
			// a string literal, as before
		}
		return SqlLiterals.literal(javaClass, pXsValue);
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}
}
