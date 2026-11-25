# ON EBX MDM Factory

> This repository is published automatically from ON EBX's development repository at every change of its main
> branch. A change made here is overwritten by the next publication: report issues and propose changes to ON EBX.

Custom module for ON EBX (`ebx-mdm-factory`, package `com.on.ebx.factory`). From the source
applications of a project it generates the three zones of an MDM build — **landing**, **staging**,
**master** with matching and merge — and keeps the cross-references that let every master record show
how it was built. See [PLAN.md](PLAN.md) for the design journal and the lots (it lags the code).

It ships with no EBX distribution and is bespoke code written for one owner, not an editor product. It needs two sibling modules, read as data (no
classpath dependency): **ebx-connectors** (the flows, the `llm` connector, the MCP bridge: required) and **ebx-data-catalog** (the scanned structures and
profiles of the sources: required in fact, the landing plan is refused without it with `engine.missing.catalogModule`). Match and Merge
(`ebx-addon-mame`) is needed for the matching steps only. The full list, with what degrades without each, is in
[docs/OPERATIONS.md](docs/OPERATIONS.md) section 1.

Operators: [docs/OPERATIONS.md](docs/OPERATIONS.md) (install, configuration reference, security settings, background work, upgrade, diagnosis, known limits),
[docs/UPGRADE.md](docs/UPGRADE.md) (upgrade and rollback; section 12 for 1.3.0 to 1.4.0), [docs/RELEASE-NOTES.md](docs/RELEASE-NOTES.md).

## Status (version 1.4.3, 2026-11-12)

The module runs the whole chain for real on the development instance (ON EBX 6.3.0.7-GP): sources, landing, staging, master with Match and Merge, lineage per
record, distribution, delivery through EBX Staging, the assistant (wizard), the explorer and the cockpit. **Version 1.4.1 was installed on 2026-11-12 on a clean instance**
(a new installation, and a start from 1.4.0 with Match and Merge and DAMA) **and on the FastTrack development instance**, where its integration suite (`tools/itest.sh`: 77 scenarios
passed, 0 failed, with an administrator account and an account without rights) and its smoke test (`tools/smoke.sh`) passed. Not done: a whole project run on the clean instance
(landing passed once `-Debx.factory.zonePermissionsFrom=Reference` was set; since 1.4.3 that is the default, docs/OPERATIONS.md section 2.6), the upgrade from 1.3.x on a copy, and any rollback (docs/UPGRADE.md section 12).

