---
name: architecture-understanding
description: Orchestrator for architecture documentation. Takes a folder the user names, confirms it, discovers the deployable/buildable units inside, classifies each as UI, service or other (and tags its layer — agentic/intelligence, ingestion, UI, knowledge graph, control plane, security, infrastructure…), gets the user's approval of the routing plan, then dispatches each bucket to the architecture-ui, architecture-service or architecture-other sub-skill and writes a timestamped routingoverview.md recording what was routed, ignored or excluded. Use when asked to document, map, explain or audit the architecture of a folder, layer or the whole repo.
---

# Architecture Understanding — routing orchestrator

This skill **does not document code itself**. It decides *which sub-skill documents which folder*,
dispatches that work, checks the result, and records every decision in `routingoverview.md`.

| Route | Sub-skill (path) | Gets |
|---|---|---|
| `ui` | `.claude/skills/architecture-ui/SKILL.md` | web front-ends, micro-frontend remotes, UI packages, mobile apps |
| `service` | `.claude/skills/architecture-service/SKILL.md` | runnable back-end units: microservices, BFFs, agents, MCP servers, workers |
| `other` | `.claude/skills/architecture-other/SKILL.md` | libraries, config, infra/compose, ontologies, scripts, tooling — written into the other layer `.md` files |

Shared references (all sub-skills use them; read the first two before Phase 1):

- [references/routing-rules.md](references/routing-rules.md) — unit detection, classification precedence, layer tagging, default exclusions. **The rules there are deterministic; follow them, don't improvise.**
- [references/guardrails.md](references/guardrails.md) — process guardrails (§A) every sub-skill obeys, and what to capture as architectural guardrails (§B).
- [references/layer-map.md](references/layer-map.md) — Orphos-specific starting hypothesis for layer tags.
- [references/doc-template.md](references/doc-template.md) — section template for layer docs.
- [references/routingoverview-template.md](references/routingoverview-template.md) — exact format of the routing record.

## Arguments

```
/architecture-understanding <folder> [--exclude <glob>[,<glob>…]] [--dry-run] [--only ui|service|other]
```

- `<folder>` — repo-relative or absolute. If omitted, **ask**; never default to the repo root silently.
- `--exclude` — extra folders/globs to skip, on top of the defaults in routing-rules.md §1.
- `--dry-run` — Phases 0–1 and Phase 3 only: produce the plan and `routingoverview.md` (every row
  `PLANNED`), dispatch nothing.
- `--only` — dispatch only that route; other classified units are recorded as `DEFERRED`.

## Hard guardrails (orchestrator)

1. **Only the folder the user gave.** Never read, classify or route anything outside the confirmed
   root. Paths in the plan must all start with that root. Symlinks/junctions that leave the root are
   recorded `EXCLUDED (outside root)`.
2. **Two approval gates, both mandatory.** Gate 1 confirms the folder + exclusions before anything
   is read. Gate 2 approves the routing plan before anything is dispatched. No approval → stop,
   write nothing except (on Gate 2 rejection) nothing at all.
3. **Read-only on source.** Only writes: `docs/architecture/**`. No builds, no `docker compose
   up/down`, no code or config edits. Sub-skills inherit this.
4. **No secrets, no PII** in the plan, the overview or any prompt passed to a sub-agent
   (guardrails §A.5). Folder names and file paths are fine; file *contents* of `.env`,
   `orphos.config.json`, realm exports, Infisical config are not.
5. **Every unit ends in exactly one status.** Nothing classified may silently disappear between
   plan and overview (see Phase 3 reconciliation).
6. **Success is verified, not reported.** A sub-agent saying "done" is not `ROUTED`; the output file
   must exist, be non-empty, and contain a section for each unit it was given.

## Phase 0 — Confirm input (Gate 1)

1. Resolve `<folder>` to an absolute path. If it does not exist or is not a directory, say so and stop.
2. Confirm it is inside the current git repo (`git -C <folder> rev-parse --show-toplevel`). If it is
   not, it can still be analysed, but output goes to `<folder>/docs/architecture/` — say so.
3. Show the user, then ask with **AskUserQuestion** (options: *Proceed* / *Change folder* / *Edit exclusions*):
   - resolved absolute path and repo-relative path
   - default exclusions (routing-rules.md §1) + any `--exclude` they passed
   - output location `docs/architecture/` and that existing files there with the same names will be overwritten
4. Loop until *Proceed* or the user abandons. Their answer is the only folder this run may touch.

## Phase 1 — Discovery (read-only)

1. Record `git rev-parse --short HEAD` (or `no-git`), and the run timestamp — get it from the shell,
   never invent it:
   - PowerShell: `Get-Date -Format "yyyyMMdd-HHmmss"` and `Get-Date -Format o`
   - Bash: `date +%Y%m%d-%H%M%S` and `date -Iseconds`
2. Walk the confirmed root with Glob/`ls` (max depth 4 below root unless a unit boundary is found
   sooner), pruning exclusions first. Do not read file contents beyond the manifests and markers
   listed in routing-rules.md §2–§3.
3. Detect **units** (routing-rules.md §2). A unit is the smallest folder that is independently
   buildable or is a self-contained asset folder. Aggregator manifests (Maven `packaging=pom`, npm
   workspaces root, Gradle root `settings.gradle`) are **not** units — descend into their children.
