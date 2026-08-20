$ErrorActionPreference = "Stop"

$legacyRef = "legacy/hayai-pre-j2k"
$schemaRoot = "data/src/commonMain/sqldelight/tachiyomi/data/"
$auditFile = "app/src/main/java/dev/ahmedmohamed/hayai/migration/LegacyDataAudit.kt"

$schemaFiles = git ls-tree -r --name-only $legacyRef -- $schemaRoot
if ($LASTEXITCODE -ne 0 -or -not $schemaFiles) {
    throw "Unable to read the legacy Hayai schema from $legacyRef"
}

$schemaTables = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($schemaFile in $schemaFiles) {
    if (-not $schemaFile.EndsWith(".sq")) { continue }
    $schema = git show "${legacyRef}:$schemaFile"
    if ($LASTEXITCODE -ne 0) { throw "Unable to read $schemaFile from $legacyRef" }
    foreach ($line in $schema) {
        if ($line -match '^CREATE TABLE(?: IF NOT EXISTS)?\s+([A-Za-z0-9_]+)') {
            [void]$schemaTables.Add($Matches[1])
        }
    }
}

$auditText = Get-Content -LiteralPath $auditFile -Raw
$classifiedTables = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($setName in @("CORE_TABLES", "TYPED_SIDECAR_TABLES", "ARCHIVED_TABLES")) {
    $match = [regex]::Match($auditText, "(?s)$setName\s*=\s*setOf\((.*?)\)\s*")
    if (-not $match.Success) { throw "Unable to parse production classification set $setName" }
    foreach ($tableMatch in [regex]::Matches($match.Groups[1].Value, '"([A-Za-z0-9_]+)"')) {
        $table = $tableMatch.Groups[1].Value
        if (-not $classifiedTables.Add($table)) { throw "Table $table has more than one production disposition" }
    }
}

$missing = @($schemaTables | Where-Object { -not $classifiedTables.Contains($_) } | Sort-Object)
$extra = @($classifiedTables | Where-Object { -not $schemaTables.Contains($_) } | Sort-Object)
if ($missing.Count -gt 0 -or $extra.Count -gt 0) {
    throw "Legacy schema classification mismatch. Missing: $($missing -join ', '); extra: $($extra -join ', ')"
}

Write-Host "Legacy schema audit verified: $($schemaTables.Count) v36 tables are classified."
