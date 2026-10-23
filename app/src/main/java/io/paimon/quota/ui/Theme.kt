package io.paimon.quota.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 纯黑白单色调色板：只有明度层次，没有色相。 */
object Mono {
    // 浅色
    val LightBg = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurface2 = Color(0xFFF2F2F2)
    val LightBorder = Color(0xFFD9D9D9)
    val LightText = Color(0xFF111111)
    val LightText2 = Color(0xFF5A5A5A)
    val LightText3 = Color(0xFF8C8C8C)
    val LightAccent = Color(0xFF111111)
    val LightOnAccent = Color(0xFFFFFFFF)

    // 深色
    val DarkBg = Color(0xFF000000)
    val DarkSurface = Color(0xFF0E0E0E)
    val DarkSurface2 = Color(0xFF1A1A1A)
    val DarkBorder = Color(0xFF333333)
    val DarkText = Color(0xFFF5F5F5)
    val DarkText2 = Color(0xFFA8A8A8)
    val DarkText3 = Color(0xFF7A7A7A)
    val DarkAccent = Color(0xFFFFFFFF)
    val DarkOnAccent = Color(0xFF000000)
}

private val LightScheme = lightColorScheme(
    primary = Mono.LightAccent,
    onPrimary = Mono.LightOnAccent,
    secondary = Mono.LightText2,
    onSecondary = Mono.LightBg,
    background = Mono.LightBg,
    onBackground = Mono.LightText,
    surface = Mono.LightSurface,
    onSurface = Mono.LightText,
    surfaceVariant = Mono.LightSurface2,
    onSurfaceVariant = Mono.LightText2,
    outline = Mono.LightBorder,
    outlineVariant = Mono.LightBorder,
)

private val DarkScheme = darkColorScheme(
    primary = Mono.DarkAccent,
    onPrimary = Mono.DarkOnAccent,
    secondary = Mono.DarkText2,
    onSecondary = Mono.DarkBg,
    background = Mono.DarkBg,
    onBackground = Mono.DarkText,
    surface = Mono.DarkSurface,
    onSurface = Mono.DarkText,
    surfaceVariant = Mono.DarkSurface2,
    onSurfaceVariant = Mono.DarkText2,
    outline = Mono.DarkBorder,
    outlineVariant = Mono.DarkBorder,
)

enum class ThemeMode(val key: String, val label: String) {
    AUTO("auto", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

@Composable
fun QuotaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
