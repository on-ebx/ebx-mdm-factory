# Transfer workflow — landing, staging, master and matching as one EBX Workflow

Written 2026-09-23 on branch `feature/transfer-workflow-beans` (worktree
`ebx-mdm-factory-transfer-workflow`). the product owner's request: "fais le bean de staging et le workflow
complet" - today landing load, staging load and master load are three manual actions (wizard buttons /
REST routes, `FactoryResource#loadLanding`/`loadStaging`/`loadMaster`), and matching already has its own
workflow bean (`docs/MATCHING.md` §18, `MatchingRunTaskBean`). This lot adds a bean per remaining loader
and chains all four into one workflow model, `MdmFactoryPipeline`.

## 1. What was built

### 1.1 Three new `ScriptTaskBean` classes, package `com.on.ebx.factory.workflow`

Same shape as `MatchingRunTaskBean` (§18 of `docs/MATCHING.md`): plain private `String` fields with
public getter/setter pairs (so a workflow model's `beanParams`/`outputMappingParams` can bind them),
`executeScript(ScriptTaskBeanContext)` that gets `Repository`/`Session` from the context, runs
`FactoryInstaller.ensure`/`FactoryInstaller.dataset`, and calls the SAME static loader method the
corresponding REST route already calls - no HTTP loopback, no permission check inside the bean (a
workflow step's effective caller identity is governed by EBX's own workflow permissions, not by a check
duplicated in Java - see each bean's own class Javadoc). An `OperationException` the loader itself
throws is let through as-is.

- **`LandingLoadTaskBean`** (`ebx-mdm-factory/src/main/java/com/on/ebx/factory/workflow/LandingLoadTaskBean.java`)
  - Inputs: `projectCode` (required), `projectSource` (optional - a single `ProjectSource.id`; blank
    loads EVERY `ProjectSource` of the project).
  - Runs the same MCP preflight the REST route runs first (`McpServers.preflight(dataset,
    McpServers.CONNECTORS)`), throwing before any load starts when it is not `ready`.
  - Every source list comes from a new public helper, `LandingFlows.projectSourceIds(Adaptation,
    String)` (`ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/LandingFlows.java`) - the same
    project/`ProjectSource` lookup `MatchingConfigurator#readTrustedSources` and
    `StagingSuggester#suggest` already inline for their own purposes, factored out and made `public`
    because this bean is the first caller from OUTSIDE the `ebx` package. No fourth inline copy of the
    query.
  - Calls `LandingFlows.load(session, dataset, sourceId)` once per source, collecting every result into
    one aggregate `resultJson` (`{project, sources[] (one LandingFlows#load result per source), failed,
    status}`) plus a plain-text `failed` output (a workflow condition compares strings, not JSON).
  - The outputs are written BEFORE a possible throw: a run with at least one failed table still leaves
    the real numbers in the data context, then an `OperationException` naming the failing source(s) is
    thrown so the workflow step itself lands `inError` instead of silently continuing to the staging
    load with a half-loaded landing zone.
  - Two pure, unit-tested static helpers: `failedCount(JsonNode)`, `sourceFailed(JsonNode)`.

- **`StagingLoadTaskBean`** (`.../workflow/StagingLoadTaskBean.java`)
  - Input: `projectCode` (required). Calls `StagingLoader.load(session, dataset, projectCode)`.
  - Outputs: `resultJson` (the loader's JSON as text), `rejected`/`written` (the loader's own totals as
    plain text), `status` (always `"DONE"` on a successful return - `StagingLoader` never reports
    `FAILED` itself: a row EBX would refuse is caught before the write and turned into a
    `TransferReject` row instead, see that class' own Javadoc "Rejects never reach staging"). No
    status-based throw here, unlike the other two loader beans - there is no `FAILED` status to react
    to.
  - One pure, unit-tested static helper: `countText(JsonNode, String)`.

- **`MasterLoadTaskBean`** (`.../workflow/MasterLoadTaskBean.java`)
  - Input: `projectCode` (required). Calls `MasterLoader.load(session, dataset, projectCode)`.
  - Outputs: `resultJson`, `rejected`/`written` (plain text), `status` (`"DONE"` or `"FAILED"` -
    `MasterLoader` CAN report `FAILED` on a normal return, `master.MasterLoadOutcome.isTotalFailure`:
    every row read ended up rejected, not merely "0 written"). Outputs are set first, then an
    `OperationException` is thrown when `status` is `FAILED`, so the workflow step lands `inError`
    instead of silently letting the matching step run against an empty/unchanged master zone.
  - Two pure, unit-tested static helpers: `countText(JsonNode, String)`, `isFailed(JsonNode)`.

Tests: `LandingLoadTaskBeanTest`, `StagingLoadTaskBeanTest`, `MasterLoadTaskBeanTest`
(`ebx-mdm-factory/src/test/java/com/on/ebx/factory/workflow/`), same JUnit-free `main`-throws-on-failure
convention as `MatchingRunTaskBeanTest`, discovered and run automatically by `tools/test_local.sh` (every
`*Test.java` under `src/test/java`). They cover ONLY the pure static helpers - `executeScript` itself
needs a live EBX `Session`/`Repository` and is out of reach for local tests, same discipline as every
other EBX-dependent class in this module.

### 1.2 `module.xml`

`ebx-mdm-factory/src/main/webapp/WEB-INF/ebx/module.xml`'s `<beans>` block (already holding
`MatchingRunTaskBean`, see `docs/MATCHING.md` §18.3) gains three more entries:

```xml
<beans>
	<bean className="com.on.ebx.factory.workflow.MatchingRunTaskBean" />
	<bean className="com.on.ebx.factory.workflow.LandingLoadTaskBean" />
	<bean className="com.on.ebx.factory.workflow.StagingLoadTaskBean" />
	<bean className="com.on.ebx.factory.workflow.MasterLoadTaskBean" />
</beans>
```

This is required, not optional - `docs/MATCHING.md` §18.4 confirmed live that a `<beans>` declaration is
what lets the workflow engine's own classloader resolve a module `scriptTask` bean at all
(`workflow_discovery_list(type='scriptTaskClasses')` only lists a bean once it is both compiled into the
deployed module AND declared here). **Needs a Tomcat restart to take effect** - `module.xml` is only read
at startup (CLAUDE.md §9/§7).

### 1.3 The workflow model, `tools/workflow/MdmFactoryPipeline.json`

A versioned file in the canonical JSON `workflow_model_import` takes (`{model, dataContext, nodes,
links}`). Ten nodes, `startNodeId: 1`:

```
 1  condition   ConditionIsValueEqualsTo(loadLanding, "true")
    |-- ifTrue  --> 2                    |-- ifFalse --> 3
 2  scriptTask  LandingLoadTaskBean(projectCode)
    --> resultJson->landingResult, failed->landingFailed --> 3
 3  scriptTask  StagingLoadTaskBean(projectCode, scope=stagingScope)
    --> resultJson->stagingResult, rejected->stagingRejected, written->stagingWritten --> 4
 4  condition   ConditionIsValueEqualsTo(stagingRejected, "0")
    |-- ifTrue  --> 8                    |-- ifFalse --> 5
 5  condition   ConditionIsValueEqualsTo(reviewRejects, "true")
    |-- ifTrue  --> 6                    |-- ifFalse --> 8
 6  userTask    "Review the transfer rejects" (ebx-root-1.0@default on /root/TransferReject,
                filtered to this project + stage STAGING)
    --> 7
 7  condition   ConditionIsLastUserTaskAccepted
    |-- ifTrue  --> 8                    |-- ifFalse --> 10
 8  scriptTask  MasterLoadTaskBean(projectCode)
    --> resultJson->masterResult, rejected->masterRejected, written->masterWritten --> 9
 9  scriptTask  MatchingRunTaskBean(projectCode, scope, maxWaitMs)
    --> resultJson->matchingResult                          (no outgoing link: end of workflow)
10  scriptTask  ScriptTaskUpdateWorkflowLabel (library bean, "stopped at review")
                                                              (no outgoing link: end of workflow)
```

Variables (`dataContext.variables`): `projectCode` (in), `loadLanding` (in, default `"true"`),
`reviewRejects` (in, default `"true"`), `scope` (in, default `"incremental"` - the MATCHING run's own
scope, node 9), `stagingScope` (in, default `"new"` - the STAGING transfer's own scope, node 3; added
2026-09-23, see §7 below, kept a separate variable from `scope` on purpose: the two steps' scopes are
independent choices), `maxWaitMs` (in, no default), and nine `out` variables with NO `defaultValue`
(CLAUDE.md §7: an `out` variable with a `defaultValue` refuses to launch) - `landingResult`,
`landingFailed`, `stagingResult`, `stagingRejected`, `stagingWritten`, `masterResult`,
`masterRejected`, `masterWritten`, `matchingResult`.

**Node 10 is an addition beyond the literal step list, and here is why.** The brief for this lot left
node 7's `ifFalse` outcome as "no node (terminates)", flagging that a condition node might not accept a
missing `ifFalse` link. Live `workflow_model_schema` (read this lot, see §2 below) settles it explicitly:
*"A condition also needs both an `ifTrue` and an `ifFalse` outgoing link, which import already refuses
without."* So `ifFalse` needs a real target. Rather than inventing a new bean for a no-op step, node 10
reuses a pattern read live off an already-published model in this same repository,
`PosSuspectReview` (`workflow_model_get`, 2026-09-23): a built-in library bean,
`ScriptTaskUpdateWorkflowLabel` (`scriptMode: "library"`, `scriptLibrary: true`, `beanParams`
`locale`/`label`), with no outgoing link of its own - which is how EBX Workflow terminates a branch at
all: *"There is no 'end' node kind. To terminate the workflow at a node, simply do not create any
outgoing link from it."* (same source). Node 10 just relabels the instance to say the pipeline stopped
at the review, for the "who launched what and how did it end" columns in a monitoring view.

**`profiles` alongside `offeredToProfiles` on node 6, beyond the brief's literal `offeredToProfiles`
alone.** Live `workflow_model_schema`'s own advice: *"Participants are required too, and
`offeredToProfiles` is the field that satisfies them... Set `offeredToProfiles`; add `profiles` as well
when the task is also assigned."* The real `CompanyCreationApproval`/`PosSuspectReview` models (read
live) both set the same profile on both fields for every `userTask`. Node 6 does the same:
`profiles: ["Badministrator"]`, `offeredToProfiles: ["Badministrator"]`.

**`branch`/`instance`/`xpath` of node 6, and where they were read.** Read live, 2026-09-23:
- `find_dataspace(hint: "MdmFactory")` (`data-access` MCP) confirmed the dataspace key is exactly
  `MdmFactory` (`info.name`).
- `list_datasets(dataspace: "MdmFactory")` confirmed the dataset name is exactly `MdmFactory`
  (`{"label":"MDM Factory","name":"MdmFactory"}`).
- These match `docs/PROJECT-EXPLORER.md`/`docs/DELIVERY.md`/`docs/RECORD-LINEAGE.md`'s own prior
  confirmations (`branch=MdmFactory&instance=MdmFactory`, `{"Dataspace":"BMdmFactory","Dataset":
  "MdmFactory"}`) - `FactoryPaths.DATASPACE`/`DATASET` default to `"MdmFactory"` absent a
  `-Debx.factory.dataspace`/`-Debx.factory.dataset` override, and this instance runs with the default.
- `describe_table(table: "/root/TransferReject", dataspace: "MdmFactory", dataset: "MdmFactory")`
  confirmed: `project` is a `foreignKey` to `/root/Project` (whose own primary key is `code` -
  `tools/build_xsd.py`'s `T('Project', ..., ['code'], ...)` - so a `TransferReject.project` value IS the
  project code, and `./project='${projectCode}'` is correct, not a bug), `stage` is a `string` with a
  static enumeration; the live value `StagingLoader` writes is `"STAGING"` (read straight from
  `StagingLoader.REJECT_STAGE`, `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/StagingLoader.java:179`).
  So: `xpath = /root/TransferReject[./project='${projectCode}' and ./stage='STAGING']`, `branch =
  MdmFactory`, `instance = MdmFactory`.
- `workflow_discovery_service_params(serviceId: "ebx-root-1.0@default")` (live) listed `service`,
  `branch`, `version`, `instance`, `xpath`, `viewPublication`, `scope`, `disableAutoComplete`,
  `firstCallDisplay`, `trackingInfo` - all `direction: "in"`. Node 6 sets `branch`, `instance`, `xpath`
  and `firstCallDisplay: "view"` (the predicate can match more than one reject row, so `"view"` forces
  the tabular view rather than `"record"`, which is for a single-record predicate - see the parameter's
  own description).

## 2. Live verification this lot (unlike the rest of the deliverables - see §5)

MCP connectivity was down for the first part of this session (every tool, including read-only
`data-access`/`data-model`/`ebx-scripting` probes, answered "Unable to connect") and came back partway
through. Once it did, the model JSON above was checked against the REAL live schema and REAL published
examples, not guessed:

- `workflow_model_schema` - the full vocabulary (node kinds, link roles, condition/scriptTask/userTask
  fields, the `BUILT_IN_REQUIRES_LIBRARY_MODE` rule, the mandatory-fields table).
- `workflow_model_get(modelName: "MdmFactoryMatchingRun")` - this module's own already-published model,
  confirming `scriptMode: "specific"` + the module-prefixed `scriptClass` convention (§18.4 of
  `docs/MATCHING.md`) directly off a live record, not re-derived from the doc's prose.
- `workflow_model_get(modelName: "CompanyCreationApproval")` and `workflow_model_get(modelName:
  "PosSuspectReview")` - two other already-published models on this instance, read for real `userTask`
  and terminal-node shapes (see node 10 and the `profiles`/`offeredToProfiles` note above).
- `workflow_discovery_list(type: "conditionClasses" | "services" | "profiles" | "scriptTaskClasses")`
  and `workflow_discovery_service_params(serviceId: "ebx-root-1.0@default")`.
- `find_dataspace`/`list_datasets`/`describe_table` (`data-access` MCP) for the `branch`/`instance`/
  `xpath` values above.
- **`workflow_model_check_json` on the FULL final JSON came back `"valid": true`, with exactly four
  warnings, all `BEAN_CLASS_NOT_RESOLVED`** (on `LandingLoadTaskBean`, `StagingLoadTaskBean`,
  `MasterLoadTaskBean`, and - notably - `MatchingRunTaskBean` too, even though that one IS already
  deployed: the check tool's own classloader does not resolve any customer-module bean, deployed or
  not, so this warning is unconditionally benign for a module bean, exactly as this lot's brief
  anticipated). No other error or warning of any kind - no unreachable node, no missing link, no
  malformed reference.

Only READ-only tools were called this lot: `workflow_model_import`, `workflow_publication_publish`,
`workflow_execution_launch` and every other write tool were deliberately never invoked, per this lot's
own constraint (the coordinator imports and publishes).

## 3. How to import, publish and launch (none of this was done this lot - see §5)

1. **Deploy the code first.** `module.xml`'s new `<beans>` entries need a Tomcat restart (§1.2) before
   the three new beans exist on the workflow engine's classloader - importing the model before that
   restart is harmless (the check above proves the JSON is structurally valid on its own), but
   publishing it and then launching an instance would fail at the `LandingLoadTaskBean`/
   `StagingLoadTaskBean`/`MasterLoadTaskBean` steps with the same class-not-found failure CLAUDE.md §7
   describes for an undeclared `wait` bean.
2. **Import**: `workflow_model_import` with the exact content of `tools/workflow/MdmFactoryPipeline.json`
   (`modelMap` = the file parsed as JSON).
3. **Validate for real**: `workflow_model_validation_generate` - the server-side check that actually
   resolves the module beans (unlike `workflow_model_check_json`, which never does, deployed or not -
   see §2). Expect it to report the three new beans resolved once the restart in step 1 has happened;
   before that, expect the same class-not-found shape `docs/MATCHING.md` §18.4 saw for
   `MatchingRunTaskBean` before its own first restart.
4. **Publish**: `workflow_publication_publish(modelName: "MdmFactoryPipeline", dryRun: true)` first
   (`wouldPublish: true` expected), then for real. Publication name `MdmFactoryPipeline` (same as the
   model name, following `MdmFactoryMatchingRun`'s own convention).

Three ways to launch it, same menu `docs/MATCHING.md` §18 documents for the matching-only model:

- **MCP**: `workflow_execution_launch(publicationName: "MdmFactoryPipeline", inputParameters:
  {projectCode: "<code>", loadLanding: "true", reviewRejects: "true", scope: "incremental"})`
  (`maxWaitMs` omitted falls back to `MatchingConfigurator.DEFAULT_MAX_WAIT_MS`, same as
  `MatchingRunTaskBean` alone).
- **Workflow REST API**: `POST /ebx-dataservices/rest/workflow/v1/publications/MdmFactoryPipeline/
  workflows` with a JSON body carrying `dataContext` - works for any authenticated caller, no MCP
  session needed (§18.4's own measurement: the endpoint launches on any well-formed body, so the caller
  is responsible for supplying real values, not the endpoint).
- **Task Scheduler**: a row in the `workflow_launchers` table (Data Services-writable, CLAUDE.md §7's
  "Workflow launchers are writable over Data Services" bullet) naming publication
  `MdmFactoryPipeline`, then a Scheduler REST API schedule (§2 of CLAUDE.md, `taskScheduler` bullet)
  running the built-in task `SchedulerWorkflowLaunchTask` (`workflowLauncherName` parameter) on a cron
  expression - e.g. a nightly full pipeline run for a project once its source connectors are stable.

## 4. Permission model

Same discipline as `MatchingRunTaskBean` (`docs/MATCHING.md` §18.2): **no bean in this chain checks who
launched it.** The model's `permissionsOnInstances` (`create`/`administrate`/`view`/
`agentAccessForAdmin`/`agentAccessForInbox`, all `Badministrator`) is the only gate on who may launch or
administer an instance of `MdmFactoryPipeline` - not a check duplicated in Java. What actually
determines whether a launched instance can DO anything is the **workflow engine's own session**, which
each bean gets from `ScriptTaskBeanContext.getSession()`/`getRepository()`: that session must be allowed
to write into the project's landing, staging and master dataspaces (the same write rights
`FactoryResource#loadLanding`/`loadStaging`/`loadMaster` require of the calling user today, since these
beans call the exact same loader methods). A workflow instance launched by a user or a scheduled task
whose EBX session lacks write access to one of those dataspaces will fail at that step with EBX's own
permission error, not a clean "not allowed" from the bean.

