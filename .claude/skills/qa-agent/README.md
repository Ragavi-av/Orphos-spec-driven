# QA Agent - Setup & Usage Guide

Complete automated QA pipeline: generate Playwright tests, execute them, evaluate quality gates, and update Jira with production readiness status.

## Quick Setup

### 1. Copy Configuration File

```bash
cp qa-agent.config.example.json qa-agent.config.json
```

### 2. Set Up Credentials

```bash
cd scripts
cp .env.example .env
```

Edit `.env` and fill in your Jira credentials:
```
JIRA_URL=https://appverseai.atlassian.net
JIRA_EMAIL=your-email@example.com
JIRA_TOKEN=your-jira-api-token
```

**Get your API token:**
1. Go to https://id.atlassian.com/manage-profile/security/api-tokens
2. Create a new token
3. Copy and paste into `.env`

### 3. Install Node.js & Playwright

```bash
# If not already installed
npm install
npm install --save-dev @playwright/test
```

### 4. Verify Configuration

```bash
java QAAgent.java scan
```

Should list stories with approved test cases.

---

## Usage

### Run Single Story (All 4 Phases)

```powershell
.\.claude\skills\qa-agent\scripts\run-qa-agent.ps1 run OT-4
```

This executes:
1. Generate Playwright tests from `testcases-OT-4.json`
2. Execute tests in QA environment
3. Calculate test score
4. Update Jira story status based on score

### Auto-Scan All Stories

```powershell
.\.claude\skills\qa-agent\scripts\run-qa-agent.ps1 run
```

Finds all stories with approved test cases and processes each one.

### List Stories Ready for QA

```powershell
.\.claude\skills\qa-agent\scripts\run-qa-agent.ps1 scan
```

Shows all stories with test cases and their status.

### Run Tests Only (Skip Generation)

```powershell
.\.claude\skills\qa-agent\scripts\run-qa-agent.ps1 execute OT-4
```

Useful for re-running tests without regenerating.

### Evaluate Gate from Existing Results

```powershell
.\.claude\skills\qa-agent\scripts\run-qa-agent.ps1 gate --results test-results-OT-4.json
```

---

## Configuration

Edit `qa-agent.config.json` to customize behavior:

### Test Score Threshold

```json
"scoring": {
  "threshold": 90
}
```

Default: 90% pass rate required to mark as "Ready for Production"

### Parallel Test Execution

```json
"execution": {
  "parallel": 4
}
```

Run 4 tests simultaneously (faster, but requires more resources)

### Critical Test Categories

```json
"criticalCategories": {
  "tenant-isolation": 100,
  "security": 100
}
```

These categories must have 100% pass rate, regardless of overall score.

### Jira Status Updates

```json
"statusMapping": {
  "approved": "Ready for Production",
  "blocked": "Tests Failed"
}
```

What story status to set based on test results.

---

## Workflow Example

### Story passes tests (87.5% → 92%)

```
Developer pushes code
    ↓
CI/CD triggers QA Agent
    ↓
[Phase 1] Generate Playwright tests from testcases-OT-4.json
    ↓
[Phase 2] Execute 8 tests in parallel
    Result: 7/8 passed = 87.5%
    ↓
[Phase 3] Evaluate gate
    87.5% < 90% → BLOCKED
    ↓
[Phase 4] Update Jira
    Story status: "Tests Failed"
    Comment: "❌ QA tests failed (87.5% < 90%)"
    Create bug: "QA Test Failed: Large file upload"
    ↓
Developer fixes issue
    ↓
Re-run QA Agent
    Now 8/8 passed = 100% → APPROVED
    ↓
Story status: "Ready for Production"
Comment: "✅ All tests passed (100%)"
```

---

## Output Files

Each run produces:

- `test-<STORY>.spec.ts` — Generated Playwright test script
- `test-results-<STORY>.json` — Full test results (JSON)
- `test-report-<STORY>.html` — Visual test report
- `test-results-<STORY>.log` — Detailed execution logs

