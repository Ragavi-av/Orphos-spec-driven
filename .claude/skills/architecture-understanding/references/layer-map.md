# Layer map (starting hypothesis — verify against code)

Derived from root `pom.xml` modules, `docker-compose.*.yml` and the directory tree. If code shows
a module belongs elsewhere, move it and note the change in the output README.

| Layer (doc) | Modules / services |
|---|---|
| Experience (`experience.md`) | `frontend/shell` (host), `frontend/remotes/{ai-copilot,decision-log,doc-ingest,email-scanner,rag-search,widgets}`, `frontend/packages/eip-shell-context`, `services/web-bff-service`, `services/widgets-service`, `services/notification-service`; compose `shell`, `plugin-*` |
| Mobile (`mobile.md`) | `mobile/android`, `services/mobile-bff-service`; compose `mobile-proxy` |
| Control plane (`control-plane.md`) | `services/platform-core-service`, `services/platform-registry-service`, `services/admin-console-service`, `tools/setup-wizard`, `libs/eip-core-dbregistry`, `libs/eip-secrets`, `config/connections`, `orphos.config.json` (keys) |
| Domain (`domain.md`) | `services/priority-engine-service`, `services/decision-log-service`, `services/briefing-service`, `services/mail-insight-service`, `ontology/*.owl` (domain vocabularies) |
| Intelligence (`intelligence.md`) | `services/nl-query-service`, `services/okg-reasoning-service`, `libs/eip-core-ai-client`; compose `ollama`, `ollama-hi` (`docker-compose.ai.yml` / `docker-compose.remote-ai.yml` overlay) |
| Knowledge graph (`knowledge-graph.md`) | `services/graph-proxy-service`, `services/okg-ingest-service`, `libs/eip-core-arcadedb`, `ontology/`, compose `arcadedb`, `fuseki`, `qdrant` |
| Data ingestion (`data-ingestion.md`) | `services/data-ingestion-service`, `services/doc-ingest-service`, `services/voice-service`, compose `docling`, `whisper`, `kafka`, `connections-seed` |
| Agent (`agent.md`) | `libs/eip-agent-spec`, `libs/eip-agent-runtime`, `services/agent-orchestrator-service`, `agents/eip-agent-{attention,briefing,comms-triage,decision,email-triage}` |
| MCP (`mcp.md`) | `libs/eip-mcp-spec`, `mcp/eip-mcp-{comms,decision,graph,ingest,mail,priority,rag,reasoning}` |
| Security (`security.md`) | `libs/eip-core-security`, compose `keycloak`, `infisical*`, `traefik`; `config/{keycloak,opa,infisical,traefik}` |
| Infrastructure (`infrastructure.md`) | all `docker-compose.*.yml`, `config/{prometheus,perses,fluent-bit,postgres}`, compose `jaeger`, `victoria-logs`, `fluent-bit`, `postgres`, `kafka`; `tools/distribution`, `scripts/`, `start*/stop*`, `Makefile` |
| Shared (cite from whichever layer uses it) | `libs/eip-common` |
