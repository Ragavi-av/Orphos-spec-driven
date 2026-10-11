# Architecture Understanding — Shared references

All files in this directory are shared by the **orchestrator** skill and its three **sub-skills**
(architecture-ui, architecture-service, architecture-other). Read them in this order before any
documentation run.

| File | Purpose | Read before |
|---|---|---|
| **routing-rules.md** | Deterministic classification: how to detect units, route them to UI/service/other, tag layers, create buckets. The rules are mandatory — never improvise. | Phase 1 discovery (both orchestrator and sub-skills) |
| **guardrails.md** | Process guardrails (§A) every skill obeys. Architectural guardrails (§B) — what patterns to capture in the layer docs. | Any skill before starting |
| **layer-map.md** | Starting hypothesis for which module belongs to which layer, specific to Orphos. Sub-skills use it for context. | When tagging units (Phase 1) |
| **doc-template.md** | Section order and content for layer.md files (sections 1–10). | When writing output (Phase 5) |
| **routingoverview-template.md** | Exact format of the routing record written after dispatch (Phase 3 of orchestrator). | After dispatch verification |
| **legacy-single-pass-skill.md** | The original single-pass architecture-understanding skill (kept for reference). | Never — this is superseded by the routing orchestrator |

## How they fit together

```
┌─────────────────────────────────────────────────────────────┐
│ User invokes: /architecture-understanding <folder>          │
└────────────────┬────────────────────────────────────────────┘
                 │
        ┌────────▼────────┐
        │  ORCHESTRATOR    │
        │ (main SKILL.md)  │
        └────────┬────────┘
                 │
        Phase 0: Confirm input (Gate 1)
        Phase 1: Discover units, classify with routing-rules.md
                 Tag layers with layer-map.md
        Gate 2: Show plan, ask approval
        Phase 2: Dispatch to sub-skills
                 ├─ architecture-ui (UI units)
                 ├─ architecture-service (services, agents, MCPs)
                 └─ architecture-other (libraries, config, infra)
        Phase 3: Write routingoverview.md
        Phase 4: Report to user
                 │
        ┌────────▼───────────────────────┐
        │   Each sub-skill receives:      │
        │  - confirmed root              │
        │  - layer tag(s)                │
        │  - unit paths (1-12 per call)  │
        │  - output file path            │
        │  - commit + timestamp          │
        └────────┬───────────────────────┘
                 │
        Read shared references:
        - guardrails.md (process rules)
        - doc-template.md (section order)
        - layer-map.md (context)
        - routing-rules.md (classification context)
                 │
        Extract evidence from each unit:
        - File paths, API endpoints, topics, roles, configs
        - Dependency and flow diagrams
        - Guardrails enforced or violated
                 │
        Write to assigned output_file:
        - [doc-template.md](doc-template.md) sections 1–10
        - Metadata: commit, run date
        - Mermaid diagrams
        - Evidence citations (all repo-relative paths)
                 │
        Output ROUTE-RESULT block:
        - units_routed, units_skipped, output_file, status
        - reclassification proposals (if any)
                 │
        ┌────────▼──────────────────────────────────────┐
        │  ORCHESTRATOR verifies each result:           │
        │  - Output file exists, non-empty              │
        │  - All units in output (heading/table row)    │
        │  - Nothing else written to docs/architecture/ │
        │  - Status recorded in routingoverview.md      │
        └───────────────────────────────────────────────┘
```

## Key rules (apply everywhere)

1. **Read-only on source.** No builds, no `docker compose`, no code edits. Only `git`, `grep`, `ls`, `cat`.
2. **Evidence or nothing.** Every claim cites a repo-relative file path. No guesses.
3. **Code beats docs.** Existing prose is a *lead to check*, never evidence.
4. **No secrets, no PII.** Copy env var *names*, never values. No `.env` contents, connection strings with creds, API keys, user data.
5. **Mark certainty** with tags: `[inferred]`, `[not implemented]`, `[unverified]`.
6. **Sections have content or a one-line omit reason.** No empty sections.
7. **Every output file is linked from docs/architecture/README.md.**
8. **Mermaid diagrams must parse** (quote labels with `()/:,`; max ~25 nodes per diagram).

## Outputs

When done, you have:

```
docs/architecture/
├── README.md                          ← index, commit + date, regeneration instructions
├── routingoverview.md                 ← latest run (pointer)
├── routingoverview-<yyyyMMdd-HHmmss>.md ← timestamped run record
├── ui/
│   ├── experience.md                  ← web shell, remotes, web-bff, widgets, notification
│   └── mobile.md                      ← Android, mobile-bff
├── services/
│   ├── experience.md                  ← web-bff, widgets, notification services
│   ├── domain.md                      ← priority, decision-log, briefing, mail-insight
│   ├── intelligence.md                ← nl-query, okg-reasoning, AI client
│   ├── knowledge-graph.md             ← graph-proxy, okg-ingest, ArcadeDB, Fuseki, Qdrant
│   ├── data-ingestion.md              ← data-ingestion, doc-ingest, voice, connectors, Kafka
│   ├── agent.md                       ← agent-orchestrator, individual agents
│   └── mcp.md                         ← MCP servers (comms, decision, graph, ingest, mail, priority, rag, reasoning)
└── other/
    ├── infrastructure.md              ← compose, networks, docker, observability, scripts
    ├── security.md                    ← Keycloak, OPA, eip-core-security, Infisical, Traefik
    ├── knowledge-graph.md             ← ontologies, schema bootstrap
    ├── control-plane.md               ← platform-core, registry, admin-console, tenancy, config
    └── shared.md                      ← common libraries, utilities
```

(Exact layer names may vary based on what code reveals; this is the expected set for Orphos EIP.)

One additional file per routing run:
- `docs/architecture/routingoverview.md` — always points to the latest timestamped run.

The orchestrator also updates `docs/architecture/README.md` with a table of all produced files,
commit hash, and date.

---

**To regenerate or re-run:**

```
/architecture-understanding [<folder>] [--exclude <glob>] [--dry-run] [--only ui|service|other]
```

or verify an existing run without writing:

```
/architecture-understanding verify
```
