param(
    [string] $Serial = "emulator-5554",
    [string] $PackageId = "dev.ahmedmohamed.hayai.nightly"
)

$ErrorActionPreference = "Stop"
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("hayai-navigation-tabs-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null

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

function Read-UiNodes {
    $captureId = [guid]::NewGuid().ToString("N")
    $remote = "/sdcard/hayai-navigation-tabs-$captureId.xml"
    $local = Join-Path $temporaryRoot "$captureId.xml"
    try {
        for ($attempt = 1; $attempt -le 5; $attempt++) {
            try {
                Invoke-Adb -Arguments @("shell", "uiautomator", "dump", $remote) | Out-Null
                Invoke-Adb -Arguments @("pull", $remote, $local) | Out-Null
                break
            } catch {
                if ($attempt -eq 5) {
                    throw
                }
                Start-Sleep -Milliseconds 500
            }
        }
        $document = [xml](Get-Content -Raw -LiteralPath $local)
        return @($document.SelectNodes("//node") | ForEach-Object {
            $match = [regex]::Match($_.bounds, "^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$")
            if ($match.Success) {
                [pscustomobject]@{
                    Text = $_.text
                    ResourceId = $_.GetAttribute("resource-id")
                    Left = [int]$match.Groups[1].Value
                    Top = [int]$match.Groups[2].Value
                    Right = [int]$match.Groups[3].Value
                    Bottom = [int]$match.Groups[4].Value
                }
            }
        })
    } finally {
        Invoke-Adb -Arguments @("shell", "rm", "-f", $remote) | Out-Null
    }
}

function Open-NavigationDestination {
    param([Parameter(Mandatory = $true)][string] $Label)

    $node = Read-UiNodes | Where-Object {
        $_.Text -eq $Label -and $_.ResourceId -like "$PackageId`:id/navigation_bar_item_*_label_view"
    } | Select-Object -First 1
    if (-not $node) {
        throw "Navigation destination '$Label' is not visible."
    }

    $x = [math]::Floor(($node.Left + $node.Right) / 2)
    $y = [math]::Floor(($node.Top + $node.Bottom) / 2)
    Invoke-Adb -Arguments @("shell", "input", "tap", "$x", "$y") | Out-Null

    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 250
        $destinationVisible = Read-UiNodes | Where-Object {
            $_.Text -eq $Label -and $_.ResourceId -like "$PackageId`:id/toolbar_title"
        } | Select-Object -First 1
    } until ($destinationVisible -or [DateTime]::UtcNow -ge $deadline)

    if (-not $destinationVisible) {
        throw "Navigation destination '$Label' did not finish rendering."
    }
}

function Assert-TopTabs {
    param(
        [Parameter(Mandatory = $true)][string] $Destination,
        [Parameter(Mandatory = $true)][string[]] $Labels
    )

    $nodes = Read-UiNodes
    $screenBottom = ($nodes | Measure-Object -Property Bottom -Maximum).Maximum
    $topHalf = $nodes | Where-Object { $_.Top -lt ($screenBottom / 2) }
    $missing = @($Labels | Where-Object { $_ -notin $topHalf.Text })
    if ($missing) {
        $visible = @($topHalf | Where-Object { $_.Text } | Select-Object -ExpandProperty Text -Unique)
        throw "$Destination tabs missing after navigation: $($missing -join ', '). Visible top-half text: $($visible -join ' | ')"
    }
}

function Assert-TopTabsAbsent {
    param(
        [Parameter(Mandatory = $true)][string] $Destination,
        [Parameter(Mandatory = $true)][string[]] $Labels
    )

    $nodes = Read-UiNodes
    $screenBottom = ($nodes | Measure-Object -Property Bottom -Maximum).Maximum
    $topHalf = $nodes | Where-Object { $_.Top -lt ($screenBottom / 2) }
    $stale = @($Labels | Where-Object { $_ -in $topHalf.Text })
    if ($stale) {
        throw "$Destination retained stale tabs: $($stale -join ', ')."
    }
}

try {
    $state = (Invoke-Adb -Arguments @("get-state") | Select-Object -Last 1).Trim()
    if ($state -ne "device") {
        throw "Android target is not ready: $state"
    }

    Open-NavigationDestination -Label "Library"
    Open-NavigationDestination -Label "Browse"
    Assert-TopTabs -Destination "Browse" -Labels @("Manga", "Novels")
    Assert-TopTabsAbsent -Destination "Browse" -Labels @("Grouped", "History", "Updates")
    Open-NavigationDestination -Label "Recents"
    Assert-TopTabs -Destination "Recents" -Labels @("Grouped", "All", "History", "Updates")
    Assert-TopTabsAbsent -Destination "Recents" -Labels @("Manga", "Novels")
    Open-NavigationDestination -Label "Browse"
    Assert-TopTabs -Destination "Browse" -Labels @("Manga", "Novels")
    Assert-TopTabsAbsent -Destination "Browse" -Labels @("Grouped", "History", "Updates")
    Open-NavigationDestination -Label "Recents"
    Assert-TopTabs -Destination "Recents" -Labels @("Grouped", "All", "History", "Updates")
    Assert-TopTabsAbsent -Destination "Recents" -Labels @("Manga", "Novels")

    Write-Output "Browse and Recents tabs remain correct across repeated direct navigation."
} finally {
    $resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
    $resolvedSystemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolvedTemporaryRoot.StartsWith($resolvedSystemTemp, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}
