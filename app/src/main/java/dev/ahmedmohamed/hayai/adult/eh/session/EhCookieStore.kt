package dev.ahmedmohamed.hayai.adult.eh.session

import android.webkit.CookieManager
import java.util.Locale

interface EhCookieStore {
    fun get(origin: String): String?

    fun clearEhDomains()
}

class AndroidEhCookieStore(
    private val manager: CookieManager = CookieManager.getInstance(),
) : EhCookieStore {
    override fun get(origin: String): String? = manager.getCookie(origin)

    override fun clearEhDomains() {
        EH_ORIGINS.forEach { origin ->
            val names =
                manager
                    .getCookie(origin)
                    .orEmpty()
                    .split(';')
                    .mapNotNull { part ->
                        part.substringBefore('=', missingDelimiterValue = "").trim().takeIf(::isCookieName)
                    }.toSet()
            names.forEach { name ->
                manager.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure; SameSite=Lax")
                cookieDomain(origin)?.let { domain ->
                    manager.setCookie(origin, "$name=; Max-Age=0; Domain=$domain; Path=/; Secure; SameSite=Lax")
                }
            }
        }
        manager.flush()
    }

    private fun cookieDomain(origin: String): String? =
        when (origin) {
            EH_ORIGIN, FORUMS_ORIGIN -> ".e-hentai.org"
            EXH_ORIGIN -> ".exhentai.org"
            else -> null
        }

    private fun isCookieName(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= 256 &&
            value.lowercase(Locale.ROOT).all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" }

    companion object {
        const val EH_ORIGIN = "https://e-hentai.org"
        const val EXH_ORIGIN = "https://exhentai.org"
        const val FORUMS_ORIGIN = "https://forums.e-hentai.org"
        val EH_ORIGINS = listOf(EH_ORIGIN, EXH_ORIGIN, FORUMS_ORIGIN)
    }
}
