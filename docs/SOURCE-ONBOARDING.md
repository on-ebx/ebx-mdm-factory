# Source onboarding — "Create a catalog source" panel (wizard step 2)

Source-onboarding lot, 2026-09-23, branch `feature/wizard-source-onboarding`. the product owner, on the deployed
wizard: "Dans un projet, on choisit des sources. Je ne vois pas ma source de test appelée SourceBidon." /
"Il me faut une fonction de création de source, qui me guide en fonction de ce qui serait nécessaire."

## Why

The wizard's step-2 picker lists the data catalog's `/root/Source` rows (`CatalogReader.sources`), not
Connectors connections. A brand new connection (a fresh `sample-file` upload, a newly configured
Snowflake account…) has to go through four steps before the wizard can even see it, and none of them
happen by themselves:

1. a **connection** exists in Connectors (`/root/Connector`), is active, and its type offers a `catalog`
   operation;
2. a **catalog Source** names that connection (`connectors_catalog_source`, plan then apply);
3. the catalog created the **scan flows** for that Source (two mappings: `Catalog scan: <SOURCE>` on
   `/root/Asset`, feed, parent; `Catalog scan columns: <SOURCE>` on `/root/Column`, child);
4. the **scan ran** (`connectors_sync` on the parent mapping) and found at least one asset.

Only then does the Source show up in the wizard's existing "add an application" combobox. The panel this
lot adds walks a person through exactly these four steps for one picked connection, with a live
checklist and one button per step - never a second, competing "create a source" flow.

## The five-part split

- **A — pure rule**, `com.on.ebx.factory.wizard.SourceOnboarding` (already committed before this lot,
  built on here): `Facts` (everything the live side could read), `diagnose(Facts)` → a `Diagnosis` of six
  `Step`s (`connection`, `catalogOperation`, `catalogSource`, `scanFlows`, `scan`, `ready`), each `DONE`/
  `TODO`/`BLOCKED`/`WAITING` with a message key + args and, for the whole diagnosis, the ONE next action to
  offer (`OPEN_CONNECTION`/`CATALOG_APPLY`/`SCAN_RUN`/`PICK`/`NONE`). No EBX or MCP import anywhere in this
  class - unit-tested against the measured tool answers below (`SourceOnboardingTest`). This lot's own
  addition to it: `Facts.scanMessage` (the last `connectors_sync` outcome text, never read by `diagnose`
  itself, only carried through for display) and `ConnectionOption` (one Connectors connection as the
  onboarding combobox lists it - a plain DTO, kept here rather than in `ebx.SourceOnboarder` because
  `WizardPage` must stay EBX-free, the same split `WizardSnapshot` already follows for the rest of the
  screen).
- **B — live side**, `com.on.ebx.factory.ebx.SourceOnboarder`: turns `Facts` into real MCP/EBX calls.
  `connections(factory)` (`connectors_list` + a cross-reference against `CatalogReader.sources`, sorted
  catalog-capable-and-not-yet-onboarded first); `facts(factory, connectorId)` (`connectors_describe` →
  `connectors_catalog_source(apply=false)` → `connectors_mappings_list` → `CatalogReader.sourceById`/
  `hasAssets`, each call's failure landing in the matching `Facts.*Error` field, never thrown - the read
  gating mirrors `SourceOnboarding.diagnose`'s own step order: no point reading the plan for an inactive/
  non-catalog connection, no point reading scan flows/scan/source for anything but a `LINKED` plan);
  `applyCatalog`/`runScan` (preflight, the real write, then a fresh `facts()` re-read - a write's own
  refusal is logged and left for the re-read to surface, never thrown, except when the required MCP
  server(s) are not usable at all). `CatalogReader` gained `sourceById` (active/lastScanStatus by primary
  key) and `hasAssets` (a boolean existence check) for this.
