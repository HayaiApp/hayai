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
        # Approved in docs/architecture/j2k-reset.md. Routes novel storage through J2K's authoritative download queue.
        "app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt"
        # Approved in docs/architecture/j2k-reset.md. Restores novel queue items through the same queue-only source adapter.
        "app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadStore.kt"
        # Approved in docs/architecture/j2k-reset.md. Routes enum preferences through Hayai's legacy-safe decoder.
        "app/src/main/java/eu/kanade/tachiyomi/data/preference/PreferencesHelper.kt"
        "app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt"
        # Approved in docs/architecture/j2k-reset.md. Exposes identified loader failures and one reconciled APK catalog.
        "app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt"
        "app/src/main/java/eu/kanade/tachiyomi/extension/model/Extension.kt"
        "app/src/main/java/eu/kanade/tachiyomi/extension/model/LoadResult.kt"
        "app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstallReceiver.kt"
        # Approved in docs/architecture/j2k-reset.md. Defers J2K's install session until unknown-source permission exists.
        "app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstallBroadcast.kt"
        "app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt"
        "app/src/main/java/eu/kanade/tachiyomi/network/JavaScriptEngine.kt"
        # Approved in docs/architecture/j2k-reset.md. Updates only the default HTTP/WebView user-agent identity.
        "app/src/main/java/eu/kanade/tachiyomi/network/NetworkHelper.kt"
        # Approved in docs/architecture/j2k-reset.md. Tracks Mihon's pinned Cloudflare challenge detector and early exit.
        "app/src/main/java/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationReceiver.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/track/TrackManager.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/updater/GithubRelease.kt"
        # Approved in docs/architecture/j2k-reset.md. Applies one Hayai-owned content seed before activity view inflation.
        "app/src/main/java/eu/kanade/tachiyomi/ui/base/activity/BaseActivity.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/base/activity/BaseThemedActivity.kt"
        "app/src/main/java/eu/kanade/tachiyomi/source/Source.kt"
        "app/src/main/java/eu/kanade/tachiyomi/source/NovelSource.kt"
        "app/src/main/java/eu/kanade/tachiyomi/source/SourceManager.kt"
        "app/src/main/java/eu/kanade/tachiyomi/source/model/Page.kt"
        "app/src/main/java/eu/kanade/tachiyomi/source/online/HttpSource.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/extension/ExtensionBottomPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/extension/ExtensionBottomSheet.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/extension/ExtensionHolder.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/extension/ExtensionItem.kt"
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
        # Approved in docs/architecture/j2k-reset.md. Reuses J2K migration sections and delegates novel side-data transfer to Hayai.
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/BaseMigrationPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/SelectionHeader.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/manga/design/MigrationSourceHolder.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/manga/design/MigrationSourceItem.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/manga/design/PreMigrationController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/manga/process/MigrationListController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/migration/manga/process/MigrationProcessAdapter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/util/MangaExtensions.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/more/stats/StatsController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/more/stats/StatsPresenter.kt"
        # Approved in docs/architecture/j2k-reset.md. Hayai product links replace inherited project destinations.
        "app/src/main/java/eu/kanade/tachiyomi/ui/more/AboutController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/more/AboutLinksPreference.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/track/bangumi/BangumiInterceptor.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/MangaBakaApi.kt"
        # Approved in docs/architecture/j2k-reset.md. Adds the source-header hide event to the existing Recents adapter delegate.
        "app/src/main/java/eu/kanade/tachiyomi/ui/recents/RecentMangaAdapter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/recents/RecentsController.kt"
        # Approved in docs/architecture/j2k-reset.md. Reuses J2K's existing Recents header for source sections.
        "app/src/main/java/eu/kanade/tachiyomi/ui/recents/RecentMangaHeaderItem.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/recents/RecentsPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/recents/options/RecentsHistoryView.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/recents/options/RecentsUpdatesView.kt"
        # Approved in docs/architecture/j2k-reset.md. Hosts the Hayai novel viewer/attachment inside the sole J2K reader.
        "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/ChapterLoader.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsAdvancedController.kt"
        # Approved in docs/architecture/j2k-reset.md. Projects the conditional Hayai E-Hentai settings destination.
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsMainController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsAppearanceController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsBrowseController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsDownloadController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsReaderController.kt"
        # Approved in docs/architecture/j2k-reset.md. Registers Hayai novel and conditional E-Hentai settings for J2K search.
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/search/SettingsSearchHelper.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsSourcesController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsTrackingController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/BrowseController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/SourcePresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/SourceHolder.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/SourceItem.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/BrowseSourceController.kt"
        # Approved in docs/architecture/j2k-reset.md. Optional typed rich-result and tag-filter presentation only.
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/BrowseSourceGridHolder.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/BrowseSourceHolder.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/BrowseSourceItem.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/BrowseSourceListHolder.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/BrowseSourcePresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/repos/RepoController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/browse/repos/RepoPresenter.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/globalsearch/GlobalSearchController.kt"
        "app/src/main/java/eu/kanade/tachiyomi/ui/source/globalsearch/GlobalSearchPresenter.kt"
        # Approved in docs/architecture/j2k-reset.md. Hosts Mihon's challenge-help banner over J2K's WebView.
        "app/src/main/java/eu/kanade/tachiyomi/ui/webview/WebViewActivity.kt"
        "app/src/main/java/eu/kanade/tachiyomi/util/manga/MangaShortcutManager.kt"
        # Approved branding-only seams. Product identity notifications use the existing Hayai vector.
        "app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupNotifier.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateNotifier.kt"
        "app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateNotifier.kt"
        "app/src/main/java/eu/kanade/tachiyomi/util/CrashLogUtil.kt"
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
    $approvedProtectedFiles = @(
        # Approved in docs/architecture/j2k-reset.md. This hosts the novel viewer/attachment without changing image branches.
        "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt"
    )
    $protectedChanges = $changed | Where-Object { $_ -in $protectedFiles -and $_ -notin $approvedProtectedFiles }
    if ($violations -or $protectedChanges) {
        Write-Error ((@("Hayai upstream boundary expanded without review:") + $violations + $protectedChanges) -join [Environment]::NewLine)
    }
    $preferencesHelper = Get-Content -Raw "app/src/main/java/eu/kanade/tachiyomi/data/preference/PreferencesHelper.kt"
    if ($preferencesHelper -match 'flowPrefs\.getEnum\s*\(') {
        Write-Error "PreferencesHelper reintroduced FlowPreferences' throwing enum decoder. Use getCompatibleEnum."
    }
    Write-Host "Hayai boundary verified: $($allowedUpstreamKotlin.Count) J2K adapter files allowed; the documented unified ReaderActivity adapter is approved."
}
finally {
    Pop-Location
}
