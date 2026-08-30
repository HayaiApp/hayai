param(
    [string] $PreviousSha,
    [Parameter(Mandatory = $true)]
    [string] $CurrentSha,
    [Parameter(Mandatory = $true)]
    [string] $OutputPath,
    [string] $RepositoryUrl = "https://github.com/HayaiApp/hayai"
)

$ErrorActionPreference = "Stop"

git cat-file -e "$CurrentSha^{commit}"
if ($LASTEXITCODE -ne 0) {
    throw "Current nightly commit does not exist: $CurrentSha"
}
$currentCommit = (git rev-parse $CurrentSha).Trim()

$range = $null
$compareRewrittenHistory = $false
if ($PreviousSha) {
    git cat-file -e "$PreviousSha^{commit}" 2>$null
    if ($LASTEXITCODE -eq 0) {
        git merge-base --is-ancestor $PreviousSha $currentCommit
        if ($LASTEXITCODE -eq 0 -and $PreviousSha -ne $CurrentSha) {
            $range = "$PreviousSha..$currentCommit"
        } elseif ($PreviousSha -ne $currentCommit) {
            $range = "$PreviousSha...$currentCommit"
            $compareRewrittenHistory = $true
        }
    }
}

if (-not $range) {
    $oldest = git rev-list --max-count=20 $currentCommit | Select-Object -Last 1
    $range = if ($oldest -and $oldest -ne $currentCommit) { "$oldest^..$currentCommit" } else { $currentCommit }
}

$logArguments = @("log", "--no-merges", "--format=%H`t%s")
if ($compareRewrittenHistory) {
    $logArguments += @("--cherry-pick", "--right-only")
}
$logArguments += $range

$changes =
    & git @logArguments |
        ForEach-Object {
            $parts = $_ -split "`t", 2
            if ($parts.Count -ne 2) { return }
            $sha = $parts[0]
            $subject = $parts[1] -replace '^(fix|feat|docs|chore|test|refactor|build|ci)(\([^)]*\))?:\s*', ''
            if ($subject -match '^(Refresh J2K patch stack|Record .*validation|Prepare .*nightl)') { return }
            if ($subject -notmatch '[\p{L}\p{N}]') { return }
            $subject = $subject.Substring(0, 1).ToUpperInvariant() + $subject.Substring(1)
            "- $subject ([commit]($RepositoryUrl/commit/$sha))"
        } |
        Select-Object -Unique -First 30

if (-not $changes) {
    $changes = @("- Maintenance and dependency updates")
}

$shortSha = (git rev-parse --short $currentCommit).Trim()
$body = (@(
    "## What changed",
    ""
) + @($changes) + @(
    "",
    "This test build comes from [$shortSha]($RepositoryUrl/commit/$currentCommit).",
    "",
    "Back up your library before installing a nightly build."
)) -join "`n"

Set-Content -LiteralPath $OutputPath -Value $body -Encoding utf8NoBOM
