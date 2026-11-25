# Project explorer (PLAN.md screen 6)

Written 2026-09-23 on branch `feature/project-explorer` (worktree
`ebx-mdm-factory-project-explorer`). **Written, never deployed, never seen running** — the EBX instance was
down for this whole lot, so nothing here was verified live: no `install.sh`, no Tomcat restart, no MCP call.
`tools/test_local.sh` is green (XSD regeneration diff - unchanged, no XSD edit in this lot -, message-bundle
ASCII check, CSS comment-balance check, compile, full test suite including the three new test classes this
lot adds).

**2026-09-23, navigation fix, branch `fix/explorer-links-in-perspective`.** The `/ebx/?...` web-component
link forms below were measured live by the coordinator once the instance came back up: `ProjectWizard` opened
with no Close button and no way back to the explorer, and the click dropped the user out of the `MdmFactory`
perspective entirely (a web component opens its own minimal-scope session, CLAUDE.md §9's own warning). All
three links were rewritten onto the `ebx-ui` data route — see "The three link forms" below, now current — and
a small contextual-return mechanism sends the Wizard/Cockpit's Close button back to the explorer instead of
the native `Project` table (see "Contextual return" below). **The two new `ebx-ui` forms themselves were
re-measured live by the coordinator the same day**: both open the target service inside the perspective, with
its own Close button and its own menu — confirmed on `Companies` for both the wizard's `serviceParams` form
and the cockpit's `predicate` form. The contextual-return piece (Close going back to the explorer specifically,
rather than to the native table) was written in response but not yet independently re-verified live.

## What it is

A faceted overview of every `Project` row, the same look and behaviour as the connector explorer / data
marketplace (`FacetedPage`): a search box (client-side, accent-insensitive, every typed word must match), a
left rail of check-box facets (Status, Style, Mode, Owner), advanced filter pills folded under the search row
(Source system - multi-valued -, Zones), a line of removable active-filter chips, a sort control (name /
creation date / sources), groups of cards shown two rows then "Show more", a "N / M results" counter, an
honest empty state, and the rail-open/closed state remembered per browser (all of this is `FacetedPage`'s own
behaviour, unchanged). One card per project: label, code, description (clamped), a status pill, style/mode
chips, owner, creation date, source count with the source systems named, staging entity count, three
landing/staging/master zone chips, the last pipeline activity, and two action buttons, Wizard and Cockpit.

## Files

- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/ProjectExplorerServiceDeclaration.java` - the
  `UserServiceDeclaration.OnTableView` on `/root/Project`, service key `ProjectExplorer`.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/ProjectExplorerUserService.java` - reads every
  `Project` row via `selection.getAllRecords()` (never the filtered table-view selection: this screen IS the
  overview, so a leftover native quick-search or saved view on the table must never hide a project from it -
  unlike the connector explorer, which deliberately reads the table's own filtered selection because it sits
  ON that table view). Per project, three more small reads, each a plain predicate on an indexed foreign key
  (`./project=`): `ProjectSource` (count + distinct `sourceSystem` codes), `StagingEntity` (a plain row count -
  every entity ever proposed, not only the ones `CockpitData` scopes its own staging volumes to, since this
  overview answers "how far has staging gone" rather than "what is actually generated"), and the single most
  recently started `PipelineStep` (a cheap max-of-`startedAt` scan, never `CockpitData`'s full per-source
  grouping - this screen has no use for it). All three cost roughly what `FactoryCockpitUserService` already
  pays for ONE project's own Cockpit, paid here for EVERY project on the table: a table with hundreds of
  projects would run roughly 3x that many extra queries to render this screen. Accepted for this lot (the task
  brief explicitly asks for these counts); worth revisiting with a cached/denormalized count if the factory
  ever manages that many projects.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/ProjectExplorerPage.java` - the PURE page (a
  `Model`/`Card` pair of plain value classes, `static String render(Model)`, `static String script(Model)`),
  no EBX type anywhere, next to `CockpitPage`. Facet vocabularies (which status/style/mode/owner/source-system/
  zone values are even offered) are computed from `Model.cards` itself, the same "only a value at least one
  card actually carries" convention ebx-connectors' own category/status/connection facets already use.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/FacetedPage.java` - copied from ebx-connectors'
  class of the same name (2026-09-23) and made genuinely pure: every method now appends to a caller-supplied
  `StringBuilder` instead of writing to a live `UserServicePaneWriter`, and the `stylesheets()` method (which
  needed a live writer to resolve a versioned resource URL) is gone - the calling `UserService` links
  `factory/factory.css` + `factory/explorer.css` itself, the same way every other screen of this module already
  loads its own stylesheets. CSS classes are renamed `ftcx-` &rarr; `mdmx-` (rail/tools/facets) and `ftcc-` &rarr;
  `mdmc-` (cards/grids) throughout, so nothing collides with ebx-connectors' own stylesheets if both happen to
  be loaded in the same browser tab.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/FactoryLinks.java` - the three navigation links
  (Cockpit, Wizard, New project) plus the native-table-view link, all pure, all unit-tested (`FactoryLinksTest`).
- `ebx-mdm-factory/src/main/webapp/www/common/stylesheets/factory/explorer.css` - the facet rules of
  ebx-connectors' `facets.css` and the generic card/grid/tools/group/count/empty-state rules of its
  `connector-catalog.css` it still needs, both renamed as above, on top of `factory.css`'s own colour tokens
  (never redeclared here - the wrapper element carries `.mdmf` for the tokens, `.mdmc-explorer` for the
  layout, and a bare `.mdmx` marker, mirroring ebx-connectors' own three-class root wrapper). Connector-specific
  rules that don't apply to a project card (logo, readiness/connection state, pricing badge, favorite star) were
  left out rather than ported and unused.
- `ebx-mdm-factory/src/main/webapp/www/common/stylesheets/factory/factory.css` - one addition, `.mdmf-chip-info`
  (the `--info`/`--info-soft` tokens already existed for `.mdmf-notice-info`, but had no chip variant yet - a
  project's LANDING/STAGING status pill needed one).
- `ebx-mdm-factory/src/main/resources/com/on/ebx/factory/ebx/ui/Messages_en.properties` /
  `Messages_fr.properties` - every `explorer.*` key (FacetedPage's own generic vocabulary, ported from
  ebx-connectors' wording, plus this screen's own card/filter text), two new `wizard.*` keys for the Wizard
  link's launch parameter and the "unknown project code" notice. Every accented character is a `\uXXXX` escape,
  checked by `tools/test_local.sh`'s ASCII scan (which is exactly how a stray literal Unicode character that
  crept in while drafting this lot's message bundles - a shell heredoc quietly turned a typed `—`/`é`
  escape into the real character - was caught and fixed before this lot was done).
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/ProjectWizardServiceDeclaration.java` /
  `ProjectWizardUserService.java` - the new `project` input parameter and its resolution (see below).
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/FactorySchemaExtensions.java` - registers
  `ProjectExplorerServiceDeclaration`.
- `ebx-mdm-factory/src/main/resources/com/on/ebx/factory/ebx/MdmFactory.perspective.json` - the new "Project
  explorer" entry; **2026-09-23 update**: the plain "Projects" table item is now REMOVED from the packaged
  JSON entirely (it no longer merely loses `default: true` - see "Perspective" below).
- Tests: `ProjectExplorerPageTest`, `FactoryLinksTest` (both `ebx/ui`), `ProjectExplorerPerspectiveTest`
  (`ebx`, parses the packaged JSON with `PerspectiveInstaller.definition()`).

## The three link forms (`FactoryLinks`) — rewritten 2026-09-23, see the note at the top of this file

All four links now share one base, `FactoryLinks#tableUri`, built exactly like `advancedViewUrl` already was
(the dataset's REAL home key/name, `getStringName()` never `toString()` - CLAUDE.md's own warning):
```
/ebx-ui/ui/advanced/data/<dataspace key>/<dataset name>/root/Project
```
`ProjectExplorerPage.Model` now carries the plain `dataspaceKey`/`datasetName` strings instead of a
pre-built `advancedViewUrl` - the page itself is the one place that decides every link's shape, through
`FactoryLinks`, so a future change to the URL form never has to touch `ProjectExplorerUserService` too.

