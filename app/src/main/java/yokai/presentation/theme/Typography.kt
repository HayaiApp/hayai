package yokai.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.tachiyomi.R

val Outfit = FontFamily(
    Font(R.font.outfit_light, FontWeight.Light),
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

// Default M3 typography re-pointed to Outfit; every other style attr stays at its M3 default.
val AppTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Outfit),
        displayMedium = displayMedium.copy(fontFamily = Outfit),
        displaySmall = displaySmall.copy(fontFamily = Outfit),
        headlineLarge = headlineLarge.copy(fontFamily = Outfit),
        headlineMedium = headlineMedium.copy(fontFamily = Outfit),
        headlineSmall = headlineSmall.copy(fontFamily = Outfit),
        titleLarge = titleLarge.copy(fontFamily = Outfit),
        titleMedium = titleMedium.copy(fontFamily = Outfit),
        titleSmall = titleSmall.copy(fontFamily = Outfit),
        bodyLarge = bodyLarge.copy(fontFamily = Outfit),
        bodyMedium = bodyMedium.copy(fontFamily = Outfit),
        bodySmall = bodySmall.copy(fontFamily = Outfit),
        labelLarge = labelLarge.copy(fontFamily = Outfit),
        labelMedium = labelMedium.copy(fontFamily = Outfit),
        labelSmall = labelSmall.copy(fontFamily = Outfit),
    )
}

val Typography.header: TextStyle
    @Composable
    get() = bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
