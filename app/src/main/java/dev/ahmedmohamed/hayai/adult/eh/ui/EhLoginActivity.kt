package dev.ahmedmohamed.hayai.adult.eh.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import dev.ahmedmohamed.hayai.adult.eh.presentation.EhTextResolver
import dev.ahmedmohamed.hayai.adult.eh.presentation.localizedMessage
import dev.ahmedmohamed.hayai.adult.eh.session.AndroidEhCookieStore
import dev.ahmedmohamed.hayai.adult.eh.session.EhLoginCookieParser
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionMutationResult
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionStore
import dev.ahmedmohamed.hayai.adult.eh.session.EhSessionVerifier
import dev.ahmedmohamed.hayai.adult.eh.session.EhVerificationResult
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.webview.BaseWebViewActivity
import eu.kanade.tachiyomi.util.system.WebViewClientCompat
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.setUserAgent
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy

class EhLoginActivity : BaseWebViewActivity() {
    private val sessionStore by injectLazy<EhSessionStore>()
    private val network by injectLazy<NetworkHelper>()
    private val text by injectLazy<EhTextResolver>()
    private val webCookies by lazy(::AndroidEhCookieStore)
    private val verifier by lazy { EhSessionVerifier(network.client) }

    private var manualIgneous: String? = null
    private var verificationInFlight = false
    private var resultCommitted = false
    private var lastOutcome = EhLoginOutcome.Cancelled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.hayai_eh_login_title)
        if (!WebViewUtil.supportsWebView(this)) {
            finishWith(EhLoginOutcome.InvalidCredentials, getString(R.string.hayai_eh_webview_required))
            return
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webview, true)
        }
        binding.webview.setUserAgent(network.defaultUserAgent)
        binding.toolbar.subtitle = getString(R.string.hayai_eh_login_instruction)
        binding.toolbar.navigationContentDescription = getString(R.string.hayai_eh_navigate_up)
        binding.toolbar.setNavigationOnClickListener { finishWith(lastOutcome) }
        binding.swipeRefresh.isEnabled = true

        binding.webview.webViewClient =
            object : WebViewClientCompat() {
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?,
                ) {
                    binding.toolbar.subtitle = getString(R.string.hayai_eh_login_loading)
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    binding.swipeRefresh.isRefreshing = false
                    inspectPage(view, url)
                }

                override fun onReceivedErrorCompat(
                    view: WebView,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String,
                    isMainFrame: Boolean,
                ) {
                    if (isMainFrame) {
                        binding.toolbar.subtitle = description ?: getString(R.string.hayai_eh_login_page_failed)
                    }
                }
            }

        if (savedInstanceState == null) {
            webCookies.clearEhDomains()
            binding.webview.loadUrl(LOGIN_URL)
        } else {
            binding.webview.restoreState(savedInstanceState)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, ACTION_RECHECK, 0, R.string.hayai_eh_recheck).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, ACTION_ALTERNATE, 1, R.string.hayai_eh_alternate_login).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, ACTION_IGNEOUS, 2, R.string.hayai_eh_set_igneous).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, ACTION_SIMPLIFY, 3, R.string.hayai_eh_simplify_login).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, ACTION_CANCEL, 4, R.string.cancel).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean =
        when (item.itemId) {
            ACTION_RECHECK -> {
                binding.webview.loadUrl(EXH_URL)
                true
            }
            ACTION_ALTERNATE -> {
                binding.webview.loadUrl(ALTERNATE_LOGIN_URL)
                true
            }
            ACTION_IGNEOUS -> {
                showIgneousDialog()
                true
            }
            ACTION_SIMPLIFY -> {
                binding.webview.evaluateJavascript(SIMPLIFY_LOGIN_JS, null)
                true
            }
            ACTION_CANCEL -> {
                finishWith(lastOutcome)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.webview.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        if (!resultCommitted) setOutcomeResult(lastOutcome, null)
        super.finish()
    }

    override fun onDestroy() {
        binding.webview.stopLoading()
        binding.webview.webViewClient = android.webkit.WebViewClient()
        binding.webview.destroy()
        super.onDestroy()
    }

    private fun inspectPage(
        view: WebView,
        url: String,
    ) {
        val host = Uri.parse(url).host.orEmpty()
        view.evaluateJavascript(CLOUDFLARE_CHECK_JS) { rawResult ->
            if (rawResult == "true") {
                lastOutcome = EhLoginOutcome.Cloudflare
                binding.toolbar.subtitle = getString(R.string.hayai_eh_cloudflare_detected)
                return@evaluateJavascript
            }
            when {
                host.equals("forums.e-hentai.org", ignoreCase = true) -> continueFromForum(url)
                host.equals("exhentai.org", ignoreCase = true) -> stageAndVerify()
                else -> binding.toolbar.subtitle = getString(R.string.hayai_eh_complete_forum_login)
            }
        }
    }

    private fun continueFromForum(url: String) {
        val cookies = webCookies.get(url)
        val parsed = EhLoginCookieParser.parse(cookies).getOrNull()
        if (parsed != null && !parsed.memberId.isNullOrBlank() && !parsed.passHash.isNullOrBlank()) {
            binding.toolbar.subtitle = getString(R.string.hayai_eh_forum_login_accepted)
            binding.webview.loadUrl(EXH_URL)
        } else {
            binding.toolbar.subtitle = getString(R.string.hayai_eh_enter_valid_credentials)
        }
    }

    private fun stageAndVerify() {
        if (verificationInFlight) return
        val result =
            sessionStore.stage(
                cookieHeaders =
                    listOf(
                        webCookies.get(AndroidEhCookieStore.FORUMS_ORIGIN),
                        webCookies.get(AndroidEhCookieStore.EH_ORIGIN),
                        webCookies.get(AndroidEhCookieStore.EXH_ORIGIN),
                    ),
                manualIgneous = manualIgneous,
            )
        if (result is EhSessionMutationResult.Failure) {
            lastOutcome = EhLoginOutcome.InvalidCredentials
            binding.toolbar.subtitle = getString(R.string.hayai_eh_set_igneous_suffix, result.reason.localizedMessage(text))
            return
        }

        verificationInFlight = true
        binding.toolbar.subtitle = getString(R.string.hayai_eh_verifying_credentials)
        lifecycleScope.launch {
            when (val verification = verifier.verify(sessionStore)) {
                EhVerificationResult.Verified -> {
                    when (val committed = sessionStore.markVerified()) {
                        is EhSessionMutationResult.Success -> finishWith(EhLoginOutcome.Success)
                        is EhSessionMutationResult.Failure -> {
                            lastOutcome = EhLoginOutcome.InvalidCredentials
                            binding.toolbar.subtitle = committed.reason.localizedMessage(text)
                        }
                    }
                }
                EhVerificationResult.Cloudflare -> {
                    lastOutcome = EhLoginOutcome.Cloudflare
                    binding.toolbar.subtitle = getString(R.string.hayai_eh_login_cloudflare_failure)
                }
                EhVerificationResult.InvalidCredentials -> {
                    sessionStore.markInvalid()
                    lastOutcome = EhLoginOutcome.InvalidCredentials
                    binding.toolbar.subtitle = dev.ahmedmohamed.hayai.adult.eh.session.EhSessionFailureReason.CredentialsRejected.localizedMessage(text)
                }
                is EhVerificationResult.NetworkFailure -> {
                    binding.toolbar.subtitle = getString(R.string.hayai_eh_recheck_suffix, getString(R.string.hayai_eh_login_network_failure))
                }
            }
            verificationInFlight = false
        }
    }

    private fun showIgneousDialog() {
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                hint = getString(R.string.hayai_eh_igneous_hint)
                setText(manualIgneous)
            }
        materialAlertDialog()
            .setTitle(R.string.hayai_eh_custom_igneous)
            .setMessage(R.string.hayai_eh_custom_igneous_message)
            .setView(input)
            .setPositiveButton(R.string.hayai_eh_save_and_recheck) { _, _ ->
                manualIgneous = input.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
                binding.webview.loadUrl(EXH_URL)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun finishWith(
        outcome: EhLoginOutcome,
        message: String? = null,
    ) {
        lastOutcome = outcome
        setOutcomeResult(outcome, message)
        resultCommitted = true
        super.finish()
    }

    private fun setOutcomeResult(
        outcome: EhLoginOutcome,
        message: String?,
    ) {
        setResult(
            outcome.resultCode,
            Intent().apply {
                putExtra(EXTRA_OUTCOME, outcome.name)
                message?.let { putExtra(EXTRA_MESSAGE, it) }
            },
        )
    }

    companion object {
        const val EXTRA_OUTCOME = "hayai.eh.login.outcome"
        const val EXTRA_MESSAGE = "hayai.eh.login.message"

        private const val LOGIN_URL = "https://forums.e-hentai.org/index.php?act=Login&CODE=00"
        private const val ALTERNATE_LOGIN_URL = "https://e-hentai.org/bounce_login.php"
        private const val EXH_URL = "https://exhentai.org/"
        private const val ACTION_RECHECK = 0x484101
        private const val ACTION_ALTERNATE = 0x484102
        private const val ACTION_IGNEOUS = 0x484103
        private const val ACTION_SIMPLIFY = 0x484104
        private const val ACTION_CANCEL = 0x484105

        private const val CLOUDFLARE_CHECK_JS =
            "(function(){return !!document.querySelector('[name=cf-turnstile-response], #challenge-form') || " +
                "document.documentElement.innerHTML.indexOf('/cdn-cgi/') >= 0;})()"

        private const val SIMPLIFY_LOGIN_JS =
            "(function(){['#gfooter','.copyright','td[width=\"40%\"]'].forEach(function(s){" +
                "var e=document.querySelector(s);if(e)e.style.display='none';});})()"

        fun newIntent(context: Context): Intent =
            Intent(context, EhLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

enum class EhLoginOutcome(
    val resultCode: Int,
) {
    Success(android.app.Activity.RESULT_OK),
    Cancelled(android.app.Activity.RESULT_CANCELED),
    Cloudflare(android.app.Activity.RESULT_FIRST_USER),
    InvalidCredentials(android.app.Activity.RESULT_FIRST_USER + 1),
}
