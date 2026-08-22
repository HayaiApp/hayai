package dev.ahmedmohamed.hayai.adult.eh.presentation

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoriteConflictKind
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoritesFailure
import dev.ahmedmohamed.hayai.adult.eh.favorites.EhFavoritesFailureReason
import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailure
import dev.ahmedmohamed.hayai.adult.eh.domain.EhFailureReason
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionFailureReason
import dev.ahmedmohamed.hayai.adult.eh.settings.EhLanguage
import dev.ahmedmohamed.hayai.adult.eh.uconfig.EhRemoteSettingsFailure
import eu.kanade.tachiyomi.R

class EhTextResolver(private val context: Context) {
    fun get(@StringRes resource: Int, vararg arguments: Any): String = context.getString(resource, *arguments)

    fun quantity(@PluralsRes resource: Int, quantity: Int, vararg arguments: Any): String =
        context.resources.getQuantityString(resource, quantity, *arguments)
}

fun EhFailure.localizedMessage(text: EhTextResolver): String = when (reason) {
    EhFailureReason.AuthenticationRequired -> text.get(R.string.hayai_eh_failure_authentication)
    EhFailureReason.AccessDenied -> text.get(R.string.hayai_eh_failure_access_denied)
    EhFailureReason.GalleryNotFound -> text.get(R.string.hayai_eh_failure_gallery_not_found)
    EhFailureReason.QuotaExceeded -> text.get(R.string.hayai_eh_failure_quota_exceeded)
    EhFailureReason.RateLimited -> (this as? EhFailure.RateLimited)?.retryAfterSeconds?.let {
        text.quantity(R.plurals.hayai_eh_failure_rate_limited_seconds, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it)
    } ?: text.get(R.string.hayai_eh_failure_rate_limited)
    EhFailureReason.RemoteWarning -> text.get(R.string.hayai_eh_failure_remote_warning)
    EhFailureReason.MalformedResponse -> text.get(R.string.hayai_eh_failure_malformed_response)
    EhFailureReason.BoundsExceeded -> text.get(R.string.hayai_eh_failure_response_limits)
    EhFailureReason.Network -> text.get(R.string.hayai_eh_failure_network)
}

fun EhSessionFailureReason.localizedMessage(text: EhTextResolver): String = text.get(
    when (this) {
        EhSessionFailureReason.InvalidCookies -> R.string.hayai_eh_session_failure_invalid_cookies
        EhSessionFailureReason.CredentialsUnavailable -> R.string.hayai_eh_session_failure_unavailable
        EhSessionFailureReason.StoredCredentialsInvalid -> R.string.hayai_eh_session_failure_stored_invalid
        EhSessionFailureReason.CredentialsRejected -> R.string.hayai_eh_session_failure_rejected
    },
)

fun EhRemoteSettingsFailure.localizedMessage(text: EhTextResolver): String = when (this) {
    is EhRemoteSettingsFailure.AuthenticationRequired -> text.get(R.string.hayai_eh_remote_failure_authentication)
    is EhRemoteSettingsFailure.RateLimited -> retryAfterSeconds?.let {
        text.quantity(R.plurals.hayai_eh_remote_failure_rate_limited_seconds, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it)
    } ?: text.get(R.string.hayai_eh_remote_failure_rate_limited)
    is EhRemoteSettingsFailure.RemoteRejected -> text.get(R.string.hayai_eh_remote_failure_rejected, code)
    is EhRemoteSettingsFailure.OutOfProfileSlots -> text.get(R.string.hayai_eh_remote_failure_no_profile_slots, site.displayName)
    is EhRemoteSettingsFailure.MalformedResponse -> text.get(R.string.hayai_eh_remote_failure_malformed)
    is EhRemoteSettingsFailure.Network -> text.get(R.string.hayai_eh_remote_failure_network)
}

fun EhFavoritesFailure.localizedMessage(text: EhTextResolver): String = when (reason) {
    EhFavoritesFailureReason.InvalidCategories -> text.get(R.string.hayai_eh_favorites_failure_categories)
    EhFavoritesFailureReason.DuplicateGallery -> text.get(R.string.hayai_eh_favorites_failure_duplicate)
    EhFavoritesFailureReason.TooManyFavorites -> text.get(R.string.hayai_eh_favorites_failure_too_many)
    EhFavoritesFailureReason.MissingCategories -> text.get(R.string.hayai_eh_favorites_failure_missing_categories)
    EhFavoritesFailureReason.PaginationExceeded -> text.get(R.string.hayai_eh_favorites_failure_pagination)
    EhFavoritesFailureReason.RequestFailed -> text.get(R.string.hayai_eh_favorites_failure_network)
    EhFavoritesFailureReason.ResponseTooLarge -> text.get(R.string.hayai_eh_favorites_failure_response_too_large)
}

fun Throwable.localizedEhMessage(text: EhTextResolver, fallbackResource: Int): String = when (this) {
    is EhFailure -> localizedMessage(text)
    is EhRemoteSettingsFailure -> localizedMessage(text)
    is EhFavoritesFailure -> localizedMessage(text)
    else -> text.get(fallbackResource)
}

@StringRes
fun EhLanguage.displayNameResource(): Int = when (this) {
    EhLanguage.Japanese -> R.string.hayai_eh_language_japanese
    EhLanguage.English -> R.string.hayai_eh_language_english
    EhLanguage.Chinese -> R.string.hayai_eh_language_chinese
    EhLanguage.Dutch -> R.string.hayai_eh_language_dutch
    EhLanguage.French -> R.string.hayai_eh_language_french
    EhLanguage.German -> R.string.hayai_eh_language_german
    EhLanguage.Hungarian -> R.string.hayai_eh_language_hungarian
    EhLanguage.Italian -> R.string.hayai_eh_language_italian
    EhLanguage.Korean -> R.string.hayai_eh_language_korean
    EhLanguage.Polish -> R.string.hayai_eh_language_polish
    EhLanguage.Portuguese -> R.string.hayai_eh_language_portuguese
    EhLanguage.Russian -> R.string.hayai_eh_language_russian
    EhLanguage.Spanish -> R.string.hayai_eh_language_spanish
    EhLanguage.Thai -> R.string.hayai_eh_language_thai
    EhLanguage.Vietnamese -> R.string.hayai_eh_language_vietnamese
    EhLanguage.NotAvailable -> R.string.hayai_eh_language_not_available
    EhLanguage.Other -> R.string.other
}

@StringRes
fun EhFavoriteConflictKind.messageResource(): Int = when (this) {
    EhFavoriteConflictKind.BothChanged -> R.string.hayai_eh_conflict_both_changed
    EhFavoriteConflictKind.MultipleMappedCategories -> R.string.hayai_eh_conflict_multiple_categories
    EhFavoriteConflictKind.DuplicateAliases -> R.string.hayai_eh_conflict_duplicate_aliases
    EhFavoriteConflictKind.RemoteChanged -> R.string.hayai_eh_conflict_remote_changed
}
