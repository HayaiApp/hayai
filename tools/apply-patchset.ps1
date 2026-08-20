$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$patchRoot = (Resolve-Path (Join-Path $repositoryRoot "patches")).Path

if ((Split-Path -Parent $patchRoot) -ne $repositoryRoot) {
    throw "Patch directory escaped the repository root: $patchRoot"
}

Push-Location $repositoryRoot
try {
    if (git status --porcelain) {
        throw "Apply the patch stack only from a clean worktree."
    }

    $expectedBase = (Get-Content -LiteralPath (Join-Path $patchRoot "UPSTREAM_BASE") -Raw).Trim()
    $currentCommit = (git rev-parse HEAD).Trim()
    if ($currentCommit -ne $expectedBase) {
        throw "HEAD is $currentCommit; the patch stack requires $expectedBase."
    }

    $patchNames = Get-Content -LiteralPath (Join-Path $patchRoot "series") | Where-Object { $_ -and -not $_.StartsWith("#") }
    if (!$patchNames) {
        throw "The patch series is empty. Run tools/refresh-patchset.ps1 after committing the Hayai delta."
    }

    $patchPaths = @()
    foreach ($patchName in $patchNames) {
        $candidate = (Resolve-Path (Join-Path $patchRoot $patchName)).Path
        if ((Split-Path -Parent $candidate) -ne $patchRoot -or [IO.Path]::GetExtension($candidate) -ne ".patch") {
            throw "Invalid patch path: $candidate"
        }
        $patchPaths += $candidate
    }

    git am --3way -- $patchPaths
    if ($LASTEXITCODE -ne 0) {
        throw "Patch replay stopped. Resolve with git am --continue or abort with git am --abort."
    }
}
finally {
    Pop-Location
}
