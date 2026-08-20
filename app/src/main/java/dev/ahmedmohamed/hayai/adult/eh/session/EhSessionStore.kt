package dev.ahmedmohamed.hayai.adult.eh.session

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.uconfig.EhProfileSlot
import dev.ahmedmohamed.hayai.adult.eh.uconfig.EhRemoteCookies
import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

class EhSessionStore(
    store: PreferenceStore,
    private val cookieStore: EhCookieStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val enableExhentai = store.getBoolean(KEY_ENABLE_EXHENTAI, false)
    private val memberId = store.getString(KEY_MEMBER_ID, "")
    private val passHash = store.getString(KEY_PASS_HASH, "")
    private val igneous = store.getString(KEY_IGNEOUS, "")
    private val ehSettingsProfile = store.getInt(KEY_EH_SETTINGS_PROFILE, -1)
    private val exhSettingsProfile = store.getInt(KEY_EXH_SETTINGS_PROFILE, -1)
    private val settingsKey = store.getString(KEY_SETTINGS_KEY, "")
    private val sessionCookie = store.getString(KEY_SESSION_COOKIE, "")
    private val hathPerksCookie = store.getString(KEY_HATH_PERKS_COOKIE, "")
    private val verificationStatus = store.getString(KEY_VERIFICATION_STATUS, STATUS_UNVERIFIED)
    private val verificationFingerprint = store.getString(KEY_VERIFICATION_FINGERPRINT, "")
    private val verifiedAt = store.getLong(KEY_VERIFIED_AT, 0)
    private val invalidReason = store.getString(KEY_INVALID_REASON, "")

    private val mutableState = MutableStateFlow(readState())
    val state: StateFlow<EhSessionState> = mutableState.asStateFlow()

    init {
        if (mutableState.value !is EhSessionState.Verified && enableExhentai.get()) {
            enableExhentai.set(false)
        }
    }

    @Synchronized
    fun stage(
        cookieHeaders: Iterable<String?>,
        manualIgneous: String? = null,
    ): EhSessionMutationResult {
        val parsed = EhLoginCookieParser.parse(*cookieHeaders.toList().toTypedArray())
        val credentials = parsed.getOrElse { return EhSessionMutationResult.Failure(it.message ?: "Invalid login cookies.") }
            .toCredentials(manualIgneous)
            .getOrElse { return EhSessionMutationResult.Failure(it.message ?: "Invalid login cookies.") }

        memberId.set(credentials.memberId)
        passHash.set(credentials.passHash)
        igneous.set(credentials.igneous)
        enableExhentai.set(false)
        verificationStatus.set(STATUS_UNVERIFIED)
        verificationFingerprint.delete()
        verifiedAt.delete()
        invalidReason.delete()
        return publish(EhSessionState.CredentialsAvailable(credentials))
    }

    @Synchronized
    fun markVerified(): EhSessionMutationResult {
        val credentials = readCredentials().getOrElse { return EhSessionMutationResult.Failure(it.message ?: "No credentials are available.") }
        val timestamp = clock()
        verificationFingerprint.set(fingerprint(credentials))
        verificationStatus.set(STATUS_VERIFIED)
        verifiedAt.set(timestamp)
        invalidReason.delete()
        enableExhentai.set(true)
        return publish(EhSessionState.Verified(credentials, timestamp))
    }

    @Synchronized
    fun markInvalid(reason: String): EhSessionState.InvalidCredentials {
        val safeReason = reason.trim().take(500).ifEmpty { "The server rejected the credentials." }
        val credentials = readCredentials().getOrNull()
        verificationStatus.set(STATUS_INVALID)
        verificationFingerprint.delete()
        verifiedAt.delete()
        invalidReason.set(safeReason)
        enableExhentai.set(false)
        return EhSessionState.InvalidCredentials(credentials, safeReason).also { mutableState.value = it }
    }

    @Synchronized
    fun logout() {
        listOf(
            memberId,
            passHash,
            igneous,
            settingsKey,
            sessionCookie,
            hathPerksCookie,
            verificationStatus,
            verificationFingerprint,
            verifiedAt,
            invalidReason,
        ).forEach { it.delete() }
        enableExhentai.set(false)
        cookieStore.clearEhDomains()
        mutableState.value = EhSessionState.LoggedOut
    }

    fun cookieHeader(site: EhSite): EhCookieHeader {
        val credentials = readCredentials().getOrNull()
        val values = linkedMapOf<String, String>()
        credentials?.let {
            values[EhLoginCookieParser.MEMBER_ID_COOKIE] = it.memberId
            values[EhLoginCookieParser.PASS_HASH_COOKIE] = it.passHash
            values[EhLoginCookieParser.IGNEOUS_COOKIE] = it.igneous
        }
        settingsProfile(site)?.let { values["sp"] = it.toString() }
        optionalSecret("sk", settingsKey.get())?.let { (name, value) -> values[name] = value }
        optionalSecret("s", sessionCookie.get())?.let { (name, value) -> values[name] = value }
        optionalSecret("hath_perks", hathPerksCookie.get())?.let { (name, value) -> values[name] = value }
        values["sl"] = "dm_2"
        values["nw"] = "1"
        return EhCookieHeader(values.entries.joinToString("; ") { (name, value) -> "$name=$value" })
    }

    fun settingsProfile(site: EhSite): Int? =
        when (site) {
            EhSite.EHentai -> ehSettingsProfile.get()
            EhSite.ExHentai -> exhSettingsProfile.get()
        }.takeIf { it in 0..3 }

    fun setSettingsProfile(
        site: EhSite,
        profile: Int?,
    ) {
        require(profile == null || profile in 0..3) { "The settings profile must be automatic or between 0 and 3." }
        when (site) {
            EhSite.EHentai -> ehSettingsProfile.set(profile ?: -1)
            EhSite.ExHentai -> exhSettingsProfile.set(profile ?: -1)
        }
    }

    @Synchronized
    fun commitRemoteProfile(
        site: EhSite,
        slot: EhProfileSlot,
        cookies: EhRemoteCookies,
    ) {
        when (site) {
            EhSite.EHentai -> ehSettingsProfile.set(slot.value)
            EhSite.ExHentai -> exhSettingsProfile.set(slot.value)
        }
        cookies.settingsKey?.let(settingsKey::set)
        cookies.session?.let(sessionCookie::set)
        cookies.hathPerks?.let(hathPerksCookie::set)
    }

    private fun readState(): EhSessionState {
        val hasAnyCredential = memberId.get().isNotEmpty() || passHash.get().isNotEmpty() || igneous.get().isNotEmpty()
        if (!hasAnyCredential) return EhSessionState.LoggedOut
        val credentials =
            readCredentials().getOrElse {
                return EhSessionState.InvalidCredentials(null, it.message ?: "Stored credentials are invalid.")
            }
        return when (verificationStatus.get()) {
            STATUS_VERIFIED ->
                if (verificationFingerprint.get() == fingerprint(credentials) && verifiedAt.get() > 0) {
                    EhSessionState.Verified(credentials, verifiedAt.get())
                } else {
                    EhSessionState.CredentialsAvailable(credentials)
                }
            STATUS_INVALID -> EhSessionState.InvalidCredentials(credentials, invalidReason.get().ifBlank { "The server rejected the credentials." })
            else -> EhSessionState.CredentialsAvailable(credentials)
        }
    }

    private fun readCredentials(): Result<EhCredentials> =
        EhLoginCookies(memberId.get(), passHash.get(), igneous.get()).toCredentials()

    private fun optionalSecret(
        name: String,
        value: String,
    ): Pair<String, String>? =
        value
            .takeIf(String::isNotBlank)
            ?.let { runCatching { name to EhCookieValue.secret(name, it) }.getOrNull() }

    private fun fingerprint(credentials: EhCredentials): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("${credentials.memberId}\u0000${credentials.passHash}\u0000${credentials.igneous}".toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun <T : EhSessionState> publish(state: T): EhSessionMutationResult.Success {
        mutableState.value = state
        return EhSessionMutationResult.Success(state)
    }

    companion object {
        val KEY_ENABLE_EXHENTAI = Preference.privateKey("enable_exhentai")
        val KEY_MEMBER_ID = Preference.privateKey("eh_ipb_member_id")
        val KEY_PASS_HASH = Preference.privateKey("eh_ipb_pass_hash")
        val KEY_IGNEOUS = Preference.privateKey("eh_igneous")
        val KEY_EH_SETTINGS_PROFILE = Preference.privateKey("eh_ehSettingsProfile")
        val KEY_EXH_SETTINGS_PROFILE = Preference.privateKey("eh_exhSettingsProfile")
        val KEY_SETTINGS_KEY = Preference.privateKey("eh_settingsKey")
        val KEY_SESSION_COOKIE = Preference.privateKey("eh_sessionCookie")
        val KEY_HATH_PERKS_COOKIE = Preference.privateKey("eh_hathPerksCookie")
        val KEY_VERIFICATION_STATUS = Preference.privateKey("hayai_eh_verification_status")
        val KEY_VERIFICATION_FINGERPRINT = Preference.privateKey("hayai_eh_verification_fingerprint")
        val KEY_VERIFIED_AT = Preference.privateKey("hayai_eh_verified_at")
        val KEY_INVALID_REASON = Preference.privateKey("hayai_eh_invalid_reason")

        private const val STATUS_UNVERIFIED = "unverified"
        private const val STATUS_VERIFIED = "verified"
        private const val STATUS_INVALID = "invalid"
    }
}
