package eu.kanade.tachiyomi.ui.setting.track

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.WebView
import com.kevinnzou.web.rememberWebViewNavigator
import com.kevinnzou.web.rememberWebViewState
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yokai.util.koin.injectLazy
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import android.webkit.WebView as AndroidWebView

/**
 * WebView-based login activity for trackers that use cookie/JWT authentication.
 *
 * Used for NovelUpdates (cookie session) and NovelList (base64-JSON cookie containing JWT).
 * Mirrors the [exh.ui.login.EhLoginActivity] pattern: Compose host using [setComposeContent],
 * Hayai's [YokaiScaffold] for the chrome, and the Accompanist WebView wrapper
 * (`com.kevinnzou.web.WebView`) so back-stack navigation/state hoisting matches the rest of
 * the app's WebView screens.
 */
class TrackerWebViewLoginActivity : AppCompatActivity() {

    private val trackManager: TrackManager by injectLazy()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!WebViewUtil.supportsWebView(this)) {
            toast(MR.strings.information_webview_required, Toast.LENGTH_LONG)
            finish()
            return
        }

        val trackerId = intent.extras?.getLong(TRACKER_ID_KEY, -1L) ?: -1L
        val trackerName = intent.extras?.getString(TRACKER_NAME_KEY) ?: return
        val loginUrl = intent.extras?.getString(LOGIN_URL_KEY) ?: return

        setComposeContent {
            TrackerWebViewLoginScreen(
                trackerId = trackerId,
                trackerName = trackerName,
                loginUrl = loginUrl,
                onLoginSuccess = { token ->
                    val tracker = when (trackerId) {
                        TrackManager.NOVEL_UPDATES -> trackManager.novelUpdates
                        TrackManager.NOVEL_LIST -> trackManager.novelList
                        else -> null
                    }
                    tracker?.let {
                        it.logout()
                        kotlinx.coroutines.runBlocking {
                            // Use "cookie_auth" as username since isLogged requires non-empty username.
                            it.login("cookie_auth", token)
                        }
                        toast(MR.strings.successfully_logged_in)
                        setResult(RESULT_OK)
                        finish()
                    }
                },
                onUp = { finish() },
            )
        }
    }

    companion object {
        private const val TRACKER_ID_KEY = "tracker_id_key"
        private const val TRACKER_NAME_KEY = "tracker_name_key"
        private const val LOGIN_URL_KEY = "login_url_key"

        fun newIntent(context: Context, trackerId: Long, trackerName: String, loginUrl: String): Intent {
            return Intent(context, TrackerWebViewLoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(TRACKER_ID_KEY, trackerId)
                putExtra(TRACKER_NAME_KEY, trackerName)
                putExtra(LOGIN_URL_KEY, loginUrl)
            }
        }
    }
}

