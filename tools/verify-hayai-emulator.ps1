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
    Invoke-Adb @("shell", "rm", "-f", $remote) | Out-Null
    Invoke-Adb @("shell", "uiautomator", "dump", $remote) | Out-Null
    $path = Join-Path $script:evidence "$Name.xml"
    Invoke-Adb @("pull", $remote, $path) | Out-Null
    return [xml](Get-Content -Raw -LiteralPath $path)
}
function Find-Node([xml] $Window, [string] $Pattern) {
    $node = $Window.SelectNodes("//node") | Where-Object { $_.text -match $Pattern -or $_.'content-desc' -match $Pattern } | Select-Object -First 1
    if ($null -eq $node) { throw "UI node was not found: $Pattern" }
    return $node
}
function Tap-Node([string] $Pattern, [string] $EvidenceName) {
    $node = Find-Node (Dump-Window $EvidenceName) $Pattern
    if ($node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "UI node has invalid bounds: $Pattern" }
    $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
    Invoke-Adb @("shell", "input", "tap", ([int]$x), ([int]$y)) | Out-Null
    Start-Sleep -Milliseconds 800
}
function Assert-Node([string] $Pattern, [string] $EvidenceName) { [void](Find-Node (Dump-Window $EvidenceName) $Pattern) }
function Dismiss-CompatibilityWarning {
    try {
        $window = Dump-Window "00-compatibility-warning"
    } catch {
        Write-Warning "Compatibility-dialog inspection was unavailable; continuing with app readiness checks. $($_.Exception.Message)"
        return
    }
    $warning = $window.SelectNodes("//node") | Where-Object { $_.text -eq "Android App Compatibility" } | Select-Object -First 1
    if ($warning) {
        Tap-Node "Don't Show Again|OK" "00-compatibility-warning"
    }
}
function Capture([string] $Name) {
    $remote = "/sdcard/$Name.png"
    Invoke-Adb @("shell", "screencap", "-p", $remote) | Out-Null
    Invoke-Adb @("pull", $remote, (Join-Path $script:evidence "$Name.png")) | Out-Null
}
function Export-PrivateDatabase([string] $Destination) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Adb
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    @("-s", $Serial, "exec-out", "run-as", $ApplicationId, "cat", "databases/hayai-j2k.db") | ForEach-Object {
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
        throw "adb private database export failed: $errorText"
    }
    $bytes = [IO.File]::ReadAllBytes($Destination)
    $header = if ($bytes.Length -ge 16) { [Text.Encoding]::ASCII.GetString($bytes, 0, 16) } else { "" }
    if ($bytes.Length -lt 512 -or $header -ne "SQLite format 3`0") {
        Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        throw "The active database export is missing or is not SQLite. $errorText"
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

    Invoke-Adb @("shell", "run-as", $ApplicationId, "am", "start", "-n", "$ApplicationId/dev.ahmedmohamed.hayai.novel.reader.NovelReaderActivity", "--el", "hayai.manga_id", "100", "--el", "hayai.chapter_id", "1000") | Out-Null
    Start-Sleep -Seconds 3
    Assert-Node "Verification Novel" "02-novel-reader"
    Capture "02-novel-reader"
    Invoke-Adb @("shell", "input", "swipe", "300", "700", "650", "700", "1200") | Out-Null
    Start-Sleep -Seconds 1
    Assert-Node "Highlight|Translate|Dictionary|Look up" "03-novel-selection-actions"
    Capture "03-novel-selection-actions"
    Invoke-Adb @("shell", "input", "keyevent", "BACK") | Out-Null
    Tap-Node "Aa" "04-reader-menu-button"
    Tap-Node "Save chapter offline" "05-save-offline"
    Start-Sleep -Seconds 2
    Invoke-Adb @("shell", "am", "force-stop", $ApplicationId) | Out-Null
    Invoke-Adb @("shell", "run-as", $ApplicationId, "am", "start", "-n", "$ApplicationId/dev.ahmedmohamed.hayai.novel.reader.NovelReaderActivity", "--el", "hayai.manga_id", "100", "--el", "hayai.chapter_id", "1000") | Out-Null
    Start-Sleep -Seconds 2
    Assert-Node "Offline" "06-offline-after-restart"
    Capture "06-offline-after-restart"

    Invoke-Adb @("shell", "run-as", $ApplicationId, "am", "start", "-n", "$ApplicationId/dev.ahmedmohamed.hayai.adult.eh.ui.EhSettingsActivity") | Out-Null
    Start-Sleep -Seconds 2
    if ($SkipAuthenticatedEh) {
        Assert-Node "E-Hentai|ExHentai|Log in|credentials" "07-eh-logged-out"
        Capture "07-eh-logged-out"
    } else {
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
    Database-Report "15-final"
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
