package dev.ahmedmohamed.hayai.adult.eh.session

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.uconfig.EhProfileSlot
import dev.ahmedmohamed.hayai.adult.eh.uconfig.EhRemoteCookies
import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EhSessionStoreTest {
    @Test
    fun `SY private preference keys remain compatible`() {
        assertEquals("__PRIVATE_enable_exhentai", EhSessionStore.KEY_ENABLE_EXHENTAI)
        assertEquals("__PRIVATE_eh_ipb_member_id", EhSessionStore.KEY_MEMBER_ID)
        assertEquals("__PRIVATE_eh_ipb_pass_hash", EhSessionStore.KEY_PASS_HASH)
        assertEquals("__PRIVATE_eh_igneous", EhSessionStore.KEY_IGNEOUS)
        assertEquals("__PRIVATE_eh_ehSettingsProfile", EhSessionStore.KEY_EH_SETTINGS_PROFILE)
        assertEquals("__PRIVATE_eh_exhSettingsProfile", EhSessionStore.KEY_EXH_SETTINGS_PROFILE)
        assertEquals("__PRIVATE_eh_settingsKey", EhSessionStore.KEY_SETTINGS_KEY)
        assertEquals("__PRIVATE_eh_sessionCookie", EhSessionStore.KEY_SESSION_COOKIE)
        assertEquals("__PRIVATE_eh_hathPerksCookie", EhSessionStore.KEY_HATH_PERKS_COOKIE)
    }

    @Test
    fun `legacy credentials start unverified`() {
        val preferences =
            FakePreferenceStore(
                EhSessionStore.KEY_MEMBER_ID to "12345",
                EhSessionStore.KEY_PASS_HASH to "abcdef012345",
                EhSessionStore.KEY_IGNEOUS to "igneous-token",
                EhSessionStore.KEY_ENABLE_EXHENTAI to true,
            )

        val state = EhSessionStore(preferences, FakeCookieStore()).state.value

        assertTrue(state is EhSessionState.CredentialsAvailable)
    }

    @Test
    fun `staged cookies stay unavailable until server verification`() {
        val preferences = FakePreferenceStore()
        val store = EhSessionStore(preferences, FakeCookieStore(), clock = { 1_700_000_000_000 })

        val staged =
            store.stage(
                listOf("ipb_member_id=12345; ipb_pass_hash=abcDEF012345; igneous=token_123"),
            )

        assertTrue(staged is EhSessionMutationResult.Success)
        assertTrue(store.state.value is EhSessionState.CredentialsAvailable)
        assertFalse(preferences.value<Boolean>(EhSessionStore.KEY_ENABLE_EXHENTAI) ?: true)

        val verified = store.markVerified()

        assertTrue(verified is EhSessionMutationResult.Success)
        assertEquals(1_700_000_000_000, (store.state.value as EhSessionState.Verified).verifiedAtEpochMillis)
        assertTrue(preferences.value(EhSessionStore.KEY_ENABLE_EXHENTAI) ?: false)
    }

    @Test
    fun `manual igneous completes a login missing the site cookie`() {
        val store = EhSessionStore(FakePreferenceStore(), FakeCookieStore())

        val result =
            store.stage(
                listOf("ipb_member_id=12345; ipb_pass_hash=abcDEF012345"),
                manualIgneous = "manual-token",
            )

        assertTrue(result is EhSessionMutationResult.Success)
        val credentials = (store.state.value as EhSessionState.CredentialsAvailable).credentials
        assertEquals("manual-token", credentials.igneous)
    }

    @Test
    fun `per-request header is deterministic and includes the selected site profile`() {
        val preferences =
            FakePreferenceStore(
                EhSessionStore.KEY_SETTINGS_KEY to "settings-key",
                EhSessionStore.KEY_SESSION_COOKIE to "session-token",
                EhSessionStore.KEY_HATH_PERKS_COOKIE to "perks-token",
            )
        val store = EhSessionStore(preferences, FakeCookieStore())
        store.stage(listOf("ipb_member_id=12345; ipb_pass_hash=abcDEF012345; igneous=token_123"))
        store.setSettingsProfile(EhSite.ExHentai, 2)

        assertEquals(
            "ipb_member_id=12345; ipb_pass_hash=abcDEF012345; igneous=token_123; sp=2; " +
                "sk=settings-key; s=session-token; hath_perks=perks-token; sl=dm_2; nw=1",
            store.cookieHeader(EhSite.ExHentai).value,
        )
        assertFalse(store.cookieHeader(EhSite.EHentai).value.contains("sp="))
    }

    @Test
    fun `malformed stored secrets are never sent`() {
        val preferences =
            FakePreferenceStore(
                EhSessionStore.KEY_MEMBER_ID to "not-a-number",
                EhSessionStore.KEY_PASS_HASH to "safe",
                EhSessionStore.KEY_IGNEOUS to "unsafe; injected=true",
            )
        val store = EhSessionStore(preferences, FakeCookieStore())

        assertTrue(store.state.value is EhSessionState.InvalidCredentials)
        assertEquals("sl=dm_2; nw=1", store.cookieHeader(EhSite.ExHentai).value)
    }

    @Test
    fun `logout removes only EH secrets and retains profiles and unrelated preferences`() {
        val preferences =
            FakePreferenceStore(
                EhSessionStore.KEY_MEMBER_ID to "12345",
                EhSessionStore.KEY_PASS_HASH to "abcdef012345",
                EhSessionStore.KEY_IGNEOUS to "igneous-token",
                EhSessionStore.KEY_SETTINGS_KEY to "settings-key",
                EhSessionStore.KEY_SESSION_COOKIE to "session-token",
                EhSessionStore.KEY_HATH_PERKS_COOKIE to "perks-token",
                EhSessionStore.KEY_EH_SETTINGS_PROFILE to 1,
                EhSessionStore.KEY_EXH_SETTINGS_PROFILE to 2,
                "unrelated" to "keep-me",
            )
        val cookies = FakeCookieStore()
        val store = EhSessionStore(preferences, cookies)

        store.logout()

        assertEquals(EhSessionState.LoggedOut, store.state.value)
        assertNull(preferences.value<String>(EhSessionStore.KEY_MEMBER_ID))
        assertNull(preferences.value<String>(EhSessionStore.KEY_PASS_HASH))
        assertNull(preferences.value<String>(EhSessionStore.KEY_IGNEOUS))
        assertEquals(1, preferences.value<Int>(EhSessionStore.KEY_EH_SETTINGS_PROFILE))
        assertEquals(2, preferences.value<Int>(EhSessionStore.KEY_EXH_SETTINGS_PROFILE))
        assertEquals("keep-me", preferences.value<String>("unrelated"))
        assertEquals(1, cookies.clearCount)
    }

    @Test
    fun `remote profile commit retains absent cookies and updates returned cookies`() {
        val preferences =
            FakePreferenceStore(
                EhSessionStore.KEY_SETTINGS_KEY to "old-settings",
                EhSessionStore.KEY_SESSION_COOKIE to "old-session",
                EhSessionStore.KEY_HATH_PERKS_COOKIE to "old-perks",
            )
        val store = EhSessionStore(preferences, FakeCookieStore())

        store.commitRemoteProfile(
            EhSite.ExHentai,
            EhProfileSlot(3),
            EhRemoteCookies(settingsKey = "new-settings"),
        )

        assertEquals(3, store.settingsProfile(EhSite.ExHentai))
        assertEquals("new-settings", preferences.value<String>(EhSessionStore.KEY_SETTINGS_KEY))
        assertEquals("old-session", preferences.value<String>(EhSessionStore.KEY_SESSION_COOKIE))
        assertEquals("old-perks", preferences.value<String>(EhSessionStore.KEY_HATH_PERKS_COOKIE))
    }
}

