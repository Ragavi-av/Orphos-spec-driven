param(
    [Parameter(Position = 0)]
    [string]$TaskKey,

    [switch]$DryRun,
    [switch]$Scan
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$qaDir = Split-Path -Parent $scriptDir
$repoRoot = Resolve-Path (Join-Path $scriptDir "..\..\..")

if ($Scan) {
    Write-Host "QA scan is not a Jira lookup; it reports candidate task JSON files in the repo."
    $candidates = Get-ChildItem -Path $repoRoot -Recurse -Filter "testcases-*.json" -File | Select-Object -ExpandProperty FullName
    if (-not $candidates) {
        Write-Host "No candidate test case JSON files found."
        exit 0
    }
    $candidates | ForEach-Object { Write-Host $_ }
    exit 0
}

if (-not $TaskKey) {
    Write-Error "Usage: run-qa-agent.ps1 <TASK-KEY> [-DryRun]"
    exit 64
}

$sourceCandidates = @(
    (Join-Path $repoRoot ".claude\skills\test-case-agent\scratchpad\testcases-$TaskKey.json"),
    (Join-Path $repoRoot ".claude\skills\test-case-agent\testcases-$TaskKey.json"),
    (Join-Path $repoRoot "playwright\testcases\testcases-$TaskKey.json"),
    (Join-Path $repoRoot "testcases-$TaskKey.json")
)

$source = $sourceCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $source) {
    Write-Error "No test case JSON found for $TaskKey. Generate the file with the test-case-agent first."
    exit 2
}

$outputPath = Join-Path $repoRoot "playwright\tests\$TaskKey.spec.ts"
if ($DryRun) {
    Write-Host "Dry run: would convert $source into $outputPath"
    exit 0
}

& node (Join-Path $scriptDir "generate-playwright.mjs") $source $outputPath
exit $LASTEXITCODE
