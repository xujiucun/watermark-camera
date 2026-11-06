package com.personal.watermarkcamera.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.personal.watermarkcamera.domain.WatermarkSettings
import org.json.JSONObject

/**
 * 当前水印编辑参数的持久化。用 SharedPreferences 存一份 JSON，重启后保留上次参数与模板。
 * 编解码与「我的模板」共用 [WatermarkSettingsJson]，避免两套字段定义不一致。
 *
 * [settings] 是 Compose 状态：拍摄页弹窗与设置页共用同一份值（尤其是字号），
 * [update] 同时改内存并落盘；拖动水印时先只改内存（persist = false），松手后 [persist] 一次落盘。
 */
class WatermarkSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("watermark_settings", Context.MODE_PRIVATE)

    var settings by mutableStateOf(load())
        private set

    fun load(): WatermarkSettings {
        val raw = prefs.getString(KEY, null) ?: return WatermarkSettings()
        return runCatching {
            WatermarkSettingsJson.decode(JSONObject(raw))
        }.getOrDefault(WatermarkSettings())
    }

    fun update(newSettings: WatermarkSettings, persist: Boolean = true) {
        if (settings == newSettings) return
        settings = newSettings
        if (persist) save(newSettings)
    }

    /** 把当前值写盘（拖动结束时调用一次，避免每个移动事件都做磁盘写入）。 */
    fun persist() {
        save(settings)
    }

    private fun save(settings: WatermarkSettings) {
        prefs.edit().putString(KEY, WatermarkSettingsJson.encode(settings).toString()).apply()
    }

    private companion object {
        const val KEY = "settings"
    }
}
