package dev.ahmedmohamed.hayai.novel.lookup

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import dev.ahmedmohamed.hayai.novel.lookup.NovelLookupAppIntent.GoogleTranslateProcessText
import dev.ahmedmohamed.hayai.novel.lookup.NovelLookupAppIntent.GoogleTranslateSend
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.toast
import kotlin.math.roundToInt

class NovelLookupLauncher(
    private val activity: AppCompatActivity,
    private val showWebSheet: (Uri, String) -> Boolean,
) {
    fun launch(request: NovelLookupRequest): NovelLookupLaunchResult {
        val route = NovelLookupRouter.route(request)
        if (route is NovelLookupRoute.AppThenWeb && launchTranslateApp(route, request.query)) {
            return NovelLookupLaunchResult.ExternalApp
        }
        return launchWeb(Uri.parse(route.webUrl.toString()), request.query)
    }

    private fun launchTranslateApp(route: NovelLookupRoute.AppThenWeb, query: NovelSelectionQuery): Boolean =
        route.appIntents.any { intentType ->
            val intent =
                when (intentType) {
                    GoogleTranslateProcessText ->
                        Intent(Intent.ACTION_PROCESS_TEXT)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_PROCESS_TEXT, query.value)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

                    GoogleTranslateSend ->
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, query.value)
                }.setPackage(GOOGLE_TRANSLATE_PACKAGE)
            try {
                activity.startActivity(intent)
                true
            } catch (_: RuntimeException) {
                false
            }
        }

    @Suppress("DEPRECATION")
    private fun launchWeb(uri: Uri, query: NovelSelectionQuery): NovelLookupLaunchResult {
        val packageName =
            try {
                CustomTabsClient.getPackageName(activity, null)
            } catch (_: RuntimeException) {
                null
            }
        if (packageName != null) {
            val metrics = activity.resources.displayMetrics
            val density = metrics.density
            val initialHeight = (metrics.heightPixels * 0.70f).roundToInt()
            val desiredWidthDp = (metrics.widthPixels / density * 0.42f).coerceIn(360f, 560f)
            val initialWidth = (desiredWidthDp * density).roundToInt().coerceAtMost(metrics.widthPixels)
            try {
                val customTab =
                    CustomTabsIntent.Builder()
                        .setDefaultColorSchemeParams(
                            CustomTabColorSchemeParams.Builder()
                                .setToolbarColor(activity.getResourceColor(R.attr.colorSurfaceContainer))
                                .build(),
                        )
                        .setShowTitle(true)
                        .setInitialActivityHeightPx(initialHeight, CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE)
                        .setInitialActivityWidthPx(initialWidth)
                        .setActivitySideSheetBreakpointDp(600)
                        .setActivitySideSheetPosition(CustomTabsIntent.ACTIVITY_SIDE_SHEET_POSITION_END)
                        .setActivitySideSheetDecorationType(CustomTabsIntent.ACTIVITY_SIDE_SHEET_DECORATION_TYPE_SHADOW)
                        .setActivitySideSheetRoundedCornersPosition(CustomTabsIntent.ACTIVITY_SIDE_SHEET_ROUNDED_CORNERS_POSITION_TOP)
                        .setToolbarCornerRadiusDp(16)
                        .setCloseButtonPosition(CustomTabsIntent.CLOSE_BUTTON_POSITION_END)
                        .build()
                customTab.intent.`package` = packageName
                customTab.intent.data = uri
                activity.startActivityForResult(customTab.intent, CUSTOM_TAB_REQUEST_CODE, customTab.startAnimationBundle)
                return NovelLookupLaunchResult.CustomTab
            } catch (_: RuntimeException) {
                Unit
            }
        }
        return if (showWebSheet(uri, query.value)) {
            NovelLookupLaunchResult.WebSheet
        } else {
            activity.toast(R.string.hayai_novel_lookup_browser_unavailable)
            NovelLookupLaunchResult.Unavailable
        }
    }

    private companion object {
        const val GOOGLE_TRANSLATE_PACKAGE = "com.google.android.apps.translate"
        const val CUSTOM_TAB_REQUEST_CODE = 0x4859
    }
}
