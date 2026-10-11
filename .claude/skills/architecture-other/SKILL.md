---
name: architecture-other
description: Document other units — libraries, SDKs, specs, infra/compose, config, ontologies, scripts, tooling. Reads routed units, produces layer.md file(s) with purpose, exports, dependencies, and data/config specifications. Called by the architecture-understanding orchestrator; do not invoke directly.
---

# Sub-skill: architecture-other

You are the handler for other route units (libraries, config, infra, ontologies, scripts, etc.).
You receive:
- `repo_root`: the git repo root (all unit paths are relative to this)
- `confirmed_root`: the user-approved folder (never read outside it; may be a subfolder of the repo)
- `layer`: the layer tag assigned by the orchestrator (e.g. `shared`, `infrastructure`, `knowledge-graph`, `security`)
- `units`: list of repo-relative unit paths, one per line
- `output_file`: repo-relative path where you must write the result (e.g. `docs/architecture/other/infrastructure.md`)
- `commit`, `run_timestamp`: for the metadata header
- `exclusions`: folders the orchestrator marked as excluded (for your reference; do not read them)

## Process

1. **Read shared references** first:
   - [guardrails.md](../architecture-understanding/references/guardrails.md) — §A, §B
   - [doc-template.md](../architecture-understanding/references/doc-template.md) — sections 1–10
   - [layer-map.md](../architecture-understanding/references/layer-map.md) — context

2. **Detect the unit type** from its contents:
   - **Library (Java/Node)**: `pom.xml` with `<packaging>jar</packaging>` (no main), `package.json` without `@module-federation/*` and no `bin` entry
   - **Spec library**: `pom.xml`/`package.json` with `spec` or `contract` in the name, or marked explicitly
   - **Config**: `*.yml/*.yaml/*.json/*.properties/*.conf/*.toml` files, `Dockerfile` (without source), compose files
   - **Ontology**: `*.owl/*.ttl/*.rdf`, `*.jsonld`, schema files
   - **Script/tool**: `*.sh/*.bat/*.ps1`, `Makefile`, CLI entry point
   - **Infrastructure**: compose files, network/storage definitions, observability configs (Prometheus, Fluent Bit, etc.)
   - **Shared**: the unit name contains "common", "shared", "core", "util"; or it has no clear type → classify by content

