# Load credentials and run the Test Case Agent helper.
# Usage: .\run-test-case-agent.ps1 <command> [args]   (run from anywhere)

param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$HelperArgs
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$skillDir  = Split-Path -Parent $scriptDir

# .env: own folder first, then the sibling user-story-agent (same Jira site). Values are never echoed.
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

# Every JVM on this machine needs TEMP/TMP off %LOCALAPPDATA%\Temp (HttpClient loopback failure otherwise).
if (-not (Test-Path 'D:\build-temp')) { New-Item -ItemType Directory -Path 'D:\build-temp' | Out-Null }
$env:TEMP = 'D:\build-temp'; $env:TMP = 'D:\build-temp'

$config = Join-Path $skillDir "test-case-agent.config.json"
& java (Join-Path $scriptDir "TestCaseAgent.java") @HelperArgs --config $config
exit $LASTEXITCODE
