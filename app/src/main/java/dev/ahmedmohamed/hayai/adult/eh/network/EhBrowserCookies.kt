package dev.ahmedmohamed.hayai.adult.eh.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.OkHttpClient

/** Shares Cloudflare cookies with the upstream WebView jar without replacing typed EH credentials. */
internal fun OkHttpClient.withEhBrowserCookies(): OkHttpClient {
    val browserCookies = cookieJar
    return newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val cookies = request.header("Cookie").orEmpty().split(';')
                .map(String::trim).filter(String::isNotEmpty)
                .filterNot { isCloudflareCookie(it.substringBefore('=')) }.toMutableList()
            browserCookies.loadForRequest(request.url).filter { isCloudflareCookie(it.name) }
                .forEach { cookies += "${it.name}=${it.value}" }
            val outgoing = request.newBuilder().removeHeader("Cookie").apply {
                if (cookies.isNotEmpty()) header("Cookie", cookies.joinToString("; "))
            }.build()
            chain.proceed(outgoing).also { response ->
                val received = Cookie.parseAll(request.url, response.headers).filter { isCloudflareCookie(it.name) }
                if (received.isNotEmpty()) browserCookies.saveFromResponse(request.url, received)
            }
        }
        .build()
}

private fun isCloudflareCookie(name: String): Boolean = name == "cf_clearance" || name.startsWith("__cf")
