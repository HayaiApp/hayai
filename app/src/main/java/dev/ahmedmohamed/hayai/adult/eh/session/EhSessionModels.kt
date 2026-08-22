package dev.ahmedmohamed.hayai.adult.eh.session

import java.util.Locale

data class EhCredentials(
    val memberId: String,
    val passHash: String,
    val igneous: String,
)

enum class EhSessionFailureReason {
    InvalidCookies,
    CredentialsUnavailable,
    StoredCredentialsInvalid,
    CredentialsRejected,
}

class EhSessionFailureException(
    val reason: EhSessionFailureReason,
    diagnostic: String? = null,
) : IllegalArgumentException(diagnostic ?: reason.name)

sealed interface EhSessionState {
    data object LoggedOut : EhSessionState

    data class CredentialsAvailable(
        val credentials: EhCredentials,
    ) : EhSessionState

    data class Verified(
        val credentials: EhCredentials,
        val verifiedAtEpochMillis: Long,
    ) : EhSessionState

    data class InvalidCredentials(
        val credentials: EhCredentials?,
        val reason: EhSessionFailureReason,
    ) : EhSessionState
}

sealed interface EhSessionMutationResult {
    data class Success(
        val state: EhSessionState,
    ) : EhSessionMutationResult

    data class Failure(
        val reason: EhSessionFailureReason,
    ) : EhSessionMutationResult
}

@JvmInline
value class EhCookieHeader(
    val value: String,
)

data class EhLoginCookies(
    val memberId: String?,
    val passHash: String?,
    val igneous: String?,
) {
    fun toCredentials(manualIgneous: String? = null): Result<EhCredentials> =
        runCatching {
            EhCredentials(
                memberId = EhCookieValue.memberId(memberId ?: throw EhSessionFailureException(EhSessionFailureReason.InvalidCookies, "member ID cookie missing")),
                passHash = EhCookieValue.secret("pass hash", passHash ?: throw EhSessionFailureException(EhSessionFailureReason.InvalidCookies, "pass hash cookie missing")),
                igneous =
                    EhCookieValue.secret(
                        "igneous",
                        manualIgneous?.takeIf { it.isNotBlank() }
                            ?: igneous ?: throw EhSessionFailureException(EhSessionFailureReason.InvalidCookies, "igneous cookie missing"),
                    ),
            )
        }
}

object EhLoginCookieParser {
    fun parse(vararg cookieHeaders: String?): Result<EhLoginCookies> =
        runCatching {
            val values = linkedMapOf<String, String>()
            cookieHeaders.filterNotNull().forEach { header ->
                header.split(';').forEach { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) return@forEach
                    val name = part.substring(0, separator).trim().lowercase(Locale.ROOT)
                    if (name !in LOGIN_COOKIE_NAMES) return@forEach
                    val value = part.substring(separator + 1).trim()
                    val old = values.putIfAbsent(name, value)
                    if (old != null && old != value) {
                        throw EhSessionFailureException(EhSessionFailureReason.InvalidCookies, "conflicting $name cookies")
                    }
                }
            }
            EhLoginCookies(
                memberId = values[MEMBER_ID_COOKIE],
                passHash = values[PASS_HASH_COOKIE],
                igneous = values[IGNEOUS_COOKIE],
            )
        }

    const val MEMBER_ID_COOKIE = "ipb_member_id"
    const val PASS_HASH_COOKIE = "ipb_pass_hash"
    const val IGNEOUS_COOKIE = "igneous"

    private val LOGIN_COOKIE_NAMES = setOf(MEMBER_ID_COOKIE, PASS_HASH_COOKIE, IGNEOUS_COOKIE)
}

internal object EhCookieValue {
    fun memberId(value: String): String {
        val normalized = value.trim()
        if (normalized.length !in 1..32 || !normalized.all(Char::isDigit)) {
            throw EhSessionFailureException(EhSessionFailureReason.InvalidCookies, "invalid member ID cookie")
        }
        return normalized
    }

    fun secret(
        label: String,
        value: String,
    ): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) throw EhSessionFailureException(EhSessionFailureReason.InvalidCookies, "$label cookie empty")
        if (normalized.length > 512) throw EhSessionFailureException(EhSessionFailureReason.InvalidCookies, "$label cookie too long")
        if (!normalized.all(::isCookieOctet)) throw EhSessionFailureException(EhSessionFailureReason.InvalidCookies, "$label cookie unsafe")
        return normalized
    }

    private fun isCookieOctet(char: Char): Boolean = char.code in 0x21..0x7e && char !in setOf('"', ',', ';', '\\')
}