3. **For each unit, extract:**

   **Libraries:**
   - Public APIs (grep `export`, `public class`, `@FunctionalInterface`, `public @interface`)
   - Maven module ID (groupId:artifactId from `pom.xml`), npm package name
   - What does it provide? (Cite the main class/interface files)
   - Who uses it? (List dependent modules you find in the repo's pom.xml/package.json)
   - Dependencies: transitive? Critical ones listed.

   **Specs:**
   - Interfaces/contracts defined (Java: `interface`, `@FunctionalInterface`, `@RestController` stubs; Node: `export type`, `.d.ts`)
   - Configuration schema (e.g. `@ConfigurationProperties` class + binding, `@Schema` on model)
   - Protocol/API contract (REST paths, Kafka topics, MCP tool signatures)
   - Example/test payloads (keep short: cite the file, do not quote full JSON)

   **Config files:**
   - High-level sections (YAML root keys, JSON top-level properties, properties file prefixes)
   - What they configure (database? LLM? Security? Compose services?)
   - Env-var overrides (grep `${...}`, `$`, substitution patterns)
   - Secrets handling (which values come from Infisical? Hardcoded? Missing handling?)
   - Multi-tenancy config (if present; e.g. tenant list, subdomain → tenant mapping)

   **Ontologies:**
   - What domain does it model (grep class definitions, entity types)?
   - Key classes/properties (one table: class name, purpose, type/cardinality, domain)
   - Relationships between ontologies (imports, extensions)
   - Schema bootstrap: is there a `SchemaBootstrap` process that loads it? Where?

   **Compose files:**
   - Services defined (grep `services:` → service names)
   - Networks (grep `networks:` → network names, attachments)
   - Volumes (grep `volumes:` → name, driver, mount points)
   - Env-file references (`.env`, `config/*.env`)
   - Startup order/dependencies (grep `depends_on`, `healthcheck`)
   - Port mappings (service : exposed port)

   **Infrastructure (observability, infra tools):**
   - What it observes (metrics? logs? traces? events?)
   - Data sources (Prometheus scrape configs, Fluent Bit inputs)
   - Sinks (Perses dashboards, VictoriaLogs storage, Jaeger backends)
   - Retention/storage (grep TTL configs, size limits)
   - Alerting rules (if present; grep alert definitions)

   **Scripts/tools:**
   - Entry point and purpose (one-line)
   - Parameters/env vars it accepts (grep `$1`, `$@`, `process.argv`, `sys.argv`)
   - What does it do (builds? deploys? migrates? cleans?)
   - Does it mutate state (read-only? builds/compiles? deletes files?)

4. **Write to `output_file`** using [doc-template.md](../architecture-understanding/references/doc-template.md)
   sections, adapted for non-service units:

   - **Section 1 (Purpose)**: What is this library/config/tool for? One or two sentences.
   - **Section 2 (Components)**: Omit if a single unit is one component; otherwise table.
   - **Section 3 (Diagram)**: Usually omit; include only if the unit integrates multiple pieces.
   - **Section 4 (APIs/Interfaces)**: For libraries/specs, the public surface.
   - **Section 5 (Dependencies)**: What this unit depends on (libraries, configs, external services).
   - **Section 6 (Key flows)**: Usually omit; include if the unit has important processes.
   - **Section 7 (Data & state)**: For infra/config, what data is stored/processed.
   - **Section 8 (Configuration)**: Env vars and config keys this unit uses or defines.
   - **Section 9 (Guardrails)**: Rules this layer enforces (esp. security, schema validation).
   - **Section 10 (Gaps & drift)**: TODO items, hardcoded values, missing docs.

   **Metadata header:**
   ```markdown
   # <Layer name> (Library / Config / Infrastructure / Ontology / Tooling)

   > Generated by the architecture-other skill from commit `<commit>` on <date>.
   > Evidence paths are repo-relative. Tags: [inferred] [not implemented] [unverified].
   ```

5. **Verify before finishing:**
   - Every cited file exists (open it)
   - Every public API / interface name matches the code
   - Every env var or config key appears in the files
   - No hardcoded secrets copied (env var names are fine)
   - All sections have content or a clear omit reason

6. **Special handling for known units in this repo:**

   | Unit | Type | Key things to look for |
   |---|---|---|
   | `libs/eip-agent-spec` | Spec | MCP tool signatures, agent orchestration contracts, trigger types |
   | `libs/eip-agent-runtime` | Library | Agent execution engine, MCP client initialization |
   | `libs/eip-mcp-spec` | Spec | MCP tool & resource schemas, message contracts |
   | `libs/eip-core-ai-client` | Library | LLM provider abstraction (OpenAI, Azure, local), model config, RAG integration |
   | `libs/eip-core-arcadedb` | Library | ArcadeDB connection pool, query helpers, schema bootstrap triggers |
   | `libs/eip-core-security` | Library | Resource-server config, OPA client, service-token validation, tenant context |
   | `libs/eip-secrets` | Library | Infisical client, secret loading, startup validation |
   | `libs/eip-common` | Shared | Utility classes, common exceptions, DTOs; list major ones |
   | `config/` | Config | Compose overlays (ai.yml, app.yml, data.yml, infra.yml, remote-ai.yml) |
   | `ontology/` | Ontology | Domain vocabularies (OWL files), schema definitions |
   | `docker-compose.*.yml` | Infra | Compose definitions, networks, services, volumes |
   | `tools/distribution` | Tool | Distribution packaging, platform-specific installers |
   | `tools/setup-wizard` | Tool/Service | Check if it has a main class; if not, it is a library |
   | `scripts/` | Script | Startup/stop/helper scripts, build automation |

## Output format

When done, append a **ROUTE-RESULT block** in this exact format:

```
ROUTE-RESULT
units_routed: <comma-separated unit names from input, or "none">
units_skipped: <unit>:<reason>, … (or "none")
output_file: <path you wrote to>
status: success | partial | failed
details: <one line summary; if failed, the error>
reclassify: <unit>:<new_route>:<new_layer>:<reason> (one per line; or "none")
```

Examples:
```
ROUTE-RESULT
units_routed: libs/eip-core-security, libs/eip-secrets, libs/eip-core-arcadedb
units_skipped: none
output_file: docs/architecture/other/security.md
status: success
details: 3 security/infra libraries documented with config and interfaces
reclassify: none
```

```
ROUTE-RESULT
units_routed: docker-compose.app.yml, docker-compose.infra.yml, config/(root files)
units_skipped: config/legacy-backup.yml:obsolete (stale overlay)
output_file: docs/architecture/other/infrastructure.md
status: success
details: Compose topology, networks, observability stack documented
reclassify: none
```

## Error cases

- Output file cannot be created → write error and exit
- A unit path does not exist → skip it, record in `units_skipped`
- Unit is empty/build output only → skip, reason "no source"
- Unit is actually a runnable service (has main/entrypoint) → reclassify to `service`

## Unit -> layer mapping (this repo)

Use this as the first hypothesis; correct it if code shows otherwise:

| Unit pattern | Layer |
|---|---|
| `libs/eip-*` | Based on the name: `-security` → security, `-arcadedb` / `-graph` → knowledge-graph, `-ai-client` / `-reasoning` → intelligence, `-agent-*` → agent, `-mcp-` → mcp, `-common` → shared; others → shared |
| `mcp/eip-mcp-*` | mcp |
| `agents/` (not agents/specs) | agent |
| `ontology/` | knowledge-graph |
| `config/`, `docker-compose*.yml`, root `pom.xml` | infrastructure |
| `tools/distribution` | infrastructure |
| `scripts/` | infrastructure |
