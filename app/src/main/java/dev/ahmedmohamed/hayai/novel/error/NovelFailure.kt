package dev.ahmedmohamed.hayai.novel.error

import android.content.Context
import androidx.annotation.StringRes
import eu.kanade.tachiyomi.R
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Stable, presentation-neutral failures for novel operations whose messages reach app UI. */
class NovelFailure(
    override val code: Code,
    override val arguments: List<Any> = emptyList(),
    cause: Throwable? = null,
) : IllegalArgumentException(code.name, cause), NovelFailureCarrier {
    enum class Code {
        ImportPlanInvalid,
        ImportEmpty,
        ImportInvalidTsundoku,
        ImportTooManyChapters,
        ImportTooManyFiles,
        ImportExpandedTooLarge,
        ImportUnsafeZipPath,
        ImportTooManyNovels,
        ImportTooManyCategories,
        ImportInvalidField,
        ImportDataTooLarge,
        ImportNovelIdentityConflict,
        ImportChapterIdentityConflict,
        ExportDuplicateAssetName,
        ExportDuplicateAssetUrl,
        ExtensionCatalogRefresh,
        ExtensionRepositoryHttps,
        ExtensionNotConfigured,
        ExtensionNoUpdate,
        PluginRepositoryName,
        PluginRepositoryMissing,
        PluginUnavailable,
        PluginRefreshRequired,
        PluginCodeSize,
        PluginChecksum,
        PluginRestoreSettings,
        PluginRollbackSettings,
        PluginRemoveCode,
        PluginRemoveMetadata,
        PluginRepositoryArray,
        PluginRepositoryDocument,
        PluginRepositoryTooMany,
        PluginRepositoryMixedKeys,
        PluginHttp,
        PluginResponseTooLarge,
        PluginId,
        PluginName,
        PluginLanguage,
        PluginVersion,
        PluginSha256,
        PluginSigningPair,
        PluginPublicKey,
        PluginSignature,
        PluginCssTooLarge,
        PluginJavaScriptTooLarge,
        PluginBase64,
        PluginUrlLength,
        PluginAbsoluteUrl,
        PluginHttpsOnly,
        PluginWebsiteUrl,
        PluginWebsiteScheme,
        PluginRepositoryUntrusted,
        PluginUnsignedFromSigned,
        PluginSignatureUntrusted,
        PluginSigningKeyChanged,
        PluginPublicKeyUnsupported,
        PluginSignatureFailed,
        PluginTrustPersist,
        PluginExecutionTimeout,
        PluginExecutionError,
        PluginChapterUrlEmpty,
        PluginAssetTooLarge,
        OfflineSavedVerify,
        OfflineDownload,
        OfflineChapterUrl,
        OfflineTextTooLarge,
        OfflineStaging,
        OfflinePreservePrevious,
        OfflinePublishPreserved,
        OfflinePublish,
        OfflineAssetsTooLarge,
        OfflineRemove,
        OfflineRemovedStillPresent,
        OfflineRemoval,
        LocalNoTextFiles,
        LocalAssetTooLarge,
        LocalCoverSize,
        LocalCoverFormat,
        ArchiveEmpty,
        ArchiveMissing,
        DictionaryUnavailable,
        CustomSourceInvalidDocument,
        CustomSourceNotFound,
        CustomSourcePersist,
        CustomSourceRemove,
        QuoteAlreadySaved,
        QuoteSave,
        QuoteSelectionEmpty,
        QuoteSelectionTooLong,
        SourceNotNovel,
        SourceChapterEmpty,
        TranslationEndpointHttps,
        TranslationEndpointCredentials,
        TranslationFailed,
        TranslationProviderNoText,
        TranslationProviderNoChoices,
        TranslationProviderNoTranslations,
        TranslationHttp,
        TranslationTooManyRequests,
        TranslationResponseTooLarge,
        ReplacementPatternTooLong,
        ReplacementBackreference,
        ReplacementNestedRepetition,
        ReplacementInvalidRegex,
    }

    enum class ImportField {
        MangaUrl,
        Title,
        ChapterUrl,
        ChapterTitle,
        Category,
        NovelPath,
        NovelName,
        ChapterPath,
        ChapterName,
    }
}

interface NovelFailureCarrier {
    val code: NovelFailure.Code
    val arguments: List<Any>
}

