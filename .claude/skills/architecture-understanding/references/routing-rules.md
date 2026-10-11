# Routing rules (deterministic)

Apply the sections in order. Record, for every unit, the rule number and the concrete signal
(file + match) that decided its route — that string goes into the Gate-2 plan and the overview.

## 1. Exclusions (applied before anything else)

Default-excluded directory names, anywhere in the tree:

`node_modules`, `target`, `dist`, `build`, `out`, `.gradle`, `.idea`, `.vscode`, `.git`,
`coverage`, `.next`, `.nuxt`, `__pycache__`, `.venv`, `bin` (when it only holds compiled output),
`generated`, `generated-sources`.

Default-excluded paths in this repo: `.claude/` (skills, worktrees), `drivers/` (vendored),
`docs/architecture/` (our own output), `frontend/node_modules`.

User `--exclude` globs add to this list. An excluded folder is never opened; record it once at its
top-most excluded path as `EXCLUDED (<reason: default | user | vendored | outside root>)`.

## 2. Unit detection

Walk depth-first from the confirmed root. A folder is a **unit** when it holds one of these
manifests *and* is not an aggregator:

| Manifest | Aggregator (descend instead) when |
|---|---|
| `pom.xml` | `<packaging>pom</packaging>` and has `<modules>` |
| `build.gradle(.kts)` | it is the root next to `settings.gradle(.kts)` that `include`s sub-projects — then each included project is a unit; a single-project Android root (`mobile/android` with one `:app`) is one unit at the root |
| `package.json` | has `"workspaces"` or no `src/` / entry of its own |
| `go.mod`, `pyproject.toml`, `Cargo.toml`, `*.csproj` | never |
| `Dockerfile` (no other manifest) | never |

Folders with **no manifest** that are direct children of the confirmed root (e.g. `config/`,
`ontology/`, `scripts/`, `docs/`) are **asset units** — one unit per folder, do not split further.
Root-level loose files that matter (`docker-compose*.yml`, `Makefile`, `start*/stop*`, root
`pom.xml`) form one asset unit named `<root>/(root files)`.

Stop descending once a unit is found; its sub-folders belong to it.

## 3. Route classification — first matching rule wins

| # | Route | Signal (any one is enough) |
|---|---|---|
| R1 | `ui` | `package.json` depends (deps/devDeps/peerDeps) on `react`, `react-dom`, `vue`, `@angular/core`, `svelte`, `solid-js`, `preact`, `next`, `nuxt`, `@module-federation/*` |
| R2 | `ui` | `vite.config.*` / `webpack.config.*` / `angular.json` **and** an `index.html` or `src/main.(t\|j)sx?` |
| R3 | `ui` | Android: `AndroidManifest.xml` with `<activity` or `res/layout/`; iOS: `*.xcodeproj` / `Info.plist` with `UIApplication`; Flutter `pubspec.yaml` with `flutter:` |
| R4 | `service` | Java/Kotlin source containing `@SpringBootApplication`, `@QuarkusMain`, `Micronaut.run`, or a `main` that starts a server (`HttpServer`, `Undertow`, `Netty`) |
| R5 | `service` | `pom.xml`/`build.gradle` depends on `spring-boot-starter-web`, `-webflux`, `spring-kafka` with a `@KafkaListener` in source, or `spring-ai-mcp-server*` |
| R6 | `service` | Node `package.json` depends on `express`, `fastify`, `@nestjs/core`, `koa`, `hono` and no R1 dependency |
| R7 | `service` | `Dockerfile` with `EXPOSE` or an `ENTRYPOINT` running an app, and the unit has source code |
| R8 | `other` | build manifest present but none of R1–R7 (libraries, SDKs, specs, CLI tools, setup wizards) |
| R9 | `other` | asset unit with at least one of: `*.yml/*.yaml/*.json/*.properties/*.conf/*.toml`, `*.owl/*.ttl/*.rdf`, `*.sh/*.bat/*.ps1/Makefile`, `*.sql`, `*.rego`, `*.md` |
| R10 | `ignored` | nothing above matched (empty, binaries/media only, lock files only) — record why |

**Ambiguity.** If a unit matches a `ui` rule *and* a `service` rule (e.g. a Next.js app with API
routes, a server that also bundles an SPA), mark it `ambiguous`, list both signals, and let the user
decide at Gate 2. Never pick silently.

**Notes for this repo** (consistent with the rules, listed so results are predictable):
BFFs (`web-bff-service`, `mobile-bff-service`) → `service` (R4). `agents/*` and `mcp/*` → `service`
(R4/R5) with layer `agent` / `mcp`. `frontend/packages/eip-shell-context` → `ui` (R1, peer dep on
react). `libs/*` → `other` (R8). `tools/setup-wizard` → check R4 first; if it has no server main it
is `other`. `mobile/android` → `ui` (R3).

## 4. Layer tagging

1. In this repo, use [layer-map.md](layer-map.md) first.
2. Otherwise (or for units the map doesn't list), first match on the unit's folder name and its
   manifest's artifact/package name:

| Layer tag | Keywords |
|---|---|
| `agent` | agent, orchestrator, planner, crew, workflow-engine |
| `mcp` | mcp |
| `intelligence` | ai, llm, nl-query, reasoning, rag, embedding, inference, copilot, ml, model |
| `data-ingestion` | ingest, ingestion, connector, etl, pipeline, ocr, voice, whisper, docling, crawler, importer |
| `knowledge-graph` | graph, okg, arcadedb, fuseki, qdrant, vector, ontology, sparql |
| `experience` | shell, web, frontend, remote, widget, portal, bff (web), notification |
| `mobile` | mobile, android, ios |
| `control-plane` | platform, registry, admin, tenant, config, setup, dbregistry, secrets |
| `security` | security, auth, keycloak, oidc, opa, policy, infisical, traefik |
| `domain` | anything else with business vocabulary (priority, decision, briefing, mail-insight…) |
| `infrastructure` | compose, docker, k8s, helm, prometheus, perses, fluent, jaeger, scripts, distribution, Makefile |
| `shared` | common, util, core (with no stronger keyword) |

3. If two tags match, prefer the more specific (earlier rows win). Record the keyword that matched.
   The sub-skill may *propose* a different layer after reading code; the orchestrator records that,
   it does not re-route on its own.

## 5. Buckets and output files

One bucket per `(route, layer)`; one output file per bucket, owned by exactly one sub-agent:

| Route | Output path |
|---|---|
| `ui` | `docs/architecture/ui/<layer>.md` |
| `service` | `docs/architecture/services/<layer>.md` |
| `other` | `docs/architecture/other/<layer>.md` |

A bucket with more than 12 units is split into `<layer>-1.md`, `<layer>-2.md`… (alphabetical by
unit path) so a single sub-agent's context stays manageable.

## 6. Statuses used in the overview

| Status | Meaning |
|---|---|
| `ROUTED` | dispatched, sub-agent returned `documented` for it, output file verified |
| `NOT ROUTED (<reason>)` | approved but not documented: sub-skill missing, sub-agent skipped it, user removed it at Gate 2 |
| `FAILED (<reason>)` | dispatched twice, no verified output |
| `DEFERRED` | classified but outside `--only` |
| `PLANNED` | dry run |
| `IGNORED (<reason>)` | R10 |
| `EXCLUDED (<reason>)` | §1 |
