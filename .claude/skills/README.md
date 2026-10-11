# Claude Skills for Orphos EIP

This directory holds custom skills for the Orphos EIP codebase analysis and generation.

## Architecture Understanding Skill Suite

The **architecture-understanding** skill is an **orchestrator-router** that takes a folder the user
specifies, discovers all buildable/deployable units inside it, classifies each as UI, service, or
other (and assigns it a layer tag), gets user approval of the routing plan, then dispatches
work to three specialized sub-skills:

- **architecture-ui** — documents UI units (web frontends, micro-frontend remotes, mobile apps)
- **architecture-service** — documents services (microservices, BFFs, agents, MCP servers)
- **architecture-other** — documents infrastructure, config, libraries, ontologies, scripts

Each sub-skill produces **one Markdown file per (route, layer) bucket**, with evidence-backed
component tables, API endpoints, dependencies, key flows, data/persistence, config, guardrails,
and gaps.

### Quick start

```bash
# Full run on a folder:
/architecture-understanding <folder>

# Just one layer:
/architecture-understanding <folder> --only ui

# Dry run (show plan, don't dispatch):
/architecture-understanding <folder> --dry-run

# Exclude extra folders:
/architecture-understanding <folder> --exclude vendor,generated

# Verify an existing run (no output changes):
/architecture-understanding verify
```

### What you get

- `docs/architecture/` — timestamped run record + layer documentation
  - `routingoverview.md` — routing decision log (which units went where, why)
  - `ui/`, `services/`, `other/` — organized by route and layer
  - `README.md` — index, commit hash, regeneration instructions
- All files follow the shared template; evidence is traced to repo-relative file paths
- No secrets, no PII, no code changes — read-only documentation

### How the orchestrator works

**Phase 0**: Confirm the folder and exclusions (Gate 1 approval).

**Phase 1**: Walk the folder tree, detect **units** (smallest independently buildable parts),
classify each by deterministic rules (routing-rules.md):
- **UI route**: web apps, micro-frontends, mobile apps (detected by `package.json` with React/Vue/etc., Android manifests, etc.)
- **Service route**: microservices, agents, MCP servers (detected by Spring Boot apps, server manifests, etc.)
- **Other route**: libraries, config, infrastructure (detected by library manifests, loose configs, compose files, etc.)

Assign each unit a **layer tag** (experience, agent, intelligence, knowledge-graph, etc.) using
layer-map.md.

**Phase 2**: Bucket units by (route, layer). Show the plan to the user (Gate 2 approval).

**Phase 3**: Dispatch each bucket to a sub-skill as an independent agent. Sub-skills:
- Read the shared references (guardrails.md, doc-template.md, layer-map.md, routing-rules.md)
- Extract evidence from each unit's code (endpoints, topics, dependencies, config, security rules)
- Write one `.md` file per bucket with documented components, flows, guardrails, gaps
- Return a `ROUTE-RESULT` block confirming what was routed and what was skipped

**Phase 4**: Verify each sub-skill's output (file exists, all units documented), collect results.

**Phase 5**: Write `routingoverview.md` with the full routing record (which units routed where,
which failed, reconciliation check).

### Key principles

1. **Deterministic classification**: routing-rules.md defines every route decision; there are no
   judgment calls or ambiguity.

2. **Orchestrator never writes docs** — it plans, routes, verifies, and records decisions. Only
   sub-skills write the architecture docs.

3. **Parallel dispatch**: independent buckets run as concurrent agents (up to 8 at a time), so
   large runs stay fast.

4. **Evidence-only**: every component, endpoint, topic, config key, dependency cites a repo-relative
   file path. Claims without citations are dropped.

5. **Guardrails captured**: the output `.md` files record architectural patterns the code enforces
   (layering rules, security checks, multi-tenancy isolation, resilience patterns, etc.) so
   they stay in sync with reality.

### Shared references (in `architecture-understanding/references/`)

- **routing-rules.md** — deterministic classification algorithm (unit detection, route precedence, layer tagging, bucket creation)
- **guardrails.md** — process guardrails (§A, mandatory for all skills) + architectural guardrails to capture (§B)
- **layer-map.md** — Orphos-specific layer assignments (starting hypothesis; code wins if it disagrees)
- **doc-template.md** — section order and content for layer docs
- **routingoverview-template.md** — exact format of the routing run record
- **legacy-single-pass-skill.md** — the original all-in-one skill (kept for reference; superseded by the orchestrator)
- **README.md** — how the orchestrator and sub-skills work together (flow diagram, output structure)

### For Orphos

The system maps the Orphos-EIP codebase into **11 layers** (and a `shared` bucket for utilities):

| Route | Layer | Examples |
|---|---|---|
| `ui` | `experience` | frontend/shell, web-bff-service, widgets-service, notification-service |
| `ui` | `mobile` | mobile/android, mobile-bff-service |
| `service` | `experience` | web-bff-service, widgets-service, notification-service |
| `service` | `domain` | priority-engine, decision-log, briefing, mail-insight |
| `service` | `intelligence` | nl-query, okg-reasoning, LLM/embedding clients |
| `service` | `knowledge-graph` | graph-proxy, okg-ingest |
| `service` | `data-ingestion` | data-ingestion, doc-ingest, voice, connectors, Kafka |
| `service` | `agent` | agent-orchestrator, individual agents |
| `service` | `mcp` | MCP servers (comms, decision, graph, ingest, mail, priority, rag, reasoning) |
| `other` | `infrastructure` | docker-compose, observability, scripts, distribution |
| `other` | `security` | Keycloak, OPA, eip-core-security, Infisical, Traefik |
| `other` | `knowledge-graph` | ontologies, schema bootstrap |
| `other` | `control-plane` | platform-core, admin-console, registry, tenancy, config |
| `other` | `shared` | eip-common, utilities |

(See layer-map.md for the full matrix.)

### Troubleshooting

- **"routing-rules says R3, but I think it's service"** → Update your Unit's manifest or rename it.
  The rules are deterministic; if the result is wrong, the input was ambiguous.
  
- **Sub-skill said "reclassify: X → service"** → You routed a unit to the wrong place. Check
  routing-rules.md Phase 3 precedence; sub-skills may report reclassifications for edge cases.
  
- **Output file is missing/incomplete** → Sub-skill failed. Check the Agent result / ROUTE-RESULT
  block. Orchestrator will retry once; if it still fails, check the sub-skill's error message.
  
- **Docs are stale / a component moved** → Re-run the orchestrator on the affected folder:
  ```bash
  /architecture-understanding <folder>
  ```
  The output files are *local* (`docs/` is gitignored), so you must force-add them to version control if you want to commit them.

---

**Status**: architecture-understanding orchestrator + three sub-skills (ui, service, other) are
ready for use. See `architecture-understanding/SKILL.md` for the full spec.
