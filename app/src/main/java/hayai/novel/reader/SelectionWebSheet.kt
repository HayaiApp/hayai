package hayai.novel.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.core.net.toUri
import androidx.core.view.isVisible
import co.touchlab.kermit.Logger
import com.google.android.material.bottomsheet.BottomSheetBehavior
import eu.kanade.tachiyomi.databinding.SelectionWebSheetBinding
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.widget.E2EBottomSheetDialog
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * "Circle to Search"-style bottom sheet that loads a search/translate result for the reader's
 * selected text in an in-app [WebView] — it never switches apps. Hosts a real WebView (not a
 * Custom Tab) so deep-link-eager apps like Google Translate can't intercept the URL, and keeps
 * all link navigation inside the sheet. Opt-out: the overflow fires a plain VIEW intent.
 */
class SelectionWebSheet(
    private val activity: Activity,
    private val url: String,
    private val query: String,
) : E2EBottomSheetDialog<SelectionWebSheetBinding>(activity) {

    private var webView: WebView? = null

    override fun createBinding(inflater: LayoutInflater) = SelectionWebSheetBinding.inflate(inflater)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onStart() {
        super.onStart()
        // ~70% of screen height, expandable to full — matches the "floating sheet over the reader"
        // ask while leaving the reader peeking behind it.
        val screenHeight = activity.window.decorView.height.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels
        sheetBehavior.peekHeight = (screenHeight * 0.7f).toInt()
        sheetBehavior.skipCollapsed = false
        sheetBehavior.isFitToContents = false
        sheetBehavior.halfExpandedRatio = 0.7f
        sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        webView?.onResume()
    }

    init {
        binding.queryText.text = query

        val webView = buildWebView()
        this.webView = webView
        binding.webContainer.addView(
            webView,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )

        binding.closeButton.setOnClickListener { dismiss() }
        binding.overflowButton.setOnClickListener { view -> showOverflow(view) }

        // Tear down the WebView whenever the sheet goes away (close button, drag-down, back, or
        // an external dismiss) so no JS timer / surface leaks past the dialog.
        setOnDismissListener { destroyWebView() }

        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        return WebView(activity).apply {
            // Mirror the source WebView config (WebViewUtil.setDefaultSettings): JS, DOM storage,
            // sane default UA, third-party cookies. Drop multiple-window support — this is a
            // single result surface, not a browser.
            setDefaultSettings()
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val target = request?.url?.toString() ?: return false
                    // Swallow intent:// / market:// / app deep links so a tapped link can't kick
                    // the user out to another app — the whole point of the in-app sheet.
                    if (!target.startsWith("http://") && !target.startsWith("https://")) {
                        return true
                    }
                    // Keep http(s) navigation inside this WebView.
                    view?.loadUrl(target)
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.progress.progress = newProgress
                    binding.progress.isVisible = newProgress < 100
                }
            }
        }
    }

    private fun showOverflow(anchor: View) {
        PopupMenu(activity, anchor).apply {
            menu.add(0, MENU_OPEN_EXTERNAL, 0, activity.getString(MR.strings.novel_selection_open_external))
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_OPEN_EXTERNAL) {
                    openExternally()
                    true
                } else {
                    false
                }
            }
            show()
        }
    }

    /** Opt-out: hand the current URL to a normal browser VIEW intent, then dismiss the sheet. */
    private fun openExternally() {
        val current = webView?.url ?: url
        try {
            activity.openInBrowser(current)
        } catch (t: Throwable) {
            // Fall back to a bare VIEW intent if the Custom-Tabs path is unavailable.
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, current.toUri())) }
        }
        dismiss()
    }

    private fun destroyWebView() {
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        } catch (t: Throwable) {
            Logger.w { "SelectionWebSheet: WebView teardown failed: ${t.message}" }
        }
    }

    override fun onStop() {
        super.onStop()
        // Pause this WebView's rendering/media while the sheet isn't visible. (pauseTimers is
        // global, so it is intentionally NOT called here — it would freeze the reader WebView.)
        webView?.onPause()
    }

    private companion object {
        const val MENU_OPEN_EXTERNAL = 1
    }
}

/** Encodes [query] and shows the in-app result sheet. Used by Define / Translate / Web Search. */
fun showSelectionWebSheet(activity: Activity, url: String, query: String) {
    SelectionWebSheet(activity, url, query).show()
}
