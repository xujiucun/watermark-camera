package com.personal.watermarkcamera.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** 主题模式：跟随系统 / 浅色 / 深色。 */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromId(raw: String?): ThemeMode = entries.firstOrNull { it.id == raw } ?: SYSTEM
    }
}

/**
 * App 界面字号（只影响界面文字：导航、设置页、弹窗、图片页等），与水印字号互不影响。
 * 用 fontScale 乘数实现，sp 文字整体缩放、dp 布局不变。
 */
enum class UiFontScale(val label: String, val scale: Float) {
    SMALL("小", 0.9f),
    STANDARD("标准", 1.0f),
    LARGE("大", 1.15f);

    companion object {
        /** 取与给定 scale 最接近的一档，用于设置页高亮当前档位。 */
        fun nearest(scale: Float): UiFontScale =
            entries.minByOrNull { abs(it.scale - scale) } ?: STANDARD

        fun fromValue(scale: Float): UiFontScale = entries.firstOrNull { it.scale == scale } ?: STANDARD
    }
}

/**
 * 拍照闪光模式：自动测光 / 拍摄瞬间强制闪光 / 关闭。
 * 只作用于 [androidx.camera.core.ImageCapture] 的 flashMode，预览不改手电筒。
 */
enum class FlashMode(val id: String, val label: String) {
    AUTO("auto", "自动"),
    ON("on", "强制闪光"),
    OFF("off", "关闭");

    fun next(): FlashMode = when (this) {
        AUTO -> ON
        ON -> OFF
        OFF -> AUTO
    }

    companion object {
        fun fromId(raw: String?): FlashMode = entries.firstOrNull { it.id == raw } ?: AUTO
    }
}

/**
 * App 级设置：主题 + 界面字号 + 闪光模式。用 Compose 状态持有，改完立即重组并写入 SharedPreferences。
 */
class AppSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(ThemeMode.fromId(prefs.getString(KEY_THEME, null)))
        private set

    /** 上次选择的闪光模式；启动时恢复，切换镜头时由拍摄页重置为自动。 */
    var flashMode by mutableStateOf(FlashMode.fromId(prefs.getString(KEY_FLASH_MODE, null)))
        private set

    /** 界面文字缩放倍数，默认 1.0（标准）。 */
    var uiFontScale by mutableStateOf(
        prefs.getFloat(KEY_UI_FONT_SCALE, UiFontScale.STANDARD.scale)
    )
        private set

    /** 分享时是否剥离 GPS 位置信息。默认开启（更安全），用户可在图片页直接切换。 */
    var stripLocationOnShare by mutableStateOf(
        prefs.getBoolean(KEY_STRIP_LOCATION_ON_SHARE, true)
    )
        private set

    fun updateStripLocationOnShare(strip: Boolean) {
        if (stripLocationOnShare == strip) return
        stripLocationOnShare = strip
        prefs.edit().putBoolean(KEY_STRIP_LOCATION_ON_SHARE, strip).apply()
    }

    fun updateTheme(mode: ThemeMode) {
        if (themeMode == mode) return
        themeMode = mode
        prefs.edit().putString(KEY_THEME, mode.id).apply()
    }

    fun updateUiFontScale(scale: Float) {
        if (uiFontScale == scale) return
        uiFontScale = scale
        prefs.edit().putFloat(KEY_UI_FONT_SCALE, scale).apply()
    }

    fun updateFlashMode(mode: FlashMode) {
        if (flashMode == mode) return
        flashMode = mode
        prefs.edit().putString(KEY_FLASH_MODE, mode.id).apply()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_UI_FONT_SCALE = "ui_font_scale"
        const val KEY_FLASH_MODE = "flash_mode"
        const val KEY_STRIP_LOCATION_ON_SHARE = "strip_location_on_share"
    }
}
