# QA Agent - Automated Test Execution & Quality Gate

**Complete automated QA pipeline:** Generate Playwright tests from QA test cases, execute them, evaluate quality gates, and update Jira with production readiness status.

## Arguments

- `/qa-agent <STORY-KEY>` — process single story: generate tests → execute → gate → update Jira
- `/qa-agent` — auto-scan: process all stories with approved test cases
- `/qa-agent scan` — list stories ready for QA execution
- `/qa-agent execute <STORY-KEY>` — skip generation, run existing tests only
- `/qa-agent gate` — evaluate gate decision without running tests
- add `--auto` to skip review pauses

## Workflow: Four Phases

### Phase 1: Generate
- Read test cases (from story-to-test-cases skill)
- Generate Playwright TypeScript scripts
- Validate syntax
- Output: `.spec.ts` files ready to execute

### Phase 2: Execute
- Set up QA environment
- Run Playwright tests (parallel workers)
- Capture results: pass/fail/skip/timeout
- Record videos/screenshots on failure
- Calculate test score

### Phase 3: Gate
- Evaluate: `(passed / total) * 100 >= threshold`?
- Check for critical test failures
- Make deployment readiness decision
- Decision: APPROVED (>= 90%) or BLOCKED (< 90%)

### Phase 4: Update Jira
- If APPROVED: Mark story → "Ready for Production"
- If BLOCKED: Mark story → "Tests Failed - Review Required"
- Post test summary comment
- Link test results dashboard
- Create/update bug issues for failures

## Hard Rules

1. **Gate is automatic, never override manually.** Only programmatic override with `--override <reason>` and `--approver <user>`.
2. **No deployment happens.** Only Jira status updates and notification.
3. **Score calculation is transparent.** Show all test results, not just pass/fail.
4. **Results are auditable.** Every decision logged with timestamp, user, score, threshold.
5. **Test isolation required.** Tests cannot interfere with each other; cleanup must run.

## Setup (Once)

1. **Config:** Copy `qa-agent.config.example.json` to `qa-agent.config.json`

   | key | meaning |
   |---|---|
   | `projectKey` | Jira project key (e.g., "OT") |
   | `storyScanJql` | JQL to find stories with test cases |
   | `storyReadyStatus` | Status to set on APPROVED stories (e.g., "Ready for Production") |
   | `storyFailedStatus` | Status to set on BLOCKED stories (e.g., "Tests Failed") |
   | `testScoreThreshold` | Pass threshold (e.g., 90) |
   | `criticalTestCategories` | Categories that must have 100% pass rate |
   | `qaEnvironment.url` | QA environment base URL |
   | `qaEnvironment.apiBase` | QA API base URL |
   | `playwrightConfig` | Browser, workers, timeout, etc. |
   | `reportingDashboard` | URL where test reports published |
   | `slackWebhook` | Slack notification webhook |

2. **Credentials:** Environment variables (same as other skills)
   ```bash
   JIRA_URL=https://appverseai.atlassian.net
   JIRA_EMAIL=your-email@example.com
   JIRA_TOKEN=your-api-token
   ```

3. **Playwright:** Node.js + Playwright installed
   ```bash
   npm install --save-dev @playwright/test
   ```

## Running the Helper

```powershell
# Process single story
.claude/skills/qa-agent/scripts/run-qa-agent.ps1 run OT-4

# Auto-scan and process all
.claude/skills/qa-agent/scripts/run-qa-agent.ps1 run

# List stories ready for QA
.claude/skills/qa-agent/scripts/run-qa-agent.ps1 scan

# Execute only (skip generation)
.claude/skills/qa-agent/scripts/run-qa-agent.ps1 execute OT-4

# Evaluate gate only
.claude/skills/qa-agent/scripts/run-qa-agent.ps1 gate --results test-results-OT-4.json
```

## Execution Output

Each run produces:
- `test-results-<STORY>.json` — Full test results
- `test-report-<STORY>.html` — Visual dashboard
- `test-results-<STORY>.log` — Detailed logs
- Jira comments with summary
- Slack notifications

## Test Score Calculation

```
Score = (Passed Tests / Total Tests) * 100

Example:
- Total Tests: 8
- Passed: 7
- Failed: 1
- Score: (7 / 8) * 100 = 87.5%

Decision:
- Score >= 90%? → APPROVED ✅ (Ready for Production)
- Score < 90%? → BLOCKED ❌ (Needs fixes)
- Critical failures (100% required)? → BLOCKED ❌ (always)
```

## Jira Integration

### Story Status Updates

```
If APPROVED (>= 90%):
Story Status: In Progress → Ready for Production
Assignee: → Release Lead
Label: +qa-passed
Comment: "✅ QA tests passed (87.5%). Ready for production deployment."

If BLOCKED (< 90%):
Story Status: In Progress → Tests Failed
Assignee: → Dev Lead
Label: +qa-failed
Comment: "❌ QA tests failed (87.5% < 90%). Failed tests: OT-4-TC-005"
```

### Task Updates (if from tasks)

```
Each task in story can have test cases
If APPROVED: Task label +qa-passed
If BLOCKED: Task label +qa-failed
```

### Bug Creation (optional)

```
For each failed test:
- Create bug in same epic
- Title: "QA Test Failed: [test name]"
- Description: Test objective, expected vs actual
- Link to failed test case
- Assign to dev team lead
```

## Configuration Example