- **C — wizard UI**: `WizardPage.renderSourceOnboardingPanel` (a muted panel under the existing "add an
  application" form). **Revised after coordinator review, 2026-09-23** (the panel's own combobox list used
  to be read unconditionally in `model()` on every render - a live `connectors_list` MCP round trip on
  every single wizard click, on any step, not just step 2; and the panel's client-only
  `data-mdmf-toggle` never actually rendered the combobox absent when collapsed): the panel is now closed
  by default and renders ONLY its title, as a real action button `SOURCE_ONBOARD_OPEN` - the ONE place
  (together with `SOURCE_ONBOARD_REFRESH`) `SourceOnboarder.connections` is ever called. Opening it reads
  the connection list into `onboardConnections`/`onboardConnectionsError` (instance state, `boolean
  onboardOpen` flips true) and the body - the connection combobox (`renderOnboardConnectionCombo`, the same
  searchable-combobox technique as the catalog-source picker; `wizard.js`'s combobox behaviour is factored
  into a reusable `initCombo(...)` so both pickers share one implementation), a link to create a brand new
  Connectors connection, a Close button (`SOURCE_ONBOARD_CLOSE`, no MCP call, no toast), and, once a
  connection is picked, the checklist (`renderOnboardChecklist`) - renders, genuinely absent from the
  markup otherwise, not merely hidden. Every row leads with a `<b>` short step name (coordinator review,
  2026-09-23: three consecutive WAITING rows used to all read the identical "Waiting on a previous step",
  with nothing saying which step each row actually was) - `wizard.onboard.step.<key>.name` (Connection /
  Catalog operation / Catalog source / Scan flows / Scan / Ready, EN/FR), derived from `Step.key` by the
  rendering layer and by `FactoryResource.onboardingJson` alike; `SourceOnboarding` itself emits no such
  key, it stays the same pure state machine. The checklist's ONE next-action control
  (`renderOnboardNextAction`): for `OPEN_CONNECTION`, a link built from the DOCUMENTED filtered-table
  pattern (`WizardPage.connectorRecordUrl`, the same `?predicate=./id='<id>'` percent-encoded pattern
  `ebx.RecordLineageReader#openUrl` already uses - a bare `.../root/Connector/<id>` path is not a
  documented `ebx-ui` record route); a real MCP-write button (disabled with the admin-only reason for a
  non-administrator) for `CATALOG_APPLY`/`SCAN_RUN`; the `SOURCE_ONBOARD_USE` button for `PICK`. A
  "Refresh" button re-reads BOTH the connection list and the picked connection's facts (so a connection
  created meanwhile in Connectors shows up without closing and reopening the panel).
  `ProjectWizardUserService` wires the seven actions (`SOURCE_ONBOARD_OPEN`/`CLOSE`/`PICK`/`REFRESH`/
  `CATALOG`/`SCAN`/`USE`), keeps `onboardOpen`/`onboardConnections`/`onboardConnectionsError`/
  `onboardConnectorId`/`onboardDiagnosis`/`onboardConnectorDisplay`/`onboardScanMessage` on the service
  instance across postbacks, refreshed ONLY by those actions - never by a plain render or an unrelated
  wizard click; `model()` only ever COPIES this instance state. `SOURCE_ONBOARD_USE` reuses
  `pickCatalogSource`'s own logic verbatim (source-system suggestion, connector lookup) and closes the
  panel via `onboardClosePanel` (also resetting `onboardOpen`); `wizard.js` scrolls to the "add an
  application" form afterwards via a stable `id="mdmf-add-source-form"` anchor, never `window.scrollTo`
  (CLAUDE.md §15's own scroll-anchor lesson).
- **D — REST parity**, `FactoryResource`: `GET /sources/onboarding/connections`, `GET
  /sources/onboarding/{connector}` (facts + diagnosis, open to any user - a plain degrading read), `POST
  /sources/onboarding/{connector}/catalog`, `POST /sources/onboarding/{connector}/scan` (administrators
  only, preflighted at the route level like every other MCP-write route in the file, on top of
  `SourceOnboarder`'s own internal preflight - the wizard calls `SourceOnboarder` directly, so its
  preflight has to be there regardless; the route's own preflight is only the nicer 503-with-server-detail
  presentation, same convention as `/sources/{id}/landing/generate`).
- **E — this document**, a `PLAN.md` "Décisions prises" line, this README's install notes are unchanged
  (no new install step - the panel is part of the wizard, not a separate screen).

## Measured tool answers (instance Test 6.3.0, `ebx-connectors` MCP server, 2026-09-23)

Copied verbatim from the task brief; `SourceOnboardingTest`'s fixtures are built from these exact JSON
shapes.

`connectors_describe(connector=SourceBidon)`:
```json
{"id":"SourceBidon","label":"SourceBidon","type":"sample-file","typeLabel":"Sample file (CSV / JSON)","baseUrl":"http://localhost:8123","authType":"NONE","trust":50,"pricing":"UNKNOWN","active":true,"properties":{},"operations":[{"name":"read","kind":"SEARCH","description":"...","requiredInputs":["path"],"optionalInputs":[]},{"name":"list","kind":"SEARCH","description":"...","requiredInputs":[],"optionalInputs":[]},{"name":"catalog","kind":"SEARCH","description":"Inventories the single uploaded sample, with columns inferred from it (inputs source, prefix, max, columnsMax)","requiredInputs":["source"],"optionalInputs":[]}]}
```

`connectors_catalog_source(connector=SourceBidon)` (plan only, `apply=false`):
```json
{"apply":false,"catalog":{"state":"VERIFIED","dataspace":"DataCatalog","dataset":"DataCatalog"},"connector":"SourceBidon","namespace":"sample-file://localhost:8123","action":"CREATE","source":"SOURCEBIDON","message":"No Source for connector SourceBidon: create Source SOURCEBIDON with namespace 'sample-file://localhost:8123'; the catalog then creates its flows.","applied":false,"nextStep":"connectors_catalog_source(connector=SourceBidon, apply=true) writes it."}
```

`connectors_catalog_source(connector=PersonsSapSample)` (already linked):
```json
{"apply":false,"catalog":{"state":"VERIFIED","dataspace":"DataCatalog","dataset":"DataCatalog"},"connector":"PersonsSapSample","namespace":"sample-file://persons-sap.sample-file.local","action":"LINKED","source":"PERSONSSAPSAMPLE","message":"Source PERSONSSAPSAMPLE already names connector PersonsSapSample with namespace 'sample-file://persons-sap.sample-file.local'.","applied":false}
```

`connectors_mappings_list(connector=PersonsSapSample)`:
```json
{"mappings":[{"id":236,"label":"Catalog scan: PERSONSSAPSAMPLE","connector":"PersonsSapSample","operation":"catalog","direction":"INBOUND","dataset":"DataCatalog","table":"/root/Asset","active":true,"feed":true,"child":false,"rules":37,"children":1},{"id":237,"label":"Catalog scan columns: PERSONSSAPSAMPLE","connector":"PersonsSapSample","operation":"catalog","direction":"INBOUND","dataset":"DataCatalog","table":"/root/Column","active":true,"feed":false,"child":true,"rules":19,"children":0}],"count":2}
```
`SourceOnboarder.mappingsInto` looks for exactly this shape: `operation=catalog`, `direction=INBOUND`,
`feed=true`, `child=false`, `table=/root/Asset` - the PARENT mapping id (236 here), never the child
(syncing the child alone answers "No record for key/predicate", per Connectors' own contract).

## What each step means, and its next action

| Step | key | DONE when | BLOCKED/TODO otherwise | Action |
|---|---|---|---|---|
| 1 | `connection` | found & active | not found (tool error shown) / inactive | `OPEN_CONNECTION` |
| 2 | `catalogOperation` | type offers `catalog` | type has no `catalog` operation | none - pick another connection |
| 3 | `catalogSource` | plan action `LINKED` | `CREATE`/`SET_CONNECTOR`/`SET_NAMESPACE` (TODO) - `MANUAL` (BLOCKED, a person must fix the connection; a `sample-file` type gets the host-collision hint, CLAUDE.md §14) - catalog state not `VERIFIED`, or the plan call itself refused (BLOCKED, no action) | `CATALOG_APPLY` (TODO cases only) |
| 4 | `scanFlows` | a parent scan mapping id was found | linked but no mapping (never silently blocked - the fix is the same as step 3) | `CATALOG_APPLY` |
| 5 | `scan` | `lastScanStatus` ∈ {OK, PARTIAL} AND at least one asset | `NEVER`/`ERROR`/OK-or-PARTIAL-with-nothing-found | `SCAN_RUN` |
| 6 | `ready` | everything above DONE and the Source row exists & is active | Source inactive (BLOCKED, activate it in the catalog) - Source missing despite everything above DONE (defensive fallback) | `PICK` |

`nextAction` is the action of the FIRST non-`DONE` step; once every step is `DONE`, that is `ready`'s own
`PICK`, and `Diagnosis.pickSourceId` carries the catalog source id to pre-pick.

## What was seen running, and what was not

Seen: `tools/test_local.sh` green end to end (XSD-diff check, compile against `EBX_LIB_DIR`, every
`*Test.java` under `src/test/java`, `SourceOnboardingTest`/`WizardPageTest`/`WizardMessagesTest` included).
`WizardPageTest` covers the panel's HTML for a `CATALOG_APPLY` diagnosis (checklist icons/state classes,
the todo message with the plan's own text, the write button's admin gating) built from the exact
SourceBidon fixture above.

**Not seen: the panel has never run under Tomcat.** No live `connectors_list`/`connectors_describe`/
`connectors_catalog_source`/`connectors_mappings_list`/`connectors_sync` call was made this session (the
task forbids calling a write tool on the live instance, and no read tool was used either - the measured
answers above are exactly and only the ones already captured in the brief). Specifically unverified:

- that `connectors_list`'s real response still parses the way `ServiceInventory.parseConnectorsList`
  expects (reused as-is, already covered by `ServiceInventoryTest` against the same measured shape used
  elsewhere in this module);
- that the onboarding combobox and checklist actually render/behave correctly in a browser (keyboard
  navigation, the toggle panel, the scroll-to-add-form override) - only the server-rendered HTML was
  checked, in `WizardPageTest`;
- that `SOURCE_ONBOARD_CATALOG`/`SOURCE_ONBOARD_SCAN` genuinely reach `ebx-connectors` and that the
  re-read afterwards reflects a real write (the code path mirrors `LandingFlows.generate`/`load` closely,
  which HAVE been seen running live in earlier lots, but this exact new code was not re-exercised);
  the REST routes (D) likewise untested live.

## Sources from sample files (sample-files lot, 2026-09-23)

the product owner's decision, the same day: "Le fichier ne doit etre qu'un echantillon qui sert a la configuration."
A person who already has export files of their source applications should get a working source in ONE
gesture - drop the files in step 2, and the Factory does everything: create the sample-file connection,
link the catalog Source, run the scan, add the application to the project. They never have to open
Connectors. The guided panel above (this document's own sections) is unchanged and stays for a REAL
connection - credentials typed by a person in Connectors, never by the Factory.

### The contract with the parallel Connectors lot

The connection's `sampleData` field holds a data URL carrying the original file name as an RFC 2397
parameter: `data:<mime>;name=<encodeURIComponent(file name)>;base64,<payload>`. `mime` by extension:
csv/txt -> `text/csv`, tsv -> `text/tab-separated-values`, json -> `application/json`, ndjson/jsonl ->
`application/x-ndjson` (the same map as Connectors' own `UploadedSampleFileWidgetFactory`, read-only
reference in `~/Dev/ebx-connectors`). **This lot depends on the parallel Connectors lot
`feature/sample-file-name`**: the OLD connector code (`UploadedSampleFileWidgetFactory.isAcceptedDataUrl`,
matched verbatim on `"data:<mime>;base64,"` with nothing between mime and `base64,`) rejects a data URL
that carries the extra `;name=...` segment outright. Both lots must be deployed together, or every sample
uploaded through this panel is refused by Connectors' own field validation.

### Design

- **Pure rules**, `wizard.SampleFileRules` (+ `SampleFileRulesTest`): `mimeFor`, `connectionId` (base name,
  CamelCase, ASCII-only, max 40 - the `Connector.id` field's own length limit, `Personnes_SAP_Sample.json`
  -> `PersonnesSapSample`), `host` (a dedicated `https://<slug>.sample-file.local/data` per connection - two
  sample-file connections must never share a host, CLAUDE.md sec 14: the catalog derives the namespace from
  the host alone), `sourceSystemCode` (delegates to `WizardRules.suggestSourceSystemCode` on the base name -
  editable before submit), `dataUrl`/`parseDataUrl` (the RFC 2397 contract above), `rawBytes` (the decoded,
  pre-base64 size, without actually decoding). `RAW_BYTES_CAP = 1,400,000` raw bytes applies BOTH to any
  single file AND to the running total of one submit - base64-inflated that is about 1.87 MB of text, under
  this Tomcat's default 2 MB POST cap (`server.xml`'s 8080 connector sets no `maxPostSize`) with headroom
  for the rest of the form. **The cap is deliberately never read from anywhere** - there is no reliable way
  to learn Tomcat's own limit from inside EBX; raising `maxPostSize` lifts the PRACTICAL limit to the
  connector's own 2 MB per file, this constant simply becomes conservative rather than wrong.
- **Live side**, `ebx.SampleSourceImporter.importFiles(Session, Adaptation, projectId, List<FileInput>)`:
  per file, in order, failing that one file but continuing with the rest - (1) validate against
  `SampleFileRules`; (2) write the `sample-file` Connector row (create, or refresh in place if one of the
  same id and type `sample-file` already exists - a different type refuses the file outright, rename it) -
  a direct EBX `Procedure` on dataspace/dataset `Connectors`, the CALLING session's own rights, the same
  write-into-another-module's-dataset technique `ebx.DmaToolbarWriter` already uses for `ebx-dma`, never the
  MCP agent account; (3) `ebx.SourceOnboarder.onboardAll` (new: chains `applyCatalog` then `runScan`, each
  only when the diagnosis still calls for it - shared with the guided panel's own new "Do everything"
  button, see below); (4) once `ready`, add the application (`WizardData.addSource`) unless the project
  already has one on this exact catalog source (named in the report as `existingApplication`). The per-file
  report row (`wizard.SampleFileRules.ReportRow` - a pure DTO in the wizard package, not
  `SampleSourceImporter` itself, so `WizardPage` can render it without importing EBX, the same split
  `SourceOnboarding.ConnectionOption` already follows) carries `name`, `connectionId`, `sourceId`,
  `diagnosis`, `applicationAdded`, `existingApplication`, `error`.
- **Wizard UI**: a new muted panel "Sources from sample files", ABOVE the guided "Create a catalog source"
  panel (the simpler entry) - collapsed behind a plain `data-mdmf-toggle` (needs no server round trip to
  open, unlike the guided panel: the drop zone is pure client-side markup). A drop zone
  (`<input type="file" multiple accept=".csv,.tsv,.txt,.json,.ndjson,.jsonl">` + dragover/drop), then a
  table (file name, size, MIME kind, an editable "Application code" input pre-filled by a SIMPLIFIED,
  deliberately non-identical JS port of the code-proposal rule - upper-case, non-alphanumerics -> `_`,
  extension stripped, 20 chars max; the server re-validates with the real rule - a trust-rank input
  defaulting to position+1, a remove button). Client-side checks mirror the server's own: extension, per-file
  and total raw-byte cap (a hand-kept-in-sync copy of `RAW_BYTES_CAP` in `wizard.js`, no shared source with
  Java), duplicate application codes. `FileReader.readAsDataURL` per file, rebuilt into the RFC 2397 contract
  form client-side (mime by extension, `;name=<encodeURIComponent(name)>`); everything is collected into ONE
  hidden `<textarea id="mdmfSampleFiles">` as a JSON array `[{name,size,code,trustRank,dataUrl}]` right
  before submit (`window.mdmfSyncSampleFiles`, called from the `[data-mdmf-action]` handler on
  `SOURCE_FILES_IMPORT` only - same "sync right before the real submit" discipline as
  `mdmfIncludeBelowThreshold`). One button, "Create the sources from these files" (`SOURCE_FILES_IMPORT`,
  administrators only, its own progress-toast label). After the round trip the panel renders OPEN with the
  report: one block per file, reusing the EXACT same checklist markup as the guided panel
  (`WizardPage.renderOnboardChecklistRows`, factored out of `renderOnboardChecklist` for this), the
  application line, and the file's own error when any. **Server guard**: if `SOURCE_FILES_IMPORT` posts with
  `mdmfSampleFiles` empty or unparsable, the notice names the raw-byte cap and says the files likely did not
  reach the server (too large for the request's own size limit) - `ProjectWizardUserService.sourceFilesImport`.
- **"Do everything" on the EXISTING guided panel**: a new button, `SOURCE_ONBOARD_ALL`, next to the write
  button whenever the next action is `CATALOG_APPLY` or `SCAN_RUN` (administrators only, same disabled-with-
  reason pattern). Backed by the new `ebx.SourceOnboarder.onboardAll` - re-reads the diagnosis after each
  write, so it naturally stops at the first step that comes back `BLOCKED` rather than `TODO` (a blocked
  step never sets `nextAction` to `CATALOG_APPLY`/`SCAN_RUN`, so the chain simply does not attempt the next
  write - no separate "stop on blocked" branch needed). When it reaches `ready`, it does exactly what
  `SOURCE_ONBOARD_USE` already does: pre-picks the source and closes the panel.
- **REST parity**, `FactoryResource`: `POST /projects/{code}/sources/files` (body: a bare JSON array
  `[{"name","dataUrl","code","trustRank"}]`, no envelope) -> the report as JSON per file (`name`,
  `connectionId`, `sourceId`, `diagnosis` as the same shape `onboardingJson` already produces,
  `applicationAdded`, `existingApplication`, `error`); `POST /sources/onboarding/{connector}/all` -> the
  final diagnosis JSON, same shape as `/catalog`/`/scan`. Both administrators-only, preflighted at the route
  level like every other MCP-write route in this file. **This is the FIRST route in `FactoryResource` that
  reads a raw POST body** (`@Consumes(MediaType.APPLICATION_JSON)` + a plain `String` parameter) - untested
  against a real Jersey deployment, unlike every other route in the file (all path/query-param driven).

### Tests and what was not seen

`tools/test_local.sh` green: `SampleFileRulesTest` (new - every rule, including the task brief's own two
`connectionId` examples), `WizardPageTest` extended (the panel closed by default; a report of two files -
one ready with the application added, one refused at the raw-byte cap), `WizardMessagesTest` extended (the
new argument-bearing keys), `SourceOnboardingTest` still green (untouched).

**Nothing here has run under Tomcat.** No live `connectors_catalog_source`/`connectors_sync` call, no
`Procedure` write into the `Connectors` dataspace, no REST call against either new route - the task forbade
calling a live tool. Specifically unverified, beyond what the guided panel's own "not seen" list already
says:

- that a `Connector` row written this way (`SampleSourceImporter.writeConnection`) is actually accepted by
  Connectors' own validation and, once the parallel `feature/sample-file-name` lot is deployed, that
  `connectors_catalog_source`/`connectors_sync` genuinely pick it up as a real `sample-file` source;
- the exact Tomcat POST-size limit this instance actually enforces - `RAW_BYTES_CAP` is a conservative
  guess documented in `SampleFileRules`'s own Javadoc, not a measured number;
- that the drop zone/table/report genuinely render and behave in a browser (drag-and-drop, the per-row
  remove button, the duplicate-code check) - only the server-rendered HTML of the closed and report states
  was checked, in `WizardPageTest`;
- the new REST routes, `/projects/{code}/sources/files` in particular (the first raw-POST-body route in
  this resource class).
