package dev.ahmedmohamed.hayai.adult.eh.session

import java.util.Locale

data class EhCredentials(
    val memberId: String,
    val passHash: String,
    val igneous: String,
)

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
        val reason: String,
    ) : EhSessionState
}

sealed interface EhSessionMutationResult {
    data class Success(
        val state: EhSessionState,
    ) : EhSessionMutationResult

    data class Failure(
        val reason: String,
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
                memberId = EhCookieValue.memberId(requireNotNull(memberId) { "The member ID cookie is missing." }),
                passHash = EhCookieValue.secret("pass hash", requireNotNull(passHash) { "The pass hash cookie is missing." }),
                igneous =
                    EhCookieValue.secret(
                        "igneous",
                        manualIgneous?.takeIf { it.isNotBlank() }
                            ?: requireNotNull(igneous) { "The igneous cookie is missing." },
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
                    require(old == null || old == value) { "Conflicting $name cookies were returned." }
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
        require(normalized.length in 1..32 && normalized.all(Char::isDigit)) { "The member ID cookie is invalid." }
        return normalized
    }

    fun secret(
        label: String,
        value: String,
    ): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "The $label cookie is empty." }
        require(normalized.length <= 512) { "The $label cookie is too long." }
        require(normalized.all(::isCookieOctet)) { "The $label cookie contains unsafe characters." }
        return normalized
    }

    private fun isCookieOctet(char: Char): Boolean = char.code in 0x21..0x7e && char !in setOf('"', ',', ';', '\\')
}