4. **Classify** each unit `ui | service | other | ignored` by the precedence in routing-rules.md §3,
   recording the *signal* that decided it (e.g. `package.json → react`, `@SpringBootApplication in
   …/WebBffApplication.java`). A unit matching two routes is `ambiguous` — never guess; it goes to Gate 2.
5. **Tag a layer** for each classified unit (routing-rules.md §4; for this repo, layer-map.md first).
6. **Bucket** units by `(route, layer)`. One bucket = one sub-agent = one output file
   (`docs/architecture/<route-dir>/<layer>.md`, see routing-rules.md §5). This guarantees no two
   sub-agents ever write the same file.

## Gate 2 — Approve the routing plan

Present a compact table, then ask with **AskUserQuestion** (*Approve and dispatch* / *Edit routing* / *Dry run only* / *Cancel*):

```
| # | Unit (repo-relative) | Route | Layer | Signal | Output file |
```

Below it, list separately: `ambiguous` units (user must pick a route), `ignored` units with reason,
and excluded folders (collapsed to top-level names). If the user edits routing, apply exactly their
change, re-bucket, and show the plan again. Only *Approve* proceeds to Phase 2.

## Phase 2 — Routing (dispatch)

1. **Check sub-skills exist.** For each route in the plan, confirm its `SKILL.md` path exists. If one
   is missing, every unit of that route is `NOT ROUTED — sub-skill missing`; continue with the others.
2. **Dispatch one Agent per bucket** (`subagent_type: general-purpose`), all independent buckets in a
   single message so they run in parallel; if more than 8 buckets, send in waves of 8. Use this prompt
   verbatim, filling the `<…>`:

   ```
   You are executing the sub-skill at <absolute path to sub-skill SKILL.md>.
   Read that file first and follow it exactly, together with the shared references it names under
   <absolute path to .claude/skills/architecture-understanding/references/>.

   Inputs (do not go outside them):
   - repo_root: <abs path>
   - confirmed_root: <abs path the user approved>   (never read outside this)
   - route: <ui|service|other>
   - layer: <layer tag>
   - units: <newline list of repo-relative unit paths>
   - output_file: <repo-relative path>               (the ONLY file you may write)
   - commit: <hash>   run_timestamp: <ISO timestamp>
   - exclusions: <list>

   Finish your reply with the ROUTE-RESULT block exactly as the sub-skill specifies.
   ```

   If the Agent tool is unavailable, run the buckets **sequentially inline** by reading the
   sub-skill file and following it — same inputs, same one-file rule.
3. **Verify each bucket** when its agent returns:
   - parse its `ROUTE-RESULT` block; missing/malformed → `FAILED (no result block)`
   - `output_file` exists and is non-empty; each unit path appears in it as a heading or table row
   - nothing else under `docs/architecture/` changed except that file (`git status --short docs/architecture`
     when in git; otherwise compare mtimes recorded before dispatch)
   - per-unit status from the block: `documented → ROUTED`, `skipped:<reason> → NOT ROUTED (<reason>)`
4. **Retry once** a bucket that `FAILED` (new agent, same prompt + the failure reason). Still failing →
   record `FAILED` with the reason; do not try a third time and do not write the doc yourself.
5. **Reclassifications.** If a sub-agent reports `reclassify: <unit> -> <route/layer>`, do not
   re-dispatch automatically; record it in the overview's *Reclassification proposals* section.

## Phase 3 — Write `routingoverview.md`

1. Write `docs/architecture/routingoverview-<yyyyMMdd-HHmmss>.md` using
   [references/routingoverview-template.md](references/routingoverview-template.md), then copy it to
   `docs/architecture/routingoverview.md` (latest-run pointer). Older timestamped files are kept.
2. **Reconcile before writing:** count of units in the Gate-2 plan == ROUTED + NOT ROUTED + FAILED +
   DEFERRED + PLANNED rows. Excluded and ignored folders are listed in their own sections. If counts
   differ, find the missing unit — do not write the overview until they match.
3. The verdict line at the top must be one of:
   - `All approved units were routed properly.`
   - `Routing completed with exceptions — see NOT ROUTED / FAILED below.`
   - `Dry run — nothing dispatched.`
4. Update `docs/architecture/README.md` (create if absent): one line per output file produced, link to
   `routingoverview.md`, commit + timestamp. Touch no other existing file.

## Phase 4 — Report to the user

Short message: verdict line, counts per status, links to the overview and each produced doc,
ambiguous/reclassification items needing a decision, anything `FAILED` with its reason.
Remind them `docs/` and `*.md` are gitignored in this repo, so outputs are local unless force-added.

## Repo-specific traps

- `.claude/worktrees/**` contains full copies of the repo (with `node_modules`) — always excluded,
  even if the user names the repo root.
- `docs/ORPHOS-EIP-Technical-Architecture.html/.pdf` are generated from `.src.html` — never routed
  as sources, never edited.
- `drivers/` is a vendored JDBC driver → `EXCLUDED (vendored)` by default.
- ArcadeDB/Ollama can be on an external host: sub-skills document the endpoint *variable*, never an IP.
- The pre-router single-pass version of this skill is kept at
  `references/legacy-single-pass-skill.md` for reference; it is not executed.
