package dev.ahmedmohamed.hayai.adult.eh.uconfig

import dev.ahmedmohamed.hayai.adult.eh.domain.EhSite
import dev.ahmedmohamed.hayai.adult.eh.network.withEhBrowserCookies
import dev.ahmedmohamed.hayai.adult.eh.session.EhCookieHeader
import dev.ahmedmohamed.hayai.adult.eh.settings.EhRemoteSettings
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CancellationException
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.Reader

interface EhRemoteSettingsRemote {
    suspend fun fetchHathPerks(cookie: EhCookieHeader): EhHathPerks

    suspend fun profiles(site: EhSite, cookie: EhCookieHeader): List<EhRemoteProfile>

    suspend fun createProfile(
        site: EhSite,
        slot: EhProfileSlot,
        name: String,
        cookie: EhCookieHeader,
    ): EhRemoteCookies

    suspend fun applyProfile(
        site: EhSite,
        slot: EhProfileSlot,
        settings: EhRemoteSettings,
        perks: EhHathPerks,
        cookie: EhCookieHeader,
    ): EhRemoteCookies
}

class EhUConfigHttpRemote(
    client: OkHttpClient,
    private val uconfigUrl: (EhSite) -> String = { it.baseUrl + "/uconfig.php" },
    private val hathPerksUrl: String = "https://e-hentai.org/hathperks.php",
) : EhRemoteSettingsRemote {
    private val client = client.withEhBrowserCookies()

    override suspend fun fetchHathPerks(cookie: EhCookieHeader): EhHathPerks =
        executeDocument(request(hathPerksUrl, cookieForSlot(cookie, EhProfileSlot(1))), EhUConfigHtmlParser::hathPerks)

    override suspend fun profiles(site: EhSite, cookie: EhCookieHeader): List<EhRemoteProfile> =
        executeDocument(request(uconfigUrl(site), cookie), EhUConfigHtmlParser::profiles)

    override suspend fun createProfile(
        site: EhSite,
        slot: EhProfileSlot,
        name: String,
        cookie: EhCookieHeader,
    ): EhRemoteCookies {
        require(name.isNotBlank() && name.length <= 128)
        val body = FormBody.Builder()
            .add("profile_action", "create")
            .add("profile_name", name)
            .add("profile_set", slot.value.toString())
            .build()
        return execute(request(uconfigUrl(site), cookieForSlot(cookie, EhProfileSlot(1)), body)) { response ->
            val cookies = EhRemoteCookieParser.parse(response.headers.values("Set-Cookie"))
            EhUConfigHtmlParser.profiles(response.body.charStream().use { it.readBounded(EhUConfigHtmlParser.MAX_DOCUMENT_CHARS) })
            cookies
        }
    }

    override suspend fun applyProfile(
        site: EhSite,
        slot: EhProfileSlot,
        settings: EhRemoteSettings,
        perks: EhHathPerks,
        cookie: EhCookieHeader,
    ): EhRemoteCookies =
        execute(request(uconfigUrl(site), cookieForSlot(cookie, slot), EhUConfigFormCodec.form(settings, perks))) { response ->
            val cookies = EhRemoteCookieParser.parse(response.headers.values("Set-Cookie"))
            val expected = EhUConfigFormCodec.entries(settings, perks).toMap()
            val actual = EhUConfigHtmlParser.settingsValues(
                response.body.charStream().use { it.readBounded(EhUConfigHtmlParser.MAX_DOCUMENT_CHARS) },
                expected.keys,
            )
            val mismatch = actual.entries.firstOrNull { (key, value) -> expected[key] != value }
            require(mismatch == null) { "E-Hentai did not retain the ${mismatch?.key} setting." }
            cookies
        }

    private fun request(
        url: String,
        cookie: EhCookieHeader,
        body: FormBody? = null,
    ): Request =
        Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Cookie", cookie.value)
            .header("Referer", url)
            .apply { if (body != null) post(body) }
            .build()

    private fun cookieForSlot(header: EhCookieHeader, slot: EhProfileSlot): EhCookieHeader {
        val values = linkedMapOf<String, String>()
        header.value.split(';').forEach { part ->
            val separator = part.indexOf('=')
            if (separator > 0) values[part.substring(0, separator).trim()] = part.substring(separator + 1).trim()
        }
        values["sp"] = slot.value.toString()
        return EhCookieHeader(values.entries.joinToString("; ") { (name, value) -> "$name=$value" })
    }

    private suspend fun <T> executeDocument(
        request: Request,
        parser: (String) -> T,
    ): T = execute(request) { response -> parser(response.body.charStream().use { it.readBounded(EhUConfigHtmlParser.MAX_DOCUMENT_CHARS) }) }

    private suspend fun <T> execute(request: Request, block: (Response) -> T): T =
        try {
            client.newCall(request).awaitSuccess().use(block)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: HttpException) {
            throw when (failure.code) {
                401, 403 -> EhRemoteSettingsFailure.AuthenticationRequired()
                429 -> EhRemoteSettingsFailure.RateLimited(null)
                else -> EhRemoteSettingsFailure.RemoteRejected(failure.code)
            }
        } catch (failure: IllegalArgumentException) {
            throw EhRemoteSettingsFailure.MalformedResponse(failure.message ?: "E-Hentai returned malformed settings data.", failure)
        } catch (failure: IOException) {
            throw EhRemoteSettingsFailure.Network("The E-Hentai settings request failed.", failure)
        }

    private fun Reader.readBounded(maxChars: Int): String {
        val output = StringBuilder(minOf(maxChars, 8192))
        val buffer = CharArray(8192)
        while (output.length <= maxChars) {
            val remaining = maxChars + 1 - output.length
            val count = read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.append(buffer, 0, count)
        }
        if (output.length > maxChars) throw IllegalArgumentException("E-Hentai settings response is too large.")
        return output.toString()
    }
}
