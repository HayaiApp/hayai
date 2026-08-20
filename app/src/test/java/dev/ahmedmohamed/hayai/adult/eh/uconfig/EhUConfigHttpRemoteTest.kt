package dev.ahmedmohamed.hayai.adult.eh.uconfig

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.session.EhCookieHeader
import dev.ahmedmohamed.hayai.adult.eh.settings.EhHentaiAtHome
import dev.ahmedmohamed.hayai.adult.eh.settings.EhImageQuality
import dev.ahmedmohamed.hayai.adult.eh.settings.EhLanguage
import dev.ahmedmohamed.hayai.adult.eh.settings.EhLanguageSelection
import dev.ahmedmohamed.hayai.adult.eh.settings.EhRemoteSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EhUConfigHttpRemoteTest {
    private lateinit var server: MockWebServer
    private lateinit var remote: EhUConfigHttpRemote

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        remote = EhUConfigHttpRemote(
            client = OkHttpClient(),
            uconfigUrl = { server.url("/uconfig.php").toString() },
            hathPerksUrl = server.url("/hathperks.php").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `create sends profile action and captures returned cookies`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "sk=settings-key; Path=/")
                .addHeader("Set-Cookie", "s=session-key; Path=/")
                .setBody(profilePage()),
        )

        val cookies = remote.createProfile(EhSite.EHentai, EhProfileSlot(2), "Hayai App", EhCookieHeader("ipb_member_id=1"))
        val request = server.takeRequest()

        assertEquals("settings-key", cookies.settingsKey)
        assertEquals("session-key", cookies.session)
        assertEquals("1", request.headers["Cookie"]?.substringAfter("sp=")?.substringBefore(';'))
        val body = request.body.readUtf8()
        assertTrue("profile_action=create" in body)
        assertTrue("profile_name=Hayai%20App" in body || "profile_name=Hayai+App" in body)
        assertTrue("profile_set=2" in body)
    }

    @Test
    fun `apply selects target slot and posts complete form`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(profilePage()))
        remote.applyProfile(EhSite.ExHentai, EhProfileSlot(3), settings(), EhHathPerks(), EhCookieHeader("sk=value; sp=1"))
        val request = server.takeRequest()

        assertTrue(request.headers["Cookie"].orEmpty().contains("sp=3"))
        val body = request.body.readUtf8()
        assertTrue("xr=0" in body)
        assertTrue("dm=2" in body)
        assertTrue("ct_misc=0" in body)
        assertTrue("apply=Apply" in body)
    }

    private fun settings() = EhRemoteSettings(
        imageQuality = EhImageQuality.Auto,
        hentaiAtHome = EhHentaiAtHome.Any,
        japaneseTitles = false,
        originalImages = false,
        tagFilterThreshold = 0,
        tagWatchingThreshold = 0,
        languages = EhLanguage.entries.associateWith { EhLanguageSelection() },
        excludedCategories = emptySet(),
    )

    private fun profilePage() =
        """
        <select name="profile_set"><option value="1">Hayai App</option></select>
        """.trimIndent()
}
