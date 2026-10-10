# Load .env file and run Epic Reader
# Usage: .\run-epic-reader.ps1 <command> [args]

param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$Args
)

# Load .env file if it exists
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $scriptDir ".env"

if (Test-Path $envFile) {
    Write-Host "Loading credentials from .env" -ForegroundColor Cyan
    $content = Get-Content $envFile
    foreach ($line in $content) {
        $line = $line.Trim()
        if ($line -and -not $line.StartsWith("#")) {
            $parts = $line -split "=", 2
            if ($parts.Count -eq 2) {
                $key = $parts[0].Trim()
                $value = $parts[1].Trim().Trim('"').Trim("'")
                [Environment]::SetEnvironmentVariable($key, $value)
                Write-Host "  X Set $key" -ForegroundColor Green
            }
        }
    }
    Write-Host ""
}
else {
    Write-Host "WARNING: .env file not found" -ForegroundColor Yellow
    exit 1
}

# Run Epic Reader
Write-Host "Running Epic Reader..." -ForegroundColor Cyan
& java "EpicReader.java" $Args
