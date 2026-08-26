[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $AvdName,
    [Parameter(Mandatory)] [string] $ApkPath,
    [string] $EhSecretsPath,
    [string] $ApplicationId = "dev.ahmedmohamed.hayai.debug",
    [string] $Serial = "emulator-5554",
    [string] $EvidenceRoot = (Join-Path $PSScriptRoot "..\artifacts\emulator-verification"),
    [string] $Adb = "adb",
    [string] $Emulator = "emulator",
    [string] $Sqlite = "sqlite3",
    [switch] $KeepTestApp,
    [switch] $AllowAuthorizedAvd,
    [switch] $SkipAuthenticatedEh,
    [switch] $DryRun
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$script:foregroundIntentArgs = $null
$script:restoringForeground = $false
$productionId = "dev.ahmedmohamed.hayai"
if ($ApplicationId -eq $productionId -or !$ApplicationId.EndsWith(".debug")) { throw "Only the debug test application ID is allowed." }
if (!$AllowAuthorizedAvd -and $AvdName -notmatch '(?i)(hayai.*test|test.*hayai)') {
    throw "The AVD name must explicitly contain both Hayai and Test unless -AllowAuthorizedAvd is supplied."
}
$fixtureRoot = Join-Path $PSScriptRoot "fixtures\hayai-emulator"
$required = @($ApkPath, (Join-Path $fixtureRoot "legacy-v36.sql"), (Join-Path $fixtureRoot "chapter-1.html"), (Join-Path $fixtureRoot "expected-counts.json"))
if (!$SkipAuthenticatedEh) {
    if ([string]::IsNullOrWhiteSpace($EhSecretsPath)) { throw "-EhSecretsPath is required unless -SkipAuthenticatedEh is supplied." }
    $required += $EhSecretsPath
}
$required | ForEach-Object { if (!(Test-Path -LiteralPath $_ -PathType Leaf)) { throw "Required verification input is missing: $_" } }
$secrets = if ($SkipAuthenticatedEh) {
    [pscustomobject]@{
        memberId = ""
        passHash = ""
        igneous = ""
        ehGalleryUrl = "https://e-hentai.org/g/1/hayaitest/"
        exhGalleryUrl = "https://exhentai.org/g/2/hayaitest/"
    }
} else {
    Get-Content -Raw -LiteralPath $EhSecretsPath | ConvertFrom-Json
}
if (!$SkipAuthenticatedEh) {
    @("memberId", "passHash", "igneous", "ehGalleryUrl", "exhGalleryUrl") | ForEach-Object {
        if ([string]::IsNullOrWhiteSpace($secrets.$_)) { throw "The dedicated test account file is missing $_." }
    }
}

function Invoke-Adb([Parameter(Mandatory, Position = 0)] [string[]] $Arguments) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')`n$output" }
    return $output
}
function Wait-Device {
    & $Adb -s $Serial wait-for-device | Out-Null
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        $booted = (& $Adb -s $Serial shell getprop sys.boot_completed 2>$null).Trim()
        if ($booted -eq "1") { return }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "The dedicated test AVD did not finish booting."
}
function Assert-TestAvd {
    $actual = (Invoke-Adb @("emu", "avd", "name") | Select-Object -First 1).Trim()
    if ($actual -ne $AvdName) { throw "Connected emulator is '$actual', expected dedicated AVD '$AvdName'." }
}
function Dump-Window([string] $Name) {
    $remote = "/data/local/tmp/hayai-window.xml"
    $path = Join-Path $script:evidence "$Name.xml"
    $pulled = $false
    for ($attempt = 0; $attempt -lt 5 -and !$pulled; $attempt++) {
        & $Adb -s $Serial shell rm -f $remote 2>$null | Out-Null
        & $Adb -s $Serial shell uiautomator dump --compressed $remote 2>$null | Out-Null
        & $Adb -s $Serial pull $remote $path 2>$null | Out-Null
        $pulled = $LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $path -PathType Leaf)
        if (!$pulled) { Start-Sleep -Milliseconds 500 }
    }
    if (!$pulled) { throw "UI hierarchy was not created at $remote." }
    $window = [xml](Get-Content -Raw -LiteralPath $path)
    $visiblePackage = $window.hierarchy.node.package
    if (
        !$script:restoringForeground -and
        $null -ne $script:foregroundIntentArgs -and
        $visiblePackage -ne $ApplicationId
    ) {
        $script:restoringForeground = $true
        try {
            Invoke-Adb $script:foregroundIntentArgs | Out-Null
            Start-Sleep -Milliseconds 800
            return Dump-Window $Name
        } finally {
            $script:restoringForeground = $false
        }
    }
    return $window
}
function Find-Node([xml] $Window, [string] $Pattern) {
    $node = Find-NodeOptional $Window $Pattern
    if ($null -eq $node) { throw "UI node was not found: $Pattern" }
    return $node
}
function Find-NodeOptional([xml] $Window, [string] $Pattern) {
    return $Window.SelectNodes("//node") | Where-Object { $_.text -match $Pattern -or $_.'content-desc' -match $Pattern } | Select-Object -First 1
}
function Tap-Node([string] $Pattern, [string] $EvidenceName) {
    $node = Find-Node (Dump-Window $EvidenceName) $Pattern
    Tap-WindowNode $node $Pattern
}
function Tap-WindowNode($Node, [string] $Pattern) {
    $node = $Node
    if ($node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "UI node has invalid bounds: $Pattern" }
    $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
    Invoke-Adb @("shell", "input", "tap", ([int]$x), ([int]$y)) | Out-Null
    Start-Sleep -Milliseconds 800
}
function Tap-NodeAfterSwiping([string] $Pattern, [string] $EvidenceName, [int] $MaximumSwipes = 8) {
    for ($attempt = 0; $attempt -le $MaximumSwipes; $attempt++) {
        $window = Dump-Window "$EvidenceName-$attempt"
        $node = $window.SelectNodes("//node") | Where-Object { $_.text -match $Pattern -or $_.'content-desc' -match $Pattern } | Select-Object -First 1
        if ($null -ne $node -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]' -and [int]$Matches[4] -gt 0) {
            $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
            $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
            Invoke-Adb @("shell", "input", "tap", ([int]$x), ([int]$y)) | Out-Null
            Start-Sleep -Milliseconds 800
            return
        }
        Invoke-Adb @("shell", "input", "swipe", "672", "2300", "672", "1150", "500") | Out-Null
        Start-Sleep -Milliseconds 500
    }
    throw "Scrollable UI node was not found: $Pattern"
}
function Assert-Node([string] $Pattern, [string] $EvidenceName) { [void](Find-Node (Dump-Window $EvidenceName) $Pattern) }
function Assert-NodeAfterSwiping([string] $Pattern, [string] $EvidenceName, [int] $MaximumSwipes = 8) {
    for ($attempt = 0; $attempt -le $MaximumSwipes; $attempt++) {
        $window = Dump-Window "$EvidenceName-$attempt"
        if ($null -ne (Find-NodeOptional $window $Pattern)) { return }
        Invoke-Adb @("shell", "input", "swipe", "672", "2300", "672", "1150", "500") | Out-Null
        Start-Sleep -Milliseconds 500
    }
    throw "Scrollable UI node was not found: $Pattern"
}
function Dismiss-CompatibilityWarning {
    try {
        $window = Dump-Window "00-compatibility-warning"
    } catch {
        Write-Warning "Compatibility-dialog inspection was unavailable; continuing with app readiness checks. $($_.Exception.Message)"
        return
    }
    $warning = $window.SelectNodes("//node") | Where-Object { $_.text -eq "Android App Compatibility" } | Select-Object -First 1
    if ($warning) {
        Tap-Node "Don't Show Again" "00-compatibility-warning-dismiss"
    }
}
function Dismiss-ImmersiveModePrompt {
    try {
        $window = Dump-Window "00-immersive-mode-prompt"
        $button = Find-NodeOptional $window "^Got it$"
        if ($button) { Tap-WindowNode $button "Got it" }
    } catch {
        Write-Warning "Immersive-mode prompt inspection was unavailable; continuing with reader readiness checks. $($_.Exception.Message)"
    }
}
function Capture([string] $Name) {
    $remote = "/sdcard/$Name.png"
    Invoke-Adb @("shell", "screencap", "-p", $remote) | Out-Null
    Invoke-Adb @("pull", $remote, (Join-Path $script:evidence "$Name.png")) | Out-Null
}
function Export-PrivateFile([string] $Source, [string] $Destination, [bool] $Required = $true) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Adb
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    @("-s", $Serial, "exec-out", "run-as", $ApplicationId, "cat", $Source) | ForEach-Object {
        [void] $start.ArgumentList.Add($_)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (!$process.Start()) { throw "Unable to start adb database export." }
    $errorRead = $process.StandardError.ReadToEndAsync()
    $stream = [IO.File]::Create($Destination)
    try {
        $process.StandardOutput.BaseStream.CopyTo($stream)
    } finally {
        $stream.Dispose()
    }
    $process.WaitForExit()
    $errorText = $errorRead.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        if (!$Required) { return $false }
        throw "adb private database export failed: $errorText"
    }
    return $true
}
function Export-PrivateDatabase([string] $Destination) {
    [void](Export-PrivateFile "databases/hayai-j2k.db" $Destination)
    [void](Export-PrivateFile "databases/hayai-j2k.db-wal" "$Destination-wal" $false)
    [void](Export-PrivateFile "databases/hayai-j2k.db-shm" "$Destination-shm" $false)
    $bytes = [IO.File]::ReadAllBytes($Destination)
    $header = if ($bytes.Length -ge 16) { [Text.Encoding]::ASCII.GetString($bytes, 0, 16) } else { "" }
    if ($bytes.Length -lt 512 -or $header -ne "SQLite format 3`0") {
        Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        throw "The active database export is missing or is not SQLite."
    }
}
function Wait-ForActiveDatabase([int] $TimeoutSeconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $listing = & $Adb -s $Serial shell run-as $ApplicationId ls -l databases/hayai-j2k.db 2>$null
        if ($LASTEXITCODE -eq 0 -and $listing -match "hayai-j2k\.db") { return }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Hayai did not create its active database within $TimeoutSeconds seconds."
}
function Pull-Database([string] $Name) {
    $local = Join-Path $script:evidence "$Name.db"
    Export-PrivateDatabase $local
    return $local
}
function Database-Report([string] $Name) {
    Invoke-Adb @("shell", "am", "force-stop", $ApplicationId) | Out-Null
    $db = Pull-Database $Name
    $expected = Get-Content -Raw -LiteralPath (Join-Path $fixtureRoot "expected-counts.json") | ConvertFrom-Json
    $report = [ordered]@{}
    $expected.PSObject.Properties | ForEach-Object {
        $actual = [int](& $Sqlite $db "SELECT COUNT(*) FROM $($_.Name);")
        if ($actual -lt [int]$_.Value) { throw "Database count $($_.Name)=$actual, expected at least $($_.Value)." }
        $report[$_.Name] = $actual
    }
    $state = & $Sqlite $db "SELECT status FROM hayai_migration_state WHERE plan_id='hayai-v36-to-j2k-v20';"
    if ($state -ne "complete") { throw "Legacy migration did not complete." }
    $report["migration"] = $state
    $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $script:evidence "$Name-counts.json") -Encoding utf8
}
function GalleryParts([string] $Url) {
    if ($Url -notmatch '^https://(e-hentai\.org|exhentai\.org)/g/([1-9][0-9]*)/([A-Za-z0-9_-]+)/?') { throw "Invalid controlled gallery URL." }
    return @{ Path = "/g/$($Matches[2])/$($Matches[3])/?nw=always"; Gid = $Matches[2]; Token = $Matches[3] }
}
function XmlEscape([string] $Value) { return [Security.SecurityElement]::Escape($Value) }

