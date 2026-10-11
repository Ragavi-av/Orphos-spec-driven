# Story Agent — Local Setup

## Quick Start

### 1. Copy the example `.env` file
```bash
cp .env.example .env
```

### 2. Fill in your Jira credentials in `.env`
```
JIRA_URL=https://your-instance.atlassian.net
JIRA_EMAIL=your-email@company.com
JIRA_TOKEN=your-api-token
```

### 3. Run the helper
```bash
java StoryAgent.java scan
```

## Credential Loading Order

The StoryAgent loads Jira credentials in this order:

1. **Environment variables** (highest priority)
   ```powershell
   $env:JIRA_URL = "..."
   $env:JIRA_EMAIL = "..."
   $env:JIRA_TOKEN = "..."
   ```

2. **`.env` file** (if env vars not set)
   - Reads from `.env` in the current directory or parent script directory
   - Format: `KEY=value` (one per line)
   - Supports comments starting with `#`

3. **Error** (if neither is available)
   - Fails with clear error message listing missing variables

## Getting a Jira API Token

1. Go to: https://id.atlassian.com/manage-profile/security/api-tokens
2. Click **"Create API token"**
3. Copy the token (shown only once)
4. Paste into `.env` as `JIRA_TOKEN=...`

## Security Notes

- `.env` is **gitignored** — never committed to the repo
- Use `.env.example` as a template for new team members
- Never share your `.env` file or API token
- Rotate your token periodically

## Commands

```bash
# Scan for approved stories (requires config file)
java StoryAgent.java scan

# Scan for stories in a specific epic
java StoryAgent.java scan --epic EIP-1

# Read a specific story
java StoryAgent.java read EIP-123

# Create tasks (dry-run)
java StoryAgent.java create EIP-123 tasks.json --dry-run

# Create tasks (for real)
java StoryAgent.java create EIP-123 tasks.json
```

## Using the PowerShell Wrapper

The `run-story-agent.ps1` script automatically loads `.env` credentials and runs the Story Agent:

```powershell
# From the scripts folder
.\run-story-agent.ps1 scan --config "../user-story-agent.config.json"

# From repo root
.\.claude\skills\user-story-agent\scripts\run-story-agent.ps1 scan --config ".\.claude\skills\user-story-agent\user-story-agent.config.json"
```

## Troubleshooting

### "missing environment variables"
- Check `.env` exists and is readable
- Verify keys are spelled exactly: `JIRA_URL`, `JIRA_EMAIL`, `JIRA_TOKEN`
- Check for typos in the file

### "Jira authentication failed (HTTP 401)"
- Verify `JIRA_EMAIL` and `JIRA_TOKEN` are correct
- Check the token hasn't expired (generate a new one)
- Ensure no extra spaces around the `=` in `.env`

### "not found, or no permission to see it (HTTP 404)"
- Check story key spelling (e.g., `EIP-123`)
- Verify you have permission to view the story in Jira
- Check `projectKey` in `user-story-agent.config.json`

## JDK Requirement

Requires JDK 21+ for the `java <file>.java` source launcher feature.

```bash
java --version  # Should show 21 or later
```