## 5. What was NOT verified this lot

- **The three new beans were never compiled into a deployed module and never run against a live EBX
  instance.** `tools/test_local.sh` is green (compiles against the real `ebx-lib.jar`, runs every pure
  unit test) - that is the only verification `executeScript` itself has had. No Tomcat restart, no
  `workflow_model_import`/`workflow_model_validation_generate`/`workflow_publication_publish`/
  `workflow_execution_launch` was performed this lot (deliberately, per this lot's own constraint: the
  coordinator does that).
- **The `MdmFactoryPipeline.json` model itself WAS checked live** (§2) - `workflow_model_check_json`
  came back clean - but that check never resolves a module bean's parameters (§2's own note on the
  `BEAN_CLASS_NOT_RESOLVED` warning), so the exact bean property NAMES used in `beanParams`/
  `outputMappingParams` (`projectCode`, `scope`, `maxWaitMs`, `resultJson`, `failed`, `rejected`,
  `written`) are only verified by reading the beans' own getter/setter pairs (§1.1) - they were never
  checked by `workflow_model_validation_generate` against the deployed classes.
- **Node 10's termination-by-no-outgoing-link and the `ScriptTaskUpdateWorkflowLabel` choice are modeled
  on a live, already-published example (`PosSuspectReview`), but this exact node was never itself
  published or run.**