- **Cockpit of project `X`** - `FactoryCockpit` is `OnRecord`, so the link selects the record with a
  `predicate` query parameter on its own primary key, `./code`, next to `service=FactoryCockpit&serviceMode=
  RECORD` - the same technique measured on ebx-data-catalog's `TagReviewUserService#uri`:
  ```
  <base>?predicate=./code%3D'X'&service=FactoryCockpit&serviceMode=RECORD
  ```
  Built as `queryValue("./code=" + FactoryHtml.quote(code)).replace("%2F", "/")`: the whole predicate is run
  through the same `URLEncoder`-based `queryValue` as every other value here (so `=` becomes `%3D`, a quote
  `%27`, a space `%20`, `[`/`]` `%5B`/`%5D`), then its own single `%2F` (from the predicate's leading `./`) is
  turned back into a literal slash - `TagReviewUserService`'s own comment records why: EBX's `ebx-ui` route
  answers "an error prevented some elements from loading" on `predicate=.%2Fcode%3D...` next to a `service=...
  &serviceMode=RECORD` pair, and only accepts the slash literal. `FactoryLinksTest` checks a code carrying a
  quote, a space and a bracket (`It's 2 [x]`) produces the exact predicate
  `./code%3D%27It%27%27s%202%20%5Bx%5D%27`, with no other stray `%2F` anywhere in the URL.
- **Wizard on project `X`** - `ProjectWizard` is `OnTableView` (no record to select), so the code cannot
  travel as a plain query parameter: CLAUDE.md's own note (confirmed live on ebx-connectors/ebx-data-catalog,
  2026-09-14/15) is that the `ebx-ui` route silently drops one. It travels instead as `serviceParams`, the
  base64 of a small JSON object - the same shape and encoding as ebx-connectors' `ConnectorForm#serviceParams`/
  `flowRunURI` and ebx-data-catalog's `SourcePanes#runURI`:
  ```
  <base>?service=ProjectWizard&serviceMode=TABLE_VIEW&serviceParams=<base64 of {"project":"X"}>
  ```
  For `X = Companies`: `serviceParams=eyJwcm9qZWN0IjoiQ29tcGFuaWVzIn0%3D` (the base64 string itself, then
  URL-encoded like any other query value - its own `=` padding becomes `%3D`). JSON escaping only touches a
  literal `"` or `\` in the code (`ConnectorForm#jsonString`'s own minimal rule) - a quote, space or bracket
  needs no escaping at all inside the JSON string, so `X = It's 2 [x]` produces the base64 of the literal
  `{"project":"It's 2 [x]"}`, with nothing left over outside the base64 blob to leak a raw character into the
  URL. See "Wizard resolves a project code" below for how the far side reads this parameter back.
- **New project** -
  ```
  <base>?service=ProjectWizard&serviceMode=TABLE_VIEW
  ```
  The exact same target as the Wizard link with no `serviceParams` at all - a brand new project.
- **Advanced view** (the discreet link in the active-filter bar) - the bare base, no query string:
  ```
  /ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project
  ```
  The page's own script rewrites `ui/advanced/data/` &rarr; `ui/custom/<perspectiveKey>/data/` client-side
  when the screen is opened inside a perspective, copied from the connector explorer's own script for the
  identical reason (CLAUDE.md &sect;9: never open an `/ebx-ui/...` route inside the modal iframe's
  perspective-agnostic browser when a perspective-aware form of the same route exists).

**Worked examples** (dataspace `BMdmFactory`, dataset `MdmFactory`):

| Link | Code `Companies` | Code `It's 2 [x]` |
| --- | --- | --- |
| Cockpit | `/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project?predicate=./code%3D%27Companies%27&service=FactoryCockpit&serviceMode=RECORD` | `/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project?predicate=./code%3D%27It%27%27s%202%20%5Bx%5D%27&service=FactoryCockpit&serviceMode=RECORD` |
| Wizard | `/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project?service=ProjectWizard&serviceMode=TABLE_VIEW&serviceParams=eyJwcm9qZWN0IjoiQ29tcGFuaWVzIn0%3D` | `/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project?service=ProjectWizard&serviceMode=TABLE_VIEW&serviceParams=eyJwcm9qZWN0IjoiSXQncyAyIFt4XSJ9` |
| New project | `/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project?service=ProjectWizard&serviceMode=TABLE_VIEW` (same for every code, no `serviceParams`) | |

