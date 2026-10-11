# QA Agent

The QA agent converts the JSON created by the test-case-agent into Playwright automation specs.

## Typical flow

1. A Jira task is approved.
2. The test-case-agent creates `testcases-<TASK-KEY>.json`.
3. The QA agent reads that JSON.
4. The QA agent writes a Playwright test file that reflects the covered scenarios.

## Commands

```powershell
.claude/skills/qa-agent/scripts/run-qa-agent.ps1 EIP-123
.claude/skills/qa-agent/scripts/run-qa-agent.ps1 EIP-123 --dry-run
.claude/skills/qa-agent/scripts/run-qa-agent.ps1 scan
```

## Output

Generated specs land in a repo convention such as:

```text
playwright/tests/EIP-123.spec.ts
```

The script generator keeps the files easy to review and trace back to the original task and acceptance criteria.