**Version 1.4.3** (a new zone copies its parent's permissions by default) is the current tag and the first version with a GitHub release (package and checksum, published 2026-11-20).
Its war as packaged has not been started on a server: a build made two commits before the tag (the wording of one message apart) passed the landing on the clean instance with no setting (12 stages of 12; the scenario then stops at the proposal step, that instance having no language-model connection),
and the development instance still runs 1.4.2.

Two functions delivered after 1.3.0 are in the code but **closed by default**: the governed agents ([docs/AGENTS.md](docs/AGENTS.md), `-Debx.factory.agents.enabled=true`)
and the source-drift proposals ([docs/DRIFT.md](docs/DRIFT.md), `-Debx.factory.sourceDrift.enabled=true`). What is missing before a customer opens them is in the
release notes. [PLAN.md](PLAN.md) is the design journal: it lags the code (its state table is dated 2026-09-20), read it for the reasoning only.

## Layout

```
ebx-mdm-factory/                    the EBX module (Eclipse project, javac build, no Maven)
  build.sh                          the only way to produce the classes (javac --release 8)
  src/main/java/com/on/ebx/factory/
    core/       paths, rights, pure rules (FactoryPaths, FactoryRights)
    ebx/        registration, installer, schema extensions, engines (landing, staging, master, matching, agents)
    landing/ staging/ master/ matching/ distribution/ agent/ ...   pure logic, unit-tested without EBX
    wizard/ ebx/ui/                 the assistant and the other screens
    rest/       /ebx-mdm-factory/rest/v1/... (FactoryResource)
    workflow/ scheduler/            script/user task beans declared in module.xml, the scheduled tasks
  src/main/webapp/WEB-INF/lib/      on-ebx-agent.jar and on-ebx-mapping.jar, built by ebx-connectors, pinned by tools/embedded-jars.sha256, never committed
  src/main/webapp/WEB-INF/ebx/schemas/MdmFactory.xsd   generated, never edited by hand
  src/main/webapp/WEB-INF/ebx/schemas/MdmFactory_toolbars.xml   Project's toolbars (docs/PROJECT-TOOLBARS.md)
docs/           one guide per feature, OPERATIONS.md, UPGRADE.md, RELEASE-NOTES.md
tools/
  build_xsd.py      the data model, as Python; regenerates MdmFactory.xsd; also checks MdmFactory_toolbars.xml's toolbar names against it
  test_local.sh     XSD up to date + compile + tests, in a temporary folder (safe while Tomcat runs)
  package.sh        the release zip (war, guides, workflow models, SHA256SUMS); runs test_local.sh first
  install.sh        copies the module into an Eclipse/FastTrack workspace (Tomcat stopped)
  deliver.sh        delivers a project through EBX Staging: domain from the Factory's route, export, dry run, import (docs/DELIVERY.md)
  workflow/         the workflow models an operator imports by hand
```

## Build and test

```bash
EBX_LIB_DIR=<ebx-ps-fasttrack-server/lib> tools/test_local.sh
```

`EBX_LIB_DIR` points to the `lib` folder of an ON EBX 6.3 server (`ebx-lib.jar`, or `ebx.jar` in a standard distribution, and its neighbours; the EBX jars are never committed). `tools/package.sh` builds the release zip: its war also embeds Jackson 2.18.12, pinned, because a standard server has none (docs/OPERATIONS.md section 2.6, which lists what was and was not rehearsed); `tools/install.sh` installs the same jars, and the package refuses an OSV advisory that `tools/deps-known.txt` does not accept (section 2.7; `DEPS_OFFLINE_WAIVER` for a build with no network). The two embedded jars
come from the Connectors build the pin names (`ON_EBX_AGENT_JAR`, `ON_EBX_MAPPING_JAR`, docs/OPERATIONS.md section 2.6). The suite takes about seven minutes.

## Install

```bash
EBX_WORKSPACE=<.../ebx-ps-fasttrack> tools/install.sh
```

`EBX_WORKSPACE` is required (the script exits 3 without it, before touching anything). It copies the module next to the other modules of the workspace,
compiles it into `target/classes` with `build.sh` and adds the Tomcat context file. Tomcat must be stopped; never touch the context of a running or booting
instance (docs/OPERATIONS.md section 7.2). Then, in Eclipse: *File -> Import -> Existing Projects* (first time) or F5, and start Tomcat. At startup the module
creates the `MdmFactory` dataspace and dataset, the DEFAULT settings row and the activation key of the agents; `GET /ebx-mdm-factory/rest/v1/status` tells whether it did.
For any other server, docs/OPERATIONS.md section 2.6.

### OpenAI reasoning models and `reasoning_effort` (2026-09-26)

A language model connector on an OpenAI reasoning model such as `gpt-6-luna` refuses every agent turn
that offers tools ("Function tools with reasoning_effort are not supported ... set reasoning_effort to
'none'", agent run 141). The Factory now passes the connector's `openai.reasoningEffort` property
(empty = not sent) to `on-ebx-agent.jar`'s `LlmClient`, and that client, on this exact refusal, resends
once with `reasoning_effort: none` and keeps it for the rest of the run. Nothing changes for `gpt-4o`.
Both need an `on-ebx-agent.jar` built from ebx-connectors with the 9-argument `LlmClient` constructor
(branch `fix/openai-reasoning-effort-tools`); with an older jar the module fails to link at the first
agent run (`NoSuchMethodError`).
