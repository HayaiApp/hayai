package yokai.presentation.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import com.google.accompanist.themeadapter.material3.createMdc3Theme
import java.util.concurrent.ConcurrentHashMap

// createMdc3Theme walks ~20 theme attrs per call; per-cell ComposeViews were paying it on every
// visible cell. Result only varies by (theme hash, uiMode), so cache process-wide.
private val colourSchemeCache = ConcurrentHashMap<Long, ColorScheme>()

private fun cachedColourScheme(context: Context): ColorScheme {
    val key = (context.theme.hashCode().toLong() shl 32) or
        (context.resources.configuration.uiMode.toLong() and 0xFFFFFFFFL)
    return colourSchemeCache.getOrPut(key) {
        @Suppress("DEPRECATION")
        val theme = createMdc3Theme(
            context = context,
            layoutDirection = LayoutDirection.Rtl,
            setTextColors = true,
            readTypography = false,
        )
        theme.component1()!!
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YokaiTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colourScheme = remember(context.theme, context.resources.configuration.uiMode) {
        cachedColourScheme(context)
    }

    // Observe via the central accessor so toggling reduced motion recomposes the tree.
    val reducedMotion by ReducedMotion.changes().collectAsState(initial = ReducedMotion.isEnabled())

    MaterialExpressiveTheme(colorScheme = colourScheme, typography = AppTypography) {
        CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
            content()
        }
    }
}
