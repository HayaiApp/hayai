[CmdletBinding()]
param(
    [string] $SyRef = "sy/master"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$resourceRoot = Join-Path $repositoryRoot "app\src\main\res"
$generatedFileName = "strings_hayai_sy.xml"
$mapping = [ordered]@{
    hayai_eh_use_original_images = "use_original_images"
    hayai_eh_tag_filtering_threshold = "tag_filtering_threshold"
    hayai_eh_tag_watching_threshold = "tag_watching_threshhold"
    hayai_eh_watched_tags_web_title = "watched_tags_exh"
    hayai_eh_category_non_h = "non_h"
    hayai_eh_category_image_set = "image_set"
    hayai_eh_category_asian_porn = "asian_porn"
    hayai_eh_language_filtering = "language_filtering"
    hayai_eh_custom_igneous = "custom_igneous_cookie"
    hayai_custom_source_field_base_url = "base_url"
    hayai_api_key = "pref_sync_api_key"
    hayai_page_previews = "page_previews"
    hayai_more_previews = "more_previews"
    hayai_clear_preview_cache = "pref_clear_page_preview_cache"
    hayai_source_metadata_base_url = "base_url"
    hayai_source_metadata_path = "path"
    hayai_source_metadata_thumbnail_url = "thumbnail_url"
    hayai_source_metadata_token = "token"
    hayai_source_metadata_url = "url"
    hayai_source_metadata_is_exhentai = "is_exhentai_gallery"
    hayai_source_metadata_parent = "parent"
    hayai_source_metadata_translated = "translated"
    hayai_source_type_doujinshi = "doujinshi"
    hayai_source_type_artist_cg = "artist_cg"
    hayai_source_type_game_cg = "game_cg"
    hayai_source_type_western = "western"
    hayai_source_type_non_h = "non_h"
    hayai_source_type_image_set = "image_set"
    hayai_source_type_cosplay = "cosplay"
    hayai_source_type_asian_porn = "asian_porn"
    hayai_source_type_misc = "misc"
}

function Read-GitXml([string] $Path) {
    $content = @(& git show "${SyRef}:$Path")
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    return [xml]($content -join "`n")
}

function String-Map([xml] $Document) {
    $values = @{}
    @($Document.SelectNodes("/resources/string")) | ForEach-Object {
        $values[[string]$_.name] = [string]$_.InnerText
    }
    return $values
}

function Escape-Xml([string] $Value) {
    return [Security.SecurityElement]::Escape($Value)
}

$commit = (& git rev-parse $SyRef).Trim()
if ($LASTEXITCODE -ne 0 -or !$commit) {
    throw "Unable to resolve SY ref $SyRef."
}
$availableLocales = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
@(& git ls-tree -r --name-only $SyRef -- i18n-sy/src/commonMain/moko-resources) | ForEach-Object {
    if ($_ -match '^i18n-sy/src/commonMain/moko-resources/([^/]+)/strings\.xml$' -and $Matches[1] -ne "base") {
        [void]$availableLocales.Add($Matches[1])
    }
}

$basePath = "i18n-sy/src/commonMain/moko-resources/base/strings.xml"
$baseDocument = Read-GitXml $basePath
if ($null -eq $baseDocument) {
    throw "Unable to read $basePath from $SyRef."
}
$baseStrings = String-Map $baseDocument

$defaultStrings = @{}
Get-ChildItem -LiteralPath (Join-Path $resourceRoot "values") -Filter "*.xml" -File | ForEach-Object {
    $document = [xml](Get-Content -Raw -LiteralPath $_.FullName)
    @($document.SelectNodes("/resources/string")) | ForEach-Object {
        $defaultStrings[[string]$_.name] = [string]$_.InnerText
    }
}

foreach ($entry in $mapping.GetEnumerator()) {
    if (!$defaultStrings.ContainsKey($entry.Key)) {
        throw "Hayai string $($entry.Key) does not exist. Review the SY translation mapping."
    }
    if (!$baseStrings.ContainsKey($entry.Value) -or $defaultStrings[$entry.Key] -ine $baseStrings[$entry.Value]) {
        throw "Hayai string $($entry.Key) no longer matches SY string $($entry.Value). Review the translation before importing it."
    }
}

$written = 0
Get-ChildItem -LiteralPath $resourceRoot -Directory | Where-Object {
    $_.Name -match '^values-' -and $_.Name -notmatch '^values-(?:night|v\d|sw\d)'
} | ForEach-Object {
    $locale = $_.Name.Substring("values-".Length)
    $upstreamPath = "i18n-sy/src/commonMain/moko-resources/$locale/strings.xml"
    $targetPath = Join-Path $_.FullName $generatedFileName
    if (!$availableLocales.Contains($locale)) {
        if (Test-Path -LiteralPath $targetPath) {
            Remove-Item -LiteralPath $targetPath
        }
    } else {
        $localeDocument = Read-GitXml $upstreamPath
        if ($null -eq $localeDocument) {
            throw "Unable to read $upstreamPath from $SyRef."
        }
        $upstreamStrings = String-Map $localeDocument
        $rows = [Collections.Generic.List[string]]::new()
        foreach ($entry in $mapping.GetEnumerator()) {
            if ($upstreamStrings.ContainsKey($entry.Value) -and $upstreamStrings[$entry.Value]) {
                $rows.Add("    <string name=`"$($entry.Key)`">$(Escape-Xml $upstreamStrings[$entry.Value])</string>")
            }
        }
        if ($rows.Count -eq 0) {
            if (Test-Path -LiteralPath $targetPath) {
                Remove-Item -LiteralPath $targetPath
            }
        } else {
            $content = @(
                '<?xml version="1.0" encoding="utf-8"?>',
                "<!-- Generated from TachiyomiSY $commit by tools/import-sy-translations.ps1. -->",
                '<resources>'
            ) + $rows + '</resources>'
            Set-Content -LiteralPath $targetPath -Value $content -Encoding utf8NoBOM
            $written++
        }
    }
}

Write-Output "Imported reviewed TachiyomiSY translations into $written supported locale directories."