- **Known limitation, carried over from `docs/MASTER-ZONE.md`/this module's own design, not new to this
  lot - FIXED 2026-09-23, see §7 below.** ~~`StagingLoader` has no `_status = NEW` filter on the landing
  rows it reads - every run re-reads and re-transforms EVERY landing row of every feed, every time (see
  `StagingLoader`'s own class Javadoc, "No change detection").~~ A `MdmFactoryPipeline` instance launched
  frequently (e.g. an hourly Task Scheduler trigger) pays a full-table staging pass on every run, not an
  incremental one - fine for the volumes seen on this instance so far (a few hundred rows), worth revisiting before wiring a tight
  schedule onto a real-sized landing zone.

## 6. Deployed and run live, 2026-09-23 afternoon (coordinator)

Everything section 5 lists as "not verified" was then done on the Test instance, in this order:

1. `main` 06b9b7e installed with `tools/install.sh` (881 classes) while Tomcat Test was stopped; Tomcat restarted by
   the product owner. `workflow_discovery_list(type='scriptTaskClasses')` then listed the three new beans as
   `ebx-mdm-factory@com.on.ebx.factory.workflow.{LandingLoad,StagingLoad,MasterLoad}TaskBean` - the `<beans>`
   declarations of section 1.2 work exactly as for `MatchingRunTaskBean`.
2. `workflow_model_import(MdmFactoryPipeline)` with the file of section 1.3 (one label change first: node 4 is
   "No staging reject?", its true branch being the no-reject one). `workflow_model_validation_generate` (the real
   server-side check, beans resolved): `errorCount: 0, warningCount: 0, valid: true`. Published as
   `MdmFactoryPipeline` (dry run `wouldPublish: true`, then `publicationMode: NEW`).
3. **Instance 562 launched with `projectCode=Companies, loadLanding=false, reviewRejects=true, scope=incremental`**
   (`workflow_execution_launch`), completed in 9 s, `inError: false`, 5 steps, no work item. Data context read back
   over `GET /ebx-dataservices/rest/workflow/v1/publications/MdmFactoryPipeline/workflows` (the product owner's own check
   method for instance 559, `docs/MATCHING.md` section 18.4):
   - node 1 false (`loadLanding=false`), landing step skipped, `landingResult` absent from the data context;
   - `stagingResult`: read 303 / written 303 / rejected 0 (Supplier 55, Account 52, Contact 89, Company 107),
     3.3 s, `qualityRulesChecked 3`, `stagingRejected="0"`, `stagingWritten="303"` - the output mapping of a
     STRING count works and feeds node 4;
   - node 4 true (`"0" = "0"`): the review task (nodes 5-7) was bypassed, as designed;
   - `masterResult`: read 196 / written 192 / rejected 0, 410 ms (Company 103 updated, Contact 89 updated,
     `missingFields: [emailValid]` on Contact), `masterRejected="0"`, `masterWritten="192"`;
   - `matchingResult`: `ran: [Company]`, `DONE`, 3.3 s, `notApplied: [Contact: plan is PROPOSED]`,
     `goldenKeyRefresh unchanged 107` - the same answer the standalone `MdmFactoryMatchingRun` gives.
   - `PipelineStep` rows 189 (STAGING_LOAD), 190 (MASTER_LOAD), 191 (MATCHING_RUN) were written by the loaders
     under the workflow engine's session, all `DONE` - the "permission model" of section 4 holds for the built-in
     administrator that launched the instance.

**Still not exercised live:** the landing step (node 2, `LandingLoadTaskBean` - it would re-run `connectors_sync`
against Snowflake, deliberately left out of this first run) and the reject-review branch (nodes 5-7, node 10 - it
needs a staging load that rejects at least one row, and Companies rejected none). Both are the next things to
look at the first time a run carries `loadLanding=true` or a real staging reject.

## 7. 2026-09-23 (later): incremental staging transfer + library-mode beans

Two additions to this same branch (`feature/incremental-staging`), the product owner's own two-part brief.

### 7.1 The staging transfer is now incremental (`docs/INCREMENTAL-TRANSFER.md`)

`StagingLoader.load` gained a `scope` (`new`/`all` - `transfer.IncrementalTransfer`); §5's "known
limitation" above is fixed. Node 3 (`StagingLoadTaskBean`) gets a `scope` `beanParams` entry bound to a
NEW data-context variable, `stagingScope` (`direction: in`, default `"new"`) - kept separate from node
9's own `scope` variable (the matching run's scope): the two steps' scopes are independent choices, and
collapsing them into one variable would have forced a caller who wants an incremental staging transfer
but a full matching re-run (or vice versa) to pick one. `docs/INCREMENTAL-TRANSFER.md` is the full design
note; the short version for this pipeline: a recurring/scheduled launch leaves `stagingScope` at its
default (`new`) and only pays for the landing rows actually re-received since the last run, skipping the
staging write entirely for one whose content did not change; a launch right after a mapping/cleaning-step/
derived-field change should pass `stagingScope=all` to force every staging row to be recomputed.

