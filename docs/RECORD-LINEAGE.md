# Record lineage — read side (lot 4)

Written 2026-09-21 on branch `feature/record-lineage-read` (worktree `ebx-mdm-factory-lineage`),
implementing the READ half of `PLAN.md`'s "Lineage par enregistrement" / lot 4: given a master record's
identity, assemble the JSON `docs/mockups/mdm-factory.html` screen 5 ("Lineage de l'enregistrement")
needs. **No screen in this change** — that is still to build from the mockup, per the task brief.
**Never run against a live instance in this session** (the coordinator's own constraint) — every claim
below is either read directly from source, read from `docs/MASTER-ZONE.md`'s own earlier live reads, or
verified by `javap` against the jars of this instance's server project, each labelled as such.

## What was abandoned, and why

`PLAN.md` (and `docs/MASTER-ZONE.md` §5.1, a live read from 2026-09-20) already decided that a separate
`FieldProvenance` table would duplicate what the Match and Merge add-on's own `MergeValueLineageCompact`
computes and stores per merge run. This lot does not create `FieldProvenance`, and does not touch
`MdmFactory.xsd` at all — the lineage screen is a pure read across tables that already exist (`Xref`,
landing, staging, master, `AttributeMapping`, `MappingStep`, and the add-on's own metadataset).

## What the route returns

`GET /ebx-mdm-factory/rest/v1/projects/{code}/lineage/{entity}?key=<formatted master primary key>`

Any authenticated user (read-only, caller's own session, no MCP call — same discipline as
`/relations/check`/`/staging/suggest`), **but see "Authorisation" below: unlike those two routes, this one
returns real field values, and enforces the caller's own EBX read permissions record by record and field
by field.** `404` for an unknown project, an entity that is not a promoted master entity of the project,
a master key that does not exist, **or a master key the caller's session may not see** (indistinguishable
from "does not exist" — see "Authorisation"); `400` for a blank or unparsable `key`; `500`, naming the
failed step but never a value, for a genuinely unexpected failure outside the merge block (which never
lets one escape — see "What was NOT read" for the `METADATA_UNREADABLE` degrade instead).

```json
{
  "project": "Companies",
  "entity": "Company",
  "masterTable": "/root/Company",
  "masterKey": "12",
  "record": {
    "fields": { "name": "Acme Industries SAS", "country": "FR", "city": "Lyon" },
    "restrictedFields": ["vatNo"]
  },
  "merge": {
    "present": true,
    "state": "GOLDEN",
    "groupId": "42",
    "goldenKey": "12",
    "autoCreated": false
  },
  "sources": [
    {
      "masterKey": "12",
      "golden": true,
      "sourceSystem": "ERP",
      "sourceId": "SUP-00231",
      "landing": { "dataset": "ERP", "table": "SUPPLIER", "key": "SUP-00231", "loadId": "L20260920-131535-1", "status": "STAGED", "receivedAt": "2026-09-20T13:15:35Z" },
      "staging": { "table": "Company", "key": "ERP|SUP-00231" }
    },
    {
      "masterKey": "7",
      "golden": false,
      "sourceSystem": "CRM",
      "restricted": true
    }
  ],
  "fields": [
    {
      "field": "name",
      "contributions": [
        { "masterKey": "12", "sourceSystem": "ERP", "landingColumn": "LEGAL_NAME", "rawValue": "ACME INDUSTRIES", "stagingValue": "ACME INDUSTRIES", "cleaningSteps": ["TRIM"] },
        { "masterKey": "7", "sourceSystem": "CRM", "restricted": true }
      ],
      "differs": null
    },
    {
      "field": "country",
      "contributions": [
        { "masterKey": "12", "sourceSystem": "ERP", "landingColumn": "COUNTRY", "rawValue": "FR", "stagingValue": "FR", "cleaningSteps": [] },
        { "masterKey": "7", "sourceSystem": "CRM", "landingColumn": "BILLING_COUNTRY", "rawValue": "France", "stagingValue": "FR", "cleaningSteps": ["TRIM", "COUNTRY_TO_ISO2"] }
      ],
      "differs": false
    }
  ],
  "fieldLineageNote": "Field-by-field merge provenance (MergeValueLineageCompact.Items[]) was not read: ..."
}
```

(The `name`/`vatNo` fields above are deliberately shown restricted in different ways — `vatNo` hidden on
the master record itself, the whole `CRM` source hidden entirely — to illustrate both shapes in one
example; a real answer only restricts what the caller's session actually cannot see. A `merge.present:
false` answer replaces the whole `merge` object with `{"present": false, "reason": "...", "detail": "..."}`
— see below.)

Notes on the shape:

- **`merge.present: false`** replaces `state`/`groupId`/`goldenKey`/`autoCreated` with a `reason`, one of
  `NO_MATCHING_YET` (the entity's `MatchPlan` is not yet `APPLIED` — checked first, without ever touching
  the add-on), `ADDON_NOT_AVAILABLE` (the add-on jar is absent from this server's classpath), `NO_METADATA`
  (the add-on is available and matching is configured, but this specific record carries no metadata yet),
  or `METADATA_UNREADABLE` (an unexpected failure reading the add-on's own metadataset — see "What was NOT
  read"), which also carries a `detail` string (the exception's class and message, never a record value).
  Never an error reaching the caller — `lineage.RecordLineageTest#mergeAbsent_eachReason`/
  `#mergeAbsent_metadataUnreadable_carriesDetail` cover all four.
- **`sources`** is one entry per contributing master record: just the requested record when merge
  information is absent or the record is not part of a group, or the golden (`golden: true`) plus every
  other master record whose `RecordMetadata.goldenId` points at it otherwise. A contributing master key
  with no `Xref` row of its own (an add-on-`autoCreated` golden with no source record) is simply absent
  from `sources` — not an error. A source whose landing or staging record the caller's session may not see
  renders as `{masterKey, golden, sourceSystem, restricted: true}` only — see "Authorisation".
- **`fields`** aggregates every contribution, across every contributing source, for that field name;
  `differs` compares the staged (cleaned/typed) value where one exists, the raw landing value otherwise,
  and is `false` whenever zero or one distinct comparable value exists (a lone contribution, or every
  contribution empty) — **unless any contribution is restricted, in which case `differs` is `null`
  (unknown), never a confident `false`.** A restricted contribution itself renders as `{masterKey,
  sourceSystem, restricted: true}` only.
- **`record.restrictedFields`** names every business field hidden on the master record itself (dropped
  from `record.fields`, never emitted with a value there either).
- **A `FOLD_KEY` derived attribute (2026-09-22, `docs/NORMALISATION.md` §3.3 bis) never appears at all** —
  neither in `record.fields` nor as a `FieldInput`/contribution — chosen as the simpler of two options
  (exclude entirely vs. show collapsed under a "derived" tag): it is a TECHNICAL matching key (e.g.
  `cityKey`), not a business value a reviewer needs to see, and showing a folded value like `ZURICH` next
  to the `city` field it was built to leave untouched would contradict the whole point of the 2026-09-22
  decision. `ebx.RecordLineageReader` filters it out by reading `StagingAttribute.derivation` fresh (a
  small dedicated query, `readDerivedAttributeNames`) rather than extending `StagingModel.AttributeInfo`'s
  shape — that class deliberately treats a derived attribute as ordinary everywhere else (§2's own note in
  `docs/MASTER-ZONE.md`); this reader is the one place that needs to tell the two apart. The pure model
  (`lineage.RecordLineage.assemble`) itself needed no change: it simply never receives a derived field's
  name in the first place.
- **`fieldLineageNote`** appears ONLY when there is more than one contributing source (so field-level,
  post-merge survivorship would be meaningful) — see "What was NOT read" below for exactly why it is a
  seam rather than a real read.

## Authorisation (coordinator review, 2026-09-21)

**This is the one route in this module that returns real field VALUES of master, staging and landing
records** — every other "any authenticated user" route (`/relations/check`, `/staging/suggest`,
`/quality/suggest`) returns counts, proposals or metadata, never a business value read off someone else's
record. `AdaptationTable.createRequestResult(predicate)`/`lookupAdaptationByPrimaryKey` are **not**
permission-filtered by themselves, and passing a `Session` through to a reader does not make its reads
permission-aware on its own — an earlier version of this route relied on exactly that mistaken assumption
and would have let any authenticated user read any record of the project's master/staging/landing zones,
whatever EBX's own access rules said. Fixed by checking the caller's own permissions explicitly, through
`Session.getPermissions()` (`com.orchestranetworks.service.SessionPermissions`), verified by `javap`
against `ebx-lib.jar`:

```
public interface com.orchestranetworks.service.SessionPermissions {
  ...
  public abstract AccessPermission getAdaptationAccessPermission(Adaptation);
  public abstract AccessPermission getNodeAccessPermission(SchemaNode, Adaptation);
  ...
}
public final class com.orchestranetworks.service.AccessPermission {
  ...
  public boolean isHidden();
  ...
}
```

(`Session.getPermissions()` itself: `public abstract SessionPermissions getPermissions();` on
`com.orchestranetworks.service.Session`.)

Where the checks sit (`ebx.RecordLineageReader`):

- `isHidden(Session, Adaptation)` wraps `getAdaptationAccessPermission(...).isHidden()` — used on the
  MASTER record right after the lookup (a hidden master answers the exact same `404` message as a master
  key that does not exist at all — `recordNotFoundMessage`, built once and thrown from both sites, so a
  caller can never tell "hidden" from "does not exist"), and on every contributing source's own landing and
  staging record inside `resolveSource` (either hidden restricts the whole `ResolvedSource`/`SourceChain`).
- `isFieldHidden(Session, Adaptation, String)` wraps `getNodeAccessPermission(...).isHidden()` — used on
  every business field of the master record (feeding `RecordLineage.FieldValue`) and, per contribution, on
  the field's own landing column (on the landing record) and the field itself (on the staging record)
  (feeding `RecordLineage.FieldContribution#restricted`).
- Both fail **closed** in every ambiguous case, never open: an unexpected `RuntimeException` from the
  permission API itself is treated as hidden, and so is a `null` `AccessPermission` answer — an earlier
  version of this class treated `null` as visible (`permission != null && permission.isHidden()`),
  inconsistent with the exception path; fixed to `permission == null || permission.isHidden()`
  (coordinator review, 2026-09-21). A field whose `SchemaNode` is `null` (it does not exist on that
  record's table at all) still answers "not hidden" - confirmed safe, not a fail-open: every one of
  `isFieldHidden`'s three call sites in `RecordLineageReader.read` only ever reaches a value through
  `safeGet`/`safeString`, which independently re-checks the identical "does this path exist" condition and
  answers `null` regardless of what `isFieldHidden` said - so a missing field never has a real value to leak
  in the first place, only its mapped column NAME (metadata, not data) reaches the JSON either way. Neither
  `isHidden` nor `isFieldHidden` is reachable from a pure, EBX-free seam (both call `Session.getPermissions()`
  and `Adaptation.getSchemaNode()` directly) - no unit test covers them; the fail-closed rule is verified by
  reading the code, not by a running test, same as everywhere else in `ebx.*` this codebase does not unit-test.
- The actual redaction decision (what to emit given the flags) lives in the PURE `lineage.RecordLineage`,
  not in the reader — see its own Javadoc and `RecordLineageTest#restrictedSource_onlySystemNameEmitted`/
  `#restrictedFieldOnOneOfTwoSources_valueOmittedDiffersNull`/
  `#restrictedMasterField_droppedFromFieldsListedAsRestricted`/`#allVisible_unchangedShape` — including a
  defense-in-depth test that a restricted `FieldContribution`/`SourceChain` fixture DELIBERATELY still
  carrying real values is redacted anyway by `assemble()` itself, not merely by the reader remembering to
  null them out.
- **Deliberately not administrators-only**: the lineage screen is for data stewards, not just admins —
  per-record/per-field EBX permissions are the actual gate, matching what the UI would show that same user
  browsing the master/staging/landing tables directly in `ebx-ui`.

**Never run live this session** (the coordinator's own constraint stands for this fix too): the two
`SessionPermissions` signatures above are real (`javap`), and the logic was reasoned through and
unit-tested at the pure-model level, but no request against this route was ever made with a genuinely
restricted profile. Check before relying on this in production:

1. That `getAdaptationAccessPermission`/`getNodeAccessPermission` actually reflect table/field-level access
   rules configured in Administration (not just role/profile-level "hidden" toggles) — the local Javadoc
   under `.../webapps/ebx-manager/www/*/html/onHelp/advanced/Java_API/` documents the contract; a live probe
   with a restricted test profile against a real hidden field would confirm it end to end.
2. Whether `AccessPermission.isReadOnly()` (never checked here — this route only ever reads, so read-only
   vs. read-write was assumed irrelevant) should also gate anything; not expected to, but not tested.
3. Performance: one `getNodeAccessPermission` call per (field, contributing source) — cheap in isolation,
   never measured under load with many contributing sources.

## What was verified by `javap`

Against `ebx-ps-fasttrack-server/lib/ebx-addons.jar` (the same server project `ebx.MameMatchingApi`
already compiles against — `ebx-addons.jar` is on this module's classpath through the existing
`server_lib/*` wildcard in `build.sh`/`tools/test_local.sh`, confirmed by building this change with no
classpath edit needed):

```
public class com.orchestranetworks.addon.mame.common.util.metadata.MetadataHelper {
  public static java.math.BigDecimal getGroupId(com.onwbp.adaptation.Adaptation) throws InvalidParameterException, PathAccessException;
  public static com.orchestranetworks.addon.mame.model.State getState(com.onwbp.adaptation.Adaptation) throws InvalidParameterException, PathAccessException;
  public static boolean isAutoCreated(com.onwbp.adaptation.Adaptation) throws InvalidParameterException, PathAccessException;
  public static com.onwbp.adaptation.Adaptation getTargetRecord(com.onwbp.adaptation.Adaptation) throws InvalidParameterException, PathAccessException, InvalidStateException;
}
public final class com.orchestranetworks.addon.mame.model.State extends java.lang.Enum<State> {
  UNSET, GOLDEN, MERGED, PENDING_APPROVAL, DELETED;
}
```

`ebx.MameRecordState` (this change) calls exactly these four methods, one record at a time, with the
same `LinkageError`-guarded seam `~/Dev/ebx-connectors`' own `GoldenRecords`/`MameGoldenRecords` already
established (read in full before writing this class): only `AddonMameRecordState` references
`com.orchestranetworks.addon.mame.*`; a server without `ebx-addons.jar` degrades to
`merge.reason: ADDON_NOT_AVAILABLE` instead of a `NoClassDefFoundError`.

The reverse lookup ("every other record whose `goldenId` points at this golden") does **not** use the
add-on's Java API at all — `MetadataHelper` only answers forward, one record at a time. It reads
`RecordMetadata` as a plain EBX table (`Adaptation`/`AdaptationTable`, no add-on class involved), the
exact same metadataset-naming convention and read pattern `ebx.MasterLoader.RecordMetadataMergeCheck`
already established and this change reuses (`<masterDataspaceKey>_<masterDatasetName>_MDS`,
`/root/RecordMetadata`, filtered by `./goldenId=...`) — unverified beyond the single live example
`docs/MASTER-ZONE.md` read on `Companies`/`Companies`, same caveat that class's own Javadoc already
carries.

**Because that reverse lookup is an unverified assumption, `ebx.RecordLineageReader.computeMerge` guards it
and degrades on failure rather than letting it fail the whole answer (coordinator review, 2026-09-21).** An
XPath predicate on a field the table does not define THROWS in EBX, and so does `Adaptation.get` on an
undefined path — `computeMerge` checks `getTableOccurrenceRootNode().getNode(Path.parse("./goldenId")) !=
null` before ever building the predicate, and wraps the entire merge determination (from `MatchPlan.status`
through the reverse query) in one `catch (RuntimeException)` that turns ANY failure into
`merge.reason: METADATA_UNREADABLE` with `detail` = the exception's class and message (never a record
value) — the master ← staging ← landing chain is still returned, HTTP 200. `MameRecordState` itself already
never throws (every `MetadataHelper` call is individually caught — see its own Javadoc), so the residual
risk this specifically guards is the metadataset-naming convention and the reverse predicate above.

## What was NOT read, and is left as a seam

**Field-by-field, post-merge provenance (`MergeValueLineageCompact.Items[]`) is not implemented.**
`docs/MASTER-ZONE.md` §5.1 read this table's shape live on 2026-09-20 (`id`, `mergingProcessId`,
`sourceField`, and a repeatable `Items[]` group of `fieldPath`/`recordId`/`sourceIndex`/`goldenIndex`/
`mergeFunction`/`condition`/`customValue`/`sourceValue`/`manualModified`/`modifiedByDataSteward`). The
table's own SCALAR fields (`id`, `mergingProcessId`, `sourceField`) are ordinary columns, exactly as safe
to read as `RecordMetadata.goldenId` already is in this codebase — but `Items[]` is a REPEATABLE,
non-table complex group, and this session could not reach the live instance to confirm what
`Adaptation.get(Path)` actually returns for such a group in this EBX version (a `List` of `Object[]`? Of
sub-`Adaptation`s? Something else?) without guessing. Per the task brief's own instruction ("read it ONLY
if you can establish by javap/Javadoc a public, supported way to do so; otherwise leave a clearly named
seam ... do NOT reverse-engineer ... by guesswork") and CLAUDE.md §13's own recurring lesson ("any
internal/undocumented API ... should be treated as build-specific"), `ebx.FieldMergeLineageSource` is
that seam: a named interface holding the exact reason
(`FieldMergeLineageSource.NOT_VERIFIED`), surfaced in the response as `fieldLineageNote` whenever it
would matter (more than one contributing source), and nothing more.

**Check before relying on per-field golden provenance in the real screen:**

1. What `Adaptation.get(Path)` (or another documented accessor — check the on-disk Java API Javadoc,
   `.../webapps/ebx-manager/www/*/html/onHelp/advanced/Java_API/`, and/or `javap` the actual repeatable
   group's generated accessor class if one exists) returns for a repeatable, non-table complex group like
   `MergeValueLineageCompact.Items[]` — then implement a real `FieldMergeLineageSource` and read
   `mergingProcessId` off the golden's own `RecordMetadata` row to filter it.
2. `RecordMetadata.state`'s real enumeration values as EBX renders/stores them — `MameRecordState` only
   ever compares against `MetadataHelper`'s own typed `State` enum, never a raw string, so this is lower
   risk than `docs/MASTER-ZONE.md` §8 flagged it, but the ADD-ON's OWN behavior on an edge case (a record
   `MetadataHelper.getState` reports `UNSET` that is also NOT the golden of any group — treated here as
   `merge.present: true, state: "UNSET"`, one contributing source: itself) was not observed live.
3. Whether `RecordMetadataMergeCheck`'s metadataset-naming convention
   (`<masterDataspaceKey>_<masterDatasetName>_MDS`) holds when the master dataspace and dataset keys
   differ — the only live example available (`Companies`/`Companies`) has them equal; this reader inherits
   the same, already-flagged assumption.
4. The exact wording/shape a real merged group produces once `matching/run` has actually merged two
   `Company` rows for real (per `PLAN.md`, no matching has run yet on this project) — every JSON example
   above is illustrative, built from the pure `RecordLineageTest` fixtures, not a live capture.

## Screen

Written 2026-09-21 on branch `feature/record-lineage-screen` (worktree `ebx-mdm-factory-lineage-ui`),
implementing the UI half of lot 4 from `docs/mockups/mdm-factory.html` screen 5 ("Lineage de
l'enregistrement") and the read side documented above. **Never seen on screen this session** (the
coordinator's own constraint for this branch too) — to be validated by eye by the product owner.

- **Pure renderer**: `lineage.RecordLineagePage` (package `lineage`, next to `RecordLineage` itself) takes
  the `com.fasterxml.jackson.databind.JsonNode` that `RecordLineage.assemble`/`RecordLineageReader.read`
  already produce, plus a `Locale`, and renders the screen's HTML - no EBX class, no second Java model of
  the same shape. Rendering directly from that `JsonNode` (rather than re-modelling it, the way
  `review.ReviewPage` takes a typed `Input`) keeps the ONE place that decides what a restricted
  source/field/record-field may show (`RecordLineage.assemble` itself) as the only place - the page only
  ever shows exactly what is already in the JSON, it does not re-derive visibility from anything else.
  Every dynamic string is HTML-escaped (`RecordLineagePage.esc`, same algorithm as every other screen's
  `esc()`).
- **Where it deviates from the mockup, and why**: mockup screen 5's "Champ par champ" table has a "Valeur
  retenue" / "Source retenue" / "Règle de survivance" shape - a post-merge SURVIVORSHIP answer
  (`MergeValueLineageCompact`) that the read side documents above as a named seam, not implemented this lot
  (`FieldMergeLineageSource.NOT_VERIFIED`). The real table instead shows, per field, every contributing
  source's own raw/staged value and cleaning steps, and whether the sources agree (a "Differ"/"Agree"/
  "Unknown (restricted)" chip, the row highlighted when they differ) - never a fabricated "retained value"
  the read side never computed. The mockup's own two-source funnel layout (a hand-placed empty grid cell so
  a third row can visually merge two rows into one) is not reproduced literally either: a real record can
  have any number of contributing sources, so the journey renders one row per source followed by one merge/
  golden row (or the merge-absent notice in plain words, one of the four documented reasons above), rather
  than a layout that only works for exactly two.
- **The service**: `ebx.ui.RecordLineageUserService` (`RecordLineageServiceDeclaration`, service key
  `RecordLineage`), `OnTableView` on `Project` - the same choice as `ProposalReview`/`ProjectWizard`, so it
  can open with nothing selected. Calls `RecordLineageReader.read` in process with the caller's own
  `Session`, never an HTTP loopback - the module's own REST route and this screen share the exact same
  read path and the exact same permission redaction, on purpose. Every error `RecordLineageReader.read`
  throws (`NotFoundException`/`BadKeyException`/any other `OperationException`) already carries a
  human-readable message by that class's own design (see "What the route returns" above); the service
  surfaces it as the page notice and nothing else - never a stack trace. A header form (project → entity →
  key), posted back with the module's usual plain-HTML-and-hidden-submit-button technique.
- **Three ways in**:
  1. A documented deep link: `.../ebx/?branch=MdmFactory&instance=MdmFactory&xpath=/root/Project&service=
     RecordLineage&project=<project code>&entity=<entity name>&key=<formatted master key>` - the `/ebx/`
     web-component route (not a plain `ebx-ui` query string, which CLAUDE.md's own notes say drops a user
     service's plain URL parameters) passes `project`/`entity`/`key` through as the service's own launch
     parameters, declared with `WebComponentDeclarationContext.addInputParameter` in
     `RecordLineageServiceDeclaration` and read with `UserServiceRequest.getParameter` in
     `RecordLineageUserService.setupObjectContext`. **Not verified live this session** - every other
     service-launch parameter example in CLAUDE.md/this module's own memory was measured on this instance,
     but this exact one (a plain, non-record-selection input parameter on a table-level service reached
     through this route) was reasoned from those examples, not measured fresh.
  2. A toolbar action on a selected `Project` record pre-fills the project (same resolution shape as
     `ProposalReviewUserService`'s own `setupObjectContext`).
  3. The perspective action ("Record lineage", section Pilotage/Operations, next to "Proposal review") opens
     with nothing selected, the project selector shown first - added to `MdmFactory.perspective.json`, so an
     already-installed perspective picks it up at the next Tomcat restart via
     `PerspectiveInstaller`/`PerspectiveCompletion`'s own completion pass (matched by `serviceKey`, never
     re-added twice) - not seen running.
  A small link was also added to the Cockpit screen (`CockpitPage.lineageLink`, project code only, no
  entity/key - the Cockpit has no single record in view) without restructuring it, a plain `<a>` (Cockpit
  never loads `review.css`, the only place `.mdmf-btn` is declared) navigating the top window.
- **A record-level action on the master model itself is possible after all - correction, coordinator
  review, 2026-09-22**: the paragraph below (as it read through 2026-09-21) concluded this was not
  reachable, reasoning only about **schema-extension** registration - true as far as it went (the master
  model is DMA-generated, not an XSD this module packages, so it cannot declare a
  `UserServiceDeclaration`/schema-extension service the way `FactorySchemaExtensions` does for the factory's
  own tables), but incomplete: EBX also offers **module-level** registration
  (`ModuleServiceRegistrationContext.registerUserService`, CLAUDE.md §9's own "Registering a service from
  `ModuleRegistration`" note), which is offered on ANY table of ANY model, DMA-published ones included, and
  needs no `<osd:extensions>` entry anywhere. See "Record-level action on a master record" below for the
  mechanism actually built, and why the original reasoning ("cannot declare a service on it") does not
  generalize to every registration path EBX offers. Kept here, struck through in spirit rather than deleted,
  as a record of the earlier (incomplete) conclusion:
  ~~What a schema-extension service on the master model itself would take, not attempted this lot: the
  master model is DMA-generated (`data-model` MCP server), not an XSD this module packages, so it cannot
  declare a `UserServiceDeclaration`/schema-extension service the way `FactorySchemaExtensions` does for the
  factory's own tables (CLAUDE.md §9's own note: a service registered from a schema extension needs an
  `<osd:extensions>` entry in that model's own XSD). If the `data-model` MCP surface ever exposes a way to
  attach a toolbar/record service declaration to a DMA-published table..., the natural shape would be a
  record-level ("OnRecord") action on the master table itself, pre-filling `project`/`entity` from the
  project's own `masterDataspace`/`masterDataset` and `entity` from the table being viewed, with only `key`
  left for EBX's own record selection to supply... Left as a documented idea, not a task for this lot.~~
- **Tests**: `lineage.RecordLineagePageTest` (JUnit-free, `main`-driven, same style as `ReviewPageTest`) -
  every state from the read side's own documented shapes (single source, two sources differing/agreeing, a
  field's cleaning steps in human words and in order including `STRIP_ACCENTS`, a restricted source, a
  restricted field contribution, each of the four merge-absent reasons in plain words plus
  `METADATA_UNREADABLE`'s own detail, a golden record with two contributors, a restricted master record
  field), `"><script>` escaping in both a field value and the typed key text, a restricted contribution
  fixture that DELIBERATELY still carries real values (same defense-in-depth discipline as
  `RecordLineageTest`'s own fixtures) proving the page never leaks them, and both locales resolving every
  label with no raw `lineage.*` key ever shown. `CockpitPageTest` gained one test for the new link
  (URL-encoding, `target="_top"`). All built directly from `RecordLineage.assemble` fixtures, never a
  hand-written JSON string, so a fixture can never silently drift from what the read side actually emits.
- **What was NOT verified, honestly**: this session could not reach a live EBX instance or a browser (the
  coordinator's own constraint for this branch) - nobody has seen this screen rendered by a real browser,
  clicked a button on it, or confirmed the deep link's `addInputParameter` mechanism actually reaches
  `setupObjectContext` the way this document claims (reasoned from CLAUDE.md's own notes on other services'
  launch parameters, not measured fresh for this exact case). The perspective menu entry has never been seen
  added by a real restart, and the Cockpit link has never been clicked. Validate all of this by eye before
  relying on it.

### Graphic rebuild (2026-09-21 night, from the validated mockup, branch `feature/record-lineage-graphic`)

The v1 screen above (server-rendered tables) is REPLACED as the primary screen by an SVG/HTML diagram built
from `docs/mockups/record-lineage.html` (four sources; two-source variant `record-lineage-2-sources.html`;
generator `build_record_lineage.py`), which the product owner validated the same night ("Je valide tout ça", on
images, from his phone - PLAN.md). The v1 tables are KEPT, unchanged, as the `<noscript>` fallback for a
browser with JavaScript disabled - never deleted, per the task brief.

- **What draws each part, from which data, today**:
  - **1 - Parcours**: fully drawable from `RecordLineageReader.read`'s own bounded chain - one lane per
    contributing source (up to 8 shown at once, a "N more sources" control reveals the rest - all are
    already in the JSON, the reader has no source-count cap of its own to report honestly), cleaning-step
    chips (kind codes only, matching CLAUDE.md's own "shows what ran, not its arguments"), the matching ring
    (group id + contributing-record count + state, in plain words) and the golden card. **Not drawable**:
    the ring's match SCORE and the per-source PROXIMITY bars - no field anywhere in `RecordLineage.MergeInfo`
    or the add-on's own read side ever carries either, so the ring never shows a number and the proximity
    block renders its own explicit "Not available yet - needs the first golden record" line rather than a
    fake one. The composition bar ("who supplied what") needs a real per-field RETAINED source; while none
    is known (see below - today, always), it shows the neutral "N sources - M field(s) agree - K field(s)
    differ" summary instead.
  - **2 - Champ par champ**: golden value first, one column per source, per-cell state (`RETAINED` filled +
    "RETAINED (DEDUCED)"/`"RETAINED"` badge, `EQ`/`DIFF` with the raw value struck through when cleaning
    changed it, `EMPTY`, `LOCK` for a restricted contribution). **The retained cell is DEDUCED, not read**:
    `lineage.RecordLineage#assemble` (not the reader, not the graphic builder - the task brief's own
    instruction, "put this rule in the PURE model with tests") counts, per field, how many non-restricted
    contributions carry EXACTLY the record's own current value; a unique match is a logical certainty (only
    that source could have supplied it) and is marked retained/`DEDUCED`; zero or more than one match is
    genuinely ambiguous and nothing is retained - covered by `RecordLineageTest#deducedRetained_*`. A REAL
    survivorship answer (`FieldMergeLineageSource`, still `NOT_VERIFIED` - unchanged this lot) would arrive
    as `FieldInput#knownRetainedMasterKey`; `assemble` already prefers it over the deduction whenever it is
    non-null (`retainedReason: "KNOWN"` instead of `"DEDUCED"`) - the ONLY change a future implementation of
    that seam needs is the one call site in `RecordLineageReader` that builds `FieldInput`, nothing in the
    model, the graphic builder or the client script. The composition bar (part 1) deliberately only counts
    `KNOWN` answers, never `DEDUCED` ones, in its per-source breakdown - see `RecordLineageGraphic`'s own
    Javadoc for why a field-level inference is not strong enough evidence for a record-wide claim.
  - **3 - Frise**: real per-source `receivedAt` dates, sorted; nothing else in the read model carries a
    timestamp (no per-record "loaded into the master zone" moment, no match/merge date, no steward name -
    `RecordLineage.MergeInfo` carries state/group/golden key only). So the timeline shows a dateless
    "Cleaned and loaded" milestone (cleaning steps DID run - only WHEN is unknown), then a dateless
    match/merge milestone when `merge.present`, or a single dateless "Not reached yet" marker with the real
    reason otherwise - never the mockup's own example "22/09 - 09:20 by J. Martin", which this screen cannot
    back up.
- **"Open in EBX" links**: `RecordLineageReader` now also builds `masterUrl`/`landingUrl`/`stagingUrl`
  (`RecordLineage.Input#masterUrl`, `SourceChain#landingUrl`/`#stagingUrl` - additive constructor overloads,
  every existing caller/fixture unaffected) from each record's OWN primary-key VALUES via
  `AdaptationTable.getPrimaryKeySpec()` + `Adaptation.get(Path)`, quoted with the same `CatalogReader.quote`
  helper this file already uses elsewhere - never by splitting/re-parsing a formatted key string (CLAUDE.md
  §9's "never build or split a composite primary key as a string"). The link itself is the documented
  `/ebx-ui/ui/advanced/data/B<dataspace>/<dataset><tablePath>?predicate=...` pattern (CLAUDE.md §9); the
  client script rewrites `ui/advanced/data/` to `ui/custom/<perspectiveKey>/data/` when it detects a
  perspective (the catalog module's own `fixUrl`, `journey.js` line 11, reused verbatim). Never even
  attempted for a restricted source - `assemble` never emits a url for one regardless of what the reader
  computed. **Not verified against a live instance this session** (task constraint) - re-check this exact
  URL shape before trusting it in anger, the same caveat every other REST/link technique in CLAUDE.md §9
  carries until re-confirmed on the target instance.
- **Reused from the catalog module's Journey view, and how it ships** (task brief: "copy and adapt the few
  helpers... into the factory's own resources - no cross-module dependency"): the APPROACH, not the file -
  `ebx-data-catalog/.../ebx/ui/journey.js` and `LineageJourneyPane.java` were read in full (`~/Dev/ebx-data-
  catalog`, read-only this lot). What was carried over into this module's own
  `lineage/lineage-graphic.js` (a new file, ships as a classpath resource next to `RecordLineagePage.java`,
  loaded via `Class#getResourceAsStream` and embedded through `UserServicePaneWriter#addJS_cr` - never a
  cross-module dependency, never a CDN): the `el(tag, attrs, parent, text)` SVG-builder helper
  (`createElementNS` + `textContent`/`setAttribute`, never `innerHTML` with data), the `fixUrl` perspective
  rewrite, the `interactive(el, cb)` a11y click handler, and the embedding-escape technique in
  `LineageJourneyPane` (`<`/`>`/`&` to `\uXXXX`, plus U+2028/U+2029 - `RecordLineagePage#graphicScript`).
  What was NOT carried over: the catalog's own REST-paginated multi-hop loading (`expand`/`loadFields`/
  `cursor`/`busy` in `journey.js`) - this screen's read side is already fully bounded (one row per
  contributing source, read once), so there is nothing left to fetch after the first render; the "N more
  sources" control only reveals rows already present in the embedded JSON, it never calls the server again.
- **A deliberate adaptation from the mockup, for the real screen**: "Champ par champ" (part 2) is an HTML
  `<table>`, not SVG - the mockup's own hand-placed SVG bus-line from the retained cell to the golden column
  does not survive a horizontally scrolling, sticky-first-column table (the task brief's own requirement for
  more than 5 source columns). A coloured BOTTOM BORDER run from the golden column through every intervening
  column up to the retained one reads the same way ("a line of that colour running under the row") and
  survives scroll/sticky positioning, which an absolutely-positioned SVG overlay would not - see
  `lineage-graphic.js`'s own comment on `buildFieldsTable` for the exact reasoning. Parts 1 and 3 stay true
  SVG, like the mockup.
- **Colour assignment**: `lineage.SourceColorPalette` - a fixed 8-colour, colour-blind-tolerant-by-design
  palette (varying hue AND lightness, never hue alone), assigned by a STABLE hash of the source system's own
  name (`String#hashCode`, specified stable across JVM runs by the `String` Javadoc) rather than by
  first-seen order or any registry - "stable for a given system name within a project" falls out for free,
  with no durable state to keep in sync, at the cost of a possible collision between two different system
  names (8 buckets, an unbounded name space - a real, accepted limitation, tested in
  `SourceColorPaletteTest`). Purple (`#684bc0`, the mockup's own `ACC`) is reserved for the golden record and
  never handed to a source.
- **A real, pre-existing bug found and fixed while sanity-checking this rebuild**: `factory/lineage.css`'s
  own header comment (written for the v1 screen, before this lot) contained a literal `*/` inside a sentence
  ("...-chip*/-table*...", meant as informal wildcard notation, read as an accidental end-of-comment) -
  EVERY rule in that file, including all of the v1 journey/field-table styling, silently never applied
  anywhere it was loaded. Found by checking `document.styleSheets` in a real browser while sanity-checking
  the new graphic screen below (0 `cssRules` for `lineage.css`), fixed by rewording the comment to avoid an
  asterisk immediately followed by a slash, verified with a small Python script counting `/*`/`*/`
  occurrences (5 and 5). This means the v1 "Lineage" screen's own custom styling (`.mdmf-journey`,
  `.mdmf-jnode`, etc.) has likely never rendered as intended on a real instance either, from the day it
  shipped - worth a quick look the next time anyone opens the OLD (`<noscript>`) fallback for real.
- **Tests**: `lineage.RecordLineageTest` gained the deduced-retained rule (unique match -> deduced, two
  matches -> none, no match -> none, a `KNOWN` answer overriding the deduction, no golden value -> never a
  guess, a restricted contribution never retained even when it would otherwise match, and the new
  `masterUrl`/`landingUrl`/`stagingUrl` fields carried through and redacted for a restricted source with the
  same "fixture deliberately still supplies one" discipline as every other redaction test here).
  `lineage.RecordLineageGraphicTest` (new) covers 1/2/4/9-source fixtures (9 proving the 8-source visibility
  cap), restricted-source and restricted-contribution redaction INSIDE the graphic JSON specifically (not
  just the underlying `RecordLineage` JSON), the composition bar's fallback-vs-known-answer switch, the
  timeline's "never fabricate a date" rule for every event kind, and both locales' labels resolving with no
  raw key. `lineage.SourceColorPaletteTest` (new) covers stability, never colliding with the golden accent,
  case/whitespace/null handling, and the mockup's own four systems staying distinct in practice.
  `lineage.RecordLineagePageTest` gained the `<noscript>`-fallback-only assertion and `graphicScript`'s own
  XSS-safety (a `"><script>`/`</script>` payload in a field value cannot break out of the embedded `var
  data = ...;` statement) and redaction (a restricted contribution's deliberately-still-carried value never
  reaches the embedded script either).
- **What was NOT verified, honestly, on top of the v1 screen's own list above**: nobody has opened this
  screen inside a real EBX perspective, clicked an "open in EBX" link and confirmed it lands on the right
  record, or watched the "N more sources"/detail-panel/field-highlight interactions run against real data
  with real permissions. What WAS checked: the pure Java renderer's own HTML/JS/CSS output, loaded in an
  actual browser (via the Browser tool, over a plain local HTTP server - `file://` does not execute
  scripts) against hand-built fixtures (a 4-source BPCE-like fixture matching the mockup's own example
  data, and a 9-source fixture to exercise the "more sources" reveal) - a genuine sanity check of the exact
  bytes the module ships, but NOT a live EBX render, and specifically not a check of `setupObjectContext`,
  the deep link, the perspective entry, or any permission redaction against a real restricted profile.

### Coordinator review, 2026-09-22 - nine visual gaps closed, a colour-ordering rework, and a second CSS bug

the product owner rendered the branch's own `preview.html` and compared it, pixel by pixel, against
`docs/mockups/record-lineage.png`. Nine gaps were found and closed; fixing them changed how several parts
of the read/model side work, not just the drawing:

1. **A ribbon now runs from the matching ring to the golden card** (`lineage-graphic.js`, a 24px gradient
   path drawn BEFORE the card so the card's own fill cleanly covers the ribbon's end) - the earlier version
   drew every ribbon up to the ring and stopped there.
2. **The group caption moved to the top of the MATCHING band** ("Group 118 - 4 records", left-aligned under
   the band title), and the state words moved to sit CENTRED under the ring, at most two short lines -
   neither used to float over the converging ribbons the way a caption placed near the ring itself did.
   **A real fixture bug was caught in the same pass**: the session's own preview harness had set
   `golden: true` on the very entry it was also describing as `state: "MERGED"` - self-contradictory (a
   record cannot be the golden AND merged into another one). Confirmed this is NOT a defect the real model
   can produce (`ebx.RecordLineageReader`'s own `computeMerge`: `merge.state` describes the EXAMINED
   record's own `MameRecordState`, which reports `GOLDEN` when the examined record IS the golden - `MERGED`
   only applies when looking up a different, non-golden member), fixed the fixture, and added
   `RecordLineageGraphicTest#goldenRecordLookedUpDirectly_neverShowsMergedWording` to lock the combination
   in as a test rather than trust the invariant by convention alone.
3. **The golden card no longer overflows**: `lineage-graphic.js`'s new `wrapText(text, maxChars)` splits the
   composition-fallback and proximity sentences into lines BEFORE the card's own height is fixed, so the
   card grows to fit its content instead of clipping it. **The whole parcours now uses the mockup's own
   fixed 1200-ish-wide coordinate system** (`build_record_lineage.py`'s own band x/widths, generalized) with
   the SVG set to `width="100%"` + `preserveAspectRatio="none"` and an explicit pixel `height` - the earlier
   version set the SVG's own `width` attribute to the same value as its `viewBox`, which never scales to
   fill a wider container (the "dead strip on the right" the product owner saw).
4. **Cleaning-step chips now show short human words** (`lineage.step.<KIND>`, both `en`/`fr` bundles - see
   `RecordLineageGraphic#stepShortWords`, pure and tested) instead of the raw `MappingStep.kind` enum value;
   the full `ProposalWording` sentence moved to the chip's `title` attribute and the detail panel.
5. **Chips moved into the landing-to-staging GAP**, which was widened from the mockup's own 85 design units
   to 130 so two chips plus a "+N" overflow chip (whose `title` lists the rest) never spill onto the
   staging card - `drawCleaningChips` in `lineage-graphic.js`.
6. **Colour assignment now follows the PROJECT's own declared source order, not a name hash.**
   `RecordLineage.SourceChain` gained `sourceOrder` (nullable `Integer`, additive constructor overload -
   every existing caller/fixture unaffected), populated by `ebx.RecordLineageReader` from `ProjectSource.
   trustRank` ("lower wins" - the SAME field the eventual merge policy's most-trusted-source order already
   reads, `WizardRules`/`WizardSnapshot`'s own Javadoc) via a new `readSourceTrustRank` read. Reusing an
   EXISTING field rather than inventing a new one: it is already a per-project, per-system, stable priority
   number, exactly what "the order of the project's sources" needs. `SourceColorPalette` gained
   `colorForOrdinal(int)` (a plain palette position - the PRIMARY strategy now) alongside the original
   `colorFor(String)` (kept as a defensive, name-hash fallback for a system with no resolvable order - see
   both classes' own Javadoc). `RecordLineageGraphic#assignColors` computes ONE map (sourceOrder ascending,
   ties/absences broken alphabetically by system name, exactly as instructed) from EVERY entry in `sources`
   (restricted ones included - a hidden source's system NAME is not itself sensitive, and excluding it would
   make every OTHER system's colour depend on who is looking, which is precisely the instability this
   ordering exists to prevent) and threads it through sources, field contributions, the composition
   breakdown and the timeline - one shared assignment, never several that could disagree
   (`RecordLineageGraphicTest#colorAssignment_sameAcrossSourcesAndFieldContributions`). The palette itself
   was reordered/recoloured for contrast between CONSECUTIVE entries (blue/teal/amber/rose - the
   coordinator's own explicit four - then olive/sky/brown/slate).
7. **"Champ par champ" gained the connector, box styling and badges the mockup actually depends on**: the
   golden cell now carries a LEFT colour bar (the retained source's colour, neutral when none), each cell is
   a proper box (retained = soft fill + 2px border + a "RETAINED"/"RETAINED (DEDUCED)" small-caps label,
   agree = white + grey border + a round "=" badge, differ = white + grey border + struck value + a round
   "&ne;" badge, empty/locked = dashed box), and every badge sits at the cell's own TOP-RIGHT corner
   (`position:absolute` inside a `position:relative` `<td>`) rather than under the value. The connector
   itself (golden &lt;-&gt; retained) is drawn by a SEPARATE pass, `drawFieldConnectors`, AFTER the table is
   built: it reads each relevant cell's actual `offsetLeft`/`offsetTop` (stable across scroll - only the
   visual `scrollLeft` changes, not the offsets) relative to a `position:relative` wrapper
   (`.mdmf-fields-inner`) shared with a small SVG overlay, and draws an orthogonal line with an arrowhead
   into the golden cell. The overlay's `z-index` sits BELOW the sticky golden column's, so a segment that
   would be drawn under the column when scrolled is naturally hidden by that column's own opaque background
   - see `lineage.css`'s own comment for the exact stacking numbers. A new, paired seam,
   `FieldInput#survivorshipRule` (a short human sentence, alongside `knownRetainedMasterKey`), lets the
   golden cell show the rule text "only when known" (coordinator's own words) - null today, like
   `knownRetainedMasterKey` itself, since `FieldMergeLineageSource` remains unimplemented; both would arrive
   together from the same real seam (`MergeValueLineageCompact.Items[].mergeFunction`).
8. **The record panel above the graphic is now compact** (title/project/entity/table + an "open in EBX"
   link) instead of repeating a field/value table that "champ par champ" already shows, WITH provenance the
   old table never had. The full field/value table still exists - it moved into the `<noscript>` fallback
   (`RecordLineagePage#renderRecordPanel`, called there instead of always).
9. **The parcours no longer wastes vertical space**: lane pitch 85, card height 60, first lane at y=85, band
   height = last lane + 80, ring vertically centred on the lanes - the mockup's own numbers, generalized to
   any lane count, replacing an earlier, taller and less considered layout.

**Also folded into this pass** (from the same review, not the numbered list above):
- **Merged `main` first** (`c8ed0a9`, `feature/declare-lineage-to-catalog`) - no conflicts outside `PLAN.md`
  (both entries kept).
- **"Open in the catalog" links on the STAGING/MASTER zone bands**, built exactly as `docs/CATALOG-LINEAGE.
  md`'s own "Reverse (factory's Record Lineage screen -> catalog's Journey view of a table)" section
  documents (`<ebxBaseUrl>/ebx-ui/ui/advanced/data/B<catalogDataspace>/<catalogDataset>/root/Asset/<id>`) -
  a new `CatalogReader.findAssetId(catalog, qualifiedName)` (a plain `./qualifiedName=` lookup on the
  catalog's own `Asset` table, reusing `CatalogLineageBuilder.ebxName`'s naming convention so it only ever
  matches the SAME asset the factory's own `/openlineage` declaration would have created) and
  `RecordLineageReader.catalogUrl`, detected purely by data (`CatalogReader.dataset`, never a hardcoded
  dataset name - no compile dependency on `ebx-data-catalog`). **Deliberately no LANDING link**: unlike
  staging/master (one table for this whole screen), a record's landing zone can have a DIFFERENT table per
  contributing source, so a single band-header link would be ambiguous about which one it points at.
  **Not verified against a live catalog this session** - same caveat `docs/CATALOG-LINEAGE.md`'s own
  "Reverse" section already carries (the exact query parameter, if any, to land on the "lineageJourney" tab
  specifically was never confirmed) - built exactly as documented anyway, per instruction.
- **A second, smaller instance of the `lineage.css` comment bug, found and fixed the same way**: both
  `factory/wizard.css` (two occurrences: `...-chip*/-table*...` and `...btn*/-conf*...`) and
  `factory/review.css` (one: `...-chip*/-table*...`) had the SAME accidental-early-comment-close shape as
  `lineage.css`'s own bug from the night before, each in their own header comment - but a much smaller
  blast radius: measured LIVE, before/after, by loading both the buggy and the fixed copy of each file
  SIDE BY SIDE in a real browser and diffing `document.styleSheets[...].cssRules`:
  - `wizard.css`: 75 rules before, 76 after - the ONE lost rule was `.mdmf-wizard { max-width: 1360px;
    margin: 0 auto; padding: 6px 6px 28px }`, the screen's own top-level width/centering wrapper (the very
    FIRST rule right after the broken comment - every rule after IT parsed fine, so the corruption cost
    exactly one rule, not the whole file, unlike `lineage.css`).
  - `review.css`: 49 rules before, 50 after - the ONE lost rule was `.mdmf-review { max-width: 1320px;
    margin: 0 auto; padding: 6px 6px 28px }`, the same kind of wrapper rule, same reason (first rule after
    the break).
  - Losing only a max-width/centering rule explains why "those screens looked right to the product owner" (task
    brief's own hypothesis) - the visual difference (content stretching edge-to-edge instead of centering
    at 1320-1360px) is easy to miss, unlike `lineage.css` losing every rule outright.
  - Both fixed the same way as `lineage.css`: reworded the header comment to avoid an asterisk immediately
    followed by a slash anywhere in it - including, on the SECOND attempt, in the sentence EXPLAINING the
    bug (the first draft of the note literally quoted the broken text, reintroducing the same two-character
    sequence and re-breaking the very comment describing why not to do that - caught immediately by
    recounting raw `/*`/`*/` occurrences, see next point).
- **`tools/test_local.sh` gained two checks, and one existing check was made portable**:
  - The existing ASCII-only check for `.properties` files used `grep -P`, a GNU extension - BSD `grep`
    (macOS) rejects the flag with "invalid option" and the script's own `if` swallowed that failure
    silently, meaning the check had been a SILENT NO-OP on this machine the whole time. Replaced with a
    portable POSIX bracket range (`[^ -~<TAB>]`, built with a literal tab from `printf` since neither BSD
    nor GNU `grep` can be trusted to accept a `\t` escape inside a bracket expression) - two lines, as
    asked. **This caught a real, live regression while writing this exact section**: several `lineage.
    graphic.*`/`lineage.step.*` French labels added earlier in this task had been written with what were
    INTENDED as `\uXXXX` escapes but arrived on disk as raw UTF-8 bytes instead - a tool-call string
    containing a literal `é` gets decoded to the real character `é` before it ever reaches a file, the
    same class of trap as the CSS comment bug, one layer up the toolchain. Fixed by a small Python pass that
    reads each `.properties` file as UTF-8 and re-escapes every character above U+007F byte-for-byte -
    verified clean (0 non-ASCII bytes) across every `.properties` file in the module afterward.
  - A new check counts raw `/*`/`*/` occurrences in every `factory/*.css` file and fails on any imbalance -
    plain CSS never legitimately contains either sequence outside a comment delimiter, so this is a simple,
    robust catch for exactly the bug class above, without attempting to actually strip comments or
    reimplement a CSS parser. Verified it fires on a deliberately reintroduced imbalance before trusting it.
- **Colour/label additions covered by new or extended pure tests**: `SourceColorPaletteTest` (ordinal
  stability, the coordinator's exact first four, wrap-around past 8), `RecordLineageGraphicTest` (order-
  based assignment when `sourceOrder` is present, alphabetical fallback when it is not, one shared map
  across sources and field contributions, short step-label lookup with a raw-kind fallback, the
  survivorship-rule seam only ever surfacing for a KNOWN answer, the two catalog URLs passing through
  verbatim when present and never fabricated when absent).
- **Re-rendered and visually re-checked**: the SAME two fixtures as the first pass (4-source BPCE-like, 9-
  source for the "more sources" reveal), this time captured as real PNGs with headless Chrome
  (`--headless=new --force-device-scale-factor=2`, 1320px window) rather than only viewed live in the
  Browser tool - see the session's own report for the exact paths. Confirmed by eye against
  `docs/mockups/record-lineage.png`: the ribbon, the relocated caption/state text, the wrapped golden card,
  the short chips in the widened gap, the four distinct ordered colours, the connector with its arrowhead
  and top-right badges, the compact header, and the tighter parcours height. The connector-under-scroll
  behaviour was ADDITIONALLY checked by actually scrolling the fields table (`scrollLeft`) and
  screenshotting: the golden column stays in place, the connector still correctly reaches the now-scrolled-
  into-view retained cell, and nothing leaks out from under the sticky column. **Still not verified**: a
  live EBX render, and the catalog band links against a real catalog dataset (no catalog dataset was ever
  installed or scanned this session).

### Coordinator review, 2026-09-22 (second pass) - seven remaining gaps

the product owner reviewed the PNGs from the pass above against `docs/mockups/record-lineage.png` again and found
seven more, smaller gaps - all closed on the same branch, `main` unchanged (`c8ed0a9`, no merge needed):

1. **Cleaning chips now MEASURE against the gap** (`lineage-graphic.js#drawCleaningChips`): each chip's real
   width comes from `SVGTextElement.getComputedTextLength()` on a throwaway probe text node (drawn, measured,
   removed), never an estimate - a chip that would cross into the staging card instead folds into the "+N"
   overflow chip. Short labels now use a real arrow (`→`, both `en`/`fr` bundles) instead of `->`.
2. **The ring shows the group's own RECORD COUNT** (a big number + small-caps "RECORDS") instead of sitting
   empty - documented in `lineage-graphic.js` as a ONE-LINE SWITCH for later: the day a real match score
   becomes readable, replace the number with the score and move the record count up into the band caption
   instead; nothing else about the ring's geometry needs to change.
3. **Dates are formatted server-side**, `RecordLineageGraphic#formatInstant`
   (`DateTimeFormatter.ofLocalizedDateTime(MEDIUM, SHORT)`, an explicit `Locale`/`ZoneId` - a NEW 3-argument
   `build` overload takes the zone explicitly, purely for test determinism; the 2-argument overload used in
   production defaults to `ZoneId.systemDefault()`, the "server's own zone"). Display only, never a value
   written back into EBX - CLAUDE.md §14's `LocalDateTime.toString()`/`ISO_LOCAL_DATE_TIME` trap is about a
   value a STRICT PARSER reads back; this string only ever reaches a browser. The landing card gained the
   mockup's own third line ("received {date}", `lineage.graphic.receivedOn`) from the same formatted value.
4. **The record's own NAME, not its bare key, is the title** - a new `RecordLineage.Input#masterLabel`
   (`ebx.RecordLineageReader` reads `Adaptation.getLabel(Session)`, falling back to null - "no real label" -
   when it is blank or literally equal to the formatted key) drives both the compact header
   (`RecordLineagePage#renderRecordHeaderCompact`) and the golden card's own title; the key moved into the
   subtitle line ("Company · 1 · 4 source(s)") rather than disappearing. Escaped exactly like every other
   dynamic string, with its own XSS test (`RecordLineagePageTest#scriptInMasterLabelIsEscapedNeverExecutable`).
5. **The colour palette grew from 8 to 12 entries** (`SourceColorPalette`, first four kept EXACTLY as the
   coordinator's own values) - past the 12th distinct system, `SourceColorPalette#isExtended` marks that
   lane's colour as REUSED, and `RecordLineageGraphic#assignColors` now returns a (colour, style) pair per
   system rather than a colour alone; the client script draws a "dashed"-style lane's ribbon, lane-bar and
   legend/header dot as dashed/hollow instead of solid, so two lanes sharing a colour never look identical.
   Tested down to the exact pair-uniqueness claim (`SourceColorPaletteTest#thirteenSources_
   noTwoColorStylePairsEqual`, `RecordLineageGraphicTest#styleIsDashedPastThePaletteSize` through the real
   `sourceOrder`-driven assignment path, not just the raw palette).
6. **The timeline is a real horizontally scrollable strip** (`overflow-x:auto` already existed; the baseline
   now spans EXACTLY the first point to the last one, point spacing raised to 170, and - the part that
   needed a decision - for few points that already fit the card the drawing STRETCHES to the card's full
   width (`friseHost.clientWidth` measured at draw time, `width:100%`/`preserveAspectRatio:xMinYMid meet`)
   while for many points it keeps a larger, legible fixed pixel size and lets the card scroll instead of
   shrinking every point down. `lineage.css` also forces an always-visible thin scrollbar
   (`::-webkit-scrollbar`/`scrollbar-width:thin`) on all three scrollable canvases, since macOS's default
   "overlay" style hides the track entirely until the user actually drags it - which, in a static
   screenshot, reads as "the content is just cut off" rather than "this scrolls".
7. **A 9-source fixture WITH a genuine retained field** (not just empty `fields`) confirmed the connector
   survives horizontal scroll correctly: the retained source (the one immediately next to the sticky golden
   column - the worst case) scrolls fully OUT of view under the sticky column, and the connector line does
   not leak out from underneath it - the golden cell's own left colour bar (that source's colour) is the
   only remaining trace, exactly as intended. The scroll-to-position-N helper for the screenshot itself
   (`?scroll=N`, reading `location.search`) lives ONLY in the throwaway scratchpad harness that built the
   PNG - never in the shipped `lineage-graphic.js`.

**What was NOT verified in this pass either**: still no live EBX render, and the catalog band links are
still unverified against a real installed/scanned catalog dataset. The date formatting was checked with
fixed `Locale.ENGLISH`/`Locale.FRANCE` and a fixed `ZoneId` in a pure test, and separately eyeballed in the
rendered PNG (English, the session's own machine's default zone) - never checked against the EBX server's
actual configured zone, which was never reachable this session either.

### 2026-09-22, 10:12 live screenshot - the title fallback from item 4 above was still just the key

the product owner screenshotted the Record lineage screen on master record `Company` key 183 (project `Companies`):
the golden card title and the page heading both showed **"183"**, although the record's `name` field holds
`"BPCE"`. Item 4 above (`Input#masterLabel`, `RecordLineageReader.masterLabel`) already fell back to `null`
- "no real label" - whenever `Adaptation.getLabel(Session)` was blank or equal to the formatted key, and every
renderer already treated `null` as "show the key instead" (`RecordLineagePage#renderRecordHeaderCompact`:
`pLineage.hasNonNull("masterLabel") ? ... : masterKey`). That was the correct thing to do for a label that
genuinely carries nothing better - but it stopped one step short: the generated master model itself carries no
`osd:table/defaultLabel` (`master.MasterModel` builds a table's `label` tree property, shown in the schema
browser, but never wires an `osd:table/defaultLabel` XPath expression - see CLAUDE.md §5's own distinction
between the two), so EBX's own label mechanism had nothing to fall back to either, and `getLabel(Session)`
simply echoes the key back - exactly the case `masterLabel` was built to detect, but with nowhere better to
fall back TO.

**Fixed in two places, both pure and unit-tested (`lineage.RecordTitleRule`, no EBX class in the file)**:

1. **Read time** - `RecordLineageReader.masterLabel` now calls `RecordTitleRule.pickTitle(label, key,
   candidateFields)` instead of giving up at "label is blank/equals the key": it falls back, in order, to a
   field named (case-insensitively) `name`/`label`/`title`, then a `...Name`-suffixed field (`accountName`,
   `legalName`), then the first other eligible string business field (never the primary key, never
   `sourceSystem`/`sourceId`, never a `_`-prefixed technical field, never a derived `FOLD_KEY` matching-key
   attribute - the same exclusions the field-by-field table already applies) - only ever the key itself if
   NOTHING qualifies. `candidateFields` is built by a new `RecordLineageReader.titleFieldCandidates`, which
   applies the SAME per-field `isFieldHidden` check every other value in this class goes through - a
   restricted field is simply never offered to the rule, exactly like every other permission boundary in this
   reader (never flagged, never partially shown). **Same fix applied to the group's own matched/suspect
   members** (`RecordLineageReader.buildGroupMember`, which already called the shared `masterLabel` helper -
   reusing it, rather than a second copy, was enough): a group member is by construction another record of the
   SAME table/entity as the anchor, so the SAME candidate field-name list (`titleFieldNames`, computed once per
   `read()` call) applies unchanged, threaded through `computeMerge`/`buildGroup`/`buildGroupMember`.
2. **Model-build time, the actual root-cause fix** - `master.MasterModel.buildTable` now also sets a NEW
   table's `osd:table/defaultLabel` directly, via a new table-level `set_element_property` op
   (`element_path` = the bare table path, never a field path - a new 3-argument `MasterModel.setElementProperty`
   overload, kept alongside the existing 4-argument field-level one) - `${./name}`/`${./legalName}`/etc.,
   the exact templated-expression form every OTHER table in the deployed `MdmFactory.xsd` already uses for its
   own `defaultLabel` (confirmed by reading the deployed schema directly, e.g. `<defaultLabel>${./code}</defaultLabel>`,
   `<defaultLabel>${./project} - ${./sourceSystem}</defaultLabel>`). The SAME three-tier priority as
   `RecordTitleRule.pickTitle`, applied to field NAMES only (no record exists yet) via a second pure entry
   point, `RecordTitleRule.pickDisplayFieldName` - restricted to plain STRING business attributes with no
   `referenceEntity` (a `*Ref` field points at ANOTHER record, never a display value of this one). This makes
   EVERY OTHER EBX screen benefit too (the plain record form's own title bar, a foreign-key picker elsewhere in
   this same master model, Data Services' own record listings) - not just this one screen's own read-time
   fallback.
   - **On regeneration, an EXISTING table is never touched.** `StagingModelDiff`'s generic diff engine
     associates a `set_element_property` op with a FIELD by matching `element_path` against a field's own path
     (`tablePath + "/" + fieldName`) - a table-level op (bare `tablePath`) never matches any field path, so it
     is silently invisible to that engine either way, the SAME blind spot that already makes
     `MasterModel.Result#autoIncrementOps` need the identical manual handling. `ebx.MasterGenerator.
     runRegeneration` now appends `MasterModel.Result#defaultLabelOps` right alongside `#autoIncrementOps`, in
     the SAME `if (existingNames.contains(entityName)) continue;` loop that already skips auto-increment
     re-application for a table that already exists - so a NEW table gets a real title from day one, and an
     ALREADY-PUBLISHED table's title convention (whatever it is - the generated default, or something an
     administrator has since set by hand) is never silently overridden. This was a deliberate choice, not
     laziness: `set_element_property` on `defaultLabel` was never proven idempotent this session, and even if
     it were, re-running it on every regeneration would still risk clobbering a manual override with no way to
     tell the difference from source alone.
   - Tested in `MasterModelTest` (`defaultLabel_pointsAtNameField_whenPresent`,
     `_pointsAtSuffixNameField_whenNoBareName`, `_fallsBackToFirstEligibleField_whenNoNameLikeField`,
     `_absentWhenNoEligibleField`, `_neverPicksForeignKeyOrNonStringField`) and in `RecordTitleRuleTest`
     directly (both entry points, every tier, blank/restricted-candidate handling, case-insensitivity).

**Not verified live** (same constraint as every other section of this document): neither half of this fix has
been deployed, published, or regenerated against a real EBX instance. What the product owner must check once it is:
(1) the Record lineage screen on `Company` key 183 shows "BPCE" as the title, not "183"; (2) a NEWLY promoted
master table (one generated AFTER this fix ships) gets a real `defaultLabel` visible in its own schema, and an
ALREADY-EXISTING table's schema is untouched by a regeneration run.

## Record-level action on a master record (coordinator review, 2026-09-22)

A fifth way into this screen, on top of the four the "Screen" section above already documents (deep link,
toolbar action on a `Project` record, perspective entry, Cockpit link) and **keeps every one of them exactly
as they were**: a **record-level ("Record lineage") action directly on a master record** - a steward looking
at a golden record in a project's master zone (e.g. dataspace `Companies_Master`, dataset `Master`, table
`/root/Company`) opens the same screen from the record's own Actions menu (record form, and table view with
one record selected), no factory navigation needed. Built on branch
`feature/record-lineage-record-action` (worktree `ebx-mdm-factory-lineage-record-action`), never deployed
or seen running - a `ModuleRegistration` change needs a full Tomcat restart (CLAUDE.md §9), and this session
never restarts anything (task constraint).

### The mechanism: module-level registration, not a schema extension

`docs/RECORD-LINEAGE.md`'s earlier note (see the struck-through paragraph in "Screen" above) concluded this
was impossible because the master model is DMA-generated and cannot declare a schema-extension service. That
conclusion only ever considered schema-extension registration. EBX separately offers **module-level**
registration - `ModuleServiceRegistrationContext.registerUserService(UserServiceDeclaration)`, called from
`FactoryModuleRegistration.handleServiceRegistration` (previously a bare `super.handleServiceRegistration`
call, per the task brief) - which is offered on **any table of any model**, DMA-published ones included: the
exact mechanism the FastTrack presales web project's own generic `DataProfilingServiceDeclaration`
(`com.on.ebx.common.ui.profiling`, registered from `ModuleRegistration`, table view of every table of every
dataset - read in full, `~/Fasttrack/Test 6.3.0/FT 6.3.0/Workspace/ebx-ps-fasttrack/
ebx-ps-fasttrack-web/src/main/java/com/on/ebx/common/ui/profiling/DataProfilingServiceDeclaration.java`)
already uses. `RecordLineageOnRecordServiceDeclaration implements UserServiceDeclaration.OnRecord`
(`com.orchestranetworks.userservice.declaration`), its `ServiceKey` built with
`ServiceKey.forModuleServiceName(moduleName, "RecordLineageOnRecord")` - `forModuleServiceName`, not
`forName` (CLAUDE.md §9's own "ServiceKey format" note: `forName` is for a schema-extension service,
`forModuleServiceName` for one registered from `ModuleRegistration`); the module name comes from
`ModuleServiceRegistrationContext.getModuleName()`/`module.xml`'s own `<name>ebx-mdm-factory</name>` (no
space, so the pattern-check warning that note describes never fires here). `OnRecord` shape precedent:
`ebx-data-catalog`'s `GlossaryEditServiceDeclaration`/`TagReviewServiceDeclaration` (schema-extension
registered, but the same `UserServiceDeclaration.OnRecord` interface, `RecordEntitySelection`, and
`ActivationContextOnRecord` shape a module-level registration also uses).

**One real structural fact worth restating**: `handleServiceRegistration` is invoked BEFORE
`handleRepositoryStartup`, and the EBX repository is *not yet initialised* at that point
(`Repository.getDefault()` would throw - the local Java API doc for `ModuleRegistration`, read 2026-09-22,
says so explicitly). `FactoryModuleRegistration.handleServiceRegistration` therefore only ever constructs
and registers the `UserServiceDeclaration` - it reads no data. Everything that actually touches the
factory's own data (`MasterZoneIndex`, below) is deferred to `handleRepositoryStartup`, and further deferred
inside that to the first real permission-rule evaluation (lazy, TTL-cached).

### Precise activation: the real API, verified by `javap` and the local Java API doc (both 2026-09-22)

the product owner's hard requirement: the action must be visible **only** on a project's own promoted master tables,
never on a landing table, a staging table, a factory table, or any other table in the repository. Against
`ebx-lib.jar` under `~/Fasttrack/Test 6.3.0/FT 6.3.0/Workspace/ebx-ps-fasttrack/
ebx-ps-fasttrack-server/lib/ebx-lib.jar` (the same jar `tools/test_local.sh` already compiles this module
against by default):

```
public interface com.orchestranetworks.userservice.declaration.ActivationContextOnRecord
    extends ActivationContextWithSchemaNodeSet<RecordEntitySelection> { }

public interface com.orchestranetworks.userservice.declaration.ActivationContextWithSchemaNodeSet<S extends TableEntitySelection>
    extends ActivationContextWithDatasetSet<S> {
  void includeAllSchemaNodes();
  void includeSchemaNodesMatching(java.util.regex.Pattern);
  void includeSchemaNodesMatching(com.orchestranetworks.schema.Path...);
  void excludeSchemaNodesMatching(java.util.regex.Pattern);
  void excludeSchemaNodesMatching(com.orchestranetworks.schema.Path...);
}

public interface com.orchestranetworks.userservice.declaration.ActivationContextWithDatasetSet<S extends DatasetEntitySelection>
    extends ActivationContext<S>, DataspaceSet, DatasetSet {
  SchemaNode getDataModelRootNode();
  void setActivationRule(ServiceActivationRuleForDataset);
}

public interface com.orchestranetworks.schema.types.dataset.DatasetSet {
  void includeAllDatasets();
  void includeDatasetsMatching(AdaptationName, DatasetSet.DatasetChildrenPolicy);
  void excludeDatasetsMatching(AdaptationName, DatasetSet.DatasetChildrenPolicy);
  ... (regex variants)
}

public interface com.orchestranetworks.schema.types.dataspace.DataspaceSet {
  void includeAllDataspaces(DataspaceSet.DataspaceType);
  void includeDataspacesMatching(HomeKey, DataspaceSet.DataspaceChildrenPolicy);
  ... (regex variants, excludeDataspacesMatching, setIgnoreDataspacesDefaultExcludes)
}

public interface com.orchestranetworks.userservice.declaration.ActivationContext<S extends EntitySelection> {
  void setDefaultPermission(UserServicePermission);
  void setPermissionRule(ServicePermissionRule<? super S>);
  void setDisplayForAllLocations(ActionDisplaySpec);
  void setDisplayForLocations(ActionDisplaySpec, ToolbarLocation...);
  java.util.Set<ToolbarLocation> getAvailableToolbarLocations();
}

public interface com.orchestranetworks.userservice.permission.ServicePermissionRule<S extends EntitySelection> {
  UserServicePermission getPermission(ServicePermissionRuleContext<S>);
  // ALWAYS_ENABLED / ENABLED_FOR_EXECUTION_ONLY / ALWAYS_DISABLED static constants also exist
}

public interface com.orchestranetworks.userservice.permission.ServicePermissionRuleContext<S extends EntitySelection>
    extends UserServiceInvocationContext<S> {
  S getEntitySelection();
  Session getSession();
  ServiceKey getServiceKey();
  boolean isForDisplay();
  boolean isForDisplayInDefaultMenu();
  boolean isForExecution();
  ToolbarLocation getToolbarLocation();
}

public interface com.orchestranetworks.ui.selection.RecordEntitySelection extends TableEntitySelection {
  Adaptation getRecord();
}
public interface com.orchestranetworks.ui.selection.TableEntitySelection extends DatasetEntitySelection {
  AdaptationTable getTable();
}
public interface com.orchestranetworks.ui.selection.DatasetEntitySelection extends DataspaceEntitySelection {
  Adaptation getDataset();
}
public interface com.orchestranetworks.ui.selection.DataspaceEntitySelection extends EntitySelection {
  AdaptationHome getDataspace();
}

public final class com.orchestranetworks.userservice.permission.UserServicePermission {
  static UserServicePermission getEnabled();
  static UserServicePermission getDisabled();
  static UserServicePermission getDisabled(UserMessage);
  boolean isEnabled();
  boolean isDisabled();
  ...
}
```

**The local Java API doc (`.../ebx-manager/www/common/html/onHelp/advanced/Java_API/`, read 2026-09-22)
fills in what `javap` alone cannot**:

- `ActivationContextOnRecord`'s own page: *"By default, the service is activated on all records in all
  datasets in all opened non-technical branches in the repository."* - the maximum scope is already the
  default; `includeAllDatasets()`/`includeAllSchemaNodes()` are called anyway in
  `RecordLineageOnRecordServiceDeclaration.defineActivation`, matching `DataProfilingServiceDeclaration`'s
  own precedent, for explicitness rather than because the default needed widening.
- `ActivationContext`'s own page distinguishes **activation** (static: which datasets/records the service
  can ever appear on, or be granted per profile in the Permissions UI) from **permission rules** (dynamic:
  *"This availability can be restricted by setting a `ServicePermissionRule`. This rule is evaluated every
  time the service is invoked, either to be displayed or executed."*) - exactly the two-layer shape this
  task needed, since which tables qualify is PER-PROJECT data (`Project.masterDataspace`/`masterDataset`,
  and which entities are promoted), not a fixed path/regex the declarative activation filters could express.
  `setPermissionRule`'s own doc: *"The specified rule is instantiated once, when the user service
  declaration is registered"* - `RecordLineageOnRecordPermissionRule` is a stateless singleton instance for
  the module's whole lifetime, never a `ThreadLocal` or per-request object.
- `UserServicePermission`'s own page: *"disabled – the service is not displayed or cannot be executed"* -
  confirms `getDisabled()` actually removes the action from the menu (never merely greys it out), which is
  what "zero visibility outside the master zone" requires.

**Expected shape, as built** (`RecordLineageOnRecordServiceDeclaration.defineActivation`):

```java
pContext.includeAllDatasets();
pContext.includeAllSchemaNodes();
pContext.setPermissionRule(new RecordLineageOnRecordPermissionRule());
```

`RecordLineageOnRecordPermissionRule.getPermission` reads `(dataspace, dataset, table path)` off
`ServicePermissionRuleContext.getEntitySelection()` (`RecordEntitySelection.getDataspace().getKey().getName()`
/ `.getDataset().getAdaptationName().toString()` / `.getTable().getTablePath().format()` - the exact same
resolution `RecordLineageReader`'s own "open in EBX" link building already uses), asks
`MasterZoneIndex.isMasterZoneTable(...)`, and answers `getEnabled()`/`getDisabled()` accordingly. **Not
verified live this session**: whether `setPermissionRule` and `setDefaultPermission` compose (e.g. `min` of
both) the way this document assumes was not tested against a real "disabled per profile in the Permissions
UI" override - `setDefaultPermission` is deliberately never called here (its own doc: used as the fallback
when no per-profile UI permission is set; leaving it untouched keeps the default `ENABLED`, so the rule
alone decides visibility) - confirm this combination on a real instance before relying on it for anything
security-sensitive beyond "which action shows in a menu".

### The cache: `MasterZoneIndex` (EBX-bound) over `MasterZoneActivation` (pure)

The rule runs on every record-form/table-view display or execution check across the WHOLE repository, so it
must be cheap and must never throw. Split in two, the same "EBX-bound reader delegates to a pure, tested
core" shape this module already uses everywhere else (`RecordLineageReader` -> `lineage.RecordLineage`,
`MasterGenerator` -> `master.MasterModel`, ...):

- **`ebx.MasterZoneActivation`** (no EBX class anywhere in the file, unit-tested directly,
  `MasterZoneActivationTest`): `Triple` (a plain `(dataspaceKey, datasetName, tablePath)` value with
  `equals`/`hashCode`), `matches(Set<Triple>, dataspaceKey, datasetName, tablePath)` (fail-closed: a `null`
  set/argument or an exception from the set itself answers `false`, never throws), and
  `isFresh(cachedAt, now, ttlMillis)` (the TTL decision: `cachedAt == 0` or `ttlMillis <= 0` or a `now`
  before `cachedAt` - a clock adjustment - all answer "not fresh", never a negative age).
- **`ebx.MasterZoneIndex`** (EBX-bound): holds the cache (`volatile` fields, a `synchronized` refresh with a
  double-checked freshness read so concurrent callers do not all recompute at once), computes the triple SET
  from the factory's own `Project` rows (`ProposalReviews.projects` + the new shared
  `ProjectMasterEntities.promotedMasterTablePaths`, one call per project with a generated master zone), and
  defers both the matching question and the freshness question to `MasterZoneActivation`.

**Correction, coordinator review, 2026-09-22 (second pass): these reads carry no session and are not
permission-filtered at all.** An earlier version of this section (and of `MasterZoneIndex`'s own Javadoc)
said the cache read "with the module's own SYSTEM session", implying that session gated the reads the way
`SessionPermissions.getAdaptationAccessPermission` gates `RecordLineageReader`'s own reads (see
"Authorisation", above) - that was wrong. None of `Repository.lookupHome`, `AdaptationHome.
findAdaptationOrNull`, `AdaptationTable.createRequestResult`, or `Adaptation.getString` (every EBX call
`MasterZoneIndex.compute()` makes, directly or through `ProposalReviews.projects`/`ProjectMasterEntities`)
takes a `Session` parameter or applies any access check. `MasterZoneIndex.init(Repository, Session)` is
still called once from `FactoryModuleRegistration.handleRepositoryStartup`
(`pContext.createSystemUserSession("mdm-factory")`, the exact same call `FactoryInstaller.install`/
`PerspectiveInstaller.install` already use), AFTER `FactoryInstaller.install` so the factory dataset already
exists - but the `Session` it receives is stored only as a "the module has actually started" guard
alongside the `Repository` reference, never passed to any read. This is still safe, for the same underlying
reason the earlier (inaccurate) text gave: the triples the cache computes (which dataspace/dataset/table
hold a project's master zone) are the MODULE'S OWN configuration, not business data, so reading them without
any permission check at all is fine - and necessary, since the viewer looking at an arbitrary master/
landing/staging table across the repository has no reason to also hold read access to the factory's own
`MdmFactory` dataset. The screen's OWN read side (`RecordLineageReader`) still enforces the CALLER's own
per-record/per-field permissions, WITH a real `Session`, when the action is actually opened (`docs/
RECORD-LINEAGE.md` "Authorisation", above) - this cache only ever decides whether the action is OFFERED,
never what it may show, and needs no session to do that. (The earlier "confirm a system session created at
startup stays usable for reads made minutes or hours later" caveat is dropped here - it does not apply once
the session is understood to never be used for a read in the first place.)

**Cache TTL and invalidation**: 30 seconds (`MasterZoneIndex.TTL_MILLIS`), plus explicit
`MasterZoneIndex.invalidate()` calls added to the success path of `MasterGenerator.generate` and
`MasterReset.reset` (both in package `ebx`, so no new cross-package dependency) - a steward does not wait
out the TTL after generating or resetting a master zone before the action appears or disappears. A cache
refresh failure (repository not fully started, an unexpected error reading `Project`) never throws and never
empties a working cache - it keeps serving the last known-good triples (fail-SOFT, on the CACHE only); the
permission rule's own final answer is what fails CLOSED (an empty/stale cache only ever means "not offered
yet somewhere it should be", never "offered somewhere it should not be").

### Resolving project/entity/key from the record being viewed

`RecordLineageOnRecordUserService.setupObjectContext` (package `ebx.ui`, extends the new
`AbstractRecordLineageUserService<RecordEntitySelection>` - see "Shared rendering", below):

1. **The factory dataset is looked up separately, never `selection.getDataset()`** - unlike
   `RecordLineageUserService` (declared ON the `Project` table of the `MdmFactory` dataset itself, so the
   selection's dataset genuinely IS the factory dataset), this action is declared on a MASTER table, so
   `selection.getDataset()` is the MASTER dataset (e.g. `Companies_Master`/`Master`). The factory dataset is
   found the same way `MasterZoneIndex.compute()` does: `selection.getDataspace().getRepository()` then
   `HomeKey.forBranchName(FactoryPaths.DATASPACE)` / `AdaptationName.forName(FactoryPaths.DATASET)`.
2. **The project is the one whose OWN `masterDataspace`/`masterDataset` equal the record's own** - the exact
   pair `MasterZoneIndex` already matched to decide this action should be offered at all, so this lookup can
   never disagree with the permission rule's own decision except through the documented TTL/invalidation
   race (a master zone reset or regeneration between the menu render and the click) - handled as a plain
   notice, never a stack trace.
3. **The entity name is the inverse of `MasterModel.tablePath(entityName)`** (`"/root/" + entityName`, so
   `"/root/Company"` -> `"Company"`) - a plain string operation, never trusted blindly even though the
   permission rule already restricted activation to exactly these paths (defense in depth, same discipline
   as every other reader in this module).
4. **The key is `selection.getRecord().getOccurrencePrimaryKey().format()`** - never built or parsed as a
   string by hand (CLAUDE.md §9's "never build or split a composite primary key as a string"), the exact
   same formatted-key convention the deep link's own `key` parameter already uses.
5. `RecordLineageReader.read(pContext.getSession(), factory, projectCode, entityName, keyText)` is called
   immediately, with the VIEWER's own session (never a system session) - the screen's own read/redaction
   side is unchanged by this new entry point.

### Shared rendering: `AbstractRecordLineageUserService`

The task brief's own instruction: "render the same page through the same reader ... no second read path".
`RecordLineageUserService` (the original `OnTableView` screen) and `RecordLineageOnRecordUserService` (this
lot) differ ONLY in how they resolve an initial project/entity/key from their own, differently-typed,
`EntitySelection` (`TableViewEntitySelection` vs `RecordEntitySelection`) - `setupObjectContext`, left
abstract. Everything past that - `setupDisplay`, the posted-back project/entity/key form (so a steward can
still look up a DIFFERENT record without leaving the screen), `lookup` (calling `RecordLineageReader.read`),
and `write` (rendering through `lineage.RecordLineagePage`) - moved into a new abstract base class,
`ebx.ui.AbstractRecordLineageUserService<S extends EntitySelection>`, generic purely over the
entity-selection type it is never told to inspect. Both concrete services are now thin: `RecordLineageUserService`
keeps only its own three-way `setupObjectContext` (deep link / toolbar selection / perspective, unchanged
behaviour), `RecordLineageOnRecordUserService` only its own record-resolving one (above).

**One pre-existing duplication was also removed, not added to**: before this lot, `RecordLineageReader.read`
and `RecordLineageUserService`'s own entity selector each carried a private, near-identical copy of "read a
project's `StagingEntity`/`StagingAttribute` rows into `StagingModel.EntityInfo`". `MasterZoneIndex` needed
the exact same resolution again (for every project's `builtEntities`, to build the triple set) - rather than
writing a THIRD copy, `ebx.ProjectMasterEntities` is now the one shared reader all three use (their own
behaviour is unchanged: same predicate, same fields, same `memberEntities` resolution).
`ebx.StagingLoader`/`ebx.MasterLoader`/`ebx.MasterGenerator` each keep their OWN separate copy of a similar
reader - deliberately not touched, matching this codebase's established "self-contained, if slightly
repeated, thin reader" choice for classes this lot must not edit.

### Tests

- `ebx.MasterZoneActivationTest` (new, JUnit-free `main`-driven, same style as `MasterResetEligibilityTest`):
  triple matching (exact match, each field differing in turn, an empty/null allowed set, null arguments, and
  - "exception -> disabled" - a `Set` whose `contains` throws still answers `false`, never propagates),
  triple value-equality/hash-code consistency (including inside a real `HashSet`), and the TTL freshness
  rule (just-refreshed, exactly at the boundary, well past it, never-cached, zero/negative TTL, and a clock
  that went backwards - each explicitly asserted "stale", never a negative age making it look fresher).
- `tools/test_local.sh`: green, 657 classes compiled (up from 656), every existing pure test unaffected by
  the `RecordLineageReader`/`RecordLineageUserService` refactor onto the shared `ProjectMasterEntities`/
  `AbstractRecordLineageUserService` (same predicates, same fields, same behaviour - verified by re-running
  `RecordLineageTest`/`RecordLineagePageTest`/`RecordLineageGraphicTest`/`SourceColorPaletteTest` unchanged).
- **No test exercises `MasterZoneIndex`, `RecordLineageOnRecordPermissionRule`, or
  `RecordLineageOnRecordUserService` themselves** - all three are EBX-bound (`Repository`, `Session`,
  `ServicePermissionRuleContext`, `RecordEntitySelection`, ...) with no pure seam this session could reach
  without a live instance, the same "not unit-tested" discipline `RecordLineageReader`'s own `isHidden`/
  `isFieldHidden` already carry (see "Authorisation", above) - their correctness rests on the `javap`-
  verified signatures above, the pure `MasterZoneActivation` core's own tests, and a careful reading, not on
  a running test.

### What was NOT verified live, and must be checked before trusting this in production

1. **Nothing in this lot was deployed or run.** No Tomcat restart, no `tools/install.sh`, no live EBX
   instance touched - task constraint. Confirm at minimum: the action actually appears on a master record's
   Actions menu (record form and table view with one record selected) and ONLY there; it does NOT appear on
   a landing table, a staging table, any factory table (`Project`, `Xref`, ...), or an unrelated model's
   table anywhere else in the repository (the acceptance criterion the product owner stated).
2. **`setPermissionRule` vs `setDefaultPermission` composition** - reasoned from the local Java API doc's
   separate descriptions of each, never observed together against a real per-profile UI permission override.
3. **How often `ServicePermissionRule.getPermission` is actually invoked per render** - "each time the
   service is invoked, either to be displayed or executed" (the local doc's own wording) could mean once per
   candidate location (record form actions menu, table view toolbar, both at once when a table view has one
   record selected) rather than once per page - `MasterZoneIndex`'s own cache absorbs repeated calls cheaply
   either way, but the exact call count was never measured.
4. **The entity-name inversion (`"/root/" + name` reversed)** assumes every promoted master table path keeps
   `MasterModel.tablePath`'s own flat, un-nested convention - true of every table this codebase generates
   today, but never defended against a hypothetical future table path shape.

(A prior version of this list also flagged "whether a system `Session` obtained at startup stays usable for
reads made minutes or hours later" - dropped, coordinator review, 2026-09-22 second pass: `MasterZoneIndex`
never actually uses that session for a read at all, so its lifetime is moot - see the "Corrected..." note
in "The cache" section, above.)

### The single most surprising thing found

**`handleServiceRegistration` runs before the EBX repository itself is initialised** (`Repository.getDefault()`
would throw if called from inside it, per the local Java API doc) - a constraint invisible from `javap`
alone (interfaces carry no lifecycle-ordering information), and easy to get backwards from the method names
alone (`handleServiceRegistration` sounds like it would run AFTER `handleRepositoryStartup`, not before). It
is the reason `RecordLineageOnRecordServiceDeclaration`'s registration itself must stay pure construction
(no data read), and why `MasterZoneIndex.init` - the actual data-reading setup - had to move to
`handleRepositoryStartup` instead, deliberately AFTER `FactoryInstaller.install` so the factory dataset
already exists by the time the cache can compute anything real.

### 2026-09-22, 10:12/10:19 - deployed, restarted, and the action was still missing everywhere

the product owner merged this lot, deployed the classes and restarted Tomcat at 10:12, then opened the RECORD form of
`Company` key 108 (dataspace `Companies_Master`, dataset `Master`) at 10:19. Its own Actions menu showed
Delete, Data Exchange, Insight, Match and Merge, Visualization, "Manage connector conflicts of this record"
(an `OnRecord` service of `ebx-connectors`), Profiling, "Synchronise with an external application" - and NO
"Record lineage". `kernel.log` after the restart named the factory only in its own startup lines, nothing
about `MasterZoneIndex` or the service key (both were already known, by design, to log nothing on an ordinary
"disabled" answer). This settled the open question from the section above (**"the action actually appears on
a master record's Actions menu and ONLY there"**, item 1): it IS the record's own Actions menu (not the table
view toolbar), and the action is absent there.

**(a) Where an `OnRecord` service appears.** `com.orchestranetworks.ui.selection.RecordEntitySelection`
(javap, `ebx-lib.jar`) is the selection type `UserServiceDeclaration.OnRecord` is parameterised over, and
`ebx-connectors`' own `OnRecord` service ("Manage connector conflicts of this record") appearing in this SAME
menu, on this SAME record, at this SAME moment, is direct proof an `OnRecord` service can and does render in
the record form's Actions menu on this instance - so declaration TYPE was never the problem.

**(b) `includeAllSchemaNodes()` vs. the exact `ActivationContextOnRecord` method set** (javap,
`com.orchestranetworks.userservice.declaration.ActivationContextOnRecord`, `ebx-lib.jar`, 2026-09-22):

```
public interface ActivationContextOnRecord
    extends ActivationContextWithSchemaNodeSet<RecordEntitySelection> {}

public interface ActivationContextWithSchemaNodeSet<S extends TableEntitySelection>
    extends ActivationContextWithDatasetSet<S> {
  void includeAllSchemaNodes();
  void includeSchemaNodesMatching(Pattern);
  void includeSchemaNodesMatching(Path...);
  void excludeSchemaNodesMatching(Pattern);
  void excludeSchemaNodesMatching(Path...);
}
```

There is no `includeAllTables()` - `includeAllSchemaNodes()` is not a guess, it is the ONLY "include
everything" method this interface declares, and `ActivationContext<S>` (the common ancestor,
`ActivationContextWithDatasetSet`'s own parent) is where `setPermissionRule`/`setDefaultPermission` live:

```
public interface ActivationContext<S extends EntitySelection> {
  void setDefaultPermission(UserServicePermission);
  void setPermissionRule(ServicePermissionRule<? super S>);
  void setDisplayForAllLocations(ActionDisplaySpec);
  void setDisplayForLocations(ActionDisplaySpec, ToolbarLocation...);
  Set<ToolbarLocation> getAvailableToolbarLocations();
}
```

So `RecordLineageOnRecordServiceDeclaration.defineActivation`'s three calls
(`includeAllDatasets()`/`includeAllSchemaNodes()`/`setPermissionRule(...)`) are all real, all on the right
interface, and there is no alternative "activate on every table" method being missed. **Activation shape was
never the problem either.**

**Also checked and ruled out**: whether `setAvailableAsToolbarAction` needed to be `false`. The local Java API
doc (`WebComponentDeclarationContext.html`, read 2026-09-22) says plainly: *"Defines whether the service is
proposed in the user interface used to define a **toolbar web component action**."* - i.e. whether the
service shows up as a choice when an administrator is BUILDING a custom toolbar/perspective button, not
whether it appears in the record form's own default Actions menu. `setDisplayForAllLocations`'s own doc adds:
*"By default, `ActionDisplaySpec.DISPLAYED` is set for all locations available for this activation."* - the
default is already "shown everywhere the activation reaches", with no need to call it at all. Neither method
explains a permanently-missing action.

**(c) The actual root cause, found by `javap -c` (bytecode), not by reading a description**:
`RecordLineageOnRecordPermissionRule.getPermission` and `RecordLineageOnRecordUserService.setupObjectContext`
both computed the dataset name as `dataset.getAdaptationName().toString()`. `AdaptationName` declares a
**concrete** `toString()` (not abstract, and `AdaptationReference` - the class `AdaptationName.forName(...)`
actually returns, confirmed by `javap`: `public final class AdaptationReference extends AdaptationName`, no
`toString()` override in its own method list) - disassembled directly:

```
public java.lang.String toString();
    Code:
       0: new           #13   // class com/onwbp/base/misc/OToString
       ...
       9: aload_0
      10: aload_1
      11: invokevirtual #18   // Method toStringDetail:(Lcom/onwbp/base/misc/OToString;)V
      14: aload_1
      15: invokevirtual #24   // Method com/onwbp/base/misc/OToString.finishWithHashCode:()Ljava/lang/String;
      18: areturn
```

`OToString`'s own constructor seeds the buffer with `<ShortClassName>[` (bytecode: `getClass()` →
`JavaClassUtils.getShortName` → append `"["`), and `finishWithHashCode()` appends `"]@" + Integer.toHexString(
instance.hashCode())`. So `dataset.getAdaptationName().toString()` never answers the plain dataset name
(e.g. `"Master"`) - it answers something shaped like `AdaptationReference[Master]@1a2b3c4d`, a debug string
with a hash-code suffix that changes every JVM run. The local Java API doc confirms which method IS the plain
accessor: `AdaptationName.html` documents `getStringName()` as *"Returns the string name."*, while
`toString()`'s own entry carries no description beyond *"Overrides: `toString` in class `Object`"* - i.e. its
contract is deliberately left unspecified, exactly what a debug `toString()` looks like from the outside.

This made `MasterZoneActivation.matches` compare a plain `"Master"` (from `Project.masterDataset`, in every
cached triple) against a debug string that could never equal it, for EVERY dataset, on EVERY record, on every
instance this class has ever run on - a permanent, silent, fail-closed "no" (§Fails CLOSED, above), with
**zero exception and zero log line by design**: `RecordLineageOnRecordPermissionRule`'s own catch block never
logs (its own Javadoc: "never a stack trace ... and never a log line on this hot path"), and
`MasterZoneActivation.matches` returning `false` on a set miss is not itself an error condition. This is
exactly the symptom the product owner measured: the action missing from a genuine, correctly-promoted master record,
with nothing anywhere explaining why. **Fixed**: both call sites now use `dataset.getAdaptationName().
getStringName()`. A third, unrelated occurrence of the identical bug was found and fixed in the same sweep -
`ebx.RecordLineageReader#catalogUrl` built the "open in the catalog" URL's dataset segment the same wrong way
(`catalog.getAdaptationName().toString()`); that one was already flagged "not verified against a live
catalog this session" and would have produced a garbled URL, never a missing action - a smaller bug, same root
cause, caught only because this sweep went looking for every occurrence of the pattern, not just the one that
was reported.

**Made robust regardless of which exact mismatch is live on a given build** (`MasterZoneActivation.Triple`,
2026-09-22): the dataspace key and dataset name are trimmed (never case-folded - EBX keys are genuinely
case-sensitive, so an actual case difference stays a real mismatch), and the table path drops one leading
`"./"` so `"./root/Company"` and `"/root/Company"` compare equal either way, from either side of the
comparison. `MasterZoneActivationTest` covers the exact live triple (`"Companies_Master"`, `"Master"`,
`"/root/Company"`), the leading-`"./"` case both ways, incidental whitespace, and that a genuine case
difference still mismatches.

**Diagnostics, so the next time this happens it takes ten seconds, not a `javap` session**:
`GET /ebx-mdm-factory/rest/v1/diagnostics/master-zone-index` (administrators only, appended at the end of
`rest/FactoryResource.java`) returns `MasterZoneIndex`'s own cached triples, `cachedAt`, and - now also
tracked, where the class previously swallowed a `compute()` failure with no trace at all -
`lastRefreshError` (the failing exception's class and message, never a record value, logged once per failure
streak at WARN rather than every 30-second TTL window). With `?dataspace=&dataset=&table=` all three given,
it also answers `wouldBeEnabled`, running the EXACT same check `RecordLineageOnRecordPermissionRule` itself
makes for that triple - so a steward or administrator can check "why isn't this showing" against the real,
current cache state, from a browser, without reading a single line of source.

**Not verified live even now** (the task's own constraint holds unchanged): the fix has not been deployed,
restarted, or observed making the action appear. What the product owner must check next, once redeployed and
restarted: (1) the record's own Actions menu on `Company` key 108 (or any other promoted master record) now
shows "Record lineage"; (2) `GET /diagnostics/master-zone-index?dataspace=Companies_Master&dataset=Master&table=/root/Company`
answers `wouldBeEnabled: true` and lists that exact triple.

## Cockpit and Proposal review's module-level treatment — added, then removed the same day (2026-09-22)

A same-day follow-up (branch `fix/master-toolbar-module-services`) briefly gave "Cockpit"/"Proposal review"
the exact module-level-service treatment this doc's "Record-level action on a master record" section
describes for "Record lineage" above (`ebx.ui.FactoryCockpitOnMasterServiceDeclaration`/
`ProposalReviewOnMasterServiceDeclaration`, `MasterZonePermissionRule<S extends TableEntitySelection>`
generalised from `RecordLineageOnRecordPermissionRule`, `MasterZoneIndex` extended into a triple→project-code
map, `ebx.ui.MasterZoneProjectResolution`) — needed because the first, cross-model design
(`serviceKey=<module>@<xsdPath>@FactoryCockpit`, `target=s`) could not be resolved from the master model's
own toolbar ("No service with key '...@FactoryCockpit' could be found" in `kernel.log`).

**Removed again, 2026-09-22 16:40, the product owner's decision** (branch `fix/master-toolbars-lineage-only`): *"Cockpit
et la revue des suggestions sont des choses à faire en amont de la génération de la table master. Aucun
intérêt de les mettre là."* Both screens belong to the staging phase, before a master table even exists, so a
button for them on the master table toolbar never made sense — they move to the factory's own `Project` table
instead. `FactoryCockpitOnMasterServiceDeclaration`/`FactoryCockpitOnMasterUserService`/
`ProposalReviewOnMasterServiceDeclaration`/`ProposalReviewOnMasterUserService` and
`ebx.ui.MasterZoneProjectResolution` are deleted; `ProposalReviewUserService` is `final` again;
`MasterZonePermissionRule` and `MasterZoneIndex`'s project-code map are KEPT (generic, tested, harmless — the
former still gates "Record lineage", the latter has no caller today, both classes' own Javadoc says so). Full
account in `docs/MASTER-ZONE.md` §12.7-§12.11.

## Suspects and groups (2026-09-22, branch `fix/lineage-suspects-and-groups`, worktree
`ebx-mdm-factory-lineage-suspects`)

The coordinator ran the first real matching pass on a live instance that morning (6.3.0-dev, add-on MAME
6.3.0-DEV) and measured facts this section replaces the earlier "unverified" statements with. **Still never
run live by this branch itself** (the coordinator's own constraint stands) - every fact below is either the
coordinator's own live measurement (dated, credited as such) or read straight from source/Javadoc, never
guessed.

### The metadataset naming convention - fixed, not just flagged

**Measured live 2026-09-22**: on dataspace `Companies_Master`, dataset `Master`, table `/root/Company`
(107 `Company` records, first real run: "0 MATCHED, 38 SUSPECTED, isolated 0, Auto-merge groups 107"), Data
Services listed the dataspace's own datasets as exactly `Master` and `Master_Company_MDS` - **not**
`Companies_Master_Master_MDS`, the `<masterDataspaceKey>_<masterDatasetName>_MDS` convention this reader (and
`ebx.MasterLoader.RecordMetadataMergeCheck`, `ebx.GoldenKeyRefresher`, `ebx.MasterReset`) assumed until now.
The real convention is **one metadataset per TABLE**, in the SAME dataspace as the master dataset:
`<masterDatasetName>_<tableLastStep>_MDS` (`ebx.RecordLineageReader#metadatasetName`/`#lastPathStep`, pure and
unit-tested - `RecordLineageReaderTest`). Because a missing/wrong metadataset degrades silently ("no merge
info", never an error - see "What was NOT read" below, unchanged), the old bug never SURFACED as a failure on
the one project (`Companies`) where dataspace and dataset happened to share the exact same name as each
other's own prefix by coincidence of earlier examples in this codebase's own history - it was never actually
exercised against a project where the names really differ, until this morning's live read.

**`ebx.MasterLoader.RecordMetadataMergeCheck` carried the identical bug and was fixed the same way** (moved
from one instance shared across the whole master-load run to one instance PER ENTITY/table, built inside the
per-entity loop, since the convention is now known to be per-table) - see that class's own Javadoc and
`MasterLoader.load`'s own comment at the loop. **`ebx.GoldenKeyRefresher`/`ebx.MasterReset` carry the exact
same wrong convention and were left UNFIXED this lot** - deliberately: both sit on `ebx.MatchingConfigurator`'s
own call graph, a file with its own branch open the same night (`MatchingConfigurator.java`/
`MameMatchingApi.java`), and touching either would have risked an avoidable merge conflict for a fix that is
exactly the one already applied elsewhere. Fix them the same way (call `RecordLineageReader.metadatasetName`,
same package) in a follow-up, once that other branch has landed.

**Follow-up, 2026-09-22 (branch `fix/metadataset-name-everywhere`)**: done. The naming derivation itself moved
out of `ebx.RecordLineageReader` into a new pure class, `master.MameMetadataset` (`name`/`lastPathStep`, tests
moved to `MameMetadatasetTest`), and all five sites that need the add-on's metadataset name now call it -
`ebx.RecordLineageReader`, `ebx.MasterLoader.RecordMetadataMergeCheck`, `ebx.GoldenKeyRefresher` and
`ebx.MasterReset` (the last one now checks EVERY promoted master table of the project, not just one, since a
project can have several and matching could have been activated on any of them independently). Never run
against a live instance this lot either (same constraint).

### `RecordMetadata` - the fields actually used, and the key

Key = **`functionalId`** (a plain string PK on the `RecordMetadata` table itself - the business record's own
formatted primary key; **not** `recordId`, which answers "Unrecognized path" per the coordinator's own live
probe). Fields this lot reads directly (guarded, best-effort, `ebx.RecordLineageReader#readIsolatedFlag`):
`isolated` (boolean) - everything else on the row (`groupId`, `state`, `autoCreated`, `goldenId`,
`hasSuspects`, `suspectedCount`, `matchingProcessId`, `mergingProcessId`) is either already read through the
add-on's public `MetadataHelper` API (`groupId`/`state`/`autoCreated`/the golden via `goldenId`, unchanged
from the earlier lot - see "What was verified by `javap`" below) or not needed this lot (`hasSuspects`/
`suspectedCount` are effectively superseded by `merge.group.suspects.length`, read through the real group API
instead - see next section; `matchingProcessId`/`mergingProcessId` on `RecordMetadata` itself were not read).

### Groups and suspects - through the add-on's public `GroupOperations`/`Group` API

Measured live by `javap` against `ebx-addons.jar` (same server project as every earlier `javap` in this
document), 2026-09-22:

```
public class com.orchestranetworks.addon.mame.service.OperationsFactory {
  public static GroupOperations getGroupOperations(Session);
}
public interface com.orchestranetworks.addon.mame.service.group.GroupOperations {
  Groups getGroupsWithActionRequired(AdaptationTable) throws InvalidStateException;
  Group getGroup(AdaptationTable, BigDecimal) throws NotFoundException;
}
public interface com.orchestranetworks.addon.mame.service.group.Group {
  AdaptationTable getTable();
  RecordIterable getMatches(); RecordIterable getSuspects(); // both @Deprecated since 6.1.3
  CloseableIterator<Adaptation> getMatchedRecords();
  CloseableIterator<Adaptation> getSuspectRecords();
  BigDecimal getGroupId(); String getGroupLabel();
  void removeMatches(Set<Adaptation>); void acceptSuspects(Set<Adaptation>);
  void acceptSuspectsAndMergeGroups(Set<Adaptation>); void rejectSuspects(Set<Adaptation>);
  List<Adaptation> getTransitiveMatches(String,String) throws InvalidParameterException;
}
```

`ebx.MameGroups` (new, this lot) wraps exactly this, with the SAME `LinkageError`-guarded seam as
`ebx.MameRecordState`: only `AddonMameGroups` references `com.orchestranetworks.addon.mame.*` types, and a
server without the add-on degrades `RecordLineage.MergeInfo#group` to `null` instead of a `500`. Read-only:
`removeMatches`/`acceptSuspects`/`acceptSuspectsAndMergeGroups`/`rejectSuspects` are documented below but
never called this lot. Bounded: `getMatchedRecords`/`getSuspectRecords` are each read up to 50 items
(`MameGroups.CAP`), with a `truncated` flag the moment a 51st item is seen - a group could genuinely be
larger, but this screen only ever needs a representative sample.

### `Suspected`/`Matched` - exact fields, from `MetadataTemplate.xsd`

Read 2026-09-22, `webapps/ebx-addon-mame/WEB-INF/ebx/schema/metadata/MetadataTemplate.xsd` (this instance's
own DMA-generated schema for the metadataset):

```xml
<Matched>    { id: string (PK), sourceId: string, targetId: string }
<Suspected>  { id: string (PK), sourceId: string, targetId: string, matchingProcessId: int }
```

**No `score` field exists anywhere on either table** - so a per-suspect match score is never attached to
`RecordLineage.GroupMember`, per the task brief's own instruction ("if not, leave those fields out").
`matchingProcessId` DOES exist on `Suspected` (not on `Matched`) - `ebx.RecordLineageReader
#lookupSuspectedProcessId` attaches it, best-effort: **which of `sourceId`/`targetId` names the anchor
(golden) vs. the suspect was never confirmed live** (this session could not reach the instance), so the
lookup tries BOTH orderings (`(./sourceId=anchor and ./targetId=suspect) or (./sourceId=suspect and
./targetId=anchor)`) and takes the first match - correct either way the add-on actually orders the pair, but
unverified which ordering is the real one. A suspect with no matching `Suspected` row (lookup failed, ordering
wrong on this build, or the row genuinely does not exist) simply has no `processId` in the JSON - never a
guess, never an error.

### What changed in the read model (`RecordLineage`) and the response shape

Additive only - every field the earlier lot documented above is unchanged:

```json
{
  "merge": {
    "present": true,
    "state": "GOLDEN",
    "derivedState": "GOLDEN_WITH_SUSPECTS",
    "groupId": "1833022115544083",
    "goldenKey": "183",
    "autoCreated": false,
    "group": {
      "id": "1833022115544083",
      "label": "BPCE",
      "memberCount": 1,
      "matched": [],
      "suspects": [
        { "masterKey": "141", "label": "BPCE (CRM)", "sourceSystem": "CRM", "processId": "77" }
      ],
      "truncated": false,
      "actions": { "canAcceptSuspects": true }
    }
  },
  "suspectSources": [
    { "masterKey": "141", "golden": false, "sourceSystem": "CRM", "suspect": true, "sourceId": "ACC-1",
      "landing": { "...": "..." }, "staging": { "...": "..." } }
  ]
}
```

- **`merge.derivedState`**: `state`, or `GOLDEN_WITH_SUSPECTS` when this record IS the golden
  (`state = GOLDEN`), `RecordMetadata.isolated` is confirmed `true`, and `merge.group.suspects` is non-empty
  (`RecordLineage.deriveState`, pure, tested directly - `RecordLineageTest#deriveState_*`). A `null`/unread
  `isolated` NEVER derives it - only a confirmed `true` does (fail closed, same discipline as the permission
  checks). This is a LABEL the pure model computes, never something the add-on itself answers or anything
  this reader stores.
- **`merge.group`**: null whenever no group could be resolved (add-on absent, unparsable/unreadable group id,
  or `GroupOperations.getGroup` answered "not found" - `merge.present` can still be `true` in that case, e.g.
  a plain `UNSET`/`DELETED` record with add-on metadata but no actual group). `matched`/`suspects` are
  `RecordLineage.GroupMember` lists - each either `{restricted: true}` ONLY (a hidden member: no key, no
  label, nothing else - the SAME all-or-nothing rule as the master record's own hidden-record case, not the
  coarser `SourceChain#restricted` shape which still names the system) or `{masterKey, label, sourceSystem,
  processId}` (label via `Adaptation.getLabel(Session)`, same "never show a bare key as if it were a name"
  rule as `Input#masterLabel`; `sourceSystem` off the member's OWN `sourceSystem` field - `master.MasterModel`
  puts a plain, optional `sourceSystem` field on every master table, even a fully golden/merged one, so this
  works the same way for a matched member, a suspect, or the record being examined itself). `memberCount` =
  `matched.size() + 1` (the `+1` is the golden itself - never returned among its own `getMatchedRecords()`).
  `actions.canAcceptSuspects`: `SessionPermissions.getAdaptationAccessPermission(Adaptation).isReadWrite()`
  (verified by `javap` against `ebx-lib.jar` - same signature `isHidden` already used, checked for
  `isReadWrite()` here) on the EXAMINED master record, AND at least one suspect present, AND the group itself
  resolved - a read-only flag, never a write.
- **`suspectSources`**: a SEPARATE top-level array from `sources` - one `SourceChain` (new `suspect: true`
  field) per non-restricted pending suspect, resolved through the exact SAME `resolveSource` a confirmed
  contributing source uses (same permission rule, same bound). Deliberately never merged into `sources`: the
  "champ par champ" field table reads its own column set from `sources`, and a suspect is not yet a confirmed
  contributor - mixing it in would show an empty/"locked"-looking column for a record that was never actually
  compared field by field. The journey view (`lineage-graphic.js`) reads BOTH arrays.

### The graphic screen - one place changed, two exact lines

`lineage.RecordLineageGraphic` (data only): `merge.suspectCount` (from `merge.group.suspects.size()`) and
`merge.stateWords` computed from `derivedState` rather than the raw `state` - only for `GOLDEN_WITH_SUSPECTS`
does this route through a `{0}`-placeholder bundle key (`lineage.state.GOLDEN_WITH_SUSPECTS`, both `en`/`fr`)
with `MessageFormat`; every other state keeps the exact same zero-argument path it always had (deliberately -
an existing `lineage.state.*` value was never meant to go through `MessageFormat`, and at least one, French
`PENDING_APPROVAL`, contains an apostrophe that would need doubling to survive it). A new top-level
`suspectLanes` array carries one lane per `suspectSources` entry, built through the exact same `buildSource`
a confirmed lane uses, with `kind: "suspect"` added - never mixed into `sources`/`visibleSourceCount` (that
budget is for confirmed contributors only). Colour assignment (`assignColors`) is now fed BOTH `sources` AND
`suspectSources`, so a suspect's own source system keeps the SAME stable colour a later confirmed contribution
from that system would get, rather than falling back to the name-hash default.

**`lineage-graphic.js` - the exact, smallest additive changes, and where**:
1. `drawParcours`: `var sources=visibleSources();` gained one new line right after it,
   `var lanes=sources.concat(data.suspectLanes||[]);`, and `var n=Math.max(1,sources.length);` became
   `var n=Math.max(1,lanes.length);` (lane count/canvas height now include suspect lanes) - the per-lane
   drawing loop's own `sources.forEach(function(s,i){` became `lanes.forEach(function(s,i){`.
2. Same loop, the dashed-style test: `var dashed=s.style==='dashed';` became
   `var dashed=s.style==='dashed'||s.kind==='suspect';` - a suspect lane is always dashed, whatever its own
   palette style.
3. Inside the staging-card block (`if(!s.restricted){...stageNode...}`), right before that node's own
   `interactive(...)` call: a small `"SUSPECT"` text badge, top-right of the card, drawn only `if
   (s.kind==='suspect')` - the one visual label the task brief asked for on the staging card.
4. The ring (`if(data.merge.present){...}`): one new line, a small `"+N suspect(s)"` text under the existing
   record-count/"RECORDS" pair, drawn only when `data.merge.suspectCount>0` - the "1 + N suspects" the brief
   asked for (using the REAL `recordCount`, not hardcoded to 1 - the brief's own example happens to have
   `recordCount = 1` because that golden is isolated).

No other file among `RecordLineageUserService.java`/`RecordLineagePage.java` was touched - both are owned by
the "Load button, connector arrows" branch open the same night.

### Confirming a suspect - documented call sequence, NOT implemented this lot

Read-side only, per the task brief ("do NOT build the accept action itself in this lot"). The add-on's own
Java API Javadoc (`webapps/ebx-addon-mame/www/common/html/onHelp/advanced/Java_API/com/orchestranetworks/
addon/mame/service/group/Group.html`, this instance - stamped "Match and Merge Add-on Version 6.2.2" even
though the running add-on measured live this morning is 6.3.0-DEV; the shipped Javadoc may simply lag the
running version, not re-verified) gives the exact sequence and a full code sample:

```java
GroupOperations groupOperations = OperationsFactory.getGroupOperations(session);
try {
    Group group = groupOperations.getGroup(table, new BigDecimal("12"));
    Set<Adaptation> duplicates = ...;   // suspects the caller has confirmed ARE the same entity
    Set<Adaptation> notDuplicates = ...; // suspects the caller has confirmed are NOT
    if (!duplicates.isEmpty()) {
        group.acceptSuspects(duplicates); // or acceptSuspectsAndMergeGroups(duplicates)
    }
    if (!notDuplicates.isEmpty()) {
        group.rejectSuspects(notDuplicates);
    }
} catch (NotFoundException ex) {
    throw OperationException.createError(ex.getMessage(session.getLocale()));
}
```

What each one is documented to do (quoted from that same page):

- **`acceptSuspects(Set<Adaptation>)`**: *"For each suspect record passed in: If the record's state is
  GOLDEN or UNSET, its entire source group (golden + all merged records) is moved into this group. If the
  record's state is MERGED, only that single record is moved; the rest of its source group (golden + sibling
  merged records) is left behind."*
- **`acceptSuspectsAndMergeGroups(Set<Adaptation>)`** (since 6.2.4): *"...merges each suspect's entire source
  group into this group. For each suspect record passed in, every record belonging to that suspect's source
  group (the golden record and all records merged into it) is moved into this group. The source group is
  dissolved. This method always merges full groups, regardless of whether the suspect record itself is
  GOLDEN, UNSET, or MERGED... addresses the limitation of acceptSuspects(Set) where accepting a MERGED
  suspect moves only that single record, leaving the rest of its source group orphaned (defect MAME-10041 /
  EBXRFA-2659)."*
- **`rejectSuspects(Set<Adaptation>)`**: *"A rejection is recorded pair by pair: one false positive decision
  is stored between each given record and each record of the group it was suspected against. It is not a
  global exclusion - the given records keep being matched normally..."*

Both `accept*`/`reject*` throw unchecked `IllegalStateException`/`IllegalArgumentException` on error (no
`throws` clause) - only `getGroup` itself declares the checked `NotFoundException`. A future write lot would:
resolve the group the same way `ebx.MameGroups`/`RecordLineageReader#buildGroup` already do, build the
`Set<Adaptation>` of accepted/rejected suspect `Adaptation`s from the SAME `getSuspectRecords()` this lot
already reads (never re-looked-up by key, to guarantee the exact same `Adaptation` instances the caller was
shown), gate the whole action behind `group.actions.canAcceptSuspects` (already exposed, read-only, this
lot), and run inside a real write path (a `Procedure`, or the add-on's own transaction boundary - not
determined this lot).

### Still unverified, honestly

1. **Which of `Suspected.sourceId`/`targetId` names the anchor vs. the suspect** - `#lookupSuspectedProcessId`
   tries both orderings rather than guessing one; confirm with a real `Suspected` row once matching has
   actually produced one with a readable `matchingProcessId`.
2. **The reverse `./goldenId=...` predicate and the metadataset naming fix** are now confirmed against ONE
   live project (`Companies_Master`/`Master`/`Company`) - not yet against a project whose master dataspace key
   and dataset name are NOT the simple `<Project>_Master`/`Master` shape, nor against a project with more than
   one promoted master table (which is exactly the scenario the `MasterLoader.RecordMetadataMergeCheck` fix
   above specifically targets, but was not exercised live either).
3. **`RecordMetadata.isolated`'s exact semantics** past the one live example (record 183, BPCE/ERP: `Golden`,
   `isolated: true`, `hasSuspects: true`, `suspectedCount: 1`) - whether a GOLDEN record that already has
   confirmed matches (`matched` non-empty) can ALSO read `isolated: true` was not observed (this lot's own
   `RecordLineage.deriveState` treats `isolated` and "has matched members" as independent facts, per the
   measured example, but a live record proving or disproving that independence was not seen).
4. **`getGroup` on an isolated record with zero suspects and zero matches** - whether the add-on still answers
   a resolvable one-member `Group` (this lot's own code assumes yes, since every record with ANY `groupId` at
   all should belong to at least a singleton group) or `NotFoundException` for a group with no matches/
   suspects at all - not observed live.
5. **`ebx.GoldenKeyRefresher`/`ebx.MasterReset` still carry the pre-fix metadataset naming bug** - see "The
   metadataset naming convention" above for why they were left unfixed this lot, and fix them the same way
   (`RecordLineageReader.metadatasetName`) once `ebx.MatchingConfigurator`'s own branch has landed.
6. **The exact wording/shape a real GOLDEN_WITH_SUSPECTS record produces on screen** - every JSON example in
   this section is illustrative, built from the pure `RecordLineageTest`/`RecordLineageGraphicTest` fixtures
   (including the coordinator's own live numbers as fixture VALUES, e.g. `1833022115544083`), never a live
   capture of the real `Company`/`183` record this session could not reach.

## Deciding on a suspect (2026-09-22, branch `feature/lineage-suspect-decision`, worktree
`ebx-mdm-factory-suspect-decision`)

The "decide on a suspect" lot: a comparison panel between the looked-up record and its pending suspects, two
decision actions (confirm duplicate / not the same) that actually call the add-on, a fix for a suspect lane's
own "0 steps" bug, and a first, conservative implementation of the field-by-field survivorship seam
(`FieldMergeLineageSource`) `docs/RECORD-LINEAGE.md`'s own "What was NOT read" section left open. **Never run
live this session** (the coordinator's own constraint, unchanged) - every add-on call is `javap`-verified
against the same `ebx-addons.jar` this document's earlier lots already used, and every screen was rendered
from hand-built fixtures with headless Chrome, never against a real EBX instance.

### A - the comparison panel

`RecordLineagePage#renderComparisonPanel` - a native `<details id="mdmfCompare">`/`<summary>` disclosure,
closed by default (opens on a plain click on its own summary, or on Space/Enter with keyboard focus - no
script required for that part at all), forced open when a decision on one of its own suspects is already
pending confirmation. `lineage-graphic.js`'s `openCompare(suspectKey)` additionally opens it and scrolls a
specific suspect's own column into view when the matching ring or a suspect lane is clicked (`interactive`
callbacks already wired for `sourceDetail`/`openLink` - this is one more thing those same callbacks do, never
a second, competing click handler).

- **Columns**: the looked-up record's own values (read straight from `record.fields`/`record.restrictedFields`
  - never repeated into a new structure, so the ONE place this model already redacts a field stays the only
  one) and one column per suspect the reader could resolve field values for
  (`comparison.suspects[]`, below). A header line per suspect column: source system + record label
  (`merge.group.suspects[]`, matched by `masterKey`), source id + received date + an "Open in EBX" link
  (`suspectSources[]`, the exact same array the journey's suspect lanes already use, matched by `masterKey`
  too) - a suspect with no matching `Xref`-resolved chain (should not happen for a genuine pending suspect,
  but not trusted blindly) simply shows the system name with nothing else, never a broken link.
- **Rows**: first the fields the entity's own accepted `MatchPlan.requirements` compares
  (`ebx.RecordLineageReader#readMatchPlanFields` - parses the plan's own `tiers[].fields[]` JSON, one
  `RecordLineage.PlanFieldRef{field, algorithm}` per DISTINCT field name, first-occurrence order across every
  tier; a missing/blank/malformed plan answers an empty list, never a hard failure), each with the algorithm
  in words (`lineage.AlgorithmWords` - a small LOCAL map, since `review.ProposalWording` carries no algorithm
  vocabulary of its own as of this lot, per the task brief's own fallback instruction; covers the 11
  `ebx-addon-mame@...` codes `docs/MATCHING.md` already measured live from `matching_config_schema`, an
  unrecognised code still shows - the raw suffix, quoted - never dropped). Every OTHER business field follows,
  collapsed under a nested `<details class="mdmf-compare-other">` ("Other fields (N)") - never dropped just
  because the plan does not mention them.
- **The chip** (`=` identical, `≈` equal after case/accent/spacing fold, `≠` different, empty text
  when either side has no value, a lock when either side is restricted) is computed in the PURE model,
  `lineage.RecordLineage#chipFor` - called once per (field, suspect) inside `assemble()` itself, reusing the
  EXACT SAME `quality.CleaningSteps.foldKey` fold the field-by-field table's own `equivalentToGolden` already
  uses (never a second, ad-hoc normalisation). `UNKNOWN` (rendered as the lock chip) whenever either side is
  restricted OR simply absent - never a confident answer guessed from only one visible side (`RecordLineageTest
  #comparison_restrictedAnchorField_unknownChipEvenIfSuspectVisible` locks this in: the ANCHOR's own field
  being hidden makes the comparison unknown too, even though the suspect's own value is perfectly visible).
- **Values are real data, under the SAME permission redaction as everything else on this screen** - a suspect
  globally hidden (`ebx.RecordLineageReader#isHidden` on the suspect's own master record) renders
  `{masterKey, restricted: true}` only in `comparison.suspects[]` (same all-or-nothing rule as `GroupMember`);
  a single field hidden on an otherwise-visible suspect (`isFieldHidden`) renders `{restricted: true,
  chip: "UNKNOWN"}` for that field alone, never a value.

### New read-side classes/fields

- `lineage.RecordLineage.PlanFieldRef{field, algorithm}` and `lineage.RecordLineage.SuspectFieldSet{masterKey,
  restricted, fields}` - both plain, pure carriers (no EBX type), additive on `Input`/`assemble()` (a new
  `comparison` top-level JSON object, emitted ONLY when `suspectFieldSets` is non-empty - an empty panel would
  have nothing to decide).
- `ebx.RecordLineageReader#buildGroup` now ALSO builds one `SuspectFieldSet` per suspect, right where it
  already holds the raw suspect `Adaptation` (from `ebx.MameGroups#read`'s own `suspectRecords`) - reading its
  master-table field VALUES under the exact same `isFieldHidden` check `recordFields` already applies to the
  anchor, rather than a second lookup by key later. `pFieldNames` (the anchor's own business field list) is
  threaded down from `#read` through `#computeMerge` into `#buildGroup` for this purpose.

### B - the decision itself: `ebx.MameSuspectDecision` + `ebx.RecordLineageReader#decideSuspect`

**The call sequence, and why `acceptSuspectsAndMergeGroups`, not the narrower `acceptSuspects`.** Both are
read from the SAME local Javadoc page this document's earlier "Confirming a suspect" section already quoted
from (`webapps/ebx-addon-mame/www/common/html/onHelp/advanced/Java_API/com/orchestranetworks/addon/mame/
service/group/Group.html`):

> `acceptSuspects(Set)`: "For each suspect record passed in: If the record's state is GOLDEN or UNSET, its
> entire source group (golden + all merged records) is moved into this group. If the record's state is
> MERGED, only that single record is moved; the rest of its source group (golden + sibling merged records)
> is left behind."

> `acceptSuspectsAndMergeGroups(Set)` (since 6.2.4): "...merges each suspect's entire source group into this
> group. For each suspect record passed in, every record belonging to that suspect's source group (the golden
> record and all records merged into it) is moved into this group. The source group is dissolved. This method
> always merges full groups, regardless of whether the suspect record itself is GOLDEN, UNSET, or MERGED...
> addresses the limitation of acceptSuspects(Set) where accepting a MERGED suspect moves only that single
> record, leaving the rest of its source group orphaned (defect MAME-10041 / EBXRFA-2659)."

This screen never checks a suspect's own `MameRecordState` before deciding on it - it could in principle
already be a non-golden member of its own source group. The narrower `acceptSuspects` risks silently
orphaning sibling records the steward never saw; `acceptSuspectsAndMergeGroups` behaves the same way
regardless of the suspect's own state, so it is the ONLY accept call this lot uses. `rejectSuspects(Set)` -
"A rejection is recorded pair by pair: one false positive decision is stored between each given record and
each record of the group it was suspected against. It is not a global exclusion - the given records keep
being matched normally..." - is exactly what "Not the same" needs, unchanged.

**Neither `accept*` method performs the actual merge (golden-record creation, survivorship) by itself** - both
only move records between the add-on's own group bookkeeping. The SEPARATE `MergeOperations` page
(`.../service/merge/MergeOperations.html`) is where the real merge lives:

> `mergeGroup(AdaptationTable, BigDecimal)`: "Executes a merging process to merge a group of records. A merge
> policy should be defined beforehand and this merge policy should have the property `Used for manual merge`
> set to true."

So `MameSuspectDecision#accept` calls `Group.acceptSuspectsAndMergeGroups` FIRST, then attempts
`MergeOperations.mergeGroup` on the SAME group id as a second, INDEPENDENTLY guarded step (its own
try/catch over `InvalidStateException`/`InvalidParameterException`/the checked `OperationException`/any
`RuntimeException`) - a merge-policy misconfiguration (e.g. no policy flagged "used for manual merge", which
this project's own live merge policy - `ONLY_DUPLICATES`/`mostCompleted`/`longest`, per the coordinator's own
live read - was never confirmed to have) never undoes or fails the accept itself, matching the task brief's
own "guard every call" instruction. `MameSuspectDecision.Result` therefore distinguishes `ACCEPTED` (the
group move alone), `ACCEPTED_AND_MERGED` (both succeeded - carries the golden's own formatted key from
`MergeProcess.getGoldenRecord()`), and `ACCEPTED_MERGE_FAILED` (the move succeeded, the merge attempt did
not - carries a detail, never a value).

**`javap` against `ebx-addons.jar` (2026-09-22, same jar `ebx.MameGroups`/`ebx.MameRecordState` already
compile against - no build file change needed here either)**:

```
public interface com.orchestranetworks.addon.mame.service.merge.MergeOperations {
  MergeProcess merge(AdaptationTable, BigDecimal) throws OperationException, InvalidStateException; // @Deprecated
  MergeProcess mergeGroup(AdaptationTable, BigDecimal) throws OperationException, InvalidStateException, InvalidParameterException;
  MergeProcess mergeGroup(AdaptationTable, BigDecimal, String) throws OperationException, InvalidStateException, InvalidParameterException;
  MergeProcess mergeRecords(List<Adaptation>) throws OperationException, InvalidStateException, InvalidParameterException;
  MergeProcess mergeRecords(List<Adaptation>, String) throws ...;
  MergeProcess mergeRecordsWithContext(ProcedureContext, List<Adaptation>, String) throws ...;
  MergingProcess mergeGroups(AdaptationTable, Set<BigDecimal>);
  MergingProcess mergeGroups(AdaptationTable, Set<BigDecimal>, String);
  void unmerge(AdaptationTable, PrimaryKey) throws NotFoundException, InvalidStateException, OperationException;
}
public interface com.orchestranetworks.addon.mame.service.merge.MergeProcess {
  String getMergeProcessId(); Adaptation getGoldenRecord(); Date getExecutionDate();
}
public interface com.orchestranetworks.addon.mame.service.group.Group {
  ... acceptSuspects(Set<Adaptation>); acceptSuspectsAndMergeGroups(Set<Adaptation>); rejectSuspects(Set<Adaptation>); ... // no throws clause - unchecked only, per the Javadoc
}
```

`OperationsFactory.getMergeOperations(Session)` sits next to the already-used `getGroupOperations(Session)` on
the same factory class.

**`ebx.RecordLineageReader#decideSuspect`** (new public method) re-resolves the anchor record and its group
FRESH from `(project, entity, anchorKey)` - never reusing anything an earlier `#read` call computed, which may
be stale by the time a steward clicks a decision button - through a SMALL, DELIBERATELY SEPARATE copy of the
project/masterTable/anchor-record resolution `#read` performs at its own head (this codebase's own
"self-contained, if slightly repeated, thin reader" precedent - `docs/RECORD-LINEAGE.md`'s own "Shared
rendering" section already chose this over touching `#read` itself for the record-level action lot). Checks,
in order: the anchor record exists and is visible (`isHidden`) - the SAME `recordNotFoundMessage` as `#read`
itself, so "hidden" and "does not exist" stay indistinguishable here too; the caller has read-write access
(`#canReadWrite`, the SAME signature `RecordLineage.GroupInfo#canAcceptSuspects` already uses, RE-CHECKED here
rather than trusted from an earlier render); the anchor carries add-on metadata and a `groupId`
(`MameRecordState#read`); the suspect's own key resolves to a visible master-table record. Every failure short
of these becomes a `DecisionResult{success: false, outcome: <a plain-word code>}` - never an exception past
this method, matching this reader's own "always plain words" discipline (class Javadoc).

### The two-step confirmation, and where the state lives

`AbstractRecordLineageUserService` gained `pendingSuspectAction`/`pendingSuspectKey` (server-tracked instance
fields, the SAME "pending confirmation before a write" shape `ProposalReviewUserService`'s own bulk-accept/
reject confirmation already uses - read in full before writing this, never a client-side `window.confirm()`):
`ACCEPT_SUSPECT`/`REJECT_SUSPECT` (the initial two buttons per suspect) only ever SET this pending state, never
call the add-on; `CONFIRM_SUSPECT_DECISION` is the one branch that actually calls `decideSuspect`;
`CANCEL_SUSPECT_DECISION` clears it with no write at all. Cleared on every `LOOKUP`/`SELECT_PROJECT`/
`CHANGE_PROJECT` too, so a stale pending confirmation from a DIFFERENT record/suspect can never survive a
navigation. A new hidden field, `mdmfSuspectKey` (`RecordLineagePage#renderHiddenFields`), carries which
suspect a click applies to; `AbstractRecordLineageUserService#pageJs` gained ONE generic rule ("any clicked
button carrying `data-mdmf-suspect-key` writes it into the hidden field") rather than one branch per action,
so a future decision button needs no matching script change.

**After a decision (success or failure), the screen re-looks-up FRESH** (task brief: "the screen re-renders
the lineage fresh after the action") - `confirmSuspectDecision` calls `#lookup` unconditionally once the
write attempt (or its failure) is recorded, and preserves the DECISION's own notice text across that fresh
lookup succeeding (rather than letting `#lookup`'s own "no notice on success" silently erase it) - a steward
sees both "the suspect was confirmed as a duplicate and the group was merged" AND the new group/golden state
in the same render.

### D - fix: a suspect lane's own cleaning steps

**The bug** (coordinator's own live fact, 2026-09-22): a SUSPECT's staging card always showed "0 steps", even
when its own source system's accepted `AttributeMapping`/`MappingStep` chain plainly has cleaning steps (e.g.
CRM's `country ← ACCOUNT.BILLING_COUNTRY` carries an accepted `COUNTRY_TO_ISO2` step) - because
`RecordLineageGraphic#cleaningKindsForSource` (still used for a plain contributing source, unchanged) derives
its answer from `fields[].contributions[]`, and a SUSPECT has no contributions at all (it is not yet a
confirmed source - see `SourceChain#suspect`'s own long-standing Javadoc). This was actively WRONG, not merely
incomplete: the mapping that will clean its values already ran, on its own staging row, whether or not the
suspect is ever confirmed.

**The fix**: `SourceChain` gained `cleaningStepKinds` (nullable `List<String>`, additive constructor overload
- every existing caller/fixture unaffected, and a PLAIN contributing source never sets it, so the graphic
builder's existing, correct behaviour for a real source is completely unchanged). `ebx.RecordLineageReader`
computes it ONLY for a suspect lane, via a new `#cleaningKindsForSourceSystem(originsByField, sourceSystem)` -
the union of `MappingStep.kind`s across every field whose accepted mapping reads from that SOURCE SYSTEM (the
SAME `originsByField` map `#read` already builds for every contributing source's own per-field contributions,
reused here keyed by system instead of by contributing master key - never a second read of
`AttributeMapping`/`MappingStep`). `RecordLineageGraphic#buildSource` prefers this field, verbatim, over the
old contribution-based scan whenever the JSON carries it (`pSource.hasNonNull("cleaningStepKinds")`) - a plain
contributing source, which never carries the field, is unaffected either way.

### C - survivorship: `FieldMergeLineageSource` implemented, conservatively

**Established that `MergeValueLineageCompact.Items[]` IS reachable through a documented, generic EBX
mechanism** - not the add-on's own internal API, which this session still could not safely use (see below).
The EBX Developer Guide's own `references/mapping_to_java.html` ("Mapping to Java"), section "Multiple
cardinality on a complex element": *"If the attribute maxOccurs is greater than 1, then the corresponding
instance in Java is: An instance of java.util.List for an aggregated list... or An instance of AdaptationTable,
if the property osd:table is specified."* Read together with "Mapping of complex types to custom JavaBeans"
(an `osd:class` node is a plain read-write JavaBean, no contextual computation allowed in its accessors) -
this is the GENERIC, DOCUMENTED mechanism for ANY `osd:class`-annotated repeatable group, and
`MergeValueLineageCompact.Items` is declared with exactly this shape in the add-on's own schema
(`webapps/ebx-addon-mame/WEB-INF/ebx/schema/metadata/MetadataTemplate.xsd`, re-read 2026-09-22):
`osd:class="com.orchestranetworks.addon.mame.model.metadata.MergeValueLineageItem"`, no sibling `osd:table`.
So `Adaptation.get(Path.parse("./Items"))` on a `MergeValueLineageCompact` record is documented to answer a
`java.util.List` of that bean type.

**But the bean class is NOT on this module's own compile classpath, and `ebx.FieldMergeLineageSource` never
references it by name.** `unzip -l`/`javap` against `ebx-addons.jar` (2026-09-22) shows only
`com.orchestranetworks.addon.mame.model.metadata.MatchingResultType` in that package -
`MergeValueLineageItem`/`MergeValueLineageCompact` exist ONLY inside the DEPLOYED `ebx-addon-mame` webapp's
own `WEB-INF/classes`, a private, per-webapp classpath this module's own webapp classloader cannot see. The
local Java API doc (`.../ebx-addon-mame/www/common/html/onHelp/advanced/Java_API/com/orchestranetworks/addon/
mame/model/metadata/`) lists only `MatchingResultType` too - `MergeValueLineageItem` carries no Javadoc page
at all, confirming it is genuinely internal, never part of the add-on's published API. **`ebx.
FieldMergeLineageSource#read` therefore reads `Items` through plain REFLECTION**
(`Object.getClass().getMethod(...)`) rather than a compile-time reference - no add-on class needs to be on the
classpath at all for this (so no `LinkageError` seam like `ebx.MameGroups`/`ebx.MameRecordState`/`ebx.
MameSuspectDecision` is needed here - those reference add-on SERVICE types that genuinely ARE on this
classpath; this is a different situation, a bean type that ISN'T), and degrades to "unread" on ANY reflective
failure (a missing method, a wrong return type, an access failure) for a single item or the whole call - never
a guess, never a stack trace reaching the caller.

**Which fields are trusted, and which are not, and why.** `fieldPath`/`mergeFunction`/`sourceValue`/
`manualModified`/`modifiedByDataSteward` are unambiguous from their own names. `recordId`/`sourceIndex`/
`goldenIndex` are NOT - which of the group's own contributing records `recordId` actually names (a master key?
a staging key? an internal row id?), and what `sourceIndex`/`goldenIndex` index into, was never confirmed live
and no documentation for this internal bean exists to check against. Per the task brief's own instruction ("if
a field's meaning is unclear... say so and read conservatively"), `#read` tries TWO independent
identification strategies, each requiring a UNIQUE match to ever answer:

1. **By `recordId`** - an item whose `recordId` equals one of the group's own contributing master keys,
   verbatim string equality (the most direct reading of the field's own name, tried first).
2. **By `sourceValue`** (fallback, only if (1) matched none) - an item whose `sourceValue` equals, verbatim,
   the comparable value (staged, else raw) of EXACTLY ONE contributing source for this field - the same "a
   unique match is a logical certainty" reasoning `lineage.RecordLineage#assemble`'s own DEDUCED rule already
   uses, but backed by the add-on's own RECORDED merge output rather than a comparison against the record's
   current value.

Neither strategy is preferred a priori beyond the try-order above; when BOTH fail to produce a single answer,
`FieldMergeLineageSource.Result#knownRetainedMasterKey` stays null and the field falls back to the EXISTING
DEDUCED rule, exactly as before this lot - **confirmed to need NO change anywhere else**: `lineage.
RecordLineage.FieldInput`'s own Javadoc already said "the ONLY change a future implementation of this seam
needs is the one call site in `RecordLineageReader` that builds `FieldInput`" - verified true this lot, and
the `docs/mockups`-validated golden-card/field-table rendering (`RecordLineageGraphic`, `lineage-graphic.js`)
needed ZERO changes to show a real `KNOWN` answer (confirmed by rendering `survivorship.html`, a hand-built
two-source fixture with `knownRetainedMasterKey`/`survivorshipRule` set as `ebx.FieldMergeLineageSource` would
now supply them - see "Screenshots" below).

**The exact `MergeValueLineageCompact` fields read** (from `MetadataTemplate.xsd`, re-confirmed 2026-09-22):
table-level scalars `id` (autoincrement PK), `mergingProcessId` (int, required), `sourceField` (string,
optional) - read the ordinary, already-proven-safe way (`Adaptation.getString`/`createRequestResult`, exactly
like `RecordMetadata.goldenId` elsewhere in this reader); repeatable `Items[]`, each item's `fieldPath`,
`recordId`, `sourceIndex` (int), `goldenIndex` (int), `mergeFunction`, `condition`, `customValue`,
`sourceValue`, `manualModified` (boolean), `modifiedByDataSteward` (boolean) - read via reflection as above,
but only `recordId`, `sourceValue`, `mergeFunction`, `manualModified`, `modifiedByDataSteward` are actually
USED (see above for why `sourceIndex`/`goldenIndex`/`condition`/`customValue` are read by NOTHING - not even
attempted, since this reader never established a safe interpretation for them).

**Correlating a field's own compact row**: the golden's own `RecordMetadata.mergingProcessId` (a NEW scalar
read this lot, `ebx.RecordLineageReader#readMergingProcessId` - same safe pattern as `#readIsolatedFlag`,
keyed by `functionalId`) is matched against `MergeValueLineageCompact.mergingProcessId`, and the business field
name against `sourceField`, via `createRequestResult("./mergingProcessId=<n> and ./sourceField=<name>")` on
the SAME metadataset table `#resolveMetadatasetTable` already resolves generically by name
(`"MergeValueLineageCompact"` - no change needed to that helper). A null `mergingProcessId` (no real merge has
run for this group yet - the NORMAL, expected state before the first merge) short-circuits before ever
querying the compact table.

**The survivorship-rule sentence** (`FieldMergeLineageSource#functionWords`) is a small, best-effort word list
built from `docs/MATCHING.md`'s own measured record-selection/default-field-merge function names (both the raw
i18n-key middle segments, e.g. `mostCompleted`, and the human words that document also recorded for the
default-field-merge chain, e.g. `"longest"`/`"last update"`/`"max"`/`"min"`/`"most frequent"`/`"most trusted
source"`) - **never verified against a REAL `mergeFunction` string from a real merge**; an unrecognised value
renders as `merge rule '<raw>'`, quoted, never dropped or mistranslated. `modifiedByDataSteward` (when true)
overrides the sentence entirely to "data steward decision"; `manualModified` (when true, and NOT by a data
steward) appends "(manually adjusted)" - both unambiguous booleans, read with no translation needed.

**The updated `fieldLineageNote`** (only ever shown for &gt;1 contributing source, unchanged condition):
`FieldMergeLineageSource.NO_MERGE_YET` when the golden carries no `mergingProcessId` at all (the normal,
un-apologetic case - no merge has run yet), `FieldMergeLineageSource.PARTIAL_OR_UNVERIFIED` when one HAS run
but this reader could not confidently resolve a retained contributor for at least the fields it tried
(honest about the "never verified live" status of the whole mechanism) - replacing the earlier, blanket
`NOT_VERIFIED` constant now that a real (if best-effort) read is actually attempted.

### Screenshots

Rendered from hand-built fixtures (never a live capture - see class-level caveats above) with a small,
throwaway scratchpad harness (`PreviewMain.java`, never committed) calling `RecordLineage.assemble` +
`RecordLineagePage.render`/`graphicScript` directly, served over a local static HTTP server, screenshotted
with headless Chrome (`--headless=new --force-device-scale-factor=2 --window-size=1320,2600`):

- Comparison panel, anchor + 1 suspect (BPCE/ERP vs. a CRM suspect, three plan fields, one `≈` chip on a
  case-only `country` difference): (a local scratch file).
- Comparison panel, anchor + 3 suspects (CRM/SAP/POS, one `=`, one `≈`, one `≠`, one locked
  restricted field on the POS suspect, three independent decision-button rows): (a local scratch file).
- Post-merge field table with a real `KNOWN` retained cell per field (CRM's name, ERP's country - RETAINED
  badges, connector lines, "longest value"/"most trusted source" rule text on the golden cell, and the
  composition bar switching from the neutral fallback to the real "1 CRM · 1 ERP" breakdown): (a local scratch file).

Checked by eye against `docs/mockups/record-lineage.png`: same card/table/chip visual language as the
already-validated "champ par champ" section (no new colours, borders or type scale introduced) - the
comparison panel and its chips are a genuinely NEW element the original mockup never depicted, so this is a
consistency check against the established design system, not a pixel-for-pixel match against a mockup image
that does not cover this panel.

### What the product owner must check once this reaches a real instance (never verified live this session)

1. **The accept/reject calls themselves** - `acceptSuspectsAndMergeGroups`/`rejectSuspects`/`mergeGroup`
   signatures are `javap`-verified and their documented behaviour is quoted above, but no call was ever made
   against a real group. Confirm on `Company` 290/248 (or any other real GOLDEN_WITH_SUSPECTS pair):
   "Confirm duplicate" moves the suspect into the group and (if a merge policy is flagged "used for manual
   merge") produces a real golden record; "Not the same" removes it from the pending-suspects list without
   merging anything.
2. **Confirmed the same day, before this branch's own first commit landed**: `mergeGroup` DOES need an
   explicit merge-policy flag - `get_matching_config` on `/root/Company` at 12:57 answered
   `"useForManualMerge":false` for the policy `matching/configure` had just written that morning. Fixed at
   the source, `master.MatchingPolicyTranslator#buildMergePolicy` now always writes `true` (`docs/MATCHING.md`
   §3.4); `AddonMameSuspectDecision#mergeFailure` also now detects the phrase "manual merge" (case-
   insensitive) in the exception's own localized message and reports the specific
   `ACCEPTED_MERGE_POLICY_NOT_MANUAL` outcome instead of the generic `ACCEPTED_MERGE_FAILED`, for an
   ALREADY-DEPLOYED policy elsewhere that might still carry the old value. Still never seen rendered from a
   REAL exception this session - the coordinator's own live read only confirmed the FLAG's value, not the
   exact wording `mergeGroup` throws for it, so the substring match itself is unverified.
3. **`FieldMergeLineageSource`'s two identification strategies against a REAL `MergeValueLineageCompact`
   row** - whether `recordId` really does hold a contributing master key (strategy 1), whether `sourceValue`
   reliably matches a contribution's own staged/raw value byte-for-byte (strategy 2, watch for a cleaning-step
   difference defeating the exact-match requirement), and what `mergeFunction`'s own real string values look
   like (whether `functionWords`' small map actually covers them, or every real merge shows "merge rule
   '...'"). The FIRST real merge on this instance is the moment to check all three at once.
4. **The comparison panel's own "Open in EBX" links and received-date formatting** for a suspect - built from
   the same `suspectSources[]`/`openUrl` mechanism CLAUDE.md sect9 already documents as unverified live; the
   received-date shown here is a plain ISO-date substring (`yyyy-MM-dd`), not the locale-formatted string the
   graphic screen's own timeline uses - a deliberate simplification for this lot, worth revisiting for
   consistency once the whole screen is checked live together.
5. **The permission redaction paths new to this lot** (`SuspectFieldSet`'s own `isHidden`/`isFieldHidden`
   checks, `decideSuspect`'s own `canReadWrite` re-check) were reasoned through and unit-tested at the pure-
   model level only, the same "not run against a genuinely restricted profile" caveat this document's own
   "Authorisation" section already carries for the read side.

### 2026-09-22 (later) - "Other fields" columns misaligned under the main comparison table, fixed

Branch `fix/suspect-compare-alignment`, worktree `ebx-mdm-factory-compare-align`. the product owner's screenshot of
"Compare with suspects (1)" showed the "Other fields (4)" table's own value/suspect columns starting well to
the left of the main table's above it (value column x&asymp;363 vs x&asymp;398, suspect column x&asymp;754
vs x&asymp;828) - the two tables read as two unrelated blocks instead of one grid. Cause: the main comparison
rows and the "Other fields" rows are two SEPARATE `<table class="mdmf-compare-table">` elements
(`RecordLineagePage#renderComparisonPanel`), each sizing its own columns from its own cell content under the
default `table-layout: auto` - a short field name ("cityKey") in one table and a longer one ("Supplier ID")
in the other gave the two tables two different natural column widths, with nothing tying them together.

Fix: approach (b) from the task brief, not (a). Both tables now open through one shared helper,
`RecordLineagePage#renderCompareTableOpen`/`#renderCompareColgroup`, which writes an identical `<colgroup>`
into both - a field-name column (20%), the anchor "this record" column (20%), then the remaining 60% split
evenly across however many suspect columns there are, computed once from the SAME `suspects.size()` both
tables render for. `lineage.css`'s `.mdmf-compare-table` gained `table-layout: fixed`, which makes that
colgroup authoritative regardless of a given table's own content (cell text wraps instead of growing a
column) - so the browser genuinely cannot size the two tables' columns apart from each other any more, for
any number of pending suspects, not only the one-suspect case in the screenshot. Approach (a) (one `<table>`
with the "Other fields" header as a full-width row) was rejected because the panel already ships a native
`<details>`/`<summary>` disclosure that opens with NO script at all (`renderComparisonPanel`'s own Javadoc);
folding "Other fields" into a `<tbody>` toggled by a group row would need a click handler to show/hide it (a
plain `<tr>` cannot itself be a disclosure widget), trading that already-shipped no-JS behaviour for a new
one just to fix a CSS problem. `RecordLineagePageTest#comparePanelOtherFields_shareSameColgroupAsMainTable`
asserts the two tables' `<colgroup>` markup is byte-for-byte identical (not just visually close) using the
"no plan" fixture, which leaves the main table's own `<tbody>` with zero data rows - the sharpest check that
column width comes from the suspect count alone. `tools/test_local.sh`: green. Alignment corrected, not yet
seen at the screen - never run against a real instance this session, same constraint as the rest of this lot.

## 2026-09-22 (later still) - confirming a suspect really DID merge, branch `fix/confirm-merge-already-done`

Worktree `ebx-mdm-factory-confirm-merge`. The coordinator clicked "Confirm duplicate" for real, on the
lineage screen of record 290 (dataspace `Companies_Master`, dataset `Master`, table `/root/Company`,
metadataset `Master_Company_MDS`, add-on 6.3.0-dev), confirming suspect 248. The screen answered: "The
suspect was confirmed as a duplicate, but the automatic merge could not run. Detail:
`com.orchestranetworks.addon.mame.common.InvalidParameterException: Select two or more records that are not
'Merged' or 'Deleted'`."

### What actually happened: the merge already ran, inside `acceptSuspectsAndMergeGroups` itself

Reading `Master_Company_MDS/root/RecordMetadata` right after that answer told a completely different story:

| key | groupId | state | autoCreated | isolated | goldenId | mergingProcessId |
|---|---|---|---|---|---|---|
| 248 | 1833036450509851 | MERGED | false | false | 322 | 1 |
| 290 | 1833036450509851 | MERGED | false | false | 322 | 1 |
| 322 | 1833036450509851 | GOLDEN | **true** | false | null | 1 |

A brand-NEW record 322 existed in `Master/root/Company` (BPCE, legal form SA, VAT `FR26493455042` from 290,
city "PARIS", `sourceSystem` "ERP" from 290, `sourceId` "001ZAhRgSiG1j5LYkQ" from 248 - every field the
project's own "longest" merge function's own choice). So `Group.acceptSuspectsAndMergeGroups` had ALREADY
performed the real merge - the same "MATCHED groups are merged automatically" behaviour CLAUDE.md &sect;8
already records for a MATCHING run, now confirmed for the manual accept-suspect path too. The subsequent
`MergeOperations.mergeGroup` call this module already made (§B above, "the decision itself") then correctly
refused, because the group's only member left that was neither `MERGED` nor `DELETED` was the golden itself -
a real, documented precondition (quoted above), not a misconfiguration. Telling the steward "the automatic
merge could not run" in that situation was simply WRONG: a golden record plainly existed.

This falsifies the class Javadoc claim §B above quoted from the add-on's own local `Group.html`/
`MergeOperations.html` pages ("neither `accept*` method performs the actual merge by itself... `mergeGroup`
is where the real merge lives") - at least for a group that becomes fully `MATCHED` by the accept call. The
documentation was not wrong to READ, it turned out incomplete for what this build actually DOES.

### The fix: re-read the suspect's own state before ever calling `mergeGroup`

`ebx.MameSuspectDecision#accept` (`AddonMameSuspectDecision`) now, right after `acceptSuspectsAndMergeGroups`
succeeds:

1. Re-reads the SUSPECT's own state through `ebx.MameRecordState` (the same public-API read - `javap`-verified
   `MetadataHelper.getState`/`getTargetRecord` - this module already uses read-only elsewhere). If it is now
   `MERGED` with a resolvable golden key, answers `ACCEPTED_AND_MERGED` with that key directly and skips
   `mergeGroup` entirely - the exact fix for the live scenario above (suspect 248 read back `MERGED`, golden
   `322`, right after the accept call).
2. Otherwise, re-fetches the group and counts how many of its own `getMatchedRecords()` (per `ebx.MameGroups`'
   own established reading of that API, this NEVER includes the group's golden) are neither `MERGED` nor
   `DELETED`. Fewer than two remaining means `mergeGroup` would only ever refuse (its own precondition, quoted
   above) - skipped, plain `ACCEPTED` (no golden key available from this path: the scan cannot see the golden
   by construction, and only the suspect's own state, checked in step 1, can answer that).
3. Only when at least two members are still eligible does it call `mergeGroup`, exactly as before this fix.
4. Belt and braces: if `mergeGroup` throws ANYWAY, the suspect's own state is re-read ONE more time before
   falling back to the generic/specific failure outcome - a golden may have been produced by the add-on's own
   internal handling of the very call that then threw (never observed, purely defensive) - so a steward is
   never told "merge failed" once a golden demonstrably exists for the suspect they just confirmed.

The decision itself (`PostAcceptAction`, a new pure class in `MameSuspectDecision.java`, no EBX import) is
directly unit-tested (`MameSuspectDecisionTest`) against the four input combinations: suspect already merged
with a golden key, merged with no usable key (defensive, falls through), fewer/more than two remaining
eligible members, and an unknown remaining count (`-1`, always falls back to calling `mergeGroup` rather than
guessing "nothing left").

**"The pivot" the task brief asked to re-read alongside the suspect has no `Adaptation` reference in
`MameSuspectDecision#accept`'s own signature** (only `pGroupId`, a `BigDecimal`) - `ebx.RecordLineageReader
#decideSuspect` never passes the anchor record down to this class, and extending the signature only for this
was judged unnecessary: the suspect's own post-accept state already answers the real question ("did a merge
already happen", proven true by the live measurement above) without it, and the group-member count answers
"is there still something to manually merge" independently of which record started the click.

**Never re-verified live this session** (no EBX access, same constraint as the rest of this document) - the
fix is built entirely from the SAME `javap`-verified signatures §B already quotes (`Group.getMatchedRecords()`,
`MetadataHelper.getState`/`getTargetRecord`), plus the one new fact this measurement established (the
automatic-merge behaviour itself). The exact wording of a hypothetical FUTURE `mergeGroup` refusal for a
genuinely different reason (not "already merged") is still unverified, same caveat §B already carries.

### `FieldMergeLineageSource` corrected against the real `MergeValueLineageCompact` row

The SAME live measurement gave the first real read of `Master_Company_MDS/root/MergeValueLineageCompact`
(one row, `mergingProcessId 1`):

```
{"id":1,"mergingProcessId":1,"sourceField":null,"Items":[
 {"fieldPath":"/sourceSystem","recordId":"290", ...,"mergeFunction":"longest", ...,"sourceValue":null, ...},
 {"fieldPath":"/sourceId","recordId":"248", ...,"mergeFunction":"longest", ...},
 {"fieldPath":"/name","recordId":"290", "mergeFunction":"longest"}, {"fieldPath":"/_receivedAt","recordId":"290","mergeFunction":null},
 {"fieldPath":"/cityKey","recordId":"290","mergeFunction":"longest"}, {"fieldPath":"/country","recordId":"290","mergeFunction":"longest"},
 {"fieldPath":"/city","recordId":"290","mergeFunction":"longest"}, {"fieldPath":"/id","recordId":"322","mergeFunction":null},
 {"fieldPath":"/_loadId","recordId":"290","mergeFunction":"longest"}, {"fieldPath":"/legalForm","recordId":"290","mergeFunction":"longest"},
 {"fieldPath":"/vatNo","recordId":"290","mergeFunction":"longest"}, {"fieldPath":"/supplierId","recordId":"248","mergeFunction":"longest"},
 {"fieldPath":"/nameKey","recordId":"290","mergeFunction":"longest"}]}
```

Two of §C's assumptions above were WRONG, both corrected 2026-09-22:

1. **One row per `mergingProcessId`, covering EVERY field - never one row per business field.** The row's own
   `sourceField` was `null`. `ebx.FieldMergeLineageSource#read` no longer filters the row query by
   `sourceField` at all; it fetches the ONE row for the process, then finds the field's own item INSIDE
   `Items[]` by `fieldPath`, which is exactly `"/" + <field name>` (matches every item above, e.g.
   `"/sourceSystem"`, `"/cityKey"`).
2. **`sourceValue` is `null` on EVERY item, technical and business fields alike.** §C's strategy-2 fallback
   (matching `sourceValue` against a contribution's own comparable value) could never once have succeeded on
   this build - REMOVED entirely, not merely deprioritised. The winner is now identified ONLY by `recordId`
   (the winning record's own formatted primary key: `"290"`, `"248"`, or the golden's own `"322"` for a
   technical/PK field like `/id`), matched against the caller's own contributing master keys - task brief:
   "the value is read from the winning source record in the business table (NOT `sourceValue`, which is
   null)" - `FieldMergeLineageSource` never re-reads that value itself; it only names WHICH contributing key
   won, and `ebx.RecordLineageReader` already holds every contribution's own value.

Confirmed correct on this data: `sourceSystem` retained from 290, `supplierId` retained from 248 - exactly the
task brief's own "the default 'longest' chose sourceSystem from 290 and sourceId from 248" (`sourceId` in the
brief's own words is this document's `supplierId`/`sourceId` pair; the measured row names both). `mergeFunction`
is `null` for the technical fields `/id` and `/_receivedAt` - rendered as "recorded by the merge, rule not
named", never dropped. `/id`'s own `recordId` (`322`, the golden itself) is never a "contributing" key in
`ebx.RecordLineageReader`'s own map (an auto-created golden has no `Xref` row of its own, §"resolveSource"
above), so that field correctly answers "unavailable" rather than a wrong guess.

The matching/rule-building logic is split into a new pure seam, `FieldMergeLineageSource#readFromItems`
(`List<?>` of add-on beans in, `Result` out - no `AdaptationTable`, no reflection-target-agnostic anything) -
directly unit-tested (`FieldMergeLineageSourceTest`) against a fixture reproducing the row above verbatim
(`FakeItem`, a plain Java object exposing exactly the five accessor names `stringProperty`/`booleanProperty`
already read reflectively - so the test exercises the REAL matching logic, not a mock of it). Per-field
survivor rules for the technical fields (`sourceSystem`/`sourceId`/...) remain a product decision pending, not
addressed this lot - only documented here, per the task brief.

### The lineage screen for the three records now in play: already correct, no code change needed

Read (not changed) `ebx.RecordLineageReader#computeMerge`/`RecordLineage.deriveState`/
`RecordLineagePage#renderMergeRow`/`#stateWords` against exactly this scenario - viewing 322 (GOLDEN,
`autoCreated true`, two merged members), and viewing either 290 or 248 (MERGED into 322) directly:

- **`deriveState` only ever derives `GOLDEN_WITH_SUSPECTS` when `state.equals("GOLDEN")`** (its own Javadoc,
  already unit-tested by `RecordLineageTest#deriveState_nonGoldenState_neverDerived`, which explicitly asserts
  `MERGED` is never derived into anything else) - a MERGED record can never be mislabelled as an isolated
  golden, by construction, regardless of what its own `isolated`/group-suspect data says.
- **Viewing 290 or 248 directly already renders "Merged into another record" (`lineage.state.MERGED`) plus a
  distinct golden node pointing at 322's own key** (`RecordLineagePage#renderMergeRow`: the arrow always runs
  from the state badge to a `<div class="mdmf-jnode-golden">` carrying `masterTable · goldenKey`, independent
  of whether the anchor itself is the golden) - this already IS "say so and point to the golden". New coverage
  added this lot, `RecordLineagePageTest#mergedRecordPointsToADifferentGoldenNeverRendersAsIsolatedGolden`.
- **Viewing 322 itself (`autoCreated true`, no `Xref` row of its own) never crashes**: `resolveSource` already
  documented "returns null when no Xref row exists... a real, unsurprising case for an add-on-autoCreated
  golden that has no source row of its own, never an error" (quoted from its own Javadoc, written before this
  scenario was ever measured live) - so 322 simply carries no entry in `sourceChains` (no landing/staging
  journey box of its own), while its two REAL contributors (290, 248) still resolve normally through their own
  `Xref` rows. `mergeInfo.autoCreated` correctly reads OFF WHICHEVER RECORD IS BEING VIEWED (`MetadataHelper.
  isAutoCreated(pMasterRecord)`), so it is `true` only when 322 itself is the one being looked at, `false` when
  290/248 are - new coverage, `RecordLineagePageTest#autoCreatedGoldenShowsTheCreatedAutomaticallyNote`.
- **Field-by-field survivorship (`fieldInputs`) already reads off the GOLDEN's own `mergingProcessId`
  regardless of which of the three records is being viewed** (`readMergingProcessId(recordMetadataTable,
  goldenKey)`, not the anchor's own key) - so the "champ par champ" table shows the same, correct survivorship
  whether the steward opened 322, 290, or 248.

**One deliberate design choice, not a bug, worth naming explicitly**: the "record's own field values" column
(labelled "Golden record" in the graphic screen for historical reasons - `RecordLineage.assemble`'s own
`goldenValue` is literally `pInput.recordFields`, i.e. whichever record was actually looked up) shows the
ANCHOR's own current field values when viewing 290 or 248 directly, not 322's synthesized composition. This
was considered and kept: the task brief explicitly allowed "redirect/point to the golden" as one option when a
MERGED record has no meaningful lineage of its own, but 290/248 are NOT meaningless - they remain real,
readable records with their own real values (CLAUDE.md &sect;8: "the losing record stays readable with state
MERGED") - and the screen already tells the steward exactly where the golden lives (the point-to-golden node
above) and shows the correct field-by-field survivorship regardless (the previous bullet). A steward who wants
322's own current composition opens 322 directly. Redirecting the whole "champ par champ" table to read off a
DIFFERENT `Adaptation` than the one just looked up would be a much larger, riskier change to a 1887-line reader
with no live instance to verify it against, for a labelling nuance rather than a correctness bug - not
attempted this lot.

### `tools/test_local.sh`: all green

`MameSuspectDecisionTest` (7 new `PostAcceptAction` cases), `FieldMergeLineageSourceTest` (new file, 8 cases
against the real-row fixture), `RecordLineagePageTest` (2 new cases) all pass alongside the full existing
suite.

## The "Open in the catalog" links REMOVED (2026-09-22, coordinator review, decision by the product owner, branch
`fix/lineage-drop-catalog-link`)

The two "Open in catalog" links added to the graphic's STAGING/MASTER band headers (§"Graphic rebuild" and
the "Coordinator review, 2026-09-22" section above) are gone. the product owner's reason: the screen is about ONE
record; the link jumps to the catalog's page of the whole TABLE without saying so, and answers no question
the reader has at that moment. The reverse direction (catalog → factory, a data product back to a record)
still keeps a meaning and is unaffected. Removed end to end: `RecordLineageReader#catalogUrl` and its two
call sites, `CatalogReader#findAssetId`, `RecordLineage.Input#stagingCatalogUrl`/`#masterCatalogUrl` and
their JSON emission (the `Input` constructor overload chain collapsed to drop them, every remaining
caller/fixture updated to the new arities), the graphic's `"openInCatalog"` label key and pass-through, the
`lineage.graphic.openInCatalog` message-bundle entry in both locales, and the band-header link markup in
`lineage-graphic.js`. The `catalogUrlsPassThroughWhenPresentAbsentOtherwise` test is deleted; the other
per-band content (the "Open in EBX" record links, the golden card, the timeline) is untouched. See
`docs/CATALOG-LINEAGE.md` §4 for the matching note on the catalog-lineage side.
## Record ids everywhere, and the confirm prompt moved to the top (2026-09-22, branch
`feature/lineage-record-ids-scroll`, worktree `ebx-mdm-factory-lineage-ids`)

Two small UI fixes from the product owner after seeing the "Record lineage" screen live on golden 322 ("BPCE") and a
suspect of "HSBC BANK": the screen named every record by its title only, nowhere its own EBX id; and clicking
a suspect on the graphic opened the "Compare with suspects" panel, but the confirm prompt rendered at the
BOTTOM of the panel, below "Other fields (N)", out of the viewport - he never saw it. **Not run live this
session either** (no EBX instance available) - verified by the full unit suite (`RecordLineagePageTest`,
`RecordLineageGraphicTest`) plus reading, same discipline as every prior lot in this document.

### 1 - the EBX id, as a small muted chip, everywhere a record is named

`RecordLineage`'s own value objects already carried the id under a different name at every call site -
`Input#masterKey`/`MergeInfo#goldenKey`/`SourceChain#stagingKey`/`SourceChain#landingKey`, each one already
`Adaptation.getOccurrencePrimaryKey().format()` read once by `ebx.RecordLineageReader` (see that class's own
Javadoc, "masterKey" section) - so this lot adds NO new field anywhere in the JSON `RecordLineage.assemble`/
`RecordLineageGraphic.build` produce, only a rendering convention on top of what already exists:
`RecordLineagePage.idChip(locale, key)` returns `<span class="mdmf-id" title="EBX record id">&middot; {key}</span>`
(empty string when the key is null/blank, so every call site can pass one unconditionally), styled by a new
`.mdmf-id` rule in `factory/lineage.css` (monospace, reusing `.mdmf code`'s own stack from `factory.css`;
muted colour via the shared `--muted` token).

Wired into every place a record is named:

- the compact golden-record header (`RecordLineagePage#renderRecordHeaderCompact`) - the id chip sits INSIDE
  the `<h2>`, right after the name, only when `masterLabel` is present (when it is absent the title already
  fell back to the bare key two lines up - a chip there would read "1 &middot; 1").
- the noscript journey's landing/staging nodes (`#renderSourceRow`) - landing's OWN key (`landing.key`, a
  DIFFERENT table's primary key than the master/staging ones sitting next to it - `sourceId`, shown in the
  same node, is the source system's own business identifier, not an EBX key at all) and staging's own key
  (`staging.key`), each now a chip after the node's bold title rather than baked into the same bold run.
- the "point to the golden" node (`#renderMergeRow`) - was one bold run `"{masterTable} &middot;
  {goldenKey}"`; the key is now a chip after `<b>{masterTable}</b>`.
- the "Compare with suspects" panel's column headers (`#renderComparisonPanel`/`#renderSuspectHeader`) - the
  anchor's own id next to "This record", each suspect's own id next to its system name (skipped when there is
  no system name to show it next to - the bare key is already the header's title then).
- the suspect decision block's own label (`#renderSuspectDecision`) - `suspectLabel` no longer appends the
  key itself (it used to, inconsistently: `"{system} &middot; {key}"` when there was no group-member label,
  the bare key alone otherwise) - it now returns ONLY a human title or `null`, and the id chip renders
  separately, exactly once, only when there is a title to sit next to (coordinator's own example: "HSBC BANK
  &middot; 274 - Confirm this suspect is a duplicate?").

The graphic (`lineage-graphic.js`) cannot reference a CSS class from inside inline SVG the way HTML markup
can, so its three id chips (SOURCE SYSTEM card - a suspect lane's own `masterKey`, a DIFFERENT master record
than the golden's, shown only for `kind==='suspect'`; LANDING card - `landingKey`, alongside the existing
`receivedAtDisplay` line; STAGING card - `stagingKey`, RESTYLED from a bold/dark line that looked like a real
record name into a muted, monospace one) repeat the same font stack and colour as attributes on each `<text>`
element directly, plus an SVG `<title>` child for the tooltip. The shared tooltip string
(`lineage.id.tooltip`, "EBX record id" / "Identifiant d'enregistrement EBX") reaches the graphic through one
extra `labels.idTooltip` entry `RecordLineageGraphic#buildLabels` adds outside its usual `"lineage.graphic."`-
prefixed key array (a deliberate exception - one shared string for the HTML and SVG halves of this screen,
never two that could drift apart).

### 2 - the confirm prompt, moved to the top of the panel, and a scroll nudge after the postback

Two independent halves, per the task brief's own split:

- **(a) Server-rendered position.** `RecordLineagePage#renderComparisonPanel` used to render the per-suspect
  decision block (the initial Accept/Reject buttons, or - once pending - the "Confirm this suspect is a
  duplicate?"/"Confirm this suspect is NOT the same record?" prompt with its own Confirm/Cancel pair) LAST,
  right before the closing `</details>`, after the main comparison table AND the collapsed "Other fields (N)"
  table. Moved verbatim (same helpers, `suspectLabel`/`renderSuspectDecision`, unchanged behaviour) to right
  under the panel's own lead paragraph/read-only note, before the comparison table - so it is the first thing
  a reviewer sees once the panel is open, whether it opened by a native `<details>` click or by
  `lineage-graphic.js`'s own `openCompare`.
- **(b) Scroll after a postback.** Clicking Accept/Reject is a real postback (CLAUDE.md &sect;9's own hidden-
  submit technique, `AbstractRecordLineageUserService#decide`/`pageJs`) that sets `pendingSuspectAction`/
  `pendingSuspectKey` and re-renders the WHOLE pane fresh - the panel then renders `open="open"`
  (`renderComparisonPanel`'s own `forceOpen`), but a freshly server-rendered page never auto-scrolls anywhere
  on its own; without (a) the confirm prompt was invisible, and even with (a) alone, the panel itself can sit
  well down the page (below the graphic canvas) on a long record. `RecordLineagePage.pendingSuspectScrollScript
  (pendingSuspectAction, pendingSuspectKey)` returns `null` on every OTHER render (a plain lookup, a Cancel, a
  Confirm that already completed - `AbstractRecordLineageUserService#write` then skips the `addJS_cr` call
  entirely, same "null means nothing to do" convention `graphicScript` already uses) and a tiny guarded script
  otherwise: `(function(){var el=document.getElementById('mdmfCompare');if(el&&el.scrollIntoView){el.scrollIntoView({behavior:'smooth',block:'start'});}})();`
  - `block:'start'` (not `'nearest'`, unlike `lineage-graphic.js`'s own `openCompare`, which only nudges an
  already-visible panel a suspect-lane click reveals): this is the FIRST thing the reviewer should see right
  after the postback, so pulling it to the very top of the viewport is deliberate. There is no client-side
  flag tracking "already scrolled once" - the SERVER simply never emits the script except on the one render
  where it is true, which is what makes it run "once, only when the panel was just opened".

### Not verified live

Everything in this section, same constraint as every prior lot: no EBX instance was available. Specifically
unverified: how `.mdmf-id` actually looks rendered in a real browser (font metrics, line wrapping next to a
long masterLabel); whether the SVG id chips visually collide with the existing cleaning-step chips or the
"suspect" badge on a narrow viewport (the coordinates were not recomputed, only the new text elements were
measured by inspection of the surrounding fixed y-offsets, not by rendering); and the actual postback/scroll
behaviour end to end (does the pane's own scroll container - EBX's `UserServicePane` may or may not be an
iframe with its own scrollbar - actually respond to `scrollIntoView` the way a plain page does). All three are
worth a first-look check once this reaches a real instance.

### `tools/test_local.sh`: all green

`RecordLineagePageTest` (id chips in every listed location, the decision block's new position, the two
`pendingSuspectScrollScript` states - null and emitting), `RecordLineageGraphicTest` (the shared `idTooltip`
label resolves in both locales) all pass alongside the full existing suite.

## Cross-references (2026-09-23, branch `feature/lineage-xrefs`, worktree `ebx-mdm-factory-xrefs`)

the product owner's request: "a data steward must be able to see, for a golden record, ALL its cross-references: the
`Xref` rows of the golden itself (an auto-created golden has none) plus those of every record merged into
it." Two deliverables, both a PURE PROJECTION of what this document already documents above - no new EBX
read, no touch to `ebx.RecordLineageReader`/`ebx.GoldenKeyRefresher`/`master.GoldenKeyRefreshDecision`/
`ebx.MameSuspectDecision` (a second agent's own files, working in parallel on `Xref.goldenKey`'s own
reliability - out of scope here by the task's own instruction, and never touched). **Never run against a live
instance this session** (no EBX access, same constraint as every prior lot in this document) - every claim
below is either read directly from the existing source this lot builds on, or verified by the full unit
suite.

### The pure projection: `lineage.CrossReferences`

A new, small pure class (Jackson only, no EBX type, package `lineage` next to `RecordLineage` itself):
`CrossReferences.project(JsonNode pLineage)` takes the EXACT SAME answer `ebx.RecordLineageReader.read(...)`
already produces (the one this document's own "What the route returns" section documents in full) and
reshapes its `sources[]` array - never `suspectSources[]`, since a pending suspect has not been confirmed as
a duplicate yet and is therefore not a cross-reference at all, the identical distinction the "champ par
champ" field table already draws - into a flat, one-row-per-`Xref` array:

```json
{
  "project": "Companies", "entity": "Company", "masterTable": "/root/Company", "masterKey": "12",
  "present": true, "state": "GOLDEN", "goldenKey": "12",
  "xrefs": [
    { "masterKey": "12", "golden": true, "sourceSystem": "ERP", "sourceId": "SUP-00231",
      "stagingTable": "Company", "stagingKey": "ERP|SUP-00231", "landingDataset": "ERP",
      "landingTable": "SUPPLIER", "landingKey": "SUP-00231", "loadId": "L20260920-131535-1",
      "receivedAt": "2026-09-20T13:15:35Z", "restricted": false,
      "landingUrl": "...", "stagingUrl": "..." },
    { "masterKey": "7", "golden": false, "sourceSystem": "CRM", "restricted": true }
  ]
}
```

- `present`/`state`/`goldenKey` mirror `merge.present`/`merge.state`/`merge.goldenKey` of the source JSON -
  `state`/`goldenKey` are `null` whenever `present` is `false` (any of the four reasons `MergeInfo` already
  documents: matching never ran, the add-on is unavailable, this record has no metadata yet, or the add-on's
  own metadataset could not be read). `xrefs` is still projected in that case - the two facts are
  independent, and a record with contributing sources but no merge information yet is a normal, expected
  state, not an error.
- **A restricted row stops at `masterKey`/`golden`/`sourceSystem`/`restricted:true`**, mirroring exactly what
  `RecordLineage#assemble` already left in the source JSON for that entry (`RecordLineage.SourceChain`'s own
  "all-or-nothing" redaction rule, documented above under "Authorisation") - `CrossReferences#projectSource`
  returns as soon as it sees `restricted:true`, so there is never anything to accidentally read from a
  restricted entry, whatever else that entry's JSON might (in a deliberately adversarial test fixture) still
  carry.
- **`landingUrl`/`stagingUrl` are carried through in addition to the field list the task brief named** (
  `masterKey`, `golden`, `sourceSystem`, `sourceId`, `stagingTable`, `stagingKey`, `landingDataset`,
  `landingTable`, `landingKey`, `loadId`, `receivedAt`, `restricted`) - a deliberate, additive choice: the
  Record lineage screen's own "Cross-references" panel (below) needs them for its "open in EBX" links, and
  `CrossReferences` is the ONE place both the screen and the REST route read a cross-reference row from, so
  leaving the URLs out here would have forced the screen to read `sources[]` a second time, duplicating this
  exact projection instead of sharing it. Absent (never emitted, never `null`) whenever the reader could not
  build one, or the row is restricted.
- **`Xref.goldenKey` is never read or written by this class.** `goldenKey` in the JSON above is the LIVE,
  per-record merge answer already in the source JSON (`merge.goldenKey`, resolved through `RecordMetadata
  .goldenId` at render time, exactly as `docs/MASTER-ZONE.md` &sect;3.4 already documents and recommends) -
  not the factory's own `Xref.goldenKey` column, which a second agent is making reliable in parallel this
  same day and which this lot deliberately does not touch, read, or depend on.

### Deliverable 1: the "Cross-references" table on the Record lineage screen

`RecordLineagePage#renderCrossReferencesPanel`/`#renderCrossReferenceRow` - a plain `<table class="mdmf-table
mdmf-xref-table">`, one row per `CrossReferences.project(pLineage)`'s own `xrefs[]` entry, columns: source
system, source id, master record (the row's own key, plus a small accent "Golden record" chip on the ONE
entry whose `golden` flag is true - the same flag `RecordLineage.SourceChain#golden` already carries through
the whole screen, confirmed correct even when the record being VIEWED is itself a merged, non-golden member,
per this document's own "already correct, no code change needed" section above), staging record (key, with
its own "open in EBX" link right under it when `stagingUrl` is present), landing record (key, same treatment
with `landingUrl`), load id, received (the date portion of `receivedAt`, same `substring(0, 10)` convention
`renderSuspectHeader`'s own "received on" line already uses). A restricted row collapses everything past its
own source system into a single `colspan="6"` cell carrying the SAME "restricted" word (`lineage.record
.restricted`, reused rather than a new key) every other restricted thing on this screen already shows.

**Design choices made, and why**:

- **Placed after the journey graphic and the existing "Compare with suspects" panel, and OUTSIDE the
  `<noscript>` block** - the task brief's own instruction, and a deliberate one: unlike the SVG journey/field
  tables, this table is plain HTML that needs no script to render or to read, so it is shown whichever way
  the graphic itself renders, and it is a new, independent addition rather than folded into either of the two
  `<noscript>`-only fallback tables (which show per-FIELD contributions, not per-`Xref` rows with landing/
  staging keys and open links - genuinely different information, not a duplicate).
- **The "open in EBX" links sit under each cell's own key** (staging cell gets `stagingUrl`, landing cell
  gets `landingUrl`), rather than a separate "Open" column with two links crammed together - the SAME
  per-record link placement `renderSuspectHeader`/`renderRecordHeaderCompact` already use elsewhere on this
  screen, reusing the identical label (`lineage.graphic.open`, "Open in EBX") rather than inventing a second
  wording for the same action.
- **`table-layout: fixed` plus an explicit `<colgroup>` (ORIGINAL choice, REVERTED 2026-09-23 - see below)** -
  borrowed the same width discipline `.mdmf-compare-table` uses (PLAN.md's own note on the "Other fields"
  alignment fix, 2026-09-22), even though there is only ONE table here (no second table to keep in sync with,
  unlike the compare panel's own main/"Other fields" pair). That turned out to be the wrong reason to copy the
  discipline: `.mdmf-compare-table` needs a fixed layout because it is genuinely two separate `<table>`s that
  must size identically to stay aligned; a single table with no sibling to align with only needed its columns
  sized from their own content, and a hard-coded 7-way percentage split was a guess that broke down under
  ~800px wide - measured live by the product owner at 757px: the "SOURCE SYSTEM"/"SOURCE ID" headers overlapped (the
  base `.mdmf-table th` rule is `white-space: nowrap`, so a header too wide for its fixed column spills into
  the next one instead of wrapping) and a source id wrapped one character per line (`overflow-wrap: break-word`
  forced to break anywhere because the fixed column left no room for even one full token). Fixed on branch
  `fix/self-heal-isolated-and-xref-columns`: `renderCrossReferencesPanel` no longer emits a `<colgroup>` for
  this table, `.mdmf-xref-table{table-layout:fixed}` is gone, and `lineage.css` instead lets the browser size
  columns from content while still protecting against the original failure modes - headers get
  `white-space: normal` (can wrap instead of overlapping), cells keep `overflow-wrap`/`word-break` so a long id
  can still wrap when a narrow pane genuinely has no room, and the two key columns (source id, master record)
  get `white-space: nowrap` above a `@media (max-width: 600px)` breakpoint so a record key does not needlessly
  split across lines on anything wider than a phone. `.mdmf-compare-table`'s own colgroup/fixed-layout is
  untouched - this reverts the choice for `.mdmf-xref-table` only, not the pattern itself.
- **No new colour, no new palette token** - `mdmf-chip-accent` (already used for a source-system chip
  elsewhere on this screen) marks the golden row; `mdmf-chip-target` (already the page's own "not visible"/
  "restricted" treatment) marks a restricted row; `.mdmf-table`'s own base look (`factory.css`) is reused
  rather than a third table style.
- **Localised labels added to the existing bundle pair** (`Messages_en.properties`/`Messages_fr.properties`,
  `lineage.xref.*`) - the "restricted" word and the "Open in EBX" link label are REUSED (`lineage.record
  .restricted`, `lineage.graphic.open`) rather than duplicated under a new key, so the two never drift apart
  in translation.

### Deliverable 2: `GET /projects/{code}/xrefs/{entity}?key=<formatted master primary key>`

`rest.FactoryResource#recordCrossReferences` - deliberately a near-copy of `#recordLineage` (the `/lineage`
route) rather than a call to it: it calls the EXACT SAME `RecordLineageReader.read(session, dataset,
projectCode, entity, key)` entry point, then answers `CrossReferences.project(lineage).toString()` instead of
the lineage JSON itself. Same authorisation (any authenticated user, `RecordLineageReader` itself enforcing
the caller's own EBX read permissions record by record and field by field - see "Authorisation" above), same
`404` (unknown project/entity/key, or a master key the caller's session may not see - indistinguishable from
"does not exist"), same `400` (a blank/unparsable `key`), same `500` (naming the failed step, never a value,
for a failure outside the merge block) - because it is, literally, the same read, only re-shaped afterwards.
The class Javadoc of `rest.FactoryResource` gained one more `<li>` entry, right after `/lineage`'s own, in the
same style.

### Tests

- `lineage.CrossReferencesTest` (new, JUnit-free `main`, like every other pure-model test in this module):
  the task brief's own scenario (a golden and two merged members, one restricted) checked field by field on
  the visible rows and the top-level anchor; the restricted-row all-or-nothing rule (`size() == 4`, the SAME
  defense-in-depth discipline as `RecordLineageTest#restrictedSource_onlySystemNameEmitted` - a fixture that
  deliberately still carries values a restricted entry should never leak, proving `CrossReferences` itself
  never reads them rather than merely trusting an already-clean fixture); `suspectSources[]` never
  projected even when the lineage JSON carries one; `merge.present: false` leaving `state`/`goldenKey` null
  while `xrefs` is still projected; a source with no "open in EBX" link simply omitting `landingUrl`/
  `stagingUrl` (never a `null` placeholder); an auto-created golden with zero contributing sources answering
  an empty `xrefs` array, not an error; row order preserved verbatim (never sorted).
- `lineage.RecordLineagePageTest` gained four cases: the same golden-plus-two-merged-members-one-restricted
  fixture rendered through `RecordLineagePage.render` (title, every column, the golden mark on exactly one
  row, both "open in EBX" links, the restricted row leaking nothing and showing the shared "restricted"
  word); the "no contributing source" empty-table notice; a pending suspect never appearing in the
  cross-references table even though the SAME lineage JSON carries it in `suspectSources`/`merge.group
  .suspects` for the (unrelated) "Compare with suspects" panel; a script-payload source system escaped the
  same way every other value on this screen already is.
- `tools/test_local.sh`: all green (`CrossReferencesTest`, the four new `RecordLineagePageTest` cases,
  and the full existing suite - 130 test classes, including every one this document's earlier sections
  already named). Caught and fixed the SAME class of bug CLAUDE.md &sect;14/this document's own 2026-09-22
  "second pass" section already recorded once before: the French message-bundle text for this lot was typed
  with `\uXXXX` escapes that arrived on disk as raw UTF-8 bytes instead (a tool-call string containing a
  literal accented character is decoded to the real character before it ever reaches a file) - caught
  immediately by the script's own portable ASCII check, fixed with the same small Python re-escaping pass
  that note already documents, verified clean afterward.

### What was NOT run live, honestly

Everything in this section, same constraint as every prior lot in this document: no EBX instance was
reachable this session. Specifically unverified:

1. **The route itself, end to end** - `GET /projects/{code}/xrefs/{entity}?key=...` was never called against
   a real EBX instance; its correctness rests entirely on `RecordLineageReader.read`'s own, already-documented
   live verification (this document's earlier sections) plus the pure projection's own unit tests.
2. **How the table actually looks in a real browser** - column widths at real content lengths, whether the
   `colspan="6"` restricted-row treatment reads cleanly next to a fully-populated row above/below it, and
   whether the "open in EBX" links under the staging/landing keys are visually clear rather than cluttered -
   none of this was rendered in an actual browser this session (unlike the graphic screen's own earlier
   lots, which WERE screenshotted with headless Chrome against hand-built fixtures - not repeated here).
3. **A real golden record with a real merged member and a real restricted source, all three at once** - every
   fixture above is built from `RecordLineage.assemble`, never a live capture; the coordinator's own
   BPCE/HSBC BANK live measurements (record 322/290/248, `Companies_Master`/`Master`/`/root/Company`) never
   included a restricted source, so the restricted-row rendering specifically has no live counterpart to
   check against yet.

### `tools/test_local.sh`: all green

## Lookup card hidden when opened from a record (2026-09-23)

Branch `feature/lineage-hide-lookup-from-record`, worktree `ebx-mdm-factory-lineage-lookup`. **Never run
live this session** (the coordinator's own constraint, unchanged) - a pure-rendering change, verified by
`RecordLineagePageTest` and `tools/test_local.sh` only.

The screen has two entry points sharing one renderer (see "Screen" and "Record-level action on a master
record", above): `RecordLineageUserService` (`OnTableView` on `Project` - a steward genuinely picks
project/entity/key) and `RecordLineageOnRecordUserService` (`OnRecord`, module-level - project/entity/key
are already resolved from the record being viewed in `setupObjectContext`). On the second entry point, the
lookup card at the top (`renderHeader`'s own title, lead sentence, "Project: … / Change project", the
Entity select, the Key input, the "Look up" button) only ever restated values the result cards below already
show ("BPCE · 322", "Company · 322 · 2 source(s)") - the product owner's own request, 2026-09-23: hide it entirely on
that entry point.

- **`RecordLineagePage.Input` gained `recordBound`** (a new 13-argument constructor; the existing
  12-argument one now delegates to it with `false`, so every existing caller/fixture is unaffected) -
  `renderHeader` returns immediately, rendering nothing at all, when it is `true`. The notice/warning
  (`render`'s own block, right before `renderHeader` is called) and every result card after it (compact
  header, graphic container, comparison panel, cross-references panel, the `<noscript>` fallback) are
  rendered by `render` independently of `renderHeader`, so a failed lookup from a record still shows its own
  message, and nothing else on the page changes.
- **`AbstractRecordLineageUserService` gained a small hook, `isRecordBound()`** (package-private, `false` by
  default - `RecordLineageUserService` never overrides it, so its own screen is untouched), read once per
  render in `write(...)` and threaded into the `Input` it builds. `RecordLineageOnRecordUserService`
  overrides it to `true`.
- **The hidden `mdmfProjectCode`/`mdmfEntity`/`mdmfKey` inputs needed no change at all**: `renderHiddenFields`
  already writes them unconditionally, from `Input#selectedProjectCode`/`#selectedEntity`/`#keyText`, right
  at the top of `render` - *before* `renderHeader` is even called, and independently of it. Since
  `AbstractRecordLineageUserService.decide(...)` re-reads `mdmfProjectCode`/`mdmfEntity`/`mdmfKey` from the
  posted request on EVERY round trip (a suspect accept/reject/confirm/cancel included - see `decide`'s own
  code, and "The two-step confirmation" above), and `RecordLineageOnRecordUserService.setupObjectContext`
  already resolves all three before the first render, these hidden inputs simply keep carrying the record's
  own resolved values whether or not the visible form exists. Verified directly (not just reasoned about):
  `recordBoundTrue_hidesLookupCard_hiddenInputsCarryResolvedValues_resultCardsStillRender` asserts the exact
  `<input type="hidden" id="mdmfProjectCode" .../>` (etc.) markup is present with the resolved values, on a
  page that also asserts no "Look up" button, no project/entity select, no key input.
- **Tests**: `RecordLineagePageTest` gained
  `recordBoundTrue_hidesLookupCard_hiddenInputsCarryResolvedValues_resultCardsStillRender` (the above, plus
  the compact header/graphic container/merge-absent notice still rendering) and
  `recordBoundFalse_rendersByteForByteUnchanged` (the SAME fixture rendered through the 12-argument
  constructor - what every pre-existing caller uses - and through the new 13-argument one with `recordBound`
  passed explicitly as `false`, asserted `.equals()` on the two HTML strings, plus the lookup card's own
  title/button/select/input still present). `tools/test_local.sh`: all green.
- **What was NOT run live, honestly**: nobody opened either entry point in a real browser this session (the
  worktree's own constraint) - confirm by eye, once deployed: the record-level action's screen no longer
  shows the project/entity/key form at all, a decision on a suspect from that screen still round-trips
  correctly (the hidden inputs argument above rests on reading `decide`'s own code, not on watching a real
  click), and the `OnTableView` entry point's screen is visually unchanged.