## Contextual return: Close goes back to the explorer, not the native table

Measured live by the coordinator the same day: the two `ebx-ui` forms above open the target service correctly
inside the perspective, but that screen's Close button otherwise lands on the plain `Project` table view, not
back on the explorer card grid the user actually came from. Fixed with the same sessionStorage-token technique
ebx-connectors' `ConnectorCatalogUserService`/`ConnectorForm` (`ftccRecordOrigin`) and ebx-data-catalog's
`FormHtml` (`ftdcOrigin`) already use, gathered in two small static methods on `FactoryHtml` (simplified: no
per-record id round-trip, since any of these three links always means "return to the explorer that was open a
moment ago" - there is exactly one explorer, unlike the catalog's per-record card origin):

- `FactoryHtml.originCaptureScript(String user, String linkSelector)` (wave 13: the token lives under `mdmf:<login>:origin`, `docs/OPERATIONS.md` section 4.3) - written into the explorer page
  (`ProjectExplorerUserService#write`, selector `".mdmc-btn, .mdmx-newproject"` - the Wizard/Cockpit buttons
  and the New-project link/button). Clears any stale `origin` sessionStorage token (of this login) on load first (so a
  Close reached from elsewhere - the perspective menu, a toolbar - is never hijacked by an old click), then
  arms every matched link to record `{url: window.top.location.href}` into that token the moment it is
  clicked, just before the browser navigates away.
- `FactoryHtml.originReturnScript()` - written into both `ProjectWizardUserService` (`write` and
  `writeBlocked`, so a blocked "new project" launch still returns correctly) and `FactoryCockpitUserService`
  (`write`). Reads the token back; if present and pointing at a same-origin `/ebx-ui/...` URL, rewires the
  shell's Close button (the last `button[type=button]` of `.ebx_ActionsForward` - the same DOM contract
  `ConnectorForm`/`MappingForm` already rely on) to navigate there and clear the token, instead of the plain
  EBX close action. Left entirely untouched when no token is present, so a Wizard or Cockpit reached from the
  perspective menu or a toolbar - never from the explorer - keeps its ordinary Close.

**Not yet independently re-verified live** - written and unit-tested (compiles, `tools/test_local.sh` green),
but the coordinator's live check that day covered only that the new link forms themselves open correctly, not
yet that Close specifically returns to the explorer afterwards.

## `perspectiveName`/`service` combination - checked, not used

