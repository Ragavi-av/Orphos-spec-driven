# Quick Start — Architecture Understanding Skill

## What it does

Documents your codebase's architecture by discovering units (services, UIs, libraries), routing them to specialized sub-skills, and producing evidence-backed layer documentation.

## One-minute usage

```bash
# Document the whole repo:
/architecture-understanding .

# Document one folder:
/architecture-understanding services

# Just UI components:
/architecture-understanding frontend --only ui

# Dry run (show plan, don't write):
/architecture-understanding . --dry-run

# Exclude extra folders:
/architecture-understanding . --exclude vendor,dist
```

## What you get

```
docs/architecture/
├── README.md ...................... index + how to regenerate
├── routingoverview.md ............. latest routing decision log
├── ui/experience.md ............... web shell, remotes, BFF, widgets
├── ui/mobile.md ................... Android, mobile-BFF
├── services/intelligence.md ....... NL query, reasoning, AI clients
├── services/agent.md .............. orchestrator, individual agents
├── services/mcp.md ................ MCP servers (tools exposed)
├── other/infrastructure.md ........ Docker, compose, observability
├── other/security.md .............. Auth, OPA, secrets
└── other/shared.md ................ common libraries
```

Each file documents:
- **Components**: what they are, responsibility
- **APIs**: endpoints (REST, Kafka, MCP tools), auth
- **Dependencies**: what they call, with env-var names
- **Flows**: user journey and service-to-service calls (Mermaid diagrams)
- **Config**: env vars that change behavior
- **Guardrails**: security rules, multi-tenancy isolation, AI safety patterns
- **Gaps**: TODOs, mock data, swallowed errors

## How it works

1. **Discover** all buildable units (services, UIs, libraries) in the folder
2. **Classify** each as UI, service, or other (deterministic rules, no guessing)
3. **Route** to the right sub-skill (one for each type)
4. **Extract** evidence from code (APIs, dependencies, flows)
5. **Verify** output files exist and are complete
6. **Record** routing decisions in `routingoverview.md`

## Key features

✅ **Deterministic**: Same folder = same result (rules-based, not heuristic)  
✅ **Parallel**: Up to 8 sub-skills run at once → fast  
✅ **Evidence-based**: Every claim cites a file path  
✅ **Approval gates**: Two checkpoints before work is dispatched  
✅ **Audit trail**: Routing decisions recorded forever  
✅ **Retry**: Failed outputs are retried once  

## Troubleshooting

**Q: Sub-skill says "reclassify: unit X → service"?**  
A: Check routing-rules.md — the unit probably has a Spring/Express manifest. Either the unit is misclassified or its manifest is wrong.

**Q: Output file missing?**  
A: Orchestrator retried automatically. Check the sub-skill's error in the result. Most likely: output path is outside `docs/architecture/`.

**Q: Docs went out of sync?**  
A: Code changed but docs didn't. Re-run:
```bash
/architecture-understanding .
```

**Q: `docs/` is gitignored — how do I save the output?**  
A: Manually force-add:
```bash
git add -f docs/architecture/
git commit -m "architecture docs"
```

Or add to `.gitignore` exception:
```
# .gitignore
!docs/architecture/
```

## Next steps

1. **Try a small run:**
   ```bash
   /architecture-understanding services
   ```
   This documents all Java services in ~30 seconds.

2. **Review the output:**
   Open `docs/architecture/services/experience.md` — see endpoints, flows, dependencies.

3. **Full repo run:**
   ```bash
   /architecture-understanding .
   ```
   Produces ~10 docs, one per layer.

4. **Verify setup:**
   ```bash
   /architecture-understanding verify
   ```
   Checks that all cited paths still exist (for CI).

---

**For full details**: read [`.claude/skills/README.md`](../skills/README.md) or the comprehensive guide in `ROUTING_SKILL_SUMMARY.md`.
