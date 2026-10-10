# QA Agent - Automated test execution and quality gate
# Usage: .\run-qa-agent.ps1 <command> [args]

param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$HelperArgs
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$skillDir  = Split-Path -Parent $scriptDir

# Load credentials from .env files
foreach ($envFile in @((Join-Path $scriptDir ".env"),
                       (Join-Path $skillDir "..\user-story-agent\scripts\.env"))) {
    if (Test-Path $envFile) {
        foreach ($line in Get-Content $envFile) {
            $line = $line.Trim()
            if ($line -and -not $line.StartsWith("#")) {
                $parts = $line -split "=", 2
                if ($parts.Count -eq 2 -and -not [Environment]::GetEnvironmentVariable($parts[0].Trim())) {
                    [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim().Trim('"').Trim("'"))
                }
            }
        }
        break
    }
}

# Set TEMP/TMP for JVM
if (-not (Test-Path 'D:\build-temp')) { New-Item -ItemType Directory -Path 'D:\build-temp' | Out-Null }
$env:TEMP = 'D:\build-temp'; $env:TMP = 'D:\build-temp'

# Run QA Agent
$config = Join-Path $skillDir "qa-agent.config.json"
& java (Join-Path $scriptDir "QAAgent.java") @HelperArgs --config $config
exit $LASTEXITCODE