The task asked to check the local `manager_component_ref.html` (Web Component reference) for a documented
`perspective` parameter that keeps the user inside the `MdmFactory` perspective when combined with `service`,
and to add it if it exists. Read in full (this instance's own copy, `.../ebx-manager/www/common/html/onHelp/
advanced/references/manager_component_ref.html`, 2026-09-23): there is no plain `perspective` parameter at
all. What exists is `perspectiveName` + `perspectiveActionName`/`perspectiveActionId`, which select a named
**perspective action** (an existing menu entry), not an arbitrary `service`; the doc's own "Combined selection"
section states the combination behaviour is between `perspectiveName` and an *entity* (dataspace/dataset/xpath)
or an *action*, never between `perspectiveName` and a bare `service` parameter the way this task's phrasing
suggested. None of `CockpitPage.lineageUrl`, `RecordLineageServiceDeclaration`'s own documented deep link, or
any other `/ebx/` link already in this module uses `perspectiveName` either - consistent with there being no
documented mechanism for what was being asked. Left out; every link in `FactoryLinks` matches the existing
module convention exactly (no `perspective*` parameter).

(One unrelated, minor finding from the same read: the doc's own prose says a table-selecting predicate is "to
be encoded using %5B and %5F" for `[`/`]` - `%5F` is `_`, not `]` (`%5D`); this reads as a typo in the shipped
documentation itself. `FactoryLinks` uses the factually correct `%5D`, not the documented typo.)

## Wizard resolves a project code

`ProjectWizardServiceDeclaration.declareWebComponent` now declares a `project` input parameter (`addInputParameter`,
the same technique `RecordLineageServiceDeclaration` already uses for its own `project`/`entity`/`key`
parameters - see that class's own Javadoc for why the `/ebx/` web-component route passes a plain query
parameter through where an `ebx-ui` route would silently drop it, CLAUDE.md's own note).
`ProjectWizardUserService.setupObjectContext` reads it with `AbstractRecordLineageUserService.param` when no
`Project` record is already selected, and resolves it by **primary key** -
`projects.lookupAdaptationByPrimaryKey(projects.computePrimaryKey(new Object[] { code }))` - never a
string-built key (CLAUDE.md's own warning; moot here since `Project`'s key is the single field `code`, but the
same call shape as every other primary-key lookup in this module). Found: `projectId`/`projectLocked = true`,
same as a toolbar launch on a selected record. Not found: falls through to the existing "new project" flow
(the `blockedNewProject` gate still applies, unchanged), and `write()` shows a one-time warning naming the
unknown code (`wizard.notice.unknownProjectCode`) on the very first render.

**Resolved only once per service instance** - the same "already initialised on an earlier round trip of this
same service instance" guard `RecordLineageUserService#setupObjectContext` already uses for its own launch
parameter, since `setupObjectContext` runs again on every postback of this same dialog (the class's own
Javadoc), where there is no record selection either; re-resolving the URL parameter on every postback could
otherwise clobber a project the user has since navigated to or away from through the posted form.

## Perspective: what happens on an already-installed instance

`MdmFactory.perspective.json`'s "Projects" section gains a new first child, "Project explorer" /
"Explorateur de projets", `default: true`, `serviceKey`
`ebx-mdm-factory@/WEB-INF/ebx/schemas/MdmFactory.xsd@ProjectExplorer`, parameters `service=ProjectExplorer`,
`branch=MdmFactory`, `instance=MdmFactory`, `xpath=/root/Project`, reusing the existing `projects.svg` icon.

**2026-09-23 update, at the coordinator's own request: the plain "Projects" table item (no `serviceKey`,
xpath `/root/Project`) is now REMOVED from the packaged JSON entirely** - the earlier lot only dropped its
`default: true`; "Project explorer" now replaces it outright rather than merely outranking it.

Read `PerspectiveCompletion`/`PerspectiveInstaller#complete` in full to answer precisely what a **fresh**
install versus an **already-installed** perspective each do with this change:

- **A brand-new install** (`PerspectiveInstaller.install`, never run on this instance before) reads the
  packaged JSON's own item list directly (`createNodes`): with the plain "Projects" item gone from the JSON,
  a fresh install never creates it at all - only "Project explorer" (first, `default: true`, honoured exactly
  as packaged), "Project wizard", "Project sources" and "Cockpit" under the "Projects" section.
