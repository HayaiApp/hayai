param([string]$Base = "j2k/master")

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$patchRoot = (Resolve-Path (Join-Path $repositoryRoot "patches")).Path

if ((Split-Path -Parent $patchRoot) -ne $repositoryRoot) {
    throw "Patch directory escaped the repository root: $patchRoot"
}

Push-Location $repositoryRoot
try {
    if (git status --porcelain) {
        throw "Commit or stash all changes before refreshing the patch stack."
    }

    $baseCommit = (git rev-parse "$Base^{commit}").Trim()
    git merge-base --is-ancestor $baseCommit HEAD
    if ($LASTEXITCODE -ne 0) {
        throw "$Base is not an ancestor of HEAD."
    }

    Get-ChildItem -LiteralPath $patchRoot -Filter "*.patch" -File | ForEach-Object {
        if ($_.DirectoryName -ne $patchRoot) {
            throw "Refusing to remove a patch outside $patchRoot"
        }
        Remove-Item -LiteralPath $_.FullName
    }

    $commits = @(git rev-list --reverse --no-merges "$baseCommit..HEAD")
    $patchNames = @()
    foreach ($commit in $commits) {
        $changedPaths = @(git diff-tree --no-commit-id --name-only -r $commit)
        $hasPayload = $changedPaths | Where-Object { $_ -notlike "patches/*" } | Select-Object -First 1
        if (!$hasPayload) { continue }

        $patchNumber = $patchNames.Count + 1
        $output = git format-patch --binary --full-index --no-stat --start-number $patchNumber --output-directory $patchRoot -1 $commit
        if ($LASTEXITCODE -ne 0) {
            throw "git format-patch failed for $commit."
        }
        $patchNames += @($output | ForEach-Object { Split-Path -Leaf $_ })
    }

    Set-Content -LiteralPath (Join-Path $patchRoot "UPSTREAM_BASE") -Value $baseCommit -Encoding utf8NoBOM
    Set-Content -LiteralPath (Join-Path $patchRoot "series") -Value $patchNames -Encoding utf8NoBOM
    Write-Host "Exported $($patchNames.Count) Hayai patches from $baseCommit."
}
finally {
    Pop-Location
}
