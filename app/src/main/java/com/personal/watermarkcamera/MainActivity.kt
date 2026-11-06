package com.personal.watermarkcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.personal.watermarkcamera.data.ThemeMode
import com.personal.watermarkcamera.ui.MainScreen
import com.personal.watermarkcamera.ui.NavigationBarStyle

private val Accent = Color(0xFFFF6B35)

/** 深色配色，保持拍摄页与弹窗原有的深灰观感。 */
private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceContainerHigh = Color(0xFF181818),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF3C3C3C)
)

/** 浅色配色：导航、设置页、弹窗、图片页使用；拍摄预览与水印不受影响。 */
private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFF7F7F8),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFEDEDF0),
    onSurfaceVariant = Color(0xFF5F6368),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    outline = Color(0xFFB6B6BC),
    outlineVariant = Color(0xFFD8D8DE)
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as WatermarkApplication).container
        setContent {
            val mode = container.appSettings.themeMode
            val dark = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val scheme = if (dark) DarkColors else LightColors
            val uiScale = container.appSettings.uiFontScale
            MaterialTheme(colorScheme = scheme) {
                // 界面字号：整体放大/缩小字号（sp），布局 dp 不变；水印字号不受影响
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, base.fontScale * uiScale)
                ) {
                    // 导航栏跟主题；状态栏由各页面按自身背景覆盖
                    NavigationBarStyle(lightIcons = !dark, color = scheme.surface)
                    MainScreen(container, dark)
                }
            }
        }
    }
}

