package dev.ahmedmohamed.hayai.novel.lookup

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.HayaiNovelLookupSheetBinding
import eu.kanade.tachiyomi.util.system.defaultBrowserPackageName
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.widget.E2EBottomSheetDialog
import kotlin.math.roundToInt

class NovelLookupWebSheet(
    private val activity: AppCompatActivity,
    initialUri: Uri,
    query: String,
    private val onDismissed: () -> Unit,
) : E2EBottomSheetDialog<HayaiNovelLookupSheetBinding>(activity) {
    private var destroyed = false
    private var failedMainFrame = false
    private var initialUri: Uri? = initialUri

    override fun createBinding(inflater: LayoutInflater) = HayaiNovelLookupSheetBinding.inflate(inflater)

    init {
        require(initialUri.isAllowedWebUri())
        binding.toolbar.title = query
        configureToolbar()
        configureWebView()
        binding.retry.setOnClickListener { this.initialUri?.let(::load) }
        setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && handleBack()
        }
        setOnDismissListener {
            destroyWebView()
            onDismissed()
        }
        load(initialUri)
    }

    override fun onStart() {
        super.onStart()
        if (!destroyed) binding.webview.onResume()
        val height = (activity.resources.displayMetrics.heightPixels * 0.70f).roundToInt()
        binding.root.updateLayoutParamsHeight(height)
        sheetBehavior.peekHeight = height
        sheetBehavior.skipCollapsed = true
        sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onStop() {
        if (!destroyed) binding.webview.onPause()
        super.onStop()
    }

    private fun configureToolbar() {
        binding.toolbar.setNavigationContentDescription(R.string.back)
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        binding.toolbar.menu.add(0, MENU_EXTERNAL, 0, R.string.open_in_browser)
            .setIcon(R.drawable.ic_open_in_new_24dp)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        binding.toolbar.menu.add(0, MENU_CLOSE, 1, R.string.close)
            .setIcon(R.drawable.ic_close_24dp)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EXTERNAL -> openExternally()
                MENU_CLOSE -> dismiss()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webview, true)
        }
        binding.webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        binding.webview.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    blockUnsafeNavigation(request.url, request.isForMainFrame)

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    blockUnsafeNavigation(Uri.parse(url), true)

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    failedMainFrame = false
                    binding.loading.isVisible = true
                    binding.errorState.isVisible = false
                    binding.webview.isVisible = true
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    binding.loading.isVisible = false
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showError()
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) showError()
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    showError()
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    activity.toast(R.string.hayai_novel_lookup_renderer_failed)
                    dismiss()
                    return true
                }
            }
    }

    private fun load(uri: Uri) {
        if (destroyed || !uri.isAllowedWebUri()) return
        failedMainFrame = false
        binding.webview.loadUrl(uri.toString())
    }

    private fun showError() {
        if (destroyed || failedMainFrame) return
        failedMainFrame = true
        binding.loading.isVisible = false
        binding.webview.isVisible = false
        binding.errorState.isVisible = true
    }

    private fun blockUnsafeNavigation(uri: Uri, mainFrame: Boolean): Boolean {
        val blocked = !uri.isAllowedWebUri()
        if (blocked && mainFrame) activity.toast(R.string.hayai_novel_lookup_blocked_navigation)
        return blocked
    }

    private fun handleBack(): Boolean {
        when {
            binding.errorState.isVisible -> dismiss()
            binding.webview.canGoBack() -> binding.webview.goBack()
            else -> dismiss()
        }
        return true
    }

    private fun openExternally() {
        val uri = binding.webview.url?.let(Uri::parse)?.takeIf { it.isAllowedWebUri() } ?: initialUri ?: return
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            activity.defaultBrowserPackageName()?.let(::setPackage)
        }
        try {
            activity.startActivity(intent)
        } catch (_: RuntimeException) {
            activity.toast(R.string.hayai_novel_lookup_browser_unavailable)
        }
    }

    private fun destroyWebView() {
        if (destroyed) return
        destroyed = true
        binding.webview.stopLoading()
        binding.webview.webChromeClient = null
        binding.webview.webViewClient = WebViewClient()
        binding.webview.loadUrl("about:blank")
        binding.webview.clearHistory()
        (binding.webview.parent as? ViewGroup)?.removeView(binding.webview)
        binding.webview.destroy()
        binding.toolbar.title = null
        initialUri = null
        CookieManager.getInstance().flush()
    }

    private fun Uri.isAllowedWebUri() = scheme.equals("https", ignoreCase = true) && !host.isNullOrBlank()

    private fun android.view.View.updateLayoutParamsHeight(height: Int) {
        layoutParams = layoutParams.apply { this.height = height }
    }

    companion object {
        const val MENU_EXTERNAL = 1
        const val MENU_CLOSE = 2
    }
}
