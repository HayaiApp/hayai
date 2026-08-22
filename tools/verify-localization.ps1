[CmdletBinding()]
param(
    [switch] $WriteCoverage,
    [switch] $WriteUpstreamBaseline,
    [switch] $WriteHayaiLiteralBaseline,
    [string] $UpstreamRef = "j2k/master"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sourceRoot = Join-Path $repositoryRoot "app\src\main\java"
$resourceRoot = Join-Path $repositoryRoot "app\src\main\res"
$hayaiSourceRoot = Join-Path $sourceRoot "dev\ahmedmohamed\hayai"
$upstreamBaselinePath = Join-Path $PSScriptRoot "localization\upstream-hardcoded-ui.txt"
$hayaiLiteralBaselinePath = Join-Path $PSScriptRoot "localization\hayai-nonlocalizable-literals.txt"
$coveragePath = Join-Path $repositoryRoot "docs\development\localization-coverage.md"

function Relative-Path([string] $Path) {
    return [IO.Path]::GetRelativePath($repositoryRoot, $Path).Replace("\", "/")
}

function Normalize-Line([string] $Line) {
    return ($Line.Trim() -replace '\s+', ' ')
}

function Find-CodeViolations(
    [string] $Path,
    [string[]] $Lines,
    [bool] $Owned
) {
    $propertyPattern = '(?x)(?:text|hint|contentDescription|tooltipText|title|subtitle|summary)\s*=\s*"(?!")'
    $callPattern = '(?x)(?:toast|setTitle|setMessage|setText|setHint|setPositiveButton|setNegativeButton|setNeutralButton|setContentDescription)\s*\(\s*"(?!")'
    $notificationPattern = '(?x)(?:setContentTitle|setContentText|setSubText|setTicker)\s*\(\s*"(?!")'
    $notificationActionPattern = '(?x)\.addAction\([^,\r\n]+,\s*"(?!")'
    $filterPattern = '(?x)Filter\.(?:Select|CheckBox|Group|Text|Header|Separator|Sort)(?:<[^>]+>)?\s*\(\s*"(?!")'
    $menuPattern = '(?x)(?:menu|Menu)\.add\([^,\r\n]+,[^,\r\n]+,[^,\r\n]+,\s*"(?!")'
    for ($index = 0; $index -lt $Lines.Count; $index++) {
        $line = $Lines[$index]
        $ignored = $line -cmatch 'localization-ignore:' -or ($index -gt 0 -and $Lines[$index - 1] -cmatch 'localization-ignore:')
        if (
            !$ignored -and
            (
                $line -cmatch $propertyPattern -or
                $line -cmatch $callPattern -or
                $line -cmatch $notificationPattern -or
                $line -cmatch $notificationActionPattern -or
                $line -cmatch $filterPattern -or
                $line -cmatch $menuPattern
            )
        ) {
            [pscustomobject]@{
                Path = $Path
                Line = $index + 1
                Code = Normalize-Line $line
                Owned = $Owned
            }
        }
    }
}

function Find-XmlViolations(
    [string] $Path,
    [string[]] $Lines,
    [bool] $Owned
) {
    for ($index = 0; $index -lt $Lines.Count; $index++) {
        $line = $Lines[$index]
        if (
            $line -cmatch 'android:(?:text|hint|contentDescription|title|summary)="(?!@|\?|%|$)[^"]+"' -or
            $line -cmatch 'tools:ignore="[^"]*HardcodedText[^"]*"'
        ) {
            [pscustomobject]@{
                Path = $Path
                Line = $index + 1
                Code = Normalize-Line $line
                Owned = $Owned
            }
        }
    }
}

function Read-StringKeys(
    [string] $Directory,
    [string] $Prefix = "",
    [bool] $ExcludeNonTranslatable = $false
) {
    $keys = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    Get-ChildItem -LiteralPath $Directory -Filter "*.xml" -File | ForEach-Object {
        try {
            $document = [xml](Get-Content -Raw -LiteralPath $_.FullName)
        } catch {
            throw "Invalid resource XML: $($_.FullName)`n$($_.Exception.Message)"
        }
        @($document.SelectNodes("/resources/string | /resources/plurals | /resources/string-array")) | ForEach-Object {
            $name = [string]$_.name
            $isTranslatable = $_.GetAttribute("translatable") -ne "false"
            if ($name.StartsWith($Prefix, [StringComparison]::Ordinal) -and (!$ExcludeNonTranslatable -or $isTranslatable)) {
                if (!$keys.Add($name)) {
                    throw "Duplicate resource key $name under $Directory."
                }
            }
        }
    }
    return ,$keys
}

function Read-FormatSignatures(
    [string] $Directory,
    [string] $Prefix = "",
    [bool] $ExcludeNonTranslatable = $false
) {
    $signatures = @{}
    Get-ChildItem -LiteralPath $Directory -Filter "*.xml" -File | ForEach-Object {
        $document = [xml](Get-Content -Raw -LiteralPath $_.FullName)
        @($document.SelectNodes("/resources/string | /resources/plurals | /resources/string-array")) | ForEach-Object {
            $name = [string]$_.name
            $isTranslatable = $_.GetAttribute("translatable") -ne "false"
            if ($name.StartsWith($Prefix, [StringComparison]::Ordinal) -and (!$ExcludeNonTranslatable -or $isTranslatable)) {
                $matches = if ($_.GetAttribute("formatted") -eq "false") {
                    @()
                } else {
                    @([regex]::Matches([string]$_.InnerText, '(?<!%)%(?!%)(?:\d+\$)?[a-zA-Z]') | ForEach-Object Value | Sort-Object -Unique)
                }
                $signatures[$name] = $matches -join ","
            }
        }
    }
    return $signatures
}

function Find-HayaiLiteralEntries {
    Get-ChildItem -LiteralPath $hayaiSourceRoot -Recurse -File | Where-Object { $_.Extension -in ".kt", ".java" } | ForEach-Object {
        $path = Relative-Path $_.FullName
        Get-Content -LiteralPath $_.FullName | ForEach-Object {
            $trimmed = $_.Trim()
            if (
                $trimmed -and
                !$trimmed.StartsWith("//") -and
                !$trimmed.StartsWith("/*") -and
                !$trimmed.StartsWith("*") -and
                $trimmed -cmatch '(?<!\\)"'
            ) {
                "$path`t$trimmed"
            }
        }
    }
}

$violations = [Collections.Generic.List[object]]::new()

Get-ChildItem -LiteralPath $sourceRoot -Recurse -File | Where-Object { $_.Extension -in ".kt", ".java" } | ForEach-Object {
    $path = Relative-Path $_.FullName
    $owned = $_.FullName.StartsWith($hayaiSourceRoot, [StringComparison]::OrdinalIgnoreCase)
    Find-CodeViolations $path @(Get-Content -LiteralPath $_.FullName) $owned | ForEach-Object { $violations.Add($_) }
}

Get-ChildItem -LiteralPath $resourceRoot -Recurse -Filter "*.xml" -File | Where-Object {
    $_.Directory.Name -ne "values" -and !$_.Directory.Name.StartsWith("values-")
} | ForEach-Object {
    Find-XmlViolations (Relative-Path $_.FullName) @(Get-Content -LiteralPath $_.FullName) $false | ForEach-Object { $violations.Add($_) }
}

if ($WriteUpstreamBaseline) {
    $commit = (& git rev-parse $UpstreamRef).Trim()
    if ($LASTEXITCODE -ne 0 -or !$commit) {
        throw "Unable to resolve upstream ref $UpstreamRef."
    }
    $entries = [Collections.Generic.List[string]]::new()
    $codeCandidates = @(& git grep -n -I -E '(text|hint|contentDescription|tooltipText|title|subtitle|summary)[[:space:]]*=|toast\(|set(Title|Message|Text|Hint|PositiveButton|NegativeButton|NeutralButton|ContentDescription|ContentTitle|ContentText|SubText|Ticker)\(|addAction\(|Filter\.(Select|CheckBox|Group|Text|Header|Separator|Sort)|[mM]enu\.add\(' $UpstreamRef -- 'app/src/main/java/*.kt' 'app/src/main/java/*.java')
    $xmlCandidates = @(& git grep -n -I -E 'android:(text|hint|contentDescription|title|summary)=|tools:ignore="[^"]*HardcodedText' $UpstreamRef -- 'app/src/main/res/*.xml')
    @($codeCandidates + $xmlCandidates) | ForEach-Object {
        if ($_ -match '^[^:]+:(app/[^:]+):([0-9]+):(.*)$') {
            $path = $Matches[1]
            $line = $Matches[3]
            if ($path -match '\.(?:kt|java)$') {
                Find-CodeViolations $path @($line) $false | ForEach-Object { $entries.Add("$($_.Path)`t$($_.Code)") }
            } else {
                Find-XmlViolations $path @($line) $false | ForEach-Object { $entries.Add("$($_.Path)`t$($_.Code)") }
            }
        }
    }
    $header = @(
        "# J2K-owned hardcoded UI literals present at $commit.",
        "# Regenerate only while rebasing onto a reviewed J2K baseline:",
        "# .\tools\verify-localization.ps1 -WriteUpstreamBaseline -UpstreamRef j2k/master"
    )
    Set-Content -LiteralPath $upstreamBaselinePath -Value @($header + ($entries | Sort-Object -Unique)) -Encoding utf8NoBOM
}

$baseline = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
if (Test-Path -LiteralPath $upstreamBaselinePath -PathType Leaf) {
    Get-Content -LiteralPath $upstreamBaselinePath | Where-Object { $_ -and !$_.StartsWith("#") } | ForEach-Object { [void]$baseline.Add($_) }
}

$newViolations = @($violations | Where-Object { $_.Owned -or !$baseline.Contains("$($_.Path)`t$($_.Code)") })
if ($newViolations) {
    $formatted = $newViolations | ForEach-Object { "$($_.Path):$($_.Line): $($_.Code)" }
    throw "Hardcoded user-visible text was found. Move it to an Android string resource or add a narrow localization-ignore reason for a non-UI literal.`n$($formatted -join "`n")"
}

$hayaiLiteralEntries = @(Find-HayaiLiteralEntries | Sort-Object -Unique)
if ($WriteHayaiLiteralBaseline) {
    $literalHeader = @(
        "# Reviewed nonlocalizable Kotlin and Java literals under dev.ahmedmohamed.hayai.",
        "# Entries cover protocol values, persistence keys, selectors, source data, and developer diagnostics.",
        "# Regenerate only after every new line has been classified:",
        "# .\tools\verify-localization.ps1 -WriteHayaiLiteralBaseline"
    )
    Set-Content -LiteralPath $hayaiLiteralBaselinePath -Value @($literalHeader + $hayaiLiteralEntries) -Encoding utf8NoBOM
}
if (!(Test-Path -LiteralPath $hayaiLiteralBaselinePath -PathType Leaf)) {
    throw "The reviewed Hayai literal baseline is missing. Run the verifier with -WriteHayaiLiteralBaseline after auditing every remaining literal."
}
$hayaiLiteralBaseline = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
Get-Content -LiteralPath $hayaiLiteralBaselinePath | Where-Object { $_ -and !$_.StartsWith("#") } | ForEach-Object {
    [void]$hayaiLiteralBaseline.Add($_)
}
$newHayaiLiterals = @($hayaiLiteralEntries | Where-Object { !$hayaiLiteralBaseline.Contains($_) })
if ($newHayaiLiterals) {
    throw "New Hayai string literals require localization review. Move user-visible text to resources. Add only protocol values, source data, persistence keys, selectors, or developer diagnostics to the reviewed baseline.`n$($newHayaiLiterals -join "`n")"
}

$defaultKeys = Read-StringKeys (Join-Path $resourceRoot "values") "hayai_" $true
$defaultFormats = Read-FormatSignatures (Join-Path $resourceRoot "values") "hayai_" $true
$localeDirectories = Get-ChildItem -LiteralPath $resourceRoot -Directory | Where-Object {
    $_.Name -match '^values-' -and
    $_.Name -notmatch '^values-(?:night|v\d|sw\d)' -and
    (Get-ChildItem -LiteralPath $_.FullName -Filter "*.xml" -File | Select-Object -First 1)
} | Sort-Object Name

$coverage = foreach ($directory in $localeDirectories) {
    $localeKeys = Read-StringKeys $directory.FullName "hayai_"
    $localeFormats = Read-FormatSignatures $directory.FullName "hayai_"
    foreach ($key in $localeKeys) {
        if ($defaultFormats.ContainsKey($key) -and $defaultFormats[$key] -cne $localeFormats[$key]) {
            throw "Format placeholders for $key in $($directory.Name) do not match the default resource. Expected '$($defaultFormats[$key])' and found '$($localeFormats[$key])'."
        }
    }
    $translated = @($defaultKeys | Where-Object { $localeKeys.Contains($_) }).Count
    [pscustomobject]@{
        Locale = $directory.Name.Substring("values-".Length)
        Translated = $translated
        Total = $defaultKeys.Count
        Percent = if ($defaultKeys.Count -eq 0) {
            "100.0"
        } else {
            (100.0 * $translated / $defaultKeys.Count).ToString("0.0", [Globalization.CultureInfo]::InvariantCulture)
        }
    }
}

if ($WriteCoverage) {
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add("# Hayai localization coverage")
    $lines.Add("")
    $lines.Add("Generated by ``tools/verify-localization.ps1 -WriteCoverage``. Android uses the default English resource when a Hayai key is missing from a locale.")
    $lines.Add("")
    $lines.Add("| Locale | Translated Hayai strings | Coverage |")
    $lines.Add("|---|---:|---:|")
    foreach ($item in $coverage) {
        $lines.Add("| ``$($item.Locale)`` | $($item.Translated) / $($item.Total) | $($item.Percent)% |")
    }
    Set-Content -LiteralPath $coveragePath -Value $lines -Encoding utf8NoBOM
}

$translatedLocales = @($coverage | Where-Object { $_.Translated -gt 0 }).Count
Write-Output "Localization verified: $($defaultKeys.Count) Hayai keys, $($localeDirectories.Count) supported locales, $translatedLocales locales with Hayai translations, and no new hardcoded UI literals."