### 7.2 The four module beans are now LIBRARY beans, not `specific`

the product owner's request: the workflow designer should show a typed, labelled input/output form for each of
these four steps instead of free name/value pairs - exactly what `ebx-connectors`'s and
`ebx-data-catalog`'s own module beans already give a workflow author (read live, both modules'
`module.xml`, 2026-09-23: each `<bean className="...">` carries a `<documentation xml:lang="en-US"/>
xml:lang="fr-FR">` (label + description) and a `<properties>` block, one `<property name="..."
input="true"|output="true">` per setter/getter used, each with its own bilingual `<documentation><label>`
- the exact `BeanDeclarationType`/`PropertyType` shape of `module_2.4.xsd`).

**`module.xml`** (`ebx-mdm-factory/src/main/webapp/WEB-INF/ebx/module.xml`) now documents all four beans
this way. Every property name matches the Java bean property (getter/setter pair) exactly:

- `MatchingRunTaskBean` - in: `projectCode`, `scope`, `maxWaitMs`; out: `resultJson`.
- `LandingLoadTaskBean` - in: `projectCode`, `projectSource`; out: `resultJson`, `failed`.
- `StagingLoadTaskBean` - in: `projectCode`, `scope` (NEW this lot, §7.1); out: `resultJson`, `rejected`,
  `written`, `unchanged` (NEW this lot, §7.1).
