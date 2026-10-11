---
name: architecture-understanding
description: Read the Orphos EIP codebase and produce (or refresh) evidence-backed, per-layer Markdown architecture docs under docs/architecture/ — layers, components, APIs, dependencies, control/data flows, Mermaid diagrams and guardrails. Use when asked to document, explain, map, audit or refresh the system architecture, a layer (agents, MCP, knowledge graph, ingestion, control plane, intelligence, BFF/frontend, mobile, infra), or the flow between services.
---

# Architecture Understanding

Produce architecture documentation that describes **what the code actually does today**, one
Markdown file per layer/service group, in `docs/architecture/`. Every claim must be traceable to a
file in this repository.

Read [references/guardrails.md](references/guardrails.md) before starting — the rules there are
mandatory, not advice. Use [references/doc-template.md](references/doc-template.md) for every
layer file and [references/layer-map.md](references/layer-map.md) as the *starting hypothesis* for
which module belongs to which layer (verify it; the code wins over the map).

## Arguments

- no argument → full run: (re)generate every file in the output set.
- a layer name (`agent`, `mcp`, `knowledge-graph`, `data-ingestion`, `control-plane`,
  `intelligence`, `domain`, `experience`, `mobile`, `security`, `infrastructure`) → regenerate only that
  file, then re-check `architecture.md` and `flows.md` for links/claims the change invalidates.
- `verify` → do not rewrite; re-check every cited path, endpoint and port in the existing docs
  and report what drifted (file + line + what the code now says).

## Output set

```
docs/architecture/
  README.md              index: reading order, one line per file, generation date + git commit
  architecture.md        system overview: layer diagram, layer responsibilities, deployment topology
  flows.md               end-to-end request/response + control flows that cross layers (sequence diagrams)
  control-plane.md       platform-core, platform-registry, admin-console, tenancy, config, secrets
  experience.md          web shell + micro-frontend remotes, web-bff, widgets, notification
  mobile.md              Android app + mobile-bff + mobile-proxy
  domain.md              domain services: priority, decision-log, briefing, mail-insight, ontologies
  intelligence.md        nl-query, okg-reasoning, AI client, LLM/embedding providers, RAG
  knowledge-graph.md     ArcadeDB, Fuseki, Qdrant, graph-proxy, okg-ingest, ontology/*.owl, schema bootstrap
  data-ingestion.md      data-ingestion, doc-ingest, voice, connectors, Kafka, docling/whisper
  agent.md               agent spec/runtime, orchestrator, each agent, triggers, tool use
  mcp.md                 MCP spec lib, each MCP server, tools exposed, who calls them
  security.md            Keycloak/OIDC, eip-core-security, OPA, service tokens, Infisical, Traefik
  infrastructure.md      compose files, networks, ports, observability (Prometheus/Perses/Jaeger/Fluent Bit/VictoriaLogs), build & distribution
  guardrails.md          architectural guardrails derived from the code (see references/guardrails.md §B)
```

Add a file when the code reveals a layer not in this list; remove/merge one when a layer is too
thin to stand alone (say so in README.md). Never pad a file to match the template.

## Workflow

1. **Baseline.** Record `git rev-parse --short HEAD` and today's date. Read root `pom.xml`
   (`<module>` list), every `docker-compose.*.yml`, `frontend/*/package.json`,
   `mobile/android/settings.gradle`, `orphos.config.json` keys only (never values). Build the
   module inventory: module → layer → compose service name → port → image.
2. **Classify.** Map each module to a layer using `references/layer-map.md`; correct the map
   where code disagrees and note the correction.
3. **Investigate per layer** (fan out with parallel Explore/general-purpose agents, one per layer,
   when the run covers more than three layers). For each module collect, with file paths:
   - entry point (`*Application.java`, `main`, Dockerfile `ENTRYPOINT`)
   - inbound APIs: `@RestController`/`@*Mapping` paths + HTTP verbs, Kafka listeners, MCP tool
     names, scheduled jobs (`@Scheduled`), websocket/SSE endpoints
   - outbound calls: `WebClient`/`RestClient`/`RestTemplate`/`HttpClient` base URLs and the env var
     that sets them, Kafka producers + topics, DB clients (ArcadeDB, Postgres, Qdrant, Fuseki),
     LLM calls (`eip-core-ai-client`)
   - security: which filter/resource-server config applies, roles checked, OPA calls
   - config: `application.yml`/`.properties` keys and env vars that change behaviour
   - persistence: types/tables/collections read and written
4. **Trace flows.** Pick the flows that matter (at minimum: user login → shell → BFF → service;
   NL question → answer; document/voice/mail ingestion → graph → search; agent trigger → MCP tool
   → domain service; tenant/plugin registration). Follow each call hop by hop in code; each hop
   cites the class/method that makes it.
5. **Write** each file from `references/doc-template.md`. Diagrams in Mermaid (`flowchart`,
   `sequenceDiagram`, `classDiagram`); keep each diagram ≤ ~25 nodes, split otherwise.
6. **Derive guardrails** (`guardrails.md`) from what the code enforces and what it relies on
   implicitly — see `references/guardrails.md` §B.
7. **Self-verify** before finishing (guardrails §A.6): re-open every cited path, grep every
   endpoint/topic/port you wrote down, validate Mermaid syntax by eye (no unquoted parentheses or
   `/` in node labels), and confirm every file is linked from README.md.
8. **Report** to the user: files written, layers added/merged vs the default set, top
   gaps/risks found, and anything you could not verify.

## Repo-specific traps (read before investigating)

- Root `docs/` and `*.md` are gitignored; outputs are local unless the user force-adds them.
- `docs/ORPHOS-EIP-Technical-Architecture.html/.pdf` are **generated** from `.src.html` by
  `docs/diagram-build`; never edit them from this skill. Other files in `docs/` (HLD, LLD, SDD,
  TECHNICAL_ARCHITECTURE…) may be stale — use them only as leads, never as evidence.
- ArcadeDB and Ollama may run on an external LAN host rather than local containers — document
  the configured endpoint *variable*, not a specific IP.
- `.env`, `orphos.config.json`, `config/infisical`, `config/keycloak/*realm*` hold or seed
  secrets: document names and purpose only.
- `drivers/` is a vendored third-party JDBC driver; not first-party architecture.
- `frontend/node_modules`, `*/target`, `*/dist`, `mobile/android/build` are build output — skip.
