package com.personal.watermarkcamera.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.personal.watermarkcamera.domain.WatermarkPreset
import com.personal.watermarkcamera.domain.WatermarkSettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 用户自定义模板（「我的模板」）的持久化：SharedPreferences 单键 JSON 数组。
 * 每条保存一份完整的水印设置快照（含 x/y 位置），应用时整体还原。
 * 数量不设上限，界面内滚动；新增/更新/改名/删除都立即落盘。
 */
class WatermarkPresetStore(context: Context) {

    private val prefs = context.getSharedPreferences("watermark_presets", Context.MODE_PRIVATE)

    var presets by mutableStateOf(load())
        private set

    private fun load(): List<WatermarkPreset> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name").trim()
                    val settings = item.optJSONObject("settings") ?: continue
                    if (id.isBlank() || name.isBlank()) continue
                    add(
                        WatermarkPreset(
                            id = id,
                            name = name,
                            createdAt = item.optLong("createdAt", 0L),
                            settings = WatermarkSettingsJson.decode(settings)
                        )
                    )
                }
            }.sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject().apply {
                    put("id", preset.id)
                    put("name", preset.name)
                    put("createdAt", preset.createdAt)
                    put("settings", WatermarkSettingsJson.encode(preset.settings))
                }
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    /** 默认名字：按序号生成并避开重名，用户可在保存对话框里改。 */
    fun defaultName(): String {
        var index = presets.size + 1
        var candidate = "我的模板 $index"
        while (presets.any { it.name == candidate }) {
            index++
            candidate = "我的模板 $index"
        }
        return candidate
    }

    /** 另存为新模板；返回新建的模板（供界面记住「当前模板」）。 */
    fun saveNew(name: String, settings: WatermarkSettings): WatermarkPreset {
        val preset = WatermarkPreset(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { defaultName() },
            createdAt = System.currentTimeMillis(),
            settings = settings
        )
        presets = listOf(preset) + presets
        save()
        return preset
    }

    /** 用当前设置覆盖指定模板（名字与创建时间不变）。 */
    fun updateSettings(id: String, settings: WatermarkSettings) {
        presets = presets.map { if (it.id == id) it.copy(settings = settings) else it }
        save()
    }

    fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        presets = presets.map { if (it.id == id) it.copy(name = clean) else it }
        save()
    }

    fun delete(id: String) {
        presets = presets.filterNot { it.id == id }
        save()
    }

    private companion object {
        const val KEY = "presets"
    }
}
