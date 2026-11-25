# Source drift: schema changes proposed, never applied alone (lot B, 2026-10-30)

> **Closed by default (production review of 2026-11-12, F3-105).** Source drift stays in the code but is off on an instance that sets nothing: a landing plan
> then behaves exactly as in v1.3.0 - a `GENERATED` or `LOADED` table whose columns were added or removed turns `DRIFTED` with the v1.3.0 warning ("The source changed
> since the landing was generated. New columns: ...; columns gone: ...."), a retyped column alone marks nothing, and NO `SourceDrift` row is written. The warning stays;
> deciding and applying are closed: `decide` / `apply` answer the coded refusal `engine.missing.sourceDriftClosed`. A `DRIFTED` table stays so until its landing is prepared
> again or the Landing step is cleaned up ("Clean this step and the following ones"), as in v1.3.0; existing `SourceDrift` rows stay on file with no effect. A server
> administrator opens the function with `-Debx.factory.sourceDrift.enabled=true` and a Tomcat restart (`docs/OPERATIONS.md` section 3.1).
>
> **The model operations are recomputed, never read from the row (F3-01).** At `apply`, the server rebuilds the change from the landing's frozen columns and the
> catalog's current scan (`SchemaDrift.revalidate`) and sends only the `create_field` it computed itself. The `SourceDrift` row only designates the change (landing
> table, column, field, kind) and those values are revalidated: known kind, plain names, column present in the scanned source, change still current. A change that
> moved on since it was recorded is refused with `engine.conflict.driftStale` ("plan the landing again"). The stored `operations` text is for display only.

A source's schema moves: a column appears, one disappears, one changes type. Until this lot the landing plan only noticed
added and removed columns, set the landing table to `DRIFTED` with a sentence, and left everything else to a person.
**When source drift is open** (see the box above), every change is a **`SourceDrift` proposal** with what it implies for the landing, the staging zone and the mapping, and
an accepted proposal is applied by the Factory, step by step. **Nothing is ever applied without a human decision.**

## Detection

Where: the landing plan (`POST /sources/{id}/landing/plan`, the wizard's rescan, the automatic intake's plan), in
`LandingPlanner.drift`, for every landing table already `GENERATED`, `LOADED` or `DRIFTED` **when source drift is open**. Closed (the default), only the
v1.3.0 comparison runs: added or removed columns of a `GENERATED` or `LOADED` table turn it `DRIFTED` with the v1.3.0 warning, a retype alone marks nothing,
and no `SourceDrift` row is written.

What is compared: the table's `LandingColumn` rows (the frozen copy of the scan the landing was generated from: there is
no scan id in the catalog, so this copy is the previous scan) against the columns the catalog holds now
(`CatalogReader.Asset.columns`). The pure diff is `landing/SchemaDrift.java`:

| Change | When |
|---|---|
| `ADDED` | a scanned column has no `LandingColumn` |
| `REMOVED` | a `LandingColumn` is no longer scanned |
| `RETYPED` | both exist and the type differs (native type if the catalog has one, else the normalised type, compared without case or outer blanks; a column with no recorded type is never reported retyped) |

A renamed column is a `REMOVED` and an `ADDED`, decided one by one. Each change becomes one `SourceDrift` row `PROPOSED`,
unless the same change (kind, column, new type) is already on file `PROPOSED`, `ACCEPTED`, `REJECTED` or `FAILED`: a rescan never
duplicates and never brings back what a person rejected. While a change of the table is open (`PROPOSED`, `ACCEPTED`,
`FAILED`) the landing table is `DRIFTED`, with a one-line summary.

## The proposal (`SourceDrift`)

Fields: project, landing table, kind, source column, landing field, old type, new type, normalised new type, part of the
source key?, the three implied changes (text), the model operations (`operations_json`), the table's status before the
drift, status (`PROPOSED`, `ACCEPTED`, `REJECTED`, `APPLIED`, `FAILED`), detected, decided by / at, applied by / at, apply
message. The table is read by every Factory role from VIEW and written by STEWARD (so a steward can still edit a row's `status` by hand, never what is
executed: see the box above); the list route `GET /projects/{code}/drift` needs STEWARD. Backed up with its landing table, deleted with the project
(`docs/OPERATIONS.md` section 9, F3-10).

What each kind implies:

| Kind | Landing model | Staging | Mapping (landing flow) |
|---|---|---|---|
| ADDED | a new text field (every landing field is text), the model republished | nothing reads it: map it in the mapping matrix if needed | regenerated to read the new column |
| REMOVED | the field is **kept**: the rows already loaded keep their values (removing a field from a published model with data is a migration, not a drift) | the attributes mapped from it lose their source: those mappings are removed and must be remapped | regenerated without the column; if it was part of the source key, choose the key again |
| RETYPED | none: the landing stores text whatever the source type | the attributes mapped from it are listed for a check of their type and transformation | unchanged; the recorded source type and the inferred type are updated |

