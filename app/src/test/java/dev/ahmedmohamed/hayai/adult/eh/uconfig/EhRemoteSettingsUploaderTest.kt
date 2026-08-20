package dev.ahmedmohamed.hayai.adult.eh.uconfig

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.session.EhCookieHeader
import dev.ahmedmohamed.hayai.adult.eh.session.EhCookieStore
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionStore
import dev.ahmedmohamed.hayai.adult.eh.settings.EhPreferences
import dev.ahmedmohamed.hayai.adult.eh.settings.EhRemoteSettings
import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EhRemoteSettingsUploaderTest {
    @Test
    fun `partial failure commits success and retry reuses created profile`() = runBlocking {
        val preferenceStore = TestPreferenceStore()
        val sessions = EhSessionStore(preferenceStore, TestCookieStore())
        sessions.stage(listOf("ipb_member_id=12345; ipb_pass_hash=pass-token; igneous=igneous-token"))
        sessions.markVerified()
        val preferences = EhPreferences(preferenceStore)
        val remote = FakeRemote().apply {
            profiles.getValue(EhSite.EHentai) += EhRemoteProfile(EhProfileSlot(2), "TachiyomiEH App")
            failApply += EhSite.ExHentai
        }
        val uploader = EhRemoteSettingsUploader(remote, sessions, preferences)
        val desired = preferences.remoteSettings()

        val first = uploader.upload(desired)

        assertEquals(setOf(EhSite.ExHentai), first.failedSites)
        assertEquals(2, sessions.settingsProfile(EhSite.EHentai))
        assertEquals(null, sessions.settingsProfile(EhSite.ExHentai))
        assertEquals(1, remote.createCalls[EhSite.ExHentai])
        assertEquals(desired.fingerprint(), preferences.appliedFingerprint(EhSite.EHentai))

        remote.failApply.clear()
        val retry = uploader.upload(desired, first.failedSites)

        assertTrue(retry.failedSites.isEmpty())
        assertEquals(1, sessions.settingsProfile(EhSite.ExHentai))
        assertEquals(1, remote.createCalls[EhSite.ExHentai])
        assertEquals(desired.fingerprint(), preferences.appliedFingerprint(EhSite.ExHentai))
    }

    @Test
    fun `occupied profile slots return typed failure without deletion`() = runBlocking {
        val preferenceStore = TestPreferenceStore()
        val sessions = EhSessionStore(preferenceStore, TestCookieStore())
        sessions.stage(listOf("ipb_member_id=12345; ipb_pass_hash=pass-token; igneous=igneous-token"))
        sessions.markVerified()
        val preferences = EhPreferences(preferenceStore)
        val remote = FakeRemote().apply {
            profiles.getValue(EhSite.EHentai) += (1..3).map { EhRemoteProfile(EhProfileSlot(it), "Personal $it") }
        }
        val uploader = EhRemoteSettingsUploader(remote, sessions, preferences)

        val report = uploader.upload(preferences.remoteSettings(), setOf(EhSite.EHentai))

        assertTrue((report.results.getValue(EhSite.EHentai) as EhSiteUploadResult.Failed).failure is EhRemoteSettingsFailure.OutOfProfileSlots)
        assertEquals(null, remote.createCalls[EhSite.EHentai])
    }
}

private class FakeRemote : EhRemoteSettingsRemote {
    val profiles = EhSite.entries.associateWith { mutableListOf<EhRemoteProfile>() }
    val createCalls = mutableMapOf<EhSite, Int>()
    val failApply = mutableSetOf<EhSite>()

    override suspend fun fetchHathPerks(cookie: EhCookieHeader) = EhHathPerks()

    override suspend fun profiles(site: EhSite, cookie: EhCookieHeader): List<EhRemoteProfile> = profiles.getValue(site).toList()

    override suspend fun createProfile(
        site: EhSite,
        slot: EhProfileSlot,
        name: String,
        cookie: EhCookieHeader,
    ): EhRemoteCookies {
        createCalls[site] = createCalls.getOrDefault(site, 0) + 1
        profiles.getValue(site) += EhRemoteProfile(slot, name)
        return EhRemoteCookies(settingsKey = "settings-key")
    }

    override suspend fun applyProfile(
        site: EhSite,
        slot: EhProfileSlot,
        settings: EhRemoteSettings,
        perks: EhHathPerks,
        cookie: EhCookieHeader,
    ): EhRemoteCookies {
        if (site in failApply) throw EhRemoteSettingsFailure.Network("forced failure")
        return EhRemoteCookies(session = "session-key")
    }
}

private class TestCookieStore : EhCookieStore {
    override fun get(origin: String): String? = null
    override fun clearEhDomains() = Unit
}

private class TestPreferenceStore : PreferenceStore {
    private val values = mutableMapOf<String, Any>()
    private val preferences = mutableMapOf<String, TestPreference<*>>()

    override fun getString(key: String, defaultValue: String): Preference<String> = preference(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long): Preference<Long> = preference(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int): Preference<Int> = preference(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = preference(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = preference(key, defaultValue)
    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = preference(key, defaultValue)
    override fun <T> getObject(key: String, defaultValue: T, serializer: (T) -> String, deserializer: (String) -> T): Preference<T> =
        preference(key, defaultValue)
    override fun getAll(): Map<String, *> = values.toMap()

    @Suppress("UNCHECKED_CAST")
    private fun <T> preference(key: String, defaultValue: T): Preference<T> =
        preferences.getOrPut(key) {
            TestPreference(
                key,
                defaultValue,
                read = { values[key] as? T ?: defaultValue },
                write = { values[key] = it as Any },
                remove = { values.remove(key) },
            )
        } as Preference<T>
}

private class TestPreference<T>(
    private val key: String,
    private val defaultValue: T,
    private val read: () -> T,
    private val write: (T) -> Unit,
    private val remove: () -> Unit,
) : Preference<T> {
    private val state = MutableStateFlow(read())
    override fun key(): String = key
    override fun get(): T = read()
    override fun set(value: T) { write(value); state.value = value }
    override fun isSet(): Boolean = read() != defaultValue
    override fun delete() { remove(); state.value = defaultValue }
    override fun defaultValue(): T = defaultValue
    override fun changes(): Flow<T> = state
    override fun stateIn(scope: CoroutineScope): StateFlow<T> = state
}
