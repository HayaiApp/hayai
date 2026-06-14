package exh.ui.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.util.view.setComposeContent
import exh.source.ExhPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import yokai.util.koin.injectLazy
import java.net.HttpCookie
import java.util.Locale

/**
 * LoginController
 */
class EhLoginActivity : AppCompatActivity() {
    private val exhPreferences: ExhPreferences by injectLazy()
    private val logger = Logger.withTag("EhLoginActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setComposeContent {
            var showIgneousDialog by remember { mutableStateOf(false) }
            var lastWebView by remember { mutableStateOf<WebView?>(null) }

            EhLoginWebViewScreen(
                onUp = { finish() },
                onPageFinished = { view, url ->
                    lastWebView = view
                    onPageFinished(view, url, null)
                },
                onClickRecheckLoginStatus = { loadUrl -> loadUrl("https://exhentai.org/") },
                onClickAlternateLoginPage = { loadUrl -> loadUrl("https://e-hentai.org/bounce_login.php") },
                onClickSkipPageRestyling = { loadUrl ->
                    loadUrl("https://forums.e-hentai.org/index.php?act=Login&$PARAM_SKIP_INJECT=true")
                },
                onClickCustomIgneousCookie = {
                    showIgneousDialog = true
                },
                onClickRefreshIgneousCookie = {
                    refreshIgneousCookie()
                },
            )

            if (showIgneousDialog) {
                IgneousDialog(
                    onDismissRequest = { showIgneousDialog = false },
                    onIgneousSet = { igneous ->
                        lastWebView?.let { webView ->
                            onPageFinished(webView, "https://exhentai.org/", igneous)
                        }
                    },
                )
            }
        }
    }

    /**
     * Refresh the igneous cookie by making a direct request to ExHentai with only
     * ipb_member_id and ipb_pass_hash cookies (JHenTai approach).
     * The server will set a fresh igneous cookie in the Set-Cookie response header.
     */
    private fun refreshIgneousCookie() {
        val memberId = exhPreferences.memberIdVal.get()
        val passHash = exhPreferences.passHashVal.get()

        if (memberId.isBlank() || passHash.isBlank()) {
            Toast.makeText(this, "Not logged in to E-Hentai. Please log in first.", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            try {
                val newIgneous = withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url("https://exhentai.org/")
                        .addHeader("Cookie", "$MEMBER_ID_COOKIE=$memberId; $PASS_HASH_COOKIE=$passHash")
                        .build()
                    val response = client.newCall(request).execute()
                    response.use {
                        val setCookieHeaders = response.headers.values("Set-Cookie")
                        setCookieHeaders.mapNotNull { header ->
                            val pairs = header.split(";")
                            pairs.firstNotNullOfOrNull { pair ->
                                val kv = pair.trim().split("=", limit = 2)
                                if (kv.size == 2 && kv[0].equals(IGNEOUS_COOKIE, ignoreCase = true)) {
                                    kv[1]
                                } else null
                            }
                        }.firstOrNull()
                    }
                }

                if (newIgneous == null || newIgneous == "mystery" || newIgneous == "deleted") {
                    Toast.makeText(this@EhLoginActivity, "Failed to refresh igneous cookie. Make sure you have ExHentai access.", Toast.LENGTH_LONG).show()
                } else {
                    exhPreferences.igneousVal.set(newIgneous)
                    Toast.makeText(this@EhLoginActivity, "Igneous cookie refreshed successfully!", Toast.LENGTH_LONG).show()
                    logger.d { "Igneous cookie refreshed: $newIgneous" }
                }
            } catch (e: Exception) {
                logger.e(e) { "Failed to refresh igneous cookie" }
                Toast.makeText(this@EhLoginActivity, "Failed to refresh igneous cookie: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun onPageFinished(view: WebView, url: String, customIgneous: String?) {
        logger.d { url }
        val parsedUrl = url.toUri()
        if (parsedUrl.host.equals("forums.e-hentai.org", ignoreCase = true)) {
            view.evaluateJavascript(
                """
                    (function() {
                        let html = document.documentElement.innerHTML;
                        return html.includes("/cdn-cgi/");
                    })();
                """.trimIndent(),
            ) { result ->
                val isCloudflareBlock = result == "true"

                if (isCloudflareBlock) {
                    logger.d { "Cloudflare block detected - skipping logic" }
                    return@evaluateJavascript
                }

                // Hide distracting content
                if (!parsedUrl.queryParameterNames.contains(PARAM_SKIP_INJECT)) {
                    view.evaluateJavascript(HIDE_JS, null)
                }
                // Check login result

                if (parsedUrl.getQueryParameter("code")?.toInt() != 0) {
                    if (checkLoginCookies(url)) view.loadUrl("https://exhentai.org/")
                }
            }
        } else if (parsedUrl.host.equals("exhentai.org", ignoreCase = true)) {
            // At ExHentai, check that everything worked out...
            if (applyExHentaiCookies(url, customIgneous)) {
                exhPreferences.enableExhentai.set(true)
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    /**
     * Check if we are logged in
     */
    private fun checkLoginCookies(url: String): Boolean {
        getCookies(url)?.let { parsed ->
            return parsed.count {
                (
                    it.name.equals(MEMBER_ID_COOKIE, ignoreCase = true) ||
                        it.name.equals(PASS_HASH_COOKIE, ignoreCase = true)
                    ) &&
                    it.value.isNotBlank()
            } >= 2
        }
        return false
    }

    /**
     * Parse cookies at ExHentai
     */
    private fun applyExHentaiCookies(url: String, customIgneous: String?): Boolean {
        getCookies(url)?.let { parsed ->

            var memberId: String? = null
            var passHash: String? = null
            var igneous: String? = customIgneous

            if (customIgneous != null) {
                CookieManager.getInstance().setCookie(url, "$IGNEOUS_COOKIE=$customIgneous")
            }

            parsed.forEach {
                when (it.name.lowercase(Locale.getDefault())) {
                    MEMBER_ID_COOKIE -> memberId = it.value
                    PASS_HASH_COOKIE -> passHash = it.value
                    IGNEOUS_COOKIE -> igneous = customIgneous ?: it.value
                }
            }

            // Missing a cookie
            if (memberId == null || passHash == null || igneous == null) return false

            // "mystery" means ExHentai rejected login (Sad Panda) — don't store it
            if (igneous == "mystery" || igneous == "deleted") {
                Toast.makeText(
                    this,
                    "ExHentai login failed: igneous cookie is \"$igneous\". Check your account has ExHentai access, then try again.",
                    Toast.LENGTH_LONG,
                ).show()
                return false
            }

            // Update prefs
            exhPreferences.memberIdVal.set(memberId)
            exhPreferences.passHashVal.set(passHash)
            exhPreferences.igneousVal.set(igneous)

            return true
        }
        return false
    }

    private fun getCookies(url: String): List<HttpCookie>? =
        CookieManager.getInstance().getCookie(url)?.let { cookie ->
            cookie.split("; ").flatMap {
                HttpCookie.parse(it)
            }
        }

    override fun finish() {
        super.finish()
    }

    companion object {
        const val PARAM_SKIP_INJECT = "TEH_SKIP_INJECT"

        const val MEMBER_ID_COOKIE = "ipb_member_id"
        const val PASS_HASH_COOKIE = "ipb_pass_hash"
        const val IGNEOUS_COOKIE = "igneous"

        const val HIDE_JS =
            """
                    javascript:(function () {
                        document.getElementsByTagName('body')[0].style.visibility = 'hidden';
                        document.getElementsByName('submit')[0].style.visibility = 'visible';
                        document.querySelector('td[width="60%"][valign="top"]').style.visibility = 'visible';

                        function hide(e) {if(e != null) e.style.display = 'none';}

                        hide(document.querySelector(".errorwrap"));
                        hide(document.querySelector('td[width="40%"][valign="top"]'));
                        var child = document.querySelector(".page").querySelector('div');
                        child.style.padding = null;
                        var ft = child.querySelectorAll('table');
                        var fd = child.parentNode.querySelectorAll('div > div');
                        var fh = document.querySelector('#border').querySelectorAll('td > table');
                        hide(ft[0]);
                        hide(ft[1]);
                        hide(fd[1]);
                        hide(fd[2]);
                        hide(child.querySelector('br'));
                        var error = document.querySelector(".page > div > .borderwrap");
                        if(error != null) error.style.visibility = 'visible';
                        hide(fh[0]);
                        hide(fh[1]);
                        hide(document.querySelector("#gfooter"));
                        hide(document.querySelector(".copyright"));
                        document.querySelectorAll("td").forEach(function(e) {
                            e.style.color = "white";
                        });
                        var pc = document.querySelector(".postcolor");
                        if(pc != null) pc.style.color = "#26353F";
                    })()
                    """

        fun newIntent(context: Context): Intent {
            return Intent(context, EhLoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}