```json
{
  "projectKey": "OT",
  
  "jql": {
    "storiesWithTestCases": "issuetype = Story AND status = Approved AND labels in (test-case-generated)",
    "storiesReadyForQA": "issuetype = Story AND status in (Approved, 'In Progress') AND created >= -7d"
  },
  
  "statusMapping": {
    "approved": "Ready for Production",
    "blocked": "Tests Failed",
    "inProgress": "QA In Progress"
  },
  
  "scoring": {
    "threshold": 90,
    "criticalCategories": {
      "tenant-isolation": 100,
      "security": 100,
      "data-integrity": 95
    }
  },
  
  "execution": {
    "parallel": 4,
    "timeout": 300,
    "retryFlaky": 2,
    "browsers": ["chromium"],
    "recordVideo": "on-failure",
    "screenshot": "only-on-failure"
  },
  
  "qaEnvironment": {
    "url": "https://qa.example.com",
    "apiBase": "https://api-qa.example.com",
    "dbHost": "qa-postgres.internal",
    "seedDataBeforeRun": true,
    "resetDataAfterRun": true
  },
  
  "reporting": {
    "dashboard": "https://qa-dashboard.example.com",
    "formats": ["html", "json"],
    "archiveResults": true,
    "resultsRetentionDays": 30
  },
  
  "notifications": {
    "slack": true,
    "slackChannel": "#qa-results",
    "jira": true,
    "email": false
  },
  
  "jiraUpdates": {
    "updateStoryStatus": true,
    "addLabels": true,
    "postComment": true,
    "createBugOnFailure": false,
    "linkTestResults": true
  }
}
```

## Workflow Examples

### Example 1: Story Approved → Ready for Production

```
Developer: Pushes code for OT-4
CI/CD: Triggers QA Agent

QA Agent Phase 1 (Generate):
├─ Read testcases-OT-4.json (8 test cases)
├─ Generate Playwright code
└─ Output: test-OT-4-*.spec.ts (5 files)

QA Agent Phase 2 (Execute):
├─ Setup QA environment
├─ Run 8 tests in parallel (2 workers)
├─ Results: 8/8 passed ✅
└─ Score: 100%

QA Agent Phase 3 (Gate):
├─ Score 100% >= 90%? YES ✅
├─ Critical tests passed? YES ✅
└─ Decision: APPROVED

QA Agent Phase 4 (Jira):
├─ Update story status → "Ready for Production"
├─ Add label: qa-passed
├─ Post comment: "✅ All 8 tests passed (100%)"
└─ Notify Slack: "OT-4 ready for production"

Result: ✅ Story ready for deployment
```

### Example 2: Story Failed → Needs Fixes

```
Developer: Pushes code for OT-4
CI/CD: Triggers QA Agent

QA Agent Phase 1 (Generate):
├─ Read testcases-OT-4.json
├─ Generate Playwright code
└─ Output: test-OT-4-*.spec.ts

QA Agent Phase 2 (Execute):
├─ Setup QA environment
├─ Run 8 tests
├─ Results: 7/8 passed ⚠️
│  └─ Failed: OT-4-TC-005 (Large file upload timeout)
└─ Score: 87.5%

QA Agent Phase 3 (Gate):
├─ Score 87.5% >= 90%? NO ❌
├─ Critical tests passed? YES ✅
└─ Decision: BLOCKED (score below threshold)

QA Agent Phase 4 (Jira):
├─ Update story status → "Tests Failed"
├─ Add label: qa-failed
├─ Post comment with failed test details
├─ Create bug: "QA Test Failed: Large file upload timeout"
└─ Notify Slack: "OT-4 QA failed (87.5% < 90%)"

Result: ❌ Story blocked, needs developer fix
Developer action: Fix timeout, re-push code → retry QA
```

### Example 3: Critical Test Failure

```
Developer: Pushes code for OT-4
CI/CD: Triggers QA Agent

QA Agent Phase 2 (Execute):
├─ Run 8 tests
├─ Results: 7/8 passed, but...
│  └─ Failed: OT-4-TC-006 (Tenant-A can access Tenant-B data) 🚨
└─ Score: 87.5%

QA Agent Phase 3 (Gate):
├─ Score 87.5% >= 90%? NO ❌
├─ Critical tests passed (tenant-isolation must be 100%)? NO ❌
│  └─ Tenant-isolation requirement: 100%
│  └─ Actual: 0% (failed)
└─ Decision: BLOCKED (critical failure)

QA Agent Phase 4 (Jira):
├─ Update story status → "Tests Failed - CRITICAL"
├─ Add label: qa-critical-failure
├─ Post urgent comment: "🚨 CRITICAL: Tenant isolation broken!"
├─ Create P1 bug: "Security: Tenant data leak"
└─ Notify Slack: "@security-team Tenant isolation test failed in OT-4"

Result: 🚨 CRITICAL BLOCK - Security issue
Developer action: Immediate security review, fix, re-test
```

## Auto-scan Mode

Invoked without a story key: `/qa-agent`

1. Scan for all stories with approved test cases (status = Approved, label = test-case-generated)
2. For each story:
   - Generate tests
   - Execute tests
   - Evaluate gate
   - Update Jira
3. Report summary

```
Auto-scan Results:
─────────────────
Stories Processed: 4
✅ APPROVED (Ready for Production): 3
  └─ OT-4 (100%)
  └─ OT-5 (92%)
  └─ OT-7 (95%)
❌ BLOCKED (Needs Fixes): 1
  └─ OT-6 (87.5%)

Next Steps:
• Deploy ready stories: OT-4, OT-5, OT-7
• Fix issues in OT-6, re-run tests
```

## Not Covered

- Actual deployment to production (manual decision)
- Playwright code optimization
- Performance testing beyond timeout detection
- Mobile app testing (web only currently)
- Load testing / stress testing
- Security scanning (beyond isolation tests)
