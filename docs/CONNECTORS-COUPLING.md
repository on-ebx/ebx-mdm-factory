# How the MDM Factory is coupled to ebx-connectors (audit F-26, wave 11)

Read at the head of `feat/w11-connectors-contract` (the file:line references are those of that branch; the audit's of
2026-10-05 had moved), updated for wave 12 (`feat/w12-agent-via-contract`). Connectors offers a versioned in-JVM contract
`ebx-connectors.sibling`: **1.0.0** since wave 11, **1.1.0** since wave 12 (`llm.chat`, the connection's `maxTokens`, `chat`
in `llm.connectors`; `ebx-connectors/docs/CONTRACT.md`; platform MBean `com.on.ebx.connectors:type=Contract`). This module speaks
it through **one class**, `ebx/ConnectorsContractClient` (and, for the agent loop's turns, `ContractLlmChat` of the agent library,
chosen by `ebx/AgentRoute`), and falls back to the way it always worked when the contract is absent or too old.

## 1. What changed, in one table

| What the module did | Where | Now |
|---|---|---|
| Read the `llm` connector row, its properties and **decrypted its API key** to ask one question | `AgentRuntime.testLlm` (connection test) | `llm.ask` (`AgentRuntime.testLlm(Session, ...)`); the key is not read |
| Read every `llm` connector row for the picker | `AgentRuntime.llmConnectors` (screen "Connection") | `llm.connectors` (`AgentRuntime.llmConnectors(Repository, Session)`) |
| Asked the model for a constraint message / an explanation through a one-tool agent run (decrypting the key) | `CapabilityApplier.runLlmMessage`, `runLlmExplanation` | `AgentRuntime.ask` = `llm.ask` (one prompt, JSON answer, up to 3 asks with the reason a value was refused) |
| Wrote the landing flow of a table with `connectors_mapping_upsert` | `LandingFlows.upsertFlowOnce` | `flows.put` (key = the flow's own label), except a rename of the table's own mapping (section 4) |
| Wrote the outbound mapping of a distribution table with `connectors_mapping_upsert`, read its rules with `connectors_mapping_get` | `DistributionConfigurator.upsertMapping`, `readExistingRules` | `flows.put`, `flows.get` |
| Wrote the data-product mappings with `connectors_mapping_upsert` | `DataProductPublication.writeMappings` | `flows.put` |
| Read and decrypted the key for the multi-turn agent loop | `AgentRuntime.run` | **wave 12: `llm.chat`** (contract 1.1.0) through `ContractLlmChat`, chosen by `AgentRoute.resolve`; the key read (`AgentLlmConnector.load`) is now only the fallback (section 3) |

Which way a call goes is the JVM property `ebx.factory.connectors.via` (`auto` default, `contract`, `direct`; section 5).
`ConnectorsCouplingScanTest` (a source scan in `tools/test_local.sh`) keeps the table reads where this document says they
are: a new read of the Connector table, of the connector properties or of the key column fails it.

## 2. Inventory of the couplings to Connectors' internals

### 2.1 Reads of Connectors' tables by path, and the key

| file:line | What | Replaced by |
|---|---|---|
| `ebx/AgentLlmConnector.java:38-53` | dataspace/dataset `Connectors`, tables `/root/Connector`, `/root/ConnectorProperty`, columns `label, type, active, baseUrl, authType, apiKeyHeader, apiKey, connectTimeoutMs, readTimeoutMs`; properties `provider, model, openai.*, azure.apiVersion, anthropic.version, maxTokens` | **Wave 12: the FALLBACK only.** `load` (decrypts `apiKey` with `McpServers.secret`) is reached only when the agent loop cannot use `llm.chat` (`via=direct`, Connectors without 1.1.0, a connection `llm.chat` does not serve) and by the direct connectivity test. `listDirect` (the picker) is the fallback of `llm.connectors`; `baseUrlOf` (the one URL column, for the allow-list) stays |
| `ebx/WizardData.java:78-80, 116` | `Connectors/Connectors`, `/root/Connector`: which connections exist, for the step 2 combo | `connectors_list` already exists in the tool surface; a later contract's `connections.list` (id, label, type, active, usable) would replace the read. Not done: it feeds a wizard page another branch is changing |
| `ebx/DeliveryDomainReader.java:39-41, 144-154` | `/root/Connector`: does the connection exist, is it active, are `apiKey` / `password` **set** (never decrypted) - the delivery pre-check | a later contract's `connections.status` (exists, active, secretSet). Also reads which Connectors tables to put in the delivery archive (`delivery/DeliveryDomain.java:316`, component ids `connectors_<table>`): the archive carries Connectors' own tables; that is Staging's business, not the contract's |
| `ebx/SampleSourceImporter.java:58-60, 250-302` | **writes** `/root/Connector` (create or modify a `sample-file` connection with its `sampleData`) | a later contract's `connections.put` (a typed connection document, MANAGE). Until then the write stays; it already needs the person to hold the Connectors right (`docs/OPERATIONS.md` 4.1) |
| `ebx/ConnectionCleanup.java` (lot C, 2026-10-17) | **reads** `/root/Connector` (type, to be sure it is a `sample-file` one), `/root/Mapping` (`connector`, `label`: which flows still run on a connection) and counts `/root/Publication`, `/root/Conflict`, `/root/AccessRight` rows that name it; **deletes** the sample-file connection the factory created for a deleted project, with its `/root/ConnectorProperty`, `/root/SchemaRefreshState`, `/root/FieldProvenance` and `/root/ConnectorOperationalMetrics` rows (never a value, never `./apiKey`). Only after the project's own PipelineStep rows prove the factory created it (`project/ConnectionProvenance`) and nothing else uses it | **no contract call exists to delete a connection** (the tools and contract 1.2.0 delete mappings only); a later `connections.delete` (MANAGE) would replace the Procedure. See `docs/PROJECT-DELETION.md` "Connections and catalogue sources" |
| `wizard/WizardPage.java:2418` | a link to `/ebx-ui/ui/advanced/data/BConnectors/Connectors/root/Connector` | none needed (a link for a person, not a read) |
| `ebx/McpServers.java:261` | `<base>/ebx-connectors/rest/mcp`, the HTTP fallback of the in-JVM MCP bridge | none; the bridge is the module's way to Connectors' tools (section 2.3) |

### 2.2 Reads and writes of the catalog's tables by path (module `ebx-data-catalog`, not Connectors; the catalog has its own contract, wave 11)

| file:line | What |
|---|---|
| `ebx/CatalogReader.java:49-54, 515` | `ebx-data-catalog` model, dataset `DataCatalog/DataCatalog` (`ebx.catalog.dataspace`/`dataset`), tables `/root/Source`, `/root/Asset`, `/root/Column` (read) |
| `ebx/DeducedScanCatalog.java:33-35, 121-126, 407-451` | **writes** `/root/Source` (create), `/root/Asset` (rename: create + move columns), `/root/Column` |
| `ebx/DeducedScanCatalogWriter.java:54-55, 224, 282, 319` | **writes** `/root/Asset`, `/root/Column` (the deduced scan) |
| `ebx/DeducedScanConfirm.java:40, 123` | **writes** `/root/Asset` (scan state) |
| `ebx/ConnectionCleanup.java` (lot C, 2026-10-17) | reads `/root/Source`, `/root/Asset`, `/root/Column` and the tables that point at them (system reader, `CatalogReadSites`); **deletes** a catalogue source the factory created for a deleted project, with its assets, columns and the lineage edges, profile snapshots, samples, alerts, jobs and suggestions on them. The catalogue has no deletion tool and EBX never cascades a delete, so children go first. A data product, access request, workflow launcher, output port or product field on it keeps it |
| `ebx/DataProductPublication.java:81-82, 539` | reads `/root/Source`, `/root/Asset`, `/root/Column`; writes the output port's mapping list |
| `ebx/CatalogLineageDeclarer.java:525-536` | POSTs OpenLineage events to `/ebx-data-catalog/rest/v1/openlineage` |

These are the four writers into another module's tables the audit counted (`DeducedScanCatalog`, `DeducedScanCatalogWriter`,
`DeducedScanConfirm`, and `SampleSourceImporter` into Connectors' own). They are not in the Connectors contract.

### 2.3 Connectors' tools called through the in-JVM MCP bridge (`ConnectorsBridge`, `com.on.ebx.connectors:type=InJvmMcp`) or HTTP

These are calls of Connectors' own tool surface as the caller's session - not table reads. They keep working as before.

| Tool | Callers (file:line) | Contract 1.0 replacement |
|---|---|---|
| `connectors_mapping_upsert` | `LandingFlows.java:526,536` (fallback and rename), `DistributionConfigurator.java:433,443` (fallback), `DataProductPublication.java:818,824` (fallback) | `flows.put` (done; the tool stays as the fallback and for a rename in place) |
| `connectors_mapping_get` | `LandingFlowOwnership.java:74` (is this mapping really the table's own), `DistributionConfigurator.java:484,621` (stale-mapping check, rules of a mapping labelled otherwise) | `flows.get` by KEY only. The ownership check starts from a stored mapping **id**; a later contract's `flows.get {mappingId}` would replace it |
| `connectors_mapping_delete` | `LandingFlows.java:507`, `DistributionConfigurator.java:504`, `GeneratedObjectDeleter.java:116` (project deletion, step reset; since lot C also the catalogue's own flows of a source the factory created, `Catalog scan: ID`... , through `ConnectionCleanup`) | `flows.delete` by key; the callers hold ids, so a later contract's `flows.delete {mappingId}` |
| `connectors_sync`, `connectors_run_get`, `connectors_run_stop` | `LandingFlows.java:841,1544,1578,1596`, `DistributionRunner.java:326`, `DistributionDispatcher.java:616`, `SourceOnboarder.java:392` | `flows.run` exists (`key` or `mappingId`, `async`); not adopted: the load loops poll a run and read its counters, which `flows.run` returns only for the synchronous answer. Candidate for the next wave (needs `flows.runs.get` / stop in the contract) |
| `connectors_list`, `connectors_describe` | `SourceOnboarder.java:77,201`, `LandingFlows.java:643`, `DeducedScanOperations.java:69`, `DistributionConnections.java:56,97,197`, `DistributionConfigurator.java:293,323`, `DataProductPublication.java:175`, `CapabilityLlmSuggester.java:1174,1238`, `McpServers.java:146` (pre-flight probe) | none in 1.0 or 1.1 (`llm.connectors` covers the `llm` ones only). A later contract's `connections.list` / `connections.describe` |
| `connectors_call` | `LandingPreview.java:133`, `DeducedScanSample.java:88`, `KeySourceUniqueness.java:154`, `DistributionDispatcher.java:682`, `DataProductPublication.java:210` | none (an arbitrary operation of a connection; MANAGE, deliberately not in the contract) |
| `connectors_catalog_source`, `connectors_mappings_list`, `connectors_publish` | `SourceOnboarder.java:241,266,332`, `DataProductPublication.java:938` | none in 1.0 |

### 2.4 Java classes of the other modules' jars

| Import | Files | Meaning |
|---|---|---|
| `com.on.ebx.agent.McpClient`, `McpException` | 38 and 45 main files | The MCP client the module uses for EBX's own servers (`data-model`, `match-merge`, `scripting`: not Connectors) and for the in-JVM bridge to Connectors' tools. Stays |
| `com.on.ebx.agent.ToolSet`, `LocalTool` | 17 and 9 main files | The local tools of the LLM passes (a model "proposes" through a tool whose handler validates). 11 `ToolSet.build` sites; two (constraint message, explanation) no longer need them when the contract is in use, the other nine do (section 6) |
| `com.on.ebx.agent.AgentLoop`, `LlmClient`, `LlmChat`, `ContractLlmChat`, `LlmException` | 5 main files (`AgentRuntime`, `AgentRoute`, `CapabilityLlmSuggester`, `MatchPlanLlmSuggester`, `ProseAnswerRecovery`) | The multi-turn loop and its model turns. Since wave 12 `AgentLoop` takes an `LlmChat`: `ContractLlmChat` (`llm.chat`, the key stays in Connectors) or, as the fallback, `LlmClient` built by `AgentRuntime.directSettings` from the connector's URL, headers and **key** |
| `com.on.ebx.connect.core.{MappingEngine, MappingDefinition, MappingRule, Transformations, TransformationContext, Json}` | `StagingLoader.java:21-25`, `MappingMatrixReader.java:13-16`, `StagingLlmSuggester.java:16-17`, `transfer/FeedRules.java:9-10` (4 main files); `transfer/PipelineCompat`, `TransformationCatalogue`, `deducedscan/CatalogSourceId` only name the classes in comments | The staging load, the Mapping matrix and the feed designer apply Connectors' rule pipeline themselves, through a **second copy** of that engine (`on-ebx-mapping.jar`, copied into `WEB-INF/lib` by `tools/mapping_jar.sh`). Not a contract matter (a transformation engine is a library, not an operation); see section 6 |

## 3. The key read, isolated (wave 12: the fallback)

`AgentLlmConnector` (package `ebx`) is the only class that reads `/root/Connector` columns `baseUrl, authType, apiKeyHeader, apiKey`
and `/root/ConnectorProperty`, and the only one that calls `McpServers.secret` on a connector's key. Since wave 12 it is the
**fallback** of the agent loop, no longer its way: `AgentRuntime.run` asks `AgentRoute.resolve` first, and `AgentLlmConnector.load` is
called from exactly two places, both in `AgentRuntime` - `directSettings` (the agent loop's fallback) and `testLlmDirect` (the direct
connectivity test) - and the direct way of the two calls the contract covers since wave 11 (connectivity test, picker), which run only with
`via=direct`, an older Connectors, or no session. `ConnectorsCouplingScanTest` fails when another class reads `./apiKey`,
`/root/ConnectorProperty`, or decrypts a value next to the key column, when `AgentLlmConnector.load` is called from anywhere else, or when the
contract way (`AgentRoute`, `LlmContract`, `ConnectorsContractClient`) mentions the key read at all.

With the contract in use, none of these reaches the key: the connectivity test, the picker, the constraint-message and explanation
passes, the flows and, since wave 12, the nine multi-turn passes.

## 4. What stays on the tool, and why

- **A rename in place of the table's own landing mapping.** The landing flow's label is `Landing: <project> <source> <table>`; a table
  generated before the project code was part of the label owns a mapping with the older label. The tool is given `id` +
  `updateById` to update THAT mapping and rename it (`LandingFlow.mapping`). The contract designates a flow by its key only and
  refuses `id`/`updateById`, so renaming would create a second mapping and lose the watermark. `LandingFlows.upsertThroughContract` therefore asks
  `flows.get(label)` first when the table owns a mapping: the contract is used when the label designates that same mapping, the tool otherwise
  (the table's detail says `flowVia: tool: ...`). A table that owns nothing always goes by the contract.
- **A stale distribution mapping** (connector or operation changed): through the contract the flow of the label is updated in place
  ("the same key updates the same mapping, even after its connector, operation or table changed"), so the delete-then-create of
  `deleteIfConnectorOrOperationChanged` is skipped when the label designates the stored mapping, and kept otherwise.
- **Deletes, runs, listings**: section 2.3.

## 5. The way a call goes: `ebx.factory.connectors.via`

| Value | Behaviour |
|---|---|
| `auto` (default) | The contract when ebx-connectors offers a compatible one; else (absent, older, another major, or switched off with `-Debx.connectors.contract=false`) the earlier way, silently, with one WARN line per state change |
| `contract` | The contract only. A Connectors without a compatible one is a refusal (`engine.external.connectorsContractUnavailable`); nothing falls back |
| `direct` | The earlier way (direct reads, MCP tools); the contract is never called |

The check (`ConnectorsContractClient.status`) reads the MBean's `ContractName` / `ContractVersion` (same major, minor.patch at
least `1.0.0`) and sends `contract.describe` (no session) to catch a switched-off contract; it runs at module start and whenever a screen
or `/status` asks, because Connectors may register its MBean after this module starts. Each distinct outcome is logged once
(`[mdm-factory] ebx-connectors contract ... found` INFO, `... not usable (ABSENT|INCOMPATIBLE|DISABLED|UNREADABLE)` WARN). The administrator's
`GET /ebx-mdm-factory/rest/v1/status` carries `connectorsContract {via, state, contract, offered, writtenFor, detail, active, way}`, and so does the
connection test (`GET /connection/test`).

Typed errors of the contract become coded engine messages (`ConnectorsContractClient.engineRefusal`, English and French sentences in
the block "wave 11: connectors contract" of `Messages_xx.properties`):

| Contract error | Engine code |
|---|---|
| `ABSENT`, `INCOMPATIBLE`, `DISABLED` | `engine.external.connectorsContractUnavailable` |
| `ACCESS_DENIED`, `SESSION_REQUIRED` | `engine.external.connectorsContractDenied` |
| `NO_DEFAULT_LLM`, `CONNECTOR_UNUSABLE`, `NOT_LLM`, `NOT_FOUND`, `NOT_SUPPORTED` (of an `llm.*` operation) | `engine.external.connectorsLlmUnusable` |
| (wave 12) the connection's `maxTokens` below what a pass needs, before any `llm.chat`; or a turn capped and cut | `engine.llm.maxTokensTooLow` (names the connection, what it allows, what to set) |
| `LLM_FAILED`, `TIMEOUT` | `engine.external.connectorsLlmFailed` (a 429/503 of the provider is first retried under `LlmRetryPolicy`, as the direct way does) |
| `AMBIGUOUS_KEY` | `engine.conflict.connectorsFlowAmbiguous` |
| `OPERATION_FAILED` of a flow write | `engine.external.writeMapping` (the same sentence as the tool's refusal) |
| anything else (`INTERNAL`, `UNKNOWN_OPERATION`, a type unknown to this module) | `engine.external.connectorsContractFailed` |

Differences a person can notice with the contract in use: `llm.connectors` does not carry the connection's URL, so when the optional allow-list
`ebx.factory.llm.allowedHosts` is set the module reads that ONE column (`AgentLlmConnector.baseUrlOf`, never the key) and applies
`CredentialPolicy.requireLlmDestination` BEFORE `llm.ask` - the same coded refusals as the direct path (`engine.conflict.llmHostNotAllowed`,
`engine.conflict.llmClearText`; an unreadable URL is refused, the list fails closed). That covers the connectivity test and the one-question passes
(`AgentRuntime.requireAllowedDestination`, `askGuarded`); with the list unset nothing is read or checked on the module's side (Connectors applies
its own `Egress` rules). `AgentRun` rows of a one-question pass carry the model the provider reported and one `MODEL` step with the answer.

### The agent loop's way (wave 12)

`AgentRoute.resolve(session, connection)` decides, per pass, from the same `via` property and the contract's state:

| Situation | Way |
|---|---|
| `via=direct`, or no session | direct (the key is read by the module, as before wave 11) |
| contract absent / incompatible / switched off / **1.0.0** (no `llm.chat`) | `auto`: direct; `contract`: refused (`engine.external.connectorsContractUnavailable`) |
| contract 1.1.0+, connection listed by `llm.connectors` with `chat: true` | **contract**: every turn is `llm.chat` as the pass's own session (the person's, or the job's session that `AgentRuntime.run` already received - the same one that writes the `AgentRun` / `AgentStep` rows), the key stays in Connectors |
| contract 1.1.0+, connection with `chat: false` (Google) | `auto`: direct (which refuses Google as before); `contract`: refused (`connectorsLlmUnusable`) |
| connection not listed / inactive | refused with the same codes as the direct way (`engine.llm.missing` / `inactive`) |
| connection `maxTokens` below `ebx.factory.agent.maxTokens` (4096) | refused up front, no `llm.chat` call (`engine.llm.maxTokensTooLow`: "raise maxTokens of connection X to at least N") |

The choice is logged once per distinct outcome (`[mdm-factory] agent loop: ...`) and shown on `/status` (`connectorsContract.agent`) and in the connectivity
test (`agentRoute`, `maxTokens`, `neededMaxTokens`; a connection that would refuse every pass turns the line red with the sentence). A turn that Connectors
capped (asked more than the connection's `maxTokens`) **and** that stopped at the cap ends the pass with the same message (a half-written tool call is never
a proposal); a capped answer that finished by itself is fine. `LLM_FAILED` / `TIMEOUT` stay ordinary failed runs (the 429/503 retry of `AgentRuntime.run`
applies unchanged); `ACCESS_DENIED`, `NO_DEFAULT_LLM`, `CONNECTOR_UNUSABLE`, `NOT_FOUND`, `NOT_SUPPORTED` end the pass with the coded message of
section 5's table. Two differences a person can notice: the model asks `max(4096, the connection's maxTokens)` per turn (the direct way asked the connection's
property, 4096 when unset), and the per-turn read timeout is the connection's own (`ebx.factory.agent.turnTimeoutMs` overrides).

## 6. What is left (after wave 12)

The agent loop's need - several model turns with tools, the caller running the tools - is met by `llm.chat` of contract 1.1.0, whose shape this module's
earlier proposal described (stateless, one turn per call, `{role: user | assistant(toolCalls) | tool}` messages, `tools[]` with a JSON Schema, `stopReason`,
`usage`), plus what the module did not ask for and now lives with: the answer size is the connection's (`maxTokensApplied`, `capped`). On this side it is
`ContractLlmChat` of `on-ebx-agent.jar` (the library's `AgentLoop` takes any `LlmChat`) behind `AgentRoute`/`AgentRuntime.run`.

Still coupled, and why:

- **The key read stays as the fallback** (`AgentLlmConnector.load`, `AgentRuntime.settingsFor`, `ConnectorInfo`, `ProviderSettings`, the key decryption in
  `McpServers.secret`): for `via=direct`, for a Connectors without 1.1.0, for a connection `llm.chat` does not serve (Google answers `NOT_SUPPORTED`), and for
  the direct connectivity test. It can be deleted when every deployment runs Connectors 1.1.0 or later and Google tool use is offered (or the Factory gives up Google,
  which `settingsFor` already refuses).
- **A pass needs 4096 tokens per turn; the connection decides.** An administrator must raise `maxTokens` on the connection (1024 when it has none): the contract
  is deliberately the connection's owner of the size (`docs/OPERATIONS.md`).
- **The allow-list of hosts** still reads one column of the connection row (`AgentLlmConnector.baseUrlOf`, never the key) before `llm.ask` and before an agent pass,
  because `llm.connectors` does not carry the URL (section 5).

Operations a later contract could add (none is in 1.1.0) to remove the remaining couplings of section 2: `connections.list` / `connections.status` (WizardData,
DeliveryDomainReader), `connections.put` (SampleSourceImporter), `flows.get {mappingId}` and `flows.delete {mappingId}` (ownership check,
deletions), and run polling for `flows.run` (the landing and distribution loops). The embedded mapping engine (`on-ebx-mapping.jar`) would
need either a `transformations.apply {pipeline, value}` operation (a call per value is too slow for the staging load: it would have to take
a batch) or stay a shared library; that is a design decision, not a contract detail.
