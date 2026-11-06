package com.personal.watermarkcamera.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 状态栏样式：图标明暗 + 底色。拍摄页要压黑背景配浅色图标，其他页面跟随主题，
 * 所以由各页面自己调用（切换页面时重新生效）。
 */
@Composable
fun StatusBarStyle(lightIcons: Boolean, color: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = color.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightIcons
    }
}

/** 导航栏样式：底部 Tab 的底色与图标明暗由主题决定，全局设置一次即可。 */
@Composable
fun NavigationBarStyle(lightIcons: Boolean, color: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as Activity).window
        window.navigationBarColor = color.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = lightIcons
    }
}

