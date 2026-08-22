param([string]$Base = "j2k/master")

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $repositoryRoot
try {
    $changed = @(git diff --name-only $Base; git ls-files --others --exclude-standard) | Sort-Object -Unique
    $allowedUpstreamKotlin = @(
        "app/src/main/java/eu/kanade/tachiyomi/AppModule.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupCreator.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupRestorer.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/backup/models/Backup.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/database/DatabaseHelper.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/database/DbOpenCallback.kt"
        "app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt"
        "app/src/main/java/eu/kanade/tachiyomi/extension/model/Extension.kt"
        "app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt"
        "app/src/main/java/eu/kanade/tachiyomi/network/JavaScriptEngine.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationReceiver.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/track/TrackManager.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/updater/GithubRelease.kt"
        "app/src/main/java/eu/kanade/tachiyomi/source/Source.kt"
        "app/src/main/java/eu/kanade/tachiyomi/source/NovelSource.kt"
        "app/src/main/java/eu/kanade/tachiyomi/source/SourceManager.kt"
        "app/src/main/java/eu/kanade/tachiyomi/source/model/Page.kt"
        "app/src/main/java/eu/kanade/tachiyomi/source/online/HttpSource.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/extension/ExtensionBottomPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/extension/ExtensionBottomSheet.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/library/filter/FilterBottomSheet.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/main/SearchActivity.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaDetailsController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaDetailsPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaDetailsAdapter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaHeaderHolder.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/manga/chapter/ChapterHolder.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/SearchPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/SourceHolder.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/manga/design/MigrationSourceHolder.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/manga/design/PreMigrationController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/manga/process/MigrationListController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/more/stats/StatsController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/more/stats/StatsPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/recents/RecentsController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/recents/RecentsPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/recents/options/RecentsHistoryView.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/recents/options/RecentsUpdatesView.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsAdvancedController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsBrowseController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsReaderController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsSourcesController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsTrackingController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/BrowseController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/SourcePresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/SourceHolder.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/SourceItem.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/BrowseSourceController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/repos/RepoController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/repos/RepoPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/globalsearch/GlobalSearchController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/globalsearch/GlobalSearchPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/util/manga/MangaShortcutManager.kt"
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