class NovelValidationFailure(
    override val code: NovelFailure.Code,
    override val arguments: List<Any> = emptyList(),
    cause: Throwable? = null,
) : IllegalArgumentException(code.name, cause), NovelFailureCarrier

internal fun novelFailure(code: NovelFailure.Code, vararg arguments: Any): Nothing =
    throw NovelFailure(code, arguments.toList())

@OptIn(ExperimentalContracts::class)
internal inline fun novelRequire(value: Boolean, code: NovelFailure.Code, vararg arguments: Any) {
    contract { returns() implies value }
    if (!value) novelFailure(code, *arguments)
}

fun Context.novelFailureMessage(error: Throwable, @StringRes fallback: Int): String {
    val failure = generateSequence(error) { it.cause }.mapNotNull { it as? NovelFailureCarrier }.firstOrNull()
        ?: return error.localizedMessage?.takeIf(String::isNotBlank) ?: getString(fallback)
    @StringRes val resource =
        when (failure.code) {
            NovelFailure.Code.ImportPlanInvalid -> R.string.hayai_failure_import_plan_invalid
            NovelFailure.Code.ImportEmpty -> R.string.hayai_failure_import_empty
            NovelFailure.Code.ImportInvalidTsundoku -> R.string.hayai_failure_import_invalid_tsundoku
            NovelFailure.Code.ImportTooManyChapters -> R.string.hayai_failure_import_too_many_chapters
            NovelFailure.Code.ImportTooManyFiles -> R.string.hayai_failure_import_too_many_files
            NovelFailure.Code.ImportExpandedTooLarge -> R.string.hayai_failure_import_expanded_too_large
            NovelFailure.Code.ImportUnsafeZipPath -> R.string.hayai_failure_import_unsafe_zip
            NovelFailure.Code.ImportTooManyNovels -> R.string.hayai_failure_import_too_many_novels
            NovelFailure.Code.ImportTooManyCategories -> R.string.hayai_failure_import_too_many_categories
            NovelFailure.Code.ImportInvalidField -> R.string.hayai_failure_import_invalid_field
            NovelFailure.Code.ImportDataTooLarge -> R.string.hayai_failure_import_data_too_large
            NovelFailure.Code.ImportNovelIdentityConflict -> R.string.hayai_failure_import_novel_identity
            NovelFailure.Code.ImportChapterIdentityConflict -> R.string.hayai_failure_import_chapter_identity
            NovelFailure.Code.ExportDuplicateAssetName -> R.string.hayai_failure_export_duplicate_asset_name
            NovelFailure.Code.ExportDuplicateAssetUrl -> R.string.hayai_failure_export_duplicate_asset_url
            NovelFailure.Code.ExtensionCatalogRefresh -> R.string.hayai_failure_extension_refresh
            NovelFailure.Code.ExtensionRepositoryHttps -> R.string.hayai_failure_extension_repository_https
            NovelFailure.Code.ExtensionNotConfigured -> R.string.hayai_failure_extension_not_configured
            NovelFailure.Code.ExtensionNoUpdate -> R.string.hayai_failure_extension_no_update
            NovelFailure.Code.PluginRepositoryName -> R.string.hayai_failure_plugin_repository_name
            NovelFailure.Code.PluginRepositoryMissing -> R.string.hayai_failure_plugin_repository_missing
            NovelFailure.Code.PluginUnavailable -> R.string.hayai_failure_plugin_unavailable
            NovelFailure.Code.PluginRefreshRequired -> R.string.hayai_failure_plugin_refresh_required
            NovelFailure.Code.PluginCodeSize -> R.string.hayai_failure_plugin_code_size
            NovelFailure.Code.PluginChecksum -> R.string.hayai_failure_plugin_checksum
            NovelFailure.Code.PluginRestoreSettings -> R.string.hayai_failure_plugin_restore_settings
            NovelFailure.Code.PluginRollbackSettings -> R.string.hayai_failure_plugin_rollback_settings
            NovelFailure.Code.PluginRemoveCode -> R.string.hayai_failure_plugin_remove_code
            NovelFailure.Code.PluginRemoveMetadata -> R.string.hayai_failure_plugin_remove_metadata
            NovelFailure.Code.PluginRepositoryArray -> R.string.hayai_failure_plugin_repository_array
            NovelFailure.Code.PluginRepositoryDocument -> R.string.hayai_failure_plugin_repository_document
            NovelFailure.Code.PluginRepositoryTooMany -> R.string.hayai_failure_plugin_repository_too_many
            NovelFailure.Code.PluginRepositoryMixedKeys -> R.string.hayai_failure_plugin_repository_mixed_keys
            NovelFailure.Code.PluginHttp -> R.string.hayai_failure_plugin_http
            NovelFailure.Code.PluginResponseTooLarge -> R.string.hayai_failure_plugin_response_too_large
            NovelFailure.Code.PluginId -> R.string.hayai_failure_plugin_id
            NovelFailure.Code.PluginName -> R.string.hayai_failure_plugin_name
            NovelFailure.Code.PluginLanguage -> R.string.hayai_failure_plugin_language
            NovelFailure.Code.PluginVersion -> R.string.hayai_failure_plugin_version
            NovelFailure.Code.PluginSha256 -> R.string.hayai_failure_plugin_sha256
            NovelFailure.Code.PluginSigningPair -> R.string.hayai_failure_plugin_signing_pair
            NovelFailure.Code.PluginPublicKey -> R.string.hayai_failure_plugin_public_key
            NovelFailure.Code.PluginSignature -> R.string.hayai_failure_plugin_signature
            NovelFailure.Code.PluginCssTooLarge -> R.string.hayai_failure_plugin_css_too_large
            NovelFailure.Code.PluginJavaScriptTooLarge -> R.string.hayai_failure_plugin_javascript_too_large
            NovelFailure.Code.PluginBase64 -> R.string.hayai_failure_plugin_base64
            NovelFailure.Code.PluginUrlLength -> R.string.hayai_failure_plugin_url_length
            NovelFailure.Code.PluginAbsoluteUrl -> R.string.hayai_failure_plugin_absolute_url
            NovelFailure.Code.PluginHttpsOnly -> R.string.hayai_failure_plugin_https_only
            NovelFailure.Code.PluginWebsiteUrl -> R.string.hayai_failure_plugin_website_url
            NovelFailure.Code.PluginWebsiteScheme -> R.string.hayai_failure_plugin_website_scheme
            NovelFailure.Code.PluginRepositoryUntrusted -> R.string.hayai_failure_plugin_repository_untrusted
            NovelFailure.Code.PluginUnsignedFromSigned -> R.string.hayai_failure_plugin_unsigned_from_signed
            NovelFailure.Code.PluginSignatureUntrusted -> R.string.hayai_failure_plugin_signature_untrusted
            NovelFailure.Code.PluginSigningKeyChanged -> R.string.hayai_failure_plugin_signing_key_changed
            NovelFailure.Code.PluginPublicKeyUnsupported -> R.string.hayai_failure_plugin_public_key_unsupported
            NovelFailure.Code.PluginSignatureFailed -> R.string.hayai_failure_plugin_signature_failed
            NovelFailure.Code.PluginTrustPersist -> R.string.hayai_failure_plugin_trust_persist
            NovelFailure.Code.PluginExecutionTimeout -> R.string.hayai_failure_plugin_execution_timeout
            NovelFailure.Code.PluginExecutionError -> R.string.hayai_failure_plugin_execution_error
            NovelFailure.Code.PluginChapterUrlEmpty -> R.string.hayai_failure_plugin_chapter_url_empty
            NovelFailure.Code.PluginAssetTooLarge -> R.string.hayai_failure_plugin_asset_too_large
            NovelFailure.Code.OfflineSavedVerify -> R.string.hayai_failure_offline_saved_verify
            NovelFailure.Code.OfflineDownload -> R.string.hayai_failure_offline_download
            NovelFailure.Code.OfflineChapterUrl -> R.string.hayai_failure_offline_chapter_url
            NovelFailure.Code.OfflineTextTooLarge -> R.string.hayai_failure_offline_text_too_large
            NovelFailure.Code.OfflineStaging -> R.string.hayai_failure_offline_staging
            NovelFailure.Code.OfflinePreservePrevious -> R.string.hayai_failure_offline_preserve_previous
            NovelFailure.Code.OfflinePublishPreserved -> R.string.hayai_failure_offline_publish_preserved
            NovelFailure.Code.OfflinePublish -> R.string.hayai_failure_offline_publish
            NovelFailure.Code.OfflineAssetsTooLarge -> R.string.hayai_failure_offline_assets_too_large
            NovelFailure.Code.OfflineRemove -> R.string.hayai_failure_offline_remove
            NovelFailure.Code.OfflineRemovedStillPresent -> R.string.hayai_failure_offline_still_present
            NovelFailure.Code.OfflineRemoval -> R.string.hayai_failure_offline_removal
            NovelFailure.Code.LocalNoTextFiles -> R.string.hayai_failure_local_no_text_files
            NovelFailure.Code.LocalAssetTooLarge -> R.string.hayai_failure_local_asset_too_large
            NovelFailure.Code.LocalCoverSize -> R.string.hayai_failure_local_cover_size
            NovelFailure.Code.LocalCoverFormat -> R.string.hayai_failure_local_cover_format
            NovelFailure.Code.ArchiveEmpty -> R.string.hayai_failure_archive_empty
            NovelFailure.Code.ArchiveMissing -> R.string.hayai_failure_archive_missing
            NovelFailure.Code.DictionaryUnavailable -> R.string.hayai_failure_dictionary_unavailable
            NovelFailure.Code.CustomSourceInvalidDocument -> R.string.hayai_failure_custom_source_invalid_document
            NovelFailure.Code.CustomSourceNotFound -> R.string.hayai_failure_custom_source_not_found
            NovelFailure.Code.CustomSourcePersist -> R.string.hayai_failure_custom_source_persist
            NovelFailure.Code.CustomSourceRemove -> R.string.hayai_failure_custom_source_remove
            NovelFailure.Code.QuoteAlreadySaved -> R.string.hayai_failure_quote_already_saved
            NovelFailure.Code.QuoteSave -> R.string.hayai_failure_quote_save
            NovelFailure.Code.QuoteSelectionEmpty -> R.string.hayai_failure_quote_selection_empty
            NovelFailure.Code.QuoteSelectionTooLong -> R.string.hayai_failure_quote_selection_too_long
            NovelFailure.Code.SourceNotNovel -> R.string.hayai_failure_source_not_novel
            NovelFailure.Code.SourceChapterEmpty -> R.string.hayai_failure_source_chapter_empty
            NovelFailure.Code.TranslationEndpointHttps -> R.string.hayai_translation_endpoint_https
            NovelFailure.Code.TranslationEndpointCredentials -> R.string.hayai_translation_endpoint_credentials
            NovelFailure.Code.TranslationFailed -> R.string.hayai_translation_failure_detail
            NovelFailure.Code.TranslationProviderNoText -> R.string.hayai_translation_provider_no_text
            NovelFailure.Code.TranslationProviderNoChoices -> R.string.hayai_translation_provider_no_choices
            NovelFailure.Code.TranslationProviderNoTranslations -> R.string.hayai_translation_provider_no_translations
            NovelFailure.Code.TranslationHttp -> R.string.hayai_translation_http_error
            NovelFailure.Code.TranslationTooManyRequests -> R.string.hayai_translation_too_many_requests
            NovelFailure.Code.TranslationResponseTooLarge -> R.string.hayai_translation_response_too_large
            NovelFailure.Code.ReplacementPatternTooLong -> R.string.hayai_failure_replacement_pattern_too_long
            NovelFailure.Code.ReplacementBackreference -> R.string.hayai_failure_replacement_backreference
            NovelFailure.Code.ReplacementNestedRepetition -> R.string.hayai_failure_replacement_nested_repetition
            NovelFailure.Code.ReplacementInvalidRegex -> R.string.hayai_failure_replacement_invalid_regex
        }
    val arguments =
        failure.arguments.map { argument ->
            if (argument !is NovelFailure.ImportField) return@map argument
            getString(
                when (argument) {
                    NovelFailure.ImportField.MangaUrl -> R.string.hayai_import_field_manga_url
                    NovelFailure.ImportField.Title -> R.string.title
                    NovelFailure.ImportField.ChapterUrl -> R.string.hayai_import_field_chapter_url
                    NovelFailure.ImportField.ChapterTitle -> R.string.hayai_import_field_chapter_title
                    NovelFailure.ImportField.Category -> R.string.category
                    NovelFailure.ImportField.NovelPath -> R.string.hayai_import_field_novel_path
                    NovelFailure.ImportField.NovelName -> R.string.hayai_import_field_novel_name
                    NovelFailure.ImportField.ChapterPath -> R.string.hayai_import_field_chapter_path
                    NovelFailure.ImportField.ChapterName -> R.string.hayai_import_field_chapter_name
                },
            )
        }
    return getString(resource, *arguments.toTypedArray())
}