if ($DryRun) {
    $avds = & $Emulator -list-avds
    if ($AvdName -notin $avds) { throw "Dedicated AVD '$AvdName' is not installed." }
    $mode = if ($SkipAuthenticatedEh) { "unauthenticated" } else { "authenticated" }
    Write-Output "Dry run passed in $mode mode. APK, AVD authorization, application ID, and fixtures are valid."
    exit 0
}

$script:evidence = Join-Path $EvidenceRoot ([DateTime]::UtcNow.ToString("yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $script:evidence -Force | Out-Null
$temporary = Join-Path ([IO.Path]::GetTempPath()) ("hayai-emulator-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $temporary | Out-Null
$startedEmulator = $false
try {
    $connected = & $Adb devices | Select-String "^$([regex]::Escape($Serial))\s+device$"
    if (!$connected) {
        Start-Process -FilePath $Emulator -ArgumentList @("-avd", $AvdName, "-no-snapshot-save", "-no-boot-anim") -WindowStyle Hidden | Out-Null
        $startedEmulator = $true
    }
    Wait-Device
    Assert-TestAvd
    & $Adb -s $Serial uninstall $ApplicationId 2>$null | Out-Null
    Invoke-Adb @("install", $ApkPath) | Out-Null
    $installed = (Invoke-Adb @("shell", "dumpsys", "package", $ApplicationId) | Select-String "debuggable").ToString()
    if (!$installed) { throw "Installed APK is not debuggable." }
    Invoke-Adb @("shell", "appops", "set", $ApplicationId, "MANAGE_EXTERNAL_STORAGE", "allow") | Out-Null
    & $Adb -s $Serial shell pm grant $ApplicationId android.permission.POST_NOTIFICATIONS 2>$null | Out-Null

    $eh = GalleryParts $secrets.ehGalleryUrl
    $exh = GalleryParts $secrets.exhGalleryUrl
    $sqlText = Get-Content -Raw -LiteralPath (Join-Path $fixtureRoot "legacy-v36.sql")
    $sqlText = $sqlText.Replace("{{EH_PATH}}", $eh.Path).Replace("{{EH_GID}}", $eh.Gid).Replace("{{EH_TOKEN}}", $eh.Token)
    $sqlText = $sqlText.Replace("{{EXH_PATH}}", $exh.Path).Replace("{{EXH_GID}}", $exh.Gid).Replace("{{EXH_TOKEN}}", $exh.Token)
    $fixtureSql = Join-Path $temporary "fixture.sql"
    $fixtureDb = Join-Path $temporary "tachiyomi.db"
    Set-Content -LiteralPath $fixtureSql -Value $sqlText -Encoding utf8
    & $Sqlite $fixtureDb ".read '$($fixtureSql.Replace("'", "''"))'"
    if ($LASTEXITCODE -ne 0) { throw "Unable to create sanitized migration fixture." }
    Invoke-Adb @("push", $fixtureDb, "/data/local/tmp/hayai-tachiyomi.db") | Out-Null
    Invoke-Adb @("shell", "run-as", $ApplicationId, "mkdir", "-p", "databases", "shared_prefs") | Out-Null
    Invoke-Adb @("shell", "run-as", $ApplicationId, "cp", "/data/local/tmp/hayai-tachiyomi.db", "databases/tachiyomi.db") | Out-Null

    # Force the first database open to inspect the fixture even if an Android component opened
    # the pristine target database between package install and fixture placement.
    $migrationPrefsFile = Join-Path $temporary "hayai_legacy_migration.xml"
    Set-Content -LiteralPath $migrationPrefsFile -Value '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="retry_requested" value="true" /></map>' -Encoding utf8
    Invoke-Adb @("push", $migrationPrefsFile, "/data/local/tmp/hayai-migration-preferences.xml") | Out-Null
    Invoke-Adb @("shell", "run-as", $ApplicationId, "cp", "/data/local/tmp/hayai-migration-preferences.xml", "shared_prefs/hayai_legacy_migration.xml") | Out-Null

    if (!$SkipAuthenticatedEh) {
        $prefs = "<?xml version=`"1.0`" encoding=`"utf-8`" standalone=`"yes`" ?><map><string name=`"__PRIVATE_eh_ipb_member_id`">$(XmlEscape $secrets.memberId)</string><string name=`"__PRIVATE_eh_ipb_pass_hash`">$(XmlEscape $secrets.passHash)</string><string name=`"__PRIVATE_eh_igneous`">$(XmlEscape $secrets.igneous)</string></map>"
        $prefsFile = Join-Path $temporary "$ApplicationId`_preferences.xml"
        Set-Content -LiteralPath $prefsFile -Value $prefs -Encoding utf8
        Invoke-Adb @("push", $prefsFile, "/data/local/tmp/hayai-preferences.xml") | Out-Null
        Invoke-Adb @("shell", "run-as", $ApplicationId, "cp", "/data/local/tmp/hayai-preferences.xml", "shared_prefs/$ApplicationId`_preferences.xml") | Out-Null
    }
    Invoke-Adb @("shell", "mkdir", "-p", "'/sdcard/Hayai/localnovels/Verification Novel'") | Out-Null
    Invoke-Adb @("push", (Join-Path $fixtureRoot "chapter-1.html"), "/sdcard/Hayai/localnovels/Verification Novel/chapter-1.html") | Out-Null

    Invoke-Adb @("logcat", "-c") | Out-Null
    Invoke-Adb @("shell", "monkey", "-p", $ApplicationId, "1") | Out-Null
    Start-Sleep -Seconds 2
    Dismiss-CompatibilityWarning
    Wait-ForActiveDatabase
    Start-Sleep -Seconds 2
    Capture "01-migrated-library"
    Database-Report "01-migrated"

    Invoke-Adb @("shell", "am", "start", "-S", "-W", "-n", "$ApplicationId/eu.kanade.tachiyomi.ui.reader.ReaderActivity", "--el", "manga", "100", "--el", "chapter", "1000") | Out-Null
    Start-Sleep -Seconds 2
    Dismiss-CompatibilityWarning
    Dismiss-ImmersiveModePrompt
    $script:foregroundIntentArgs = @("shell", "am", "start", "--activity-reorder-to-front", "-n", "$ApplicationId/eu.kanade.tachiyomi.ui.reader.ReaderActivity", "--el", "manga", "100", "--el", "chapter", "1000")
    $readerWindow = Dump-Window "02-novel-reader-actions"
    if ($null -eq (Find-NodeOptional $readerWindow "Verification Novel")) {
        Invoke-Adb @("shell", "input", "tap", "672", "1496") | Out-Null
        Start-Sleep -Seconds 1
        $readerWindow = Dump-Window "02-novel-reader-actions"
    }
    [void](Find-Node $readerWindow "Verification Novel")
    [void](Find-Node $readerWindow "Read aloud")
    $viewerContainerNode = $readerWindow.SelectNodes("//node") | Where-Object { $_.'resource-id' -match ':(id/)?reader_layout$' } | Select-Object -First 1
    $contentNode = $readerWindow.SelectNodes("//node") | Where-Object { $_.class -in @('android.widget.EditText', 'android.webkit.WebView') } | Select-Object -First 1
    if ($null -eq $viewerContainerNode -or $null -eq $contentNode) { throw "The real J2K reader layout or novel content was missing from the hierarchy." }
    if ($viewerContainerNode.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "J2K reader-layout bounds were invalid." }
    $viewerBounds = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    if ($contentNode.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "Novel content bounds were invalid." }
    $contentBounds = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    if (
        $contentBounds[0] -lt $viewerBounds[0] -or
        $contentBounds[1] -lt $viewerBounds[1] -or
        $contentBounds[2] -gt $viewerBounds[2] -or
        $contentBounds[3] -gt $viewerBounds[3] -or
        $contentBounds[2] -le $contentBounds[0] -or
        $contentBounds[3] -le $contentBounds[1]
    ) {
        throw "Novel content bounds [$($contentBounds -join ',')] were outside the real J2K reader layout [$($viewerBounds -join ',')]."
    }
    $j2kProgress = $readerWindow.SelectNodes("//node") | Where-Object {
        $_.'resource-id' -match ':(id/)?(page_number|left_page_text|right_page_text)$' -and ($_.text -replace '\s', '') -match '^\d{1,3}%$'
    } | Select-Object -First 1
    if ($null -eq $j2kProgress) { throw "The real J2K progress surface did not expose continuous novel progress as a percentage." }
    Capture "02-novel-reader"
    Tap-WindowNode (Find-Node $readerWindow "^Read aloud$") "Read aloud"
    $ttsWindow = $null
    for ($attempt = 0; $attempt -lt 8 -and $null -eq $ttsWindow; $attempt++) {
        Start-Sleep -Seconds 1
        $candidate = Dump-Window "02b-tts-controls-$attempt"
        if ($null -ne (Find-NodeOptional $candidate "^Read from viewport$")) { $ttsWindow = $candidate }
    }
    if ($null -eq $ttsWindow) { throw "The J2K action row did not enter active TTS mode." }
    @("Read from viewport", "Previous paragraph", "Next paragraph", "Stop reading aloud") | ForEach-Object {
        [void](Find-Node $ttsWindow "^$([regex]::Escape($_))$")
    }
    Capture "02b-tts-controls"
    Tap-WindowNode (Find-Node $ttsWindow "^Stop reading aloud$") "Stop reading aloud"
    $readerWindow = Dump-Window "02-novel-reader-settings-action"
    if ($null -eq (Find-NodeOptional $readerWindow "^Reading$")) {
        $settingsAction = Find-NodeOptional $readerWindow "^(Reader settings|Display options)$"
        if ($null -eq $settingsAction) { throw "Neither the reader settings action nor its sheet was visible." }
        Tap-WindowNode $settingsAction "Reader settings"
    }
    $settingsWindow = Dump-Window "03-reader-settings-tabs"
    @("Reading", "Appearance", "Controls", "TTS", "Advanced") | ForEach-Object { [void](Find-Node $settingsWindow "^$([regex]::Escape($_))$") }
    [void](Find-Node $settingsWindow "Reading engine")
    Capture "03-reader-settings-reading"
    foreach ($tab in @(
        @{ Name = "Appearance"; Evidence = "04-reader-settings-appearance"; Expected = "Typography" },
        @{ Name = "Controls"; Evidence = "05-reader-settings-controls"; Expected = "Page" },
        @{ Name = "TTS"; Evidence = "06-reader-settings-tts"; Expected = "Read aloud" }
    )) {
        Tap-Node "^$($tab.Name)$" "$($tab.Evidence)-tap"
        Assert-Node "$($tab.Expected)" "$($tab.Evidence)-content"
        Capture $tab.Evidence
    }
    $settingsWindow = Dump-Window "07-reader-settings-tabs"
    Tap-WindowNode (Find-Node $settingsWindow "^Advanced$") "Advanced"
    $advancedWindow = Dump-Window "07-reader-advanced"
    [void](Find-Node $advancedWindow "Content")
    [void](Find-Node $advancedWindow "Use EPUB styles")
    [void](Find-Node $advancedWindow "^Previous chapter$")
    Capture "07-reader-settings-advanced"
    Tap-NodeAfterSwiping "Save or remove offline copy" "08-save-offline"
    Start-Sleep -Seconds 3
    Invoke-Adb @("shell", "am", "force-stop", $ApplicationId) | Out-Null
    Invoke-Adb @("shell", "am", "start", "-S", "-W", "-n", "$ApplicationId/eu.kanade.tachiyomi.ui.reader.ReaderActivity", "--el", "manga", "100", "--el", "chapter", "1000") | Out-Null
    Start-Sleep -Seconds 2
    Dismiss-CompatibilityWarning
    Dismiss-ImmersiveModePrompt
    $script:foregroundIntentArgs = @("shell", "am", "start", "--activity-reorder-to-front", "-n", "$ApplicationId/eu.kanade.tachiyomi.ui.reader.ReaderActivity", "--el", "manga", "100", "--el", "chapter", "1000")
    Invoke-Adb @("shell", "input", "tap", "672", "1496") | Out-Null
    Start-Sleep -Seconds 1
    Assert-Node "Offline" "09-offline-after-restart"
    Capture "09-offline-after-restart"

    Invoke-Adb @("shell", "am", "force-stop", $ApplicationId) | Out-Null
    Invoke-Adb @("shell", "am", "start", "-n", "$ApplicationId/eu.kanade.tachiyomi.ui.main.MainActivity") | Out-Null
    $script:foregroundIntentArgs = @("shell", "am", "start", "--activity-reorder-to-front", "-n", "$ApplicationId/eu.kanade.tachiyomi.ui.main.MainActivity")
    Start-Sleep -Seconds 2
    Tap-Node "^More$" "07-eh-more"
    Tap-Node "^Settings$" "07-eh-settings"
    Tap-NodeAfterSwiping "^Reader$" "07-reader-settings"
    Tap-NodeAfterSwiping "^Novel reader$" "07-novel-reader-settings"
    Assert-NodeAfterSwiping "Rendering mode" "07-novel-reader-settings-open"
    Capture "07-novel-reader-settings"
    Invoke-Adb @("shell", "input", "keyevent", "4") | Out-Null
    Start-Sleep -Milliseconds 800
    Invoke-Adb @("shell", "input", "keyevent", "4") | Out-Null
    Start-Sleep -Milliseconds 800
    if (!$SkipAuthenticatedEh) {
        Tap-NodeAfterSwiping "^Advanced$" "07-eh-advanced"
        Tap-NodeAfterSwiping "^E-Hentai and ExHentai$" "07-eh-account"
        Tap-Node "Recheck current credentials" "07-eh-recheck"
        Start-Sleep -Seconds 5
        Assert-Node "Verified|verified" "08-eh-verified"
        Tap-Node "Apply to E-Hentai and ExHentai" "09-eh-settings-upload"
        Tap-Node "Preview favorites sync" "10-eh-favorites-preview"
        Tap-Node "Start or resume favorites sync" "11-eh-favorites-sync"
        Tap-Node "Run gallery updater now" "12-eh-updater"
        Start-Sleep -Seconds 10
        Capture "12-eh-updater"

        Invoke-Adb @("shell", "monkey", "-p", $ApplicationId, "1") | Out-Null
        Start-Sleep -Seconds 2
        Tap-Node "Verification E-Hentai Gallery" "13-eh-details"
        Start-Sleep -Seconds 5
        Assert-Node "Gallery page 1" "14-eh-previews"
        Capture "14-eh-previews"
    }

    Invoke-Adb @("shell", "am", "force-stop", $ApplicationId) | Out-Null
    Invoke-Adb @("shell", "am", "start", "-n", "$ApplicationId/eu.kanade.tachiyomi.ui.main.MainActivity") | Out-Null
    $script:foregroundIntentArgs = @("shell", "am", "start", "--activity-reorder-to-front", "-n", "$ApplicationId/eu.kanade.tachiyomi.ui.main.MainActivity")
    Start-Sleep -Seconds 3
    Dismiss-CompatibilityWarning
    $mainWindow = Dump-Window "15-main-before-browse"
    if ($null -eq (Find-NodeOptional $mainWindow "^Search sources$")) {
        Tap-WindowNode (Find-Node $mainWindow "^Browse$") "Browse"
        Start-Sleep -Seconds 3
    }
    $browseWindow = Dump-Window "16-browse"
    [void](Find-Node $browseWindow "^Search sources$")
    [void](Find-Node $browseWindow "^Manga$")
    [void](Find-Node $browseWindow "^Novels$")
    [void](Find-Node $browseWindow "^E-Hentai$")
    [void](Find-Node $browseWindow "^Migration$")
    Capture "16-browse"

    Database-Report "17-final"
    Invoke-Adb @("logcat", "-d", "-v", "threadtime") | Set-Content -LiteralPath (Join-Path $script:evidence "logcat.txt") -Encoding utf8
    $fatal = Get-Content -LiteralPath (Join-Path $script:evidence "logcat.txt") | Select-String "FATAL EXCEPTION|AndroidRuntime: Process: $ApplicationId"
    if ($fatal) { throw "The verification run captured an application crash. See logcat.txt." }
    Write-Output "Hayai emulator verification passed. Evidence: $script:evidence"
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
    if (!$KeepTestApp) {
        & $Adb -s $Serial shell am force-stop $ApplicationId 2>$null | Out-Null
        & $Adb -s $Serial uninstall $ApplicationId 2>$null | Out-Null
        & $Adb -s $Serial shell rm -rf "'/sdcard/Hayai/localnovels/Verification Novel'" 2>$null | Out-Null
    }
    if ($startedEmulator) { & $Adb -s $Serial emu kill 2>$null | Out-Null }
}
