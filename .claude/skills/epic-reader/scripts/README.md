# Epic Reader — Local Setup

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
java EpicReader.java scan
```

## Credential Loading Order

The EpicReader loads Jira credentials in this order:

1. **Environment variables** (highest priority)
   ```bash
   export JIRA_URL="..."
   export JIRA_EMAIL="..."
   export JIRA_TOKEN="..."
   ```

2. **`.env` file** (if env vars not set)
   - Reads from `.env` in the current directory
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
# Scan for approved epics (requires config file)
java EpicReader.java scan

# Read a specific epic
java EpicReader.java read EIP-123

# Create stories (dry-run)
java EpicReader.java create EIP-123 stories.json --dry-run

# Create stories (for real)
java EpicReader.java create EIP-123 stories.json
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
- Check epic key spelling (e.g., `EIP-123`)
- Verify you have permission to view the epic in Jira
- Check `projectKey` in `epic-reader.config.json`

## JDK Requirement

Requires JDK 21+ for the `java <file>.java` source launcher feature.

```bash
java --version  # Should show 21 or later
```
