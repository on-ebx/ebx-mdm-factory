package com.on.ebx.factory.rest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.core.FactoryRights;
import com.on.ebx.factory.core.ModuleVersion;
import com.on.ebx.factory.core.ScopeParam;
import com.on.ebx.factory.ebx.CapabilityApplier;
import com.on.ebx.factory.ebx.CapabilityDispatcher;
import com.on.ebx.factory.ebx.CapabilityLlmSuggester;
import com.on.ebx.factory.ebx.CatalogLineageDeclarer;
import com.on.ebx.factory.ebx.DataProductPublication;
import com.on.ebx.factory.ebx.DeliveryDomainReader;
import com.on.ebx.factory.ebx.DerivationBackfillPass;
import com.on.ebx.factory.ebx.DerivationParameterSettings;
import com.on.ebx.factory.ebx.DistributionConfigurator;
import com.on.ebx.factory.ebx.DistributionConnections;
import com.on.ebx.factory.ebx.DistributionDispatcher;
import com.on.ebx.factory.ebx.DistributionReader;
import com.on.ebx.factory.ebx.DistributionRunner;
import com.on.ebx.factory.ebx.DistributionSendRun;
import com.on.ebx.factory.ebx.DistributionTriggerInstaller;
import com.on.ebx.factory.ebx.FactoryAccess;
import com.on.ebx.factory.ebx.FactoryInstaller;
import com.on.ebx.factory.ebx.GoldenKeyRefreshRun;
import com.on.ebx.factory.ebx.LandingFlows;
import com.on.ebx.factory.ebx.LandingGenerator;
import com.on.ebx.factory.ebx.LandingPlanner;
import com.on.ebx.factory.ebx.MasterBuildRun;
import com.on.ebx.factory.ebx.MasterGenerator;
import com.on.ebx.factory.ebx.MasterLoader;
import com.on.ebx.factory.ebx.MasterReset;
import com.on.ebx.factory.ebx.MasterResetRun;
import com.on.ebx.factory.ebx.MasterZoneIndex;
import com.on.ebx.factory.master.MasterLoadScope;
import com.on.ebx.factory.ebx.MatchPlanLlmSuggester;
import com.on.ebx.factory.ebx.MatchPlanSuggester;
import com.on.ebx.factory.ebx.MatchingConfigureRun;
import com.on.ebx.factory.ebx.MatchingConfigurator;
import com.on.ebx.factory.ebx.MatchingPreviewPolicy;
import com.on.ebx.factory.ebx.MatchingRunRun;
import com.on.ebx.factory.ebx.OrphanConnectionCleanup;
import com.on.ebx.factory.project.OrphanConnections;
import com.on.ebx.factory.ebx.McpServers;
import com.on.ebx.factory.ebx.OrphanMatchingConfigCleanup;
import com.on.ebx.factory.ebx.ProjectDeleteRun;
import com.on.ebx.factory.ebx.ProjectDeleter;
import com.on.ebx.factory.project.ProjectDeletionPlan;
import com.on.ebx.factory.ebx.QualityLlmSuggester;
import com.on.ebx.factory.ebx.QualitySuggester;
import com.on.ebx.factory.ebx.RecordLineageReader;
import com.on.ebx.factory.ebx.RelationChecker;
import com.on.ebx.factory.ebx.SampleSourceImporter;
import com.on.ebx.factory.ebx.SourceOnboarder;
import com.on.ebx.factory.ebx.StagingGenerator;
import com.on.ebx.factory.ebx.StagingLlmSuggester;
import com.on.ebx.factory.ebx.StagingLoadRun;
import com.on.ebx.factory.ebx.StagingPreflight;
import com.on.ebx.factory.ebx.StagingLoader;
import com.on.ebx.factory.ebx.StagingSuggester;
import com.on.ebx.factory.ebx.StepResetRun;
import com.on.ebx.factory.ebx.StepResetter;
import com.on.ebx.factory.project.StepResetPlan;
import com.on.ebx.factory.lineage.CrossReferences;
import com.on.ebx.factory.transfer.IncrementalTransfer;
import com.on.ebx.factory.wizard.SourceOnboarding;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.rest.inject.SessionContext;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * <b>Audit F-02 (2026-10-06): who may call each route is decided in {@code core.FactoryRights} (VIEW, STEWARD, DESIGN or the
 * administrator) and checked first by {@link #refused}; every "Administrators only" below predates that and now reads
 * "the right {@code FactoryRights.route} gives this route" (docs/OPERATIONS.md section 4.1).</b>
 * <p>
 * {@code /ebx-mdm-factory/rest/v1}:
 * <ul>
 * <li>{@code GET /status} — whether the factory dataset is installed and really its own, and how many
 * rows each of its tables holds.</li>
 * <li>{@code GET /connection/test} — reaches every MCP server the factory needs with the one agent account of the
 * settings: reachable, account accepted, number of tools, and one read-only probe call answered ({@code
 * McpServers.PROBES}), per server. Administrators only. Every route below marked "acts with the agent account
 * on the MCP servers" runs the same check on the servers it needs FIRST ({@link #preflight}) and answers
 * {@code 503} with the same sentences when one fails, before doing anything.</li>
 * <li>{@code POST /sources/{id}/landing/plan} — plans the landing of a project source from the scanned
 * structures of its catalog source: landing tables and columns, names and source key proposed. Adds
 * what is new, leaves what is planned alone, flags a generated table whose source changed. Also runs
 * {@code RelationRule} over every planned table of the source and writes its proposals as
 * {@code LandingRelation} rows (never as an EBX foreign key: the landing itself stays constraint-free) —
 * the answer's {@code relations}, {@code relationsSkipped} and {@code loadOrder} report what was
 * proposed, why some columns were not, and the parents-first order to load the tables in.</li>
 * <li>{@code POST /sources/{id}/landing/generate} — builds and publishes the landing data model of a
 * project source from its plan, then creates its landing dataspace and dataset. Step by step, resumable
 * after a failure. Administrators only: it acts with the factory's one agent account on the MCP
 * servers, like {@code /connection/test}.</li>
 * <li>{@code POST /sources/{id}/landing/flows[?force=true]} — writes one ebx-connectors inbound mapping per
 * generated landing table (a clean dry run first, then for real), storing the mapping id on
 * {@code LandingTable.inboundMapping}. Resumable after a failure, like {@code /landing/generate}: skipped
 * when its step already ran, unless {@code force=true} — the upsert is idempotent on the mapping's label,
 * so forcing updates the mappings already written (rules and inputs fully replaced, not merged) rather
 * than duplicating them; use it to push a changed SQL generator onto flows generated before the change.
 * Administrators only: it acts with the factory's one agent account on the MCP servers.</li>
 * <li>{@code POST /sources/{id}/landing/load} — runs the flow of every landing table that has one: mints
 * a load id, syncs with an explicit, sized {@code max}, and records rows loaded / status per table. Not
 * resumable-skip like the other steps: a load is meant to be repeated on demand. Administrators only.</li>
 * <li>{@code POST /sources/{id}/relations/check} — checks every {@code LandingRelation} of the source whose
 * two tables are both {@code LOADED} against the values actually loaded: a SQL anti-join run inside EBX
 * ({@code Adaptation.createQuery}, falling back to a record-by-record scan for a statement the engine
 * refuses), distinct child values, distinct orphan values, a bounded sample, and whether each parent key is
 * referenced at most once. Writes {@code checkedAt}/{@code childValues}/{@code orphanValues}/
 * {@code cardinality}/{@code message} on every relation checked; only adjusts {@code confidence}, and only
 * for a relation still {@code RULE}/{@code PROPOSED} (never one a person or another origin already decided).
 * No {@code PipelineStep} is recorded (not one of the model's declared step values). Any authenticated user,
 * like {@code /landing/plan}: it reads landing data and writes evidence with the caller's own rights, no MCP
 * call and no agent account.</li>
 * <li>{@code GET /sources/onboarding/connections}, {@code GET /sources/onboarding/{connector}},
 * {@code POST /sources/onboarding/{connector}/catalog}, {@code POST /sources/onboarding/{connector}/scan} —
 * source-onboarding lot (2026-09-23): the wizard's step-2 "Create a catalog source" panel, turning a
 * Connectors connection into a catalog source a project can pick ({@code ebx.SourceOnboarder},
 * {@code wizard.SourceOnboarding} - see their own Javadoc for the six-step chain). The two GETs are read-only
 * and open to any user; the two POSTs act as the agent account on {@code ebx-connectors} and are
 * administrators-only, preflighted at the route level like every other MCP-write route above.</li>
 * <li>{@code POST /projects/{code}/staging/suggest} — deterministic, rule-only first pass of the staging
 * suggestion for a GREENFIELD project: one entity per landing table, one attribute per landing column plus
 * the two technical identity attributes, one mapping per attribute, references from the accepted/proposed
 * landing relations. Writes {@code StagingEntity}/{@code StagingAttribute}/{@code AttributeMapping} rows,
 * origin {@code RULE}, status {@code PROPOSED}; an entity that already exists for the project is left
 * alone. Refuses a {@code TARGET_EXISTS} project (a later lot) and a project with no landing table yet.
 * Any authenticated user, like {@code /landing/plan}: it only writes proposals in the factory dataset with
 * the caller's own rights, no MCP call and no agent account.</li>
 * <li>{@code POST /projects/{code}/quality/suggest[?maxDistinctValues=N]} — normalisation/quality detection
 * pass over a project's ACCEPTED-or-APPLIED mappings: reads each landing column's distinct values with
 * counts inside EBX (capped at {@code maxDistinctValues}, default 200), runs the pure detectors
 * ({@code quality.Detectors}/{@code quality.QualityDetectors}) against them, and writes {@code
 * MappingStep}/{@code QualityRule} proposals, origin {@code RULE}, status {@code PROPOSED} - idempotent, a
 * kind already proposed for a mapping/attribute is left alone (its evidence is refreshed only while still
 * {@code PROPOSED}). No language model, nothing leaves the instance. Any authenticated user, like
 * {@code /landing/plan}/{@code /staging/suggest}: it only reads landing data and writes proposals with the
 * caller's own rights, no MCP call and no agent account.</li>
 * <li>{@code POST /projects/{code}/quality/suggest-llm[?entity=Name]} — language-model proposer of
 * normalisation/quality rules over the same ACCEPTED-or-APPLIED mapped columns {@code /quality/suggest}
 * reads. The factory drives the iteration, not the model: ONE AGENT RUN PER ACCEPTED ENTITY (an entity with
 * at least one mapped column), sequentially, each scoped to only that entity's columns, with its own {@code
 * measure} budget and refusal circuit breaker - one entity's failure (an early stop, a connector error) is
 * recorded and never aborts the others. The optional {@code entity} query parameter restricts the whole call
 * to one entity (validated against the project's own accepted-mapped entities), useful to re-run just the
 * one a previous call left unfinished. For each column, the model sees metadata and value SHAPES (real
 * values only when {@code Project.llmValueSharing = SAMPLE_VALUES}, a per-project opt-in), is asked to go
 * through every column of the entity in order - cleaning steps first, then quality rules, or an explicit
 * {@code note} naming the column and why it is left alone - and may test a regex/reference-list/check-digit
 * hypothesis with a read-only {@code measure} tool. Each proposal is MEASURED by the factory against the
 * real distinct values before it is stored, refused if it does not hold (a documented threshold per kind,
 * see {@code quality.QualityLlmPass}). Writes {@code MappingStep}/{@code QualityRule} rows, origin
 * {@code LLM}, status {@code PROPOSED}; a kind already present for a column (whatever its origin/status) is
 * never duplicated or touched. The response carries one block per entity ({@code status}, token/step counts,
 * {@code stepsCreated}/{@code rulesCreated}, {@code earlyStop}, {@code notes}, {@code
 * columnsWithoutProposalOrNote} - the columns that run neither proposed on nor called {@code note} for,
 * informational only) plus project-wide totals. Traced as a single project-level {@code PipelineStep}
 * ({@code QUALITY_SUGGEST_LLM}), marked {@code DONE} only when every entity run ended normally (its detail
 * names the ones that did not). Administrators only: it spends tokens and uses the language model
 * connector's key, like {@code /staging/suggest-llm}; answers a clear refusal (via {@code AgentRuntime}) when
 * no language model connector is configured.</li>
 * <li>{@code POST /projects/{code}/staging/suggest-llm} — semantic, language-model second pass over a
 * GREENFIELD project's rule-proposed entities: finds entities across source systems that describe the same
 * business object and proposes a canonical entity that unifies them, with aligned attributes. Writes
 * {@code StagingEntity}/{@code StagingAttribute}/{@code AttributeMapping} rows, origin {@code LLM}, status
 * {@code PROPOSED}; a rule-proposed entity is only ever read, never modified. Administrators only: it spends
 * tokens and uses the language model connector's key, like {@code /connection/test}.</li>
 * <li>{@code POST /projects/{code}/staging/generate} — builds and publishes the staging data model of a
 * project from its ACCEPTED (or already {@code APPLIED}) entities and attributes, one table per entity,
 * then creates its staging dataspace and dataset. Step by step, resumable after a failure, like
 * {@code /landing/generate}: an already-generated staging model whose tables no longer match the accepted
 * proposals (new entities/attributes accepted since) is refused without touching anything - regenerating a
 * changed staging model is not supported yet. On success every applied entity/attribute moves to
 * {@code APPLIED} and the project's own status becomes {@code STAGING}. Administrators only: it acts with
 * the factory's one agent account on the MCP servers, like {@code /landing/generate}.</li>
 * <li>{@code POST /projects/{code}/staging/load[?scope=new|all]} (any other {@code scope} value: 400, 2026-10-12) — transfers landing rows of the project into
 * its staging zone: transforms with {@code Transformations}, upserts by primary key in batches
 * ({@code FactorySettings.transferBatchSize}), and rejects (never writes to staging) a row a blocking EBX
 * constraint would refuse or whose foreign key is orphaned - the landing row's own {@code _status} becomes
 * {@code STAGED} or {@code REJECTED}, and every reject reason is recorded on a new {@code TransferReject}
 * row. {@code scope} (default {@code new}, 2026-09-23 - {@code transfer.IncrementalTransfer}) reads only
 * landing rows still at "version received, not yet transferred", and skips the actual staging write for a
 * row whose content hash still matches its staging row's own {@code _sourceHash} (counted separately,
 * {@code unchanged}); {@code scope=all} is the full-table pass, needed after a mapping/cleaning-step/
 * derived-field change. Not resumable-skip: a transfer is meant to be repeated - see {@code StagingLoader}'s
 * own Javadoc. Answers per-entity read/written/unchanged/skipped/rejected counts, {@code sourceHashField}
 * (false when an entity's staging table has not been regenerated since {@code _sourceHash} was added), the
 * first 10 reject reasons, duration and rows/second. Administrators only: unlike every other write route
 * here it runs with the CALLER's own session (no MCP call at all), so it needs the caller to actually hold
 * write rights on the project's staging and landing dataspaces - see {@code StagingLoader#load}'s own
 * Javadoc.</li>
 * <li>{@code POST /projects/{code}/master/generate[?overwriteTarget=true]} — builds and publishes the
 * GREENFIELD master data model of a project ({@code master.MasterModel}: one table per PROMOTED staging
 * entity, auto-increment {@code id} primary key), then creates its master dataspace and dataset. Step by
 * step, resumable after a failure, like {@code /staging/generate}; refuses a project with no loaded staging
 * entity yet. Never overwrites a {@code Project.masterDataspace}/{@code masterDataset} already set to
 * something this generator did not itself produce: refused with 409, naming what was found and what would be
 * generated - {@code overwriteTarget=true} replaces those two fields with the generated names (nothing is
 * ever deleted) - see {@code master.MasterTargetDecision}'s own Javadoc for the exact decision. Administrators
 * only: it acts with the factory's one agent account on the MCP servers, like {@code /staging/generate}.</li>
 * <li>{@code POST /projects/{code}/master/load[?scope=changed|full]} (any other {@code scope} value: 400, 2026-10-12) — (lot M-INC, 2026-10-12: {@code scope=changed} reads only the
 * staging rows the journal of changed staging rows recorded since the last successful load, entity by entity, and loads an entity in
 * full - saying why in the step detail - whenever the journal cannot stand for a full read; {@code full}, the default unless
 * {@code -Dmdm.factory.masterLoad.defaultScope=changed}, reads every staging row; see {@code master.MasterLoadScope}) transfers every promoted entity's own staging rows into the
 * master zone: inserts a new master record when {@code Xref} carries no {@code masterKey} yet for it, updates
 * one it already knows about, and SKIPS (counted, never overwritten) one the add-on has already merged away
 * ({@code master.MasterUpsertDecision}). A redirected foreign key (a member's own attribute, retargeted to its
 * promoted canonical entity by {@code MasterModel}) is resolved through {@code Xref}; an unresolved one rejects
 * the whole row into {@code TransferReject} with {@code stage = MASTER}. Not resumable-skip, like
 * {@code /staging/load}. Administrators only: like {@code /staging/load}, it runs with the CALLER's own
 * session (no MCP call), so the caller needs write access to the project's master and staging dataspaces - see
 * {@code MasterLoader#load}'s own Javadoc.</li>
 * <li>{@code POST /projects/{code}/master/reset?confirm=<masterDataspace>} — closes and deletes a GREENFIELD
 * project's factory-generated master zone (dataspace + DMA model), clears {@code Xref.masterTable}/{@code
 * masterKey}/{@code goldenKey} for the project, and moves every {@code DONE MASTER_MODEL}/{@code
 * MASTER_DATASET}/{@code MASTER_LOAD} {@code PipelineStep} to {@code RESET} - the recovery path when a master
 * zone was generated with the wrong entity set (`docs/MASTER-ZONE.md`, 2026-09-21 incident), since dropping a
 * table is NON-ADDITIVE and regeneration alone will not remove it. Every guard is in {@code
 * master.MasterResetEligibility}: GREENFIELD only, the dataspace/dataset must be exactly what this factory
 * would itself generate (never a foreign/hand-typed value), real {@code PipelineStep} evidence that the
 * factory actually created the dataspace, no Match and Merge metadataset yet, and {@code confirm} must equal
 * the master dataspace key exactly. Administrators only: it acts with the factory's one agent account on the
 * MCP servers (to delete the DMA model) and performs an irreversible repository-level deletion - see {@code
 * MasterReset}'s own Javadoc for the exact API calls.</li>
 * <li>{@code GET /projects/{code}/deletion/plan} — what deleting the project would touch: the row counts of
 * its own factory rows (always deleted), plus one item per generated object (a matching configuration, a
 * Connectors flow, a dataspace, a data model, a catalogue source or a sample-file connection the factory created) with whether it is currently eligible and, if not, why - see
 * {@code project.ProjectDeletionPlan}'s own Javadoc for every eligibility rule. Read-only, no write of any
 * kind, but administrators only: it probes {@code get_matching_config} with the factory's one agent account,
 * the same restriction every route that reaches an MCP server has. Preflights {@code MATCH_MERGE}
 * only when the project has a master dataspace - the only case where the plan itself calls
 * {@code get_matching_config}.</li>
 * <li>{@code POST /projects/{code}/delete?confirm=<code>[&generated=matching,flows,dataspaces,models,connections]} —
 * deletes the project: first the OPTIONAL generated objects named by {@code generated} (comma-separated,
 * blank/omitted deletes none of them), in the fixed order {@code project.ProjectDeletionPlan} computes
 * (matching configurations, then Connectors flows, then dataspaces, then data models - matching first so a
 * dataspace deletion never orphans a live metadataset - then, with {@code connections}, the catalogue sources
 * and the sample-file connections the factory created for the project, last: the flows that run on them must
 * be gone first), then, always, every one of the project's own rows in
 * the factory dataset, children before parents, and finally the {@code Project} row itself. {@code confirm}
 * must equal the project code exactly, the same ceremony {@code /master/reset} already uses. If any ticked
 * generated-object deletion fails, the whole call stops before touching a single factory row and reports every
 * failure - a retry recomputes the plan from the live state, so an object already removed is reported absent,
 * never a failure. Irreversible. Administrators only: like {@code /master/reset}, it acts with the factory's
 * one agent account on the MCP servers and performs irreversible repository-level deletions; preflights only
 * the servers the ticked options will actually reach ({@code MATCH_MERGE} for {@code matching}, {@code
 * CONNECTORS} for {@code flows} and {@code connections}, {@code DATA_MODEL} for {@code models} - {@code dataspaces} needs none, a
 * plain repository-level call) - see {@code
 * ebx.ProjectDeleter}'s own Javadoc for the exact API calls.</li>
 * <li>{@code GET /projects/{code}/reset/plan?from=<stage>} — what cleaning up the project's pipeline FROM
 * {@code stage} onward (one of {@code LANDING}/{@code STAGING}/{@code MASTER}/{@code MATCHING} - resetting a
 * stage also resets every stage downstream of it) would touch: one item per generated object in scope with its
 * disposition ({@code DELETE}/{@code EXCLUDED}/{@code REFUSED}), the row counts of the factory rows that would
 * be deleted, and the {@code PipelineStep} names that would move to {@code RESET} - see {@code
 * project.StepResetPlan}'s own Javadoc for every rule (unlike {@code /deletion/plan}, ANY {@code REFUSED} item
 * blocks the whole call, never just an option a person can leave unchecked: a stage cannot be "cleaned" while leaving a
 * generated object nobody can prove the factory owns still in place). Read-only, but administrators only, same
 * guard and same conditional {@code MATCH_MERGE} preflight as {@code /deletion/plan}.</li>
 * <li>{@code POST /projects/{code}/reset?from=<stage>&confirm=<code>} — cleans up FROM {@code stage} onward:
 * deletes every {@code DELETE}-disposition generated object (stopping before touching a single row on the
 * first failure, exactly like {@code /delete}), then one {@code Procedure} that deletes the in-scope factory
 * rows (children before parents), clears the {@code Project}/{@code StagingEntity}/{@code Xref} fields that
 * memorize the reset zones, moves every in-scope {@code PipelineStep} to {@code RESET}, and writes this call's
 * own {@code STEP_RESET} step ({@code scope} = {@code stage}). The project's own identity, its sources
 * ({@code ProjectSource} rows) and every {@code AgentRun}/{@code AgentStep}/{@code PipelineStep} history row
 * are never deleted - see {@code ebx.StepResetter}'s own Javadoc for the exact row/field scope of each stage
 * and the corrections it made to the original brief. {@code confirm} must equal the project code exactly.
 * Irreversible. Administrators only: same posture as {@code /delete}, preflighting only the servers this
 * particular stage could reach.</li>
 * <li>{@code POST /projects/{code}/matching/suggest} — deterministic, rule-only proposal of at most one
 * {@code MatchPlan} per PROMOTED master entity (`docs/MATCHING.md`), from its accepted attributes and any
 * accepted CHECK_DIGIT {@code QualityRule} evidence on them: a validated identifier becomes its own
 * exact-match, MATCH-terminal tier — the only role this domain-free pass ever assigns (2026-09-21: it no
 * longer guesses a fuzzy anchor or a blocking/supporting field from an attribute's NAME; see {@code
 * master.MatchPlanRules}' own Javadoc for what was removed and why). An entity with no validated identifier
 * is skipped, naming the language-model pass below as the next step. A child entity (an accepted foreign key
 * to another entity) is not proposed by default. Never touches a plan already {@code ACCEPTED}/{@code
 * REJECTED}/{@code APPLIED} — only a still-{@code PROPOSED} plan (or none) is written/refreshed, so
 * re-running after an entity gains new attributes picks up the new evidence without overwriting a human
 * decision. Administrators only.</li>
 * <li>{@code POST /projects/{code}/matching/suggest-llm[?entity=Name]} — semantic, language-model proposal of
 * the field ROLES a deterministic pass cannot infer from names alone (fuzzy anchor, supporting field,
 * blocking/clustering field), for every PROMOTED, non-child master entity of the project, or - with
 * {@code entity} - just that one (reject/repropose lot, 2026-09-22: the "Propose another" action on a single
 * {@code MatchPlan} line marks it {@code SUPERSEDED} first, then calls this scoped to that entity alone).
 * {@code SUPERSEDED} is rewritable exactly like {@code PROPOSED} (never {@code ACCEPTED}/{@code REJECTED}
 * with a {@code USER} origin). Reads each accepted
 * attribute's metadata, its accepted cleaning steps and quality rules, and the foreign keys between entities
 * from the factory's own tables; reads the profiling NUMBERS (row count, fill rate, distinct count/ratio,
 * most-frequent-value/token SHARE — real values only when {@code Project.llmValueSharing = SAMPLE_VALUES},
 * the same per-project opt-in {@code /quality/suggest-llm} reads, never a caller-supplied override) and the
 * allowed algorithm/null-handling/merge-function vocabulary and per-field pre-clustering capability from the
 * {@code match-merge} MCP server's own {@code profile_table_for_matching}/{@code matching_config_schema} —
 * this pass never invents its own algorithm list or profiles data itself ({@code
 * master.MatchPlanLlmPass}/{@code ebx.MatchPlanLlmSuggester}, coordinator review, 2026-09-21). The editor's
 * own {@code matching_guide} text (algorithm selection, threshold tuning, merge strategy) is fed to the
 * model instead of a recipe written in this codebase. Proposes one {@code MatchPlan} per entity through the
 * same structured requirements JSON {@code master.MatchingPolicyTranslator} already consumes. Guarded, with
 * a refusal explaining what to fix and a circuit breaker after 3 repeated refusals: every field must be a
 * real accepted attribute of the entity; the algorithm must be one the server reports as allowed; a
 * MATCH-terminal tier only ever uses the exact algorithm at score 100 (anything fuzzy terminates {@code
 * SUSPECTED}); the model never sets {@code autoMerge} (always false, like the rule pass); a clustering field
 * must be capable of pre-clustering AND (when reliably, i.e. {@code EXACT}ly, measured) sufficiently filled
 * and neither constant nor near-unique; an exact tier's field must itself be near-unique when reliably
 * measured — a field whose statistics are only sampled/unsupported is never refused on that account alone,
 * but caps the whole plan's confidence instead. Requires the project's master zone to exist and be loaded
 * (profiles the MASTER table, not staging). Same idempotence rule as the rule pass — never touches a
 * decided or {@code USER}-origin plan, {@code SUPERSEDED} excepted (see above). Traced as a {@code
 * PipelineStep} ({@code MATCHING_SUGGEST_LLM}).
 * Administrators only: it spends tokens and uses the language model connector's key, like
 * {@code /staging/suggest-llm}.</li>
 * <li>{@code POST /projects/{code}/matching/configure} — for every promoted entity with an {@code ACCEPTED}
 * {@code MatchPlan}: translates {@code MatchPlan.requirements} into a real Match and Merge configuration
 * ({@code master.MatchingPolicyTranslator}) and drives the ordered MCP call chain (`docs/MATCHING.md`
 * &sect;4) — {@code get_matching_config} (refuses if a configuration already exists that this generator's
 * own {@code PipelineStep} history has no record of creating), {@code generate_matching_policy},
 * {@code generate_merge_policy}, {@code patch_matching_config} (links them), {@code validate_matching_config},
 * {@code activate_matching}. {@code MatchPlan.autoMerge} (default false) gates whether an exact/identifier
 * tier's own {@code MATCH} outcome survives translation or is forced to {@code SUSPECTED} — a merge policy's
 * {@code mode} does not gate this (CLAUDE.md §8). On success the plan becomes {@code APPLIED}. Step by step,
 * resumable per entity, one entity's failure never blocks another's. An entity whose {@code MatchPlan} is
 * {@code REJECTED} ("No matching for this entity", reject/repropose lot, 2026-09-22) is listed under its own
 * {@code excludedByDecision} array (entity + the reviewer's comment), separate from {@code notAccepted} (an
 * entity with no plan yet, or one still {@code PROPOSED}/{@code SUPERSEDED}) — the two read very differently:
 * one is "not decided yet", the other "decided against". Administrators only: it acts with the factory's one
 * agent account on the {@code match-merge} MCP server.</li>
 * <li>{@code POST /projects/{code}/matching/run[?scope=incremental|all][&maxWaitMs=N]} (any other {@code scope} value, {@code full} included: 400 with the two accepted values, 2026-10-12; it used to run an incremental run) — for every promoted entity with
 * an {@code APPLIED} {@code MatchPlan}: launches a SCOPED matching run through the add-on's own public Java
 * API ({@code MatchingOperations.matchRecords}, {@code ebx.MameMatchingApi}) — never an unscoped run, which
 * would discard every existing verdict of the whole table. Default scope is {@code master.MatchingRunScope
 * #incremental} (the most recently loaded batch, by the master table's own {@code _loadId}); {@code
 * scope=all} selects every record with an {@code Xref} row of this project instead. Polls the asynchronous
 * process up to {@code maxWaitMs} (default 30s) rather than blocking indefinitely, and only refreshes
 * {@code Xref.goldenKey} ({@code master.GoldenKeyRefreshDecision}, {@code ebx.GoldenKeyRefresher}) once the
 * process reports {@code DONE} — a still-running process is reported as such, to re-check later. Runs with
 * the CALLER's own session, no agent-account credentials on this path. Administrators only.</li>
 * <li>{@code POST /projects/{code}/matching/refresh-golden-keys[?entity=<name>]} — "Keeping Xref.goldenKey
 * fresh" lot, 2026-09-23: re-reads {@code RecordMetadata} and rewrites a stale {@code Xref.goldenKey} for
 * every promoted entity (or just {@code entity} when given) WITHOUT launching a new matching run — the
 * on-demand counterpart of what {@code matching/run} already does as a side effect of a {@code DONE}
 * process, for when a steward's Unmerge/Change-state click through the add-on's own standard services (no
 * hook exists on that path) left the cache stale. The same call also backs the
 * {@code GoldenKeyRefreshScheduledTask} Task Scheduler task. Administrators only.</li>
 * <li>{@code POST /projects/{code}/matching/simulate?entity=..&masterKey=..} — a zero-risk, read-only
 * simulation of what the entity's configured (APPLIED) policy would decide for one existing master record
 * ({@code MatchingOperations.simulateMatch}) — nothing is persisted. Administrators only.</li>
 * <li>{@code POST /projects/{code}/matching/survivorship?entity=<name>} — trusted-sources lot, 2026-09-22:
 * re-applies {@code master.MatchingPolicyTranslator#buildMergePolicy}'s desired trusted-source ranking and
 * survivor rules onto an entity's ALREADY-CONFIGURED merge policy ({@code set_trusted_sources} + a minimal
 * {@code patch_matching_config} op list computed by {@code master.MergeConfigPatchPlanner}), without deleting
 * or regenerating its decision tree. Requires {@code matching/configure} to have already run once for the
 * entity. Administrators only.</li>
 * <li>{@code POST /projects/{code}/matching/simulate-preview/remove?entity=<name>} — simulation-preview-policy
 * lot, 2026-09-22: drops the {@code <name>__preview} matching/merge policy the Proposal review's "Simulate on
 * N pivots" button writes on every click while a plan is not yet {@code APPLIED} ({@code
 * ebx.MatchingPreviewPolicy}). Idempotent — nothing to remove is a {@code 200} no-op, never an error.
 * {@code matching/configure} already calls this automatically right after a successful accept; this route
 * exists only for manual cleanup (e.g. a plan that was simulated and then explicitly rejected instead of
 * accepted). Administrators only.</li>
 * <li>{@code POST /projects/{code}/capabilities/suggest-llm[?entity=<name>][&maxPromptChars=<n>]} — language-model proposer of
 * two capability families the product owner asked for (task brief, 2026-09-21): {@code SCRIPT_CONSTRAINT}
 * (positioning an EXISTING, already-published EBX Script library function as a validation constraint on the
 * model — run by EBX's own validation engine, never this pass) and {@code EXTERNAL_SERVICE} (advice that an
 * EXISTING connector operation could be interesting for a given entity/attribute — never called on real
 * data by this pass). Iterates the project's ACCEPTED-or-APPLIED staging entities ONE AGENT RUN PER ENTITY
 * ({@code capability.CapabilityLlmPass}/{@code ebx.CapabilityLlmSuggester} — a first real run of the
 * similarly-shaped quality pass showed a model stops after the first entity when given the whole project at
 * once); {@code entity=<name>} restricts the run to one entity. One entity's own failure never aborts the
 * others — each gets its own block in the response, with totals. Both inventories (the {@code scripting}
 * MCP server's published library functions, the {@code ebx-connectors} MCP server's read/search operations)
 * are built once per call and degrade to empty, with a note, when their server is unreachable — the
 * scripting server is OPTIONAL ({@code McpServers.OPTIONAL}). Both are COMPACT (one line per library/
 * connector); the model calls a local {@code get_library_functions}/{@code get_service_details} tool to see
 * full detail before it may propose anything from that library/connector — a {@code connectors_describe} call
 * (never with {@code operation}/{@code inputs}) happens lazily, only for a connector the model actually asks
 * about, and a refusal (e.g. no secret configured) leaves the connector suggestable with that reason as its
 * readiness detail rather than dropping it. {@code maxPromptChars} (default 60000 characters) refuses an
 * entity's own LLM call locally, before it is ever sent, when the estimated request size exceeds it. Every
 * guard is structural only (the library/
 * function or connector/operation genuinely exists, the binding names real accepted attributes, arity and
 * basic type compatibility, {@code MASTER} zone only for a promoted entity, a service's operation kind is
 * {@code read}/{@code search} only) — never a decision from an attribute's NAME. Writes {@code
 * CapabilitySuggestion} rows, origin {@code LLM}, status {@code PROPOSED}; a kind already proposed for an
 * entity/attribute is never duplicated or touched. Traced as a {@code PipelineStep} ({@code
 * CAPABILITY_SUGGEST_LLM}). Administrators only: it spends tokens and uses the language model connector's
 * key, like {@code /staging/suggest-llm}.</li>
 * <li>{@code POST /projects/{code}/capabilities/{id}/apply[?measureMaxRecords=N]} — applies one ACCEPTED (or
 * re-applies an already {@code APPLIED}) {@code SCRIPT_CONSTRAINT} suggestion directly onto the staging or
 * master model ({@code ebx.CapabilityApplier}, apply lot 2026-09-21, reshaped by a coordinator review the same
 * day into two phases): declares the constraint ({@code data-model.execute_operation(set_script_constraint)}),
 * authors its body (a deterministic template for a {@code boolean}-returning function, one bounded
 * language-model call otherwise), publishes, verifies {@code authored:true} - any failure THROUGH this point
 * removes the declaration again and republishes only if a publish had already succeeded, leaving the row
 * {@code ACCEPTED} with {@code applyMessage} naming why. Only THEN is the row marked {@code APPLIED} — a
 * plain-language explanation and the measured effect (bounded to {@code measureMaxRecords}, default 200 000
 * records - see {@code /measure} below) are best-effort AFTER that point: a failure there never rolls back and
 * never fails the apply, it is noted in {@code applyMessage} on an otherwise {@code APPLIED} row. {@code
 * EXTERNAL_SERVICE} rows are refused outright — out of scope for this lot. <b>Lot M5 (2026-10-04): the three routes {@code
 * apply}, {@code apply?ids=} and {@code remove} dispatch on the row's own nature through {@code ebx.CapabilityDispatcher}, exactly
 * as the review screen does</b>: a {@code SCRIPT_DERIVATION} goes to {@code ebx.DerivationApplier} (declares
 * two table triggers on the staging model that keep the stored derived attribute up to date (lot T1), authors them, publishes the model, then
 * backfills the rows already there - needs the {@code data-model} AND {@code scripting} MCP servers; lot T2 (2026-10-04): the answer's {@code master} node says what happened to the same two triggers on the
 * master table - {@code DECLARED}, {@code SKIPPED} with a reason, or {@code FAILED} without ever failing the staging apply; {@code remove} takes them off the master table too), an {@code EXTERNAL_SERVICE} keeps a clear refusal; {@code apply} without ids also applies every {@code ACCEPTED}
 * {@code SCRIPT_DERIVATION}. Administrators only: it acts as the
 * agent account on the {@code data-model}/{@code scripting} MCP servers and may spend language-model tokens.
 * A single-id call throws (HTTP 409) on failure, same contract as before batching existed.</li>
 * <li>{@code POST /projects/{code}/capabilities/apply[?ids=a,b,c][&measureMaxRecords=N]} — the SAME sequence
 * for several suggestions at once, grouped by TARGET MODEL with exactly ONE {@code publish_data_model} per
 * model (a publish takes tens of seconds to minutes — never one per constraint). {@code ids} omitted or empty
 * defaults to every {@code ACCEPTED SCRIPT_CONSTRAINT} of the project not yet applied. Never throws: answers
 * {@code {"results":[{"suggestion","ok","status"|"error",...}, ...],"applied":M,"failed":K}} — one constraint's
 * failure (declare/author, or the group's own publish) never aborts the others. Administrators only, same
 * reasoning as the single-id route.</li>
 * <li>{@code POST /projects/{code}/capabilities/{id}/remove} — takes an {@code APPLIED SCRIPT_CONSTRAINT}
 * back off the model ({@code unset_element_property}, {@code item} set to the exact script name — CLAUDE.md
 * §5, fact 8) and republishes; the row reverts to {@code ACCEPTED} with its applied-* bookkeeping and
 * measured effect cleared. Idempotent (a declaration already gone is treated as already-removed, not a
 * failure); if the declaration comes off but the republish itself fails, the row STAYS {@code APPLIED} (the
 * published model still carries the constraint) with {@code applyMessage} saying so, so Remove can be
 * retried. Administrators only, same reasoning as {@code /apply}.</li>
 * <li>{@code POST /projects/{code}/capabilities/{id}/backfill} — lot T1 (2026-10-04): recomputes the rows the staging table already holds for
 * an {@code APPLIED SCRIPT_DERIVATION} (the derived attribute is a stored field fed by two table triggers; a trigger only fires on a write -
 * {@code ebx.DerivationBackfillPass}). Run automatically at the end of an apply when the table has rows; this is the on-demand form. Administrators only.</li>
 * <li>{@code PUT /projects/{code}/capabilities/{id}/binding} — lot P1 (2026-10-04): a PERSON settles the parameter(s) of a {@code SCRIPT_DERIVATION}
 * that the model left {@code toBeProvided} (or a literal to change). Body {@code {"<parameter>":"<value>", ...}}; each named parameter must exist in the stored
 * binding and be a {@code literal} or a {@code toBeProvided} entry (an attribute-bound one is never changed here); a blank value is allowed only on an entry
 * that already was a literal, never to settle a {@code toBeProvided}; the value is stored as {@code {"literal":"<value>"}}. 400 on a bad body (an unknown parameter
 * answers the list of settable ones), 404 unknown project/suggestion, 409 not a derivation / rejected. Answers {@code {"binding","changed","toBeProvidedRemaining",
 * "reapplyNeeded"}}; when the suggestion is already APPLIED and a value changed, {@code applyMessage} is set to the fixed re-apply sentence and {@code
 * reapplyNeeded} is true - applying stays an explicit action ({@code POST .../apply} re-declares the triggers in place, republishes and backfills). Administrators only.</li>
 * <li>{@code POST /projects/{code}/capabilities/{id}/measure} — re-measures an {@code APPLIED SCRIPT_CONSTRAINT}
 * on demand, WHATEVER the table's size (no bound, unlike the automatic measurement above) - only the
 * measurement fields are rewritten, {@code appliedAt}/{@code scriptBody}/{@code explanation} untouched. The
 * review screen offers this as "Measure again", and as the way to see the real effect of a constraint whose
 * automatic measurement was skipped for being too large. Administrators only.</li>
 * <li>{@code GET /projects/{code}/lineage/{entity}?key=<formatted master primary key>} — the read side of the
 * record-lineage screen (lot 4, `PLAN.md` "Lineage par enregistrement"): for one master record, the chain of
 * every contributing source (system, landing row, staging row), a field-by-field table of which source fed
 * which value through which landing column and cleaning steps, and the record's Match and Merge state - or,
 * when matching has never run, the add-on is unavailable, this record carries no metadata, or the add-on's
 * own metadataset could not be read, an explicit {@code merge.reason} rather than an error ({@code
 * lineage.RecordLineage}/{@code ebx.RecordLineageReader}). Read-only, bounded to this one record's own chain
 * - never a table scan. {@code 404} for an unknown project, entity (not a promoted master entity of the
 * project), or record key - a master record the caller's session may not see answers the SAME {@code 404}
 * as one that does not exist. {@code 400} for a blank or unparsable {@code key}; {@code 500}, naming the
 * failed step but never a value, for a genuinely unexpected failure outside the merge block. Any
 * authenticated user, like {@code /relations/check}/{@code /staging/suggest} - no MCP call and no agent
 * account - but, UNLIKE those two, this route returns real field VALUES of master/staging/landing records,
 * not counts or metadata: {@code ebx.RecordLineageReader} therefore enforces the caller's OWN EBX read
 * permissions record by record and field by field ({@code Session.getPermissions()}, verified by {@code
 * javap} - see its own Javadoc for exactly what that means and what was NOT run live), never relying on
 * "any authenticated user" alone to gate what is actually visible. This is deliberately NOT
 * administrators-only: the lineage screen is for data stewards.</li>
 * <li>{@code GET /projects/{code}/xrefs/{entity}?key=<formatted master primary key>} — "cross-references of
 * a golden record" (the product owner's request, 2026-09-23): a flat, one-row-per-{@code Xref} array of every source
 * that fed the record being looked at — the golden's own row (if any — an auto-created golden has none) plus
 * every record merged into it. A PURE projection ({@code lineage.CrossReferences#project}) of the exact same
 * {@code RecordLineageReader.read(...)} answer {@code /lineage} above returns — never a second read, never a
 * second permission decision: this route calls the identical reader entry point, then reshapes its {@code
 * sources} array (never {@code suspectSources} — a pending suspect is not yet a confirmed contributor, so it
 * is not a cross-reference) into flat rows ({@code masterKey}, {@code golden}, {@code sourceSystem}, {@code
 * sourceId}, {@code stagingTable}, {@code stagingKey}, {@code landingDataset}, {@code landingTable}, {@code
 * landingKey}, {@code loadId}, {@code receivedAt}, {@code restricted}, plus the already-built {@code
 * landingUrl}/{@code stagingUrl} "open in EBX" links), with the anchor's own {@code state}/{@code goldenKey}
 * at the top level. Same authorisation, same {@code 404}/{@code 400}/{@code 500} answers as {@code /lineage}
 * — see that route's own Javadoc — because it is, literally, the same read. Any authenticated user, not
 * administrators-only, for the same reason.</li>
 * <li>{@code POST /projects/{code}/catalog/declare-lineage} — declares the project's STRUCTURE lineage
 * (source table &rarr; landing table &rarr; staging attribute &rarr; master field, cleaning steps and the
 * mapping's own transformation as the edge's description) to the sibling data catalog, over the same
 * channel {@code ebx-connectors} uses for its own declared lineage (HTTP POST, EBX Basic authentication,
 * straight to {@code /ebx-data-catalog/rest/v1/openlineage} - {@code ebx.CatalogLineageDeclarer}, see its
 * own Javadoc for the full "how Connectors does it" writeup). Only ACCEPTED-or-{@code APPLIED} mappings are
 * ever declared; because a declaration is the job's whole current lineage, a mapping later REJECTED is
 * retired from the catalog's graph on the very next call, automatically. Records one {@code PipelineStep}
 * ({@code CATALOG_LINEAGE}) per zone (landing/staging/master) with job/column-link counts; when the catalog
 * is not installed, every zone is recorded {@code SKIPPED} and no HTTP request is ever sent - never an
 * error either way. Administrators only, like the other write routes that act on an external system with
 * the factory's own agent account.</li>
 * <li>{@code GET /diagnostics/master-zone-index} — read-only snapshot of {@code ebx.MasterZoneIndex}'s own
 * activation cache for the "Record lineage" record-level action ({@code ebx.ui.RecordLineageOnRecordPermissionRule}):
 * every currently-cached (dataspace, dataset, table) triple, when it was last (successfully or not) refreshed,
 * and the class/message of the last refresh failure if the cache is currently degraded (coordinator review,
 * 2026-09-22, "fact 2" - the product owner measured the action missing entirely from a genuine master record's own
 * Actions menu, with nothing in {@code kernel.log}; this route exists so that diagnosis takes ten seconds, not
 * a source-reading session). With {@code ?dataspace=&dataset=&table=} all three given, also answers whether
 * that exact triple would currently be enabled - the SAME check {@code RecordLineageOnRecordPermissionRule}
 * itself makes, so a "no" here is the actual, current answer, not a guess. Administrators only: the triple set
 * reveals every project's own master-zone location, which is module configuration, not business data, but
 * still not for every user to page through.</li>
 * <li>{@code GET /projects/{code}/delivery/domain} - delivery lot 1 ({@code docs/DELIVERY.md}): the EBX Staging domain of
 * the project as a list of ready component bodies for {@code POST /ebx-dataservices/rest/staging/v1/domains/{domain}/components}
 * (dataspaces, DMA models and publications, datasets, the Factory's decision tables, Connectors' flow tables, the applied
 * Match and Merge configurations), what is refused and why (a zone whose model or dataset step is not DONE), and a
 * pre-check of what Staging itself does not verify (connectors to create on the target and their secrets to set, reference
 * datasets, the add-on). Read-only, the caller's own session, no MCP call and no agent account: any authenticated user.
 * The domain itself is created, exported and imported by EBX Staging under EBX's own authentication - the Factory adds
 * no import endpoint.</li>
 * <li>{@code POST /projects/{code}/distribution/targets/{targetId}/stop} - wave 13 (2026-10-12, {@code docs/DISTRIBUTION.md} "Following and stopping a send"): a send
 * runs each mapping as a Connectors run the job follows ({@code ebx.DistributionSendCalls}); this route stops the target's send - cancels its queued or running
 * first-full / send-now jobs (the running one stops its run at its next poll and ends STOPPED) and stops, with {@code connectors_run_stop}, a recorded run that
 * {@code connectors_run_get} still reports running (a run that outlived a restart). Answers {@code {targetId, stopped, jobsCancelled[], runsStopped[], message}};
 * {@code stopped=false} when nothing was running. The send routes ({@code first-full-send}, {@code send}) refuse with {@code 409} "a send of target N is still
 * running: run R" while a recorded run is running. Administrators only.</li>
 * <li>{@code GET /projects/{code}/distribution/product} - distribution lot 2 ({@code SPEC-distribution.md} &sect;7c,
 * {@code docs/DISTRIBUTION-PRODUCT.md}): reachable warehouse connections (a Snowflake connectivity probe through
 * ebx-connectors' in-JVM bridge; Databricks always reported unsupported - no {@code TablePublisher} for it yet),
 * this project's candidate tables (master entities, a cross-references candidate per root master entity, catalogue
 * assets to add) and a default draft. {@code POST /projects/{code}/distribution/product[?dryRun=true]} plans/
 * validates the posted draft ({@code distribution.product.ProductPlan}) without writing anything when
 * {@code dryRun=true}; otherwise writes the {@code DataProduct}/{@code OutputPort} rows of the data catalog (as
 * data, in a {@code Procedure} running as the caller's session - no compile dependency on ebx-data-catalog), one
 * Connectors OUTBOUND mapping per publishable table, and launches the first publication. A cross-references table
 * is a SECOND root mapping on its host master table (input {@code crosswalk=true}, {@code golden(...)}/
 * {@code mdmState(...)} transformations) - never the Factory's own shared {@code Xref} table, which has no
 * per-project filter in today's Connectors mapping mechanism (see {@code distribution.product.ProductPlan}'s own
 * Javadoc). The chosen refresh cadence only sets the port's {@code maxLagMinutes}/{@code autoPublish}: the actual
 * republish is the data catalog's own {@code PortSlaTask} sweep, not a schedule this route creates. Idempotent
 * (updates what already exists rather than duplicating it). Administrators only, like the other routes that act as
 * the agent/session on ebx-connectors and write into the shared catalogue.</li>
 * <li>Automatic intake (2026-10-05, {@code docs/AUTOMATIC-INTAKE.md}; facade {@code ebx.Intake}): {@code GET /projects/{code}/intake} - the {@code IntakeStatus} JSON
 * (any authenticated user; cheap, meant to be polled every few seconds: nothing is counted on that path). {@code POST /projects/{code}/intake/settings} (JSON body
 * {@code {"reviewRejects":true,"stopRejectPercent":5,"sources":[{"sourceSystem":"CRM","quietSeconds":60,"maxWaitMinutes":10,"minRows":1,"batchSignal":false}]}};
 * a missing/null value is the default, a missing {@code stopRejectPercent} means never stop), {@code POST /projects/{code}/intake/on},
 * {@code /pause}, {@code /resume}, {@code /off}, {@code /process} - administrators, or holders of the role of setting {@code pipelineLauncherRole}; every one answers the new
 * {@code IntakeStatus}; {@code /on} installs the landing triggers through the caller's own MCP session and can take minutes. {@code POST
 * /projects/{code}/intake/{source}:complete} (no body) - the application's "batch complete" call: any authenticated user with write access to that application's landing
 * dataset; answers {@code {"project","sourceSystem","accepted","intakeState"}} ({@code accepted=false} when the intake is not ON, not an error). The {@code :complete}
 * suffix is declared as {@code {source: [^/:]+}:complete} and was matched against CXF's own {@code URITemplate} (see {@code IntakeRoutesTest}). State changes answer
 * {@code 409} with a sentence when refused, {@code 403} when not allowed, {@code 404} for an unknown project/application. {@code POST /projects/{code}/intake/{source}:read} - "Read now" of
 * one CONNECTORS application (operator; the intake not OFF): queues a plain landing load as a background job and answers {@code 202} with its {@code jobId}; the chain starts after the read by itself.</li>
 * </ul>
 */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
public final class FactoryResource
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final SessionContext sessionContext;

	public FactoryResource(@Context final SessionContext pSessionContext)
	{
		this.sessionContext = pSessionContext;
	}

	@GET
	@Path("/status")
	public Response status()
	{
		if (!this.sessionContext.getSession().isUserInRole(com.orchestranetworks.service.Profile.ADMINISTRATOR))
		{
			// security review (2026-09-24): liveness only for a non-administrator - no install, no row counts
			return Response.ok("{\"module\":\"" + FactoryPaths.MODULE_NAME + "\",\"alive\":true}").build();
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
		out.put("module", FactoryPaths.MODULE_NAME);
		// audit F-03: which build is running (version.properties written by build.sh; "unknown" when absent) - administrators only, like the rest of this answer
		out.put("version", ModuleVersion.version());
		out.put("commit", ModuleVersion.commit());
		out.put("buildTime", ModuleVersion.buildTime());
		// production review 2026-11-12 (F3-08): the two functions delivered after v1.3.0 are CLOSED unless the JVM property says true; read at each call
		final ObjectNode switches = out.putObject("featureSwitches");
		final ObjectNode agentsSwitch = switches.putObject("agents");
		agentsSwitch.put("enabled", com.on.ebx.factory.ebx.FeatureSwitches.agents());
		agentsSwitch.put("property", com.on.ebx.factory.ebx.FeatureSwitches.AGENTS_PROPERTY);
		// only whether the activation key exists, never its value
		agentsSwitch.put("activationKeyPresent", com.on.ebx.factory.ebx.AgentIdentity.activationKeyPresent(FactoryInstaller.dataset(this.sessionContext.getRepository())));
		final ObjectNode driftSwitch = switches.putObject("sourceDrift");
		driftSwitch.put("enabled", com.on.ebx.factory.ebx.FeatureSwitches.sourceDrift());
		driftSwitch.put("property", com.on.ebx.factory.ebx.FeatureSwitches.SOURCE_DRIFT_PROPERTY);
		// 1.4.3 (X3-11): where the permissions of the NEW generated zones come from (parent dataspace by default, a named dataspace, or none)
		out.set("zonePermissions", com.on.ebx.factory.ebx.ZonePermissions.statusJson(this.sessionContext.getRepository()));
		final ObjectNode embeddedJars = out.putObject("embeddedJars");
		embeddedJars.put("on-ebx-agent.jar", ModuleVersion.agentJarSha256());
		embeddedJars.put("on-ebx-mapping.jar", ModuleVersion.mappingJarSha256());
		// X3-07 / X3-08: the Jackson jars of the module's WEB-INF/lib (a package and, since 1.4.1, a tools/install.sh installation alike)
		for (final java.util.Map.Entry<String, String> packaged : ModuleVersion.packagedJars().entrySet())
		{
			embeddedJars.put(packaged.getKey(), packaged.getValue());
		}
		// X3-08 (1.4.1): the JSON read bounds in force (Jackson's defaults since 2.15, kept on purpose; docs/OPERATIONS.md "JSON read bounds")
		out.set("readBounds", com.on.ebx.factory.core.JsonLimits.statusJson());
		// F2-11 (1.4.2): the bounds of a project restore (bytes read, rows), JVM properties, wide defaults
		out.set("restoreBounds", com.on.ebx.factory.backup.RestoreLimits.statusJson());
		out.put("dataspace", FactoryPaths.DATASPACE);
		out.put("dataset", FactoryPaths.DATASET);
		out.put("datasetState", FactoryInstaller.datasetState(this.sessionContext.getRepository()));
		out.put("user", this.sessionContext.getSession().getUserReference().getUserId());
		// audit F-16: false = the internal token API changed on this build, every call to EBX's own MCP servers will fail (or use the agent account); absent = not checked
		final Boolean tokenMintable = com.on.ebx.factory.ebx.SessionToken.startupMintable();
		if (tokenMintable != null)
		{
			out.put("sessionTokenMintable", tokenMintable.booleanValue());
		}
		// wave 11 (audit F-26): the versioned contract of ebx-connectors - found or not, which version, which way the calls it covers go (ebx.factory.connectors.via)
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		// wave 12: "agent" = the way the agent loop reaches the model (llm.chat of the contract, or direct with the key read here) and the check of the connection's maxTokens
		final ObjectNode contract = com.on.ebx.factory.ebx.ConnectorsContractClient.statusJson();
		contract.set("agent", com.on.ebx.factory.ebx.AgentRoute.statusJson(this.sessionContext.getSession(), com.on.ebx.factory.ebx.AgentRuntime.chosenConnection(dataset)));
		out.set("connectorsContract", contract);
		// F2-12: the in-JVM bridge has 8 threads; a call that outlives its timeout keeps its thread until Connectors lets go
		out.set("connectorsBridge", com.on.ebx.factory.ebx.ConnectorsBridge.statusJson());
		// test cycle (lot F4): llm.record / llm.replay - whether the language model's answers are recorded or replayed (system properties ebx.factory.llm.record.dir / replay.dir)
		out.set("llm", com.on.ebx.factory.ebx.AgentRuntime.recordReplayStatus());
		if (dataset != null)
		{
			final ObjectNode tables = out.putObject("tables");
			for (final com.orchestranetworks.schema.Path table : FactoryPaths.TABLES)
			{
				final RequestResult rows = dataset.getTable(table).createRequestResult(null);
				try
				{
					tables.put(table.getLastStep().format(), rows.getSize());
				}
				finally
				{
					rows.close();
				}
			}
		}
		return Response.ok(out.toString()).build();
	}

	/**
	 * Lot 3 measurement: which credentials EBX's own MCP servers accept from this node ({@code ebx.AuthProbe}).
	 * Administrators only; read-only (initialize + tools/list); never returns a token, cookie or password and never relays the caller's cookies (audit F2-09). Answers 404
	 * unless the JVM runs with {@code -Debx.factory.authProbe=true} (security hardening, 2026-09-24).
	 */
	@GET
	@Path("/connection/auth-probe")
	public Response authProbe(@Context final jakarta.servlet.http.HttpServletRequest pRequest)
	{
		// a diagnostic that mints tokens: off unless the JVM is started with it switched on
		if (!"true".equalsIgnoreCase(System.getProperty("ebx.factory.authProbe", "false")))
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Not found");
		}
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /connection/auth-probe");
		if (refused != null)
		{
			return refused;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		return Response.ok(com.on.ebx.factory.ebx.AuthProbe.run(this.sessionContext.getSession(), dataset).toString())
			.build();
	}

	/**
	 * Lot C1/C2: the background jobs of a project - QUEUED and RUNNING ones (with their position on their resource and,
	 * for a running one, its parent step's progress) plus the last ten finished. Polled by the wizard every few seconds
	 * instead of reloading the whole page. Administrators only (the jobs are theirs).
	 */
	@GET
	@Path("/projects/{code}/jobs")
	public Response projectJobs(@PathParam("code") final String pProjectCode, @QueryParam("lang") final String pLang)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/jobs", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final com.onwbp.adaptation.RequestResult projects = dataset.getTable(FactoryPaths.PROJECT).createRequestResult("./code='" + pProjectCode.replace("'", "''") + "'");
		final Adaptation project;
		try
		{
			project = projects.nextAdaptation();
		}
		finally
		{
			projects.close();
		}
		if (project == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Project " + pProjectCode + " not found");
		}
		// run panel i18n (2026-10-17): the wizard asks for its own language (?lang=fr) so the texts the run stored with a code come back worded in it; everyone else gets the English
		final java.util.Locale locale = "fr".equals(pLang) ? java.util.Locale.FRENCH : "en".equals(pLang) ? java.util.Locale.ENGLISH : null;
		return Response.ok(com.on.ebx.factory.ebx.FactoryJobStatus.of(dataset, project.getOccurrencePrimaryKey().format(), this.sessionContext.getSession(), locale).toString()).build();
	}

	/**
	 * Matching-live lot (2026-09-27): the last matching action of a project ("Run matching" or "Configure") with one
	 * line per entity - what step 6's live panel polls while the action runs ({@code ebx.MatchingActivityReader}).
	 * Administrators only, like the jobs route (the actions are theirs).
	 */
	@GET
	@Path("/projects/{code}/matching/activity")
	public Response matchingActivity(@PathParam("code") final String pProjectCode, @QueryParam("lang") final String pLang)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/matching/activity", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final com.onwbp.adaptation.RequestResult projects = dataset.getTable(FactoryPaths.PROJECT).createRequestResult("./code='" + pProjectCode.replace("'", "''") + "'");
		final Adaptation project;
		try
		{
			project = projects.nextAdaptation();
		}
		finally
		{
			projects.close();
		}
		if (project == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Project " + pProjectCode + " not found");
		}
		// lot M12: the wizard asks for its own language (?lang=fr), as for the jobs route
		final java.util.Locale locale = "fr".equals(pLang) ? java.util.Locale.FRENCH : "en".equals(pLang) ? java.util.Locale.ENGLISH : null;
		return Response.ok(com.on.ebx.factory.ebx.UserView.scoped(com.on.ebx.factory.ebx.UserView.of(this.sessionContext.getSession()), () -> com.on.ebx.factory.ebx.MatchingActivityReader.read(dataset, project.getOccurrencePrimaryKey().format())).toJson(locale).toString()).build();
	}

	/**
	 * Production review of 2026-11-12 (F3-02): switches an agent on FOR THE CALLER. The server signs the caller's login together with the agent's current
	 * settings into {@code AgentDefinition.enabledBy} ({@code ebx.AgentIdentity}); an event or a schedule then acts for that person, with that person's EBX
	 * permissions, and refuses to run once they lose the project or the Factory right STEWARD. Changing the agent's project, kind, autonomy, tools or
	 * instructions afterwards cancels the activation. Closed while the agents are closed.
	 */
	@POST
	@Path("/agents/{code}/enable")
	public Response enableAgent(@PathParam("code") final String pAgentCode)
	{
		final Response refused = this.refused("POST /agents/{code}/enable");
		if (refused != null)
		{
			return refused;
		}
		final Response refusedOnAgent = this.refusedAgentRoute("POST /agents/{code}/enable", pAgentCode, true);
		if (refusedOnAgent != null)
		{
			return refusedOnAgent;
		}
		try
		{
			return Response.ok(com.on.ebx.factory.ebx.AgentIdentity.enable(this.sessionContext.getSession(), FactoryInstaller.dataset(this.sessionContext.getRepository()), pAgentCode)
				.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/** Production review of 2026-11-12 (F3-02): switches an agent off (no event, no schedule runs it); allowed while the agents are closed. */
	@POST
	@Path("/agents/{code}/disable")
	public Response disableAgent(@PathParam("code") final String pAgentCode)
	{
		final Response refused = this.refused("POST /agents/{code}/disable");
		if (refused != null)
		{
			return refused;
		}
		final Response refusedOnAgent = this.refusedAgentRoute("POST /agents/{code}/disable", pAgentCode, false);
		if (refusedOnAgent != null)
		{
			return refusedOnAgent;
		}
		try
		{
			return Response.ok(com.on.ebx.factory.ebx.AgentIdentity.disable(this.sessionContext.getSession(), FactoryInstaller.dataset(this.sessionContext.getRepository()), pAgentCode)
				.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Agentic framework lot (2026-10-30, docs/AGENTS.md): queues a run of a declared agent, acting for the caller. The agent's definition must be visible to
	 * the caller (its row, read with the caller's view), and the run re-checks the caller still holds STEWARD when it starts.
	 */
	@POST
	@Path("/agents/{code}/run")
	public Response runAgent(@PathParam("code") final String pAgentCode)
	{
		final Response refused = this.refused("POST /agents/{code}/run");
		if (refused != null)
		{
			return refused;
		}
		final Response refusedOnAgent = this.refusedAgentRoute("POST /agents/{code}/run", pAgentCode, true);
		if (refusedOnAgent != null)
		{
			return refusedOnAgent;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		try
		{
			return Response.ok(com.on.ebx.factory.ebx.AgentRunJob.start(this.sessionContext.getSession(), dataset, pAgentCode, "manual").toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Agentic framework lot: accepts or rejects one agent proposal with the caller's own session - an accepted line of an agent allowed to act is applied with
	 * the caller's EBX permissions, a suggestion only becomes ACCEPTED. The caller must hold the agent's owner profile (or be an administrator).
	 */
	@POST
	@Path("/agents/proposals/{id}/decide")
	public Response decideAgentProposal(@PathParam("id") final String pProposalId, @QueryParam("accept") final Boolean pAccept)
	{
		final Response refused = this.refused("POST /agents/proposals/{id}/decide");
		if (refused != null)
		{
			return refused;
		}
		final Response closed = this.agentsClosed();
		if (closed != null)
		{
			return closed;
		}
		if (pAccept == null)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "accept=true or accept=false is required");
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Session session = this.sessionContext.getSession();
		final com.on.ebx.factory.ebx.UserView view = com.on.ebx.factory.ebx.UserView.of(session);
		final Adaptation proposal = view.datasetVisible(dataset) ? view.lookup(dataset.getTable(FactoryPaths.AGENT_PROPOSAL), pProposalId) : null;
		if (proposal == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Agent proposal " + pProposalId + " not found");
		}
		// F3-12: the second permissions pass, in reading mode (the Factory right STEWARD and the agent's owner profile decide; the project must be visible to the caller)
		if (FactoryResource.visibleProject(view, dataset, view.text(proposal, FactoryPaths.AgentProposal.PROJECT)) == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Agent proposal " + pProposalId + " not found");
		}
		if (!com.on.ebx.factory.ebx.AgentReviewAccess.mayDecide(session, dataset, proposal))
		{
			return FactoryResource.problem(Response.Status.FORBIDDEN, "Only the owner profile of the agent decides its proposals");
		}
		try
		{
			return Response.ok(com.on.ebx.factory.ebx.AgentFramework.decideProposal(session, dataset, pProposalId, pAccept.booleanValue(),
				session.getUserReference().getUserId()).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/** Source drift lot (2026-10-30, docs/DRIFT.md): the schema changes of a project's sources, newest first, optionally of one status. */
	@GET
	@Path("/projects/{code}/drift")
	public Response projectDrift(@PathParam("code") final String pProjectCode, @QueryParam("status") final String pStatus)
	{
		final Response refused = this.refused("GET /projects/{code}/drift", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final com.onwbp.adaptation.RequestResult projects = dataset.getTable(FactoryPaths.PROJECT).createRequestResult("./code='" + pProjectCode.replace("'", "''") + "'");
		final Adaptation project;
		try
		{
			project = projects.nextAdaptation();
		}
		finally
		{
			projects.close();
		}
		if (project == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Project " + pProjectCode + " not found");
		}
		return Response.ok(com.on.ebx.factory.ebx.SourceDrifts.list(dataset, project.getOccurrencePrimaryKey().format(), pStatus).toString()).build();
	}

	/** Source drift lot: a steward accepts or rejects one proposed schema change. Accepting changes nothing yet: a designer applies it. */
	@POST
	@Path("/drift/{id}/decide")
	public Response decideDrift(@PathParam("id") final String pDriftId, @QueryParam("accept") final Boolean pAccept)
	{
		final Response refused = this.refused("POST /drift/{id}/decide");
		if (refused != null)
		{
			return refused;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Session session = this.sessionContext.getSession();
		// then the drift's own project. F3-12: a drift with no project is refused (it used to skip this check); a closed function answers its coded refusal (F3-105).
		// F3-11: deciding is the steward's act and a steward has no write right on the Project record (a DESIGN table), so the project check is a READ one here;
		// applying (below) keeps the write check, it is the designer's.
		final String driftProject;
		try
		{
			driftProject = com.on.ebx.factory.ebx.SourceDrifts.projectOrRefuse(dataset, pDriftId);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
		final com.on.ebx.factory.ebx.UserView view = com.on.ebx.factory.ebx.UserView.of(session);
		final Adaptation visible = FactoryResource.visibleProject(view, dataset, driftProject);
		final Response refusedOnProject = FactoryResource.answer(com.on.ebx.factory.core.RecordGate.onRecord(FactoryRight.VIEW, visible != null, visible != null,
			view.recordWritable(visible)), driftProject);
		if (refusedOnProject != null)
		{
			return refusedOnProject;
		}
		if (pAccept == null)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "accept=true or accept=false is required");
		}
		try
		{
			return Response.ok(com.on.ebx.factory.ebx.SourceDrifts.decide(session, dataset, pDriftId, pAccept.booleanValue(), session.getUserReference().getUserId())
				.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/** Source drift lot: a designer applies one ACCEPTED schema change (landing model patched and republished, landing columns, flow), with their own session. */
	@POST
	@Path("/drift/{id}/apply")
	public Response applyDrift(@PathParam("id") final String pDriftId)
	{
		final Response refused = this.refused("POST /drift/{id}/apply");
		if (refused != null)
		{
			return refused;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		// then the drift's own project (the Factory right on it, and EBX's permissions on the project record). F3-12: a drift with no project is refused
		// (it used to skip this check); a closed function answers its coded refusal (F3-105).
		final String driftProject;
		try
		{
			driftProject = com.on.ebx.factory.ebx.SourceDrifts.projectOrRefuse(dataset, pDriftId);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
		final Response refusedOnProject = this.refused("POST /drift/{id}/apply", driftProject);
		if (refusedOnProject != null)
		{
			return refusedOnProject;
		}
		final Session session = this.sessionContext.getSession();
		try
		{
			return Response.ok(com.on.ebx.factory.ebx.SourceDrifts.apply(session, dataset, pDriftId, session.getUserReference().getUserId()).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/** Lot C1: cancels a QUEUED job, or asks a RUNNING one to stop. Administrators only. */
	@POST
	@Path("/jobs/{id}/cancel")
	public Response cancelJob(@PathParam("id") final String pJobId)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /jobs/{id}/cancel");
		if (refused != null)
		{
			return refused;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		// second permissions pass (2026-10-14): the job row and its project with the caller's view - a job the caller cannot see, or of a project
		// the caller cannot see, answers like an unknown job; stopping it needs the project writable
		final com.on.ebx.factory.ebx.UserView view = com.on.ebx.factory.ebx.UserView.of(this.sessionContext.getSession());
		final Adaptation job = view.datasetVisible(dataset) ? view.lookup(dataset.getTable(FactoryPaths.FACTORY_JOB), pJobId) : null;
		final String jobProject = job == null ? "" : view.text(job, FactoryPaths.FactoryJob.PROJECT);
		final Adaptation project = jobProject.isEmpty() ? null : FactoryResource.visibleProject(view, dataset, jobProject);
		if (job == null || !jobProject.isEmpty() && project == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Job " + pJobId + " not found");
		}
		if (project != null && !view.recordWritable(project))
		{
			return FactoryResource.problem(Response.Status.FORBIDDEN, "EBX lets you read project " + jobProject + " but not modify it");
		}
		try
		{
			final boolean done = com.on.ebx.factory.ebx.FactoryJobQueue.cancel(this.sessionContext.getSession(), dataset, pJobId);
			final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
			out.put("jobId", pJobId);
			out.put("cancelRequested", done);
			return Response.ok(out.toString()).build();
		}
		catch (final OperationException ex)
		{
			// audit F-02: the job's own kind asks a right the caller does not hold (FactoryJobQueue.cancel)
			final String message = ex.getMessage() == null ? "" : ex.getMessage();
			return FactoryResource.problem(message.startsWith("Access denied") ? Response.Status.FORBIDDEN : Response.Status.CONFLICT, message);
		}
	}

	/**
	 * Migration repair (2026-09-27): fills {@code originalDataModel} on the DMA publications EBX Staging imported without
	 * it, so {@code publish_data_model} stops answering PUBLICATION_OWNED_BY_ANOTHER_MODEL on a migrated model
	 * ({@code ebx.PublicationOwnerRepair}). Administrators only; {@code dryRun} defaults to true.
	 */
	@POST
	@Path("/maintenance/publication-owners")
	public Response repairPublicationOwners(@QueryParam("dryRun") @DefaultValue("true") final boolean pDryRun)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /maintenance/publication-owners");
		if (refused != null)
		{
			return refused;
		}
		return Response.ok(com.on.ebx.factory.ebx.PublicationOwnerRepair.run(this.sessionContext.getRepository(), this.sessionContext.getSession(), pDryRun).toString())
			.build();
	}

	@GET
	@Path("/connection/test")
	public Response testConnection()
	{
		// the test acts as the agent account, whoever asks: not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /connection/test");
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		return Response.ok(McpServers.test(this.sessionContext.getSession(), dataset).toString()).build();
	}

	@POST
	@Path("/sources/{id}/landing/plan")
	public Response planLanding(@PathParam("id") final String pProjectSource)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refusedSource("POST /sources/{id}/landing/plan", pProjectSource);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(LandingPlanner.plan(this.sessionContext.getSession(), dataset, pProjectSource).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/sources/{id}/landing/generate")
	public Response generateLanding(@PathParam("id") final String pProjectSource)
	{
		// acts as the agent account on the MCP servers, whoever asks: not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refusedSource("POST /sources/{id}/landing/generate", pProjectSource);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			return Response.ok(LandingGenerator.generate(this.sessionContext.getSession(), dataset, pProjectSource).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/sources/{id}/landing/flows")
	public Response generateLandingFlows(@PathParam("id") final String pProjectSource, @QueryParam("force") @DefaultValue("false") final boolean pForce)
	{
		// acts as the agent account on the MCP servers, whoever asks: not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refusedSource("POST /sources/{id}/landing/flows", pProjectSource);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.CONNECTORS);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			return Response.ok(LandingFlows.generate(this.sessionContext.getSession(), dataset, pProjectSource, pForce).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/sources/{id}/landing/load")
	public Response loadLanding(@PathParam("id") final String pProjectSource, @QueryParam("fullRefresh") @DefaultValue("false") final boolean pFullRefresh)
	{
		// acts as the agent account on the MCP servers, whoever asks: not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refusedSource("POST /sources/{id}/landing/load", pProjectSource);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.CONNECTORS);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			// fullRefresh=true: every table read through a change column ignores its watermark and is reloaded in full (volume wave 2, lot V2-E)
			return Response.ok(LandingFlows.load(this.sessionContext.getSession(), dataset, pProjectSource, com.on.ebx.factory.landing.ProgressNote.NONE, null, pFullRefresh).toString())
				.build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/sources/{id}/relations/check")
	public Response checkRelations(@PathParam("id") final String pProjectSource)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refusedSource("POST /sources/{id}/relations/check", pProjectSource);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(RelationChecker.check(this.sessionContext.getSession(), dataset, pProjectSource).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * source-onboarding lot (2026-09-23): thin REST parity for the wizard's step-2 "Create a catalog source"
	 * panel ({@code ebx.SourceOnboarder}, {@code wizard.SourceOnboarding} - see their own Javadoc for the
	 * six-step chain). The two GETs are open to any user (a plain read, degrading into {@code Facts}'s own
	 * error fields rather than throwing - {@code SourceOnboarder.facts}'s own contract); the two POSTs act as
	 * the agent account on {@code ebx-connectors} and are administrators-only, preflighted at the route level
	 * like every other MCP-write route in this file.
	 */
	@GET
	@Path("/sources/onboarding/connections")
	public Response onboardingConnections()
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /sources/onboarding/connections");
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
			final ArrayNode connections = out.putArray("connections");
			// audit F-02 (catalog reads): catalogSourceId is read as the caller; a catalog right that hides the sources is said
			final com.on.ebx.factory.ebx.CatalogReader.Reader catalogReader = com.on.ebx.factory.ebx.CatalogReader.Reader.of(this.sessionContext.getSession());
			for (final SourceOnboarding.ConnectionOption connection : SourceOnboarder.connections(this.sessionContext.getSession(), dataset, catalogReader))
			{
				final ObjectNode node = connections.addObject();
				node.put("id", connection.id);
				node.put("label", connection.label);
				node.put("type", connection.type);
				node.put("active", connection.active);
				node.put("catalogCapable", connection.catalogCapable);
				node.put("catalogSourceId", connection.catalogSourceId);
			}
			if (catalogReader.restricted())
			{
				out.put("catalogRestricted", true);
				out.put("catalogMessage", com.on.ebx.factory.core.CatalogRightMessages.hidden("the data catalog's sources (catalogSourceId is left empty)"));
			}
			return Response.ok(out.toString()).build();
		}
		catch (final OperationException ex)
		{
			// a connections LISTING failure is shown as such, never a blank list - SourceOnboarder's own Javadoc
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@GET
	@Path("/sources/onboarding/{connector}")
	public Response onboardingDiagnosis(@PathParam("connector") final String pConnector)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /sources/onboarding/{connector}");
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final SourceOnboarding.Facts facts = SourceOnboarder.facts(this.sessionContext.getSession(), dataset, pConnector);
		return Response.ok(FactoryResource.onboardingJson(SourceOnboarding.diagnose(facts)).toString()).build();
	}

	@POST
	@Path("/sources/onboarding/{connector}/catalog")
	public Response onboardingCatalog(@PathParam("connector") final String pConnector)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /sources/onboarding/{connector}/catalog");
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.CONNECTORS);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			final SourceOnboarding.Facts facts = SourceOnboarder.applyCatalog(this.sessionContext.getSession(), dataset, pConnector);
			return Response.ok(FactoryResource.onboardingJson(SourceOnboarding.diagnose(facts)).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/sources/onboarding/{connector}/scan")
	public Response onboardingScan(@PathParam("connector") final String pConnector)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /sources/onboarding/{connector}/scan");
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.CONNECTORS);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			final SourceOnboarding.Facts facts = SourceOnboarder.runScan(this.sessionContext.getSession(), dataset, pConnector);
			final ObjectNode out = FactoryResource.onboardingJson(SourceOnboarding.diagnose(facts));
			if (facts.scanMessage != null)
			{
				out.put("scanMessage", facts.scanMessage);
			}
			return Response.ok(out.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * sample-files lot (2026-09-23): REST parity for the guided panel's own "Do everything" button -
	 * {@link SourceOnboarder#onboardAll}, same administrators-only/preflight discipline as
	 * {@link #onboardingCatalog}/{@link #onboardingScan}.
	 */
	@POST
	@Path("/sources/onboarding/{connector}/all")
	public Response onboardingAll(@PathParam("connector") final String pConnector)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /sources/onboarding/{connector}/all");
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.CONNECTORS);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			final SourceOnboarding.Facts facts = SourceOnboarder.onboardAll(this.sessionContext.getSession(), dataset, pConnector);
			final ObjectNode out = FactoryResource.onboardingJson(SourceOnboarding.diagnose(facts));
			if (facts.scanMessage != null)
			{
				out.put("scanMessage", facts.scanMessage);
			}
			return Response.ok(out.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * sample-files lot (2026-09-23): "drop the files, get a working source" - REST parity for the wizard's
	 * "Sources from sample files" panel ({@code ebx.SampleSourceImporter}). Body: a bare JSON array
	 * {@code [{"name","dataUrl","code","trustRank"}, ...]} (no envelope - {@code SampleSourceImporter.parseFileInputs}
	 * reads it the same tolerant way the wizard's own hidden {@code mdmfSampleFiles} textarea is read).
	 * Administrators only: this writes a Connectors connection and, when ready, the project's own
	 * {@code ProjectSource} - the same class of write every other MCP-acting route in this file gates the
	 * same way. <b>Untested live</b> (task brief) - no other route in this class reads a raw POST body, so the
	 * {@code @Consumes}/plain-{@code String} parameter combination has not been exercised against a real
	 * Jersey deployment.
	 */
	@POST
	@Path("/projects/{code}/sources/files")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response importSampleFiles(@PathParam("code") final String pProjectCode, final String pBody)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/sources/files", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Adaptation project = com.on.ebx.factory.ebx.ProposalReviews.findProjectByCode(dataset, pProjectCode);
		if (project == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "No project with code " + pProjectCode);
		}
		final List<SampleSourceImporter.FileInput> files;
		try
		{
			files = SampleSourceImporter.parseFileInputsStrict(pBody);
		}
		catch (final java.io.IOException ex)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, com.on.ebx.factory.core.JsonLimits.refusal(ex, "the request body"));
		}
		if (files.isEmpty())
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "No usable file entry in the request body (each row needs at least name and dataUrl)");
		}
		final SampleSourceImporter.Report report = SampleSourceImporter.importFiles(this.sessionContext.getSession(), dataset, project.getOccurrencePrimaryKey().format(), files);
		final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
		final ArrayNode fileArray = out.putArray("files");
		for (final com.on.ebx.factory.wizard.SampleFileRules.ReportRow row : report.files)
		{
			final ObjectNode node = fileArray.addObject();
			node.put("name", row.name);
			node.put("connectionId", row.connectionId);
			node.put("sourceId", row.sourceId);
			if (row.diagnosis != null)
			{
				node.set("diagnosis", FactoryResource.onboardingJson(row.diagnosis));
			}
			node.put("applicationAdded", row.applicationAdded);
			if (!row.existingApplication.isEmpty())
			{
				node.put("existingApplication", row.existingApplication);
			}
			if (row.error != null)
			{
				node.put("error", row.error);
			}
		}
		return Response.ok(out.toString()).build();
	}

	/** {@code steps[] {key,name(en),state,message(en),action}, nextAction, ready, pickSourceId} - the English message/name text is resolved here ({@code WizardMessages}, {@code Locale.ENGLISH}: this REST surface carries no session locale of its own), the same pattern/args {@code WizardPage} renders per-locale for the wizard UI. {@code name} (coordinator review, 2026-09-23) is {@code wizard.onboard.step.<key>.name} - not a {@code SourceOnboarding} key, derived from {@code Step.key} here exactly as {@code WizardPage.renderOnboardChecklist} does. */
	private static ObjectNode onboardingJson(final SourceOnboarding.Diagnosis pDiagnosis)
	{
		final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
		final ArrayNode steps = out.putArray("steps");
		for (final SourceOnboarding.Step step : pDiagnosis.steps)
		{
			final ObjectNode node = steps.addObject();
			node.put("key", step.key);
			node.put("name", WizardMessages.get(java.util.Locale.ENGLISH, "wizard.onboard.step." + step.key + ".name"));
			node.put("state", step.state.name());
			node.put("message", WizardMessages.get(java.util.Locale.ENGLISH, step.messageKey, step.args));
			node.put("action", step.action.name());
		}
		out.put("nextAction", pDiagnosis.nextAction.name());
		out.put("ready", pDiagnosis.ready);
		if (pDiagnosis.pickSourceId != null)
		{
			out.put("pickSourceId", pDiagnosis.pickSourceId);
		}
		return out;
	}

	@POST
	@Path("/projects/{code}/staging/suggest")
	public Response suggestStaging(@PathParam("code") final String pProjectCode)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/staging/suggest", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(StagingSuggester.suggest(this.sessionContext.getSession(), dataset, pProjectCode).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/quality/suggest")
	public Response suggestQuality(@PathParam("code") final String pProjectCode, @QueryParam("maxDistinctValues") final Integer pMaxDistinctValues)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/quality/suggest", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(QualitySuggester.suggest(this.sessionContext.getSession(), dataset, pProjectCode, pMaxDistinctValues).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/quality/suggest-llm")
	public Response suggestQualityLlm(@PathParam("code") final String pProjectCode, @QueryParam("entity") final String pEntity)
	{
		// spends tokens and uses the language model connector's key, whoever asks: not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/quality/suggest-llm", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(QualityLlmSuggester.suggest(this.sessionContext.getSession(), dataset, pProjectCode, pEntity).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/staging/suggest-llm")
	public Response suggestStagingLlm(@PathParam("code") final String pProjectCode)
	{
		// spends tokens and uses the language model connector's key, whoever asks: not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/staging/suggest-llm", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(StagingLlmSuggester.suggest(this.sessionContext.getSession(), dataset, pProjectCode).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/staging/generate")
	public Response generateStaging(@PathParam("code") final String pProjectCode)
	{
		// acts as the agent account on the MCP servers, whoever asks: not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/staging/generate", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			return Response.ok(StagingGenerator.generate(this.sessionContext.getSession(), dataset, pProjectCode).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 1): this route used to run {@code StagingLoader.load} directly
	 * on the Tomcat request thread - fine at the scale every prior lot measured against, but a genuinely
	 * multi-hour hold on a huge landing zone (CLAUDE.md's own "EBX tient le volume") on a thread the caller
	 * (a script, a load balancer, curl with a timeout) may well give up on long before the load itself
	 * finishes, orphaning nothing server-side (the load keeps running) but leaving the caller with no result at
	 * all. Default now: enqueue the SAME job the wizard's "Build and load"/"Load the staging" buttons already
	 * use ({@link StagingLoadRun#start(Session, Adaptation, String, List, boolean)}, resource STAGING - see
	 * that class' own Javadoc), stage {@link StagingLoadRun#LOAD} only (this route never built the model), and
	 * answer {@code 202} with {@code jobId}/{@code statusUrl} - poll the existing {@code GET .../jobs} route.
	 * {@code ?sync=true} keeps the exact pre-lot behaviour (call {@code StagingLoader.load} directly, block
	 * until it returns, answer its own JSON body) for a caller that genuinely depends on it - checked against
	 * every consumer this session could find (the wizard uses the job path already; {@code docs/DELIVERY.md}'s
	 * runbook and {@code docs/MATCHING.md}'s worked examples both read this route's response body inline as a
	 * manual curl/operator step, never from a script that would need to be updated here - see
	 * {@code docs/VOLUME.md}'s "Lot 8a" section for the full survey).
	 */
	@POST
	@Path("/projects/{code}/staging/load")
	public Response loadStaging(@PathParam("code") final String pProjectCode, @QueryParam("scope") @DefaultValue("new") final String pScope,
		@QueryParam("sync") @DefaultValue("false") final boolean pSync, @QueryParam("buildAnyway") @DefaultValue("false") final boolean pBuildAnyway)
	{
		// runs with the caller's own session (no MCP call), but still gated to administrators: it writes into
		// the staging/landing dataspaces and the factory's own reject/lineage tables, like every other write route
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/staging/load", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		// scope=new (default): only landing rows still at "version received, not yet transferred" (transfer.IncrementalTransfer);
		// scope=all: every landing row, whatever its _status - the pre-2026-09-23 behaviour, for a full re-transform after a
		// mapping/cleaning-step/derived-field change. Same tolerant parsing convention as /matching/run's own scope parameter.
		final boolean allRows;
		try
		{
			// 2026-10-12: only "new" and "all" are accepted; anything else is a 400, not the default
			allRows = IncrementalTransfer.SCOPE_ALL.equals(ScopeParam.parse(pScope, IncrementalTransfer.SCOPE_NEW, IncrementalTransfer.SCOPE_ALL));
		}
		catch (final IllegalArgumentException ex)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, ex.getMessage());
		}
		try
		{
			if (pSync)
			{
				return Response.ok(StagingLoader.load(this.sessionContext.getSession(), dataset, pProjectCode, allRows).toString()).build();
			}
			// lot A of the hard-cases scenario (2026-10-17): buildAnyway=true is the wizard's "Build anyway" button (STAGING_LOAD_RUN_ANYWAY) - the check before the build is set aside
			// and the rows EBX refuses go to the reject review. Without it (the default) the job makes the check and stops when rows would be refused, as before.
			return FactoryResource.accepted(
				StagingLoadRun.start(this.sessionContext.getSession(), dataset, pProjectCode, java.util.Collections.singletonList(StagingLoadRun.LOAD), allRows, pBuildAnyway), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Staging preflight lot H1 (2026-10-17, docs/STAGING-PREFLIGHT.md): the LAST check before the build - the stored report ({@code {"exists":false}} when none was made), counts,
	 * format names and pipeline texts only, no value. The build itself ({@code POST .../staging/load}, the wizard, the matrix button) makes the same check before it writes a row
	 * and stops when rows would be refused.
	 */
	@GET
	@Path("/projects/{code}/staging/preflight")
	public Response stagingPreflightLast(@PathParam("code") final String pProjectCode)
	{
		final Response refused = this.refused("GET /projects/{code}/staging/preflight", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final com.on.ebx.factory.staging.PreflightReport report = StagingPreflight.last(dataset, pProjectCode);
		if (report == null)
		{
			return Response.ok("{\"exists\":false}").build();
		}
		final com.fasterxml.jackson.databind.node.ObjectNode body = report.toJson();
		body.put("exists", true);
		body.put("builtSince", StagingPreflight.builtSince(dataset, pProjectCode, report));
		return Response.ok(body.toString()).build();
	}

	/**
	 * Staging preflight lot H1: makes the check now, stores the report and answers it (the {@code GET} body plus {@code exists}). Reads the landing's distinct values (a bounded
	 * sample per mapped column) and replays every ACCEPTED link with the engine the load runs: nothing is written but the stored report. {@code ?apply=<mapping id>} (or {@code
	 * apply=*} for every fixable link) first writes the fix the LAST report proposed - {@code AttributeMapping.pipeline} of the link, its status untouched - and then checks again;
	 * the answer carries {@code fix} ({@code applied}, {@code stale}, {@code missing}). Administrators / designers only, like {@code staging/load}.
	 */
	@POST
	@Path("/projects/{code}/staging/preflight")
	public Response stagingPreflight(@PathParam("code") final String pProjectCode, @QueryParam("apply") final String pApply)
	{
		final Response refused = this.refused("POST /projects/{code}/staging/preflight", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			StagingPreflight.FixOutcome fix = null;
			if (pApply != null && !pApply.trim().isEmpty())
			{
				fix = StagingPreflight.applyFix(this.sessionContext.getSession(), dataset, pProjectCode, pApply);
			}
			final com.on.ebx.factory.staging.PreflightReport report = StagingPreflight.runAndStore(this.sessionContext.getSession(), dataset, pProjectCode, null,
				StagingPreflight.NEVER);
			final com.fasterxml.jackson.databind.node.ObjectNode body = report.toJson();
			body.put("exists", true);
			if (fix != null)
			{
				final com.fasterxml.jackson.databind.node.ObjectNode fixNode = body.putObject("fix");
				fixNode.put("applied", fix.applied);
				fixNode.put("stale", fix.stale);
				fixNode.put("missing", fix.missing);
			}
			return Response.ok(body.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/master/generate")
	public Response generateMaster(@PathParam("code") final String pProjectCode, @QueryParam("overwriteTarget") @DefaultValue("false") final boolean pOverwriteTarget)
	{
		// acts as the agent account on the MCP servers, whoever asks: not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/master/generate", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			return Response.ok(MasterGenerator.generate(this.sessionContext.getSession(), dataset, pProjectCode, pOverwriteTarget).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 1): same reasoning as {@link #loadStaging} - default now
	 * enqueues the wizard's own combined job ({@link MasterBuildRun#start(Session, Adaptation, String, List)},
	 * resource MASTER), restricted to {@link MasterBuildRun#COPY} (this route never built the master model),
	 * and answers {@code 202}; {@code ?sync=true} keeps the exact pre-lot blocking behaviour.
	 */
	@POST
	@Path("/projects/{code}/master/load")
	public Response loadMaster(@PathParam("code") final String pProjectCode, @QueryParam("sync") @DefaultValue("false") final boolean pSync,
		@QueryParam("scope") final String pScope)
	{
		// runs with the caller's own session (no MCP call), but still gated to administrators - same reasoning as /staging/load
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/master/load", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final String scope;
		try
		{
			// 2026-10-12: only "full" and "changed" are accepted; blank stays "take the instance default" (null)
			scope = ScopeParam.parse(pScope, MasterLoadScope.FULL, MasterLoadScope.CHANGED);
		}
		catch (final IllegalArgumentException ex)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, ex.getMessage());
		}
		try
		{
			if (pSync)
			{
				return Response.ok(MasterLoader.load(this.sessionContext.getSession(), dataset, pProjectCode, scope).toString()).build();
			}
			return FactoryResource.accepted(
				MasterBuildRun.start(this.sessionContext.getSession(), dataset, pProjectCode, java.util.Collections.singletonList(MasterBuildRun.COPY), scope), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 2): default now enqueues {@link MasterResetRun} (a
	 * whole-project job, {@link com.on.ebx.factory.ebx.FactoryJobs#claimAll}) and answers {@code 202} instead
	 * of running {@link MasterReset#reset} synchronously, inside {@link FactoryJobQueue#exclusively}'s
	 * outright-refuse-if-busy check, on the request thread. {@code ?sync=true} keeps the exact pre-lot
	 * behaviour (including that same refusal).
	 */
	@POST
	@Path("/projects/{code}/master/reset")
	public Response resetMaster(@PathParam("code") final String pProjectCode, @QueryParam("confirm") final String pConfirm,
		@QueryParam("sync") @DefaultValue("false") final boolean pSync)
	{
		// closes and deletes a dataspace and a data model, plus acts as the agent account on the MCP servers - not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/master/reset", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			if (pSync)
			{
				return Response.ok(MasterReset.reset(this.sessionContext.getSession(), dataset, pProjectCode, pConfirm).toString()).build();
			}
			return FactoryResource.accepted(MasterResetRun.start(this.sessionContext.getSession(), dataset, pProjectCode, pConfirm), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Distribution lot 2 ({@code SPEC-distribution.md} &sect;7c): reachable warehouse connections
	 * ({@code ebx.DataProductPublication#detect}, a Snowflake connectivity probe through the Connectors
	 * in-JVM bridge), this project's candidate tables (master entities, their available crosswalks, and
	 * catalogue assets to add), and a default draft. Administrators only: it probes ebx-connectors and
	 * reads the data catalog's dataset directly.
	 */
	@GET
	@Path("/projects/{code}/distribution/product")
	public Response distributionProductDetect(@PathParam("code") final String pProjectCode)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/distribution/product", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(DataProductPublication.detect(this.sessionContext.getSession(), dataset, pProjectCode).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Distribution lot 2: {@code ?dryRun=true} (default false) plans and validates the posted draft
	 * ({@code distribution.product.ProductPlan}) without writing anything - the same re-plan {@code false}
	 * runs before it writes, so a caller can always preview first. {@code false} writes the data product,
	 * its output port (with {@code maxLagMinutes}/{@code autoPublish} from the chosen refresh cadence - the
	 * data catalog's own {@code PortSlaTask} sweep does the actual republishing, no schedule is created
	 * here), one Connectors mapping per publishable table, and launches the first publication - idempotent,
	 * see {@code ebx.DataProductPublication#create}'s own Javadoc for exactly what is and is not verified
	 * live. Administrators only: it writes into the shared data catalog and acts on ebx-connectors.
	 */
	@POST
	@Path("/projects/{code}/distribution/product")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response distributionProductCreate(@PathParam("code") final String pProjectCode, @QueryParam("dryRun") @DefaultValue("false") final boolean pDryRun,
		final String pBody)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/distribution/product", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final JsonBody draft = FactoryResource.readJsonBody(pBody, FactoryResource.MAPPER.createObjectNode());
		if (draft.error != null)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, draft.error);
		}
		final JsonNode draftJson = draft.node;
		try
		{
			final ObjectNode result = pDryRun ? DataProductPublication.plan(this.sessionContext.getSession(), dataset, pProjectCode, draftJson)
				: DataProductPublication.create(this.sessionContext.getSession(), dataset, pProjectCode, draftJson);
			return Response.ok(result.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@GET
	@Path("/projects/{code}/deletion/plan")
	public Response planProjectDeletion(@PathParam("code") final String pProjectCode)
	{
		// read-only, but it probes the match-merge server as the agent account - same guard as every MCP-reaching route
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/deletion/plan", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		// ProjectDeleter.gather only ever probes get_matching_config when the project has a master dataspace -
		// preflight MATCH_MERGE only then, the same "only the servers this call needs" discipline as every route here.
		if (ProjectDeleter.hasMasterDataspace(dataset, pProjectCode))
		{
			final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.MATCH_MERGE);
			if (preflight != null)
			{
				return preflight;
			}
		}
		try
		{
			return Response.ok(ProjectDeleter.plan(this.sessionContext.getSession(), dataset, pProjectCode).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
		catch (final RuntimeException ex)
		{
			// staging integration (2026-10-17): a planning bug answered a bare 500 with nothing in any log - say it and log it
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] planning the deletion of project " + pProjectCode + " failed", ex);
			return FactoryResource.problem(Response.Status.INTERNAL_SERVER_ERROR, "Planning the deletion failed: " + ex);
		}
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 2): default now enqueues {@link ProjectDeleteRun} (a
	 * whole-project job) and answers {@code 202} instead of running {@link ProjectDeleter#delete}
	 * synchronously on the request thread. {@code ?sync=true} keeps the exact pre-lot blocking behaviour.
	 */
	@POST
	@Path("/projects/{code}/delete")
	public Response deleteProject(@PathParam("code") final String pProjectCode, @QueryParam("confirm") final String pConfirm,
		@QueryParam("generated") final String pGenerated, @QueryParam("sync") @DefaultValue("false") final boolean pSync)
	{
		// closes/deletes dataspaces and data models, plus acts as the agent account on the MCP servers - same guard as /master/reset
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/delete", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		// second permissions pass (2026-10-14): EBX's own delete right on the project record (the deletion job then removes every generated object)
		final Adaptation deletionDataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (deletionDataset != null)
		{
			final com.on.ebx.factory.ebx.UserView view = com.on.ebx.factory.ebx.UserView.of(this.sessionContext.getSession());
			if (!view.recordDeletable(FactoryResource.visibleProject(view, deletionDataset, pProjectCode)))
			{
				return FactoryResource.problem(Response.Status.FORBIDDEN, "EBX does not let you delete project " + pProjectCode);
			}
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Set<String> options = new LinkedHashSet<String>();
		if (pGenerated != null)
		{
			for (final String option : pGenerated.split(","))
			{
				final String trimmed = option.trim();
				if (!trimmed.isEmpty())
				{
					options.add(trimmed);
				}
			}
		}
		// only the servers the TICKED options will actually reach - dataspaces needs none (a plain
		// Repository.closeHome/deleteHome call, no MCP involved)
		final List<String> serversNeeded = new ArrayList<String>();
		if (options.contains(ProjectDeletionPlan.OPTION_MATCHING))
		{
			serversNeeded.add(McpServers.MATCH_MERGE);
		}
		// lot C (2026-10-17): the 'connections' option also deletes catalogue flows (connectors_mapping_delete), so it needs the Connectors server too
		if (options.contains(ProjectDeletionPlan.OPTION_FLOWS) || options.contains(ProjectDeletionPlan.OPTION_CONNECTIONS))
		{
			serversNeeded.add(McpServers.CONNECTORS);
		}
		if (options.contains(ProjectDeletionPlan.OPTION_MODELS))
		{
			serversNeeded.add(McpServers.DATA_MODEL);
		}
		if (!serversNeeded.isEmpty())
		{
			final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, serversNeeded.toArray(new String[0]));
			if (preflight != null)
			{
				return preflight;
			}
		}
		try
		{
			if (pSync)
			{
				return Response.ok(ProjectDeleter.delete(this.sessionContext.getSession(), dataset, pProjectCode, options, pConfirm).toString()).build();
			}
			return FactoryResource.accepted(ProjectDeleteRun.start(this.sessionContext.getSession(), dataset, pProjectCode, options, pConfirm), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Orphan-connections lot (2026-10-17): the sample-file connections (and their catalogue sources) that no project references and that carry no creation
	 * trace - left by projects deleted before the project deletion's {@code connections} option. Read-only, administrators only. {@code namePattern}: an
	 * optional plain substring (never a regex) on the connection id, label and catalogue source ids. See {@code ebx.OrphanConnectionCleanup}.
	 */
	@GET
	@Path("/connections/orphans")
	public Response listOrphanConnections(@QueryParam("namePattern") final String pNamePattern)
	{
		// the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /connections/orphans");
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(OrphanConnectionCleanup.list(dataset, pNamePattern).toString()).build();
		}
		catch (final RuntimeException ex)
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] listing the orphan connections failed", ex);
			return FactoryResource.problem(Response.Status.INTERNAL_SERVER_ERROR, "Listing the orphan connections failed: " + ex);
		}
	}

	/**
	 * Orphan-connections lot (2026-10-17): body {@code {"ids":[...],"confirm":"<count>"}}. Re-gathers live, deletes only the ids still eligible (catalogue flows,
	 * then catalogue source rows, then the connection rows - the project deletion's own code), answers one line per id (DELETED / KEPT with the reason /
	 * ABSENT / FAILED), logs each to kernel.log. {@code confirm} must be exactly the number of eligible ids sent, else 409 and nothing is deleted.
	 * Synchronous (the catalogue flows go through {@code connectors_mapping_delete}). Administrators only.
	 */
	@POST
	@Path("/connections/orphans/delete")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response deleteOrphanConnections(final String pBody)
	{
		// the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /connections/orphans/delete");
		if (refused != null)
		{
			return refused;
		}
		final JsonBody parsedBody = FactoryResource.readJsonBody(pBody, null);
		if (parsedBody.error != null)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, parsedBody.error);
		}
		final JsonNode body = parsedBody.node;
		final String bodyProblem = OrphanConnections.bodyProblem(body);
		if (bodyProblem != null)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, bodyProblem);
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		// the catalogue flows go through connectors_mapping_delete
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.CONNECTORS);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			final OrphanConnectionCleanup.DeleteResult result = OrphanConnectionCleanup.delete(this.sessionContext.getSession(), dataset, OrphanConnections.idsOf(body),
				body.path("confirm").isValueNode() ? body.path("confirm").asText() : "");
			return result.refusal != null ? FactoryResource.problem(Response.Status.CONFLICT, result.refusal) : Response.ok(result.json.toString()).build();
		}
		catch (final RuntimeException ex)
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] deleting orphan connections failed", ex);
			return FactoryResource.problem(Response.Status.INTERNAL_SERVER_ERROR, "Deleting the orphan connections failed: " + ex);
		}
	}

	/**
	 * Lot M18 (2026-10-18): what the Match and Merge add-on's own configuration dataset (Bebx-addon-mame / ebx-addon-mame-configuration) still holds for publications
	 * whose model no longer exists - business-object and table configuration rows left by projects deleted before the project deletion removed them. A business-object row
	 * without its model breaks the add-on at the next start. {@code publication=<name>} lists that one publication whatever the state of its model (the only way to see
	 * one that still exists). Read-only, administrators only; needs the data-model server. See {@code ebx.OrphanMatchingConfigCleanup}.
	 */
	@GET
	@Path("/matching/orphans")
	public Response listOrphanMatchingConfigs(@QueryParam("publication") final String pPublication)
	{
		// the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /matching/orphans");
		if (refused != null)
		{
			return refused;
		}
		if (pPublication != null && !pPublication.trim().isEmpty() && !com.on.ebx.factory.project.MatchingConfigRows.isPublicationName(pPublication.trim()))
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "publication must be a bare name of letters, digits, '_', '.' or '-' (not a urn)");
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			return Response.ok(OrphanMatchingConfigCleanup.list(this.sessionContext.getSession(), dataset, pPublication).toString()).build();
		}
		catch (final OperationException | RuntimeException ex)
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] listing the orphan matching configurations failed", ex);
			return FactoryResource.problem(Response.Status.INTERNAL_SERVER_ERROR, "Listing the orphan matching configurations failed: " + ex);
		}
	}

	/**
	 * Lot M18 (2026-10-18): body {@code {"publications":["E2EMASTER20261018_Master",...],"confirm":"<count>"}}. Re-reads live, then for each named publication that is
	 * eligible now (its model is gone, or it is named and still exists and is not a live project's master model): every business-object configuration is unlinked from its
	 * table configurations and deleted; when the model still exists each table configuration is then removed with {@code delete_matching_config}; for a vanished model the
	 * table configurations stay (deleting them breaks the next start). Answers one line per name (DELETED / KEPT with the reason / ABSENT / FAILED) with the rows left, logs
	 * each to kernel.log. {@code confirm} must be exactly the number of eligible names sent, else 409 and nothing is changed. Synchronous. Administrators only.
	 */
	@POST
	@Path("/matching/orphans/delete")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response deleteOrphanMatchingConfigs(final String pBody)
	{
		// the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /matching/orphans/delete");
		if (refused != null)
		{
			return refused;
		}
		final JsonBody parsedBody = FactoryResource.readJsonBody(pBody, null);
		if (parsedBody.error != null)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, parsedBody.error);
		}
		final JsonNode body = parsedBody.node;
		final String bodyProblem = com.on.ebx.factory.project.MatchingConfigRows.bodyProblem(body);
		if (bodyProblem != null)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, bodyProblem);
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		// get_publication (is the model there) and delete_matching_config (a publication whose model still exists)
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL, McpServers.MATCH_MERGE);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			final OrphanMatchingConfigCleanup.DeleteResult result = OrphanMatchingConfigCleanup.delete(this.sessionContext.getSession(), dataset,
				com.on.ebx.factory.project.MatchingConfigRows.namesOf(body), body.path("confirm").isValueNode() ? body.path("confirm").asText() : "");
			return result.refusal != null ? FactoryResource.problem(Response.Status.CONFLICT, result.refusal) : Response.ok(result.json.toString()).build();
		}
		catch (final OperationException | RuntimeException ex)
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] cleaning the orphan matching configurations failed", ex);
			return FactoryResource.problem(Response.Status.INTERNAL_SERVER_ERROR, "Cleaning the orphan matching configurations failed: " + ex);
		}
	}

	/**
	 * Audit F-30 (wave 10): the backup of ONE project - every row the Factory holds for it, as a JSON file (format version 1, see
	 * {@code backup.BackupFormat}), streamed. {@code exclude} (comma-separated table names, for example {@code Xref,TransferReject}) leaves volume-scale
	 * tables out. The generated EBX objects are not in it: the archive lists them under {@code references}. Administrators only; recorded as a
	 * {@code PROJECT_BACKUP} step of the project.
	 */
	@GET
	@Path("/projects/{code}/backup")
	public Response backupProject(@PathParam("code") final String pProjectCode, @QueryParam("exclude") final String pExclude)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/backup", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final com.onwbp.adaptation.AdaptationTable projects = dataset.getTable(FactoryPaths.PROJECT);
		if (projects.lookupAdaptationByPrimaryKey(projects.computePrimaryKey(new Object[] { pProjectCode })) == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "No project '" + pProjectCode + "'");
		}
		final Set<String> exclude = new LinkedHashSet<String>();
		if (pExclude != null)
		{
			for (final String name : pExclude.split(","))
			{
				if (!name.trim().isEmpty())
				{
					exclude.add(name.trim());
				}
			}
		}
		final Session session = this.sessionContext.getSession();
		final jakarta.ws.rs.core.StreamingOutput stream = new jakarta.ws.rs.core.StreamingOutput()
		{
			@Override
			public void write(final java.io.OutputStream pOut) throws java.io.IOException
			{
				try
				{
					com.on.ebx.factory.ebx.ProjectBackup.export(session, dataset, pProjectCode, exclude, pOut);
				}
				catch (final OperationException ex)
				{
					throw new java.io.IOException(ex.getMessage(), ex);
				}
			}
		};
		return Response.ok(stream).header("Content-Disposition", "attachment; filename=\"" + com.on.ebx.factory.backup.BackupFormat.fileName(pProjectCode, new java.util.Date()) + "\"")
			.build();
	}

	/**
	 * Audit F-30 (wave 10): restores a project from the body (a file written by {@code GET /projects/{code}/backup}) into an instance where the project
	 * does NOT exist - a project that exists is refused, nothing is merged or overwritten. {@code dryRun} defaults to {@code true}: the report says what
	 * would be written (rows per table, references renumbered, warnings, the generated objects the backup does not hold) and nothing is written; a real
	 * restore needs {@code dryRun=false}. A failure rolls back what was written. Administrators only; a restore is recorded as a {@code PROJECT_RESTORE}
	 * step of the project.
	 */
	@POST
	@Path("/projects/{code}/restore")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response restoreProject(@PathParam("code") final String pProjectCode, @QueryParam("dryRun") @DefaultValue("true") final boolean pDryRun, final java.io.InputStream pBody)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refusedCreateOrWrite("POST /projects/{code}/restore", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(com.on.ebx.factory.ebx.ProjectRestore.restore(this.sessionContext.getSession(), dataset, pProjectCode, pBody, pDryRun).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@GET
	@Path("/projects/{code}/reset/plan")
	public Response planStepReset(@PathParam("code") final String pProjectCode, @QueryParam("from") final String pFrom)
	{
		// read-only, but it can probe the match-merge server as the agent account (a project with a master
		// dataspace) - same guard as every MCP-reaching route
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/reset/plan", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		// a step reset always reaches MATCHING (resetting FROM any stage also resets everything downstream of
		// it - see StepResetPlan's own class Javadoc), so MATCH_MERGE is preflighted whenever the project has a
		// master dataspace at all, exactly like /deletion/plan already does.
		if (ProjectDeleter.hasMasterDataspace(dataset, pProjectCode))
		{
			final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.MATCH_MERGE);
			if (preflight != null)
			{
				return preflight;
			}
		}
		try
		{
			return Response.ok(StepResetter.plan(this.sessionContext.getSession(), dataset, pProjectCode, pFrom).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 2): default now enqueues {@link StepResetRun} (a
	 * whole-project job) and answers {@code 202} instead of running {@link StepResetter#reset} synchronously on
	 * the request thread. {@code ?sync=true} keeps the exact pre-lot blocking behaviour.
	 */
	@POST
	@Path("/projects/{code}/reset")
	public Response stepReset(@PathParam("code") final String pProjectCode, @QueryParam("from") final String pFrom, @QueryParam("confirm") final String pConfirm,
		@QueryParam("sync") @DefaultValue("false") final boolean pSync)
	{
		// closes/deletes dataspaces and data models, plus acts as the agent account on the MCP servers - same guard as /master/reset and /delete
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/reset", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		// only the servers this stage could actually reach - MATCHING is always in scope (see planStepReset's own
		// comment); STAGING/MASTER/LANDING each also delete a data model; only LANDING deletes a Connectors flow.
		final List<String> serversNeeded = new ArrayList<String>();
		if (ProjectDeleter.hasMasterDataspace(dataset, pProjectCode))
		{
			serversNeeded.add(McpServers.MATCH_MERGE);
		}
		if (!"MATCHING".equalsIgnoreCase(pFrom))
		{
			serversNeeded.add(McpServers.DATA_MODEL);
		}
		if ("LANDING".equalsIgnoreCase(pFrom))
		{
			serversNeeded.add(McpServers.CONNECTORS);
		}
		if (!serversNeeded.isEmpty())
		{
			final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, serversNeeded.toArray(new String[0]));
			if (preflight != null)
			{
				return preflight;
			}
		}
		try
		{
			if (pSync)
			{
				return Response.ok(StepResetter.reset(this.sessionContext.getSession(), dataset, pProjectCode, pFrom, pConfirm).toString()).build();
			}
			return FactoryResource.accepted(StepResetRun.start(this.sessionContext.getSession(), dataset, pProjectCode, pFrom, pConfirm), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/matching/suggest")
	public Response suggestMatching(@PathParam("code") final String pProjectCode)
	{
		// no MCP call and no agent account, but writing matching plans is left to administrators (unlike
		// staging/suggest) since it is the first step of a chain that will act as the agent account
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/matching/suggest", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(MatchPlanSuggester.suggest(this.sessionContext.getSession(), dataset, pProjectCode).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/matching/suggest-llm")
	public Response suggestMatchingLlm(@PathParam("code") final String pProjectCode, @QueryParam("entity") final String pEntity)
	{
		// spends tokens and uses the language model connector's key, whoever asks: not for every user - same guard as staging/suggest-llm
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/matching/suggest-llm", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.MATCH_MERGE);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			// whether a real value may be shared with the model is the project's OWN decision (Project.llmValueSharing),
			// never a caller-supplied override - see MatchPlanLlmSuggester's own Javadoc. pEntity (reject/repropose
			// lot, 2026-09-22) scopes the pass to one entity - null/blank runs the ordinary full sweep, unchanged.
			return Response.ok(MatchPlanLlmSuggester.suggest(this.sessionContext.getSession(), dataset, pProjectCode, pEntity).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 1): default now enqueues the SAME job the wizard's "Configure
	 * the matching" button uses ({@link MatchingConfigureRun#start}, resource MATCHING) and answers {@code 202};
	 * {@code ?sync=true} keeps the exact pre-lot blocking behaviour (call {@link MatchingConfigurator#configure}
	 * directly with no {@link com.on.ebx.factory.ebx.FactoryJobs.Handle}, the bounded synchronous path that
	 * class' own Javadoc describes).
	 */
	@POST
	@Path("/projects/{code}/matching/configure")
	public Response configureMatching(@PathParam("code") final String pProjectCode, @QueryParam("sync") @DefaultValue("false") final boolean pSync)
	{
		// acts as the agent account on the match-merge MCP server, whoever asks: not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/matching/configure", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.MATCH_MERGE, McpServers.DATA_MODEL);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			if (pSync)
			{
				return Response.ok(MatchingConfigurator.configure(this.sessionContext.getSession(), dataset, pProjectCode).toString()).build();
			}
			return FactoryResource.accepted(MatchingConfigureRun.start(this.sessionContext.getSession(), dataset, pProjectCode), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 1): default now enqueues the wizard's "Run the matching" job
	 * ({@link MatchingRunRun#start}, resource MATCHING) and answers {@code 202} - {@code maxWaitMs} then has no
	 * effect (the job path always polls to the process' own terminal state, no wall-clock cap - {@link
	 * MatchingConfigurator#runInternal}'s own Javadoc on {@code effectiveMaxWaitMs}, unaffected by this lot).
	 * {@code ?sync=true} keeps the exact pre-lot blocking behaviour, {@code maxWaitMs} included.
	 */
	@POST
	@Path("/projects/{code}/matching/run")
	public Response runMatching(@PathParam("code") final String pProjectCode, @QueryParam("scope") @DefaultValue("incremental") final String pScope,
		@QueryParam("maxWaitMs") final Long pMaxWaitMs, @QueryParam("sync") @DefaultValue("false") final boolean pSync)
	{
		// launches a real matching run with the caller's own session - not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/matching/run", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.MATCH_MERGE);
		if (preflight != null)
		{
			return preflight;
		}
		final boolean scopeAll;
		try
		{
			// 2026-10-12: an unknown word is refused, never read as the default (?scope=full used to run an incremental run)
			scopeAll = "all".equals(ScopeParam.parse(pScope, "incremental", "all"));
		}
		catch (final IllegalArgumentException ex)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, ex.getMessage());
		}
		try
		{
			if (pSync)
			{
				final long maxWaitMs = pMaxWaitMs == null ? MatchingConfigurator.DEFAULT_MAX_WAIT_MS : pMaxWaitMs.longValue();
				return Response.ok(MatchingConfigurator.run(this.sessionContext.getSession(), dataset, pProjectCode, scopeAll, maxWaitMs).toString()).build();
			}
			return FactoryResource.accepted(MatchingRunRun.start(this.sessionContext.getSession(), dataset, pProjectCode, scopeAll), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 1): this route had no job wrapper at all before this lot
	 * (unlike configure/run, above) - new {@link GoldenKeyRefreshRun} (resource MATCHING, so it never overlaps
	 * a configure/run job or the matching workflow bean on the same project) wraps the exact same {@link
	 * MatchingConfigurator#refreshGoldenKeys} call this route always made. Default now enqueues it and answers
	 * {@code 202}; {@code ?sync=true} keeps the exact pre-lot blocking behaviour.
	 */
	@POST
	@Path("/projects/{code}/matching/refresh-golden-keys")
	public Response refreshGoldenKeys(@PathParam("code") final String pProjectCode, @QueryParam("entity") final String pEntity,
		@QueryParam("sync") @DefaultValue("false") final boolean pSync)
	{
		// re-reads RecordMetadata and rewrites a stale Xref.goldenKey cache - a real, if narrow, write against the master zone - not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/matching/refresh-golden-keys", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			if (pSync)
			{
				return Response.ok(MatchingConfigurator.refreshGoldenKeys(this.sessionContext.getSession(), dataset, pProjectCode, pEntity).toString()).build();
			}
			return FactoryResource.accepted(GoldenKeyRefreshRun.start(this.sessionContext.getSession(), dataset, pProjectCode, pEntity), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/matching/simulate")
	public Response simulateMatching(@PathParam("code") final String pProjectCode, @QueryParam("entity") final String pEntity,
		@QueryParam("masterKey") final String pMasterKey)
	{
		// read-only and zero-risk (nothing is persisted), but still acts with a live session against the master zone - not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/matching/simulate", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.MATCH_MERGE);
		if (preflight != null)
		{
			return preflight;
		}
		if (pEntity == null || pEntity.trim().isEmpty() || pMasterKey == null || pMasterKey.trim().isEmpty())
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "Both entity and masterKey query parameters are required");
		}
		try
		{
			return Response.ok(MatchingConfigurator.simulate(this.sessionContext.getSession(), dataset, pProjectCode, pEntity, pMasterKey).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/matching/survivorship")
	public Response applySurvivorship(@PathParam("code") final String pProjectCode, @QueryParam("entity") final String pEntity)
	{
		// re-applies trusted-source survivorship to an ALREADY-CONFIGURED table (docs/MATCHING.md, 2026-09-22
		// "Survivorship and trusted sources") - acts as the agent account on match-merge, same guard as
		// matching/configure, not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/matching/survivorship", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.MATCH_MERGE);
		if (preflight != null)
		{
			return preflight;
		}
		if (pEntity == null || pEntity.trim().isEmpty())
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "The entity query parameter is required");
		}
		try
		{
			return Response.ok(MatchingConfigurator.applySurvivorship(this.sessionContext.getSession(), dataset, pProjectCode, pEntity).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/matching/simulate-preview/remove")
	public Response removeMatchingPreviewPolicy(@PathParam("code") final String pProjectCode, @QueryParam("entity") final String pEntity)
	{
		// simulation-preview-policy lot (2026-09-22): manual cleanup of a leftover <entity>__preview policy - see
		// class Javadoc. matching/configure already calls MatchingPreviewPolicy.remove itself right after a
		// successful accept; this route exists only for the cases that leaves uncovered (rejected after being
		// simulated, an abandoned draft, ...). Same guard as every other matching/* route - not for every user.
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/matching/simulate-preview/remove", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.MATCH_MERGE);
		if (preflight != null)
		{
			return preflight;
		}
		if (pEntity == null || pEntity.trim().isEmpty())
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "The entity query parameter is required");
		}
		try
		{
			return Response.ok(MatchingPreviewPolicy.remove(this.sessionContext.getSession(), dataset, pProjectCode, pEntity).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/capabilities/suggest-llm")
	public Response suggestCapabilitiesLlm(@PathParam("code") final String pProjectCode, @QueryParam("entity") final String pEntity,
		@QueryParam("maxPromptChars") final Integer pMaxPromptChars)
	{
		// spends tokens and uses the language model connector's key, whoever asks: not for every user - same guard as staging/suggest-llm
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/capabilities/suggest-llm", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(CapabilityLlmSuggester.suggest(this.sessionContext.getSession(), dataset, pProjectCode, pEntity, pMaxPromptChars).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/capabilities/{id}/apply")
	public Response applyCapability(@PathParam("code") final String pProjectCode, @PathParam("id") final String pSuggestionId,
		@QueryParam("measureMaxRecords") final Long pMeasureMaxRecords)
	{
		// declares/authors/publishes on the data-model and scripting MCP servers as the agent account, and may spend language-model tokens: not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/capabilities/{id}/apply", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL, McpServers.SCRIPTING);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			// lot M5: dispatched on the row's own nature (SCRIPT_CONSTRAINT or SCRIPT_DERIVATION), exactly as the review screen does
			final ObjectNode out = CapabilityDispatcher.apply(this.sessionContext.getSession(), dataset, pProjectCode, pSuggestionId, pMeasureMaxRecords);
			return Response.ok(out.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/capabilities/apply")
	public Response applyCapabilities(@PathParam("code") final String pProjectCode, @QueryParam("ids") final String pIds,
		@QueryParam("measureMaxRecords") final Long pMeasureMaxRecords)
	{
		// same guard as the single-id route: declares/authors/publishes as the agent account, may spend language-model tokens
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/capabilities/apply", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL, McpServers.SCRIPTING);
		if (preflight != null)
		{
			return preflight;
		}
		final List<String> ids = new ArrayList<String>();
		if (pIds != null && !pIds.trim().isEmpty())
		{
			for (final String id : pIds.split(","))
			{
				if (!id.trim().isEmpty())
				{
					ids.add(id.trim());
				}
			}
		}
		try
		{
			final ObjectNode out = CapabilityDispatcher.applyAll(this.sessionContext.getSession(), dataset, pProjectCode, ids, pMeasureMaxRecords);
			return Response.ok(out.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/capabilities/{id}/remove")
	public Response removeCapability(@PathParam("code") final String pProjectCode, @PathParam("id") final String pSuggestionId)
	{
		// removes a declared constraint and republishes on the data-model MCP server as the agent account: not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/capabilities/{id}/remove", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL, McpServers.SCRIPTING);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			return Response.ok(CapabilityDispatcher.remove(this.sessionContext.getSession(), dataset, pProjectCode, pSuggestionId).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Lot T1 (2026-10-04): recomputes, on demand, the rows a staging table already holds for an {@code APPLIED SCRIPT_DERIVATION} ({@code
	 * ebx.DerivationBackfillPass}) - a trigger only fires on a write, so a row loaded before the derivation was applied holds an old copy or nothing.
	 * No MCP call; it writes the derived field of the rows that need it (the triggers compute it), in batches. Answers the pass' node ({@code status}
	 * {@code DONE}, {@code EMPTY}, {@code SKIPPED} when a staging load holds the table, {@code STOPPED}, {@code TRIGGER_NOT_LIVE} - nothing changed -,
	 * {@code FAILED}; {@code scanned}/{@code recomputed}/{@code skipped}, {@code durationMs}). Administrators only: it rewrites a staging table.
	 */
	@POST
	@Path("/projects/{code}/capabilities/{id}/backfill")
	public Response backfillCapability(@PathParam("code") final String pProjectCode, @PathParam("id") final String pSuggestionId)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/capabilities/{id}/backfill", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(DerivationBackfillPass.backfill(this.sessionContext.getSession(), dataset, pProjectCode, pSuggestionId).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Lot P1 (2026-10-04): settles the parameter(s) of a {@code SCRIPT_DERIVATION} suggestion - see the class Javadoc entry and {@code
	 * capability.DerivationParameters}. Writes one {@code CapabilitySuggestion} row, no MCP call, no apply. Administrators only, like every other
	 * capability route: the value ends up in a script the system runs on every record.
	 */
	@PUT
	@Path("/projects/{code}/capabilities/{id}/binding")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response settleDerivationParameters(@PathParam("code") final String pProjectCode, @PathParam("id") final String pSuggestionId, final String pBody)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("PUT /projects/{code}/capabilities/{id}/binding", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final java.util.Map<String, String> values = new java.util.LinkedHashMap<String, String>();
		final JsonBody parsedBody = FactoryResource.readJsonBody(pBody, FactoryResource.MAPPER.createObjectNode());
		if (parsedBody.error != null)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, parsedBody.error);
		}
		final JsonNode body = parsedBody.node;
		if (!body.isObject())
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "The body must be a JSON object {\"<parameter>\":\"<value>\", ...}");
		}
		final java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = body.fields();
		while (fields.hasNext())
		{
			final java.util.Map.Entry<String, JsonNode> field = fields.next();
			// a non-string value is carried as null: the pure rules refuse it with "must be a string"
			values.put(field.getKey(), field.getValue().isTextual() ? field.getValue().asText() : null);
		}
		try
		{
			return Response.ok(DerivationParameterSettings.settle(this.sessionContext.getSession(), dataset, pProjectCode, pSuggestionId, values).toString()).build();
		}
		catch (final com.on.ebx.factory.capability.DerivationParameters.Refusal ex)
		{
			return FactoryResource.problem(Response.Status.fromStatusCode(ex.status), ex.getMessage());
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/capabilities/{id}/measure")
	public Response measureCapability(@PathParam("code") final String pProjectCode, @PathParam("id") final String pSuggestionId)
	{
		// reads a live validation report against the model as the caller's own session (no MCP call): still an administrator-only action, like every other capability route
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/capabilities/{id}/measure", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(CapabilityApplier.measure(this.sessionContext.getSession(), dataset, pProjectCode, pSuggestionId).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@GET
	@Path("/projects/{code}/delivery/domain")
	public Response deliveryDomain(@PathParam("code") final String pProjectCode)
	{
		// read-only, no MCP call, but it lists the names of every generated object of any project and whether each
		// connector has a secret set: administrators only, like the other routes that read the factory's own state
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/delivery/domain", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(this.asCaller(() -> DeliveryDomainReader.read(this.sessionContext.getRepository(), dataset, pProjectCode)).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, ex.getMessage());
		}
	}

	@GET
	@Path("/projects/{code}/lineage/{entity}")
	public Response recordLineage(@PathParam("code") final String pProjectCode, @PathParam("entity") final String pEntity, @QueryParam("key") final String pKey)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/lineage/{entity}", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		// read-only, caller's own session, no MCP call: any authenticated user (like /relations/check and
		// /staging/suggest) - but this route returns real field VALUES, not counts/metadata, so
		// RecordLineageReader itself enforces the caller's own EBX read permissions record-by-record and
		// field-by-field (see its own Javadoc); deliberately not administrators-only (coordinator review,
		// 2026-09-21) - the lineage screen is for data stewards.
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(RecordLineageReader.read(this.sessionContext.getSession(), dataset, pProjectCode, pEntity, pKey).toString()).build();
		}
		catch (final RecordLineageReader.NotFoundException ex)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, ex.getMessage());
		}
		catch (final RecordLineageReader.BadKeyException ex)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, ex.getMessage());
		}
		catch (final OperationException ex)
		{
			// reserved for a genuine state conflict; RecordLineageReader.read does not throw a plain
			// OperationException today (every case it can name is one of the two subclasses above, or, for the
			// merge block specifically, degraded in place - see its own Javadoc) - kept for the same reason
			// every other route in this file keeps it, and in case a future change adds one.
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
		catch (final RuntimeException ex)
		{
			// an unexpected failure OUTSIDE the merge block (which never lets one escape - see
			// RecordLineageReader's own Javadoc): never a 409, never the exception's own message (it could name
			// a path or a raw value), just what step of the read failed.
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] reading record lineage for project " + pProjectCode + ", entity " + pEntity + " failed: " + ex, ex);
			return FactoryResource.problem(Response.Status.INTERNAL_SERVER_ERROR,
				"Reading record lineage for project " + pProjectCode + ", entity " + pEntity + " failed unexpectedly (" + ex.getClass().getSimpleName() + ").");
		}
	}

	@GET
	@Path("/projects/{code}/xrefs/{entity}")
	public Response recordCrossReferences(@PathParam("code") final String pProjectCode, @PathParam("entity") final String pEntity, @QueryParam("key") final String pKey)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/xrefs/{entity}", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		// see the class Javadoc's own writeup of this route: a PURE projection of the exact same
		// RecordLineageReader.read(...) answer /lineage returns (lineage.CrossReferences#project), never a
		// second read and never a second permission decision - so this handler is deliberately a near-copy of
		// #recordLineage above, down to the same three catch blocks, rather than a call to that method (which
		// would answer the WRONG shape, not this route's own flat one).
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			final ObjectNode lineage = RecordLineageReader.read(this.sessionContext.getSession(), dataset, pProjectCode, pEntity, pKey);
			return Response.ok(CrossReferences.project(lineage).toString()).build();
		}
		catch (final RecordLineageReader.NotFoundException ex)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, ex.getMessage());
		}
		catch (final RecordLineageReader.BadKeyException ex)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, ex.getMessage());
		}
		catch (final OperationException ex)
		{
			// same reservation as #recordLineage above - kept for the same reason, and in case a future change
			// to RecordLineageReader.read adds a plain OperationException case.
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
		catch (final RuntimeException ex)
		{
			// an unexpected failure OUTSIDE the merge block, same guard as #recordLineage - never the
			// exception's own message, just what step of the read failed.
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] reading cross-references for project " + pProjectCode + ", entity " + pEntity + " failed: " + ex, ex);
			return FactoryResource.problem(Response.Status.INTERNAL_SERVER_ERROR,
				"Reading cross-references for project " + pProjectCode + ", entity " + pEntity + " failed unexpectedly (" + ex.getClass().getSimpleName() + ").");
		}
	}

	@POST
	@Path("/projects/{code}/catalog/declare-lineage")
	public Response declareCatalogLineage(@PathParam("code") final String pProjectCode)
	{
		// posts to the sibling data catalog with the factory's own agent account, like /connection/test and
		// every other route that reaches an external system - not for every user
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/catalog/declare-lineage", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(CatalogLineageDeclarer.declare(this.sessionContext.getSession(), dataset, pProjectCode).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * See the class Javadoc's own writeup of this route (coordinator review, 2026-09-22, "fact 2"). Appended at
	 * the end of this class on purpose, per that review's own instruction, so it is never mistaken for one of
	 * the ordinary project/source routes above it. Never triggers a project read of any kind - purely {@code
	 * MasterZoneIndex}'s own already-cached state, plus (only when all three query parameters are given) the
	 * exact same check {@code RecordLineageOnRecordPermissionRule} makes for that one triple.
	 */
	@GET
	@Path("/diagnostics/master-zone-index")
	public Response masterZoneIndexDiagnostics(@QueryParam("dataspace") final String pDataspaceKey, @QueryParam("dataset") final String pDatasetName,
		@QueryParam("table") final String pTablePath)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /diagnostics/master-zone-index");
		if (refused != null)
		{
			return refused;
		}
		final boolean queryGiven = pDataspaceKey != null || pDatasetName != null || pTablePath != null;
		final boolean queryComplete = pDataspaceKey != null && pDatasetName != null && pTablePath != null;
		// the "would this triple be enabled" check runs FIRST, through the ordinary TTL-bounded refresh path
		// (MasterZoneIndex.isMasterZoneTable) - so the snapshot read right after it, below, reflects whatever
		// state that very check may just have refreshed. Never a forced, always-live read: this route is a
		// diagnostic, not a way to bypass the cache's own cost discipline.
		final Boolean wouldBeEnabled = queryComplete ? Boolean.valueOf(MasterZoneIndex.isMasterZoneTable(pDataspaceKey, pDatasetName, pTablePath)) : null;
		final MasterZoneIndex.Diagnostics diagnostics = MasterZoneIndex.diagnostics();
		final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
		out.put("cachedAt", diagnostics.cachedAt);
		out.put("tripleCount", diagnostics.tripleCount);
		if (diagnostics.lastRefreshError != null)
		{
			out.put("lastRefreshError", diagnostics.lastRefreshError);
		}
		final ArrayNode triples = out.putArray("triples");
		for (final String triple : diagnostics.triples)
		{
			triples.add(triple);
		}
		if (queryGiven)
		{
			final ObjectNode query = out.putObject("query");
			query.put("dataspace", pDataspaceKey);
			query.put("dataset", pDatasetName);
			query.put("table", pTablePath);
			if (queryComplete)
			{
				query.put("wouldBeEnabled", wouldBeEnabled.booleanValue());
			}
			else
			{
				query.put("error", "dataspace, dataset and table must all be given together to evaluate a triple");
			}
		}
		return Response.ok(out.toString()).build();
	}

	// ================================================================================= Distribution lot 1 (engine)
	// SPEC-distribution.md. Admin-only, like every other write route above. A UI lot builds a wizard screen on
	// top of this API later - see docs/DISTRIBUTION.md for the exact request/response shapes.

	@GET
	@Path("/projects/{code}/distribution/targets")
	public Response listDistributionTargets(@PathParam("code") final String pProjectCode)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/distribution/targets", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(this.asCaller(() -> DistributionReader.listTargets(dataset, pProjectCode)).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/** Coordinator review 2026-09-24, item 5: candidate connections for the target picker, with/without a write operation - see {@code DistributionConnections}' own "not verified" note. */
	@GET
	@Path("/projects/{code}/distribution/connections")
	public Response listDistributionConnections(@PathParam("code") final String pProjectCode)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/distribution/connections", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.CONNECTORS);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			return Response.ok(DistributionConnections.listCandidateConnections(this.sessionContext.getSession(), dataset).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/distribution/targets")
	public Response upsertDistributionTarget(@PathParam("code") final String pProjectCode, final String pBody)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/distribution/targets", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(DistributionReader.upsertTarget(this.sessionContext.getSession(), dataset, pProjectCode, pBody).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/distribution/targets/{targetId}/configure")
	public Response configureDistributionTarget(@PathParam("code") final String pProjectCode, @PathParam("targetId") final String pTargetId)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/distribution/targets/{targetId}/configure", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.CONNECTORS);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			return Response.ok(DistributionConfigurator.configure(this.sessionContext.getSession(), dataset, pProjectCode, pTargetId).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/distribution/targets/{targetId}/install-triggers")
	public Response installDistributionTriggers(@PathParam("code") final String pProjectCode, @PathParam("targetId") final String pTargetId,
		@QueryParam("modelName") final String pModelName)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/distribution/targets/{targetId}/install-triggers", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL, McpServers.SCRIPTING);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			return Response.ok(DistributionTriggerInstaller.install(this.sessionContext.getSession(), dataset, pProjectCode, pModelName).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 1): default now enqueues {@link DistributionSendRun#startFirstFull}
	 * (resource DISTRIBUTION, new this lot) and answers {@code 202} instead of running one {@code
	 * connectors_sync} call per mapping on the request thread. {@code ?sync=true} keeps the exact pre-lot
	 * blocking behaviour.
	 */
	@POST
	@Path("/projects/{code}/distribution/targets/{targetId}/first-full-send")
	public Response firstFullSendDistributionTarget(@PathParam("code") final String pProjectCode, @PathParam("targetId") final String pTargetId,
		@QueryParam("sync") @DefaultValue("false") final boolean pSync)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/distribution/targets/{targetId}/first-full-send", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.CONNECTORS);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			if (pSync)
			{
				return Response.ok(DistributionRunner.sendFirstFull(this.sessionContext.getSession(), dataset, pProjectCode, pTargetId).toString()).build();
			}
			return FactoryResource.accepted(DistributionSendRun.startFirstFull(this.sessionContext.getSession(), dataset, pProjectCode, pTargetId), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/** Admin override, SPEC-distribution.md "Arming gate": arms the target with no first full send attempted. */
	@POST
	@Path("/projects/{code}/distribution/targets/{targetId}/arm-override")
	public Response armOverrideDistributionTarget(@PathParam("code") final String pProjectCode, @PathParam("targetId") final String pTargetId)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/distribution/targets/{targetId}/arm-override", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(DistributionRunner.armWithoutFirstFullSend(this.sessionContext.getSession(), dataset, pTargetId).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@POST
	@Path("/projects/{code}/distribution/targets/{targetId}/disarm")
	public Response disarmDistributionTarget(@PathParam("code") final String pProjectCode, @PathParam("targetId") final String pTargetId)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/distribution/targets/{targetId}/disarm", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(DistributionReader.setState(this.sessionContext.getSession(), dataset, pTargetId, FactoryPaths.DistributionTarget.STATE_DISABLED).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 1): default now enqueues {@link DistributionSendRun#startSendNow}
	 * (resource DISTRIBUTION) and answers {@code 202}; {@code ?sync=true} keeps the exact pre-lot blocking
	 * behaviour.
	 */
	@POST
	@Path("/projects/{code}/distribution/targets/{targetId}/send")
	public Response sendNowDistributionTarget(@PathParam("code") final String pProjectCode, @PathParam("targetId") final String pTargetId,
		@QueryParam("scope") final String pScope, @QueryParam("sync") @DefaultValue("false") final boolean pSync)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/distribution/targets/{targetId}/send", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.CONNECTORS);
		if (preflight != null)
		{
			return preflight;
		}
		final String scope;
		try
		{
			// 2026-10-12: only "changed" and "all" are accepted; blank stays "the target's own scope" (null)
			scope = ScopeParam.parse(pScope, "changed", "all");
		}
		catch (final IllegalArgumentException ex)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, ex.getMessage());
		}
		try
		{
			if (pSync)
			{
				return Response.ok(DistributionRunner.sendNow(this.sessionContext.getSession(), dataset, pProjectCode, pTargetId, scope).toString()).build();
			}
			return FactoryResource.accepted(DistributionSendRun.startSendNow(this.sessionContext.getSession(), dataset, pProjectCode, pTargetId, scope), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Wave 13 (2026-10-12): stops the send of a target - cancels its queued or running first-full / send-now jobs (the running one stops its Connectors
	 * run at the next poll and ends STOPPED) and stops, with {@code connectors_run_stop}, any recorded Connectors run that {@code connectors_run_get}
	 * still reports running (a run that outlived a restart of ON EBX). Administrators only. Answers {@code {targetId, stopped, jobsCancelled[],
	 * runsStopped[], message}}; {@code stopped=false} when nothing was running.
	 */
	@POST
	@Path("/projects/{code}/distribution/targets/{targetId}/stop")
	public Response stopDistributionTarget(@PathParam("code") final String pProjectCode, @PathParam("targetId") final String pTargetId)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/distribution/targets/{targetId}/stop", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(com.on.ebx.factory.ebx.DistributionSendStop.stop(this.sessionContext.getSession(), dataset, pProjectCode, pTargetId).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	@GET
	@Path("/projects/{code}/distribution/events")
	public Response listDistributionEvents(@PathParam("code") final String pProjectCode, @QueryParam("targetId") final String pTargetId,
		@QueryParam("status") final String pStatus, @QueryParam("limit") @DefaultValue("50") final int pLimit)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/distribution/events", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(this.asCaller(() -> DistributionReader.listEvents(dataset, pProjectCode, pTargetId, pStatus, pLimit)).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/** Resets one or every FAILED event of a target back to PENDING - {@link DistributionDispatcher} picks it up on its next pass. SPEC-distribution.md "replay from the Cockpit". */
	@POST
	@Path("/projects/{code}/distribution/events/replay")
	public Response replayDistributionEvents(@PathParam("code") final String pProjectCode, @QueryParam("targetId") final String pTargetId,
		@QueryParam("eventId") final String pEventId)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/distribution/events/replay", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return Response.ok(DistributionReader.replayEvents(this.sessionContext.getSession(), dataset, pProjectCode, pTargetId, pEventId).toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	// ================================================================================= Automatic intake lot (2026-10-05)
	// docs/AUTOMATIC-INTAKE.md. The facade (ebx.Intake) holds the authorisation of each action; the routes only map it to HTTP.

	/** One intake action: runs against the factory dataset and answers the JSON text. */
	private interface IntakeAction
	{
		String run(Adaptation pDataset) throws OperationException;
	}

	private Response intakeRoute(final boolean pOperator, final IntakeAction pAction)
	{
		return this.intakeRoute(pOperator, Response.Status.OK, pAction);
	}

	private Response intakeRoute(final boolean pOperator, final Response.Status pOkStatus, final IntakeAction pAction)
	{
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		if (pOperator && !com.on.ebx.factory.ebx.Intake.mayOperate(this.sessionContext.getSession(), dataset))
		{
			return FactoryResource.problem(Response.Status.FORBIDDEN, "Administrators, or holders of the pipeline launcher role, only");
		}
		try
		{
			return Response.status(pOkStatus).entity(pAction.run(dataset)).build();
		}
		catch (final OperationException ex)
		{
			final String message = ex.getMessage() == null ? "" : ex.getMessage();
			final Response.Status status = message.startsWith("Forbidden") ? Response.Status.FORBIDDEN : message.endsWith(" not found") ? Response.Status.NOT_FOUND : Response.Status.CONFLICT;
			return FactoryResource.problem(status, message);
		}
	}

	@GET
	@Path("/projects/{code}/intake")
	public Response intakeStatus(@PathParam("code") final String pProjectCode)
	{
		// audit F-25 (merged 2026-10-05): every GET of this resource is for administrators, the wizard that polls this route included
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/intake", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		return this.intakeRoute(false, dataset -> com.on.ebx.factory.ebx.Intake.status(this.sessionContext.getSession(), dataset, pProjectCode).toJson().toString());
	}

	@POST
	@Path("/projects/{code}/intake/settings")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response intakeSettings(@PathParam("code") final String pProjectCode, final String pBody)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/intake/settings", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		final JsonBody parsedBody = FactoryResource.readJsonBody(pBody, FactoryResource.MAPPER.createObjectNode());
		if (parsedBody.error != null)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, parsedBody.error);
		}
		final JsonNode body = parsedBody.node;
		final Boolean reviewRejects = body.hasNonNull("reviewRejects") ? Boolean.valueOf(body.path("reviewRejects").asBoolean(true)) : null;
		final Integer percent = body.hasNonNull("stopRejectPercent") ? Integer.valueOf(body.path("stopRejectPercent").asInt(0)) : null;
		final java.util.List<com.on.ebx.factory.ebx.Intake.SourceSettings> sources = new java.util.ArrayList<com.on.ebx.factory.ebx.Intake.SourceSettings>();
		for (final JsonNode node : body.path("sources"))
		{
			final com.on.ebx.factory.ebx.Intake.SourceSettings settings = new com.on.ebx.factory.ebx.Intake.SourceSettings();
			settings.sourceSystem = node.path("sourceSystem").asText(null);
			settings.quietSeconds = node.hasNonNull("quietSeconds") ? Integer.valueOf(node.path("quietSeconds").asInt(0)) : null;
			settings.maxWaitMinutes = node.hasNonNull("maxWaitMinutes") ? Integer.valueOf(node.path("maxWaitMinutes").asInt(0)) : null;
			settings.minRows = node.hasNonNull("minRows") ? Integer.valueOf(node.path("minRows").asInt(0)) : null;
			settings.batchSignal = node.hasNonNull("batchSignal") ? Boolean.valueOf(node.path("batchSignal").asBoolean(false)) : null;
			sources.add(settings);
		}
		return this.intakeRoute(true,
			dataset -> com.on.ebx.factory.ebx.Intake.saveSettings(this.sessionContext.getSession(), dataset, pProjectCode, reviewRejects, percent, sources).toJson().toString());
	}

	@POST
	@Path("/projects/{code}/intake/on")
	public Response intakeOn(@PathParam("code") final String pProjectCode)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/intake/on", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		return this.intakeRoute(true, dataset -> com.on.ebx.factory.ebx.Intake.switchOn(this.sessionContext.getSession(), dataset, pProjectCode).toJson().toString());
	}

	@POST
	@Path("/projects/{code}/intake/pause")
	public Response intakePause(@PathParam("code") final String pProjectCode)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/intake/pause", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		return this.intakeRoute(true, dataset -> com.on.ebx.factory.ebx.Intake.pause(this.sessionContext.getSession(), dataset, pProjectCode).toJson().toString());
	}

	@POST
	@Path("/projects/{code}/intake/resume")
	public Response intakeResume(@PathParam("code") final String pProjectCode)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/intake/resume", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		return this.intakeRoute(true, dataset -> com.on.ebx.factory.ebx.Intake.resume(this.sessionContext.getSession(), dataset, pProjectCode).toJson().toString());
	}

	@POST
	@Path("/projects/{code}/intake/off")
	public Response intakeOff(@PathParam("code") final String pProjectCode)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/intake/off", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		return this.intakeRoute(true, dataset -> com.on.ebx.factory.ebx.Intake.switchOff(this.sessionContext.getSession(), dataset, pProjectCode).toJson().toString());
	}

	@POST
	@Path("/projects/{code}/intake/process")
	public Response intakeProcess(@PathParam("code") final String pProjectCode)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/intake/process", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		return this.intakeRoute(true, dataset -> com.on.ebx.factory.ebx.Intake.processNow(this.sessionContext.getSession(), dataset, pProjectCode).toJson().toString());
	}

	/**
	 * "Read now" of one application: {@code POST /projects/{code}/intake/{source}:read} (operator; answers {@code 202} with the job, see {@code Intake#readNow}). Same {@code :suffix} routing as
	 * {@code :complete}, checked in {@code IntakeRoutesTest}.
	 */
	@POST
	@Path("/projects/{code}/intake/{source: [^/:]+}:read")
	public Response intakeReadNow(@PathParam("code") final String pProjectCode, @PathParam("source") final String pSourceSystem)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/intake/{source: [^/:]+}:read", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		return this.intakeRoute(true, Response.Status.ACCEPTED,
			dataset -> com.on.ebx.factory.ebx.Intake.readNow(this.sessionContext.getSession(), dataset, pProjectCode, pSourceSystem).toString());
	}

	/** The application's call: {@code POST /projects/{code}/intake/{source}:complete}. The variable's regex excludes ':' so the literal suffix routes (checked against CXF's URITemplate in {@code IntakeRoutesTest}). */
	@POST
	@Path("/projects/{code}/intake/{source: [^/:]+}:complete")
	public Response intakeBatchComplete(@PathParam("code") final String pProjectCode, @PathParam("source") final String pSourceSystem)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/intake/{source: [^/:]+}:complete");
		if (refused != null)
		{
			return refused;
		}
		return this.intakeRoute(false,
			dataset -> com.on.ebx.factory.ebx.Intake.batchComplete(this.sessionContext.getSession(), dataset, pProjectCode, pSourceSystem).toString());
	}

	// ------------------------------------------------------------------------------------------------------------------------------
	// Scenario routes (lot H2, 2026-10-17, docs/E2E-B2C.md): thin wrappers over what the wizard's own buttons call, so that
	// tools/e2e/b2c_scenario.sh can drive a whole landing + staging run without a browser. Nothing here has logic of its own: each route
	// starts the same job (or makes the same single decision) as its button. Landing run, staging proposal and bulk decision answer 202
	// with the job, polled on GET /projects/{code}/jobs; the review and the single decisions are synchronous.
	// ------------------------------------------------------------------------------------------------------------------------------

	/** The wizard's "Prepare the landing" button (step 3): {@code ebx.LandingRun.start}. {@code useLlm} defaults to false: a key already chosen or validated needs no model. */
	@POST
	@Path("/projects/{code}/landing/run")
	public Response runLanding(@PathParam("code") final String pProjectCode, @QueryParam("useLlm") @DefaultValue("false") final boolean pUseLlm,
		@QueryParam("fullRefresh") @DefaultValue("false") final boolean pFullRefresh)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/landing/run", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL, McpServers.CONNECTORS);
		if (preflight != null)
		{
			return preflight;
		}
		try
		{
			return FactoryResource.accepted(com.on.ebx.factory.ebx.LandingRun.start(this.sessionContext.getSession(), dataset, pProjectCode, pUseLlm, pFullRefresh), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/** The wizard's "Propose the staging" button (step 4): the whole proposal chain as one job ({@code ebx.StagingProposalRun.start}). Both optional passes default to on, like the wizard's checkboxes. */
	@POST
	@Path("/projects/{code}/staging/propose")
	public Response proposeStaging(@PathParam("code") final String pProjectCode, @QueryParam("quality") @DefaultValue("true") final boolean pQuality,
		@QueryParam("capability") @DefaultValue("true") final boolean pCapability)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/staging/propose", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		try
		{
			return FactoryResource.accepted(com.on.ebx.factory.ebx.StagingProposalRun.start(this.sessionContext.getSession(), dataset, pProjectCode, pQuality, pCapability), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * The matrix' "Accept the N proposals at {threshold} % or above" button (and its Reject twin): the bulk sweep job ({@code ebx.StagingBulkDecideRun.start}).
	 * {@code accept=false} rejects instead; {@code entity} (a StagingEntity id) restricts it to one business object; {@code includeBelowThreshold} also takes the
	 * lines under the bar (the wizard's "Accept all"). Without {@code threshold} the bar is 60.
	 */
	@POST
	@Path("/projects/{code}/staging/decide")
	public Response decideStagingInBulk(@PathParam("code") final String pProjectCode, @QueryParam("accept") @DefaultValue("true") final boolean pAccept,
		@QueryParam("threshold") @DefaultValue("60") final int pThreshold, @QueryParam("includeBelowThreshold") @DefaultValue("false") final boolean pIncludeBelow,
		@QueryParam("entity") final String pEntityId)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/staging/decide", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		if (pThreshold < 0 || pThreshold > 100)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "threshold must be between 0 and 100");
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Adaptation project = com.on.ebx.factory.ebx.ProposalReviews.findProjectByCode(dataset, pProjectCode);
		if (project == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Project " + pProjectCode + " not found");
		}
		try
		{
			return FactoryResource.accepted(com.on.ebx.factory.ebx.StagingBulkDecideRun.start(this.sessionContext.getSession(), dataset, project.getOccurrencePrimaryKey().format(),
				pProjectCode, pAccept, pEntityId == null || pEntityId.trim().isEmpty() ? null : pEntityId.trim(), null, pIncludeBelow, pThreshold), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * The proposals of the project as the Proposal review screen lists them ({@code ebx.ProposalReviews.lines}, read with the caller's view): {@code {"lines":[{nature,id,element,proposition,origin,
	 * confidence,rationale,status}], "counts":{"<status>":n}}}. {@code status} (default PROPOSED, {@code ALL} for every line) filters the {@code lines}; {@code counts} always covers every line.
	 * What a person still has to decide after a bulk accept is this list with the default filter.
	 */
	@GET
	@Path("/projects/{code}/staging/review")
	public Response stagingReview(@PathParam("code") final String pProjectCode, @QueryParam("status") @DefaultValue("PROPOSED") final String pStatus)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/staging/review", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Adaptation project = com.on.ebx.factory.ebx.ProposalReviews.findProjectByCode(dataset, pProjectCode);
		if (project == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Project " + pProjectCode + " not found");
		}
		final String wanted = pStatus == null || pStatus.trim().isEmpty() ? "PROPOSED" : pStatus.trim().toUpperCase(java.util.Locale.ROOT);
		try
		{
			final List<com.on.ebx.factory.review.ReviewLine> all = this.asCaller(
				() -> com.on.ebx.factory.ebx.ProposalReviews.lines(dataset, project.getOccurrencePrimaryKey().format(), java.util.Locale.ENGLISH));
			final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
			final ObjectNode counts = out.putObject("counts");
			final java.util.Map<String, Integer> tally = new java.util.TreeMap<String, Integer>();
			final ArrayNode lines = out.putArray("lines");
			// staging integration (2026-10-17): like step 4 (wizard.Step4Counts), a business object absorbed by a canonical one (SUPERSEDED) and its own
			// fields, links and rules are no decision of anyone's - leave them out (the first automated B2C run counted 41 such lines as "still to decide")
			final java.util.Set<String> superseded = new java.util.HashSet<String>();
			final com.onwbp.adaptation.RequestResult entities = dataset.getTable(com.on.ebx.factory.core.FactoryPaths.STAGING_ENTITY).createRequestResult(
				"./project='" + project.getOccurrencePrimaryKey().format().replace("'", "''") + "' and ./status='SUPERSEDED'");
			try
			{
				for (Adaptation entity = entities.nextAdaptation(); entity != null; entity = entities.nextAdaptation())
				{
					superseded.add(entity.getString(com.on.ebx.factory.core.FactoryPaths.StagingEntity.NAME));
				}
			}
			finally
			{
				entities.close();
			}
			for (final com.on.ebx.factory.review.ReviewLine line : all)
			{
				final String element = line.element == null ? "" : line.element;
				final int dot = element.indexOf('.');
				if (superseded.contains(dot < 0 ? element : element.substring(0, dot)))
				{
					continue;
				}
				tally.merge(String.valueOf(line.status), Integer.valueOf(1), Integer::sum);
				if (!"ALL".equals(wanted) && !wanted.equals(line.status))
				{
					continue;
				}
				final ObjectNode node = lines.addObject();
				node.put("nature", line.nature);
				node.put("id", line.rowId);
				node.put("element", line.element);
				node.put("proposition", line.proposition);
				node.put("origin", line.origin);
				if (line.confidence != null)
				{
					node.put("confidence", line.confidence.intValue());
				}
				node.put("rationale", line.rationale == null ? "" : (line.rationale.length() <= 1500 ? line.rationale : line.rationale.substring(0, 1500)));
				node.put("status", line.status);
			}
			for (final java.util.Map.Entry<String, Integer> entry : tally.entrySet())
			{
				counts.put(entry.getKey(), entry.getValue().intValue());
			}
			return Response.ok(out.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Single decisions, the same call as a button on a card ({@code ebx.ProposalReviews.decide}: rule check, cascades, refusal). Body {@code {"decisions":[{"nature":"ATTRIBUTE","id":"2693","status":"REJECTED"}]}},
	 * at most 200 per call, {@code status} ACCEPTED or REJECTED. A decision on a row that is not a line of this project is refused (the id alone would reach any project). Answers
	 * {@code {"results":[{nature,id,ok,reason,newStatus}]}} in the order sent; a refused line does not stop the following ones.
	 */
	@POST
	@Path("/projects/{code}/staging/decisions")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response decideStagingLines(@PathParam("code") final String pProjectCode, final String pBody)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/staging/decisions", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		final JsonBody parsedBody = FactoryResource.readJsonBody(pBody, FactoryResource.MAPPER.createObjectNode());
		if (parsedBody.error != null)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, parsedBody.error);
		}
		final JsonNode decisions = parsedBody.node.path("decisions");
		if (!decisions.isArray() || decisions.size() == 0 || decisions.size() > 200)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "The body must be {\"decisions\":[{\"nature\",\"id\",\"status\"}...]} with 1 to 200 entries");
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Adaptation project = com.on.ebx.factory.ebx.ProposalReviews.findProjectByCode(dataset, pProjectCode);
		if (project == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Project " + pProjectCode + " not found");
		}
		try
		{
			final Set<String> ofProject = new java.util.HashSet<String>();
			for (final com.on.ebx.factory.review.ReviewLine line : this.asCaller(
				() -> com.on.ebx.factory.ebx.ProposalReviews.lines(dataset, project.getOccurrencePrimaryKey().format(), java.util.Locale.ENGLISH)))
			{
				ofProject.add(line.nature + "|" + line.rowId);
			}
			final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
			final ArrayNode results = out.putArray("results");
			for (final JsonNode decision : decisions)
			{
				final String nature = decision.path("nature").asText("");
				final String id = decision.path("id").asText("");
				final String status = decision.path("status").asText("");
				final ObjectNode result = results.addObject();
				result.put("nature", nature);
				result.put("id", id);
				if (!"ACCEPTED".equals(status) && !"REJECTED".equals(status))
				{
					result.put("ok", false);
					result.put("reason", "status must be ACCEPTED or REJECTED");
					continue;
				}
				if (!ofProject.contains(nature + "|" + id))
				{
					result.put("ok", false);
					result.put("reason", "not a proposal line of project " + pProjectCode);
					continue;
				}
				final com.on.ebx.factory.ebx.ProposalReviews.DecisionResult decided = com.on.ebx.factory.ebx.ProposalReviews.decide(this.sessionContext.getSession(), dataset, nature, id, status);
				result.put("ok", decided.ok);
				if (decided.reason != null)
				{
					result.put("reason", decided.reason);
				}
				if (decided.newStatus != null)
				{
					result.put("newStatus", decided.newStatus);
				}
			}
			return Response.ok(out.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Lot E2E-master (2026-10-17): the wizard's step 5 "Build the master" button - the whole one-click run (master model and dataset, the copy of every promoted business object's
	 * staging rows, the matching hand-over) as one job ({@code ebx.MasterBuildRun.start}, resource MASTER). The older {@code master/generate} and {@code master/load} routes run only one
	 * stage each; this is the run the page queues.
	 */
	@POST
	@Path("/projects/{code}/master/build")
	public Response buildMaster(@PathParam("code") final String pProjectCode, @QueryParam("scope") final String pScope)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/master/build", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Response preflight = FactoryResource.preflight(this.sessionContext.getSession(), dataset, McpServers.DATA_MODEL);
		if (preflight != null)
		{
			return preflight;
		}
		final String scope;
		try
		{
			scope = ScopeParam.parse(pScope, MasterLoadScope.FULL, MasterLoadScope.CHANGED);
		}
		catch (final IllegalArgumentException ex)
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, ex.getMessage());
		}
		try
		{
			return FactoryResource.accepted(MasterBuildRun.start(this.sessionContext.getSession(), dataset, pProjectCode, MasterBuildRun.STAGES, scope), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Lot E2E-master (2026-10-17): the wizard's step 6 "Propose the matching plans" button - the rules, then (when a language-model connector is configured and the caller may use it) the
	 * model for the objects the rules left without a plan, as one job ({@code ebx.SuggestRulesRun.startMatching}). {@code useLlm=false} forbids the model pass; the default is "as the
	 * instance is configured", exactly what the page decides ({@code snapshot.designer && snapshot.llmConfigured}). The older {@code matching/suggest} and {@code matching/suggest-llm}
	 * routes run one pass each, synchronously.
	 */
	@POST
	@Path("/projects/{code}/matching/propose")
	public Response proposeMatching(@PathParam("code") final String pProjectCode, @QueryParam("useLlm") @DefaultValue("true") final boolean pUseLlm)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/matching/propose", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Adaptation project = com.on.ebx.factory.ebx.ProposalReviews.findProjectByCode(dataset, pProjectCode);
		if (project == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Project " + pProjectCode + " not found");
		}
		try
		{
			final com.on.ebx.factory.wizard.WizardSnapshot snapshot = com.on.ebx.factory.ebx.WizardData.readSnapshot(
				com.on.ebx.factory.ebx.UserView.of(this.sessionContext.getSession()), dataset, project.getOccurrencePrimaryKey().format(), this.sessionContext.getSession());
			return FactoryResource.accepted(com.on.ebx.factory.ebx.SuggestRulesRun.startMatching(this.sessionContext.getSession(), dataset, pProjectCode,
				pUseLlm && snapshot.designer && snapshot.llmConfigured), pProjectCode);
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Lot E2E-master (2026-10-17): the "Simulate" button of a matching plan card (step 6, and the Proposal review screen) - {@code ebx.MatchPlanSimulationRunner.simulate} on the plan
	 * {@code plan} (a {@code MatchPlan} row id, a PROPOSED, ACCEPTED or APPLIED one, as the page allows). On a plan not applied yet it writes the {@code <Entity>__preview} policy on the master
	 * table ({@code ebx.MatchingPreviewPolicy}) and simulates on it - the very thing that, on 2026-10-17, made the next Configure refuse the entity. Answers {@code {entity, plan, pivots,
	 * pairs, verdicts:{MATCHED:n,...}, onPreviewPolicy, policyCode, failure}}; the page's own refusals (activation, no master zone) come back as 409 with their sentence.
	 */
	@POST
	@Path("/projects/{code}/matching/simulate-plan")
	public Response simulateMatchPlan(@PathParam("code") final String pProjectCode, @QueryParam("plan") final String pPlanId)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("POST /projects/{code}/matching/simulate-plan", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		if (pPlanId == null || pPlanId.trim().isEmpty())
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "The plan query parameter (a MatchPlan row id) is required");
		}
		FactoryInstaller.ensure(this.sessionContext.getRepository(), this.sessionContext.getSession());
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final Adaptation project = com.on.ebx.factory.ebx.ProposalReviews.findProjectByCode(dataset, pProjectCode);
		if (project == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Project " + pProjectCode + " not found");
		}
		try
		{
			com.on.ebx.factory.review.ReviewLine plan = null;
			for (final com.on.ebx.factory.review.ReviewLine line : this.asCaller(
				() -> com.on.ebx.factory.ebx.ProposalReviews.lines(dataset, project.getOccurrencePrimaryKey().format(), java.util.Locale.ENGLISH)))
			{
				if (com.on.ebx.factory.review.ReviewLine.MATCH.equals(line.nature) && pPlanId.trim().equals(line.rowId))
				{
					plan = line;
					break;
				}
			}
			if (plan == null)
			{
				return FactoryResource.problem(Response.Status.NOT_FOUND, "Matching plan " + pPlanId + " is not a plan of project " + pProjectCode);
			}
			if (!(com.on.ebx.factory.review.ReviewLine.PROPOSED.equals(plan.status) || com.on.ebx.factory.review.ReviewLine.ACCEPTED.equals(plan.status)
				|| com.on.ebx.factory.review.ReviewLine.APPLIED.equals(plan.status)))
			{
				return FactoryResource.problem(Response.Status.CONFLICT, "A plan in status " + plan.status + " cannot be simulated");
			}
			final com.on.ebx.factory.review.MatchPlanView.SimulationResult result = com.on.ebx.factory.ebx.MatchPlanSimulationRunner.simulate(this.sessionContext.getSession(), dataset,
				pProjectCode, project.getOccurrencePrimaryKey().format(), plan.element, plan.matchRequirementsJson, plan.rowId);
			final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
			out.put("entity", plan.element);
			out.put("plan", plan.rowId);
			out.put("pivots", result.pivotCount);
			out.put("pairs", result.pairs.size());
			final java.util.Map<String, Integer> verdicts = new java.util.TreeMap<String, Integer>();
			for (final com.on.ebx.factory.review.MatchPlanView.SimulationPair pair : result.pairs)
			{
				verdicts.merge(String.valueOf(pair.verdict), Integer.valueOf(1), Integer::sum);
			}
			final ObjectNode verdictsNode = out.putObject("verdicts");
			for (final java.util.Map.Entry<String, Integer> entry : verdicts.entrySet())
			{
				verdictsNode.put(entry.getKey(), entry.getValue().intValue());
			}
			out.put("onPreviewPolicy", result.onPreviewPolicy);
			out.put("policyCode", result.policyCode == null ? "" : result.policyCode);
			if (result.failureMessage != null)
			{
				out.put("failure", result.failureMessage);
			}
			return Response.ok(out.toString()).build();
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Lot A of the hard-cases scenario (2026-10-17): why the rows held back by a transfer were set aside - the very counts and the very sentence the wizard's step 4 banner words
	 * ({@code ebx.RejectCauseReader} + {@code rejects.RejectCauses}, one aggregate query on the unresolved rejects, run with the caller's session). {@code stage} is STAGING (default)
	 * or MASTER. Answers {@code {"stage","total","counts":{"<category>":n},"sentence","causePhrase","blamesARule","entities":{"<name>":{"total","counts","sentence"}}}}; the sentences are
	 * English (this REST surface carries no locale). Empty counts and an empty sentence when nothing is held back (or when the caller may not read the rejects).
	 */
	@GET
	@Path("/projects/{code}/rejects/causes")
	public Response rejectCauses(@PathParam("code") final String pProjectCode, @QueryParam("stage") @DefaultValue("STAGING") final String pStage)
	{
		// audit F-02: the right of this route (core.FactoryRights)
		final Response refused = this.refused("GET /projects/{code}/rejects/causes", pProjectCode);
		if (refused != null)
		{
			return refused;
		}
		final String stage = pStage == null ? "STAGING" : pStage.trim().toUpperCase(java.util.Locale.ROOT);
		if (!"STAGING".equals(stage) && !"MASTER".equals(stage))
		{
			return FactoryResource.problem(Response.Status.BAD_REQUEST, "stage must be STAGING or MASTER");
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final com.on.ebx.factory.ebx.RejectCauseReader.Causes causes = "MASTER".equals(stage)
			? com.on.ebx.factory.ebx.RejectCauseReader.master(this.sessionContext.getSession(), dataset, pProjectCode)
			: com.on.ebx.factory.ebx.RejectCauseReader.staging(this.sessionContext.getSession(), dataset, pProjectCode);
		final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
		out.put("stage", stage);
		FactoryResource.putCauses(out, causes.project());
		final ObjectNode entities = out.putObject("entities");
		for (final java.util.Map.Entry<String, com.on.ebx.factory.rejects.RejectCauses> entry : causes.byEntity().entrySet())
		{
			FactoryResource.putCauses(entities.putObject(entry.getKey()), entry.getValue());
		}
		return Response.ok(out.toString()).build();
	}

	private static void putCauses(final ObjectNode pNode, final com.on.ebx.factory.rejects.RejectCauses pCauses)
	{
		pNode.put("total", pCauses.total());
		final ObjectNode counts = pNode.putObject("counts");
		for (final java.util.Map.Entry<String, Long> entry : pCauses.counts().entrySet())
		{
			counts.put(entry.getKey(), entry.getValue().longValue());
		}
		pNode.put("sentence", pCauses.sentence(java.util.Locale.ENGLISH));
		pNode.put("causePhrase", pCauses.causePhrase(java.util.Locale.ENGLISH));
		pNode.put("blamesARule", pCauses.blamesARule());
	}

	/**
	 * The head of every route that acts on the MCP servers as the agent account: {@link McpServers#preflight}
	 * on the servers that route needs, BEFORE any side effect. Null when every named server answers its tool
	 * list and its probe call; otherwise a {@code 503} whose {@code error} names each failing server with its
	 * problem (the same sentences as {@code /connection/test}) and whose {@code servers} carries the full
	 * per-server lines. A disabled server, a revoked account or a wrong base URL is thus reported as a
	 * connection problem, never as a failed pipeline step with a dataspace or a half-built model behind it.
	 */
	private static Response preflight(final Adaptation pDataset, final String... pServers)
	{
		return FactoryResource.preflight(null, pDataset, pServers);
	}

	/** With the caller's session: a route that reaches ebx-connectors through {@code McpServers.connectors} is checked the same way. */
	private static Response preflight(final Session pSession, final Adaptation pDataset, final String... pServers)
	{
		final ObjectNode check = McpServers.preflight(pSession, pDataset, pServers);
		if (check.path("ready").asBoolean(false))
		{
			return null;
		}
		final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
		out.put("error", check.path("summary").asText("MCP server(s) not usable"));
		out.set("servers", check.path("servers"));
		return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(out.toString()).build();
	}

	/**
	 * Audit F-02: null when the caller may use the route {@code pRouteKey} ({@code "<METHOD> <@Path>"}, as {@code core.FactoryRights}
	 * lists it - a route not listed asks the administrator), otherwise the 403 answer naming the right and the default role. The
	 * few routes {@code FactoryRights#pipelineOperatorMay} lists also accept the pipeline launcher role.
	 */
	/**
	 * Second permissions pass (2026-10-14): the Factory right of the route ({@link #refused(String)}), then EBX's own permissions on the project the
	 * URL names, read with the caller's session ({@code ebx.UserView}, decided by {@code core.RecordGate}): the dataset closed to the caller, an unknown
	 * code and a project the caller cannot see all answer the same 404; a GET needs the project visible, any other method needs read-write access on
	 * its record (403 otherwise). What the route then runs (a job as the system user, a procedure with the caller's session) was allowed here.
	 */
	private Response refused(final String pRouteKey, final String pProjectCode)
	{
		final Response route = this.refused(pRouteKey);
		if (route != null)
		{
			return route;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return null; // the route answers 503 itself
		}
		final com.on.ebx.factory.ebx.UserView view = com.on.ebx.factory.ebx.UserView.of(this.sessionContext.getSession());
		final Adaptation project = FactoryResource.visibleProject(view, dataset, pProjectCode);
		final com.on.ebx.factory.core.RecordGate.Verdict verdict = com.on.ebx.factory.core.RecordGate.onRecord(
			pRouteKey.startsWith("GET ") ? FactoryRight.VIEW : FactoryRights.route(pRouteKey), project != null, project != null, view.recordWritable(project));
		return FactoryResource.answer(verdict, pProjectCode);
	}

	/** {@link #refused(String, String)} for a route that may create the project ({@code restore}): an absent project needs the creation right on {@code Project}. */
	private Response refusedCreateOrWrite(final String pRouteKey, final String pProjectCode)
	{
		final Response route = this.refused(pRouteKey);
		if (route != null)
		{
			return route;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return null;
		}
		final com.on.ebx.factory.ebx.UserView view = com.on.ebx.factory.ebx.UserView.of(this.sessionContext.getSession());
		final com.onwbp.adaptation.AdaptationTable projects = dataset.getTable(FactoryPaths.PROJECT);
		Adaptation existing = null;
		try
		{
			existing = projects.lookupAdaptationByPrimaryKey(projects.computePrimaryKey(new Object[] { pProjectCode }));
		}
		catch (final RuntimeException malformed)
		{
			existing = null;
		}
		if (existing == null)
		{
			// nothing to see yet: the caller must see the dataset and may create a project there
			return view.datasetVisible(dataset) && view.canCreate(projects) ? null
				: FactoryResource.problem(Response.Status.FORBIDDEN, "EBX does not let you create a project in this dataset");
		}
		final Adaptation project = FactoryResource.visibleProject(view, dataset, pProjectCode);
		return FactoryResource.answer(com.on.ebx.factory.core.RecordGate.onRecord(FactoryRights.route(pRouteKey), project != null, project != null,
			view.recordWritable(project)), pProjectCode);
	}

	/** {@link #refused(String, String)} for a route that names a project source: its project decides. */
	private Response refusedSource(final String pRouteKey, final String pProjectSourceId)
	{
		final Response route = this.refused(pRouteKey);
		if (route != null)
		{
			return route;
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return null;
		}
		final com.on.ebx.factory.ebx.UserView view = com.on.ebx.factory.ebx.UserView.of(this.sessionContext.getSession());
		final Adaptation source = view.datasetVisible(dataset) ? view.lookup(dataset.getTable(FactoryPaths.PROJECT_SOURCE), pProjectSourceId) : null;
		final String projectCode = source == null ? null : view.text(source, FactoryPaths.ProjectSource.PROJECT);
		final Adaptation project = source == null ? null : FactoryResource.visibleProject(view, dataset, projectCode);
		if (project == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Project source " + pProjectSourceId + " not found");
		}
		return FactoryResource.answer(com.on.ebx.factory.core.RecordGate.onRecord(FactoryRights.route(pRouteKey), true, true, view.recordWritable(project)), projectCode);
	}

	/** The project of that code when the caller may see the dataspace, the dataset, the table and the record; null otherwise (answered as not found). */
	private static Adaptation visibleProject(final com.on.ebx.factory.ebx.UserView pView, final Adaptation pDataset, final String pProjectCode)
	{
		if (pProjectCode == null || pProjectCode.trim().isEmpty() || !pView.datasetVisible(pDataset))
		{
			return null;
		}
		final com.onwbp.adaptation.AdaptationTable projects = pDataset.getTable(FactoryPaths.PROJECT);
		try
		{
			return pView.lookup(projects, projects.computePrimaryKey(new Object[] { pProjectCode }));
		}
		catch (final RuntimeException malformed)
		{
			return null;
		}
	}

	private static Response answer(final com.on.ebx.factory.core.RecordGate.Verdict pVerdict, final String pProjectCode)
	{
		switch (pVerdict)
		{
			case ALLOW:
				return null;
			case UNKNOWN:
				return FactoryResource.problem(Response.Status.NOT_FOUND, "Project " + pProjectCode + " not found");
			default:
				return FactoryResource.problem(Response.Status.FORBIDDEN, "EBX lets you read project " + pProjectCode + " but not modify it");
		}
	}

	/** A pure read of a shared reader with the caller's view ({@code UserView#scoped}): rows and fields the caller's EBX permissions show. */
	private <T> T asCaller(final com.on.ebx.factory.ebx.UserView.Read<T> pRead) throws OperationException
	{
		return com.on.ebx.factory.ebx.UserView.scopedRead(com.on.ebx.factory.ebx.UserView.of(this.sessionContext.getSession()), pRead);
	}

	/** Production review of 2026-11-12 (F3-08): the coded refusal while the governed agents are closed (409, the text names the JVM property), null when open. */
	private Response agentsClosed()
	{
		try
		{
			com.on.ebx.factory.ebx.FeatureSwitches.requireAgents();
			return null;
		}
		catch (final OperationException ex)
		{
			return FactoryResource.problem(Response.Status.CONFLICT, ex.getMessage());
		}
	}

	/**
	 * Production review of 2026-11-12 (F3-12): the checks of a route that names an agent, in order - the Factory right of the route, the switch of the agents
	 * (when {@code pNeedsOpen}), the agent visible to the caller, then the second permissions pass on the agent's PROJECT ({@link #refused(String, String)}):
	 * the project must be visible and writable with the caller's own EBX permissions. Null when the caller may go on.
	 */
	private Response refusedAgentRoute(final String pRouteKey, final String pAgentCode, final boolean pNeedsOpen)
	{
		final Response route = this.refused(pRouteKey);
		if (route != null)
		{
			return route;
		}
		if (pNeedsOpen)
		{
			final Response closed = this.agentsClosed();
			if (closed != null)
			{
				return closed;
			}
		}
		final Adaptation dataset = FactoryInstaller.dataset(this.sessionContext.getRepository());
		if (dataset == null)
		{
			return FactoryResource.problem(Response.Status.SERVICE_UNAVAILABLE, "The MDM Factory dataset is not installed");
		}
		final com.on.ebx.factory.ebx.UserView view = com.on.ebx.factory.ebx.UserView.of(this.sessionContext.getSession());
		final Adaptation definition = view.datasetVisible(dataset) ? view.lookup(dataset.getTable(FactoryPaths.AGENT_DEFINITION), pAgentCode) : null;
		if (definition == null)
		{
			return FactoryResource.problem(Response.Status.NOT_FOUND, "Agent " + pAgentCode + " not found");
		}
		return this.refused(pRouteKey, view.text(definition, FactoryPaths.AgentDefinition.PROJECT));
	}

	private Response refused(final String pRouteKey)
	{
		final Session session = this.sessionContext.getSession();
		final FactoryRight needed = FactoryRights.route(pRouteKey);
		final Adaptation dataset = FactoryRights.pipelineOperatorMay(pRouteKey) ? FactoryInstaller.dataset(this.sessionContext.getRepository()) : null;
		if (FactoryAccess.allows(session, dataset, pRouteKey, needed))
		{
			return null;
		}
		return FactoryResource.problem(Response.Status.FORBIDDEN, FactoryAccess.refusal(pRouteKey, needed));
	}

	/** X3-08: a JSON request body read, or the coded sentence that refuses it (see {@link com.on.ebx.factory.core.JsonLimits}). */
	static final class JsonBody
	{
		final JsonNode node;
		final String error;

		JsonBody(final JsonNode pNode, final String pError)
		{
			this.node = pNode;
			this.error = pError;
		}
	}

	/**
	 * X3-08: the one way the routes read a JSON body. A blank body is {@code pWhenBlank} (which may be null). A document that goes beyond a Jackson read bound (nesting depth,
	 * string, number or name length) or is not JSON answers a coded refusal ({@code MDMF-JSON-1} / {@code MDMF-JSON-2}) for the route to return as a 400, never a 500.
	 */
	static JsonBody readJsonBody(final String pBody, final JsonNode pWhenBlank)
	{
		if (pBody == null || pBody.trim().isEmpty())
		{
			return new JsonBody(pWhenBlank, null);
		}
		try
		{
			return new JsonBody(FactoryResource.MAPPER.readTree(pBody), null);
		}
		catch (final java.io.IOException ex)
		{
			return new JsonBody(null, com.on.ebx.factory.core.JsonLimits.refusal(ex, "the request body"));
		}
	}

	private static Response problem(final Response.Status pStatus, final String pMessage)
	{
		final ObjectNode out = FactoryResource.MAPPER.createObjectNode();
		// security review (2026-09-24): an error text can quote a remote answer - never return more than 1000 chars
		// wave 10 (F-23): a failure with no message (a NullPointerException) answers a sentence, never null
		final String message = pMessage == null || pMessage.trim().isEmpty() ? "The request failed and the engine gave no explanation. See the server log." : pMessage;
		out.put("error", message.length() <= 1000 ? message : message.substring(0, 1000) + "...");
		return Response.status(pStatus).entity(out.toString()).build();
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 1): the {@code 202} body for a route that enqueued a
	 * background job instead of running it on the request thread - {@code jobId}/{@code merged}/{@code
	 * position}/{@code started} exactly as the {@code *Run.start(...)} call already returns them (lot C1/C2,
	 * {@code FactoryJobQueue.Queued}), plus {@code statusUrl} - the existing {@code GET
	 * /projects/{code}/jobs} route (already polled by the wizard), which the caller filters to this
	 * {@code jobId} itself.
	 */
	private static Response accepted(final ObjectNode pStarted, final String pProjectCode)
	{
		pStarted.put("statusUrl", "/projects/" + pProjectCode + "/jobs");
		return Response.status(Response.Status.ACCEPTED).entity(pStarted.toString()).build();
	}
}
