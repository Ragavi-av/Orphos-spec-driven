# PowerShell port of scripts/sample-data/ImportSampleData.java.
# Same contract: substitute __TENANT_ID__, split into statements, POST in
# chunks as language=sqlscript. Avoids java.net.http.HttpClient, which cannot
# initialise on this host (loopback pipe blocked).
param(
    [Parameter(Mandatory=$true)][string]$TenantId,
    [string]$SqlFile   = "D:\Orphos_1\Orphos---EIP\scripts\sample-data\orphos-sample-data.sql",
    [string]$ArcadeHost= "http://192.168.10.2:2480",
    [string]$Database  = "eip_okg",
    [int]$ChunkSize    = 250,
    [string]$EnvFile   = "D:\Orphos_1\Orphos---EIP\.env"
)

$ErrorActionPreference = "Stop"

$pw = (Select-String -Path $EnvFile -Pattern "^ARCADEDB_PASSWORD=").Line.Split('=',2)[1].Trim()
if (-not $pw) { throw "ARCADEDB_PASSWORD not found in $EnvFile" }
$auth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("root:$pw"))
$headers = @{ Authorization = $auth }

$script = (Get-Content $SqlFile -Raw).Replace('__TENANT_ID__', $TenantId)

# Split on statement-terminating semicolons; drop comments and blank lines.
$statements = New-Object System.Collections.Generic.List[string]
foreach ($raw in ($script -split ";\s*\r?\n")) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($line in ($raw -split "\r?\n")) {
        $t = $line.Trim()
        if ($t -eq "" -or $t.StartsWith("--")) { continue }
        [void]$sb.Append($t).Append(' ')
    }
    $stmt = $sb.ToString().Trim()
    # This ArcadeDB build rejects "DELETE VERTEX <type> WHERE ..." with
    # "missing FROM"; the supported form is "DELETE FROM <type> WHERE ...".
    # The generated SQL emits the former, so rewrite it here.
    $stmt = $stmt -replace '^DELETE\s+VERTEX\s+(\w+)\s+WHERE', 'DELETE FROM $1 WHERE'
    if ($stmt -ne "") { $statements.Add($stmt) }
}

Write-Host ("Loaded {0} -> {1:N0} statements. Importing into {2}/{3} (chunks of {4})..." -f `
    (Split-Path $SqlFile -Leaf), $statements.Count, $ArcadeHost, $Database, $ChunkSize)

$url = "$ArcadeHost/api/v1/command/$Database"

# ArcadeDB commits per REQUEST, so type/index DDL is not visible to statements
# in the same sqlscript payload — a DELETE/INSERT batched with the CREATE TYPE
# that defines it fails with "Type with name 'X' was not found". Send the whole
# schema block in its own request first, then the data in chunks.
$schemaRe = '^CREATE\s+(VERTEX\s+TYPE|EDGE\s+TYPE|PROPERTY|INDEX)\b'
$schema = @($statements | Where-Object { $_ -match $schemaRe })
$data   = @($statements | Where-Object { $_ -notmatch $schemaRe })

# ArcadeDB will not index an undeclared property ("Cannot create the index on
# type 'X.tenantId' because the property does not exist"), and the generated SQL
# ships no CREATE PROPERTY. Derive them from the index statements themselves so
# this stays correct if the generator adds types or indexes later. Every indexed
# column here (id, tenantId) is a string.
$types = New-Object System.Collections.Generic.List[string]
$props = New-Object System.Collections.Generic.List[string]
foreach ($s in $schema) {
    if ($s -match '^CREATE\s+(VERTEX|EDGE)\s+TYPE') { $types.Add($s); continue }
    if ($s -match '^CREATE\s+INDEX.*\bON\s+(\w+)\s*\(([^)]+)\)') {
        $t = $Matches[1]
        foreach ($p in ($Matches[2] -split ',')) {
            # ArcadeDB wants IF NOT EXISTS between the property name and its
            # type — "CREATE PROPERTY IF NOT EXISTS x.y STRING" and
            # "... x.y STRING IF NOT EXISTS" are both parse errors.
            $decl = "CREATE PROPERTY {0}.{1} IF NOT EXISTS STRING" -f $t, $p.Trim()
            if (-not $props.Contains($decl)) { $props.Add($decl) }
        }
    }
}
# Ordering matters: types -> properties -> indexes.
$schema = @($types) + @($props) + @($schema | Where-Object { $_ -match '^CREATE\s+INDEX' })

if ($schema.Count -gt 0) {
    Write-Host ("  schema: {0} DDL statements ({1} property declarations synthesised, separate commit)" -f `
        $schema.Count, $props.Count)
    $body = @{ language = "sqlscript"; command = ($schema -join ";`n") + ";" } | ConvertTo-Json -Compress -Depth 3
    try {
        [void](Invoke-RestMethod -Uri $url -Headers $headers -Method Post `
               -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType "application/json")
    } catch {
        Write-Host ("  SCHEMA FAILED: {0}" -f $_.Exception.Message)
        if ($_.ErrorDetails.Message) {
            $m = $_.ErrorDetails.Message
            Write-Host ("  detail: " + $m.Substring(0, [Math]::Min(500, $m.Length)))
        }
        exit 5
    }
}

$statements = $data
$done = 0; $chunkNo = 0
for ($i = 0; $i -lt $statements.Count; $i += $ChunkSize) {
    $end   = [Math]::Min($i + $ChunkSize, $statements.Count) - 1
    $chunk = $statements[$i..$end]
    $command = ($chunk -join ";`n") + ";"
    $body = @{ language = "sqlscript"; command = $command } | ConvertTo-Json -Compress -Depth 3
    $chunkNo++
    try {
        [void](Invoke-RestMethod -Uri $url -Headers $headers -Method Post `
               -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType "application/json")
    } catch {
        Write-Host ""
        Write-Host ("Chunk {0} FAILED: {1}" -f $chunkNo, $_.Exception.Message)
        if ($_.ErrorDetails.Message) {
            $m = $_.ErrorDetails.Message
            Write-Host ("  detail: " + $m.Substring(0, [Math]::Min(500, $m.Length)))
        }
        exit 4
    }
    $done += $chunk.Count
    Write-Host ("  chunk {0} - {1:N0} / {2:N0} statements" -f $chunkNo, $done, $statements.Count)
}
Write-Host ("Done. Imported {0:N0} statements for tenant {1}." -f $done, $TenantId)