private class FakeCookieStore : EhCookieStore {
    var clearCount = 0

    override fun get(origin: String): String? = null

    override fun clearEhDomains() {
        clearCount++
    }
}

private class FakePreferenceStore(
    vararg initial: Pair<String, Any>,
) : PreferenceStore {
    private val values = initial.toMap().toMutableMap()
    private val preferences = mutableMapOf<String, FakePreference<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> value(key: String): T? = values[key] as? T

    override fun getString(
        key: String,
        defaultValue: String,
    ): Preference<String> = preference(key, defaultValue)

    override fun getLong(
        key: String,
        defaultValue: Long,
    ): Preference<Long> = preference(key, defaultValue)

    override fun getInt(
        key: String,
        defaultValue: Int,
    ): Preference<Int> = preference(key, defaultValue)

    override fun getFloat(
        key: String,
        defaultValue: Float,
    ): Preference<Float> = preference(key, defaultValue)

    override fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Preference<Boolean> = preference(key, defaultValue)

    override fun getStringSet(
        key: String,
        defaultValue: Set<String>,
    ): Preference<Set<String>> = preference(key, defaultValue)

    override fun <T> getObject(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = preference(key, defaultValue)

    override fun getAll(): Map<String, *> = values.toMap()

    @Suppress("UNCHECKED_CAST")
    private fun <T> preference(
        key: String,
        defaultValue: T,
    ): Preference<T> =
        preferences.getOrPut(key) {
            FakePreference(
                key = key,
                defaultValue = defaultValue,
                read = { values[key] as? T ?: defaultValue },
                write = { values[key] = it as Any },
                remove = { values.remove(key) },
                contains = { key in values },
            )
        } as Preference<T>
}

private class FakePreference<T>(
    private val key: String,
    private val defaultValue: T,
    private val read: () -> T,
    private val write: (T) -> Unit,
    private val remove: () -> Unit,
    private val contains: () -> Boolean,
) : Preference<T> {
    private val changes = MutableStateFlow(read())

    override fun key(): String = key

    override fun get(): T = read()

    override fun set(value: T) {
        write(value)
        changes.value = value
    }

    override fun isSet(): Boolean = contains()

    override fun delete() {
        remove()
        changes.value = defaultValue
    }

    override fun defaultValue(): T = defaultValue

    override fun changes(): Flow<T> = changes

    override fun stateIn(scope: CoroutineScope): StateFlow<T> = changes
}
