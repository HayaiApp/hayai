package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.TimeUnit

class NetworkHelper(
    val context: Context,
) {
    private val preferences: PreferencesHelper by injectLazy()

    private val cacheDir = File(context.cacheDir, "network_cache")

    private val cacheSize = 5L * 1024 * 1024 // 5 MiB

    val cookieJar = AndroidCookieJar()

    private val userAgentInterceptor by lazy { UserAgentInterceptor(::defaultUserAgent) }
    private val cloudflareInterceptor by lazy {
        CloudflareInterceptor(context, cookieJar, ::defaultUserAgent)
    }

    private val baseClientBuilder: OkHttpClient.Builder
        get() {
            val builder =
                OkHttpClient
                    .Builder()
                    .cookieJar(cookieJar)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(2, TimeUnit.MINUTES)
                    .addInterceptor(UncaughtExceptionInterceptor())
                    .addInterceptor(userAgentInterceptor)
                    .apply {
//                    if (BuildConfig.DEBUG) {
//                        addInterceptor(
//                            ChuckerInterceptor.Builder(context)
//                                .collector(ChuckerCollector(context))
//                                .maxContentLength(250000L)
//                                .redactHeaders(emptySet())
//                                .alwaysReadResponseBody(false)
//                                .build(),
//                        )
//                    }

                        when (preferences.dohProvider()) {
                            PREF_DOH_CLOUDFLARE -> dohCloudflare()
                            PREF_DOH_GOOGLE -> dohGoogle()
                            PREF_DOH_ADGUARD -> dohAdGuard()
                            PREF_DOH_QUAD9 -> dohQuad9()
                        }
                    }

            return builder
        }

    val client by lazy {
        baseClientBuilder
            .cache(Cache(cacheDir, cacheSize))
            .addInterceptor(cloudflareInterceptor)
            .build()
    }

    /**
     * @deprecated The regular client handles Cloudflare by default since extensions-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient
        get() = client

    val defaultUserAgent
        get() =
            preferences
                .defaultUserAgent()
                .get()
                .replace("\n", " ")
                .trim()

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"
    }
}