- **An already-installed perspective** goes through `complete`, driven by `PerspectiveCompletion.decide`.
  Matching is by *target* (a `serviceKey`, or a plain xpath for a table-shorthand item), never by label or
  position (`PerspectiveCompletion`'s own Javadoc explains why: a `menuItem` row's only real identity is EBX's
  own auto-increment id, and nothing else is stored to key on) - and **`decide`/`complete` never reorders,
  renames or removes anything from the existing perspective; they only ever add an item recognised as
  genuinely missing** (`PerspectiveCompletion`'s own Javadoc, read again for this change). Consequences:
  - Removing the plain "Projects" item from the packaged JSON has **zero live effect** on an
    already-installed perspective: nothing in `PerspectiveCompletion`/`PerspectiveInstaller#complete` ever
    deletes a live `menuItem` row. The old "Projects" item stays exactly where it was, under whatever label,
    order and default status the original install (or a prior lot) left it - completion only compares the
    packaged JSON's items against what already exists to find what is *missing*, never to find what is now
    *absent from the package* and prune it.
  - "Project explorer" still targets a `serviceKey` nothing existing points at, so it is still recognised as
    genuinely missing and still gets created - appended at the end of the existing "Projects" section's
    current children (`order = maxOrder(...) + 1000`), not inserted first as the packaged JSON declares, and
    with its own `"default": true` read and explicitly discarded during completion (only a fresh install's
    `createNodes` call ever writes `domain/properties/defaultSelection` from a packaged `default` flag) - both
    unchanged from the earlier lot's own finding.
  - **Net effect on an instance where this perspective was already installed before this lot**: nothing
    visibly changes on its own. "Project explorer" was already there (added by the earlier lot, once, the
    first time this JSON was applied) or gets added now as a new, last entry under "Projects"; the plain
    "Projects" table item, if the instance still has it, is left in place, untouched, exactly as before. The
    coordinator switches the live perspective by hand (Administration &rarr; the perspective's own dataset:
    delete the old "Projects" menu item, set `domain/properties/defaultSelection` to "Project explorer") or
    with the `ebx-perspective-admin` module's own tools ([[ebx-perspective-admin-module]]) - this lot's JSON
    edit only guarantees the intended, final shape on an instance that installs the perspective for the very
    first time.

`ProjectExplorerPerspectiveTest` checks the packaged JSON itself (first child, `default: true`, correct
`serviceKey`/parameters, and that no remaining child is the plain table-shorthand "Projects" item) - it does
not, and cannot, exercise `PerspectiveCompletion`/`PerspectiveInstaller` against a live perspective;
`PerspectiveCompletionTest` already covers that class's own matching/ordering logic in the abstract.

## Favorites (2026-09-23, branch `feature/explorer-favorites`, worktree `ebx-mdm-factory-favorites`)

the product owner asked for the project explorer to be "consistent" with ebx-connectors' own connector explorer, which
already has a per-user favorite star. This lot ports that feature as faithfully as this module's own
conventions allow - written and unit-tested, **never deployed, never seen running** (same posture as the rest
of this document).

**Table.** `Favorite` (`tools/build_xsd.py`, regenerated `MdmFactory.xsd`): `userLogin` (mandatory, 255),
`targetType` (mandatory, an enumeration with the one value `PROJECT` today - kept as an enumeration, not a
bare string, the same way ebx-connectors' own table does, so a second kind of favorite only ever means adding
a value here), `targetKey` (mandatory, 255, no foreign key - deliberately, the same reasoning ebx-connectors'
own field documentation gives: one table could serve more than one kind one day, and a deleted target only
ever leaves a dangling shortcut, never a broken reference), `createdAt` (dateTime). Primary key `userLogin` +
`targetType` + `targetKey`. `FactoryPaths.FAVORITE` / `FactoryPaths.Favorite.*` mirror ebx-connectors'
`ConnectorsPaths.FAVORITE` / `ConnectorsPaths.Favorite`.

**Rule.** `com.on.ebx.factory.ebx.Favorites` and `com.on.ebx.factory.ebx.FavoriteAccessRule` are a
line-for-line port of ebx-connectors' classes of the same name: `login`/`validType`/`validKey`/`predicate`
guard what the client is trusted with, `rows`/`keys` read a login's own favorites (never anyone else's),
`toggle` adds or removes one (checking the type, the key, that the target still exists, and the 200-per-user
limit, all server-side - never trusting the client's own state), `pruneDangling` drops a favorite whose target
was since deleted, `json`/`escapeJson` build the star's AJAX answer. `FavoriteAccessRule` makes every row
read-only to its own owner (an administrator reads every row read-only too) and hidden to everyone else - the
rows are written exclusively by `Favorites.toggle` with `setAllPrivileges(true)`, never by hand. Registered in
`FactorySchemaExtensions` (`setAccessRuleOnOccurrence(FactoryPaths.FAVORITE, new FavoriteAccessRule())`),
exactly like `ConnectorsSchemaExtensions` does for its own table. The one kind is `Favorites.PROJECT`, whose
target table is `/root/Project` and whose target key is a project's own occurrence primary key, formatted
(`getOccurrencePrimaryKey().format()` - moot here since `Project`'s key is the single field `code`, but read
the same way every other primary-key lookup in this module is, per CLAUDE.md's own warning against building a
key from a raw field).

**Endpoint.** `POST`-free, plain `UserServiceAjaxRequest` on `ProjectExplorerUserService`
(`favoriteRequest`), the same shape as ebx-connectors' `ConnectorCatalogUserService#favoriteRequest`: the URL
is resolved once per render with `UserServicePaneWriter.getURLForAjaxRequest(this.favoriteRequest)` and
rendered into the page as a hidden span, `<span id="mdmxFavEndpoint" hidden data-url="...">`. The client script
(`FavoriteStars.JS`) reads that `data-url`, appends `?key=<target key>&type=PROJECT` (`URLSearchParams`, so
both values are safely encoded), and posts through `EBX_AJAXResponseHandler` - a GET, like every other AJAX
call in this module and in ebx-connectors (CLAUDE.md's own note: the AJAX endpoint helper only does GET, so a
very long payload would not fit; a star toggle's two short values always do). The JSON answer is
`{"favorite":true|false}` on success, or `{"favorite":<unchanged state>,"error":"<localized message>"}` on a
refusal (`Favorites.ERR_LIMIT`/`ERR_UNKNOWN_TARGET`/`ERR_BAD_REQUEST`/`ERR_FAILED`, texts in
`Messages_en/fr.properties` under `favorite.error.*`).

**Facet values.** One checkbox facet, `fav`, first in the rail (`ProjectExplorerPage#facets`, before
`status`/`style`/`mode`/`owner`) - a single value, `"1"` &rarr; "My favorites" (`explorer.favorites.mine`),
copied exactly from ebx-connectors' own connector-explorer facet (`ConnectorCatalogUserService#writeCatalog`,
its `mine` map): always offered, never filtered by "does any card already have one" the way every other facet
on this screen is, since the option is actionable even before anyone has pinned a project yet. Every card
carries `data-fav="1"`/`"0"` (`ProjectExplorerPage#card`), read by `FacetedPage`'s own generic filtering script
exactly like `data-status`/`data-style`/etc.

**The star's placement is the one real adaptation, not a straight copy.** ebx-connectors' connector card is a
single whole-card `<a>`, so its star has to be an absolutely positioned overlay button sitting outside that
link (`position:absolute;right:10px;bottom:10px` in `connector-catalog.css`). A project card
(`ProjectExplorerPage#card`) has no whole-card link at all - only the two `.mdmc-btn` buttons (Wizard, Cockpit)
navigate - so the star (`FavoriteStars.button`, class `mdmx-fav`) is rendered as a third, plain flex child of
`.mdmc-meta`, right after the status chip and the project code, laid out inline (`factory/explorer.css`:
`.mdmc-meta` drops `justify-content:space-between` for a plain flex row where `code` shrinks with
`min-width:0` - which also happens to make that field's own text-overflow ellipsis actually take effect, a
latent issue in the pre-favorites layout - and the star sits at a fixed 22&times;22px). Being a `<button>` of
its own class, it never navigates and never arms `FactoryHtml#originCaptureScript`'s selector (`.mdmc-btn,
.mdmx-newproject`): checked by reading that selector again while writing this lot, still true, `mdmx-fav`
matches neither.

**Client script.** `FavoriteStars.JS` is ebx-connectors' own script, renamed (`ftcx-fav` &rarr; `mdmx-fav`,
`.ftcc-card` &rarr; `.mdmc-card`, `#ftcxFavEndpoint` &rarr; `#mdmxFavEndpoint`, `root.ftFacets` &rarr;
`root.mdmxFacets`). `ProjectExplorerUserService#write` appends it after `ProjectExplorerPage.script(model)`
runs (which sets up `root.mdmxFacets` synchronously, at the end of `FacetedPage#script`'s own IIFE), in its own
`addJS_cr` call that resolves `root`/`F` in scope, the same ordering `ConnectorCatalogUserService` relies on
for its own `root.ftFacets`.

**On an instance whose dataset predates the `Favorite` table.** `Favorites.rows` tolerates it explicitly
(`pDataset.getTable(FactoryPaths.FAVORITE)` inside a `try`/`catch (RuntimeException)`, returning an empty
list): the explorer still opens, simply with no star showing "on" and the favorites facet always empty, rather
than failing the whole screen. Read `FactoryInstaller.install` in full to answer what happens to the dataset
itself once the module's jar carries the new table: the factory dataset is created **once**, rooted at
`SchemaLocation.forPathInModule(FactoryPaths.MODEL_PATH, pModuleName)` (`pContext.doCreateRoot(...)`) - the
module's own XSD path, not a frozen DMA-publication snapshot; every later startup only calls
`findAdaptationOrNull` and reseeds rows (`seedSettings`, `stampInstanceId`, `seedReferenceBindings`), it never
recreates or alters the dataset's schema. Because the dataset's root schema location is the module's XSD path
itself, a Tomcat restart that loads the new module jar (carrying the regenerated `MdmFactory.xsd` with the
`Favorite` table) makes the *existing* dataset resolve that same, now-larger schema on its own - no dataset
operation, no republish, nothing `FactoryInstaller` itself has to do differently. **This is a different
mechanism from the DMA-publication "a dataset follows a republication of the same model" behavior CLAUDE.md
§6 documents for the zones this module generates** (`Companies_Master` etc., which live on a DMA publication
snapshot) - the factory's own `MdmFactory` dataset is a plain module-schema dataset, and the corresponding EBX
behavior (existing dataset picks up an XSD change on restart, no explicit "refresh" step) is standard for that
kind of dataset. **Verified by reading `FactoryInstaller.install`/`doCreateRoot`, not measured live** - the
instance was not touched for this lot.

**Tests.** `FavoritesTest` (new, `ebx-mdm-factory/src/test/java/com/on/ebx/factory/ebx/`): a line-for-line port
of ebx-connectors' `FavoritesTest`, covering `validType`/`validKey`/`withinLimit`/`predicate`/`json` with the
one kind `PROJECT`. `ProjectExplorerPageTest` gains three cases: the star's on/off markup and `data-fav`
escaping a key that carries a quote (`favoriteStarRendersOnAndOffAndEscapesTheKey`), the favorites facet being
first and always offered (`favoritesFacetIsFirstAndAlwaysOffered`), and the hidden endpoint span rendering the
(escaped) URL the caller resolved (`favoriteEndpointIsRenderedAsAHiddenSpan`). `ProjectExplorerPerspectiveTest`
is untouched and still green - the perspective JSON has nothing to do with favorites.

## What was not verified

**2026-09-23 update**: the coordinator has since measured live that the Wizard and Cockpit links (in their
rewritten `ebx-ui` form) do open the right service, inside the perspective, with its own Close button and
menu - see the note at the top of this file. Still not independently confirmed live: that the perspective
actually renders "Project explorer" first (on a fresh install) or last (on a completed one) as reasoned above;
that the facet rail/cards/script behave in a real browser the way `FacetedPage`'s own connector-explorer
incarnation is known to; that `computePrimaryKey`/`lookupAdaptationByPrimaryKey` resolve a real `Project`
record the way every other primary-key lookup in this module already does; that the disabled "New project"
button's `TargetRule.refusal()` reads sensibly as a button title in a browser; and, specifically, that the new
contextual-return mechanism (Close going back to the explorer rather than the native table, see "Contextual
return" above) actually fires in a real browser. All of this was reasoned from CLAUDE.md's own documented
rules, this module's own established conventions, sibling modules' own measured live behaviour, and the
manager-component reference doc - the same posture `docs/PROJECT-TOOLBARS.md` documents for its own, unrelated
lot, now narrowed by the one live check above.

**2026-09-23, favorites lot**: nothing in the "Favorites" section above was measured live either - the AJAX
round trip (click a star, get `{"favorite":true}` back, see it flip), the inline layout of the star inside
`.mdmc-meta` in a real browser, the favorites facet actually filtering the grid, and the dataset-schema
reasoning about a restart picking up the new `Favorite` table are all reasoned from CLAUDE.md's own documented
rules and this module's own established conventions, exactly like every other unverified claim on this page.