- `MasterLoadTaskBean` - in: `projectCode`; out: `resultJson`, `rejected`, `written`, `status`.

`xmllint --noout` passes (plain well-formedness, the same check `tools/test_local.sh` would run on any
XML file it touched - `module.xml` itself is not part of that script's own checks, so this was run by
hand this lot). A schema-validation attempt against the instance's own `module_2.4.xsd` reports one
error, on the root `<module>` element's `xsi:schemaLocation` attribute - confirmed to be a pre-existing,
unrelated quirk of that XSD file itself (not something this change introduced): the SAME error reproduces
verbatim on the already-deployed, known-good `ebx-connectors/.../module.xml`, copied here as the
reference shape, when checked against the identical schema file. Every element below the root validates
against `BeanDeclarationType`/`PropertyType`.

**`tools/workflow/MdmFactoryPipeline.json`**: nodes 2, 3, 8 and 9 (the four module-bean script tasks)
switch from `"scriptMode": "specific"` to `"scriptMode": "library", "scriptLibrary": true` -
`scriptClass`, `beanParams` and `outputMappingParams` all unchanged otherwise. This is the same
`scriptMode`/`scriptLibrary` combination CLAUDE.md §7 describes for a BUILT-IN library bean
(`ebx-root-1.0@...`) - the difference here is the `scriptClass` names a MODULE bean instead
(`ebx-mdm-factory@com.on.ebx.factory.workflow.<Bean>`), which is exactly the shape read live off the
already-published `CatalogAccessRequest` model's own `AccessRequestStatusBean` reference
(`ebx-data-catalog@com.on.ebx.catalog.ebx.workflow.AccessRequestStatusBean`, `workflow_model_get`,
2026-09-23) - a module `scriptTask` bean can run in library mode exactly like a built-in one; `specific`
(what this file used until now) is meant for a genuinely one-off bean invocation, and was never actually
required here.