Example results file:
```json
{
  "storyKey": "OT-4",
  "score": 92.5,
  "total": 8,
  "passed": 7,
  "failed": 1,
  "approved": true,
  "criticalFailures": false,
  "failures": ["OT-4-TC-005: Large file upload"],
  "timestamp": "2026-10-08T15:32:00Z"
}
```

---

## Jira Integration

### Story Status Changes

**If APPROVED (>= 90%):**
```
Status: In Progress → Ready for Production
Label: +qa-passed
Comment: ✅ QA tests passed (92.5%). Ready for production deployment.
```

**If BLOCKED (< 90%):**
```
Status: In Progress → Tests Failed
Label: +qa-failed
Comment: ❌ QA tests failed (87.5% < 90%). Failed tests: OT-4-TC-005
Bug Created: "QA Test Failed: OT-4-TC-005"
```

### Test Results Dashboard

Results posted to: `https://qa-dashboard.example.com/OT-4`

Shows:
- Overall score (87.5%)
- Test breakdown (passed, failed, skipped)
- Failed test details with evidence (screenshot, video)
- Performance metrics

---

## Troubleshooting

### "Test cases not found"

```
error: Test cases not found: testcases-OT-4.json
```

**Solution:** Run story-to-test-cases skill first to generate test cases

```powershell
/story-to-test-cases OT-4
```

### "JIRA credentials missing"

```
Missing: JIRA_URL, JIRA_EMAIL, JIRA_TOKEN
```

**Solution:** Fill in `.env` file in scripts directory

### "Playwright not installed"

```
npx: command not found
```

**Solution:** Install Node.js and Playwright

```bash
npm install --save-dev @playwright/test
```

### "Tests timing out"

Increase timeout in `qa-agent.config.json`:

```json
"execution": {
  "timeout": 600
}
```

Timeout in seconds (default: 300 = 5 minutes)

---

## CI/CD Integration

### GitHub Actions

```yaml
- name: Run QA Tests
  run: |
    .\.claude\skills\qa-agent\scripts\run-qa-agent.ps1 run OT-4
```

### GitLab CI

```yaml
qa-tests:
  script:
    - .\.claude\skills\qa-agent\scripts\run-qa-agent.ps1 run
```

---

## Advanced Configuration

### Override Gate Decision

Force a story to "Ready for Production" even if tests fail (requires approval):

```powershell
.\.claude\skills\qa-agent\scripts\run-qa-agent.ps1 run OT-4 --override "time-critical-fix" --approver ragavi.mugunthan@appverse.dev
```

This creates an audit trail in Jira with who approved the override and why.

### Custom Test Data

Pre-seed test data before running tests:

```json
"testDataManagement": {
  "seedTestData": true,
  "testDataDir": "tests/data"
}
```

Place seed data in `tests/data/` directory.

### Video Recording

Record videos of all test executions:

```json
"execution": {
  "recordVideo": "on"
}
```

Options: `"on"`, `"off"`, `"on-failure"` (default)

---

## Next Steps

1. ✅ Run story-to-test-cases skill to generate test cases
2. ✅ Configure qa-agent.config.json for your environment
3. ✅ Test: `run-qa-agent.ps1 scan` to verify configuration
4. ✅ Run single story: `run-qa-agent.ps1 run OT-4`
5. ✅ Set up CI/CD to auto-run on code push
6. ✅ Monitor results in Jira + QA dashboard

---

## See Also

- [SKILL.md](SKILL.md) — Detailed skill documentation
- [Story-to-Test-Cases Skill](..\story-to-test-cases\SKILL.md) — Generate test cases from stories
- [User Story Agent](..\user-story-agent\SKILL.md) — Generate tasks from stories
- [Epic Reader](..\epic-reader\SKILL.md) — Generate stories from epics
