# Guardrails

Two kinds: **A** constrain how the skill works (mandatory); **B** describe what to capture as
architectural guardrails in the output `guardrails.md`.

## A. Process guardrails (mandatory)

1. **Evidence or nothing.** Every component, endpoint, topic, port, dependency and flow hop cites
   a repo-relative path (`services/x/src/main/java/.../Foo.java`, optionally `:line`). No citation →
   do not state it. Do not describe a component from its name alone.
2. **Code beats docs.** Existing prose (`docs/*.md`, READMEs, FEATURES.md, comments) is a lead to
   check, never evidence. If prose and code disagree, document the code and list the discrepancy
   under "Gaps & drift".
3. **Mark certainty.** Use exactly these tags where certainty is not complete:
   - `[inferred]` — deduced from config/wiring but the call path was not traced end to end.
   - `[not implemented]` — declared (interface, compose entry, config key, UI stub) with no working
     code path behind it, or returns mock/static data.
   - `[unverified]` — could not be checked (e.g. runtime-only, external host).
   Never present intended/roadmap architecture as current.
4. **Read-only on source.** The skill writes only under `docs/architecture/`. It never edits code,
   config, compose files, ontologies, or other docs; never runs builds, `docker compose up/down`,
   migrations, or anything that mutates state. Read-only commands only (`git`, `grep`, `ls`, `cat`,
   `docker ps`/`docker compose config` if needed).
5. **No secrets, no PII.** Never copy values of passwords, client secrets, API keys, tokens,
   connection strings with credentials, private IPs tied to a person, emails, user names,
   or seed/test personal data. Write the variable/key name and its purpose instead
   (`KEYCLOAK_CLIENT_SECRET — OIDC client secret for web-bff`). Redact if quoting a snippet.
6. **Self-verify before finishing.** Re-open each cited path (it exists and says what you claim);
   grep each endpoint/topic/port; every doc is linked from README.md; every Mermaid block parses
   (quote labels containing `()/:,`). Report any claim you dropped during verification.
7. **Scope and size.** Skip build output, vendored code and generated files. Prefer tables and
   diagrams to prose. Quote code only when a short snippet (≤ 15 lines) is the clearest evidence.
8. **Stable over volatile.** Record commit hash + date in README.md. Avoid documenting values that
   drift daily (image digests, exact counts of seeded rows) unless architecturally significant.
9. **Don't fix, flag.** Bugs, dead code and security concerns found while reading go into the
   layer's "Gaps & drift" section with a path — not into code changes.

## B. Architectural guardrails to capture in `guardrails.md`

Derive each from code; cite where it is enforced (or note that it is *not* enforced — that is
itself the finding). Group by:

- **Layering & dependency rules** — which layers may call which (e.g. frontends only via BFFs;
  agents reach domain services only through MCP; only graph-proxy/okg-* touch the graph store).
  Check imports and HTTP clients for violations.
- **Identity & access** — every inbound endpoint authenticated? resource-server config per
  service, role/tenant checks, OPA policy calls, internal service tokens, anonymous endpoints.
- **Multi-tenancy** — how tenant is resolved (header/claim/subdomain), where it is propagated,
  where isolation is enforced in queries.
- **AI / LLM safety** — prompt construction, grounding (RAG/graph context), output validation,
  tool allow-lists for agents, human-in-the-loop/approval points, PII handling before LLM calls,
  model/provider config, timeouts and fallbacks.
- **Agent & MCP limits** — which tools each agent may call, side-effecting vs read-only tools,
  rate/iteration limits, audit/decision logging.
- **Data & ingestion** — validation of uploaded files, size/type limits, dedup, idempotency,
  schema bootstrap, ontology conformance.
- **Secrets & config** — sources (Infisical, env, config file), startup ordering assumptions,
  behaviour when a secret is missing (fail fast vs silently degrade).
- **Resilience & observability** — health checks, retries, circuit breakers, timeouts, tracing,
  logging standard (Log4j2 API), metrics exposure.
- **Fallbacks that hide failure** — mock data, swallowed exceptions, default-empty responses.

Format each guardrail as a table row: `Rule | Enforced where (path) | Status (enforced / partial /
not enforced) | Risk if violated`.
