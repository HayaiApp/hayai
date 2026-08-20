package dev.ahmedmohamed.hayai.adult.eh.uconfig

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.session.EhCookieHeader

data class EhHathPerks(
    val moreThumbs: Boolean = false,
    val thumbsUp: Boolean = false,
    val allThumbs: Boolean = false,
    val pagingI: Boolean = false,
    val pagingII: Boolean = false,
    val pagingIII: Boolean = false,
) {
    val thumbnailRowsValue: String
        get() = when {
            allThumbs -> "3"
            thumbsUp -> "2"
            moreThumbs -> "1"
            else -> "0"
        }

    val resultCountValue: String
        get() = when {
            pagingIII -> "3"
            pagingII -> "2"
            pagingI -> "1"
            else -> "0"
        }
}

@JvmInline
value class EhProfileSlot(val value: Int) {
    init {
        require(value in 1..3) { "E-Hentai application profile slots are between 1 and 3." }
    }
}

data class EhRemoteProfile(
    val slot: EhProfileSlot,
    val name: String,
)

data class EhRemoteCookies(
    val settingsKey: String? = null,
    val session: String? = null,
    val hathPerks: String? = null,
) {
    init {
        validate("settings key", settingsKey)
        validate("session", session)
        validate("H@H perks", hathPerks)
    }

    fun merge(other: EhRemoteCookies): EhRemoteCookies =
        EhRemoteCookies(
            settingsKey = other.settingsKey ?: settingsKey,
            session = other.session ?: session,
            hathPerks = other.hathPerks ?: hathPerks,
        )

    fun overlay(header: EhCookieHeader): EhCookieHeader {
        val values = linkedMapOf<String, String>()
        header.value.split(';').forEach { part ->
            val separator = part.indexOf('=')
            if (separator > 0) values[part.substring(0, separator).trim()] = part.substring(separator + 1).trim()
        }
        settingsKey?.let { values["sk"] = it }
        session?.let { values["s"] = it }
        hathPerks?.let { values["hath_perks"] = it }
        return EhCookieHeader(values.entries.joinToString("; ") { (name, value) -> "$name=$value" })
    }

    private fun validate(label: String, value: String?) {
        if (value == null) return
        require(value.isNotBlank() && value.length <= 512) { "The $label cookie is invalid." }
        require(value.all { it.code in 0x21..0x7e && it !in setOf('"', ',', ';', '\\') }) {
            "The $label cookie contains unsafe characters."
        }
    }
}

sealed class EhRemoteSettingsFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class AuthenticationRequired : EhRemoteSettingsFailure("Verify an ExHentai session before uploading settings.")
    class RateLimited(val retryAfterSeconds: Long?) :
        EhRemoteSettingsFailure(
            retryAfterSeconds?.let { "E-Hentai rate-limited settings upload. Retry in $it seconds." }
                ?: "E-Hentai rate-limited settings upload.",
        )
    class RemoteRejected(code: Int) : EhRemoteSettingsFailure("E-Hentai rejected the settings request with HTTP $code.")
    class OutOfProfileSlots(site: EhSite) : EhRemoteSettingsFailure("${site.displayName} has no free application profile slot.")
    class MalformedResponse(message: String, cause: Throwable? = null) : EhRemoteSettingsFailure(message, cause)
    class Network(message: String, cause: Throwable? = null) : EhRemoteSettingsFailure(message, cause)
}

sealed interface EhSiteUploadResult {
    val site: EhSite

    data class Applied(
        override val site: EhSite,
        val slot: EhProfileSlot,
    ) : EhSiteUploadResult

    data class Failed(
        override val site: EhSite,
        val failure: EhRemoteSettingsFailure,
    ) : EhSiteUploadResult
}

data class EhUploadReport(
    val results: Map<EhSite, EhSiteUploadResult>,
) {
    val failedSites: Set<EhSite>
        get() = results.values.filterIsInstance<EhSiteUploadResult.Failed>().mapTo(linkedSetOf()) { it.site }

    val successfulSites: Set<EhSite>
        get() = results.values.filterIsInstance<EhSiteUploadResult.Applied>().mapTo(linkedSetOf()) { it.site }
}

sealed interface EhUploadProgress {
    data class Started(val site: EhSite) : EhUploadProgress
    data class Finished(val result: EhSiteUploadResult) : EhUploadProgress
}
