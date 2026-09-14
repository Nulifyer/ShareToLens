package dev.nulifyer.sharetolens.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Paper = Color(0xFFF8F9FF)
private val Ink = Color(0xFF191B23)
private val Iris = Color(0xFF4D5BB8)
private val IrisContainer = Color(0xFFDFE1FF)
private val PrivacyTeal = Color(0xFF006B5F)
private val Night = Color(0xFF11131A)

private val LightColors = lightColorScheme(
    primary = Iris,
    onPrimary = Color.White,
    primaryContainer = IrisContainer,
    onPrimaryContainer = Color(0xFF101B60),
    secondary = PrivacyTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9DF2E2),
    onSecondaryContainer = Color(0xFF00201C),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE3E2EC),
    onSurfaceVariant = Color(0xFF46464F),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBC3FF),
    onPrimary = Color(0xFF202D78),
    primaryContainer = Color(0xFF36438F),
    onPrimaryContainer = Color(0xFFDFE1FF),
    secondary = Color(0xFF81D5C6),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005047),
    onSecondaryContainer = Color(0xFF9DF2E2),
    background = Night,
    onBackground = Color(0xFFE3E2EA),
    surface = Night,
    onSurface = Color(0xFFE3E2EA),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    error = Color(0xFFFFB4AB),
)

@Composable
internal fun ShareToLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
