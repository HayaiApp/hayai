package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.ahmedmohamed.hayai.network.CloudflareHelpDetector
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toast
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CountDownLatch

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {
    private val executor = ContextCompat.getMainExecutor(context)

    override fun shouldIntercept(response: Response): Boolean {
        return response.isCloudflareChallenge()
    }

    override fun intercept(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
    ): Response {
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            val oldCookie =
                cookieManager
                    .get(request.url)
                    .firstOrNull { it.name == "cf_clearance" }
            resolveWithWebView(request, oldCookie)

            return chain.proceed(request)
            // Because OkHttp's enqueue only handles IOExceptions, wrap the exception so that
            // we don't crash the entire app
        } catch (e: CloudflareBypassException) {
            throw IOException(context.getString(R.string.failed_to_bypass_cloudflare), e)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(
        originalRequest: Request,
        oldCookie: Cookie?,
    ) {
        // We need to lock this thread until the WebView finds the challenge solution url, because
        // OkHttp doesn't support asynchronous interceptors.
        val latch = CountDownLatch(1)

        var webView: WebView? = null

        var challengeFound = false
        var cloudflareBypassed = false
        var isWebViewOutdated = false

        val origRequestUrl = originalRequest.url.toString()
        val headers = parseHeaders(originalRequest.headers)

        executor.execute {
            val challengeWebView = createWebView(originalRequest)
            webView = challengeWebView

            challengeWebView.addJavascriptInterface(
                object {
                    @Suppress("unused")
                    @JavascriptInterface
                    fun interactiveDetected() {
                        latch.countDown()
                    }
                },
                "mihon",
            )

            challengeWebView.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        url: String,
                    ) {
                        fun isCloudFlareBypassed(): Boolean =
                            cookieManager
                                .get(origRequestUrl.toHttpUrl())
                                .firstOrNull { it.name == "cf_clearance" }
                                .let { it != null && it != oldCookie }

                        if (isCloudFlareBypassed()) {
                            cloudflareBypassed = true
                            latch.countDown()
                        }

                        if (url == origRequestUrl) {
                            if (!challengeFound) {
                                latch.countDown()
                            } else {
                                view.evaluateJavascript(
                                    """
                                    addEventListener("message", ({data}) => {
                                        if (data?.source === "cloudflare-challenge" && data?.event === "interactiveBegin") {
                                            mihon.interactiveDetected();
                                        }
                                    })
                                    """.trimIndent(),
                                    null,
                                )
                            }
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            if (errorResponse != null && CloudflareHelpDetector.isChallengeResponse(errorResponse.statusCode, errorResponse.responseHeaders.orEmpty().mapValues { listOf(it.value) })) {
                                challengeFound = true
                            } else {
                                latch.countDown()
                            }
                        }
                    }
                }

            challengeWebView.loadUrl(origRequestUrl, headers)
        }

        latch.awaitFor30Seconds()

        executor.execute {
            if (!cloudflareBypassed) {
                isWebViewOutdated = webView?.isOutdated() == true
            }

            webView?.run {
                stopLoading()
                destroy()
            }
        }

        // Throw exception if we failed to bypass Cloudflare
        if (!cloudflareBypassed) {
            // Prompt user to update WebView if it seems too outdated
            if (isWebViewOutdated) {
                context.toast(R.string.please_update_webview, Toast.LENGTH_LONG)
            }

            throw CloudflareBypassException()
        }
    }
}

internal fun Response.isCloudflareChallenge(): Boolean =
    CloudflareHelpDetector.isChallengeResponse(code, headers.toMultimap())

private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()
