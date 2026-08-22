param(
    [string]$SourceRef = "sy/master"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$assetPath = Join-Path $repositoryRoot "app/src/main/assets/hayai/eh_tags.txt"
$tagFiles = git -C $repositoryRoot ls-tree -r --name-only $SourceRef |
    Where-Object { $_ -match '^app/src/main/java/exh/eh/tags/(?!TagList\.kt$).+\.kt$' }

if (-not $tagFiles) {
    throw "No TachiyomiSY E-Hentai tag sources were found at $SourceRef"
}

$tags = [System.Collections.Generic.List[string]]::new()
foreach ($file in $tagFiles) {
    $body = git -C $repositoryRoot show "${SourceRef}:$file"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read $file from $SourceRef"
    }
    foreach ($line in $body) {
        if ($line -match '^\s*"([^"]+)",\s*$') {
            $tags.Add($Matches[1])
        }
    }
}

$uniqueTags = $tags | Sort-Object -Unique
if ($uniqueTags.Count -lt 1000) {
    throw "Refusing to write an incomplete E-Hentai tag catalog"
}

$assetDirectory = Split-Path -Parent $assetPath
[System.IO.Directory]::CreateDirectory($assetDirectory) | Out-Null
[System.IO.File]::WriteAllLines($assetPath, $uniqueTags, [System.Text.UTF8Encoding]::new($false))
$commit = git -C $repositoryRoot rev-parse $SourceRef
Write-Output "Wrote $($uniqueTags.Count) E-Hentai tags from $commit"