## Deciding and applying

1. **Decide** (`POST /drift/{id}/decide?accept=true|false`, Factory right STEWARD; the check on the project is a read check, a steward cannot write the `Project` record - F3-11): `PROPOSED` becomes `ACCEPTED` or
   `REJECTED`. A rejection changes nothing in the Factory, and the same change (kind, column, new type) is never proposed
   again by a rescan; a later, different change of that column is.
2. **Apply** (`POST /drift/{id}/apply`, Factory right DESIGN, write access to the project in EBX): only an `ACCEPTED` (or a `FAILED`, to retry) row. The landing
   model is patched through the `data-model` MCP server as the caller: a token minted for the caller's session, with the caller's EBX permissions, **unless
   that token cannot be minted, in which case the call falls back to the agent account** when `agentFallback` allows it (`docs/OPERATIONS.md` section 4;
   set it to `FORBIDDEN` to refuse instead). The Factory's own rows are written with the caller's session and, for the landing table's status, under the
   engine's privileges:
   - `ADDED`: `apply_data_model_patch` on the source's landing model with the `create_field` operation recomputed by the server (see above; the stored
     `operations` text is not sent) (`gate=false`), checked (`failed`, `compiled`, `valid`), then `publish_data_model` (its answer is the gate,
     `PublishDecision`); then the `LandingColumn` row is created (last position, the new type, the inferred staging type).
   - `REMOVED`: the `AttributeMapping` rows that read the column are deleted (listed in the apply message), then its
     `LandingColumn` row.
   - `RETYPED`: the `LandingColumn`'s `sourceDataType` and `inferredType` are updated.
   - `ADDED` and `REMOVED` then regenerate the table's landing flow (`LandingFlows.generateOne`, the Connectors mapping).
     A flow that cannot be regenerated does not fail the apply: the message says to regenerate it from the landing step.
   - Once no change of the table is open (`PROPOSED`, `ACCEPTED`, `FAILED`), the table goes back to the status it had
     before the drift (`LOADED`, else `GENERATED`).
3. A failure leaves the row `FAILED` with the reason. An `ADDED` row whose model patch succeeded but whose later step failed
   is retried by `apply`: the stored message says the field was added and the model republished, so the retry skips the patch and
   replays the landing column and the flow (F3-113). The next plan does not propose that change a second time (a `FAILED` row is a known signature).

Lists: `GET /projects/{code}/drift[?status=]` (STEWARD). The decide and apply routes check the right on the drift's own project.

## Not done in this first cut

- No screen: the rows are read in the MDM Factory dataset or through the REST route; no wizard or Cockpit tile. The end-of-plan message still announces a review
  "in the next step" that does not exist (F3-105): that is why drift ships closed. Applying runs the model republication in the HTTP request and does not reserve
  the project's landing (F3-106, open).
- Staging attributes are never changed: a new column is not proposed as a new attribute, a retyped column does not
  retype its attribute (the staging proposal and the mapping matrix stay the place for that).
- A removed column is not removed from the landing model.
- A change of nullability or length is not reported.

## Needs a live test on the RC

Done on the RC (B1..B10): on a file source's landing table, a renamed column (ADDED + REMOVED) and a changed
type proposed; RETYPED and ADDED applied (the landing model patched and republished, a new `LandingColumn`), REMOVED rejected; the table back
to its status; a second plan proposes nothing new; a second decision refused.

**That run predates the correction wave of 2026-11-12** (drift closed by default, operations recomputed by the server, the decide/apply rights, the refusal codes
`engine.missing.sourceDriftClosed`, `invalid.driftKind`, `invalid.driftField`, `invalid.driftColumnNotInSource`, `missing.driftColumn`, `missing.driftAsset`, `missing.driftProject`,
`conflict.driftStale`). `tools/e2e/agents_drift_rc.py` has not been re-run against it (it needs `-Debx.factory.sourceDrift.enabled=true` on the server); the
offline suite covers the pure comparison and revalidation and the closed behaviour, and is green.

Still to do:

- A SQL source: add a column, rescan, accept, apply: the field exists in the landing model, the publication is new, the
  Connectors mapping reads the column, the next load fills it.
- Drop a mapped column: the mappings are removed, the flow no longer reads it, the next load succeeds.
- A user with STEWARD but not DESIGN can decide but not apply (the route was refused with 403 before F3-11; fixed, not yet measured on the RC).
- Open: `SourceDrift` stays writable by STEWARD in `FactoryRightAccessRule` (rights lot): a steward can edit `status`, never what is executed.
