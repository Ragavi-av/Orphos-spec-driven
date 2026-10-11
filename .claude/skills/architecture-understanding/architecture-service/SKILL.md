---
name: architecture-service
description: Document service units — microservices, BFFs, agents, MCP servers, workers. Reads routed units, produces one layer.md file with components, APIs, dependencies, flows, persistence, config and guardrails. Called by the architecture-understanding orchestrator; do not invoke directly.
---

# Sub-skill: architecture-service

You are the handler for service route units. You receive:
- `repo_root`: the git repo root (all unit paths are relative to this)
- `confirmed_root`: the user-approved folder (never read outside it; may be a subfolder of the repo)
- `layer`: the layer tag assigned by the orchestrator (e.g. `experience`, `intelligence`, `agent`, `mcp`)
- `units`: list of repo-relative unit paths, one per line
- `output_file`: repo-relative path where you must write the result (e.g. `docs/architecture/services/experience.md`)
- `commit`, `run_timestamp`: for the metadata header
- `exclusions`: folders the orchestrator marked as excluded (for your reference; do not read them)

## Process

1. **Read shared references** first (all from `.claude/skills/architecture-understanding/references/`):
   - [guardrails.md](../architecture-understanding/references/guardrails.md) — §A mandatory, §B what to capture
   - [doc-template.md](../architecture-understanding/references/doc-template.md) — section order and content
   - [layer-map.md](../architecture-understanding/references/layer-map.md) — context only

2. **Read each unit** specified in `units`:
   - Java: `*Application.java`, `pom.xml`/`build.gradle`, `application.yml`/`.properties`, source under `src/main/java`
   - Node/TypeScript: `package.json`, `tsconfig.json`, `index.ts`/`main.ts`, source under `src/`
   - Python: `pyproject.toml`, `main.py`, `src/`, `requirements.txt`
   - Dockerfile (if present)
   - README.md for context, but **never** cite it as evidence
   - MCP servers: `mcp-manifest.json`, tool definitions
   - Agent specs: orchestrator wiring, trigger configs

3. **For each unit, extract** (do not guess; everything must be in code):
   - **Entry point**: `*Application.java:main()`, Node `index.js`, Python `main`, Docker `ENTRYPOINT`
   - **Inbound APIs**:
     - REST: grep `@RestController`, `@RequestMapping`, `@Get`, `@Post`, `@Put`, `@Delete`, `router.get`, `@app.route` → list verb, path, handler method/function name, auth requirement
     - Kafka: grep `@KafkaListener`, `consumer.subscribe()`, `topic:` → topics consumed
     - MCP: `mcp-manifest.json` → tools exposed (name, input schema, description)
     - Scheduled jobs: grep `@Scheduled`, `schedule()` → cron/rate
     - WebSocket: grep `@WebSocketEndpoint`, `WebSocket.connect()` → endpoint paths
   - **Outbound calls**:
     - HTTP: grep `WebClient`, `RestClient`, `RestTemplate`, `HttpClient`, `fetch`, `axios` → base URL (env var or hardcoded), endpoints called
     - Kafka: grep `.send()`, `KafkaTemplate` → topics produced
     - Database: grep `ArcadeDB`, `PostgresClient`, `JdbcClient`, `ArcadeDB`, `@Repository`, `JpaRepository` → connection string (env var), collections/tables
     - LLM: grep `eip-core-ai-client` imports, `LlmClient`, `ChatClient` → endpoint config, model names
     - Other services: grep `WebClient.create()`, base URLs to other services
   - **Security**:
     - Which resource-server / filter chain config applies (grep `@Configuration`, `SecurityConfig`, `@EnableWebSecurity`)
     - Roles/permissions checked (grep `@PreAuthorize`, `hasRole`, `requiresRole`)
     - OPA policy calls (grep `OpaClient`, policy decision endpoints)
     - Internal service tokens (grep Infisical client usage)
     - Anonymous endpoints (if any; grep routes without auth)
   - **Config**: env vars and properties (names only, never values — grep `System.getenv()`, `${}`, `@Value`, `@ConfigurationProperties`, `.env` structure)
   - **Persistence**:
     - What it reads/writes (collections/tables, grep code + schema files if present)
     - Schema bootstrap (grep `SchemaBootstrap`, `flyway`, `liquibase`, `mongomigrations`)
     - Multi-tenancy: is tenant_id included in queries (grep `@TenantId` or tenant context propagation)?
   - **Multi-tenancy**:
     - How is the tenant resolved (header, claim, URL path)? Grep the interceptor/filter.
     - Is it propagated to outbound calls? Grep `x-tenant-id` header or similar.
     - Where is isolation enforced? Grep the repository query, the SQL, the ArcadeDB schema.
   - **Resilience**:
     - Retries (grep `@Retry`, `RetryPolicy`, `retry=`)
     - Circuit breakers (grep `@CircuitBreaker`, `CircuitBreaker`)
     - Timeouts (grep `timeout`, `Duration.ofSeconds`, timeout configs)
     - Health checks (grep `@Health`, `/health`, `Liveness`, `Readiness`)

