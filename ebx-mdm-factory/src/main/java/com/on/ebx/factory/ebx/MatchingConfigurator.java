package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.RunTexts;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.master.BusinessObjectPlan;
import com.on.ebx.factory.master.BusinessObjectRewritePlan;
import com.on.ebx.factory.master.ChildEntityMatching;
import com.on.ebx.factory.master.DefaultMatchingPolicyGate;
import com.on.ebx.factory.master.MameAddonDecision;
import com.on.ebx.factory.master.MameAnswer;
import com.on.ebx.factory.master.MameDataModelResolution;
import com.on.ebx.factory.master.MasterFieldFilter;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.matching.CandidateFilterCheck;
import com.on.ebx.factory.matching.MatchResultsSnapshot;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.master.MatchPlanProfileReader;
import com.on.ebx.factory.master.MatchingConfigRewriteBuilder;
import com.on.ebx.factory.master.MatchingPolicyComparator;
import com.on.ebx.factory.master.MatchingPolicyTranslator;
import com.on.ebx.factory.master.MatchingTriggerMode;
import com.on.ebx.factory.master.MatchingRunScope;
import com.on.ebx.factory.master.MergeConfigPatchPlanner;
import com.on.ebx.factory.master.RelatedTableAlignment;
import com.on.ebx.factory.master.SourceKeyResolver;
import com.on.ebx.factory.master.TrustedSourceRanking;
import com.on.ebx.factory.matching.MatchStats;
import com.on.ebx.factory.matching.MatchingRunFailure;
import com.on.ebx.factory.quality.ValidValueDerivation;
import com.on.ebx.factory.staging.ScriptDerivationGate;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.onwbp.adaptation.RequestSortCriteria;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Drives the Match and Merge (`ebx-addon-mame`) configuration of a project's ACCEPTED {@code MatchPlan}s
 * (`docs/MATCHING.md` &sect;4, `PLAN.md` "Zone MDM" lot 4-5) - the ordered MCP call chain established there
 * for {@link #configure}, step by step, resumable, each entity's own {@code PipelineStep} (scope = the
 * entity name, unlike the project-scope steps of {@code MasterGenerator}/{@code MasterLoader}: matching is
 * configured per table, and one entity's failure must never block another's). {@link #run}/{@link #simulate}
 * use the add-on's public Java API instead ({@link MameMatchingApi}) - see that class' own Javadoc.
 * <p>
 * <b>{@link #configure}'s write tools were never exercised against the live instance this lot</b> -
 * {@code generate_matching_policy}, {@code generate_merge_policy}, {@code patch_matching_config},
 * {@code activate_matching} are all forbidden in the read-only session that wrote this class. The read-only
 * check ({@code get_matching_config}) WAS run live, repeatedly (`docs/MATCHING.md` &sect;2) - including on
 * the real, freshly-generated {@code Companies_Master}/{@code Master}/{@code /root/Company} table, where it
 * answered {@code TABLE_NOT_FOUND}: the master model had never declared {@code
 * <osd:addon>ebx-addon-mame</osd:addon>}, a hard precondition {@code activate_matching}'s own tool
 * description states explicitly. **{@code ebx.MasterGenerator} now declares that add-on at generation time**
 * (`docs/MATCHING.md` &sect;6, coordinator review of 1cdb8bd) - {@link #configureOne} still checks
 * {@code get_matching_config} first and still refuses cleanly on {@code TABLE_NOT_FOUND}.
 * <p>
 * <b>Never passes the master model's NAME as {@code dataModel}</b> (coordinator review, 2026-09-21,
 * `docs/MATCHING.md` &sect;2.2 "dataModel" entry - a real bug, measured live: {@code
 * get_matching_config(table="/root/Company")} answered {@code CONFIG_NOT_FOUND} twelve seconds after the
 * add-on was declared and republished, while the SAME call with {@code dataModel="Companies_Master"}
 * answered {@code TABLE_NOT_FOUND} at the SAME instant - a plain publication name is never the
 * {@code SchemaLocation} string the tool's own contract calls for). Every call in {@link #configureOne} now
 * omits {@code dataModel} by default; {@link MameDataModelResolution} decides, from the first
 * {@code get_matching_config} answer, whether to keep omitting it, use a resolved
 * {@code AMBIGUOUS_TABLE} candidate for the rest of the pass, or refuse outright - see that class' own
 * Javadoc, including why its {@code AMBIGUOUS_TABLE} parsing is a best effort never yet observed live.
 * A live {@code TABLE_NOT_FOUND} now gets a message that reflects whether the add-on is ACTUALLY declared
 * ({@link #tableNotFoundHint}, reusing {@code MasterGenerator}'s own read-only {@code list_addons} check) -
 * it used to always suggest {@code master/generate} even when that would change nothing.
 * <p>
 * <b>Refuses to overwrite a matching configuration it did not create</b> - the same philosophy as
 * {@code master.MasterResetEligibility}. Ownership evidence is simply "this project's own {@code
 * MATCHING_CONFIG} step for this entity has already completed" ({@code PipelineSteps.isDone}, the resumable-
 * skip convention every other generator here already uses): if {@code get_matching_config} finds a REAL
 * configuration on the table and this project's own step has never completed for that entity, this class
 * refuses outright rather than guess whose configuration it is - reconfiguring/patching an existing config
 * this generator did not itself create is not attempted by this lot at all (a narrower promise than "detect
 * and reuse ours, refuse everyone else's" would be, deliberately: nothing about telling the two apart from a
 * bare {@code MameConfig} read was established this session).
 * <p>
 * <b>2026-09-21, 22:16 - the first real {@code configure} call, real refusal, fixed.</b> {@code
 * generate_matching_policy} answered {@code INVALID_PARAMS} ("Decision tree references a field not declared
 * on the policy") - {@link MatchingPolicyTranslator} only ever declared {@code matchingPolicy.fields} from
 * {@code clusteringFields}, never the union with every field the decision tree itself compares (see that
 * class' own Javadoc for the fix and the leading-slash field-path fix found the same minute). {@link
 * #configureOne} now also runs {@link #checkFieldCapabilities} right after building the policy and before
 * sending it - a second, independent pre-flight check, this one against the LIVE {@code
 * matching_config_schema(table=...)} answer, that refuses the same class of mistake (a field the table
 * cannot pre-cluster on) before a single write call is made, whenever that live answer is actually available
 * (never exercised this session - write tools are forbidden either way).
 * <p>
 * <b>2026-09-22, 08:58 - the first real {@code configure} call that got past every generate/patch step,
 * real refusal, fixed.</b> {@code POST /projects/Companies/matching/configure} ran {@code
 * generate_matching_policy} and {@code generate_merge_policy} for real (policy {@code Company}, a 2-tier
 * tree over {@code /name}/{@code /city}/{@code /country}, merge policy {@code Company} linked - all
 * verified afterwards with {@code get_matching_config}), then REFUSED at {@link #configureOne}'s own
 * validate step with a plainly {@code valid:true} answer:
 * {@code {"data":{"messages":[],"table":"/root/Company","valid":true},"success":true}}. The validate parsing
 * read {@code valid} at the ROOT of the answer, while every match-merge answer - including {@code
 * activate_matching}'s own {@code state} a few lines below, which already read {@code data.state} with a
 * root fallback - wraps its real payload under {@code data}. Fixed by {@link MameAnswer#field}, one pure
 * helper now shared by every answer-shape read in this class (validate's {@code valid}/{@code messages},
 * activate's {@code state}, and {@code get_matching_config}'s {@code success}/{@code code}) - see that
 * class' own Javadoc for the full measured shape. A soft miss on the validate call itself ({@code
 * success:false}, e.g. a stale {@code CONFIG_NOT_FOUND} between the patch and the validate) used to read as
 * plain "invalid" by the same accident; it is now reported as its own distinct refusal.
 * <p>
 * <b>Idempotence corollary of the same bug, same review:</b> the failed attempt above had already run
 * {@code generate_matching_policy}/{@code generate_merge_policy}/{@code patch_matching_config} to
 * completion before failing at validate - so a plain retry finds a REAL, already-{@code success:true}
 * configuration on the table while this project's own {@code MATCHING_CONFIG} step for the entity is still
 * {@code FAILED}, not {@code DONE}. Before this fix {@link #configureOne} could not tell that config apart
 * from a stranger's and refused outright ({@code "refusing to overwrite a configuration this generator did
 * not create"}), forever, since nothing ever marks the step {@code DONE} to unblock the next retry - a
 * genuine deadlock, not just the validate-shape bug's runtime symptom. {@link #configureOne} now checks its
 * OWN most recently recorded step detail for this exact project/entity ({@code lastConfigStepDetail} at the
 * time, widened into {@link #allConfigStepDetails} by lot R8 below - see the paragraph further down):
 * only when THAT detail shows this project's own last attempt already completed generate+generate+patch
 * successfully does it resume straight at validate/activate ({@link #priorAttemptCompletedGenerationAndLink})
 * - never by re-parsing the live server's own found-config shape, which has never been observed in enough
 * detail to key on safely (unlike {@code MameDataModelResolution}'s {@code AMBIGUOUS_TABLE} candidates, this
 * class does not try). A found config with no matching recorded attempt (a stranger's, or this project's own
 * from before this fix shipped, whose detail JSON does not carry these keys) still hits the original
 * unconditional refusal - resuming is only ever "trust our own bookkeeping," never "guess from their
 * answer." Never exercised live (write tools are forbidden in this session either way).
 * <p>
 * <b>2026-09-22 23:50 - compare-then-rewrite: {@code configure} on an ALREADY-CONFIGURED entity no longer
 * blindly resumes (`docs/MATCHING.md` &sect;19).</b> The gap, read in this method after a live sequence the
 * same evening: a reviewer accepted a NEW {@code MatchPlan} for an entity that had already been configured
 * and activated once (a genuinely {@code DONE} {@code MATCHING_CONFIG} step) - {@link #configure}'s own loop
 * used to skip such an entity outright ({@code skippedAlreadyDone}), so {@link #configureOne} was never even
 * called again for it, and the add-on kept serving the OLD policy forever while the new plan sat marked
 * {@code APPLIED} by nothing. Fixed on two levels: {@link #configure}'s loop no longer skips an entity solely
 * because its {@code MATCHING_CONFIG} step is {@code DONE} - it always calls {@link #configureOne}, which now
 * decides {@link ConfigureDecision#GENERATE_FRESH}/{@link ConfigureDecision#RESUME_SAME}/{@link
 * ConfigureDecision#REWRITE}/{@link ConfigureDecision#REFUSE} itself ({@link #decideConfigureAction}) by
 * actually translating the ACCEPTED plan ({@code MatchingPolicyTranslator}, exactly as the fresh path always
 * did) and structurally comparing it ({@code master.MatchingPolicyComparator}) against whatever {@code
 * get_matching_config} shows is REALLY stored - decision tree, candidate-gate fields, the linked {@code
 * mergePolicyCode}, the merge policy's own chains/survivors, and (lot 2, cascade, 2026-09-23, `docs/MATCHING.md`
 * &sect;12.4/&sect;22) each {@code relatedTables[]} entry's own {@code alignForeignKeys} value. Ownership is unchanged in
 * spirit from the paragraph above (a config this project never finished configuring, ever, and that is not a
 * leftover preview, is still refused outright) - the one thing that changed is that ANY past {@code DONE} step
 * now qualifies as ownership, not only an immediately-prior {@code FAILED} attempt's own recorded tool calls.
 * When the stored shape differs, {@code master.MatchingConfigRewriteBuilder} rewrites the entity's own {@code
 * matchingPolicies[]}/{@code mergePolicies[]} entries IN PLACE via {@code apply_matching_config} (never a
 * second {@code generate_matching_policy}/{@code generate_merge_policy} for the same {@code processCode}, and
 * never touching {@code trustedSources}/{@code tableSettings} - the survivorship route stays the only writer
 * of those), preserving every operational field the stored entry already carries ({@code active}, {@code
 * matchingOnCreation}/{@code matchingOnUpdate}, {@code executePurge}, {@code showMatchType}, {@code
 * matchAgainstMergedRecords}, any {@code workflowOn*} key on the matching policy; {@code mode}, {@code
 * useForManualMerge}, {@code applyPermission} on the merge policy) and dropping any leftover {@code __preview}
 * entry from both lists in the same write. {@code relatedTables} is the ONE exception to "preserved untouched":
 * every entry's own {@code name}/{@code path} still survive verbatim, but an entry whose {@code path} is a
 * PROMOTED CHILD entity of this one ({@code MasterModel.Result#childTablePaths} - a table holding a foreign key
 * to this entity) has its {@code alignForeignKeys} rewritten to {@link RelatedTableAlignment#WANTED} ({@code NONE} since lot MT, 2026-10-04 - the add-on's own cascade fails at volume; the master load re-points the children) ({@code
 * master.RelatedTableAlignment}) - the product owner's decision (`docs/MATCHING.md` &sect;12.4/&sect;22, replacing &sect;12.4's
 * earlier "leave MANUAL, deferred" note): a merge must cascade to re-point every child record's own foreign key
 * to the surviving golden record. The SAME alignment runs after {@code GENERATE_FRESH} too (a follow-up
 * {@code get_matching_config} + {@code apply_matching_config}, since the add-on's own {@code
 * generate_merge_policy} answer does not echo the merge policy it just wrote) - never only on a REWRITE, so a
 * first-time configuration cascades exactly like a later re-sync would. {@code activate_matching} is then only
 * called on a REWRITE when {@code get_matching_activation_status} does not already report {@code ENABLED} -
 * re-activating an already-running configuration is a heavier operation than a plain policy edit (CLAUDE.md
 * &sect;8's own "Calcite" trigger-reattach note) and the task brief is explicit about the gate. Never exercised
 * live this session (write tools forbidden either way, same as every write path in this class) - proved by
 * {@code MatchingPolicyComparatorTest}/{@code MatchingConfigRewriteBuilderTest}/{@code
 * MatchingConfiguratorTest} against hand-built fixtures, including the exact live pair (`docs/MATCHING.md`
 * &sect;19) that motivated this lot.
 * <p>
 * <b>2026-09-23 13:30 - a second live Simulate pass on Contact, three measurements fixed (`docs/MATCHING.md`
 * &sect;26).</b>
 * <ol>
 * <li>{@code generate_merge_policy} refused outright ({@code INVALID_PARAMS}, "Survivor field '/emailValid'
 * does not resolve to a field of table /root/Contact") because the field had only ever reached the STAGING
 * zone ({@code staging/generate} + {@code staging/load} ran; {@code master/generate} did not) - {@link
 * #configureOne}/{@link #applySurvivorship} now filter every desired merge policy's own {@code survivors[]}
 * through {@link MasterFieldFilter#dropSurvivorsNotOnMaster} against the project's real {@link #findMasterTable
 * master table} (new this fix, factored out of {@link #run}/{@link #simulate}'s own inline lookup) before it
 * ever reaches a write call, recording a human note under {@code detail.survivorsDroppedNotOnMaster} for
 * whichever field(s) get dropped.</li>
 * <li>The SAME {@code get_matching_config} read-back showed {@code tableSettings.defaultMatchingPolicy:
 * "Contact__preview"} - the add-on makes the FIRST matching policy ever generated on a table its default,
 * preview policies included (contradicting {@code ebx.MatchingPreviewPolicy}'s own PRIOR Javadoc claim that
 * nothing in that class ever touches the field - true as to touching, false as to the outcome). {@link
 * #configureOne} now corrects this after both {@code GENERATE_FRESH} and {@code REWRITE} via {@link
 * #ensureDefaultMatchingPolicy} ({@link DefaultMatchingPolicyGate#decide} is the pure rule; hard failure, never
 * best-effort, on a write that does not verifiably stick) - {@code ebx.MatchingPreviewPolicy}'s own {@code
 * ensureFresh}/{@code remove} apply the identical correction, see that class' own Javadoc.</li>
 * <li>The re-read config also showed a jarowinkler field's own {@code params[]} carrying an extra {@code
 * jaroWinklerThreshold: 0.7} this class never sends - an algorithm default the add-on fills in on its own, not
 * a real difference. Fixed in {@code master.MatchingPolicyComparator#sameParams} (asymmetric now: a STORED
 * -only param is ignored, a DESIRED-only or value-mismatched one is still a real difference) - see that
 * method's own Javadoc.</li>
 * </ol>
 * None of the three was replayed live this session (no EBX access - same constraint as every other write path
 * in this class); fixed strictly from the add-on's own measured answers, unit-tested against hand-built
 * fixtures ({@code MasterFieldFilterTest}, {@code DefaultMatchingPolicyGateTest}, three new cases in {@code
 * MatchingPolicyComparatorTest}).
 * <p>
 * <b>2026-09-23 14:20 - "resolve {@code dataModel} once from a read, thread the result through every following
 * call" turned out unsound (`docs/MATCHING.md` &sect;28).</b> A "Simulate" click on the PROPOSED {@code Contact}
 * plan failed: {@code apply_matching_config} answered {@code AMBIGUOUS_TABLE} on the exact same table whose own
 * {@code get_matching_config} - called WITHOUT {@code dataModel} moments earlier in the same pass - had just
 * answered {@code success:true}. Only ONE of the table's two candidate data models actually carries a
 * configuration at this point, so a READ stops looking ambiguous once it can serve an answer from the one that
 * has it; a WRITE is not disambiguated by that at all. Every call in this class (and in {@code
 * ebx.MatchingPreviewPolicy}/{@code ebx.MatchingActivationGuard}/{@code ebx.ProjectDeleter}/{@code
 * ebx.MatchPlanLlmSuggester}) now goes through {@link MameCalls#call}, which sends {@code dataModel = }{@link
 * MameDataModelResolution#publicationUrn(String)} UNCONDITIONALLY on every call for this project's master table
 * - never omitted, never resolved-once-and-reused - falling back to {@link MameDataModelResolution}'s own
 * {@code AMBIGUOUS_TABLE} candidate parsing only as a per-call retry-once safety net (kept, unchanged, for the
 * rare case the guessed URN itself still comes back ambiguous). {@link #configureOne}'s own opening block used
 * to call {@code get_matching_config} TWICE when a candidate needed resolving (once unscoped, once rescoped) -
 * it is now always exactly once, since the right {@code dataModel} is already known before the first call is
 * made. Never replayed live this session (no EBX access) - {@link MameCalls#decide}, the pure part of the fix,
 * is unit-tested against the exact live payload above (`MameCallsTest`).
 * <p>
 * <b>2026-09-27, lot R8 - ownership deadlocked forever after one failed configure attempt (`docs/MATCHING.md`
 * &sect;39, branch {@code fix/config-ownership-own-publication}).</b> Live, project PersVol7, {@code /root/Personne}:
 * step 563 (02:55:41) ran {@code generate_matching_policy}/{@code generate_merge_policy}/the merge-policy link/
 * {@code activate_matching} all successfully, then failed LATER in the same pass (lot R7's own relink-last/pause
 * machinery, &sect;38). The pre-R8 {@code lastConfigStepDetail} only ever inspected the MOST RECENTLY recorded {@code
 * MATCHING_CONFIG} step's own detail - after the first refusal, every retry's own step records nothing but the
 * refusal itself (never a generation attempt), so {@link #priorAttemptCompletedGenerationAndLink} on that LATEST
 * detail read {@code false} forever, and the table's real, self-written configuration stayed permanently
 * unrecognised as this project's own. Fixed two ways, {@code ownsExistingConfig} now {@code true} whenever ANY of
 * three signals holds (recorded per-entity as {@code ownershipRule} in the step detail, never silently):
 * <ol>
 * <li>{@code isDoneStep} - unchanged from &sect;19.3.</li>
 * <li>{@link #anyPriorAttemptCompletedGenerationAndLink} - the SAME {@link #priorAttemptCompletedGenerationAndLink}
 * check, now applied to EVERY past {@code MATCHING_CONFIG} step detail this project/entity ever recorded ({@link
 * #allConfigStepDetails}), not only the latest one - a refusal-only step never itself satisfies the check (it
 * records no tool calls at all) but no longer hides an earlier attempt's own real success.</li>
 * <li>{@link #ownsByDataModel} - NEW. The master publication {@link #configureOne} is already calling
 * {@code get_matching_config} against is {@code pModelName} - this project's OWN master publication, generated
 * exclusively for it by {@code MasterGenerator}/{@code MasterNames} and shared with no other project. A
 * configuration {@code get_matching_config} actually FOUND while scoped to that exact {@code dataModel} (read back
 * off the mutated call arguments after {@link MameCalls#call} returns - {@code dataModel} is sent unconditionally,
 * &sect;28 above) is, by construction, a configuration of THIS project's own publication - never a stranger's -
 * regardless of what this project's own {@code PipelineStep} bookkeeping does or does not remember. This is the
 * signal that actually breaks the PersVol7 deadlock; the other two are kept as before, and as a defensive
 * fallback for the rare case a retry (&sect;28's own {@code AMBIGUOUS_TABLE} safety net) resolves to a candidate
 * that is NOT the exact publication URN.</li>
 * </ol>
 * See `docs/MATCHING.md` &sect;39 for the live sequence and the unit tests (`MatchingConfiguratorTest`) covering
 * every combination.
 */
public final class MatchingConfigurator
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String STEP_CONFIG = "MATCHING_CONFIG";
	private static final String STEP_RUN = "MATCHING_RUN";
	private static final String STEP_SURVIVORSHIP = "MATCHING_SURVIVORSHIP";
	/** {@link #refreshGoldenKeys}'s own step - deliberately separate from {@link #STEP_RUN}: no matching process is launched, only {@code Xref.goldenKey} is re-read/rewritten. */
	private static final String STEP_REFRESH_GOLDEN_KEYS = "GOLDEN_KEY_REFRESH";
	/** The master table's own {@code sourceSystem} field, record-relative with the leading slash {@code MatchingPolicyTranslator#toFieldPath} would add - {@code set_trusted_sources}' own {@code sourceField} argument. */
	private static final String SOURCE_SYSTEM_FIELD_PATH = "/sourceSystem";
	/**
	 * Default bound for {@link #run}'s poll of an asynchronous matching process - see {@link MameMatchingApi}'s
	 * own Javadoc for why this is bounded, never an unbounded blocking wait.
	 * <p>
	 * <b>Raised from 30s to 15 minutes, lot R12 (2026-09-27, docs/MATCHING.md &sect;41, live evidence B).</b>
	 * Measured live twice the same evening (projects {@code ret}/{@code PersVol7}): a real matching run on a
	 * modest master table routinely took past the old 30s bound (34s on {@code Personne}), which used to make
	 * {@link #runInternal} move on to the NEXT entity while the add-on's own process still held the master
	 * dataspace - the next entity's own {@code matchRecords} call then refused outright ("invalid state - this
	 * service cannot be executed because the dataspace is locked", PipelineStep 677). With a 600000ms bound the
	 * SAME sequence ran end to end (Personne 9s, Adresse 3s, both {@code DONE}) with no lock refusal at all. 15
	 * minutes is a generous ceiling for a table this factory ever builds (CLAUDE.md's own "EBX tient le volume"
	 * note notwithstanding - a genuinely huge table's matching run is expected to still be {@code RUNNING} when
	 * this deadline passes, exactly as {@link MameMatchingApi}'s own class Javadoc already documents: the caller
	 * reports it as such and a later {@code POST matching/run} picks the {@code Xref.goldenKey} refresh up once
	 * the add-on's own background process has actually finished). {@code pMaxWaitMs} remains a real parameter
	 * (the REST route's own {@code maxWaitMs} query parameter, {@code FactoryResource}) - this is only the
	 * default an unspecified call falls back to.
	 */
	public static final long DEFAULT_MAX_WAIT_MS = 900000;

	/**
	 * Volume-guards lot (2026-09-28, task item 3, "a matching run must be awaited to its terminal state"): the
	 * wait used INSTEAD of {@code pMaxWaitMs} whenever a {@link FactoryJobs.Handle} is present (the call runs
	 * inside {@link MatchingRunRun}'s background job, not a synchronous REST/workflow call) - see {@link
	 * #runInternal}. Before this lot, {@link MatchingRunRun#HANDLER} passed the same bounded {@link
	 * #DEFAULT_MAX_WAIT_MS} a synchronous caller gets, so a run past 15 minutes was reported "launched, still
	 * RUNNING" with the step ended {@code ok=true} and {@code GoldenKeyRefresher.refresh} skipped, while the
	 * job itself had already returned - the NEXT queued entity (or a completely different job) could then start
	 * against a table whose matching process was still mutating it. A background job has no reason to give up on
	 * a wall clock at all: it already checks {@link FactoryJobs.Handle#cancelled()} on every poll ({@link
	 * MameMatchingApi#launchScoped}), which is both how a person's Stop button reaches it AND (since the
	 * volume-guards lot's {@code FactoryJobs.Handle#touch()}) how its OWN inactivity deadline is kept alive while
	 * it is genuinely still polling. Not {@code Long.MAX_VALUE}: {@link MameMatchingApi#launchScoped} computes
	 * its own deadline as {@code System.currentTimeMillis() + pMaxWaitMs}, which would overflow into the past and
	 * make the very first poll look like a timeout. Ten years is unreachable by any real run and never overflows
	 * a {@code long} of epoch milliseconds.
	 */
	static final long UNBOUNDED_WAIT_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(3650);

	private MatchingConfigurator()
	{
	}

	// ============================================================================================== matching/configure

	public static ObjectNode configure(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		return MatchingConfigurator.configure(pSession, pFactory, pProjectCode, null);
	}

	/**
	 * Same as {@link #configure(Session, Adaptation, String)}, checked for a stop between two entities (lot C3,
	 * 2026-09-24, {@code ebx.MatchingConfigureRun}): {@code pHandle} may be null (every synchronous caller -
	 * {@code FactoryResource}, the wizard's old direct call, the REST route) and is then never consulted, exactly
	 * as before this lot. An entity already being configured when the stop is asked always finishes (the MCP call
	 * chain inside {@link #configureOne} has no cancellation point of its own); only entities not yet started are
	 * skipped, listed under {@code stoppedBefore}.
	 */
	public static ObjectNode configure(final Session pSession, final Adaptation pFactory, final String pProjectCode, final FactoryJobs.Handle pHandle)
		throws OperationException
	{
		final Adaptation project = MatchingConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		MemberEntitiesBackfill.run(pSession, pFactory, projectId);

		final Map<String, String> entityIdByName = new HashMap<String, String>();
		final List<StagingModel.EntityInfo> stagingEntities = MatchingConfigurator.readEntities(pFactory, projectId, entityIdByName);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(stagingEntities);
		final String modelName = MasterNames.modelName(pProjectCode);

		final List<String> configured = new ArrayList<String>();
		final List<String> skippedAlreadyDone = new ArrayList<String>();
		final List<String> refused = new ArrayList<String>();
		final List<String> notAccepted = new ArrayList<String>();
		// reject/repropose lot (2026-09-22): an entity a reviewer explicitly ruled out ("No matching for this
		// entity", MatchPlan.status=REJECTED) is not merely "not accepted yet" - it never will be, unless the
		// reviewer changes their mind (Propose another / Accept, both still offered on a REJECTED line). Listed
		// separately, with the reviewer's own comment, so the Cockpit can say "Matching: none, by decision"
		// instead of the generic "no matching plan"/"plan is REJECTED" wording notAccepted still uses for a
		// merely-not-yet-decided entity.
		final List<String> excludedByDecision = new ArrayList<String>();
		// lot C3 (2026-09-24): entities not yet started when a stop was asked - see this method's own overload Javadoc.
		final List<String> stoppedBefore = new ArrayList<String>();
		// lot M2 (2026-10-17, docs/MATCHING.md sect;56): a child entity (an address, a phone, a purchase of a person) follows its parent. It is neither "not accepted" nor a
		// failure: listed under followsParent. A plan of its own that an earlier pass already configured stays exactly as it is (childKeptAsIs, never deleted silently).
		final Map<String, List<String>> parentsByChild = ChildEntityMatching.parentsByChild(masterModel);
		final List<String> followsParent = new ArrayList<String>();
		final List<String> childKeptAsIs = new ArrayList<String>();
		// a Simulate preview policy that could not be removed after its entity was configured: reported, never silent (the configure itself succeeded)
		final List<String> previewsLeft = new ArrayList<String>();
		// lot M4 (2026-10-18, docs/MATCHING.md sect;59): the inert policies written on the member tables of a business object, one sentence per member
		final List<String> memberPolicies = new ArrayList<String>();
		// lot M6 (2026-10-18, docs/MATCHING.md sect;61): the notes of a business-object validation that threw and was replaced by a validation of the main table alone
		// (coded strings: English text + bundle marker, MatchingValidation.note)
		final List<String> validationNotes = new ArrayList<String>();

		for (final String entityName : masterModel.builtEntities)
		{
			if (pHandle != null && pHandle.cancelled())
			{
				stoppedBefore.add(entityName);
				continue;
			}
			final String entityId = entityIdByName.get(entityName);
			final Adaptation plan = MatchingConfigurator.findMatchPlan(pFactory, entityId);
			final String planStatus = plan == null ? null : plan.getString(FactoryPaths.MatchPlan.STATUS);
			if ("REJECTED".equals(planStatus))
			{
				final String comment = plan.getString(FactoryPaths.MatchPlan.REVIEW_COMMENT);
				excludedByDecision.add(entityName + (comment == null || comment.isEmpty() ? "" : ": " + comment));
				continue;
			}
			final List<String> childParents = parentsByChild.get(entityName);
			if (childParents != null)
			{
				if ("APPLIED".equals(planStatus))
				{
					childKeptAsIs.add(ChildEntityMatching.keptAsIsNote(entityName, childParents));
					continue;
				}
				if (plan == null || !MatchingConfigurator.isConfigurablePlanStatus(planStatus))
				{
					followsParent.add(ChildEntityMatching.followsText(entityName, childParents));
					continue;
				}
				// an ACCEPTED plan of its own that was never configured: asked for explicitly, configured only if it passes the two guards of configureOne
			}
			// lot M4 (2026-10-03, defect 2, Vol1m): an APPLIED plan (configured by an earlier pass) is "accepted-like" exactly as
			// everywhere else in this module ({@link TechnicalKeyRule#isAcceptedLike}). It used to be listed here as "not accepted"
			// ("0 configured, 0 refused, 2 not accepted"), so editing its requirements after the first configure changed
			// nothing until someone put the plan back to ACCEPTED by hand: configureOne's own compare (RESUME_SAME / REWRITE)
			// is what decides whether anything must be rewritten.
			if (plan == null || !MatchingConfigurator.isConfigurablePlanStatus(planStatus))
			{
				notAccepted.add(entityName + (plan == null ? ": no matching plan" : ": plan is " + planStatus));
				continue;
			}
			// compare-then-rewrite lot (2026-09-22 23:50, docs/MATCHING.md sec 19 "the gap"): an entity whose
			// MATCHING_CONFIG step already completed is NO LONGER skipped here - configureOne itself now reads
			// the live get_matching_config answer, compares it against the CURRENT accepted plan's translation,
			// and decides GENERATE_FRESH/RESUME_SAME/REWRITE/REFUSE on its own (see that method's own Javadoc).
			// Skipping here unconditionally, as before this lot, meant an entity whose accepted plan changed
			// AFTER its first successful configure could never be re-synced by any later matching/configure call
			// at all - exactly the live bug this lot fixes (a reviewer re-accepts a different plan; the add-on
			// keeps serving the OLD one forever).
			try
			{
				final MatchingConfigurator.ConfigureDecision decision = MatchingConfigurator.configureOne(pSession, pFactory, projectId, pProjectCode, entityName,
					modelName, plan, masterModel, previewsLeft, memberPolicies, validationNotes);
				if (decision == MatchingConfigurator.ConfigureDecision.RESUME_SAME)
				{
					skippedAlreadyDone.add(entityName + ": stored policy already matches the accepted plan");
				}
				else if (decision == MatchingConfigurator.ConfigureDecision.REWRITE)
				{
					configured.add(entityName + " (policy rewritten in place - the accepted plan changed since the last successful configure)");
				}
				else
				{
					configured.add(entityName);
				}
			}
			catch (final OperationException ex)
			{
				refused.add(entityName + ": " + ex.getMessage());
			}
			catch (final McpException ex)
			{
				refused.add(entityName + ": MCP call failed: " + ex.getMessage());
			}
		}

		final ObjectNode out = MatchingConfigurator.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		MatchingConfigurator.putStrings(out, "configured", configured);
		MatchingConfigurator.putStrings(out, "skippedAlreadyDone", skippedAlreadyDone);
		MatchingConfigurator.putStrings(out, "refused", refused);
		MatchingConfigurator.putStrings(out, "notAccepted", notAccepted);
		MatchingConfigurator.putStrings(out, "excludedByDecision", excludedByDecision);
		MatchingConfigurator.putStrings(out, "stoppedBefore", stoppedBefore);
		MatchingConfigurator.putStrings(out, "followsParent", followsParent);
		MatchingConfigurator.putStrings(out, "childKeptAsIs", childKeptAsIs);
		MatchingConfigurator.putStrings(out, "previewsLeft", previewsLeft);
		MatchingConfigurator.putStrings(out, "memberPolicies", memberPolicies);
		MatchingConfigurator.putStrings(out, "validationNotes", validationNotes);
		return out;
	}

	/**
	 * Lot M4 (2026-10-03, defect 2): the plan statuses {@link #configure} configures - {@code ACCEPTED} (first configure) and {@code
	 * APPLIED} (configured by an earlier pass; {@code configureOne} then decides RESUME_SAME or REWRITE from what the add-on holds).
	 * Pure; {@code PROPOSED}, {@code REJECTED}, {@code SUPERSEDED} and a missing status stay "not accepted".
	 */
	static boolean isConfigurablePlanStatus(final String pPlanStatus)
	{
		return TechnicalKeyRule.isAcceptedLike(pPlanStatus);
	}

	private static MatchingConfigurator.ConfigureDecision configureOne(final Session pSession, final Adaptation pFactory, final String pProjectId,
		final String pProjectCode, final String pEntityName, final String pModelName, final Adaptation pPlan, final MasterModel.Result pMasterModel,
		final List<String> pPreviewsLeft, final List<String> pMemberPolicies, final List<String> pValidationNotes) throws OperationException, McpException
	{
		final String tablePath = MasterModel.tablePath(pEntityName);
		// cascade lot (2026-09-23, docs/MATCHING.md sec 12.4/22): every promoted CHILD entity's own table path of
		// pEntityName - the only tables whose alignForeignKeys is ever set to RelatedTableAlignment.WANTED (NONE, lot MT)
		// (master.RelatedTableAlignment; never used to add/remove a relatedTables[] entry, only to pick which
		// ALREADY-PRESENT one qualifies).
		final Set<String> childTablePaths = pMasterModel.childTablePaths(pEntityName);
		final List<String> relatedTablesAligned = new ArrayList<String>();
		final String stepId = PipelineSteps.begin(pSession, pFactory, pProjectId, MatchingConfigurator.STEP_CONFIG, pEntityName);
		final ObjectNode detail = MatchingConfigurator.MAPPER.createObjectNode();
		// lot R7 (2026-09-27, error-handling fix): declared OUTSIDE the try so a failure-handling catch below can
		// still reach them even when the failure happens before this method's own local variables would otherwise be
		// in scope. currentPhase names whatever this method is doing right now, in place of the single hardcoded
		// "matching policy translation failed" every RuntimeException used to be reported as.
		String currentPhase = "resolving the matching configuration and the accepted plan";
		McpClient client = null;
		try
		{
			// lot M2 (2026-10-17, docs/MATCHING.md sect;56): the plan of a CHILD entity is for suspects only (a pair of children is never merged on its own) - refused before
			// anything is written, with the reason, never configured silently
			final List<String> childParents = ChildEntityMatching.parentsOf(pMasterModel, pEntityName);
			final String matchLeafRefusal = ChildEntityMatching.matchLeafRefusal(pEntityName, childParents, pPlan.getString(FactoryPaths.MatchPlan.REQUIREMENTS));
			if (matchLeafRefusal != null)
			{
				detail.put("childPlanRefused", matchLeafRefusal);
				throw EngineRefusal.coded("conflict.refused", matchLeafRefusal);
			}
			client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);

			// layer 1 (docs/MATCHING.md sec 28, measured live 2026-09-23 14:20, class Javadoc): dataModel is always
			// urn:ebx:publication:<pModelName> from this very first call on - never omitted, never resolved once
			// from a read and reused - so this method needs only ONE get_matching_config call here, not two.
			// MameCalls.call still falls back to MameDataModelResolution's own AMBIGUOUS_TABLE parsing (layer 2) as
			// a per-call retry-once safety net, and throws OperationException outright if that too cannot settle
			// on exactly one candidate.
			final String dataModel = MameDataModelResolution.publicationUrn(pModelName);
			final ObjectNode getArgs = MatchingConfigurator.MAPPER.createObjectNode();
			getArgs.put("table", tablePath);
			final McpClient.ToolResult getResult = MameCalls.call(client, "get_matching_config", getArgs, pModelName);
			detail.set("existingConfig", MatchingConfigurator.toolDetail(getArgs, getResult));
			// lot R8 (2026-09-27, docs/MATCHING.md sec 39): MameCalls.call always writes its FINAL dataModel back onto
			// getArgs (layer 1, unconditional; layer 2's retry-once overwrites it again on a genuine AMBIGUOUS_TABLE) -
			// read back here, after the call, never guessed, so ownsByDataModel below compares against whatever
			// dataModel this exact successful call actually used.
			final String requestedDataModel = getArgs.path("dataModel").asText(null);

			final boolean found = MameAnswer.success(getResult.json, false);
			final String code = MameAnswer.code(getResult.json);
			// simulation-preview-policy lot (2026-09-22): a config "found" here is not necessarily a stranger's OR
			// this project's own prior attempt either - it may be nothing but a leftover INACTIVE preview policy
			// (<Entity>__preview) a "Simulate on N pivots" click wrote earlier (ebx.MatchingPreviewPolicy#ensure).
			// Without this check, the very first real accept of an entity that was ever simulated pre-acceptance
			// would hit the ownership refusal below forever (nothing about a preview policy ever marks this
			// project's own MATCHING_CONFIG step DONE) - so a preview-only config is treated exactly like
			// CONFIG_NOT_FOUND, never like a stranger's configuration.
			final boolean previewOnly = found && MatchingPreviewPolicy.isPreviewOnly(getResult.json, pEntityName);

			// compare-then-rewrite lot (2026-09-22 23:50, docs/MATCHING.md sec 19): a real, owned configuration
			// found here is no longer always a RESUME - the accepted MatchPlan itself may have changed since the
			// last successful configure (a reviewer rejected/re-proposed, or simply accepted a different plan),
			// and configure() no longer skips an entity whose MATCHING_CONFIG step is already DONE before ever
			// reaching this method (see configure()'s own comment) - so this is the FIRST point that can tell
			// "nothing changed" from "the stored policy is stale" apart, by actually comparing. Ownership itself
			// is unchanged in spirit from the pre-2026-09-22 rule (class Javadoc, "Refuses to overwrite..."): a
			// config this project never finished configuring (no completed MATCHING_CONFIG step for this entity,
			// EVER - not just "the last attempt") and that is not a leftover preview is still someone else's, and
			// this method still refuses outright rather than guess.
			//
			// lot R8 (2026-09-27, docs/MATCHING.md sec 39): ownership is now established by ANY of three signals,
			// never only the LAST recorded MATCHING_CONFIG step - a table this project's own attempt genuinely
			// configured (generate+generate+link all succeeded) but that later failed for an UNRELATED reason
			// (class Javadoc, PersVol7/Personne) used to be refused forever, because every retry after the first
			// failure records nothing but the refusal itself, and only the LATEST step detail was ever inspected.
			final boolean isDoneStep = PipelineSteps.isDone(pFactory, pProjectId, MatchingConfigurator.STEP_CONFIG, pEntityName);
			final List<String> priorDetails = MatchingConfigurator.allConfigStepDetails(pFactory, pProjectId, pEntityName);
			final boolean priorAttemptOwnership = MatchingConfigurator.anyPriorAttemptCompletedGenerationAndLink(priorDetails);
			final boolean ownPublicationOwnership = MatchingConfigurator.ownsByDataModel(requestedDataModel, dataModel);
			final MatchingConfigurator.OwnershipRule ownershipRule = MatchingConfigurator
				.determineOwnership(isDoneStep, priorAttemptOwnership, ownPublicationOwnership);
			final boolean ownsExistingConfig = ownershipRule != MatchingConfigurator.OwnershipRule.NONE;
			detail.put("ownsExistingConfig", ownsExistingConfig);
			detail.put("ownershipRule", ownershipRule.name());

			if (found && !previewOnly && !ownsExistingConfig)
			{
				final String hint = "TABLE_NOT_FOUND".equals(code) ? MatchingConfigurator.tableNotFoundHint(pSession, pFactory, pModelName, pProjectCode, detail) : "";
				throw EngineRefusal.coded("conflict.matchingConfigExists", "A matching configuration already exists for " + tablePath
					+ " and this project's own MATCHING_CONFIG step for " + pEntityName + " has never completed (not even once): refusing to overwrite a "
					+ "configuration this generator did not create (same philosophy as master.MasterResetEligibility)." + hint, tablePath, pEntityName);
			}
			if (!found && !"CONFIG_NOT_FOUND".equals(code))
			{
				final String hint = "TABLE_NOT_FOUND".equals(code) ? MatchingConfigurator.tableNotFoundHint(pSession, pFactory, pModelName, pProjectCode, detail) : "";
				throw EngineRefusal.coded("conflict.matchingConfigState", "get_matching_config for " + tablePath + " answered " + code + ", not CONFIG_NOT_FOUND: refusing to proceed." + hint, tablePath, code);
			}
			if (previewOnly)
			{
				detail.put("previewPolicyPresent", "An inactive preview policy (" + MatchingPreviewPolicy.policyCode(pEntityName) + ") already exists on " + tablePath
					+ " from an earlier Simulate click - treated as CONFIG_NOT_FOUND, proceeding to generate the REAL policy; the preview is removed once this "
					+ "succeeds (MatchingPreviewPolicy.remove).");
			}

			// The desired shape is built unconditionally now (needed by every remaining branch: a fresh table
			// needs it to generate, an already-configured one needs it to compare) - unlike before this lot, where
			// it was only ever built inside the "not resuming" (fresh) path.
			final JsonNode requirements = MatchingConfigurator.parseRequirements(pPlan.getString(FactoryPaths.MatchPlan.REQUIREMENTS));
			final boolean autoMerge = Boolean.TRUE.equals(pPlan.get(FactoryPaths.MatchPlan.AUTO_MERGE));
			final String entityId = pPlan.getString(FactoryPaths.MatchPlan.ENTITY);
			// native relation fields (lot W13, 2026-10-12, docs/MATCHING.md sect;33): a plan whose decision tree names a child-entity ("via") field is
			// translated into a policy field with businessObjectField = /root/<Child>/<field>; pMasterModel carries the child table of every promoted
			// child (a child's own FK targeting this entity, keyed by FieldRef.via - the same child entity name).
			currentPhase = "translating the accepted plan into a matching policy";
			final MatchingPolicyTranslator.TranslationResult translation = MatchingPolicyTranslator.translate(pEntityName, requirements, autoMerge,
				MatchingConfigurator.relationChildTablePaths(pMasterModel, pEntityName));
			final ObjectNode desiredMatchingPolicy = translation.matchingPolicy;
			if (translation.hasRelationFields())
			{
				detail.put("relationFieldCount", translation.relationFieldWeights.size());
				// pre-clustering weight written for each relation field (requirements.relationClusteringWeights, default 0 - docs/MATCHING.md sect;48)
				final ObjectNode relationWeights = detail.putObject("relationPreclusteringWeights");
				for (final Map.Entry<String, Integer> relationWeight : translation.relationFieldWeights.entrySet())
				{
					relationWeights.put(relationWeight.getKey(), relationWeight.getValue().intValue());
				}
			}
			// which business object (if any) the table belongs to: the relation fields of a plan are only writable through one, and a table that
			// is a member of one is activated through it (activate_matching is refused on a member)
			currentPhase = "finding the business object of the table";
			final MameBusinessObjects.Scope boScope = MameBusinessObjects.resolve(client, pModelName, pMasterModel, pEntityName, translation.hasRelationFields(), detail);
			if (boScope.route == BusinessObjectPlan.Route.REFUSE_MISSING_BUSINESS_OBJECT)
			{
				throw MatchingConfigurator.missingBusinessObjectRefusal(pEntityName, tablePath, pModelName, translation.relationFieldWeights.keySet());
			}
			// lot M4 (2026-10-18, docs/MATCHING.md sect;59): the object must be BOUND before anything else is written on its main table - a configuration made first by a tool that has no
			// businessObject argument (set_trusted_sources) cannot be repaired by any tool and refuses every field of a member table afterwards
			if (boScope.inBusinessObject() && !BusinessObjectPlan.isMember(boScope.businessObject, tablePath))
			{
				currentPhase = "checking that the business object of the table is bound";
				MameBusinessObjects.checkBinding(client, pModelName, boScope.businessObject, translation.hasRelationFields(), detail);
			}
			// a MEMBER of a business object (not its main table) refuses matchingOnCreation/matchingOnUpdate (Vol1mR step 1433, 2026-10-12): the object drives its
			// matching, so the policy is sent and compared without them, and the execution mode is neither read nor patched below
			final boolean memberOfBusinessObject = BusinessObjectPlan.isMember(boScope.businessObject, tablePath);
			// lot M2: the merging phase of the add-on fails on a MEMBER table whatever its tree says (docs/MATCHING.md sect;56) - a child's own plan is not configured there
			final String memberRunRefusal = ChildEntityMatching.memberRunRefusal(pEntityName, childParents, memberOfBusinessObject, boScope.businessObject);
			if (memberRunRefusal != null)
			{
				detail.put("childPlanRefused", memberRunRefusal);
				throw EngineRefusal.coded("conflict.refused", memberRunRefusal);
			}
			if (BusinessObjectPlan.dropExecutionModesOfMember(desiredMatchingPolicy, memberOfBusinessObject))
			{
				detail.put("executionModesOmitted", "business object member of " + boScope.businessObject);
			}
			// the related-table alignment is not done on a business-object table (the object's child relationship replaces it): do not compare it either
			final Set<String> comparisonChildTablePaths = BusinessObjectPlan.comparisonChildTablePaths(boScope.inBusinessObject(), childTablePaths);
			// CANDIDATE_SCORE_SHARE (lot M16, 2026-10-18, docs/MATCHING.md sect;68; it replaces the dominance note of lot R16): the share of the total pre-clustering weight each key set of the tree
			// holds under the weights the policy carries (the plan's node weights and the model's clusteringWeights - the configurator raises none), as facts: applied=false, the sets under the
			// add-on's 30 % and the weights that would lift them. Recorded when the tree has a key set, so a live run's step detail shows what the add-on was given without re-deriving it.
			if (translation.candidateScoreShare != null)
			{
				detail.set("candidateScoreShare", translation.candidateScoreShare);
			}
			// 2026-10-12 (add-on build 6.3.0.5-RC3): a decision-tree field no longer accepts DO_NOTHING - translated here, at payload time, so
			// the stored plan keeps its meaning (class Javadoc of MatchingPolicyTranslator#translateTreeNullHandling); every branch below
			// (generate, compare, rewrite) then works on the SAME translated policy.
			currentPhase = "reading the decision-tree null-value handlings the live add-on accepts";
			final List<String> nullHandlingTranslated = MatchingConfigurator.translateTreeNullHandlingForLiveSchema(client, desiredMatchingPolicy, pEntityName, detail);
			currentPhase = "checking field capabilities against the live match-merge schema";
			MatchingConfigurator.checkFieldCapabilities(client, tablePath, pModelName, desiredMatchingPolicy, detail);
			// 2026-09-23 fix (live PipelineStep 108, main 117e193): the REWRITE branch (docs/MATCHING.md sec 19.5)
			// must build its desired merge policy with the SAME structurally-detected source-key field names as
			// the fresh path always has - this single call, shared by every branch below (GENERATE_FRESH/
			// RESUME_SAME/REWRITE all read the same desiredMergePolicy object, never a second, differently
			// -parameterised call), is the one and only place sourceKeyFieldNames is threaded into
			// MatchingPolicyTranslator.buildMergePolicy in this method. Logged into detail (not just used
			// silently) so a live run where the entity's own source-key attribute is unexpectedly missing from
			// the rewritten survivors - a `/supplierId` "no merge" rule going missing, as observed live - is
			// diagnosable from the recorded PipelineStep detail alone, without re-deriving the query from source:
			// an empty list here for an entity known to have a business-key attribute points straight at
			// readSourceKeyFieldNames/MatchPlan.entity, never at the comparator or the rewrite builder.
			//
			// 2026-09-23 SECOND fix, same live run: the root cause WAS readSourceKeyFieldNames after all - for a
			// CANONICAL entity (Company, members Supplier/Account) the businessKey flag lives on each MEMBER's
			// own attribute, never on Company's own attribute of the same name (measured live: Company.supplierId
			// itself carries businessKey:false). readSourceKeyFieldNames now follows memberEntities and maps each
			// member's own source key onto this entity's same-named attribute (master.SourceKeyResolver, shared by
			// this method, applySurvivorship and MatchingPreviewPolicy - docs/MATCHING.md sec 19.10) - the notes it
			// returns (how, or whether, each member key was mapped) are recorded here too.
			final List<String> sourceKeyFieldNamesNotes = new ArrayList<String>();
			final List<String> sourceKeyFieldNames = MatchingConfigurator.readSourceKeyFieldNames(pFactory, entityId, sourceKeyFieldNamesNotes);
			detail.put("sourceKeyFieldNames", sourceKeyFieldNames.toString());
			if (!sourceKeyFieldNamesNotes.isEmpty())
			{
				MatchingConfigurator.putStrings(detail, "sourceKeyFieldNamesNotes", sourceKeyFieldNamesNotes);
			}
			// lot 1 (2026-09-23, quality-informed survivorship): the entity's own VALID_VALUE derived fields - a
			// FOURTH structural list, threaded the same way sourceKeyFieldNames itself was added (this method's
			// own comment above, "the one and only place ... is threaded into buildMergePolicy in this method").
			final List<String> validValueFieldNames = MatchingConfigurator.readValidValueFieldNames(pFactory, entityId);
			detail.put("validValueFieldNames", validValueFieldNames.toString());
			final ObjectNode desiredMergePolicy = MatchingPolicyTranslator.buildMergePolicy(pEntityName, requirements, sourceKeyFieldNames, validValueFieldNames);
			// master-field-filter lot (2026-09-23 13:30, live PipelineStep 159, docs/MATCHING.md sec 26): a survivor
			// field accepted in staging (a *Valid derived field, a source-key attribute, or an LLM-proposed business
			// field) may not be on the MASTER table yet if master/generate never ran again after it was accepted -
			// generate_merge_policy refuses the WHOLE call outright when that happens (measured live: /emailValid,
			// INVALID_PARAMS "does not resolve to a field of table /root/Contact"). Filtered here, once, right after
			// the merge policy is built and before it ever reaches ANY match-merge write call below (GENERATE_FRESH's
			// own generate_merge_policy, or REWRITE's apply_matching_config) - MasterFieldFilter is pure, the schema
			// lookup itself (CLAUDE.md sec 9: getTableOccurrenceRootNode().getNode(...), never Adaptation.get, which
			// throws on an unknown path) lives in the predicate built from findMasterTable's own AdaptationTable.
			final AdaptationTable masterTableForSurvivors = MatchingConfigurator.findMasterTable(pFactory, pProjectCode, pEntityName);
			final Predicate<String> fieldExistsOnMaster = pFieldName -> masterTableForSurvivors.getTableOccurrenceRootNode().getNode(Path.parse("./" + pFieldName)) != null;
			final List<String> survivorsDroppedNotOnMaster = MasterFieldFilter.dropSurvivorsNotOnMaster(desiredMergePolicy, tablePath, fieldExistsOnMaster);
			if (!survivorsDroppedNotOnMaster.isEmpty())
			{
				MatchingConfigurator.putStrings(detail, "survivorsDroppedNotOnMaster", survivorsDroppedNotOnMaster);
			}

			final boolean generatingFresh = !found || previewOnly;
			final MatchingConfigurator.ConfigureDecision decision;
			MatchingPolicyComparator.Diff diff = null;
			if (generatingFresh)
			{
				decision = MatchingConfigurator.ConfigureDecision.GENERATE_FRESH;
			}
			else
			{
				// found, real (not preview), owned - compare the LIVE stored entry (this exact entity's own, never
				// a stranger sharing the table) against the freshly-translated ACCEPTED plan before deciding
				// anything (docs/MATCHING.md sec 19 "the gap").
				final JsonNode existingMatchingPolicies = MameAnswer.field(getResult.json, "matchingPolicies");
				final JsonNode existingMergePolicies = MameAnswer.field(getResult.json, "mergePolicies");
				final JsonNode storedMatchingPolicy = MatchingPolicyComparator.findPolicyEntry(existingMatchingPolicies, "processCode", pEntityName);
				final String storedMergePolicyCode = storedMatchingPolicy.path("mergePolicyCode").asText(pEntityName);
				final JsonNode storedMergePolicy = MatchingPolicyComparator.findPolicyEntry(existingMergePolicies, "code", storedMergePolicyCode);
				diff = MatchingPolicyComparator.compare(storedMatchingPolicy, desiredMatchingPolicy, pEntityName, storedMergePolicy, desiredMergePolicy,
					comparisonChildTablePaths);
				detail.put("comparisonResult", diff.summary());
				decision = MatchingConfigurator.decideConfigureAction(found, previewOnly, ownsExistingConfig, diff.same());
			}

			boolean skipActivateBecauseAlreadyEnabled = false;
			boolean rewriteSkippedIdentical = false;
			if (decision == MatchingConfigurator.ConfigureDecision.REWRITE)
			{
				// lot M1 (2026-10-17): the rewritten merge policy names the source of the goldens it creates (autoCreateNewGoldenSource): it must be registered
				currentPhase = "registering the source of the golden records";
				MatchingConfigurator.ensureGoldenSourceRegistered(client, tablePath, dataModel, pModelName, getResult.json, detail);
			}
			currentPhase = "writing the matching/merge policy via MCP (" + decision + ")";
			switch (decision)
			{
				case GENERATE_FRESH:
				{
					if (previewOnly)
					{
						// master integration (2026-10-17, live): a preview written before this fix is the table's manual-merge policy and makes the real
						// generate_merge_policy refuse ("already the manual-merge policy") - remove the preview FIRST (it is removed after success anyway)
						try
						{
							final ObjectNode earlyRemoval = MatchingPreviewPolicy.remove(pSession, pFactory, pProjectCode, pEntityName);
							// lot M2 (2026-10-17): "true" used to be written whatever remove answered - a preview LEFT on a business object table read as removed
							detail.set("previewRemovedBeforeGenerate", MatchingConfigurator.MAPPER.getNodeFactory().booleanNode(MatchingPreviewPolicy.isGone(earlyRemoval)));
							if (MatchingPreviewPolicy.wasLeft(earlyRemoval))
							{
								detail.put("previewLeftBeforeGenerate", earlyRemoval.path("leftReason").asText(""));
							}
						}
						catch (final OperationException | RuntimeException ex)
						{
							detail.put("previewRemovedBeforeGenerate", "failed: " + ex.getMessage());
						}
					}
					// lot M4 (2026-10-18, docs/MATCHING.md sect;59): on a table of a business object set_trusted_sources runs AFTER the first generate_matching_policy (which carries the
					// businessObject and so binds the object and creates the configuration of every table of it); run first, it creates a configuration with no business object that no tool
					// repairs. It still runs before generate_merge_policy (the golden source it registers must exist when the merge policy names it).
					final McpClient writeClient = client;
					final MatchingConfigurator.WriteStep setTrustedSources = () -> {
						// trusted-sources lot, 2026-09-22: set_trusted_sources BEFORE generate_merge_policy (docs/MATCHING.md
						// "Survivorship and trusted sources") - the merge policy's own "most trusted source" functions read
						// this table-level configuration at MERGE time, not at generation time, but nothing says the order
						// is safe the other way round and the task brief is explicit about it. 2026-09-23 correction
						// (docs/MATCHING.md sec 28): an earlier task brief had this call OMIT dataModel unconditionally
						// ("never taken at all") - re-checked against the match-merge server's own "Common params" section
						// (dataModel is generic across the whole server, the ONE stated exception being
						// profile_table_for_matching), which this call is not, so it now goes through MameCalls like every
						// other call in this method.
						final List<TrustedSourceRanking.Source> trustedSources = TrustedSourceRanking.order(MatchingConfigurator.readTrustedSources(pFactory, pProjectId));
						final ObjectNode trustedSourcesArgs = MatchingConfigurator.MAPPER.createObjectNode();
						trustedSourcesArgs.put("table", tablePath);
						// survivorship lot (2026-09-22, docs/MATCHING.md sec 15): a field-level "most trusted source" override
						// the language model proposed - requirements is already in scope (parsed just above for
						// buildMatchingPolicy) and MatchingPolicyTranslator.extractFieldTrustedSourceOverrides is pure/stateless,
						// so this is always the FULL desired list, never a delta (TrustedSourceRanking's own contract).
						// Run-30 self-check lot (2026-09-22): trustedSources (just read/ordered above) is passed through too, so
						// the translator can refuse/clean any code this plan's own requirements name that is not one of this
						// project's real trusted sources - never a phantom reaching set_trusted_sources, even for a plan stored
						// before that validation existed (that method's own Javadoc).
						final List<String> trustedSourceOverrideNotes = new ArrayList<String>();
						trustedSourcesArgs.set("trustedSources", TrustedSourceRanking.buildTrustedSourcesPayload(MatchingConfigurator.SOURCE_SYSTEM_FIELD_PATH, trustedSources,
							MatchingPolicyTranslator.extractFieldTrustedSourceOverrides(requirements, trustedSources, trustedSourceOverrideNotes)));
						if (!trustedSourceOverrideNotes.isEmpty())
						{
							MatchingConfigurator.putStrings(detail, "trustedSourceOverrideNotes", trustedSourceOverrideNotes);
						}
						final McpClient.ToolResult trustedSourcesResult = MameCalls.call(writeClient, "set_trusted_sources", trustedSourcesArgs, pModelName);
						detail.set("setTrustedSources", MatchingConfigurator.toolDetail(trustedSourcesArgs, trustedSourcesResult));
						MatchingConfigurator.requireSuccess(trustedSourcesResult, "set_trusted_sources");
					};
					if (!boScope.inBusinessObject())
					{
						setTrustedSources.run();
					}
					MatchingConfigurator.writePoliciesThroughGenerate(client, tablePath, dataModel, pModelName, pEntityName, desiredMatchingPolicy, desiredMergePolicy, boScope, detail,
						boScope.inBusinessObject() ? setTrustedSources : null);

					// cascade lot (2026-09-23, docs/MATCHING.md sec 12.4/22): generate_merge_policy just added every
					// inverse-FK related table to the merge policy itself, MANUAL by default (CLAUDE.md sec 8) -
					// align every PROMOTED CHILD entity's own entry to RelatedTableAlignment.WANTED (NONE since lot MT) now, in the SAME configure pass,
					// so a fresh configuration cascades exactly like a REWRITE would (master.RelatedTableAlignment).
					// A second get_matching_config is required here: generate_merge_policy's own answer does not
					// echo the merge policy it just wrote (same restraint MatchingPreviewPolicy's own generate
					// calls already observe) - never assumed, always re-read. Best-effort: an unreadable answer
					// here never fails an otherwise-successful GENERATE_FRESH configure.
					final ObjectNode postGenerateArgs = MatchingConfigurator.MAPPER.createObjectNode();
					postGenerateArgs.put("table", tablePath);
					MatchingConfigurator.putDataModel(postGenerateArgs, dataModel);
					final McpClient.ToolResult postGenerateResult = MameCalls.call(client, "get_matching_config", postGenerateArgs, pModelName);
					detail.set("getConfigAfterGenerate", MatchingConfigurator.toolDetail(postGenerateArgs, postGenerateResult));
					if (!BusinessObjectPlan.alignsRelatedTables(boScope.inBusinessObject()))
					{
						// a business-object table: the object's child relationship replaces the alignment (generate_merge_policy refuses relatedTables there)
						detail.set("alignRelatedTables", MatchingConfigurator.alignmentSkippedDetail());
					}
					else if (MameAnswer.success(postGenerateResult.json, false))
					{
						final JsonNode freshMatchingPolicies = MameAnswer.field(postGenerateResult.json, "matchingPolicies");
						final JsonNode freshMergePolicies = MameAnswer.field(postGenerateResult.json, "mergePolicies");
						final JsonNode freshMergePolicy = MatchingPolicyComparator.findPolicyEntry(freshMergePolicies, "code", pEntityName);
						final List<String> toAlign = MatchingConfigurator.alignRelatedTables(client, tablePath, dataModel, pModelName, pEntityName, childTablePaths,
							desiredMatchingPolicy, desiredMergePolicy, freshMatchingPolicies, freshMergePolicies, freshMergePolicy, detail, "alignRelatedTables");
						relatedTablesAligned.addAll(toAlign);
					}
					else
					{
						detail.put("alignRelatedTablesSkipped",
							"get_matching_config after generation did not answer success - related-table cascade alignment skipped, best-effort.");
					}
					// default-matching-policy lot (2026-09-23 13:30, docs/MATCHING.md sec 26): generate_matching_policy
					// just made this table's own FIRST-EVER matching policy its tableSettings.defaultMatchingPolicy -
					// on a genuinely fresh table that is exactly pEntityName already (harmless), but on a table whose
					// only prior policy was a Simulate-click preview (previewOnly branch), the default still names the
					// PREVIEW right now. Correct it to the real code, hard failure on a write that does not stick -
					// unlike the best-effort cascade-alignment check just above, a wrong default is not cosmetic.
					MatchingConfigurator.ensureDefaultMatchingPolicy(client, tablePath, pModelName, MatchingPreviewPolicy.policyCode(pEntityName), pEntityName,
						postGenerateResult.json, detail);
					break;
				}
				case RESUME_SAME:
				{
					detail.put("resumedFromExistingConfig", "The stored matching/merge policy for " + pEntityName + " already matches the accepted plan ("
						+ diff.summary() + ") - resuming at validate_matching_config without touching apply_matching_config/generate_*/patch_matching_config.");
					break;
				}
				case REWRITE:
				{
					// docs/MATCHING.md sec 19 "the gap": the accepted plan differs from what the add-on has stored -
					// rewrite the entity's own matchingPolicies[]/mergePolicies[] entries IN PLACE (apply_matching_config,
					// desired-state, whole-list REPLACE - CLAUDE.md sec 8) rather than re-running generate_matching_policy/
					// generate_merge_policy (which this table already has real entries for - a second generate for the
					// SAME processCode was never measured live and is avoided here on purpose - docs/MATCHING.md sec 25
					// measured live that a generate call CAN answer a hard refusal, not just a tolerable soft miss, so a
					// second generate for an existing processCode is avoided rather than assumed safe). set_trusted_sources
					// is deliberately NOT called here - the task brief's own contract: trustedSources/tableSettings are
					// unchanged by this route, the survivorship route (POST matching/survivorship, MergeConfigPatchPlanner)
					// stays the only writer of the table-level trusted-source ranking.
					if (!BusinessObjectPlan.mayUseApplyMatchingConfig(boScope.inBusinessObject()))
					{
						// a business-object table: apply_matching_config is refused there (measured on W13BO, 2026-10-12) and generate_matching_policy does NOT replace a
						// stored policy (Vol1mR steps 1435/1436: "already exists ... use patch_matching_config"). Each part is decided on its own: generate what is
						// missing, patch what differs in place, refuse a structural tree change; no related-table alignment (see alignsRelatedTables).
						detail.put("rewriteRoute", BusinessObjectPlan.writeMode(true).name());
						rewriteSkippedIdentical = MatchingConfigurator.rewriteBusinessObjectPolicies(client, tablePath, dataModel, pModelName, pEntityName,
							MameAnswer.field(getResult.json, "matchingPolicies"), MameAnswer.field(getResult.json, "mergePolicies"), desiredMatchingPolicy, desiredMergePolicy,
							boScope, detail);
					}
					else
					{
						final JsonNode existingMatchingPolicies = MameAnswer.field(getResult.json, "matchingPolicies");
						final JsonNode existingMergePolicies = MameAnswer.field(getResult.json, "mergePolicies");
						final JsonNode existingMergePolicy = MatchingPolicyComparator.findPolicyEntry(existingMergePolicies, "code", pEntityName);
						relatedTablesAligned.addAll(RelatedTableAlignment.alignedPaths(existingMergePolicy.path("relatedTables"), childTablePaths));
						final MatchingConfigRewriteBuilder.Rewrite rewrite = MatchingConfigRewriteBuilder.build(existingMatchingPolicies, existingMergePolicies,
							pEntityName, MatchingPreviewPolicy.policyCode(pEntityName), desiredMatchingPolicy, pEntityName, desiredMergePolicy, childTablePaths);
						final ObjectNode applyArgs = MatchingConfigurator.MAPPER.createObjectNode();
						applyArgs.put("table", tablePath);
						MatchingConfigurator.putDataModel(applyArgs, dataModel);
						final ObjectNode config = applyArgs.putObject("config");
						config.set("matchingPolicies", rewrite.matchingPolicies);
						config.set("mergePolicies", rewrite.mergePolicies);
						final McpClient.ToolResult applyResult = MameCalls.call(client, "apply_matching_config", applyArgs, pModelName);
						detail.set("applyRewrite", MatchingConfigurator.toolDetail(applyArgs, applyResult));
						MatchingConfigurator.requireSuccess(applyResult, "apply_matching_config");
					}
					detail.put("rewriteReason", diff.summary());

					// default-matching-policy lot (2026-09-23 13:30, docs/MATCHING.md sec 26): the coordinator's own
					// precision on this fix - the live table this fix was found against (/root/Contact) was ALREADY
					// in exactly the state "preview policy only, defaultMatchingPolicy = Contact__preview" BEFORE this
					// rewrite ran, so the NEXT configure on it takes REWRITE, not GENERATE_FRESH: the same correction
					// applies here too, not only after a fresh generate. apply_matching_config's own answer is never
					// assumed to carry tableSettings (unlike GENERATE_FRESH, which already has a fresh read-back in
					// hand) - pKnownReadBackOrNull is null, forcing ensureDefaultMatchingPolicy to fetch its own.
					MatchingConfigurator.ensureDefaultMatchingPolicy(client, tablePath, pModelName, MatchingPreviewPolicy.policyCode(pEntityName), pEntityName, null,
						detail);

					// "then activate_matching only if the status is not ready" - a rewrite onto an ALREADY-running
					// configuration should not force a re-activation (activate_matching resets the schema and
					// reattaches the matching trigger, CLAUDE.md sec 8 "Calcite" note - a heavier operation than a
					// plain policy edit) unless the table genuinely is not ready yet. Best-effort: an unreadable
					// status falls back to the pre-2026-09-22 unconditional activate_matching call below, never blocks.
					// 2026-09-23 15:50 fix (docs/MATCHING.md sec 29): "ENABLED" alone used to be trusted here, the
					// exact criterion measured live to be insufficient (state:"ENABLED" with metadatasetExists:false
					// still fails a simulate/match call) - now the same MatchingActivationGuard.isReady test the
					// guard itself uses, never a second, narrower copy of the rule.
					final MatchingActivationGuard.StatusSnapshot statusBeforeActivate = MatchingConfigurator.readActivationStatus(client, tablePath, pModelName, detail);
					skipActivateBecauseAlreadyEnabled = statusBeforeActivate != null && MatchingActivationGuard.isReady(statusBeforeActivate);
					final String stateBeforeActivateText = statusBeforeActivate == null ? "unreadable"
						: statusBeforeActivate.state + " (metadatasetExists=" + statusBeforeActivate.metadatasetExists + ")";
					detail.put("activationGate", skipActivateBecauseAlreadyEnabled ? "already ready (ENABLED + metadataset present) before the rewrite - activate_matching skipped"
						: "not ready (" + stateBeforeActivateText + ") before the rewrite - activate_matching will run");
					break;
				}
				default:
					throw new IllegalStateException("MatchingConfigurator.decideConfigureAction returned REFUSE after the refusal above already ran - this should be unreachable.");
			}

			// lot M4 (2026-10-18, docs/MATCHING.md sect;59): whichever branch ran above, the member tables of the business object get what a run of the object needs (a default matching
			// policy and a merge policy each; inert, never run on their own) - the add-on refuses the run of the main table otherwise ("The default matching policy is not defined")
			final List<String> memberSentences = new ArrayList<String>();
			if (boScope.inBusinessObject() && !memberOfBusinessObject)
			{
				currentPhase = "giving the member tables of the business object their policies";
				// lot M8 (2026-10-18, docs/MATCHING.md sect;62): the members' merge policies carry the MODE of the main table's (the add-on refuses the run otherwise: "Ensure the merge
				// policies use the same 'Mode'"); the mode the configure writes on the main table is handed over for the case where the re-read gives none
				memberSentences.addAll(MameBusinessObjects.ensureMemberPolicies(client, pModelName, boScope.businessObject, desiredMergePolicy.path("mode").asText(null), detail));
				if (!memberSentences.isEmpty())
				{
					pMemberPolicies.addAll(memberSentences);
					MatchingConfigurator.putStrings(detail, "memberPoliciesWritten", memberSentences);
					// the members were published by an earlier activation without these policies: activate the object again below
					skipActivateBecauseAlreadyEnabled = false;
				}
			}

			// lot MT fix (2026-10-04, measured live on Vol100k): whichever branch ran above (fresh generation, rewrite, resume,
			// "unchanged"), read the configuration back NOW and make every promoted child's relatedTables[] entry read
			// alignForeignKeys=WANTED in THIS configure. A REWRITE sends the merge policy with the entries it had stored - [] on
			// Vol100k's first configure - and the add-on re-adds every inverse-foreign-key table as MANUAL by itself, so the list
			// that was sent says nothing about the list that stands: only a read after the write does. The write below is the
			// SAME apply_matching_config the fresh branch uses (alignRelatedTables), made before the execution mode, validate,
			// activate.
			currentPhase = "aligning the related tables of the merge policy";
			if (!BusinessObjectPlan.alignsRelatedTables(boScope.inBusinessObject()))
			{
				detail.set("alignRelatedTablesAfterWrites", MatchingConfigurator.alignmentSkippedDetail());
			}
			else
			{
			final ObjectNode convergeArgs = MatchingConfigurator.MAPPER.createObjectNode();
			convergeArgs.put("table", tablePath);
			MatchingConfigurator.putDataModel(convergeArgs, dataModel);
			final McpClient.ToolResult convergeResult = MameCalls.call(client, "get_matching_config", convergeArgs, pModelName);
			detail.set("getConfigBeforeRelatedTablesCheck", MatchingConfigurator.toolDetail(convergeArgs, convergeResult));
			MatchingConfigurator.requireSuccess(convergeResult, "get_matching_config (related tables alignment check)");
			final JsonNode convergeMatchingPolicies = MameAnswer.field(convergeResult.json, "matchingPolicies");
			final JsonNode convergeMergePolicies = MameAnswer.field(convergeResult.json, "mergePolicies");
			final JsonNode convergeMergePolicy = MatchingPolicyComparator.findPolicyEntry(convergeMergePolicies, "code", pEntityName);
			final Map<String, String> foundBeforeAlignment = RelatedTableAlignment.mismatches(convergeMergePolicy.path("relatedTables"), childTablePaths);
			if (!foundBeforeAlignment.isEmpty())
			{
				final ObjectNode foundNode = detail.putObject("relatedTablesFoundBeforeAlignment");
				for (final Map.Entry<String, String> foundEntry : foundBeforeAlignment.entrySet())
				{
					foundNode.put(foundEntry.getKey(), foundEntry.getValue());
				}
				final List<String> alignedNow = MatchingConfigurator.alignRelatedTables(client, tablePath, dataModel, pModelName, pEntityName, childTablePaths,
					desiredMatchingPolicy, desiredMergePolicy, convergeMatchingPolicies, convergeMergePolicies, convergeMergePolicy, detail, "alignRelatedTablesAfterWrites");
				for (final String alignedPath : alignedNow)
				{
					if (!relatedTablesAligned.contains(alignedPath))
					{
						relatedTablesAligned.add(alignedPath);
					}
				}
			}
			}

			// lot MT (2026-10-04): the project's matching trigger mode on the REAL policy, whichever decision branch ran above
			// (RESUME_SAME included: a project whose mode changed since the last configure is corrected here, nothing else rewritten).
			currentPhase = "writing the execution mode of the matching policy";
			final MatchingTriggerMode triggerMode = MatchingTriggerMode.parse(MatchingConfigurator.readMatchingTrigger(pFactory, pProjectCode));
			if (memberOfBusinessObject)
			{
				// the object drives the matching of its member: the member refuses the execution-mode fields (Vol1mR step 1433), so they are neither read nor patched
				detail.set("executionMode", BusinessObjectPlan.executionModeSkippedOfMember());
			}
			else
			{
				MatchingConfigurator.ensureExecutionMode(client, tablePath, dataModel, pModelName, pEntityName, triggerMode, boScope, detail);
			}

			currentPhase = "validating the matching configuration";
			// lot M6 (2026-10-18, docs/MATCHING.md sect;61): with a business object the tool validates every configured member - and the add-on's own validator
			// throws (JSON-RPC error "matchingTableRecord is null") as soon as the object's report has one item, a product bug; MatchingValidation then
			// validates the main table alone and the three checks below apply to THAT answer, unchanged. A fallback that throws too propagates (today's refusal).
			final String dataModelForValidate = dataModel;
			final McpClient validateClient = client;
			final MatchingValidation.Outcome validation = MatchingValidation.validate(new MatchingValidation.Port()
			{
				@Override
				public MatchingValidation.Answer validate(final ObjectNode pArgs) throws McpException, OperationException
				{
					MatchingConfigurator.putDataModel(pArgs, dataModelForValidate);
					return MatchingValidation.Answer.of(MameCalls.call(validateClient, "validate_matching_config", pArgs, pModelName));
				}
			}, tablePath, boScope.inBusinessObject() ? boScope.businessObject : null, detail);
			final MatchingValidation.Answer validateResult = validation.answer;
			if (validateResult.error)
			{
				throw EngineRefusal.coded("external.validateMatching", "validate_matching_config failed for " + tablePath + ": " + MatchingConfigurator.shortText(validateResult.text), tablePath);
			}
			if (!MameAnswer.success(validateResult.json, false))
			{
				// a soft miss here (e.g. a stale CONFIG_NOT_FOUND between the patch above and this call) used to
				// read as plain "invalid" by the same root-vs-data accident this fix addresses - now reported for
				// what it actually is, never silently folded into "the policy is invalid" (class Javadoc, 2026-09-22).
				final String validateCode = MameAnswer.code(validateResult.json);
				throw EngineRefusal.coded("external.validateMatching", "validate_matching_config answered a soft miss for " + tablePath + " ("
					+ (validateCode == null ? "no code" : validateCode) + "): " + MatchingConfigurator.shortText(validateResult.text), tablePath);
			}
			final boolean valid = MameAnswer.field(validateResult.json, "valid").asBoolean(false);
			if (!valid)
			{
				throw EngineRefusal.coded("external.validateMatching", "validate_matching_config reported errors for " + tablePath + " after generating the policy: "
					+ MatchingConfigurator.describeValidationMessages(validateResult.json), tablePath);
			}
			// the main table validated alone after the object's validation threw: said to the person (step message, configure job, wizard), never silently
			String validationNote = null;
			if (validation.fellBack)
			{
				validationNote = MatchingValidation.noteEnglish(boScope.businessObject, tablePath);
				detail.put("validateBusinessObjectNote", MatchingValidation.note(boScope.businessObject, tablePath));
				pValidationNotes.add(MatchingValidation.note(boScope.businessObject, tablePath));
			}

			currentPhase = "activating matching";
			if (skipActivateBecauseAlreadyEnabled)
			{
				detail.put("activate", "skipped - get_matching_activation_status already reported ENABLED before this rewrite");
			}
			else if (boScope.inBusinessObject())
			{
				// activate_matching is refused on a table that is part of a business object: activate_business_object_matching activates every member
				// (measured on W13BO: the main table AND the child get a metadataset); the table must read ready in get_business_object_activation_status
				if (!MameBusinessObjects.activate(client, pModelName, tablePath, boScope, detail))
				{
					throw EngineRefusal.coded("external.activateMatching", "activate_business_object_matching did not leave " + tablePath + " ready (ENABLED + metadataset present): "
						+ detail.path("activateBusinessObjects").toString(), tablePath);
				}
			}
			else
			{
				final ObjectNode activateArgs = MatchingConfigurator.MAPPER.createObjectNode();
				activateArgs.put("table", tablePath);
				MatchingConfigurator.putDataModel(activateArgs, dataModel);
				final McpClient.ToolResult activateResult = MameCalls.call(client, "activate_matching", activateArgs, pModelName);
				detail.set("activate", MatchingConfigurator.toolDetail(activateArgs, activateResult));
				// 2026-09-23 15:50 fix (docs/MATCHING.md sec 29): used to check "ENABLED" alone off activate_matching's
				// own answer - the exact criterion measured live to be insufficient (state:"ENABLED" with
				// metadatasetExists:false still fails a simulate/match call). Same MatchingActivationGuard.isReady
				// test the guard's own retry core uses, applied here to activate_matching's OWN answer rather than a
				// separate re-read call - a minimal fix, not the full status/activate/re-read cycle MatchingActivationGuard
				// runs for the simulate path; this call site has never itself been seen to hit the ENABLED-but-no-metadataset
				// shape live, but there is no reason activate_matching's own answer would be immune to it.
				final boolean activated = MameAnswer.success(activateResult.json, false) && MatchingActivationGuard.isReady(MatchingActivationGuard.parseStatus(activateResult.json));
				if (!activated)
				{
					throw EngineRefusal.coded("external.activateMatching", "activate_matching did not report a ready state (ENABLED + metadataset present) for " + tablePath + ": "
						+ MatchingConfigurator.shortText(activateResult.text), tablePath);
				}
			}

			currentPhase = "recording the accepted plan as applied";
			MatchingConfigurator.markApplied(pSession, pFactory, pPlan, pEntityName);
			// simulation-preview-policy lot (2026-09-22): best-effort cleanup of a leftover Simulate-click preview
			// policy for this SAME entity, now that the real one is configured and activated - never allowed to
			// undo an otherwise-successful configure: a removal failure is recorded in this step's own detail and
			// otherwise ignored, the exact same "never let a report-only follow-up fail the call that matters"
			// discipline ebx.MameMatchingApi's own safeDetail/safeCompletionRate/safeDurationMs already apply.
			// lot M2 (2026-10-17): a preview that stays - left on purpose (business object table) or a failed removal - is reported to the configure job, not only buried in this step's detail
			String previewLeftReason = null;
			try
			{
				final ObjectNode removal = MatchingPreviewPolicy.remove(pSession, pFactory, pProjectCode, pEntityName);
				detail.set("removedPreviewPolicy", removal);
				if (MatchingPreviewPolicy.wasLeft(removal))
				{
					previewLeftReason = removal.path("leftReason").asText("");
				}
			}
			catch (final OperationException ex)
			{
				detail.put("removePreviewPolicyError", ex.getMessage());
				previewLeftReason = "the removal failed: " + ex.getMessage();
			}
			catch (final RuntimeException ex)
			{
				detail.put("removePreviewPolicyError", ex.getClass().getName() + ": " + ex.getMessage());
				previewLeftReason = "the removal failed: " + ex.getClass().getName() + ": " + ex.getMessage();
			}
			if (previewLeftReason != null)
			{
				detail.put("previewPolicyLeft", MatchingPreviewPolicy.policyCode(pEntityName));
				pPreviewsLeft.add(ChildEntityMatching.previewLeftNote(pEntityName, MatchingPreviewPolicy.policyCode(pEntityName), previewLeftReason));
			}

			// lot R12 (2026-09-27, docs/MATCHING.md sect;41, live evidence A): a SECOND, fully independent recheck of
			// activation at the very END of configure - the same "recheck at the very end, after every write this
			// method makes" discipline. This
			// method's own activate_matching call (or the skip because it was already ENABLED) already ran above -
			// but the boot-race defect this self-heal exists for (CLAUDE.md sect;8 "Recursive load",
			// MatchingActivationGuard's own class Javadoc) is, in principle, a race with the add-on's own async
			// schema recompile, so checking once more here means a "successful" configure never hands back a table
			// that runInternal's OWN pre-run check (the same ensureActivatedBeforeRun call) would immediately have
			// to repair anyway. A single read when the table is already ready, exactly like the pre-run check.
			currentPhase = "verifying matching activation at the end of configure";
			try
			{
				final String activationNoteAtEnd = MatchingActivationGuard.ensureActivatedBeforeRun(pSession, pFactory, pProjectCode, tablePath);
				if (activationNoteAtEnd != null)
				{
					detail.put("activationRepairedAtEnd", true);
					detail.put("activationNoteAtEnd", activationNoteAtEnd);
				}
			}
			catch (final OperationException ex)
			{
				final String activationFailureMessage = "Matching was configured for " + pEntityName + " but a final activation recheck failed for " + tablePath + ": "
					+ ex.getMessage();
				PipelineSteps.end(pSession, pFactory, stepId, false, activationFailureMessage, detail.toString());
				throw EngineRefusal.codedWithCause("unexpected.failed", activationFailureMessage, ex);
			}

			// lot MT fix (2026-10-04): the proof, read after EVERY write of this configure (execution mode, validate, activate,
			// preview cleanup): no promoted child's relatedTables[] entry may read anything but alignForeignKeys=WANTED. A configure that
			// leaves one on MANUAL is not "configured": the add-on's automatic merge then fails at volume (CascadingRecords, section 52.2).
			currentPhase = "verifying the related-table alignment at the end of configure";
			if (!BusinessObjectPlan.alignsRelatedTables(boScope.inBusinessObject()))
			{
				detail.put("relatedTablesAlignmentVerified", "skipped - business object (its child relationship replaces the related-table alignment)");
			}
			else
			{
				final ObjectNode finalReadArgs = MatchingConfigurator.MAPPER.createObjectNode();
				finalReadArgs.put("table", tablePath);
				MatchingConfigurator.putDataModel(finalReadArgs, dataModel);
				final McpClient.ToolResult finalReadResult = MameCalls.call(client, "get_matching_config", finalReadArgs, pModelName);
				MatchingConfigurator.requireSuccess(finalReadResult, "get_matching_config (final related tables check)");
				final JsonNode finalMergePolicy = MatchingPolicyComparator.findPolicyEntry(MameAnswer.field(finalReadResult.json, "mergePolicies"), "code", pEntityName);
				final Map<String, String> stillWrong = RelatedTableAlignment.mismatches(finalMergePolicy.path("relatedTables"), childTablePaths);
				if (!stillWrong.isEmpty())
				{
					final ObjectNode stillWrongNode = detail.putObject("relatedTablesStillWrong");
					final StringBuilder wrongText = new StringBuilder();
					for (final Map.Entry<String, String> wrong : stillWrong.entrySet())
					{
						stillWrongNode.put(wrong.getKey(), wrong.getValue());
						wrongText.append(wrongText.length() == 0 ? "" : ", ").append(wrong.getKey()).append(" reads alignForeignKeys=").append(wrong.getValue());
					}
					throw EngineRefusal.coded("conflict.relatedAlignment", "Matching was configured for " + pEntityName + " but the merge policy's related table(s) are not aligned on " + tablePath + ": "
						+ wrongText + " (wanted " + RelatedTableAlignment.WANTED + ") - the add-on put the entry back after the alignment write; Configure again, and report it if it repeats.", pEntityName, tablePath);
				}
				detail.put("relatedTablesAlignmentVerified", true);
			}

			// cascade lot (2026-09-23): reported regardless of which decision branch actually did the aligning
			// (GENERATE_FRESH's own follow-up write, or REWRITE's single apply_matching_config call) - a data
			// steward reading the step's message should not need to know which branch ran to see the cascade.
			String message = "Matching configured and activated for " + tablePath + " ("
				+ BusinessObjectPlan.triggerModeSentence(triggerMode.name(), triggerMode.executionValue(), memberOfBusinessObject, boScope.businessObject) + ").";
			// lot M5 (2026-10-04): a plan field that is a script derivation nothing computes is compared EMPTY - say so, never silently
			final List<String> scriptDerivationWarnings = ScriptDerivationGate.planWarnings(ScriptDerivations.read(pFactory, pProjectId), pEntityName,
				ScriptDerivationGate.policyFieldNames(desiredMatchingPolicy));
			if (!scriptDerivationWarnings.isEmpty())
			{
				MatchingConfigurator.putStrings(detail, "scriptDerivationWarnings", scriptDerivationWarnings);
				message += " WARNING: " + String.join("; ", scriptDerivationWarnings) + ".";
			}
			message += MatchingPolicyTranslator.summariseNullHandlingTranslations(nullHandlingTranslated);
			if (!memberSentences.isEmpty())
			{
				message += " " + String.join(" ", memberSentences);
			}
			if (validationNote != null)
			{
				message += " NOTE: " + validationNote;
			}
			if (previewLeftReason != null)
			{
				message += " NOTE: the Simulate preview policy " + MatchingPreviewPolicy.policyCode(pEntityName) + " could not be removed (" + previewLeftReason + ").";
			}
			// lot M8a (2026-10-04, docs/MATCHING.md sect;51): does the add-on get candidate filters out of the plan just applied, and is its JVM option on here?
			final String candidateSearchWarning = MatchingConfigurator.recordCandidateSearch(detail, requirements);
			if (candidateSearchWarning != null)
			{
				message += " WARNING: " + candidateSearchWarning;
			}
			if (!relatedTablesAligned.isEmpty())
			{
				MatchingConfigurator.putStrings(detail, "relatedTablesAligned", relatedTablesAligned);
				message += " Related table(s) set to alignForeignKeys=" + RelatedTableAlignment.WANTED + " (the Factory re-points the children to the golden at the master load): "
					+ String.join(", ", relatedTablesAligned) + ".";
			}
			PipelineSteps.end(pSession, pFactory, stepId, true, message, detail.toString());
			// a business-object rewrite that found the stored policies identical after the add-on's read-back differences wrote nothing: reported as already done
			return rewriteSkippedIdentical ? MatchingConfigurator.ConfigureDecision.RESUME_SAME : decision;
		}
		catch (final McpException ex)
		{
			// lot R7 (2026-09-27, error-handling fix, task brief): the full stack trace now always reaches
			// kernel.log - before this fix, an unlabelled generic exception's message was the ONLY trace anywhere,
			// and this class' own catches never logged at all (a masked-kernel-exception class of bug this
			// codebase has hit before, CLAUDE.md sec 9's own "Adaptation.get throws" note).
			LoggingCategory.getKernel().error(
				"[mdm-factory] matching/configure failed for " + tablePath + " (entity " + pEntityName + ", project " + pProjectCode + ") while " + currentPhase, ex);
			detail.put("transportError", ex.getMessage());
			detail.put("failedPhase", currentPhase);
						PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final OperationException ex)
		{
			LoggingCategory.getKernel().error(
				"[mdm-factory] matching/configure failed for " + tablePath + " (entity " + pEntityName + ", project " + pProjectCode + ") while " + currentPhase, ex);
			detail.put("failedPhase", currentPhase);
						PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final RuntimeException ex)
		{
			// MatchingPolicyTranslator's own pre-flight self-check (docs/MATCHING.md, 2026-09-21 fix) throws
			// IllegalStateException rather than a checked type, being pure/EBX-free - caught here so a translator
			// (or any other unexpected) defect ends this entity's step as FAILED (never a bare, unrecorded 500) and
			// never leaves a RUNNING row behind. PipelineSteps.isDone only ever matches DONE (PipelineSteps.java:36-46),
			// so a FAILED step here never blocks a later re-run of matching/configure for the same entity - see this
			// class' own Javadoc "idempotent/resumable" note.
			// <p>
			// lot R7 (2026-09-27, task brief): this catch used to unconditionally report "matching policy translation
			// failed" for EVERY RuntimeException reaching here, whatever actually threw it (a live PersVol7 failure
			// with getMessage()==null read as the entirely uninformative "matching policy translation failed for
			// /root/Personne: null", even though the actual failure was downstream of translation, in the MCP write/
			// relink sequence). The message now names the PHASE this method was actually in when the exception hit
			// ({@link #currentPhase}, updated as this method progresses) and never loses the exception's own class
			// name when {@code getMessage()} is null - and the full stack trace always reaches kernel.log, not only
			// this one string.
			final String exceptionMessage = ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
			LoggingCategory.getKernel().error(
				"[mdm-factory] matching/configure failed for " + tablePath + " (entity " + pEntityName + ", project " + pProjectCode + ") while " + currentPhase, ex);
			final String message = currentPhase + " failed for " + tablePath + ": " + exceptionMessage;
			detail.put("failedPhase", currentPhase);
			detail.put("exceptionClass", ex.getClass().getName());
			detail.put("translationError", ex.getMessage());
						PipelineSteps.end(pSession, pFactory, stepId, false, message, detail.toString());
			throw EngineRefusal.codedWithCause("unexpected.failed", message, ex);
		}
	}

	/** Lot M8a: the requirements JSON as a tree, or null when it does not parse - for a read-only check that must never fail a step. */
	static JsonNode requirementsOrNull(final String pRequirementsJson)
	{
		try
		{
			return pRequirementsJson == null || pRequirementsJson.trim().isEmpty() ? null : MatchingConfigurator.MAPPER.readTree(pRequirementsJson);
		}
		catch (final Exception ex)
		{
			return null;
		}
	}

	/**
	 * Lot M8a (2026-10-04, {@code docs/MATCHING.md} &sect;51): records, under {@code detail.candidateSearch}, whether the add-on gets candidate filters out of the plan
	 * ({@link CandidateFilterCheck}) and whether its JVM option is on in THIS JVM, and returns the one-sentence warning for the step message - null when the plan is
	 * filtered and the option is on, or when there is no tree to judge. Read-only: nothing is written to the add-on, and a plan that is not filtered is never refused here
	 * (the language-model pass is where it is refused).
	 */
	static String recordCandidateSearch(final ObjectNode pDetail, final JsonNode pRequirements)
	{
		final CandidateFilterCheck.Result result = CandidateFilterCheck.check(pRequirements);
		if (CandidateFilterCheck.VERDICT_NOT_APPLICABLE.equals(result.verdict))
		{
			return null;
		}
		final boolean option = CandidateFilterCheck.jvmOptionEnabled();
		final ObjectNode node = pDetail.putObject("candidateSearch");
		node.put("verdict", result.verdict);
		node.put("jvmOption", option ? "ON" : "OFF");
		if (result.filtered())
		{
			node.put("filters", result.filtersPhrase());
		}
		final ArrayNode withoutKey = node.putArray("pathsWithoutKey");
		for (final CandidateFilterCheck.PathResult path : result.pathsWithoutKey())
		{
			withoutKey.add(path.label());
		}
		final String reason = CandidateFilterCheck.acceptedReason(pRequirements);
		if (!reason.isEmpty())
		{
			node.put("acceptedReason", reason);
		}
		final String warning = CandidateFilterCheck.warning(result, option);
		if (warning != null)
		{
			node.put("warning", warning);
		}
		return warning;
	}

	private static JsonNode parseRequirements(final String pRequirementsJson) throws OperationException
	{
		try
		{
			return MatchingConfigurator.MAPPER.readTree(pRequirementsJson);
		}
		catch (final java.io.IOException ex)
		{
			throw OperationException.createError("MatchPlan.requirements is not valid JSON: " + ex.getMessage(), ex);
		}
	}

	// --------------------------------------------------------------------------------------- relation fields

	/**
	 * The coded refusal ({@code missing.businessObject}) of a plan that compares child fields on a table the master model gives no business object: the add-on
	 * reads a child field only through one, so there is no fallback (the older placeholder/relink route is gone). A master generated before lot W13
	 * (2026-10-12) has none: the remedy is generating the master model again (the generator declares one per table that has child tables) or declaring it by hand.
	 */
	static EngineRefusal missingBusinessObjectRefusal(final String pEntityName, final String pTablePath, final String pModelName, final Set<String> pRelationFields)
	{
		return EngineRefusal.coded("missing.businessObject", "The accepted plan of " + pEntityName + " compares " + pRelationFields + " (fields of child tables) but the master model "
			+ pModelName + " declares no business object whose main table is " + pTablePath + ": the add-on reads a child field only through a business object. "
			+ "Generate the master model again (the Master step declares one business object per table that has child tables, and publishes), or declare it by hand in the "
			+ "data model assistant (Extensions, Business objects: main table " + pTablePath + ", one relationship per child through an inverse association). "
			+ "A master generated before 2026-10-12 has none.", pEntityName, pTablePath);
	}


	/**
	 * Every promoted CHILD entity that holds a foreign key targeting {@code pEntityName} (the exact same relationship {@code
	 * pMasterModel.childTablePaths} already reads off {@code pMasterModel.foreignKeys}, kept here at the ENTITY level since {@code FieldRef#via} names
	 * the child ENTITY, not its table path), mapped to the child's master table path - what {@code MatchingPolicyTranslator.translate} needs to write
	 * a relation field as {@code /root/<Child>/<field>} (native relation fields, add-on 6.3.0.5-RC3).
	 */
	static Map<String, String> relationChildTablePaths(final MasterModel.Result pMasterModel, final String pEntityName)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (final MasterModel.ForeignKeyInfo foreignKey : pMasterModel.foreignKeys)
		{
			if (pEntityName.equals(foreignKey.targetEntityName) && !pEntityName.equals(foreignKey.entityName))
			{
				out.put(foreignKey.entityName, MasterModel.tablePath(foreignKey.entityName));
			}
		}
		return out;
	}

	/** The step-detail entry that says the related-table alignment was not done for a business-object table (see {@link BusinessObjectPlan#alignsRelatedTables}). */
	static ObjectNode alignmentSkippedDetail()
	{
		final ObjectNode node = MatchingConfigurator.MAPPER.createObjectNode();
		node.put("skipped", "business object");
		return node;
	}

	/**
	 * The rewrite of the policies of a business-object table whose accepted plan differs from what the add-on stores (lot W13b, `docs/MATCHING.md` &sect;33.13):
	 * {@link BusinessObjectRewritePlan} decides each part. A missing matching or merge policy is generated, an existing one is patched in place with
	 * {@code patch_matching_config} (one call: matching operations, merge operations, the merge-policy link), and a structural change of the decision tree is refused
	 * with the coded {@code conflict.policyRewriteNeedsManualChange} before anything is written. Records {@code rewrite} in the step detail. Returns true when the
	 * stored policies already were the plan's and nothing was written ({@code rewrite: {skipped: "identical"}}).
	 */
	static boolean rewriteBusinessObjectPolicies(final McpClient pClient, final String pTablePath, final String pDataModel, final String pModelName, final String pEntityName,
		final JsonNode pStoredMatchingPolicies, final JsonNode pStoredMergePolicies, final ObjectNode pDesiredMatching, final ObjectNode pDesiredMerge,
		final MameBusinessObjects.Scope pScope, final ObjectNode pDetail) throws OperationException, McpException
	{
		final BusinessObjectRewritePlan plan = BusinessObjectRewritePlan.build(pEntityName, pStoredMatchingPolicies, pStoredMergePolicies, pDesiredMatching, pDesiredMerge);
		final ObjectNode rewrite = pDetail.putObject("rewrite");
		rewrite.put("matching", plan.matching.name());
		rewrite.put("merge", plan.merge.name());
		rewrite.put("link", plan.linkNeeded);
		if (plan.refuses())
		{
			MatchingConfigurator.putStrings(rewrite, "structural", plan.structural);
			throw EngineRefusal.coded("conflict.policyRewriteNeedsManualChange", "The decision tree of policy " + pEntityName + " on " + pTablePath
				+ " differs structurally from the accepted plan (" + plan.describeStructural() + "); delete the policy in the Match and Merge administration screen and configure again.",
				pTablePath, pEntityName, plan.describeStructural());
		}
		if (plan.identical())
		{
			rewrite.put("skipped", "identical");
			return true;
		}
		if (plan.matching == BusinessObjectRewritePlan.Action.GENERATE)
		{
			final ObjectNode args = MatchingConfigurator.MAPPER.createObjectNode();
			args.put("table", pTablePath);
			MatchingConfigurator.putDataModel(args, pDataModel);
			args.set("matchingPolicy", pDesiredMatching);
			MameBusinessObjects.putBusinessObject(args, pScope);
			final McpClient.ToolResult result = MameCalls.call(pClient, "generate_matching_policy", args, pModelName);
			rewrite.set("generateMatchingPolicy", MatchingConfigurator.toolDetail(args, result));
			MatchingConfigurator.requireSuccess(result, "generate_matching_policy");
		}
		if (plan.merge == BusinessObjectRewritePlan.Action.GENERATE)
		{
			final ObjectNode args = MatchingConfigurator.MAPPER.createObjectNode();
			args.put("table", pTablePath);
			MatchingConfigurator.putDataModel(args, pDataModel);
			args.set("mergePolicy", pDesiredMerge);
			MameBusinessObjects.putBusinessObject(args, pScope);
			final McpClient.ToolResult result = MameCalls.call(pClient, "generate_merge_policy", args, pModelName);
			rewrite.set("generateMergePolicy", MatchingConfigurator.toolDetail(args, result));
			MatchingConfigurator.requireSuccess(result, "generate_merge_policy");
		}
		final ArrayNode operations = plan.patchOperations();
		if (operations.size() > 0)
		{
			final ObjectNode args = MatchingConfigurator.MAPPER.createObjectNode();
			args.put("table", pTablePath);
			MatchingConfigurator.putDataModel(args, pDataModel);
			args.set("operations", operations);
			MameBusinessObjects.putBusinessObject(args, pScope);
			final McpClient.ToolResult result = MameCalls.call(pClient, "patch_matching_config", args, pModelName);
			rewrite.set("patchMatchingConfig", MatchingConfigurator.toolDetail(args, result));
			MatchingConfigurator.requireSuccess(result, "patch_matching_config (rewrite in place)");
		}
		return false;
	}

	/**
	 * Lot M1 (2026-10-17, `docs/MATCHING.md` sect;56): a REWRITE of a table configured before this lot sends a merge policy whose {@code autoCreateNewGoldenSource} is
	 * {@link MatchingPolicyTranslator#GOLDEN_SOURCE_CODE}, which the add-on only accepts when that source is registered. {@code set_trusted_sources} registers any code of
	 * {@code tableTrustedSourceList} - so, when the code is neither in the stored ranking nor in the instance catalogue, the STORED ranking is sent back with the code
	 * appended (never the project's rows: a rewrite does not touch the ranking, {@code fieldTrustedSources} and {@code sources} are not sent, hence left as they are).
	 * Nothing is called when the source is already registered.
	 */
	static void ensureGoldenSourceRegistered(final McpClient pClient, final String pTablePath, final String pDataModel, final String pModelName, final JsonNode pStoredConfig,
		final ObjectNode pDetail) throws OperationException, McpException
	{
		final ObjectNode payload = TrustedSourceRanking.registerGoldenSourcePayload(MameAnswer.field(pStoredConfig, "trustedSources"), MatchingConfigurator.SOURCE_SYSTEM_FIELD_PATH);
		if (payload == null)
		{
			pDetail.put("goldenSourceRegistered", "already registered");
			return;
		}
		final ObjectNode args = MatchingConfigurator.MAPPER.createObjectNode();
		args.put("table", pTablePath);
		MatchingConfigurator.putDataModel(args, pDataModel);
		args.set("trustedSources", payload);
		final McpClient.ToolResult result = MameCalls.call(pClient, "set_trusted_sources", args, pModelName);
		pDetail.set("registerGoldenSource", MatchingConfigurator.toolDetail(args, result));
		MatchingConfigurator.requireSuccess(result, "set_trusted_sources (register the golden source)");
	}

	/**
	 * Writes the matching policy, the merge policy and the link between them with the policy tools: {@code generate_matching_policy}, {@code generate_merge_policy} (for a policy that does not exist yet:
	 * a second call with the same {@code processCode} is refused, see {@link #rewriteBusinessObjectPolicies}) and {@code patch_matching_config set_merge_policy_link}, each with
	 * {@code businessObject} when the table belongs to one. The only way to write a business-object table ({@code apply_matching_config} is refused there); also the fresh
	 * generation of any table.
	 */
	static void writePoliciesThroughGenerate(final McpClient pClient, final String pTablePath, final String pDataModel, final String pModelName, final String pEntityName,
		final ObjectNode pMatchingPolicy, final ObjectNode pMergePolicy, final MameBusinessObjects.Scope pScope, final ObjectNode pDetail) throws OperationException, McpException
	{
		MatchingConfigurator.writePoliciesThroughGenerate(pClient, pTablePath, pDataModel, pModelName, pEntityName, pMatchingPolicy, pMergePolicy, pScope, pDetail, null);
	}

	/** One write the caller wants between {@code generate_matching_policy} and {@code generate_merge_policy} (lot M4). */
	@FunctionalInterface
	interface WriteStep
	{
		void run() throws OperationException, McpException;
	}

	/** As above, {@code pBetweenMatchingAndMerge} (null = nothing) runs after the matching policy is written and before the merge policy. */
	static void writePoliciesThroughGenerate(final McpClient pClient, final String pTablePath, final String pDataModel, final String pModelName, final String pEntityName,
		final ObjectNode pMatchingPolicy, final ObjectNode pMergePolicy, final MameBusinessObjects.Scope pScope, final ObjectNode pDetail, final WriteStep pBetweenMatchingAndMerge)
		throws OperationException, McpException
	{
		final ObjectNode generateMatchingArgs = MatchingConfigurator.MAPPER.createObjectNode();
		generateMatchingArgs.put("table", pTablePath);
		MatchingConfigurator.putDataModel(generateMatchingArgs, pDataModel);
		generateMatchingArgs.set("matchingPolicy", pMatchingPolicy);
		MameBusinessObjects.putBusinessObject(generateMatchingArgs, pScope);
		final McpClient.ToolResult matchingResult = MameCalls.call(pClient, "generate_matching_policy", generateMatchingArgs, pModelName);
		pDetail.set("generateMatchingPolicy", MatchingConfigurator.toolDetail(generateMatchingArgs, matchingResult));
		MatchingConfigurator.requireSuccess(matchingResult, "generate_matching_policy");
		if (pBetweenMatchingAndMerge != null)
		{
			pBetweenMatchingAndMerge.run();
		}

		final ObjectNode generateMergeArgs = MatchingConfigurator.MAPPER.createObjectNode();
		generateMergeArgs.put("table", pTablePath);
		MatchingConfigurator.putDataModel(generateMergeArgs, pDataModel);
		generateMergeArgs.set("mergePolicy", pMergePolicy);
		MameBusinessObjects.putBusinessObject(generateMergeArgs, pScope);
		final McpClient.ToolResult mergeResult = MameCalls.call(pClient, "generate_merge_policy", generateMergeArgs, pModelName);
		pDetail.set("generateMergePolicy", MatchingConfigurator.toolDetail(generateMergeArgs, mergeResult));
		MatchingConfigurator.requireSuccess(mergeResult, "generate_merge_policy");

		final ObjectNode patchArgs = MatchingConfigurator.MAPPER.createObjectNode();
		patchArgs.put("table", pTablePath);
		MatchingConfigurator.putDataModel(patchArgs, pDataModel);
		final ArrayNode operations = patchArgs.putArray("operations");
		final ObjectNode linkOp = operations.addObject();
		linkOp.put("op", "set_merge_policy_link");
		linkOp.putObject("target").put("policyCode", pEntityName);
		linkOp.put("value", pEntityName);
		MameBusinessObjects.putBusinessObject(patchArgs, pScope);
		final McpClient.ToolResult patchResult = MameCalls.call(pClient, "patch_matching_config", patchArgs, pModelName);
		pDetail.set("linkMergePolicy", MatchingConfigurator.toolDetail(patchArgs, patchResult));
		MatchingConfigurator.requireSuccess(patchResult, "patch_matching_config (set_merge_policy_link)");
	}

	/**
	 * Lot MT fix (2026-10-04): the ONE {@code apply_matching_config} that sets every promoted child's {@code relatedTables[]} entry to
	 * {@link RelatedTableAlignment#WANTED}, built from a configuration read back right after the writes ({@code
	 * MatchingConfigRewriteBuilder.build} over the just-read {@code matchingPolicies} + {@code mergePolicies}, so the decision tree
	 * already stored is sent back unchanged). Shared by the fresh-generation follow-up and by the convergence step every branch now
	 * runs - it used to be one inline copy that only the fresh branch had, which is why a REWRITE (whose own write re-created the
	 * entry as {@code MANUAL}) could end a configure unaligned.
	 * <p>
	 * Never called for a table that belongs to a business object (lot W13): {@code apply_matching_config} is refused there and the object's child relationship replaces the
	 * alignment ({@link BusinessObjectPlan#alignsRelatedTables}).
	 *
	 * @return the paths whose entry was not {@code WANTED} before the write (empty = nothing written)
	 */
	private static List<String> alignRelatedTables(final McpClient pClient, final String pTablePath, final String pDataModel, final String pModelName, final String pEntityName,
		final Set<String> pChildTablePaths, final ObjectNode pDesiredMatchingPolicy, final ObjectNode pDesiredMergePolicy, final JsonNode pReadMatchingPolicies,
		final JsonNode pReadMergePolicies, final JsonNode pReadMergePolicy, final ObjectNode pDetail, final String pDetailKey) throws OperationException, McpException
	{
		final List<String> toAlign = RelatedTableAlignment.alignedPaths(pReadMergePolicy.path("relatedTables"), pChildTablePaths);
		if (toAlign.isEmpty())
		{
			return toAlign;
		}
		final MatchingConfigRewriteBuilder.Rewrite rewrite = MatchingConfigRewriteBuilder.build(pReadMatchingPolicies, pReadMergePolicies, pEntityName,
			MatchingPreviewPolicy.policyCode(pEntityName), pDesiredMatchingPolicy, pEntityName, pDesiredMergePolicy, pChildTablePaths);
		final ObjectNode alignArgs = MatchingConfigurator.MAPPER.createObjectNode();
		alignArgs.put("table", pTablePath);
		MatchingConfigurator.putDataModel(alignArgs, pDataModel);
		final ObjectNode alignConfig = alignArgs.putObject("config");
		alignConfig.set("matchingPolicies", rewrite.matchingPolicies);
		alignConfig.set("mergePolicies", rewrite.mergePolicies);
		final McpClient.ToolResult alignResult = MameCalls.call(pClient, "apply_matching_config", alignArgs, pModelName);
		pDetail.set(pDetailKey, MatchingConfigurator.toolDetail(alignArgs, alignResult));
		MatchingConfigurator.requireSuccess(alignResult, "apply_matching_config (align related tables)");
		return toAlign;
	}

	/**
	 * Package-visible (not {@code private}) since the preview-policy-fresh-link fix, 2026-09-23: {@code
	 * ebx.MatchingPreviewPolicy#ensureFresh} now mirrors this class' own {@code GENERATE_FRESH} sequence
	 * (generate matching, generate merge, {@code patch_matching_config} link) exactly and reuses this SAME
	 * check rather than duplicating it - see that class' own Javadoc for the live bug this closed.
	 */
	static void requireSuccess(final McpClient.ToolResult pResult, final String pToolName) throws OperationException
	{
		if (pResult.error)
		{
			throw EngineRefusal.coded("external.toolFailed", pToolName + " failed: " + MatchingConfigurator.shortText(pResult.text), pToolName);
		}
		// default true on an absent/null json, unchanged from before the 2026-09-22 fix - most write tools here
		// answer no "success" field at all on a genuine success, only a soft miss carries one set to false.
		if (!MameAnswer.success(pResult.json, true))
		{
			throw EngineRefusal.coded("external.toolFailed", pToolName + " answered a soft miss: " + MatchingConfigurator.shortText(pResult.text), pToolName);
		}
	}

	/**
	 * default-matching-policy lot (2026-09-23 13:30, live PipelineStep 159, `docs/MATCHING.md` &sect;26): the
	 * add-on makes the FIRST matching policy ever generated on a table its {@code tableSettings
	 * .defaultMatchingPolicy}, whatever that policy's code - including a throwaway {@code __preview} one from a
	 * Simulate click. Left uncorrected the table keeps serving that policy as its default indefinitely, even
	 * after the entity is accepted and a real policy is generated/linked (nothing about {@code
	 * generate_matching_policy}/{@code apply_matching_config} touches {@code tableSettings} - only {@code
	 * set_table_settings} does). This method decides ({@link DefaultMatchingPolicyGate#decide}) and, when a
	 * change is actually needed, WRITES it via {@code set_table_settings} and VERIFIES the write - never a
	 * best-effort follow-up (a wrong default silently mis-points every future manual "Run match" on the table),
	 * unlike {@link MatchingPreviewPolicy#remove}'s own cleanup a few lines below in {@link #configureOne}.
	 * <p>
	 * Shared by three call sites (class Javadoc convention, same as {@link #requireSuccess}): {@code
	 * MatchingPreviewPolicy#ensureFresh} ({@code pRealCodeOrNull = null}), {@link #configureOne} after {@code
	 * GENERATE_FRESH} and after {@code REWRITE} ({@code pRealCodeOrNull = } the entity name), and {@code
	 * MatchingPreviewPolicy#remove} ({@code pRealCodeOrNull = } the table's remaining real merge-policy code, if
	 * any, found by {@code firstNonPreviewCode} - called BEFORE the removal itself, the same "safe order"
	 * {@code remove}'s own Javadoc documents).
	 *
	 * @param pKnownReadBackOrNull a {@code get_matching_config} answer the caller ALREADY HAS in hand and knows
	 *            reflects the table's CURRENT state (e.g. the read-back right after a fresh generate, or the
	 *            config read before a removal) - reused as-is when it answers success, saving a redundant call;
	 *            {@code null}, or an answer that does not read as a success, makes this method fetch its own
	 *            fresh {@code get_matching_config} first.
	 * @param pPublicationName the project's master model name (never a resolved {@code dataModel} value any
	 *            more, 2026-09-23, `docs/MATCHING.md` &sect;28) - every call this method makes goes through
	 *            {@link MameCalls#call}, which sets the real {@code dataModel} unconditionally; {@code null}
	 *            only for a call with no owning project (none, as of this fix).
	 */
	static void ensureDefaultMatchingPolicy(final McpClient pClient, final String pTablePath, final String pPublicationName, final String pPreviewCode,
		final String pRealCodeOrNull, final JsonNode pKnownReadBackOrNull, final ObjectNode pDetail) throws OperationException, McpException
	{
		JsonNode readBack = pKnownReadBackOrNull;
		if (readBack == null || !MameAnswer.success(readBack, false))
		{
			final ObjectNode getArgs = MatchingConfigurator.MAPPER.createObjectNode();
			getArgs.put("table", pTablePath);
			final McpClient.ToolResult getResult = MameCalls.call(pClient, "get_matching_config", getArgs, pPublicationName);
			pDetail.set("defaultMatchingPolicyCheck", MatchingConfigurator.toolDetail(getArgs, getResult));
			readBack = getResult.json;
		}
		final String current = DefaultMatchingPolicyGate.currentDefault(readBack);
		final DefaultMatchingPolicyGate.Action action = DefaultMatchingPolicyGate.decide(current, pPreviewCode, pRealCodeOrNull);
		pDetail.put("defaultMatchingPolicyAction", action.name() + " (was '" + current + "')");
		if (action == DefaultMatchingPolicyGate.Action.NONE)
		{
			return;
		}
		final String newValue = action == DefaultMatchingPolicyGate.Action.CLEAR ? "" : pRealCodeOrNull;
		final ObjectNode setArgs = MatchingConfigurator.MAPPER.createObjectNode();
		setArgs.put("table", pTablePath);
		setArgs.putObject("settings").put("defaultMatchingPolicy", newValue);
		final McpClient.ToolResult setResult = MameCalls.call(pClient, "set_table_settings", setArgs, pPublicationName);
		pDetail.set("setDefaultMatchingPolicy", MatchingConfigurator.toolDetail(setArgs, setResult));
		MatchingConfigurator.requireSuccess(setResult, "set_table_settings (defaultMatchingPolicy)");

		// "Returns the full re-read MameConfig on success" (the live tool signature, task brief) - verify from
		// THAT, and only fall back to one more get_matching_config when the write's own answer does not carry it.
		String verified = DefaultMatchingPolicyGate.currentDefault(setResult.json);
		if (verified == null)
		{
			final ObjectNode reGetArgs = MatchingConfigurator.MAPPER.createObjectNode();
			reGetArgs.put("table", pTablePath);
			final McpClient.ToolResult reGetResult = MameCalls.call(pClient, "get_matching_config", reGetArgs, pPublicationName);
			pDetail.set("verifyDefaultMatchingPolicy", MatchingConfigurator.toolDetail(reGetArgs, reGetResult));
			verified = DefaultMatchingPolicyGate.currentDefault(reGetResult.json);
		}
		final String expected = newValue == null ? "" : newValue;
		final String actual = verified == null ? "" : verified;
		if (!expected.equals(actual))
		{
			throw OperationException.createError("set_table_settings for " + pTablePath + " answered success but the table's own defaultMatchingPolicy still reads '"
				+ actual + "' (expected '" + expected + "').");
		}
	}

	/** Lot MT: the project's stored {@code Project.matchingTrigger}, {@code null} when the project or the value is absent ({@link MatchingTriggerMode#parse} then reads MANUAL). */
	static String readMatchingTrigger(final Adaptation pFactory, final String pProjectCode)
	{
		final Adaptation project = MatchingConfigurator.findProject(pFactory, pProjectCode);
		return project == null ? null : project.getString(FactoryPaths.Project.MATCHING_TRIGGER);
	}

	/**
	 * Lot MT (2026-10-04): writes the project's matching trigger mode on the entity's REAL matching policy ({@code processCode} =
	 * the entity name - never the {@code <Entity>__preview} policy, which stays MANUAL by construction, {@link MatchingPreviewPolicy}):
	 * {@code matchingOnCreation} and {@code matchingOnUpdate} both 0 for {@link MatchingTriggerMode#MANUAL}, both 1 for
	 * {@link MatchingTriggerMode#REALTIME}. WHY: {@code generate_matching_policy} leaves the add-on's default (1/1, measured live on
	 * 6.3.0-rc2), so every project ran an inline matching at each batch commit of a master load. Idempotent: the stored values are read
	 * first and only the parameter(s) that differ are patched ({@code patch_matching_config}, op {@code set_execution_mode}, built by
	 * {@link MatchingTriggerMode#patchOps}); the answer is re-read to prove the write stuck. Recorded in the step detail as
	 * {@code executionMode} ({@code mode}, the values found, {@code patched}, the values after).
	 */
	static void ensureExecutionMode(final McpClient pClient, final String pTablePath, final String pDataModel, final String pModelName, final String pPolicyCode,
		final MatchingTriggerMode pMode, final MameBusinessObjects.Scope pScope, final ObjectNode pDetail) throws OperationException, McpException
	{
		final ObjectNode modeDetail = pDetail.putObject("executionMode");
		modeDetail.put("mode", pMode.name());
		modeDetail.put("wantedValue", pMode.executionValue());
		final ObjectNode getArgs = MatchingConfigurator.MAPPER.createObjectNode();
		getArgs.put("table", pTablePath);
		MatchingConfigurator.putDataModel(getArgs, pDataModel);
		final McpClient.ToolResult getResult = MameCalls.call(pClient, "get_matching_config", getArgs, pModelName);
		MatchingConfigurator.requireSuccess(getResult, "get_matching_config (execution mode)");
		final JsonNode stored = MatchingPolicyComparator.findPolicyEntry(MameAnswer.field(getResult.json, "matchingPolicies"), "processCode", pPolicyCode);
		if (stored.isMissingNode())
		{
			throw OperationException.createError("Cannot write the execution mode of matching policy " + pPolicyCode + " on " + pTablePath
				+ ": the policy was not found in the re-read configuration.");
		}
		modeDetail.put("foundOnCreation", MatchingTriggerMode.storedValue(stored, MatchingTriggerMode.ON_CREATION));
		modeDetail.put("foundOnUpdate", MatchingTriggerMode.storedValue(stored, MatchingTriggerMode.ON_UPDATE));
		final ArrayNode ops = pMode.patchOps(pPolicyCode, stored);
		if (ops.size() == 0)
		{
			modeDetail.put("patched", false);
			return;
		}
		final ObjectNode patchArgs = MatchingConfigurator.MAPPER.createObjectNode();
		patchArgs.put("table", pTablePath);
		MatchingConfigurator.putDataModel(patchArgs, pDataModel);
		patchArgs.set("operations", ops);
		MameBusinessObjects.putBusinessObject(patchArgs, pScope);
		final McpClient.ToolResult patchResult = MameCalls.call(pClient, "patch_matching_config", patchArgs, pModelName);
		modeDetail.set("patch", MatchingConfigurator.toolDetail(patchArgs, patchResult));
		MatchingConfigurator.requireSuccess(patchResult, "patch_matching_config (set_execution_mode)");
		modeDetail.put("patched", true);

		final ObjectNode reGetArgs = MatchingConfigurator.MAPPER.createObjectNode();
		reGetArgs.put("table", pTablePath);
		MatchingConfigurator.putDataModel(reGetArgs, pDataModel);
		final McpClient.ToolResult reGetResult = MameCalls.call(pClient, "get_matching_config", reGetArgs, pModelName);
		final JsonNode after = MatchingPolicyComparator.findPolicyEntry(MameAnswer.field(reGetResult.json, "matchingPolicies"), "processCode", pPolicyCode);
		final int afterOnCreation = MatchingTriggerMode.storedValue(after, MatchingTriggerMode.ON_CREATION);
		final int afterOnUpdate = MatchingTriggerMode.storedValue(after, MatchingTriggerMode.ON_UPDATE);
		modeDetail.put("afterOnCreation", afterOnCreation);
		modeDetail.put("afterOnUpdate", afterOnUpdate);
		if (afterOnCreation != pMode.executionValue() || afterOnUpdate != pMode.executionValue())
		{
			throw OperationException.createError("patch_matching_config (set_execution_mode) answered success but matching policy " + pPolicyCode + " on " + pTablePath
				+ " still reads matchingOnCreation=" + afterOnCreation + ", matchingOnUpdate=" + afterOnUpdate + " (expected " + pMode.executionValue() + " for " + pMode.name() + ").");
		}
	}

	/**
	 * EVERY recorded {@code detail} JSON of THIS project's own {@code MATCHING_CONFIG} step for {@code
	 * pEntityName}, across every status and every past attempt (a still-{@code RUNNING} or a {@code FAILED} row
	 * included, in no particular order) - empty if the step has never run at all for this project/entity. Read
	 * directly here rather than through {@link PipelineSteps} (whose own {@link PipelineSteps#isDone} only ever
	 * answers a boolean) because {@link #configureOne}'s ownership logic (class Javadoc, 2026-09-22, widened lot
	 * R8 2026-09-27) needs each attempt's own recorded detail, not just whether the LATEST one succeeded.
	 * <p>
	 * <b>Lot R8 (2026-09-27, docs/MATCHING.md &sect;39):</b> before this fix, only the single MOST RECENT row was
	 * ever read ({@code lastConfigStepDetail}, this method's own former name and shape) - a genuinely successful
	 * generate+generate+link attempt whose step later failed for an unrelated reason (class Javadoc, PersVol7/
	 * Personne) was then invisible forever, since every RETRY after the first failure records nothing but its own
	 * refusal, and a refusal's detail never carries a completed generation. Returning every row instead lets
	 * {@link #anyPriorAttemptCompletedGenerationAndLink} find that earlier success regardless of how many
	 * refusal-only attempts followed it.
	 */
	private static List<String> allConfigStepDetails(final Adaptation pFactory, final String pProjectId, final String pEntityName)
	{
		final List<String> details = new ArrayList<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(MatchingConfigurator.STEP_CONFIG) + " and ./scope="
				+ CatalogReader.quote(pEntityName));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String rowDetail = row.getString(FactoryPaths.PipelineStep.DETAIL);
				if (rowDetail != null)
				{
					details.add(rowDetail);
				}
			}
		}
		finally
		{
			rows.close();
		}
		return details;
	}

	/**
	 * {@code true} only when {@code pDetailJson} (a previous {@link #configureOne} attempt's own recorded
	 * {@code detail}, built exclusively by {@link #toolDetail} in this class - never a guess at the live
	 * server's shape) shows {@code generate_matching_policy}, {@code generate_merge_policy} AND {@code
	 * patch_matching_config} all answered success. {@code pDetailJson == null} (the step never ran, or its
	 * detail could not be parsed) is always {@code false} - resuming is opt-in, never the default.
	 * <p>
	 * Package-private (not {@code private}): unit-tested directly by {@code MatchingConfiguratorTest}, same
	 * package, same convention {@code PipelineSteps.scopeEquals} already follows.
	 */
	static boolean priorAttemptCompletedGenerationAndLink(final String pDetailJson)
	{
		if (pDetailJson == null)
		{
			return false;
		}
		final JsonNode detail;
		try
		{
			detail = MatchingConfigurator.MAPPER.readTree(pDetailJson);
		}
		catch (final java.io.IOException ex)
		{
			return false;
		}
		return MatchingConfigurator.toolDetailSucceeded(detail, "generateMatchingPolicy") && MatchingConfigurator.toolDetailSucceeded(detail, "generateMergePolicy")
			&& MatchingConfigurator.toolDetailSucceeded(detail, "linkMergePolicy");
	}

	/** Reads one of {@link #toolDetail}'s own {@code {"arguments":...,"error":bool,"result":{...}}} entries back out of a recorded step detail. */
	private static boolean toolDetailSucceeded(final JsonNode pDetail, final String pKey)
	{
		final JsonNode entry = pDetail.path(pKey);
		if (entry.path("error").asBoolean(true))
		{
			return false;
		}
		final JsonNode resultJson = entry.path("result");
		return !resultJson.isMissingNode() && MameAnswer.success(resultJson, false);
	}

	/**
	 * {@code true} when ANY entry of {@code pDetailJsons} (every past {@code MATCHING_CONFIG} step detail this
	 * project/entity ever recorded, {@link #allConfigStepDetails}, in no particular order) satisfies {@link
	 * #priorAttemptCompletedGenerationAndLink} - lot R8 (2026-09-27, docs/MATCHING.md &sect;39): unlike the
	 * pre-R8 check, which only ever looked at the SINGLE most recently recorded step, this scans every one, so an
	 * earlier attempt's genuine success is never hidden behind a later, refusal-only retry (a refusal step records
	 * no tool calls at all, so it can never itself satisfy {@link #priorAttemptCompletedGenerationAndLink} - a
	 * refusal never counts either way, exactly like the single-detail check it replaces). An empty list is always
	 * {@code false}.
	 * <p>
	 * Package-private: unit-tested directly by {@code MatchingConfiguratorTest}, same convention as {@link
	 * #priorAttemptCompletedGenerationAndLink}.
	 */
	static boolean anyPriorAttemptCompletedGenerationAndLink(final List<String> pDetailJsons)
	{
		for (final String detailJson : pDetailJsons)
		{
			if (MatchingConfigurator.priorAttemptCompletedGenerationAndLink(detailJson))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code true} when {@code pRequestedDataModel} (the {@code dataModel} argument {@link MameCalls#call}
	 * actually ended up sending for the {@code get_matching_config} call that FOUND the configuration - read back
	 * off the mutated call arguments, never re-derived) is exactly {@code pOwnDataModel} ({@link
	 * MameDataModelResolution#publicationUrn(String)} of {@code pModelName}, this project's OWN master
	 * publication, never shared with another project - {@code master.MasterGenerator}/{@code MasterNames}
	 * generate one exclusively per project). Lot R8 (2026-09-27, docs/MATCHING.md &sect;39): a configuration
	 * found while scoped to this project's own publication is, by construction, this project's own to manage -
	 * regardless of what its {@code PipelineStep} history does or does not remember - which is what actually
	 * breaks the PersVol7/Personne deadlock (class Javadoc): the table's real configuration was written by this
	 * project's own {@code MATCHING_CONFIG} attempt, but every retry after its later failure only ever recorded a
	 * refusal, so neither {@code isDoneStep} nor {@link #anyPriorAttemptCompletedGenerationAndLink} alone could
	 * ever recognise it.
	 * <p>
	 * Both arguments are compared exactly (case-sensitive string equality) - a retry that resolved to a DIFFERENT
	 * {@code AMBIGUOUS_TABLE} candidate (a module's own schema URN, never this project's publication URN itself -
	 * {@link MameDataModelResolution}'s own {@code containsWholeToken} filter only ever lets through a candidate
	 * that literally CONTAINS the publication name, never a bare unrelated URN) deliberately does not satisfy this
	 * rule - the other two ownership signals remain the fallback for that rare case. Either argument being
	 * {@code null} (no call was made with a publication scope at all) is always {@code false}.
	 * <p>
	 * Package-private: unit-tested directly by {@code MatchingConfiguratorTest}.
	 */
	static boolean ownsByDataModel(final String pRequestedDataModel, final String pOwnDataModel)
	{
		return pRequestedDataModel != null && pRequestedDataModel.equals(pOwnDataModel);
	}

	/**
	 * Which of {@link #configureOne}'s three ownership signals (if any) established that a REAL, found
	 * configuration belongs to this project - lot R8 (2026-09-27, docs/MATCHING.md &sect;39). Recorded verbatim
	 * into the step {@code detail} ({@code ownershipRule}) so a later read of {@code PipelineStep} shows WHY a
	 * given configure call resumed/rewrote rather than refused, instead of only the boolean {@code
	 * ownsExistingConfig}.
	 */
	enum OwnershipRule
	{
		/** {@code PipelineSteps.isDone} found a past, genuinely {@code DONE} {@code MATCHING_CONFIG} step for this entity (&sect;19.3, unchanged). */
		DONE_STEP,
		/** No {@code DONE} step, but SOME past attempt's own recorded detail shows generate+generate+link all succeeded ({@link #anyPriorAttemptCompletedGenerationAndLink}). */
		PRIOR_ATTEMPT,
		/** Neither of the above, but the configuration was found scoped to this project's own master publication ({@link #ownsByDataModel}) - the R8 fix. */
		OWN_PUBLICATION,
		/** None of the three signals holds - a real configuration this project never touched; {@link #configureOne} refuses outright. */
		NONE
	}

	/**
	 * The pure decision behind {@link #configureOne}'s {@code ownsExistingConfig}/{@code ownershipRule} - lot R8
	 * (2026-09-27, docs/MATCHING.md &sect;39). Checked in the same priority order the class Javadoc lists them
	 * (first past-{@code DONE} step, then an earlier generate+generate+link success, then the own-publication
	 * signal) purely for a deterministic, single reported reason when more than one happens to hold at once -
	 * {@link #configureOne} only ever consults {@code != NONE}, never the specific rule, for its own branching.
	 * <p>
	 * Package-private: unit-tested directly by {@code MatchingConfiguratorTest} against every reachable
	 * combination of the three boolean inputs.
	 */
	static MatchingConfigurator.OwnershipRule determineOwnership(final boolean pIsDoneStep, final boolean pAnyPriorAttemptOwnership,
		final boolean pOwnPublicationOwnership)
	{
		if (pIsDoneStep)
		{
			return MatchingConfigurator.OwnershipRule.DONE_STEP;
		}
		if (pAnyPriorAttemptOwnership)
		{
			return MatchingConfigurator.OwnershipRule.PRIOR_ATTEMPT;
		}
		if (pOwnPublicationOwnership)
		{
			return MatchingConfigurator.OwnershipRule.OWN_PUBLICATION;
		}
		return MatchingConfigurator.OwnershipRule.NONE;
	}

	// ------------------------------------------------------------------------------- compare-then-rewrite (2026-09-22)

	/**
	 * {@link #configureOne}'s own four possible outcomes - docs/MATCHING.md &sect;19 "configure on an existing
	 * policy: compare, then rewrite in place". {@code REFUSE} is never actually RETURNED by {@link
	 * #configureOne} (that path throws an {@link OperationException} instead, same as every other refusal in
	 * this class) - it exists here purely so {@link #decideConfigureAction} stays a total, pure function over
	 * all four combinations of its boolean inputs, independently unit-testable without having to fake a thrown
	 * exception for that one case.
	 */
	enum ConfigureDecision
	{
		/** {@code CONFIG_NOT_FOUND} (or a preview-only config, {@link MatchingPreviewPolicy#isPreviewOnly}) - generate a brand new policy. */
		GENERATE_FRESH,
		/** A real, owned configuration exists and already matches the accepted plan's translation - validate/activate only, no write. */
		RESUME_SAME,
		/** A real, owned configuration exists but differs from the accepted plan's translation - rewrite it in place ({@code apply_matching_config}). */
		REWRITE,
		/** A real configuration exists, is not a preview, and this project never finished configuring it (no completed {@code MATCHING_CONFIG} step, ever, and no in-progress-attempt evidence either) - not this generator's to touch. */
		REFUSE
	}

	/**
	 * The pure decision rule behind {@link #configureOne}'s branching (docs/MATCHING.md &sect;19) - no EBX
	 * class, no MCP call, every input already resolved by the caller:
	 * <ul>
	 * <li>{@code !pFound || pPreviewOnly} -&gt; {@link ConfigureDecision#GENERATE_FRESH} (nothing real to
	 * compare against - a fresh table, or nothing but a leftover Simulate-click preview).</li>
	 * <li>otherwise, {@code !pOwnsExistingConfig} -&gt; {@link ConfigureDecision#REFUSE} (a real configuration
	 * this project never finished configuring, ever - the same philosophy as {@code
	 * master.MasterResetEligibility}, unchanged by this lot).</li>
	 * <li>otherwise (a real, OWNED configuration) -&gt; {@link ConfigureDecision#RESUME_SAME} when {@code
	 * pStoredMatchesDesired}, {@link ConfigureDecision#REWRITE} otherwise.</li>
	 * </ul>
	 * {@code pStoredMatchesDesired} is only ever meaningful in that last case - the caller passes whatever it
	 * has (even a placeholder) when {@code pFound}/{@code pPreviewOnly} already settle the answer, since this
	 * method never reads it otherwise.
	 * <p>
	 * Package-private: unit-tested directly by {@code MatchingConfiguratorTest} against all four reachable
	 * combinations, the same convention {@link #priorAttemptCompletedGenerationAndLink} already follows.
	 */
	static ConfigureDecision decideConfigureAction(final boolean pFound, final boolean pPreviewOnly, final boolean pOwnsExistingConfig,
		final boolean pStoredMatchesDesired)
	{
		if (!pFound || pPreviewOnly)
		{
			return ConfigureDecision.GENERATE_FRESH;
		}
		if (!pOwnsExistingConfig)
		{
			return ConfigureDecision.REFUSE;
		}
		return pStoredMatchesDesired ? ConfigureDecision.RESUME_SAME : ConfigureDecision.REWRITE;
	}

	/**
	 * {@code get_matching_activation_status(table[, dataModel])}'s own answer, parsed with {@link
	 * MatchingActivationGuard#parseStatus} - the exact same {@code state}/{@code published}/{@code
	 * metadatasetExists} snapshot that class' own {@link MatchingActivationGuard#isReady} gate reads
	 * (`docs/MASTER-ZONE.md` &sect;1.1, measured live: {@code {"metadatasetExists":true,"paused":false,
	 * "published":true,"state":"ENABLED"}}). Best-effort only: a transport failure or a soft miss answers
	 * {@code null}, never throws - the one caller ({@link #configureOne}'s {@code REWRITE} branch) treats an
	 * unreadable status the same as "not ready" and falls back to the pre-2026-09-22 unconditional {@code
	 * activate_matching} call rather than block a rewrite on a status check that itself failed (the same "never
	 * let a report-only check fail the call that matters" discipline {@code MameMatchingApi}'s own {@code
	 * safeDetail}/{@code safeCompletionRate} already apply).
	 * <p>
	 * <b>2026-09-23 15:50 fix (`docs/MATCHING.md` &sect;29):</b> this used to return only the bare {@code state}
	 * string, and the caller skipped {@code activate_matching} whenever it read {@code "ENABLED"} - exactly the
	 * criterion measured live to be insufficient (a table can report {@code state:"ENABLED"} with {@code
	 * metadatasetExists:false}, and a simulate/match call on it then fails on a missing {@code Linked_Field_*}
	 * field). Returning the full {@link MatchingActivationGuard.StatusSnapshot} lets the caller apply the SAME
	 * {@link MatchingActivationGuard#isReady} readiness test {@code MatchingActivationGuard} itself uses, instead
	 * of a second, narrower copy of the same rule. Never exercised live this session (write tools forbidden
	 * either way, same as every other match-merge call in this class).
	 */
	private static MatchingActivationGuard.StatusSnapshot readActivationStatus(final McpClient pClient, final String pTablePath,
		final String pPublicationName, final ObjectNode pDetail)
	{
		final ObjectNode args = MatchingConfigurator.MAPPER.createObjectNode();
		args.put("table", pTablePath);
		try
		{
			final McpClient.ToolResult result = MameCalls.call(pClient, "get_matching_activation_status", args, pPublicationName);
			pDetail.set("activationStatusBeforeRewrite", MatchingConfigurator.toolDetail(args, result));
			if (result.error || !MameAnswer.success(result.json, true))
			{
				return null;
			}
			return MatchingActivationGuard.parseStatus(result.json);
		}
		catch (final McpException ex)
		{
			pDetail.put("activationStatusCheckError", ex.getMessage());
			return null;
		}
		catch (final OperationException ex)
		{
			// best-effort (class Javadoc): an AMBIGUOUS_TABLE this call could not resolve either is read the same
			// as any other unreadable status, never blocking the rewrite that is waiting on this check.
			pDetail.put("activationStatusCheckError", ex.getMessage());
			return null;
		}
	}

	/**
	 * {@code validate_matching_config}'s own {@code messages} array (code, path, severity, message, hint per
	 * entry, {@code matching_config_schema}'s own {@code messageCodes} sibling) - included in a refusal instead
	 * of the raw JSON, so a person reading the refusal sees what actually failed rather than an opaque blob.
	 * <p>
	 * Package-private (not {@code private}): unit-tested directly by {@code MatchingConfiguratorTest}.
	 */
	static String describeValidationMessages(final JsonNode pValidateJson)
	{
		final JsonNode messages = MameAnswer.field(pValidateJson, "messages");
		if (!messages.isArray() || messages.size() == 0)
		{
			return "no messages reported - raw answer: " + MatchingConfigurator.shortText(pValidateJson == null ? null : pValidateJson.toString());
		}
		final StringBuilder out = new StringBuilder();
		for (final JsonNode message : messages)
		{
			if (out.length() > 0)
			{
				out.append("; ");
			}
			out.append('[').append(message.path("severity").asText("?")).append("] ").append(message.path("code").asText("?"));
			final String path = message.path("path").asText(null);
			if (path != null && !path.isEmpty())
			{
				out.append(" at ").append(path);
			}
			final String text = message.path("message").asText(null);
			if (text != null && !text.isEmpty())
			{
				out.append(": ").append(text);
			}
			final String hint = message.path("hint").asText(null);
			if (hint != null && !hint.isEmpty())
			{
				out.append(" (hint: ").append(hint).append(')');
			}
		}
		return out.toString();
	}

	private static void markApplied(final Session pSession, final Adaptation pFactory, final Adaptation pPlan, final String pEntityName) throws OperationException
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(pPlan.getAdaptationName());
				value.setValue(pEntityName, FactoryPaths.MatchPlan.MATCHING_POLICY);
				value.setValue(pEntityName, FactoryPaths.MatchPlan.MERGE_POLICY);
				value.setValue("APPLIED", FactoryPaths.MatchPlan.STATUS);
				pContext.doModifyContent(pPlan, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.markMatchPlanApplied", "Matching was configured for " + pEntityName + " but marking the MatchPlan APPLIED failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH), pEntityName);
		}
	}

	// ===================================================================================================== matching/run

	/**
	 * Launches a SCOPED matching-execution run for every promoted entity whose {@code MatchPlan} is {@code
	 * APPLIED} (i.e. {@link #configure} succeeded for it), waits (bounded, see {@link MameMatchingApi}'s own
	 * Javadoc) and refreshes {@code Xref.goldenKey} once the process reports {@code DONE}.
	 * <p>
	 * <b>Coordinator review of 1cdb8bd, 2026-09-21: replaces the earlier REST-loopback design entirely.</b>
	 * The matching-execution REST endpoint the first version of this class called
	 * (`/ebx-addon-mame/rest/v1/matchingProcesses`, an unverified body combining two sources never seen
	 * together) is GONE - {@link MameMatchingApi#launchScoped} calls the add-on's own public Java API
	 * ({@code MatchingOperations.matchRecords}, found by {@code javap} on {@code ebx-addons.jar} and read
	 * from that same server's on-disk Javadoc, `docs/MATCHING.md` &sect;4.4) directly, in-process, with the
	 * CALLER's own session - no HTTP call, no agent-account credentials on this path any more.
	 * <p>
	 * <b>{@code pScopeAll}</b> selects {@link master.MatchingRunScope#all} (every record with an {@code Xref}
	 * row of this project) instead of the default {@link master.MatchingRunScope#incremental} (the most
	 * recently loaded batch, by the master table's own {@code _loadId} - see that class' own Javadoc for
	 * why there is no dedicated "master load run id" to key on instead). Either way {@code MatchingRunScope}
	 * refuses outright, by entity, rather than fall back to an unscoped predicate - matching {@code
	 * matching_guide}'s own warning (`docs/MATCHING.md` &sect;2.1) that an unscoped run discards every
	 * existing match/suspect verdict of the WHOLE table, across every policy.
	 */
	/**
	 * Distribution lot 1 (SPEC-distribution.md "Real time"): a scope-{@code all} matching run is one of the
	 * three "FULL reload" steps that pause the project's armed REALTIME distribution targets while it runs,
	 * with a CHANGED catch-up send once it is over ({@code ebx.DistributionPause}) - the far more common
	 * incremental run (scope not all) never pauses anything. The actual work is unchanged, in {@link #runInternal}.
	 */
	public static ObjectNode run(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pScopeAll, final long pMaxWaitMs)
		throws OperationException
	{
		return MatchingConfigurator.run(pSession, pFactory, pProjectCode, pScopeAll, pMaxWaitMs, null);
	}

	/**
	 * Same as {@link #run(Session, Adaptation, String, boolean, long)}, checked for a stop between two entities
	 * (lot C3, 2026-09-24, {@code ebx.MatchingRunRun}) - see {@link #configure(Session, Adaptation, String,
	 * FactoryJobs.Handle)}'s own Javadoc for the same "null for every synchronous caller, an entity already
	 * launched always finishes" contract.
	 */
	public static ObjectNode run(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pScopeAll, final long pMaxWaitMs,
		final FactoryJobs.Handle pHandle) throws OperationException
	{
		try
		{
			return MatchingConfigurator.runWithPause(pSession, pFactory, pProjectCode, pScopeAll, pMaxWaitMs, pHandle);
		}
		finally
		{
			// lot S2 (2026-10-18): an automatic merge only writes the Match and Merge metadataset, no business-table trigger fires - so the armed REALTIME targets of the
			// project are asked to sweep the metadataset's states now (also after a run that failed or was stopped half way: what it merged is merged). Returns at once.
			try
			{
				final Adaptation project = MatchingConfigurator.findProject(pFactory, pProjectCode);
				if (project != null)
				{
					DistributionDispatcher.requestSweep(project.getOccurrencePrimaryKey().format());
				}
			}
			catch (final RuntimeException ex)
			{
				LoggingCategory.getKernel().error("[mdm-factory] could not ask the distribution targets of project " + pProjectCode + " to sweep after the matching run: " + ex, ex);
			}
		}
	}

	private static ObjectNode runWithPause(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pScopeAll, final long pMaxWaitMs,
		final FactoryJobs.Handle pHandle) throws OperationException
	{
		if (!pScopeAll)
		{
			return MatchingConfigurator.runInternal(pSession, pFactory, pProjectCode, false, pMaxWaitMs, pHandle);
		}
		final Adaptation pauseProject = MatchingConfigurator.findProject(pFactory, pProjectCode);
		final String pauseProjectId = pauseProject == null ? null : pauseProject.getOccurrencePrimaryKey().format();
		// every pause is recorded durably under this owner (LoadPauseLedger): a stop before the resume below leaves the proof the module needs to lift it at its next start
		final LoadPauseLedger.Owner pauseOwner = LoadPauseLedger.open(MatchingConfigurator.STEP_RUN, pauseProjectId);
		final java.util.List<String> paused = pauseProjectId == null ? java.util.Collections.<String>emptyList()
			: DistributionPause.pause(pSession, pFactory, pauseProjectId, pauseOwner);
		try
		{
			return MatchingConfigurator.runInternal(pSession, pFactory, pProjectCode, true, pMaxWaitMs, pHandle);
		}
		finally
		{
			try
			{
				DistributionPause.resumeAndReport(pSession, pFactory, pProjectCode, pauseProjectId, paused, pauseOwner, MatchingConfigurator.STEP_RUN);
			}
			finally
			{
				pauseOwner.close();
			}
		}
	}

	static ObjectNode runInternal(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pScopeAll, final long pMaxWaitMs,
		final FactoryJobs.Handle pHandle) throws OperationException
	{
		final Adaptation project = MatchingConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (MatchingConfigurator.blank(masterDataspaceKey) || MatchingConfigurator.blank(masterDatasetName))
		{
			throw EngineRefusal.coded("missing.noMasterZone", "Project " + pProjectCode + " has no master zone yet: run POST /projects/" + pProjectCode + "/master/generate first", pProjectCode);
		}
		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		final Adaptation masterDataset = masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(masterDatasetName));
		if (masterDataset == null)
		{
			throw EngineRefusal.coded("missing.masterDatasetPath", "Master dataset " + masterDataspaceKey + "/" + masterDatasetName + " of project " + pProjectCode + " could not be found.", masterDataspaceKey + "/" + masterDatasetName);
		}

		final Map<String, String> entityIdByName = new HashMap<String, String>();
		final List<StagingModel.EntityInfo> stagingEntities = MatchingConfigurator.readEntities(pFactory, projectId, entityIdByName);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(stagingEntities);
		// parent-first matching order lot (2026-09-27, the product owner's decision): masterModel.builtEntities is already
		// ordered parent-before-child (MasterModel.orderParentsBeforeChildren), so a CHILD entity's own decision
		// tree comparing its parent-confirmation node only ever runs AFTER the parent has already been matched in
		// this same call - (before lot MT) the merge policy's own relatedTables AUTOMATIC alignment had therefore already re-pointed
		// the child FKs of any parent group that merged, before the child's tree compares them. What this ordering
		// does NOT fix on its own: parent DUPLICATES that are still only SUSPECTED (not yet merged) leave their
		// per-source child rows pointing at DIFFERENT (unmerged) parents, so the child's parent-confirmation node
		// correctly (by design) treats them as NOT_MATCHED - measured live on PersVol6 (Adresse precision 100%,
		// recall 16%, entirely attributable to the parent's own pending suspects). parentEntitiesByChild below
		// drives the informational note added to each ran CHILD's own result/step message.
		final Map<String, List<String>> parentEntitiesByChild = MatchingConfigurator.parentEntitiesByChild(masterModel);
		final Map<String, List<String>> parentsByChildForRun = ChildEntityMatching.parentsByChild(masterModel);
		final List<String> followsParentInRun = new ArrayList<String>();

		final List<String> ran = new ArrayList<String>();
		final List<String> notApplied = new ArrayList<String>();
		final List<String> refused = new ArrayList<String>();
		// lot C3 (2026-09-24): entities not yet started when a stop was asked - see the (Session,...,Handle) overload's own Javadoc.
		final List<String> stoppedBefore = new ArrayList<String>();
		// lot M4 (2026-10-03, defect 1): entities whose add-on process did NOT confirm a requested stop - may still be running
		final List<String> stopUnconfirmed = new ArrayList<String>();
		// lot MT (2026-10-04): entities whose add-on process ENDED in failure (FAILED, or CANCELLED by nobody the Factory knows) - one
		// "Entity - reason" entry each (matching.MatchingRunFailure); they are not in "ran", the others keep their own results
		final List<String> failed = new ArrayList<String>();
		final Map<String, ObjectNode> resultByEntity = new HashMap<String, ObjectNode>();

		for (final String entityName : masterModel.builtEntities)
		{
			if (pHandle != null && pHandle.cancelled())
			{
				stoppedBefore.add(entityName);
				continue;
			}
			final String entityId = entityIdByName.get(entityName);
			final Adaptation plan = MatchingConfigurator.findMatchPlan(pFactory, entityId);
			// lot M4 (2026-10-18, docs/MATCHING.md sect;59): a child entity (a member of its parent's business object) is never run on its own while the add-on's merging phase
			// fails on a member table - whatever the status of its plan, an APPLIED one included. The run of the parent covers it.
			final List<String> memberParents = parentsByChildForRun.get(entityName);
			if (!ChildEntityMatching.runsAlone(memberParents))
			{
				followsParentInRun.add(ChildEntityMatching.followsText(entityName, memberParents));
				continue;
			}
			if (plan == null || !"APPLIED".equals(plan.getString(FactoryPaths.MatchPlan.STATUS)))
			{
				// lot M2 (2026-10-17, docs/MATCHING.md sect;56): a child entity without a plan of its own is not "not applied" - it follows its parent's matching
				final List<String> runParents = parentsByChildForRun.get(entityName);
				if (runParents != null && (plan == null || !"ACCEPTED".equals(plan.getString(FactoryPaths.MatchPlan.STATUS))))
				{
					followsParentInRun.add(ChildEntityMatching.followsText(entityName, runParents));
					continue;
				}
				notApplied.add(entityName + (plan == null ? ": no matching plan" : ": plan is " + plan.getString(FactoryPaths.MatchPlan.STATUS)));
				continue;
			}
			// simulation-preview-policy lot (2026-09-22), task brief "Safety": a real matching run must never be
			// launched against a preview policy code. entityName is always a real StagingEntity.name here (never
			// carries the __preview suffix in normal operation - MasterModel builds it, this generator never does)
			// - defense in depth only, never expected to trigger.
			if (MatchingPreviewPolicy.isPreviewCode(entityName))
			{
				refused.add(entityName + ": refusing to run matching against what looks like a preview policy code");
				continue;
			}
			final String tablePath = MasterModel.tablePath(entityName);
			final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, MatchingConfigurator.STEP_RUN, entityName);
			final ObjectNode detail = MatchingConfigurator.MAPPER.createObjectNode();
			try
			{
				final AdaptationTable masterTable = masterDataset.getTable(Path.parse(tablePath));
				// lot R9 (2026-09-27, docs/MATCHING.md sect;40): pWholeTableBelongsToProject=true always, for the
				// reason MatchingRunScope's own class Javadoc "Lot R9" paragraph gives - this project's master
				// dataset/dataspace (read a few lines above from Project.masterDataspace/masterDataset) is
				// exclusive to this one project. KeyType.NUMERIC always (the master primary key is always the
				// plain auto-increment id, master.MasterModel's own design, never a string) - but the field's own
				// NAME is read live off the table's real primary-key spec, never hardcoded to "id" (coordinator
				// review, same day: a bare true() predicate was tried first and measured live to be an invalid
				// EBX XPath predicate on this same table - see MatchingRunScope's own class Javadoc).
				final String primaryKeyField = MatchingConfigurator.primaryKeyFieldOf(masterTable, tablePath);
				final MatchingRunScope.Decision scope = pScopeAll
					? MatchingRunScope.all(MatchingConfigurator.masterKeysOf(pFactory, projectId, tablePath), true, primaryKeyField, MatchingRunScope.KeyType.NUMERIC)
					: MatchingRunScope.incremental(MatchingConfigurator.mostRecentLoadId(masterDataset, tablePath));
				if (!scope.scoped)
				{
					throw EngineRefusal.coded("conflict.refused", scope.refusal);
				}
				detail.put("scopeMode", pScopeAll ? "ALL" : "INCREMENTAL");
				detail.put("xpathPredicate", scope.xpathPredicate);
				// lot M4 (2026-10-03, defect 3): state the scope plainly - how many records are pivots out of how many the table holds
				// (one cheap COUNT each, before the run changes anything; for "all" the predicate IS the whole table, one count only).
				final long scopeTotal = MatchingConfigurator.countRecords(masterTable, null);
				final long scopePivots = pScopeAll ? scopeTotal : MatchingConfigurator.countRecords(masterTable, scope.xpathPredicate);
				final String scopeSentence = MatchingRunScope.describe(pScopeAll, scopePivots, scopeTotal);
				final ObjectNode scopeNode = detail.putObject("scope");
				scopeNode.put("mode", pScopeAll ? "ALL" : "INCREMENTAL");
				scopeNode.put("pivots", scopePivots);
				scopeNode.put("totalRecords", scopeTotal);
				scopeNode.put("sentence", scopeSentence);
				// lot M8a: the candidate-filter verdict of the plan being run, and the JVM option's state - a run that compares by weighted search says so up front
				final String candidateSearchWarning = MatchingConfigurator.recordCandidateSearch(detail, MatchingConfigurator.requirementsOrNull(plan.getString(FactoryPaths.MatchPlan.REQUIREMENTS)));

				// lot R12 (2026-09-27, docs/MATCHING.md sect;41, live evidence A): self-heal the boot-race defect
				// (CLAUDE.md sect;8 "Recursive load", MatchingActivationGuard's own class Javadoc) BEFORE launching
				// matchRecords - a Tomcat restart that hits it leaves a table's matching trigger unattached even
				// though its MatchPlan is already APPLIED (a previously-successful configure), and matchRecords then
				// refuses outright ("Please check the Table activation and settings configuration") with nothing in
				// this step's own detail to act on. ensureActivatedBeforeRun is a single read when the table is
				// already ready (the common case) - never load-bearing latency on a healthy instance.
				final String activationNote = MatchingActivationGuard.ensureActivatedBeforeRun(pSession, pFactory, pProjectCode, tablePath);
				if (activationNote != null)
				{
					detail.put("activationRepaired", true);
					detail.put("activationNote", activationNote);
				}

				// lot M4 (2026-10-18, docs/MATCHING.md sect;59): the main table of a business object is run only when every member holds a default matching policy and a merge
				// policy - the add-on refuses the run otherwise ("The default matching policy is not defined" for a member). A project configured before this lot is completed here.
				if (!masterModel.childTablePaths(entityName).isEmpty())
				{
					final List<String> memberWrites;
					try
					{
						memberWrites = MameBusinessObjects.ensureMembersBeforeRun(pFactory, McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE), pProjectCode,
							MasterNames.modelName(pProjectCode), tablePath, detail);
					}
					catch (final McpException ex)
					{
						throw EngineRefusal.codedWithCause("external.matchingCall", "The member tables of the business object of " + tablePath + " could not be checked before the run: " + ex.getMessage(), ex, "get_business_object_config", tablePath);
					}
					if (!memberWrites.isEmpty())
					{
						MatchingConfigurator.putStrings(detail, "memberPoliciesWritten", memberWrites);
					}
				}

				// volume-guards lot (2026-09-28, task item 3): a job-driven call (pHandle != null) waits to the
				// process' own terminal state, no wall-clock cap - see UNBOUNDED_WAIT_MS's own Javadoc. A
				// synchronous caller (REST route, workflow bean, pHandle == null) keeps the bounded pMaxWaitMs.
				final long effectiveMaxWaitMs = pHandle != null ? MatchingConfigurator.UNBOUNDED_WAIT_MS : pMaxWaitMs;
				final MameMatchingApi.RunResult runResult;
				// lot MT: the instant this entity's process starts - the ERROR lines of ebx-addon-mame.log written since are its reason if it fails
				final long processStartedMs = System.currentTimeMillis();
				try
				{
					runResult = MameMatchingApi.launchScoped(pSession, masterTable, scope.xpathPredicate, entityName, effectiveMaxWaitMs, pHandle);
				}
				catch (final LinkageError ex)
				{
					// see ebx.MameMatchingApi's own Javadoc: the guard belongs here, at the caller's first reference to that
					// class, not inside its own method bodies - reasoned from JVM class-loading semantics, not tested
					throw EngineRefusal.codedWithCause("missing.mameApi", "The Match and Merge Java API is not available on this instance (add-on not installed?): " + ex, ex);
				}
				final ObjectNode resultNode = MatchingConfigurator.MAPPER.createObjectNode();
				resultNode.put("status", runResult.status);
				resultNode.put("completionRate", runResult.completionRate);
				resultNode.put("durationMs", runResult.durationMs);
				resultNode.put("detail", runResult.detail);
				if (runResult.stop != null)
				{
					// lot M4 (2026-10-03, defect 1): what the add-on answered to the stop request - never only "stopped"
					final ObjectNode stopNode = resultNode.putObject("stop");
					stopNode.put("requested", runResult.stop.requested);
					stopNode.put("confirmed", runResult.stop.confirmed);
					stopNode.put("finalStatus", runResult.stop.finalStatus);
					stopNode.put("waitedMs", runResult.stop.waitedMs);
					if (runResult.stop.note != null)
					{
						stopNode.put("note", runResult.stop.note);
					}
					stopNode.put("sentence", runResult.stop.sentence());
				}
				if (!runResult.reportNotes.isEmpty())
				{
					// getDetail()/getCompletionRate()/getDuration() can each throw once the process has genuinely
					// finished (ebx.MameMatchingApi's own class Javadoc, 2026-09-22) - surfaced here rather than
					// swallowed, but never turned into a refusal: runResult.status (from getMatchingStatus() alone)
					// already carries the real outcome.
					MatchingConfigurator.putStrings(resultNode, "reportNotes", runResult.reportNotes);
				}
				detail.set("process", resultNode);

				// parent-first matching order lot (2026-09-27): entityName's own parent(s) - among those PROMOTED and
				// FK-referenced - that were ALSO run in THIS SAME call, earlier in this loop (parent-before-child
				// order guarantees "earlier" whenever a note is due). `ran` only ever holds entities whose own
				// PipelineStep already ended successfully, so checking it here (rather than resultByEntity, whose
				// value carries no status) is enough.
				final List<String> parentEntities = parentEntitiesByChild.get(entityName);
				String parentSuspectNote;
				try
				{
					parentSuspectNote = MatchingConfigurator.parentPendingSuspectNote(repository, masterDataspaceKey, masterDatasetName, entityName, parentEntities, ran);
				}
				catch (final RuntimeException ex)
				{
					// lot R11 (2026-09-27, live PersVol7 step 608, kernel.log 21:58:03,943): "ClassCastException: BigDecimal
					// cannot be cast to String" from MatchingResultsBuilder#safeGetString (fixed the same lot - its own
					// Javadoc has the full stack) used to propagate straight out of this method, landing in the
					// RuntimeException catch below and marking this step FAILED even though runResult (above) had
					// already committed the matching run itself. This note is purely informational (task brief) and
					// must never do that - best-effort, same [mdm-factory]-prefixed kernel.log convention as every
					// other best-effort follow-up in this class.
					LoggingCategory.getKernel()
						.error("[mdm-factory] pending-parent-suspects note failed for " + entityName + " (project " + pProjectCode + ") after a successful matching run", ex);
					parentSuspectNote = MatchingConfigurator.pendingSuspectNoteOnFailure(ex.getClass());
				}
				if (parentSuspectNote != null)
				{
					resultNode.put("parentPendingSuspectsNote", parentSuspectNote);
				}
				final String noteSuffix = (parentSuspectNote == null ? "" : " " + parentSuspectNote)
					+ (candidateSearchWarning == null ? "" : " WARNING: " + candidateSearchWarning);

				if (runResult.done())
				{
					final GoldenKeyRefresher.Result refresh = GoldenKeyRefresher.refresh(pSession, pFactory, projectId, tablePath, masterDataspaceKey, masterDatasetName);
					final ObjectNode refreshNode = refresh.describe(MatchingConfigurator.MAPPER.createObjectNode()); // lot V3-M3: + scanMs, readMode
					resultNode.set("goldenKeyRefresh", refreshNode);
					// wave 2 (2026-09-30): the matching-results block of the wizard is computed HERE, once, and stored in this row's
					// detail (MatchResultsSnapshot/MatchingResultsStore) - the wizard only reads it. Best-effort: no results, or a
					// failure computing them, never turns a finished run into a failed one (the block then reads MISSING and offers a refresh).
					final String resultsJson = MatchingResultsStore.compute(pSession, pFactory, pProjectCode, projectId, entityName);
					MatchingResultsStore.bump(projectId);
					// run texts i18n (2026-10-18): the message keeps its English and carries the code of its sentence (and of the scope sentence inside it), so the wizard words it in
					// the viewer's language; a reader that ignores the marker (REST, MCP, the cockpit) sees the same English as before
					PipelineSteps.end(pSession, pFactory, stepId, true,
						RunTexts.matchingFinished(entityName, runResult.status, refresh.set, refresh.cleared, refresh.unchanged,
							RunTexts.matchingScope(pScopeAll, scopePivots, scopeTotal).stored(), TextLimits.truncate(noteSuffix, 400)).stored(),
						resultsJson == null ? detail.toString() : MatchResultsSnapshot.mergeIntoDetail(detail.toString(), resultsJson));
				}
				else
				{
					final boolean stoppedByHandle = pHandle != null && pHandle.cancelled();
					if (MatchingRunFailure.isFailure(runResult.status, stoppedByHandle))
					{
						// lot MT (2026-10-04, Vol1mF): a terminal FAILED (or a CANCELLED nobody here asked for) is NOT a timeout - it used to read
						// "still FAILED after 315360000000ms" and the job ended DONE. The step is FAILED with the add-on's own reason, read from
						// ebx-addon-mame.log (the process answers none through its API); the job fails at the end of the loop, the other
						// entities carry on and keep their results.
						final String reason = AddonLog.reasonSince(processStartedMs);
						final String failureMessage = MatchingRunFailure.stepMessage(entityName, runResult.status, runResult.completionRate, reason, AddonLog.logHint(),
							scopeSentence + noteSuffix);
						resultNode.put("goldenKeyRefreshSkipped", "process " + runResult.status + ": Xref.goldenKey NOT refreshed.");
						final ObjectNode failureNode = resultNode.putObject("failure");
						failureNode.put("status", runResult.status);
						failureNode.put("completionRate", runResult.completionRate);
						if (reason != null)
						{
							failureNode.put("reason", reason);
						}
						failureNode.put("source", AddonLog.logHint());
						PipelineSteps.end(pSession, pFactory, stepId, false, failureMessage, detail.toString());
						failed.add(MatchingRunFailure.entry(entityName, reason, runResult.status, runResult.completionRate));
						resultByEntity.put(entityName, resultNode);
						continue;
					}
					// volume-guards lot (2026-09-28, task item 3): this step must never read ok=true when the
					// process is not DONE - GoldenKeyRefresher.refresh did NOT run, so the next thing to touch this
					// table (another entity of this same run, a later job, a person) must be able to tell from the
					// step's own status alone, not only from its message text. A job-driven call (pHandle != null)
					// only ever reaches this branch by being stopped (cancelled()) - see effectiveMaxWaitMs's own
					// comment above - so its message says so rather than naming a wait that was never really bounded.
					// lot MT: only a NON-terminal status (or a stop) reaches here now, so "still <status> after Nms" is true.
					final String why = stoppedByHandle ? "stopped (" + pHandle.stopReason() + ")" : "still " + runResult.status + " after " + effectiveMaxWaitMs + "ms";
					resultNode.put("goldenKeyRefreshSkipped",
						"process " + why + ": Xref.goldenKey NOT refreshed" + (stoppedByHandle ? "." : " - re-run POST matching/run later once it finishes."));
					// lot M4 (defect 1): say whether the add-on really stopped - a stopped job that leaves the add-on's threads running
					// is the worst kind of "stopped"
					final String stopSentence = runResult.stop == null ? "" : " " + runResult.stop.sentence();
					PipelineSteps.end(pSession, pFactory, stepId, false,
						"Matching run on " + entityName + " " + why + " - Xref.goldenKey NOT refreshed." + stopSentence + " " + scopeSentence + noteSuffix,
						detail.toString());
					if (runResult.stopUnconfirmed())
					{
						stopUnconfirmed.add(entityName);
					}
				}
				resultByEntity.put(entityName, resultNode);
				ran.add(entityName);
			}
			catch (final OperationException ex)
			{
				// lot R9 (2026-09-27, task brief): a failed run step used to leave its full stack trace nowhere but
				// the exception's own (often terse) message - see the RuntimeException catch below for the live
				// failure (a bare ClassCastException) this was written against. Same [mdm-factory]-prefixed
				// kernel.log convention as ebx.MatchingConfigurator#configureOne's own catches (docs/MATCHING.md sect;38.2).
				LoggingCategory.getKernel().error("[mdm-factory] matching/run failed for " + tablePath + " (entity " + entityName + ", project " + pProjectCode + ")", ex);
				PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
				refused.add(entityName + ": " + ex.getMessage());
			}
			catch (final RuntimeException ex)
			{
				// a RUNNING step must never be left behind by an exception (coordinator review, 2026-09-22: the
				// MatchingOperationProcess.getCurrentPhase() NPE fixed in ebx.MameMatchingApi used to propagate
				// straight through this method with no catch for it at all, leaving PipelineStep id 78 RUNNING
				// forever although the add-on's own run had genuinely finished) - same discipline
				// ebx.MatchingConfigurator.configureOne already applies to its own translator RuntimeException.
				// lot R9 (2026-09-27, task brief, live PersVol7 step 596 - "ClassCastException: class
				// java.math.BigDecimal cannot be cast to class java.lang.String" from a quoted-string predicate over
				// a numeric id field): the full stack trace now always reaches kernel.log too, not only this one
				// message string - same [mdm-factory]-prefixed convention as #configureOne (docs/MATCHING.md sect;38.2).
				final String message = ex.getClass().getName() + ": " + ex.getMessage();
				LoggingCategory.getKernel().error("[mdm-factory] matching/run failed for " + tablePath + " (entity " + entityName + ", project " + pProjectCode + ")", ex);
				PipelineSteps.end(pSession, pFactory, stepId, false, message, detail.toString());
				refused.add(entityName + ": " + message);
			}
		}

		final ObjectNode out = MatchingConfigurator.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		MatchingConfigurator.putStrings(out, "ran", ran);
		MatchingConfigurator.putStrings(out, "notApplied", notApplied);
		MatchingConfigurator.putStrings(out, "followsParent", followsParentInRun);
		MatchingConfigurator.putStrings(out, "refused", refused);
		MatchingConfigurator.putStrings(out, "stoppedBefore", stoppedBefore);
		MatchingConfigurator.putStrings(out, "stopUnconfirmed", stopUnconfirmed);
		// lot MT: "Entity - reason" per entity whose add-on process failed (matching.MatchingRunFailure#entry); read by ebx.MatchingRunRun to fail the job
		MatchingConfigurator.putStrings(out, "failed", failed);
		final ObjectNode resultsOut = out.putObject("results");
		for (final Map.Entry<String, ObjectNode> entry : resultByEntity.entrySet())
		{
			resultsOut.set(entry.getKey(), entry.getValue());
		}
		return out;
	}

	// ---------------------------------------------------------- parent-first matching order (lot R6, 2026-09-27)

	/**
	 * Every promoted CHILD entity of {@code pMasterModel} mapped to the PARENT entities its own foreign key(s)
	 * target - see the pure overload below for the actual logic and its own unit tests.
	 */
	static Map<String, List<String>> parentEntitiesByChild(final MasterModel.Result pMasterModel)
	{
		final List<String[]> edges = new ArrayList<String[]>();
		for (final MasterModel.ForeignKeyInfo foreignKey : pMasterModel.foreignKeys)
		{
			edges.add(new String[] { foreignKey.entityName, foreignKey.targetEntityName });
		}
		return MatchingConfigurator.parentEntitiesByChild(pMasterModel.builtEntities, edges);
	}

	/**
	 * Pure form of {@link #parentEntitiesByChild(MasterModel.Result)} (lot R6, 2026-09-27, unit-tested in {@code
	 * MatchingConfiguratorTest} without any {@code master}-package class): {@code pEdges} is one {@code {entityName,
	 * targetEntityName}} pair per foreign key. Both ends of an edge must be in {@code pBuiltEntities} (the same
	 * restriction {@code MasterModel.orderParentsBeforeChildren} itself already applies when building the
	 * dependency graph this ordering relies on) and a self-reference ({@code targetEntityName.equals(entityName)})
	 * carries no parent-first ordering constraint, so both are excluded - same rule as that method's own dependency
	 * graph. Insertion order of both the map and each child's own parent list follows {@code pEdges}' order, a
	 * target listed only once per child even if named by several distinct edges.
	 */
	static Map<String, List<String>> parentEntitiesByChild(final List<String> pBuiltEntities, final List<String[]> pEdges)
	{
		final Set<String> built = new LinkedHashSet<String>(pBuiltEntities);
		final Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
		for (final String[] edge : pEdges)
		{
			final String entityName = edge[0];
			final String targetEntityName = edge[1];
			if (targetEntityName == null || targetEntityName.equals(entityName) || !built.contains(entityName) || !built.contains(targetEntityName))
			{
				continue;
			}
			List<String> parents = out.get(entityName);
			if (parents == null)
			{
				parents = new ArrayList<String>();
				out.put(entityName, parents);
			}
			if (!parents.contains(targetEntityName))
			{
				parents.add(targetEntityName);
			}
		}
		return out;
	}

	/**
	 * The informational note (task brief, domain-neutral wording built only from entity names and a count) for ONE
	 * child entity that just ran, or null when there is nothing to say: none of {@code pParentEntities} was ALSO
	 * run in this same call ({@code pRanSoFar}, entities already ended successfully earlier in the SAME loop -
	 * parent-before-child order, {@code MasterModel.orderParentsBeforeChildren}, guarantees a parent that ran at
	 * all ran BEFORE this child), or the parent(s) that did have no pending suspects. Several qualifying parents
	 * are joined with a space - not expected in practice (one child usually has one promoted parent) but never
	 * silently drops a note for a second one.
	 */
	static String parentPendingSuspectNote(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pChildEntityName, final List<String> pParentEntities, final List<String> pRanSoFar)
	{
		if (pParentEntities == null || pParentEntities.isEmpty())
		{
			return null;
		}
		final StringBuilder note = new StringBuilder();
		for (final String parentEntity : pParentEntities)
		{
			if (!pRanSoFar.contains(parentEntity))
			{
				continue;
			}
			final int pendingCount = MatchingConfigurator.countPendingSuspectPairs(pRepository, pMasterDataspaceKey, pMasterDatasetName,
				MasterModel.tablePath(parentEntity));
			final String parentNote = MatchingConfigurator.buildPendingSuspectNote(parentEntity, pChildEntityName, pendingCount);
			if (parentNote != null)
			{
				if (note.length() > 0)
				{
					note.append(' ');
				}
				note.append(parentNote);
			}
		}
		return note.length() == 0 ? null : note.toString();
	}

	/**
	 * Pure (task brief): the wording is domain-neutral, built only from entity names and a count - never assumes
	 * any business meaning for either entity. Null when there is nothing pending to mention ({@code
	 * pPendingCount <= 0}). Unit-tested directly in {@code MatchingConfiguratorTest}.
	 */
	static String buildPendingSuspectNote(final String pParentEntityName, final String pChildEntityName, final int pPendingCount)
	{
		if (pPendingCount <= 0)
		{
			return null;
		}
		return pPendingCount + " " + pParentEntityName + " pair(s) await a decision: cross-source " + pChildEntityName
			+ " duplicates are found only once they are merged - run matching on " + pChildEntityName + " again after deciding them.";
	}

	/**
	 * Pure (lot R11, 2026-09-27, unit-tested in {@code MatchingConfiguratorTest}): the note {@link #runInternal}
	 * records for {@code entityName} when {@link #parentPendingSuspectNote} itself throws - see that call site's
	 * own comment for the live crash this guards against. Never {@code null}, so the caller can always show it.
	 */
	static String pendingSuspectNoteOnFailure(final Class<? extends Throwable> pExceptionClass)
	{
		return "pending parent pairs could not be counted: " + pExceptionClass.getName();
	}

	/**
	 * Counts the PENDING SUSPECT PAIRS of one master table: the rows of its metadataset's {@code Suspected} table, one row per
	 * pair. Wave 2 (2026-09-30): this used to count the GOLDEN {@code RecordMetadata} rows flagged {@code hasSuspects} - a count
	 * of records, not pairs (each pair is flagged on BOTH of its records) - yet the note it feeds said "pair(s)": measured on
	 * Vol100k, 46 663 goldens for 33 374 pairs ({@code Personne}), 17 210 for 8 958 ({@code Adresse}). Neither the goldens nor
	 * {@code SUM(suspectedCount)} (66 748, twice the pairs) is the pair count; {@code Suspected} is (checked against the ground
	 * truth). Never throws: a missing metadataset or table (matching never activated on the parent, an older add-on build) is
	 * silently 0, the discipline {@link MatchingResultsBuilder#metadatasetTable} documents for every reader of it. {@code
	 * RequestResult.getSize()} is EBX's own count primitive (no session, no row materialised) - CLAUDE.md sec 9 volume lot.
	 */
	private static int countPendingSuspectPairs(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pTablePath)
	{
		final AdaptationTable table = MatchingResultsBuilder.metadatasetTable(pRepository, pMasterDataspaceKey, pMasterDatasetName, pTablePath, "Suspected");
		if (table == null)
		{
			return 0;
		}
		try
		{
			final RequestResult rows = table.createRequestResult(null);
			try
			{
				return rows.getSize();
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return 0;
		}
	}

	// ==================================================================================== matching/refresh-golden-keys

	/**
	 * Re-reads {@code RecordMetadata} and rewrites a stale {@code Xref.goldenKey} for every promoted entity
	 * of the project (or just {@code pEntityName} when given), WITHOUT launching a new matching run
	 * (2026-09-23, "Keeping Xref.goldenKey fresh") - the on-demand/scheduled counterpart of the refresh
	 * {@link #run} already performs as a side effect of a `DONE` process. Calls the exact same {@link
	 * GoldenKeyRefresher#refresh} {@link #run} calls, once per target entity - no {@code
	 * MatchingOperations}/{@code MameMatchingApi} call anywhere in this method, so it is safe to run far more
	 * often than a real matching pass (a steward's Unmerge/Change-state click through the add-on's own
	 * standard services, or any other drift, leaves no hook to react to - see `docs/MATCHING.md`).
	 * <p>
	 * Shared by two callers: {@code FactoryResource}'s {@code POST
	 * /projects/{code}/matching/refresh-golden-keys[?entity=<name>]} route (administrators only, like every
	 * other write route) and {@code com.on.ebx.factory.scheduler.GoldenKeyRefreshScheduledTask} (the Task
	 * Scheduler task, registered at runtime through the Scheduler REST API - CLAUDE.md &sect;2 - never
	 * through {@code module.xml}) - a manual admin click and a scheduled run behave identically.
	 *
	 * @param pEntityName blank/null refreshes every promoted entity of the project; a specific entity name
	 *            restricts it to that one (refused with {@link OperationException} if it is not a promoted
	 *            entity of the project - the same "name a real thing or refuse" discipline {@link #simulate}
	 *            already applies to its own {@code entity} parameter).
	 */
	public static ObjectNode refreshGoldenKeys(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName)
		throws OperationException
	{
		final Adaptation project = MatchingConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (MatchingConfigurator.blank(masterDataspaceKey) || MatchingConfigurator.blank(masterDatasetName))
		{
			throw EngineRefusal.coded("missing.noMasterZone", "Project " + pProjectCode + " has no master zone yet: run POST /projects/" + pProjectCode + "/master/generate first", pProjectCode);
		}

		final Map<String, String> entityIdByName = new HashMap<String, String>();
		final List<StagingModel.EntityInfo> stagingEntities = MatchingConfigurator.readEntities(pFactory, projectId, entityIdByName);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(stagingEntities);

		final List<String> targetEntities = new ArrayList<String>();
		if (MatchingConfigurator.blank(pEntityName))
		{
			targetEntities.addAll(masterModel.builtEntities);
		}
		else if (masterModel.builtEntities.contains(pEntityName))
		{
			targetEntities.add(pEntityName);
		}
		else
		{
			throw EngineRefusal.coded("conflict.notPromoted", "Entity " + pEntityName + " is not a promoted master entity of project " + pProjectCode, pEntityName, pProjectCode);
		}

		final List<String> refreshed = new ArrayList<String>();
		final List<String> refused = new ArrayList<String>();
		final ObjectNode resultsOut = MatchingConfigurator.MAPPER.createObjectNode();
		for (final String entityName : targetEntities)
		{
			final String tablePath = MasterModel.tablePath(entityName);
			final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, MatchingConfigurator.STEP_REFRESH_GOLDEN_KEYS, entityName);
			try
			{
				final GoldenKeyRefresher.Result refresh = GoldenKeyRefresher.refresh(pSession, pFactory, projectId, tablePath, masterDataspaceKey, masterDatasetName);
				final ObjectNode refreshNode = refresh.describe(MatchingConfigurator.MAPPER.createObjectNode()); // lot V3-M3: + scanMs, readMode
				resultsOut.set(entityName, refreshNode);
				PipelineSteps.end(pSession, pFactory, stepId, true, "Xref.goldenKey refreshed for " + entityName + " (" + refresh.set + " set, " + refresh.cleared
					+ " cleared, " + refresh.unchanged + " unchanged).", refreshNode.toString());
				refreshed.add(entityName);
			}
			catch (final OperationException ex)
			{
				PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), null);
				refused.add(entityName + ": " + ex.getMessage());
			}
			catch (final RuntimeException ex)
			{
				// a RUNNING step must never be left behind by an exception - same discipline #run already
				// applies to its own per-entity loop (see that method's own comment for the live bug this
				// guards against)
				final String message = ex.getClass().getName() + ": " + ex.getMessage();
				PipelineSteps.end(pSession, pFactory, stepId, false, message, null);
				refused.add(entityName + ": " + message);
			}
		}

		final ObjectNode out = MatchingConfigurator.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		MatchingConfigurator.putStrings(out, "refreshed", refreshed);
		MatchingConfigurator.putStrings(out, "refused", refused);
		out.set("results", resultsOut);
		return out;
	}

	// ================================================================================================= matching/simulate

	/**
	 * {@code POST /projects/{code}/matching/simulate?entity=..&amp;masterKey=..} - a zero-risk read of what
	 * the entity's configured policy would decide for one existing master record, via
	 * {@link MameMatchingApi#simulate} ({@code MatchingOperations.simulateMatch(Adaptation, String)}:
	 * "matching results are not persisted into the MatchResult table", the Java API's own Javadoc, quoted in
	 * `docs/MATCHING.md` &sect;4.4). Requires an {@code APPLIED} {@code MatchPlan} (the policy must actually
	 * be configured and activated first) but never writes anything itself - a safe way for a human to see
	 * the policy work before trusting a real {@link #run}.
	 * <p>
	 * <b>2026-09-22 23:30, self-heal-simulate-activation lot</b>: calls {@code MatchingActivationGuard
	 * #ensureActivatedBeforeSimulate} before the first {@link MameMatchingApi#simulate} - the same boot-race
	 * self-heal {@code ebx.MatchPlanSimulationRunner#simulate} runs, so this REST route never hits the raw
	 * add-on exception either (`docs/MATCHING.md` &sect;13.7). A repair is reported back to the caller as
	 * {@code activationNote} on the returned JSON.
	 * <p>
	 * <b>2026-09-23 - unaffected by the ORDER defect fixed the same day on the preview path</b> (`docs/MATCHING.md`
	 * &sect;13.8): this route requires {@code APPLIED} outright (refused a few lines above otherwise) and never
	 * calls {@code MatchingPreviewPolicy.ensure}, so there was never an "ensure vs. activation-check" ordering
	 * question here - the config is expected to already exist. It keeps using {@code
	 * ensureActivatedBeforeSimulate}'s own self-resolving {@code dataModel} lookup, unchanged; only {@code
	 * MatchingActivationGuard#decide}'s own gate changed under it - first (2026-09-23 07:45) to stop refusing on
	 * {@code published}/{@code metadatasetExists} before ever attempting a repair, then again (2026-09-23 15:50,
	 * &sect;29) to require {@code metadatasetExists} as part of what counts as READY, since {@code
	 * state == ENABLED} alone was measured live not to guarantee it. Both changes only ever make this route
	 * MORE likely to (successfully) call {@code activate_matching} on a table that needs it, never less.
	 */
	public static ObjectNode simulate(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName, final String pMasterKey)
		throws OperationException
	{
		final Adaptation project = MatchingConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (MatchingConfigurator.blank(masterDataspaceKey) || MatchingConfigurator.blank(masterDatasetName))
		{
			throw EngineRefusal.coded("missing.noMasterZone", "Project " + pProjectCode + " has no master zone yet: run POST /projects/" + pProjectCode + "/master/generate first", pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Map<String, String> entityIdByName = new HashMap<String, String>();
		MatchingConfigurator.readEntities(pFactory, projectId, entityIdByName);
		final String entityId = entityIdByName.get(pEntityName);
		final Adaptation plan = MatchingConfigurator.findMatchPlan(pFactory, entityId);
		if (plan == null || !"APPLIED".equals(plan.getString(FactoryPaths.MatchPlan.STATUS)))
		{
			throw EngineRefusal.coded("missing.noAppliedPlan", "Entity " + pEntityName + " has no APPLIED matching plan yet: run POST /projects/" + pProjectCode + "/matching/configure first.", pEntityName);
		}

		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		final Adaptation masterDataset = masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(masterDatasetName));
		if (masterDataset == null)
		{
			throw EngineRefusal.coded("missing.masterDatasetPath", "Master dataset " + masterDataspaceKey + "/" + masterDatasetName + " of project " + pProjectCode + " could not be found.", masterDataspaceKey + "/" + masterDatasetName);
		}
		final AdaptationTable masterTable = masterDataset.getTable(Path.parse(MasterModel.tablePath(pEntityName)));
		final Adaptation record = masterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pMasterKey));
		if (record == null)
		{
			throw EngineRefusal.coded("missing.masterRecord", "No record " + pMasterKey + " in " + MasterModel.tablePath(pEntityName) + " of " + masterDataspaceKey + "/" + masterDatasetName, pMasterKey, MasterModel.tablePath(pEntityName));
		}

		// self-heal-simulate-activation lot (2026-09-22): same boot-race self-heal as ebx.MatchPlanSimulationRunner
		// #simulate, called before the FIRST simulateMatch below - see ebx.MatchingActivationGuard's own class
		// Javadoc for the full defect (measured live twice on Company, 15:11 and 23:26, both right after a Tomcat
		// restart) and exactly which situations it repairs vs. refuses outright.
		final String activationNote = MatchingActivationGuard.ensureActivatedBeforeSimulate(pSession, pFactory, pProjectCode, MasterModel.tablePath(pEntityName));

		final MameMatchingApi.SimulationOutcome outcome;
		try
		{
			outcome = MameMatchingApi.simulate(pSession, record, pEntityName);
		}
		catch (final LinkageError ex)
		{
			// see ebx.MameMatchingApi's own Javadoc: the guard belongs here, at the caller's first reference to that
			// class, not inside its own method bodies - reasoned from JVM class-loading semantics, not tested
			throw EngineRefusal.codedWithCause("missing.mameApi", "The Match and Merge Java API is not available on this instance (add-on not installed?): " + ex, ex);
		}
		final ObjectNode out = MatchingConfigurator.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("entity", pEntityName);
		out.put("masterKey", pMasterKey);
		if (activationNote != null)
		{
			out.put("activationNote", activationNote);
		}
		final ArrayNode matches = out.putArray("matches");
		for (final MameMatchingApi.SimulationMatch match : outcome.matches)
		{
			final ObjectNode matchNode = matches.addObject();
			matchNode.put("targetRecordPrimaryKey", match.targetRecordPrimaryKey);
			matchNode.put("pivotRecordPrimaryKey", match.pivotRecordPrimaryKey);
			matchNode.put("result", match.result);
		}
		return out;
	}

	// =========================================================================================== matching/survivorship

	/**
	 * {@code POST /projects/{code}/matching/survivorship?entity=..} - trusted-sources lot, 2026-09-22
	 * (`docs/MATCHING.md` "Survivorship and trusted sources"): re-applies {@link
	 * MatchingPolicyTranslator#buildMergePolicy}'s desired trusted-source/survivor shape onto an ALREADY
	 * -CONFIGURED table's merge policy, WITHOUT deleting or regenerating the decision tree {@code
	 * generate_matching_policy} built (which {@link #configureOne} would otherwise have to redo, losing any
	 * manual tuning). Requires a matching configuration to already exist for the entity (run {@code
	 * matching/configure} first on a fresh entity) - this route only ever PATCHES chains/survivors, it never
	 * creates the merge policy itself.
	 * <p>
	 * Same {@code dataModel} discipline as {@link #configureOne} (`docs/MATCHING.md` &sect;28, 2026-09-23):
	 * every call, {@code set_trusted_sources} included (an earlier task brief had this route OMIT it there -
	 * corrected, see {@link #configureOne}'s own class Javadoc), goes through {@link MameCalls#call}, which
	 * sends {@code dataModel = }{@link MameDataModelResolution#publicationUrn(String)} unconditionally.
	 * <p>
	 * Never exercised live (no EBX access this session) - {@link MergeConfigPatchPlanner}'s own op-list logic
	 * is unit-tested against a hand-built fixture, not against a real {@code get_matching_config} answer for a
	 * merge policy (this session never captured one - see the module's own final report).
	 */
	public static ObjectNode applySurvivorship(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName)
		throws OperationException
	{
		final Adaptation project = MatchingConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Map<String, String> entityIdByName = new HashMap<String, String>();
		MatchingConfigurator.readEntities(pFactory, projectId, entityIdByName);
		final String entityId = entityIdByName.get(pEntityName);
		if (entityId == null)
		{
			throw EngineRefusal.coded("missing.entity", "Entity " + pEntityName + " not found in project " + pProjectCode, pEntityName, pProjectCode);
		}
		final Adaptation plan = MatchingConfigurator.findMatchPlan(pFactory, entityId);
		if (plan == null)
		{
			throw EngineRefusal.coded("missing.noMatchPlan", "Entity " + pEntityName + " has no matching plan yet: run POST /projects/" + pProjectCode + "/matching/suggest[-llm] first.", pEntityName);
		}
		final JsonNode requirements = MatchingConfigurator.parseRequirements(plan.getString(FactoryPaths.MatchPlan.REQUIREMENTS));
		// native relation fields (lot W13): a table that belongs to a business object is patched through it (businessObject argument)
		final List<StagingModel.EntityInfo> survivorshipStagingEntities = MatchingConfigurator.readEntities(pFactory, projectId, new HashMap<String, String>());
		final MasterModel.Result survivorshipMasterModel = MasterModel.buildFromStaging(survivorshipStagingEntities);
		final String tablePath = MasterModel.tablePath(pEntityName);

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, MatchingConfigurator.STEP_SURVIVORSHIP, pEntityName);
		final ObjectNode detail = MatchingConfigurator.MAPPER.createObjectNode();
		try
		{
			final McpClient client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);
			final String modelName = MasterNames.modelName(pProjectCode);
			final MameBusinessObjects.Scope boScope = MameBusinessObjects.resolve(client, modelName, survivorshipMasterModel, pEntityName, false, detail);

			final ObjectNode getArgs = MatchingConfigurator.MAPPER.createObjectNode();
			getArgs.put("table", tablePath);
			final McpClient.ToolResult getResult = MameCalls.call(client, "get_matching_config", getArgs, modelName);
			detail.set("existingConfig", MatchingConfigurator.toolDetail(getArgs, getResult));

			if (!MameAnswer.success(getResult.json, false))
			{
				throw EngineRefusal.coded("missing.noMatchConfig", "No matching configuration exists yet for " + tablePath + " (" + MameAnswer.code(getResult.json)
					+ "): run POST /projects/" + pProjectCode + "/matching/configure first.", tablePath);
			}

			// 2026-09-23 fix, docs/MATCHING.md sec 19.10: same shared resolver as configureOne - a canonical
			// entity's source key lives on its MEMBERS' own attributes, mapped by name onto this entity's own.
			final List<String> sourceKeyFieldNamesNotes = new ArrayList<String>();
			final List<String> sourceKeyFieldNames = MatchingConfigurator.readSourceKeyFieldNames(pFactory, entityId, sourceKeyFieldNamesNotes);
			if (!sourceKeyFieldNamesNotes.isEmpty())
			{
				MatchingConfigurator.putStrings(detail, "sourceKeyFieldNamesNotes", sourceKeyFieldNamesNotes);
			}
			// lot 1 (2026-09-23, quality-informed survivorship): same fourth list as configureOne's own call.
			final List<String> validValueFieldNames = MatchingConfigurator.readValidValueFieldNames(pFactory, entityId);
			detail.put("validValueFieldNames", validValueFieldNames.toString());
			final ObjectNode desiredMergePolicy = MatchingPolicyTranslator.buildMergePolicy(pEntityName, requirements, sourceKeyFieldNames, validValueFieldNames);
			// master-field-filter lot (2026-09-23, docs/MATCHING.md sec 26): same fix as configureOne's own - a
			// survivor field this route is about to PATCH in may not be on the master table yet either (this route
			// only ever patches an EXISTING configuration, but the field-name gap is identical either way).
			final AdaptationTable masterTableForSurvivors = MatchingConfigurator.findMasterTable(pFactory, pProjectCode, pEntityName);
			final Predicate<String> fieldExistsOnMaster = pFieldName -> masterTableForSurvivors.getTableOccurrenceRootNode().getNode(Path.parse("./" + pFieldName)) != null;
			final List<String> survivorsDroppedNotOnMaster = MasterFieldFilter.dropSurvivorsNotOnMaster(desiredMergePolicy, tablePath, fieldExistsOnMaster);
			if (!survivorsDroppedNotOnMaster.isEmpty())
			{
				MatchingConfigurator.putStrings(detail, "survivorsDroppedNotOnMaster", survivorsDroppedNotOnMaster);
			}

			// set_trusted_sources - same call shape as configureOne's, dataModel included (docs/MATCHING.md sec 28:
			// an earlier task brief had this OMIT dataModel unconditionally, corrected), field overrides included
			// (see that method's own comment on extractFieldTrustedSourceOverrides), same run-30 self-check
			// (trustedSources passed through so the translator can refuse/clean a phantom code).
			final List<TrustedSourceRanking.Source> trustedSources = TrustedSourceRanking.order(MatchingConfigurator.readTrustedSources(pFactory, projectId));
			final ObjectNode trustedSourcesArgs = MatchingConfigurator.MAPPER.createObjectNode();
			trustedSourcesArgs.put("table", tablePath);
			final List<String> trustedSourceOverrideNotes = new ArrayList<String>();
			trustedSourcesArgs.set("trustedSources", TrustedSourceRanking.buildTrustedSourcesPayload(MatchingConfigurator.SOURCE_SYSTEM_FIELD_PATH, trustedSources,
				MatchingPolicyTranslator.extractFieldTrustedSourceOverrides(requirements, trustedSources, trustedSourceOverrideNotes)));
			if (!trustedSourceOverrideNotes.isEmpty())
			{
				MatchingConfigurator.putStrings(detail, "trustedSourceOverrideNotes", trustedSourceOverrideNotes);
			}
			final McpClient.ToolResult trustedSourcesResult = MameCalls.call(client, "set_trusted_sources", trustedSourcesArgs, modelName);
			detail.set("setTrustedSources", MatchingConfigurator.toolDetail(trustedSourcesArgs, trustedSourcesResult));
			MatchingConfigurator.requireSuccess(trustedSourcesResult, "set_trusted_sources");

			final JsonNode currentMergePolicy = MergeConfigPatchPlanner.findMergePolicy(getResult.json, pEntityName);
			final ArrayNode ops = MergeConfigPatchPlanner.plan(pEntityName, currentMergePolicy, desiredMergePolicy);
			detail.set("plannedOps", ops);

			final ObjectNode out = MatchingConfigurator.MAPPER.createObjectNode();
			out.put("project", pProjectCode);
			out.put("entity", pEntityName);
			out.put("opsApplied", ops.size());
			out.set("operations", ops);

			if (ops.size() == 0)
			{
				PipelineSteps.end(pSession, pFactory, stepId, true, "Survivorship for " + tablePath + " already matches the desired shape - nothing to patch.",
					detail.toString());
				return out;
			}

			final ObjectNode patchArgs = MatchingConfigurator.MAPPER.createObjectNode();
			patchArgs.put("table", tablePath);
			patchArgs.set("operations", ops);
			MameBusinessObjects.putBusinessObject(patchArgs, boScope);
			final McpClient.ToolResult patchResult = MameCalls.call(client, "patch_matching_config", patchArgs, modelName);
			detail.set("patch", MatchingConfigurator.toolDetail(patchArgs, patchResult));
			MatchingConfigurator.requireSuccess(patchResult, "patch_matching_config");

			PipelineSteps.end(pSession, pFactory, stepId, true, "Applied " + ops.size() + " survivorship op(s) to " + tablePath + ".", detail.toString());
			return out;
		}
		catch (final McpException ex)
		{
			// unlike configureOne (called from configure's own per-entity loop, which catches McpException
			// itself), this method is called directly from the REST layer (FactoryResource), which only ever
			// catches OperationException - so a transport failure is wrapped here rather than left checked.
			final String message = "MCP call failed while applying survivorship to " + tablePath + ": " + ex.getMessage();
			detail.put("transportError", ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, message, detail.toString());
			throw EngineRefusal.codedWithCause("unexpected.failed", message, ex);
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final RuntimeException ex)
		{
			// same discipline as configureOne's own catch: a translator/planner defect must end this step FAILED,
			// never leave a RUNNING row behind - see that method's own Javadoc.
			final String message = "survivorship planning failed for " + tablePath + ": " + ex.getMessage();
			detail.put("planningError", ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, message, detail.toString());
			throw EngineRefusal.codedWithCause("unexpected.failed", message, ex);
		}
	}

	/**
	 * The master table's own primary key field, relative to the record, in {@link MatchingRunScope#all}'s own
	 * expected shape (no leading {@code ./} or {@code /}) - read live off {@code AdaptationTable.getPrimaryKeySpec()}
	 * (CLAUDE.md sect;9's own "answers absolute-looking paths, e.g. /id" note: the leading slash is stripped here,
	 * a {@code Path.format()} of {@code "/group/field"} becomes {@code "group/field"}), never hardcoded - even
	 * though every master table this factory builds uses the plain {@code id} field by design
	 * (`master.MasterModel`). For a composite key, the FIRST field is used (see {@code MatchingRunScope}'s own
	 * class Javadoc "Lot R9" for why that is enough for the {@code all} scope's own purpose) - not measured live,
	 * since this factory's own master tables never have one.
	 *
	 * @throws OperationException naming {@code pTablePath} if the table or its primary key spec cannot be read -
	 *         never silently falls back to a guessed field name.
	 */
	private static String primaryKeyFieldOf(final AdaptationTable pMasterTable, final String pTablePath) throws OperationException
	{
		final Path[] primaryKeySpec = pMasterTable == null ? null : pMasterTable.getPrimaryKeySpec();
		if (primaryKeySpec == null || primaryKeySpec.length == 0)
		{
			throw OperationException.createError("Could not read the primary key of " + pTablePath + ": getPrimaryKeySpec() answered nothing.");
		}
		final String formatted = primaryKeySpec[0].format();
		return formatted.startsWith("/") ? formatted.substring(1) : formatted;
	}

	/**
	 * Every non-blank {@code Xref.masterKey} of this project for the given master table - the {@code
	 * MatchingRunScope.all} scope.
	 * <p>
	 * <b>Lot volume-4 (2026-09-28): a bounded existence probe, not a full deduplicated key list.</b> {@code
	 * MatchingRunScope.all}'s own Javadoc/parameter doc states plainly that {@code pMasterKeys} is "only ever
	 * consulted for 'is there anything to scope at all'" when {@code pWholeTableBelongsToProject} is true - and
	 * that is the ONLY value this codebase's one caller ({@link #run}, {@code pWholeTableBelongsToProject=true}
	 * always - {@code MatchingRunScope}'s own class Javadoc "Lot R9") ever passes. So the full list the old body
	 * built - a whole-project {@code Xref} scan materializing every distinct master key with an O(n^2) {@code
	 * ArrayList.contains} dedup - was always thrown away past its own {@code keys.isEmpty()} check on a table
	 * that can hold millions of {@code Xref} rows. This reads at most ONE matching row (same "read one, stop"
	 * idiom as {@link #findProject} a few lines below) and returns a list of 0 or 1 elements - functionally
	 * identical to the old list for every caller in this codebase (an emptiness check), at O(1) EBX reads
	 * instead of O(project's {@code Xref} row count).
	 */
	private static List<String> masterKeysOf(final Adaptation pFactory, final String pProjectId, final String pMasterTablePath)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.XREF).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./masterTable=" + CatalogReader.quote(pMasterTablePath) + " and osd:is-not-null(./masterKey)");
		try
		{
			final Adaptation row = rows.nextAdaptation();
			if (row != null)
			{
				final String key = row.getString(FactoryPaths.Xref.MASTER_KEY);
				if (key != null && !key.trim().isEmpty())
				{
					out.add(key);
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** A defensive, small cap on how many leading (descending-sorted) master rows {@link #mostRecentLoadId} will read looking for the first non-blank {@code _loadId} - see that method's own Javadoc for why more than 1 is ever needed. */
	private static final int MOST_RECENT_LOAD_ID_SCAN_CAP = 20;

	/**
	 * The maximum {@code _loadId} value present on the master table's own rows - the {@code
	 * MatchingRunScope.incremental} scope's input. See that class' own Javadoc for why this (not a dedicated
	 * "master load run id", which does not exist) is the scope.
	 * <p>
	 * <b>Lot volume-4 (2026-09-28): sorted, bounded read instead of a full scan.</b> The old body iterated EVERY
	 * row of the master table computing a running string max by hand - fine on "hundreds of rows in the
	 * reference demo" (the comment this replaces), an unbounded scan on a master table of millions of rows (task
	 * brief). {@code RequestSortCriteria} sorts server-side on {@code _loadId} DESCENDING (the same public EBX
	 * Java API a table-view column sort already uses; {@code javap}-confirmed public constructor/{@code
	 * add(Path, boolean)} on {@code com.onwbp.adaptation.RequestSortCriteria}, 2026-09-28) and only the first
	 * {@link #MOST_RECENT_LOAD_ID_SCAN_CAP} rows are ever read looking for the first non-blank value (a blank/
	 * null {@code _loadId} should not exist on a real master row - {@code ebx.MasterLoader} always carries a
	 * non-blank one through from staging - so this defensive cap, not a single {@code nextAdaptation()}, only
	 * guards against an unusual null-sort-order without ever falling back to a full table scan). <b>Deliberately
	 * NOT converted to the SQL {@code Query} API</b> (unlike {@code ebx.MatchingResultsBuilder}'s own volume-4
	 * queries): {@code Query.setSession(Session)} needs a {@code Session}, and this method's only caller ({@link
	 * #run}) is explicitly out of this lot's allowed footprint - {@code RequestResult}/{@code
	 * RequestSortCriteria}, unlike {@code Query}, need no {@code Session} in this codebase's established usage
	 * (every other {@code createRequestResult} call in this class), so the signature stays exactly as it was and
	 * {@link #run}'s own call site needs no change. Compiled against the real {@code ebx-lib.jar} (task brief's
	 * {@code tools/test_local.sh}) but NOT exercised live this session (no write access, and no project on the
	 * reached instance had more than a handful of distinct {@code _loadId} values to meaningfully verify sort
	 * order against) - see {@code docs/VOLUME-SCREENS.md}.
	 */
	/**
	 * Lot M4 (defect 3): the number of rows of {@code pTable} matching {@code pPredicate} ({@code null} = every row) - {@code
	 * RequestResult.getSize()}, EBX's own count primitive, no row materialised. {@code -1} when it could not be counted (never throws:
	 * the scope sentence is information, it must not stop a run).
	 */
	static long countRecords(final AdaptationTable pTable, final String pPredicate)
	{
		try
		{
			final RequestResult rows = pTable.createRequestResult(pPredicate);
			try
			{
				return rows.getSize();
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return -1L;
		}
	}

	private static String mostRecentLoadId(final Adaptation pMasterDataset, final String pTablePath)
	{
		try
		{
			final AdaptationTable table = pMasterDataset.getTable(Path.parse(pTablePath));
			final RequestSortCriteria sort = new RequestSortCriteria().add(Path.parse("./_loadId"), false);
			final RequestResult rows = table.createRequestResult(null, sort);
			try
			{
				for (int i = 0; i < MatchingConfigurator.MOST_RECENT_LOAD_ID_SCAN_CAP; i++)
				{
					final Adaptation row = rows.nextAdaptation();
					if (row == null)
					{
						return null;
					}
					final String loadId = row.getString(Path.parse("./_loadId"));
					if (loadId != null && !loadId.trim().isEmpty())
					{
						return loadId;
					}
				}
				return null;
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	// ---------------------------------------------------------------------------------------------------------- reads

	static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * The master {@link AdaptationTable} of {@code pEntityName} within {@code pProjectCode}'s own master
	 * dataspace/dataset - master-field-filter lot (2026-09-23, `docs/MATCHING.md` &sect;26): factored out of the
	 * inline project/masterDataspaceKey/masterDatasetName/{@link AdaptationHome}/{@link AdaptationName} lookup
	 * {@link #run}/{@link #simulate} each already perform for their own purposes, so {@link #configureOne},
	 * {@link #applySurvivorship} and {@code ebx.MatchingPreviewPolicy} (same package, calls this directly - never
	 * duplicated there) can all reach the SAME master table a survivor field must resolve on ({@link
	 * MasterFieldFilter}) without re-deriving the plumbing three more times.
	 *
	 * @throws OperationException the project does not exist, has no master zone yet ({@code master/generate}
	 *             never ran - same message {@link #run}/{@link #simulate} already give for the identical
	 *             precondition), or the master dataset/table cannot actually be found. Always throws rather than
	 *             returning {@code null}: a merge policy's survivor fields cannot be checked against a table
	 *             that cannot be located, and every caller of this method is about to build one.
	 */
	static AdaptationTable findMasterTable(final Adaptation pFactory, final String pProjectCode, final String pEntityName) throws OperationException
	{
		final Adaptation project = MatchingConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (MatchingConfigurator.blank(masterDataspaceKey) || MatchingConfigurator.blank(masterDatasetName))
		{
			throw EngineRefusal.coded("missing.noMasterZone", "Project " + pProjectCode + " has no master zone yet: run POST /projects/" + pProjectCode + "/master/generate first", pProjectCode);
		}
		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		final Adaptation masterDataset = masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(masterDatasetName));
		if (masterDataset == null)
		{
			throw EngineRefusal.coded("missing.masterDatasetPath", "Master dataset " + masterDataspaceKey + "/" + masterDatasetName + " of project " + pProjectCode + " could not be found.", masterDataspaceKey + "/" + masterDatasetName);
		}
		final String tablePath = MasterModel.tablePath(pEntityName);
		final AdaptationTable masterTable = masterDataset.getTable(Path.parse(tablePath));
		if (masterTable == null)
		{
			throw EngineRefusal.coded("missing.masterTable", "Master table " + tablePath + " of project " + pProjectCode + " could not be found in " + masterDataspaceKey + "/" + masterDatasetName + ".", tablePath, masterDataspaceKey + "/" + masterDatasetName);
		}
		return masterTable;
	}

	private static Adaptation findMatchPlan(final Adaptation pFactory, final String pEntityId)
	{
		if (pEntityId == null)
		{
			return null;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.MATCH_PLAN).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	/** Same shape {@code MasterGenerator.readEntities}/{@code MasterLoader.readEntities}/{@code MatchPlanSuggester.readEntities} already read - a separate copy per this codebase's own convention. */
	static List<StagingModel.EntityInfo> readEntities(final Adaptation pFactory, final String pProjectId,
		final Map<String, String> pEntityIdByName)
	{
		final List<Adaptation> entityRowsList = new ArrayList<Adaptation>();
		final Map<String, String> entityIdToName = new HashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				entityRowsList.add(row);
				final String id = row.getOccurrencePrimaryKey().format();
				final String name = row.getString(FactoryPaths.StagingEntity.NAME);
				entityIdToName.put(id, name);
				pEntityIdByName.put(name, id);
			}
		}
		finally
		{
			entityRows.close();
		}

		final List<StagingModel.EntityInfo> out = new ArrayList<StagingModel.EntityInfo>();
		for (final Adaptation row : entityRowsList)
		{
			final String entityId = row.getOccurrencePrimaryKey().format();
			final List<String> memberNames = new ArrayList<String>();
			final List<?> rawMemberIds = row.getList(FactoryPaths.StagingEntity.MEMBER_ENTITIES);
			if (rawMemberIds != null)
			{
				for (final Object rawMemberId : rawMemberIds)
				{
					final String memberName = entityIdToName.get(String.valueOf(rawMemberId));
					if (memberName != null)
					{
						memberNames.add(memberName);
					}
				}
			}
			out.add(new StagingModel.EntityInfo(row.getString(FactoryPaths.StagingEntity.NAME), row.getString(FactoryPaths.StagingEntity.LABEL),
				row.getString(FactoryPaths.StagingEntity.STATUS), MatchingConfigurator.readAttributes(pFactory, entityId, entityIdToName), memberNames));
		}
		return out;
	}

	private static List<StagingModel.AttributeInfo> readAttributes(final Adaptation pFactory, final String pEntityId,
		final Map<String, String> pEntityIdToName)
	{
		final List<StagingModel.AttributeInfo> out = new ArrayList<StagingModel.AttributeInfo>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object rawMaxLength = row.get(FactoryPaths.StagingAttribute.MAX_LENGTH);
				final Integer maxLength = rawMaxLength instanceof Number ? Integer.valueOf(((Number) rawMaxLength).intValue()) : null;
				final boolean mandatory = Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.MANDATORY));
				final boolean primaryKey = Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY));
				final String referenceEntityId = row.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
				final String referenceEntityName = referenceEntityId == null ? null : pEntityIdToName.get(referenceEntityId);
				out.add(new StagingModel.AttributeInfo(row.getString(FactoryPaths.StagingAttribute.NAME),
					row.getString(FactoryPaths.StagingAttribute.LABEL), row.getString(FactoryPaths.StagingAttribute.DATA_TYPE), maxLength, mandatory, primaryKey,
					referenceEntityName, row.getString(FactoryPaths.StagingAttribute.STATUS)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Audit F-02 (catalog reads): why {@link #readTrustedSources} reads the catalog without the person's session. */
	static final String TRUSTED_SOURCE_LABEL_REASON = "labels of the catalog sources the project already uses, as descriptions of its trusted sources in the matching"
		+ " configuration and the language-model prompt (runs in jobs too): only the project's own sources, never a listing";

	/**
	 * The project's own {@code ProjectSource} rows, turned into {@link TrustedSourceRanking.Source} - {@code
	 * code} is {@code sourceSystem}, the SAME identifier the master table's own rows carry in their {@code
	 * sourceSystem} field (see {@code master.MasterModel}'s class Javadoc "{@code sourceSystem}/{@code
	 * sourceId} move to plain, OPTIONAL fields" and {@code ebx.MasterLoader}, which writes a landing row's
	 * {@code _sourceSystem} - itself the wizard's own {@code ProjectSource.sourceSystem}, see {@code
	 * ebx.WizardData#addSource} - straight into the master/staging row's {@code sourceSystem} field, no
	 * translation in between). {@code description} prefers the linked catalog source's own label ({@code
	 * CatalogReader.Source.label}, the same lookup {@code ebx.WizardData} performs for the wizard's own source
	 * list) and falls back to the bare {@code sourceSystem} code when no catalog source is linked or the
	 * catalog itself is unavailable - {@link TrustedSourceRanking.Source}'s own constructor already falls
	 * back to the code for a blank/null description, so passing {@code null} here is enough.
	 * <p>
	 * Package-visible (not {@code private}) since the survivorship lot (2026-09-22): {@code
	 * ebx.MatchPlanLlmSuggester} reuses it verbatim to show the project's own trusted-source ranking in the
	 * LLM prompt ({@code master.MatchPlanLlmPass.ProjectInfo#trustedSources}) - same package, same read,
	 * never a second copy of this query.
	 */
	static List<TrustedSourceRanking.Source> readTrustedSources(final Adaptation pFactory, final String pProjectId)
	{
		final Map<String, String> catalogLabelById = new HashMap<String, String>();
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog != null)
		{
			// audit F-02 (catalog reads): only the labels of the catalog sources this project already uses end up in the matching
			// configuration (trusted-source descriptions) and in the language-model prompt - nothing is listed to anybody
			for (final CatalogReader.Source source : CatalogReader.sources(CatalogReader.Reader.system(MatchingConfigurator.TRUSTED_SOURCE_LABEL_REASON), catalog))
			{
				catalogLabelById.put(source.id, source.label);
			}
		}
		final List<TrustedSourceRanking.Source> out = new ArrayList<TrustedSourceRanking.Source>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String code = row.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
				if (code == null || code.trim().isEmpty())
				{
					continue;
				}
				final Object rank = row.get(FactoryPaths.ProjectSource.TRUST_RANK);
				final Integer trustRank = rank instanceof Number ? Integer.valueOf(((Number) rank).intValue()) : null;
				final String catalogSourceId = row.getString(FactoryPaths.ProjectSource.CATALOG_SOURCE);
				final String description = catalogSourceId == null || catalogSourceId.trim().isEmpty() ? null : catalogLabelById.get(catalogSourceId);
				out.add(new TrustedSourceRanking.Source(code.trim(), trustRank, description));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * The entity's own SOURCE-KEY business attribute(s) - {@code StagingAttribute.businessKey = true} ("the
	 * SOURCE's own key", {@code ReviewLine}/{@code WizardSnapshot}'s own Javadoc for that field - e.g. {@code
	 * supplierId} on {@code Supplier}), structurally distinct from {@code StagingAttribute.primaryKey} (the
	 * technical row identity, {@code sourceSystem}/{@code sourceId}, already covered by {@link
	 * MatchingPolicyTranslator#buildMergePolicy}'s own fixed technical-field rules). Never a name guess - this
	 * reads the actual schema role EBX stores for the attribute, and (2026-09-23 fix, `docs/MATCHING.md`
	 * &sect;19.10) reads it from the right PLACE: for a CANONICAL entity ({@code StagingEntity.memberEntities}
	 * non-empty), the {@code businessKey} flag lives on each MEMBER's own attribute, never on the canonical
	 * entity's own attribute of the same name (measured live: {@code Company.supplierId} itself carries
	 * {@code businessKey: false}, while {@code Supplier.supplierId} - a member of {@code Company} - carries
	 * {@code businessKey: true}). {@code master.SourceKeyResolver} - shared by this method, {@link
	 * #applySurvivorship}, and {@code ebx.MatchingPreviewPolicy}'s own preview generation, so all three now
	 * resolve identically - does the actual decision (own attributes directly flagged, UNION each member's own
	 * flagged attribute mapped by NAME onto a same-named, structurally-eligible attribute of THIS entity, the
	 * only mapping this schema records - see that class' own Javadoc) once every {@code StagingAttribute}/
	 * {@code StagingEntity} row involved has been read here.
	 * <p>
	 * <b>Deliberately excludes a business-key attribute that is ALSO a foreign key</b> ({@code
	 * referenceEntity} non-blank): on the master table such an attribute is promoted as a {@code "...Ref"}
	 * field, a name this method has no reliable way to reproduce without re-running {@code
	 * MasterModel.buildTable}'s own collision-handling ({@code unique(...)}) - see {@code MasterModel}'s own
	 * Javadoc for that renaming. Excluding it here is a scoping choice, not a correctness bug: a source-key
	 * attribute in every real case seen so far (`docs/MATCHING.md`) is plain source-system data, never a
	 * reference to another entity, so this only ever matters if that assumption stops holding - documented so
	 * the exclusion is not mistaken for an oversight.
	 *
	 * @param pNotesOut appended with one human-readable note per member-resolved source key (or per member key
	 *            that could NOT be mapped onto this entity) - {@code master.SourceKeyResolver.Result#notes}
	 *            verbatim, so a caller can record in its own {@code PipelineStep} detail exactly how (or
	 *            whether) same-name mapping resolved each one, per the task brief's own "say so in the detail"
	 *            requirement. Never {@code null}; pass a throwaway list to ignore.
	 */
	static List<String> readSourceKeyFieldNames(final Adaptation pFactory, final String pEntityId, final List<String> pNotesOut)
	{
		if (pEntityId == null || pEntityId.trim().isEmpty())
		{
			return new ArrayList<String>();
		}
		final List<SourceKeyResolver.AttributeInfo> ownAttributes = MatchingConfigurator.readSourceKeyAttributeInfo(pFactory, pEntityId);
		final Map<String, List<SourceKeyResolver.AttributeInfo>> memberAttributesByName = new LinkedHashMap<String, List<SourceKeyResolver.AttributeInfo>>();
		final Adaptation entityRow = pFactory.getTable(FactoryPaths.STAGING_ENTITY).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pEntityId));
		if (entityRow != null)
		{
			final List<?> rawMemberIds = entityRow.getList(FactoryPaths.StagingEntity.MEMBER_ENTITIES);
			if (rawMemberIds != null)
			{
				for (final Object rawMemberId : rawMemberIds)
				{
					final String memberId = String.valueOf(rawMemberId);
					final Adaptation memberRow = pFactory.getTable(FactoryPaths.STAGING_ENTITY).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(memberId));
					if (memberRow == null)
					{
						continue;
					}
					final String memberName = memberRow.getString(FactoryPaths.StagingEntity.NAME);
					memberAttributesByName.put(memberName == null || memberName.trim().isEmpty() ? memberId : memberName,
						MatchingConfigurator.readSourceKeyAttributeInfo(pFactory, memberId));
				}
			}
		}
		final SourceKeyResolver.Result result = SourceKeyResolver.resolve(ownAttributes, memberAttributesByName);
		pNotesOut.addAll(result.notes);
		return result.fieldNames;
	}

	/** Every {@code StagingAttribute} row of {@code pEntityId}, turned into {@link SourceKeyResolver.AttributeInfo} - the raw material {@link #readSourceKeyFieldNames} feeds to {@code master.SourceKeyResolver.resolve} for BOTH the entity itself and each of its members (canonical case). */
	private static List<SourceKeyResolver.AttributeInfo> readSourceKeyAttributeInfo(final Adaptation pFactory, final String pEntityId)
	{
		final List<SourceKeyResolver.AttributeInfo> out = new ArrayList<SourceKeyResolver.AttributeInfo>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String name = row.getString(FactoryPaths.StagingAttribute.NAME);
				if (name == null || name.trim().isEmpty())
				{
					continue;
				}
				final boolean businessKey = Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.BUSINESS_KEY));
				final boolean primaryKey = Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY));
				final String referenceEntity = row.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
				final boolean isForeignKey = referenceEntity != null && !referenceEntity.trim().isEmpty();
				final boolean acceptedLike = TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.StagingAttribute.STATUS));
				out.add(new SourceKeyResolver.AttributeInfo(name.trim(), businessKey, primaryKey, isForeignKey, acceptedLike));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Lot 1 (2026-09-23, quality-informed survivorship, `docs/MATCHING.md` &sect;23): the entity's own accepted
	 * {@code VALID_VALUE} derived attributes ({@code StagingAttribute.derivation = VALID_VALUE}, {@code
	 * ebx.QualitySuggester}'s own writer, {@code quality.ValidValueDerivation}'s own Javadoc) - bare master field
	 * names (e.g. {@code vatNoValid}), read STRUCTURALLY, the same "never a name guess" discipline {@link
	 * #readSourceKeyFieldNames} already applies to {@code businessKey}. Threaded into {@link
	 * MatchingPolicyTranslator#buildMergePolicy} as a FOURTH parameter, mirroring how {@code
	 * pSourceKeyFieldNames} itself was added (that method's own Javadoc) - every one of {@link
	 * #buildMergePolicy}'s four call sites ({@code MatchingConfigurator#configureOne}/{@code
	 * #applySurvivorship}, {@code MatchingPreviewPolicy} twice) reads this the SAME way, right next to its own
	 * {@code readSourceKeyFieldNames} call.
	 * <p>
	 * <b>Unlike {@link #readSourceKeyFieldNames}, this never follows {@code memberEntities}.</b> A {@code
	 * VALID_VALUE} field is always derived from an attribute of the SAME entity it is declared on ({@code
	 * StagingAttribute.derivedFrom} is a foreign key within one entity, {@code staging.DerivedFieldRule}'s own
	 * Javadoc) - there is no canonical/member split to resolve here, so a single, direct read of this entity's
	 * own {@code StagingAttribute} rows is the whole answer, never a two-level union like {@code
	 * master.SourceKeyResolver}'s.
	 */
	static List<String> readValidValueFieldNames(final Adaptation pFactory, final String pEntityId)
	{
		final List<String> out = new ArrayList<String>();
		if (pEntityId == null || pEntityId.trim().isEmpty())
		{
			return out;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String derivation = row.getString(FactoryPaths.StagingAttribute.DERIVATION);
				if (!ValidValueDerivation.DERIVATION_VALID_VALUE.equals(derivation))
				{
					continue;
				}
				if (!TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.StagingAttribute.STATUS)))
				{
					continue;
				}
				final String name = row.getString(FactoryPaths.StagingAttribute.NAME);
				if (name != null && !name.trim().isEmpty())
				{
					out.add(name.trim());
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------- misc

	/**
	 * Pre-flight check, run once per entity right after {@link MatchingPolicyTranslator#buildMatchingPolicy}
	 * and BEFORE {@code generate_matching_policy} is ever called (`docs/MATCHING.md` &sect;3.1, item 4 of the
	 * 2026-09-21 fix): asks {@code matching_config_schema(table[, dataModel])} for {@code fieldCapabilities}
	 * and refuses outright if a field the built policy declares is either absent from it or reports an empty
	 * {@code available} list (a structural incapacity to pre-cluster on that field, never merely
	 * "unmeasurable" - the same distinction {@code MatchPlanLlmPass}'s own clustering guard already makes).
	 * <p>
	 * By the time this method runs, {@code get_matching_config} has already answered either {@code
	 * CONFIG_NOT_FOUND} (a fresh table) or a real, owned configuration (`docs/MATCHING.md` &sect;19,
	 * compare-then-rewrite lot, 2026-09-22: {@link #configureOne} now calls this method unconditionally,
	 * whenever it is about to generate OR compare/rewrite, never only on the fresh path as before that lot) -
	 * never {@code TABLE_NOT_FOUND}, which {@link #configureOne} refuses before ever reaching here. Unlike
	 * {@code MatchPlanLlmSuggester}'s own earlier, per-entity {@code fieldCapabilities} call (`docs/MATCHING.md`
	 * &sect;4.2.1), which has only ever been observed to soft-miss {@code TABLE_NOT_FOUND} because the table
	 * was not yet registered with the add-on at that point in the pipeline, this call is made strictly later
	 * and may genuinely succeed where that one never has. It never has been exercised live either way this
	 * session (write tools are forbidden here, and this is a read call made from the same forbidden session) -
	 * if it fails, soft-misses, or answers no {@code fieldCapabilities} at all, this method records
	 * "not verified" in {@code pDetail} and returns without refusing, the same discipline {@code
	 * MatchPlanLlmSuggester} already applies to the identical soft-miss.
	 */
	private static void checkFieldCapabilities(final McpClient pClient, final String pTablePath, final String pPublicationName, final ObjectNode pMatchingPolicy,
		final ObjectNode pDetail) throws OperationException
	{
		final ObjectNode args = MatchingConfigurator.MAPPER.createObjectNode();
		args.put("table", pTablePath);
		final McpClient.ToolResult result;
		try
		{
			result = MameCalls.call(pClient, "matching_config_schema", args, pPublicationName);
		}
		catch (final McpException ex)
		{
			pDetail.put("fieldCapabilities", "not verified (matching_config_schema call failed: " + ex.getMessage() + ")");
			return;
		}
		catch (final OperationException ex)
		{
			// same discipline as the soft-miss branch below (class Javadoc): an AMBIGUOUS_TABLE this call could not
			// resolve is "not verified", never a hard refusal of the whole configure pass.
			pDetail.put("fieldCapabilities", "not verified (matching_config_schema could not resolve a data model: " + ex.getMessage() + ")");
			return;
		}
		pDetail.set("fieldCapabilitiesCall", MatchingConfigurator.toolDetail(args, result));
		if (result.error || !MameAnswer.success(result.json, false))
		{
			pDetail.put("fieldCapabilities",
				"not verified (" + (result.json == null ? "no response" : MatchPlanProfileReader.describeSoftMiss(result.json)) + ")");
			return;
		}
		final JsonNode schemaDocument = MatchPlanProfileReader.resolveSchemaData(result.json);
		final Map<String, Boolean> capabilities = MatchPlanProfileReader.readFieldCapabilities(schemaDocument);
		if (capabilities.isEmpty())
		{
			pDetail.put("fieldCapabilities", "not verified (matching_config_schema returned no fieldCapabilities for this table)");
			return;
		}
		final List<String> missing = MatchPlanProfileReader.findFieldsMissingCapability(pMatchingPolicy, capabilities);
		if (!missing.isEmpty())
		{
			throw EngineRefusal.coded("conflict.clusteringFields", "matching_config_schema reports " + pTablePath + " cannot pre-cluster on: " + missing
				+ " - fix the matching plan (drop these from clusteringFields, or from any tier that compares them) before retrying.", pTablePath, missing);
		}
		pDetail.put("fieldCapabilities", "verified: every declared field offers a pre-clustering search strategy");
	}

	/**
	 * Reads the decision-tree null-value handlings the LIVE add-on accepts ({@code matching_config_schema}, no table argument - the global call, which
	 * has always worked) and translates {@code pMatchingPolicy}'s tree accordingly ({@link MatchingPolicyTranslator#translateTreeNullHandling}).
	 * A schema that cannot be read leaves the policy untouched and says so in the detail ({@code treeNullHandling: not verified ...}) - never a
	 * guess in either direction. Records {@code treeNullHandlingAllowed} and, when something changed, {@code nullHandlingTranslated}.
	 *
	 * @return the translation notes (empty when none)
	 */
	static List<String> translateTreeNullHandlingForLiveSchema(final McpClient pClient, final ObjectNode pMatchingPolicy, final String pEntityName, final ObjectNode pDetail)
	{
		final List<String> allowed;
		try
		{
			final McpClient.ToolResult result = pClient.callTool("matching_config_schema", MatchingConfigurator.MAPPER.createObjectNode());
			if (result.error || result.json == null || !result.json.path("success").asBoolean(false))
			{
				pDetail.put("treeNullHandling", "not verified (matching_config_schema: " + (result.json == null ? MatchingConfigurator.shortText(result.text)
					: MatchPlanProfileReader.describeSoftMiss(result.json)) + ") - the plan's null handlings are sent as stored");
				return new ArrayList<String>();
			}
			final MatchPlanProfileReader.VocabularyResult parsed = MatchPlanProfileReader.readVocabulary(MatchPlanProfileReader.resolveSchemaData(result.json));
			if (parsed.vocabulary == null)
			{
				pDetail.put("treeNullHandling", "not verified (" + parsed.error + ") - the plan's null handlings are sent as stored");
				return new ArrayList<String>();
			}
			allowed = parsed.vocabulary.treeNullHandlings;
		}
		catch (final McpException ex)
		{
			pDetail.put("treeNullHandling", "not verified (matching_config_schema call failed: " + ex.getMessage() + ") - the plan's null handlings are sent as stored");
			return new ArrayList<String>();
		}
		MatchingConfigurator.putStrings(pDetail, "treeNullHandlingAllowed", allowed);
		final List<String> notes = MatchingPolicyTranslator.translateTreeNullHandling(pMatchingPolicy, allowed, pEntityName);
		if (!notes.isEmpty())
		{
			MatchingConfigurator.putStrings(pDetail, "nullHandlingTranslated", notes);
		}
		return notes;
	}

	/** Only ever set from a {@link MameDataModelResolution} resolution - never the publication name directly (see the class Javadoc's opening note). */
	private static void putDataModel(final ObjectNode pArgs, final String pDataModel)
	{
		if (pDataModel != null)
		{
			pArgs.put("dataModel", pDataModel);
		}
	}

	/**
	 * A truthful {@code TABLE_NOT_FOUND} message - coordinator review, 2026-09-21 (`docs/MATCHING.md` &sect;2.2,
	 * "dataModel" entry). Before this fix the hint always pointed at {@code master/generate}, which was true only
	 * while the add-on genuinely was not declared yet; now it is actually checked, with {@code
	 * MasterGenerator}'s own read-only {@code list_addons} parsing ({@link MasterGenerator#checkMameAddonDeclared},
	 * extracted for this reuse - never duplicated here). Never throws: a failure to check is reported as such,
	 * softening the advice rather than asserting something unverified either way.
	 */
	private static String tableNotFoundHint(final Session pSession, final Adaptation pFactory, final String pModelName, final String pProjectCode, final ObjectNode pDetail)
	{
		final ObjectNode addonDetail = MatchingConfigurator.MAPPER.createObjectNode();
		try
		{
			final McpClient dataModelClient = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
			final MasterGenerator.MameAddonCheck check = MasterGenerator.checkMameAddonDeclared(dataModelClient, pModelName, addonDetail);
			pDetail.set("addonCheck", addonDetail);
			if (check.decision.action == MameAddonDecision.Action.ALREADY_DECLARED)
			{
				return " The MAME add-on IS declared on " + pModelName + " (list_addons confirms it) - match-merge still does not resolve the table under it "
					+ "(raw get_matching_config answer above). Two known causes: the model was republished moments ago and match-merge has not caught up yet "
					+ "(retry), or the master dataset this project uses is not built on the " + pModelName
					+ " publication. Running master/generate again would not change either of these - it is not the fix here.";
			}
			return " The master model " + pModelName + " does not declare the MAME add-on yet: run POST /projects/" + pProjectCode
				+ "/master/generate first (it declares and republishes the add-on additively, safe to re-run).";
		}
		catch (final McpException ex)
		{
			pDetail.set("addonCheck", addonDetail);
			pDetail.put("addonCheckError", ex.getMessage());
			return " Could not verify whether the MAME add-on is declared on " + pModelName + " (list_addons call failed: " + ex.getMessage()
				+ "): if master/generate has not been (re)run since the model last changed, run POST /projects/" + pProjectCode + "/master/generate first.";
		}
		catch (final OperationException ex)
		{
			pDetail.set("addonCheck", addonDetail);
			pDetail.put("addonCheckError", ex.getMessage());
			return " Could not verify whether the MAME add-on is declared on " + pModelName + " (" + ex.getMessage()
				+ "): if master/generate has not been (re)run since the model last changed, run POST /projects/" + pProjectCode + "/master/generate first.";
		}
	}

	static ObjectNode toolDetail(final ObjectNode pArguments, final McpClient.ToolResult pResult)
	{
		final ObjectNode node = MatchingConfigurator.MAPPER.createObjectNode();
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

	static void putStrings(final ObjectNode pOut, final String pKey, final List<String> pValues)
	{
		final ArrayNode array = pOut.putArray(pKey);
		for (final String value : pValues)
		{
			array.add(value);
		}
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