@Composable
private fun TrackerWebViewLoginScreen(
    trackerId: Long,
    trackerName: String,
    loginUrl: String,
    onLoginSuccess: (String) -> Unit,
    onUp: () -> Unit,
) {
    val state = rememberWebViewState(url = loginUrl)
    val navigator = rememberWebViewNavigator()
    val scope = rememberCoroutineScope()
    val currentOnLoginSuccess by rememberUpdatedState(onLoginSuccess)
    val currentOnUp by rememberUpdatedState(onUp)
    var currentUrl by remember(loginUrl) { mutableStateOf(loginUrl) }
    var isLoading by remember { mutableStateOf(true) }

    val extractToken: () -> Unit = {
        scope.launch {
            val token = extractTokenFromCookies(trackerId, currentUrl)
            if (token != null) {
                currentOnLoginSuccess(token)
            }
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    YokaiScaffold(
        onNavigationIconClicked = currentOnUp,
        title = stringResource(MR.strings.log_in_to_, trackerName),
        navigationIcon = Icons.Outlined.Close,
        appBarType = AppBarType.SMALL,
        actions = {
            // Escape hatch: open the current page in the system browser. Useful when
            // Google's `disallowed_useragent` check blocks the in-app sign-in despite
            // the UA strip — the user can complete OAuth in Chrome (which Google trusts),
            // then return here to finish the cookie capture from the in-app session.
            IconButton(onClick = {
                runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(currentUrl))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }) {
                Icon(
                    imageVector = Icons.Outlined.OpenInBrowser,
                    contentDescription = stringResource(MR.strings.open_in_browser),
                )
            }
            IconButton(onClick = { navigator.reload() }) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(MR.strings.refresh),
                )
            }
            IconButton(onClick = extractToken) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = stringResource(MR.strings.complete),
                )
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val webClient = remember(loginUrl) {
                object : AccompanistWebViewClient() {
                    override fun onPageStarted(view: AndroidWebView, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        isLoading = true
                    }

                    override fun onPageFinished(view: AndroidWebView, url: String?) {
                        super.onPageFinished(view, url)
                        isLoading = false
                        currentUrl = url ?: loginUrl
                        Logger.d { "TrackerWebViewLogin: page loaded $url" }
                    }
                }
            }

            WebView(
                state = state,
                navigator = navigator,
                modifier = Modifier.fillMaxSize(),
                onCreated = { webView ->
                    webView.setDefaultSettings()
                    // Trackers often redirect through Google OAuth ("Sign in with Google" on
                    // NovelUpdates / NovelList), and Google's `disallowed_useragent` check
                    // (Error 403) refuses any UA containing the Android WebView markers
                    // (`; wv)` and the "Version/x.x" tag). Strip those so the OAuth page
                    // sees a plain Chrome UA. Affects only this login activity; the global
                    // setDefaultSettings used by source extensions is left alone so
                    // Cloudflare interceptors and per-source UA logic still work normally.
                    webView.settings.userAgentString = sanitizeUserAgentForOAuth(webView.settings.userAgentString)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                },
                client = webClient,
            )

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                val instructions = when (trackerId) {
                    TrackManager.NOVEL_UPDATES -> stringResource(MR.strings.tracker_novelupdates_login_help)
                    TrackManager.NOVEL_LIST -> stringResource(MR.strings.tracker_novellist_login_help)
                    else -> stringResource(MR.strings.tracker_webview_login_help)
                }
                Text(
                    text = instructions,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * Strips the markers Google's OAuth `disallowed_useragent` check uses to detect embedded
 * browsers (HTTP `Error 403: disallowed_useragent`). After this rewrite the UA reads as
 * vanilla Chrome on Android and Google's sign-in page accepts it. Setting the WebView's
 * `userAgentString` updates BOTH the request header and `navigator.userAgent`.
 *
 * The strips:
 *  - `; wv)` — Android System WebView marker since ~Lollipop.
 *  - `Version/<x.x>` — Chrome WebView's secondary version tag (vanilla Chrome omits it).
 *  - `wv ` — older WebView marker variant.
 *  - In-app browser fingerprints (FBAN/FBAV/FB_IAB/Instagram/Line/KAKAOTALK) — defensive,
 *    in case Google later expands its detection list.
 *
 * Falls back to the input verbatim if it's null/blank (e.g. unit tests, headless contexts).
 */
private fun sanitizeUserAgentForOAuth(userAgent: String?): String {
    if (userAgent.isNullOrBlank()) return userAgent.orEmpty()
    return userAgent
        .replace("; wv)", ")")
        .replace(" wv ", " ")
        .replace(Regex("Version/[\\d.]+\\s*"), "")
        .replace(Regex("\\s*(FBAN|FBAV|FB_IAB|Instagram|Line|KAKAOTALK|MicroMessenger)/[^\\s;]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private suspend fun extractTokenFromCookies(trackerId: Long, currentUrl: String): String? {
    return withContext(Dispatchers.Main) {
        val cookieManager = CookieManager.getInstance()
        when (trackerId) {
            TrackManager.NOVEL_UPDATES -> {
                val cookies = cookieManager.getCookie("https://www.novelupdates.com")
                Logger.d { "NovelUpdates cookies: $cookies" }
                if (cookies != null && cookies.contains("wordpress_logged_in")) {
                    cookies
                } else {
                    Logger.w { "NovelUpdates login cookie not found" }
                    null
                }
            }
            TrackManager.NOVEL_LIST -> {
                val cookies = cookieManager.getCookie("https://www.novellist.co")
                if (cookies == null) {
                    Logger.w { "NovelList cookies header missing" }
                    return@withContext null
                }

                // NovelList runs Supabase Auth. When the encoded session exceeds the
                // ~3.18 KB per-cookie limit (Google OAuth tokens routinely do — the live
                // cookie measures ~4.6 KB joined) Supabase chunks it into `novellist.0`,
                // `novellist.1`, … with the literal `base64-` prefix on chunk 0 only.
                // Older sessions that still fit come back as a single `novellist=` cookie.
                // Match both shapes, sort by chunk index, then concatenate before decoding.
                // Pass the decoded JSON through verbatim — `NovelList.login` reads the
                // `refresh_token` out of it so we can renew the access token in-app.
                val chunkRegex = Regex("(?:^|;\\s*)novellist(?:\\.(\\d+))?=([^;]+)")
                val joined = chunkRegex.findAll(cookies)
                    .map { (it.groupValues[1].toIntOrNull() ?: -1) to it.groupValues[2] }
                    .sortedBy { it.first }
                    .joinToString(separator = "") { it.second }

                if (joined.isEmpty()) {
                    Logger.w { "NovelList cookie not found (looked for novellist[.N]=)" }
                    return@withContext null
                }

                try {
                    if (joined.startsWith("base64-")) {
                        val base64Part = joined.removePrefix("base64-")
                        val decodedBytes = android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT)
                        String(decodedBytes, Charsets.UTF_8)
                    } else {
                        joined
                    }
                } catch (e: Exception) {
                    Logger.e(e) { "Failed to decode NovelList session payload" }
                    joined
                }
            }
            else -> null
        }
    }
}
