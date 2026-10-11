# routingoverview.md template

Write exactly these sections in this order. Timestamps come from the shell (never invented).
No secrets, no PII, no file contents — paths, signals and statuses only.

````markdown
# Routing overview

| | |
|---|---|
| Run timestamp | <ISO-8601 from shell, with offset> |
| Run id | <yyyyMMdd-HHmmss> |
| Confirmed root | <repo-relative path approved at Gate 1> |
| Commit | <short hash or `no-git`> |
| Mode | full / dry-run / only=<route> |
| Exclusions | defaults + <user globs, or "none"> |

**Verdict:** <one of the three verdict lines from SKILL.md Phase 3>

## Summary

| Status | Count |
|---|---|
| ROUTED | n |
| NOT ROUTED | n |
| FAILED | n |
| DEFERRED | n |
| PLANNED | n |
| IGNORED | n |
| EXCLUDED | n (top-level folders) |

## Routed units

| # | Unit | Route | Layer | Signal (rule) | Sub-skill | Output | Status |
|---|---|---|---|---|---|---|---|
| 1 | services/web-bff-service | service | experience | @SpringBootApplication in …/WebBffApplication.java (R4) | architecture-service | [services/experience.md](services/experience.md) | ROUTED |

## Not routed / failed

| Unit | Route | Status | Reason |
|---|---|---|---|

(Write "None." if empty.)

## Ignored

| Folder | Reason |
|---|---|

## Excluded

| Folder | Reason (default / user / vendored / outside root) |
|---|---|

## Gate-2 decisions

Ambiguous units and how the user resolved them; routing edits the user made. "None." if empty.

## Reclassification proposals

From sub-agent `reclassify:` lines — unit, current route/layer, proposed, reason. Not applied.
"None." if empty.

## Reconciliation

Plan units: N = ROUTED a + NOT ROUTED b + FAILED c + DEFERRED d + PLANNED e  ✔
````
