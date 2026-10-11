# QA Agent

Turn approved task test cases into executable Playwright coverage.

## Purpose

This agent reads the JSON created by the test-case-agent skill, validates that the test case artifact is usable, and generates a Playwright spec file for the task. The generated spec should be kept in the repo as automation-ready code, not just human-readable notes.

## Inputs

- `/qa-agent <TASK-KEY>` — generate Playwright from one task's test case artifact
- `/qa-agent` — auto-scan for the newest approved test-case JSON files and generate specs for them
- `/qa-agent scan` — list candidate task files and their statuses without writing code
- `/qa-agent <TASK-KEY> --dry-run` — preview generated output without writing files

## Source artifact

The QA agent expects the output produced by the test-case-agent skill, typically under a scratchpad folder such as:

```text
.claude/skills/test-case-agent/scratchpad/testcases-EIP-123.json
```

or an equivalent task-specific JSON file with this structure:

```json
{
  "taskKey": "EIP-123",
  "coverage": {
    "notApplicable": {
      "edge": "No boundary-driven scenario is applicable."
    }
  },
  "testCases": [
    {
      "title": "Reject application when email already exists in the same tenant",
      "type": "negative",
      "automation": "integration",
      "covers": ["AC1"],
      "steps": [
        { "action": "POST /api/applications with duplicate email data", "expected": "409 Conflict" }
      ],
      "expected": "The request is rejected and no duplicate row is created."
    }
  ]
}
```

## Hard rules

1. The source JSON must already exist. Do not invent test cases when the test-case-agent artifact is missing.
2. The QA agent must only convert tasks that were already produced by the test-case-agent flow. It does not approve tasks or replace the gate.
3. Generate Playwright specs from the test-case JSON, not from assumptions or unrelated stories.
4. Each generated test should map to one real test case, one `test()` block, and the matching AC references in the source artifact.
5. Keep the generated scripts deterministic, readable, and aligned with real app routes and form flows.
6. Use synthetic data only; never copy real customer data or PII into generated code or test names.
7. Keep script names traceable to the task key so they are easy to correlate with Jira.
8. Prefer reusable helpers for login, base URL, requests, and tenant switching when test cases require multi-tenant behavior.
9. Do not generate tests for scenarios the source JSON does not explicitly represent.

## Workflow

### 1. Locate the task artifact

- Resolve the task key from the user argument or from a scan result.
- Search the repository for the matching `testcases-<TASK-KEY>.json` file.
- If multiple files are found, prefer the most recent artifact and surface the full path in the output.

### 2. Validate the input

Before writing automation, verify that:

- the file exists and is valid JSON
- `taskKey` is present
- `testCases` is an array
- every test case has a `title` and at least one `steps` entry
- `covers` or `coversObjective` is present for traceability

If validation fails, stop and report the issues rather than generating broken scripts.

### 3. Build the Playwright spec

Generate a TypeScript Playwright spec that includes:

- `import { test, expect } from '@playwright/test';`
- a `describe` block for the task
- one `test()` per task case
- a stable base URL defaulting to `http://localhost:3000` when no app URL is configured
- request helpers for `GET`, `POST`, `PUT`, `PATCH`, `DELETE`
- `expect` assertions derived from each step `expected` value
- comments that preserve the source coverage mapping, such as `// covers: AC1`

### 4. Write output

Write the generated file under a repository path such as:

```text
playwright/tests/<TASK-KEY>.spec.ts
```

or, if the repo already has a test convention:

```text
frontend/e2e/<TASK-KEY>.spec.ts
```

Prefer the project's current Playwright convention when it already exists.

### 5. Final report

Return a compact summary:

```text
QA generation complete:
✓ Task: EIP-123
✓ Source: .claude/skills/test-case-agent/scratchpad/testcases-EIP-123.json
✓ Generated: playwright/tests/EIP-123.spec.ts
✓ Test cases converted: 4
⊘ Warnings: 1 (tenant-isolation steps use a stubbed tenant context)
```

## Generation conventions

- The generated code should be intentionally scaffolded, not production-perfect automation.
- Use sensible page interactions for common flows: login, navigation, form filling, validation, and response assertions.
- For `tenant-isolation` scenarios, create a helper that switches tenant context using a header or a fixture rather than hardcoding tenant IDs into every test.
- For `validation` scenarios, assert status message or field errors.
- For `negative` scenarios, validate 4xx/5xx behavior and that no mutation occurs.
- For `positive` scenarios, assert the expected success status and UI or API state change.

## Execution model

This agent only generates the Playwright specs. It does not execute them unless the user explicitly asks for a run.

## Example output pattern

```ts
import { test, expect, request } from '@playwright/test';

const baseURL = process.env.BASE_URL ?? 'http://localhost:3000';

async function apiRequest(method: 'get' | 'post' | 'put' | 'patch' | 'delete', path: string, body?: any) {
  const apiContext = await request.newContext();
  const response = await apiContext[method](baseURL + path, body ? { data: body } : undefined);
  return response;
}

test.describe('EIP-123 - duplicate email rejection', () => {
  test('Reject application when email already exists in the same tenant', async ({ page }) => {
    // covers: AC1
    await page.goto(baseURL + '/applications/new');
    await page.fill('[name="email"]', 'student@example.test');
    await page.fill('[name="tenant"]', 'tenant-a');
    await page.click('button:has-text("Submit")');

    await expect(page.getByText('already exists')).toBeVisible();
  });
});
```

This skill is intentionally practical: it converts the task-level test cases into a starting Playwright suite, keeping the connection to the source test artifact explicit and auditable.