**Re-checked live**, same tool and same benign result as before the switch: `workflow_model_check_json`
on the full updated JSON answered `valid: true`, with exactly FOUR warnings, all `BEAN_CLASS_NOT_RESOLVED`
- one per module bean (`LandingLoadTaskBean`, `StagingLoadTaskBean`, `MasterLoadTaskBean`,
`MatchingRunTaskBean`), same message as §2's original check ("is not on the workflow class loader...
This is expected for a bean deployed in a customer module"). No other error or warning - switching
`scriptMode` did not change what this particular check can or cannot verify (it never resolved a module
bean's classloader either way), and it confirms the JSON stays structurally valid after the change.

**Why library mode, concretely:** with `specific`, the workflow designer UI shows each `beanParams`/
`outputMappingParams` entry as a free-text name/value pair the model author must get right by memory (the
exact hazard CLAUDE.md §7 already flags for a built-in bean: "`beanParams` are never validated by the
tooling... the only reliable way to get correct setter names... is `javap`"). With `library` +
`module.xml`'s new `<properties>` declarations, the designer instead reads the module's own declared
input/output list - typed, labelled in both locales, generated straight from the same getter/setter pairs
the Java compiler already checks - the same authoring experience `ebx-connectors`'s
`ConnectorScriptTaskBean`/`ProductPublishTaskBean` and `ebx-data-catalog`'s beans already give their own
workflow models.

**Needs a Tomcat restart, same reason as §1.2/§3 step 1 of this doc** - `module.xml` (the NEW
`<documentation>`/`<properties>` blocks) is only read at server startup; none of this reaches the
designer UI, and `workflow_model_validation_generate`'s own bean-parameter check stays unable to resolve
these classes, until after that restart. Nothing in this section was imported, published or launched live
this lot (same constraint as §1/§5 above - the coordinator does that); §6's already-deployed instance
(562) was launched BEFORE this section's changes and is unaffected by them.

## 8. 2026-09-24: no agent account left in the pipeline (in-JVM Connectors bridge)

Question behind it (the product owner, production under SSO): the pipeline is data execution, so it should need no agent.
Measured in the code: staging (`StagingLoader`), master (`MasterLoader`) and matching (`MameMatchingApi`) were
already plain Java in this JVM; only the landing load reached `connectors_sync` over HTTP with the stored agent
account (`LandingFlows.load`, preceded by an HTTP preflight in `LandingLoadTaskBean`).

ebx-connectors' own workflow bean (`ConnectorScriptTaskBean`) could not replace it: one target table per call,
one `inputs` string shared by every mapping (each landing table has its own `loadId`), and a workflow variable
holds ~180 characters, so no load plan can travel between steps.

What was built instead (ebx-connectors branch `feature/in-jvm-mcp`, this repo `feature/connectors-in-jvm`):

- **ebx-connectors** registers the platform MBean `com.on.ebx.connectors:type=InJvmMcp` at repository startup
  (unregistered at shutdown and before the module is unregistered). Its one operation,
  `handle(Object session, String message)`, answers a JSON-RPC message through the same `McpEndpoint.dispatch`
  as `POST /ebx-connectors/rest/mcp`, with the CALLER's EBX `Session`. The EBX classes come from Tomcat's common
  class loader, so the Session object crosses webapps as is; a remote JMX client cannot send one.
- **on-ebx-agent.jar**: `McpClient` takes an injectable `Transport` in place of HTTP (same protocol, same answers).
- **This module**: `ebx.ConnectorsBridge` (the transport over the MBean) and `McpServers.connectors(session,
  factory)`, which goes through the bridge when it is registered and a session is at hand, and falls back to HTTP
  with the agent account otherwise (an older ebx-connectors). `LandingFlows.generate`/`load` use it and record
  `transport: in-jvm|http` in the step detail; `LandingLoadTaskBean` and the two landing REST routes run their
  preflight with the session (`McpServers.preflight(session, factory, CONNECTORS)`).
- **Same day, second lot** (`feature/connectors-bridge-design-time`): every other ebx-connectors call went the
  same way - source onboarding (`SourceOnboarder`: connections, facts, catalog, scan, onboard-all, and its
  preflight), landing preview (`LandingPreview.sample`, so the key study too), mapping deletion
  (`GeneratedObjectDeleter.deleteMapping`, from project deletion and step reset) and the capability inventory
  (`CapabilityLlmSuggester.buildServiceCatalogue`), each now taking the caller's `Session`; the REST routes of
  onboarding, deletion and step reset preflight with it. No factory code reaches ebx-connectors over HTTP any
  more except the fallback. The connection test's ebx-connectors line is tested through `McpServers.connectors`
  with the person's session and says "in-JVM bridge, runs as you" (the separate "(in-JVM)" line is gone); the
  agent account is now only for data-model, match-merge and scripting.

Consequence: the whole `MdmFactoryPipeline` runs as the workflow's own session, with no password and no HTTP, and
the wizard's landing buttons run as the person who clicks.

**Deployment order**: this module now compiles against the new `McpClient.Transport`, so `tools/install.sh` must
copy an `on-ebx-agent.jar` built from ebx-connectors' merged `feature/in-jvm-mcp`
(`ON_EBX_AGENT_JAR=<ebx-connectors>/dist/on-ebx-agent.jar`), and ebx-connectors must be deployed with the bridge
for the in-JVM path to be taken (without it, the HTTP fallback keeps working).

## 9. 2026-09-24: EBX's own MCP servers as the session's user (lot 3)

Measured first with `GET /connection/auth-probe` (`ebx.AuthProbe`, administrators, read-only): data-model,
match-merge and scripting accept an `EBXUID` cookie minted in this JVM for a user by EBX's own
`JsonWebTokenHandler` (`createToken(user, activitySeconds, sessionId, trustSeconds, sessionSeconds)` +
`formatToken`, internal API found by `javap`), provided the session id is not null (null answers 500);
`Authorization: Bearer` is 401 (IdP tokens only), `Authorization: EBX` 500; forwarding a browser's own cookies
also works. A token minted for a user that does not exist is accepted too (authentication trusts the signed token),
so a tool list proves nothing about identity; identity was proven with a temporary `?asUser=<login>` mode: with
data-model closed to `[all profiles]` in Server permissions (administrator kept), a token for the non-admin `user`
got 0 tools there while the admin's kept 29. `asUser` was then removed (an impersonation surface), and
`SessionToken` only mints for a session's own user.

`McpServers.client(session, factory, server)` now reaches the EBX servers with such a cookie (`ebx.SessionToken`,
one-hour validity, a fresh random session id, loopback only) and falls back to the agent account when there is no
session or the token cannot be minted (the internal class gone or changed after an upgrade). Every factory call site
passes its session; the delete and step-reset plans, the capability script inventory and the matching table hint
got one threaded in. The connection test reports each line's mode (`in-jvm`, `session`, `agent`). Consequence: with
a session, no factory call uses the agent account any more; it stays configured only as the fallback. Not verified
yet: an SSO directory's view of a made-up session id (`DirectoryHandler.isUserSessionValid`).

## 10. 2026-09-24: security hardening of the credentials

After lot 3, three gaps were closed (`ebx.CredentialPolicy`):

- **No silent escalation to the agent account.** A call that cannot run as its caller (no session, ebx-connectors'
  bridge not registered, no session token mintable after an upgrade) would silently switch to the agent account,
  often more privileged than the caller. New setting `agentFallback`: `AUTO` (default - allowed on a DEVELOPMENT
  instance, forbidden on a TARGET), `ALLOWED`, `FORBIDDEN` (the call fails instead). An allowed fallback is written to
  the EBX kernel log (WARN, "called with the AGENT ACCOUNT"); the connection test shows each line's mode and the
  policy in force. `McpServers` and `CatalogLineageDeclarer` are the only two places a fallback can happen.
- **No credential in clear text across the network.** A session token or the agent password is sent only over HTTPS,
  or over HTTP to an address of this machine (loopback or one of its own interfaces); an `http://` internal address
  naming another machine is refused before anything is sent.
- **The lineage declaration runs as its caller too**: `ebx-data-catalog`'s REST API accepts the EBXUID cookie (200
  with, 401 without, measured the same day), so `CatalogLineageDeclarer` sends a session token, the agent account
  only through the policy.
- **`/connection/auth-probe` is off by default**: 404 unless the JVM runs with `-Debx.factory.authProbe=true`.

## 11. 2026-09-24: who launched it (pipeline audit and launcher gate)

A script task runs with a system-user session that bypasses every permission (EBX Javadoc,
`ProcessExecutionInfoContext.getSession`: "a system-user session (not an authenticated user session)", a `Procedure`
run with it is equivalent to `setAllPrivileges`). Two consequences handled here:

- **Gate** (`workflow.PipelineLauncher`): every factory bean first checks that the instance's CREATOR
  (`getProcessInstanceCreator()`, recorded by the engine, not a data-context variable) holds the role of the new
  setting `pipelineLauncherRole` (a specific EBX role; empty = administrators), through
  `DirectoryHandler.isUserInRole`. Otherwise the step fails with a message naming the user and the role. This holds
  whatever the publication's launch permissions say - restrict those too.
- **Audit** (`ebx.Actor` + two new `PipelineStep` fields): `startedBy` (the person in the wizard or behind a REST
  route, the workflow's launcher for a bean) and `via` (`session`, or `workflow <instance key>`), written by
  `PipelineSteps.begin` for every step.

## 12. 2026-10-12: `masterScope` (lot M-INC, `docs/VOLUME.md` "Lot M-INC")

`MasterLoadTaskBean` (node 8) gets a `scope` bean property bound to a new `MdmFactoryPipeline` variable, `masterScope` (`direction: in`, default `"changed"`): `changed` reads only the staging rows the journal of changed
staging rows recorded since the last successful master load and loads an entity in full - the step says why - when the journal cannot stand for a full read (first load, triggers missing, plan changed, ...); `full` reads
every staging row. Kept apart from `stagingScope` and `scope`: a staging scope `all` that rewrites rows is journaled by EBX like any write, so it needs no matching master scope. **The published model must be republished
for the variable to exist**; until then the bean's `scope` is null and the instance default applies (`-Dmdm.factory.masterLoad.defaultScope`, `full`).