4. **Walk dependencies** (not deep code review, structural only):
   - Direct Maven/npm/pip dependencies (library layer tags)
   - Does this service call other services in the same repo? Grep hardcoded endpoints or env-var names.
   - Does it expose MCP tools? Who calls them?
   - Does it have Kafka consumers/producers? Which topics?

5. **Components diagram** (Mermaid `flowchart LR` or `TB`):
   - Your service units as boxes
   - Neighbouring layers (BFFs, domain services, knowledge graph, ingestion, agents) as styled boxes
   - Direct dependency arrows (HTTP, Kafka, DB)
   - Keep under 25 nodes; split if needed

6. **Key flows** — pick workflows that matter:
   - User action → service (e.g. user navigates to Decision Log → web-bff → decision-log-service)
   - Service-to-service (e.g. nl-query → graph-proxy → ArcadeDB)
   - Kafka event → consumer → outbound call (if applicable)
   - Agent → MCP tool → service (if applicable)
   - Each flow: one `sequenceDiagram`, then numbered steps citing class/method/file:line

7. **Write to `output_file`** using the [doc-template.md](../architecture-understanding/references/doc-template.md)
   sections (1–10). Omit sections that do not apply (e.g. WebSocket if the service has no WebSocket).
   **Metadata header:**
   ```markdown
   # <Layer name> (Services)

   > Generated by the architecture-service skill from commit `<commit>` on <date>.
   > Evidence paths are repo-relative. Tags: [inferred] [not implemented] [unverified].
   ```

8. **Guardrails in this layer** (doc §9):
   - Identity: every endpoint authenticated? (list any that are not)
   - Authorization: roles checked at every endpoint? OPA policy enforced?
   - Multi-tenancy: tenant isolation in queries and outbound calls?
   - LLM safety: prompts grounded? Output validated? Tool allow-lists?
   - Data validation: uploaded files scanned? Size limits? Dedup?
   - Secrets: configuration from Infisical, env, or config files? Fail fast if missing?
   - AI tools: side-effecting vs read-only? Rate limits? Approval/audit logging?
   - Link to [guardrails.md](../architecture-understanding/references/guardrails.md) rows; add new ones as needed.

9. **Gaps & drift** (doc §10):
   - Unimplemented endpoints (interface defined, returns `200 OK` or mock)?
   - Swallowed exceptions (catch + log, then return 200)?
   - Missing health checks or always-healthy (no real checks)?
   - TODO comments in critical paths?
   - Stale dependencies or security vulnerabilities (not your review, but flag if obvious)?
   - Config keys documented in code but not in the service's README?
   - Each item: path + observation.

10. **Verify before finishing:**
    - Every cited path exists (open it)
    - Every endpoint/topic/tool name matches the code
    - Every env var name appears in code
    - Mermaid diagrams parse
    - All sections have content or a one-line omit reason
    - No secrets in the doc (copy env var names, not values)

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
units_routed: services/web-bff-service, services/widgets-service, services/notification-service
units_skipped: none
output_file: docs/architecture/services/experience.md
status: success
details: 3 services documented, flows to shell and domain services traced
reclassify: none
```

```
ROUTE-RESULT
units_routed: agents/eip-agent-briefing, agents/eip-agent-decision
units_skipped: agents/eip-agent-stub:no source (stub directory)
output_file: docs/architecture/services/agent.md
status: partial
details: 2 agents documented; stub directory has no runnable code
reclassify: none
```

## Error cases

- Output file cannot be created → write the error and exit
- A unit path does not exist → skip it, record in `units_skipped`
- Unit has no source code (empty, build output only) → skip, reason "no source"
- Unit is a library (pom.xml with `<packaging>jar</packaging>`, no main) → it should have been routed to `other`; reclassify it

## In this repo

- **Java services** use Spring Boot; main class is `*Application.java` with `@SpringBootApplication`
- REST endpoints are `@RestController` or `@RequestMapping` decorated
- Kafka: `@KafkaListener`, topics in `application.yml` under `spring.kafka.…`
- Security config: `eip-core-security`, `@EnableWebSecurity`, resource-server for OAuth/OIDC
- Multi-tenancy: tenant from Keycloak claims or URL path; TenantContext injected in filters
- LLM: via `eip-core-ai-client`, configured with provider (OpenAI, Azure, local Ollama), model name
- ArcadeDB: via `eip-core-arcadedb`, connection to local container or external host (Infisical env var)
- Logging: Log4j2 API (not SLF4J) — grep `import org.apache.logging.log4j.*`
- Health: Spring Boot `@Component implements HealthIndicator`, endpoints under `/health`
- Agents: orchestrator in `services/agent-orchestrator-service`, individual agents under `agents/`
- MCP servers: `mcp-manifest.json`, tool definitions under each `eip-mcp-*` module, consumed by agent runtime
