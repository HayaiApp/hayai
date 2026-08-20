param([string]$Base = "j2k/master")

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $repositoryRoot
try {
    $changed = @(git diff --name-only $Base; git ls-files --others --exclude-standard) | Sort-Object -Unique
    $allowedUpstreamKotlin = @(
        "app/src/main/java/eu/kanade/tachiyomi/data/database/DatabaseHelper.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/database/DbOpenCallback.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationReceiver.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/main/SearchActivity.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaDetailsController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/recents/RecentsController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsAdvancedController.kt"
    )
    $violations = $changed | Where-Object {
        $_ -like "app/src/main/java/*.kt" -and
        $_ -notlike "app/src/main/java/dev/ahmedmohamed/hayai/*" -and
        $_ -notin $allowedUpstreamKotlin
    }
    $protectedFiles = @(
        "app/src/main/java/eu/kanade/tachiyomi/App.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt"
    )
    $protectedChanges = $changed | Where-Object { $_ -in $protectedFiles }
    if ($violations -or $protectedChanges) {
        Write-Error ((@("Hayai upstream boundary expanded without review:") + $violations + $protectedChanges) -join [Environment]::NewLine)
    }
    Write-Host "Hayai boundary verified: $($allowedUpstreamKotlin.Count) J2K adapter files allowed; protected lifecycle, navigation, and image-reader files untouched."
}
finally {
    Pop-Location
}
