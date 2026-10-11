# Impact analysis — which service, domain and agent a story needs

Work from the **code**, not from names alone. Every target you put in a task must be a path
you opened (or, for new modules, a path you confirmed does not exist). The helper rejects
tasks whose `target.module` doesn't match reality.

## 1. Extract the capability

From the story (summary, "As a … I want … so that …", acceptance criteria) write down:

- **Actor / channel** — web user, mobile user, admin, another service, a scheduled/agentic trigger.
- **Data** — which business entities (decision, priority, mail, document, briefing, widget,
  connector record, graph node…) are read or written.
- **Behaviour** — CRUD, query/search, reasoning/inference, ingestion, notification,
  autonomous action, approval.
- **Non-functional** — tenant isolation, RBAC/OPA policy, audit, PII handling, latency.

## 2. Map it to layers and modules

Start from `.claude/skills/architecture-understanding/references/layer-map.md`, then confirm
by searching the code (Grep for the entity / endpoint / topic name; read the controller,
service and repository you'd change). If `docs/architecture/` exists, use its layer docs too.

| Signal in the story | Layer | Typical module(s) |
|---|---|---|
| Web screen, widget, dashboard | experience | `frontend/shell`, `frontend/remotes/<remote>`, `services/web-bff-service`, `services/widgets-service` |
| Mobile screen | mobile | `mobile/android` (outside this repo root may apply), `services/mobile-bff-service` |
| Push/e-mail/in-app notification | experience | `services/notification-service` |
| Priorities, decisions, briefings, mail insights (business rules) | domain | `services/priority-engine-service`, `services/decision-log-service`, `services/briefing-service`, `services/mail-insight-service` |
| New business concept / relationship | domain + knowledge-graph | `ontology/eip-<domain>.owl` (+ `eip-core.owl` for shared terms), `services/okg-ingest-service`, `services/graph-proxy-service` |
| NL question answering, inference, LLM use | intelligence | `services/nl-query-service`, `services/okg-reasoning-service`, `libs/eip-core-ai-client` |
| Ingest from a source system / documents / voice | data-ingestion | `services/data-ingestion-service`, `services/doc-ingest-service`, `services/voice-service`, `services/okg-ingest-service` (Jira etc.) |
| Something acts on the user's behalf, on a trigger, with approval | agent | `agents/eip-agent-<name>`, `services/agent-orchestrator-service`, `libs/eip-agent-spec`, `libs/eip-agent-runtime` |
| An agent/LLM needs a new tool | mcp | `mcp/eip-mcp-<area>` (tools in `.../tools/*Tools.java`) |
| Tenants, admin settings, connector setup, branding | control-plane | `services/platform-core-service`, `services/admin-console-service`, `services/platform-registry-service` |
| Roles, permissions, policy | security | `libs/eip-core-security`, `config/opa`, `config/keycloak` |
| Deployment, compose, observability | infrastructure | `docker-compose.*.yml`, `config/*` |

**Domain** = the business vocabulary the story lives in. Name it after the ontology module that
owns the concepts: `core`, `enterprise`, `finance`, `hr`, `itops`, `risk`, `strategy`,
`commercial`, `governance`, `mobile` (files `ontology/eip-<domain>.owl`). Check the concept
actually exists there (Grep the class/property name). If it doesn't, add an `ontology` task.

## 3. Existing vs new — decide conservatively

Default to **extending an existing module**. Propose a new one only when the story clearly
needs it, and say why in that task's `implementationNotes`:

- **new-service** — a separately deployable capability with its own data/lifecycle that no
  existing service owns, and adding it to an existing one would break that service's
  responsibility. Include compose wiring, Dockerfile, Keycloak client/OPA policy, Log4j2
  config (see the repo's Log4j2 standard), and registry registration in the tasks.
- **new-agent** — the story needs autonomous, triggered behaviour (schedule/event/request)
  with a goal prompt and an autonomy level. Orphos agents are thin: a Spring Boot app that
  declares an `AgentManifest` bean (`Autonomy`, `Trigger`, tools, `goalPromptKey`) and a goal
  prompt under `eip.agent.runtime.prompts` in `application.yml`; the runtime
  (`libs/eip-agent-runtime`) provides planner, MCP gateway, approvals and registration. Model it
  on `agents/eip-agent-decision`. Writes must go through the approval flow
  (`ACT_WITH_APPROVAL`) unless the story explicitly justifies more autonomy.
- **new-mcp** — the tool area doesn't fit any existing `mcp/eip-mcp-*` server. Usually a new
  tool in an existing MCP server is the right answer instead.
- If an existing agent can do it with a new tool or prompt change, that's a `kind: agent` task,
  not `new-agent`.

## 4. Slice into tasks

One task = one module + one reviewable change (≈ ½–2 days). Typical order:

1. ontology / schema (if new concepts)
2. domain/service logic + persistence
3. API (controller, BFF endpoint) + security policy
4. MCP tool / agent manifest + prompt (if agentic)
5. UI (remote / mobile screen)
6. wiring: compose, config, registry, Keycloak/OPA

Use `dependsOn` to point at earlier tasks by summary. Don't create "write tests" tasks — tests
belong inside each task.

## 5. Unit test cases and acceptance criteria

Each code task carries concrete **unit tests** named the way the repo names them
(`<Class>Test` with JUnit 5 + Spring Boot test support; method names describe behaviour, e.g.
`rejectsRequestFromOtherTenant`). Give each: `name`, `given`, `when`, `then`. Cover:

- the happy path from the story's acceptance criteria,
- validation / bad input,
- tenant isolation and authorization (every service is multi-tenant — a story touching data
  needs a "other tenant cannot see/change it" test),
- the failure path of each external call (ArcadeDB, Kafka, LLM, MCP) — degraded, not crashed.

Integration behaviour that needs real infrastructure belongs in a `*IT` test (Testcontainers);
mark those in the test name and note that they need Docker.

Config / infra / ontology tasks may use **acceptanceCriteria** only (Given/When/Then that a
reviewer can check, e.g. "Given the stack is started, When … Then the service registers").
Every task also gets acceptance criteria tracing back to the story's criteria it satisfies.
