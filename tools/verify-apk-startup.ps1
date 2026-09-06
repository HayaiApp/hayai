param(
    [Parameter(Mandatory = $true)]
    [string] $Apk,

    [Parameter(Mandatory = $true)]
    [string] $PackageId,

    [string] $Serial = "",

    [ValidateRange(10, 60)]
    [int] $StartupWaitSeconds = 20,

    [string] $DiagnosticsDirectory = "artifacts/apk-startup"
)

$ErrorActionPreference = "Stop"

$resolvedApk = (Resolve-Path -LiteralPath $Apk).Path
if ((Get-Item -LiteralPath $resolvedApk).Length -eq 0) {
    throw "APK is empty: $resolvedApk"
}

$adbPrefix = @()
if ($Serial) {
    $adbPrefix = @("-s", $Serial)
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $output = & adb @adbPrefix @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed:`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

$state = (Invoke-Adb -Arguments @("get-state") | Select-Object -Last 1).Trim()
if ($state -ne "device") {
    throw "Android target is not ready: $state"
}

$install = Invoke-Adb -Arguments @("install", "-r", $resolvedApk)
if (($install -join "`n") -notmatch "Success") {
    throw "APK installation did not report success:`n$($install -join [Environment]::NewLine)"
}

New-Item -ItemType Directory -Path $DiagnosticsDirectory -Force | Out-Null
try {
    Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageId) | Out-Null
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Invoke-Adb -Arguments @("shell", "monkey", "-p", $PackageId, "-c", "android.intent.category.LAUNCHER", "1") |
        Tee-Object -FilePath (Join-Path $DiagnosticsDirectory "launch.txt")
    Start-Sleep -Seconds $StartupWaitSeconds

    $crashLog = Invoke-Adb -Arguments @("logcat", "-d", "-v", "brief", "AndroidRuntime:E", "*:S")
    $crashText = $crashLog -join [Environment]::NewLine
    if ($crashText -match "FATAL EXCEPTION" -and $crashText -match [regex]::Escape($PackageId)) {
        throw "APK crashed during startup:`n$crashText"
    }

    $activities = (Invoke-Adb -Arguments @("shell", "dumpsys", "activity", "activities")) -join [Environment]::NewLine
    $packagePattern = [regex]::Escape($PackageId)
    if ($activities -match "$packagePattern/eu\.kanade\.tachiyomi\.crash\.CrashActivity") {
        throw "Hayai redirected to CrashActivity during startup."
    }

    $mainActivityPattern = "$packagePattern/eu\.kanade\.tachiyomi\.ui\.main\.MainActivity"
    $mainProcess = (Invoke-Adb -Arguments @("shell", "pidof", $PackageId) | Select-Object -Last 1).Trim()
    if (-not $mainProcess -or $activities -notmatch $mainActivityPattern) {
        $relevantActivities = $activities -split "`r?`n" | Where-Object {
            $_ -match "ResumedActivity|topResumedActivity|CrashActivity|$packagePattern"
        } | Select-Object -First 30
        throw "Hayai MainActivity or its main process did not survive startup:`n$($relevantActivities -join [Environment]::NewLine)"
    }

    Write-Output "Signed/minified APK startup verification passed for $PackageId."
} finally {
    & adb @adbPrefix logcat -d -v threadtime 2>&1 | Set-Content (Join-Path $DiagnosticsDirectory "logcat.txt")
    & adb @adbPrefix shell dumpsys activity exit-info $PackageId 2>&1 | Set-Content (Join-Path $DiagnosticsDirectory "exit-info.txt")
    & adb @adbPrefix shell dumpsys activity activities 2>&1 | Set-Content (Join-Path $DiagnosticsDirectory "activities.txt")
}
