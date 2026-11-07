package com.cablebee.assistant.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import com.cablebee.assistant.R

// Light palette matching Flutter AppTheme
val Bg0 = Color(0xFFFFFFFF)
val Bg1 = Color(0xFFF5F5F5)
val Bg2 = Color(0xFFEEEEEE)
val Bg3 = Color(0xFFDDDDDD)

val Primary = Color(0xFF00957A)
val Secondary = Color(0xFF1565C0)
val Warning = Color(0xFFE65100)
val Danger = Color(0xFFB71C1C)
val Success = Color(0xFF2E7D32)

val TextPrimary = Color(0xFF111111)
val TextSecondary = Color(0xFF444444)
val TextMuted = Color(0xFF888888)

// ── Typography ────────────────────────────────────────────────────────────────
// 与 Flutter 版一致：标题/按钮用 SpaceMono，正文/终端用 JetBrainsMono（google_fonts 同款字体，本地打包）

val SpaceMono = FontFamily(
    Font(R.font.space_mono, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val JetBrainsMono = FontFamily(
    // JetBrains Mono 是可变字体，按字重取对应实例
    Font(
        R.font.jetbrains_mono,
        FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        R.font.jetbrains_mono,
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(
        R.font.jetbrains_mono,
        FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))
    ),
    Font(
        R.font.jetbrains_mono,
        FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    ),
)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = Primary.copy(alpha = 0.15f),
    secondary = Secondary,
    onSecondary = Color.White,
    tertiary = Secondary,
    background = Bg0,
    onBackground = TextPrimary,
    surface = Bg1,
    onSurface = TextPrimary,
    surfaceVariant = Bg2,
    onSurfaceVariant = TextSecondary,
    error = Danger,
    onError = Color.White,
    outline = Bg3,
)

@Composable
fun CableBeeTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Bg0.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography(),
        content = content
    )
}
